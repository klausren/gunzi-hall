package com.gunzihall.room;

import com.gunzihall.domain.action.BuryBottomCommand;
import com.gunzihall.domain.action.CommandResult;
import com.gunzihall.domain.action.PickBottomJokerCommand;
import com.gunzihall.domain.action.ReturnTributeCommand;
import com.gunzihall.domain.action.TributeCommand;
import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.card.Suit;
import com.gunzihall.domain.player.HumanPlayer;
import com.gunzihall.domain.player.Player;
import com.gunzihall.domain.player.Seat;
import com.gunzihall.domain.room.GamePhase;
import com.gunzihall.domain.room.GameRoom;
import com.gunzihall.domain.tribute.TributeObligation;
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
 * 「本局都发生了什么」三个事实的快照回归测试：**干锅 / 扣王 / 进贡**。
 *
 * <p>来源：Tracy 2026-09-18 —— 点「闲家得分」弹出的面板除了分牌明细，还要报
 * "本局是否干锅、是否扣王、是否进贡"，进贡了还要列出**贡了哪几张、还了哪几张**。
 *
 * <p>这里锁三件事：
 * <ol>
 *   <li>{@code dryPot} / {@code jokerBuried} 必须按**事实**下发，不按"底牌摊没摊开"。
 *       两个口径今天几乎同真同假，但**干锅局会分叉**：干锅是原样扣回，底牌里那几张王
 *       是发牌发出来的（手册 2.3.7 专门为"干锅底牌王"立规），{@code bottomRevealed}
 *       会因"底牌含王"为真，而"有人扣王"必须仍为假；</li>
 *   <li>{@code tributes} 明细要带 **收贡人**：义务一付清就摘出 pendingTributes，
 *       收贡人事后补推不出来（一局可能同时有两笔、收贡人各不相同）；</li>
 *   <li>这些事实**不能顺着局号带出去**：开新局（resetRoundState）后必须清空。</li>
 * </ol>
 */
class RoundFactsSnapshotTest {

    private static final long ROOM = 5002L;
    private static final long HUMAN = 1L;        // SOUTH
    private static final long EAST_ID = 3L;
    private static final long WEST_ID = 4L;

    /** 主牌 = 红桃 3（级牌 3 与 2、王 都不算"主花色普通牌"，干锅判定里会被过滤掉） */
    private static final Suit TRUMP = Suit.HEART;
    private static final int LEVEL = 3;

    private RoomActor actor;

    @AfterEach
    void tearDown() {
        if (actor != null) {
            actor.shutdown();
        }
    }

    /** 4 家人齐但不开局（不起 bot 驱动），房间状态完全由用例手工摆 */
    private GameRoom newIdleRoom() {
        actor = new RoomActor(ROOM, new InMemoryStateStore());
        actor.join(new HumanPlayer(HUMAN, Seat.SOUTH), false);
        actor.join(new HumanPlayer(2L, Seat.NORTH), true);
        actor.join(new HumanPlayer(EAST_ID, Seat.EAST), true);
        actor.join(new HumanPlayer(WEST_ID, Seat.WEST), true);
        return actor.room();
    }

    private Map<String, Object> snapshot() {
        return JsonUtil.read(actor.snapshotFor(HUMAN), Map.class);
    }

    /** 状态机只允许单步前进，所以沿序列走 */
    private static void advanceTo(GameRoom room, GamePhase target) {
        GamePhase[] order = GamePhase.values();
        while (room.phase() != target) {
            room.transitionTo(order[room.phase().ordinal() + 1]);
        }
    }

    /** 摆一个"随时可扣底"的局面：定主 + 庄家南 + 原底牌 + 庄家原本手牌 */
    private static void arrangeBeforeBury(GameRoom room, List<Card> bottom,
                                         List<Card> bankerHandBeforeTake) {
        advanceTo(room, GamePhase.BIDDING);
        room.setFirstRound(false);                       // 首局豁免干锅，正式规则才判
        room.setTrump(new TrumpContext(LEVEL, TRUMP));
        room.setBankerSeat(Seat.SOUTH);                  // 真人当庄，扣底命令由他发
        room.setBottomCards(bottom);
        Player banker = room.playerAt(Seat.SOUTH);
        banker.hand().clear();
        banker.hand().addAll(bankerHandBeforeTake);
    }

    private static final List<Card> NORMAL_BOTTOM = List.of(
            Card.of(Suit.HEART, 4), Card.of(Suit.SPADE, 6),
            Card.of(Suit.CLUB, 4), Card.of(Suit.CLUB, 6),
            Card.of(Suit.DIAMOND, 4), Card.of(Suit.DIAMOND, 7));

