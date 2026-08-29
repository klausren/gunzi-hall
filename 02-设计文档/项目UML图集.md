# 滚子大厅 - 项目 UML 图集

> 面向甲方 / 项目干系人的可视化文档  
> 所有图均为 Mermaid 源码，GitHub 自动渲染；修改图 = 修改文本，永远和代码同仓库演进  
> 绘制日期：2026-08-29 ｜ 依据：架构v0、WBS-v0、规则手册 v1.1（已定稿冻结）

---

## 1. 功能全景图（用例视角）

玩家能在这个小游戏里做什么——一张图讲清楚产品边界。

```mermaid
flowchart LR
    subgraph 玩家["👤 玩家"]
        P1[微信用户]
    end

    subgraph 核心玩法["🃏 核心玩法"]
        U1[微信登录]
        U2[熟人建房/分享邀请]
        U3[打滚子对局<br/>三副牌·四人·对家配对]
        U4[抠庄/亮王/进贡/还贡/抗贡]
        U5[实时语音/快捷语/表情]
    end

    subgraph 智能体验["🤖 智能体验"]
        U6[掉线 AI 秒级托管]
        U7[AI 补位凑桌]
        U8[赛后复盘报告]
    end

    subgraph 商业化["💰 广告变现（无内购·合规）"]
        U9[建房解锁]
        U10[高级记牌器]
        U11[方言配音/装扮]
        U12[完整复盘解锁]
    end

    P1 --> U1 --> U2 --> U3
    U3 --> U4
    U3 --> U5
    U3 -.掉线自动触发.-> U6
    U3 -.人数不足.-> U7
    U3 --> U8
    U2 -.-> U9
    U3 -.-> U10
    U5 -.-> U11
    U8 -.-> U12
```

**给甲方的三个要点**：
- **零门槛进入**：微信扫码即玩，无需下载安装
- **不缺人**：掉线 AI 托管 + AI 补位，牌桌永远开得起来
- **合规变现**：全部收入来自激励视频广告，无充值、无内购、不涉赌

---

## 2. 系统架构图

```mermaid
flowchart TB
    subgraph client["微信小游戏客户端（Cocos Creator 3.x）"]
        C1[登录/大厅/房间]
        C2[牌桌战斗 UI<br/>162张牌渲染·拖拽出牌]
        C3[复盘/记牌器]
    end

    subgraph gateway["API 网关（Spring Cloud Gateway）"]
        G1[鉴权/限流/路由]
    end

    subgraph backend["后端服务（Spring Boot 3 + JDK 17）"]
        B1["game-room 战斗服<br/>(Netty WebSocket·房间状态机·命令校验)"]
        B2["game-account 账房服<br/>(用户·战绩·广告事件)"]
        B3["game-ai 调度服<br/>(决策树·AgentProxy)"]
        B4["game-domain 领域模型<br/>(纯规则·可独立测试)"]
    end

    subgraph infra["基础设施（腾讯云）"]
        I1[(Redis 7<br/>牌局实时状态)]
        I2[(MySQL 8 主从<br/>业务持久化)]
        I3[消息队列<br/>结算/回调异步化]
        I4[SkyWalking + ELK<br/>监控告警]
    end

    subgraph external["第三方"]
        E1[微信登录 API]
        E2[激励视频广告 SDK]
        E3[LLM API<br/>DeepSeek/通义·仅复盘文案]
    end

    client <-->|"WebSocket 长连接"| gateway
    gateway --> B1 & B2 & B3
    B1 --> B4
    B3 --> B4
    B1 <--> I1
    B2 --> I2
    B1 --> I3 --> I2
    B2 <--> E2
    B3 --> E3
    client <--> E1
    B1 & B2 & B3 -.-> I4

    style client fill:#e8f4fd,stroke:#1f77b4
    style backend fill:#eaf7ea,stroke:#2ca02c
    style infra fill:#fdf3e7,stroke:#ff7f0e
    style external fill:#f5eef8,stroke:#9467bd
```

**架构决策要点**：
- **服务器权威**：所有出牌命令必须服务端校验后生效，客户端只是"显示器"——从根上防外挂
- **规则引擎独立**（game-domain）：不依赖网络和数据库，可全量单元测试，规则手册 12 项决议逐一对应测试用例
- **AI 与真人同接口**：房间状态机不区分人机，断线托管零成本切换

