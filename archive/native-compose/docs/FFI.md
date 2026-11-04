# FFI 契约：Rust 协议核心 ↔ Kotlin 宿主

**这是冻结接口。** Rust 侧（`rust/crates/ffi`）与 Kotlin 侧（`android/.../core/`）按本文各自实现，
两边都不需要看对方代码就能对齐。改动本文必须先改双方。

---

## 1. 边界与核心设计

### 1.1 零回调

协议 crate 的 `RelayTransport` 是个**回调 trait**（`connect` / `send_text` / `close`），按常理
Kotlin 要实现它。**我们不这么做。**

Rust 侧提供一个**记录型 transport**（`RecordingTransport`）：它不碰网络，只把这三个动作**记下来**，
随 `step()` 的返回值一起当事件吐给宿主。于是：

- Kotlin 侧**不实现任何 Rust trait**，只做两件事：处理事件、喂回输入。
- FFI 面全是 `String`（JSON）进、`String`（JSON）出，没有对象生命周期、没有回调线程、没有 JNI 局部引用管理。
- 协议 crate 一行不改（311 个测试不受影响）。

代价：一次 socket 动作要多走一趟 JSON。这个开销相对网络往返可以忽略。

### 1.2 谁在驱动

宿主（Kotlin）负责**全部 I/O 与时钟**：WebSocket 收发、定时器、持久化。核心是纯状态机：

```
宿主                                   核心
  │  nativeStep(handle, inputJson, now)  │
  │ ────────────────────────────────────>│  纯计算
  │  {"ok":true,"events":[...]}          │
  │ <────────────────────────────────────│
  │                                      │
  ├─ 按 events 执行：连/发/关、起/撤定时器
  └─ 收到 socket 帧或定时器到点 → 再 nativeStep
```

**时间一律由宿主传入**（`nowMs`），核心内部不读时钟。这让整条链路可复现、可测试。

---

## 2. JNI 符号表

- Kotlin 类：`app.zemote.core.NativeCore`（源文件 `android/app/src/main/kotlin/app/zemote/core/NativeCore.kt`）
- 库名：`zemote_ffi` → `System.loadLibrary("zemote_ffi")` → `libzemote_ffi.so`
- Rust crate：`rust/crates/ffi`，package `zemote-ffi`，`crate-type = ["cdylib", "rlib"]`

全部是**静态** native 方法（Kotlin `object` 里的 `external fun`），故 JNI 第二参为 `jclass`。

| JNI 符号 | Kotlin 签名 | JNI 描述符 |
|---|---|---|
| `Java_app_zemote_core_NativeCore_nativeAbiVersion` | `(): String` | `()Ljava/lang/String;` |
| `Java_app_zemote_core_NativeCore_nativeCreate` | `(String): String` | `(Ljava/lang/String;)Ljava/lang/String;` |
| `Java_app_zemote_core_NativeCore_nativeStep` | `(Long, String, Long): String` | `(JLjava/lang/String;J)Ljava/lang/String;` |
| `Java_app_zemote_core_NativeCore_nativeCall` | `(Long, String, Long): String` | `(JLjava/lang/String;J)Ljava/lang/String;` |
| `Java_app_zemote_core_NativeCore_nativeQuery` | `(Long, String): String` | `(JLjava/lang/String;)Ljava/lang/String;` |
| `Java_app_zemote_core_NativeCore_nativeDispose` | `(Long, Long): String` | `(JJ)Ljava/lang/String;` |

Kotlin 的 `Long` 是 JNI 的 `jlong`（描述符 `J`），不是对象类型 —— 这点容易写错。

**所有方法都不抛异常**（Rust 侧 `catch_unwind`），失败通过返回信封表达。

---

## 3. 返回信封

**每一个** native 方法的返回值都是 JSON 对象：

```json
{"ok": true, "events": [ ... ]}
{"ok": false, "error": {"kind": "panic", "message": "..."}, "events": []}
```

- `error.kind` 取值：`panic`（Rust panic，已捕获）／`badInput`（JSON 解析失败或缺字段）／
  `timeout`／`invalidState`／`protocol`（后三者来自 `ZemoteError`）。
- `events` **始终存在**（可能为空数组），即使 `ok=false` —— 出错前已产生的事件不能丢。
- **不省略键**：`Option::None` 一律序列化成 `null`，不跳过字段。Kotlin 侧解析因此不需要处理缺键。

---

## 4. `nativeAbiVersion()`

```json
{"ok": true, "events": [], "abi": 2, "protocolVersion": "0.1.0", "features": ["conversation", "automation"]}
```

（`events` 键按 §3 的约定**始终存在**，这里是空数组 —— 该方法只读版本，不驱动核心。）

`abi` 是本文的版本号：**改任何 schema 就 +1**。Kotlin 启动时核对，不一致就直接报错而不是
带着错位的结构跑下去。`features` 列出该 .so 编译进去的可选能力。

---

## 5. `nativeCreate(configJson)`

