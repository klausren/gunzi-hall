package com.gunzihall.domain.player;

/**
 * 座位：四人围坐，座位名 = 罗盘绝对方位（上北 / 右东 / 下南 / 左西），对家间隔 2。
 * <p>N+S 一队（庄家方或抓分方之一），E+W 一队。
 *
 * <p>【index 是编号，不是出牌方向 —— 这两者方向正好相反】枚举顺序
 * N(0) E(1) S(2) W(3) 是**按罗盘顺时针**编号的，只为让 {@link #index()} /
 * {@link #ofIndex(int)} 与发牌结果的下标一一对应。而手册 3.2 规定**出牌是逆时针**
 * （N→W→S→E），由 {@link #next()} 表达 —— 它**不等于** {@code index + 1}。
 *
 * <p>这里是踩过的坑：一圈人里"编号顺序"与"出牌顺序"方向相反，历史上
 * {@code next()} 被写成了 {@code index + 1}，于是整局出牌变成顺时针，并且连带把
 * 上家/下家整体对调（进贡规则是"庄家的上家向庄家进贡"，进贡会进给错的人）。
 * 护栏：{@code SeatDirectionTest}。改本类前请先读它。
 */
public enum Seat {
    NORTH(0),
    EAST(1),
    SOUTH(2),
    WEST(3);

    private final int index;

    Seat(int index) {
        this.index = index;
    }

    /** 座位序号 0..3（按罗盘顺时针编号），与发牌结果的下标对应；**不代表出牌方向** */
    public int index() {
        return index;
    }

    /** 对家（间隔 2 座位） */
    public Seat partner() {
        return Seat.values()[(index + 2) % 4];
    }

    /**
     * 下家：出牌方向的下一家（手册 3.2「每轮（每圈）由庄家先出牌，逆时针」）。
     *
     * <p>逆时针顺序是 N→W→S→E→N。玩家坐南边面朝北看牌桌（屏幕「上北 右东 下南 左西」），
     * 南家的下家落在**右手边（东）**，与真实牌桌一致。若写成 {@code (index + 1) % 4}，
     * 整圈就反成顺时针，下家会跑到左手边 —— 这是本项目踩过的坑。
     */
    public Seat next() {
        return Seat.values()[(index + 3) % 4];
    }

    /** 上家：先于本家出牌的一家，与 {@link #next()} 相反（进贡执行人取"庄家上家"） */
    public Seat previous() {
        return Seat.values()[(index + 1) % 4];
    }

    /** 队伍：对家两人同队 */
    public Team team() {
        return (index % 2 == 0) ? Team.A : Team.B;
    }

    public static Seat ofIndex(int index) {
        return Seat.values()[index];
    }
}
