# 02 · 最终线上 payload 推导（端到端，只读）

> 只读审计，未修改任何源码。本报告回答「实际会发到线上的 JSON 长什么样」，逐字段给出取值/缺省/类型。
> 对照基准：`round2/01`（项目导航）、`round2/02`（新建对话）、`round3/01`（端到端实现）、`round3/02`（wire 字段）。
>
> 审计时读取的文件指纹（函数名是稳定锚点，行号会漂移）：
>
> | 文件 | sha256 前 16 位 | 行数 |
> |---|---|---|
> | `rust/crates/ffi/src/codec.rs` | `0ae44cd862fa0e57` | 2324 |
> | `rust/crates/ffi/src/session.rs` | `957c51c0fae57018` | 1938 |
> | `rust/crates/protocol/src/zemote_client.rs` | `6b1f1a14d4bf87d9` | 4308 |
> | `rust/crates/protocol/src/conversation_transport.rs` | `cc19c5fb71aa27d7` | 6654 |
> | `rust/crates/protocol/src/conversation.rs` | `cb9a9183c2a42ac7` | 4290 |
> | `android/.../ui/chat/ChatViewModel.kt` | `e9f33f9f2ba32d63` | 2004 |
> | `android/.../ui/chat/ConversationRegistry.kt` | `ac989d5ef28fd3aa` | 204 |
> | `android/.../ui/chat/ChatOps.kt` | `1464ff367a881a7a` | 153 |
> | `android/.../engine/ZemoteEngine.kt` | `30f0ef4c67f188bc` | 921 |
>
> **⚠ 与 round3 基线不同**：`ChatViewModel.kt` 1910 → 2004 行、`ConversationRegistry.kt` 198 → 204 行，
> 已被并发改动过。本报告以本轮读取的版本为准。
>
> 桌面侧证据来自 `app.asar`（`out/renderer/assets/validation-CN6eIzmJ.js`、`out/main/index.js`、
> `out/host/index.js`、`out/host/chunk-ZSDMP7DM.js`、`out/host/chunk-LNTVTW5D.js`），只读反混淆，未执行。

---

## 1. 结论

| 项 | 判断 |
|---|---|
| A/B/C/D 四条 `workspace-bridge-open` | **全部能通过桌面 zod 与主进程分支**（`taskId` 从不发 null，`project`/`taskId` 互斥，`taskHostId` 与 `taskId` 成对） |
| E 首条 `sendText` | **能通过**：`mode` 取值落在 `["build","edit","plan","yolo","guarded"]`，`modelSelection` 形状与 `.strict()` schema 逐键一致 |
| 整体 | **可交付**，但带 1 个 P1（`sendText` 宿主兜底超时预算不足，降级桥下会假超时并可能重复投递）与 2 个 P2 |

**P0：无。** 没有任何一条命令会把用户数据写坏，也没有哪条路径会稳定不可用。

**P1（1 条）**：`sendText` 没有 `args.timeoutSecs`，宿主兜底只有 35s，而核心在降级桥上可能先等
`bridge_wait_healthy`（45s）再发命令（30s），真实预算 75s。→ 见 §5.5。

**P2（2 条）**：
1. `ConversationRegistry.effectiveScope` 用精确字符串比较决定是否重建 scope；快照读不到时直接沿用请求 key，
   会把「请求 key ≠ 桌面权威 key」的差异带进 `conversation_open`（远端 cwd 可能指错）。
2. Rust 的 `open_bridge_with_intent` 不拦 `taskId + project` 同时出现（只有 Kotlin 与桌面 zod 兜着）。

---

## 2. 三层 wire 模型（先分清「线上」指哪一层，否则会对着错误的对象核字段）

| 层 | 载体 | 谁看 |
|---|---|---|
| L1 FFI 命令 | `nativeCall(commandJson)` 的 JSON 字符串 | Rust `codec::parse_call_command` |
| L2 relay 载荷 | `zcode_type` 帧，经 relay 送到桌面主进程 | 桌面 zod（`validation-*.js`） |
| L3 桥上通道调用 | `zcode-agent.<method>([args])` 的 rpc-frame | 桌面 host 的 agent 运行时 |

关键：**`sendText` 不是 L2 帧**。它只以 L3 通道调用出现：
`zcode-agent.sendConversationCommandV4([{...scope, envelope}])`（`conversation_transport.rs:1929` +
`conversation.rs:308`，`call_json` 把 args 包成单元素数组 → `conversation_transport.rs:1884-1894`）。
而 `open_bridge` 才是 L2 帧（`workspace-bridge-open`）。

