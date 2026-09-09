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
        Instant deadline = Instant.now().plusSeconds(15);
        while (Instant.now().isBefore(deadline)) {
            if (actor.room().phase().name().equals("PLAYING")
                    && actor.room().turnSeat().map(s -> s == Seat.NORTH).orElse(false)) {
                return;
            }
            sleep(20);
        }
        throw new AssertionError("15s 内未等到 NORTH 出牌，卡在: " + actor.room().phase());
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
            awaitNorthTurn(actor);
            int handBefore = actor.room().playerAt(Seat.NORTH).hand().size();

            // 真人手动出 BotBrain 给出的一手（必合法：领出/跟牌都能兜住）
            var p = actor.room().playerAt(Seat.NORTH);
            List<Card> legal = actor.room().currentTrick().isEmpty()
                    ? BotBrain.leadPlay(actor.room(), p) : BotBrain.followPlay(actor.room(), p);
            List<String> codes = CardCodec.encodeAll(legal);
            actor.submit(new RoomActor.CommandSpec("PLAY", 1001, codes, null, null, null, null));

            // 手牌数是快速变化的移动靶，用 < 单调断言「已减少」，避免 == 精确卡瞬间导致 flaky
            awaitTrue("手动出牌后手牌应减少", 3000,
                    () -> actor.room().playerAt(Seat.NORTH).hand().size() < handBefore);

            // 越过超时点（200ms），覆盖本墩收尾，但不等到下一墩 NORTH 自然超时（~500ms）
            sleep(350);
            assertFalse(actor.isStuck(), "真人正常出牌后，迟到的超时任务必须空响，不得破坏状态");

            // 关键断言：真人在线且已手动出牌，系统不得广播 AUTO 托管事件。
            // 若迟到超时误触发代打，AUTO 事件必然出现在 messages 中（单调可检测，不依赖牌数瞬间）。
            boolean autoSeen = sink.messages.stream()
                    .anyMatch(m -> m.contains("\"op\":\"AUTO\""));
            assertFalse(autoSeen, "真人在线并已手动出牌，不得触发 AUTO 托管代打");
        } finally {
            actor.shutdown();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
