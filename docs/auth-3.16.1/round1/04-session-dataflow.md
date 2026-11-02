# 04 · 配对成功之后的数据流（bootstrap / workspace / sessions-index / bridge / RPC）

- 审计对象：ZCode Web **3.16.1**（`Reference_web/3.16.1/`）+ 同发布桌面端（`app.asar`）
- 审计方式：只读。网页包用 Node `Buffer.indexOf` 定位后截取局部；桌面端用 `tools/asar.mjs grep` / `dump`。
- **偏移约定（重要）**
  - 「网页证据」的偏移是 **UTF-8 字节偏移**（`Buffer.byteLength(s.slice(0,i),'utf8')` 计算）。
  - 「桌面端证据」的偏移是 `asar.mjs grep` 打印的 **JS 字符串 char 偏移**（文件按 UTF-8 解码后的下标），不是字节偏移。两者不可直接相加比较。
- 标注约定：`CONFIRMED` = 代码/双端 schema 直接读到；`UNVERIFIED` = 未读到权威证据；`SPECULATION` = 推断。

---

## 1. 结论摘要

1. **3.16.1 的 `bootstrap-response.result` 只有 6 个键，顶层没有 `tasks`。** 网页 schema 与桌面端 `buildBootstrapResult` 完全一致：`windowControlSessionId` / `desktopAppVersion?` / `workspaces` / `initialViewState?` / `mobileViewState?` / `mobileTaskViewEpoch?`。`tasks` 已移除（`CONFIRMED`，见 §3、§8-E1/E2）。
2. **任务来自 sessions-index V4 订阅**，不是 bootstrap：`zcode-agent` 通道上的 `subscribeSessionsIndexV4` → `{ack:{subscriptionId}}`，随后数据以 **事件流** `onDynamicSessionsIndexFrame` 推 `snapshot` / `deltas` 帧；客户端 store 维护 `{workspaceId, logEpoch, seq, sessions: Map}`，并按 `lastActivityAt` 倒序投影成 `list`（`CONFIRMED`，§4）。
3. **`pinned` / `archived` / `unreadAt` 不在 sessions-index 条目里。** 真实来源是桌面任务服务（`zcodeTaskService` 的 `getTaskMeta` / `setTaskUnread`，以及 `projectService.readTree({includeDeleted,includeDeletedTasks})` 的任务记录 / `windowControllerService.list` 的活动订阅）。网页端靠 `cvt(entry,{previous})` 把「上一版任务对象」里的这些字段原样带过来，再靠 `mutateTask({kind:'pin'|'archive'|'mark-read'|'mark-unread'})` 写回（`CONFIRMED`，§5）。
4. **登录 token 只在 relay WebSocket 握手上用一次**：`wss://<origin>/ws/remote/v4?token=<terminal token>&mid=<deviceMid>`。`bootstrap-*` / `workspace-list-*` / `workspace-bridge-open` / `rpc-frame` 全部走这条已认证的 relay `data` 通道，**不再附 token**（`CONFIRMED`，§5.4、§8-E3）。
5. **`rpc-frame` 是带 crc32 的分片协议**：`bridgeSessionId/bridgeGeneration/recoveryId` 身份 + `seq`（物理帧）/`messageSeq`（逻辑消息）/`fragmentIndex`/`fragmentCount`/`messageBytes`/`checksum{algorithm:'crc32',value}`/`dataBase64`；上限 1 MiB/帧、16 MiB/消息、64 片、组装超时 30 s；对端用 `rpc-frame-ack{ackMessageSeq}` 确认，带 1 MiB/256 KiB 水位与 8 MiB/45 s 重放缓冲（`CONFIRMED`，§5.3）。
6. **`mobileTaskViewEpoch` 是桌面端每次进入 `paired` 时新生成的随机 id**（`Ka()`），随 bootstrap 下发；移动端 `seed` 后，桌面端用 `mobile-task-view-request{epoch}` 索取、移动端回 `mobile-task-view-update{epoch,seq,bridgeSessionId,bridgeGeneration,view}`。作用是让桌面知道「手机正在看哪个任务」，从而正确写 `unreadAt`（`CONFIRMED`，§6）。
7. **当前实现并没有等待 `subscribeSessionsIndex` 的 `completed`**（`HomeViewModel` / `ChatViewModel` / `ConversationRegistry` 都用 `conversationCallNoWait`）——任务里的前提不成立。反过来，**Rust 侧其实会在 ack 到达后发 `Completed{Ok}`**（`emit_completion` 仅在 `flow.op.is_some()` 时发，而 subscribe flow 的 `op` 是 `Some(...)`），所以 `ZemoteEngine.kt` 里「订阅类 op 没有 completed / 等它只会挂到宿主兜底超时」这句注释是**错的**（`CONFIRMED`，§7.1）。
8. 当前实现与 3.16.1 的**实质**差距在字段映射与任务元数据，而不是订阅等待：`WorkspaceProjection` 仍按旧形状解析顶层 `tasks`（3.16.1 已无该键，静默降级为空）；`pinned/archived/unreadAt` 永远读不到（sessions-index 里没有，也没调任务服务）；`initialViewState`/`mobileViewState`/`mobileTaskViewEpoch`/`mobile-task-view-*` 完全未实现（§7）。

---

## 2. 完整数据流时序图

```
浏览器（手机）                                              Relay(WS)                       桌面端 main
──────────────────────────────────────────────────────────────────────────────────────────────────────────
0) 登录态门控
   localStorage: zcodejwttoken / oauth:*:access_token
   K9n.start(): 无 JWT → onView({kind:'login'})                 ── 不建 WS ──
   Ja(url): 取 sid/mid/name/app_version（有 hash/t 参数 → null）
        ↓
1) 取 terminal token
   POST /api/v1/remote/token  {role:'terminal'}
     Authorization: Bearer <zcode jwt>
   ← {code:0,data:{token,expires_in}}                          (HTTP，不经 relay)
        ↓
2) relay 建连 + 配对
   new WebSocket(`/ws/remote/v4?token=<token>&mid=<mid>`)
   send {type:'auth_init', device_sid, meta:{platform:'web',version,name:'mobile-browser'}}
                                                              ──►  auth_ack{device_sid,pair_status}
                                                              ◄──  pair_status_ack{pair_status}
   state: idle→connecting→authenticating→waiting→paired
        ↓  (state 变 paired 时桌面端 UA(): mobileTaskViewEpoch = Ka())
3) bootstrap（Q9n.check() → ie()）
   send {type:'data',payload:{zcode_type:'bootstrap-request',requestId}}
                                                              ──►  routePayload → buildBootstrapResult
                                                              ◄──  {zcode_type:'bootstrap-response',requestId,success:true,
                                                                    result:{windowControlSessionId,desktopAppVersion,
                                                                            workspaces[],initialViewState,mobileViewState,
                                                                            mobileTaskViewEpoch}}
   校验 desktopAppVersion 与 URL 的 app_version；不一致 → navigate(带新 app_version)
   onAccepted → ee.seed(mobileTaskViewEpoch)
        ↓
4) 选工作区（Xa = qer）
   候选 = workspaces.filter(Ya)          // Ya: kind!=='remote' || (workspaceIdentity && remoteSessionId)
   优先 mobileViewState?.activeWorkspaceKey ?? initialViewState?.activeWorkspaceKey（必须在候选内）
   否则候选[0]；否则 home-only（canBridge:false）
        ↓
5) 开桥（ue）
   send {zcode_type:'workspace-bridge-open', requestId, bridgeSessionId, bridgeGeneration,
         recoveryId?, workspaceKey, taskId?, taskHostId?, fileTreeTaskId?, project?}
                                                              ──►  re()/oe() 建桥
                                                              ◄──  {zcode_type:'workspace-bridge-ready',bridgeSessionId,
                                                                    bridgeGeneration,recoveryId,
                                                                    bridge:{kind:'local'|'remote',workspaceKey,workspacePath,
                                                                            workspaceIdentity?,remoteSessionId?,
                                                                            capabilities:{windowProjectDirectory:true},
                                                                            initialTaskId?}}
   B5n(bridge) → 建立 relay RPC 复用层（identity + 分片 + ack）
   A5n(protocol) → 建立 VS Code 风格 channel 客户端（fileService/zcodeTaskService/…/zcodeAgentService）
        ↓
6) 首帧渲染
   de(services, bridge) → 渲染整个 ZCode UI：
     initialWorkspaceAbsPath = bridge.workspacePath
     initialWorkspaceIdentity = bridge.kind==='remote' ? bridge.workspaceIdentity : undefined
     initialTaskId = bridge.initialTaskId
     restoreSession=false, allowOpenWorkspace=false, allowRemoteWorkspace=false
   Fer(...)  → 若 initialTaskId 且 expectedUnreadAt 是数字：setTaskUnread{unread:false}
   ce(workspaceKey, initialTaskId) → send mobile-view-state-update
        ↓
7) 工作区列表（惰性，侧栏挂载时）
   send {zcode_type:'workspace-list-request',requestId}
                                                              ──►  buildWorkspaceListResult
                                                              ◄──  {workspace-list-response, result:{workspaces,activeWorkspaceKey,activeTaskId}}
   桌面端后续变更 → {zcode_type:'workspace-list-updated', result:{...}}（按签名去重推送）
        ↓
8) sessions-index V4（每工作区一条）
   RPC: zcode-agent.subscribeSessionsIndexV4({workspacePath,workspaceIdentity?,runtimePolicy:'existing-only'})
                                                              ◄──  {ack:{subscriptionId}}
   RPC event: zcode-agent.onDynamicSessionsIndexFrame(scope)  ◄──  帧：
        {toSeq, fromSeq, payload:{kind:'snapshot', snapshot:{workspaceId,logEpoch,sessions[]}}
                              | {kind:'deltas',   deltas:[{op:'session.upserted',session}|{op:'session.removed',sessionId}]}}
   客户端 store：seq 连续性校验 → 断档则 resyncSessionsIndexV4 / forceSnapshot
        ↓
9) 任务渲染
   sessions-index 条目 --cvt(entry,{previous})--> Task（补 pinned/archived/unreadAt/mode/model/provider/traceId）
   与 projectService.readTree 的 tasks 合并（U_t / W_t）→ 按 pinned / timeline / project 过滤排序（GN）
        ↓
10) 手机在看的任务回传
   桌面 → {zcode_type:'mobile-task-view-request', epoch}
   手机 → {zcode_type:'mobile-task-view-update', epoch, seq, bridgeSessionId, bridgeGeneration, view:{workspacePath,workspaceIdentity?,taskId}|null}
        ↓
11) 所有业务数据（对话行、文件、任务元数据…）
   走 rpc-frame 分片（§5.3）→ 对端组装 → channel 调用
```

