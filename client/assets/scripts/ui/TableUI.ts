import { _decorator, Color, Component, Graphics, Label, Node, Tween, tween, UIOpacity, UITransform, Vec3, view } from 'cc';
import { NetClient } from '../net/NetClient';
import type { EventMsg, JoinedMsg, SeatName, SnapshotMsgDown } from '../net/Protocol';
import { cardFace, createCardNode, createMiniCardNode, drawCardBg, SUIT_OPTIONS } from './CardUI';

const { ccclass, property } = _decorator;

/**
 * 牌桌主控（Sprint 6 juice 版）。设计分辨率：1280×720（屏幕半高 360）。
 *
 * 视角旋转：以 mySeat 为底部座位（我永远在下方，出牌落点在手牌上方），
 * 其余三家按相对位次映射到 右/上/左 插槽。
 *
 * 布局（世界坐标，屏幕 -360..360）：
 *   顶部 y≈320/288/256   信息栏两行 + toast
 *   上家   y≈205 铭牌（其墩牌在铭牌下方 y≈160）
 *   左右   y≈60  铭牌（墩牌在铭牌上方 y≈105）
 *   我     y≈-160 铭牌（墩牌 y≈-115，按钮区 y≈-30，手牌 y≈-240）
 *
 * 渲染策略（juice 版）：铭牌/墩牌改持久节点 + 差量动画——
 * 快照仍是全量推送，但 renderTrick 按「座位→牌串」diff，只对新增出牌做飞入动画；
 * 墩清空时整墩向赢家（下一轮领出者）收拢。手牌选中不再全量重建（点击即弹跳）。
 *
 * 动效节奏（紧凑档）：飞牌 0.18s quadOut、收墩 0.22s quadIn（0.04s 阶梯）、
 * 按压 0.08s / 回弹 0.12s backOut、结算面板弹入 0.25s backOut。
 */
@ccclass('TableUI')
export class TableUI extends Component {

    // 默认值仅作兜底，运行时以 main.scene 里挂载的 serverUrl 为准。
    // 【真机调试必读】手机上的 localhost 是手机自己，必须填电脑的局域网 IP，
    // 且手机与电脑连同一 WiFi。换 WiFi 后 IP 会变，两处都要改。
    @property serverUrl = 'ws://10.192.0.121:8080/ws';
    @property roomId = 1001;
    @property playerId = 1;
    @property mySeat: SeatName = 'NORTH';

    private net: NetClient | null = null;
    private snap: SnapshotMsgDown | null = null;
    // 【真机必修】用普通 string[] 存选中牌代码，**不要用 Set<string>**。
    // 原因：Cocos 把 `[...this.selected]` 编译成 `[].concat(this.selected)`，
    // 而 Array.prototype.concat 对 Set 不会展开（Set 没 Symbol.isConcatSpreadable），
    // 结果 `selectedCodes()` 实际返回 `[Set]`（数组里塞一个 Set 对象）；
    // JSON.stringify 后 `cards:[{}]`（空对象）→ 服务端 Jackson 期望 List<String>，
    // 报 "Cannot deserialize value of type `java.lang.String` from Object value"。
    // 重复牌（两张同点同花的 S5）天然支持：array 允许重复元素。
    private selected: string[] = [];
    private handNodes = new Map<number, Node>();

    private topLabel!: Label;
    private scoreLabel!: Label;
    private toastLabel!: Label;
    private timerLabel!: Label;
    private trickNode!: Node;
    private handNode!: Node;
    private btnNode!: Node;
    private settleNode: Node | null = null;
    private settleKey = '';                 // 面板内容 key：相同则不重建（避免重复弹入）

    // 四家持久铭牌（呼吸高亮必须持久节点，不能每次快照重建）
    private seatPlateG: Partial<Record<SeatName, Graphics>> = {};
    private seatLabelC: Partial<Record<SeatName, Label>> = {};
    private seatHaloOp: Partial<Record<SeatName, UIOpacity>> = {};
    private plateText: Partial<Record<SeatName, string>> = {};

    // 墩牌差量动画状态：座位 → 当前已展示的牌串 / 牌节点
    private trickShown = new Map<SeatName, string>();
    private trickNodes = new Map<SeatName, Node[]>();

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

    // 断线重连 UI（T-702）：首次连接不算"断线"，只刷顶栏；
    // 曾连上过再掉线 → 弹全屏遮罩 + 已等待秒数；重连成功 → 关遮罩 + 恢复引导
    private reconnectNode: Node | null = null;
    private reconnectLabel: Label | null = null;
    private wasOnline = false;      // 本次会话是否曾成功连上过
    private offlineSince = 0;       // 掉线时刻（遮罩显示已等待秒数）
    private hintMyTurnOnSnapshot = false; // 重连成功后，快照到达时提示"轮到你"

