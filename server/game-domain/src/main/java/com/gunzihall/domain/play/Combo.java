package com.gunzihall.domain.play;

import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.card.Suit;
import com.gunzihall.domain.trump.CardComparator;
import com.gunzihall.domain.trump.TrumpContext;

import java.util.List;
import java.util.Optional;

/**
 * 合法牌型（值对象，不可变）：单牌 / 棒子 / 滚子。
 *
 * <p><strong>类别（category）</strong>是牌型比较的关键：一张牌的"有效花色"要么是主牌，
 * 要么是其本身的副花色。级牌（无论什么花色）、2、王、主花色牌的类别都是"主牌"——
 * 例如主花色为 ♠、级数为 5 时，♥5 的类别是主牌而非 ♥。
 *
 * <p>比较语义（手册 3.2）：
 * <ul>
 *   <li>同类别同点数比大小（主牌类别内部按 2.1 大小链条）</li>
 *   <li>首出为副牌时，主牌类别可"杀"</li>
 *   <li>不同副花色之间互不可比（垫牌永远不赢）</li>
 * </ul>
 *
 * <p>规则依据：手册 3.1（无顺子/拖拉机/泰坦尼克/甩牌）。
 */
public final class Combo {

    private final ComboType type;
    private final List<Card> cards;   // 全部同身份（同花色同点数，或同王）
    private final boolean trump;      // 类别是否为主牌
    private final CardComparator comparator;

    private Combo(ComboType type, List<Card> cards, boolean trump, CardComparator comparator) {
        this.type = type;
        this.cards = List.copyOf(cards);
        this.trump = trump;
        this.comparator = comparator;
    }

    /**
     * 解析一组牌是否构成合法牌型。
     *
     * @return 非法（张数超限、非同身份即"甩牌"）时返回 empty
     */
    public static Optional<Combo> parse(List<Card> cards, TrumpContext ctx) {
        if (cards == null || cards.isEmpty() || cards.size() > 3) {
            return Optional.empty();
        }
        Card first = cards.get(0);
        // 全部同身份才是棒子/滚子；混合牌（甩牌、垫牌）不是合法牌型
        for (Card c : cards) {
            if (!c.equals(first)) {
                return Optional.empty();
            }
        }
        CardComparator cmp = new CardComparator(ctx);
        boolean trump = cmp.isTrump(first);
        return Optional.of(new Combo(ComboType.ofSize(cards.size()), cards, trump, cmp));
    }

    public ComboType type() {
        return type;
    }

    /** 张数 */
    public int size() {
        return cards.size();
    }

    /** 组成牌（不可变副本） */
    public List<Card> cards() {
        return cards;
    }

    /** 代表牌（同身份任取其一） */
    public Card rep() {
        return cards.get(0);
    }

    /** 类别是否为主牌（王、2、级牌、主花色） */
    public boolean isTrumpCategory() {
        return trump;
    }

    /** 副花色；主牌类别或王返回 null */
    public Suit suit() {
        return trump || rep().isJoker() ? null : rep().suit();
    }

    /**
     * 本牌型能否压过另一牌型（同为可竞逐的候选赢家时）。
     * <p>调用方须先保证两者 type 相同（与首出牌型一致）。
     *
     * <p>注意：本方法只做"类别 + 牌力"比较，<strong>不</strong>判断 type 是否匹配，
     * 由 {@link Trick} 统一控制（垫牌/异型牌永远不参与竞逐）。
     */
    public boolean beats(Combo other) {
        if (type != other.type) {
            throw new IllegalArgumentException("只有同牌型才可比较: " + type + " vs " + other.type);
        }
        if (trump) {
            if (!other.trump) {
                return true; // 主牌杀副牌
            }
            return comparator.compare(rep(), other.rep()) > 0;
        }
        // 本组合为副牌：压不过任何主牌，也压不过不同副花色
        if (other.trump || suit() != other.suit()) {
            return false;
        }
        return comparator.compare(rep(), other.rep()) > 0;
    }

    /** 牌力是否与另一牌型相当（同类别同 type 且代表牌相等） */
    public boolean ties(Combo other) {
        return type == other.type && trump == other.trump
                && (trump || suit() == other.suit())
                && comparator.compare(rep(), other.rep()) == 0;
    }

    @Override
    public String toString() {
        return type + cards.toString();
    }
}
