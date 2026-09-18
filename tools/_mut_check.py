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
    # ---- 针对 2026-09-18 Tracy 反馈的"抓分方上台后庄家标记不切" ----
    # 这条是那个 bug 的**还原**：牌桌标记的庄家口径退回旧写法，
    # DEALING / BIDDING（新一局的发牌与亮主）会落到"其余"分支去取 preSettleBanker
    # （上一局的庄）→ 下一局开局时"庄"瓦片仍挂在原来的庄家身上，扣底才跳过去。
    (
        'M14', '牌桌庄家口径退回"DEALING/BIDDING 用 preSettleBanker"（下一局庄瓦片不切）',
        'return s.banker ?? this.preSettleBanker;',
        'return TableUI.IN_GAME_PHASES.has(s.phase)'
        ' ? s.banker : this.preSettleBanker; // MUT',
        ['【回归】下一局发牌：庄瓦片必须跟到新庄 NORTH',
         '下一局亮主：庄瓦片仍在 NORTH'],
    ),
    # ---- 以下五条针对 2026-09-18 的"第一行改报主牌 + 面板补本局事实" ----
    (
        'M15', '第一行退回"局号 + 阶段词"（出牌/亮主/扣底又回来占标题位）',
        "return `第${s.gameNumber}局 · ${s.trump ? '主牌' : '主牌未定'}`;",
        "return `第${s.gameNumber}局 · ${TableUI.PHASE_TEXT[s.phase] ?? s.phase}`; // MUT",
        ['第一行标题 = 「第3局 · 主牌」', '无论什么阶段，第一行都不再出现阶段词'],
    ),
    (
        'M16', '"是否扣王"的高亮拿可见性字段顶替（干锅局会误报成扣王）',
        'hot: s?.jokerBuried === true',
        'hot: s?.bottomRevealed === true // MUT',
        ['干锅 + 扣王都发生时两段都高亮'],
    ),
    (
        'M17', '"是否扣王"的文案拿可见性字段顶替（面板会报"扣王 否"）',
        "text: `扣王 ${s?.jokerBuried ? '是' : '否'}`",
        "text: `扣王 ${s?.bottomRevealed ? '是' : '否'}` // MUT",
        ['面板报出「扣王 是」'],
    ),
    (
        'M18', '进贡明细只列贡牌、不列还贡牌',
        '            if (t.returned && t.returned.length > 0) {',
        '            if (false) { // MUT',
        ['已交已还：两行（进贡 + 还贡）', '面板列出「进贡」「还贡」两行明细'],
    ),
    (
        'M19', '待进贡（还没交）的那一行不画（面板只报"进贡 是"却看不到欠谁多少）',
        '            const ob = pending[seat];\n            if (!ob) continue;',
        '            const ob = pending[seat];\n            if (true) continue; // MUT',
        ['还没交贡：只有一行「待进贡 N 张」，不带牌面'],
    ),
    (
        'M20', '面板内 ♠/♣ 花色图标退回 suitColor 的近黑（深蓝底上等于没画）',
        '            : new Color(226, 232, 240, 255);   // 浅灰白：♠/♣ 的"黑"在深底上要反过来提亮',
        '            : new Color(30, 30, 30, 255); // MUT',
        ['面板内 ♠/♣ 花色图标必须提亮'],
    ),
    (
        'M21', '面板内 ♥/♦ 花色图标丢掉红色语义（也变成浅灰白）',
        '            ? new Color(232, 84, 84, 255)      // 亮红：深蓝底上够扎眼',
        '            ? new Color(226, 232, 240, 255)  // MUT',
        ['面板内 ♥/♦ 花色图标为亮红'],
    ),
    (
        'M22', '配色算对了却忘了用：调用点退回 suitColor(grp.suit)',
        '                addSuitIcon(overlay, grp.suit, -PW / 2 + 44, y, 18, this.panelSuitColor(grp.suit));',
        '                addSuitIcon(overlay, grp.suit, -PW / 2 + 44, y, 18, suitColor(grp.suit)); // MUT',
        ['面板把提亮配色真正交给了 ♠ 图标'],
    ),
    (
        'M23', '面板顶部与分牌段标题重复（同一句「闲家已捡分牌」出现两次）',
        "        this.addPanelText(overlay, '本局明细', 0, y, 22, gold, true);",
        "        this.addPanelText(overlay, `闲家已捡分牌 · 共 ${info.score} 分`, 0, y, 22, gold, true); // MUT",
        ['顶部报「本局明细」'],
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
    broken = 0
    for mid, desc, old, new, expects in MUTATIONS:
        if only and mid not in only:
            continue
        if old not in original:
            print(f'  [SKIP] {mid} 原文没找到，可能实现已变：{old[:50]}')
            bad += 1
            continue
        # **固定同一个临时文件名**（每次覆盖写），不要每条变异建一个再删一个：
        # 2026-09-18 实测踩过 —— 一轮全表 20+ 次 unlink 会撞上环境的"批量删除需确认"阈值，
        # 脚本在第 3 条变异上就被打断（exit=1，日志停在 M2），而看起来像"测试有问题"。
        target = MUT_DIR / '_mutant.ts'
        target.write_text(original.replace(old, new, 1), encoding='utf-8')
        out = run_test(target)
        reds = [line.strip() for line in out.splitlines() if '[FAIL]' in line]
        ran = any('[OK]' in line for line in out.splitlines())
        if not reds and not ran:
            # 变异体**一条断言都没跑**：这多半是 old/new 串写错，把文件改成了语法错误。
            # 既不能算"成功变红"，也不能算"测试有漏洞" —— 必须单独报出来。
            # 不区分的话，一个笔误就会以"绿"的形态混过去，整张变异表就全不可信了
            # （2026-09-18 踩过：M21 的 new 串多带一个分号，就是这样假绿的）。
            print(f'  [BROKEN] {mid} {desc}')
            print('           变异体没跑起来（多半语法错误），请检查 old/new 串是否与源码逐字一致')
            for line in out.splitlines()[-4:]:
                print(f'           {line.strip()}')
            broken += 1
            continue
        ok = all(any(k in r for r in reds) for k in expects)
        hit = sum(1 for k in expects if any(k in r for r in reds))
        print(f'  [{"RED" if ok else "GREEN!"}] {mid} {desc}')
        print(f'         期望命中 {len(expects)} 条 / 实际命中 {hit} 条，共红 {len(reds)} 条')
        for r in reds[:4]:
            print(f'         {r}')
        if not ok:
            bad += 1
    # 刻意**不清理**这个临时文件：变异体每轮覆盖写在同一个 `_mutant.ts` 上，本来就只有一份；
    # 再为"收尾"多删一次，只会继续消耗环境的删除配额（这个目录是 Cocos 的 temp，不进版本库）。
    print('=' * 49)
    if broken:
        print(f'{broken} 个变异体没编译过（BROKEN），结果不可信，请先修 old/new 串')
    if bad:
        print(f'{bad} 个变异没被发现（测试有漏洞）')
    if not bad and not broken:
        print('全部变异都成功变红')
    return 1 if (bad or broken) else 0


if __name__ == '__main__':
    sys.exit(main())