---

## 3. bootstrap / workspace-list 响应逐字段表

### 3.1 `bootstrap-response.result`（schema 名 `tw`）

证据：`validation-BBf1FA1a.js` @304850；桌面 `out/main/index.js` @393667（`buildBootstrapResult`）。

| 字段 | 类型 | 必填 | 生成方 | 用途 / 消费方 |
|---|---|---|---|---|
| `windowControlSessionId` | string(1..128) | 是 | 桌面：`h.deviceSid` | 会话标识；网页端未直接用于业务判断（`UNVERIFIED` 具体消费点） |
| `desktopAppVersion` | string? | 否 | 桌面：`appVersion` | **版本门控**：`Q9n.check()` 把它和 URL 的 `app_version` 比对，不一致就 `location.replace` 到带新 `app_version` 的 URL（`index` @6771185） |
| `workspaces` | array\<Workspace\> | 是 | 桌面：`getAvailableWorkspaces` | 工作区选择、home-only 列表、`workspace-list` 的基底 |
| `initialViewState` | `{activeWorkspaceKey?, activeTaskId?, updatedAt}`? | 否 | 桌面：`getRuntimeInitialViewState`，仅当 `initialTaskId && isBridgeable(currentWorkspace)` | 兜底落点（`mobileViewState ?? initialViewState`） |
| `mobileViewState` | 同上? | 否 | 桌面运行时内存：最近一次 `mobile-view-state-update` | **首选落点**；重载后恢复手机上次位置 |
| `mobileTaskViewEpoch` | string? | 否 | 桌面：进入 `paired` 时 `Ka()` 随机生成 | 移动端 `ee.seed()`；后续 `mobile-task-view-*` 的 epoch |
| ~~`tasks`~~ | — | **不存在** | — | 3.16.1 已移除（`CONFIRMED`，见 §7.2） |

`Workspace` 元素（schema 名 `YC`，`validation` @303107）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `workspacePath` | string(trim,≥1) | 是 | 真实路径 |
| `workspaceIdentity` | string? | 否 | 远程工作区身份；**有值才出现，不出现 null** |
| `remoteSessionId` | string? | 否 | 远程会话 id |
| `label` | string | 是 | 桌面 `getPathLabel`：把 `\` 归一成 `/`，取最后一段；空则回退整个路径 |
| `workspacePurpose` | `'project'\|'conversation'`? | 否 | `conversation` 是"伪工作区"，手机侧过滤掉 |
| `kind` | `'local'\|'remote'` | 是 | `remoteSessionId \|\| workspaceIdentity ? 'remote' : 'local'` |
| `connectionState` | `'connected'\|'disconnected'\|'reconnecting'`? | 否 | **不在 `Ee()` 归一化输出里**，但 `buildWorkspaceListPushSignature` 会读（`?? 'connected'`），说明注册表里的原始条目可能带它 |
| `lastConnectionError` | string? | 否 | 同上，用于签名与 UI 失败态 |

**工作区键规则**（四处独立实现一致，`CONFIRMED`）：
- 网页：`src-uVqBd_Eq.js` @52038 `function V(e){return e.workspaceIdentity?.trim()||e.workspacePath}`
- 网页：`index` @? `lP(e){return e.workspaceIdentity?.trim()||e.workspacePath}`
- 桌面：`out/host/chunk-5YNPFY3L.js` @551560 `resolveWorkspaceKey`
- 桌面：`out/main/index.js` 的 `Ct` 即该函数

### 3.2 `workspace-list-response.result`（schema 名 `nw`）

证据：`validation` @305022；桌面 `buildWorkspaceListResult` @393934 附近。

| 字段 | 类型 | 必填 | 生成规则 |
|---|---|---|---|
| `workspaces` | array\<Workspace\> | 是 | 同 `getAvailableWorkspaces` |
| `activeWorkspaceKey` | string? | 否 | `mobileViewState?.activeWorkspaceKey ?? initialViewState?.activeWorkspaceKey ?? workspaceKey(currentTarget)`（注意最后一段**总是有值**） |
| `activeTaskId` | string? | 否 | `mobileViewState?.activeTaskId ?? currentBridge?.initialTaskId ?? initialViewState?.activeTaskId` |

`workspace-list-updated` 的载荷就是同一个 `nw`（无 `requestId`，无 `success`）。

`getAvailableWorkspaces(h)`（桌面 @393260 区块）：
```
map = {}
for w in registry.get(windowId) ?? []: map[key(w)] = w      // syncAvailableWorkspaces 写入
cur = currentBridge ?? {workspacePath,workspaceIdentity,remoteSessionId,label,kind}
if isBridgeable(cur) && !map.has(key(cur)): map[key(cur)] = cur
return values(map)
```

### 3.3 桥打开参数与 `workspace-bridge-ready`

请求（`validation` @305613 区块）：

| 字段 | 必填 | 说明 |
|---|---|---|
| `requestId` | 是 | |
| `bridgeSessionId` | 是 | 每次开桥新生成（`Z9('bridge')`） |
| `bridgeGeneration` | 否 | 客户端自增计数（`++m`），用于丢弃过期 ready |
| `recoveryId` | 否 | 恢复轮次 id（`Z9('recovery')`），`bridge-rebuild-retry` 时复用 |
| `workspaceKey` | 是 | |
| `taskId` / `taskHostId` / `fileTreeTaskId` | 否 | 三者互斥约束（见 schema `.refine`） |
| `project` | 否 | `{projectId,hostId?,draftId?,reuseDraft?}`，仅"未分类草稿"可复用目录 |

响应 `bridge`（schema `ZC`，@303792）是 `kind` 判别联合：

| kind | 字段 |
|---|---|
| `local` | `bridgeSessionId, bridgeGeneration?, recoveryId?, kind:'local', workspaceKey, workspacePath, initialTaskId?` |
| `remote` | 同上 + `workspaceIdentity, workspacePurpose?, remoteSessionId, capabilities?:{windowProjectDirectory?:true}` |

桌面侧 `toExternalBridge`（`out/main/index.js` @384802）：`remote` 缺 `workspaceIdentity` 或 `remoteSessionId` 直接抛错；`capabilities.windowProjectDirectory` 恒为 `true`。

---

## 4. sessions-index 条目逐字段表 + 任务映射规则

### 4.1 传输与帧

- 通道：`zcode-agent`（`Channels::ZCODE_AGENT = "zcode-agent"`，`rust/crates/protocol/src/channel_client.rs` @714）
- 方法（桌面 `out/host/index.js` @239705 区块）：
  - `subscribeSessionsIndexV4({workspacePath,workspaceIdentity?,runtimePolicy})` → `{ack:{subscriptionId}}`
  - `unsubscribeSessionsIndexV4({...,subscriptionId,runtimePolicy})`
  - `resyncSessionsIndexV4({...,subscriptionId,base?,forceSnapshot?})`
  - `reconcileSessionsIndexV4({workspaceId,sessionIds})`（内部路由 `v4/sessionsIndex/reconcile`）
  - 事件：`onDynamicSessionsIndexFrame(scope)` —— **是 RPC event，不是 request/response**
- 订阅应答（`ack`）形状（桌面 `out/host/index.js` @186422 `Hd`）：
  `{ack:{subscriptionId:string, mode:'snapshot'|'resume', logEpoch:string}}` —— **线上只有这一条响应，没有顶层 `completed`**。
  （`completed` 只存在于 FFI 事件流：Rust 在 ack 到达后自己 `emit_completion(Completion::Ok)`，`conversation_transport.rs` @3174。）
- 帧形状（网页 `gvt` @2030973；桌面 `out/host/index.js` @186990 `P_`）：
```
{ topic:'sessions-index/<workspaceIdentity||workspacePath>',
  subscriptionId: string, sentAt: number,
  toSeq: number, fromSeq?: number,
  payload:
    | { kind:'snapshot', snapshot:{ protocolVersion:1, workspaceId, logEpoch, sessions:[Entry] } }
    | { kind:'deltas',   deltas:[ {op:'session.upserted', session:Entry} | {op:'session.removed', sessionId} ] } }
```
  注意 `topic` / `subscriptionId` / `sentAt` 是线上必带字段（早期版本漏写）。
- 协议里**没有 `ready` 字段**：`ready` 是客户端本地概念（Rust `SessionsIndexState.ready`，由 `sessions_index_snapshot` 合成；网页用 `status` + `awaitingInitial` 表达同一件事）。
- 网页 `sessions-index` store 的 `status` 枚举：`idle / connecting / live / error / dormant`；「还在加载」的判据是
  `Evt(s) = s.workspaceId===null && (status==='idle'||status==='connecting')`（`index` @2046045），**此时空列表不等于「没有任务」**。
- 客户端 store 规则（网页 `_vt` 类）：`snapshot` → 重建 Map 并记 `seq=toSeq`；`deltas` 要求 `fromSeq === seq`，否则判为断档 → `requestRecovery` / `connect(forceSnapshot:true)`；`toSeq <= seq` 视为重复帧丢弃。
- 订阅重试：subscribe 瞬时失败退避 `[250,1000,3000] ms`；错误恢复退避 `[5s,15s,60s]`；`EBUSY/EMFILE/ENFILE/config.json.lock` 视为可重试；`ZCODE_AGENT_RUNTIME_UNAVAILABLE` 走 runtime 不可用分支。
- 生命周期事件：`onAgentRuntimeRestarted` / `onAgentRuntimeLifecycle` → 清空 store、丢弃 subscriptionId（generation++）。

### 4.2 条目 schema（`Cx`，`validation` @248245）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sessionId` | string | 是 | **就是协议里的 `taskId`** |
| `workspaceId` | string | 是 | **宿主侧的工作区 id，不是 `workspaceKey`**（`workspaceKey` 是 `workspaceIdentity?.trim() \|\| workspacePath`，两者不可互推；本报告早期版本写成「工作区键」，已撤销） |
| `parentSessionId` | string? | 否 | → 任务 `forkedFromTaskId` |
| `title` | string | 是 | |
| `titleSource` | `'default'\|'generated'\|'custom'`? | 否 | `custom` → `titleOverridden` |
| `lastOutcome` | `'completed'\|'error'\|'interrupted'`? | 否 | 与 `phase` **并存**，任务状态映射只用 `phase` |
| `phase` | `'draft'\|'prewarming'\|'running'\|'completedSuccess'\|'completedInterrupted'\|'error'` | 是 | 任务状态主来源 |
| `sessionEnded` | boolean | 是 | 手机端当前实现未消费 |
| `hasBackgroundWork` | boolean | 是 | 后台任务在跑 |
| `workflowActivity` | object? | 否 | 工作流活动（结构在 `sm`） |
| `pendingInteraction` | `{interactionId,kind:'permission'\|'userInput',toolName?,autoResolution?}`? | 否 | 待审批/待输入 |
| `pendingInteractionSummary` | `{permissionCount,userInputCount}`? | 否 | 汇总角标 |
| `goalStatus` | `'active'\|'paused'\|'verifying'\|'verified'\|'notSatisfied'\|'failed'`? | 否 | 取自 `Lv.shape.status`（`validation` @220194）。**网页远程主包 0 命中，未被消费**（本报告早期版本写成 `pending\|inProgress\|completed`，已撤销） |
| `lastActivityAt` | number | 是 | → 任务 `updatedAt` |
| `lastAssistantPreview` | string? | 否 | 最后一条助手预览 |
| `createdAt` | number | 是 | |

