# 03 · 任务基底 / pinned / archived / unread / sessions-index —— Round2 交叉复核

审计方式：**只做静态阅读**。未执行 web 快照 JS，未执行 asar 内任何代码，未连设备。
坐标口径：

- `Reference_web/3.17.1/assets/index-Dwhx77cK.js`：**UTF-8 字节偏移**（`grep -b -o -F`），引用时写 `web@偏移`。
- 桌面 asar：`tools/asar.mjs` 口径，**偏移相对该内部文件**（`node tools/asar.mjs dump …` 后用 Buffer 按字节切片复核），写 `out/host/index.js@偏移` / `out/host/chunk-LNTVTW5D.js@偏移`。
- Rust / Kotlin：`文件:行号`（行号是下面列出的快照版本的物理行）。

被审代码快照（审计期间有并发修改，取快照保证可复现）：

| 文件 | sha256 |
|---|---|
| `android/app/src/main/kotlin/app/zemote/ui/home/HomeViewModel.kt` | `bf90185c910767566881a0d15f7de78bb112102adcb527cb911b37de0d375767`（975 行） |
| `android/app/src/main/kotlin/app/zemote/ui/chat/SessionsIndexProjection.kt` | `0dab3eb08c7b0d9d04eaffc2db0b788c3dfdba265a12e53b9f64ba595a4befab` |
| `android/app/src/main/kotlin/app/zemote/ui/chat/ChatViewModel.kt` | 审计时 `a21f1f60…`，复核时已被并发改成 `4aebe5fe…` → 因此只按函数名引用 |
| `android/app/src/main/kotlin/app/zemote/ui/nav/AppNavHost.kt` | `c0a0164fe6067ce08474d17adeb344570bc69700b76fd6155fc21c18421539bc` |
| `rust/crates/protocol/src/conversation_transport.rs` | `91be4ea5e6b1853dcaa6448408f1f2ff6e4f88cd193a2805a75db9c1907a78e3` |

---

## 0. 结论摘要（先给答案）

1. **`project.readTree.tasks` 本身就是完整任务基底**：`taskId / title / titleOverridden / workspacePath / workspaceIdentity / createdAt / updatedAt / status / pinned / archived / unreadAt / projectId / hostId` 全在其中（SQL repository 路径直接读列；legacy 路径三路拼装后手工打 `pinned/archived`）。sessions-index **只补活动字段**，不含 `pinned/archived/unreadAt`。→ Android 再对每个工作区调 `listPinnedTasks/listArchivedTasks` **不必要**，且是**危险的重复计算**（失败即清零，见 §5 R1）。
2. **subscribeSessionsIndex 的应答是 `{ack:{subscriptionId, mode, logEpoch}}`**，由桌面 host 从 agent service 的 `v4/conversation/subscribe` 原样返回；Rust 只校验 `ack.subscriptionId`，超时 `SUBSCRIBE_TIMEOUT_SECS=60s`，而 Android 宿主兜底是 `30s+5s=35s`（实际生效 35s）。「没有任务」不会导致超时；但 `runtimePolicy='existing-only'` 在**没有活着的 runtime**时会立刻 reject（不是超时）。
3. **sessions-index 是「一个订阅 = 一个工作区」**（topic `sessions-index/{identity||path}`），不是「所有项目」；快照 `{workspaceId, logEpoch, sessions[]}`，增量只有 `session.upserted/removed`，合并规则是 snapshot 清空重建、deltas 校验 `fromSeq==seq` 否则 resync。**确不含 pinned/archived/unreadAt**。
4. **web 的已读标记 = `zcode-task.setTaskUnread({…, unread:false, expectedUnreadAt})`**（CAS）。Android `markRead` 发的 RPC 与令牌**语义等价**，但成功后被丢弃，且本地 `unreadAt` 的合并规则是「旧值优先」，**未读点永不消失**（§5 R2）。
5. Android 合并策略有 3 个高危问题：`applyTaskMeta` **失败即清零** pinned/archived、`unreadAt` **只增不减**、**任务基底方向与 web 相反**（以 sessions-index 为基底会丢掉 SQL 里存在但索引里没有的任务）。另有 mutation 后不刷新、标题被索引覆盖、只读项目不订阅等问题。

---

## 1. 任务的两个来源分工（Q1）

