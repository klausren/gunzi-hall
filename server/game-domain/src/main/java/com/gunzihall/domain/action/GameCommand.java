package com.gunzihall.domain.action;

import com.gunzihall.domain.room.GameRoom;

import java.io.Serializable;

/**
 * 命令模式：所有玩家动作都是一个命令（架构 v0 3.2）。
 * <p>每个命令：
 * <ul>
 *   <li>携带全局唯一 {@code commandId}（UUID），局内重复提交直接拒绝（防重放，架构 v0 六-5）</li>
 *   <li>由服务端权威校验后才落地，客户端只是 view</li>
 *   <li>执行结果写入 {@code t_game_action}，支持回放与审计</li>
 * </ul>
 */
public interface GameCommand extends Serializable {

    /** 命令唯一 ID（UUID），防重放 */
    String commandId();

    /** 所属房间 */
    long roomId();

    /** 发起玩家 */
    long playerId();

    /** 顺序号（由 {@link CommandHistory} 在执行时分配，用于回放） */
    long tick();

    /** 执行（含校验）。返回结果决定命令是否被记录为成功。 */
    CommandResult execute(GameRoom room);

    /** 回滚（仅本地调试/本地对战用，线上房间不做回滚） */
    default CommandResult rollback(GameRoom room) {
        return CommandResult.fail("当前命令不支持回滚");
    }
}
