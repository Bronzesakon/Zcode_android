# 08 · 对话页行为协议冻结（实现清单）

> **性质**：只读取证产物，不改任何代码。
> **目标**：把对话页 UI 需要的协议行为与状态形状冻结成可直接照着实现的清单。
> **基线**：
> - 对端服务端：ZCode 桌面端 3.15.3（`app.asar` build `d7ca8bee`，`resources/glm/zcode.cjs`）
> - 网页端：`archive/pre-3.16.1-auth/Reference_web/3.15.3/assets/index-BGa3KiRe.js` + `validation-CqXZOLXO.js`
> - 我们的协议层：`rust/crates/protocol/src/conversation.rs`、`conversation_transport.rs`
> - FFI 契约：`docs/FFI.md` §13
> - 参考实现：`Reference_code/lib/ui/chat_page.dart`、`Reference_code/lib/protocol/conversation.dart`
>
> **类型记号**：`number` 一律是 JSON 整数（i64）；`T|null` 表示键存在且可为 `null`（Rust 序列化不省键）；
> `T?` 表示服务端 schema 里的 optional（线上可能缺键）。UI 侧对两者都应按「缺失 = null/空」处理。

---

## 0. 结论摘要

1. `conversations[sessionId]` 是 **`ConversationState` 字段 + 全部派生 getter + 订阅元数据** 的扁平对象；
   UI 真正要驱动渲染的是 `snapshot`、`rows`、`phase`、`config`、`queue/queueItems`、
   `pendingInteractions`、`usage`、`plan`、`goal`、`backgroundWorks`、`hasStreamingRow`、`canLoadOlder`。
2. 派生 getter **全部从 `snapshot` 现算**，所以 `state.updated` 一旦把 patch 并进 `snapshot`，派生值立刻变化。
3. `state.updated.patch` 是**顶层浅替换**，不是深合并：`queue`/`config`/`control` 等对象必须整包给。
4. `row.removed` 是「删除 `rowId >= fromRowId` 的所有行」，不是删一条；`row.upserted` 找不到目标行时**整体忽略**。
5. `row.delta` 只有 4 个 path；类型不匹配或行不在窗口内一律**静默丢弃**（不报错、不插行）。
6. `rowsRange` 是纯读取：返回 `{rows, atSeq, atRevision, atLogEpoch, hasMore}`，**不会自动并入 state**；
   UI 必须自己调 `prependOlderRows`。`atLogEpoch` 不匹配时应整批丢弃。
7. `historyExhausted` **不是线上字段**，由 UI 在 `hasMore === false` 时通过 `setHistoryExhausted` 写入。
8. 行 `actions` 到命令的映射是固定的：`canFork→forkAssistant`、`canEdit→editUserQuery`、
   `canRetry→retryTurn`、`canRewindFiles→applyFileRewind`；`target` 统一为 `{rowId, entityId?}`。
9. **协议层没有「静默历史」或「不抢滚动」的专用标记**。可用的只有 `payload.kind`（snapshot/deltas）、
   `row.kind`、`userInput.origin`/`inputOrigin`/`clientId`；`deliveryKind` 只存在于线上信封，
   我们的 Rust 在拆信封时就丢掉了。
10. **FFI 暴露缺口（实现时必须知道）**：`ConversationTransport::snapshot()` 有 `conversations`，
    但 `nativeQuery` 只返回核心快照（`rust/crates/ffi/src/session.rs:121-130`），**没有对话查询入口**。
    当前 Kotlin `refreshConversationState()` 读 `snapshot["conversations"]` 会拿到 `null` 并静默回退。
    UI 目前只能从 `rowsUpdated.frame.payload.deltas` 自己归约 `state.updated`，或等 FFI 增加查询（abi +1）。

---

## 1. `snapshot()` 里 `conversations[sessionId]` 的全部字段与类型

实现位置：`conversation_transport.rs:3843-3898`（`conversation_state_snapshot`）。
状态本体：`conversation.rs:1551-1571`（`ConversationState`）。

### 1.1 订阅元数据（本模块附加，不在线上快照里）

| 字段 | 类型 | 来源 / 语义 |
| --- | --- | --- |
| `subscriptionId` | `string\|null` | `ack.subscriptionId`；未订阅成功前为 `null` |
| `disposed` | `boolean` | 订阅已销毁；已销毁的订阅**不会出现在 `conversations` 里** |
| `started` | `boolean` | 订阅流程已启动 |
| `listenerId` | `number\|null` | 宿主注册的动态事件监听器 id |
| `resyncing` | `boolean` | 正在 resync |
| `stagedFrameCount` | `number` | 订阅 ack 前暂存的帧数 |
| `pendingFragmentCount` | `number` | 未集齐的分片组数 |
| `lastFrameAtMs` | `number` | 最后一帧到达时间（宿主传入的 `nowMs`） |

### 1.2 `ConversationState` 字段 + 派生 getter

| 字段 | 类型 | 语义 / 计算规则 |
| --- | --- | --- |
| `snapshot` | `object\|null` | 原始会话快照（已并入 `state.updated` 与 `pendingPatch`）。**其余派生值的唯一来源** |
| `rows` | `Row[]` | 当前行窗口，按 `rowId` 升序；可能包含窗口外前插的老行 |
| `seq` | `number` | 已应用到的 `toSeq` |
| `logEpoch` | `string\|null` | 快照/应答的 `logEpoch` |
| `firstRowId` | `number\|null` | 快照投影头（服务端裁剪前窗口第一行），**可能不在 `rows` 里**；不是分页游标 |
| `totalCount` | `number` | 全会话行数（服务端裁剪前窗口长度）；`row.appended` +1、`row.removed` 递减；`prependOlderRows` 不改它 |
| `ready` | `boolean` | 至少成功应用过一帧 |
| `historyExhausted` | `boolean` | **UI 写入**（见 §4.4）；协议层只读它算 `canLoadOlder` |
| `control` | `object\|null` | `snapshot.control`，形状见 §5.2 |
| `revision` | `number` | `snapshot.revision`，缺省 0；CAS 命令的基准 |
| `phase` | `string` | `control.phase`；缺省 `""`。枚举：`draft`/`prewarming`/`running`/`completedSuccess`/`completedInterrupted`/`error` |
| `canStop` | `boolean` | `control.canStop === true`（严格等于 `true`） |
| `isRunning` | `boolean` | `phase ∈ {running, prewarming}` |
| `config` | `Config\|null` | `snapshot.config`，形状见下 |
| `currentModel` | `string` | `config.model`；缺失/非字符串 → `""` |
| `currentThought` | `string` | `config.thought`；缺失 → `""` |
| `currentMode` | `string` | `config.mode`；缺失 → `"build"` |
| `thoughtLevels` | `string[]` | `config.thoughtLevels`；非数组 → `[]`；元素逐个转字符串（`null` → `"null"`） |
| `queue` | `Queue\|null` | `snapshot.queue`，形状见 §3.1 |
| `queueItems` | `QueueItem[]` | `queue.items` 里**只保留对象元素**；非对象元素被丢弃 |
| `autoDrain` | `boolean` | `queue.autoDrain !== false`（只有严格 `false` 才算关） |
| `usage` | `Usage\|null` | `snapshot.usage`，形状见 §5.2 |
| `canLoadOlder` | `boolean` | `!historyExhausted && firstRowId != null && totalCount > rows.length` |
| `oldestRowId` | `number\|null` | `rows[0].rowId`；窗口空时回退 `firstRowId`。**分页游标用这个** |
| `backgroundWorks` | `BackgroundWork[]` | `snapshot.backgroundWorks`，只保留对象元素 |
| `goal` | `Goal\|null` | `snapshot.goal` |
| `plan` | `Plan\|null` | `snapshot.plan` |
| `inputRoutingMode` | `string` | `snapshot.inputRouting.mode`；缺省 `"startNow"` |
| `pendingInteractions` | `PendingInteraction[]` | `snapshot.pendingInteractions`，只保留对象元素 |
| `hasStreamingRow` | `boolean` | `rows.any(r => r.state === "streaming")`。**只覆盖 `assistantText`/`reasoning`**；`toolCall` 用 `status`，不算在内 |

