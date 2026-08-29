package com.gunzihall.domain.deck;

import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.card.Joker;
import com.gunzihall.domain.card.Suit;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DeckTest {

    @Test
    void freshDeckHas162Cards() {
        assertEquals(Deck.TOTAL, Deck.fresh().cards().size());
        assertEquals(162, Deck.fresh().cards().size());
    }

    @Test
    void everyIdentityAppearsExactly3Times() {
        var counts = new java.util.HashMap<Card, Integer>();
        for (Card c : Deck.fresh().cards()) {
            counts.merge(c, 1, Integer::sum);
        }
        // 54 种身份 × 3 副
        assertEquals(54, counts.size());
        assertTrue(counts.values().stream().allMatch(n -> n == 3));
    }

    @Test
    void dealGives39EachPlus6Bottom() {
        Deck deck = Deck.fresh();
        deck.shuffle(new Random(42));
        Deck.DealResult deal = deck.deal();
        assertEquals(4, deal.hands().size());
        for (var hand : deal.hands()) {
            assertEquals(Deck.HAND_SIZE, hand.size());
        }
        assertEquals(Deck.BOTTOM_SIZE, deal.bottom().size());
        assertTrue(deck.cards().isEmpty(), "发牌后牌组应清空");

        // 发出去的牌 + 底牌 = 全部 162 张且无重复副本超量
        var all = new java.util.ArrayList<Card>();
        deal.hands().forEach(all::addAll);
        all.addAll(deal.bottom());
        assertEquals(162, all.size());
    }

    @Test
    void sameSeedSameShuffle() {
        Deck a = Deck.fresh();
        Deck b = Deck.fresh();
        a.shuffle(new Random(2026));
        b.shuffle(new Random(2026));
        assertEquals(a.cards(), b.cards(), "同种子洗牌必须可复现（审计要求）");
    }

    @Test
    void dealRequiresFullDeck() {
        Deck deck = Deck.fresh();
        deck.shuffle(new Random(1));
        deck.deal(); // 第一次发牌后牌组清空
        assertThrows(IllegalStateException.class, deck::deal, "空牌组不能再次发牌");
    }

    @Test
    void jokersPresent() {
        Set<Card> jokers = new HashSet<>();
        for (Card c : Deck.fresh().cards()) {
            if (c.isJoker()) {
                jokers.add(c);
            }
        }
        assertTrue(jokers.contains(Card.bigJoker()));
        assertTrue(jokers.contains(Card.smallJoker()));
        assertEquals(Joker.BIG, Card.bigJoker().joker());
        assertEquals(Suit.SPADE, Card.of(Suit.SPADE, 3).suit());
    }
}
