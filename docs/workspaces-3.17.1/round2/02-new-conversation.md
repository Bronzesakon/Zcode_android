# 02 · 新对话 / createSession / sendText 交叉复核（Round2）

> 复核对象：`Reference_web/3.17.1/assets/index-Dwhx77cK.js`（只读、未执行）、
> `Reference_web/3.16.1/assets/index-DbEHT6Gg.js`（仅用于 diff）、`Reference_code/lib/...`（Dart 参考实现）、
> 本仓 `rust/crates/{protocol,ffi}` 与 `android/app/src/main/kotlin/app/zemote/...`。
> Round1 报告：`docs/workspaces-3.17.1/round1/03-new-conversation.md`。
>
> 偏移口径：**UTF-8 字节偏移**（Node `Buffer.indexOf` / `Buffer.slice`，不是 JS 字符串下标）。
> ⚠️ 本次审计期间 Kotlin 侧被**并发重写**：`ChatViewModel.kt` 在 16:28→16:55 之间至少换过 5 个 sha256
> （`4aebe5fe…` → `5a3cd43a…` → `733aa076…` → `fb5d45c2…` → `c8f94d0a…`）。Kotlin 证据钉在
> **sha256 `733aa076…`**（§7.4 表头），偏移与行号只对那一版成立；每条同时给 `needle`，**复核请用 grep**。
> 收尾时并行工作流已实现本文 M2 的调用点部分与 M3 方向的部分改动，
> **以 §10「审计结束时的最新状态」为准**，§5.4/§8 的 Kotlin 片段仅为时点快照。
> Web / Rust 文件未变动，偏移可直接 `indexOf` 复核。

---

## 0. 结论速览（可直接当作规格）

| # | 结论 | 置信度 |
|---|---|---|
| 1 | 3.17.1 进草稿页（`sessionId===null`）在**挂载后的 `useEffect` 里**就发 `createSession`；payload 本地构造只有 `workspaceId`(=workspaceKey) 与可选 `config`，**`payload.sessionId` 由通用信封构造器 `fu()` 追加**为 `sess_<uuid>`（3.16.1 没有这一层）。 | 已证 |
| 2 | 预热失败后再点发送**会补发一次 `createSession`**（`waitForSession` 里 `startCurrent`），补发仍失败才抛「草稿会话创建失败，请重试」。Round1 的「不会补发」是错的。 | 已证 |
| 3 | `sendText` 线上载荷：`text` 必填，`mode/planEnabled/modelSelection` 由 web **无条件携带**（缺失时客户端自己 `blocked`）；`genUiContext` 由 `ga` 的 dispatch 包装统一注入（默认 `[]`，非空才受能力门禁）。缺失 `genUiContext` **不会**触发 `proto.genUiContextUnsupported`。 | 已证（客户端侧）/ 桌面侧 UNVERIFIED |
| 4 | Rust `send_text_payload` **不能表达** `mode/planEnabled/modelSelection`，且 `call_op` 不校验未知键 → Android 即便传了也会被**静默丢弃**。 | 已证 |
| 5 | `createSession` 90s / 命令 30s **不是 web 的常量**（web 端命令无超时）；来自 Dart 参考实现，Rust 照抄。Android 从未传 `timeoutSecs`，宿主 35s 就判死，核心却会跑到 90s（降级时还要先等 healthy 45s）→ 假失败 + 孤儿会话。 | 已证 |
| 6 | `createSession.payload.workspaceId` 的权威值 = **workspaceKey**（`identity?.trim() || path`），3.17.1 全部 4 处 createSession 调用（预热 / 工作流启动 / 工作流提升 / 崩溃重放）都遵守这个规则。Android 现在首选 key 是对的；本地工作区 key==path，远端工作区 path≠key。 | 已证（web 侧）/ 桌面解析 UNVERIFIED |

---

## 1. Q1 · 草稿预热时序

### 1.1 进入新对话页是否立即发？

**是**，但要看开关：`enabled = 草稿(sessionId===null) && agentStartupAllowed && 连接就绪位 && 无待重放记录`。

```
<I4 sessionId=null>
  └─ useEffect: zKt(...).acquire({generation, invalidationVersion, onBinding})   // zKt 内部 @2,976,499
       └─ FKt.reconcile() → startCurrent(v) → NKt({workspaceKey, dispatchCommand, onReady, onSettled, resolveInitialConfig})
            └─ dispatchCommand(`createSession`, payload, /*sessionId*/ null)
```

- 预热开关：`{binding:ea,waitForSession:ta}=zKt({enabled:t===null&&wr&&!Re, workspaceKey:Kn, …})`
  @ **4,783,596**（`t===null` 即草稿；`wr=yr&&fe`，`yr` 是 `qKt()` 返回的 `agentStartupAllowed`，
  `fe` 是连接就绪位；`Re` = 待重放的首条未决命令）。
- `acquire` 的生命周期：订阅者清空后 0ms 定时 `retireCurrent()`；`retireCurrent` 里 `binding=null` +
  `controller.dispose()`；`NKt.dispose()` 只在 `状态仍为 draft` 且已拿到 sessionId 时发 `deleteSession`。
- 自动退避：`var PKt=[500,1e3,2e3]` @ **2,971,749**，每次失败最多补 3 次（`scheduleRetryAfterFailure`）。

### 1.2 payload 到底有哪些键

两段拼出来的，**不要在 `NKt` 里找 `sessionId`**：

```js
// NKt 内部（@2,970,940）—— 本地只造两个键
u=()=>{let e={workspaceId:t}, n=a?.(); return n&&Object.keys(n).length>0&&(e.config=n), e}
// 发送（@2,971,074）
n(`createSession`, e, null)
```

```js
// 通用信封构造器 fu()（@247,452）—— 3.17.1 新增
let t=e.type===`createSession`
  ? {...e.payload, sessionId: e.payload.sessionId ?? `sess_${crypto.randomUUID()}`}
  : e.payload;
return {commandId:Ba(), clientId:Nle(), sessionId:e.sessionId, …}
```

因此线上的 `createSession` 命令是：

| 位置 | 键 | 值 |
|---|---|---|
| envelope | `commandId` / `clientId` / `sessionId` / `type` / `payload` / `issuedAt` | `sessionId` **键存在且为 null** |
| payload | `workspaceId` | `workspaceKey = identity?.trim() || path` |
| payload | `config` | 仅当 `resolveInitialConfig()` 返回非空 |
| payload | `sessionId` | `sess_<randomUUID>`（**3.17.1 新增**；3.16.1 bundle 中 `sess_${crypto` 出现 0 次） |

### 1.3 预热失败后用户点发送会发生什么

**会补发一次 createSession，补不上才报错**（不是 Round1 说的「不会补发」）：

```js
// FKt.waitForSession（@2,972,087）
this.current?.settled && !this.current.binding
  && (this.clearRetryTimer(), this.blockedInvalidationVersion=null,
      this.current=null, this.startCurrent(e));        // ← @2,972,353 重新走一遍 NKt
let r=this.current; if(!r) throw Error(`草稿会话尚未开始创建`);   // @2,972,538
if(await r.completion, …) throw Error(`草稿会话已切换，请重新发送`); // @2,972,246
if(!r.binding) throw Error(`草稿会话创建失败，请重试`);            // @2,972,749
```

