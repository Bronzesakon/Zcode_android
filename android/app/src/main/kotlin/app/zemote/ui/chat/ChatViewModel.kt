package app.zemote.ui.chat

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zemote.auth.RemoteTokenException
import app.zemote.data.AuthStore
import app.zemote.data.ChatCache
import app.zemote.data.DeviceRecord
import app.zemote.engine.EngineEvent
import app.zemote.engine.EngineException
import app.zemote.engine.EngineState
import app.zemote.engine.NativeError
import app.zemote.engine.RelayState
import app.zemote.log.ZLog
import app.zemote.pairing.PairingUrl
import app.zemote.ui.EnginePort
import app.zemote.ui.devices.DeviceRepository
import app.zemote.ui.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 一个工作区在界面上的投影。
 *
 * 字段名取自对端 `workspaces[i]` 的权威 schema（8 键，见 `archive/pre-3.16.1-auth/docs/desktop/06-工作区与桥生命周期.md`）：
 * `workspacePath`(必) / `label`(必) / `kind`(必) / `workspaceIdentity` / `workspacePurpose` /
 * `remoteSessionId` / `connectionState` / `lastConnectionError`。
 *
 * 两个容易搞错的地方：
 * - `workspaceIdentity` **有值才出现，永不出现 null**；键缺席就表示没有。
 * - 键规则是 `workspaceIdentity?.trim() || workspacePath`（identity 优先，且只 trim identity）。
 */
@Serializable
data class WorkspaceItem(    /** 工作区键（`open_bridge` 用它）。= `workspaceIdentity?.trim() || workspacePath`。 */
    val key: String,
    /** 真实文件系统路径。`conversation` scope 的 `workspacePath` 必须用它（对端拿它当 cwd）。 */
    val path: String,
    /** `workspaceIdentity`；缺席 = null。 */
    val identity: String? = null,
    /** 权威字段 `label`（必填）。 */
    val label: String,
    /** 权威字段 `kind`（必填）：`local` / `remote`。 */
    val kind: String? = null,
    /** `workspacePurpose`：`project` / `conversation`。 */
    val purpose: String? = null,
    /** `connectionState` 原始值（枚举未冻结，界面层自行映射）。 */
    val connectionState: String? = null,
    val lastError: String? = null,
    val remoteSessionId: String? = null,
    /** `project.readTree` 才有的项目 id；bootstrap 工作区没有。 */
    val projectId: String? = null,
    /** 项目所在 host（`local` 或远端 host id）；bootstrap 工作区没有。 */
    val hostId: String? = null,
    /**
     * 是否已由 bootstrap/workspace-list 确认「当前桌面窗口里可桥接」。
     *
     * `project.readTree` 补出来的项目是 `false`：它们不在桌面当前 tab 里，开桥时必须
     * 带 `taskHostId` / `project` 让桌面先 `prepareProjectWorkspace`，否则会被
     * 「目标工作区不在当前桌面窗口中」拒绝。
     */
    val bridgeable: Boolean = true,
    val tasks: List<TaskItem> = emptyList(),
) {
    /** 兼容旧界面代码（权威字段是 [label]）。 */
    val name: String get() = label

    /** 连接态徽标待界面层按 [connectionState] 映射（见 `ChatScreen` 的 TODO(UI)）。 */
    val connected: Boolean? get() = null

    val taskCount: Int
        get() = tasks.size
}

/**
 * 一个任务在界面上的投影。
 *
 * 字段名取自对端 `tasks[i]` 的权威 schema（16 键，见 `archive/pre-3.16.1-auth/docs/desktop/11-host进程与会话任务.md`）。
 * 注意：`pinned` / `archived` / `hasBackgroundWork` 是**「存在即 true」的旗标**——
 * 协议里 false 时整个键缺席，不是发 `false`；`unreadAt` 是 number，缺省表示无未读。
 */
@Serializable
data class TaskItem(
    /** `taskId`（协议里 `taskId === sessionId`）。 */
    val id: String,
    val title: String? = null,
    /** 标题来源（sessions-index 的 `titleSource`）：`custom` = 用户改名，优先级最高。 */
    val titleSource: String? = null,
    /** `displayStatus`：`idle` / `running` / `completed` / `error`。 */
    val displayStatus: String? = null,
    /** 存在即 true。 */
    val hasBackgroundWork: Boolean = false,
    val updatedAt: Long? = null,
    val createdAt: Long? = null,
    /** number；缺省 = 无未读。 */
    val unreadAt: Long? = null,
    /** 存在即 true。 */
    val pinned: Boolean = false,
    /** 存在即 true。 */
    val archived: Boolean = false,
    val provider: String? = null,
    /** 任务自带的工作区标识，用来把**顶层** `tasks` 数组归到各工作区。 */
    val workspaceKey: String? = null,
    /** 任务自己的真实路径；多根项目里它与工作区主根不同，打开时必须用它。 */
    val workspacePath: String? = null,
    /** 任务自己的远端 identity；本地任务为 null。 */
    val workspaceIdentity: String? = null,
    val workspaceLabel: String? = null,
    val workspaceKind: String? = null,
    val remoteSessionId: String? = null,
    /** `project.readTree.tasks[].hostId`；开非当前 tab 的项目任务时作为 `taskHostId`。 */
    val hostId: String? = null,
) {
    /** 兼容旧界面代码（权威字段是 [displayStatus]）。 */
    val status: String? get() = displayStatus

    /** 兼容旧界面代码（权威字段是 [unreadAt]）。 */
    val unread: Boolean get() = unreadAt != null

    /** 与网页端/对端一致的「在跑」判据。 */
    val running: Boolean get() = displayStatus == "running" || hasBackgroundWork
}

/**
 * 一条对话事件（`conversation_event`，`docs/FFI.md` §13.4）。
 *
 * 只做**搬运**：`type` 与原始体原样留着，具体渲染交给界面层（§13.5 的事件表还没冻结）。
 */
data class ConversationEventItem(
    /** 递增序号，作为列表 key（同一毫秒内多条事件也能稳定区分）。 */
    val seq: Long,
    val session: Long,
    val key: String,
    val type: String,
    val raw: JsonElement,
)

/** 待发送的本地附件（选中后先留在内存，发送时再上传）。 */
data class ChatAttachment(
    val name: String,
    val mime: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is ChatAttachment && name == other.name && mime == other.mime &&
            bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * name.hashCode() + mime.hashCode()
}

/**
 * 对话页的状态。
 *
 * @property engineState 引擎生命周期（[EngineState.Fatal] 的 `reason` 是可展示文案，`detail` 只进日志）。
 * @property relayState relay 六态。
 * @property relayFailure 最近一次 relay 失败。**不会自动清空**（`docs/ENGINE-API.md` §8），
 *   所以界面要配合 [relayFailureVisible] 决定显示。
 * @property bridgeDegraded 桥降级原因；`null` = 正常。
 * @property credentialsMissing 当前设备凭据不可用（Keystore 密钥丢失），需要重新配对。
 * @property conversationEvents 对话事件流（有上限，见 [MAX_CONVERSATION_EVENTS]）。
 */
data class ChatUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val engineState: EngineState = EngineState.Idle,
    val relayState: RelayState = RelayState.Idle,
    val relayFailure: EngineEvent.RelayFailure? = null,
    val bridgeSession: Long? = null,
    val bridgeDegraded: String? = null,
    val deviceName: String? = null,
    val credentialsMissing: Boolean = false,
    /** 3.16.1 新链接 / token 失效：需要先 OAuth 登录。 */
    val needsLogin: Boolean = false,
    val workspaces: List<WorkspaceItem> = emptyList(),
    val selectedWorkspaceKey: String? = null,
    val selectedTaskId: String? = null,
    val conversationEvents: List<ConversationEventItem> = emptyList(),
    /**
     * 当前 [selectedTaskId] 的内容是否已经可用。
     *
     * 语义：该任务的首个对话快照已应用（窗口为空也算），或本地缓存已经给出非空 rows。
     * 换任务 / 新建对话 / 重新打开工作区时置 `false`，界面先显示骨架而不是「没有可展示内容」
     * 的空态；别的会话的事件、切任务前发出的过期快照都不得把它置 `true`。
     */
    val rowsReady: Boolean = false,
    /** 对话行窗口（按 `rowId` 升序）。 */
    val rows: List<JsonObject> = emptyList(),
    val totalCount: Long = 0,
    val firstRowId: Long? = null,
    /** 当前对话日志的 epoch（`snapshot.logEpoch`），翻页 CAS 用。 */
    val logEpoch: String? = null,
    /** 正在向前翻历史（静默加载，不显示按钮）。 */
    val loadingOlder: Boolean = false,
    /** 更早的历史已经取完。 */
    val historyExhausted: Boolean = false,
    /** 核心认为还能继续往前翻（`canLoadOlder`）。 */
    val canLoadOlder: Boolean = false,
    val isRunning: Boolean = false,
    val pendingInteractions: JsonArray? = null,
    val queueItems: JsonArray? = null,
    /** 队列是否自动顺序发送（`queue.autoDrain`）。 */
    val queueAutoDrain: Boolean = true,
    /** 队列暂停原因：`stopped` / `manual` / `error`。 */
    val queuePauseReason: String? = null,
    val config: JsonObject? = null,
    /**
     * 草稿态（`selectedTaskId == null`）选中的协作模式。
     *
     * 新会话页还没有 config，模式选择只能先存在本地；首条消息建会话时作为
     * `createSession.config.mode` 下发，发送时也优先用它，避免 config 未回填而按 build 发。
     */
    val draftMode: String? = null,
    /** 最近一次失败的模式切换（错误条「重试」用它重发，而不是整页 refresh）。 */
    val failedMode: String? = null,
    /** 模型目录（`model-selection.getView`）：providers[].models[]。 */
    val modelView: JsonObject? = null,
    val modelViewLoading: Boolean = false,
    val modelViewError: String? = null,
    /** 当前工作区的 `workspaceId`（`createSession` 要用）。 */
    val workspaceId: String? = null,
    val conversationKey: String? = null,
    val sending: Boolean = false,
    /** 已选中、还没发出的附件。 */
    val attachments: List<ChatAttachment> = emptyList(),
    /** 正在上传附件（发送按钮转圈）。 */
    val uploading: Boolean = false,
    /** 当前正在上传的附件下标（按 [attachments] 顺序）。 */
    val uploadIndex: Int = 0,
    /** 当前附件的上传进度 0..1（`attachmentProgress` 事件）。 */
    val uploadProgress: Float = 0f,
    /**
     * 发送失败时要回填输入框的文本。
     *
     * 界面在点发送时会立刻清空输入框；如果 RPC 失败（桥没就绪 / 超时 / 被拒），
     * 这段文本就是唯一的副本，必须还给用户，不能静默丢消息。
     */
    val draftRestore: String? = null,
) {
    /** 错误条是否该显示：paired 之后旧的 relay 失败不该再挡在界面上。 */
    val relayFailureVisible: Boolean
        get() = relayState != RelayState.Paired && relayFailure != null

    /** 当前选中的工作区。 */
    val selectedWorkspace: WorkspaceItem?
        get() = workspaces.firstOrNull { it.key == selectedWorkspaceKey }

    /** 当前选中的任务（任务操作菜单要用它的摘要字段）。 */
    val selectedTask: TaskItem?
        get() = selectedWorkspace?.tasks?.firstOrNull { it.id == selectedTaskId }
}

/**
 * 对话页：工作区/任务列表、桥状态、对话事件流。
 *
 * ## 分工
 *
 * - **引擎面**（[EnginePort]）：生命周期、relay 状态、`bootstrap` / `open_bridge` /
 *   `poke_relay` 这些**门面级**命令；
 * - **对话面**（[ChatOps]）：`op` 透传（发消息等）。FFI §13 还没接线，所以生产实现在
 *   现在必定抛 [ChatOpsUnavailable]——界面会显示「对话层尚未接线」，**这是当前的真实状态**，
 *   不是本层的 bug（见 [ChatOps] 的文档）。
 * - **持久化面**（[DeviceRepository]）：只需要当前设备（标题与凭据可用性）。
 *
 * 所有失败都收敛成 [ChatUiState.error]，不往上抛。
 *
 * @param deviceId 连到哪台设备（由路由携带，见 `Route.Chat`）。
 * @param initialWorkspaceKey 路由带进来的落点工作区；`null` = 进页后由用户选。
 * @param initialTaskId 路由带进来的落点任务。
 */
