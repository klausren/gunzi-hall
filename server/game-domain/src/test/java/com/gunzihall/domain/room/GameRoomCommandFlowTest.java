package com.gunzihall.domain.room;

import com.gunzihall.domain.action.CommandResult;
import com.gunzihall.domain.action.PlayCardsCommand;
import com.gunzihall.domain.action.ShuffleAndDealCommand;
import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.player.HumanPlayer;
import com.gunzihall.domain.player.Player;
import com.gunzihall.domain.player.Seat;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 命令模式骨架的集成测试：入座 → 发牌 → 阶段流转 → 出牌 → 防重放。
 */
class GameRoomCommandFlowTest {

    private GameRoom fullRoom() {
        GameRoom room = new GameRoom(1001L);
        room.sitDown(new HumanPlayer(1L, Seat.NORTH));
        room.sitDown(new HumanPlayer(2L, Seat.EAST));
        room.sitDown(new HumanPlayer(3L, Seat.SOUTH));
        room.sitDown(new HumanPlayer(4L, Seat.WEST));
        return room;
    }

    @Test
    void seatPartnerAndTeam() {
        assertEquals(Seat.SOUTH, Seat.NORTH.partner());
        assertEquals(Seat.WEST, Seat.EAST.partner());
        assertEquals(Seat.EAST, Seat.NORTH.next());
        assertEquals(Seat.WEST, Seat.NORTH.previous());
        assertSame(Seat.NORTH.team(), Seat.SOUTH.team());
        assertNotSame(Seat.NORTH.team(), Seat.EAST.team());
    }

    @Test
    void cannotDealWhenNotFull() {
        GameRoom room = new GameRoom(1001L);
        room.sitDown(new HumanPlayer(1L, Seat.NORTH));
        var cmd = new ShuffleAndDealCommand(1001L, 1L, 42L);
        assertTrue(room.apply(cmd).isFailure());
    }

    @Test
    void dealAssigns39EachAndMovesToBidding() {
        GameRoom room = fullRoom();
        var cmd = new ShuffleAndDealCommand(1001L, 1L, 42L);
        CommandResult result = room.apply(cmd);
        assertTrue(result.success(), () -> String.valueOf(result.reason()));

        assertEquals(GamePhase.BIDDING, room.phase());
        for (Seat seat : Seat.values()) {
            assertEquals(39, room.playerAt(seat).hand().size(), seat + " 手牌应为 39 张");
            assertEquals(39, room.handOf(seat).size());
        }
        assertEquals(6, room.bottomCards().size());
    }

    @Test
    void replayCommandRejected() {
        GameRoom room = fullRoom();
        var cmd = new ShuffleAndDealCommand(1001L, 1L, 42L);
        assertTrue(room.apply(cmd).success());
        // 同一 commandId 再次提交 → 拒绝（防重放）
        CommandResult again = room.apply(cmd);
        assertTrue(again.isFailure());
        assertNotNull(again.reason());
        assertEquals(1, room.history().size(), "重复命令不增加历史");
    }

    @Test
    void wrongRoomCommandRejected() {
        GameRoom room = fullRoom();
        var cmd = new ShuffleAndDealCommand(9999L, 1L, 42L);
        assertTrue(room.apply(cmd).isFailure());
    }

    @Test
    void playCardsOnlyInPlayingPhase() {
        GameRoom room = fullRoom();
        room.apply(new ShuffleAndDealCommand(1001L, 1L, 42L)); // → BIDDING
        Player north = room.playerAt(Seat.NORTH);
        Card any = north.hand().get(0);
        var play = new PlayCardsCommand(1001L, 1L, List.of(any));
        assertTrue(room.apply(play).isFailure(), "BIDDING 阶段不允许出牌");
    }

    @Test
    void playCardsRemovesFromHandAndRollbackRestores() {
        GameRoom room = fullRoom();
        room.apply(new ShuffleAndDealCommand(1001L, 1L, 42L));
        // 骨架阶段直接切到 PLAYING 模拟（Sprint 2 由亮王/扣底命令自然推进）
        room.transitionTo(GamePhase.TRIBUTE);
        room.transitionTo(GamePhase.BURYING);
        room.transitionTo(GamePhase.PLAYING);

        Player north = room.playerAt(Seat.NORTH);
        Card first = north.hand().get(0);
        int before = north.hand().size();

        var play = new PlayCardsCommand(1001L, 1L, List.of(first));
        assertTrue(room.apply(play).success());
        assertEquals(before - 1, north.hand().size());
        assertFalse(north.hand().contains(first));

        // 回滚（本地调试用）
        assertTrue(play.rollback(room).success());
        assertEquals(before, north.hand().size());
        assertTrue(north.hand().contains(first));
    }

    @Test
    void playCardsNotInHandRejected() {
        GameRoom room = fullRoom();
        room.apply(new ShuffleAndDealCommand(1001L, 1L, 42L));
        room.transitionTo(GamePhase.TRIBUTE);
        room.transitionTo(GamePhase.BURYING);
        room.transitionTo(GamePhase.PLAYING);

        Player north = room.playerAt(Seat.NORTH);
        Player east = room.playerAt(Seat.EAST);
        // 注意：Card 按身份判等（三副牌同身份最多 3 张），东家第一张可能恰好在北家手上。
        // 需挑一张确定不在北家手上的东家牌，保证测试确定性。
        Card stolen = east.hand().stream()
                .filter(c -> !north.hand().contains(c))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("测试前提不成立：东家手牌全部与北家重合"));
        // NORTH 试图打出 EAST 的牌 → 拒绝（服务端权威校验）
        var play = new PlayCardsCommand(1001L, 1L, List.of(stolen));
        assertTrue(room.apply(play).isFailure());
    }

    @Test
    void moreThanThreeCardsRejected() {
        GameRoom room = fullRoom();
        room.apply(new ShuffleAndDealCommand(1001L, 1L, 42L));
        room.transitionTo(GamePhase.TRIBUTE);
        room.transitionTo(GamePhase.BURYING);
        room.transitionTo(GamePhase.PLAYING);

        Player north = room.playerAt(Seat.NORTH);
        List<Card> four = north.hand().subList(0, 4);
        assertTrue(room.apply(new PlayCardsCommand(1001L, 1L, four)).isFailure());
    }

    @Test
    void historyTicksAreSequential() {
        GameRoom room = fullRoom();
        room.apply(new ShuffleAndDealCommand(1001L, 1L, 42L));
        room.apply(new ShuffleAndDealCommand(1001L, 1L, 43L)); // 重新洗牌（BIDDING 重入）
        var history = room.history().all();
        assertEquals(2, history.size());
        assertEquals(1, history.get(0).tick());
        assertEquals(2, history.get(1).tick());
    }

    @Test
    void phaseTransitionIsStrictlyForward() {
        assertFalse(GamePhase.WAITING.canTransitionTo(GamePhase.PLAYING));
        assertFalse(GamePhase.PLAYING.canTransitionTo(GamePhase.WAITING));
        assertTrue(GamePhase.WAITING.canTransitionTo(GamePhase.DEALING));
        assertTrue(GamePhase.DEALING.canTransitionTo(GamePhase.DEALING));
    }
}
