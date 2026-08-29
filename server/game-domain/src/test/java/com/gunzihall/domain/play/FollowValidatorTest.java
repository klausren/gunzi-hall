package com.gunzihall.domain.play;

import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.card.Suit;
import com.gunzihall.domain.trump.TrumpContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 活棒/死棒跟牌校验测试（手册 3.2/3.3，Q3 已拍板默认活棒）。
 * <p>固定上下文：级数 5，主花色 ♠。
 */
class FollowValidatorTest {

    private final TrumpContext ctx = new TrumpContext(5, Suit.SPADE);

    private Combo leadOf(List<Card> cards) {
        return Combo.parse(cards, ctx).orElseThrow();
    }

    private Optional<String> validate(FollowRule rule, List<Card> lead, List<Card> hand, List<Card> played) {
        return FollowValidator.validate(rule, leadOf(lead), hand, played, ctx);
    }

    // ================ 活棒（默认模式） ================

    @Test
    void liveMustFollowSuitWhenHolding() {
        List<Card> lead = List.of(Card.of(Suit.HEART, 7));
        List<Card> hand = List.of(Card.of(Suit.HEART, 3), Card.of(Suit.CLUB, 9));
        // 有 ♥ 必须跟 ♥，出 ♣ 被拒
        assertTrue(validate(FollowRule.LIVE, lead, hand, List.of(Card.of(Suit.CLUB, 9))).isPresent());
        // 跟 ♥ 合法（即使更小）
        assertTrue(validate(FollowRule.LIVE, lead, hand, List.of(Card.of(Suit.HEART, 3))).isEmpty());
    }

    @Test
    void liveVoidInSuitCanKillOrDiscard() {
        List<Card> lead = List.of(Card.of(Suit.HEART, 7));
        List<Card> hand = List.of(Card.of(Suit.CLUB, 9), Card.smallJoker());
        // 没有 ♥：主牌杀合法
        assertTrue(validate(FollowRule.LIVE, lead, hand, List.of(Card.smallJoker())).isEmpty());
        // 垫 ♣ 也合法
        assertTrue(validate(FollowRule.LIVE, lead, hand, List.of(Card.of(Suit.CLUB, 9))).isEmpty());
    }

    @Test
    void liveLevelCardDoesNotCountAsLeadSuit() {
        // 手中只剩 ♥5（级牌 → 主牌类别）：首出 ♥ 时它不算 ♥，可自由出
        List<Card> lead = List.of(Card.of(Suit.HEART, 7));
        List<Card> hand = List.of(Card.of(Suit.HEART, 5));
        assertTrue(validate(FollowRule.LIVE, lead, hand, List.of(Card.of(Suit.HEART, 5))).isEmpty());
    }

    @Test
    void livePairLeadDoesNotRequirePair() {
        // 活棒：打棒可以不跟棒，两张单张（同花色）合法
        List<Card> lead = List.of(Card.of(Suit.HEART, 8), Card.of(Suit.HEART, 8));
        List<Card> hand = List.of(Card.of(Suit.HEART, 3), Card.of(Suit.HEART, 9),
                Card.of(Suit.CLUB, 4));
        assertTrue(validate(FollowRule.LIVE, lead, hand,
                List.of(Card.of(Suit.HEART, 3), Card.of(Suit.HEART, 9))).isEmpty(),
                "活棒：两张同花单张即可");
    }

    @Test
    void livePairLeadStillRequiresBothCardsOfLeadSuit() {
        List<Card> lead = List.of(Card.of(Suit.HEART, 8), Card.of(Suit.HEART, 8));
        List<Card> hand = List.of(Card.of(Suit.HEART, 3), Card.of(Suit.HEART, 9),
                Card.of(Suit.CLUB, 4));
        // 手中 ♥ ≥ 2 张：必须两张都跟 ♥，混垫被拒
        assertTrue(validate(FollowRule.LIVE, lead, hand,
                List.of(Card.of(Suit.HEART, 3), Card.of(Suit.CLUB, 4))).isPresent());
    }

    @Test
    void livePairLeadSingleLeadSuitCardMustBeIncluded() {
        List<Card> lead = List.of(Card.of(Suit.HEART, 8), Card.of(Suit.HEART, 8));
        List<Card> hand = List.of(Card.of(Suit.HEART, 3), Card.of(Suit.CLUB, 4));
        // 唯一的 ♥ 必须跟出，另一张自由（垫或杀）
        assertTrue(validate(FollowRule.LIVE, lead, hand,
                List.of(Card.of(Suit.HEART, 3), Card.of(Suit.CLUB, 4))).isEmpty());
        assertTrue(validate(FollowRule.LIVE, lead, hand,
                List.of(Card.of(Suit.HEART, 3), Card.smallJoker())).isEmpty());
        assertTrue(validate(FollowRule.LIVE, lead, hand,
                List.of(Card.of(Suit.CLUB, 4), Card.of(Suit.CLUB, 6))).isPresent(),
                "唯一的 ♥ 没跟出 → 拒绝");
    }