class ChatViewModel(
    private val repository: DeviceRepository,
    private val engine: EnginePort,
    private val authStore: AuthStore,
    private val chatOps: ChatOps,
    private val deviceId: String,
    private val initialWorkspaceKey: String? = null,
    private val initialTaskId: String? = null,
    /** 路由带来的任务标题：sessions-index 到达前用它兜底页头标题。 */
    private val initialTaskTitle: String? = null,
    /** 路由带来的真实工作区信息（项目树工作区不在 bootstrap 里，必须靠它补全）。 */
    private val initialWorkspacePath: String? = null,
    private val initialWorkspaceIdentity: String? = null,
    private val initialHostId: String? = null,
    private val initialProjectId: String? = null,
    private val pairTimeoutMs: Long = PAIR_TIMEOUT_MS,
    /** 待交互状态变化时通知外部（`null` = 不通知，单测与无通知环境用）。 */
    private val onAttention: ((taskId: String, title: String, workspaceKey: String, active: Boolean) -> Unit)? = null,
    /** 行窗口的本地缓存（`null` = 不缓存）。 */
    private val cache: ChatCache? = null,
    /** 进程级对话保活（`null` = 走旧的 openBridge 路径，单测用）。 */
    private val registry: ConversationRegistry? = null,
) : ViewModel() {

    /**
     * 路由自带的工作区投影。
     *
     * bootstrap 只给「桌面当前 window 的 tab」；项目树里的其他项目只能由路由补出来，
     * 否则 `conversation_open` 会拿 key 当 cwd、任务操作也会因为 `selectedWorkspace == null`
     * 直接 no-op。任务只放一个骨架（id/hostId），真实标题等由 sessions-index 后续覆盖。
     */
    private val routeWorkspace: WorkspaceItem? = initialWorkspacePath?.let { path ->
        val key = initialWorkspaceKey
            ?: initialWorkspaceIdentity?.trim()?.takeIf { it.isNotEmpty() }
            ?: path
        WorkspaceItem(
            key = key,
            path = path,
            identity = initialWorkspaceIdentity,
            label = path.substringAfterLast('/').substringAfterLast('\\').ifEmpty { path },
            kind = if (initialHostId == null || initialHostId == "local") "local" else "remote",
            projectId = initialProjectId,
            hostId = initialHostId,
            // 路由带 hostId = 需要 prepare（非 tab 项目 / 非主根任务），不是已挂载 tab。
            bridgeable = initialHostId == null,
            tasks = initialTaskId?.let {
                listOf(
                    TaskItem(
                        id = it,
                        title = initialTaskTitle,
                        workspaceKey = key,
                        hostId = initialHostId,
                    ),
                )
            }.orEmpty(),
        )
    }

    private val _state = MutableStateFlow(
        ChatUiState(
            selectedWorkspaceKey = initialWorkspaceKey,
            selectedTaskId = initialTaskId,
            workspaces = listOfNotNull(routeWorkspace),
        ),
    )

    /** 界面唯一的状态入口。 */
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val observers = mutableListOf<Job>()

    private var entered = false

    /** 最后一次切到后台的时刻（`elapsedRealtime`）；null = 当前在前台。 */
    private var backgroundedAtMs: Long? = null

    private var eventSeq = 0L

    /** 缓存写入的防抖任务（流式帧很密，不逐帧落盘）。 */
    private var persistJob: Job? = null

    /**
     * 可见区是否已远离最老端（由时间线回报）。
     *
     * 窗口裁剪只允许在这个条件下发生：reverseLayout 里丢弃最老的行不改变其余单元的
     * index，但若用户正读到那一段，数据会从眼前消失。界面的判定是「可见最顶项距内容
     * 顶部 > 3 屏」，进入时/贴顶时恒 false（10 审计 §3）。
     */
    @Volatile
    private var windowTrimSafe = false

    /**
     * 时间线回报「现在裁剪不会动到可见内容」。
     *
     * 由 `ConversationTimeline` 的布局派生状态驱动；变 true 时立刻尝试一次裁剪，
     * 覆盖「用户从历史顶部滚回底部后，直到下一帧才触发裁剪」的空窗。
     */
    fun setWindowTrimSafe(safe: Boolean) {
        windowTrimSafe = safe
        if (safe) trimWindowIfNeeded()
    }

    /**
     * 行窗口上界：超过 [MAX_WINDOW_ROWS] 且可见区安全时裁到 [TRIM_TARGET_ROWS]。
     *
     * 内存事故（9286 行 / LOW_MEMORY）的根源是向前翻历史线性增长；200 行滞回避免
     * 每合并一页就裁一次。裁剪只从最老端丢弃，配合 [windowTrimSafe] 不动视口。
     */
    private fun trimWindowIfNeeded() {
        if (!windowTrimSafe) return
        if (_state.value.rows.size <= MAX_WINDOW_ROWS) return
        _state.update { current ->
            if (current.rows.size <= MAX_WINDOW_ROWS) {
                current
            } else {
                current.copy(rows = trimWindowRows(current.rows))
            }
        }
        val size = _state.value.rows.size
        ZLog.memory("chat.trim", size)
        ZLog.vm("chat", "window trimmed to=$size")
        // 游标前移后「还能往前翻」重新成立；下一次上翻会重新取被裁掉的段。
        recomputeCanLoadOlder()
    }

    /** 首屏「首个 turn 不完整」的自动补拉是否已尝试过。 */
    private var backfillTried = false

    /** 进页后等首个快照的看门狗（超时把「空内容」变成可重试的错误条）。 */
    private var rowsWatchdogJob: Job? = null

    /** 分页拉取互斥：静默预取与用户翻页共用，避免并发重复请求。 */
    private val olderMutex = Mutex()

    /** 已经发过 `subscribe` 的会话 id；重试发送时用于补订阅（订阅可能随失败一起丢了）。 */
    private var subscribedSessionId: String? = null

    /** 正在等 ack 的订阅：避免进页异步订阅与首条发送各发一次。 */
    private var subscribeInFlightSessionId: String? = null

    /** 上次订阅失败的会话；用户点重试时先重建引擎，清掉可能卡死的旧握手/订阅。 */
    private var lastSubscribeFailedSessionId: String? = null

    /**
     * 已经自动恢复过一次订阅的会话。
     *
     * 冷启动后首页预览先建好的桥，隔几十秒再订阅对话时对端可能已经不响应（ack 超时）；
     * 实测只有重建引擎 + 重开工作区能恢复。这里每个会话只自动恢复一次，避免无限重连；
     * 再失败就把「实时更新连接未建立」交给用户手动重试。
     */
    private var autoRecoveredSessionId: String? = null
    private var subscribeRecoveryJob: Job? = null

    /** `sessionsIndexUpdated` / `stateChanged` 触发的对话快照刷新（合并 + 节流）。 */
    private var conversationRefreshJob: Job? = null
    private var conversationRefreshQueued = false

    /**
     * 当前订阅的首帧信号：applyRowsUpdated 收到当前任务的首个快照/增量帧时完成。
     * [subscribeConversation] 用它把「ack + 首帧」并进同一个成功判据。
     */
    private var firstFrameSignal: CompletableDeferred<Unit>? = null

    init {
        observe()
    }

    /**
     * 进页：确保会话 → 等配对 → 取快照 → （若路由指定）打开工作区。
     *
     * 由界面在进入时调一次（`LaunchedEffect(Unit)`）；重复调用只生效一次。
     */
    fun onEnter() {
        if (entered) return
        entered = true
        viewModelScope.launch { enter() }
    }

    /**
     * 从登录覆盖层返回：登录成功就重走一次进页流程。
     *
     * `entered` 是「首次进页」的闸门，这里显式复位，否则登录后不会重试。
     */
    fun onLoginReturn() {
        viewModelScope.launch {
            val token = authStore.token()
            _state.update { it.copy(needsLogin = false) }
            if (token != null) {
                entered = false
                onEnter()
            }
        }
    }

    /** 用户关掉了登录页。 */
    fun cancelLogin() {
        _state.update { it.copy(needsLogin = false) }
    }

    /**
     * 手动刷新。
     *
     * 已经选中任务时**重新走一遍打开工作区**（重订阅 + 等新快照），而不是只刷新任务列表：
     * 内容区卡在骨架/空态时，用户点「重试」要的是对话本身重来。没有选中任务才只 bootstrap。
     */
    fun refresh() {
        viewModelScope.launch {
            if (!ensureReady()) return@launch
            val current = _state.value
            val taskId = current.selectedTaskId
            val workspaceKey = current.selectedWorkspaceKey
            if (taskId != null && workspaceKey != null) {
                if (lastSubscribeFailedSessionId == taskId) {
                    // 订阅 ack 超时通常意味着旧连接的握手/订阅已经卡死；同一个 bridge
                    // 上再发一次只会继续等。用户点「重试」时重建会话，让桥/对话重新握手。
                    ZLog.vm("chat", "refresh: subscribe previously failed, reconnecting session")
                    engine.reconnect()
                    lastSubscribeFailedSessionId = null
                }
                openWorkspaceInternal(workspaceKey, taskId)
            } else {
                refreshInternal()
            }
        }
    }

    /** 打开一个工作区的桥（`open_bridge` + `bridge_wait_healthy` + 回推移动端焦点）。 */
    fun openWorkspace(workspaceKey: String, taskId: String? = null) {
        viewModelScope.launch {
            if (!ensureReady()) return@launch
            openWorkspaceInternal(workspaceKey, taskId)
        }
    }

    /**
     * 切换当前任务（只改焦点，不重开桥）。
     *
     * 换成另一个任务时 [ChatUiState.rowsReady] 复位为 `false`：旧任务的首个快照结论
     * 不属于新任务，界面应先显示骨架而不是旧内容/空态。
     */
    fun selectTask(taskId: String?) {
        rowsWatchdogJob?.cancel()
        rowsWatchdogJob = null
        _state.update {
            it.copy(
                selectedTaskId = taskId,
                // 换任务后旧任务的首个快照结论不再适用：先回骨架，等缓存/快照。
                rowsReady = if (taskId == it.selectedTaskId) it.rowsReady else false,
            )
        }
    }

    /** 选中一个本地文件作为待发附件（读取由界面层完成）。 */
    fun attach(name: String, mime: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        if (bytes.size > MAX_ATTACHMENT_BYTES) {
            _state.update { it.copy(error = "附件太大（上限 ${MAX_ATTACHMENT_BYTES / 1024 / 1024} MB）") }
            return
        }
        if (_state.value.attachments.size >= MAX_ATTACHMENTS) {
            _state.update { it.copy(error = "一次最多 ${MAX_ATTACHMENTS} 个附件") }
            return
        }
        _state.update {
            it.copy(
                attachments = it.attachments + ChatAttachment(name, mime, bytes),
                error = null,
            )
        }
    }

    /** 移除一个待发附件。 */
    fun removeAttachment(index: Int) {
        _state.update { current ->
            if (index !in current.attachments.indices) current
            else current.copy(attachments = current.attachments.filterIndexed { i, _ -> i != index })
        }
    }

    /** 发一条用户消息（`op = "sendText"`）；没有选中任务时先 `createSession`。空白文本忽略。 */
    fun sendText(text: String) {
        val trimmed = text.trim()
        val pending = _state.value.attachments
        if (trimmed.isEmpty() && pending.isEmpty()) return
        if (_state.value.sending) {
            // 界面的输入框在点击时已经清空；这次点击被丢弃时必须把文本还回去，不能静默吞掉。
            _state.update { it.copy(draftRestore = trimmed.ifEmpty { it.draftRestore }) }
            return
        }
        val sessionId = _state.value.selectedTaskId
        if (sessionId == null) {
            createConversation(trimmed)
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(sending = true, error = null) }
            try {
                ensureRouteBridge(taskId = sessionId)
                // 首条消息可能因 subscribe 失败而“发出但收不到帧”；重试时补订阅。
                if (subscribedSessionId != sessionId) subscribeConversation(sessionId)
                val uploaded = uploadAttachments(sessionId)
                chatOps.call(
                    ChatOps.OP_SEND_TEXT,
                    buildJsonObject {
                        put("sessionId", sessionId)
                        put(ChatOps.ARG_TEXT, trimmed)
                        putSendSubmission()
                        if (uploaded.descriptors.isNotEmpty()) put("attachments", uploaded.descriptors)
                    },
                    conversationScope(),
                )
                // 只清掉真正发出去的附件；发送在途时新加的不能被误删。
                _state.update {
                    it.copy(attachments = it.attachments - uploaded.items.toSet())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 界面点发送时已清空输入框；失败必须把文本还给用户，否则消息静默消失。
                _state.update {
                    it.copy(
                        error = e.toUserMessage(),
                        draftRestore = trimmed.ifEmpty { it.draftRestore },
                    )
                }
            } finally {
                _state.update { it.copy(sending = false, uploading = false) }
            }
        }
    }

    /**
     * 按 `ref` 读回附件字节（用户气泡里的图片缩略图用）。
     *
     * 失败返回 `null`；错误不弹条（缩略图失败只显示占位）。
     */
    suspend fun readAttachmentBytes(ref: String): ByteArray? {
        val sessionId = _state.value.selectedTaskId ?: return null
        return try {
            val result = chatOps.call(
                "attachmentRead",
                buildJsonObject {
                    put("sessionId", sessionId)
                    put("ref", ref)
                },
                conversationScope(),
            )
            val base64 = (result as? JsonObject)?.get("base64").asString() ?: return null
            android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** 一次附件上传的结果：描述符 + 实际参与上传的附件快照。 */
    private data class UploadedAttachments(
        val descriptors: JsonArray,
        val items: List<ChatAttachment>,
    )

    /** 依次上传待发附件，返回描述符（失败即中止整条消息）。 */
    private suspend fun uploadAttachments(sessionId: String): UploadedAttachments {
        val pending = _state.value.attachments
        if (pending.isEmpty()) {
            return UploadedAttachments(JsonArray(emptyList()), emptyList())
        }
        _state.update { it.copy(uploading = true, uploadIndex = 0, uploadProgress = 0f) }
        val descriptors = mutableListOf<JsonElement>()
        for ((index, attachment) in pending.withIndex()) {
            _state.update { it.copy(uploadIndex = index, uploadProgress = 0f) }
            val descriptor = chatOps.call(
                "attachmentPut",
                buildJsonObject {
                    put("sessionId", sessionId)
                    put("fileName", attachment.name)
                    put("mime", attachment.mime)
                    put(
                        "dataBase64",
                        android.util.Base64.encodeToString(
                            attachment.bytes,
                            android.util.Base64.NO_WRAP,
                        ),
                    )
                },
                conversationScope(),
            )
            if (descriptor !is JsonObject) {
                // 没有描述符就不能发：静默跳过等于静默丢附件。
                throw EngineException(
                    NativeError("protocol", "附件上传没有返回描述符：${attachment.name}"),
                )
            }
            descriptors += descriptor
            _state.update { it.copy(uploadProgress = 1f) }
        }
        return UploadedAttachments(JsonArray(descriptors), pending)
    }

    /** 停止当前运行（`op = "stop"`）。 */
    fun stop() {
        val sessionId = _state.value.selectedTaskId ?: return
        viewModelScope.launch {
            try {
                chatOps.call(
                    "stop",
                    buildJsonObject { put("sessionId", sessionId) },
                    conversationScope(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toUserMessage()) }
            }
        }
    }

    /**
     * 「继续」：恢复队列自动顺序发送。
     *
     * 网页端只有这一种切换（`setAutoDrain({autoDrain:true})`），没有暂停开关。
     */
    fun resumeQueue() {
        val sessionId = _state.value.selectedTaskId ?: return
        viewModelScope.launch {
            try {
                chatOps.call(
                    "setAutoDrain",
                    buildJsonObject {
                        put("sessionId", sessionId)
                        put("autoDrain", true)
                    },
                    conversationScope(),
                )
                _state.update { it.copy(queueAutoDrain = true, queuePauseReason = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toUserMessage()) }
            }
        }
    }

    /**
     * 静默向前翻历史（`rowsRange`）。
     *
     * 触发由时间线在接近顶部时自动调用，不需要按钮。游标是**当前持有的最老一行**
     * （`rows.first().rowId`），不是快照投影头 `firstRowId`；并发与取完都由
     * [ChatUiState.loadingOlder] / [ChatUiState.historyExhausted] 拦住。
     */
    fun loadOlder() {
        val current = _state.value
        if (current.loadingOlder || current.historyExhausted) return
        if (!current.canLoadOlder && current.rows.isNotEmpty()) return
        val sessionId = current.selectedTaskId ?: return
        ZLog.vm(
            "chat",
            "loadOlder start rows=${current.rows.size} first=${current.rows.firstOrNull()?.rowId()} " +
                "head=${current.firstRowId} can=${current.canLoadOlder} exhausted=${current.historyExhausted}",
        )
        _state.update { it.copy(loadingOlder = true) }
        viewModelScope.launch {
            try {
                fetchOlderPage(sessionId, silent = false)
            } catch (e: CancellationException) {
                throw e
            } finally {
                _state.update { it.copy(loadingOlder = false) }
            }
        }
    }

    /**
     * 拉取并合并一页更早的历史。
     *
     * 与 [loadOlder] 共用 [olderMutex]，所以静默预取和用户翻页不会并发重复请求。
     * 双 CAS（网页端同款）：`atLogEpoch` 与当前 epoch 不符、或当前首行已经前移，
     * 整页丢弃。
     *
     * 「取完」只在明确 `hasMore == false`（或空页且没声明还有）时成立：网页端的
     * `loadOlder` 根本不看 `hasMore`，到没到头由 `window[0].rowId > firstRowId` 判断；
     * 把「不足一页」当取完会让部分页之后永远不再请求。
     *
     * @return `true` = 还有更早的行可以继续拉；`false` = 到头/失败/被取消/任务已切换。
     * @param silent `true` 时失败不写错误横幅（预取用），只记一条日志。
     */
    private suspend fun fetchOlderPage(sessionId: String, silent: Boolean): Boolean =
        olderMutex.withLock {
            val before = _state.value
            if (before.selectedTaskId != sessionId) return false
            val cursor = before.rows.firstOrNull()?.rowId() ?: before.firstRowId ?: return false
            val rangeStartedAt = System.nanoTime()
            ZLog.vm(
                "chat",
                "rowsRange begin before=$cursor limit=$OLDER_PAGE_SIZE rows=${before.rows.size} silent=$silent",
            )
            val result = try {
                chatOps.call(
                    "rowsRange",
                    buildJsonObject {
                        put("sessionId", sessionId)
                        put("beforeRowId", cursor)
                        put("limit", OLDER_PAGE_SIZE)
                    },
                    conversationScope(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (silent) {
                    android.util.Log.w(TAG, "prefetch rowsRange failed: ${e.message}")
                } else {
                    _state.update { it.copy(error = e.toUserMessage()) }
                }
                // 失败不写 historyExhausted：下一次（滚动触发的）loadOlder 还要能重试。
                return false
            }
            val obj = result as? JsonObject ?: return false
            val atEpoch = obj["atLogEpoch"].asString()
            val after = _state.value
            if (after.selectedTaskId != sessionId) return false
            val epochStale = atEpoch != null && after.logEpoch != null && atEpoch != after.logEpoch
            val oldest = after.rows.firstOrNull()?.rowId()
            val cursorStale = oldest != null && oldest != cursor
            val rawCount = (obj["rows"] as? JsonArray)?.size ?: 0
            if (epochStale || cursorStale) {
                ZLog.vm(
                    "chat",
                    "rowsRange discarded epochStale=$epochStale cursorStale=$cursorStale " +
                        "cursor=$cursor oldest=$oldest atEpoch=${atEpoch ?: "-"} " +
                        "localEpoch=${after.logEpoch ?: "-"} returned=$rawCount",
                )
                return false
            }
            // 与网页 `Bw` 同口径：只接收严格早于游标的行（服务端可能把游标行也带回来）。
            val older = (obj["rows"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                .orEmpty()
                .filter { it.rowId() < cursor }
            val hasMore = obj["hasMore"].asBool()
            if (older.isEmpty()) {
                if (isOlderExhausted(hasMore, returnedCount = 0)) {
                    _state.update { it.copy(historyExhausted = true) }
                    recomputeCanLoadOlder()
                }
                return false
            }
            val existing = _state.value.rows
            val ids = existing.mapTo(HashSet()) { it.rowId() }
            val merged = (older.filter { it.rowId() !in ids } + existing)
                .sortedBy { it.rowId() }
            val exhausted = isOlderExhausted(hasMore, older.size)
            _state.update {
                it.copy(
                    rows = merged,
                    // `firstRowId` 是会话首行（投影头），翻页不能覆盖它。
                    historyExhausted = it.historyExhausted || exhausted,
                )
            }
            trimWindowIfNeeded()
            recomputeCanLoadOlder()
            schedulePersist()
            // 上翻历史是 OOM/卡死的高发路径：把合并后的窗口大小和堆占用记进同一条时间线，
            // 下次闪退能从 crash.log 的 recent 里直接看出是不是内存问题。
            ZLog.memory("chat.loadOlder", merged.size)
            ZLog.vm(
                "chat",
                "rowsRange ok before=$cursor returned=$rawCount older=${older.size} hasMore=$hasMore " +
                    "exhausted=$exhausted merged=${merged.size} ms=${elapsedMsLog(rangeStartedAt)}",
            )
            !exhausted
        }

    /**
     * 新对话：先 `createSession` 拿到 sessionId，再 `sendText` 发首条消息。
     *
     * 3.17.1 的正常路径是「进草稿页预热 `createSession`，点发送只发 `sendText`」；
     * 首条消息**不**随 `createSession` 提交（`firstInput` 只用于崩溃恢复重放）。
     * 这样即使发送失败，会话本身也已稳定——重试只重发消息，不会重复建会话。
     */
    private fun createConversation(firstText: String) {
        // `workspaceId` 与网页的 workspaceKey 同规则：`workspaceIdentity?.trim() || workspacePath`。
        // 远端工作区必须用 identity，不能退化成裸路径。
        val workspaceId = _state.value.selectedWorkspace?.key
            ?: _state.value.selectedWorkspaceKey
            ?: _state.value.workspaceId
        if (workspaceId == null) {
            _state.update {
                it.copy(
                    error = "工作区还没就绪，请稍后再试",
                    draftRestore = firstText.ifEmpty { null },
                )
            }
            return
        }
        val selected = _state.value.selectedWorkspace
        if (selected != null &&
            !selected.bridgeable &&
            selected.projectId == null
        ) {
            // legacy 项目没有 projectId，桌面端无法 prepare；提前给可行动文案，别发必然失败的 RPC。
            _state.update {
                it.copy(
                    error = "这个项目还没有项目记录，无法新建对话：请先在电脑端打开一次该项目",
                    draftRestore = firstText.ifEmpty { null },
                )
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(sending = true, error = null, draftRestore = null) }
            try {
                // 先把桥/对话备好：用户可能在进页动画结束前就点了发送。
                val handle = ensureRouteBridge(taskId = null)
                // createSession 的 workspaceId 用 bridge-ready 的权威 key（identity 归一化后
                // 可能与路由携带的 key 不同；用旧值会建到错误工作区）。
                val targetWorkspaceId = handle?.effectiveWorkspaceKey ?: workspaceId
                val result = chatOps.call(
                    "createSession",
                    buildJsonObject {
                        put("workspaceId", targetWorkspaceId)
                        // 核心默认 90s；宿主只按 args.timeoutSecs 兜底，不传会在 35s 假超时。
                        put("timeoutSecs", 90)
                        // 草稿页选的模式随建会话下发（web 的 draft config）；否则首条消息只能按 build 发。
                        _state.value.draftMode?.let { draft ->
                            put("config", buildJsonObject { put("mode", draft) })
                        }
                    },
                    conversationScope(),
                )
                val newId = (result as? JsonPrimitive)?.contentOrNull
                if (newId.isNullOrBlank()) {
                    _state.update {
                        it.copy(
                            error = "服务端没有返回新会话 id",
                            draftRestore = firstText.ifEmpty { null },
                        )
                    }
                    return@launch
                }
                rowsWatchdogJob?.cancel()
                rowsWatchdogJob = null
                _state.update {
                    it.copy(
                        selectedTaskId = newId,
                        rows = emptyList(),
                        rowsReady = false,
                        totalCount = 0,
                        firstRowId = null,
                        logEpoch = null,
                        historyExhausted = false,
                        canLoadOlder = false,
                    )
                }
                // 先订阅再发消息：对端的增量帧只推给已订阅的会话。
                subscribeConversation(newId)
                loadModelView()
                // 拿到新会话的 config（模型/模式）后再发首条，sendText 才能带上正确提交字段。
                refreshConversationState()

                val hasAttachments = _state.value.attachments.isNotEmpty()
                if (firstText.isNotEmpty() || hasAttachments) {
                    val uploaded = uploadAttachments(newId)
                    chatOps.call(
                        ChatOps.OP_SEND_TEXT,
                        buildJsonObject {
                            put("sessionId", newId)
                            put(ChatOps.ARG_TEXT, firstText)
                            putSendSubmission()
                            if (uploaded.descriptors.isNotEmpty()) {
                                put("attachments", uploaded.descriptors)
                            }
                        },
                        conversationScope(),
                    )
                    // 只清掉本次发出去的附件（发送在途时新加的保留）。
                    _state.update {
                        it.copy(attachments = it.attachments - uploaded.items.toSet())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 发送失败时选择已经拿到的新会话保留下来；文本回填，重试走普通 sendText。
                _state.update {
                    it.copy(
                        error = e.toUserMessage(),
                        draftRestore = firstText.ifEmpty { it.draftRestore },
                    )
                }
            } finally {
                _state.update { it.copy(sending = false, uploading = false) }
            }
        }
    }

    /** 界面把 [ChatUiState.draftRestore] 填回输入框后调用，避免重复回填。 */
    fun consumeDraftRestore() {
        _state.update { it.copy(draftRestore = null) }
    }

    /**
     * 订阅一个会话的对话帧（`op = "subscribe"`）。
     *
     * **「订阅成功」的完整定义是 ack + 首帧都到手**：`mode = first` 的订阅在 ack 后会
     * 立刻推来一份快照帧；实测存在 ack 正常、快照却永远不来的静默失效（等了 5 分钟
     * 零帧）。把首帧并进同一个成功判据后，这种失效天然走与 ack 超时完全相同的
     * 重试 / 自动恢复路径，不需要单独的看门狗。
     *
     * 失败只记日志并保持未订阅，后续（重进页 / 再次发送 / [refresh]）可以重试。
     * `force = true` 允许对同一会话重复订阅（重进页时对端会重新推首帧快照）。
     */
    private suspend fun subscribeConversation(sessionId: String, force: Boolean = false) {
        if (!force && (subscribedSessionId == sessionId || subscribeInFlightSessionId == sessionId)) return
        // 桥可能还在建（新建对话与进页并发）：直接向注册表要同一条保活句柄，
        // 不要依赖 `_state.bridgeSession` 是否已经回填——否则会静默不订阅、消息永远不出现。
        val handle = ensureRouteBridge(taskId = null)
        val bridge = handle?.bridge ?: _state.value.bridgeSession
        val key = handle?.key ?: _state.value.conversationKey
        if (bridge == null || key == null) {
            ZLog.vm("chat", "subscribe skipped: bridge/conversation 尚未就绪（session=$sessionId）")
            return
        }
        subscribeInFlightSessionId = sessionId
        val startedAt = System.nanoTime()
        // 进页/恢复（force）：第一次失败会走自动恢复（重连 + 重开工作区），所以只试一次；
        // 已经自动恢复过仍失败时再给第二次机会，最后才把错误交给用户手动重试。
        val canAutoRecover = force && autoRecoveredSessionId != sessionId
        val attempts = if (force && !canAutoRecover) 2 else 1
        try {
            var attempt = 0
            while (attempt < attempts) {
                attempt += 1
                // 首帧信号由 applyRowsUpdated 在当前任务的首个快照/增量帧到达时完成；
                // 订阅换手（切任务/重订阅）时整体替换，旧信号不会被新订阅误完成。
                val firstFrame = CompletableDeferred<Unit>()
                firstFrameSignal = firstFrame
                try {
                    ZLog.vm(
                        "chat",
                        "subscribe begin session=$sessionId bridge=$bridge attempt=$attempt/$attempts",
                    )
                    // 两段独立预算：ack 6s + 首帧 8s。帧往往在 ack 前后就位，这里只是收口。
                    val acked = withTimeoutOrNull(SUBSCRIBE_ACK_TIMEOUT_MS) {
                        chatOps.call(
                            ChatOps.OP_SUBSCRIBE,
                            buildJsonObject { put("sessionId", sessionId) },
                            conversationScope(),
                        )
                        true
                    }
                    if (acked != true) {
                        throw EngineException(
                            NativeError(
                                "timeout",
                                "subscribe ack timeout ${SUBSCRIBE_ACK_TIMEOUT_MS}ms",
                            ),
                        )
                    }
                    // 首帧判据只用于进页/恢复（force）：那条路径必须拿到内容才算订上；
                    // 发送前的补订阅（非 force）只等 ack，不能把发消息拖 8 秒。
                    if (force) {
                        withTimeoutOrNull(SUBSCRIBE_FIRST_FRAME_TIMEOUT_MS) {
                            firstFrame.await()
                        } ?: throw EngineException(
                            NativeError(
                                "timeout",
                                "subscribe first frame timeout ${SUBSCRIBE_FIRST_FRAME_TIMEOUT_MS}ms",
                            ),
                        )
                    }
                    subscribedSessionId = sessionId
                    lastSubscribeFailedSessionId = null
                    // 订阅恢复成功后清掉自动恢复标记：以后（如长时间后台回来）还能再自愈一次。
                    autoRecoveredSessionId = null
                    _state.update {
                        if (it.error == SUBSCRIBE_FAILED_MESSAGE) it.copy(error = null) else it
                    }
                    ZLog.vm("chat", "subscribe ready session=$sessionId ms=${elapsedMsLog(startedAt)}")
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 不抛：订阅失败不该让发送整条消息失败；保持 subscribedSessionId 未写入以便重试。
                    ZLog.vm(
                        "chat",
                        "subscribe failed session=$sessionId attempt=$attempt/$attempts " +
                            "ms=${elapsedMsLog(startedAt)}",
                        e,
                    )
                    if (attempt < attempts) delay(SUBSCRIBE_RETRY_DELAY_MS)
                } finally {
                    if (firstFrameSignal === firstFrame) firstFrameSignal = null
                }
            }
            // 有本地缓存时 rowsReady 已经是 true，看门狗不会报错；这里必须显式告诉用户
            // 「内容在，但实时更新没接上」，否则页面会像这次一样静默冻结。
            if (force) {
                lastSubscribeFailedSessionId = sessionId
                if (autoRecoveredSessionId != sessionId) {
                    // 第一次失败先自动恢复（重建引擎 + 重开工作区），不把超时直接甩给用户。
                    autoRecoveredSessionId = sessionId
                    ZLog.vm("chat", "subscribe failed; auto recover session=$sessionId")
                    recoverSubscription(sessionId)
                } else {
                    _state.update {
                        if (it.error == null || it.error == ROWS_TIMEOUT_MESSAGE) {
                            it.copy(error = SUBSCRIBE_FAILED_MESSAGE)
                        } else {
                            it
                        }
                    }
                }
            }
        } finally {
            if (subscribeInFlightSessionId == sessionId) subscribeInFlightSessionId = null
        }
    }

    /**
     * 进页看门狗：约 [ROWS_READY_TIMEOUT_MS] 后当前任务仍没有内容就写一条可重试的错误。
     *
     * 不把 [ChatUiState.rowsReady] 置 true：界面继续显示骨架而不是「没有可展示内容」的空态。
     */
    private fun startRowsWatchdog(sessionId: String) {
        rowsWatchdogJob?.cancel()
        rowsWatchdogJob = viewModelScope.launch {
            delay(ROWS_READY_TIMEOUT_MS)
            _state.update { current ->
                if (current.selectedTaskId == sessionId && !current.rowsReady && current.error == null) {
                    current.copy(error = ROWS_TIMEOUT_MESSAGE)
                } else {
                    current
                }
            }
        }
    }

    /**
     * 确保路由指向的工作区桥已建立（含项目导航意图），返回注册表句柄。
     *
     * 所有建桥路径都收在这里：`ChatOps` 只取现成句柄，不自己开桥，避免同工作区出现
     * 第二条 transport（`helloConversationV4` 是单连接语义，第二条永远等不到应答）。
     */
    private suspend fun ensureRouteBridge(taskId: String?): ConversationRegistry.Handle? {
        val workspaceKey = _state.value.selectedWorkspaceKey ?: return null
        val handle = registry?.ensure(
            workspaceKey = workspaceKey,
            taskId = taskId,
            conversationScope = conversationScope(),
            taskHostId = if (taskId != null) initialHostId else null,
            project = if (taskId == null) projectIntent() else null,
            // 聊天页只等桥 + conversation_open；sessions-index 只发不等（首页才需要它）。
            awaitSessionsIndex = false,
        )
        if (handle != null) {
            // 先登记身份再订阅/发命令：事件门禁按 (session,key) 过滤，晚写会丢掉首帧。
            _state.update { it.copy(bridgeSession = handle.bridge, conversationKey = handle.key) }
        }
        return handle
    }

    /** 在项目里新建对话的开桥意图；`null` = 普通工作区（不触发 prepareProjectWorkspace）。 */
    private fun projectIntent(): JsonObject? {
        val projectId = initialProjectId ?: return null
        return buildJsonObject {
            put("projectId", projectId)
            initialHostId?.let { put("hostId", it) }
        }
    }

    /**
     * `sendText` 的提交字段（3.17.1 web 强制带 `mode/planEnabled/modelSelection`）。
     *
     * 取值来自对话快照的 `config`（`provider/model/thought/mode`）；新会话还没有 config
     * 时退回工作区默认（`build` + 不带 modelSelection），至少不会把 plan 模式丢掉。
     */
    private fun JsonObjectBuilder.putSendSubmission() {
        val config = _state.value.config
        // 新会话首条消息时 config 可能还没回填：草稿页选过的模式优先，避免按 build 误发。
        val rawMode = (config?.get("mode") as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: _state.value.draftMode
        val plan = rawMode == "plan" ||
            (config?.get("planEnabled") as? JsonPrimitive)?.booleanOrNull == true
        // web 把 plan 归一化成 build + planEnabled；`default`/未知值一律按 build 发，
        // 避免服务端因为不认识的 mode 拒绝整条消息。
        val mode = when (rawMode) {
            "edit", "yolo", "guarded" -> rawMode
            else -> "build"
        }
        put("mode", mode)
        put("planEnabled", plan)
        val provider = (config?.get("provider") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val model = (config?.get("model") as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (!provider.isNullOrBlank() && !model.isNullOrBlank()) {
            put(
                "modelSelection",
                buildJsonObject {
                    put("providerId", provider)
                    put("modelId", model)
                    val thought = (config.get("thought") as? JsonPrimitive)
                        ?.takeIf { it.isString }?.content
                    if (!thought.isNullOrBlank()) {
                        put("options", buildJsonObject { put("reasoningLevel", thought) })
                    }
                },
            )
        }
    }

    /**
     * `conversation_open` 的 scope。
     *
     * 必须是**真实路径 + identity 两个字段**：对端把 `workspacePath` 当成 agent CLI 的
     * spawn cwd，把 `workspaceIdentity` 当成工作区标识。把键塞进 `workspacePath`
     * 在远程工作区上会让 cwd 变成一个不存在的路径。
     */
    private fun conversationScope(): JsonObject {
        val workspace = _state.value.selectedWorkspace
        if (workspace == null) {
            // 项目树补出来的工作区不在 bootstrap 的 workspaces 里；路由带的是真实路径，
            // 直接用 key 兜底，避免把空 scope 交给 conversation_key。
            val path = initialWorkspacePath
                ?: _state.value.selectedWorkspaceKey
                ?: return JsonObject(emptyMap())
            return buildJsonObject {
                put("workspacePath", path)
                (initialWorkspaceIdentity
                    ?: _state.value.selectedWorkspaceKey?.takeIf { it != path })
                    ?.let { put("workspaceIdentity", it) }
            }
        }
        return buildJsonObject {
            put("workspacePath", workspace.path)
            workspace.identity?.let { put("workspaceIdentity", it) }
        }
    }

    /**
     * 通用 `op` 透传（界面层后续接具体操作时用）。
     *
     * @return 成功时的返回值；失败返回 `null`（原因已进 [ChatUiState.error]）。
     */
    suspend fun callOp(op: String, args: JsonObject): JsonElement? = try {
        chatOps.call(op, args, conversationScope())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        _state.update { it.copy(error = e.toUserMessage()) }
        null
    }

    /** 回前台探活（`poke_relay`）；后台超过阈值则直接重建会话。 */
    fun onForeground() {
        val backgroundedAt = backgroundedAtMs
        backgroundedAtMs = null
        viewModelScope.launch {
            val awayMs = backgroundedAt?.let { SystemClock.elapsedRealtime() - it } ?: 0L
            if (awayMs >= BACKGROUND_RECONNECT_MS && _state.value.selectedWorkspaceKey != null) {
                recoverAfterBackground(awayMs)
                return@launch
            }
            try {
                engine.pokeRelay()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toUserMessage()) }
            }
        }
    }

    /** Activity 进入后台（`ON_STOP`）：记录离开时刻，回前台时据此决定是否重建连接。 */
    fun onBackground() {
        backgroundedAtMs = SystemClock.elapsedRealtime()
        // 后台可能被系统回收：把还在防抖窗口里的最后一帧立刻落盘（10 审计 P1-11）。
        persistNow()
    }

    override fun onCleared() {
        // viewModelScope 在 onCleared 时已取消，防抖写盘会一起被取消；用一次性守护线程
        // 把当前窗口写完，避免「看过的内容下次进页又没了」。
        val cache = cache
        val taskId = _state.value.selectedTaskId
        val workspaceKey = _state.value.selectedWorkspaceKey
        val rows = _state.value.rows
        if (cache != null && taskId != null && workspaceKey != null && rows.isNotEmpty()) {
            val logEpoch = _state.value.logEpoch
            val firstRowId = _state.value.firstRowId
            Thread {
                cache.saveConversation(workspaceKey, taskId, rows, logEpoch, firstRowId)
            }.apply { isDaemon = true }.start()
        }
        super.onCleared()
    }

    /**
     * 后台恢复：连接还在就只补一次订阅快照；只有真的断了才重建。
     *
     * 正常情况下 [RelayKeepAlive] 会在后台撑住 CPU / 网络，socket 不应掉线；
     * 如果厂商仍然掐断了连接（relay 不是 Paired 或桥已降级），旧连接上的握手可能
     * 已经卡死，这时重建会话才能清掉旧桥、旧订阅，拿到新连接上的首帧快照。
     */
    private suspend fun recoverAfterBackground(awayMs: Long) {
        val workspaceKey = _state.value.selectedWorkspaceKey ?: return
        val taskId = _state.value.selectedTaskId
        val relay = engine.relayState.value
        val degraded = _state.value.bridgeDegraded
        ZLog.vm(
            "chat",
            "foreground resume awayMs=$awayMs relay=$relay degraded=${degraded ?: "-"} " +
                "ws=${workspaceKey.take(120)} task=${taskId ?: "-"}",
        )
        try {
            if (relay == RelayState.Paired && degraded == null) {
                engine.pokeRelay()
                if (taskId != null) {
                    // 订阅没断时这一步是幂等的；若后台漏了帧，服务端会随订阅重推首帧。
                    subscribeConversation(taskId, force = true)
                }
                refreshConversationState()
                return
            }
            engine.reconnect()
            // bootstrap 只补工作区/任务元数据，与开桥/订阅互不依赖；并行跑避免串行等待。
            viewModelScope.launch { refreshInternal() }
            openWorkspaceInternal(workspaceKey, taskId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.toUserMessage()) }
        }
    }

    /** 清空对话事件流（调试/排查用）。 */
    fun clearConversationEvents() {
        _state.update { it.copy(conversationEvents = emptyList()) }
    }

    /** 清掉错误条。 */
    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private suspend fun enter() {
        val device = resolveDevice()
        if (device == null) {
            _state.update { it.copy(error = "设备不存在（可能已被删除）") }
            return
        }
        val url = device.remoteUrl
        if (url == null) {
            _state.update {
                it.copy(credentialsMissing = true, error = "「${device.name}」的凭据不可用，需要重新配对")
            }
            return
        }

        val token = authStore.token()
        if (token == null && !PairingUrl.hasPassHash(url)) {
            _state.update { it.copy(needsLogin = true, error = "需要登录后继续远控") }
            return
        }
        // 幂等：已经连着同一个 URL 时不会重连（引擎实例 = 一条连接，见 EnginePort 的文档）。
        try {
            engine.ensureSession(url, token)
        } catch (e: RemoteTokenException) {
            if (e.authRequired) {
                authStore.clear()
                _state.update { it.copy(needsLogin = true, error = "登录已失效，请重新登录") }
            } else {
                _state.update { it.copy(error = e.toUserMessage()) }
            }
            return
        }
        // 路由已经带真实 workspacePath/identity：先开工作区/订阅首帧，bootstrap 并行跑。
        // 以前这里是 refreshInternal() 完再 open，页面必须先等一整轮 bootstrap 才有内容，
        // 首屏时间被拉长；两者互不依赖（open 只用路由信息，header 由 bootstrap 补齐）。
        val workspaceKey = initialWorkspaceKey
        if (workspaceKey != null) {
            ZLog.vm(
                "chat",
                "enter fast-path ws=${workspaceKey.take(120)} task=${(initialTaskId ?: "-").take(120)}",
            )
            viewModelScope.launch { refreshInternal() }
            openWorkspaceInternal(workspaceKey, initialTaskId)
        } else {
            refreshInternal()
        }
    }

    /**
     * 按 [deviceId] 取设备。
     *
     * 用「列表里找」而不是「读当前设备」：当前设备是异步写的，进页时可能还没落盘
     * （见 `Route.Chat` 的文档）。
     */
    private suspend fun resolveDevice(): DeviceRecord? =
        repository.devices.first().firstOrNull { it.id == deviceId }

    /** 会话是否可用；不可用时把原因写进状态并返回 `false`。 */
    private suspend fun ensureReady(): Boolean {
        if (engine.connectedUrl.value != null) {
            // `error` / `kicked` / `closed` 是终态：继续用同一个引擎等下去只会超时。
            // 拆掉重建（会重新换 terminal token），让刷新/重试真的重连。
            val relay = engine.relayState.value
            if (relay != RelayState.Error &&
                relay != RelayState.Kicked &&
                relay != RelayState.Closed
            ) {
                return true
            }
            engine.disconnect()
        }
        val device = try {
            resolveDevice()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.toUserMessage()) }
            return false
        }
        val url = device?.remoteUrl
        if (url == null) {
            _state.update { it.copy(error = "没有可用的设备连接：这台设备不存在或凭据不可用") }
            return false
        }
        val token = authStore.token()
        if (token == null && !PairingUrl.hasPassHash(url)) {
            _state.update { it.copy(needsLogin = true, error = "需要登录后继续远控") }
            return false
        }
        try {
            engine.ensureSession(url, token)
        } catch (e: RemoteTokenException) {
            if (e.authRequired) {
                authStore.clear()
                _state.update { it.copy(needsLogin = true, error = "登录已失效，请重新登录") }
            } else {
                _state.update { it.copy(error = e.toUserMessage()) }
            }
            return false
        }
        return true
    }

    private suspend fun refreshInternal() {
        _state.update { it.copy(loading = true, error = null) }
        try {
            engine.waitPaired(pairTimeoutMs)
            val snapshot = engine.bootstrap()
            val projected = WorkspaceProjection.from(snapshot)
            // 路由带来的项目树工作区不在 bootstrap 里；补回去，否则 selectedWorkspace 变 null。
            // 注意：bootstrap 对已挂载 tab 返回的 workspace **tasks 为空**，直接取 projected
            // 会把 Home 路由带过来的任务/标题冲掉（页头变「未命名」、任务操作 no-op）。
            val route = routeWorkspace
            _state.update {
                it.copy(workspaces = mergeRouteWorkspace(projected, route))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.toUserMessage()) }
        } finally {
            _state.update { it.copy(loading = false) }
        }
    }

    private suspend fun openWorkspaceInternal(workspaceKey: String, taskId: String?) {
        val openStartedAt = System.nanoTime()
        rowsWatchdogJob?.cancel()
        rowsWatchdogJob = null
        _state.update {
            val switchingTask = taskId != null && taskId != it.selectedTaskId
            it.copy(
                loading = true,
                error = null,
                selectedWorkspaceKey = workspaceKey,
                // 新建会话可能已经把 selectedTaskId 设成服务端返回的 id；进页引导的
                // null 不能把它冲掉（否则重试会重复建会话）。
                selectedTaskId = taskId ?: it.selectedTaskId,
                // 换任务时旧行不属于当前任务（否则快照合并会把它们当成更早历史留下），
                // 先清掉并撤掉「内容已就绪」，等缓存/首个快照。
                // 同一任务重开（refresh）保留现有内容，不闪骨架。
                rows = if (switchingTask) emptyList() else it.rows,
                rowsReady = if (switchingTask) false else it.rowsReady,
                totalCount = if (switchingTask) 0 else it.totalCount,
                firstRowId = if (switchingTask) null else it.firstRowId,
                // 同一任务刷新时保留 epoch：快照里 epoch 变了就说明日志被重建，
                // 上层才能整窗替换而不是把旧前缀拼出洞（10 审计 P1-6）。
                logEpoch = if (switchingTask) null else it.logEpoch,
                historyExhausted = false,
                canLoadOlder = false,
                modelView = null,
                modelViewLoading = false,
                modelViewError = null,
            )
        }
        backfillTried = false
        try {
            // 有 registry 时桥与 conversation 由它保活复用；否则走旧的一次性路径。
            // 项目树工作区（不在桌面 tab 里）必须带 taskHostId/project，让桌面先
            // `prepareProjectWorkspace`；两者不能同时给（桌面端会拒绝）。
            //
            // 建桥与读缓存互不依赖：并行跑，缓存先到就先铺内容（10 审计 P2-12）。
            val bridgeStartedAt = System.nanoTime()
            val bridgeDeferred = viewModelScope.async { ensureRouteBridge(taskId) }

            // 先铺本地缓存：打开过的对话立刻有内容，等快照到达再合并。
            // 缓存是整份历史 JSON，解析可能几十毫秒到几百毫秒，不能占主线程首帧。
            if (taskId != null && cache != null) {
                val cacheStartedAt = System.nanoTime()
                val cached = withContext(Dispatchers.IO) {
                    cache.loadConversation(workspaceKey, taskId)
                }
                if (cached != null && cached.rows.isNotEmpty()) {
                    _state.update {
                        it.copy(
                            rows = cached.rows,
                            // 本地缓存就是当前任务的内容：先撤骨架，快照到了再合并。
                            rowsReady = true,
                            totalCount = cached.rows.size.toLong(),
                            firstRowId = cached.firstRowId,
                            logEpoch = cached.logEpoch,
                        )
                    }
                    recomputeCanLoadOlder()
                    ZLog.vm(
                        "chat",
                        "cache loaded rows=${cached.rows.size} first=${cached.firstRowId} " +
                            "ms=${elapsedMsLog(cacheStartedAt)}",
                    )
                } else {
                    ZLog.vm("chat", "cache miss ms=${elapsedMsLog(cacheStartedAt)}")
                }
            }

            val handle = bridgeDeferred.await()
            ZLog.vm(
                "chat",
                "route bridge ready=${handle != null} ms=${elapsedMsLog(bridgeStartedAt)}",
            )
            val session: Long
            val key: String
            if (handle != null) {
                session = handle.bridge
                key = handle.key
                // 用 bridge-ready 的权威 key：项目草稿/远端 identity 归一化后可能与路由 key 不同，
                // 推错 key 会让桌面的移动端焦点指向另一个工作区。
                engine.sendMobileViewState(handle.effectiveWorkspaceKey, taskId)
            } else {
                session = engine.openBridge(
                    workspaceKey = workspaceKey,
                    taskId = taskId,
                    taskHostId = if (taskId != null) initialHostId else null,
                    project = if (taskId == null) projectIntent() else null,
                )
                engine.waitHealthy(session)
                engine.sendMobileViewState(workspaceKey, taskId)

                // 打开对话层并订阅：sessions-index 用于拿 workspaceId 与任务预览。
                key = engine.conversationOpen(session, conversationScope())
                engine.conversationCallNoWait(
                    session,
                    key,
                    "subscribeSessionsIndex",
                    buildJsonObject { put("timeoutSecs", 60) },
                )
            }
            // 先登记身份再订阅：事件门禁按 (session,key) 过滤。
            _state.update { it.copy(bridgeSession = session, conversationKey = key) }
            if (taskId != null) {
                // 进页就起表：订阅 ack 慢、首帧丢失都要在窗口内变成可重试的错误，而不是
                // 一直停在骨架/空态。force = true：重进页（含 refresh）允许重复订阅。
                startRowsWatchdog(taskId)
                // 关键：订阅不要在进页路径上 await。内容由 snapshot 事件驱动，ack 只影响
                // 日志/重试；await 会把「等会话内容」变成「等订阅握手」，慢一个 RTT。
                viewModelScope.launch { subscribeConversation(taskId, force = true) }
                loadModelView()
            }
            // 只刷新「不属于行」的状态（运行中/队列/config），不阻塞上面的订阅。
            refreshConversationState()
            ZLog.vm(
                "chat",
                "workspace open done ws=${workspaceKey.take(120)} task=${(taskId ?: "-").take(120)} " +
                    "totalMs=${elapsedMsLog(openStartedAt)}",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.toUserMessage()) }
        } finally {
            _state.update { it.copy(loading = false) }
        }
    }

    /** 置顶 / 取消置顶（`zcode-task.setTaskPinned`）。 */
    fun setPinned(pinned: Boolean) {
        val id = _state.value.selectedTaskId ?: return
        mutateTask(
            "setTaskPinned",
            { put("pinned", pinned) },
            updateTasks = { tasks ->
                tasks.map { if (it.id == id) it.copy(pinned = pinned) else it }
            },
        )
    }

    /** 归档（`zcode-task.archiveTask`）。 */
    fun archiveTask() {
        val id = _state.value.selectedTaskId ?: return
        mutateTask(
            "archiveTask",
            {},
            updateTasks = { tasks ->
                tasks.map { if (it.id == id) it.copy(archived = true) else it }
            },
        )
    }

    /** 删除（`zcode-task.deleteTask`），同时清掉本地缓存。 */
    fun deleteTask() {
        val workspace = _state.value.selectedWorkspace
        val id = _state.value.selectedTaskId
        if (workspace != null && id != null) cache?.remove(workspace.key, id)
        rowsWatchdogJob?.cancel()
        rowsWatchdogJob = null
        // 不要 close 注册表：那是**工作区级**保活，Home 的 sessions-index 预览也在用；
        // 删一个任务就关掉整条对话会让该工作区失去实时更新。
        if (id != null) {
            mutateTask(
                "deleteTask",
                {},
                updateTasks = { tasks -> tasks.filterNot { it.id == id } },
            )
            // 清掉选中态与时间线：否则 header 回到「新对话」但旧消息还在，
            // 菜单也会对着一个已经不存在的任务发 no-op 请求。
            _state.update {
                it.copy(
                    selectedTaskId = null,
                    rows = emptyList(),
                    rowsReady = false,
                    totalCount = 0,
                    firstRowId = null,
                    logEpoch = null,
                    historyExhausted = false,
                    canLoadOlder = false,
                )
            }
            subscribedSessionId = null
        } else {
            mutateTask("deleteTask", {})
        }
    }

    /** 重命名（`zcode-task.renameTask`，远程桥回落到基础实现）。 */
    fun renameTask(title: String) {
        val id = _state.value.selectedTaskId ?: return
        mutateTask(
            "renameTask",
            { put("title", title) },
            updateTasks = { tasks ->
                tasks.map { if (it.id == id) it.copy(title = title) else it }
            },
        )
    }

    /**
     * 处理一条待审批 / 待输入。
     *
     * 四个可选字段与协议一致（`docs/chat/08` §2.5）：权限选项带 `optionId +
     * action=accept + content`；表单带 `action=accept + content.answers`；
     * 自由文本只带 `freeText`。至少给一个，否则服务端无法判断意图。
     */
    fun resolveInteraction(
        interactionId: String,
        optionId: String?,
        freeText: String?,
        action: String?,
        content: JsonObject?,
    ) {
        val sessionId = _state.value.selectedTaskId ?: return
        viewModelScope.launch {
            try {
                chatOps.call(
                    "resolveInteraction",
                    buildJsonObject {
                        put("sessionId", sessionId)
                        put("interactionId", interactionId)
                        optionId?.let { put("optionId", it) }
                        freeText?.takeIf { it.isNotBlank() }?.let { put("freeText", it) }
                        action?.let { put("action", it) }
                        content?.let { put("content", it) }
                    },
                    conversationScope(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toUserMessage()) }
            }
        }
    }

    /**
     * 任务级 mutation：参数是任务摘要对象本身（`zcode-task` 代理据此解析 address）。
     *
     * @param updateTasks 本地乐观更新；失败回滚到调用前快照（服务端不会推 workspaces 列表，
     *   不这样做按钮会「转圈结束但列表不动」）。
     */
    private fun mutateTask(
        method: String,
        extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
        updateTasks: (List<TaskItem>) -> List<TaskItem> = { it },
    ) {
        val workspace = _state.value.selectedWorkspace ?: return
        val task = _state.value.selectedTask ?: return
        val workspaceKey = _state.value.selectedWorkspaceKey ?: return
        val before = workspace.tasks
        _state.update { current ->
            current.copy(
                workspaces = current.workspaces.map {
                    if (it.key == workspaceKey) it.copy(tasks = updateTasks(it.tasks)) else it
                },
            )
        }
        viewModelScope.launch {
            try {
                val handle = ensureRouteBridge(task.id)
                val bridge = handle?.bridge
                    ?: _state.value.bridgeSession
                    ?: throw EngineException(
                        NativeError("invalidState", "工作区还在连接，请稍候再试"),
                    )
                val arg = buildJsonObject {
                    put("taskId", task.id)
                    // 多根项目的任务有自己的 path/identity；不能用所属工作区的主根。
                    put("workspacePath", task.workspacePath ?: workspace.path)
                    (task.workspaceIdentity ?: workspace.identity)?.let {
                        put("workspaceIdentity", it)
                    }
                    task.remoteSessionId?.let { put("remoteSessionId", it) }
                    put("title", task.title ?: "")
                    task.displayStatus?.let { put("displayStatus", it) }
                    task.updatedAt?.let { put("updatedAt", it) }
                    task.createdAt?.let { put("createdAt", it) }
                    task.unreadAt?.let { put("unreadAt", it) }
                    extra()
                }
                engine.channelCall(
                    session = bridge,
                    channel = "zcode-task",
                    method = method,
                    args = JsonArray(listOf(arg)),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { current ->
                    val beforeIds = before.mapTo(HashSet()) { task -> task.id }
                    current.copy(
                        error = e.toUserMessage(),
                        workspaces = current.workspaces.map {
                            if (it.key == workspaceKey) {
                                // 只回滚调用前的任务；在飞期间新合并进来的任务保留。
                                it.copy(tasks = before + it.tasks.filter { t -> t.id !in beforeIds })
                            } else {
                                it
                            }
                        },
                    )
                }
            }
        }
    }

    /** 切换协作模式（`switchCollaborationMode`：build / edit / plan / yolo / guarded）。 */
    fun switchMode(mode: String) {
        // web 只认这五个值；未知值一律归一成 build，避免把 schema 外值发给桌面端。
        val next = when (mode) {
            "build", "edit", "plan", "guarded", "yolo" -> mode
            else -> "build"
        }
        val sessionId = _state.value.selectedTaskId
        if (sessionId == null) {
            // 草稿态：还没有会话可切换，先记住选择；首条消息 createSession 时下发。
            _state.update { it.copy(draftMode = next, error = null, failedMode = null) }
            return
        }
        val previousConfig = _state.value.config
        viewModelScope.launch {
            // 乐观回填：网页点完立即生效；这里也先改本地 config，稍后由服务端快照校正。
            _state.update {
                it.copy(
                    config = previousConfig?.withMode(next)
                        ?: JsonObject(mapOf("mode" to JsonPrimitive(next))),
                    error = null,
                    failedMode = null,
                )
            }
            try {
                val ack = chatOps.call(
                    "switchCollaborationMode",
                    buildJsonObject { put("sessionId", sessionId); put("mode", next) },
                    conversationScope(),
                )
                val ackObject = ack as? JsonObject
                val status = ackObject?.get("status").asString()
                // accepted / noop / duplicate 都算生效；其余是服务端拒绝，必须回滚并提示。
                if (status != null && status !in setOf("accepted", "noop", "duplicate")) {
                    val reason = ackObject?.get("message").asString()
                        ?: ackObject?.get("reasonCode").asString()
                        ?: status
                    _state.update {
                        it.copy(
                            config = previousConfig,
                            error = "模式切换被拒绝：$reason",
                            failedMode = next,
                        )
                    }
                    return@launch
                }
                refreshConversationState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        config = previousConfig,
                        error = e.toUserMessage(),
                        failedMode = next,
                    )
                }
            }
        }
    }

    /** 模式切换的乐观回填：只改 `config.mode` 与 `planEnabled`，其余字段原样保留。 */
    private fun JsonObject.withMode(mode: String): JsonObject = JsonObject(
        this + ("mode" to JsonPrimitive(mode)) + ("planEnabled" to JsonPrimitive(mode == "plan")),
    )

    /** 错误条「重试」：优先重发失败的模式切换；没有待重试动作时回退到整页刷新。 */
    fun retryLastAction() {
        val mode = _state.value.failedMode
        if (mode != null) {
            _state.update { it.copy(error = null) }
            switchMode(mode)
            return
        }
        refresh()
    }

    /**
     * 拉取模型目录（`model-selection.getView`）。
     *
     * 网页端用同一份视图渲染「按 provider 分组」的二级菜单，Effort 档位也来自
     * 当前模型的 `config.optionSpecs.reasoningLevel.values`。
     */
    fun loadModelView() {
        val sessionId = _state.value.selectedTaskId ?: return
        if (_state.value.modelViewLoading) return
        viewModelScope.launch {
            _state.update { it.copy(modelViewLoading = true, modelViewError = null) }
            try {
                val result = chatOps.call(
                    "modelSelectionView",
                    buildJsonObject { put("sessionId", sessionId) },
                    conversationScope(),
                )
                _state.update {
                    it.copy(
                        modelView = result as? JsonObject,
                        modelViewLoading = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(modelViewLoading = false, modelViewError = e.toUserMessage())
                }
            }
        }
    }

    /** 切换模型（`switchModelConfig`）；`thought` 为空时沿用当前档位。 */
    fun switchModel(provider: String, model: String, thought: String? = null) {
        val sessionId = _state.value.selectedTaskId ?: return
        val config = _state.value.config
        val nextThought = thought
            ?: config?.get("thought").asString()
            ?: modelViewReasoningLevels(_state.value.modelView, provider, model).firstOrNull()
            ?: ""
        viewModelScope.launch {
            try {
                chatOps.call(
                    "switchModelConfig",
                    buildJsonObject {
                        put("sessionId", sessionId)
                        put("provider", provider)
                        put("model", model)
                        put("thought", nextThought)
                    },
                    conversationScope(),
                )
                refreshConversationState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toUserMessage()) }
            }
        }
    }

    /**
     * 切换思考级别（`switchModelConfig`；provider/model 取当前 config）。
     */
    fun switchThought(thought: String) {
        val sessionId = _state.value.selectedTaskId ?: return
        val config = _state.value.config ?: return
        val provider = config["provider"].asString() ?: return
        val model = config["model"].asString() ?: return
        viewModelScope.launch {
            try {
                chatOps.call(
                    "switchModelConfig",
                    buildJsonObject {
                        put("sessionId", sessionId)
                        put("provider", provider)
                        put("model", model)
                        put("thought", thought)
                    },
                    conversationScope(),
                )
                refreshConversationState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toUserMessage()) }
            }
        }
    }

    /**
     * 从对话层快照刷新对话的派生状态（运行中、待交互、队列、config）。
     *
     * 行窗口由 `rowsUpdated` 增量维护；这里只读「不属于行」的那部分状态。
     * `conversations` / `sessionsIndex` 在 `ConversationTransport` 里，核心快照没有。
     */
    private suspend fun refreshConversationState() {
        val bridge = _state.value.bridgeSession ?: return
        val key = _state.value.conversationKey ?: return
        // 对话刚关闭/重开时可能瞬时 invalidState；保持现有状态即可，下一帧会再来。
        val snapshot = runCatching { engine.conversationSnapshot(bridge, key) }
            .getOrNull() as? JsonObject ?: return
        val sessionId = _state.value.selectedTaskId
        val conversations = snapshot["conversations"] as? JsonObject
        val conversation = sessionId?.let { conversations?.get(it) as? JsonObject }
        val indexes = snapshot["sessionsIndex"] as? JsonArray
        val workspaceId = indexes?.firstOrNull().asObj()?.get("workspaceId").asString()
        // 对话页的 `workspaces` 来自 bootstrap，那里**没有任务**；任务只存在于 sessions-index。
        // 不合并的话 `selectedTask` 永远为 null，聊天页里的置顶/归档/删除/改名全部 no-op。
        val currentWorkspace = _state.value.selectedWorkspace
        val indexedTasks = if (currentWorkspace != null && indexes != null) {
            SessionsIndexProjection.tasks(currentWorkspace, indexes)
        } else {
            emptyList()
        }

        _state.update { current ->
            val mergedWorkspaces = if (indexedTasks.isEmpty() || currentWorkspace == null) {
                current.workspaces
            } else {
                current.workspaces.map { workspace ->
                    if (workspace.key != currentWorkspace.key) return@map workspace
                    val previous = workspace.tasks.associateBy { it.id }
                    // 对话页只关心「当前任务」与索引里的最新任务：以索引为准重建，
                    // 把本地已知的 pinned/archived/unreadAt 贴回去（索引不含这些字段）。
                    val merged = indexedTasks.map { task ->
                        val old = previous[task.id]
                        val title = when {
                            task.titleSource == "custom" -> task.title ?: old?.title
                            !task.title.isNullOrBlank() -> task.title
                            else -> old?.title
                        }
                        if (old == null) {
                            task.copy(title = title)
                        } else {
                            task.copy(
                                title = title,
                                pinned = old.pinned,
                                archived = old.archived,
                                unreadAt = old.unreadAt ?: task.unreadAt,
                            )
                        }
                    }
                    val mergedIds = merged.mapTo(HashSet()) { t -> t.id }
                    // 刚建的新会话可能还没进索引，用当前选中任务占位；其余旧任务不复活。
                    val extras = workspace.tasks.filter {
                        it.id !in mergedIds && it.id == current.selectedTaskId
                    }
                    workspace.copy(tasks = merged + extras)
                }
            }
            current.copy(
                workspaces = mergedWorkspaces,
                workspaceId = workspaceId ?: current.workspaceId,
                isRunning = conversation?.get("isRunning").asBool() ?: current.isRunning,
                pendingInteractions = (conversation?.get("pendingInteractions") as? JsonArray)
                    ?: current.pendingInteractions,
                queueItems = conversation?.queueItemsOrNull() ?: current.queueItems,
                config = (conversation?.get("config") as? JsonObject) ?: current.config,
            )
        }

        notifyAttention()
    }

    /**
     * 请求刷新对话派生状态（运行中、待交互、队列、config、任务列表）。
     *
     * `sessionsIndexUpdated` 在流式期间每秒能来十几次，每次都拉一份约 250KB 的完整
     * 对话快照并在单线程引擎 dispatcher 上解析；逐条刷新会把协议帧的处理挤到后面，
     * 表现就是思考/正文的增量比网页慢半拍。这里做合并 + 最小间隔节流：首帧立即刷，
     * 之后最多每 [CONVERSATION_REFRESH_MIN_INTERVAL_MS] 刷一次，中间的事件只记「还要再刷」。
     */
    private fun requestConversationRefresh() {
        if (conversationRefreshJob?.isActive == true) {
            conversationRefreshQueued = true
            return
        }
        conversationRefreshJob = viewModelScope.launch {
            do {
                conversationRefreshQueued = false
                refreshConversationState()
                if (conversationRefreshQueued) {
                    delay(CONVERSATION_REFRESH_MIN_INTERVAL_MS)
                }
            } while (conversationRefreshQueued)
        }
    }

    /**
     * 订阅 ack 超时后的自动恢复：重建引擎 → 重开当前工作区 → 重新订阅。
     *
     * 与错误条「重试」走同一条已实测有效的路径（21:41 日志：reconnect → subscribe ack 893ms）。
     * 冷启动复用了首页预览的旧桥时，对端可能不再响应对话订阅；重建是唯一可靠的解。
     */
    private fun recoverSubscription(sessionId: String) {
        if (subscribeRecoveryJob?.isActive == true) return
        subscribeRecoveryJob = viewModelScope.launch {
            try {
                engine.reconnect()
                val workspaceKey = _state.value.selectedWorkspaceKey
                if (workspaceKey != null) {
                    openWorkspaceInternal(workspaceKey, sessionId)
                } else {
                    subscribeConversation(sessionId, force = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ZLog.vm("chat", "subscribe auto recover failed session=$sessionId", e)
                _state.update { it.copy(error = SUBSCRIBE_FAILED_MESSAGE) }
            }
        }
    }


    /** 处理 `rowsUpdated`：snapshot 覆盖窗口，deltas 增量归约。 */
    private fun applyRowsUpdated(raw: JsonElement) {
        val event = raw as? JsonObject ?: return
        // 同一工作区里可能同时订阅了多个会话：事件自带 sessionId，必须按当前任务过滤，
        // 否则另一条对话的行会混进本页并被写进本地缓存。
        val eventSession = event["sessionId"].asString()
        if (eventSession != null && eventSession != _state.value.selectedTaskId) return
        val frame = event["frame"] as? JsonObject ?: return
        val payload = frame["payload"] as? JsonObject ?: return
        // 到帧即完成首帧信号：订阅成功判据（ack + 首帧）的后一半。
        firstFrameSignal?.complete(Unit)
        when (payload["kind"].asString()) {
            "snapshot" -> {
                val snapshot = payload["snapshot"] as? JsonObject ?: return
                val window = ConversationRows.fromSnapshot(snapshot)
                val epoch = snapshot["logEpoch"].asString()
                // 事件属于哪个任务只认一次：应用时再校验，别的会话/过期任务的快照不能
                // 把当前页的骨架撤掉。
                val target = _state.value.selectedTaskId
                _state.update { current ->
                    val epochChanged = epoch != null && current.logEpoch != null &&
                        epoch != current.logEpoch
                    current.copy(
                        // 窗口内以快照为准；窗口之前的历史（缓存/已翻页）只在不与窗口
                        // 断开时保留（否则会拼出一段空洞）。epoch 变化说明日志重建，
                        // 旧前缀一律作废、整窗替换（10 审计 P1-7）。
                        rows = if (epochChanged) {
                            window.rows.sortedBy { it.rowId() }
                        } else {
                            mergeSnapshotRows(current.rows, window.rows)
                        },
                        // 快照到达（哪怕窗口为空）就说明「内容已就绪」：空对话显示空态，
                        // 而不是一直停在骨架。
                        rowsReady = if (current.selectedTaskId == target) true else current.rowsReady,
                        totalCount = if (window.rows.isEmpty()) {
                            current.totalCount
                        } else {
                            window.totalCount
                        },
                        firstRowId = window.firstRowId ?: current.firstRowId,
                        logEpoch = epoch ?: current.logEpoch,
                        // 日志被重建（epoch 变化）时旧会话的「取完」结论不再成立。
                        historyExhausted = if (epochChanged) false else current.historyExhausted,
                    )
                }
                if (_state.value.rowsReady) {
                    rowsWatchdogJob?.cancel()
                    rowsWatchdogJob = null
                    // 看门狗写的那条超时提示在内容到达后自动消失，不会一直挡在界面上。
                    _state.update {
                        if (it.error == ROWS_TIMEOUT_MESSAGE) it.copy(error = null) else it
                    }
                }
                val applied = _state.value
                ZLog.vm(
                    "chat",
                    "snapshot applied window=${window.rows.size} rows=${applied.rows.size} " +
                        "total=${applied.totalCount} first=${applied.firstRowId} " +
                        "epoch=${epoch ?: "-"} ready=${applied.rowsReady}",
                )
                ZLog.memory("chat.snapshot", applied.rows.size)
                trimWindowIfNeeded()
                recomputeCanLoadOlder()
                maybeBackfillFirstTurn()
                applyConversationFields(snapshot)
                schedulePersist()
            }

            "deltas" -> {
                val deltas = payload["deltas"] as? JsonArray ?: return
                val rowOps = JsonArray(
                    deltas.filter { op ->
                        (op as? JsonObject)?.get("op").asString()?.startsWith("row.") == true
                    },
                )
                val patches = deltas.mapNotNull { op ->
                    val obj = op as? JsonObject ?: return@mapNotNull null
                    if (obj["op"].asString() != "state.updated") null
                    else obj["patch"] as? JsonObject
                }
                val current = _state.value
                val window = ConversationRows.apply(
                    ConversationRows.Window(current.rows, current.totalCount, current.firstRowId),
                    rowOps,
                )
                _state.update {
                    it.copy(
                        rows = window.rows,
                        totalCount = window.totalCount,
                        firstRowId = window.firstRowId,
                    )
                }
                trimWindowIfNeeded()
                recomputeCanLoadOlder()
                patches.forEach { applyStatePatch(it) }
                schedulePersist()
            }
        }
    }

    /**
     * 从对话快照（`payload.snapshot`）刷新「不属于行」的状态。
     *
     * 注意：核心的 `nativeQuery.snapshot()` **不包含** `conversations`（那在
     * `conversationSnapshot` 里），而 `rowsUpdated` 的 snapshot 就是完整对话状态，直接用它。
     */
    private fun applyConversationFields(snapshot: JsonObject) {
        _state.update { current ->
            current.copy(
                isRunning = snapshot.conversationIsRunning() ?: current.isRunning,
                pendingInteractions = (snapshot["pendingInteractions"] as? JsonArray)
                    ?: current.pendingInteractions,
                queueItems = snapshot.queueItemsOrNull() ?: current.queueItems,
                queueAutoDrain = snapshot.queueAutoDrainOrNull() ?: current.queueAutoDrain,
                queuePauseReason = snapshot.queuePauseReasonOrNull() ?: current.queuePauseReason,
                config = (snapshot["config"] as? JsonObject) ?: current.config,
            )
        }
        notifyAttention()
    }

    /**
     * 首屏快照可能从某一轮中间开始（窗口首行的 turnId 在窗口内没有 turnHeader）。
     * 网页会静默补拉一次，让第一轮有段标题；这里同样只补一次。
     */
    private fun maybeBackfillFirstTurn() {
        if (backfillTried) return
        val rows = _state.value.rows
        val firstTurn = rows.firstOrNull()?.get("turnId").asString() ?: return
        val hasHeader = rows.any { row ->
            row.kind() == "turnHeader" && row["turnId"].asString() == firstTurn
        }
        backfillTried = true
        if (!hasHeader) loadOlder()
    }

    /** 窗口变化后重算「还能不能往前翻」：网页口径（窗口首行 > 会话首行）并联动 [ChatUiState.historyExhausted]。 */
    private fun recomputeCanLoadOlder() {
        _state.update { current ->
            val can = canLoadOlderAt(current.rows, current.firstRowId, current.historyExhausted)
            if (can == current.canLoadOlder) {
                current
            } else {
                ZLog.vm(
                    "chat",
                    "canLoadOlder ${current.canLoadOlder}->$can rows=${current.rows.size} " +
                        "first=${current.rows.firstOrNull()?.rowId()} head=${current.firstRowId} " +
                        "exhausted=${current.historyExhausted}",
                )
                current.copy(canLoadOlder = can)
            }
        }
    }

    /** `state.updated.patch` 是顶层浅替换；只取 UI 需要的键。 */
    private fun applyStatePatch(patch: JsonObject) {
        _state.update { current ->
            current.copy(
                isRunning = patch.conversationIsRunning() ?: current.isRunning,
                pendingInteractions = (patch["pendingInteractions"] as? JsonArray)
                    ?: current.pendingInteractions,
                queueItems = patch.queueItemsOrNull() ?: current.queueItems,
                queueAutoDrain = patch.queueAutoDrainOrNull() ?: current.queueAutoDrain,
                queuePauseReason = patch.queuePauseReasonOrNull() ?: current.queuePauseReason,
                config = (patch["config"] as? JsonObject) ?: current.config,
            )
        }
        notifyAttention()
    }

    /** `phase ∈ {running, prewarming}`，与网页端 `isRunning` 口径一致。 */
    private fun JsonObject.conversationIsRunning(): Boolean? {
        val control = this["control"] as? JsonObject ?: return null
        val phase = control["phase"].asString() ?: return null
        return phase == "running" || phase == "prewarming"
    }

    /** `queueItems` 优先；否则从 `queue.items` 里过滤出对象元素。 */
    private fun JsonObject.queueItemsOrNull(): JsonArray? {
        (this["queueItems"] as? JsonArray)?.let { return it }
        val items = (this["queue"] as? JsonObject)?.get("items") as? JsonArray ?: return null
        return JsonArray(items.filter { it is JsonObject })
    }

    /** `queue.autoDrain`；顶层同名字段是 FFI 派生值，作为兜底。 */
    private fun JsonObject.queueAutoDrainOrNull(): Boolean? {
        (this["queue"] as? JsonObject)?.get("autoDrain").asBool()?.let { return it }
        return this["autoDrain"].asBool()
    }

    private fun JsonObject.queuePauseReasonOrNull(): String? =
        (this["queue"] as? JsonObject)?.get("pauseReason").asString()
            ?: this["pauseReason"].asString()

    /** 待授权/待输入：只在有选中任务时上报（通知要能定位回这个任务）。 */
    private fun notifyAttention() {
        val task = _state.value.selectedTask
        val workspace = _state.value.selectedWorkspace
        if (task == null || workspace == null) return
        val pending = _state.value.pendingInteractions?.isNotEmpty() == true
        onAttention?.invoke(task.id, task.title ?: task.id, workspace.key, pending)
    }

    /** 防抖写缓存（600ms）；流式帧期间不逐帧落盘。 */
    private fun schedulePersist() {
        val cache = cache ?: return
        val taskId = _state.value.selectedTaskId ?: return
        val workspaceKey = _state.value.selectedWorkspaceKey ?: return
        val rows = _state.value.rows
        if (rows.isEmpty()) return
        val logEpoch = _state.value.logEpoch
        val firstRowId = _state.value.firstRowId
        persistJob?.cancel()
        persistJob = viewModelScope.launch(Dispatchers.IO) {
            delay(600)
            cache.saveConversation(workspaceKey, taskId, rows, logEpoch, firstRowId)
        }
    }

    /** 取消防抖并立刻写一次（进后台用；内容为空时不写）。 */
    private fun persistNow() {
        val cache = cache ?: return
        val taskId = _state.value.selectedTaskId ?: return
        val workspaceKey = _state.value.selectedWorkspaceKey ?: return
        val rows = _state.value.rows
        if (rows.isEmpty()) return
        val logEpoch = _state.value.logEpoch
        val firstRowId = _state.value.firstRowId
        persistJob?.cancel()
        persistJob = viewModelScope.launch(Dispatchers.IO) {
            cache.saveConversation(workspaceKey, taskId, rows, logEpoch, firstRowId)
        }
    }

    private fun observe() {        observers += collect(engine.engineState) { s -> _state.update { it.copy(engineState = s) } }
        observers += collect(engine.relayState) { r -> _state.update { it.copy(relayState = r) } }
        observers += collect(engine.relayFailure) { f ->
            val needsLogin = f?.reason == "auth-failed" || f?.reason == "auth-required"
            if (needsLogin) {
                viewModelScope.launch {
                    authStore.clear()
                    engine.disconnect()
                }
            }
            _state.update {
                it.copy(
                    relayFailure = f,
                    needsLogin = it.needsLogin || needsLogin,
                    error = if (needsLogin) "登录已失效，请重新登录" else it.error,
                )
            }
        }
        // 注意：**不要**镜像 `engine.bridgeSession`（那是全局「最近一次开桥」）。
        // 本页的 bridge/conversationKey 只由 ensureRouteBridge / openWorkspaceInternal 设置，
        // 否则另一个对话页或首页预览开桥会把本页身份改掉，事件门禁会把本页帧全部丢掉。
        observers += collect(repository.devices) { devices ->
            val device = devices.firstOrNull { it.id == deviceId }
            _state.update {
                it.copy(deviceName = device?.name, credentialsMissing = device?.needsRepair == true)
            }
        }
        observers += collect(engine.events) { event -> onEngineEvent(event) }
    }

    private fun onEngineEvent(event: EngineEvent) {
        when (event) {
            is EngineEvent.WorkspaceListUpdated -> {
                // `workspace-list-*` 是**整表快照**：合法的空快照要清空 UI（旧实现
                // 「空列表不覆盖」会让界面永久卡在旧数据上）。认不出的形状才不动。
                WorkspaceProjection.snapshotOrNull(event.value)?.let { snapshot ->
                    _state.update { current ->
                        val merged = WorkspaceProjection.merge(current.workspaces, snapshot.workspaces)
                        // 整表推送只包含桌面 tab；路由带来的项目树工作区不在里面，
                        // 直接替换会让 selectedWorkspace/selectedTask 变 null、mutation 全部失效。
                        val route = routeWorkspace
                        if (route == null || merged.any { it.key == route.key }) {
                            current.copy(workspaces = merged)
                        } else {
                            val previous = current.workspaces.firstOrNull { it.key == route.key }
                            current.copy(
                                workspaces = merged +
                                    (previous?.let { route.copy(tasks = it.tasks) } ?: route),
                            )
                        }
                    }
                }
            }

            is EngineEvent.AppError -> {
                // 对端主动报错：旧实现完全忽略，桌面端断开时界面永远转圈。
                val text = when (event.reason) {
                    "desktop-disconnected" -> "桌面端已断开远控"
                    "session-conflict" -> "另一台手机接管了远控"
                    "workspace-closed" -> "工作区已关闭"
                    "unsupported-action" -> "桌面端不支持这个操作"
                    else -> event.error ?: "桌面端报错"
                }
                _state.update { it.copy(error = text) }
            }

            is EngineEvent.ConversationEvent -> {
                // 共享事件流：别的对话页/工作区订阅的帧不能混进本页（会污染行与持久缓存）。
                // 本页还没登记桥身份时（进页第一拍/测试）放行；会话级事件另有 sessionId 门禁。
                val mine = _state.value.bridgeSession.let { bridge ->
                    bridge == null ||
                        (event.session == bridge && event.key == _state.value.conversationKey)
                }
                if (!mine) return
                eventSeq += 1
                val type = event.event.stringField("type").orEmpty()
                // rowsUpdated / sessionsIndexUpdated 是流式高频帧（每秒十几条），界面不消费
                // 这份事件流；逐条复制进 state 会让整页 state 每秒变更十几次、拖慢渲染。
                // 其余低频事件仍保留，供诊断与测试使用。
                if (type != "rowsUpdated" && type != "sessionsIndexUpdated") {
                    val item = ConversationEventItem(
                        seq = eventSeq,
                        session = event.session,
                        key = event.key,
                        type = type,
                        raw = event.event,
                    )
                    _state.update { current ->
                        val next = current.conversationEvents + item
                        current.copy(
                            conversationEvents = if (next.size > MAX_CONVERSATION_EVENTS) {
                                next.takeLast(MAX_CONVERSATION_EVENTS)
                            } else {
                                next
                            },
                        )
                    }
                }
                when (type) {
                    "rowsUpdated" -> applyRowsUpdated(event.event)
                    "attachmentProgress" -> {
                        val payload = event.event as? JsonObject
                        val eventSession = payload?.get("sessionId").asString()
                        if (eventSession != null && eventSession != _state.value.selectedTaskId) {
                            return
                        }
                        val progress = payload?.get("progress").asDouble()
                        if (progress != null) {
                            _state.update {
                                it.copy(uploadProgress = progress.toFloat().coerceIn(0f, 1f))
                            }
                        }
                    }
                    "stateChanged" ->
                        // 行、isRunning、config 已由 rowsUpdated 的 snapshot / state.updated 带上；
                        // 这里再拉整份对话快照（实测 300–700KB，解析近 1s）只会拖慢流式渲染。
                        Unit
                    "sessionsIndexUpdated" ->
                        // workspaceId 与任务列表只在这个事件里更新；任务列表不需要秒级新鲜度。
                        requestConversationRefresh()
                }
            }

            is EngineEvent.BridgeDegraded ->
                _state.update { it.copy(bridgeDegraded = event.degraded) }

            is EngineEvent.BridgeRecovered ->
                _state.update { it.copy(bridgeDegraded = null) }

            else -> Unit
        }
    }

    private fun <T> collect(flow: Flow<T>, onValue: (T) -> Unit): Job =
        viewModelScope.launch {
            try {
                flow.collect(onValue)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.toUserMessage()) }
            }
        }

    companion object {
        /** 等配对的上限（与引擎层默认值一致）。 */
        const val PAIR_TIMEOUT_MS: Long = 30_000

        /** 对话事件流在内存里保留的条数（与引擎日志环一样，不无限增长）。 */
        const val MAX_CONVERSATION_EVENTS: Int = 200

        /** 单个附件上限（协议侧 30 MiB 读取上限，上传留同样余量）。 */
        const val MAX_ATTACHMENT_BYTES: Int = 30 * 1024 * 1024

        /** 一条消息最多带几个附件。 */
        const val MAX_ATTACHMENTS: Int = 10

        /** `rowsRange` 每页行数（协议上限 200）。 */
        const val OLDER_PAGE_SIZE: Int = 60

        /**
         * 行窗口上界：超过就裁到 [TRIM_TARGET_ROWS]。
         *
         * 依据 10 审计 §3：约 30MB/1000 行的边际成本；600 行 ≈ 18MB 安全垫。
         * 与 `ChatCache.MAX_ROWS`（400）保持同一量级，避免「内存里 3000 行、磁盘 400 行」
         * 这种脱节口径。
         */
        const val MAX_WINDOW_ROWS: Int = 600

        /** 裁剪目标：留 200 行滞回，避免每合并一页裁一次。 */
        const val TRIM_TARGET_ROWS: Int = 400

        /** 进页后等首个快照的上限；超时把空白变成可重试的错误条。 */
        const val ROWS_READY_TIMEOUT_MS: Long = 6_000

        /** 看门狗文案（界面已有「重试」，这里只给原因）。 */
        const val ROWS_TIMEOUT_MESSAGE: String = "对话内容加载超时，可点重试"

        /** 后台超过这个时长就重建会话；短于一瞬的前后台切换只 poke，避免无谓重连。 */
        const val BACKGROUND_RECONNECT_MS: Long = 2_000

        /** 订阅重试间隔（仅进页/恢复的 force 订阅会重试）。 */
        private const val SUBSCRIBE_RETRY_DELAY_MS: Long = 1_500

        /**
         * 等 subscribe ack 的上限。
         *
         * 正常 ack 在 1s 内（实测 0.17–0.9s）；这里 6s 足够覆盖弱网，又不会让冷启动遇到
         * 旧桥失联时白等半分钟——失败后马上走自动恢复（重连 + 重开工作区）。
         */
        private const val SUBSCRIBE_ACK_TIMEOUT_MS: Long = 6_000

        /**
         * subscribe ack 后等首帧（快照）的上限；与 ack 超时共用同一条失败路径。
         * mode=first 的订阅 ack 后必然跟一份快照帧，等不到 = 订阅静默失效。
         */
        private const val SUBSCRIBE_FIRST_FRAME_TIMEOUT_MS: Long = 8_000

        /** 订阅失败且本地缓存仍有内容时也必须显式提示，不能静默冻结。 */
        const val SUBSCRIBE_FAILED_MESSAGE: String = "实时更新连接未建立，可点重试"

        /** 索引事件合并后的最小刷新间隔。整份对话快照可达数百 KB、解析近 1s；
         *  任务列表/workspaceId 不需要更实时，否则会持续挤占流式帧的处理。 */
        private const val CONVERSATION_REFRESH_MIN_INTERVAL_MS: Long = 10_000

        private const val TAG = "ChatViewModel"
    }
}