### 1.3 关键派生规则（实现时不要另算一套）

- `phase` 在 `control` 下面，**不在快照顶层**。
- `canStop` 是严格布尔比较，`1`/`"true"` 都不算。
- `currentMode` 缺省是 `"build"`，不是空串。
- `thoughtLevels` 对非字符串元素用 Dart 插值语义（数字/布尔转字符串，`null` → `"null"`）。
- `queueItems`、`backgroundWorks`、`pendingInteractions` 都会**过滤掉非对象元素**。
- `hasStreamingRow` 不能替代 `isRunning`；判断「正在跑」应使用 `phase`/`isRunning` + 行自身的 `state/status`。
- `oldestRowId` 是「当前持有的最老一行」，不是 `firstRowId`。翻页必须用它当 `beforeRowId`。

### 1.4 `Config` 形状（`snapshot.config`）

来源：`validation-CqXZOLXO.js` @183,0xx（`xh`）。

```ts
type Config = {
  modelSelection?: unknown;                 // 不透明对象（F.optional()）
  provider: string;
  model: string;
  thought: string;
  thoughtLevels: string[];                  // 默认 []
  followupMode: 'queue' | 'guide';
  mode: string;                             // 默认 'build'；取值见 Gp = build|edit|plan|yolo|guarded
  planEnabled?: boolean;
  permissionGrant?: { interactionId: string };
  planTransition?: { toolCallId: string; planEnabled: boolean };
};
```

### 1.5 顶层 `snapshot()` 的其他键（定位用）

`key`、`clientId`、`appVersion`、`handshaken`、`connectionId`、`bridgeDegraded`、
`ackedRevisions`（`{sessionId: revision}`）、`conversations`、`sessionsIndex`、`workspacePrep`。

`sessionsIndex[i]`：`subscriptionId`、`disposed`、`started`、`listenerId`、`resyncing`、
`lastFrameAtMs`、`workspaceId`、`logEpoch`、`seq`、`ready`、`sessionCount`、`list`。

### 1.6 FFI 暴露现状（重要）

- Rust 有 `ConversationTransport::snapshot()`（`conversation_transport.rs:1146-1189`），
  `conversations` 字段齐全。
- FFI 的 `nativeQuery {"query":"snapshot"}` 只返回**核心**快照（`rust/crates/ffi/src/session.rs:121-130`），
  其中没有 `conversations`。
- `docs/FFI.md` §14.1 也明确记录了这个缺口（要加查询需把 `abi` 升到 2）。
- 因此 Kotlin 当前 `ChatViewModel.refreshConversationState()`（`ChatViewModel.kt:850-869`）读
  `snapshot["conversations"]` 永远为 `null`，只能回退到旧值。**UI 不能依赖这条路径刷新非行状态。**

---

## 2. `pendingInteractions[]` 的完整形状与 `resolveInteraction`

来源：`validation-CqXZOLXO.js` @185,7xx–186,9xx（`Wh` / `zh` / `Hh` / `Hf`）。
Rust 侧只是原样透传数组（`conversation.rs:2069-2081`）。

### 2.1 数组元素

```ts
type PendingInteraction = {
  interactionId: string;
  kind: 'permission' | 'userInput' | 'workspaceHookReview';  // 必须等于 payload.kind
  anchorRowId: number | null;        // 内联卡片的锚点行；null = 不锚定
  createdAt: number;                 // 毫秒时间戳
  autoResolution?:
    | { state: 'hiddenGrace' | 'visibleCountdown';
        startedAt: number; visibleAt: number; deadlineAt: number }
    | { state: 'snoozed'; startedAt: number; snoozedAt: number };
  payload: PermissionPayload | UserInputPayload | WorkspaceHookReviewPayload;
};
```

约束（服务端 `superRefine`）：
- `kind` 必须与 `payload.kind` 一致。
- `workspaceHookReview` 不能带 `autoResolution`。
- `workspaceHookReview.payload.interactionId` 必须等于顶层 `interactionId`。

### 2.2 `PermissionPayload`（`payload.kind === "permission"`）

```ts
type PermissionPayload = {
  kind: 'permission';
  toolCallId: string;
  toolName: string;
  summary: string;
  detail: any;
  freeText?: boolean;                 // 是否显示自由文本输入
  origin?: object;                    // subagent 来源对象（gf）
  display?: unknown;                  // 展示用 payload（ih 联合）
  fullAccessOption?: {
    optionId: 'fullAccess';
    label: string;
    kind: 'custom';
    response?: unknown;               // cf
  };
  options: Array<{
    optionId: string;
    label: string;
    kind: 'allowOnce' | 'allowAlways' | 'deny' | 'custom';
    response?: unknown;               // cf
  }>;
};
```

### 2.3 `UserInputPayload`（`payload.kind === "userInput"`）

```ts
type UserInputPayload = {
  kind: 'userInput';
  prompt: string;
  freeText: boolean;                  // 注意是布尔，不是文本
  options?: Array<{ optionId: string; label: string }>;
  sensitive?: boolean;
  toolName?: string;
  toolCallId?: string;
  traceId?: string;
  input?: any;
  schema?: any;
  questions?: Array<{
    question: string;
    header: string;
    options: Array<{
      value: string;
      label: string;
      description?: string;
      preview?: string;
    }>;
    multiSelect?: boolean;
  }>;
  currentQuestionIndex?: number;      // 0 起；网页用它决定显示第几题
  answerDrafts?: Record<string, string[]>;  // 题目文本 -> 已选值
  origin?: object;
};
```

`questions` 是「表单式提问」：每题的 `question` 同时是答案 map 的键；选项值取 `value`，
展示取 `label`；`multiSelect === true` 才允许多选。

