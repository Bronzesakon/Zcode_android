# 归档 Rust 协议核心作为 Android 薄壳「后台原生 carrier」的可用度评估

> 评估日期：2026-11-04 ｜ 角色：只读调研（未修改任何既有文件，仅新增本文）
> 评估对象：`archive/native-compose/rust/`（tag `native-compose-final`，22 个 `.rs` / 31 151 行，其中 519 个 `#[test]`）
> 参照实现：`archive/native-compose/android/app/src/main/kotlin/app/zemote/`（158 个 `.kt` / 34 269 行）、
> 现行薄壳 `android/app/src/main/java/com/zcode/remote/`（34 个 `.kt` / 约 4.1 千行核心 + 注入层）
> 凭证口径：全文不出现任何 sid / hash / mid / token 原文，一律写 `<SID>` / `<PASS_HASH>` / `<MID>` / `<TOKEN>`。

---

## 0. 一句话结论

**「需补」——Rust 协议核心本身可直接复用（认证、握手、数据面、会话/索引状态全部达到 3.16.1+/3.17.1 口径，
且有 519 项主机侧单测），但 carrier 不在 Rust 里**：Rust 是「JSON 进 / JSON 出、宿主驱动全部 I/O 与时钟」的
纯状态机，后台承载所需的 WebSocket/定时器宿主层、通知输入接线、多工作区轮换策略、接管/交还状态机
**都必须在 Kotlin 侧新写**——其中约 3.1 千行宿主层与交接/通知策略可从归档与现有薄壳直接搬运/改写。
粗估最小可用载体 **13–20 人日**（单工作区最小集 10–16 人日），最大缺口是**多工作区服务权轮换策略**
与**接管前的「单控制端」串行化**（两者 Rust 都没有，且 3.17.1 页面新增的 KICKED 自动重试让对抗窗口变窄）。

---

## 1. 评估方法与范围

**方法**：逐文件读 Rust 源码 + FFI 冻结契约（`archive/native-compose/docs/FFI.md`，657 行），
与三份「事实来源」交叉核对：
① 网页 3.17.1 快照（`Reference_web/3.17.1/assets/*.js`，压缩产物用 `grep -b`/`dd` 取字节偏移取证）；
② 归档 Kotlin 宿主（已实现同一份 FFI 契约，是「宿主层要写多少」的一手样本）；
③ 现行薄壳 `android/`（交接/判死/通知链路的现行实现与历史结论）。

**范围**：只评估「能否充当后台 carrier」，不评估归档 App 的 UI/Compose/升级器；不评估鸿蒙侧。

**一处口径更正（影响后续阅读）**：
任务描述里「薄壳 `android/` 里没有可复用的原生协议实现（原生承载已不在）」与实测不符——
`android/app/src/main/java/com/zcode/remote/core/NativeRelayClient.kt`（1073 行）、`RelayBridge.kt`（1342 行）、
`RelayWire.kt`（767 行）**都还在**，`ShellRuntime.kt` 有 **48 处** `NativeRelayClient` 引用。
但它们**只实现了 legacy HMAC**（`NativeRelayClient.kt:20` `relayProof`、`:906` `auth_init{role:'terminal'}`、
`:928` `auth_challenge` → `:933` HMAC proof），全仓 `grep ws2|auth_token|/api/v1/remote` **零命中**，
而 3.17.1 页面**已完全没有** `auth_challenge` / `passHash`（全 JS 零命中，见 §3.1）。
薄壳 README 自己也记着这条：`android/README.md:1227`「原生承载在 3.17.1 上不启动（legacy HMAC 需要 `hash`，
3.16.1+ 链接没有），后台原生接管当前不可用」。
**结论不变（原生承载不可用），但它的 Kotlin 骨架是宝贵的参考实现**——多工作区轮换、判死、交还、通知接线都在里面。

---

## 2. 逐项结论表

| # | 维度 | 结论 | 主要依据 |
|---|---|---|---|
| 1 | 认证（OAuth JWT / `ws2?token=` / 交换与刷新 / 旧 HMAC） | ✅ **协议侧完整**；token **交换与刷新在宿主**（Rust 只接收 token） | `connection_params.rs:518-548`、`relay_client.rs:404-424, 556-590`、`ffi/session.rs:457-505`、归档 `auth/OAuth.kt:374-452`、`EnginePort.kt:362-366` |
| 2 | 握手 / 配对（`auth_init`、challenge/response/ack、`pair_status_query`、KICKED） | ✅ **齐备**，且 KICKED 策略与 3.17.1 页面基本对齐（Rust 单次恢复 vs 页面 60s 窗一次） | `relay_client.rs:700-731, 758-800, 908-915, 985-1000, 836-856` |
| 3 | 数据面（bootstrap / 桥 / 订阅 / 分片 CRC / 16MB） | ✅ **齐备且超集**（含附件分块、rowsRange、plans、fileChanges） | `zemote_client.rs:1316-1470, 2436-2520`、`rpc_transport.rs:60-105, 128-175, 461`、`conversation_transport.rs`（79 测试） |
| 4 | 多工作区（多桥 / 服务权 / 轮换） | ⚠️ **能开多桥，但没有服务权/轮换概念**；策略层缺失 | `zemote_client.rs:918-933, 1359-1420`；轮换只在 `android/…/RelayBridge.kt:1040`（Kotlin）里 |
| 5 | 状态与通知字段 | 🟡 **两条源齐（sessions-index + 会话流运行态），第三条（controller 全局清单）没有且不应重开**；`pinned/archived` 需另走 readTree | `conversation.rs:2160-2300`、`conversation_transport.rs:4005-4040`、`codec.rs:885-905`（channel_call）、`android/README.md:947-949` |
| 6 | carrier 模式（无 UI / 可取消 / 无页面副作用 / JNI） | ✅ **架构上完全支持**（纯逻辑 + 宿主驱动 + 每 handle 独立锁 + `catch_unwind`）；无 Compose 依赖 | `rust/README.md`、`ffi/src/jni_bridge.rs`、`ffi/src/registry.rs:26-108`、`FFI.md §11` |
| 7 | 交接（接管/交还/判死/KICKED/单控制端） | ❌ **Rust 零实现**；薄壳已有 `HandoffState`/`CarrierHandback` + 35s 判死，需与 Rust carrier 对接 | `ShellRuntime.kt:1123, 1139, 1386-1452`、`core/HandoffState.kt`、`core/CarrierHandback.kt` |

