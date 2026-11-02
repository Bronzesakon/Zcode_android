# 03 · 交叉验证（C 路）：配对后的数据流

- 审计对象：`Reference_web/3.16.1/`（只读）+ 桌面端 `app.asar`（只读）+ 本仓 `rust/`、`android/`
- 被审报告：`docs/auth-3.16.1/round1/04-session-dataflow.md`
- 方法：不执行网页 JS；用 Node `Buffer.indexOf` / `byteLength` 定位后截取；桌面端用 `tools/asar.mjs grep --context` 流式读取。
- **坐标约定**
  - `WEB:<file>@<n>` = **UTF-8 字节偏移**（`Buffer.byteLength(s.slice(0,i),'utf8')`）。
  - `DESKTOP:<innerPath>@<n>` = `asar.mjs` 打印的 **char 偏移**；`out/main/index.js` 前 661,752 字符全为 ASCII，因此该文件 383k–412k 区间的 char == byte。
  - `RUST:<path>:<line>` / `KOTLIN:<path>:<line>` = 行号。
- 标注：`【网页 3.16.1】` / `【桌面 3.16.x】` / `【本仓】`。桌面 asar 实际版本是 **3.16.3**（见 §5）。

---

## 0. 一句话结论

04 的字段表大体正确，但**核心因果链错了两处**：任务基底不是 sessions-index 而是 `projectService.readTree`；relay 不是 `/ws/remote/v4` 而是 `/api/v1/remote/ws2`。另外 04 把 `cvt` 的继承范围写宽了（`pinned/archived` 不在 `cvt` 里），把 Rust 已实现的能力（`bridgeGeneration`/`recoveryId`）标成未验证，并漏掉了订阅 ack 的 `mode/logEpoch` 与 `deliveryKind`。

---

## 1. 裁决表

### 1.1 必裁 11 项

