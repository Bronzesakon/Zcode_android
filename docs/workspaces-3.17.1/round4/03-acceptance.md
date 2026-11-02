# Round4 / 03 验收视角审计：测试覆盖、失败路径、UI 落地

**审查时点：2026-10-30 17:35（+08）。**
**审查对象（只读）：`android/app/src/main/kotlin/app/zemote/`、`android/app/src/test/kotlin/app/zemote/`、`docs/workspaces-3.17.1/WORKSPACES-FINAL.md` §10。**
**基线说明：审查期间另一路 Round4 代理仍在改测试/源码。本次快照内发生的变化：`ChatViewModel.kt` 17:26（附件只清本次上传）、`ConversationRegistryTest.kt` 17:31（6→8 例）、新增 `ui/home/HomeViewModelTest.kt` 17:32（2 例）。行号以 17:35 快照为准，后续改动可能导致 ±数行漂移。**

## 0. 结论先行

1. **验收清单 8 条里没有一条被"端到端"测试覆盖**；有直接断言的只有第 4 条的"createSession→sendText"、第 6 条的"注册表幂等/并发只建一次"、第 8 条的登录/配对单元。**第 5 条（失败回填、重试不重建设会话）目前零断言 —— 而它恰好是唯一会丢用户输入的能力。**
2. **实测 `./gradlew testDebugUnitTest`：35 个 suite / 394 例 / 0 失败 / 0 错误**，报告 `android/app/build/reports/tests/testDebugUnitTest/index.html`（17:34:11，6.665s）。全绿只说明"现有断言成立"，不说明验收项成立。
3. **失败路径没有"永久卡死"，但有"最长 35s/135s 无反馈等待"和三条真实丢数路径**：发送在途返回上一页（草稿+附件只存内存）、附件描述符非对象被静默丢弃、附件全量读入内存后可 OOM。
4. **UI 存在明确的"ViewModel 改了但没人读"字段**：`ChatUiState.bridgeDegraded`、`relayFailure`、`relayFailureVisible`、`credentialsMissing` 在 Chat 页完全没有任何渲染路径；`ChatScreen` 的"重试"按钮对所有错误都只会刷新工作区列表，不会重发消息。
5. **Fake 存在三处"过于乐观"**：`FakeChatOps` 对全部 op 返回同一个值、`FakeEnginePort.conversationSnapshot()` 默认 `JsonNull`（导致 config/运行态合并分支在单测里永远不执行）、`FakeEnginePort.openedBridgeIntents`/`waitHealthyFailure` 有记录/开关但从未被断言。

---

## 1. 对照 §10 验收清单：逐条判定

判定口径：
- **有线** = 有测试直接断言该验收行为；
- **间接** = 相邻层有测试，但验收行为本身没有断言；
- **无** = 没有测试。