/**
 * 行窗口裁剪：超过 [maxRows] 时只保留最尾部 [targetRows] 行（最老端丢弃）。
 *
 * reverseLayout 下最老端在最高 index，删它们不改变其余单元的 index，也不会移动视口；
 * 调用方仍须用「可见区离最老端足够远」门控（见 `ChatViewModel.setWindowTrimSafe`）。
 */
internal fun trimWindowRows(
    rows: List<JsonObject>,
    maxRows: Int = ChatViewModel.MAX_WINDOW_ROWS,
    targetRows: Int = ChatViewModel.TRIM_TARGET_ROWS,
): List<JsonObject> {
    if (rows.size <= maxRows) return rows
    val target = targetRows.coerceIn(0, rows.size)
    if (target >= rows.size) return rows
    return rows.subList(rows.size - target, rows.size).toList()
}

/**
 * 把快照窗口合并进本地已有行。
 *
 * 规则（`docs/CONVERSATION-LIFECYCLE.md` §4）：
 * - 保留 `existing` 中 `rowId` 小于窗口最老行的行（缓存/已翻页的历史）；
 * - 但这些旧行必须与窗口**相邻**（最大 id + 1 ≥ 窗口最老 id）。中间有洞时整段丢弃，
 *   只保留窗口：拼出洞会让「上翻」跨过一段永远补不回来的空白（10 审计 P1-7）。
 * - 窗口内以快照为准（被服务端删除/改写的行不会复活）；
 * - 空窗口保留 `existing`（避免一次空帧清空界面）；
 * - 结果按 `rowId` 升序。
 */
