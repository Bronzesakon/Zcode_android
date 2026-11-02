# 01 · 端到端实现审计：Android/Rust 对照 3.17.1 已确认行为

> 只读审计，未修改任何源码。对照基准：`round1/*`、`round2/01`（§2-§5、§7）、
> `round2/02`（§2-§5）、`round2/03`（§1-§6、§8）。
>
> **⚠ 审计期间 Kotlin 源码被并发修改**（`ChatViewModel.kt` 17:04 / 17:12、`HomeViewModel.kt` 17:06
> 各改过；其中「删除任务后 close 整条工作区对话」被移除、`ensurePreviews` 改为**跳过所有非 tab 工作区**）。
> 本报告结论以最后一次读取为准（见下表哈希）；**函数名是稳定锚点**，行号若漂移请按函数名复核。
>
> | 文件 | sha256 前 16 位 | 行数 |
> |---|---|---|
> | `HomeViewModel.kt` | `7a2cfdbb7f134557` | 1028 |
> | `ChatViewModel.kt` | `09b6e608f78145e9` | 1910 |
> | `ConversationRegistry.kt` | `d5198de8c2c05731` | 198 |
> | `ChatOps.kt` | `220e32a858c4171f` | 154 |
> | `AppNavHost.kt` | `38f515b1da904f04` | 456 |
> | `AppContainer.kt` | `032922a6576ffb76` | 397 |
> | `ZemoteEngine.kt` | `1272fa08f54e0e8d` | 917 |
> | `NotificationCoordinator.kt` | `bdd1d0d81d4e0195` | 281 |
>
> Rust 侧本轮只读、未被改动：`crates/ffi/src/{codec,session}.rs`、
> `crates/protocol/src/{zemote_client,conversation_transport,conversation}.rs`。
>
> 严重度口径：**P0** = 污染用户数据或让主流程不可用；**P1** = 功能性缺陷/崩溃/长时间阻塞，
> 用户可感知且无自愈；**P2** = 保真度、体验或可维护性缺口。

---

## 1. P0

### P0-1 对话事件不分 session/key，串流会污染另一条对话并写进它的持久缓存

**结论**：`ChatViewModel.onEngineEvent` 对 `ConversationEvent` 只按 `type` 分发，从不校验
`event.session` / `event.key`。进程里同时存在两个 `ChatViewModel` 时（返回栈叠两个
`Route.Chat`：首页点任务 A → 再点通知打开任务 B；`AppNavHost.kt:96-97` 的 `NavEntryStores`
会保留栈内全部条目，两个 ViewModel 的 `viewModelScope` 都活着），B 的行增量会被 A 合并进
自己的 `rows`，`config/queue/pendingInteractions` 被 B 覆盖，随后 A 的 `schedulePersist`
把混合行写进 **A 的缓存文件**（键是 A 的 workspaceKey + taskId），重启后仍显示错误历史。

**证据**
- `ChatViewModel.kt:1706-1741`（`is EngineEvent.ConversationEvent ->`）：无 `event.session` 判定；
  `"rowsUpdated" -> applyRowsUpdated(event.event)`（:1727）、`attachmentProgress`（:1728-1736）、
  `stateChanged`/`sessionsIndexUpdated`（:1737-1741）同样不过滤。
- `ChatViewModel.kt:1479-1524`（`applyRowsUpdated`）：把 `payload.snapshot` 窗口与 deltas 直接归约进 `_state.rows`。
- `ChatViewModel.kt:1549-1561`（`applyConversationFields`）：整体覆盖 `isRunning/config/queue`。
- `ChatViewModel.kt:1639-1652`（`schedulePersist`）→ `ChatCache.saveConversation(workspaceKey, taskId, rows, …)`（`ChatCache.kt:72`）。
- 事件源是共享旁路流：`ZemoteEngine.kt:151-160`、`EnginePort.kt:269` / `:486-491`。