    /** 只有这些阶段的 banker 才是"本局"庄家（结算切庄后 DEALING/BIDDING 里已是下一局的） */
    private static readonly IN_GAME_PHASES = new Set(['BURYING', 'TRIBUTE', 'RETURN_TRIBUTE', 'PLAYING', 'SETTLING']);

    /** 有"轮到谁"语义、需要呼吸高亮的阶段 */
    private static readonly ACTION_PHASES = new Set(['BIDDING', 'BURYING', 'PLAYING', 'TRIBUTE', 'RETURN_TRIBUTE']);

    private static readonly ALL_SEATS: SeatName[] = ['NORTH', 'EAST', 'SOUTH', 'WEST'];

    /** 对家（搭档）座位 */
    private static readonly PARTNER: Record<SeatName, SeatName> = {
        NORTH: 'SOUTH', SOUTH: 'NORTH', EAST: 'WEST', WEST: 'EAST',
    };

    private static readonly PHASE_TEXT: Record<string, string> = {
        WAITING: '等待中', DEALING: '发牌中', BIDDING: '亮主', BURYING: '扣底',
        PLAYING: '出牌', TRIBUTE: '进贡', RETURN_TRIBUTE: '还贡',
        SETTLING: '结算中', SETTLE: '结算', ROUND_OVER: '整轮结束',
        AUTO: '超时托管',
    };

    // ==================== 座位旋转（我在下方） ====================

    /** 相对位次：0=我(下) 1=下家(右) 2=对家(上) 3=上家(左) */
    private relOf(seat: SeatName): number {
        return (TableUI.ALL_SEATS.indexOf(seat) - TableUI.ALL_SEATS.indexOf(this.mySeat) + 4) % 4;
    }

    /** 铭牌局部坐标（trickNode 子空间，trickNode 位于 (0,80)） */
    private plateLocal(seat: SeatName): Vec3 {
        const slots = [new Vec3(0, -240, 0), new Vec3(430, -20, 0), new Vec3(0, 125, 0), new Vec3(-430, -20, 0)];
        return slots[this.relOf(seat)];
    }

    /** 该座位墩牌行的局部坐标（牌行中心） */
    private trickLocal(seat: SeatName): Vec3 {
        const slots = [new Vec3(0, -195, 0), new Vec3(430, 25, 0), new Vec3(0, 80, 0), new Vec3(-430, 25, 0)];
        return slots[this.relOf(seat)];
    }

    start(): void {
        this.buildLayout();
        this.net = new NetClient(this.serverUrl);
        this.net.onJoined(j => this.onJoinedMsg(j));
        this.net.onSnapshot(s => this.onSnapshotMsg(s));
        this.net.onEvent(e => this.onEventToast(e));
        this.net.onError(r => this.showToast(`⚠ ${r}`, true));
        this.net.onStateChange(ok => this.onNetState(ok));
        this.net.join(this.roomId, this.playerId, this.mySeat);
    }

    onDestroy(): void {
        this.net?.close();
    }

    // ==================== 断线重连 UI（T-702） ====================

    private onJoinedMsg(j: JoinedMsg): void {
        if (j.reconnect) {
            // 重连恢复：等快照到达后再给"轮到你"引导（此刻还没有牌局状态）
            this.hintMyTurnOnSnapshot = true;
            this.showToast('已重新连接，牌局已恢复');
        } else {
            this.showToast(`已入座 ${this.mySeat}`);
        }
    }

    private onSnapshotMsg(s: SnapshotMsgDown): void {
        // 【必修】不再清空 selected：selected 存的是牌代码（不是下标），快照变化不影响。
        // 出牌成功后该牌代码不在新 yourHand 里，自然失效，无需手动清。
        this.snap = s;
        this.pruneSelected();
        this.renderAll();
        // 重连恢复引导：快照对齐后，若正轮到我行动则明确提示
        if (this.hintMyTurnOnSnapshot) {
            this.hintMyTurnOnSnapshot = false;
            if (TableUI.ACTION_PHASES.has(s.phase) && s.turn === this.mySeat) {
                this.showToast(`轮到你${TableUI.PHASE_TEXT[s.phase] ?? '行动'}了`, true);
            }
        }
    }

    private onNetState(ok: boolean): void {
        this.renderTop();
        if (ok) {
            if (this.wasOnline) {
                this.hideReconnectOverlay(); // 断线重连成功
            }
            this.wasOnline = true;
            return;
        }
        if (this.wasOnline) {
            // 曾连上过再掉线才是"断线"（首次连接失败只刷顶栏，不弹遮罩）
            this.offlineSince = Date.now();
            this.showReconnectOverlay();
        }
    }

