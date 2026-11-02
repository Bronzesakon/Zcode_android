# 02 · 线协议字段级核对（Round3）

范围：本轮新增/复用的四个字段组在「Kotlin JSON → FFI codec → 核心 → 线上 payload → 3.17.1 桌面校验」链路上是否逐字保留。

- `open_bridge`：`taskHostId` / `project`
- `sendText`：`mode` / `planEnabled` / `modelSelection`
- `createSession`：payload 形状 + `timeoutSecs`
- conversation 的 effective key（`bridge-ready.bridge.workspaceKey` → `conversation_open` scope）

取证方式：只读源码 + 只读 asar（`node tools/asar.mjs dump/grep`，不执行其中代码）+ web 快照按字节偏移切片。
下文 `asar:` 指 `C:\Users\wdn32\AppData\Local\Programs\ZCode\resources\app.asar` 内的 `<路径>@<字节偏移>`；`web:` 指 `Reference_web/3.17.1/assets/index-Dwhx77cK.js@<字节偏移>`。

---

## 0. 结论速览

| # | 问题 | 结论 |
|---|---|---|
| 1 | `taskHostId`/`project` 是否逐字保留；`taskId:null` 是否被误当存在 | **确认正确**。三处都不改写；`null` 在 codec 变 `None`、线上**键不出现**（这一步是必须的，桌面 `taskId` 是 `optional` 不是 `nullable`） |
| 2 | `project` + `taskId` 同时给是否可能由 ChatViewModel 产生 | **不可能**。两条路径用 `if (taskId == null)` 三目互斥；`taskHostId` 也只在 `taskId != null` 时给 |
| 3 | `sendText` 三键是否原样进 payload；`modelSelection` 结构是否与 web 一致；桌面缺键行为 | **字段正确**（嵌套结构逐字一致；桌面三键全 `.optional()`，缺失不报错）。但 mode 无枚举校验、新会话无条件发 `build`（见 D4/D5） |
| 4 | 现有 `createSession` 形状是否被接受；`timeoutSecs` 是否被读；是否要补 `sessionId` | **被接受**。`timeoutSecs` 被 Rust 读成毫秒、**不进 payload**；`sessionId` 桌面 host 侧兜底生成，**不必补** |
| 5 | bridge-ready 四键在 Rust 侧如何处理；snapshot 是否够；`initialTaskId` 是否覆盖 view state | **部分正确**。Rust 只用 `workspaceKey`/`initialTaskId` 发 view state（与 Dart 同）；snapshot 够拿 key、**不够**拿 path/identity（D2）；`initialTaskId` 是桌面回显的请求值，但 **Kotlin 随后用请求值重发 view state，会覆盖权威值**（D1） |
| 6 | `channel_call` 的 args 形状 | **确认正确**：位置参数数组，`readTree`=`[obj]`、`readDirectory`=`[]`，与 web 调用形状一致 |

---

## 1. 确认正确

### 1.1 `open_bridge`：`taskHostId` / `project` 逐字到线上（Q1）

逐层证据：

| 层 | 位置 | 行为 |
|---|---|---|
| Kotlin 命令构造 | `android/app/src/main/kotlin/app/zemote/engine/ZemoteEngine.kt:292-320` | `put("taskId", taskId?.let{JsonPrimitive(it)} ?: JsonNull)`；`taskHostId?.let{put("taskHostId", it)}`；`project?.let{put("project", it)}` |
| FFI codec | `rust/crates/ffi/src/codec.rs:941-952` | `task_host_id: optional_string(map,"taskHostId")`；`project: optional_object(map,"project")`，空对象→`None` |
| FFI 分发 | `rust/crates/ffi/src/session.rs:260-279` | 原样传 `task_id/task_host_id/project` 给 `open_bridge_with_intent` |
| 核心构造 | `rust/crates/protocol/src/zemote_client.rs:1376-1420` | 只透传，不做归一化 |
| 线上 payload | `rust/crates/protocol/src/zemote_client.rs:2458-2490` | `payload.insert("taskHostId", …)`（2477）、`payload.insert("project", Value::Object(project.clone()))`（2479-2481） |

键名与桌面读取端一致：桌面 `createWorkspaceBridge` 直接读 `b.project` / `b.taskHostId` / `b.taskId`
（`asar: out/main/index.js@423578`，函数体 `…b.project??((b.fileTreeTaskId||b.taskId)&&b.taskHostId?{hostId:b.taskHostId,task:{taskId:…,workspaceKey:b.workspaceKey}}:void 0)` 在 @423635）。

