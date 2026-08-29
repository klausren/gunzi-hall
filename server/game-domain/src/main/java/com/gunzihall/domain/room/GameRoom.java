package com.gunzihall.domain.room;

import com.gunzihall.domain.action.CommandResult;
import com.gunzihall.domain.action.GameCommand;
import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.play.FollowRule;
import com.gunzihall.domain.play.Trick;
import com.gunzihall.domain.player.Player;
import com.gunzihall.domain.player.Seat;
import com.gunzihall.domain.player.Team;
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

    // ---- Sprint 2：出牌阶段状态 ----
    /** 跟牌模式（Q3 已拍板：默认活棒，房主建房时可切换） */
    private FollowRule followRule = FollowRule.defaultValue();
    /** 当前圈（null = 下一手是首出） */
    private Trick currentTrick;
    /** 当前轮到谁出牌（PLAYING 阶段必填，由扣底结束/上一圈赢家设定） */
    private Seat turnSeat;
    /** 两队出牌阶段已收走的分牌 */
    private final Map<Team, Integer> trickPoints = new EnumMap<>(Team.class);
    /** 最后一圈赢家所在队伍（结算抠底/保底用） */
    private Team lastTrickWinnerTeam;

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

    // ---- Sprint 2：出牌阶段状态访问/变更 ----

    /** 跟牌模式（活棒/死棒房间开关，Q3） */
    public FollowRule followRule() {
        return followRule;
    }

    public void setFollowRule(FollowRule followRule) {
        this.followRule = followRule;
    }

    /** 当前圈；null 表示下一手为首出 */
    public Optional<Trick> currentTrick() {
        return Optional.ofNullable(currentTrick);
    }

    public void setCurrentTrick(Trick trick) {
        this.currentTrick = trick;
    }

    public void clearCurrentTrick() {
        this.currentTrick = null;
    }

    /** 当前轮到的座位；未开始出牌为 empty */
    public Optional<Seat> turnSeat() {
        return Optional.ofNullable(turnSeat);
    }

    public void setTurnSeat(Seat seat) {
        this.turnSeat = seat;
    }

    /** 两队出牌阶段已收分（只读视图） */
    public Map<Team, Integer> trickPoints() {
        return Collections.unmodifiableMap(trickPoints);
    }

    public void addTrickPoints(Team team, int points) {
        trickPoints.merge(team, points, Integer::sum);
    }

    public void clearTrickPoints() {
        trickPoints.clear();
    }

    public Optional<Team> lastTrickWinnerTeam() {
        return Optional.ofNullable(lastTrickWinnerTeam);
    }

    public void setLastTrickWinnerTeam(Team team) {
        this.lastTrickWinnerTeam = team;
    }

    /** 全员手牌是否打空（PLAYING → SETTLING 的切换条件） */
    public boolean allHandsEmpty() {
        return !players.isEmpty()
                && players.values().stream().allMatch(p -> p.hand().isEmpty());
    }
}
