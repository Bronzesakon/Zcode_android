# 10 对话数据窗口生命周期审计（进页 / 缓存 / 快照 / 翻历史 / 内存上界）

> 只读审计，未改任何源码。结论基于当前工作区源码 + `build-logs/` 内真机日志。
> 行号一律相对仓库根 `E:\Zcode_android`。

## 0. 结论速览

| 问题 | 结论 | 关键证据 |
| --- | --- | --- |
| A 进页 | 缓存命中时首屏 0 RTT（读盘 51–86ms）；但**无论是否命中都会重新 `subscribe(force)` 拉一次 60 行尾窗快照**。冷桥首屏 = 4 个 RTT（openBridge / conversationOpen / subscribe ack / snapshot），另有 1 次 `rowsRange` 补首轮。异常路径实测 7.3s（ack 超时→重建引擎→重开工作区）和 61.2s（注册表全局锁被首页 `subscribeSessionsIndex` await 占住）。 | `ChatViewModel.kt:1404-1524`、`963-1068`、`ConversationRegistry.kt:128-213`；`build-logs/live-0851.log:2827,2845,2966,3984` |
| B 内存上界 | **`rows` 没有硬上限**，向前翻历史会无限增长；`ChatCache` 的 400 行只约束磁盘，不约束内存。实测 1188→1248→1308 行连续增长，堆 97→104MB；历史事故 9286 行/9346 行、4857 个列表单元、142/256MB 后 LOW_MEMORY（该条按任务给定事实引用，本仓库现有日志中未检索到该次退出记录）。 | `ChatViewModel.kt:799-810`、`2024`、`2417-2425`；`build-logs/pin-0922.log:12-13,27-29,48-50,86-88` |
| C 翻历史抖动 | 前插旧行**本身是锚稳定的**（reverseLayout 下旧行落在最高 index 端，其余 index 不变；日志显示合并只让锚点漂 3 个单元），抖动来自 `following` 判定失效后触发的 `follow drift → pin`：一次 pin 就把视口移动 49 个单元（≈ 数屏），随后立刻触发下一页。 | `Timeline.kt:141-150,162-183,209-221,804-827`；`pin-0922.log:14,26,52,64,90` |
| D 预取 | 与 web 口径不等价：web 是像素阈值 `scrollTop ≤ max(64, 2×视口高)` + **只在滚动事件里**触发；当前是「最高可见 index ≥ total−16」+ `LaunchedEffect(rows.size)` + 失败 1.5s 无限重试循环。因此在「贴顶且每次插入后仍在阈值内」「内容不足一屏」「pin 把视口推回顶端」时会自动连续拉全历史，每次 60 行/100–190KB。 | `Timeline.kt:152-160,239-267,486,489`；`docs/web-reverse/03-chat-timeline.md:37,158` |
| E 双写一致性 | 三条写入路径（快照 / deltas / 缓存）合并规则基本正确（都按 rowId 排序、窗口内以快照为准），但有三个口子：①`row.appended` 不去重，force 重订阅重放会出重复 rowId；②reducer「首次出现」与 UI 去重「保留最后一份」语义相反，重复时可能显示旧内容；③快照合并只判 `rowId < 窗口最老`，**不校验相邻性**，缓存尾与窗口之间可留洞。 | `ConversationRows.kt:61-72,77-87`、`Timeline.kt:555-576`、`ChatViewModel.kt:2417-2425` |
| F 订阅与窗口 | `subscribe` 只带 `sessionId`，没有 mode/epoch 参数（"mode=first" 只是 Rust 日志标签）；本地缓存窗口与快照窗口只靠 `atLogEpoch` 做 CAS，且该 CAS 在 `logEpoch == null` 时被短路——而 `openWorkspaceInternal` **每次**都会把 `logEpoch` 置 null。缺洞无任何检测。 | `ChatViewModel.kt:997`、`1424`、`773`、`conversation_transport.rs:3178-3190` |
| G 状态机 | 见 §4。核心缺口：进页/首屏/跟随/翻历史没有显式状态，全部由 5 个分散的 Boolean（`rowsReady`、`following`、`userDragged`、`loadingOlder`、`historyExhausted`）隐式表达，互相覆盖。 | 全文 |

