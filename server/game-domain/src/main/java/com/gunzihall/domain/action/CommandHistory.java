package com.gunzihall.domain.action;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 命令历史：顺序号分配、防重放、回放支持。
 * <p>对应数据表 {@code t_game_action}（每个动作留痕）。
 */
public final class CommandHistory {

    private final List<GameCommand> executed = new ArrayList<>();
    private final Set<String> seenCommandIds = new HashSet<>();
    private long currentTick = 0;

    /** 是否已见过该命令 ID（防重放，架构 v0 六-5） */
    public boolean isReplay(String commandId) {
        return seenCommandIds.contains(commandId);
    }

    /** 记录一条已执行命令并分配 tick。 */
    public void record(GameCommand command) {
        if (isReplay(command.commandId())) {
            throw new IllegalStateException("命令重放被拒绝: " + command.commandId());
        }
        if (command instanceof AbstractGameCommand abstractCmd) {
            abstractCmd.assignTick(++currentTick);
        }
        executed.add(command);
        seenCommandIds.add(command.commandId());
    }

    /** 按执行顺序返回全部命令（回放用） */
    public List<GameCommand> all() {
        return List.copyOf(executed);
    }

    public int size() {
        return executed.size();
    }

    public long currentTick() {
        return currentTick;
    }
}
