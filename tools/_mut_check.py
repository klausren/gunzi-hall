"""变异验证：把 TableUI.ts 逐条改坏，确认 ui_smoke_test.js 真的会红。

用法：node tools/ui_smoke_test.js 通过 GUNZI_TABLE_UI 指向被改坏的副本。
这是"测试真的会红"的反向证据 —— 只看绿没有任何意义。
"""
import pathlib
import re
import shutil
import subprocess
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
SRC = REPO / 'client/assets/scripts/ui/TableUI.ts'
MUT_DIR = REPO / 'client/temp/mut'
NODE = r'C:\Users\icymoon\.workbuddy\binaries\node\versions\22.22.2-3\node.exe'
NODE_PATH = r'C:\Users\icymoon\.workbuddy\binaries\node\workspace\node_modules'
TEST = REPO / 'tools/ui_smoke_test.js'

# (编号, 说明, 原文, 改坏后, 期望变红的断言关键字)
MUTATIONS = [
    (
        'M1', '去掉"扣王窗口开着时不给庄家扣底按钮"的守卫',
        'if (s.pickSeat != null) break;',
        'if (false) break;',
        ['扣王窗口开着时庄家不再看到「扣底」'],
    ),
    (
        'M2', '把 bottomRevealed 从底牌区签名里拿掉',
        "|${revealed ? 'rev' : ''}",
        "|",
        ['只有 bottomRevealed 变', '刷新后进入"已扣王"口径'],
    ),
    (
        'M3', '把 pickSeat 从底牌区签名里拿掉',
        '|pick:${s?.pickSeat === this.mySeat ? \'me\' : (s?.pickSeat ?? \'-\')}:${s?.pickMax ?? 0}`',
        '`',
        ['只有 pickSeat 变化', '提示条态下 pickSeat 变化'],
    ),
    (
        'M4', '把王的判定改成宽松的 includes("J")',
        "return code === 'BJ' || code === 'SJ';",
        "return code.includes('J');",
        ['点数为 J 的普通牌不能被误判'],
    ),
    (
        'M5', '把"轮到我扣王"的按钮分支删掉（退回只有庄家扣底）',
        'if (s.pickSeat === this.mySeat) {',
        'if (false) {',
        ['扣王窗口轮到我'],
    ),
    # ---- 以下五条针对"窗口期间不摊牌"（Tracy 拍板口径）----
    (
        'M6', '窗口开着却不画提示条 → 底牌区整块空掉，玩家不知道牌局停在扣王窗口',
        'const pickHint = !isPublic && codes.length === 0 && s?.pickSeat != null;',
        'const pickHint = false; // MUT',
        ['窗口开着但没人扣', '提示条说清'],
    ),
    (
        'M7', '提示条只在自己轮到时才画（轮到别人时底牌区一片空白）',
        'const pickHint = !isPublic && codes.length === 0 && s?.pickSeat != null;',
        'const pickHint = !isPublic && codes.length === 0'
        ' && s?.pickSeat === this.mySeat; // MUT',
        ['窗口开着但没人扣', '提示条说清'],
    ),
    (
        'M8', '窗口收口后仍强画提示条（留下"轮到你扣王"的残影）',
        '            if (pickHint) {',
        '            if (true) { // MUT',
        ['没人扣王、窗口收口'],
    ),
    (
        'M9', '提示条丢掉"最多能押几张"的上限',
        '? `底牌已扣回 · 轮到你扣王（最多 ${pickMax} 张，或点「跳过」）`',
        '? `底牌已扣回 · 轮到你扣王`',
        ['轮到我 → 提示条写明最多能押几张'],
    ),
    (
        'M10', '提示条不说是谁在表态（只留一句"底牌已扣回"）',
        ": `底牌已扣回 · 等${picker ?? '对家'}决定是否扣王`",
        ": '底牌已扣回'",
        ['提示条说清'],
    ),
    # ---- 以下三条针对 2026-09-18 的"选大王也提示只能扣王" ----
    # 这条就是那个真 bug 的**还原**：拿 selected 里的复合身份当牌码去喂 isJokerCode。
    (
        'M11', '把扣王的王判定退回用 this.selected（键当牌码 → 选大王也报错）',
        'if (pickedCodes.some(c => !TableUI.isJokerCode(c))) {',
        'if (this.selected.some(c => !TableUI.isJokerCode(c))) { // MUT',
        ['选大王点「扣王」→ 发出 PICK_JOKER', '选中带后缀的小王 SJ#0'],
    ),
    (
        'M12', 'selectedCodes 不再剥掉 #n 后缀（把键当牌码发给服务端）',
        'return p >= 0 ? k.slice(0, p) : k;',
        'return k; // MUT',
        ['发出去的是剥了后缀的牌码'],
    ),
    (
        'M13', 'cardKey 退回裸牌码（三副牌里同码的牌再也分不开，一次只能选一张）',
        'return `${code}#${occ}`;',
        'return code; // MUT',
        ['同码的两张王能各自选中'],
    ),
]


def run_test(src_path):
    env = dict(**__import__('os').environ)
    env['GUNZI_TABLE_UI'] = str(src_path)
    env['NODE_PATH'] = NODE_PATH
    p = subprocess.run([NODE, str(TEST)], capture_output=True, text=True,
                       encoding='utf-8', errors='replace', env=env, cwd=str(REPO))
    return p.stdout + p.stderr


def main():
    # 只跑指定编号（如 `_mut_check.py M11 M12`），不传就跑全部。
    # 与 _mut_check_server.py 保持一致 —— 改完一条断言想快速复验时，别等全部 13 条。
    only = {a.strip().upper() for a in sys.argv[1:]} if len(sys.argv) > 1 else None
    MUT_DIR.mkdir(parents=True, exist_ok=True)
    original = SRC.read_text(encoding='utf-8')
    bad = 0
    for mid, desc, old, new, expects in MUTATIONS:
        if only and mid not in only:
            continue
        if old not in original:
            print(f'  [SKIP] {mid} 原文没找到，可能实现已变：{old[:50]}')
            bad += 1
            continue
        target = MUT_DIR / f'{mid}.ts'
        target.write_text(original.replace(old, new, 1), encoding='utf-8')
        out = run_test(target)
        reds = [line.strip() for line in out.splitlines() if '[FAIL]' in line]
        ok = all(any(k in r for r in reds) for k in expects)
        hit = sum(1 for k in expects if any(k in r for r in reds))
        print(f'  [{"RED" if ok else "GREEN!"}] {mid} {desc}')
        print(f'         期望命中 {len(expects)} 条 / 实际命中 {hit} 条，共红 {len(reds)} 条')
        for r in reds[:4]:
            print(f'         {r}')
        if not ok:
            bad += 1
        target.unlink(missing_ok=True)
    shutil.rmtree(MUT_DIR, ignore_errors=True)
    print('=' * 49)
    print('全部变异都成功变红' if bad == 0 else f'{bad} 个变异没被发现（测试有漏洞）')
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