### 1.1 `readTree.tasks` 的字段（两种 host 实现，字段一致）

**repository 路径**（有 projects 表时）`out/host/chunk-LNTVTW5D.js@100574`：

```js
function ud(r){return r.prepare("SELECT * FROM tasks WHERE provider=? AND deleted=0 AND coalesce(cli_deleted,0)=0 AND cli_created_at IS NOT NULL ORDER BY cli_updated_at DESC,task_id").all(te).map(e=>{…return{...t,taskId:String(e.task_id),traceId:…,workspacePath:String(e.root_path),workspaceIdentity:…,title:String(e.title_overridden===1?e.title:e.cli_title??""),titleOverridden:…,createdAt:Number(e.cli_created_at),updatedAt:Number(e.cli_updated_at),status:e.cli_last_outcome==="error"?"error":e.cli_last_outcome?"completed":void 0,mode:t.mode??"default",pinned:e.pinned===1,archived:e.archived===1,unreadAt:e.unread_at==null?void 0:Number(e.unread_at),projectId:…,hostId:String(e.host_id)}})}
```

- `pinned/archived`：`@101399` `pinned:e.pinned===1,archived:e.archived===1`
- `unreadAt`：`@101443` `unreadAt:e.unread_at==null?void 0:Number(e.unread_at)`
- `title`（含 `title_overridden` 逻辑）：`@101093`

**legacy 路径**（无 projects 表）`out/host/index.js@38229` 起的 `readTree`，`@38500` 附近：

```js
return[...f.map(k=>({...k,pinned:!1,archived:!1})),...m.map(k=>({...k,pinned:!0,archived:!1})),...v.map(k=>({...k,pinned:!1,archived:!0}))]
```

其中 `[f,m,v] = readTasks(kind:"active"/"pinned"/"archived")`，每条最终带 `hostId/projectId`，再经 `jv()`（`out/host/index.js@46715`）按 `workspacePath` 回填 `workspaceIdentity`。

**结论**：`readTree.tasks` **已经**包含 pinned / archived / unreadAt / title / status / updatedAt / createdAt / workspacePath / workspaceIdentity / projectId / hostId。
`readTree` 的 `tasks` 不走 `includeDeleted` 过滤（`@102497` 的 `readTree` 里 tasks 只来自 `ud()`），`includeDeletedTasks:true` 只多返回一个**独立字段** `deletedTasks`（`out/host/chunk-LNTVTW5D.js@102643`），**不会**把已删任务混进 `tasks`。

### 1.2 sessions-index 只提供活动字段

entry schema `pC`（`out/host/chunk-ZSDMP7DM.js@655239`）：

```
pC=e.object({sessionId, workspaceId, parentSessionId?, title, titleSource?, lastOutcome?,
  phase, sessionEnded, hasBackgroundWork, workflowActivity?, pendingInteraction?,
  pendingInteractionSummary?, goalStatus?, lastActivityAt, lastAssistantPreview?, createdAt})
```

—— **没有** `pinned/archived/unreadAt/titleOverridden/provider`。这与 Round1 一致。

### 1.3 权威分工（web 的事实）

- 存在性 + 置顶 + 归档 + 未读 ← `readTree.tasks`；
- 活动（phase / lastActivityAt / hasBackgroundWork / pendingInteractions / workflowActivity）+ 标题来源 ← sessions-index（`cvt`/`Pbt` `web@2073406`）；
- 分组过滤只用基底字段：`web@2066420 function OP(e,t)`：
  `t.kind===`pinned`?e.pinned&&!e.archived : t.kind===`timeline`?!e.archived : …!e.archived&&!e.pinned`
- unread 分组 `web@2066846 function gbt(e)`：排除 `archived`、必须有 `unreadAt`、项目未删除。

### 1.4 Android 额外调用 `listPinnedTasks` / `listArchivedTasks` 是否必要

**不必要，且带来三个副作用。**

这两个接口返回的是 `readTree` 的**子集**：

- `out/host/index.js@668760` `async listPinnedTasks(C){return(await T.listTaskMetas({workspacePath:C.workspacePath,workspaceIdentity:C.workspaceIdentity,provider:ta,pinned:!0,archived:!1})).map(Vn)}`
- `out/host/index.js@669390` `async listArchivedTasks(C){return(await T.listTaskMetas({workspacePath:C.workspacePath,workspaceIdentity:C.workspaceIdentity,provider:ta,archived:!0})).map(Vn)}`

