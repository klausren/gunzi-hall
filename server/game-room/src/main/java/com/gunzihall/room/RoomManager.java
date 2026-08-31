package com.gunzihall.room;

import com.gunzihall.domain.player.HumanPlayer;
import com.gunzihall.domain.player.Player;
import com.gunzihall.domain.player.Seat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 房间管理器：roomId → {@link RoomActor} 路由（架构 v0：战斗服按 RoomId 路由）。
 * <p>职责：建房（可选 bot 占位）、真人加入/断线重连、命令分发、
 * 服务重启后从 {@link RoomStateStore} 命令日志重放恢复牌局。
 */
public final class RoomManager {

    /** bot 玩家 ID 段（真人不会撞上：t_user 自增从 1 开始，此处 9 亿段） */
    public static final long BOT_ID_BASE = 900_000_000L;

    private final RoomStateStore store;
    private final long botDelayMs;
    private final Map<Long, RoomActor> rooms = new ConcurrentHashMap<>();
    // 房间级调参（建房与重启恢复共用，避免恢复出的房间丢配置）
    private volatile long thinkMinMs = 0;
    private volatile long thinkMaxMs = 0;
    private volatile long turnTimeoutMs = 0;

    public RoomManager(RoomStateStore store) {
        this(store, 0);
    }

    public RoomManager(RoomStateStore store, long botDelayMs) {
        this.store = store;
        this.botDelayMs = botDelayMs;
    }

    /** T-701：bot 思考时长区间（对所有房间生效，含重启恢复） */
    public void setThinkTime(long minMs, long maxMs) {
        this.thinkMinMs = minMs;
        this.thinkMaxMs = maxMs;
        rooms.values().forEach(a -> a.setThinkTime(minMs, maxMs));
    }

    /** T-704：真人超时托管时长（对所有房间生效，含重启恢复） */
    public void setTurnTimeout(long ms) {
        this.turnTimeoutMs = ms;
        rooms.values().forEach(a -> a.setTurnTimeout(ms));
    }

    /** 新建房间：应用房间级调参 */
    private void configure(RoomActor actor) {
        if (thinkMaxMs > 0) {
            actor.setThinkTime(thinkMinMs, thinkMaxMs);
        }
        if (turnTimeoutMs > 0) {
            actor.setTurnTimeout(turnTimeoutMs);
        }
    }

    /**
     * 建房：botSeats 指定的座位由 bot 占据，其余留给真人。
     * 房满 4 人自动开局。
     */
    public RoomActor create(long roomId, Set<Seat> botSeats) {
        RoomActor actor = new RoomActor(roomId, store, botDelayMs);
        configure(actor);
        for (Seat seat : Seat.values()) {
            if (botSeats.contains(seat)) {
                actor.join(new BotPlayer(botId(roomId, seat), seat), true);
            }
        }
        rooms.put(roomId, actor);
        return actor;
    }

    public RoomActor get(long roomId) {
        return rooms.get(roomId);
    }

    /** 真人加入/重连：已在房 → 绑定通道发快照；WAITING 且座位空 → 入座，满员自动开局 */
    public String join(long roomId, long playerId, Seat seat, RoomActor.Sink sink) {
        RoomActor actor = rooms.get(roomId);
        if (actor == null) {
            actor = restore(roomId);
            if (actor != null) {
                rooms.put(roomId, actor);
            }
        }
        if (actor == null) {
            return JsonUtil.write(Map.of("type", "error", "reason", "房间不存在: " + roomId));
        }
        synchronized (actor) {
            if (actor.hasPlayer(playerId)) {
                // 断线重连：重新绑定通道
                actor.addSink(sink);
                sink.send(snapshotMessage(actor, playerId));
                return JsonUtil.write(Map.of("type", "joined", "reconnect", true,
                        "roomId", roomId, "playerId", playerId));
            }
            if (actor.room().phase() != com.gunzihall.domain.room.GamePhase.WAITING) {
                return JsonUtil.write(Map.of("type", "error",
                        "reason", "牌局已开始，无法入座"));
            }
            if (actor.room().players().containsKey(seat)) {
                return JsonUtil.write(Map.of("type", "error",
                        "reason", "座位已被占用: " + seat));
            }
            actor.join(new HumanPlayer(playerId, seat), false);
            actor.addSink(sink);
        }
        String joined = JsonUtil.write(Map.of("type", "joined", "reconnect", false,
                "roomId", roomId, "playerId", playerId, "seat", seat.name()));
        if (actor.room().isFull()) {
            actor.start();
        } else {
            sink.send(snapshotMessage(actor, playerId));
        }
        return joined;
    }

