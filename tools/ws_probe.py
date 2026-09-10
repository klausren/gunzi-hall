#!/usr/bin/env python3
"""打滚子战斗服 WebSocket 探针（零依赖：只用标准库 socket）。

为什么手写而不 import websockets：本机 python 环境没装 websockets，
且沙箱内 pip 装包受限。手写握手 + 帧编解码约 70 行，足够调试用。

用途：在排查"客户端连不上"时，先用它判断**服务端是否正常**（分层定位法）。
服务端 OK → 问题在网络或客户端；服务端不通 → 先修服务端。

用法：
    env no_proxy='*' python3 tools/ws_probe.py                    # 自动探测本机 IP + 发 join
    env no_proxy='*' python3 tools/ws_probe.py --op NEWGAME       # 额外发一条命令
    env no_proxy='*' python3 tools/ws_probe.py --host 10.0.0.5 --player 2 --seat EAST
    env no_proxy='*' python3 tools/ws_probe.py --silent           # 只看握手成败（端口连通性检查）

⚠️ 必须加 `env no_proxy='*'`，否则 python 会走系统 SOCKS 代理导致连接失败。
"""
import argparse
import base64
import json
import os
import socket
import struct
import subprocess
import sys


def local_ip() -> str:
    """取本机局域网 IP；取不到退回 127.0.0.1。"""
    for iface in ("en0", "en1", "en2"):
        try:
            out = subprocess.run(["ipconfig", "getifaddr", iface],
                                 capture_output=True, text=True, timeout=5)
            ip = out.stdout.strip()
            if ip and ip.count(".") == 3:
                return ip
        except Exception:
            pass
    return "127.0.0.1"


def handshake(host: str, port: int, path: str, timeout: float = 8.0):
    """建立 WebSocket 连接，返回 (socket, 状态行, 已读到的剩余字节)。"""
    s = socket.create_connection((host, port), timeout=timeout)
    key = base64.b64encode(os.urandom(16)).decode()
    req = (f"GET {path} HTTP/1.1\r\n"
           f"Host: {host}:{port}\r\n"
           f"Upgrade: websocket\r\n"
           f"Connection: Upgrade\r\n"
           f"Sec-WebSocket-Key: {key}\r\n"
           f"Sec-WebSocket-Version: 13\r\n\r\n")
    s.sendall(req.encode())
    buf = b""
    while b"\r\n\r\n" not in buf:
        chunk = s.recv(4096)
        if not chunk:
            raise RuntimeError("握手期间连接被对端关闭")
        buf += chunk
    head, _, rest = buf.partition(b"\r\n\r\n")
    return s, head.split(b"\r\n")[0].decode(errors="replace"), rest


def send_text(sock: socket.socket, text: str) -> None:
    """发一个客户端文本帧（客户端必须加 mask）。"""
    payload = text.encode()
    mask = os.urandom(4)
    n = len(payload)
    if n < 126:
        header = bytes([0x81, 0x80 | n])
    elif n < 65536:
        header = bytes([0x81, 0x80 | 126]) + struct.pack(">H", n)
    else:
        header = bytes([0x81, 0x80 | 127]) + struct.pack(">Q", n)
    masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
    sock.sendall(header + mask + masked)


class Reader:
    """按帧读取（够用即可：不分片、不处理压缩扩展）。"""

    def __init__(self, sock: socket.socket, rest: bytes = b""):
        self.sock = sock
        self.buf = bytearray(rest)

    def _read_n(self, n: int) -> bytes:
        while len(self.buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise RuntimeError("连接已关闭")
            self.buf += chunk
        out = bytes(self.buf[:n])
        del self.buf[:n]
        return out

    def frame(self):
        """返回 (opcode, payload)；opcode 1=text 2=binary 8=close 9=ping 10=pong。"""
        b0, b1 = self._read_n(2)
        length = b1 & 0x7F
        if length == 126:
            length = struct.unpack(">H", self._read_n(2))[0]
        elif length == 127:
            length = struct.unpack(">Q", self._read_n(8))[0]
        mask = self._read_n(4) if (b1 & 0x80) else None
        payload = self._read_n(length)
        if mask:
            payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        return b0 & 0x0F, payload


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default=None, help="战斗服地址，默认自动探测本机局域网 IP")
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--path", default="/ws")
    ap.add_argument("--room", type=int, default=1001)
    ap.add_argument("--player", type=int, default=1)
    ap.add_argument("--seat", default="NORTH")
    ap.add_argument("--op", default=None, help="join 之后再发一条命令，如 NEWGAME")
    ap.add_argument("--count", type=int, default=5, help="最多打印几条消息")
    ap.add_argument("--silent", action="store_true", help="只检查连通性，不 join")
    args = ap.parse_args()

    host = args.host or local_ip()
    print(f"→ 目标 ws://{host}:{args.port}{args.path}")

    try:
        sock, status, rest = handshake(host, args.port, args.path)
    except Exception as e:
        print(f"✗ 连接失败：{type(e).__name__}: {e}")
        print("  排查：服务端是否启动？（本机跑 start.command）")
        print("  手机连不上还要查：是否同一 WiFi / 校园网是否禁止设备互访。")
        return 1

    print(f"握手响应: {status}")
    if "101" not in status:
        print("✗ 不是 101 Switching Protocols，握手未完成")
        return 1
    print("✓ WebSocket 已建立")

    if args.silent:
        return 0

    send_text(sock, json.dumps({"op": "join", "roomId": args.room,
                                "playerId": args.player, "seat": args.seat}))
    print(f"→ 已发 join (room={args.room} player={args.player} seat={args.seat})")
    if args.op:
        send_text(sock, json.dumps({"op": "cmd", "type": args.op, "roomId": args.room}))
        print(f"→ 已发命令 {args.op}")

    sock.settimeout(8)
    reader = Reader(sock, rest)
    for i in range(args.count):
        try:
            op, payload = reader.frame()
        except Exception as e:
            print(f"  [{i}] 读取结束：{e}")
            break
        if op == 9:
            print(f"  [{i}] <- ping")
            continue
        if op == 8:
            print(f"  [{i}] <- close")
            break
        try:
            msg = json.loads(payload.decode())
        except Exception:
            print(f"  [{i}] raw: {payload[:140]}")
            continue
        kind = msg.get("type")
        if kind == "joined":
            print(f"  [{i}] joined token={str(msg.get('token'))[:14]}… reconnect={msg.get('reconnect')}")
        elif kind == "snapshot":
            hand = msg.get("yourHand") or []
            print(f"  [{i}] snapshot phase={msg.get('phase')} 第{msg.get('gameNumber')}局 "
                  f"手牌{len(hand)}张 turn={msg.get('turn')} banker={msg.get('banker')}")
            if hand:
                print(f"      手牌前10: {hand[:10]}")
        else:
            print(f"  [{i}] {kind}: {payload.decode(errors='replace')[:130]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
