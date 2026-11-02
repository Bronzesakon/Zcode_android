package app.zemote.ui

import app.zemote.auth.RemoteTokenException
import app.zemote.core.JniNativeBridge
import app.zemote.engine.EngineConfig
import app.zemote.engine.EngineEvent
import app.zemote.engine.EngineException
import app.zemote.engine.EngineState
import app.zemote.engine.NativeError
import app.zemote.engine.RelayState
import app.zemote.engine.ZemoteEngine
import app.zemote.pairing.PairingUrl
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 表现层看到的**引擎会话面**。
 *
 * ## 为什么再包一层（引擎本身已经是给 ViewModel 用的 API）
 *
 * 只有一个理由：**一个引擎实例 = 一条连接**。[ZemoteEngine.start] 幂等、`dispose()` 之后
 * 不可复用（见 `docs/ENGINE-API.md` §3.2），所以「换设备」必然是「拆掉旧引擎、建新引擎」。
 * 那个「拆旧建新 + 把新引擎的流重新接出来」的动作需要一个**稳定的持有者**——它不能放在
 * ViewModel 里（ViewModel 会随界面销毁，而会话不该），也不该散落在多个 ViewModel 里。
 * 本接口就是那个持有者，生产实现是 [ZemoteEnginePort]。
 *
 * 第二个好处是单测：ViewModel 的状态映射可以用假实现跑，不需要 `FakeNativeBridge`
 * 与真实 dispatcher 参与（引擎自己的单测已经覆盖了它那一层）。
 *
 * ## 线程
 *
 * 所有 `StateFlow` / `SharedFlow` 都可从任意线程读；`ensureSession` / `disconnect` 非挂起、
 * 内部同步；其余是挂起函数，内部走引擎自己的单线程 dispatcher（契约 §11）。
 */
interface EnginePort {

    /** 引擎自身生命周期；没有会话时是 [EngineState.Idle]。 */
    val engineState: StateFlow<EngineState>

    /** relay 状态机（六态文案的数据源）。 */
    val relayState: StateFlow<RelayState>

    /** 最近一次 `relay_failure`；`null` = 还没失败过。 */
    val relayFailure: StateFlow<EngineEvent.RelayFailure?>

    /**
     * 全部引擎输出事件的旁路流。
     *
     * **会丢事件**（引擎侧缓冲满即丢，见 `docs/ENGINE-API.md` §8），关键状态请读
     * [engineState] / [relayState] / [relayFailure]。订阅它只是为了看
     * `workspace_list_updated` / `conversation_event` / `bridge_*` 这类增量。
     */
    val events: SharedFlow<EngineEvent>

    /** 当前会话使用的远控 URL；`null` = 没有会话。 */
    val connectedUrl: StateFlow<String?>

    /**
     * 会话序号：每次**真正重建**引擎（换 URL / 换 JWT / 手动重连）时 +1。
     *
     * 宿主用它判断本地的桥/对话缓存是否已经失效——只比较 URL/JWT 看不出引擎已经换了。
     */
    val sessionSerial: Long get() = 0L

    /** 最近一次 [openBridge] 返回的桥会话号；`null` = 还没开过桥。 */
    val bridgeSession: StateFlow<Long?>

    /**
     * 最近一次 [openBridge] 的 `workspaceKey`；`null` = 还没开过桥。
     *
     * 用途只有一个：给 §13 的 `conversation_open` 造 scope（`conversation_key(scope)` 取
     * `workspaceIdentity ?? workspacePath`）。桥的 `workspaceKey` 本身就是 Dart 的
     * `workspaceKeyOf(w) = identity?.trim() || path`，所以放进 `workspacePath` 得到的
     * conversation key 与 Dart 一致。
     */
    val bridgeWorkspaceKey: StateFlow<String?>

