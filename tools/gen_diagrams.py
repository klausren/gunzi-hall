#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
打滚子小游戏 —— SDD 教学图件生成器

用途：为「软件工程 SDD 项目案例」生成一套统一视觉语言的 UML 图件（SVG）。
事实来源：全部取自仓库真实代码（server/ + client/），非示意性编造。

产出：docs/diagrams/*.svg
用法：python3 tools/gen_diagrams.py
"""
from __future__ import annotations

import pathlib

# ---------------- 视觉规范（单一来源，保证 9 张图同一套语言） ----------------
FONT = "PingFang SC, Hiragino Sans GB, Microsoft YaHei, Helvetica Neue, Arial, sans-serif"
INK = "#1F2937"        # 主文字
INK2 = "#4B5563"       # 次级文字
INK3 = "#6B7280"       # 弱文字
EDGE = "#94A3B8"       # 连接线 / 边框
CANVAS = "#FFFFFF"

# 语义色板：(填充, 描边, 标题色)
BLUE = ("#E8F0FE", "#1D4ED8", "#1E3A8A")
GREEN = ("#E7F6EF", "#047857", "#065F46")
AMBER = ("#FDF0E3", "#B45309", "#7C2D12")
PURPLE = ("#F3E8FD", "#6D28D9", "#4C1D95")
RED = ("#FDECEC", "#B91C1C", "#7F1D1D")
GRAY = ("#EEF2F6", "#64748B", "#334155")

OUT = pathlib.Path(__file__).resolve().parent.parent / "docs" / "diagrams"


def esc(s: str) -> str:
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


class Svg:
    """极简 SVG 拼装器。所有图共用同一套 helper，避免风格漂移。"""

    def __init__(self, w: int, h: int, title: str, subtitle: str = "") -> None:
        self.w, self.h = w, h
        self.parts: list[str] = []
        self.parts.append(
            f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" width="{w}" height="{h}">'
        )
        self.parts.append(f"<title>{esc(title)}</title>")
        self.parts.append(
            '<defs>'
            '<marker id="a" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">'
            f'<path d="M1 1L9 5L1 9" fill="none" stroke="{EDGE}" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></marker>'
            '<marker id="ab" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">'
            f'<path d="M1 1L9 5L1 9" fill="none" stroke="{BLUE[1]}" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></marker>'
            '<marker id="af" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="9" markerHeight="9" orient="auto-start-reverse">'
            f'<path d="M0 0L10 5L0 10z" fill="{INK2}"/></marker>'
            '</defs>'
        )
        self.parts.append(f'<rect width="{w}" height="{h}" fill="{CANVAS}"/>')
        self.title(title, subtitle)

    def title(self, t: str, sub: str = "") -> None:
        self.parts.append(
            f'<text x="48" y="46" font-family="{FONT}" font-size="21" font-weight="600" fill="{INK}">{esc(t)}</text>'
        )
        if sub:
            self.parts.append(
                f'<text x="48" y="70" font-family="{FONT}" font-size="13" fill="{INK3}">{esc(sub)}</text>'
            )

    def rect(self, x, y, w, h, fill="none", stroke=EDGE, rx=10, sw=1.2, dash=None) -> None:
        d = f' stroke-dasharray="{dash}"' if dash else ""
        self.parts.append(
            f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="{fill}" '
            f'stroke="{stroke}" stroke-width="{sw}"{d}/>'
        )

    def box(self, x, y, w, h, label, sub="", tone=BLUE, rx=10, size=14, subsize=12) -> None:
        """sub 支持用 \n 分行（多行副标题）"""
        fill, stroke, tink = tone
        self.rect(x, y, w, h, fill, stroke, rx)
        lines = sub.split("\n") if sub else []
        cy = y + h / 2
        if lines:
            block = 20 + len(lines) * (subsize + 4)
            top = cy - block / 2 + 15
            self.txt(x + w / 2, top, label, size, tink, "middle", 600)
            for i, ln in enumerate(lines):
                self.txt(x + w / 2, top + 20 + i * (subsize + 4), ln, subsize, INK2, "middle")
        else:
            self.txt(x + w / 2, cy + 5, label, size, tink, "middle", 600)

    def txt(self, x, y, s, size=13, fill=INK, anchor="start", weight=400) -> None:
        self.parts.append(
            f'<text x="{x}" y="{y}" font-family="{FONT}" font-size="{size}" font-weight="{weight}" '
            f'fill="{fill}" text-anchor="{anchor}">{esc(s)}</text>'
        )

    def multiline(self, x, y, lines, size=12, fill=INK2, lh=17) -> None:
        for i, ln in enumerate(lines):
            self.txt(x, y + i * lh, ln, size, fill)

    def line(self, x1, y1, x2, y2, color=EDGE, sw=1.2, dash=None, marker=False) -> None:
        d = f' stroke-dasharray="{dash}"' if dash else ""
        m = ' marker-end="url(#a)"' if marker else ""
        self.parts.append(
            f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{color}" stroke-width="{sw}"{d}{m}/>'
        )

    def path(self, d, color=EDGE, sw=1.2, dash=None, marker=True) -> None:
        da = f' stroke-dasharray="{dash}"' if dash else ""
        m = ' marker-end="url(#a)"' if marker else ""
        self.parts.append(
            f'<path d="{d}" fill="none" stroke="{color}" stroke-width="{sw}"{da}{m}/>'
        )

    def diamond(self, cx, cy, w, h, label, tone=AMBER, lines=None) -> None:
        fill, stroke, tink = tone
        self.parts.append(
            f'<path d="M{cx} {cy - h / 2}L{cx + w / 2} {cy}L{cx} {cy + h / 2}L{cx - w / 2} {cy}Z" '
            f'fill="{fill}" stroke="{stroke}" stroke-width="1.2"/>'
        )
        raw = lines if lines else [label]
        wrapped: list[str] = []
        for ln in raw:
            wrapped.extend(self._wrap(ln, w * 0.58, 11.5))
        top = cy + 4 - (len(wrapped) - 1) * 7
        for i, ln in enumerate(wrapped):
            self.txt(cx, top + i * 14, ln, 11.5, tink, "middle", 600)

    def actor(self, cx, cy, label) -> None:
        """用例图 actor：火柴人"""
        self.parts.append(f'<circle cx="{cx}" cy="{cy - 22}" r="11" fill="none" stroke="{INK}" stroke-width="1.4"/>')
        self.parts.append(f'<line x1="{cx}" y1="{cy - 11}" x2="{cx}" y2="{cy + 14}" stroke="{INK}" stroke-width="1.4"/>')
        self.parts.append(f'<line x1="{cx - 15}" y1="{cy - 2}" x2="{cx + 15}" y2="{cy - 2}" stroke="{INK}" stroke-width="1.4"/>')
        self.parts.append(f'<line x1="{cx}" y1="{cy + 14}" x2="{cx - 12}" y2="{cy + 36}" stroke="{INK}" stroke-width="1.4"/>')
        self.parts.append(f'<line x1="{cx}" y1="{cy + 14}" x2="{cx + 12}" y2="{cy + 36}" stroke="{INK}" stroke-width="1.4"/>')
        self.txt(cx, cy + 54, label, 13, INK, "middle", 600)

    def ellipse(self, cx, cy, w, h, label, sub="", tone=BLUE) -> None:
        fill, stroke, tink = tone
        self.parts.append(
            f'<ellipse cx="{cx}" cy="{cy}" rx="{w / 2}" ry="{h / 2}" fill="{fill}" stroke="{stroke}" stroke-width="1.2"/>'
        )
        if sub:
            self.txt(cx, cy - 4, label, 12.5, tink, "middle", 600)
            self.txt(cx, cy + 13, sub, 11, INK2, "middle")
        else:
            self.txt(cx, cy + 5, label, 12.5, tink, "middle", 600)

    def box_wrap(self, x, y, w, h, label, tone=BLUE, rx=10, size=12.5, lh=18) -> None:
        """自动折行的方框（活动图用）：文字按行高居中堆叠"""
        fill, stroke, tink = tone
        self.rect(x, y, w, h, fill, stroke, rx)
        lines = self._wrap(label, w - 30, size)
        top = y + h / 2 - (len(lines) - 1) * lh / 2 + size / 3
        for i, ln in enumerate(lines):
            self.txt(x + w / 2, top + i * lh, ln, size, tink, "middle", 600)

    @staticmethod
    def _wrap(text: str, max_px: float, size: float) -> list[str]:
        """按像素宽度折行：CJK 记 1 个字宽，ASCII 记 0.55；ASCII 词组不拆开。"""
        def widen(ch: str) -> float:
            return size if ord(ch) > 0x2E80 else size * 0.55

        out: list[str] = []
        for raw in text.split("\n"):
            if not raw:
                out.append("")
                continue
            tokens: list[str] = []
            buf = ""
            for ch in raw:
                if ord(ch) > 0x2E80:          # CJK 单字成词
                    if buf:
                        tokens.append(buf)
                        buf = ""
                    tokens.append(ch)
                elif ch == " ":
                    if buf:
                        tokens.append(buf)
                        buf = ""
                    tokens.append(" ")
                else:
                    buf += ch
            if buf:
                tokens.append(buf)
            cur, width = "", 0.0
            for tk in tokens:
                wd = sum(widen(c) for c in tk)
                if width + wd > max_px and cur.strip():
                    out.append(cur.rstrip())
                    cur, width = tk.lstrip(" "), sum(widen(c) for c in tk.lstrip(" "))
                else:
                    cur += tk
                    width += wd
            out.append(cur.rstrip())
        return out

    def note(self, x, y, w, text, tone=GRAY, size=11.5) -> float:
        """注释块，返回高度（自动折行，不拆 ASCII 词组）"""
        lines = self._wrap(text, w - 24, size)
        h = len(lines) * (size + 5) + 16
        fill, stroke, _ = tone
        self.rect(x, y, w, h, fill, stroke, 8, 1)
        self.multiline(x + 12, y + 22, lines, size, INK2, size + 5)
        return h

    def save(self, name: str) -> None:
        self.parts.append("</svg>")
        OUT.mkdir(parents=True, exist_ok=True)
        (OUT / name).write_text("\n".join(self.parts), encoding="utf-8")
        print(f"  ✓ {name}")


# =====================================================================
# D1 用例图
# =====================================================================
def usecase() -> None:
    s = Svg(1180, 820, "图 1  打滚子小游戏 —— 用例图",
            "参与者：玩家 / 系统托管 / Bot 陪打。用例取自 proposal.md §5 MVP 功能范围 F1–F10")
    s.rect(360, 100, 500, 660, "#F8FAFC", EDGE, 16, 1.2)
    s.txt(610, 128, "打滚子小游戏（微信小游戏 + 战斗服）", 14, INK2, "middle", 600)

    ucs = [
        ("扫码进入并自动入座", "F1", 0),
        ("开始新局（重置牌局）", "F1", 1),
        ("亮主 / 反主 / 确认定主", "F2", 2),
        ("扣底（选恰好 6 张）", "F3", 3),
        ("点选出牌（可多张同出）", "F4", 4),
        ("进贡 / 还贡", "F5", 5),
        ("查看结算（分数 / 血数 / 升级）", "F6", 6),
        ("断线重连并恢复牌局", "F8", 7),
        ("查看牌桌状态与阶段提示", "—", 8),
        ("超时托管：系统代打", "F10", 9),
        ("Bot 补位陪打（拟人化 0.8–2.5s）", "F7", 10),
        ("体验版二维码入口", "F9", 11),
    ]
    y = 152
    for label, f, _ in ucs:
        tone = BLUE
        if f == "F10":
            tone = AMBER
        elif f == "F7":
            tone = GREEN
        elif f == "F9":
            tone = PURPLE
        s.ellipse(610, y + 22, 380, 44, label, f"（{f}）" if f != "—" else "", tone)
        y += 50

    s.actor(140, 300, "玩家（真人）")
    s.actor(1020, 470, "系统托管")
    s.actor(1020, 660, "Bot 陪打")

    # 玩家 → 各用例（主关联）
    for i in range(12):
        s.line(178, 300, 420, 174 + i * 50, EDGE, 1, None, True)
    # 系统托管 → 超时托管；Bot → Bot 补位（次要参与者，普通关联）
    s.line(982, 470, 800, 624, AMBER[1], 1.2, None, True)
    s.line(982, 660, 800, 674, GREEN[1], 1.2, None, True)

    s.note(48, 620, 270,
           "说明\n"
           "· 超时托管（F10）：玩家长时间不操作时由系统代打，不阻塞牌局（proposal 异常场景 E3）。\n"
           "· Bot 补位（F7）：凑不齐 4 人时由 bot 补座，保证「凑不齐人也能玩」。\n"
           "· 二者在 UML 中可作为「超时托管」「Bot 补位」对「出牌」的 «extend» 关系理解。")
    s.save("01-usecase.svg")


# =====================================================================
# D2 系统架构图
# =====================================================================
def architecture() -> None:
    s = Svg(1180, 1080, "图 2  系统技术架构图",
            "五层架构：客户端 → 接入 → 应用 → 领域 / 存储。类名与包结构全部取自真实代码")

    layers = [
        ("客户端层 · Cocos Creator 3.8.8 / TypeScript", BLUE,
         [("TableUI", "牌桌渲染 · 交互 · 视角旋转"),
          ("CardUI", "牌面矢量绘制"),
          ("NetClient", "WS 封装 · 心跳 · 重连"),
          ("ServerUrl", "地址解析 · 兜底注入")]),
        ("服务端接入层 · Netty 4 WebSocket", GRAY,
         [("GameServer", "pipeline /ws · 空闲 90s"),
          ("WsServerHandler", "join / cmd / snapshot / ping"),
          ("SessionRegistry", "token → 玩家身份绑定"),
          ("ChannelSink", "按连接回推消息")]),
        ("应用层 · game-room", PURPLE,
         [("RoomManager", "建房 · 入座 · 路由 · 恢复"),
          ("RoomActor", "单房线程模型 · 驱动"),
          ("BotBrain / BotPlayer", "陪打策略 · 拟人化"),
          ("RoomStateStore", "Redis / 内存命令日志")]),
        ("领域层 · game-domain（纯 Java，无框架依赖）", GREEN,
         [("GameRoom", "牌局聚合根 · 状态机"),
          ("GameCommand ×10", "命令模式 · 可回放"),
          ("FollowValidator / Trick", "牌型与跟牌判定"),
          ("ScoreCalculator", "计分 · 血数 · 升级")]),
    ]
    y = 110
    for name, tone, boxes in layers:
        fill, stroke, tink = tone
        s.rect(60, y, 1060, 150, fill, stroke, 12, 1.2)
        s.txt(84, y + 28, name, 14, tink, "start", 600)
        bx = 84
        for label, sub in boxes:
            s.box(bx, y + 46, 236, 82, label, sub, tone, 8, 13, 11)
            bx += 252
        y += 170

    s.rect(60, y, 1060, 96, "#F8FAFC", EDGE, 12, 1.2)
    s.txt(84, y + 28, "运行环境 / 存储层", 14, INK2, "start", 600)
    s.box(84, y + 42, 300, 42, "Redis（可选）· 命令日志，重启可重放恢复", "", GRAY, 8, 12)
    s.box(404, y + 42, 300, 42, "内存（降级）· Redis 不可用时自动切换", "", GRAY, 8, 12)
    s.box(724, y + 42, 372, 42, "微信小游戏运行环境（无 DOM / 无 location）", "", BLUE, 8, 12)

    # 层间连接：箭头画在左内侧的层间空隙，标签紧随其后
    conn = ["WebSocket 长连接（局域网 ws:// · 真机需 wss://）+ JSON 消息",
            "Java 方法调用（进程内，同步）",
            "Java 方法调用（进程内，同步）"]
    for i, label in enumerate(conn):
        top = 110 + i * 170 + 150
        s.line(100, top, 100, top + 20, EDGE, 1.4, None, True)
        s.txt(118, top + 15, label, 12, INK2, "start")

    y_note = y + 112
    s.note(60, y_note, 1060,
           "关键约束（决定了部署方案）\n"
           "① 微信小游戏真机只允许 wss://，ws:// 仅在「开发调试 → 打开调试」模式下可用；socket 合法域名必须是 wss + ICP 备案域名，192.168.x.x / localhost 一律非法。\n"
           "② 战斗服地址烧进小游戏包（连不上服务器时无法取配置）→ 地址必须固定，因此免费内网穿透不适用。\n"
           "③ 客户端无 location / 无 DOM → 服务端地址靠构建期注入 + 运行时兜底解析（ServerUrl.resolveServerUrl）。\n"
           "④ 领域层不依赖任何框架与网络 → 规则正确性可用纯 JUnit 验证（25 个测试类）。")
    s.save("02-architecture.svg")


# =====================================================================
# D3 模块依赖图
# =====================================================================
def modules() -> None:
    s = Svg(1080, 660, "图 3  模块依赖图",
            "三个可独立构建的模块：client（TypeScript）/ game-room（应用层）/ game-domain（领域层）")
    s.box(80, 120, 400, 130, "client",
           "Cocos Creator 3.8.8 · TypeScript\nassets/scripts/（ui / net）", BLUE, 12, 17, 12)
    s.box(600, 120, 400, 130, "game-room",
           "Spring Boot 3 风格工程 · Maven\nNetty · Jackson · Lettuce(Redis)", PURPLE, 12, 17, 12)
    s.box(600, 400, 400, 130, "game-domain",
           "纯 Java 领域层 · 零框架依赖\n25 个测试类覆盖规则正确性", GREEN, 12, 17, 12)
    s.box(80, 400, 400, 130, "RoomStateStore(Redis / 内存)",
           "命令日志 → 服务重启后重放恢复", GRAY, 12, 14, 12)

    # client → game-room
    s.path("M480 185 L600 185", BLUE[1], 1.8)
    s.txt(540, 175, "WebSocket + JSON 协议", 11.5, BLUE[1], "middle", 600)
    s.note(80, 280, 340,
           "协议消息（Protocol.ts ↔ ClientMsg）\n"
           "上行：{op:join} / {op:cmd} / {op:snapshot} / {op:ping}\n"
           "下行：{type:joined} / {type:snapshot} / {type:event} / {type:error}",
           BLUE, 11)

    # game-room → game-domain
    s.path("M800 250 L800 400", GREEN[1], 1.8)
    s.txt(790, 330, "依赖（编译期）", 11.5, GREEN[1], "end", 600)
    s.note(600, 280, 190,
           "领域层不反向依赖应用层\n→ 规则可脱离网络单测", GREEN, 11)

    # game-domain ←→ RoomStateStore
    s.path("M600 465 L480 465", GRAY[1], 1.8)
    s.txt(540, 455, "读写命令日志", 11.5, GRAY[1], "middle", 600)
    s.save("03-modules.svg")


# =====================================================================
# D4 牌局状态图
# =====================================================================
def state_machine() -> None:
    s = Svg(1180, 900, "图 4  牌局状态图（GamePhase 状态机）",
            "来源：game-domain/.../room/GamePhase.java —— 枚举名与 canTransitionTo() 的合法迁移逐条对应")
    nodes = ["WAITING", "DEALING", "BIDDING", "TRIBUTE", "BURYING", "PLAYING", "SETTLING", "ROUND_OVER"]
    subs = ["等人齐 4 人", "发牌 39×4 + 底 6", "亮主 / 反主 / 定主", "进贡 / 还贡 / 抗贡",
            "庄家扣底 6 张", "出牌直至手牌打完", "算分 / 血数 / 升级", "打完 10 出锅"]
    Y0, PITCH, BH = 170, 86, 62

    def top(i: int) -> int:
        return Y0 + i * PITCH

    s.txt(120, 150, "主流程（正向单步迁移）", 13, INK2, "start", 600)
    for i, (n, sub) in enumerate(zip(nodes, subs)):
        tone = BLUE if i in (0, 1) else GREEN if i in (2, 3, 4) else PURPLE if i == 5 else AMBER
        s.box(120, top(i), 300, BH, n, sub, tone, 10, 15, 11.5)
        if i < len(nodes) - 1:
            s.line(270, top(i) + BH, 270, top(i + 1), EDGE, 1.4, None, True)

    c_bid, c_bury = top(2) + BH / 2, top(4) + BH / 2
    c_settle, c_deal = top(6) + BH / 2, top(1) + BH / 2

    # 跳过进贡：BIDDING → BURYING（走内圈 x=520）
    s.path(f"M420 {c_bid} L520 {c_bid} L520 {c_bury} L420 {c_bury}", AMBER[1], 1.6)
    s.txt(534, c_bury - 24, "首局或上局无血：", 11.5, AMBER[1], "start", 600)
    s.txt(534, c_bury - 7, "确认定主后直接进扣底", 11.5, AMBER[1], "start")
    s.txt(534, c_bury + 10, "（BIDDING → BURYING）", 10.5, INK3, "start")

    # 多局循环：SETTLING → DEALING（走外圈 x=680）
    s.path(f"M420 {c_settle} L680 {c_settle} L680 {c_deal} L420 {c_deal}", GREEN[1], 1.6, "6 4")
    s.txt(694, c_deal + 30, "多局循环：结算后 / 打完 10 出锅后直接发新局", 11.5, GREEN[1], "start", 600)
    s.txt(694, c_deal + 47, "（SETTLING → DEALING、ROUND_OVER → DEALING）", 10.5, INK3, "start")

    s.note(880, 170, 260,
           "NEWGAME（客户端「新局」按钮）\n"
           "任意阶段 → WAITING\n"
           "RoomActor 收到 NEWGAME 后调用 room.hardResetToWaiting()，再判断房间是否满 4 人：满则重新发牌，不满则广播原因。\n"
           "⚠ 当前无权限校验：任何玩家都能重置牌局（见 proposal 附录二 B4）。", GRAY, 11)
    s.save("04-state-machine.svg")


# =====================================================================
# D5 活动图：开局流程（入座 → 定主 → 扣底）
# =====================================================================
def activity_opening() -> None:
    s = Svg(1180, 780, "图 5  活动图 —— 开局流程（入座 → 定主 → 扣底）",
            "从入座到扣底的动作与判定；右侧为「否」分支的处理动作（来源：RoomActor.stepBidding / stepTribute / stepBury）")
    CX, BX, BW, BH = 340, 110, 460, 56
    DIA_W, DIA_H = 260, 88
    y = 150
    tops: list[float] = []
    bots: list[float] = []
    dias: list[float] = []

    def box(label, tone=BLUE):
        nonlocal y
        s.box_wrap(BX, y, BW, BH, label, tone, 10, 12.5)
        tops.append(y)
        bots.append(y + BH)
        y += BH + 22

    def dia(lines):
        nonlocal y
        cy = y + DIA_H / 2
        s.diamond(CX, cy, DIA_W, DIA_H, "", AMBER, lines)
        tops.append(cy - DIA_H / 2)
        bots.append(cy + DIA_H / 2)
        dias.append(cy)
        y += DIA_H + 24
        return cy

    s.parts.append(f'<circle cx="{CX}" cy="118" r="9" fill="{INK}"/>')
    s.line(CX, 127, CX, 150, EDGE, 1.4, None, True)

    box("扫码进入 → 自动入座（3 个 bot 已就位）")
    box("点「新局」：hardResetToWaiting() → 发牌 39×4 + 底 6")
    box("[BIDDING] 选级牌亮主 / 反主（2 反 1、3 反 2、王叫任意花色）", GREEN)
    d1 = dia(["有人亮主且确认定主？"])
    box("[TRIBUTE] 进贡 / 还贡（首局或上局无血则跳过）", GREEN)
    box("[BURYING] 庄家选恰好 6 张扣底（张数不对则提示）", GREEN)

    for i in range(len(tops) - 1):
        s.line(CX, bots[i], CX, tops[i + 1], EDGE, 1.4, None, True)

    s.line(CX, bots[-1], CX, y + 6, EDGE, 1.4, None, True)
    s.parts.append(f'<circle cx="{CX}" cy="{y + 18}" r="11" fill="none" stroke="{INK}" stroke-width="1.6"/>')
    s.parts.append(f'<circle cx="{CX}" cy="{y + 18}" r="6" fill="{INK}"/>')
    s.txt(CX + 24, y + 23, "转入出牌阶段（见图 6）", 12, INK2, "start", 600)

    # 「否」分支
    s.line(CX + DIA_W / 2, d1, 680, d1, AMBER[1], 1.4, None, True)
    s.txt(CX + DIA_W / 2 + 40, d1 - 8, "否", 11.5, AMBER[1], "middle", 600)
    s.box_wrap(680, d1 - 29, 460, 58, "未定主：等 bot 亮主 / 超时托管代打", AMBER, 10, 12)
    s.txt(690, d1 + 52, "↩ 回到 BIDDING（首局为抢亮大王）", 11, INK2, "start")

    s.note(680, d1 + 76, 460,
           "首局特殊：抢亮大王（非级牌）；第二局起亮主必须用级牌（打 3 亮 3）。"
           "反主规则：2 个反 1 个、3 个反 2 个；3 个王可叫任意花色，大王叫牌权优于小王。", GRAY, 11)
    s.save("05-activity-opening.svg")


# =====================================================================
# D6 活动图：出牌循环与结算
# =====================================================================
def activity_play() -> None:
    s = Svg(1180, 1040, "图 6  活动图 —— 出牌循环与结算",
            "一局中最主要的循环：出牌 → 跟牌判定 → 本墩结果，直到手牌打完进入结算（来源：RoomActor.stepPlay / onHumanTimeout）")
    CX, BX, BW, BH = 340, 110, 460, 56
    DIA_W, DIA_H = 260, 88
    y = 150
    tops: list[float] = []
    bots: list[float] = []
    dias: list[float] = []

    def box(label, tone=BLUE):
        nonlocal y
        s.box_wrap(BX, y, BW, BH, label, tone, 10, 12.5)
        tops.append(y)
        bots.append(y + BH)
        y += BH + 22

    def dia(lines):
        nonlocal y
        cy = y + DIA_H / 2
        s.diamond(CX, cy, DIA_W, DIA_H, "", AMBER, lines)
        tops.append(cy - DIA_H / 2)
        bots.append(cy + DIA_H / 2)
        dias.append(cy)
        y += DIA_H + 24
        return cy

    s.parts.append(f'<circle cx="{CX}" cy="118" r="9" fill="{INK}"/>')
    s.line(CX, 127, CX, 150, EDGE, 1.4, None, True)

    box("[PLAYING] 轮到某家出牌", PURPLE)                       # 0
    d1 = dia(["轮到真人且连接在线？"])                            # 1
    box("玩家点选牌（复合身份 code#occurrence）→ 点「出牌」", PURPLE)  # 2
    d2 = dia(["跟牌合法？FollowValidator"])                       # 3
    box("出牌成功：写命令日志 → 广播 event + 各家私有快照", PURPLE)   # 4
    d3 = dia(["手牌已全部打完？"])                                 # 5
    box("[SETTLING] 算分（抠底 ×2）/ 血数 / 升级", AMBER)            # 6
    d4 = dia(["级数已到 10（出锅）？"])                             # 7

    for i in range(len(tops) - 1):
        s.line(CX, bots[i], CX, tops[i + 1], EDGE, 1.4, None, True)
    s.line(CX, bots[-1], CX, y + 6, EDGE, 1.4, None, True)
    s.parts.append(f'<circle cx="{CX}" cy="{y + 18}" r="11" fill="none" stroke="{INK}" stroke-width="1.6"/>')
    s.parts.append(f'<circle cx="{CX}" cy="{y + 18}" r="6" fill="{INK}"/>')

    # 「否」分支（右侧）
    def branch(cy, title, detail, tone=RED):
        s.line(CX + DIA_W / 2, cy, 680, cy, tone[1], 1.4, None, True)
        s.txt(CX + DIA_W / 2 + 40, cy - 8, "否", 11.5, tone[1], "middle", 600)
        s.box_wrap(680, cy - 29, 460, 58, title, tone, 10, 12)
        s.txt(690, cy + 52, detail, 11, INK2, "start")

    branch(d1, "Bot 出牌 / 超时托管代打（默认 32s）", "↩ 回到「轮到某家出牌」，牌局继续推进", GREEN)
    branch(d2, "拒绝出牌 + 返回可读原因", "如「有首家花色必须跟出」；牌局状态不变，可重出", RED)
    branch(d3, "未打完 → 进入下一墩", "↩ 回到「轮到某家出牌」", PURPLE)
    branch(d4, "未出锅 → 开新局（带进贡关系）", "↩ 回到图 5 的「发牌」步骤", BLUE)

    # 出牌循环回边（走最外侧，避开所有方框）
    rb_y = d3 + 29
    s.path(f"M680 {rb_y} L640 {rb_y} L640 990 L70 990 L70 {tops[0] + BH / 2} L{BX} {tops[0] + BH / 2}",
           EDGE, 1.4, "6 4")
    s.txt(250, 983, "回边：一局最多约 40 墩，每墩重复该循环", 10.5, INK3, "start")
    s.save("06-activity-play.svg")


# =====================================================================
# 顺序图公共构件
# =====================================================================
def seq_setup(s: Svg, names, y=110, w=170, h=54):
    """画参与者方框 + 生命线，返回各参与者中心 x 列表"""
    step = (1180 - 80) / len(names)
    xs = [80 + step * (i + 0.5) for i in range(len(names))]
    for x, n in zip(xs, names):
        s.box(x - w / 2, y, w, h, n, "", GRAY, 8, 12, 10.5)
        s.line(x, y + h, x, 1140, EDGE, 1, "5 5")
    return xs


def seq_act(s: Svg, x, y0, y1, tone=BLUE) -> None:
    s.rect(x - 6, y0, 12, y1 - y0, tone[0], tone[1], 3, 1)


def seq_msg(s: Svg, x1, x2, y, label, color=INK2, dashed=False, size=11.5, dy=-7) -> None:
    s.line(x1, y, x2, y, color, 1.4, "5 4" if dashed else None, True)
    s.txt((x1 + x2) / 2, y + dy, label, size, color, "middle", 600)


def seq_self(s: Svg, x, y, label, color=INK2, size=11) -> None:
    s.parts.append(
        f'<path d="M{x} {y} L{x + 28} {y} L{x + 28} {y + 20} L{x} {y + 20}" fill="none" '
        f'stroke="{color}" stroke-width="1.3" marker-end="url(#a)"/>'
    )
    s.txt(x + 36, y + 16, label, size, color, "start")


# =====================================================================
# D6 顺序图：入座与开局
# =====================================================================
def seq_join() -> None:
    s = Svg(1180, 900, "图 7  顺序图 —— 入座与自动开局",
            "参与者与调用顺序取自 NetClient.join / WsServerHandler.handleJoin / RoomManager.join / RoomActor.start（客户端 = TableUI + NetClient）")
    xs = seq_setup(s, ["玩家客户端", "WsServerHandler", "RoomManager", "RoomActor", "GameRoom"], y=104, w=186)
    c, ws, mgr, actor, room = xs

    seq_act(s, c, 190, 660)
    seq_act(s, ws, 250, 620)
    seq_act(s, mgr, 290, 560)
    seq_act(s, actor, 330, 790)
    seq_act(s, room, 410, 700, GREEN)

    y = 200
    seq_msg(s, c, ws, y, '{op:"join", roomId, playerId, seat}'); y += 40
    seq_msg(s, ws, mgr, y, "join(roomId, playerId, seat, sink)"); y += 40
    seq_self(s, mgr, y, "房间不在内存 → restore(roomId)：从命令日志重放重建"); y += 52
    seq_msg(s, mgr, actor, y, "join(new HumanPlayer(playerId, seat), bot=false)"); y += 40
    seq_msg(s, actor, room, y, "room.sitDown(player)  // 仅 WAITING 阶段可入座"); y += 40
    seq_self(s, actor, y, 'store.append("JOIN")  // 留痕供重启重放'); y += 52
    seq_msg(s, actor, mgr, y, "joined（reconnect=false）", GREEN[1], True); y += 40
    seq_msg(s, mgr, ws, y, '{"type":"joined"}', GREEN[1], True); y += 40
    seq_msg(s, ws, c, y, 'joined + token（后续 cmd / snapshot 必带）', BLUE[1]); y += 46
    seq_msg(s, mgr, actor, y, "start()  // 房满 4 人 → 自动开局", PURPLE[1]); y += 40
    seq_msg(s, actor, room, y, "ShuffleAndDealCommand(seed)：洗牌 + 发 39×4 + 底 6", PURPLE[1]); y += 40
    seq_msg(s, room, actor, y, "CommandResult(成功)", GREEN[1], True); y += 40
    seq_msg(s, actor, c, y, "emit()：event + snapshotFor(playerId)（每家一份私有手牌）", BLUE[1]); y += 46
    seq_self(s, actor, y, "scheduleDrive()：bot 每步随机思考 0.8–2.5s")

    s.note(60, 800, 1060,
           "重连分支（同一顺序图的另一入口）：hasPlayer(playerId) 命中 → addSink(sink) → 先发确定性快照 → resumeAfterRestore() 恢复 bot 驱动。\n"
           "顺序不可颠倒：若先恢复驱动，异步线程可能已推进牌局，重连者会拿到过期快照。", GRAY, 11)
    s.save("07-seq-join.svg")


# =====================================================================
# D7 顺序图：出牌与全量快照广播
# =====================================================================
def seq_play() -> None:
    s = Svg(1180, 880, "图 8  顺序图 —— 出牌与全量快照广播",
            "来源：WsServerHandler.handleCmd / RoomActor.submit+emit / PlayCardsCommand+FollowValidator（GameRoom 为领域聚合根，RoomStateStore 为命令日志）")
    xs = seq_setup(s, ["玩家客户端", "WsServerHandler", "SessionRegistry", "RoomActor",
                       "GameRoom", "RoomStateStore"], y=104, w=168)
    c, ws, sess, actor, room, store = xs

    seq_act(s, c, 190, 620)
    seq_act(s, ws, 240, 520)
    seq_act(s, sess, 280, 350, GRAY)
    seq_act(s, actor, 300, 700)
    seq_act(s, room, 420, 620, GREEN)
    seq_act(s, store, 470, 540, PURPLE)

    y = 200
    seq_msg(s, c, ws, y, '{op:"cmd", type:"PLAY", cards:["H9","H9"], token}'); y += 40
    seq_msg(s, ws, sess, y, "verify(token, roomId)"); y += 34
    seq_msg(s, sess, ws, y, "playerId（以 token 解析为准，不信任客户端上报）", GREEN[1], True); y += 42
    seq_msg(s, ws, actor, y, "submit(CommandSpec(op, playerId, cards))"); y += 40
    seq_self(s, actor, y, "runInThread：投递到该房间的调度线程（单房串行）"); y += 50
    seq_msg(s, actor, room, y, "PlayCardsCommand.execute(room)"); y += 40
    seq_self(s, room, y, "FollowValidator：首家花色 / 牌型（单·棒子·滚子）校验"); y += 50
    seq_msg(s, room, actor, y, "CommandResult(成功 | 失败原因)", GREEN[1], True); y += 40
    seq_msg(s, actor, store, y, "store.append(日志)  // 仅成功时写入"); y += 44
    seq_msg(s, actor, c, y, "对每个 sink：event + snapshotFor(sink.playerId())", BLUE[1]); y += 46
    seq_self(s, c, y, "pruneSelected → renderAll（重建签名：内容未变则不重建）")

    s.note(60, 660, 520,
           "失败分支：FollowValidator 拒绝时只广播 event(success=false, reason)，不写命令日志、牌局状态不变（proposal E5）。",
           RED, 11)
    s.note(620, 660, 500,
           "性能约束：renderHand / renderButtons 每收到一条快照就重建节点 × 39 张手牌，清容器必须用 destroyChildren()"
           "（先摘后毁），否则显存泄漏 → 上下文丢失 → 白屏（PR #8 根因）。", AMBER, 11)
    s.save("08-seq-play.svg")


# =====================================================================
# D8 顺序图：断线重连与恢复
# =====================================================================
def seq_reconnect() -> None:
    s = Svg(1180, 840, "图 9  顺序图 —— 断线重连与牌局恢复",
            "两条路径：①连接断开后自动重连（内存中房间）②服务重启后从命令日志重放重建")
    xs = seq_setup(s, ["玩家客户端", "WsServerHandler", "RoomManager", "RoomActor",
                       "RoomStateStore"], y=104, w=180)
    c, ws, mgr, actor, store = xs

    seq_act(s, c, 190, 700)
    seq_act(s, ws, 250, 600)
    seq_act(s, mgr, 300, 660)
    seq_act(s, actor, 340, 720)
    seq_act(s, store, 460, 560, PURPLE)

    s.txt(60, 178, "① 掉线重连（房间仍在内存中）", 12.5, BLUE[1], "start", 600)
    y = 200
    seq_self(s, c, y, "心跳未收到 pong → onclose → scheduleReconnect（指数退避）"); y += 50
    seq_msg(s, c, ws, y, '{op:"join"}  // 同 roomId / playerId / seat'); y += 40
    seq_msg(s, ws, mgr, y, "join(...)"); y += 40
    seq_msg(s, mgr, actor, y, "hasPlayer(playerId) → 命中：判定为重连"); y += 40
    seq_self(s, actor, y, "addSink(sink)  // 绑定新连接"); y += 50
    seq_msg(s, actor, c, y, "snapshotFor(playerId)：先发确定性快照", GREEN[1]); y += 44
    seq_self(s, actor, y, "resumeAfterRestore()  // 再恢复 bot 驱动"); y += 50
    seq_msg(s, c, ws, y, 'requestSnapshot()  // 客户端主动对齐 UI', BLUE[1], True); y += 46

    s.txt(60, y + 6, "② 服务重启后恢复（内存房间已丢）", 12.5, PURPLE[1], "start", 600)
    y += 28
    seq_msg(s, mgr, store, y, "commandLog(roomId)"); y += 36
    seq_msg(s, store, mgr, y, "命令日志（JOIN / REVEAL / PLAY / … 逐条）", PURPLE[1], True); y += 40
    seq_self(s, mgr, y, "replayJoin / replaySpec 逐条重放（不广播、不写日志）")

    s.note(60, 740, 1060,
           "顺序铁律：重连时必须「先发快照、后恢复驱动」——若顺序颠倒，异步驱动线程可能已推进牌局，重连者拿到的就是过期快照。\n"
           "注意：replaySpec 期间 RoomActor 处于 replaying 状态，不广播 event、不重复写命令日志；重放结束也不调用 resumeAfterRestore()，由 join() 在发完快照后统一恢复。",
           GRAY, 11)
    s.save("09-seq-reconnect.svg")


# =====================================================================
# D9 类图：核心领域模型
# =====================================================================
def class_domain() -> None:
    s = Svg(1240, 940, "图 10  类图 —— 核心领域模型（game-domain）",
            "聚合根、实体、值对象与领域服务；成员名取自真实源码，关系为 UML 标准记法")
    CW, CH = 280, 150
    xs = [40, 340, 640, 940]
    ys = [130, 310, 490, 670]

    def cls(col, row, name, stereo, members, tone=BLUE, h=CH):
        x, y = xs[col], ys[row]
        fill, stroke, tink = tone
        s.rect(x, y, CW, h, fill, stroke, 10, 1.2)
        s.line(x, y + 48, x + CW, y + 48, stroke, 1)
        if stereo:
            s.txt(x + CW / 2, y + 20, f"«{stereo}»", 10.5, INK3, "middle")
            s.txt(x + CW / 2, y + 38, name, 13, tink, "middle", 600)
        else:
            s.txt(x + CW / 2, y + 30, name, 13, tink, "middle", 600)
        s.multiline(x + 14, y + 70, members, 10.5, INK2, 15)

    cls(0, 0, "GameRoom", "聚合根",
        ["- phase: GamePhase", "- players: Map<Seat, Player>",
         "- hands / bottomCards", "- trump / bankerSeat / turnSeat",
         "- currentTrick: Trick",
         "+ apply(GameCommand) / transitionTo()"], BLUE)
    cls(1, 0, "Card", "值对象",
        ["- suit: Suit", "- rank: int（3–10 / J–A / 王）",
         "- equals 按值比较（三副牌多重集）",
         "+ Cards.containsCopies / removeCopies",
         "  严禁手牌用 removeAll / containsAll"], PURPLE)
    cls(2, 0, "Trick", "实体",
        ["- leader: Seat", "- lead: Combo",
         "- plays: List<PlayRecord>",
         "+ 一轮结束判定（最后出牌者胜）"], GREEN)
    cls(3, 0, "TrumpContext", "值对象",
        ["- level: int（级数 3–10）", "- trumpSuit: Suit",
         "+ 定主后手牌排序依据",
         "  （CardComparator(trump)）"], AMBER)

    cls(0, 1, "Player", "抽象类",
        ["- playerId / seat", "- hand: List<Card>",
         "HumanPlayer（真人）",
         "BotPlayer（game-room 侧）"], GRAY)
    cls(1, 1, "Combo / ComboType", "枚举 + 值对象",
        ["SINGLE(1) 单牌", "PAIR(2) 棒子（对子）",
         "TRIPLE(3) 滚子（三张）",
         "无顺子 / 拖拉机 / 甩牌"], PURPLE)
    cls(2, 1, "FollowValidator", "领域服务",
        ["+ validate(room, seat, cards)",
         "  1) 首家花色必须跟出",
         "  2) 牌型与张数匹配",
         "  3) FollowRule: STRICT / ALIVE"], GREEN)
    cls(3, 1, "CardComparator", "领域服务",
        ["+ compare(Card, Card)",
         "  小王 < 大王 < 级牌 / 主牌",
         "定主前后排序规则不同"], AMBER)

    cls(0, 2, "GameCommand", "接口",
        ["+ execute(GameRoom): CommandResult",
         "+ rollback(GameRoom)",
         "AbstractGameCommand 提供模板"],
         BLUE)
    cls(1, 2, "命令实现 ×10", "命令模式",
        ["REVEAL / CONFIRM / RESOLVE_BOTTOM",
         "TRIBUTE / RETURN_TRIBUTE / BURY",
         "PLAY / SETTLE / DEAL / NEWGAME",
         "→ 可序列化 JSON、可回放"], PURPLE)
    cls(2, 2, "RoundSettlement", "值对象",
        ["- attackerScore / bankerScore",
         "- attackerTakesBank", "- attackerPromoted / bankerPromoted",
         "- dugBottom（抠底 ×2）"], AMBER)
    cls(3, 2, "ScoreCalculator", "领域服务",
        ["+ 分牌计分（5 / 10 / 10）",
         "+ 血数 = 分差折算 + 扣王折算",
         "  两笔分别结算",
         "+ 升级 / 上台 / 出锅判定"], AMBER)

    cls(0, 3, "TributeCalculator", "领域服务",
        ["+ 进贡关系计算",
         "+ 血数 → 进贡张数",
         "仅庄家上家进贡"], GREEN)
    cls(1, 3, "TributeObligation", "值对象",
        ["- payer: Seat", "- receiver: Seat",
         "- bloodCount: int"], GREEN)
    cls(2, 3, "Seat / Team", "枚举",
        ["Seat: NORTH / EAST / SOUTH / WEST",
         "Team: 对家两两配对（1–3、2–4）"], GRAY)
    cls(3, 3, "CommandHistory / Result", "支撑类",
        ["- CommandHistory: 命令日志",
         "- CommandResult: 成功 / 失败 + 原因",
         "→ 供重启重放与可追溯性"], GRAY)

    # 关系（走空白通道，避免压住类框）
    def arrow(d, label, lx, ly, color=INK2, dash="5 4"):
        s.path(d, color, 1.4, dash)
        s.txt(lx, ly, label, 10.5, color, "start", 600)

    arrow("M180 130 L180 112 L780 112 L780 130", "◆— GameRoom 1 : 0..1 currentTrick", 300, 106, GREEN[1])
    arrow("M240 130 L240 96 L1080 96 L1080 130", "◆— GameRoom 1 : 0..1 trump", 620, 90, AMBER[1])
    arrow("M100 280 L100 310", "◆— GameRoom 1 : 4 players", 108, 302, GRAY[1])

    s.note(40, 845, 560,
           "记法：◆— 组合（聚合根内部持有）；⇢ 依赖 / 使用。\n"
           "其余依赖关系：Trick ⇢ Combo；FollowValidator ⇢ Combo / FollowRule；"
           "GameCommand ⇢ GameRoom；ScoreCalculator ⇢ RoundSettlement.Result；"
           "TributeCalculator ⇢ TributeObligation。", GRAY, 11)
    s.note(640, 845, 560,
           "设计要点：领域层不含任何框架与网络依赖，规则正确性由 25 个 JUnit 测试类保证"
           "（含 FullGameFlowTest 整局回归、FollowValidatorTest、ScoreCalculatorTest、TributeCalculatorTest）。",
           GRAY, 11)
    s.save("10-class-domain.svg")


def main() -> None:
    print("生成 SDD 教学图件 →", OUT)
    usecase()
    architecture()
    modules()
    state_machine()
    activity_opening()
    activity_play()
    seq_join()
    seq_play()
    seq_reconnect()
    class_domain()


if __name__ == "__main__":
    main()
