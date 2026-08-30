package com.gunzihall.room;

import com.gunzihall.domain.player.Seat;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 帧处理：JSON 消息路由 + 心跳 + 会话生命周期。
 * <p>客户端协议：
 * <pre>
 * {"op":"join","roomId":1001,"playerId":1,"seat":"NORTH"}
 * {"op":"ping"}                                        → {"type":"pong"}
 * {"op":"snapshot","roomId":1001,"playerId":1}
 * {"op":"cmd","type":"PLAY","roomId":1001,"playerId":1,"cards":["H5"]}
 * </pre>
 */
public final class WsServerHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    private static final AttributeKey<ChannelSink> SINK =
            AttributeKey.valueOf("gunzi.sink");

    private final RoomManager manager;
    /** channel → playerId（断线时反查解绑） */
    private final Map<Channel, Long> bound = new ConcurrentHashMap<>();

    public WsServerHandler(RoomManager manager) {
        this.manager = manager;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        String text = frame.text();
        ClientMsg msg;
        try {
            msg = JsonUtil.read(text, ClientMsg.class);
        } catch (Exception e) {
            send(ctx, JsonUtil.write(Map.of("type", "error", "reason", "消息解析失败: " + e.getMessage())));
            return;
        }
        if (msg.op() == null) {
            send(ctx, JsonUtil.write(Map.of("type", "error", "reason", "缺少 op 字段")));
            return;
        }
        switch (msg.op()) {
            case "ping" -> send(ctx, JsonUtil.write(Map.of("type", "pong")));
            case "join" -> handleJoin(ctx, msg);
            case "snapshot" -> manager.snapshot(msg.roomId(), msg.playerId(), sinkOf(ctx, msg));
            case "cmd" -> handleCmd(ctx, msg);
            default -> send(ctx, JsonUtil.write(Map.of("type", "error", "reason", "未知 op: " + msg.op())));
        }
    }

    private void handleJoin(ChannelHandlerContext ctx, ClientMsg msg) {
        if (msg.roomId() <= 0 || msg.playerId() <= 0 || msg.seat() == null) {
            send(ctx, JsonUtil.write(Map.of("type", "error", "reason", "join 需要 roomId/playerId/seat")));
            return;
        }
        ChannelSink sink = sinkOf(ctx, msg);
        String reply = manager.join(msg.roomId(), msg.playerId(), Seat.valueOf(msg.seat()), sink);
        // join 回执（成功 joined / 失败 error）总是发送给发起方
        send(ctx, reply);
    }

    private void handleCmd(ChannelHandlerContext ctx, ClientMsg msg) {
        if (msg.type() == null) {
            send(ctx, JsonUtil.write(Map.of("type", "error", "reason", "cmd 需要 type 字段")));
            return;
        }
        ChannelSink sink = sinkOf(ctx, msg);
        manager.route(msg.roomId(), new RoomActor.CommandSpec(
                msg.type(), msg.playerId(), msg.cards(), msg.suit(), msg.payee(), msg.indexes(), msg.seed()), sink);
    }

    private ChannelSink sinkOf(ChannelHandlerContext ctx, ClientMsg msg) {
        ChannelSink sink = ctx.channel().attr(SINK).get();
        if (sink == null) {
            sink = new ChannelSink(ctx.channel(), msg.playerId());
            ctx.channel().attr(SINK).set(sink);
        } else {
            sink.playerId = msg.playerId();
        }
        return sink;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            ctx.close(); // 90s 读空闲：判定掉线
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // WS 协议层心跳
        if (msg instanceof PingWebSocketFrame ping) {
            ctx.writeAndFlush(new PongWebSocketFrame(ping.content().retain()));
            return;
        }
        if (msg instanceof PongWebSocketFrame) {
            return;
        }
        super.channelRead(ctx, msg);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        ChannelSink sink = ctx.channel().attr(SINK).get();
        if (sink != null) {
            manager.detach(sink);
        }
        bound.remove(ctx.channel());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }

    private void send(ChannelHandlerContext ctx, String json) {
        ctx.writeAndFlush(new TextWebSocketFrame(json));
    }

    /** channel 包装的 sink：断线重连后重新 join 会生成新 sink 绑定新 channel */
    static final class ChannelSink implements RoomActor.Sink {
        private final Channel channel;
        volatile long playerId;

        ChannelSink(Channel channel, long playerId) {
            this.channel = channel;
            this.playerId = playerId;
        }

        @Override
        public long playerId() {
            return playerId;
        }

        @Override
        public void send(String json) {
            if (channel.isActive()) {
                channel.writeAndFlush(new TextWebSocketFrame(json));
            }
        }
    }
}
