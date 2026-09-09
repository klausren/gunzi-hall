import { Color, Graphics, Label, Node, UITransform } from 'cc';

/**
 * 扑克牌视觉节点工厂（T-601）。
 *
 * 程序化绘制（无美术资源）：Graphics 圆角矩形 + Label 文字。
 * 牌编码与服务端 CardCodec 对齐：S14/H5/D10/C13/BJ/SJ。
 *
 * 选中态：上移由 TableUI 控制（布局职责），这里只负责边框高亮。
 */

const SUIT_CHAR: Record<string, { ch: string; red: boolean }> = {
    S: { ch: '♠', red: false },
    H: { ch: '♥', red: true },
    D: { ch: '♦', red: true },
    C: { ch: '♣', red: false },
};

const RANK_TEXT: Record<number, string> = { 11: 'J', 12: 'Q', 13: 'K', 14: 'A' };

export const CARD_W = 56;
export const CARD_H = 80;

/** 牌面文字与颜色（♠♣ 黑 / ♥♦ 红 / 王金色） */
export function cardFace(code: string): { text: string; color: Color } {
    if (code === 'BJ') return { text: '大王', color: new Color(200, 150, 20, 255) };
    if (code === 'SJ') return { text: '小王', color: new Color(160, 120, 20, 255) };
    const suit = SUIT_CHAR[code[0]];
    const rank = parseInt(code.slice(1), 10);
    const rankText = RANK_TEXT[rank] ?? String(rank);
    if (!suit) return { text: code, color: new Color(40, 40, 40, 255) };
    return {
        text: `${suit.ch}${rankText}`,
        color: suit.red ? new Color(200, 30, 30, 255) : new Color(30, 30, 30, 255),
    };
}

/**
 * 花色矢量绘制（子节点 Graphics）。
 *
 * 【为什么不用 Unicode 字符 ♠♥♦♣】
 * 这些符号走系统字渲染，笔画极细且 isBold 对其无效，在真机高分辨率屏上
 * 位图放大后发虚、糊成一团（任老师 2026-09-09 反馈"花色不清晰"）。
 * 改用 Graphics 画矢量路径：任意缩放都锐利，且形状/粗细完全可控。
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

    const w = size, h = size;
    const hw = w / 2, hh = h / 2;

    switch (suitKey) {
        case 'H': { // ♥ 红桃：底部尖点 + 上方两个圆瓣
            g.moveTo(0, -hh);
            g.bezierCurveTo(-hw, -h * 0.15, -hw, hh, 0, h * 0.28);
            g.bezierCurveTo(hw, hh, hw, -h * 0.15, 0, -hh);
            g.close();
            g.fill();
            break;
        }
        case 'S': { // ♠ 黑桃：倒置心形 + 底部茎
            g.moveTo(0, hh);
            g.bezierCurveTo(-hw, h * 0.15, -hw, -hh * 0.7, 0, -hh * 0.15);
            g.bezierCurveTo(hw, -hh * 0.7, hw, h * 0.15, 0, hh);
            g.close();
            g.fill();
            // 茎：上窄下宽的梯形
            g.moveTo(-w * 0.09, -hh * 0.62);
            g.lineTo(w * 0.09, -hh * 0.62);
            g.lineTo(w * 0.20, -hh);
            g.lineTo(-w * 0.20, -hh);
            g.close();
            g.fill();
            break;
        }
        case 'D': { // ♦ 方块：菱形
            g.moveTo(0, hh);
            g.lineTo(hw, 0);
            g.lineTo(0, -hh);
            g.lineTo(-hw, 0);
            g.close();
            g.fill();
            break;
        }
        case 'C': { // ♣ 梅花：三圆 + 茎
            const r = w * 0.23;
            g.circle(0, h * 0.18, r);
            g.fill();
            g.circle(-w * 0.25, -h * 0.10, r);
            g.fill();
            g.circle(w * 0.25, -h * 0.10, r);
            g.fill();
            g.moveTo(-w * 0.07, -h * 0.05);
            g.lineTo(w * 0.07, -h * 0.05);
            g.lineTo(w * 0.20, -hh);
            g.lineTo(-w * 0.20, -hh);
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

/** 花色选择按钮用的中文描述 */
export const SUIT_OPTIONS: { suit: string; label: string }[] = [
    { suit: 'SPADE', label: '♠' },
    { suit: 'HEART', label: '♥' },
    { suit: 'DIAMOND', label: '♦' },
    { suit: 'CLUB', label: '♣' },
];

