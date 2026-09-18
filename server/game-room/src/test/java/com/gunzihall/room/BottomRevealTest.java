package com.gunzihall.room;

import com.gunzihall.domain.action.BuryBottomCommand;
import com.gunzihall.domain.action.CommandResult;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 底牌公开规则回归测试（手册 2.3.1「发牌或反主结束后底牌公开」）。
 *
 * <p>需求原话：<i>"扣底时，原 6 张底牌应该让所有人看到，扣完底后隐藏。如果本局干锅，
 * 原 6 张底牌所有人全都能看到。"</i>
 *
 * <p>快照里 {@code bottom} 字段的出现时机就是这条规则的实现口径：
 * <ol>
 *   <li><b>BURYING</b>：公开原 6 张（庄家刚收底、还没扣回，桌上摊开的就是它们）；</li>
 *   <li><b>正常局 PLAYING 起</b>：不下发 —— 庄家扣回去的 6 张是机密（手册 2.3.3
 *       "不扣王时底牌不公开"）；</li>
 *   <li><b>干锅局 PLAYING 起</b>：依然公开 —— 干锅局底牌不能替换、只能原样扣回
 *       （手册 2.3.7），而且干锅会整段跳过 BURYING，不在 PLAYING 补发就永远看不到。</li>
 * </ol>
 *
 * <p>这里顺带锁住一个**隐式不变量**：BURYING 阶段 {@code room.bottomCards()} 仍是原底牌。
 * 覆盖动作 {@code setBottomCards(新牌)} 只发生在 {@link BuryBottomCommand} 成功路径的末尾
 * （紧接着就切 PLAYING），失败路径走不到 —— 所以"扣底失败重试"也不会把公开的底牌改掉。
 */
class BottomRevealTest {

    private static final long ROOM = 5001L;
    private static final long HUMAN = 1L;

    /** 主牌 = 红桃 3（级牌 3 与 2 不算"主花色普通牌"，干锅判定里会被过滤掉） */
    private static final Suit TRUMP = Suit.HEART;
    private static final int LEVEL = 3;

    private RoomActor actor;

    @AfterEach
    void tearDown() {
        // 这里刻意**不**调 start()：房间没有 bot 驱动线程，状态完全由用例手工摆，
        // 快照随时可取。shutdown 仍走一遍以防将来测试里加了驱动。
        if (actor != null) {
            actor.shutdown();
        }
    }

    /** 4 家人齐但不开局（不起 bot 驱动），返回可直接改状态的房间 */
    private GameRoom newIdleRoom() {
        actor = new RoomActor(ROOM, new InMemoryStateStore());
        actor.join(new HumanPlayer(HUMAN, Seat.SOUTH), false);
        actor.join(new HumanPlayer(2L, Seat.NORTH), true);
        actor.join(new HumanPlayer(3L, Seat.EAST), true);
        actor.join(new HumanPlayer(4L, Seat.WEST), true);
        return actor.room();
    }

    private Map<String, Object> snapshot() {
        return JsonUtil.read(actor.snapshotFor(HUMAN), Map.class);
    }

    /**
     * 把房间从 WAITING 逐级推到目标阶段（状态机只允许单步前进，所以沿序列走）。
     * 不开局、不发牌：手牌与底牌全部由用例自行给定，避免随机发牌把断言写脆。
     */
    private static void advanceTo(GameRoom room, GamePhase target) {
        GamePhase[] order = GamePhase.values();
        while (room.phase() != target) {
            room.transitionTo(order[room.phase().ordinal() + 1]);
        }
    }

    /** 摆一个"随时可扣底"的局面：定主 + 庄家 + 原底牌 + 庄家原本手牌 */
    private static void arrangeBeforeBury(GameRoom room, List<Card> bottom,
                                          List<Card> bankerHandBeforeTake) {
        advanceTo(room, GamePhase.BIDDING);
        room.setFirstRound(false);                       // 首局豁免干锅，这里要的是正式规则
        room.setTrump(new TrumpContext(LEVEL, TRUMP));
        room.setBankerSeat(Seat.SOUTH);                  // 真人当庄，扣底命令由他发
        room.setBottomCards(bottom);
        Player banker = room.playerAt(Seat.SOUTH);
        banker.hand().clear();
        banker.hand().addAll(bankerHandBeforeTake);
    }