### 2.4 `WorkspaceHookReviewPayload`（只列与 UI 有关的字段）

```ts
type WorkspaceHookReviewPayload = {
  kind: 'workspaceHookReview';
  reviewFlowId: string;
  generation: number;
  interactionId: string;              // 必须等于顶层 interactionId
  sessionId: string;
  taskId: string;
  runId: string;
  workspaceIdentity: string;
  workspaceLabel: string;
  remoteSessionId?: string;
  bundleDigest: string;
  createdAt: number;
  deadlineAt: number;
  sourceFiles: Array<{ path: string; displayPath: string; editable: boolean }>;
  summary: { eventCount: number; hookCount: number; pendingCount: number };
  items: Array<{
    reviewItemId: string;
    event: 'SessionStart' | 'UserPromptSubmit' | 'PreToolUse' | 'PermissionRequest'
         | 'PostToolUse' | 'PostToolUseFailure' | 'Stop';
    matcher?: string;
    type: 'command' | 'process';
    displayName: string;
    displayCommand: string;
    sourcePath: string;
    resolvedTimeoutMs: number;
    resolvedMaxOutputBytes: number;
    executionMode: 'foreground' | 'background';
    configuredEnabled: boolean;
    editable: boolean;
    trustState: 'pending_trust' | 'revoked' | 'stale_digest' | string;
  }>;
  warningCode: 'workspace_hooks_execute_code';
};
```

> 本项目的对话页只要求处理 `permission` / `userInput`；`workspaceHookReview` 可以整卡透传或暂不渲染，
> 但**不要把它当成未知项丢弃**（它仍然占 `pendingInteractions` 名额，影响「有待处理」判断）。

### 2.5 `resolveInteraction` op

FFI：`docs/FFI.md` §13.5.1；Rust 分发：`conversation_transport.rs:1524-1539`；
payload 构造：`conversation.rs:509-532`。

```json
{"op":"resolveInteraction","args":{
  "sessionId":"...",
  "interactionId":"...",
  "optionId":"...?",        // 可选
  "freeText":"...?",        // 可选
  "action":"...?",          // 可选
  "content":{...}?          // 可选对象
}}
```

线上命令：

```json
{"type":"resolveInteraction","payload":{
  "interactionId":"...",
  "answer":{"optionId":"...","freeText":"...","action":"...","content":{...}}
}}
```

- `resolveInteraction` **不是 CAS 命令**，不需要 `baseRevision`/`baseLogEpoch`。
- ack 的 `result` 带 `resolvedBy`（`archive/pre-3.16.1-auth/docs/desktop/09-对话服务端.md` §7.2）。
- 空 `answer` 是合法的（四个键都可选），但 UI 至少要给 `optionId` 或 `freeText` 或
  `action + content` 之一，否则服务端无法判断用户意图。

### 2.6 UI 答案配方（与参考实现一致）

| 场景 | 参数 |
| --- | --- |
| 权限按钮（普通） | `optionId` = 选中项；`action: "accept"`；`content: {value,label,kind}`（存在才带） |
| 权限按钮（fullAccess） | `optionId: "fullAccess"`；`action: "accept"`；`content` 同上 |
| 自由文本 | `freeText: "<用户输入>"` |
| `questions` 表单 | `action: "accept"`；`content: {"answers": {"<question 文本>": ["<value>", ...]}}` |
| 自定义选项 | `optionId` + `action: "accept"` + `content` |

参考：`Reference_code/lib/ui/chat_page.dart:73-86`（`interactionOptionAnswer`）、
`:3420-3520`（`questions` / `freeText` 渲染）、`:3560-3660`（题目表单）。

---

## 3. `queueItems[]` 的完整形状与队列 op

来源：`validation-CqXZOLXO.js` @162,5xx–163,4xx（`Qp` / `Zp` / `Yp` / `Xp` / `Jp` / `Gp` / `Kp` / `qp`）、
@185,6xx（`Fh`）。

### 3.1 `queue` 对象（`snapshot.queue`）

```ts
type Queue = {
  items: QueueItem[];
  autoDrain: boolean;
  pauseReason?: 'stopped' | 'manual' | 'error';
};
```

`queueItems` getter = `queue.items` 中只保留对象元素。

### 3.2 `QueueItem`

```ts
type QueueItem = {
  inputOrigin?: 'desktop' | 'mobile';
  botGroupSource?: object;                 // gp
  conversationQuotes?: object;             // qf
  sourceCommandId: string;
  queueItemId: string;
  clientId: string;
  kind: 'sendText' | 'sendGoalCommand' | 'compact';
  text: string;
  attachments: Attachment[];               // 默认 []
  modelSelection?: unknown;                // F
  mode?: 'build' | 'edit' | 'plan' | 'yolo' | 'guarded';
  planEnabled?: boolean;
  sharedContextRefs?: Array<{ kind: 'shared_context_import'; context_id: string }>; // max 1
  delivery: {
    requested: 'auto' | 'startNow' | 'queue' | 'guide';
    admitted: 'startNow' | 'queue' | 'guide';
    fallbackReasonCode?: string;
  };
  order: {
    admissionSeq: number;
    queuePosition?: number;
  };
  steer: {
    state: 'notRequested' | 'submitting' | 'steering' | 'guided' | 'fellBack';
    reasonCode?: string;
  };
  dispatch: {
    // 快照里的 queue item 被 Fh.extend 收窄为下面 3 值
    state: 'queued' | 'reserved' | 'promoting';
    reservationId?: string;
  };
  admittedAt: number;
  highspeed?: {
    schemaVersion: 1;
    cardId: string;
    taskId: string;
    provider: string;
    model: string;
    issuedAt: number;
    expiresAt: number;
    regularTps?: number;
    outputTokens?: number;
    durationMs?: number;
    highspeedTps?: number;
    savedDurationMs?: number;
    modelDurationMs?: number;
    toolDurationMs?: number;
  };
  provenance?: {
    sourceCommandId: string;
    queueItemId?: string;
    clientId?: string;
  };
  source?:
    | { kind: 'pluginUi'; pluginId: string; toolCallId: string }
    | { kind: 'genUi'; path: string; title?: string };
  toolDisallowlist?: string[];
};
```

`Attachment`（队列/输入共用）：

```ts
type Attachment = {
  ref: string;
  fileName: string;
  mime: string;
  bytes: number;
  sourceKind?: 'clipboard-text' | 'topic-history';
  messageCount?: number;
  previewRef?: string;
};
```

### 3.3 队列相关 op

