package com.gunzihall.room;

import com.gunzihall.domain.action.BuryBottomCommand;
import com.gunzihall.domain.action.CommandResult;
import com.gunzihall.domain.action.ConfirmTrumpCommand;
import com.gunzihall.domain.action.DealNextCardCommand;
import com.gunzihall.domain.action.GameCommand;
import com.gunzihall.domain.action.PickBottomJokerCommand;
import com.gunzihall.domain.action.PlayCardsCommand;
import com.gunzihall.domain.action.ResolveTrumpFromBottomCommand;
import com.gunzihall.domain.action.ReturnTributeCommand;
import com.gunzihall.domain.action.RevealTrumpCommand;
import com.gunzihall.domain.action.SettleRoundCommand;
import com.gunzihall.domain.action.SetupDealCommand;
import com.gunzihall.domain.action.ShuffleAndDealCommand;
import com.gunzihall.domain.action.TributeCommand;
import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.card.Suit;
import com.gunzihall.domain.play.RoundSettlement;
import com.gunzihall.domain.play.Trick;
import com.gunzihall.domain.player.Player;
import com.gunzihall.domain.player.Seat;
import com.gunzihall.domain.room.GamePhase;
import com.gunzihall.domain.room.GameRoom;
import com.gunzihall.domain.tribute.TributeObligation;
import com.gunzihall.domain.trump.CardComparator;
import com.gunzihall.domain.trump.TrumpContext;
import com.gunzihall.domain.trump.TrumpReveal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 房间编排器：包装领域 {@link GameRoom}，职责——
 * <ol>
 *   <li>命令路由：WS 客户端命令 / bot 决策 → 领域命令 → 应用 → 事件广播；</li>
 *   <li>bot 驱动：每个阶段自动推进（发牌→亮王→进贡→扣底→出牌→结算→下一局）；</li>
 *   <li>状态留痕：全部命令写入 {@link RoomStateStore}（Redis 重放恢复）；</li>
 *   <li>快照：按玩家定制视图（自己的手牌 + 全桌公开状态）。</li>
 * </ol>
 * 线程模型：异步模式走单线程调度器（房间内串行，架构 v0 R-005）；同步模式供测试/演示。
 */
public final class RoomActor {

    /** 观察者出口：按玩家定制快照 + 全桌事件 */
    public interface Sink {
        long playerId();

        void send(String json);
    }

    private final long roomId;
    private final GameRoom room;
    private final RoomStateStore store;
    private final EnumMap<Seat, Boolean> botSeats = new EnumMap<>(Seat.class);
    private final List<Sink> sinks = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> rawListeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler; // null = 同步模式
    private final long botDelayMs;
    private final AtomicBoolean drivePending = new AtomicBoolean(false);

    // T-701 拟人化：bot 思考时长随机区间；未设置（max<=0）时退回固定 botDelayMs
    private volatile long thinkMinMs = 0;
    private volatile long thinkMaxMs = 0;

    // T-704 超时托管：真人等待上限，归零后由 BotBrain 代打（0 = 关闭）
    private volatile long turnTimeoutMs = 0;
    /** 逐张发牌的节奏（每张间隔毫秒）。必须与 bot 思考时长分开：发牌若吃 0.8~2.5s
     *  的思考时长，156 张要发好几分钟 */
    private volatile long dealDelayMs = 60;
    /** 已装弹的超时任务（等待真人时装、有人行动后取消/空响） */
    private ScheduledFuture<?> timeoutTask;
    /** 装弹时的 actionSeq：归零时若已变化说明真人行动过 → 空响直接返回 */
    private long timeoutArmedSeq = -1;
    /** 每次成功命令自增，用于识别"等待状态是否还成立" */
    private long actionSeq = 0;

    /** 演示用：打到第 N 局后停在 DEALING 不再发牌 */
    private int stopAfterGames = Integer.MAX_VALUE;

    // 亮王轮转状态（编排层私有，不入领域）
    private Seat bidTurn = Seat.NORTH;
    private int bidPasses = 0;
    // 进贡方向（payer → 收贡人），编排层在进贡时捕获
    private final EnumMap<Seat, Seat> tributeReceiver = new EnumMap<>(Seat.class);

    private boolean started = false;
    private boolean stuck = false;
    private int consecutiveFailures = 0;
    private boolean replaying = false;
    /**
     * 当前正在处理的是不是真人提交的命令。
     * <p>用于把「真人误点造成的失败」排除在 bot 驱动的卡死判定之外：
     * 玩家在超时托管后继续点“出牌”、或界面未刷新时连点，命令必然失败；
     * 若把这些失败计入 {@link #consecutiveFailures}，点够 30 次就会把整局判 stuck。
     */
    private boolean humanCommand = false;

    public RoomActor(long roomId, RoomStateStore store) {
        this(roomId, store, null, 0);
    }