发送路径把这段包在 `await ta()` 里，拿到 binding 后必须 `beginPromotion()` 成功才继续：

```js
let x=t===null?await ta():null;                                     // @4,790,984
if(x&&(await aa.current(x.sessionId),!x.beginPromotion()))
  throw Error(`草稿会话已切换，请重新发送`);                            // @4,791,015
let S=t??x.sessionId;                                               // 首条消息的 sessionId 来源
```

失败面的完整枚举：

| 触发条件 | 结果 | 证据 |
|---|---|---|
| 预热被拒（`status!=='accepted'` 或 `result.type!=='createSession'`） | 只 warn，binding 保持 null，走退避重试 | `[v4-draft-prewarm] createSession 被拒` @2,971,206 |
| 预热的 RPC 抛错 | warn，binding 保持 null，退避重试 | `createSession 失败` @2,971,468 |
| 退避 3 次仍失败 → 用户点发送 | `waitForSession` 补发 1 次；再失败 → `草稿会话创建失败，请重试` | @2,972,353 / @2,972,749 |
| 发送中用户切了工作区/草稿失效 | `草稿会话已切换，请重新发送` | @2,972,246 |
| 预热根本没启用（rpcReady=false / agentStartup 不允许 / 有待重放记录） | `草稿会话尚未启用`，**一个 RPC 都不发** | @2,976,685 |
| 预热的会话订阅报错 | 丢弃 binding，下次 `waitForSession` 重建 | `预热会话订阅失败，丢弃旧 binding` @4,785,680 |

### 1.4 对 Android 的落地规格（若要复刻预热）

1. `ChatViewModel.enter()` 里（草稿态、桥就绪、无待重放）发一次 `createSession({workspaceId})`，
   把 `sessionId` 存成 `draftBinding`；**不要带 `firstText`**。
2. 发送时：`binding == null` → 先补发一次（等价 `startCurrent`），仍失败则把文本回填输入框并报错
   （Android 已有 `draftRestore` 机制，可直接复用）。
3. 离开草稿页且未提升为正式会话 → `deleteSession({}, sessionId)` 回收；否则桌面会留一堆空会话。
4. 预热与"已选中任务"互斥：`selectedTaskId != null` 不预热。

---

## 2. Q2 · 首条消息

### 2.1 `sendText` 的线上载荷

字面量（@4,791,287）：

```js
w=await c(`sendText`,{
  text:e, ...d,                                           // d = submission
  ...requestedDelivery?{requestedDelivery}:{},
  conversationQuotes:n?.conversationQuotes,
  ...attachments.length>0?{attachments}:{},
  ...heldQueueDisposition?{heldQueueDisposition}:{},
  ...expectedHeldQueueItemIds?{expectedHeldQueueItemIds}:{},
  ...sharedContextRefs?.length?{context_refs:sharedContextRefs}:{},
  ...source?{source}:{},
  ...C.override                                            // 加速卡：可能带 modelExecution
}, S, void 0, void 0, telemetrySeed, …)
```

`d` = `qM()` 冻结的 submission（@1,994,945）：

```js
Object.freeze({
  mode: r.data===`plan`?`build`:r.data,      // plan 被归一化成 build+planEnabled
  planEnabled: Ii(e).planEnabled,
  modelSelection: {providerId, modelId, options:{reasoningLevel}}
})
```

`genUiContext` **不在这个字面量里**，由 `ga` 的 dispatch 包装补上（@4,789,218 / @4,789,440）：

```js
t[0]===`sendText` && (t[1]={...t[1], genUiContext:[]})       // 默认空数组
t[0]===`sendText` && … genUiService … t[1]={...t[1], genUiContext:e}  // 服务可用时注入真实上下文
```

| 键 | web 是否必带 | Rust `send_text_payload` |
|---|---|---|
| `text` | 必 | ✅ |
| `mode` / `planEnabled` / `modelSelection` | 必 | ❌ **静默丢弃** |
| `genUiContext` | 必（默认 `[]`） | ❌ |
| `attachments` | 有才带 | ✅ |
| `conversationQuotes` / `context_refs` / `source` | 有才带 | ❌ |
| `requestedDelivery` | 有才带 | ❌ |
| `heldQueueDisposition` / `expectedHeldQueueItemIds` | 有才带 | ✅ |
| `automationId` / `offPeak*` / `botDeliveryTarget` / `toolDisallowlist` | 旁路 | ✅ |

### 2.2 `mode/planEnabled/modelSelection` 缺失时桌面端会怎样？

- **客户端侧已证**：web 自己不允许缺。`if(!d)return J.warn('[v4-pane] Submission 缺少完整模型或模式配置'),'blocked'`
  @4,790,208；`qM` 在 `modelSelection` 不在模型目录里时直接返回 `null`。
- **桌面侧 UNVERIFIED**：3.17.1 快照里没有任何 command-payload 的 schema
  （`validation-B0HfQ5gr.js` 中 `createSession` 出现 0 次；`sendText` 只出现在 queue 行 schema）。
  只能给旁证：`QueueItem` 行 schema 里 `modelSelection?` / `mode?` / `planEnabled?` 全是 `.optional()`
  （@207,657 区），说明对端至少**能持久化**缺席的模型字段——但那不是命令校验 schema，不能当结论。
- **实际行为推断**：缺 `modelSelection` 时最可能是「用工作区默认模型」，**不会报错**；缺 `mode` 同理。
  标 UNVERIFIED，不建议把正确性建立在这条推断上。

### 2.3 `genUiContext` 缺失是否触发 `proto.genUiContextUnsupported`

**不会。** 该错误只有一个抛出点（@272,594），且三个条件必须同时成立：

```js
if(t.type===`sendText` && o.genUiContext?.length && i.capabilities.genUiContext!==!0)
  throw Error(`proto.genUiContextUnsupported`);
```

即：**仅当 `genUiContext` 非空数组 + hello 应答未宣告 `capabilities.genUiContext:true`**。
`undefined`、`null`、`[]` 都不触发。这是**客户端自己的门禁**，Rust 核心不会走它。

> 同一处还有另一个门（3.16.1 就有）：`planEnabled` / `config.planEnabled` / `firstInput.planEnabled`
> 为真且 hello 未宣告 `capabilities.independentPlanState===true` → `proto.independentPlanUnsupported`
> （@272,435）。Rust 的 `ClientHello` 没有任何 `capabilities` 字段（`conversation.rs` @7,429），
> 所以「Android 发 planEnabled:true」在**这个语义**下等价于"能力未协商"。服务端是否也拒绝：UNVERIFIED。

### 2.4 Rust `send_text_payload` 是否足够

**不够，而且失败是静默的。**

- payload builder 只接 9 个参数（`conversation.rs` @15,001），没有任何模型/模式入口。
- `"sendText" => { … }` 只读 `sessionId/text/attachments/heldQueueDisposition/expectedHeldQueueItemIds/
  automationId/offPeakTaskId/offPeakRunType/botDeliveryTarget/toolDisallowlist`（@53,100），
  **不读也不校验其它键**；`call_op` 只在 `OPS` 名单层面拦未知 **op**（@37,662 / `call_op` 入口），
  不拦未知 **args 键**。
