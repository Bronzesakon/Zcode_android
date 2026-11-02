# 03 · 界面状态 / 失败边界 / 用户可见行为 —— Round3 审计

审计方式：**只做静态阅读**。未连设备、未运行 App、未执行 Reference_web 中的 JS。
本轮重点：草稿回填（draftRestore）、乐观更新、sessions-index 合并是否引入新的 UI 错误。

> **快照说明**：审计期间源码被并发修改（HomeViewModel 在 17:00–17:10 间至少变了 4 次哈希）。
> 下表的哈希是**写入本报告时刻**的值；正文行号按同一时刻的快照标注，
> 结论以「函数名 + 代码片段」为准。若后续继续改动，请按函数名重核。

| 文件 | 行数 | sha256（写入时） |
|---|---:|---|
| `android/app/src/main/kotlin/app/zemote/ui/chat/ChatViewModel.kt` | 1914 | `09b6e608f78145e94b0f8b958dad8c9dd19435ff5abbae78e15cc089108a8048` |
| `android/app/src/main/kotlin/app/zemote/ui/chat/ChatScreen.kt` | 1146 | `e8890622f5146b1adb2bb515798db5ae90010cff7452a783f3a189ec978549fc` |
| `android/app/src/main/kotlin/app/zemote/ui/home/HomeViewModel.kt` | 1029 | `e4533aa77db25ef680a88eb47ac67083ce83c2979e36c001bde392c5aff3fb2a` |
| `android/app/src/main/kotlin/app/zemote/ui/home/HomeScreen.kt` | 735 | `ab3b52671054a7d676064296077995bc5e212e0ef598f0c11207e8adbce32da1` |
| `android/app/src/main/kotlin/app/zemote/ui/nav/AppNavHost.kt` | 456 | `38f515b1da904f04492cdcbd0a366fecdf88ce12047d544d1a8ad92590b4ad6d` |
| `android/app/src/main/kotlin/app/zemote/ui/chat/SessionsIndexProjection.kt` | — | `2c12dacc4084905a68e6fd83e5ffe5456b452994699050f69e08706f15c9d8d0` |
| `android/app/src/test/kotlin/app/zemote/ui/chat/ChatViewModelTest.kt` | 696 | `0c9c0b59cbdae9653c27e51a5d5f194526b8473f98fa7e7c6e57e7c9bf48d263` |

---

## 0. 结论摘要

- **P0：0 条**（未发现崩溃、不可恢复的数据损坏或必然卡死主流程的问题）。
- **P1：8 条**：删除后的会话状态、sessions-index 永不删除任务、新建会话与进页竞态、
  首条消息订阅失败不可恢复、workspace-list 丢路由工作区、Chat/Home 任务状态分叉、
  draftRestore 覆盖新输入、通知深链缺上下文。
- **P2：14 条**：并发 mutation 回滚竞态、索引增量复活已删任务、高频全量合并、
  发送中附件可增删、跨 host 预览/忙碌键冲突、非 tab 项目无实时活动、通知缺 workspaceKey 的退化、
  测试缺口等。
- **已修好 / 无问题**：草稿回填生命周期闭环、`sending` 双保险、createSession 失败不改状态、
  失败重试不重复建会话、附件失败保留、Home 的 readTree 基底 + index 叠加方向与字段分工、
  unread 可清、删除不再关闭共享对话、返回首页不丢展开/预览、索引未就绪不清列表。

---

## 1. 逐条答复（对应任务书的 6 项）

### Q1 发送失败回填：生命周期、并发、附件、覆盖

