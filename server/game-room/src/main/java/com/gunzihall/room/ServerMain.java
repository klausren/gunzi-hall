package com.gunzihall.room;

import com.gunzihall.domain.player.Seat;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 战斗服启动入口：Netty WebSocket + 房间管理 + 命令日志存储。
 *
 * <p>默认布局：房间 1001 = 3 个 bot（NORTH/EAST/WEST）+ SOUTH 留给真人客户端，
 * 真人连上 ws://localhost:8080/ws 发 join 即自动开局，bot 全自动陪打。
 *
 * <p><b>座位约定（前后端必须一致，改动前先看这段）</b>：真人在 <b>南家 SOUTH</b>。
 * 客户端把"我"永远画在屏幕最下方，而座位名是绝对方位（上北·右东·下南·左西），
 * 所以真人只能是 SOUTH —— 否则界面上会出现"上方写着 SOUTH、自己却坐在下方"的错位。
 * 客户端对应字段：{@code client/assets/scripts/ui/TableUI.ts} 的 {@code mySeat}（默认 'SOUTH'）。
 * 一旦两端不一致，真人会撞上"座位已被占用"（本服自己给 bot 留的座位把真人挡住了）。
 *
 * <p>存储策略：Redis 可用则用 Redis（重启可恢复），否则降级内存。
 *
 * <p>参数（全部可缺省，位置无关）：
 * <pre>
 *   [端口] [房间号] [真人超时托管毫秒] [真人座位名]
 *   例：8080 1001 32000 SOUTH ／ 8080 1001 SOUTH（不写毫秒时用默认 32s）
 * </pre>
 * 纯数字按 端口 → 房间号 → 托管时长 顺序认领；座位名单独识别，
 * 这样"只想换个座位"时不必把托管时长一起写死。超时托管传小值（如 1500）
 * 能让人不动也把一局快速推完（默认 32s 下一次约 40 轮，光等托管要 20 多分钟）。
 *
 * <p>用法：{@code mvn -pl game-room exec:java -Dexec.mainClass=com.gunzihall.room.ServerMain}
 */
public final class ServerMain {

    private static final String REDIS_URI = "redis://localhost:6379";

    /**
     * 真人座位（与客户端 {@code TableUI.mySeat} 必须一致）。改动时两处一起改。
     */
    public static final Seat DEFAULT_HUMAN_SEAT = Seat.SOUTH;

    public static void main(String[] args) throws Exception {
        int port = 8080;
        long roomId = 1001L;
        long turnTimeoutMs = 32_000;
        Seat humanSeat = DEFAULT_HUMAN_SEAT;
        int numeric = 0;
        for (String raw : args) {
            String a = raw == null ? "" : raw.trim();
            if (a.isEmpty()) {
                continue;
            }
            if (a.chars().allMatch(Character::isDigit)) {
                switch (numeric++) {
                    case 0 -> port = Integer.parseInt(a);
                    case 1 -> roomId = Long.parseLong(a);
                    case 2 -> turnTimeoutMs = Long.parseLong(a);
                    default -> throw new IllegalArgumentException("多余的数值参数: " + a
                            + "（用法：端口 房间号 托管毫秒 真人座位名）");
                }
            } else {
                humanSeat = parseSeat(a);
            }
        }
        if (turnTimeoutMs < 0) {
            throw new IllegalArgumentException("非法超时托管时长: " + turnTimeoutMs);
        }
        // 真人不坐的座位全给 bot —— 座位表由 humanSeat 推导，不再手写常量，
        // 避免"改了默认座位却漏改 bot 列表"这种两端不一致的老问题。
        Set<Seat> botSeats = botSeatsFor(humanSeat);

        RoomStateStore store = RedisStateStore.available(REDIS_URI)
                ? new RedisStateStore(REDIS_URI)
                : new InMemoryStateStore();
        System.out.println("命令日志存储: " + store.getClass().getSimpleName());

        RoomManager manager = new RoomManager(store, 150);
        manager.setThinkTime(800, 2500);   // T-701 拟人化：bot 每步随机思考 0.8~2.5s
        manager.setTurnTimeout(turnTimeoutMs); // T-704 超时托管：真人未行动由系统代打
        RoomActor room = manager.create(roomId, botSeats);

        GameServer server = new GameServer(port, manager);
        int bound = server.start();
        System.out.println("=== 打滚子战斗服已启动 ===");
        System.out.println("WebSocket: ws://localhost:" + bound + "/ws");
        System.out.println("房间 " + roomId + ": " + names(botSeats) + " 为 bot，" + humanSeat + " 留给真人");
        System.out.println("超时托管: " + (turnTimeoutMs <= 0 ? "已关闭" : turnTimeoutMs + "ms"));
        System.out.println("客户端协议：{\"op\":\"join\",\"roomId\":" + roomId
                + ",\"playerId\":1,\"seat\":\"" + humanSeat + "\"}");
        System.out.println("[座位约定] 真人在 " + humanSeat + "，客户端 TableUI.mySeat 必须同为 " + humanSeat
                + "（不一致会报\"座位已被占用\"）");
        System.out.println("Ctrl+C 退出");

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        Thread.currentThread().join(); // 常驻
    }

    /**
     * 由真人座位推导 bot 座位表（真人座位之外全部给 bot）。
     *
     * <p>公开出来是为了让测试能直接验证"默认布局下真人座位一定是空的"——
     * 这条约定曾经只在两端各写一遍常量，改了一边就出现"座位已被占用"。
     */
    public static Set<Seat> botSeatsFor(Seat humanSeat) {
        return EnumSet.complementOf(EnumSet.of(humanSeat));
    }

    private static Seat parseSeat(String raw) {
        try {
            return Seat.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("无效座位名: " + raw
                    + "（可选 " + names(EnumSet.allOf(Seat.class)) + "）");
        }
    }

    private static String names(Set<Seat> seats) {
        return seats.stream().map(Enum::name).collect(Collectors.joining("/"));
    }

    private ServerMain() {
    }
}
