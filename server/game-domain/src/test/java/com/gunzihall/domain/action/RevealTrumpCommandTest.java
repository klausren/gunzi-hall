package com.gunzihall.domain.action;

import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.card.Suit;
import com.gunzihall.domain.player.HumanPlayer;
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
 * 亮王 / 反主命令测试（手册 2.2，Q2/Q2b/Q2c）。
 */
class RevealTrumpCommandTest {

    private final GameRoom room = fullRoom();

    static GameRoom fullRoom() {
        GameRoom room = new GameRoom(1001L);
        room.sitDown(new HumanPlayer(1L, Seat.NORTH));
        room.sitDown(new HumanPlayer(2L, Seat.EAST));
        room.sitDown(new HumanPlayer(3L, Seat.SOUTH));
        room.sitDown(new HumanPlayer(4L, Seat.WEST));
        return room;
    }

    static Player playerOf(GameRoom room, Seat seat) {
        return room.players().values().stream()
                .filter(p -> p.seat() == seat).findFirst().orElseThrow();
    }

    /** 发牌并把指定座位的亮牌候选塞进手牌（测试可控） */
    static void dealAndPrep(GameRoom room) {
        room.apply(new ShuffleAndDealCommand(1001L, 1L, 42L));
        assertEquals(GamePhase.BIDDING, room.phase());
    }

    private static void addToHand(Player player, Card card) {
        player.hand().add(card);
    }

    // ---- 第一局（Q2） ----

    @Test
    void firstRoundBigJokerClaimsBankAndSuit() {
        dealAndPrep(room);
        Player north = playerOf(room, Seat.NORTH);
        addToHand(north, Card.bigJoker());

        var cmd = new RevealTrumpCommand(1001L, 1L, List.of(Card.bigJoker()), Suit.HEART);
        assertTrue(room.apply(cmd).success());
        assertEquals(Suit.HEART, room.trump().orElseThrow().trumpSuit());
        assertEquals(3, room.trump().orElseThrow().level());
        assertEquals(Seat.NORTH, room.bankerSeat().orElseThrow());
        // 亮出的牌不离手
        assertTrue(north.hand().contains(Card.bigJoker()));
    }

    @Test
    void firstRoundCannotBeOverridden() {
        dealAndPrep(room);
        addToHand(playerOf(room, Seat.NORTH), Card.bigJoker());
        assertTrue(room.apply(new RevealTrumpCommand(1001L, 1L,
                List.of(Card.bigJoker()), Suit.HEART)).success());

        // 东家也想亮大王 → 被拒
        addToHand(playerOf(room, Seat.EAST), Card.bigJoker());
        var r = room.apply(new RevealTrumpCommand(1001L, 2L,
                List.of(Card.bigJoker()), Suit.SPADE));
        assertTrue(r.isFailure());
        assertEquals(Suit.HEART, room.trump().orElseThrow().trumpSuit());
    }

    @Test
    void firstRoundMustBeSingleBigJoker() {
        dealAndPrep(room);
        Player north = playerOf(room, Seat.NORTH);
        addToHand(north, Card.smallJoker());
        var r = room.apply(new RevealTrumpCommand(1001L, 1L,
                List.of(Card.smallJoker()), Suit.HEART));
        assertTrue(r.isFailure());
        assertTrue(r.reason().contains("大王"));
    }

    // ---- 第二局起：级牌亮/反（Q2b） ----

    @Test
    void laterRoundPairLevelCardsOverrideSingle() {
        laterRoundPrep();
        Player north = playerOf(room, Seat.NORTH);
        addToHand(north, Card.of(Suit.SPADE, 6));
        assertTrue(room.apply(new RevealTrumpCommand(1001L, 1L,
                List.of(Card.of(Suit.SPADE, 6)), Suit.SPADE)).success());

        // 东家 2 张 ♥6 反 1 张 ♠6
        Player east = playerOf(room, Seat.EAST);
        addToHand(east, Card.of(Suit.HEART, 6));
        addToHand(east, Card.of(Suit.HEART, 6));
        assertTrue(room.apply(new RevealTrumpCommand(1001L, 2L,
                List.of(Card.of(Suit.HEART, 6), Card.of(Suit.HEART, 6)), Suit.HEART)).success());
        assertEquals(Suit.HEART, room.trump().orElseThrow().trumpSuit());
    }

    @Test
    void laterRoundTripleOverridesPairOrSingle() {
        laterRoundPrep();
        Player north = playerOf(room, Seat.NORTH);
        addToHand(north, Card.of(Suit.SPADE, 6));
        assertTrue(room.apply(new RevealTrumpCommand(1001L, 1L,
                List.of(Card.of(Suit.SPADE, 6)), Suit.SPADE)).success());

        Player south = playerOf(room, Seat.SOUTH);
        for (int i = 0; i < 3; i++) {
            addToHand(south, Card.of(Suit.CLUB, 6));
        }
        assertTrue(room.apply(new RevealTrumpCommand(1001L, 3L,
                List.of(Card.of(Suit.CLUB, 6), Card.of(Suit.CLUB, 6), Card.of(Suit.CLUB, 6)),
                Suit.CLUB)).success());
        assertEquals(Suit.CLUB, room.trump().orElseThrow().trumpSuit());
    }

