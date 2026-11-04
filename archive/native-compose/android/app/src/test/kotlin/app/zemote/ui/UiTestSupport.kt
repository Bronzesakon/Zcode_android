package app.zemote.ui

import app.zemote.data.AppSettings
import app.zemote.data.AuthAccount
import app.zemote.data.AuthStore
import app.zemote.data.DeviceRecord
import app.zemote.data.RelayStatus
import app.zemote.engine.EngineEvent
import app.zemote.engine.EngineException
import app.zemote.engine.EngineState
import app.zemote.engine.NativeError
import app.zemote.engine.RelayState
import app.zemote.ui.chat.ChatOps
import app.zemote.ui.devices.DeviceRepository
import app.zemote.ui.update.ApkInstaller
import app.zemote.update.InstallResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * 表现层单测共用的假实现与规则。
 *
 * 全部依赖都通过构造注入，所以 ViewModel 能在**纯 JVM** 上构造与断言：不碰 DataStore
 * （它在 Windows 上的原子改名会抛 `IOException`）、不碰 Keystore、不碰 native、不碰网络。
 */

/**
 * 把主线程换成测试调度器。
 *
 * ViewModel 用 `viewModelScope`（`Dispatchers.Main.immediate`），JVM 单测里没有 Android
 * 主线程，必须先把 `Dispatchers.Main` 换掉。用 [UnconfinedTestDispatcher]：协程按调用顺序
 * 同步跑完，断言不需要等待（需要推进虚拟时间的地方显式 `advanceUntilIdle`）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

/** 造一台设备记录；URL 默认是「凭据可用」的明文远控链接。 */
fun deviceRecord(
    id: String = "d1",
    name: String = "桌面",
    remoteUrl: String? = "https://zcode.z.ai/remote/v4?sid=s1&hash=h1&t=1",
    host: String = "zcode.z.ai",
    sid: String = "s1",
    addedAt: Long = 1_700_000_000_000L,
    lastConnectedAt: Long? = null,
    lastRelayStatus: RelayStatus = RelayStatus.IDLE,
    sortKey: Long = 1L,
): DeviceRecord = DeviceRecord(
    id = id,
    name = name,
    remoteUrl = remoteUrl,
    host = host,
    sid = sid,
    addedAt = addedAt,
    lastConnectedAt = lastConnectedAt,
    lastRelayStatus = lastRelayStatus,
    sortKey = sortKey,
)

/**
 * [DeviceRepository] 的内存替身。
 *
 * [readFailure] / [writeFailure] 可随时设置，用来验「加载失败」与「写入失败」两条路径。
 */
