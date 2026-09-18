package com.gunzihall.domain.action;

import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.card.Suit;
import com.gunzihall.domain.player.Seat;
import com.gunzihall.domain.room.GamePhase;
import com.gunzihall.domain.room.GameRoom;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 干锅（底牌里没有主花色普通牌）必须"无论从哪条路进入扣底，都跳过扣底流程"。
 *
 * <p>事故背景：干锅拦截最初只加在两处——亮主确认（{@link ConfirmTrumpCommand}）
 * 和还贡完（{@link ReturnTributeCommand}）。漏了第三条路：非首局"抽底牌定主"
 * （{@link ResolveTrumpFromBottomCommand}）之后直接进 BURYING。后果很实在：
 * 干锅局照样进入扣底阶段——庄家是真人时点"扣底"永远被规则拒绝（界面上表现成
 * "怎么点都没反应"）；庄家是 bot 时反复扣底失败会累加失败计数把房间判成 stuck，
 * bot 驱动停摆、整局冻在扣底。现在拦截收口在 {@code GameRoom.transitionTo()}，
 * 本测试锁住这条最容易被漏掉的路。
 */
class DryPotTransitionTest {

    /**
     * 非首局、无人亮主 → 抽底牌定主：抽中的底牌是「黑桃2」，于是主花色 = 黑桃，
     * 而底牌里除这张 2（不算普通牌）之外再无黑桃 → 干锅。
     */
    @Test
    void resolvingTrumpFromBottom_dryPot_skipsBurying() {
        GameRoom room = RevealTrumpCommandTest.fullRoom();
        room.apply(new ShuffleAndDealCommand(1001L, 1L, 42L));
        assertEquals(GamePhase.BIDDING, room.phase());

        room.setFirstRound(false);          // 干锅只在非首局判定（首局无干锅）
        room.setBankerSeat(Seat.NORTH);
        List<Card> bottom = List.of(
                Card.of(Suit.SPADE, 2),     // 抽中它 → 主黑桃；但它 rank=2 不算"普通牌"
                Card.of(Suit.CLUB, 4), Card.of(Suit.CLUB, 8),
                Card.of(Suit.DIAMOND, 3), Card.of(Suit.DIAMOND, 7),
                Card.of(Suit.HEART, 9));
        room.setBottomCards(bottom);
        assertFalse(room.isDryPot(), "干锅判定发生在阶段切换时，此时还不应标记");

        CommandResult r = room.apply(new ResolveTrumpFromBottomCommand(1001L, 1L, List.of(0)));

        assertTrue(r.success(), r.reason());
        assertEquals(Suit.SPADE, room.trump().orElseThrow().trumpSuit());
        assertTrue(room.isDryPot(), "底牌除抽中的 2 之外无主花色普通牌 → 应判干锅");
        assertEquals(GamePhase.PLAYING, room.phase(),
                "干锅必须直接进 PLAYING（底牌原样扣回、庄家领出），不得停在 BURYING 等扣底");
        assertTrue(room.isBottomTaken(), "干锅视作已收底");
    }
}