---

## 3. 领域模型类图（DDD Lite + 命令模式）

```mermaid
classDiagram
    class Card {
        +Suit suit 花色
        +Rank rank 点数
        +boolean isJoker
        +int scoreValue() 分牌5/10/K计分
        +int compare(Card other, TrumpContext t)
    }

    class Deck {
        +List~Card~ cards
        +int SIZE = 162 三副牌
        +shuffle() 洗牌
        +deal() 发牌39x4+底牌6
    }

    class Player {
        <<interface>>
        +long playerId
        +List~Card~ hand 手牌
        +GameCommand nextAction(GameRoom ctx)
    }

    class HumanPlayer {
        WebSocket 会话
        心跳超时检测
    }

    class AgentProxyPlayer {
        +int thinkDelay 0.8~2.5s拟人
        +double errorRate 3%失误
        +PreferenceVector style
    }

    class GameRoom {
        +Phase phase
        +Player[4] seats 对家两两配对
        +TrumpContext trump
        +ScoreLedger ledger
        +List~GameCommand~ history 回放/反作弊
        +Result apply(GameCommand cmd)
    }

    class TrumpContext {
        +Suit trumpSuit 定主花色
        +Rank level 当前级数3~10
        +jokersDeclared 大小王亮牌
        +boolean isTrump(Card c)
    }

    class GameCommand {
        <<interface>>
        +String commandId UUID防重放
        +long tick 顺序号
        +Result execute(GameRoom ctx)
    }

    class PlayCardsCommand
    class DeclareBankerCommand {
        抠庄/亮王/反主
        2反1·3反2或1
    }
    class PayTributeCommand {
        进贡·大王2血小王1血
        只有庄家上家进贡
    }
    class ReturnTributeCommand {
        还贡/抗贡
    }
    class BuryCommand {
        扣底·先大王后小王
        含分牌则他人禁扣王
    }

    class Trick {
        +List~Play~ plays
        +Player winner 一轮最大者
        +int collectScore() 收分
    }

    class ScoreLedger {
        +int captureScore 抓分方得分
        +boolean baodi 保底
        +int bloodCount() 总血数
        +int ouDiMultiplier 抠底×2
    }

    Player <|.. HumanPlayer
    Player <|.. AgentProxyPlayer
    GameCommand <|.. PlayCardsCommand
    GameCommand <|.. DeclareBankerCommand
    GameCommand <|.. PayTributeCommand
    GameCommand <|.. ReturnTributeCommand
    GameCommand <|.. BuryCommand
    GameRoom o-- "4" Player
    GameRoom o-- TrumpContext
    GameRoom o-- ScoreLedger
    GameRoom *-- Trick
    Player o-- "39" Card
    Deck ..> Card : 生成162张
```

**为什么用命令模式**：每个玩家动作（出牌/亮王/进贡…）都是一个可序列化对象 → 天然支持**回放、反作弊审计、断线重演**，这是棋牌类产品的合规底线。

---

## 4. 一局牌的完整状态机

```mermaid
stateDiagram-v2
    [*] --> WAITING: 建房/邀请·满4人
    WAITING --> DEALING: 庄家开局
    DEALING --> BIDDING: 洗牌·发牌(39x4+底6)
    BIDDING --> BIDDING: 亮王/反主/加固<br/>2反1·3反2或1
    BIDDING --> FIRST_DEAL: 第一局无亮主<br/>翻最大牌定庄
    BIDDING --> TRIBUTE: 定庄·定主花色
    FIRST_DEAL --> TRIBUTE
    TRIBUTE --> TRIBUTE: 庄家上家进贡<br/>血数=分差+扣王折算
    TRIBUTE --> BURY: 还贡/抗贡结算
    BURY --> PLAYING: 庄家扣6张底牌<br/>先大王后小王·干锅检测
    PLAYING --> PLAYING: 出牌→跟牌→判最大→收分<br/>活棒/死棒按房间开关
    PLAYING --> SETTLING: 全部手牌出完
    SETTLING --> SETTLING: 计分·抠底×2<br/>血数结算·升级判定
    SETTLING --> CHUGUO: 一方打完10级出锅<br/>本轮结束
    SETTLING --> TRIBUTE: 下一局开始
    CHUGUO --> WAITING: 新一轮从3打起
    CHUGUO --> [*]: 满足结束条件
```

