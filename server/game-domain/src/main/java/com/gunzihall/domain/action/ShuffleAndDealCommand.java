package com.gunzihall.domain.action;

import com.gunzihall.domain.deck.Deck;
import com.gunzihall.domain.player.Seat;
import com.gunzihall.domain.room.GamePhase;
import com.gunzihall.domain.room.GameRoom;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

/**
 * 洗牌并发牌：162 张 → 四家各 39 张 + 底牌 6 张。
 * <p>执行后房间进入 BIDDING（亮王/抠庄）阶段。
 * <p>随机种子由服务端房间事件循环注入并写入命令留痕，保证牌局可复现审计。
 */
public final class ShuffleAndDealCommand extends AbstractGameCommand {

    private final long seed;

    public ShuffleAndDealCommand(long roomId, long playerId, long seed) {
        super(roomId, playerId);
        this.seed = seed;
    }

    public long seed() {
        return seed;
    }

    @Override
    public CommandResult execute(GameRoom room) {
        if (!room.isFull()) {
            return CommandResult.fail("房间未满 4 人，不能发牌");
        }
        if (room.phase() != GamePhase.WAITING && room.phase() != GamePhase.DEALING
                && room.phase() != GamePhase.BIDDING) {
            return CommandResult.fail("当前阶段不能发牌: " + room.phase());
        }
        // BIDDING 阶段重入仅用于"底牌全王→重新洗牌"场景（手册 2.2），Sprint 2 由亮王引擎触发
        if (room.phase() == GamePhase.WAITING) {
            room.transitionTo(GamePhase.DEALING);
        }

        Deck deck = Deck.fresh();
        deck.shuffle(new Random(seed));
        Deck.DealResult deal = deck.deal();

        Map<Seat, java.util.List<com.gunzihall.domain.card.Card>> hands = new EnumMap<>(Seat.class);
        for (Seat seat : Seat.values()) {
            hands.put(seat, deal.hands().get(seat.index()));
            room.playerAt(seat).hand().clear();
            room.playerAt(seat).hand().addAll(deal.hands().get(seat.index()));
        }
        room.setHands(hands);
        room.setBottomCards(deal.bottom());
        room.transitionTo(GamePhase.BIDDING);
        return CommandResult.ok();
    }
}
