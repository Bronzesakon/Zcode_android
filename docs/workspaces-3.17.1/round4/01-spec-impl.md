# Round4 · 规格 ↔ 实现一致性核对

> **方式**：只读静态审计，未修改任何源码，未执行构建/单测（结论均为源码阅读，未在真机验证）。
> **规格基线**：`docs/workspaces-3.17.1/WORKSPACES-FINAL.md`（2026-10-30 17:09）。
> **实现快照**：当前工作树（`android/app/src/main/kotlin/...`、`rust/crates/{ffi,protocol}/src/...`）。
> 行号以本次读取为准：`HomeViewModel.kt` 1036 行、`ChatViewModel.kt` 1985 行、`ConversationRegistry.kt` 204 行、
> `ChatOps.kt` 154 行、`SessionsIndexProjection.kt` 86 行、`AppNavHost.kt` 456 行、`AppContainer.kt` 397 行、
> `NotificationCoordinator.kt` 318 行、`ZemoteEngine.kt` 921 行。
> **严重度口径**沿用 round3：P0 = 污染数据/主流程不可用；P1 = 用户可感知、无自愈；P2 = 保真度/体验/可维护性。

---

## 0. 结论摘要

- **round3 P0：1 条已加门禁（残留问题见 §2.4）。round3 P1 共 14 条（01×6 + 03×8）：已修 7 / 部分修 2 / 未修 5**，
  其中 2 条未修项与规格明文冲突（01-P1-1 串行化、01-P1-6 非 tab 不订阅）；02 的 D1 半修（key 已用权威值、taskId 未用）。
  本轮另新增 1 条 P1（§2.4）。
- **核心验收项（规格 §10）**：项目树、任务打开、新建对话、失败回填、同工作区单 transport 均满足；
  **「bootstrap 为空时项目树不出现」仍未修**（§1.1-I），**非 tab 项目无实时活动**属规格内取舍（§5）。
- **可交付判断**：**有条件可交付（内部构建可以，对外发布建议先修 §2.4 + P1-8 + P1-6）**；
  剩余问题集中在「多 Chat 叠栈的事件门禁」「通知深链缺上下文」「Chat/Home 任务元数据分叉」。

---

## 1. 不一致

### 1.1 实现与规格条款不一致

