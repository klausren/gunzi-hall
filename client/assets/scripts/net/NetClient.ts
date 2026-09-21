import type {
    CmdMsg, CmdType, EventMsg, JoinedMsg, SeatName, ServerMsg, SnapshotMsgDown,
} from './Protocol';

/**
 * 战斗服 WebSocket 客户端封装（T-503）。
 *
 * 职责：
 *  - 连接管理：心跳（JSON ping，30s 间隔；2 次无 pong 判死）+ 指数退避自动重连
 *  - 会话令牌：join 签发后自动附带在 cmd/snapshot 上；token 失效（服务重启/过期）
 *    自动重新 join（断线重连恢复手牌由服务端保证）
 *  - 消息分发：onSnapshot / onEvent / onJoined / onError 回调
 *
 * 环境兼容：标准 WebSocket API（微信小游戏经 Cocos Creator 适配层提供全局 WebSocket）。
 *
 * 用法（Cocos 组件内）：
 *   const net = new NetClient('ws://192.168.x.x:8080/ws');
 *   net.onSnapshot(s => this.renderTable(s));
 *   net.onEvent(e => this.playAnim(e));
 *   net.join(1001, myPlayerId, 'NORTH');
 *   ...
 *   net.sendCmd('PLAY', { cards: ['H5'] });
 */
export class NetClient {

    private ws: WebSocket | null = null;
    private url: string;

    // ---- 会话状态 ----
    private token: string | null = null;
    private roomId = -1;
    private playerId = -1;
    private seat: SeatName | null = null;

    // ---- 心跳 ----
    private heartbeatTimer: number | null = null;
    private missedPong = 0;
    private static readonly HEARTBEAT_MS = 30_000;

    // ---- 重连 ----
    private reconnectTimer: number | null = null;
    private reconnectAttempts = 0;
    private static readonly MAX_RECONNECT_MS = 15_000; // 退避上限 15s
    private manuallyClosed = false;

    // ---- 建连自检（平台无关兜底） ----
    /**
     * 建连超时自检定时器。
     *
     * <p>【为什么必须有】浏览器里连接失败一定会触发 onerror + onclose，所以"靠 onclose
     * 收尾"一直够用。但小游戏平台不是：`wx.connectSocket` 在域名不合法、网关丢包、
     * 超时等情况下**可能只回调 onError，既不回调 onOpen 也不回调 onClose**
     * （小游戏适配层把平台事件原样透传，见构建产物里的 web-adapter）。
     * 此时连接对象会永远停在 CONNECTING，而 UI 只能靠 stateHandler 事件更新——
     * 于是顶栏永远停在构造时的"连接中…"（2026-09-20 真机实测就是这个症状，
     * 玩家和排错的人都只能干看着，连"连的是哪台机器"都看不到）。
     * 这里用一个与平台无关的计时器兜底：到时仍未连上就主动判定失败、把原因交给 UI。
     */
    private connectTimer: number | null = null;
    private static readonly CONNECT_TIMEOUT_MS = 8_000;

    /** 已发起的连接次数（含重连），顶栏用来显示"已重试 N 次" */
    private attemptCount = 0;

    /** 最近一次可展示的连接失败原因（来自 onerror / 构造异常 / 超时自检） */
    private lastErrorDetail = '';

    // ---- 回调 ----
    private snapshotHandler: ((s: SnapshotMsgDown) => void) | null = null;
    private eventHandler: ((e: EventMsg) => void) | null = null;
    private joinedHandler: ((j: JoinedMsg) => void) | null = null;
    private errorHandler: ((reason: string) => void) | null = null;
    private stateHandler: ((online: boolean) => void) | null = null;

    constructor(url: string) {
        this.url = url;
    }

    // ==================== 对外 API ====================

    onSnapshot(h: (s: SnapshotMsgDown) => void) { this.snapshotHandler = h; }
    onEvent(h: (e: EventMsg) => void) { this.eventHandler = h; }
    onJoined(h: (j: JoinedMsg) => void) { this.joinedHandler = h; }
    onError(h: (reason: string) => void) { this.errorHandler = h; }
    onStateChange(h: (online: boolean) => void) { this.stateHandler = h; }

