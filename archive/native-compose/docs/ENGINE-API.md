# 引擎层公开 API（给 ViewModel 层）

> 实现：`android/app/src/main/kotlin/app/zemote/engine/`
> 契约依据：`docs/FFI.md`（冻结）。本文只描述 **Kotlin 侧怎么用**，不重复协议细节。

---

## 1. 它在哪一层

```
UI / ViewModel
   ↕  本文档描述的 API（StateFlow / SharedFlow / suspend 函数）
ZemoteEngine                     ← 事件循环 + 单线程调度 + 定时器 + OkHttp WebSocket
   ↕  NativeBridge（String in / String out，同步）
Rust 协议核心 libzemote_ffi.so
```

| 文件 | 职责 |
|---|---|
| `core/NativeCore.kt` | JNI 面，6 个 `external fun`（签名对应 FFI.md §2，勿改） |
| `core/NativeBridge.kt` | `NativeBridge` 接口 + `JniNativeBridge` 真实实现 |
| `engine/EngineModels.kt` | 信封、事件、状态、输入构造与解析 |
| `engine/ZemoteEngine.kt` | 引擎本体（唯一入口） |
| `engine/RelayWebSocket.kt` | `ws_connect` / `ws_send_text` / `ws_close` 的 OkHttp 实现 |
| `engine/TimerScheduler.kt` | 两套独立定时器命名空间 |

---

## 2. 装配

```kotlin
class RemoteViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = ZemoteEngine(
        bridge = JniNativeBridge(),
        scope = viewModelScope,        // 引擎的定时器与回喂任务挂在它下面
    )

    fun connect(url: String) {
        engine.start(
            EngineConfig(
                url = url,                          // 深链解析出的 https://zcode.z.ai/remote/v4?... 原样传
                token = terminalToken,              // 3.16.1：用 OAuth JWT 换来的 ws2 短 token；旧链接可为 null
                appVersion = BuildConfig.VERSION_NAME,
                deviceName = Build.MODEL,
                theme = if (isSystemInDarkTheme()) "dark" else "light",
            )
        )
    }

    override fun onCleared() {
        engine.dispose()
    }
}
```

**`scope` 的语义**：引擎的定时器与回喂任务是这个 scope 的子任务。取消它等于取消引擎（
`dispose()` 仍应调用，以便把 `nativeDispose` 送进核心）。

---

## 3. 公开 API

### 3.1 构造

```kotlin
class ZemoteEngine(
    bridge: NativeBridge,
    scope: CoroutineScope,
    clock: () -> Long = System::currentTimeMillis,
    dispatcher: CoroutineDispatcher? = null,   // 仅供测试 / 嵌入式宿主注入，见 §6
)
```

`clock` 是**唯一**的取时入口：契约要求时间一律由宿主传入（`nowMs`），核心不读时钟。
单测注入固定值即可让整条链路可复现。

### 3.2 生命周期

```kotlin
fun start(config: EngineConfig)   // 非挂起，幂等；进度看 state
fun dispose()                     // 非挂起，幂等；销毁后引擎不可复用
fun close()                       // = dispose()
```

`start` 的步骤：核对 ABI → `nativeCreate` → 处理构造事件 → 发 `{"type":"start"}`。
`dispose` 的步骤：`nativeDispose` → 处理其事件 → 撤全部定时器 / 关全部 socket →
唤醒所有等待中的挂起调用 → `state = Disposed` → 关闭自建的 dispatcher。

`start` 是**非挂起**的：调用后立刻返回，用 `state` / `relayState` 观察进度。重复调用被忽略。

### 3.3 状态

```kotlin
val state:        StateFlow<EngineState>                 // 引擎生命周期
val relayState:   StateFlow<RelayState>                  // relay 状态机
val relayFailure: StateFlow<EngineEvent.RelayFailure?>   // 最近一次 relay_failure（错误条）
val lastError:    StateFlow<NativeError?>                // 最近一次 native ok=false
val events:       SharedFlow<EngineEvent>                // 全部输出事件的旁路流
val logs:         StateFlow<List<String>>                // 最近 500 行日志（调试页）
```

```kotlin
sealed interface EngineState {
    data object Idle : EngineState
    data object Starting : EngineState
    data class  Running(val handle: Long) : EngineState
    data class  Fatal(val reason: String, val detail: String?) : EngineState
    data object Disposed : EngineState
}

enum class RelayState { Idle, Connecting, Authenticating, Waiting, Paired,
                        Reconnecting, Error, Kicked, Closed }
```

`Fatal.reason` 是**面向用户的收敛文案**；`Fatal.detail` 是给日志/调试页的原文（可能含 Rust
内部路径），**不要**直接当 UI 文案。