    @Test
    void liveTrumpLeadPoolIsAllTrumpCards() {
        // 首出主牌：手中所有主牌（王/2/级牌/♠）都算跟牌池
        List<Card> lead = List.of(Card.of(Suit.SPADE, 7));
        List<Card> hand = List.of(Card.of(Suit.HEART, 5), Card.of(Suit.DIAMOND, 2),
                Card.of(Suit.CLUB, 9));
        // 垫 ♣（非主牌）被拒：手中有两张主牌（♥5 级牌 + ♦2）
        assertTrue(validate(FollowRule.LIVE, lead, hand, List.of(Card.of(Suit.CLUB, 9))).isPresent());
        // 出主牌合法
        assertTrue(validate(FollowRule.LIVE, lead, hand, List.of(Card.of(Suit.HEART, 5))).isEmpty());
        assertTrue(validate(FollowRule.LIVE, lead, hand, List.of(Card.of(Suit.DIAMOND, 2))).isEmpty());
    }

    @Test
    void wrongCountRejected() {
        List<Card> lead = List.of(Card.of(Suit.HEART, 8), Card.of(Suit.HEART, 8));
        List<Card> hand = List.of(Card.of(Suit.CLUB, 4), Card.of(Suit.CLUB, 5), Card.of(Suit.CLUB, 6));
        assertTrue(validate(FollowRule.LIVE, lead, hand, List.of(Card.of(Suit.CLUB, 4))).isPresent());
    }

    // ================ 死棒 ================

    @Test
    void deadSingleLeadSameAsLive() {
        List<Card> lead = List.of(Card.of(Suit.HEART, 7));
        List<Card> hand = List.of(Card.of(Suit.HEART, 3), Card.of(Suit.CLUB, 9));
        assertTrue(validate(FollowRule.DEAD, lead, hand, List.of(Card.of(Suit.CLUB, 9))).isPresent());
        assertTrue(validate(FollowRule.DEAD, lead, hand, List.of(Card.of(Suit.HEART, 3))).isEmpty());
    }

    @Test
    void deadPairLeadMustPlayPairWhenHolding() {
        List<Card> lead = List.of(Card.of(Suit.HEART, 8), Card.of(Suit.HEART, 8));
        List<Card> hand = List.of(Card.of(Suit.HEART, 6), Card.of(Suit.HEART, 6),
                Card.of(Suit.HEART, 9));
        // 有 ♥6 棒子：必须出棒
        assertTrue(validate(FollowRule.DEAD, lead, hand,
                List.of(Card.of(Suit.HEART, 6), Card.of(Suit.HEART, 9))).isPresent(),
                "死棒：打棒必须出棒");
        assertTrue(validate(FollowRule.DEAD, lead, hand,
                List.of(Card.of(Suit.HEART, 6), Card.of(Suit.HEART, 6))).isEmpty());
    }

    @Test
    void deadPairLeadNoPairThenTwoSinglesFollowSuit() {
        List<Card> lead = List.of(Card.of(Suit.HEART, 8), Card.of(Suit.HEART, 8));
        List<Card> hand = List.of(Card.of(Suit.HEART, 3), Card.of(Suit.HEART, 9),
                Card.of(Suit.CLUB, 4));
        // 没棒：两张单张仍须跟花色
        assertTrue(validate(FollowRule.DEAD, lead, hand,
                List.of(Card.of(Suit.HEART, 3), Card.of(Suit.HEART, 9))).isEmpty());
        assertTrue(validate(FollowRule.DEAD, lead, hand,
                List.of(Card.of(Suit.HEART, 3), Card.of(Suit.CLUB, 4))).isPresent());
    }