它们与 `ud()` 相比多了 `archived=0`（pinned 侧）等 SQL 过滤，**不会比 readTree 更新**。副作用：

1. 覆盖：`HomeViewModel.kt:701-724 applyTaskMeta` 用**成员关系**重算每个任务的 `pinned/archived`（`715-716`），readTree 的值被无条件替换；
2. 清零：任一调用失败 → `metaTasks(null)` 返回 `emptyList()`（`:671`）→ 整工作区 `pinned=false, archived=false`；
3. 成本：13 个工作区 × 2 次 8s 超时调用，全部串在一把 `Mutex` 里（见 §2.5）。

---

## 2. `subscribeSessionsIndex` 的应答语义与超时（Q2）

### 2.1 桌面回什么

host 实现 `out/host/index.js@597731`：

```js
async subscribeSessionsIndexV4(y){
  let O=await lt(y,y.runtimePolicy),            // existing-only：没有 runtime 直接抛
      j=Aa(y,Ta(y.subscriberScope)),Y=Oa(ve(y)),me=[];
  if(Qye(y.workspaceIdentity)) try{ me=(await s.listTaskMetas({workspacePath:…,workspaceIdentity:…,provider:"glm"})).slice(0,QT).map(X=>X.taskId)}catch(X){Qe.warn(…)}
  let Ne=await O.request(Bt.conversationSubscribe,{topic:Y,connectionId:j.connectionId,
      clientMode:j.clientMode,...me.length>0?{legacyTaskIds:me}:{},...y.base?{base:y.base}:{},
      ...y.visibility?{visibility:y.visibility}:{}},M1);
  return Yo(y,Y,Ne.ack.subscriptionId,j.connectionId),Ne }
```

- 应答 schema：`out/host/index.js@198169` `M1=g.object({ack:fl}).strict()`
- ack schema：`out/host/index.js@192786` `fl=g.object({subscriptionId:g.string(),mode:g.enum(["snapshot","resume"]),logEpoch:g.string()})`
- **即：`{ack:{subscriptionId, mode, logEpoch}}`**（`mode` 只有 `snapshot|resume`）。

Rust 侧只认 `ack.subscriptionId`：`rust/crates/protocol/src/conversation.rs:741 parse_subscribe_ack` → `None => Err(InvalidState("{subscribe_method}: missing ack.subscriptionId"))`；收到后 transport 合成 host-level `Completed(Ok)`（`conversation_transport.rs:3103 on_subscribe_response`，`:3174 emit_completion(flow, Completion::Ok, events)`）。

### 2.2 超时常量（全部实测值）

| 环节 | 常量 | 值 | 证据 |
|---|---|---:|---|
| 订阅 ack（Rust 定时器） | `SUBSCRIBE_TIMEOUT_SECS` | **60 s** | `conversation.rs:67`；使用点 `conversation_transport.rs:2266` |
| 命令默认 / 握手 initialize | `COMMAND_DEFAULT_TIMEOUT_SECS` | 30 s | `conversation.rs:63` |
| 桥健康 | `BRIDGE_HEALTHY_TIMEOUT_SECS` / `WAIT_HEALTHY_TIMEOUT_MS` | 45 s | `conversation.rs:65`、`conversation_transport.rs:144` |
| **Android 宿主兜底（实际先到）** | `DEFAULT_OPERATION_TIMEOUT_MS + HOST_TIMEOUT_GRACE_MS` | **30 s + 5 s = 35 s** | `ZemoteEngine.kt:36`、`:45`、`:699-703` |

`conversationCall` 没有 `timeoutMs`，也没有 `args.timeoutSecs`，所以落到默认 30 s + 5 s = **35 s**；比 Rust 的 60 s 先到 —— 订阅实际最多等 35 s。

### 2.3 「对没有任务的工作区会不会超时」

**不会因为「没有任务」而超时**：ack 由 agent service 的订阅注册产生，与任务条数无关；host 侧唯一的额外读是 `Qye(workspaceIdentity)` 为真时的 `listTaskMetas`（远端身份），也在同一请求内。

