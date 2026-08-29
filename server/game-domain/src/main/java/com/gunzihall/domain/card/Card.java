package com.gunzihall.domain.card;

import java.util.Objects;

/**
 * 单张牌（值对象，不可变）。
 * <p>三副牌中同一点数花色的 3 张牌视为同一个身份（{@link #equals} 按身份判定），
 * 具体哪一张副本在领域层不区分。
 *
 * <p>规则依据：规则手册 v1.1 第 1、2 节。
 */
public final class Card {

    /** 点数常量（2 为常主；J=11, Q=12, K=13, A=14；本玩法级数序列 3..10，无 J） */
    public static final int TWO = 2;
    public static final int JACK = 11;
    public static final int QUEEN = 12;
    public static final int KING = 13;
    public static final int ACE = 14;

    private final Suit suit;   // null 当且仅当 joker != null
    private final int rank;    // 2..14；joker 时无意义
    private final Joker joker; // null 当且仅当 suit != null

    private Card(Suit suit, int rank, Joker joker) {
        this.suit = suit;
        this.rank = rank;
        this.joker = joker;
    }

    public static Card of(Suit suit, int rank) {
        if (suit == null) {
            throw new IllegalArgumentException("花色牌的 suit 不能为空");
        }
        if (rank < 2 || rank > ACE) {
            throw new IllegalArgumentException("点数必须在 2..14 之间: " + rank);
        }
        return new Card(suit, rank, null);
    }

    public static Card bigJoker() {
        return new Card(null, 0, Joker.BIG);
    }

    public static Card smallJoker() {
        return new Card(null, 0, Joker.SMALL);
    }

    public boolean isJoker() {
        return joker != null;
    }

    /** @return 花色；王牌返回 null */
    public Suit suit() {
        return suit;
    }

    /** @return 点数 2..14；王无点数 */
    public int rank() {
        return rank;
    }

    /** @return 大小王；花色牌返回 null */
    public Joker joker() {
        return joker;
    }

    /**
     * 分牌分值（规则手册 Q1 已拍板）：5=5 分、10=10 分、K=10 分，其余 0 分。
     * 三副牌满分 = 12×5 + 12×10 + 12×10 = 300 分。
     */
    public int points() {
        if (isJoker()) {
            return 0;
        }
        return switch (rank) {
            case 5 -> 5;
            case 10, KING -> 10;
            default -> 0;
        };
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Card other)) return false;
        return rank == other.rank && suit == other.suit && joker == other.joker;
    }

    @Override
    public int hashCode() {
        return Objects.hash(suit, rank, joker);
    }

    @Override
    public String toString() {
        if (isJoker()) {
            return joker.toString();
        }
        return suit.symbol() + rankLabel();
    }

    private String rankLabel() {
        return switch (rank) {
            case JACK -> "J";
            case QUEEN -> "Q";
            case KING -> "K";
            case ACE -> "A";
            default -> String.valueOf(rank);
        };
    }
}