    @Test
    void deadPairLeadGunnerMayNotBeBroken() {
        // 没棒只有滚子（♥9×3）：手册明确"没棒有滚子时可以不出滚子"→ 两张垫牌合法（从宽）
        List<Card> lead = List.of(Card.of(Suit.HEART, 8), Card.of(Suit.HEART, 8));
        List<Card> hand = List.of(Card.of(Suit.HEART, 9), Card.of(Suit.HEART, 9),
                Card.of(Suit.HEART, 9), Card.of(Suit.CLUB, 4), Card.of(Suit.DIAMOND, 6));
        assertTrue(validate(FollowRule.DEAD, lead, hand,
                List.of(Card.of(Suit.CLUB, 4), Card.of(Suit.DIAMOND, 6))).isEmpty(),
                "可以不拆滚子");
        // 拆滚子出棒也合法
        assertTrue(validate(FollowRule.DEAD, lead, hand,
                List.of(Card.of(Suit.HEART, 9), Card.of(Suit.HEART, 9))).isEmpty());
    }

    @Test
    void deadTripleLeadMustPlayGunner() {
        List<Card> lead = List.of(
                Card.of(Suit.HEART, 8), Card.of(Suit.HEART, 8), Card.of(Suit.HEART, 8));
        List<Card> hand = List.of(Card.of(Suit.HEART, 9), Card.of(Suit.HEART, 9),
                Card.of(Suit.HEART, 9), Card.of(Suit.CLUB, 4));
        assertTrue(validate(FollowRule.DEAD, lead, hand,
                List.of(Card.of(Suit.HEART, 9), Card.of(Suit.HEART, 9), Card.of(Suit.CLUB, 4)))
                        .isPresent(), "死棒：打滚子必须出滚子");
        assertTrue(validate(FollowRule.DEAD, lead, hand,
                List.of(Card.of(Suit.HEART, 9), Card.of(Suit.HEART, 9), Card.of(Suit.HEART, 9)))
                        .isEmpty());
    }

    @Test
    void deadTripleLeadPairPlusSingle() {
        List<Card> lead = List.of(
                Card.of(Suit.HEART, 8), Card.of(Suit.HEART, 8), Card.of(Suit.HEART, 8));
        // 没滚子有棒（♥6×2）+ 余牌 ♥9：必须棒+单张，且单张跟花色
        List<Card> hand = List.of(Card.of(Suit.HEART, 6), Card.of(Suit.HEART, 6),
                Card.of(Suit.HEART, 9), Card.of(Suit.CLUB, 4));
        assertTrue(validate(FollowRule.DEAD, lead, hand,
                List.of(Card.of(Suit.HEART, 6), Card.of(Suit.HEART, 6), Card.of(Suit.HEART, 9)))
                        .isEmpty(), "棒+单张合法");
        assertTrue(validate(FollowRule.DEAD, lead, hand,
                List.of(Card.of(Suit.HEART, 6), Card.of(Suit.HEART, 6), Card.of(Suit.CLUB, 4)))
                        .isPresent(), "有 ♥ 余牌时单张必须跟花色");
    }

    @Test
    void deadTripleLeadPairPlusFreeSingleWhenPoolExhausted() {
        List<Card> lead = List.of(
                Card.of(Suit.HEART, 8), Card.of(Suit.HEART, 8), Card.of(Suit.HEART, 8));
        // 棒是手中仅有的两张 ♥：单张自由（可垫任意）
        List<Card> hand = List.of(Card.of(Suit.HEART, 6), Card.of(Suit.HEART, 6),
                Card.of(Suit.CLUB, 4), Card.of(Suit.DIAMOND, 6));
        assertTrue(validate(FollowRule.DEAD, lead, hand,
                List.of(Card.of(Suit.HEART, 6), Card.of(Suit.HEART, 6), Card.of(Suit.CLUB, 4)))
                        .isEmpty());
    }

    @Test
    void deadTripleLeadNoPairThreeSinglesFollowSuit() {
        List<Card> lead = List.of(
                Card.of(Suit.HEART, 8), Card.of(Suit.HEART, 8), Card.of(Suit.HEART, 8));
        List<Card> hand = List.of(Card.of(Suit.HEART, 3), Card.of(Suit.HEART, 9),
                Card.of(Suit.CLUB, 4), Card.of(Suit.DIAMOND, 6));
        assertTrue(validate(FollowRule.DEAD, lead, hand,
                List.of(Card.of(Suit.HEART, 3), Card.of(Suit.CLUB, 4), Card.of(Suit.DIAMOND, 6)))
                        .isPresent(), "两张 ♥ 只跟出一张 → 拒绝");
        assertTrue(validate(FollowRule.DEAD, lead, hand,
                List.of(Card.of(Suit.HEART, 3), Card.of(Suit.HEART, 9), Card.of(Suit.DIAMOND, 6)))
                        .isEmpty(), "两张 ♥ + 一张垫牌合法");
    }
}