L1 → L2 的字段消长（这是本报告最容易被忽略的部分）：

| L1 字段 | L1 有 null 吗 | L2 结果 |
|---|---|---|
| `open_bridge.taskId` | 有（Kotlin 显式发 `JsonNull`，`ZemoteEngine.kt:301`） | **键消失**（`open_bridge_request` 只在 `Some` 时插入，`zemote_client.rs:2473-2475`） |
| `open_bridge.taskHostId` | 无（`?.let`） | 有则原样 |
| `open_bridge.project` | 无（`?.let`）；空 `{}` 被 `if project.is_empty() { None }` 消掉（`codec.rs:945-948`） | 有则原样 |
| `conversation_call.args.timeoutSecs` | 无 | **永不进 payload**（只驱动超时，见 §5.4/§5.5） |
| `sendText.mode/planEnabled/modelSelection` | 无 | 原样保留（见 §5.3） |

---

## 3. 五条命令的最终线上 payload

`requestId` / `bridgeSessionId` 的真实形状是 `workspace-bridge-<uuid-v4>` / `bridge-<uuid-v4>`
（`zemote_client.rs:2311-2313` + `id.rs:72-109`）。`bridgeGeneration` 是客户端自增整数，从 1 起。
下面示例里的 uuid 用占位符。

### A. 打开当前 tab 工作区里的任务

产生分支：`HomeViewModel.onOpenTask` → `needsPrepare = !workspace.bridgeable || taskKey != workspace.key`
为 **false** → 路由 `hostId = null`（`AppNavHost.kt:164-177`）→
`ChatViewModel.ensureRouteBridge(taskId)`：`taskHostId = if (taskId != null) initialHostId` = `null`，
`project = if (taskId == null) …` = `null`（`ChatViewModel.kt:852-860`）→
`ConversationRegistry.ensure` → `ZemoteEngine.openBridge` → `open_bridge_with_intent`
→ `open_bridge_request(..., task_id=Some, task_host_id=None, project=None)`。

```json
{
  "zcode_type": "workspace-bridge-open",
  "requestId": "workspace-bridge-8f0c3a52-6d3f-4a8e-9a1c-1f5d0b7c2e44",
  "bridgeSessionId": "bridge-2b7e4d10-9c85-4f36-b0d2-77a1e6c9f301",
  "bridgeGeneration": 1,
  "workspaceKey": "E:\\work\\proj",
  "taskId": "task-9d2f1c60-4a1b-4c7e-8f52-3e6b9a0d7c21"
}
```

- **缺省**：`taskHostId`、`project`、`recoveryId`、`terminalClientSessionId`、`fileTreeTaskId` 全都不出现。
- 桌面走「按 key 在已挂载工作区里找」分支（`out/main/index.js` @423822 区：
  `O = I(h).find(ie => ot(ie) === b.workspaceKey)`），找不到就报
  `目标工作区不在当前桌面窗口中，无法创建 Web 远程控制 bridge。`
- zod 校验点：`taskId: Q.optional()`（`Q = z.string().trim().min(1)`）→ 字符串 OK。

### B. 打开非 tab 项目（local）里的任务

产生分支：`!workspace.bridgeable` → `hostId = task.hostId ?: workspace.hostId`，
桌面本地任务行的 `hostId` 固定为 `"local"`
（`out/host/index.js` @38235 区：`.map(k => ({...k, hostId: e.hostId, projectId: S}))`，
本地目录的 `e.hostId === "local"`；`chunk-LNTVTW5D.js` @122548 有字面量 `hostId:"local"`）
→ `ensureRouteBridge` 把 `initialHostId` 当 `taskHostId` 发出。

```json
{
  "zcode_type": "workspace-bridge-open",
  "requestId": "workspace-bridge-4e1a77c0-2d55-4b93-8a6e-c0f4d2b19a37",
  "bridgeSessionId": "bridge-6a3c90e7-1f48-4d2b-9c05-8e7a1b4f6d29",
  "bridgeGeneration": 1,
  "workspaceKey": "E:\\work\\other-proj",
  "taskId": "task-3c8b5e21-7f9a-4d60-b18c-2a5e7d0f9134",
  "taskHostId": "local"
}
```

- `workspaceKey` = 该任务自己的 `workspaceIdentity?.trim() || workspacePath`
  （多根项目必须用任务自己的 key，`AppNavHost.kt:161-173`）。
- 桌面：`Z = {hostId:"local", task:{taskId, workspaceKey}}` → `prepareProjectWorkspace(windowId, Z, hostRequestId)`
  （`out/main/index.js` @423822）。这是**唯一**能让非 tab 工作区被找到的分支。
