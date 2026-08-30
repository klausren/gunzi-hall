package com.gunzihall.domain.action;

import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.player.Player;
import com.gunzihall.domain.player.Seat;
import com.gunzihall.domain.room.GamePhase;
import com.gunzihall.domain.room.GameRoom;
import com.gunzihall.domain.tribute.TributeObligation;
import com.gunzihall.domain.trump.CardComparator;
import com.gunzihall.domain.trump.TrumpContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 进贡命令（手册 5 节，Q4/Q4b 已拍板）。
 *
 * <p>TRIBUTE 阶段执行：有进贡义务的玩家（收贡人的上家）一次性交足血数的牌。
 *
 * <p>服务端权威校验——所贡必须是手中"最大的牌（分牌除外）"：
 * <ul>
 *   <li>候选 = 手牌中剔除分牌（5/10/K）后的牌；</li>
 *   <li>但打 5、打 10 时 5/10 是级牌，级牌优先于分牌身份，必须进贡（手册 5 基础规则第 2 条）；</li>
 *   <li>按 {@link CardComparator}（主牌体系）从大到小取前 N 张，与命令所贡牌逐一比对。</li>
 * </ul>
 */
public final class TributeCommand extends AbstractGameCommand {

    private final List<Card> cards;

    public TributeCommand(long roomId, long playerId, List<Card> cards) {
        super(roomId, playerId);
        this.cards = List.copyOf(cards);
    }

    @Override
    public CommandResult execute(GameRoom room) {
        if (room.phase() != GamePhase.TRIBUTE) {
            return CommandResult.fail("当前阶段不能进贡: " + room.phase());
        }
        Player player = Players.find(room, playerId());
        if (player == null) {
            return CommandResult.fail("玩家不在本房间: " + playerId());
        }
        TributeObligation obligation = room.pendingTributes().get(player.seat());
        if (obligation == null) {
            return CommandResult.fail("该玩家无进贡义务（无义务或已交过）");
        }
        if (cards.size() != obligation.bloodCount()) {
            return CommandResult.fail("进贡张数不对：应交 " + obligation.bloodCount() + " 张，实交 " + cards.size());
        }
        if (!com.gunzihall.domain.card.Cards.containsCopies(player.hand(), cards)) {
            return CommandResult.fail("所贡之牌不在手牌中（按副本数校验）");
        }
        TrumpContext trump = room.trump().orElse(null);
        if (trump == null) {
            return CommandResult.fail("主牌上下文缺失，无法校验进贡牌");
        }

        // ---- 校验"最大的牌（分牌除外，级牌除外身份优先）" ----
        CardComparator cmp = new CardComparator(trump);
        List<Card> candidates = new ArrayList<>(player.hand());
        candidates.removeIf(c -> c.points() > 0
                && (c.isJoker() || c.rank() != trump.level()));
        candidates.sort(cmp.reversed());
        int n = Math.min(obligation.bloodCount(), candidates.size());
        List<Card> expected = candidates.subList(0, n);

        List<Card> actual = new ArrayList<>(cards);
        actual.sort(cmp.reversed());
        if (actual.size() != expected.size() || !actual.equals(expected)) {
            return CommandResult.fail("进贡必须是手中最大的非分牌（打" + trump.level()
                    + "时" + trump.level() + "要贡），期望: " + expected + "，实交: " + actual);
        }

        // ---- 执行：贡牌离手，交到收贡人手中 ----
        com.gunzihall.domain.card.Cards.removeCopies(player.hand(), cards);
        Player receiver = room.playerAt(obligation.receiver());
        receiver.hand().addAll(cards);
        room.recordTribute(player.seat(), cards);
        return CommandResult.ok();
    }

    @Override
    public CommandResult rollback(GameRoom room) {
        return CommandResult.fail("进贡命令不支持回滚（防作弊关键路径）");
    }

}