类型与出现条件也一致（桌面 schema，`asar: out/main/chunk-ZP2RJMJD.js@731279` 起的 `SN` 联合）：

```
workspace-bridge-open: {
  …, workspaceKey: b, taskId: b.optional(), taskHostId: b.optional(), fileTreeTaskId: b.optional(),
  project: object({projectId: b.nullable(), hostId: b.optional(),
                  draftId: uuid.optional(), reuseDraft: bool.optional()}).strict()
           .refine((!draftId||projectId===null)&&(!reuseDraft||!!draftId), …).optional()
}.refine((!project||!taskId&&!fileTreeTaskId) && !(taskId&&fileTreeTaskId)
         && (!fileTreeTaskId||!!taskHostId) && (!taskHostId||!!(taskId||fileTreeTaskId)),
         "Project draft cannot target an existing task")        // @731998
```

其中 `b = string().trim().min(1)`（`asar: chunk-ZP2RJMJD.js@726493`）。

**`taskId: null` 不会被误当存在**，而且这一步是必需而非无害：

- Kotlin 总是写 `"taskId": null`（键存在）→ codec `optional_string` 把 `None | Null` 都判成 `None`（`codec.rs:1034-1041`）→ `open_bridge_request` 只在 `Some` 时插键（`zemote_client.rs:2470-2472`）。
- 单测断言 `!without_task.contains_key("taskId")`（`zemote_client.rs:2906-2916`）。
- 桌面端 `taskId` 是 `b.optional()`，而 `b` 是 `string().trim().min(1)` —— **zod 的 `.optional()` 不接受 `null`**。若 Rust 把 `null` 原样上线，`workspace-bridge-open` 会整帧校验失败，桥永远打不开。当前实现规避了这个致命点。

### 1.2 `project` 与 `taskId` 不可能同时由 ChatViewModel 产生（Q2）

两条建桥路径都写死了互斥（`ChatViewModel.kt:825-834` 的 `ensureRouteBridge`、`:1084-1120` 的 `openWorkspaceInternal`）：

```kotlin
taskHostId = if (taskId != null) initialHostId else null,
project    = if (taskId == null) projectIntent() else null,
```

- `projectIntent()`（`ChatViewModel.kt:837-843`）只在 `initialProjectId != null` 时非空，且只写 `projectId` + 可选 `hostId`（**没有** `draftId`/`reuseDraft`，因此 `.strict()` 与 `draftId` 的 uuid 约束都不触碰）。
- 其它调用点：`HomeViewModel.kt:467-496` 传 `taskId = null` + 可选 `project`；`SettingsSectionViewModel.kt:214`、`ChatOps` 的 `registry.handleFor` 都只取现成句柄；`ConversationRegistry.ensure`（`ConversationRegistry.kt:75-118`）本身不校验，只按调用方传参透传。

结论：在当前代码里「project + taskId」既不会同现，`taskHostId` 也必然伴随 `taskId`（满足桌面两条 refine）。

### 1.3 `sendText` 三键原样进 payload，`modelSelection` 嵌套结构与 web 逐字一致（Q3）

链路：

| 层 | 位置 | 行为 |
|---|---|---|
| Kotlin | `ChatViewModel.kt:851-878` `putSendSubmission()` | `mode`（`plan`→`build`）、`planEnabled`（bool）、`modelSelection={providerId, modelId, options?:{reasoningLevel}}`；`provider/model` 空白则**整块不发**；`thought` 空白则不写 `options` |
| FFI codec | `codec.rs:973-978` | `args` 作为对象原样交给对话层（未知键不报错也不转发） |
| 核心 | `conversation_transport.rs:1335-1367` | `opt_str("mode")` / `opt_bool("planEnabled")` / `opt_obj("modelSelection")`，三者都是 `None|Null→None`、类型不符→`Other` 错误（`3706-3736`） |
| payload | `conversation.rs:358-395`（`mode`@376、`planEnabled`@379、`modelSelection`@383） | 原样 insert；`modelSelection` 仅在非空对象时写 |

嵌套结构对齐（web 提交形状，`web: index-Dwhx77cK.js@1994988` 区，`qM`）：

