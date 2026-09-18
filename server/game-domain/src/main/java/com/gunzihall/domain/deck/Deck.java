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

    /**
     * 逐张发牌：从牌堆末尾取一张（取牌方向与 {@link #deal()} 完全一致）。
     *
     * <p>存在意义：真实牌桌是一张一张轮流发的，玩家要能"一边摸牌一边亮主"
     * ——手册 2.2 第一局"亮大王后摸到的第一张花色牌定主"就依赖于此，
     * 一次性发完时牌堆立刻见底，这条规则无从实现。
     *
     * <p>{@link #deal()} 保留给"一次性发完"的旧路径与既有单元测试，两者并存不冲突。
     */
    public Card dealOne() {
        if (cards.isEmpty()) {
            throw new IllegalStateException("牌堆已空，不能再发牌");
        }
        return cards.remove(cards.size() - 1);
    }

    /** 剩余未发张数（发到 0 时余下的即底牌） */
    public int remaining() {
        return cards.size();
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
