package com.gunzihall.domain.action;

import com.gunzihall.domain.card.Joker;
import com.gunzihall.domain.player.Seat;
import com.gunzihall.domain.player.Team;
import com.gunzihall.domain.play.RoundSettlement;
import com.gunzihall.domain.room.GamePhase;
import com.gunzihall.domain.room.GameRoom;
import com.gunzihall.domain.tribute.TributeCalculator;
import com.gunzihall.domain.tribute.TributeObligation;
import com.gunzihall.domain.trump.TrumpContext;

/**
 * 一局结算编排命令（手册 4、5 节）：PLAYING 打空后进入 SETTLING，
 * 由本命令汇总收分/底牌/最后一圈，产出：
 *
 * <ul>
 *   <li>得分与上台/升级/出锅判定（{@link RoundSettlement}，含抠底 ×2）；</li>
 *   <li>进贡血数（{@link TributeCalculator}：分差折算 + 扣王折算，Q4b）；</li>
 *   <li>3.4.3 底牌扣三王的中途结束判定；</li>
 *   <li>下一局级数/庄家/进贡义务，并重置局内状态开新局。</li>
 * </ul>
 *
 * <p>出锅路径（手册 3.4.1/3.4.2/4.5）：
 * 抓分方 0 分或 300 分；底牌三王 + 保底/抠底条件；升级后越过 10 级（打完 10 出锅）。
 *
 * <p>干锅局（手册 2.3.7）：底牌王不算血、不追加升级，进贡只按分差折算。
 */
public final class SettleRoundCommand extends AbstractGameCommand {

    public SettleRoundCommand(long roomId, long playerId) {
        super(roomId, playerId);
    }

    @Override
    public CommandResult execute(GameRoom room) {
        if (room.phase() != GamePhase.SETTLING) {
            return CommandResult.fail("当前阶段不能结算: " + room.phase());
        }
        Seat bankerSeat = room.bankerSeat().orElse(null);
        if (bankerSeat == null) {
            return CommandResult.fail("庄家未定，无法结算");
        }
        Team lastWinner = room.lastTrickWinnerTeam().orElse(null);
        if (lastWinner == null) {
            return CommandResult.fail("最后一圈赢家缺失，无法结算");
        }
        if (Players.find(room, playerId()) == null) {
            return CommandResult.fail("玩家不在本房间: " + playerId());
        }

        Team bankerTeam = bankerSeat.team();
        RoundSettlement.Result result = RoundSettlement.settle(
                bankerTeam, room.trickPoints(), room.bottomCards(), lastWinner);
        room.setLastSettlement(result);

        // ---- 进贡血数（Q4b）：干锅局王不算血 ----
        long bigJokers = room.bottomCards().stream()
                .filter(c -> c.isJoker() && c.joker() == Joker.BIG).count();
        long smallJokers = room.bottomCards().stream()
                .filter(c -> c.isJoker() && c.joker() == Joker.SMALL).count();
        TributeCalculator.TributeResult tribute = TributeCalculator.calculate(
                result.attackerScore(),
                !result.dugBottom(),
                room.isDryPot() ? 0 : (int) bigJokers,
                room.isDryPot() ? 0 : (int) smallJokers);
        room.setLastTributeResult(tribute);

        // ---- 3.4.3：底牌扣 3 大王或 3 小王的中途结束 ----
        boolean tripleJokers = bigJokers == 3 || smallJokers == 3;
        boolean midGameOver = tripleJokers
                && ((!result.dugBottom() && result.attackerScore() < 120)
                    || (result.dugBottom() && result.attackerScore() >= 120));

        boolean promoted = result.attackerPromoted() || result.bankerPromoted();
        int nextLevel = room.currentLevel() + (promoted ? 1 : 0);

        // ---- 出锅判定 ----
        boolean roundOver = result.roundOver() || midGameOver
                || (promoted && nextLevel > TrumpContext.MAX_LEVEL);

        if (roundOver) {
            room.setCurrentLevel(TrumpContext.MIN_LEVEL);
            room.setFirstRound(true);
            room.setBankerSeat(null);
            room.clearTributeObligations();
            room.resetRoundState();
            room.setGameNumber(room.gameNumber() + 1);
            room.transitionTo(GamePhase.ROUND_OVER);
            return CommandResult.ok();
        }

        // ---- 下一局：级数/庄家/进贡义务 ----
        Seat newBanker = result.attackerTakesBank() ? bankerSeat.previous() : bankerSeat;

        room.setCurrentLevel(nextLevel);
        room.setBankerSeat(newBanker);
        room.setFirstRound(false);
        room.setGameNumber(room.gameNumber() + 1);
        room.resetRoundState(); // 先清局内状态（含旧进贡义务）

        int defBlood = bloodOf(tribute, TributeCalculator.Payer.DEFENDER);
        int bankBlood = bloodOf(tribute, TributeCalculator.Payer.BANKER);
        if (defBlood > 0) {
            // 抓分方进贡给庄家：执行人 = 庄家上家（Q4）
            room.putTributeObligation(bankerSeat.previous(),
                    new TributeObligation(defBlood, bankerSeat));
        } else if (bankBlood > 0) {
            // 庄家方进贡给新庄家（抓分方已上台）：执行人 = 新庄家上家
            room.putTributeObligation(newBanker.previous(),
                    new TributeObligation(bankBlood, newBanker));
        }

        room.transitionTo(GamePhase.DEALING); // 开新局，等 ShuffleAndDealCommand
        return CommandResult.ok();
    }

    private static int bloodOf(TributeCalculator.TributeResult tribute, TributeCalculator.Payer payer) {
        int blood = 0;
        if (tribute.scoreBlood().payer() == payer) {
            blood += tribute.scoreBlood().blood();
        }
        if (tribute.kingBlood().payer() == payer) {
            blood += tribute.kingBlood().blood();
        }
        return blood;
    }

    @Override
    public CommandResult rollback(GameRoom room) {
        return CommandResult.fail("结算命令不支持回滚");
    }
}