---

## 1. 现状时序（进历史会话）

```
[用户点任务]
  │
  ▼ ChatScreen 组合：showSkeleton = taskId!=null && !rowsReady   ChatScreen.kt:217
  │  （骨架分支 → 首次 rowsReady=true 时整棵 Timeline 重挂载，LazyListState / following / 折叠态全丢）
  ▼
enter()  ── ensureSession(relay 握手)                              ChatViewModel.kt:1280-1325
  └─ openWorkspaceInternal(ws, task)                              :1404
       ├─[1] state 重置：rows/rowsReady/logEpoch=null/historyExhausted  :1408-1431
       ├─[2] cache.loadConversation()  —— 顺序阻塞 51-86ms（IO）  :1436-1461
       │      → rows=400, totalCount=400(伪值), logEpoch=缓存值, rowsReady=true
       │      → recomputeCanLoadOlder()（oldest>head ⇒ true）
       ├─[3] ensureRouteBridge → registry.ensure（全局 Mutex）  :1467 / Registry:128
       │      openBridge(wait bridge-ready) → waitHealthy → conversationOpen(helloV4)
       │      → subscribeSessionsIndex(no-wait)
       │      ⚠ 首页 preview 用 awaitIndex=true 持锁等 60s 时，这里被阻塞（日志 61.2s）
       ├─[4] sendMobileViewState
       ├─[5] startRowsWatchdog(6s)  +  launch { subscribe(force=true) }  :1501-1507
       │      subscribe: ack ≤6s → 首帧 ≤8s（force 才等首帧）  :994-1021
       │      失败 ⇒ 自动 recoverSubscription：reconnect→重开工作区→重订阅  :1048-1064,1979
       ├─[6] loadModelView()（并行）
       └─[7] refreshConversationState()（本地 FFI snapshot，7ms，无网络）  :1511,1880
  ▼
[首个 rowsUpdated]
  kind=snapshot：rows = mergeSnapshotRows(cached, window60)        :2012-2059
                ⇒ rowsReady=true；重算 canLoadOlder；maybeBackfillFirstTurn
                   （首轮缺 turnHeader ⇒ 立刻再发一次 rowsRange）   :2117-2126
  kind=deltas  ：ConversationRows.apply(current.rows …)  无上限      :2061-2088
  ▼
[Timeline 组合]
  nearTop = 最高可见 index ≥ total-16                               Timeline.kt:152-160
  LaunchedEffect(nearTop, canLoadOlder, loadingOlder, rows.size)    :239-267
     └─ onLoadOlder() → loadOlder() → fetchOlderPage(60)
          CAS：atLogEpoch vs 本地 logEpoch、rows.first() vs cursor   ChatViewModel.kt:773-785
          merged = (older∖ids ++ existing).sortedBy(rowId)   ← 无上限
  ▼
[合并后的两次连带]
  ① follow drift → scrollToTimelineBottom()（following 为 true 时）  Timeline.kt:209-221
  ② orderedRows.size 变化 ⇒ effect 重启 ⇒ nearTop 仍真 ⇒ 再拉一页（自激链）
```

`pin-0922.log` 的实测链（同一会话，间隔 1.1s / 1.2s）：

```
09:22:29.357 rowsRange ok before=9775 merged=1188 ms=261
09:22:29.389 follow drift → pin units=604
09:22:30.224 pin(0): first 540→589 canBack=true canFwd=false      ← pin 落在历史顶端
09:22:30.225 loadOlder start rows=1188
09:22:30.499 rowsRange ok merged=1248 ms=273
09:22:30.530 follow drift → pin units=650
09:22:31.421 pin(0): first 589→649 (size=4785) canFwd=false
09:22:31.702 rowsRange ok merged=1308 ms=279
```

> 注意：日志构建里 `pin(0)` 结束在 `canFwd=false`（历史顶端），与当前源码 `scrollToItem(0, offset=0)` 的语义不符，说明该日志来自旧构建（`BOTTOM_SCROLL_OFFSET = Int.MAX_VALUE/2` 仍残留在 `Timeline.kt:797` 未被引用）。**无论哪个版本，这行 pin 在翻历史时都不该执行**，需要真机复测确认。