**严重度**：P0（持久化数据被写错）。

**最小修复**：在 `ConversationEvent` 分支加门禁：
```kotlin
val mine = event.session == _state.value.bridgeSession && event.key == _state.value.conversationKey
if (!mine) return@onEngineEvent
```
三个入口（`applyRowsUpdated` / `attachmentProgress` / `refreshConversationState` 的触发）都走它。

---

## 2. P1

### P1-1 注册表一把全局 Mutex 包住三次长 await：13 工作区串行，且阻塞对话页

**结论**：`ConversationRegistry.ensure` 在同一把 mutex 内完成 `openBridge`(宿主上限 35s) →
`waitHealthy`(50s) → `conversationOpen` → `conversationCall(subscribeSessionsIndex)`(65s)，
单工作区最坏 ≈150s；首页对 bootstrap 的每个工作区串行跑这一套，期间对话页的
`ensureRouteBridge` / `sendText` / `mutateTask` 全部排队。首次「点任务 → 发消息」可能等数分钟；
13 个项目列表本身也只有在首个工作区 ensure 成功后才拉（`loadProjectTree` 在锁内）。

**证据**
- `ConversationRegistry.kt:74-116`（`mutex.withLock { openBridge(:88) … waitHealthy(:94) …
  conversationOpen(:97) … conversationCall(subscribe, timeoutSecs=60)(:102-108) … }`，全程持锁）。
- `ZemoteEngine.kt:699-703`（宿主兜底 = 声明值 + 5s）、`:333-341`（waitHealthy 默认 45s）、
  `:292-320`（openBridge 固定 30s）。
- `HomeViewModel.kt:411`（对全部 bootstrap 工作区逐个 `ensurePreviews`）、`:456-527`
  （`previewSetup.withLock` → `registry.ensure` → `loadProjectTree`(:504) 也在锁内）。

**严重度**：P1（可用性；首屏与发送被串行放大）。

**最小修复**：`ensure` 改成 **per-key 互斥**（`Map<String, Deferred<Handle>>`），只对同一 key 串行；
`helloConversationV4` 的单连接语义用全局信号量(1) 只包 `conversationOpen + subscribe` 这一小段；
`loadProjectTree` 移出 `previewSetup` 锁，放进独立后台任务。

### P1-2 Chat 收到 `workspace-list-*` 推送会丢掉路由工作区，项目树任务页的 mutation 全部变 no-op

**结论**：`ChatViewModel` 处理 `WorkspaceListUpdated` 用 `WorkspaceProjection.merge`，该函数只保留
`updated` 里存在的 key；项目树工作区不在 bootstrap/workspace-list 中，一收到整表推送就从
`workspaces` 消失 → `selectedWorkspace == null`。

**证据**
- `ChatViewModel.kt:1684-1692`：`it.copy(workspaces = WorkspaceProjection.merge(it.workspaces, snapshot.workspaces))`。
- `ChatViewModel.kt:1841-1847`（`merge`）：`return updated.map { … }`，未出现在 `updated` 的条目被丢弃。
  对照 `HomeViewModel.kt:523-537`（有 `projectTreeWorkspaces` 补偿）+ `:529`。
- `ChatViewModel.kt:373-397` 只在进页时补一次 `routeWorkspace`。
- 后果链：`ChatViewModel.kt:1249-1257`（`mutateTask` 的 `selectedWorkspace ?: return`）→ 置顶/归档/删除/改名静默无效；
  `:1436-1441`（无 `currentWorkspace` 就不合并任务）；`:1630-1636`（`notifyAttention` 直接 return）。

**严重度**：P1。

**最小修复**：`onEngineEvent(WorkspaceListUpdated)` 里补回路由工作区（key 不在快照里就
`+ routeWorkspace`），或给 `merge` 加一个 `keep: Set<String>` 参数。

### P1-3 `mergeSessionsIndex` 在挂起点上遍历可变 `HashMap`，存在 ConcurrentModificationException