    // ---------------------------------------------------------------- ① 扣底阶段公开

    @Test
    void buryingPhase_originalBottomIsPublicToEveryone() {
        GameRoom room = newIdleRoom();
        List<Card> bottom = List.of(
                Card.of(Suit.SPADE, 4), Card.of(Suit.SPADE, 6),
                Card.of(Suit.CLUB, 4), Card.of(Suit.CLUB, 6),
                Card.of(Suit.DIAMOND, 7), Card.of(Suit.DIAMOND, 9));
        arrangeBeforeBury(room, bottom, List.of(Card.of(Suit.SPADE, 7)));
        advanceTo(room, GamePhase.BURYING);

        assertEquals(CardCodec.encodeAll(bottom), snapshot().get("bottom"),
                "扣底阶段必须把原 6 张底牌下发给所有人");
    }

    // ---------------------------------------------------------------- ② 正常局扣完即隐藏

    @Test
    void afterNormalBury_newBottomStaysSecret() {
        GameRoom room = newIdleRoom();
        // 底牌含一张红桃普通牌（♥4）→ 不是干锅，庄家可以替换底牌
        List<Card> bottom = List.of(
                Card.of(Suit.HEART, 4), Card.of(Suit.SPADE, 6),
                Card.of(Suit.CLUB, 4), Card.of(Suit.CLUB, 6),
                Card.of(Suit.DIAMOND, 4), Card.of(Suit.DIAMOND, 7));
        List<Card> handBefore = List.of(
                Card.of(Suit.SPADE, 7), Card.of(Suit.SPADE, 8),
                Card.of(Suit.CLUB, 7), Card.of(Suit.CLUB, 8),
                Card.of(Suit.DIAMOND, 8), Card.of(Suit.DIAMOND, 10),
                Card.of(Suit.HEART, 5));
        arrangeBeforeBury(room, bottom, handBefore);
        advanceTo(room, GamePhase.BURYING);

        // 扣 6 张自己原来的牌（不含王，避开 Q5 扣王顺序约束）
        CommandResult r = room.apply(new BuryBottomCommand(ROOM, HUMAN, handBefore.subList(0, 6)));

        assertTrue(r.success(), "正常局扣底应被接受: " + r.reason());
        assertFalse(room.isDryPot(), "底牌含主花色普通牌，不该判干锅");
        assertEquals(GamePhase.PLAYING, room.phase());
        assertFalse(snapshot().containsKey("bottom"),
                "扣完底后新底牌是庄家机密，快照不得下发（手册 2.3.3「不扣王时底牌不公开」）");
    }

    // ---------------------------------------------------------------- ③ 干锅局全程公开

    @Test
    void dryPotRound_originalBottomStaysPublicThroughPlaying() {
        GameRoom room = newIdleRoom();
        // 底牌全是黑桃/梅花/方片普通牌，**没有一张红桃**（主花色）→ 干锅
        List<Card> bottom = List.of(
                Card.of(Suit.SPADE, 4), Card.of(Suit.SPADE, 6),
                Card.of(Suit.CLUB, 4), Card.of(Suit.CLUB, 6),
                Card.of(Suit.DIAMOND, 4), Card.of(Suit.DIAMOND, 7));
        List<Card> handBefore = List.of(
                Card.of(Suit.SPADE, 7), Card.of(Suit.SPADE, 8),
                Card.of(Suit.CLUB, 7), Card.of(Suit.CLUB, 8),
                Card.of(Suit.DIAMOND, 8), Card.of(Suit.DIAMOND, 10),
                Card.of(Suit.HEART, 5));
        arrangeBeforeBury(room, bottom, handBefore);
        advanceTo(room, GamePhase.BURYING);

        // 干锅局只能原样扣回
        CommandResult r = room.apply(new BuryBottomCommand(ROOM, HUMAN, bottom));

        assertTrue(r.success(), "干锅局原样扣回应被接受: " + r.reason());
        assertTrue(room.isDryPot(), "底牌无主花色普通牌，应判干锅");
        assertEquals(GamePhase.PLAYING, room.phase());
        assertEquals(CardCodec.encodeAll(bottom), snapshot().get("bottom"),
                "干锅局底牌原样扣回，出牌阶段仍要对所有人公开");
    }

