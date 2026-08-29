package com.gunzihall.domain.play;

import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.player.Team;
import com.gunzihall.domain.scoring.ScoreCalculator;

import java.util.List;
import java.util.Map;

/**
 * 一局结算（手册 3.4、4.1–4.4，Q8 已拍板"抠底固定 ×2"）。
 *
 * <p>区分两件事：
 * <ul>
 *   <li><strong>上台（换庄）</strong>：抓分方得分 ≥ 120（上台线）即上台坐庄（4.3 第 3/4 行：
 *       即便庄家保底，得分过线抓分方照样上台）；否则庄家方继续坐庄；</li>
 *   <li><strong>升级（级数 +1）</strong>：抓分方 ≥120 且抠底（4.1）；庄家方 &lt;120 且保底（4.2）。
 *       其余组合（过线未抠底 / 未过线被抠底）只换庄或留庄，不升级。</li>
 * </ul>
 *
 * <p>抠底：最后一圈赢家获得底牌分 <strong>×2</strong>（庄家方赢最后一圈 = 保底，
 * 抓分方赢 = 抠底）。
 *
 * <p>出锅判定（3.4.2）：抓分方 0 分或拿到 300 分 → 整轮结束；打完 10 级出锅属多局推进，
 * 由轮次编排层判定。3.4.3 底牌扣三王的中途结束与 4.3 扣王奖励折算，
 * 由 {@code TributeCalculator} 在 Sprint 3 结算编排时接入。
 */
public final class RoundSettlement {

    /** 结算结果 */
    public record Result(
            /** 抓分方最终得分（含抠底×2 的底牌分） */
            int attackerScore,
            /** 庄家方最终得分（含保底×2 的底牌分） */
            int bankerScore,
            /** 抓分方是否抠底（赢了最后一圈） */
            boolean dugBottom,
            /** 是否触发整轮结束（出锅） */
            boolean roundOver,
            /** 出锅原因（未出锅为 null） */
            String roundOverReason,
            /** 抓分方是否上台坐庄（≥120 上台线） */
            boolean attackerTakesBank,
            /** 抓分方是否升级（≥120 且抠底，4.1） */
            boolean attackerPromoted,
            /** 庄家方是否升级（&lt;120 且保底，4.2） */
            boolean bankerPromoted) {
    }

    private RoundSettlement() {
    }

    /**
     * 结算一局。
     *
     * @param bankerTeam          庄家方队伍
     * @param trickPoints         两队各自在出牌阶段收走的分牌（不含底牌）
     * @param bottomCards         底牌（6 张）
     * @param lastTrickWinnerTeam 最后一圈赢家的队伍
     */
    public static Result settle(Team bankerTeam,
                                Map<Team, Integer> trickPoints,
                                List<Card> bottomCards,
                                Team lastTrickWinnerTeam) {
        Team attackerTeam = bankerTeam.opponent();
        int bottomPoints = ScoreCalculator.points(bottomCards);

        boolean dug = lastTrickWinnerTeam == attackerTeam;
        int attackerScore = trickPoints.getOrDefault(attackerTeam, 0)
                + (dug ? bottomPoints * 2 : 0);
        int bankerScore = trickPoints.getOrDefault(bankerTeam, 0)
                + (dug ? 0 : bottomPoints * 2);

        // 出锅判定（手册 3.4.2）
        boolean roundOver = false;
        String reason = null;
        if (attackerScore == 0) {
            roundOver = true;
            reason = "抓分方一分不得，庄家方整轮获胜（出锅）";
        } else if (attackerScore >= ScoreCalculator.FULL_SCORE) {
            roundOver = true;
            reason = "抓分方拿到满分 300，整轮结束（出锅）";
        }

        // 上台与升级（4.1/4.2/4.3，简化版：未含扣王奖励）
        boolean attackerTakesBank = attackerScore >= ScoreCalculator.DEFENDER_PROMOTE_LINE;
        boolean attackerPromoted = attackerTakesBank && dug;
        boolean bankerPromoted = attackerScore < ScoreCalculator.DEFENDER_PROMOTE_LINE && !dug;

        return new Result(attackerScore, bankerScore, dug, roundOver, reason,
                attackerTakesBank, attackerPromoted, bankerPromoted);
    }
}