| op | args | 线上命令 / payload | UI 语义 |
| --- | --- | --- | --- |
| `sendText` | `sessionId`, `text`, `attachments?`, `heldQueueDisposition?`, `expectedHeldQueueItemIds?`, `automationId?`, `offPeakTaskId?`, `offPeakRunType?`, `botDeliveryTarget?`, `toolDisallowlist?` | `sendText` | 发送；队列冲突时带 disposition |
| `sendGoalCommand` | `sessionId`, `text`, `displayText?`, `heldQueueDisposition?`, `expectedHeldQueueItemIds?` | `sendGoalCommand` | `/goal` 发送 |
| `sendQueuedNow` | `sessionId`, `queueItemId` | `{queueItemId}` | 立即发送某条排队消息 |
| `editQueueItem` | `sessionId`, `queueItemId`, `newText` | `{queueItemId,newText}` | 编辑排队消息 |
| `deleteQueueItem` | `sessionId`, `queueItemId` | `{queueItemId}` | 删除排队消息 |
| `setAutoDrain` | `sessionId`, `autoDrain` | `{autoDrain}` | 自动排空开关 |
| `sendCommand` | `sessionId?`, `type:"reorderQueueItem"`, `payload:{queueItemId, beforeQueueItemId:string\|null}` | 逃生口 | 拖拽排序（没有专用 op） |
| `optimisticRemoveQueueItem` | `sessionId`, `queueItemId` | 无 RPC | 本地先删；服务端 `queue` patch 随后确认 |

Rust 分发：`conversation_transport.rs:1323-1472`、`:1726-1745`；payload 构造：
`conversation.rs:358-410`、`:473-492`。

### 3.4 `heldQueueDisposition` 与 `expectedHeldQueueItemIds`

网页端确认逻辑（`index-BGa3KiRe.js` @2,306,900–2,310,000）：

```text
inputRouting.mode === 'choice' 且 queue.items 非空
  → 弹「排队发送 / 立即发送」
  → clearQueueAndSend：清空 held queue 后插队执行
  → keepQueueAndSend ：保留 held queue，按队列顺序发送
  → 同时把用户看到的 queueItemIds 作为 expectedHeldQueueItemIds 带上
```

- `heldQueueDisposition` 取值只有：`"clearQueueAndSend"` / `"keepQueueAndSend"`。
- `expectedHeldQueueItemIds` 是**用户确认时看到的 queue item id 列表**；服务端用它判断队列是否已变化
  （变化时会要求重新确认，网页端收到 `confirmationRequired` 后带最新 id 重发）。
- `inputRouting.mode === "reject"` 时不要发（composer 应禁用/提示）；
  `"choice"` 才弹选择；`"startNow"`/`"enqueue"`/`"guide"` 直接按当前语义发送。
- 注意：网页端还会发 `requestedDelivery`（`auto`/`startNow`/`queue`/`guide`），
  但**我们的 `sendText` op 没有暴露这个参数**（Rust `send_text_payload` 不构造它）。
  目前 UI 只能依赖 `inputRouting` + `config.followupMode`；这是已知能力缺口，不要伪造该字段。

---

## 4. `rowsRange`、`historyExhausted`、`row.removed`

### 4.1 请求参数

FFI op：`docs/FFI.md` §13.5.1；Rust 分发：`conversation_transport.rs:1567-1580`；
参数构造：`conversation.rs:566-580`。

```json
{"op":"rowsRange","args":{
  "sessionId":"...",
  "beforeRowId": 123,     // 可选；只取 rowId < beforeRowId
  "limit": 60             // 可选；默认 60
}}
```

线上（`conversationRowsRangeV4`）：

```json
{"...scope","sessionId":"...","beforeRowId":123,"limit":60}
```

约束：
- `limit` 服务端夹到 **1..200**（`rowsRangeMaxLimit = 200`）；默认值由客户端给，Rust 默认 **60**
  （`ROWS_RANGE_DEFAULT_LIMIT`，`conversation.rs:104`）。
- 我们不发 `clientMode`；服务端缺省按 `replayable` 处理（`archive/pre-3.16.1-auth/docs/desktop/09-对话服务端.md` §5.1）。
- `beforeRowId` 传 `state.oldestRowId`；不要传 `firstRowId`（两者语义不同）。

### 4.2 返回

```json
{
  "rows": [ Row, ... ],       // rowId 升序；长度 <= limit
  "atSeq": 1234,
  "atRevision": 56,
  "atLogEpoch": "epoch-...",
  "hasMore": true
}
```

服务端算法（`archive/pre-3.16.1-auth/docs/desktop/09-对话服务端.md` §5.2）：

```js
c = beforeRowId === undefined ? window : window.filter(r => r.rowId < beforeRowId)
u = c.slice(-limit)
hasMore = c.length > u.length
```

⇒ `rows` 是「满足条件的那段的**尾部**」，不是头部。

Rust 返回：`Completion::Value(res)`，**原样**，不自动并入 state
（`conversation_transport.rs:2026-2035`）。UI 必须自己合并。

### 4.3 UI 合并算法（照网页端双 CAS）

1. 发请求前记下 `before = state.oldestRowId`。
2. 收到应答：
   - `atLogEpoch !== state.logEpoch` → **整批丢弃**（网页端 CAS-1）。
   - `state.oldestRowId !== before` → **整批丢弃**（网页端 CAS-2：窗口已前移）。
   - `atSeq` / `atRevision` **不参与校验**（网页端也不读）。
3. 取 `rows`（升序），调用：

```json
{"op":"prependOlderRows","args":{
  "sessionId":"...",
  "rows":[ ... ],
  "newFirstRowId": null
}}
```

- Rust 的 `prepend_older_rows` 会按 `rowId` 去重（缺失 rowId 视为同一个键），只前插新行，
  不排序；所以 UI 必须保证传入升序。
- `newFirstRowId` 通常传 `null`；传了会覆盖 `firstRowId`（服务端应答里没有这个字段）。
- `prependOlderRows` **不改 `totalCount`**。
4. `hasMore === false` → 调 `setHistoryExhausted {sessionId, value:true}`。
5. 若 `rows` 为空但 `hasMore === false`，同样置 `historyExhausted = true`，并提示「没有更早的消息了」。

### 4.4 `historyExhausted` 的判定

- **线上不存在这个字段**（`grep 'historyExhausted' zcode.cjs` = 0；`archive/pre-3.16.1-auth/docs/desktop/09` §5.2）。
- 它是 `ConversationState.history_exhausted`，默认 `false`，只由 UI 通过 op 写入：
  `setHistoryExhausted {sessionId, value}`（`conversation_transport.rs:1640-1656`）。
- 推荐判定：**每次 `rowsRange` 返回 `hasMore === false` 时置 `true`**。
- `canLoadOlder` = `!historyExhausted && firstRowId != null && totalCount > rows.length`。
- 注意：`apply_snapshot` **不会重置** `historyExhausted`。换会话、`logEpoch` 变化、
  或重新订阅拿到全新快照时，UI 必须自己把它重置为 `false`，否则会永久禁止翻页。

### 4.5 `row.removed` 的语义

线上 op：`{op:"row.removed", fromRowId: number}`。

Rust（`conversation.rs:1718-1738`）：