internal fun mergeSnapshotRows(
    existing: List<JsonObject>,
    window: List<JsonObject>,
): List<JsonObject> {
    if (window.isEmpty()) return existing.sortedBy { it.rowId() }
    val oldest = window.minOf { it.rowId() }
    val kept = existing.filter { it.rowId() < oldest }.sortedBy { it.rowId() }
    val newestKept = kept.lastOrNull()?.rowId()
    if (newestKept != null && newestKept + 1 < oldest) {
        // 缓存/旧窗口与快照之间断开：宁可少显示一段，也不留永远补不上的洞。
        return window.sortedBy { it.rowId() }
    }
    return (kept + window).sortedBy { it.rowId() }
}

/**
 * 把路由工作区合并进 bootstrap 投影。
 *
 * bootstrap 的 `tasks` 恒为空（任务来自 sessions-index/readTree）；直接用它替换会把
 * 路由带过来的任务 id/标题/路径冲掉，导致页头「未命名」、任务操作拿不到 selectedTask。
 * 任务以路由为先（Home 刚点的那条是最权威的），bootstrap 只补它没有的 id。
 */
internal fun mergeRouteWorkspace(
    projected: List<WorkspaceItem>,
    route: WorkspaceItem?,
): List<WorkspaceItem> {
    if (route == null) return projected
    val index = projected.indexOfFirst { it.key == route.key }
    if (index < 0) return projected + route
    return projected.toMutableList().also { list ->
        val existing = list[index]
        val tasks = (route.tasks + existing.tasks).distinctBy { it.id }
        list[index] = existing.copy(tasks = tasks)
    }
}

