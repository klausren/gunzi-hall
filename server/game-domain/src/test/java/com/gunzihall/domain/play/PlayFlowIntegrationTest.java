package com.gunzihall.domain.play;

import com.gunzihall.domain.action.CommandResult;
import com.gunzihall.domain.action.PlayCardsCommand;
import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.card.Suit;
import com.gunzihall.domain.player.HumanPlayer;
import com.gunzihall.domain.player.Seat;
import com.gunzihall.domain.player.Team;
import com.gunzihall.domain.room.GamePhase;
import com.gunzihall.domain.room.GameRoom;
import com.gunzihall.domain.trump.TrumpContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 规则引擎与命令模式的集成测试：完整一圈出牌流转、活/死棒开关、
 * 轮次赢家收分、手牌打空进入结算。
 * <p>固定上下文：级数 5，主花色 ♠；用极简手牌构造确定性场景。
 */
class PlayFlowIntegrationTest {

    private final TrumpContext ctx = new TrumpContext(5, Suit.SPADE);

    /** 构造一个 PLAYING 阶段的房间，并给四家发指定手牌（命令层读写 player.hand()） */
    private GameRoom playingRoom(List<Card> north, List<Card> east,
                                 List<Card> south, List<Card> west) {
        GameRoom room = new GameRoom(1001L);
        room.sitDown(new HumanPlayer(1L, Seat.NORTH));
        room.sitDown(new HumanPlayer(2L, Seat.EAST));
        room.sitDown(new HumanPlayer(3L, Seat.SOUTH));
        room.sitDown(new HumanPlayer(4L, Seat.WEST));
        room.transitionTo(GamePhase.DEALING);
        room.transitionTo(GamePhase.BIDDING);
        room.transitionTo(GamePhase.TRIBUTE);
        room.transitionTo(GamePhase.BURYING);
        room.transitionTo(GamePhase.PLAYING);
        room.setTrump(ctx);
        room.setTurnSeat(Seat.NORTH);
        room.playerAt(Seat.NORTH).hand().addAll(north);
        room.playerAt(Seat.EAST).hand().addAll(east);
        room.playerAt(Seat.SOUTH).hand().addAll(south);
        room.playerAt(Seat.WEST).hand().addAll(west);
        return room;
    }

    private boolean play(GameRoom room, long playerId, List<Card> cards) {
        return room.apply(new PlayCardsCommand(1001L, playerId, cards)).success();
    }

    @Test
    void fullTrickCollectsPointsAndWinnerLeadsNext() {
        GameRoom room = playingRoom(
                List.of(Card.of(Suit.HEART, 7), Card.of(Suit.CLUB, 3)),   // 北：♥7 + ♣3
                List.of(Card.of(Suit.HEART, 13), Card.of(Suit.CLUB, 4)),  // 东：♥K + ♣4
                List.of(Card.of(Suit.HEART, 10), Card.of(Suit.CLUB, 5)),  // 南：♥10 + ♣5
                List.of(Card.of(Suit.CLUB, 6), Card.of(Suit.CLUB, 7)));   // 西：无 ♥

        assertTrue(play(room, 1L, List.of(Card.of(Suit.HEART, 7))));
        assertTrue(play(room, 2L, List.of(Card.of(Suit.HEART, 13))));
        assertTrue(play(room, 3L, List.of(Card.of(Suit.HEART, 10))));
        assertTrue(play(room, 4L, List.of(Card.of(Suit.CLUB, 6)))); // 西无 ♥，垫牌合法

        // 一圈结束：♥K 最大 → 东家赢；本圈分牌 ♥K(10) + ♥10(10) = 20 分归东家所在 B 队
        assertEquals(20, room.trickPoints().getOrDefault(Team.B, 0));
        assertTrue(room.currentTrick().isEmpty(), "一圈结束清空");
        assertEquals(Seat.EAST, room.turnSeat().orElseThrow(), "赢家先出下一圈");
        assertEquals(Team.B, room.lastTrickWinnerTeam().orElseThrow());
        assertEquals(GamePhase.PLAYING, room.phase(), "还有手牌未打空");

        // 第二圈由赢家东家先出
        assertTrue(play(room, 2L, List.of(Card.of(Suit.CLUB, 4))));
    }

    @Test
    void outOfTurnRejected() {
        GameRoom room = playingRoom(
                List.of(Card.of(Suit.HEART, 7)),
                List.of(Card.of(Suit.HEART, 13)),
                List.of(Card.of(Suit.HEART, 10)),
                List.of(Card.of(Suit.CLUB, 6)));

        assertTrue(play(room, 1L, List.of(Card.of(Suit.HEART, 7))));
        assertFalse(play(room, 3L, List.of(Card.of(Suit.HEART, 10))), "没轮到南家");
        assertTrue(play(room, 2L, List.of(Card.of(Suit.HEART, 13))));
    }

    @Test
    void leadingInvalidComboRejected() {
        GameRoom room = playingRoom(
                List.of(Card.of(Suit.HEART, 7), Card.of(Suit.HEART, 8)), // 顺子不合法
                List.of(Card.of(Suit.CLUB, 9)),
                List.of(Card.of(Suit.CLUB, 10)),
                List.of(Card.of(Suit.CLUB, 11)));

        CommandResult r = room.apply(new PlayCardsCommand(1001L, 1L,
                List.of(Card.of(Suit.HEART, 7), Card.of(Suit.HEART, 8))));
        assertTrue(r.isFailure());
        assertTrue(r.reason() != null && r.reason().contains("牌型不合法"));
    }

