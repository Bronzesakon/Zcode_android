package app.zemote.engine

import android.util.Log
import app.zemote.core.NativeBridge
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Kotlin 侧期望的 FFI ABI 版本（契约 §4：改任何 schema 就 +1）。 */
const val EXPECTED_ABI: Int = 2

/** 挂起操作在宿主侧的兜底超时（native 未给 `timeoutMs` 时的默认值）。 */
private const val DEFAULT_OPERATION_TIMEOUT_MS = 30_000L

/**
 * 宿主侧兜底比 Rust 侧定时器**多留的余量**。
 *
 * 正常路径上超时由核心自己的定时器驱动（`wait_paired` 等都有 Dart 侧默认值），
 * 宿主这层只是防「核心定时器事件丢了 / 宿主漏回喂 `timer_fired`」把调用者永久挂住。
 * 留余量是为了让核心先失败、错误信息更准确（`timeout` 由核心给而不是宿主造）。
 */
private const val HOST_TIMEOUT_GRACE_MS = 5_000L

private const val DEFAULT_WAIT_PAIRED_TIMEOUT_MS = 30_000L
private const val DEFAULT_WAIT_HEALTHY_TIMEOUT_MS = 45_000L
private const val DEFAULT_OPEN_BRIDGE_TIMEOUT_MS = 30_000L

/** 日志环形缓冲上限（调试页用；不要无限增长）。 */
private const val MAX_LOG_LINES = 500

/** [ZemoteEngine.events] 的缓冲容量。 */
private const val EVENT_BUFFER = 512

private const val TAG = "ZemoteEngine"

/** native 层失败（`ok=false`）或宿主侧超时，统一以异常抛给挂起 API 的调用方。 */
class EngineException(val error: NativeError) : Exception("${error.kind}: ${error.message.orEmpty()}")

/**
 * 引擎层：Rust 协议核心与 UI 之间的那一层。
 *
 * ## 它做什么
 *
 * - 把 [NativeBridge] 的同步 JSON 调用包成 `StateFlow` / `SharedFlow` / 挂起函数；
 * - 执行核心吐出的传输动作（OkHttp WebSocket）与定时器；
 * - 把 socket 回调与定时器到点**回喂**成 `nativeStep` 的输入；
 * - 把 `completed` 事件关联回发起它的挂起调用。
 *
 * ## 线程语义（契约 §11，**最重要的一条**）
 *
 * 同一个 handle 上的 native 调用**不可重入**，所以**所有** `bridge.*` 调用与事件处理都在
 * 一个专用单线程 dispatcher（[dispatcher]）上执行。OkHttp 的 I/O 线程与任何外部线程都不能
 * 直接调 native——它们只能 `launch` 到引擎 dispatcher（见 [postStep]）。
 *
 * 因此：`state` / `relayState` / `events` / `logs` 的**读取**可从任意线程；**挂起 API 可从任意
 * 线程调用**（内部会切到引擎 dispatcher）。回调式的 `SharedFlow` 订阅者不会拿到引擎线程的
 * 保证，需要主线程更新 UI 请自行 `withContext`。
 *
 * ## 生命周期
 *
 * `start` 一次（幂等，重复调用被忽略）→ 用各种挂起 API → `dispose()`。
 * `dispose()` 之后引擎 dispatcher 被关闭，引擎不可复用，需要新建实例。
 *
 * @param bridge FFI 实现；生产用 [app.zemote.core.JniNativeBridge]，单测用 `FakeNativeBridge`。
 * @param scope 宿主作用域；引擎的定时器与回喂任务挂在它下面（取消它等于取消引擎）。
 * @param clock 时钟注入点。契约要求**时间一律由宿主传入**（`nowMs`），核心不读时钟；
 *   单测注入固定值即可让整条链路可复现。
 * @param dispatcher 引擎专用调度器。默认自建单线程；**仅供测试/嵌入式宿主注入**，
 *   传进来时引擎不负责关闭它。绝不能传 `Dispatchers.Default`（多线程 = 违反 §11）。
 */