```json
{
  "url": "https://zcode.z.ai/remote/v4?app_version=3.16.1&sid=...&mid=...&name=...",
  "nowMs": 1760000000000,
  "isWeb": false,
  "targetPlatform": "android",
  "appVersion": "1.0.0",
  "deviceName": "Pixel 8",
  "theme": "dark",
  "mid": "可选，覆盖 URL 里的 mid",
  "token": "ws2 terminal token（宿主用 OAuth JWT 换得；旧链接带 hash 时可省略）"
}
```

- `url` 必填，其余可选。用 `ZemoteConnectionParams::parse(url)` 解析；若给了
  `appVersion` / `deviceName` / `theme` / `mid`，则**覆盖**解析出来的对应字段
  （`app_version` / `device_name` / `theme` / `device_mid`）。
- `url` 只强制要求 `sid`。3.15.x 的 `hash` / `t` 变为可选；3.16.1 的新链接不带它们。
- `token` 是 **ws2 terminal token**，由宿主登录后注入，**不来自 URL**。宿主先：
  `POST /api/v1/remote/token`（`Authorization: Bearer <OAuth JWT>`，body `{"role":"terminal"}`）
  拿到 22 字符短 token。给了 `token` 时：
  - relay 地址为 `wss://<host>/api/v1/remote/ws2?token=<terminal token>&mid=…`；
  - `auth_init` 不带 `role`，`client_ts` 为 Unix **秒**；
  - 不再走 `auth_challenge` / HMAC proof。
  没给 `token` 且 URL 里也没有 `hash` 时，`nativeCreate` 返回 `badInput`（`dryRun` 除外）。
- `targetPlatform` 取值：`android` | `ios` | `windows` | `macos` | `linux` | `fuchsia`，默认 `android`。
- `dryRun` 见下。
- 解析失败 → `{"ok":false,"error":{"kind":"badInput",...}}`。

**安全**：`token` 只存在于本次 `nativeCreate` 的配置里；`snapshot` / `dryRun.parsed`
都不会回传它，relay 日志里的 query 值一律脱敏为 `<redacted>`。

返回：

```json
{"ok": true, "handle": 1, "events": [ ... ]}
```

`handle` 是 u64，进程内唯一、单调递增。**`events` 里通常已经有内容**——构造过程本身会产生事件
（如 relay 的 `TimerStart`、日志），宿主必须处理，不能丢。

### 5.1 `dryRun`：只解析，不建会话

```json
{"url": "...", "dryRun": true}
```

```json
{"ok": true, "dryRun": true, "handle": null, "events": [],
 "parsed": {
   "deviceSid": "...", "deviceMid": null, "deviceName": null,
   "appVersion": null, "theme": null, "timestamp": null,
   "host": "zcode.z.ai", "scheme": "https",
   "relayUri": "wss://zcode.z.ai/api/v1/remote/ws2?mid=..."
 }}
```

**为什么需要它**：配对界面要即时告诉用户"这个链接是给哪台设备的"、并算出设备去重键。
如果 Kotlin 自己实现一套 query 解码（键解码、重复键后者胜、`+`→空格、空串视为不存在），
那就是**在 Kotlin 里重复实现协议语义**，早晚与 Rust 漂移。让 Rust 权威解析一次即可消除这类 bug。

约定：

- `dryRun=true` 时**不建会话、不占 handle**（`handle` 为 `null`），也不产生任何事件。
- `dryRun` 只要求 `sid`：3.16.1 新链接在登录前就能验证（认证材料由后续 `token` 提供）。
- `parsed` 的字段与 §9 `snapshot.params` **完全一致**，额外给出 `host` / `scheme` / `relayUri`
  （配对层要用前两者做去重键与展示）。`relayUri` 在 token 模式下指向 ws2，但**不含 token**。
- `dryRun` 为 `false` 或缺省时行为**完全不变**。
- 解析失败仍是 `badInput`。
- 注意 `dryRun` **不等于**"这个链接一定可用"：它只说明 URL 结构可解析。真正的可用性由
  `nativeCreate` 建会话后的握手结果决定。

---

## 6. `nativeStep(handle, inputJson, nowMs)`

`inputJson` 是 `{"type":"...", ...}` 的对象，逐个映射 `ZemoteInput` / `RelayInput`：

| JSON | 对应 Rust | 说明 |
|---|---|---|
| `{"type":"start"}` | `RelayInput::Start` | 开始连接（对应 Dart `start()`） |
| `{"type":"socket_ready","generation":N}` | `SocketReady` | 建连成功 |
| `{"type":"socket_failed","generation":N,"error":"..."}` | `SocketFailed` | 建连失败 |
| `{"type":"text_frame","generation":N,"text":"..."}` | `TextFrame` | 收到文本帧 |
| `{"type":"binary_frame","generation":N,"base64":"..."}` | `BinaryFrame` | 收到二进制帧（**base64**，宿主不要预解码） |
| `{"type":"socket_closed","generation":N,"code":1006,"reason":null}` | `SocketClosed` | 连接关闭 |
| `{"type":"relay_timer_fired","timer":"heartbeat"}` | `TimerFired(RelayTimer)` | `timer`: `heartbeat`/`waiting`/`reconnect`/`rewait` |
| `{"type":"send_payload","payload":{...}}` | `SendPayload` | 逃生舱；**正常路径不要用**（会绕过门面监听语义），宿主请用 `nativeCall` 的方法 |
| `{"type":"poke"}` | `Poke` | 回前台探活 |
| `{"type":"debug_drop_socket"}` | `DebugDropSocket` | 诊断用，强制掉线演练重连 |
| `{"type":"dispose"}` | `Dispose` | 退出 |
| `{"type":"timer_fired","token":N}` | `ZemoteInput::TimerFired` | 门面自己的定时器到点 |

