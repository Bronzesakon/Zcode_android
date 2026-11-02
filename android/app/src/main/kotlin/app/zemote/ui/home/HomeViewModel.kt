package app.zemote.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zemote.auth.RemoteTokenException
import app.zemote.data.AuthStore
import app.zemote.data.DeviceRecord
import app.zemote.pairing.PairingUrl
import app.zemote.engine.EngineEvent
import app.zemote.engine.EngineState
import app.zemote.engine.RelayState
import app.zemote.ui.EnginePort
import app.zemote.ui.chat.ConversationRegistry
import app.zemote.ui.chat.SessionsIndexProjection
import app.zemote.ui.chat.TaskItem
import app.zemote.ui.chat.WorkspaceItem
import app.zemote.ui.chat.WorkspaceProjection
import app.zemote.ui.devices.DeviceRepository
import app.zemote.ui.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
 * 3.17.1 首页是项目树（项目 → 任务），不再有公告卡/工作区横条：
 * - 置顶任务统一抽到最上方「已置顶」区，项目内不重复；
 * - 分组视图按项目分组，列表视图按更新时间平铺；
 * - 未读点与任务数都取自 `readTree` 基底（`pinned` / `archived` / `unreadAt`）。
 *
 * 折叠规则沿用旧口径：默认折叠；有未读的项目自动展开且只显示未读；
 * 用户点一下项目行 → 显示全部任务，再点一下 → 折叠并记住「不要自动展开」。
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
    /** 当前设备需要 OAuth 登录（3.16.1 新链接 / token 失效）。界面据此打开登录页。 */
    val needsLogin: Boolean = false,
    val workspaces: List<WorkspaceItem> = emptyList(),
    /** taskId → lastAssistantPreview。 */
    val previews: Map<String, String> = emptyMap(),
    /** 用户显式展开（显示全部）的工作区键。 */
    val expanded: Set<String> = emptySet(),
    /** 用户显式折叠（即使有未读也不自动展开）的工作区键。 */
    val collapsed: Set<String> = emptySet(),
    /** 正在执行置顶/归档/删除的任务 id（菜单禁用）。 */
    val busyTasks: Set<String> = emptySet(),
) {

    /** 过滤掉 `workspacePurpose=conversation` 的伪工作区，按最近更新时间倒序。 */
    val visibleWorkspaces: List<WorkspaceItem>
        get() = workspaces
            .filter { it.purpose != "conversation" }
            .sortedByDescending { workspace -> workspace.tasks.maxOfOrNull { it.updatedAt ?: 0L } ?: 0L }

    /** 项目树（分组视图）：每个项目一行，展开时带任务。 */
    val projectSections: List<ProjectSection>
        get() = visibleWorkspaces.map { workspace ->
            ProjectSection(
                workspace = workspace,
                expanded = isExpanded(workspace),
                tasks = tasksOf(workspace),
                totalTasks = workspace.tasks.count { !it.archived },
                unreadCount = workspace.tasks.count { it.unreadAt != null && !it.archived },
                hasUnread = hasUnread(workspace),
                offline = workspace.isOffline(),
                canShowAll = canShowAll(workspace),
            )
        }

    /** 置顶任务：从项目抽出，列表最上方独立成区；带所属项目供导航回调。 */
    val pinnedEntries: List<TaskEntry>
        get() = visibleWorkspaces
            .flatMap { workspace ->
                workspace.tasks
                    .filter { it.pinned && !it.archived }
                    .map { TaskEntry(workspace, it) }
            }
            .sortedByDescending { it.task.updatedAt ?: 0L }

    /**
     * 列表视图：非置顶、非归档任务按更新时间倒序。
     *
     * 置顶任务只在「已置顶」区出现，平铺列表里不重复。
     */
    val timelineEntries: List<TaskEntry>
        get() = visibleWorkspaces
            .flatMap { workspace ->
                workspace.tasks
                    .filter { !it.archived && !it.pinned }
                    .map { TaskEntry(workspace, it) }
            }
            .sortedByDescending { it.task.updatedAt ?: 0L }

    /** 列表视图的日期分节：今天 / 昨天 / 更早（保持更新时间倒序）。 */
    fun timelineDays(now: Long = System.currentTimeMillis()): List<TimelineDay> {
        val entries = timelineEntries
        if (entries.isEmpty()) return emptyList()
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return entries
            .groupBy { entry ->
                val ts = entry.task.updatedAt ?: 0L
                if (ts <= 0L) {
                    "更早"
                } else {
                    val date = java.time.Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()
                    when (date) {
                        today -> "今天"
                        today.minusDays(1) -> "昨天"
                        else -> "更早"
                    }
                }
            }
            .map { (label, dayEntries) -> TimelineDay(label, dayEntries) }
    }

    /** 全部未读任务数（不含归档；跨项目，含置顶）。 */
    val unreadCount: Int
        get() = visibleWorkspaces.sumOf { workspace ->
            workspace.tasks.count { it.unreadAt != null && !it.archived }
        }

    /** 全部任务数（不含归档；含置顶）。 */
    val totalTaskCount: Int
        get() = visibleWorkspaces.sumOf { workspace -> workspace.tasks.count { !it.archived } }

    /** 兼容旧入口：置顶任务本身。 */
    val pinnedTasks: List<TaskItem>
        get() = pinnedEntries.map { it.task }

    fun hasUnread(workspace: WorkspaceItem): Boolean =
        workspace.tasks.any { it.unreadAt != null && !it.archived }

    /**
     * 是否该自动展开：只有**非置顶**的未读任务触发。
     *
     * 置顶任务已经在最上方的「已置顶」区单独显示，不该再让原项目展开一次。
     */
    private fun hasUnpinnedUnread(workspace: WorkspaceItem): Boolean =
        workspace.tasks.any { it.unreadAt != null && !it.archived && !it.pinned }

    /** 当前是否展开（未读自动展开也算）。 */
    fun isExpanded(workspace: WorkspaceItem): Boolean =
        workspace.key in expanded || (hasUnpinnedUnread(workspace) && workspace.key !in collapsed)

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
            hasUnpinnedUnread(workspace) && workspace.key !in collapsed ->
                base.filter { it.unreadAt != null }
            else -> emptyList()
        }
    }

    fun previewOf(task: TaskItem): String? = previews[task.id]?.takeIf { it.isNotBlank() }

    /** 是否该显示「查看全部」提示：未读自动展开且还有非未读任务。 */
    fun canShowAll(workspace: WorkspaceItem): Boolean =
        workspace.key !in expanded &&
            hasUnpinnedUnread(workspace) &&
            workspace.tasks.any { !it.archived && !it.pinned && it.unreadAt == null }
}

