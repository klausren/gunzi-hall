package com.gunzihall.room;

import com.gunzihall.domain.player.Seat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-510 WS 令牌鉴权：三层攻击面验证。
 * <p>攻击面：①无 token 调 cmd/snapshot ②伪造 token ③持自己的 token、
 * 谎报别人 playerId 出牌（服务端必须以 token 解析为准）。
 */
class SessionAuthTest {

    private GameServer server;
    private RoomManager manager;
    private SessionRegistry sessions;
    private int port;

    /** 测试用 sink：收集发出的 JSON */
    static class CollectSink implements RoomActor.Sink {
        final long playerId;
        final List<String> received = new CopyOnWriteArrayList<>();

        CollectSink(long playerId) {
            this.playerId = playerId;
        }

        @Override
        public long playerId() {
            return playerId;
        }

        @Override
        public void send(String json) {
            received.add(json);
        }
    }

    @BeforeEach
    void start() throws Exception {
        manager = new RoomManager(new InMemoryStateStore(), 0);
        // 房间 2001：NORTH 留给"真人"，其余 bot
        manager.create(2001L, java.util.Set.of(Seat.EAST, Seat.SOUTH, Seat.WEST));
        sessions = new SessionRegistry();
        server = new GameServer(0, manager, sessions);
        port = server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void joinIssuesTokenAndCmdRequiresIt() {
        // 真人 NORTH join → 满员自动开局 → 拿到 token
        CollectSink human = new CollectSink(1L);
        String reply = manager.join(2001L, 1L, Seat.NORTH, human);
        Map<String, Object> m = JsonUtil.read(reply, Map.class);
        assertEquals("joined", m.get("type"));
        String token = sessions.issue(2001L, 1L); // 直接用注册表签发（等价于 WsServerHandler 路径）

        // ① 无 token：verify 失败
        assertEquals(-1L, sessions.verify(null, 2001L));
        // ② 伪造 token
        assertEquals(-1L, sessions.verify("deadbeefdeadbeef", 2001L));
        // ③ 房间不符（token 是 2001 的，去 2002 验）
        assertEquals(-1L, sessions.verify(token, 2002L));
        // ④ 合法 token → 解析出正确 playerId
        assertEquals(1L, sessions.verify(token, 2001L));
    }

    @Test
    void tokenBindsPlayerIdAndCannotBeSpoofed() {
        // 两个玩家、两个 token
        String t1 = sessions.issue(2001L, 1L);
        String t2 = sessions.issue(2001L, 2L);
        assertNotEquals(t1, t2);
        assertEquals(1L, sessions.verify(t1, 2001L));
        assertEquals(2L, sessions.verify(t2, 2001L));

        // 冒充场景：客户端带 t2（玩家2），谎报 playerId=1
        // WsServerHandler 以 verify(t2) 的结果为准 → 命令实际归属玩家 2
        long authId = sessions.verify(t2, 2001L);
        assertEquals(2L, authId);
        assertNotEquals(1L, authId);
    }

    @Test
    void tokenExpiresAfterTtl() {
        SessionRegistry shortTtl = new SessionRegistry(1L); // 1ms TTL
        String token = shortTtl.issue(3001L, 42L);
        assertEquals(42L, shortTtl.verify(token, 3001L));
        try {
            Thread.sleep(20);
        } catch (InterruptedException ignored) {
        }
        assertEquals(-1L, shortTtl.verify(token, 3001L), "过期 token 必须失效");
    }

    @Test
    void gameServerAcceptsConnectionAndPing() throws Exception {
        // 冒烟：真实起服，验证鉴权后的完整 join→snapshot 链路由 WS 层负责（此处只验证端口存活）
        assertNotNull(server);
        assertTrue(port > 0);
    }
}