    get online(): boolean {
        return this.ws != null && this.ws.readyState === WebSocket.OPEN;
    }

    /** 已发起的连接次数（含重连）。顶栏显示"已重试 N 次"，让真机上一眼看出在反复重连 */
    get attempts(): number { return this.attemptCount; }

    /**
     * 最近一次连接失败原因（无失败时为空串）。
     *
     * <p>平台只给 onerror 不给 onclose 时，这是 UI 唯一能拿到的线索 —— 不能丢。
     */
    get lastError(): string { return this.lastErrorDetail; }

    /** 建连并入座（重连场景同房同座位再 join，服务端自动恢复快照） */
    join(roomId: number, playerId: number, seat: SeatName): void {
        this.roomId = roomId;
        this.playerId = playerId;
        this.seat = seat;
        this.manuallyClosed = false;
        this.connect();
    }

    /** 发送游戏命令（自动附带 token；playerId 由服务端从 token 解析） */
    sendCmd(type: CmdType, payload: Partial<Omit<CmdMsg, 'op' | 'type' | 'roomId' | 'token'>> = {}): void {
        if (!this.token) {
            this.errorHandler?.('未加入房间（无 token），无法发送命令');
            return;
        }
        this.rawSend({
            op: 'cmd', type, roomId: this.roomId, token: this.token, ...payload,
        });
    }

    /** 主动请求全量快照（UI 状态错乱时自救用） */
    requestSnapshot(): void {
        if (!this.token) return;
        this.rawSend({ op: 'snapshot', roomId: this.roomId, token: this.token });
    }

    /** 手动断开（退出房间），不再自动重连 */
    close(): void {
        this.manuallyClosed = true;
        this.stopHeartbeat();
        this.stopReconnect();
        this.stopConnectTimer();
        this.ws?.close();
        this.ws = null;
        this.stateHandler?.(false);
    }

    /**
     * 立即重连（UI"重试连接"用）：清空指数退避并强制重建连接。
     * 用于开发期连不上时的人工重试——否则退避拉到 15s，玩家只能干等。
     */
    reconnectNow(): void {
        this.stopHeartbeat();
        this.stopReconnect();
        this.stopConnectTimer();
        this.reconnectAttempts = 0;
        this.manuallyClosed = false;
        const old = this.ws;
        this.ws = null;              // 先摘掉引用：旧连接的 onclose 会因 ws !== this.ws 而自我忽略
        try { old?.close(); } catch { /* ignore */ }
        this.connect();
    }

    // ==================== 连接与心跳 ====================