```text
保留 rowId < fromRowId 的行（即删除 rowId >= fromRowId 的所有行）
removed = 原窗口行数 - 保留行数
if firstRowId != null && fromRowId <= firstRowId:
    totalCount = 0
    firstRowId = null
else:
    totalCount = max(0, totalCount - removed)
```

要点：
- 不是「删一条」，是**按 id 截断**。
- 窗口里被删掉的行数只用于扣 `totalCount`；窗口外的行不计。
- `fromRowId <= firstRowId` 表示整个投影头都被截掉 → 清零。
- 该 op **幂等**（重复应用结果相同）。

### 4.6 其他行 op 的精确语义（实现时必须照抄）

| op | 语义 | 边界 |
| --- | --- | --- |
| `row.appended` | 追加到 `rows` 末尾；`totalCount += 1`；`firstRowId` 为空时设为新行 `rowId` | 不去重；重复到达会产生重复行 |
| `row.upserted` | 在窗口里按 `rowId` 找；找到则整行替换；**找不到就整体忽略**（不插入、不报错） | `rowId` 缺失时 `null == null` 会命中第一条缺 rowId 的行（Dart 怪癖，Rust 照抄） |
| `row.delta` | 行必须在窗口内；否则丢弃。按 path 追加（见 §7） | 类型不匹配 → 原行不变，静默丢弃 |
| `state.updated` | 顶层浅替换进 `snapshot`；快照未到时先攒 `pendingPatch`，快照落地时合并 | 不校验 `revision`；后写覆盖 |
| `pluginUi.*` | no-op（本项目无插件 UI） | 服务端无门控必发，静默丢弃 |
| `workflowRun.*` | no-op；我们未声明 `workflowRunDeltas`，服务端会合并成 `state.updated`，实际不会到达 | — |

批量归约是**顺序左折叠**，顺序敏感。

---

## 5. `state.updated.patch` 中 UI 需要响应的字段

### 5.1 合并语义（先记住这三条）

1. **顶层浅替换**：`for (k,v) in patch { snapshot[k] = v }`。`config`/`queue`/`control` 等对象
   必须整包给，UI 不要做深合并。
2. **不校验 `revision`**：没有 CAS；后到的 patch 覆盖先到的。
3. **快照未到时缓冲**：`pendingPatch` 累积，`apply_snapshot` 时把 patch 覆盖到快照上；
   但 `logEpoch` 和 `rows` 仍从**原始快照**读。

Rust：`conversation.rs:1755-1770`、`:1632-1639`。

### 5.2 逐字段形状与 UI 响应

| patch 键 | 形状 | UI 必须响应什么 |
| --- | --- | --- |
| `control` | 见下 `Control` | 刷新 `phase`、`isRunning`、`canStop`、停止按钮、运行态/错误条 |
| `availability` | 见下 `Availability` | 逐项启用/禁用操作按钮；禁用时显示 `reasonCode` |
| `inputRouting` | `{mode:'startNow'\|'enqueue'\|'guide'\|'reject'\|'choice', reasonCode?:string}` | 决定 composer 行为：直接发/入队/引导/禁用/弹队列选择 |
| `queue` | 见 §3.1 `Queue` | 整包替换队列条；`autoDrain` 开关；`pauseReason` 提示 |
| `pendingInteractions` | 见 §2.1 `PendingInteraction[]` | 整包替换交互卡片；空数组 = 全部消失 |
| `usage` | 见下 `Usage` | 上下文占用/累计 token 展示 |
| `plan` | 见下 `Plan` | 计划步骤列表（步骤状态变化） |
| `goal` | 见下 `Goal` | 目标横幅/目标状态/验证结果 |
| `backgroundWorks` | 见下 `BackgroundWork[]` | 后台任务列表（bash/subagent/workflow） |
| `subagents` | 见下 `Subagents` | 子代理运行/等待/阻塞指示 |
| `config` | 见 §1.4 `Config` | 模型/思考等级/协作模式/后续消息模式；`thoughtLevels` 决定思考档位选项 |
| `meta` | `{title:string, titleSource:'default'\|'generated'\|'custom'}` | 标题 |
| `modelTransition` | `{eventId, origin:'registryFallback', from:{provider,model}, to:{provider,model}}\|null` | 模型切换提示（可选） |
| `pendingCommands` | `{commandId, clientId, type, state:'accepted'\|'executing', at}[]` | 命令执行中指示（可选；服务端最多 32 条） |
| `revision` | `number` | 更新本地 CAS 基准（Rust 内部也维护；UI 仅 `fileChanges` 用得到） |
| `sharedContextImport` | 分享导入对象（可选） | 分享上下文提示（可选） |
| `workflowRuns` | 工作流运行集合（可选） | 本项目不声明能力，通常不会到达 |
| `workspaceHookAdmission` | `{pendingCount:number, bundleDigest:string, workspaceIdentity?:string}\|null` | 工作区 Hook 审核提示（可选） |

`Control`（`validation-CqXZOLXO.js` @183,0xx `kh`）：

```ts
type Control = {
  phase: 'draft' | 'prewarming' | 'running' | 'completedSuccess' | 'completedInterrupted' | 'error';
  sessionEnded: boolean;
  canStop: boolean;
  stopState: 'idle' | 'stoppable' | 'stopping';
  stopTargetKind: 'primaryTurn' | 'foregroundSubagent' | 'compact' | 'goalVerifier'
                | 'goalContinuation' | 'turnSteer' | 'mixed' | 'unknown';
  activeWorks: Array<{
    kind: 'primaryTurn' | 'foregroundSubagent' | 'compact' | 'goalVerifier'
        | 'goalContinuation' | 'turnSteer';
    foregroundExecutionId?: string;
    sourceCommandId?: string;
    startedAt: number;
  }>;
  lastError: {
    code: string; message: string; recoverable: boolean; at: number;
    source: 'provider' | 'runtime' | 'tool' | 'network';
    traceId?: string; detail?: any;
    underlyingErrorMessage?: string; underlyingErrorDetail?: string;
    attribution?: object;
  } | null;
  apiRetry: { attempt: number; maxAttempts: number; nextRetryAt: number; reasonCode: string } | null;
};
```

`Availability`（`jh`）：

```ts
type Availability = {
  fork:              { allowed: true } | { allowed: false; reasonCode: string };
  compact:           { allowed: true } | { allowed: false; reasonCode: string };
  switchModelConfig: { allowed: true } | { allowed: false; reasonCode: string };
  setFollowupMode:   { allowed: true } | { allowed: false; reasonCode: string };
  queueEdit:         { allowed: true } | { allowed: false; reasonCode: string };
  sendQueuedNow:     { allowed: true } | { allowed: false; reasonCode: string };
  pauseGoal:         { allowed: true } | { allowed: false; reasonCode: string };
  resumeGoal:        { allowed: true } | { allowed: false; reasonCode: string };
};
```

`Usage`（`Ph`）：

