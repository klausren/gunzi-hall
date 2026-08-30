package com.gunzihall.room;

import com.gunzihall.domain.player.Seat;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Sprint 4 验收 Demo：4 个 bot 自动打完整 N 局，命令行输出全过程。
 * <p>运行：{@code mvn -pl game-room exec:java}（可选参数：局数 出牌间隔ms）
 * <p>诊断保障：所有输出走 println 自动刷新；看门狗每 5s 打印阶段；
 * 超时自动 dump 现场（phase/turn/手牌数/stuck）后退出，不再无限等待。
 */
public final class BotGameDemo {

    public static void main(String[] args) throws Exception {
        int games = args.length > 0 ? Integer.parseInt(args[0]) : 2;
        long botDelayMs = args.length > 1 ? Long.parseLong(args[1]) : 100;
        int awaitSec = args.length > 2 ? Integer.parseInt(args[2]) : 120;

        System.out.println("=== 打滚子 4-Bot 自动牌局 Demo（" + games + " 局，间隔 " + botDelayMs + "ms） ===\n");

        RoomManager manager = new RoomManager(new InMemoryStateStore(), botDelayMs);
        long roomId = 1001;
        RoomActor actor = manager.create(roomId, Set.of(Seat.NORTH, Seat.EAST, Seat.SOUTH, Seat.WEST));
        actor.setStopAfterGames(games);

        CountDownLatch done = new CountDownLatch(1);
        final int[] eventCount = {0};
        final int[] settles = {0};
        final long[] lastEventAt = {System.currentTimeMillis()};
        actor.addRawListener(json -> {
            Map<String, Object> ev = JsonUtil.read(json, Map.class);
            lastEventAt[0] = System.currentTimeMillis();
            String op = String.valueOf(ev.get("op"));
            boolean success = Boolean.TRUE.equals(ev.get("success"));
            if (!success) {
                System.out.println("!! 失败事件: " + json);
            } else {
                eventCount[0]++;
                // 全事件打印（DEAL/CONFIRM/RESOLVE_BOTTOM 也打，便于定位静默卡死）
                String seat = String.valueOf(ev.get("seat"));
                @SuppressWarnings("unchecked")
                var cards = (java.util.List<Object>) ev.get("cards");
                String cardStr = cards == null || cards.isEmpty() ? "" : " " + cards;
                String line = "局" + ev.get("gameNumber") + " " + seat + " " + op + cardStr
                        + (op.equals("SETTLE") ? "" : "");
                System.out.println(line);
                if ("SETTLE".equals(op)) {
                    settles[0]++;
                    System.out.println("局" + settles[0] + " ---- 结算完成（第 " + settles[0]
                            + "/" + games + " 局结束） ----");
                }
            }
            // 完成条件：实际打满 games 局（SETTLE 事件计数，gameNumber 会被结算命令 +1 不可靠）
            if (settles[0] >= games) {
                done.countDown();
            }
        });

        // 看门狗：5s 无事件打印现场
        Thread watchdog = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                }
                long idle = System.currentTimeMillis() - lastEventAt[0];
                if (idle > 5000 && done.getCount() > 0) {
                    System.out.println("[watchdog] 已 " + (idle / 1000) + "s 无事件 | phase="
                            + actor.room().phase() + " game=" + actor.room().gameNumber()
                            + " turn=" + actor.room().turnSeat().map(Seat::name).orElse("-")
                            + " stuck=" + actor.isStuck()
                            + " hands=" + handsSummary(actor));
                }
            }
        }, "demo-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        long t0 = System.currentTimeMillis();
        actor.start();
        boolean finished = done.await(awaitSec, TimeUnit.SECONDS);

        System.out.println("\n=== 结果 ===");
        System.out.println("耗时: " + (System.currentTimeMillis() - t0) + " ms，成功事件: " + eventCount[0]);
        System.out.println("终态: phase=" + actor.room().phase()
                + " game=" + actor.room().gameNumber()
                + " level=" + actor.room().currentLevel()
                + " stuck=" + actor.isStuck());
        if (!finished) {
            System.out.println("!! " + awaitSec + "s 内未完成，现场快照：");
            System.out.println(actor.snapshotFor(0));
        }
        actor.shutdown();
        System.exit(finished ? 0 : 2);
    }

    private static String handsSummary(RoomActor actor) {
        StringBuilder sb = new StringBuilder("{");
        for (Seat seat : Seat.values()) {
            var p = actor.room().players().get(seat);
            if (p != null) {
                sb.append(seat.name()).append("=").append(p.hand().size()).append(" ");
            }
        }
        return sb.append("}").toString();
    }

    private BotGameDemo() {
    }
}