    private connect(): void {
        this.stopHeartbeat();
        this.stopReconnect();
        this.stopConnectTimer();
        this.attemptCount++;
        let ws: WebSocket;
        try {
            ws = new WebSocket(this.url);
        } catch (e) {
            // 构造阶段就失败（URL 非法等）：原实现只排重连、不通知 UI，
            // 顶栏会永远停在"连接中…"。原因必须交出去，否则真机无从排查。
            this.failConnect(`无法创建连接：${(e as Error)?.message ?? String(e)}`);
            return;
        }
        this.ws = ws;
        // 建连自检：到时仍未 OPEN 就自己判失败（成因见 connectTimer 字段注释）
        this.connectTimer = setTimeout(() => {
            if (this.ws !== ws || ws.readyState === WebSocket.OPEN) return;
            this.failConnect(`连接超时：${NetClient.CONNECT_TIMEOUT_MS / 1000} 秒内未建立`
                + `（readyState=${ws.readyState}）`);
        }, NetClient.CONNECT_TIMEOUT_MS) as unknown as number;
        // 所有回调都先校验"我是不是当前这条连接"：避免旧连接的 onclose
        // 触发新连接的 scheduleReconnect（双连接 / 状态被旧连接回滚）。
        ws.onopen = () => {
            if (this.ws !== ws) return;
            this.stopConnectTimer();     // 已连上：撤掉建连自检
            this.reconnectAttempts = 0;
            this.attemptCount = 0;       // 连上即清零，顶栏不再显示"已重试 N 次"
            this.lastErrorDetail = '';
            this.missedPong = 0;
            this.stateHandler?.(true);
            // 连上即（重）入座：服务端凭 roomId+playerId 识别重连，发回快照与新 token
            this.rawSend({
                op: 'join', roomId: this.roomId, playerId: this.playerId, seat: this.seat!,
            });
            this.startHeartbeat();
        };
        ws.onmessage = (ev: MessageEvent) => {
            if (this.ws !== ws) return;
            this.handleMessage(decodeMessageData(ev.data));
        };
        ws.onclose = () => {
            if (this.ws !== ws) return;
            this.stopConnectTimer();
            this.stateHandler?.(false);
            this.stopHeartbeat();
            this.token = null; // 连接已断，token 随新连接重新签发
            this.ws = null;
            if (!this.manuallyClosed) {
                this.scheduleReconnect();
            }
        };
        ws.onerror = (ev?: unknown) => {
            if (this.ws !== ws) return;
            // 【不能留空】原实现的注释是"onclose 会跟着触发"——这在浏览器成立，
            // 在小游戏不成立：wx.connectSocket 遇到域名不合法 / 连接超时 / 网关丢包时
            // **只回调 onError**，既不回调 onOpen 也不回调 onClose。留空就等于把
            // 失败原因整个吞掉，UI 永远停在"连接中…"（2026-09-20 真机实测症状）。
            // 这里立即判失败：failConnect 会先摘掉 this.ws，随后的 onclose 会自我忽略，
            // 不会重复调度重连。
            const msg = (ev as { message?: string } | undefined)?.message;
            this.failConnect(msg ? `连接出错：${msg}` : '连接出错（平台未给出原因）');
        };
    }

    private startHeartbeat(): void {
        this.stopHeartbeat();
        this.heartbeatTimer = setInterval(() => {
            if (this.missedPong >= 2) {
                // 判定连接死亡：主动断开走重连流程
                this.ws?.close();
                return;
            }
            this.missedPong++;
            this.rawSend({ op: 'ping' });
        }, NetClient.HEARTBEAT_MS) as unknown as number;
    }

    private stopHeartbeat(): void {
        if (this.heartbeatTimer != null) {
            clearInterval(this.heartbeatTimer);
            this.heartbeatTimer = null;
        }
    }

    // ==================== 重连（指数退避 1s→2s→4s→…≤15s） ====================

    private scheduleReconnect(): void {
        if (this.manuallyClosed) return;
        this.stopReconnect();
        const delay = Math.min(1000 * Math.pow(2, this.reconnectAttempts), NetClient.MAX_RECONNECT_MS);
        this.reconnectAttempts++;
        this.reconnectTimer = setTimeout(() => this.connect(), delay) as unknown as number;
    }

    private stopReconnect(): void {
        if (this.reconnectTimer != null) {
            clearTimeout(this.reconnectTimer);
            this.reconnectTimer = null;
        }
    }

    private stopConnectTimer(): void {
        if (this.connectTimer != null) {
            clearTimeout(this.connectTimer);
            this.connectTimer = null;
        }
    }

    /**
     * 判定当前连接失败：把原因交给 UI、摘掉引用、按退避重排重连。
     *
     * <p>【顺序要紧】先 errorHandler（写 lastNetError）再 stateHandler（触发 renderTop）。
     * 反过来的话 renderTop 走的是 `online === false` 那条笼统分支，
     * 具体原因（超时 / 平台报错）就被吞掉了 —— 而排错时这句话最值钱。
     */
    private failConnect(reason: string): void {
        this.stopConnectTimer();
        this.lastErrorDetail = reason;
        this.errorHandler?.(reason);
        this.stateHandler?.(false);
        const old = this.ws;
        this.ws = null;              // 先摘引用：旧连接的 onclose 会因此自我忽略，不会重复调度
        try { old?.close(); } catch { /* ignore */ }
        this.scheduleReconnect();
    }

    // ==================== 消息处理 ====================