---

## 3. 分项详证

### 3.1 认证：Rust 覆盖 3.16.1+/3.17.1 全部要求，但「换 token / 刷 token」是宿主职责

**Rust 已实现（可直接复用）**

- 连接参数把「旧链接」与「新链接」分开：`pass_hash: Option<String>`、`auth_token: Option<String>`
  （`crates/protocol/src/connection_params.rs:462, 469`），`parse` 只强制 `sid`
  （`:488-511`；测试 `:705-733` 覆盖「无 hash 无 t 的 3.16.1 链接」）。
- relay 地址按模式二选一：token 模式 → `wss://<host>/api/v1/remote/ws2?token=<TOKEN>&mid=<MID>`，
  legacy → `wss://<host>/ws?mid=<MID>`（`connection_params.rs:518-548`；测试 `:736-746`）。
  展示用 URI 显式**剔除 token**（`:553-575`，测试 `:749-759`）。
- 握手形状按模式切换：token 模式 `auth_init` **不带 `role`**、`client_ts` 用**秒**；
  legacy 保留 `role:"terminal"` + 毫秒（`relay_client.rs:404-424`，测试 `:1531-1559`）。
- token 模式下收到 `auth_challenge` 会**忽略并记日志**（`:665-700`，测试 `:1560-1569`）；
  legacy 仍走 `calculate_proof`（`proof.rs`，9 项测试）。
- FFI 侧把 token 从 URL 里隔离出来：`config.token` 覆盖，且 **`dryRun.parsed` / `snapshot` 都不回传**
  （`ffi/src/session.rs:470-480, 553-580`；`FFI.md §5/§9`），`passHash` 亦永不外露。

**token 交换与刷新：不在 Rust**

- 契约明写「`token` 是 ws2 terminal token，由宿主登录后注入」（`docs/FFI.md §5`）；
  `ffi/src/session.rs:473` 注释同口径。Rust 里 `grep refresh|oauth|remote/token` 只有注释与测试字符串。
- **归档 Kotlin 已实现**：`auth/OAuth.kt:374-452` —— `POST /api/v1/remote/token`，
  `Authorization: Bearer <OAuth JWT>`，body `{"role":"terminal"}`，返回 22 字符短 token，
  读取 `expires_in` 仅作诊断；目的是 `wss://<host>/api/v1/remote/ws2?token=<短token>`。
- **刷新策略是「每次建连都换」而不是定时续期**：`ui/EnginePort.kt:362-366`
  （`每次 ws 重连都要重新换 terminal token`，给引擎装 `terminalTokenRefresher`）、
  `engine/ZemoteEngine.kt:837-865`（`generation<=1` 用首连换好的，重连先 `refresher()` 再开 socket）。
  本地过期判定 `auth/JwtExpiry.kt:12-44`：只认 JWT `exp`、留 30 s 余量，`expires_in` 不参与拦截。

**与 3.17.1 页面实际用的一致吗？——一致（逐条对齐，均为 3.17.1 快照实测）**

