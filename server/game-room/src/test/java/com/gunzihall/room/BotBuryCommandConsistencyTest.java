package com.gunzihall.room;

import com.gunzihall.domain.action.BuryBottomCommand;
import com.gunzihall.domain.action.CommandResult;
import com.gunzihall.domain.action.ShuffleAndDealCommand;
import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.card.Suit;
import com.gunzihall.domain.player.HumanPlayer;
import com.gunzihall.domain.player.Player;
import com.gunzihall.domain.player.Seat;
import com.gunzihall.domain.room.GamePhase;
import com.gunzihall.domain.room.GameRoom;
import com.gunzihall.domain.trump.TrumpContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * bot 扣底必须与规则命令口径一致（否则会连环失败把整局冻死）。
 *
 * <p>事故经过：房间出现过 {@code phase=BURYING banker=EAST 首局 stuck=true} ——
 * bot 驱动在扣底阶段反复失败，失败计数越过阈值后房间被判 stuck，bot 停摆，
 * 整局永远冻在扣底。根因是两处**干锅口径不一致**：
 * <ul>
 *   <li>{@link BotBrain#buryCards} 判"底牌无主花色普通牌"就原样扣回，**没有首局豁免**；</li>
 *   <li>{@link BuryBottomCommand} 有首局豁免（首局无干锅），于是继续走 Q5 扣王校验。</li>
 * </ul>
 * 于是首局里"看着像干锅"的底牌会被 bot 原样提交：一旦这 6 张里有小王、而庄家手里还有大王，
 * 命中 Q5（扣小王必须先扣完所有大王）→ 命令拒绝 → {@code doBury} 的兜底又是同一套原样底牌
 * → 再失败 → 循环 15 轮就 stuck。
 *
 * <p>本用例锁住不变量：**bot 选出的扣底牌永远能通过 BuryBottomCommand**（含首局这个边界）。
 */
class BotBuryCommandConsistencyTest {

    /** 首局 + 底牌"像干锅"（无主花色普通牌）但含小王 + 庄家手上有大王 */
    @Test
    void firstRound_dryPotLookingBottomWithSmallJoker_botBuryStillAccepted() {
        GameRoom room = new GameRoom(1001L);
        room.sitDown(new BotPlayer(9000, Seat.EAST));
        room.sitDown(new BotPlayer(9001, Seat.SOUTH));
        room.sitDown(new BotPlayer(9002, Seat.WEST));
        room.sitDown(new HumanPlayer(1001, Seat.NORTH));
        room.apply(new ShuffleAndDealCommand(1001L, 1L, 42L));

        room.setFirstRound(true);                       // 首局：干锅规则不适用（可正常替换底牌）
        room.setTrump(new TrumpContext(3, Suit.HEART));
        room.setBankerSeat(Seat.NORTH);

        // 底牌：无红桃普通牌（"像干锅"），且含一张小王
        List<Card> bottom = List.of(
                Card.smallJoker(),
                Card.of(Suit.SPADE, 4), Card.of(Suit.SPADE, 6),
                Card.of(Suit.CLUB, 4), Card.of(Suit.CLUB, 6),
                Card.of(Suit.DIAMOND, 4));
        room.setBottomCards(bottom);

        Player banker = room.playerAt(Seat.NORTH);
        banker.hand().clear();
        banker.hand().addAll(List.of(Card.bigJoker(), Card.of(Suit.HEART, 5),
                Card.of(Suit.SPADE, 7), Card.of(Suit.SPADE, 8),
                Card.of(Suit.CLUB, 7), Card.of(Suit.CLUB, 8),
                Card.of(Suit.DIAMOND, 7)));

        room.transitionTo(GamePhase.TRIBUTE);
        room.transitionTo(GamePhase.BURYING);

        // bot 选牌（口径必须与命令一致）
        List<Card> combined = new ArrayList<>(banker.hand());
        combined.addAll(room.bottomCards());
        List<Card> bury = BotBrain.buryCards(room, banker, combined);

        CommandResult r = room.apply(new BuryBottomCommand(1001L, 1001L, bury));

        assertTrue(r.success(), "bot 扣底必须被规则接受，否则驱动会反复失败把房间判成 stuck。"
                + " 被拒原因: " + r.reason() + "，bot 选牌: " + bury);
        // 扣完底之后还有一个"他人捡牌扣王"窗口（手册 2.3.5）：只要别家手里还有王，
        // 阶段会停在 BURYING 依次问他们 —— 本用例锁的是"扣底命令本身被接受"，
        // 两种落点都算通过（窗口的推进由 PickBottomJokerCommandTest 覆盖）。
        assertTrue(room.phase() == GamePhase.PLAYING || room.phase() == GamePhase.BURYING,
                "bot 扣底后要么开打、要么留在扣王窗口，实际: " + room.phase());
    }
}