- 因此 Android 现在把 `mode/modelSelection` 塞进 `args` ⇒ Rust 丢掉，线上帧里没有，桌面只能吃默认模型。

**规格**：`send_text_payload` 增加 `mode/planEnabled/modelSelection`（全部可选、空值不写），
`sendText` 分支读取并透传；Android 由 `state.config`（`modelSelection/provider/model/mode/planEnabled`）
构造，`plan` 归一化成 `mode="build" + planEnabled=true`（与 web `qM` 一致）。
**先只发 `mode+modelSelection`，`planEnabled` 需单独开关**——理由见 §2.3 的 `independentPlanUnsupported`。

---

## 3. Q3 · createSession 与 sendText 的时序约束

### 3.1 accepted 之前能不能发 sendText？

**不能（拿不到地址），但协议层没有强制。**

- `sendText` 的 envelope.sessionId 用的是预热 binding 的 `sessionId`，而 binding 只在
  `status==='accepted' && result.type==='createSession'` 时建立（`NKt` 的 `onReady` 分支，
  `c=e.result.sessionId, … r(c)`，@2,970,598 区）。
- 发送路径 `let S=t??x.sessionId`（@4,790,984）之后才 dispatch；没有任何"先用客户端生成的
  `sess_<uuid>` 当 sessionId"的写法。
- Rust 侧也没有跨 op 依赖：`sendText` 只要求 `args.sessionId` 是字符串（@53,100），
  `createSession` 的 flow 与 `sendText` 的 flow 互不感知。**顺序完全由宿主保证。**
- 「客户端在 `payload.sessionId` 里自造的 `sess_<uuid>` 是否会被桌面采用为最终 sessionId」：**UNVERIFIED**
  （web 只读 `result.sessionId`，从不回读自己发出去的那个值）。所以不要用「提前发 sendText 到
  `sess_uuid`」这种捷径。

### 3.2 `subscribe` 与 `sendText` 的顺序

- 协议上：`subscribe` 是独立 RPC（`subscribeConversationV4({...scope, sessionId, base?, visibility?})`），
  Rust 对应 `"subscribe"` op（@49,307），只要求先握手；与 `sendText` 没有帧序约束。
- 参考实现的做法：**先订阅、再发首条**。拿到 binding 的 sessionId 后就被当成"当前会话"去开订阅
  （`oa=t??Re?.sessionId??ra`，@4,783,596 区；并有「预热会话订阅失败，丢弃旧 binding」@4,785,680）。
- Dart 参考实现把这条写成了注释（`Reference_code/lib/ui/chat_page.dart` @22,506）：
  `// This avoids a send-before-subscribe race where the first command can be dropped on a fresh session.`
- **Android 现状与规格一致**：`createConversation` 现在是 createSession → `subscribeConversation(newId)`
  → (upload) → `sendText`（ChatViewModel @line 705/@733/@743）。但 `subscribeConversation` 用的是
  `conversationCallNoWait`（不等 ack，`ZemoteEngine` @20,212 区），仍存在 ack 前的窄窗口；
  若要彻底对齐 Dart，改成等 ack 或至少在 sendText 前 `yield` 一帧。属 P2。

### 3.3 `createSession + firstInput` 在 3.17.1 的地位

**不是"仅崩溃重放"。** 3.17.1 有 3 条路径带 `firstInput`：

| 路径 | 证据 | 性质 |
|---|---|---|
| 保存的工作流「提升为全局」 | @5,000,054：`sendCommand(fu({type:'createSession',payload:{workspaceId:nbn(e),firstInput:{text:eNn(r)}},sessionId:null}))` | **正常 UI 功能**，不是恢复 |
| 首条未决命令重放（崩溃恢复） | @4,814,048：`ri(t.result.sessionId, clientContext.sessionCreateSource, e.payload.firstInput?t.commandId:void 0, …)` | 恢复路径 |
| `createSelectionSideSession` | @4,779,425：`vi('createSelectionSideSession',{firstInput:{text:e,…}}) ` | 不同类型，但同族 |

更重要的是：**3.17.1 也有"先建空会话、再发后续命令"的正常路径**，与 Android 现在的做法同构：

```
saved-workflow-launch（@4,372,052）
  createSession({workspaceId: nbn(e)})            // 不带 firstInput
  → await accepted，取 result.sessionId = u
  → sendCommand({type:'startSavedWorkflow', …, sessionId:u})   // 紧接着发，不等订阅
  → 失败：deleteSession(u) 回收空会话
```

这条路径同时证明了三件事：
1. `createSession` 不带 `firstInput`、拿到 sessionId 后再发下一条命令，是桌面上正常工作的流程；
2. 后续命令在 `accepted` 之后立刻发即可，**不强制等订阅 ack**（对 `startSavedWorkflow` 而言）；
3. 失败时用 `deleteSession` 回收空会话是官方做法（与 `NKt.dispose` 的回收逻辑一致）。

另外 `Fkn()`（@4,749,900）会从 `createSession` 的 `firstInput` 里回读
`modelSelection / mode / planEnabled`，说明 **3.17.1 的 firstInput 允许带模型与模式**（Round1 §2.2 正确）。

结论：
- 桌面端**是否仍正确处理 firstInput**：快照只有 web 侧代码，**UNVERIFIED**；
  但「保存工作流提升」是 3.17.1 的正常功能，若 firstInput 不被处理该功能直接废掉——强旁证其可用。
- **Android 不该依赖它**：3.17.1 的主路径（草稿预热 + sendText）不用 firstInput，
  而用 firstInput 会把"首条消息"和"建会话"绑成一个不可重试的整体（失败就得重发整个 createSession，
  有重复建会话风险）。现在的「createSession(无 firstInput) → subscribe → sendText」是正确形态。

---

## 4. Q4 · 超时

### 4.1 90s / 30s 的来源

| 层 | 值 | 证据 |
|---|---|---|
| Dart 参考 `createSession` 默认 | 90s | `Reference_code/lib/protocol/conversation.dart` @9,256（`Duration timeout = const Duration(seconds: 90),`） |
| Dart 参考调用点显式传 | 90s | `Reference_code/lib/ui/chat_page.dart` @23,065 |
| Dart 参考 `sendCommand` 默认 | 30s | `Reference_code/lib/protocol/conversation.dart` @4,760 |
| Rust 常量 | 30 / 90 | `conversation.rs` @3,388 / @3,775 |
| Rust `createSession` 分支默认 | 90s（可被 `timeoutSecs` 覆盖） | `conversation_transport.rs` @61,044 |
| Rust 其它命令默认 | 30s | `enqueue_command` @74,210；`opt_timeout_ms` @151,947 |
| **Web 3.17.1** | **没有超时** | `sendCommand` 只 `await a()` 握手后直接 RPC；`Ale()` 只是 E2E 延迟钩子（`__zcodeTransportDelayE2E`，@246,598），不设超时 |

即：**90/30 是 Dart 移植常量，不是网页行为**。网页端命令可以无限等（靠 UI 的 loading 状态收场）。

### 4.2 Kotlin 宿主的超时怎么取