| 检查点 | 结论 |
|---|---|
| draftRestore 设置 → `LaunchedEffect` → `consume` | **闭环正确**。`sendText` 失败写 `draftRestore`（ChatViewModel `sendText` catch，≈:463-470）；`ChatScreen` 的 `LaunchedEffect(state.draftRestore)` 先 `input = restore` 再 `onDraftRestored()`（ChatScreen ≈:158-163）；`consumeDraftRestore` 立即置 null（ChatViewModel ≈:800-802）。null→同值 的第二次失败仍会重新触发 effect。 |
| 并发发送 | **基本被挡住**：UI `canSend = ... && !sending && !uploading`（ChatScreen ≈:937-946）+ VM `if (... || _state.value.sending) return`（ChatViewModel ≈:439）双保险；`sending` 在 `viewModelScope`（Main.immediate）里、首个挂起点之前置位。 |
| 附件保留 | **失败时保留**（sendText/createConversation 的 catch 都不清 `attachments`），成功后才 `attachments = emptyList()`。但发送期间仍可增删附件，成功后会把「发送中新增、未随本条上传」的附件一起清掉（见 P2-4）。 |
| 输入框被旧值覆盖 | **会**：effect 无条件 `input = restore`；发送在途时输入框仍可编辑，用户新输入的文字会被失败回填覆盖（P1-7）。另有一个窄竞态：ChatScreen 先 `input=""` 再调 VM，若 VM 因 `sending` 早退，文本直接丢失（P1-7 同条）。 |

### Q2 新建对话四条路径的状态一致性

`createConversation`（ChatViewModel ≈:693-797）顺序：`ensureRouteBridge(taskId=null)` → `createSession(timeoutSecs=90)`
→ 写 `selectedTaskId=newId` + 清 rows → `subscribeConversation` → `loadModelView` →
`refreshConversationState` → 上传附件 → `sendText`。

| 路径 | selectedTaskId | rows | error | attachments | draftRestore | 判定 |
|---|---|---|---|---|---|---|
| createSession 失败 | 不变（null） | 不变 | 有 | 保留 | 回填 | ✅ 一致；重试仍是新会话路径，不会改坏旧状态 |
| createSession 成功、sendText 失败 | 新 id（保留） | 已清空 | 有 | 保留 | 回填 | ✅ 会话保留，重试走普通 `sendText`，**不会重复 createSession**；但附件会二次上传（P2） |
| subscribe 失败 | 新 id | 已清空 | 有 | 保留 | 回填 | ❌ 会话在、消息没发；重试走 `sendText`，**不会重新订阅** → 消息发出但界面永远收不到帧（P1-4） |
| 附件上传失败 | 新 id | 已清空 | 有 | 保留 | 回填 | ✅ 可重试；但已上传成功的 blob 会重复上传/残留（P2-4） |
| 返回空 sessionId（协议异常） | 不变 | 不变 | 有 | 保留 | 回填 | ⚠️ 服务端可能已建会话 → 孤儿会话；重试会再建一个（低概率，P2） |
| `workspaceId == null` / legacy 项目无 projectId | 不变 | 不变 | 有 | 保留 | 回填 | ✅ 本轮已加提前报错（legacy 分支 ≈:708-721），不发必然失败的 RPC |

另有一条**新的竞态**：`enter()` 在 `refreshInternal()` 之后总会执行
`openWorkspaceInternal(initialWorkspaceKey, initialTaskId)`，而它无条件写
`selectedTaskId = taskId`（ChatViewModel ≈:1069-1080）。新对话路由的 `initialTaskId == null`，
所以「用户在进页（等 bootstrap）期间就发出首条消息」时，新建的 `selectedTaskId` 会被随后执行的
`openWorkspaceInternal` 重置为 null（P1-3）。

### Q3 sessions-index 合并进 ChatViewModel.workspaces

- **不会覆盖路由骨架**：`extras = workspace.tasks.filter { it.id !in merged }`（≈:1465）把索引还没收录的
  本地任务补回；合并瞬间 `selectedTask` 不会变 null（前提是 workspace 还在 `workspaces` 里）。
- **不会回退 pinned/archived**：`task.copy(pinned = old.pinned, archived = old.archived, unreadAt = old.unreadAt ?: task.unreadAt)`
  （≈:1452-1456）旧值优先，本地乐观值保留。
- **会回退标题**：`merged` 的标题恒取索引条目（`SessionsIndexProjection` 的 title），
  改名后若索引帧还是旧标题（titleSource 非 custom），标题被顶回去；索引 title 为空时还会把标题清成 null，
  header 落回「新对话」。Home 的合并有 `titleSource == "custom"` / 非空判断，Chat 没有（P2-13）。
- **`extras` 的副作用**：它不只保留骨架，而是保留**所有**「当前列表里有、索引里没有」的任务；
  索引发 `session.removed` 或列表变空时任务也不会消失（`indexedTasks.isEmpty()` 直接跳过合并，
  ≈:1449-1451）。Chat 页因此**永远不删任务**（P1-2）。
