package com.gunzihall.room;

import com.gunzihall.domain.player.Seat;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 4 T-105：服务重启后从命令日志重放恢复牌局（断线重连）。
 *
 * <p>场景：4 bot 打满 2 局 → 进程"重启"（新 RoomManager，内存无房间）→
 * 玩家 join 触发 restore 重放 → 断线重连拿到快照，与重启前状态逐项一致。
 * <p>种子随发牌命令留痕，重放后手牌完全可复现。
 */
class RestoreTest {

    private static final String REDIS_URI = "redis://localhost:6379";

    @Test
    void restoreFromInMemoryLog() {
        verifyRestore(new InMemoryStateStore(), 3001);
    }

    @Test
    void restoreFromRedisLog() {
        org.junit.jupiter.api.Assumptions.assumeTrue(RedisStateStore.available(REDIS_URI),
                "本机 Redis 未运行，跳过");
        try (RedisStateStore store = new RedisStateStore(REDIS_URI)) {
            store.clear(3002);
            verifyRestore(store, 3002);
            store.clear(3002);
        }
    }

    private void verifyRestore(RoomStateStore store, long roomId) {
        // ---- 阶段 1：原进程打满 2 局 ----
        RoomActor actor = new RoomActor(roomId, store); // 同步模式
        Map<Seat, Long> ids = new java.util.EnumMap<>(Seat.class);
        for (Seat seat : Seat.values()) {
            long id = RoomManager.BOT_ID_BASE + roomId * 10 + seat.index();
            ids.put(seat, id);
            actor.join(new BotPlayer(id, seat), true);
        }
        actor.setStopAfterGames(2);
        actor.start();
        actor.driveSync(5000);
        assertFalse(actor.isStuck(), "bot 不应卡死");

        long northId = ids.get(Seat.NORTH);
        String before = actor.snapshotFor(northId);
        Map<String, Object> beforeSnap = JsonUtil.read(before, Map.class);

        // ---- 阶段 2：模拟服务重启（新 RoomManager，房间不在内存）----
        RoomManager restarted = new RoomManager(store);
        List<String> received = new CopyOnWriteArrayList<>();
        RoomActor.Sink sink = new RoomActor.Sink() {
            @Override
            public long playerId() {
                return northId;
            }

            @Override
            public void send(String json) {
                received.add(json);
            }
        };
        String reply = restarted.join(roomId, northId, Seat.NORTH, sink);
        assertTrue(reply.contains("\"reconnect\":true"), "应识别为断线重连: " + reply);

        // ---- 断言：重连快照与重启前一致 ----
        String snapshotJson = received.stream()
                .filter(s -> s.contains("\"type\":\"snapshot\""))
                .reduce((a, b) -> b).orElseThrow(() -> new AssertionError("未收到快照"));
        Map<String, Object> after = JsonUtil.read(snapshotJson, Map.class);
        assertEquals(beforeSnap.get("phase"), after.get("phase"), "阶段应一致");
        assertEquals(beforeSnap.get("gameNumber"), after.get("gameNumber"), "局数应一致");
        assertEquals(beforeSnap.get("level"), after.get("level"), "级数应一致");
        assertEquals(beforeSnap.get("yourHand"), after.get("yourHand"), "手牌应逐张一致（种子重放）");
        assertEquals(beforeSnap.get("hands"), after.get("hands"), "四家手牌张数应一致");

        RoomActor restored = restarted.get(roomId);
        assertTrue(restored.hasPlayer(northId), "恢复后玩家应在座");
        assertEquals(actor.room().trickPoints(), restored.room().trickPoints(), "收分状态应一致");
        actor.shutdown();
        restored.shutdown();
    }

    @Test
    void redisStoreRoundtrip() {
        org.junit.jupiter.api.Assumptions.assumeTrue(RedisStateStore.available(REDIS_URI),
                "本机 Redis 未运行，跳过");
        try (RedisStateStore store = new RedisStateStore(REDIS_URI)) {
            store.clear(9999);
            store.append(9999, "{\"op\":\"JOIN\",\"playerId\":1}");
            store.append(9999, "{\"op\":\"DEAL\",\"playerId\":1,\"seed\":42}");
            List<String> log = store.commandLog(9999);
            assertEquals(2, log.size(), "命令日志应按序完整往返");
            assertTrue(log.get(1).contains("\"seed\":42"));
            store.clear(9999);
        }
    }

    private static final Set<Seat> ALL = Set.of(Seat.values()); // 语义提示用
}
