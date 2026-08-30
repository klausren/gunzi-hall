package com.gunzihall.room;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话令牌注册表：token → (roomId, playerId) 绑定。
 * <p>解决 WS 无鉴权漏洞：join 成功后签发 token，后续 cmd/snapshot
 * 必须携带 token，服务端以 token 解析出的 playerId 为准（不信任客户端上报）。
 * <p>体验版阶段为进程内存储（重启失效，重连需重新 join）；接入用户体系后
 * 可替换为 Redis 共享存储。
 */
public final class SessionRegistry {

    public record Session(long roomId, long playerId, long issuedAt) {
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final long ttlMs;

    public SessionRegistry() {
        this(Duration.ofHours(12).toMillis());
    }

    public SessionRegistry(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    /** join 成功后签发令牌 */
    public String issue(long roomId, long playerId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        sessions.put(token, new Session(roomId, playerId, System.currentTimeMillis()));
        return token;
    }

    /**
     * 校验令牌：返回绑定的 playerId。
     *
     * @return 绑定的 playerId；令牌缺失/过期/房间不符返回 -1
     */
    public long verify(String token, long roomId) {
        if (token == null || token.isBlank()) {
            return -1;
        }
        Session s = sessions.get(token);
        if (s == null) {
            return -1;
        }
        if (ttlMs > 0 && System.currentTimeMillis() - s.issuedAt() > ttlMs) {
            sessions.remove(token);
            return -1;
        }
        return s.roomId() == roomId ? s.playerId() : -1;
    }

    /** 令牌绑定的房间（用于诊断） */
    public long roomIdOf(String token) {
        Session s = sessions.get(token);
        return s == null ? -1 : s.roomId();
    }
}
