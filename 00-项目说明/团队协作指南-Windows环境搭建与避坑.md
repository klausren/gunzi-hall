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
  - `game-domain` 领域模型：牌 / 牌值比较 / 发牌 / 命令模式 / 计分 / 进贡血数（**126 个**单元测试全绿）
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

- 远程 `origin/main` 已推送到 `e0722bd`（含 `.gitattributes` 强制 LF、`main.scene.meta` uuid 小写、`build-wechatgame.sh` 一键构建 + 主包瘦身至 1.73MB）。
- **团队现在 clone 即可拿到修复版**，无需等任老师补 push。
- 任老师本地仅剩**文档自身的测试数修正**未提交（无代码阻塞）。

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
mvn test        # 142 个单元测试（game-domain 126 + game-room 16，2 个 Redis 相关本机无 Redis 时跳过），全绿即环境 OK
```

启动战斗服（3 bot + 真人 NORTH 演示）：

```powershell
cd server
mvn -pl game-room exec:java -Dexec.mainClass=com.gunzihall.room.ServerMain -Dexec.args="8080 1001"
```

> 控制台出现 bot 自动开局 / 出牌日志即成功。战斗服监听 `ws://localhost:8080/ws`。

> ⚠️ **一个房间目前只支持 1 个真人**：`ServerMain` 用 `manager.create(roomId, Set.of(Seat.EAST, Seat.SOUTH, Seat.WEST))` 把三个座位交给 bot，**只有 NORTH 留给真人**。所以**两个人不要同时连同一个战斗服**（会抢 NORTH 座位、互相把对方踢下线）。**各自在自己电脑上跑各自的战斗服即可，互不影响**。
>
> 验证服务端是否正常（零依赖探针，任选其一）：
> ```powershell
> python tools\ws_probe.py --silent     # 只验握手
> python tools\ws_probe.py              # 验握手 + 入座 + 看快照
> ```
> （Windows 端若 python 走了系统代理导致连不上，可先 `set no_proxy=*`）

---

## 6. 第三步：打开客户端（Cocos Creator）

1. 打开 **Cocos Dashboard**（**必须是 3.8.8**，否则会提示升级/降级，可能破坏工程 meta 文件）
2. 项目 → **打开其他项目** → 选择 `client/` 目录
3. **首次打开会自动导入资源、生成 `library/` 缓存，需要几分钟**——`library/ temp/ build/ profiles/` 都被 `.gitignore` 排除，clone 下来没有，属正常现象
4. 导入完成后，打开场景 `assets/scenes/main`，点编辑器顶部 **▶ 预览** 即可在浏览器看到牌桌

> **本地联调注意（2026-09-10 更新）**：`serverUrl` **不再写死任老师的 IP**。
> 构建脚本（`.sh` / `.ps1`）会在**构建时自动同步成本机局域网 IP**，所以：
> - **你自己跑战斗服** → 直接构建即可，地址自动指向你自己的电脑
> - **想连任老师的战斗服**（需同一 WiFi）→ 手动把 `client/assets/scripts/ui/TableUI.ts` + `assets/scenes/main.scene` 里的 serverUrl 改成任老师当前的 IP；**改完必须重新构建**才生效
> - 只想改已构建产物、不想重跑构建 → 直接改 `build/wechatgame/assets/main/index.js` 里的 `ws://...` 字符串
>
> 验证服务端是否正常：`env no_proxy='*' python3 tools/ws_probe.py --silent`（零依赖，见 `tools/ws_probe.py`）
> ⚠️ 真机连不上时先分清两种"已连接"：**开发者工具右上角的绿点**是「手机↔开发者工具」的调试通道，**不等同于**「手机↔战斗服」通了；以**游戏顶栏**显示的状态为准。

---

## 7. 第四步：微信小游戏构建（Windows）

### ✅ Windows 已有对等脚本（2026-09-10 补齐）

`client/build-wechatgame.ps1` —— 与 macOS 的 `.sh` 版本功能对等，队友已修复「源码编码损坏」与「局域网 IP 选错」两个坑：

```powershell
cd client
.\build-wechatgame.ps1              # 完整流程：同步 serverUrl -> Cocos 构建 -> 打补丁
.\build-wechatgame.ps1 -PatchOnly   # 跳过构建，只做补丁（改了产物想快速重打时用）
```