---

## 2. 问题与风险（按严重度）

### P0-1 行窗口无上限，向前翻历史线性吃内存

- `fetchOlderPage` 只做 `older + existing` 合并，没有行数/单元数上限（`ChatViewModel.kt:799-810`）。
- 快照分支只增不减（`mergeSnapshotRows` 保留全部 `rowId < 窗口最老` 的行，`:2417-2425`）。
- deltas 分支的 `row.appended` 同样无上限（`ConversationRows.kt:77-87`）。
- `ChatCache.MAX_ROWS=400`（`ChatCache.kt:158`）只在磁盘侧，内存侧没有任何对应物；`totalCount` 可能上万（日志 `total=11688`）。
- 真机：1188 行 → 堆 97MB；1248 → 104MB；1308 → 99MB（`pin-0922.log:12,48,86`）。历史事故在 9286 行 / 4857 单元时 OOM。
- 失败模式：LOW_MEMORY 被系统杀（`ZLog.kt:197,534-544` 有 exit reason 记录，但**没有 `onTrimMemory` 响应**，全仓库无该回调）。

### P0-2 预取自激：一次贴顶会连续拉完整段历史

`Timeline.kt:239-267` 的 effect key 包含 `orderedRows.size`，每次成功插入旧行都会重启；只要位置仍在阈值内就再拉一页。三条触发路径：

1. 贴顶时合并后位置仍在阈值内（配合 P0-3 的 pin，最典型）；
2. 内容不足一屏（`total < 16` 时 `last.index >= total-16` 恒真）；
3. 失败/被 CAS 丢弃时 `rows.size` 不变 → 进入 `while (nearTop && …)` 每 1.5s 重试一次，直到离开阈值（`Timeline.kt:246-258`）——每次都是一次 60 行/100–190KB 的 RPC。

不设上限时最坏结果 = 自动把整段历史（11688 行）拉进内存；这既是 P0-1 的放大器，也是「进历史会话慢」的次要原因。

### P0-3 跟随意图（following）会在翻历史时把视口推走

- `following` 是 Timeline 内的 `remember { mutableStateOf(true) }`（`:167`），只在**触摸拖动**时置 false（`:170-183`），没有任何「本次进入页面后用户是否真的上滚过」的门；重挂载即复位（`ChatScreen.kt:217-236` 的骨架/时间线分支切换会销毁整个 Timeline）。
- 触发条件只有 `atBottom`（单阈值 48dp，无滞回，`:141-150`）与实际位置，位置在插入旧行/长高时本来就会瞬时变化（代码注释也承认）。
- 结果：`follow drift → pin` 在翻历史期间反复执行（日志连发 3 次），视口被移动 49 个单元，且 pin 后的位置让 `nearTop` 再次为真 → P0-2 自激。
- 兜底函数 `scrollToTimelineBottom` 还留着未使用的 `BOTTOM_SCROLL_OFFSET`（`:797`），说明「大偏移会被钳到列表另一端」的历史 bug 刚修过；日志构建仍表现为钳到历史顶端，需要复测。

### P1-4 注册表全局锁内 await 首页索引订阅（60s 级卡顿）

`ConversationRegistry.ensureLocked` 在 `mutex.withLock` 内串行执行 `openBridge → waitHealthy → conversationOpen → subscribeSessionsIndex(await=true)`（`:128-213`，await 分支 `:251-258` 超时向上抛）。不同工作区共用同一把锁，于是首页对 workbuddy 的 60s 超时把对话页的 ensure 堵了 61.2s：

```
08:45:54.5  #10 ensure begin key=E:\Zcode_android ...
08:46:55.460 #8 subscribeSessionsIndex timeout ms=64693（向上抛）
08:46:55.461 #8 ensure failed ms=64834
08:46:55.710 #10 open_bridge ← session=3 ms=248
08:46:55.729 workspace open done totalMs=61248      ← 进页耗时 61s
```
（`build-logs/live-0851.log:3949-3996`）

### P1-5 每次进页都强制重订阅 + 重推 60 行快照