**结论**：`for ((workspaceKey, pair) in previewSubscriptions)` 的循环体会挂起
（`engine.conversationSnapshot`），而 `ensurePreviews` 在**另一个协程**里对同一个
`mutableMapOf` 写入。写入发生在读协程的挂起点之间，下一次 `hasNext()` 抛 CME；
异常发生在 `viewModelScope.launch { mergeSessionsIndex() }` 内且未捕获 → 崩溃/列表停更。

**证据**
- `HomeViewModel.kt:778`（遍历）、`:781`（挂起调用）、`:510`（另一协程写入）、
  `:936-950`（事件分支 `viewModelScope.launch { mergeSessionsIndex() }`，无 try/catch）、`:166`（声明）。

**严重度**：P1（崩溃/停更）。

**最小修复**：迭代前 `val pairs = previewSubscriptions.toList()`；写入点改为「构造新 map 再整体提交」。

### P1-4 通知链路拿不到任务：运行卡片、完成/失败提醒实际永不触发

**结论**：`NotificationCoordinator.syncTasks` 的唯一数据源是 `workspace-list-*` 投影出来的
`workspaces[].tasks`，而 `WorkspaceProjection.toWorkspace` 从不填 tasks（bootstrap /
workspace-list 没有顶层 tasks）。于是 `running` 恒空、`wasRunning` 恒空、「运行 → 完成」
迁移永不成立，前台服务正文恒为 0 条；`refreshPreviews` 捞到的 `lastAssistantPreview` 无消费者。

**证据**
- `NotificationCoordinator.kt:146-149`（只吃 `WorkspaceListUpdated`）、`:163-243`（`syncTasks` 遍历 `workspace.tasks`）。
- `ChatViewModel.kt:1873-1889`（`toWorkspace` 只映射 8 键，`tasks` 走默认空）、`:1825-1827`（注释：没有顶层 tasks）。
- `NotificationCoordinator.kt:87-109`（`onAttention` 只由 Chat 页调用 → 后台任务不会提醒）。

**严重度**：P1（设置里的通知开关全部无效）。

**最小修复**：`NotificationCoordinator` 的数据源换成 sessions-index（每工作区订阅一次），
或由 `AppContainer` 单向注入 Home 合并后的 `StateFlow<List<WorkspaceItem>>`；
运行态取 `phase ∈ {running, prewarming}`，完成态用 phase 迁移。

### P1-5 `engine.events` 是 `tryEmit` 的共享旁路流，三个消费者共用一个 512 缓冲，慢消费者丢协议帧

**结论**：`rowsUpdated` 的 deltas 被丢 → 行窗口**永久缺行**（只有下一次 snapshot 才复原）；
`stateChanged`/`sessionsIndexUpdated` 被丢 → 会话状态/任务活动停更。当前 3 个消费者
（Home/Chat/Notification），事件流还被转接两次（引擎 → `EnginePort` → 各 VM，两次都 `tryEmit`）。
Chat 的收集器在 `rowsUpdated` 上同步归约，是最慢的一环。

**证据**
- `ZemoteEngine.kt:151-160`（`extraBufferCapacity = 512` + `tryEmit`，注释明说「缓冲满了会丢事件，
  不要把关键逻辑建立在它上面」）。
- `EnginePort.kt:269` / `:486-491`（第二层 `tryEmit`）。
- 消费者：`HomeViewModel.kt:933-958`、`ChatViewModel.kt:1663/1682`、`NotificationCoordinator.kt:67-80`
  （后者对每个 `stateChanged` 再发一次 `conversationSnapshot`）。

**严重度**：P1（流式期间缺行/停更，且不可观测）。

**最小修复**：Chat 按 P0-1 过滤后再消费；`tryEmit` 返回 false 时计数并触发一次
`refreshConversationState` + `rowsRange` 对账；或把关键消费者换成独立 `Channel(buffer(UNLIMITED))`。