    private showReconnectOverlay(): void {
        if (!this.reconnectNode) {
            // 全屏半透明遮罩（添加顺序最后 = 最顶层，盖住牌桌但透出牌局轮廓）
            const mask = new Node('reconnect-mask');
            mask.layer = 1 << 25;
            mask.addComponent(UITransform).setContentSize(1280, 720);
            const g = mask.addComponent(Graphics);
            g.fillColor = new Color(0, 0, 0, 165);
            g.fillRect(-640, -360, 1280, 720);
            const title = this.makeLabelNode('', 34, new Color(255, 210, 120, 255));
            title.setPosition(0, 30, 0);
            mask.addChild(title);
            this.reconnectLabel = title.getComponent(Label)!;
            const sub = this.makeLabelNode(
                '牌局仍在进行，恢复连接后自动回到座位', 16, new Color(200, 200, 200, 255));
            sub.setPosition(0, -16, 0);
            mask.addChild(sub);
            this.node.addChild(mask);
            this.reconnectNode = mask;
        }
        this.reconnectNode.active = true;
        this.updateReconnectText();
    }

    private hideReconnectOverlay(): void {
        if (this.reconnectNode) {
            this.reconnectNode.active = false;
        }
    }

    /** 遮罩文案：动画省略号 + 已等待秒数（update 里刷新） */
    private updateReconnectText(): void {
        if (!this.reconnectLabel) return;
        const dots = '.'.repeat(1 + Math.floor(Date.now() / 450) % 3);
        const waited = Math.max(0, Math.floor((Date.now() - this.offlineSince) / 1000));
        this.reconnectLabel.string = `网络断开，正在重连${dots}（已等待 ${waited} 秒）`;
    }

    // ==================== 布局骨架 ====================

    private buildLayout(): void {
        // 牌桌绒布背景（最先添加 = 最底层；纯 Graphics 程序化，无美术资源）
        const felt = new Node('felt');
        felt.layer = 1 << 25;
        felt.addComponent(UITransform).setContentSize(1280, 720);
        const g = felt.addComponent(Graphics);
        // 木沿
        g.roundRect(-634, -354, 1268, 708, 24);
        g.lineWidth = 8;
        g.strokeColor = new Color(96, 72, 38, 255);
        g.stroke();
        // 桌面基底（暗角）
        g.roundRect(-629, -349, 1258, 698, 20);
        g.fillColor = new Color(9, 42, 31, 255);
        g.fill();
        // 主桌面
        g.roundRect(-560, -282, 1120, 564, 28);
        g.fillColor = new Color(13, 54, 40, 255);
        g.fill();
        // 中心提亮椭圆（模拟灯光照射）
        g.ellipse(0, 0, 470, 235);
        g.fillColor = new Color(16, 61, 45, 255);
        g.fill();
        // 内圈金线
        g.roundRect(-560, -282, 1120, 564, 28);
        g.lineWidth = 1.5;
        g.strokeColor = new Color(200, 165, 70, 60);
        g.stroke();
        this.node.addChild(felt);

        // 顶部信息栏（行 1：局/阶段/级数/主牌/庄/轮）—— 绒布上用浅色字
        const top = this.makeLabelNode('连接中…', 18, new Color(228, 238, 228, 255));
        top.setPosition(0, 320, 0);
        this.node.addChild(top);
        this.topLabel = top.getComponent(Label)!;

        // 顶部信息栏（行 2：本墩捡分 + 各家分，醒目色）
        const score = this.makeLabelNode('', 20, new Color(255, 130, 55, 255));
        score.setPosition(0, 288, 0);
        this.node.addChild(score);
        this.scoreLabel = score.getComponent(Label)!;

        // Toast（事件提示，叠在信息栏下方）
        const toast = this.makeLabelNode('', 16, new Color(255, 175, 85, 255));
        toast.setPosition(0, 258, 0);
        this.node.addChild(toast);
        this.toastLabel = toast.getComponent(Label)!;

        // 出牌倒计时（轮到我时显示在按钮区右侧）
        const timer = this.makeLabelNode('', 32, new Color(235, 145, 25, 255));
        timer.setPosition(240, -30, 0);
        this.node.addChild(timer);
        this.timerLabel = timer.getComponent(Label)!;

        // 中部：四家持久铭牌 + 墩牌（差量动画）
        this.trickNode = new Node('trick');
        this.trickNode.layer = 1 << 25;
        this.trickNode.setPosition(0, 80, 0);
        this.node.addChild(this.trickNode);
        for (const seat of TableUI.ALL_SEATS) {
            this.trickNode.addChild(this.makeSeatPlate(seat));
        }

        // 下部操作按钮（y=-30，躲开发调试面板大约 y∈[-100,-200] 区域）
        this.btnNode = new Node('buttons');
        this.btnNode.layer = 1 << 25;
        this.btnNode.setPosition(0, -30, 0);
        this.node.addChild(this.btnNode);

        // 底部手牌（y=-240）
        this.handNode = new Node('hand');
        this.handNode.layer = 1 << 25;
        this.handNode.setPosition(0, -240, 0);
        this.node.addChild(this.handNode);
    }