    /** 客户端命令分发 */
    public void route(long roomId, RoomActor.CommandSpec spec, RoomActor.Sink sink) {
        RoomActor actor = rooms.get(roomId);
        if (actor == null) {
            sink.send(JsonUtil.write(Map.of("type", "error",
                    "reason", "房间不存在: " + roomId)));
            return;
        }
        actor.submit(spec);
    }

    /** 主动请求快照 */
    public void snapshot(long roomId, long playerId, RoomActor.Sink sink) {
        RoomActor actor = rooms.get(roomId);
        if (actor == null) {
            sink.send(JsonUtil.write(Map.of("type", "error", "reason", "房间不存在: " + roomId)));
            return;
        }
        sink.send(snapshotMessage(actor, playerId));
    }

    public void detach(RoomActor.Sink sink) {
        for (RoomActor actor : rooms.values()) {
            actor.removeSink(sink);
            actor.onSinkRemoved(); // T-704：断开可能正轮到该真人 → 唤醒驱动接管
        }
    }

    private String snapshotMessage(RoomActor actor, long playerId) {
        return actor.snapshotFor(playerId);
    }

    /** 服务重启恢复：按命令日志重放重建 RoomActor */
    private RoomActor restore(long roomId) {
        List<String> log = store.commandLog(roomId);
        if (log.isEmpty()) {
            return null;
        }
        RoomActor actor = new RoomActor(roomId, store, botDelayMs);
        configure(actor); // 恢复出的房间同样应用调参（T-701/T-704）
        for (String entry : log) {
            Map<String, Object> m = JsonUtil.read(entry, Map.class);
            String op = String.valueOf(m.get("op"));
            long playerId = ((Number) m.get("playerId")).longValue();
            if ("JOIN".equals(op)) {
                Seat seat = Seat.valueOf(String.valueOf(m.get("seat")));
                boolean bot = Boolean.parseBoolean(String.valueOf(m.get("bot")));
                actor.replayJoin(playerId, seat, bot);
            } else {
                actor.replaySpec(toSpec(op, playerId, m));
            }
        }
        // 日志中已有发牌 → 牌局进行过，恢复 bot 驱动（不重新发牌）
        boolean dealt = log.stream().anyMatch(e -> e.contains("\"op\":\"DEAL\""));
        if (dealt) {
            actor.resumeAfterRestore();
        }
        return actor;
    }

    @SuppressWarnings("unchecked")
    private RoomActor.CommandSpec toSpec(String op, long playerId, Map<String, Object> m) {
        List<String> cards = (List<String>) m.get("cards");
        String suit = (String) m.get("suit");
        String payee = (String) m.get("payee");
        List<Integer> indexes = null;
        Object idxObj = m.get("indexes");
        if (idxObj instanceof List<?> l) {
            indexes = l.stream().map(n -> ((Number) n).intValue()).toList();
        }
        Long seed = m.get("seed") != null ? ((Number) m.get("seed")).longValue() : null;
        return new RoomActor.CommandSpec(op, playerId, cards, suit, payee, indexes, seed);
    }

    private static long botId(long roomId, Seat seat) {
        return BOT_ID_BASE + roomId * 10 + seat.index();
    }
}
