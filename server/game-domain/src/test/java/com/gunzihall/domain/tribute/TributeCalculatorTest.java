package com.gunzihall.domain.tribute;

import com.gunzihall.domain.tribute.TributeCalculator.BloodPart;
import com.gunzihall.domain.tribute.TributeCalculator.Payer;
import com.gunzihall.domain.tribute.TributeCalculator.TributeResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Q4b 已拍板公式的单元测试。
 * 尾数规则不对称是重点：<80 向上进位（5 分按 10 计），>150 向下取整（5 分不计）。
 */
class TributeCalculatorTest {

    @Test
    void scoreBelow80RoundsUp() {
        // 79 分：差 1 分 → ceil(1/10)=1 血，抓分方进贡
        assertScoreBlood(79, 1, Payer.DEFENDER);
        // 75 分：差 5 分 → 5 分按 10 计 → 1 血
        assertScoreBlood(75, 1, Payer.DEFENDER);
        // 70 分：差 10 分 → 1 血
        assertScoreBlood(70, 1, Payer.DEFENDER);
        // 65 分：差 15 分 → ceil(15/10)=2 血（尾数 5 进位）
        assertScoreBlood(65, 2, Payer.DEFENDER);
        // 0 分：差 80 分 → 8 血
        assertScoreBlood(0, 8, Payer.DEFENDER);
    }

    @Test
    void scoreBetween80And150IsZero() {
        assertScoreBlood(80, 0, Payer.NONE);
        assertScoreBlood(120, 0, Payer.NONE);   // 抓分方刚好上台，无分差血
        assertScoreBlood(150, 0, Payer.NONE);
    }

    @Test
    void scoreAbove150RoundsDown() {
        // 155 分：多 5 分 → 5 分不计 → 0 血
        assertScoreBlood(155, 0, Payer.BANKER);
        // 160 分：多 10 分 → 1 血
        assertScoreBlood(160, 1, Payer.BANKER);
        // 165 分：多 15 分 → floor(15/10)=1 血（尾数 5 不计）
        assertScoreBlood(165, 1, Payer.BANKER);
        // 300 分：15 血
        assertScoreBlood(300, 15, Payer.BANKER);
    }

    @Test
    void kingBloodWhenDefendersKeepBottom() {
        // 庄家保底，抓分方 100 分（<120）→ 抓分方进贡王血
        TributeResult r = TributeCalculator.calculate(100, true, 1, 1);
        BloodPart king = r.kingBlood();
        assertEquals(3, king.blood()); // 大王2 + 小王1
        assertEquals(Payer.DEFENDER, king.payer());

        // 庄家保底，抓分方 130 分（≥120，已上台）→ 庄家方进贡王血
        r = TributeCalculator.calculate(130, true, 0, 2);
        assertEquals(2, r.kingBlood().blood());
        assertEquals(Payer.BANKER, r.kingBlood().payer());
    }

    @Test
    void kingBloodWhenDefendersDigBottom() {
        // 完美抠底：130 分 ≥120 → 庄家方进贡王血
        TributeResult r = TributeCalculator.calculate(130, false, 2, 0);
        assertEquals(4, r.kingBlood().blood());
        assertEquals(Payer.BANKER, r.kingBlood().payer());

        // 臭抠：100 分 <120 → 底牌王作废
        r = TributeCalculator.calculate(100, false, 1, 1);
        assertEquals(0, r.kingBlood().blood());
        assertEquals(Payer.NONE, r.kingBlood().payer());
    }

    @Test
    void directionInvariant_twoPartsNeverOppose() {
        // 规则不变量：庄家方进贡王血的前提是 S>=120（上台/完美抠底），
        // 而抓分方进贡分差血的前提是 S<80 —— 两者区间不相交，
        // 所以两笔血的方向永远不会相反（引擎可安全合并结算）。
        for (int score = 0; score <= 300; score += 5) {
            for (boolean keep : new boolean[]{true, false}) {
                TributeResult r = TributeCalculator.calculate(score, keep, 1, 1);
                BloodPart s = r.scoreBlood();
                BloodPart k = r.kingBlood();
                assertFalse(s.payer() == Payer.DEFENDER && k.payer() == Payer.BANKER,
                        "score=" + score + " 出现方向冲突");
                assertFalse(s.payer() == Payer.BANKER && k.payer() == Payer.DEFENDER,
                        "score=" + score + " 出现方向冲突");
            }
        }
    }

    @Test
    void bankerPaysBothPartsWhenCrushed() {
        // 160 分 + 完美抠底 + 底牌 1 大王：分差 1 血 + 王血 2 血，全部庄家方出
        TributeResult r = TributeCalculator.calculate(160, false, 1, 0);
        assertEquals(1, r.scoreBlood().blood());
        assertEquals(2, r.kingBlood().blood());
        assertEquals(Payer.BANKER, r.scoreBlood().payer());
        assertEquals(Payer.BANKER, r.kingBlood().payer());
        assertEquals(3, r.totalBlood());
    }

    @Test
    void totalBloodCombinesParts() {
        // 60 分（抓分方 2 血）+ 保底 + 底牌 1 大王（抓分方再 2 血）= 4 血全由抓分方出
        TributeResult r = TributeCalculator.calculate(60, true, 1, 0);
        assertEquals(2, r.scoreBlood().blood());
        assertEquals(2, r.kingBlood().blood());
        assertEquals(Payer.DEFENDER, r.scoreBlood().payer());
        assertEquals(Payer.DEFENDER, r.kingBlood().payer());
        assertEquals(4, r.totalBlood());
    }

    @Test
    void invalidScoreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> TributeCalculator.calculate(-1, true, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> TributeCalculator.calculate(301, true, 0, 0));
    }

    private void assertScoreBlood(int score, int expectedBlood, Payer expectedPayer) {
        BloodPart part = TributeCalculator.calculate(score, true, 0, 0).scoreBlood();
        assertEquals(expectedBlood, part.blood(), "score=" + score);
        assertEquals(expectedPayer, part.payer(), "score=" + score);
    }
}
