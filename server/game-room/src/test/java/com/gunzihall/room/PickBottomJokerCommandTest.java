package com.gunzihall.room;

import com.gunzihall.domain.action.BuryBottomCommand;
import com.gunzihall.domain.action.CommandResult;
import com.gunzihall.domain.action.PickBottomJokerCommand;
import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.card.Suit;
import com.gunzihall.domain.player.HumanPlayer;
import com.gunzihall.domain.player.Player;
import com.gunzihall.domain.player.Seat;
import com.gunzihall.domain.room.GamePhase;
import com.gunzihall.domain.room.GameRoom;
import com.gunzihall.domain.trump.TrumpContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 他人捡牌扣王回归测试（手册 2.3 第 5 ~ 8 条）。
 *
 * <p>规则原文：<i>"在庄家扣完底牌后，其他用户也可在底牌中扣王，扣王时底牌必须公开。
 * 扣王后还需从底牌中捡最小的牌，不能捡分牌，扣几张捡几张。最小牌的判定方法为先看是否是
 * 主牌，然后看点数，最后看花色。"</i>
 *
 * <p>注意动作方向容易被读反：**扣王 = 把自己的王扣进底牌**，再从底牌捡走同样张数的
 * 最小非分牌（底牌张数守恒），不是"从底牌里拿走王"。
 *
 * <p>本用例锁住四件事：① 窗口什么时候开、什么时候不开；② 底牌什么时候因此公开；
 * ③ 捡牌捡的是不是"最小的非分牌"且张数守恒；④ 越权/非本轮的提交会被拒。
 */
class PickBottomJokerCommandTest {

    private static final long ROOM = 6001L;
    private static final long HUMAN = 1L;      // 南家，真人，本局庄家
    private static final long EAST_PID = 3L;

    /** 主牌 = 红桃 3；级牌 3 与 2 都不算"主花色普通牌"（干锅判定里会被过滤） */
    private static final Suit TRUMP = Suit.HEART;
    private static final int LEVEL = 3;

    /** 原底牌：含一张 ♥4（主花色普通牌）→ 不干锅，庄家可以正常替换 */
    private static final List<Card> ORIGINAL_BOTTOM = List.of(
            Card.of(Suit.HEART, 4), Card.of(Suit.SPADE, 6),
            Card.of(Suit.CLUB, 4), Card.of(Suit.CLUB, 6),
            Card.of(Suit.DIAMOND, 4), Card.of(Suit.DIAMOND, 7));

    /**
     * 庄家扣出的新底牌：6 张全是"非王、非分牌"。
     * 花色序 SPADE(0) &lt; HEART(1) &lt; CLUB(2) &lt; DIAMOND(3)，点数 7 &lt; 8 &lt; 9，
     * 所以其中"最小"的一张是 ♠7 —— 捡牌用例据此断言。
     */
    private static final List<Card> NEW_BOTTOM = List.of(
            Card.of(Suit.SPADE, 7), Card.of(Suit.SPADE, 8),
            Card.of(Suit.CLUB, 7), Card.of(Suit.CLUB, 8),
            Card.of(Suit.DIAMOND, 8), Card.of(Suit.DIAMOND, 9));

    private RoomActor actor;

    @AfterEach
    void tearDown() {
        // 刻意不 start()：不开 bot 驱动，状态完全由用例手工摆，快照随时可取
        if (actor != null) {
            actor.shutdown();
        }
    }

    private GameRoom newIdleRoom() {
        actor = new RoomActor(ROOM, new InMemoryStateStore());
        actor.join(new HumanPlayer(HUMAN, Seat.SOUTH), false);
        actor.join(new HumanPlayer(2L, Seat.NORTH), true);
        actor.join(new HumanPlayer(EAST_PID, Seat.EAST), true);
        actor.join(new HumanPlayer(4L, Seat.WEST), true);
        return actor.room();
    }

    private Map<String, Object> snapshot() {
        return JsonUtil.read(actor.snapshotFor(HUMAN), Map.class);
    }

    /** 状态机只允许单步前进，所以沿序列走到目标阶段 */
    private static void advanceTo(GameRoom room, GamePhase target) {
        GamePhase[] order = GamePhase.values();
        while (room.phase() != target) {
            room.transitionTo(order[room.phase().ordinal() + 1]);
        }
    }