```kotlin
// ZemoteEngine.kt @32,161（sha 7448870…）
private fun hostTimeoutMs(command: JsonObject): Long {
    val declaredMs = (command["timeoutMs"] as? JsonPrimitive)?.longOrNull
        ?: conversationTimeoutSecs(command)
    return (declaredMs ?: DEFAULT_OPERATION_TIMEOUT_MS) + HOST_TIMEOUT_GRACE_MS
}
// @32,521
private fun conversationTimeoutSecs(command: JsonObject): Long? {
    if (command.str("method") != "conversation_call") return null
    val args = command["args"] as? JsonObject ?: return null
    return (args["timeoutSecs"] as? JsonPrimitive)?.longOrNull?.times(1000)
}
```

- 取值链：顶层 `timeoutMs` →（仅 `conversation_call`）`args.timeoutSecs` → 默认 30s；再 **+5s** 宽限
  （`DEFAULT_OPERATION_TIMEOUT_MS=30_000L` @1,437，`HOST_TIMEOUT_GRACE_MS=5_000L` @1,906）。
- **全仓没有任何调用点传 `timeoutSecs`**（`grep timeoutSecs android/app/src/main/kotlin` 只命中引擎注释/实现）。
  所以 `createSession` 的宿主上限 = **35s**，而 Rust 核心是 90s。
- Rust 默认是否 90s：**是**（`None => CREATE_SESSION_TIMEOUT_SECS * 1000` @61,044）。

### 4.3 不一致会导致什么（实际可观测）

Rust 的 `createSession` flow 在核心内按 90s 计时；宿主只等 35s：

```
0s   宿主发送 conversation_call(op=createSession) → Rust OpAccepted(token)
35s  宿主 withTimeoutOrNull 到期 → pending.remove(token) → 抛 timeout 错误条
      ⇒ ChatViewModel catch → error="操作 createSession:1 超时…" + draftRestore 回填文本
90s  核心命令定时器还没到点；桌面此时才回 accepted
      ⇒ Rust 拿到 result.sessionId，发 Completed —— 但宿主已丢弃该 token，**无人接收**
```

后果（都可复现）：
1. **假失败**：UI 报超时，实际会话已建好（桌面上能看到一条空会话）。
2. **孤儿会话**：App 不知道 sessionId，无法订阅/回收；用户重试 → 再建一个（会话列表里成对出现）。
3. **无法取消**：`conversation_timeout` 之后没有任何 cancel op；Rust flow 继续跑完。
4. 若桥处于降级态，Rust 在发命令前还有一次 `waitHealthy`（45s，`conversation_transport.rs`
   @100,601 / @10,385），最坏总时长 = 握手 30+30 + healthy 45 + cmd 90 ≈ **195s**，与 35s 相差一个量级。

**规格（注意：光传 `timeoutSecs` 不够）**：
- 宿主的公式是 `declared + 5s`，而 Rust 的最坏路径是 `healthy(45s) + declared`。两者相差 40s，
  **任何有限的 `timeoutSecs` 都补不平**（传 90 ⇒ 宿主 95、核心 135；传 135 ⇒ 宿主 140、核心 180）。
- 因此正确修法在**宿主**：`conversation_call` 的兜底超时改成
  `declared + BRIDGE_HEALTHY_TIMEOUT(45s) + grace(5s)`（只对 `method=="conversation_call"` 生效）。
  或者让 Rust 把 `waitHealthy` 计入命令自身的 `timeout_ms`（改核心语义，影响面更大）。
- 调用点仍建议显式传 `timeoutSecs: 90`：让「核心意图」写在请求里，宿主的 45s 预算是纯兜底。
- 无论哪种，**超时后不要提示"失败"就完事**：至少把"可能已在桌面创建"的语义写进错误文案，或超时后
  走一次 `queryCommands` 对账（web 的 `yL.reconcileSession` 就是这个思路）。

---

## 5. Q5 · workspaceId 的权威值

### 5.1 web 侧：四处 createSession 全用 workspaceKey

| 调用点 | 取值 | 证据 |
|---|---|---|
| 草稿预热 `NKt` | `workspaceId: workspaceKey`（`Kn=identity?.trim()||path`） | @2,970,940 |
| 保存工作流**启动**（无 firstInput） | `workspaceId: nbn(e)` | @4,372,052 |
| 保存工作流**提升为全局**（带 firstInput） | `workspaceId: nbn(e)`，`function nbn(e){return e.workspaceIdentity?.trim()||e.workspacePath}` | @4,370,766 / @5,000,054 |
| 崩溃重放 | 重放记录里的 `payload.workspaceId`；`mL()` 先取 `clientContext.workspace` 的 key，再与当前 key 比对（`XEt`：`mL(e)===(n?.trim()||t)`） | @2,387,440 / @2,387,703 |

`mL`/`XEt` 的存在本身就证明：**`payload.workspaceId` 的语义就是 workspaceKey**，否则没法拿来和当前工作区比对。

### 5.2 桌面端怎么处理这个值

**UNVERIFIED（快照里没有 host 代码）**，但有两条硬旁证：

1. 3.17.1 的 `WorkspaceEntry` schema **没有 `workspaceId` 字段**（Round1 `01-workspace-list.md` §4/§节点 96/209），
   桌面一侧工作区的稳定标识就是 `workspacePath + workspaceIdentity?`，`workspaceKey` 是它们唯一的归一化。
2. 命令外层 scope 只有 `workspacePath` / `workspaceIdentity?`（web 是 `{...n}`，n 由 workspace 构造）；
   `createSession` 的 payload 再单独给一个 `workspaceId`——如果它不是 key，客户端没有任何途径拿到"第 4 种 id"。

**因此：`workspaceId` 必须送 workspaceKey，不能送裸 path（远端工作区）。**

### 5.3 sessions-index 返回的 workspaceId 是什么

- Rust 侧**原样透传**，不做任何推导：`self.workspace_id = str_of(snap.get("workspaceId"))?;`
  （`conversation.rs` @87,015）。所以它是桌面给的、不透明的值。
- web 侧**只存不用**：sessions-index 归约里唯一一处是 `state:{workspaceId:e.workspaceId,…}`
  （@2,077,789）；`X.workspaceId` 形式的属性读取全 bundle 只此一处，解构 `{workspaceId}` 也 0 处
  ——未发现任何消费者。
- 结论：它是桌面自述的字段，**不能假设 `=== workspacePath`**。Round1 §3 的「服务端一般回
  `workspaceId === workspacePath`」在本快照里**没有证据**，只是 Android 注释里的传闻。

### 5.4 Android 现状安全性

> 时点快照（sha `733aa076…`）。后续版本已改为优先取注册表 handle 的
> `effectiveWorkspaceKey`，即本文 M3 方向；见 §10。

当时（`733aa076…`）的实现：

```kotlin
// @line 687（sha 733aa076…）
val workspaceId = _state.value.selectedWorkspace?.key
    ?: _state.value.selectedWorkspaceKey
    ?: _state.value.workspaceId
```

| 场景 | 结论 |
|---|---|
| **本地工作区**（identity 缺席） | `key == path`，`selectedWorkspace.key` 与 path 等价 → **安全**；即便退到 `workspaceId`（sessions-index）风险也低。 |
| **远端工作区**（identity = `remote:ssh:…`） | `key = identity` ≠ `path`。当前首选 key → **正确**；但历史上「path 兜底」的写法（Round1 记录的旧版）在远端是**错的**：桌面会拿一个远端绝对路径去匹配工作区，轻则 `noWorkspace`，重则落到另一个同路径的工作区。 |
| **无 selectedWorkspace、无 route key** | 只剩 `_state.workspaceId` → 可能来自**上一个工作区**（见下），有串工作区风险。 |