### P1-6 非 tab 项目不再订阅 sessions-index：没有未读、没有运行态、没有新任务（17:06 的改动引入）

**结论**：`ensurePreviews` 现在对 `!workspace.bridgeable` 直接 `return@withLock`，且不再传
`project` intent。项目树里的全部项目（13 个项目里除当前 tab 之外的那些）因此**没有任何实时源**：
`sessionsIndexUpdated` 永远不来，`previewSubscriptions` 里没有它们，`mergeSessionsIndex`
永远不碰它们的 tasks。结果：未读点、运行中/后台工作标记、新建任务、桌面端改名/置顶/归档/删除
在这类项目上全部不可见，直到引擎重建或用户下拉刷新（`readTree` 只跑一次，见 P2-7）。

**证据**
- `HomeViewModel.kt:461-464`：`if (!workspace.bridgeable) return@withLock`（注释解释：避免
  「展开一下」就在桌面端凭空 prepare 一个草稿工作区）。
- `HomeViewModel.kt:472-476`：`registry.ensure(workspace.key, taskId = null, conversationScope = scope)`
  —— 已无 `project` 参数；`return` 之后这段对非 tab 项目不再执行。
- `HomeViewModel.kt:504`（`loadProjectTree` 只在成功订阅后跑）、`:778`（合并只覆盖 `previewSubscriptions`）。

**严重度**：P1（大多数项目没有未读/运行态，是用户可见的功能缺失；与 round2 03 R4 的结论方向相反，
但出发点是避免脏副作用，需要产品决策而不是简单回滚）。

**最小修复**（三选一，按代价从低到高）：
1. 只对**已确认有 projectId 的**工作区带 `project` intent 订阅（原实现），把「凭空开草稿」限制在
   `projectId == null` 的 legacy 项目上（那才是 `prepareSession` 会建随机目录的场景）；
2. 不订阅 sessions-index，但把「活动/未读」改为**按需**：进入工作区时、或 `AppContainer` 的
   前台周期里对 `readTree` 做节流刷新（≥30s），用树字段驱动未读/运行态；
3. 为这类项目单独开一条 `subscribeSessionsIndex`（用任务级 `taskHostId` 桥）只在对话页在场时存在。

---

## 3. P2