| 项 | 3.17.1 页面实测 | Rust | 判定 |
|---|---|---|---|
| relay 路径 | `/api/v1/remote/ws2`（`validation-B0HfQ5gr.js` off≈125934、126656） | `connection_params.rs:522` | ✅ |
| 建连前换 token | `let n=await this.options.getToken(...); r.searchParams.set('token', n)`（`index-Dwhx77cK.js` off≈6909.4k） | 宿主职责（归档已实现） | ✅ |
| `mid` | `this.options.deviceMid?.trim() && r.searchParams.set('mid', …)`（同处） | `connection_params.rs:539-546` | ✅ |
| 旧 HMAC | `auth_challenge` / `passHash` **全 JS 零命中** | legacy 分支保留（不影响新链接） | ✅（冗余兼容） |
| 心跳间隔 | `heartbeatIntervalMs??5e3`（`index-Dwhx77cK.js`） | `relay_client.rs:138` `HEARTBEAT_INTERVAL_MS = 5_000` | ✅ |
| 心跳 ack 超时 | 同族常量 `om=15e3`（`validation-B0HfQ5gr.js` off≈152400） | `relay_client.rs:140` `= 15_000` | ✅ |
| KICKED | `retry.kickedDelay()`；首次 KICK 返回 `2e3+rand(3e3)` 后**重连**，60 s 内第二次才终态（`validation-B0HfQ5gr.js` off≈152050-152160） | `relay_client.rs:836-856`：`kick_recovery_attempted` 单次恢复，否则 `Kicked` 终态 | 🟡 语义近似，非逐字（见 §6-R1） |
| token 交换接口 | `POST /api/v1/remote/token`，schema `{role}`（`validation-B0HfQ5gr.js` off≈150763） | 宿主（归档 `OAuth.kt:395-452` 同形状） | ✅ |

> ⚠️ 因此 **docs/25 与 docs/TODO 里「心跳 10s / ack 30s」是 3.16.1 时代旧值**，
> 3.17.1 页面与 Rust 都是 **5 s ±1 s / 15 s**。carrier 不需要为此改 Rust，但别按旧文档去「调成 10s」。

### 3.2 握手 / 配对

| 帧 | Rust 实现 | 行号 |
|---|---|---|
| `auth_init`（`role` / `device_sid` / `meta` / `client_ts`） | ✅ 两模式 | `relay_client.rs:556-590` |
| `auth_challenge` → `auth_response`（proof） | ✅ legacy；token 模式忽略 | `:665-700` |
| `auth_ack`（门控：必须 `authenticating` 且 `device_sid` 相等） | ✅ | `:700-716` |
| `pair_status_ack`（门控：必须先 `authenticated`） | ✅ | `:717-731` |
| `apply_pair_status`（`waiting` / `matched`，`was_paired` 语义） | ✅ | `:758-800` |
| `pair_status_query` 心跳（周期定时器由宿主实现） | ✅ `TimerStart(Heartbeat, repeat=true)` | `:908-915, 985-1000` |
| ack 看门狗（15 s 无 ack → 关 socket 重连） | ✅ | `:985-1000` |
| `waiting` 30 s 超时 → 终态 | ✅ `WAITING_TIMEOUT_MS=30_000` | `:142, 922-935` |
| 重连退避 | ✅ 1s→2→4→8→15s（`1000*2^n` clamp[1s,15s]），**与页面 0.5s→10s 梯不同但同量级** | `:1116-1119`，测试 `:2183` |
| 关闭码 → 原因码 | ✅ 4004/4009/4010/4011/4012/4013 | `:125-136` |
| 错误码分流 | ✅ `AUTH_FAILED`/`WRONG_PARAM` 终态；`INTERNAL` 可恢复；`KICKED` 单次恢复否则终态；`DEVICE_OFFLINE` 保留重连 | `:830-875` |

**结论**：握手/配对这一层**不需要补任何东西**。唯一要注意的是 `matched` 后的桥/订阅流程见 §3.3。

### 3.3 数据面

**已有（Rust 侧）**

- bootstrap：`bootstrap()` → `BOOTSTRAP_REQUEST` → `BootstrapResult` 解包
  （`zemote_client.rs:1316-1334, 1203-1220, 2436-2442`）。
  ⚠️ 但 **3.16.1+ 的 bootstrap 不带顶层 `tasks`**（归档实测注记：`SessionsIndexProjection.kt:11-20`、
  `ChatViewModel.kt:2560, 2622`），Rust 也**没有任何 `tasks` 解析**（`grep tasks` 在 `zemote_client.rs` 零命中）
  → 「发现链」这条路在新版上本来就是死的（见 §3.4）。
- workspace 桥：`open_bridge` / `open_bridge_with_intent` / `reconnect_workspace`
  （`zemote_client.rs:1359-1420, 2458-2520`），桥的存活/降级/恢复（`BridgeDegraded` /
  `BridgeRecovered` / `recover_once` 重试，`zemote_client.rs:1592-1700`）。
- 通道层：`ChannelClient`（Initialize 门控、promise 配对、事件监听 → `ChannelEvent`）、
  `channel_call` 通用逃生口（`ffi/src/codec.rs:885-905`；`FFI.md §8`）。
- 物理分片：`rpc-frame` / `rpc-frame-ack` 全字段（`bridgeSessionId` / `bridgeGeneration` /
  `recoveryId` / `seq` / `messageSeq` / `fragmentIndex` / `fragmentCount` / `messageBytes` /
  `checksum` / `dataBase64`）与身份三元组门（`rpc_transport.rs:128-175, 342-360`），
  **CRC-32**（`crc32.rs`）、**重组即回 ack**（`:461`）、上限
  **单帧 1 MiB / 单消息 16 MiB / ≤64 片 / 组装超时 30 s / 512 KiB 分片**
  （`:60-105`，测试 `:1333-1335`）。
