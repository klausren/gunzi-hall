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
    | 'PICK_JOKER'      // 他人捡牌扣王（手册 2.3.5；cards 为空 = 本轮不扣、让给下一家）
    | 'PLAY'            // 出牌
    | 'SETTLE'          // 结算（服务端驱动）
    | 'NEWGAME';        // 新局（重开发牌，客户端"新局"按钮触发）

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
    /** 是否本轮第一局：第一局抢亮 1 张大王（1 张即可、无人能反），第二局起亮/反级牌 */
    firstRound?: boolean;
    level: number;                          // 当前级数（3~10）
    trump?: { level: number; suit: string }; // 定主信息
    /**
     * 亮王/反主状态。
     * kind：FIRST_ROUND_JOKER（第一局抢亮大王）/ FIXED_BY_BOTTOM（无人亮时底牌定主）
     *      / LEVEL_CARDS（级牌）/ TRIPLE_SMALL_JOKER（3 小王）/ TRIPLE_BIG_JOKER（3 大王）
     * count：级牌张数（1..3，仅 LEVEL_CARDS 有意义，其余为 0）——"2 张反 1 张、
     *      3 张反 2 张或 1 张"要靠它判定，客户端据此决定图标是"可亮"还是"可反"。
     */
    /**
     * 亮主/反主声明。
     * suit：第一局"已亮大王、主花色待摸"时服务端**不下发**该字段——主花色由亮牌人
     * 随后摸到的第一张花色牌决定（手册 2.2），摸到之前它还没有值。
     */
    reveal?: { kind: string; suit?: string; count?: number; seat: SeatName };
    banker?: SeatName | null;               // 庄家座位
    turn?: SeatName | null;                 // 当前轮到谁出牌/行动
    followRule: string;                     // 跟牌规则（STRICT/ALIVE...）
    hands: Record<string, number>;          // 各座位余牌数
    yourHand: string[];                     // 私有手牌（仅本人可见，已按主牌排序）
    trick?: {                               // 当前一墩
        leader: SeatName;                   // 首出者
        leadCards: string[];                // 首出牌
        plays: { seat: SeatName; cards: string[] }[]; // 跟牌记录
        /** 一圈凑齐 4 手时才有：赢家座位（客户端收墩动画收向它） */
        winner?: SeatName;
    };
    trickPoints?: Record<string, number>;   // 各队已捡分（key = 队伍代号 A/B，仅供取数不进界面）
    /**
     * 各队已收走的**分牌明细**（team → 牌编码列表，只含 5/10/K）。
     * "闲家得分"条展开后要按花色列出"这些分具体是哪几张牌"，客户端只能由服务端下发。
     */
    takenPointCards?: Record<string, string[]>;
    pendingTributes?: Record<string, { blood: number; receiver: string }>; // 待进贡
    settlement?: SettlementMsg;             // 上一局结算（SETTLED 阶段读取）
    dryPot?: boolean;                        // 本局是否干锅（底牌无主花色普通牌）
    /**
     * 本局是否有人扣王（手册 2.3.3 庄家扣王 / 2.3.5 他人捡牌扣王）。
     *
     * <p>【别拿 bottomRevealed 顶替】那个是**可见性**口径（底牌摊没摊开），本字段是
     * **事实**口径。两者今天几乎同真同假，但**干锅局会分叉**：干锅是原样扣回，底牌里
     * 那几张王是发牌发出来的（手册 2.3.7 专门为"干锅底牌王"立规），此时
     * `bottomRevealed` 会因"底牌含王"为真，而"有人扣王"必须仍为假。
     */
    jokerBuried?: boolean;
    /**
     * 本局**已完成**的进贡流水（谁贡给谁、贡了哪几张、还贡还了哪几张）。
     *
     * <p>与 `pendingTributes`（只说明"还欠多少血"）互补：这里是已发生的牌面明细。
     * 进贡与还贡都是公开动作（真实牌桌上就是摊在桌面上的），所以对四家一视同仁地下发。
     * 数组为空 = 本局没有进贡。
     */
    tributes?: TributeLogMsg[];
    /**
     * 公开的底牌（6 张原底牌，牌面编码）。
     *
     * <p>只在三种情况下发，均由服务端按规则判定（手册 2.3.1 / 2.3.3 / 2.3.5 / 2.3.7）：
     * <ul>
     *   <li>扣底阶段（BURYING）：庄家刚收底、还没扣回，这 6 张就是原底牌，公开；</li>
     *   <li>干锅局（dryPot）：底牌不能替换、原样扣回，且干锅整段跳过扣底阶段，
     *       出牌全程仍然公开；</li>
     *   <li>扣王之后（bottomRevealed）：庄家扣了王，或有人在他人的扣王窗口里扣了王 ——
     *       手册 2.3.3 / 2.3.5 都要求"扣王时底牌必须亮给所有人看"。扣王窗口期间也会
     *       临时下发（不看到牌面就无从判断值不值得押）。</li>
     * </ul>
     * 其余时间（正常局出牌起、且没人扣王）**不会出现该字段** —— 庄家扣回去的 6 张是机密
     * （手册 2.3.3「不扣王时底牌不公开」），客户端据此把它从桌上收起。</li>
     */
    bottom?: string[];
    /**
     * **庄家私有**的底牌（扣完底之后）—— 只发给庄家本人，其余三家拿不到该字段。
     *
     * <p>扣底成功后 `bottom` 就不再下发了（那 6 张原底牌被庄家换成新的 6 张，属机密，
     * 手册 2.3.3），可庄家自己必须能回看"我到底扣了哪 6 张"：否则扣底决策等于开盲盒。
     * 因此服务端按玩家定制快照，只对庄家下发这一份（见 RoomActor 的私有区）。
     *
     * <p>与 `bottom` 的分工：`bottom` = 公开的 6 张原底牌（扣底阶段 / 干锅局全程），
     * 谁来都能看；`myBottom` = 我自己扣下的 6 张（扣底后长期有效）。两者同时存在时
     * 客户端按**公开优先**渲染，不会重复摆两块底牌。
     */
    myBottom?: string[];
    /**
     * 底牌是否处于"已公开"状态（手册 2.3.3 庄家扣王 / 2.3.5 他人扣王）。
     * 与 `bottom` 是否下发**不是同一件事**：`bottom` 决定画不画那 6 张，
     * 本字段只影响底牌区的说明文案（"扣完收起" 还是 "扣王了、本局公开"）。
     */
    bottomRevealed?: boolean;
    /**
     * 他人捡牌扣王窗口（手册 2.3.5）：当前轮到哪一家表态。没有窗口时**不出现**该字段。
     *
     * <p>等于自己的座位时，客户端给出「扣王 / 跳过」两个按钮。窗口的准入条件
     * （干锅禁扣、庄家底牌含分牌禁扣、有没有王可扣）全部由服务端判定，
     * 客户端只认字段 —— 不在这里复算规则。
     */
    pickSeat?: SeatName;
    /** 本次最多能扣几张王（= 底牌里可捡的非分牌张数）；只在 pickSeat 出现时下发 */
    pickMax?: number;
    [k: string]: unknown;
}

/**
 * 一笔进贡流水（服务端 GameRoom.tributeReceived / tributeReturnedCards）。
 *
 * <p>`returned` 缺省 = 还没还贡（收贡人尚未出牌）。两副牌面各自独立：
 * 进贡可能欠着、也可能已还，界面据此决定显示"待进贡 / 已进贡 / 已还贡"。
 */
export interface TributeLogMsg {
    payer: SeatName;            // 进贡人
    receiver: SeatName;         // 收贡人（= 进贡人的下家，服务端留档）
    cards: string[];            // 已交的贡牌
    returned?: string[];        // 已还的牌（缺省 = 还没还）
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