| # | 规格 | 实现 | 证据 | 严重度 |
|---|---|---|---|---|
| A | §2 L88：`bridge.initialTaskId` 会覆盖请求 `taskId` 用于 `send_mobile_view_state`，**宿主不要再用请求值覆盖回去** | 权威 **key** 已用 `handle.effectiveWorkspaceKey`；但 **taskId 仍用请求值**：`engine.sendMobileViewState(handle.effectiveWorkspaceKey, taskId)`；非注册表兜底更是 `sendMobileViewState(workspaceKey, taskId)`（请求 key + 请求 taskId）。`Handle` 未暴露 `initialTaskId` | `ChatViewModel.kt:1149`、`:1158`；`ConversationRegistry.kt:51-57` | P2（仅在桌面改写 initialTaskId 时可感知，round3 02 D1 后半未修） |
| B | §2 L85-87：`conversation_open` 的 scope 必须用 bridge-ready 的权威 `workspacePath / workspaceIdentity` | 核心 snapshot 的 `bridges[]` **不投影** path/identity，Kotlin 只能用字符串反推（`remote:` 前缀 + 第一个 `/` 切分）。远端 identity 格式一变就会把错误路径当 cwd | `rust/crates/ffi/src/session.rs:167-182`；`ConversationRegistry.kt:160-173` | P2（round3 02 D2 未修） |
| C | §3.1 L97：`createSession.workspaceId = 权威 workspaceKey` | 主路径正确（`handle.effectiveWorkspaceKey`），但兜底链第三项是 `_state.workspaceId`——该值跨工作区不清空（`workspaceId = workspaceId ?: current.workspaceId`），可能来自上一个工作区 | `ChatViewModel.kt:717-719`、`:1534` | P2（round3 01 P2-11 未修） |
| D | §2 表第 4 行 / §3.2 L115：无 projectId 的 legacy 项目「不要在这里新建对话」 | 拦截条件 `!selected.bridgeable && selected.projectId == null`；而 Chat 的 `bridgeable` 是 `initialHostId == null` 推导出来的（`ChatViewModel.kt:313`），local 项目若 `hostId` 缺失（readTree 行不带 hostId 时）会被判成 bridgeable，守卫不生效，仍会发出裸 `openBridge` | `ChatViewModel.kt:300-318`、`:729-742`；`AppNavHost.kt:189-190` | P2（round3 01 P2-4 未修；依赖 §6 UNVERIFIED-1） |
| E | §4.1/§4.2 L123-131：任务**存在性**与基底永远是 `readTree.tasks`，sessions-index 只叠活动字段 | Chat 页没有 readTree：直接把 sessions-index 当基底**重建整个任务列表**（`merged = indexedTasks.map {...}`），pinned/archived/unreadAt 只从旧值回贴；首次从通知直进任务时 pinned/archived 恒 false。 | `ChatViewModel.kt:1500-1528`（无 `readTree` 调用） | **P1**（round3 03 P1-6 未修） |
| F | §4.2 L131：索引里没有的任务保留（readTree 基底） | Chat 的 extras **只保留 `selectedTaskId`**（`:1526-1528`），其余索引缺席任务被删；反过来当索引列表为空时**整段跳过合并**（`:1508-1509`），桌面已删任务永久残留 | `ChatViewModel.kt:1508-1509`、`:1524-1528` | P1/P2（round3 03 P1-2 半修） |
| G | §4.2 L131：标题按 `titleSource==='custom' ? 条目标题 : 条目标题或旧标题` | Home 符合；**Chat 恒取索引标题**，不使用 `titleSource`、不回退旧标题 → 索引标题为空时 header 回落「新对话」 | `ChatViewModel.kt:1516-1522`；对照 `HomeViewModel.kt:813-819` | P2（round3 03 P2-13 未修） |
| H | §4.3 L139：打开任务时带 `expectedUnreadAt` | 只有首页任务行打开路径调用 `markRead`（带 CAS）；**通知深链进入不 markRead** | `AppNavHost.kt:157-159` vs `:104-119` | P2（round3 01 P2-10 未修） |
| I | §5 L156：首页下拉刷新清空预览订阅表并重拉 readTree；§10 验收 1：首页出现全部项目 | 逻辑本身正确；但 `loadProjectTree` 只在 `ensurePreviews` 内、且只在**成功订阅一个 bridgeable 工作区之后**触发。若 bootstrap 为空（桌面没开 tab），项目树永不加载 | `HomeViewModel.kt:456-517`（`loadProjectTree` 在 `:508`，`!bridgeable` 在 `:469` 直接 return） | P2→**P1 边缘**（round3 03 P2-9 未修） |
| J | §4.4 L144：删除任务同时删 `ChatCache` 对应行 | Home 用 `workspace.key` 删；Chat 保存/读取用的是**路由 key（任务自己的 key）**。多根项目的非主根任务、远端 identity 任务两者不同 → Home 删除清不到 Chat 写的那一行 | `HomeViewModel.kt:300`；`ChatViewModel.kt:1719`、`:1124` | P2（缓存残留；不影响主流程） |
| K | §5 L153：订阅失败（非 timeout）不等于桥不可用 | 符合；但订阅失败后 `Handle` 仍被缓存，Home 也把它登记进 `previewSubscriptions`；之后 `registry.ensure` 永久短路，**该工作区的 sessions-index 订阅永远不会重试** | `ConversationRegistry.kt:107-124`；`HomeViewModel.kt:499` | P2（该工作区预览/未读永久缺失，直到引擎重建） |
| L | §2 L71/§3.1 L99：`project` 与 `taskId` 互斥、`taskHostId` 必须伴随 `taskId` | Kotlin 侧互斥正确；Rust FFI 仍**不做组合校验**，非法组合会整帧上线后被桌面 zod 拒（表现为桥永远不 ready） | `Kotlin: ChatViewModel.kt:852-866`；`rust/crates/ffi/src/codec.rs:941-952` | P2（round3 02 D3 未修，防御性） |
| M | §3.1 L104：`modelSelection` 来自会话 config，新会话无 config 时省略 | 正确（provider/model 空白整块不发）；但无 config 时仍强制 `mode="build" + planEnabled=false`，会把工作区默认 plan 模式顶掉（web 的语义是整块不发） | `ChatViewModel.kt:883-895` | P2（round3 02 D5 未修；规格未明文要求省略） |
| N | §3.1 L103：`plan` 归一化成 `build + planEnabled:true` | 实现恒发 `planEnabled`（含 true）；与 round3 02 P2-2/D7（按 `independentPlanState` 能力裁剪）相反。**规格明文支持发送，故不算实现缺陷**，但能力门禁缺失仍是 UNVERIFIED | `ChatViewModel.kt:895` | P2（规格与 round3 建议冲突，记录待产品决策） |

