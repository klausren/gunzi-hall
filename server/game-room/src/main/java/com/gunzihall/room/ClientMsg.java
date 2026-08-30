package com.gunzihall.room;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** 客户端上行消息（字段全部可选，按 op 使用） */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClientMsg(
        String op,
        String type,
        long roomId,
        long playerId,
        String seat,
        String suit,
        String payee,
        List<String> cards,
        List<Integer> indexes,
        Long seed,
        String token) {

    public ClientMsg {
        // record 反序列化时 null 容器字段统一成空值防 NPE
        cards = cards == null ? List.of() : cards;
        indexes = indexes == null ? List.of() : indexes;
    }
}