    /** 摆一个"随时可扣底"的局面：定主 + 庄家（南） + 原底牌 + 庄家扣底前的手牌 */
    private static void arrangeBeforeBury(GameRoom room, List<Card> bankerHandBeforeTake) {
        advanceTo(room, GamePhase.BIDDING);
        room.setFirstRound(false);
        room.setTrump(new TrumpContext(LEVEL, TRUMP));
        room.setBankerSeat(Seat.SOUTH);
        room.setBottomCards(ORIGINAL_BOTTOM);
        Player banker = room.playerAt(Seat.SOUTH);
        banker.hand().clear();
        banker.hand().addAll(bankerHandBeforeTake);
        advanceTo(room, GamePhase.BURYING);
    }

    /** 庄家扣底前的手牌：NEW_BOTTOM 六张 + 一张多余的牌（收底后共 13 张，扣 6 张） */
    private static List<Card> bankerHandBeforeTake() {
        List<Card> hand = new ArrayList<>(NEW_BOTTOM);
        hand.add(Card.of(Suit.HEART, 5));
        return hand;
    }

    /** 把房间推到"庄家刚扣完 NEW_BOTTOM"的状态 */
    private static CommandResult bury(GameRoom room) {
        return room.apply(new BuryBottomCommand(ROOM, HUMAN, NEW_BOTTOM));
    }

    /** 给某个座位手里塞几张王（绕过真实发牌，用例只关心扣王规则本身） */
    private static void giveJokers(GameRoom room, Seat seat, int smalls, int bigs) {
        Player p = room.playerAt(seat);
        for (int i = 0; i < smalls; i++) {
            p.hand().add(Card.smallJoker());
        }
        for (int i = 0; i < bigs; i++) {
            p.hand().add(Card.bigJoker());
        }
    }

    // ------------------------------------------------- ① 什么时候开窗、什么时候不开

    @Test
    void buryWithoutPointsAndOthersHoldJoker_opensWindowForNextSeat() {
        GameRoom room = newIdleRoom();
        giveJokers(room, Seat.EAST, 1, 0);
        arrangeBeforeBury(room, bankerHandBeforeTake());

        CommandResult r = bury(room);

        assertTrue(r.success(), "正常扣底应被接受: " + r.reason());
        assertEquals(GamePhase.BURYING, room.phase(),
                "还有三家要表态，不能直接进 PLAYING");
        assertEquals(Seat.EAST, room.buryPickSeat(),
                "南家的下家是东（逆时针），第一个问东家");
        Map<String, Object> snap = snapshot();
        assertFalse(snap.containsKey("bottom"),
                "扣王窗口期间**不摊牌** —— 公开是押中之后的后果（2.3.5），不是开窗的前提");
        assertFalse(room.isBottomRevealed(), "还没人扣王，底牌保持机密");
        assertEquals("EAST", snap.get("pickSeat"));
        assertEquals(6, snap.get("pickMax"), "6 张全非分非王，最多能扣 6 张");
    }

    /**
     * 机密只在"真有人扣了"的那一刻让渡 —— 有人表态"过"不算。
     *
     * <p>这条是"窗口期间不摊牌"最容易被改坏的地方：把公开动作挂在开窗上、
     * 或者挂在任何一次表态上，都会让三家白看一次底牌。
     */
    @Test
    void windowStaysSecretUntilSomebodyActuallyPicks() {
        GameRoom room = newIdleRoom();
        giveJokers(room, Seat.EAST, 1, 0);
        giveJokers(room, Seat.NORTH, 1, 0);
        arrangeBeforeBury(room, bankerHandBeforeTake());
        bury(room);

        // 东家"过"：只是表态，不构成扣王，底牌不得因此公开
        room.apply(new PickBottomJokerCommand(ROOM, EAST_PID, List.of()));
        assertEquals(Seat.NORTH, room.buryPickSeat(), "东家过完轮到北家");
        assertFalse(room.isBottomRevealed(), "有人过 ≠ 有人扣，底牌不能跟着亮");
        assertFalse(snapshot().containsKey("bottom"), "窗口还没收口，底牌仍是机密");

        // 北家真扣了：这一刻起才对所有人公开
        room.apply(new PickBottomJokerCommand(ROOM, 2L, List.of(Card.smallJoker())));
        assertTrue(room.isBottomRevealed(), "真的有人扣了王才公开（2.3.5）");
        assertEquals(CardCodec.encodeAll(room.bottomCards()), snapshot().get("bottom"),
                "公开的是含王的那份新底牌");

        // 第三家（西）也过 → 收口进 PLAYING，押过注的底牌本局一直公开
        room.apply(new PickBottomJokerCommand(ROOM, 4L, List.of()));
        assertEquals(GamePhase.PLAYING, room.phase());
        assertTrue(room.isBottomRevealed(), "押过注的底牌本局一直公开");
        assertEquals(CardCodec.encodeAll(room.bottomCards()), snapshot().get("bottom"));
    }

