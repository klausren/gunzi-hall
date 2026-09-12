#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
滚子大厅 · 前端静态服务器（诊断增强版）

替代 `python -m http.server`，解决两个问题：

1. 【关键】HTTP/1.1 + Keep-Alive
   `python -m http.server` 走 HTTP/1.0，每个请求都新建 TCP 连接。
   Cocos 首屏要并发拉 ~14 个资源（cc.js 单文件 1.58MB），连接频繁建立/关闭
   容易偶发失败 → 引擎初始化中断 → 卡在启动画面（splash 不消失，页面白底 + 角落水印）。
   改 HTTP/1.1 后连接可复用，首屏加载更稳。

2. 【诊断】逐请求日志
   每个请求记录 时间/方法/路径/状态码/字节/耗时，404 与中断醒目标注。
   出现白屏时，看 静态服务器日志.txt 最后几十行，就能知道是哪个资源没拿到。

用法：
    python serve.py [端口] [根目录]
    默认 8081 + 当前目录

停止：Ctrl+C，或用项目的 停止-滚子大厅.bat
"""

import os
import sys
import time
import mimetypes
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler

# 确保 Cocos 需要的类型正确（Windows 注册表有时把 .js 关联成 text/plain，
# 而浏览器会对 text/plain 的 <script> 拒绝执行 → 引擎起不来）
mimetypes.add_type('text/javascript', '.js')
mimetypes.add_type('text/javascript', '.mjs')
mimetypes.add_type('application/json', '.json')
mimetypes.add_type('application/octet-stream', '.bin')
mimetypes.add_type('application/wasm', '.wasm')
mimetypes.add_type('image/png', '.png')

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8081
ROOT = os.path.abspath(sys.argv[2]) if len(sys.argv) > 2 else os.getcwd()
LOGFILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), '静态服务器日志.txt')

# 进程内累计，便于结束时汇总
_stats = {'ok': 0, 'notfound': 0, 'abort': 0}


def log(line: str) -> None:
    print(line, flush=True)
    try:
        with open(LOGFILE, 'a', encoding='utf-8') as f:
            f.write(line + '\n')
    except OSError:
        pass


class Handler(SimpleHTTPRequestHandler):

    # ★ 关键：HTTP/1.1 才支持 Keep-Alive
    protocol_version = 'HTTP/1.1'

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=ROOT, **kwargs)

    def do_GET(self):
        self._t0 = time.time()
        try:
            super().do_GET()
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError) as e:
            _stats['abort'] += 1
            log(f"[{time.strftime('%H:%M:%S')}] !! {self.path} 连接中断 ({type(e).__name__})")

    def log_request(self, code='-', size='-'):
        dt = (time.time() - getattr(self, '_t0', time.time())) * 1000
        if code == 404:
            _stats['notfound'] += 1
            mark = ' <<< 404 缺失!'
        elif isinstance(code, int) and code >= 400:
            mark = ' <<< 错误'
        else:
            _stats['ok'] += 1
            mark = ''
        size_s = size if size != '-' else '-'
        log(f"[{time.strftime('%H:%M:%S')}] {code} {self.path}  {size_s}B  {dt:.0f}ms{mark}")

    def log_message(self, fmt, *args):
        # 屏蔽默认 stderr 输出，统一走 log_request
        pass

    def log_error(self, fmt, *args):
        pass


def main():
    os.makedirs(os.path.dirname(LOGFILE), exist_ok=True)
    with open(LOGFILE, 'a', encoding='utf-8') as f:
        f.write(f"\n===== {time.strftime('%Y-%m-%d %H:%M:%S')} 启动 "
                f"http://127.0.0.1:{PORT}  根目录={ROOT} =====\n")

    httpd = ThreadingHTTPServer(('0.0.0.0', PORT), Handler)
    log(f"滚子大厅 静态服务器已启动")
    log(f"  地址：http://127.0.0.1:{PORT}")
    log(f"  根目录：{ROOT}")
    log(f"  日志：{LOGFILE}")
    log(f"  （HTTP/1.1 + Keep-Alive；Ctrl+C 停止）")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        log(f"收到 Ctrl+C，停止。统计：成功 {_stats['ok']} / 404 {_stats['notfound']} / 中断 {_stats['abort']}")
    finally:
        httpd.server_close()


if __name__ == '__main__':
    main()
