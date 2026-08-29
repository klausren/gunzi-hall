package com.gunzihall.domain.play;

import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.card.Suit;
import com.gunzihall.domain.player.Seat;
import com.gunzihall.domain.trump.TrumpContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 一圈（Trick）赢家判定与分牌收集测试（手册 3.2）。
 * <p>固定上下文：级数 5，主花色 ♠。
 */
class TrickTest {

    private final TrumpContext ctx = new TrumpContext(5, Suit.SPADE);

    private Trick trick(Seat leader, List<Card> leadCards) {
        Combo lead = Combo.parse(leadCards, ctx).orElseThrow();
        return new Trick(leader, lead, ctx);
    }

    @Test
    void highestLeadSuitCardWins() {
        Trick t = trick(Seat.NORTH, List.of(Card.of(Suit.HEART, 7)));
        t.play(Seat.EAST, List.of(Card.of(Suit.HEART, 13)));
        t.play(Seat.SOUTH, List.of(Card.of(Suit.CLUB, 14))); // 垫牌
        t.play(Seat.WEST, List.of(Card.of(Suit.HEART, 3)));
        assertEquals(Seat.EAST, t.winnerSeat(), "跟花色最大的 ♥K 赢");
    }

    @Test
    void discardNeverWins() {
        Trick t = trick(Seat.NORTH, List.of(Card.of(Suit.HEART, 3)));
        t.play(Seat.EAST, List.of(Card.of(Suit.CLUB, 14)));
        t.play(Seat.SOUTH, List.of(Card.of(Suit.DIAMOND, 14)));
        t.play(Seat.WEST, List.of(Card.of(Suit.CLUB, 13)));
        assertEquals(Seat.NORTH, t.winnerSeat(), "全部垫牌时首出者赢");
    }

    @Test
    void trumpKillsOffSuit() {
        Trick t = trick(Seat.NORTH, List.of(Card.of(Suit.HEART, 14)));
        t.play(Seat.EAST, List.of(Card.of(Suit.CLUB, 3)));
        t.play(Seat.SOUTH, List.of(Card.smallJoker())); // 主牌杀
        t.play(Seat.WEST, List.of(Card.of(Suit.DIAMOND, 6)));
        assertEquals(Seat.SOUTH, t.winnerSeat());
    }

    @Test
    void biggerTrumpBeatsSmallerTrump() {
        Trick t = trick(Seat.NORTH, List.of(Card.of(Suit.HEART, 7)));
        t.play(Seat.EAST, List.of(Card.smallJoker()));
        t.play(Seat.SOUTH, List.of(Card.bigJoker()));
        t.play(Seat.WEST, List.of(Card.of(Suit.CLUB, 9)));
        assertEquals(Seat.SOUTH, t.winnerSeat());
    }

    @Test
    void trumpLeadFollowerMustPlayTrumpToWin() {
        // 首出主牌（小王）：副牌永远压不过
        Trick t = trick(Seat.NORTH, List.of(Card.smallJoker()));
        t.play(Seat.EAST, List.of(Card.of(Suit.SPADE, 14))); // 主花色 A 也压不过小王
        t.play(Seat.SOUTH, List.of(Card.of(Suit.HEART, 5)));  // 级牌也压不过
        t.play(Seat.WEST, List.of(Card.of(Suit.CLUB, 9)));
        assertEquals(Seat.NORTH, t.winnerSeat());
    }

    @Test
    void sameStrengthLeaderWins() {
        // 三副牌同身份可在同一圈出现：先出者赢
        Trick t = trick(Seat.NORTH, List.of(Card.of(Suit.HEART, 7)));
        t.play(Seat.EAST, List.of(Card.of(Suit.HEART, 7)));
        t.play(Seat.SOUTH, List.of(Card.of(Suit.CLUB, 9)));
        t.play(Seat.WEST, List.of(Card.of(Suit.DIAMOND, 9)));
        assertEquals(Seat.NORTH, t.winnerSeat());
    }

