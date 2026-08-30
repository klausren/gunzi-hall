package com.gunzihall.room;

import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.play.Combo;
import com.gunzihall.domain.play.Trick;
import com.gunzihall.domain.player.Seat;
import com.gunzihall.domain.room.GameRoom;
import com.gunzihall.domain.trump.TrumpContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.gunzihall.domain.card.Suit.CLUB;
import static com.gunzihall.domain.card.Suit.DIAMOND;
import static com.gunzihall.domain.card.Suit.HEART;
import static com.gunzihall.domain.card.Suit.SPADE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BotBrain v2 抢墩策略的行为级测试：不测实现细节，只测关键局面下的决策方向。
 *
 * <p>场景统一设定：打 3，主 = 黑桃（♠）。SOUTH(bot) 是被测者。
 * 对照组是 v1 行为：跟牌永远选"动用主牌最少、最强牌最弱"——从不抢墩。
 */
class BotBrainV2Test {

    private final TrumpContext trump = new TrumpContext(3, SPADE);

    /** 搭一个最小房间：四家入座、发指定手牌、设庄、摆一个进行中的墩 */
    private GameRoom room(Trick trick, Map<Seat, List<Card>> hands, Seat banker) {
        GameRoom room = new GameRoom(1001);
        for (Seat seat : Seat.values()) {
            BotPlayer p = new BotPlayer(9000 + seat.index(), seat);
            p.hand().addAll(hands.get(seat));
            room.sitDown(p);
        }
        room.setTrump(trump);
        room.setBankerSeat(banker);
        room.setCurrentTrick(trick);
        return room;
    }

    private BotPlayer south(GameRoom room) {
        return (BotPlayer) room.playerAt(Seat.SOUTH);
    }

    private Combo combo(Card c) {
        return Combo.parse(List.of(c), trump).orElseThrow();
    }

    // 场景 1：对手领出 10 分，我持 ♥A 和 ♥5 → 必须出 ♥A 抢墩
    //（v1 会出 ♥5 放水，眼睁睁看着 10 分被对手收走）
    @Test
    void opponentLeadsPoints_botOvertakes() {
        Card led = Card.of(HEART, 10);
        Trick trick = new Trick(Seat.WEST, combo(led), trump);
        GameRoom room = room(trick, Map.of(
                Seat.WEST, List.of(led),
                Seat.SOUTH, List.of(Card.of(HEART, 14), Card.of(HEART, 5), Card.of(CLUB, 4)),
                Seat.NORTH, List.of(Card.of(DIAMOND, 7)),
                Seat.EAST, List.of(Card.of(DIAMOND, 8))
        ), Seat.SOUTH);

        List<Card> play = BotBrain.followPlay(room, south(room));
        assertEquals(List.of(Card.of(HEART, 14)), play, "对手领 10 分且我持 ♥A：应出 ♥A 抢墩（v1 会垫 ♥5）");
    }

    // 场景 2：对家（搭档）领出 ♦A 必大 → 我跟最小的 ♥，拆都不拆 K
    @Test
    void partnerLeadsStrong_botPlaysCheapest() {
        Card led = Card.of(DIAMOND, 14);
        Trick trick = new Trick(Seat.NORTH, combo(led), trump);
        GameRoom room = room(trick, Map.of(
                Seat.NORTH, List.of(led),
                Seat.SOUTH, List.of(Card.of(DIAMOND, 13), Card.of(DIAMOND, 5), Card.of(CLUB, 4)),
                Seat.EAST, List.of(Card.of(CLUB, 8)),
                Seat.WEST, List.of(Card.of(CLUB, 9))
        ), Seat.SOUTH);

        List<Card> play = BotBrain.followPlay(room, south(room));
        assertEquals(List.of(Card.of(DIAMOND, 5)), play, "搭档 ♦A 领先：应跟最小的 ♦5 保 ♦K 资产");
    }