### 3.4 命令（挂起）

全部 `suspend`，可从任意线程调用；内部切到引擎 dispatcher。

```kotlin
suspend fun bootstrap(): JsonElement                                  // 配对后取全量快照
suspend fun listWorkspaces(): JsonElement                             // 刷新工作区列表
suspend fun openBridge(workspaceKey: String, taskId: String? = null): Long
suspend fun waitPaired(timeoutMs: Long = 30_000)
suspend fun waitHealthy(session: Long, timeoutMs: Long = 45_000)
suspend fun sendMobileViewState(workspaceKey: String, taskId: String? = null)   // 不产生挂起操作
suspend fun reconnectWorkspace(workspaceKey: String): JsonElement
suspend fun pokeRelay()                                              // 不产生挂起操作
suspend fun snapshot(): JsonElement                                   // nativeQuery snapshot
suspend fun call(command: JsonObject): JsonElement                    // 通用逃生舱（FFI.md §8）
```

返回值映射（对应 FFI.md §7.5 的 `completed.outcome`）：

| `outcome.kind` | 返回值 |
|---|---|
| `payload` | `value` 原样 |
| `bridge_opened` | `JsonPrimitive(session)`（`openBridge` 解成 `Long`） |
| `ok` | `JsonNull` |
| `failed` | 抛 `EngineException` |
| 命令不产生挂起操作（`operation: null`） | 立刻返回 `JsonNull` |

### 3.4.1 对话层与定时任务层（FFI.md §13）

```kotlin
suspend fun conversationOpen(session: Long, scope: JsonObject): String            // 返回 conversation key
suspend fun conversationCall(session: Long, key: String, op: String,
                             args: JsonObject): JsonElement                       // 该 op 的结果
suspend fun conversationClose(session: Long, key: String)                         // 不产生挂起操作
suspend fun automationCall(session: Long, op: String, args: JsonObject): JsonElement
```

信封（`session` 是 `openBridge` 返回的桥会话号）：

```json
{"method":"conversation_open","session":1,"scope":{"workspacePath":"/repo"}}
{"method":"conversation_call","session":1,"key":"<conversation_open 的返回>","op":"sendText","args":{...}}
{"method":"conversation_close","session":1,"key":"..."}
{"method":"automation_call","session":1,"op":"list","args":{}}
```

`op` 与 `args` 的形状**以 Rust 实现为准**（FFI.md §13.5.1），Kotlin 侧只透传、不理解。
`automationCall` 的 `op`：`list` / `create` / `setEnabled` / `delete` / `runNow` / `restart`。

四条命令各自的完成语义：

| 命令 | 信封 `operation` | 完成方式 | 本层行为 |
|---|---|---|---|
| `conversation_open` | **key 本身** | Rust 侧 `ConversationLayer::open` 额外补一条同 token 的顶层 `completed`（`rust/crates/ffi/src/conversation.rs`） | 走「发命令 + 等 `completed`」通用路径，返回 `operation` 当 key |
| `conversation_call` | `conv:<key>:<op>:<n>` | 该 op 结束时顶层 `completed` | 按 token 配对 resolve；`operation: null`（Rust 没看到 `opAccepted`，如已握手时的幂等 `handshake`）立刻返回 `JsonNull` |
| `conversation_close` | `null` | 无 | 立刻返回 |
| `automation_call` | `automation:<op>:<n>` | 应答到达时顶层 `completed` | 按 token 配对 resolve；`list` 的值是原始条目数组 |

`conversationOpen` 的 key 与那条 `completed` 的 token 是**同一个字符串**，所以两条路径等价；
本层按「等 `completed`」实现（与其它命令一致）。若 Rust 侧哪天不再补那条 `completed`，
`conversationOpen` 会等到宿主兜底超时才抛 `timeout`——`ZemoteEngineConversationTest`
里有一条测试锁住这个行为。

**对话层事件**（§13.4）走 `events` 旁路流的 `EngineEvent.ConversationEvent`
（`session` / `key` / `event` 原样），引擎不解析 `event` 内部结构（§13.5.2 的表以 Rust 为准）。
`conversation_event` **不需要**回喂任何输入——§13.5.2 里那些「宿主回喂」的输入
（`channel` / `timerFired` / `listenerRegistered` / `waitHealthy` …）全部由 Rust 侧
`ConversationLayer` 内部构造，不新增 `nativeStep` 输入类型。

**已知局限（刻意保留）**：`conversation_close` **不会**让在飞的 `conversationCall` 失败
（Rust 侧只清本层的挂起登记，不补 `completed`）。那些调用只能等宿主兜底超时到期后抛
`timeout`；要立刻收场请自行取消协程。