```js
Object.freeze({mode: r.data===`plan`?`build`:r.data,
               planEnabled: Ii(e).planEnabled,
               modelSelection: Object.freeze({providerId, modelId,
                                              options: Object.freeze({reasoningLevel})})})
```

桌面侧 schema（`asar: out/main/chunk-S55WGVKG.js@34482` 起的 `Go.sendText`）与此完全一致：

```
sendText: object({ text, …,
  modelSelection: I.optional(),      // @32741
  mode: Y.optional(),                // 枚举 Y（@32935 附近 planEnabled 同一对象）
  planEnabled: boolean().optional(), // @32935
  … }).superRefine(只校验 modelExecution→modelSelection、automationId×offPeakTaskId)
```

解析链（两跳别名，已逐跳核对导出表）：

- `I` → `P` → 本地 `K`：`object({providerId: string().trim().min(1), modelId: string().trim().min(1), options: object({reasoningLevel: string().trim().min(1).optional()}).strict().optional()}).strict()`（`asar: chunk-ZP2RJMJD.js@502792`，导出表 `K as P`）。
- `Y` → `gb` → 本地 `Zx`：`enum(["build","edit","plan","yolo","guarded"])`（`asar: chunk-ZP2RJMJD.js@582908`，导出表 `Zx as gb`）。

**桌面端对缺失三键的真实行为：接受，不报错、不填默认值。** 依据：三键均为 `.optional()`，`superRefine` 里没有任何「mode/modelSelection 必填」校验（仅 `modelExecution` 需要 `modelSelection`，而 Android 从不发 `modelExecution`）。因此三键缺失时由服务端按工作区默认模型/模式处理。这也是 round2 §2.2 只能给出「旁证」的那条结论的**直接证据**（此前只有 QueueItem 行 schema 的旁证）。

### 1.4 `createSession`：现有形状被接受，`timeoutSecs` 被读但不入 payload（Q4）

- Rust payload 只有 `{workspaceId, firstInput?, config?, runtimeModel?, mcpServers?}`（`conversation.rs:319-353`）。Android 实际只发 `{workspaceId, timeoutSecs}`（`ChatViewModel.kt:729-739`）；`firstInput` 缺失合法（`conversation.rs:330` 才写）。
- 桌面 schema：`createSession: object({ sessionId: string().min(1).optional(),  // @32537
  additionalDirectories?, workspaceId: string, permissionScope?, firstInput?: object({text, attachments?, modelSelection?, mode?, planEnabled?}), config?: Xt, mcpServers?, offPeakToolEnabled?, dynamicWorkflowEnabled?, dynamicWorkflowMode? })`
  —— `workspaceId` 必填、其余全可选，**没有** `timeoutSecs` 这个键（`asar: out/main/chunk-S55WGVKG.js@33796`）。
- `timeoutSecs` 是 **op 参数**而非 payload：`conversation_transport.rs:1506-1509` `opt_i64(op,args,"timeoutSecs")? * 1000`，缺省 `CREATE_SESSION_TIMEOUT_SECS*1000`（90s）。所以把它放进 payload 反而会在 `.strict()` 之外的宽松对象里被静默丢弃（该对象非 strict，未知键被剥掉，不会报错）。
- 信封 `sessionId` 为 `null` 但**键存在**（`conversation.rs:196-198`），桌面 `Eh.sessionId: string().nullable()` 接受。

**是否要补 `payload.sessionId: sess_<uuid>`：不必。** 桌面 host 的信封构造器对 `createSession` 显式兜底：

```js
// asar: out/host/index.js@610199
let t = e.type === "createSession" ? {...e.payload, sessionId: e.payload.sessionId ?? `sess_${randomUUID()}`} : e.payload;
```

即「不补」的结果是 id 由 host 生成而非客户端预分配，字段语义没有丢失；Android 本来就只读 `result.sessionId`（`conversation.rs:672-700`），不回读自造值。所以不补**没有**可观测影响，除非将来要和 web 的逐字 payload 对齐。

### 1.5 `channel_call` 的 args 是位置参数数组（Q6，复确认）