`generation` 是**连接代次**，由核心在 `ws_connect` 事件里给出，宿主原样回传。代次不符的输入会被
核心忽略（这是防止旧 socket 的迟到帧污染新连接状态）。

未知 `type` → `{"ok":false,"error":{"kind":"badInput",...}}`。

---

## 7. 输出事件

`step` / `call` / `dispose` 返回的 `events` 数组。**命名规则**：Rust 枚举变体名转 `snake_case`
作为 `type`；字段名转 `camelCase`；`Value` / `Payload` / `Map` 原样内联为 JSON 对象；
`Option::None` → `null`（键不省）。

### 7.1 传输动作（来自 `RecordingTransport`，宿主必须执行）

```json
{"type":"ws_connect","generation":1,"uri":"wss://..."}
{"type":"ws_send_text","generation":1,"text":"<完整 JSON 文本>"}
{"type":"ws_close","generation":1,"code":3000,"reason":"debug-drop"}
```

`ws_close` 的 `code`/`reason` 可为 `null`（对应 Dart 的无参 `sink.close()`）。
`ws_send_text` 的 `text` 是**已序列化好的紧凑 JSON**，宿主直接发，不要重新序列化。

### 7.2 relay 层（`RelayEvent`）

```json
{"type":"relay_state","state":"paired"}
{"type":"relay_log","line":"..."}
{"type":"relay_payload","payload":{...}}
{"type":"relay_failure","reason":"...","message":null}
{"type":"relay_timer_start","timer":"heartbeat","delayMs":10000,"repeat":true}
{"type":"relay_timer_cancel","timer":"heartbeat"}
```

`relay_state` 取值：`idle` / `connecting` / `authenticating` / `waiting` / `paired` /
`reconnecting` / `error` / `kicked` / `closed`。

**定时器语义**：`relay_timer_start` 的 `repeat=true` 表示周期定时器（宿主自行重排），
`false` 表示单次；`relay_timer_cancel` **幂等**，槽位为空时是 no-op，宿主按幂等实现即可。

### 7.3 门面层（`ZemoteEvent`）

```json
{"type":"log","line":"..."}
{"type":"timer_start","token":7,"delayMs":30000}
{"type":"timer_cancel","token":7}
{"type":"workspace_list_updated","value":{...}}
{"type":"bridge_degraded","session":1,"degraded":"reopen-failed: ..."}
{"type":"app_error","reason":"desktop-disconnected","error":"..."}
{"type":"bridge_recovered","session":1,"count":2}
{"type":"bridge_stack_attached","session":1}
{"type":"bridge_stack_disposed","session":1}
{"type":"conversation_opened","session":1,"key":"...","scope":{...}}
{"type":"channel","event":{ ... }}
{"type":"completed","token":"...","outcome":{ ... }}
```

- `bridge_degraded` 的 `degraded` 为 `null` 表示**已恢复健康**。
- `timer_start` 的 `token` 是门面自己的定时器句柄，到点回
  `{"type":"timer_fired","token":N}`。**relay 的定时器槽位与门面的 token 是两套独立机制**，
  不要混用。
- `channel.event` 与 `completed.outcome` 见下。

### 7.4 `channel.event`（`ChannelEvent`）

按同一命名规则展开（`type` 为 snake_case 变体名，字段 camelCase）。已知形态：

```json
{"type":"promise_success","id":1,"data":{...}}
{"type":"promise_error","id":1,"error":{"message":"...","data":{...}}}
{"type":"promise_error_obj","id":1,"error":{"message":"...","data":{...}}}
{"type":"event_fire","id":1,"data":{...}}
```

实现时以 `channel_client.rs` 里 `ChannelEvent` 的**全部**变体为准，不要只照抄上面四条。

### 7.5 `completed.outcome`（`Completion`）

```json
{"kind":"payload","value":{...}}
{"kind":"bridge_opened","session":1}
{"kind":"ok"}
{"kind":"failed","error":{"kind":"timeout","message":"..."}}
```

`failed.error.kind` 取值：`timeout` / `invalidState` / `protocol`。

---

## 8. `nativeCall(handle, commandJson, nowMs)`

```json
{"method":"bootstrap"}
{"method":"list_workspaces"}
{"method":"open_bridge","workspaceKey":"wk","taskId":null,"timeoutMs":30000}
{"method":"reconnect_workspace","workspaceKey":"wk"}
{"method":"send_mobile_view_state","workspaceKey":"wk","taskId":null}
{"method":"connect"}
{"method":"poke_relay"}
{"method":"wait_paired","timeoutMs":30000}
{"method":"bridge_wait_healthy","session":1,"timeoutMs":45000}
{"method":"request","payload":{...},"matchTypes":["..."],"matchFields":{...},"timeoutMs":30000}
```