| # | §10 验收项 | 判定 | 现有对应测试 | 关键缺口（缺哪条测试） |
|---|---|---|---|---|
| 1 | 首页出现桌面侧栏全部项目（本地 + 在线远端），不只 bootstrap 的 tab | **间接（本地部分刚补，远端/空 bootstrap 无）** | `ui/home/HomeViewModelTest.kt:44-105`（readTree 补出两个项目 + 多根任务归组）；`ui/chat/SessionsIndexProjectionTest.kt` | ① `remote-projects.readDirectory` → 只对 `connectionStatus=="online"` 的 host 发 readTree；② 远端 identity = `hostId + ":" + gy(path)`（`remoteIdentityOf` 零测试）；③ bootstrap 为空时 `loadProjectTree` 根本不触发（见 §4 的 P2-9）；④ readTree 的参数形状 `[{includeDeleted,includeDeletedTasks,hostId?}]` 无断言。 |
| 2 | 每个项目展开后任务数与桌面一致；置顶区出现且不含归档；未读点打开后消失 | **间接** | `HomeViewModelTest.kt:107-145`（markRead 本地清空 + 发了 setTaskUnread）；`HomeViewModelTest.kt:44-105`（pinned/unreadAt 来自 readTree 且保留） | ① `HomeUiState.pinnedTasks`（pinned && !archived）、`tasksOf`（未读自动展开 / collapsed 抑制 / 归档排除）、`hasUnread`、`canShowAll` 四个纯派生函数**零测试**；② readTree 里消失的任务必须移除（`mergeTreeTasks`）无断言；③ markRead 失败回滚、`mutationBridge == null` 时的可行动提示无断言。 |
| 3 | 点开非当前 tab 项目的任务能进入对话并收到历史/增量 | **间接** | `ChatViewModelTest.kt:156-169`（路由 key/taskId 透传到 `openBridge` 与 `sendMobileViewState`）；`ChatHistoryMergeTest.kt`（只测 `mergeSnapshotRows` 纯函数） | ① `FakeEnginePort.openedBridgeIntents` 记录了 `(workspaceKey, taskHostId, project)` 但**从无断言** —— `taskHostId`/`project` 这两个"非 tab 项目能不能开桥"的关键字段没有任何测试兜底；② `applyRowsUpdated`（snapshot/deltas/按 sessionId 过滤/epoch 重置）在 ViewModel 层零测试；③ `ChatViewModelTest` 全部用 `registry = null`，生产走的保活/权威 key 路径没有进页集成测试。 |
| 4 | 在非当前 tab 项目里新建对话能创建会话，首条消息出现，模型/模式按当前选择 | **间接（只有 createSession→sendText 一段有线）** | `ChatViewModelTest.kt:285-313`：断言 `workspaceId` 用 identity、`timeoutSecs=90`、不带 `firstText`、随后单独 `sendText` | ① `putSendSubmission`（mode/planEnabled/modelSelection 来自 config）零测试，`ChatViewModelTest.kt:264-268` 只覆盖"无 config → build/false"；② 新对话的 `project` 开桥意图无断言；③ 首条消息带附件、`createSession` 成功但 subscribe/首条发送失败的分支无测试；④ `selectedTaskId` 落位后的 UI 呈现（首条消息行）无测试。 |
| 5 | 发送失败时文本回到输入框，重试不重复建会话 | **无** | 无。`ChatViewModelTest.kt:348-358` 只断言 `error` 文案与 `sending=false`；全仓 `grep draftRestore` 在测试里 **0 命中** | 必须补：① 失败后 `draftRestore == 原文`、`consumeDraftRestore()` 置空、附件仍在；② `createSession` 成功→`sendText` 失败→重试只发 `sendText`（`createSession` 调用次数仍为 1）；③ 发送在途返回上一页（`viewModelScope` 取消）时的文本去向 —— 当前会丢（见 §2）。 |
| 6 | 同一工作区不会出现两条 transport（无「只发 hello 无后续帧」） | **间接（注册表单元有线，生产组合未测）** | `ConversationRegistryTest.kt` 8 例：重复/16 并发 ensure 只建一次、失败不缓存、权威 key scope、引擎重建后 `handleFor` 失效；`EngineChatOpsTest.kt:53-81`（换桥才重开对话） | ① `EngineChatOps(registry != null)` 分支（**生产唯一走的路径**）零测试 —— 现有 8 例全部 `registry = null`；② Home 与 Chat 共用同一 `ConversationRegistry` 时"同 key 只一条桥"没有跨 ViewModel 集成断言；③ 桥降级后第二条 transport 的回归用例无。 |
| 7 | 删除任务后缓存清除、工作区预览仍工作 | **间接** | `data/ChatCacheTest.kt`（9 例，覆盖 `remove`/`load`/`save`）；`ChatViewModel.deleteTask()` 代码已实现 `cache.remove` 且不 close registry | ① `deleteTask` 的"清缓存 + 不 close 注册表"组合零断言；② 删除后 `selectedTaskId/rows` 立即清空与 RPC 失败的交互无断言；③ Home 的 `delete` 与 Home 预览订阅不被影响的断言无。 |
| 8 | 迁移/登录/扫码不受影响 | **有线（单元级）** | `AuthStoreTest`(6)、`OAuthFlowTest`(7)、`OAuthTokenClientTest`(2)、`RemoteTokenClientTest`(5)、`PairingUrlTest`(8)、`PairingViewModelTest`(17)、`DeviceStoreTest`(22)、`DeviceRecordTest`(8)、`SecretCipherFormatTest`(4) | ① 旧设备记录缺 `lastRelayError/lastRelayErrorMessage/sortKey` 时的反序列化迁移无专门用例（依赖 Kotlin 默认值，未显式验证）；② `needsLogin` 触发→登录覆盖层→`onLoginReturn` 重进页的端到端只有 ViewModel 级零散断言，无 UI 级；③ 无任何 instrumented/Compose 测试（仓库无 `androidTest` 目录）。 |

