# remote-v4 `bootstrap-response` 载荷：合并总表与「可用但未被解析」字段清单

> **审计基线**：3.15.3（2026-10-15 快照）
> 主包 `assets/index-BGa3KiRe.js`（6,592,643 B，SHA-256 `ce2e2fc4…b88fd`）
> schema `assets/validation-CqXZOLXO.js`（307,369 B，SHA-256 `96e64e8f…dd6c9b`）
> 旧基线（2026-09-20 / `index-B-ilXaCQ.js`）的偏移与符号名**一律作废**。
>
> **审计日期**：2026-10-15 ｜ **方法**：载荷字段按 `validation-CqXZOLXO.js` **字节偏移**取证；
> 客户端消费面按**当前工作树实读**（`rust/crates/**` + `android/app/src/**`，`文件:行` 可直接跳转）；
> 参考实现按 `Reference_code/lib/protocol/zemote_client.dart`（Dart 冻结版）。
> 标「**外部输入**」的条目来自**旧安卓壳**真机日志/注释 —— 该壳已不在工作树（见 §0 末），一律标「未复核（旧基线）」。

**这份文档回答什么**：那个约 28 KB 的包里除了"哪些工作区在跑任务"，**还有哪些信息**、
**当前客户端丢掉了什么**、**能拿来增强流体云卡片与后台追踪的字段具体是哪几个、要动哪里**。

字段的权威定义、逐字 schema 文本、枚举取值与生产/消费链见
[`14-remote-v4-bootstrap载荷-完整字段表.md`](14-remote-v4-bootstrap载荷-完整字段表.md)。

---

## 0. 一句话结论（含量化）

`bootstrap-response` 是**配对端（桌面）在配对成功后给出的"全工作区任务状态全量快照"**。
帧内共 **37 个键位 / 33 个去重键名**（信封 4 + `result` 顶层 6 + 视图状态 3 + `workspaces[]` 元素 8 + `tasks[]` 元素 **16**；
旧基线是 34 / 30，本代因新增 `desktopAppVersion`、`hasBackgroundWork`、`workflowActivity` 而变大）。

**当前客户端从这一帧里读进程序逻辑的 schema 键是 0 个。**
唯一消费点是 Kotlin 侧 `WorkspaceProjection`（`ChatViewModel.kt:424-470`），而它的"键门"要求
`key`/`workspaceKey`/`id`（`ChatViewModel.kt:441`）—— 真载荷的 `workspaces[i]` 里**一个都没有**
（schema `tS` 只有 `workspacePath`/`workspaceIdentity`/`remoteSessionId`/`label`/`workspacePurpose`/`kind`/`connectionState`/`lastConnectionError`）。
结果是：**每一座工作区都被 `?: return null` 丢掉，投影恒为空列表**（`ChatScreen.kt:92-101` 显示"工作区（0）"）。

即便把这道键门修好，上限也只有 **3/24 元素键**（`workspacePath`→`path`、`kind`→`kind`、
`lastConnectionError`→`lastError`）；**`tasks[i]` 的 16 个键结构上不可达** ——
投影只在**工作区元素内部**找 `tasks`/`items`（`ChatViewModel.kt:450-453`），
而协议把 `tasks` 放在 `result` **顶层**、与 `workspaces` **平级**（schema `sS`）。

### 客户端基线变更（必读）

旧基线（2026-09-20）的"壳端"是 `android-shell/app/src/main/java/com/zcode/remote/` 的
`RelayBridge.kt` / `Tier2Probe.kt` / `RelayTaskDigest` / `TaskStore`。**这些文件在当前工作树里已全部不存在**
（`find . -iname "*RelayBridge*" -o -iname "*Tier2Probe*"` = 0 命中）。当前实现是：

| 层 | 位置 | 说明 |
|---|---|---|
| Rust 协议核心 | `rust/crates/protocol/src/`（`zemote_client.rs` 4,176 行、`relay_client.rs` 2,404 行、`device_info.rs`…） | **逐行移植 Dart 冻结版**（`Reference_code/lib/protocol/zemote_client.dart`），文件头注释写明"复刻网页端 `otn()` 那条主流程" |
| FFI 桥 | `rust/crates/ffi/src/`（`session.rs` / `codec.rs` / `jni_bridge.rs`…） | JSON 命令/事件信封 |
| Kotlin UI | `android/app/src/main/kotlin/app/zemote/` | `engine/ZemoteEngine.kt`、`ui/chat/ChatViewModel.kt` 等 |

⟹ 本文所有"客户端读没读"的结论都按**这三层**实读；旧基线的 `RelayBridge.kt:775` 这类坐标**已作废**。

---

## 1. 载荷定位与三个结构性事实

