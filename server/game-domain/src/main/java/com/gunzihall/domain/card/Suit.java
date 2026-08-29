package com.gunzihall.domain.card;

/**
 * 花色。
 * <p>suitOrder 仅用作同点数不同花色时的确定性排序兜底（规则手册 2.3-5：点数相同花色不同时
 * 由玩家自选，引擎内部仍需一个稳定的全序用于回放和哈希一致）。
 */
public enum Suit {
    SPADE("♠", 0),
    HEART("♥", 1),
    CLUB("♣", 2),
    DIAMOND("♦", 3);

    private final String symbol;
    private final int suitOrder;

    Suit(String symbol, int suitOrder) {
        this.symbol = symbol;
        this.suitOrder = suitOrder;
    }

    public String symbol() {
        return symbol;
    }

    /** 同点数不同花色的稳定排序值（越小越靠前），不参与牌力大小判定 */
    public int suitOrder() {
        return suitOrder;
    }

    @Override
    public String toString() {
        return symbol;
    }
}
