# 滚子大厅（Gunzi Hall）团队协作指南

> **面向对象**：即将把代码仓库 clone 到本地的团队成员（**Windows 系统**）
> **版本**：v1.0　**日期**：2026-09-02　**作者**：任老师（主开发）
> **目的**：三句话讲清"现在做到哪了、接下来干什么、怎么不踩坑"

---

## 1. 项目是什么

| 项 | 内容 |
|---|---|
| 名称 | 滚子大厅（Gunzi Hall）——大连打滚子扑克 |
| 玩法 | 三副牌 162 张，四人对家两两配对，抠庄 / 进贡 / 抗贡 / 亮王 / 出锅 |
| 产品形态 | 微信小游戏（横屏，Cocos Creator 3.8.8，2D） |
| 后端 | Spring Boot 3 + Netty WebSocket + MySQL + Redis |
| 代码仓库 | `https://github.com/klausren/gunzi-hall`（公开，main 分支） |
| 规则依据 | `01-规则手册/打滚子规则手册-v1.1-定稿.md`（**已定稿冻结**，一切逻辑以它为准） |

### ⚠️ 发布路线（重要，别被 README 误导）

当前 **README.md 里"广告变现 / 2027-02 上架"是旧计划，已作废**。真实路线是：

> **微信"体验版"自娱路线**：不上架、不申请版号、不接广告。个人主体（任老师）手动在微信后台加"体验成员"，朋友扫码就能玩。

原因：棋牌/扑克类小游戏无论是否变现都需**版号**，版号主体必须是企业且棋牌版号极难获批，个人主体公开上架走不通。所以走体验版，**客户端做完即达成交付，无外部资质依赖**。

---

## 2. 当前进展（截至 2026-09-02）

### 已完成 ✅

- **规则手册** v1.1 定稿冻结（12 项决议全部拍板）
- **后端**（`server/`，Maven 多模块，JDK 17）：
  - `game-domain` 领域模型：牌 / 牌值比较 / 发牌 / 命令模式 / 计分 / 进贡血数（36 个单元测试全绿）
  - `game-room` Netty 战斗服：3 个 bot + 真人 NORTH 演示入口（`ws://localhost:8080/ws`）
- **客户端**（`client/`，Cocos Creator 3.8.8）：
  - 牌桌 UI 全套（TableUI / CardUI / 手牌排序 / 墩牌可视化 / 动画 / 局结算面板）
  - 网络层（WebSocket 心跳 / 指数退避重连 / token 管理）
  - 断线重连 UI、超时 AI 托管、BotBrain v2（拟人决策）
- **微信小游戏构建链路已打通**：一键构建脚本 + 微信开发者工具导入 + 模拟器跑通（横屏）

### 进行中 🔄

- 真机预览联调（手机微信扫码横屏试玩）
- 音效素材
- 体验版发布（加体验成员 → 朋友扫码玩）

### 代码仓库状态 ⚠️

- 远程 `origin/main` 已推送到 `60e8e8c`
- **但本地有 6 个改动尚未 commit + push**（详见 §9），**团队 clone 之前请先由任老师 push**，否则会拿到旧版（带大写 uuid 的场景文件、旧构建脚本）

---

## 3. 后续安排

### 近期任务

| 任务 | 说明 |
|---|---|
| 推送本地未提交改动 | 见 §9，团队拉代码的前置条件 |
| T-705 真人内测 | 至少 10 局，收集玩法/手感反馈 |
| T-703 性能优化 | 真机看帧率（162 张牌 + 动画） |
| 音效素材 | 出牌 / 洗牌 / 结算提示音 |
| 体验版发布 | 微信后台加体验成员，朋友扫码 |

### 建议分工（可调整）

- **任老师**：客户端主开发、微信构建与发布、规则裁决
- **新成员**：后端扩展（`game-room` 房间/匹配/计分深化）、单元测试、联调与真机内测、文档

> 后端是目前最适合新成员快速上手的模块——纯 Java，`mvn test` 即可验证，不依赖 Cocos 环境。

---

## 4. 第一步：拉取代码（Windows）

### 4.1 前置软件清单

