package com.gunzihall.domain.play;

/**
 * 牌型（规则手册 3.1）。
 * <p>打滚子只有三种牌型：单牌、棒子（对子）、滚子（三张）。
 * <strong>无顺子、无拖拉机、无泰坦尼克、无甩牌</strong>——这是与"升级/拖拉机"的核心区别。
 */
public enum ComboType {
    /** 单牌：任意一张 */
    SINGLE(1),
    /** 棒子：两张牌点和花色都相同的牌（对子） */
    PAIR(2),
    /** 滚子：三张牌点和花色都相同的牌（游戏名由来） */
    TRIPLE(3);

    private final int size;

    ComboType(int size) {
        this.size = size;
    }

    /** 该牌型的张数 */
    public int size() {
        return size;
    }

    /** 按张数反查牌型 */
    public static ComboType ofSize(int size) {
        return switch (size) {
            case 1 -> SINGLE;
            case 2 -> PAIR;
            case 3 -> TRIPLE;
            default -> throw new IllegalArgumentException("非法张数: " + size);
        };
    }
}