- 会话订阅：`ConversationTransport` 完整状态机 —— 握手 `helloConversationV4` +
  `initializeConversationV4`、订阅 `subscribeConversationV4`、resync（`forceSnapshot:true`）、
  看门狗、CAS 重试、`rowsRange`、附件 begin/chunk/commit/read、plans/fileChanges/fileRewind、
  以及 `ConversationState` 的快照 + 五类 delta 应用（含 `row.delta path=text` 只作用于
  `assistantText`/`reasoning`，`conversation.rs:2110-2130`）。**79 项单测**。
- sessions-index 订阅：`subscribeSessionsIndexV4`（`runtimePolicy: existing-only`）、
  快照/增量、**序号缺口回调**（`on_gap`）、`SessionsIndexState`
  （`conversation.rs:1453-1490, 2160-2300`）。

**缺口（数据面相关，均非阻塞）**

| 缺口 | 证据 | 影响 |
|---|---|---|
| 没有全局任务清单订阅（页面那条 `controller/tasks-index`） | Rust 无该 topic/订阅；薄壳里原生订它曾让**桌面端 host 崩**（`android/README.md:947-949`、`ShellRuntime.kt:1161-1165`） | 后台通知范围只能 =「已开桥的工作区 × sessions-index」+「已订阅会话的会话流」；**不要重开**（§6-R2） |
| 订阅参数缺 `visibility` | `conversation.rs:1482-1486` 只发 `{sessionId}`；页面带 `visibility:{foreground\|background}`（3.17.1 快照含该字段） | 【待验证】服务端是否据此区别推送节奏 |
| ack/replay 的 45 s 宽限只在**对端**（页面侧）实现 | Rust 只负责"收到就 ack"，不做 45 s grace 记账 | 无影响（我们是对端视角） |
| 挂起操作在 `dispose` 时不发失败 | `FFI.md §14.3` | 交还/停机时宿主必须有兜底超时 |

### 3.4 多工作区：能开多桥，但没有「桌面端同一时刻只服务一座桥」的服务权与轮换

- Rust 支持多桥：`active_bridges()` / `bridge(session)` / 每桥独立 `RpcFrameTransport` +
  `ChannelClient`（`zemote_client.rs:918-933, 481-610`），桥恢复按序重试（`:1650-1700`）。
- **没有任何服务权 / 轮换 / 饥饿度 / 上限 5 / 12 s 拍**：全仓 `grep 服务权|rotate|reanchor|hungriest`
  零命中；桥数上限 `DEFAULT_MAX_COVERAGE = 5` 与 12 s 轮换都只存在于
  `android/app/src/main/java/com/zcode/remote/core/NativeRelayClient.kt:90` 与
  `RelayBridge.kt:1040-1102`（`BridgeManager.reanchorProgress`）——**Kotlin 策略层，Rust 侧没有对应物**。
- 结论：**「开多座桥」Rust 能做；「只轮换最饿的一座 + 12 s 拍 + 上限 5」必须新写**，但可以直接照抄
  薄壳现有 `BridgeManager` 的判据（`ROTATE_MIN_STARVE_MS = 8_000`，
  `RelayBridge.kt:744`；一次 resync 最坏 ≤6 s，必须塞进 12 s 拍，`:469`）。
- 另一个必须在策略层解决的问题：**覆盖目标从哪来**。3.16.1+ 的 bootstrap / workspace-list 都**没有 `tasks`**
  （`SessionsIndexProjection.kt:11-20`、`ChatViewModel.kt:2622`），薄壳那套「发现链」
  （`NativeRelayClient.kt:151-171` 从 `bootstrap-response.result.tasks` 取活动工作区）**在新版上取不到东西**。
  可行替代（按代价升序）：① 直接复用原生 TaskStore 上一轮的「在跑任务→工作区」集合；
  ② 按用户最近活动的工作区排序、依次开桥（上限 5）后靠 sessions-index 自证；
  ③ 走 `project.readTree`（通用 `channel_call` 可达，`codec.rs:885-905`）拿全量项目树。
  这三条都需要真机验证，标【待验证】。

### 3.5 状态三源与通知字段

薄壳现行三源（`docs/android/32`）：① 页面 controller 流（全局，**承载一接管就死**）
② sessions-index 持久相位（按工作区）③ 会话流 `turnHeader.state`（只覆盖已订阅会话）。

| 源 | Rust 有没有 | 依据 |
|---|---|---|
| ② sessions-index | ✅ 完整（含 `phase` / `lastAssistantPreview` / `pendingInteraction` / `hasBackgroundWork` / `lastActivityAt` / `createdAt` / `title` / `sessionId` / `parentSessionId`，且保留 `raw` 整条） | `conversation.rs:2163-2210`；快照出口 `conversation_transport.rs:4005-4040`；`FFI.md §13.5.3` |
| ③ 会话流运行态 | ✅ `phase` / `canStop` / `isRunning` / `hasStreamingRow`（来自 `control`，与页面 `isRunning` 同口径） | `conversation_transport.rs:4011-4030`；归档消费点 `ChatViewModel.kt:2241-2250` |
| ① controller 全局清单 | ❌ 无（且见 §6-R2，**不应重开**） | 见 §3.3 缺口表 |

**通知所需字段能否取到**（对照归档通知链 `NotificationCoordinator.kt:219-300`）：