    @Test
    void buryWithoutPointsButNobodyElseHasJoker_skipsWindowEntirely() {
        GameRoom room = newIdleRoom();
        // 其他三家一张王都没有 → 窗口必然全"过"，开它只会白停一轮、白露一次底牌
        arrangeBeforeBury(room, bankerHandBeforeTake());

        CommandResult r = bury(room);

        assertTrue(r.success(), r.reason());
        assertEquals(GamePhase.PLAYING, room.phase(), "没人能扣王，直接开打");
        assertFalse(snapshot().containsKey("bottom"),
                "没人扣王 → 底牌保持机密（手册 2.3.3「不扣王时底牌不公开」）");
        assertFalse(snapshot().containsKey("pickSeat"), "没有窗口就不该下发 pickSeat");
    }

    @Test
    void buryWithPointCards_neverOpensWindow() {
        GameRoom room = newIdleRoom();
        giveJokers(room, Seat.EAST, 1, 0);
        // 庄家扣的底牌里含分牌（♦K = 10 分）→ 手册 2.3.6 / 2.3.8（Q5b 合并）：
        // 底牌含分牌（无论是否混有王），其他玩家都不能再在底牌中扣王
        List<Card> withPoints = List.of(
                Card.of(Suit.SPADE, 7), Card.of(Suit.SPADE, 8),
                Card.of(Suit.CLUB, 7), Card.of(Suit.CLUB, 8),
                Card.of(Suit.DIAMOND, 8), Card.of(Suit.DIAMOND, 13));
        arrangeBeforeBury(room, withPoints);

        CommandResult r = room.apply(new BuryBottomCommand(ROOM, HUMAN, withPoints));

        assertTrue(r.success(), r.reason());
        assertEquals(GamePhase.PLAYING, room.phase());
        assertFalse(snapshot().containsKey("pickSeat"),
                "底牌含分牌 → 他人不得扣王（捡牌前提是不能捡分牌，索性整条禁掉）");
        assertFalse(snapshot().containsKey("bottom"),
                "本局无人扣王，含分牌的新底牌依然是机密");
        assertTrue(room.bottomCards().contains(Card.of(Suit.DIAMOND, 13)),
                "底牌应原样是庄家扣下的 6 张");
    }

    @Test
    void bankerBuriesJoker_bottomBecomesPublic() {
        GameRoom room = newIdleRoom();
        // 庄家扣进一张小王（手册 2.3.3：扣王时底牌必须亮给所有人看）
        List<Card> withJoker = List.of(
                Card.of(Suit.SPADE, 7), Card.of(Suit.SPADE, 8),
                Card.of(Suit.CLUB, 7), Card.of(Suit.CLUB, 8),
                Card.of(Suit.DIAMOND, 8), Card.smallJoker());

        arrangeBeforeBury(room, withJoker);
        CommandResult r = room.apply(new BuryBottomCommand(ROOM, HUMAN, withJoker));

        assertTrue(r.success(), r.reason());
        assertEquals(CardCodec.encodeAll(withJoker), snapshot().get("bottom"),
                "庄家扣了王 → 底牌必须对所有人公开（2.3.3）");
        assertTrue(room.isBottomRevealed());
    }

    // ------------------------------------------------- ② 捡牌：最小、非分、非王、张数守恒

    @Test
    void pickJoker_swapsOwnJokerForSmallestNonPointCard() {
        GameRoom room = newIdleRoom();
        giveJokers(room, Seat.EAST, 1, 0);
        arrangeBeforeBury(room, bankerHandBeforeTake());
        bury(room);

        Card picked = Card.of(Suit.SPADE, 7); // 底牌里最小的一张（点数最小、花色序最靠前）
        CommandResult r = room.apply(new PickBottomJokerCommand(ROOM, EAST_PID,
                List.of(Card.smallJoker())));

        assertTrue(r.success(), "东家扣 1 张王应被接受: " + r.reason());
        assertEquals(6, room.bottomCards().size(), "扣几张捡几张 → 底牌张数必须守恒");
        assertTrue(room.bottomCards().contains(Card.smallJoker()),
                "东家的小王进了底牌（押注行为）");
        assertFalse(room.bottomCards().contains(picked), "最小的一张被捡走了");
        assertTrue(room.playerAt(Seat.EAST).hand().contains(picked), "捡走的牌归扣王者");
        assertFalse(room.playerAt(Seat.EAST).hand().contains(Card.smallJoker()),
                "扣进底牌的王要离开自己手牌");
        assertEquals(Seat.NORTH, room.buryPickSeat(), "东家表态完，轮到北家");
        assertTrue(room.isBottomRevealed(), "手册 2.3.5：扣王时底牌必须公开");
        assertEquals(CardCodec.encodeAll(room.bottomCards()), snapshot().get("bottom"),
                "扣完之后底牌仍要对所有人公开");
    }

