package com.gunzihall.domain.play;

import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.trump.CardComparator;
import com.gunzihall.domain.trump.TrumpContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * 跟牌校验器（规则手册 3.2 + 3.3）。
 *
 * <p>核心概念——<strong>有效类别跟牌</strong>（手册 3.2"跟牌必须先出首家花色，没有该花色
 * 可用主牌杀或垫其他副牌"）：
 * <ul>
 *   <li>首出为副牌花色 X：手中"有效 ♥/♠/♦/♣ = X"的牌（级牌、2、主花色牌不算 X，它们是主牌）必须优先出；</li>
 *   <li>首出为主牌类别：手中的任何主牌（王、2、级牌、主花色）都算"跟牌池"；</li>
 *   <li>手中跟牌池不足 N 张时，必须全部跟出，剩余用主牌杀或垫牌补足；一张不剩时才完全自由。</li>
 * </ul>
 *
 * <p>活棒/死棒差异（3.3）只作用于"凑型"要求，见 {@link FollowRule}。
 */
public final class FollowValidator {

    private FollowValidator() {
    }

    /**
     * 校验一次跟牌是否合法。
     *
     * @param rule   房间跟牌模式
     * @param lead   首出牌型
     * @param hand   出牌前的完整手牌（含所出之牌）
     * @param played 本次所出之牌
     * @param ctx    主牌上下文
     * @return 合法返回 empty；非法返回原因（供客户端提示）
     */
    public static Optional<String> validate(FollowRule rule, Combo lead,
                                            List<Card> hand, List<Card> played,
                                            TrumpContext ctx) {
        int n = lead.size();
        if (played.size() != n) {
            return Optional.of("跟牌张数必须与首出一致: 需 " + n + " 张, 实出 " + played.size() + " 张");
        }

        CardComparator cmp = new CardComparator(ctx);
        Predicate<Card> matches = lead.isTrumpCategory()
                ? cmp::isTrump
                : c -> !cmp.isTrump(c) && c.suit() == lead.suit();

        // ---- 活棒：只做花色跟牌（有效类别），不要求凑型 ----
        if (rule == FollowRule.LIVE) {
            return checkSuitFollow(matches, hand, played, n);
        }

        // ---- 死棒：在花色跟牌基础上叠加"凑型"要求 ----
        return switch (lead.type()) {
            case SINGLE -> checkSuitFollow(matches, hand, played, n);
            case PAIR -> checkDeadPairFollow(matches, hand, played, n, ctx);
            case TRIPLE -> checkDeadTripleFollow(matches, hand, played, n, ctx);
        };
    }

    /**
     * 花色跟牌检查（手册 3.2）。
     * <p>池中牌数 ≥ N → 所出必须全部来自池；池中牌数 k &lt; N → 必须含全部 k 张池牌。
     */
    private static Optional<String> checkSuitFollow(Predicate<Card> matches,
                                                    List<Card> hand, List<Card> played, int n) {
        long handMatching = hand.stream().filter(matches).count();
        long playedMatching = played.stream().filter(matches).count();
        if (handMatching >= n) {
            if (playedMatching != n) {
                return Optional.of("有首家花色必须跟出（活棒可不凑型，但花色必须跟）");
            }
        } else if (playedMatching != handMatching) {
            return Optional.of("首家花色的牌必须全部跟出后才能垫牌/杀牌");
        }
        return Optional.empty();
    }

