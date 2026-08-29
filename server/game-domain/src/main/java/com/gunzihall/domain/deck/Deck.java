package com.gunzihall.domain.deck;

import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.card.Joker;
import com.gunzihall.domain.card.Suit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 牌组：三副牌共 162 张。洗牌、发牌（每人 39 张 + 底牌 6 张）。
 * <p>规则依据：手册第 1 节。
 */
public final class Deck {

    /** 三副牌 */
    public static final int DECK_COPIES = 3;
    /** 每副 54 张 */
    public static final int CARDS_PER_DECK = 54;
    /** 总牌数 162 */
    public static final int TOTAL = DECK_COPIES * CARDS_PER_DECK;
    /** 每人手牌数 */
    public static final int HAND_SIZE = 39;
    /** 底牌张数 */
    public static final int BOTTOM_SIZE = 6;

    private final List<Card> cards;

    private Deck(List<Card> cards) {
        this.cards = cards;
    }

    /** 生成一套完整的三副牌（未洗，按花色点数序）。 */
    public static Deck fresh() {
        List<Card> all = new ArrayList<>(TOTAL);
        for (int copy = 0; copy < DECK_COPIES; copy++) {
            for (Suit suit : Suit.values()) {
                for (int rank = Card.TWO; rank <= Card.ACE; rank++) {
                    all.add(Card.of(suit, rank));
                }
            }
            all.add(Card.bigJoker());
            all.add(Card.smallJoker());
        }
        return new Deck(all);
    }

    /** 洗牌（Fisher-Yates）。传入 Random 便于测试和回放复现。 */
    public void shuffle(Random random) {
        Collections.shuffle(cards, random);
    }

    /**
     * 发牌：切出 4 家各 39 张 + 底牌 6 张。
     * <p>调用前必须已洗牌；发牌后牌组清空。
     */
    public DealResult deal() {
        if (cards.size() != TOTAL) {
            throw new IllegalStateException("发牌前牌组必须完整（162 张），当前 " + cards.size() + " 张");
        }
        List<List<Card>> hands = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            List<Card> hand = new ArrayList<>(HAND_SIZE);
            for (int j = 0; j < HAND_SIZE; j++) {
                hand.add(cards.remove(cards.size() - 1));
            }
            hands.add(hand);
        }
        List<Card> bottom = new ArrayList<>(cards);
        cards.clear();
        return new DealResult(hands, bottom);
    }

    public List<Card> cards() {
        return Collections.unmodifiableList(cards);
    }

    /** 发牌结果：四家手牌 + 底牌。索引 0..3 对应座位序（由房间层映射到 {@code Seat}）。 */
    public record DealResult(List<List<Card>> hands, List<Card> bottom) {
        public DealResult {
            hands = List.copyOf(hands);
            bottom = List.copyOf(bottom);
        }
    }
}
