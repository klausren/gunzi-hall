/*
 * TableUI 提示（toast）行为回归测试。
 *
 * 为什么需要它
 * ------------
 * 客户端没有测试框架，TableUI 又强依赖 cc 运行时，所以"提示什么时候消失"这类
 * 纯状态逻辑以前只能靠肉眼在浏览器里盯。结果是踩了真 bug：
 *   出牌失败的红字只要之后没有新事件（出牌成功的事件是被有意过滤掉的），
 *   就永远留在牌桌上，下一把还能看见。
 * 这类"UI 里的陈旧状态没人清"的 bug 复现成本高（要真打到出错），
 * 所以这里把 TableUI.ts 直接转译后在 Node 里跑：cc 与兄弟模块用代理桩顶掉，
 * 只测真正会出错的那几个方法（showToast / clearToast / syncToastContext / update /
 * renderBottom / renderSettlement）。
 *
 * 运行
 * ----
 *   node tools/ui_smoke_test.js
 * （需要 typescript：用受管 node 工作区，见下方 TS 解析顺序）
 *
 * 设计取舍
 * --------
 * · 不引入 jest/mocha：只想在改客户端代码后能一条命令确认"提示不会粘住"、
 *   "结算面板不挡操作区"、"扣完底后庄家还能回看自己的底牌"。
 * · cc 分两层桩：① 万能代理桩（makeStub）顶掉装饰器与用不到的引擎 API；
 *   ② 一小撮**有状态的假实现**（makeFakeCc 里的 Node/UITransform/Graphics/Label…）——
 *   结算面板的断言要看几何（尺寸/位置）与"点了会不会关"，纯代理桩什么都读不到。
 */
'use strict';

const fs = require('fs');
const path = require('path');
const Module = require('module');

const REPO = path.resolve(__dirname, '..');
// 允许用 GUNZI_TABLE_UI 指向一份候选文件（用来确认"改成旧写法时本测试会红"）
const TABLE_UI = process.env.GUNZI_TABLE_UI
    || path.join(REPO, 'client', 'assets', 'scripts', 'ui', 'TableUI.ts');

// 客户端源码根目录。**兄弟模块一律从这里解析**，绝不能从被测文件的位置推导：
// 变异验证会把副本丢到 client/temp/mut/ 下，从那里推导会指向不存在的目录，
// 结果不是"断言变红"而是整个测试崩掉（看起来像脚本坏了）。
const SCRIPTS = path.join(REPO, 'client', 'assets', 'scripts');
// 允许用 GUNZI_NET_CLIENT 指向一份候选的网络层副本（同样用于变异验证）
const NET_CLIENT = process.env.GUNZI_NET_CLIENT
    || path.join(SCRIPTS, 'net', 'NetClient.ts');

// ---------- 1. 找到 typescript ----------
function resolveTypescript() {
    const cands = [
        'typescript',
        path.join(process.env.USERPROFILE || '', '.workbuddy', 'binaries', 'node',
            'workspace', 'node_modules', 'typescript'),
        'C:/Users/icymoon/.workbuddy/binaries/node/workspace/node_modules/typescript',
        'C:/ProgramData/cocos/editors/Creator/3.8.8/resources/app.asar.unpacked/node_modules/typescript',
    ];
    for (const c of cands) {
        try { return require(c); } catch { /* 试下一个 */ }
    }
    throw new Error('找不到 typescript。请设置 NODE_PATH 指向受管 node 工作区的 node_modules');
}
const ts = resolveTypescript();

// ---------- 2. 万能桩：任何属性都返回一个"可调用且可 new"的代理 ----------
// 三个关键点：
//  · apply 返回 undefined —— `@ccclass('X')` 这类装饰器工厂的返回值因此是 falsy，
//    TS 的 __decorate 会跳过它（`if (d = decorators[i])`），类不会被替换掉。
//  · **不要**定义 construct 陷阱 —— 一旦陷阱返回普通对象，`super()` 拿到的 this
//    就不再是 TableUI.prototype 的实例，实例上所有方法都会消失
//    （初版 harness 就栽在这里：TypeError: ui.showToast is not a function）。
//    不定义陷阱时 [[Construct]] 走默认路径，this 的原型仍是 new.target.prototype。
//  · `prototype` 也返回代理 —— 否则 `new Node()` 出来的对象原型是**普通对象**，
//    上面的方法全是 undefined（`strip.addChild(...)` 会 TypeError）。
function makeStub(label) {
    const target = function () { /* stub */ };
    const handler = {
        get(t, k) {
            if (k === Symbol.toPrimitive || k === 'toString') return () => `[stub ${label}]`;
            if (k === 'then') return undefined;              // 别被当成 thenable
            if (k === 'prototype') return new Proxy(t.prototype, handler);
            if (!(k in t)) return makeStub(`${label}.${String(k)}`);
            return t[k];
        },
        apply() { return undefined; },
    };
    return new Proxy(target, handler);
}

// ---------- 2b. CardUI 的假道具：瓦片/文字都返回能过 layoutTileStrip 的哑节点 ----------
// 只用桩的话 `createTileNode()` 返回 undefined，layoutTileStrip 里 `tiles[0].getComponent`
// 会炸。这里返回最小可用的节点，并把每次创建的瓦片记进 createdTiles，
// 让测试能直接断言"画了几个瓦片"（= 铭牌上长出了什么）。
//
// 【为什么要 on/emit/position】手牌那条路径（renderHand）会把牌节点当事件源：
// `card.on(TOUCH_START, ...)` → 玩家点牌 → `toggleSelect()` 读 `card.position.x`。
// 少了这几样，测试就只能手写 ui.selected，而手写的"裸牌码"和真实的"复合身份"
// 是两种形状 —— 2026-09-18"选大王也提示只能扣王"正是被这个差异漏掉的（见第 24 段）。
let createdTiles = [];
// addSuitIcon 的调用留痕：用来断言"面板算对了提亮配色，也真的把提亮配色交了出去"
let suitIconCalls = [];
// createCardNode 的调用留痕：用来量手牌的几何（每行 baseY → 最低边离屏幕底多远）
let createdCards = [];
const fakeTile = () => ({
    children: [],
    handlers: {},
    position: { x: 0, y: 0, z: 0 },
    on(type, cb) { (this.handlers[type] = this.handlers[type] || []).push(cb); return this; },
    emit(type) { (this.handlers[type] || []).slice().forEach(f => f()); },
    getComponent: () => ({ contentSize: { width: 22 }, setContentSize: () => { /* noop */ } }),
    setPosition(x, y, z) {
        this.position = (x && typeof x === 'object') ? x : { x, y, z };
        return this;
    },
    setScale: () => { /* noop */ },
    addChild: () => { /* noop */ },
    destroy: () => { /* noop */ },
});
const cardUiStub = {
    createTileNode: () => { const t = fakeTile(); createdTiles.push(t); return t; },
    addTileText: () => { /* noop */ },
    addSuitIcon: (parent, suit, x, y, size, color) => { suitIconCalls.push({ suit, color }); },
    suitColor: () => '#000000',
    cardFace: (c) => ({ text: String(c) }),
    createCardNode: () => { const t = fakeTile(); createdCards.push(t); return t; },
    createMiniCardNode: () => fakeTile(),
    drawCardBg: () => { /* noop */ },
};

// ---------- 2c. 有状态的 cc 假实现：够跑 renderSettlement / renderBottom 的那一套 ----------
// 万能桩看不出"面板多大、摆在哪、点了会怎样"，而这三件事正是本次要锁的回归点。
// 这里只实现被用到的那几个类，其余键继续落到 makeStub（装饰器、@property 等）。
function makeFakeCc() {
    class UITransform {
        constructor() { this.contentSize = { width: 0, height: 0 }; }
        setContentSize(w, h) { this.contentSize = { width: w, height: h }; }
    }
    /** Graphics：把每条绘制指令记下来，测试就能断言"有没有铺全屏黑幕" */
    class Graphics {
        constructor() { this.ops = []; this.fills = 0; this.strokes = 0; }
        clear() { this.ops = []; }
        roundRect(x, y, w, h, r) { this.ops.push({ op: 'roundRect', x, y, w, h, r }); }
        fillRect(x, y, w, h) { this.ops.push({ op: 'fillRect', x, y, w, h }); }
        rect(x, y, w, h) { this.ops.push({ op: 'rect', x, y, w, h }); }
        ellipse() { /* noop */ }
        moveTo() { /* noop */ }
        lineTo() { /* noop */ }
        close() { /* noop */ }
        fill() { this.fills++; }
        stroke() { this.strokes++; }
    }
    class Label {
        constructor() { this.string = ''; this.fontSize = 0; this.color = null; }
    }
    Label.HorizontalAlign = { LEFT: 0, CENTER: 1, RIGHT: 2 };
    Label.VerticalAlign = { TOP: 0, CENTER: 1, BOTTOM: 2 };
    class UIOpacity { constructor() { this.opacity = 255; } }
    class Color { constructor(r, g, b, a) { this.r = r; this.g = g; this.b = b; this.a = a; } }
    class Vec3 { constructor(x, y, z) { this.x = x; this.y = y; this.z = z; } }

    class Node {
        constructor(name) {
            this.name = name;
            this.children = [];
            this.parent = null;
            this._comps = [];
            this.handlers = {};
            this.active = true;
            this.destroyed = false;
        }
        addComponent(T) { const c = new T(); c.node = this; this._comps.push(c); return c; }
        getComponent(T) { return this._comps.find(c => c instanceof T) || null; }
        addChild(c) { c.parent = this; this.children.push(c); return c; }
        removeAllChildren() { for (const c of this.children) c.parent = null; this.children = []; }
        setPosition(x, y, z) {
            this.position = (x && typeof x === 'object') ? x : { x, y, z };
            return this;
        }
        setScale(x, y, z) { this.scale = { x, y, z }; return this; }
        on(type, cb) { (this.handlers[type] = this.handlers[type] || []).push(cb); return this; }
        off(type, cb) {
            const a = this.handlers[type];
            if (a) this.handlers[type] = a.filter(f => f !== cb);
            return this;
        }
        /** 测试用：模拟点一下（'touch-start' 等，见 Node.EventType） */
        emit(type) { (this.handlers[type] || []).slice().forEach(f => f()); }
        destroy() { this.destroyed = true; }
    }
    Node.EventType = {
        TOUCH_START: 'touch-start', TOUCH_MOVE: 'touch-move',
        TOUCH_END: 'touch-end', TOUCH_CANCEL: 'touch-cancel',
    };

    /** tween 链：立即执行 call（收集动画那类回调靠它落到现场），其余返回自身 */
    function tweenStub() {
        const chain = {
            to: () => chain, by: () => chain, delay: () => chain,
            call: (f) => { if (typeof f === 'function') f(); return chain; },
            union: () => chain, start: () => chain, stop: () => chain,
        };
        return chain;
    }

    const known = {
        Node, UITransform, Graphics, Label, UIOpacity, Color, Vec3,
        tween: tweenStub,
        Tween: { stopAllByTarget: () => { /* noop */ } },
        Component: class Component { },
        view: { getVisibleSize: () => ({ width: 1280, height: 720 }) },
        game: { canvas: null },
    };
    return new Proxy(known, {
        get(t, k) {
            if (k in t) return t[k];
            if (k === 'then') return undefined;
            return makeStub(`cc.${String(k)}`);
        },
    });
}

