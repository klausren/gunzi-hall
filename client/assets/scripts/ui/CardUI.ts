import { Color, Graphics, Label, Node, UITransform } from 'cc';

/**
 * 扑克牌视觉节点工厂（T-601）。
 *
 * 程序化绘制（无美术资源）：Graphics 圆角矩形 + Label 文字。
 * 牌编码与服务端 CardCodec 对齐：S14/H5/D10/C13/BJ/SJ。
 *
 * 选中态：上移由 TableUI 控制（布局职责），这里只负责边框高亮。
 */

/**
 * 花色符号（仅 cardFace 的调试文本用）。
 * 注意：颜色**不在这里**，统一由下面的 suitColor() 决定——
 * 原先的 red 布尔只够分"红/黑"两类，无法区分同属黑色的 ♠ 与 ♣。
 */
const SUIT_CHAR: Record<string, { ch: string }> = {
    S: { ch: '♠' },
    H: { ch: '♥' },
    D: { ch: '♦' },
    C: { ch: '♣' },
};

const RANK_TEXT: Record<number, string> = { 11: 'J', 12: 'Q', 13: 'K', 14: 'A' };

/**
 * 花色配色。
 *
 * 【历史】2026-09-10 任老师反馈"草花黑桃图标太相近"，当时的处置是给 ♠ 换成深蓝，
 * 用**色相**拉开距离（两个纯黑实心团块在 22px 下确实难分）。
 *
 * 【2026-09-18 改回纯黑】Tracy 要求 ♠ / ♣ 统一用黑色（更贴近实体牌）。于是区分手段
 * 从"颜色"换回"形状"——见 addSuitGraphic 重画的两套轮廓：
 *   ♠ 尖顶 + 细腰 + 外撇底座（瘦长、有尖角）
 *   ♣ 三个正圆 + 细柄（宽扁、全是圆弧）
 * 小尺寸下"顶部有没有尖角"是最稳的判据，比色相更不依赖显示器与色觉。
 * 若要临时回到双色，把 S 单独 return 一个蓝色即可，形状强化会独立生效。
 */
export function suitColor(suitKey: string): Color {
    if (suitKey === 'H' || suitKey === 'D') return new Color(200, 30, 30, 255);
    return new Color(30, 30, 30, 255);        // ♠ 与 ♣ 同为黑：靠形状区分（尖 vs 圆）
}

export const CARD_W = 56;
export const CARD_H = 80;

/** 牌面调试文本与颜色（♠♣ 黑 / ♥♦ 红 / 王金色） */
export function cardFace(code: string): { text: string; color: Color } {
    if (code === 'BJ') return { text: '大王', color: new Color(200, 150, 20, 255) };
    if (code === 'SJ') return { text: '小王', color: new Color(160, 120, 20, 255) };
    const suit = SUIT_CHAR[code[0]];
    const rank = parseInt(code.slice(1), 10);
    const rankText = RANK_TEXT[rank] ?? String(rank);
    if (!suit) return { text: code, color: new Color(40, 40, 40, 255) };
    return { text: `${suit.ch}${rankText}`, color: suitColor(code[0]) };
}

/**
 * 花色矢量绘制（子节点 Graphics）。
 *
 * 【为什么不用 Unicode 字符 ♠♥♦♣】
 * 这些符号走系统字渲染，笔画极细且 isBold 对其无效，在真机高分辨率屏上
 * 位图放大后发虚、糊成一团（任老师 2026-09-09 反馈"花色不清晰"）。
 * 改用 Graphics 画矢量路径：任意缩放都锐利，且形状/粗细完全可控。
 *
 * 【轮廓设计 · 2026-09-18 按实体牌重画】♠ 与 ♣ 现在同为黑色，区分全靠形状。
 * 坐标以中心为原点，A = 宽、B = 高（调用方传正方形边长，故 A = B）：
 *   ♥ 两个圆瓣 + 深 V 谷 + 尖底（对照实体牌 ♥ 的比例）
 *   ♠ = ♥ 上下镜像（顶部收成尖）+ 腰部收窄到 0.86 + 底部外撇细柄 —— 有尖、瘦长
 *   ♦ 菱形，宽:高 = 0.8，比正方形瘦长
 *   ♣ 三个正圆 + 细柄 —— 无尖、宽扁
 * 判据落在一个字上：**顶部有没有尖角**。颜色丢掉了也分得开。
 *
 * 坐标以 (cx, cy) 为中心，size 为外接方框边长。
 * 命中区注意：子节点 UITransform 同样参与命中测试（见 MEMORY 铁律），
 * 因此这里把尺寸严格限制为 size×size，不做全宽 56。
 */
