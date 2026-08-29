package com.gunzihall.domain.trump;

import com.gunzihall.domain.card.Suit;
import com.gunzihall.domain.player.Seat;

/**
 * 亮主声明（手册 2.2 抢亮/反主）。
 *
 * <p>不可变值对象，{@code GameRoom} 只保留当前最高声明，后来的声明必须通过
 * {@link #canBeOverriddenBy(TrumpReveal)} 校验才能覆盖。
 *
 * <p>规则依据（已拍板决议）：
 * <ul>
 *   <li>第一局：抢亮 1 张大王即定，无人能反（Q2）；</li>
 *   <li>第二局起亮级牌（打几亮几），反主规则：2 个相同的反 1 个、3 个相同的反 2 个或 1 个（Q2b）；</li>
 *   <li>3 个小王或 3 个大王可以叫任意花色（不能叫无主）；3 个大王可以推翻 3 个小王已定的花色，
 *       反之不行（Q2c）。</li>
 * </ul>
 */
public final class TrumpReveal {

    /** 声明类型 */
    public enum Kind {
        /** 第一局：抢亮 1 张大王（不可反） */
        FIRST_ROUND_JOKER,
        /** 无人亮主时由底牌翻/抽确定（不可反，手册 2.2） */
        FIXED_BY_BOTTOM,
        /** 第二局起：亮/反级牌（1..3 张同花色） */
        LEVEL_CARDS,
        /** 3 张小王（可叫任意花色） */
        TRIPLE_SMALL_JOKER,
        /** 3 张大王（可叫任意花色，压 3 小王） */
        TRIPLE_BIG_JOKER
    }

    private final Kind kind;
    /** 主花色（级牌声明 = 级牌花色；王声明 = 所叫花色） */
    private final Suit suit;
    /** 级牌张数（仅 LEVEL_CARDS 有效，1..3） */
    private final int levelCardCount;
    /** 亮牌人 */
    private final Seat seat;

    private TrumpReveal(Kind kind, Suit suit, int levelCardCount, Seat seat) {
        this.kind = kind;
        this.suit = suit;
        this.levelCardCount = levelCardCount;
        this.seat = seat;
    }

    /** 第一局抢亮大王（主花色 = 亮牌人随后摸到的第一张花色牌，由调用方传入） */
    public static TrumpReveal firstRoundJoker(Seat seat, Suit suit) {
        return new TrumpReveal(Kind.FIRST_ROUND_JOKER, suit, 0, seat);
    }

    /** 无人亮主时底牌定主（不可反） */
    public static TrumpReveal fixedByBottom(Seat seat, Suit suit) {
        return new TrumpReveal(Kind.FIXED_BY_BOTTOM, suit, 0, seat);
    }

    /** 第二局起亮/反级牌（count 张同花色级牌） */
    public static TrumpReveal levelCards(Seat seat, Suit suit, int count) {
        if (count < 1 || count > 3) {
            throw new IllegalArgumentException("级牌声明张数必须 1..3: " + count);
        }
        return new TrumpReveal(Kind.LEVEL_CARDS, suit, count, seat);
    }

    /** 3 张小王叫任意花色 */
    public static TrumpReveal tripleSmallJoker(Seat seat, Suit suit) {
        return new TrumpReveal(Kind.TRIPLE_SMALL_JOKER, suit, 0, seat);
    }

    /** 3 张大王叫任意花色 */
    public static TrumpReveal tripleBigJoker(Seat seat, Suit suit) {
        return new TrumpReveal(Kind.TRIPLE_BIG_JOKER, suit, 0, seat);
    }

    /**
     * 新声明能否推翻本声明。
     *
     * <ul>
     *   <li>第一局大王声明 / 3 大王声明：不可反；</li>
     *   <li>3 大王：可反一切（级牌声明与 3 小王声明）；</li>
     *   <li>3 小王：只可反级牌声明（不能反 3 大王，Q2c）；</li>
     *   <li>级牌：2 张反 1 张、3 张反 2 张或 1 张（Q2b）；同花色加固同样按张数规则判定。</li>
     * </ul>
     */
    public boolean canBeOverriddenBy(TrumpReveal candidate) {
        if (kind == Kind.FIRST_ROUND_JOKER || kind == Kind.TRIPLE_BIG_JOKER) {
            return false;
        }
        return switch (candidate.kind) {
            case TRIPLE_BIG_JOKER -> true;
            case TRIPLE_SMALL_JOKER -> kind == Kind.LEVEL_CARDS;
            case LEVEL_CARDS -> kind == Kind.LEVEL_CARDS
                    && ((candidate.levelCardCount == 2 && levelCardCount == 1)
                        || (candidate.levelCardCount == 3 && levelCardCount <= 2));
            default -> false;
        };
    }

    public Kind kind() {
        return kind;
    }

    public Suit suit() {
        return suit;
    }

    public int levelCardCount() {
        return levelCardCount;
    }

    public Seat seat() {
        return seat;
    }

    @Override
    public String toString() {
        return kind + "{" + suit + ", seat=" + seat
                + (kind == Kind.LEVEL_CARDS ? ", count=" + levelCardCount : "") + "}";
    }
}
