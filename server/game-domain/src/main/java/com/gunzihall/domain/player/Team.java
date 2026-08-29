package com.gunzihall.domain.player;

/** 队伍：NORTH+SOUTH / EAST+WEST 两两配对对抗。 */
public enum Team {
    A,
    B;

    public Team opponent() {
        return this == A ? B : A;
    }
}