    /**
     * 确保会话与 [url] + [token] 一致：不一致（含没有会话）就拆掉旧的、建新的并 `start`。
     *
     * **幂等**：同一个 URL + 同一个 OAuth JWT 重复调用什么都不做（不会重连、不会清掉已有状态）。
     * JWT 变化（重新登录）必须重建会话。
     *
     * ## token 是 OAuth JWT，不是 ws2 的 token
     *
     * 3.16.1 的 ws2 需要 `POST /api/v1/remote/token`（`{"role":"terminal"}`）换来的短 token；
     * 实现负责在**真正建会话时**完成这次交换。旧链接（带 `hash`）不需要交换，直接传 `null`。
     *
     * @throws RemoteTokenException OAuth JWT 失效（`auth-required`）或中继不可用。
     */
    suspend fun ensureSession(url: String, token: String? = null)

    /** 拆掉会话（`dispose` 旧引擎并把状态复位）。没有会话时无副作用。 */
    fun disconnect()

    /** `wait_paired`：等 relay 进入 paired。超时由核心的定时器驱动。 */
    suspend fun waitPaired(timeoutMs: Long = 30_000)

    /** `bootstrap`：配对后取全量工作区/任务快照。 */
    suspend fun bootstrap(): JsonElement

    /** `list_workspaces`：刷新工作区列表。 */
    suspend fun listWorkspaces(): JsonElement

    /** `open_bridge`：为工作区开桥，返回桥会话号。 */
    suspend fun openBridge(
        workspaceKey: String,
        taskId: String? = null,
        taskHostId: String? = null,
        project: JsonObject? = null,
    ): Long

    /** `bridge_wait_healthy`：等桥恢复健康。 */
    suspend fun waitHealthy(session: Long, timeoutMs: Long = 45_000)

    /** `send_mobile_view_state`：把移动端焦点推给桌面。不产生挂起操作。 */
    suspend fun sendMobileViewState(workspaceKey: String, taskId: String? = null)

    /** `poke_relay`：回前台探活。不产生挂起操作。 */
    suspend fun pokeRelay()

    /**
     * §13.2 `conversation_open`：打开（或复用）一个对话，返回 conversation key。
     *
     * 重复 open 同一 key 幂等。
     */
    suspend fun conversationOpen(session: Long, scope: JsonObject): String

    /**
     * §13.2 `conversation_call`：把一条 `op` + `args` 透传给某个对话，返回该 `op` 的结果。
     *
     * 表现层只搬运 JSON，不理解 `op`（清单见 `docs/FFI.md` §13.5.1）。
     */
    suspend fun conversationCall(
        session: Long,
        key: String,
        op: String,
        args: JsonObject,
    ): JsonElement

    /**
     * 发送一个**订阅类**对话 op（`subscribe` / `subscribeSessionsIndex`），不等 `completed`。
     *
     * wire 上没有顶层 `completed`，Rust 在 ack 后合成 host-level `Completed`；订阅是长期
     * 事件流，用 [conversationCall] 会把 ack 延迟变成界面阻塞。后续帧通过
     * `conversation_event` 到达。
     */
    suspend fun conversationCallNoWait(
        session: Long,
        key: String,
        op: String,
        args: JsonObject,
    )

    /**
     * §13.2 `conversation_close`：释放一个对话。不产生挂起操作。
     *
     * **在飞调用不会被失败**，只能等宿主兜底超时（见 [ZemoteEngine.conversationClose] 的 KDoc）。
     */
    suspend fun conversationClose(session: Long, key: String)

    /**
     * §13.3 `automation_call`：定时任务层（`list` / `create` / `setEnabled` / `delete` /
     * `runNow` / `restart`）。结果经顶层 `completed` 返回。
     */
    suspend fun automationCall(session: Long, op: String, args: JsonObject): JsonElement

    /**
     * 通用通道调用：任意通道 / 任意方法，`args` 是位置参数数组。
     *
     * 设置页（`setting`）、任务投影（`window-controller`）、技能/插件/MCP/子代理等
     * 没有专用包装的通道都走这里。
     */
    suspend fun channelCall(
        session: Long,
        channel: String,
        method: String,
        args: JsonArray = JsonArray(emptyList()),
        timeoutMs: Long = 30_000,
    ): JsonElement

