package app.zemote.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zemote.data.DeviceRecord
import app.zemote.engine.EngineEvent
import app.zemote.engine.EngineState
import app.zemote.engine.RelayState
import app.zemote.ui.EnginePort
import app.zemote.ui.chat.TaskItem
import app.zemote.ui.chat.WorkspaceItem
import app.zemote.ui.chat.WorkspaceProjection
import app.zemote.ui.devices.DeviceRepository
import app.zemote.ui.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 「对话」Tab 的状态。
 *
 * 工作区与任务来自 `bootstrap` / `workspace-list-*` 的权威快照；`lastAssistantPreview`
 * 不在快照里，来自 `sessions-index/<workspaceKey>` 订阅（Rust 侧已解析，见
 * `docs/FFI.md` §9 的 `sessionsIndex[i].list`）。
 *
 * 折叠规则（UI-SPEC §1.2）：
 * - 默认全部折叠；
 * - 有未读的工作区自动展开，且只显示未读任务；
 * - 用户点一下横条 → 显示全部任务；再点一下 → 折叠并记住本次「不要自动展开」。
 */
data class HomeUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val engineState: EngineState = EngineState.Idle,
    val relayState: RelayState = RelayState.Idle,
    val relayFailure: EngineEvent.RelayFailure? = null,
    val bridgeDegraded: String? = null,
    val deviceName: String? = null,
    /** 当前电脑 id（进对话页的路由要带）。 */
    val deviceId: String? = null,
    val credentialsMissing: Boolean = false,
    val workspaces: List<WorkspaceItem> = emptyList(),
    /** taskId → lastAssistantPreview。 */
    val previews: Map<String, String> = emptyMap(),
    /** 用户显式展开（显示全部）的工作区键。 */
    val expanded: Set<String> = emptySet(),
    /** 用户显式折叠（即使有未读也不自动展开）的工作区键。 */
    val collapsed: Set<String> = emptySet(),
    /** 正在执行置顶/归档/删除的任务 id（按钮转圈用）。 */
    val busyTasks: Set<String> = emptySet(),
) {

    /** 过滤掉 `workspacePurpose=conversation` 的伪工作区，按最近更新时间倒序。 */
    val visibleWorkspaces: List<WorkspaceItem>
        get() = workspaces
            .filter { it.purpose != "conversation" }
            .sortedByDescending { workspace -> workspace.tasks.maxOfOrNull { it.updatedAt ?: 0L } ?: 0L }

    /** 置顶任务：从工作区抽出，单独成区。 */
    val pinnedTasks: List<TaskItem>
        get() = workspaces
            .flatMap { it.tasks }
            .filter { it.pinned && !it.archived }
            .sortedByDescending { it.updatedAt ?: 0L }

    fun hasUnread(workspace: WorkspaceItem): Boolean =
        workspace.tasks.any { it.unreadAt != null && !it.archived }

    /** 当前是否展开（未读自动展开也算）。 */
    fun isExpanded(workspace: WorkspaceItem): Boolean =
        workspace.key in expanded || (hasUnread(workspace) && workspace.key !in collapsed)

    /**
     * 当前该显示的任务。
     *
     * - 显式展开：全部（不含归档、不含已置顶——置顶已在顶部区）；
     * - 未读自动展开：只显示未读；
     * - 其余：空（折叠）。
     */
    fun tasksOf(workspace: WorkspaceItem): List<TaskItem> {
        val base = workspace.tasks.filter { !it.archived && !it.pinned }
            .sortedByDescending { it.updatedAt ?: 0L }
        return when {
            workspace.key in expanded -> base
            hasUnread(workspace) && workspace.key !in collapsed ->
                base.filter { it.unreadAt != null }
            else -> emptyList()
        }
    }

    fun previewOf(task: TaskItem): String? = previews[task.id]?.takeIf { it.isNotBlank() }

    /** 是否该显示「查看全部」提示：未读自动展开且还有非未读任务。 */
    fun canShowAll(workspace: WorkspaceItem): Boolean =
        workspace.key !in expanded &&
            hasUnread(workspace) &&
            workspace.tasks.any { !it.archived && !it.pinned && it.unreadAt == null }
}

/**
 * 「对话」Tab：当前电脑、工作区/任务列表、预览与任务操作。
 *
 * 一个 ViewModel 同时服务两个界面：主 Tab 与对话页返回后的刷新。任务操作走
 * `zcode-task` 通道（它是 `window-controller.mutateTask` 的代理，见
 * `docs/desktop/13-settings通道与键表.md`）。
 */