它做的事与 `.sh` 一致：
1. 自动同步 `serverUrl` 为**本机局域网 IP**（`Get-NetIPAddress`）
2. `game.json` 覆写成 6 字段（见 §8.1）
3. `project.config.json` 修 `libVersion="3.7.9"` / `appid` / `compileType=game`
4. 归一化 `assets/` 下文件名为小写（防微信端黑屏，见 §8.2）
5. 主包体积守卫（4MB）

### 若仍想走 Cocos GUI 手动构建

构建面板 → 平台选"微信小游戏" —— 但**必须手动补丁**，否则必踩坑：

1. **`build/wechatgame/game.json` 改写成 6 字段**（见 §8.1 模板）
2. **`project.config.json` 的 `libVersion` 改成 `"3.7.9"`**、`appid` 填 `wx72e9a9e9764f0ee1`
3. **检查 `assets/main/import/` 下文件名是否全小写**（大写会导致微信端黑屏，见 §8.2）

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

- `serverUrl` **已不再写死任老师的 IP**：**构建脚本会在构建时自动同步为本机局域网 IP**（`.sh` 用 `ipconfig getifaddr`，`.ps1` 用 `Get-NetIPAddress`）。**换人 / 换 WiFi 后重跑一次构建脚本即可**，无需手改。
- 不想重跑整个 Cocos 构建（较慢）→ **直接改产物字符串**，效果完全等同：
  `client/build/wechatgame/assets/main/index.js` 里的 `ws://旧IP:8080` 替换成 `ws://新IP:8080`
- 真机试玩要求**手机和电脑同一 WiFi**；校园网 AP 隔离会连不通，需改用热点或确认互通。
- ⚠️ **两个"已连接"别混淆**：开发者工具**右上角的绿点**是「手机 ↔ 开发者工具」的调试通道；**游戏顶栏**显示的才是「手机 ↔ 战斗服」。顶栏若显示"连接不上 ws://…"，就是**到战斗服没通** —— 依次查：战斗服起了吗（`tools/ws_probe.py --silent`）→ 地址对吗 → 同一 WiFi 吗。
- ⚠️ **改了客户端代码看不到效果**：`Cmd+B` 只做本地编译，**真机调试的包必须重新点「真机调试」重新扫码才会重推**；保险起见先「工具 → 清除缓存」，并把手机上的小游戏**杀进程**再扫码。

### 8.6 版本一致性 🟡

- Cocos Creator 必须 **3.8.8**，微信开发者工具版本见下条，JDK 必须 **17**。版本不一致是大量诡异问题的源头。

### 8.7 微信开发者工具：项目类型锁定 + 版本兼容 🟡

- **项目类型在第一次导入时锁定**：一旦被识别成"小程序"，后续改 `compileType: "game"` 也救不回，会一直找 `app.json` 而报"找不到 app.json"。
- 新版开发者工具**已无独立"小游戏"选项**（只有"小程序/插件/多端应用"），靠 `compileType: "game"` 自动分流。
- **正确姿势**：用「**+ 新建项目**」（不是"导入项目"），目录选 `build/wechatgame`，项目类型**留空**让它自己推断，AppID 选 `wx72e9a9e9764f0ee1`。
- 若误识别成小程序：关闭项目 → 删 `project.private.config.json` → 「+ 新建」重来。
- **版本兼容**：社区验证 Cocos 3.8.x 的稳定搭配是 **`1.06.2412050`**；最新版（2.02+）可能报 `SummerCompiler.getAllPagesAndPageComponent` 崩溃。出问题时优先降级到 1.06.2412050。

### 8.8 三副牌多重集 🟡（后端开发）

- `Card` 按值 `equals`，手牌操作**严禁 `removeAll` / `containsAll`**（出 1 张会删光全部同名副本），一律用 `Cards.containsCopies / removeCopies`。

---

## 9. 仓库修复状态（2026-09-08 核对）

《给任老师的反馈-2026-09-07》§1 担心的三项**已全部推上 `origin/main`（提交 `e0722bd`）**，团队 clone 拿到的是修复版：

| §1 检查项 | 状态 |
|---|---|
| `.gitattributes`（§8.3 强制 LF） | ✅ 已在 `e0722bd` |
| `client/build-wechatgame.sh`（§7 构建脚本） | ✅ 已在 `e0722bd` |
| `main.scene.meta` uuid 小写 | ✅ 已在 `e0722bd`（大写 uuid 黑屏雷已消除） |