| # | 结论 | 证据 | 最小修复 |
|---|---|---|---|
| P2-1 | 新建对话后 `refreshConversationState` 常空转：`conversationKey` 为 null 就直接 return；新会话 `config` 还没到，首条消息可能不带 `modelSelection`（落到工作区默认模型） | `ChatViewModel.kt:766`、`:1423-1425`（`?: return`）、`:851-875`（`putSendSubmission`） | `createConversation` 先把 `handle.key` 写入 `_state.conversationKey`；或等首帧 `stateChanged`（≤300ms）再发首条 |
| P2-2 | `planEnabled` 总是发送（含 true）：Rust `ClientHello` 不声明 `capabilities`，web 在同语义下会报 `proto.independentPlanUnsupported`；桌面是否拒绝 UNVERIFIED | `ChatViewModel.kt:854-858`；round2 02 §2.3 | 先只发 `mode + modelSelection`，`planEnabled` 加开关 |
| P2-3 | 宿主兜底超时未计入 flow 内 `waitHealthy(45s)`：仍会 95s 判超时而核心要 135s → 降级态假失败 + 孤儿会话 | `ZemoteEngine.kt:699-703`；round2 02 §4.3 | `conversation_call` 兜底改 `declared + 45s + 5s`；超时文案注明「可能已在桌面创建」 |
| P2-4 | legacy 工作区（`projectId==null && !bridgeable`）仍给「新建对话」入口；进入后开桥必然失败（无可用 intent），只在点发送时被拦 | `AppNavHost.kt:189-193`；`HomeViewModel.kt:461-464`（预览已跳过）；`ChatViewModel.kt:708-721`（发送前拦截） | 首页对这种工作区隐藏「新建对话」，或进页时跳过开桥 |
| P2-5 | 注册表按请求 key 缓存、忽略 taskId/project：同工作区第二次打开**另一个任务**不重开桥，`initialTaskId` 保持首个；`sendMobileViewState` 在注册表路径已改用 `handle.effectiveWorkspaceKey`（`ChatViewModel.kt:1115`），仅非注册表兜底仍用请求 key（`:1124`） | `ConversationRegistry.kt:86`、`:97` | `Handle` 记 taskId/project 签名，不一致时重开桥 |
| P2-6 | `EngineChatOps` 只 `handleFor`、不校验 `sessionSerial`：引擎重建后旧句柄仍被返回，非建桥类操作（stop/rowsRange/resolveInteraction/attachmentRead/switchModel）持续 `invalidState` | `ChatOps.kt:122-133`；`ConversationRegistry.kt:81-86`（清理只在 ensure 内） | `handleFor` 加 serial 校验，不符返回 null 并清缓存 |
| P2-7 | `readTree` 只跑一次、只对 `online` host；桌面端改名/置顶/删除本地永不可见（索引不含 pinned/archived，删除只表现为「索引里没有」而被保留） | `HomeViewModel.kt:504`、`:578-603`、`:773-825`；round2 03 §1.3/§6 | `ready` 后允许「索引与树都没有 ⇒ 移除」；或 `sessionsIndexUpdated` 后节流（≥30s）重跑 readTree |
| P2-8 | mutation 应答被丢弃；失败回滚用整表快照覆盖，会抹掉期间的索引合并（丢新任务/活动） | `HomeViewModel.kt:844`、`:879-887`；`ChatViewModel.kt:1257`、`:1297-1303` | 回滚只回滚该 taskId 的字段；或用应答 TaskMeta 写回 |
| P2-9 | title 未知时仍发送 `title: ""`，若对端持久化摘要会清空标题（round2 03 §8.2 未证） | `HomeViewModel.kt:860`、`ChatViewModel.kt:1284` | title 为空时不写该键 |
| P2-10 | 从通知进入任务不 markRead（只有首页任务行会清未读） | `AppNavHost.kt:104-119` | 通知 target 补 workspaceKey，进入后走 `onOpenTask` 同样的 `markRead` |
| P2-11 | `state.workspaceId` 跨工作区不清空，可能来自上一个工作区（第三兜底） | `ChatViewModel.kt:1433`、`:1466`、`:1048-1060`（openWorkspaceInternal 不置空） | 切工作区时置空，或从 `createConversation` 候选链去掉 |
| P2-12 | 每个 `stateChanged`/`sessionsIndexUpdated` 都新起协程读全量快照，无去抖；Home 每个事件遍历全部工作区各读一次 | `ChatViewModel.kt:1737-1741`；`HomeViewModel.kt:778-796` | 去抖 + 单飞（`conflate`）；Home 只读发生变化的工作区 |
| P2-13 | 标题合并方向与 web `cvt` 相反：索引标题非空即覆盖树标题，忽略「本地改名（tree titleOverridden）但 index titleSource≠custom」 | `HomeViewModel.kt:809-815` | 改 `index.titleSource=="custom" ? index.title : (tree.title ?: index.title)` |
| P2-14 | `ConversationRegistry.close/closeAll` 已无任何调用点（删除任务的 close 已被移除）；`close()` 仍会把 `conversationClose` 的异常抛给调用方 | `ConversationRegistry.kt:176-197`；全仓 grep 仅定义 | 若要保留，把异常降级为日志并返回 Boolean；否则删掉 |
| P2-15 | `HomeViewModel.unread(Boolean)` 无调用点（死代码） | `HomeViewModel.kt:891-893` | 删除 |

---

## 4. 六条链路逐条结论

### 4.1 首页加载（bootstrap → ensurePreviews → loadProjectTree → merge）

