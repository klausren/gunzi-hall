package com.gunzihall.domain.play;

import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.card.Suit;
import com.gunzihall.domain.player.Team;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 一局结算测试（手册 3.4、4.1–4.3；Q8 抠底固定 ×2）。
 * <p>固定设定：A 队为庄家方，B 队为抓分方。
 */
class RoundSettlementTest {

    private final Team banker = Team.A;
    private final Team attacker = Team.B;

    @Test
    void dugBottomDoublesBottomPoints() {
        // 抓分方出牌阶段 100 分，底牌 30 分（5+10+10+5），抠底 → 100 + 60 = 160
        List<Card> bottom = List.of(
                Card.of(Suit.HEART, 5), Card.of(Suit.HEART, 10),
                Card.of(Suit.CLUB, 13), Card.of(Suit.SPADE, 5),
                Card.of(Suit.DIAMOND, 3), Card.of(Suit.DIAMOND, 7));
        var r = RoundSettlement.settle(banker,
                Map.of(attacker, 100, banker, 170), bottom, attacker);
        assertEquals(160, r.attackerScore());
        assertEquals(170, r.bankerScore());
        assertTrue(r.dugBottom());
        assertTrue(r.attackerTakesBank());
        assertTrue(r.attackerPromoted(), "≥120 且抠底 → 抓分方升级（4.1）");
        assertFalse(r.bankerPromoted());
        assertFalse(r.roundOver());
    }

    @Test
    void guardedBottomGivesBankerDoubledBottom() {
        // 庄家方保底：底牌分 ×2 归庄家方，抓分方不加
        List<Card> bottom = List.of(
                Card.of(Suit.HEART, 10), Card.of(Suit.HEART, 10),
                Card.of(Suit.CLUB, 13), Card.of(Suit.CLUB, 13),
                Card.of(Suit.SPADE, 5), Card.of(Suit.SPADE, 5));
        var r = RoundSettlement.settle(banker,
                Map.of(attacker, 130, banker, 120), bottom, banker);
        assertEquals(130, r.attackerScore());
        assertEquals(220, r.bankerScore());
        assertFalse(r.dugBottom());
        assertTrue(r.attackerTakesBank(), "≥120 上台线，即便保底也上台（4.3 第 3 行）");
        assertFalse(r.attackerPromoted(), "过线但未抠底 → 上台不升级");
        assertFalse(r.bankerPromoted());
    }

    @Test
    void bankerPromotesWhenGuardAndBelowLine() {
        List<Card> bottom = List.of(Card.of(Suit.HEART, 3), Card.of(Suit.HEART, 4),
                Card.of(Suit.CLUB, 6), Card.of(Suit.CLUB, 7),
                Card.of(Suit.SPADE, 8), Card.of(Suit.SPADE, 9));
        var r = RoundSettlement.settle(banker,
                Map.of(attacker, 60, banker, 240), bottom, banker);
        assertEquals(60, r.attackerScore());
        assertFalse(r.attackerTakesBank());
        assertFalse(r.attackerPromoted());
        assertTrue(r.bankerPromoted(), "<120 且保底 → 庄家方升级（4.2）");
        assertFalse(r.roundOver());
    }

    @Test
    void zeroScoreEndsRound() {
        List<Card> bottom = List.of(Card.of(Suit.HEART, 3), Card.of(Suit.HEART, 4),
                Card.of(Suit.CLUB, 6), Card.of(Suit.CLUB, 7),
                Card.of(Suit.SPADE, 8), Card.of(Suit.SPADE, 9));
        var r = RoundSettlement.settle(banker,
                Map.of(attacker, 0, banker, 300), bottom, banker);
        assertTrue(r.roundOver());
        assertNotNull(r.roundOverReason());
        assertTrue(r.roundOverReason().contains("一分不得"));
        assertTrue(r.bankerPromoted());
    }

    @Test
    void fullScoreEndsRound() {
        // 抓分方拿满 300 分（含抠底翻倍溢出）
        List<Card> bottom = List.of(
                Card.of(Suit.HEART, 5), Card.of(Suit.HEART, 5),
                Card.of(Suit.CLUB, 10), Card.of(Suit.CLUB, 10),
                Card.of(Suit.SPADE, 13), Card.of(Suit.SPADE, 13)); // 底牌 50 分
        var r = RoundSettlement.settle(banker,
                Map.of(attacker, 250, banker, 0), bottom, attacker);
        assertEquals(250 + 100, r.attackerScore());
        assertTrue(r.roundOver());
        assertTrue(r.roundOverReason().contains("300"));
    }

    @Test
    void dugButBelowLineNoPromotion() {
        // 抠底但 <120：庄家方留庄不升级，抓分方不升级（4.3 第 2 行语义）
        List<Card> bottom = List.of(Card.of(Suit.HEART, 3), Card.of(Suit.HEART, 4),
                Card.of(Suit.CLUB, 6), Card.of(Suit.CLUB, 7),
                Card.of(Suit.SPADE, 8), Card.of(Suit.SPADE, 9));
        var r = RoundSettlement.settle(banker,
                Map.of(attacker, 80, banker, 220), bottom, attacker);
        assertTrue(r.dugBottom());
        assertFalse(r.attackerTakesBank());
        assertFalse(r.attackerPromoted());
        assertFalse(r.bankerPromoted(), "被抠底 → 庄家方不升级");
    }
}
