package com.gunzihall.domain.tribute;

import com.gunzihall.domain.player.Team;

/**
 * 进贡血数计算（Q4b 已拍板公式）。
 *
 * <p>总血数 = 分差折算血数 + 扣王折算血数，两笔分别结算进贡方向：
 * <pre>
 * 一、分差折算（以 80 分及格线、150 分进贡线为基准，10 分折 1 血）
 *   S &lt; 80          → 抓分方进贡，血数 = ceil((80-S)/10)   尾数 5 分按 10 分计（向上进位）
 *   80 ≤ S ≤ 150     → 0 血
 *   S &gt; 150         → 庄家方进贡，血数 = floor((S-150)/10) 尾数 5 分不计（向下取整）
 *
 * 二、扣王折算（大王 = 2 血，小王 = 1 血）
 *   庄家方保底：
 *     S &lt; 120   → 抓分方追加进贡底牌王血
 *     S ≥ 120   → 庄家方追加进贡（抓分方上台，成为新庄家方）
 *   抓分方抠底：
 *     S ≥ 120   → 完美抠底，庄家方追加进贡王血
 *     S &lt; 120   → 臭抠，底牌王作废（0 血）
 * </pre>
 *
 * <p>执行约束（Q4 已拍板）：只有庄家的上家向庄家进贡。血数方向在
 * {@link TributeResult} 中分别记录，由进贡状态机统一折算成对局动作。
 */
public final class TributeCalculator {

    /** 大王折血 */
    public static final int BIG_JOKER_BLOOD = 2;
    /** 小王折血 */
    public static final int SMALL_JOKER_BLOOD = 1;

    private TributeCalculator() {
    }

    /**
     * @param score             抓分方本局得分（不含抠底翻倍时请先按规则手册第 6 节折算后传入）
     * @param defendersTookBottom 庄家方是否保底（true=保底；false=抓分方抠底）
     * @param bigJokersInBottom   底牌中大王张数
     * @param smallJokersInBottom 底牌中小王张数
     */
    public static TributeResult calculate(int score, boolean defendersTookBottom,
                                          int bigJokersInBottom, int smallJokersInBottom) {
        BloodPart scorePart = scorePart(score);
        BloodPart kingPart = kingPart(score, defendersTookBottom,
                bigJokersInBottom, smallJokersInBottom);
        return new TributeResult(scorePart, kingPart);
    }

    /** 分差折算（进贡方以庄家视角记录：DEFENDER=抓分方进贡，BANKER=庄家方进贡） */
    private static BloodPart scorePart(int score) {
        if (score < 0 || score > 300) {
            throw new IllegalArgumentException("得分必须在 0..300: " + score);
        }
        if (score < 80) {
            // 尾数 5 分按 10 分计 → 向上取整
            int blood = ceilDiv(80 - score, 10);
            return new BloodPart(blood, Payer.DEFENDER);
        }
        if (score <= 150) {
            return new BloodPart(0, Payer.NONE);
        }
        // 尾数 5 分不计 → 向下取整
        return new BloodPart((score - 150) / 10, Payer.BANKER);
    }

    private static BloodPart kingPart(int score, boolean defendersTookBottom,
                                      int bigJokers, int smallJokers) {
        int blood = bigJokers * BIG_JOKER_BLOOD + smallJokers * SMALL_JOKER_BLOOD;
        if (blood == 0) {
            return new BloodPart(0, Payer.NONE);
        }
        if (defendersTookBottom) {
            // 庄家保底：S<120 抓分方进贡；S≥120 抓分方已上台，庄家方进贡
            return new BloodPart(blood, score < 120 ? Payer.DEFENDER : Payer.BANKER);
        }
        // 抓分方抠底：S≥120 完美抠底庄家方进贡；S<120 臭抠作废
        if (score >= 120) {
            return new BloodPart(blood, Payer.BANKER);
        }
        return new BloodPart(0, Payer.NONE); // 臭抠，王作废
    }

    /** 向上取整除法（正数） */
    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    /** 进贡方（NONE 表示该笔为 0 血） */
    public enum Payer {
        NONE, DEFENDER, BANKER;

        /** 转成队伍语义（庄家方视角） */
        public Team teamOf(Team bankerTeam) {
            return switch (this) {
                case NONE -> null;
                case DEFENDER -> bankerTeam.opponent();
                case BANKER -> bankerTeam;
            };
        }
    }

    /** 一笔血：血数 + 进贡方 */
    public record BloodPart(int blood, Payer payer) {
        public boolean isEmpty() {
            return blood <= 0 || payer == Payer.NONE;
        }
    }

    /**
     * 进贡结果：分差血与扣王血分别记录。
     * 两笔方向可能不同（如分差是抓分方进贡、扣王是庄家方进贡），进贡状态机分别结算。
     */
    public record TributeResult(BloodPart scoreBlood, BloodPart kingBlood) {

        public int totalBlood() {
            return scoreBlood.blood() + kingBlood.blood();
        }
    }
}