class FakeDeviceRepository(
    initialDevices: List<DeviceRecord> = emptyList(),
    initialCurrentId: String? = null,
    initialSettings: AppSettings = AppSettings(),
) : DeviceRepository {

    val devicesFlow = MutableStateFlow(initialDevices)

    val currentFlow = MutableStateFlow(initialDevices.firstOrNull { it.id == initialCurrentId })

    val settingsFlow = MutableStateFlow(initialSettings)

    /** 非空时读列表直接抛它（验「加载态 → 错误态」）。 */
    var readFailure: Throwable? = null

    /** 非空时所有写操作抛它（验「操作失败 → 错误态」）。 */
    var writeFailure: Throwable? = null

    /** 只让 `setCurrentDevice` 失败（验「选中失败不否定配对成功」）。 */
    var setCurrentFailure: Throwable? = null

    /** `addDevice` 生成的记录 id（确定性，便于断言）。 */
    var nextDeviceId: String = "new-device"

    val addedUrls = mutableListOf<String>()

    val addedNames = mutableListOf<String?>()

    val setCurrentIds = mutableListOf<String?>()

    val removedIds = mutableListOf<String>()

    val renames = mutableListOf<Pair<String, String>>()

    val settingsUpdates = mutableListOf<AppSettings>()

    override val devices: Flow<List<DeviceRecord>> = flow {
        readFailure?.let { throw it }
        emitAll(devicesFlow)
    }

    override val currentDevice: Flow<DeviceRecord?> = currentFlow

    override val settings: Flow<AppSettings> = flow {
        readFailure?.let { throw it }
        emitAll(settingsFlow)
    }

    override suspend fun addDevice(url: String, name: String?): DeviceRecord {
        writeFailure?.let { throw it }
        addedUrls += url
        addedNames += name
        val record = deviceRecord(
            id = nextDeviceId,
            name = name ?: "新设备",
            remoteUrl = url,
            host = "host.example",
            sid = "sid-from-url",
        )
        devicesFlow.value = devicesFlow.value + record
        return record
    }

    override suspend fun removeDevice(id: String): Boolean {
        writeFailure?.let { throw it }
        removedIds += id
        val before = devicesFlow.value.size
        devicesFlow.value = devicesFlow.value.filterNot { it.id == id }
        if (currentFlow.value?.id == id) currentFlow.value = null
        return devicesFlow.value.size != before
    }

    override suspend fun setCurrentDevice(id: String?) {
        setCurrentFailure?.let { throw it }
        writeFailure?.let { throw it }
        setCurrentIds += id
        currentFlow.value = devicesFlow.value.firstOrNull { it.id == id }
    }

    override suspend fun renameDevice(id: String, name: String): Boolean {
        writeFailure?.let { throw it }
        renames += id to name
        val existing = devicesFlow.value.firstOrNull { it.id == id } ?: return false
        devicesFlow.value = devicesFlow.value.map { if (it.id == id) it.copy(name = name) else it }
        return existing.name != name
    }

    override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        writeFailure?.let { throw it }
        val next = transform(settingsFlow.value)
        settingsUpdates += next
        settingsFlow.value = next
    }

    /** 收到的 `recordRelayStatus` 调用（按顺序）。 */
    val relayStatusWrites = mutableListOf<RelayStatusWrite>()

    /** 收到的 `recordRelayFailure` 调用（按顺序）。 */
    val relayFailureWrites = mutableListOf<RelayFailureWrite>()

    override suspend fun recordRelayStatus(
        id: String,
        status: RelayStatus,
        connectedAt: Long?,
    ): Boolean {
        writeFailure?.let { throw it }
        relayStatusWrites += RelayStatusWrite(id, status, connectedAt)
        val existing = devicesFlow.value.firstOrNull { it.id == id } ?: return false
        val next = existing.copy(
            lastRelayStatus = status,
            lastConnectedAt = connectedAt ?: existing.lastConnectedAt,
            lastRelayError = if (status == RelayStatus.PAIRED) null else existing.lastRelayError,
            lastRelayErrorMessage =
                if (status == RelayStatus.PAIRED) null else existing.lastRelayErrorMessage,
        )
        devicesFlow.value = devicesFlow.value.map { if (it.id == id) next else it }
        // 与真 DeviceStore 一致：当前设备是从列表里派生出来的，写盘后它也会跟着变。
        if (currentFlow.value?.id == id) currentFlow.value = next
        return next != existing
    }

    override suspend fun recordRelayFailure(
        id: String,
        reason: String?,
        message: String?,
    ): Boolean {
        writeFailure?.let { throw it }
        relayFailureWrites += RelayFailureWrite(id, reason, message)
        val existing = devicesFlow.value.firstOrNull { it.id == id } ?: return false
        val next = existing.copy(lastRelayError = reason, lastRelayErrorMessage = message)
        devicesFlow.value = devicesFlow.value.map { if (it.id == id) next else it }
        if (currentFlow.value?.id == id) currentFlow.value = next
        return next != existing
    }
}

/** [FakeDeviceRepository.recordRelayStatus] 的一次调用。 */
data class RelayStatusWrite(val id: String, val status: RelayStatus, val connectedAt: Long?)