```ts
type Usage = {
  contextWindow: {
    usedTokens: number;
    maxTokens: number;
    autoCompactThresholdTokens: number | null;
    cache?: unknown;
    breakdown?: unknown;
  } | null;
  cumulative: {
    inputTokens: number;
    outputTokens: number;
    cacheReadTokens: number;
    cacheWriteTokens: number;
  };
};
```

`Plan`（`Qh`）：

```ts
type Plan = {
  items: Array<{ id: string; content: string; status: 'pending' | 'inProgress' | 'completed' }>;
  updatedAt: number;
};
```

`Goal`（`Zh`）：

```ts
type Goal = {
  targetId: string;                 // 默认 ''
  objective: string;
  summaryTitle: string | null;
  timeUsedSeconds: number;
  activeRunStartedAtMs: number | null;
  status: 'active' | 'paused' | 'verifying' | 'verified' | 'notSatisfied' | 'failed';
  iteration: number;
  verifications: Array<{
    iteration: number;
    outcome: 'pass' | 'notSatisfied' | 'failed';
    at: number;
    anchorRowId: number | null;
    reason?: string;
    nextAction?: string;
  }>;
  iterations: Array<{
    iteration: number;
    items: Array<{ id: string; content: string; status: 'pending' | 'inProgress' | 'completed' }>;
    updatedAt: number;
  }>;
};
```

`BackgroundWork`（`Kh`）：

```ts
type BackgroundWork = {
  workId: string;
  kind: 'bash' | 'subagent' | 'workflow';
  title: string;
  status: 'running' | 'resultPending' | 'failed' | 'cancelled';
  startedAt: number;
  endedAt?: number;
  cancellable?: boolean;
  blocked?: boolean;
  anchorRowId: number | null;
  childSessionId?: string;
};
```

`Subagents`（`Jh`）：

```ts
type Subagents = {
  revision: number;
  childSessionIds: string[];
  running: Array<{
    childSessionId: string;
    agentId?: string;
    toolCallId?: string;
    subagentType: string;
    title: string;
    summary?: string;
    status: 'running' | 'waiting' | 'blocked';
    startedAt?: number;
  }>;
  endedTotal: number;
};
```

### 5.3 哪些 patch 会让服务端 bump `revision`

服务端 `deltaBumpsRevision`（`archive/pre-3.16.1-auth/docs/desktop/09-对话服务端.md` §2.3）：

```text
control, availability, inputRouting, meta, config, queue,
pendingInteractions, backgroundWorks, subagents, goal, plan
```

- `usage`、`revision`、`modelTransition`、`pendingCommands` 等**不在**这个集合里。
- `row.*` / `pluginUi.*` / `workflowRun.*` 一律不 bump。
- UI 不需要自己算 revision；Rust 从快照/ack 维护。

### 5.4 宿主看到的更新事件

`rowsUpdated`（`conversation_transport.rs:320-335`）：

```json
{"type":"rowsUpdated","sessionId":"...","frame":{...}|null,"ready":true,
 "seq":123,"totalCount":456,"firstRowId":1,"rowCount":60}
```

- `frame` 是刚应用的**逻辑帧**：`{topic, subscriptionId, fromSeq, toSeq, sentAt, payload}`。
- `payload.kind` 为 `snapshot` 或 `deltas`；`state.updated` 在 `deltas` 数组里。
- 状态写入类 op（`setHistoryExhausted` / `prependOlderRows` / `optimistic*`）触发的更新
  `frame === null`。
- **`frame` 里没有 `deliveryKind`**（见 §8）。
- UI 处理 `state.updated` 的推荐路径：遍历 `frame.payload.deltas`，把 `op === "state.updated"`
  的 `patch` 顶层浅替换到本地会话状态；不要等 `snapshot()`（FFI 目前拿不到）。

---

## 6. 行基础字段 `actions` 与对应 op

行基座（`archive/pre-3.16.1-auth/docs/remote-v4/18` §5；`archive/pre-3.16.1-auth/docs/desktop/09` §3）：

```ts
type RowBase = {
  rowId: number;
  turnId: string;
  entityId?: string;
  productTurnId?: string;
  visibility?: 'visible';
  createdAt: number;
  createdAtSeq: number;
  actions?: {
    canFork?: boolean;
    canEdit?: boolean;
    canRetry?: boolean;
    canRewindFiles?: boolean;
    editDisposition?: 'rewind' | 'fork';
  };
};
```

| actions 字段 | op | args | 线上命令 | ack 结果 |
| --- | --- | --- | --- | --- |
| `canFork === true` | `forkAssistant` | `{sessionId, target}` | `forkAssistant`，CAS（自动带 `baseRevision` + `baseLogEpoch`） | `result.sessionId`（新会话） |
| `canEdit === true` | `editUserQuery` | `{sessionId, target, newText}` | `editUserQuery`，CAS | `result.disposition`（`rewind`/`fork` 等） |
| `canRetry === true` | `retryTurn` | `{sessionId, target}` | `retryTurn`，CAS | — |
| `canRewindFiles === true` | `applyFileRewind` | `{sessionId, target}` | `applyFileRewind`，CAS | `result {applied, preview, response}` |
| 预览（可选） | `fileRewindPreview` | `{sessionId, target, baseRevision?, baseLogEpoch?}` | `conversationFileRewindPreviewV4` | 原始应答 |
| 查看变更（可选） | `fileChanges` | `{sessionId, target, baseRevision?, baseLogEpoch?}` | `conversationFileChangesV4` | 原始应答 |
| 点赞/点踩（非 actions） | `setAssistantFeedback` | `{sessionId, target, feedback?: 'like'\|'dislike'\|null}` | `setAssistantFeedback`，CAS | — |

`target` 统一构造（参考 `Reference_code/lib/ui/chat_page.dart:1633-1637`）：

```ts
{ rowId: row.rowId, entityId?: row.entityId }
```

要点：
- `forkAssistant` / `editUserQuery` / `retryTurn` / `applyFileRewind` 都是 **CAS 命令**
  （`CAS_COMMANDS` 15 个），Rust 自动从状态里取 `baseRevision`/`baseLogEpoch` 并处理
  `stale` 重试一次；UI **不要**自己传这两个字段。
- `fileChanges` / `fileRewindPreview` 不是 CAS 命令，但服务端会校验 `baseLogEpoch` →
  `baseRevision` → 行目标；UI 应传 `state.logEpoch` / `state.revision` 以避免 `staleLogEpoch` /
  `staleRevision`。
- `editDisposition` 是 schema 里的**预计算提示**（`'rewind'|'fork'`），3.15.3 网页主包
  对它 **0 引用**；没有单独的「按 disposition 选择 op」逻辑。编辑动作始终走 `editUserQuery`，
  真正的处置结果看 ack 的 `result.disposition`。UI 可以只用它来给按钮打标签/二次确认。
- `actions` 缺失或字段为 `false` → 不显示对应操作。
- 行类型不同，可用的 action 也不同；`canFork`/`canRetry` 通常出现在 assistant 行，
  `canEdit` 通常出现在 `userInput` 行。以服务端给的布尔值为准，不要按 kind 猜。

