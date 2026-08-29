package com.gunzihall.domain.room;

import com.gunzihall.domain.action.CommandResult;
import com.gunzihall.domain.action.GameCommand;
import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.player.Player;
import com.gunzihall.domain.player.Seat;
import com.gunzihall.domain.trump.TrumpContext;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 游戏房间（Sprint 1 骨架）：座位、阶段、主牌上下文、命令历史的聚合根。
 * <p>所有状态变更必须通过 {@link #apply(GameCommand)} 走命令模式，保证可回放可审计。
 * 具体阶段逻辑（亮王/进贡/扣底/出牌校验）在 Sprint 2 规则引擎中逐步充实。
 */
public final class GameRoom {

    private final long roomId;
    private final Map<Seat, Player> players = new EnumMap<>(Seat.class);
    private final Map<Seat, List<Card>> hands = new EnumMap<>(Seat.class);
    private List<Card> bottomCards = List.of();

    private GamePhase phase = GamePhase.WAITING;
    private TrumpContext trump;
    private final com.gunzihall.domain.action.CommandHistory history =
            new com.gunzihall.domain.action.CommandHistory();

    public GameRoom(long roomId) {
        this.roomId = roomId;
    }

    /** 玩家入座（仅 WAITING 阶段，且座位未被占） */
    public void sitDown(Player player) {
        if (phase != GamePhase.WAITING) {
            throw new IllegalStateException("只有 WAITING 阶段可以入座，当前: " + phase);
        }
        Seat seat = player.seat();
        if (players.containsKey(seat)) {
            throw new IllegalStateException("座位已被占用: " + seat);
        }
        players.put(seat, player);
    }

    public boolean isFull() {
        return players.size() == 4;
    }

    /**
     * 执行命令的唯一入口：校验房间/防重放 → 执行 → 留痕。
     */
    public CommandResult apply(GameCommand command) {
        if (command.roomId() != roomId) {
            return CommandResult.fail("命令不属于本房间: roomId=" + command.roomId());
        }
        if (history.isReplay(command.commandId())) {
            return CommandResult.fail("命令重放被拒绝（UUID 重复）");
        }
        CommandResult result = command.execute(this);
        history.record(command); // 成功与失败都留痕，失败原因写入 t_game_action
        return result;
    }

    // ---- 内部状态访问/变更（包内及命令层使用） ----

    public long roomId() {
        return roomId;
    }

    public GamePhase phase() {
        return phase;
    }

    public void transitionTo(GamePhase target) {
        if (!phase.canTransitionTo(target)) {
            throw new IllegalStateException("非法阶段跳转: " + phase + " -> " + target);
        }
        this.phase = target;
    }

    public Optional<TrumpContext> trump() {
        return Optional.ofNullable(trump);
    }

    public void setTrump(TrumpContext trump) {
        this.trump = trump;
    }

    public Map<Seat, Player> players() {
        return Collections.unmodifiableMap(players);
    }

    public Player playerAt(Seat seat) {
        Player p = players.get(seat);
        if (p == null) {
            throw new IllegalArgumentException("座位空缺: " + seat);
        }
        return p;
    }

    public Map<Seat, List<Card>> hands() {
        return Collections.unmodifiableMap(hands);
    }

    public List<Card> handOf(Seat seat) {
        return hands.getOrDefault(seat, List.of());
    }

    public void setHands(Map<Seat, List<Card>> hands) {
        this.hands.clear();
        this.hands.putAll(hands);
    }

    public List<Card> bottomCards() {
        return bottomCards;
    }

    public void setBottomCards(List<Card> bottomCards) {
        this.bottomCards = List.copyOf(bottomCards);
    }

    public com.gunzihall.domain.action.CommandHistory history() {
        return history;
    }
}
