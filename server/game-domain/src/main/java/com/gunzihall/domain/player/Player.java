package com.gunzihall.domain.player;

import com.gunzihall.domain.card.Card;

import java.util.List;

/**
 * 玩家抽象：真实玩家与 AI 托管玩家（AgentProxyPlayer，Sprint 3+ 实现）实现同一接口，
 * 房间状态机不区分"是不是 AI"，避免双路径（架构 v0 3.3）。
 */
public interface Player {

    /** 玩家唯一 ID（对应 t_user.user_id） */
    long playerId();

    /** 座位 */
    Seat seat();

    /** 当前手牌（可变，由命令执行层增删；领域层不直接暴露给外部） */
    List<Card> hand();

    /** 是否为 AI 托管（断线接管后为 true，用于 UI 提示"被托管局数"） */
    default boolean isAgent() {
        return false;
    }
}