但 **`runtimePolicy='existing-only'` 在没有活着的 runtime 时会立刻 reject**（快失败，不是超时）：

- `out/host/index.js@546767` `async function lw(y,O="start-if-needed"){if(O==="existing-only"){…let me=t.getExistingClient(y);if(!me)throw nx(y);…}}`
- `out/host/index.js@502914` `function nx(e){let t=new Error("ZCode Agent runtime is not running.");…}`

Rust 侧订阅参数固定带该策略：`conversation.rs:1448 SESSIONS_INDEX_RUNTIME_POLICY="existing-only"`、`:1474 sessions_index_subscribe_args()`。

### 2.4 Android 的调用方式

`HomeViewModel.kt:446-451`（无注册表时）：

```kotlin
engine.conversationCall(bridge, key, "subscribeSessionsIndex", JsonObject(emptyMap()))
```

`ConversationRegistry.kt:89-94` 同形。而 `ZemoteEngine.kt:414-427` 的 `conversationCallNoWait` 文档明确写：

> **wire 上没有顶层 `completed`**：对端只回 `{ack:{subscriptionId}}` … 用 `conversationCall` 等它只会把 ack 延迟变成界面阻塞

两处注释/实现互相矛盾：Rust 确实会合成 `Completed`，所以能等到；但它把「订阅」当成一次性调用，付出的是**串行阻塞**代价。

### 2.5 13 个工作区串行的真实代价

`HomeViewModel.kt:424-478 ensurePreviews`：整段在 `previewSetup.withLock`（`:427`）内，且 `refreshInternal` 只对 bootstrap 工作区逐个 `forEach { ensurePreviews(it) }`（`:377-379`）；`ConversationRegistry.ensure` 自己还有一把 `Mutex`。单工作区最坏路径：

```
openBridge 30s → waitHealthy 45s → conversationOpen(握手 initialize) 35s
→ subscribe ack 35s → readTree 12s → listPinnedTasks 8s + listArchivedTasks 8s
≈ 173 s / 工作区
```

13 个工作区 → **最坏 ~37 分钟**全部串行；任意一个工作区卡住 35 s（或 45 s），后面所有工作区的活动与置顶/归档都要等它。正常情况每个工作区是亚秒级，但这是**无界串行**，不是有界并发。

额外：`projectTreeAttempted` 只在**第一个工作区订阅成功之后**才置位（`:462-466`），所以首个工作区订阅失败 → 项目树永不加载，只能等下一次 `refresh()`。

---

## 3. sessions-index 帧内容（Q3）

### 3.1 帧形状

- 快照（`out/host/chunk-ZSDMP7DM.js@655239` 同段）：`Hte=e.object({protocolVersion:e.literal(1),workspaceId:e.string(),logEpoch:e.string(),sessions:e.array(pC)})`
- 增量：`Jte=e.discriminatedUnion("op",[{op:"session.upserted",session:pC},{op:"session.removed",sessionId}])`
- Rust 状态机：`conversation.rs:2201 SessionsIndexState{workspace_id, log_epoch, seq, sessions, ready}`；`conversation.rs:2234 apply_frame`：
  - `snapshot` → `self.sessions.clear()` 后逐条插入（全量替换）；
  - `deltas` → `to_seq <= seq` 直接丢；`from_seq != seq` 触发 `on_gap()`（→ resync）并**提前返回**（不改 ready）；否则应用 upsert/remove 并 `self.seq = to_seq`；
  - 末尾统一 `self.ready = true`。

Rust 对外暴露的 `sessionsIndex[i]`（`conversation_transport.rs:3940 sessions_index_snapshot`）含 `subscriptionId/disposed/started/listenerId/resyncing/lastFrameAtMs/workspaceId/logEpoch/seq/ready/sessionCount/list`，其中 `list` 是条目原始 JSON 按 `lastActivityAt` 倒序（`conversation.rs:2224 list()`）。Android 读的正是 `list`（`HomeViewModel.kt:746`）。

Android 自己不应用帧：靠 Rust 合成的事件 `"sessionsIndexUpdated"`（`rust/crates/ffi/src/codec.rs:467`）触发重读快照（`HomeViewModel.kt:897-899`）。

### 3.2 是否含 pinned/archived

