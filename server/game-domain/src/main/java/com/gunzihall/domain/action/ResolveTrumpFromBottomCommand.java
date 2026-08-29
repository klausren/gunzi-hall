package com.gunzihall.domain.action;

import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.card.Joker;
import com.gunzihall.domain.room.GamePhase;
import com.gunzihall.domain.room.GameRoom;
import com.gunzihall.domain.trump.TrumpContext;
import com.gunzihall.domain.trump.TrumpReveal;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 无人亮主时的底牌定主命令（手册 2.2）。
 *
 * <ul>
 *   <li><strong>第一局</strong>：4 家各从底牌翻 1 张，以翻出最大牌的一家为庄家，
 *       该牌花色即主花色（Q2 拍板：同点最大牌时先翻者为庄）。翻出王时王最大但无花色，
 *       命令拒绝并提示换牌重翻。</li>
 *   <li><strong>第二局起</strong>：从底牌任意抽 1 张，以该牌花色为主花色；抽到王继续抽
 *       （命令拒绝并提示换下标重抽）；底牌全王 → 命令拒绝，上层应重新洗牌发牌
 *       （{@code ShuffleAndDealCommand} 支持 BIDDING 阶段重入）。庄家延续上局。</li>
 * </ul>
 *
 * <p>参数 {@code revealIndexes}：第一局为 4 个互异下标（按座位序 N/E/S/W 对应各自翻的牌）；
 * 第二局起为 1 个下标。
 */
public final class ResolveTrumpFromBottomCommand extends AbstractGameCommand {

    private final List<Integer> revealIndexes;

    public ResolveTrumpFromBottomCommand(long roomId, long playerId, List<Integer> revealIndexes) {
        super(roomId, playerId);
        this.revealIndexes = List.copyOf(revealIndexes);
    }

    @Override
    public CommandResult execute(GameRoom room) {
        if (room.phase() != GamePhase.BIDDING) {
            return CommandResult.fail("底牌定主只能在亮主窗口内进行，当前阶段: " + room.phase());
        }
        if (room.revealState().isPresent()) {
            return CommandResult.fail("已有人亮主，无需底牌定主");
        }
        List<Card> bottom = room.bottomCards();
        if (bottom.isEmpty()) {
            return CommandResult.fail("底牌为空（未发牌？）");
        }
        if (Players.find(room, playerId()) == null) {
            return CommandResult.fail("玩家不在本房间: " + playerId());
        }

        if (room.isFirstRound()) {
            return resolveFirstRound(room, bottom);
        }
        return resolveLaterRound(room, bottom);
    }

    /** 第一局：4 家翻底牌，最大牌定庄定花色 */
    private CommandResult resolveFirstRound(GameRoom room, List<Card> bottom) {
        if (revealIndexes.size() != 4) {
            return CommandResult.fail("第一局底牌定主需要 4 家各翻 1 张（按 N/E/S/W 座位序给下标）");
        }
        Set<Integer> distinct = new HashSet<>(revealIndexes);
        if (distinct.size() != 4) {
            return CommandResult.fail("4 家翻的底牌不能是同一张");
        }
        for (int i : revealIndexes) {
            if (i < 0 || i >= bottom.size()) {
                return CommandResult.fail("底牌下标越界: " + i);
            }
        }
        // 找最大牌：王 > 花色牌；花色牌比点数，再比花色兜底序
        int bestIdx = 0;
        for (int i = 1; i < 4; i++) {
            if (compareNoTrump(bottom.get(revealIndexes.get(i)), bottom.get(revealIndexes.get(bestIdx))) > 0) {
                bestIdx = i;
            }
        }
        Card best = bottom.get(revealIndexes.get(bestIdx));
        if (best.isJoker()) {
            return CommandResult.fail("翻出最大牌是王（无花色可定主），请换牌重翻");
        }
        // bestIdx 即座位序（N=0/E=1/S=2/W=3）
        com.gunzihall.domain.player.Seat banker = com.gunzihall.domain.player.Seat.ofIndex(bestIdx);
        room.setTrump(new TrumpContext(room.currentLevel(), best.suit()));
        room.setBankerSeat(banker);
        room.setRevealState(TrumpReveal.firstRoundJoker(banker, best.suit()));
        return advance(room);
    }

    /** 第二局起：抽底牌定主花色，庄家延续 */
    private CommandResult resolveLaterRound(GameRoom room, List<Card> bottom) {
        if (revealIndexes.size() != 1) {
            return CommandResult.fail("第二局起底牌定主只抽 1 张");
        }
        int idx = revealIndexes.get(0);
        if (idx < 0 || idx >= bottom.size()) {
            return CommandResult.fail("底牌下标越界: " + idx);
        }
        Card drawn = bottom.get(idx);
        if (drawn.isJoker()) {
            return CommandResult.fail("抽到王，继续抽（换一个下标重试）；底牌全王请重新发牌");
        }
        if (room.bankerSeat().isEmpty()) {
            return CommandResult.fail("庄家未定（上一局结算异常）");
        }
        room.setTrump(new TrumpContext(room.currentLevel(), drawn.suit()));
        room.setRevealState(TrumpReveal.fixedByBottom(room.bankerSeat().get(), drawn.suit()));
        return advance(room);
    }

    /** 定主完成后推进：有进贡义务 → TRIBUTE，否则 → BURYING */
    private CommandResult advance(GameRoom room) {
        if (room.pendingTributes().isEmpty()) {
            room.transitionTo(GamePhase.BURYING);
        } else {
            room.transitionTo(GamePhase.TRIBUTE);
        }
        return CommandResult.ok();
    }

    /** 无主牌上下文时的原始牌力比较：王 > 花色牌；花色牌先点数后花色兜底序 */
    private static int compareNoTrump(Card a, Card b) {
        int ta = a.isJoker() ? (a.joker() == Joker.BIG ? 2 : 1) : 0;
        int tb = b.isJoker() ? (b.joker() == Joker.BIG ? 2 : 1) : 0;
        if (ta != tb) {
            return Integer.compare(ta, tb);
        }
        if (ta > 0) {
            return 0; // 同为王
        }
        int byRank = Integer.compare(a.rank(), b.rank());
        return byRank != 0 ? byRank
                : Integer.compare(a.suit().suitOrder(), b.suit().suitOrder());
    }

    @Override
    public CommandResult rollback(GameRoom room) {
        return CommandResult.fail("底牌定主不支持回滚");
    }
}