- zod 断言：`(!e.taskHostId || !!(e.taskId || e.fileTreeTaskId))` 成立（`taskHostId` 与 `taskId` 成对）。

### C. 打开非 tab 项目（remote server）里的任务

产生分支：同 B，`hostId` 来自任务行，形如 `remote:server:<serverId>` / `remote:ssh:<host>:<port>:<user>`
（`out/host/chunk-ZSDMP7DM.js` @760369 区 `buildRemoteWorkspaceIdentity`）。

```json
{
  "zcode_type": "workspace-bridge-open",
  "requestId": "workspace-bridge-c27f5b64-0e39-4f81-a6d4-5b90c3e17a28",
  "bridgeSessionId": "bridge-1d84f6a3-7c20-4e59-bb71-0a93e5d2c846",
  "bridgeGeneration": 1,
  "workspaceKey": "remote:ssh:devbox:22:alice:/home/alice/proj",
  "taskId": "task-a17e93b0-5c42-4f68-9d31-7b0e2a4c85f6",
  "taskHostId": "remote:ssh:devbox:22:alice"
}
```

- `workspaceKey` 与 `taskHostId` 的关系是**拼接**：`identity = hostId + ":" + gy(path)`，
  `gy(p) = "/" + p.replace(/\\/g,"/").replace(/\/+/g,"/").replace(/^\/+|\/+$/g,"")`
  （`HomeViewModel.remoteIdentityOf` = 同一规则）。所以 `taskHostId` 恒等于
  `identity` 去掉末尾 `/<path>` 的部分，两者**必须同时给**，否则桌面 `uF()` 会抛
  `远程 workspace bridge 缺少 workspaceIdentity 或 remoteSessionId。`。

### D. 在非 tab 项目里新建对话（project intent）

产生分支：`onNewConversation(workspace)` 且 `!bridgeable`
→ `hostId = workspace.hostId`、`projectId = workspace.projectId`（`AppNavHost.kt:181-194`）
→ `sendText` 时 `selectedTaskId == null` → `createConversation` → `ensureRouteBridge(taskId = null)`
→ `taskHostId = null`、`project = projectIntent() = {projectId, hostId?}`（`ChatViewModel.kt:869-875`）。

```json
{
  "zcode_type": "workspace-bridge-open",
  "requestId": "workspace-bridge-7b30d9a1-5f64-4c28-8e07-1a4b6d92f0c5",
  "bridgeSessionId": "bridge-93f5c0d8-6b17-4a42-9e3a-2d8f04c7b16e",
  "bridgeGeneration": 1,
  "workspaceKey": "E:\\work\\other-proj",
  "project": {
    "projectId": "3f7a1c92-8d64-4b05-9e21-ca0d7b58e34f",
    "hostId": "local"
  }
}
```

- **`taskId` 键完全不存在**（不是 `null`）。这是 A/B/C 与 D 的硬性差别。
- `project.hostId` 仅当 `initialHostId != null` 才出现；`draftId` / `reuseDraft` **不发**。
- zod：`project.projectId: Q.nullable()`（必填但可为 null）、`hostId: Q.optional()`、
  `draftId: uuid().optional()`、`reuseDraft: boolean().optional()`，且 `.strict()`
  → 多一个键就整帧失败；Kotlin 只发这两个键，安全。
- 主进程：`if (b.project && b.taskId) throw "Project navigation is unavailable."` ——
  因为 `taskId` 键不存在，这里恒为 falsy，走 `prepareProjectWorkspace`。
- 前置守卫：`!selected.bridgeable && selected.projectId == null` 时**提前报错不发 RPC**
  （`ChatViewModel.kt:730-742`），所以「legacy 项目（无 projectId）」不会退化成一个只有
  `workspaceKey` 的裸 open（那种帧会被桌面按 key 找不到而报错）。

### E. 新建会话后的首条 `sendText`

产生分支：`createConversation` 里 `createSession` 成功 → `subscribeConversation` → `loadModelView` →
`refreshConversationState`（拿到 `config`）→ `chatOps.call("sendText", …)`
（`ChatViewModel.kt:751-803`）；`putSendSubmission()` 从 `_state.value.config` 取
`mode/planEnabled/provider/model/thought`（`ChatViewModel.kt:883-912`）。

**E-1：已有 config 且 `config.mode == "plan"`**（任务指定的情形）

L3 线上（`zcode-agent.sendConversationCommandV4` 的第 1 个位置参数）：

