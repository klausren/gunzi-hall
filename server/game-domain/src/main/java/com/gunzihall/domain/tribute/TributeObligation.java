package com.gunzihall.domain.tribute;

import com.gunzihall.domain.player.Seat;

/**
 * 一名玩家的进贡义务（上局结算产出，本局 TRIBUTE 阶段执行）。
 *
 * <p>规则依据（Q4/Q4b 已拍板）：总血数 = 分差折算 + 扣王折算，两笔方向一致时合并为
 * 一名执行人。手册 5.3 实现提示：分差血与扣王血来源不同、方向可能不同，
 * 但同一局中两笔方向只可能同为 DEFENDER 或同为 BANKER（分三段区间互斥），
 * 因此引擎按"收贡人的上家"统一折算执行人：
 * <ul>
 *   <li>抓分方进贡（S&lt;80，或保底 S&lt;120 的扣王血）：收贡人 = 庄家，执行人 = 庄家上家；</li>
 *   <li>庄家方进贡（S&gt;150，或 S≥120 的扣王血）：抓分方已上台，收贡人 = 新庄家
 *       （原庄家上家），执行人 = 新庄家上家（原庄家搭档）。</li>
 * </ul>
 *
 * @param bloodCount 进贡张数（血数）
 * @param receiver   收贡人座位
 */
public record TributeObligation(int bloodCount, Seat receiver) {

    public TributeObligation {
        if (bloodCount < 1) {
            throw new IllegalArgumentException("进贡血数必须 ≥ 1: " + bloodCount);
        }
        if (receiver == null) {
            throw new IllegalArgumentException("收贡人不能为空");
        }
    }
}