function addSuitGraphic(parent: Node, suitKey: string,
                        cx: number, cy: number, size: number, color: Color): void {
    const n = new Node('suit');
    n.layer = 1 << 25;
    n.addComponent(UITransform).setContentSize(size, size);
    const g = n.addComponent(Graphics);
    g.fillColor = color;
    g.strokeColor = color;
    g.lineWidth = 0;

    const A = size, B = size;

    switch (suitKey) {
        case 'H': { // ♥ 红桃：尖底 + 两侧圆瓣 + 中间深 V
            g.moveTo(0, -0.50 * B);
            g.bezierCurveTo(-0.33 * A, -0.17 * B, -0.50 * A, -0.01 * B, -0.50 * A, 0.20 * B);
            g.bezierCurveTo(-0.50 * A, 0.37 * B, -0.38 * A, 0.50 * B, -0.22 * A, 0.50 * B);
            g.bezierCurveTo(-0.14 * A, 0.50 * B, -0.05 * A, 0.46 * B, 0, 0.39 * B);
            g.bezierCurveTo(0.05 * A, 0.46 * B, 0.14 * A, 0.50 * B, 0.22 * A, 0.50 * B);
            g.bezierCurveTo(0.38 * A, 0.50 * B, 0.50 * A, 0.37 * B, 0.50 * A, 0.20 * B);
            g.bezierCurveTo(0.50 * A, -0.01 * B, 0.33 * A, -0.17 * B, 0, -0.50 * B);
            g.close();
            g.fill();
            break;
        }
        case 'S': { // ♠ 黑桃：♥ 上下镜像 + 底部外撇细柄（顶部必须是尖的）
            g.moveTo(0, 0.50 * B);
            g.bezierCurveTo(-0.28 * A, 0.24 * B, -0.43 * A, 0.10 * B, -0.43 * A, -0.06 * B);
            g.bezierCurveTo(-0.43 * A, -0.19 * B, -0.33 * A, -0.30 * B, -0.19 * A, -0.30 * B);
            g.bezierCurveTo(-0.12 * A, -0.30 * B, -0.05 * A, -0.26 * B, 0, -0.21 * B);
            g.bezierCurveTo(0.05 * A, -0.26 * B, 0.12 * A, -0.30 * B, 0.19 * A, -0.30 * B);
            g.bezierCurveTo(0.33 * A, -0.30 * B, 0.43 * A, -0.19 * B, 0.43 * A, -0.06 * B);
            g.bezierCurveTo(0.43 * A, 0.10 * B, 0.28 * A, 0.24 * B, 0, 0.50 * B);
            g.close();
            g.fill();
            // 柄：顶端要伸进身体（-0.17B 高于 V 谷的 -0.21B），否则中间会露一条缝；
            // 两侧用贝塞尔外撇，比直梯形更接近实体牌的喇叭口
            g.moveTo(-0.065 * A, -0.170 * B);
            g.lineTo(0.065 * A, -0.170 * B);
            g.bezierCurveTo(0.115 * A, -0.290 * B, 0.150 * A, -0.400 * B, 0.215 * A, -0.500 * B);
            g.lineTo(-0.215 * A, -0.500 * B);
            g.bezierCurveTo(-0.150 * A, -0.400 * B, -0.115 * A, -0.290 * B, -0.065 * A, -0.170 * B);
            g.close();
            g.fill();
            break;
        }
        case 'D': { // ♦ 方块：菱形（收窄到 0.8，比正方形瘦长）
            g.moveTo(0, 0.50 * B);
            g.lineTo(0.40 * A, 0);
            g.lineTo(0, -0.50 * B);
            g.lineTo(-0.40 * A, 0);
            g.close();
            g.fill();
            break;
        }
        case 'C': { // ♣ 梅花：三个正圆 + 细柄（顶部是圆的，与 ♠ 的尖形成对照）
            // 半径 0.19、圆心间距 0.59/0.36 → 圆心斜距 0.70 明显大于 2r=0.38，
            // 三个圆各自成瓣，凹陷看得见（原参数 0.23/0.50 会叠成一坨）。
            const r = 0.190 * A;
            g.circle(0, 0.290 * B, r);
            g.fill();
            g.circle(-0.295 * A, -0.070 * B, r);
            g.fill();
            g.circle(0.295 * A, -0.070 * B, r);
            g.fill();
            // 柄：顶端伸进上瓣，底端外撇
            g.moveTo(-0.060 * A, 0.150 * B);
            g.lineTo(0.060 * A, 0.150 * B);
            g.bezierCurveTo(0.100 * A, -0.050 * B, 0.130 * A, -0.300 * B, 0.175 * A, -0.500 * B);
            g.lineTo(-0.175 * A, -0.500 * B);
            g.bezierCurveTo(-0.130 * A, -0.300 * B, -0.100 * A, -0.050 * B, -0.060 * A, 0.150 * B);
            g.close();
            g.fill();
            break;
        }
        default:
            break;
    }
    n.setPosition(cx, cy, 0);
    parent.addChild(n);
}