- 未给的 `timeoutMs` 用 Dart 侧的默认值（实现时读 `zemote_client.rs` 的常量）。
- `request` 是逃生舱，映射 `ZemoteClient::request(payload, PayloadMatcher, timeout_ms, now_ms)`；
  `matchTypes` 对应 `PayloadMatcher::new(&[...])`，`matchFields` 对应逐个 `.with_field(k, v)`。
- **打开对话不在这里**：统一用 §13.2 的 `conversation_open` / `conversation_call` /
  `conversation_close`。本文早期草稿在 §8 里放过一个 `conversation` 命令（直接映射核心的
  `ZemoteClient::conversation`），**已废弃** —— 同一件事有两个名字，只会让人用错那个没有
  配套 `op` 分发与事件路由的。若实现里还留着它，删掉。

返回：

```json
{"ok": true, "operation": "bootstrap-abc123", "events": [ ... ]}
{"ok": true, "operation": null, "events": [ ... ]}
{"ok": false, "error": {"kind": "invalidState", "message": "..."}, "events": [ ... ]}
```

`operation` 是**挂起操作的关联令牌**（字符串）。宿主拿它跟后续 `completed` 事件的 `token` 对上，
从而 resolve 自己的 `Deferred`/`CompletableDeferred`。`connect` / `poke_relay` /
`send_mobile_view_state` 这类不产生挂起操作的返回 `operation: null`。

---

## 9. `nativeQuery(handle, queryJson)`

```json
{"query":"snapshot"}
```

返回：

```json
{"ok": true, "snapshot": {
  "disposed": false,
  "relay": {"state": "paired", "wasPaired": true},
  "deviceInfo": {"platform": "android", "version": "1.0.0", "name": "zemote"},
  "params": {
    "deviceSid": "...", "deviceMid": null, "deviceName": null,
    "appVersion": null, "theme": null, "timestamp": 0
  },
  "bridges": [{
    "session": 1, "wireBridgeSessionId": "...", "workspaceKey": "wk",
    "initialTaskId": null, "degraded": null, "recovered": 0,
    "disposed": false, "conversationKeys": ["..."]
  }],
  "recovering": [1]
}}
```

**安全约定：`passHash` 绝不出现在任何返回值里。** Kotlin 侧不需要它，导出它只会扩大泄漏面。

未知 `query` → `{"ok":false,"error":{"kind":"badInput",...}}`。

### 9.1 对话层快照（abi 2）

核心的 `snapshot` 只有 relay/桥状态；`conversations` 与 `sessionsIndex`（含
`lastAssistantPreview`）属于对话层的 `ConversationTransport`，用这条查询取：

```json
{"query":"conversation","session":1,"key":"<conversation key>"}
```

`session` 是 `open_bridge` 返回的桥句柄，`key` 是 `conversation_open` 返回的 key。
返回 `{"ok":true,"snapshot":{...}}`，结构见 `ConversationTransport::snapshot`（顶层键
`key` / `clientId` / `appVersion` / `handshaken` / `connectionId` / `bridgeDegraded` /
`ackedRevisions` / `conversations` / `sessionsIndex` / `workspacePrep`）。

- 缺 `session` / `key` 或类型不符 → `badInput`；
- 该 conversation 没 open 过或已 close → `invalidState`（不是协议错误）。

---

## 10. `nativeDispose(handle, nowMs)`

销毁会话：核心 `dispose(now_ms)` → 事件；然后从注册表移除 handle。
返回 `{"ok":true,"events":[...]}`。对已销毁的 handle 再调用返回
`{"ok":false,"error":{"kind":"invalidState","message":"..."}}`（**幂等失败，不 panic**）。

---

## 11. 线程与并发

- 会话注册表是 `static` + `Mutex`；每个 handle 的操作在**该会话自己的锁**内串行执行。
- **同一个 handle 上的调用不可重入**：`step` 执行期间若宿主在同一线程回调另一个 native 方法，
  会死锁。**Kotlin 侧必须把单个 handle 的所有 native 调用收敛到同一个单线程调度器**
  （`Dispatchers.Default.limitedParallelism(1)` 或专用单线程 dispatcher）。
- 不同 handle 之间无共享可变状态，可并行。
- 锁中毒（某次调用 panic 导致）用 `PoisonError::into_inner()` 恢复，**不要**让会话永久不可用。

---

## 12. panic 与错误处理约定

- 每个 JNI 入口都包 `std::panic::catch_unwind`。这是 `Cargo.toml` 里**刻意保留
  `panic = "unwind"`**（未设 `panic = "abort"`）的原因：解析网络来的字节，任何越界或 `unwrap`
  都可能 panic，abort 会让整个应用 SIGABRT，unwind 则能转成一次失败调用。
- panic 时返回 `{"ok":false,"error":{"kind":"panic","message":"..."}}`。
  **不要**把 panic 信息里的路径/内部结构原样透给 UI 之外的地方（日志可以，用户可见文案要收敛）。
- 输入 JSON 解析失败一律 `badInput`，不 panic。

---

## 13. 对话层与定时任务层（`op` 通用透传面）

### 13.1 为什么是"通用透传"而不是逐个方法