`openWorkspaceInternal` 对任何 taskId 都 `subscribeConversation(force=true)`（`:1507`），即使缓存已命中、窗口未变。force 还会额外等首帧 8s（`:1012-1021`），失败后走「重建引擎 + 重开工作区」（`:1048-1064,1979-1997`）。实测一次失败的代价：

```
08:44:32.647 进页（缓存 400 行已出，85ms）
08:44:38.660 subscribe ack timeout 6000ms
08:44:38.663 reconnect → dispose → 新引擎
08:44:39.832 snapshot applied window=60 rows=429
```
（`live-0851.log:2827,3900-3923`）

即：有缓存时用户能看到内容，但窗口数据要 7.3s 才对齐，期间 `logEpoch=null`、翻页 CAS 失效（见 P1-6）。

### P1-6 `logEpoch` 被无条件置空，CAS-1 在关键时刻失效

`openWorkspaceInternal` 的重置块里 `logEpoch = null` 在 `switchingTask` 判断之外（`:1424`，对比 `:1420-1423`），所以**同一任务刷新/自动恢复**也会清空 epoch；`fetchOlderPage` 的 CAS 是 `atEpoch != null && after.logEpoch != null && …`（`:773`），本地为 null 时直接放行。同时缓存里的旧 epoch 会先被写入 `state.logEpoch`（`:1449`），与服务端不一致时每一页都被整页丢弃（`:777-785`）+ 1.5s 重试空转。

### P1-7 快照窗口与本地窗口不校验相邻性 → 可以出洞、可以串 epoch

`mergeSnapshotRows` 只按 `rowId < window.min` 切分（`:2417-2425`），不检查 `缓存最大 rowId` 与 `窗口最老 rowId` 是否相邻。三种出洞/串数据场景：

1. 缓存尾比服务端尾旧很多（写入失败、被 6MB 规则丢弃后重建、长时间未打开）→ 保留的前缀与窗口之间缺一段，界面上看不出来，翻页游标也永远补不到；
2. `logEpoch` 变化（服务端压缩/重建日志，rowId 可能重新从 1 开始或整体平移）→ 旧 epoch 的行只要 `rowId < 新窗口最老` 就被当作「更早历史」保留（文档 `docs/CONVERSATION-LIFECYCLE.md:91` 也是这么写的），于是两条日志的正文被拼在一屏；
3. 缓存侧 `totalCount = cached.rows.size`（`:1447`）把 400 当全会话行数，后续 UI/调试口径失真（`firstRowId` 仍保留，所以 `canLoadOlder` 判断侥幸正确）。

### P1-8 右侧进度条按「单元索引 / 固定 72dp 估高」计算 → 必然乱飘

`TimelineScrollIndicator.kt:40-68`：`estimatedVisible = viewport / 72dp`，`thumbFraction = estimatedVisible / totalItems`，`progress = (top.index + insideItem) / (total - estimatedVisible)`。因此：

- 每翻一页 `totalItems` ±46~60，thumb 高度与位置同时跳；
- `insideItem = (-top.offset)/top.size` 在超高大行上失真（日志里有 4785px 的单行），一屏滚动可以让 thumb 走掉大半条；
- 折叠/展开（`defaultOpenTurns` 变化）也会让 `totalItems` 突变。

对照 web：`estimateSize` 有按键尺寸缓存（上限 4000 条），进度基于像素比例（`docs/web-reverse/03-chat-timeline.md:165-166`）。

### P1-9 主线程每帧多趟 O(n) + 大对象序列化

`rows` 每变一次（流式期间可能每秒十几帧）：

- `normalizeTimelineRows`（排序+去重）、`buildTimelineUnitsOrdered`、`defaultOpenTurnIds`、`finalAnswerRowIdByTurn`、`visibleTimelineUnits`、`uniqueTimelineKeys`（`Timeline.kt:109-139`）——6 趟全量遍历，都在组合阶段主线程执行；
- `contentSignature` 对每个流式行调用 `get("output")?.toString()`（`:755-759`），即对 output 整棵 JSON 做全量序列化，且该表达式没有 `remember`，每次重组都算（`:203-208`）；
- `fetchOlderPage` 用 `(older + existing).sortedBy`（`:799-802`），两个已有序列表做全量排序。