> 任老师本地当前**无代码层阻塞**；仅本指南的测试数（36→142）等措辞修正尚未提交，不影响 clone。

### 9.1 协作方式已定：GitHub 协作者 + PR（详见 §11）

不再走 OneDrive 共享文件夹。队友以 **Write 协作者** 身份推分支、开 PR，任老师 review 合并。

---

## 11. 协作流程：GitHub 协作者 + Pull Request（2026-09-08 起生效）

> 项目走 **GitHub 协作者（Write）路线**：队友直接在 `klausren/gunzi-hall` 推分支、开 PR，任老师 review 后合并。
> **不走 OneDrive 共享**（含 macOS 专属 `.workbuddy/`、绝对路径、600MB+ 体积、无版本控制）。

### 11.1 任老师：添加协作者（一次性）

1. 打开 `https://github.com/klausren/gunzi-hall` → **Settings → Collaborators**（左侧栏）
2. **Add people** → 输入队友的 GitHub 用户名 / 邮箱 → 权限选 **Write**
3. 队友收到邮件 / 站内通知，接受后即刻生效

> 或本机有 `gh` 时一行命令：`gh repo invite <队友用户名> --permission write`（需 `gh auth login`）。
> ⚠️ 仓库已是 **public**，协作者推代码无需额外资质；但**严禁在仓库提交任何密钥 / 版号材料**（个人主体走体验版，无商业机密）。

### 11.2 队友：日常开发流程（禁止直接推 main）

```powershell
git clone https://github.com/klausren/gunzi-hall.git
cd gunzi-hall
git checkout -b fix/flaky-tests-2026-09-07   # 分支命名见 11.3
# ... 改代码 ...
git add -A
git commit -m "fix(room): 修复两个 flaky 测试竞态（见反馈§三）"   # 提交规范见 11.4
git push -u origin fix/flaky-tests-2026-09-07
# 去 GitHub 网页点 Compare & pull request，按模板填（见 11.5）
```

> 任老师 review 通过后合并；合并后队友 `git checkout main && git pull` 同步。**main 受保护，所有人走 PR**。

### 11.3 分支命名

| 类型 | 前缀 | 例 |
|---|---|---|
| 新功能 | `feat/` | `feat/matchmaking` |
| Bug / 测试修复 | `fix/` | `fix/flaky-tests-2026-09-07` |
| 文档 | `docs/` | `docs/windows-guide` |
| 构建 / 工程 | `build/` | `build/wechatgame-ps1` |

### 11.4 提交信息规范（Conventional Commits，带 scope）

格式：`类型(范围): 简述（中文祈使句，不加句号）`

- 对齐仓库已有风格：`fix(wechatgame):`、`build(wechatgame):`、`feat(ui):`、`feat(room):`、`chore(client):`
- 类型：`feat` / `fix` / `docs` / `build` / `refactor` / `test` / `chore`
- 范围：改哪个模块写哪个（`room` / `ui` / `wechatgame` / `client` / `domain`）
- 例：`fix(room): 修复重连快照与后台驱动竞态`、`build(wechatgame): 新增 Windows 构建脚本`

### 11.5 PR 模板与 review 要点

- 仓库已放 `.github/PULL_REQUEST_TEMPLATE.md`，开 PR 时自动载入。
- **必填**：改动文件清单 + 验证方式（尤其 `mvn test` 是否全绿、flaky 是否连跑 5 次通过）。
- 任老师 review 重点：**是否遵守 §8 三铁律**、**是否用构建脚本而非 Cocos GUI 直发**、**有无密钥 / 大二进制入库**。

### 11.6 首个 PR 示例（队友待推）

- 分支：`fix/flaky-tests-2026-09-07`
- 内容：
  1. `server/game-room/.../RoomManager.java` —— 重连快照时机后移（修复 `RestoreTest` 竞态）
  2. `server/game-room/.../TimeoutTrustTest.java` —— 单调断言 + AUTO 事件断言（修复 `humanPlaysBeforeTimeout`）
  3. 新增 `client/build-wechatgame.ps1` —— Windows 构建等价物（§7 待办）
- 详见《给任老师的反馈-2026-09-07.md》§三 / §四。

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
