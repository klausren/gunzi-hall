import { _decorator, Color, Component, game, Graphics, Label, Node, ResolutionPolicy, Tween, tween, UIOpacity, UITransform, Vec3, view } from 'cc';
import { NetClient } from '../net/NetClient';
import { describeServerUrl, resolveServerUrl } from '../net/ServerUrl';
import type { EventMsg, JoinedMsg, SeatName, SnapshotMsgDown, TributeLogMsg } from '../net/Protocol';
import { addSuitIcon, addTileText, cardFace, createCardNode, createMiniCardNode, createTileNode, drawCardBg, suitColor } from './CardUI';

const { ccclass, property } = _decorator;

/**
 * 座位可被 URL 参数临时覆盖：`?seat=EAST`。
 *
 * <p>与 `?server=` 同一套思路：同机联调（开两个浏览器窗口各占一个座位，验证真人↔真人
 * 的交互，如进贡/还贡、抢亮时序）时不用改代码 + 重新构建。非法值一律忽略，回退配置值。
 */
function resolveSeatOverride(configured: SeatName): SeatName {
    if (typeof location === 'undefined') return configured;
    const m = /[?&]seat=([A-Za-z]+)/.exec(location.search);
    const v = (m?.[1] ?? '').toUpperCase();
    return (['NORTH', 'EAST', 'SOUTH', 'WEST'] as string[]).includes(v)
        ? (v as SeatName) : configured;
}