600 行时是毫秒级；1300+ 行 + 流式时就是掉帧来源。

### P1-10 缓存「行数上限」与「体积上限」脱节

`ChatCache.saveConversation` 只按 400 行截断（`:92`），不按字节；而 `loadConversation` 对 `> 6MB` 的文件**直接删除**（`:61-64`）。重对话 400 行很容易超过 6MB → 写进去，下次读被删 → 永远 cache miss → 每次进页都退化成等网络。`MAX_FILE_BYTES` 与 `MAX_ROWS` 之间没有一致性校验或降级日志（只在 `:1459` 打一行 `cache miss`）。

### P2-11 重复 rowId 的两个语义相反的去重

- reducer 的 `indexOf(id)` 取**首次出现**位置，`row.upserted`/`row.delta` 只改第一份（`ConversationRows.kt:61-72,89-94,113-121`）；
- UI 的 `normalizeTimelineRows` 用 `put` 覆盖，保留**最后一份**（`Timeline.kt:570-571`）。

`row.appended` 不去重（`ConversationRows.kt:77-87`），force 重订阅/帧重放产生重复 rowId 时，就会出现「UI 显示的是旧内容、下一次 delta 又改了没人看的另一份」。此外重复行会被 `schedulePersist` 原样写进缓存。

### P2-12 进页串行项可以并行

cache 读盘（`:1436-1461`）与 `ensureRouteBridge`（`:1467`）互不依赖却串行；`refreshConversationState`（`:1511`）与订阅首帧也互不依赖。合计可省 50–100ms（缓存大时更多）。

### P2-13 文档与实现不一致（应一并撤销旧口径）

`docs/CONVERSATION-LIFECYCLE.md:41` 仍写「不设行数/文件数/体积上限」，`:12` 写「LRU 上限 80 个任务」（代码没有 LRU）；`:65-75` 描述 `prefetchHistory()` 循环与「200 页上限」（代码中不存在该函数与上限）。

### P2-14 其他

- `rowsRange` 的 `limit` 在 Rust 侧不夹 1..200（`conversation_transport.rs:1585-1597`，只有默认 60），与 `docs/chat/08-behavior-protocol.md:473` 的契约不符（客户端恒传 60，暂不触发）。
- `loadOlder()` 在 `rows` 为空时不看 `canLoadOlder`（`ChatViewModel.kt:705`），靠 cursor 为空兜底返回，属侥幸。
- 缓存落盘防抖 600ms（`:2196-2209`），离开页面/onCleared 不 flush，最后一段可能丢。
- `subscribe` 的失败恢复是「每个会话只自动恢复一次」（`:401`），重进页面会重置；弱网下表现为「内容在、实时更新掉线」。

---

## 3. 目标：窗口预算（问题 B 的量化建议）

依据实测：1188 行 / 604 单元 ≈ 97MB（37%，可用）；9286 行 / 4857 单元 → 142MB 后 OOM。按 ~30MB/1000 行的边际成本留安全垫：

| 项 | 建议值 | 说明 |
| --- | --- | --- |
| `MAX_WINDOW_ROWS` | **600** | 与 `ChatCache.MAX_ROWS` 共用同一个常量，避免两套口径 |
| `TRIM_TARGET_ROWS` | **400** | 超限裁到 400，留 200 行滞回，避免每加一页裁一次 |
| `SAFE_UNITS_FROM_TOP` | **≈ 3 屏（45–60 个单元）** | 可见最顶单元距最老端小于该值时不裁 |
| 软警告 | 堆 ≥ 60% 或 `onTrimMemory(TRIM_MEMORY_RUNNING_LOW)` | 主动裁到 400 并记录一条 `ZLog.memory` |

**超限丢弃旧行的锚点处理**（问题 C 的第二问）：