    /** 一个座位的持久铭牌：呼吸光环 + 牌面底板 + 文字 */
    private makeSeatPlate(seat: SeatName): Node {
        const root = new Node(`seat_${seat}`);
        root.layer = 1 << 25;
        root.setPosition(this.plateLocal(seat));

        // 呼吸光环（双层描边模拟辉光，UIOpacity 脉动）
        const halo = new Node('halo');
        halo.layer = 1 << 25;
        const hg = halo.addComponent(Graphics);
        hg.lineWidth = 9;
        hg.strokeColor = new Color(255, 205, 70, 60);
        hg.roundRect(-85, -25, 170, 50, 17);
        hg.stroke();
        hg.lineWidth = 3;
        hg.strokeColor = new Color(255, 205, 70, 255);
        hg.roundRect(-85, -25, 170, 50, 17);
        hg.stroke();
        const hop = halo.addComponent(UIOpacity);
        hop.opacity = 0;
        root.addChild(halo);
        this.seatHaloOp[seat] = hop;

        // 底板（内容变化时重绘）
        const plate = new Node('plate');
        plate.layer = 1 << 25;
        this.seatPlateG[seat] = plate.addComponent(Graphics);
        root.addChild(plate);

        // 文字
        const txt = new Node('txt');
        txt.layer = 1 << 25;
        txt.addComponent(UITransform).setContentSize(150, 34);
        const l = txt.addComponent(Label);
        l.string = '';
        l.fontSize = 15;
        l.lineHeight = 20;
        l.color = new Color(215, 225, 218, 255);
        l.useSystemFont = true;
        l.horizontalAlign = Label.HorizontalAlign.CENTER;
        l.verticalAlign = Label.VerticalAlign.CENTER;
        root.addChild(txt);
        this.seatLabelC[seat] = l;

        this.drawPlate(seat, seat === this.mySeat ? '我' : seat, false);
        return root;
    }

    /** 重绘铭牌底板 + 文字（庄家金框金字） */
    private drawPlate(seat: SeatName, text: string, banker: boolean): void {
        const g = this.seatPlateG[seat];
        const l = this.seatLabelC[seat];
        if (!g || !l) return;
        g.clear();
        g.roundRect(-75, -17, 150, 34, 17);
        g.fillColor = banker ? new Color(66, 50, 12, 235) : new Color(14, 25, 21, 220);
        g.fill();
        g.lineWidth = banker ? 2.5 : 1;
        g.strokeColor = banker ? new Color(235, 180, 60, 255) : new Color(255, 255, 255, 45);
        g.stroke();
        l.string = text;
        l.color = banker ? new Color(255, 215, 110, 255)
            : (seat === this.mySeat ? new Color(180, 240, 190, 255) : new Color(215, 225, 218, 255));
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
        // 系统字体：Bitmap font 可能缺字（如"庄"）等中文不可靠
        l.useSystemFont = true;
        l.horizontalAlign = Label.HorizontalAlign.CENTER;
        l.verticalAlign = Label.VerticalAlign.CENTER;
        return n;
    }

