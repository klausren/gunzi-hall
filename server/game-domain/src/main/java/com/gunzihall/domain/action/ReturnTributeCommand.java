package com.gunzihall.domain.action;

import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.player.Player;
import com.gunzihall.domain.player.Seat;
import com.gunzihall.domain.room.GamePhase;
import com.gunzihall.domain.room.GameRoom;

import java.util.List;

/**
 * 还贡命令：收贡人（庄家）向进贡者还同等数量的牌。
 *
 * <p>规则手册未细化还贡牌的限制（未规定必须还小牌/非王/非级牌），领域层第一版
 * 允许还任意手牌，仅校验数量与归属；若甲方后续要求收紧，在此处加过滤即可。
 *
 * <p>全部进贡还清后 TRIBUTE → BURYING。
 */
public final class ReturnTributeCommand extends AbstractGameCommand {

    private final Seat payeeSeat;
    private final List<Card> cards;

    public ReturnTributeCommand(long roomId, long playerId, Seat payeeSeat, List<Card> cards) {
        super(roomId, playerId);
        this.payeeSeat = payeeSeat;
        this.cards = List.copyOf(cards);
    }

    @Override
    public CommandResult execute(GameRoom room) {
        if (room.phase() != GamePhase.TRIBUTE) {
            return CommandResult.fail("当前阶段不能还贡: " + room.phase());
        }
        Player receiver = Players.find(room, playerId());
        if (receiver == null) {
            return CommandResult.fail("玩家不在本房间: " + playerId());
        }
        List<Card> received = room.tributeReceived().get(payeeSeat);
        if (received == null) {
            return CommandResult.fail(payeeSeat + " 没有进贡记录");
        }
        if (room.isTributeReturned(payeeSeat)) {
            return CommandResult.fail(payeeSeat + " 的进贡已还过");
        }
        if (cards.size() != received.size()) {
            return CommandResult.fail("还贡张数必须等于进贡张数 " + received.size());
        }
        if (!com.gunzihall.domain.card.Cards.containsCopies(receiver.hand(), cards)) {
            return CommandResult.fail("所还之牌不在收贡人手牌中（或副本数不足）");
        }

        com.gunzihall.domain.card.Cards.removeCopies(receiver.hand(), cards);
        room.playerAt(payeeSeat).hand().addAll(cards);
        room.markTributeReturned(payeeSeat);

        if (room.allTributesReturned()) {
            room.transitionTo(GamePhase.BURYING);
        }
        return CommandResult.ok();
    }

    @Override
    public CommandResult rollback(GameRoom room) {
        return CommandResult.fail("还贡命令不支持回滚");
    }
}
