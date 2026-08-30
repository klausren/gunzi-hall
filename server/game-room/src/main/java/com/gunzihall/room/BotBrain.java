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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bot 决策（规则版第一档，架构 v0：MVP 用简单策略，Sprint 7 换偏好向量 + 决策树）。
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
        // 三大王 / 三小王
        long bigCount = hand.stream().filter(c -> c.isJoker() && c.joker() == Joker.BIG).count();
        long smallCount = hand.stream().filter(c -> c.isJoker() && c.joker() == Joker.SMALL).count();
        if (bigCount >= 3) {
            return new RevealDecision(fillJokers(hand, Joker.BIG, 3), longestSuit(hand));
        }
        if (smallCount >= 3) {
            return new RevealDecision(fillJokers(hand, Joker.SMALL, 3), longestSuit(hand));
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
            return new RevealDecision(List.copyOf(cards), bestSuit);
        }
        return null;
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

    // ================= 出牌（PLAYING） =================

    /** 首出：出最小的单张（优先非主）。 */
    public static List<Card> leadPlay(GameRoom room, Player p) {
        TrumpContext trump = room.trump().orElseThrow();
        CardComparator cmp = new CardComparator(trump);
        List<Card> hand = new ArrayList<>(p.hand());
        hand.sort(Comparator
                .<Card>comparingInt(c -> cmp.isTrump(c) ? 1 : 0)
                .thenComparing(cmp));
        return List.of(hand.get(0));
    }

    /**
     * 跟牌：枚举所有同张数候选（≤3 张，组合数最多 C(39,3)≈9k），
     * 用 {@link FollowValidator} 过滤合法项，选"动用主牌最少、最强牌最弱"的。
     */
    public static List<Card> followPlay(GameRoom room, Player p) {
        Trick trick = room.currentTrick().orElseThrow();
        TrumpContext trump = room.trump().orElseThrow();
        CardComparator cmp = new CardComparator(trump);
        int n = trick.lead().size();
        List<Card> hand = new ArrayList<>(p.hand());

        List<Card> best = null;
        int bestTrumpCount = Integer.MAX_VALUE;
        Card bestMaxCard = null;

        for (List<Card> candidate : combinations(hand, n)) {
            if (FollowValidator.validate(room.followRule(), trick.lead(),
                    p.hand(), candidate, trump).isPresent()) {
                continue;
            }
            int trumpCount = 0;
            Card maxCard = null;
            for (Card c : candidate) {
                if (cmp.isTrump(c)) {
                    trumpCount++;
                }
                if (maxCard == null || cmp.compare(c, maxCard) > 0) {
                    maxCard = c;
                }
            }
            if (best == null
                    || trumpCount < bestTrumpCount
                    || (trumpCount == bestTrumpCount && cmp.compare(maxCard, bestMaxCard) < 0)) {
                best = candidate;
                bestTrumpCount = trumpCount;
                bestMaxCard = maxCard;
            }
        }
        return best;
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
