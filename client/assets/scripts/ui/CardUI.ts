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

    // 先加 Graphics（牌背），后加 Label（牌面文字）——同节点按组件添加顺序渲染
    drawCardBg(node, false);

    const face = cardFace(code);
    const label = node.addComponent(Label);
    label.string = face.text;
    label.fontSize = face.text.length > 2 ? 18 : 22;
    label.lineHeight = face.text.length > 2 ? 18 : 22;
    label.color = face.color;
    label.isBold = true;
    label.useSystemFont = true;     // 大王/小王中文 + 花色符号都得走系统字

    node.userData = { code };
    return node;
}

/** 重绘牌背（选中 = 金色粗边框） */
export function drawCardBg(node: Node, selected: boolean): void {
    let g = node.getComponent(Graphics);
    if (!g) g = node.addComponent(Graphics);
    g.clear();
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
