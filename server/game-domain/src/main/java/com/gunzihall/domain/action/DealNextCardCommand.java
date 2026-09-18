package com.gunzihall.domain.action;

import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.deck.Deck;
import com.gunzihall.domain.player.Seat;
import com.gunzihall.domain.room.GamePhase;
import com.gunzihall.domain.room.GameRoom;

/**
 * 逐张发牌：给当前轮到的座位发一张，发完轮转。
 *
 * <p>发满 156 张（4 家 × 39）后，牌堆里剩下的 6 张即底牌，房间转入 BIDDING
 * ——所以"发牌"和"亮主窗口"是同一段时间轴上的前后两段，玩家在发牌过程中
 * 摸到大王/级牌就能直接抢亮（手册 2.2）。
 *
 * <p>同时负责结算第一局的"待摸定主"：亮大王后摸到的第一张花色牌即主花色。
 */
public final class DealNextCardCommand extends AbstractGameCommand {

    public DealNextCardCommand(long roomId, long playerId) {
        super(roomId, playerId);
    }

    @Override
    public CommandResult execute(GameRoom room) {
        if (room.phase() != GamePhase.DEALING) {
            return CommandResult.fail("不在发牌阶段: " + room.phase());
        }
        if (room.dealRemaining() == 0) {
            return CommandResult.fail("牌堆为空（尚未洗牌或已发完）");
        }

        Seat seat = room.dealTurn();
        Card card = room.dealOneToNext();

        // 第一局：亮大王的人随后摸到的第一张花色牌 → 就是主花色（手册 2.2）
        room.settlePendingSuit(seat, card);

        // 【收口判定的正确口径】牌堆初始 162 张，发到只剩 BOTTOM_SIZE（6）张时
        // 这 6 张才是底牌。此处**必须**用 "剩余 <= 6" 而不是 "剩余 == 0"：
        // 162 - 156 = 6 永远不等于 0，用 ==0 判会把本该留底的 6 张也发出去
        // ——底牌变空 → 庄家收底 0 张、扣底 0 张 → 四家手牌 41/41/40/40 不均衡，
        // 出牌阶段先打空的两家会触发 BOT_STUCK（2026-09-15 定位到的真因）。
        if (room.dealRemaining() <= Deck.BOTTOM_SIZE) {
            room.setBottomCards(room.remainingPile());
            room.clearDealPile();
            room.transitionTo(GamePhase.BIDDING);
        }
        return CommandResult.ok();
    }
}