---

## 7. 流式 delta 的 path 全集与追加语义

线上枚举（`validation-CqXZOLXO.js` @116,963）：**只有 4 个值**：

```text
text | inputText | output.text | summaryText
```

Rust 实现：`conversation.rs:2094-2139`（`append_to_row`）；应用点 `:1739-1754`。

| path | 目标行 kind | 追加规则 | 不匹配时 |
| --- | --- | --- | --- |
| `text` | `assistantText` **或** `reasoning` | `row.text = (row.text ?? "") + append` | 原行不变 |
| `inputText` | `toolCall` | `row.inputText = (row.inputText ?? "") + append` | 原行不变 |
| `output.text` | `toolCall` **且 `row.output` 是对象** | `row.output.text = (row.output.text ?? "") + append` | 原行不变（**不会创建 `output`**） |
| `summaryText` | `subagent` | `row.summaryText = (row.summaryText ?? "") + append` | 原行不变 |
| 其他（不在枚举内） | — | — | 原行不变（静默） |

其他边界：
- `append` 缺失/`null` → 按空串处理。
- 行不在当前窗口 → **整条 delta 丢弃**（不会缓存、不会补行）。
- 类型不匹配（例如 `text` 打到 `toolCall`）→ 静默丢弃，不抛错。
- `row.delta` 是**纯追加**，不可重放；重复帧会导致文本重复。去重只能靠帧级 `toSeq <= local.seq`。
- 移动端 profile 可能只收到 `text` 的 delta（服务端 `streamPaths`：`replayable` 只开 `text`），
  `inputText` / `output.text` / `summaryText` 会通过 `row.upserted` 整行覆盖到达。
  **UI 不能假设这 4 种 delta 都会来；必须同时支持整行 upsert。**

### 7.1 行类型全集（9 种，UI 分派用）

`turnHeader`、`userInput`、`assistantText`、`reasoning`、`toolCall`、`artifact`、`subagent`、
`hookInvocation`、`timelineMarker`。

流式判据（网页 `Knn`，`archive/pre-3.16.1-auth/docs/remote-v4/18` §5）：

```text
assistantText / reasoning → state === 'streaming'
toolCall                  → backgrounded !== true && status ∈ {inputStreaming, pendingApproval, running}
subagent                  → backgrounded !== true && status === 'running'
timelineMarker            → 'status' in marker && marker.status === 'running'
```

注意：Rust 的 `hasStreamingRow` 只看 `row.state === 'streaming'`，**不覆盖 toolCall/subagent**。

---

## 8. 「静默接受历史」与「新消息到达但不抢滚动」有没有协议标记？

### 8.1 结论

**没有专用标记。** 协议层没有 `silent`、`isHistory`、`shouldScroll`、`isInitial` 之类的字段。
可用的只有下面这些**间接信号**：

| 信号 | 位置 | 能说明什么 | 局限 |
| --- | --- | --- | --- |
| `payload.kind === "snapshot"` | 逻辑帧 | 这是一次「全量状态」，通常是订阅初始帧、resync、或缓冲溢出后的重发 | **不等于历史**：resync/overflow 也会发 snapshot |
| `payload.kind === "deltas"` | 逻辑帧 | 增量；`row.appended` 可能是新消息，也可能是别的行 | 不区分「历史补拉」与「实时新消息」 |
| `deliveryKind` | **线上信封**：`initial`/`online`/`recovery` | `initial` = subscribe 初始帧；`online` = 定时 flush；`recovery` = resync | **我们的 Rust 在 `route_wire_frame` 拆信封时丢掉**（`conversation.rs:1284-1311`、`:1349-1374`）；`RowsUpdated.frame` 里没有它 |
| `row.visibility` | 行基座 | schema 只有字面量 `'visible'` | 不是「静默」开关；服务端内部有 `visible/stateOnly/modelOnly`，但线上只发 `visible` |
| `userInput.origin` | `userInput` 行 | `realUser` / `backgroundResult` / `goalContinuation` / `mailbox` / `synthetic` / `workflowLaunch` | 最接近「非用户主动消息」的判据 |
| `userInput.inputOrigin` | `userInput` 行 | `desktop` / `mobile` | 区分哪一端发的 |
| `userInput.clientId` | `userInput` 行 | 发送端 clientId | 可与 `snapshot().clientId` 比较，识别「本机发送」 |
| `userInput.guided` | `userInput` 行 | 是否为引导消息 | 可选，缺失即 false |
| `userInput.originMeta` | `userInput` 行 | `{backgroundSource:'bash'\|'subagent'\|'workflow', workId?, senderSessionId?, senderLabel?}` | 后台结果来源 |
| `subscribe.visibility` | 订阅参数 `foreground`/`background` | 网页/桌面会带 | **服务端 `subscribeReserved` 未读取**（`archive/pre-3.16.1-auth/docs/desktop/09` §9.2）；我们的 Rust 也不发 |

### 8.2 实现建议（把「协议没有」变成 UI 的确定性规则）

**静默接受历史（初始 hydration 不当作新消息）：**

1. 会话页打开/重订阅后，记录「基线时刻」：第一次收到 `payload.kind === "snapshot"` 时，
   把窗口内所有行标记为 `baseline`，**不播放入场动画、不触发未读、不滚动**。
2. 之后的 `row.appended` 才视为「新到达」。
3. 如果收到新的 snapshot（resync/overflow），用 `snapshot.logEpoch` 与旧值比较：
   - 同 epoch → 视为同一会话的重新同步，保留 UI 状态（滚动位置、未读）；
   - epoch 变化 → 视为新会话/重建，重置 `historyExhausted`、未读、滚动。
4. 不要依赖 `deliveryKind`（我们拿不到）；也不要依赖 `row.visibility`（只有 `visible`）。

**新消息到达但不抢滚动：**

1. 滚动策略完全由 UI 的「是否跟随底部」状态决定（网页端是 `data-following` + 虚拟列表，
   `zw`/`H1e` 只是本地判据，不是协议字段）。
2. 用户已向上滚动（不跟随）时，任何 `row.appended` 都**不滚动**；只更新「回到底部」按钮/未读数。
3. 需要区分「本机刚发的消息」与「其他来源」时，用：
   - 本地 echo（发送时先插入的临时行）匹配 `rowId`/`text`；
   - 或 `userInput.origin === 'realUser'` 且 `userInput.clientId === snapshot().clientId`
     （本机）→ 可以吸底；
   - `origin !== 'realUser'`（`backgroundResult`/`goalContinuation`/`mailbox`/`synthetic`/
     `workflowLaunch`）或 `inputOrigin === 'desktop'`（另一端）→ 默认不抢滚动。
4. `state.updated` 引起的布局变化（plan/goal/queue/pendingInteractions 出现）也不要自动吸底；
   只有用户本来就在底部时才保持吸底。

---

## 9. 实现清单（按 UI 模块）

