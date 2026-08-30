package com.gunzihall.room;

import com.gunzihall.domain.card.Card;
import com.gunzihall.domain.card.Joker;
import com.gunzihall.domain.card.Suit;

import java.util.ArrayList;
import java.util.List;

/**
 * 牌与传输编码之间的转换。
 * <p>编码规则：花色牌 = 花色字母 + 点数（S14/H5/D10/C13），大王 BJ，小王 SJ。
 * 三副牌中同点同花的多张牌身份相同（领域层 equals 按身份），编码亦不区分副本。
 */
public final class CardCodec {

    private CardCodec() {
    }

    public static String encode(Card card) {
        if (card.isJoker()) {
            return card.joker() == Joker.BIG ? "BJ" : "SJ";
        }
        char suit = switch (card.suit()) {
            case SPADE -> 'S';
            case HEART -> 'H';
            case DIAMOND -> 'D';
            case CLUB -> 'C';
        };
        return "" + suit + card.rank();
    }

    public static List<String> encodeAll(List<Card> cards) {
        List<String> out = new ArrayList<>(cards.size());
        for (Card c : cards) {
            out.add(encode(c));
        }
        return out;
    }

    public static Card decode(String code) {
        if (code == null || code.isEmpty()) {
            throw new IllegalArgumentException("牌编码为空");
        }
        if ("BJ".equals(code)) {
            return Card.bigJoker();
        }
        if ("SJ".equals(code)) {
            return Card.smallJoker();
        }
        Suit suit = switch (code.charAt(0)) {
            case 'S' -> Suit.SPADE;
            case 'H' -> Suit.HEART;
            case 'D' -> Suit.DIAMOND;
            case 'C' -> Suit.CLUB;
            default -> throw new IllegalArgumentException("未知花色编码: " + code);
        };
        int rank = Integer.parseInt(code.substring(1));
        return Card.of(suit, rank);
    }

    public static List<Card> decodeAll(List<String> codes) {
        List<Card> out = new ArrayList<>(codes.size());
        for (String c : codes) {
            out.add(decode(c));
        }
        return out;
    }
}