```json
{
  "workspacePath": "E:\\work\\other-proj",
  "envelope": {
    "commandId": "db4c1a76-2f0e-4a91-b5c8-30e7d6f1a294",
    "clientId": "0a7c3e55-9b18-4d72-8f60-2c5e14b7d903",
    "sessionId": "sess_7f31a0c4-5b62-4de8-9c07-e1a4d5b3f286",
    "type": "sendText",
    "payload": {
      "text": "帮我看看这个报错",
      "mode": "build",
      "planEnabled": true,
      "modelSelection": {
        "providerId": "anthropic",
        "modelId": "claude-sonnet-4-5-20250929",
        "options": {
          "reasoningLevel": "high"
        }
      }
    },
    "issuedAt": 1761840000000
  }
}
```

远端工作区时 scope 多一键，其余不变：

```json
{
  "workspacePath": "/home/alice/proj",
  "workspaceIdentity": "remote:ssh:devbox:22:alice:/home/alice/proj",
  "envelope": { "…同上…": "…" }
}
```

- **`mode` 永远是 `"build"`，不是 `"plan"`**：`rawMode == "plan"` 落进 `else -> "build"` 分支，
  plan 状态改由 `planEnabled: true` 表达（`ChatViewModel.kt:890-895`）。这与网页端一致
  （`Reference_web/3.17.1/assets/index-Dwhx77cK.js`：`mode: t.data==='plan' ? 'build' : t.data`、
  `planEnabled: t.data==='plan'`）。
- `mode` 的合法集是**严格 5 值**：`Xw = Cx = z.enum(["build","edit","plan","yolo","guarded"])`
  （`out/host/chunk-ZSDMP7DM.js` @513606，经 `out/host/index.js` 的 `qc as Xw` 引入；
  `sendText.payload.mode: Xw.optional()`）。沙箱里另有一个 6 值枚举含 `"auto"`（`vt` @478565），
  但它只用于会话快照/事件层，**不是** `sendText` 的校验器——所以「把 `auto` 原样透传」会失败，
  而当前 Kotlin 的 `when` 已把 `auto/default` 折成 `build`，安全。
- `modelSelection` 的 schema 是 `.strict()` 且逐键吻合：
  `z.object({providerId: string().trim().min(1), modelId: string().trim().min(1), options: z.object({reasoningLevel: string().trim().min(1).optional()}).strict().optional()}).strict()`
  （`out/host/chunk-ZSDMP7DM.js` @475093，即 `out/host/index.js` 里的 `ar`）。
  Kotlin 只在 `thought` 非空时才加 `options`，空串会被 `isNullOrBlank()` 挡掉 →
  绝不会出现 `reasoningLevel: ""`（那个会被 `.min(1)` 拒）。
- `sessionId` 是**新建会话的 id**（`sendText.args.sessionId`），不是 null。
- 没有 `baseRevision` / `baseLogEpoch`：`sendText` 不在 `CAS_COMMANDS` 里
  （`conversation.rs:107-123`），也不在 `ROW_TARGET_COMMANDS` 里 → 两个键都不出现。
- `attachments` 仅当有附件时出现；`heldQueueDisposition` 等其余键全不发。

**E-2：新会话还没有 config**（`refreshConversationState` 尚未回填）

`payload` 变成 `{"text": …, "mode": "build", "planEnabled": false}`，**没有 `modelSelection`**。
这是 `provider`/`model` 为空时的合法降级（`ChatViewModel.kt:896-911`），桌面只用工作区默认模型。

---

## 4. 依赖的两条伴随命令（D/E 的前提）

虽然不在 A–E 五条里，但 E 能否成立依赖它们，列出来免得漏字段。

### 4.1 `createSession`（L3）

L1：`{"method":"conversation_call","session":N,"key":"…","op":"createSession","args":{"workspaceId":"…","timeoutSecs":90}}`

L3：`zcode-agent.sendConversationCommandV4([{…scope, envelope}])`

```json
{
  "workspacePath": "E:\\work\\other-proj",
  "envelope": {
    "commandId": "5c81e0a7-3b26-4f19-8d04-a72c6e9b53f1",
    "clientId": "0a7c3e55-9b18-4d72-8f60-2c5e14b7d903",
    "sessionId": null,
    "type": "createSession",
    "payload": {
      "workspaceId": "E:\\work\\other-proj"
    },
    "issuedAt": 1761839999000
  }
}
```

- `sessionId: null` 是**显式允许**的：信封 schema 就是
  `sessionId: z.string().nullable()`（`out/host/index.js` @227055），不是 `.optional()`。
