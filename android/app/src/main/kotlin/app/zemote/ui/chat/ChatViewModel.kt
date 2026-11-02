package app.zemote.ui.chat

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
import app.zemote.pairing.PairingUrl
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
                listOf(TaskItem(id = it, workspaceKey = key, hostId = initialHostId))
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

    private var eventSeq = 0L

    /** 缓存写入的防抖任务（流式帧很密，不逐帧落盘）。 */
    private var persistJob: Job? = null

    /** 首屏「首个 turn 不完整」的自动补拉是否已尝试过。 */
    private var backfillTried = false

    /** 静默预取历史的 Job（切任务/重开工作区时取消）。 */
    private var prefetchJob: Job? = null

    /** 分页拉取互斥：静默预取与用户翻页共用，避免并发重复请求。 */
    private val olderMutex = Mutex()

    /** 已经发过 `subscribe` 的会话 id；重试发送时用于补订阅（订阅可能随失败一起丢了）。 */
    private var subscribedSessionId: String? = null

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
        prefetchJob?.cancel()
        prefetchJob = null
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
     * @return `true` = 还有更早的行可以继续拉；`false` = 到头/失败/被取消/任务已切换。
     * @param silent `true` 时失败不写错误横幅（预取用），只记一条日志。
     */
    private suspend fun fetchOlderPage(sessionId: String, silent: Boolean): Boolean =
        olderMutex.withLock {
            val before = _state.value
            if (before.selectedTaskId != sessionId) return false
            val cursor = before.rows.firstOrNull()?.rowId() ?: before.firstRowId ?: return false
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
                return false
            }
            val obj = result as? JsonObject ?: return false
            val atEpoch = obj["atLogEpoch"].asString()
            val after = _state.value
            if (after.selectedTaskId != sessionId) return false
            val epochStale = atEpoch != null && after.logEpoch != null && atEpoch != after.logEpoch
            val oldest = after.rows.firstOrNull()?.rowId()
            val cursorStale = oldest != null && oldest != cursor
            if (epochStale || cursorStale) return false
            val older = (obj["rows"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                .orEmpty()
            val hasMore = obj["hasMore"].asBool()
            if (older.isEmpty()) {
                _state.update { it.copy(historyExhausted = true) }
                return false
            }
            val existing = _state.value.rows
            val ids = existing.mapTo(HashSet()) { it.rowId() }
            val merged = (older.filter { it.rowId() !in ids } + existing)
                .sortedBy { it.rowId() }
            val exhausted = hasMore == false || older.size < OLDER_PAGE_SIZE
            _state.update {
                it.copy(
                    rows = merged,
                    // `firstRowId` 是会话首行（投影头），翻页不能覆盖它。
                    historyExhausted = exhausted,
                )
            }
            recomputeCanLoadOlder()
            schedulePersist()
            !exhausted
        }

    /**
     * 静默预取完整历史：首个快照后后台循环翻页，直到取完/任务切换/达到 [MAX_PREFETCH_PAGES]。
     *
     * 不设置 [ChatUiState.loadingOlder]、不弹错误横幅；与用户翻页共用 [olderMutex]。
     */
    private fun prefetchHistory() {
        prefetchJob?.cancel()
        val sessionId = _state.value.selectedTaskId ?: return
        if (_state.value.historyExhausted) return
        prefetchJob = viewModelScope.launch {
            repeat(MAX_PREFETCH_PAGES) {
                val current = _state.value
                if (current.selectedTaskId != sessionId || current.historyExhausted) return@launch
                if (!fetchOlderPage(sessionId, silent = true)) return@launch
            }
        }
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
                prefetchJob?.cancel()
                prefetchJob = null
                _state.update {
                    it.copy(
                        selectedTaskId = newId,
                        rows = emptyList(),
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

    /** 订阅一个会话的对话帧（`op = "subscribe"`）。 */
    private suspend fun subscribeConversation(sessionId: String) {
        // 桥可能还在建（新建对话与进页并发）：直接向注册表要同一条保活句柄，
        // 不要依赖 `_state.bridgeSession` 是否已经回填——否则会静默不订阅、消息永远不出现。
        val handle = ensureRouteBridge(taskId = null)
        val bridge = handle?.bridge ?: _state.value.bridgeSession ?: return
        val key = handle?.key ?: _state.value.conversationKey ?: return
        engine.conversationCallNoWait(
            bridge,
            key,
            "subscribe",
            buildJsonObject { put("sessionId", sessionId) },
        )
        subscribedSessionId = sessionId
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
        val rawMode = (config?.get("mode") as? JsonPrimitive)?.takeIf { it.isString }?.content
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
            val route = routeWorkspace
            _state.update {
                it.copy(
                    workspaces = if (route == null || projected.any { w -> w.key == route.key }) {
                        projected
                    } else {
                        projected + route
                    },
                )
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
        prefetchJob?.cancel()
        prefetchJob = null
        _state.update {
            it.copy(
                loading = true,
                error = null,
                selectedWorkspaceKey = workspaceKey,
                // 新建会话可能已经把 selectedTaskId 设成服务端返回的 id；进页引导的
                // null 不能把它冲掉（否则重试会重复建会话）。
                selectedTaskId = taskId ?: it.selectedTaskId,
                logEpoch = null,
                historyExhausted = false,
                canLoadOlder = false,
                modelView = null,
                modelViewLoading = false,
                modelViewError = null,
            )
        }
        backfillTried = false
        try {
            // 先铺本地缓存：打开过的对话立刻有内容，等快照到达再合并。
            if (taskId != null && cache != null) {
                val cached = cache.loadConversation(workspaceKey, taskId)
                if (cached != null && cached.rows.isNotEmpty()) {
                    _state.update {
                        it.copy(
                            rows = cached.rows,
                            totalCount = cached.rows.size.toLong(),
                            firstRowId = cached.firstRowId,
                            logEpoch = cached.logEpoch,
                        )
                    }
                    recomputeCanLoadOlder()
                }
            }

            // 有 registry 时桥与 conversation 由它保活复用；否则走旧的一次性路径。
            // 项目树工作区（不在桌面 tab 里）必须带 taskHostId/project，让桌面先
            // `prepareProjectWorkspace`；两者不能同时给（桌面端会拒绝）。
            val handle = ensureRouteBridge(taskId)
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
                engine.conversationCallNoWait(
                    session,
                    key,
                    "subscribe",
                    buildJsonObject { put("sessionId", taskId) },
                )
                subscribedSessionId = taskId
                loadModelView()
            }
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
        prefetchJob?.cancel()
        prefetchJob = null
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

    /** 处理 `rowsUpdated`：snapshot 覆盖窗口，deltas 增量归约。 */
    private fun applyRowsUpdated(raw: JsonElement) {
        val event = raw as? JsonObject ?: return
        // 同一工作区里可能同时订阅了多个会话：事件自带 sessionId，必须按当前任务过滤，
        // 否则另一条对话的行会混进本页并被写进本地缓存。
        val eventSession = event["sessionId"].asString()
        if (eventSession != null && eventSession != _state.value.selectedTaskId) return
        val frame = event["frame"] as? JsonObject ?: return
        val payload = frame["payload"] as? JsonObject ?: return
        when (payload["kind"].asString()) {
            "snapshot" -> {
                val snapshot = payload["snapshot"] as? JsonObject ?: return
                val window = ConversationRows.fromSnapshot(snapshot)
                val epoch = snapshot["logEpoch"].asString()
                _state.update { current ->
                    val epochChanged = epoch != null && current.logEpoch != null &&
                        epoch != current.logEpoch
                    current.copy(
                        // 窗口内以快照为准；窗口之前的历史（缓存/已翻页）保留。
                        rows = mergeSnapshotRows(current.rows, window.rows),
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
                recomputeCanLoadOlder()
                maybeBackfillFirstTurn()
                applyConversationFields(snapshot)
                schedulePersist()
                prefetchHistory()
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

    /** 窗口变化后重算「还能不能往前翻」：最老持有行 > 会话首行。 */
    private fun recomputeCanLoadOlder() {
        _state.update { current ->
            val head = current.firstRowId
            val oldest = current.rows.firstOrNull()?.rowId()
            val can = head != null && oldest != null && oldest > head
            if (can == current.canLoadOlder) current else current.copy(canLoadOlder = can)
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
                        viewModelScope.launch { refreshConversationState() }
                    "sessionsIndexUpdated" ->
                        // workspaceId 与任务列表都只在这个事件里更新。
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

        /** `rowsRange` 每页行数（协议上限 200）。 */
        const val OLDER_PAGE_SIZE: Int = 60

        /** 静默预取一次最多翻多少页（防御服务端 hasMore 异常）。 */
        const val MAX_PREFETCH_PAGES: Int = 200

        private const val TAG = "ChatViewModel"
    }
}

/**
 * 把快照窗口合并进本地已有行。
 *
 * 规则（`docs/CONVERSATION-LIFECYCLE.md` §4）：
 * - 保留 `existing` 中 `rowId` 小于窗口最老行的行（缓存/已翻页的历史）；
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
    val kept = existing.filter { it.rowId() < oldest }
    return (kept + window).sortedBy { it.rowId() }
}

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

/** 取字符串字段；空串与非字符串一律当「没有这个字段」。 */
private fun JsonObject.strField(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

/** 从任意 JSON 值里取字符串字段（对话事件的 `type`）。 */
private fun JsonElement.stringField(key: String): String? =
    (this as? JsonObject)?.strField(key)