| # | 04 的结论 | 裁决 | 坐标与原文 |
|---|---|---|---|
| 1a | `bootstrap-response.result` 只有 6 键，无顶层 `tasks` | **CONFIRMED** | `WEB:validation-BBf1FA1a.js@304856` — `tw=O({windowControlSessionId:Q,desktopAppVersion:Q.optional(),workspaces:YC,initialViewState:QC.optional(),mobileViewState:QC.optional(),mobileTaskViewEpoch:$.optional()})`；`DESKTOP:out/main/index.js@393667` — `function A(h){return{windowControlSessionId:h.deviceSid,desktopAppVersion:e.appVersion,workspaces:Le(h),initialViewState:T(h),mobileViewState:h.mobileViewState,mobileTaskViewEpoch:h.mobileTaskViewEpoch}}` |
| 1b | `Workspace` 元素字段与键规则 | **CONFIRMED** | `WEB:validation-BBf1FA1a.js@303107` — `YC=D(O({workspacePath:Q,workspaceIdentity:Q.optional(),remoteSessionId:Q.optional(),label:Q,workspacePurpose:M([`project`,`conversation`]).optional(),kind:M([`local`,`remote`]),connectionState:M([`connected`,`disconnected`,`reconnecting`]).optional(),lastConnectionError:S().optional()}))`；键规则 `WEB:src-uVqBd_Eq.js@52038` — `function V(e){return e.workspaceIdentity?.trim()||e.workspacePath}` |
| 1c | `connectionState/lastConnectionError` 不在 `Ee()` 归一化输出 | **CONFIRMED** | `DESKTOP:out/main/index.js@392855` — `function Ee(h){return{workspacePath:h.workspacePath,workspaceIdentity:h.workspaceIdentity,remoteSessionId:h.remoteSessionId,label:Xu(h.workspacePath),kind:h.remoteSessionId||h.workspaceIdentity?"remote":"local"}}`；但推送签名读它们：`DESKTOP:@393961` — `JSON.stringify([W,D.kind,D.connectionState??"connected",D.remoteSessionId??"",D.lastConnectionError??""])` |
| 2a | 任务来自 sessions-index V4 订阅 | **CONTRADICTED** | 见 §2-C1。任务基底是 `readTree`；sessions-index 只供预览/活动回退，`Dvt` 分支是死代码 |
| 2b | `subscribe` 只回 `ack` | **CONFIRMED**（但 ack 不止 `subscriptionId`） | `WEB:index-DbEHT6Gg.js@2027954` — `subscribe(t){...await e.subscribeSessionsIndexV4({...n,runtimePolicy:`existing-only`,...t.base?{base:t.base}:{},...t.visibility?{visibility:t.visibility}:{}});...u.bind(a,r.ack.subscriptionId)}`；sessions-index store 还读 `r.ack.mode` / `r.ack.logEpoch`（`@2033200`） |
| 2c | `snapshot`/`deltas` 帧、seq 连续性、断档 resync | **CONFIRMED** | `WEB:index@2030973` — `function gvt(e,t){if(t.payload.kind===`snapshot`){...seq:t.toSeq,sessions:n};if(t.toSeq<=e.seq)return{state:e,gap:!1};if(t.fromSeq!==e.seq)return{state:e,gap:!0};...}`；断档处理 `WEB:index@2034702` — `if(this.applyFrame(e)&&this.transport){...i?this.connect(this.transport,{forceSnapshot:!0}):this.requestRecovery(...)}` |
| 3a | 条目 schema 逐字段 | **CONFIRMED** | `WEB:validation@248245` — `Cx=O({sessionId:S(),workspaceId:S(),parentSessionId:S().optional(),title:S(),titleSource:yv.shape.titleSource.optional(),lastOutcome:M([`completed`,`error`,`interrupted`]).optional(),phase:lv,sessionEnded:T(),hasBackgroundWork:T(),workflowActivity:sm.optional(),pendingInteraction:xx.optional(),pendingInteractionSummary:Sx.optional(),goalStatus:Lv.shape.status.optional(),lastActivityAt:R,lastAssistantPreview:S().optional(),createdAt:R})` |
| 3b | phase 枚举 | **CONFIRMED** | `WEB:validation@215213` — `lv=M([`draft`,`prewarming`,`running`,`completedSuccess`,`completedInterrupted`,`error`])` |
| 3c | `sessionId == taskId` | **CONFIRMED** | `WEB:index@2026736` — `return HO({taskId:e.sessionId,traceId:n?.traceId??`session-${e.sessionId}`,...})` |
| 4a | `pinned/archived/unreadAt` 不在 sessions-index 条目 | **CONFIRMED** | `Cx` 无这三键（同 3a）；`WEB:index@2026736` 的 `cvt` 也不产出 `pinned/archived` |
| 4b | 真实来源是 `getTaskMeta`/`setTaskUnread`/`readTree`/`controller.list` | **CONFIRMED** | `WEB:index@2047917` — `function Avt(e,t,n){return e.controller?e.controller.getTaskMeta({address:kvt(t,n)}):e.service.getTaskMeta(t)}`；`WEB:index@2025653` — `projectService.readTree({includeDeleted:!0,includeDeletedTasks:!0})`；`DESKTOP:out/host/index.js@602851` — `async setTaskUnread(P){if(!P.unread&&typeof P.expectedUnreadAt=="number"){...clearTaskUnreadIfMatches(...)}}` |
| 4c | `cvt` 从 previous 继承 `pinned/archived/unreadAt/mode/model/provider/traceId` | **CONTRADICTED** | 见 §2-C2。`cvt` 不继承 `pinned/archived`；`unreadAt` 只在 previous 值为 truthy 时继承 |
| 5a | 任务列表两个来源：`readTree`+controller vs `Dvt(sessions-index)` | **CONFIRMED** | `WEB:index@2058592` — `let I=Dvt(s?[]:j)`；`@2058657` — `U_t(p,s?s.readActivities():I.items.flatMap(...))` |
| 5b | U1（`windowControllerService` 是否存在）未定 | **CONTRADICTED** | 见 §2-C3。服务对象在 `D5n` 构造函数里**无条件**创建，`s` 恒真，`Dvt` 分支在网页客户端恒为死代码 |
| 6a | `mobileTaskViewEpoch` 每次 `paired` 重新生成 | **CONFIRMED** | `DESKTOP:out/main/index.js@403672` — `b!=="paired"?(h.mobileTaskViewEpoch=void 0,h.mobileTaskViewSeq=void 0,Re(h)):D!=="paired"&&(h.mobileTaskViewEpoch=Ka(),h.mobileTaskViewSeq=-1)`；网页 seed `WEB:index@6819071` — `onAccepted:e=>{c===`paired`&&ee.seed(e.mobileTaskViewEpoch)}` |
| 6b | `mobile-task-view-*` 的 epoch/seq/bridge 校验 | **CONFIRMED** | `DESKTOP:@401430` — `if(h.transportState!=="paired"||b.epoch!==h.mobileTaskViewEpoch||b.seq<=(h.mobileTaskViewSeq??-1)||!D?.readyAnnounced||D.degraded||b.bridgeSessionId!==D.bridgeSessionId||b.bridgeGeneration!==D.bridgeGeneration||W&&(W.workspacePath!==D.workspacePath||Ct(W)!==D.workspaceKey))return;`；网页发送 `WEB:index@6770537` — `s=()=>{!t||!r||!a||(a=!e({zcode_type:`mobile-task-view-update`,epoch:r,seq:++i,bridgeSessionId:t.bridgeSessionId,...view:n}))}` |
| 7a | `rpc-frame` 分片字段/限额/crc32/ack | **CONFIRMED** | `WEB:validation@161461` — `wm=O({zcode_type:N(`rpc-frame`),bridgeSessionId:bm,bridgeGeneration:ym.optional(),recoveryId:bm.optional(),seq:vm,messageSeq:vm,fragmentIndex:...max(_m.maxFragments-1),fragmentCount:...max(_m.maxFragments),messageBytes:...max(_m.maxMessageBytes),checksum:Sm,dataBase64:...}).strict().superRefine(...)`；`@160855` — `_m={maxPhysicalFrameBytes:Fp.maxFrameBytes,maxMessageBytes:16*1024*1024,maxFragments:64,assemblyTimeoutMs:3e4,...}`；`@162157` — `Tm=O({zcode_type:N(`rpc-frame-ack`),bridgeSessionId:bm,...,ackMessageSeq:vm}).strict()` |
| 7b | 限额与流控 | **CONFIRMED** | `WEB:validation@148178` — `Fp={maxFrameBytes:1024*1024,logicalFrameAssemblyMaxBytes:16*1024*1024,logicalFrameAssemblyMaxFragments:1024,logicalFrameAssemblyMaxConcurrent:32,logicalFrameAssemblyMaxStagedBytes:32*1024*1024,logicalFrameAssemblyTimeoutMs:3e4,...}`；`WEB:index@6695998` — `O9={saturationHighWaterMarkBytes:1024*1024,saturationLowWaterMarkBytes:256*1024,replayBufferMaxBytes:8*1024*1024,replayBufferGraceMs:45e3,assemblyTimeoutMs:Qt.assemblyTimeoutMs}` |
| 7c | token 只在 relay URL，内层 `data` 不附 token | **CONFIRMED** | `WEB:index@6750843` — `let n=await this.options.getToken(t.signal);...r.searchParams.set(`token`,n),this.options.deviceMid?.trim()&&r.searchParams.set(`mid`,this.options.deviceMid)`；内层序列化 `WEB:index@6705019` — `let r={type:`data`,payload:e,client_ts:t},i=JSON.stringify(r)`（无 token） |
| 8 | 刷新/重载/恢复分支表 | **CONFIRMED**（有补漏） | 见 §3-M15/M16。`WEB:index@6769319` `q9n`（visibilitychange/freeze/resume/pageshow/pagehide/online）；`@6763429` `I9n` 去重；`@6763375` `P9n=[1e3,2e3,5e3]`；`@6746615` `w9n` 单飞 `recoverConnection`；`@6823139` `onSendReady:()=>{...ee.flush(),$9n({activeRelayProtocol:b,pendingRelayProtocol:x})}` |
| 9 | relay WS 是 `/ws/remote/v4?token=...` | **CONTRADICTED** | 见 §2-C4。正确是 `/api/v1/remote/ws2?token=...&mid=...` |
| 10a | Rust `subscribe` 成功会 `emit_completion(Completed::Ok)` | **CONFIRMED** | `RUST:rust/crates/protocol/src/conversation_transport.rs:3174` — `emit_completion(flow, Completion::Ok, events);`（`on_subscribe_response` 成功分支末尾） |
| 10b | `emit_completion` 仅在 `flow.op.is_some()` 时发 | **CONFIRMED** | `RUST:conversation_transport.rs:3614` — `fn emit_completion(flow: &Flow, outcome: Completion, events: &mut Vec<ConversationEvent>) { if flow.op.is_some() { events.push(ConversationEvent::Completed{...}) } }` |
| 10c | `new_op_flow` 对 subscribe 也设 `op: Some(...)` | **CONFIRMED** | `RUST:conversation_transport.rs:1787` — `fn new_op_flow(...) -> Flow { Flow { token: self.new_op_token(op), op: Some(op.to_string()), ... } }`；subscribe 走 `enqueue_flow` → `new_op_flow`（`:1256`、`:1798`） |
| 10d | Kotlin 注释「订阅不会发顶层 completed」是错的 | **CONFIRMED** | `RUST:ffi/src/conversation.rs:1091` — `ConversationEvent::Completed{token,outcome} => out.push(codec::completed_event(conversation_op_token(key,token),...))`；`KOTLIN:android/.../engine/ZemoteEngine.kt:396-399` 原文 `Rust 侧的订阅流程是「注册监听器 → 发 subscribe*V4 → 收到 Subscribed」，**不会**再发顶层 completed` |
| 11 | D1–D20 描述准确、行号以实际代码为准 | **部分 CONTRADICTED** | 逐条见 §1.2 |

### 1.2 D1–D20 逐条裁决（行号已按实际代码校正）