- **13 个项目能否全部出现**：能，但前置条件是「首个工作区 ensure 成功」（`HomeViewModel.kt:504`
  才 `loadProjectTree`），受 P1-1 串行队列影响；且只对 `connectionStatus=="online"` 的 host
  发 readTree（`:590-603`，web `Mbt` 是全部 host + allSettled，round2 01 §6.4），
  `connecting` 的 host 项目要等下拉刷新（P2-7）。
- **项目树的实时性**：非 tab 项目已完全不订阅 sessions-index（`HomeViewModel.kt:461-464`，
  见 P1-6），因此列表能出现、但活动/未读/新任务不再更新。
- **任务被错误丢弃/覆盖**：`mergeTreeTasks`（`:743-755`）以 readTree 为权威并删除树里没有的旧任务；
  `mergeSessionsIndex`（`:773-825`）以「树派生的 workspace.tasks」为基底、索引只补活动字段、
  索引缺席的任务保留 —— round2 03 R3 的结构性偏差已修。
- **pinned/archived/unreadAt 方向**：正确。三者只来自 readTree（`:672-674`），`unreadAt` 允许为 null
  覆盖旧值（`:745-753`），`markRead` 本地清零（`:256-267`）。残留：索引侧标题覆盖方向相反（P2-13）；
  非 tab 项目因 P1-6 拿不到索引，unread 只能等下次 readTree。
- **projectId/hostId/identity**：`projectKeyById["hostId\0projectId"]`（`:636-638`）、任务 groupKey
  （`:663-665`）；远端 identity 优先取任务行、否则 `hostId + ":" + gy(path)`（`:633-635`、`:554-558`，
  与桌面 `_se/Wo` 同式）；`hostId` 原样使用（与 web 一致）。
- **多根项目归组**：任务按 `projectId` 归到主根的 WorkspaceItem（`:663-665`），打开时用任务自己的
  `workspaceKey/Path/Identity`（`AppNavHost.kt:161-177`），符合 round2 01 §5.4。

### 4.2 打开任务（Home.onOpenTask → Route.Chat → ensureRouteBridge → openBridge）

- **字段组合**：三种场景全部合法且与 round2 01 §7.2 一致 —— 非 tab 项目 `taskId + taskHostId`；
  多根项目 `workspaceKey = task.workspaceIdentity?.trim() ?: task.workspacePath`；远端项目
  `taskHostId` 为远端 host id；`project` 只在 `taskId == null` 时给（`ChatViewModel.kt:825-843`）。
  `project` 与 `taskId` 永不同时出现，`taskHostId` 不单独出现。✅
- **needsPrepare**：`!workspace.bridgeable || taskKey != workspace.key`（`AppNavHost.kt:171`）正确；
  tab 内普通任务不带 hostId（走 tab 查找），非 tab / 非主根才带。✅
- **conversation scope 是否用权威 key**：是。`ConversationRegistry` 从 `snapshot.bridges[]` 取 ready
  的 `workspaceKey`，再用 `effectiveScope` 重算 path/identity（`ConversationRegistry.kt:97-98`、`:128-161`）。✅
- **routeWorkspace/tasks 够不够支撑 mutateTask**：够（骨架 task 保证 `selectedTask != null`），
  但会被 P1-2 的整表推送打断；且路由 skeleton 的 title 为空（P2-9）。P2-4 的 legacy 场景开桥必然失败。

### 4.3 新建对话（createSession → subscribe → snapshot → sendText → 失败回填）

- **时序**：正确。`createSession` → `subscribeConversation`(`conversationCallNoWait`) →
  `loadModelView` → `refreshConversationState` → `uploadAttachments` → `sendText`
  （`ChatViewModel.kt:726-782`），与 round2 02 §3.2「先订阅再发首条」一致。✅