class HomeViewModel(
    private val repository: DeviceRepository,
    private val engine: EnginePort,
    private val pairTimeoutMs: Long = 30_000,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var entered = false
    private val observers = mutableListOf<Job>()

    /** 任务 mutation 用的桥（任意一个工作区即可；`zcode-task` 是 host 级通道）。 */
    private var mutationBridge: Long? = null

    /** 预览订阅：workspaceKey → (bridge, conversationKey)。 */
    private val previewSubscriptions = mutableMapOf<String, Pair<Long, String>>()

    init {
        observe()
    }

    fun onEnter() {
        if (entered) return
        entered = true
        viewModelScope.launch { enter() }
    }

    fun refresh() {
        viewModelScope.launch { refreshInternal() }
    }

    /** 横条点击：折叠 ↔ 显示全部。 */
    fun toggleWorkspace(key: String) {
        val current = _state.value
        val workspace = current.workspaces.firstOrNull { it.key == key } ?: return
        if (current.isExpanded(workspace)) {
            _state.update {
                it.copy(expanded = it.expanded - key, collapsed = it.collapsed + key)
            }
        } else {
            _state.update {
                it.copy(expanded = it.expanded + key, collapsed = it.collapsed - key)
            }
            ensurePreviews(workspace)
        }
    }

    /** 进入对话页后调用：把未读清掉（UI-SPEC：停留约 2 秒后）。 */
    fun markRead(task: TaskItem, workspace: WorkspaceItem) {
        viewModelScope.launch {
            mutate(task, workspace, method = "setTaskUnread", extra = { put("unread", false) })
        }
    }

    fun setPinned(task: TaskItem, workspace: WorkspaceItem, pinned: Boolean) {
        viewModelScope.launch {
            mutate(task, workspace, method = "setTaskPinned", extra = { put("pinned", pinned) })
        }
    }

    fun setArchived(task: TaskItem, workspace: WorkspaceItem, archived: Boolean) {
        viewModelScope.launch {
            mutate(
                task,
                workspace,
                method = if (archived) "archiveTask" else "unarchiveTask",
                extra = {},
            )
        }
    }

    fun delete(task: TaskItem, workspace: WorkspaceItem) {
        viewModelScope.launch {
            mutate(task, workspace, method = "deleteTask", extra = {})
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private suspend fun enter() {
        val device = resolveDevice()
        if (device == null) {
            _state.update { it.copy(error = "还没有连接电脑") }
            return
        }
        val url = device.remoteUrl
        if (url == null) {
            _state.update {
                it.copy(
                    credentialsMissing = true,
                    error = "「${device.name}」的凭据不可用，需要重新配对",
                )
            }
            return
        }
        engine.ensureSession(url)
        _state.update { it.copy(deviceId = device.id, deviceName = device.name) }
        refreshInternal()
    }

    private suspend fun refreshInternal() {
        _state.update { it.copy(loading = true, error = null) }
        try {
            engine.waitPaired(pairTimeoutMs)
            val snapshot = engine.bootstrap()
            val workspaces = WorkspaceProjection.from(snapshot)
            _state.update { it.copy(workspaces = workspaces) }
            ensureMutationBridge(workspaces)
            // 已经展开（或有未读）的工作区顺手订阅预览。
            workspaces
                .filter { it.purpose != "conversation" && it.key in _state.value.expanded }
                .forEach { ensurePreviews(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.toUserMessage()) }
        } finally {
            _state.update { it.copy(loading = false) }
        }
    }

    private suspend fun resolveDevice(): DeviceRecord? {
        val devices = repository.devices.first()
        return repository.currentDevice.first() ?: devices.firstOrNull()
    }

    /** 任务 mutation 需要一个桥；挑第一个非伪工作区。 */
    private suspend fun ensureMutationBridge(workspaces: List<WorkspaceItem>) {
        if (mutationBridge != null) return
        val workspace = workspaces.firstOrNull { it.purpose != "conversation" } ?: return
        try {
            val session = engine.openBridge(workspace.key)
            engine.waitHealthy(session)
            mutationBridge = session
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.toUserMessage()) }
        }
    }

    /**
     * 懒加载某工作区的 `lastAssistantPreview`。
     *
     * 一条工作区 = 一个桥 + 一个 conversation + 一次 `subscribeSessionsIndex`。
     * 只在展开时做，折叠不付代价；同一工作区只做一次。
     */
    private fun ensurePreviews(workspace: WorkspaceItem) {
        if (workspace.key in previewSubscriptions) return
        viewModelScope.launch {
            try {
                val bridge = engine.openBridge(workspace.key)
                engine.waitHealthy(bridge)
                val key = engine.conversationOpen(
                    bridge,
                    buildJsonObject {
                        put("workspacePath", workspace.path)
                        workspace.identity?.let { put("workspaceIdentity", it) }
                    },
                )
                previewSubscriptions[workspace.key] = bridge to key
                engine.conversationCall(bridge, key, "subscribeSessionsIndex", JsonObject(emptyMap()))
                mergeSessionsIndex()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 预览失败不影响主列表；只记一次可读错误。
                _state.update { it.copy(error = "预览加载失败：${e.toUserMessage()}") }
            }
        }
    }

    /** 从核心快照的 `sessionsIndex[i].list` 合并 `lastAssistantPreview`。 */
    private suspend fun mergeSessionsIndex() {
        val snapshot = engine.snapshot().asObject() ?: return
        val entries = snapshot["sessionsIndex"]?.asArray() ?: return
        val merged = HashMap<String, String>()
        for (entry in entries) {
            val list = entry.asObject()?.get("list")?.asArray() ?: continue
            for (item in list) {
                val obj = item.asObject() ?: continue
                val id = obj["sessionId"].asString() ?: obj["taskId"].asString() ?: continue
                val preview = obj["lastAssistantPreview"].asString() ?: continue
                if (preview.isNotBlank()) merged[id] = preview
            }
        }
        if (merged.isEmpty()) return
        _state.update { it.copy(previews = it.previews + merged) }
    }

    /**
     * 调 `zcode-task` 的任务 mutation。
     *
     * 参数就是任务摘要对象本身（代理内部据此解析 address）；`extra` 补上这次要改的字段。
     */
    private suspend fun mutate(
        task: TaskItem,
        workspace: WorkspaceItem,
        method: String,
        extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ) {
        val bridge = mutationBridge ?: run {
            _state.update { it.copy(error = "还没有可用的工作区连接") }
            return
        }
        _state.update { it.copy(busyTasks = it.busyTasks + task.id, error = null) }
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
                task.provider?.let { put("provider", it) }
                extra()
            }
            engine.channelCall(
                session = bridge,
                channel = "zcode-task",
                method = method,
                args = JsonArray(listOf(arg)),
            )
            // 通道应答只是"已提交"；真正的列表变化由 workspace-list 推送。
            _state.update { it.copy(busyTasks = it.busyTasks - task.id) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update {
                it.copy(busyTasks = it.busyTasks - task.id, error = e.toUserMessage())
            }
        }
    }

    private fun unread(value: Boolean): kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {
        put("unread", value)
    }

    private fun observe() {
        observers += viewModelScope.launch {
            engine.engineState.collect { engineState ->
                _state.update { it.copy(engineState = engineState) }
            }
        }
        observers += viewModelScope.launch {
            engine.relayState.collect { relayState ->
                _state.update { it.copy(relayState = relayState) }
            }
        }
        observers += viewModelScope.launch {
            engine.relayFailure.collect { failure ->
                _state.update { it.copy(relayFailure = failure) }
            }
        }
        observers += viewModelScope.launch {
            engine.events.collect { event ->
                when (event) {
                    is EngineEvent.WorkspaceListUpdated -> {
                        WorkspaceProjection.snapshotOrNull(event.value)?.let { snapshot ->
                            _state.update { it.copy(workspaces = snapshot.workspaces) }
                        }
                    }

                    is EngineEvent.ConversationEvent -> {
                        if (event.event.asObject()?.get("type").asString() == "sessionsIndexUpdated") {
                            viewModelScope.launch { mergeSessionsIndex() }
                        }
                    }

                    is EngineEvent.BridgeDegraded ->
                        _state.update { it.copy(bridgeDegraded = event.degraded) }

                    is EngineEvent.BridgeRecovered ->
                        _state.update { it.copy(bridgeDegraded = null) }

                    is EngineEvent.AppError -> {
                        val text = when (event.reason) {
                            "desktop-disconnected" -> "桌面端已断开远控"
                            "session-conflict" -> "另一台手机接管了远控"
                            "workspace-closed" -> "工作区已关闭"
                            else -> event.error ?: "桌面端报错"
                        }
                        _state.update { it.copy(error = text) }
                    }

                    else -> Unit
                }
            }
        }
        observers += viewModelScope.launch {
            repository.devices.collect { devices ->
                val device = devices.firstOrNull()
                _state.update {
                    it.copy(
                        deviceId = device?.id,
                        deviceName = device?.name,
                        credentialsMissing = device?.needsRepair == true,
                    )
                }
            }
        }
    }

    override fun onCleared() {
        observers.forEach { it.cancel() }
        super.onCleared()
    }
}

// ---------------------------------------------------------------------------
// JSON 小工具（只在本文件用；与 ChatViewModel 的私有工具同语义）
// ---------------------------------------------------------------------------

private fun JsonElement?.asObject(): JsonObject? = (this as? JsonObject)

private fun JsonElement?.asArray(): JsonArray? = (this as? JsonArray)

private fun JsonElement?.asString(): String? = when (this) {
    null -> null
    is JsonPrimitive -> contentOrNull
    else -> null
}