    // ------------------------------------------------- ④ 干锅跳过扣底（不经过 BURYING）

    @Test
    void dryPotSkippingBurying_bottomStillReachesClients() {
        GameRoom room = newIdleRoom();
        List<Card> bottom = List.of(
                Card.of(Suit.SPADE, 4), Card.of(Suit.SPADE, 6),
                Card.of(Suit.CLUB, 4), Card.of(Suit.CLUB, 6),
                Card.of(Suit.DIAMOND, 4), Card.of(Suit.DIAMOND, 7));
        arrangeBeforeBury(room, bottom, List.of(Card.of(Suit.SPADE, 7)));
        advanceTo(room, GamePhase.TRIBUTE);

        // 干锅的正式入口：判定通过就整段跳过 BURYING 直接进 PLAYING
        boolean handled = room.enterBuryingPhase();

        assertTrue(handled, "干锅应由 enterBuryingPhase 拦下，不进扣底阶段");
        assertEquals(GamePhase.PLAYING, room.phase());
        assertTrue(room.isBottomTaken(), "干锅自动收底，底牌已并入庄家手牌");
        assertEquals(CardCodec.encodeAll(bottom), snapshot().get("bottom"),
                "干锅跳过了 BURYING，若不在此处补发底牌，玩家从头到尾都看不到那 6 张");
    }

    // ------------------------------------------- ⑤ 庄家私有底牌（扣底后回看，不外泄）

    @Test
    void afterNormalBury_bankerCanReviewOwnBottom() {
        GameRoom room = newIdleRoom();
        List<Card> bottom = List.of(
                Card.of(Suit.HEART, 4), Card.of(Suit.SPADE, 6),
                Card.of(Suit.CLUB, 4), Card.of(Suit.CLUB, 6),
                Card.of(Suit.DIAMOND, 4), Card.of(Suit.DIAMOND, 7));
        List<Card> handBefore = List.of(
                Card.of(Suit.SPADE, 7), Card.of(Suit.SPADE, 8),
                Card.of(Suit.CLUB, 7), Card.of(Suit.CLUB, 8),
                Card.of(Suit.DIAMOND, 8), Card.of(Suit.DIAMOND, 10),
                Card.of(Suit.HEART, 5));
        arrangeBeforeBury(room, bottom, handBefore);
        advanceTo(room, GamePhase.BURYING);

        List<Card> buried = List.copyOf(handBefore.subList(0, 6));
        CommandResult r = room.apply(new BuryBottomCommand(ROOM, HUMAN, buried));
        assertTrue(r.success(), "正常局扣底应被接受: " + r.reason());

        assertEquals(CardCodec.encodeAll(buried), snapshot().get("myBottom"),
                "庄家必须能看到自己扣下的 6 张（前端「我的底牌」回看区的数据来源）");
        assertFalse(snapshot().containsKey("bottom"),
                "私有底牌不是公开字段：公开的 bottom 仍不得下发");

        // 闲家（东家）的快照里不能出现这些牌 —— 否则等于把庄家底牌泄给对手
        Map<String, Object> others = JsonUtil.read(actor.snapshotFor(3L), Map.class);
        assertFalse(others.containsKey("myBottom"), "非庄家不得收到 myBottom");
        assertFalse(others.containsKey("bottom"), "非庄家不得收到公开底牌");
    }