    public RoomActor(long roomId, RoomStateStore store, long botDelayMs) {
        this(roomId, store, Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "room-" + roomId);
            t.setDaemon(true);
            return t;
        }), botDelayMs);
    }

    private RoomActor(long roomId, RoomStateStore store,
                      ScheduledExecutorService scheduler, long botDelayMs) {
        this.roomId = roomId;
        this.room = new GameRoom(roomId);
        this.store = store;
        this.scheduler = scheduler;
        this.botDelayMs = botDelayMs;
    }

    // ================= 对外 API =================

    public long roomId() {
        return roomId;
    }

    public GameRoom room() {
        return room;
    }

    public void setStopAfterGames(int n) {
        this.stopAfterGames = n;
    }

    /** T-701 拟人化：bot 每次行动前的思考时长在 [minMs, maxMs] 随机（闭区间） */
    public void setThinkTime(long minMs, long maxMs) {
        if (maxMs < minMs || minMs < 0) {
            throw new IllegalArgumentException("非法思考时长区间: [" + minMs + ", " + maxMs + "]");
        }
        this.thinkMinMs = minMs;
        this.thinkMaxMs = maxMs;
    }

    /** T-704 超时托管：真人单步等待上限（毫秒），归零后 BotBrain 代打；<=0 关闭 */
    public void setTurnTimeout(long ms) {
        if (ms < 0) {
            throw new IllegalArgumentException("非法超时时长: " + ms);
        }
        this.turnTimeoutMs = ms;
    }

    public void addSink(Sink sink) {
        sinks.add(sink);
    }

    public void removeSink(Sink sink) {
        sinks.remove(sink);
    }

    /** 仅事件流监听（演示打印用，不收快照） */
    public void addRawListener(Consumer<String> listener) {
        rawListeners.add(listener);
    }

    /** 玩家入座（WAITING 阶段）；记录 JOIN 留痕供重放 */
    public void join(Player player, boolean bot) {
        room.sitDown(player);
        botSeats.put(player.seat(), bot);
        if (!replaying) {
            store.append(roomId, JsonUtil.write(Map.of(
                    "op", "JOIN", "playerId", player.playerId(),
                    "seat", player.seat().name(), "bot", bot)));
        }
    }

    public boolean isBot(Seat seat) {
        return botSeats.getOrDefault(seat, false);
    }

    public boolean hasPlayer(long playerId) {
        return room.players().values().stream().anyMatch(p -> p.playerId() == playerId);
    }

    public Player playerById(long playerId) {
        return room.players().values().stream()
                .filter(p -> p.playerId() == playerId).findFirst().orElse(null);
    }

    /** 开局（发牌并开始 bot 驱动）。异步模式下在调度器线程执行。 */
    public void start() {
        runInThread(() -> {
            if (started || !room.isFull()) {
                return;
            }
            started = true;
            beginDealing();
            scheduleDrive();
        });
    }

    /**
     * 开一局：把房间推进到 DEALING，其余交给驱动循环逐步完成。
     *
     * <p>这里刻意**不**直接发牌——洗牌与逐张发牌都在 DEALING 分支里由 step 推进，
     * 发牌才"看得见"（玩家边摸牌边抢亮，手册 2.2），而不是一条命令瞬间发完。
     */
    private void beginDealing() {
        if (room.phase() == GamePhase.WAITING) {
            room.transitionTo(GamePhase.DEALING);
        }
    }

    /** 客户端命令入口（异步模式投递到房间线程） */
    public void submit(CommandSpec spec) {
        runInThread(() -> {
            handleSpec(spec, false);
            scheduleDrive();
        });
    }

    /** 同步驱动直到等待真人/终态/上限（测试与演示用） */
    public void driveSync(int maxSteps) {
        for (int i = 0; i < maxSteps && !stuck; i++) {
            if (!step()) {
                return;
            }
        }
    }

    public boolean isStuck() {
        return stuck;
    }

    public void shutdown() {
        cancelTimeout();
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** T-704：连接断开（可能正轮到该真人行动）→ 唤醒驱动让 bot 立即接管 */
    public void onSinkRemoved() {
        runInThread(() -> {
            cancelTimeout(); // 代打接管后旧超时失效
            scheduleDrive();
        });
    }

    // ================= 命令规格（客户端协议 + 重放共用） =================

    public record CommandSpec(String op, long playerId, List<String> cards, String suit,
                              String payee, List<Integer> indexes, Long seed) {
    }

    private void handleSpec(CommandSpec spec, boolean replay) {
        boolean prevHuman = this.humanCommand;
        this.humanCommand = !replay;   // 重放不是真人实时命令
        try {
            handleSpec0(spec, replay);
        } finally {
            this.humanCommand = prevHuman;
        }
    }

    private void handleSpec0(CommandSpec spec, boolean replay) {
        long pid = spec.playerId();
        switch (spec.op()) {
            case "DEAL" -> {
                long seed = spec.seed() != null ? spec.seed() : newSeed();
                applyLogged(new ShuffleAndDealCommand(roomId, pid, seed),
                        "DEAL", pid, logOf("DEAL", pid, spec.cards(), spec.suit(), null, null, seed),
                        List.of());
            }
            case "SETUP_DEAL" -> {
                long seed = spec.seed() != null ? spec.seed() : newSeed();
                applyLogged(new SetupDealCommand(roomId, pid, seed),
                        "SETUP_DEAL", pid, logOf("SETUP_DEAL", pid, null, null, null, null, seed),
                        List.of());
            }
            case "DEAL_NEXT" -> applyLogged(new DealNextCardCommand(roomId, pid),
                    "DEAL_NEXT", pid, logOf("DEAL_NEXT", pid, null, null, null, null, null), List.of());
            case "REVEAL" -> {
                // 第一局抢亮大王时不上送主花色（按手册 2.2 由"随后摸到的第一张花色牌"决定），
                // 所以这里允许 suit 为空 —— 直接 Suit.valueOf(null) 会 NPE。
                Suit claim = spec.suit() != null ? Suit.valueOf(spec.suit()) : null;
                CommandResult rr = applyLogged(
                        new RevealTrumpCommand(roomId, pid, CardCodec.decodeAll(spec.cards()), claim),
                        "REVEAL", pid, logOf("REVEAL", pid, spec.cards(), spec.suit(), null, null, null),
                        CardCodec.decodeAll(spec.cards()));
                if (!replay) {
                    advanceBidAfterHuman(pid, rr.success());
                }
            }
            case "CONFIRM" -> applyLogged(new ConfirmTrumpCommand(roomId, pid),
                    "CONFIRM", pid, logOf("CONFIRM", pid, null, null, null, null, null), List.of());
            case "RESOLVE_BOTTOM" -> applyLogged(
                    new ResolveTrumpFromBottomCommand(roomId, pid, spec.indexes()),
                    "RESOLVE_BOTTOM", pid,
                    logOf("RESOLVE_BOTTOM", pid, null, null, null, spec.indexes(), null), List.of());
            case "TRIBUTE" -> applyLogged(
                    new TributeCommand(roomId, pid, CardCodec.decodeAll(spec.cards())),
                    "TRIBUTE", pid, logOf("TRIBUTE", pid, spec.cards(), null, null, null, null),
                    CardCodec.decodeAll(spec.cards()));
            case "RETURN_TRIBUTE" -> applyLogged(
                    new ReturnTributeCommand(roomId, pid, Seat.valueOf(spec.payee()),
                            CardCodec.decodeAll(spec.cards())),
                    "RETURN_TRIBUTE", pid, logOf("RETURN_TRIBUTE", pid, spec.cards(), null,
                            spec.payee(), null, null),
                    CardCodec.decodeAll(spec.cards()));
            case "BURY" -> applyLogged(
                    new BuryBottomCommand(roomId, pid, CardCodec.decodeAll(spec.cards())),
                    "BURY", pid, logOf("BURY", pid, spec.cards(), null, null, null, null),
                    CardCodec.decodeAll(spec.cards()));
            // 【他人捡牌扣王】手册 2.3 第 5 条：庄家扣完底后，其他三家依次可把自己的王
            // 扣进底牌并捡回同样张数的最小非分牌。cards 为空 = 本轮过（让给下一家）。
            case "PICK_JOKER" -> {
                List<Card> jokers = spec.cards() != null
                        ? CardCodec.decodeAll(spec.cards()) : List.of();
                applyLogged(new PickBottomJokerCommand(roomId, pid, jokers),
                        "PICK_JOKER", pid,
                        logOf("PICK_JOKER", pid, spec.cards(), null, null, null, null), jokers);
            }
            case "PLAY" -> applyLogged(
                    new PlayCardsCommand(roomId, pid, CardCodec.decodeAll(spec.cards())),
                    "PLAY", pid, logOf("PLAY", pid, spec.cards(), null, null, null, null),
                    CardCodec.decodeAll(spec.cards()));
            case "SETTLE" -> applyLogged(new SettleRoundCommand(roomId, pid),
                    "SETTLE", pid, logOf("SETTLE", pid, null, null, null, null, null), List.of());
            // 【新局】玩家点"新局"重开：强制回到 WAITING 再发牌。
            // 背景：战斗服是长跑的，牌局一直由 bot 驱动推进，玩家 join 进来时
            // 往往已经打了一半（任老师 2026-09-09 反馈"预览时牌局已进行一段"）。
            // 这里同时清 stuck，让之前若因异常停摆的驱动恢复。
            case "NEWGAME" -> {
                room.hardResetToWaiting();
                stuck = false;
                if (room.isFull()) {
                    beginDealing(); // 逐张发牌：余下交给驱动循环（submit 尾部会 scheduleDrive）
                } else {
                    emit("NEWGAME", pid, false, "房间未满 4 人，无法开新局", List.of());
                }
            }
            default -> emit("UNKNOWN_OP", pid, false, "未知命令: " + spec.op(), List.of());
        }
    }

    /** 重放一条日志（服务重启恢复）：执行但不写日志不广播 */
    void replaySpec(CommandSpec spec) {
        replaying = true;
        try {
            handleSpec(spec, true);
        } finally {
            replaying = false;
        }
    }

    /** 重放入座 */
    void replayJoin(long playerId, Seat seat, boolean bot) {
        replaying = true;
        try {
            Player p = bot ? new BotPlayer(playerId, seat)
                    : new com.gunzihall.domain.player.HumanPlayer(playerId, seat);
            room.sitDown(p);
            botSeats.put(seat, bot);
        } finally {
            replaying = false;
        }
    }

    /**
     * 日志重放完成后恢复驱动（服务重启恢复场景）。
     * <p>不能走 {@link #start()}——它会重新发牌毁掉恢复出的牌局；
     * 只需标记已开局并恢复 bot 驱动循环（轮到真人时 step 自然返回 false 等待）。
     */
    void resumeAfterRestore() {
        runInThread(() -> {
            if (!started && room.gameNumber() > 0) {
                started = true;
                scheduleDrive();
            }
        });
    }

    // ================= bot 驱动状态机 =================

    /**
     * 一次性发完（旧路径）：只保留给"底牌全王 → 重新洗牌"这种 BIDDING 阶段的重入场景
     * ——那时阶段已经走到 BIDDING，状态机不允许倒回 DEALING，只能整副重发。
     * 正常开局走 {@link #beginDealing()} 进入 DEALING，再由下面的逐张发牌推进。
     */
    private void deal() {
        long pid = anyPlayerId();
        applyLogged(new ShuffleAndDealCommand(roomId, pid, newSeed()),
                "DEAL", pid, logOf("DEAL", pid, null, null, null, null, lastSeed), List.of());
    }

    /** 逐张发牌第一步：洗牌并把整副牌放进房间牌堆（一步完成，随后每步发一张） */
    private void setupDeal() {
        long pid = anyPlayerId();
        long seed = newSeed();
        applyLogged(new SetupDealCommand(roomId, pid, seed),
                "SETUP_DEAL", pid, logOf("SETUP_DEAL", pid, null, null, null, null, seed), List.of());
    }

    /** 逐张发牌：发一张给当前座位，并让刚拿到牌的一方尝试抢亮 */
    private boolean dealOneCard() {
        long pid = anyPlayerId();
        Seat seat = room.dealTurn();
        CommandResult r = applyLogged(new DealNextCardCommand(roomId, pid),
                "DEAL_NEXT", pid, logOf("DEAL_NEXT", pid, null, null, null, null, null), List.of());
        if (r.success()) {
            trySnipeReveal(seat);
        }
        return r.success();
    }

    /**
     * 发牌过程中"抢亮"：摸到大王（第一局）或凑齐级牌/三王的一方当场亮。
     *
     * <p>真人靠客户端点图标抢，这里只驱动 bot —— 两边抢同一段窗口，
     * 谁先亮谁定（第一局大王声明不可反，手册 2.2）。
     */
    private void trySnipeReveal(Seat seat) {
        if (!isBot(seat)) {
            return;
        }
        Player p = room.playerAt(seat);
        BotBrain.RevealDecision d = BotBrain.decideReveal(room, p);
        if (d == null) {
            return;
        }
        applyLogged(new RevealTrumpCommand(roomId, p.playerId(), d.cards(), d.claimSuit()),
                "REVEAL", p.playerId(),
                logOf("REVEAL", p.playerId(), CardCodec.encodeAll(d.cards()),
                        d.claimSuit() != null ? d.claimSuit().name() : null, null, null, null),
                d.cards());
    }

    private long lastSeed;

    private long newSeed() {
        lastSeed = ThreadLocalRandom.current().nextLong();
        return lastSeed;
    }

    private long anyPlayerId() {
        return room.playerAt(Seat.NORTH).playerId();
    }

    /** 执行一步 bot 动作；返回 false = 无事可做（等待真人或终态） */
    private boolean step() {
        if (stuck) {
            return false;
        }
        GamePhase phase = room.phase();
        switch (phase) {
            case WAITING, ROUND_OVER -> {
                return false;
            }
            case DEALING -> {
                if (room.gameNumber() > stopAfterGames) {
                    return false;
                }
                if (room.dealRemaining() == 0) {
                    setupDeal();          // 第一步：洗牌裁堆（牌堆空 = 还没洗牌）
                    return true;
                }
                return dealOneCard();      // 之后每步发一张；发满 156 张时自动进 BIDDING
            }
            case BIDDING -> {
                return stepBidding();
            }
            case TRIBUTE -> {
                return stepTribute();
            }
            case BURYING -> {
                return stepBury();
            }
            case PLAYING -> {
                return stepPlay();
            }
            case SETTLING -> {
                long pid = anyPlayerId();
                applyLogged(new SettleRoundCommand(roomId, pid), "SETTLE",
                        pid, logOf("SETTLE", pid, null, null, null, null, null), List.of());
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** 亮王：按座位轮转，bot 能亮则亮；累计 4 家无动作则确认/底牌定主 */
    private boolean stepBidding() {
        if (bidPasses >= 4) {
            // 收口：有人亮 → 确认；无人亮 → 底牌定主（失败则重发）
            if (room.revealState().isPresent()) {
                bidTurn = Seat.NORTH;
                bidPasses = 0;
                applyLogged(new ConfirmTrumpCommand(roomId, anyPlayerId()), "CONFIRM",
                        anyPlayerId(), logOf("CONFIRM", anyPlayerId(), null, null, null, null, null),
                        List.of());
                return true;
            }
            for (int idx = 0; idx < 6; idx++) {
                List<Integer> indexes = room.isFirstRound()
                        ? List.of(0, 1, 2, 3)
                        : List.of(idx);
                CommandResult r = applyLogged(
                        new ResolveTrumpFromBottomCommand(roomId, anyPlayerId(), indexes),
                        "RESOLVE_BOTTOM", anyPlayerId(),
                        logOf("RESOLVE_BOTTOM", anyPlayerId(), null, null, null, indexes, null),
                        List.of());
                if (r.success()) {
                    bidTurn = Seat.NORTH;
                    bidPasses = 0;
                    return true;
                }
                if (room.isFirstRound()) {
                    break; // 首局 4 家翻牌一旦失败（翻出王）→ 重发
                }
            }
            // 底牌全王等极端情况：重新洗牌发牌
            bidTurn = Seat.NORTH;
            bidPasses = 0;
            deal();
            return true;
        }

        Seat seat = bidTurn;
        Player p = room.playerAt(seat);
        // 【真人回合】在线真人且手里有能亮/能反的牌 → 停在这里等他点。
        //
        // 原先这里不管座位是谁都直接 bidPasses++ 跳过去，等于真人根本没有回合：
        // 他的"窗口"只有 bot 转一圈的时间（约 3~7 秒），第一局 bot 手握大王时
        // 一圈就把庄抢走了，真人根本来不及点（2026-09-15 定位到的真因）。
        // 手里没料则直接算过 —— 不给玩家制造无意义的空等。
        boolean auto = isBot(seat) || !seatOnline(seat);
        // turnTimeoutMs <= 0 时（托管被显式关闭）不能停等，否则没人兜底会永久卡死亮主窗口
        if (!auto && hasRevealOption(seat) && turnTimeoutMs > 0) {
            return false; // 交由 armTimeout 装弹兜底（到点按"过"处理）
        }

        bidTurn = seat.next();
        if (auto) {
            BotBrain.RevealDecision d = BotBrain.decideReveal(room, p);
            if (d != null) {
                CommandResult r = applyLogged(
                        new RevealTrumpCommand(roomId, p.playerId(), d.cards(), d.claimSuit()),
                        "REVEAL", p.playerId(),
                        logOf("REVEAL", p.playerId(), CardCodec.encodeAll(d.cards()),
                                d.claimSuit() != null ? d.claimSuit().name() : null,
                                null, null, null), d.cards());
                if (r.success()) {
                    bidPasses = 0;
                    return true;
                }
                // 反不动（Q2b/Q2c 约束）→ 视为过
            }
        }
        bidPasses++;
        return true; // 过牌也是一步（继续轮转）
    }

    /** 该座位手里是否有可亮/可反的牌（决定亮主窗口是否要停下来等他） */
    private boolean hasRevealOption(Seat seat) {
        Player p = room.players().get(seat);
        if (p == null) {
            return false;
        }
        // 已经拿着当前最高声明的座位不再被等待：否则他亮完一轮后，手里剩下的牌
        // （比如同级牌还能"加固"）会让窗口再次停在他身上，玩家就得反复点同一个图标
        // 才能往下走，体感是"点了没反应"。想加固仍可主动点，只是不再强制他表态。
        if (room.revealState().map(r -> r.seat() == seat).orElse(false)) {
            return false;
        }
        return BotBrain.decideReveal(room, p) != null;
    }

    /**
     * 真人在亮主窗口点完图标后的收尾：把轮转推进到下一家。
     *
     * <p>为什么必须做：真人"有料"时 {@link #stepBidding()} 会停在他身上等
     * （return false），不推进就永远停在同一个人，他亮完也走不到下一家。
     * 亮成功则窗口重开（bidPasses 归零，其余三家仍可反主）。
     */
    private void advanceBidAfterHuman(long playerId, boolean success) {
        if (room.phase() != GamePhase.BIDDING || bidPasses >= 4) {
            return;
        }
        Player p = playerById(playerId);
        if (p == null || p.seat() != bidTurn) {
            return;
        }
        bidTurn = bidTurn.next();
        if (success) {
            bidPasses = 0;
        } else {
            bidPasses++; // 亮不动 → 视同过
        }
    }

    private boolean stepTribute() {
        // 1) 有义务未交：bot / 掉线真人 交贡（在线真人等其操作）
        var pendings = room.pendingTributes();
        if (!pendings.isEmpty()) {
            Seat payer = pendings.keySet().iterator().next();
            TributeObligation ob = pendings.get(payer);
            if (isHuman(payer) && seatOnline(payer)) {
                return false; // 等真人交贡
            }
            tributeReceiver.put(payer, ob.receiver());
            doTribute(payer, ob.bloodCount());
            return true;
        }
        // 2) 已收贡未还：收贡人还贡
        for (Map.Entry<Seat, List<Card>> e : room.tributeReceived().entrySet()) {
            if (room.isTributeReturned(e.getKey())) {
                continue;
            }
            Seat payer = e.getKey();
            Seat receiverSeat = tributeReceiver.getOrDefault(payer,
                    room.bankerSeat().orElse(Seat.NORTH));
            if (isHuman(receiverSeat) && seatOnline(receiverSeat)) {
                return false; // 等真人还贡
            }
            doReturnTribute(receiverSeat, payer, e.getValue().size());
            return true;
        }
        return false;
    }

    /** 交贡动作（bot / 超时托管共用） */
    private void doTribute(Seat payer, int bloodCount) {
        Player p = room.playerAt(payer);
        List<Card> cards = BotBrain.tributeCards(room, p, bloodCount);
        applyLogged(new TributeCommand(roomId, p.playerId(), cards), "TRIBUTE",
                p.playerId(),
                logOf("TRIBUTE", p.playerId(), CardCodec.encodeAll(cards), null, null, null, null),
                cards);
    }

    /** 还贡动作（bot / 超时托管共用） */
    private void doReturnTribute(Seat receiverSeat, Seat payer, int count) {
        Player receiver = room.playerAt(receiverSeat);
        List<Card> cards = BotBrain.returnTributeCards(room, receiver, count);
        applyLogged(new ReturnTributeCommand(roomId, receiver.playerId(), payer, cards),
                "RETURN_TRIBUTE", receiver.playerId(),
                logOf("RETURN_TRIBUTE", receiver.playerId(),
                        CardCodec.encodeAll(cards), null, payer.name(), null, null), cards);
    }

    private boolean stepBury() {
        // 【优先】他人捡牌扣王窗口（手册 2.3.5）：庄家扣完之后三家依次表态，
        // 队列问完（GameRoom.advanceBuryPick）才会进入 PLAYING。
        Seat picker = room.buryPickSeat();
        if (picker != null) {
            if (isHuman(picker) && seatOnline(picker)) {
                return false; // 等真人点"扣王 / 跳过"（超时由 onHumanTimeout 兜底为过）
            }
            doPickJoker(picker);
            return true;
        }
        Seat bankerSeat = room.bankerSeat().orElse(null);
        if (bankerSeat == null) {
            return false;
        }
        if (isHuman(bankerSeat) && seatOnline(bankerSeat)) {
            return false; // 等真人扣底
        }
        doBury(bankerSeat);
        return true;
    }

    /**
     * 底牌该不该对所有人下发（手册 2.3 系列）。快照 {@code bottom} 字段的唯一判据 ——
     * 客户端只认字段，不自己判断"现在该不该公开底牌"，避免规则在两端各写一份走散。
     *
     * <ol>
     *   <li>干锅局：全程公开（2.3.7；干锅跳过 BURYING，不在这里补发就永远看不到）；</li>
     *   <li>BURYING 且庄家还没收底：桌上那 6 张就是"原底牌"，本就该摊开（2.3.1）；</li>
     *   <li>{@code bottomRevealed}：扣王即公开（2.3.3 庄家扣王 / 2.3.5 他人扣王），
     *       他人扣王窗口期间也临时置真，好让三家看清牌面再决定押不押。</li>
     * </ol>
     * 三条都不成立 → 庄家扣回去的 6 张是机密，不下发（庄家本人另有私有 myBottom）。
     */
    private boolean bottomVisible() {
        if (room.isDryPot()) {
            return true;
        }
        if (room.phase() == GamePhase.BURYING && !room.isBottomTaken()) {
            return true;
        }
        return room.isBottomRevealed();
    }

    /**
     * 他人捡牌扣王动作（bot / 掉线接管 / 超时托管共用）。
     *
     * <p>【依赖不变量】{@link BotBrain#pickBottomJokers} 选出的牌必须能通过
     * {@link PickBottomJokerCommand}（张数上限 = 底牌可捡的非分牌数）。与埋牌同理 ——
     * 一旦不成立，失败计数会持续累加把房间判成 stuck、bot 驱动永久停摆。
     * 护栏见 {@code BotPickJokerConsistencyTest}。
     *
     * <p>失败一律退化为"过"：扣王是可选动作，卡在这里比放弃更糟 —— 窗口不推进，
     * 三家问不完，牌局就永远进不了 PLAYING（这正是"整局冻死在扣底"那类事故的形态）。
     */
    private void doPickJoker(Seat picker) {
        Player p = room.playerAt(picker);
        List<Card> jokers = BotBrain.pickBottomJokers(room, p);
        CommandResult r = applyLogged(new PickBottomJokerCommand(roomId, p.playerId(), jokers),
                "PICK_JOKER", p.playerId(),
                logOf("PICK_JOKER", p.playerId(), CardCodec.encodeAll(jokers), null, null, null, null),
                jokers);
        if (r.isFailure()) {
            applyLogged(new PickBottomJokerCommand(roomId, p.playerId(), List.of()),
                    "PICK_JOKER", p.playerId(),
                    logOf("PICK_JOKER", p.playerId(), null, null, null, null, null), List.of());
        }
    }

    /**
     * 扣底动作（bot / 超时托管共用）。
     *
     * <p>【依赖不变量】{@link BotBrain#buryCards} 选出的牌必须能通过 {@link BuryBottomCommand}。
     * 一旦不成立，这里会「首选失败 → 兜底（原样底牌）再失败」地循环，失败计数每轮 +2，
     * 十几轮就把房间判成 stuck、bot 停摆，整局冻死在扣底（2026-09-17 就是这么冻的：
     * bot 的干锅口径漏了首局豁免，把含小王的底牌原样提交，撞上 Q5 扣王校验）。
     * 该不变量由 {@code BotBuryCommandConsistencyTest} 锁住。
     */
    private void doBury(Seat bankerSeat) {
        Player banker = room.playerAt(bankerSeat);
        List<Card> combined = new ArrayList<>(banker.hand());
        if (!room.isBottomTaken()) {
            combined.addAll(room.bottomCards());
        }
        List<Card> bury = BotBrain.buryCards(room, banker, combined);
        CommandResult r = applyLogged(new BuryBottomCommand(roomId, banker.playerId(), bury),
                "BURY", banker.playerId(),
                logOf("BURY", banker.playerId(), CardCodec.encodeAll(bury), null, null, null, null),
                bury);
        if (r.isFailure()) {
            // 兜底：原样扣回（干锅等边界）
            List<Card> original = room.bottomCards();
            applyLogged(new BuryBottomCommand(roomId, banker.playerId(), original),
                    "BURY", banker.playerId(),
                    logOf("BURY", banker.playerId(), CardCodec.encodeAll(original), null, null, null, null),
                    original);
        }
    }

    private boolean stepPlay() {
        Seat turn = room.turnSeat().orElse(null);
        if (turn == null) {
            return false;
        }
        if (isHuman(turn) && seatOnline(turn)) {
            return false; // 等真人出牌（超时由 onHumanTimeout 代打）
        }
        return actPlay(turn);
    }

    /** 出牌动作（bot / 掉线接管 / 超时托管共用）；返回 false = 状态损坏 */
    private boolean actPlay(Seat turn) {
        Player p = room.playerAt(turn);
        if (p.hand().isEmpty()) {
            // PLAYING 阶段轮到出牌的人手牌为空 = 状态损坏（正常应转入 SETTLING），显式暴露
            stuck = true;
            emit("BOT_STUCK", p.playerId(), false,
                    "PLAYING 阶段轮到 " + turn + " 但其手牌为空（状态损坏）", List.of());
            return false;
        }
        List<Card> cards;
        if (room.currentTrick().isEmpty()) {
            cards = BotBrain.leadPlay(room, p);
        } else {
            cards = BotBrain.followPlay(room, p);
            if (cards == null) {
                stuck = true;
                emit("BOT_STUCK", p.playerId(), false,
                        "bot 找不到合法跟牌: " + p.hand(), List.of());
                return false;
            }
        }
        applyLogged(new PlayCardsCommand(roomId, p.playerId(), cards), "PLAY",
                p.playerId(),
                logOf("PLAY", p.playerId(), CardCodec.encodeAll(cards), null, null, null, null), cards);
        return true;
    }

    // ================= T-704 超时托管 =================

    private boolean isHuman(Seat seat) {
        return !isBot(seat);
    }

    /** 该座位是否有活跃连接（真人掉线 → false → bot 接管） */
    private boolean seatOnline(Seat seat) {
        Player p = room.players().get(seat);
        if (p == null) {
            return false;
        }
        for (Sink s : sinks) {
            if (s.playerId() == p.playerId()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 当前驱动停在等真人行动时调用：确定等待的真人座位并装弹超时任务。
     * 驱动循环每次停下都会重装（先取消旧的），真人行动后 actionSeq 变化使旧弹空响。
     */
    private void armTimeout() {
        cancelTimeout();
        if (turnTimeoutMs <= 0 || scheduler == null || stuck) {
            return;
        }
        Seat waiter = humanWaiter();
        if (waiter == null) {
            return;
        }
        timeoutArmedSeq = actionSeq;
        timeoutTask = scheduler.schedule(this::onHumanTimeout, turnTimeoutMs, TimeUnit.MILLISECONDS);
    }

    /** 当前在等哪个真人行动；不在等真人返回 null */
    private Seat humanWaiter() {
        switch (room.phase()) {
            case BIDDING -> {
                // 亮主窗口停在某个真人身上时，超时任务要认这个座位（到点视为"过"）
                if (bidPasses < 4) {
                    Seat turn = bidTurn;
                    if (isHuman(turn) && seatOnline(turn) && hasRevealOption(turn)) {
                        return turn;
                    }
                }
            }
            case PLAYING -> {
                Seat turn = room.turnSeat().orElse(null);
                if (turn != null && isHuman(turn)) {
                    return turn;
                }
            }
            case BURYING -> {
                // 他人扣王窗口优先：等的是当前轮到的那一家（超时按"过"处理）
                Seat picker = room.buryPickSeat();
                if (picker != null) {
                    if (isHuman(picker) && seatOnline(picker)) {
                        return picker;
                    }
                } else {
                    Seat banker = room.bankerSeat().orElse(null);
                    if (banker != null && isHuman(banker)) {
                        return banker;
                    }
                }
            }
            case TRIBUTE -> {
                for (Seat payer : room.pendingTributes().keySet()) {
                    if (isHuman(payer)) {
                        return payer;
                    }
                }
                for (Map.Entry<Seat, List<Card>> e : room.tributeReceived().entrySet()) {
                    if (room.isTributeReturned(e.getKey())) {
                        continue;
                    }
                    Seat receiver = tributeReceiver.getOrDefault(e.getKey(),
                            room.bankerSeat().orElse(Seat.NORTH));
                    if (isHuman(receiver)) {
                        return receiver;
                    }
                }
            }
            default -> {
            }
        }
        return null;
    }

    /** 超时到点：状态未变（无人行动）→ BotBrain 代打 */
    private void onHumanTimeout() {
        runInThread(() -> {
            timeoutTask = null;
            if (stuck || actionSeq != timeoutArmedSeq) {
                return; // 空响：真人已行动或状态已变
            }
            Seat waiter = humanWaiter();
            if (waiter == null) {
                return;
            }
            Player p = room.playerAt(waiter);
            emit("AUTO", p.playerId(), true, "超时托管：系统代打", List.of());
            switch (room.phase()) {
                case BIDDING -> {
                    // 亮主窗口到点没点 → 视为"过"，推进轮转让下一家表态
                    if (waiter == bidTurn) {
                        bidTurn = bidTurn.next();
                        bidPasses++;
                    }
                }
                case PLAYING -> actPlay(waiter);
                case BURYING -> {
                    // 扣王窗口到点没点 → 按"过"处理（推进到下一家），不能卡住整桌。
                    // 这里必须区分"等的是扣王"还是"等的是扣底"：拿扣王窗口的座位去发
                    // BURY 命令会被"只有庄家能扣底"挡住，反复失败就把房间判成 stuck。
                    if (room.buryPickSeat() == waiter) {
                        doPickJoker(waiter);
                    } else {
                        doBury(waiter);
                    }
                }
                case TRIBUTE -> {
                    if (room.pendingTributes().containsKey(waiter)) {
                        doTribute(waiter, room.pendingTributes().get(waiter).bloodCount());
                    } else {
                        for (Map.Entry<Seat, List<Card>> e : room.tributeReceived().entrySet()) {
                            if (room.isTributeReturned(e.getKey())) {
                                continue;
                            }
                            Seat receiver = tributeReceiver.getOrDefault(e.getKey(),
                                    room.bankerSeat().orElse(Seat.NORTH));
                            if (receiver == waiter) {
                                doReturnTribute(receiver, e.getKey(), e.getValue().size());
                                break;
                            }
                        }
                    }
                }
                default -> {
                }
            }
            scheduleDrive();
        });
    }

    private void cancelTimeout() {
        if (timeoutTask != null) {
            timeoutTask.cancel(false);
            timeoutTask = null;
        }
        timeoutArmedSeq = -1;
    }

    // ================= 应用命令 + 留痕 + 广播 =================

    private CommandResult applyLogged(GameCommand cmd, String op, long playerId, String logJson,
                                      List<Card> eventCards) {
        CommandResult r;
        try {
            r = room.apply(cmd);
        } catch (Exception e) {
            r = CommandResult.fail("命令执行异常: " + e.getMessage());
        }
        if (!replaying && logJson != null) {
            store.append(roomId, logJson);
        }
        emit(op, playerId, r.success(), r.reason(), eventCards);
        if (r.isFailure()) {
            if (humanCommand) {
                // 真人命令失败（例：超时托管已代打、界面未刷新时连点“出牌”）不算 bot 驱动故障，
                // 更不能累加到 30 次把整局判 stuck；顺手清零，避免历史计数把后续失败推过阈值。
                consecutiveFailures = 0;
            } else {
                consecutiveFailures++;
                if (consecutiveFailures > 30) {
                    stuck = true;
                    emit("STUCK", playerId, false, "连续命令失败过多，bot 停止驱动", List.of());
                }
            }
        } else {
            consecutiveFailures = 0;
            actionSeq++; // T-704：任何成功行动都会让已装弹的超时任务失效
        }
        return r;
    }

    private String logOf(String op, long playerId, List<String> cards, String suit,
                         String payee, List<Integer> indexes, Long seed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", op);
        m.put("playerId", playerId);
        if (cards != null) {
            m.put("cards", cards);
        }
        if (suit != null) {
            m.put("suit", suit);
        }
        if (payee != null) {
            m.put("payee", payee);
        }
        if (indexes != null) {
            m.put("indexes", indexes);
        }
        if (seed != null) {
            m.put("seed", seed);
        }
        return JsonUtil.write(m);
    }

    private void emit(String op, long playerId, boolean success, String reason, List<Card> cards) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("type", "event");
        ev.put("op", op);
        ev.put("playerId", playerId);
        Player p = playerById(playerId);
        ev.put("seat", p != null ? p.seat().name() : null);
        ev.put("cards", CardCodec.encodeAll(cards));
        ev.put("success", success);
        ev.put("reason", reason);
        ev.put("phase", room.phase().name());
        ev.put("gameNumber", room.gameNumber());
        ev.put("level", room.currentLevel());
        String json = JsonUtil.write(ev);
        for (Consumer<String> l : rawListeners) {
            l.accept(json);
        }
        for (Sink sink : sinks) {
            sink.send(json);
            sink.send(snapshotFor(sink.playerId()));
        }
    }

    // ================= 快照 =================

    /** 按玩家定制快照：全桌公开状态 + 该玩家自己的手牌（playerId &lt;=0 无私有视图） */
    public String snapshotFor(long playerId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "snapshot");
        m.put("roomId", roomId);
        m.put("phase", room.phase().name());
        m.put("gameNumber", room.gameNumber());
        // 是否本轮第一局：第一局"抢亮 1 张大王"与第二局起"亮/反级牌"是两套完全不同的
        // 亮主规则，客户端要靠它决定图标栏点亮逻辑（光看 gameNumber 不行——出锅回到
        // 第一局时 gameNumber 并不会归 1）。只读字段，不参与任何规则判定。
        m.put("firstRound", room.isFirstRound());
        m.put("level", room.currentLevel());
        Optional<TrumpContext> trump = room.trump();
        if (trump.isPresent()) {
            m.put("trump", Map.of("level", trump.get().level(),
                    "suit", trump.get().trumpSuit().name()));
        }
        Optional<TrumpReveal> reveal = room.revealState();
        if (reveal.isPresent()) {
            // count = 级牌张数（非级牌声明恒为 0）。客户端反主高亮必须知道它：
            // "2 张反 1 张、3 张反 2 张或 1 张"这条只看 kind 是算不出来的。
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("kind", reveal.get().kind().name());
            // 第一局"已亮大王、主花色待摸"时主花色为空 → 不下发该字段（客户端按 undefined 处理）。
            // 注意不能用 Map.of：它不接受 null 值，会直接抛 NPE。
            if (reveal.get().suit() != null) {
                rm.put("suit", reveal.get().suit().name());
            }
            rm.put("count", reveal.get().levelCardCount());
            rm.put("seat", reveal.get().seat().name());
            m.put("reveal", rm);
        }
        m.put("banker", room.bankerSeat().map(Seat::name).orElse(null));
        m.put("turn", room.turnSeat().map(Seat::name).orElse(null));
        m.put("dryPot", room.isDryPot());
        // ---- 底牌公开（手册 2.3.1 ~ 2.3.8） ----
        // 【口径说明】手册 2.3.1 写的是"发牌或反主结束后公开"，落到实现里取的是
        // **进入扣底（庄家收底）那一刻**：收底之前这 6 张谁都没碰过，提前摊给所有人看
        // 等于把底牌内容泄给庄家的对手去影响亮主/反主决策。手册 2.3.3 那句
        // "不扣王时底牌不公开"指的是**庄家扣回去的新底牌**，两条并不冲突：
        // 摊开给大家看的是"原底牌"，保密的是"新底牌"。
        //
        // 会对所有人下发这 6 张的情形（见 bottomVisible()）：
        //   ① 扣底阶段（BURYING）且庄家还没收底：桌上这 6 张就是"原底牌"，本就该摊开；
        //   ② 干锅局（dryPot）：底牌不能替换、只能原样扣回（2.3.7），而且干锅会**整段跳过
        //      BURYING 直接进 PLAYING**（GameRoom.enterBuryingPhase），不在这里补发，
        //      玩家从头到尾都看不到那 6 张；
        //   ③ bottomRevealed —— **扣王就公开**（2.3.3 庄家扣王 / 2.3.5 他人扣王）。
        //      公开是"押中之后"的后果，**扣王窗口期间不摊牌**：窗口开着而还没人扣时，
        //      底牌依旧是庄家扣出时那份机密，那三家只能从 pickSeat / pickMax 知道
        //      "谁表态、最多能押几张"；真有人扣了才翻成公开并一直公开到结算，
        //      三家全"过"则始终没露过面，收口时由 GameRoom.advanceBuryPick →
        //      refreshBottomReveal 把标志校正回机密。
        // 其余时间（正常局 PLAYING 起且底牌无王）**不下发**：庄家扣回去的 6 张是机密。
        // **庄家自己要能回看**（否则他连扣了哪 6 张都无从查证）—— 那条走下面的私有字段
        // myBottom，只发给庄家本人，不是公开。
        //
        // 【依赖的不变量】BURYING 阶段 room.bottomCards() 在庄家收底前必须仍是**原底牌**：
        // 覆盖动作 room.setBottomCards(新牌) 只发生在 BuryBottomCommand 成功路径的**末尾**，
        // 失败路径不会走到。由 game-room 的 BottomRevealTest 锁住。
        if (bottomVisible()) {
            m.put("bottom", CardCodec.encodeAll(room.bottomCards()));
        }
        m.put("bottomRevealed", room.isBottomRevealed());
        // ---- 他人捡牌扣王窗口（手册 2.3.5） ----
        // 只下发"轮到谁"和"最多能扣几张"，客户端据此决定是否显示"扣王 / 跳过"，
        // 不在客户端复算规则（谁能扣、能扣几张一律以服务端为准）。
        Seat buryPickSeat = room.buryPickSeat();
        if (buryPickSeat != null) {
            m.put("pickSeat", buryPickSeat.name());
            m.put("pickMax", room.bottomPickableCount());
        }
        m.put("followRule", room.followRule().name());
        Map<String, Integer> hands = new LinkedHashMap<>();
        for (Seat seat : Seat.values()) {
            Player p = room.players().get(seat);
            if (p != null) {
                hands.put(seat.name(), p.hand().size());
            }
        }
        m.put("hands", hands);
        if (playerId > 0) {
            Player me = playerById(playerId);
            if (me != null) {
                List<Card> hand = new ArrayList<>(me.hand());
                if (trump.isPresent()) {
                    hand.sort(new CardComparator(trump.get()));
                } else {
                    hand.sort(Comparator.comparingInt(Card::rank));
                }
                m.put("yourHand", CardCodec.encodeAll(hand));

                // ---- 庄家私有底牌（扣完底之后回看用） ----
                // 收底那一刻摊开的 6 张原底牌只在 BURYING 阶段公开过一次；扣底成功后
                // room.bottomCards() 已被 BuryBottomCommand 换成**庄家扣出的新 6 张**，
                // 客户端若不再下发就彻底"没有能看到底牌的地方"了 —— 连庄家自己都查不到
                // 刚才扣了哪 6 张。对闲家这 6 张是机密（手册 2.3.3），对庄家却是他必须能
                // 回看的信息，所以这里**按玩家定制**：只有本端座位就是庄家、且已收底才下发。
                //
                // 【不会提前泄露】resetRoundState()（由 SettleRoundCommand 收尾调用）会把
                // bottomTaken 置回 false，所以下一局发牌/亮主期间即便仍是同一家坐庄，
                // 也不会把上一局的底牌带出来；新一局的那 6 张要等庄家真的收底
                // （BuryBottomCommand / 干锅 autoBuryIfDryPot）之后才可能下发。
                // 干锅局：bottomTaken 为 true，但同一份牌面已经作为公开 bottom 下发，
                // 客户端按"公开优先"渲染，不会重复展示（见 TableUI.renderBottom）。
                Seat bankerSeat = room.bankerSeat().orElse(null);
                if (room.isBottomTaken() && bankerSeat != null && me.seat() == bankerSeat) {
                    m.put("myBottom", CardCodec.encodeAll(room.bottomCards()));
                }
            }
        }
        Optional<Trick> trick = room.currentTrick();
        // 上一圈刚打完时 currentTrick 已清空，但 lastCompletedTrick 还留着那四张；
        // 出牌/结算阶段把它也发下去，玩家才能看清本轮四张出牌（否则第 4 张直接消失）
        if (trick.isEmpty() && (room.phase() == GamePhase.PLAYING || room.phase() == GamePhase.SETTLING)) {
            trick = room.lastCompletedTrick();
        }
        if (trick.isPresent()) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("leader", trick.get().leader().name());
            t.put("leadCards", CardCodec.encodeAll(trick.get().lead().cards()));
            List<Map<String, Object>> plays = new ArrayList<>();
            for (Trick.PlayRecord pr : trick.get().plays()) {
                plays.add(Map.of("seat", pr.seat().name(),
                        "cards", CardCodec.encodeAll(pr.cards())));
            }
            t.put("plays", plays);
            // 一圈凑齐 4 手时下发赢家座位：客户端收墩动画必须收向真正的赢家，
            // 不能依赖快照里的 turn —— 下一圈一旦开牌，turn 已经变成"下一个跟牌人"了。
            if (trick.get().isComplete()) {
                t.put("winner", trick.get().winnerSeat().name());
            }
            m.put("trick", t);
        }
        Map<String, Integer> points = new LinkedHashMap<>();
        room.trickPoints().forEach((team, v) -> points.put(team.name(), v));
        m.put("trickPoints", points);
        // 分牌明细（team → 已收走的 5/10/K 编码列表）：客户端「闲家得分」展开面板用。
        // 只下发分牌，整圈 4 张不必下发（一局上百张，既费带宽又无展示价值）。
        Map<String, Object> taken = new LinkedHashMap<>();
        room.takenPointCards().forEach((team, cs) -> taken.put(team.name(), CardCodec.encodeAll(cs)));
        m.put("takenPointCards", taken);
        Map<String, Object> tributes = new LinkedHashMap<>();
        room.pendingTributes().forEach((payer, ob) -> tributes.put(payer.name(),
                Map.of("blood", ob.bloodCount(), "receiver", ob.receiver().name())));
        m.put("pendingTributes", tributes);
        Optional<RoundSettlement.Result> settle = room.lastSettlement();
        if (settle.isPresent()) {
            RoundSettlement.Result s = settle.get();
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("attackerScore", s.attackerScore());
            sm.put("bankerScore", s.bankerScore());
            sm.put("attackerTakesBank", s.attackerTakesBank());
            sm.put("attackerPromoted", s.attackerPromoted());
            sm.put("bankerPromoted", s.bankerPromoted());
            sm.put("dugBottom", s.dugBottom());
            m.put("settlement", sm);
        }
        return JsonUtil.write(m);
    }

    // ================= 线程模型 =================

    private void runInThread(Runnable task) {
        if (scheduler != null) {
            scheduler.execute(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    emit("ERROR", 0, false, "房间线程异常: " + e.getMessage(), List.of());
                }
            });
        } else {
            task.run();
        }
    }

    private void scheduleDrive() {
        if (scheduler == null) {
            return;
        }
        if (drivePending.compareAndSet(false, true)) {
            scheduler.schedule(() -> {
                drivePending.set(false);
                try {
                    if (!stuck && step()) {
                        scheduleDrive();
                    } else if (!stuck) {
                        armTimeout(); // T-704：停下等真人 → 装弹超时托管
                    }
                } catch (Exception e) {
                    // 调度器会吞掉任务异常导致驱动循环无声死亡，这里显式暴露
                    stuck = true;
                    emit("DRIVE_ERROR", 0, false, "bot 驱动异常: " + e, List.of());
                }
            }, nextBotDelayMs(), TimeUnit.MILLISECONDS);
        }
    }

    /** 固定延迟（测试/演示）或拟人化随机思考时长（T-701） */
    private long nextBotDelayMs() {
        // 【发牌节奏单独一档】发牌必须快：若沿用 bot 思考时长（ServerMain 设的
        // 0.8~2.5s），156 张要发好几分钟。固定用 dealDelayMs（默认 60ms → 约 9 秒）。
        // 若调用方本来就设了更快的节奏（测试/演示，如 botDelayMs=1），跟随更快的那个，
        // 否则"快速跑完整局"的测试会被发牌本身拖到超时。
        if (room.phase() == GamePhase.DEALING) {
            long base = thinkMaxMs <= 0 ? botDelayMs : thinkMinMs;
            return Math.min(dealDelayMs, Math.max(base, 1));
        }
        if (thinkMaxMs <= 0) {
            return botDelayMs;
        }
        return ThreadLocalRandom.current().nextLong(thinkMinMs, thinkMaxMs + 1);
    }
}
