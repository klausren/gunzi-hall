package com.gunzihall.domain.trump;

import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.card.Suit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 牌力链条测试：手册 2.1
 * 大王 > 小王 > 主花色级牌 > 其他花色级牌 > 主花色2 > 其他花色2 > 主花色牌(级牌除外) > 副牌
 */
class CardComparatorTest {

    // 场景：打 6，主花色 ♥
    private final TrumpContext ctx = new TrumpContext(6, Suit.HEART);
    private final CardComparator cmp = new CardComparator(ctx);

    @Test
    void fullChainOrdering() {
        Card big = Card.bigJoker();
        Card small = Card.smallJoker();
        Card trumpLevel = Card.of(Suit.HEART, 6);   // 主花色级牌 ♥6
        Card offLevel = Card.of(Suit.SPADE, 6);     // 副级牌 ♠6
        Card trumpTwo = Card.of(Suit.HEART, 2);     // 主2 ♥2
        Card offTwo = Card.of(Suit.CLUB, 2);        // 副2 ♣2
        Card trumpAce = Card.of(Suit.HEART, Card.ACE); // 主花色牌 ♥A
        Card offAce = Card.of(Suit.SPADE, Card.ACE);   // 副牌 ♠A

        Card[] chain = {offAce, trumpAce, offTwo, trumpTwo, offLevel, trumpLevel, small, big};
        for (int i = 1; i < chain.length; i++) {
            assertTrue(cmp.compare(chain[i], chain[i - 1]) > 0,
                    chain[i] + " 应大于 " + chain[i - 1]);
        }
    }

    @Test
    void offSuitLevelCardBeatsTrumpTwo() {
        // 其他花色级牌 > 主花色2（链条中容易写错的一环）
        assertTrue(cmp.compare(Card.of(Suit.DIAMOND, 6), Card.of(Suit.HEART, 2)) > 0);
    }

    @Test
    void trumpPlainCardsCompareByRank() {
        assertTrue(cmp.compare(Card.of(Suit.HEART, Card.ACE), Card.of(Suit.HEART, Card.KING)) > 0);
        assertTrue(cmp.compare(Card.of(Suit.HEART, 3), Card.of(Suit.HEART, 5)) < 0);
    }

    @Test
    void offSuitPlainCompareRankThenSuit() {
        // 同点异花：先点数（相同）后花色兜底序 —— 只保证全序稳定，不声明花色大小
        Card spade5 = Card.of(Suit.SPADE, 5);
        Card club5 = Card.of(Suit.CLUB, 5);
        assertNotEquals(0, cmp.compare(spade5, club5));
        assertEquals(cmp.compare(spade5, club5), -cmp.compare(club5, spade5));

        assertTrue(cmp.compare(Card.of(Suit.SPADE, Card.KING), Card.of(Suit.CLUB, Card.QUEEN)) > 0);
    }

    @Test
    void isTrumpClassification() {
        // 主牌 = 王、所有2、所有级牌、主花色牌
        assertTrue(cmp.isTrump(Card.bigJoker()));
        assertTrue(cmp.isTrump(Card.smallJoker()));
        assertTrue(cmp.isTrump(Card.of(Suit.HEART, 2)));
        assertTrue(cmp.isTrump(Card.of(Suit.CLUB, 2)), "所有 2 是常主");
        assertTrue(cmp.isTrump(Card.of(Suit.SPADE, 6)), "所有级牌算主牌（手册 2.1 链条）");
        assertTrue(cmp.isTrump(Card.of(Suit.HEART, 9)));
        // 副牌
        assertFalse(cmp.isTrump(Card.of(Suit.SPADE, 3)));
        assertFalse(cmp.isTrump(Card.of(Suit.CLUB, Card.ACE)));
        assertFalse(cmp.isTrump(Card.of(Suit.DIAMOND, Card.KING)));
    }

    @Test
    void levelChangesContext() {
        // 打 9 时 ♠9 变级牌（主牌），打 6 时是普通副牌
        Card spade9 = Card.of(Suit.SPADE, 9);
        assertFalse(new CardComparator(new TrumpContext(6, Suit.HEART)).isTrump(spade9));
        assertTrue(new CardComparator(new TrumpContext(9, Suit.HEART)).isTrump(spade9));
    }

    @Test
    void contextValidation() {
        assertThrows(IllegalArgumentException.class, () -> new TrumpContext(2, Suit.HEART));
        assertThrows(IllegalArgumentException.class, () -> new TrumpContext(11, Suit.HEART)); // 无 J
        assertThrows(IllegalArgumentException.class, () -> new TrumpContext(6, null));       // 不能无主
    }
}
