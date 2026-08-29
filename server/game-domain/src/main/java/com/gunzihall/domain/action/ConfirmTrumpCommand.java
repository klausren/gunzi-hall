package com.gunzihall.domain.action;

import com.gunzihall.domain.room.GamePhase;
import com.gunzihall.domain.room.GameRoom;

/**
 * 亮主窗口结束确认：任一玩家确认后锁定主花色，进入进贡（有义务）或扣底。
 *
 * <p>真实牌桌上"抢亮窗口"由发牌节奏决定（谁先亮、别人可否反），领域层用显式确认命令
 * 收口，避免引入定时器。进贡开始后不得再反主（{@link RevealTrumpCommand} 只认 BIDDING 阶段）。
 */
public final class ConfirmTrumpCommand extends AbstractGameCommand {

    public ConfirmTrumpCommand(long roomId, long playerId) {
        super(roomId, playerId);
    }

    @Override
    public CommandResult execute(GameRoom room) {
        if (room.phase() != GamePhase.BIDDING) {
            return CommandResult.fail("当前不是亮主窗口: " + room.phase());
        }
        if (room.revealState().isEmpty()) {
            return CommandResult.fail("尚无人亮主，无法确认（无人亮主请走底牌定主流程，手册 2.2）");
        }
        if (room.bankerSeat().isEmpty()) {
            return CommandResult.fail("庄家未定，无法确认");
        }
        if (room.pendingTributes().isEmpty()) {
            room.transitionTo(GamePhase.BURYING);
        } else {
            room.transitionTo(GamePhase.TRIBUTE);
        }
        return CommandResult.ok();
    }

    @Override
    public CommandResult rollback(GameRoom room) {
        return CommandResult.fail("确认命令不支持回滚");
    }
}