### 1.1 判定汇总

- 有线：第 8 条（单元级）。
- 间接：第 1、2、3、4、6、7 条。
- 无：第 5 条（P0，直接对应用户可感知的"消息没了"）。

---

## 2. 失败路径审查

### 2.1 逐场景

| 场景 | 代码路径 | 用户看到的最终状态 | 卡死 / 丢数评估 | 测试 |
|---|---|---|---|---|
| **网络断开（relay 掉线）** | `engine.relayState → ChatUiState.relayState`；Home 头显示"重连中/已断开" | **Chat 页完全没有指示**：`ChatScreen.kt` 不读 `relayState`/`relayFailure`/`relayFailureVisible`，只有 `error` 才画横幅；Home 页头有状态点与文案 | 不卡死；但用户在 Chat 页看到的是**过期时间线 + 可点的发送按钮**。此时点发送会进入 `sendText → ensureRouteBridge`，可能拿到失效桥，直到宿主兜底 35s 才报超时 | 无 |
| **bridge 降级** | `EngineEvent.BridgeDegraded → ChatUiState.bridgeDegraded`（`ChatViewModel.kt:1838-1842`） | Chat 页**不渲染**（`bridgeDegraded` 全仓仅 HomeScreen 读）；Home 显示"连接已降级：原因"，无关闭按钮 | 不卡死；但降级期间发送/订阅失败要等超时；且降级提示在用户最需要它的对话页缺失 | `ChatViewModelTest.kt:513-526` 只断言 state 映射，不涉及 UI |
| **登录失效** | relay `auth-failed/auth-required` → `authStore.clear()` + `engine.disconnect()` + `needsLogin=true` + error（`ChatViewModel.kt:1728-1742`）；`AppNavHost.kt:364-370` 压 LoginOverlay | 全屏登录覆盖层；取消后回到带"登录已失效，请重新登录"横幅的页面 | 不卡死；重新登录走 `onLoginReturn → entered=false → onEnter()`，会重新 ensureSession | `ChatViewModelTest` 无 auth-failed 用例；`OAuthFlowTest` 只覆盖登录协议 |
| **桌面拒绝：Project navigation is unavailable.** | 引擎 `failed` → `EngineException` → `toUserMessage()`（`UiFailure.kt:19-35`） | 红色错误条，文案形如"参数被引擎拒绝：Project navigation is unavailable."（英文原文） | 不卡死；但**没有中文可行动指引**，也没有自动降级（例如去掉 `project` 重试）。`createConversation` 只对"无 projectId 的 legacy 项目"做了提前拦截 | 无 |
| **桌面拒绝：task-not-found** | 同上（RPC 失败）或 `AppError`（未列入映射表） | 错误条；`AppError` 未映射时读 `event.error` 原文 | 不卡死；任务菜单仍可点，重复操作只是重复报错（`mutateTask` 失败回滚） | 无 |
| **桌面拒绝：workspace-closed** | `EngineEvent.AppError(reason="workspace-closed")` → "工作区已关闭"（`ChatViewModel.kt:1772-1783`） | 错误条；时间线/发送按钮仍在 | 不卡死；若正在 `sending`，`sending` 要等 RPC 超时才落回 false，期间发送按钮禁用 | 无 |
| **发送超时（sendText）** | `sendText` 不传 `timeoutSecs` → 引擎宿主兜底 30s+5s（`ZemoteEngine.kt:36,45,703-708`） | 最长 35s 内：发送按钮灰、无 spinner、无"发送中"文案；超时后错误条 + **草稿回填** + 附件保留 | 不卡死、不丢文本；但 35s 无反馈，且 `ChatErrorBanner` 的"重试"会调用 `refresh()`（刷新工作区列表），**不会重发**，用户容易误以为已重试 | 无 |
| **createSession 超时** | `createSession(timeoutSecs=90)` → 宿主 90+5+45=140s 上限（`ZemoteEngine.kt:703-708`） | 最长 ~135-140s 的 `sending=true`；超时后错误条 + 草稿回填 + 附件保留，`selectedTaskId` 不写 | 不卡死、不丢文本；但不可取消，且若服务端实际已建会话（响应丢失），重试会**再建一个会话**（孤儿会话，可接受但无提示） | `ZemoteEngineConversationTest.kt:212-243` 只测宿主兜底时长，不测 ViewModel 表现 |
| **附件上传失败** | `uploadAttachments()`（`ChatViewModel.kt:522-551`）`attachmentPut` 抛错 → 整条中止 | 错误条 + 草稿回填 + 全部附件保留（`uploading` 在 finally 复位） | 不丢文本/附件；**但见 2.2 的静默丢附件与 OOM** | 无（`FakeChatOps` 无法只让 `attachmentPut` 失败） |

