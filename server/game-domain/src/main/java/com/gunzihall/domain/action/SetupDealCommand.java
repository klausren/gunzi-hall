package com.gunzihall.domain.action;

import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.deck.Deck;
import com.gunzihall.domain.player.Seat;
import com.gunzihall.domain.room.GamePhase;
import com.gunzihall.domain.room.GameRoom;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 逐张发牌第一步：洗牌并把整副牌放进房间牌堆，房间进入 DEALING。
 *
 * <p>随后由 {@link DealNextCardCommand} 一张一张发（每张一步，节奏由编排层的
 * 发牌延迟控制），发满 156 张后余下的 6 张自动成为底牌并进入 BIDDING。
 *
 * <p>为什么要拆成两步而不是改 {@link ShuffleAndDealCommand}：
 * 现有 6 个测试文件都直接断言"发完牌后阶段 = BIDDING、手牌 39 张 39 张"，
 * 那条一次性发完的旧路径原封不动保留，逐张发牌走新命令，
 * 两条路径并存、互不干扰。
 */
public final class SetupDealCommand extends AbstractGameCommand {

    private final long seed;

    public SetupDealCommand(long roomId, long playerId, long seed) {
        super(roomId, playerId);
        this.seed = seed;
    }

    public long seed() {
        return seed;
    }

    @Override
    public CommandResult execute(GameRoom room) {
        if (!room.isFull()) {
            return CommandResult.fail("房间未满 4 人，不能发牌");
        }
        if (room.phase() != GamePhase.WAITING && room.phase() != GamePhase.DEALING) {
            return CommandResult.fail("当前阶段不能洗牌发牌: " + room.phase());
        }
        if (room.phase() == GamePhase.WAITING) {
            room.transitionTo(GamePhase.DEALING);
        }

        Deck deck = Deck.fresh();
        deck.shuffle(new Random(seed));

        // 清手牌：新一局开始，四家都从空手起摸。
        // 这里用可变 ArrayList —— DealNextCardCommand 会往这些 list 里逐张 add，
        // 若放不可变 list（List.copyOf）会在第一次发牌时抛 UnsupportedOperationException。
        Map<Seat, List<Card>> hands = new EnumMap<>(Seat.class);
        for (Seat seat : Seat.values()) {
            room.playerAt(seat).hand().clear();
            hands.put(seat, new ArrayList<>(Deck.HAND_SIZE));
        }
        room.setHands(hands);
        room.setBottomCards(List.of());
        room.prepareDealPile(deck.cards(), Seat.NORTH);
        return CommandResult.ok();
    }
}