// ---------- 3. 转译 + 加载 TableUI.ts ----------
function loadTableUI(ccImpl) {
    const src = fs.readFileSync(TABLE_UI, 'utf8');
    const out = ts.transpileModule(src, {
        compilerOptions: {
            module: ts.ModuleKind.CommonJS,
            target: ts.ScriptTarget.ES2019,
            experimentalDecorators: true,
            esModuleInterop: true,
            removeComments: false,
        },
        fileName: TABLE_UI,
    });
    const mod = { exports: {} };
    const requireShim = (spec) => {
        if (spec === 'cc') return ccImpl;
        if (spec === './CardUI') return cardUiStub;
        // 真实加载地址解析模块：顶栏"连不上/连的哪台机器"的文案依赖它，
        // 用 Proxy stub 会让断言看到 undefined，测不出真问题。
        // （注意 TableUI 在 ui/ 下，import 的是 '../net/ServerUrl'，别只匹配 './net/...'）
        if (/\/ServerUrl$/.test(spec)) {
            return loadTsModule(path.join(SCRIPTS, 'net', 'ServerUrl.ts'));
        }
        if (spec.startsWith('.')) return makeStub(spec);   // 其他兄弟模块：被测逻辑用不到
        return Module.createRequire(TABLE_UI)(spec);
    };
    const fn = new Function('require', 'module', 'exports', '__filename', '__dirname', out.outputText);
    fn(requireShim, mod, mod.exports, TABLE_UI, path.dirname(TABLE_UI));
    if (!mod.exports.TableUI) throw new Error('TableUI.ts 未导出 TableUI');
    return mod.exports.TableUI;
}

// ---------- 4. 断言 ----------
let failed = 0;
function check(name, cond, extra) {
    if (cond) { console.log(`  [OK]   ${name}`); return; }
    failed++;
    console.log(`  [FAIL] ${name}${extra ? '  -> ' + extra : ''}`);
}

const FAKE_CC = makeFakeCc();
const TableUI = loadTableUI(FAKE_CC);

/** 造一个只带 toast 相关状态的最小实例，不跑 onLoad（不建 UI 节点） */
function newUi() {
    const ui = new TableUI();
    ui.toastLabel = { string: '', color: null };
    ui.toastCtxKey = '';
    ui.toastUntil = 0;
    ui.toastSticky = false;
    return ui;
}

/** 造快照：只带 syncToastContext/extractTrickPlays 需要的字段 */
function snap(gameNumber, phase, turn, plays) {
    return {
        gameNumber, phase, turn,
        trick: plays && plays.length ? { plays } : undefined,
    };
}
const trick = (leader, cards) => ({ seat: leader, cards });

/**
 * 造一个能跑铭牌标记（renderSeatBadges / renderTrumpMark / renderBankerMark）的实例：
 * 铭牌根节点用哑节点顶掉，四家的标记账本各清一份。
 */
function newBadgeUi() {
    const ui = newUi();
    ui.seatRoot = {
        NORTH: { addChild: () => { /* noop */ } },
        EAST: { addChild: () => { /* noop */ } },
        SOUTH: { addChild: () => { /* noop */ } },
        WEST: { addChild: () => { /* noop */ } },
    };
    ui.seatTrumpNode = {};
    ui.seatTrumpSig = {};
    ui.seatBankerNode = {};
    ui.seatBankerSig = {};
    return ui;
}
const resetTiles = () => { createdTiles = []; };

/**
 * 底牌展示区（renderBottom）的可观察容器。
 * renderBottom 只碰 bottomNode 的 children / addChild / removeAllChildren，
 * 以及子节点的 getComponent → setContentSize；其余一律走 CardUI 的假道具。
 */
function fakeContainer(tag) {
    return {
        tag,
        children: [],
        addChild(c) { this.children.push(c); },
        removeAllChildren() { this.children = []; },
        getComponent: () => ({ setContentSize: () => { /* noop */ } }),
        setPosition: () => { /* noop */ },
        setScale: () => { /* noop */ },
        destroy: () => { /* noop */ },
    };
}

/** 造一个能跑 renderBottom 的实例；labels 收集每次传给 makeLabelNode 的文案 */
function newBottomUi() {
    const ui = newUi();
    ui.bottomNode = fakeContainer('bottom');
    const labels = [];
    ui.makeLabelNode = (text) => { labels.push(text); return fakeContainer('label'); };
    return { ui, labels };
}

/**
 * 造一个能跑 renderSettlement 的实例。
 * 服务端没有 SETTLED 阶段 —— 结算命令直接开下一局，所以快照的 phase 就是 DEALING，
 * 这条正是 Tracy 反馈"弹窗挡着下局发牌"的场景，测试也照这个摆。
 */
function newSettleUi(opts) {
    const ui = newUi();
    ui.node = new FAKE_CC.Node('root');       // 面板会被 addChild 到这里
    ui.snap = {
        gameNumber: 2,
        phase: (opts && opts.phase) || 'DEALING',
        banker: 'SOUTH',
        settlement: {
            attackerScore: 135, bankerScore: 105, attackerTakesBank: true,
            attackerPromoted: false, bankerPromoted: false, dugBottom: false,
        },
    };
    ui.preSettleBanker = 'SOUTH';
    ui.settleVisibleUntil = Date.now() + 6000;
    return ui;
}

console.log('TableUI 回归测试（提示生命周期 + 铭牌标记 + 底牌展示区 + 扣王窗口）');
console.log('=================================================');
// --- 1. 失败提示自带到期时间，不再"永远挂着" ---
{
    const ui = newUi();
    const t0 = Date.now();
    ui.showToast('SOUTH PLAY ♠4 失败：首家有花色必须跟出', true);
    check('失败提示写入后不再为空', ui.toastLabel.string.includes('失败'));
    check('失败提示带 4.5s 到期时间（旧代码永远是 0）',
        ui.toastUntil - t0 >= 4000 && ui.toastUntil - t0 <= 5000,
        `toastUntil-t0=${ui.toastUntil - t0}`);

    // 到期后 update() 必须把它清掉（覆盖"牌局卡住、没有新快照"的场景）
    ui.toastUntil = Date.now() - 1;
    ui.update(0.016);
    check('到期后 update() 清空提示（卡局兜底）', ui.toastLabel.string === '', JSON.stringify(ui.toastLabel.string));
}

// --- 2. 出牌失败后、牌局没有前进时，提示必须留着给人读完 ---
{
    const ui = newUi();
    const s1 = snap(1, 'PLAYING', 'SOUTH', [trick('NORTH', ['♠4'])]);
    ui.syncToastContext(s1);                       // 首条快照只建立基线
    ui.showToast('SOUTH PLAY ♠4 失败：首家有花色必须跟出', true);
    ui.syncToastContext(s1);                       // 同一条快照又推了一次（失败不产生状态变化）
    check('牌局没动时失败提示不被清掉', ui.toastLabel.string.includes('失败'));
}

// --- 3. 【本次 bug】跨局必须清空：上一把的失败提示不能出现在下一把 ---
{
    const ui = newUi();
    ui.syncToastContext(snap(2, 'PLAYING', 'SOUTH', [trick('NORTH', ['♠4'])]));
    ui.showToast('SOUTH PLAY ♠4 失败：首家有花色必须跟出', true);
    ui.syncToastContext(snap(3, 'BIDDING', 'SOUTH'));   // 第 3 局开始
    check('开新一局清空上一局的失败提示', ui.toastLabel.string === '', JSON.stringify(ui.toastLabel.string));
    check('清空后到期时间也归零', ui.toastUntil === 0);
}

// --- 4. 出牌成功（轮次/墩变化）也必须清空 ---
{
    const ui = newUi();
    ui.syncToastContext(snap(1, 'PLAYING', 'SOUTH', [trick('NORTH', ['♠4'])]));
    ui.showToast('SOUTH PLAY ♠4 失败：首家有花色必须跟出', true);
    ui.syncToastContext(snap(1, 'PLAYING', 'WEST', [trick('NORTH', ['♠4']), trick('SOUTH', ['♠9'])]));
    check('别人成功出一手（轮次+牌面变化）清空提示', ui.toastLabel.string === '');
}

// --- 5. 首条快照不得清掉"已入座"提示 ---
{
    const ui = newUi();
    ui.showToast('已入座 SOUTH');
    ui.syncToastContext(snap(1, 'DEALING', 'SOUTH'));
    check('首条快照不清"已入座"（基线建立而非变化）', ui.toastLabel.string === '已入座 SOUTH');
}

// --- 6. 普通提示 2.2s，且 showToast('') 等于手动清空 ---
{
    const ui = newUi();
    const t0 = Date.now();
    ui.showToast('已入座 SOUTH');
    const d = ui.toastUntil - t0;
    check('普通提示到期时间 2.2s', d >= 1800 && d <= 2600, `d=${d}`);
    ui.showToast('');
    check('showToast(\'\') 立即清空', ui.toastLabel.string === '' && ui.toastUntil === 0);
}

// --- 7. 致命错误必须常驻（不能被本次修复顺手改成"过一会儿就没了"） ---
// 房间线程异常 / bot 驱动异常 / 入不了座这类错误意味着牌局已经跑不动，
// 而且有快照时顶栏不会显示 lastNetError —— 提示一消失线索就全丢了。
{
    const ui = newUi();
    ui.showToast('⚠ 房间线程异常: ...', true, true);
    check('致命错误不打到期时间', ui.toastUntil === 0);
    ui.update(0.016);
    ui.syncToastContext(snap(1, 'PLAYING', 'WEST', [trick('NORTH', ['♠4'])]));
    ui.syncToastContext(snap(2, 'BIDDING', 'SOUTH'));
    check('致命错误不被牌局前进清掉', ui.toastLabel.string.includes('房间线程异常'));
    // 但恢复（重新入座）时必须能清掉，否则错误会跟着一整局
    ui.clearToast();
    check('恢复后 clearToast 能清掉常驻错误', ui.toastLabel.string === '' && ui.toastSticky === false);
}

// --- 8. 铭牌「定主者」标记：第一局不画 ---
// 第一局的声明（抢亮大王 / 无人亮时翻底牌定庄）在服务端都记为 FIRST_ROUND_JOKER。
// 它没有"定主者"这个概念可表达：庄家本身就是定主者（与旁边的"庄"瓦片重复），
// 而主花色要等摸到第一张花色牌才定下来。原先画的那个"大"瓦片只会让人误读。
{
    resetTiles();
    const ui = newBadgeUi();
    ui.renderTrumpMark('SOUTH', { kind: 'FIRST_ROUND_JOKER', suit: 'HEART', count: 0 });
    check('第一局抢亮大王：一个瓦片都不画', createdTiles.length === 0, `tiles=${createdTiles.length}`);
    check('第一局不建空的标记节点（别留空壳挂在铭牌上）', ui.seatTrumpNode.SOUTH === undefined);

    resetTiles();
    ui.renderTrumpMark('SOUTH', { kind: 'FIRST_ROUND_JOKER', suit: undefined, count: 0 });
    check('第一局主花色待摸（suit 未定）同样不画', createdTiles.length === 0);
}

// --- 9. 第二局起的标记不能跟着被误伤 ---
{
    resetTiles();
    const ui = newBadgeUi();
    ui.renderTrumpMark('SOUTH', { kind: 'LEVEL_CARDS', suit: 'DIAMOND', count: 2 });
    check('第二局亮 2 张级牌 → 画 2 个花色瓦片', createdTiles.length === 2, `tiles=${createdTiles.length}`);
    check('第二局有标记节点', !!ui.seatTrumpNode.SOUTH);

    resetTiles();
    const ui2 = newBadgeUi();
    ui2.renderTrumpMark('NORTH', { kind: 'TRIPLE_SMALL_JOKER', suit: undefined, count: 0 });
    check('三小王 → 画 3 个瓦片', createdTiles.length === 3, `tiles=${createdTiles.length}`);

    resetTiles();
    const ui3 = newBadgeUi();
    ui3.renderTrumpMark('WEST', { kind: 'FIXED_BY_BOTTOM', suit: 'CLUB', count: 0 });
    check('第二局起无人亮主翻底定主 → 画 1 个瓦片', createdTiles.length === 1);
}