    /** 立体按钮：底唇模拟厚度，按压时整体下沉 4px（底唇被遮住 → "按下"） */
    private makeButton(text: string, x: number, cb: () => void): Node {
        const n = new Node(`btn_${text}`);
        n.layer = 1 << 25;
        const ut = n.addComponent(UITransform);
        ut.setContentSize(140, 52);
        const g = n.addComponent(Graphics);
        // 底唇（厚度）
        g.roundRect(-70, -26, 140, 50, 9);
        g.fillColor = new Color(36, 58, 104, 255);
        g.fill();
        // 按钮面
        g.roundRect(-70, -22, 140, 48, 9);
        g.fillColor = new Color(72, 112, 192, 255);
        g.fill();
        g.lineWidth = 1;
        g.strokeColor = new Color(150, 190, 255, 90);
        g.stroke();
        // 顶面高光条
        g.roundRect(-64, 8, 128, 12, 6);
        g.fillColor = new Color(255, 255, 255, 28);
        g.fill();

        // Label 独立子节点挂在按钮下，填满整个按钮区域
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
        // 【真机必修】回调挂在 TOUCH_START：真机手指轻微滑动会把 TOUCH_END 变成 TOUCH_CANCEL，
        // 按钮会表现为"按了没反应"。按下即触发 + 松手回弹，是移动端按钮的标准做法。
        let fired = false;
        n.on(Node.EventType.TOUCH_START, () => {
            tween(n).to(0.06, { position: new Vec3(x, -4, 0) }, { easing: 'quadOut' }).start();
            if (!fired) { fired = true; cb(); }
        });
        n.on(Node.EventType.TOUCH_END, () => {
            tween(n).to(0.12, { position: new Vec3(x, 0, 0) }, { easing: 'backOut' }).start();
            fired = false;
        });
        n.on(Node.EventType.TOUCH_CANCEL, () => {
            tween(n).to(0.12, { position: new Vec3(x, 0, 0) }, { easing: 'backOut' }).start();
        });
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
        // 重连遮罩文案刷新（动画省略号 + 已等待秒数）
        if (this.reconnectNode && this.reconnectNode.active) {
            this.updateReconnectText();
        }
        // 结算面板到期自动收起（出锅常驻除外）
        if (this.settleNode && Date.now() >= this.settleVisibleUntil
            && this.snap?.phase !== 'ROUND_OVER') {
            this.destroySettle();
        }
        // 轮次呼吸高亮：当前行动者铭牌金圈脉动
        const s = this.snap;
        const active = s && TableUI.ACTION_PHASES.has(s.phase) ? s.turn : null;
        const pulse = 0.5 + 0.5 * Math.sin(Date.now() / 1000 * 5);
        for (const seat of TableUI.ALL_SEATS) {
            const op = this.seatHaloOp[seat];
            if (!op) continue;
            op.opacity = seat === active ? 80 + 150 * pulse : 0;
        }
        if (!this.timerDeadline) {
            if (this.timerLabel && this.timerLabel.string !== '') this.timerLabel.string = '';
            return;
        }
        const left = (this.timerDeadline - Date.now()) / 1000;
        if (left > 0) {
            const sec = Math.ceil(left);
            this.timerLabel.string = `剩 ${sec}s`;
            this.timerLabel.color = sec <= 5
                ? new Color(255, 70, 70, 255)
                : new Color(235, 145, 25, 255);
        } else {
            this.timerLabel.string = '已超时';
            this.timerLabel.color = new Color(200, 90, 90, 255);
        }
    }

    // ==================== 结算页（T-606） ====================

    /** 结算面板：SETTLE 事件后 8s 内展示（快照紧跟事件到达）；出锅（ROUND_OVER）常驻 */
    private renderSettlement(): void {
        const s = this.snap;
        if (!s || !s.settlement) {
            if (this.settleNode) this.destroySettle();
            return;
        }
        const roundOver = s.phase === 'ROUND_OVER';
        if (!roundOver && Date.now() >= this.settleVisibleUntil) {
            if (this.settleNode) this.destroySettle();
            return;
        }
        const st = s.settlement;
        // 内容没变且面板还活着：不重建（否则每张快照都重新弹入一次）
        const key = `${this.settleGameNumber}|${st.bankerScore}|${st.attackerScore}|${st.attackerTakesBank}|${roundOver}`;
        if (this.settleNode && key === this.settleKey) return;
        this.destroySettle();
        this.settleKey = key;

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

        // 弹入：缩放 0.72→1 backOut + 淡入
        overlay.setScale(0.72, 0.72, 1);
        const op = overlay.addComponent(UIOpacity);
        op.opacity = 0;
        tween(overlay).to(0.25, { scale: new Vec3(1, 1, 1) }, { easing: 'backOut' }).start();
        tween(op).to(0.2, { opacity: 255 }).start();
    }