**注意：schema 里没有 `pinned` / `archived` / `unreadAt` / `mode` / `model` / `provider` / `traceId`。**

### 4.3 条目 → 任务（网页 `cvt`，`index` @2026736）

```
status   = svt(phase): running|prewarming → 'running'
                       completedSuccess|completedInterrupted → 'completed'
                       error → 'error'
                       其他(draft) → undefined
title    = (previous.titleOverridden && titleSource!=='custom') ? previous.title
           : (entry.title || previous.title || '')
titleOverridden = titleSource==='custom' || previous.titleOverridden
taskId   = entry.sessionId
traceId  = previous.traceId ?? `session-${entry.sessionId}`
createdAt= entry.createdAt || previous.createdAt || 0
updatedAt= entry.lastActivityAt || previous.updatedAt || 0
mode     = previous.mode ?? 'default'
model    = previous.model
provider = previous.provider
forkedFromTaskId = entry.parentSessionId
pendingInteraction = entry.pendingInteraction
unreadAt = previous.unreadAt
```
再挂一个非枚举字段 `__zcodeSessionActivity = {phase,lastActivityAt,hasBackgroundWork,pendingInteractions,workflowActivity}`（`HO`，`index` @?），供 `jit/Mit/Nit` 判断"运行中/有后台/有待审批"。

排序与分组（`GN`，`index` @2020424）：
- `kind='pinned'`：`pinned && !archived`
- `kind='timeline'`：`!archived`
- `kind='project'`：`projectId===id && hostId 匹配 && !archived && !pinned`
- 组内按 `updatedAt`（`timeline`）或 `created` 排序

侧栏状态（`Hut`，`index` @1844635）：
```
phase==='error' 或 (无 activity 且 task.status==='error') → 'error'
typeof unreadAt === 'number'                              → 'unread'
phase==='prewarming'||'running'                           → 'loading'
否则                                                      → 'none'
```

### 4.4 任务列表的**两个来源**（重要）

网页 `Fvt` 钩子（`index` @2058596 附近）：

```
tree      = await projectService.readTree({includeDeleted:true, includeDeletedTasks:true})
            （+ 每个远程 host 各一次；remoteProjectService.readDirectory 做目录同步）
activity  = controller ? controller.readActivities() : Dvt(workspaces).items 的 __zcodeSessionActivity
tasks     = U_t(tree, activity)     // 把 activity 贴到 tree.tasks 上
```

- `Dvt`（sessions-index → 任务列表）**只在没有 windowController 通道时**才被调用（`s ? [] : j`）。
- 有 `windowControllerService` 时，任务基底来自 `readTree`，活动来自 `controller.list(kind,{workspaceScopes,sortBy,limit})` 的订阅推送。
- `unreadAt` 的写入/清除由 `uP(mutateTask)` 完成，并有 `mark-read` / `mark-unread` 的"补记/回滚"队列（`jvt`）。

> 手机 Web 远端里 `windowControllerService` 是否存在 → 见 §10-U1（`UNVERIFIED`）。两条分支都已被证实存在，差别只在"谁提供任务基底"。

---

## 5. 任务元数据（pinned / archived / unread）来源专项

### 5.1 结论

| 字段 | 在 sessions-index 里？ | 真实来源 | 写入通道 |
|---|---|---|---|
| `pinned` | **否** | 桌面项目树 `projectService.readTree` 的 task 记录；`zcodeTaskService.listPinnedTasks/listPinnedTaskIds` 亦可 | `zcodeTaskService.setTaskPinned({pinned})`；控制器 `mutateTask({kind:'pin'})` |
| `archived` | **否** | 同上（`readTree` 的 `includeDeletedTasks` 分支 + `listArchivedTasks`） | `archiveTask` / `unarchiveTask`；`mutateTask({kind:'archive'})` |
| `unreadAt` | **否** | 桌面任务服务：`getTaskMeta(address)` 返回的 `unreadAt`；控制器 `onDynamicTaskUnreadSignal()` 推送 | `setTaskUnread({unread, expectedUnreadAt})`；`mutateTask({kind:'mark-read'\|'mark-unread'})` |

证据（网页）：
- `uP(e,t,n,r)`（`index` @2048033）就是 `mutateTask` 的映射表：
  `archive→archiveTask/unarchiveTask`、`delete→deleteTask`、`rename→renameTask`、`pin→setTaskPinned`、`mark-read→setTaskUnread({unread:false,expectedUnreadAt})`、`mark-unread→setTaskUnread({unread:true})`
- `Avt`（@2047917）= `controller.getTaskMeta({address})` / `service.getTaskMeta(target)`
- `Per`（@6801749）= 打开任务时标已读：跨 workspace 直接跳过；`typeof unreadAt!=='number'` 也跳过；否则 `setTaskUnread({unread:false, expectedUnreadAt})`
- `Fer`（@6802275）= 桥打开后按 `bridge.initialTaskId` + `expectedUnreadAt` 标已读
- 客户端还有一层"乐观 overlay"：`taskUnreadOverlayByEntityKey` / `optimisticTaskListByTaskId` / `taskUnreadByTaskId`（本地状态，跨刷新不保留，`SPECULATION` 关于持久化）

### 5.2 为什么 `cvt` 要带 `previous`

因为 sessions-index 不提供元数据，`cvt` 必须从**上一版任务对象**继承 `pinned/archived/unreadAt/mode/model/provider/traceId`。一旦 `previous` 缺失（例如首次冷启动且 `readTree` 还没回来），这些字段会退化成 `undefined`/默认值——这正是"任务列表先出标题、元数据后补"的原因。

### 5.3 RPC 分片（`rpc-frame`）精确形状

`validation` @161461：

```
rpc-frame = {
  zcode_type:'rpc-frame',
  bridgeSessionId, bridgeGeneration?, recoveryId?,
  seq: int>0,                 // 物理帧序号
  messageSeq: int>0,          // 逻辑消息序号
  fragmentIndex: 0..63,
  fragmentCount: 1..64,
  messageBytes: int>0,        // 逻辑消息总字节
  checksum: {algorithm:'crc32', value:/^[0-9a-f]{8}$/},
  dataBase64: string          // 必须 base64 可解且 crc32 与 checksum 一致
}
rpc-frame-ack = { zcode_type:'rpc-frame-ack', bridgeSessionId, bridgeGeneration?, recoveryId?, ackMessageSeq }
```
限额（`Fp`/`_m`，`validation` @148528 / @160412 附近）：
`maxPhysicalFrameBytes=1MiB`、`maxMessageBytes=16MiB`、`maxFragments=64`、`assemblyTimeoutMs=30s`、
`logicalFrameAssemblyMaxFragments=1024`、`logicalFrameAssemblyMaxConcurrent=32`、`logicalFrameAssemblyMaxStagedBytes=32MiB`。
运行时流控（`index` @6696132 `O9`）：高水位 1 MiB / 低水位 256 KiB / 重放缓冲 8 MiB / 重放宽限 45 s。
降级原因码：`remote.rpcFrame.{encodingFailed,outerMeterFailed,replayBufferExceeded,sendFailed,invalidPayload,deliveryFailed,futureAck,ackGraceExceeded,replayGraceExceeded}`。

### 5.4 token 归属（明确回答）

- **relay WS URL**：`?token=<terminal token>&mid=<deviceMid>`（`index` @6633951）。terminal token 由 `POST /api/v1/remote/token {role:'terminal'}` 用 ZCode JWT 换取（`index` @6762730），`expires_in` 字段存在但手机端**没有**在会话中途刷新 token 的逻辑（重连时重新 `getToken`）。
- **bootstrap / workspace-list / bridge-open / mobile-view-state / rpc-frame**：都是 relay `{type:'data',payload:{zcode_type,...}}` 的内层载荷，**不再附 token**（`CONFIRMED`：`sendPayload` 只做 JSON 序列化 + 尺寸校验）。
- 由 relay 透传 vs 桌面本地处理：
  - relay 只做**帧级透传**（`type:'data'` 包装 + `client_ts`），不看 `zcode_type`。
  - 桌面端 `routePayload`（`out/main/index.js` @402816）本地处理：`bootstrap-request`、`workspace-list-request`、`platform-request`、`mobile-view-state-update`、`mobile-task-view-update`、`workspace-bridge-open`、`workspace-reconnect-request`、`rpc-frame/rpc-frame-ack`、`telemetry-report`、`mobile-diagnostic`。
  - 桌面端主动推送：`workspace-list-updated`、`workspace-bridge-ready`、`workspace-bridge-error`、`bridge-degraded`、`app-error`、`mobile-task-view-request`。

---

## 6. 刷新 / 重载 / 恢复分支表