/**
 * 花色矢量绘制的对外入口。
 * 顶栏"主牌+级数"图标、定主标记、庄家标记都要画花色，统一走这里，
 * 保证与牌面 / 亮主栏的花色形状、配色完全一致（♠♣ 黑 / ♥♦ 红）。
 *
 * ⚠️ 这几个调用点只有 14~19px，正是"♠ 有尖 / ♣ 全圆"最需要扛住尺寸的地方。
 * 改完 addSuitGraphic 的轮廓参数，务必顺手看一眼**小尺寸**还分不分得开。
 */
export function addSuitIcon(parent: Node, suitKey: string,
                            cx: number, cy: number, size: number, color: Color): void {
    addSuitGraphic(parent, suitKey, cx, cy, size, color);
}

/**
 * 浅色圆角"瓦片"底（项目无图片资源，图标一律代码画）。
 *
 * <p>用途：庄家"庄"标记 / 级数标记 / 定主者花色标记 —— 都是同一套小方块，
 * 只是内容不同，所以把"底"抽出来复用，避免每处各画一遍圆角矩形。
 */
export function createTileNode(w: number, h: number, bg: Color, border: Color,
                               radius = 5): Node {
    const n = new Node('tile');
    n.layer = 1 << 25;
    n.addComponent(UITransform).setContentSize(w, h);
    const g = n.addComponent(Graphics);
    g.roundRect(-w / 2, -h / 2, w, h, radius);
    g.fillColor = bg;
    g.fill();
    g.lineWidth = 1;
    g.strokeColor = border;
    g.stroke();
    return n;
}

/** 瓦片内的文字（一个节点只能挂一个 UIRenderer，Label 必须拆到子节点） */
export function addTileText(parent: Node, text: string, x: number, y: number,
                            fontSize: number, color: Color, boxW: number): void {
    const n = new Node('t');
    n.layer = 1 << 25;
    n.addComponent(UITransform).setContentSize(boxW, fontSize + 6);
    const l = n.addComponent(Label);
    l.string = text;
    l.fontSize = fontSize;
    l.lineHeight = fontSize + 3;
    l.color = color;
    l.isBold = true;
    l.useSystemFont = true;     // 系统字体：Bitmap font 中文字形不可靠（如"庄"）
    l.horizontalAlign = Label.HorizontalAlign.CENTER;
    l.verticalAlign = Label.VerticalAlign.CENTER;
    n.setPosition(x, y, 0);
    parent.addChild(n);
}