**规格自身的两处模糊（实现无法同时满足）**

1. §4.1 L123 说 `title`（含 `titleOverridden`）权威在 readTree；§4.2 L131 又说非 custom 时用索引标题。Home 按 §4.2 实现，
   于是「刚在 Chat 改名、索引还没把 `titleSource` 变成 custom」的窗口内，标题会被索引旧值顶回去（round3 P2-13 的 Home 半边）。
2. §5 L154 要求「所有建桥/开对话串行化（注册表 Mutex）」，而 round3 01 P1-1 要求「per-key 互斥 + 短临界区」。
   当前实现是全局 Mutex 包住 `openBridge → waitHealthy → conversationOpen → subscribe` 全程
   （`ConversationRegistry.kt:82-125`），**符合规格**，但首屏 13 个工作区串行、对话页操作排队的可用性问题仍在（见 §2.1 备注）。

### 1.2 规格没写、但实现做了

| # | 实现 | 证据 | 评价 |
|---|---|---|---|
| a | Home 把 sessions-index 里有、readTree 里没有的任务**加回**列表（`additions`） | `HomeViewModel.kt:825-827` | 与 §4.2「基底永远是 readTree」相悖；正是 round3 P2-2 的「已删任务复活」通道 |
| b | Chat 用 sessions-index 重建任务列表（见 §1.1-E） | `ChatViewModel.kt:1516-1528` | 与 §4.1/§4.2 的分工相反；Chat 的 pinned/archived 只在 `old != null` 时可保留 |
| c | Chat 删除任务后立即清空 `selectedTaskId / rows / logEpoch` 等 | `ChatViewModel.kt:1216-1247` | 合理增强（规格 §4.4 只要求删缓存、不 close 对话） |
| d | Chat 在重试 `sendText` 前自动补订阅（`subscribedSessionId`） | `ChatViewModel.kt:456-458`、`:831-844` | 符合 §3.2 精神（规格只说「重进页面会补快照」，实现更稳） |
| e | Home `mergeWorkspaceSnapshot` 用 `projectTreeWorkspaces` 补偿工作区 | `HomeViewModel.kt:529-535` | 规格未描述，但 §1.3「避免任务消失」需要它 |
| f | 通知总控改为由 `sessionsIndexUpdated / stateChanged` 事件驱动读会话快照 | `NotificationCoordinator.kt:167-180`、`:278-306` | 规格完全不覆盖通知；属 round3 P1-4 的修复实现 |
| g | `SessionsIndexProjection` 读 `provider` | `SessionsIndexProjection.kt:79` | §4.1 明说索引**不含** provider（读不到恒 null，无害但属多余解析） |
| h | Home 用预览桥兼作 `zcode-task` 的 mutation 桥 | `HomeViewModel.kt:499-501` | 规格 §4.4 允许「用哪条桥都可以」 |
| i | Chat 维护 200 条 `conversationEvents` 调试环 | `ChatViewModel.kt:1804-1813` | UI 未消费（见 §4-7） |
| j | Chat `workspaceId` 从索引首项取并跨工作区保留 | `ChatViewModel.kt:1497`、`:1534` | 正是 §1.1-C 的兜底来源，规格未授权 |

---

## 2. 未修 P0 / P1（逐条核对 round3）

### 2.1 round3 01（端到端实现）