### 3.5 异常

```kotlin
class EngineException(val error: NativeError) : Exception(...)
```

所有失败（native `ok=false`、宿主兜底超时、引擎未启动 / 已销毁 / Fatal）都收敛成这一个类型。
`error.kind` 取值：`panic` / `badInput` / `timeout` / `invalidState` / `protocol`。

---

## 4. 线程语义（最重要的一节）

契约 §11 规定**同一个 handle 上的 native 调用不可重入**。因此：

- 引擎自建一个**专用单线程 dispatcher**（线程名 `zemote-engine`），**全部** `bridge.*` 调用与
  事件处理都在它上面串行执行。
- OkHttp 的 I/O 线程、定时器回调、任何外部线程都**不能**直接调 native；引擎内部把 socket 回调
  投递到该 dispatcher 后才回喂 `nativeStep`。
- `state` / `relayState` / `relayFailure` / `lastError` / `logs` / `events` 的**读取**可从任意线程。
- 挂起 API 可从任意线程调用（内部 `withContext(dispatcher)`）。
- **不要**把 `events` 的订阅者当作跑在引擎线程上：需要主线程更新 UI 请自行 `withContext(Main)`。

`events` 是**旁路流**（`extraBufferCapacity = 512`，满了丢事件）：引擎自己已经消费了它需要的
部分（传输、定时器、`completed`），订阅它是为了观察 `workspace_list_updated` / `channel` /
`conversation_event` / 日志等。**不要把关键逻辑建立在它上面**。

---

## 5. 超时与兜底

超时是**两层**的：

1. **核心侧**（正常路径）：`wait_paired` / `bridge_wait_healthy` 等由核心自己的定时器驱动——
   核心吐 `timer_start`，宿主到点回喂 `timer_fired`，超时后核心发 `completed{kind:"failed"}`。
   对话层的命令类 `op` 同理：Rust 侧 `ConversationLayer` 按 `nowMs` 记 deadline，到点
   `remove_pending` 后喂 `channelTimeout`，状态机随即产出 `failed` 的 `completed`。
2. **宿主侧兜底**（本层新增）：`(命令声明的时间，缺省 30s) + 5s` 宽限。到点仍未收到
   `completed` 就抛 `EngineException(kind = "timeout")`，并把它从等待表里摘掉。

「命令声明的时间」有两个来源：
- §8 的门面命令：顶层的 `timeoutMs`（毫秒）；
- §13.2 的 `conversation_call`：**没有顶层 `timeoutMs`**，超时写在 `args.timeoutSecs`（秒，
  `createSession` 默认 90）。不认它的话，长操作会在核心还没到点前被宿主判超时（假失败）。
- `automation_call` 的各 op 超时由 Rust 侧定（最长 30s），落在默认兜底内。

留 5s 宽限是为了让**核心先失败**——这样错误信息来自协议层（更准确），而不是宿主造出来的。
兜底只在「核心的定时器事件丢了 / 宿主漏回喂 `timer_fired` / 核心卡死 / 对话被 `close` 掉」
时触发。

`EngineState.Fatal` 与 `dispose()` 都会立刻唤醒所有等待中的调用（分别抛 `panic` /
`invalidState`），不会让调用方干等到兜底超时。

---

## 6. 测试与替换点

JVM 单测加载不了 `.so`（`System.loadLibrary("zemote_ffi")` 只对 arm64 Android 有效），
所以引擎**只依赖 `NativeBridge` 接口**，绝不直接引用 `NativeCore`：

```kotlin
// app/src/test/kotlin/app/zemote/engine/ZemoteEngineTest.kt
val engine = ZemoteEngine(
    bridge = FakeNativeBridge(),                 // 脚本化每个方法的返回信封
    scope = backgroundScope,
    clock = { 1_760_000_000_000L },
    dispatcher = UnconfinedTestDispatcher(testScheduler),   // 注入点，见构造参数
)
```

`dispatcher` 参数**只**用于这个目的：注入测试调度器（虚拟时间 + 同步执行）。
生产代码不要传它——默认值就是那个专用单线程 dispatcher。**绝不能传 `Dispatchers.Default`**
（多线程 = 违反契约 §11）。

`JniNativeBridge` 是生产实现，单测不要碰它。

---

## 7. 给 ViewModel 的典型用法

