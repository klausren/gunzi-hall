# 打滚子客户端（Cocos Creator 3.x）

> Sprint 5 起步。当前内容：与战斗服对齐的网络层（编辑器无关的纯 TypeScript，
> 已对真实服务器做过端到端冒烟验证）。

## 目录

```
client/
  assets/
    scripts/
      net/
        Protocol.ts    协议与类型定义（上行/下行消息、牌编码）
        NetClient.ts   WebSocket 封装：心跳 / 指数退避重连 / token 自动管理
```

## 接入 Cocos 工程

1. 安装 Cocos Dashboard + Creator 3.8.x，新建空项目（或直接用本项目后续工程）
2. 把 `assets/scripts/net/` 拷贝（或软链）到工程 `assets/scripts/net/`
3. 场景组件中使用：

```typescript
import { _decorator, Component } from 'cc';
import { NetClient } from '../net/NetClient';
const { ccclass } = _decorator;

@ccclass('GameEntry')
export class GameEntry extends Component {
    private net: NetClient = new NetClient('ws://192.168.x.x:8080/ws');

    start() {
        this.net.onSnapshot(s => this.renderTable(s));   // 全量状态 → 刷牌桌
        this.net.onEvent(e => this.onGameEvent(e));      // 增量事件 → 动画/音效
        this.net.onJoined(j => console.log('入座', j.seat));
        this.net.onError(r => console.warn('服务端错误', r));
        this.net.join(1001, myPlayerId, 'NORTH');
    }

    onDestroy() { this.net.close(); }
}
```

## 协议要点（与服务端 v8d28fb8 起对齐）

- **鉴权**：`join` 成功回执带 `token`；之后所有 `cmd`/`snapshot` 必须带 token，
  服务端以 token 解析 playerId（客户端无需也无效上报 playerId）
- **心跳**：JSON `{"op":"ping"}` 30s 一次，2 次无 pong 自动断线重连
- **重连**：断线后指数退避（1s→2s→…≤15s）自动重新 join；服务端识别
  (roomId, playerId) 自动恢复快照（`reconnect:true`），NetClient 收到后
  会主动再拉一份 snapshot 对齐 UI
- **token 失效**（服务重启/TTL 12h 过期）：服务端报错带 "token" 字样，
  NetClient 自动重新 join
- **牌编码**：`S14/H5/D10/C13`，大王 `BJ`、小王 `SJ`

## 开发顺序（WBS v1）

- [x] T-503 网络层（本目录）
- [ ] T-501/T-502 Cocos 工程与分层
- [ ] T-505 登录页 + 大厅
- [ ] T-506 房间页（4 座位 + bot 自动开局）

## 本地联调

```bash
# 起战斗服（3 bot + 真人 NORTH）
cd server
mvn -pl game-room exec:java -Dexec.mainClass=com.gunzihall.room.ServerMain -Dexec.args="8080 1001"
# 客户端连 ws://localhost:8080/ws（真机用本机局域网 IP）
```
