package com.gunzihall.domain.play;

import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.player.Seat;
import com.gunzihall.domain.scoring.ScoreCalculator;
import com.gunzihall.domain.trump.CardComparator;
import com.gunzihall.domain.trump.TrumpContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 一圈（一轮出牌）：首出 + 三家跟牌，共 4 手（手册 3.2）。
 *
 * <p>赢家判定：只有与首出<strong>同牌型</strong>且类别为"首出副花色或主牌"的合法牌型才能竞逐；
 * 垫牌、异型组合（两张单张等）永远不赢。主牌类别可杀副牌。同牌力先出者赢。
 * 一圈结束，赢家所在队伍收走该圈全部分牌。
 */
public final class Trick {

    /** 一次出牌记录：座位 + 牌 */
    public record PlayRecord(Seat seat, List<Card> cards) {
        public PlayRecord {
            cards = List.copyOf(cards);
        }
    }

    private final Seat leader;
    private final Combo lead;
    private final TrumpContext ctx;
    private final List<PlayRecord> plays = new ArrayList<>(4);

    public Trick(Seat leader, Combo lead, TrumpContext ctx) {
        this.leader = leader;
        this.lead = lead;
        this.ctx = ctx;
        plays.add(new PlayRecord(leader, lead.cards()));
    }

    /** 从既有记录重建（回放/测试用） */
    public Trick(Seat leader, Combo lead, TrumpContext ctx, List<PlayRecord> records) {
        this.leader = leader;
        this.lead = lead;
        this.ctx = ctx;
        plays.addAll(records);
    }

    public Seat leader() {
        return leader;
    }

    public Combo lead() {
        return lead;
    }

    public List<PlayRecord> plays() {
        return List.copyOf(plays);
    }

    /** 该圈是否已打满 4 手 */
    public boolean isComplete() {
        return plays.size() == 4;
    }

    /** 追加一手跟牌（合法性由 {@link FollowValidator} 在命令层先行校验） */
    public void play(Seat seat, List<Card> cards) {
        if (isComplete()) {
            throw new IllegalStateException("本圈已打满 4 手");
        }
        if (plays.stream().anyMatch(p -> p.seat() == seat)) {
            throw new IllegalStateException("本座位已出过牌: " + seat);
        }
        plays.add(new PlayRecord(seat, cards));
    }

    /** 回滚最后一手（命令撤销用） */
    public boolean removeLastPlay(Seat seat) {
        if (plays.isEmpty()) {
            return false;
        }
        PlayRecord last = plays.get(plays.size() - 1);
        if (last.seat() != seat) {
            return false;
        }
        plays.remove(plays.size() - 1);
        return true;
    }

    /**
     * 赢家座位：竞逐规则见类注释；同牌力先出者赢。
     */
    public Seat winnerSeat() {
        if (!isComplete()) {
            throw new IllegalStateException("一圈打满 4 手才能判定赢家");
        }
        PlayRecord best = plays.get(0);
        for (int i = 1; i < plays.size(); i++) {
            if (beats(plays.get(i), best)) {
                best = plays.get(i);
            }
        }
        return best.seat();
    }

    /** 该圈全部分牌分值（5/10/K） */
    public int points() {
        return plays.stream()
                .flatMap(p -> p.cards().stream())
                .mapToInt(Card::points)
                .sum();
    }

    /** 该圈打出的全部牌（赢家队伍收入囊中） */
    public List<Card> allCards() {
        return plays.stream()
                .flatMap(p -> p.cards().stream())
                .toList();
    }

    /**
     * p 能否压过当前最佳 best。
     * <p>只有合法牌型（单/棒/滚）且与首出同 type 才有竞逐资格；类别规则：
     * 首出为副牌时主牌可杀；同为竞逐者按牌力比较。
     */
    private boolean beats(PlayRecord p, PlayRecord best) {
        Optional<Combo> pc = Combo.parse(p.cards(), ctx);
        if (pc.isEmpty() || pc.get().type() != lead.type()) {
            return false; // 垫牌/异型永远不赢
        }
        Combo bc = Combo.parse(best.cards(), ctx).orElseThrow(
                () -> new IllegalStateException("当前最佳必须是合法牌型"));
        // 牌型不同不能比较（理论不可达：best 只能由本方法筛出的合法同型牌构成）
        if (pc.get().type() != bc.type()) {
            return false;
        }
        // 首出为副牌花色时，非该花色的副牌没有竞逐资格（主牌除外）
        if (!lead.isTrumpCategory() && !pc.get().isTrumpCategory()
                && pc.get().suit() != lead.suit()) {
            return false;
        }
        return pc.get().beats(bc);
    }

    @Override
    public String toString() {
        return "Trick{leader=" + leader + ", lead=" + lead + ", plays=" + plays.size() + "}";
    }
}