- `payload` 只有 `workspaceId`：`create_session_payload(workspace_id, first_text=None, …)` 其余全不插
  （`conversation.rs:319-354`）。`workspaceId` 取 `handle.effectiveWorkspaceKey`
  （= bridge-ready 的 `bridge.workspaceKey`，`ChatViewModel.kt:750`），不是路由带来的旧 key。
- `timeoutSecs` **不进 payload**（见 §5.5）。
- 与网页端差异（P2，不影响可用性）：网页预热会带 `config`（`{workspaceId, config}`，
  `Reference_web/…/index-Dwhx77cK.js` @415 的 `v4-draft-prewarm`），Kotlin 不带 →
  新会话先用工作区默认 config，首条 `sendText` 的 `modelSelection` 再把它纠正过来。

### 4.2 `subscribeSessionsIndex`（L3）

L1：`{"method":"conversation_call","session":N,"key":"…","op":"subscribeSessionsIndex","args":{"timeoutSecs":60}}`

L3：

```json
{
  "workspacePath": "E:\\work\\other-proj",
  "runtimePolicy": "existing-only"
}
```

即 `zcode-agent.subscribeSessionsIndexV4([{workspacePath, workspaceIdentity?, runtimePolicy:"existing-only"}])`
（`conversation.rs:1489-1496` + `subscribe_request_args` 的 scope 合并，`conversation.rs:1506-1508`）。
订阅不带 `sessionId`；conversation 的订阅才是 `{…scope, sessionId}`。

---

## 5. 六条核对

### 5.1 `taskId: null` 会发成 JSON null 吗？桌面 zod 接受 null 吗？

**不会发成 null；而桌面也确实不接受 null。**

链路：
1. Kotlin **确实**在 L1 发 `null`：`put("taskId", taskId?.let { JsonPrimitive(it) } ?: JsonNull)`
   （`ZemoteEngine.kt:301`，注释写「契约 §3：键不省」）。
2. Rust `codec.rs:943`：`optional_string(map, "taskId")`，其实现 `None | Some(Value::Null) => Ok(None)`
   （`codec.rs:1035-1041`）→ null 归成 `None`，**不报错**。
3. Rust `open_bridge_request`：`if let Some(task_id) = task_id { payload.insert("taskId", …) }`
   （`zemote_client.rs:2473-2475`）→ 键被**整体省略**。
4. 桌面 zod：`taskId: Q.optional()`，`Q = x().trim().min(1)`（`validation-CN6eIzmJ.js`）。
   `z.string().optional()` **不接受 `null`**，也**不接受空串**。所以就算哪天真发了 `null`，
   结果是整帧 `safeParse` 失败、被桌面 `handleDataPayload` 丢弃（只打一条 warn），不是被兼容
   —— 该「校验失败即静默丢帧」的行为与 `zemote_client.rs:2543-2548` 记录的前轮实测一致。

结论：L2 上不存在 `"taskId": null`；L2 与 L3 的所有 `taskId` 都是非空字符串。
`send_mobile_view_state` 同理（`mobile_view_state_update_payload` 也是 `if let Some`）。

### 5.2 `project` 与 `taskId` 能同时出现吗？

**当前代码里没有任何可达路径，但 Rust 层不设防。**

| 层 | 是否可能同时 | 证据 |
|---|---|---|
| Kotlin | 否，**构造上互斥** | `taskHostId = if (taskId != null) initialHostId else null`、`project = if (taskId == null) projectIntent() else null`（`ChatViewModel.kt:858-859`；`:1154-1155` 同）；`HomeViewModel` 的 `registry.ensure` 只给 `taskId = null` 且不给 `project` |
| Rust | **是**，不拦 | `open_bridge_with_intent` 只是把两个 `Option` 各自透传（`zemote_client.rs:1396-1404`）；`open_bridge_request` 两个 `if let` 独立 |
| 桌面 zod | **拒绝** | `.refine(e => (!e.project \|\| (!e.taskId && !e.fileTreeTaskId)) && …)`，出错文案 `Project draft cannot target an existing task` |
| 桌面主进程 | **再拒一次** | `if (b.project && b.taskId || !e.prepareProjectWorkspace) throw new Error("Project navigation is unavailable.")` |

同一条 refine 还隐含另外两条约束，Kotlin 都满足：
- `taskHostId` 不能单独出现（`!e.taskHostId || !!(e.taskId || e.fileTreeTaskId)`）——
  Kotlin 只在 `taskId != null` 时给 `taskHostId` ✔