| 项 | 事实 | 证据 |
|---|---|---|
| `sS` 的完整键集 | `{windowControlSessionId, desktopAppVersion?, workspaces, tasks, initialViewState?, mobileViewState?}` —— **6 键，前 3 + tasks 共 4 个必填** | `validation-CqXZOLXO.js` @**284,202** |
| 同族两帧 | `workspace-list-response`（有 `requestId`+`success`）与 `workspace-list-updated`（**无 `requestId`、无 `success`**，配对端主动推）共用 `cS`：`{workspaces, tasks?, activeWorkspaceKey?, activeTaskId?}` | @284,350 / @284,866 / @284,947 |
| 谁应答 | **配对端（桌面）**；网页端与移动客户端同为客户端 | 网页端请求 @6,579,011、等待谓词同段 |
| 客户端发送点 | Rust `ZemoteClient::bootstrap()`（`zemote_client.rs:1285`）→ `bootstrap_request(request_id)`（`:2383`）；载荷形状 `{zcode_type:'bootstrap-request',requestId}` | 同左；Kotlin 侧 `ZemoteEngine.kt:235` `suspend fun bootstrap()` |
| 客户端触发时机 | 配对成功（`waitPaired`）之后立刻 `engine.bootstrap()` | `ChatViewModel.kt:303-306` |
| 结构① **原文不落盘** | Rust 侧把整帧交给 `ZemoteEvent::WorkspaceListUpdated(Value)` / operation 结果，**不做键集摘要**；Kotlin 侧只把 `value` 喂给 `WorkspaceProjection`，不留原始 JSON | `zemote_client.rs:1033`；`ChatViewModel.kt:306`、`:355-358` |
| 结构② **只取 `workspaces` 一个键** | `WorkspaceProjection.from(snapshot)` 只认 `snapshot["workspaces"]`（或 `items`/`list`），**顶层 `tasks` 从不被读** | `ChatViewModel.kt:427-438` |
| 结构③ **元素层靠猜键名** | 逐元素要求 `key`/`workspaceKey`/`id` 之一；真载荷 8 个键里没有 ⟹ **整份投影归零** | `ChatViewModel.kt:441` vs schema @282,217 |

⟹ **那 28 KB 原文在当前客户端里的唯一落点是一条可能为空的投影列表**；
`sS` 的 6 个键里只有 `workspaces` 被碰过，`tasks`/`initialViewState`/`mobileViewState`/`desktopAppVersion`/`windowControlSessionId` **全部零读取**。

---

## 2. 逐字段合并总表

图例：**✅读** = 客户端真读进逻辑；**◐兜底** = 代码里存在但真载荷上不生效；**❌未读** = 零读取零存储。
「用在哪」列给当前工作树的 `文件:行`；「能干什么」列给可用方向（**只描述，本次未改任何代码**）。

### 2.1 信封层（`bootstrap-response` 共 4 键）

| 字段 | 类型/枚举 | 客户端 | 用在哪（`文件:行`） | 能干什么 |
|---|---|---|---|---|
| `zcode_type` | 字面量 | ✅读 | 分派 `dispatch_payload` 的 `payload_type` 分支（`zemote_client.rs:1023-1040`）；常量表 `:98-127` | — |
| `requestId` | 非空串（≤256，`[A-Za-z0-9._~-]`） | ✅读 | **挂起请求的匹配谓词**（`zemote_client.rs:1233`、`:1254-1261`；注释 `:205-206` 说明"响应不保证回显 requestId，所以每个挂起项都拿每个载荷试一遍"） | — |
| `success` | 字面量 `true`（schema **无 false 变体**） | ◐兜底 | **只在 recovery 路径读一次**（`zemote_client.rs:1712` `if response.get("success")==Some(&Value::Bool(true))`，用于 `workspace-reconnect-response`）；bootstrap / workspace-list 路**不看** | 无增量价值：失败只能走 `app-error{reason}`（11 值，含 `desktop-bootstrap-timeout`）或超时；而客户端对 `app-error` **零处理**（§2.7） |
| `result` | object（6 键） | ✅读（但只取 1 个键） | `ChatViewModel.kt:306` 只取 `result.workspaces` | 见下 |

### 2.2 `result.windowControlSessionId` / `result.desktopAppVersion`

| 字段 | 类型 | 客户端 | 能干什么 |
|---|---|---|---|
| `windowControlSessionId` | 非空串（必填） | ❌未读（`grep -rn windowControlSessionId rust/crates android/app/src` = **0 命中**） | 桌面窗口控制会话的稳定标识。网页用它判"这次移动连接是否还挂在同一个桌面窗口上"（主包状态比较器 @4,514,820 / @4,658,060 一带、WS ready 回传 @6,564,666）⟹ 可用它做**重连后"还是不是原来那个桌面窗口"**的判据，避免跨窗口误接管 |
| `desktopAppVersion` | 非空串（可选） | ❌未读（`grep -rn desktopAppVersion rust/crates android/app/src` = **0 命中**） | **本代新增**。桌面的应用版本；网页端拿它跟 URL 的 `app_version` 比对，不一致就导航对齐（主包 @6,548,337 的 `s6n`）。客户端可用它判"我连的桌面与我预期版本是否一致"，或显示在诊断页 |

### 2.3 `result.workspaces[i]`（`tS`，8 键）