class ZemoteEngine private constructor(
    private val bridge: NativeBridge,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val dispatcher: CoroutineDispatcher,
    private val ownsDispatcher: Boolean,
    private val sockets: RelayWebSocket,
) {

    constructor(
        bridge: NativeBridge,
        scope: CoroutineScope,
        clock: () -> Long = System::currentTimeMillis,
        dispatcher: CoroutineDispatcher? = null,
    ) : this(
        bridge = bridge,
        scope = scope,
        clock = clock,
        dispatcher = dispatcher ?: newEngineDispatcher(),
        ownsDispatcher = dispatcher == null,
        sockets = RelayWebSocket(),
    )

    /** 单测注入假 socket；生产走上面的公开构造器。 */
    internal constructor(
        bridge: NativeBridge,
        scope: CoroutineScope,
        clock: () -> Long,
        dispatcher: CoroutineDispatcher,
        sockets: RelayWebSocket,
    ) : this(bridge, scope, clock, dispatcher, ownsDispatcher = false, sockets = sockets)

    private val engineJob = SupervisorJob(scope.coroutineContext[Job])
    private val engineScope = CoroutineScope(engineJob + dispatcher)

    // ---- 对外状态 ----

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)

    /** 引擎自身生命周期。 */
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _relayState = MutableStateFlow(RelayState.Idle)

    /** relay 状态机当前状态（`relay_state` 事件）。 */
    val relayState: StateFlow<RelayState> = _relayState.asStateFlow()

    private val _relayFailure = MutableStateFlow<EngineEvent.RelayFailure?>(null)

    /** 最近一次 `relay_failure`；`null` 表示还没失败过。UI 的错误条读它。 */
    val relayFailure: StateFlow<EngineEvent.RelayFailure?> = _relayFailure.asStateFlow()

    private val _lastError = MutableStateFlow<NativeError?>(null)

    /** 最近一次 native 层的 `ok=false`（含 panic）。 */
    val lastError: StateFlow<NativeError?> = _lastError.asStateFlow()

    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = EVENT_BUFFER)

    /**
     * 全部输出事件的旁路流（契约 §7）。
     *
     * **只是旁路**：引擎自己已经消费了它需要的部分（传输、定时器、`completed`），
     * 订阅它是为了 UI 观察（`workspace_list_updated`、`channel`、`conversation_event`、
     * 日志等）。缓冲满了会丢事件，**不要**把关键逻辑建立在它上面。
     */
    val events: SharedFlow<EngineEvent> = _events.asSharedFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())

    /** 最近 [MAX_LOG_LINES] 行日志（`log` / `relay_log` 合并），给调试页用。 */
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val logRing = ArrayDeque<String>()

    // ---- 内部 ----

    private val timers = TimerScheduler(engineScope)

    /**
     * ws 重连前的 terminal token 刷新钩子。
     *
     * 3.16.1 网页/桌面每次 `_connect()` 都重新换 token，不是只在登录时换一次。首次连接
     * （generation == 1）沿用 [EngineConfig.token]；之后每次 `ws_connect` 都调用本钩子。
     * 返回 `null` / 抛异常时回喂一次 `socket_failed`，让核心按退避重连。
     *
     * 由 [app.zemote.ui.ZemoteEnginePort] 注入；`null` = 不刷新（旧链接走 HMAC）。
     */
    @Volatile
    internal var terminalTokenRefresher: (suspend () -> String?)? = null

    private var handle: Long? = null
    private val pending = HashMap<String, CompletableDeferred<CompletionOutcome>>()
    private var started = false
    private var disposed = false

    // ----------------------------------------------------------------------
    // 启动
    // ----------------------------------------------------------------------

    /**
     * 核对 ABI → `nativeCreate` → 处理构造事件 → 发 `{"type":"start"}`。
     *
     * 非挂起：立刻返回，进度通过 [state] 观察。重复调用被忽略。
     *
     * ABI 不匹配（契约 §4）或 `nativeCreate` 失败会进入 [EngineState.Fatal] 而**不是**崩溃——
     * Rust 侧出了 bug 时继续跑没有意义，但进程必须活着让用户看到原因。
     */
    fun start(config: EngineConfig) {
        engineScope.launch {
            if (started || disposed) return@launch
            started = true
            _state.value = EngineState.Starting

            // --- 契约 §4：ABI 核对 ---
            val abiRaw = runCatching { bridge.abiVersion() }.getOrNull()
            val abi = abiRaw?.let(::decodeEnvelope)
            if (abi == null) {
                enterFatal("无法解析 nativeAbiVersion() 的返回（.so 未加载或版本过旧）", abiRaw)
                return@launch
            }
            if (!abi.ok) {
                enterFatal("nativeAbiVersion() 报告失败", abi.error?.let { "${it.kind}: ${it.message}" })
                return@launch
            }
            if (abi.abi != EXPECTED_ABI) {
                enterFatal(
                    "FFI ABI 不匹配：Kotlin 期望 $EXPECTED_ABI，libzemote_ffi.so 报告 ${abi.abi}。" +
                        "两者必须同源构建。",
                    abi.protocolVersion,
                )
                return@launch
            }
            logLine("FFI abi=${abi.abi} protocol=${abi.protocolVersion ?: "?"}")

            // --- 契约 §5：建会话 ---
            val createdRaw = runCatching { bridge.create(config.toJson(clock()).toString()) }.getOrNull()
            val created = createdRaw?.let(::decodeEnvelope)
            if (created == null) {
                enterFatal("nativeCreate() 的返回无法解析", createdRaw)
                return@launch
            }
            val newHandle = created.handle
            if (newHandle == null) {
                // 出错前的事件不能丢（契约 §3），即使没有 handle 也要处理
                dispatchEnvelope(created, fatalOnError = false)
                enterFatal("nativeCreate() 未返回 handle", created.error?.message)
                return@launch
            }
            handle = newHandle
            _state.value = EngineState.Running(newHandle)

            // 构造过程本身就会产生事件（relay TimerStart、日志等），**不能丢**（契约 §5）
            dispatchEnvelope(created, fatalOnError = true)
            if (_state.value is EngineState.Fatal) return@launch

            feed(TimerInputs.start())
        }
    }

    // ----------------------------------------------------------------------
    // 公开挂起 API
    // ----------------------------------------------------------------------

    /** `bootstrap`：配对后取全量工作区/任务快照。返回 `outcome.value`。 */
    suspend fun bootstrap(): JsonElement = call(jsonCommand("bootstrap"))

    /** `list_workspaces`：刷新工作区列表。返回 `outcome.value`。 */
    suspend fun listWorkspaces(): JsonElement = call(jsonCommand("list_workspaces"))

    /**
     * 通用命令入口（契约 §8 的 `nativeCall`）。
     *
     * 发命令 → 等 `completed` → 把 `outcome` 映射成返回值：
     * `payload` → `value`；`bridge_opened` → session 数字；`ok` → `JsonNull`；
     * `failed` → 抛 [EngineException]。命令若不产生挂起操作（`operation: null`）立即返回 `JsonNull`。
     *
     * 超时：核心自己的定时器先到期；宿主侧再兜一层 `(timeoutMs ?: 30s) + 5s`（见
     * [HOST_TIMEOUT_GRACE_MS]），兜底触发时抛 `kind="timeout"` 的 [EngineException]。
     */
    suspend fun call(command: JsonObject): JsonElement = when (val outcome = invoke(command)) {
        null -> JsonNull
        is CompletionOutcome.Payload -> outcome.value
        is CompletionOutcome.BridgeOpened -> JsonPrimitive(outcome.session)
        is CompletionOutcome.Ok -> JsonNull
        is CompletionOutcome.Failed -> throw EngineException(outcome.error)
        is CompletionOutcome.Unknown -> outcome.raw
    }

    /**
     * `open_bridge`：为工作区开一个桥。返回 `bridgeSessionId`。
     *
     * @param taskId 可空；`null` 序列化成 JSON `null`（契约 §3：键不省）。
     * @param taskHostId 项目树任务所属 host。与 `taskId` 一起给时，桌面端会先
     *   `prepareProjectWorkspace`，因此不在当前桌面窗口 tab 里的项目任务也能开桥。
     * @param project 项目草稿意图 `{projectId, hostId?, draftId?, reuseDraft?}`；新建对话用。
     *   不能与 `taskId` 同时给（桌面端会拒绝）。
     */
    suspend fun openBridge(
        workspaceKey: String,
        taskId: String? = null,
        taskHostId: String? = null,
        project: JsonObject? = null,
    ): Long {
        val command = buildJsonObject {
            put("method", "open_bridge")
            put("workspaceKey", workspaceKey)
            put("taskId", taskId?.let { JsonPrimitive(it) } ?: JsonNull)
            taskHostId?.let { put("taskHostId", it) }
            project?.let { put("project", it) }
            put("timeoutMs", DEFAULT_OPEN_BRIDGE_TIMEOUT_MS)
        }
        return when (val outcome = invoke(command)) {
            is CompletionOutcome.BridgeOpened -> outcome.session
            is CompletionOutcome.Payload ->
                (outcome.value as? JsonPrimitive)?.longOrNull
                    ?: throw EngineException(
                        NativeError("protocol", "open_bridge 的 payload 不是 session id"),
                    )

            null -> throw EngineException(NativeError("protocol", "open_bridge 未返回挂起操作"))
            is CompletionOutcome.Failed -> throw EngineException(outcome.error)
            else -> throw EngineException(
                NativeError("protocol", "open_bridge 的 outcome 形态意外：$outcome"),
            )
        }
    }

    /** `wait_paired`：等 relay 进入 paired。超时由核心的定时器驱动。 */
    suspend fun waitPaired(timeoutMs: Long = DEFAULT_WAIT_PAIRED_TIMEOUT_MS) {
        invoke(
            buildJsonObject {
                put("method", "wait_paired")
                put("timeoutMs", timeoutMs)
            },
        )
    }

    /** `bridge_wait_healthy`：等桥恢复健康。 */
    suspend fun waitHealthy(session: Long, timeoutMs: Long = DEFAULT_WAIT_HEALTHY_TIMEOUT_MS) {
        invoke(
            buildJsonObject {
                put("method", "bridge_wait_healthy")
                put("session", session)
                put("timeoutMs", timeoutMs)
            },
        )
    }

    /** `send_mobile_view_state`：把移动端焦点（工作区/任务）推给桌面。不产生挂起操作。 */
    suspend fun sendMobileViewState(workspaceKey: String, taskId: String? = null) {
        invoke(
            buildJsonObject {
                put("method", "send_mobile_view_state")
                put("workspaceKey", workspaceKey)
                put("taskId", taskId?.let { JsonPrimitive(it) } ?: JsonNull)
            },
        )
    }

    /** `reconnect_workspace`：桥恢复的第一条路径。返回 `outcome.value`（无则 `JsonNull`）。 */
    suspend fun reconnectWorkspace(workspaceKey: String): JsonElement = call(
        buildJsonObject {
            put("method", "reconnect_workspace")
            put("workspaceKey", workspaceKey)
        },
    )

    /** `poke_relay`：回前台探活。不产生挂起操作。 */
    suspend fun pokeRelay() {
        invoke(jsonCommand("poke_relay"))
    }

    // ----------------------------------------------------------------------
    // §13 对话层与定时任务层（`docs/FFI.md` §13）
    // ----------------------------------------------------------------------

    /**
     * `conversation_open`（§13.2）：打开（或复用）一个对话，返回 **conversation key**。
     *
     * ## 为什么这里走「发命令 + 等 `completed`」而不是直接读信封的 `operation`
     *
     * 信封的 `operation` 确实是 key（§13.2），但 `conversation_open` **不是**「不产生挂起操作」
     * 的命令：Rust 侧 `ConversationLayer::open` 在返回信封的同时**额外**塞了一条同 token 的
     * 顶层 `completed`（`rust/crates/ffi/src/conversation.rs` 的 `open()`，注释明说「这里立刻
     * 收尾」）。所以走通用路径即可：登记 `operation` → 派事件（`completed` 可能就在本次返回的
     * `events` 里）→ 立刻 resolve。**key 与那条 `completed` 的 token 是同一个字符串**。
     *
     * 若哪天 Rust 侧不再补那条 `completed`，这里会白等到宿主兜底超时（[HOST_TIMEOUT_GRACE_MS]）
     * 再抛 `timeout`——这正是 `conversation.rs` 那条注释要防的情形。
     *
     * 重复 open 同一个 key 是**幂等**的（Rust 侧 `putIfAbsent` 语义），不会重复登记。
     *
     * @param session `open_bridge` 返回的桥会话号。
     * @param scope 工作区 scope（至少含 `workspacePath`，可含 `workspaceIdentity`）；
     *   key 由 `conversation_key(scope)` 取 `workspaceIdentity ?? workspacePath` 生成。
     * @throws EngineException `invalidState`（未知桥会话）/ `timeout` / `protocol`。
     */
    suspend fun conversationOpen(session: Long, scope: JsonObject): String {
        val command = buildJsonObject {
            put("method", "conversation_open")
            put("session", session)
            put("scope", scope)
        }
        val invocation = invokeDetailed(command)
        // `completed` 的 outcome 若是 failed，invokeDetailed 已经抛了；这里只取 key。
        return invocation.operation
            ?: throw EngineException(
                NativeError("protocol", "conversation_open 未返回 operation（conversation key）"),
            )
    }

    /**
     * `conversation_call`（§13.2）：把一条 `op` + `args` 透传给某个对话。返回该 `op` 的
     * 结果（`completed.outcome` → 返回值，映射规则同 [call]）。
     *
     * 命令类 `op` 的结果经**顶层** `completed` 回来，token 形如 `conv:<key>:<op>:<n>`
     * （Rust 侧 `conversation_op_token`）；本层不解析它的形态，只做 `operation` ↔ `token` 配对。
     * `operation` 为 `null`（Rust 侧没看到 `opAccepted`，例如已握手时的幂等 `handshake`）
     * 时立刻返回 [JsonNull]，不等任何 `completed`。
     *
     * @throws EngineException `badInput`（未知 `op` / 参数不符）/ `invalidState`（未知 key）/
     *   `timeout` / `protocol`。
     */
    suspend fun conversationCall(
        session: Long,
        key: String,
        op: String,
        args: JsonObject,
    ): JsonElement = call(conversationCommand(session, key, op, args))

    /**
     * 发送一个**订阅类**对话 op（`subscribe` / `subscribeSessionsIndex`），**不等 `completed`**。
     *
     * ## 为什么不能用 [conversationCall]
     *
     * **wire 上没有顶层 `completed`**：对端只回 `{ack:{subscriptionId}}`。Rust 在收到 ack 后
     * 会合成一个 host-level `Completed`（`on_subscribe_response`），但订阅本身是**长期事件
     * 流**——用 [conversationCall] 等它只会把 ack 延迟变成界面阻塞，还会让调用方误以为
     * 「拿到 ack 就结束了」。
     *
     * 这里只发送命令并处理本次返回的事件；后续帧通过 `conversation_event` 到达。
     * 普通命令继续用 [conversationCall]。
     */
    suspend fun conversationCallNoWait(
        session: Long,
        key: String,
        op: String,
        args: JsonObject,
    ) {
        withContext(dispatcher) {
            if (disposed) throw EngineException(NativeError("invalidState", "引擎已销毁"))
            (_state.value as? EngineState.Fatal)?.let {
                throw EngineException(NativeError("invalidState", "引擎处于 Fatal：${it.reason}"))
            }
            val h = handle
                ?: throw EngineException(NativeError("invalidState", "引擎尚未启动（先调用 start()）"))
            val command = conversationCommand(session, key, op, args)
            val raw = runCatching { bridge.call(h, command.toString(), clock()) }.getOrNull()
                ?: throw EngineException(NativeError("protocol", "nativeCall() 调用失败"))
            val env = decodeEnvelope(raw)
                ?: throw EngineException(NativeError("protocol", "nativeCall() 返回无法解析"))
            dispatchEnvelope(env, fatalOnError = false, raw = raw)
            env.error?.let { throw EngineException(it) }
        }
    }

    private fun conversationCommand(
        session: Long,
        key: String,
        op: String,
        args: JsonObject,
    ): JsonObject = buildJsonObject {
        put("method", "conversation_call")
        put("session", session)
        put("key", key)
        put("op", op)
        put("args", args)
    }

    /**
     * `conversation_close`（§13.2）：释放一个对话（撤定时器/监听器，清路由）。不产生挂起操作，
     * 立刻返回。
     *
     * ## 在飞操作不会被失败——只能靠宿主兜底超时收场（**已知局限**）
     *
     * Rust 侧的 `ConversationLayer::close` 只清掉**本层**的挂起登记（`calls.retain(...)`），
     * **不会**为那些已在飞的 `conversation_call` 补一条 `completed`（协议层 `dispose` 也不发）。
     * 于是那些调用者注册的 `Deferred` 永远等不到 `completed`，只能等 [HOST_TIMEOUT_GRACE_MS]
     * 那一层兜底超时到期后抛 `timeout`。
     *
     * 这是**刻意保留**的行为（不额外造 `completed`，避免和 Rust 侧将来补的失败事件打架）；
     * 代价是：`close` 之后在飞调用会挂到兜底超时为止。调用方若要立刻收场，自行取消协程。
     */
    suspend fun conversationClose(session: Long, key: String) {
        invoke(
            buildJsonObject {
                put("method", "conversation_close")
                put("session", session)
                put("key", key)
            },
        )
    }

    /**
     * `automation_call`（§13.3）：定时任务层的 `op` 透传。
     *
     * `op` 取值：`list` / `create` / `setEnabled` / `delete` / `runNow` / `restart`（以 Rust 侧
     * `ConversationLayer::automation` 为准）。结果经顶层 `completed` 的 `outcome.value` 返回，
     * token 形如 `automation:<op>:<n>`。
     *
     * `list` 的返回值是**原始条目数组**（Rust 侧 `parse_automation_list` 过滤掉非对象元素）；
     * 其余 `op` 返回原始应答。
     *
     * @throws EngineException `badInput` / `invalidState`（未知桥会话）/ `timeout` / `protocol`。
     */
    suspend fun automationCall(session: Long, op: String, args: JsonObject): JsonElement = call(
        buildJsonObject {
            put("method", "automation_call")
            put("session", session)
            put("op", op)
            put("args", args)
        },
    )

    /**
     * `channel_call`：任意通道 / 任意方法。
     *
     * `args` 是**位置参数数组**（与对端 `ChannelClient.call` 的签名一致）。结果经顶层
     * `completed` 的 `outcome.value` 返回原始应答。用于 `setting` / `window-controller` /
     * `skills` / `plugins` / `subagents` / `usage-stats` 等没有专用包装的通道。
     *
     * @throws EngineException `badInput` / `invalidState` / `timeout` / `protocol`。
     */
    suspend fun channelCall(
        session: Long,
        channel: String,
        method: String,
        args: JsonArray = JsonArray(emptyList()),
        timeoutMs: Long = 30_000,
    ): JsonElement = call(
        buildJsonObject {
            put("method", "channel_call")
            put("session", session)
            put("channel", channel)
            // 顶层 `method` 已用于选命令；通道方法走 `rpcMethod`，不能再写 `method`。
            put("rpcMethod", method)
            put("args", args)
            put("timeoutMs", timeoutMs)
        },
    )

    /**
     * `nativeQuery {"query":"snapshot"}`（契约 §9）。
     *
     * 返回的 snapshot 里**绝不含 `passHash`**（契约 §9 的安全约定）。
     */
    suspend fun snapshot(): JsonElement = withContext(dispatcher) {
        if (disposed) throw EngineException(NativeError("invalidState", "引擎已销毁"))
        val h = handle ?: throw EngineException(NativeError("invalidState", "引擎尚未启动"))
        val raw = runCatching { bridge.query(h, """{"query":"snapshot"}""") }.getOrNull()
            ?: throw EngineException(NativeError("protocol", "nativeQuery() 调用失败"))
        val env = decodeEnvelope(raw)
            ?: throw EngineException(NativeError("protocol", "nativeQuery() 返回无法解析"))
        dispatchEnvelope(env, fatalOnError = false, raw = raw)
        env.error?.let { throw EngineException(it) }
        env.snapshot ?: JsonNull
    }

    /**
     * `nativeQuery {"query":"conversation","session":…,"key":…}`（契约 §9.1，abi 2）。
     *
     * 返回该对话传输层的完整快照：`conversations`（按 sessionId）与 `sessionsIndex`。
     * 核心快照里没有这两块。
     */
    suspend fun conversationSnapshot(session: Long, key: String): JsonElement =
        withContext(dispatcher) {
            if (disposed) throw EngineException(NativeError("invalidState", "引擎已销毁"))
            val h = handle ?: throw EngineException(NativeError("invalidState", "引擎尚未启动"))
            val query = buildJsonObject {
                put("query", "conversation")
                put("session", session)
                put("key", key)
            }.toString()
            val raw = runCatching { bridge.query(h, query) }.getOrNull()
                ?: throw EngineException(NativeError("protocol", "nativeQuery() 调用失败"))
            val env = decodeEnvelope(raw)
                ?: throw EngineException(NativeError("protocol", "nativeQuery() 返回无法解析"))
            dispatchEnvelope(env, fatalOnError = false, raw = raw)
            env.error?.let { throw EngineException(it) }
            env.snapshot ?: JsonNull
        }

    // ----------------------------------------------------------------------
    // 生命周期收尾
    // ----------------------------------------------------------------------

    /**
     * 销毁：`nativeDispose` → 处理其事件 → 撤定时器/关 socket → 取消所有等待中的调用 →
     * 状态置 [EngineState.Disposed] → 关闭自建的 dispatcher。
     *
     * 非挂起；重复调用无副作用。对已销毁 handle 再调 native 是幂等失败（契约 §10），
     * 不当作错误。**销毁后引擎不可复用**。
     */
    fun dispose() {
        engineScope.launch {
            if (disposed) return@launch
            disposed = true
            val h = handle
            handle = null

            if (h != null) {
                val raw = runCatching { bridge.dispose(h, clock()) }.getOrNull()
                dispatchEnvelope(raw?.let(::decodeEnvelope), fatalOnError = false, raw = raw)
            }

            timers.cancelAll()
            sockets.closeAll()
            val waiting = pending.values.toList()
            pending.clear()
            waiting.forEach {
                it.completeExceptionally(
                    EngineException(NativeError("invalidState", "引擎已销毁")),
                )
            }

            if (_state.value !is EngineState.Fatal) _state.value = EngineState.Disposed

            engineJob.cancel()
            if (ownsDispatcher) (dispatcher as? ExecutorCoroutineDispatcher)?.close()
        }
    }

    /** [dispose] 的别名（名字更贴合「释放资源」的读法）。 */
    fun close() = dispose()

    // ----------------------------------------------------------------------
    // 命令执行
    // ----------------------------------------------------------------------

    /**
     * 发一条 `nativeCall`，必要时等它的 `completed`。
     *
     * 返回 `null` 表示该命令不产生挂起操作（`operation: null`）。
     */
    private suspend fun invoke(command: JsonObject): CompletionOutcome? =
        invokeDetailed(command).outcome

    /**
     * [invoke] 的完整形态：除了完成形态，还带**信封里的 `operation`**。
     *
     * 只有 `conversation_open` 需要它——它的 key 就是 `operation`（§13.2），而 key 本身不是
     * 「结果」（`completed` 的 outcome 是 `ok`）。
     *
     * 关键顺序：**先登记 deferred，再处理事件**——`completed` 可能就藏在本次返回的 `events`
     * 里（同步完成），登记晚了就丢了。
     */
    private suspend fun invokeDetailed(command: JsonObject): Invocation = withContext(dispatcher) {
        if (disposed) throw EngineException(NativeError("invalidState", "引擎已销毁"))
        (_state.value as? EngineState.Fatal)?.let {
            throw EngineException(NativeError("invalidState", "引擎处于 Fatal：${it.reason}"))
        }
        val h = handle ?: throw EngineException(NativeError("invalidState", "引擎尚未启动（先调用 start()）"))

        val raw = runCatching { bridge.call(h, command.toString(), clock()) }.getOrNull()
            ?: throw EngineException(NativeError("protocol", "nativeCall() 调用失败"))
        val env = decodeEnvelope(raw)
            ?: throw EngineException(NativeError("protocol", "nativeCall() 返回无法解析"))

        val token = env.operation
        val deferred = if (token != null) CompletableDeferred<CompletionOutcome>() else null
        if (token != null && deferred != null) pending[token] = deferred

        // 出错前的事件不能丢（契约 §3）：先派事件（可能顺带 resolve 上面的 deferred），再决定抛不抛
        dispatchEnvelope(env, fatalOnError = false, raw = raw)

        env.error?.let { throw EngineException(it) }
        if (token == null || deferred == null) return@withContext Invocation(token, null)

        val timeoutMs = hostTimeoutMs(command)
        val outcome = withTimeoutOrNull(timeoutMs) { deferred.await() }
        if (outcome == null) {
            pending.remove(token)
            throw EngineException(
                NativeError(
                    "timeout",
                    "操作 $token 超时（宿主侧兜底 ${timeoutMs}ms，核心定时器可能丢失）",
                ),
            )
        }
        Invocation(token, outcome)
    }

    /** [invokeDetailed] 的结果：信封的 `operation` + 完成形态（`null` = 命令不产生挂起操作）。 */
    private class Invocation(val operation: String?, val outcome: CompletionOutcome?)

    /**
     * 宿主兜底超时：命令自己声明的时间再加余量；没声明就用默认值。
     *
     * 两个来源（都是「Rust 侧先失败、宿主只兜底」这条约定的输入）：
     * - §8 的门面命令：顶层的 `timeoutMs`（毫秒）；
     * - §13.2 的 `conversation_call`：**没有顶层 `timeoutMs`**，超时写在 `args.timeoutSecs`
     *   （秒，`createSession` 默认 90）。不认它的话 `createSession` 会在核心还没到点前就被
     *   宿主判超时——那会把一条正常的长操作变成假失败。
     */
    private fun hostTimeoutMs(command: JsonObject): Long {
        val conversationSecs = conversationTimeoutSecs(command)
        val declaredMs = (command["timeoutMs"] as? JsonPrimitive)?.longOrNull
            ?: conversationSecs
        // 对话命令在桥降级时还要先等 `bridge_wait_healthy`（45s）；这段预算不在核心的
        // 命令超时里，宿主必须补上。对所有 `conversation_call` 生效（sendText 没有
        // timeoutSecs，但同样可能先等 healthy，否则 35s 假超时后重试会重复投递消息）。
        val healthyBudget =
            if (command.str("method") == "conversation_call") DEFAULT_WAIT_HEALTHY_TIMEOUT_MS else 0L
        return (declaredMs ?: DEFAULT_OPERATION_TIMEOUT_MS) + HOST_TIMEOUT_GRACE_MS + healthyBudget
    }

    /** §13.5.1 的 `args.timeoutSecs`（秒）→ 毫秒；没有就 `null`。 */
    private fun conversationTimeoutSecs(command: JsonObject): Long? {
        if (command.str("method") != "conversation_call") return null
        val args = command["args"] as? JsonObject ?: return null
        return (args["timeoutSecs"] as? JsonPrimitive)?.longOrNull?.times(1000)
    }

    private fun jsonCommand(method: String): JsonObject =
        buildJsonObject { put("method", method) }

    // ----------------------------------------------------------------------
    // 事件处理（契约 §7）
    // ----------------------------------------------------------------------

    /** 把输入喂回核心，并处理返回的事件。必须在引擎 dispatcher 上调用。 */
    private fun feed(input: JsonObject) {
        if (disposed) return
        if (_state.value is EngineState.Fatal) return
        val h = handle ?: return
        val raw = runCatching { bridge.step(h, input.toString(), clock()) }.getOrNull()
        if (raw == null) {
            logLine("nativeStep() 调用失败：$input")
            return
        }
        dispatchEnvelope(decodeEnvelope(raw), fatalOnError = false, raw = raw)
    }

    /**
     * 处理一个返回信封：**先派事件，再看 error**（契约 §3：出错前已产生的事件不能丢）。
     */
    private fun dispatchEnvelope(env: NativeEnvelope?, fatalOnError: Boolean, raw: String? = null) {
        if (env == null) {
            _lastError.value = NativeError("protocol", "native 返回不是合法 JSON 信封")
            logLine("native 返回不是合法信封：${raw?.take(200).orEmpty()}")
            return
        }
        env.events.forEach { dispatchEvent(EventParser.parse(it)) }

        val error = env.error ?: return
        _lastError.value = error
        logLine("native error: ${error.kind}: ${error.message.orEmpty()}")
        when {
            // 契约 §12：panic 说明 Rust 侧出了 bug，继续跑没意义——但不崩进程
            error.kind == "panic" -> enterFatal("Rust 核心发生 panic，会话已不可继续", error.message)
            fatalOnError -> enterFatal("native ${error.kind}", error.message)
        }
    }

    private fun dispatchEvent(event: EngineEvent) {
        _events.tryEmit(event)

        when (event) {
            // §7.1 传输动作
            is EngineEvent.WsConnect -> connect(event.generation, event.uri)
            is EngineEvent.WsSendText -> sockets.sendText(event.generation, event.text)
            is EngineEvent.WsClose -> sockets.close(event.generation, event.code, event.reason)

            // §7.2 relay 层
            is EngineEvent.RelayStateChanged -> _relayState.value = event.state
            is EngineEvent.RelayLog -> logLine(event.line)
            // relay_payload 只作旁路透出：门面已按 _dispatchPayload 消费过它的语义
            is EngineEvent.RelayPayload -> Unit
            is EngineEvent.RelayFailure -> {
                _relayFailure.value = event
                logLine(
                    "[relay] failure: ${event.reason}" +
                        (event.message?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""),
                )
            }

            is EngineEvent.RelayTimerStart -> timers.startRelay(
                slot = event.timer,
                delayMs = event.delayMs,
                repeat = event.repeat,
                onFire = { feed(TimerInputs.relayFired(event.timer)) },
            )

            is EngineEvent.RelayTimerCancel -> timers.cancelRelay(event.timer)

            // §7.3 门面层
            is EngineEvent.Log -> logLine(event.line)

            is EngineEvent.TimerStart -> timers.startToken(
                token = event.token,
                delayMs = event.delayMs,
                onFire = { feed(TimerInputs.facadeFired(event.token)) },
            )

            is EngineEvent.TimerCancel -> timers.cancelToken(event.token)

            is EngineEvent.Completed -> resolvePending(event)

            // 其余（workspace/bridge/conversation/channel/unknown）由 events 流交给 UI 层
            else -> Unit
        }
    }

    /**
     * `ws_connect`：首连直接用配置里的 token，重连先换一张新的 terminal token。
     *
     * 3.16.1 网页的 `connect()` 第一行就是 `await getToken()`，桌面端同构；token 的
     * `expires_in` 三端都不读，靠「每次建连都换」保证不过期。交换是挂起的，所以在
     * [engineScope] 里完成后才开 socket；期间引擎被 dispose 就放弃。
     */
    private fun connect(generation: Long, uri: String) {
        val refresher = terminalTokenRefresher
        if (generation <= 1L || refresher == null) {
            sockets.connect(generation, uri, socketListener)
            return
        }
        engineScope.launch {
            val fresh = try {
                refresher()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logLine("[relay] terminal token refresh failed: $e")
                null
            }
            if (disposed) return@launch
            if (fresh.isNullOrBlank()) {
                feed(SocketInputs.failed(generation, "terminal token refresh failed"))
            } else {
                sockets.connect(generation, RelayUri.withToken(uri, fresh), socketListener)
            }
        }
    }

    /**
     * 供宿主注入的刷新器上报失败（如 `auth-required`）。
     *
     * 与核心产出的 `relay_failure` 走同一条路径，UI 的收集器不需要区分来源。
     */
    internal fun reportRelayFailure(reason: String, message: String?) {
        engineScope.launch { dispatchEvent(EngineEvent.RelayFailure(reason, message)) }
    }

    /** `completed` → 唤醒发起它的挂起调用（契约 §8 的 `operation` ↔ `token` 配对）。 */
    private fun resolvePending(event: EngineEvent.Completed) {
        val deferred = pending.remove(event.token) ?: return
        when (val outcome = event.outcome) {
            is CompletionOutcome.Failed -> deferred.completeExceptionally(EngineException(outcome.error))
            else -> deferred.complete(outcome)
        }
    }

    private fun enterFatal(reason: String, detail: String?) {
        if (_state.value is EngineState.Fatal) return
        logLine("FATAL: $reason${detail?.let { " [$it]" }.orEmpty()}")
        _state.value = EngineState.Fatal(reason, detail)

        // 停掉一切自动动作，并唤醒所有等待者（否则它们要等宿主兜底超时）
        timers.cancelAll()
        sockets.closeAll()
        val waiting = pending.values.toList()
        pending.clear()
        waiting.forEach {
            it.completeExceptionally(EngineException(NativeError("panic", reason)))
        }
    }

    private fun logLine(line: String) {
        // `Log.d` 在多数 OEM 上被全局日志级别吞掉，复现连接问题时 logcat 里一条都拿不到。
        // 用 `I` 记录**非帧内容**（状态迁移 / 连接失败 / 关闭码 / error frame）；
        // `>>` / `<<` 是完整协议帧（可能含工作区/对话内容），只留在内存环形缓冲，不写系统日志。
        if (!line.startsWith(">>") && !line.startsWith("<<")) Log.i(TAG, line)
        logRing.addLast(line)
        while (logRing.size > MAX_LOG_LINES) logRing.removeFirst()
        _logs.value = logRing.toList()
    }

    // ----------------------------------------------------------------------
    // socket 回调 → 回喂输入
    // ----------------------------------------------------------------------

    private val socketListener = object : RelayWebSocket.Listener {
        override fun onOpen(generation: Long) {
            postStep(SocketInputs.ready(generation))
        }

        override fun onText(generation: Long, text: String) {
            postStep(SocketInputs.textFrame(generation, text))
        }

        override fun onBinary(generation: Long, base64: String) {
            postStep(SocketInputs.binaryFrame(generation, base64))
        }

        override fun onFailure(generation: Long, error: String) {
            postStep(SocketInputs.failed(generation, error))
        }

        override fun onClosed(generation: Long, code: Int, reason: String?) {
            postStep(SocketInputs.closed(generation, code, reason))
        }
    }

    /**
     * OkHttp 的 I/O 线程（或任意外部线程）→ 引擎单线程 dispatcher。
     *
     * 契约 §11 要求同一 handle 上的 native 调用不可重入，所以 socket 回调**必须**经过这里，
     * 不能就地调 `bridge.step`。
     */
    private fun postStep(input: JsonObject) {
        engineScope.launch { feed(input) }
    }
}

/** 引擎专用单线程 dispatcher（`dispose()` 时关闭）。 */
private fun newEngineDispatcher(): ExecutorCoroutineDispatcher =
    Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "zemote-engine").apply { isDaemon = true }
    }.asCoroutineDispatcher()
