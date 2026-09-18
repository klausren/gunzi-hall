package com.gunzihall.domain.room;

import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.trump.CardComparator;
import com.gunzihall.domain.trump.TrumpContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 底牌捡牌规则（手册 2.3 第 5 条）：他人扣王后"还需从底牌捡最小的牌，不能捡分牌，
 * 扣几张捡几张"。
 *
 * <p>最小牌判定顺序 = 先看是主牌还是副牌 → 再看点数 → 最后看花色。这正是
 * {@link CardComparator} 的口径（{@code CardTier} 分层 + 同层比点数 + 花色兜底序），
 * 所以直接按其升序取前 N 张即可，不必再写一遍大小链条。
 *
 * <p>【与手册的一处已知简化】手册写"如果点数相同、花色不同，可由用户自己决定要哪张牌"，
 * 本实现不开放这项自选：兜底取花色序最靠前的那张（也就是 {@link CardComparator} 已经
 * 采用的稳定序）。理由：这几张牌在牌力上完全等价，捡哪张对双方都没有实质影响，
 * 为它多加一轮交互不值当。若将来要开放自选，只需要把这里换成"候选集下发给客户端挑选"。
 */
public final class BottomPickRule {

    private BottomPickRule() {
    }

    /**
     * 从底牌中挑出 {@code count} 张可被捡走的牌（非王、非分牌），按"最小"升序排列。
     *
     * @param bottom 底牌（含刚被扣进来的王；王与分牌都会被跳过）
     * @param trump  主牌上下文，不能为 null（扣王只会发生在主牌已定的扣底阶段）
     * @param count  要捡的张数
     * @return 最小在前；底牌里可捡的牌不足 {@code count} 时返回全部可捡的，
     *         由调用方（命令层）负责在扣王前用张数上限校验拦掉
     */
    public static List<Card> smallestPickable(List<Card> bottom, TrumpContext trump, int count) {
        Objects.requireNonNull(trump, "捡牌需要主牌上下文");
        List<Card> candidates = new ArrayList<>();
        for (Card c : bottom) {
            if (!c.isJoker() && c.points() == 0) {
                candidates.add(c);
            }
        }
        candidates.sort(new CardComparator(trump)); // 升序 = 牌力小的在前
        if (count >= candidates.size()) {
            return candidates;
        }
        return new ArrayList<>(candidates.subList(0, count));
    }
}
