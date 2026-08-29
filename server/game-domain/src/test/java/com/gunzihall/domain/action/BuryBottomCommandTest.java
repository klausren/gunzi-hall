package com.gunzihall.domain.action;

import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.card.Suit;
import com.gunzihall.domain.player.Player;
import com.gunzihall.domain.player.Seat;
import com.gunzihall.domain.room.GamePhase;
import com.gunzihall.domain.room.GameRoom;
import com.gunzihall.domain.trump.TrumpContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 扣底命令测试（手册 2.3，Q5/Q5b）：收底换扣、扣王先大王后小王、干锅不能替换。
 */
class BuryBottomCommandTest {

    private final GameRoom room = RevealTrumpCommandTest.fullRoom();

    /** 构造 BURYING 场景：主花色 ♥、级数 3、庄家北、指定底牌与庄家手牌 */
    private Player prepBurying(List<Card> bottom, List<Card> bankerHand) {
        room.apply(new ShuffleAndDealCommand(1001L, 1L, 42L));
        room.setTrump(new TrumpContext(3, Suit.HEART));
        room.setBankerSeat(Seat.NORTH);
        room.setBottomCards(bottom);
        Player banker = RevealTrumpCommandTest.playerOf(room, Seat.NORTH);
        banker.hand().clear();
        banker.hand().addAll(bankerHand);
        room.transitionTo(GamePhase.TRIBUTE);
        room.transitionTo(GamePhase.BURYING);
        assertEquals(GamePhase.BURYING, room.phase());
        return banker;
    }

    @Test
    void bankerTakesBottomThenBuriesSix() {
        // 底牌 6 张（无王、含主花色普通牌 → 非干锅）
        List<Card> bottom = List.of(
                Card.of(Suit.HEART, 7), Card.of(Suit.HEART, 9),
                Card.of(Suit.CLUB, 4), Card.of(Suit.CLUB, 8),
                Card.of(Suit.DIAMOND, 3), Card.of(Suit.SPADE, 11));
        Player banker = prepBurying(bottom, List.of(Card.of(Suit.SPADE, 13), Card.of(Suit.CLUB, 12)));

        int before = banker.hand().size();
        var bury = List.of(Card.of(Suit.SPADE, 13), Card.of(Suit.CLUB, 12),
                Card.of(Suit.HEART, 7), Card.of(Suit.HEART, 9),
                Card.of(Suit.CLUB, 4), Card.of(Suit.CLUB, 8));
        assertTrue(room.apply(new BuryBottomCommand(1001L, 1L, bury)).success());

        // 底牌已收又扣出：手牌 = 原 2 + 6 - 6 = 2
        assertEquals(before, banker.hand().size());
        assertEquals(bury, room.bottomCards());
        assertEquals(GamePhase.PLAYING, room.phase());
        assertEquals(Seat.NORTH, room.turnSeat().orElseThrow(), "庄家领出第一手");
    }

    @Test
    void onlyBankerCanBury() {
        List<Card> bottom = List.of(
                Card.of(Suit.HEART, 7), Card.of(Suit.HEART, 9),
                Card.of(Suit.CLUB, 4), Card.of(Suit.CLUB, 8),
                Card.of(Suit.DIAMOND, 3), Card.of(Suit.SPADE, 11));
        prepBurying(bottom, List.of(Card.of(Suit.SPADE, 13), Card.of(Suit.CLUB, 12)));

        var r = room.apply(new BuryBottomCommand(1001L, 2L, List.of()));
        assertTrue(r.isFailure());
        assertTrue(r.reason().contains("庄家"));
    }

    @Test
    void wrongCountRejected() {
        List<Card> bottom = List.of(
                Card.of(Suit.HEART, 7), Card.of(Suit.HEART, 9),
                Card.of(Suit.CLUB, 4), Card.of(Suit.CLUB, 8),
                Card.of(Suit.DIAMOND, 3), Card.of(Suit.SPADE, 11));
        prepBurying(bottom, List.of(Card.of(Suit.SPADE, 13), Card.of(Suit.CLUB, 12)));

        var r = room.apply(new BuryBottomCommand(1001L, 1L, List.of(Card.of(Suit.SPADE, 13))));
        assertTrue(r.isFailure());
        assertTrue(r.reason().contains("张数"));
    }

