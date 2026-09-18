package com.gunzihall.domain.room;

import com.gunzihall.domain.action.CommandResult;
import com.gunzihall.domain.action.GameCommand;
import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.card.Suit;
import com.gunzihall.domain.play.FollowRule;
import com.gunzihall.domain.play.RoundSettlement;
import com.gunzihall.domain.play.Trick;
import com.gunzihall.domain.player.Player;
import com.gunzihall.domain.player.Seat;
import com.gunzihall.domain.player.Team;
import com.gunzihall.domain.tribute.TributeCalculator;
import com.gunzihall.domain.tribute.TributeObligation;
import com.gunzihall.domain.trump.TrumpContext;
import com.gunzihall.domain.trump.TrumpReveal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
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

    // ---- 逐张发牌状态（手册 1 节；配合 SetupDealCommand / DealNextCardCommand） ----
    /** 剩余未发牌堆；空 = 尚未洗牌或已发完（发到 0 时余下的即底牌） */
    private List<Card> dealPile = List.of();
    /** 下一张发给谁（按座位序轮转） */
    private Seat dealTurn = Seat.NORTH;

    private GamePhase phase = GamePhase.WAITING;
    private TrumpContext trump;
    private final com.gunzihall.domain.action.CommandHistory history =
            new com.gunzihall.domain.action.CommandHistory();

    // ---- Sprint 2：出牌阶段状态 ----
    /** 跟牌模式（Q3 已拍板：默认活棒，房主建房时可切换） */
    private FollowRule followRule = FollowRule.defaultValue();
    /** 当前圈（null = 下一手是首出） */
    private Trick currentTrick;
    /**
     * 上一圈刚打完时暂存的"已完成圈"副本（4 手都在）。
     * 出完即清 currentTrick 会丢掉第 4 张，客户端永远看不到最后一张、整墩一闪而过；
     * 所以把已完成的圈单独留一份，随快照下发给客户端看清楚，下一圈首出时清空。
     */
    private Trick lastCompletedTrick;
    /** 当前轮到谁出牌（PLAYING 阶段必填，由扣底结束/上一圈赢家设定） */
    private Seat turnSeat;
    /** 两队出牌阶段已收走的分牌 */
    private final Map<Team, Integer> trickPoints = new EnumMap<>(Team.class);
    /**
     * 两队已收走的**分牌明细**（只存 5/10/K，分值 > 0 的牌），与 {@link #trickPoints} 同步累加。
     *
     * <p>为什么要在服务端留一份：客户端「闲家得分」条要能展开看到"这些分具体是哪几张牌"，
     * 而分牌是按圈收走的（赢家队伍一次收走 4 张），客户端只看到自己的手牌与桌面的出牌，
     * 无法自行还原谁收了哪些牌 —— 只能由服务端随快照下发。
     *
     * <p>只存分牌而不是整圈 4 张：一局下来整圈牌可达 150+ 张，随快照全量下发既浪费带宽
     * 也没有展示价值（玩家关心的是"分在我手上还是对手手上"）。
     */
    private final Map<Team, List<Card>> takenPointCards = new EnumMap<>(Team.class);
    /** 最后一圈赢家所在队伍（结算抠底/保底用） */
    private Team lastTrickWinnerTeam;

    // ---- Sprint 3：多局推进状态（手册 2.2/2.3/5 节） ----
    /** 当前轮次所打级数（3..10，出锅后回到 3） */
    private int currentLevel = 3;
    /** 是否本轮第一局（第一局抢亮大王定庄 / 翻底牌定庄，手册 2.2） */
    private boolean firstRound = true;
    /** 庄家座位（第一局由亮主产生，之后由上局结算决定） */
    private Seat bankerSeat;
    /** 当前最高亮主声明（抢亮/反主，手册 2.2） */
    private TrumpReveal revealState;
    /** 庄家是否已把底牌收入手牌（BURYING 阶段一次性动作） */
    private boolean bottomTaken;
    /** 本局是否干锅（底牌无主花色普通牌；干锅局底牌王不算血不追加升级，手册 2.3.7） */
    private boolean dryPot;
    /**
     * 底牌是否已对所有人公开（手册 2.3.3 / 2.3.5：扣王时底牌必须亮给所有人看）。
     *
     * <p>与"进扣底阶段就先摊开原底牌"（BURYING 且未收底）不是同一件事：那是**扣底前**
     * 的原底牌，本字段管的是**扣回去之后**的底牌要不要公开。
     */
    private boolean bottomRevealed;
    /**
     * 本局是否有**王被扣进底牌**（庄家扣王 2.3.3，或他人捡牌扣王 2.3.5）。
     *
     * <p>【为什么单独一个字段，不复用 {@link #bottomRevealed}】那个是**可见性**口径
     * （决定底牌摊不摊开），本字段是**事实**口径（本局到底有没有人扣王）。今天两者
     * 恰好同真同假（底牌含王 ⇔ 有人扣王），但语义不同：只要将来多一处调
     * {@code refreshBottomReveal()}（比如"某些局面强制摊底牌给判罚用"），两者立刻分叉。
     * 面板要报给玩家的是**事实**，所以由扣王的两条命令各自置位。
     *
     * <p>干锅局（2.3.7）永远是 false —— 底牌不能替换、也没人能扣，即便那 6 张里本来就
     * 带着王，那也是发牌发出来的，不算"扣"。
     */
    private boolean jokerBuried;
    /**
     * 他人捡牌扣王（手册 2.3 第 5 条）的待询问队列，队首即当前轮到的那一家。
     *
     * <p>【为什么是队列而不是"谁先点谁扣"】手册没写多人同时扣王的先后，本项目拍板为
     * **依次询问**（庄家下家起，按出牌方向逆时针），理由是服务端权威、动作序列可复现、
     * 超时托管与 bot 决策都有确定的落点；抢扣会出现"两家都以为自己扣到了"的争议。
     *
     * <p>队列为空 = 没有扣王窗口（正常局扣完底直接进 PLAYING）。
     */
    private final List<Seat> buryPickQueue = new ArrayList<>();
    /** 局数（从 1 计） */
    private int gameNumber = 1;
    /** 本局待执行的进贡义务（payer 座位 → 血数与收贡人；由上局结算产出） */
    private final Map<Seat, TributeObligation> pendingTributes = new EnumMap<>(Seat.class);
    /** 已收到的进贡牌（payer 座位 → 贡牌，等还贡） */
    private final Map<Seat, List<Card>> tributeReceived = new EnumMap<>(Seat.class);
    /** 已完成还贡的 payer 座位 */
    private final EnumSet<Seat> tributeReturned = EnumSet.noneOf(Seat.class);
    /**
     * 每笔进贡的**收贡人**（payer 座位 → 收贡人座位）。
     *
     * <p>进贡义务一旦付清就从 {@link #pendingTributes} 里摘掉了，而快照与界面面板还要
     * 讲清"谁贡给了谁" —— 所以在 {@link #recordTribute} 里顺手把收贡人留下来，
     * 不必让上层再去推（"进贡人 = 收贡人的上家"这条关系只写在规则手册里，
     * 不该在快照组装处再复算一遍）。
     */
    private final Map<Seat, Seat> tributeReceiverOf = new EnumMap<>(Seat.class);
    /** 已还贡的**牌面**（payer 座位 → 还贡牌）：面板要显示"还贡的牌都是哪些" */
    private final Map<Seat, List<Card>> tributeReturnedCards = new EnumMap<>(Seat.class);
    /** 最近一次一局结算结果（SettleRoundCommand 产出） */
    private RoundSettlement.Result lastSettlement;
    /** 最近一次进贡血数计算结果 */
    private TributeCalculator.TributeResult lastTributeResult;

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

    /**
     * 阶段切换底层方法。
     *
     * <p><b>注意</b>：要进入扣底阶段请调 {@link #enterBuryingPhase()}，不要直接切 BURYING ——
     * 干锅（非首局且底牌无主花色普通牌）必须先被拦下、跳过扣底流程。
     */
    public void transitionTo(GamePhase target) {
        if (!phase.canTransitionTo(target)) {
            throw new IllegalStateException("非法阶段跳转: " + phase + " -> " + target);
        }
        this.phase = target;
    }

    /**
     * 进入扣底阶段的唯一入口（干锅判定与阶段切换绑在一起）。
     *
     * <p>【为什么必须收口】干锅拦截原先散落在各命令里：只在亮主确认（ConfirmTrumpCommand）
     * 和还贡完（ReturnTributeCommand）两处判过，漏了第三条路——非首局"抽底牌定主"
     * （ResolveTrumpFromBottomCommand）之后直接进扣底。漏掉的后果不是"少一次提示"，
     * 而是真卡住：干锅局照样进了 BURYING，庄家是真人时怎么点"扣底"都被规则拒绝；
     * 庄家是 bot 时反复扣底失败会累加失败计数把房间判成 stuck，bot 驱动停摆，整局冻死。
     *
     * @return true = 判定为干锅，已原样扣回并直接进入 PLAYING（未进入 BURYING）
     */
    public boolean enterBuryingPhase() {
        if (autoBuryIfDryPot()) {
            return true;
        }
        transitionTo(GamePhase.BURYING);
        return false;
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

    // ---- 逐张发牌（手册 1 节） ----

    /** 洗好牌后把整副牌放进房间，并指定第一张发给谁（{@code SetupDealCommand} 调用） */
    public void prepareDealPile(List<Card> pile, Seat first) {
        this.dealPile = new ArrayList<>(pile);
        this.dealTurn = first != null ? first : Seat.NORTH;
    }

    /** 剩余未发张数 */
    public int dealRemaining() {
        return dealPile.size();
    }

    /** 下一张发给谁 */
    public Seat dealTurn() {
        return dealTurn;
    }

    /**
     * 发一张给当前轮到的座位，发完轮转。
     *
     * <p>同时写入 {@code room.hands} 与 {@code player.hand()}，与
     * {@code ShuffleAndDealCommand} 的双写保持一致——两处状态若不同步，
     * 快照（读 player.hand）与规则判定（读 hands）就会打架。
     */
    public Card dealOneToNext() {
        if (dealPile.isEmpty()) {
            throw new IllegalStateException("牌堆已空，不能再发牌");
        }
        Card card = dealPile.remove(dealPile.size() - 1);
        Seat seat = dealTurn;
        dealTurn = dealTurn.next();
        hands.computeIfAbsent(seat, k -> new ArrayList<>()).add(card);
        playerAt(seat).hand().add(card);
        return card;
    }

    /** 牌堆里剩下的牌（发满 156 张后剩下的 6 张即底牌） */
    public List<Card> remainingPile() {
        return List.copyOf(dealPile);
    }

    /** 清空牌堆（底牌已取走） */
    public void clearDealPile() {
        this.dealPile = List.of();
    }

    // ---- 第一局"摸牌定主"（手册 2.2） ----

    /**
     * 是否处于"已亮大王、主花色待摸"状态。
     *
     * <p>第一局抢亮 1 张大王即定庄，但主花色要等亮牌人**随后摸到的第一张花色牌**
     * ——逐张发牌下亮牌那一刻往往还没摸到它，所以允许主花色暂时为空。
     */
    public boolean pendingFirstRoundSuit() {
        return revealState != null
                && revealState.kind() == TrumpReveal.Kind.FIRST_ROUND_JOKER
                && revealState.suit() == null;
    }

    /**
     * 结算"待摸定主"：若这张牌正是亮牌人摸到的、且是花色牌（非王），则定主花色。
     *
     * @param drawnSeat 这张牌发给了谁
     * @param drawn     刚发出的牌
     * @return 本次是否定下了主花色
     */
    public boolean settlePendingSuit(Seat drawnSeat, Card drawn) {
        if (!pendingFirstRoundSuit()) {
            return false;
        }
        if (revealState.seat() != drawnSeat || drawn == null || drawn.isJoker()) {
            return false;
        }
        Suit suit = drawn.suit();
        revealState = TrumpReveal.firstRoundJoker(revealState.seat(), suit);
        trump = new TrumpContext(currentLevel, suit);
        return true;
    }

    /**
     * 兜底定主：亮大王后始终没摸到花色牌时（例如牌已发完才点大王）用。
     * 依次从底牌、亮牌人手里取第一张花色牌；全都没有（极端：清一色王）返回 false。
     */
    public boolean forceResolvePendingSuit() {
        if (!pendingFirstRoundSuit()) {
            return false;
        }
        Seat owner = revealState.seat();
        for (List<Card> pool : List.of(bottomCards, handOf(owner))) {
            for (Card c : pool) {
                if (!c.isJoker()) {
                    revealState = TrumpReveal.firstRoundJoker(owner, c.suit());
                    trump = new TrumpContext(currentLevel, c.suit());
                    return true;
                }
            }
        }
        return false;
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

    /** 暂存刚打完的已完成圈（供快照下发，让客户端看清本轮四张出牌）；下一圈首出时清空 */
    public void setLastCompletedTrick(Trick trick) {
        this.lastCompletedTrick = trick;
    }

    /** 取上一圈已完成圈（若存在）；下一圈首出时清空 */
    public Optional<Trick> lastCompletedTrick() {
        return Optional.ofNullable(lastCompletedTrick);
    }

    public void clearLastCompletedTrick() {
        this.lastCompletedTrick = null;
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

    /**
     * 一队收走一圈时的分牌明细登记（与 {@link #addTrickPoints} 成对调用）。
     * 只留分值 &gt; 0 的牌（5/10/K），顺序即收牌顺序（按圈）。
     */
    public void addTakenPointCards(Team team, List<Card> cards) {
        List<Card> bucket = takenPointCards.computeIfAbsent(team, k -> new ArrayList<>());
        for (Card c : cards) {
            if (c.points() > 0) {
                bucket.add(c);
            }
        }
    }

    /** 两队已收走的分牌明细（只读视图，未收分的队伍可能没有 key） */
    public Map<Team, List<Card>> takenPointCards() {
        Map<Team, List<Card>> view = new EnumMap<>(Team.class);
        takenPointCards.forEach((k, v) -> view.put(k, List.copyOf(v)));
        return Collections.unmodifiableMap(view);
    }

    public void clearTrickPoints() {
        trickPoints.clear();
        takenPointCards.clear();
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

    // ---- Sprint 3：多局推进状态访问/变更 ----

    public int currentLevel() {
        return currentLevel;
    }

    public void setCurrentLevel(int currentLevel) {
        this.currentLevel = currentLevel;
    }

    public boolean isFirstRound() {
        return firstRound;
    }

    public void setFirstRound(boolean firstRound) {
        this.firstRound = firstRound;
    }

    public Optional<Seat> bankerSeat() {
        return Optional.ofNullable(bankerSeat);
    }

    public void setBankerSeat(Seat bankerSeat) {
        this.bankerSeat = bankerSeat;
    }

    public Optional<TrumpReveal> revealState() {
        return Optional.ofNullable(revealState);
    }

    public void setRevealState(TrumpReveal revealState) {
        this.revealState = revealState;
    }

    public boolean isBottomTaken() {
        return bottomTaken;
    }

    public void setBottomTaken(boolean bottomTaken) {
        this.bottomTaken = bottomTaken;
    }

    public boolean isDryPot() {
        return dryPot;
    }

    public void setDryPot(boolean dryPot) {
        this.dryPot = dryPot;
    }

    // ---- 底牌公开 / 他人捡牌扣王（手册 2.3.3 ~ 2.3.8） ----

    public boolean isBottomRevealed() {
        return bottomRevealed;
    }

    public void setBottomRevealed(boolean bottomRevealed) {
        this.bottomRevealed = bottomRevealed;
    }

    /** 本局是否有人扣王（庄家 2.3.3 / 他人 2.3.5）；快照 {@code jokerBuried} 的唯一来源 */
    public boolean isJokerBuried() {
        return jokerBuried;
    }

    public void setJokerBuried(boolean jokerBuried) {
        this.jokerBuried = jokerBuried;
    }

    /**
     * 底牌是否允许"他人捡牌扣王"（手册 2.3.5 的准入条件）。
     *
     * <p>三条否决：干锅（2.3.7「干锅时底牌不能替换，也不能在底牌中扣王」）、
     * 庄家尚未收底（还没轮到别人），以及**庄家扣的底牌含分牌**（2.3.6/2.3.8，
     * Q5b 合并为一条：含分牌——无论是否混有王——其他玩家都不能再在底牌中扣王）。
     * 最后这条不是洁癖：扣王要求"从底牌捡最小的牌、不能捡分牌"，底牌里一旦有分牌，
     * 就可能出现捡到分牌的废操作，规则索性整条禁掉。
     */
    public boolean bottomPickAllowed() {
        if (dryPot || !bottomTaken) {
            return false;
        }
        return bottomCards.stream().noneMatch(c -> c.points() > 0);
    }

    /** 底牌中"可被捡走"的牌数（既不捡王也不捡分牌）—— 决定他人最多能扣几张 */
    public int bottomPickableCount() {
        return (int) bottomCards.stream().filter(c -> !c.isJoker() && c.points() == 0).count();
    }

    /** 开放他人捡牌扣王窗口（按给定顺序依次询问）；传空表示不开放 */
    public void openBuryPickWindow(List<Seat> seats) {
        buryPickQueue.clear();
        buryPickQueue.addAll(seats);
    }

    /** 当前轮到谁扣王；无窗口返回 null */
    public Seat buryPickSeat() {
        return buryPickQueue.isEmpty() ? null : buryPickQueue.get(0);
    }

    public boolean isBuryPickWindowOpen() {
        return !buryPickQueue.isEmpty();
    }

    /**
     * 当前这一家表完态（扣王或过）后推进到下一家；三家全问完则收口进入 PLAYING。
     *
     * <p>收口时重算底牌公开状态：底牌里只要还有王（庄家扣的，或期间被别家扣进来的）
     * 就必须继续公开（2.3.3/2.3.5）；三家里没人扣、庄家也没扣王 → 底牌回到机密
     * （2.3.3「不扣王时底牌不公开」），窗口期间为了让人看牌而临时公开的那一段随之收起。
     *
     * <p>只在 BURYING 阶段调用（窗口由扣底命令开放）。
     */
    public void advanceBuryPick() {
        if (!buryPickQueue.isEmpty()) {
            buryPickQueue.remove(0);
        }
        if (!buryPickQueue.isEmpty()) {
            return;
        }
        refreshBottomReveal();
        transitionTo(GamePhase.PLAYING);
    }

    /** 底牌含王 → 必须公开；不含王 → 不公开（手册 2.3.3） */
    public void refreshBottomReveal() {
        this.bottomRevealed = bottomCards.stream().anyMatch(Card::isJoker);
    }

    /** 作废扣王窗口（重开一局等场景） */
    public void clearBuryPickWindow() {
        buryPickQueue.clear();
    }

    /**
     * 干锅判定：底牌中是否存在“主花色普通牌”（级牌 / 2 / 王 都不算）。
     *
     * <p>第一局（{@link #isFirstRound()}）永远返回 false——无论底牌是什么都不判干锅，
     * 庄家可以正常替换底牌。</p>
     */
    public boolean wouldBeDryPot() {
        if (isFirstRound()) {
            return false;
        }
        TrumpContext trump = this.trump;
        if (trump == null) {
            return false;
        }
        return bottomCards.stream()
                .filter(c -> !c.isJoker() && c.rank() != 2 && c.rank() != trump.level())
                .noneMatch(c -> c.suit() == trump.trumpSuit());
    }

    /**
     * 若干锅且非第一局，自动完成“底牌原样扣回”并进入 PLAYING。
     *
     * <p>庄家收底 → 原样扣回 → 标记干锅 → 庄家领出；不经过 BURYING 阶段，
     * 避免玩家先把牌选好再被告知“不能替换”。</p>
     *
     * @return 是否已自动处理（true = 已干锅并跳过扣底流程）
     */
    public boolean autoBuryIfDryPot() {
        if (!wouldBeDryPot()) {
            return false;
        }
        Seat bankerSeat = this.bankerSeat;
        if (bankerSeat == null) {
            return false;
        }
        Player banker = playerAt(bankerSeat);
        // 收底 → 原样扣回：净效果庄家手牌张数不变，底牌保持原样。
        banker.hand().addAll(bottomCards);
        com.gunzihall.domain.card.Cards.removeCopies(banker.hand(), bottomCards);
        this.bottomTaken = true;
        this.dryPot = true;
        this.turnSeat = bankerSeat;
        this.phase = GamePhase.PLAYING;
        return true;
    }

    public int gameNumber() {
        return gameNumber;
    }

    public void setGameNumber(int gameNumber) {
        this.gameNumber = gameNumber;
    }

    public Map<Seat, TributeObligation> pendingTributes() {
        return Collections.unmodifiableMap(pendingTributes);
    }

    public void putTributeObligation(Seat payer, TributeObligation obligation) {
        pendingTributes.put(payer, obligation);
    }

    public void clearTributeObligations() {
        pendingTributes.clear();
    }

    public Map<Seat, List<Card>> tributeReceived() {
        return Collections.unmodifiableMap(tributeReceived);
    }

    /** 记录一笔已收进贡（payer → 贡牌），并移除其义务；收贡人同时留档（供快照/面板用） */
    public void recordTribute(Seat payer, List<Card> cards) {
        TributeObligation obligation = pendingTributes.remove(payer);
        if (obligation != null) {
            tributeReceiverOf.put(payer, obligation.receiver());
        }
        tributeReceived.put(payer, List.copyOf(cards));
    }

    /** 每笔进贡的收贡人（payer → receiver）；没进贡过则不含该键 */
    public Map<Seat, Seat> tributeReceivers() {
        return Collections.unmodifiableMap(tributeReceiverOf);
    }

    /** 已还贡的牌面（payer → 还贡牌）；还没还贡则不含该键 */
    public Map<Seat, List<Card>> tributeReturnedCards() {
        return Collections.unmodifiableMap(tributeReturnedCards);
    }

    public boolean isTributeReturned(Seat payer) {
        return tributeReturned.contains(payer);
    }

    /**
     * 标记已还贡，并记下**还了哪几张**（面板"还贡的牌都是哪些"的数据来源）。
     * 还贡张数恒等于进贡张数（{@code ReturnTributeCommand} 已校验），这里不再复核。
     */
    public void markTributeReturned(Seat payer, List<Card> cards) {
        tributeReturned.add(payer);
        tributeReturnedCards.put(payer, List.copyOf(cards));
    }

    /** 全部进贡是否均已还贡（TRIBUTE → BURYING 的切换条件） */
    public boolean allTributesReturned() {
        return !tributeReceived.isEmpty()
                && tributeReceived.keySet().stream().allMatch(tributeReturned::contains);
    }

    public Optional<RoundSettlement.Result> lastSettlement() {
        return Optional.ofNullable(lastSettlement);
    }

    public void setLastSettlement(RoundSettlement.Result lastSettlement) {
        this.lastSettlement = lastSettlement;
    }

    public Optional<TributeCalculator.TributeResult> lastTributeResult() {
        return Optional.ofNullable(lastTributeResult);
    }

    public void setLastTributeResult(TributeCalculator.TributeResult lastTributeResult) {
        this.lastTributeResult = lastTributeResult;
    }

    /** 开新局前清空局内流水状态（手牌/底牌由 ShuffleAndDealCommand 重发） */
    public void resetRoundState() {
        currentTrick = null;
        lastCompletedTrick = null;
        turnSeat = null;
        trickPoints.clear();
        takenPointCards.clear();
        lastTrickWinnerTeam = null;
        trump = null;
        bottomCards = List.of();
        dealPile = List.of();
        dealTurn = Seat.NORTH;
        revealState = null;
        bottomTaken = false;
        dryPot = false;
        bottomRevealed = false;
        jokerBuried = false;
        buryPickQueue.clear();
        pendingTributes.clear();
        tributeReceived.clear();
        tributeReturned.clear();
        tributeReceiverOf.clear();
        tributeReturnedCards.clear();
    }

    /**
     * 【强制重开】把房间拉回 WAITING 状态，用于"新局"按钮。
     *
     * <p>为什么不能走 {@link #transitionTo}：状态机只允许 ordinal 顺序前进
     * （见 GamePhase.canTransitionTo），PLAYING/SETTLE 等阶段跳回 WAITING 会抛
     * IllegalStateException。而"重开一局"是玩家主动的显式意图，不属于正常流转，
     * 因此这里提供一个显式的强制入口，绕开顺序校验。
     *
     * <p>只重置一局的进行中状态（回合/主牌/底牌/进贡），保留座位、玩家与分数。
     */
    public void hardResetToWaiting() {
        resetRoundState();
        this.phase = GamePhase.WAITING;
    }
}
