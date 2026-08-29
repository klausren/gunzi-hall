package com.gunzihall.domain.trump;

import com.gunzihall.domain.card.Card;

/**
 * 牌力分层：实现规则手册 2.1 的主牌大小链条。
 *
 * <pre>
 * 大王 &gt; 小王 &gt; 主花色级牌 &gt; 其他花色级牌 &gt; 主花色2 &gt; 其他花色2 &gt; 主花色牌(级牌除外) &gt; 副牌
 * </pre>
 *
 * <p>层级数值越大牌力越强；同层内部再按点数、花色兜底序细分，形成全序，
 * 保证回放与哈希计算的确定性。
 */
final class CardTier {

    // ---- 层级常量（越大越强）----
    static final int BIG_JOKER = 800;
    static final int SMALL_JOKER = 700;
    static final int TRUMP_LEVEL_CARD = 600;   // 主花色级牌
    static final int OFF_LEVEL_CARD = 500;     // 其他花色级牌
    static final int TRUMP_TWO = 400;          // 主花色2
    static final int OFF_TWO = 300;            // 其他花色2
    static final int TRUMP_PLAIN = 200;        // 主花色牌（级牌、2 除外）
    static final int OFF_PLAIN = 100;          // 副牌（非级牌、非2、非主花色）

    private CardTier() {
    }

    static int tierOf(Card card, TrumpContext ctx) {
        if (card.isJoker()) {
            return card.joker() == com.gunzihall.domain.card.Joker.BIG ? BIG_JOKER : SMALL_JOKER;
        }
        boolean isTrumpSuit = card.suit() == ctx.trumpSuit();
        boolean isLevel = ctx.isLevel(card.rank());
        boolean isTwo = card.rank() == Card.TWO;

        if (isLevel) {
            return isTrumpSuit ? TRUMP_LEVEL_CARD : OFF_LEVEL_CARD;
        }
        if (isTwo) {
            return isTrumpSuit ? TRUMP_TWO : OFF_TWO;
        }
        return isTrumpSuit ? TRUMP_PLAIN : OFF_PLAIN;
    }

    /**
     * 判断一张牌是否为主牌。
     * <p>按手册 2.1 链条，"副牌"以上的全部算主牌：
     * 王、所有 2（常主）、所有级牌、主花色牌。
     */
    static boolean isTrump(Card card, TrumpContext ctx) {
        return tierOf(card, ctx) >= TRUMP_PLAIN;
    }
}
