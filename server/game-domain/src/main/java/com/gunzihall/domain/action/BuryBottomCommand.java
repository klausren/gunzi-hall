package com.gunzihall.domain.action;

import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.card.Joker;
import com.gunzihall.domain.player.Player;
import com.gunzihall.domain.room.GamePhase;
import com.gunzihall.domain.room.GameRoom;

import java.util.ArrayList;
import java.util.List;

/**
 * 扣底命令（手册 2.3，Q5/Q5b 已拍板）。
 *
 * <p>庄家先把底牌收入手牌（命令内一次性完成），再扣出同样张数的牌重新埋底。
 * 服务端权威校验：
 * <ul>
 *   <li><strong>Q5 扣王顺序</strong>：扣牌含小王时，手牌中所有大王必须先扣完
 *       （"先大王后小王"系统强制约束）；</li>
 *   <li><strong>干锅</strong>（手册 2.3.7）：原底牌中没有主花色普通牌（级牌/2/王除外）
 *       时底牌不能替换——只允许原样扣回；干锅局标记 dryPot，结算时底牌王不算血
 *       不追加升级；</li>
 *   <li>扣牌张数 = 底牌张数，且都在庄家手牌中。</li>
 * </ul>
 *
 * <p>扣底完成后 PLAYING 开始，首出人 = 庄家（领出权）。他人捡牌扣王（手册 2.3 第 5 条）
 * 留待后续 Sprint 扩展。
 */
public final class BuryBottomCommand extends AbstractGameCommand {

    private final List<Card> cards;

    public BuryBottomCommand(long roomId, long playerId, List<Card> cards) {
        super(roomId, playerId);
        this.cards = List.copyOf(cards);
    }

    @Override
    public CommandResult execute(GameRoom room) {
        if (room.phase() != GamePhase.BURYING) {
            return CommandResult.fail("当前阶段不能扣底: " + room.phase());
        }
        var bankerSeat = room.bankerSeat().orElse(null);
        if (bankerSeat == null) {
            return CommandResult.fail("庄家未定，不能扣底");
        }
        Player banker = Players.find(room, playerId());
        if (banker == null) {
            return CommandResult.fail("玩家不在本房间: " + playerId());
        }
        if (banker.seat() != bankerSeat) {
            return CommandResult.fail("只有庄家能扣底，庄家是 " + bankerSeat);
        }
        var trump = room.trump().orElse(null);
        if (trump == null) {
            return CommandResult.fail("主牌上下文缺失，不能扣底");
        }

        // ---- 庄家收底（一次性） ----
        List<Card> originalBottom = room.bottomCards();
        if (!room.isBottomTaken()) {
            banker.hand().addAll(originalBottom);
            room.setBottomTaken(true);
        }

        if (cards.size() != originalBottom.size()) {
            return CommandResult.fail("扣底张数必须等于底牌张数 " + originalBottom.size());
        }
        if (!com.gunzihall.domain.card.Cards.containsCopies(banker.hand(), cards)) {
            return CommandResult.fail("所扣之牌不在庄家手牌中（或副本数不足）");
        }

        // ---- 干锅判定（用原底牌；级牌/2/王不算主花色普通牌） ----
        long trumpPlain = originalBottom.stream()
                .filter(c -> !c.isJoker() && c.rank() != 2 && c.rank() != trump.level())
                .filter(c -> c.suit() == trump.trumpSuit())
                .count();
        boolean dryPot = trumpPlain == 0;
        if (dryPot) {
            List<Card> actual = new ArrayList<>(cards);
            List<Card> expected = new ArrayList<>(originalBottom);
            if (actual.size() != expected.size() || !actual.containsAll(expected)) {
                return CommandResult.fail("干锅局（底牌无主花色普通牌）底牌不能替换，只能原样扣回");
            }
            room.setDryPot(true);
        }

        // ---- Q5：扣王必须先扣完所有大王，才能扣小王 ----
        // 干锅局强制原样扣回时跳过：系统强制行为无选择余地（底牌含小王而庄家手里
        // 有大王时，Q5 与干锅约束会互相矛盾，以干锅"不能替换"为准）。
        if (!dryPot) {
            long handBigJokers = banker.hand().stream()
                    .filter(c -> c.isJoker() && c.joker() == Joker.BIG).count();
            long buriedBigJokers = cards.stream()
                    .filter(c -> c.isJoker() && c.joker() == Joker.BIG).count();
            boolean buriedSmallJoker = cards.stream()
                    .anyMatch(c -> c.isJoker() && c.joker() == Joker.SMALL);
            if (buriedSmallJoker && handBigJokers > 0 && buriedBigJokers != handBigJokers) {
                return CommandResult.fail("扣王必须先扣完所有大王才能扣小王（Q5 系统强制约束）：手牌有 "
                        + handBigJokers + " 张大王，扣牌中只有 " + buriedBigJokers + " 张");
            }
        }

        // ---- 执行 ----
        com.gunzihall.domain.card.Cards.removeCopies(banker.hand(), cards);
        room.setBottomCards(cards);
        room.setTurnSeat(bankerSeat); // 庄家领出第一手
        room.transitionTo(GamePhase.PLAYING);
        return CommandResult.ok();
    }

    @Override
    public CommandResult rollback(GameRoom room) {
        return CommandResult.fail("扣底命令不支持回滚（底牌涉及结算，防作弊关键路径）");
    }
}
