package com.gunzihall.domain.action;

import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.card.Suit;
import com.gunzihall.domain.player.Seat;
import com.gunzihall.domain.player.Team;
import com.gunzihall.domain.room.GamePhase;
import com.gunzihall.domain.room.GameRoom;
import com.gunzihall.domain.trump.TrumpContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结算编排测试（手册 4/5 节）：级数推进、庄家更替、下一局进贡义务、出锅判定、3.4.3 三王。
 */
class SettleRoundCommandTest {

    private final GameRoom room = RevealTrumpCommandTest.fullRoom();

    /** 构造 SETTLING 场景：级数 3、庄家北（A 队）、指定收分/底牌/最后一圈赢家 */
    private void prepSettling(int level, Map<Team, Integer> points,
                              List<Card> bottom, Team lastWinner) {
        prepSettlingOn(room, level, points, bottom, lastWinner, Seat.NORTH);
    }

    /** 同上，房间与庄家座位可指定（锁"接庄方向"用） */
    private void prepSettlingOn(GameRoom r, int level, Map<Team, Integer> points,
                                List<Card> bottom, Team lastWinner, Seat banker) {
        r.apply(new ShuffleAndDealCommand(1001L, 1L, 42L));
        r.setTrump(new TrumpContext(level, Suit.HEART));
        r.setBankerSeat(banker);
        r.setCurrentLevel(level);
        r.setBottomCards(bottom);
        points.forEach(r::addTrickPoints);
        r.setLastTrickWinnerTeam(lastWinner);
        r.transitionTo(GamePhase.BIDDING);
        r.transitionTo(GamePhase.TRIBUTE);
        r.transitionTo(GamePhase.BURYING);
        r.transitionTo(GamePhase.PLAYING);
        r.transitionTo(GamePhase.SETTLING);
        assertEquals(GamePhase.SETTLING, r.phase());
    }

    @Test
    void bankerDefendsLowScore_attackerPaysTribute_bankerPromoted() {
        // 庄家方保底（最后一圈 A 队赢），抓分方 40 分 < 80 → 抓分方进贡 4 血（分差）
        prepSettling(3, Map.of(Team.B, 40),
                List.of(Card.of(Suit.CLUB, 4), Card.of(Suit.CLUB, 8),
                        Card.of(Suit.DIAMOND, 3), Card.of(Suit.SPADE, 11),
                        Card.of(Suit.SPADE, 13), Card.of(Suit.CLUB, 12)),
                Team.A);

        assertTrue(room.apply(new SettleRoundCommand(1001L, 1L)).success());

        var s = room.lastSettlement().orElseThrow();
        // 底牌只有 K 是分牌（10 分）×2 保底 → 庄家方 20；抓分方出牌收 40
        assertEquals(40, s.attackerScore());
        assertEquals(20, s.bankerScore());
        assertTrue(s.bankerPromoted(), "<120 且保底 → 庄家方升级");
        assertFalse(s.attackerTakesBank());

        // 升级：3 → 4；庄家不变（北）；抓分方进贡 4 血给北家，执行人 = 北上家（东）
        // （出牌逆时针，上家 = 东；见 SeatDirectionTest）
        assertEquals(4, room.currentLevel());
        assertEquals(Seat.NORTH, room.bankerSeat().orElseThrow());
        var ob = room.pendingTributes().get(Seat.EAST);
        assertEquals(4, ob.bloodCount());
        assertEquals(Seat.NORTH, ob.receiver());
        assertEquals(GamePhase.DEALING, room.phase(), "未出锅 → 直接开新局");
    }

    @Test
    void attackerReaches120Defended_threeSmallJokerBottom_bankerPaysTribute() {
        // 抓分方 120 分、庄家方保底（最后一圈 A 队赢）：上台但不升级；
        // 底牌 3 小王 → 扣王血 3 由庄家方进贡（5.3 保底 S≥120 行）
        prepSettling(3, Map.of(Team.B, 120),
                List.of(Card.smallJoker(), Card.smallJoker(), Card.smallJoker(),
                        Card.of(Suit.CLUB, 4), Card.of(Suit.CLUB, 8),
                        Card.of(Suit.DIAMOND, 3)),
                Team.A);

        assertTrue(room.apply(new SettleRoundCommand(1001L, 1L)).success());

        var s = room.lastSettlement().orElseThrow();
        assertTrue(s.attackerTakesBank(), "≥120 上台（4.3 第 3 行：保底也挡不住上台）");
        assertFalse(s.attackerPromoted(), "保底局抓分方没抠底 → 不升级");
        assertEquals(120, s.attackerScore());

        // 新庄家 = 原庄家（北）的下家 = 西（B 队）；无人升级（抓分方没抠底、庄家方没保住 120 线以下）
        // 接庄方向 = 出牌方向的下一家（逆时针 N→W）。若写成"上家接庄"会得到东，方向恰好相反 ——
        // 这正是 2026-09-18 修掉的那一版，护栏见 SeatDirectionTest。
        assertEquals(Seat.WEST, room.bankerSeat().orElseThrow());
        assertEquals(3, room.currentLevel(), "双方都不满足升级条件，级数不变");

        // 庄家方进贡：扣王血 = 3 小王 × 1 = 3 血（分差 120 在 80..150 无分差血）
        // 执行人 = 新庄家（西）上家 = 北（原庄家本人），收贡 = 西
        var ob = room.pendingTributes().get(Seat.NORTH);
        assertEquals(3, ob.bloodCount());
        assertEquals(Seat.WEST, ob.receiver());
    }