**不含**，复核对 Round1 成立：`pC` 字段清单里没有；`cvt`/`Pbt`（`web@2073406`）里 `unreadAt` 只从 `previous` 继承（`...n?.unreadAt?{unreadAt:n.unreadAt}:{}`），不是从 entry 读。

### 3.3 覆盖范围

**仅当前工作区**：topic 由 scope 决定 —— `conversation.rs:1458 pub fn sessions_index_topic(scope)` → `format!("sessions-index/{}", identity||path)`；一个 transport 可以有多个 sessions-index 订阅（`conversation_transport.rs:1154` 注释 + snapshot 是数组）。所以「所有项目」必须由**每个工作区各订一次**拼出来 —— Android 正是这样做的（`HomeViewModel.kt:424-478` 每工作区一次）。

---

## 4. 未读与已读（Q4）

### 4.1 web 的完整流程（三条入口，同一个 RPC）

1. **同工作区点开任务**：`markTaskRead(task)` → `web@6956544 async function gar({bridge,services,task})`：
   - 先校验 `gr(task) !== bridge.workspaceKey` 则**跳过**（跨工作区不写）；
   - `typeof task.unreadAt !== 'number'` 则跳过；
   - → `jir({service, target})`。
2. **跨工作区打开**：`switchWorkspace(..., {markTaskReadExpectedUnreadAt: task.unreadAt})`（`web@2349458` 出现在这个参数位上），桥就绪后调 `_ar`。
3. **桥就绪钩子**：`web@6957070 async function _ar({bridge,expectedUnreadAt,services})` → `Mir`（`web@6925013`）：`typeof t!=="number" || !bridge.initialTaskId` 直接 `{ok:true}`；否则 `target = {taskId: bridge.initialTaskId, workspacePath: bridge.workspacePath, …(kind==='remote'?{workspaceIdentity}:{}) , expectedUnreadAt}`。

最终 RPC（`web@6924881 async function jir`）：

```js
await e.setTaskUnread({...t, unread:false})
```

→ `zcode-task.setTaskUnread({taskId, workspacePath, workspaceIdentity?, expectedUnreadAt, unread:false})`。
桌面侧走 CAS 分支（`out/host/index.js@677778`）：`clearTaskUnreadIfMatches({taskId, workspacePath, workspaceIdentity, expectedUnreadAt})`，返回 `{meta, cleared}`。

### 4.2 Android 的 `markRead` 是否等价

`HomeViewModel.kt:251-259` → `mutate(..., "setTaskUnread", {unread:false, expectedUnreadAt:task.unreadAt})`，`mutate` 在 `:796-836` 组装参数：

```kotlin
put("taskId", …); put("workspacePath", workspace.path); workspace.identity?.let{…}
task.remoteSessionId?.let{…}; put("title", …); task.displayStatus?.let{…}
task.updatedAt?.let{…}; task.createdAt?.let{…}; task.unreadAt?.let{…}; task.provider?.let{…}
extra()   // unread=false, expectedUnreadAt
```

**协议层面等价**：同一个 method、同一个 CAS 令牌 `expectedUnreadAt`、同一个 `unread:false`；`zcode-task` 是 host 级通道，用哪个工作区的桥不影响路由（host 按 `args.workspacePath/workspaceIdentity` 定位）。facade 没有请求侧 `.strict()` 校验（`@677778` 直接读 `C.expectedUnreadAt`），多传的 `title/displayStatus/...` 被忽略 —— 这一条是**静态阅读结论，运行期未验证**。

**行为层面不等价（真正的问题在本地状态）**：

- `mutate` 丢弃应答（`:821-828` 只 `channelCall` 然后清 `busyTasks`）；
- 本地 `unreadAt` 的两处合并都是「旧值优先」：`mergeTreeTasks:622 unreadAt = previous.unreadAt ?: task.unreadAt`、`mergeSessionsIndex:777 unreadAt = old.unreadAt ?: task.unreadAt`；
- 全仓没有任何一处把 `unreadAt` 置 `null`（grep 只有写入/读取，见 §5 R2 证据）。

→ **服务端已清、本地永远显示未读**，直到引擎会话重建（`serialChanged` 才重置 `projectTreeAttempted` 并重拉 readTree，`HomeViewModel.kt:353-360`）。

### 4.3 若不等价，正确调用