| 字段 | 类型/枚举 | 客户端 | 用在哪（`文件:行`） | 能干什么 |
|---|---|---|---|---|
| `workspacePath` | 非空串（必填） | ❌**未读**（投影里读的是 `path`/`workspacePath`/`root`，`ChatViewModel.kt:446`，但**在 `key` 门之后**，永远走不到） | — | 修好键门后：**工作区键的唯一兜底**（§4①），也是列表副标题 |
| `workspaceIdentity` | 非空串（可选） | ❌**未读**（投影里**完全没有** `workspaceIdentity` 这个词；`grep` = 0 命中） | — | **远程工作区的主键**（网页规则 identity 优先）。当前客户端缺这个词 ⟹ 远程工作区在客户端侧**没有键来源** |
| `remoteSessionId` | 非空串（可选） | ❌未读（0 命中） | — | **远程工作区开桥凭据之一**：网页开桥资格 = `kind!=='remote' \|\| (workspaceIdentity && remoteSessionId)`（主包 @6,584,266）⟹ 有了可直接判"这座能不能开桥"，省一次试探 |
| `label` | 非空串（必填） | ❌**未读**（投影读的是 `name`/`title`，`:445`） | — | 覆盖清单/卡片的**权威可读名**（现在即使键门修好也只能落到 `key` 自身当名字） |
| `workspacePurpose` | `project`｜`conversation` | ❌未读（0 命中） | — | `conversation` = 配对端内置"默认会话"伪工作区；网页首页正是把它滤掉（主包 @3,261,030 一带的 `workspacePurpose==='conversation'` 分支）⟹ **可避免它污染覆盖清单**（多一座永远匹配不上的项） |
| `kind` | `local`｜`remote` | ◐兜底 | 投影读 `kind`/`workspaceKind`/`type`（`:447`），同样卡在 `key` 门后 | 决定要不要开远程桥（配合上面两条） |
| `connectionState` | `connected`｜`disconnected`｜`reconnecting` | ❌**未读**（投影读的是 `connected` **布尔**，`:448` —— schema 里没有这个键，只有三值枚举） | 远程工作区"未连/重连中"的卡片态 |
| `lastConnectionError` | 字符串（可选） | ◐兜底 | 投影读 `lastConnectionError`/`error`（`:449`），同样卡在 `key` 门后 | 卡片错误副标题 / 排障提示 |

> ⚠️ 三处**键名不匹配**是本节的核心：投影猜 `key`/`name`/`connected`，schema 是 `workspacePath`+`workspaceIdentity`/`label`/`connectionState`。
> 这不是"读得少"，是**读不到**。

### 2.4 `result.tasks[i]`（`nS`，**16 键**）

| 字段 | 类型/枚举 | 客户端 | 用在哪 | 能干什么 |
|---|---|---|---|---|
| `taskId` | 非空串（必填） | ❌**结构不可达** | 投影的 `toTask()` 只在**工作区元素内部**被调用（`:450-453`）；协议把 `tasks` 放在 `result` 顶层 ⟹ 该函数**永不执行** | **任务级 id**：让卡片直接锚到任务、省一次桥内往返 |
| `title` | 字符串（可为空串） | ❌结构不可达 | 同上（投影读 `title`/`name`，`:461`） | **卡片正文** |
| `workspacePath` | 非空串（必填） | ❌结构不可达 | 同上 | 任务→工作区归属 |
| `workspaceIdentity` | 非空串（可选） | ❌结构不可达 | 同上 | 远程任务的工作区键来源 |
| `remoteSessionId` | 非空串（可选） | ❌结构不可达 | 同上 | 免等 sessions-index |
| `workspaceLabel` | 非空串（必填） | ❌结构不可达 | 同上 | 卡片副标题（**不是键**，别拿来匹配） |
| `workspaceKind` | `local`｜`remote` | ❌结构不可达 | 同上 | 任务级本地/远程 |
| `createdAt` | number(finite)（必填） | ❌结构不可达 | 同上 | 时间线起点 |
| `updatedAt` | number(finite)（必填） | ❌结构不可达 | 同上 | **"最近活动"排序**（网页按它排：主包 @4,424,66x `.sort(cbn('updated'))`）；也可当**桌面侧权威时间戳**做陈旧判定 |
| `provider` | enum（**本代只剩 `glm`**） | ❌结构不可达 | 同上 | 卡片标注 agent 类型（枚举已收缩，见 14 篇 §5） |
| `unreadAt` | number(finite)（可选） | ❌结构不可达 | 同上 | **未读角标**；它是 **CAS 令牌**：读作布尔安全，要"标已读"必须回带 `expectedUnreadAt`（主包 @5,731,723） |
| `displayStatus` | `idle`｜`running`｜`completed`｜`error`（可选） | ❌结构不可达 | 同上（投影读 `status`/`state`，`:462`） | **扁平运行态**：卡片"在跑/已完成/出错"的唯一现成来源 |
| `hasBackgroundWork` | **boolean**（可选，**本代新增**） | ❌结构不可达 | 同上 | "后台还有活在跑"的明确标志；与 `displayStatus` 互补（网页 true-only 写入，主包 @4,424,566 一带） |
| `workflowActivity` | `ud`（可选，**本代新增**） | ❌结构不可达 | 同上 | 工作流 phase 进度（`runs[].phases[].status`，4 值枚举；`runs` 最多 4 条，见 14 篇 §5）⟹ 卡片可显示"3 个 agent 在跑第 2 阶段" |
| `pinned` | boolean（可选，**true-only**） | ❌结构不可达 | 同上 | 置顶排序（网页三分法 `pinned&&!archived` / `archived` / 其余） |
| `archived` | boolean（可选，**true-only**） | ❌结构不可达 | 同上 | 归档分组；**同时是重要的过滤位**：归档任务不该占用卡片 |

> ⚠️ **16 个任务键全部不可达**，原因不是"没写解析"，而是**结构错位**：
> `ChatViewModel.kt:450-453` 只在工作区元素里找 `tasks`/`items`，而 schema `sS` 把 `tasks` 放在
> `result` 顶层（与 `workspaces` 平级）。修键门也救不回来。

### 2.5 `result.initialViewState` / `result.mobileViewState`（各 3 键，bootstrap 独有）