### 2.2 会卡死或丢数据的路径（按严重度）

1. **P0｜发送在途返回上一页 = 草稿与附件永久丢失。**
   `AppNavHost` 弹出路由时 `NavEntryStores.retain()` 会 `viewModelStore.clear()` → `viewModelScope` 取消。`sendText` 的 `catch (CancellationException) throw` 不会写 `draftRestore`，界面输入框在点发送时已清空。结果：RPC 若未到达服务端，文本与内存中的附件无任何副本可恢复。无测试、无持久化草稿。位置：`ui/nav/AppNavHost.kt:96-97,432-450`；`ChatViewModel.kt:453-488`。
2. **P0｜附件描述符被静默丢弃。** `uploadAttachments` 里 `if (descriptor is JsonObject) descriptors += descriptor`（`ChatViewModel.kt:547`）：`attachmentPut` 返回 null/字符串/错误信封时，该附件**不进消息、也不报错**，而发送成功后 `attachments - uploaded.items` 会把它从输入区移除。用户看到"已发送"，桌面上却没有这个附件。无测试。
3. **P1｜附件选择先整读再校验大小 → OOM 崩溃。** `ChatScreen.readPickedFile` 在 `Dispatchers.IO` 上 `readBytes()` 全量读入，之后 `attach()` 才校验 30MB（`ChatViewModel.kt:412-428`）；10 个附件上限意味着最坏 ~300MB 常驻 + Base64 再膨胀 1.33 倍。`OutOfMemoryError` 不是 `Exception`，现有 `catch (e: Exception)` 接不住。无测试。
4. **P1｜发送路径绕过 `ensureReady()`。** `sendText` 不调用 `ensureReady`（只有 `refresh`/`openWorkspace` 调）；relay 进入 `Error/Kicked/Closed` 终态后，注册表句柄的 `sessionSerial` 未变，`handleFor` 仍返回旧句柄 → 发送对着死桥等到 35s 超时，而不是像刷新那样重连。位置：`ChatViewModel.kt:439-488` vs `1014-1075`。无测试。
5. **P2｜删除任务先删缓存再发 RPC，失败不回填。** `deleteTask()` 先 `cache.remove`，随后乐观把 `selectedTaskId/rows` 清空；RPC 失败只回滚任务列表，选中态与时间线不恢复，缓存也已删除。位置：`ChatViewModel.kt:1216-1246`、`mutateTask` `1304-1350`。无测试。
6. **P2｜`refreshConversationState()` 吞掉所有快照错误。** `runCatching{}.getOrNull()` 失败直接 return（`ChatViewModel.kt:1489-1495`）：桥被关/降级时 `isRunning`、`queueItems`、`config` 停留在旧值，可能出现"一直显示运行中/停止按钮"的假状态。无测试。
7. **P2｜`enter()` 的 bootstrap 错误会被随后的 `openWorkspaceInternal` 覆盖。** `openWorkspaceInternal` 起始就把 `error = null`（`ChatViewModel.kt:1088-1102`）；若 bootstrap 失败但开桥成功，用户看不到"列表拉取失败"，只看到空/旧工作区。无测试。

