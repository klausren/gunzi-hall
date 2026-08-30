import { _decorator, Color, Component, Graphics, Label, Node, UITransform, Vec3 } from 'cc';
import { NetClient } from '../net/NetClient';
import type { EventMsg, SeatName, SnapshotMsgDown } from '../net/Protocol';
import { cardFace, createCardNode, drawCardBg, SUIT_OPTIONS } from './CardUI';

const { ccclass, property } = _decorator;

/**
 * 牌桌主控（T-602/603/604）。设计分辨率：1280×720（屏幕半高 360）。
 *
 *   顶部  y≈300  信息栏（局/阶段/级数/主牌/庄家/轮到/捡分）
 *   中部  y≈100  当前墩（四家出牌 + 座位铭牌）
 *   下部  y≈-150 操作按钮区
 *   底部  y≈-280 我的手牌（点击选中弹起）
 *
 * 渲染策略（T-603 初版）：全量快照驱动重建（39 节点级，差量留后续优化）。
 */
@ccclass('TableUI')
export class TableUI extends Component {

    @property serverUrl = 'ws://localhost:8080/ws';
    @property roomId = 1001;
    @property playerId = 1;
    @property mySeat: SeatName = 'NORTH';

    private net: NetClient | null = null;
    private snap: SnapshotMsgDown | null = null;
    private selected = new Set<number>();   // 手牌下标

    private topLabel!: Label;
    private toastLabel!: Label;
    private trickNode!: Node;
    private handNode!: Node;
    private btnNode!: Node;

    // 设计分辨率 1280×720 内的布局常量
    private static readonly SEAT_POS: Record<SeatName, Vec3> = {
        NORTH: new Vec3(0, 180, 0),
        SOUTH: new Vec3(0, -80, 0),
        WEST: new Vec3(-420, 50, 0),
        EAST: new Vec3(420, 50, 0),
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
        // 顶部信息栏
        const top = this.makeLabelNode('连接中…', 18, new Color(50, 50, 50, 255));
        top.setPosition(0, 320, 0);
        this.node.addChild(top);
        this.topLabel = top.getComponent(Label)!;

        // Toast（事件提示，叠在信息栏下方）
        const toast = this.makeLabelNode('', 16, new Color(200, 80, 20, 255));
        toast.setPosition(0, 290, 0);
        this.node.addChild(toast);
        this.toastLabel = toast.getComponent(Label)!;

        // 中部当前墩
        this.trickNode = new Node('trick');
        this.trickNode.layer = 1 << 25;
        this.trickNode.setPosition(0, 80, 0);
        this.node.addChild(this.trickNode);

        // 下部操作按钮（提到 y=-130，躲开发调试面板大约 y∈[-100,-200] 区域）
        this.btnNode = new Node('buttons');
        this.btnNode.layer = 1 << 25;
        this.btnNode.setPosition(0, -30, 0);
        this.node.addChild(this.btnNode);

        // 底部手牌（y=-220，调试面板关闭时牌底 y=-260<屏底-360）
        this.handNode = new Node('hand');
        this.handNode.layer = 1 << 25;
        this.handNode.setPosition(0, -240, 0);
        this.node.addChild(this.handNode);
    }

    private makeLabelNode(text: string, size: number, color: Color): Node {
        const n = new Node('label');
        n.layer = 1 << 25;
        const ut = n.addComponent(UITransform);
        ut.setContentSize(1280, size + 8);
        const l = n.addComponent(Label);
        l.string = text;
        l.fontSize = size;
        l.lineHeight = size + 4;
        l.color = color;
        // 系统字体：Bitmap font 可能缺字（如“庄”），“”等中文不可靠
        l.useSystemFont = true;
        l.horizontalAlign = Label.HorizontalAlign.CENTER;
        l.verticalAlign = Label.VerticalAlign.CENTER;
        return n;
    }

    private makeButton(text: string, x: number, cb: () => void): Node {
        const n = new Node(`btn_${text}`);
        n.layer = 1 << 25;
        const ut = n.addComponent(UITransform);
        ut.setContentSize(140, 48);
        const g = n.addComponent(Graphics);
        g.roundRect(-70, -24, 140, 48, 8);
        g.fillColor = new Color(70, 110, 190, 255);
        g.fill();
        // Label 独立节点挂在按钮下，填满整个按钮区域
        const txt = new Node(`txt_${text}`);
        txt.layer = 1 << 25;
        const txtUt = txt.addComponent(UITransform);
        txtUt.setContentSize(140, 48);
        const l = txt.addComponent(Label);
        l.string = text;
        l.fontSize = 18;
        l.lineHeight = 22;
        l.color = new Color(255, 255, 255, 255);
        l.isBold = true;
        l.useSystemFont = true;            // 避免按钮文字缺字
        l.horizontalAlign = Label.HorizontalAlign.CENTER;
        l.verticalAlign = Label.VerticalAlign.CENTER;
        n.addChild(txt);
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
            `第${s.gameNumber}局 ${phase} | 级${s.level} 主${trump} | 庄${s.banker ?? '-'} 轮${s.turn ?? '-'}  捡分${pts}` +
            `${this.net?.online ? '' : '  ⚠ 离线'}`;
    }