- **会让 selectedTask 变 null 的不是合并，而是另外两条路径**：
  `WorkspaceListUpdated` 用 `WorkspaceProjection.merge`（只 map `updated`，≈:1846-1852）会把不在
  事件里的路由/项目树工作区整个丢掉（P1-5）；`refresh()`→`refreshInternal`（≈:1043-1059）
  用 bootstrap 重建 workspaces，也会丢掉已合并的 tasks。
- **高频触发**：`stateChanged` 和 `sessionsIndexUpdated` 各起一个协程调 `refreshConversationState()`
  （≈:1748-1752），每次都 `conversationSnapshot` 全量读 + 全量 map；没有去抖/合并/序号，
  并发完成顺序不受控，旧快照可能覆盖新活动字段（P2-5）。

### Q4 乐观更新与回滚

- **只回滚目标工作区**：Home `mutate` 与 Chat `mutateTask` 的失败分支都按 `workspace.key` 匹配后回滚（Home ≈:890-898；Chat ≈:1300+），不会误伤其他工作区。✅
- **同一工作区并发两次 mutation 会互相回滚**：`before` 是调用时的整表快照（Home ≈:844；Chat ≈:1262），
  A 失败时把 B 的乐观结果一起覆盖；且 `busyTasks` 只用于转圈，并不禁用点击，Home 左滑三按钮与 Chat 菜单都能重复触发（P2-1）。
- **delete 后 selectedTask 变 null 的影响（Chat）**：删除只从 `tasks` 里 filter 掉，不重置
  `selectedTaskId`/`rows`（P1-1）。结果是 header 变成「新对话」、旧时间线仍在、菜单还在但所有任务操作
  静默 no-op（`mutateTask` 的 `selectedTask ?: return`），再点发送会把消息发往已删除的 session。

### Q5 Home 派生属性（readTree 基底 + 索引叠加 + 多根按 projectId 归组）

- `visibleWorkspaces` / `pinnedTasks` / `hasUnread` / `tasksOf` 的过滤规则与 web `OP`/`gbt` 一致：
  归档不出现；置顶从工作区行抽出且排除归档；未读自动展开只看未读；`canShowAll` 判据正确。✅
- **基底方向正确**：`mergeTreeTasks` 以 readTree 为基底（tree 外任务丢弃，≈:744-755），
  `mergeSessionsIndex` 只补 `displayStatus/hasBackgroundWork/updatedAt/title/titleSource`（≈:800-822），
  不碰 `pinned/archived/unreadAt`。round2 R1/R2/R3/R9/R10 的修复保持有效。
- **多根项目归组正确**：`projectKeyById["hostId\0projectId"]` → 主根 key，任务按 projectId 归组（Home ≈:640-680）；
  任务行打开时用任务自己的 path/identity（AppNavHost ≈:168-177），不会被主根带偏。✅
- 残留问题：
  - index additions（索引有、readTree 没有）会重新加回刚被 readTree 丢掉的任务（P2-2）；
  - `previews`/`busyTasks` 只按 taskId 做键，跨 host 同 id 会串台（P2-6）；
  - `SessionsIndexProjection` 不产出 workspacePath/identity/hostId，index-only 任务在 multi-root/远端打开时可能落到主根路径（P2-7）；
  - 非 `bridgeable` 的项目工作区已**不再订阅** sessions-index（Home `ensurePreviews` ≈:465），
    这些工作区没有 preview、没有实时 running/background 状态（P2-8）；
  - 若 bootstrap 为空（桌面没有打开任何 tab），`loadProjectTree` 不会触发，项目树完全不显示（P2-9）。

### Q6 路由与返回

- **从任务进入 Chat 再返回**：`HomeViewModel` 由 `NavEntryStores` 的 `"main"` key 保留（AppNavHost ≈:432-453），
  `expanded/collapsed/previews` 不丢；返回时 `MainRoute` 的 `LaunchedEffect(Unit)` 会再次 `home.onEnter()`，
  只重拉 bootstrap，不清 UI 状态。✅
- 但 **Home 与 Chat 的任务状态不共享**：在 Chat 里置顶/归档/改名/删除不会通知 Home，
  返回首页仍是旧状态，直到用户下拉刷新（force 会重跑 readTree）；Chat 自己重建后 pinned/archived 也会丢（P1-6）。
