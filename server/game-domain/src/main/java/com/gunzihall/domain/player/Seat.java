package com.gunzihall.domain.player;

/**
 * 座位：四人逆时针就座，对家间隔 2。
 * <p>N+S 一队（庄家方或抓分方之一），E+W 一队。
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

    /** 座位序号 0..3，与发牌结果的下标对应 */
    public int index() {
        return index;
    }

    /** 对家（间隔 2 座位） */
    public Seat partner() {
        return Seat.values()[(index + 2) % 4];
    }

    /** 下家（逆时针） */
    public Seat next() {
        return Seat.values()[(index + 1) % 4];
    }

    /** 上家 */
    public Seat previous() {
        return Seat.values()[(index + 3) % 4];
    }

    /** 队伍：对家两人同队 */
    public Team team() {
        return (index % 2 == 0) ? Team.A : Team.B;
    }

    public static Seat ofIndex(int index) {
        return Seat.values()[index];
    }
}
