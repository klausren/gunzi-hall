package com.gunzihall.domain.trump;

import com.gunzihall.domain.card.Card;

import java.util.Comparator;

/**
 * 牌力比较器：在给定 {@link TrumpContext} 下对两张牌建立全序（牌力大者"大"）。
 *
 * <p>规则依据：手册 2.1 大小链条。层级相同时：
 * <ul>
 *   <li>主/副花色普通牌：先比点数，点数相同再比花色兜底序（仅同点异色时出现，规则允许玩家自选，引擎内部取稳定序）</li>
 *   <li>其他花色级牌 / 其他花色2：同点不同花，按花色兜底序</li>
 * </ul>
 *
 * <p>注意：{@code compare} 返回正数表示前者<strong>牌力更大</strong>；
 * 按升序 {@code sort} 的结果为牌力从小到大。
 */
public final class CardComparator implements Comparator<Card> {

    private final TrumpContext ctx;

    public CardComparator(TrumpContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public int compare(Card a, Card b) {
        int ta = CardTier.tierOf(a, ctx);
        int tb = CardTier.tierOf(b, ctx);
        if (ta != tb) {
            return Integer.compare(ta, tb);
        }
        // ---- 同层细分 ----
        return switch (ta) {
            // 王各自一层，层内只有一种身份
            case CardTier.BIG_JOKER, CardTier.SMALL_JOKER,
                 CardTier.TRUMP_LEVEL_CARD, CardTier.TRUMP_TWO,
                 CardTier.TRUMP_PLAIN -> Integer.compare(a.rank(), b.rank()); // 主花色普通牌比点数
            // 同点异花（副级牌、副2、副牌）：先点数后花色兜底序
            default -> {
                int byRank = Integer.compare(a.rank(), b.rank());
                yield byRank != 0 ? byRank
                        : Integer.compare(a.suit().suitOrder(), b.suit().suitOrder());
            }
        };
    }

    /** @return a 是否为严格大于 b 的主牌/单牌 */
    public boolean stronger(Card a, Card b) {
        return compare(a, b) > 0;
    }

    /** @return 该牌在此上下文下是否为主牌（王、2、级牌、主花色） */
    public boolean isTrump(Card card) {
        return CardTier.isTrump(card, ctx);
    }
}
