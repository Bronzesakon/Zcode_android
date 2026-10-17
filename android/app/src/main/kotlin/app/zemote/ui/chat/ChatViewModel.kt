package app.zemote.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zemote.data.ChatCache
import app.zemote.data.DeviceRecord
import app.zemote.engine.EngineEvent
import app.zemote.engine.EngineState
import app.zemote.engine.RelayState
import app.zemote.ui.EnginePort
import app.zemote.ui.devices.DeviceRepository
import app.zemote.ui.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 一个工作区在界面上的投影。
 *
 * 字段名取自对端 `workspaces[i]` 的权威 schema（8 键，见 `docs/desktop/06-工作区与桥生命周期.md`）：
 * `workspacePath`(必) / `label`(必) / `kind`(必) / `workspaceIdentity` / `workspacePurpose` /
 * `remoteSessionId` / `connectionState` / `lastConnectionError`。
 *
 * 两个容易搞错的地方：
 * - `workspaceIdentity` **有值才出现，永不出现 null**；键缺席就表示没有。
 * - 键规则是 `workspaceIdentity?.trim() || workspacePath`（identity 优先，且只 trim identity）。
 */
data class WorkspaceItem(
    /** 工作区键（`open_bridge` 用它）。= `workspaceIdentity?.trim() || workspacePath`。 */
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
 * 字段名取自对端 `tasks[i]` 的权威 schema（16 键，见 `docs/desktop/11-host进程与会话任务.md`）。
 * 注意：`pinned` / `archived` / `hasBackgroundWork` 是**「存在即 true」的旗标**——
 * 协议里 false 时整个键缺席，不是发 `false`；`unreadAt` 是 number，缺省表示无未读。
 */
data class TaskItem(
    /** `taskId`（协议里 `taskId === sessionId`）。 */
    val id: String,
    val title: String? = null,
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
    val workspaceLabel: String? = null,
    val workspaceKind: String? = null,
    val remoteSessionId: String? = null,
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
    val workspaces: List<WorkspaceItem> = emptyList(),
    val selectedWorkspaceKey: String? = null,
    val selectedTaskId: String? = null,
    val conversationEvents: List<ConversationEventItem> = emptyList(),
    /** 对话行窗口（按 `rowId` 升序）。 */
    val rows: List<JsonObject> = emptyList(),
    val totalCount: Long = 0,
    val firstRowId: Long? = null,
    val isRunning: Boolean = false,
    val pendingInteractions: JsonArray? = null,
    val queueItems: JsonArray? = null,
    val config: JsonObject? = null,
    /** 当前工作区的 `workspaceId`（`createSession` 要用）。 */
    val workspaceId: String? = null,
    val conversationKey: String? = null,
    val sending: Boolean = false,
    /** 已选中、还没发出的附件。 */
    val attachments: List<ChatAttachment> = emptyList(),
    /** 正在上传附件（发送按钮转圈）。 */
    val uploading: Boolean = false,
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
    private val chatOps: ChatOps,
    private val deviceId: String,
    private val initialWorkspaceKey: String? = null,
    private val initialTaskId: String? = null,
    private val pairTimeoutMs: Long = PAIR_TIMEOUT_MS,
    /** 待交互状态变化时通知外部（`null` = 不通知，单测与无通知环境用）。 */
    private val onAttention: ((taskId: String, title: String, workspaceKey: String, active: Boolean) -> Unit)? = null,
    /** 行窗口的本地缓存（`null` = 不缓存）。 */
    private val cache: ChatCache? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(
        ChatUiState(
            selectedWorkspaceKey = initialWorkspaceKey,
            selectedTaskId = initialTaskId,
        ),
    )

    /** 界面唯一的状态入口。 */
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val observers = mutableListOf<Job>()

    private var entered = false

    private var eventSeq = 0L

    /** 缓存写入的防抖任务（流式帧很密，不逐帧落盘）。 */
    private var persistJob: Job? = null

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

    /** 手动刷新工作区/任务列表。 */
    fun refresh() {
        viewModelScope.launch {
            if (!ensureReady()) return@launch
            refreshInternal()
        }
    }

    /** 打开一个工作区的桥（`open_bridge` + `bridge_wait_healthy` + 回推移动端焦点）。 */
    fun openWorkspace(workspaceKey: String, taskId: String? = null) {
        viewModelScope.launch {
            if (!ensureReady()) return@launch
            openWorkspaceInternal(workspaceKey, taskId)
        }
    }

    /** 切换当前任务（只改焦点，不重开桥）。 */
    fun selectTask(taskId: String?) {
        _state.update { it.copy(selectedTaskId = taskId) }
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
        if ((trimmed.isEmpty() && pending.isEmpty()) || _state.value.sending) return
        val sessionId = _state.value.selectedTaskId
        if (sessionId == null) {
            createConversation(trimmed)
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(sending = true, error = null) }
            try {
                val attachments = uploadAttachments(sessionId)
                chatOps.call(
                    ChatOps.OP_SEND_TEXT,
                    buildJsonObject {
                        put("sessionId", sessionId)
                        put(ChatOps.ARG_TEXT, trimmed)
                        if (attachments.isNotEmpty()) put("attachments", attachments)
                    },
                    conversationScope(),
                )
                _state.update { it.copy(attachments = emptyList()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toUserMessage()) }
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

    /** 依次上传待发附件，返回描述符数组（失败即中止整条消息）。 */
    private suspend fun uploadAttachments(sessionId: String): JsonArray {
        val pending = _state.value.attachments
        if (pending.isEmpty()) return JsonArray(emptyList())
        _state.update { it.copy(uploading = true) }
        val descriptors = mutableListOf<JsonElement>()
        for (attachment in pending) {
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
            if (descriptor is JsonObject) descriptors += descriptor
        }
        return JsonArray(descriptors)
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

    /** 上滑到顶时加载更早的行（`rowsRange`，默认 60 行）。 */
    fun loadOlder() {
        val sessionId = _state.value.selectedTaskId ?: return
        val before = _state.value.firstRowId ?: return
        viewModelScope.launch {
            try {
                val result = chatOps.call(
                    "rowsRange",
                    buildJsonObject {
                        put("sessionId", sessionId)
                        put("beforeRowId", before)
                        put("limit", 60)
                    },
                    conversationScope(),
                )
                val obj = result as? JsonObject ?: return@launch
                val older = (obj["rows"] as? JsonArray)
                    ?.mapNotNull { it as? JsonObject }
                    .orEmpty()
                if (older.isEmpty()) return@launch
                val existing = _state.value.rows
                val ids = existing.mapTo(HashSet()) { it.rowId() }
                val merged = (older.filter { it.rowId() !in ids } + existing)
                    .sortedBy { it.rowId() }
                _state.update {
                    it.copy(rows = merged, firstRowId = merged.firstOrNull()?.rowId())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toUserMessage()) }
            }
        }
    }

    /**
     * 新对话的第一条消息：`createSession(workspaceId, firstText)`。
     *
     * 有附件时 `firstText` 不随 `createSession` 发：附件必须挂在已存在的 sessionId 上
     * 上传，所以先建会话、再上传、最后用 `sendText` 把文本与描述符一起发出。
     */
    private fun createConversation(firstText: String) {
        val workspaceId = _state.value.workspaceId
        if (workspaceId == null) {
            _state.update { it.copy(error = "工作区还没就绪，请稍后再试") }
            return
        }
        val hasAttachments = _state.value.attachments.isNotEmpty()
        viewModelScope.launch {
            _state.update { it.copy(sending = true, error = null) }
            try {
                val result = chatOps.call(
                    "createSession",
                    buildJsonObject {
                        put("workspaceId", workspaceId)
                        if (firstText.isNotEmpty() && !hasAttachments) put("firstText", firstText)
                    },
                    conversationScope(),
                )
                val newId = (result as? JsonPrimitive)?.contentOrNull
                if (newId.isNullOrBlank()) {
                    _state.update { it.copy(error = "服务端没有返回新会话 id") }
                    return@launch
                }
                _state.update { it.copy(selectedTaskId = newId, rows = emptyList(), totalCount = 0) }
                subscribeConversation(newId)

                if (hasAttachments) {
                    val attachments = uploadAttachments(newId)
                    chatOps.call(
                        ChatOps.OP_SEND_TEXT,
                        buildJsonObject {
                            put("sessionId", newId)
                            put(ChatOps.ARG_TEXT, firstText)
                            if (attachments.isNotEmpty()) put("attachments", attachments)
                        },
                        conversationScope(),
                    )
                    _state.update { it.copy(attachments = emptyList()) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toUserMessage()) }
            } finally {
                _state.update { it.copy(sending = false, uploading = false) }
            }
        }
    }

    /** 订阅一个会话的对话帧（`op = "subscribe"`）。 */
    private suspend fun subscribeConversation(sessionId: String) {
        val bridge = _state.value.bridgeSession ?: engine.bridgeSession.value ?: return
        val key = _state.value.conversationKey ?: return
        engine.conversationCall(
            bridge,
            key,
            "subscribe",
            buildJsonObject { put("sessionId", sessionId) },
        )
    }

    /**
     * `conversation_open` 的 scope。
     *
     * 必须是**真实路径 + identity 两个字段**：对端把 `workspacePath` 当成 agent CLI 的
     * spawn cwd，把 `workspaceIdentity` 当成工作区标识。把键塞进 `workspacePath`
     * 在远程工作区上会让 cwd 变成一个不存在的路径。
     */
    private fun conversationScope(): JsonObject {
        val workspace = _state.value.selectedWorkspace ?: return JsonObject(emptyMap())
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

    /** 回前台探活（`poke_relay`）。 */
    fun onForeground() {
        viewModelScope.launch {
            try {
                engine.pokeRelay()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toUserMessage()) }
            }
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

        // 幂等：已经连着同一个 URL 时不会重连（引擎实例 = 一条连接，见 EnginePort 的文档）。
        engine.ensureSession(url)
        refreshInternal()

        val workspaceKey = initialWorkspaceKey ?: return
        openWorkspaceInternal(workspaceKey, initialTaskId)
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
        if (engine.connectedUrl.value != null) return true
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
        engine.ensureSession(url)
        return true
    }

    private suspend fun refreshInternal() {
        _state.update { it.copy(loading = true, error = null) }
        try {
            engine.waitPaired(pairTimeoutMs)
            val snapshot = engine.bootstrap()
            _state.update { it.copy(workspaces = WorkspaceProjection.from(snapshot)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.toUserMessage()) }
        } finally {
            _state.update { it.copy(loading = false) }
        }
    }

    private suspend fun openWorkspaceInternal(workspaceKey: String, taskId: String?) {
        _state.update {
            it.copy(
                loading = true,
                error = null,
                selectedWorkspaceKey = workspaceKey,
                selectedTaskId = taskId,
            )
        }
        try {
            // 先铺本地缓存：打开过的对话立刻有内容，等快照到达再覆盖。
            if (taskId != null && cache != null) {
                val cached = cache.load(workspaceKey, taskId)
                if (cached.isNotEmpty()) {
                    _state.update {
                        it.copy(
                            rows = cached,
                            totalCount = cached.size.toLong(),
                            firstRowId = cached.firstOrNull()?.rowId(),
                        )
                    }
                }
            }
            val session = engine.openBridge(workspaceKey = workspaceKey, taskId = taskId)
            engine.waitHealthy(session)
            engine.sendMobileViewState(workspaceKey, taskId)

            // 打开对话层并订阅：sessions-index 用于拿 workspaceId 与任务预览；
            // 有任务时再订阅该会话的对话帧。
            val key = engine.conversationOpen(session, conversationScope())
            engine.conversationCall(
                session,
                key,
                "subscribeSessionsIndex",
                JsonObject(emptyMap()),
            )
            if (taskId != null) {
                engine.conversationCall(
                    session,
                    key,
                    "subscribe",
                    buildJsonObject { put("sessionId", taskId) },
                )
            }
            _state.update { it.copy(bridgeSession = session, conversationKey = key) }
            refreshConversationState()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.toUserMessage()) }
        } finally {
            _state.update { it.copy(loading = false) }
        }
    }

    /** 置顶 / 取消置顶（`zcode-task.setTaskPinned`）。 */
    fun setPinned(pinned: Boolean) = mutateTask("setTaskPinned") { put("pinned", pinned) }

    /** 归档（`zcode-task.archiveTask`）。 */
    fun archiveTask() = mutateTask("archiveTask") {}

    /** 删除（`zcode-task.deleteTask`），同时清掉本地缓存。 */
    fun deleteTask() {
        val task = _state.value.selectedTask
        val workspace = _state.value.selectedWorkspace
        if (task != null && workspace != null) cache?.remove(workspace.key, task.id)
        mutateTask("deleteTask") {}
    }

    /** 重命名（`zcode-task.renameTask`，远程桥回落到基础实现）。 */
    fun renameTask(title: String) = mutateTask("renameTask") { put("title", title) }

    /** 处理一条待审批 / 待输入。 */
    fun resolveInteraction(interactionId: String, optionId: String?) {
        val sessionId = _state.value.selectedTaskId ?: return
        viewModelScope.launch {
            try {
                chatOps.call(
                    "resolveInteraction",
                    buildJsonObject {
                        put("sessionId", sessionId)
                        put("interactionId", interactionId)
                        optionId?.let { put("optionId", it) }
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
     */
    private fun mutateTask(
        method: String,
        extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ) {
        val workspace = _state.value.selectedWorkspace ?: return
        val task = _state.value.selectedTask ?: return
        val bridge = _state.value.bridgeSession ?: engine.bridgeSession.value ?: return
        viewModelScope.launch {
            try {
                val arg = buildJsonObject {
                    put("taskId", task.id)
                    put("workspacePath", workspace.path)
                    workspace.identity?.let { put("workspaceIdentity", it) }
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
                _state.update { it.copy(error = e.toUserMessage()) }
            }
        }
    }

    /** 切换协作模式（`switchCollaborationMode`：build / edit / plan / yolo）。 */
    fun switchMode(mode: String) {
        val sessionId = _state.value.selectedTaskId ?: return
        viewModelScope.launch {
            try {
                chatOps.call(
                    "switchCollaborationMode",
                    buildJsonObject { put("sessionId", sessionId); put("mode", mode) },
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

    /** 切换思考级别（`switchModelConfig`；provider/model 取当前 config）。 */
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
     * 从核心快照刷新对话的派生状态（运行中、待交互、队列、config）。
     *
     * 行窗口由 `rowsUpdated` 增量维护；这里只读「不属于行」的那部分状态。
     */
    private suspend fun refreshConversationState() {
        val snapshot = engine.snapshot() as? JsonObject ?: return
        val sessionId = _state.value.selectedTaskId
        val conversations = snapshot["conversations"] as? JsonObject
        val conversation = sessionId?.let { conversations?.get(it) as? JsonObject }
        val indexes = snapshot["sessionsIndex"] as? JsonArray
        val workspaceId = indexes?.firstOrNull().asObj()?.get("workspaceId").asString()

        _state.update { current ->
            current.copy(
                workspaceId = workspaceId ?: current.workspaceId,
                isRunning = conversation?.get("isRunning").asBool() ?: current.isRunning,
                pendingInteractions = (conversation?.get("pendingInteractions") as? JsonArray)
                    ?: current.pendingInteractions,
                queueItems = (conversation?.get("queueItems") as? JsonArray)
                    ?: (conversation?.get("queue") as? JsonArray)
                    ?: current.queueItems,
                config = (conversation?.get("config") as? JsonObject) ?: current.config,
            )
        }

        // 待授权/待输入：只在有选中任务时上报（通知要能定位回这个任务）。
        val task = _state.value.selectedTask
        val workspace = _state.value.selectedWorkspace
        if (task != null && workspace != null) {
            val pending = (_state.value.pendingInteractions?.isNotEmpty() == true)
            onAttention?.invoke(task.id, task.title ?: task.id, workspace.key, pending)
        }
    }

    /** 处理 `rowsUpdated`：snapshot 覆盖窗口，deltas 增量归约。 */
    private fun applyRowsUpdated(raw: JsonElement) {
        val event = raw as? JsonObject ?: return
        val frame = event["frame"] as? JsonObject ?: return
        val payload = frame["payload"] as? JsonObject ?: return
        when (payload["kind"].asString()) {
            "snapshot" -> {
                val snapshot = payload["snapshot"] as? JsonObject ?: return
                val window = ConversationRows.fromSnapshot(snapshot)
                _state.update {
                    it.copy(
                        rows = window.rows,
                        totalCount = window.totalCount,
                        firstRowId = window.firstRowId,
                        isRunning = snapshot["isRunning"].asBool()
                            ?: (snapshot["canStop"].asBool() == true),
                    )
                }
                viewModelScope.launch { refreshConversationState() }
                schedulePersist()
            }

            "deltas" -> {
                val deltas = payload["deltas"] as? JsonArray ?: return
                val rowOps = JsonArray(
                    deltas.filter { op ->
                        (op as? JsonObject)?.get("op").asString()?.startsWith("row.") == true
                    },
                )
                val hasStateUpdate = deltas.any { op ->
                    (op as? JsonObject)?.get("op").asString() == "state.updated"
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
                if (hasStateUpdate) {
                    viewModelScope.launch { refreshConversationState() }
                }
                schedulePersist()
            }
        }
    }

    /** 防抖写缓存（600ms）；流式帧期间不逐帧落盘。 */
    private fun schedulePersist() {
        val cache = cache ?: return
        val taskId = _state.value.selectedTaskId ?: return
        val workspaceKey = _state.value.selectedWorkspaceKey ?: return
        val rows = _state.value.rows
        if (rows.isEmpty()) return
        persistJob?.cancel()
        persistJob = viewModelScope.launch(Dispatchers.IO) {
            delay(600)
            cache.save(workspaceKey, taskId, rows)
        }
    }

    private fun observe() {        observers += collect(engine.engineState) { s -> _state.update { it.copy(engineState = s) } }
        observers += collect(engine.relayState) { r -> _state.update { it.copy(relayState = r) } }
        observers += collect(engine.relayFailure) { f -> _state.update { it.copy(relayFailure = f) } }
        observers += collect(engine.bridgeSession) { s -> _state.update { it.copy(bridgeSession = s) } }
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
                    _state.update { it.copy(workspaces = snapshot.workspaces) }
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
                eventSeq += 1
                val type = event.event.stringField("type").orEmpty()
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
                when (type) {
                    "rowsUpdated" -> applyRowsUpdated(event.event)
                    "stateChanged", "sessionsIndexUpdated" ->
                        viewModelScope.launch { refreshConversationState() }
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
    }
}

/**
 * `bootstrap` / `list_workspaces` / `workspace_list_updated` 的 `value` → 界面投影。
 *
 * 形状已冻结（对端 3.15.3 的权威 schema，见 `docs/desktop/06-工作区与桥生命周期.md`
 * 与 `docs/desktop/11-host进程与会话任务.md`）：
 * - `workspaces[i]`：8 键，`workspacePath` / `label` / `kind` 必填；
 *   `workspaceIdentity` **有值才出现，永不出现 null**；
 * - `tasks` 是 `workspaces` 的**顶层兄弟数组**，不嵌在工作区对象里；
 *   每个任务自带 `workspacePath` / `workspaceIdentity`，靠它归位。
 *
 * 键规则（四处独立实现一致）：`workspaceIdentity?.trim() || workspacePath`。
 */
internal object WorkspaceProjection {

    /** 一次快照：工作区（已带各自任务）+ 原始顶层任务 + 当前落点。 */
    data class Snapshot(
        val workspaces: List<WorkspaceItem>,
        val tasks: List<TaskItem>,
        val activeWorkspaceKey: String?,
        val activeTaskId: String?,
    )

    fun from(value: JsonElement?): List<WorkspaceItem> =
        snapshotOrNull(value)?.workspaces ?: emptyList()

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
        val tasks = root?.get("tasks")?.asArrayOrNull()
            .orEmpty()
            .mapNotNull { it.asObjectOrNull()?.toTask() }

        // 任务靠自带的 workspaceKey 归到工作区；归不上的仍留在 Snapshot.tasks 里。
        val byKey = tasks.mapNotNull { task -> task.workspaceKey?.let { it to task } }
            .groupBy({ it.first }, { it.second })
        val withTasks = workspaces.map { workspace ->
            workspace.copy(tasks = byKey[workspace.key].orEmpty())
        }

        return Snapshot(
            workspaces = withTasks,
            tasks = tasks,
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

    private fun JsonObject.toTask(): TaskItem? {
        // 协议里 `taskId === sessionId`；缺 id 的记录不可用。
        val id = strField("taskId") ?: strField("sessionId") ?: return null
        val workspacePath = strField("workspacePath")
        val workspaceIdentity = strField("workspaceIdentity")?.trim()?.takeIf { it.isNotEmpty() }
        return TaskItem(
            id = id,
            title = strField("title"),
            displayStatus = strField("displayStatus"),
            hasBackgroundWork = boolField("hasBackgroundWork") ?: false,
            updatedAt = longField("updatedAt"),
            createdAt = longField("createdAt"),
            unreadAt = longField("unreadAt"),
            pinned = boolField("pinned") ?: false,
            archived = boolField("archived") ?: false,
            provider = strField("provider"),
            workspaceKey = workspaceIdentity ?: workspacePath,
            workspaceLabel = strField("workspaceLabel"),
            workspaceKind = strField("workspaceKind"),
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

/** 取字符串字段；空串与非字符串一律当「没有这个字段」。 */
private fun JsonObject.strField(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.boolField(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.longField(key: String): Long? =
    (this[key] as? JsonPrimitive)?.longOrNull

/** 从任意 JSON 值里取字符串字段（对话事件的 `type`）。 */
private fun JsonElement.stringField(key: String): String? =
    (this as? JsonObject)?.strField(key)
