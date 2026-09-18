package com.gunzihall.room;

import com.gunzihall.domain.player.Seat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真人座位约定回归测试（"座位已被占用: SOUTH" 那次的护栏）。
 *
 * <p>事故经过：客户端的默认座位从 NORTH 改成 SOUTH（把罗盘摆正：我在下 = 我在南），
 * 而服务端当时仍把 SOUTH 划给 bot。结果真人 join 时撞上"自己服的 bot 占着该座位"，
 * 界面永远停在"等待服务器数据…"——两端各写一遍座位常量，改了一边就出事。
 *
 * <p>这里锁住服务端这一半的三条：
 * <ol>
 *   <li>{@link ServerMain#DEFAULT_HUMAN_SEAT} 在默认布局（其余座位交给 bot）下必须可入座，
 *       且 3 bot + 真人 = 满员应自动开局；</li>
 *   <li>bot 座位表必须由真人座位推导，永不包含真人座位（4 个座位逐一验证）；</li>
 *   <li>座位被占时的报错必须带上"空座"提示，下次出问题不用翻源码定位。</li>
 * </ol>
 *
 * <p><b>客户端那一半（{@code TableUI.mySeat}）无法从 JVM 断言</b>：改动客户端默认座位时，
 * 请同步改 {@link ServerMain#DEFAULT_HUMAN_SEAT}（或反方向），并让两边注释保持一致。
 */
class HumanSeatConventionTest {

    private static final long ROOM_DEFAULT = 4001L;
    private static final long ROOM_PARTIAL = 4002L;

    private RoomManager manager;
    private final List<Long> created = new ArrayList<>();

    /** 测试用 sink：收集发出的 JSON */
    private static final class CollectSink implements RoomActor.Sink {
        private final long playerId;
        final List<String> messages = new CopyOnWriteArrayList<>();

        CollectSink(long playerId) {
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

    private RoomManager newManager(long roomId, Set<Seat> botSeats) {
        manager = new RoomManager(new InMemoryStateStore(), 0);
        manager.create(roomId, botSeats);
        created.add(roomId);
        return manager;
    }

    private static Map<String, Object> joinAs(RoomManager m, long roomId, long pid, Seat seat) {
        return JsonUtil.read(m.join(roomId, pid, seat, new CollectSink(pid)), Map.class);
    }

    @AfterEach
    void tearDown() {
        // 房间会自动开局并起 bot 驱动线程：不收尾会留一堆线程在后台推牌局
        for (long roomId : created) {
            RoomActor actor = manager.get(roomId);
            if (actor != null) {
                actor.shutdown();
            }
        }
        created.clear();
    }

    @Test
    void defaultLayout_humanSeatIsFreeAndRoomStartsWhenHumanSits() {
        Seat human = ServerMain.DEFAULT_HUMAN_SEAT;
        RoomManager m = newManager(ROOM_DEFAULT, ServerMain.botSeatsFor(human));

        Map<String, Object> reply = joinAs(m, ROOM_DEFAULT, 1L, human);

        assertEquals("joined", reply.get("type"),
                "默认布局下真人应能入座 " + human + "，实际回执: " + reply);
        assertEquals(human.name(), reply.get("seat"));
        assertTrue(m.get(ROOM_DEFAULT).room().isFull(),
                "3 个 bot + 1 个真人 = 满员，应自动开局");
    }

    @Test
    void botSeats_neverContainHumanSeat() {
        for (Seat human : Seat.values()) {
            Set<Seat> bots = ServerMain.botSeatsFor(human);
            assertFalse(bots.contains(human),
                    "bot 座位表不能包含真人座位 " + human + "，否则真人必然撞\"座位已被占用\"");
            assertEquals(3, bots.size(), "除真人座位外应恰好 3 个 bot 座位");
        }
    }

    @Test
    void occupiedSeatError_namesFreeSeats() {
        // 只放 2 个 bot：NORTH/EAST 被占，空座为 SOUTH/WEST
        RoomManager m = newManager(ROOM_PARTIAL, Set.of(Seat.NORTH, Seat.EAST));

        Map<String, Object> reply = joinAs(m, ROOM_PARTIAL, 1L, Seat.NORTH);

        assertEquals("error", reply.get("type"));
        String reason = String.valueOf(reply.get("reason"));
        assertTrue(reason.contains("座位已被占用: NORTH"), reason);
        assertTrue(reason.contains("空座"), "报错必须给出空座，便于直接换座位: " + reason);
        assertTrue(reason.contains("SOUTH") && reason.contains("WEST"), reason);
    }
}