    @Test
    void pairBeatsPairSameSuit() {
        Trick t = trick(Seat.NORTH,
                List.of(Card.of(Suit.HEART, 8), Card.of(Suit.HEART, 8)));
        t.play(Seat.EAST,
                List.of(Card.of(Suit.HEART, 13), Card.of(Suit.HEART, 13)));
        t.play(Seat.SOUTH,
                List.of(Card.of(Suit.HEART, 14), Card.of(Suit.HEART, 3))); // 两张单张不构成牌型
        t.play(Seat.WEST,
                List.of(Card.of(Suit.HEART, 9), Card.of(Suit.HEART, 9)));
        assertEquals(Seat.EAST, t.winnerSeat(), "♥K 棒子最大");
    }

    @Test
    void trumpPairKillsOffSuitPair() {
        Trick t = trick(Seat.NORTH,
                List.of(Card.of(Suit.HEART, 14), Card.of(Suit.HEART, 14)));
        t.play(Seat.EAST,
                List.of(Card.of(Suit.SPADE, 3), Card.of(Suit.SPADE, 3))); // 主花色棒子杀
        t.play(Seat.SOUTH,
                List.of(Card.of(Suit.CLUB, 4), Card.of(Suit.DIAMOND, 6))); // 垫
        t.play(Seat.WEST,
                List.of(Card.smallJoker(), Card.smallJoker())); // 小王棒子更大
        assertEquals(Seat.WEST, t.winnerSeat());
    }

    @Test
    void jokerPairBeatsLevelCardPairKill() {
        // 首出 ♥A 棒子；东家用级牌棒子 ♥5♥5 杀；南家用小王棒子反杀
        Trick t = trick(Seat.NORTH,
                List.of(Card.of(Suit.HEART, 14), Card.of(Suit.HEART, 14)));
        t.play(Seat.EAST,
                List.of(Card.of(Suit.HEART, 5), Card.of(Suit.HEART, 5)));
        t.play(Seat.SOUTH,
                List.of(Card.smallJoker(), Card.smallJoker()));
        t.play(Seat.WEST,
                List.of(Card.of(Suit.CLUB, 9), Card.of(Suit.CLUB, 9)));
        assertEquals(Seat.SOUTH, t.winnerSeat());
    }

    @Test
    void mismatchedTypeNeverWins() {
        // 首出棒子：跟牌者用滚子拆出（三张）也不算赢（只能跟同牌型）
        Trick t = trick(Seat.NORTH,
                List.of(Card.of(Suit.HEART, 8), Card.of(Suit.HEART, 8)));
        t.play(Seat.EAST,
                List.of(Card.of(Suit.HEART, 13), Card.of(Suit.HEART, 13), Card.of(Suit.HEART, 13)));
        t.play(Seat.SOUTH,
                List.of(Card.of(Suit.CLUB, 4), Card.of(Suit.DIAMOND, 6)));
        t.play(Seat.WEST,
                List.of(Card.of(Suit.HEART, 9), Card.of(Suit.HEART, 9)));
        assertEquals(Seat.WEST, t.winnerSeat(), "滚子跟棒子不算竞逐");
    }

    @Test
    void pointsCollectedFromAllPlays() {
        Trick t = trick(Seat.NORTH, List.of(Card.of(Suit.HEART, 6)));  // 0 分
        t.play(Seat.EAST, List.of(Card.of(Suit.HEART, 10)));           // 10 分
        t.play(Seat.SOUTH, List.of(Card.of(Suit.CLUB, 13)));           // K 10 分
        t.play(Seat.WEST, List.of(Card.of(Suit.DIAMOND, 3)));          // 0
        assertEquals(20, t.points());
        assertEquals(4, t.allCards().size());
        assertEquals(Seat.EAST, t.winnerSeat(), "♥10 最大");
    }

    @Test
    void incompleteTrickCannotJudgeWinner() {
        Trick t = trick(Seat.NORTH, List.of(Card.of(Suit.HEART, 7)));
        t.play(Seat.EAST, List.of(Card.of(Suit.HEART, 9)));
        assertThrows(IllegalStateException.class, t::winnerSeat);
    }

    @Test
    void duplicateSeatPlayRejected() {
        Trick t = trick(Seat.NORTH, List.of(Card.of(Suit.HEART, 7)));
        assertThrows(IllegalStateException.class,
                () -> t.play(Seat.NORTH, List.of(Card.of(Suit.CLUB, 9))));
    }
}