RPC 不用改，改的是**回写**：把 `setTaskUnread` 的应答 `TaskMeta`（`unreadAt` 缺失即已清）写回该任务，或按事件/刷新重拉 readTree；并且合并时允许 `null` 覆盖旧值。详见 §6 第 2 条。

---

## 5. Android 合并策略风险（Q5，逐条）

### R1【高】`applyTaskMeta` 在元数据调用失败时把 pinned/archived 清零

- `HomeViewModel.kt:641-658 loadTaskMeta`：两次 `channelCall` 都是 `runCatching{…}.getOrNull()`，失败即 `null`；
- `:661-671 metaTasks`：非数组/对象 → `?: return emptyList()`；
- `:701-724 applyTaskMeta`：对工作区内**每个**任务 `copy(pinned = it.id in pinnedIds, archived = it.id in archivedIds)`。

后果：任一 8 s 超时或通道错误 → 该工作区**全部** `pinned/archived` 被置 false，readTree 里正确的值被抹掉；而 `loadTaskMeta` 每个引擎会话只跑一次（`:425` 的 `previewSubscriptions` 早退），**不会自愈**，只能重连/重启。
这正是 Round1 §6 里「pinned 区只剩两个」的**第一候选原因**，比 SQL 过滤假设更直接。

### R2【高】`unreadAt` 只增不减（markRead 无效）

- `:622 unreadAt = previous.unreadAt ?: task.unreadAt`（readTree 合并方向反了：内存值覆盖树值）
- `:777 unreadAt = old.unreadAt ?: task.unreadAt`（索引合并同样）
- `:812-818` mutation 只发不收
- `HomeScreen.kt:508 / :571` 只看 `task.unreadAt != null`

后果：**已读状态清不掉**；`hasUnread`（`:92-93`）永远为真 → 工作区一直自动展开、只显示「未读」，用户每次进入都要手动折叠。

### R3【高·结构性】任务基底方向与 web 相反

- web：基底 = `readTree.tasks`，sessions-index 只往上挂活动（`web@2072273 Mbt` + `web@2073406 Pbt` + `OP @2066420`）。
- Android：`mergeSessionsIndex:769-785` 以 sessions-index 条目为基底重建 `merged`，只额外保留 `it.pinned || it.archived` 的旧任务（`:782-784`）。

后果：**SQL 里有、sessions-index 里没有的任务**（历史导入、cron/自动化、`existing-only` 下没有 runtime 的旧会话）在**已订阅**的工作区里被删除显示。首次 `loadProjectTree` 拿到的这些任务，在下一次 `mergeSessionsIndex` 就被冲掉。

### R4【中】只读项目树的工作区不会被订阅，也不跑元数据

- `refreshInternal:377-379` 只对 **bootstrap** 工作区 `ensurePreviews`；`loadProjectTree`（`:533-608`）新增的 `bridgeable=false` 工作区不在其中；
- 用户点开这些工作区的横条 → `toggleWorkspace`（`:235-248`）→ `ensurePreviews` → `registry.ensure(key, taskId=null, conversationScope=scope)`（`:436`）/ `engine.openBridge(workspace.key)`（`:443`），**不带** `project` / `taskHostId`；
- 而 `WorkspaceItem.bridgeable` 的文档（复核时 `ChatViewModel.kt:70-77`）明说：readTree 补出来的项目开桥必须带 `taskHostId` / `project`，否则桌面会以「目标工作区不在当前桌面窗口中」拒绝。

后果：展开这类工作区 → 报「预览加载失败」，且该工作区永远拿不到活动/新任务。

### R5【中】标题被 sessions-index 覆盖，忽略 `titleSource/titleOverridden`

`mergeSessionsIndex:774-778` 的 `copy` 只保留 `pinned/archived/unreadAt`，`title` 恒取条目；web 的 `cvt` 有保护：`title = titleSource==='custom' ? e.title : (e.title || previous.title)`（`web@2073406`），并且 `titleOverridden = titleSource==='custom' || previous.titleOverridden===true`。

后果：本地改名（SQL `title_overridden=1`）但会话侧 `titleSource!=='custom'` 的任务，标题会被索引标题顶回去。

### R6【中】mutation 之后本地不刷新