| 字段 | 来源 | 可达性 |
|---|---|---|
| `taskId` = `sessionId` | sessions-index 条目 `sessionId` | ✅ 直接 |
| `title` | 条目 `title`（`titleSource` 决定是否覆盖 readTree 标题） | ✅ 直接 |
| `displayStatus` | 条目 `phase` 经 `SessionsIndexProjection.kt:60-77` 映射（`running`/`prewarming`→running） | ✅ 直接 |
| `updatedAt` | 条目 `lastActivityAt ?? updatedAt`（`SessionsIndexProjection.kt:77`） | ✅ 直接 |
| `workspaceKey` / `workspacePath` | 桥的 `workspaceKey`（`open_bridge` 入参）与 `workspace-list` 的 path | ✅（`zemote_client.rs:564`） |
| 卡片正文 `body` | 条目 `lastAssistantPreview`（`SessionsIndexProjection.previews`）或 session 行里最新 `assistantText.text` | ✅（本条即 Rust 的 `raw`/`rows` 直出） |
| `pinned` / `archived` / `unreadAt` | **不在 sessions-index schema**（`SessionsIndexProjection.kt:25-27` 明写），只有 `readTree.tasks` / `zcodeTaskService.getTaskMeta` | 🟡 需 `channel_call` 走 `project.readTree`（Rust 支持，无协议改动）；最小集可先不做 |

### 3.6 carrier 模式：架构上完全成立

- **无 UI 依赖**：`zemote-protocol` 是纯逻辑、同步、无 I/O、不读时钟的 crate
  （`rust/README.md`「边界」、`lib.rs` 头部）；不含 Compose/Kotlin/Android 依赖。
- **宿主驱动全部 I/O 与时钟**：`FFI.md §1.1/§1.2` —— 零回调、`RecordingTransport` 把
  `ws_connect` / `ws_send_text` / `ws_close` 记成事件交给宿主执行；时间一律 `nowMs` 入参。
  ⇒ 「无 UI、可取消、不写页面状态」天然满足：carrier 只产事件 + 收输入，**不碰 DOM/页面状态**。
- **单例/全局状态**：只有一个是全局的 —— `static REGISTRY`（handle→会话）与 `NEXT_HANDLE`
  （`ffi/src/registry.rs:26-34`）。会话本身互相隔离（`:327-355` 测试），锁中毒可恢复（`:361-375`）。
  **约束**：同一 handle 上的调用不可重入，宿主必须把单个 handle 的所有 native 调用收敛到
  **同一单线程调度器**（`FFI.md §11`；归档实现用 `Dispatchers.Default.limitedParallelism(1)`
  一类的专用 dispatcher，见 `ui/EnginePort.kt` 注释）。
- **JNI 入口可直接复用，但类名被写死**：6 个符号是
  `Java_app_zemote_core_NativeCore_native{AbiVersion,Create,Step,Call,Query,Dispose}`
  （`ffi/src/jni_bridge.rs`、`FFI.md §2`）。薄壳要么放一个 42 行的
  `app.zemote.core.NativeCore`（抄 `archive/.../core/NativeCore.kt:1-42`）即可**零改 Rust**，
  要么改 Rust 的 `#[no_mangle]` 名。每个入口都包 `catch_unwind`（`jni_bridge.rs:44-147`）。
- **取消/交接语义**：`dispose` 干净 —— 拆全部桥 → relay `TimerCancel`×4 → `transport.close` →
  `RelayState::Closed`（`zemote_client.rs:1592-1610`、`relay_client.rs:1169-1190`）。
  **但**：① `ws_close` 的 code/reason 为 `null`，宿主需自己用 1000/自带 reason；
  ② 「等 socket 真的关闭」只能靠宿主（Rust 无法观测 TCP 层）；
  ③ 关闭对话不发 unsubscribe（`FFI.md §14.3`）。
- **无页面副作用**：Rust 从不写 localStorage/history；唯一的「页面状态」概念是
  `send_mobile_view_state`，那是发给**桌面端**的（`zemote_client.rs:1472-1486`），不是写页面。

### 3.7 交接：Rust 侧零实现；薄壳已有可复用的状态机与阈值

**Rust 提供的只有连接层状态机**：`idle/connecting/authenticating/waiting/paired/reconnecting/error/kicked/closed`
（`FFI.md §7.2`）——**没有「谁持有连接」的所有权状态机**。

**薄壳已有的（可直接对接）**

