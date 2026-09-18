# -*- coding: utf-8 -*-
"""把 ui_smoke_test.js 的第 23 段（扣王窗口断言）整段换成"窗口期间不摊牌"的新口径。

起因：原实现是"开窗就摊牌"，Tracy 拍板改成"窗口期间不摊牌，真有人扣了才公开"，
所以旧断言（窗口开着就必须有 6 张牌面）现在语义正好相反。
按锚点整段替换，避免多行 Edit 因匹配范围过大而失手。
"""
import io, sys

PATH = r'D:\workbuddy\projects\gunzi-hall\tools\ui_smoke_test.js'
START = '// --- 23. 【Tracy 核心需求】有人扣王 → 底牌向全体公开，且公开期间说清进度 ---'
END = '// --- 24. 王的判定必须"整码精确比对"，且这个静态方法必须真的存在 ---'

NEW = u'''// --- 23. 【Tracy 拍板】扣王窗口期间**不摊牌**：真有人扣了才公开 ---
// 原实现是"开窗就把底牌摊开"（不看到牌面没法判断要不要押），Tracy 定的是
// "窗口期间不摊牌，真有人扣了才公开" —— 手册 2.3.5 的公开是**押中的后果**，不是开窗的前提。
{
    const { ui, labels } = newBottomUi();
    const me = ui.mySeat;
    const other = TableUI.ALL_SEATS.find(s => s !== me);
    const last = () => labels[labels.length - 1] || '';

    // (a) 窗口开着、轮到别人、谁都没扣 → 底牌一张都不透，只留一条"轮到谁"的提示条
    ui.snap = {
        gameNumber: 1, phase: 'BURYING', dryPot: false,
        bottomRevealed: false, pickSeat: other, pickMax: 2,
    };
    ui.renderBottom();
    check('窗口开着但没人扣 → 底牌不摊（只有提示条，没有 6 张牌面）',
        ui.bottomNode.children.length === 1, `children=${ui.bottomNode.children.length}`);
    check('提示条说清"底牌已扣回"且在等谁，不提前说"已扣王"',
        /已扣回/.test(last()) && /决定是否扣王/.test(last()) && !/已扣王/.test(last()), last());

    // (b) 轮到我 → 提示条给出押注上限，同样不摊牌
    ui.snap = Object.assign({}, ui.snap, { pickSeat: me });
    ui.renderBottom();
    check('轮到我 → 提示条写明最多能押几张，且仍不摊牌',
        ui.bottomNode.children.length === 1
        && /轮到你扣王/.test(last()) && /2/.test(last()), last());

    // (c) 全程没人扣、窗口收口 → 底牌区必须整块清空
    ui.snap = { gameNumber: 1, phase: 'PLAYING', dryPot: false };
    ui.renderBottom();
    check('没人扣王、窗口收口 → 提示条与牌面一起收起（不留残影）',
        ui.bottomNode.children.length === 0, `children=${ui.bottomNode.children.length}`);

    // (d) 真有人扣了 → 这一刻才摊牌，且本局一直公开
    ui.snap = {
        gameNumber: 1, phase: 'PLAYING', dryPot: false,
        bottom: SIX_BOTTOM, bottomRevealed: true,
    };
    ui.renderBottom();
    check('有人扣王 → 出牌阶段底牌对全体公开',
        ui.bottomNode.children.length === 8, `children=${ui.bottomNode.children.length}`);
    check('标题改为"已扣王（本局公开到结算）"',
        /已扣王/.test(last()) && /公开/.test(last()), last());

    // (e) 【易错】窗口里刚有人扣、还没收口：提示条必须立刻让位给牌面，不能两态叠着
    ui.snap = {
        gameNumber: 1, phase: 'BURYING', dryPot: false,
        bottom: SIX_BOTTOM, bottomRevealed: true, pickSeat: other, pickMax: 2,
    };
    ui.renderBottom();
    check('窗口内就有人扣了 → 提示条让位，当场摊出 6 张牌面',
        ui.bottomNode.children.length === 8, `children=${ui.bottomNode.children.length}`);
    check('牌面可见时标题走"等 XX 决定是否扣王"（窗口还在走）',
        /决定是否扣王/.test(last()), last());

    // (f) 庄家视角：窗口期间他拿的是私有回看胶囊，不是公开提示条
    ui.snap = {
        gameNumber: 1, phase: 'BURYING', dryPot: false,
        myBottom: SIX_BOTTOM, bottomRevealed: false, pickSeat: other, pickMax: 2,
    };
    ui.renderBottom();
    check('庄家在窗口期间仍能回看自己的底牌（私有胶囊优先于提示条）',
        /我的底牌/.test(last()), last());

    // (g) 签名必须带 bottomRevealed：牌面/阶段/干锅都没变，只有它变 → 文案也得刷新
    ui.snap = {
        gameNumber: 1, phase: 'BURYING', dryPot: false,
        bottom: SIX_BOTTOM, bottomRevealed: false,
    };
    ui.renderBottom();
    const n0 = labels.length;
    ui.snap = Object.assign({}, ui.snap, { bottomRevealed: true });
    ui.renderBottom();
    check('只有 bottomRevealed 变 → 标签必须刷新（签名要带 revealed）',
        labels.length === n0 + 1, `labels=${labels.length - n0}`);
    check('刷新后进入"已扣王"口径',
        labels.length === n0 + 1 && /已扣王/.test(last()), last());

    // (h) 签名必须带 pickSeat：同一副牌面，从"别人"切到"我" → 文案也得刷新
    const n1 = labels.length;
    ui.snap = Object.assign({}, ui.snap, { bottomRevealed: false, pickSeat: other, pickMax: 2 });
    ui.renderBottom();
    ui.snap = Object.assign({}, ui.snap, { pickSeat: me });
    ui.renderBottom();
    check('只有 pickSeat 变化 → 标签必须刷新（签名要带 pickSeat）',
        labels.length === n1 + 2 && /轮到你扣王/.test(last()),
        `labels=${labels.length - n1}, last=${last()}`);

    // (i) 【本轮最容易漏】底牌不可见时，"谁在表态"全靠提示条 —— 签名若只在公开态带
    //     pickSeat，提示条会一直停在上一家的名字上。
    ui.snap = {
        gameNumber: 1, phase: 'BURYING', dryPot: false,
        bottomRevealed: false, pickSeat: other, pickMax: 2,
    };
    ui.renderBottom();
    const n2 = labels.length;
    ui.snap = Object.assign({}, ui.snap, { pickSeat: me });
    ui.renderBottom();
    check('提示条态下 pickSeat 变化也必须刷新（签名不能只在公开态生效）',
        labels.length === n2 + 1 && /轮到你扣王/.test(last()),
        `labels=${labels.length - n2}, last=${last()}`);
}

'''

with io.open(PATH, 'r', encoding='utf-8') as f:
    text = f.read()

i = text.find(START)
j = text.find(END)
if i < 0 or j < 0 or j <= i:
    sys.exit('anchor not found: i=%d j=%d' % (i, j))

out = text[:i] + NEW + text[j:]
with io.open(PATH, 'w', encoding='utf-8', newline='\n') as f:
    f.write(out)

print('replaced %d chars -> %d chars' % (j - i, len(NEW)))
