package com.gunzihall.room;

import com.gunzihall.domain.action.BuryBottomCommand;
import com.gunzihall.domain.action.CommandResult;
import com.gunzihall.domain.action.ConfirmTrumpCommand;
import com.gunzihall.domain.action.GameCommand;
import com.gunzihall.domain.action.PlayCardsCommand;
import com.gunzihall.domain.action.ResolveTrumpFromBottomCommand;
import com.gunzihall.domain.action.ReturnTributeCommand;
import com.gunzihall.domain.action.RevealTrumpCommand;
import com.gunzihall.domain.action.SettleRoundCommand;
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
            deal();
            scheduleDrive();
        });
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
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    // ================= 命令规格（客户端协议 + 重放共用） =================

    public record CommandSpec(String op, long playerId, List<String> cards, String suit,
                              String payee, List<Integer> indexes, Long seed) {
    }

    private void handleSpec(CommandSpec spec, boolean replay) {
        long pid = spec.playerId();
        switch (spec.op()) {
            case "DEAL" -> applyLogged(
                    new ShuffleAndDealCommand(roomId, pid,
                            spec.seed() != null ? spec.seed() : newSeed()),
                    "DEAL", pid, logOf("DEAL", pid, spec.cards(), spec.suit(), null, null, spec.seed()),
                    List.of());
            case "REVEAL" -> applyLogged(
                    new RevealTrumpCommand(roomId, pid, CardCodec.decodeAll(spec.cards()),
                            Suit.valueOf(spec.suit())),
                    "REVEAL", pid, logOf("REVEAL", pid, spec.cards(), spec.suit(), null, null, null),
                    CardCodec.decodeAll(spec.cards()));
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
            case "PLAY" -> applyLogged(
                    new PlayCardsCommand(roomId, pid, CardCodec.decodeAll(spec.cards())),
                    "PLAY", pid, logOf("PLAY", pid, spec.cards(), null, null, null, null),
                    CardCodec.decodeAll(spec.cards()));
            case "SETTLE" -> applyLogged(new SettleRoundCommand(roomId, pid),
                    "SETTLE", pid, logOf("SETTLE", pid, null, null, null, null, null), List.of());
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

    // ================= bot 驱动状态机 =================

    private void deal() {
        long pid = anyPlayerId();
        applyLogged(new ShuffleAndDealCommand(roomId, pid, newSeed()),
                "DEAL", pid, logOf("DEAL", pid, null, null, null, null, lastSeed), List.of());
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
                deal();
                return true;
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
        bidTurn = seat.next();
        Player p = room.playerAt(seat);
        if (isBot(seat)) {
            BotBrain.RevealDecision d = BotBrain.decideReveal(room, p);
            if (d != null) {
                CommandResult r = applyLogged(
                        new RevealTrumpCommand(roomId, p.playerId(), d.cards(), d.claimSuit()),
                        "REVEAL", p.playerId(),
                        logOf("REVEAL", p.playerId(), CardCodec.encodeAll(d.cards()),
                                d.claimSuit().name(), null, null, null), d.cards());
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

    private boolean stepTribute() {
        // 1) 有义务未交：bot 交贡
        var pendings = room.pendingTributes();
        if (!pendings.isEmpty()) {
            Seat payer = pendings.keySet().iterator().next();
            TributeObligation ob = pendings.get(payer);
            if (!isBot(payer)) {
                return false; // 等真人交贡
            }
            tributeReceiver.put(payer, ob.receiver());
            Player p = room.playerAt(payer);
            List<Card> cards = BotBrain.tributeCards(room, p, ob.bloodCount());
            applyLogged(new TributeCommand(roomId, p.playerId(), cards), "TRIBUTE",
                    p.playerId(),
                    logOf("TRIBUTE", p.playerId(), CardCodec.encodeAll(cards), null, null, null, null),
                    cards);
            return true;
        }
        // 2) 已收贡未还：bot 收贡人还贡
        for (Map.Entry<Seat, List<Card>> e : room.tributeReceived().entrySet()) {
            if (room.isTributeReturned(e.getKey())) {
                continue;
            }
            Seat payer = e.getKey();
            Seat receiverSeat = tributeReceiver.getOrDefault(payer,
                    room.bankerSeat().orElse(Seat.NORTH));
            if (!isBot(receiverSeat)) {
                return false; // 等真人还贡
            }
            Player receiver = room.playerAt(receiverSeat);
            List<Card> cards = BotBrain.returnTributeCards(room, receiver, e.getValue().size());
            applyLogged(new ReturnTributeCommand(roomId, receiver.playerId(), payer, cards),
                    "RETURN_TRIBUTE", receiver.playerId(),
                    logOf("RETURN_TRIBUTE", receiver.playerId(),
                            CardCodec.encodeAll(cards), null, payer.name(), null, null), cards);
            return true;
        }
        return false;
    }

    private boolean stepBury() {
        Seat bankerSeat = room.bankerSeat().orElse(null);
        if (bankerSeat == null) {
            return false;
        }
        if (!isBot(bankerSeat)) {
            return false; // 等真人扣底
        }
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
        return true;
    }

    private boolean stepPlay() {
        Seat turn = room.turnSeat().orElse(null);
        if (turn == null) {
            return false;
        }
        if (!isBot(turn)) {
            return false; // 等真人出牌
        }
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
            consecutiveFailures++;
            if (consecutiveFailures > 30) {
                stuck = true;
                emit("STUCK", playerId, false, "连续命令失败过多，bot 停止驱动", List.of());
            }
        } else {
            consecutiveFailures = 0;
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
        m.put("level", room.currentLevel());
        Optional<TrumpContext> trump = room.trump();
        if (trump.isPresent()) {
            m.put("trump", Map.of("level", trump.get().level(),
                    "suit", trump.get().trumpSuit().name()));
        }
        Optional<TrumpReveal> reveal = room.revealState();
        if (reveal.isPresent()) {
            m.put("reveal", Map.of("kind", reveal.get().kind().name(),
                    "suit", reveal.get().suit().name(),
                    "seat", reveal.get().seat().name()));
        }
        m.put("banker", room.bankerSeat().map(Seat::name).orElse(null));
        m.put("turn", room.turnSeat().map(Seat::name).orElse(null));
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
            }
        }
        Optional<Trick> trick = room.currentTrick();
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
            m.put("trick", t);
        }
        Map<String, Integer> points = new LinkedHashMap<>();
        room.trickPoints().forEach((team, v) -> points.put(team.name(), v));
        m.put("trickPoints", points);
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
                    }
                } catch (Exception e) {
                    // 调度器会吞掉任务异常导致驱动循环无声死亡，这里显式暴露
                    stuck = true;
                    emit("DRIVE_ERROR", 0, false, "bot 驱动异常: " + e, List.of());
                }
            }, botDelayMs, TimeUnit.MILLISECONDS);
        }
    }
}