- **Chat 页没有「新对话」按钮**，只有 header 在无选中任务时显示标题「新对话」；新建入口在 Home 的
  WorkspaceCard「+」。该入口对 bootstrap 工作区与有 projectId 的项目工作区都正确；
  本轮已给「legacy 项目（bridgeable=false 且 projectId=null）」加了提前报错（ChatViewModel ≈:708-721）。✅
- **通知点击**：`NotifyTarget` 只有 `workspaceKey + taskId`（AppContainer ≈:114-135），
  AppNavHost 只把它们塞进 `Route.Chat`（≈:101-119），没有 path/identity/hostId/projectId。
  - `workspaceKey == null` → `replaceRoot(Main(HOME))`，退化正确 ✅（但不会定位/标记该任务）。
  - `workspaceKey != null` 但不在 bootstrap（项目树任务，最常见）→ `selectedWorkspace == null`，
    `openBridge(taskId, taskHostId=null, project=null)` 退化成 tab 查找，桌面抛
    「目标工作区不在当前桌面窗口中」；即便桥能开，`conversationScope()` 的兜底会把 key 当
    `workspacePath`（ChatViewModel ≈:889-908），远端 identity key 会变成不存在的 cwd（P1-8）。

---

## 2. P1（按影响排序）

### P1-1 Chat 删除任务后不清理 selectedTaskId / rows

- **证据**：`deleteTask()`（ChatViewModel ≈:1180-1198）只做 `updateTasks = filterNot`，
  `mutateTask` 成功后也不写 `selectedTaskId`；`ChatUiState.selectedTask` 由 `workspace.tasks` 反查（≈:250），
  于是立刻变 null。`ChatScreen` header 标题回落「新对话」（≈:186）、菜单仍渲染（≈:389-406）、
  `sendText` 仍用非空 `selectedTaskId` 发往已删 session（≈:440-457）。
- **用户可见**：删除后页面显示「新对话」但旧消息还在；置顶/归档/改名/再删全部静默无反应；
  再发消息报错并回填草稿。
- **修复**：`deleteTask` 成功后（或乐观摘除后立刻）如果 `id == selectedTaskId`：
  `selectedTaskId = null`，清 `rows/totalCount/firstRowId/logEpoch/historyExhausted/canLoadOlder`，
  取消 `prefetchJob`/`persistJob`；更符合直觉的是成功后 `onBack()` 回首页。

### P1-2 Chat 的 sessions-index 合并永远不会删除任务

- **证据**：`refreshConversationState` 中
  `if (indexedTasks.isEmpty() || currentWorkspace == null) current.workspaces`（≈:1449-1451）——
  索引列表为空（例如所有会话都被删）时**整个跳过合并**；
  `extras = workspace.tasks.filter { it.id !in merged }`（≈:1465）把索引里已移除的任务全部补回。
- **用户可见**：桌面端删除/归档掉的任务在 Chat 页永远还在；`session.removed` 无效；
  与 P1-1 叠加后删除体验完全错乱（先消失、下一帧又回来、header 在「新对话」和标题之间跳）。
- **修复**：维护一个显式的「本地待确认 id」集合（路由骨架 + 本页 createSession 新建的 id，带 TTL），
  `extras` 只从该集合取；索引 `ready` 且列表为空时按空处理（清空），非空时以索引为权威移除。
  或者 Chat 也像 Home 一样先拉一次 `project.readTree` 作基底，索引只补活动。

### P1-3 createConversation 与进页 openWorkspaceInternal 的竞态

- **证据**：`enter()` → `refreshInternal()`（可能等 `waitPaired` 35s）→
  `openWorkspaceInternal(initialWorkspaceKey, initialTaskId)`（ChatViewModel ≈:951-981）；
  `openWorkspaceInternal` 开头无条件 `selectedTaskId = taskId`（≈:1069-1080）。
  新对话路由 `initialTaskId == null`；若用户在 bootstrap 完成前输入并发送，
  `createConversation` 已把 `selectedTaskId` 设为 `newId`（≈:751-761），随后被重置为 null。
- **用户可见**：header 回到「新对话」、时间线仍显示刚发的内容；再点发送会**再建一个会话**，
  第一条消息留在无人引用的会话里；`refreshConversationState` 也因 `sessionId` 为 null 读不到 config。