// --- 10. 从"有标记"切到"第一局"必须把旧标记拆掉（不能残留） ---
{
    resetTiles();
    const ui = newBadgeUi();
    ui.renderTrumpMark('EAST', { kind: 'LEVEL_CARDS', suit: 'DIAMOND', count: 2 });
    const strip = ui.seatTrumpNode.EAST;
    let destroyed = false;
    strip.destroy = () => { destroyed = true; };
    resetTiles();
    ui.renderTrumpMark('EAST', { kind: 'FIRST_ROUND_JOKER', suit: 'HEART', count: 0 });
    check('旧标记被拆掉、且不再新建', destroyed && ui.seatTrumpNode.EAST === undefined);
    check('拆掉后一个瓦片都没多画', createdTiles.length === 0);
}

// --- 11. 底牌展示区：扣底阶段把原 6 张摆出来 ---
{
    const { ui, labels } = newBottomUi();
    const bottom = ['H4', 'S6', 'C4', 'C6', 'D4', 'D7'];
    ui.snap = { gameNumber: 1, phase: 'BURYING', dryPot: false, bottom };
    ui.renderBottom();
    // 底板 + 6 张牌 + 标签 = 8 个子节点
    check('扣底阶段：底板 + 6 张原底牌 + 标签都画出来',
        ui.bottomNode.children.length === 8, `children=${ui.bottomNode.children.length}`);
    check('扣底阶段标签提示"扣完收起"', /扣完收起/.test(labels[0] || ''), labels[0]);
}

// --- 12. 底牌区也必须走签名制：同一份底牌的后续快照不许重建 ---
{
    const { ui } = newBottomUi();
    const bottom = ['H4', 'S6', 'C4', 'C6', 'D4', 'D7'];
    ui.snap = { gameNumber: 1, phase: 'BURYING', dryPot: false, bottom };
    ui.renderBottom();
    const first = ui.bottomNode.children[0];
    ui.renderBottom();      // 服务端每条命令都推全量快照，同一份底牌会反复到达
    check('同一份底牌的后续快照不重建节点', ui.bottomNode.children[0] === first);
}

// --- 13. 【核心场景】扣完底后必须从桌面收起 ---
{
    const { ui } = newBottomUi();
    ui.snap = {
        gameNumber: 1, phase: 'BURYING', dryPot: false,
        bottom: ['H4', 'S6', 'C4', 'C6', 'D4', 'D7'],
    };
    ui.renderBottom();
    // 扣底成功 → 进 PLAYING，服务端不再下发 bottom（庄家扣回去的 6 张是机密）
    ui.snap = { gameNumber: 1, phase: 'PLAYING', dryPot: false };
    ui.renderBottom();
    check('扣完底后底牌必须从桌面收起',
        ui.bottomNode.children.length === 0, `children=${ui.bottomNode.children.length}`);
}

// --- 14. 干锅局：出牌阶段仍展出，且标签要换口径 ---
{
    const { ui, labels } = newBottomUi();
    const bottom = ['S4', 'S6', 'C4', 'C6', 'D4', 'D7'];
    ui.snap = { gameNumber: 1, phase: 'BURYING', dryPot: false, bottom };
    ui.renderBottom();
    const before = labels.length;
    // 干锅局：干锅整段跳过 BURYING，牌面可能与上一状态完全相同，只是 dryPot 变 true
    ui.snap = { gameNumber: 1, phase: 'PLAYING', dryPot: true, bottom };
    ui.renderBottom();
    check('干锅局出牌阶段底牌仍展出', ui.bottomNode.children.length === 8,
        `children=${ui.bottomNode.children.length}`);
    check('牌面没变但进入干锅，标签必须刷新（签名要带 dryPot）',
        labels.length === before + 1, `labels=${labels.length}`);
    check('干锅标签标注"干锅"', /干锅/.test(labels[labels.length - 1] || ''),
        labels[labels.length - 1]);
}

// --- 15. 干锅局打完开新局：底牌区同样要收起（换局不能残留） ---
{
    const { ui } = newBottomUi();
    ui.snap = {
        gameNumber: 1, phase: 'PLAYING', dryPot: true,
        bottom: ['S4', 'S6', 'C4', 'C6', 'D4', 'D7'],
    };
    ui.renderBottom();
    ui.snap = { gameNumber: 2, phase: 'DEALING', dryPot: false };
    ui.renderBottom();
    check('干锅局结束、下一局重新发牌后底牌收起',
        ui.bottomNode.children.length === 0, `children=${ui.bottomNode.children.length}`);
}

// --- 16. 【Tracy 反馈】扣完底后，庄家必须还能回看自己扣的 6 张 ---
// 服务端只对庄家下发 myBottom（闲家拿不到，见 BottomRevealTest）。客户端把它折叠成
// 一枚胶囊：常驻摊开会压住对家墩牌行那条带，而"我扣了哪 6 张"又不是每帧都要看的。
{
    const { ui, labels } = newBottomUi();
    const mine = ['S7', 'S8', 'C7', 'C8', 'D8', 'D10'];
    ui.snap = { gameNumber: 1, phase: 'PLAYING', dryPot: false, myBottom: mine };
    ui.renderBottom();
    check('扣完底后庄家有"我的底牌"入口（折叠 = 1 个胶囊节点）',
        ui.bottomNode.children.length === 1, `children=${ui.bottomNode.children.length}`);
    check('折叠态文案说清是什么、有多少张',
        /我的底牌 6 张/.test(labels[labels.length - 1] || ''), labels[labels.length - 1]);
    check('折叠态不摊牌（桌上没有 6 张明牌）', ui.bottomNode.children.length < 8);

    const cap = ui.bottomNode.children[0];
    cap.emit('touch-start');
    // 底板 + 6 张 + 标签 + 一层"点这里收起"的透明命中层 = 9
    check('点一下摊开：6 张明牌都在桌上（+ 底板/标签/命中层）',
        ui.bottomNode.children.length === 9, `children=${ui.bottomNode.children.length}`);
    check('摊开后提示怎么收起',
        /收起/.test(labels[labels.length - 1] || ''), labels[labels.length - 1]);

    const hit = ui.bottomNode.children[ui.bottomNode.children.length - 1];
    hit.emit('touch-start');
    check('摊开态再点一下能收回去（不是只能开不能关）',
        ui.bottomNode.children.length === 1, `children=${ui.bottomNode.children.length}`);
}

// --- 17. 非庄家：拿不到 myBottom，桌面就不该有底牌区（不泄密） ---
{
    const { ui } = newBottomUi();
    ui.snap = { gameNumber: 1, phase: 'PLAYING', dryPot: false };
    ui.renderBottom();
    check('非庄家桌面不出现任何底牌区',
        ui.bottomNode.children.length === 0, `children=${ui.bottomNode.children.length}`);
}

// --- 18. 干锅局：公开与私有同时下发时按"公开优先"，别摆两块 ---
{
    const { ui, labels } = newBottomUi();
    const six = ['S4', 'S6', 'C4', 'C6', 'D4', 'D7'];
    ui.snap = { gameNumber: 1, phase: 'PLAYING', dryPot: true, bottom: six, myBottom: six };
    ui.renderBottom();
    check('公开态优先（干锅局摆的是那块"所有人可见"的底牌）',
        ui.bottomNode.children.length === 8, `children=${ui.bottomNode.children.length}`);
    check('按公开态贴标签（干锅），不是"我的底牌"',
        /干锅/.test(labels[labels.length - 1] || ''), labels[labels.length - 1]);

    // 形态切换要复位展开态：公开 → 私有之后应当重新从折叠开始
    ui.snap = { gameNumber: 1, phase: 'PLAYING', dryPot: false, myBottom: six };
    ui.renderBottom();
    check('从公开态切回私有态时重新折叠（不继承上一形态的展开状态）',
        ui.bottomNode.children.length === 1, `children=${ui.bottomNode.children.length}`);
}

// --- 19. 【Tracy 反馈】结算面板不能挡操作区 ---
// 服务端没有 SETTLED 阶段：结算命令直接开新局，所以面板弹出的同一瞬间下一局已在发牌，
// 而亮主窗口恰好覆盖 DEALING —— 玩家要在这几秒里点图标亮主/改主/定主。
// 原实现铺了全屏黑幕 + 居中 660×420 面板，正好把亮主栏（y=-110）、手牌、按钮全压暗。
{
    const OP_TOP = -4;          // 底部操作区顶边：按钮 y=-30±26
    const TOAST_BOTTOM = 248;   // 顶部 toast 基线 258 − 半个行高
    const ui = newSettleUi();
    ui.renderSettlement();

    const panel = ui.settleNode;
    check('结算面板已创建', !!panel);
    const box = panel.getComponent(FAKE_CC.UITransform).contentSize;
    const top = panel.position.y + box.height / 2;
    const bottom = panel.position.y - box.height / 2;
    check('面板整体在底部操作区之上（否则又变成"亮主时被挡"）',
        bottom >= OP_TOP, `bottom=${bottom}, OP_TOP=${OP_TOP}`);
    check('面板不压顶部 toast / 得分条', top <= TOAST_BOTTOM, `top=${top}`);
    check('面板触摸命中区不是全屏（全屏命中区会把整屏点击都吞掉）',
        box.width < 1280 && box.height < 720, `${box.width}x${box.height}`);

    const g = panel.getComponent(FAKE_CC.Graphics);
    const fullScreen = g.ops.some(o => Math.abs(o.w) >= 1280 || Math.abs(o.h) >= 720);
    check('不再铺全屏黑幕（那正是"看不见按钮也点不到"的元凶）', !fullScreen,
        JSON.stringify(g.ops));
    check('面板本体仍然画了（深色底 + 金边）', g.fills >= 2 && g.strokes >= 1);
}

// --- 20. 点一下就能收起，且收起后不被后续快照弹回来 ---
{
    const ui = newSettleUi();
    ui.renderSettlement();
    const panel = ui.settleNode;
    check('面板注册了"点哪里收起"', (panel.handlers['touch-start'] || []).length > 0);

    panel.emit('touch-start');
    check('点一下即收起', ui.settleNode === null && ui.settleDismissed === true);
    // 服务端每条命令后都推全量快照 → renderAll 会反复调用 renderSettlement
    ui.renderSettlement();
    check('收起后不被后续快照重建（否则等于关不掉）', ui.settleNode === null);
}

// --- 21. 新一局结算必须能重新弹（别把"点掉"变成永久的） ---
{
    const ui = newSettleUi();
    ui.renderSettlement();
    ui.dismissSettle();
    check('上一局面板已点掉', ui.settleDismissed === true);

    ui.onEventToast({ op: 'SETTLE', gameNumber: 4, success: true });
    check('收到新一次 SETTLE 事件后重置"已看过"标记', ui.settleDismissed === false);
    ui.snap = {
        gameNumber: 4, phase: 'DEALING', banker: 'EAST',
        settlement: {
            attackerScore: 40, bankerScore: 200, attackerTakesBank: false,
            attackerPromoted: false, bankerPromoted: true, dugBottom: true,
        },
    };
    ui.preSettleBanker = 'EAST';
    ui.settleVisibleUntil = Date.now() + 6000;
    ui.renderSettlement();
    check('新一局的结算面板照常弹出', !!ui.settleNode);
}