| 条目 | 状态 | 核对证据 |
|---|---|---|
| **P0-1** 对话事件不分 session/key | **已修（门禁存在）**，但引入新问题，见 §2.4 | `ChatViewModel.kt:1787-1794`：`event.session == bridge && event.key == conversationKey`；`rowsUpdated` 另有 `sessionId != selectedTaskId` 过滤（`:1548-1550`）；`attachmentProgress` 同样过滤（`:1817-1821`） |
| **P1-1** 注册表全局 Mutex 串行 | **未修**（**规格 §5 L154 明文要求串行化**，属性能问题而非规格违背） | `ConversationRegistry.kt:82-125` 全程持锁；`HomeViewModel.kt:459-515` 的 `previewSetup` 仍把 `loadProjectTree` 包在锁内 |
| **P1-2** Chat 收到 workspace-list 丢失路由工作区 | **已修** | `ChatViewModel.kt:1753-1772`：合并后若缺 route 则 `+ route`（保留旧 tasks） |
| **P1-3** `mergeSessionsIndex` 遍历可变 HashMap | **已修** | `HomeViewModel.kt:784`：`previewSubscriptions.toList()` |
| **P1-4** 通知拿不到任务 | **部分修复**：数据源已换 sessions-index；但 `onAttention` 仍只有 Chat 页调用，后台待交互提醒不触发；未被订阅的工作区（非 tab 项目）仍无运行卡 | `NotificationCoordinator.kt:167-180`、`:278-306`、`:92-114`；唯一调用点 `AppContainer.kt:279-281` |
| **P1-5** `engine.events` 共享 `tryEmit` 512 缓冲、三消费者丢帧 | **未修** | `ZemoteEngine.kt:151-160`；`EnginePort.kt:487`（第二次 tryEmit）；消费者 `HomeViewModel.kt:957-961`、`ChatViewModel.kt:1748`、`NotificationCoordinator.kt:75` |
| **P1-6** 非 tab 项目不订阅 sessions-index | **未修**（**规格 §5 L155 明文规定首页不订阅**，属规格内行为；功能缺口需产品决策） | `HomeViewModel.kt:465-469`；`loadProjectTree` 未解析 `phase/hasBackgroundWork` |
| P2-1 `refreshConversationState` 空转 | 已修（`ensureRouteBridge` 先执行，key 已就位） | `ChatViewModel.kt:747`、`:787` |
| P2-2 `planEnabled` 恒发 | 未修；**规格 L103 支持此行为** | `ChatViewModel.kt:895` |
| P2-3 宿主兜底未计 waitHealthy 45s | **已修** | `ZemoteEngine.kt:699-706`（`+DEFAULT_WAIT_HEALTHY_TIMEOUT_MS`；实际 createSession 最坏 140s） |
| P2-4 legacy 工作区仍给「新建对话」 | 未修（首页 `+` 无条件渲染；Chat 守卫有 §1.1-D 的漏判） | `HomeScreen.kt:400`；`ChatViewModel.kt:729-733` |
| P2-5 `sendMobileViewState` 请求 key | **key 已修**（`effectiveWorkspaceKey`），taskId 未修；非注册表兜底仍用请求 key | `ChatViewModel.kt:1149`、`:1158` |
| P2-6 `handleFor` 不校验 sessionSerial | **已修** | `ConversationRegistry.kt:128-132` |
| P2-7 readTree 只跑一次 | 未修（仅下拉/引擎重建重跑） | `HomeViewModel.kt:168-169`、`:387-392`、`:502-508` |
| P2-8 mutation 整表回滚 | 半修：回滚保留期间新合并的任务，但同 taskId 的其他乐观改动仍会被覆盖 | `HomeViewModel.kt:885-895`；`ChatViewModel.kt:1350-1363` |
| P2-9 title 为空仍发送 `""` | 未修 | `HomeViewModel.kt:869`；`ChatViewModel.kt:1337` |
| P2-10 通知进入不 markRead | 未修 | `AppNavHost.kt:104-119` |
| P2-11 `workspaceId` 跨工作区不清空 | 未修 | `ChatViewModel.kt:717-719`、`:1534` |
| P2-12 无去抖/单飞 | 未修 | `ChatViewModel.kt:1829-1833`；`HomeViewModel.kt:958-959` |
| P2-13 标题合并方向 | Home 按规格（§4.2）实现；Chat 未用 titleSource（§1.1-G） | `HomeViewModel.kt:813-819`；`ChatViewModel.kt:1516-1522` |
| P2-14 `close/closeAll` 死代码 | 未修 | `ConversationRegistry.kt:181-203`；仅测试调用 |
| P2-15 `HomeViewModel.unread` 死代码 | 未修 | `HomeViewModel.kt:906-908` |

### 2.2 round3 02（线协议字段）

| 条目 | 状态 | 核对证据 |
|---|---|---|
| D1 请求值覆盖权威 view state | **半修**：key 用权威值；**taskId 仍用请求值**；兜底路径仍用请求 key | `ChatViewModel.kt:1149`、`:1158` |
| D2 snapshot 的 `bridges` 缺 path/identity | **未修** | `rust/crates/ffi/src/session.rs:167-182`；`ConversationRegistry.kt:160-173` 仍字符串反推 |
| D3 Rust 不校验 project/taskId 组合 | **未修** | `codec.rs:941-952` |
| D4 mode 未按枚举校验 | **已修**（build/edit/yolo/guarded，其余回落 build） | `ChatViewModel.kt:890-893` |
| D5 新会话无 config 仍发 mode/planEnabled | **未修** | `ChatViewModel.kt:883-895` |
| D6 三处新字段无最小检查 | **部分补**：codec 有 `taskHostId/project` 用例（`codec.rs:2125-2168`）；`send_text_payload` 有断言（`conversation.rs:2691-2721`）；transport 层**仍无** mode/planEnabled/modelSelection 透传断言（`conversation_transport.rs:5280-5318` 的用例不含三键） | 同左 |
| D7 `planEnabled` 能力门禁 | 未修；**规格 L103 与 round3 建议相反** | `ChatViewModel.kt:895` |