- **修复**：`openWorkspaceInternal` 在写 `selectedTaskId` 前检查「期间是否已创建会话」：
  例如进入时记录 `initialTaskId`，若当前 `selectedTaskId != initialTaskId` 则不覆盖；
  或 `createConversation` 置一个 `sessionCreated = true`，`openWorkspaceInternal` 见到就跳过
  `selectedTaskId/rows` 重置；更简单的是在 `loading` 期间禁用 Composer 的发送。

### P1-4 首条消息的 subscribe 失败后，重试不会重新订阅

- **证据**：`createConversation` 先 `subscribeConversation(newId)`（≈:763），失败被同一个 catch 吞成
  `error + draftRestore`（≈:783-792），但 `newId` 已写进 `selectedTaskId`。
  重试走 `sendText`（≈:436-475），该路径只 `ensureRouteBridge` + `sendText`，**没有 subscribe**；
  `openWorkspaceInternal` 的 subscribe 只发生在进页时（≈:1130-1137）。
- **用户可见**：消息实际上发出去了（`ChatOps` 的 handle 还在），但页面永远收不到 `rowsUpdated`，
  时间线空白；只有退出重进（新 ViewModel 走 openWorkspaceInternal）才恢复。
- **修复**：在 `sendText` 里发消息前做幂等订阅（记录 `subscribedSessionIds`，或每次先
  `conversationCallNoWait("subscribe")`）；`createConversation` 的 catch 里若 `sessionId` 已拿到，
  单独重试一次 subscribe 并把失败显式告知。

### P1-5 workspace-list 整表更新会把路由工作区从 Chat.workspaces 里删掉

- **证据**：`onEngineEvent` 的 `WorkspaceListUpdated` 用
  `WorkspaceProjection.merge(it.workspaces, snapshot.workspaces)`（ChatViewModel ≈:1689-1695），
  该函数只 `updated.map { ... }`（≈:1846-1852），**不会保留事件里没有的当前工作区**；
  也不像 `refreshInternal` 那样补 `routeWorkspace`（≈:1043-1059）。
- **用户可见**：项目树/远端工作区在 Chat 页收到一次 workspace-list 推送后，`selectedWorkspace`
  → null、`selectedTask` → null：header 变「新对话」、菜单静默失效、`refreshConversationState`
  的索引合并被跳过；`conversationScope()` 退化成用 key 当路径。
- **修复**：抽一个 `mergeWorkspacesKeepingRoute(current, updated)`：先 `WorkspaceProjection.merge`，
  再对 `routeWorkspace`、以及 `projectTree` 类 key 做 `putIfAbsent`（保留旧 tasks），
  事件处理与 `refreshInternal` 共用。

### P1-6 Chat 的任务 mutation 不回写 Home；Chat 自身 pinned/archived 不持久

- **证据**：Chat 的 `setPinned/archiveTask/renameTask/deleteTask` 只改 Chat 的
  `_state.workspaces`（≈:1150-1204）并发 RPC，没有任何跨 ViewModel 通知；Home 返回时
  `refreshInternal(force=false)` 不会再拉 readTree（`projectTreeAttempted` 仍为 true）。
  Chat 侧 `readTree` 从未调用，sessions-index 又没有 pinned/archived，
  一旦 `refreshConversationState` 遇到 `old == null`（VM 重建、refresh 后）就只能给 false。
- **用户可见**：在 Chat 里置顶/归档/改名/删除后返回首页，列表完全没变（要下拉刷新才对齐）；
  从首页打开任务，Chat 菜单仍显示「置顶」而不是「取消置顶」；Chat 内下拉/错误横幅「重试」后
  pinned/archived/本地改名丢失（索引能补回标题，但补不回 pinned/archived）。
- **修复**：把任务元数据收敛到一个共享的 `TaskRepository`（`StateFlow<List<WorkspaceItem>>`
  或进程级缓存）供 Home/Chat 共用；最低成本方案：Chat 的 mutation 成功后通过 `AppContainer`
  回调通知 Home（或在 Chat pop 时让 Home 做一次轻量 readTree 对齐，而不是整表 refresh）。

