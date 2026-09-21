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
TABLE_UI_REL = 'client/assets/scripts/ui/TableUI.ts'
NET_CLIENT_REL = 'client/assets/scripts/net/NetClient.ts'
SRC = REPO / TABLE_UI_REL
MUT_DIR = REPO / 'client/temp/mut'
# 源文件 -> 测试读取该副本所用的环境变量（ui_smoke_test.js 里对应 GUNZI_* 变量）
ENV_KEY_BY_FILE = {
    TABLE_UI_REL: 'GUNZI_TABLE_UI',
    NET_CLIENT_REL: 'GUNZI_NET_CLIENT',
}
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
    (
        'M24', '得分面板遮罩退回写死 1280（长条屏两侧留一条不变暗的缝）',
        # 锚点必须带上 alpha=120 那一行：只写 `        g.fillRect(...)`（8 空格）会**先命中
        # 断线遮罩**——断线那行的 12 空格里就含这 8 空格，且它在文件里更靠前。
        '        g.fillColor = new Color(0, 0, 0, 120);\n'
        '        g.fillRect(-this.vw / 2, -this.vh / 2, this.vw, this.vh);',
        '        g.fillColor = new Color(0, 0, 0, 120);\n'
        '        g.fillRect(-640, -360, 1280, 720); // MUT',
        ['得分面板遮罩铺满可见宽度'],
    ),
    (
        'M25', '断线遮罩退回写死 1280',
        '            g.fillRect(-this.vw / 2, -this.vh / 2, this.vw, this.vh);',
        '            g.fillRect(-640, -360, 1280, 720); // MUT',
        ['断线遮罩铺满可见宽度'],
    ),
    (
        'M26', '侧翼槽位写死 ±430（平板 4:3 上铭牌外缘 515 越过半宽 480 被裁）',
        '        return Math.min(430, Math.max(180, this.vw / 2 - halfPlate - margin));',
        '        return 430; // MUT',
        ['平板 4:3（可见宽 960）侧翼槽位必须内收'],
    ),
    (
        'M27', '手牌下行退回 -70（底边 -350，被 iPhone 横屏 Home 指示条压住）',
        '        const rowY: number[] = rows === 2 ? [6, -62] : [0];',
        '        const rowY: number[] = rows === 2 ? [6, -70] : [0]; // MUT',
        ['手牌最低边离屏幕底 ≥ 15 单位'],
    ),
    (
        'M28', '只改视觉不改命中区：fillRect 用 vw 但 UITransform 仍写死 1280（两侧点不动）',
        '        overlay.addComponent(UITransform).setContentSize(this.vw, this.vh);',
        '        overlay.addComponent(UITransform).setContentSize(1280, 720); // MUT',
        ['得分面板的命中区（UITransform）同样是可见宽度'],
    ),
    # ---- 以下五条针对"连接失败必须看得见"（2026-09-20 真机事故）----
    # 事故症状：顶栏永远停在"连接中…"，牌桌空着，连"连的是哪台机器"都看不到。
    # 根因是小游戏平台连不上时可能**只回调 onerror**、不回调 onopen/onclose，
    # 而当时 onerror 是空实现、又没有与平台无关的兜底 → stateHandler 永不触发 →
    # renderTop 永不执行 → UI 全程静默。这几条变异就是把修复逐项退回旧行为，
    # 确认新增的断言真的能抓住"退回静默"。
    (
        'M29', 'onerror 退回空实现（平台只报 onerror 时全程静默，顶栏停在「连接中…」）',
        "            const msg = (ev as { message?: string } | undefined)?.message;\n"
        "            this.failConnect(msg ? `连接出错：${msg}` : '连接出错（平台未给出原因）');",
        "            /* MUT：退回空实现，平台只报 onerror 时静默 */",
        ['平台只回调 onerror 时立即上报'],
        NET_CLIENT_REL,
    ),
    (
        'M30', '去掉建连超时自检（平台既不 onopen 也不 onclose 时永远干等）',
        "            this.failConnect(`连接超时：${NetClient.CONNECT_TIMEOUT_MS / 1000} 秒内未建立`\n"
        "                + `（readyState=${ws.readyState}）`);",
        "            /* MUT：吞掉建连超时 */;",
        ['自检超时必须判定离线并上报 UI'],
        NET_CLIENT_REL,
    ),
    (
        'M31', 'onopen 后不发 join（连上了但没入座 → 服务端不会推快照，牌桌永远空着）',
        "            this.rawSend({\n"
        "                op: 'join', roomId: this.roomId, playerId: this.playerId, seat: this.seat!,\n"
        "            });",
        "            /* MUT：连上但不入座 */",
        ['onopen 时立刻发出 join'],
        NET_CLIENT_REL,
    ),
    (
        'M32', '消息体退回 String(data)（小游戏给 ArrayBuffer 时解析失败、静默丢快照）',
        '            this.handleMessage(decodeMessageData(ev.data));',
        '            this.handleMessage(String(ev.data)); // MUT',
        ['消息体为 ArrayBuffer 时也能解析出快照'],
        NET_CLIENT_REL,
    ),
    (
        'M33', '顶栏不显示已重试次数（真机看不出在反复重连）',
        "            const tries = this.net && this.net.attempts > 1 ? `（已重试 ${this.net.attempts} 次）` : '';",
        "            const tries = ''; // MUT：不显示重试次数",
        ['显示已重试次数'],
    ),
    # ---- 以下五条针对"坐庄（收贡人）必须能自己还贡"（2026-09-20 真机 bug）----
    (
        'M34', '把还贡按钮挂回服务端**不存在**的 RETURN_TRIBUTE 阶段（真机 bug 第一层原样复现）',
        "                    const owed = this.pendingReturnOf(s);\n"
        "                    if (owed) {",
        "                    const owed = this.pendingReturnOf(s);\n"
        "                    if (owed && s.phase === 'RETURN_TRIBUTE') { // MUT",
        ['坐庄收到进贡'],
    ),
    (
        'M35', 'pendingReturnOf 不判断"已还"→ 还完贡按钮还赖在桌上，点了必被服务端拒',
        'if (t.receiver === this.mySeat && !done) {',
        'if (t.receiver === this.mySeat) {',
        ['已还过贡 → 不再出现「还贡」按钮'],
    ),
    (
        'M36', '还贡的 payee 传成自己（收件人该是进贡人；传错会被服务端拒或给错人）',
        "this.net?.sendCmd('RETURN_TRIBUTE', { cards: this.selectedCodes(), payee: still.payer });",
        "this.net?.sendCmd('RETURN_TRIBUTE', { cards: this.selectedCodes(), payee: this.mySeat }); // MUT",
        ['RETURN_TRIBUTE 的 payee 必须是进贡人本人'],
    ),
    (
        'M37', '还贡不进按钮签名 → 还完贡按钮不消失 / 第二笔血切不过去',
        "                    const owed = this.pendingReturnOf(s);\n"
        "                    p.push(owed ? `ret:${owed.payer}:${owed.count}` : '-');",
        "                    p.push('-'); // MUT：还贡不进签名",
        ['签名把「还贡对象'],
    ),
    (
        'M38', '去掉还贡张数的本地校验（选 1 张也发出去，让服务端拒绝 + 弹红字）',
        "                            if (this.selected.length !== still.count) {\n"
        "                                this.showToast(`要还 ${still.count} 张（已选 ${this.selected.length} 张）`, true);\n"
        "                                return;\n"
        "                            }",
        "                            if (this.selected.length !== still.count) {\n"
        "                                this.showToast(`要还 ${still.count} 张（已选 ${this.selected.length} 张）`, true);\n"
        "                            }",
        ['张数不足点「还贡」→ 本地拦下并说明要还几张'],
    ),
]