| 字段 | 类型 | 客户端 | 能干什么 |
|---|---|---|---|
| `activeWorkspaceKey` | 非空串（可选） | ❌未读（`grep -rn initialViewState\|mobileViewState rust/crates android/app/src` = **0 命中**） | **配对瞬间得知"桌面正在看哪"** ⟹ 首卡片排序 / 预开那一座桥 |
| `activeTaskId` | 非空串（可选） | ❌未读 | 同上 |
| `updatedAt` | number(finite)（**必填**） | ❌未读 | 判该焦点的时效（过旧就不该拿来排序） |

合并规则（网页明写，**relay 与 REST 两条路径都有**）：**`mobileViewState` 优先于 `initialViewState`** ——
relay 路径在 `src-AL-_ONNp.js:64,013` 的 `Ud`（`o=t??n`，`t`=mobileViewState），REST 路径在主包 @6,557,212
（`n.mobileViewState?.activeWorkspaceKey ?? n.initialViewState?.activeWorkspaceKey`）。

注意这两个槽与**客户端已经在发的** `mobile-view-state-update.viewState`（Rust `zemote_client.rs:2462`
`mobile_view_state_update_payload()`；Kotlin `ChatViewModel.kt:328` `engine.sendMobileViewState(...)`，
实现 `ZemoteEngine.kt:309`）**同一形状**（schema `aS` @283,744）—— 它们是那条上报的**回执侧**。

### 2.6 增量族

| 帧/字段 | 客户端 | 用在哪 | 能干什么 |
|---|---|---|---|
| `workspace-list-response.result.{workspaces,tasks?,activeWorkspaceKey?,activeTaskId?}` | ◐部分读 | Rust 把它当普通响应交给挂起请求；Kotlin 侧 `engine.listWorkspaces()`（`ZemoteEngine.kt:238`）取 `outcome.value` —— **当前 UI 没有调用点**（`grep -rn listWorkspaces android/app/src` 只有声明与实现） | `tasks` 是**白拿的一份活动态**；`activeWorkspaceKey`/`activeTaskId` 是桌面认定的焦点 |
| `activeWorkspaceKey` / `activeTaskId`（`cS` 的两个增量键） | ❌未读 | — | 网页用"缺省回填上次已知焦点"的语义消费（主包 @6,575,959 `x=e=>({...e,activeWorkspaceKey:e.activeWorkspaceKey??s,…})`）⟹ 客户端若缓存它，就能在**没有 tasks 的增量帧**里保持焦点不丢 |
| `workspace-list-updated`（整条推送） | ◐**收到了但解析不出东西** | Rust `dispatch_payload` 把 `payload.result` 原样塞进 `ZemoteEvent::WorkspaceListUpdated`（`zemote_client.rs:1031-1036`）→ FFI `codec.rs:229-231` → Kotlin `ChatViewModel.kt:354-358` 又喂给**同一个** `WorkspaceProjection` ⟹ 同样归零（`cS` 的形状与 `sS` 的 `workspaces` 一致） | **零新增订阅的增量刷新源**（配对端主动推、无 requestId）。**通道已通、解析归零** —— 修 `WorkspaceProjection` 即可同时救活这一路 |

### 2.7 客户端**零命中**的 relay 消息类型（顺带核实）

union `uS` 的 19 个唯一 `zcode_type` 里，Rust 常量表（`zemote_client.rs:98-127`）覆盖 **14 个**；
**零命中的 5 个**：`telemetry-report`（本代新增）、`platform-request`、`platform-response`、
`app-error`、`mobile-diagnostic`。
（旧基线口径是 18 种里零命中 6 种：`workspace-list-updated`、`workspace-reconnect-request`、
`workspace-reconnect-response`、`platform-request`、`platform-response`、`app-error` ——
其中前三种**当前客户端已实现**，`telemetry-report` 是新增的缺口。）

其中 `app-error` 值得单列：它是本族**唯一**的失败通道（`reason` 取 11 个 `rS` 值之一，含可重试的
`desktop-bootstrap-timeout`/`relay-unavailable`）。客户端在 bootstrap/通用路径上**完全不看它** ——
所以"bootstrap 为什么没回来"在客户端日志里只能表现为"没有 `bootstrap-response` 那一行"（超时后由
宿主侧兜底抛 `kind="timeout"`，`ZemoteEngine.kt` 的 `HOST_TIMEOUT_GRACE_MS`）。

---

## 3. 「可用但未被解析」清单（按用途分类）

> 共同前提：这些字段**已经在收的那一帧里**（无需新订阅、无需新握手、无需改协议），成本主要是解析侧改动。
> ⚠️ 但**当前有一道前置门槛**：先修 `WorkspaceProjection` 的键门与 `tasks` 归属（§3.7），否则下面全部拿不到。

### 3.1 直接增强**流体云卡片**