/** [FakeDeviceRepository.recordRelayFailure] 的一次调用。 */
data class RelayFailureWrite(val id: String, val reason: String?, val message: String?)

/**
 * [EnginePort] 的替身。
 *
 * 默认行为：`ensureSession` 记下 URL 并把引擎置为 `Running(1)`（模拟建会话成功）。
 * 想验别的路径就改 [ensureSessionHook] / 各 `*Failure`。
 */
class FakeEnginePort(
    initialEngineState: EngineState = EngineState.Idle,
    initialRelayState: RelayState = RelayState.Idle,
) : EnginePort {

    val engineStateFlow = MutableStateFlow(initialEngineState)

    val relayStateFlow = MutableStateFlow(initialRelayState)

    val relayFailureFlow = MutableStateFlow<EngineEvent.RelayFailure?>(null)

    val eventsFlow = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)

    val connectedUrlFlow = MutableStateFlow<String?>(null)

    val bridgeSessionFlow = MutableStateFlow<Long?>(null)

    val bridgeWorkspaceKeyFlow = MutableStateFlow<String?>(null)

    override val engineState: StateFlow<EngineState> = engineStateFlow.asStateFlow()

    override val relayState: StateFlow<RelayState> = relayStateFlow.asStateFlow()

    override val relayFailure: StateFlow<EngineEvent.RelayFailure?> = relayFailureFlow.asStateFlow()

    override val events: SharedFlow<EngineEvent> = eventsFlow.asSharedFlow()

    override val connectedUrl: StateFlow<String?> = connectedUrlFlow.asStateFlow()

    override val bridgeSession: StateFlow<Long?> = bridgeSessionFlow.asStateFlow()

    override val bridgeWorkspaceKey: StateFlow<String?> = bridgeWorkspaceKeyFlow.asStateFlow()

    /** `ensureSession` 被调用时做的事（默认：记 URL + 置 Running）。 */
    var ensureSessionHook: (String) -> Unit = { url ->
        connectedUrlFlow.value = url
        engineStateFlow.value = EngineState.Running(1)
    }

    val ensuredUrls = mutableListOf<String>()

    /** 每次 `ensureSession` 传入的 token（与 [ensuredUrls] 一一对应）。 */
    val ensuredTokens = mutableListOf<String?>()

    var disconnected: Boolean = false
        private set

    var waitPairedFailure: Throwable? = null

    var bootstrapResult: JsonElement = JsonNull

    var bootstrapFailure: Throwable? = null

    var openBridgeFailure: Throwable? = null

    var openBridgeSession: Long = 7L

    var waitHealthyFailure: Throwable? = null

    var pokeFailure: Throwable? = null

    var callResult: JsonElement = JsonNull

    var callFailure: Throwable? = null

    val openedBridges = mutableListOf<Pair<String, String?>>()
    val openedBridgeIntents = mutableListOf<Triple<String, String?, JsonObject?>>()

    val pushedViewStates = mutableListOf<Pair<String, String?>>()

    val commands = mutableListOf<JsonObject>()

    var pokeCount: Int = 0
        private set

    override suspend fun ensureSession(url: String, token: String?) {
        ensuredUrls += url
        ensuredTokens += token
        ensureSessionHook(url)
    }

    override fun disconnect() {
        disconnected = true
        connectedUrlFlow.value = null
        engineStateFlow.value = EngineState.Idle
    }

    var reconnectCount: Int = 0
        private set

    override suspend fun reconnect() {
        reconnectCount += 1
        val url = connectedUrlFlow.value ?: return
        disconnected = false
        ensureSessionHook(url)
    }

    override suspend fun waitPaired(timeoutMs: Long) {
        waitPairedFailure?.let { throw it }
    }

    override suspend fun bootstrap(): JsonElement {
        bootstrapFailure?.let { throw it }
        return bootstrapResult
    }

    override suspend fun listWorkspaces(): JsonElement = bootstrapResult

    override suspend fun openBridge(
        workspaceKey: String,
        taskId: String?,
        taskHostId: String?,
        project: JsonObject?,
    ): Long {
        openedBridges += workspaceKey to taskId
        openedBridgeIntents += Triple(workspaceKey, taskHostId, project)
        openBridgeFailure?.let { throw it }
        bridgeSessionFlow.value = openBridgeSession
        bridgeWorkspaceKeyFlow.value = workspaceKey
        return openBridgeSession
    }

    override suspend fun waitHealthy(session: Long, timeoutMs: Long) {
        waitHealthyFailure?.let { throw it }
    }

    override suspend fun sendMobileViewState(workspaceKey: String, taskId: String?) {
        pushedViewStates += workspaceKey to taskId
    }

    override suspend fun pokeRelay() {
        pokeCount += 1
        pokeFailure?.let { throw it }
    }

    override suspend fun call(command: JsonObject): JsonElement {
        commands += command
        callFailure?.let { throw it }
        return callResult
    }

    // ---- §13 对话层与定时任务层 ----

    /** `conversationOpen` 返回的 key（默认与桥会话号拼成稳定值，便于断言）。 */
    var conversationOpenKey: String = "conv-key"

    var conversationOpenFailure: Throwable? = null

    /** `conversationCall` 的返回；`conversationCallFailure` 非空时抛它。 */
    var conversationCallResult: JsonElement = JsonNull

    var conversationCallFailure: Throwable? = null

    /** `automationCall` 的返回；`automationCallFailure` 非空时抛它。 */
    var automationCallResult: JsonElement = JsonNull

    var automationCallFailure: Throwable? = null

    /** `(session, scope)` 的 `conversationOpen` 调用记录。 */
    val conversationOpens = mutableListOf<Pair<Long, JsonObject>>()

    /** `(session, key, op, args)` 的 `conversationCall` 调用记录。 */
    val conversationCalls = mutableListOf<ConversationCallRecord>()

    /** `(session, op, args)` 的 `automationCall` 调用记录。 */
    val automationCalls = mutableListOf<AutomationCallRecord>()

    val conversationCloses = mutableListOf<Pair<Long, String>>()

    override suspend fun conversationOpen(session: Long, scope: JsonObject): String {
        conversationOpens += session to scope
        conversationOpenFailure?.let { throw it }
        return conversationOpenKey
    }

    override suspend fun conversationCall(
        session: Long,
        key: String,
        op: String,
        args: JsonObject,
    ): JsonElement {
        conversationCalls += ConversationCallRecord(session, key, op, args)
        conversationCallFailure?.let { throw it }
        return conversationCallResult
    }

    override suspend fun conversationCallNoWait(
        session: Long,
        key: String,
        op: String,
        args: JsonObject,
    ) {
        // 订阅类 op 与普通 op 记在同一个列表：对测试而言都是「发出去的对话命令」。
        conversationCalls += ConversationCallRecord(session, key, op, args)
        conversationCallFailure?.let { throw it }
    }

    override suspend fun conversationClose(session: Long, key: String) {
        conversationCloses += session to key
    }

    override suspend fun automationCall(
        session: Long,
        op: String,
        args: JsonObject,
    ): JsonElement {
        automationCalls += AutomationCallRecord(session, op, args)
        automationCallFailure?.let { throw it }
        return automationCallResult
    }

    /** `channel_call` 的返回；`channelCallFailure` 非空时抛它。 */
    var channelCallResult: JsonElement = JsonNull

    var channelCallFailure: Throwable? = null

    /** `(session, channel, method, args)` 的 `channelCall` 调用记录。 */
    val channelCalls = mutableListOf<ChannelCallRecord>()

    /** `snapshot()` 的返回。 */
    var snapshotResult: JsonElement = JsonNull

    override suspend fun channelCall(
        session: Long,
        channel: String,
        method: String,
        args: JsonArray,
        timeoutMs: Long,
    ): JsonElement {
        channelCalls += ChannelCallRecord(session, channel, method, args, timeoutMs)
        channelCallFailure?.let { throw it }
        return channelCallResult
    }

    override suspend fun snapshot(): JsonElement = snapshotResult

    /** `conversationSnapshot()` 的返回；`conversationSnapshotFailure` 非空时抛它。 */
    var conversationSnapshotResult: JsonElement = JsonNull

    var conversationSnapshotFailure: Throwable? = null

    /** `(session, key)` 的 `conversationSnapshot` 调用记录。 */
    val conversationSnapshotCalls = mutableListOf<Pair<Long, String>>()

    override suspend fun conversationSnapshot(session: Long, key: String): JsonElement {
        conversationSnapshotCalls += session to key
        conversationSnapshotFailure?.let { throw it }
        return conversationSnapshotResult
    }
}

