package com.gunzihall.room;

import com.gunzihall.domain.action.CommandResult;
import com.gunzihall.domain.action.PickBottomJokerCommand;
import com.gunzihall.domain.action.ShuffleAndDealCommand;
import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.card.Suit;
import com.gunzihall.domain.player.Player;
import com.gunzihall.domain.player.Seat;
import com.gunzihall.domain.room.GamePhase;
import com.gunzihall.domain.room.GameRoom;
import com.gunzihall.domain.trump.TrumpContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * bot 扣王选牌必须与规则命令口径一致（否则窗口推进不了，整局冻死）。
 *
 * <p>与 {@code BotBuryCommandConsistencyTest} 是同一类护栏，踩坑机理也一样：
 * {@code RoomActor.doPickJoker} 的失败兜底是"再发一条空列表（过）"，一旦 bot 的选牌
 * 稳定地被命令拒绝，每轮就是 +1 次失败，几十轮后房间被判 stuck、bot 驱动永久停摆，
 * 而牌局会停死在 BURYING —— 这正是"整局冻死在扣底"那类事故的形态。
 *
 * <p>重点锁两条边界：
 * <ol>
 *   <li>底牌里可捡的非分牌很少（其余都是王）时，bot 不能按"手里有几张王"就扣几张；</li>
 *   <li>抓分方 bot 默认不押注，返回空列表（等价于"过"），命令也必须接受。</li>
 * </ol>
 */
class BotPickJokerConsistencyTest {

    private static final long ROOM = 1001L;

    /** 4 个 bot 的房间，主牌红桃 3，庄家 NORTH；返回可直接摆局的房间 */
    private GameRoom botRoom() {
        GameRoom room = new GameRoom(ROOM);
        room.sitDown(new BotPlayer(9000, Seat.EAST));
        room.sitDown(new BotPlayer(9001, Seat.SOUTH));
        room.sitDown(new BotPlayer(9002, Seat.WEST));
        room.sitDown(new BotPlayer(9003, Seat.NORTH));
        room.apply(new ShuffleAndDealCommand(ROOM, 1L, 42L));
        room.setFirstRound(false);
        room.setTrump(new TrumpContext(3, Suit.HEART));
        room.setBankerSeat(Seat.NORTH);
        return room;
    }

    /** 摆好扣王窗口，并轮到 SOUTH（庄家的对家，庄家方）表态 */
    private void toPickWindowForSouth(GameRoom room, List<Card> bottom, List<Card> southHand) {
        room.setBottomCards(bottom);
        room.setBottomTaken(true);
        Player south = room.playerAt(Seat.SOUTH);
        south.hand().clear();
        south.hand().addAll(southHand);
        room.transitionTo(GamePhase.TRIBUTE);
        room.transitionTo(GamePhase.BURYING);
        room.openBuryPickWindow(List.of(Seat.EAST, Seat.SOUTH, Seat.WEST));
        room.apply(new PickBottomJokerCommand(ROOM, 9000L, List.of())); // EAST 过
        assertEquals(Seat.SOUTH, room.buryPickSeat());
    }

    @Test
    void bankerSideBot_neverAsksForMoreThanCanBePickedBack() {
        GameRoom room = botRoom();
        // 底牌 = 5 张王 + 1 张非分牌 → 只捡得回 1 张
        List<Card> bottom = List.of(
                Card.smallJoker(), Card.smallJoker(), Card.smallJoker(),
                Card.bigJoker(), Card.bigJoker(), Card.of(Suit.SPADE, 7));
        // 庄家方（南）手里 3 张王，很想全扣
        toPickWindowForSouth(room, bottom, List.of(
                Card.bigJoker(), Card.bigJoker(), Card.bigJoker(),
                Card.of(Suit.CLUB, 8), Card.of(Suit.DIAMOND, 8)));

        Player south = room.playerAt(Seat.SOUTH);
        List<Card> choice = BotBrain.pickBottomJokers(room, south);

        assertEquals(1, choice.size(),
                "底牌只捡得回 1 张，bot 最多只能扣 1 张（否则命令会拒绝、驱动反复失败）");
        assertEquals(1, room.bottomPickableCount(), "前置条件：底牌可捡 1 张");

        CommandResult r = room.apply(new PickBottomJokerCommand(ROOM, 9001L, choice));

        assertTrue(r.success(), "bot 选出的扣王牌必须被命令接受，被拒原因: " + r.reason());
        assertEquals(6, room.bottomCards().size(), "底牌张数守恒");
    }

    @Test
    void defenderBot_holdsBackAndItsPassIsAccepted() {
        GameRoom room = botRoom();
        List<Card> bottom = List.of(
                Card.of(Suit.SPADE, 7), Card.of(Suit.SPADE, 8),
                Card.of(Suit.CLUB, 7), Card.of(Suit.CLUB, 8),
                Card.of(Suit.DIAMOND, 8), Card.of(Suit.DIAMOND, 9));
        // WEST 是抓分方（庄家 NORTH 的对家是 SOUTH，抓分方 = EAST + WEST），手里有王也不押
        room.setBottomCards(bottom);
        room.setBottomTaken(true);
        room.transitionTo(GamePhase.TRIBUTE);
        room.transitionTo(GamePhase.BURYING);
        room.openBuryPickWindow(List.of(Seat.WEST));
        Player west = room.playerAt(Seat.WEST);
        west.hand().clear();
        west.hand().add(Card.bigJoker());

        List<Card> choice = BotBrain.pickBottomJokers(room, west);

        assertTrue(choice.isEmpty(), "抓分方要先拿到 120 分再抠底才吃血，胜率低，不押注");
        CommandResult r = room.apply(new PickBottomJokerCommand(ROOM, 9002L, choice));

        assertTrue(r.success(), "空列表 = 过，命令必须接受: " + r.reason());
        assertTrue(room.isBuryPickWindowOpen() == false, "窗口问完就收口");
        assertEquals(GamePhase.PLAYING, room.phase());
    }
}