| 字段 | 含义 | 现在丢在哪 | 要动哪里（描述） | 风险 |
|---|---|---|---|---|
| `title` | 任务标题 | 顶层 `tasks` 从不被遍历（`ChatViewModel.kt:450-453` 只在工作区元素内找） | 让投影先吃 `result.tasks`，再把任务按 `workspacePath`/`workspaceIdentity` 分组挂到工作区上 | 低 |
| `provider` | 哪个 agent CLI 在跑 | 同上 | 同上（**注意枚举本代只剩 `glm`**） | 低 |
| `displayStatus` / `hasBackgroundWork` | 运行态 / 后台有活 | 同上 | 同上；`hasBackgroundWork` 是 true-only 布尔 | 低 |
| `pinned` / `archived` | 置顶 / 归档成员资格 | 同上 | 同上；`archived` 建议直接当过滤位 | 低；**true-only 语义**（缺省=false） |
| `unreadAt` | 有未读（可当布尔） | 同上 | 只读作布尔；要"标已读"还得配 `setTaskUnread` 带 `expectedUnreadAt` | 中：脱离 CAS 调用无独立意义 |
| `createdAt` / `updatedAt` | 时间线 | 同上 | 排序键或卡片副标题 | 低；**桌面时钟与设备时钟可能有偏差，按差值用** |
| `workflowActivity` | 工作流 phase 进度（本代新增） | 同上 | 同上；`runs[].phases[].status` 是 4 值枚举，可直接映射成徽标 | 低（形状较深，`runs` 最多 4 条） |
| `workspaces[].label` | 工作区可读名 | 投影读的是 `name`/`title` | 键名改成 `label`（**不是键**，别拿来匹配） | 低 |
| `workspaces[].workspacePath` + `workspaceIdentity` | **工作区键** | 投影键门要求 `key`/`workspaceKey`/`id` | 键改成 `workspaceIdentity?.trim() \|\| workspacePath`（identity 优先，§4①） | 低；这是**必须先做**的一步 |

### 3.2 增强**后台追踪**（判断"到底还在不在跑"）

| 字段 | 含义 | 现在丢在哪 | 要动哪里 | 风险 |
|---|---|---|---|---|
| `displayStatus` + `hasBackgroundWork` | 扁平运行态 / 后台有活 | 见 §3.1 | 卡片与通知都可用 | 低；**这是当前唯一现成的"在跑"数据源**（见下） |
| `taskId` | 任务级 id | 顶层 `tasks` 不可达 | 让发现结果携带任务 id ⟹ **省一次桥内往返**、卡片可直接锚任务 | 低 |
| `updatedAt` | 配对端权威"最后活动" | 未读 | 与设备侧体征泵配合做"任务真的停了吗"判据 | 中（时钟偏差） |
| `windowControlSessionId` | 桌面窗口控制会话 id | 未读（0 命中） | 缓存后用于重连判定"还是不是同一个桌面窗口" | 低；**客户端此前从未用过该字段** |
| `workflowActivity` | 工作流 phase | 未读 | "N 个 agent 在跑第 M 阶段" | 低 |

> **重要**：旧基线的运行态来源（桥内 controller 订阅 `controller/tasks-index` → `ControllerTasksState.kt` /
> `TaskStore.runningTaskRefs()`）**在当前工作树里已不存在**：
> `grep -rn "tasks-index\|controller/tasks\|liveStatus\|displayStatus" rust/crates android/app/src` = **0 命中**。
> ⟹ 当前客户端**没有任何任务运行态数据源**。bootstrap 的 `displayStatus` 是目前唯一现成的替代品。

### 3.3 增强**多工作区覆盖**

| 字段 | 含义 | 现在丢在哪 | 要动哪里 | 风险 |
|---|---|---|---|---|
| `workspaces[]`（**bootstrap 里的**） | 一次拿到全部工作区的 label/kind/purpose/连接态 | 投影键门不匹配 ⟹ 整份归零（`ChatViewModel.kt:441`） | 改键门（§3.1 最后一行） | 低 |
| `workspacePurpose:'conversation'` | 配对端内置"默认会话"伪工作区 | 未读 | 列表里**滤掉它**（网页正是这么做的） | 低：避免"永远匹配不上的项" |
| `kind` + `connectionState` + `lastConnectionError` | 远程工作区连接态 | `kind`/`lastConnectionError` 卡在键门后；`connectionState` **键名都不对**（投影读 `connected` 布尔） | 按 `kind==='remote'` 决定是否开远程桥；未连的给卡片提示 | 低 |
| `workspaceIdentity` + `remoteSessionId`（工作区级与任务级） | 远程开桥凭据 | 投影里**连词都没有**（0 命中） | **开桥前即可判定"能不能开"**（网页规则 @6,584,266）；也可省一次 sessions-index 等待 | 中：远程工作区在真机占比未知（§6①） |

### 3.4 **零成本增量源**（单列：它不是字段而是通道）

`workspace-list-updated` —— 配对端**主动推送**的工作区/任务增量（无 `requestId`、无 `success`、
`result` 形状与 `workspace-list-response` 完全相同、`tasks` 可选）。
**当前状态：通道已打通、事件已到 Kotlin（`ChatViewModel.kt:354`），但解析器归零**（§2.6）。
网页侧是订阅广播（主包 @6,578,052 分派 → @6,576,0xx 广播 → @6,575,959 回填焦点）。
修好 `WorkspaceProjection` 即可**零新增订阅**获得免费的任务列表刷新源。
⚠️ 配对端是否对移动客户端也推**未验证**（§6③）。

### 3.5 配对瞬间的桌面焦点

`mobileViewState ?? initialViewState` 的 `activeWorkspaceKey/activeTaskId` —— 客户端**已经在发**
`mobile-view-state-update.viewState`（`zemote_client.rs:2462`），而这两个字段正是它的**回执侧**。
可用于：首卡片排序、预开那座桥（省掉配对后的一次往返）、少开无效桥。
⚠️ 真机是否随响应下发**未验证**（§6②）；且**当前客户端不读 `mobileViewState`**（0 命中），
所以即使桌面回了也没有消费点。