### 2.3 round3 03（UI 状态）

| 条目 | 状态 | 核对证据 |
|---|---|---|
| **P1-1** Chat 删除后不清理选中态 | **已修** | `ChatViewModel.kt:1216-1247`（清 `selectedTaskId/rows/totalCount/firstRowId/logEpoch/historyExhausted/canLoadOlder`，并清 `subscribedSessionId`） |
| **P1-2** Chat 索引合并永不删任务 | **半修**：索引非空时以索引为准移除（extras 只剩 selected）；索引为空时仍整段跳过 | `ChatViewModel.kt:1508-1509`、`:1524-1528` |
| **P1-3** createConversation 与进页竞态 | **已修** | `ChatViewModel.kt:1109-1112`：`selectedTaskId = taskId ?: it.selectedTaskId` |
| **P1-4** 订阅失败重试不重订阅 | **已修** | `ChatViewModel.kt:458`：`if (subscribedSessionId != sessionId) subscribeConversation(sessionId)` |
| **P1-5** workspace-list 丢路由工作区 | **已修**（同 01-P1-2） | `ChatViewModel.kt:1753-1772` |
| **P1-6** Chat mutation 不回写 Home；Chat pinned 不持久 | **未修** | Chat 仍只改自己的 `_state.workspaces`（`ChatViewModel.kt:1304-1319`），无跨 VM 通道；Chat 无 readTree |
| **P1-7** draftRestore 覆盖新输入 | **已修**：改为「空则回填，否则失败文本在前、保留新输入」；VM 拒绝发送时也会写 `draftRestore` | `ChatScreen.kt:160-164`；`ChatViewModel.kt:443-447` |
| **P1-8** 通知深链缺 path/identity/hostId/projectId | **未修** | `NotifyTarget` 仍只有 2 字段（`AppContainer.kt:379-382`），`onNotifyIntent` 只取 2 个 extra（`:122-132`），导航只 push 2 字段（`AppNavHost.kt:107-114`） |
| P2-1 并发 mutation 回滚互相覆盖 | 半修（见 01-P2-8） | 同 §2.1 |
| P2-2 Home additions 复活已删任务 | 未修 | `HomeViewModel.kt:825-827` |
| P2-3 Chat `refresh()` 丢已合并任务/pinned | **未修**：`refreshInternal` 仍用 bootstrap 重建 + 仅补 route 骨架 | `ChatViewModel.kt:1075-1091` |
| P2-4 发送中可增删附件/成功清空 | 半修：成功后只减本次上传项（`ChatViewModel.kt:471-473`、`:805-807`），但 `+`/`−` 仍不禁用（`ChatScreen.kt:825-836`、`:878-885`），下标寻址未改 |
| P2-5 无去抖/序号 | 未修 | 同 01-P2-12 |
| P2-6 `previews/busyTasks` 只按 taskId | 未修 | `HomeUiState` `previews: Map<String,String>`、`busyTasks: Set<String>`（`HomeViewModel.kt:73`、`:79`）；`SessionsIndexProjection.previews` 按 sessionId |
| P2-7 index-only 任务缺 path/identity/hostId | 未修 | `SessionsIndexProjection.kt:71-84`；`AppNavHost.kt:172-175` 兜底主根 |
| P2-8 非 bridgeable 无实时活动 | 规格内（§5 L155）；`loadProjectTree` 也未解析 phase/background | `HomeViewModel.kt:465-469`、`:666-681` |
| P2-9 bootstrap 为空不加载项目树 | 未修（见 §1.1-I） | `HomeViewModel.kt:502-508` |
| P2-10 无任务时 header 菜单仍显示 | 未修 | `ChatScreen.kt:381-408`（菜单无条件渲染） |
| P2-11 Home `deviceId` 被 `devices.firstOrNull()` 覆盖 | 未修 | `HomeViewModel.kt:990-1001`；`resolveDevice` `:445-448` |
| P2-12 未读自动展开打开后折叠 | 未修 | `HomeUiState.isExpanded` `:99-100`；`AppNavHost.kt:157-159` 只 markRead 不写 `expanded` |
| P2-13 标题（Chat 半边） | 未修（§1.1-G） | `ChatViewModel.kt:1516-1522` |
| P2-14 测试缺口 | 未修：仍**无** `HomeViewModelTest`；`ChatViewModelTest` 无 draftRestore/重订阅/删除清态/route 保留断言（现有 43 个用例里没有这些场景） | `android/app/src/test/kotlin/app/zemote/ui/home/` 不存在；`ChatViewModelTest.kt` 用例清单 |