| 件 | 位置 | 作用 |
|---|---|---|
| `HandoffState`（62 行） | `android/…/core/HandoffState.kt` | epoch / away / `ownsAwayWindow` / `foregroundArrivalPending` / `claimCarrier` / `releaseCarrier`；`START_DELAY_MS=200`、`RETURN_CHECK_MS=500` |
| `CarrierHandback`（66 行，带 7 项单测） | `android/…/core/CarrierHandback.kt` | 交还不变式：`shouldHandBack = pageLinkAlive && (!startedEarly \|\| pageIsServing)`；`pageIsServing = readyState==1 && paired && convFrameAgo<30s` |
| 判死阈值 | `ShellRuntime.kt:1123` `STALL_SILENCE_MS = 35_000`；`:1139` `EARLY_TAKEOVER_CONV_SILENCE_MS = 30_000`；另加「页面上次动 socket 后 20 s 内不接管」守卫（`:1417-1440` 注释） | 与 TODO §2.2「入站帧静默 ≥35 s」一致 |
| 接管/交还编排 | `ShellRuntime.kt:1386-1560` 一带（`carrierStarted` / `carrierStartedEarly` / `carrierHandedBack` / `carrierRanThisStint` / `carrierMayHaveKickedPage()` `:1264`） | 现在是围绕 `NativeRelayClient` 写的，把引擎换成 Rust carrier 即可 |
| KICK 事实 | `ShellRuntime.kt:1161-1165`（原生订 controller 流让桌面 host 崩）、`android/README.md:1227`、`:1289`（`KICKED 自愈：1.5s 后重载页面（第 1/2 次）`） | 交接时的既知坑 |

**需要新写的状态机（3 件，都不在 Rust 里）**

1. **所有权机**：`PAGE_OWNED → HANDOFF_PENDING → NATIVE_DRAINING → NATIVE_CONNECTING → NATIVE_PAIRED →
   NATIVE_STREAMING →（回前台）→ HAND_BACK → PAGE_RESTORING`（docs/25 §3.2 已给出完整形态）。
2. **接管准入**：**「页面 socket 已确认关闭」才允许 native dial**（docs/25 §3.2 硬规则 1）。
   薄壳现状是 **KICK 互斥**路线（`NativeRelayClient.kt:38-45`「接管会踢掉页面连接（KICK 互斥）」），
   并未真正做到"先关后连"。3.17.1 页面新增「KICKED 会先重试一次」后，**双向重试**可能把
   "一条连接"变成"两条互相顶"——这条要么补一个注入命令（薄壳注入层已经 hook 了 `WebSocket`，
   `assets/inject.js:288-360`，加一个「关闭页面 relay socket」的小命令即可），要么接受 KICK 并
   把「60 s 内第二次 KICK」当作硬失败。
3. **交还顺序**：native 先 `dispose` → 确认 `ws_close` 落地 → 再通知页面（reload 或
   让页面自恢复）；`KICKED` 走「主动让位」而不是用户可见失败（docs/25 §4.2）。

---

## 4. 最小功能集（只做后台通知与连接延续）

目标：**健康路径页面持有连接不变**；只在页面链路判死后由 Rust carrier 顶上，把「在跑任务的进度/状态」
喂给现有 `TaskStore` / `NotifyState` / `Notifier`。

1. **构建与打包**：把 `zemote-ffi` 编成 `libzemote_ffi.so`（arm64-v8a）进 `android/app/src/main/jniLibs/`；
   Kotlin 侧加 `app.zemote.core.NativeCore`（42 行，零改 Rust）；CI 恢复 NDK 步骤（薄壳 CI 现无 Rust/NDK）。
2. **宿主 I/O 层**（最小裁剪版引擎）：OkHttp WebSocket + 4 个 relay 定时器槽 +
   conversation 定时器/监听器回喂 + `nowMs` 时钟；单线程调度器串行化单 handle。
   **不做** UI、不做附件、不做命令发送（只读）。
3. **握手 + 配对**（Rust 现成）：`connect → auth_init → auth_ack(pair_status=matched)`；
   `pair_status_query` 心跳（5 s）+ 15 s ack 看门狗；`KICKED` 处理。
4. **只开「在跑任务所在工作区」的桥**（上限 5 先不做轮换，取 1–3 座即可）：
   `bootstrap`（拿 workspaces）→ `open_bridge(workspaceKey)` → `conversation_open(scope)` →
   `handshake` → `subscribeSessionsIndex`。
5. **只订在跑会话的会话流**：对 `sessionsIndex.list` 里 `phase ∈ {running, prewarming}`（或原生
   TaskStore 已有的在跑集）逐条 `subscribe(sessionId)`，取 `phase`/`isRunning` + 最新 `assistantText`。
6. **喂给现有通知链**：复用薄壳 `TaskStore.applyWorkspace` / `applyConversationRunState` /
   `NotifyState`；正文优先 `lastAssistantPreview`，其次会话流行文本。**不碰页面 UI、不写页面状态。**
7. **交还**：回前台 → carrier `dispose` → 确认 socket closed → 页面重载（薄壳已有兜底重载逻辑）；
   `KICKED` 只当作「让位」信号，不做用户可见失败。
8. **判死**：沿用 35 s 入站静默 + 20 s socket-quiet 守卫（`ShellRuntime.kt:1123, 1417-1440`）。

---

## 5. 缺口与移植工作量（粗估）

**口径**：人日 = 1 名熟悉本仓的工程师；「依据」列是量级来源，不是精确测量。

