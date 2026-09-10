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
        let ws: WebSocket;
        try {
            ws = new WebSocket(this.url);
        } catch (e) {
            this.scheduleReconnect();
            return;
        }
        this.ws = ws;
        // 所有回调都先校验"我是不是当前这条连接"：避免旧连接的 onclose
        // 触发新连接的 scheduleReconnect（双连接 / 状态被旧连接回滚）。
        ws.onopen = () => {
            if (this.ws !== ws) return;
            this.reconnectAttempts = 0;
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
            this.handleMessage(String(ev.data));
        };
        ws.onclose = () => {
            if (this.ws !== ws) return;
            this.stateHandler?.(false);
            this.stopHeartbeat();
            this.token = null; // 连接已断，token 随新连接重新签发
            this.ws = null;
            if (!this.manuallyClosed) {
                this.scheduleReconnect();
            }
        };
        ws.onerror = () => { /* onclose 会跟着触发，统一在 onclose 处理 */ };
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