---

## 3. ChatScreen / HomeScreen 是否真正呈现新状态

### 3.1 已呈现

| 状态 | 位置 | 结论 |
|---|---|---|
| `error` | `ChatScreen.kt:207-209` + `ChatErrorBanner`；`HomeScreen.kt:117-124` | ✅ 有错误条；Chat 的"重试"= `refresh()`（语义错误，见 3.3） |
| `draftRestore` | `ChatScreen.kt:160-164`（`LaunchedEffect` 回填；非空时把失败文本拼在用户新输入前）+ `onDraftRestored` | ✅ 回填链路完整；未测试 |
| `loading` | Chat 顶部 1dp 进度条 `199-205`；Home 下拉刷新 + 空态 spinner `133-145,620-664` | ✅ |
| `sending` / `uploading` / `uploadIndex` / `uploadProgress` | `Composer` 参数 `233-268`；附件进度环 `771-838` | ⚠️ `sending` 只让发送按钮变灰（`938-947`），**没有"发送中"文案/spinner**；上传进度有环 |
| `attachments` | 输入区附件卡片 + 删除按钮 `771-838` | ✅ |
| busy（任务 mutation） | Home `TaskRow` 转圈 `537-544` | ✅ Home；❌ Chat 的置顶/归档/删除/改名**无任何 busy 态、按钮不禁用** |
| 未读 | Home 未读点 `580-585` + 未读自动展开 `HomeUiState.tasksOf` | ✅ |
| 置顶 | Home "已置顶"区 `148-177` + 行内 pin 图标 `586-591`；Chat 菜单文案随 `pinned` 切换 `390-406` | ✅（Chat 标题栏无 pin 徽标，仅菜单文案） |

### 3.2 ViewModel 有、UI 不读（孤儿字段）

| 字段 | 定义 | 生产 UI 读取情况 | 影响 |
|---|---|---|---|
| `ChatUiState.bridgeDegraded` | `ChatViewModel.kt:187`，由 `BridgeDegraded/Recovered` 维护 | **0 处**（仅 HomeScreen 读自己的同名字段） | 对话页桥降级不可见 —— 用户不知道为什么发不出去 |
| `ChatUiState.relayFailure` | `:185` | **0 处** | 掉线原因（`invalid-mobile-connection` 等可行动文案）在 Chat 页丢失 |
| `ChatUiState.relayFailureVisible` | `:241-242`，专门为"界面该不该显示 relay 失败"而写 | **0 处生产读取**（只有 `ChatViewModelTest.kt:536,542` 断言） | 设计意图与实现脱节：这个字段就是为 UI 准备的，UI 没用 |
| `ChatUiState.credentialsMissing` | `:189` | **0 处**（Chat 只在 `enter()` 里用它拼 error：`975-978`） | 作为状态字段无人消费；Home 用 `relayState != Paired` 近似代替 |
| `ChatUiState.conversationEvents` | `:195` | 0 处（调试用，可接受） | — |
| `ChatUiState.totalCount` / `historyExhausted` | `:198,205` | 0 处 UI；`historyExhausted` 由 VM 内部用 | 可接受，但 `totalCount` 完全不参与渲染，属于冗余状态 |
| `HomeUiState.relayFailure` | `HomeViewModel.kt:63` | 0 处 UI（`error` 已含翻译后文案） | 可接受 |

补充：`ChatScreen` 完全不读 `relayState`，所以"重连中/已被接管/已断开"在对话页没有任何视觉反馈；这是本次验收里最直接的"状态没落地"。

### 3.3 UI 语义问题

1. **Chat 错误条的"重试"永远是 `refresh()`**（`AppNavHost.kt:343` 传 `viewModel::refresh`）。发送失败 / 附件失败 / 删除失败后点"重试"只会刷新工作区列表，**不会重发消息、不会重试上传、不会重试删除**。用户会认为已经重试过，实际什么也没发生。
2. **Chat 任务菜单在无选中任务时仍显示**（新对话、刚删完），点击走 `mutateTask` 的 `workspace ?: return` / `task ?: return` 静默 no-op（`ChatViewModel.kt:1304-1307`）。round3 P2-10 仍未修。
3. **`sending` 期间无取消入口**：`stop` 只在 `running && 输入为空` 时显示（`ChatScreen.kt:920-936`），createSession 的 135s 等待期用户既不能取消也看不到进度。