### 2.4 本轮新发现（Round3 修复引入）

**P1｜Chat 事件门禁的桥身份来自「全局最近一次 openBridge」，会被别的页面覆写 → 叠栈返回后会话帧全部被丢弃**

- 门禁读取 `_state.value.bridgeSession`（`ChatViewModel.kt:1790-1794`），但该字段同时被两条来源写：
  1. 本页的 `ensureRouteBridge` 写自己的 handle（`:861-864`，正确来源）；
  2. `collect(engine.bridgeSession)` 无条件镜像**全局**「最近一次 openBridge」的会话号（`:1744`）。
- `EnginePort.bridgeSession` 的语义就是全局 last（`EnginePort.kt:77-79`、`:396-405`），任何工作区开桥都会更新它。
- 复现路径：首页点任务 A 进 Chat（A 的 `viewModelScope` 存活）→ 点通知/再次进入任务 B（第二个 `Route.Chat` 压栈，
  `NavEntryStores` 保留栈内全部条目，`AppNavHost.kt:96-97`）→ B 经注册表 `openBridge` 把全局 bridge 改成 B →
  返回 A：A 的 `_state.bridgeSession` 是 B 的桥，`event.session == bridge` 对 A 自己的帧不成立 → **A 不再收到任何
  `rowsUpdated`**；`refreshConversationState`（`:1484-1489`）也用错桥，状态不再更新。只有再发一条消息（走
  `ensureRouteBridge` 把字段写回自己的句柄）才恢复。
- 同一门禁还有一处放行口：`bridge == null` 时全部放行（`:1790-1793`）。引擎重建后 `engine.bridgeSession` 被置空
  （`EnginePort.kt:474`）直到本页再次 ensure，这期间别的桥的事件会进入本页 `conversationEvents`
  （`rowsUpdated` 仍有 sessionId 过滤，**不污染行/缓存**，仅污染调试事件环）。
- 结论：round3 P0-1 的「防串流」目标达成，但**门禁身份应与本页句柄（registry handle）绑定并只由本页写**，
  不能复用全局 last-bridge 镜像。**这是当前最高优先级残留（P1，无数据损坏）**。

**P2｜`syncTasks` 的 `removed` 只按「上一帧列表里消失」判定，任务从索引消失但工作区列表没更新时不会撤卡**
（`NotificationCoordinator.kt:200-239`；实际随 `tasksByWorkspace` 更新会走 `syncTrackedTasks`，多数场景可撤，但
`stateChanged` 不刷新任务集，属边界）。

**P2｜`ChatAttachment` 的 `equals` 含字节、`hashCode` 只含 name+mime**（`ChatViewModel.kt:162-167`），
作为 Set/HashMap 键时违反契约；目前只用于 `List.minus(Set)`（`:471`、`:805`），暂无可见后果。

---

## 3. 同一事实在两处实现不一致