Dart 的 `ConversationTransport` 与 `AutomationClient` 里有几十个方法（发消息、建会话、切模型、
订阅、行区间、附件分块、CAS 重试、看门狗……）。**不要在 FFI 上把它们逐个展开**：那样每加一个
命令都要改 Kotlin，而且 Kotlin 会开始理解协议细节——这正是重写要避免的。

改用 `op` 判别式：Kotlin 只透传 `op` 名字与参数，**协议细节全在 Rust**。

### 13.2 对话层

```json
{"method":"conversation_open","session":1,"scope":{...}}
{"method":"conversation_call","session":1,"key":"<conversation_open 返回的 operation>","op":"sendText","args":{...}}
{"method":"conversation_close","session":1,"key":"..."}
```

- `conversation_open` 返回的 `operation` 就是 **conversation key**（与 Dart
  `conversation_key(scope)` 一致），后续用它引用。
- `conversation_call` 的 `op` 与 `args` 由 Rust 侧解释；未知 `op` → `badInput`。
- `op` 清单**以 Rust 实现为准**（`rust/crates/protocol/src/conversation_transport.rs` 的文档头），
  实现者负责在本文 §13.5 补一张 `op → args → 语义` 的表。Kotlin 侧只做透传与结果渲染。

对话事件：

```json
{"type":"conversation_event","session":1,"key":"...","event":{"type":"...", ...}}
```

`event` 的内容由 Rust 侧状态机产出（行增量、快照、握手完成、订阅状态、附件进度、错误等），
同样**以 Rust 实现为准**并补进 §13.5。

### 13.3 定时任务层

```json
{"method":"automation_call","session":1,"op":"list","args":{}}
{"method":"automation_call","session":1,"op":"create","args":{...}}
{"method":"automation_call","session":1,"op":"setEnabled","args":{"automationId":"...","enabled":true}}
{"method":"automation_call","session":1,"op":"delete","args":{"automationId":"..."}}
{"method":"automation_call","session":1,"op":"runNow","args":{"automationId":"..."}}
{"method":"automation_call","session":1,"op":"restart","args":{"automationId":"..."}}
```

结果通过 `completed` 事件的 `outcome.value` 返回（与其它挂起操作同一套机制）。

### 13.4 新增事件汇总

```json
{"type":"conversation_event","session":1,"key":"...","event":{...}}
```

### 13.5 `op` 与对话事件清单（**由实现者填写**）

> 实现 `conversation_transport.rs` 与 FFI 接线时，把 `op` 表与对话事件表补在这里。
> 在补完之前，Kotlin 侧只依赖 §13.2 / §13.3 的**信封形状**，不依赖具体 `op`。

**实现状态**：已完成 —— `rust/crates/protocol/src/conversation_transport.rs`（79 条 `#[test]`）。
**本节以 Rust 实现为准**（`ConversationTransport::OPS`）。

#### 13.5.1 `conversation_call` 的 `op` 清单

`args` 一律是 JSON 对象（缺省或 `null` 视为 `{}`）；键名与 Dart 具名参数同名。
未列出的键一律忽略；必填键缺失或类型不符 → `{"ok":false,"error":{"kind":"badInput",...}}`。
命令类 `op` 的 `timeoutSecs` 默认 30（对应 `sendCommand` 的 `timeout` 参数）。