    /**
     * 扣王窗口开着、还没人扣 → **新底牌仍是机密**（2.3.5 要求的是"扣王时公开"，
     * 公开是押中的后果，不是开窗的前提）。
     *
     * <p>注意这条与 ① 不冲突：① 摊开的是**原底牌**（庄家收底前桌上那 6 张），
     * 这里保密的是**庄家扣回去的新底牌**。两者是两份不同的牌面。
     *
     * <p>庄家本人不受影响 —— 他走 myBottom 私有字段，照旧能回看自己扣的 6 张。
     */
    @Test
    void buryPickWindowOpen_newBottomStaysSecretUntilSomebodyPicks() {
        GameRoom room = newIdleRoom();
        // 原底牌含 ♥4（主花色普通牌）→ 不是干锅，庄家可以替换
        List<Card> bottom = List.of(
                Card.of(Suit.HEART, 4), Card.of(Suit.SPADE, 6),
                Card.of(Suit.CLUB, 4), Card.of(Suit.CLUB, 6),
                Card.of(Suit.DIAMOND, 4), Card.of(Suit.DIAMOND, 7));
        // 新底牌：6 张全"非分非王"，满足 2.3.5 开窗前提
        List<Card> buried = List.of(
                Card.of(Suit.SPADE, 7), Card.of(Suit.SPADE, 8),
                Card.of(Suit.CLUB, 7), Card.of(Suit.CLUB, 8),
                Card.of(Suit.DIAMOND, 8), Card.of(Suit.DIAMOND, 9));
        List<Card> handBefore = new ArrayList<>(buried);
        handBefore.add(Card.of(Suit.HEART, 5));
        arrangeBeforeBury(room, bottom, handBefore);
        // 东家手里有王 → 窗口会真的开起来
        room.playerAt(Seat.EAST).hand().add(Card.smallJoker());
        advanceTo(room, GamePhase.BURYING);

        CommandResult r = room.apply(new BuryBottomCommand(ROOM, HUMAN, buried));

        assertTrue(r.success(), "正常局扣底应被接受: " + r.reason());
        assertEquals(GamePhase.BURYING, room.phase(), "还有三家要表态，先留在 BURYING");
        assertNotNull(room.buryPickSeat(), "窗口应已开到第一家");
        assertFalse(room.isBottomRevealed(), "还没人扣王 → 底牌保持机密");
        assertFalse(snapshot().containsKey("bottom"),
                "窗口期间不摊牌：公开要等真有人扣了王（2.3.5）");
        assertEquals(CardCodec.encodeAll(buried), snapshot().get("myBottom"),
                "庄家自己仍能回看（私有字段，不是公开）");
    }

    /**
     * 最关键的一条：底牌不能"顺着局号带出去"。
     * 结算命令收尾会调 resetRoundState()（bottomTaken=false、bottomCards 清空），
     * 因此新一局发牌/亮主期间即使仍是同一家坐庄，也不会把上一局的底牌再发给谁。
     */
    @Test
    void newRoundBeforeBury_nobodyGetsBottom() {
        GameRoom room = newIdleRoom();
        List<Card> bottom = List.of(
                Card.of(Suit.HEART, 4), Card.of(Suit.SPADE, 6),
                Card.of(Suit.CLUB, 4), Card.of(Suit.CLUB, 6),
                Card.of(Suit.DIAMOND, 4), Card.of(Suit.DIAMOND, 7));
        List<Card> handBefore = List.of(
                Card.of(Suit.SPADE, 7), Card.of(Suit.SPADE, 8),
                Card.of(Suit.CLUB, 7), Card.of(Suit.CLUB, 8),
                Card.of(Suit.DIAMOND, 8), Card.of(Suit.DIAMOND, 10),
                Card.of(Suit.HEART, 5));
        arrangeBeforeBury(room, bottom, handBefore);
        advanceTo(room, GamePhase.BURYING);
        room.apply(new BuryBottomCommand(ROOM, HUMAN, handBefore.subList(0, 6)));

        // 一局结束开新局：结算收尾清局内状态，再重新发牌
        room.resetRoundState();
        room.setBankerSeat(Seat.SOUTH);          // 仍是同一家坐庄
        room.setBottomCards(List.of(
                Card.of(Suit.SPADE, 9), Card.of(Suit.SPADE, 10)));
        room.setGameNumber(room.gameNumber() + 1);

        Map<String, Object> banker = JsonUtil.read(actor.snapshotFor(HUMAN), Map.class);
        assertFalse(banker.containsKey("myBottom"),
                "还没收底就不能下发底牌：否则新一局的底牌在亮主前就被庄家看到了");
        assertFalse(banker.containsKey("bottom"), "未收底同样不公开");
    }
}
