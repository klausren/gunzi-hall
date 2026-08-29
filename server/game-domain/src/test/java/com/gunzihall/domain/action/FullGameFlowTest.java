package com.gunzihall.domain.action;

import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.card.Suit;
import com.gunzihall.domain.player.Player;
import com.gunzihall.domain.player.Seat;
import com.gunzihall.domain.room.GamePhase;
import com.gunzihall.domain.room.GameRoom;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 3 验收：完整两局连打。
 *
 * <p>第一局（级数 3）：北家抢亮大王定主 ♥ → 扣底 → 受控手牌打两圈 → 结算
 * （庄家方保底、抓分方 10 分 → 庄家方升级 3→4，抓分方 7 血进贡）。
 * <p>第二局（级数 4）：发牌 → 东家亮 2 张 ♠4 定主 ♠ → 西家（庄家上家）进贡 7 张
 * 最大非分牌 → 北家还贡 → 扣底 → 打一圈 → 结算（B 队抠底 30 分，未过线庄家留任）。
 *
 * <p>注意受控手牌避免使用级数牌（第一局 3、第二局 4）以外的坑：级牌 tier 高于主花色普通牌，
 * 赢圈判定会反转。
 */
class FullGameFlowTest {

    private final GameRoom room = RevealTrumpCommandTest.fullRoom();

    private Player at(Seat seat) {
        return RevealTrumpCommandTest.playerOf(room, seat);
    }

    private long pid(Seat seat) {
        return at(seat).playerId();
    }