- **workspaceId**：`handle.effectiveWorkspaceKey`（bridge-ready 权威值），正确（`:726-738`）。✅
- **timeout**：显式 `timeoutSecs: 90`（`:735`）+ 宿主认 `args.timeoutSecs`（`ZemoteEngine.kt:706-710`）
  → 95s vs core 90s，「35s 假超时」已修；降级态仍可能假超时（P2-3）。
- **订阅是否真的发出**：是，走注册表句柄（`:805-817`），不依赖 `state.bridgeSession` 猜测。✅
- **文本是否丢**：不丢。失败写 `draftRestore`（`:785-792`、`:463-468`），界面
  `LaunchedEffect(state.draftRestore)` 回填（`ChatScreen.kt:159-162`）；legacy 工作区提前给可行动文案
  （`:708-721`）。残留：P2-1、P2-2。

### 4.4 已读 / 置顶 / 归档 / 删除

- **unreadAt 是否可被清**：可以。乐观清零（`HomeViewModel.kt:256-267`）+ 树为准（`:745-753`）+
  CAS `expectedUnreadAt`（`:257-260`），与桌面 `clearTaskUnreadIfMatches` 对齐。✅
- **mutation 失败回滚范围**：整表回滚（`HomeViewModel.kt:879-887`、`ChatViewModel.kt:1297-1303`）→
  覆盖期间的索引合并（P2-8）。
- **delete 后 registry.close 时序**：**已修**——`ChatViewModel.kt:1175-1194` 现在明确不 close，
  注释说明理由（工作区级保活也要给 Home 的预览用），早期「删一个任务关整条对话 + 未捕获异常」
  的路径不存在了。遗留只有 P2-14（close/closeAll 成死代码）。
- **selectedTask 是否可能为 null**：会。① 索引未到的新路由任务；② P1-2 生效后 `selectedWorkspace == null`；
  ③ 删除后 `selectedTaskId` 仍指向已删除 id（此后所有 mutation early-return）。

### 4.5 桥 / 会话生命周期

- **key 复用**：按请求 key 幂等（`ConversationRegistry.kt:86`）；同 key 不会出现第二条 transport
  （`ChatOps` 只取现成句柄，绝不自己 ensure：`ChatOps.kt:124-133`）。✅
- **sessionSerial**：只在 `ensure` 内对账并清缓存（`:81-86`）；`handleFor` 无版本校验（P2-6）。
- **effectiveScope**：`remote:server:<id>:/path` 取第一个 `/` 之后的路径段；server/ssh/wsl/docker 的
  identity 前缀段不含 `/`，切分正确（`:147-161`）。✅
- **close/closeAll**：现无调用点（P2-14）；进程结束由引擎 `dispose` 兜底，可接受。
- **同工作区第二条 transport**：同 key 不会；「项目主根 key」与「secondary root 任务 key」是两条独立
  transport（各自一条 conversation），这是规格允许的代价（round2 01 §5.3）。
- **引擎重建后缓存**：最迟在下一次 `ensure` 清；期间 `handleFor` 会给出失效句柄（P2-6）。

### 4.6 状态与事件

- `sessionsIndexUpdated` / `stateChanged`：都触发 `refreshConversationState`（`ChatViewModel.kt:1737-1741`），
  但无来源过滤（P0-1）、无去抖（P2-12）。
- `refreshConversationState` 的任务合并：保留 `pinned/archived/unreadAt`、保留索引缺席的本地任务
  （`:1447-1461`）—— 方向正确；但依赖 `currentWorkspace != null`（P1-2）。
- `workspaceId` 赋值：`indexes.firstOrNull().workspaceId ?: 旧值`（`:1433`、`:1466`），不清空（P2-11）。
- `NotificationCoordinator`：与前台争抢的结果是「读不到任务」（P1-4），并与 Chat/Home 共享丢帧缓冲（P1-5）；
  `onAttention` 的 `active=false` 分支只在 Chat 页在场时才会被调用，后台任务永远拿不到等待提醒。

---