    @Test
    void laterRoundSingleCannotOverridePair() {
        laterRoundPrep();
        Player east = playerOf(room, Seat.EAST);
        addToHand(east, Card.of(Suit.HEART, 6));
        addToHand(east, Card.of(Suit.HEART, 6));
        assertTrue(room.apply(new RevealTrumpCommand(1001L, 2L,
                List.of(Card.of(Suit.HEART, 6), Card.of(Suit.HEART, 6)), Suit.HEART)).success());

        Player north = playerOf(room, Seat.NORTH);
        addToHand(north, Card.of(Suit.SPADE, 6));
        var r = room.apply(new RevealTrumpCommand(1001L, 1L,
                List.of(Card.of(Suit.SPADE, 6)), Suit.SPADE));
        assertTrue(r.isFailure());
        assertEquals(Suit.HEART, room.trump().orElseThrow().trumpSuit());
    }

    // ---- 第二局起：三小王 / 三大王（Q2c） ----

    @Test
    void tripleSmallJokerOverridesLevelCards() {
        laterRoundPrep();
        Player north = playerOf(room, Seat.NORTH);
        addToHand(north, Card.of(Suit.SPADE, 6));
        assertTrue(room.apply(new RevealTrumpCommand(1001L, 1L,
                List.of(Card.of(Suit.SPADE, 6)), Suit.SPADE)).success());

        Player east = playerOf(room, Seat.EAST);
        for (int i = 0; i < 3; i++) {
            addToHand(east, Card.smallJoker());
        }
        assertTrue(room.apply(new RevealTrumpCommand(1001L, 2L,
                List.of(Card.smallJoker(), Card.smallJoker(), Card.smallJoker()),
                Suit.DIAMOND)).success());
        assertEquals(Suit.DIAMOND, room.trump().orElseThrow().trumpSuit());
    }

    @Test
    void tripleBigJokerOverridesTripleSmallJokerButNotViceVersa() {
        laterRoundPrep();
        Player east = playerOf(room, Seat.EAST);
        for (int i = 0; i < 3; i++) {
            addToHand(east, Card.smallJoker());
        }
        assertTrue(room.apply(new RevealTrumpCommand(1001L, 2L,
                List.of(Card.smallJoker(), Card.smallJoker(), Card.smallJoker()),
                Suit.DIAMOND)).success());

        // 3 大王推翻 3 小王
        Player south = playerOf(room, Seat.SOUTH);
        for (int i = 0; i < 3; i++) {
            addToHand(south, Card.bigJoker());
        }
        assertTrue(room.apply(new RevealTrumpCommand(1001L, 3L,
                List.of(Card.bigJoker(), Card.bigJoker(), Card.bigJoker()),
                Suit.CLUB)).success());
        assertEquals(Suit.CLUB, room.trump().orElseThrow().trumpSuit());

        // 3 小王不能推翻 3 大王（东家已无小王，构造西家场景直接验证被拒）
        Player west = playerOf(room, Seat.WEST);
        for (int i = 0; i < 3; i++) {
            addToHand(west, Card.smallJoker());
        }
        var r = room.apply(new RevealTrumpCommand(1001L, 4L,
                List.of(Card.smallJoker(), Card.smallJoker(), Card.smallJoker()),
                Suit.HEART));
        assertTrue(r.isFailure());
        assertEquals(Suit.CLUB, room.trump().orElseThrow().trumpSuit());
    }

    @Test
    void jokersMustBeSameKindAndThree() {
        laterRoundPrep();
        Player north = playerOf(room, Seat.NORTH);
        addToHand(north, Card.bigJoker());
        addToHand(north, Card.smallJoker());
        addToHand(north, Card.smallJoker());
        var r = room.apply(new RevealTrumpCommand(1001L, 1L,
                List.of(Card.bigJoker(), Card.smallJoker(), Card.smallJoker()), Suit.HEART));
        assertTrue(r.isFailure());
    }

    // ---- 阶段约束 ----

    @Test
    void revealForbiddenOutsideBidding() {
        dealAndPrep(room);
        room.transitionTo(GamePhase.TRIBUTE); // 亮主窗口关闭后（进贡阶段）
        Player north = playerOf(room, Seat.NORTH);
        addToHand(north, Card.bigJoker());
        var r = room.apply(new RevealTrumpCommand(1001L, 1L,
                List.of(Card.bigJoker()), Suit.HEART));
        assertTrue(r.isFailure());
        assertTrue(r.reason().contains("亮主"));
    }

    @Test
    void confirmTrumpAdvancesToBuryingWhenNoTribute() {
        dealAndPrep(room);
        addToHand(playerOf(room, Seat.NORTH), Card.bigJoker());
        assertTrue(room.apply(new RevealTrumpCommand(1001L, 1L,
                List.of(Card.bigJoker()), Suit.HEART)).success());

        assertTrue(room.apply(new ConfirmTrumpCommand(1001L, 2L)).success());
        assertEquals(GamePhase.BURYING, room.phase());
    }

    @Test
    void confirmTrumpRejectedWhenNobodyRevealed() {
        dealAndPrep(room);
        var r = room.apply(new ConfirmTrumpCommand(1001L, 1L));
        assertTrue(r.isFailure());
    }

    /** 构造第二局场景：级数 6、庄家南、firstRound=false、阶段 BIDDING */
    private void laterRoundPrep() {
        room.apply(new ShuffleAndDealCommand(1001L, 1L, 42L));
        room.setCurrentLevel(6);
        room.setFirstRound(false);
        room.setBankerSeat(Seat.SOUTH);
        assertEquals(GamePhase.BIDDING, room.phase());
    }
}