/** 项目树里的一行：项目 + 展开时实际显示的任务 + 网页口径的计数。 */
data class ProjectSection(
    val workspace: WorkspaceItem,
    val expanded: Boolean,
    /** 展开时显示的任务（已排除归档与置顶）。 */
    val tasks: List<TaskItem>,
    /** 项目任务总数（不含归档、含置顶）。 */
    val totalTasks: Int,
    /** 项目未读数（不含归档、含置顶）。 */
    val unreadCount: Int,
    val hasUnread: Boolean,
    val offline: Boolean,
    val canShowAll: Boolean,
)

/** 任务 + 所属项目：置顶区与列表视图都要带着项目信息回调导航。 */
data class TaskEntry(val workspace: WorkspaceItem, val task: TaskItem)

/** 列表视图的日期分节。 */
data class TimelineDay(val label: String, val entries: List<TaskEntry>)

/** `connectionState` 里表示不可用的取值（枚举未冻结，按小写匹配）。 */
private val OFFLINE_CONNECTION_STATES = setOf("offline", "disconnected", "error", "closed", "kicked")

/** 项目行是否该显示「离线」：有连接错误，或连接态落在不可用集合。 */
internal fun WorkspaceItem.isOffline(): Boolean {
    if (!lastError.isNullOrBlank()) return true
    val state = connectionState?.lowercase() ?: return false
    return state in OFFLINE_CONNECTION_STATES
}

/**
 * 「对话」Tab：当前电脑、工作区/任务列表、预览与任务操作。
 *
 * 一个 ViewModel 同时服务两个界面：主 Tab 与对话页返回后的刷新。任务操作走
 * `zcode-task` 通道（它是 `window-controller.mutateTask` 的代理，见
 * `archive/pre-3.16.1-auth/docs/desktop/13-settings通道与键表.md`）。
 */
