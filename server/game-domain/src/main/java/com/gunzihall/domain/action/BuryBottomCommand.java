package com.gunzihall.domain.action;

import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.card.Joker;
import com.gunzihall.domain.player.Player;
import com.gunzihall.domain.player.Seat;
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
 * <p>扣底完成后：底牌含王则对所有人公开（手册 2.3.3）；若底牌不含分牌且非干锅，还要
 * 开放"他人捡牌扣王"窗口（手册 2.3.5，见 {@link PickBottomJokerCommand}），等三家
 * 依次表态完才进入 PLAYING；否则直接进 PLAYING，首出人 = 庄家（领出权）。
 *
 * <p><b>窗口期间不摊牌</b>：2.3.5 要求的是"扣王**时**底牌必须公开"，公开是押中的后果
 * 而不是开窗的前提。所以开窗只下发"轮到谁表态 / 最多能押几张"，新底牌依旧是机密，
 * 要等真有人扣了王才由 {@link PickBottomJokerCommand} 翻成公开。
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
        // 第一局无论底牌是什么都不判干锅，庄家可以正常替换底牌。
        boolean dryPot = !room.isFirstRound() && originalBottom.stream()
                .filter(c -> !c.isJoker() && c.rank() != 2 && c.rank() != trump.level())
                .noneMatch(c -> c.suit() == trump.trumpSuit());
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

        // ---- 扣完之后：底牌要不要公开、别家能不能接着扣王（手册 2.3.3 / 2.3.5 / 2.3.6 / 2.3.8）----
        boolean hasJoker = cards.stream().anyMatch(Card::isJoker);
        boolean hasPoints = cards.stream().anyMatch(c -> c.points() > 0);
        room.setBottomRevealed(hasJoker); // 2.3.3：扣王时底牌必须亮给所有人看
        if (hasJoker && !dryPot) {
            // 本局"有人扣王"这个**事实**（供结算后的面板回看）。与可见性字段分开记，
            // 而且必须排掉干锅：干锅是原样扣回，底牌里那几张王是**发牌发出来的**
            // （手册 2.3.7 专门为"干锅底牌王"立规：不算血、不追加升级），没人扣过它们。
            // 漏掉这层判断，干锅局就会被面板报成"本局扣王 是"。
            room.setJokerBuried(true);
        }

        if (!dryPot && !hasPoints && othersHoldJoker(room, bankerSeat)) {
            // 2.3.5：庄家扣完底牌后，其他玩家也可以在底牌中扣王 —— 依次询问三家
            // （庄家下家起，按出牌方向逆时针）。Q2 拍板走"依次询问"而非抢扣。
            // 2.3.6 / 2.3.8（Q5b 合并）：庄家底牌含分牌则谁都不能再扣（hasPoints 已挡住）；
            // 2.3.7：干锅不能扣王（dryPot 挡住）。
            //
            // 【为什么还要看"别人手里有没有王"】扣王是用**自己的王**去换底牌里的最小非分牌，
            // 三家手里一张王都没有时，窗口开了也必然全部走"过" —— 白白把牌局卡在 BURYING
            // 一轮询问。规则层面没有禁止提前收口，结果完全等价。
            //
            // 【窗口期间不摊牌】2.3.5 的原话是"**扣王时**底牌必须公开"—— 公开是**押中之后**
            // 的后果，不是开窗的前提。所以这里只开窗，不碰 bottomRevealed：
            // 底牌仍保持庄家扣出时那份机密（庄家自己扣了王的情形上面已经亮过，那是 2.3.3）。
            // 真有人扣了王，PickBottomJokerCommand 才把它翻成公开并一直公开到结算；
            // 三家全"过"则始终没露过面，收口时 refreshBottomReveal 会把标志校正回机密。
            //
            // 【不看牌也做得成决策】扣王 = 拿**自己的王**换回一张"最小的非分牌"，
            // 这笔交易值不值与底牌具体长什么样无关；能押几张由服务端下发的 pickMax 给出
            // （可捡的非分非王牌数）。规则对"押注前看不看得到底牌"没有任何要求，
            // 先摊一次等于白送对手一次情报。
            room.openBuryPickWindow(List.of(bankerSeat.next(), bankerSeat.next().next(),
                    bankerSeat.next().next().next()));
            return CommandResult.ok(); // 留在 BURYING，等三家依次表态
        }

        room.transitionTo(GamePhase.PLAYING);
        return CommandResult.ok();
    }

    @Override
    public CommandResult rollback(GameRoom room) {
        return CommandResult.fail("扣底命令不支持回滚（底牌涉及结算，防作弊关键路径）");
    }

    /**
     * 除庄家外的三家手牌里是否至少有一张王 —— 决定"他人捡牌扣王"窗口值不值得开。
     *
     * <p>不是规则要求，而是避免无意义交互：扣王是用自己的王换底牌里最小的非分牌，
     * 三家都没王时窗口必然全"过"，开了只会让牌局多停一轮、还白露一次底牌。
     */
    private static boolean othersHoldJoker(GameRoom room, Seat bankerSeat) {
        return room.players().values().stream()
                .filter(p -> p.seat() != bankerSeat)
                .anyMatch(p -> p.hand().stream().anyMatch(Card::isJoker));
    }
}