    @Test
    void liveRuleAllowsUnmatchedFollow() {
        GameRoom room = playingRoom(
                List.of(Card.of(Suit.HEART, 8), Card.of(Suit.HEART, 8)),   // 北首出棒子
                List.of(Card.of(Suit.HEART, 3), Card.of(Suit.HEART, 9)),   // 东：两张 ♥ 单张
                List.of(Card.of(Suit.CLUB, 4), Card.of(Suit.CLUB, 5)),     // 南：垫牌
                List.of(Card.smallJoker(), Card.of(Suit.CLUB, 6)));        // 西：单王+垫

        assertTrue(play(room, 1L, List.of(Card.of(Suit.HEART, 8), Card.of(Suit.HEART, 8))));
        // 活棒：东家两张单张合法（跟了花色即可，不要求凑棒）
        assertTrue(play(room, 2L, List.of(Card.of(Suit.HEART, 3), Card.of(Suit.HEART, 9))));
        // 南家无 ♥，垫两张 ♣ 合法
        assertTrue(play(room, 3L, List.of(Card.of(Suit.CLUB, 4), Card.of(Suit.CLUB, 5))));
        // 西家无 ♥：单王 + ♣6 混搭（活棒不要求凑型）
        assertTrue(play(room, 4L, List.of(Card.smallJoker(), Card.of(Suit.CLUB, 6))));
        // 只有北家的棒子构成可竞逐牌型 → 北家赢下一圈先出
        assertEquals(Seat.NORTH, room.turnSeat().orElseThrow());
    }

    @Test
    void liveRuleStillEnforcesSuitFollow() {
        GameRoom room = playingRoom(
                List.of(Card.of(Suit.HEART, 8), Card.of(Suit.HEART, 8)),
                List.of(Card.of(Suit.HEART, 3), Card.of(Suit.CLUB, 9),
                        Card.of(Suit.DIAMOND, 11)),   // 东有一张 ♥
                List.of(Card.of(Suit.CLUB, 4), Card.of(Suit.CLUB, 5)),
                List.of(Card.of(Suit.CLUB, 6), Card.of(Suit.CLUB, 7)));

        assertTrue(play(room, 1L, List.of(Card.of(Suit.HEART, 8), Card.of(Suit.HEART, 8))));
        // 东家唯一的 ♥ 必须跟出，两张全垫副牌被拒
        assertFalse(play(room, 2L, List.of(Card.of(Suit.CLUB, 9), Card.of(Suit.DIAMOND, 11))));
        // 含 ♥3 的跟牌合法
        assertTrue(play(room, 2L, List.of(Card.of(Suit.HEART, 3), Card.of(Suit.CLUB, 9))));
    }

    @Test
    void deadRuleRequiresPairFollow() {
        GameRoom room = playingRoom(
                List.of(Card.of(Suit.HEART, 8), Card.of(Suit.HEART, 8)),
                List.of(Card.of(Suit.HEART, 6), Card.of(Suit.HEART, 6), Card.of(Suit.HEART, 9)),
                List.of(Card.of(Suit.CLUB, 4), Card.of(Suit.CLUB, 5)),
                List.of(Card.of(Suit.CLUB, 6), Card.of(Suit.CLUB, 7)));
        room.setFollowRule(FollowRule.DEAD);

        assertTrue(play(room, 1L, List.of(Card.of(Suit.HEART, 8), Card.of(Suit.HEART, 8))));
        // 死棒：东家有 ♥6 棒子，出"棒子拆散 + 单张"被拒
        assertFalse(play(room, 2L, List.of(Card.of(Suit.HEART, 6), Card.of(Suit.HEART, 9))));
        // 出棒合法
        assertTrue(play(room, 2L, List.of(Card.of(Suit.HEART, 6), Card.of(Suit.HEART, 6))));
    }

    @Test
    void lastHandEmptyMovesToSettling() {
        GameRoom room = playingRoom(
                List.of(Card.of(Suit.HEART, 7)),
                List.of(Card.of(Suit.HEART, 13)),
                List.of(Card.of(Suit.HEART, 10)),
                List.of(Card.of(Suit.CLUB, 6)));

        assertTrue(play(room, 1L, List.of(Card.of(Suit.HEART, 7))));
        assertTrue(play(room, 2L, List.of(Card.of(Suit.HEART, 13))));
        assertTrue(play(room, 3L, List.of(Card.of(Suit.HEART, 10))));
        assertTrue(play(room, 4L, List.of(Card.of(Suit.CLUB, 6))));

        assertEquals(GamePhase.SETTLING, room.phase(), "全员手牌打空 → 结算阶段");
        assertEquals(20, room.trickPoints().getOrDefault(Team.B, 0), "♥K + ♥10 = 20 分");
        assertEquals(Team.B, room.lastTrickWinnerTeam().orElseThrow());
    }

    @Test
    void replaySamePlayCommandRejected() {
        GameRoom room = playingRoom(
                List.of(Card.of(Suit.HEART, 7), Card.of(Suit.CLUB, 3)),
                List.of(Card.of(Suit.CLUB, 4)),
                List.of(Card.of(Suit.CLUB, 5)),
                List.of(Card.of(Suit.CLUB, 6)));

        var cmd = new PlayCardsCommand(1001L, 1L, List.of(Card.of(Suit.HEART, 7)));
        assertTrue(room.apply(cmd).success());
        assertTrue(room.apply(cmd).isFailure(), "同 commandId 重放被拒");
    }
}
