package com.gunzihall.domain.action;

import com.gunzihall.domain.player.Player;
import com.gunzihall.domain.room.GameRoom;

/** 命令层通用查找工具（包内可见）。 */
final class Players {

    private Players() {
    }

    /** 按 playerId 找玩家；找不到返回 null */
    static Player find(GameRoom room, long playerId) {
        return room.players().values().stream()
                .filter(p -> p.playerId() == playerId)
                .findFirst()
                .orElse(null);
    }
}