    private handleMessage(text: string): void {
        let msg: ServerMsg;
        try {
            msg = JSON.parse(text) as ServerMsg;
        } catch {
            return;
        }
        switch (msg.type) {
            case 'pong':
                this.missedPong = 0;
                break;
            case 'joined':
                this.token = (msg as JoinedMsg).token ?? null;
                this.joinedHandler?.(msg as JoinedMsg);
                if ((msg as JoinedMsg).reconnect) {
                    this.requestSnapshot(); // 重连后主动要一份最新快照对齐 UI
                }
                break;
            case 'snapshot':
                this.snapshotHandler?.(msg as SnapshotMsgDown);
                break;
            case 'event':
                this.eventHandler?.(msg as EventMsg);
                break;
            case 'error': {
                const reason = (msg as { reason: string }).reason ?? '';
                this.errorHandler?.(reason);
                // token 失效（服务重启/TTL 过期）→ 自动重新 join
                if (reason.includes('token')) {
                    this.token = null;
                    this.rawSend({
                        op: 'join', roomId: this.roomId,
                        playerId: this.playerId, seat: this.seat!,
                    });
                }
                break;
            }
        }
    }

    private rawSend(obj: unknown): void {
        if (this.ws != null && this.ws.readyState === WebSocket.OPEN) {
            this.ws.send(JSON.stringify(obj));
        }
    }
}

/**
 * 把平台交给 onmessage 的 data 统一成字符串。
 *
 * <p>【为什么需要】浏览器发文本帧时 `MessageEvent.data` 一定是 string，
 * 所以 `String(ev.data)` 一直没暴露问题。但小游戏平台的 WebSocket 适配层
 * 是把平台原生事件对象**原样透传**给 onmessage 的（见构建产物 web-adapter 里的
 * `r.onMessage(function(e){ t.onmessage(e) })`），其 `data` 类型由平台决定：
 * 二进制帧 / binaryType 为 arraybuffer 时会是 ArrayBuffer。
 * 此时 `String(data)` 得到的是 `"[object ArrayBuffer]"`，`JSON.parse` 抛异常后
 * 被静默 `return` —— 表现为"连接一切正常，但永远收不到任何快照"，
 * 是最难定位的一类问题。这里统一解码，彻底消掉这个分支。
 */
function decodeMessageData(data: unknown): string {
    if (typeof data === 'string') return data;
    if (data instanceof ArrayBuffer) return utf8Decode(new Uint8Array(data));
    if (ArrayBuffer.isView(data)) {
        const v = data as ArrayBufferView;
        return utf8Decode(new Uint8Array(v.buffer, v.byteOffset, v.byteLength));
    }
    return String(data);
}

/** UTF-8 解码：优先平台原生 TextDecoder，小游戏环境不保证有时退回手写实现 */
function utf8Decode(bytes: Uint8Array): string {
    const TD = (globalThis as {
        TextDecoder?: new (label?: string) => { decode(input: Uint8Array): string };
    }).TextDecoder;
    if (TD) {
        try { return new TD('utf-8').decode(bytes); } catch { /* 落到手写实现 */ }
    }
    let out = '';
    for (let i = 0; i < bytes.length;) {
        const b = bytes[i];
        if (b < 0x80) { out += String.fromCharCode(b); i += 1; continue; }
        let n = 0;
        let cp = 0;
        if ((b & 0xe0) === 0xc0) { n = 1; cp = b & 0x1f; }
        else if ((b & 0xf0) === 0xe0) { n = 2; cp = b & 0x0f; }
        else if ((b & 0xf8) === 0xf0) { n = 3; cp = b & 0x07; }
        else { out += '\ufffd'; i += 1; continue; }
        if (i + n >= bytes.length) { out += '\ufffd'; break; }
        for (let k = 1; k <= n; k++) cp = (cp << 6) | (bytes[i + k] & 0x3f);
        i += n + 1;
        out += cp > 0xffff
            ? String.fromCharCode(0xd800 + ((cp - 0x10000) >> 10), 0xdc00 + ((cp - 0x10000) & 0x3ff))
            : String.fromCharCode(cp);
    }
    return out;
}
