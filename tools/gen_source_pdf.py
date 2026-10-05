# -*- coding: utf-8 -*-
import os
from fpdf import FPDF

ROOT = "/Users/renzheng/Library/CloudStorage/OneDrive-大连东软信息学院/横向/2026/扑克游戏小程序"
OUT  = "/Users/renzheng/Downloads/软著申报材料/源程序-滚子大厅纸牌游戏软件V1.0.pdf"
SOFT = "滚子大厅纸牌游戏软件 V1.0"
CN_FONT = "/Library/Fonts/Arial Unicode.ttf"

SERVER = os.path.join(ROOT, "server")
CLIENT = os.path.join(ROOT, "client")

DOMAIN_ORDER = {'card':0,'deck':1,'trump':2,'player':3,'room':4,
                'play':5,'action':6,'tribute':7,'scoring':8}

def java_key(p):
    rel = os.path.relpath(p, SERVER)
    parts = rel.split("/")
    pkg = ""
    if "domain" in parts:
        try: pkg = parts[parts.index("domain") + 1]
        except Exception: pkg = ""
    mod = 0 if "game-domain" in parts else 1
    return (mod, DOMAIN_ORDER.get(pkg, 9), rel)

def collect_server_main():
    out = []
    for dp, _, fs in os.walk(SERVER):
        if "/src/test/" in dp or "/src/main/" not in dp:
            continue
        for f in fs:
            if f.endswith(".java"):
                out.append(os.path.join(dp, f))
    return sorted(out, key=java_key)

def collect_client():
    out = []
    for dp, _, fs in os.walk(CLIENT):
        if any(s in dp for s in ("/build/", "/library/", "/local/", "/temp/", "/node_modules/")):
            continue
        for f in fs:
            if f.endswith(".ts") and not f.endswith(".d.ts"):
                out.append(os.path.join(dp, f))
    return sorted(out)

all_lines = []  # (text, kind)  kind: 'filehdr'|'code'
for fp in collect_server_main():
    rel = os.path.relpath(fp, ROOT)
    all_lines.append((f"// ============ FILE: {rel} ============", "filehdr"))
    with open(fp, encoding="utf-8", errors="ignore") as fh:
        for ln in fh.read().split("\n"):
            if ln.endswith("\r"): ln = ln[:-1]
            all_lines.append((ln, "code"))
for fp in collect_client():
    rel = os.path.relpath(fp, ROOT)
    all_lines.append((f"// ============ FILE: {rel} ============", "filehdr"))
    with open(fp, encoding="utf-8", errors="ignore") as fh:
        for ln in fh.read().split("\n"):
            if ln.endswith("\r"): ln = ln[:-1]
            all_lines.append((ln, "code"))

L = len(all_lines)
print(f"拼接后总行数(含文件分隔行): {L}")
assert L >= 3000, "源码不足3000行，前后30页会重叠"
PER = 50
head = all_lines[: PER*30]
tail = all_lines[L - PER*30 :]
for t, k in reversed(head):
    if k == "filehdr": print("前30页末行所属文件:", t); break
for t, k in tail:
    if k == "filehdr": print("后30页首行所属文件:", t); break

class SrcPDF(FPDF):
    def header(self):
        self.set_font("cn", size=10)
        self.set_y(16)
        self.cell(0, 8, SOFT, align="C")
        self.set_font("cn", size=9)
        pw = 130
        self.set_xy(self.w - self.r_margin - pw, 16)
        self.cell(pw, 8, f"第 {self.page_no():02d} 页 / 共 {self.total_pages} 页", align="R")
        self.set_draw_color(150, 150, 150)
        self.line(self.l_margin, 28, self.w - self.r_margin, 28)
        self.set_draw_color(0, 0, 0)
    def footer(self):
        pass

pdf = SrcPDF(orientation="P", unit="pt", format="A4")
pdf.add_font("cn", "", CN_FONT)
pdf.set_auto_page_break(auto=False)
pdf.total_pages = 60

LEFT = 40; TOP = 36; LINE_H = 10.5; CODE_SIZE = 8
AVAIL = pdf.w - pdf.l_margin - pdf.r_margin
NUM_W = 7 * (CODE_SIZE * 0.5) + 2   # 行号前缀(6位数字+空格)近似宽
CODE_W = AVAIL - NUM_W

pdf.set_font("cn", size=CODE_SIZE)

def wrap(text, maxw):
    """按显示宽度切分，返回若干段（每段<=maxw）。"""
    if text == "":
        return [""]
    lines = []; cur = ""; w = 0.0
    for ch in text:
        cw = pdf.get_string_width(ch)
        if w + cw > maxw and cur:
            lines.append(cur); cur = ch; w = cw
        else:
            cur += ch; w += cw
    if cur:
        lines.append(cur)
    return lines

def emit_page(lines, global_start):
    pdf.add_page()
    pdf.set_font("cn", size=CODE_SIZE)
    y = TOP
    gi = global_start
    for text, kind in lines:
        if kind == "filehdr":
            segs = wrap(text, CODE_W)
            for j, s in enumerate(segs):
                prefix = "       " if j == 0 else "        "
                pdf.set_xy(LEFT, y); pdf.cell(0, LINE_H, prefix + s)
                y += LINE_H
            continue
        segs = wrap(text, CODE_W)
        first = segs[0]
        pdf.set_xy(LEFT, y); pdf.cell(0, LINE_H, f"{gi:05d} {first}")
        y += LINE_H; gi += 1
        for s in segs[1:]:
            pdf.set_xy(LEFT, y); pdf.cell(0, LINE_H, "        " + s)
            y += LINE_H

pages_data = []
for i in range(30):
    pages_data.append((head[i*PER:(i+1)*PER], 1 + i*PER))
tail_start = L - PER*30 + 1
for i in range(30):
    pages_data.append((tail[i*PER:(i+1)*PER], tail_start + i*PER))

for lines, gstart in pages_data:
    emit_page(lines, gstart)

pdf.output(OUT)
print("已生成:", OUT)
print("页数:", pdf.page_no())