- `project` 与 `fileTreeTaskId` 互斥 —— Kotlin 从不发 `fileTreeTaskId` ✔

**风险（P2）**：FFI 是对外契约，绕过 Kotlin 直接 `nativeCall({method:"open_bridge", project:{…}, taskId:"x"})`
会得到一个必然被桌面丢弃的帧，而 Rust 侧会照常登记一个挂起请求，等到 30s（+5s）超时才失败。
建议在 `codec.rs` 的 `open_bridge` 解析处加一条互斥校验（本地立刻 `badInput`），成本一行。
（本轮只读，未改。）

### 5.3 `mode` / `planEnabled` / `modelSelection` 会被保留还是丢弃？

| 载荷 | `sendText` | `createSession` |
|---|---|---|
| `mode` | **保留**：`if let Some(v) = mode { m.insert("mode", v) }`（`conversation.rs:375-377`） | **不存在这个参数**，`create_session_payload` 签名里就没有（`conversation.rs:319-326`）→ 若调用方塞了就**静默丢弃** |
| `planEnabled` | **保留**：`conversation.rs:378-380` | 同上，丢弃 |
| `modelSelection` | **保留**：`conversation.rs:381-385`，但 `if !selection.is_empty()` —— 空对象会被丢（Kotlin 不会发空对象） | 同上，丢弃 |

`sendText` 的取值闸门也很严：`opt_bool` 只收 bool（`conversation_transport.rs:3728-3736`）、
`opt_obj` 只收对象（`:3707-3715`）、`opt_str` 只收字符串（`:3696-3704`），类型不符直接
`ProtocolError`（→ 宿主 `badInput`），不会静默降级。Rust **不重排、不改写、不裁键**这三项。

现状：Kotlin 的 `createSession` 只发 `{workspaceId, timeoutSecs}`，所以「丢弃」这条实际不发生；
但这也是为什么首条消息的 `mode`/模型必须由 `sendText` 重新携带——而它确实带了
（`putSendSubmission()` 在 `createConversation` 与 `sendText` 两处都调用）。

### 5.4 `subscribeSessionsIndex` 的 `args.timeoutSecs` 会被当成未知键拒绝吗？

**不会——它根本到不了任何序列化点。**

`conversation_transport.rs:1256-1268`：

```rust
"subscribeSessionsIndex" => {
    self.enqueue_flow(op, FlowKind::Subscribe(Box::new(SubscribeFlow { /* 不读 args */ })), …);
}
```

整个分支**一次 `args.get` 都没有**（对比 `subscribe` 至少读了 `sessionId`，`sendText` 读了 13 个键）。
`timeoutSecs` 只被宿主层 `ZemoteEngine.hostTimeoutMs → conversationTimeoutSecs` 读走
（`ZemoteEngine.kt:699-714`），用于算 Kotlin 侧 `withTimeoutOrNull` 的预算；
L3 payload 用的是 Rust 自己重建的 `{…scope, runtimePolicy:"existing-only"}`。

所以：
- 没有「未知键被拒」的风险（Rust 不校验键集，桌面信封 `payload: unknown()` 也不校验）；
- 但也**没有**「未知键被转发」的风险（它不转发）。

副作用（值得记住）：给订阅塞一个不存在的 `timeoutSecs` 不会让核心等更久——核心的 ack 超时是硬编码
`SUBSCRIBE_TIMEOUT_SECS = 60`（`conversation.rs:67`，用于 `conversation_transport.rs:2272`），
`args.timeoutSecs` 只放宽**宿主**那一侧。

### 5.5 `createSession` 的 `timeoutSecs` 只影响超时吗？宿主兜底覆盖 45s `waitHealthy` 吗？

**只影响超时，不进 payload。**（⇒ 见上面的 §4.1 payload，没有 `timeoutSecs` 键）

```rust
"createSession" => {
    let payload = create_session_payload(&workspace_id, first_text…, config…);   // 无 timeout 字段
    let timeout_ms = match opt_i64(op, args, "timeoutSecs")? {
        Some(secs) => secs * 1000,
        None => CREATE_SESSION_TIMEOUT_SECS as i64 * 1000,                         // 90s
    };
    …CommandParams { payload, timeout_ms, … }
}
```
（`conversation_transport.rs:1491-1522`）

宿主公式（`ZemoteEngine.kt:699-707`）：

```
hostTimeout = declaredMs + HOST_TIMEOUT_GRACE_MS(5s) + healthyBudget
declaredMs   = command.timeoutMs ?? args.timeoutSecs*1000 ?? 30s
healthyBudget = 45s  仅当 command 是 conversation_call 且 args.timeoutSecs 存在；否则 0
```