| `op` | `args` | 语义（对应 Dart） |
|---|---|---|
| `handshake` | — | `handshake()`：`helloConversationV4` → `initializeConversationV4`。幂等（已握手立即 `completed`） |
| `subscribe` | `sessionId` | `subscribe(sessionId)`：注册 `onDynamicConversationFrame` 监听器 → `subscribeConversationV4` |
| `subscribeSessionsIndex` | — | `subscribeSessionsIndex()`：监听 `onDynamicSessionsIndexFrame` → `subscribeSessionsIndexV4`（`runtimePolicy: existing-only`） |
| `disposeSubscription` | `sessionId` | `ConversationSubscription.dispose()`：取消定时器/监听器并发 `unsubscribeConversationV4` |
| `disposeSessionsIndex` | — | 对全部 sessions-index 订阅同上 |
| `sendCommand` | `sessionId?`, `type`, `payload?`, `timeoutSecs?` | 通用逃生口（Dart 公开的 `sendCommand`），覆盖没有包装方法的命令（如 `reorderQueueItem`） |
| `sendText` | `sessionId`, `text`, `attachments?`, `heldQueueDisposition?`, `expectedHeldQueueItemIds?`, `automationId?`, `offPeakTaskId?`, `offPeakRunType?`, `botDeliveryTarget?`, `toolDisallowlist?` | 命令 `sendText` |
| `sendGoalCommand` | `sessionId`, `text`, `displayText?`, `heldQueueDisposition?`, `expectedHeldQueueItemIds?` | 命令 `sendGoalCommand` |
| `pauseGoal` / `resumeGoal` / `stop` / `compact` | `sessionId` | 同名命令，空 payload |
| `switchModelConfig` | `sessionId`, `provider`, `model`, `thought` | 命令 `switchModelConfig`；应答 message 含 `Unsupported reasoning effort` 时自动换另一族 thought 重发一次 |
| `switchCollaborationMode` | `sessionId`, `mode` | build / edit / plan / yolo |
| `setFollowupMode` | `sessionId`, `mode` | queue / guide |
| `setAssistantFeedback` | `sessionId`, `target`, `feedback?` | `feedback` 缺省时线上**显式**写 `"feedback": null` |
| `retryTurn` / `forkAssistant` / `applyFileRewind` | `sessionId`, `target` | 行目标命令（带 `baseRevision` + `baseLogEpoch`） |
| `editUserQuery` | `sessionId`, `target`, `newText` | 同上 |
| `sendQueuedNow` / `deleteQueueItem` | `sessionId`, `queueItemId` | |
| `editQueueItem` | `sessionId`, `queueItemId`, `newText` | |
| `setAutoDrain` | `sessionId`, `autoDrain` | |
| `createSession` | `workspaceId`, `firstText?`, `attachments?`, `config?`, `runtimeModel?`, `mcpServers?`, `timeoutSecs?`（默认 90） | 成功 → `outcome.sessionId` |
| `createSelectionSideSession` | `parentSessionId`, `timeoutSecs?`（默认 60） | `accepted` 或 `duplicate` 都算成功 |
| `resolveInteraction` | `sessionId`, `interactionId`, `optionId?`, `freeText?`, `action?`, `content?` | |
| `plans` | `sessionId` | `conversationPlansV4`，原样返回应答 |
| `fileChanges` | `sessionId`, `target`, `baseRevision?`, `baseLogEpoch?` | `conversationFileChangesV4` |
| `fileRewindPreview` | 同 `fileChanges` | `conversationFileRewindPreviewV4` |
| `rowsRange` | `sessionId`, `beforeRowId?`, `limit?`（默认 60） | `conversationRowsRangeV4`；结果**不自动**并入 state，取老行后需再调 `prependOlderRows` |
| `attachmentPut` | `sessionId`, `fileName`, `mime`, `dataBase64` | begin → chunk（384 KiB/片）→ commit；成功 → `outcome.attachmentDescriptor`；进度经 `attachmentProgress` 事件 |
| `attachmentRead` | `sessionId`, `ref` | 循环 `attachmentReadV4`（`limit` = 512 KiB，最多 60 轮 = 30 MiB）；成功 → `outcome.attachmentBytes` |
| `prepareWorkspace` | `refresh?`（默认 false） | **不发 RPC**：对端 3.15.3 没有 `zcode-task.prepareWorkspace`（只有 `zcode-agent.prepareStorage`，语义不同）。直接回空 prep 并缓存；`refresh:true` 只重新生成。真正的 model/mode 与 slash 命令待 `queryConversationCommandsV4` 接线 |
| `skills` | — | 通道 `skills` 的 `list`（`provider: glm`）；返回过滤掉空名的技能数组 |
| `setHistoryExhausted` | `sessionId`, `value` | 对应 UI 写 `state.historyExhausted` |
| `prependOlderRows` | `sessionId`, `rows`, `newFirstRowId?` | 对应 `state.prependOlderRows`（按 rowId 去重） |
| `optimisticPatch` | `sessionId`, `patch` | 无 snapshot 时静默跳过（Dart 同） |
| `optimisticRowUpdate` | `sessionId`, `rowId?`, `patch` | |
| `optimisticRemoveQueueItem` | `sessionId`, `queueItemId` | |

#### 13.5.2 结果与对话事件

`conversation_call` 返回的 `events` 里，每个被接受的 `op` 先给一条 `opAccepted`，结束时给一条 `completed`：

```json
{"type":"opAccepted","token":"sendText:4","op":"sendText"}
{"type":"completed","token":"sendText:4","outcome":{"type":"value","value":{...}}}
```

`token` 由 Rust 生成（形如 `"<op>:<n>"`），Kotlin 原样保留、在 `completed` 里对上即可。

`outcome.type`：

| `type` | 字段 | 对应 Dart |
|---|---|---|
| `ok` | — | `Future<void>`（握手 / 订阅 / 状态写入） |
| `value` | `value` | `dynamic`（命令应答 / plans / rowsRange / skills / prepareWorkspace / fileChanges） |
| `sessionId` | `value` | `createSession` / `createSelectionSideSession` |
| `attachmentDescriptor` | `value` = `{ref, fileName, mime, bytes}` | `attachmentPut` |
| `attachmentBytes` | `base64`, `mediaType` | `attachmentRead` |
| `failed` | `error` = `{kind, message}`；`kind` ∈ `invalidState`(StateError) / `timeout`(TimeoutException) / `channel`(ChannelRpcError) / `protocol` | 抛出的异常 |

`{"type":"conversation_event","session":1,"key":"...","event":{...}}` 里的 `event`：