| # | 工作 | 粗估 | 依据 |
|---|---|---|---|
| 1 | Rust→APK 构建链（cargo-ndk/NDK/CI/jniLibs/ABI 自检） | **1–1.5** | 归档 `rust/README.md` 已有完整交叉编译配方（含 CI 变量版）；薄壳 CI 需重加（`docs/TODO.md §1.8` 明写现在去掉了 Rust/NDK） |
| 2 | 宿主 I/O 层移植（engine 2009 行 + core 93 行 + auth 505 行 + AuthStore 198 行 + PairingUrl 317 行 ≈ 3.1 千行，**均无 Compose 依赖**，实测 `grep androidx.compose` 零命中） | **2.5–4** | 行数实测（`wc -l`）；改动面主要是包名 `app.zemote.*`→薄壳包、依赖从 `kotlinx.serialization` 换成薄壳已有的 `org.json`/OkHttp（薄壳已用 OkHttp） |
| 3 | 通知输入接线（sessions-index → 任务表、preview → 正文、会话流 → 运行态；参考归档 `SessionsIndexProjection.kt` 105 行 + `NotificationCoordinator.kt` 376 行） | **2–3** | 薄壳 `TaskStore`(880 行)/`NotifyState` 已是成品，主要写「第二输入源」适配 |
| 4 | 多工作区覆盖 + 轮换（上限 5 / 最饿 / 12 s 拍 / 服务权回收） | **2–3** | 参考 `android/…/RelayBridge.kt:1040-1102`（BridgeManager 约 460 行）+ `NativeRelayClient.kt` 覆盖刷新（60 s） |
| 5 | 交接状态机接线（所有权机 + 接管准入 + 交还顺序 + KICKED 让位；`HandoffState`/`CarrierHandback` 已有） | **2–3** | `docs/25 §3.2/§3.3`；薄壳 `ShellRuntime` 已有 80% 编排骨架（2598 行） |
| 6 | 真机验证（判死接管、交还 20 次循环、30 min 后台、快速切应用、KICKED 观测） | **3–5** | `docs/25 §6` 的 E1/E2/E4/E6/E9/E14；每项都要真机 + 桌面端 |
| | **合计（完整最小功能集）** | **13–20** | |
| | **只做单工作区最小集**（砍掉第 4 项、第 3 项减半） | **10–16** | |

**明确不需要做的**（省下来的量）：认证/HMAC/握手/分片/CRC/会话订阅/索引订阅 —— Rust 全有且带测试；
归档已有 519 项 `#[test]` 可在主机侧跑（`cd archive/native-compose/rust && cargo test`）作为回归网。

---

## 6. 风险与前置条件

- **R1 · KICKED 对抗窗口变窄（最高优先）**：3.17.1 页面在收到 KICKED 后会**先重试一次**
  （2–5 s 后重连，60 s 内第二次才终态，`validation-B0HfQ5gr.js` off≈152050-152160）。
  ⇒ 若 carrier「先连再让页面退出」，页面会重拨并把 carrier 顶掉，carrier 再顶回来，**双方各退一次后
  双双进入终态**（用户看到「已被其他设备接管」死页）。前置条件：接管必须「先确认页面 socket 已关」
  （需要一条注入命令；注入层已 hook `WebSocket`，`assets/inject.js:288-360`），或明确设计为只允许一次对抗。
- **R2 · 桌面端 host 崩溃禁区**：原生订阅 `controller/tasks-index` 曾让桌面 host
  `uncaughtException` 退出（`android/README.md:947-949`、`ShellRuntime.kt:1161-1165`，
  且当年 channel 名可能还打错了）。⇒ 最小集**只用 sessions-index + 会话流**，别重开这条。
- **R3 · 单控制端互斥**：任意时刻只允许一条 `/ws`；薄壳的兜底重载、点通知定位等路径都依赖
  `handBackCarrierForLocate()` 先交还（`android/README.md:1045`）。carrier 上线后这些路径都要同步改接 Rust carrier。
- **R4 · 页面无原生桥**：页面不暴露任何 native 接口（docs/25 §2.9-⑧），接管/交还的「页面侧动作」
  只能靠注入层；注入层已 hook `WebSocket` 且维护 socket 索引，是唯一入口。
- **R5 · ColorOS/Doze 保活**：前台服务 + WakeLock + WifiLock 已有（薄壳 `KeepAliveService`），
  但 ColorOS 可能在 AOSP 规则外杀 FGS（docs/25 B16）⇒ 必须保留"断了重连 + 回前台对齐"。
- **R6 · 依赖/包名差异**：归档宿主用 `kotlinx.serialization`，薄壳用 `org.json`；
  JNI 符号绑定 `app.zemote.core.NativeCore`；minSdk 26 与 clang wrapper `aarch64-linux-android26-clang` 需一致。
- **R7 · 基线漂移**：薄壳最后验证基线是 3.15.3，3.17.1 锚点审计未做（`docs/TODO.md §4`）；
  Rust 常量对齐只核到「心跳/ack 超时/ws2/token/KICKED」这几条（§3.1 表），其余未逐条比对。

---

## 7. 推荐落地顺序

1. **半天 spike（零风险）**：在 `archive/native-compose/rust` 跑 `cargo test`（应为 519 项全绿），
   再用归档配方出一版 `libzemote_ffi.so`，在薄壳里加 `app.zemote.core.NativeCore` + `dryRun` 调用，
   验证 **ABI/打包/加载** 三件事（不动任何生产路径）。