/**
 * 网页 `canLoadOlder` 口径：窗口第一行仍晚于会话首行 [firstRowId] 时才可能还有更早历史。
 *
 * `historyExhausted` 是本地概念（网页没有这个符号）：确认取完后就把它压平成 `false`，
 * 免得继续发注定为空的 `rowsRange`。
 */
internal fun canLoadOlderAt(
    rows: List<JsonObject>,
    firstRowId: Long?,
    historyExhausted: Boolean,
): Boolean {
    if (historyExhausted) return false
    val oldest = rows.firstOrNull()?.rowId() ?: return false
    val head = firstRowId ?: return false
    return oldest > head
}

/**
 * 是否已经取完更早历史。
 *
 * 只在 `hasMore == false`，或**空页且没有明确说还有**（`hasMore` 缺失）时成立。
 * 普通 `loadOlder` 与网页一样不看 `hasMore`，但「不足一页」绝不能当成取完——
 * 那会让部分页之后永远不再请求历史。
 */
internal fun isOlderExhausted(hasMore: Boolean?, returnedCount: Int): Boolean =
    hasMore == false || (returnedCount == 0 && hasMore != true)

/**
 * `bootstrap` / `list_workspaces` / `workspace_list_updated` 的 `value` → 界面投影。
 *
 * 形状已冻结（对端 3.15.3 的权威 schema，见 `archive/pre-3.16.1-auth/docs/desktop/06-工作区与桥生命周期.md`
 * 与 `archive/pre-3.16.1-auth/docs/desktop/11-host进程与会话任务.md`）：
 * - `workspaces[i]`：8 键，`workspacePath` / `label` / `kind` 必填；
 *   `workspaceIdentity` **有值才出现，永不出现 null**；
 * - `tasks` 是 `workspaces` 的**顶层兄弟数组**，不嵌在工作区对象里；
 *   每个任务自带 `workspacePath` / `workspaceIdentity`，靠它归位。
 *
 * 键规则（四处独立实现一致）：`workspaceIdentity?.trim() || workspacePath`。
 */