| `type` | 字段 | 对应 Dart | Kotlin 侧动作 |
|---|---|---|---|
| `log` | `line` | `_log` → `onLog` | 记日志 |
| `timerStart` | `token`(数字), `delayMs` | `Timer.periodic` / `Timer` | 按 `delayMs` 起计时器；**同一 token 再次出现 = 重新装弹**（周期定时器） |
| `timerCancel` | `token` | `Timer.cancel()` | 取消该 token |
| `stateChanged` | — | `notifyListeners()`（非行类） | 重读 `snapshot()` |
| `rowsUpdated` | `sessionId`, `frame`, `ready`, `seq`, `totalCount`, `firstRowId`, `rowCount` | `ConversationState.notifyListeners()` | 按 `frame.payload.kind`（`snapshot`/`deltas`）增量渲染；`frame` 为 `null` 表示来自状态写入类 op；要完整行窗口读 `snapshot()` |
| `sessionsIndexUpdated` | `workspaceId`, `logEpoch`, `seq`, `ready`, `sessionCount` | `SessionsIndexState.notifyListeners()` | 重渲染任务列表（条目在 `snapshot().sessionsIndex[i].list`） |
| `handshakeDone` | `connectionId`（可为 `null`） | `_handshaken = true` | 记录；`null` = 服务端 hello 没给 `connectionId` |
| `opAccepted` | `token`, `op` | 方法返回的 `Future` | 记下 token |
| `completed` | `token`, `outcome` | Future 完成 / 抛错 | 按上表渲染 |
| `listenChannel` | `token`(数字), `channel`, `event`, `arg` | `addEventListener(channel, event, handler, arg: scope)` | 调 `channels.add_listener(channel, event, arg)`，**拿到 id 后必须回喂** `listener_registered{token, id}`，否则订阅流程挂起 |
| `unlistenChannel` | `id` | 取消函数 → `reqEventDispose` | 调 `channels.cancel_listener(id)` |
| `subscribed` | `sessionId`（sessions-index 为 `null`）, `subscriptionId` | `ack.subscriptionId` | 记录 |
| `waitHealthy` | `token`(数字), `timeoutMs` | `session.waitHealthy(45s)` | 调 `bridge_wait_healthy`；完成喂 `bridge_healthy{token}`，超时喂 `bridge_healthy_timeout{token}` |
| `attachmentProgress` | `token`, `sessionId`, `uploadId`, `progress` | `onProgress` | 更新进度条 |

### `ConversationInput`：**内部**输入，不是宿主输入

下面这张表描述的是 `ConversationTransport::step` 需要哪些输入。**它不是宿主契约** ——
`nativeStep` 的输入表（§6）一个字都没变，Kotlin 侧不需要、也无法构造这些输入。

原因：`listenerRegistered` / `channelTimeout` / `bridgeHealthy` 都需要核心 `ChannelClient` 内部的
请求 id 或挂起项，**宿主按 §6 根本拿不到**；把这些写进宿主契约等于给宿主派一个不可能完成的义务。
所以全部由 FFI 层（`rust/crates/ffi/src/conversation.rs`）在内部构造并直接喂给 conversation。

| 内部输入 | 何时喂 | 对应 Dart |
|---|---|---|
| `channel` | 路由到本 conversation 的 `ChannelEvent`（先用 `owns_channel_id(id)` 判断） | `_handlers[id]` 回调 |
| `timerFired{token}` | 上面 `timerStart` 的计时器到点（由 FFI 层的 token 重映射还原） | `Timer` 回调 |
| `bridgeRecovered` | `ZemoteEvent::BridgeRecovered` | `session.recovered` 监听 |
| `bridgeDegraded{degraded}` | `ZemoteEvent::BridgeDegraded`（`degraded: null` = 恢复健康） | `session.degraded.value` |
| `listenerRegistered{token, id}` | `listenChannel` 注册完成 —— **FFI 层调 `add_listener` 拿到 id 后自己回喂** | `addEventListener` 的返回值 |
| `channelTimeout{id}` | 该请求的 `timeout_ms` 到点（**先 `remove_pending(id)`**）—— FFI 层的 deadline 清扫 | `Future.timeout` 抛 `TimeoutException` |
| `bridgeHealthy{token}` / `bridgeHealthyTimeout{token}` | `waitHealthy` 的结果 —— FFI 层调 `bridge_wait_healthy` 后的 `completed` | `waitHealthy` 完成 / 超时 |

**路由**：`owns_channel_id(id)` 对「本对象当前仍挂起的通道请求 id」与「本对象发起的监听器 id」返回 `true`；
已结束的请求返回 `false`（Dart 里 `_handlers.remove(id)` 之后同 id 的响应会被静默忽略）。

#### 13.5.3 `snapshot()` 字段

```json
{
  "key": "<conversation key>", "clientId": "...", "appVersion": "3.6.5",
  "handshaken": false, "connectionId": null, "bridgeDegraded": null,
  "ackedRevisions": {"<sessionId>": 0},
  "conversations": {"<sessionId>": { "...": "见下" }},
  "sessionsIndex": [ { "...": "见下" } ],
  "workspacePrep": null
}
```

`conversations[<sessionId>]` —— `ConversationState` 的全部字段与派生 getter，外加本模块的订阅元数据：

`snapshot`, `rows`, `seq`, `logEpoch`, `firstRowId`, `totalCount`, `ready`, `historyExhausted`,
`control`, `revision`, `phase`, `canStop`, `isRunning`, `config`, `currentModel`, `currentThought`,
`currentMode`, `thoughtLevels`, `queue`, `queueItems`, `autoDrain`, `usage`, `canLoadOlder`,
`oldestRowId`, `backgroundWorks`, `goal`, `plan`, `inputRoutingMode`, `pendingInteractions`,
`hasStreamingRow`, `subscriptionId`, `disposed`, `started`, `listenerId`, `resyncing`,
`stagedFrameCount`, `pendingFragmentCount`, `lastFrameAtMs`。

