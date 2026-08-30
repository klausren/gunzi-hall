package com.gunzihall.room;

import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.player.Player;
import com.gunzihall.domain.player.Seat;

import java.util.ArrayList;
import java.util.List;

/** AI 托管玩家（架构 v0 3.3：与真实玩家实现同一接口，房间状态机不区分是不是 AI）。 */
public final class BotPlayer implements Player {

    private final long playerId;
    private final Seat seat;
    private final List<Card> hand = new ArrayList<>();

    public BotPlayer(long playerId, Seat seat) {
        this.playerId = playerId;
        this.seat = seat;
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

    @Override
    public boolean isAgent() {
        return true;
    }
}