    /**
     * §9 `nativeQuery {"query":"snapshot"}`：读核心快照（relay / 桥 / 设备信息）。
     *
     * 返回值**绝不含 `passHash`**。`conversations` / `sessionsIndex` 不在核心快照里，
     * 用 [conversationSnapshot] 取。
     */
    suspend fun snapshot(): JsonElement

    /**
     * §9.1 `nativeQuery {"query":"conversation"}`：读某个对话的传输层快照。
     *
     * 含 `conversations`（按 sessionId）与 `sessionsIndex`（`lastAssistantPreview` 的来源）。
     * 该 conversation 未 open 或已 close 时抛 `invalidState`。
     */
    suspend fun conversationSnapshot(session: Long, key: String): JsonElement

    /**
     * 通用命令逃生舱（`docs/FFI.md` §8 的 `nativeCall`）。
     *
     * 给 `ChatOps` 之类的**协议透传**用：表现层只搬运 JSON，不理解协议。
     */
    suspend fun call(command: JsonObject): JsonElement
}

/**
 * 生产实现：持有一个可替换的 [ZemoteEngine]，把它所有的流转接成稳定的 `StateFlow`。
 *
 * @param scope 应用级作用域（引擎的定时器与回喂任务挂在它下面；转接用的收集任务也挂这里）。
 * @param appVersion 传给核心的 `appVersion`（生产是 `BuildConfig.VERSION_NAME`）。
 * @param deviceName 传给核心的 `deviceName`（生产是 `Build.MODEL`）。
 * @param theme 建会话时取一次当前主题（`"dark"` / `"light"`；`null` = 不指定）。
 * @param terminalTokenProvider OAuth JWT → terminal token。生产由 `AppContainer` 注入
 *   [app.zemote.auth.RemoteTokenClient]；单测可注入假实现。
 * @param newEngine 引擎工厂。**仅供单测注入**（用假 bridge + 测试 dispatcher）；
 *   生产不要传，默认就是 `JniNativeBridge` + 引擎自建的单线程 dispatcher。
 */