def run_test(src_path, env_key='GUNZI_TABLE_UI'):
    env = dict(**__import__('os').environ)
    env[env_key] = str(src_path)
    env['NODE_PATH'] = NODE_PATH
    p = subprocess.run([NODE, str(TEST)], capture_output=True, text=True,
                       encoding='utf-8', errors='replace', env=env, cwd=str(REPO))
    return p.stdout + p.stderr


def main():
    # 只跑指定编号（如 `_mut_check.py M11 M12`），不传就跑全部。
    # 与 _mut_check_server.py 保持一致 —— 改完一条断言想快速复验时，别等全部 13 条。
    only = {a.strip().upper() for a in sys.argv[1:]} if len(sys.argv) > 1 else None
    MUT_DIR.mkdir(parents=True, exist_ok=True)
    bad = 0
    broken = 0
    ambig = 0
    src_cache = {}          # 按文件缓存源码：TableUI 与 NetClient 各读一次
    for entry in MUTATIONS:
        # 前 5 项固定；第 6 项可选，用来指定目标源文件（默认 TableUI.ts）。
        # 需要多文件是因为"连接失败必须可见"这类逻辑在 net/NetClient.ts 里，
        # 而它恰恰是本次真机问题的核心 —— 只在 TableUI 上做变异覆盖不到。
        mid, desc, old, new, expects = entry[:5]
        rel = entry[5] if len(entry) > 5 else TABLE_UI_REL
        env_key = ENV_KEY_BY_FILE.get(rel)
        if env_key is None:
            # 新加文件却忘了在这儿登记 → 直接报错，否则会被当成 TableUI 的去跑，
            # 结果是"锚点找不到"的 SKIP，看起来像源码改了、其实只是漏配。
            print(f'  [SKIP] {mid} 源文件 {rel} 未登记对应的环境变量（见 ENV_KEY_BY_FILE）')
            bad += 1
            continue
        if only and mid not in only:
            continue
        if rel not in src_cache:
            src_cache[rel] = (REPO / rel).read_text(encoding='utf-8')
        original = src_cache[rel]
        if old not in original:
            print(f'  [SKIP] {mid} 原文没找到，可能实现已变（{rel}）：{old[:50]}')
            bad += 1
            continue
        n_site = original.count(old)
        if n_site > 1:
            # 锚点撞了多处：`replace(old, new, 1)` 只改第一处，而"第一处"未必是想改的那处 ——
            # 缩进不同的两行，**短的那串会作为子串先命中长的那行**。症状极隐蔽：变异确实
            # 让测试变红了，但红的是**另一条**断言，而目标断言永远绿（2026-09-18 实测：
            # M24 想改「得分面板遮罩」，8 空格锚点先命中了缩进 12 空格的「断线遮罩」）。
            # 所以当错误报出来，逼着把锚点加长到唯一。
            print(f'  [AMBIG] {mid} 锚点在源码里出现 {n_site} 次，改哪一处不确定：{old[:56]!r}')
            print('          把锚点加长（带上相邻唯一的一行，如 fillColor 的 alpha）再试')
            bad += 1
            ambig += 1
            continue
        # **固定同一个临时文件名**（每次覆盖写），不要每条变异建一个再删一个：
        # 2026-09-18 实测踩过 —— 一轮全表 20+ 次 unlink 会撞上环境的"批量删除需确认"阈值，
        # 脚本在第 3 条变异上就被打断（exit=1，日志停在 M2），而看起来像"测试有问题"。
        target = MUT_DIR / '_mutant.ts'
        target.write_text(original.replace(old, new, 1), encoding='utf-8')
        out = run_test(target, env_key)
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
    if ambig:
        print(f'{ambig} 个变异锚点不唯一（AMBIG），可能改错了地方，必须先加长锚点')
    if bad - ambig:
        print(f'{bad - ambig} 个变异没被发现（测试有漏洞）')
    if not bad and not broken:
        print('全部变异都成功变红')
    return 1 if (bad or broken) else 0


if __name__ == '__main__':
    sys.exit(main())
