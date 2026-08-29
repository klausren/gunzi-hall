package com.gunzihall.domain.player;

import com.gunzihall.domain.card.Card;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 真实玩家。 */
public final class HumanPlayer implements Player {

    private final long playerId;
    private final Seat seat;
    private final List<Card> hand = new ArrayList<>();

    public HumanPlayer(long playerId, Seat seat) {
        this.playerId = playerId;
        this.seat = Objects.requireNonNull(seat);
    }

    @Override
    public long playerId() {
        return playerId;
    }

    @Override
    public Seat seat() {
        return seat;
    }

    @Override
    public List<Card> hand() {
        return hand;
    }

    /** 只读视图，用于断言/展示 */
    public List<Card> handView() {
        return Collections.unmodifiableList(hand);
    }
}
