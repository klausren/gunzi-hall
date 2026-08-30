package com.gunzihall.room;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 进程内命令日志存储（测试与本地单机模式）。 */
public final class InMemoryStateStore implements RoomStateStore {

    private final Map<Long, List<String>> logs = new ConcurrentHashMap<>();

    @Override
    public void append(long roomId, String entryJson) {
        logs.computeIfAbsent(roomId, k -> new CopyOnWriteArrayList<>()).add(entryJson);
    }

    @Override
    public List<String> commandLog(long roomId) {
        return new ArrayList<>(logs.getOrDefault(roomId, List.of()));
    }

    @Override
    public void clear(long roomId) {
        logs.remove(roomId);
    }
}