    @Test
    void twoConsecutiveGamesWithTribute() {
        // ================= 第一局（级数 3，主 ♥） =================
        room.apply(new ShuffleAndDealCommand(1001L, 1L, 42L));
        assertEquals(GamePhase.BIDDING, room.phase());

        // 北家抢亮大王，主花色 ♥
        at(Seat.NORTH).hand().add(Card.bigJoker());
        assertTrue(room.apply(new RevealTrumpCommand(1001L, pid(Seat.NORTH),
                List.of(Card.bigJoker()), Suit.HEART)).success());
        assertEquals(Seat.NORTH, room.bankerSeat().orElseThrow());

        // 确认亮主：首局无进贡 → 跳过 TRIBUTE 直接扣底
        assertTrue(room.apply(new ConfirmTrumpCommand(1001L, pid(Seat.EAST))).success());
        assertEquals(GamePhase.BURYING, room.phase());

        // 受控手牌（每家 2 张；避免级数 3 的牌）
        setHand(Seat.NORTH, Card.of(Suit.HEART, 4), Card.of(Suit.HEART, 6));
        setHand(Seat.EAST, Card.of(Suit.HEART, 7), Card.of(Suit.CLUB, 9));
        setHand(Seat.SOUTH, Card.of(Suit.CLUB, 5), Card.of(Suit.CLUB, 8));
        setHand(Seat.WEST, Card.of(Suit.DIAMOND, 5), Card.of(Suit.DIAMOND, 9));

        // 底牌（♥9 保证非干锅；分值 = K 10 分）
        var bottom1 = List.of(Card.of(Suit.HEART, 9), Card.of(Suit.CLUB, 4),
                Card.of(Suit.CLUB, 8), Card.of(Suit.DIAMOND, 3),
                Card.of(Suit.SPADE, 11), Card.of(Suit.SPADE, 13));
        room.setBottomCards(bottom1);
        assertTrue(room.apply(new BuryBottomCommand(1001L, pid(Seat.NORTH), bottom1)).success());
        assertEquals(GamePhase.PLAYING, room.phase());
        assertEquals(Seat.NORTH, room.turnSeat().orElseThrow());

        // ---- 第 1 圈：北 ♥6 首出，东 ♥7 压（都是主），南西垫分牌 ----
        play(Seat.NORTH, Card.of(Suit.HEART, 6));
        play(Seat.EAST, Card.of(Suit.HEART, 7));
        play(Seat.SOUTH, Card.of(Suit.CLUB, 5));
        play(Seat.WEST, Card.of(Suit.DIAMOND, 5));
        assertEquals(Seat.EAST, room.turnSeat().orElseThrow(), "♥7 赢 → 东领出下一圈");

        // ---- 第 2 圈：东 ♣9 首出，南 ♣8 跟，西垫 ♦9，北 ♥4 主杀 ----
        play(Seat.EAST, Card.of(Suit.CLUB, 9));
        play(Seat.SOUTH, Card.of(Suit.CLUB, 8));
        play(Seat.WEST, Card.of(Suit.DIAMOND, 9));
        play(Seat.NORTH, Card.of(Suit.HEART, 4));
        assertEquals(GamePhase.SETTLING, room.phase(), "手牌打空 → 结算；北（A 队）赢最后一圈 = 保底");

        // ---- 结算：B 队 10 分（♣5+♦5）< 80 → 庄家方升级，B 队进贡 ceil(70/10)=7 血 ----
        assertTrue(room.apply(new SettleRoundCommand(1001L, pid(Seat.EAST))).success());
        var s1 = room.lastSettlement().orElseThrow();
        assertEquals(10, s1.attackerScore());
        assertEquals(20, s1.bankerScore(), "底牌 K 10 分 ×2");
        assertTrue(s1.bankerPromoted());
        assertEquals(4, room.currentLevel(), "3 → 4 级");
        assertEquals(Seat.NORTH, room.bankerSeat().orElseThrow(), "庄家留任");
        assertEquals(GamePhase.DEALING, room.phase(), "未出锅开新局");

        // ================= 第二局（级数 4，主 ♠） =================
        assertTrue(room.apply(new ShuffleAndDealCommand(1001L, 1L, 99L)).success());
        assertEquals(GamePhase.BIDDING, room.phase());

        // 西家进贡义务：7 血（分差），收贡人 = 庄家北
        var ob = room.pendingTributes().get(Seat.WEST);
        assertEquals(7, ob.bloodCount());
        assertEquals(Seat.NORTH, ob.receiver());

        // 东家亮 2 张 ♠4 反主窗口内定主 ♠（级数已推进为 4）
        at(Seat.EAST).hand().add(Card.of(Suit.SPADE, 4));
        at(Seat.EAST).hand().add(Card.of(Suit.SPADE, 4));
        assertTrue(room.apply(new RevealTrumpCommand(1001L, pid(Seat.EAST),
                List.of(Card.of(Suit.SPADE, 4), Card.of(Suit.SPADE, 4)), Suit.SPADE)).success());
        assertEquals(Suit.SPADE, room.trump().orElseThrow().trumpSuit());
        assertEquals(4, room.trump().orElseThrow().level());

        // 确认 → 有进贡义务 → TRIBUTE
        assertTrue(room.apply(new ConfirmTrumpCommand(1001L, pid(Seat.SOUTH))).success());
        assertEquals(GamePhase.TRIBUTE, room.phase());

        // 西家受控手牌：最大非分牌 7 张（K 是分牌不贡；5 分牌不贡）
        var tributeCards = List.of(Card.bigJoker(), Card.smallJoker(),
                Card.of(Suit.SPADE, 14), Card.of(Suit.HEART, 14),
                Card.of(Suit.CLUB, 14), Card.of(Suit.DIAMOND, 14), Card.of(Suit.CLUB, 7));
        setHand(Seat.WEST, tributeCards.toArray(new Card[0]));
        at(Seat.WEST).hand().add(Card.of(Suit.HEART, 5)); // 分牌留在手里
        assertTrue(room.apply(new TributeCommand(1001L, pid(Seat.WEST), tributeCards)).success(),
                "交 7 张最大非分牌");

        // 北家还贡（把收到的贡牌原样还回）
        assertTrue(room.apply(new ReturnTributeCommand(1001L, pid(Seat.NORTH),
                Seat.WEST, tributeCards)).success());
        assertEquals(GamePhase.BURYING, room.phase());

        // 庄家扣底（♠7/♠9 保证非干锅；底牌分值 = ♣5 + ♣10 = 15）
        var bottom2 = List.of(Card.of(Suit.SPADE, 7), Card.of(Suit.SPADE, 9),
                Card.of(Suit.CLUB, 5), Card.of(Suit.CLUB, 10),
                Card.of(Suit.DIAMOND, 3), Card.of(Suit.HEART, 11));
        room.setBottomCards(bottom2);
        assertTrue(room.apply(new BuryBottomCommand(1001L, pid(Seat.NORTH), bottom2)).success());
        assertEquals(GamePhase.PLAYING, room.phase());
        assertEquals(2, room.gameNumber(), "局数推进");

        // 第二局打一圈（每家 1 张）：北 ♠6 首出，东 ♠8 压，南西垫 → 东（B 队）赢 = 抠底
        setHand(Seat.NORTH, Card.of(Suit.SPADE, 6));
        setHand(Seat.EAST, Card.of(Suit.SPADE, 8));
        setHand(Seat.SOUTH, Card.of(Suit.CLUB, 6));
        setHand(Seat.WEST, Card.of(Suit.HEART, 8));
        play(Seat.NORTH, Card.of(Suit.SPADE, 6));
        play(Seat.EAST, Card.of(Suit.SPADE, 8));
        play(Seat.SOUTH, Card.of(Suit.CLUB, 6));
        play(Seat.WEST, Card.of(Suit.HEART, 8));
        assertEquals(GamePhase.SETTLING, room.phase());

        assertTrue(room.apply(new SettleRoundCommand(1001L, pid(Seat.SOUTH))).success());
        var s2 = room.lastSettlement().orElseThrow();
        assertEquals(30, s2.attackerScore(), "圈分 0 + 底牌 15 分 ×2 抠底");
        assertTrue(s2.dugBottom());
        assertFalse(s2.attackerPromoted(), "抠底但 30 分未过 120 线 → 不升级");
        assertEquals(GamePhase.DEALING, room.phase(), "未过线庄家留任继续下一局");
        assertEquals(Seat.NORTH, room.bankerSeat().orElseThrow());
        assertEquals(4, room.currentLevel(), "无人升级，级数不变");
    }

    // ---- 辅助 ----

    private void setHand(Seat seat, Card... cards) {
        Player p = at(seat);
        p.hand().clear();
        p.hand().addAll(List.of(cards));
    }

    private void play(Seat seat, Card card) {
        var r = room.apply(new PlayCardsCommand(1001L, pid(seat), List.of(card)));
        assertTrue(r.success(), seat + " 出 " + card + " 被拒: " + r.reason());
    }
}
