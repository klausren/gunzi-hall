package com.gunzihall.domain.action;

import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.play.Combo;
import com.gunzihall.domain.play.FollowValidator;
import com.gunzihall.domain.play.Trick;
import com.gunzihall.domain.player.Player;
import com.gunzihall.domain.room.GamePhase;
import com.gunzihall.domain.room.GameRoom;
import com.gunzihall.domain.trump.TrumpContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 出牌命令（Sprint 2 规则引擎版）。
 *
 * <p>校验链：阶段 → 轮到该玩家 → 牌在手上 → 牌型/跟牌校验 → 落子。
 * <ul>
 *   <li>首出：必须是合法牌型（单牌/棒子/滚子，无顺子拖拉机甩牌，手册 3.1）；</li>
 *   <li>跟牌：按房间活棒/死棒开关校验（手册 3.2/3.3，{@link FollowValidator}）；</li>
 *   <li>一圈打满 4 手：判定赢家 → 赢家队伍收分牌 → 下一圈由赢家先出（手册 3.2）；</li>
 *   <li>全员手牌打空 → PLAYING → SETTLING。</li>
 * </ul>
 */
public final class PlayCardsCommand extends AbstractGameCommand {

    private final List<Card> cards;
    private final List<Card> playedLog = new ArrayList<>();
    /** 本命令是否开新圈（回滚时需要撤销） */
    private boolean startedTrick;

    public PlayCardsCommand(long roomId, long playerId, List<Card> cards) {
        super(roomId, playerId);
        this.cards = List.copyOf(cards);
    }

    public List<Card> cards() {
        return cards;
    }

    @Override
    public CommandResult execute(GameRoom room) {
        if (room.phase() != GamePhase.PLAYING) {
            return CommandResult.fail("当前阶段不能出牌: " + room.phase());
        }
        if (cards.isEmpty()) {
            return CommandResult.fail("出牌不能为空");
        }
        if (cards.size() > 3) {
            return CommandResult.fail("打滚子只有单牌/棒子(2张)/滚子(3张)三种牌型，最多 3 张");
        }
        Player player = findPlayer(room, playerId());
        if (player == null) {
            return CommandResult.fail("玩家不在本房间: " + playerId());
        }
        if (room.turnSeat().filter(s -> s == player.seat()).isEmpty()) {
            return CommandResult.fail("还没轮到该玩家出牌，当前轮到: "
                    + room.turnSeat().map(Object::toString).orElse("未定"));
        }
        if (!com.gunzihall.domain.card.Cards.containsCopies(player.hand(), cards)) {
            return CommandResult.fail("所出之牌不在手牌中（或副本数不足）");
        }
        TrumpContext trump = room.trump().orElse(null);
        if (trump == null) {
            return CommandResult.fail("主牌上下文缺失，无法出牌（需先完成定主）");
        }

        Trick trick = room.currentTrick().orElse(null);
        if (trick == null) {
            // ---- 首出：必须构成合法牌型（构造器已记录首出这一手） ----
            Optional<Combo> combo = Combo.parse(cards, trump);
            if (combo.isEmpty()) {
                return CommandResult.fail("首出牌型不合法：只有单牌/棒子(两张同点同花)/滚子(三张同点同花)，无顺子、拖拉机、甩牌");
            }
            trick = new Trick(player.seat(), combo.get(), trump);
            startedTrick = true;
            room.setCurrentTrick(trick);
        } else {
            // ---- 跟牌：活棒/死棒校验 ----
            Optional<String> violation = FollowValidator.validate(
                    room.followRule(), trick.lead(), player.hand(), cards, trump);
            if (violation.isPresent()) {
                return CommandResult.fail(violation.get());
            }
            trick.play(player.seat(), cards);
        }

        playedLog.addAll(cards);
        com.gunzihall.domain.card.Cards.removeCopies(player.hand(), cards);

        if (trick.isComplete()) {
            var winner = trick.winnerSeat();
            room.addTrickPoints(winner.team(), trick.points());
            room.setLastTrickWinnerTeam(winner.team());
            room.clearCurrentTrick();
            room.setTurnSeat(winner);
            if (room.allHandsEmpty()) {
                room.transitionTo(GamePhase.SETTLING);
            }
        } else {
            room.setTurnSeat(player.seat().next());
        }
        return CommandResult.ok();
    }

    @Override
    public CommandResult rollback(GameRoom room) {
        Player player = findPlayer(room, playerId());
        if (player == null || playedLog.isEmpty()) {
            return CommandResult.fail("无可回滚内容");
        }
        // 撤销对圈状态的影响（尽力而为，供本地调试使用）
        room.currentTrick().ifPresent(trick -> trick.removeLastPlay(player.seat()));
        if (startedTrick && room.currentTrick().isPresent()) {
            room.clearCurrentTrick();
        }
        room.setTurnSeat(player.seat());
        player.hand().addAll(playedLog);
        playedLog.clear();
        startedTrick = false;
        return CommandResult.ok();
    }

    private static Player findPlayer(GameRoom room, long playerId) {
        return room.players().values().stream()
                .filter(p -> p.playerId() == playerId)
                .findFirst()
                .orElse(null);
    }
}