## 5. 本轮确认已修好的项

1. **readTree 取代 `listPinnedTasks/listArchivedTasks`**：`loadTaskMeta`/`applyTaskMeta` 已删，
   `pinned/archived` 只取 `readTree.tasks`（`HomeViewModel.kt:507-509`）。round2 03 R1「失败清零」不成立。
2. **任务基底翻转为 readTree**：`mergeSessionsIndex` 以 `workspace.tasks` 为基底、索引只补活动字段
   （`:773-825`）。round2 03 R3 已修。
3. **`unreadAt` 可被清**：树值允许 null 覆盖 + `markRead` 乐观清零 + CAS。round2 03 R2 已修。
4. **非 tab 工作区不再硬开桥**：`ensurePreviews` 对 `!bridgeable` 直接跳过（`:461-464`），
   `registry.ensure` 不再传 `project`（`:472-476`）—— round2 03 R4 的「裸 openBridge 必然失败」
   已被消解，代价见 P1-6（无实时活动）。
5. **`sendText` 的模型/模式字段**：Rust 已透传 `mode/planEnabled/modelSelection`
   （`conversation.rs:358-385`、`conversation_transport.rs:1335-1363`）。round2 02 §2.4「静默丢弃」已修。
6. **`createSession` 超时**：调用点显式 90s + 宿主认 `args.timeoutSecs`（`ZemoteEngine.kt:706-710`）。
   round2 02 §4.2 的「35s 假超时」主体已修（余 P2-3）。
7. **`channel_call` 的 args 形状**：`readTree`/`readDirectory`/`zcode-task` 都是
   `JsonArray(listOf(arg))`（`HomeViewModel.kt:571-583`、`:594-598`、`:871-883`；`ChatViewModel.kt:1290`），
   与 round2 01 §1 一致。
8. **打开任务用任务自己的 key/path/identity，本地任务也带 `taskHostId:"local"`**
   （`AppNavHost.kt:161-177`、`ChatViewModel.kt:825-833`）。round2 01 §7.2 / §5.4 已落地。
9. **`mergeTreeTasks` 删除树里已不存在的旧任务**（`:743-755`），不再无限残留。
10. **删除任务不再 close 整条工作区对话**（`ChatViewModel.kt:1175-1194`）—— 本轮审计开始时存在的
    「删一个任务瘫掉该工作区订阅 + 未捕获异常崩溃」已由作者修掉。
11. **pinned 列表的 Compose key 带 workspaceKey**（`HomeScreen.kt:155`），round2 03 R8 的 key 撞车已修。
12. **`_bridgeWorkspaceKey` 仍是请求值**（`EnginePort.kt:402`）但全仓已无读取点，权威 key 改由
    `ConversationRegistry.effectiveWorkspaceKey(snapshot)` 提供（`:128-137`）—— round2 01 §3.5 的隐患实际消除，
    该字段可删。

---

## 6. UNVERIFIED（需真机/抓包）

1. **远端 readTree 行的 `hostId`**：Android 原样使用 `task.hostId`/`project.hostId`（与 web 一致）。
   若某 host 的行不带 hostId，本地会退化成 `kind="local"` + 裸路径 key，开桥必然失败。
   `jv` 包装只补 `workspaceIdentity`（`tmp/host-index.js` @46639 区），而 web `vbt` 按
   `task.hostId===hostId` 过滤 —— 说明线上必被某层盖章，具体位置未定位。
2. **`zcode-task` 请求侧是否使用 `title/displayStatus/updatedAt`**（P2-9 是否真会清标题）。
3. **桌面端是否对 `planEnabled:true` 做能力门禁**（P2-2）。
4. **`conversation_event` 的实际频率与丢帧概率**（P1-5 的量化）。
5. **两个 Chat 页叠栈的可达性**：代码路径成立（`NavEntryStores` 保留栈内全部条目），
   需真机确认通知/外链能产生第二个 `Route.Chat`。