    @Test
    void attackerTakesBank_newBankerIsOriginalBankersNextSeat() {
        // 手册 4.3 原文只写"抓分方上台坐庄"，未指明由哪一家接 —— 缺失的方向已与 Tracy 确认：
        // 【下家接庄】，即出牌逆时针方向的下一家（{@link Seat#next()}）。
        // 四个座位逐一验证，把方向钉死：若有人改回 previous()（上家接庄），四条断言全红。
        for (Seat banker : Seat.values()) {
            GameRoom r = RevealTrumpCommandTest.fullRoom();
            Team attacker = banker.team().opponent();
            // 保底（最后一圈由庄家方赢）→ 抓分方拿不到底牌分，靠出牌收满 120 上台线
            prepSettlingOn(r, 3, Map.of(attacker, 120),
                    List.of(Card.of(Suit.CLUB, 4), Card.of(Suit.CLUB, 8),
                            Card.of(Suit.DIAMOND, 3), Card.of(Suit.SPADE, 11),
                            Card.of(Suit.SPADE, 13), Card.of(Suit.CLUB, 12)),
                    banker.team(), banker);

            assertTrue(r.apply(new SettleRoundCommand(1001L, 1L)).success(), banker + " 坐庄：结算应成功");
            assertTrue(r.lastSettlement().orElseThrow().attackerTakesBank(),
                    banker + " 坐庄：抓分方 120 分必须上台，否则本用例失去意义");
            assertEquals(banker.next(), r.bankerSeat().orElseThrow(),
                    banker + " 坐庄、抓分方上台后，应由原庄家的【下家】接庄");
            assertEquals(GamePhase.DEALING, r.phase(), "未出锅 → 直接开新局");
        }
    }

    @Test
    void highScoreBankerPaysScoreTribute() {
        // 抓分方总分 200（出牌 180 + 抠底底牌 10 分 ×2）、底牌无王：S>150 → 庄家方进贡 5 血
        prepSettling(3, Map.of(Team.B, 180),
                List.of(Card.of(Suit.CLUB, 4), Card.of(Suit.CLUB, 8),
                        Card.of(Suit.DIAMOND, 3), Card.of(Suit.SPADE, 11),
                        Card.of(Suit.SPADE, 13), Card.of(Suit.CLUB, 12)),
                Team.B);

        assertTrue(room.apply(new SettleRoundCommand(1001L, 1L)).success());

        // 200 分含底牌 20×2=40 → 出牌阶段抓分 160；上台；无抠底升级？有：dug=true 且 ≥120 → 升级
        // 新庄家 = 北的下家 = 西，执行人 = 西的上家 = 北
        var ob = room.pendingTributes().get(Seat.NORTH);
        assertEquals(5, ob.bloodCount(), "(200-150)/10 = 5 血");
        assertEquals(Seat.WEST, ob.receiver());
    }

    @Test
    void tripleJokerBottomMidGameOver_343() {
        // 底牌 3 大王，抓分方 100 分（<120）且庄家保底 → 整轮结束（3.4.3）
        prepSettling(3, Map.of(Team.B, 100),
                List.of(Card.bigJoker(), Card.bigJoker(), Card.bigJoker(),
                        Card.of(Suit.CLUB, 4), Card.of(Suit.CLUB, 8),
                        Card.of(Suit.DIAMOND, 3)),
                Team.A);

        assertTrue(room.apply(new SettleRoundCommand(1001L, 1L)).success());
        assertEquals(GamePhase.ROUND_OVER, room.phase());
        assertEquals(3, room.currentLevel(), "出锅 → 级数回到 3");
        assertTrue(room.bankerSeat().isEmpty());
        assertTrue(room.pendingTributes().isEmpty());
    }

    @Test
    void promotionPastTenEndsRound() {
        // 打 10 且庄家方升级 → 越过 10 出锅
        prepSettling(10, Map.of(Team.B, 40),
                List.of(Card.of(Suit.CLUB, 4), Card.of(Suit.CLUB, 8),
                        Card.of(Suit.DIAMOND, 3), Card.of(Suit.SPADE, 11),
                        Card.of(Suit.SPADE, 13), Card.of(Suit.CLUB, 12)),
                Team.A);

        assertTrue(room.apply(new SettleRoundCommand(1001L, 1L)).success());
        assertEquals(GamePhase.ROUND_OVER, room.phase(), "打完 10 升级出锅");
    }

    @Test
    void dryPotKingsDoNotCountAsBlood() {
        // 干锅局：底牌无主花色普通牌且含王 → 王不算血，只按分差
        prepSettling(3, Map.of(Team.B, 40),
                List.of(Card.bigJoker(), Card.smallJoker(),
                        Card.of(Suit.CLUB, 4), Card.of(Suit.CLUB, 8),
                        Card.of(Suit.DIAMOND, 3), Card.of(Suit.SPADE, 11)),
                Team.A);
        room.setDryPot(true);

        assertTrue(room.apply(new SettleRoundCommand(1001L, 1L)).success());

        // 分差血 (80-40)/10 = 4；王血因干锅作废
        // 执行人 = 庄家北的上家 = 东
        var ob = room.pendingTributes().get(Seat.EAST);
        assertEquals(4, ob.bloodCount());
    }
}
