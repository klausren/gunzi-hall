package com.gunzihall.domain.player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 出牌方向护栏（手册 3.2「每轮（每圈）由庄家先出牌，逆时针」）。
 *
 * <p>【为什么值得单独一个测试类】围坐一圈时，**座位编号顺序**（本枚举按罗盘顺时针
 * 编号 N→E→S→W，为的是对齐发牌结果下标）与**出牌方向**（逆时针）方向正好相反，
 * 极容易顺手写成 {@code (index + 1) % 4}。
 *
 * <p>写错之后的症状很隐蔽：程序不报错，只是"整局出牌反了"，并且连带把上家/下家
 * 整体对调 —— 进贡规则是"庄家的上家向庄家进贡"，于是贡牌进给错的人。界面上看
 * 只是"出牌顺序有点别扭"，很难被当成 bug 报出来。所以用测试钉死。
 */
class SeatDirectionTest {

    @Test
    void playOrderIsCounterClockwise() {
        // 俯视罗盘（北在上、东在右）：逆时针 = 12 → 9 → 6 → 3 点 = 北 → 西 → 南 → 东
        assertEquals(Seat.WEST, Seat.NORTH.next(), "北的下家应是西（逆时针）");
        assertEquals(Seat.SOUTH, Seat.WEST.next(), "西的下家应是南");
        assertEquals(Seat.EAST, Seat.SOUTH.next(), "南的下家应是东");
        assertEquals(Seat.NORTH, Seat.EAST.next(), "东的下家应是北");
    }

    @Test
    void nextOfSouthIsOnThePlayersRightHand() {
        // 玩家坐南边面朝北看牌桌 → 屏幕「上北 · 右东 · 下南 · 左西」（与客户端
        // TableUI.plateLocal 的槽位表同款）。逆时针出牌时，南家的下家必须在**右手边
        // （东）**、上家在左手边（西）—— 与真实牌桌一致（麻将同理）。
        // 这条红了基本就是 next()/previous() 被写反了。
        assertEquals(Seat.EAST, Seat.SOUTH.next(), "南的下家应在右手边（东）");
        assertEquals(Seat.WEST, Seat.SOUTH.previous(), "南的上家应在左手边（西）");
    }

    @Test
    void nextAndPreviousAreInverse() {
        for (Seat s : Seat.values()) {
            assertEquals(s, s.next().previous(), s + ": next 再 previous 应回到自己");
            assertEquals(s, s.previous().next(), s + ": previous 再 next 应回到自己");
            assertEquals(s, s.next().next().next().next(), s + ": 绕四圈回到自己");
        }
    }

    @Test
    void playDirectionIsNotTheIndexOrder() {
        // 把"反直觉点"显式钉住：编号顺序是罗盘顺时针，出牌方向是逆时针，两者必须相反。
        // 如果哪天有人"顺手简化"成 index+1，这条会立刻红。
        for (Seat s : Seat.values()) {
            assertNotEquals(Seat.ofIndex((s.index() + 1) % 4), s.next(),
                    s + ": 出牌方向不能等于编号顺序（否则整局反成顺时针）");
        }
    }

    @Test
    void partnerAndTeamUnaffectedByDirection() {
        // 方向只决定"谁接谁的班"，不该影响搭档与分队
        for (Seat s : Seat.values()) {
            assertEquals(s, s.partner().partner(), s + ": 对家的对家是自己");
            assertSame(s.team(), s.partner().team(), s + ": 对家必须同队");
            assertNotSame(s.team(), s.next().team(), s + ": 下家必须是对手");
            assertEquals(s.partner(), Seat.ofIndex((s.index() + 2) % 4));
        }
    }
}
