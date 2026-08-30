package com.gunzihall.domain.card;

import java.util.ArrayList;
import java.util.List;

/**
 * 手牌多重集操作工具（三副牌关键路径）。
 *
 * <p>背景：{@link Card} 是值对象，同一点数花色的 3 张牌 equals 相同。
 * 因此手牌是"多重集"——{@code List.containsAll} / {@code List.removeAll}
 * 均按身份判定，无法区分副本数量：
 * <ul>
 *   <li>{@code hand.containsAll(cards)}：手里 1 张 H5 也能通过 [H5,H5,H5] 的校验；</li>
 *   <li>{@code hand.removeAll(cards)}：出 1 张 H5 会把手里的 H5 全部删光。</li>
 * </ul>
 * 所有"从手牌校验/移除若干张"的命令必须走本工具，按副本数精确判定。
 */
public final class Cards {

    private Cards() {
    }

    /** 手牌是否包含 cards 的全部副本（多重集包含，逐张消耗判定） */
    public static boolean containsCopies(List<Card> hand, List<Card> cards) {
        List<Card> rest = new ArrayList<>(hand);
        for (Card c : cards) {
            if (!rest.remove(c)) {
                return false;
            }
        }
        return true;
    }

    /** 从手牌移除 cards 的各一张副本（调用前须已通过 {@link #containsCopies} 校验） */
    public static void removeCopies(List<Card> hand, List<Card> cards) {
        for (Card c : cards) {
            if (!hand.remove(c)) {
                throw new IllegalStateException("手牌缺失副本，状态已损坏: " + c);
            }
        }
    }
}