internal object WorkspaceProjection {

    /**
     * 一次快照：工作区 + 当前落点。
     *
     * 3.16.1 的 bootstrap / `workspace-list-response` 都**没有顶层 `tasks`**（任务由
     * sessions-index 提供），所以这里不再解析它。
     */
    data class Snapshot(
        val workspaces: List<WorkspaceItem>,
        val activeWorkspaceKey: String?,
        val activeTaskId: String?,
    )

    fun from(value: JsonElement?): List<WorkspaceItem> =
        snapshotOrNull(value)?.workspaces ?: emptyList()

    /**
     * `workspace-list-updated` 只带工作区，不带任务。整表替换会让本地 tasks 空一拍
     * （下一帧 sessions-index 才补回来），所以按 key 保留旧任务；工作区集合仍以新快照为准。
     */
    fun merge(current: List<WorkspaceItem>, updated: List<WorkspaceItem>): List<WorkspaceItem> {
        val previous = current.associateBy { it.key }
        return updated.map { workspace ->
            val old = previous[workspace.key] ?: return@map workspace
            if (workspace.tasks.isNotEmpty()) workspace else workspace.copy(tasks = old.tasks)
        }
    }

    /**
     * `null` = **认不出的形状**（既不是数组也不是带 `workspaces`/`items`/`list` 的对象）。
     * 与「合法的空快照」区分开：后者返回空列表，应该清空 UI。
     */
    fun snapshotOrNull(value: JsonElement?): Snapshot? {
        val root = value as? JsonObject
        val array = when {
            value is JsonArray -> value
            root != null -> root["workspaces"]?.asArrayOrNull()
                ?: root["items"]?.asArrayOrNull()
                ?: root["list"]?.asArrayOrNull()

            else -> null
        } ?: return null

        val workspaces = array.mapNotNull { it.asObjectOrNull()?.toWorkspace() }

        return Snapshot(
            workspaces = workspaces,
            activeWorkspaceKey = root?.strField("activeWorkspaceKey"),
            activeTaskId = root?.strField("activeTaskId"),
        )
    }

