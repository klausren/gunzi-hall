package com.gunzihall.domain.trump;

import com.gunzihall.domain.card.Suit;

/**
 * 主牌上下文：当前级数 + 主花色。
 * <p>一局牌的全部牌力判定都依赖这个不可变上下文，由亮王/抠庄阶段产出，
 * 出牌阶段不得变更。
 *
 * <p>规则依据：规则手册 v1.1 第 2 节。
 *
 * <p>扩展点（Q7 已拍板"暂不支持"）：若未来引入"混主"地区变体，
 * 应在本类增加变体标志并调整 {@link CardComparator}，不改动牌本身。
 */
public record TrumpContext(int level, Suit trumpSuit) {

    /** 级数序列 3..10（无 J，打完 10 出锅） */
    public static final int MIN_LEVEL = 3;
    public static final int MAX_LEVEL = 10;

    public TrumpContext {
        if (level < MIN_LEVEL || level > MAX_LEVEL) {
            throw new IllegalArgumentException("级数必须在 " + MIN_LEVEL + ".." + MAX_LEVEL + " 之间: " + level);
        }
        if (trumpSuit == null) {
            throw new IllegalArgumentException("主花色不能为空（本玩法不能叫无主，规则手册 2.2）");
        }
    }

    public boolean isLevel(int rank) {
        return rank == level;
    }
}