```kotlin
// 观察
viewModelScope.launch {
    engine.state.collect { s ->
        when (s) {
            is EngineState.Running -> showConnected(s.handle)
            is EngineState.Fatal   -> showFatal(s.reason)   // detail 只进日志
            EngineState.Disposed   -> finish()
            else -> Unit
        }
    }
}

// 配对 → 引导 → 开桥
viewModelScope.launch {
    engine.waitPaired(60_000)
    val snapshot = engine.bootstrap()
    renderWorkspaces(snapshot)
}

// 一个工作区就绪后
viewModelScope.launch {
    try {
        val session = engine.openBridge(workspaceKey = "wk", taskId = null)
        engine.waitHealthy(session, 45_000)
    } catch (e: EngineException) {
        showError(e.error.kind, e.error.message)
    }
}

// 回前台
override fun onResume() { viewModelScope.launch { engine.pokeRelay() } }
```

---

## 8. 表现层的两个接缝（`ui/EnginePort.kt` / `ui/chat/ChatOps.kt`）

引擎之上还有两个薄接缝，**不在** `engine/` 包里但属于本层契约：

### 8.1 `EnginePort`（`ui/EnginePort.kt`）

`ZemoteEngine` 的直接透传 + 一层「拆旧建新」（一个引擎实例 = 一条连接，换设备必然换实例）。
除 §3 的全部方法外，多两个 StateFlow 与 §3.4.1 的四条对话/定时任务方法：

```kotlin
val bridgeSession: StateFlow<Long?>        // 最近一次 openBridge 的桥会话号
val bridgeWorkspaceKey: StateFlow<String?> // 最近一次 openBridge 的 workspaceKey
suspend fun conversationOpen(session: Long, scope: JsonObject): String
suspend fun conversationCall(session: Long, key: String, op: String, args: JsonObject): JsonElement
suspend fun conversationClose(session: Long, key: String)
suspend fun automationCall(session: Long, op: String, args: JsonObject): JsonElement
```

`bridgeWorkspaceKey` 的唯一用途是给 `conversation_open` 造 scope：
`conversation_key(scope)` 取 `workspaceIdentity ?? workspacePath`，而桥的 `workspaceKey` 本身
就是 Dart 的 `workspaceKeyOf(w) = identity?.trim() || path`，所以放进 `workspacePath` 得到的
conversation key 与 Dart 一致。**已知近似**：线上 RPC 的 `workspacePath` 因此拿到的是
「identity 或 path」；若桌面端要求两者分开，需要从工作区快照里取真实字段（那要求快照形状冻结）。

### 8.2 `ChatOps`（`ui/chat/ChatOps.kt`）

对话层的**唯一入口**，只有一个方法：`suspend fun call(op: String, args: JsonObject): JsonElement`。
生产实现 `EngineChatOps` **自己**持有 session/key：第一次 `call` 时惰性
`conversation_open`（scope 由 `EnginePort.bridgeWorkspaceKey` 派生），之后按桥会话缓存，
换桥自动重开；并发 `call` 由 `Mutex` 串行化。

接口上**不出现** session/key，是为了不动 `ChatViewModel`（它只调 `call(op, args)`）。
没有桥会话时抛 `EngineException(invalidState)`。

### 8.3 relay 状态回写设备记录（`ui/devices/DeviceRepository.kt`）

设备列表显示的是 `DeviceRecord.lastRelayStatus`（持久化的「最后已知状态」）。
`RelayStatusWriter`（挂在 `AppContainer` 的进程级作用域，**不是**界面作用域）把引擎的
`relayState` 写回当前设备，纪律三条：

- **只在状态真的变了时写**（以盘上的值为基准，启动时不白写）；
- `lastConnectedAt` **只在进入 `paired`** 时更新；
- 写盘失败**吞掉并记日志**（不否定连接状态）；`CancellationException` 照旧上抛。

写盘走注入的 `DeviceRepository.recordRelayStatus(id, status, connectedAt)` →
`DeviceStore.setRelayStatus` / `markConnected`。**不要**为此新建 `DeviceStore`
（同一路径只允许一个实例）。

---

## 9. 已知边界

- **`events` 会丢事件**（缓冲满时）。要可靠的事件请等 `completed`（挂起 API）或读 `StateFlow`。
- **`logs` 只保留最近 500 行**，且是「`log` + `relay_log` 合并」的视图，不含 native 侧 stdout。
- **`relayFailure` 不会自动清空**：它是「最近一次失败」，成功重连后仍保留旧值，UI 自行按
  `relayState == Paired` 决定是否隐藏。
- **`conversation_close` 之后在飞调用只能等兜底超时**（见 §3.4.1 的已知局限）。
- **对话层 `op` 的参数校验全在 Rust 侧**：例如 `sendText` 必填 `sessionId`，界面层拿不到
  会话 id 时会在线上拿 `badInput`（明确失败，不会静默发错会话）。