### P1-7 draftRestore 无条件覆盖输入框；VM 拒绝发送时文本丢失

- **证据**：`ChatScreen` 的 `LaunchedEffect(state.draftRestore)` 直接 `input = restore`（≈:158-163），
  没有判断输入框当前是否为空；发送期间 Composer 的 `BasicTextField` 没有禁用（≈:844-867）。
  `onSend` 先 `onSend(text); input = ""`（≈:251-257），`sendText` 的 `sending` 早退（≈:439）不会回填。
- **用户可见**：发送在途（桥慢/附件大）时用户输入了下一句话，失败回填把新输入覆盖掉；
  双击发送的第二次点击可能让第一段文本既没发出也没回填。
- **修复**：回填只在 `input.isBlank()` 时执行，否则用 `input = restore + "\n" + input` 或
  在错误条里提供「恢复为草稿」按钮；`onSend` 改成返回 `Boolean`（或接受回调）后再清空输入；
  发送期间禁用输入/附件按钮。

### P1-8 通知深链 Route.Chat 缺 path/identity/hostId/projectId

- **证据**：`AppContainer.onNotifyIntent` 只取 `EXTRA_SESSION_ID` + `EXTRA_WORKSPACE_KEY`
  （AppContainer ≈:114-135）；`ChatViewModel` 的 `onAttention` 只传出 `workspaceKey`（≈:1639-1641）；
  AppNavHost 只 push `Route.Chat(deviceId, workspaceKey, taskId)`（≈:101-119）。
- **用户可见**：点通知进入项目树任务（非当前 tab）几乎必然失败：`ensureRouteBridge(taskId)`
  拿不到 `initialHostId` → `openBridge` 不带 `taskHostId` → 桌面退化成 tab 查找
  （Round2/01 §2.2：「有 taskId 但不给 taskHostId … 项目树任务会失败」）。
  远端 identity 工作区还会把 key 当 `workspacePath`。页面表现为错误横幅 + 「新对话」标题。
- **修复**：`NotifyTarget` 增加 `workspacePath/workspaceIdentity/hostId/projectId`，
  由 `onAttention` 的 `WorkspaceItem` 填充（需要把回调签名从 4 参扩成 5 参或传 WorkspaceItem）；
  导航层解析不到时退回 Home 而不是进入一个必然失败的 Chat。

---

## 3. P2（可排期修）