    // ---- Q5：扣王必须先扣完所有大王 ----

    @Test
    void burySmallJokerRequiresAllBigJokersBuriedFirst() {
        // 手牌：2 大王 + 1 小王 + 若干；底牌无王
        List<Card> bottom = List.of(
                Card.of(Suit.HEART, 7), Card.of(Suit.HEART, 9),
                Card.of(Suit.CLUB, 4), Card.of(Suit.CLUB, 8),
                Card.of(Suit.DIAMOND, 3), Card.of(Suit.SPADE, 11));
        Player banker = prepBurying(bottom, List.of(
                Card.bigJoker(), Card.bigJoker(), Card.smallJoker(),
                Card.of(Suit.SPADE, 13), Card.of(Suit.CLUB, 12), Card.of(Suit.DIAMOND, 5)));

        // 只扣 1 大王 + 1 小王（手里还有 1 大王）→ 拒绝（Q5）
        var bad = List.of(Card.bigJoker(), Card.smallJoker(),
                Card.of(Suit.HEART, 7), Card.of(Suit.HEART, 9),
                Card.of(Suit.CLUB, 4), Card.of(Suit.CLUB, 8));
        var r = room.apply(new BuryBottomCommand(1001L, 1L, bad));
        assertTrue(r.isFailure());
        assertTrue(r.reason().contains("大王"));

        // 扣 2 大王 + 1 小王 → 合法
        var good = List.of(Card.bigJoker(), Card.bigJoker(), Card.smallJoker(),
                Card.of(Suit.HEART, 7), Card.of(Suit.HEART, 9), Card.of(Suit.CLUB, 4));
        assertTrue(room.apply(new BuryBottomCommand(1001L, 1L, good)).success());
        assertFalse(room.isDryPot());
        assertEquals(GamePhase.PLAYING, room.phase());
    }

    @Test
    void burySmallJokerAllowedWhenNoBigJokerInHand() {
        List<Card> bottom = List.of(
                Card.of(Suit.HEART, 7), Card.of(Suit.HEART, 9),
                Card.of(Suit.CLUB, 4), Card.of(Suit.CLUB, 8),
                Card.of(Suit.DIAMOND, 3), Card.of(Suit.SPADE, 11));
        prepBurying(bottom, List.of(
                Card.smallJoker(), Card.of(Suit.SPADE, 13), Card.of(Suit.CLUB, 12)));

        var bury = List.of(Card.smallJoker(),
                Card.of(Suit.HEART, 7), Card.of(Suit.HEART, 9),
                Card.of(Suit.CLUB, 4), Card.of(Suit.CLUB, 8), Card.of(Suit.DIAMOND, 3));
        assertTrue(room.apply(new BuryBottomCommand(1001L, 1L, bury)).success());
    }

    // ---- 干锅（手册 2.3.7） ----

    @Test
    void dryPotCannotReplaceBottom() {
        // 底牌全是非主花色牌 → 无主花色普通牌 → 干锅
        List<Card> bottom = List.of(
                Card.of(Suit.CLUB, 4), Card.of(Suit.CLUB, 8),
                Card.of(Suit.DIAMOND, 3), Card.of(Suit.SPADE, 11),
                Card.of(Suit.SPADE, 13), Card.of(Suit.CLUB, 12));
        prepBurying(bottom, List.of(Card.of(Suit.SPADE, 14)));

        // 想换成别的 6 张 → 拒绝
        var r = room.apply(new BuryBottomCommand(1001L, 1L, List.of(
                Card.of(Suit.CLUB, 4), Card.of(Suit.CLUB, 8),
                Card.of(Suit.DIAMOND, 3), Card.of(Suit.SPADE, 11),
                Card.of(Suit.SPADE, 13), Card.of(Suit.SPADE, 14))));
        assertTrue(r.isFailure());
        assertTrue(r.reason().contains("干锅"));

        // 原样扣回 → 合法，且标记干锅
        assertTrue(room.apply(new BuryBottomCommand(1001L, 1L, bottom)).success());
        assertTrue(room.isDryPot());
        assertEquals(GamePhase.PLAYING, room.phase());
    }
}
