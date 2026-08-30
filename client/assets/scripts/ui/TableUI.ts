import { _decorator, Color, Component, Graphics, Label, Node, UITransform, Vec3 } from 'cc';
import { NetClient } from '../net/NetClient';
import type { EventMsg, SeatName, SnapshotMsgDown } from '../net/Protocol';
import { cardFace, createCardNode, drawCardBg, SUIT_OPTIONS } from './CardUI';

const { ccclass, property } = _decorator;

/**
 * 牌桌主控（T-602/T-603/T-604）：快照驱动渲染 + 阶段操作按钮。
 *
 * 布局（750×1334 设计分辨率，中心原点）：
 *   顶部  y≈560  信息栏（局/阶段/级数/主牌/庄家/轮到/捡分）
 *   中部  y≈0    当前墩（四家出牌）
 *   下部  y≈-360 操作按钮区（随阶段变化）
 *   底部  y≈-520 我的手牌（点击选中弹起）
 *
 * 渲染策略（T-603 初版）：全量快照驱动重建（39 节点级重建，微信真机足够流畅；
 * 后续量大再改差量）。
 */
@ccclass('TableUI')
export class TableUI extends Component {

    @property serverUrl = 'ws://localhost:8080/ws';
    @property roomId = 1001;
    @property playerId = 1;
    @property mySeat: SeatName = 'NORTH';

    private net: NetClient | null = null;
    private snap: SnapshotMsgDown | null = null;
    private selected = new Set<number>();   // 手牌下标（三副牌有重复，按下标选）

    private topLabel!: Label;
    private toastLabel!: Label;
    private trickNode!: Node;
    private handNode!: Node;
    private btnNode!: Node;

    private static readonly SEAT_POS: Record<SeatName, Vec3> = {
        NORTH: new Vec3(0, 150, 0),
        SOUTH: new Vec3(0, -120, 0),
        WEST: new Vec3(-260, 15, 0),
        EAST: new Vec3(260, 15, 0),
    };

    private static readonly PHASE_TEXT: Record<string, string> = {
        WAITING: '等待中', DEALING: '发牌中', BIDDING: '亮主', BURYING: '扣底',
        PLAYING: '出牌', TRIBUTE: '进贡', RETURN_TRIBUTE: '还贡', SETTLED: '结算',
    };

    start(): void {
        this.buildLayout();
        this.net = new NetClient(this.serverUrl);
        this.net.onJoined(() => this.showToast(`已入座 ${this.mySeat}`));
        this.net.onSnapshot(s => { this.snap = s; this.renderAll(); });
        this.net.onEvent(e => this.onEventToast(e));
        this.net.onError(r => this.showToast(`⚠ ${r}`, true));
        this.net.onStateChange(ok => this.renderTop());
        this.net.join(this.roomId, this.playerId, this.mySeat);
    }

    onDestroy(): void {
        this.net?.close();
    }

    // ==================== 布局骨架 ====================

    private buildLayout(): void {
        this.topLabel = this.makeLabel('连接中…', 24, new Color(60, 60, 60, 255));
        this.topLabel.node.setPosition(0, 560, 0);

        this.toastLabel = this.makeLabel('', 26, new Color(180, 60, 20, 255));
        this.toastLabel.node.setPosition(0, 470, 0);

        this.trickNode = new Node('trick');
        this.trickNode.layer = 1 << 25;
        this.node.addChild(this.trickNode);

        this.btnNode = new Node('buttons');
        this.btnNode.layer = 1 << 25;
        this.btnNode.setPosition(0, -350, 0);
        this.node.addChild(this.btnNode);

        this.handNode = new Node('hand');
        this.handNode.layer = 1 << 25;
        this.handNode.setPosition(0, -540, 0);
        this.node.addChild(this.handNode);
    }