| # | 事实 | 实现 A | 实现 B | 后果 |
|---|---|---|---|---|
| 1 | **任务存在性与合并基底** | Home：readTree 基底 + 索引只叠活动字段，并额外把 index-only 任务加回（`HomeViewModel.kt:800-827`） | Chat：索引基底重建、只保留 selected 旧任务（`ChatViewModel.kt:1508-1528`）；Notification：索引即全部（`NotificationCoordinator.kt:298-305`） | 同一任务在首页/Chat/通知三处的「可见性」规则不同；桌面删除在首页可能被 additions 复活、在 Chat 空索引时残留 |
| 2 | **Chat 页的桥身份** | 注册表 `Handle.bridge/key`（权威，`ChatViewModel.kt:861-864`） | 全局 `engine.bridgeSession` 镜像（`:1744`，`EnginePort.kt:402/474`） | 见 §2.4：叠栈返回后门禁失败、状态冻结 |
| 3 | **workspaceKey / identity 推导** | `WorkspaceProjection`：`identity?.trim() ?: path`（`ChatViewModel.kt:1965-1981`） | Home `remoteIdentityOf`（`HomeViewModel.kt:554-558`）；Registry `effectiveScope` 字符串反推（`ConversationRegistry.kt:160-173`）；Chat `conversationScope` 无 path 时把 key 当 path（`ChatViewModel.kt:926-934`）；ChatOps key 规则（`ChatOps.kt:151-153`） | 五处独立实现，只有 1 处有单测（`WorkspaceProjection`）；远端/项目草稿路径最容易分叉 |
| 4 | **标题合并** | Home：`titleSource==custom → 索引；非空索引 → 索引；否则旧值`（`HomeViewModel.kt:813-819`） | Chat：恒取索引标题（`ChatViewModel.kt:1516-1522`） | 同一任务在首页与 Chat 显示的标题可能不同；Chat 会出现空标题「新对话」 |
| 5 | **lastAssistantPreview 提取** | `SessionsIndexProjection.previews`（`SessionsIndexProjection.kt:45-57`，Home 用） | Notification 内联重写一份（`NotificationCoordinator.kt:284-296`） | 重复逻辑，schema 变更要改两处 |
| 6 | **任务 mutation 参数** | Home：`task.workspacePath ?: workspace.path`、带 `provider`（`HomeViewModel.kt:861-875`） | Chat：同样用 task path/identity（`ChatViewModel.kt:1329-1340`），但**不带 provider** | 字段集不一致；服务端若用 provider 做摘要会丢 |
| 7 | **ChatCache 键口径** | Chat 保存/读取用路由 key（`ChatViewModel.kt:1719`、`:1124`） | Home 删除用工作区主根 key（`HomeViewModel.kt:300`） | 多根/远端任务删除后缓存行清不掉（§1.1-J） |
| 8 | **引擎重建后的缓存清理** | Home 按 `sessionSerial` 清预览表/项目树（`HomeViewModel.kt:380-392`） | Registry 只在 `ensure` 内清 handles（`ConversationRegistry.kt:83-88`）；Chat 无任何 serial 处理 | Chat 页在引擎重建后仍持有旧 `conversationKey`，直到下一次 ensure |
| 9 | **非 tab 项目的活动状态** | Home：不订阅、无 `phase/hasBackgroundWork`（`HomeViewModel.kt:465-469`） | Chat：订阅索引但索引任务不带 path/hostId（`SessionsIndexProjection.kt:71-84`） | 同一项目在首页无运行态、在 Chat 有运行态；打开 index-only 任务可能落主根 |
| 10 | **subscribeSessionsIndex 60s 口径** | Kotlin 三处硬编码 `timeoutSecs:60`（`HomeViewModel.kt:490`、`ChatViewModel.kt:1166`、`ConversationRegistry.kt:113`） | Rust 通道调用固定 `SUBSCRIBE_TIMEOUT_SECS=60`，忽略传入值（`conversation_transport.rs:2272`） | 口径一致，但常量四份、改一处会漂移 |

---

## 4. 死代码 / 未使用参数 / 重复逻辑

### 4.1 死代码（生产无调用点）

1. `HomeViewModel.unread(value: Boolean)` —— 仅定义，无调用（`HomeViewModel.kt:906-908`；round3 P2-15 未修）。
2. `ConversationRegistry.close()` / `closeAll()` —— 生产零调用，仅 `ConversationRegistryTest` 用（`ConversationRegistry.kt:181-203`）；
   `AppContainer.kt:192-199` 的注释仍写「只有任务删除（ChatViewModel 调 close）」——**注释已过期**。
3. `ConversationRegistry.Handle.workspaceKey` —— 只被 `closeAll` 使用（`ConversationRegistry.kt:199`），随 closeAll 一起成死字段。
4. `EnginePort.bridgeWorkspaceKey`（接口 + 实现 + `_bridgeWorkspaceKey`）—— 只有写入（`EnginePort.kt:263/287/402/474`），
   生产无读取点（仅 `UiTestSupport.kt:350` 写）。round3 01 §5-12 已指出可删，未删。
5. `WorkspaceItem.name` / `connected` / `taskCount`（`ChatViewModel.kt:85-91`）—— 生产未使用（`taskCount` 仅测试断言）。
6. `TaskItem.status` / `unread`（`ChatViewModel.kt:133-136`）—— 生产未使用。
7. `ChatViewModel.clearConversationEvents()` 与 `ChatUiState.conversationEvents` —— UI 不渲染，仅测试/调试
   （`ChatViewModel.kt:195`、`:970-972`、`:1804-1813`）。