### 3.6 若只做一件事：最小落地集

**先修 `WorkspaceProjection` 的两处结构错位**（键门 + `tasks` 归属），再取
**`title` + `provider` + `displayStatus` + `updatedAt` + `label` + `pinned`** ——
六个字段全部来自**已经在收的那一帧**，足以把卡片从"工作区名 + 状态"升级为
"任务标题 + agent + 状态 + 最近活动 + 置顶"。改动面限于
`ChatViewModel.kt:424-470` 一个 object（外加 `WorkspaceItem`/`TaskItem` 两个 data class）。

### 3.7 前置修复（**不修则 §3.1–§3.6 全部落空**）

| # | 缺陷 | 位置 | 现象 |
|---|---|---|---|
| ① | 工作区键门要求 `key`/`workspaceKey`/`id`，真载荷只有 `workspacePath`/`workspaceIdentity` | `ChatViewModel.kt:441` | **每一座工作区被丢弃，列表恒为空** |
| ② | `tasks` 只在工作区元素内查找，而协议放在 `result` 顶层 | `ChatViewModel.kt:450-453` | **16 个任务键结构不可达** |
| ③ | 键名猜测与 schema 不符：`name`/`title`↔`label`、`connected`(布尔)↔`connectionState`(枚举) | `ChatViewModel.kt:445`、`:448` | 即使 ① 修好，这两个字段仍读不到 |

> `ChatViewModel.kt:412-421` 的注释已自认这是"**防御性投影**，不是协议实现"（因为 `docs/FFI.md` §13.5 未冻结）。
> 本节的结论是：该投影对**当前 3.15.3 真载荷**的命中率是 **0**，不是"部分命中"。

---

## 4. 客户端与网页规则的差异（五处不一致）

| # | 位置 | 现象 | 影响 |
|---|---|---|---|
| ① | 工作区键规则 | 网页**五类实现、111 处内联**全是 **identity 优先**（对象形 `U`：`src-AL-_ONNp.js:63,863`，主包以 `ja` 导入 @64,442；内联 111 处命中）。客户端**根本没有这条规则** —— `WorkspaceProjection` 不认 `workspaceIdentity`，Rust `conversation_key()`（`zemote_client.rs:2494`）虽写了 `workspaceIdentity ?? workspacePath` 但那是 **Dart 的 `??`（只判 null）不是 `?.trim()`** | 远程工作区在客户端侧**没有键**；即便补上键门也必须用 `?.trim()\|\|` 而不是 `??`，否则空串 identity 会顶掉 path |
| ② | `Tier2Probe.kt:449-457` 的 `deviceInfo`（**旧安卓壳，已消失**） | 该壳发 **`timeZone`**（大写 Z）+ **缺必填 `updatedAt`** ⟹ 该帧整体不合法 | **该缺陷已被 Rust 移植继承一半**：Rust `device_info.rs:98` 的 `DeviceInfo` 只有 `{platform, version, name}`，**同样缺 `updatedAt`**（`timeZone` 拼写问题已消失）。后果链见 14 篇 §6.5 |
| ③ | `mobile-view-state-update` 的 `deviceInfo` | Rust `zemote_client.rs:2462` `mobile_view_state_update_payload()` 把 `DeviceInfo`（3 键）直接 `serde_json::to_value` 进 `deviceInfo`；`viewState` 侧合规（带 `updatedAt`） | **同 ②**：整帧被接收侧丢弃 ⟹ 桌面永远不更新 `mobileViewState`。**`device_info.rs:103` 的注释把"线上字段名已冻结，不可改：platform/version/name"写成了协议事实**，实际来源只是 Dart 客户端自己的内联 map（`zemote_client.dart:396-401`）——**客户端发什么 ≠ schema 要求什么** |
| ④ | bootstrap 的响应校验 | 客户端对收到的帧**不做任何 schema 校验**（Rust 侧按 `zcode_type` 分派后直接取字段，`zemote_client.rs:1023`） | 与网页端相反：网页端过不了 `uS.safeParse` 就整帧丢弃（主包 @6,528,394）；客户端是"能取到就用、取不到就退化"。**这对接收侧是优点（不会因为桌面多发一个键就丢帧），但对发送侧是缺点（不会因为自己少发一个必填键就报警）** |
| ⑤ | `bootstrap` 的 `requestId` 回配 | 客户端**有**回配（`zemote_client.rs:1233`/`:1254`，注释 `:205-206` 说明"响应不保证回显 requestId，所以每个挂起项试每个载荷"） | 与旧安卓壳（从不回配）不同，这一条**当前客户端是对的** |

---

## 5. 量化结论：这个包有多大 · 客户端实际用了几个字段

### 5.1 键位清单（schema 侧，可逐条复算）

| 层 | 键数 | 键名 |
|---|---|---|
| 信封 | 4 | `zcode_type`,`requestId`,`success`,`result` |
| `result` 顶层 `sS` | **6** | `windowControlSessionId`,`desktopAppVersion`,`workspaces`,`tasks`,`initialViewState`,`mobileViewState` |
| 视图状态 `aS` | 3 | `activeWorkspaceKey`,`activeTaskId`,`updatedAt` |
| `workspaces[i]` `tS` | 8 | `workspacePath`,`workspaceIdentity`,`remoteSessionId`,`label`,`workspacePurpose`,`kind`,`connectionState`,`lastConnectionError` |
| `tasks[i]` `nS` | **16** | `taskId`,`title`,`workspacePath`,`workspaceIdentity`,`remoteSessionId`,`workspaceLabel`,`workspaceKind`,`createdAt`,`updatedAt`,`provider`,`unreadAt`,`displayStatus`,`hasBackgroundWork`,`workflowActivity`,`pinned`,`archived` |