| # | 裁决 | 依据 / 实际行号 |
|---|---|---|
| D1 | **CONFIRMED** | `KOTLIN:ChatViewModel.kt:1544` — `val tasks = root?.get("tasks")?.asArrayOrNull().orEmpty()...`；死分支，不崩 |
| D2 | **CONFIRMED** | 同上，缺键 → `tasks=[]` |
| D3 | **CONTRADICTED** | 见 C1/C3。`SessionsIndexProjection.tasks()` 不是权威路径；网页恒走 `readTree` |
| D4 | **CONFIRMED** | `RUST:ffi/src/codec.rs:460-467` 合成 `{"type":"sessionsIndexUpdated",...}`；`WEB:index` / `WEB:validation` / `DESKTOP:out` 命中数均为 0 |
| D5 | **CONFIRMED**（行为对、注释错） | `KOTLIN:HomeViewModel.kt:407-413`、`ChatViewModel.kt:908-913`、`ConversationRegistry.kt:71-77` |
| D6 | **CONFIRMED** | `KOTLIN:SessionsIndexProjection.kt:76-78` 从条目读 `unreadAt/pinned/archived`，条目无这三键 → 恒 `null/false/false` |
| D7 | **CONFIRMED** | `KOTLIN:SessionsIndexProjection.kt:63-64` — `hasBackground || phase=="running" || phase=="prewarming" -> "running"`；网页 `WEB:index@2026553` `svt` 只看 phase；`hasBackgroundWork` 只进 `Mit`（`WEB:index@1697924` — `function Mit(e){return jit(e)||UO(e)?.hasBackgroundWork===!0}`） |
| D8 | **CONFIRMED** | `WEB:index@2026736` — `i=e.titleSource===`custom`,a=n?.titleOverridden===!0&&!i?n.title:e.title||n?.title||`` |
| D9 | **CONFIRMED**（并可加一条） | 网页落点 `WEB:src-uVqBd_Eq.js@52188` `Xa`；本仓只发不收：`KOTLIN:ChatViewModel.kt:900/904` 调 `sendMobileViewState`，从不读 bootstrap 的 `mobileViewState`。另：`WorkspaceProjection.from` 只返回 `workspaces`，把 `activeWorkspaceKey/activeTaskId` 丢弃（`ChatViewModel.kt:1526-1527`） |
| D10 | **CONFIRMED** | `mobileTaskViewEpoch` / `mobile-task-view-*` 在 `rust/`、`android/` 除注释外 0 命中 |
| D11 | **CONFIRMED** | `KOTLIN:HomeViewModel.kt:79`、`:332`、`:375` 过滤 `purpose=="conversation"` |
| D12 | **CONFIRMED** | `KOTLIN:ChatViewModel.kt:1566-1568` — `val identity = strField("workspaceIdentity")?.trim()?.takeIf { it.isNotEmpty() }; ... key = identity ?: path`（identity 显式 trim，path 不 trim，与网页 `V` 一致） |
| D13 | **CONFIRMED** | 网页 `WEB:src@52104` `Ya`；本仓无等价判定，直接 `openBridge`（`HomeViewModel.kt:377`、`:397`） |
| D14 | **CONTRADICTED**（04 标 UNVERIFIED） | 见 C5。`RUST:zemote_client.rs:1367` — `self.bridge_generation += 1;`，`:2448` 写入请求 |
| D15 | **CONTRADICTED**（04 标 UNVERIFIED） | 见 C6。`RUST:zemote_client.rs:1785` reopen 带旧 `recoveryId`；`:2029-2039` 把 ready 的 `recoveryId` 装进 transport identity |
| D16 | **CONFIRMED** | `KOTLIN:SessionsIndexProjection.kt:44-53` 取 `lastAssistantPreview` 进 `previews`；网页 `cvt` 不映射它 |
| D17 | **CONFIRMED** | `sessionEnded/lastOutcome/goalStatus/workflowActivity` 在 Kotlin 仅出现在注释（`SessionsIndexProjection.kt:18-20`） |
| D18 | **CONTRADICTED**（04 标 UNVERIFIED） | 见 C8。`zcode-task` 的 flat 对象形状可用，但网页首选 `window-controller.mutateTask({address,mutation})` |
| D19 | **CONTRADICTED** | 见 C9。`markRead()` 只有定义（`HomeViewModel.kt:227`），全仓无调用点；不存在「进入对话约 2s 后调用」 |
| D20 | **CONFIRMED** | 见 §1.3 |

### 1.3 额外必须核查项

| 项 | 裁决 | 依据 |
|---|---|---|
| `workspace-list-updated` 整体替换并清空本地 tasks | **CONFIRMED** | `KOTLIN:HomeViewModel.kt:554-557` — `WorkspaceProjection.snapshotOrNull(event.value)?.let { snapshot -> _state.update { it.copy(workspaces = snapshot.workspaces) } }`；`ChatViewModel.kt:1379-1382` 同样；而 `snapshotOrNull` 的 tasks 来自已不存在的顶层键（`ChatViewModel.kt:1544`）→ 每个 `WorkspaceItem.tasks` 被置空，直到下一次 `sessionsIndexUpdated` 才恢复。网页无此问题：`nw` 只有 3 键，任务另行维护；`k(e)` 只是 `O=e=>({...e,activeWorkspaceKey:e.activeWorkspaceKey??l,activeTaskId:e.activeTaskId??u})` 后广播（`WEB:index@6819445` / `@6819527`） |
| `hasBackgroundWork` 是否参与 status | **CONFIRMED 不参与网页 status** | `svt` 只看 phase（`WEB:index@2026553`）；`Hut` 也只看 phase/unreadAt（`WEB:index@1844635` — `t?.phase===`error`||!t&&e.status===`error`?`error`:typeof e.unreadAt==`number`?`unread`:t?.phase===`prewarming`||t?.phase===`running`?`loading`:`none``） |
| `titleSource=custom` 标题继承 | **CONFIRMED** | `WEB:index@2026736`：`custom` → 用 `entry.title` 且 `titleOverridden=true`；previous 被覆盖且 `titleSource!=='custom'` → 保留 `previous.title` |
| `initialViewState`/`mobileViewState` 落点（`Xa`） | **CONFIRMED** | `WEB:src@52188` — `let r=e.filter(Ya),i=new Set(r.map(e=>V(e))),...o=t??n,s=o?.activeWorkspaceKey;if(s&&i.has(s))return{workspaceKey:s,...canBridge:!0};let c=r[0];if(!c){if(s&&a.has(s))return{...canBridge:!1};let t=e[0];return t?{workspaceKey:V(t),canBridge:!1}:null}` |
| `bridgeGeneration`/`recoveryId` 在 Rust `open_bridge` | **已实现** | 见 C5/C6 |
| `zcode-task` mutation 参数形状 vs 网页 `mutateTask` | **不一致（但可用）** | 网页首选 `controller.mutateTask({address:kvt(t,r),mutation:n})`（`WEB:index@2048033`）；本仓 `KOTLIN:HomeViewModel.kt:486-504` 传 flat 对象 + `extra` 给 `zcode-task.<method>`，对应网页的 **fallback** 分支（`service.setTaskPinned/setTaskUnread/archiveTask...`），host 端是 flat 解构，额外键被忽略 |
| `lastAssistantPreview` 的网页消费点（04-U3） | **CONTRADICTED**（网页无消费点） | `grep -abo "lastAssistantPreview" Reference_web/3.16.1/assets/index-DbEHT6Gg.js` → **0 行**；`validation-BBf1FA1a.js` → 1 行（仅 `Cx` schema）。预览行是手机端自定义用法 |
| 04 桌面端版本/偏移约定 | **CONTRADICTED** | 见 C10 |

---

## 2. CONTRADICTED 纠错

### C1 · 任务基底不是 sessions-index，而是 `projectService.readTree` + controller activity

**04 原文**（§1.2）：`任务来自 sessions-index V4 订阅，不是 bootstrap`；§4.4 称「`Dvt` 只在没有 windowController 通道时才被调用」。

**正确结论**：网页客户端的任务基底是 `readTree().tasks` + `windowControllerService.readActivities()`；`sessions-index` 只在 controller 服务缺失时才通过 `Dvt` 进入任务列表，而该条件在网页客户端不可达（见 C3）。另外 `lastAssistantPreview` 在 3.16.1 网页 bundle 里**没有任何消费点**（`index-DbEHT6Gg.js` 命中 0，`validation` 仅 schema 命中 1），所以 sessions-index 在 controller 分支里实际不贡献任何任务字段。

**坐标**：
- `WEB:index-DbEHT6Gg.js@2053790` — `function Fvt(e=!1){let t=lE(),n=t.windowProjectDirectory??t,r=n.projectService,i=n.remoteProjectService,...,o=t.windowControllerService,s=(0,Q.useMemo)(()=>o?Rut(o):null,[o])`
- `WEB:index-DbEHT6Gg.js@2058592` — `let I=Dvt(s?[]:j)`
- `WEB:index-DbEHT6Gg.js@2058657` — `let R=(0,Q.useMemo)(()=>{let e=U_t(p,s?s.readActivities():I.items.flatMap(...));return L.current=W_t(L.current,e),L.current},[p,I.items,s,c])`
- `WEB:index-DbEHT6Gg.js@6687771` — `D5n=class{...constructor(e,t={}){...this.zcodeTaskService=Gl.toService(e.getChannel(I8e.channelName)),this.windowControllerService=Gl.toService(e.getChannel(L8e.channelName)),...}`
- `WEB:index-DbEHT6Gg.js@2025653` — `projectService.readTree({includeDeleted:!0,includeDeletedTasks:!0})`
- `grep -abo "lastAssistantPreview" Reference_web/3.16.1/assets/index-DbEHT6Gg.js` → 0 行；`validation` → 1 行（schema）

**影响**：本仓只订阅 sessions-index，**从未调用 `readTree`**（`grep -rn "readTree" android/ rust/` 仅注释）。因此任务列表在真机上可能只剩 sessions-index 里能看到的会话，且拿不到 `pinned/archived`。这是比 04 描述更大的缺口。

### C2 · `cvt` 不继承 `pinned/archived`；`unreadAt` 只在 truthy 时继承

**04 原文**（§5.2）：`cvt 必须从上一版任务对象继承 pinned/archived/unreadAt/mode/model/provider/traceId`。

**正确结论**：`cvt` 产出的键只有 `taskId/traceId/title/titleOverridden?/workspacePath/workspaceIdentity?/createdAt/updatedAt/mode/model?/forkedFromTaskId?/provider?/status?/pendingInteraction?/unreadAt?`，**没有 `pinned`/`archived`**。这两个字段只在 `W_t` 合并 `readTree.tasks` 时从树里的任务记录带过来。`unreadAt` 的继承条件是 `...n?.unreadAt?{unreadAt:n.unreadAt}:{}`，即 `0` 也会被丢弃。

**坐标**：
- `WEB:index-DbEHT6Gg.js@2026736` — `return HO({taskId:e.sessionId,traceId:n?.traceId??`session-${e.sessionId}`,title:a,...createdAt:e.createdAt||n?.createdAt||0,updatedAt:e.lastActivityAt||n?.updatedAt||0,mode:n?.mode??`default`,...n?.model?{model:n.model}:{},...e.parentSessionId?{forkedFromTaskId:e.parentSessionId}:{},...n?.provider?{provider:n.provider}:{},...r?{status:r}:{},...e.pendingInteraction?{...}:{},...n?.unreadAt?{unreadAt:n.unreadAt}:{}}`
- `WEB:index@2020424` — `function GN(e,t){return e.tasks.filter(e=>t.kind===`pinned`?e.pinned&&!e.archived:t.kind===`timeline`?!e.archived:...)}`
- `WEB:index@2061595` — `function zvt(e,t,n){let r=new Map(e.filter(e=>!e.pinned&&!e.archived)...`

### C3 · U1 可静态判定：`windowControllerService` 恒存在，`Dvt` 分支死

**04 原文**（§4.4/§9-U1）：`windowControllerService 是否存在 → UNVERIFIED`。

**正确结论**：`lE()` 返回的 `baseServices` 由 `D5n` 构造，`windowControllerService` 在构造函数里无条件赋值；`Fvt` 里 `o=t.windowControllerService`，`s=o?Rut(o):null`。只要 `D5n` 被使用（远端网页客户端走 `A5n` → `D5n`），`s` 恒非空。**「谁提供任务基底」已定：`readTree`。** 仍属 UNVERIFIED 的只是「远端 host 是否实现 `window-controller`/`project` 通道的运行时可用性」。

**坐标**：
- `WEB:index@1230850` — `function lE(){return j6e(_s(),CT(e=>e.baseServices))}`
- `WEB:index@6693704` — `function A5n(e,t={}){return new D5n(new ile(e),t)}`
- `WEB:index@6687771` — 同上无条件赋值

### C4 · relay WS 是 `/api/v1/remote/ws2`，不是 `/ws/remote/v4`

**04 原文**（§1.4/§2 步骤 2）：`wss://<origin>/ws/remote/v4?token=<terminal token>&mid=<deviceMid>`。

**正确结论**：3.16.1 网页的 relay URL 由 `Id({endpointOrigin})` 生成，`pathname='/api/v1/remote/ws2'`；`Qer()` = `Wt({endpointOrigin:window.location.origin})`，`Wt` 即该函数。`/ws/remote/<id>` 与 `/ws` 是 **Web+Server 模式**的本地 web-server 路由（`Ker()`），与手机远控 relay 无关。

**坐标**：
- `WEB:validation-BBf1FA1a.js@125195` — `function Id(e){...let n=new URL(kd(e?.endpointOrigin?.trim()||`https://zcode.z.ai`));return n.protocol=n.protocol===`https:`?`wss:`:`ws:`,n.pathname=`/api/v1/remote/ws2`,n.toString()}`
- `WEB:validation@126091` — `relayWsUrl:`${i}/api/v1/remote/ws2``
- `WEB:index@6818346` — `function Qer(){return Wt({endpointOrigin:window.location.origin})}`
- `WEB:index@6810990` — `function Ker(){let e=new URLSearchParams(window.location.search).get(`remote`),t=e?`${Ger()}/ws/remote/${e}`:`${Ger()}/ws`;...}`
- `RUST:connection_params.rs:487-494` — `token 模式（3.16.1+）：wss://<host>/api/v1/remote/ws2?token=…&mid=…` / `"/api/v1/remote/ws2"`（本仓与 3.16.1 一致）

### C5 · Rust `open_bridge` 已实现 `bridgeGeneration`

**04 原文**（D14）：`bridgeGeneration 未使用（Rust open_bridge 只带 workspaceKey/taskId）`，标 UNVERIFIED。

**正确结论**：每次 `open_bridge` 自增 generation 并写入请求；bridge-ready 的 generation 还会进入 RPC transport identity。

**坐标**：
- `RUST:rust/crates/protocol/src/zemote_client.rs:1367-1368` — `self.bridge_generation += 1; let generation = self.bridge_generation;`
- `RUST:zemote_client.rs:2448` — `payload.insert("bridgeGeneration".into(), json!(bridge_generation));`
- `RUST:rpc_transport.rs:136-138` — `#[serde(rename = "bridgeGeneration", skip_serializing_if = "Option::is_none")] pub bridge_generation: Option<i64>`

### C6 · Rust 已实现 `recoveryId`（reopen 路径）

**04 原文**（D15）：`recoveryId 未使用`，标 UNVERIFIED。

**正确结论**：`start_reopen` 复用旧 bridge 的 `recoveryId` 发 `workspace-bridge-open`；bridge-ready 的 `recoveryId` 被存进 transport identity，用于帧/ack 身份严格匹配。

**坐标**：
- `RUST:zemote_client.rs:1772-1786` — `self.bridge_generation += 1;...reopen_bridge_request(&request_id,&bridge_session_id,generation,old_bridge.get("recoveryId").filter(|value| !value.is_null()),workspace_key)`
- `RUST:zemote_client.rs:2029-2039` — `let recovery_id = bridge.get("recoveryId").and_then(Value::as_str).map(str::to_string);...RpcFrameTransport::new(wire_id,...,generation,recovery_id)`

### C7 · `markRead()` 是死代码，未读永远不会被清

**04 原文**（D19）：`markRead() 由 UI 在进入对话约 2s 后调用`。

**正确结论**：`HomeViewModel.markRead` 只有定义，全仓无调用点；`ChatViewModel` 也没有 `setTaskUnread`。进入对话不会清未读，也不会带 `expectedUnreadAt`。

**坐标**：
- `KOTLIN:android/app/src/main/kotlin/app/zemote/ui/home/HomeViewModel.kt:227-231` — `fun markRead(task: TaskItem, workspace: WorkspaceItem) { viewModelScope.launch { mutate(task, workspace, method = "setTaskUnread", extra = { put("unread", false) }) } }`
- `grep -rn "markRead" android/app/src/main/kotlin/` 仅 1 行（定义处）

### C8 · `zcode-task` 参数形状：能用，但不是网页首选路径

**04 原文**（D18）：`UNVERIFIED（方法名/参数形状需真机或抓包验证）`。

**正确结论**：`zcodeTaskService` 的方法都是 **flat 对象**（`{workspacePath,workspaceIdentity?,taskId,...}`），host 端直接解构，额外键被忽略——所以本仓传整份任务摘要 + `extra` **不会报错**。但网页在 controller 存在时首选 `controller.mutateTask({address,mutation})`（`address` 是 `{workspacePath,workspaceIdentity?,taskId,remoteSessionId?,sourceServerId?}` 形状），`zcode-task` 只是 fallback；`expectedUnreadAt` 也只在 mutation 路径里用于乐观并发。

**坐标**：
- `WEB:index@2048033` — `async function uP(e,t,n,r){let i={workspacePath:t.workspacePath,workspaceIdentity:t.workspaceIdentity,taskId:t.taskId};if(e.controller)return e.controller.mutateTask({address:kvt(t,r),mutation:n});switch(n.kind){case`pin`:return e.service.setTaskPinned({...i,pinned:n.pinned});case`mark-read`:return e.service.setTaskUnread({...i,unread:!1,expectedUnreadAt:n.expectedUnreadAt});...}`
- `DESKTOP:out/host/index.js@602851` — `async setTaskUnread(P){if(!P.unread&&typeof P.expectedUnreadAt=="number"){let le=await A.clearTaskUnreadIfMatches({taskId:P.taskId,workspacePath:P.workspacePath,...});...}}`
- `KOTLIN:HomeViewModel.kt:486-504` — flat `arg` + `engine.channelCall(session=bridge, channel="zcode-task", method=method, args=JsonArray(listOf(arg)))`

### C9 · 桌面端证据版本是 3.16.3，且 04 的部分网页偏移不可复现

**04 原文**：`审计对象：ZCode Web 3.16.1 + 同发布桌面端（app.asar）`；偏移约定称网页为 UTF-8 字节偏移。

**正确结论**：
1. `app.asar` 的 `package.json` 是 `"version": "3.16.3"`，**不是 3.16.1**。所有 `DESKTOP:` 结论只能当「3.16.x 参考」，不能直接当 3.16.1 权威。
2. 桌面 `out/main/index.js` 在 661,752 字符之前全 ASCII，因此 383k–412k 的 char 偏移与 byte 偏移相同；04 的 char/byte 警告在这段区间无实际影响。
3. 04 的网页偏移**多数**可复现（`tw@304850` 实际 304856、`YC@303107` 精确、`Cx@248245` 精确、`cvt@2026736` 精确），但至少 3 处不可复现：`getToken` 标 6633951（实际 **6750843**）、`auth_init` 标 6632663（实际 **6751405**）、`_m` 标 160412（实际 **160855**）。前两处落在测试夹具里。

**坐标**：
- `DESKTOP:package.json` — `"version": "3.16.3"`
- `WEB:index@6750843` — `let n=await this.options.getToken(t.signal);...r.searchParams.set(`token`,n)`
- `WEB:index@6751405` — `this.setState(`authenticating`),this.send({type:`auth_init`,device_sid:this.options.deviceSid,meta:{platform:`web`,version:this.options.appVersion??`web`,name:`mobile-browser`},client_ts:Math.floor(Date.now()/1e3)})`
- `WEB:validation@160855` — `var _m={maxPhysicalFrameBytes:Fp.maxFrameBytes,maxMessageBytes:16*1024*1024,maxFragments:64,assemblyTimeoutMs:3e4,...}`

---

## 3. MISSING：04 没覆盖但源码存在的分支/字段

| # | 缺失项 | 坐标与原文 |
|---|---|---|
| M1 | 订阅 ack 不只是 `subscriptionId`：sessions-index store 还读 `mode` / `logEpoch`（`mode==='resume' && ack.logEpoch===base.logEpoch` 才认为 base 已应用）；conversation store 另读 `openTiming` | `WEB:index@2033200` — `this.subscriptionHasAppliedBase=!!(r&&t.ack.mode===`resume`&&t.ack.logEpoch===r.logEpoch)`；`WEB:index@273199` — `setState({status:`live`,subscriptionId:r.ack.subscriptionId,lastError:null,openTiming:r.ack.openTiming})` |
| M2 | 订阅请求还支持 `base` 与 `visibility` 参数 | `WEB:index@2027954` — `subscribeSessionsIndexV4({...n,runtimePolicy:`existing-only`,...t.base?{base:t.base}:{},...t.visibility?{visibility:t.visibility}:{}})` |
| M3 | 帧回调带 `deliveryKind`（`initial` / `recovery` / `online`），决定断档时是 `connect(forceSnapshot)` 还是 `requestRecovery` | `WEB:index@2034702` — `handleFrame(e,t){...let r=t?.deliveryKind??`online`,i=r===`initial`?n:null;...if(e.payload.kind===`deltas`&&!this.subscriptionHasAppliedBase){this.requestRecovery(r===`recovery`);return}` |
| M4 | ack 前的帧会**暂存**，ack 后按序应用；`initialFrameStagingOverflow` 触发 `forceSnapshot` 重连 | `RUST:conversation_transport.rs:3162-3167` — `let staged = match self.subscriptions.get_mut(&key) { Some(sub) => sub.core.drain_staged(), ... }; for frame in staged { self.accept_logical_frame(...) }`；`WEB:index@2033647` — `if(i.includes(`fault.subscription.initialFrameStagingOverflow`)&&!t.initialOverflowRetry){await this.connect(e,{forceSnapshot:!0,...})}` |
| M5 | 网页 store **显式丢弃重复帧**（`toSeq<=seq`）；Rust `SessionsIndexState::apply_frame` 没有这个判断 | `WEB:index@2031195` — `if(t.toSeq<=e.seq)return{state:e,gap:!1}`；`RUST:conversation.rs:2230-2290` — `pub fn apply_frame(...)` 的 `deltas` 分支只有 `if from_seq != self.seq { on_gap(); return Ok(()) }`，没有 `to_seq <= self.seq` 的丢弃分支 |
| M6 | Rust 的 sessions-index topic 不做 `trim()`（网页 `lP` 做） | `RUST:conversation.rs:1458-1465` — `let v = match scope.get("workspaceIdentity"){...}; format!("sessions-index/{}", interp(v))`；`WEB:index@2045628` — `function lP(e){return e.workspaceIdentity?.trim()||e.workspacePath}` |
| M7 | 桌面 3.16.3 对 legacy 远程工作区会在订阅时补 `legacyTaskIds`（3.16.1 网页无此字段） | `DESKTOP:out/host/index.js@523277` — `if(Jfe(y.workspaceIdentity))try{he=(await s.listTaskMetas({workspacePath:y.workspacePath,workspaceIdentity:y.workspaceIdentity,provider:"glm"})).slice(0,R_).map(V=>V.taskId)}...let oe=await M.request(Nt.conversationSubscribe,{topic:te,connectionId:$.connectionId,clientMode:$.clientMode,...he.length>0?{legacyTaskIds:he}:{},...})`；`WEB:index`/`WEB:validation` 中 `legacyTaskIds` 命中 0 |
| M8 | `workspace-reconnect-request` / `workspace-reconnect-response` 是独立的一对消息，04 只在 §5.4 顺带提到请求 | `WEB:validation@305661` 区块 — `O({zcode_type:N(`workspace-reconnect-request`),requestId:Q,workspaceKey:Q})`、`O({zcode_type:N(`workspace-reconnect-response`),requestId:Q,workspaceKey:Q,success:N(!0)})`；`DESKTOP:@400929` — `async function fe(h,b){...Xe(h,{zcode_type:"workspace-reconnect-response",...})}` |
| M9 | `bridge-degraded` / `workspace-bridge-error` 的 reason 枚举（11 个） | `WEB:validation@303539` — `XC=M([`session-not-found`,`session-expired`,`session-conflict`,`workspace-closed`,`desktop-disconnected`,`invalid-mobile-connection`,`desktop-bootstrap-timeout`,`connection-recovery-timeout`,`relay-unavailable`,`unsupported-action`,`unexpected-error`])` |
| M10 | `mobile-view-state-update.deviceInfo` 是 12 键 schema，且 `updatedAt` 必填；缺它整帧被静默丢弃 | `WEB:validation@304481` — `ew=O({platform:Q,version:Q,name:Q,userAgent:S().optional(),...online:T().optional(),updatedAt:w().finite()})`；`RUST:zemote_client.rs:2512-2526` 注释与实现补 `updatedAt` |
| M11 | `WorkspaceProjection.from` 丢弃 `activeWorkspaceKey/activeTaskId`，即使 workspace-list 里有也不会用于落点 | `KOTLIN:ChatViewModel.kt:1526-1527` — `fun from(value: JsonElement?): List<WorkspaceItem> = snapshotOrNull(value)?.workspaces ?: emptyList()` |
| M12 | `SessionsIndexProjection` 用 `workspace.key` 而非条目里的 `workspaceId` 归位 | `KOTLIN:SessionsIndexProjection.kt:80` — `workspaceKey = workspace.key` |
| M13 | 网页 timeline 分组**包含置顶任务**（`!archived` 即可），本仓 `tasksOf` 排除 `pinned` | `WEB:index@2020424` — `t.kind===`timeline`?!e.archived:...`；`KOTLIN:HomeViewModel.kt:104` — `workspace.tasks.filter { !it.archived && !it.pinned }` |
| M14 | `mergeSessionsIndex` 的 ready 过滤：`ready==false && list 为空` 才跳过 | `KOTLIN:HomeViewModel.kt:449-454` — `val ready = ...; ready || obj["list"].asArray()?.isNotEmpty() == true` |
| M15 | 网页 relay 状态机还有 `reconnecting`；`waiting_terminal` 是 **pair_status**，不是 state | `WEB:index@6752789` — `suspend(){this.state!==`paired`&&this.state!==`connecting`&&this.state!==`reconnecting`&&this.state!==`authenticating`&&this.state!==`waiting`||...}`；`WEB:index@6755466` — `cee(e)===`waiting_terminal`? ... this.setState(`waiting`)` |
| M16 | 项目树事件会触发刷新：`onDynamicWorkspaceEvent` 的 `workspace_task_list_changed` 与 `broadcastService` 的 `state:projects` | `WEB:index@2053067` — `(s?[]:j).map(e=>t.zcodeTaskService.onDynamicWorkspaceEvent(e)(e=>{e.type===`workspace_task_list_changed`&&k()}))`；`WEB:index@2058461` — `t.broadcastService.onMessage(e=>{e.channel===`state:projects`&&k()})` |
| M17 | `readTree` 的 `deletedTasks` 参与合并；`W_t` 会保留旧对象引用做结构复用 | `WEB:index@2019942` — `function W_t(e,t){...let a=BA(e.tasks,t.tasks);return r&&a===e.tasks&&zA(e.deletedTasks,t.deletedTasks)?e:{...t,...}}` |
| M18 | `Dvt` 的每个 workspace 使用自己的 `agentService`，并按 `workspaceKey` 取 store；`service-unavailable` 是显式状态 | `WEB:index@2046156` — `let n=e.agentService??t;return n?Tvt(e,n):`${Cvt(e)} ${e.workspacePath} service-unavailable`` |

---

## 4. 旧基线残留清单

| 位置 | 残留 | 说明 |
|---|---|---|
| `KOTLIN:ChatViewModel.kt:1515-1533`（`WorkspaceProjection` 文档） | `形状已冻结（对端 3.15.3 的权威 schema）`、`tasks 是 workspaces 的顶层兄弟数组` | 3.16.1 的 `bootstrap`/`workspace-list` 都没有顶层 `tasks`；该文档直接导致 D1 的死分支 |
| `KOTLIN:HomeViewModel.kt:43-45` | `工作区与任务来自 bootstrap / workspace-list-* 的权威快照` | 3.16.1 任务不在 bootstrap |
| `KOTLIN:HomeViewModel.kt:329-330` | `3.16.1 的任务来自 sessionsIndex，不是 bootstrap` | 方向对了一半：任务来自 `readTree`，sessions-index 是活动/预览 |
| `KOTLIN:SessionsIndexProjection.kt:26-27` | `等桌面端提供任务元数据通道后再补` | 元数据通道已经存在（`zcode-task` 的 `getTaskMeta/setTaskUnread/setTaskPinned` + `project.readTree`），不是「未来能力」 |
| `KOTLIN:HomeViewModel.kt:127-128` | `zcode-task 通道（它是 window-controller.mutateTask 的代理）` | 注释方向反了：网页首选 `window-controller.mutateTask`，`zcode-task` 是 fallback |
| `KOTLIN:HomeViewModel.kt:66-67` / `HomeUiState.previews` | `taskId → lastAssistantPreview` | 跨工作区同 taskId 会撞键（04 附录 A 已指出，但实现未改） |
| `KOTLIN:HomeUiState.tasksOf` / `pinnedTasks` | 依赖 `pinned/archived/unreadAt` | 这些字段在 3.16.1 的 sessions-index 里不存在，整块 UI 语义建立在旧形状上 |

---

## 5. 网页 / 桌面端证据混淆清单

1. **版本混淆**：`app.asar` 是 **3.16.3**，04 称「同发布桌面端 3.16.1」。`legacyTaskIds`、`waiting_terminal` transportState 等只在该 3.16.3 包里读到。
2. **relay 路径混淆**：04 把本地 web-server 的 `/ws/remote/<id>`（`Ker()`，Web+Server 模式）当成了手机远控 relay；实际 relay 是 `Id()` 的 `/api/v1/remote/ws2`。
3. **`cvt` 与 `readTree` 混淆**：04 把 `pinned/archived` 的继承归给 `cvt`，实际归 `W_t` 合并 `readTree.tasks`。
4. **controller 分支混淆**：04 认为 `Dvt(sessions-index)` 是「无 controller 时的权威路径」，但网页客户端的 controller 服务恒存在，该路径不可达。
5. **`controller.list` 签名**：04 写 `controller.list(kind,{workspaceScopes,sortBy,limit})`；实际是 `s.list(label, {controllerRevision,taskListVersionSignature,workspaceSourceGenerationSignature}, {kind,workspaceScopes,sortBy,limit})`（`WEB:index@2057768`）。
6. **桌面偏移**：04 把桌面偏移标为 char、网页标为 byte，但网页 §8-E3 的 `getToken`/`auth_init` 两项实际是 char 近似值，不是字节偏移，导致不可复现。
7. **`connectionState`**：04 用桌面 `Ee()` 的输出来论证网页 schema；两者不是同一层（网页 schema 允许该键，桌面归一化会丢）。

---

## 6. 最终可信结论集（可直接用于实现）

### 6.1 【网页 3.16.1】bootstrap / workspace
1. `bootstrap-response.result` = 6 键：`windowControlSessionId`、`desktopAppVersion?`、`workspaces`、`initialViewState?`、`mobileViewState?`、`mobileTaskViewEpoch?`；**无 `tasks`**。
2. `workspaces` 元素：`workspacePath`(trim≥1)、`workspaceIdentity?`、`remoteSessionId?`、`label`、`workspacePurpose?('project'|'conversation')`、`kind('local'|'remote')`、`connectionState?`、`lastConnectionError?`。
3. 键规则 `workspaceIdentity?.trim() || workspacePath`。
4. `workspace-list-response` / `workspace-list-updated` 的 result 都是 `{workspaces, activeWorkspaceKey?, activeTaskId?}`；`activeWorkspaceKey` 兜底到当前 bridge 的 key（**总有值**），`activeTaskId` 兜底 `currentBridge.initialTaskId ?? initialViewState.activeTaskId`。
5. 版本门控：仅当 pathname 为 `/remote/v4` 且 `desktopAppVersion` 与 URL `app_version` 不等 → `location.replace`（页面未 ready 时延迟到 ready）。
6. 落点：`mobileViewState ?? initialViewState`；`activeWorkspaceKey` 必须在「可桥接集合」内，否则取第一个可桥接工作区；没有可桥接项时 `canBridge:false`，再退到第一个工作区，最后 `null`。

### 6.2 【网页 3.16.1】任务与元数据
7. 任务基底 = `projectService.readTree({includeDeleted:true,includeDeletedTasks:true})`（每个远程 host 再来一次）+ `windowControllerService.readActivities()`；`Dvt(sessions-index)` 仅在 controller 服务缺失时生效（网页实际不可达）。
8. sessions-index 条目在 controller 分支里**不进入任务列表**（`Dvt(s?[]:j)` 恒空）；只有 `Dvt` 回退分支才会用 `phase/hasBackgroundWork/pendingInteractionSummary/workflowActivity`。`lastAssistantPreview` 在 3.16.1 网页里没有任何消费点。
9. `pinned/archived` 只来自 `readTree.tasks`（或 `zcodeTaskService.listPinnedTasks/listArchivedTasks`）；`unreadAt` 来自 `getTaskMeta` / `onDynamicTaskUnreadSignal`。
10. `cvt` 继承：`title`（`previous.titleOverridden && titleSource!=='custom'` 时保留旧标题）、`titleOverridden`、`traceId`、`createdAt/updatedAt`、`mode`、`model?`、`provider?`、`unreadAt`（仅 truthy）；不继承 `pinned/archived`。
11. 状态：`svt` 只看 phase（`running|prewarming→running`，`completedSuccess|completedInterrupted→completed`，`error→error`，`draft→undefined`）；`hasBackgroundWork` 只影响 `Mit`。
12. 侧栏状态 `Hut`：`phase==='error' || (!activity && status==='error') → error`；`unreadAt` 是 number → `unread`；`prewarming|running → loading`；否则 `none`。
13. 写回：controller 存在时 `mutateTask({address:{workspacePath,workspaceIdentity?,taskId,remoteSessionId?,sourceServerId?}, mutation:{kind:'pin'|'archive'|'delete'|'rename'|'mark-read'|'mark-unread',...}})`；否则 `zcodeTaskService.setTaskPinned/setTaskUnread({unread,expectedUnreadAt})/archiveTask/...`。

### 6.3 【网页 3.16.1】sessions-index
14. 通道 `zcode-agent`；`subscribeSessionsIndexV4({workspacePath,workspaceIdentity?,runtimePolicy:'existing-only',base?,visibility?})` → `{ack:{subscriptionId,mode?,logEpoch?}}`（conversation store 另读 `openTiming`）。
15. 事件 `onDynamicSessionsIndexFrame(scope)` 带 `deliveryKind`；帧 `{toSeq,fromSeq?,payload:{kind:'snapshot',snapshot:{protocolVersion:1,workspaceId,logEpoch,sessions[]}} | {kind:'deltas',deltas:[{op:'session.upserted',session}|{op:'session.removed',sessionId}]}}`。
16. store 规则：snapshot 重建 Map；`toSeq<=seq` 丢弃；`fromSeq!==seq` 断档 → 首帧 `connect(forceSnapshot)`，否则 `requestRecovery`；deltas 在 base 未应用时先 recovery；`ack.mode==='resume' && ack.logEpoch===base.logEpoch` 才认 base。
17. 重试：subscribe 瞬时失败 `[250,1000,3000]ms`；错误恢复 `[5s,15s,60s]`；runtime restart → generation++、清 store、`forceSnapshot` 重连（burst 退避 100ms×2^n，上限 5s，30s 无重启则重置）。

### 6.4 【网页 3.16.1】bridge / RPC / relay
18. `workspace-bridge-open`：`requestId,bridgeSessionId,bridgeGeneration?,recoveryId?,workspaceKey,taskId?,taskHostId?,fileTreeTaskId?,project?`；约束：`project` 不能带 `taskId/fileTreeTaskId`；`taskId` 与 `fileTreeTaskId` 互斥；`fileTreeTaskId` 需要 `taskHostId`；`taskHostId` 需要 `taskId|fileTreeTaskId`。
19. `workspace-bridge-ready.bridge`：local/remote 判别联合；remote 必须有 `workspaceIdentity`+`remoteSessionId`，`capabilities.windowProjectDirectory` 恒 `true`。
20. `rpc-frame`：`seq/messageSeq/fragmentIndex(0..63)/fragmentCount(1..64)/messageBytes(≤16MiB)/checksum{algorithm:'crc32',value:8hex}/dataBase64(≤1MiB)`；`rpc-frame-ack{ackMessageSeq}`；流控 1MiB 高水位 / 256KiB 低水位 / 8MiB 重放 / 45s 宽限。
21. relay：`wss://<origin>/api/v1/remote/ws2?token=<terminal token>&mid=<deviceMid>`；token 由 `POST /api/v1/remote/token {role:'terminal'}` + `Authorization: Bearer <JWT>` 换取；内层 `{type:'data',payload}` 不再附 token。
22. `mobileTaskViewEpoch`：桌面进入 `paired` 时 `Ka()`；离开 `paired` 清空。手机 `seed(epoch)`；桌面 `mobile-task-view-request{epoch}`；手机回 `{epoch,seq,bridgeSessionId,bridgeGeneration?,view:{workspacePath,workspaceIdentity?,taskId}|null}`；桌面校验 paired + epoch + seq 单调 + bridge ready/not degraded + bridgeSessionId/bridgeGeneration + workspace 匹配。
23. 恢复：`visibilitychange(hidden)/freeze/pagehide → suspend`（发一次 `view:null`，relay suspend）；`visible/pageshow/online/resume → B(reason)`：先 `recoverConnection`，桥未降级且 pathname 为 `/remote/v4` 时只跑 bootstrap 门控，否则重开桥（`recoveryId` 复用）；重连预算 `[1s,2s,5s]`；`onSendReady` → `replayUnacknowledged`。

### 6.5 【本仓 Rust/Kotlin】
24. Rust **会**为 `subscribeSessionsIndex` 发 `Completed{Ok}`（ack 之后）；`conversationCallNoWait` 是防御性选择，不是必需；`ZemoteEngine.kt:396-399` 注释必须改。
25. Rust `open_bridge` 已实现 `bridgeGeneration`；reopen 路径已实现 `recoveryId` 复用与 identity 校验。
26. Rust `SessionsIndexState` 缺网页的「重复帧丢弃」；`sessions_index_topic` 未 trim。
27. Android 当前缺口（按严重度）：
    - 没有 `projectService.readTree`，任务基底错误（C1）；
    - `pinned/archived/unreadAt` 永远缺失，且 `markRead` 死代码（C2/C7）；
    - `workspace-list-updated` 到达即清空本地 tasks（§1.3）；
    - `initialViewState/mobileViewState` 只发不收，`mobileTaskViewEpoch`/`mobile-task-view-*` 未实现；
    - status 映射把 `hasBackgroundWork` 当 running（D7）；
    - `titleSource` 未消费（D8）；
    - 无桥可开性判定（D13）。

---

## 7. 未解问题

| # | 问题 | 现状 |
|---|---|---|
| U1 | 远端 host 是否在桥后实现 `window-controller` / `project` 通道（服务对象存在 ≠ RPC 可用） | **UNVERIFIED**：`D5n` 无条件建服务，但远端通道白名单未在网页包里读到 |
| U2 | `windowControlSessionId` 在网页端的具体消费点 | **UNVERIFIED**：网页 `bootstrap-response` 的校验/门控只读 `desktopAppVersion`，未读该字段；桌面 `routePayload` 只把它传给 `_()` |
| U3 | `Ka()`（`mobileTaskViewEpoch` 生成器）的确切格式 | **UNVERIFIED**：`Ka` 来自 `out/main` 之外的 chunk，未定位 |
| U4 | 3.16.1 桌面（非 3.16.3）是否有 `legacyTaskIds` / `waiting_terminal` | **UNVERIFIED**：本机只有 3.16.3 asar |
| U5 | `setTaskUnread({unread:false,expectedUnreadAt})` 在服务端的并发语义（不匹配时返回什么） | **UNVERIFIED**：host 只看到 `clearTaskUnreadIfMatches`，返回值形状未追 |
| U6 | 手机端是否应该改用 `window-controller.mutateTask`（而非 `zcode-task` flat 方法） | 待产品/兼容决策；两条路都能通 |

---

## 8. 复现方式（只读）

```bash
# 网页：按字节偏移取片段
node -e "const fs=require('fs'),b=fs.readFileSync('Reference_web/3.16.1/assets/index-DbEHT6Gg.js'),s=b.toString('utf8');let ci=b.slice(0,2058592).toString('utf8').length;console.log(s.slice(ci,ci+300))"
# 桌面：流式 grep
node tools/asar.mjs grep "buildBootstrapResult" --context 1200 --path-filter out/main/index.js
# 本仓
grep -n "conversationCallNoWait\|emit_completion" rust/crates/protocol/src/conversation_transport.rs
```

未修改任何源码或 Round 1 报告；未执行网页 JS；未联网；未使用真机；未留下临时文件。
