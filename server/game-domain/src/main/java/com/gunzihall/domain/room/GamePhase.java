package com.gunzihall.domain.room;

/**
 * 牌局阶段状态机（对应架构 v0 与 UML 图集"牌局状态机"）。
 * <p>WAITING → DEALING → BIDDING → TRIBUTE → BURYING → PLAYING → SETTLING → ROUND_OVER
 */
public enum GamePhase {
    /** 等人齐（4 人就座） */
    WAITING,
    /** 发牌中（39×4 + 底 6） */
    DEALING,
    /** 亮王 / 抠庄 / 定主（第一局含翻底牌定庄） */
    BIDDING,
    /** 进贡 / 还贡 / 抗贡（首局无进贡直接跳过） */
    TRIBUTE,
    /** 庄家扣底（可扣王）、他人捡牌扣王 */
    BURYING,
    /** 出牌阶段（若干圈直至手牌打完） */
    PLAYING,
    /** 结算（得分 / 上台下台 / 升级 / 出锅判定） */
    SETTLING,
    /** 整轮结束（打完 10 出锅）或房间解散 */
    ROUND_OVER;

    /** 是否允许从当前阶段切换到目标阶段（正向单步或保持不变） */
    public boolean canTransitionTo(GamePhase target) {
        if (target == this) {
            return true;
        }
        // Sprint 3 多局循环：结算完直接开新局；出锅/整轮结束后重新开局
        if (target == DEALING && (this == SETTLING || this == ROUND_OVER)) {
            return true;
        }
        // 首局（或上局无血）没有进贡环节，亮主确认后跳过 TRIBUTE 直接扣底
        if (target == BURYING && this == BIDDING) {
            return true;
        }
        return target.ordinal() == this.ordinal() + 1;
    }
}