- codec：`args: optional_value_array` → `Vec<Value>`，缺省/`null`→`[]`，非数组→`badInput`（`codec.rs:988-996`、`1060-1066`）。
- 通道层：`send_call` 用 `Value::Array(args.to_vec())` 作为帧的 arg（`channel_client.rs:525-534`）；`ipc_codec::Value` 有独立 `Array` 变体（tag 4），`serde_json::Value → Object`、`Vec<Value> → Array`，不会把数组降级成对象（`ipc_codec.rs:182-200`、`250-267`）。
- Kotlin：`ZemoteEngine.channelCall(..., args: JsonArray)` 直接 `put("args", args)`（`ZemoteEngine.kt:528-544`）。
- 两个具体调用：
  - `project.readTree` → `JsonArray(listOf({includeDeleted:false, includeDeletedTasks:true, hostId?}))`（`HomeViewModel.kt:568-584`）。web 同形状：`projectService.readTree({includeDeleted:!0,includeDeletedTasks:!0})`（`web: index-Dwhx77cK.js@1982053`）——**位置形状一致，取值 `includeDeleted` 不同（见备注）**。
  - `remote-projects.readDirectory` → `JsonArray(emptyList())`（`HomeViewModel.kt:588-596`）。web 也是零参：`remoteProjectService?.readDirectory()`（`web: index-Dwhx77cK.js@1282934`）。

备注（非缺陷，取值差异）：web 拉全量项目树时用 `includeDeleted:true`，Android 用 `false` 并自行跳过 `deleted` 项目（`HomeViewModel.kt:630`）。这只影响「已删除项目/任务是否出现在列表」，不影响协议形状。

---

## 2. 有缺陷

### D1（中）开桥后 Kotlin 用**请求值**重发 mobile view state，覆盖 Rust 用**权威值**发的那一帧

- 现象：Rust 在 bridge-ready 时按 Dart 同式发送 view state：
  `effective_key = bridge.workspaceKey ?? workspaceKey`、`effective_task = bridge.initialTaskId ?? task_id`（`zemote_client.rs:2152-2170`）。
  随后 Kotlin 无条件再发一次，用的是**请求值**：`engine.sendMobileViewState(workspaceKey, taskId)`（`ChatViewModel.kt:1108` 注册表路径、`:1117` 兜底路径）。后发的那帧覆盖前者。
- 证据：桌面 `createWorkspaceBridge` 组装 bridge 时 `workspaceKey = ot(准备后的 workspace)`、`initialTaskId = b.taskId`（`asar: out/main/index.js@423578`、`@425372`）；项目导航（`prepareProjectWorkspace`）会把 workspacePath/identity 归一化成与请求 key 不同的值，这正是 `ConversationRegistry.effectiveWorkspaceKey`（`ConversationRegistry.kt:136-153`）存在的原因。
- 影响范围：仅「bridge-ready 的 workspaceKey 与请求 key 不同」的场景（项目草稿 prepare、远端 identity 归一化）。此时桌面 `mobileViewState.activeWorkspaceKey` 被写回请求 key，桌面按 key 找不到工作区 → 已读/焦点/当前任务定位失效；`initialTaskId` 与请求 taskId 不同时（目前桌面只回显，出现概率低）也会被清掉。
- 最小修复：在 `ChatViewModel.openWorkspaceInternal` 里用权威值发送——
  `val key = handle?.effectiveWorkspaceKey ?: workspaceKey`；
  taskId 取 `snapshot.bridges[session].initialTaskId ?: taskId`（或在刚 `openBridge` 的分支直接跳过这次调用，Rust 已发过）。

### D2（低-中）snapshot 的 `bridges` 投影缺 `workspacePath`/`workspaceIdentity`，Kotlin 只能字符串反推 scope

- 现象：`session.rs:167-182` 只投影 `session/wireBridgeSessionId/workspaceKey/initialTaskId/degraded/recovered/disposed/conversationKeys`；`bridge-ready.bridge` 里的 `workspacePath`、`workspaceIdentity`（远端专属）被留在 `BridgeSession.bridge` 里但**不外露**。
- 后果：`ConversationRegistry.effectiveScope`（`:155-166`）用「`remote:` 前缀 + 第一个 `/` 切分」反推路径，identity 格式一变就会把错误路径当 cwd 传给 `conversation_open`（`workspacePath` 是对端 agent CLI 的 spawn cwd，见 `ChatViewModel.kt:887` 注释）。
- 最小修复：snapshot 增补 `"workspacePath": bridge.workspace_path(), "workspaceIdentity": bridge.workspace_identity()`（两个 accessor 各 3 行），Kotlin 直接用；字符串反推降级为兜底。
- 影响范围：远端工作区 & 项目草稿；本地工作区 key==path，不受影响。