`mutate:821-828` 丢弃应答，注释写「真正的列表变化由 workspace-list 推送」；但 `WorkspaceProjection.merge`（复核时 `ChatViewModel.kt:1711-1717`）只在该工作区 `tasks` 为空时保留旧任务 —— **workspace-list 本身不带 tasks**。同时 `loadProjectTree`/`loadTaskMeta` 每会话只跑一次。

后果：置顶/归档/删除/改名成功后，按钮转圈结束但列表不动，直到重进或重连。`zcode-task` 的 `setTaskPinned/archiveTask/setTaskUnread` 都**返回 TaskMeta**，白白丢掉。

### R7【低】`applyTaskMeta` 补进来的任务没有 `hostId`

`metaTasks:676-691` 不设 `hostId`；`AppNavHost.kt:168` 用 `task.hostId ?: workspace.hostId` 兜底。同一工作区通常一致，跨 host 项目才可能错。

### R8【低】去重键只有 `taskId`

`applyTaskMeta:720`、`mergeSessionsIndex:784` 都是 `distinctBy { it.id }`；web 的去重键是 `[hostId, identity||path, taskId]`（`web@2066846 gbt` 内的 `n` Set）。`HomeUiState.pinnedTasks`（`:86-90`）跨工作区扁平化后按 `"pinned-${it.id}"` 做 Compose key，跨 host 同 id 会撞 key。

### R9【已修·确认】早期 `loadProjectTree` 的 key 不一致

审计开始时的版本用 `key = path` 建项目工作区、任务却按 `identity ?: path` 分组 → 远端项目会重复出现且树任务丢失。当前快照已改为 `key = identity ?: path`（`:547`）+ `identityByPath` 预扫（`:523-531`）+ 任务按同一键分组（`:566`），此问题**在当前快照上不成立**。

### R10【确认·无问题】已归档任务不会漏出来

`tasksOf:107` `filter { !it.archived && !it.pinned }`；`pinnedTasks:86-90` `filter { it.pinned && !it.archived }`；`hasUnread:92-93` 排除 archived。与 `web@2066420 OP` 的分组规则一致。代价是**没有归档视图**（`HomeScreen.kt` 无归档区），归档任务只能通过 mutation 反归档。

---

## 6. 最小修正建议（只给方向，不写代码）

1. **删掉对 pinned/archived 的二次计算**：`loadTaskMeta`/`applyTaskMeta` 整体移除，直接用 readTree 的 `pinned/archived`。若因兼容必须保留，只允许在**两次调用都成功**时应用，且对列表里没有的 id **不做否定**（没有证据不等于「未置顶」）。
2. **让 `unreadAt` 可以被清**：合并规则改成「新值优先、允许 null」，并把 `setTaskUnread` 的应答 `TaskMeta` 写回 state（或 mutation 后节流重拉 readTree）。
3. **翻转基底**：`mergeSessionsIndex` 以现有 `workspace.tasks`（readTree 派生）为基底，用条目**更新活动字段**；索引里没有的任务保留而不是丢弃（去掉 `it.pinned || it.archived` 的限制）。
4. **mutation 回写**：`setTaskPinned/archiveTask/unarchiveTask/setTaskUnread/deleteTask` 都有 `TaskMeta` 应答，用它更新对应任务；删除则先摘除再请求。
5. **有界并发**：`previewSetup.withLock` 串行 13 个工作区 → 改为信号量 + 每工作区独立失败，capped 并发（例如 3~4），并且 `projectTreeAttempted` 与订阅解耦（readTree 用任意健康桥先拉，不被首个订阅失败卡死）。
6. **`bridgeable=false` 的工作区**：不要裸 `openBridge`；带 `project`/`taskHostId`，或延后到进入对话页时再订。
7. **标题**：`mergeSessionsIndex` 保留 `title/titleOverridden`，只在 `titleSource==='custom'` 时采用条目标题（对齐 `cvt`）。

---

## 7. 与 Round1 冲突的结论

> 说明：Round1 对 **web/桌面侧**的事实判断本轮全部复核通过（见 §7.2）。冲突集中在「这些结论能不能套到 Android」与「故障归因的优先级」。

### 7.1 冲突/修正

