import { _decorator, Color, Component, Graphics, Label, Node, UITransform, Vec3, view } from 'cc';
import { NetClient } from '../net/NetClient';
import type { EventMsg, SeatName, SnapshotMsgDown } from '../net/Protocol';
import { cardFace, createCardNode, createMiniCardNode, drawCardBg, SUIT_OPTIONS } from './CardUI';

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
    private scoreLabel!: Label;
    private toastLabel!: Label;
    private timerLabel!: Label;
    private trickNode!: Node;
    private handNode!: Node;
    private btnNode!: Node;
    private settleNode: Node | null = null;

    // 出牌倒计时（仅本地提醒；服务端无超时托管，超时不出牌 bot 会一直等）
    private timerDeadline = 0;
    private timerKey = '';
    private static readonly TURN_SECONDS = 30;

    // 结算面板：收到 SETTLE 事件后展示 8s（服务端无 SETTLED 阶段，
    // SettleRoundCommand 直接 SETTLING→DEALING 开新局，只能事件驱动）
    private settleVisibleUntil = 0;
    private settleGameNumber = 0;   // 结算命令会把 gameNumber+1，事件里的局号已是下一局
    private preSettleBanker: SeatName | null = null; // 本局庄家（SETTLE 后快照的 banker 已是新庄，不能用）
    private static readonly SETTLE_SHOW_MS = 8000;

    /** 只有这些阶段的 banker 才是"本局"庄家（结算切庄后 DEALING/BIDDING 里已是下一局的） */
    private static readonly IN_GAME_PHASES = new Set(['BURYING', 'TRIBUTE', 'RETURN_TRIBUTE', 'PLAYING', 'SETTLING']);

    /** 对家（搭档）座位 */
    private static readonly PARTNER: Record<SeatName, SeatName> = {
        NORTH: 'SOUTH', SOUTH: 'NORTH', EAST: 'WEST', WEST: 'EAST',
    };

    // 设计分辨率 1280×720 内的布局常量
    private static readonly SEAT_POS: Record<SeatName, Vec3> = {
        NORTH: new Vec3(0, 180, 0),
        SOUTH: new Vec3(0, -80, 0),
        WEST: new Vec3(-420, 50, 0),
        EAST: new Vec3(420, 50, 0),
    };

    private static readonly PHASE_TEXT: Record<string, string> = {
        WAITING: '等待中', DEALING: '发牌中', BIDDING: '亮主', BURYING: '扣底',
        PLAYING: '出牌', TRIBUTE: '进贡', RETURN_TRIBUTE: '还贡',
        SETTLING: '结算中', SETTLE: '结算', ROUND_OVER: '整轮结束',
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
        // 顶部信息栏（行 1：局/阶段/级数/主牌/庄/轮）
        const top = this.makeLabelNode('连接中…', 18, new Color(50, 50, 50, 255));
        top.setPosition(0, 320, 0);
        this.node.addChild(top);
        this.topLabel = top.getComponent(Label)!;

        // 顶部信息栏（行 2：本墩捡分 + 各家分，醒目色）
        const score = this.makeLabelNode('', 20, new Color(220, 80, 30, 255));
        score.setPosition(0, 288, 0);
        this.node.addChild(score);
        this.scoreLabel = score.getComponent(Label)!;

        // Toast（事件提示，叠在信息栏下方）
        const toast = this.makeLabelNode('', 16, new Color(200, 80, 20, 255));
        toast.setPosition(0, 258, 0);
        this.node.addChild(toast);
        this.toastLabel = toast.getComponent(Label)!;

        // 出牌倒计时（轮到我时显示在按钮区右侧）
        const timer = this.makeLabelNode('', 32, new Color(235, 145, 25, 255));
        timer.setPosition(240, -30, 0);
        this.node.addChild(timer);
        this.timerLabel = timer.getComponent(Label)!;

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
        // 持续记录本局庄家：结算面板要在切庄后仍按"本局"的庄/抓分队贴标签
        const cur = this.snap;
        if (cur?.banker && TableUI.IN_GAME_PHASES.has(cur.phase)) {
            this.preSettleBanker = cur.banker;
        }
        this.renderTop();
        this.renderTrick();
        this.renderHand();
        this.renderButtons();
        this.renderTimerState();
        this.renderSettlement();
    }

    // ==================== 出牌倒计时 ====================

    /**
     * 服务端每条命令后都推全量快照，所以不能每次快照都重置倒计时——
     * 用「局号|阶段|轮到谁」作 key，只有轮替发生时才重新计 30s。
     */
    private renderTimerState(): void {
        const s = this.snap;
        const key = s ? `${s.gameNumber}|${s.phase}|${s.turn}` : '';
        if (key === this.timerKey) return;
        this.timerKey = key;
        this.timerDeadline = (s && s.phase === 'PLAYING' && s.turn === this.mySeat)
            ? Date.now() + TableUI.TURN_SECONDS * 1000
            : 0;
    }

    update(_dt: number): void {
        // 结算面板到期自动收起（出锅常驻除外）
        if (this.settleNode && Date.now() >= this.settleVisibleUntil
            && this.snap?.phase !== 'ROUND_OVER') {
            this.settleNode.destroy();
            this.settleNode = null;
        }
        if (!this.timerDeadline) {
            if (this.timerLabel && this.timerLabel.string !== '') this.timerLabel.string = '';
            return;
        }
        const left = (this.timerDeadline - Date.now()) / 1000;
        if (left > 0) {
            const s = Math.ceil(left);
            this.timerLabel.string = `剩 ${s}s`;
            this.timerLabel.color = s <= 5
                ? new Color(220, 45, 45, 255)
                : new Color(235, 145, 25, 255);
        } else {
            this.timerLabel.string = '已超时';
            this.timerLabel.color = new Color(150, 60, 60, 255);
        }
    }

    // ==================== 结算页（T-606） ====================

    /** 结算面板：SETTLE 事件后 8s 内展示（快照紧跟事件到达）；出锅（ROUND_OVER）常驻 */
    private renderSettlement(): void {
        this.settleNode?.destroy();
        this.settleNode = null;
        const s = this.snap;
        if (!s || !s.settlement) return;
        const roundOver = s.phase === 'ROUND_OVER';
        if (!roundOver && Date.now() >= this.settleVisibleUntil) return;
        const st = s.settlement;

        const overlay = new Node('settlement');
        overlay.layer = 1 << 25;
        overlay.addComponent(UITransform).setContentSize(1280, 720);
        overlay.setPosition(0, 0, 0);
        const g = overlay.addComponent(Graphics);
        // 半透明遮罩 + 深色面板（单节点单 UIRenderer：面板文字全部拆子节点）
        g.fillColor = new Color(0, 0, 0, 170);
        g.fillRect(-640, -360, 1280, 720);
        const PW = 660, PH = 420;
        g.fillColor = new Color(28, 42, 66, 255);
        g.roundRect(-PW / 2, -PH / 2, PW, PH, 16);
        g.fill();
        g.lineWidth = 3;
        g.strokeColor = new Color(230, 170, 40, 255);
        g.stroke();
        this.node.addChild(overlay);
        this.settleNode = overlay;

        // 队伍归属：庄家 + 其搭档 = 庄家方，另外两家 = 抓分方
        // 用 preSettleBanker（本局庄）：快照里的 banker 在抓分方上台后已切给下一局
        const bankerSeat = (this.preSettleBanker ?? s.banker ?? 'NORTH') as SeatName;
        const bankerTeam = [bankerSeat, TableUI.PARTNER[bankerSeat]];
        const allSeats: SeatName[] = ['NORTH', 'EAST', 'SOUTH', 'WEST'];
        const attackerTeam = allSeats.filter(x => !bankerTeam.includes(x));
        const iAmBankerTeam = bankerTeam.includes(this.mySeat);

        const gold = new Color(235, 180, 45, 255);
        const white = new Color(235, 235, 235, 255);
        const gray = new Color(150, 160, 175, 255);

        this.addSettleText(overlay, `第 ${this.settleGameNumber || s.gameNumber} 局 · 结算`, 0, 150, 30, gold, true);
        this.addSettleText(overlay, `庄家方 ${bankerTeam.join(' + ')}：${st.bankerScore} 分`, 0, 95, 22, white);
        this.addSettleText(overlay, `抓分方 ${attackerTeam.join(' + ')}：${st.attackerScore} 分`, 0, 58, 22, white);

        const takesColor = st.attackerTakesBank
            ? new Color(90, 200, 110, 255) : new Color(120, 170, 235, 255);
        this.addSettleText(overlay,
            st.attackerTakesBank ? '抓分方上台！' : '庄家方守住',
            0, 14, 27, takesColor, true);

        let promo = '双方不升级';
        if (st.attackerPromoted) promo = '抓分方升级';
        else if (st.bankerPromoted) promo = '庄家方升级';
        if (st.dugBottom) promo += ' · 抠底！底牌分×2';
        this.addSettleText(overlay, promo, 0, -24, 19, gray);

        const iWin = iAmBankerTeam ? !st.attackerTakesBank : st.attackerTakesBank;
        this.addSettleText(overlay, iWin ? '我方胜利！' : '我方失利', 0, -85, 36,
            iWin ? gold : new Color(130, 135, 145, 255), true);
        this.addSettleText(overlay,
            roundOver ? '整轮结束（出锅），本轮收官' : '下一局即将自动开始…',
            0, -150, 15, gray);
    }

    /** 结算面板内的一行文字（子节点，避开单 UIRenderer 限制） */
    private addSettleText(parent: Node, text: string, x: number, y: number,
                          size: number, color: Color, bold = false): void {
        const n = new Node('line');
        n.layer = 1 << 25;
        n.addComponent(UITransform).setContentSize(640, size + 8);
        const l = n.addComponent(Label);
        l.string = text;
        l.fontSize = size;
        l.lineHeight = size + 4;
        l.color = color;
        l.isBold = bold;
        l.useSystemFont = true;
        l.horizontalAlign = Label.HorizontalAlign.CENTER;
        l.verticalAlign = Label.VerticalAlign.CENTER;
        n.setPosition(x, y, 0);
        parent.addChild(n);
    }

    private renderTop(): void {
        const s = this.snap;
        if (!s) {
            this.topLabel.string = this.net?.online ? '已连接，等待快照…' : '连接中…';
            this.scoreLabel.string = '';
            return;
        }
        const phase = TableUI.PHASE_TEXT[s.phase] ?? s.phase;
        const trump = s.trump ? `${s.trump.suit[0]}${s.trump.level}` : '未定';
        this.topLabel.string =
            `第${s.gameNumber}局 ${phase} | 级${s.level} 主${trump} | 庄${s.banker ?? '-'} 轮${s.turn ?? '-'}`
            + `${this.net?.online ? '' : '  ⚠ 离线'}`;

        // 本墩分 + 各家分（醒目色）
        const trickPts = s.trickPoints ?? {};
        const totalPts = Object.values(trickPts).reduce((a, b) => a + (b as number), 0);
        const breakdown = Object.entries(trickPts)
            .map(([team, v]) => `${team} ${v}`).join('  ');
        this.scoreLabel.string = totalPts > 0
            ? `本墩捡分 ${totalPts}（${breakdown}）`
            : '本墩暂未捡分';
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

        // 当前墩出牌：用迷你牌（真实牌面 + 花色）显示在每家铭牌上方
        const plays: Array<{ seat: SeatName; cards: string[] }> = [];
        if (s.trick?.plays && s.trick.plays.length > 0) {
            for (const p of s.trick.plays) {
                plays.push({ seat: p.seat as SeatName, cards: p.cards });
            }
        } else if (s.trick?.leader && s.trick.leadCards) {
            plays.push({ seat: s.trick.leader as SeatName, cards: s.trick.leadCards });
        }
        for (const p of plays) {
            this.renderPlacedCards(p.seat, p.cards);
        }
    }

    /** 在指定座位铭牌上方横排渲染一组牌（多张时摊开） */
    private renderPlacedCards(seat: SeatName, cards: string[]): void {
        const pos = TableUI.SEAT_POS[seat];
        const spacing = 22;   // 迷你牌 32 宽 + 间隔
        const totalW = (cards.length - 1) * spacing;
        for (let i = 0; i < cards.length; i++) {
            const c = createMiniCardNode(cards[i]);
            // 位于铭牌上方 32px（让出 26px 铭牌 + 6px 间隙）；多张牌居中横排
            c.setPosition(pos.x - totalW / 2 + i * spacing, pos.y + 32, 0);
            this.trickNode.addChild(c);
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
        // 39 张牌排开（牌宽 56）：横屏按 1100 上限，窄窗口/竖屏按可见宽度收缩，避免手牌被裁
        const cardW = 56;
        const vw = view.getVisibleSize().width;
        const maxSpread = Math.min(1100, vw - 60);
        const gap = 16;   // 组间额外间隙（副牌按花色分堆 + 主牌一堆）
        const groups = this.groupCount(hand);
        const gapTotal = Math.max(groups - 1, 0) * gap;
        const spacing = n > 1 ? Math.min(cardW - 12, (maxSpread - cardW - gapTotal) / (n - 1)) : cardW;
        const total = spacing * (n - 1) + gapTotal;

        // 排序后的显示顺序（原始下标数组），选中仍按原始下标记
        const order = this.sortHand(hand);
        let x = -total / 2;
        let prevGroup = -1;
        for (let i = 0; i < n; i++) {
            const idx = order[i];
            const g = this.groupOf(hand[idx]);
            if (i > 0 && g !== prevGroup) x += gap;   // 换组加间隙
            prevGroup = g;
            const card = createCardNode(hand[idx]);
            const selected = this.selected.has(idx);
            card.setPosition(x, selected ? 18 : 0, 0);
            if (selected) drawCardBg(card, true);
            // 牌面 56 宽但间距 44 互相重叠：命中区缩为一张 spacing 宽，
            // 否则点牌的右侧露出部分会命中叠在上面的右边那张（视觉错位）
            card.getComponent(UITransform)!.setContentSize(Math.min(spacing, cardW), 80);
            card.on(Node.EventType.TOUCH_END, () => {
                if (this.selected.has(idx)) this.selected.delete(idx);
                else this.selected.add(idx);
                this.renderHand();
            });
            this.handNode.addChild(card);
            x += spacing;
        }
    }

    // ==================== 手牌排序（主牌一堆 + 副牌按花色分堆） ====================

    /** 服务端花色名 → 牌编码首字符 */
    private static readonly SUIT_CHAR: Record<string, string> = {
        SPADE: 'S', HEART: 'H', DIAMOND: 'D', CLUB: 'C',
    };
    /** 副牌堆从左到右的花色顺序（主花色会被抽到主牌堆） */
    private static readonly SIDE_ORDER = ['S', 'H', 'D', 'C'];

    private parseCode(code: string): { suit: string; rank: number; joker: number } {
        if (code === 'BJ') return { suit: '', rank: 0, joker: 2 };
        if (code === 'SJ') return { suit: '', rank: 0, joker: 1 };
        return { suit: code[0], rank: parseInt(code.slice(1), 10), joker: 0 };
    }

    /**
     * 手册 2.1 主牌判定（与服务端 CardTier.isTrump 对齐）：
     * 王、所有 2（常主）、所有级牌、主花色牌。定主前（trump 空）无主牌堆。
     */
    private isTrumpCard(code: string): boolean {
        const t = this.snap?.trump;
        if (!t) return false;
        const { suit, rank, joker } = this.parseCode(code);
        if (joker > 0) return true;
        if (rank === t.level) return true;
        if (rank === 2) return true;
        return suit === (TableUI.SUIT_CHAR[t.suit] ?? '');
    }

    /**
     * 分组号：0 = 主牌堆（最左），1..4 = 副牌四堆（按 SIDE_ORDER）。
     * 定主前无主牌堆：王单独一堆靠左，其余按花色分堆。
     */
    private groupOf(code: string): number {
        const { suit, joker } = this.parseCode(code);
        if (joker > 0) return 0;   // 王恒在最左堆：无主时单独一堆，定主后并入主牌堆
        if (this.isTrumpCard(code)) return 0;
        const g = TableUI.SIDE_ORDER.indexOf(suit);
        return g >= 0 ? g + 1 : 4;
    }

    private groupCount(hand: string[]): number {
        const set = new Set<number>();
        for (const c of hand) set.add(this.groupOf(c));
        return set.size;
    }

    /**
     * 返回排序后的原始下标数组（显示从左到右）。
     * 主牌堆最左、按牌力从大到小（大王最左）；副牌四堆在其后，堆内同样从大到小（A 在左）。
     */
    private sortHand(hand: string[]): number[] {
        const t = this.snap?.trump;
        const tierOf = (code: string): number => {
            const { suit, rank, joker } = this.parseCode(code);
            if (joker === 2) return 800;
            if (joker === 1) return 700;
            if (!t) return rank;   // 定主前：仅按点数比较，堆内排序用
            const trumpSuit = TableUI.SUIT_CHAR[t.suit] ?? '';
            if (rank === t.level) return suit === trumpSuit ? 600 : 500;
            if (rank === 2) return suit === trumpSuit ? 400 : 300;
            // 副牌返回点数（而非服务端统一层 100）：堆内排序需要真实点数，
            // 否则同堆 tier 全相等会掉进字符串兜底比较（D10<D13<D14<D4 字典序错误）
            return suit === trumpSuit ? 200 + rank : rank;
        };
        const idx = hand.map((_, i) => i);
        idx.sort((a, b) => {
            const ga = this.groupOf(hand[a]);
            const gb = this.groupOf(hand[b]);
            if (ga !== gb) return ga - gb;                      // 先按堆（主牌最左）
            // 堆内统一从大到小（副牌 A 在左 / 主牌大王在左）；同 tier 返回 0，
            // JS sort 现代引擎保证稳定，多副本顺序不变
            return tierOf(hand[b]) - tierOf(hand[a]);
        });
        return idx;
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
        if (e.op === 'SETTLE') {
            // 结算面板展示窗口：事件先到、快照紧随其后（renderAll 里渲染）
            this.settleVisibleUntil = Date.now() + TableUI.SETTLE_SHOW_MS;
            // 事件发出时结算命令已执行完，gameNumber 已 +1 → 实际结算局号要减 1
            const gn = (e.gameNumber ?? this.snap?.gameNumber ?? 1) - 1;
            this.settleGameNumber = Math.max(gn, 1);
        }
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