| 软件 | 版本要求 | 用途 | 说明 |
|---|---|---|---|
| Git | 2.30+ | 拉代码 | 装 [Git for Windows](https://git-scm.com/download/win) |
| JDK | **17**（x64） | 后端 | 推荐 [Eclipse Temurin 17](https://adoptium.net/)，装完配 `JAVA_HOME` |
| Maven | 3.9+ | 后端构建 | 或直接用 IDE（IntelliJ IDEA）自带 Maven |
| Cocos Dashboard | **3.8.8** | 客户端 | 版本必须一致，见 §6 |
| 微信开发者工具 | 见 §7 | 小游戏调试 | 版本有讲究，见 §8.7 |

### 4.2 克隆仓库（注意换行符）

```powershell
# 建议先关掉 Windows 默认的 CRLF 自动转换，避免换行符污染
git config --global core.autocrlf false

git clone https://github.com/klausren/gunzi-hall.git
cd gunzi-hall
```

> **为什么关 autocrlf**：仓库根目录已放 `.gitattributes` 强制 LF。Windows 上若开着 `autocrlf=true` 会和它打架，导致 `.sh` 脚本、Java/TS 源码出现成片"换行符伪 diff"。见 §8.3。

---

## 5. 第二步：把后端跑起来（Windows 可完整运行）

```powershell
cd server
mvn test        # 36 个单元测试，全绿即环境 OK
```

启动战斗服（3 bot + 真人 NORTH 演示）：

```powershell
cd server
mvn -pl game-room exec:java -Dexec.mainClass=com.gunzihall.room.ServerMain -Dexec.args="8080 1001"
```

> 控制台出现 bot 自动开局 / 出牌日志即成功。战斗服监听 `ws://localhost:8080/ws`。

---

## 6. 第三步：打开客户端（Cocos Creator）

1. 打开 **Cocos Dashboard**（**必须是 3.8.8**，否则会提示升级/降级，可能破坏工程 meta 文件）
2. 项目 → **打开其他项目** → 选择 `client/` 目录
3. **首次打开会自动导入资源、生成 `library/` 缓存，需要几分钟**——`library/ temp/ build/ profiles/` 都被 `.gitignore` 排除，clone 下来没有，属正常现象
4. 导入完成后，打开场景 `assets/scenes/main`，点编辑器顶部 **▶ 预览** 即可在浏览器看到牌桌

> **本地联调注意**：场景里 `serverUrl` 目前写的是任老师电脑的局域网 IP `ws://10.192.0.121:8080/ws`。你自己跑战斗服时，要把它改成 `ws://localhost:8080/ws`（或你自己电脑的局域网 IP）。

---

## 7. 第四步：微信小游戏构建（Windows 特别注意）

### 现状分工

**微信小游戏构建目前统一由任老师（macOS）出产物**。原因是：现有构建脚本 `client/build-wechatgame.sh` 是 **bash + macOS 专用**（硬编码了 macOS 的 Cocos 路径、依赖 `python3`），Windows 上跑不了。

### Windows 成员如果要自己构建

走 **Cocos Creator GUI 发布**（构建面板 → 平台选"微信小游戏"）——但**必须手动补丁**，否则必踩坑（这几项就是 §8.1 的血泪教训）：

1. **`build/wechatgame/game.json` 改写成 6 字段**（见 §8.1 模板）
2. **`project.config.json` 的 `libVersion` 改成 `"3.7.9"`**、`appid` 填 `wxf88033b2ee303d71`
3. **检查 `assets/main/import/` 下文件名是否全小写**（大写会导致微信端黑屏，见 §8.2）

> 更省心的做法：需要频繁在 Windows 构建时，可以由任老师补一个 PowerShell 版构建脚本（列入待办）。

---

## 8. 避坑手册（必读，全是血泪教训）

### 8.1 微信小游戏 `game.json` 配置三铁律 🔴

`game.json` 的字段是**双向雷**——有的字段空串会爆，有的字段缺失也会爆。**最终可用版本 = 6 字段**：

```json
{
    "deviceOrientation": "landscape",
    "showStatusBar": false,
    "backgroundColor": "#000000",
    "pages": [],
    "subpackages": [],
    "networkTimeout": {
        "request": 5000,
        "connectSocket": 5000,
        "uploadFile": 5000,
        "downloadFile": 500000
    }
}
```

三条铁律：

1. **字符串路径类字段**（`openDataContext` / `workers` / `lazyLoading`）：**必须删掉**，设空串会报 `['字段名'] 不能为 ''`
2. **数组类 manifest 字段**（`pages` / `subpackages`）：**必须显式写空数组 `[]`**，缺失会触发编译器 `Object.keys(undefined)` 崩溃
3. **别照搬 Cocos 默认模板**：它的 9 字段模板在小游戏里至少 5 个会踩雷

### 8.2 uuid 大小写 🔴

- 所有资源 `.meta` 的 uuid 必须**全小写**。引擎把短码解码后**恒为小写**，去拼 `import/<前2位>/<uuid>.json` 的加载路径。
- 若某个 meta 的 uuid 是大写（本项目 `main.scene` 曾踩过），构建产物文件名就是大写 → 微信开发者工具的虚拟文件系统**大小写敏感** → 读不到 → `JSON.parse("")` 崩溃 → 黑屏。
- **桌面端浏览器预览大小写不敏感，所以这雷只在微信小游戏端炸**——桌面正常不代表微信正常。

### 8.3 换行符 CRLF 🔴（Windows 特有）

- 仓库已加 `.gitattributes` 强制 LF。**务必先 `git config --global core.autocrlf false` 再 clone**，否则脚本/源码被转成 CRLF，跨平台协作会出现成片伪 diff，`.sh` 脚本直接运行报错。

### 8.4 构建脚本 macOS 专用 🔴

- `client/build-wechatgame.sh` 里的 Cocos 路径是 `/Applications/Cocos/Creator/3.8.8/...`，Windows 无法运行。
- 它做的补丁（landscape、urlCheck=false、appid、libVersion、文件名小写归一化）**在 Windows 手动构建时必须照做**（见 §7）。

### 8.5 局域网 IP 🟡

- 客户端场景里的 `serverUrl` 写死了任老师电脑的 IP，换人/换环境必须改。
- 真机试玩要求**手机和电脑同一 WiFi**；校园网 AP 隔离会连不通，需改用热点或确认互通。

### 8.6 版本一致性 🟡

- Cocos Creator 必须 **3.8.8**，微信开发者工具版本见下条，JDK 必须 **17**。版本不一致是大量诡异问题的源头。

### 8.7 微信开发者工具：项目类型锁定 + 版本兼容 🟡

- **项目类型在第一次导入时锁定**：一旦被识别成"小程序"，后续改 `compileType: "game"` 也救不回，会一直找 `app.json` 而报"找不到 app.json"。
- 新版开发者工具**已无独立"小游戏"选项**（只有"小程序/插件/多端应用"），靠 `compileType: "game"` 自动分流。
- **正确姿势**：用「**+ 新建项目**」（不是"导入项目"），目录选 `build/wechatgame`，项目类型**留空**让它自己推断，AppID 选 `wxf88033b2ee303d71`。
- 若误识别成小程序：关闭项目 → 删 `project.private.config.json` → 「+ 新建」重来。
- **版本兼容**：社区验证 Cocos 3.8.x 的稳定搭配是 **`1.06.2412050`**；最新版（2.02+）可能报 `SummerCompiler.getAllPagesAndPageComponent` 崩溃。出问题时优先降级到 1.06.2412050。

### 8.8 三副牌多重集 🟡（后端开发）

- `Card` 按值 `equals`，手牌操作**严禁 `removeAll` / `containsAll`**（出 1 张会删光全部同名副本），一律用 `Cards.containsCopies / removeCopies`。

---

## 9. 重要提醒：当前有改动未推送 🔴

**以下改动还在任老师本地，尚未 commit + push。团队 clone 之前必须先 push，否则会拿到带大写 uuid 的旧版：**

| 文件 | 改动 |
|---|---|
| `client/assets/scenes/main.scene` | 场景 `_id` uuid 改小写 |
| `client/assets/scenes/main.scene.meta` | uuid 改小写 |
| `client/build-wechatgame.sh` | 补丁升级（6 字段 game.json + libVersion=3.7.9 + 文件名小写归一化） |
| `client/package.json` | （上轮遗留） |
| `client/settings/v2/packages/project.json` | （上轮遗留） |
| `.gitattributes` | 本次新增（换行符规范） |

> 任老师 push 完成后，团队再 clone，就能拿到完整修复版。

---

## 10. 遇到问题怎么办

1. 先看本文 §8 避坑手册，90% 的坑都能对上号
2. 后端报错 → `mvn test` 定位；客户端报错 → 浏览器预览看控制台
3. 微信小游戏黑屏/崩溃 → 依次检查：game.json 6 字段 → libVersion=3.7.9 → 项目类型是否锁成小程序 → import 文件名是否小写
4. 仍解决不了 → 把报错截图 + 控制台日志发到群里，附上操作步骤

---

## 附：文档索引

| 文档 | 位置 | 说明 |
|---|---|---|
| 规则手册（定稿） | `01-规则手册/打滚子规则手册-v1.1-定稿.md` | 一切逻辑的唯一依据 |
| 架构设计 | `00-项目说明/架构v0.md` | 技术栈与模块拆分 |
| 任务分解 | `00-项目说明/WBS-v0.md`、`WBS-v1.md` | Sprint 排期与验收标准 |
| UML 图集 | `02-设计文档/项目UML图集.md` | 功能/架构/类图/状态机/时序/甘特 |
| 体验版发布手册 | `00-项目说明/体验版发布-操作手册.md` | 加体验成员、扫码试玩流程 |
