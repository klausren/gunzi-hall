package com.gunzihall.domain.play;

import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.card.Suit;
import com.gunzihall.domain.trump.TrumpContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 牌型判定测试（手册 3.1：只有单牌/棒子/滚子，无顺子/拖拉机/泰坦尼克/甩牌）。
 * <p>固定上下文：级数 5，主花色 ♠（黑桃）。
 */
class ComboTest {

    private final TrumpContext ctx = new TrumpContext(5, Suit.SPADE);

    @Test
    void parseSingle() {
        Optional<Combo> c = Combo.parse(List.of(Card.of(Suit.HEART, 7)), ctx);
        assertTrue(c.isPresent());
        assertEquals(ComboType.SINGLE, c.get().type());
        assertFalse(c.get().isTrumpCategory());
        assertEquals(Suit.HEART, c.get().suit());
    }

    @Test
    void parsePairSameIdentity() {
        Optional<Combo> c = Combo.parse(
                List.of(Card.of(Suit.HEART, 8), Card.of(Suit.HEART, 8)), ctx);
        assertTrue(c.isPresent());
        assertEquals(ComboType.PAIR, c.get().type());
    }

    @Test
    void parseTripleSameIdentity() {
        Optional<Combo> c = Combo.parse(
                List.of(Card.of(Suit.CLUB, 9), Card.of(Suit.CLUB, 9), Card.of(Suit.CLUB, 9)), ctx);
        assertTrue(c.isPresent());
        assertEquals(ComboType.TRIPLE, c.get().type());
    }

    @Test
    void parseJokerPairAndTriple() {
        assertTrue(Combo.parse(List.of(Card.bigJoker(), Card.bigJoker()), ctx).isPresent());
        assertTrue(Combo.parse(List.of(Card.smallJoker(), Card.smallJoker(), Card.smallJoker()), ctx).isPresent());
    }

    @Test
    void mixedSuitsNotAPair() {
        // 同点不同花不是棒子
        assertTrue(Combo.parse(List.of(Card.of(Suit.HEART, 8), Card.of(Suit.CLUB, 8)), ctx).isEmpty());
    }

    @Test
    void differentRanksNotACombo() {
        // 顺子/任意两张单牌都不是合法牌型（无甩牌）
        assertTrue(Combo.parse(List.of(Card.of(Suit.HEART, 3), Card.of(Suit.HEART, 4)), ctx).isEmpty());
        assertTrue(Combo.parse(
                List.of(Card.of(Suit.HEART, 5), Card.of(Suit.CLUB, 9), Card.bigJoker()), ctx).isEmpty());
    }

    @Test
    void bigAndSmallJokerNotAPair() {
        assertTrue(Combo.parse(List.of(Card.smallJoker(), Card.bigJoker()), ctx).isEmpty());
    }

    @Test
    void fourCardsInvalid() {
        assertTrue(Combo.parse(
                List.of(Card.of(Suit.HEART, 7), Card.of(Suit.HEART, 7),
                        Card.of(Suit.HEART, 7), Card.of(Suit.HEART, 7)), ctx).isEmpty());
    }

    @Test
    void levelCardsAreTrumpCategory() {
        // 级牌无论什么花色都是主牌类别（手册 2.1）
        Optional<Combo> levelPair = Combo.parse(
                List.of(Card.of(Suit.HEART, 5), Card.of(Suit.HEART, 5)), ctx);
        assertTrue(levelPair.isPresent());
        assertTrue(levelPair.get().isTrumpCategory());
        assertNull(levelPair.get().suit());

        // 2 也是主牌
        Optional<Combo> twoPair = Combo.parse(
                List.of(Card.of(Suit.DIAMOND, 2), Card.of(Suit.DIAMOND, 2)), ctx);
        assertTrue(twoPair.get().isTrumpCategory());
    }

    @Test
    void trumpSuitPairIsTrumpCategory() {
        Optional<Combo> c = Combo.parse(
                List.of(Card.of(Suit.SPADE, 7), Card.of(Suit.SPADE, 7)), ctx);
        assertTrue(c.get().isTrumpCategory());
    }

    @Test
    void trumpPairBeatsOffSuitPair() {
        Combo heartPair = Combo.parse(
                List.of(Card.of(Suit.HEART, 14), Card.of(Suit.HEART, 14)), ctx).orElseThrow();
        Combo trumpPlainPair = Combo.parse(
                List.of(Card.of(Suit.SPADE, 3), Card.of(Suit.SPADE, 3)), ctx).orElseThrow();
        assertTrue(trumpPlainPair.beats(heartPair));
        assertFalse(heartPair.beats(trumpPlainPair));
    }

    @Test
    void jokerPairBeatsLevelPair() {
        Combo smallJokerPair = Combo.parse(
                List.of(Card.smallJoker(), Card.smallJoker()), ctx).orElseThrow();
        Combo trumpLevelPair = Combo.parse(
                List.of(Card.of(Suit.SPADE, 5), Card.of(Suit.SPADE, 5)), ctx).orElseThrow();
        assertTrue(smallJokerPair.beats(trumpLevelPair));
    }

    @Test
    void offSuitPairsOnlyCompareWithinSameSuit() {
        Combo heartPair = Combo.parse(
                List.of(Card.of(Suit.HEART, 7), Card.of(Suit.HEART, 7)), ctx).orElseThrow();
        Combo clubPair = Combo.parse(
                List.of(Card.of(Suit.CLUB, 14), Card.of(Suit.CLUB, 14)), ctx).orElseThrow();
        assertFalse(clubPair.beats(heartPair), "不同副花色互不可比（垫牌不赢）");
        assertFalse(heartPair.beats(clubPair));
    }

    @Test
    void sameSuitHigherRankWins() {
        Combo seven = Combo.parse(List.of(Card.of(Suit.HEART, 7)), ctx).orElseThrow();
        Combo king = Combo.parse(List.of(Card.of(Suit.HEART, 13)), ctx).orElseThrow();
        assertTrue(king.beats(seven));
    }

    @Test
    void differentTypeCompareThrows() {
        Combo single = Combo.parse(List.of(Card.of(Suit.HEART, 7)), ctx).orElseThrow();
        Combo pair = Combo.parse(
                List.of(Card.of(Suit.HEART, 7), Card.of(Suit.HEART, 7)), ctx).orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> single.beats(pair));
    }
}