/**
 * 花色选择按钮用的中文描述。
 *
 * 【为什么用汉字而不是 ♠♥♦♣ 符号】任老师 2026-09-10 反馈"草花黑桃图标太相近"。
 * 按钮宽度 140、间距 160，放得下 3 个汉字；汉字表意唯一，彻底消除符号混淆。
 * （suit 字段是发给服务端的协议值，保持英文枚举不变。）
 */
export const SUIT_OPTIONS: { suit: string; label: string }[] = [
    { suit: 'SPADE', label: '黑桃' },
    { suit: 'HEART', label: '红桃' },
    { suit: 'DIAMOND', label: '方块' },
    { suit: 'CLUB', label: '梅花' },
];

/**
 * 创建一张牌的可视节点。
 * 额外挂一个 `userData.code`（运行时属性，Node 未声明此字段，故用断言写入）：
 * 目前没有代码依赖它，留着是为了在浏览器控制台翻节点时能直接认出是哪张牌。
 */
export function createCardNode(code: string): Node {
    const node = new Node(`card_${code}`);
    node.layer = 1 << 25; // UI_2D
    const ut = node.addComponent(UITransform);
    ut.setContentSize(CARD_W, CARD_H);

    // 一个节点只能挂一个 UIRenderer：Graphics 留在本节点，Label 必须拆到子节点。
    // 点数与花色各用一个子节点：混排串 "♠3" 在系统字下会折行/截断（只显示花色）。
    drawCardBg(node, false);

    const isJoker = code === 'BJ' || code === 'SJ';
    if (isJoker) {
        // 王：中央大字 + 上下各一个小菱形，和普通牌一眼区分（金色系，与红黑两色不冲突）
        const gold = new Color(code === 'BJ' ? 205 : 140, code === 'BJ' ? 150 : 100, 22, 255);
        addSuitGraphic(node, 'D', 0, 25, 11, gold);
        addFaceLabel(node, code === 'BJ' ? '大王' : '小王', 0, -1, 22, gold);
        addSuitGraphic(node, 'D', 0, -28, 11, gold);
    } else {
        const suit = SUIT_CHAR[code[0]];
        const rank = parseInt(code.slice(1), 10);
        const rankText = RANK_TEXT[rank] ?? String(rank);
        const color = suitColor(code[0]);
        // 【实体牌式布局】左上角"索引"（点数在上、花色在下）+ 中央大花色。
        // 手牌间距 44 < 牌宽 56，每张只露出左侧 44px —— 索引必须贴左边才不会被
        // 右边那张压住，这正是真实扑克牌把索引放在左上角的原因。
        // 点数：Label 加粗（数字/字母走系统字够清晰，isBold 对它们有效）
        addFaceLabel(node, rankText, -15, 25, 19, color, 26);
        // 索引里的花色：矢量绘制（不再用 Unicode ♠♥♦♣，真机上发虚）
        if (suit) addSuitGraphic(node, code[0], -15, 9, 13, color);
        // 中央大花色：中心放在 x=+2 而非 +4，让 26px 的外框整块落在"露出来的 44px"内
        if (suit) addSuitGraphic(node, code[0], 2, -11, 26, color);
    }

    // Node 的 .d.ts 没有 userData 字段（引擎运行时可以随便挂），
    // 直接赋值会让 tsc 报 TS2339，一直污染类型检查结果 → 显式断言写入。
    (node as unknown as { userData: { code: string } }).userData = { code };
    return node;
}

/**
 * 迷你牌（桌面出牌区用）：32×46，数字 16px、花色 12px。
 * 同一节点只挂 Graphics 画背，文字拆到子节点——复用主牌同样的单 UIRenderer 规则。
 */
