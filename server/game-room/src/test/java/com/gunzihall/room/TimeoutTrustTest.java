package com.gunzihall.room;

import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.player.HumanPlayer;
import com.gunzihall.domain.player.Seat;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-704 超时托管 / 断线接管的行为级测试。
 *
 * <p>场景：房间 1001 = 3 bot + NORTH 真人。bot 思考 1ms（快），真人超时 300ms。
 * 两条铁律：
 * <ul>
 *   <li>在线真人挂机不操作 → 超时后系统代打，牌局不卡死；</li>
 *   <li>真人掉线 → bot 立即接管（不等超时）。</li>
 * </ul>
 */
class TimeoutTrustTest {

    /** 测试 sink：只记录收包，真人视为在线 */
    private static final class RecordSink implements RoomActor.Sink {
        final long playerId;
        final List<String> messages = new CopyOnWriteArrayList<>();

        RecordSink(long playerId) {
            this.playerId = playerId;
        }

        @Override
        public long playerId() {
            return playerId;
        }

        @Override
        public void send(String json) {
            messages.add(json);
        }
    }

    private RoomActor buildActor(long turnTimeoutMs, RecordSink humanSink, long botDelayMs) {
        RoomActor actor = new RoomActor(1001, new InMemoryStateStore(), botDelayMs);
        actor.join(new BotPlayer(9000, Seat.EAST), true);
        actor.join(new BotPlayer(9001, Seat.SOUTH), true);
        actor.join(new BotPlayer(9002, Seat.WEST), true);
        actor.join(new HumanPlayer(1001, Seat.NORTH), false);
        if (humanSink != null) {
            actor.addSink(humanSink); // 有 sink = 真人在线
        }
        actor.setTurnTimeout(turnTimeoutMs);
        actor.start();
        return actor;
    }

    /** 轮询直到真人（NORTH）轮到出牌，超时抛错 */
    private void awaitNorthTurn(RoomActor actor) {
        awaitNorthTurn(actor, null);
    }