**状态机与规则手册的对应**：每个状态转移都来自规则手册 v1.1 定稿条款（已冻结），Sprint 1-5 将逐状态实现并配单元测试。

---

## 5. 关键场景时序图：出牌 + 断线 AI 托管

甲方最关心的体验问题——"玩家掉线了牌局会散吗？"——答案在这张图里：**不会，AI 无感接管，重连无缝回来**。

```mermaid
sequenceDiagram
    autonumber
    participant C as 客户端(玩家)
    participant G as 战斗服(Netty)
    participant R as Redis(牌局状态)
    participant AI as AI调度服

    C->>G: PlayCardsCommand(出牌·带UUID)
    G->>G: 命令校验(牌型合法·轮到该玩家·UUID未重放)
    G->>R: 原子更新牌局状态(Lua脚本)
    R-->>G: 更新成功
    G-->>C: 广播差量状态(其他3人同步收到)

    Note over C,AI: ―― 玩家突然掉线 ――
    C--xG: 心跳超时(3~5秒)
    G->>AI: 注入AgentProxyPlayer(同一Player接口)
    AI-->>G: 拟人决策(思考0.8~2.5s·3%失误率)
    G->>R: 牌局继续推进·标记"托管中"
    Note over C: 玩家看到"牌局仍在进行"
    C->>G: 重连(拉取状态快照)
    G-->>C: 完整局面+托管标记·平滑交还操作权
```

---

## 6. 项目进度甘特图（WBS v0）

```mermaid
gantt
    title 滚子大厅 开发排期（目标：2027年2月底上架）
    dateFormat YYYY-MM-DD
    axisFormat %m月

    section 准备期
    Sprint 0 规则手册(已完成)·主体申请·三端demo :done, s0, 2026-08-29, 14d

    section 后端核心
    Sprint 1 战斗服骨架·命令模式 :s1, 2026-09-14, 14d
    Sprint 2 规则引擎·三副牌玩法 :s2, 2026-09-28, 14d
    Sprint 5 抠庄·进贡·抗贡 :s5, 2026-11-09, 14d

    section 客户端
    Sprint 3 登录·大厅·房间 :s3, 2026-10-12, 14d
    Sprint 4 牌桌UI·162张牌实时同步 :s4, 2026-10-26, 14d

    section 智能与商业化
    Sprint 6 AI托管·拟人决策 :s6, 2026-11-23, 14d
    Sprint 7 广告变现·合规闭环 :s7, 2026-12-07, 14d

    section 扩展与上架
    Sprint 8 散人匹配·LLM复盘 :s8, 2026-12-21, 21d
    Sprint 9 压测·反作弊·审核准备 :s9, 2027-01-11, 21d
    提审·灰度·上架 :launch, 2027-02-01, 28d

    section 里程碑
    milestone 规则手册定稿冻结 :milestone, m0, 2026-08-29, 0d
    milestone 4个bot能玩完一局 :milestone, m1, 2026-09-25, 0d
    milestone 真机完整对局 :milestone, m4, 2026-11-06, 0d
    milestone 大连特色规则全通 :milestone, m5, 2026-11-20, 0d
    milestone 商业化闭环 :milestone, m7, 2026-12-18, 0d
```

**当前进度（2026-08-29）**：
- ✅ 架构设计 v0、WBS v0（10 个 Sprint）
- ✅ **规则手册 v1.1 定稿冻结**（12 项规则决议全部拍板——项目最大风险已消除）
- ✅ 代码仓库建立（GitHub 自动化部署就绪）
- ⏳ 微信小游戏主体注册（甲方协助项）
- 📍 下一站：Sprint 1 领域模型 + 命令模式骨架（9 月中旬完成）

---

## 图集维护约定

| 变更场景 | 操作 |
|---|---|
| 新增/修改功能 | 先改本图集，评审后再动代码 |
| Sprint 结束 | 更新甘特图 done 状态和"当前进度" |
| 规则变更 | 规则手册已冻结，需走变更记录并同步状态机/类图 |
