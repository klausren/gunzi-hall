package com.gunzihall.domain.scoring;

import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.card.Suit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScoreCalculatorTest {

    @Test
    void fullDeckTotalsExactly300() {
        assertTrue(ScoreCalculator.fullDeckSanityCheck());
    }

    @Test
    void pointValues() {
        assertEquals(5, Card.of(Suit.HEART, 5).points());
        assertEquals(10, Card.of(Suit.SPADE, 10).points());
        assertEquals(10, Card.of(Suit.CLUB, Card.KING).points());
        assertEquals(0, Card.of(Suit.CLUB, Card.ACE).points());
        assertEquals(0, Card.bigJoker().points());
        assertEquals(0, Card.of(Suit.DIAMOND, 2).points());
    }

    @Test
    void pointsOfCollection() {
        assertEquals(25, ScoreCalculator.points(List.of(
                Card.of(Suit.HEART, 5),
                Card.of(Suit.SPADE, 10),
                Card.of(Suit.CLUB, Card.KING)
        )));
    }
}