- reverseLayout 下「最老」在最高 index 端，丢弃它们**不改变其余单元 index**，因此不会移动视口——这是可行的前提。
- 唯一风险是可见区正好覆盖被丢段。所以裁剪条件必须是：`visibleItemsInfo.last().index <= totalItems - 1 - SAFE_UNITS_FROM_TOP`；不满足就**跳过本次裁剪**（下一帧再试），绝不为了省内存动视口。
- 裁剪前记录 `anchorKey = 当前底部可见单元的 key` 与 `anchorOffset`，裁剪后若 `firstVisibleItemIndex` 变了，用 `scrollToItem(indexOf(anchorKey), anchorOffset)` 显式恢复（LazyColumn 的 key 级重锚定只是兜底，不应依赖）。
- 裁剪必须同步前移 `rows.first()`（它就是分页游标），并在裁剪后重置「同一行数已请求过」的预取门（`Timeline.kt:239`），否则预取会以为无进展。
- 不要在用户贴顶时裁剪：贴顶 + 裁剪 = 把用户正在读的内容删掉，并立刻触发再拉同一段（死循环）。

---

## 4. 目标状态机（问题 G）

```
                    ┌──────────────────────────────┐
                    │ IDLE  无选中任务（草稿页）      │
                    └──────────────┬───────────────┘
                       selectTask   │
                                    ▼
                    ┌──────────────────────────────┐
                    │ OPENING 缓存+桥+订阅            │
                    │ 允许：读缓存(∥ensureBridge)、   │
                    │       subscribe(force)        │
                    │ 取消：切换任务/离开页面          │
                    └──────────────┬───────────────┘
                     cache 命中 → │ rowsReady=true
                     snapshot    │
                                    ▼
                    ┌──────────────────────────────┐
      ┌────────────►│ FIRST_PAINT 首屏（贴底）        │◄──── 恢复/回前台(短)
      │             │ 允许：1 次 backfill rowsRange  │
      │             │ 禁止：pin、预取、裁剪            │
      │             └───────┬──────────────┬────────┘
      │        用户拖动上滚    │              │ 无操作/流式
      │                     ▼              ▼
      │      ┌────────────────────┐  ┌────────────────────┐
      │      │ READING 上翻阅读      │  │ FOLLOWING 贴底跟随   │
      │      │ 允许：roll 到顶时拉 1 页│  │ 允许：流式追加、pin  │
      │      │ 禁止：pin、following  │  │ 禁止：预取、裁剪      │
      │      └───────┬────────────┘  └──────────┬─────────┘
      │    距顶 ≤2 屏 │ 且已 armed          用户拖回底部 │
      │              ▼                          │
      │      ┌────────────────────┐              │
      │      │ PAGING 一页在飞        │              │
      │      │ 允许：1 个 rowsRange  │──────────────┘
      │      │ 取消：切任务/epoch 变   │
      │      └───┬───────────┬────┘
      │  合并成功 │           │ hasMore=false/空页
      │  (仍贴顶) │           ▼
      │          │   ┌────────────────────┐
      │          │   │ EXHAUSTED 到此为止    │
      │          │   └────────────────────┘
      │          └─► 回 READING（armed=false，需用户再上滚 1 屏才重新武装）
      │
      │  裁剪（仅当可见区离最老端 >3 屏）
      │      └─► READING（保持锚点）
      │
      └─ TRIM/内存压力/回前台恢复
```

各状态允许的加载动作与取消条件：

| 状态 | 允许的加载 | 禁止 | 退出条件 / 取消 |
| --- | --- | --- | --- |
| OPENING | 读缓存（与 ensureBridge 并行）、1 次 `subscribe(force)`、1 次 `conversationSnapshot` | 预取、pin、裁剪 | 缓存命中→FIRST_PAINT；首帧→FIRST_PAINT；超时→ERROR_RETRY |
| FIRST_PAINT | 至多 1 次 backfill `rowsRange` | 旧的 follow-drift pin、自动翻页 | 用户手势→READING；贴底流式→FOLLOWING |
| FOLLOWING | 追加/替换窗口、贴底 pin | 预取、裁剪 | 用户上滚→READING；切任务→OPENING |
| READING | 仅当「armed 且距顶≤2 屏」拉 1 页 | pin、following 恢复、裁剪（除非离顶>3 屏） | 回底→FOLLOWING；到顶且无更多→EXHAUSTED |
| PAGING | 1 个在飞的 `rowsRange` | 第二个请求、pin、裁剪 | 成功/失败/epoch 变/切任务（后三者丢弃本页） |
| EXHAUSTED | 无 | 所有翻页 | epoch 变→READING；切任务→OPENING |
| 恢复（回前台>2s） | 1 次 resubscribe + 1 次 snapshot 合并 | 重读缓存覆盖内存、pin | 合并完成→READING（保持原位置，不强制贴底） |