    @Test
    void pickJoker_neverTakesJokersOrPointCards() {
        GameRoom room = newIdleRoom();
        giveJokers(room, Seat.EAST, 1, 0);
        // 庄家扣的底牌里已经有一张大王 → 扣 5 张非分牌 + 1 张大王
        List<Card> withJoker = List.of(
                Card.of(Suit.SPADE, 7), Card.of(Suit.SPADE, 8),
                Card.of(Suit.CLUB, 7), Card.of(Suit.CLUB, 8),
                Card.of(Suit.DIAMOND, 8), Card.bigJoker());
        arrangeBeforeBury(room, withJoker);
        room.apply(new BuryBottomCommand(ROOM, HUMAN, withJoker));

        CommandResult r = room.apply(new PickBottomJokerCommand(ROOM, EAST_PID,
                List.of(Card.smallJoker())));

        assertTrue(r.success(), r.reason());
        assertEquals(6, room.bottomCards().size());
        assertEquals(2, room.bottomCards().stream().filter(Card::isJoker).count(),
                "两张王都留在底牌里：捡的是最小的牌，王最大，绝不可能被捡走");
        assertFalse(room.playerAt(Seat.EAST).hand().contains(Card.bigJoker()));
    }

    @Test
    void pickJoker_beyondPickableCount_rejected() {
        GameRoom room = newIdleRoom();
        // 底牌 = 4 张王 + 2 张非分非王 → 只有 2 张可捡；
        // （三副牌总共 6 张王，现实中凑不出"扣的比能捡的多"，这里直接塞牌，
        //   纯粹为了锁住这道防御性校验：客户端乱传也必须被拒。）
        List<Card> fourJokers = List.of(
                Card.smallJoker(), Card.smallJoker(), Card.smallJoker(), Card.bigJoker(),
                Card.of(Suit.SPADE, 7), Card.of(Suit.SPADE, 8));
        arrangeBeforeBury(room, fourJokers);
        giveJokers(room, Seat.EAST, 2, 1); // 东家手里 3 张王，想全扣，但底牌只捡得回 2 张
        room.apply(new BuryBottomCommand(ROOM, HUMAN, fourJokers));

        CommandResult r = room.apply(new PickBottomJokerCommand(ROOM, EAST_PID, List.of(
                Card.smallJoker(), Card.smallJoker(), Card.bigJoker())));

        assertFalse(r.success(), "可捡数不足时必须拒绝，否则“扣几张捡几张”落不了地");
        assertTrue(r.reason().contains("最多扣"), "拒绝原因要说清张数上限: " + r.reason());
        assertEquals(6, room.bottomCards().size(), "被拒的命令不得改动底牌");
    }

    // ------------------------------------------------- ③ 依次询问：过、收口、越权

    @Test
    void pass_handsTurnToNextSeat() {
        GameRoom room = newIdleRoom();
        giveJokers(room, Seat.EAST, 1, 0);
        arrangeBeforeBury(room, bankerHandBeforeTake());
        bury(room);

        CommandResult r = room.apply(new PickBottomJokerCommand(ROOM, EAST_PID, List.of()));

        assertTrue(r.success(), "空列表 = 过，不是错误: " + r.reason());
        assertEquals(Seat.NORTH, room.buryPickSeat());
        assertEquals(GamePhase.BURYING, room.phase());
    }

    @Test
    void allPass_closesWindowAndHidesBottomAgain() {
        GameRoom room = newIdleRoom();
        giveJokers(room, Seat.EAST, 1, 0);
        arrangeBeforeBury(room, bankerHandBeforeTake());
        bury(room);

        room.apply(new PickBottomJokerCommand(ROOM, EAST_PID, List.of()));
        room.apply(new PickBottomJokerCommand(ROOM, 2L, List.of()));
        room.apply(new PickBottomJokerCommand(ROOM, 4L, List.of()));

        assertEquals(GamePhase.PLAYING, room.phase(), "三家问完就该开打");
        assertFalse(room.isBottomRevealed(), "没人扣王、庄家也没扣王 → 底牌重新收起");
        assertFalse(snapshot().containsKey("bottom"),
                "收口后底牌回到机密状态（2.3.3）");
        assertFalse(snapshot().containsKey("pickSeat"));
    }