| # | 问题 | 证据 / 触发 | 建议 |
|---|---|---|---|
| P2-1 | 同工作区并发 mutation 的 `before` 快照回滚互相覆盖；busy 不禁用按钮 | Home `mutate` `before` ≈:844、回滚 ≈:890-898；Chat `mutateTask` `before` ≈:1262；Home 左滑按钮/TaskRow 无 `enabled`，Chat 菜单无 busy | `before` 改为「只回滚本次改动的那个 task 字段」或按 mutation id 合并；busy 时禁用该行操作 |
| P2-2 | Home 的索引 additions 把已从 readTree 消失的任务加回来 | `additions = indexed.filter { it.id !in known }`（Home ≈:821）；删除/桌面删除后索引未更新即复活 | 维护短期 tombstone（taskId+TTL），过滤 additions；RPC 成功后写入 |
| P2-3 | Chat 的 `refresh()` / 错误横幅「重试」丢弃已合并任务与 pinned | `refreshInternal`（Chat ≈:1043-1059）用 bootstrap 重建 workspaces；`WorkspaceProjection.merge` 只在 key 存在时保 tasks | 与 P1-5 共用 keep-route/keep-tasks 合并；refresh 前后保留本地任务 |
| P2-4 | 发送期间可增删附件；成功后 `attachments = emptyList()` 清掉发送中新增的附件；`uploadIndex` 按下标错位 | ChatScreen 附件 `onPickAttachment`/`onRemoveAttachment` 始终可点（≈:824-884）；ChatViewModel 成功清空 ≈:460/781；`uploadAttachments` 捕获旧列表 | 发送/上传期间禁用 +/−；成功后只移除本次上传的 id；附件加稳定 id 而不是下标 |
| P2-5 | `sessionsIndexUpdated`/`stateChanged` 每次全量 snapshot + 全量重建，无去抖/合并/序号 | Chat ≈:1748-1752；Home 每个事件对所有 `previewSubscriptions` 逐个 `conversationSnapshot`（Home ≈:780-795，目前仅 bootstrap 工作区） | 用 `Mutex` + 最新帧合并（conflate）或 50–100ms 去抖；快照带 seq，旧结果丢弃 |
| P2-6 | 跨 host 同 taskId：`previews`、`busyTasks` 只按 taskId | `previews` map / `busyTasks` set（HomeUiState ≈:72-79）；`SessionsIndexProjection.previews` 按 sessionId | 键改为 `(workspaceKey|hostId, taskId)`；Compose key 已修好，保持 |
| P2-7 | index-only 任务没有 `workspacePath/identity/hostId`，multi-root/远端打开可能落到主根 | `SessionsIndexProjection.toTask` 只填 workspaceKey/label/kind；AppNavHost 兜底 `task.workspacePath ?: workspace.path`（≈:172-174） | 打开前用 readTree/任务详情补齐；或在 TaskItem 里带 `projectId/rootPath` |
| P2-8 | 非 bridgeable 项目工作区不再订阅 sessions-index：无 preview、无实时 running/hasBackgroundWork | Home `ensurePreviews` 新逻辑 `if (!workspace.bridgeable) return@withLock`（≈:465）；`loadProjectTree` 也未解析 `phase/hasBackgroundWork` | 如需活动态：在 loadProjectTree 里解析 `phase`→displayStatus、`hasBackgroundWork`；或进入对话页时再订阅（已有设计） |
| P2-9 | bootstrap 为空（桌面无 tab）时项目树永不加载；workspace-list 新增工作区不订阅预览 | `loadProjectTree` 只在 `ensurePreviews` 内触发（Home ≈:513-516），而 `refreshInternal` 只对 bootstrap 工作区调 `ensurePreviews`（≈:407-411）；`WorkspaceListUpdated` 只合并不 `ensurePreviews`（≈:936-944） | 用任意健康桥（或注册表里已有桥）独立触发一次 readTree；workspace-list 新 key 调 `ensurePreviews` |
| P2-10 | Chat header 菜单在 `selectedTask == null`（新对话/刚删）仍显示，点击静默 no-op | ChatScreen ≈:185-196、389-406；`mutateTask` `selectedTask ?: return`（≈:1260） | 无任务时隐藏任务菜单；或点击给 toast「先发送第一条消息」 |
| P2-11 | Home 的 `deviceId` 被 `devices.firstOrNull()` 覆盖（排序是 sortKey 升序，新增设备在末尾） | Home observe ≈:977-987；`enter()` 用 `currentDevice ?: devices.first()`（≈:445-448）；Route.Chat 依赖它（AppNavHost ≈:160-190） | 用 `currentDevice` 更新 deviceId，`devices` 收集只更新 name/credentials；或按 `deviceId` 反查 |
| P2-12 | 未读自动展开在 `markRead` 后自动折叠；`expanded` 集合保留但可见状态变化 | `isExpanded` = `expanded || (hasUnread && !collapsed)`（HomeUiState ≈:99-100）；AppNavHost 打开任务前 `markRead`（≈:157-159） | 打开任务时若该工作区是「未读自动展开」而非显式展开，顺手写入 `expanded`（记住用户看过） |
| P2-13 | 标题：Chat 合并恒取索引标题（空标题会清空）；Home 非 custom 的非空索引标题也会盖 readTree 标题 | Chat ≈:1456-1457、`SessionsIndexProjection`；Home ≈:811-817（与 web `cvt` 一致，但改名瞬间有回退窗口） | Chat 与 Home 统一为 `custom → 索引；空 → 旧值`；rename 后本地置 `titleSource=custom` 直到索引确认 |
| P2-14 | 测试缺口：无 `HomeViewModelTest`；`ChatViewModelTest` 无 draftRestore/失败回填/合并/回滚断言 | 全仓 `android/app/src/test/.../ui/home` 不存在；ChatViewModelTest 只在两处断言 `sending=false`（≈:262/325） | 至少补：发送失败回填并 consume、createSession 成功后 send 失败不重复建会话、索引 removed 不被 extras 复活、workspace-list 不丢 route、mutation 失败只回滚目标工作区 |

---

## 4. 已修好 / 确认无问题