### D3（低）Rust 不校验 `project` 与 `taskId` 互斥，也不校验 `taskHostId` 必须伴随 `taskId`

- 现象：`codec.rs:941-952` 逐字段独立解析，没有组合校验；`open_bridge_request` 也会把两者都写上线（`zemote_client.rs:2470-2481`）。
- 后果：今天不会触发（Kotlin 互斥），但宿主/未来调用方一旦组合，线上帧会被桌面 zod **整帧拒**（`"Project draft cannot target an existing task"`，`asar@731998`），错误以「桥永远不 ready / 30s 超时」的形式出现，而不是本地 `badInput`，排查成本高。
- 最小修复：`parse_call_command` 的 `open_bridge` 分支加两条 guard → `BadInput`：
  `if project.is_some() && task_id.is_some()`；`if task_host_id.is_some() && task_id.is_none()`。
- 影响范围：防御性；不影响现有调用。

### D4（低）`mode` 未按桌面枚举校验，非法值会让整条 `sendText` 被拒

- 现象：`ChatViewModel.kt:853-860` 只把 `plan` 归一化成 `build`，其它字符串（含空串）原样 `put("mode", rawMode)`；Rust 侧 `opt_str` 也只校验类型（`conversation_transport.rs:3696-3704`）。
- 桌面契约：`sendText.mode` 是 5 值枚举 `["build","edit","plan","yolo","guarded"]`（`asar: chunk-ZP2RJMJD.js@582908`）。`config.mode` 若出现 `"auto"`/`""` 等值，整条命令 zod 失败 → `sendText` 被拒（用户看到的是发送失败，重试也失败）。
- 最小修复：Kotlin 侧 `rawMode.takeIf { it in setOf("build","edit","plan","yolo","guarded") }`，否则回落 `"build"`；Rust 侧可选加同样白名单，非法时返回 `badInput` 更早暴露。
- 影响范围：仅当服务端回传非枚举 mode 时；概率低，但失败是「整条命令」，代价高。

### D5（低）新会话 `config` 未到时无条件发 `mode="build" + planEnabled=false`，与 web 语义不同

- 现象：`putSendSubmission` 在 `config == null` 时仍写 `mode:"build"`、`planEnabled:false`（`ChatViewModel.kt:855-860`）。
- web 语义：composer 的提交配置在 `mode` 未定义时**整块不返回**（`if (!N.current.mode) return;`，`web: index-Dwhx77cK.js@2867048` 的 `M` memo + `ae` 回调），线上不带三键，交给服务端用工作区默认。
- 后果：若工作区默认模式是 plan，首条消息会被强制成 build（`planEnabled=false`），正是该函数注释想避免的情况。
- 最小修复：`config == null` 时不写 `mode`/`planEnabled`（只保留可用的 `modelSelection`）；或先确保 `refreshConversationState()` 成功再发（当前顺序已在发送前调用，见 `ChatViewModel.kt:766-768`，但失败时无兜底）。
- 影响范围：新会话首条消息；已观察到 config 的会话不受影响。

### D6（低，测试缺口）三处新字段没有最小检查

- `codec.rs` 的命令解析测试没有任何 `taskHostId`/`project` 用例（`2041-2261` 里 `project: None` 是全部覆盖），`open_bridge_request_payload_shape` 覆盖了核心层（`zemote_client.rs:2906-2950`）但没覆盖 FFI 解析。
- `conversation.rs` 没有 `send_text_payload` 的单测（全文只有定义）。
- `conversation_transport.rs` 没有 `sendText` 带 `mode/planEnabled/modelSelection` 的透传断言（现有 `sendText` 测试都只给 `sessionId/text`）。
- 影响：本轮最容易回归的三处正是这三处；将来有人「顺手清理未知键」时不会被测试拦住。
- 最小修复：各加一条断言（沿用现有 `FakeChannels`/`Harness`）：codec 解析 `{…,"taskHostId":"h","project":{"projectId":"p"}}`；`send_text_payload` 三键出现/为空时不出现；`op()` 发 `sendText` 后检查 `channels` 里 `sendConversationCommandV4` 的 payload 三键。

### D7（低，能力门禁缺失）`planEnabled` 未按 `independentPlanState` 能力裁剪