    @Test
    void anyPick_keepsBottomPublicAfterWindowCloses() {
        GameRoom room = newIdleRoom();
        giveJokers(room, Seat.EAST, 1, 0);
        arrangeBeforeBury(room, bankerHandBeforeTake());
        bury(room);

        room.apply(new PickBottomJokerCommand(ROOM, EAST_PID, List.of(Card.smallJoker())));
        room.apply(new PickBottomJokerCommand(ROOM, 2L, List.of()));
        room.apply(new PickBottomJokerCommand(ROOM, 4L, List.of()));

        assertEquals(GamePhase.PLAYING, room.phase());
        assertTrue(room.isBottomRevealed(), "有人扣了王 → 底牌本局一直公开（2.3.5）");
        assertEquals(CardCodec.encodeAll(room.bottomCards()), snapshot().get("bottom"),
                "闲家也能看到这张被押过注的底牌");
    }

    @Test
    void notYourTurn_rejected() {
        GameRoom room = newIdleRoom();
        giveJokers(room, Seat.EAST, 1, 0);
        giveJokers(room, Seat.NORTH, 1, 0);
        arrangeBeforeBury(room, bankerHandBeforeTake());
        bury(room);

        // 现在轮到东家，北家抢先提交
        CommandResult r = room.apply(new PickBottomJokerCommand(ROOM, 2L, List.of(Card.smallJoker())));

        assertFalse(r.success(), "没轮到就不许扣");
        assertTrue(r.reason().contains("还没轮到你"), r.reason());
        assertEquals(Seat.EAST, room.buryPickSeat(), "被拒的提交不能推进队列");
    }

    @Test
    void notAWindow_rejected() {
        GameRoom room = newIdleRoom();
        arrangeBeforeBury(room, bankerHandBeforeTake());
        giveJokers(room, Seat.EAST, 1, 0);
        // 还没扣底：没有窗口
        CommandResult r = room.apply(new PickBottomJokerCommand(ROOM, EAST_PID, List.of()));

        assertFalse(r.success(), "庄家还没扣完底，谈不上他人扣王");
        assertTrue(r.reason().contains("没有他人扣王的窗口"), r.reason());
    }

    @Test
    void nonJokerOrMissingCard_rejected() {
        GameRoom room = newIdleRoom();
        giveJokers(room, Seat.EAST, 1, 0);
        arrangeBeforeBury(room, bankerHandBeforeTake());
        bury(room);

        // ① 拿一张非王来"扣王"
        CommandResult r1 = room.apply(new PickBottomJokerCommand(ROOM, EAST_PID,
                List.of(Card.of(Suit.SPADE, 13))));
        assertFalse(r1.success(), "只能扣王");
        assertTrue(r1.reason().contains("只能扣王"), r1.reason());

        // ② 拿一张手里没有的王（王在别人手里）
        CommandResult r2 = room.apply(new PickBottomJokerCommand(ROOM, EAST_PID,
                List.of(Card.bigJoker(), Card.bigJoker())));
        assertFalse(r2.success(), "手牌里没有的王不能拿来扣");
        assertTrue(r2.reason().contains("不在自己手牌中"), r2.reason());

        assertEquals(Seat.EAST, room.buryPickSeat(), "被拒的提交不能推进队列");
        assertEquals(6, room.bottomCards().size(), "被拒的命令不得改动底牌");
    }

    // ------------------------------------------------- ④ 干锅：连窗口都不该有

    @Test
    void dryPot_bottomCanNotBeTouchedByAnyone() {
        GameRoom room = newIdleRoom();
        // 原底牌一张红桃都没有 → 干锅；干锅会整段跳过 BURYING（手册 2.3.7）
        List<Card> dryBottom = List.of(
                Card.of(Suit.SPADE, 4), Card.of(Suit.SPADE, 6),
                Card.of(Suit.CLUB, 4), Card.of(Suit.CLUB, 6),
                Card.of(Suit.DIAMOND, 4), Card.of(Suit.DIAMOND, 7));
        giveJokers(room, Seat.EAST, 1, 0);
        advanceTo(room, GamePhase.BIDDING);
        room.setFirstRound(false);
        room.setTrump(new TrumpContext(LEVEL, TRUMP));
        room.setBankerSeat(Seat.SOUTH);
        room.setBottomCards(dryBottom);
        room.playerAt(Seat.SOUTH).hand().clear();

        boolean dry = room.enterBuryingPhase();

        assertTrue(dry, "应判干锅");
        assertEquals(GamePhase.PLAYING, room.phase());
        assertFalse(room.isBuryPickWindowOpen(), "干锅局不能扣王，窗口不该开");
        assertEquals(6, room.bottomCards().size(), "干锅原样扣回，底牌张数不变");
    }
}