| 命令 | L1 `timeoutSecs` | 宿主预算 | 核心预算 | 覆盖？ |
|---|---|---|---|---|
| `createSession` | 90 | 90+5+45 = **140s** | 90s + 核心内部 `waitHealthy` 45s = 135s | ✅ 覆盖 |
| `subscribeSessionsIndex` | 60 | 60+5+45 = **110s** | 60s | ✅ 覆盖 |
| `sendText` | **无** | 30+5+**0** = **35s** | 30s + 降级桥上先 `waitHealthy` 45s = **75s** | ❌ **不覆盖（P1）** |
| `open_bridge` | 顶层 `timeoutMs=30000` | 30+5 = 35s | 30s（无内部 waitHealthy） | ✅ 覆盖 |

`healthyBudget` 的必要性由核心实证：`FlowKind::PendingCommand` 在桥降级时会先转
`FlowStage::AwaitHealthy{ timeout_ms: WAIT_HEALTHY_TIMEOUT_MS }`（= 45s，
`conversation_transport.rs:2527-2534`、`144`），健康回来后才真正发命令；命令自身的 30s 定时器
是在**发送之后**才起算的。因此降级桥上的 `sendText` 最坏 45+30 = 75s。

**P1 后果**：宿主在 35s 抛 `EngineException(kind="timeout")`，但
`invoke()` 只做 `pending.remove(token)`（`ZemoteEngine.kt:675-682`），
核心侧的定时器与 flow **不会被取消** —— 命令随后仍可能送达并被服务端接受。
界面已回填草稿（`draftRestore`），用户重试就会**重复投递同一条消息**。

**修法（一行级）**：`ChatViewModel.sendText` / `createConversation` 里给 `sendText` 的 args 也带
`timeoutSecs`（与订阅同一手法，例如 30），或把 `hostTimeoutMs` 的 `healthyBudget`
改成「所有 `conversation_call` 都加 45s」。两者都不改 wire（`timeoutSecs` 不进 payload）。

### 5.6 `effectiveScope` 对不同 key 形态的产出，以及与桌面 `bridge.workspacePath/workspaceIdentity` 的一致性

桌面权威定义（`out/main/chunk-ZLEG2KTO.js`，`workspaceKeyOf`）：

```js
function ht(path, identity) {
  return identity?.trim() || path?.trim() || "__default_conversation_workspace__";
}
```

即 **identity 先去空白优先，path 只去空白（不折叠斜杠、不去尾斜杠）**；两者都没有时才用哨兵值。
远端 identity 由 `buildRemoteWorkspaceIdentity` 生成，路径段经过
`gy(p) = "/" + p.replace(/\\/g,"/").replace(/\/+/g,"/").replace(/^\/+|\/+$/g,"")`
（`out/host/chunk-ZSDMP7DM.js` @760369 区）。

`ConversationRegistry.effectiveScope(requested, effectiveKey)`（`ConversationRegistry.kt:160-174`）：

```
requestedKey = requested.workspaceIdentity ?? requested.workspacePath      // 不 trim，不 fallback 哨兵
if (requestedKey == effectiveKey) return requested                          // 原样（保留 path+identity）
slash = effectiveKey.indexOf('/')                                           // 整个串的第一个 '/'
if (effectiveKey.startsWith("remote:") && slash > 0)
    → { workspacePath: effectiveKey.substring(slash), workspaceIdentity: effectiveKey }
else
    → { workspacePath: effectiveKey }                                       // identity 被丢弃
```

逐形态：