    private makeLabel(text: string, size: number, color: Color): Label {
        const n = new Node('label');
        n.layer = 1 << 25;
        const l = n.addComponent(Label);
        l.string = text;
        l.fontSize = size;
        l.lineHeight = size + 4;
        l.color = color;
        this.node.addChild(n);
        return l;
    }

    private makeButton(text: string, x: number, cb: () => void): Node {
        const n = new Node(`btn_${text}`);
        n.layer = 1 << 25;
        const ut = n.addComponent(UITransform);
        ut.setContentSize(140, 64);
        // 先 Graphics（底色）后 Label（文字），同节点按添加顺序渲染
        const g = n.addComponent(Graphics);
        g.roundRect(-70, -32, 140, 64, 8);
        g.fillColor = new Color(70, 110, 190, 255);
        g.fill();
        const l = n.addComponent(Label);
        l.string = text;
        l.fontSize = 24;
        l.lineHeight = 28;
        l.color = new Color(255, 255, 255, 255);
        l.isBold = true;
        n.setPosition(x, 0, 0);
        n.on(Node.EventType.TOUCH_END, cb);
        this.btnNode.addChild(n);
        return n;
    }

    // ==================== 渲染 ====================

    private renderAll(): void {
        this.renderTop();
        this.renderTrick();
        this.renderHand();
        this.renderButtons();
    }

    private renderTop(): void {
        const s = this.snap;
        if (!s) {
            this.topLabel.string = this.net?.online ? '已连接，等待快照…' : '连接中…';
            return;
        }
        const phase = TableUI.PHASE_TEXT[s.phase] ?? s.phase;
        const trump = s.trump ? `${s.trump.suit[0]}${s.trump.level}` : '未定';
        const pts = s.trickPoints ? JSON.stringify(s.trickPoints) : '';
        this.topLabel.string =
            `第${s.gameNumber}局 ${phase} | 级${s.level} 主${trump} | 庄${s.banker ?? '-'} 轮${s.turn ?? '-'}\n` +
            `捡分 ${pts}  ${this.net?.online ? '' : '⚠ 离线'}`;
    }

    private renderTrick(): void {
        this.trickNode.removeAllChildren();
        const s = this.snap;
        if (!s) return;
        // 四家座位铭牌 + 余牌数
        if (s.hands) {
            for (const seat of Object.keys(s.hands) as SeatName[]) {
                const l = this.makeSeatLabel(`${seat}(${s.hands[seat]})`, seat);
                this.trickNode.addChild(l);
            }
        }
        // 当前墩出牌
        const plays = s.trick?.plays?.length
            ? s.trick.plays
            : (s.trick ? [{ seat: s.trick.leader, cards: s.trick.leadCards }] : []);
        for (const p of plays) {
            const l = this.makeSeatLabel(p.cards.map(c => cardFace(c).text).join(' '), p.seat as SeatName, true);
            this.trickNode.addChild(l);
        }
    }

    private makeSeatLabel(text: string, seat: SeatName, isPlay = false): Node {
        const n = new Node(`seat_${seat}`);
        n.layer = 1 << 25;
        const l = n.addComponent(Label);
        l.string = text;
        l.fontSize = isPlay ? 24 : 20;
        l.lineHeight = isPlay ? 28 : 24;
        l.color = isPlay ? new Color(30, 30, 30, 255) : new Color(120, 120, 120, 255);
        const pos = TableUI.SEAT_POS[seat];
        n.setPosition(pos.x, pos.y + (isPlay ? 40 : 0), 0);
        return n;
    }

    private renderHand(): void {
        this.handNode.removeAllChildren();
        const hand = this.snap?.yourHand ?? [];
        const n = hand.length;
        const spacing = Math.min(26, n > 1 ? 680 / (n - 1) : 26);
        const total = n > 0 ? spacing * (n - 1) + 56 : 0;
        for (let i = 0; i < n; i++) {
            const card = createCardNode(hand[i]);
            const selected = this.selected.has(i);
            card.setPosition(-total / 2 + 28 + i * spacing, selected ? 30 : 0, 0);
            if (selected) drawCardBg(card, true);
            card.on(Node.EventType.TOUCH_END, () => {
                if (this.selected.has(i)) this.selected.delete(i);
                else this.selected.add(i);
                this.renderHand();
            });
            this.handNode.addChild(card);
        }
    }

