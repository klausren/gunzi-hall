package com.gunzihall.domain.play;

/**
 * 跟牌规则模式（规则手册 3.3，Q3 已拍板）。
 *
 * <ul>
 *   <li>{@link #LIVE 活棒}（默认）：打滚子时可以不跟滚子、不跟棒；打棒时可以不跟棒。
 *       首家花色的跟牌义务（手册 3.2）仍然保留——只是不要求凑成型。</li>
 *   <li>{@link #DEAD 死棒}：打棒必须出棒，没有时可以给两张单张；没棒有滚子时可以不出滚子；
 *       打滚子必须出滚子，没有时按"棒+单张"或"3张单牌"顺序出。</li>
 * </ul>
 */
public enum FollowRule {
    LIVE("活棒"),
    DEAD("死棒");

    private final String label;

    FollowRule(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Q3 已拍板：默认活棒 */
    public static FollowRule defaultValue() {
        return LIVE;
    }
}