// ---------- 5. renderButtons 的观察台（他人捡牌扣王窗口，手册 2.3.5） ----------
// renderButtons 只碰 btnNode 的 children，并通过 makeButton 产出按钮；
// 这里把 makeButton 换成"记账版"，测试就能看到"这一轮给了哪些按钮、点了会发什么"。
function newButtonUi() {
    const ui = newUi();
    const buttons = [];
    const sent = [];
    ui.btnSig = '';
    ui.btnNode = fakeContainer('buttons');
    // 【账本跟着节点清空】renderButtons 每轮开头都会 destroyChildren(btnNode)，
    // 所以 buttons 应当 == **最新一轮**的按钮，而不是历史累加。
    // 这条尤其重要：点一下手牌会触发 toggleSelect → renderButtons()（选中数影响按钮文案），
    // 若账本只增不清，测试就会把"上一轮的按钮"当成现役按钮来点。
    ui.btnNode.removeAllChildren = () => { ui.btnNode.children = []; buttons.length = 0; };
    ui.makeButton = (text, x, cb) => {
        const n = new FAKE_CC.Node(`btn_${text}`);
        buttons.push({ text, x, cb });
        ui.btnNode.addChild(n);
        return n;
    };
    ui.net = { online: true, sendCmd: (t, p) => sent.push({ t, p }) };
    return { ui, buttons, sent, me: ui.mySeat };
}
const btnTexts = (bs) => bs.map(b => b.text).join(' / ');
function clickBtn(bs, re) {
    const b = bs.find(x => re.test(x.text));
    if (!b) throw new Error(`按钮不存在：${re}（现有：${btnTexts(bs)}）`);
    b.cb();
    return b;
}

/**
 * 造一个能跑"**点手牌** → 点按钮"整条真实路径的实例（按钮 + 手牌都在）。
 *
 * 【为什么必须走 renderHand】`selected` 里装的是 `cardKey()` 生成的**复合身份**
 * `code#occurrence`（`BJ#0`、`H9#1`）。测试若直接 `ui.selected = ['BJ']`，塞进去的是
 * 裸牌码 —— 与真实形状不一致，于是"实现里把键当牌码用"这类错误测不出来。
 * 2026-09-18「选大王也提示只能扣王」就是这么漏过去的，第 24 段起一律走这条路径。
 */
function newPickUi(hand, snapExtra) {
    const r = newButtonUi();
    const ui = r.ui;
    ui.handNode = fakeContainer('hand');
    ui.handNodes = new Map();
    ui.handBaseY = new Map();
    ui.vw = 1280;
    ui.snap = Object.assign({
        gameNumber: 1, phase: 'BURYING', yourHand: hand,
        dryPot: false, banker: 'NORTH', bottomRevealed: false,
        pickSeat: r.me, pickMax: 2,
    }, snapExtra || {});
    ui.renderHand();
    // 不在这里渲染按钮：让"点牌 → toggleSelect → renderButtons()"这条真实链路首渲。
    // btnSig 置 null 只为保证首次不被签名守卫吞掉（null ≠ 任何签名串）。
    ui.btnSig = null;
    return r;
}

/** 点手牌里的某张牌：nth = 同名同码牌中的第几份（三副牌里同码最多 3 张） */
function clickHandCard(ui, code, nth) {
    const hand = ui.snap.yourHand;
    const idxs = [];
    for (let i = 0; i < hand.length; i++) if (hand[i] === code) idxs.push(i);
    const idx = idxs[nth || 0];
    if (idx === undefined) throw new Error(`手牌里没有第 ${(nth || 0) + 1} 张 ${code}：${hand.join(',')}`);
    const node = ui.handNodes.get(idx);
    if (!node) throw new Error(`手牌节点缺失：${code}（renderHand 没跑？）`);
    node.emit('touch-start');
    return node;
}

const SIX_BOTTOM = ['H4', 'S6', 'C4', 'C6', 'D4', 'D7'];

// --- 22. 他人捡牌扣王：按钮只在轮到我时出现，且非法输入被本地拦下 ---
{
    const { ui, buttons, sent, me } = newButtonUi();
    const other = TableUI.ALL_SEATS.find(s => s !== me);
    const base = {
        gameNumber: 1, phase: 'BURYING', dryPot: false, banker: other,
        bottom: SIX_BOTTOM, bottomRevealed: true, pickMax: 2,
    };

    // (a) 轮到我 → 扣王 / 跳过
    ui.snap = Object.assign({}, base, { pickSeat: me });
    buttons.length = 0; ui.renderButtons();
    check('扣王窗口轮到我 → 出现「扣王：选王」与「跳过」',
        /扣王：选王/.test(btnTexts(buttons)) && /跳过/.test(btnTexts(buttons)), btnTexts(buttons));
    check('还没选牌时是引导文案，不显示「扣王(0张)」',
        !/扣王\(0张\)/.test(btnTexts(buttons)), btnTexts(buttons));

    // (b) 没选牌就点 → 只提示、不发送
    clickBtn(buttons, /扣王/);
    check('没选牌点「扣王」→ 提示且不发命令',
        sent.length === 0 && /点选/.test(ui.toastLabel.string), ui.toastLabel.string);

    // (c) 选到非王 → 拒绝（规则：只能把自己的王扣进底牌）
    // 【形状对齐】selected 的元素是复合身份 `code#occurrence`，这里照真实格式写；
    // 端到端（真的去点手牌）另见第 24 段。
    ui.selected = ['H9#0'];
    buttons.length = 0; ui.renderButtons();
    clickBtn(buttons, /扣王/);
    check('选到非王 → 提示"只能扣王"且不发命令',
        sent.length === 0 && /只能扣王/.test(ui.toastLabel.string), ui.toastLabel.string);

    // (d) 超过服务端下发上限 → 拒绝
    ui.selected = ['BJ#0', 'SJ#0', 'BJ#1'];
    buttons.length = 0; ui.renderButtons();
    check('选中张数写进按钮文案（扣王(3张)）',
        /扣王\(3张\)/.test(btnTexts(buttons)), btnTexts(buttons));
    clickBtn(buttons, /扣王/);
    check('超过 pickMax 张 → 提示上限且不发命令',
        sent.length === 0 && /最多扣 2 张/.test(ui.toastLabel.string), ui.toastLabel.string);

    // (e) 合法 → 发送 PICK_JOKER + 剥掉 #n 后缀的牌码
    ui.selected = ['BJ#0', 'SJ#0'];
    buttons.length = 0; ui.renderButtons();
    clickBtn(buttons, /扣王/);
    check('合法扣王 → 发送 PICK_JOKER 且带上选中的王',
        sent.length === 1 && sent[0].t === 'PICK_JOKER'
        && JSON.stringify(sent[0].p && sent[0].p.cards) === '["BJ","SJ"]',
        JSON.stringify(sent));

    // (f) 跳过 → 不带 cards（服务端按空列表 = 过 处理）
    sent.length = 0;
    clickBtn(buttons, /跳过/);
    check('跳过 → 发送不带 cards 的 PICK_JOKER',
        sent.length === 1 && sent[0].t === 'PICK_JOKER' && sent[0].p === undefined,
        JSON.stringify(sent));

    // (g) 轮到别人 → 我不该被告知"你可以扣王"
    ui.snap = Object.assign({}, base, { pickSeat: other });
    buttons.length = 0; ui.renderButtons();
    check('轮到别人 → 不出现「扣王」「跳过」',
        !/扣王/.test(btnTexts(buttons)) && !/跳过/.test(btnTexts(buttons)), btnTexts(buttons));

    // (h) 窗口开着时庄家（我）不该再看到「扣底」——那是点了必被服务端拒的死按钮
    ui.snap = Object.assign({}, base, { pickSeat: other, banker: me });
    buttons.length = 0; ui.renderButtons();
    check('扣王窗口开着时庄家不再看到「扣底」',
        !/扣底/.test(btnTexts(buttons)), btnTexts(buttons));

    // (i) 庄家还没扣完底（无窗口）→ 照常给「扣底」
    ui.snap = { gameNumber: 1, phase: 'BURYING', dryPot: false, banker: me, bottom: SIX_BOTTOM };
    buttons.length = 0; ui.renderButtons();
    check('没有扣王窗口时，庄家照常看到「扣底：选6张」',
        /扣底：选6张/.test(btnTexts(buttons)), btnTexts(buttons));

    // (j) 按钮渲染后窗口已翻页 → 误点必须本地拦下
    ui.snap = Object.assign({}, base, { pickSeat: me });
    buttons.length = 0; ui.renderButtons();
    const stale = buttons.find(b => /扣王/.test(b.text));
    ui.snap = Object.assign({}, base, { pickSeat: other });   // 已轮到下一家
    sent.length = 0;
    stale.cb();
    check('窗口过去后误点「扣王」→ 提示且不发命令',
        sent.length === 0 && /过去了/.test(ui.toastLabel.string), ui.toastLabel.string);
}

// --- 23. 【Tracy 拍板】扣王窗口期间**不摊牌**：真有人扣了才公开 ---
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

// --- 24. 王的判定必须"整码精确比对"，且这个静态方法必须真的存在 ---
// （本轮真踩过：整个静态方法漏落盘，只有 tsc 报 Property 'isJokerCode' does not exist，
//   运行时此前完全没测过它 —— 补一条，让"方法消失"也能被测试抓到。）
{
    check('TableUI.isJokerCode 存在（定义漏落盘时这条会红）',
        typeof TableUI.isJokerCode === 'function');
    check('大王 BJ / 小王 SJ 判为王',
        TableUI.isJokerCode('BJ') === true && TableUI.isJokerCode('SJ') === true);
    check('点数为 J 的普通牌不能被误判（不能写成 includes("J")）',
        TableUI.isJokerCode('HJ') === false && TableUI.isJokerCode('S11') === false
        && TableUI.isJokerCode('J') === false);
    check('普通牌码 / 空串一律不是王',
        TableUI.isJokerCode('S5') === false && TableUI.isJokerCode('H10') === false
        && TableUI.isJokerCode('') === false);
}