- web 在 `sendCommand` 里硬门禁：`if (capabilities.independentPlanState !== !0 && (payload.planEnabled || …)) throw Error('proto.independentPlanUnsupported')`（`web: index-Dwhx77cK.js@272437`）。Rust 全文没有 `independentPlanState`/`capabilities`（grep 0 命中），Kotlin 也无条件发 `planEnabled`。
- 后果：对不声明该能力的宿主，Android 仍会带 `planEnabled:true`；桌面 schema 不拦，服务端如何处理**未取证**（UNVERIFIED U4）。
- 最小修复：握手应答里存 `capabilities.independentPlanState`，为 false 时 `putSendSubmission` 不写 `planEnabled`（与之配套的 `mode` 归一化逻辑保持不变）。

---

## 3. UNVERIFIED

1. **桌面收到 zod 校验失败帧后的具体表现**：能确定「schema 会拒」（refine 文本 @731998、枚举/严格对象），但没取到「拒绝后回 `workspace-bridge-error` 还是静默丢帧」的分支代码。影响 D3/D4 的用户可见形态描述。
2. **`createSession` 缺 `payload.sessionId` 时服务端最终 session id 的来源**：只取证到 host 信封构造器 `ci()` 兜底生成 `sess_<uuid>`（`asar: out/host/index.js@610199`）；服务端是否采用该值、`result.sessionId` 是否可能与之不同，未取证。不影响 Android（只读 `result.sessionId`）。
3. **`remote:` identity 的精确格式**：`ConversationRegistry.effectiveScope` 的字符串手术（`startsWith("remote:")` + 第一个 `/`）依据是 Android 侧注释与 round2 结论，未在 3.17.1 桌面端逐字取到 `ot()`（workspace key 派生）实现。
4. **3.17.1 桌面是否恒声明 `independentPlanState: true`**：未取证；决定 D7 的实际风险为「零」还是「存在」。
5. **`project` 里带 `draftId`/`reuseDraft` 的真实服务端语义**：schema 与 refine 已取证（`draftId` 必须 uuid、`reuseDraft` 需 `draftId`、`draftId` 需 `projectId===null`），但 Android 当前不发，未验证「本地未归类草稿」路径。

---

## 附：证据索引

Rust：

- `rust/crates/ffi/src/codec.rs:811-825, 941-952, 973-978, 984-996, 1034-1066`
- `rust/crates/ffi/src/session.rs:167-182, 260-279, 345-357`
- `rust/crates/protocol/src/zemote_client.rs:1376-1420, 2101-2190, 2458-2490, 2569-2574, 2906-2950`
- `rust/crates/protocol/src/conversation.rs:189-213, 319-353, 358-395, 672-700`
- `rust/crates/protocol/src/conversation_transport.rs:945-985, 1114-1133, 1335-1367, 1491-1521, 3696-3736`
- `rust/crates/protocol/src/channel_client.rs:392-406, 525-534`
- `rust/crates/protocol/src/ipc_codec.rs:182-200, 250-267`

Android：

- `app/zemote/engine/ZemoteEngine.kt:292-320, 344-350, 528-544`
- `app/zemote/ui/chat/ChatViewModel.kt:729-739, 825-843, 851-878, 887-910, 1084-1120`
- `app/zemote/ui/chat/ConversationRegistry.kt:75-118, 136-166`
- `app/zemote/ui/chat/ChatOps.kt:120-175`
- `app/zemote/ui/home/HomeViewModel.kt:467-496, 568-604`
- `app/zemote/ui/EnginePort.kt:119-135, 180-195, 389-410`

桌面 asar：

- `out/main/chunk-ZP2RJMJD.js@726493`（`b`）、`@731279`（`workspace-bridge-open`）、`@731492/@731544`（`taskHostId`/`project`）、`@731998`（互斥 refine）、`@727408`（bridge-ready 的 `kind` 判别联合）、`@732809/@728090`（mobile-view-state-update）、`@502792`（modelSelection）、`@582908`（mode 枚举）
- `out/main/chunk-S55WGVKG.js@33796`（createSession schema）、`@34482`（sendText schema）、`@32537/@32741/@32935`
- `out/main/index.js@423578/@423635/@423863/@425372`（createWorkspaceBridge）
- `out/host/index.js@610199`（createSession 信封兜底 `sess_<uuid>`）

Web 快照：

- `index-Dwhx77cK.js@1994988`（提交形状 `qM`）、`@272437`（`independentPlanUnsupported` 门禁）、`@2867048`（composer `mode` 未定义即整块不发）、`@1982053`（`readTree` 调用）、`@1282934`（`readDirectory()` 零参）