class ZemoteEnginePort(
    private val scope: CoroutineScope,
    private val appVersion: String?,
    private val deviceName: String?,
    private val theme: () -> String?,
    private val terminalTokenProvider: suspend (host: String, jwt: String) -> String = { _, _ ->
        throw RemoteTokenException("relay-unavailable", "terminal token 交换器未配置")
    },
    private val newEngine: (CoroutineScope) -> ZemoteEngine = { ZemoteEngine(JniNativeBridge(), it) },
) : EnginePort {

    private val lock = Any()

    @Volatile
    private var engine: ZemoteEngine? = null

    private var relayJobs: List<Job> = emptyList()

    private val _engineState = MutableStateFlow<EngineState>(EngineState.Idle)
    private val _relayState = MutableStateFlow(RelayState.Idle)
    private val _relayFailure = MutableStateFlow<EngineEvent.RelayFailure?>(null)
    private val _connectedUrl = MutableStateFlow<String?>(null)

    /** 当前会话实际用于 ws2 的 terminal token；只用于幂等判断，不对外暴露。 */
    @Volatile
    private var connectedToken: String? = null

    /** 建会话时传入的 OAuth JWT；重新登录（JWT 变化）必须重建会话。 */
    @Volatile
    private var connectedAuthToken: String? = null
    private val _bridgeSession = MutableStateFlow<Long?>(null)

    private val _bridgeWorkspaceKey = MutableStateFlow<String?>(null)

    /**
     * 事件转接用 `MutableSharedFlow` + `tryEmit`（而不是 `MutableStateFlow`）：
     * 事件是**序列**不是状态，且转接这一层不允许反压引擎的旁路流。
     */
    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = EVENT_BUFFER)

    override val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    override val relayState: StateFlow<RelayState> = _relayState.asStateFlow()

    override val relayFailure: StateFlow<EngineEvent.RelayFailure?> = _relayFailure.asStateFlow()

    override val events: SharedFlow<EngineEvent> = _events.asSharedFlow()

    override val connectedUrl: StateFlow<String?> = _connectedUrl.asStateFlow()

    @Volatile
    override var sessionSerial: Long = 0L
        private set

    override val bridgeSession: StateFlow<Long?> = _bridgeSession.asStateFlow()

    override val bridgeWorkspaceKey: StateFlow<String?> = _bridgeWorkspaceKey.asStateFlow()

    override suspend fun ensureSession(url: String, token: String?) {
        val target = url.trim()
        if (target.isEmpty()) return

        // 快路径：已经连着同一个 URL + 同一个 OAuth JWT，直接返回（不重复换 token）。
        synchronized(lock) {
            if (engine != null && _connectedUrl.value == target && connectedAuthToken == token) return
        }

        // 3.16.1：OAuth JWT 不能直接连 ws2，必须先换 terminal token。
        // 旧链接（带 hash）不需要，`resolveTerminalToken` 会返回 null，继续走 HMAC proof。
        // 网络交换放在锁外，避免阻塞其他会话操作。
        val terminalToken = resolveTerminalToken(target, token)
        val refreshHost = if (token != null && !PairingUrl.hasPassHash(target)) {
            hostOf(target) ?: throw RemoteTokenException("bad-input", "远控链接缺少 host")
        } else {
            null
        }

        synchronized(lock) {
            if (engine != null && _connectedUrl.value == target && connectedAuthToken == token) return
            disposeLocked()
            val created = newEngine(scope)
            engine = created
            _connectedUrl.value = target
            connectedAuthToken = token
            connectedToken = terminalToken
            // 3.16.1：每次 ws 重连都要重新换 terminal token（网页 `connect()` 第一步就是
            // `await getToken()`）。首连用上面已换好的；旧链接（hash）不需要。
            if (token != null && refreshHost != null) {
                created.terminalTokenRefresher = refresherFor(created, refreshHost, token)
            }
            relayJobs = attach(created)
            // 顺序：先挂转接再 start，避免构造事件（relay TimerStart / 日志）在转接之前发生。
            // 转接任务本身是异步的，所以最早的一两条**旁路事件**仍可能错过（StateFlow 会自动补最新值，
            // 受影响的只有 events 这条会丢事件的旁路流，与引擎层的既有约定一致）。
            created.start(
                EngineConfig(
                    url = target,
                    appVersion = appVersion,
                    deviceName = deviceName,
                    theme = theme(),
                    token = terminalToken,
                ),
            )
            // 新引擎 = 新桥/新对话；宿主缓存的 bridge/conversation key 全部失效。
            sessionSerial += 1
        }
    }

    /**
     * OAuth JWT → ws2 的 terminal token。
     *
     * 旧链接（URL 里有 `hash`）走 HMAC，不需要 token；新链接必须先换。host 直接取 URL 的
     * host（`zcode.z.ai`），与网页端 `new URL(Er.token.path, origin)` 一致。
     */
    private suspend fun resolveTerminalToken(url: String, jwt: String?): String? {
        if (jwt == null) return null
        if (PairingUrl.hasPassHash(url)) return null
        val host = hostOf(url) ?: throw RemoteTokenException("bad-input", "远控链接缺少 host")
        return terminalTokenProvider(host, jwt)
    }

    private fun hostOf(url: String): String? = try {
        URI(url).host
    } catch (_: Exception) {
        null
    }

    /**
     * 重连时的 token 刷新器。
     *
     * 401（`auth-required`）先上报 `relay_failure` 再返回 `null`：UI 收到后清登录态并断开，
     * 不会继续拿同一个失效 JWT 打服务端。其余失败只让核心退避重试，保留登录态。
     *
     * 定义在 `synchronized` 块外：Kotlin 2.x 拒绝在临界区内出现挂起点。
     */
    private fun refresherFor(
        engine: ZemoteEngine,
        host: String,
        jwt: String,
    ): suspend () -> String? = {
        try {
            terminalTokenProvider(host, jwt)
        } catch (e: RemoteTokenException) {
            engine.reportRelayFailure(e.reason, e.message)
            null
        }
    }

    override fun disconnect() {
        synchronized(lock) { disposeLocked() }
    }

    override suspend fun waitPaired(timeoutMs: Long) = requireEngine().waitPaired(timeoutMs)

    override suspend fun bootstrap(): JsonElement = requireEngine().bootstrap()

    override suspend fun listWorkspaces(): JsonElement = requireEngine().listWorkspaces()

    override suspend fun openBridge(
        workspaceKey: String,
        taskId: String?,
        taskHostId: String?,
        project: JsonObject?,
    ): Long {
        val session = requireEngine().openBridge(
            workspaceKey = workspaceKey,
            taskId = taskId,
            taskHostId = taskHostId,
            project = project,
        )
        _bridgeSession.value = session
        _bridgeWorkspaceKey.value = workspaceKey
        return session
    }

    override suspend fun waitHealthy(session: Long, timeoutMs: Long) =
        requireEngine().waitHealthy(session = session, timeoutMs = timeoutMs)

    override suspend fun sendMobileViewState(workspaceKey: String, taskId: String?) =
        requireEngine().sendMobileViewState(workspaceKey = workspaceKey, taskId = taskId)

    override suspend fun pokeRelay() = requireEngine().pokeRelay()

    override suspend fun conversationOpen(session: Long, scope: JsonObject): String =
        requireEngine().conversationOpen(session = session, scope = scope)

    override suspend fun conversationCall(
        session: Long,
        key: String,
        op: String,
        args: JsonObject,
    ): JsonElement = requireEngine().conversationCall(session, key, op, args)

    override suspend fun conversationCallNoWait(
        session: Long,
        key: String,
        op: String,
        args: JsonObject,
    ) = requireEngine().conversationCallNoWait(session, key, op, args)

    override suspend fun conversationClose(session: Long, key: String) =
        requireEngine().conversationClose(session = session, key = key)

    override suspend fun automationCall(session: Long, op: String, args: JsonObject): JsonElement =
        requireEngine().automationCall(session = session, op = op, args = args)

    override suspend fun channelCall(
        session: Long,
        channel: String,
        method: String,
        args: JsonArray,
        timeoutMs: Long,
    ): JsonElement = requireEngine().channelCall(
        session = session,
        channel = channel,
        method = method,
        args = args,
        timeoutMs = timeoutMs,
    )

    override suspend fun snapshot(): JsonElement = requireEngine().snapshot()

    override suspend fun conversationSnapshot(session: Long, key: String): JsonElement =
        requireEngine().conversationSnapshot(session, key)

    override suspend fun call(command: JsonObject): JsonElement = requireEngine().call(command)

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private fun requireEngine(): ZemoteEngine =
        engine ?: throw EngineException(NativeError("invalidState", "引擎尚未启动（没有会话）"))

    private fun disposeLocked() {
        relayJobs.forEach { it.cancel() }
        relayJobs = emptyList()
        engine?.dispose()
        engine = null
        _connectedUrl.value = null
        connectedToken = null
        connectedAuthToken = null
        _bridgeSession.value = null
        _bridgeWorkspaceKey.value = null
        _relayFailure.value = null
        _relayState.value = RelayState.Idle
        _engineState.value = EngineState.Idle
    }

    /**
     * 把 [created] 的四条流转接到本对象的 StateFlow / SharedFlow 上。
     *
     * 每条都带「还是当前引擎吗」的判据：拆旧建新时旧收集任务可能已经取到一个值正在写回，
     * 没有这个判据就会把**上一个会话**的状态盖到新会话上。
     */
    private fun attach(created: ZemoteEngine): List<Job> = listOf(
        scope.launch { created.state.collect { if (isCurrent(created)) _engineState.value = it } },
        scope.launch { created.relayState.collect { if (isCurrent(created)) _relayState.value = it } },
        scope.launch { created.relayFailure.collect { if (isCurrent(created)) _relayFailure.value = it } },
        scope.launch { created.events.collect { if (isCurrent(created)) _events.tryEmit(it) } },
    )

    private fun isCurrent(candidate: ZemoteEngine): Boolean = engine === candidate

    private companion object {
        /** 与引擎侧 `events` 的缓冲一致：转接层不该成为新的丢事件点。 */
        const val EVENT_BUFFER = 512
    }
}
