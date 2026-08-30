package com.gunzihall.room;

import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.card.Joker;
import com.gunzihall.domain.card.Suit;
import com.gunzihall.domain.play.Combo;
import com.gunzihall.domain.play.FollowValidator;
import com.gunzihall.domain.play.Trick;
import com.gunzihall.domain.player.Player;
import com.gunzihall.domain.room.GameRoom;
import com.gunzihall.domain.trump.CardComparator;
import com.gunzihall.domain.trump.TrumpContext;
import com.gunzihall.domain.trump.TrumpReveal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bot 决策（规则版第二档：抢墩 + 搭档 + 资产意识；后续可换偏好向量 + 决策树）。
 *
 * <p>所有决策只产出"候选命令参数"，合法性由领域命令的服务端权威校验兜底——
 * bot 想出非法牌也会被拒绝，不存在作弊路径。
 */
public final class BotBrain {

    private BotBrain() {
    }

    // ================= 亮王（BIDDING） =================

    /**
     * 决定是否亮主。返回 null = 过。
     * <p>首局：持大王必抢（claimSuit = 手中最长花色）；
     * 第二局起：三王 > 多张级牌，能反就反（canBeOverriddenBy 由命令校验）。
     */
    public static RevealDecision decideReveal(GameRoom room, Player p) {
        List<Card> hand = p.hand();
        if (room.isFirstRound()) {
            Optional<Card> bigJoker = hand.stream()
                    .filter(c -> c.isJoker() && c.joker() == Joker.BIG).findFirst();
            if (bigJoker.isPresent() && room.revealState().isEmpty()) {
                return new RevealDecision(List.of(bigJoker.get()), longestSuit(hand));
            }
            return null;
        }
        // 第二局起：三王 > 多张级牌；先按 canBeOverriddenBy 自检，
        // 不做注定失败的反主尝试（失败事件会以 toast 噪音形式出现在客户端）
        long bigCount = hand.stream().filter(c -> c.isJoker() && c.joker() == Joker.BIG).count();
        long smallCount = hand.stream().filter(c -> c.isJoker() && c.joker() == Joker.SMALL).count();
        Suit claim = longestSuit(hand);
        if (bigCount >= 3 && canOverride(room, TrumpReveal.tripleBigJoker(p.seat(), claim))) {
            return new RevealDecision(fillJokers(hand, Joker.BIG, 3), claim);
        }
        if (smallCount >= 3 && canOverride(room, TrumpReveal.tripleSmallJoker(p.seat(), claim))) {
            return new RevealDecision(fillJokers(hand, Joker.SMALL, 3), claim);
        }
        // 级牌：同花色级牌越多越好（1..3 张）
        int level = room.currentLevel();
        Map<Suit, List<Card>> bySuit = new EnumMap<>(Suit.class);
        for (Card c : hand) {
            if (!c.isJoker() && c.rank() == level) {
                bySuit.computeIfAbsent(c.suit(), k -> new ArrayList<>()).add(c);
            }
        }
        Suit bestSuit = null;
        for (Map.Entry<Suit, List<Card>> e : bySuit.entrySet()) {
            if (bestSuit == null || e.getValue().size() > bySuit.get(bestSuit).size()) {
                bestSuit = e.getKey();
            }
        }
        if (bestSuit != null) {
            List<Card> cards = bySuit.get(bestSuit);
            if (canOverride(room, TrumpReveal.levelCards(p.seat(), bestSuit, cards.size()))) {
                return new RevealDecision(List.copyOf(cards), bestSuit);
            }
        }
        return null;
    }

    /** 无既有声明 → 恒可亮；有声明 → 按 Q2b/Q2c 预判反主是否成立 */
    private static boolean canOverride(GameRoom room, TrumpReveal candidate) {
        return room.revealState()
                .map(cur -> cur.canBeOverriddenBy(candidate))
                .orElse(true);
    }

    private static List<Card> fillJokers(List<Card> hand, Joker joker, int n) {
        List<Card> out = new ArrayList<>();
        for (Card c : hand) {
            if (c.isJoker() && c.joker() == joker && out.size() < n) {
                out.add(c);
            }
        }
        return out;
    }