关键不变量（建议写成断言/单测）：

1. 任何时刻 `rows.size ≤ MAX_WINDOW_ROWS`；`rows` 严格按 rowId 升序且无重复。
2. 同一时刻最多 1 个 `rowsRange` 在飞（`olderMutex` 已保证，但 UI 层不得排队第二页）。
3. 一次用户手势最多触发 1 页；`armed=false` 期间任何位置变化都不触发加载。
4. 非 FOLLOWING 状态下不得调用 `scrollToTimelineBottom`。
5. 窗口合并后：`windowEnd` 连续（`rows[i+1].rowId == rows[i].rowId+1` 不成立时记为 hole 并打日志）。

---

## 5. 改造清单（文件:行，按优先级；不在此写代码）

### 第一批（P0，止崩溃/止自激）

1. `android/.../ui/chat/ChatViewModel.kt:799-810`、`:2061-2088`、`:2417-2425` —— 引入统一窗口裁剪：新增 `MAX_WINDOW_ROWS=600` / `TRIM_TARGET_ROWS=400`，三处合并后调用同一个 `trimWindow(rows, visibleAnchor)`；裁剪条件由 UI 提供（见 3）。
2. `android/.../ui/chat/ChatViewModel.kt:2417-2425` —— 合并前校验窗口连续性（`cacheMaxRowId + 1 == window.min` 不成立则记录 hole，并丢弃缓存前缀改为只保留窗口）；`epoch` 变化时整窗替换、不保留旧前缀。
3. `android/.../ui/chat/timeline/Timeline.kt:239-267` —— 预取改为滚动驱动 + 一次性：effect key 去掉 `orderedRows.size`，改用 `snapshotFlow { firstVisibleItemIndex/offset }`；加 `pagingArmed`（离开阈值区 >1 屏才重新武装）；删除 `while (nearTop…)` 的 1.5s 重试循环。
4. `android/.../ui/chat/timeline/Timeline.kt:152-160` —— `nearTop` 改像素口径：`visibleItemsInfo.last().offset + size` 与 `viewportStartOffset` 的差 ≤ `2×viewportSize.height`（对齐 web `max(64px, 2×clientHeight)`）。
5. `android/.../ui/chat/timeline/Timeline.kt:162-183`、`:209-221`、`:804-827` —— `following` 提升为显式状态（Following/Detached + `userScrolledSinceMount`）；只有「用户手势结束且 atBottom」才恢复 Following；`loadingOlder` 期间禁止 pin；`atBottom` 加滞回（进入 48dp、退出 200dp）；删掉未使用的 `BOTTOM_SCROLL_OFFSET`（`:797`）。
6. `android/.../ui/chat/ChatScreen.kt:217-236` —— 把 `rememberLazyListState` / 跟随状态提升到 ChatScreen（或 `rememberSaveable`），并用稳定 key 保证骨架↔时间线切换不销毁 Timeline（否则每次 `rowsReady` 翻转都丢锚点、复位 following）。
7. `android/.../ui/chat/ConversationRegistry.kt:128-213` —— 锁内不再 await `sessionsIndex`：`ensureLocked` 只负责建桥+对话，`subscribeSessionsIndex(await=true)` 移到出锁之后；`:192-197` 聊天页恒 `awaitIndex=false`。

### 第二批（P1，止慢/止抖）