/**
 * 牌桌主控（Sprint 6 juice 版）。设计分辨率：1280×720（屏幕半高 360）。
 *
 * 视角旋转：以 mySeat 为底部座位（我永远在下方，出牌落点在手牌上方），
 * 其余三家按相对位次沿罗盘**顺时针**映射到插槽（0/1/2/3 = 下/左/上/右）。
 * 槽位是"方位"，与**逆时针**的出牌顺序方向相反，两者不要混（详见 relOf）。
 *
 * 布局（世界坐标，屏幕 -360..360）：
 *   顶部 y≈320/288/256   信息栏两行 + toast
 *   对家   y≈205 铭牌（其墩牌在铭牌下方 y≈160）
 *   左右   y≈60  铭牌（墩牌在铭牌上方 y≈105）
 *   我     y≈-160 铭牌（墩牌 y≈-115，亮主栏 y≈-110，按钮区 y≈-30，手牌 y≈-240）
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

    // 兜底地址：只在拿不到 location 的环境生效（微信小游戏、Cocos 编辑器预览）。
    // Web 端运行时会自动探测——页面从哪台机器加载，就连哪台机器的后端，换 WiFi 无需改这里
    // （见 net/ServerUrl.ts）。构建小游戏时 build-wechatgame.ps1 会把本机 IP 注入进来。
    // 临时指向别的后端：页面 URL 后加 ?server=ws://<host>:8080/ws
    // （尖括号是刻意的：构建脚本会按 ws://<字母数字.- >:8080/ws 的模式批量替换本机 IP，
    //   示例若写成真地址，注释也会被一起改掉、源码反复变脏。）
    // 这里留 127.0.0.1 表示"本机默认值"：**真机地址一律由 build-wechatgame.ps1 注入**。
    // 不要把某个开发者的局域网 IP 写死在这儿 —— 换 WiFi 就失效，
    // 而且会让"注入没生效"看起来像生效了（构建脚本的产物自检会兜住这一点）。
    @property serverUrl = 'ws://127.0.0.1:8080/ws';
    @property roomId = 1001;
    @property playerId = 1;
    /**
     * 我的座位（绝对方位）。
     *
     * <p>【为什么默认是 SOUTH】屏幕上"我"永远在下方，而座位名是罗盘绝对方位
     * （上北下南）：玩家坐在桌子南边面朝北看牌，所以我在下 = 我在南。
     * 原先默认 NORTH 会出现"上方写着 SOUTH、下方是自己"的错位观感。
     * 多人同机联调可用 `?seat=EAST` 临时改座位（见 resolveSeatOverride）。
     *
     * <p>【必须与服务端一致】服务端只把 {@code ServerMain.DEFAULT_HUMAN_SEAT}
     * （当前 SOUTH）留给真人，其余三个座位是 bot 的。这里写别的座位会直接撞
     * "座位已被占用" 并永远停在"等待服务器数据"——两端座位名要一起改。
     */
    @property mySeat: SeatName = 'SOUTH';

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
    private vw = 1280;                              // 本机实际可见宽度（FIXED_HEIGHT 下按屏幕比例算出来）
    private vh = 720;                               // 可见高度：FIXED_HEIGHT 把它钉死在设计高度，恒为 720
    private tw = 1120;                              // 牌桌绒布宽度（随屏幕放宽，避免宽屏两侧露底色）
    private lastDiagKey = '';                       // 顶栏诊断日志去重（快照很频繁，避免刷屏）
    /** 最近一次服务端错误：顶栏常驻显示（toast 会消失，入座失败这种卡死状态必须一直看得见） */
    private lastNetError = '';
    private handBaseY = new Map<string, number>();  // 复合身份 → 该牌所在行的基础 y（两行后不能写死 0）
    private handNodes = new Map<number, Node>();

    // 【白屏根因修复 · 重建签名】快照内容没变就一个节点都不动。
    //
    // 服务端**每条命令后都推全量快照**，一局下来几百条；而 renderHand() 是
    // 「39 张牌全部拆掉重建」。即使把资源释放做对，这种量级的反复重建也是在
    // 拿 GPU 显存做压力测试（每张牌 = 1 个 Graphics 顶点缓冲 + 若干系统字贴图）。
    // 用签名把重建次数从「每快照一次」压到「每次手牌真的变化一次」，量级差几十倍。
    // null = 尚未渲染过（不能用 '' 当哨兵：空手牌时签名恰好也是 ''）。
    private handSig: string | null = null;
    private btnSig: string | null = null;
    /** 手牌上次重建时间戳（DEALING 阶段高频快照下限制重建频率，防白屏式反复重建） */
    private lastHandBuildAt = 0;

    // ---- 顶栏第一行：第N局 + 阶段 + 主牌级数图标（不再用"级3 主方块3 / 庄X 轮Y"文字） ----
    private topBarNode!: Node;
    private topTextLabel!: Label;
    /** 主牌级数图标签名；变化才重建瓦片（否则每条快照只挪位置） */
    private trumpBadgeSig = '';
    private trumpBadgeNode: Node | null = null;
    // ---- 顶栏第二行：闲家得分条（展开按钮 + 得分胶囊），展开后列分牌明细 ----
    private scoreBarNode!: Node;
    private scoreCapsuleLabel!: Label;
    private scoreExpandBtn: Node | null = null;
    /** 展开按钮上的三角（收起 ◀ / 展开 ▼，只重画三角不重建按钮） */
    private scoreToggleG: Graphics | null = null;
    /** 是否已展开分牌明细 */
    private scoreExpanded = false;
    private scorePanelNode: Node | null = null;
    private scorePanelSig = '';
    private toastLabel!: Label;
    /**
     * toast 的牌局上下文 key（局号|阶段|轮次|本墩牌面）。
     *
     * <p>【为什么必须有它】toast 只是"最近一条事件提示"的展示位，但事件是**稀疏**到达的：
     * 出牌成功的事件被有意过滤掉（见 onEventToast），所以一次出牌失败的红字之后，
     * 可能连着好几手、甚至整整一局都没有新事件来覆盖它 —— 结果就是失败提示一直挂在
     * 牌桌上，下一把还能看见（Tracy 反馈的现象）。上下文 key 用来表达
     * "牌局真的往前走了"，一变就把旧提示作废。
     */
    private toastCtxKey = '';
    /** toast 到期时刻（毫秒，0=无倒计时）。牌局卡住不再有快照时由它兜底清空 */
    private toastUntil = 0;
    /**
     * 常驻标记：致命错误（房间线程异常 / bot 驱动异常 / 座位被占）不许自动消失。
     *
     * <p>这类消息和"出牌失败"是两种东西：出牌失败是**牌局内的一次尝试**，
     * 牌局往前走就该让它消失；致命错误意味着**牌局已经跑不动了**，
     * 它一消失玩家就彻底失去线索（顶栏在有快照时也不会显示 lastNetError）。
     * 所以只对 ①游戏事件提示 施加"到期 + 上下文清空"，②致命错误保持常驻。
     */
    private toastSticky = false;
    private timerLabel!: Label;
    private trickNode!: Node;
    /**
     * 底牌展示区（原 6 张，扣底阶段与干锅局对所有人公开）。
     *
     * <p>【位置必须事先留出来】它不是"某张牌飞过来"的临时节点，而是一块固定区域：
     * 扣底阶段庄家要看清底牌再决定扣哪 6 张，干锅局更是全程要能回看那 6 张。
     */
    private bottomNode!: Node;
    /** 底牌区签名（公开/私有 + 牌面 + 是否干锅 + 是否展开）。内容不变就一个节点都不动 */
    private bottomSig = '';
    /**
     * 「我的底牌」是否展开。
     *
     * <p>扣底阶段摊开的原底牌是**所有人**都能看的（服务端 bottom 字段）；扣完之后那 6 张
     * 换成庄家扣下的新牌，只有庄家本人拿得到（服务端 myBottom 字段）—— 这是"回看"性质
     * 的信息，不是每个人的必备信息，所以默认**折叠成一枚小胶囊**，点开才摊牌。
     * 不折叠的话它会常驻牌桌中央，挡住对家墩牌行那条带。
     */
    private bottomExpanded = false;
    /** 上一次渲染的底牌区形态（'pub' | 'mine' | 'hint' | ''）。形态切换时把展开态复位 */
    private bottomMode = '';
    private handNode!: Node;
    private btnNode!: Node;
    /** 亮主候选栏：手牌上方一排 6 个图标（发牌中 + 亮主阶段可见） */
    private bidNode!: Node;
    /** 亮主候选栏**结构**重建签名（与 handSig/btnSig 同理，避免每条快照都重建） */
    private bidSig: string | null = null;
    /**
     * 角标 Label（按图标 key 索引）。发牌期间手牌每 60ms 变一次，角标数字跟着变；
     * 若每次都拆建整排图标，等于每秒重建十几次 Graphics + 系统字贴图。
     * 所以数字走这里的增量刷文本，节点只在"图标亮灭状态"变化时才重建。
     */
    private bidBadgeLabels: Partial<Record<string, Label>> = {};
    /** 亮主栏上方提示行（"发牌中 N/39" / "已亮大王，等待摸到花色牌…"） */
    private bidTipLabel: Label | null = null;
    private settleNode: Node | null = null;
    private settleKey = '';                 // 面板内容 key：相同则不重建（避免重复弹入）

    // 四家持久铭牌（呼吸高亮必须持久节点，不能每次快照重建）
    private seatPlateG: Partial<Record<SeatName, Graphics>> = {};
    private seatLabelC: Partial<Record<SeatName, Label>> = {};
    private seatHaloOp: Partial<Record<SeatName, UIOpacity>> = {};
    private plateText: Partial<Record<SeatName, string>> = {};
    /**
     * 铭牌附加标记：庄家（级数瓦片 + "庄"瓦片）与定主者（花色瓦片）。
     *
     * <p>为什么挂在铭牌节点下而不是全局重画：铭牌是持久节点且会随视角旋转换槽位，
     * 标记跟着父节点走就不会错位；签名制重建保证每条快照不重建瓦片（快照一局几百条）。
     */
    private seatRoot: Partial<Record<SeatName, Node>> = {};
    private seatBankerNode: Partial<Record<SeatName, Node>> = {};
    private seatBankerSig: Partial<Record<SeatName, string>> = {};
    private seatTrumpNode: Partial<Record<SeatName, Node>> = {};
    private seatTrumpSig: Partial<Record<SeatName, string>> = {};

    // ---- 小瓦片配色（庄家标记 / 级数标记 / 定主标记共用；项目无图片资源，一律代码画） ----
    /** 瓦片底：浅米白（压在绿色绒布上对比最强，且与参考图一致） */
    private static readonly TILE_BG = new Color(246, 245, 238, 246);
    private static readonly TILE_BORDER = new Color(120, 110, 88, 135);
    private static readonly TILE_GOLD = new Color(206, 130, 22, 255);
    private static readonly TILE_H = 24;
    /** 顶栏"主牌+级数"瓦片宽（花色 + 最多 2 位级数） */
    private static readonly TRUMP_TILE_W = 50;

    // 墩牌差量动画状态：座位 → 当前已展示的牌串 / 牌节点
    private trickShown = new Map<SeatName, string>();
    private trickNodes = new Map<SeatName, Node[]>();
    /** 四张牌已出齐、正在停留展示中：期间忽略新的出牌快照，停留结束统一收墩 */
    private trickHoldScheduled = false;
    /** 本墩赢家（收墩动画的目标座位），从快照 trick.winner 取 */
    private trickHoldWinner: SeatName | null = null;
    /** 正在停留的这墩 key，用于识别是否仍是同一墩 */
    private trickHoldKey = '';
    /** 已经收掉的墩 key：避免服务端仍下发同一墩时重复飞回桌面 */
    private trickDismissedKey = '';

    // 出牌倒计时（本地提醒）。服务端超时托管默认 32s：客户端 30s 先亮警告，
    // 归零后显示“即将托管”，提醒玩家服务端很快会代打，但不代表命令立即失效。
    private timerDeadline = 0;
    private timerKey = '';
    private static readonly TURN_SECONDS = 30;

    // 【别每帧 new Color】UIRenderer.color 的 setter 是**引用比较**（this._color === value），
    // 每帧 new 一个 Color 必然不等 → 每次都 _updateColor + markForUpdateRenderData，
    // 顶点缓冲被反复标脏重传。倒计时每帧都在跑，是纯粹的显存/带宽浪费。
    // 用共享常量，引用不变就真的跳过。
    private static readonly TIMER_COLOR_NORMAL = new Color(235, 145, 25, 255);
    private static readonly TIMER_COLOR_WARN = new Color(255, 70, 70, 255);
    private static readonly TIMER_COLOR_OVER = new Color(200, 90, 90, 255);

    // 结算面板：收到 SETTLE 事件后展示（服务端无 SETTLED 阶段，
    // SettleRoundCommand 直接 SETTLING→DEALING 开新局，只能事件驱动）
    private settleVisibleUntil = 0;
    private settleGameNumber = 0;   // 结算命令会把 gameNumber+1，事件里的局号已是下一局
    private preSettleBanker: SeatName | null = null; // 本局庄家（SETTLE 后快照的 banker 已是新庄，不能用）
    /**
     * 玩家已把这一局的结算面板点掉 → 后续快照不许再把它弹回来。
     * 由下一条 SETTLE 事件（新一局结算）重置为 false。
     */
    private settleDismissed = false;

    // ---- 结算面板几何（三处硬约束，改前先看 renderSettlement 的说明）----
    //  ① 底部操作区 y∈[-139,-4]：亮主栏（-110，图标高 58）+ 按钮（-30，高 52）——
    //     面板底边必须在它之上，否则又变成"亮主时被挡"；
    //  ② 顶部 toast 基线 258、得分条 288 —— 面板顶边不能盖住它们；
    //  ③ 面板节点尺寸就是触摸命中区：**绝不能**给成全屏，否则点击收起会吞掉整屏点击。
    private static readonly SETTLE_PANEL_W = 660;
    private static readonly SETTLE_PANEL_H = 240;
    private static readonly SETTLE_PANEL_Y = 126;   // 中心 → y∈[6,246]（① ② 都满足）
    /** 展示时长。面板已不遮操作区，但仍会压住对家铭牌/墩牌行那条带，6s 够看完一次 */
    private static readonly SETTLE_SHOW_MS = 6000;
    // 一墩打完（4 家都出过）后，整墩四张牌在桌上停留的时长（毫秒），让人看清本轮出的
    // 全部四张牌，再收墩并开始下一轮出牌。2 秒兼顾"看清"与"不拖沓"。
    private static readonly TRICK_DWELL_MS = 2000;

    // 底牌展示区（扣底阶段 / 干锅局）参数。
    // 牌面比墩牌行的迷你牌放大一点：底牌是"要看清再决定扣哪 6 张"的信息，
    // 而墩牌行只求看清出了什么花色点数。间距 38 > 32×1.25 保证放大后不互相压边。
    private static readonly BOTTOM_CARD_SCALE = 1.25;
    private static readonly BOTTOM_CARD_SPACING = 38;
    /** 底牌区标题/胶囊文字色（浅青白，压在深绿绒布上最清楚；干锅态另行改用暖色） */
    private static readonly BOTTOM_TITLE_COLOR = new Color(214, 230, 216, 255);

    // Toast 存活时长（毫秒）。失败提示带原因（"首家有花色必须跟出"这类规则说明），
    // 需要够读一遍，给 4.5s；普通提示（已入座/亮主结果）2.2s 足够。
    // 真正决定"什么时候消失"的是牌局上下文变化（见 syncToastContext），
    // 这只两档是"牌局不动了"时的兜底，不参与正常节奏。
    private static readonly TOAST_FAIL_MS = 4500;
    private static readonly TOAST_INFO_MS = 2200;
    // 【别每次 new Color】同 TIMER_COLOR_* 的理由：UIRenderer.color 的 setter 是引用比较，
    // 每弹一次 toast 新建一个 Color 必然被判为"变了"→ 白标一次渲染数据。用共享常量。
    private static readonly TOAST_COLOR_WARN = new Color(255, 95, 95, 255);
    private static readonly TOAST_COLOR_INFO = new Color(255, 175, 85, 255);

    // 断线重连 UI（T-702）：首次连接不算"断线"，只刷顶栏；
    // 曾连上过再掉线 → 弹全屏遮罩 + 已等待秒数；重连成功 → 关遮罩 + 恢复引导
    private reconnectNode: Node | null = null;
    private reconnectLabel: Label | null = null;
    private wasOnline = false;      // 本次会话是否曾成功连上过
    private offlineSince = 0;       // 掉线时刻（遮罩显示已等待秒数）
    private hintMyTurnOnSnapshot = false; // 重连成功后，快照到达时提示"轮到你"

    /**
     * 【阶段名只有一个真相来源：服务端 GamePhase】
     * 服务端枚举是 WAITING / DEALING / BIDDING / **TRIBUTE** / BURYING / PLAYING / SETTLING / ROUND_OVER ——
     * 进贡、还贡、抗贡**同属 TRIBUTE 一个阶段**，**不存在 RETURN_TRIBUTE 阶段**。
     * 下面集合与 {@link PHASE_TEXT} 里的 `RETURN_TRIBUTE` 只是"名字→文案"的兼容映射
     * （万一将来服务端真拆出这个阶段，文案已经在位），**不代表它今天会到来**：
     * 任何 `switch (s.phase)` 都不要为它写分支 —— 那是永远进不去的死分支。
     * 2026-09-20 真机 bug 就是栽在这里（详见 renderButtons 的 TRIBUTE 分支注释）。
     */
    /** 只有这些阶段的 banker 才是"本局"庄家（结算切庄后 DEALING/BIDDING 里已是下一局的） */
    private static readonly IN_GAME_PHASES = new Set(['BURYING', 'TRIBUTE', 'RETURN_TRIBUTE', 'PLAYING', 'SETTLING']);

    /** 有"轮到谁"语义、需要呼吸高亮的阶段 */
    private static readonly ACTION_PHASES = new Set(['BIDDING', 'BURYING', 'PLAYING', 'TRIBUTE', 'RETURN_TRIBUTE']);

    /** 相对位次文案：下标 = relOf(seat) 的值（0 我 / 1 左手家 / 2 对家 / 3 右手家） */
    private static readonly REL_TEXT: string[] = ['我', '左手家', '对家', '右手家'];

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

    /**
     * 牌码是不是"王"。
     *
     * <p>与服务端 CardCodec 对齐：普通牌码是「花色首字母 + 点数」（如 S5 / H10 / C3），
     * 王没有花色位，就是 `SJ`（小王）/ `BJ`（大王）两个整码。
     * 必须精确比对 —— 不能写成 includes('J')，那会把点数为 J 的普通牌也算成王。
     *
     * <p>⚠️ 入参**只能是服务端下发的牌码**。`selected` 里存的是复合身份
     * `code#occurrence`（见 {@link TableUI#cardKey}），直接喂进来恒为 false。
     * 要判"选中的牌是不是王"，先 {@link TableUI#selectedCodes} 剥掉 `#n` 后缀
     * （2026-09-18 的"选大王也提示只能扣王"就是这么来的）。
     */
    static isJokerCode(code: string): boolean {
        return code === 'BJ' || code === 'SJ';
    }

    // ==================== 座位旋转（我在下方） ====================

    /**
     * 相对位次：从"我"出发沿**罗盘顺时针**数几格（ALL_SEATS 按顺时针 N→E→S→W 编号）。
     * mySeat=SOUTH 时：0=我(下/南) 1=左家(西) 2=对家(上/北) 3=右家(东)。
     *
     * <p>⚠️ 这里数的是**座位方位**，不是出牌顺序 —— 出牌是**逆时针**（手册 3.2，
     * 服务端 Seat.next()），所以"下家"（下一个出牌的人）落在 3 号槽位（右手边），
     * 不是 1 号。两者方向相反是正常的，别把槽位表按出牌方向去"纠正"。
     */
    private relOf(seat: SeatName): number {
        return (TableUI.ALL_SEATS.indexOf(seat) - TableUI.ALL_SEATS.indexOf(this.mySeat) + 4) % 4;
    }

    /**
     * 铭牌局部坐标（trickNode 子空间，trickNode 位于 (0,80)）。
     *
     * <p>槽位顺序 = 相对位次 0/1/2/3 = 下 → 左 → 上 → 右（沿**罗盘顺时针**排一圈）。
     *
     * <p>【座位上的人必须和牌桌罗盘对得上】座位名是**绝对方位**（N/E/S/W），玩家坐在
     * 南边面朝北看牌桌，所以屏幕上必须是「上北 · 右东 · 下南 · 左西」。如果 1 号位
     * 放在右边，等于把罗盘摆了 180°（屏幕上北在下、南在上），玩家会看到上方标着
     * SOUTH、而自己（本该是 SOUTH）在下方 —— N/S 看着就是反的。
     */
    /**
     * 左右两家（西/东）槽位的横向偏移。
     *
     * <p>原先写死 ±430：在所有**手机**上都安全（可见半宽 ≥ 640），但在窄屏上会挨裁 ——
     * 折叠屏展开 / 平板 4:3 的可见宽只有 960（半宽 480），而铭牌 150 宽、呼吸光环外缘
     * 到 ±85，外缘落在 430+85 = 515 > 480，左右两家的铭牌会被屏幕切掉一截。
     *
     * <p>所以改成按可见宽度推导，并**保留 430 作为上限**：这样 16:9 及以上（半宽 ≥ 640）
     * 的取值与过去逐字节一致（手机行为不变），只有更窄的屏幕才向内收。
     */
    private sideX(): number {
        const halfPlate = 85;   // 呼吸光环外半宽（铭牌 75 + 光环外扩 10）
        const margin = 12;      // 与屏幕边缘留一条缝
        // 下限 180：再窄也不让左右两块铭牌穿过桌面中心叠在一起
        return Math.min(430, Math.max(180, this.vw / 2 - halfPlate - margin));
    }

    private plateLocal(seat: SeatName): Vec3 {
        const x = this.sideX();
        const slots = [new Vec3(0, -240, 0), new Vec3(-x, -20, 0), new Vec3(0, 125, 0), new Vec3(x, -20, 0)];
        return slots[this.relOf(seat)];
    }

    /** 该座位墩牌行的局部坐标（牌行中心；横向与铭牌同源，见 {@link #sideX}） */
    private trickLocal(seat: SeatName): Vec3 {
        const x = this.sideX();
        const slots = [new Vec3(0, -195, 0), new Vec3(-x, 25, 0), new Vec3(0, 80, 0), new Vec3(x, 25, 0)];
        return slots[this.relOf(seat)];
    }

    /**
     * 庄家标记（级数瓦片 + "庄"瓦片）相对铭牌中心的锚点。
     * 下标 = 相对位次 0..3（我 / 左家 / 对家 / 右家），dir：+1 向右生长、-1 向左、0 居中。
     *
     * <p>为什么不是统一的"铭牌上方"：铭牌上方 45 单位就是该家自己的墩牌行（出牌区），
     * 只有对家（上方槽位）的墩牌行在铭牌**下方**，上方是空的。所以：
     * 我对家在头上、左右两家贴外侧、我自己排在铭牌左侧（上方同样被墩牌占着）。
     */
    private static readonly BANKER_MARK: { x: number; y: number; dir: number }[] = [
        { x: -84, y: 10, dir: -1 },   // 我（下）：铭牌左侧
        { x: -84, y: 10, dir: -1 },   // 左家（西）：铭牌外侧
        { x: 0, y: 34, dir: 0 },      // 对家（上）：铭牌上方（居中）
        { x: 84, y: 10, dir: 1 },     // 右家（东）：铭牌外侧
    ];

    /**
     * 定主者标记（花色 / 大王小王 / 底牌 瓦片）相对铭牌中心的锚点。
     * 一律贴向桌面中心那一侧（手册口径：对家看上下、左右家看左右），
     * 但上/下两家要沿切向让开正中的墩牌行，故横向偏了 60。
     */
    private static readonly TRUMP_MARK: { x: number; y: number; dir: number }[] = [
        { x: -60, y: 36, dir: -1 },   // 我（下）：铭牌上方偏左（正上方是墩牌行）
        { x: 84, y: 0, dir: 1 },      // 左家（西）：铭牌右侧（向心）
        { x: -60, y: -36, dir: -1 },  // 对家（上）：铭牌下方偏左（正下方是墩牌行）
        { x: -84, y: 0, dir: -1 },    // 右家（东）：铭牌左侧（向心）
    ];

    start(): void {
        // 【宽屏适配 T-701】默认 fitWidth（宽钉死 1280、高按比例）：在 2.17:1 的现代手机上
        // 可见高度只剩 589 而不是 720，顶栏 y=320/288 会被整行裁掉 —— 级数、主牌、庄家
        // 全看不见。改 FIXED_HEIGHT：垂直 720 完整可见，宽度按屏幕比例向外扩展，
        // 宽屏反而更宽（手牌区更松），是一举两得的解法。
        view.setDesignResolutionSize(1280, 720, ResolutionPolicy.FIXED_HEIGHT);
        const fs = view.getFrameSize();
        this.vw = fs.height > 0 ? 720 * fs.width / fs.height : 1280;
        // FIXED_HEIGHT 的语义就是"可见高度恒等于设计高度"，所以 vh 恒为 720，
        // 而 vw 随屏幕比例变化（16:9→1280、20:9→1600、21:9→1680）。
        // 【规矩】凡是"铺满屏幕"的遮罩/底色，都必须用这两个值算，不许写死 1280：
        // 长条屏上写死会在两侧留下一条既不变暗、**也点不动**的缝（得分面板就是如此，
        // 因为"点任意处收起"的命中判定看的是节点尺寸）。
        this.vh = 720;
        this.tw = Math.min(1500, this.vw - 80);
        // 座位覆盖要在 buildLayout 之前生效：铭牌的"我"标记与槽位摆位都读 mySeat
        this.mySeat = resolveSeatOverride(this.mySeat);
        this.buildLayout();
        this.installContextLostGuard();
        // 运行时解析地址：优先 ?server= 参数，其次从 location 自动探测，最后才用配置的兜底值。
        // 换来换去的局域网 IP 不再需要改代码 + 重新构建。
        const url = resolveServerUrl(this.serverUrl);
        console.log(`[TableUI] 战斗服地址 ${describeServerUrl(this.serverUrl)}`);
        this.net = new NetClient(url);
        this.net.onJoined(j => this.onJoinedMsg(j));
        this.net.onSnapshot(s => this.onSnapshotMsg(s));
        this.net.onEvent(e => this.onEventToast(e));
        this.net.onError(r => this.onNetError(r));
        this.net.onStateChange(ok => this.onNetState(ok));
        this.net.join(this.roomId, this.playerId, this.mySeat);
    }

    onDestroy(): void {
        this.net?.close();
    }

    // ==================== 断线重连 UI（T-702） ====================

    private onJoinedMsg(j: JoinedMsg): void {
        this.lastNetError = '';   // 入座成功：清掉顶栏的失败提示
        if (j.seat && j.seat !== this.mySeat) {
            // 服务端认的座位和本端不一致（正常不会发生）：至少让它在控制台可见，
            // 否则座位名错位只能表现成"牌桌上的名字怪怪的"，很难反查到根因。
            console.warn(`[TableUI] 服务端座位 ${j.seat} ≠ 本端 mySeat ${this.mySeat}`);
        }
        if (j.reconnect) {
            // 重连恢复：等快照到达后再给"轮到你"引导（此刻还没有牌局状态）
            this.hintMyTurnOnSnapshot = true;
            this.showToast('已重新连接，牌局已恢复');
        } else {
            this.showToast(`已入座 ${j.seat ?? this.mySeat}`);
        }
    }

    /**
     * 服务端错误提示。
     *
     * <p>【为什么「座位已被占用」要单独处理】它几乎只有一个成因：本端请求的座位，
     * 与服务端给真人预留的座位不一致（服务端把另外 3 个座位交给 bot，见 ServerMain）。
     * 这种失败不会产生任何快照，界面会一直挂在"等待服务器数据…"——
     * 只弹一条错误而不给下一步动作，玩家（和当时排错的我）都只能干看着。
     * 所以：toast 保留原文，顶栏常驻一句"该改哪里"（toast 会消失，顶栏不会）。
     *
     * <p>sticky=true：这是**致命**错误（房间线程/bot 驱动挂了、入不了座），
     * 牌局不会再前进，提示必须一直挂着 —— 有快照时顶栏不会再显示 lastNetError，
     * 提示一消失就等于线索全丢。出牌失败那种"牌局内的失败"走事件通道，不吃 sticky。
     */
    private onNetError(reason: string): void {
        this.lastNetError = reason;
        this.renderTop();
        this.showToast(`⚠ ${reason}`, true, true);
    }

    private onSnapshotMsg(s: SnapshotMsgDown): void {
        // 【必修】不再清空 selected：selected 存的是牌代码（不是下标），快照变化不影响。
        // 出牌成功后该牌代码不在新 yourHand 里，自然失效，无需手动清。
        const prevPhase = this.snap?.phase;
        this.snap = s;
        // 【先作废旧提示，再渲染/提示新状态】放在 renderAll 与下面的"干锅/轮到你"之前，
        // 否则刚弹出来的新提示会被自己的上下文变化立刻清掉。
        this.syncToastContext(s);
        this.pruneSelected();
        this.renderAll();
        // 干锅局跳过扣底直接进入 PLAYING：给庄家一次明确提示。
        // 事件消息可能因网络抖动丢失，快照兜底再提示一次。
        if (s.phase === 'PLAYING' && (prevPhase === 'BIDDING' || prevPhase === 'BURYING' || prevPhase === 'TRIBUTE')
            && s.dryPot && s.banker === this.mySeat) {
            this.showToast('干锅：底牌无主花色普通牌，已原样扣回', true);
        }
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
        this.renderButtons();   // 在线↔离线要换文案："新局" / "重试连接"（签名守卫，不会白重建）
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
            // 铺满**可见**区域：写死 1280 会在 20:9 / 21:9 屏的两侧留下一条不透光的缝
            mask.addComponent(UITransform).setContentSize(this.vw, this.vh);
            const g = mask.addComponent(Graphics);
            g.fillColor = new Color(0, 0, 0, 165);
            g.fillRect(-this.vw / 2, -this.vh / 2, this.vw, this.vh);
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
        felt.addComponent(UITransform).setContentSize(this.vw, 720);
        const g = felt.addComponent(Graphics);
        // 木沿
        g.roundRect(-this.vw / 2 + 6, -354, this.vw - 12, 708, 24);
        g.lineWidth = 8;
        g.strokeColor = new Color(96, 72, 38, 255);
        g.stroke();
        // 桌面基底（暗角）
        g.roundRect(-this.vw / 2 + 11, -349, this.vw - 22, 698, 20);
        g.fillColor = new Color(9, 42, 31, 255);
        g.fill();
        // 主桌面
        g.roundRect(-this.tw / 2, -282, this.tw, 564, 28);
        g.fillColor = new Color(13, 54, 40, 255);
        g.fill();
        // 中心提亮椭圆（模拟灯光照射）
        g.ellipse(0, 0, 470, 235);
        g.fillColor = new Color(16, 61, 45, 255);
        g.fill();
        // 内圈金线
        g.roundRect(-this.tw / 2, -282, this.tw, 564, 28);
        g.lineWidth = 1.5;
        g.strokeColor = new Color(200, 165, 70, 60);
        g.stroke();
        this.node.addChild(felt);

        // 顶部信息栏（第一行）：第N局 + 阶段 + **主牌级数图标**。
        // 原先是一整行文字「第1局 出牌 | 级3 主方块3 | 庄EAST 轮SOUTH」：
        // 级数/主牌用文字描述既不直观又占地方（玩家得自己把"主方块3"翻译成图标），
        // 而"庄谁/轮谁"本来就有铭牌金框与呼吸高亮在表达，纯属重复。
        // 现在拆成"文字 + 图标"两个节点，整体居中摆位（见 layoutTopBar）。
        this.topBarNode = new Node('topbar');
        this.topBarNode.layer = 1 << 25;
        this.topBarNode.setPosition(0, 320, 0);
        this.node.addChild(this.topBarNode);
        const topText = this.makeLabelNode('连接中…', 18, new Color(228, 238, 228, 255));
        topText.setPosition(0, 0, 0);
        this.topBarNode.addChild(topText);
        this.topTextLabel = topText.getComponent(Label)!;

        // 顶部信息栏（第二行）：闲家得分条（展开按钮 + 得分胶囊）。
        // 原先的「本墩捡分 15（A 10 B 5）」是内部队伍代号 + 本墩小计，对玩家没有意义；
        // 玩家真正关心的是"闲家（抓分方）现在总共拿了多少分"，需要明细再展开。
        this.scoreBarNode = new Node('scorebar');
        this.scoreBarNode.layer = 1 << 25;
        this.scoreBarNode.setPosition(0, 288, 0);
        this.node.addChild(this.scoreBarNode);
        this.buildScoreBar();

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

        // 底牌展示位（扣底阶段 / 干锅局公开）。位置必须事先留出来，且要躲开既有元素：
        //   · 中央正中（y≈30）不行 —— 扣底按钮就在 y∈[-56,-4]、x∈[-70,70]（btnNode 在 -30，
        //     按钮 140×52），压上去正好盖住"扣底：选6张"；
        //   · 干锅局虽在出牌，但四家的墩牌行分别落在各自方位（我 -115 / 左右 105 / 上 160），
        //     对家墩牌行底边 ≈137，所以底板顶边收到 112 就互不干扰；
        //   · 再往上会撞对家铭牌（205）与 toast（258）。
        // 干锅局会整段跳过扣底阶段，所以这块区域必须常驻 —— 不能挂在"扣底阶段才显示"的条件里。
        this.bottomNode = new Node('bottom');
        this.bottomNode.layer = 1 << 25;
        this.bottomNode.setPosition(0, 72, 0);
        this.node.addChild(this.bottomNode);

        // 下部操作按钮（y=-30，躲开发调试面板大约 y∈[-100,-200] 区域）
        this.btnNode = new Node('buttons');
        this.btnNode.layer = 1 << 25;
        this.btnNode.setPosition(0, -30, 0);
        this.node.addChild(this.btnNode);

        // 亮主候选栏（y=-110，仅 BIDDING 阶段 active）。
        // 为什么是这个高度：手牌行顶边 -200、我的铭牌顶边 -143，中间只剩 57 单位；
        // 图标高 58 → 中心最高只能到 -114 才不会压住铭牌，取 -110 留 4 单位余量。
        // 这一带在亮主期间是空的（墩牌只在自己出牌后才有，而那时早就不是亮主阶段了）。
        this.bidNode = new Node('bidbar');
        this.bidNode.layer = 1 << 25;
        this.bidNode.setPosition(0, -110, 0);
        this.bidNode.active = false;
        this.node.addChild(this.bidNode);

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
        this.seatRoot[seat] = root;
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

    // ==================== 铭牌附加标记（庄家 / 定主者） ====================

    /**
     * 牌桌标记（"庄"瓦片 + 铭牌金框）用的庄家口径：**以快照为准**。
     *
     * <p>为什么不能沿用 {@link #IN_GAME_PHASES} 那套判据：切庄发生在**结算命令**里 ——
     * 服务端 `setBankerSeat(newBanker)` 之后**立刻** `transitionTo(DEALING)`，
     * 也就是说新一局的 DEALING / BIDDING 快照里 `banker` 已经是**新庄家**了。
     * 旧写法把这两个阶段归到"其余"分支去取 `preSettleBanker`（上一局的庄），
     * 症状正是 Tracy 反馈的：抓分方上台（闲家 ≥120）之后，**下一局开局时"庄"瓦片
     * 仍挂在原来那个庄家的铭牌旁**，要等到扣底阶段才"跳"过去。
     *
     * <p>`preSettleBanker` 只保留给结算面板 —— 面板要按**本局**的庄/抓分队贴标签
     * （见 renderSettlement），那才是这个缓冲存在的唯一理由，不该外溢到牌桌标记上。
     * 这里的回退分支只服务"快照还没给出庄家"的场合：第一局亮主前、出锅后的 ROUND_OVER。
     */
    private tableBanker(s: SnapshotMsgDown): SeatName | null {
        return s.banker ?? this.preSettleBanker;
    }

    /**
     * 给四块铭牌补上"谁是庄家"和"谁定的主"两个标记。
     *
     * <p>为什么需要：原来"庄"只体现在铭牌文字前缀（庄·EAST）与金色边框上，四块小牌子
     * 长得几乎一样，玩家反馈"找半天才能找到谁是庄家"；而定主者（谁亮的主、用了几张
     * 什么牌）在亮主窗口关闭之后就再无痕迹，复盘时只能靠回忆。
     */
    private renderSeatBadges(s: SnapshotMsgDown): void {
        // 牌桌标记以快照为准：新一局的 DEALING / BIDDING 里 banker 已经是新庄家；
        // preSettleBanker 只留给结算面板 —— 见 tableBanker 的说明。
        const banker = this.tableBanker(s);
        const rev = s.reveal;
        for (const seat of TableUI.ALL_SEATS) {
            const isBanker = banker === seat;
            this.renderBankerMark(seat, isBanker ? (s.trump?.level ?? s.level) : null);
            this.renderTrumpMark(seat,
                rev && rev.seat === seat
                    ? { kind: rev.kind, suit: rev.suit, count: rev.count ?? 0 }
                    : null);
        }
    }

    /** 庄家标记：级数瓦片 + "庄"瓦片（传 null = 该家不是庄，清空标记） */
    private renderBankerMark(seat: SeatName, level: number | null): void {
        const sig = level == null ? '' : String(level);
        if (this.seatBankerSig[seat] === sig) return;
        this.seatBankerSig[seat] = sig;

        const old = this.seatBankerNode[seat];
        if (old) {
            old.destroy();
            this.seatBankerNode[seat] = undefined;
        }
        const root = this.seatRoot[seat];
        if (!root || level == null) return;

        const strip = new Node(`banker_${seat}`);
        strip.layer = 1 << 25;
        // 级数瓦片（"庄"旁边那个数字 = 当前打的级数）；14px 让两位数的"10"也放得下
        const lv = createTileNode(TableUI.TILE_H, TableUI.TILE_H,
            TableUI.TILE_BG, TableUI.TILE_BORDER);
        addTileText(lv, String(level), 0, 0, 14, TableUI.TILE_GOLD, TableUI.TILE_H);
        // "庄"瓦片
        const zhuang = createTileNode(TableUI.TILE_H, TableUI.TILE_H,
            TableUI.TILE_BG, TableUI.TILE_BORDER);
        addTileText(zhuang, '庄', 0, 0, 15, new Color(196, 66, 40, 255), TableUI.TILE_H);

        root.addChild(strip);
        this.layoutTileStrip(strip, [lv, zhuang], TableUI.BANKER_MARK[this.relOf(seat)]);
        this.seatBankerNode[seat] = strip;
    }

    /**
     * 定主者标记：按声明内容画 1..3 个小瓦片。
     *
     * <ul>
     *   <li>级牌定主：画 N 个该花色的瓦片（如"两张方片"就是两个♦）—— 张数即定主强度；</li>
     *   <li>3 大王 / 3 小王：画 大×3 / 小×3；</li>
     *   <li>无人亮主由底牌翻出（第二局起）：画一个"底"，说明主花色不是人叫的。</li>
     *   <li><strong>第一局：不画任何瓦片</strong>（见 switch 里的说明）。</li>
     * </ul>
     */
    private renderTrumpMark(seat: SeatName,
                            rev: { kind: string; suit?: string; count: number } | null): void {
        const sig = rev ? `${rev.kind}|${rev.suit ?? '-'}|${rev.count}` : '';
        if (this.seatTrumpSig[seat] === sig) return;
        this.seatTrumpSig[seat] = sig;

        const old = this.seatTrumpNode[seat];
        if (old) {
            old.destroy();
            this.seatTrumpNode[seat] = undefined;
        }
        const root = this.seatRoot[seat];
        if (!root || !rev) return;

        const SIZE = 22;
        const suitTile = (sym: string): Node => {
            const t = createTileNode(SIZE, SIZE, TableUI.TILE_BG, TableUI.TILE_BORDER, 4);
            // 14 → 16：♠/♣ 同为黑色后要靠"尖顶 vs 三圆"区分，14px 时差距偏小；
            // 22×22 的瓦片放得下 16（四周仍留 3px）
            if (sym) addSuitIcon(t, sym, 0, 0, 16, suitColor(sym));
            return t;
        };
        const textTile = (txt: string): Node => {
            const t = createTileNode(SIZE, SIZE, TableUI.TILE_BG, TableUI.TILE_BORDER, 4);
            addTileText(t, txt, 0, 0, 14, TableUI.TILE_GOLD, SIZE);
            return t;
        };

        const tiles: Node[] = [];
        switch (rev.kind) {
            case 'LEVEL_CARDS': {
                const sym = TableUI.SUIT_CHAR[rev.suit ?? ''] ?? '';
                const n = Math.max(1, Math.min(3, rev.count));
                for (let i = 0; i < n; i++) tiles.push(suitTile(sym));
                break;
            }
            case 'FIRST_ROUND_JOKER':
                // 【第一局不画定主标记】第一局的声明只有两种来路（服务端都记为
                // FIRST_ROUND_JOKER）：抢亮 1 张大王，或无人亮时四家翻底牌定庄。
                // 两者都**没有"定主者"这个概念可表达**：
                //   · 庄家就是定主者本身（第一局庄由亮主/翻底产生）→ 与旁边的"庄"瓦片重复；
                //   · 主花色要等亮牌人摸到第一张花色牌才定下来，此刻还没有值，
                //     画个"大"既不是主花色也不是张数，只会让人误读它在说什么。
                // 主花色在顶栏的"主牌+级数"瓦片里已经能看到，这里不画 = 不丢信息。
                break;
            case 'TRIPLE_BIG_JOKER':
                for (let i = 0; i < 3; i++) tiles.push(textTile('大'));
                break;
            case 'TRIPLE_SMALL_JOKER':
                for (let i = 0; i < 3; i++) tiles.push(textTile('小'));
                break;
            default:
                tiles.push(textTile('底'));            // 无人亮主，底牌定主
                break;
        }
        // 没有该画的瓦片（第一局）：直接返回，别留一个空节点挂在铭牌上
        if (tiles.length === 0) return;

        const strip = new Node(`trump_${seat}`);
        strip.layer = 1 << 25;
        root.addChild(strip);
        this.layoutTileStrip(strip, tiles, TableUI.TRUMP_MARK[this.relOf(seat)]);
        this.seatTrumpNode[seat] = strip;
    }

    /** 把一组瓦片按「锚点 + 生长方向」摆好（dir=+1 向右、-1 向左、0 以锚点为中心） */
    private layoutTileStrip(parent: Node, tiles: Node[], mark: { x: number; y: number; dir: number }): void {
        if (tiles.length === 0) return;
        const w = tiles[0].getComponent(UITransform)?.contentSize.width ?? 22;
        const gap = 3;
        const span = tiles.length * w + (tiles.length - 1) * gap;
        for (let i = 0; i < tiles.length; i++) {
            let x: number;
            if (mark.dir === 0) x = mark.x - span / 2 + w / 2 + i * (w + gap);
            else if (mark.dir > 0) x = mark.x + w / 2 + i * (w + gap);
            else x = mark.x - w / 2 - i * (w + gap);
            tiles[i].setPosition(x, mark.y, 0);
            parent.addChild(tiles[i]);
        }
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

    /**
     * 【白屏根因修复】销毁容器下的全部子节点。
     *
     * `Node.removeAllChildren()` **只是解绑父子关系**（child.parent = null），
     * 它并不销毁子节点 —— 组件的 onDestroy 不会执行，于是这些东西永远留在显存里：
     *   · 每个 Graphics 从渲染池领走的 MeshRenderData（顶点/索引缓冲）；
     *   · 每个 UIRenderer 的 MaterialInstance（D3D11 上是 uniform buffer）；
     *   · useSystemFont 的 Label 各自持有的 canvas 生成的贴图。
     * 而 handNode / btnNode 是**每个快照都要重建**的，一局 39 张手牌 × 几百条快照，
     * 显存几分钟内就被啃光 → ANGLE 报
     * `GL_OUT_OF_MEMORY ... ResourceManager11::allocate: Internal D3D11 error:
     *  HRESULT: 0x8007000E: Error allocating Buffer` → 上下文丢失 → 整屏白。
     *
     * 正确顺序：先整体摘除（destroy() 可能是帧末延迟执行，不先摘会出现
     * "旧节点还在树上、新节点已进来"的同帧重叠），再逐个 destroy 走完
     * onDestroy → 把渲染资源还回池子。
     */
    private destroyChildren(parent: Node): void {
        if (parent.children.length === 0) return;
        const kids = parent.children.slice();
        parent.removeAllChildren();
        for (const k of kids) {
            Tween.stopAllByTarget(k);
            const op = k.getComponent(UIOpacity);
            if (op) Tween.stopAllByTarget(op);
            k.destroy();
        }
    }

    /**
     * 【白屏兜底】WebGL 上下文丢失（显存耗尽 / 显卡驱动重置）之后，Cocos 不会
     * 自行重建渲染器，页面会**永久停在白屏**，玩家只能自己想到按 F5。
     * 这里监听 canvas 的 webglcontextlost：提示并自动刷新一次 —— 刷新后
     * NetClient 会重新 join，服务端凭 roomId+playerId 把牌局原样恢复，
     * 是最便宜的自愈路径。用 sessionStorage 记时间戳，避免驱动故障时无限刷新。
     */
    private installContextLostGuard(): void {
        const canvas = game.canvas;
        if (!canvas) return;
        canvas.addEventListener('webglcontextlost', (ev: Event) => {
            ev.preventDefault();   // 不阻止的话浏览器不会再发 restored，也无法重试
            const KEY = 'gunzi.ctxLostAt';
            let last = 0;
            try { last = Number(sessionStorage.getItem(KEY) ?? 0) || 0; } catch { /* 小游戏环境无 sessionStorage */ }
            if (Date.now() - last < 30_000) {
                // 30 秒内已经刷过一次还丢 → 不是偶发，别再刷，交给人工
                this.showToast('显卡渲染上下文丢失，请手动按 F5 刷新', true);
                return;
            }
            try { sessionStorage.setItem(KEY, String(Date.now())); } catch { /* ignore */ }
            this.showToast('显卡渲染上下文丢失，正在自动恢复…', true);
            setTimeout(() => { try { location.reload(); } catch { /* ignore */ } }, 1200);
        }, false);
    }

    private renderAll(): void {
        // 持续记录本局庄家：结算面板要在切庄后仍按"本局"的庄/抓分队贴标签
        const cur = this.snap;
        if (cur?.banker && TableUI.IN_GAME_PHASES.has(cur.phase)) {
            this.preSettleBanker = cur.banker;
        }
        this.renderTop();
        this.renderTrick();
        // 底牌区：与 renderTrick 同样在"收墩停留"期间不该被跳过（它跟墩无关），
        // 所以单独走一遍，不要塞进 renderTrick 的动画分支里。
        this.renderBottom();
        // 铭牌标记（庄家/定主者）单独走一遍：renderTrick 在"收墩停留"期间会提前 return，
        // 挂在它后面会让庄家标记跟着停顿（出牌期间最需要看清谁是庄）。
        if (cur) this.renderSeatBadges(cur);
        this.renderHand();
        this.renderButtons();
        this.renderBid();
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
        // Toast 到期兜底。正常节奏下 toast 由"牌局上下文变化"清掉（见 syncToastContext），
        // 但牌局若卡住（等一个迟迟不来的快照），上下文 key 永远不变 ——
        // 只靠上下文就会让失败红字无限期挂在牌桌上。所以这里再加一道时间闸。
        if (this.toastUntil !== 0 && Date.now() >= this.toastUntil) {
            this.clearToast();
        }
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
            // Label.string 内部按值比较，同一秒内重复赋值是空操作，不会重建贴图
            const txt = `剩 ${sec}s`;
            if (this.timerLabel.string !== txt) this.timerLabel.string = txt;
            const c = sec <= 5 ? TableUI.TIMER_COLOR_WARN : TableUI.TIMER_COLOR_NORMAL;
            if (this.timerLabel.color !== c) this.timerLabel.color = c;
        } else {
            if (this.timerLabel.string !== '即将托管') this.timerLabel.string = '即将托管';
            if (this.timerLabel.color !== TableUI.TIMER_COLOR_OVER) {
                this.timerLabel.color = TableUI.TIMER_COLOR_OVER;
            }
        }
    }

    // ==================== 结算页（T-606） ====================

    /**
     * 结算面板。
     *
     * <p>【它绝不能挡操作】服务端没有 SETTLED 阶段：{@code SettleRoundCommand} 直接
     * SETTLING→DEALING 开新局，所以结算面板弹出的**同一瞬间**下一局已经在发牌，
     * 而亮主窗口（{@code inBidWindow}）恰好覆盖 DEALING —— 玩家要在面板还在的这几秒里
     * 点图标亮主/改主/定主。原实现铺了一整块 alpha 170 的全屏黑幕 + 中央 660×420 面板，
     * 正好把亮主栏（y≈-110）、手牌与按钮区全压暗，玩家"根本不能点击图标"（Tracy 反馈）。
     *
     * <p>所以现在的口径是：
     * <ol>
     *   <li><b>不铺全屏遮罩</b>，只画面板本体 + 外围一圈柔和投影；</li>
     *   <li>面板整体抬到 <b>底部操作区之上</b>（y∈[6,246]，操作区是 y∈[-139,-4]），
     *       只压住对家铭牌/墩牌行那条带 —— 那里在本局刚结束时没有可点元素；</li>
     *   <li>面板节点尺寸 = 面板尺寸，<b>不是</b> 1280×720：点击收起就注册在它身上，
     *       而 Cocos 的触摸分发只给最顶层的命中节点，全屏命中区会把整屏点击都吞掉；</li>
     *   <li>点面板任意处立即收起，且收起后不再被后续快照重建（见 settleDismissed）。</li>
     * </ol>
     *
     * <p>出锅（ROUND_OVER）时没有下一局，面板同样只作展示，玩家可以点掉去按「新局」。
     */
    private renderSettlement(): void {
        const s = this.snap;
        if (!s || !s.settlement) {
            if (this.settleNode) this.destroySettle();
            return;
        }
        // 已经点掉过：不再重建。没有这道闸门就会出现"点掉 → 下一条快照又弹回来"，
        // 而服务端每条命令后都推全量快照，观感上等于关不掉。
        if (this.settleDismissed) {
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

        const PW = TableUI.SETTLE_PANEL_W;
        const PH = TableUI.SETTLE_PANEL_H;
        const overlay = new Node('settlement');
        overlay.layer = 1 << 25;
        overlay.addComponent(UITransform).setContentSize(PW, PH);
        overlay.setPosition(0, TableUI.SETTLE_PANEL_Y, 0);
        const g = overlay.addComponent(Graphics);
        // 外围投影：不铺全屏也能一眼看出这是浮层，边缘也不至于贴在绿绒布上显得糊
        g.roundRect(-PW / 2 - 8, -PH / 2 - 8, PW + 16, PH + 16, 22);
        g.fillColor = new Color(0, 0, 0, 95);
        g.fill();
        // 面板本体（深色底 + 金边；文字全部拆子节点 —— 一个节点只挂一个 UIRenderer）
        g.roundRect(-PW / 2, -PH / 2, PW, PH, 16);
        g.fillColor = new Color(28, 42, 66, 250);
        g.fill();
        g.lineWidth = 3;
        g.strokeColor = new Color(230, 170, 40, 255);
        g.stroke();
        // 整块面板就是"收起"按钮（尺寸已收窄，不会吞掉面板之外的点击）
        overlay.on(Node.EventType.TOUCH_START, () => this.dismissSettle());
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

        this.addSettleText(overlay, `第 ${this.settleGameNumber || s.gameNumber} 局 · 结算`, 0, 96, 22, gold, true);
        this.addSettleText(overlay, `庄家方 ${bankerTeam.join('+')}：${st.bankerScore} 分`, 0, 62, 18, white);
        this.addSettleText(overlay, `抓分方 ${attackerTeam.join('+')}：${st.attackerScore} 分`, 0, 32, 18, white);

        const takesColor = st.attackerTakesBank
            ? new Color(90, 200, 110, 255) : new Color(120, 170, 235, 255);
        this.addSettleText(overlay,
            st.attackerTakesBank ? '抓分方上台！' : '庄家方守住',
            0, -2, 26, takesColor, true);

        let promo = '双方不升级';
        if (st.attackerPromoted) promo = '抓分方升级';
        else if (st.bankerPromoted) promo = '庄家方升级';
        if (st.dugBottom) promo += ' · 抠底！底牌分×2';
        this.addSettleText(overlay, promo, 0, -34, 17, gray);

        const iWin = iAmBankerTeam ? !st.attackerTakesBank : st.attackerTakesBank;
        this.addSettleText(overlay, iWin ? '我方胜利！' : '我方失利', 0, -74, 28,
            iWin ? gold : new Color(130, 135, 145, 255), true);
        // 底部这行同时是"怎么关掉它"的说明：面板整块可点（不是按钮，免得再去挤位置）
        this.addSettleText(overlay,
            roundOver ? '整轮结束（出锅）· 本轮收官，点面板收起后按「新局」'
                : '点面板任意处收起 · 下一局照常发牌亮主，不会被挡',
            0, -106, 13, gray);

        // 弹入：缩放 0.72→1 backOut + 淡入
        overlay.setScale(0.72, 0.72, 1);
        const op = overlay.addComponent(UIOpacity);
        op.opacity = 0;
        tween(overlay).to(0.25, { scale: new Vec3(1, 1, 1) }, { easing: 'backOut' }).start();
        tween(op).to(0.2, { opacity: 255 }).start();
    }

    /** 收起结算面板：点面板 / 到期 / 阶段切走都走这里；点掉的情况额外记下"这一局已看过" */
    private dismissSettle(): void {
        this.settleDismissed = true;
        this.destroySettle();
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

    // ==================== 顶栏第一行（第N局 + 主牌级数图标） ====================

    private renderTop(): void {
        const s = this.snap;
        if (!s) {
            // 【T-703 可诊断连接态】原来只显示"连接中…"，玩家完全不知道连的是谁、
            // 为什么连不上、该找谁——真人真机排查时只能干看着（"什么也做不了"）。
            // 现在把地址与可能原因直接打在顶栏，并配合"重试/新局"常驻按钮。
            const url = this.serverUrl ? describeServerUrl(this.serverUrl) : '(未配置 serverUrl)';
            // 已重试次数也一起显示：真机上"连不上"最常见的原因就是地址不对
            // （小游戏没有 location，只能用构建时注入的地址）。次数在涨 = 一直没连上；
            // 次数归零后又出现 = 连上过再断。两种情况的处理方式完全不同。
            const tries = this.net && this.net.attempts > 1 ? `（已重试 ${this.net.attempts} 次）` : '';
            if (this.lastNetError) {
                this.setTopText(this.lastNetError.includes('座位已被占用')
                    ? `⚠ 入座失败：${this.lastNetError} · 本端 seat=${this.mySeat}，`
                        + `请让两端座位名一致（服务端启动参数 / 页面 ?seat=）`
                    : `⚠ ${this.lastNetError} · ${url}${tries}`);
            } else {
                this.setTopText(this.net?.online
                    ? `已连接 ${url} · 等待服务器数据…`
                    : `连接不上 ${url}（手机需与电脑同一 WiFi；校园网可能禁止设备互访）${tries}`);
            }
            this.setTopTrumpBadge(null);
            this.renderScoreBar(null);
            return;
        }
        const phase = TableUI.PHASE_TEXT[s.phase] ?? s.phase;
        // 【顶栏瘦身】原先还塞了「手牌N 视口WxH」：真机上顶栏一行已被撑满，
        // 右侧还会被调试浮层压住。视口属于排查用的诊断信息 → 移到 Console
        // （按 局/阶段/轮次/手牌数 去重，避免每条快照都刷屏）；手牌数玩家眼前就有。
        const vs = view.getVisibleSize();
        const diagKey = `${s.gameNumber}|${s.phase}|${s.turn}|${(s.yourHand ?? []).length}`;
        if (diagKey !== this.lastDiagKey) {
            this.lastDiagKey = diagKey;
            const trumpCn = s.trump
                ? `${TableUI.SUIT_CN[s.trump.suit] ?? s.trump.suit}${s.trump.level}` : '未定';
            console.log(`[TableUI] 第${s.gameNumber}局 ${phase} 级${s.level} 主${trumpCn} `
                + `庄${s.banker ?? '-'} 轮${s.turn ?? '-'} 手牌${(s.yourHand ?? []).length} `
                + `视口${Math.round(vs.width)}x${Math.round(vs.height)}`);
        }
        // 第一行 = 「第N局 · 主牌」+ 主牌级数图标。
        // 2026-09-18 Tracy：这行里的「出牌」改成「主牌」，并且**恒定**显示主牌、不再显示
        // 阶段词。理由：这行是牌桌上最显眼的"标题位"，该回答的是**稳定**信息
        // （第几局、主牌是什么），而"发牌中/亮主/扣底/出牌/进贡/结算中"是转瞬即逝的
        // 状态 —— 每隔几秒就换一个词，反而把真正要看的"主牌"挤没了。阶段该知道的时候
        // 自然会知道：轮到谁，按钮区就是那个阶段的动作（扣底 / 扣王 / 出牌 / 进贡 / 还贡），
        // 该等谁则由铭牌呼吸高亮表达。
        this.setTopText(this.topTitle(s) + (this.net?.online ? '' : '  ⚠ 离线'));
        this.setTopTrumpBadge(s.trump ? { suit: s.trump.suit, level: s.trump.level } : null);
        // 第二行 = 闲家（抓分方）得分条
        this.renderScoreBar(s);
    }

    /**
     * 第一行标题：**局号 + 主牌**（主牌图标紧跟其后，见 {@link setTopTrumpBadge}）。
     *
     * <p>刻意不随阶段变化（2026-09-18 Tracy：「出牌」应改为「主牌」）。主牌未定时报
     * 「主牌未定」而不是把两个字省掉 —— 发牌/亮主期间这一行总得说明白"为什么后面
     * 没有那个花色瓦片"，否则玩家会以为图标丢了。
     */
    private topTitle(s: SnapshotMsgDown): string {
        return `第${s.gameNumber}局 · ${s.trump ? '主牌' : '主牌未定'}`;
    }

    /** 第一行文字（内容不变时一个字都不动） */
    private setTopText(text: string): void {
        if (this.topTextLabel.string === text) return;
        this.topTextLabel.string = text;
        this.layoutTopBar();
    }

    /**
     * 主牌级数图标（"第N局"后面那个小瓦片）：花色矢量 + 级数数字。
     * 等价于原来的「主方块3」，但一眼可读；未定主时整块隐藏（发牌/亮主阶段还没有主牌）。
     */
    private setTopTrumpBadge(t: { suit: string; level: number } | null): void {
        const sig = t ? `${t.suit}|${t.level}` : '-';
        if (sig === this.trumpBadgeSig) return;
        this.trumpBadgeSig = sig;
        if (this.trumpBadgeNode) {
            this.trumpBadgeNode.destroy();
            this.trumpBadgeNode = null;
        }
        const box = new Node('trumpbadge');
        box.layer = 1 << 25;
        if (t) {
            const sym = TableUI.SUIT_CHAR[t.suit] ?? '';
            const tile = createTileNode(TableUI.TRUMP_TILE_W, TableUI.TILE_H,
                TableUI.TILE_BG, TableUI.TILE_BORDER);
            const color = suitColor(sym);
            // 花色 + 级数同色（像真实牌面「梅花4」那样一眼读出主牌与级数）
            if (sym) addSuitIcon(tile, sym, -12, 0, 19, color);
            addTileText(tile, String(t.level), 12, 0, 19, color, 26);
            box.addChild(tile);
        }
        this.topBarNode.addChild(box);
        this.trumpBadgeNode = box;
        this.layoutTopBar();
    }

    /**
     * 第一行整体居中：「文字 + 图标」作为一个组摆正中间。
     *
     * <p>文字宽度用启发式估算而**不**读 Label 的 UITransform 尺寸：Label 的排版尺寸
     * 要等到渲染帧才写回 UITransform，依赖它会让首帧位置抖动、每帧位置都在变。
     * 估值的误差只表现为整组左右平移几个像素，肉眼不可见。
     */
    private layoutTopBar(): void {
        const textW = TableUI.estimateTextWidth(this.topTextLabel.string, 18);
        const gap = 6;
        const badgeW = this.trumpBadgeNode ? TableUI.TRUMP_TILE_W + gap : 0;
        const total = textW + badgeW;
        this.topTextLabel.node.setPosition(-total / 2 + textW / 2, 0, 0);
        this.trumpBadgeNode?.setPosition(total / 2 - TableUI.TRUMP_TILE_W / 2, 0, 0);
    }

    /** 文字宽度启发式估算（CJK 按 1em、ASCII 按 0.55em、空格 0.35em；只用于居中摆位） */
    private static estimateTextWidth(text: string, size: number): number {
        let w = 0;
        for (let i = 0; i < text.length; i++) {
            const c = text.charCodeAt(i);
            if (c === 32) w += size * 0.35;
            else if (c >= 0x2e80 && c <= 0x9fff) w += size;      // CJK / 标点
            else if (c >= 0xff00 && c <= 0xffef) w += size;      // 全角
            else w += size * 0.55;
        }
        return w;
    }

    // ==================== 顶栏第二行（闲家得分条 + 展开明细） ====================

    /**
     * 得分条静态结构：绿色三角展开按钮 + 棕底"闲家得分"胶囊。
     *
     * <p>胶囊宽度**固定**：分数位数变化（0 → 300）时只改 Label 文本，
     * 底纹与按钮位置完全不动，既省重建也让这张常驻小条不会每分一次就抖一下。
     */
    private buildScoreBar(): void {
        const btnW = 26, capW = 134, gap = 6, H = 26;
        const total = btnW + gap + capW;

        // 展开/收起按钮（绿底 + 黄色三角，样式对齐参考图）
        const btn = new Node('scoretoggle');
        btn.layer = 1 << 25;
        btn.addComponent(UITransform).setContentSize(btnW, H);
        const bg = btn.addComponent(Graphics);
        bg.roundRect(-btnW / 2, -H / 2, btnW, H, 6);
        bg.fillColor = new Color(38, 150, 82, 255);
        bg.fill();
        bg.lineWidth = 1;
        bg.strokeColor = new Color(255, 255, 255, 70);
        bg.stroke();
        const tri = new Node('tri');
        tri.layer = 1 << 25;
        tri.addComponent(UITransform).setContentSize(btnW, H);
        this.scoreToggleG = tri.addComponent(Graphics);
        btn.addChild(tri);
        btn.setPosition(-total / 2 + btnW / 2, 0, 0);
        btn.on(Node.EventType.TOUCH_START, () => this.toggleScorePanel());
        this.scoreBarNode.addChild(btn);
        this.scoreExpandBtn = btn;
        this.redrawScoreToggle(false);

        // 得分胶囊
        const cap = new Node('scorecap');
        cap.layer = 1 << 25;
        cap.addComponent(UITransform).setContentSize(capW, H);
        const cg = cap.addComponent(Graphics);
        cg.roundRect(-capW / 2, -H / 2, capW, H, H / 2);
        cg.fillColor = new Color(88, 56, 30, 235);
        cg.fill();
        cg.lineWidth = 1.5;
        cg.strokeColor = new Color(206, 156, 84, 220);
        cg.stroke();
        const txt = new Node('txt');
        txt.layer = 1 << 25;
        txt.addComponent(UITransform).setContentSize(capW, H);
        const l = txt.addComponent(Label);
        l.string = '闲家得分 0';
        l.fontSize = 15;
        l.lineHeight = 18;
        l.isBold = true;
        l.useSystemFont = true;
        l.color = new Color(255, 226, 170, 255);
        l.horizontalAlign = Label.HorizontalAlign.CENTER;
        l.verticalAlign = Label.VerticalAlign.CENTER;
        cap.addChild(txt);
        cap.setPosition(total / 2 - capW / 2, 0, 0);
        this.scoreBarNode.addChild(cap);
        this.scoreCapsuleLabel = l;

        // 未定庄时第二行整条隐藏（此刻还没有"闲家"这个概念）
        this.scoreBarNode.active = false;
    }

    /** 三角方向：收起 = ◀（可展开）、展开 = ▼（可收起） */
    private redrawScoreToggle(expanded: boolean): void {
        const g = this.scoreToggleG;
        if (!g) return;
        g.clear();
        g.fillColor = new Color(250, 214, 60, 255);
        if (expanded) {
            g.moveTo(0, -5);
            g.lineTo(7, 4);
            g.lineTo(-7, 4);
        } else {
            g.moveTo(5, 6);
            g.lineTo(-5, 0);
            g.lineTo(5, -6);
        }
        g.close();
        g.fill();
    }

    private toggleScorePanel(): void {
        if (this.scoreExpandBtn) {
            // 按下反馈：轻微缩一下再弹回（与其它按钮同一套动效语汇）
            tween(this.scoreExpandBtn).to(0.07, { scale: new Vec3(0.9, 0.9, 1) }, { easing: 'quadOut' })
                .to(0.12, { scale: new Vec3(1, 1, 1) }, { easing: 'backOut' }).start();
        }
        this.scoreExpanded = !this.scoreExpanded;
        this.redrawScoreToggle(this.scoreExpanded);
        this.renderScoreBar(this.snap);
    }

    /**
     * 第二行内容：闲家（抓分方）**总得分**。
     *
     * <p>原来的「本墩捡分 15（A 10 B 5）」有两个毛病：A/B 是内部队伍代号（玩家不认识），
     * 且"本墩小计"对局势判断没有价值 —— 玩家想知道的是"抓分方现在攒了多少分"。
     */
    private renderScoreBar(s: SnapshotMsgDown | null): void {
        const info = s ? this.attackerScoreInfo(s) : null;
        if (!info) {
            if (this.scoreBarNode.active) this.scoreBarNode.active = false;
            this.closeScorePanel();
            return;
        }
        this.scoreBarNode.active = true;
        const text = `闲家得分 ${info.score}`;
        if (this.scoreCapsuleLabel.string !== text) this.scoreCapsuleLabel.string = text;
        if (this.scoreExpanded && s) this.buildScorePanel(info, s);
        else this.closeScorePanel();
    }

    /**
     * 闲家（抓分方）得分 + 已收走的分牌明细。
     *
     * <p>闲家 = 庄家的对家那队（手册：庄家方 vs 抓分方）。庄家未定时返回 null——
     * 发牌/亮主期间还没有"闲家"这个概念，硬显示一个 0 反而误导。
     * 队伍代号（A/B）只用于取数，绝不出现在界面上。
     */
    private attackerScoreInfo(s: SnapshotMsgDown): { score: number; cards: string[] } | null {
        // 用快照里的 banker（结算切庄后就是下一局的庄，与"本局分已清零"同步），
        // 拿不到时才回退到本局庄（开局第一局亮主之前两者都没值 → 整条隐藏）
        const banker = s.banker ?? this.preSettleBanker;
        if (!banker) return null;
        const bankerTeam = (banker === 'NORTH' || banker === 'SOUTH') ? 'A' : 'B';
        const attacker = bankerTeam === 'A' ? 'B' : 'A';
        return {
            score: s.trickPoints?.[attacker] ?? 0,
            cards: s.takenPointCards?.[attacker] ?? [],
        };
    }

    // ==================== 面板：本局特殊事件（干锅 / 扣王 / 进贡） ====================

    /**
     * 本局三个"特殊事件"事实：**干锅 / 扣王 / 进贡**（2026-09-18 Tracy 要求加进得分面板）。
     *
     * <p>三段口径全部读服务端字段，客户端不复算规则：
     * <ul>
     *   <li>干锅 = {@code dryPot}（底牌无主花色普通牌，手册 2.3.7）；</li>
     *   <li>扣王 = {@code jokerBuried}。**不能拿 bottomRevealed 顶替** —— 那个是"底牌摊没
     *       摊开"的可见性口径：干锅局底牌里本来就带着王（发牌发出来的，手册 2.3.7 专门为
     *       "干锅底牌王"立规），可见性为真而实际没人扣过王；</li>
     *   <li>进贡 = 本局有进贡义务（{@code pendingTributes}，还没交）或已有进贡流水
     *       （{@code tributes}）。</li>
     * </ul>
     *
     * <p>抽成纯函数（不碰节点）是为了能在无 UI 环境下断言 —— 面板要点开、要建一堆节点，
     * 而"这三句话报得对不对"才是真正会错的地方（见 tools/ui_smoke_test.js 第 27 段）。
     */
    private roundFactSegments(s: SnapshotMsgDown | null): { text: string; hot: boolean }[] {
        const hasTribute = this.tributesOf(s).length > 0
            || Object.keys(s?.pendingTributes ?? {}).length > 0;
        return [
            { text: `干锅 ${s?.dryPot ? '是' : '否'}`, hot: s?.dryPot === true },
            { text: `扣王 ${s?.jokerBuried ? '是' : '否'}`, hot: s?.jokerBuried === true },
            { text: `进贡 ${hasTribute ? '是' : '否'}`, hot: hasTribute },
        ];
    }

    /**
     * 本局已完成的进贡流水，按座位固定顺序重排。
     *
     * <p>为什么要重排：服务端那份是 Map 序，不值得依赖；而面板走签名制 —— 顺序一变签名
     * 就变，会白白重建一次整个浮层（还可能让玩家正在点的牌落空）。
     */
    private tributesOf(s: SnapshotMsgDown | null): TributeLogMsg[] {
        const raw = s?.tributes;
        if (!raw || raw.length === 0) return [];
        const out: TributeLogMsg[] = [];
        for (const seat of TableUI.ALL_SEATS) {
            for (const t of raw) {
                if (t && t.payer === seat && t.receiver && t.cards) out.push(t);
            }
        }
        return out;
    }

    /**
     * 本局「我作为收贡人还欠谁一次还贡」——收贡人视角的待办。
     *
     * <p>数据源必须是 {@link tributesOf}（**已收贡流水**），**不能用 `pendingTributes`**：
     * 进贡一交上去，`GameRoom.recordTribute` 立刻把这条义务从 `pendingTributes` 摘掉，
     * 所以"谁贡给了我"在 `pendingTributes` 里根本查不到。
     * 2026-09-20 那个真机 bug 有**两层**，这是第二层；第一层是把按钮挂在了服务端
     * 从不发送的 `RETURN_TRIBUTE` 阶段上（见 renderButtons 的 TRIBUTE 分支注释）。
     * 两层缺一不可 —— 只修阶段名而不换数据源，按钮照样出不来。
     *
     * @return 第一位"收了但没还"的进贡人 + 应还张数；不欠则 null
     */
    private pendingReturnOf(s: SnapshotMsgDown | null): { payer: SeatName; count: number } | null {
        for (const t of this.tributesOf(s)) {
            const done = !!t.returned && t.returned.length > 0;
            if (t.receiver === this.mySeat && !done) {
                return { payer: t.payer, count: t.cards.length };
            }
        }
        return null;
    }

    /**
     * 进贡 / 还贡明细行：左边一句话、右边一串牌面。
     *
     * <p>三种行：`X 进贡 → Y` + 贡牌（已交）、`Y 还贡 → X` + 还牌（没还则整行不出现）、
     * `X 待进贡 N 张 → Y`（还没交，只有欠的张数、没有牌面）。
     */
    private tributeRows(s: SnapshotMsgDown | null): { text: string; cards: string[] }[] {
        const rows: { text: string; cards: string[] }[] = [];
        const pending = s?.pendingTributes ?? {};
        for (const seat of TableUI.ALL_SEATS) {
            const ob = pending[seat];
            if (!ob) continue;
            rows.push({
                text: `${this.seatText(seat)} 待进贡 ${ob.blood} 张 → ${this.seatText(ob.receiver as SeatName)}`,
                cards: [],
            });
        }
        for (const t of this.tributesOf(s)) {
            rows.push({
                text: `${this.seatText(t.payer)} 进贡 → ${this.seatText(t.receiver)}`,
                cards: t.cards,
            });
            if (t.returned && t.returned.length > 0) {
                rows.push({
                    text: `${this.seatText(t.receiver)} 还贡 → ${this.seatText(t.payer)}`,
                    cards: t.returned,
                });
            }
        }
        return rows;
    }

    /** 面板里的座位名：是我自己就补一个「(我)」，一眼对上桌面铭牌 */
    private seatText(seat: SeatName): string {
        return seat === this.mySeat ? `${seat}(我)` : seat;
    }

    /**
     * 展开面板：**本局都发生了什么**（干锅 / 扣王 / 进贡与还贡明细）+ 闲家已收走的分牌。
     *
     * <p>为什么做成整屏浮层而不是贴着胶囊的下拉：第二行正下方就是对家铭牌与墩牌区，
     * 任何下拉面板都会盖住对家牌面；这里是"我主动要看的明细"，做成遮罩浮层点哪都能关，
     * 既不会长期挡视线，也不必为腾地方重排整个牌桌。
     *
     * <p>为什么分牌只列"分牌"：一局整圈牌上百张，铺满屏也看不清；玩家要确认的是
     * "这些分具体是哪几张、都收在谁手上"。进贡 / 还贡是一次性的少数几张，所以全列。
     *
     * <p>高度**按内容自适应**（进贡段与分牌段的行数都是可变的）：固定高度要么空一大片，
     * 要么把内容挤出面板底边。所以先算需要多高，再画底板，最后自上而下铺内容。
     */
    private buildScorePanel(info: { score: number; cards: string[] }, s: SnapshotMsgDown): void {
        const segs = this.roundFactSegments(s);
        const tRows = this.tributeRows(s);

        // 分花色成组：每组左边一个花色图标，右边按点数从大到小（K > 10 > 5）列出
        const groups: { suit: string; cards: string[] }[] = [];
        for (const suit of TableUI.SIDE_ORDER) {
            const of = info.cards.filter(c => c[0] === suit);
            if (of.length === 0) continue;
            of.sort((a, b) => parseInt(b.slice(1), 10) - parseInt(a.slice(1), 10));
            groups.push({ suit, cards: of });
        }

        // 签名必须带上"本局事实"：面板开着的时候扣王 / 进贡都可能发生（而分牌还没变），
        // 少这几项就会出现"面板僵着不更新"的假象。
        const sig = [
            info.score, info.cards.join(','),
            segs.map(x => `${x.text}${x.hot ? '!' : ''}`).join(';'),
            tRows.map(r => `${r.text}:${r.cards.join('-')}`).join(';'),
        ].join('|');
        if (this.scorePanelNode && sig === this.scorePanelSig) return;
        this.closeScorePanel();
        this.scorePanelSig = sig;

        // ---- 几何：先按内容算高 ----
        const PW = 880;
        const ROW_H = 52;                       // 迷你牌高 46 + 6 间距，再挤就叠上了
        let need = 34 + 30 + 18 + 22;           // 标题 / 状态行 / 分隔线 / 段前留白
        if (tRows.length > 0) need += 26 + tRows.length * ROW_H + 4 + 22;
        need += 26 + Math.max(groups.length, 1) * ROW_H;
        need += 40;                             // 底部说明 + 底边内边距
        const PH = Math.max(260, Math.min(650, need));

        const overlay = new Node('scorepanel');
        overlay.layer = 1 << 25;
        // 【必须用可见尺寸，不能写死 1280】这块遮罩同时就是"点任意处收起面板"的命中区
        // （TOUCH_START 注册在 overlay 身上），而 Cocos 的命中判定看的是节点的 UITransform
        // 尺寸。写死 1280 的后果：长条屏两侧各 160~200 设计单位既不变暗、**也点不动**，
        // 玩家会以为面板卡死了。可见宽 vw 在 16:9 上正好是 1280，行为不变。
        overlay.addComponent(UITransform).setContentSize(this.vw, this.vh);
        const g = overlay.addComponent(Graphics);
        g.fillColor = new Color(0, 0, 0, 120);
        g.fillRect(-this.vw / 2, -this.vh / 2, this.vw, this.vh);
        g.fillColor = new Color(26, 40, 62, 252);
        g.roundRect(-PW / 2, -PH / 2 - 10, PW, PH, 16);
        g.fill();
        g.lineWidth = 2.5;
        g.strokeColor = new Color(206, 156, 84, 235);
        g.stroke();
        overlay.setPosition(0, 0, 0);
        this.node.addChild(overlay);
        this.scorePanelNode = overlay;
        overlay.on(Node.EventType.TOUCH_START, () => {
            this.scoreExpanded = false;
            this.redrawScoreToggle(false);
            this.closeScorePanel();
        });

        // 分隔线走**独立节点**：Cocos 的 Graphics 路径会累积，在同一支笔上再 stroke 一次
        // 会把面板外框按新的线宽/颜色重描一遍，边框会花。
        const divNode = new Node('divider');
        divNode.layer = 1 << 25;
        divNode.addComponent(UITransform).setContentSize(PW, PH);
        const dg = divNode.addComponent(Graphics);
        dg.lineWidth = 1;
        dg.strokeColor = new Color(206, 156, 84, 90);
        overlay.addChild(divNode);
        const divider = (yy: number): void => {
            dg.moveTo(-PW / 2 + 26, yy);
            dg.lineTo(PW / 2 - 26, yy);
            dg.stroke();
        };

        const gold = new Color(255, 214, 130, 255);
        const sub = new Color(170, 200, 220, 255);

        let y = PH / 2 - 34;
        this.addPanelText(overlay, '本局明细', 0, y, 22, gold, true);
        y -= 30;

        // 状态行：三段并排居中。"发生过"的那段用暖色加粗，其余灰蓝 —— 一眼扫过就知道
        // 本局有没有事，不必逐字读。
        const widths = segs.map(x => TableUI.estimateTextWidth(x.text, 17));
        const dotGap = 20;
        const totalW = widths.reduce((a, b) => a + b, 0) + dotGap * (segs.length - 1);
        let sx = -totalW / 2;
        for (let i = 0; i < segs.length; i++) {
            this.addPanelText(overlay, segs[i].text, sx + widths[i] / 2, y, 17,
                segs[i].hot ? new Color(255, 205, 100, 255) : new Color(150, 165, 180, 255),
                segs[i].hot);
            sx += widths[i] + dotGap;
        }
        y -= 18;
        divider(y);
        y -= 22;

        // ---- 本局进贡 / 还贡（有才显示） ----
        if (tRows.length > 0) {
            this.addPanelText(overlay, '本局进贡 / 还贡', 0, y, 16, sub, false);
            y -= 26;
            for (const row of tRows) {
                this.addPanelRowText(overlay, row.text, -PW / 2 + 34, y, 17,
                    new Color(235, 235, 235, 255), 230);
                for (let k = 0; k < row.cards.length; k++) {
                    const card = createMiniCardNode(row.cards[k]);
                    card.setPosition(-PW / 2 + 300 + k * 34, y, 0);
                    overlay.addChild(card);
                }
                y -= ROW_H;
            }
            y -= 4;
            divider(y);
            y -= 22;
        }

        // ---- 闲家已捡的分牌（一行一门） ----
        this.addPanelText(overlay, `闲家已捡分牌 · 共 ${info.score} 分`, 0, y, 16, sub, false);
        y -= 26;
        if (groups.length === 0) {
            this.addPanelText(overlay, '闲家还没有收到分牌', 0, y, 18,
                new Color(180, 190, 200, 255), false);
        } else {
            // 一行一门：左边花色图标，右边分牌从大到小。单门最多 9 张（三副牌的 5/10/K），
            // 9 × 34 = 306，加上标签位也远在 880 宽之内。
            for (const grp of groups) {
                addSuitIcon(overlay, grp.suit, -PW / 2 + 44, y, 18, this.panelSuitColor(grp.suit));
                for (let k = 0; k < grp.cards.length; k++) {
                    const card = createMiniCardNode(grp.cards[k]);
                    card.setPosition(-PW / 2 + 74 + k * 34, y, 0);
                    overlay.addChild(card);
                }
                y -= ROW_H;
            }
        }

        this.addPanelText(overlay, '点击任意处收起', 0, -PH / 2 + 20, 14,
            new Color(160, 170, 180, 255), false);

        // 弹入：缩放 0.85→1 + 淡入（与结算面板同一套动效语汇）
        overlay.setScale(0.85, 0.85, 1);
        const op = overlay.addComponent(UIOpacity);
        op.opacity = 0;
        tween(overlay).to(0.18, { scale: new Vec3(1, 1, 1) }, { easing: 'backOut' }).start();
        tween(op).to(0.15, { opacity: 255 }).start();
    }

    private closeScorePanel(): void {
        if (!this.scorePanelNode) return;
        const op = this.scorePanelNode.getComponent(UIOpacity);
        if (op) Tween.stopAllByTarget(op);
        Tween.stopAllByTarget(this.scorePanelNode);
        this.scorePanelNode.destroy();
        this.scorePanelNode = null;
        this.scorePanelSig = '';
    }

    /**
     * 面板内的花色图标配色：**不能直接用 {@link suitColor}**。
     *
     * <p>牌桌上的牌体是近白色（253,253,252），♠/♣ 用近黑（30,30,30）在牌面上清清楚楚；
     * 但面板底板是深蓝（26,40,62），近黑图标贴上去对比度只有 1.05:1 —— 等于没画
     * （2026-09-18 自查发现）。所以面板内换成"提亮版"：♥/♦ 用亮红、♠/♣ 用浅灰白。
     * 只改这个浮层的取值，牌桌与手牌上的花色一律照旧，避免误伤已验收的牌面。
     */
    private panelSuitColor(suit: string): Color {
        return (suit === 'H' || suit === 'D')
            ? new Color(232, 84, 84, 255)      // 亮红：深蓝底上够扎眼
            : new Color(226, 232, 240, 255);   // 浅灰白：♠/♣ 的"黑"在深底上要反过来提亮
    }

    /** 浮层面板内的一行文字（子节点，避开单 UIRenderer 限制） */
    private addPanelText(parent: Node, text: string, x: number, y: number,
                         size: number, color: Color, bold: boolean): void {
        const n = new Node('line');
        n.layer = 1 << 25;
        n.addComponent(UITransform).setContentSize(660, size + 8);
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

    /**
     * 浮层面板内的**左对齐**一行（行首钉在 x0，不居中）。
     *
     * <p>只有进贡/还贡那几行需要它：它们左边是"谁贡给谁"、右边要跟一串牌面，用居中的
     * {@link addPanelText} 会把文字压到牌面上。Cocos 的 Label 按锚点居中摆放，所以
     * 想让文字从 x0 开始，节点 x 得落在「x0 + maxW/2」。
     */
    private addPanelRowText(parent: Node, text: string, x0: number, y: number,
                           size: number, color: Color, maxW: number): void {
        const n = new Node('rowtext');
        n.layer = 1 << 25;
        n.addComponent(UITransform).setContentSize(maxW, size + 8);
        const l = n.addComponent(Label);
        l.string = text;
        l.fontSize = size;
        l.lineHeight = size + 4;
        l.color = color;
        l.isBold = false;
        l.useSystemFont = true;
        l.horizontalAlign = Label.HorizontalAlign.LEFT;
        l.verticalAlign = Label.VerticalAlign.CENTER;
        n.setPosition(x0 + maxW / 2, y, 0);
        parent.addChild(n);
    }

    // ==================== 墩牌（差量动画） ====================

    private renderTrick(): void {
        const s = this.snap;
        if (!s) return;

        // 铭牌：座位名 + 余牌数（庄家口径见 tableBanker —— 以快照为准）。
        // "庄"不再写进文字里：文字版"庄·EAST(32)"一桌四块牌子长得几乎一样，
        // 找庄家得逐字读 → 改成铭牌上方/旁边的金色瓦片标记（见 renderSeatBadges），
        // 铭牌本身仍保留金色边框，双重提示。
        const bankerNow = this.tableBanker(s);
        for (const seat of TableUI.ALL_SEATS) {
            const cnt = s.hands?.[seat];
            const name = seat === this.mySeat ? '我' : seat;
            const txt = `${name}${cnt != null ? `(${cnt})` : ''}`;
            if (txt !== this.plateText[seat]) {
                this.plateText[seat] = txt;
                this.drawPlate(seat, txt, bankerNow === seat);
            }
        }

        let plays = this.extractTrickPlays(s);
        // 服务端可能仍在下发刚被收掉的上一墩（currentTrick 已清空、新领牌还没到），
        // 直接当空墩处理，避免收完的旧墩重新飞回桌面。
        if (plays.length > 0 && this.trickKey(plays) === this.trickDismissedKey) {
            plays = [];
        }

        // 四张已出齐、正在停留展示：期间忽略新的出牌快照，到点后统一收墩。
        if (this.trickHoldScheduled) {
            if (s.phase !== 'PLAYING') {
                // 离开出牌阶段（结算 / 开新局）：撤销停留，避免定时器到点后动到新状态的牌
                this.trickHoldScheduled = false;
                this.trickHoldWinner = null;
                this.trickHoldKey = '';
                this.trickDismissedKey = '';
            } else {
                return;
            }
        }

        // 离开出牌阶段（结算 / 新局）：桌上若还有牌，直接收向赢家，不留到下一阶段
        if (s.phase !== 'PLAYING' && this.trickShown.size > 0) {
            this.collectTrick(s.trick?.winner ?? this.trickHoldWinner ?? s.turn ?? null);
            this.trickShown.clear();
            plays = [];
        }

        if (plays.length === 0) {
            // 墩已消失：把仍在桌上的牌整墩收向赢家（兜底路径）
            if (this.trickShown.size > 0) {
                this.collectTrick(this.trickHoldWinner ?? s.turn ?? null);
            }
            this.trickShown.clear();
            return;
        }

        // 差量渲染：新增/变化的座位飞入，消失的座位清掉
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

        // 刚凑齐 4 张：让四张牌在桌上停留 TRICK_DWELL_MS 秒，再整墩收向赢家，
        // 然后才按最新快照显示下一墩（下一墩领牌若在停留期间到达，会先被忽略）。
        //
        // 赢家优先取服务端下发的 trick.winner。不能直接用 turn：服务端出完一圈后
        // 会把 turn 设为赢家，但下一圈领牌一到，turn 就变成"下一个跟牌人"了。
        if (s.phase === 'PLAYING' && plays.length >= 4) {
            const key = this.trickKey(plays);
            if (key !== this.trickDismissedKey) {
                this.trickHoldScheduled = true;
                this.trickHoldWinner = s.trick?.winner ?? s.turn ?? null;
                this.trickHoldKey = key;
                const self = this;
                this.scheduleOnce(() => {
                    self.trickHoldScheduled = false;
                    const winner = self.trickHoldWinner;   // 先捕获，再置空
                    self.trickHoldWinner = null;
                    const heldKey = self.trickHoldKey;
                    self.trickHoldKey = '';
                    // 停留期间若已离开出牌阶段（如开新一局 / 结算），不收墩
                    if (!self.snap || self.snap.phase !== 'PLAYING') return;
                    self.collectTrick(winner);     // 整墩四张收向赢家
                    self.trickShown.clear();
                    self.trickDismissedKey = heldKey;
                    const nowPlays = self.extractTrickPlays(self.snap);
                    if (nowPlays.length > 0 && self.trickKey(nowPlays) === heldKey) {
                        // 停留期间下一墩还没开牌：服务端仍发同一墩，不要再飞回来
                        return;
                    }
                    self.renderTrick();            // 此时快照已是新墩状态 → 飞入新牌
                }, TableUI.TRICK_DWELL_MS / 1000); // Cocos scheduleOnce 以秒为单位
            }
        }
    }

    /** 从快照提取“座位 → 出牌牌组”列表（把首出者也作为一条 plays） */
    private extractTrickPlays(s: SnapshotMsgDown): Array<{ seat: SeatName; cards: string[] }> {
        const plays: Array<{ seat: SeatName; cards: string[] }> = [];
        if (s.trick?.plays && s.trick.plays.length > 0) {
            for (const p of s.trick.plays) {
                plays.push({ seat: p.seat as SeatName, cards: p.cards });
            }
        } else if (s.trick?.leader && s.trick.leadCards) {
            plays.push({ seat: s.trick.leader as SeatName, cards: s.trick.leadCards });
        }
        return plays;
    }

    /** 墩牌 key：用于识别服务端快照里的 trick 是不是同一墩 */
    private trickKey(plays: Array<{ seat: SeatName; cards: string[] }>): string {
        return plays.map(p => `${p.seat}:${p.cards.join(',')}`).join('|');
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
                // 先停掉可能还在跑的飞入 tween，避免两个 tween 同时改 position/opacity
                Tween.stopAllByTarget(nd);
                const delay = (i++) * 0.04;
                const op = nd.getComponent(UIOpacity) ?? nd.addComponent(UIOpacity);
                Tween.stopAllByTarget(op);
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

    // ==================== 底牌区（公开展示 / 我的底牌回看） ====================

    /**
     * 底牌展示区。两种形态，**都由服务端字段驱动，客户端不做规则判断**：
     *
     * <ol>
     *   <li><b>公开展示</b>（快照有 {@code bottom}）：原 6 张摊开摆给所有人看。
     *       扣底阶段（手册 2.3.1"发牌或反主结束后底牌公开"）与干锅局全程如此
     *       —— 干锅整段跳过扣底阶段，不在出牌阶段补发就永远看不到那 6 张（手册 2.3.7）。</li>
     *   <li><b>我的底牌</b>（快照有 {@code myBottom}，只有庄家拿得到）：扣底成功后
     *       原 6 张已被换成庄家扣下的新 6 张，对别人是机密（手册 2.3.3），但庄家自己
     *       必须能回看 —— 否则"我扣了哪 6 张"就无从查证。这一态默认**折叠**成一枚胶囊，
     *       点一下才摊开（常驻摊开会挡住对家墩牌行那条带）。</li>
     * </ol>
     *
     * <p>【为什么一块固定区域】原 6 张底牌在扣底阶段是**公开信息**，但客户端原先完全
     * 没有它的位置：手牌区只画 {@code yourHand}，而庄家收底后这 6 张已并进手牌 ——
     * 看起来就只是"我有 45 张"，另外三家更是从头到尾没见过底牌。
     *
     * <p>一旦客户端自己判断"该不该公开/能不能看"，就又多出一份会跟服务端走散的规则
     * （底牌公开与否牵涉扣王、进贡、干锅好几条），这正是本项目反复踩坑的地方 ——
     * 所以两种形态**只认服务端下没下发字段**，公开优先。
     *
     * <p>结算阶段不用管：结算面板（中心 y=126、660×240）正好把本区整个盖住，
     * 不会出现"面板边缘漏出半截底牌"的叠影。干锅局的底牌在面板收起后还在。
     */
    private renderBottom(): void {
        const s = this.snap;
        const pub = s?.bottom ?? [];
        const mine = s?.myBottom ?? [];
        const dry = s?.dryPot === true;
        // 公开优先：干锅局庄家会同时拿到 bottom（公开）与 myBottom（私有），
        // 两者是同一份牌面，只画公开那一态，别在桌面上摆两块。
        const isPublic = pub.length > 0;
        const codes = isPublic ? pub : mine;
        // 扣王窗口开着、底牌还不可见（还没人扣王）→ 这块区域没有牌面可画，只剩提示条。
        // 不能因为"没牌可画"就整块空掉：底牌区一片空白时，玩家既不知道牌局正停在扣王窗口，
        // 也不知道该不该去点下面那两个按钮。
        const pickHint = !isPublic && codes.length === 0 && s?.pickSeat != null;
        const mode = isPublic ? 'pub' : (codes.length > 0 ? 'mine' : (pickHint ? 'hint' : ''));
        // 形态切换时复位展开态：公开态本来就全摊开，切回私有态要从折叠重新开始，
        // 否则会出现"上一局展开过、这一局一进来就摊着"的莫名状态。
        if (mode !== this.bottomMode) {
            this.bottomMode = mode;
            this.bottomExpanded = false;
        }
        // 标签说清两件事：这块是什么（底牌）、现在该谁做什么。
        // 四态文案：干锅 / 轮到我扣王 / 已扣王公开 / 扣底阶段看完就收。
        // 扣王窗口那两态与"扣完收起"的牌面可能完全相同，差别只在 pickSeat 与
        // bottomRevealed，所以这两个都得进签名，否则文案不刷新。
        const revealed = s?.bottomRevealed === true;
        const myPick = s?.pickSeat === this.mySeat;
        // 窗口开着但轮的是别人：这时还没人扣（扣了就直接收口），别提前说"已扣王"。
        const picker = s?.pickSeat != null && !myPick
            ? TableUI.REL_TEXT[this.relOf(s.pickSeat)]
            : null;
        const warm = dry || revealed || myPick;
        const sig = `${mode}|${codes.join('.')}|${dry ? 'dry' : ''}|${revealed ? 'rev' : ''}`
            + `|pick:${s?.pickSeat === this.mySeat ? 'me' : (s?.pickSeat ?? '-')}:${s?.pickMax ?? 0}`
            + `|${!isPublic && this.bottomExpanded ? 'open' : 'fold'}`;
        if (sig === this.bottomSig) return;
        this.bottomSig = sig;
        this.destroyChildren(this.bottomNode);
        if (codes.length === 0) {
            if (pickHint) {
                this.renderPickHint(myPick, s?.pickMax ?? 0, picker);
            }
            return;
        }
        // 私有态的两块表现（胶囊 / 摊开的牌）都在下方画，这里只处理公开态
        if (!isPublic) {
            this.renderMyBottom(codes);
            return;
        }

        // 标签说清两件事：这块是什么（底牌）、现在该谁做什么 ——
        // 扣王窗口全靠这块区域提示，玩家一眼要知道"轮到我了、最多能押几张"。
        // 五态：干锅 / 轮到我扣王 / 等某家表态 / 已扣王（本局公开）/ 看完就收。
        const title = dry
            ? '底牌 · 干锅（原样扣回，本局公开）'
            : myPick
                ? `底牌 · 轮到你扣王（最多 ${s?.pickMax ?? 0} 张，或点「跳过」）`
                : picker
                    ? `底牌 · 等${picker}决定是否扣王（本局公开）`
                    : revealed
                        ? '底牌 · 已扣王（本局公开到结算）'
                        : '底牌（明牌 · 扣完收起）';
        this.drawBottomPlate(codes, title,
            warm ? TableUI.TOAST_COLOR_INFO : TableUI.BOTTOM_TITLE_COLOR);
    }

    /**
     * 庄家私有底牌（扣底后的回看区）。两种形态：
     * <ul>
     *   <li><b>折叠</b>（默认）：一枚小胶囊，只占 30 高度，不压对家墩牌行；</li>
     *   <li><b>展开</b>：与公开态同样铺底板 + 6 张迷你明牌，标签提示"点一下收起"。</li>
     * </ul>
     * 交互挂在 <b>TOUCH_START</b>：真机手指轻微滑动会把 TOUCH_END 变成 TOUCH_CANCEL，
     * 只在 END 触发会表现为"点了没反应"（与 makeButton / 手牌选牌同一处理）。
     */
    private renderMyBottom(codes: string[]): void {
        if (!this.bottomExpanded) {
            const W = 260, H = 30;
            const cap = new Node('mybottom_toggle');
            cap.layer = 1 << 25;
            cap.addComponent(UITransform).setContentSize(W, H);
            const g = cap.addComponent(Graphics);
            g.roundRect(-W / 2, -H / 2, W, H, H / 2);
            g.fillColor = new Color(8, 34, 26, 225);
            g.fill();
            g.lineWidth = 1.5;
            g.strokeColor = new Color(200, 165, 70, 150);
            g.stroke();
            cap.setPosition(0, -6, 0);
            const l = this.makeLabelNode(`我的底牌 ${codes.length} 张 · 点开回看`, 14,
                TableUI.BOTTOM_TITLE_COLOR);
            // 文字节点必须拆出去：一个节点只能挂一个 UIRenderer（Graphics 与 Label 不共存）
            l.getComponent(UITransform)!.setContentSize(W - 20, H);
            l.setPosition(0, 0, 0);
            cap.addChild(l);
            cap.on(Node.EventType.TOUCH_START, () => this.toggleMyBottom());
            this.bottomNode.addChild(cap);
            return;
        }
        this.drawBottomPlate(codes, '我的底牌 · 点一下收起（只有我能看到）',
            TableUI.BOTTOM_TITLE_COLOR);
        // 摊开态要能点回去：再铺一层透明命中区盖住整块底板（Graphics 只画边框，
        // 不铺色，视觉上仍是那块底板）。
        const hit = new Node('mybottom_hit');
        hit.layer = 1 << 25;
        hit.addComponent(UITransform).setContentSize(400, 130);
        hit.setPosition(0, -6, 0);
        hit.on(Node.EventType.TOUCH_START, () => this.toggleMyBottom());
        this.bottomNode.addChild(hit);
    }

    /** 折叠/展开「我的底牌」。只改本地状态 + 重画本区，不碰任何服务端状态 */
    private toggleMyBottom(): void {
        this.bottomExpanded = !this.bottomExpanded;
        // sig 里含展开态，重画时会自然重建（这里不必手动清 bottomSig）
        this.renderBottom();
    }

    /**
     * 扣王窗口的「无牌面」提示条。
     *
     * <p>窗口开着但还没人扣王时，底牌仍是庄家扣回去的那份机密 —— 手册 2.3.5 要求的是
     * "扣王**时**公开"，公开是押中的后果而不是开窗的前提。所以这块区域没有牌面可画，
     * 但绝不能就此整块空掉：底牌区一片空白，玩家既看不出牌局正停在扣王窗口，
     * 也不知道下面那两个按钮为什么冒出来。这里退化成一条胶囊，只讲"轮到谁、最多押几张"，
     * 一个字的牌面都不透。
     */
    private renderPickHint(myPick: boolean, pickMax: number, picker: string | null): void {
        const text = myPick
            ? `底牌已扣回 · 轮到你扣王（最多 ${pickMax} 张，或点「跳过」）`
            : `底牌已扣回 · 等${picker ?? '对家'}决定是否扣王`;
        const W = myPick ? 430 : 360, H = 34;
        const cap = new Node('pick_hint');
        cap.layer = 1 << 25;
        cap.addComponent(UITransform).setContentSize(W, H);
        const g = cap.addComponent(Graphics);
        g.roundRect(-W / 2, -H / 2, W, H, H / 2);
        g.fillColor = new Color(8, 34, 26, 225);
        g.fill();
        g.lineWidth = 1.5;
        // 轮到自己时用暖金边 —— 与公开态标题同一套"该你动了"的视觉提示
        g.strokeColor = myPick ? new Color(230, 190, 90, 190) : new Color(120, 160, 145, 130);
        g.stroke();
        cap.setPosition(0, -6, 0);
        const l = this.makeLabelNode(text, 14,
            myPick ? TableUI.TOAST_COLOR_INFO : TableUI.BOTTOM_TITLE_COLOR);
        // 文字节点必须拆出去：一个节点只能挂一个 UIRenderer（Graphics 与 Label 不共存）
        l.getComponent(UITransform)!.setContentSize(W - 20, H);
        l.setPosition(0, 0, 0);
        cap.addChild(l);
        this.bottomNode.addChild(cap);
    }

    /**
     * 摊开 6 张底牌：底板 + 迷你明牌 + 顶部标签（公开态与私有展开态共用）。
     * 位置与尺寸都不变 —— 与公开态视觉一致，玩家不需要学第二套摆法。
     */
    private drawBottomPlate(codes: string[], titleText: string, titleColor: Color): void {
        const spacing = TableUI.BOTTOM_CARD_SPACING;
        const cardW = 32 * TableUI.BOTTOM_CARD_SCALE;
        const totalW = (codes.length - 1) * spacing + cardW;

        // 底板：不铺一块的话 6 张小白牌直接浮在深绿绒布上，和墩牌区分不开
        const plate = createTileNode(totalW + 30, 92,
            new Color(8, 32, 24, 205), new Color(200, 165, 70, 95), 12);
        plate.setPosition(0, -6, 0);
        this.bottomNode.addChild(plate);

        const left = -totalW / 2 + cardW / 2;
        for (let i = 0; i < codes.length; i++) {
            const c = createMiniCardNode(codes[i]);
            c.setScale(TableUI.BOTTOM_CARD_SCALE, TableUI.BOTTOM_CARD_SCALE, 1);
            c.setPosition(left + i * spacing, -14, 0);
            this.bottomNode.addChild(c);
        }

        const title = this.makeLabelNode(titleText, 14, titleColor);
        title.getComponent(UITransform)!.setContentSize(totalW + 200, 20);
        title.setPosition(0, 26, 0);
        this.bottomNode.addChild(title);
    }

    // ==================== 手牌 ====================

    private renderHand(): void {
        const hand = this.snap?.yourHand ?? [];
        // 签名 = 手牌内容 + 顺序（服务端定主前后会重排，顺序变化同样要重建）。
        // 选中态不参与签名：单击是 toggleSelect 单张改的，不需要整手重建。
        const sig = hand.join(',');
        if (sig === this.handSig) return;
        // 【逐张发牌 · 节流】发牌期间手牌每 60ms 就多一张，若逐张重建，9 秒内要拆建
        // 上百次（每次最多 39 张牌 × Graphics + 系统字贴图）——正是白屏那次的压力模型。
        // 这里在发牌阶段把重建节流到约 220ms 一次（观感上仍是"一把把进牌"），
        // 牌发完（phase 离开 DEALING）后的第一条快照会立刻补上最终手牌。
        if (this.snap?.phase === 'DEALING') {
            const now = Date.now();
            if (now - this.lastHandBuildAt < 220) return;
            this.lastHandBuildAt = now;
        }
        this.handSig = sig;
        this.destroyChildren(this.handNode);   // 【白屏必修】必须 destroy，不能只 removeAllChildren
        this.handNodes.clear();
        this.handBaseY.clear();
        const n = hand.length;
        if (n === 0) return;

        // 【T-701 手牌分两行】三副牌每人 39 张、牌宽 56：
        // 单行 spacing = (1100-56-gapTotal)/38 ≈ 25 → 每张只露出 45%，
        // 命中区真机只有约 17pt（iOS 建议 ≥44pt）→ 既看不清也点不准。
        // 分两行后每行约 20 张，spacing 顶到上限 44 → 露出 78%、命中区约 30pt。
        const cardW = 56;
        const NICE = cardW - 12;                    // 44：再密就看不清牌面
        const gap = 16;                             // 组间额外间隙
        const maxSpread = Math.min(1500, this.vw - 60);
        const order = this.sortHand(hand);

        // 一行超过 23 张就开始挤（间距跌破 44），再多就分两行
        const rows = n > 23 ? 2 : 1;
        let split = n;
        if (rows === 2) {
            // 切分点优先落在花色组边界上，避免同一堆牌被拆到两行
            const mid = Math.ceil(n / 2);
            let best = mid, bestDist = 99;
            for (let i = Math.max(1, mid - 4); i <= Math.min(n - 1, mid + 4); i++) {
                if (this.groupOf(hand[order[i]]) !== this.groupOf(hand[order[i - 1]])) {
                    const d = Math.abs(i - mid);
                    if (d < bestDist) { bestDist = d; best = i; }
                }
            }
            split = best;
        }

        // 两行：前半（主牌堆在前）在上行 +6，后半在下行 -62。行距 68 < 牌高 80，
        // 重叠 12 单位让两行像紧贴的一摞；FIXED_HEIGHT 下可见 y∈±360，上下都不越界。
        //
        // 【下行为什么从 -70 抬到 -62】手牌挂在 y=-240 的 handNode 上，下行底边原本落在
        // 全局 -350，距屏幕底只剩 10 单位 —— iPhone 横屏的 Home 指示条正好压在最下沿。
        // 想"整体上移"其实**没有空间**：上行顶边 -194，再往上 9 单位就碰到自己铭牌的
        // 呼吸光环（中心 -160、含光环半高 25 → 底边 -185）。所以只能压行距：**只把下行
        // 抬 8 单位**，底边回到 -342（余量 18 单位），上行一步不动，不引入任何新碰撞。
        // 代价：两行重叠由 4 → 12 单位（单张牌高 80，遮挡 15%；牌面信息在顶部，可接受）。
        // 注：想彻底避开 iOS 安全区（横屏底部约 21pt ≈ 38 设计单位）必须改版式
        //（缩牌或挪铭牌），不在本次范围 —— 真机上先看这 8 单位够不够。
        const rowDefs: number[][] = rows === 2 ? [order.slice(0, split), order.slice(split)] : [order];
        const rowY: number[] = rows === 2 ? [6, -62] : [0];
        for (let r = 0; r < rowDefs.length; r++) {
            this.layoutHandRow(rowDefs[r], rowY[r], hand, cardW, NICE, gap, maxSpread);
        }
    }

    /** 摆一行手牌：间距按本行张数独立算（两行时每行都够松）。 */
    private layoutHandRow(idxs: number[], baseY: number, hand: string[], cardW: number,
                          nice: number, gap: number, maxSpread: number): void {
        const m = idxs.length;
        if (m === 0) return;
        let gapTotal = 0;
        for (let i = 1; i < m; i++) {
            if (this.groupOf(hand[idxs[i]]) !== this.groupOf(hand[idxs[i - 1]])) gapTotal += gap;
        }
        const spacing = m > 1 ? Math.min(nice, (maxSpread - cardW - gapTotal) / (m - 1)) : cardW;
        const total = spacing * (m - 1) + gapTotal;
        let x = -total / 2;
        let prevGroup = -1;
        for (let i = 0; i < m; i++) {
            const idx = idxs[i];
            const g = this.groupOf(hand[idx]);
            if (i > 0 && g !== prevGroup) x += gap;   // 换组加间隙
            prevGroup = g;
            const card = createCardNode(hand[idx]);
            // 复合身份 "code#occurrence"：快照重排不影响，三副牌重复代码也能区分
            const key = this.cardKey(hand, idx);
            const selected = this.selected.indexOf(key) >= 0;
            this.handBaseY.set(key, baseY);
            card.setPosition(x, baseY + (selected ? 12 : 0), 0);
            if (selected) drawCardBg(card, true);
            // 命中区缩到 spacing 宽，子节点必须一起缩（见 MEMORY 铁律）
            const hitW = Math.min(spacing, cardW);
            card.getComponent(UITransform)!.setContentSize(hitW, 80);
            for (const child of card.children) {
                const cut = child.getComponent(UITransform);
                if (cut) cut.setContentSize(hitW, cut.contentSize.height);
            }
            card.on(Node.EventType.TOUCH_START, () => {
                tween(card).to(0.08, { scale: new Vec3(0.94, 0.94, 1) }, { easing: 'quadOut' }).start();
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
        const baseY = this.handBaseY.get(key) ?? 0;   // 两行后基础 y 不再是 0
        tween(card).to(0.12, { position: new Vec3(card.position.x, baseY + (!on ? 12 : 0), 0) },
            { easing: 'backOut' }).start();
        // 选中变化会影响按钮文案（"出牌：先选牌" → "出牌"）
        this.renderButtons();
    }

    // ==================== 手牌排序（主牌一堆 + 副牌按花色分堆） ====================

    /** 服务端花色名 → 牌编码首字符 */
    private static readonly SUIT_CHAR: Record<string, string> = {
        SPADE: 'S', HEART: 'H', DIAMOND: 'D', CLUB: 'C',
    };
    /**
     * 服务端花色名 → 中文（顶栏主牌展示用）。
     * 原先顶栏取 `suit[0]` 得到的是字母（显示成「主S3」），玩家得自己反应 S=黑桃；
     * 而且字母 S / C 本身就容易混淆（与任老师反馈的 ♠♣ 难分同源）。
     */
    private static readonly SUIT_CN: Record<string, string> = {
        SPADE: '黑桃', HEART: '红桃', DIAMOND: '方块', CLUB: '梅花',
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
        const s = this.snap;
        const online = this.net?.online === true;
        // 按钮集合签名：任何影响「按钮数量 / 文案 / 回调参数」的状态都要进签名，
        // 否则会出现"该变的没变"。加了守卫后，快照洪流不会再反复拆建按钮区。
        const sig = this.buttonSignature(online, s);
        if (sig === this.btnSig) return;
        this.btnSig = sig;

        // 旧按钮可能有按压动画在跑：destroyChildren 内部会先停 tween 再销毁
        // 【白屏必修】必须 destroy，removeAllChildren 不释放按钮的 Graphics / Label 资源
        this.destroyChildren(this.btnNode);

        // 【T-703 常驻自救按钮】不能等 this.snap 到位才创建按钮：
        // 之前 `if (!s) return` 会让"还没收到第一帧/连不上"时整个按钮区空白，
        // 玩家在手机上完全无操作可做（"感觉什么也做不了"）。
        // 未连上 → "重试连接"（强制退出退避、立即重连）；已连上 → "新局"。
        this.makeButton(online ? '新局' : '重试连接', 420, () => {
            this.resetSelection();
            if (online) { this.net?.sendCmd('NEWGAME'); } else { this.net?.reconnectNow(); }
        });

        if (!s) return;
        const myTurn = s.turn === this.mySeat;
        switch (s.phase) {
            case 'BIDDING':
                // 亮主交互整体搬到手牌上方的图标栏了（见 renderBid）：
                // 点亮的图标点一下 = 亮主，不再需要"① 先选牌 → ② 再点四个花色按钮"两段式，
                // 也不再需要"确认定主"——服务端 RoomActor.stepBidding 在 4 家都不再亮/反时
                // 会自动 CONFIRM 收口进扣底（有人亮过会把 bidPasses 归零重开反主窗口）。
                break;
            case 'BURYING': {
                // ---- 他人捡牌扣王（手册 2.3.5）：轮到自己时给「扣王 / 跳过」 ----
                // 扣王 = 把自己手牌中的王扣进底牌，再从底牌捡回同样张数的最小非分牌；
                // 能不能扣、能扣几张全由服务端判（pickMax），这里只负责把意图发出去。
                if (s.pickSeat === this.mySeat) {
                    const max = s.pickMax ?? 0;
                    const picked = this.selected.length;
                    this.makeButton(picked > 0 ? `扣王(${picked}张)` : '扣王：选王', -110, () => {
                        // 防超时误点：按钮渲染后到点击之间，窗口可能已经轮到下一家
                        if (this.snap?.phase !== 'BURYING' || this.snap.pickSeat !== this.mySeat) {
                            this.showToast('扣王窗口已经过去了', true);
                            this.resetSelection();
                            return;
                        }
                        // 【必修 · 2026-09-18】校验的对象必须是**牌码**，不能是 selected 里的键。
                        // selected 存的是复合身份 `code#occurrence`（见 cardKey()），
                        // 拿它去喂 isJokerCode（严格比对 'BJ'/'SJ'）恒为 false ——
                        // 症状就是"选了大王也提示只能扣王"，怎么点都发不出去。
                        // 这里先剥后缀，判的正是即将发给服务端的那串码，本地口径与服务端一致。
                        const pickedCodes = this.selectedCodes();
                        if (this.selected.length === 0) {
                            this.showToast('先点选要扣进底牌的王', true);
                            return;
                        }
                        if (pickedCodes.some(c => !TableUI.isJokerCode(c))) {
                            this.showToast('底牌里只能扣王（大王 / 小王）', true);
                            return;
                        }
                        if (this.selected.length > max) {
                            this.showToast(
                                `底牌里可捡的牌只有 ${max} 张，最多扣 ${max} 张`, true);
                            return;
                        }
                        this.net?.sendCmd('PICK_JOKER', { cards: this.selectedCodes() });
                        this.resetSelection();
                    });
                    this.makeButton('跳过', 110, () => {
                        if (this.snap?.phase !== 'BURYING' || this.snap.pickSeat !== this.mySeat) {
                            this.showToast('扣王窗口已经过去了', true);
                            this.resetSelection();
                            return;
                        }
                        // 不带 cards = 本轮不扣，把表态权让给下一家（服务端按空列表处理）
                        this.net?.sendCmd('PICK_JOKER');
                        this.resetSelection();
                    });
                    break;
                }
                if (s.banker === this.mySeat) {
                    // 扣底只在"还没扣完"时给。扣王窗口一旦开着，说明庄家这 6 张已经扣完
                    // （服务端只有 BuryBottomCommand 成功后才开窗），此时再给「扣底」按钮
                    // 就是一枚点了必被拒的死按钮 —— 庄家也在等那三家表态，什么都别给。
                    if (s.pickSeat != null) break;
                    const need = 6 - this.selected.length;
                    const txt = this.selected.length > 0
                        ? `扣底(还差${Math.max(need, 0)}张)`
                        : '扣底：选6张';
                    this.makeButton(txt, 0, () => {
                        if (this.snap?.phase !== 'BURYING' || this.snap.banker !== this.mySeat) {
                            this.showToast('扣底阶段已经结束', true);
                            this.resetSelection();
                            return;
                        }
                        if (this.selected.length !== 6) { this.showToast('扣底需恰好 6 张', true); return; }
                        this.net?.sendCmd('BURY', { cards: this.selectedCodes() });
                    });
                }
                break;
            }
            case 'PLAYING': {
                if (myTurn) {
                    this.makeButton(this.selected.length > 0 ? '出牌' : '出牌：先选牌', 0, () => {
                        // 【防超时误点】按钮渲染后到点击之间，服务端可能已按超时托管代打、
                        // 或牌局已翻到下一手。此时再发 PLAY 必然失败并弹一片红字，
                        // 先在本地拦掉：只提示，不发送。
                        if (this.snap?.phase !== 'PLAYING' || this.snap.turn !== this.mySeat) {
                            this.showToast('这一手已经结束，等下一轮', true);
                            this.resetSelection();
                            return;
                        }
                        if (this.selected.length === 0) { this.showToast('先点选要出的牌', true); return; }
                        this.net?.sendCmd('PLAY', { cards: this.selectedCodes() });
                        this.resetSelection();
                    });
                }
                break;
            }
            case 'TRIBUTE': {
                // 【阶段真相】服务端 GamePhase 只有 TRIBUTE —— 进贡、还贡、抗贡同属这一个阶段，
                // 快照里的 phase **永远**是 'TRIBUTE'，没有 'RETURN_TRIBUTE'。
                //
                // 2026-09-20 真机 bug（Tracy：坐庄喝血那局"没让我还贡、直接就开始了，
                // 可明细里已经有还贡的牌"）：这里原先只有"我是进贡人才给按钮"这一条，
                // 而"还贡"被写在了一个 `case 'RETURN_TRIBUTE'` 里 —— 那个阶段服务端从不发送，
                // 是**永远进不去的死分支**。于是收贡人（庄家）整局都没有还贡入口：
                // 服务端 humanWaiter() 明明返回了他、在等他，32 秒后超时托管代还 → 直接进扣底。
                // 玩家视角就是"我没有操作，牌却自己贡了、还了"。别再按阶段名分开写。
                const mine = s.pendingTributes?.[this.mySeat];
                if (mine) {
                    // 我是进贡人（收贡人的上家）：交足血数的牌。文案带张数，省得玩家猜要选几张。
                    const need = mine.blood;
                    this.makeButton(`进贡(${need}张)`, 0, () => {
                        const stillMine = this.snap?.pendingTributes?.[this.mySeat];
                        if (this.snap?.phase !== 'TRIBUTE' || !stillMine) {
                            this.showToast('进贡阶段已经结束', true);
                            this.resetSelection();
                            return;
                        }
                        if (this.selected.length === 0) { this.showToast('先选要贡的牌', true); return; }
                        if (this.selected.length !== stillMine.blood) {
                            this.showToast(`要贡 ${stillMine.blood} 张（已选 ${this.selected.length} 张）`, true);
                            return;
                        }
                        this.net?.sendCmd('TRIBUTE', { cards: this.selectedCodes(), payee: stillMine.receiver });
                        this.resetSelection();
                    });
                } else {
                    // 我是收贡人：把收到的血还回去（还贡张数必须等于收到的张数）
                    const owed = this.pendingReturnOf(s);
                    if (owed) {
                        this.makeButton(`还贡(${owed.count}张)`, 0, () => {
                            const still = this.pendingReturnOf(this.snap);
                            if (this.snap?.phase !== 'TRIBUTE' || !still || still.payer !== owed.payer) {
                                this.showToast('还贡阶段已经结束', true);
                                this.resetSelection();
                                return;
                            }
                            if (this.selected.length === 0) { this.showToast('先选要还的牌', true); return; }
                            if (this.selected.length !== still.count) {
                                this.showToast(`要还 ${still.count} 张（已选 ${this.selected.length} 张）`, true);
                                return;
                            }
                            this.net?.sendCmd('RETURN_TRIBUTE', { cards: this.selectedCodes(), payee: still.payer });
                            this.resetSelection();
                        });
                    }
                }
                break;
            }
        }

        // 【常驻】新局按钮已上移到函数开头（不依赖 snapshot，连不上时显示"重试连接"）
    }

    // ==================== 亮主候选栏（BIDDING 阶段，手牌上方一排图标） ====================

    /**
     * 图标定义。顺序即显示顺序：大王 · 小王 · 黑桃 · 红桃 · 梅花 · 方块。
     * key：王用牌代码前缀（BJ/SJ），花色用服务端 Suit 枚举名（点下去直接作为 REVEAL 的 suit）。
     * 用词沿用项目现有的 SUIT_CN（梅花/方块），保证与顶栏"主XX"显示一致。
     */
    private static readonly BID_ICONS: { key: string; label: string; sym: string }[] = [
        { key: 'BJ', label: '大王', sym: '' },
        { key: 'SJ', label: '小王', sym: '' },
        { key: 'SPADE', label: '黑桃', sym: '♠' },
        { key: 'HEART', label: '红桃', sym: '♥' },
        { key: 'CLUB', label: '梅花', sym: '♣' },
        { key: 'DIAMOND', label: '方块', sym: '♦' },
    ];

    /** 牌编码首字符 → 服务端 Suit 枚举名（SUIT_CHAR 的反查） */
    private static readonly CHAR_SUIT: Record<string, string> = {
        S: 'SPADE', H: 'HEART', D: 'DIAMOND', C: 'CLUB',
    };

    /**
     * 是否"本轮第一局"。第一局的亮主规则与第二局起完全不同：
     * 第一局抢亮 **1 张大王**即可（无人能反，且不能用级牌亮）；第二局起才是
     * "亮/反级牌（1..3 张）+ 3 张小王 / 3 张大王叫任意花色"。
     *
     * 优先用服务端下发的 firstRound；拿不到时兜底推断 —— 第一局的庄家正是由本次
     * 亮主产生（banker 为空），而第二局起的庄家在上局结算时就已确定（banker 非空）。
     * 注意不能用 gameNumber===1 推断：出锅回到第一局时 gameNumber 并不会归 1。
     */
    private isFirstRoundBidding(): boolean {
        const s = this.snap;
        if (!s) return false;
        if (typeof s.firstRound === 'boolean') return s.firstRound;
        if (s.reveal) return s.reveal.kind === 'FIRST_ROUND_JOKER';
        return !s.banker;
    }

    /** 是否在亮主窗口内：发牌中（边摸边抢亮，手册 2.2）+ 发牌后（亮主/反主） */
    private inBidWindow(): boolean {
        const p = this.snap?.phase;
        return p === 'BIDDING' || p === 'DEALING';
    }

    /**
     * 候选栏**结构**签名：只在"某个图标由灰转亮 / 由亮转灰"时才重建整排。
     *
     * <p>与上一版的区别：手牌不再进签名。逐张发牌下手里每 60ms 就多一张牌，
     * 手牌进签名 = 整排图标每秒被拆建十几次（每个图标带 Graphics + 多个系统字
     * Label，是显存和帧率的双重负担，白屏那次已经吃过教训）。数字改走
     * {@link #updateBidBadges} 增量刷文本，结构只在亮灭状态真变时重建。
     *
     * <p>返回 null = 当前不在亮主窗口（整排隐藏）。
     */
    private bidSignature(): string | null {
        const s = this.snap;
        if (!s || !this.inBidWindow()) return null;
        const r = s.reveal;
        const cur = r ? `${r.kind}/${r.suit ?? '-'}/${r.count ?? 0}` : '-';
        const plan = this.computeBidPlan();
        const modes = plan ? plan.chips.map(c => `${c.key}:${c.mode}`).join(',') : '';
        return `${s.level}|${this.isFirstRoundBidding() ? 1 : 0}|${cur}|${modes}`;
    }

    /**
     * 算出每个图标的状态，以及"点某个花色时该送哪几张牌"。
     *
     * 判定与服务端 {@code TrumpReveal.canBeOverriddenBy} 严格等价 —— 这里只负责让图标
     * 亮得准（能不能点），合法性最终仍由服务端把关，所以即使算偏也不会产生非法操作，
     * 最坏只是"该亮没亮"或"亮了但被服务端拒"（会以失败 toast 呈现）。
     *
     * mode：off=手里没有可用的牌（灰，点不动）/ reveal=可新亮（金）/ override=可反主（红）。
     */
    private computeBidPlan(): {
        chips: { key: string; label: string; sym: string; mode: 'off' | 'reveal' | 'override'; count: number }[];
        suitCards: Record<string, string[]>;
    } | null {
        const s = this.snap;
        if (!s || !this.inBidWindow()) return null;
        const hand = s.yourHand ?? [];
        const level = s.level;
        const first = this.isFirstRoundBidding();
        const curKind = s.reveal?.kind ?? null;
        const curCount = s.reveal?.count ?? 0;

        const rep = (v: string, n: number): string[] => {
            const a: string[] = [];
            for (let i = 0; i < n; i++) a.push(v);
            return a;
        };

        // ---- 手里握着哪些"能亮的牌" ----
        const levelCnt: Record<string, number> = { SPADE: 0, HEART: 0, CLUB: 0, DIAMOND: 0 };
        // 【角标】手里每个花色的**全部**牌张数（不限点数，级牌也含在内），以及大小王张数。
        // 与 levelCnt 的区别：levelCnt 只数级牌（决定"能不能亮"），suitTotal 数整门（决定角标数字）。
        const suitTotal: Record<string, number> = { SPADE: 0, HEART: 0, CLUB: 0, DIAMOND: 0 };
        let bigJokers = 0;
        let smallJokers = 0;
        for (const code of hand) {
            const { suit, rank, joker } = this.parseCode(code);
            if (joker === 2) { bigJokers++; continue; }
            if (joker === 1) { smallJokers++; continue; }
            const name = TableUI.CHAR_SUIT[suit];
            if (!name) continue;
            suitTotal[name]++;                  // 【角标】任意点数都计入该花色总数
            if (rank === level) levelCnt[name]++;
        }

        /** 与 TrumpReveal.canBeOverriddenBy 等价；无当前声明 = 恒可 */
        const canClaim = (kind: string, count: number): boolean => {
            if (!curKind) return true;
            if (curKind === 'FIRST_ROUND_JOKER' || curKind === 'TRIPLE_BIG_JOKER') return false;
            if (kind === 'TRIPLE_BIG_JOKER') return true;
            if (kind === 'TRIPLE_SMALL_JOKER') return curKind === 'LEVEL_CARDS';
            if (kind === 'LEVEL_CARDS') {
                return curKind === 'LEVEL_CARDS'
                    && ((count === 2 && curCount === 1) || (count === 3 && curCount <= 2));
            }
            return false;
        };

        // ---- 王的候选 ----
        // 第一局：1 张大王即抢亮（无人能反）；第二局起：3 张大王 / 3 张小王。
        // 第二局起必须是"3 张同一种王"，大小王混合不算（服务端同样拒绝）。
        const bigCount = first ? 1 : 3;
        const bigCards = (first ? bigJokers >= 1 : bigJokers >= 3)
            && canClaim(first ? 'FIRST_ROUND_JOKER' : 'TRIPLE_BIG_JOKER', bigCount)
            ? rep('BJ', bigCount) : [];
        const smallCards = !first && smallJokers >= 3 && canClaim('TRIPLE_SMALL_JOKER', 3)
            ? rep('SJ', 3) : [];

        // ---- 四个花色 ----
        // 王的优先级高于级牌：3 张王能叫任意花色，且 3 大王可反一切，是严格更强的
        // 声明 —— 手里同时有王和级牌时，玩家不可能想用级牌去亮。
        // 第一局例外：主花色由"亮牌人随后摸到的第一张花色牌"决定（手册 2.2），
        // 玩家不需要选花色 → 四个花色图标保持灰，点大王即抢亮。
        const suitCards: Record<string, string[]> = {};
        for (const suit of ['SPADE', 'HEART', 'CLUB', 'DIAMOND']) {
            let cards: string[] = [];
            if (first) {
                cards = [];
            } else if (bigCards.length > 0) {
                cards = rep('BJ', bigCards.length);
            } else if (smallCards.length > 0) {
                cards = rep('SJ', 3);
            } else {
                const cnt = levelCnt[suit];
                if (cnt > 0 && canClaim('LEVEL_CARDS', cnt)) {
                    cards = rep(`${TableUI.SUIT_CHAR[suit]}${level}`, cnt);
                }
            }
            suitCards[suit] = cards;
        }

        const modeOf = (cards: string[]): 'off' | 'reveal' | 'override' =>
            cards.length === 0 ? 'off' : (curKind ? 'override' : 'reveal');

        const chips = TableUI.BID_ICONS.map(ic => {
            let mode: 'off' | 'reveal' | 'override';
            let count: number;
            if (ic.key === 'BJ') { mode = modeOf(bigCards); count = bigJokers; }
            else if (ic.key === 'SJ') { mode = modeOf(smallCards); count = smallJokers; }
            else { mode = modeOf(suitCards[ic.key]); count = suitTotal[ic.key] ?? 0; }
            return { key: ic.key, label: ic.label, sym: ic.sym, mode, count };
        });
        return { chips, suitCards };
    }

    /** 渲染亮主候选栏：结构没变时只刷数字/提示，一个节点都不重建 */
    private renderBid(): void {
        const sig = this.bidSignature();
        if (sig === null) {
            if (this.bidNode.active) this.bidNode.active = false;
            this.bidSig = null;     // 离开亮主窗口 → 下次进来必须重建
            this.bidBadgeLabels = {};
            this.bidTipLabel = null;
            return;
        }
        this.bidNode.active = true;

        const plan = this.computeBidPlan();
        if (!plan) return;

        if (sig !== this.bidSig) {
            this.bidSig = sig;
            // 【白屏必修】必须 destroy 而不是 removeAllChildren：每个图标都带
            // Graphics + 系统字 Label，只解绑不销毁会把顶点缓冲 / 材质实例 / 字贴图
            // 永久留在显存里（同 handNode / btnNode 的教训）。
            this.destroyChildren(this.bidNode);
            this.bidBadgeLabels = {};
            this.bidTipLabel = this.addBidTip(this.bidNode);

            const W = 62;
            const GAP = 10;
            const total = plan.chips.length * W + (plan.chips.length - 1) * GAP;
            let x = -total / 2 + W / 2;
            for (const chip of plan.chips) {
                this.bidNode.addChild(this.makeBidChip(chip, x));
                x += W + GAP;
            }
        }

        // 这两个是每帧（每条快照）都跑的增量刷新：只改文本，不碰节点结构
        this.updateBidBadges(plan.chips);
        this.updateBidTip();
    }

    /**
     * 角标数字增量刷新。
     * 发牌期间每 60ms 就有一张牌进手，某个花色的角标要 +1 —— 只有文本变，
     * 所以这里绝不重建节点（重建的代价见 bidBadgeLabels 的注释）。
     */
    private updateBidBadges(chips: { key: string; count: number }[]): void {
        for (const chip of chips) {
            const l = this.bidBadgeLabels[chip.key];
            if (!l) continue;
            const t = String(chip.count);
            if (l.string !== t) l.string = t;
        }
    }

    /** 亮主栏上方提示行：发牌进度 / 待摸定主 */
    private updateBidTip(): void {
        const l = this.bidTipLabel;
        if (!l) return;
        const s = this.snap;
        let tip = '';
        if (s) {
            if (s.phase === 'DEALING') {
                tip = `发牌中 ${(s.yourHand ?? []).length}/39`;
            } else if (s.reveal?.kind === 'FIRST_ROUND_JOKER' && !s.reveal.suit) {
                tip = '已亮大王，等待摸到花色牌定主…';
            }
        }
        if (l.string !== tip) l.string = tip;
    }

    /** 提示行节点（挂在亮主栏上方，避免与图标抢位置） */
    private addBidTip(parent: Node): Label {
        const n = new Node('bidtip');
        n.layer = 1 << 25;
        n.addComponent(UITransform).setContentSize(360, 20);
        const l = n.addComponent(Label);
        l.string = '';
        l.fontSize = 14;
        l.lineHeight = 18;
        l.isBold = true;
        l.useSystemFont = true;
        l.color = new Color(255, 214, 130, 255);
        l.horizontalAlign = Label.HorizontalAlign.CENTER;
        l.verticalAlign = Label.VerticalAlign.CENTER;
        n.setPosition(0, 46, 0);
        parent.addChild(n);
        return l;
    }

    /**
     * 画一个亮主图标。项目里没有任何图片资源（牌面/按钮全是代码画的），所以这里同样用
     * Graphics 画底 + Label 写字；符号配色复用 suitColor()，与手牌牌面一致
     * （♠ 深蓝 / ♣ 黑 / ♥♦ 红 —— ♠ 与 ♣ 靠色相拉开，沿用任老师反馈"草花黑桃太相近"后的做法）。
     */
    private makeBidChip(
        chip: { key: string; label: string; sym: string; mode: 'off' | 'reveal' | 'override'; count: number },
        x: number): Node {
        const W = 62, H = 58, R = 12;
        const n = new Node(`bid_${chip.key}`);
        n.layer = 1 << 25;
        n.addComponent(UITransform).setContentSize(W, H);
        const g = n.addComponent(Graphics);
        const on = chip.mode !== 'off';
        const over = chip.mode === 'override';

        // 可亮 = 暗金底金框；可反 = 暗红底红框；没牌 = 深灰半透明
        g.roundRect(-W / 2, -H / 2, W, H, R);
        g.fillColor = !on ? new Color(16, 28, 24, 175)
            : over ? new Color(78, 24, 24, 245) : new Color(66, 52, 16, 245);
        g.fill();
        g.lineWidth = on ? 2 : 1;
        g.strokeColor = !on ? new Color(255, 255, 255, 40)
            : over ? new Color(240, 95, 95, 255) : new Color(235, 185, 70, 255);
        g.stroke();

        const nameColor = !on ? new Color(146, 156, 150, 150)
            : over ? new Color(255, 168, 168, 255) : new Color(255, 220, 130, 255);
        if (chip.sym) {
            const symColor = on ? suitColor(TableUI.SUIT_CHAR[chip.key] ?? '')
                : new Color(146, 156, 150, 150);
            this.addChipLabel(n, chip.sym, 19, symColor, 9, W);
            this.addChipLabel(n, chip.label, 12, nameColor, -14, W);
        } else {
            this.addChipLabel(n, chip.label, 17, nameColor, 0, W);
        }

        // 【角标】右上角数字 = 手里该花色的总张数（王 = 该王张数，如手里俩大王就是 2）。
        // 颜色沿用图标状态语义：金底（能亮）/ 红底（能反）/ 灰底（没料），
        // 于是"有几张"和"能不能点"在同一处就能读出来。数字为 0 时也显示，
        // 明确告诉玩家"这门确实一张没有"，而不是让灰图标独自承担这个信息。
        const badgeBg = !on ? new Color(58, 66, 62, 215)
            : over ? new Color(228, 82, 82, 255) : new Color(232, 184, 68, 255);
        const badgeFg = !on ? new Color(168, 176, 170, 255)
            : over ? new Color(255, 240, 240, 255) : new Color(52, 34, 0, 255);
        // 存下 Label 引用：发牌期间 updateBidBadges 直接改它的 string，不重建节点
        this.bidBadgeLabels[chip.key] =
            this.addChipBadge(n, String(chip.count), W / 2 - 3, H / 2 - 3, badgeBg, badgeFg);

        n.setPosition(x, 0, 0);
        if (on) {
            // 回调挂 TOUCH_START：真机手指轻微滑动会把 TOUCH_END 变成 TOUCH_CANCEL，
            // 只在 END 触发会表现为"按了没反应"（同 makeButton 的既有处理）。
            n.on(Node.EventType.TOUCH_START, () => {
                tween(n).to(0.07, { scale: new Vec3(0.92, 0.92, 1) }, { easing: 'quadOut' }).start();
                this.onBidClick(chip.key);
            });
            n.on(Node.EventType.TOUCH_END, () => {
                tween(n).to(0.12, { scale: new Vec3(1, 1, 1) }, { easing: 'backOut' }).start();
            });
            n.on(Node.EventType.TOUCH_CANCEL, () => {
                tween(n).to(0.12, { scale: new Vec3(1, 1, 1) }, { easing: 'backOut' }).start();
            });
        } else {
            // 灰图标也要给反馈，把"点不动的原因"说清楚，玩家不必再去猜规则
            const firstRound = this.isFirstRoundBidding();
            n.on(Node.EventType.TOUCH_START, () => {
                if (firstRound && chip.sym) {
                    // 第一局不是"没有"，而是"不用选"：主花色由摸牌决定
                    this.showToast('第一局点「大王」即可抢亮，主花色由随后摸到的第一张花色牌决定', true);
                } else {
                    this.showToast(
                        chip.sym ? `手里没有能亮${chip.label}的牌` : `你手里没有 3 张${chip.label}`, true);
                }
            });
        }
        return n;
    }

    /** 图标里的文字。Label 必须挂在子节点：一个节点只能挂一个 UIRenderer。 */
    private addChipLabel(parent: Node, text: string, size: number, color: Color,
                         y: number, width: number): void {
        const n = new Node('t');
        n.layer = 1 << 25;
        n.addComponent(UITransform).setContentSize(width, size + 6);
        const l = n.addComponent(Label);
        l.string = text;
        l.fontSize = size;
        l.lineHeight = size + 4;
        l.color = color;
        l.isBold = true;
        l.useSystemFont = true;             // 系统字体：Bitmap font 中文不可靠（可能缺字）
        l.horizontalAlign = Label.HorizontalAlign.CENTER;
        l.verticalAlign = Label.VerticalAlign.CENTER;
        n.setPosition(0, y, 0);
        parent.addChild(n);
    }

    /**
     * 图标右上角的计数角标（胶囊形底 + 数字），右边缘对齐图标内缘。
     *
     * <p>宽度**固定**而不随位数自适应：逐张发牌时数字每 60ms 就可能变，宽度若随位数
     * 变化就得连角标底一起重建；固定后只需改一个 Label 字符串（见 updateBidBadges）。
     * 20px 足够放下三位数（同花色手牌最多 39 张）。
     *
     * <p>结构说明：一个节点只能挂一个 UIRenderer，所以底（Graphics）和数字（Label）
     * 必须分成两个节点 —— 与 makeBidChip 里"底 + 文字"同一套路。
     */
    private addChipBadge(parent: Node, text: string, rightX: number, topY: number,
                         bg: Color, fg: Color): Label {
        const w = 20, h = 17;
        const b = new Node('badge');
        b.layer = 1 << 25;
        b.addComponent(UITransform).setContentSize(w, h);
        const g = b.addComponent(Graphics);
        g.roundRect(-w / 2, -h / 2, w, h, h / 2);
        g.fillColor = bg;
        g.fill();
        g.lineWidth = 1;
        g.strokeColor = new Color(0, 0, 0, 110);   // 细描边：金底压在金框上时也能分清边界
        g.stroke();
        b.setPosition(rightX - w / 2, topY - h / 2, 0);
        parent.addChild(b);

        const t = new Node('n');
        t.layer = 1 << 25;
        t.addComponent(UITransform).setContentSize(w, h);
        const l = t.addComponent(Label);
        l.string = text;
        l.fontSize = 12;
        l.lineHeight = 13;
        l.color = fg;
        l.isBold = true;
        l.useSystemFont = true;
        l.horizontalAlign = Label.HorizontalAlign.CENTER;
        l.verticalAlign = Label.VerticalAlign.CENTER;
        b.addChild(t);
        return l;
    }

    /** 点了点亮的图标：直接把手里符合条件的那几张牌送上去（不再需要先选牌） */
    private onBidClick(key: string): void {
        const plan = this.computeBidPlan();
        if (!plan) return;
        if (key === 'BJ' || key === 'SJ') {
            // 第一局：点大王即抢亮，主花色**不上送** —— 手册 2.2 规定它由"亮牌人
            // 随后摸到的第一张花色牌"决定，服务端先记待定，摸到时自动补上。
            if (key === 'BJ' && this.isFirstRoundBidding()) {
                this.net?.sendCmd('REVEAL', { cards: ['BJ'] });
                this.showToast('亮大王！等待摸到第一张花色牌定主…');
                return;
            }
            // 第二局的王图标是"我手里有王"的指示牌，真正的花色选择在它右边的四个花色图标上
            const n = key === 'BJ' ? '大王' : '小王';
            this.showToast(`点右边任一花色，用 3 张${n}叫该花色为主`, true);
            return;
        }
        const cards = plan.suitCards[key];
        if (!cards || cards.length === 0) {
            this.showToast(`手里没有能亮${TableUI.SUIT_CN[key] ?? key}的牌`, true);
            return;
        }
        this.net?.sendCmd('REVEAL', { cards, suit: key });
        this.showToast(`亮${TableUI.SUIT_CN[key] ?? key}：${cards.map(c => cardFace(c).text).join(' ')}`);
    }

    /**
     * 按钮集合签名。凡是会影响「按钮有没有 / 文案 / 回调参数」的状态，都必须进签名，
     * 否则会出现"状态变了但按钮没变"的死按钮。
     * 与手牌不同：按钮最多 5 个，重建成本低，但**每条快照都重建**同样是显存泄漏源，
     * 所以同样用签名把它压到"状态真的变了才重建"。
     */
    private buttonSignature(online: boolean, s: SnapshotMsgDown | null): string {
        const p: string[] = [online ? 'on' : 'off'];
        if (!s) return p.join('|');
        p.push(s.phase, String(s.turn ?? '-'), String(this.selected.length));
        switch (s.phase) {
            case 'BIDDING':
                break;                                      // 亮主阶段无按钮（图标栏见 renderBid）
            case 'BURYING':
                // 扣底阶段有两个子状态，按钮完全不同，必须一起进签名，
                // 否则"扣底：选6张"会粘在他人扣王窗口上：
                //   ① 等庄家扣底（banker === 我）；
                //   ② 等某一家表态要不要在底牌中扣王（手册 2.3.5，pickSeat）。
                p.push(s.banker ?? '-');
                p.push(`pick:${s.pickSeat ?? '-'}:${s.pickMax ?? 0}`);
                break;
            case 'PLAYING':
                p.push(s.turn === this.mySeat ? 'mine' : '-');
                break;
            case 'TRIBUTE': {
                // 进贡与还贡同属 TRIBUTE（服务端没有 RETURN_TRIBUTE 阶段，见 renderButtons）。
                // **张数与对象都必须进签名**：一局可能有两笔血（分差血 + 扣王血）且收贡人不同，
                // 第一笔还完按钮要立刻变成第二笔；签名不带这些就会出现"该变的没变"的死按钮。
                const mine = s.pendingTributes?.[this.mySeat];
                if (mine) {
                    p.push(`pay:${mine.blood}:${mine.receiver}`);
                } else {
                    const owed = this.pendingReturnOf(s);
                    p.push(owed ? `ret:${owed.payer}:${owed.count}` : '-');
                }
                break;
            }
            default:
                break;
        }
        return p.join('|');
    }

    /**
     * 清空选中（出牌/进贡/新局等"动作已提交或已作废"的场合）。
     * 同时把 handSig 置空 → 下一次 renderHand 会真的重画，避免出现
     * "selected 已空、但牌面上还留着金色选中框"的脏状态。
     */
    private resetSelection(): void {
        if (this.selected.length === 0) return;
        this.selected.length = 0;
        this.handSig = null;
    }

    // ==================== 事件提示 ====================

    private onEventToast(e: EventMsg): void {
        // 【必须过滤发牌事件】逐张发牌会为每张牌广播一条 DEAL_NEXT（一局 156 条），
        // 不过滤的话 toast 会被刷成流水线，玩家什么都看不清。
        if (e.op === 'DEAL' || e.op === 'DEAL_NEXT' || e.op === 'SETUP_DEAL') return;
        // 【第三行瘦身】"WEST PLAY ♦7 ♦4" 这类出牌流水同样毫无用处：谁出了什么牌
        // 桌面上就摆着，而且逐手刷屏会把真正该看的提示（干锅/失败原因/亮主结果）挤掉。
        // 只过滤成功的出牌事件；失败仍然要弹（那是玩家必须知道的）。
        if (e.op === 'PLAY' && e.success !== false) return;
        if (e.op === 'SETTLE') {
            // 结算面板展示窗口：事件先到、快照紧随其后（renderAll 里渲染）
            this.settleVisibleUntil = Date.now() + TableUI.SETTLE_SHOW_MS;
            // 新一局结算 → 上一局被点掉的"免打扰"标记作废，面板重新参与展示
            this.settleDismissed = false;
            // 事件发出时结算命令已执行完，gameNumber 已 +1 → 实际结算局号要减 1
            const gn = (e.gameNumber ?? this.snap?.gameNumber ?? 1) - 1;
            this.settleGameNumber = Math.max(gn, 1);
        }
        const who = e.seat ?? '';
        const what = e.cards?.length ? ` ${e.cards.map(c => cardFace(c).text).join(' ')}` : '';
        const fail = e.success === false ? ` 失败：${String(e.reason ?? '')}` : '';
        const info = e.success !== false && e.reason ? ` — ${String(e.reason)}` : '';
        this.showToast(`${who} ${TableUI.PHASE_TEXT[e.op] ?? e.op}${what}${fail}${info}`, e.success === false);
    }

    /**
     * 弹一条提示。
     *
     * <p>【它是"展示位"，不是"消息队列"】只有一块 Label，后来者覆盖先来者。
     * 因为出牌成功的事件被过滤（见 onEventToast），一次失败的红字之后可能很久没有
     * 新事件来覆盖 —— 所以每条 toast 都自带到期时间，并且牌局往前走了就立刻作废。
     *
     * @param sticky 致命错误传 true：不打到期时间、也不被牌局前进清掉（见 toastSticky）
     */
    private showToast(text: string, warn = false, sticky = false): void {
        if (!text) {
            this.clearToast();
            return;
        }
        this.toastLabel.string = text;
        this.toastLabel.color = warn ? TableUI.TOAST_COLOR_WARN : TableUI.TOAST_COLOR_INFO;
        this.toastSticky = sticky;
        this.toastUntil = sticky
            ? 0
            : Date.now() + (warn ? TableUI.TOAST_FAIL_MS : TableUI.TOAST_INFO_MS);
    }

    private clearToast(): void {
        this.toastUntil = 0;
        this.toastSticky = false;
        if (this.toastLabel && this.toastLabel.string !== '') this.toastLabel.string = '';
    }

    /**
     * 牌局上下文变化 → 旧提示作废。
     *
     * <p>key 取「局号|阶段|轮次|本墩牌面」，这个组合正好表达"牌局确实往前走了"：
     * <ul>
     *   <li>一次**失败**的出牌不会改变其中任何一项（牌没出出去）→ 红字留在屏幕上给人读完；</li>
     *   <li>任何**成功**的出牌都会改 turn / 改本墩牌面 → 红字立刻消失；</li>
     *   <li>一局结束、新一局开始 → 局号变 → 上一局的失败提示绝不会跨局残留（Tracy 反馈的现象）。</li>
     * </ul>
     *
     * <p>首条快照只建立基线、不清提示：否则"已入座 SOUTH"会被紧随其后的快照立刻抹掉。
     * 常驻的致命错误（toastSticky）不受牌局前进影响。
     * （牌局卡死不再有快照的情况由 update() 里的到期时间兜底。）
     */
    private syncToastContext(s: SnapshotMsgDown): void {
        const key = `${s.gameNumber}|${s.phase}|${s.turn ?? '-'}`
            + `|${this.trickKey(this.extractTrickPlays(s))}`;
        if (!this.toastSticky && this.toastCtxKey !== '' && key !== this.toastCtxKey) {
            this.clearToast();
        }
        this.toastCtxKey = key;
    }
}
