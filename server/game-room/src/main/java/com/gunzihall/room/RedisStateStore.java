package com.gunzihall.room;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Redis 命令日志存储（架构 v0 4 节：牌局实时状态仅缓存 Redis，不进 MySQL）。
 * <p>KEY: game:cmdlog:{roomId}（List），TTL 24h。服务重启后按序重放恢复牌局。
 */
public final class RedisStateStore implements RoomStateStore, AutoCloseable {

    private static final String KEY_PREFIX = "game:cmdlog:";

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> sync;

    public RedisStateStore(String uri) {
        this.client = RedisClient.create(uri);
        this.connection = client.connect();
        this.sync = connection.sync();
    }

    /** Redis 是否可用（不可用时上层应降级为内存存储） */
    public static boolean available(String uri) {
        try (RedisClient probe = RedisClient.create(uri);
             StatefulRedisConnection<String, String> c = probe.connect()) {
            return "PONG".equals(c.sync().ping());
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void append(long roomId, String entryJson) {
        String key = KEY_PREFIX + roomId;
        sync.rpush(key, entryJson);
        sync.expire(key, TimeUnit.HOURS.toSeconds(24));
    }

    @Override
    public List<String> commandLog(long roomId) {
        return sync.lrange(KEY_PREFIX + roomId, 0, -1);
    }

    @Override
    public void clear(long roomId) {
        sync.del(KEY_PREFIX + roomId);
    }

    @Override
    public void close() {
        connection.close();
        client.shutdown();
    }
}