---

## 4. 会掩盖 bug 的假实现

| Fake | 问题 | 会掩盖什么 |
|---|---|---|
| `FakeChatOps`（`UiTestSupport.kt:504-521`） | 所有 op 共用同一个 `result`；`failure` 是全局的 | ① 把 `createSession` 的返回值误当成 `sendText` 的结果也能过（`ChatViewModelTest.kt:287` 就是 `result = "new-1"` 被所有 op 共享）；② 无法构造"只有 `attachmentPut` 失败"或"`createSession` 成功但 `sendText` 失败"；③ `scopes` 记录了但无断言，scope 传错（真实 bug 高发点）测不出来 |
| `FakeEnginePort.conversationSnapshotResult = JsonNull` | 默认无快照 | `refreshConversationState()` 在**所有** ViewModel 单测里第一行就 `return`；`config/isRunning/queueItems/pendingInteractions` 的合并逻辑、以及 `putSendSubmission` 依赖的 config 读取全部**没有被执行过**。把整个函数删掉，394 例照样全绿 |
| `FakeEnginePort.bootstrapResult = JsonNull` | 默认无工作区 | 进页路径的"空快照"分支只测过一次（`合法的空快照会清空投影`），bootstrap 与 route 工作区合并的真实组合覆盖很薄 |
| `FakeEnginePort.openedBridgeIntents` | 记录了 `(key, taskHostId, project)` 却**从未断言** | `taskHostId`/`project`（验收 3/4 的核心）传错、传反、漏传都测不出来；`ConversationRegistryTest` 也只断言 `openedBridges`（key,taskId） |
| `FakeEnginePort.waitHealthyFailure` | 有开关，**0 次使用** | `registry.ensure` 里 `waitHealthy` 失败不缓存/可重试的分支没测 |
| `FakeEnginePort.openBridge` 恒定成功 + `sessionSerial` 恒 0 | 真实引擎的"换 URL 重建 → 旧句柄失效"由 `ConversationRegistryTest.handleForIgnoresStaleEngineSession` 用匿名包装补上了（✅），但 ViewModel 层仍无法模拟 | Home/Chat 在引擎重建后仍用旧 `selectedTaskId/rows` 的行为无测试 |
| `FakeDeviceRepository` | `readFailure` 存在但 Chat/Home 都不用 | `repository.devices` 抛错 → `collect` 的 catch 只置 `loading=false + error`，没有测试保证不崩、不卡 loading |
| `HomeViewModelTest` 的 `channelCallResult` 单一值 | 同一个 JSON 同时回应 `project.readTree` 与 `remote-projects.readDirectory` | ① channel/method 路由错误（例如把 readDirectory 的参数发给 readTree）测不出；② readTree 的 `[{...}]` 参数形状、`hostId` 分流、online 过滤全部不可断言；③ 远端 host 用例实际上走的是"hosts 字段缺失 → 空列表" |
| 无 Compose/UI 测试（仓库无 `androidTest`） | 所有"UI 是否呈现"结论只能靠静态阅读 | 回填 `LaunchedEffect`、错误条、busy/未读/置顶的渲染回归无法自动发现 |

---

## 5. 必须补的最小测试清单（6 条，按优先级）

> 前 3 条对应"用户能感知的丢数/验收不成立"，后 3 条对应"协议关键字段无人守"。建议在同批把 `FakeChatOps` 改成 `Map<op, JsonElement>` + `failOn: Set<String>`，否则 2/3 条写不出来。