    private fun JsonObject.toWorkspace(): WorkspaceItem? {
        // `workspacePath` 是必填且是真实路径；缺它这条记录不可用。
        val path = strField("workspacePath") ?: return null
        val identity = strField("workspaceIdentity")?.trim()?.takeIf { it.isNotEmpty() }
        return WorkspaceItem(
            key = identity ?: path,
            path = path,
            identity = identity,
            label = strField("label")
                ?: path.substringAfterLast('/').substringAfterLast('\\').ifEmpty { path },
            kind = strField("kind"),
            purpose = strField("workspacePurpose"),
            connectionState = strField("connectionState"),
            lastError = strField("lastConnectionError"),
            remoteSessionId = strField("remoteSessionId"),
        )
    }

    private fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonElement.asArrayOrNull(): JsonArray? = this as? JsonArray
}

// ---------------------------------------------------------------------------
// 局部 JSON 小工具
//
// 刻意**不复用引擎层的 `internal` 扩展**（`app.zemote.engine.str` 等）：那是引擎的内部约定，
// 不是给上层用的 API。这几个函数只有几行，重复比耦合便宜。
// ---------------------------------------------------------------------------

/** 毫秒耗时，日志专用。 */
private fun elapsedMsLog(startedAt: Long): Long =
    (System.nanoTime() - startedAt) / 1_000_000L

/** 取字符串字段；空串与非字符串一律当「没有这个字段」。 */
private fun JsonObject.strField(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

/** 从任意 JSON 值里取字符串字段（对话事件的 `type`）。 */
private fun JsonElement.stringField(key: String): String? =
    (this as? JsonObject)?.strField(key)