    private renderTrick(): void {
        this.trickNode.removeAllChildren();
        const s = this.snap;
        if (!s) return;

        // 四家座位铭牌 + 余牌数
        if (s.hands) {
            for (const seat of Object.keys(s.hands) as SeatName[]) {
                const l = this.makeSeatLabel(`${seat}(${s.hands[seat]})`, seat, false);
                this.trickNode.addChild(l);
            }
        }

        // 当前墩出牌
        const plays: Array<{ seat: SeatName; cards: string[] }> = [];
        if (s.trick?.plays && s.trick.plays.length > 0) {
            for (const p of s.trick.plays) {
                plays.push({ seat: p.seat as SeatName, cards: p.cards });
            }
        } else if (s.trick?.leader && s.trick.leadCards) {
            plays.push({ seat: s.trick.leader as SeatName, cards: s.trick.leadCards });
        }
        for (const p of plays) {
            const l = this.makeSeatLabel(p.cards.map(c => cardFace(c).text).join(' '), p.seat, true);
            this.trickNode.addChild(l);
        }
    }

    private makeSeatLabel(text: string, seat: SeatName, isPlay = false): Node {
        const n = new Node(`seat_${seat}`);
        n.layer = 1 << 25;
        const ut = n.addComponent(UITransform);
        ut.setContentSize(200, isPlay ? 36 : 26);
        const l = n.addComponent(Label);
        l.string = text;
        l.fontSize = isPlay ? 18 : 14;
        l.lineHeight = isPlay ? 26 : 20;
        l.color = isPlay ? new Color(20, 20, 20, 255) : new Color(120, 120, 120, 255);
        l.useSystemFont = true;
        l.horizontalAlign = Label.HorizontalAlign.CENTER;
        l.verticalAlign = Label.VerticalAlign.CENTER;
        const pos = TableUI.SEAT_POS[seat];
        // 出牌显示在铭牌上方
        n.setPosition(pos.x, pos.y + (isPlay ? 26 : 0), 0);
        return n;
    }

    private renderHand(): void {
        this.handNode.removeAllChildren();
        const hand = this.snap?.yourHand ?? [];
        const n = hand.length;
        if (n === 0) return;
        // 39 张牌在 1280 宽度内排开（牌宽 56），spacing 按可用宽度计算
        const cardW = 56;
        const maxSpread = 1100;
        const spacing = n > 1 ? Math.min(cardW - 12, (maxSpread - cardW) / (n - 1)) : cardW;
        const total = spacing * (n - 1);
        for (let i = 0; i < n; i++) {
            const card = createCardNode(hand[i]);
            const selected = this.selected.has(i);
            card.setPosition(-total / 2 + i * spacing, selected ? 18 : 0, 0);
            if (selected) drawCardBg(card, true);
            // 牌面 56 宽但间距 44 互相重叠：命中区缩为一张 spacing 宽，
            // 否则点牌的右侧露出部分会命中叠在上面的右边那张（视觉错位）
            card.getComponent(UITransform)!.setContentSize(Math.min(spacing, cardW), 80);
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

    // ==================== 阶段操作按钮 ====================

    private renderButtons(): void {
        this.btnNode.removeAllChildren();
        const s = this.snap;
        if (!s) return;
        const myTurn = s.turn === this.mySeat;
        switch (s.phase) {
            case 'BIDDING': {
                if (this.selected.size > 0) {
                    SUIT_OPTIONS.forEach((o, i) => {
                        const x = -240 + i * 160;
                        this.makeButton(`亮${o.label}`, x, () => {
                            this.net?.sendCmd('REVEAL', { cards: this.selectedCodes(), suit: o.suit });
                        });
                    });
                }
                if (s.reveal) {
                    this.makeButton('确认定主', -240, () => this.net?.sendCmd('CONFIRM'));
                }
                break;
            }
            case 'BURYING': {
                if (s.banker === this.mySeat) {
                    const need = 6 - this.selected.size;
                    const txt = this.selected.size > 0
                        ? `扣底(还差${Math.max(need, 0)}张)`
                        : '扣底：选6张';
                    this.makeButton(txt, 0, () => {
                        if (this.selected.size !== 6) { this.showToast('扣底需恰好 6 张', true); return; }
                        this.net?.sendCmd('BURY', { cards: this.selectedCodes() });
                    });
                }
                break;
            }
            case 'PLAYING': {
                if (myTurn) {
                    this.makeButton(this.selected.size > 0 ? '出牌' : '出牌：先选牌', 0, () => {
                        if (this.selected.size === 0) { this.showToast('先点选要出的牌', true); return; }
                        this.net?.sendCmd('PLAY', { cards: this.selectedCodes() });
                        this.selected.clear();
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
                        this.selected.clear();
                    });
                }
                break;
            }
            case 'RETURN_TRIBUTE': {
                const payer = Object.entries(s.pendingTributes ?? {})
                    .find(([, v]) => v.receiver === this.mySeat)?.[0];
                if (payer) {
                    this.makeButton('还贡', 0, () => {
                        if (this.selected.size === 0) { this.showToast('先选要还的牌', true); return; }
                        this.net?.sendCmd('RETURN_TRIBUTE', { cards: this.selectedCodes(), payee: payer });
                        this.selected.clear();
                    });
                }
                break;
            }
        }
    }

    // ==================== 事件提示 ====================

    private onEventToast(e: EventMsg): void {
        if (e.op === 'DEAL') return;
        const who = e.seat ?? '';
        const what = e.cards?.length ? ` ${e.cards.map(c => cardFace(c).text).join(' ')}` : '';
        const fail = e.success === false ? ` 失败:${String(e.reason ?? '')}` : '';
        this.showToast(`${who} ${TableUI.PHASE_TEXT[e.op] ?? e.op}${what}${fail}`, e.success === false);
    }

    private showToast(text: string, warn = false): void {
        this.toastLabel.string = text;
        this.toastLabel.color = warn ? new Color(200, 30, 30, 255) : new Color(160, 100, 20, 255);
    }
}