| 优先级 | 测试 | 断言要点 | 对应 §10 |
|---|---|---|---|
| **P0-1** | `ChatViewModelTest`：发送失败回填草稿并保留附件 | 先 `attach()` 再 `selectTask("t1")`；`chatOps.failure = engineError("timeout")`；`sendText("你好")` 后断言：`error != null`、`draftRestore == "你好"`、`attachments.size == 1`、`sending == false`；`consumeDraftRestore()` 后为 null；再成功发送一次断言附件被清空 | 5 |
| **P0-2** | `ChatViewModelTest`：createSession 成功、首条 sendText 失败 → 重试不重复建会话 | `FakeChatOps` 按 op 返回：`createSession → "sess-1"`、`sendText` 第一次抛错第二次成功。断言：第一次失败后 `selectedTaskId == "sess-1"`、`draftRestore` 有值；第二次 `sendText` 后 `createSession` 调用次数恒为 1，`sendText` 调用次数为 2 | 5 |
| **P0-3** | `ChatViewModelTest`：开桥意图逐字段 | 构造 `initialHostId="remote:server:s1"` + `initialProjectId="p1"`（taskId 有/无各一次），进页/发送后断言 `FakeEnginePort.openedBridgeIntents`：任务路径 = `(key, hostId, null)`；新建路径 = `(key, null, {"projectId":"p1","hostId":...})`；且两者都不出现 `taskId/project` 同时给 | 3、4 |
| **P1-4** | `ChatViewModelTest`：rowsUpdated 快照/增量与会话过滤 | emit `ConversationEvent(type="rowsUpdated", sessionId=别的)` → `rows` 不变；emit 当前 session 的 snapshot（含 `totalCount/logEpoch/control.phase=running`）→ `rows` 合并、`logEpoch` 落位、`isRunning=true`；再 emit deltas（`row.*` + `state.updated.patch`）→ 行与 `queue.autoDrain/pauseReason` 正确 | 3 |
| **P1-5** | `HomeViewModelTest`：readTree 参数形状 + 远端 host 路由（先把 Fake 按 `channel/method` 分发） | 断言 `channelCalls` 中存在 `project/readTree` 且 `args[0]` 恰为 `{includeDeleted:false, includeDeletedTasks:true}`、`args.size==1`；`remote-projects/readDirectory` 的 args 为 `[]`；`hosts` 里 `connectionStatus!="online"` 的 host **没有** readTree；远端任务 key = `hostId + ":" + "/路径/"` 归一化结果；bootstrap 为空时仍能以现有桥触发一次本地 readTree（P2-9 回归） | 1 |
| **P1-6** | 新增 `HomeUiStateTest`（纯逻辑）：置顶/未读/归档派生 + 回滚 | `pinnedTasks` 不含 archived；`tasksOf`：未读自动展开只列未读、`collapsed` 时为空、显式展开列全部且排除 pinned/archived；`canShowAll` 判据；再补一条 `HomeViewModel.markRead` 失败（`channelCallFailure`）时 `unreadAt` 回滚的断言 | 2、7 |

可选第 7 条（超出上限，记 backlog）：`ChatScreen` 的 Compose 测试（`draftRestore` 回填、错误条"重试"语义），需要先引入 `androidTest` 基建。

---

## 6. 附：实测与限制

- 命令：`cd android && ./gradlew testDebugUnitTest --console=plain -q`。
- 结果：`35 suites / 394 tests / 0 failures / 0 errors / 0 skipped`，耗时 6.665s；报告 `E:\Zcode_android\android\app\build\reports\tests\testDebugUnitTest\index.html`（17:34:11）。
- 本轮审查只读，未改任何源码/测试；临时构建日志已删除。
- 未验证项（明确标注）：① 桌面端对"同 host 两条 transport"的实际降级行为（无设备/桌面环境）；② `sendText` 在 `Killed/Error` 终态下的真实等待时长（依赖核心定时器，仅有宿主兜底 35s 的代码证据）；③ 远端 host 的 `connectionStatus` 取值集合（按 round2 文档与代码一致处理）。
- 与既有文档的关系：本报告的 P2-9（空 bootstrap 不拉项目树）、P2-10（无任务菜单仍显示）、P2-11（Home deviceId 被首个设备覆盖）在 `round3/03-ui-states.md` 已记录，本次复核仍成立；`round3` 标记的 P1-1（Chat 删除不清 selectedTaskId/rows）与 P2-4（发送成功清空全部附件）在 17:26 的改动中已修，但**两者都没有测试**，属于"修了但无回归保护"。