1. **draftRestore 生命周期闭环**：设置→effect 回填→consume，重复同值失败可再次触发；`consumeDraftRestore` 幂等置 null。✅
2. **`sending` 双保险**：UI `canSend` + VM 早退；Main.immediate 下置位早于首个挂起点，常规连点发不出第二条。✅（唯一缺口见 P1-7）
3. **createSession 失败不污染状态**：不写 `selectedTaskId`、不清 rows、保留附件与草稿。✅
4. **createSession 成功、sendText 失败**：会话保留，`selectedTaskId` 已就位，重试走普通 sendText，**不重复建会话**。✅
5. **附件上传失败**：中止整条消息、保留附件、草稿回填；不会「发一半」。✅（重复上传见 P2-4）
6. **Home 合并方向**：`mergeTreeTasks` 以 readTree 为基底并丢弃树外任务（ghost 任务已修）；`mergeSessionsIndex` 只补活动字段，不动 pinned/archived/unreadAt。✅
7. **未读可清**：`markRead` 本地置 null（Home ≈:256-268），readTree 的 `unreadAt=null` 会生效，不再「旧值优先」。✅（round2 R2）
8. **不再二次计算 pinned/archived**：`loadTaskMeta/applyTaskMeta/listPinnedTasks/listArchivedTasks` 已从 Home 移除。✅（round2 R1）
9. **跨 host 置顶 Compose key**：`"pinned-${workspaceKey}-${id}"` + `(workspaceKey, taskId)` 定位（HomeScreen ≈:147-177）。✅（round2 R8）
10. **Chat 合并不会覆盖 pinned/archived、不会在合并瞬间把 selectedTask 变 null**（old 优先 + extras）。✅
11. **索引未就绪不清列表**：`ready == false && list 为空` 时跳过（Home ≈:787-793；Chat 的空列表跳过同理）。✅
12. **删除不再关闭注册表**：`deleteTask` 已移除 `registry.close`，不会再把 Home 的 sessions-index 订阅一起打断（本轮并发修改已修）。✅
13. **legacy 项目新建对话提前报错**（bridgeable=false 且 projectId=null，Chat ≈:708-721）。✅
14. **workspace-list 整表语义**：合法空快照会清空投影、认不出的形状不动（Chat ≈:1689-1695；Home 同理）。✅
15. **返回首页不丢 UI 状态**：Home VM 由 `"main"` store 保留，`expanded/collapsed/previews` 不动；返回只重拉 bootstrap。✅
16. **通知缺 workspaceKey 的退化**：`replaceRoot(Main(HOME))`，不会崩、不会进错页面。✅

---

## 5. UNVERIFIED

1. `readTree` 的 `args` 形状（round2/01 曾指出 `JsonArray(listOf(obj))` 可能让服务端只看数组、`hostId` 退化为 local）。
   本轮静态无法证明桌面 `iH.readTree` 是否把数组展开成位置参数；若是退化，远端项目/任务根本不会出现在 Home，
   本文 Q5 的跨 host 结论就只在「无远端 host」前提下成立。
2. `createSession` 的 90s 超时与宿主 95s 兜底之间的孤儿会话概率（静态只能确认参数已传）。
3. 发送失败但服务端已接受的重复消息概率（sendText 无幂等键）。
4. 桌面 `renameTask` 后 sessions-index 多快把 `titleSource` 更新为 custom（决定 P2-13 的回退窗口大小）。
5. `deleteTask` 从 RPC 成功到 sessions-index 移除之间的窗口（决定 P1-1/P1-2 的可见时长）。
6. 多设备场景（P2-11）是否真实存在（取决于用户是否配过 ≥2 台）。

---

## 6. 建议的修复顺序

1. P1-1 + P1-2（Chat 删除/移除语义，二者必须一起修，否则互相对冲）。
2. P1-3（新建会话竞态）、P1-4（订阅重试）。
3. P1-5 + P2-3（统一 workspace 合并保留 route/本地 tasks）。
4. P1-6（Home/Chat 任务状态共享；先做最小回调，再考虑 repository）。
5. P1-7（draftRestore 只在空输入时回填；onSend 结果驱动清空）。
6. P1-8（NotifyTarget 扩字段）。
7. P2-1/2/4/5（并发回滚、tombstone、附件、去抖）。
8. 其余 P2 与测试补齐。
