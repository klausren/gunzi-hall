import { _decorator, Component } from 'cc';
import { NetClient } from './net/NetClient';
import type { SnapshotMsgDown } from './net/Protocol';

const { ccclass, property } = _decorator;

/**
 * 联调测试组件（Sprint 5）。
 *
 * 用法：
 *  1. 先启动战斗服（3 bot + 真人 NORTH）：
 *     server 目录下 mvn -pl game-room exec:java -Dexec.mainClass=com.gunzihall.room.ServerMain
 *  2. 在编辑器里新建场景，把本组件挂到 Canvas 节点
 *  3. 运行预览，控制台应看到 joined → snapshot → bot 们的 event 依次刷出
 *
 * 验证点：连上战斗服、拿到 token 鉴权、收到全量快照（手牌 40 张左右）、
 * bot 出牌事件推送正常。UI 在 Sprint 6 再做，本组件只做链路验证。
 */
@ccclass('NetTest')
export class NetTest extends Component {

    @property
    serverUrl = 'ws://localhost:8080/ws';

    @property
    roomId = 1001;

    @property
    playerId = 1;

    private net: NetClient | null = null;

    start(): void {
        this.net = new NetClient(this.serverUrl);
        this.net.onStateChange(online => {
            console.log(`[NetTest] ${online ? '已连接服务器' : '与服务器断开（自动重连中…）'}`);
        });
        this.net.onJoined(j => {
            console.log(`[NetTest] joined: seat=${j.seat} reconnect=${j.reconnect} token=${j.token?.slice(0, 8)}…`);
        });
        this.net.onSnapshot(s => this.dumpSnapshot(s));
        this.net.onEvent(e => {
            const who = e.seat ? `${e.seat}#${e.playerId}` : `${e.playerId}`;
            const fail = e.success === false ? ' ❌失败' : '';
            console.log(`[NetTest] event: ${e.op} by ${who}${fail}${e.cards?.length ? ` cards=${e.cards.join(',')}` : ''}`);
        });
        this.net.onError(reason => console.warn(`[NetTest] 服务器错误: ${reason}`));

        console.log(`[NetTest] 连接 ${this.serverUrl} …`);
        this.net.join(this.roomId, this.playerId, 'NORTH');
    }

    private dumpSnapshot(s: SnapshotMsgDown): void {
        const hand = s.yourHand ?? [];
        console.log('[NetTest] ====== 快照 ======');
        console.log(`  roomId=${s.roomId} 第${s.gameNumber}局 阶段=${s.phase} 级数=${s.level}`);
        const trump = s.trump ? `主牌=${s.trump.suit}` : '主牌=未定';
        console.log(`  ${trump}  庄家=${s.banker ?? '未定'}  轮到=${s.turn ?? '-'}`);
        if (s.reveal) {
            console.log(`  亮牌: ${s.reveal.kind} ${s.reveal.suit} by ${s.reveal.seat}`);
        }
        console.log(`  我的手牌（${hand.length} 张）: ${hand.join(' ')}`);
        if (s.hands) {
            console.log(`  各家余牌: ${JSON.stringify(s.hands)}`);
        }
        if (s.trick) {
            const plays = s.trick.plays.map(p => `${p.seat}:${p.cards.join(',')}`).join(' | ');
            console.log(`  当前墩: 首出 ${s.trick.leader}:${s.trick.leadCards.join(',')} ${plays ? '| 跟出 ' + plays : ''}`);
        }
        if (s.trickPoints && Object.keys(s.trickPoints).length) {
            console.log(`  已捡分: ${JSON.stringify(s.trickPoints)}`);
        }
    }

    onDestroy(): void {
        this.net?.close();
    }
}