### 9.1 会话状态容器

- [ ] 保存 `rows`、`seq`、`logEpoch`、`firstRowId`、`totalCount`、`ready`、`historyExhausted`。
- [ ] 保存 `snapshot`（或至少 `control/config/queue/pendingInteractions/usage/plan/goal/backgroundWorks/subagents/meta`）。
- [ ] 实现 §4.6 的 5 种 op 归约，顺序左折叠。
- [ ] 实现 §7 的 4 种 delta 追加，类型不匹配静默丢弃。
- [ ] 收到 `state.updated` 时顶层浅替换，并重算所有派生值。
- [ ] `historyExhausted` 只在 `hasMore === false` 时置 true；换会话/epoch 变化时重置 false。

### 9.2 时间线

- [ ] 按 `rowId` 升序渲染；虚拟列表 overscan 参考网页 8（可选）。
- [ ] 行分派覆盖 9 种 kind；未知 kind 渲染为通用占位，不崩。
- [ ] `row.upserted` 找不到目标行时忽略（不要追加）。
- [ ] `row.removed` 按 `rowId >= fromRowId` 截断，并同步 `totalCount`/`firstRowId`。
- [ ] 流式：`assistantText/reasoning` 看 `state`；`toolCall` 看 `status`；`subagent` 看 `status`。
- [ ] `output.text` delta 只在 `output` 已存在时生效。

### 9.3 输入区

- [ ] 根据 `inputRouting.mode` 决定：直接发 / 入队 / 引导 / 禁用 / 弹队列选择。
- [ ] `mode === 'choice'` 且队列非空时，弹「排队发送 / 立即发送」。
- [ ] 选择结果映射为 `heldQueueDisposition`：
      `clearQueueAndSend` / `keepQueueAndSend`；同时带 `expectedHeldQueueItemIds`。
- [ ] 不要伪造 `requestedDelivery`（我们的 op 不支持）。
- [ ] `availability` 控制模型/模式/停止/队列操作按钮。

### 9.4 队列条

- [ ] 用 `queue.items` 渲染；`text`、`attachments`、`kind`、`order.queuePosition`。
- [ ] `sendQueuedNow` / `editQueueItem` / `deleteQueueItem` 前可先乐观更新；
      服务端 `queue` patch 会整包覆盖确认。
- [ ] `setAutoDrain` 切换；`autoDrain` 缺省 true。
- [ ] 拖拽排序用 `sendCommand {type:"reorderQueueItem", payload:{queueItemId, beforeQueueItemId}}`。

### 9.5 交互卡片

- [ ] 按 `pendingInteractions[0]` 渲染（或全部渲染，按产品决定）。
- [ ] `kind === 'permission'`：标题用 `toolName`，正文用 `summary`，按钮来自 `options`；
      `freeText === true` 时显示输入框；`fullAccessOption` 单独渲染。
- [ ] `kind === 'userInput'`：正文用 `prompt`；有 `questions` 时渲染表单（`multiSelect`）；
      否则渲染 `options`；`freeText === true` 时显示输入框。
- [ ] 提交时按 §2.6 组装 `resolveInteraction`。
- [ ] `autoResolution` 存在时显示倒计时/稍后提醒（可选）。

### 9.6 历史分页

- [ ] 触发条件：`canLoadOlder === true` 且用户滚到顶部。
- [ ] 请求 `rowsRange {sessionId, beforeRowId: oldestRowId, limit: 60}`（上限 200）。
- [ ] 校验 `atLogEpoch` 与 `oldestRowId` 双 CAS，失败整批丢弃。
- [ ] 成功调 `prependOlderRows {rows, newFirstRowId: null}`。
- [ ] `hasMore === false` → `setHistoryExhausted {value:true}`。

---

## 10. 证据索引

| 主题 | 位置 |
| --- | --- |
| `ConversationState` 字段 | `rust/crates/protocol/src/conversation.rs:1551-1571` |
| 快照应用 / pendingPatch | `conversation.rs:1632-1689` |
| 5 种 op 归约 | `conversation.rs:1692-1782` |
| 派生 getter | `conversation.rs:1852-2088` |
| `append_to_row` | `conversation.rs:2094-2139` |
| `rowsRange` 参数 | `conversation.rs:566-580` |
| `resolveInteraction` payload | `conversation.rs:509-532` |
| `sendText` payload | `conversation.rs:358-410` |
| `conversations[sessionId]` 组装 | `conversation_transport.rs:3843-3898` |
| `snapshot()` 顶层 | `conversation_transport.rs:1146-1189` |
| `OPS` 全集（40） | `conversation_transport.rs:934-981` |
| `rowsRange` 分发 | `conversation_transport.rs:1567-1580` |
| `setHistoryExhausted` / `prependOlderRows` | `conversation_transport.rs:1640-1680` |
| `RowsUpdated` 事件 | `conversation_transport.rs:320-335`、`:3493-3519` |
| `SimpleCall` 原样返回 | `conversation_transport.rs:2026-2035` |
| FFI 无对话查询入口 | `rust/crates/ffi/src/session.rs:121-130`；`docs/FFI.md` §14.1 |
| 线上 op 全集（11） | `archive/pre-3.16.1-auth/docs/desktop/09-对话服务端.md` §2.3；`archive/pre-3.16.1-auth/docs/remote-v4/18` §1.2 |
| `state.updated.patch` 键集 | `archive/pre-3.16.1-auth/docs/desktop/09` §2.3；`archive/pre-3.16.1-auth/docs/remote-v4/18` §1.2 |
| 行类型 9 种 / actions | `archive/pre-3.16.1-auth/docs/remote-v4/18` §5；`archive/pre-3.16.1-auth/docs/desktop/09` §3 |
| `rowsRange` 服务端算法 | `archive/pre-3.16.1-auth/docs/desktop/09` §5.2 |
| `historyExhausted` 非线上字段 | `archive/pre-3.16.1-auth/docs/desktop/09` §5.2、§8.6 |
| `deliveryKind` | `archive/pre-3.16.1-auth/docs/remote-v4/18` §1.1；`archive/pre-3.16.1-auth/docs/desktop/09` §2.2 |
| `visibility` 未被服务端读取 | `archive/pre-3.16.1-auth/docs/desktop/09` §9.2 |
| 队列 schema `Qp/Fh` | `validation-CqXZOLXO.js` @162,5xx–163,4xx、@185,6xx |
| 交互 schema `Wh/zh/Hh/Hf` | `validation-CqXZOLXO.js` @185,7xx–186,9xx |
| 配置/用量/控制/计划/目标 | `validation-CqXZOLXO.js` @183,0xx–189,4xx |
| 网页 held queue 确认 | `index-BGa3KiRe.js` @2,306,900–2,310,000 |
| 参考 UI 交互/队列/编辑 | `Reference_code/lib/ui/chat_page.dart:73-86`、`:3122-3270`、`:3420-3660`、`:1633-1745` |