    private selectedCodes(): string[] {
        const hand = this.snap?.yourHand ?? [];
        return [...this.selected].sort((a, b) => a - b).map(i => hand[i]);
    }

    // ==================== 阶段操作按钮（T-604） ====================

    private renderButtons(): void {
        this.btnNode.removeAllChildren();
        const s = this.snap;
        if (!s) return;
        const myTurn = s.turn === this.mySeat;
        switch (s.phase) {
            case 'BIDDING': {
                if (this.selected.size > 0) {
                    // 亮主需声明花色：四个花色按钮（选中手牌为亮出的牌）
                    SUIT_OPTIONS.forEach((o, i) => {
                        const b = this.makeButton(`亮${o.label}`, -240 + i * 160,
                            () => this.net?.sendCmd('REVEAL', { cards: this.selectedCodes(), suit: o.suit }));
                    });
                }
                if (s.reveal) {
                    this.makeButton('确认定主', 240, () => this.net?.sendCmd('CONFIRM'));
                }
                break;
            }
            case 'BURYING': {
                if (s.banker === this.mySeat) {
                    const need = 6 - this.selected.size;
                    this.makeButton(this.selected.size > 0 ? `扣底(还差${need}张)` : '扣底：选6张', 0,
                        () => {
                            if (this.selected.size !== 6) { this.showToast('扣底需恰好 6 张', true); return; }
                            this.net?.sendCmd('BURY', { cards: this.selectedCodes() });
                        });
                }
                break;
            }
            case 'PLAYING': {
                if (myTurn) {
                    this.makeButton(this.selected.size > 0 ? '出牌' : '出牌：先选牌', 0,
                        () => {
                            if (this.selected.size === 0) { this.showToast('先点选要出的牌', true); return; }
                            this.net?.sendCmd('PLAY', { cards: this.selectedCodes() });
                        });
                }
                break;
            }
            case 'TRIBUTE': {
                const mine = s.pendingTributes?.[this.mySeat];
                if (mine) {
                    this.makeButton('进贡', 0, () => {
                        if (this.selected.size === 0) { this.showToast('先选要贡的牌', true); return; }
                        this.net?.sendCmd('TRIBUTE', { cards: this.selectedCodes(), payee: mine.receiver });
                    });
                }
                break;
            }
            case 'RETURN_TRIBUTE': {
                // 找到贡给我的那位（pendingTributes[x].receiver == 我）→ 还贡给他
                const payer = Object.entries(s.pendingTributes ?? {})
                    .find(([, v]) => v.receiver === this.mySeat)?.[0];
                if (payer) {
                    this.makeButton('还贡', 0, () => {
                        if (this.selected.size === 0) { this.showToast('先选要还的牌', true); return; }
                        this.net?.sendCmd('RETURN_TRIBUTE', { cards: this.selectedCodes(), payee: payer });
                    });
                }
                break;
            }
        }
    }

    // ==================== 事件提示 ====================

    private onEventToast(e: EventMsg): void {
        if (e.op === 'DEAL') return; // 发牌事件太吵
        const who = e.seat ?? '';
        const what = e.cards?.length ? ` ${e.cards.map(c => cardFace(c).text).join(' ')}` : '';
        const fail = e.success === false ? ` 失败:${String(e.reason ?? '')}` : '';
        this.showToast(`${who} ${TableUI.PHASE_TEXT[e.op] ?? e.op}${what}${fail}`, e.success === false);
    }

    private showToast(text: string, _warn = false): void {
        this.toastLabel.string = text;
        this.toastLabel.color = _warn ? new Color(200, 30, 30, 255) : new Color(160, 100, 20, 255);
    }
}
