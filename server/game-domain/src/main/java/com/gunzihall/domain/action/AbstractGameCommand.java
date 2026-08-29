package com.gunzihall.domain.action;

import java.util.Objects;
import java.util.UUID;

/**
 * 命令基类：持有 commandId / roomId / playerId / tick。
 * tick 由 {@link CommandHistory} 分配后回填。
 */
public abstract class AbstractGameCommand implements GameCommand {

    private final String commandId;
    private final long roomId;
    private final long playerId;
    private long tick = -1;

    protected AbstractGameCommand(long roomId, long playerId) {
        this.commandId = UUID.randomUUID().toString();
        this.roomId = roomId;
        this.playerId = playerId;
    }

    protected AbstractGameCommand(String commandId, long roomId, long playerId) {
        this.commandId = Objects.requireNonNull(commandId);
        this.roomId = roomId;
        this.playerId = playerId;
    }

    @Override
    public String commandId() {
        return commandId;
    }

    @Override
    public long roomId() {
        return roomId;
    }

    @Override
    public long playerId() {
        return playerId;
    }

    @Override
    public long tick() {
        return tick;
    }

    /** 仅供 CommandHistory 内部回填 */
    void assignTick(long tick) {
        this.tick = tick;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{id=" + commandId.substring(0, 8)
                + ", room=" + roomId + ", player=" + playerId + ", tick=" + tick + "}";
    }
}