export function createMiniCardNode(code: string): Node {
    const W = 32, H = 46;
    const node = new Node(`mini_${code}`);
    node.layer = 1 << 25;
    const ut = node.addComponent(UITransform);
    ut.setContentSize(W, H);

    const g = node.addComponent(Graphics);
    g.clear();
    // 投影（同主牌：右下偏移暗色底）
    g.roundRect(-W / 2 + 2, -H / 2 - 3, W, H, 5);
    g.fillColor = new Color(0, 0, 0, 50);
    g.fill();
    // 牌体：与主牌同色（改过主牌的牌体色，这里要跟着走，否则出牌区偏黄）
    g.roundRect(-W / 2, -H / 2, W, H, 5);
    g.fillColor = new Color(253, 253, 252, 255);
    g.fill();
    g.lineWidth = 1;
    g.strokeColor = new Color(186, 184, 176, 255);
    g.stroke();

    const isJoker = code === 'BJ' || code === 'SJ';
    if (isJoker) {
        addMiniText(node, code === 'BJ' ? '大' : '小', 0, 0, 16,
            new Color(code === 'BJ' ? 200 : 160, 150, 20, 255));
    } else {
        const suit = SUIT_CHAR[code[0]];
        const rank = parseInt(code.slice(1), 10);
        const rankText = RANK_TEXT[rank] ?? String(rank);
        const color = suitColor(code[0]);
        addMiniText(node, rankText, 0, 8, 20, color);
        // 花色同样矢量绘制（32×46 下 Unicode 符号几乎糊成一坨）。
        // 尺寸由 14 提到 16：♠/♣ 同为黑色后，三圆与尖顶的差别需要这点像素才看得出来。
        if (suit) addSuitGraphic(node, code[0], 0, -9, 16, color);
    }
    return node;
}

function addMiniText(parent: Node, text: string, x: number, y: number,
                     fontSize: number, color: Color): void {
    const n = new Node('t');
    n.layer = 1 << 25;
    n.addComponent(UITransform).setContentSize(32, fontSize + 4);
    const label = n.addComponent(Label);
    label.string = text;
    label.fontSize = fontSize;
    label.lineHeight = fontSize + 2;
    label.color = color;
    label.isBold = true;
    label.useSystemFont = true;
    label.horizontalAlign = Label.HorizontalAlign.CENTER;
    label.verticalAlign = Label.VerticalAlign.CENTER;
    n.setPosition(x, y, 0);
    parent.addChild(n);
}

/**
 * 在牌节点上加一个文字子节点。
 * boxW 用来**收窄命中区**：左上角的索引只占一小块，若沿用全宽 56，
 * 这个 Label 的 UITransform 会横跨整张牌（子节点同样参与命中测试）。
 */
function addFaceLabel(parent: Node, text: string, x: number, y: number,
                       fontSize: number, color: Color, boxW = CARD_W): void {
    const n = new Node('face');
    n.layer = 1 << 25;
    const ut = n.addComponent(UITransform);
    ut.setContentSize(boxW, fontSize + 6);
    const label = n.addComponent(Label);
    label.string = text;
    label.fontSize = fontSize;
    label.lineHeight = fontSize + 2;
    label.color = color;
    label.isBold = true;
    label.useSystemFont = true;     // 大王/小王中文 + 花色符号都得走系统字
    label.horizontalAlign = Label.HorizontalAlign.CENTER;
    label.verticalAlign = Label.VerticalAlign.CENTER;
    n.setPosition(x, y, 0);
    parent.addChild(n);
}

/** 重绘牌背（选中 = 金色粗边框 + 投影加深，强化"抬起"感） */
export function drawCardBg(node: Node, selected: boolean): void {
    let g = node.getComponent(Graphics);
    if (!g) g = node.addComponent(Graphics);
    g.clear();
    const R = 7;                    // 圆角略放大，牌面更柔和
    // 投影：右下偏移的暗色圆角矩形（重叠手牌会产生自然的堆叠层次）
    g.roundRect(-CARD_W / 2 + 3, -CARD_H / 2 - 4, CARD_W, CARD_H, R);
    g.fillColor = new Color(0, 0, 0, selected ? 100 : 52);
    g.fill();
    // 牌体：接近纯白。原来的 (250,250,245) 偏黄，衬红/黑花色会显脏
    g.roundRect(-CARD_W / 2, -CARD_H / 2, CARD_W, CARD_H, R);
    g.fillColor = new Color(253, 253, 252, 255);
    g.fill();
    if (selected) {
        g.lineWidth = 3;
        g.strokeColor = new Color(230, 160, 20, 255);
    } else {
        g.lineWidth = 1;
        g.strokeColor = new Color(186, 184, 176, 255);
    }
    g.stroke();
}