8. `ChatOpsUnavailable` —— 无抛出点，仅 `UiFailure.kt:30` 的映射分支与测试引用（`ChatOps.kt:64`）。
9. `EngineChatOps` 的非注册表分支（`opened` / `openedScopeKey` / `defaultScope`，`ChatOps.kt:106-109`、`:135-144`）——
   生产 `AppContainer` 恒注入 registry（`AppContainer.kt:162`、`:283`），该分支只在测试可达。
10. 两段「无注册表时自己开桥」的兜底：`HomeViewModel.kt:478-500`、`ChatViewModel.kt:1150-1167` —— 同上，生产不可达，
    且与 `ConversationRegistry.ensure` 重复（内含重复的 `subscribeSessionsIndex` 调用与 60s 字面量）。

### 4.2 未使用参数 / 半死参数

- `ChatOps.call(op, args, scope)` 的 `scope`：注册表路径下只用于 `conversationKeyOf(scope)` 查句柄
  （`ChatOps.kt:122-133`）——语义上仍需要，但 `EngineChatOps.defaultScope` 在注册表路径完全不读，属半死。
- `ConversationRegistry.scope` 构造参数：只被 `closeAll` 使用（`ConversationRegistry.kt:46-48`、`:195`），随 closeAll 成死参数。
- `ConversationRegistry.ensure(taskId/project)`：生产由 Chat 传入；Home 恒 `taskId=null, project=null`（`HomeViewModel.kt:472-476`），
  Home 侧的这两个参数是常量——可接受，但说明 `ensure` 的意图参数只服务一个调用方。

### 4.3 重复逻辑

1. 任务合并/活动字段叠加：Home（`HomeViewModel.kt:800-827`）、Chat（`ChatViewModel.kt:1508-1528`）、
   Notification（`NotificationCoordinator.kt:298-305`）三套，规则互不一致（见 §3-1）。
2. sessions-index → preview 提取：`SessionsIndexProjection.previews` 与 `NotificationCoordinator.kt:284-296` 两份。
3. workspace key 规则：`WorkspaceProjection.toWorkspace`、Home `loadProjectTree`、`Chat.routeWorkspace`、
   `ConversationRegistry.effectiveScope`、`ChatOps.conversationKeyOf` 五份（§3-3）。
4. mutation 参数对象：Home `mutate` 与 Chat `mutateTask` 两份（字段集已有差异，§3-6）。
5. 私有 JSON 工具：`HomeViewModel.kt:1028-1036` 与 `ChatViewModel.kt` 文件尾各一份同语义 `asObject/asArray/asString`
   （Home 注释已承认「与 ChatViewModel 的私有工具同语义」）。
6. 引擎重建清缓存：Home 的 `serialChanged` 块（`HomeViewModel.kt:380-392`）与 Registry 的 `sessionSerial` 块
   （`ConversationRegistry.kt:83-88`）各自实现一次，Chat 第三次缺席。
7. 通知里两次构造 `WorkspaceItem(key = key, path = key, label = key)`（`NotificationCoordinator.kt:185`、`:299`）——
   把 key 当 path 的占位对象，建议抽一个 `WorkspaceItem.forKey(key)` 或直接让投影只吃 `Map<String, List<TaskItem>>`。

---

## 5. 交付判断

- **规格符合度**：§2 开桥四种合法组合、§3 新对话时序/失败矩阵、§4 已读/置顶/归档/删除的主干、§5 单桥单对话/串行化，
  实现均能对应；§4 的任务权威分工在 Chat 页被绕开（§1.1-E/F）。
- **验收清单（§10）**：8 条中 7 条可判「通过（静态）」，1 条有前提——「首页出现全部项目」依赖 bootstrap 至少有一个
  tab 工作区（§1.1-I）；非 tab 项目无实时活动不在验收项内。
- **不建议直接对外发布的原因（3 条 P1）**：
  1. §2.4 事件门禁身份被全局 bridge 覆写：双 Chat 叠栈返回旧页面会「静默停更」，用户可感知且无自愈（除再发一条消息）；
  2. round3 03 P1-8 通知深链缺 `path/identity/hostId`：点通知进入项目任务大概率失败（这是最常见的通知路径）；
  3. round3 03 P1-6 Chat/Home 任务元数据分叉：Chat 里置顶/归档/改名/删除不更新首页，Chat 自身重建后 pinned/archived 丢失。
- **若定位为「内部构建 / 先用起来」**：主干（读项目、开任务、建会话、发消息、失败回填、删任务清缓存）可用；
  上述 3 条 P1 建议列入下一迭代必须修，且 §2.1 的 P1-5（共享丢帧缓冲）与 §1.1-J（缓存键）建议同批处理。