8. `android/.../ui/chat/ChatViewModel.kt:1424` —— `logEpoch = null` 移进 `if (switchingTask)`；同任务刷新保留 epoch。
9. `android/.../ui/chat/ChatViewModel.kt:1436-1461` —— 缓存读盘与 `ensureRouteBridge` 并行（`async`/`awaitAll`）。
10. `android/.../ui/chat/ChatViewModel.kt:1501-1509` —— 缓存已命中且 `logEpoch` 未变时不重发 `subscribe(force)`，改为「注册表保活的订阅 + 只补一次 resync」；把 `force` 的 8s 首帧等待从进页关键路径移出。
11. `android/.../ui/chat/timeline/TimelineScrollIndicator.kt:40-68`、`:108-109` —— 用按单元 key 的高度缓存（对齐 web `nwn` 4000 条）估算内容总高，进度按像素比例；至少对 `totalItems` 做滑动平均，消除每页 ±60 的跳变。
12. `android/.../ui/chat/timeline/Timeline.kt:109-139` —— 把 6 趟遍历合并成一次（normalize + units + turn 状态 + 最终回答 + 可见过滤 + key），并用 `derivedStateOf` 只读取真正参与布局的部分。
13. `android/.../ui/chat/timeline/Timeline.kt:203-208`、`:755-759` —— `contentSignature` 改读 `output.text` 的 length（不 `toString()` 整棵 JSON），并用 `remember(orderedRows)` 缓存签名。
14. `android/.../data/ChatCache.kt:82-108` —— 写入前按字节裁剪（先估算 payload，超 `MAX_FILE_BYTES` 再 `takeLast` 递减），避免「写进去、下次读被删」；把 `MAX_ROWS` 提成公共常量与 ViewModel 共用。
15. `android/.../ui/chat/ChatViewModel.kt:2196-2209` —— `onCleared` / `onBackground` 时立即 flush 一次缓存（取消防抖）。

### 第三批（P2，一致性与文档）

16. `android/.../ui/chat/ConversationRows.kt:61-72`、`:77-87` —— `row.appended` 按 rowId 去重（或至少检测重复并打日志）；把「首现/最后一份」语义注释清楚，并与 `Timeline.kt:555-576` 对齐。
17. `android/.../ui/chat/ChatViewModel.kt:799-802` —— 两段已有序列表改为归并（O(n)），去掉全量 `sortedBy`。
18. `android/.../ui/chat/ChatViewModel.kt:1447` —— 缓存命中时 `totalCount` 不要写成 400，改为「未知（-1）」或保留缓存里存下的真实 totalCount（需在 `ChatCache` schema 增加字段）。
19. `rust/crates/protocol/src/conversation_transport.rs:1585-1597` —— `limit` 夹到 `1..=200`（与 `docs/chat/08-behavior-protocol.md:473` 对齐）。
20. `docs/CONVERSATION-LIFECYCLE.md:12,41,65-75,91` —— 撤销「不设上限 / LRU 80 / prefetchHistory() 200 页」旧口径，改写成 §3 的窗口预算与状态机；在文首标注本次审计编号。
21. `android/app/src/main/kotlin/app/zemote/AppRoot.kt`（或 Application）—— 增加 `ComponentCallbacks2.onTrimMemory`：`TRIM_MEMORY_RUNNING_LOW` 以上时调用 ViewModel 的 `trimWindow(force=true)`（裁到 400，不受"距顶 3 屏"限制，但要先记锚点）。

---

## 6. 待验证项（本报告不能下结论的部分）

1. **pin 目标端**：日志构建的 `pin(0)` 落在历史顶端（`canFwd=false`），当前源码是 `scrollOffset=0`。需在当前 APK 上复测一次「上翻到顶 → 触发一页」看 `pin(0)` 后的 `first` 是否为 0。
2. **following 为何在翻历史时为 true**：pin-0922 日志是过滤片段，看不到此前的 drag。按源码只能给出候选（重挂载复位 / 单阈值无滞回 / pin 自身制造"漂移"），需带完整 `[UI/timeline]` 日志复现一次。
3. **9286 行 / 4857 单元 / LOW_MEMORY 那次事故**：本仓库 `logs/`、`build-logs/` 中未检索到该次记录（只有更早的低内存退出机制代码 `ZLog.kt:197,534-544`），报告按任务给定事实引用，建议从 `crash-history.log` 补齐。
4. **快照窗口是否恒为 60**：仅见一次 `snapshot applied window=60`（`live-0851.log:2966`）。若桌面端会随 `historyFirst` 能力改变窗口大小，§3 的上限需要按窗口重算。