    private destroySettle(): void {
        if (!this.settleNode) return;
        const op = this.settleNode.getComponent(UIOpacity);
        if (op) Tween.stopAllByTarget(op);
        Tween.stopAllByTarget(this.settleNode);
        this.settleNode.destroy();
        this.settleNode = null;
        this.settleKey = '';
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
        // 诊断行（真机适配排查用）：显示实际可见区域与手牌张数。
        // 若"视口"高度明显小于 720，说明屏幕比例导致上下内容被裁（手牌会掉出屏幕）。
        const vs = view.getVisibleSize();
        this.topLabel.string =
            `第${s.gameNumber}局 ${phase} | 级${s.level} 主${trump} | 庄${s.banker ?? '-'} 轮${s.turn ?? '-'}`
            + ` | 手牌${(s.yourHand ?? []).length} 视口${Math.round(vs.width)}x${Math.round(vs.height)}`
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

    // ==================== 墩牌（差量动画） ====================

    private renderTrick(): void {
        const s = this.snap;
        if (!s) return;

        // 铭牌：座位名 + 余牌数 + 庄标记（结算切庄后沿用本局庄）
        const bankerNow = TableUI.IN_GAME_PHASES.has(s.phase) ? s.banker : this.preSettleBanker;
        for (const seat of TableUI.ALL_SEATS) {
            const cnt = s.hands?.[seat];
            const name = seat === this.mySeat ? '我' : seat;
            const txt = `${bankerNow === seat ? '庄·' : ''}${name}${cnt != null ? `(${cnt})` : ''}`;
            if (txt !== this.plateText[seat]) {
                this.plateText[seat] = txt;
                this.drawPlate(seat, txt, bankerNow === seat);
            }
        }

        // 当前墩出牌：差量比较，新增的飞入，消失的清掉
        const plays: Array<{ seat: SeatName; cards: string[] }> = [];
        if (s.trick?.plays && s.trick.plays.length > 0) {
            for (const p of s.trick.plays) {
                plays.push({ seat: p.seat as SeatName, cards: p.cards });
            }
        } else if (s.trick?.leader && s.trick.leadCards) {
            plays.push({ seat: s.trick.leader as SeatName, cards: s.trick.leadCards });
        }

        if (plays.length === 0) {
            // 墩被收走：整墩向赢家（= 下一轮领出者）方向收拢
            if (this.trickShown.size > 0) this.collectTrick(s.turn ?? null);
            this.trickShown.clear();
            return;
        }
        for (const seat of [...this.trickShown.keys()]) {
            if (!plays.some(p => p.seat === seat)) {
                this.destroySeatCards(seat);
                this.trickShown.delete(seat);
            }
        }
        for (const p of plays) {
            const key = p.cards.join('.');
            if (this.trickShown.get(p.seat) === key) continue;
            this.destroySeatCards(p.seat);
            this.flyIn(p.seat, p.cards);
            this.trickShown.set(p.seat, key);
        }
    }

    /** 出牌飞入：我的牌从手牌区放大态起飞落定；别人的牌从其铭牌处飞出 */
    private flyIn(seat: SeatName, cards: string[]): void {
        const base = this.trickLocal(seat);
        const spacing = 22;
        const totalW = (cards.length - 1) * spacing;
        const mine = seat === this.mySeat;
        const plate = this.plateLocal(seat);
        const nodes: Node[] = [];
        for (let i = 0; i < cards.length; i++) {
            const c = createMiniCardNode(cards[i]);
            const dst = new Vec3(base.x - totalW / 2 + i * spacing, base.y, 0);
            const from = mine ? new Vec3(dst.x, -330, 0) : new Vec3(plate.x, plate.y, 0);
            c.setPosition(from);
            if (mine) c.setScale(1.5, 1.5, 1);
            this.trickNode.addChild(c);
            const delay = i * 0.06;   // 多张牌阶梯起飞
            tween(c).delay(delay).to(0.18, { position: dst }, { easing: 'quadOut' }).start();
            if (mine) {
                tween(c).delay(delay).to(0.18, { scale: new Vec3(1, 1, 1) }, { easing: 'quadOut' }).start();
            }
            nodes.push(c);
        }
        this.trickNodes.set(seat, nodes);
    }

    /** 整墩牌向赢家方向收拢消失（阶梯 0.04s） */
    private collectTrick(winner: SeatName | null): void {
        const target = winner ? this.plateLocal(winner) : new Vec3(0, 40, 0);
        let i = 0;
        for (const [, nodes] of this.trickNodes) {
            for (const nd of nodes) {
                const delay = (i++) * 0.04;
                const op = nd.getComponent(UIOpacity) ?? nd.addComponent(UIOpacity);
                tween(nd).delay(delay)
                    .to(0.22, { position: target, scale: new Vec3(0.35, 0.35, 1) }, { easing: 'quadIn' })
                    .call(() => nd.destroy())
                    .start();
                tween(op).delay(delay).to(0.22, { opacity: 0 }).start();
            }
        }
        this.trickNodes.clear();
    }

    private destroySeatCards(seat: SeatName): void {
        const nodes = this.trickNodes.get(seat);
        if (!nodes) return;
        for (const n of nodes) {
            const op = n.getComponent(UIOpacity);
            if (op) Tween.stopAllByTarget(op);
            Tween.stopAllByTarget(n);
            n.destroy();
        }
        this.trickNodes.delete(seat);
    }

    // ==================== 手牌 ====================

    private renderHand(): void {
        // 旧节点可能还有按压回弹动画在跑：先停 tween 再销毁，避免操作已销毁节点
        for (const n of this.handNodes.values()) Tween.stopAllByTarget(n);
        this.handNode.removeAllChildren();
        this.handNodes.clear();
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
            const cardCode = hand[idx];
            const card = createCardNode(cardCode);
            // 用复合身份 "code#occurrence" 判断选中（不是按下标、也不是纯代码）：
            // 重画整手时保留选中视觉，且能区分三副牌里的重复代码
            const key = this.cardKey(hand, idx);
            const selected = this.selected.indexOf(key) >= 0;
            card.setPosition(x, selected ? 18 : 0, 0);
            if (selected) drawCardBg(card, true);
            // 牌面 56 宽但牌多时间距只有 ~40 → 互相重叠。命中区必须缩到 spacing 宽，
            // 否则点左侧露出部分会命中左边那张（视觉错位）。
            // 【真机必修】子节点（点数/花色 Label）各自带 56 宽 UITransform，同样参与命中测试；
            // 只缩父节点等于没缩，点到的仍是相邻那张 —— 子节点必须一起收缩。
            const hitW = Math.min(spacing, cardW);
            card.getComponent(UITransform)!.setContentSize(hitW, 80);
            for (const child of card.children) {
                const cut = child.getComponent(UITransform);
                if (cut) cut.setContentSize(hitW, cut.contentSize.height);
            }
            // 点击手感：按下缩、松手回弹
            card.on(Node.EventType.TOUCH_START, () => {
                tween(card).to(0.08, { scale: new Vec3(0.94, 0.94, 1) }, { easing: 'quadOut' }).start();
                // 【真机必修】按下即选中：真机手指轻微滑动会把 TOUCH_END 变成 TOUCH_CANCEL，
                // 选中逻辑挂在 TOUCH_END 上会表现为"点了没反应"。
                // 模拟器用鼠标点击无抖动，所以这个问题在模拟器上根本测不出来。
                // 传复合身份（不是下标、也不是纯代码）：快照重排不影响，重复代码也能区分。
                this.toggleSelect(key, card);
            });
            card.on(Node.EventType.TOUCH_END, () => {
                tween(card).to(0.12, { scale: new Vec3(1, 1, 1) }, { easing: 'backOut' }).start();
            });
            card.on(Node.EventType.TOUCH_CANCEL, () => {
                tween(card).to(0.12, { scale: new Vec3(1, 1, 1) }, { easing: 'backOut' }).start();
            });
            this.handNode.addChild(card);
            this.handNodes.set(idx, card);
            x += spacing;
        }
    }

