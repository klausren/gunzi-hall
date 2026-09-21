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
 *
 * <p><b>接庄方向（手册 4.3 / Q9，2026-09-20 补全第二条）</b>：
 * 手册 4.3 只写"抓分方上台坐庄"与"下局庄家方继续坐庄"，都没点明由哪一家接。按 QQ 官方
 * 「打滚子」规则补全为：
 * <ul>
 *   <li>抓分方得分 <b>≥120</b>（上台）→ 新庄家 = 原庄家的<b>下家</b>
 *       （{@link Seat#next()}，出牌逆时针方向的下一家）；</li>
 *   <li>抓分方得分 <b>&lt;120</b>（未上台）→ 新庄家 = 原庄家的<b>对家</b>
 *       （{@link Seat#partner()}）—— "庄家方继续坐庄"指的是庄家方这<b>两个人</b>，
 *       继续坐庄的那一位是庄家的对家。</li>
 * </ul>
 * 连带影响进贡：收贡人一律是<b>下一局的庄家</b>，执行人 = 新庄家的上家
 * （抓分方上台时它恰好落回原庄家本人，见 Q9）。
 * 护栏：{@code SettleRoundCommandTest}。
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
        // 庄家轮换两条分支（手册 4.3 + Q9；2026-09-20 补全第二条）：
        //   ① 抓分方 ≥120（上台）   → 新庄家 = 原庄家的【下家】（next()，出牌逆时针下一家）
        //   ② 抓分方 <120（未上台） → 新庄家 = 原庄家的【对家】（partner()）
        //
        // 【坑 1：方向】next()/previous() 是"出牌方向"（逆时针 N→W→S→E），**不等于** index±1。
        // 【坑 2：本行曾错】分支 ② 旧写法是 `bankerSeat`（原庄家自己连庄）—— 错。
        //   手册 4.3 原文写的是「下局**庄家方**继续坐庄」，而庄家方 = 庄家 + 对家**两个人**；
        //   继续坐庄的是**对家**。QQ 官方「打滚子」规则原文更直白：
        //     「抓分方得分小于120时，下局由本局庄家的**对家**做庄家；
        //       抓分方得分大于等于120时，下一局由本局庄家的**下家**当庄家。」
        //   症状：庄家方赢了，下一局"庄"瓦片还挂在原庄家的铭牌旁，玩家一眼就看出来。
        //   翻牌定庄那两条路（RevealTrumpCommand / ResolveTrumpFromBottomCommand）都只在
        //   第一局才 setBankerSeat，第二局起不再改庄家 —— 所以这里是唯一的换庄点。
        //   护栏：SettleRoundCommandTest#bankerHolds_attackerBelowLine_partnerTakesBank
        Seat newBanker = result.attackerTakesBank() ? bankerSeat.next() : bankerSeat.partner();

        room.setCurrentLevel(nextLevel);
        room.setBankerSeat(newBanker);
        room.setFirstRound(false);
        room.setGameNumber(room.gameNumber() + 1);
        room.resetRoundState(); // 先清局内状态（含旧进贡义务）

        // 进贡的【收贡人一律是下一局的庄家 newBanker】—— 进贡发生在下一局抓牌完成后、拿底牌之前
        // （手册 3.5），那个时点上"庄家"只能是新庄家；执行人 = 新庄家的上家（Q4/Q9）。
        // 因此这里必须用 newBanker，不能用本局的 bankerSeat —— 否则分支 ② 换到对家后，
        // 贡会进错人（进给已经卸任的旧庄家）。
        int defBlood = bloodOf(tribute, TributeCalculator.Payer.DEFENDER);
        int bankBlood = bloodOf(tribute, TributeCalculator.Payer.BANKER);
        if (defBlood > 0) {
            // 分差血 / 保底扣王血：抓分方进贡给新庄家（Q4）
            room.putTributeObligation(newBanker.previous(),
                    new TributeObligation(defBlood, newBanker));
        } else if (bankBlood > 0) {
            // 高分血 / 抠底扣王血：庄家方进贡给新庄家（Q9：执行人恰好落回原庄家本人）
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
