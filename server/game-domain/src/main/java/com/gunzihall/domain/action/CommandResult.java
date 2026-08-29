package com.gunzihall.domain.action;

/**
 * 命令执行结果。
 * <p>失败必须携带原因码/原因文本，随命令一起写入 {@code t_game_action} 留痕（反作弊 + 回放）。
 */
public record CommandResult(boolean success, String reason) {

    public static CommandResult ok() {
        return new CommandResult(true, null);
    }

    public static CommandResult fail(String reason) {
        return new CommandResult(false, reason);
    }

    public boolean isFailure() {
        return !success;
    }
}
