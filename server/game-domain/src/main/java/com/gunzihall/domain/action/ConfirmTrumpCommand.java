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
        // 第一局亮大王后若始终没摸到花色牌（例如牌发完才点大王）→ 兜底定主。
        // 少了这一步，主花色会一直悬空，扣底/出牌阶段拿不到主牌上下文。
        if (room.pendingFirstRoundSuit() && !room.forceResolvePendingSuit()) {
            return CommandResult.fail("主花色未定，无法确认（手册 2.2）");
        }
        if (room.bankerSeat().isEmpty()) {
            return CommandResult.fail("庄家未定，无法确认");
        }
        if (room.pendingTributes().isEmpty()) {
            // 干锅局由 enterBuryingPhase() 统一拦下（原样扣回 → 直接 PLAYING），
            // 这里只需要把"干锅"这个原因透给客户端，让玩家知道为什么没进扣底。
            if (room.enterBuryingPhase()) {
                return new CommandResult(true, "干锅，底牌原样扣回");
            }
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
