package com.gunzihall.room;

import java.util.List;

/**
 * 牌局状态存储：保存命令日志（牌局可复现审计 + 服务重启后重放恢复）。
 * <p>实现：进程内（测试）/ Redis（生产，架构 v0 4 节 KEY game:cmdlog:{roomId}）。
 */
public interface RoomStateStore {

    /** 追加一条已执行命令/入座记录（JSON） */
    void append(long roomId, String entryJson);

    /** 读取完整命令日志（按执行顺序） */
    List<String> commandLog(long roomId);

    /** 清空（房间解散时） */
    void clear(long roomId);
}
