package com.gunzihall.domain.card;

/** 大小王（常主）。 */
public enum Joker {
    /** 大王（红鬼），牌力最大的常主 */
    BIG("大王"),
    /** 小王（黑鬼），牌力仅次于大王 */
    SMALL("小王");

    private final String label;

    Joker(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