| 触发 | 网页行为 | 数据后果 |
|---|---|---|
| `visibilitychange→hidden` / `pagehide` / `freeze` | `onSuspend`：`ee.suspend()`（发一次 `mobile-task-view-update{view:null}`）+ `te.suspend()`（未认证则关 socket；置 `suspended`） | 桥与 store 保留在内存；不发新请求 |
| `visible` / `pageshow` / `online` / `resume` | `B(reason)` = 重建：`te.recoverConnection()`（默认 15 s） | 若桥未降级且页面在 `/remote/v4`：只跑一次 bootstrap 门控（`oe()`），**不重开桥** |
| 同上但桥已降级 | 先 `recoverWorkspace` 再 `I({preferredWorkspaceKey,preferredTaskId})` | 重新 bootstrap + 重开桥（`recoveryId` 复用） |
| 重连预算耗尽 | `z()`：`F9n(f)` = `[1s,2s,5s]`，超预算 → `P(...)` 致命 | 停止重试 |
| 网络闪断后 relay 重连 | `onSendReady({kind:'same-socket'\|'reconnected-socket'})` → `replayUnacknowledged()` | rpc 重放缓冲（8 MiB / 45 s）内的未确认消息重发 |
| 桌面 runtime 重启 | `onAgentRuntimeRestarted` → generation++，清 store/subscription | sessions-index 全量重订阅（forceSnapshot） |
| 页面整页刷新 | 全部重建：`ntr()` → `Ker()` 取 wsUrl → `Y9.start()` | 登录态来自 localStorage；配对态来自 URL 的 `sid/mid`；**内存态全丢**（桥、store、`mobileViewState` 由桌面端回给手机） |
| 手机换设备/换标签 | `zcode-web-remote-client-id:<sid>` 生成稳定 clientId（`Yer`） | 桌面端据此识别"同一个手机" |
| 桌面端重启 | 桌面从 `startupRestoreStorageProvider` 恢复 `syncAvailableWorkspaces` 列表 | `workspace-list-updated` 立即推送；`mobileViewState`/`mobileTaskViewEpoch` 是否持久化 → §10-U2 |

`mobileViewState` / `mobileTaskViewEpoch` 的作用（`CONFIRMED`）：

| 字段 | 生成 | 生命周期 | 作用 |
|---|---|---|---|
| `mobileViewState` | 手机 `ce()` 每次切工作区/任务都发 `mobile-view-state-update{viewState,deviceInfo}` | 桌面端内存 `h.mobileViewState`；不随配对重置 | 决定 bootstrap 落点、`workspace-list` 的 `activeWorkspaceKey/activeTaskId`；重载后恢复手机上次位置 |
| `mobileTaskViewEpoch` | 桌面端 `UA()`：`transportState` 由非 `paired` → `paired` 时 `= Ka()`；离开 `paired` 时 `= undefined` | 每次配对周期一个新值 | 防止**旧页面**的 `mobile-task-view-update` 污染桌面端的"手机正在看哪个任务"；`Be()` 校验 `epoch` + `seq` 单调 + `bridgeSessionId/bridgeGeneration` + `view.workspaceKey` 匹配当前桥 |
| `mobileTaskView` | 手机 `Z9n.update()` 推 `mobile-task-view-update` | 桌面内存 | 桌面用它判断"该任务正在被手机查看"→ 自动标已读/写未读（`jvt` 的 `mark-read`/`mark-unread` 队列） |

---

## 7. 与当前 Rust / Kotlin 差异清单

### 7.1 「是否错误等待 `subscribeSessionsIndex` 的 `completed`」——**前提不成立**

- `CONFIRMED`：`HomeViewModel.ensurePreviews()`（第 407–413 行）、`ChatViewModel.openWorkspaceInternal()`（第 908–913 行）、`ConversationRegistry.ensure()`（第 71–77 行）**都调用 `conversationCallNoWait`**，不等待 `completed`。
- `CONFIRMED`：`ZemoteEngine.conversationCallNoWait` 的文档注释（第 396–401 行）称"Rust 侧订阅流程**不会**再发顶层 `completed`"。**这句与 Rust 源码不符**：
  - `rust/crates/protocol/src/conversation_transport.rs`：`on_subscribe_response()` 成功分支末尾 `emit_completion(flow, Completion::Ok, events)`；
  - `emit_completion` 仅在 `flow.op.is_some()` 时发，而 `new_op_flow()` 对 subscribe 也设 `op: Some(op.to_string())`；
  - `rust/crates/ffi/src/conversation.rs` 把 `ConversationEvent::Completed` 映射成 `codec::completed_event(conversation_op_token(key, token), ...)` 推给宿主。
  → 结论：**用 `conversationCall` 也能返回**（在 ack 之后）；用 `NoWait` 是防御性选择，功能上无害，但注释必须改正，否则会误导后续排障。

### 7.2 差异表

| # | 主题 | 当前实现 | 3.16.1 权威行为 | 判定 |
|---|---|---|---|---|
| D1 | bootstrap 形状 | `WorkspaceProjection.snapshotOrNull` 仍读 `root["tasks"]` 并做 `byKey` 归位（`ChatViewModel.kt` 第 1544–1553 行） | bootstrap 无 `tasks`；任务全部来自 sessions-index | `CONFIRMED`（代码死分支，静默为空，不是崩溃点） |
| D2 | 顶层 `tasks` 兜底 | 若 `tasks` 不存在则 `Snapshot.tasks = []` | 一致（就是空） | `CONFIRMED` 无害 |
| D3 | 任务来源 | `SessionsIndexProjection.tasks()` 读 `sessionsIndex[i].list` | 权威路径之一（无 controller 时）；有 controller 时以 `readTree` 为基底 + activity | `CONFIRMED`（见 §4.4；U1 决定是否等价） |
| D4 | `sessionsIndexUpdated` 事件 | 监听 `EngineEvent.ConversationEvent` 且 `type=="sessionsIndexUpdated"`（`HomeViewModel` 第 561 行） | **网页/桌面都没有这个字符串**（`validation`/`index`/`out/host` 均 0 命中）；它是 Rust 自己合成的事件（`rust/crates/ffi/src/codec.rs` @467、@1638） | `CONFIRMED`（自造事件，等价于"帧已应用"通知；但与桌面协议无关，换实现时不能指望对端发它） |
| D5 | 订阅等待 | `conversationCallNoWait` | 订阅是"ack + 事件流"，帧不会随 `completed` 一起到 | `CONFIRMED` 行为正确，注释错误（§7.1） |
| D6 | `pinned`/`archived`/`unreadAt` | 从 sessions-index 条目读（永远缺失）→ 恒 `false`/`false`/`null`；`SessionsIndexProjection` 注释也承认"等桌面端提供任务元数据通道后再补" | 来自 `zcodeTaskService.getTaskMeta` / `setTaskUnread` / `readTree`；`cvt` 用 `previous` 继承 | `CONFIRMED` **功能缺口**：置顶区恒空、未读自动展开永不触发、归档任务无法隐藏 |
| D7 | 状态映射 | `hasBackgroundWork \|\| phase in (running,prewarming)` → `running` | `svt`：只看 `phase`；`hasBackgroundWork` 单独作为 `Mit()` 的"有后台"标记 | `CONFIRMED` 偏差（有后台但 phase=completed 时会被标成 running） |
| D8 | `titleSource` | 未使用 | `custom` → `titleOverridden`；继承旧标题时优先旧值 | `CONFIRMED` 缺口（自定义标题在 `readTree` 未回时会被覆盖） |
| D9 | `initialViewState` / `mobileViewState` | 未读取 | 决定 bootstrap 落点（`Xa`：优先 `mobileViewState ?? initialViewState` 且必须在 bridgeable 集合内） | `CONFIRMED` 缺口（重载后落点由本地状态决定，可能与桌面记录不一致） |
| D10 | `mobileTaskViewEpoch` | 未实现（Rust 全仓 0 命中） | bootstrap 下发 → `seed` → `mobile-task-view-request/update` | `CONFIRMED` 缺口（桌面端无法知道手机在看哪个任务 → 已读/未读行为不对） |
| D11 | `workspacePurpose='conversation'` 过滤 | 有（`visibleWorkspaces` / `refreshInternal` / `ensureMutationBridge`） | 一致（`Xa` 不特判，但侧栏 home-only 与 `GN` 会自然排除） | `CONFIRMED` 一致 |
| D12 | 工作区键规则 | `identity ?: path`（`WorkspaceProjection.toWorkspace`） | `identity?.trim() \|\| path` | `CONFIRMED` 等价（`strField` 已 trim + 非空过滤） |
| D13 | 桥可开性判定 | 无（直接 `openBridge`，失败报错） | `Ya`：`kind!=='remote' \|\| (workspaceIdentity && remoteSessionId)`；不可开则走 home-only | `CONFIRMED` 缺口（远程未连接的工作区会抛错而不是灰显/重连） |
| D14 | `bridgeGeneration` | 未使用（Rust `open_bridge` 只带 `workspaceKey/taskId`） | 每次开桥自增，用于丢弃过期 ready | `UNVERIFIED`（Rust `zemote_client` 是否内部维护，需查 `open_bridge` 实现） |
| D15 | `recoveryId` | 未使用 | 恢复轮次 id，重开桥时复用 | `UNVERIFIED` |
| D16 | `lastAssistantPreview` | 用作任务预览行（`previews`） | 在 schema 里；网页 `cvt` **不**把它映射进 Task（用途见 U3） | `SPECULATION`（手机端自定义用法，数据存在所以可用） |
| D17 | `sessionEnded` / `lastOutcome` / `goalStatus` / `workflowActivity` | 全部未消费 | `sessionEnded` / `lastOutcome` / `goalStatus` 网页远程主包 **0 命中**（不消费）；但 **`workflowActivity` 是被消费的**：`cvt` 映射进 `__zcodeSessionActivity`，列表渲染工作流指示器（`index` @2223939） | `CONFIRMED` 前三个未消费非缺口；`workflowActivity` 是**真实缺口**（见 §9.6） |
| D18 | 任务 mutation 参数 | 传整个任务摘要 + `extra` 到 `zcode-task` 通道 | 通道名 `zcode-task` 正确；但网页走 `mutateTask`/`controller.mutateTask`，参数是 `{address:{workspacePath,workspaceIdentity?,taskId,remoteSessionId?,sourceServerId?}} + mutation` | `UNVERIFIED`（方法名/参数形状需真机或抓包验证） |
| D19 | 未读标记时机 | `markRead()` 由 UI 在进入对话约 2s 后调用 | 网页：`Per` 打开任务即标（带 `expectedUnreadAt` 乐观并发），桥打开时 `Fer` 再标一次 | `CONFIRMED` 时序差异；且缺 `expectedUnreadAt` 并发保护 |
| D20 | `workspace-list-updated` | 监听并整体替换 `workspaces`（会丢掉本地 `tasks`） | `k(e)` 只更新 `{...e, activeWorkspaceKey ?? l, activeTaskId ?? u}`，任务列表由 sessions-index 另行维护 | `CONFIRMED` 风险：推送到达时本地任务可能被清空一拍 |