/**
 * 创建一张牌的可视节点。
 * node.userData = { code } 供外部取回。
 */
export function createCardNode(code: string): Node {
    const node = new Node(`card_${code}`);
    node.layer = 1 << 25; // UI_2D
    const ut = node.addComponent(UITransform);
    ut.setContentSize(CARD_W, CARD_H);

    // 一个节点只能挂一个 UIRenderer：Graphics 留在本节点，Label 必须拆到子节点。
    // 点数与花色各用一个 Label：混合串 "♠3" 在系统字下会折行/截断（只显示花色）
    drawCardBg(node, false);

    const isJoker = code === 'BJ' || code === 'SJ';
    if (isJoker) {
        addFaceLabel(node, code === 'BJ' ? '大王' : '小王', 0, 0, 18,
            new Color(code === 'BJ' ? 200 : 160, 150, 20, 255));
    } else {
        const suit = SUIT_CHAR[code[0]];
        const rank = parseInt(code.slice(1), 10);
        const rankText = RANK_TEXT[rank] ?? String(rank);
        const color = suit?.red ? new Color(200, 30, 30, 255) : new Color(30, 30, 30, 255);
        // 点数：Label 加粗加大（数字/字母用系统字够清晰，bold 对它们有效）
        addFaceLabel(node, rankText, 0, 11, 30, color);
        // 花色：矢量绘制（不再用 Unicode ♠♥♦♣，真机上发虚）
        if (suit) addSuitGraphic(node, code[0], 0, -15, 22, color);
    }

    node.userData = { code };
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
    g.roundRect(-W / 2 + 2, -H / 2 - 3, W, H, 4);
    g.fillColor = new Color(0, 0, 0, 50);
    g.fill();
    // 牌体
    g.roundRect(-W / 2, -H / 2, W, H, 4);
    g.fillColor = new Color(250, 250, 245, 255);
    g.fill();
    g.lineWidth = 1;
    g.strokeColor = new Color(160, 160, 155, 255);
    g.stroke();

    const isJoker = code === 'BJ' || code === 'SJ';
    if (isJoker) {
        addMiniText(node, code === 'BJ' ? '大' : '小', 0, 0, 16,
            new Color(code === 'BJ' ? 200 : 160, 150, 20, 255));
    } else {
        const suit = SUIT_CHAR[code[0]];
        const rank = parseInt(code.slice(1), 10);
        const rankText = RANK_TEXT[rank] ?? String(rank);
        const color = suit?.red ? new Color(200, 30, 30, 255) : new Color(30, 30, 30, 255);
        addMiniText(node, rankText, 0, 7, 20, color);
        // 花色同样改矢量（迷你牌只有 32×46，Unicode 符号在这里几乎糊成一坨）
        if (suit) addSuitGraphic(node, code[0], 0, -10, 14, color);
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

/** 在牌节点上加一个居中文字子节点 */
function addFaceLabel(parent: Node, text: string, x: number, y: number,
                       fontSize: number, color: Color): void {
    const n = new Node('face');
    n.layer = 1 << 25;
    const ut = n.addComponent(UITransform);
    ut.setContentSize(CARD_W, fontSize + 6);
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
    // 投影：右下偏移的暗色圆角矩形（重叠手牌会产生自然的堆叠层次）
    g.roundRect(-CARD_W / 2 + 3, -CARD_H / 2 - 4, CARD_W, CARD_H, 6);
    g.fillColor = new Color(0, 0, 0, selected ? 100 : 55);
    g.fill();
    // 牌体
    g.roundRect(-CARD_W / 2, -CARD_H / 2, CARD_W, CARD_H, 6);
    g.fillColor = new Color(250, 250, 245, 255);
    g.fill();
    if (selected) {
        g.lineWidth = 3;
        g.strokeColor = new Color(230, 160, 20, 255);
    } else {
        g.lineWidth = 1;
        g.strokeColor = new Color(180, 180, 175, 255);
    }
    g.stroke();
}