    /**
     * 死棒·首出棒子："打棒必须出棒，没有时可以给两张单张；没棒有滚子时可以不出滚子"。
     * <ol>
     *   <li>手中有纯棒子（跟牌池中某身份恰好 2 张）→ 必须出一张跟牌池棒子；</li>
     *   <li>没有棒子只有滚子 → 可拆滚子出棒，也可以给两张单张（手册明确允许不拆，从宽处理）；</li>
     *   <li>连滚子都没有 → 两张单张，仍须花色跟牌。</li>
     * </ol>
     */
    private static Optional<String> checkDeadPairFollow(Predicate<Card> matches,
                                                        List<Card> hand, List<Card> played,
                                                        int n, TrumpContext ctx) {
        Map<Card, Long> poolCounts = countByIdentity(hand, matches);
        boolean hasPurePair = poolCounts.values().stream().anyMatch(c -> c == 2);
        boolean hasGunner = poolCounts.values().stream().anyMatch(c -> c == 3);

        if (hasPurePair) {
            boolean playedPoolPair = played.stream().allMatch(matches)
                    && Combo.parse(played, ctx).map(c -> c.type() == ComboType.PAIR).orElse(false);
            if (!playedPoolPair) {
                return Optional.of("死棒规则：打棒必须出棒（手中有棒子必须出）");
            }
            return Optional.empty();
        }
        if (hasGunner) {
            // 出棒（拆滚子）合法；不拆则按单张跟牌检查（从宽：滚子身份不计入跟牌义务）
            boolean poolPair = played.stream().allMatch(matches)
                    && Combo.parse(played, ctx).map(c -> c.type() == ComboType.PAIR).orElse(false);
            if (poolPair) {
                return Optional.empty();
            }
            Predicate<Card> matchesExcludingGunner = matches.and(
                    c -> poolCounts.getOrDefault(c, 0L) != 3);
            return checkSuitFollow(matchesExcludingGunner, hand, played, n);
        }
        return checkSuitFollow(matches, hand, played, n);
    }

    /**
     * 死棒·首出滚子："打滚子必须出滚子，没有时按'棒+单张'或'3张单牌'顺序出"。
     * <ol>
     *   <li>手中有滚子 → 必须出滚子；</li>
     *   <li>没有滚子有棒子 → 必须棒+单张（棒来自跟牌池；单张优先跟花色）；</li>
     *   <li>都没有 → 三张单张，仍须花色跟牌。</li>
     * </ol>
     */
    private static Optional<String> checkDeadTripleFollow(Predicate<Card> matches,
                                                          List<Card> hand, List<Card> played,
                                                          int n, TrumpContext ctx) {
        Map<Card, Long> poolCounts = countByIdentity(hand, matches);
        boolean hasGunner = poolCounts.values().stream().anyMatch(c -> c == 3);
        boolean hasPair = poolCounts.values().stream().anyMatch(c -> c >= 2);

        if (hasGunner) {
            boolean poolTriple = played.stream().allMatch(matches)
                    && Combo.parse(played, ctx).map(c -> c.type() == ComboType.TRIPLE).orElse(false);
            if (!poolTriple) {
                return Optional.of("死棒规则：打滚子必须出滚子（手中有滚子必须出）");
            }
            return Optional.empty();
        }
        if (hasPair) {
            // 棒+单张：三张中恰有一个身份出现 2 次且来自跟牌池；单张若池中还有余牌必须跟花色
            Map<Card, Long> playedCounts = countByIdentity(played, c -> true);
            List<Card> pairPart = playedCounts.entrySet().stream()
                    .filter(e -> e.getValue() == 2)
                    .flatMap(e -> java.util.stream.Stream.generate(e::getKey).limit(2))
                    .toList();
            if (pairPart.size() != 2 || !matches.test(pairPart.get(0))) {
                return Optional.of("死棒规则：没有滚子时必须按'棒+单张'出（棒须为首家花色）");
            }
            long handMatching = hand.stream().filter(matches).count();
            // 池中除棒子外还有牌 → 第 3 张必须也是跟牌池的
            if (handMatching > 2) {
                Card third = played.stream()
                        .filter(c -> playedCounts.get(c) == 1)
                        .findFirst().orElse(null);
                if (third == null || !matches.test(third)) {
                    return Optional.of("死棒规则：棒+单张的单张有首家花色必须跟出");
                }
            }
            return Optional.empty();
        }
        return checkSuitFollow(matches, hand, played, n);
    }

    /** 按牌身份（同花色同点数/同王视为同一身份）统计手中满足条件的张数 */
    private static Map<Card, Long> countByIdentity(List<Card> cards, Predicate<Card> filter) {
        Map<Card, Long> counts = new HashMap<>();
        for (Card c : cards) {
            if (filter.test(c)) {
                counts.merge(c, 1L, Long::sum);
            }
        }
        return counts;
    }
}
