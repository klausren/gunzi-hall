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
        addFaceLabel(node, rankText, 0, 10, 26, color);      // 点数大字
        if (suit) addFaceLabel(node, suit.ch, 0, -18, 20, color); // 花色符号
    }

    node.userData = { code };
    return node;
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