**键位（槽位）总数 = 37**（旧基线 34）；其中 `workspacePath`/`workspaceIdentity`/`remoteSessionId`/`updatedAt`
在多层重复出现，**去重后的键名总数 = 33**（旧基线 30）。

### 5.2 客户端实际读到的

| 口径 | 数字 |
|---|---|
| 从收到的帧里读进逻辑的**元素层叶子字段** | **0 个**（`WorkspaceProjection` 键门不匹配 ⟹ 整份归零；见 §3.7①） |
| **元素层**（`workspaces[]`+`tasks[]` = **24 键位 / 21 去重键名**） | **0/24 = 0%** |
| **`tasks[i]` 元素**（16 键位） | **0/16 = 0%**，且**结构上不可达**（`tasks` 在 `result` 顶层，投影只在工作区元素内找） |
| **`workspaces[i]` 元素**（8 键位） | **0/8**；若只修键门（不动其它）：**3/8**（`workspacePath`→`path`、`kind`→`kind`、`lastConnectionError`→`lastError`） |
| 信封层 | **2 ✅ + 1 ◐**：`zcode_type` ✅、`requestId` ✅、`result` ✅（只取 `workspaces` 一个键）、`success` ◐（只在 recovery 路径读） |
| `result` 顶层 6 键 | 只有 `workspaces` 被碰过 ⟹ **1/6**；`tasks`/`initialViewState`/`mobileViewState`/`desktopAppVersion`/`windowControlSessionId` **全 0** |
| **全帧去重键名**（33 个，含信封） | 有读者的 **3 个 ≈ 9%**（`zcode_type`、`requestId`、`result`）；**元素层字段 0 个** |
| 帧内**任何代码路径（含日志）都没碰过**的键名 | **30 个**：`success`、`windowControlSessionId`、`desktopAppVersion`、`tasks`、`initialViewState`、`mobileViewState`、`activeWorkspaceKey`、`activeTaskId`、`workspacePath`、`workspaceIdentity`、`remoteSessionId`、`label`、`workspacePurpose`、`kind`、`connectionState`、`lastConnectionError`、`taskId`、`title`、`workspaceLabel`、`workspaceKind`、`createdAt`、`updatedAt`、`provider`、`unreadAt`、`displayStatus`、`hasBackgroundWork`、`workflowActivity`、`pinned`、`archived`（口径：**从收到的帧里读取**；客户端自己往外发的同名键不算——例如 `zemote_client.rs:2469` 发的 `viewState.activeWorkspaceKey`） |
| 原文留存 | **不落盘**（Rust 侧不写原始 JSON；Kotlin 侧只留投影后的 data class）。旧基线的"两行日志 + 一个键列表"已不存在 |

### 5.3 体量与通道

| 项 | 数字 |
|---|---|
| 单帧体量 | 旧壳真机 **27,832 字符**；旧壳注释记「约 24 KB」/「24–25 KB」—— **未复核（旧基线，外部输入）** |
| 内容规模 | 91 个任务 / 7 个工作区（另一时段 94 任务）—— **未复核（旧基线，外部输入）** |
| 请求→响应 | 约 300–400 ms —— **未复核（旧基线，外部输入）** |
| 该帧为客户端带来的行为 | **当前：一个恒为空的 `List<WorkspaceItem>`**（`ChatViewModel.kt:306`） |
| relay 消息类型覆盖率 | 19 个唯一类型里客户端有引用 **14** 种，**5 种零命中**（§2.7） |

**一句话**：这个包是"全工作区任务状态的全量快照"，**当前客户端读进逻辑的元素层字段是 0 个**；
剩下 **24 个元素键位**（8 个工作区键 + 16 个任务键）在任何代码路径上都没有读者。
根因不是"没写解析"，而是**键门与 `tasks` 归属两处结构错位**（§3.7）。

---

## 6. 盲区（判不出 + 下一步怎么判出）

| # | 盲区 | 为什么判不出 | 下一步（都是只读手段） |
|---|---|---|---|
| ① | 真机 `tasks[i]` / `workspaces[i]` 的实际键集与取值分布 | 当前客户端**不做键集摘要、不落原文**（旧壳的 `shapeOf` 探针已随旧壳消失） | 抓一轮 relay 原文（`result` 内可能含远程会话凭据，**只落键名与计数**）；或在 Rust 侧加"键→出现计数"的调试摘要（**需改码，本轮未动**） |
| ② | `result.workspaces[]` 真机形状、`initialViewState`/`mobileViewState` 是否随响应下发 | 客户端不读、不落这两项 | 同上一次抓包可一并判定 |
| ③ | `workspace-list-updated` 是否会推给移动客户端 | 客户端**收到了会走 `dispatch_payload`**（`zemote_client.rs:1031`），但**无法从代码判断配对端是否真的推**；真机也没落过该事件 | 抓包观察；或配对端日志 |
| ④ | `result` 顶层除已列 6 键外是否还有键 | 客户端不落原文，无样本可对账 | 只能抓包；或接受"以 schema 为准"（schema 非 strict，多出的键会被**网页侧** strip；客户端**不校验**，多出的键会被忽略） |
| ⑤ | **客户端 `deviceInfo` 缺 `updatedAt` 在真机上的实际后果** | 只能从 schema 必填 + 接收侧闸门语义推出"整帧被丢"（14 篇 §6.5），**无线上样本** | 桌面端日志里找 `[web-remote-control] invalid external relay payload dropped` 且 `zcode_type=mobile-view-state-update`；或看桌面 UI 的 `mobileDeviceInfo` 是否为空 |
| ⑥ | 修好 `WorkspaceProjection` 后界面能否正确渲染 | 未改码、未跑真机 | 单测 + 一次真机配对（`docs/FFI.md` §13.5 冻结后应把投影搬进引擎层） |

