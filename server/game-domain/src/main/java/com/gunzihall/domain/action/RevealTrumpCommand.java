package com.gunzihall.domain.action;

import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.card.Joker;
import com.gunzihall.domain.card.Suit;
import com.gunzihall.domain.player.Player;
import com.gunzihall.domain.room.GamePhase;
import com.gunzihall.domain.room.GameRoom;
import com.gunzihall.domain.trump.TrumpContext;
import com.gunzihall.domain.trump.TrumpReveal;

import java.util.List;
import java.util.Optional;

/**
 * 亮王 / 反主命令（手册 2.2，Q2/Q2b/Q2c 已拍板）。
 *
 * <p>仅 BIDDING 阶段可执行（抢亮窗口内可多次反主；进贡/扣底开始后禁止反主，
 * 窗口结束由 {@link ConfirmTrumpCommand} 推进阶段）。
 *
 * <ul>
 *   <li><strong>第一局</strong>：抢亮 1 张大王即定，无人能反。亮牌人为庄家；
 *       主花色 = 亮牌人亮牌后摸到的第一张花色牌的花色（客户端流程中摸定后随命令上送
 *       {@code claimSuit}）。</li>
 *   <li><strong>第二局起</strong>：亮/反级牌（1..3 张同花色，打几亮几）；
 *       或 3 张小王 / 3 张大王叫任意花色。反主合法性由 {@link TrumpReveal#canBeOverriddenBy} 校验。
 *       第二局起亮主人不改变庄家（庄家由上局结算决定）。</li>
 * </ul>
 *
 * <p>亮出的牌不离手（亮牌是展示，不是打出）。
 */
public final class RevealTrumpCommand extends AbstractGameCommand {

    private final List<Card> cards;
    /** 主花色（第一局 = 摸到的第一张花色牌；王声明 = 所叫花色；级牌声明必须等于级牌花色） */
    private final Suit claimSuit;

    public RevealTrumpCommand(long roomId, long playerId, List<Card> cards, Suit claimSuit) {
        super(roomId, playerId);
        this.cards = List.copyOf(cards);
        this.claimSuit = claimSuit;
    }

    public List<Card> cards() {
        return cards;
    }

    public Suit claimSuit() {
        return claimSuit;
    }

    @Override
    public CommandResult execute(GameRoom room) {
        GamePhase phase = room.phase();
        // 亮主窗口 = 发牌期间 + 发牌后。
        // 【为什么含 DEALING】手册 2.2 的"抢亮"本就发生在发牌过程中——玩家摸到大王
        // （第一局）或凑齐级牌/三王时当场就亮，而不是等 156 张全部发完才动手。
        if (phase != GamePhase.DEALING && phase != GamePhase.BIDDING) {
            return CommandResult.fail("亮主/反主只能在发牌中或发牌后的亮主窗口内进行，当前阶段: " + phase);
        }
        Player player = Players.find(room, playerId());
        if (player == null) {
            return CommandResult.fail("玩家不在本房间: " + playerId());
        }
        if (cards.isEmpty() || cards.size() > 3) {
            return CommandResult.fail("亮王牌数必须 1..3 张");
        }
        if (!com.gunzihall.domain.card.Cards.containsCopies(player.hand(), cards)) {
            return CommandResult.fail("所亮之牌不在手牌中（按副本数校验：手里实际张数不足）");
        }

        TrumpReveal candidate;
        if (room.isFirstRound()) {
            // ---- 第一局：抢亮 1 张大王（Q2：无人能反） ----
            if (cards.size() != 1 || !cards.get(0).isJoker() || cards.get(0).joker() != Joker.BIG) {
                return CommandResult.fail("第一局必须抢亮 1 张大王（无人能反，手册 2.2）");
            }
            if (room.revealState().isPresent()) {
                return CommandResult.fail("第一局大王已亮，不可再反");
            }
            candidate = TrumpReveal.firstRoundJoker(player.seat(), claimSuit);
        } else {
            // ---- 第二局起：级牌 / 三小王 / 三大王 ----
            candidate = parseNonFirstRound(room, player.seat());
            if (candidate == null) {
                return CommandResult.fail("亮牌不合法：第二局起须亮当前级数牌（打" + room.currentLevel()
                        + "亮" + room.currentLevel() + "，1..3 张同花色），或 3 张小王/3 张大王叫任意花色");
            }
            Optional<TrumpReveal> current = room.revealState();
            if (current.isPresent() && !current.get().canBeOverriddenBy(candidate)) {
                return CommandResult.fail("反主失败：当前声明 " + current.get() + " 不能被该声明推翻（Q2b/Q2c）");
            }
        }

        // 主花色已知才建主牌上下文：第一局先亮大王时它还没定（等摸牌），
        // 此时建 TrumpContext(level, null) 会让后续排序/比较拿到空花色。
        if (claimSuit != null) {
            room.setTrump(new TrumpContext(room.currentLevel(), claimSuit));
        }
        room.setRevealState(candidate);
        if (room.isFirstRound()) {
            room.setBankerSeat(player.seat());
        }
        return CommandResult.ok();
    }

    /** 解析第二局起的声明；不合法返回 null */
    private TrumpReveal parseNonFirstRound(GameRoom room, com.gunzihall.domain.player.Seat seat) {
        int level = room.currentLevel();
        boolean allJokers = cards.stream().allMatch(Card::isJoker);
        if (allJokers) {
            if (cards.size() != 3) {
                return null;
            }
            boolean allSmall = cards.stream().allMatch(c -> c.joker() == Joker.SMALL);
            boolean allBig = cards.stream().allMatch(c -> c.joker() == Joker.BIG);
            if (allSmall) {
                return TrumpReveal.tripleSmallJoker(seat, claimSuit);
            }
            if (allBig) {
                return TrumpReveal.tripleBigJoker(seat, claimSuit);
            }
            return null; // 大小王混合
        }
        // 级牌声明：全部为当前级数牌且同花色
        boolean allLevelCards = cards.stream()
                .allMatch(c -> !c.isJoker() && c.rank() == level);
        if (allLevelCards) {
            Suit suit = cards.get(0).suit();
            boolean sameSuit = cards.stream().allMatch(c -> c.suit() == suit);
            if (sameSuit && suit == claimSuit) {
                return TrumpReveal.levelCards(seat, suit, cards.size());
            }
        }
        return null;
    }

    @Override
    public CommandResult rollback(GameRoom room) {
        // 亮主属于展示型命令且覆盖语义复杂（后续反主会覆盖状态），不提供回滚
        return CommandResult.fail("亮主命令不支持回滚");
    }

}
