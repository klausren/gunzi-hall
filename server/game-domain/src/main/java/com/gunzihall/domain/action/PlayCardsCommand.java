package com.gunzihall.domain.action;

import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.player.Player;
import com.gunzihall.domain.room.GamePhase;
import com.gunzihall.domain.room.GameRoom;

import java.util.ArrayList;
import java.util.List;

/**
 * 出牌命令（Sprint 1 骨架版本）。
 * <p>当前只做基础校验（阶段 + 牌在手上 + 数量为 1/2/3 张）。
 * <p>Sprint 2 规则引擎将补充：牌型判定（单牌/棒子/滚子）、跟牌校验（活棒/死棒房间开关，
 * 手册 3.3）、轮次赢家判定与分牌收集（tricks 包）。
 */
public final class PlayCardsCommand extends AbstractGameCommand {

    private final List<Card> cards;
    private final List<Card> playedLog = new ArrayList<>();

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
        if (!player.hand().containsAll(cards)) {
            return CommandResult.fail("所出之牌不在手牌中");
        }
        // TODO Sprint 2：牌型同点同花校验、跟牌校验（活棒/死棒）、当前轮到该玩家出牌
        playedLog.addAll(cards);
        player.hand().removeAll(cards);
        return CommandResult.ok();
    }

    @Override
    public CommandResult rollback(GameRoom room) {
        Player player = findPlayer(room, playerId());
        if (player == null || playedLog.isEmpty()) {
            return CommandResult.fail("无可回滚内容");
        }
        player.hand().addAll(playedLog);
        playedLog.clear();
        return CommandResult.ok();
    }

    private static Player findPlayer(GameRoom room, long playerId) {
        return room.players().values().stream()
                .filter(p -> p.playerId() == playerId)
                .findFirst()
                .orElse(null);
    }
}