    /** 选中/取消选中一张牌：只动这一张节点（弹起 + 金框），不重建整手。
     *  参数 key 是复合身份 "code#occurrence"（不是下标、也不是纯代码）：
     *   - 不是下标：服务端每次快照都重排手牌（定主前后规则还不同），下标会整体错位
     *   - 不是纯代码：三副牌同点同花有 3 张（都是 "H9"），纯代码无法区分，
     *     点第 2 张会被判定成取消第 1 张 → "一次只能选一张"
     *  用 string[] 不用 Set：避免 Cocos 把 `[...set]` 编译成 `[].concat(set)`，
     *  后者对 Set 不展开 → selectedCodes() 返回 [Set] → JSON 序列化出 `cards:[{}]`。 */
    private toggleSelect(key: string, card: Node): void {
        const i = this.selected.indexOf(key);
        const on = i >= 0;
        if (on) this.selected.splice(i, 1);
        else this.selected.push(key);
        drawCardBg(card, !on);
        tween(card).to(0.12, { position: new Vec3(card.position.x, !on ? 18 : 0, 0) },
            { easing: 'backOut' }).start();
        // 选中变化会影响按钮文案（"出牌：先选牌" → "出牌"）
        this.renderButtons();
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

    /**
     * 【真机必修】手牌里每张牌的**稳定身份** = `牌代码#第几次出现`（如 "H9#0" / "H9#1"）。
     *
     * 为什么不能用纯牌代码：三副牌里同点同花的牌有 3 张（都是 "H9"），
     * 若只用代码做身份，选中第 2 张时 indexOf 会命中第 1 张 → 判定成"取消选中"
     * → 表现为"一次只能选一张，点第二张就把第一张取消了"。
     *
     * 为什么不能用纯下标：服务端每次快照都重新排序手牌（定主前后排序规则还不同：
     * 定主前按 rank、定主后按 CardComparator(trump)），下标会整体错位。
     *
     * 复合身份两者兼得：手牌集合不变时身份稳定；重复代码靠 occurrence 区分。
     * 发给服务端时用 selectedCodes() 剥掉 "#n" 后缀，恢复成纯牌代码 multiset。
     */
    /**
     * 保险：剔除 selected 里已不在手牌中的复合身份。
     * 出牌成功后那些牌从 yourHand 消失，对应的 "code#occurrence" 自然失效；
     * 若不清掉，下次 selectedCodes() 会把已打出去的牌再发一遍。
     */
    private pruneSelected(): void {
        if (this.selected.length === 0) return;
        const hand = this.snap?.yourHand ?? [];
        // 统计每个牌代码在手牌里的可用张数
        const avail = new Map<string, number>();
        for (const c of hand) avail.set(c, (avail.get(c) ?? 0) + 1);
        const kept: string[] = [];
        for (const k of this.selected) {
            const p = k.indexOf('#');
            if (p < 0) continue;                       // 非法身份，丢弃
            const code = k.slice(0, p);
            const occ = parseInt(k.slice(p + 1), 10);
            if (Number.isFinite(occ) && (avail.get(code) ?? 0) > occ) kept.push(k);
        }
        this.selected = kept;
    }

    private cardKey(hand: string[], idx: number): string {
        const code = hand[idx];
        let occ = 0;
        for (let i = 0; i < idx; i++) {
            if (hand[i] === code) occ++;
        }
        return `${code}#${occ}`;
    }

    private selectedCodes(): string[] {
        // selected 存的是 "code#occurrence" 复合身份，发给服务端前剥掉 "#n" 后缀。
        // 结果形如 ["H9","H9"]（两张 H9）—— 后端 Cards 按值 equals，multiset 正确处理。
        return this.selected.map(k => {
            const p = k.indexOf('#');
            return p >= 0 ? k.slice(0, p) : k;
        });
    }

    // ==================== 阶段操作按钮 ====================

    private renderButtons(): void {
        // 旧按钮可能有按压动画在跑：先停 tween 再清
        this.btnNode.children.forEach(c => Tween.stopAllByTarget(c));
        this.btnNode.removeAllChildren();
        const s = this.snap;
        if (!s) return;
        const myTurn = s.turn === this.mySeat;
        switch (s.phase) {
            case 'BIDDING': {
                if (this.selected.length > 0) {
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
                    const need = 6 - this.selected.length;
                    const txt = this.selected.length > 0
                        ? `扣底(还差${Math.max(need, 0)}张)`
                        : '扣底：选6张';
                    this.makeButton(txt, 0, () => {
                        if (this.selected.length !== 6) { this.showToast('扣底需恰好 6 张', true); return; }
                        this.net?.sendCmd('BURY', { cards: this.selectedCodes() });
                    });
                }
                break;
            }
            case 'PLAYING': {
                if (myTurn) {
                    this.makeButton(this.selected.length > 0 ? '出牌' : '出牌：先选牌', 0, () => {
                        if (this.selected.length === 0) { this.showToast('先点选要出的牌', true); return; }
                        this.net?.sendCmd('PLAY', { cards: this.selectedCodes() });
                        this.selected.length = 0;
                    });
                }
                break;
            }
            case 'TRIBUTE': {
                const mine = s.pendingTributes?.[this.mySeat];
                if (mine) {
                    this.makeButton('进贡', 0, () => {
                        if (this.selected.length === 0) { this.showToast('先选要贡的牌', true); return; }
                        this.net?.sendCmd('TRIBUTE', { cards: this.selectedCodes(), payee: mine.receiver });
                        this.selected.length = 0;
                    });
                }
                break;
            }
            case 'RETURN_TRIBUTE': {
                const payer = Object.entries(s.pendingTributes ?? {})
                    .find(([, v]) => v.receiver === this.mySeat)?.[0];
                if (payer) {
                    this.makeButton('还贡', 0, () => {
                        if (this.selected.length === 0) { this.showToast('先选要还的牌', true); return; }
                        this.net?.sendCmd('RETURN_TRIBUTE', { cards: this.selectedCodes(), payee: payer });
                        this.selected.length = 0;
                    });
                }
                break;
            }
        }

        // 【常驻】新局按钮：战斗服是长跑的，牌局一直被 bot 推进，
        // 玩家进来时往往已经打了一半（"预览时牌局已进行一段"）。
        // 放最右侧 x=420，与其他阶段按钮（x 范围 -240~240）不重叠。
        this.makeButton('新局', 420, () => {
            this.selected.length = 0;
            this.net?.sendCmd('NEWGAME');
        });
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
        this.toastLabel.color = warn ? new Color(255, 95, 95, 255) : new Color(255, 175, 85, 255);
    }
}