残留缺陷（按严重度）：

1. **`state.workspaceId` 跨工作区不清空**：`openWorkspaceInternal`（@999）没有
   `workspaceId = null`；`sessionsIndexUpdated`（@line 1661）/`refreshConversationState`（@line 1355）
   只做 `workspaceId = new ?: old`（@58,017）。切工作区后旧值一直留着。→ 作为第三兜底才被用到，风险中等；
   建议**切换工作区时清空**，或干脆从回退链去掉（key 已经足够）。
2. **key 归一化不一致**：`ChatOps.conversationKeyOf`（@7,075）用 `workspaceIdentity` 的**原样**字符串
   （不 trim、不判空），而注册表的 map key 是 `selectedWorkspaceKey`（= `identity?.trim()||path`，
   `ChatViewModel.toWorkspace` @76,491）。identity 带首尾空白时两者不等 → `registry.handleFor()`
   返回 null → 抛 `工作区还在连接，请稍候再发送`（ChatOps @6,111）。→ 统一到一个 `workspaceKeyOf()`。
3. **Rust 的 `conversation_key` 也不 trim**：`conversation_key(scope)` = `identity`（非 null 即用，空串也给空串）
   否则 `path`（`zemote_client.rs` @105,181）；web 是 `identity?.trim()||path`（空串回落到 path）。
   identity 为空串时 Rust 的 conversation key 会变成 `""`，与其他层不一致。→ Rust 侧加 `trim().is_empty()` 回落。

---

## 6. 与 Round1 冲突的结论

| # | Round1 的说法 | 复核 | 证据 |
|---|---|---|---|
| C1 | §0/§1.3「预热失败 ⇒ 根本没有会话，也**不会补发 createSession**」 | **错**。`waitForSession` 在 `settled && !binding` 时会 `startCurrent()` 重新发一次 createSession，补发失败才抛「草稿会话创建失败，请重试」。 | web @2,972,353 / @2,972,749 |
| C2 | §1.3/§2.3「`genUiContext: []` 恒为空数组（composer 显式置空）」 | **位置与恒定性都不对**：不在 sendText 字面量里，而在 `ga` 的 dispatch 包装 `c()` 里注入；且 genUi 服务可用时会注入**非空**上下文（此时才受能力门禁）。Round1 引用的 E10 片段看不到它。 | web @4,789,218 / @4,789,440；字面量 @4,791,287 无该键 |
| C3 | §2.3「`mode` 枚举 = build\|edit\|yolo\|guarded，plan 被归一化」 | **不准确**：3.17.1 validation 的 mode 枚举**含 `plan`**（`M(['build','edit','plan','yolo','guarded'])`），只是 web composer 主动归一化成 build+planEnabled。Rust 若直接透传 `plan` 也在 schema 允许范围内。 | validation @206,619 |
| C4 | §6 H1「`workspaceId` 全仓从未赋值（死字段）」 | **已被改掉**：现在有两条写回路径（sessions-index 快照 @line 1355、`sessionsIndexUpdated` @line 1661），且 createConversation 首选 `selectedWorkspace.key`，`workspaceId` 只作第三兜底。 | ChatViewModel @line 1355/1661 |
| C5 | §6 H5「`EngineChatOps` 用 `engine.bridgeSession.value` 而非注册表 handle，导致第二条 transport」 | **已被改掉**：`EngineChatOps` 注入 `registry`，`ensureConversation` 改为 `registry.handleFor()`，取不到就 `invalidState`（不再自己 open）；`AppContainer` 已传 `registry = conversationRegistry`。 | ChatOps @5,574/@6,111；AppContainer @6,958/@7,371 |
| C6 | §6 现象学「输入框已清空、无消息、无错误条」 | **前提已变**：发送前会先 `ensureRouteBridge`，ChatOps 缺句柄会立刻抛 `工作区还在连接，请稍候再发送`；且 `draftRestore` + `LaunchedEffect(state.draftRestore)` 会回填文本（不再静默丢字）。 | ChatViewModel @line 703；ChatScreen @10,549 / @7,132 |
| C7 | §5 D6「Kotlin 只认 `args.timeoutSecs`，不传就按 30s+5s → createSession 35s 判死」 | **仍成立**，并补充：全仓**没有任何调用点传 `timeoutSecs`**；Rust 默认 90s。 | ZemoteEngine @32,161/@32,521；grep timeoutSecs 仅命中引擎 |
| C8 | §0 点 1「firstInput 只在崩溃恢复重放与工作流升级两条旁路」 | **不完整**：还有 `createSelectionSideSession` 的 firstInput（不同类型）；且"保存工作流提升"是**正常 UI 功能**，不是恢复旁路。另外 3.17.1 还有一条**不带 firstInput** 的 createSession 正常路径（saved-workflow-launch）。 | web @5,000,054 / @4,814,048 / @4,779,425 / @4,372,052 |
| C9 | §5 D2「Rust createSession 不写 sessionId」 | **成立**，补充：Rust 也**不读** `args.sessionId`（op 分支只读 workspaceId/firstText/attachments/config/runtimeModel/mcpServers/timeoutSecs），所以 Android 目前**无法**补这个字段，只能改 Rust。 | transport @60,145 |
| C10 | §1.3 表 #1「清空输入框发生在 await 之前」 | **成立**（未变）：`onSend{ onSend(text); input="" }`。现在多了 `draftRestore` 回填，但清空仍是乐观的。 | ChatScreen @10,549 |

---

## 7. 证据索引（文件 @ UTF-8 字节偏移 + 原文）

### 7.1 Web 3.17.1 主 bundle（`index-Dwhx77cK.js`，6,986,617 B）