/** [FakeEnginePort.channelCall] 的一次调用。 */
data class ChannelCallRecord(
    val session: Long,
    val channel: String,
    val method: String,
    val args: JsonArray,
    val timeoutMs: Long,
)

/** [FakeEnginePort.conversationCall] 的一次调用。 */
data class ConversationCallRecord(
    val session: Long,
    val key: String,
    val op: String,
    val args: JsonObject,
)

/** [FakeEnginePort.automationCall] 的一次调用。 */
data class AutomationCallRecord(val session: Long, val op: String, val args: JsonObject)

/** [ChatOps] 的替身：记下每次 `op`，按 [result] / [failure] 返回。 */
class FakeChatOps : ChatOps {

    val calls = mutableListOf<Pair<String, JsonObject>>()

    /** 每次调用传进来的 `conversation_open` scope（用于断言「真实 path + identity」）。 */
    val scopes = mutableListOf<JsonObject?>()

    var result: JsonElement = JsonNull

    var failure: Throwable? = null

    /**
     * 调用时钩子：`subscribe` 现在要等首帧才算成功，测试用它模拟桌面端推快照。
     */
    var onCall: ((op: String) -> Unit)? = null

    override suspend fun call(op: String, args: JsonObject, scope: JsonObject?): JsonElement {
        calls += op to args
        scopes += scope
        onCall?.invoke(op)
        failure?.let { throw it }
        return result
    }
}