2. **只读 carrier 原型**：单工作区、`open_bridge` + `subscribeSessionsIndex` + 会话流，
   把条目喂给现有 `TaskStore`/通知链；**不接管、不写页面**，只做「影子观测」比对
   （与页面自身推送的 sessions 结果对照，验证帧解析与字段口径）。
3. **接管/交还小闭环**：接 `HandoffState`/`CarrierHandback` + 35 s 判死；
   先在诊断命令下手动开关（薄壳已有 `carrier_on/carrier_off`、`carrier_takeover` 一类通路），真机跑
   20 次「后台→前台」，目标 0 次 KICKED 死页（对应 docs/25 E6/E7）。
4. **补运行态与正文**：会话流 `isRunning` 合并进 `TaskStore` 三源（替代已死的 controller 源，
   注意 `TaskStore` 的「谁后到谁算数」规则）；正文用 `lastAssistantPreview` 起，
   会话流增量再补精度。
5. **多工作区轮换**：移植 `BridgeManager.reanchorProgress` 策略（上限 5 / 最饿 / 12 s 拍），
   同时解决「覆盖目标从哪来」（§3.4 的三条替代路，先取①）。
6. **收口验收**：docs/TODO §2.3 的四条验收（30 min 后台、回前台 0 重载、故障路径交还、
   快速切应用 <5 s 不接管）+ 单控制端断言（任意时刻一条 `/ws`）。

---

## 8. 未验证项（本报告未实测，均需真机/真桌面端）

1. **KICKED 双向重试的真机行为**：carrier 与 3.17.1 页面各做一次恢复尝试后是否稳定进入终态；
   60 s 窗口是否如代码所示（Evidence 是压缩 bundle 的字节偏移，未跑线上）。
2. **`visibility` 订阅参数**是否影响服务端推送节奏（Rust 不发该字段）。
3. **覆盖目标来源**：3.16.1+ 是否真的完全没有全局任务源（除 readTree 外）；
   readTree 在 carrier（后台、无 UI）下调用是否有副作用或额外权限要求。
4. **terminal token 失效路径**：carrier 持有会话期间若 JWT 过期（>exp 后重连才会重换 token），
   是否会出现「无法重连且无处提示」；需要一条"回前台要求重新登录"的出口。
5. **Rust 在 Android 上的随机源/时区无关性**（`getrandom` 0.3 走 `getrandom(2)`，属低风险但未在真机跑过）。
6. **3.17.1 相对归档版（3.15.3/3.16.1 基线）的协议差异清单**：本次只核了认证/心跳/KICKED 三项，
   `FFI.md §13.5.1` 的 op 面（命令、附件、plans/fileChanges）未与 3.17.1 逐条比对。
7. **薄壳 CI 恢复 Rust/NDK 后的构建时间与缓存策略**（TODO §1.8 现在刻意不构建 Rust）。

---

### 附：本次用到的关键证据索引（便于复核）

- Rust 认证/握手：`archive/native-compose/rust/crates/protocol/src/connection_params.rs:462,469,488-511,518-575`；
  `relay_client.rs:125-142,404-424,556-590,665-731,758-800,830-875,908-915,985-1000,1116-1119,1169-1190`
- Rust 数据面：`rpc_transport.rs:60-105,128-175,342-360,461`；`zemote_client.rs:1316-1470,1592-1610,2436-2520`；
  `conversation.rs:1453-1490,2160-2300`；`conversation_transport.rs:OPS,1002-1205,4005-4040`
- FFI 契约：`archive/native-compose/docs/FFI.md` §1.1/§1.2/§2/§5/§6/§7/§8/§11/§13.2/§13.5.3/§14.1-14.3
- 宿主参考实现：`archive/native-compose/android/app/src/main/kotlin/app/zemote/{auth/OAuth.kt:374-452, auth/JwtExpiry.kt, engine/ZemoteEngine.kt:837-865, ui/EnginePort.kt:313-399, ui/chat/SessionsIndexProjection.kt, notify/NotificationCoordinator.kt:219-300}`
- 3.17.1 页面：`Reference_web/3.17.1/assets/validation-B0HfQ5gr.js` off≈125934/126656（ws2 URL）、
  off≈150763（`POST /api/v1/remote/token`）、off≈152050-152160（`kickedDelay` 60 s 窗 + 心跳常量 `im=5e3/om=15e3`）；
  `Reference_web/3.17.1/assets/index-Dwhx77cK.js` off≈6909.4k（`getToken` + `token`/`mid` query）
- 薄壳现状：`android/README.md:1227,947-949,1045,1289`；`android/app/src/main/java/com/zcode/remote/core/{HandoffState.kt,CarrierHandback.kt,NativeRelayClient.kt:38-45,90,20,906-935,RelayBridge.kt:744,1040-1102,SessionsIndexState.kt}`；
  `ShellRuntime.kt:1123,1139,1161-1165,1264,1386-1452`；`app/src/main/assets/inject.js:288-360`
- 设计稿与 TODO：`docs/TODO.md §2`、`docs/android/25-android-shell-后台原生承载-独立设计.md §2/§3/§4/§5/§6`、
  `docs/android/31-…多工作区承载与轮换.md`、`docs/android/32-…运行态三源判定与原生承载.md`、`docs/android/29-…任务通知与实时卡片.md`
