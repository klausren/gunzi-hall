package com.gunzihall.domain.action;

import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.card.Cards;
import com.gunzihall.domain.player.Player;
import com.gunzihall.domain.player.Seat;
import com.gunzihall.domain.room.BottomPickRule;
import com.gunzihall.domain.room.GamePhase;
import com.gunzihall.domain.room.GameRoom;

import java.util.ArrayList;
import java.util.List;

/**
 * 他人捡牌扣王（手册 2.3 第 5 条）。
 *
 * <p>庄家扣完底牌后，其他玩家也可以在底牌中扣王：把自己的王扣进底牌，再从底牌里
 * <strong>捡走同样张数的最小非分牌</strong>——底牌张数因此守恒（进 K 张王、出 K 张小牌）。
 * 动作的实质是"押注本局方向"：底牌里的王会按 4.3 / 5.3 折算成进贡血，押中了
 * （本方保底成功 / 完美抠底）就多吃贡，押错则把自己的强牌白白送进底牌。
 *
 * <p>服务端权威校验：
 * <ul>
 *   <li><strong>轮流</strong>：只有 {@link GameRoom#buryPickSeat()} 指向的那一家能提交，
 *       顺序由扣底命令开放窗口时排定（庄家下家起，逆时针依次问三家）；</li>
 *   <li><strong>干锅禁扣</strong>（2.3.7）；</li>
 *   <li><strong>庄家底牌含分牌禁扣</strong>（2.3.6/2.3.8，Q5b 合并为一条）；</li>
 *   <li><strong>只能扣自己的王</strong>，且张数不能超过底牌中可捡的非分牌数
 *       （否则"扣几张捡几张"落不了地）。</li>
 * </ul>
 *
 * <p>【空列表 = 过】本轮不扣王，直接让给下一家。这样"扣王 / 过"共用一条命令，
 * 客户端不必为放弃单开一个 op，服务端也不必猜"没发命令"到底是没轮到还是掉线
 * （掉线走超时托管，同样落在入口 {@code RoomActor.doPickJoker}）。
 */
public final class PickBottomJokerCommand extends AbstractGameCommand {

    /** 要扣进底牌的牌（必须全是自己手牌中的王）；空列表表示本轮放弃扣王 */
    private final List<Card> jokers;

    public PickBottomJokerCommand(long roomId, long playerId, List<Card> jokers) {
        super(roomId, playerId);
        this.jokers = List.copyOf(jokers);
    }

    public List<Card> jokers() {
        return jokers;
    }

    @Override
    public CommandResult execute(GameRoom room) {
        if (room.phase() != GamePhase.BURYING) {
            return CommandResult.fail("当前阶段不能扣王: " + room.phase());
        }
        if (!room.isBuryPickWindowOpen()) {
            return CommandResult.fail("现在没有他人扣王的窗口（庄家还没扣完底，或本局不允许扣王）");
        }
        Player me = Players.find(room, playerId());
        if (me == null) {
            return CommandResult.fail("玩家不在本房间: " + playerId());
        }
        Seat current = room.buryPickSeat();
        if (me.seat() != current) {
            return CommandResult.fail("还没轮到你扣王，当前轮到 " + current);
        }

        // ---- 过：本轮不扣王，交给下一家 ----
        if (jokers.isEmpty()) {
            room.advanceBuryPick();
            return CommandResult.ok();
        }

        // ---- 准入（窗口开放时已经判过，这里再兜一层，防止将来有人绕过开窗逻辑） ----
        if (room.isDryPot()) {
            return CommandResult.fail("干锅局底牌不能替换，也不能扣王（手册 2.3.7）");
        }
        if (!room.bottomPickAllowed()) {
            return CommandResult.fail(
                    "庄家底牌含分牌，其他玩家不能再在底牌中扣王（手册 2.3.6/2.3.8）");
        }

        // ---- 只能扣自己的王 ----
        if (jokers.stream().anyMatch(c -> !c.isJoker())) {
            return CommandResult.fail("底牌里只能扣王（大王 / 小王）");
        }
        if (!Cards.containsCopies(me.hand(), jokers)) {
            return CommandResult.fail("所扣之王不在自己手牌中（或副本数不足）");
        }

        // ---- 扣几张就得捡几张：底牌里得有那么多非分牌可捡 ----
        int pickable = room.bottomPickableCount();
        if (jokers.size() > pickable) {
            return CommandResult.fail("底牌中可捡的非分牌只有 " + pickable + " 张，最多扣 "
                    + pickable + " 张王（扣几张捡几张，不能捡分牌）");
        }

        // ---- 执行：此消彼长，底牌张数守恒 ----
        List<Card> bottom = new ArrayList<>(room.bottomCards());
        Cards.removeCopies(me.hand(), jokers);
        bottom.addAll(jokers);
        List<Card> picked = BottomPickRule.smallestPickable(bottom, room.trump().orElse(null),
                jokers.size());
        Cards.removeCopies(bottom, picked);
        me.hand().addAll(picked);
        room.setBottomCards(bottom);

        // 手册 2.3.5「扣王时底牌必须公开」——从此这张底牌对所有人可见
        room.setBottomRevealed(true);
        room.advanceBuryPick();
        return CommandResult.ok();
    }

    @Override
    public CommandResult rollback(GameRoom room) {
        return CommandResult.fail("扣王命令不支持回滚（底牌涉及结算，防作弊关键路径）");
    }
}