    // 场景 3：末家、对手领先、0 分墩 → 用便宜牌拿领出权，绝不动王
    @Test
    void lastSeatZeroPoints_botNeverSpendsJoker() {
        Card led = Card.of(CLUB, 7);
        Trick trick = new Trick(Seat.WEST, combo(led), trump);
        trick.play(Seat.NORTH, List.of(Card.of(DIAMOND, 4)));  // 搭档垫牌
        trick.play(Seat.EAST, List.of(Card.of(CLUB, 9)));      // 对手 ♣9 领先（0 分墩）
        GameRoom room = room(trick, Map.of(
                Seat.WEST, List.of(led),
                Seat.NORTH, List.of(Card.of(DIAMOND, 4)),
                Seat.EAST, List.of(Card.of(CLUB, 9)),
                Seat.SOUTH, List.of(Card.bigJoker(), Card.of(CLUB, 10), Card.of(CLUB, 4))
        ), Seat.SOUTH);

        List<Card> play = BotBrain.followPlay(room, south(room));
        assertFalse(play.contains(Card.bigJoker()), "0 分墩末家绝不动王");
        assertEquals(List.of(Card.of(CLUB, 10)), play, "应用 ♣10 拿领出权");
    }

    // 场景 4：高分局末家，对手领先 → 值得动强主收分（资产上限随墩分升级）
    @Test
    void lastSeatBigPoints_botSpendsStrongTrump() {
        Card led = Card.of(CLUB, 10);   // 领出 10 分
        Trick trick = new Trick(Seat.WEST, combo(led), trump);
        trick.play(Seat.NORTH, List.of(Card.of(DIAMOND, 5)));  // 搭档垫 5 分 → 墩内 15 分
        trick.play(Seat.EAST, List.of(Card.of(CLUB, 14)));     // 对手 ♥A？不，♣A 领先
        GameRoom room = room(trick, Map.of(
                Seat.WEST, List.of(led),
                Seat.NORTH, List.of(Card.of(DIAMOND, 5)),
                Seat.EAST, List.of(Card.of(CLUB, 14)),
                // 手里没梅花了：可以用主牌杀。15 分墩 → 允许动到王
                Seat.SOUTH, List.of(Card.bigJoker(), Card.of(HEART, 4), Card.of(DIAMOND, 8))
        ), Seat.SOUTH);

        List<Card> play = BotBrain.followPlay(room, south(room));
        assertTrue(play.contains(Card.bigJoker()), "15 分墩对手领先：末家应用大王杀收分");
    }

    // 场景 5：领出 → 持主牌棒优先领出（清主抢墩）
    @Test
    void lead_prefersTrumpPair() {
        GameRoom room = new GameRoom(1001);
        BotPlayer south = new BotPlayer(9002, Seat.SOUTH);
        south.hand().addAll(List.of(
                Card.of(SPADE, 9), Card.of(SPADE, 9),          // 主牌棒
                Card.of(HEART, 13), Card.of(HEART, 13),        // 副牌 K 棒
                Card.of(CLUB, 4), Card.of(DIAMOND, 5)));
        room.sitDown(south);
        room.setTrump(trump);
        room.setBankerSeat(Seat.SOUTH);

        List<Card> lead = BotBrain.leadPlay(room, south);
        assertEquals(List.of(Card.of(SPADE, 9), Card.of(SPADE, 9)),
                lead, "持主牌棒应优先领出清主");
    }

    // 场景 6：进贡牌不喂分（沿用 v1 逻辑回归）
    @Test
    void tribute_neverPaysPoints() {
        GameRoom room = new GameRoom(1001);
        BotPlayer south = new BotPlayer(9002, Seat.SOUTH);
        south.hand().addAll(List.of(
                Card.of(SPADE, 14),          // 主 A（最强非分）
                Card.of(HEART, 10),          // 10 分
                Card.of(CLUB, 5)));          // 5 分
        room.sitDown(south);
        room.setTrump(trump);
        room.setBankerSeat(Seat.NORTH);

        List<Card> tribute = BotBrain.tributeCards(room, south, 1);
        assertEquals(List.of(Card.of(SPADE, 14)), tribute, "进贡应给最大的非分牌（主 A）");
    }
}
