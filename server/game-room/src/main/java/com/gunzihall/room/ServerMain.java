package com.gunzihall.room;

import com.gunzihall.domain.player.Seat;

import java.util.Set;

/**
 * 战斗服启动入口：Netty WebSocket + 房间管理 + 命令日志存储。
 *
 * <p>默认布局：房间 1001 = 3 个 bot（EAST/SOUTH/WEST）+ NORTH 留给真人客户端，
 * 真人连上 ws://localhost:8080/ws 发 join 即自动开局，bot 全自动陪打。
 * <p>存储策略：Redis 可用则用 Redis（重启可恢复），否则降级内存。
 * <p>用法：{@code mvn -pl game-room exec:java -Dexec.mainClass=com.gunzihall.room.ServerMain}
 */
public final class ServerMain {

    private static final String REDIS_URI = "redis://localhost:6379";

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        long roomId = args.length > 1 ? Long.parseLong(args[1]) : 1001;

        RoomStateStore store = RedisStateStore.available(REDIS_URI)
                ? new RedisStateStore(REDIS_URI)
                : new InMemoryStateStore();
        System.out.println("命令日志存储: " + store.getClass().getSimpleName());

        RoomManager manager = new RoomManager(store, 150);
        RoomActor room = manager.create(roomId, Set.of(Seat.EAST, Seat.SOUTH, Seat.WEST));
        room.setThinkTime(800, 2500); // T-701 拟人化：bot 每步随机思考 0.8~2.5s

        GameServer server = new GameServer(port, manager);
        int bound = server.start();
        System.out.println("=== 打滚子战斗服已启动 ===");
        System.out.println("WebSocket: ws://localhost:" + bound + "/ws");
        System.out.println("房间 " + roomId + ": EAST/SOUTH/WEST 为 bot，NORTH 留给真人");
        System.out.println("客户端协议：{\"op\":\"join\",\"roomId\":" + roomId
                + ",\"playerId\":1,\"seat\":\"NORTH\"}");
        System.out.println("Ctrl+C 退出");

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        Thread.currentThread().join(); // 常驻
    }

    private ServerMain() {
    }
}