    // ------------------------------------------------------------ ① 没人扣王

    @Test
    void noJokerBuried_allFactsFalse() {
        GameRoom room = newIdleRoom();
        List<Card> handBefore = List.of(
                Card.of(Suit.SPADE, 7), Card.of(Suit.SPADE, 8),
                Card.of(Suit.CLUB, 7), Card.of(Suit.CLUB, 8),
                Card.of(Suit.DIAMOND, 8), Card.of(Suit.DIAMOND, 10),
                Card.of(Suit.HEART, 5));
        arrangeBeforeBury(room, NORMAL_BOTTOM, handBefore);
        advanceTo(room, GamePhase.BURYING);

        CommandResult r = room.apply(new BuryBottomCommand(ROOM, HUMAN, handBefore.subList(0, 6)));
        assertTrue(r.success(), "正常局扣底应被接受: " + r.reason());

        Map<String, Object> snap = snapshot();
        assertEquals(Boolean.FALSE, snap.get("jokerBuried"), "没人扣王 → 「本局是否扣王」必须是假");
        assertEquals(Boolean.FALSE, snap.get("dryPot"), "底牌含 ♥4 → 不是干锅");
        assertEquals(List.of(), snap.get("tributes"), "没有进贡 → 明细为空数组（不是缺字段）");
    }

    // ------------------------------------------------------------ ② 庄家扣王

    @Test
    void bankerBuriesJoker_factIsTrue_andClearedNextRound() {
        GameRoom room = newIdleRoom();
        // 手牌 6 张里带一个小王，其余为非分非王；手里没有大王 → 不触发 Q5 约束
        List<Card> handBefore = List.of(
                Card.smallJoker(), Card.of(Suit.SPADE, 7), Card.of(Suit.SPADE, 8),
                Card.of(Suit.CLUB, 7), Card.of(Suit.CLUB, 8), Card.of(Suit.DIAMOND, 8));
        arrangeBeforeBury(room, NORMAL_BOTTOM, handBefore);
        advanceTo(room, GamePhase.BURYING);

        CommandResult r = room.apply(new BuryBottomCommand(ROOM, HUMAN, handBefore));
        assertTrue(r.success(), "扣王应被接受: " + r.reason());

        Map<String, Object> snap = snapshot();
        assertEquals(Boolean.TRUE, snap.get("jokerBuried"), "庄家把小王扣进底牌 → 本局扣王为真");
        assertEquals(Boolean.TRUE, snap.get("bottomRevealed"), "扣王同时要求底牌公开（2.3.3）");

        // 开新局：这些"本局事实"不能顺着局号带出去
        room.resetRoundState();
        Map<String, Object> next = snapshot();
        assertEquals(Boolean.FALSE, next.get("jokerBuried"), "新局必须清空「本局是否扣王」");
        assertEquals(Boolean.FALSE, next.get("dryPot"), "新局必须清空干锅标记");
    }

    // ---------------------------------------------- ③ 干锅：两个口径在这里分叉

    @Test
    void dryPotWithJokerInBottom_isNotAJokerBury() {
        GameRoom room = newIdleRoom();
        // 底牌带一张小王，其余全是非主花色普通牌 → 干锅（王在干锅判定里被过滤掉）
        List<Card> bottom = List.of(
                Card.smallJoker(), Card.of(Suit.SPADE, 4), Card.of(Suit.SPADE, 6),
                Card.of(Suit.CLUB, 4), Card.of(Suit.CLUB, 6), Card.of(Suit.DIAMOND, 4));
        arrangeBeforeBury(room, bottom, List.of(Card.of(Suit.SPADE, 7)));
        advanceTo(room, GamePhase.BURYING);

        // 干锅局只能原样扣回
        CommandResult r = room.apply(new BuryBottomCommand(ROOM, HUMAN, bottom));
        assertTrue(r.success(), "干锅原样扣回应被接受: " + r.reason());
        assertTrue(room.isDryPot(), "底牌无主花色普通牌 → 干锅");

        Map<String, Object> snap = snapshot();
        assertEquals(Boolean.TRUE, snap.get("dryPot"), "干锅要报给玩家");
        assertEquals(Boolean.FALSE, snap.get("jokerBuried"),
                "干锅底牌里的王是发牌发出来的，没人扣过它们 —— 不能报成「本局扣王」");
        // 对照：可见性字段此刻是**真**（底牌含王）。两个口径就是在这里分叉，
        // 所以面板的「是否扣王」不能拿 bottomRevealed 顶替。
        assertTrue(room.isBottomRevealed(), "对照项：可见性口径此时为真");
    }