| # | 偏移 | 原文（截断） | 用途 |
|---|---|---|---|
| W1 | 2,970,598 | `function NKt(e){let{workspaceKey:t,dispatchCommand:n,onReady:r,onSettled:i,resolveInitialConfig:a}=e…` | 预热 worker |
| W2 | 2,970,829 | ``清理预热会话被拒`` | dispose → `deleteSession` |
| W3 | 2,970,940 | `let e={workspaceId:t},n=a?.();return n&&Object.keys(n).length>0&&(e.config=n),e` | payload 只有 workspaceId(+config) |
| W4 | 2,971,074 | ``n(`createSession`,e,null)`` | 预热发送点 |
| W5 | 2,971,206 | ``createSession 被拒`` | 被拒只 warn |
| W6 | 2,971,365 | ``draft session 预热就绪`` | onReady |
| W7 | 2,971,468 | ``createSession 失败`` | 抛错只 warn |
| W8 | 2,971,749 | `var PKt=[500,1e3,2e3]` | 退避 3 次 |
| W9 | 2,972,087 | `async waitForSession(e,t){` | 发送前等待 |
| W10 | 2,972,246 | ``草稿会话已切换，请重新发送`` | promote 失败 |
| W11 | 2,972,353 | `this.current?.settled&&!this.current.binding&&(…this.current=null,this.startCurrent(e))` | **补发 createSession** |
| W12 | 2,972,538 | ``草稿会话尚未开始创建`` | 无 current |
| W13 | 2,972,749 | ``if(!r.binding)throw Error(`草稿会话创建失败，请重试`)`` | 补发仍失败 |
| W14 | 2,976,685 | ``草稿会话尚未启用`` | enabled=false 时 |
| W15 | 4,783,596 | `{binding:ea,waitForSession:ta}=zKt({enabled:t===null&&wr&&!Re,…}),…let ra=ea?.sessionId??null,…oa=t??Re?.sessionId??ra;` | 预热启用条件 + 草稿即订阅 |
| W15b | 2,976,499 | ``(0,Q.useEffect)(()=>{if(t)return l.acquire({generation:u,invalidationVersion:a,onBinding:…})},[l,t,u,a])`` | acquire 在 useEffect 里（挂载后） |
| W16 | 4,785,680 | ``预热会话订阅失败，丢弃旧 binding`` | 订阅失败丢 binding |
| W17 | 4,789,218 | ``t[0]===`sendText`&&(t[1]={...t[1],genUiContext:[]})`` | genUiContext 注入位置 |
| W18 | 4,789,440 | ``t[1]={...t[1],genUiContext:e}`` | 非空上下文 |
| W19 | 4,790,208 | ``if(!d)return J.warn(`[v4-pane] Submission 缺少完整模型或模式配置`),`blocked``` | 客户端强制三键 |
| W20 | 4,790,984 | `let x=t===null?await ta():null;` | 首条必须等 binding |
| W21 | 4,791,015 | `if(x&&(await aa.current(x.sessionId),!x.beginPromotion()))throw Error(…)` | promote |
| W22 | 4,791,287 | ``let C=await JWt(S,s,d.modelSelection),w=await c(`sendText`,{text:e,...d,…`` | sendText 载荷 |
| W23 | 4,792,224 | ``sendText 被拒绝`` | 拒绝文案 |
| W24 | 247,452 | ``createSession`?{...e.payload,sessionId:e.payload.sessionId??`sess_${crypto.randomUUID()}`}`` | **3.17.1 新增 payload.sessionId** |
| W25 | 247,857 | `Aee.parse(await e.helloConversationV4())` | hello 应答过 zod |
| W26 | 248,048 | `initializeConversationV4({kind:`clientHello`,protocolVersion:3,…,capabilities:n})` | 握手第二步 + 能力 |
| W27 | 272,435 | `i.capabilities.independentPlanState!==!0&&(o.planEnabled||o.config?.planEnabled||o.firstInput?.planEnabled)…independentPlanUnsupported` | plan 门禁 |
| W28 | 272,594 | `t.type===`sendText`&&o.genUiContext?.length&&i.capabilities.genUiContext!==!0…genUiContextUnsupported` | genUi 门禁（仅非空） |
| W29 | 1,994,945 | `function qM(e,t){if(!e)return null;…Object.freeze({mode:…,planEnabled:…,modelSelection:…})` | submission 形状 |
| W30 | 4,370,766 | `function nbn(e){return e.workspaceIdentity?.trim()||e.workspacePath}` | 工作流路径的 workspaceId |
| W31 | 5,000,054 | ``sendCommand(fu({type:`createSession`,payload:{workspaceId:nbn(e),firstInput:{text:eNn(r)}},sessionId:null})`` | firstInput 正常功能 |
| W31b | 4,372,052 | ``sendCommand(fu({type:`createSession`,payload:{workspaceId:a},sessionId:null}),{projectId:e.projectId})``；随后 `u=t.result.sessionId; … sendCommand(fu({type:'startSavedWorkflow',…,sessionId:u}))`；失败 `deleteSession(u)` | **先建空会话再发后续命令**的正常路径 |
| W32 | 4,814,048 | `return ri(t.result.sessionId,e.clientContext?.sessionCreateSource,e.payload.firstInput?t.commandId:void 0,…)` | 崩溃重放 |
| W33 | 4,749,900 | `function Fkn(e,t){let n=e===`createSession`?t.firstInput:…` | firstInput 可带 mode/modelSelection |
| W34 | 2,387,440 | `function mL(e){let t=e.clientContext?.workspace…` / 2,387,703 `return mL(e)===(n?.trim()||t)` | workspaceId 语义 = key |
| W35 | 2,077,789 | `state:{workspaceId:e.workspaceId,logEpoch:e.logEpoch,seq:t.toSeq,sessions:n}` | sessions-index 只存不用 |
| W36 | 246,598 | `function Ale(e,t){…window.__zcodeTransportDelayE2E…}` | 无超时，仅 E2E 钩子 |
| W37 | validation 206,619 | `M([`build`,`edit`,`plan`,`yolo`,`guarded`])` | mode 含 plan |

### 7.2 Web 3.16.1（对照）

| # | 偏移 | 原文 | 用途 |
|---|---|---|---|
| O1 | 2,862,311 | `let e={workspaceId:t},n=a?.();return n&&Object.keys(n).length>0&&(e.config=n),e` | 预热 payload 同形 |
| O2 | 2,863,458 | `async waitForSession(e,t){` | 同形（含补发逻辑） |
| O3 | 2,864,120 | ``if(!r.binding)throw Error(`草稿会话创建失败，请重试`)`` | 同 |
| O4 | — | `sess_${crypto` 0 次命中 | 3.16.1 无 payload.sessionId |
| O5 | — | `genUiContextUnsupported` 0 次命中 | 3.16.1 无该门禁 |

### 7.3 Rust

| # | 文件 @ 偏移 | 原文 | 用途 |
|---|---|---|---|
| R1 | `conversation.rs` @3,267 | `pub const CONVERSATION_CLIENT_KIND: &str = "mobileApp";` | 客户端类型 |
| R2 | `conversation.rs` @3,388 | `pub const COMMAND_DEFAULT_TIMEOUT_SECS: u64 = 30;` | 30s |
| R3 | `conversation.rs` @3,775 | `pub const CREATE_SESSION_TIMEOUT_SECS: u64 = 90;` | 90s |
| R4 | `conversation.rs` @7,429 | `pub struct ClientHello { kind, protocolVersion, clientId, clientKind, appVersion }` | **无 capabilities** |
| R5 | `conversation.rs` @13,679 | `pub fn create_session_payload(workspace_id, first_text, attachments, config, runtime_model, mcp_servers)` | 无 sessionId 参数 |
| R6 | `conversation.rs` @15,001 | `pub fn send_text_payload(text, attachments, held_queue_disposition, expected_held_queue_item_ids, automation_id, off_peak_task_id, off_peak_run_type, bot_delivery_target, tool_disallowlist)` | 无 mode/planEnabled/modelSelection |
| R7 | `conversation.rs` @87,015 | `self.workspace_id = str_of(snap.get("workspaceId"))?;` | sessions-index 原样透传 |
| R8 | `conversation_transport.rs` @37,662 | `pub const OPS: &'static [&'static str] = &[ "handshake", "subscribe", …, "sendText", …, "createSession", … ]` | op 白名单（不含 web 的 3 个 RPC 名） |
| R9 | `conversation_transport.rs` @49,307 | `"subscribe" => { let session_id = req_str(op,args,"sessionId")?; … AwaitHandshake }` | 订阅独立、只要握手 |
| R10 | `conversation_transport.rs` @53,100 | `"sendText" => { sessionId, text, attachments, heldQueueDisposition, … }` | 只读 10 个键 |
| R11 | `conversation_transport.rs` @60,145 | `"createSession" => { workspaceId, firstText, attachments, config, runtimeModel, mcpServers … }` | 不读 sessionId |
| R12 | `conversation_transport.rs` @61,044 | `None => CREATE_SESSION_TIMEOUT_SECS as i64 * 1000,` | 默认 90s |
| R13 | `conversation_transport.rs` @74,210 | `fn enqueue_command(…) { … timeout_ms: COMMAND_DEFAULT_TIMEOUT_SECS as i64 * 1000 … }` | 命令默认 30s |
| R14 | `conversation_transport.rs` @151,947 | `fn opt_timeout_ms(…) { None => COMMAND_DEFAULT_TIMEOUT_SECS as i64 * 1000 }` | `sendCommand` 的 timeoutSecs |
| R15 | `conversation_transport.rs` @100,601 | `if self.bridge_degraded.is_some() && !flow.healthy_ok {` | 降级时先 waitHealthy |
| R16 | `conversation_transport.rs` @10,385 | `pub const WAIT_HEALTHY_TIMEOUT_MS: i64 = BRIDGE_HEALTHY_TIMEOUT_SECS as i64 * 1000;` | healthy 45s |
| R17 | `zemote_client.rs` @105,181 | `pub fn conversation_key(scope: &Payload) -> String { match scope.get("workspaceIdentity") { Some(v) if !v.is_null() => …, _ => path } }` | key = identity（**不 trim / 空串不回落**）?: path |
| R18 | `ffi/src/codec.rs` @37,195 | `"conversation_call" => CallCommand::ConversationCall { session, key, op, args }` | FFI 信封 |
| R19 | `ffi/src/conversation.rs` @13,913 / @15,751 | `pub fn open(…)` / `pub fn call(…)` | `putIfAbsent` + 未知 key → invalidState |

### 7.4 Kotlin（钉 `ChatViewModel.kt` sha256 `733aa076…`）

> 该文件在审计期间被并发重写了 3 次（4aebe5fe → 5a3cd43a → 733aa076）；
> 下表是 **733aa076** 时刻的快照，**offset 会随编辑漂移，`needle` 不会**。
> 其余 Kotlin 文件在本次审计中未变（sha 见各行）。

| # | 偏移 / 行 | 原文（needle） | 用途 | sha256 前 12 |
|---|---|---|---|---|
| K1 | 28,599 / 684 | `private fun createConversation(firstText: String) {` | 新建对话入口 | 733aa0761366 |
| K2 | 28,845 / 687 | `val workspaceId = _state.value.selectedWorkspace?.key ?: _state.value.selectedWorkspaceKey ?: _state.value.workspaceId` | workspaceId 取值链 | 733aa0761366 |
| K3 | 29,621 / 705 | `"createSession",` + 29,676/706 `put("workspaceId", workspaceId)` | 当前只传 workspaceId | 733aa0761366 |
| K4 | 30,792 / 733 | `subscribeConversation(newId)` | 先订阅 | 733aa0761366 |
| K5 | 31,258 / 743 | `put(ChatOps.ARG_TEXT, firstText)` | 再 sendText（无模型字段） | 733aa0761366 |
| K6 | 32,425 / 772 | `private suspend fun subscribeConversation` | 走 `ensureRouteBridge` 的 handle | 733aa0761366 |
| K7 | 33,490 / 792 | `private suspend fun ensureRouteBridge(taskId: String?): ConversationRegistry.Handle? {` | 所有建桥收敛点 | 733aa0761366 |
| K8 | 56,199 / 1355 | `val workspaceId = indexes?.firstOrNull()…get("workspaceId")` | sessions-index → state | 733aa0761366 |
| K9 | 70,893 / 1661 | `sessionsIndexUpdated`（现改为 `refreshConversationState()`） | 增量更新 | 733aa0761366 |
| K10 | 76,491 / 1800 | `key = identity ?: path,` | `WorkspaceItem.key`（trim 后身份） | 733aa0761366 |
| K11 | ChatOps 5,574 / 122 | `private suspend fun ensureConversation(scope: JsonObject): ConversationRef = mutex.withLock {` | 取注册表句柄 | 220e32a858c4 |
| K12 | ChatOps 6,111 / 128 | `val handle = registry.handleFor(scopeKey) ?: throw EngineException(NativeError("invalidState", "工作区还在连接，请稍候再发送"),)` | 不自己开桥 | 220e32a858c4 |
| K13 | ChatOps 7,075 / 151 | `private fun conversationKeyOf(scope: JsonObject): String? = identity ?: path`（**不 trim/不判空**） | 与 registry key 不一致的根源 | 220e32a858c4 |
| K14 | Registry 3,088 / 64 | `suspend fun ensure(` | 幂等建句柄 | f6dc60bc4d48 |
| K15 | Registry 4,311 / 92 | `"subscribeSessionsIndex",`（`engine.conversationCall(...)` **等 ack**） | 串行化握手 | f6dc60bc4d48 |
| K16 | ZemoteEngine 32,161 / 699 | `private fun hostTimeoutMs(command: JsonObject): Long {` | 宿主兜底 | 7448870e2fdc |
| K17 | ZemoteEngine 32,521 / 706 | `private fun conversationTimeoutSecs(command: JsonObject): Long? {` | 只认 conversation_call 的 args.timeoutSecs | 7448870e2fdc |
| K18 | ZemoteEngine 1,437 / 36 | `private const val DEFAULT_OPERATION_TIMEOUT_MS = 30_000L` | 30s | 7448870e2fdc |
| K19 | ZemoteEngine 1,906 / 45 | `private const val HOST_TIMEOUT_GRACE_MS = 5_000L` | +5s | 7448870e2fdc |
| K20 | AppContainer 6,958 / 155 | `val chatOps: ChatOps by lazy {` + 7,371/162 `registry = conversationRegistry,` | registry 已接线 | 28455ee7f01d |
| K21 | ChatScreen 10,549 / 251 | `onSend = { … onSend(text); input = "" }` | **乐观清空** | e8890622f514 |
| K22 | ChatScreen 7,132 / 159 | `LaunchedEffect(state.draftRestore) { … input = restore; onDraftRestored() }` | 失败回填 | e8890622f514 |

---

## 8. 可落地的修改清单

按「必须 / 建议 / 可选」分级。每条都给了验收判据。

### 必须

**M1 · `sendText` 支持 mode / planEnabled / modelSelection**
- Rust：`send_text_payload` 增 3 个 `Option` 参数，非空才写；`"sendText"` 分支读取
  （`opt_str("mode")`、`opt_bool("planEnabled")`、`opt_obj("modelSelection")`）。
- Android：`ChatViewModel` 从 `state.config` 取 `modelSelection`（对象）、`mode`；
  `plan` → `mode="build" + planEnabled=true`（与 web `qM` 一致）。
- 验收：relay 帧里的 `sendConversationCommandV4.envelope.payload` 含三键；
  新建会话的首条消息能命中用户选的模型（而非工作区默认）。
- 注意：`planEnabled=true` 受 §2.3 的 `independentPlanUnsupported` 语义影响；第一版建议只透传
  `mode + modelSelection`，`planEnabled` 走 feature flag，并处理被拒 reasonCode 的降级重试。

**M2 · 修宿主对 `conversation_call` 的兜底超时（并按需传 `timeoutSecs`）**
- Android：`chatOps.call("createSession", {workspaceId, timeoutSecs: 90}, scope)`（把意图写在请求里）。
- 宿主：`hostTimeoutMs` 对 `method=="conversation_call"` 额外加 `BRIDGE_HEALTHY_TIMEOUT_SECS(45s)`，
  即 `declared + 45s + 5s`；否则降级场景核心必然比宿主晚 40s 结束。
- 理由：核心默认 90s，宿主默认 35s；仅靠调用点传 `timeoutSecs` 无法覆盖 healthy 的 45s
  （宿主只加 5s，见 §4.3）。
- 验收：注入 40s 的 createSession 延迟（或 mock），App 不再在 35s 报超时；90s 内成功的用例能正常进会话；
  桥降级 + 60s 成功 的用例也能进会话。

**M3 · key 归一化统一**
- 抽一个 `workspaceKeyOf(path, identity) = identity?.trim()?.takeIf{isNotEmpty()} ?: path`，
  Android 的 `conversationKeyOf`、`WorkspaceItem.key`、registry map key 全部用它。
- Rust `conversation_key` 同样加 `trim()` + 空串回落（当前原样 identity，空串会变 conversation key `""`）。
- 验收：identity 为 `"  remote:ssh:x  "`、`""`、`null` 三种输入下，注册表 key、ChatOps 查找 key、
  Rust conversation key 三者一致；单测覆盖。

### 建议

**S1 · `state.workspaceId` 去掉或按会话打标**
- 现状：sessions-index 的 `workspaceId` 只作第三兜底，但跨工作区不清空。
- 建议：切换工作区时置 null；或让 `workspaceId` 只用于展示，`createConversation` 只用 key。
- 验收：从工作区 A 切到 B 后立刻新对话，`createSession.payload.workspaceId` 必须是 B 的 key。

**S2 · 超时后对账，而不是直接报失败**
- 宿主 timeout 后调一次 `queryCommands`（web 的 `yL.reconcileSession` 等价物）或 `sessionsIndex`
  刷新，确认桌面是否已建会话；已建则直接采用，避免孤儿会话。
- 验收：人为把 createSession 延迟到 40s，App 最终能进入该会话而不是报错。

**S3 · `subscribe` 等 ack**
- 现在 `subscribeConversation` 用 `conversationCallNoWait`；Dart 参考明确说 send-before-subscribe
  可能丢首条命令。可在 createConversation 里用 `conversationCall`（等 ack 的合成 completed）。
- 验收：冷启动后立即发送首条，消息行稳定出现（连续 20 次不丢）。

### 可选

**O1 · 复刻草稿预热**（见 §1.4）。收益是「首条消息只依赖 sendText」，失败面更小；成本是
`deleteSession` 回收与 promote 语义。若做，务必按 §1.4 的 4 条。

**O2 · `ClientHello` 补 `capabilities`**（`workspaceHookReviewUi:true` 等），与 web 对齐；
但 `clientKind:'mobileApp'` 是否在白名单内 UNVERIFIED，改前先确认。

---

## 9. UNVERIFIED 清单

1. **桌面端对 `sendText` 缺 `mode/modelSelection` 的反应**：拒绝 or 用默认模型。快照内无 command payload schema，
   `validation` 里只有 queue 行 schema（字段 optional）。→ 需真机日志或桌面侧错误码。
2. **`payload.sessionId = sess_<uuid>` 是否被桌面采纳**为 `result.sessionId`。web 只读 `result.sessionId`，无从判断。
3. **桌面 3.17.1 对 `createSession + firstInput` 的处理**：快照只有 web 侧；但"保存工作流提升"是正常功能，
   强旁证可用。
4. **sessions-index `workspaceId` 的确切取值**：Rust 原样透传，web 只存不用；不能假设等于 path。
5. **hello 应答的 zod schema 是否 `.strict()`**（Round1 遗留）：若严格，Rust/对端多返回任何未知键都会让握手失败，
   表现为"一条命令都没发出去"。
6. **`helloConversationV4` 单连接语义的确切条件**（Round1 遗留）：Android 的 `ConversationRegistry` 注释
   断言"多个工作区并发握手只回一个"，快照无法证实；但"同一连接只握手一次"的收敛策略无论如何是安全的。
7. **`clientKind:'mobileApp'` 是否被 3.17.1 服务端接受**。web 发的是 `t.clientMode==='desktop-continuous'?'desktop':'web'`
   （@248,048 区），Rust 固定 `'mobileApp'`；服务端白名单未知。

---

## 10. 审计结束时的最新状态（可变，勿当结论）

审计收尾时 Kotlin 侧仍在被并行工作流改写（最后一次观测：`ChatViewModel.kt` sha `c8f94d0a…`、
`ConversationRegistry.kt` sha `4ed48247…`）。与本文相关的三个变化：

1. **`createSession` 现在带超时**（`ChatViewModel` @30,176 区）：
   ```kotlin
   val handle = ensureRouteBridge(taskId = null)
   // createSession 的 workspaceId 用 bridge-ready 的权威 key（identity 归一化后
   // 可能与路由携带的 key 不同；用旧值会建到错误工作区）。
   val targetWorkspaceId = handle?.effectiveWorkspaceKey ?: workspaceId
   chatOps.call("createSession", buildJsonObject {
       put("workspaceId", targetWorkspaceId)
       // 核心默认 90s；宿主只按 args.timeoutSecs 兜底，不传会在 35s 假超时。
       put("timeoutSecs", 90)
   }, conversationScope())
   ```
   → 本文 **M2 的调用点部分已落地**：宿主上限从 35s 变成 95s，核心命令仍是 90s。
   **但 §4.3 的缺口仍存在**：桥降级时 Rust 还要先 `waitHealthy`（45s），最坏 135s > 宿主 95s，
   仍会假超时。**M2 的宿主侧改动（把 45s healthy 计入兜底）仍未做。**

2. **`workspaceId` 改为取注册表句柄的权威 key**：`handle?.effectiveWorkspaceKey ?: workspaceId`。
   `ConversationRegistry.Handle` 新增 `effectiveWorkspaceKey`（默认 = `workspaceKey`，
   `ensure` 里用 `effectiveWorkspaceKey(bridge)` 解析后回填）。→ 方向正确（本文 M3）。
   注：若 `ChatOps.conversationKeyOf` 仍是"原样 identity"，§5.4 残留缺陷 #2 仍需单独修——两者不是同一处。

3. 截至收尾，**未观察到** `sendText` 携带 `mode/modelSelection`（`modelSelection` 只出现在 config 解析处），
   即 **M1 仍未落地**；`planEnabled` 在 Android 侧 0 处出现。

> 结论：本文的**规格**（§0/§8）仍然有效，**Kotlin 现状**以本节为准。复核时先 `git diff`
> 看 `ChatViewModel.kt` / `ConversationRegistry.kt` / `ChatOps.kt` 的最新形态，
> 再核对 M1 / M2-host / M3 三项是否已完成。