---

## 7. 给鸿蒙侧的移植提示（字段级接口清单）

1. **这个包就是"免点进对话即得全量工作区任务状态"的官方数据源**。最小实现：配对成功后**立即发**
   `bootstrap-request`（`{zcode_type, requestId}`，requestId 需 ≤256 且 `[A-Za-z0-9._~-]`），
   然后**先按 schema 的键名解析**（`workspaces[i]` 8 键、`tasks[i]` 16 键，见 14 篇 §4/§5），
   **不要**像当前 Kotlin 投影那样猜 `key`/`name`/`connected`（§3.7）。
2. **`tasks` 在 `result` 顶层、与 `workspaces` 平级**（`sS`），**不是**嵌在工作区元素里。
   聚合方式：用工作区键把 `tasks[i]` 分到 `workspaces[i]` 上（任务自带 `workspacePath`/`workspaceIdentity`）。
3. **按工作区键聚合**：键规则 = `workspaceIdentity?.trim() || workspacePath`（identity 优先，**顺序不能反**，
   且必须是 `?.trim()||` 而不是 `??`）；`workspaceLabel`/`label` **只是显示名，不是键**。
4. **两个运行态域别混**：`displayStatus`（4 值，扁平，本族）+ `hasBackgroundWork`（true-only 布尔）是本族的；
   `liveStatus`（含 `waiting`）/`activity.phase`（含 `prewarming`/`draft`）属于**桥内 controller 订阅域**，
   **不在本族 schema 里**，别指望在扁平载荷上命中。
5. **安全**：原文可能带远程会话凭据（`remoteSessionId`/`workspaceIdentity`），**只落键名与摘要**。
6. **同族三帧一个解析器**：`bootstrap-response`（配对时一次全量）、`workspace-list-response`（请求应答）、
   `workspace-list-updated`（配对端主动推）共用同一套元素 schema，`tasks` 只有"是否可选"的差别。
7. **可选位都要按可选处理**：`pinned`/`archived`/`hasBackgroundWork` 只在 true 时出现；
   `displayStatus` 缺省视同 `idle`；`initialViewState`/`mobileViewState`/`tasks`(list 路) 都可能整个不在。
8. **`unreadAt` 是 CAS 令牌**：只读作"有未读"布尔；要标已读必须走 `setTaskUnread{unread:false, expectedUnreadAt}`。
9. **`deviceInfo` 别照抄 Dart / Rust 移植**（§4②③）：要发小写 `timezone`，并**必须带 `updatedAt`**
   （`z.number()`，无 int/非负约束）；`platform`/`version`/`name` 三个必填键 trim 后不能为空。
10. **`provider` 枚举本代只剩 `glm`** —— 别按旧基线的 5 值做映射表。

---

## 8. 出处

| 你要什么 | 去哪 |
|---|---|
| 字段的逐字 schema、枚举全量值、字节偏移、zod 别名反解、键规则的全部实现点、`fragment`/`deltas`/近亲形状、`deviceInfo` 缺 `updatedAt` 的完整证据链 | [`14-remote-v4-bootstrap载荷-完整字段表.md`](14-remote-v4-bootstrap载荷-完整字段表.md) |
| 客户端每一处的 `文件:行` | `android/app/src/main/kotlin/app/zemote/ui/chat/ChatViewModel.kt`（投影 @412-470、bootstrap @303-306、增量 @354-358）、`android/app/src/main/kotlin/app/zemote/engine/ZemoteEngine.kt`（@230-270）、`android/app/src/main/kotlin/app/zemote/ui/chat/ChatScreen.kt`（@92-101） |
| Rust 核心的对应实现 | `rust/crates/protocol/src/zemote_client.rs`（`zcode_type` 常量 @98-127、`dispatch_payload` @1023、`bootstrap` @1285、`mobile_view_state_update_payload` @2462）、`rust/crates/protocol/src/device_info.rs`（`DeviceInfo` @98-127）、`rust/crates/ffi/src/session.rs`（`CallCommand::Bootstrap` @229、`snapshot` @147-200） |
| Dart 参考实现（冻结版） | `Reference_code/lib/protocol/zemote_client.dart`（`sendMobileViewState` @389-403、`bootstrap` @676 附近）、`device_info.dart` |
| relay 协议全目录（帧、时序、桥、RPC） | [`16-remote-v4-relay协议目录.md`](16-remote-v4-relay协议目录.md) |
| 网页端整体架构与模块坐标 | [`15-remote-v4-网页架构与模块地图.md`](15-remote-v4-网页架构与模块地图.md) |

> ⚠️ 本目录被 gitignore（见 `Docs/README.md` 顶部），`ripgrep`/`grep -r` 会静默跳过 `Docs/`，读本目录请用 `ls` + 直接打开。
