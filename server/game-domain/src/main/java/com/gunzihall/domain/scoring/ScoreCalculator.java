package com.gunzihall.domain.scoring;

import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.deck.Deck;

import java.util.Collection;

/**
 * 计分：分牌为 5/10/K（Q1 已拍板：5=5、10=10、K=10）。
 * 三副牌满分 300 分；80 分为庄家及格线，120 分为抓分方上台线，150 分以上进入进贡区间。
 */
public final class ScoreCalculator {

    /** 庄家及格线 */
    public static final int BANKER_PASS_LINE = 80;
    /** 抓分方上台线 */
    public static final int DEFENDER_PROMOTE_LINE = 120;
    /** 分差进贡起点（超过 150 分庄家方进贡） */
    public static final int BANKER_TRIBUTE_LINE = 150;
    /** 一局满分 */
    public static final int FULL_SCORE = 300;

    private ScoreCalculator() {
    }

    public static int points(Collection<Card> cards) {
        return cards.stream().mapToInt(Card::points).sum();
    }

    /** 断言：三副牌总分必须恰好 300（测试与结算自检用） */
    public static boolean fullDeckSanityCheck() {
        return Deck.fresh().cards().stream().mapToInt(Card::points).sum() == FULL_SCORE;
    }
}