    // -------------------------------------------------- ④ 他人捡牌扣王（2.3.5）

    @Test
    void otherPlayerPicksJoker_factTurnsTrue() {
        GameRoom room = newIdleRoom();
        List<Card> buried = List.of(
                Card.of(Suit.SPADE, 7), Card.of(Suit.SPADE, 8),
                Card.of(Suit.CLUB, 7), Card.of(Suit.CLUB, 8),
                Card.of(Suit.DIAMOND, 8), Card.of(Suit.DIAMOND, 9));
        List<Card> handBefore = new ArrayList<>(buried);
        handBefore.add(Card.of(Suit.HEART, 5));
        arrangeBeforeBury(room, NORMAL_BOTTOM, handBefore);
        // 三家都发一张王 → 扣王窗口会真的开起来（东家排第一）
        for (Seat s : List.of(Seat.EAST, Seat.NORTH, Seat.WEST)) {
            room.playerAt(s).hand().add(Card.smallJoker());
        }
        advanceTo(room, GamePhase.BURYING);

        assertTrue(room.apply(new BuryBottomCommand(ROOM, HUMAN, buried)).success());
        Seat picker = room.buryPickSeat();
        assertEquals(Seat.EAST, picker, "庄家南的下家是东，依次询问从东家开始");
        assertEquals(Boolean.FALSE, snapshot().get("jokerBuried"), "还没人扣 → 仍报否");

        CommandResult pick = room.apply(new PickBottomJokerCommand(ROOM, EAST_ID, List.of(Card.smallJoker())));
        assertTrue(pick.success(), "他人捡牌扣王应被接受: " + pick.reason());
        assertEquals(Boolean.TRUE, snapshot().get("jokerBuried"), "有人捡牌扣王 → 本局扣王为真");
    }

    // ------------------------------------------------- ⑤ 进贡 / 还贡明细（含收贡人）

    @Test
    void tributeLogCarriesPayerReceiverAndBothCardSets() {
        GameRoom room = newIdleRoom();
        advanceTo(room, GamePhase.TRIBUTE);
        room.setTrump(new TrumpContext(LEVEL, TRUMP));
        room.setBankerSeat(Seat.SOUTH);
        room.putTributeObligation(Seat.WEST, new TributeObligation(2, Seat.SOUTH));

        Player west = room.playerAt(Seat.WEST);
        west.hand().clear();
        west.hand().addAll(List.of(Card.bigJoker(), Card.smallJoker(), Card.of(Suit.SPADE, 14)));
        List<Card> back = List.of(Card.of(Suit.HEART, 13), Card.of(Suit.CLUB, 7));
        Player south = room.playerAt(Seat.SOUTH);
        south.hand().clear();
        south.hand().addAll(back);

        // 还没交贡：明细为空，但"欠着 2 张血"必须在 pendingTributes 里
        Map<String, Object> before = snapshot();
        assertEquals(List.of(), before.get("tributes"), "没人交贡 → 明细为空");
        Map<?, ?> pending = (Map<?, ?>) before.get("pendingTributes");
        assertNotNull(pending.get("WEST"), "待进贡义务仍走 pendingTributes");

        List<Card> paid = List.of(Card.bigJoker(), Card.smallJoker());
        assertTrue(room.apply(new TributeCommand(ROOM, WEST_ID, paid)).success());

        Map<String, Object> afterPay = snapshot();
        List<?> log = (List<?>) afterPay.get("tributes");
        assertEquals(1, log.size(), "一笔进贡 → 一条流水");
        Map<?, ?> entry = (Map<?, ?>) log.get(0);
        assertEquals("WEST", entry.get("payer"));
        assertEquals("SOUTH", entry.get("receiver"), "收贡人必须随流水下发（面板要讲「谁贡给谁」）");
        assertEquals(CardCodec.encodeAll(paid), entry.get("cards"));
        assertFalse(entry.containsKey("returned"), "还没还贡 → 不带 returned 字段");

        assertTrue(room.apply(new ReturnTributeCommand(ROOM, HUMAN, Seat.WEST, back)).success());

        Map<String, Object> afterReturn = snapshot();
        Map<?, ?> entry2 = (Map<?, ?>) ((List<?>) afterReturn.get("tributes")).get(0);
        assertEquals(CardCodec.encodeAll(back), entry2.get("returned"),
                "还贡的牌面要随流水下发（面板要显示还的是哪几张）");

        // 开新局：上一局的进贡流水不能留在快照里
        room.resetRoundState();
        assertEquals(List.of(), snapshot().get("tributes"), "新局必须清空进贡明细");
    }
}