---

## 8. 证据索引

> 网页 = `E:\Zcode_android\Reference_web\3.16.1\`；偏移为 UTF-8 字节偏移。
> 桌面 = `C:\Users\wdn32\AppData\Local\Programs\ZCode\resources\app.asar` 内路径；偏移为 char 偏移（asar.mjs 输出）。

### E1 bootstrap / workspace-list schema（网页）
| 文件 | 偏移 | 片段 |
|---|---|---|
| `assets/validation-BBf1FA1a.js` | 303107 | `YC=D(O({workspacePath:Q,workspaceIdentity:Q.optional(),remoteSessionId:Q.optional(),label:Q,workspacePurpose:M([`project`,`conversation`]).optional(),kind:M([`local`,`remote`]),connectionState:M([`connected`,`disconnected`,`reconnecting`]).optional(),lastConnectionError:S().optional()}))` |
| 同上 | 303792 | `ZC=A(`kind`,[O({bridgeSessionId:$,bridgeGeneration:w().int().nonnegative().optional(),recoveryId:$.optional(),kind:N(`local`),workspaceKey:Q,workspacePath:Q,initialTaskId:Q.optional()}),O({...kind:N(`remote`),...workspaceIdentity:Q,workspacePurpose:...,remoteSessionId:Q,initialTaskId:Q.optional(),capabilities:O({windowProjectDirectory:N(!0).optional()}).optional()})])` |
| 同上 | 304319 | `QC=O({activeWorkspaceKey:Q.optional(),activeTaskId:Q.optional(),updatedAt:w().finite()})` |
| 同上 | 304408 | `$C=O({workspacePath:Q,workspaceIdentity:Q.optional(),taskId:Q}).strict()`（mobile task view） |
| 同上 | **304850** | `tw=O({windowControlSessionId:Q,desktopAppVersion:Q.optional(),workspaces:YC,initialViewState:QC.optional(),mobileViewState:QC.optional(),mobileTaskViewEpoch:$.optional()})` ← **无 tasks** |
| 同上 | 305022 | `nw=O({workspaces:YC,activeWorkspaceKey:Q.optional(),activeTaskId:Q.optional()})` |
| 同上 | 305336 起 | `O({zcode_type:N(`bootstrap-response`),requestId:Q,success:N(!0),result:tw})`、`O({zcode_type:N(`workspace-bridge-open`),requestId:Q,bridgeSessionId:$,bridgeGeneration:...,recoveryId:$,workspaceKey:Q,taskId:Q.optional(),taskHostId:Q.optional(),fileTreeTaskId:Q.optional(),project:...})` |

### E2 桌面端 bootstrap / workspace-list 生成
| 文件 | 偏移 | 片段 |
|---|---|---|
| `out/main/index.js` | 393260 | `function Ee(h){return{workspacePath:h.workspacePath,workspaceIdentity:h.workspaceIdentity,remoteSessionId:h.remoteSessionId,label:Xu(h.workspacePath),kind:h.remoteSessionId\|\|h.workspaceIdentity?"remote":"local"}}` + `getAvailableWorkspaces` + `getRuntimeInitialViewState` |
| 同上 | 393667 | `function A(h){return{windowControlSessionId:h.deviceSid,desktopAppVersion:e.appVersion,workspaces:Le(h),initialViewState:T(h),mobileViewState:h.mobileViewState,mobileTaskViewEpoch:h.mobileTaskViewEpoch}}s(A,"buildBootstrapResult")` |
| 同上 | 393934 | `function L(h){let b=ce(h),D=T(h);return{workspaces:Le(h),activeWorkspaceKey:h.mobileViewState?.activeWorkspaceKey??D?.activeWorkspaceKey??Ct(b),activeTaskId:h.mobileViewState?.activeTaskId??h.currentBridge?.initialTaskId??D?.activeTaskId}}` + `buildWorkspaceListPushSignature`（读 `connectionState ?? "connected"`、`lastConnectionError`）+ `pushWorkspaceListUpdated` |
| 同上 | 402816 | `function kd(h,b){switch(b.zcode_type){case"bootstrap-request":_(h,b.terminalClientSessionId),Xe(h,{zcode_type:"bootstrap-response",requestId:b.requestId,success:!0,result:A(h)});break;case"workspace-list-request":...case"workspace-bridge-open":ve(h,b);break;case"rpc-frame":case"rpc-frame-ack":Et(h,b);break;...}}s(kd,"routePayload")` |
| 同上 | 400892 | `respondToWorkspaceBridgeOpen` → `Xe(h,{zcode_type:"workspace-bridge-ready",requestId,bridgeSessionId,...,bridge:D})` |
| 同上 | 384802 | `s(gN,"toExternalBridge")`：remote 必须 `workspaceIdentity && remoteSessionId`，`capabilities:{windowProjectDirectory:!0}` |
| 同上 | 383908 / 384022 | `getPathLabel`（`\`→`/`，取末段）；`isBridgeableRemoteTarget`：`e.kind!=="remote"\|\|!!(e.workspaceIdentity&&e.remoteSessionId)` |
| 同上 | 411610 | `syncAvailableWorkspaces(h,b){a.set(h,b);let D=t.get(h);D&&M(D)}` —— 渲染进程把窗口工作区列表注册进来并立即推 `workspace-list-updated` |

### E3 relay / token / 配对
| 文件 | 偏移 | 片段 |
|---|---|---|
| `assets/index-DbEHT6Gg.js` | 6749437 | `var M9n=class{...}`（relay 客户端；`state: idle/connecting/authenticating/waiting/paired/kicked/suspended`） |
| 同上 | 6633951 | `let n=await this.options.getToken(t.signal);...r.searchParams.set(`token`,n),this.options.deviceMid?.trim()&&r.searchParams.set(`mid`,this.options.deviceMid)` |
| 同上 | 6762730 | `function N9n(e,t=fetch){...t(new URL(Er.token.path,e),{method:`POST`,headers:{Authorization:`Bearer ${n}`...},body:JSON.stringify({role:`terminal`})...return s.data.token}` |
| 同上 | 6747754 | `function E9n` = `GET /api/v1/remote/device`（设备列表） |
| 同上 | 6632663 区块 | `auth_init{device_sid,meta:{platform:`web`,version:appVersion??`web`,name:`mobile-browser`}}`；`auth_ack`/`pair_status_ack`/`data`/`error`；错误码 `KICKED/DEVICE_OFFLINE/AUTH_FAILED/WRONG_PARAM/INTERNAL` |
| `assets/src-uVqBd_Eq.js` | 51830 | `function Ja(e){if(e.has(`hash`)\|\|e.has(`t`))return null;...[[`deviceSid`,`sid`],[`deviceMid`,`mid`],[`deviceName`,`name`],[`appVersion`,`app_version`]]...}` |
| 同上 | 52038 | `function V(e){return e.workspaceIdentity?.trim()\|\|e.workspacePath}` |
| 同上 | 52104 | `function Ya(e){return e.kind!==`remote`\|\|!!(e.workspaceIdentity&&e.remoteSessionId)}` |
| 同上 | 52188 | `function Xa({workspaces:e,mobileViewState:t,initialViewState:n}){...}`（默认落点选择） |
| 同上 | 53807 | `io={register:{method:`POST`,path:`/api/v1/remote/device`},list:...,token:{method:`POST`,path:`/api/v1/remote/token`}}` |

### E4 bootstrap 门控 / 版本校验 / 任务视图
| 文件 | 偏移 | 片段 |
|---|---|---|
| `assets/index-DbEHT6Gg.js` | 6771185 | `function Q9n(e){...check(){...requestBootstrap().then(a=>{let s=a.desktopAppVersion?.trim();if(s){let t=new URL(e.getUrl());if(t.pathname...===`/remote/v4`&&t.searchParams.get(`app_version`)!==s){t.searchParams.set(`app_version`,s),r=t.toString(),...}...}return e.onAccepted?.(a),a})...}` |
| 同上 | 6770537 | `function Z9n(e){...seed(e){!r&&e&&c(e)}...update(e,t){o(e)&&(n=t,a=!0,s())}`（`mobile-task-view-*`） |
| 同上 | 6823476 | `ie=async()=>{let e=Z9(`bootstrap`);return(await V({zcode_type:`bootstrap-request`,requestId:e},t=>t.zcode_type===`bootstrap-response`&&t.requestId===e)).result}` |
| 同上 | 6824111 | `se=async()=>{...V({zcode_type:`workspace-list-request`,requestId:e},t=>t.zcode_type===`workspace-list-response`&&t.requestId===e)...}` |
| 同上 | 6824992 | `o=await re(()=>V({zcode_type:`workspace-bridge-open`,requestId:Z9(`workspace-bridge`),bridgeSessionId:n,bridgeGeneration:r,...d?{recoveryId:d}:{},workspaceKey:e,...t?.taskId?{taskId:t.taskId}:{},...},e=>e.zcode_type===`workspace-bridge-ready`&&e.bridgeSessionId===n))` |
| 同上 | 6824321 | `ce=async(e,n)=>{...te.sendPayload({zcode_type:`mobile-view-state-update`,viewState:{activeWorkspaceKey:e,...n?{activeTaskId:n}:{},updatedAt:Date.now()},deviceInfo:O9n({appVersion:t.appVersion})})}` |
| 同上 | 6822254 | `if(!ne.acceptPayload(e)){if(e.zcode_type===`workspace-list-updated`){k(e.result);return}...}` |
| 同上 | 6819081 | `mobileTaskViewEpoch` 在 `ttr` 中：`onAccepted:e=>{c===`paired`&&ee.seed(e.mobileTaskViewEpoch)}` |
| 同上 | 6811454 | `function qer(e){return zn(e)}`（= `Xa`，导入别名见 @61772 `F as zn`） |
| 同上 | 61919 / 61772 | `L as nr`（= `V`）、`F as zn`（= `Xa`）—— 证明 `nr`/`zn` 来自 `src-uVqBd_Eq.js` |

### E5 sessions-index（网页）
| 文件 | 偏移 | 片段 |
|---|---|---|
| `assets/validation-BBf1FA1a.js` | 215213 | `lv=M([`draft`,`prewarming`,`running`,`completedSuccess`,`completedInterrupted`,`error`])`（phase 枚举） |
| 同上 | 248245 | `Cx=O({sessionId:S(),workspaceId:S(),parentSessionId:S().optional(),title:S(),titleSource:yv.shape.titleSource.optional(),lastOutcome:M([`completed`,`error`,`interrupted`]).optional(),phase:lv,sessionEnded:T(),hasBackgroundWork:T(),workflowActivity:sm.optional(),pendingInteraction:xx.optional(),pendingInteractionSummary:Sx.optional(),goalStatus:Lv.shape.status.optional(),lastActivityAt:R,lastAssistantPreview:S().optional(),createdAt:R})` |
| 同上 | 248685 | `wx=O({protocolVersion:N(1),workspaceId:S(),logEpoch:S(),sessions:D(Cx)})`（snapshot 载荷） |
| 同上 | 249180 | `Tx=A(`op`,[O({op:N(`session.upserted`),session:Cx}),O({op:N(`session.removed`),sessionId:S()})]);...function Ex(e){return`sessions-index/${e}`}` |
| `assets/index-DbEHT6Gg.js` | 2026553 | `function svt(e){switch(e){case`running`:case`prewarming`:return`running`;case`completedSuccess`:case`completedInterrupted`:return`completed`;case`error`:return`error`;default:return}}` |
| 同上 | 2026736 | `function cvt(e,t){let n=t.previous,r=svt(e.phase),i=e.titleSource===`custom`,...return HO({taskId:e.sessionId,traceId:n?.traceId??`session-${e.sessionId}`,title:a,...createdAt:e.createdAt\|\|n?.createdAt\|\|0,updatedAt:e.lastActivityAt\|\|n?.updatedAt\|\|0,mode:n?.mode??`default`,...forkedFromTaskId:e.parentSessionId,...r?{status:r}:{},...n?.unreadAt?{unreadAt:n.unreadAt}:{}},{phase:e.phase,lastActivityAt:e.lastActivityAt,hasBackgroundWork:e.hasBackgroundWork,...})}` |
| 同上 | 2027954 | `function lvt(e,t){let n={workspacePath:t.workspacePath,...t.workspaceIdentity?{workspaceIdentity:t.workspaceIdentity}:{}},...i=Ye(t.workspaceIdentity?.trim()\|\|t.workspacePath)...}` |
| 同上 | 2028562 | `e.subscribeSessionsIndexV4({...n,runtimePolicy:`existing-only`,...t.base?{base:t.base}:{},...t.visibility?{visibility:t.visibility}:{}})` |
| 同上 | 2029664 | `onFrame(t){...d??=e.onDynamicSessionsIndexFrame(n)(e=>u.accept(e))...}` |
| 同上 | 2029xxx（`gvt`） | `if(t.payload.kind===`snapshot`){...state:{workspaceId,logEpoch,seq:t.toSeq,sessions:n}}...if(t.fromSeq!==e.seq)return{state:e,gap:!0}...` |
| 同上 | 2033976 区块 | subscribe 瞬时失败退避 `pvt=[250,1e3,3e3]`；恢复退避 `tP=[5e3,15e3,6e4]`；`mvt` 判 `EBUSY/EMFILE/ENFILE/config.json.lock`；`hvt` 判 `ZCODE_AGENT_RUNTIME_UNAVAILABLE` |
| 同上 | 2046156 | `function Dvt(e){...for(let t of u.getSessions())e.push(cvt(t,{workspacePath:o.workspacePath,...,previous:a.get(Ovt(o,t.sessionId))}))...}` |
| 同上 | 2047917 / 2048033 | `function Avt`（`getTaskMeta`）、`function uP`（`mutateTask` 映射表） |
| 同上 | 2058596 | `let I=Dvt(s?[]:j)` + `U_t(p, s?s.readActivities():I.items.flatMap(...))` + `W_t(...)`（任务基底与活动合并） |
| 同上 | 2019791 / 2019942 / 2020424 | `U_t`（贴 activity）、`W_t`（树合并）、`GN`（pinned/timeline/project 过滤排序） |
| 同上 | 1844635 | `function Hut(e,t){return t?.phase===`error`\|\|!t&&e.status===`error`?`error`:typeof e.unreadAt==`number`?`unread`:t?.phase===`prewarming`\|\|t?.phase===`running`?`loading`:`none`}` |
| 同上 | 2025653 | `ovt` → `projectService.readTree({includeDeleted:!0,includeDeletedTasks:!0})` + 每个远程 host 各一次 |

### E6 sessions-index（桌面端 RPC 实现）
| 文件 | 偏移 | 片段 |
|---|---|---|
| `out/host/index.js` | 239705 | `...sessionsIndexReconcile:"v4/sessionsIndex/reconcile"...` + 订阅所有权表（`ownershipKey:`sessions-index\0${workspaceKey}\0${connectionId}``） |
| 同上 | 245715 | `async subscribeSessionsIndexV4(q){...let Ke=await e.subscribeSessionsIndexV4(br(q,ae));return await Zt(Ke.ack.subscriptionId,{kind:"sessions-index",...}),Ke}` |
| 同上 | 246093 | `async unsubscribeSessionsIndexV4(q){...}async resyncSessionsIndexV4(q){return pr("sessions-index",q)}onDynamicSessionsIndexFrame(q){return kt("sessions-index",q,e.onDynamicSessionsIndexFrame(q))}` |
| 同上 | 523205 | `async reconcileSessionsIndexV4(y){return(await xe(y,"existing-only")).request(Nt.sessionsIndexReconcile,{workspaceId:Se(y),sessionIds:y.sessionIds},lU)}` |
| 同上 | 493 区块 | `applySessionsIndexFrame`：`if(ie.payload.kind==="snapshot"){E.indexLogEpoch=ie.payload.snapshot.logEpoch,E.indexSeq=ie.toSeq,...}` / `deltas` 断档判 `ie.fromSeq!==...` |
| `out/host/chunk-5YNPFY3L.js` | 551560 | `function WO(t){return t.workspaceIdentity?.trim()\|\|t.workspacePath}s(WO,"resolveWorkspaceKey")` |

### E7 rpc-frame / 通道
| 文件 | 偏移 | 片段 |
|---|---|---|
| `assets/validation-BBf1FA1a.js` | 148528 | `Fp={maxFrameBytes:1024*1024,logicalFrameAssemblyMaxBytes:16*1024*1024,logicalFrameAssemblyMaxFragments:1024,logicalFrameAssemblyMaxConcurrent:32,...}` |
| 同上 | 160412 | `_m={maxPhysicalFrameBytes:Fp.maxFrameBytes,maxMessageBytes:16*1024*1024,maxFragments:64,assemblyTimeoutMs:3e4,transportIdMaxChars:...}` |
| 同上 | 161461 | `wm=O({zcode_type:N(`rpc-frame`),bridgeSessionId:bm,bridgeGeneration:ym.optional(),recoveryId:bm.optional(),seq:vm,messageSeq:vm,fragmentIndex:...,fragmentCount:...,messageBytes:...,checksum:Sm,dataBase64:...}).strict().superRefine(...)` |
| 同上 | 162157 | `Tm=O({zcode_type:N(`rpc-frame-ack`),bridgeSessionId:bm,...,ackMessageSeq:vm}).strict()` |
| `assets/index-DbEHT6Gg.js` | 6696132 | `O9={saturationHighWaterMarkBytes:1024*1024,saturationLowWaterMarkBytes:256*1024,replayBufferMaxBytes:8*1024*1024,replayBufferGraceMs:45e3,assemblyTimeoutMs:Qt.assemblyTimeoutMs}` |
| 同上 | 6699566 | `reserveMessage(e){...t=mt(e,{...this.identity,firstPhysicalSeq:this.nextPhysicalSeq,messageSeq:this.nextMessageSeq})...}` |
| 同上 | 6704953 | `function B5n(e){return new z5n(e)}`（RPC 复用层） |
| 同上 | 6696399 / 6696575 | `F5n`（identity 严格相等：`bridgeSessionId/bridgeGeneration/recoveryId`）、`I5n`（只接受 `rpc-frame`/`rpc-frame-ack`） |
| 同上 | 6746797 | `function T9n({sendPayload:e,timeoutMs:t=1e4}){...requestAppPayload(r,i,a){...setTimeout(...)}...}`（请求/响应配对，默认 10 s） |
| `rust/crates/protocol/src/channel_client.rs` | 712 / 714 | `ZCODE_TASK = "zcode-task"`、`ZCODE_AGENT = "zcode-agent"` |

### E8 刷新/恢复
| 文件 | 偏移 | 片段 |
|---|---|---|
| `assets/index-DbEHT6Gg.js` | 6769319 | `function q9n({documentTarget,windowTarget,onSuspend,onRecover}){...visibilitychange/freeze/resume/pageshow/pagehide/online...}` |
| 同上 | 6763429 | `function I9n(e)`（重建去重）；`P9n=[1e3,2e3,5e3]`（重建重试预算） |
| 同上 | 6746615 | `function w9n(e)`（`recoverConnection` 单飞） |
| `out/main/index.js` | 403594 | `if(h.transportState=b,b!=="paired"?(h.mobileTaskViewEpoch=void 0,h.mobileTaskViewSeq=void 0,Re(h)):D!=="paired"&&(h.mobileTaskViewEpoch=Ka(),h.mobileTaskViewSeq=-1)...` |
| 同上 | 402063 | `function Be(h,b){let D=h.currentBridge,W=b.view;if(h.transportState!=="paired"\|\|b.epoch!==h.mobileTaskViewEpoch\|\|b.seq<=(h.mobileTaskViewSeq??-1)\|\|!D?.readyAnnounced\|\|D.degraded\|\|b.bridgeSessionId!==D.bridgeSessionId\|\|b.bridgeGeneration!==D.bridgeGeneration\|\|W&&(W.workspacePath!==D.workspacePath\|\|Ct(W)!==D.workspaceKey))return;...}s(Be,"applyMobileTaskViewUpdate")` |
| 同上 | 388893 | `function vt(h){h.transportState!=="paired"\|\|!h.mobileTaskViewEpoch\|\|Xe(h,{zcode_type:"mobile-task-view-request",epoch:h.mobileTaskViewEpoch})}s(vt,"requestMobileTaskView")` |

### E9 当前实现（Rust / Kotlin）
| 文件 | 位置 | 片段 |
|---|---|---|
| `rust/crates/protocol/src/conversation_transport.rs` | 1256–1267 | `"subscribeSessionsIndex" => { self.enqueue_flow(op, FlowKind::Subscribe(Box::new(SubscribeFlow{...kind: SubKind::SessionsIndex...})), FlowStage::AwaitHandshake, events); }` |
| 同上 | 3160–3178 | `on_subscribe_response` 成功分支：`events.push(ConversationEvent::Subscribed{...}); ... emit_completion(flow, Completion::Ok, events);` |
| 同上 | 3614 | `fn emit_completion(flow,outcome,events){ if flow.op.is_some(){ events.push(ConversationEvent::Completed{...}) } }` |
| 同上 | 1787 | `fn new_op_flow(...) -> Flow { Flow{ op: Some(op.to_string()), ... } }` |
| 同上 | 4560 | 测试 `sessions_index_subscription_uses_its_own_methods_and_args`：断言监听事件 `onDynamicSessionsIndexFrame`、方法 `subscribeSessionsIndexV4`、参数 `{workspacePath,workspaceIdentity,runtimePolicy:"existing-only"}`、`Subscribed{subscription_id:"si-1"}`、`sessions-index 没有 watchdog` |
| 同上 | 4600 | 测试 `sessions_index_frame_emits_index_updated`：帧 → `SessionsIndexUpdated{session_count:2,ready:true,seq:4}`；`sessionsIndex[0].list` 按 `lastActivityAt` 倒序 |
| `rust/crates/ffi/src/conversation.rs` | 1091 | `ConversationEvent::Completed{token,outcome} => out.push(codec::completed_event(conversation_op_token(key,token), ...))` |
| `rust/crates/ffi/src/codec.rs` | 467 / 1638 | 合成 `"type":"sessionsIndexUpdated"` 事件 |
| `android/.../ui/home/HomeViewModel.kt` | 325–333 | `engine.waitPaired(...); val snapshot = engine.bootstrap(); val workspaces = WorkspaceProjection.from(snapshot)` |
| 同上 | 407–413 | `// 订阅类 op 没有 completed；等它只会挂到宿主兜底超时。` + `engine.conversationCallNoWait(...)` |
| 同上 | 555–557 | `is EngineEvent.WorkspaceListUpdated -> ... it.copy(workspaces = snapshot.workspaces)`（整体替换） |
| 同上 | 561–563 | `if (event.event.asObject()?.get("type").asString() == "sessionsIndexUpdated") { mergeSessionsIndex() }` |
| `android/.../ui/chat/ChatViewModel.kt` | 1515–1607 | `object WorkspaceProjection`：读 `workspaces`/`items`/`list` + 顶层 `tasks`；`toTask` 读 `pinned/archived/unreadAt` |
| `android/.../ui/chat/SessionsIndexProjection.kt` | 58–85 | phase→status 映射含 `hasBackground \|\| phase=="running"`；`pinned/archived/unreadAt` 从条目读 |
| `android/.../ui/chat/ConversationRegistry.kt` | 71–77 | 同一句注释 + `conversationCallNoWait` |
| `android/.../engine/ZemoteEngine.kt` | 396–401 | 注释「Rust 侧的订阅流程……**不会**再发顶层 `completed`」← 与 Rust 源码矛盾 |

---

## 9. 补充核对（第二次独立复算的增量结论）

本节是同一份 3.16.1 源的**第二次独立字节级复算**，只写上面没有的结论；与上文冲突处以本节为准（冲突点已在上文就地标注「已撤销」）。

### 9.1 任务基底的三层结构（细化 §4.4）

```
① 工作区层   bootstrap-response.result.workspaces / workspace-list-*        → 只有工作区，无任务
② 任务基底   zcodeTaskService.listTasks / listPinnedTasks / listArchivedTasks（bridge RPC）
             + workspace_task_list_changed 动态事件（带 taskMeta / unreadSignal）
             + projectService.readTree（项目树视图）
③ 活动覆盖   sessions-index（subscribeSessionsIndexV4）→ cvt() 投影 → WO() 覆盖到②的对象上
```

`WO(e,t)`（`index` @1698170）是②③的合流点，逐字：

```js
function WO(e,t){let n=UO(e);if(!n)return t;let r=Object.prototype.hasOwnProperty.call(t,`unreadAt`);
 return HO({...e,...t,createdAt:e.createdAt,updatedAt:n.lastActivityAt,status:e.status,
            unreadAt:r?t.unreadAt:e.unreadAt},n)}
```

关键语义（上文未写）：
- `updatedAt` 被 sessions-index 的 `lastActivityAt` **覆盖**；
- `status` 被**任务树的值**覆盖（不是 phase 推导值）——即最终列表状态以任务索引为准，`cvt` 里的 `svt(phase)` 只在任务树没给 `status` 时生效；
- `createdAt` 用任务树的值；
- 结果再挂回 `__zcodeSessionActivity`，供 `Mit/Nit` 使用。

### 9.2 `hasBackgroundWork` **不**改状态；角标来自 summary

`index` @1697924 / @1697985：

```js
function jit(e){let t=UO(e)?.phase;return t===`prewarming`||t===`running`}
function Mit(e){return jit(e)||UO(e)?.hasBackgroundWork===!0}     // 只用于「活跃」判定与排序
function Nit(e){let t=UO(e)?.pendingInteractions;if(!t)return null;
  let n=t.permissionCount+t.userInputCount;
  return n===0?null:{kind:t.userInputCount>0?`userInput`:`permission`,count:n}}   // 角标用 summary 的两个计数
```

→ **后台任务的状态仍是 `completed`/`error`，只是额外被标为「活跃」**；待审批角标用 `pendingInteractionSummary`，不是 `pendingInteraction`。

### 9.3 空工作区 / 无任务 / 部分工作区 的真实表现

| 情形 | 表现 | 证据 |
|---|---|---|
| `workspaces` 为空且无当前目标 | `Xa()` 返回 `null` → `throw X9('workspace-closed','No opened desktop workspace is available for Web remote control.')` → 错误页 | `src` @52188；`index` @6811454 `qer` |
| 有工作区但都不可桥接（`Ya()` 为假） | `pe()` → **home-only 模式**：`Zer()` 造 stub services，`listTasks/listPinnedTasks/listArchivedTasks` 恒返回 `[]`；界面只有工作区列表 | `index` @6812634 `Zer`；@6693704 的 stub 定义 |
| 可桥接但 sessions-index 尚未 ready | 任务基底（②）照常显示；活动层缺失 → 无 `updatedAt` 覆盖、无运行态、无预览 | §4.4 + `Evt` |
| 部分工作区可桥接 | 选 `mobileViewState.activeWorkspaceKey`（须在可桥接集合内），否则第一个可桥接者；其余只出现在工作区列表，不订阅 | `src` @52188 |

### 9.4 `workspace-list-updated` 的推送去重签名

桌面 `M()`（@394200）只在签名变化时推送；签名由 `R()` 生成 = 对每个工作区取
`[workspaceKey, kind, connectionState ?? 'connected', remoteSessionId ?? '', lastConnectionError ?? '']` 后排序再 `JSON.stringify`。
→ 只改 `label` / `workspacePurpose` 不会触发推送。

### 9.5 `mobile-view-state-update` 的 `deviceInfo` 形状

`index` @6824333 + @6747xxx `O9n()`：

```
{platform:'web', version: <appVersion 或 'web'>, name:'mobile-browser', userAgent?, language?,
 languages?, browserPlatform?, viewport?:{width,height,devicePixelRatio}, screen?:{width,height},
 timezone?, online?, updatedAt}
```
`schema` 侧是 `ew`（`validation` @304481）。Rust/Kotlin 目前不发这个字段（只发 `viewState`）。

### 9.6 sessions-index 里**网页远程端实际不消费**的字段

对 `index-DbEHT6Gg.js` 全量 `Buffer.indexOf` 计数：`sessionEnded` = 0、`lastOutcome` = 0、`goalStatus` = 0。
→ 移动端不用它们做任何显示判断；schema 里存在但只服务于其它消费方（桌面渲染器 / 未来功能）。

被消费的字段只有：`sessionId / parentSessionId / title / titleSource / phase / hasBackgroundWork /
workflowActivity / pendingInteraction / pendingInteractionSummary / lastActivityAt / lastAssistantPreview / createdAt`。

### 9.7 宿主 `subscribeSessionsIndexV4` 的 `legacyTaskIds` 前置

`out/host/index.js` @523277：远程工作区（`Jfe(workspaceIdentity)` 为真）订阅前会先
`listTaskMetas({workspacePath, workspaceIdentity, provider:'glm'}).slice(0, R_)` 取一批 taskId 作为 `legacyTaskIds` 传给
`v4/conversation/subscribe`；读取失败只记 `sessions_index.legacy_remote_allowlist_read_failed` 警告，随后按严格 identity 订阅。
→ 这是「老任务归属」的兼容通道，`R_` 上限值未读到（见 §10-U11）。

### 9.8 桌面端偏移口径修正

上文 §3.2 等处的桌面端偏移来自 `asar.mjs grep` 的 **JS char 偏移**。本次用
`Buffer.indexOf(Buffer.from(kw,'utf8'))` 复算，`out/main/index.js` 与 `out/host/index.js` 在上述锚点处
**char 偏移 == 字节偏移**（锚点前缀全为 ASCII），可直接引用。补充锚点（字节）：

| 文件 | 字节偏移 | 符号 |
|---|---:|---|
| `out/main/index.js` | 393459 | `buildBootstrapResult` |
| `out/main/index.js` | 393690 | `buildWorkspaceListResult` |
| `out/main/index.js` | 393098 | `getAvailableWorkspaces` |
| `out/main/index.js` | 393285 | `getRuntimeInitialViewState` |
| `out/main/index.js` | 394200 | `pushWorkspaceListUpdated` |
| `out/main/index.js` | 402774 | `routePayload`（`bootstrap-request` / `workspace-list-request` 分发） |
| `out/main/index.js` | 401430 | `applyMobileTaskViewUpdate` |
| `out/main/index.js` | 403516 | `mapTransportState`（epoch 生成/清零） |
| `out/host/index.js` | 186422 | ack schema `Hd` |
| `out/host/index.js` | 186990 | 帧 schema `P_` |
| `out/host/index.js` | 189034 | RPC 方法枚举 `Nt` |
| `out/host/index.js` | 523277 | `subscribeSessionsIndexV4` |
| `out/host/index.js` | 593682 | `listTasks` |
| `out/host/index.js` | 602729 | `setTaskPinned` / `setTaskUnread` |
| `out/host/index.js` | 1689375 | `workspace_task_list_changed` 发射点 |

网页端字节偏移补充：`requestBootstrap:` @6819016、`bootstrap-request` @6823488、`workspace-list-request` @6824123、
`workspace-list-updated` @6822254、`mobile-task-view-request` @6822045、`mobile-view-state-update` @6824333、
`function WO(` @1698170、`function Mit(` @1697924、`function Nit(` @1697985、`function Evt(` @2046045、
`function Dvt(` @2046156、`let I=Dvt(` @2058592、`getSessions(){` @2042217、`function Zer(` @6812634。

---

### 9.9 网页证据 vs 桌面端证据（逐结论对照）

| 结论 | 网页证据（`Reference_web/3.16.1/`） | 桌面端证据（`app.asar`） | 判定 |
|---|---|---|---|
| bootstrap 只有 6 键、无 `tasks` | `validation` `tw` @304850；`src` `Xa` @52188（只解构 3 键） | `out/main/index.js` @393459 | 双端一致 `CONFIRMED` |
| workspace-list 无任务 | `validation` `nw` @305022；`index` `se()` @6824045 | `out/main/index.js` @393690 / @394200 | 双端一致 `CONFIRMED` |
| 工作区键 = `identity?.trim()\|\|path` | `src` @52038 `V` | `out/host/chunk-G6XJUBQB.js` @37446 `getWorkspaceKey` | 双端一致 `CONFIRMED` |
| 可桥接判定 | `src` @52104 `Ya` | `out/main/index.js` @384022 `Yu`（逐字相同） | 双端一致 `CONFIRMED` |
| sessions-index 条目 16 字段 | `validation` `Cx` @248245 | `out/host/chunk-5YNPFY3L.js` @640294（`O_`） | 双端一致 `CONFIRMED` |
| 帧 / ack 形状 | `index` `gvt` @2030973、`lvt` @2027954 | `out/host/index.js` @186990 `P_`、@186422 `Hd` | 双端一致 `CONFIRMED` |
| 线上无 `completed` | 订阅只等 `{ack}`（`lvt`） | `Nt.conversationSubscribe` 返回 `IH={ack:Hd}` | 双端一致 `CONFIRMED` |
| `pinned/archived/unreadAt` 来自任务索引 | `index` `uP` @2048033、`jvt` @2048675、`WO` @1698170 | `out/host/index.js` @593682 `listTasks`、@602729 `setTaskUnread`、@1689375 `workspace_task_list_changed` | 双端一致 `CONFIRMED` |
| `mobileTaskViewEpoch` 每次 paired 重生成 | `index` `Z9n` @6770537（seed/request/update） | `out/main/index.js` @403516 `UA`（`Ka()`）、@401430 `Be` | 双端一致 `CONFIRMED` |
| `hasBackgroundWork` 不改状态 | `index` `svt` @2026553 + `Mit` @1697924 | 无对端逻辑（纯客户端渲染规则） | **仅网页证据** |
| home-only 模式（空/不可桥接工作区） | `index` `Zer` @6812634 + `pe()` | 无（桌面端没有这个概念） | **仅网页证据** |
| `workspace-list-updated` 去重签名 | `index` `k()` @6822254 消费 | `out/main/index.js` @394200 `M()`/`R()` 生成 | 双端一致 `CONFIRMED` |
| `legacyTaskIds` 前置 | 无（网页不感知） | `out/host/index.js` @523277 | **仅桌面端证据** |

> 说明：网页 `index-DbEHT6Gg.js` 与桌面 `out/renderer/assets/styles-CQuzX8si.js` 是同一渲染器的两个产物
> （`_vt` store、`cvt`、`WO` 逐字相同），因此「网页证据」同时代表**桌面渲染器**行为；
> 只有 `out/main`、`out/host` 是桌面端独有的**对端契约**证据。

---

## 10. 未解问题与需要实证的清单

| # | 问题 | 现状 | 建议实证方式 |
|---|---|---|---|
| U1 | 手机 Web 远端里 `windowControllerService` 是否可用？这决定任务基底是 `projectService.readTree` 还是 sessions-index（`Dvt`） | `UNVERIFIED`：`D5n` 构造函数无条件注入该服务，但远端 host 是否实现 `list`/`readActivities` 未确认 | 真机抓 `zcode-agent`/`window-controller` 通道的首批调用；或看 `readTree` 是否被调用 |
| U2 | `mobileViewState` / `mobileTaskViewEpoch` / `mobileTaskView` 是否跨桌面端重启持久化？ | `UNVERIFIED`：只看到内存字段 `h.mobileViewState`；`startupRestoreStorageProvider` 只见到恢复 workspace 列表 | 重启桌面端后看 bootstrap 的 `mobileViewState` 是否为 null |
| U3 | `lastAssistantPreview` 在网页端的真实消费点 | `UNVERIFIED`：`cvt` 不映射它；可能在全局搜索 / `Hut` 之外 | 全量 `rg` `lastAssistantPreview` 于 `index` 与 `out/host` |
| U4 | `workspace-list-updated` 的触发频率与去重签名是否只看 5 个字段（key/kind/connectionState/remoteSessionId/lastConnectionError） | `CONFIRMED` 签名内容；`h.workspaces` 与 `a.get(windowId)` 两个来源的写入时序 `UNVERIFIED` | 真机观察推送次数 |
| U5 | `zcode-task` 通道各方法的**精确参数形状**（`mutateTask` 的 `address` + `mutation`） | `UNVERIFIED`：网页走 `controller.mutateTask` 或 `service.setTaskPinned/setTaskUnread/...`；手机端直接传任务摘要 | 抓一次置顶/已读的 RPC 参数 |
| U6 | `bridgeGeneration` / `recoveryId` 在 Rust `open_bridge` 里是否实现 | `UNVERIFIED`（`zemote_client.rs` 需逐行确认） | 读 `zemote_client::open_bridge` 与 `bridge_session` 结构 |
| U7 | 桌面端 `workspaces` 的 `connectionState` / `lastConnectionError` 何时出现（`Ee()` 归一化会丢） | `UNVERIFIED`：签名函数读它，但 `Le()` 用 `Ee()`/`ce()` 构造 | 断开一个远程工作区后看 `workspace-list` 响应 |
| U8 | `sessions-index` 的 `deltas` 是否会在 `snapshot` 之前单独到达（首帧） | 网页 store 有 `subscriptionHasAppliedBase` 保护 → 会 `requestRecovery`；Rust 侧有 `stagedFrameCount` 暂存 | 已在 Rust 测试覆盖（`frames_before_ack_are_staged_then_applied`） |
| U9 | `workspacePurpose:'conversation'` 的工作区是否也参与 sessions-index 订阅 | 网页 `Xa` 不特判；手机端当前过滤掉 | 观察真实 bootstrap 是否含此类条目 |
| U10 | `pendingInteraction` / `pendingInteractionSummary` 在手机端是否应参与"待审批"角标 | 当前未消费；网页用 `Nit()` 取 `permissionCount+userInputCount` 做角标（`index` @1697985） | 产品决策 |
| U11 | `legacyTaskIds` 的上限 `R_` 与"严格 identity 订阅"的回退边界 | `UNVERIFIED`：只见 `.slice(0,R_)`（`out/host/index.js` @523277） | 读 host chunk 里 `R_` 的定义 |
| U12 | 订阅参数 `visibility:'foreground'\|'background'` 的服务端语义（推送频率/内容差异） | `UNVERIFIED`：网页远程端从不传；桌面后台观测传 `background` | 对比两种订阅的首帧与增量频率 |
| U13 | `workspaceId`（sessions-index 条目）与 `workspaceKey` 的映射规则 | `UNVERIFIED`：条目里两者都出现，但生成方未读到 | 抓一帧 snapshot 比对 |
| U14 | `v4/sessionsIndex/reconcile`（`reconcileSessionsIndexV4`）的调用方 | 网页侧 0 命中；host 暴露 `@523124` | 在 `out/renderer` / `out/host` 全量搜调用点 |
| U15 | `mobileTaskViewEpoch` 重连窗口：手机端 `seed` 只在未持有时生效（`Z9n.seed` 的 `!r&&e&&c(e)`），旧 epoch 会不会被桌面静默丢弃 | `SPECULATION`：`Be()` 对 epoch 不匹配直接 return | 断网重连后观察 `mobileTaskView` 是否停更 |

---

## 附录 A · 术语与命名对照

| 网页/协议名 | 含义 | 当前 Android 对应 |
|---|---|---|
| `taskId` | 任务 id，== `sessionId` | `TaskItem.id` |
| `workspaceKey` | `workspaceIdentity?.trim() \|\| workspacePath` | `WorkspaceItem.key` |
| `entityKey` / `aE(e)` | `` `${workspaceKey}::${taskId}` `` | 无（`previews` 以 taskId 为键，跨工作区可能撞） |
| `sessionsIndex[i]` | 一条 sessions-index 订阅的快照 | `engine.conversationSnapshot(...)["sessionsIndex"][i]` |
| `list` | 按 `lastActivityAt` 倒序的条目数组 | `SessionsIndexProjection.tasks` |
| `ready` | 是否已收到首个 snapshot | `HomeViewModel.mergeSessionsIndex` 的 `ready` 过滤 |
| `bridgeSessionId` | 一次开桥的 id | `open_bridge` 返回的 session 号 |
| `__zcodeSessionActivity` | 挂在任务上的活动元信息 | `TaskItem.hasBackgroundWork` / `displayStatus` |

## 附录 B · 本报告未做的事

- 未执行网页包内任何 JS（只做字符串定位与截取）。
- 未联网、未连真机、未运行 `cargo test`。
- 未修改任何源码或快照；临时 dump 文件已删除。