    /**
     * 轮询直到真人（NORTH）轮到出牌，超时抛错。
     *
     * <p>【为什么超时信息要带上现场】这个用例曾经偶发红过一次：卡在 BURYING。
     * 只报"卡在: XXX"根本看不出是"真人被要求扣底（正常等待）"还是"托管没兜住（真故障）"，
     * 下一次复现又得重新猜。所以这里把 stuck / 庄家 / 最近几条关键事件一起打出来。
     */
    private void awaitNorthTurn(RoomActor actor, RecordSink sink) {
        Instant deadline = Instant.now().plusSeconds(15);
        while (Instant.now().isBefore(deadline)) {
            if (actor.room().phase().name().equals("PLAYING")
                    && actor.room().turnSeat().map(s -> s == Seat.NORTH).orElse(false)) {
                return;
            }
            // 庄家落到真人头上时，房间会（正常地）停在 BURYING 等真人扣底。
            // 本用例等的是"出牌"，不是"扣底" —— 真人该扣底就替他扣，否则必假超时。
            if (actor.room().phase().name().equals("BURYING")
                    && actor.room().bankerSeat().map(s -> s == Seat.NORTH).orElse(false)) {
                buryAsNorth(actor);
            }
            sleep(20);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("15s 内未等到 NORTH 出牌：phase=").append(actor.room().phase())
                .append(" banker=").append(actor.room().bankerSeat().orElse(null))
                .append(" turn=").append(actor.room().turnSeat().orElse(null))
                .append(" gameNumber=").append(actor.room().gameNumber())
                .append(" 首局=").append(actor.room().isFirstRound())
                .append(" stuck=").append(actor.isStuck())
                .append("\n  真人持牌=").append(actor.room().playerAt(Seat.NORTH).hand().size());
        if (sink != null) {
            int n = 0;
            sb.append("\n  关键事件（时间顺序）：");
            for (String m : sink.messages) {
                boolean key = m.contains("\"success\":false") || m.contains("\"op\":\"AUTO\"")
                        || m.contains("\"op\":\"BURY\"") || m.contains("\"op\":\"TRIBUTE\"")
                        || m.contains("\"op\":\"RETURN_TRIBUTE\"") || m.contains("STUCK")
                        || m.contains("DRIVE_ERROR");
                if (key && n++ < 8) {
                    sb.append("\n    ").append(m);
                }
            }
        }
        throw new AssertionError(sb.toString());
    }

    /** 轮询直到断言成立或超时 */
    private void awaitTrue(String what, long maxMs, java.util.function.BooleanSupplier cond) {
        Instant deadline = Instant.now().plusMillis(maxMs);
        while (Instant.now().isBefore(deadline)) {
            if (cond.getAsBoolean()) {
                return;
            }
            sleep(20);
        }
        throw new AssertionError("等待超时: " + what);
    }

    @Test
    void onlineIdle_humanAutoPlayedAfterTimeout() {
        RecordSink sink = new RecordSink(1001);
        RoomActor actor = buildActor(300, sink, 1);
        try {
            awaitNorthTurn(actor);
            int handBefore = actor.room().playerAt(Seat.NORTH).hand().size();
            assertTrue(handBefore > 30, "出牌阶段 NORTH 应持 39 张（162 张 - 6 底 ÷ 4），实际: " + handBefore);

            // 挂机：什么都不做，等系统代打
            awaitTrue("超时后 NORTH 手牌应减少（系统代打）", 5000,
                    () -> actor.room().playerAt(Seat.NORTH).hand().size() < handBefore);

            // 代打事件应广播（客户端显示"超时托管"）
            boolean autoSeen = sink.messages.stream()
                    .anyMatch(m -> m.contains("\"op\":\"AUTO\""));
            assertTrue(autoSeen, "应广播 AUTO 超时托管事件");
        } finally {
            actor.shutdown();
        }
    }

    @Test
    void offlineHuman_takenOverImmediatelyWithoutTimeout() {
        // 超时给到 60s：若靠超时早就等死了，只有"掉线立即接管"能救
        RoomActor actor = buildActor(60_000, null, 1);
        try {
            awaitNorthTurn(actor);
            int handBefore = actor.room().playerAt(Seat.NORTH).hand().size();

            // 无超时依赖：bot 节奏（1ms）下应立即代打出牌
            awaitTrue("掉线真人应被立即接管出牌", 3000,
                    () -> actor.room().playerAt(Seat.NORTH).hand().size() < handBefore);
        } finally {
            actor.shutdown();
        }
    }

    @Test
    void humanPlaysBeforeTimeout_timeoutFiresAsNoop() {
        // bot 每步 100ms：真人手动出牌后本墩要 ~300ms 才收完。
        // 超时 200ms 必然落在墩进行中（等待者是 bot）→ 空响，且 actionSeq 已变 → 双重保险。
        RecordSink sink = new RecordSink(1001);
        RoomActor actor = buildActor(200, sink, 100);
        try {
            awaitNorthTurn(actor, sink);
            int handBefore = actor.room().playerAt(Seat.NORTH).hand().size();

            // 只检查"手动出牌之后"的 AUTO 事件：亮主窗口到点自动"过"也会广播 AUTO，
            // 扫全量 messages 会把它算进来 → 偶发假红（同一条路合法地广播了 AUTO）。
            int mark = sink.messages.size();

            // 真人手动出 BotBrain 给出的一手（必合法：领出/跟牌都能兜住）
            var p = actor.room().playerAt(Seat.NORTH);
            List<Card> legal = actor.room().currentTrick().isEmpty()
                    ? BotBrain.leadPlay(actor.room(), p) : BotBrain.followPlay(actor.room(), p);
            List<String> codes = CardCodec.encodeAll(legal);
            actor.submit(new RoomActor.CommandSpec("PLAY", 1001, codes, null, null, null, null));

            // 手牌数是快速变化的移动靶，用 < 单调断言「已减少」，避免 == 精确卡瞬间导致 flaky
            awaitTrue("手动出牌后手牌应减少", 3000,
                    () -> actor.room().playerAt(Seat.NORTH).hand().size() < handBefore);

            // 越过超时点（200ms），但仍在同一墩内（bot 每步 100ms，本墩至少还要 100~300ms）。
            // 此刻驱动在等 bot、压根没装弹，所以窗口内出现的 AUTO 只可能是"迟到的替打"。
            sleep(250);
            assertFalse(actor.isStuck(), "真人正常出牌后，迟到的超时任务必须空响，不得破坏状态");

            // 关键断言：真人在线且已手动出牌，系统不得替他再出一次。
            boolean autoSeen = sink.messages.subList(mark, sink.messages.size()).stream()
                    .anyMatch(m -> m.contains("\"op\":\"AUTO\""));
            assertFalse(autoSeen, "真人在线并已手动出牌，迟到的超时任务不得触发 AUTO 托管代打");
        } finally {
            actor.shutdown();
        }
    }

    /**
     * 真人超时被系统代打后，又连续点了很多次"出牌"（客户端界面未及时刷新时很容易发生）：
     * 这些命令会因为"不是你的回合/牌已不在手里"而失败，但**不得**把房间判成 stuck。
     * 否则一次超时 + 几次误点就会让整局卡死。
     */
    @Test
    void humanCommandsAfterTimeout_doNotStuckRoom() {
        RecordSink sink = new RecordSink(1001);
        RoomActor actor = buildActor(300, sink, 1);
        try {
            awaitNorthTurn(actor, sink);
            // 等超时托管完成代打（300ms 超时 + 余量）
            sleep(600);
            // 取一张一定不在 NORTH 手里的合法牌编码：轮到他也必然"牌不在手牌中"，不轮到则"不是你的回合"
            String absent = absentCardCode(actor.room().playerAt(Seat.NORTH).hand());
            for (int i = 0; i < 35; i++) {
                actor.submit(new RoomActor.CommandSpec("PLAY", 1001,
                        List.of(absent), null, null, null, null));
            }
            sleep(500);
            assertFalse(actor.isStuck(),
                    "真人超时后的连续失败命令不应把房间判为 stuck（否则一次误点就连锁卡死）");
        } finally {
            actor.shutdown();
        }
    }

    /** 真人（NORTH）当庄时替他扣 6 张底：等价于真人在界面上点"扣底"（用例不关心扣底策略） */
    private static void buryAsNorth(RoomActor actor) {
        List<Card> hand = actor.room().playerAt(Seat.NORTH).hand();
        if (hand.size() < 6) {
            return;
        }
        List<String> six = CardCodec.encodeAll(hand.subList(0, 6));
        actor.submit(new RoomActor.CommandSpec("BURY", 1001, six, null, null, null, null));
    }

    /** 扣王窗口轮到真人 NORTH 时替他"过"：等价于真人在界面上点"跳过"（用例不关心扣王策略） */
    private static void passPickAsNorth(RoomActor actor) {
        actor.submit(new RoomActor.CommandSpec("PICK_JOKER", 1001, List.of(),
                null, null, null, null));
    }

    /** 从 NORTH 手牌之外挑一个合法牌编码 */
    private static String absentCardCode(List<Card> hand) {
        java.util.Set<String> codes = new java.util.HashSet<>();
        for (Card c : hand) {
            codes.add(CardCodec.encode(c));
        }
        for (char suit : new char[]{'S', 'H', 'D', 'C'}) {
            for (int rank = 2; rank <= 14; rank++) {
                String code = "" + suit + rank;
                if (!codes.contains(code)) {
                    return code;
                }
            }
        }
        return "BJ";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