class HomeViewModel(
    private val repository: DeviceRepository,
    private val engine: EnginePort,
    private val authStore: AuthStore,
    /**
     * 进程级桥/对话注册表：与对话页共用同一条工作区桥，避免同一工作区开两条桥
     * 导致 `helloConversationV4` 握手互相抢应答。
     */
    private val registry: ConversationRegistry? = null,
    /** 对话行缓存：任务删除时一并清掉（与对话页共用同一个实例）。 */
    private val cache: app.zemote.data.ChatCache? = null,
    // 比 relay 自己的 waiting 超时（30 s）多留 5 s：让核心先把
    // `invalid-mobile-connection` / `auth-failed` 这类**具体原因**报上来，
    // 而不是被宿主兜底超时抢先盖成一句笼统的「pairing timeout」。
    private val pairTimeoutMs: Long = 35_000,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var entering = false
    private var refreshing = false
    private var lastEnteredDeviceId: String? = null
    private var observedSessionSerial = 0L
    private val observers = mutableListOf<Job>()

    /** 任务 mutation 用的桥（任意一个工作区即可；`zcode-task` 是 host 级通道）。 */
    private var mutationBridge: Long? = null

    /** 预览订阅：workspaceKey → (bridge, conversationKey)。 */
    private val previewSubscriptions = mutableMapOf<String, Pair<Long, String>>()

    /** `project.readTree` 每个引擎会话只尝试一次（它是全量项目树）。 */
    private var projectTreeAttempted = false

    /** 项目树里的全部工作区；`workspace-list-updated` 只带已挂载工作区，不能把它们冲掉。 */
    private var projectTreeWorkspaces = emptyList<WorkspaceItem>()

    /**
     * 预览握手串行化。
     *
     * 对端的 `helloConversationV4` 是**单连接**语义：同一 host 上并发发两个 hello 只会回一个，
     * 另一个的 `initializeConversationV4` 永远等不到应答，随后所有订阅都挂住。所以每个工作区
     * 必须「开桥 → 握手 → 订阅 ack」完成后再处理下一个。
     */
    private val previewSetup = Mutex()

    init {
        observe()
    }

    /**
     * 进入首页：建会话并拉快照。
     *
     * **不是一次性**：扫码配对完成会回到同一个 ViewModel；如果这里用 `entered`
     * 锁死，首页会停在配对前的「还没有连接电脑」，只能手动重试。并发调用由
     * [entering] 去重。
     */
    fun onEnter() {
        if (entering) return
        entering = true
        viewModelScope.launch {
            try {
                enter()
            } finally {
                entering = false
            }
        }
    }

    /**
     * 从登录页返回：登录成功就重新建会话并拉快照；取消则只收起登录引导。
     *
     * 首页的 ViewModel 在登录页压栈期间仍然存活，所以这里不需要额外的结果通道。
     */
    fun onLoginReturn() {
        viewModelScope.launch {
            val token = authStore.token()
            _state.update { it.copy(needsLogin = false) }
            if (token != null) onEnter()
        }
    }

    /** 用户关掉了登录页。 */
    fun cancelLogin() {
        _state.update { it.copy(needsLogin = false) }
    }

    fun refresh() {
        viewModelScope.launch {
            // relay 的 `error` / `kicked` / `closed` 是终态，不会自己重连；
            // 只重新 wait_paired 会一直等到超时。这里重建会话（顺带换一张新的
            // terminal token），让「重试」真的重试。
            val relay = _state.value.relayState
            if (relay == RelayState.Error || relay == RelayState.Kicked || relay == RelayState.Closed) {
                engine.disconnect()
                onEnter()
            } else {
                refreshInternal(force = true)
            }
        }
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
        if (task.unreadAt == null) return
        viewModelScope.launch {
            mutate(task, workspace, method = "setTaskUnread", extra = {
                put("unread", false)
                // 网页用 expectedUnreadAt 做并发保护：期间来了新消息就不清未读。
                put("expectedUnreadAt", task.unreadAt)
            }, updateTasks = { tasks ->
                // 本地必须允许清空：旧值优先会让未读点永远不消失。
                tasks.map { if (it.id == task.id) it.copy(unreadAt = null) else it }
            })
        }
    }

    fun setPinned(task: TaskItem, workspace: WorkspaceItem, pinned: Boolean) {
        viewModelScope.launch {
            mutate(
                task,
                workspace,
                method = "setTaskPinned",
                extra = { put("pinned", pinned) },
                updateTasks = { tasks ->
                    tasks.map { if (it.id == task.id) it.copy(pinned = pinned) else it }
                },
            )
        }
    }

    fun setArchived(task: TaskItem, workspace: WorkspaceItem, archived: Boolean) {
        viewModelScope.launch {
            mutate(
                task,
                workspace,
                method = if (archived) "archiveTask" else "unarchiveTask",
                extra = {},
                updateTasks = { tasks ->
                    tasks.map { if (it.id == task.id) it.copy(archived = archived) else it }
                },
            )
        }
    }

    fun delete(task: TaskItem, workspace: WorkspaceItem) {
        // 对话删除即删缓存（用户明确要求）；缓存键与对话页一致，用任务自己的 key。
        cache?.remove(task.workspaceKey ?: workspace.key, task.id)
        viewModelScope.launch {
            mutate(
                task,
                workspace,
                method = "deleteTask",
                extra = {},
                updateTasks = { tasks -> tasks.filterNot { it.id == task.id } },
            )
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
        val token = authStore.token()
        // 3.16.1 新链接没有 hash：必须先登录拿 JWT，否则 relay 一定会拒绝。
        if (token == null && !PairingUrl.hasPassHash(url)) {
            _state.update {
                it.copy(
                    deviceId = device.id,
                    deviceName = device.name,
                    needsLogin = true,
                    error = "需要登录后继续远控",
                )
            }
            return
        }
        try {
            engine.ensureSession(url, token)
        } catch (e: RemoteTokenException) {
            // 换 terminal token 失败：401 说明 OAuth 登录态也失效了，清掉并引导重登；
            // 其余（网络/5xx）只显示错误，保留登录态让用户重试。
            if (e.authRequired) {
                authStore.clear()
                _state.update {
                    it.copy(
                        deviceId = device.id,
                        deviceName = device.name,
                        needsLogin = true,
                        error = "登录已失效，请重新登录",
                    )
                }
            } else {
                _state.update { it.copy(error = e.toUserMessage()) }
            }
            return
        }
        _state.update {
            it.copy(deviceId = device.id, deviceName = device.name, needsLogin = false)
        }
        // 从对话页返回会重新触发 onEnter：此时强制重拉 readTree，让新会话/置顶
        // 立即出现在首页（注册表复用已有桥，代价只有一次 readTree）。
        val reenter = lastEnteredDeviceId == device.id
        lastEnteredDeviceId = device.id
        refreshInternal(force = reenter)
    }

    private suspend fun refreshInternal(force: Boolean = false) {
        if (refreshing) return
        refreshing = true
        // 只有引擎真的重建（换 URL/JWT/手动重连）才丢弃桥/对话缓存；同一个引擎重拉
        // bootstrap 时任务仍由已有订阅维护，不能被空 tasks 覆盖。
        val serialChanged = engine.sessionSerial != observedSessionSerial
        if (serialChanged) {
            observedSessionSerial = engine.sessionSerial
            mutationBridge = null
            previewSubscriptions.clear()
            projectTreeAttempted = false
            projectTreeWorkspaces = emptyList()
        } else if (force) {
            // 用户下拉刷新：重订阅 + 重拉 readTree。注册表会复用已有桥，代价很小。
            mutationBridge = null
            previewSubscriptions.clear()
            projectTreeAttempted = false
        }
        _state.update { it.copy(loading = true, error = null) }
        try {
            engine.waitPaired(pairTimeoutMs)
            val snapshot = engine.bootstrap()
            val workspaces = WorkspaceProjection.from(snapshot)
            _state.update { current ->
                current.copy(
                    workspaces = if (serialChanged) {
                        workspaces
                    } else {
                        mergeWorkspaceSnapshot(current.workspaces, workspaces)
                    },
                )
            }
            // 3.16.1 的任务来自 sessionsIndex，不是 bootstrap；每个工作区都要订阅一次，
            // 否则折叠行没有最近活动时间、展开后也没有任务。
            workspaces
                .filter { it.purpose != "conversation" }
                .forEach { ensurePreviews(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 有 relay 失败原因就优先显示它：`pairing timeout` 只是宿主兜底，
            // `invalid-mobile-connection` / `auth-failed` 才说明卡在哪一步。
            val failureText = _state.value.relayFailure?.let(::relayFailureText)
            _state.update { it.copy(error = failureText ?: e.toUserMessage()) }
        } finally {
            refreshing = false
            _state.update { it.copy(loading = false) }
        }
    }

    /**
     * 把 relay 失败原因翻成用户能行动的一句话。
     *
     * 文案只解释「下一步该做什么」，不复述服务端错误码；原始 `reason`/`message` 仍保留在
     * [HomeUiState.relayFailure] 与设备记录里，供诊断页/日志使用。
     */
    private fun relayFailureText(failure: EngineEvent.RelayFailure): String {
        val detail = failure.message?.takeIf { it.isNotBlank() }
        return when (failure.reason) {
            "auth-failed", "auth-required" -> "登录已失效，请重新登录"
            "invalid-mobile-connection" ->
                "桌面端没有接受这次连接：请确认电脑端已打开手机远控，" +
                    "且手机与电脑登录同一平台（Z.ai 或 BigModel）"
            "wrong-param" -> "配对参数不被接受，请重新扫码"
            "relay-unavailable" ->
                "中继服务不可用${detail?.let { "：$it" }.orEmpty()}"
            else -> "连接失败：${failure.reason}${detail?.let { "（$it）" }.orEmpty()}"
        }
    }

    private suspend fun resolveDevice(): DeviceRecord? {
        val devices = repository.devices.first()
        return repository.currentDevice.first() ?: devices.firstOrNull()
    }

    /**
     * 为工作区订阅 `sessionsIndex`（任务列表 + `lastAssistantPreview`）。
     *
     * 一条工作区 = 一个桥 + 一个 conversation + 一次 `subscribeSessionsIndex`。
     * 同一工作区只做一次；结果由 [mergeSessionsIndex] 合并进工作区。
     */
    private fun ensurePreviews(workspace: WorkspaceItem) {
        if (workspace.key in previewSubscriptions) return
        viewModelScope.launch {
            previewSetup.withLock {
                if (workspace.key in previewSubscriptions) return@withLock
                try {
                    // 非当前 tab 的项目不在这里订阅：openBridge 必须带 project/taskHostId，
                    // 而「展开一下」就 prepare 一个项目草稿会在桌面端凭空多开工作区。
                    // 这些工作区的任务由 readTree 提供；进入对话页时再按任务精确 prepare。
                    if (!workspace.bridgeable) return@withLock
                    val scope = buildJsonObject {
                        put("workspacePath", workspace.path)
                        workspace.identity?.let { put("workspaceIdentity", it) }
                    }
                    // 有注册表时与对话页共用同一条桥/对话；它内部串行化「开桥 → 握手 →
                    // 订阅 ack」，避免同一工作区两条桥抢 `helloConversationV4`。
                    val handle = registry?.ensure(
                        workspace.key,
                        taskId = null,
                        conversationScope = scope,
                    )
                    val bridge: Long
                    val key: String
                    if (handle != null) {
                        bridge = handle.bridge
                        key = handle.key
                    } else {
                        bridge = engine.openBridge(workspace.key)
                        engine.waitHealthy(bridge)
                        // 握手完成即返回；下面的 readTree 只依赖握手，不依赖 sessions-index 订阅。
                        key = engine.conversationOpen(bridge, scope)
                    }
                    // 任务 mutation 复用这条桥：同一工作区开两条桥会让对端把旧桥降级，
                    // 后续握手/订阅永远等不到应答。
                    if (mutationBridge == null && workspace.purpose != "conversation") {
                        mutationBridge = bridge
                    }
                    // `project` / `zcode-task` 只要求对话握手已完成。先拉 readTree 再订阅：
                    // 订阅超时（existing-only / 无 runtime）只影响任务活动字段，不能让
                    // 「工作区/项目列表」一起消失。
                    if (!projectTreeAttempted) {
                        // 只有真的拿到合法响应才算完成；失败留待下一轮 refresh 重试。
                        projectTreeAttempted = loadProjectTree(bridge)
                    }
                    if (handle == null) {
                        engine.conversationCall(
                            bridge,
                            key,
                            "subscribeSessionsIndex",
                            buildJsonObject { put("timeoutSecs", 60) },
                        )
                    }
                    // 订阅成功后才登记，失败时下一轮 refresh 还能重试。
                    previewSubscriptions[workspace.key] = bridge to key
                    // pinned/archived/unreadAt 全部来自 readTree 的 tasks，不再二次计算
                    // （listPinnedTasks/listArchivedTasks 只是它的子集，失败还会把标记清零）。
                    mergeSessionsIndex()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 预览失败不影响主列表；只记一次可读错误。
                    _state.update { it.copy(error = "预览加载失败：${e.toUserMessage()}") }
                }
            }
        }
    }

    /**
     * 合并一次工作区快照：bootstrap/workspace-list 只给已挂载工作区，
     * `readTree` 补出来的项目树工作区不能被冲掉。
     */
    private fun mergeWorkspaceSnapshot(
        current: List<WorkspaceItem>,
        updated: List<WorkspaceItem>,
    ): List<WorkspaceItem> {
        val merged = WorkspaceProjection.merge(current, updated)
        val currentByKey = current.associateBy { it.key }
        val extras = projectTreeWorkspaces
            .filter { project -> merged.none { it.key == project.key } }
            .map { project ->
                val old = currentByKey[project.key]
                if (old == null) project else project.copy(tasks = old.tasks)
            }
        return merged + extras
    }

    /**
     * `project.readTree`：拿**全部项目 + 全部任务**。
     *
     * bootstrap 的 `workspaces` 只是当前窗口已经挂载（`workspace-bridge-open` 过）的工作区，
     * 桌面侧栏里的其他项目不会出现在里面。网页端自己就是桌面渲染器，所以它不需要这步；
     * 独立 App 必须用 `readTree` 补全项目列表与任务基底。
     *
     * 返回 `true` 表示这次拿到了合法响应（下次会话前不再重复拉）。
     */
    /**
     * 远端 workspaceIdentity：`hostId + ":" + normalizePath(path)`。
     *
     * 桌面端 `_se/Wo(hostId, path)` 就是在 hostId 后拼路径段；`hostId` 本身是 identity
     * 去掉末尾 `/<path>` 的部分（`remote:server:<id>` / `remote:ssh:<host>:<port>:<user>` …）。
     * 路径归一化必须与桌面 `gy` 一致：反斜杠转 `/`、折叠 `//`、去首尾 `/`、再补一个 `/`。
     */
    private fun remoteIdentityOf(hostId: String, path: String): String? {
        if (!hostId.startsWith("remote:")) return null
        val normalized = "/" + path.replace('\\', '/').replace(Regex("/+"), "/").trim('/')
        return "$hostId:$normalized"
    }

    /** `project.readTree` 单次调用；`hostId=null` = 本机。参数必须是单个对象（见 `channel_call`）。 */
    private suspend fun readTree(bridge: Long, hostId: String?): JsonObject? {
        val arg = buildJsonObject {
            put("includeDeleted", false)
            put("includeDeletedTasks", true)
            hostId?.let { put("hostId", it) }
        }
        return runCatching {
            engine.channelCall(
                bridge,
                "project",
                "readTree",
                JsonArray(listOf(arg)),
                timeoutMs = 12_000,
            )
        }.getOrNull().asObject()
    }

    private suspend fun loadProjectTree(bridge: Long): Boolean {
        // 本地 + 每个在线远端 host 各一次 readTree（网页 Mbt 的完整读法）。
        val local = readTree(bridge, hostId = null) ?: return false
        val responses = mutableListOf(local)
        val directory = runCatching {
            engine.channelCall(
                bridge,
                "remote-projects",
                "readDirectory",
                JsonArray(emptyList()),
                timeoutMs = 8_000,
            )
        }.getOrNull().asObject()
        val hostIds = directory?.get("hosts").asArray().orEmpty().mapNotNull { host ->
            val obj = host.asObject() ?: return@mapNotNull null
            val hostId = obj["id"].asString() ?: return@mapNotNull null
            val status = obj["connectionStatus"].asString()
            // 未给状态时按在线处理；显式非 online 的 host 跳过（连不上只会白等 12s）。
            if (status != null && status != "online") return@mapNotNull null
            hostId
        }
        // 与网页 `Promise.allSettled` 一致：各 host 并发拉，单个失败不影响其余。
        responses += coroutineScope {
            hostIds.map { hostId -> async { readTree(bridge, hostId = hostId) } }.awaitAll()
                .filterNotNull()
        }
        val projects = responses.flatMap { it["projects"].asArray().orEmpty() }
        val treeTasks = responses.flatMap { it["tasks"].asArray().orEmpty() }

        // 远端项目的 `roots[].rootPath` 只是路径；identity 在任务行上（`workspaceIdentity`）。
        // 同一 (hostId, path) 的所有任务 identity 一致；不同 host 可能有相同路径，必须带 hostId。
        val identityByPath = HashMap<String, String>()
        for (row in treeTasks) {
            val obj = row.asObject() ?: continue
            val path = obj["workspacePath"].asString() ?: continue
            val hostId = obj["hostId"].asString() ?: "local"
            val identity = obj["workspaceIdentity"].asString()?.trim()?.takeIf { it.isNotEmpty() }
            if (identity != null) identityByPath.putIfAbsent("$hostId\u0000$path", identity)
        }

        val built = mutableListOf<WorkspaceItem>()
        // (hostId, projectId) → 项目主根对应的 workspace key；多根项目的任务按 projectId 归组。
        val projectKeyById = HashMap<String, String>()
        for (project in projects) {
            val obj = project.asObject() ?: continue
            if ((obj["deleted"] as? JsonPrimitive)?.booleanOrNull == true) continue
            val roots = obj["roots"].asArray().orEmpty()
            val rootObject = roots.firstOrNull {
                (it.asObject()?.get("isPrimary") as? JsonPrimitive)?.booleanOrNull == true
            }?.asObject() ?: roots.firstOrNull()?.asObject()
            val path = rootObject?.get("rootPath").asString() ?: continue
            val label = obj["name"].asString()
                ?: path.substringAfterLast('/').substringAfterLast('\\').ifEmpty { path }
            val hostId = obj["hostId"].asString()
            // 远端项目的 identity 在任务行上；没有任何任务时用 `hostId + ":" + gy(path)` 推导
            // （与桌面 `_se/Wo(hostId, path)` 同式；hostId 就是 identity 去掉路径段）。
            val identity = identityByPath["${hostId ?: "local"}\u0000$path"]
                ?: hostId?.takeIf { it != "local" }?.let { remoteIdentityOf(it, path) }
            val key = identity ?: path
            obj["id"].asString()?.let { projectId ->
                projectKeyById["${hostId ?: "local"}\u0000$projectId"] = key
            }
            built += WorkspaceItem(
                key = key,
                path = path,
                identity = identity,
                label = label,
                kind = if (hostId == null || hostId == "local") "local" else "remote",
                purpose = "project",
                projectId = obj["id"].asString(),
                hostId = hostId,
                bridgeable = false,
            )
        }

        val tasksByKey = treeTasks.mapNotNull { row ->
            val obj = row.asObject() ?: return@mapNotNull null
            val id = obj["taskId"].asString() ?: obj["sessionId"].asString()
                ?: return@mapNotNull null
            val path = obj["workspacePath"].asString() ?: return@mapNotNull null
            val identity = obj["workspaceIdentity"].asString()?.trim()?.takeIf { it.isNotEmpty() }
            val key = identity ?: path
            val hostId = obj["hostId"].asString()
            val projectId = obj["projectId"].asString()
            // 任务按 projectId 归到项目主根；没有 projectId 的 legacy 任务按自己的 key 归组。
            val groupKey = projectId
                ?.let { projectKeyById["${hostId ?: "local"}\u0000$it"] }
                ?: key
            TaskItem(
                id = id,
                title = obj["title"].asString(),
                displayStatus = obj["status"].asString() ?: obj["displayStatus"].asString(),
                updatedAt = (obj["updatedAt"] as? JsonPrimitive)?.longOrNull,
                createdAt = (obj["createdAt"] as? JsonPrimitive)?.longOrNull,
                unreadAt = (obj["unreadAt"] as? JsonPrimitive)?.longOrNull,
                pinned = (obj["pinned"] as? JsonPrimitive)?.booleanOrNull == true,
                archived = (obj["archived"] as? JsonPrimitive)?.booleanOrNull == true,
                provider = obj["provider"].asString(),
                workspaceKey = key,
                workspacePath = path,
                workspaceIdentity = identity,
                remoteSessionId = obj["remoteSessionId"].asString(),
                hostId = hostId,
            ) to groupKey
        }.groupBy({ it.second }, { it.first })
            .mapValues { (_, tasks) -> tasks.distinctBy { it.id } }

        // 有些任务不在任何项目里（legacy/临时工作区）：给它们补一个工作区，否则整个任务消失。
        val covered = built.mapTo(HashSet()) { it.key }
        for ((groupKey, tasks) in tasksByKey) {
            if (groupKey in covered) continue
            val first = tasks.first()
            val path = first.workspacePath ?: groupKey
            built += WorkspaceItem(
                key = groupKey,
                path = path,
                identity = first.workspaceIdentity,
                label = path.substringAfterLast('/').substringAfterLast('\\').ifEmpty { path },
                kind = if (first.hostId == null || first.hostId == "local") "local" else "remote",
                purpose = "project",
                hostId = first.hostId,
                bridgeable = false,
            )
        }
        projectTreeWorkspaces = built.distinctBy { it.key }

        _state.update { current ->
            val previous = current.workspaces.associateBy { it.key }
            val merged = LinkedHashMap<String, WorkspaceItem>()
            for (workspace in built) {
                val old = previous[workspace.key]
                val tree = tasksByKey[workspace.key].orEmpty()
                merged[workspace.key] = if (old == null) {
                    workspace.copy(tasks = tree)
                } else {
                    workspace.copy(
                        identity = old.identity,
                        connectionState = old.connectionState,
                        lastError = old.lastError,
                        remoteSessionId = old.remoteSessionId,
                        // bootstrap 已确认它是当前窗口的 tab：保留可桥接标记与原始 purpose，
                        // 否则会把 tab 工作区误判成「需要 prepareProjectWorkspace」。
                        bridgeable = old.bridgeable,
                        purpose = old.purpose ?: workspace.purpose,
                        tasks = mergeTreeTasks(tree, old.tasks),
                    )
                }
            }
            // 保留 bootstrap 里有、项目树里没有的（例如 conversation 伪工作区/临时桥）。
            for ((key, workspace) in previous) {
                merged.putIfAbsent(key, workspace)
            }
            current.copy(workspaces = merged.values.toList())
        }
        return true
    }

    /**
     * 项目树任务与已有任务合并。
     *
     * 基底方向与网页一致：`readTree.tasks` 是**权威基底**（存在性、pinned/archived/
     * unreadAt/title），sessions-index 只补活动字段。因此：
     * - readTree 的 `unreadAt` **允许为 null**（已读后服务端清掉），不能再用旧值顶回去；
     * - readTree 里没有的任务直接消失（桌面端删除后本地不能残留）；刚建的新任务由
     *   sessions-index 的增量补回来。
     */
    private fun mergeTreeTasks(tree: List<TaskItem>, old: List<TaskItem>): List<TaskItem> {
        val oldById = old.associateBy { it.id }
        return tree.map { task ->
            val previous = oldById[task.id] ?: return@map task
            task.copy(
                displayStatus = previous.displayStatus ?: task.displayStatus,
                hasBackgroundWork = previous.hasBackgroundWork,
                updatedAt = maxOfNullable(task.updatedAt, previous.updatedAt),
                // 树里的标题更权威（含 title_overridden），只在树缺失时回退。
                title = task.title ?: previous.title,
            )
        }
    }

    private fun maxOfNullable(a: Long?, b: Long?): Long? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }

    /**
     * 从对话层快照的 `sessionsIndex[i].list` 合并**任务**与 `lastAssistantPreview`。
     *
     * 3.16.1 的 bootstrap 不再带顶层 `tasks`（桌面端 `buildBootstrapResult` 只有
     * `workspaces`），任务列表来自每个工作区的 `subscribeSessionsIndexV4`。这里按
     * 工作区把条目映射成 [TaskItem]（见 [SessionsIndexProjection]）。
     *
     * 每个已订阅的工作区各有一个 (桥, key)；`sessionsIndex` 在 `ConversationTransport`
     * 里，核心快照没有它。
     */
    private suspend fun mergeSessionsIndex() {
        val previews = HashMap<String, String>()
        val tasksByWorkspace = HashMap<String, List<TaskItem>>()
        val workspaces = _state.value.workspaces.associateBy { it.key }

        // 迭代快照：循环体里有挂起点，另一个协程可能同时写入 previewSubscriptions。
        for ((workspaceKey, pair) in previewSubscriptions.toList()) {
            val workspace = workspaces[workspaceKey] ?: continue
            // 对话关闭/重开时可能瞬时 invalidState；跳过，下一拍再合。
            val snapshot = runCatching { engine.conversationSnapshot(pair.first, pair.second) }
                .getOrNull().asObject() ?: continue
            val entries = snapshot["sessionsIndex"].asArray() ?: continue

            previews.putAll(SessionsIndexProjection.previews(entries))

            // `ready=false` 且列表为空 = 初始快照还没到，不能当成「没有任务」把界面清空。
            val readyEntries = entries.filter { entry ->
                val obj = entry.asObject() ?: return@filter false
                val ready = (obj["ready"] as? JsonPrimitive)?.booleanOrNull == true
                ready || obj["list"].asArray()?.isNotEmpty() == true
            }
            if (readyEntries.isEmpty()) continue
            tasksByWorkspace[workspaceKey] =
                SessionsIndexProjection.tasks(workspace, JsonArray(readyEntries))
        }

        if (previews.isEmpty() && tasksByWorkspace.isEmpty()) return
        _state.update { current ->
            val updated = current.workspaces.map { workspace ->
                val indexed = tasksByWorkspace[workspace.key] ?: return@map workspace
                // 基底是 readTree 派生的 workspace.tasks：sessions-index 只补活动字段。
                // 索引里没有的任务必须保留（历史导入、无 runtime 的旧会话在索引里可能缺席）。
                val byId = indexed.associateBy { it.id }
                val base = workspace.tasks.map { task ->
                    val entry = byId[task.id] ?: return@map task
                    task.copy(
                        displayStatus = entry.displayStatus ?: task.displayStatus,
                        hasBackgroundWork = entry.hasBackgroundWork,
                        updatedAt = maxOfNullable(task.updatedAt, entry.updatedAt),
                        title = when {
                            entry.titleSource == "custom" -> entry.title ?: task.title
                            !entry.title.isNullOrBlank() -> entry.title
                            else -> task.title
                        },
                        titleSource = entry.titleSource ?: task.titleSource,
                    )
                }
                val known = base.mapTo(HashSet()) { it.id }
                val additions = indexed.filter { it.id !in known }
                workspace.copy(tasks = base + additions)
            }
            current.copy(workspaces = updated, previews = current.previews + previews)
        }
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
        /** 本地乐观更新；失败时整组任务回滚到调用前快照。 */
        updateTasks: (List<TaskItem>) -> List<TaskItem> = { it },
    ) {
        val bridge = mutationBridge ?: run {
            _state.update { it.copy(error = "还没有可用的工作区连接") }
            return
        }
        val before = _state.value.workspaces.firstOrNull { it.key == workspace.key }?.tasks.orEmpty()
        _state.update { current ->
            current.copy(
                busyTasks = current.busyTasks + task.id,
                error = null,
                workspaces = current.workspaces.map {
                    if (it.key == workspace.key) it.copy(tasks = updateTasks(it.tasks)) else it
                },
            )
        }
        try {
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
                task.provider?.let { put("provider", it) }
                extra()
            }
            engine.channelCall(
                session = bridge,
                channel = "zcode-task",
                method = method,
                args = JsonArray(listOf(arg)),
            )
            // 应答是 TaskMeta；本地已经在乐观更新里改好，下一次 readTree 再对齐服务端。
            _state.update { it.copy(busyTasks = it.busyTasks - task.id) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update {
                val beforeIds = before.mapTo(HashSet()) { task -> task.id }
                it.copy(
                    busyTasks = it.busyTasks - task.id,
                    error = e.toUserMessage(),
                    workspaces = it.workspaces.map { ws ->
                        if (ws.key == workspace.key) {
                            // 只回滚调用前的任务；RPC 期间 sessions-index 新加的任务不能被抹掉。
                            ws.copy(tasks = before + ws.tasks.filter { t -> t.id !in beforeIds })
                        } else {
                            ws
                        }
                    },
                )
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
                // token 失效 / 不属于当前账号：relay 回 auth-failed，或重连换 token 时
                // HTTP 401（auth-required）。清掉旧登录态、停掉会话并引导重登，
                // 否则重连会一直拿同一个无效 JWT 打服务端。
                val needsLogin =
                    failure?.reason == "auth-failed" || failure?.reason == "auth-required"
                if (needsLogin) {
                    viewModelScope.launch {
                        authStore.clear()
                        engine.disconnect()
                    }
                }
                val failureText = failure?.let(::relayFailureText)
                _state.update {
                    it.copy(
                        relayFailure = failure,
                        needsLogin = it.needsLogin || needsLogin,
                        error = when {
                            needsLogin -> "登录已失效，请重新登录"
                            failureText != null -> failureText
                            else -> it.error
                        },
                    )
                }
            }
        }
        observers += viewModelScope.launch {
            engine.events.collect { event ->
                when (event) {
                    is EngineEvent.WorkspaceListUpdated -> {
                        WorkspaceProjection.snapshotOrNull(event.value)?.let { snapshot ->
                            _state.update { current ->
                                current.copy(
                                    workspaces = mergeWorkspaceSnapshot(
                                        current.workspaces,
                                        snapshot.workspaces,
                                    ),
                                )
                            }
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
        // 扫码配对完成会新增/切换当前设备；首页此时必须自动重建会话并拉快照，
        // 不能停在配对前的「还没有连接电脑」等用户手动重试。
        observers += viewModelScope.launch {
            repository.currentDevice.collect { device ->
                val id = device?.id ?: return@collect
                if (id != lastEnteredDeviceId) {
                    lastEnteredDeviceId = id
                    onEnter()
                }
            }
        }
        // 兜底：relay 刚进入 paired 且首页还没有数据/仍带错误时，自动重试一次。
        observers += viewModelScope.launch {
            engine.relayState.collect { relay ->
                if (relay == RelayState.Paired &&
                    (_state.value.workspaces.isEmpty() || _state.value.error != null)
                ) {
                    onEnter()
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
