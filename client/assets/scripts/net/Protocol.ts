/**
 * 打滚子客户端 ↔ 战斗服 协议定义（与服务端 ClientMsg / snapshot / event 对齐）
 *
 * 上行（客户端 → 服务端）：
 *   {op:"join", roomId, playerId, seat}            → joined（含 token）
 *   {op:"ping"}                                     → pong
 *   {op:"snapshot", roomId, token}                  → snapshot
 *   {op:"cmd", type, roomId, token, cards?, suit?, payee?, indexes?, seed?}
 *
 * 下行（服务端 → 客户端）：
 *   {type:"pong"} | {type:"joined", reconnect, token, roomId, playerId, seat?}
 *   | {type:"snapshot", ...牌局全量状态} | {type:"event", op, ...}
 *   | {type:"error", reason}
 */

/** 上行消息（NetClient 内部组装，业务代码不直接拼） */
export interface JoinMsg {
    op: 'join';
    roomId: number;
    playerId: number;
    seat: SeatName;
}

export interface PingMsg {
    op: 'ping';
}

export interface SnapshotMsg {
    op: 'snapshot';
    roomId: number;
    token: string;
}

export type CmdType =
    | 'DEAL'            // 发牌（首局由服务端/bot 触发，客户端一般不发）
    | 'REVEAL'          // 亮王/反主
    | 'CONFIRM'         // 确认定主
    | 'RESOLVE_BOTTOM'  // 收底
    | 'TRIBUTE'         // 进贡
    | 'RETURN_TRIBUTE'  // 还贡
    | 'BURY'            // 扣底
    | 'PLAY'            // 出牌
    | 'SETTLE';         // 结算（服务端驱动）

export interface CmdMsg {
    op: 'cmd';
    type: CmdType;
    roomId: number;
    /** 会话令牌（join 时签发，必带；playerId 由服务端从 token 解析，不再上报） */
    token: string;
    cards?: string[];       // 牌编码：S14/H5/D10/C13/BJ/SJ
    suit?: string;          // REVEAL 用：HEART/SPADE/DIAMOND/CLUB
    payee?: string;         // TRIBUTE 用：收贡人 seat
    indexes?: number[];
    seed?: number;
}

/** 下行消息判别 */
export interface JoinedMsg {
    type: 'joined';
    reconnect: boolean;
    token: string;
    roomId: number;
    playerId: number;
    seat?: SeatName;
}

export interface SnapshotMsgDown {
    type: 'snapshot';
    roomId: number;
    phase: string;          // WAITING/DEALING/BIDDING/BURYING/PLAYING/SETTLED...
    gameNumber: number;
    level: number;                          // 当前级数（3~10）
    trump?: { level: number; suit: string }; // 定主信息
    reveal?: { kind: string; suit: string; seat: SeatName }; // 亮王/反主状态
    banker?: SeatName | null;               // 庄家座位
    turn?: SeatName | null;                 // 当前轮到谁出牌/行动
    followRule: string;                     // 跟牌规则（STRICT/ALIVE...）
    hands: Record<string, number>;          // 各座位余牌数
    yourHand: string[];                     // 私有手牌（仅本人可见，已按主牌排序）
    trick?: {                               // 当前一墩
        leader: SeatName;                   // 首出者
        leadCards: string[];                // 首出牌
        plays: { seat: SeatName; cards: string[] }[]; // 跟牌记录
    };
    trickPoints?: Record<string, number>;   // 各队已捡分
    pendingTributes?: Record<string, { blood: number; receiver: string }>; // 待进贡
    settlement?: SettlementMsg;             // 上一局结算（SETTLED 阶段读取）
    [k: string]: unknown;
}

/** 一局结算结果（服务端 RoundSettlement.Result） */
export interface SettlementMsg {
    attackerScore: number;      // 抓分方最终得分（含抠底×2）
    bankerScore: number;        // 庄家方最终得分（含保底×2）
    attackerTakesBank: boolean; // 抓分方是否上台（≥120）
    attackerPromoted: boolean;  // 抓分方是否升级
    bankerPromoted: boolean;    // 庄家方是否升级
    dugBottom: boolean;         // 是否抠底
}

export interface EventMsg {
    type: 'event';
    op: string;             // DEAL/REVEAL/CONFIRM/RESOLVE_BOTTOM/TRIBUTE/RETURN_TRIBUTE/BURY/PLAY/SETTLE/...
    success?: boolean;
    seat?: SeatName;
    playerId?: number;
    cards?: string[];
    gameNumber?: number;
    [k: string]: unknown;
}

export interface ErrorMsg {
    type: 'error';
    reason: string;
}

export type ServerMsg = JoinedMsg | { type: 'pong' } | SnapshotMsgDown | EventMsg | ErrorMsg;

export type SeatName = 'NORTH' | 'EAST' | 'SOUTH' | 'WEST';

/** 牌编码工具（与服务端 CardCodec 对齐） */
export const CardCodec = {
    suits: ['SPADE', 'HEART', 'DIAMOND', 'CLUB'] as const,
    /** 排序权重：小王 < 大王 < 级牌/主牌（渲染排序交给 UI 层按快照主牌信息排） */
    rankOf(code: string): number {
        if (code === 'SJ') return 16;
        if (code === 'BJ') return 17;
        return parseInt(code.slice(1), 10);
    },
};
