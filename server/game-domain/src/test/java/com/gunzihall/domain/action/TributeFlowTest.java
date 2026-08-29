package com.gunzihall.domain.action;

import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.card.Suit;
import com.gunzihall.domain.player.Player;
import com.gunzihall.domain.player.Seat;
import com.gunzihall.domain.room.GamePhase;
import com.gunzihall.domain.room.GameRoom;
import com.gunzihall.domain.tribute.TributeObligation;
import com.gunzihall.domain.trump.TrumpContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进贡 / 还贡流程测试（手册 5 节，Q4/Q4b）：
 * 只有庄家上家执行进贡、必须交最大的非分牌、打 5 时 5 要贡、还贡后进扣底。
 */
class TributeFlowTest {

    private final GameRoom room = RevealTrumpCommandTest.fullRoom();

    /** 构造 TRIBUTE 场景：主花色 ♥、级数 3、庄家北 → 进贡人 = 北上家（西） */
    private Player prepTribute(int blood, List<Card> payerHand) {
        room.apply(new ShuffleAndDealCommand(1001L, 1L, 42L));
        room.setTrump(new TrumpContext(3, Suit.HEART));
        room.setBankerSeat(Seat.NORTH);
        room.putTributeObligation(Seat.WEST, new TributeObligation(blood, Seat.NORTH));
        Player west = RevealTrumpCommandTest.playerOf(room, Seat.WEST);
        west.hand().clear();
        west.hand().addAll(payerHand);
        // 北家（收贡人）手牌同样受控，保证还贡牌可用
        Player north = RevealTrumpCommandTest.playerOf(room, Seat.NORTH);
        north.hand().clear();
        north.hand().addAll(List.of(Card.of(Suit.HEART, 13), Card.of(Suit.CLUB, 7)));
        room.transitionTo(GamePhase.TRIBUTE);
        assertEquals(GamePhase.TRIBUTE, room.phase());
        return west;
    }

    @Test
    void payerMustTributeBiggestNonScoreCards() {
        // 西家手牌：大王 > 小王 > ♥A > ♠A ... K 是分牌不能贡
        Player west = prepTribute(2, List.of(
                Card.bigJoker(), Card.smallJoker(),
                Card.of(Suit.SPADE, 14), Card.of(Suit.HEART, 13), Card.of(Suit.CLUB, 7)));

        // 交 ♠A + ♥K → K 是分牌且非级牌，拒绝
        var r = room.apply(new TributeCommand(1001L, 4L,
                List.of(Card.of(Suit.SPADE, 14), Card.of(Suit.HEART, 13))));
        assertTrue(r.isFailure());
        assertTrue(r.reason().contains("最大"));

        // 交大王+小王 → 合法
        assertTrue(room.apply(new TributeCommand(1001L, 4L,
                List.of(Card.bigJoker(), Card.smallJoker()))).success());
        assertFalse(west.hand().contains(Card.bigJoker()));

        Player north = RevealTrumpCommandTest.playerOf(room, Seat.NORTH);
        assertTrue(north.hand().contains(Card.bigJoker()));
        assertTrue(north.hand().contains(Card.smallJoker()));

        // 还贡：北还西 2 张 → 全部还清 → BURYING
        assertTrue(room.apply(new ReturnTributeCommand(1001L, 1L, Seat.WEST,
                List.of(Card.of(Suit.HEART, 13), Card.of(Suit.CLUB, 7)))).success());
        assertEquals(GamePhase.BURYING, room.phase());
        assertTrue(west.hand().contains(Card.of(Suit.HEART, 13)));
    }

    @Test
    void tributeCountMustMatchBlood() {
        prepTribute(2, List.of(
                Card.bigJoker(), Card.smallJoker(), Card.of(Suit.SPADE, 14)));

        var r = room.apply(new TributeCommand(1001L, 4L, List.of(Card.bigJoker())));
        assertTrue(r.isFailure());
        assertTrue(r.reason().contains("张数"));
    }

    @Test
    void wrongPayerRejected() {
        prepTribute(2, List.of(Card.bigJoker(), Card.smallJoker(), Card.of(Suit.SPADE, 14)));

        // 东家想进贡（无义务）→ 拒绝
        var r = room.apply(new TributeCommand(1001L, 2L,
                List.of(Card.bigJoker(), Card.smallJoker())));
        assertTrue(r.isFailure());
        assertTrue(r.reason().contains("义务"));
    }

    @Test
    void returnTributeCountMustMatch() {
        prepTribute(2, List.of(Card.bigJoker(), Card.smallJoker(), Card.of(Suit.SPADE, 14)));
        assertTrue(room.apply(new TributeCommand(1001L, 4L,
                List.of(Card.bigJoker(), Card.smallJoker()))).success());

        Player north = RevealTrumpCommandTest.playerOf(room, Seat.NORTH);
        var r = room.apply(new ReturnTributeCommand(1001L, 1L, Seat.WEST,
                List.of(north.hand().get(0))));
        assertTrue(r.isFailure());
        assertTrue(r.reason().contains("张数"));
    }

    @Test
    void levelFiveMustBeTributedWhenPlayingFive() {
        // 打 5：5 是级牌，虽然也是分牌，但必须进贡（手册 5 基础规则 2）
        room.apply(new ShuffleAndDealCommand(1001L, 1L, 42L));
        room.setTrump(new TrumpContext(5, Suit.HEART));
        room.setBankerSeat(Seat.NORTH);
        room.putTributeObligation(Seat.WEST, new TributeObligation(1, Seat.NORTH));
        Player west = RevealTrumpCommandTest.playerOf(room, Seat.WEST);
        west.hand().clear();
        west.hand().addAll(List.of(Card.bigJoker(), Card.of(Suit.SPADE, 5)));
        room.transitionTo(GamePhase.TRIBUTE);

        // 5（级牌）牌力低于大王，交 5 → 拒绝（要交大王）
        var r = room.apply(new TributeCommand(1001L, 4L, List.of(Card.of(Suit.SPADE, 5))));
        assertTrue(r.isFailure());

        assertTrue(room.apply(new TributeCommand(1001L, 4L, List.of(Card.bigJoker()))).success());

        // 西家只剩 5（级牌是此刻最大非分牌候选），若还有 1 血要再交 5
        room.setTrump(new TrumpContext(5, Suit.HEART));
        room.putTributeObligation(Seat.WEST, new TributeObligation(1, Seat.NORTH));
        var r2 = room.apply(new TributeCommand(1001L, 4L, List.of(Card.of(Suit.SPADE, 5))));
        assertTrue(r2.success(), "打 5 时级牌 5 必须可进贡: " + r2.reason());
    }
}