// --- 25. 【2026-09-18 真 bug】扣王的本地校验要认"选中键"，不能拿键当牌码 ---
// 症状（Tracy 反馈）：扣王窗口里选了**大王**，点「扣王」永远弹"底牌里只能扣王（大王 / 小王）"，
// 命令一次都发不出去。根因：selected 装的是 cardKey() 的复合身份 `BJ#0`，
// 而 isJokerCode 是严格比对 'BJ'/'SJ' → 恒 false。
// 本段刻意**走真实点牌路径**（renderHand + 牌节点 touch-start），不再手写 ui.selected ——
// 手写裸码正是上一版测试漏掉这个 bug 的原因。
{
    const hand = ['BJ', 'BJ', 'H9', 'SJ'];   // 三副牌：大王有两张，键必须能区分副本
    const { ui, buttons, sent } = newPickUi(hand);

    // (a) 点手牌第一张大王 → selected 收到的是复合身份，不是裸码。
    // 【按钮不用手动重算】toggleSelect 结尾就会 renderButtons()（选中张数进按钮签名），
    // 点一下牌按钮文案自己就刷新了；手动"清空 + 重算"反而会把刷新结果清掉、
    // 随后又被签名守卫早退拦下 —— 那是测试自己制造的假失败。
    clickHandCard(ui, 'BJ', 0);
    check('点手牌后 selected 是复合身份 BJ#n（真实形状）',
        ui.selected.length === 1 && /^BJ#\d+$/.test(ui.selected[0]),
        JSON.stringify(ui.selected));
    check('选中后按钮自动变成「扣王(1张)」',
        /扣王\(1张\)/.test(btnTexts(buttons)), btnTexts(buttons));

    // (b) 再点第二张大王 → 两张都该选中（键不同才算真的选中两张）
    clickHandCard(ui, 'BJ', 1);
    check('同码的两张王能各自选中（键必须区分副本）',
        ui.selected.length === 2 && ui.selected[0] !== ui.selected[1],
        JSON.stringify(ui.selected));

    // (c) 【核心回归】选大王点「扣王」→ 必须真发命令，而不是误报"只能扣王"
    check('两张王 → 按钮「扣王(2张)」', /扣王\(2张\)/.test(btnTexts(buttons)), btnTexts(buttons));
    clickBtn(buttons, /扣王/);
    check('【回归】选大王点「扣王」→ 发出 PICK_JOKER（不再误报只能扣王）',
        sent.length === 1 && sent[0].t === 'PICK_JOKER'
        && JSON.stringify(sent[0].p && sent[0].p.cards) === '["BJ","BJ"]',
        `sent=${JSON.stringify(sent)} toast=${ui.toastLabel.string}`);
    check('发出去的是剥了后缀的牌码，不是带 #n 的键',
        sent.length === 1 && !/#/.test(JSON.stringify(sent[0].p.cards)), JSON.stringify(sent));
    check('发送后清空选中（键残留会在下一轮误判张数）',
        ui.selected.length === 0, JSON.stringify(ui.selected));

    // (d) 小王（SJ）同样是王
    const small = newPickUi(['SJ', 'H9']);
    clickHandCard(small.ui, 'SJ', 0);
    clickBtn(small.buttons, /扣王/);
    check('选中带后缀的小王 SJ#0 → 也认，命令发出',
        small.sent.length === 1 && JSON.stringify(small.sent[0].p.cards) === '["SJ"]',
        `sent=${JSON.stringify(small.sent)} toast=${small.ui.toastLabel.string}`);

    // (e) 选到非王（键同样带 #n）→ 仍然必须本地拦下
    const plain = newPickUi(['BJ', 'H9', 'H9', 'SJ']);
    clickHandCard(plain.ui, 'H9', 1);              // 第二张 H9 → 键 H9#1
    check('非王的选中键形如 H9#1', /^H9#1$/.test(plain.ui.selected[0]), JSON.stringify(plain.ui.selected));
    clickBtn(plain.buttons, /扣王/);
    check('选到非王（带后缀）→ 本地拦下、不发命令',
        plain.sent.length === 0 && /只能扣王/.test(plain.ui.toastLabel.string),
        `sent=${JSON.stringify(plain.sent)} toast=${plain.ui.toastLabel.string}`);

    // (f) 上限照样生效：pickMax=2 时选 3 张王必须被拦
    const over = newPickUi(['BJ', 'BJ', 'BJ', 'H9']);
    clickHandCard(over.ui, 'BJ', 0);
    clickHandCard(over.ui, 'BJ', 1);
    clickHandCard(over.ui, 'BJ', 2);
    check('三张王 → 按钮「扣王(3张)」', /扣王\(3张\)/.test(btnTexts(over.buttons)), btnTexts(over.buttons));
    clickBtn(over.buttons, /扣王/);
    check('超过 pickMax 张 → 提示上限且不发命令',
        over.sent.length === 0 && /最多扣 2 张/.test(over.ui.toastLabel.string),
        `sent=${JSON.stringify(over.sent)} toast=${over.ui.toastLabel.string}`);
}

// --- 26. 【Tracy 反馈】抓分方上台后，下一局的庄家标记必须切到新庄家 ---
// 症状：闲家（抓分方）得分 >= 120，结算弹窗也提示"抓分方上台！"，
//   但**下一局开局时"庄"瓦片仍挂在原来那个庄家的铭牌旁**。
//
// 根因（客户端）：牌桌标记的庄家口径写成了
//     IN_GAME_PHASES.has(phase) ? s.banker : this.preSettleBanker
// 而 DEALING / BIDDING（新一局的发牌与亮主）恰好落在"其余"分支 → 用了**上一局**的庄家。
// 服务端此时下发的 s.banker 已经是新庄家（SettleRoundCommand 里 setBankerSeat(newBanker)
// 之后直接 transitionTo(DEALING)），被客户端无视了。
//
// 正确口径：**牌桌标记一律以快照为准**（有 s.banker 就用它）；
//   preSettleBanker 只保留给结算面板 —— 面板要按"本局"的庄/抓分队贴标签，
//   那才是这个缓冲存在的唯一理由，不该外溢到牌桌标记上。
{
    resetTiles();
    const ui = newBadgeUi();
    // preSettleBanker 由 renderAll 在局内阶段记下"本局庄"，这里直接给（不跑 renderAll）
    ui.preSettleBanker = 'SOUTH';

    // (a) 本局出牌：SOUTH 坐庄
    ui.snap = { gameNumber: 1, phase: 'PLAYING', banker: 'SOUTH', level: 3 };
    ui.renderSeatBadges(ui.snap);
    check('本局出牌：庄瓦片在 SOUTH',
        !!ui.seatBankerNode.SOUTH && !ui.seatBankerNode.NORTH,
        `S=${!!ui.seatBankerNode.SOUTH} N=${!!ui.seatBankerNode.NORTH}`);

    // (b) 结算中：快照的 banker 仍是本局庄（切庄发生在结算命令里）
    ui.snap = { gameNumber: 1, phase: 'SETTLING', banker: 'SOUTH', level: 3 };
    ui.renderSeatBadges(ui.snap);
    check('结算中：庄瓦片仍在本局庄 SOUTH', !!ui.seatBankerNode.SOUTH);

    // (c) 【核心回归】下一局发牌：抓分方上台，服务端已把 banker 切成 NORTH
    ui.snap = { gameNumber: 2, phase: 'DEALING', banker: 'NORTH', level: 3 };
    ui.renderSeatBadges(ui.snap);
    check('【回归】下一局发牌：庄瓦片必须跟到新庄 NORTH',
        !!ui.seatBankerNode.NORTH, `N=${!!ui.seatBankerNode.NORTH}`);
    check('【回归】下一局发牌：旧庄 SOUTH 的庄瓦片必须拆掉',
        !ui.seatBankerNode.SOUTH, `S=${!!ui.seatBankerNode.SOUTH}`);

    // (d) 亮主阶段同理（BIDDING 同样不在 IN_GAME_PHASES 里）
    ui.snap = { gameNumber: 2, phase: 'BIDDING', banker: 'NORTH', level: 3 };
    ui.renderSeatBadges(ui.snap);
    check('下一局亮主：庄瓦片仍在 NORTH、不在 SOUTH',
        !!ui.seatBankerNode.NORTH && !ui.seatBankerNode.SOUTH);

    // (e) 新局的扣底/出牌阶段必须和发牌期一致，不许在阶段交界处来回横跳
    ui.snap = { gameNumber: 2, phase: 'BURYING', banker: 'NORTH', level: 3 };
    ui.renderSeatBadges(ui.snap);
    check('下一局扣底：仍在 NORTH（阶段间不许横跳）',
        !!ui.seatBankerNode.NORTH && !ui.seatBankerNode.SOUTH);
    ui.snap = { gameNumber: 2, phase: 'PLAYING', banker: 'NORTH', level: 3 };
    ui.renderSeatBadges(ui.snap);
    check('下一局出牌：仍在 NORTH', !!ui.seatBankerNode.NORTH && !ui.seatBankerNode.SOUTH);

    // (f) 快照还没给出庄家时（第一局亮主前）回退到本局缓冲，且不许空画瓦片
    const early = newBadgeUi();
    early.preSettleBanker = null;
    early.snap = { gameNumber: 1, phase: 'BIDDING', banker: null, level: 3 };
    early.renderSeatBadges(early.snap);
    check('第一局亮主前（banker=null）：四家都不画庄瓦片',
        !early.seatBankerNode.NORTH && !early.seatBankerNode.SOUTH
        && !early.seatBankerNode.EAST && !early.seatBankerNode.WEST);

    // (g) 口径必须与"结算面板"分开：面板仍按本局庄分队（不能跟着被改坏）；
    //     而牌桌瓦片与铭牌金框必须共用**同一个**口径方法，否则两处各错各的。
    const panel = newBadgeUi();
    panel.preSettleBanker = 'SOUTH';
    const tb = typeof panel.tableBanker === 'function' ? panel.tableBanker.bind(panel) : null;
    check('牌桌/铭牌共用的庄家口径存在，且 DEALING 时取快照的新庄',
        !!tb && tb({ phase: 'DEALING', banker: 'NORTH' }) === 'NORTH');
    check('快照没庄家时该口径回退到本局缓冲',
        !!tb && tb({ phase: 'DEALING', banker: null }) === 'SOUTH');
}

/**
 * 造一个能跑 renderScoreBar → buildScorePanel 的实例：面板会挂到 ui.node 上，
 * 于是可以直接遍历节点树断言"文案与牌面真的画上去了"（而不只是纯函数算对了）。
 */
function newScoreUi() {
    const ui = newUi();
    ui.node = new FAKE_CC.Node('root');
    ui.scoreBarNode = new FAKE_CC.Node('scorebar');
    ui.scoreCapsuleLabel = { string: '' };
    ui.scoreExpanded = true;
    ui.mySeat = 'SOUTH';
    ui.preSettleBanker = 'SOUTH';
    return ui;
}

/** 收齐一棵节点树上的所有 Label 文案（面板内容断言用） */
function collectLabels(root) {
    const out = [];
    const walk = (n) => {
        if (!n) return;
        const l = n.getComponent ? n.getComponent(FAKE_CC.Label) : null;
        if (l && typeof l.string === 'string' && l.string) out.push(l.string);
        (n.children || []).forEach(walk);
    };
    walk(root);
    return out;
}

// --- 27. 【Tracy 反馈】第一行改报「主牌」+ 得分面板补「本局干锅 / 扣王 / 进贡」 ---
// 分两层测：① 纯函数（roundFactSegments / tributeRows）—— "话报得对不对"最容易错的地方；
//          ② 端到端（renderScoreBar → 面板节点树）—— 确认这些文案真的画上去了。
{
    const ui = newUi();
    ui.mySeat = 'SOUTH';

    // (a) 第一行：恒为「第N局 · 主牌」，任何阶段都不再出现阶段词
    const t3 = ui.topTitle({ gameNumber: 3, phase: 'PLAYING', trump: { suit: 'S', level: 3 } });
    check('第一行标题 = 「第3局 · 主牌」', t3 === '第3局 · 主牌', t3);
    check('主牌未定时报「主牌未定」（不把两个字省掉）',
        ui.topTitle({ gameNumber: 1, phase: 'BIDDING' }) === '第1局 · 主牌未定');
    const allPhases = ['DEALING', 'BIDDING', 'BURYING', 'PLAYING', 'TRIBUTE', 'RETURN_TRIBUTE', 'SETTLING'];
    check('无论什么阶段，第一行都不再出现阶段词',
        allPhases.every(p => !/发牌中|亮主|扣底|出牌|进贡|还贡|结算中/.test(
            ui.topTitle({ gameNumber: 2, phase: p, trump: { suit: 'H', level: 5 } }))));

    // (b) 状态三问：三段文字 + 只有"发生过"的才高亮
    const f0 = ui.roundFactSegments({});
    check('都没发生时 = 「干锅 否 · 扣王 否 · 进贡 否」',
        f0.map(x => x.text).join(' · ') === '干锅 否 · 扣王 否 · 进贡 否',
        f0.map(x => x.text).join(' · '));
    check('都没发生时三段都不高亮', f0.every(x => x.hot === false));

    const f1 = ui.roundFactSegments({ dryPot: true, jokerBuried: true });
    check('干锅 + 扣王都发生时两段都高亮、进贡不高亮',
        f1[0].text === '干锅 是' && f1[0].hot === true
        && f1[1].text === '扣王 是' && f1[1].hot === true
        && f1[2].hot === false);
    check('字段缺失（老快照）不能报成"是"：undefined 不是 true',
        ui.roundFactSegments({}).every(x => x.hot === false));

    // (c) 「是否进贡」的两个来源：还欠着 / 已经交过
    check('有待进贡义务 → 进贡 是',
        ui.roundFactSegments({ pendingTributes: { WEST: { blood: 2, receiver: 'NORTH' } } })[2].hot === true);
    check('已有进贡流水 → 进贡 是',
        ui.roundFactSegments({ tributes: [{ payer: 'EAST', receiver: 'SOUTH', cards: ['BJ'] }] })[2].hot === true);

    // (d) 明细行：谁贡给谁、贡了哪几张、还了哪几张
    const rowsPending = ui.tributeRows({ pendingTributes: { WEST: { blood: 3, receiver: 'NORTH' } } });
    check('还没交贡：只有一行「待进贡 N 张」，不带牌面',
        rowsPending.length === 1 && rowsPending[0].text === 'WEST 待进贡 3 张 → NORTH'
        && rowsPending[0].cards.length === 0, JSON.stringify(rowsPending));

    const rowsPaid = ui.tributeRows({
        tributes: [{ payer: 'SOUTH', receiver: 'EAST', cards: ['BJ', 'SJ'], returned: ['HK', 'C7'] }],
    });
    check('已交已还：两行（进贡 + 还贡），各带两张牌，且标明"(我)"',
        rowsPaid.length === 2
        && rowsPaid[0].text === 'SOUTH(我) 进贡 → EAST' && rowsPaid[0].cards.join(',') === 'BJ,SJ'
        && rowsPaid[1].text === 'EAST 还贡 → SOUTH(我)' && rowsPaid[1].cards.join(',') === 'HK,C7',
        JSON.stringify(rowsPaid));

    check('还没还贡 → 不出现「还贡」行（不能凭空多一行空的）',
        ui.tributeRows({ tributes: [{ payer: 'EAST', receiver: 'SOUTH', cards: ['BJ'] }] })
            .map(r => r.text).join(' / ') === 'EAST 进贡 → SOUTH(我)');

    // (e) 端到端：真跑一遍面板，文案与牌面都得落在节点树上
    const panelUi = newScoreUi();
    const snap1 = {
        gameNumber: 2, phase: 'PLAYING', banker: 'SOUTH', trump: { suit: 'S', level: 3 },
        trickPoints: { A: 60, B: 135 }, takenPointCards: { B: ['SK', 'H10'] },
        dryPot: false, jokerBuried: true,
        tributes: [{ payer: 'EAST', receiver: 'SOUTH', cards: ['BJ', 'SJ'], returned: ['HK', 'C7'] }],
    };
    panelUi.renderScoreBar(snap1);
    const overlay = panelUi.scorePanelNode;
    check('面板已弹出', !!overlay);
    const labels = overlay ? collectLabels(overlay) : [];
    check('面板报出「扣王 是」', labels.includes('扣王 是'), labels.join(' | '));
    check('面板报出「干锅 否」与「进贡 是」',
        labels.includes('干锅 否') && labels.includes('进贡 是'));
    check('面板列出「进贡」「还贡」两行明细',
        labels.includes('EAST 进贡 → SOUTH(我)') && labels.includes('SOUTH(我) 还贡 → EAST'));
    // 顶部原本是「闲家已捡分牌 · 共 N 分」，面板扩容后下面又有一个「闲家已捡分牌」段标题
    // —— 同一句话出现两次。改成：顶部报「本局明细」，分数跟着分牌段走。
    check('顶部报「本局明细」，分数只出现一次（跟着分牌段）',
        labels.includes('本局明细')
        && labels.filter(l => l.indexOf('闲家已捡分牌') >= 0).length === 1
        && labels.includes('闲家已捡分牌 · 共 135 分'),
        labels.join(' | '));
    // 迷你牌是 CardUI 桩返回的普通对象，面板自己的节点都是 FAKE_CC.Node 实例 —— 正好用来数牌
    const minis = overlay ? overlay.children.filter(c => !(c instanceof FAKE_CC.Node)) : [];
    check('牌面画全：贡 2 张 + 还 2 张 + 闲家分牌 2 张 = 6 张',
        minis.length === 6, `实际 ${minis.length} 张`);

    // (f) 签名制：内容没变不重建；只有"本局事实"变了也必须刷新
    const before = panelUi.scorePanelNode;
    panelUi.renderScoreBar(snap1);
    check('快照没变 → 面板不重建（签名制）', panelUi.scorePanelNode === before);
    panelUi.renderScoreBar({ ...snap1, jokerBuried: false });
    check('只有「扣王」由是变否 → 面板必须重建（否则内容僵着不更新）',
        panelUi.scorePanelNode !== before);

    // (g) 没有进贡时不该出现空的"本局进贡 / 还贡"段
    const plainUi = newScoreUi();
    plainUi.renderScoreBar({
        gameNumber: 2, phase: 'PLAYING', banker: 'SOUTH',
        trickPoints: { B: 5 }, takenPointCards: { B: ['D5'] },
    });
    const plainLabels = collectLabels(plainUi.scorePanelNode);
    check('没有进贡 → 不出现「本局进贡 / 还贡」段，但状态行仍报「进贡 否」',
        !plainLabels.includes('本局进贡 / 还贡') && plainLabels.includes('进贡 否'),
        plainLabels.join(' | '));

    // (h) 面板花色图标配色：牌体是近白、面板底板是深蓝，同一支笔的"黑"在两边不能通用。
    //     ♠/♣ 若沿用 suitColor 的近黑(30,30,30)，贴到面板(26,40,62)上对比度 1.05:1 —— 等于没画。
    const iconUi = newScoreUi();
    const iconS = iconUi.panelSuitColor('S');
    const iconH = iconUi.panelSuitColor('H');
    check('面板内 ♠/♣ 花色图标必须提亮（不能沿用近黑 30,30,30）',
        iconS.r > 200 && iconS.g > 200 && iconS.b > 200,
        `实际 ${iconS.r},${iconS.g},${iconS.b}`);
    check('面板内 ♥/♦ 花色图标为亮红（语义不变，且不与深蓝底板糊在一起）',
        iconH.r > 180 && iconH.g < 120 && iconH.b < 120,
        `实际 ${iconH.r},${iconH.g},${iconH.b}`);

    // (i) 端到端再钉一次：配色**算对了还得真用上**。只测 panelSuitColor 是不够的 ——
    //     把调用点改回 suitColor(grp.suit)，那个纯函数测试照样绿。
    const iconEndUi = newScoreUi();
    suitIconCalls = [];
    iconEndUi.renderScoreBar({
        gameNumber: 2, phase: 'PLAYING', banker: 'SOUTH',
        trickPoints: { B: 15 }, takenPointCards: { B: ['SK', 'HK'] },
    });
    const spadeCall = suitIconCalls.find(c => c.suit === 'S');
    const heartCall = suitIconCalls.find(c => c.suit === 'H');
    check('面板把提亮配色真正交给了 ♠ 图标（不能"算了不用"退回近黑）',
        !!spadeCall && !!spadeCall.color && spadeCall.color.r > 200,
        JSON.stringify(spadeCall));
    check('面板把亮红配色真正交给了 ♥ 图标',
        !!heartCall && !!heartCall.color && heartCall.color.r > 180 && heartCall.color.g < 120,
        JSON.stringify(heartCall));
}

// --- 28. 【真机适配】铺满屏幕的东西必须随可见宽度走（写死 1280 只对 16:9 成立） ---
// 背景：1280×720 是 16:9，而手机横屏普遍 19.5:9~21:9（可见宽 1561~1680）；
// 反过来平板 / 折叠屏展开是 4:3，可见宽只有 960（半宽 480）。FIXED_HEIGHT 让 vw
// 随屏幕变，所以"铺满屏幕"的遮罩和"贴边"的铭牌都不能写死 —— 两个方向都钉住。
{
    /** 在节点树里按名字找节点（面板/遮罩都挂在 ui.node 上） */
    function findNode(root, name) {
        if (!root) return null;
        if (root.name === name) return root;
        for (const c of root.children || []) {
            const hit = findNode(c, name);
            if (hit) return hit;
        }
        return null;
    }
    /** 造一个"已设好可见宽度"的最小实例 */
    function layoutUi(vw) {
        const ui = newUi();
        ui.node = new FAKE_CC.Node('root');
        ui.mySeat = 'SOUTH';
        ui.vw = vw;
        return ui;
    }
    /** 节点上第一条 fillRect 的宽度 —— 遮罩尺寸就画在这条指令上 */
    function maskWidth(node) {
        const g = node && node.getComponent(FAKE_CC.Graphics);
        const op = g && g.ops.find(o => o.op === 'fillRect');
        return op ? op.w : NaN;
    }

    // (a) 侧翼槽位 sideX：手机端必须与老的 ±430 逐字节一致（不能顺手改动手机观感）
    const phone = layoutUi(1280);     // 16:9
    const wide = layoutUi(1680);      // 21:9
    const tablet = layoutUi(960);     // 平板 / 折叠屏展开 4:3
    check('侧翼槽位在 16:9 上仍是 ±430（手机行为不变）',
        phone.sideX() === 430, String(phone.sideX()));
    check('侧翼槽位在 21:9 上仍是 ±430（更宽也不把铭牌甩到屏幕边上）',
        wide.sideX() === 430, String(wide.sideX()));
    check('平板 4:3（可见宽 960）侧翼槽位必须内收：430+85=515 已越过半宽 480',
        tablet.sideX() <= 480 - 85, String(tablet.sideX()));

    // (b) 端到端：铭牌与墩牌行**真的**用了它 —— 连同呼吸光环不得越出屏幕
    for (const [label, ui] of [['手机 16:9', phone], ['平板 4:3', tablet]]) {
        const left = ui.plateLocal('WEST').x;
        const trickLeft = ui.trickLocal('WEST').x;
        check(`${label}：左家铭牌含光环不越出屏幕左缘`,
            left + 85 <= ui.vw / 2 + 0.001, `x=${left}, 半宽=${ui.vw / 2}`);
        check(`${label}：墩牌行与铭牌同源取值（不会一个内收、一个没动）`,
            trickLeft === left, `trick=${trickLeft}, plate=${left}`);
    }

    // (c) 得分面板遮罩：它既是视觉底色，**也是"点任意处收起"的命中区**
    const panelUi = layoutUi(1680);
    panelUi.scoreBarNode = new FAKE_CC.Node('scorebar');
    panelUi.scoreCapsuleLabel = { string: '' };
    panelUi.scoreExpanded = true;
    panelUi.renderScoreBar({
        gameNumber: 2, phase: 'PLAYING', banker: 'SOUTH',
        trickPoints: { B: 5 }, takenPointCards: { B: ['D5'] },
    });
    const panel = findNode(panelUi.node, 'scorepanel');
    check('得分面板遮罩铺满可见宽度（写死 1280 会在 21:9 两侧各留 200 单位不变的缝）',
        maskWidth(panel) === 1680, String(maskWidth(panel)));
    check('得分面板的命中区（UITransform）同样是可见宽度：否则两侧点下去没反应',
        !!panel && panel.getComponent(FAKE_CC.UITransform).contentSize.width === 1680,
        panel ? String(panel.getComponent(FAKE_CC.UITransform).contentSize.width) : 'panel 不存在');

    // (d) 断线重连遮罩同理（纯视觉，但两侧透出牌桌很难看）
    const reconnectUi = layoutUi(1680);
    reconnectUi.offlineSince = Date.now();
    reconnectUi.showReconnectOverlay();
    const rc = findNode(reconnectUi.node, 'reconnect-mask');
    check('断线遮罩铺满可见宽度',
        maskWidth(rc) === 1680, String(maskWidth(rc)));

    // (e) 手牌：两行的顶边与底边都要落在安全带里
    //     底边 —— iPhone 横屏的 Home 指示条压在最下沿；
    //     顶边 —— 再往上就是自己铭牌的呼吸光环（中心 -160、含光环半高 25 → 底边 -185）。
    const handUi = layoutUi(1680);
    handUi.handNode = new FAKE_CC.Node('hand');
    handUi.handNode.setPosition(0, -240, 0);    // 与 buildLayout 里的 handNode 一致
    const ranks = ['A', '2', '3', '4', '5', '6', '7', '8', '9', 'T', 'J', 'Q', 'K'];
    const hand39 = [];
    outer: for (const s of ['S', 'H', 'C']) {
        for (const r of ranks) {
            hand39.push(s + r);
            if (hand39.length === 39) break outer;
        }
    }
    handUi.snap = { phase: 'PLAYING', yourHand: hand39 };
    createdCards = [];
    handUi.renderHand();
    const CARD_H = 80;
    const handY = handUi.handNode.position.y;
    const ys = createdCards.map(c => c.position.y);
    check('39 张手牌确实分成了两行',
        createdCards.length === 39 && new Set(ys).size === 2,
        `张数=${createdCards.length}, 行数=${new Set(ys).size}`);
    const lowest = Math.min(...ys) - CARD_H / 2 + handY;
    const highest = Math.max(...ys) + CARD_H / 2 + handY;
    check('手牌最低边离屏幕底 ≥ 15 单位（不给 Home 指示条压住）',
        lowest >= -360 + 15, `最低边=${lowest}`);
    check('手牌最高边不顶到我的铭牌光环（须 ≤ -189）',
        highest <= -189, `最高边=${highest}`);
}

// ============================================================
// 第 29 段：NetClient —— 连接失败必须"看得见"（2026-09-20 真机排查）
// ------------------------------------------------------------
// 真机症状：顶栏永远停在"连接中…"，连"连的是哪台机器"都看不到，牌桌空着。
// 根因不是服务端（探针验证它正常推快照），而是**任何一条失败路径都没把消息交到 UI**：
//   · 小游戏平台的 wx.connectSocket 连不上时可能只回调 onError，
//     既不回调 onOpen 也不回调 onClose —— 而当时 onerror 是空实现；
//   · 又没有与平台无关的兜底，于是 stateHandler 永不触发 →
//     renderTop 永不执行 → 顶栏保持构造时的初始文案，静默干等。
// 这组断言锁的是"失败必须可见，且绝不能把正常连接误判成失败"。
// ============================================================

/** 造一个可控的 WebSocket + 定时器环境（模拟小游戏平台"不回调"的行为） */
function makeFakeNetEnv() {
    const saved = {
        WebSocket: globalThis.WebSocket,
        setTimeout: globalThis.setTimeout,
        clearTimeout: globalThis.clearTimeout,
        setInterval: globalThis.setInterval,
        clearInterval: globalThis.clearInterval,
    };
    const state = { sockets: [], timers: [], nextId: 1 };
    class FakeWebSocket {
        constructor(url) {
            this.url = url;
            this.readyState = FakeWebSocket.CONNECTING;
            this.sent = [];
            this.onopen = null; this.onmessage = null; this.onclose = null; this.onerror = null;
            state.sockets.push(this);
        }
        send(d) { this.sent.push(d); }
        close() { this.readyState = FakeWebSocket.CLOSED; }
    }
    FakeWebSocket.CONNECTING = 0;
    FakeWebSocket.OPEN = 1;
    FakeWebSocket.CLOSING = 2;
    FakeWebSocket.CLOSED = 3;
    globalThis.WebSocket = FakeWebSocket;
    globalThis.setTimeout = (fn, ms) => {
        const id = state.nextId++;
        state.timers.push({ id, fn, ms, cancelled: false });
        return id;
    };
    globalThis.clearTimeout = (id) => {
        const t = state.timers.find(x => x.id === id);
        if (t) t.cancelled = true;
    };
    globalThis.setInterval = () => state.nextId++;
    globalThis.clearInterval = () => { /* noop */ };
    return {
        FakeWebSocket,
        state,
        restore() { Object.assign(globalThis, saved); },
        /** 触发所有未取消的定时器（模拟"时间到了"），返回触发个数 */
        fireTimers() {
            const list = state.timers.filter(t => !t.cancelled);
            state.timers = state.timers.filter(t => t.cancelled);
            for (const t of list) t.fn();
            return list.length;
        },
    };
}

/** 转译并加载任意客户端 TS 模块（只 stub 相对依赖，被测逻辑不碰 cc） */
function loadTsModule(file) {
    const src = fs.readFileSync(file, 'utf8');
    const out = ts.transpileModule(src, {
        compilerOptions: {
            module: ts.ModuleKind.CommonJS,
            target: ts.ScriptTarget.ES2019,
            experimentalDecorators: true,
            esModuleInterop: true,
            removeComments: false,
        },
        fileName: file,
    });
    const mod = { exports: {} };
    const requireShim = (spec) => {
        if (spec.startsWith('.')) return makeStub(spec);   // 兄弟模块：被测逻辑用不到
        return Module.createRequire(file)(spec);
    };
    const fn = new Function('require', 'module', 'exports', '__filename', '__dirname', out.outputText);
    fn(requireShim, mod, mod.exports, file, path.dirname(file));
    return mod.exports;
}

{
    // ---- 29.1 平台不回调任何事件（挂起）→ 建连自检必须把失败交给 UI ----
    {
        const env = makeFakeNetEnv();
        const { NetClient } = loadTsModule(NET_CLIENT);
        const states = [];
        const errors = [];
        const net = new NetClient('ws://127.0.0.1:8080/ws');
        net.onStateChange(ok => states.push(ok));
        net.onError(r => errors.push(r));
        net.join(1001, 1, 'SOUTH');
        check('NetClient: join 会立即建立 socket', env.state.sockets.length === 1);
        check('NetClient: 连接挂起的瞬间不误报失败', states.length === 0, JSON.stringify(states));
        env.fireTimers();   // 自检到点
        check('NetClient: 平台既不 onopen 也不 onclose 时，自检超时必须判定离线并上报 UI',
            states.includes(false), JSON.stringify(states));
        check('NetClient: 超时必须给出可读原因（含「超时」）而非静默',
            errors.length > 0 && /超时/.test(errors[0]), JSON.stringify(errors));
        check('NetClient: 失败原因可被顶栏读取（lastError 非空）', !!net.lastError);
        check('NetClient: 失败后自动排重连（attempts 递增，顶栏可显示「已重试 N 次」）',
            net.attempts >= 1, `attempts=${net.attempts}`);
        env.restore();
    }

    // ---- 29.2 平台只给 onerror（微信的典型行为）→ 必须立刻上报 ----
    {
        const env = makeFakeNetEnv();
        const { NetClient } = loadTsModule(NET_CLIENT);
        const errors = [];
        const states = [];
        const net = new NetClient('ws://127.0.0.1:8080/ws');
        net.onError(r => errors.push(r));
        net.onStateChange(ok => states.push(ok));
        net.join(1001, 1, 'SOUTH');
        const ws = env.state.sockets[0];
        ws.onerror({ message: 'url not in domain list' });   // 模拟微信域名校验失败
        check('NetClient: 平台只回调 onerror 时立即上报（不必干等 8 秒）',
            errors.length === 1 && /url not in domain list/.test(errors[0]), JSON.stringify(errors));
        check('NetClient: onerror 上报同时通知 UI 离线', states.includes(false), JSON.stringify(states));
        ws.onerror({ message: 'again' });
        check('NetClient: 已判失败的连接再次报错不重复上报（避免重连风暴）',
            errors.length === 1, JSON.stringify(errors));
        env.restore();
    }

    // ---- 29.3 正常连上 → 自检必须作废，不能把好连接判成失败 ----
    {
        const env = makeFakeNetEnv();
        const { NetClient } = loadTsModule(NET_CLIENT);
        const states = [];
        const net = new NetClient('ws://127.0.0.1:8080/ws');
        net.onStateChange(ok => states.push(ok));
        net.join(1001, 1, 'SOUTH');
        const ws = env.state.sockets[0];
        ws.readyState = env.FakeWebSocket.OPEN;
        ws.onopen();
        check('NetClient: onopen 后通知 UI 在线', states.includes(true), JSON.stringify(states));
        check('NetClient: onopen 时立刻发出 join（服务端靠它才会推快照）',
            ws.sent.some(s => s.includes('"op":"join"')), JSON.stringify(ws.sent));
        check('NetClient: 连上后 attempts 归零（顶栏不再显示「已重试」）', net.attempts === 0);
        env.fireTimers();
        check('NetClient: 连上后建连自检必须作废（不能误判为超时）',
            !states.includes(false), JSON.stringify(states));
        env.restore();
    }

    // ---- 29.4 消息体不是字符串（小游戏适配层可能给 ArrayBuffer）→ 必须仍能解析 ----
    {
        const env = makeFakeNetEnv();
        const { NetClient } = loadTsModule(NET_CLIENT);
        const snaps = [];
        const net = new NetClient('ws://127.0.0.1:8080/ws');
        net.onSnapshot(s => snaps.push(s));
        net.join(1001, 1, 'SOUTH');
        const ws = env.state.sockets[0];
        ws.readyState = env.FakeWebSocket.OPEN;
        ws.onopen();
        const json = JSON.stringify({ type: 'snapshot', gameNumber: 7, phase: 'PLAYING' });
        ws.onmessage({ data: new TextEncoder().encode(json).buffer });
        check('NetClient: 消息体为 ArrayBuffer 时也能解析出快照（不能静默丢弃）',
            snaps.length === 1 && snaps[0].gameNumber === 7, JSON.stringify(snaps));
        ws.onmessage({ data: json });
        check('NetClient: 消息体为字符串时行为不变',
            snaps.length === 2 && snaps[1].gameNumber === 7, String(snaps.length));
        env.restore();
    }

    // ---- 29.5 顶栏文案：连不上时必须报出"连的哪台机器 + 失败原因 + 重试次数" ----
    // 真机上"连不上"是第一现场，但玩家/排查的人都看不到这台机器连的是谁 ——
    // 顶栏是唯一的输出窗口，它的三分支（有原因 / 离线 / 已连上等数据）必须各自正确。
    {
        /** 造一个只够 renderTop 用的实例（不建节点、不跑 onLoad） */
        function newTopUi() {
            const ui = new TableUI();
            ui.snap = null;                                   // 未收到任何快照
            ui.serverUrl = 'ws://10.192.6.212:8080/ws';
            ui.mySeat = 'SOUTH';
            ui.lastNetError = '';
            // 接管 setTopText 捕获文本：本段测的是 renderTop"决定显示什么"，
            // 而 setTopText 内部的 layoutTopBar 要摆真实节点（headless 下没有）。
            ui.topText = '';
            ui.setTopText = (t) => { ui.topText = t; };
            ui.setTopTrumpBadge = () => { /* noop */ };
            ui.renderScoreBar = () => { /* noop */ };
            return ui;
        }

        const a = newTopUi();
        a.net = { online: false, attempts: 4 };
        a.renderTop();
        check('顶栏（无快照 + 离线）：显示连不上并带上实际地址',
            /连接不上/.test(a.topText) && a.topText.includes('10.192.6.212'),
            a.topText);
        check('顶栏（无快照 + 离线）：显示已重试次数（真机一眼看出在反复重连）',
            a.topText.includes('已重试 4 次'), a.topText);

        const b = newTopUi();
        b.net = { online: false, attempts: 4 };
        b.lastNetError = '连接超时：8 秒内未建立（readyState=0）';
        b.renderTop();
        check('顶栏（有失败原因）：必须显示原因原文，不能只说「连接不上」',
            b.topText.includes('连接超时') && b.topText.includes('readyState=0'),
            b.topText);

        const c = newTopUi();
        c.net = { online: true, attempts: 0 };
        c.renderTop();
        check('顶栏（已连上、等快照）：显示「等待服务器数据」而不是「连接不上」',
            /等待服务器数据/.test(c.topText), c.topText);
    }
}

// --- 30. 【2026-09-20 真机 bug】坐庄（收贡人）必须能自己还贡，不能被超时托管代还 ---
// Tracy 反馈：坐庄那局"没看到给我上贡和让我还贡的操作，直接就开始了，
// 可明细里已经出现上贡和还贡的牌，我并没有操作"。
// 两层原因（缺一不可，只修一层按钮照样出不来）：
//   ① 还贡按钮被写在 `case 'RETURN_TRIBUTE'` 里 —— 服务端 GamePhase **没有**这个阶段，
//      进贡/还贡/抗贡同属 'TRIBUTE'，那个分支是**永远进不去的死分支**；
//   ② 就算阶段名对了，它从 `pendingTributes` 里找"收贡人是我" —— 可进贡一交上去，
//      服务端 `recordTribute` 立刻把这条义务从 `pendingTributes` 摘掉了，那里查不到。
//   于是收贡人整局没有任何还贡入口；而服务端 `humanWaiter()` 明明返回了他、在等他，
//   32 秒后超时托管 `doReturnTribute()` 代还 → 直接进扣底。
// 这组断言锁住"收贡人在 TRIBUTE 阶段看得到还贡按钮、按得出正确命令、还完立刻消失"。
{
    const me = newButtonUi().me;                      // 读一下本端座位（默认 SOUTH）
    const payer = TableUI.ALL_SEATS.find(s => s !== me);
    const HAND = ['S5', 'H7', 'C9', 'D4'];
    const CARDS = ['S5', 'H7'];                       // 收到的两张血（= 应还两张）
    const received = { payer, receiver: me, cards: CARDS };
    const tributeSnap = (extra) => Object.assign({
        gameNumber: 2, phase: 'TRIBUTE', dryPot: false, banker: me, yourHand: HAND,
        pendingTributes: {}, tributes: [received],
    }, extra || {});

    // (a) 【核心】别人贡给我、我还没还 —— 阶段名就是 'TRIBUTE'，不是 RETURN_TRIBUTE
    {
        const r = newPickUi(HAND.slice(), tributeSnap());
        r.buttons.length = 0; r.ui.btnSig = null; r.ui.renderButtons();
        check('坐庄收到进贡（phase 仍是 TRIBUTE）→ 收贡人必须看到「还贡」按钮',
            /还贡/.test(btnTexts(r.buttons)), btnTexts(r.buttons));
        check('还贡按钮带上应还张数（收 2 张 → 还贡(2张)）',
            /还贡\(2张\)/.test(btnTexts(r.buttons)), btnTexts(r.buttons));
        check('收贡人视角不该同时出现「进贡」按钮（他不是进贡人）',
            !/进贡/.test(btnTexts(r.buttons)), btnTexts(r.buttons));
    }

    // (b) 没选牌就点 → 只提示、不发命令（与扣王/进贡同一个防呆口径）
    {
        const r = newPickUi(HAND.slice(), tributeSnap());
        r.buttons.length = 0; r.ui.btnSig = null; r.ui.renderButtons();
        clickBtn(r.buttons, /还贡/);
        check('没选牌点「还贡」→ 提示且不发命令',
            r.sent.length === 0 && /先选要还的牌/.test(r.ui.toastLabel.string),
            `${JSON.stringify(r.sent)} / ${r.ui.toastLabel.string}`);
    }

    // (c) 选错张数 → 本地就拦下（服务端会以"还贡张数必须等于进贡张数"拒绝，
    //     本地先拦是为了不让玩家吃一片红字，还能告诉他到底要还几张）
    {
        const r = newPickUi(HAND.slice(), tributeSnap());
        clickHandCard(r.ui, 'S5', 0);
        r.buttons.length = 0; r.ui.btnSig = null; r.ui.renderButtons();
        check('选 1 张（应还 2 张）→ 按钮文案仍报「还贡(2张)」',
            /还贡\(2张\)/.test(btnTexts(r.buttons)), btnTexts(r.buttons));
        clickBtn(r.buttons, /还贡/);
        check('张数不足点「还贡」→ 本地拦下并说明要还几张',
            r.sent.length === 0 && /要还 2 张/.test(r.ui.toastLabel.string),
            `${JSON.stringify(r.sent)} / ${r.ui.toastLabel.string}`);
    }

    // (d) 选够 2 张 → 发出 RETURN_TRIBUTE，收件人 = 进贡人（不是自己、也不是庄家兜底）
    {
        const r = newPickUi(HAND.slice(), tributeSnap());
        clickHandCard(r.ui, 'S5', 0);
        clickHandCard(r.ui, 'H7', 0);
        r.buttons.length = 0; r.ui.btnSig = null; r.ui.renderButtons();
        clickBtn(r.buttons, /还贡/);
        const cmd = r.sent[r.sent.length - 1];
        check('选够张数点「还贡」→ 发出 RETURN_TRIBUTE',
            !!cmd && cmd.t === 'RETURN_TRIBUTE', JSON.stringify(r.sent));
        check('RETURN_TRIBUTE 的 payee 必须是进贡人本人',
            !!cmd && cmd.p.payee === payer, JSON.stringify(cmd && cmd.p));
        check('RETURN_TRIBUTE 带的是选中的那两张牌',
            !!cmd && cmd.p.cards.join(',') === 'S5,H7', JSON.stringify(cmd && cmd.p));
        check('提交后清空选中（不能把牌留在选中态）', r.ui.selected.length === 0);
    }

    // (e) 已经还过了（returned 非空）→ 不能再出现还贡按钮（否则点了必被服务端拒）
    {
        const r = newPickUi(HAND.slice(), tributeSnap({
            tributes: [{ payer, receiver: me, cards: CARDS, returned: ['D4', 'C9'] }],
        }));
        r.buttons.length = 0; r.ui.btnSig = null; r.ui.renderButtons();
        check('已还过贡 → 不再出现「还贡」按钮',
            !/还贡/.test(btnTexts(r.buttons)), btnTexts(r.buttons));
    }

    // (f) 收贡人是别人（我不是权益人）→ 与我没关系，不该给我按钮
    {
        const other = TableUI.ALL_SEATS.find(s => s !== me && s !== payer);
        const r = newPickUi(HAND.slice(), tributeSnap({
            tributes: [{ payer: other, receiver: payer, cards: CARDS }],
        }));
        r.buttons.length = 0; r.ui.btnSig = null; r.ui.renderButtons();
        check('我不是收贡人 → 不出现「还贡」按钮',
            !/还贡/.test(btnTexts(r.buttons)), btnTexts(r.buttons));
    }

    // (g) 我是进贡人（收贡人的上家）→ 出现「进贡(N张)」，且不能同时给我还贡按钮
    {
        const r = newPickUi(HAND.slice(), tributeSnap({
            banker: payer, pendingTributes: { [me]: { blood: 3, receiver: payer } }, tributes: [],
        }));
        r.buttons.length = 0; r.ui.btnSig = null; r.ui.renderButtons();
        check('轮到我进贡 → 出现「进贡(3张)」（带张数，省得猜要选几张）',
            /进贡\(3张\)/.test(btnTexts(r.buttons)), btnTexts(r.buttons));
        check('进贡人视角不该出现「还贡」按钮',
            !/还贡/.test(btnTexts(r.buttons)), btnTexts(r.buttons));

        clickHandCard(r.ui, 'S5', 0);
        r.buttons.length = 0; r.ui.btnSig = null; r.ui.renderButtons();
        clickBtn(r.buttons, /进贡/);
        check('张数不足点「进贡」→ 本地拦下并说明要贡几张',
            r.sent.length === 0 && /要贡 3 张/.test(r.ui.toastLabel.string),
            `${JSON.stringify(r.sent)} / ${r.ui.toastLabel.string}`);
    }

    // (h) 阶段已经翻页 → 误点必须本地拦下（别让玩家点了才发现牌局已过）
    {
        const r = newPickUi(HAND.slice(), tributeSnap());
        clickHandCard(r.ui, 'S5', 0);
        clickHandCard(r.ui, 'H7', 0);
        r.buttons.length = 0; r.ui.btnSig = null; r.ui.renderButtons();
        r.ui.snap = Object.assign({}, r.ui.snap, { phase: 'BURYING' });   // 期间被托管代还了
        clickBtn(r.buttons, /还贡/);
        check('按钮渲染后阶段已翻页 → 本地拦下，不发无效命令',
            r.sent.length === 0 && /还贡阶段已经结束/.test(r.ui.toastLabel.string),
            `${JSON.stringify(r.sent)} / ${r.ui.toastLabel.string}`);
    }

    // (i) 【签名制】未还 → 已还 必须让按钮签名变化，否则按钮会赖在桌上不走
    {
        const r = newPickUi(HAND.slice(), tributeSnap());
        const sigBefore = r.ui.buttonSignature(true, r.ui.snap);
        r.ui.snap = Object.assign({}, r.ui.snap, {
            tributes: [{ payer, receiver: me, cards: CARDS, returned: ['D4', 'C9'] }],
        });
        const sigAfter = r.ui.buttonSignature(true, r.ui.snap);
        check('签名把「还贡对象 + 张数」算进去了（未还 → 已还 必须换签名）',
            sigBefore !== sigAfter, `${sigBefore} vs ${sigAfter}`);
    }

    // (j) 一局两笔血（分差血 + 扣王血，收贡人还不是同一家）→ 第一笔还完，
    //     按钮必须立刻切到第二笔：对象与张数都得进签名，否则按钮不会重建
    {
        const other = TableUI.ALL_SEATS.find(s => s !== me && s !== payer);
        const onlyFirst = newPickUi(HAND.slice(), tributeSnap({
            tributes: [{ payer, receiver: me, cards: ['S5'], returned: ['D4'] }],  // 已还 → 无事可做
        }));
        const two = newPickUi(HAND.slice(), tributeSnap({
            tributes: [
                { payer, receiver: me, cards: ['S5'], returned: ['D4'] },          // 第一笔：已还
                { payer: other, receiver: me, cards: ['H7', 'C9'] },               // 第二笔：待还
            ],
        }));
        two.buttons.length = 0; two.ui.btnSig = null; two.ui.renderButtons();
        check('第一笔已还、第二笔待还 → 按钮切到第二笔（还贡(2张)）',
            /还贡\(2张\)/.test(btnTexts(two.buttons)), btnTexts(two.buttons));

        const sigIdle = onlyFirst.ui.buttonSignature(true, onlyFirst.ui.snap);
        const sigTwo = two.ui.buttonSignature(true, two.ui.snap);
        check('换了一笔血（对象 + 张数都变）→ 签名必须不同，否则按钮不会重建',
            sigIdle !== sigTwo, `${sigIdle} vs ${sigTwo}`);
    }
}

console.log('=================================================');
if (failed === 0) {
    console.log('全部通过');
    process.exit(0);
}
console.log(`${failed} 项失败`);
process.exit(1);