| 输入 | 桌面 canonical `workspaceKey` | `effectiveScope` 产出 | 一致？ |
|---|---|---|---|
| local path `E:\work\proj`（无 identity） | `E:\work\proj` | 走 `==` 分支，原样 `{workspacePath:"E:\\work\\proj"}` | ✅ |
| local path 带尾斜杠 `E:\work\proj\` | 若项目根是 `E:\work\proj`，`prepareProjectWorkspace` 回权威值 `E:\work\proj` | key 不等 → 走 else 分支 `{workspacePath:"E:\\work\\proj"}`（identity 本来就没有，丢不了东西） | ✅ 自愈 |
| Windows 路径（反斜杠） | 原样保留反斜杠（`ht` 不做分隔符归一） | 原样透传 | ✅ |
| remote identity `remote:ssh:devbox:22:alice:/home/alice/proj` | 同串 | `==` 分支，原样 `{workspacePath:"/home/alice/proj", workspaceIdentity:"remote:ssh:…"}` | ✅ |
| remote identity 与请求值不同（大小写/host 归一/路径归一差异） | 权威串 | `remote:` 分支 → `workspacePath = substring(第一个 '/')` = `/home/alice/proj`，`workspaceIdentity = 权威串` | ✅ 自愈 |
| `remote:server:<id>:/srv/x` | 同串 | 同上 → `/srv/x` | ✅（`serverId` 被 `normalizeServerIdForIdentity` 归一成 `[a-z0-9._-]`，不含 `/`） |
| `remote:wsl:Ubuntu:root:/home/u/p` / `remote:docker:ctr:/srv/p` | 同串 | 同上 | ✅（distro/user/container 段都不含 `/`） |
| 快照读不到（`engine.snapshot()` 抛错/无该 bridge） | — | `effectiveWorkspaceKey()` 返回 null → `effectiveKey = 请求的 workspaceKey`，`conversation_open` 直接用请求 scope | ⚠️ **可能不一致（P2）** |

三条与桌面不一致的可能性，按严重度：

1. **P2 · 快照不可用时的静默降级**（`ConversationRegistry.kt:141-150`）。`effectiveWorkspaceKey`
   靠 `snapshot.bridges[]` 里 `session == bridge` 的行取 `workspaceKey`；拿不到就 `?: workspaceKey`
   用请求值。此时若请求 key 与桌面权威 key 不同（典型：远端 identity 的 host 大小写、或
    `prepareProjectWorkspace` 归一后的路径），`conversation_open` 的 `workspacePath` 会被当作
   agent CLI 的 spawn cwd（`ChatViewModel.kt:914-920` 的注释），可能指到不存在的路径 →
   会话起不来或起在错的目录。这里没有重试也没有日志，属于「静默错工作区」。
2. **P2 · 精确字符串比较脆弱**。`requestedKey == effectiveKey` 是逐字节比较；`conversationScope()`
   里的 identity 已 `trim`（`ChatViewModel.kt:1971`），但 `workspacePath` **没有 trim**，
   而桌面 `ht()` 会 trim path。理论上「路由 path 带首尾空白」会让两侧 key 不等 → 走重建分支。
   对 local 无害（重建出的 path 就是权威 path），对 remote 也无害（identity 保留），
   所以只是「多一次不必要重建」，不会错。
3. **理论边界 · 非 remote 的 identity**。else 分支会**丢掉** `workspaceIdentity`。当前桌面
   只有 `remote:` 系工作区带 identity（`workspace-bridge-ready` 的 `kind:"local"` 分支里
   **没有** `workspaceIdentity` 字段，`validation-CN6eIzmJ.js`），所以这条路径不会丢真信息。
   若将来桌面给本地工作区也发 identity，这里会静默丢键 —— 那时 `else` 分支应改成
   「有 identity 就保留，只把 path 换成 effectiveKey」。

补充：`effectiveScope` **不会**产出 `remoteSessionId` / `workspacePurpose`。桌面
`workspace-bridge-ready` 的 remote 分支**要求**它们，但那是**桌面 → 手机**的方向；
手机 → 桌面的 `conversation_open` scope 只需要 `workspacePath` + `workspaceIdentity`
（`workspaceKeyOf` 只读这两键），所以不缺。

---

## 6. 结论与建议（按优先级）

**可交付。** A/B/C/D 四条开桥命令与 E 首条 `sendText` 的线上 JSON 全部落在桌面 zod 与主进程分支可接受的形状内；
`taskId` 不会以 `null` 上线，`project`/`taskId` 不会同现，`mode/planEnabled/modelSelection` 全程保真。

落地前建议（按优先级）：

1. **P1**：给 `sendText` 的 `conversation_call.args` 补 `timeoutSecs`（或让 `hostTimeoutMs`
   对所有 `conversation_call` 都加 45s `healthyBudget`）。否则降级桥下 35s 假超时 +
   用户重试重复投递同一条消息。
2. **P2**：`codec.rs` 的 `open_bridge` 解析处拒 `project` 与 `taskId`/`fileTreeTaskId` 同现
   （本地 `badInput`，别让桌面 zod 去丢帧并白等 35s）。
3. **P2**：`ConversationRegistry.effectiveWorkspaceKey` 取不到时至少打一条日志（现在是静默
   回退到请求 key，会让 `conversation_open` 的 cwd 指向请求路径而非桌面权威路径）；
   同时给 `conversationScope()` 的 `workspacePath` 补 `trim()`，与桌面 `ht()` 对齐。