| # | Round1 的说法 | 本轮结论 | 证据 |
|---|---|---|---|
| C1 | §2.2「任务是否存在、是否置顶/归档：**只看 A**（tree.tasks）」，并在 §6 把「Android 靠自己调的是哪个方法来推断」当作合理设计 | 对 web 成立，**对 Android 不成立**：Android 用 `listPinnedTasks/listArchivedTasks` 重算并覆盖 readTree，且失败即清零。这条不能作为 Android 的行为描述 | `HomeViewModel.kt:701-724`、`:641-671`；`out/host/index.js@668760/@669390` |
| C2 | §6 把 `applyTaskMeta` 覆盖问题排在最后（「若库里 pinned>2 且都合规，才查 Android 合并逻辑，需要抓 bridge 请求/响应」） | 升级为**已定位的第一嫌疑**，不需要抓包：`runCatching→null→emptyList()` 的失败清零 + 列表子集语义已足够解释「置顶区只剩两个」 | `HomeViewModel.kt:647-657`、`:671`、`:713-718` |
| C3 | §2.2「未读：`unreadAt` 来自 A」 | 在 Rust/桌面侧成立；**Android 本地永不同步**（合并旧值优先 + 丢弃应答），即使用户已打开过任务 | `HomeViewModel.kt:622`、`:777`、`:812-818` |
| C4 | §1.1「两源分工」（A=基底，B=活动） | 是 web 的分工；**Android 把 B 当基底**，这是本轮新发现的结构性偏差，Round1 未覆盖 | `HomeViewModel.kt:769-785` |
| C5 | §2.4 表格把 `readTree` 的 `unreadAt` 描述为「从 A 来」 | 需要补一句：`includeDeletedTasks:true` **不会**让已删任务进入 `tasks`（只多一个 `deletedTasks` 字段），所以 Android 传 `true` 并不危险；真正危险的是 `includeDeleted:false` 与 web 的 `true` 不一致（仅影响 projects，不影响任务） | `out/host/chunk-LNTVTW5D.js@102497`、`@102643`、`@103095` |

### 7.2 复核通过、与 Round1 一致的结论

- `pinned/archived` **不在** `TaskMeta`/`K0` 与 sessions-index `pC` 里；只有 `readTree.tasks` 带（`out/host/chunk-ZSDMP7DM.js@655239`、`@757156`）。
- `listPinnedTasks` 带 `archived=0`，`listArchivedTasks` **不**过滤 pinned（`out/host/index.js@668760/@669390`）。
- `cvt`/`Pbt` 里 `unreadAt` 只从 `previous` 继承，3.16.1→3.17.1 语义零变化（`web@2073406`）。
- mark-read 的 CAS 语义在桌面侧实现为 `clearTaskUnreadIfMatches`（`out/host/index.js@677778`，Round1 §3.3 的 SQL）。
- 订阅应答是 `{subscriptionId, …}` 形状（本轮精确到 `{ack:{subscriptionId, mode, logEpoch}}`，见 §2.1）。

---

## 8. UNVERIFIED

1. **运行期事实未验证**：ack 实际时延、13 工作区真实总耗时、`listPinnedTasks` 的实际失败率、未读点是否真的不消失（需设备 + 抓包）。
2. **`zcode-task` 请求侧是否做 schema 校验**：静态只看到 facade 直接读 `C.expectedUnreadAt`（`out/host/index.js@677778`），`ht.fromService` 的实现不在 `out/host/*` 内（其它 chunk/包），未逐行确认；因此「Android 多传 title/displayStatus 等键无害」属静态推断。
3. **`readTree.tasks` 是否带 `remoteSessionId`**：`ud()` 只 `...t`（`meta_json`）展开，未确认 `meta_json` 是否含该键；而 Android `mutate` 依赖它做远端 mutation。
4. **`ChatViewModel.kt` 在审计期间被并发修改**（`a21f1f60…` → `4aebe5fe…`，行数 1716 → 1754），本报告中与它相关的只有 `notifyAttention`（复核时 `:1470-1476`）、`WorkspaceProjection.merge`（复核时 `:1711`）与 `bridgeable` 文档（`:70-77`），按函数名成立；行号可能随并发修改漂移。
5. **`HomeViewModel.kt` / `SessionsIndexProjection.kt`** 在本报告快照哈希下未变；若后续被改动，以本报告 §5 的「函数名 + 代码片段」为准重核。