已 dispose 的订阅不出现在 `conversations` 里（对应 Dart `_subscriptions.remove(...)`）。

`sessionsIndex[i]`：

`subscriptionId`, `disposed`, `started`, `listenerId`, `resyncing`, `lastFrameAtMs`,
`workspaceId`, `logEpoch`, `seq`, `ready`, `sessionCount`, `list`
（`list` 是原始会话条目，按 `lastActivityAt` 倒序）。

`workspacePrep`：`prepareWorkspace` 的缓存原始应答（未取过为 `null`）。

---

## 14. 待办与已知缺口

§13 接线落地后，实现者报回的、**当前有意不修**的缺口。都不是阻塞项，但接手前应当知道。

### 14.1 对话层的快照没有查询入口

`conversation_transport.rs` 有 `snapshot()`（§13.5.3 列了字段），但 §9 的 `nativeQuery` 只暴露核心的
快照。**UI 拿不到对话的完整行窗口**。

当前不改的理由：对话的行数据本来就通过 `conversation_event` 的 `rows_updated` 流式推给宿主，
新建/重连后也会收到完整快照帧，所以事件流已经够用；而加一个查询入口要动 `abi`（进而要同步改
已冻结的 Kotlin 引擎），为一个暂时没有消费者的能力付这个代价不划算。

要加的时候：`{"query":"conversation","session":N,"key":"..."}` → `{"ok":true,"snapshot":{...}}`，
并把 `abi` 升到 2。

### 14.2 多桥同时打开时，通道事件的路由有歧义

核心的 `ZemoteEvent::Channel(ChannelEvent)` **不带桥句柄**，而每个桥的 `ChannelClient` 各自从 1 开始
计数通道请求 id。于是多桥同时打开时，同一个 id 可能属于两个桥的 conversation。

当前做法：按桥句柄定序取首个 `owns_channel_id(id)` 命中的 conversation。**单桥场景完全正确**，
多桥是已知局限。根治要在协议 crate 里给 `ZemoteEvent::Channel` 带上 session（那是一次协议 crate 改动）。

### 14.3 `conversation_close` 不为在飞操作发失败

`ConversationTransport::dispose` 不发失败事件（与 Dart `BridgeSession.dispose` 一致）。因此关闭对话后，
宿主的 `Deferred` 只能靠**自己的兜底超时**收场。Kotlin 引擎已按此实现（超时 = 命令超时 + 宽限），
新增对话调用时别把兜底去掉。

### 14.4 对端有、我们有意不实现的对话能力

2026-10-17 对桌面端 3.15.3（`app.asar`，build `d7ca8bee`）的逆向结论。

| 能力 | 现状 | 为什么不做 |
|---|---|---|
| `pluginUi.resourceUpdated` / `resourceListChanged` / `instanceClosed` / `appToolCall` | reducer 的 `_ => {}` 静默忽略 | 只影响插件渲染的面板，本项目没有插件 UI 层 |
| `workflowRun.updated` / `removed` | 同上，而且**根本不会到达** | 我们不在 `clientHello.capabilities` 里声明 `workflowRunDeltas`，服务端会合并成一条 `state.updated` |
| `attachmentAbortV4` | 未实现 | 对端有、参考 Dart 没有；上传失败时服务端 staging 会保留 5 分钟后自行回收 |
| `queryConversationCommandsV4` | 未实现 | slash 命令目录；也是 `prepareWorkspace` 空转的替代方案 |
| `platform-request` / `telemetry-report` / `mobile-diagnostic` | 从不发 | 平台能力通道 / 诊断上报；不影响连通性 |

`prepareWorkspace` 已改为**不发 RPC**（见 §13.5.1 的 op 表）：对端 3.15.3 没有这个方法，
参考 Dart 一直在调一个不存在的名字，两个调用点都用 `catch (_) {}` 吞掉，所以该功能一直是静默失效的。

### 14.5 同一个完成事件发两遍（**有意为之**）

对话层的操作完成会同时出现在：
- 顶层 `completed`（§7.5）—— 宿主按令牌 resolve `Deferred` 用；
- `conversation_event` 内部的 `completed`（§13.5.2）—— 让"只订阅某一个对话"的消费者自成闭环，
  不必回头去顶层事件里按令牌捞。

两者是有意重复，不是 bug。若将来 UI 因此重复处理，优先改消费侧，不要在这里删一条——删掉任一条都会
打断其中一种消费方式。

### 14.6 其他

- `features` 现在如实返回 `["conversation","automation"]`。
- `wait_paired` / `bridge_wait_healthy` 之外的长时操作超时，全部由门面自己的定时器事件驱动，
  宿主只需按时回喂 `timer_fired`。
- `automation_call` 的 workspace scope 在 §13.3 没有出处：实现按 `args.scope` 取，缺省回落该桥的
  `workspaceKey`。若将来发现服务端需要别的口径，改这里并 +1 `abi`。