/** [ApkInstaller] 的替身。 */
class FakeApkInstaller : ApkInstaller {

    var result: InstallResult = InstallResult.Launched

    var settingsOpened: Boolean = true

    val installed = mutableListOf<File>()

    override fun install(apk: File): InstallResult {
        installed += apk
        return result
    }

    override fun openUnknownSourcesSettings(): Boolean = settingsOpened
}

/** 造一个引擎错误（`EngineException` 的构造需要 `NativeError`）。 */
fun engineError(kind: String = "timeout", message: String? = "boom"): EngineException =
    EngineException(NativeError(kind = kind, message = message))

/** 内存版 [AuthStore]：JVM 单测用，不碰 DataStore / Keystore。 */
class FakeAuthStore(
    initialToken: String? = null,
    initialAccount: AuthAccount? = null,
) : AuthStore {

    var tokenValue: String? = initialToken
        private set

    private val _account = MutableStateFlow(initialAccount)

    override val account: StateFlow<AuthAccount?> = _account.asStateFlow()

    override suspend fun token(): String? = tokenValue

    override suspend fun save(
        provider: String,
        jwt: String,
        displayName: String,
        avatarUrl: String?,
        expiresAt: Long?,
    ) {
        tokenValue = jwt
        _account.value = AuthAccount(
            provider = provider,
            displayName = displayName,
            avatarUrl = avatarUrl,
            expiresAt = expiresAt,
        )
    }

    override suspend fun clear() {
        tokenValue = null
        _account.value = null
    }
}