    /** 手中最长的花色（王与 2/级牌名义花色仍可作 claim，命令层只看声明合法性） */
    private static Suit longestSuit(List<Card> hand) {
        Map<Suit, Integer> counts = new EnumMap<>(Suit.class);
        for (Card c : hand) {
            if (!c.isJoker()) {
                counts.merge(c.suit(), 1, Integer::sum);
            }
        }
        Suit best = Suit.SPADE;
        int bestCount = -1;
        for (Map.Entry<Suit, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best;
    }

    public record RevealDecision(List<Card> cards, Suit claimSuit) {
    }

    // ================= 进贡 / 还贡（TRIBUTE） =================

    /**
     * 进贡牌选择：与 TributeCommand 的服务端校验算法保持一致——
     * 候选 = 手牌剔分牌（5/10/K，级牌除外身份优先），按主牌体系从大到小取前 N 张。
     */
    public static List<Card> tributeCards(GameRoom room, Player payer, int bloodCount) {
        TrumpContext trump = room.trump().orElseThrow();
        CardComparator cmp = new CardComparator(trump);
        List<Card> candidates = new ArrayList<>(payer.hand());
        candidates.removeIf(c -> c.points() > 0
                && (c.isJoker() || c.rank() != trump.level()));
        candidates.sort(cmp.reversed());
        return List.copyOf(candidates.subList(0, Math.min(bloodCount, candidates.size())));
    }

    /** 还贡：还手中最小的牌（手册未限制还贡牌，任何牌合法） */
    public static List<Card> returnTributeCards(GameRoom room, Player receiver, int count) {
        CardComparator cmp = new CardComparator(room.trump().orElseThrow());
        List<Card> sorted = new ArrayList<>(receiver.hand());
        sorted.sort(cmp);
        return List.copyOf(sorted.subList(0, Math.min(count, sorted.size())));
    }

    // ================= 扣底（BURYING） =================

    /**
     * 扣底策略：埋最没用的牌——非主非分小点数优先；
     * 极端手牌不足时才埋主牌，王最后（且先大王后小王，满足 Q5）。
     * <p>注意此时庄家手牌 = 原手牌 + 已收底的底牌（由调用方拼好）。
     * 干锅局（原底牌无主花色普通牌）只能原样扣回。
     */
    public static List<Card> buryCards(GameRoom room, Player banker, List<Card> combined) {
        List<Card> originalBottom = room.bottomCards();
        // 干锅判定（与 BuryBottomCommand 相同口径）
        TrumpContext trump = room.trump().orElseThrow();
        long trumpPlain = originalBottom.stream()
                .filter(c -> !c.isJoker() && c.rank() != 2 && c.rank() != trump.level())
                .filter(c -> c.suit() == trump.trumpSuit())
                .count();
        if (trumpPlain == 0) {
            return List.copyOf(originalBottom); // 干锅：原样扣回
        }

        CardComparator cmp = new CardComparator(trump);
        int n = originalBottom.size();
        // 埋牌等级：0 非主普通 < 2 非主分牌 < 3 主牌 < 4 大王 < 5 小王
        // （同级内点数从小到大，cmp 升序）——保证非主牌没埋够 6 张前绝不碰主牌，
        // 被迫埋王时大王一定排在小王前面（Q5 先大王后小王）。
        List<Card> pool = new ArrayList<>(combined);
        pool.sort(Comparator
                .<Card>comparingInt(c -> buryClass(c, trump))
                .thenComparing(cmp));
        return List.copyOf(pool.subList(0, Math.min(n, pool.size())));
    }

    /** 埋牌等级（数值越大越不该埋） */
    private static int buryClass(Card c, TrumpContext trump) {
        if (c.isJoker()) {
            return c.joker() == Joker.BIG ? 4 : 5;
        }
        boolean isTrump = c.rank() == 2 || c.rank() == trump.level()
                || c.suit() == trump.trumpSuit();
        if (isTrump) {
            return 3;
        }
        return c.points() > 0 ? 2 : 0;
    }

    // ================= 出牌（PLAYING，v2 抢墩策略） =================

    /**
     * 首出 v2：能抢就抢，不能抢保资产。
     * <ol>
     *   <li>主牌棒/滚 → 领出（清主 + 抢墩）；</li>
     *   <li>副牌 K/A 棒 → 领出收分；</li>
     *   <li>残局（余牌 ≤8）持大王/主过半 → 领最大主牌单张收墩；</li>
     *   <li>默认：最小非主单张（v1 行为，保资产）。</li>
     * </ol>
     */
    public static List<Card> leadPlay(GameRoom room, Player p) {
        TrumpContext trump = room.trump().orElseThrow();
        CardComparator cmp = new CardComparator(trump);
        List<Card> hand = new ArrayList<>(p.hand());

        // 身份分组（Card 按值 equals：同花色同点数/同王 → 棒/滚）
        Map<Card, List<Card>> groups = new LinkedHashMap<>();
        for (Card c : hand) {
            groups.computeIfAbsent(c, k -> new ArrayList<>()).add(c);
        }
        List<Card> bestTrumpSet = null;
        List<Card> bestHighSideSet = null;
        for (List<Card> g : groups.values()) {
            Card rep = g.get(0);
            if (cmp.isTrump(rep)) {
                if (bestTrumpSet == null || cmp.compare(rep, bestTrumpSet.get(0)) > 0) {
                    bestTrumpSet = g;
                }
            } else if (rep.rank() >= 13) {
                if (bestHighSideSet == null || cmp.compare(rep, bestHighSideSet.get(0)) > 0) {
                    bestHighSideSet = g;
                }
            }
        }
        if (bestTrumpSet != null) return List.copyOf(bestTrumpSet);
        if (bestHighSideSet != null) return List.copyOf(bestHighSideSet);

        if (hand.size() <= 8) {
            List<Card> trumps = new ArrayList<>(hand);
            trumps.removeIf(c -> !cmp.isTrump(c));
            if (!trumps.isEmpty()) {
                trumps.sort(cmp.reversed());
                Card top = trumps.get(0);
                if (top.isJoker() || trumps.size() * 2 >= hand.size()) {
                    return List.of(top);
                }
            }
        }

        List<Card> sorted = new ArrayList<>(hand);
        sorted.sort(Comparator
                .<Card>comparingInt(c -> cmp.isTrump(c) ? 1 : 0)
                .thenComparing(cmp));
        return List.of(sorted.get(0));
    }

    /**
     * 跟牌 v2：抢墩意识 + 搭档意识 + 资产管理。
     * <ul>
     *   <li>当前领先者是对家 → 垫最弱省资产（高分局且非末家时可用非王牌加固）；</li>
     *   <li>当前领先者是对手 → 能压就压（选"最小能赢"的），墩分越高可动用的资产
     *       上限越高（0 分 ≤ 副2，5 分 ≤ 主2，10 分 ≤ 主级牌，15 分以上可动王）；</li>
     *   <li>末家 0 分墩不花王（领出权价值有限）；垫牌尽量不把分喂给赢家。</li>
     * </ul>
     * <p>候选合法性仍由 {@link FollowValidator} 过滤，非法牌不可能被选出。
     */
    public static List<Card> followPlay(GameRoom room, Player p) {
        Trick trick = room.currentTrick().orElseThrow();
        TrumpContext trump = room.trump().orElseThrow();
        Combo lead = trick.lead();
        int n = lead.size();
        List<Card> hand = new ArrayList<>(p.hand());

        Trick.PlayRecord best = currentWinner(trick, trump);
        boolean partnerLeading = best.seat().team() == p.seat().team();
        boolean isLast = trick.plays().size() == 3;
        int trickPts = trick.points();
        boolean attacker = room.bankerSeat()
                .map(bs -> bs.team() != p.seat().team()).orElse(false);

        List<List<Card>> winners = new ArrayList<>();
        List<List<Card>> losers = new ArrayList<>();
        for (List<Card> candidate : combinations(hand, n)) {
            if (FollowValidator.validate(room.followRule(), lead, p.hand(), candidate, trump).isPresent()) {
                continue;
            }
            if (beatsCurrent(candidate, best.cards(), lead, trump)) {
                winners.add(candidate);
            } else {
                losers.add(candidate);
            }
        }
        if (winners.isEmpty() && losers.isEmpty()) {
            return null;
        }

        if (partnerLeading) {
            // 对家领先：末家已稳收；非末家高分局可用强牌加固（不动王）
            if (!isLast && trickPts >= 15 && !winners.isEmpty()) {
                List<Card> strong = strongest(winners, trump);
                if (strong.stream().noneMatch(Card::isJoker)) {
                    return strong;
                }
            }
            if (!losers.isEmpty()) return cheapest(losers, trump, true);
            return cheapest(winners, trump, false);
        }

        // 对手领先
        if (!winners.isEmpty()) {
            if (isLast) {
                // 末家精确结算：有分（或抓分方需要分）就用最小赢牌收墩
                if (trickPts > 0 || attacker) {
                    return cheapest(winners, trump, false);
                }
                // 0 分墩：领出权有点价值，但不动王
                List<Card> w = cheapest(winners, trump, false);
                if (w.stream().noneMatch(Card::isJoker)) return w;
                if (!losers.isEmpty()) return cheapest(losers, trump, true);
                return w;
            }
            // 非末家：值不值——按墩分决定可动用资产上限，抓分方放宽一档
            List<Card> w = cheapest(winners, trump, false);
            int cap = trickPts >= 15 ? 80 : trickPts >= 10 ? 78 : trickPts >= 5 ? 76 : 75;
            if (attacker) cap = Math.min(80, cap + 1);
            if (maxTier(w, trump) <= cap) {
                return w;
            }
        }
        return losers.isEmpty() ? cheapest(winners, trump, false) : cheapest(losers, trump, true);
    }

    /** 当前墩暂时领先的一手（竞逐口径与 Trick.beats 一致：垫牌/异型永不赢） */
    private static Trick.PlayRecord currentWinner(Trick trick, TrumpContext trump) {
        List<Trick.PlayRecord> plays = trick.plays();
        Trick.PlayRecord best = plays.get(0);
        for (int i = 1; i < plays.size(); i++) {
            if (beatsCurrent(plays.get(i).cards(), best.cards(), trick.lead(), trump)) {
                best = plays.get(i);
            }
        }
        return best;
    }

    /** 候选能否压过当前最佳（同 Trick.beats：须同型、副牌须同花色、主牌可杀） */
    private static boolean beatsCurrent(List<Card> candidate, List<Card> bestCards,
                                        Combo lead, TrumpContext trump) {
        Optional<Combo> pc = Combo.parse(candidate, trump);
        if (pc.isEmpty() || pc.get().type() != lead.type()) {
            return false;
        }
        Combo bc = Combo.parse(bestCards, trump).orElseThrow();
        if (!lead.isTrumpCategory() && !pc.get().isTrumpCategory()
                && pc.get().suit() != lead.suit()) {
            return false;
        }
        return pc.get().beats(bc);
    }

    /**
     * 全局牌力序数（越大越强，口径同手册 2.1）：
     * 大王80 &gt; 小王79 &gt; 主级牌78 &gt; 副级牌77 &gt; 主2 76 &gt; 副2 75
     * &gt; 主普通 40+点 &gt; 副牌点数。
     */
    private static int tierOf(Card c, TrumpContext trump) {
        if (c.isJoker()) return c.joker() == Joker.BIG ? 80 : 79;
        if (c.suit() == trump.trumpSuit() && c.rank() == trump.level()) return 78;
        if (c.rank() == trump.level()) return 77;
        if (c.suit() == trump.trumpSuit() && c.rank() == 2) return 76;
        if (c.rank() == 2) return 75;
        if (c.suit() == trump.trumpSuit()) return 40 + c.rank();
        return c.rank();
    }

    /**
     * 成本最低的候选。
     * discard=true：垫牌给（可能）对手赢的墩——分牌加罚，别把分喂给赢家；
     * discard=false：抢墩成本（纯牌力消耗，最小能赢）。
     */
    private static List<Card> cheapest(List<List<Card>> cands, TrumpContext trump, boolean discard) {
        List<Card> best = null;
        int bestCost = Integer.MAX_VALUE;
        for (List<Card> c : cands) {
            int cost = 0;
            for (Card card : c) {
                cost += tierOf(card, trump) + (discard && card.points() > 0 ? 25 : 0);
            }
            if (cost < bestCost) {
                bestCost = cost;
                best = c;
            }
        }
        return best;
    }

    /** 最强候选（对家领先时加固用） */
    private static List<Card> strongest(List<List<Card>> cands, TrumpContext trump) {
        List<Card> best = null;
        int bestTier = Integer.MIN_VALUE;
        for (List<Card> c : cands) {
            int t = maxTier(c, trump);
            if (t > bestTier) {
                bestTier = t;
                best = c;
            }
        }
        return best;
    }

    private static int maxTier(List<Card> cards, TrumpContext trump) {
        int t = 0;
        for (Card c : cards) {
            t = Math.max(t, tierOf(c, trump));
        }
        return t;
    }

    /** 组合枚举（组合数上限 9k 量级，足够小；n=1/2/3） */
    private static Iterable<List<Card>> combinations(List<Card> cards, int n) {
        List<List<Card>> out = new ArrayList<>();
        dfs(cards, 0, n, new ArrayList<>(), out);
        return out;
    }

    private static void dfs(List<Card> cards, int start, int n, List<Card> path, List<List<Card>> out) {
        if (path.size() == n) {
            out.add(List.copyOf(path));
            return;
        }
        for (int i = start; i <= cards.size() - (n - path.size()); i++) {
            path.add(cards.get(i));
            dfs(cards, i + 1, n, path, out);
            path.remove(path.size() - 1);
        }
    }
}
