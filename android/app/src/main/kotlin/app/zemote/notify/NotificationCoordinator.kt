package app.zemote.notify

import android.content.Context
import android.util.Log
import app.zemote.data.AppSettings
import app.zemote.engine.EngineEvent
import app.zemote.ui.EnginePort
import app.zemote.ui.chat.SessionsIndexProjection
import app.zemote.ui.chat.TaskItem
import app.zemote.ui.chat.WorkspaceItem
import app.zemote.ui.chat.WorkspaceProjection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 把引擎事件翻译成通知：运行中卡片、完成/失败提醒、等待确认提醒、前台服务。
 *
 * ## 数据源
 *
 * - **运行集与状态迁移**：`workspace-list-*` 的整表快照（`workspaces[].tasks[]`），
 *   字段是 `displayStatus` / `hasBackgroundWork`。同一 `taskId` 的稳定通知 id 由
 *   [Notifier.notificationIdFor] 派生，因此「运行 → 完成」是**同一条通知原地改字**，
 *   不是撤一张再发一张。
 * - **正文**：`sessions-index` 里的 `lastAssistantPreview`。快照里没有它，所以这里在
 *   每次收到会话事件时顺手从引擎快照里捞一次，按 `sessionId` 缓存。
 * - **等待授权/输入**：`stateChanged` 不带载荷，无法从事件本身判断。由对话页在读到
 *   `pendingInteractions` 时调用 [onAttention]（`active=false` 时撤掉去重标记）。
 *
 * ## 开关
 *
 * 总开关 `notificationEnabled` 关掉时**不发任何通知**（含前台服务通知），但连接与
 * 服务本身继续；事件勾选分别控制完成/出错/等待确认。前台服务由
 * `foregroundServiceEnabled` 控制。
 */
class NotificationCoordinator(
    context: Context,
    private val engine: EnginePort,
    private val settings: StateFlow<AppSettings>,
    private val scope: CoroutineScope,
) {

    private val appContext = context.applicationContext
    private val notifier = Notifier.get(appContext)

    /** taskId → 上一次看到的运行态。 */
    private var wasRunning: Map<String, Boolean> = emptyMap()

    /** taskId → 稳定通知 id（任务从快照里消失时用来撤卡）。 */
    private var previousIds: Map<String, Int> = emptyMap()

    /** 上一次上报的运行集（服务正文/组摘要用）。 */
    private var runningNow: List<RunningTask> = emptyList()

    /** sessionId → lastAssistantPreview。 */
    private var previews: Map<String, String> = emptyMap()

    /** workspaceKey → sessions-index 投影出的任务（workspace-list 本身不带 tasks）。 */
    private val tasksByWorkspace = HashMap<String, List<TaskItem>>()

    /** 已经发过「等待确认」的任务；回到无待交互时移除。 */
    private val attentionPosted = mutableSetOf<String>()

    private var serviceWanted = false

    fun start() {
        scope.launch {
            try {
                engine.events.collect { event -> onEvent(event) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "通知事件流中断: ${e.message}")
            }
        }
        scope.launch {
            settings.collect { applySettings(it) }
        }
    }

    /**
     * 对话页读到待交互时调用。
     *
     * @param active `pendingInteractions` 非空；同一个任务只提醒一次，直到它变回空。
     */
    fun onAttention(
        taskId: String,
        title: String,
        workspaceKey: String,
        active: Boolean,
        body: String = "",
    ) {
        val current = settings.value
        if (!current.notificationEnabled || !current.notifyOnAttention) return
        if (!active) {
            attentionPosted.remove(taskId)
            return
        }
        if (!attentionPosted.add(taskId)) return
        notifier.notifyAttention(
            AttentionTask(
                title = title.ifBlank { "任务" },
                body = body.ifBlank { "等待你的授权或输入" },
                sessionId = taskId,
                workspaceKey = workspaceKey,
            ),
        )
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private fun applySettings(current: AppSettings) {
        val wanted = current.foregroundServiceEnabled
        if (wanted && !serviceWanted) {
            serviceWanted = true
            startService()
        } else if (!wanted && serviceWanted) {
            serviceWanted = false
            ZemoteForegroundService.stop(appContext)
        }
        if (!current.notificationEnabled) {
            // 总开关关掉：撤掉全部卡片；服务通知也一起没掉（服务本身继续跑）。
            notifier.clearAll()
        }
    }

    private fun startService() {
        try {
            ZemoteForegroundService.start(
                appContext,
                runningNow.size,
                Notifier.runningBody(runningNow),
            )
        } catch (e: Exception) {
            // Android 12+ 在后台启动 FGS 会抛 ForegroundServiceStartNotAllowedException；
            // 首次启动发生在 Activity 前台，正常不会命中。这里不让它把应用带崩。
            Log.e(TAG, "启动前台服务失败: ${e.message}")
        }
    }

    private fun onEvent(event: EngineEvent) {
        when (event) {
            is EngineEvent.WorkspaceListUpdated -> {
                val snapshot = WorkspaceProjection.snapshotOrNull(event.value) ?: return
                // workspace-list 不再带 tasks；已有任务来自 sessions-index，按 key 保留。
                val next = HashMap<String, List<TaskItem>>()
                for (workspace in snapshot.workspaces) {
                    next[workspace.key] = if (workspace.tasks.isNotEmpty()) {
                        workspace.tasks
                    } else {
                        tasksByWorkspace[workspace.key].orEmpty()
                    }
                }
                tasksByWorkspace.clear()
                tasksByWorkspace.putAll(next)
                syncTrackedTasks()
            }

            is EngineEvent.ConversationEvent -> {
                val type = (event.event as? JsonObject)
                    ?.get("type")?.let { (it as? JsonPrimitive)?.contentOrNull }
                when (type) {
                    // 任务状态只在 sessions-index 里；不从这里同步，运行卡/完成提醒永远为空。
                    "sessionsIndexUpdated" -> refreshFromIndex(event.session, event.key, syncTasks = true)
                    "stateChanged" -> refreshFromIndex(event.session, event.key, syncTasks = false)
                }
            }

            else -> Unit
        }
    }

    /** 把各工作区的 tasks 合成整表喂给 [syncTasks]（通知 id 仍按 workspaceKey+taskId）。 */
    private fun syncTrackedTasks() {
        syncTasks(
            tasksByWorkspace.map { (key, tasks) ->
                WorkspaceItem(key = key, path = key, label = key, tasks = tasks)
            },
        )
    }

    private fun syncTasks(workspaces: List<WorkspaceItem>) {
        // 断线重连时可能先收到一个空快照；直接当成「全部任务没了」会把卡片全撤掉，
        // 然后下一拍再重建，看起来像闪断。没有工作区时不动。
        if (workspaces.isEmpty() && wasRunning.isNotEmpty()) return

        val current = settings.value
        val running = mutableListOf<RunningTask>()
        val finished = mutableListOf<FinishedTask>()
        val nextRunning = HashMap<String, Boolean>()
        val nextIds = HashMap<String, Int>()
        val seen = HashSet<String>()

        for (workspace in workspaces) {
            for (task in workspace.tasks) {
                if (task.archived) continue
                seen += task.id
                val runningTask = task.running
                nextRunning[task.id] = runningTask
                val id = Notifier.notificationIdFor(workspace.key, task.id)
                nextIds[task.id] = id

                if (runningTask) {
                    running += RunningTask(
                        id = id,
                        title = task.title ?: task.id,
                        body = previews[task.id].orEmpty(),
                        status = TaskStatus.RUNNING,
                        activityAt = task.updatedAt ?: 0L,
                        sessionId = task.id,
                        workspaceKey = workspace.key,
                    )
                } else if (wasRunning[task.id] == true) {
                    val status = when (task.displayStatus) {
                        "error" -> TaskStatus.FAILED
                        "idle", "interrupted" -> TaskStatus.INTERRUPTED
                        else -> TaskStatus.COMPLETED
                    }
                    finished += FinishedTask(
                        id = id,
                        title = task.title ?: task.id,
                        body = previews[task.id].orEmpty(),
                        status = status,
                        sessionId = task.id,
                        workspaceKey = workspace.key,
                    )
                }
            }
        }

        val removed = previousIds.filterKeys { it !in seen }.values

        wasRunning = nextRunning
        previousIds = nextIds
        runningNow = running

        if (!current.notificationEnabled) return

        notifier.syncRunningTasks(
            running = running,
            removedIds = removed,
            completed = finished,
        )

        if (serviceWanted) {
            ZemoteForegroundService.update(appContext, running)
        }

        if (finished.isNotEmpty()) {
            for (task in finished) {
                when {
                    task.status == TaskStatus.FAILED && current.notifyOnError ->
                        notifier.notifyCompleted(task)

                    task.status != TaskStatus.FAILED && current.notifyOnComplete ->
                        notifier.notifyCompleted(task)

                    else -> Unit
                }
            }
        }
    }

    /**
     * 从对话层快照的 `sessionsIndex` 里捞 `lastAssistantPreview`，并在需要时同步任务状态。
     *
     * `sessionsIndex` 属于 `ConversationTransport`（不是核心快照），要按触发事件里的
     * 桥句柄 + conversation key 查。任务列表也只在这里（workspace-list 是空的）。
     */
    private fun refreshFromIndex(session: Long, key: String, syncTasks: Boolean) {
        scope.launch {
            try {
                val snapshot = engine.conversationSnapshot(session, key) as? JsonObject
                    ?: return@launch
                val entries = snapshot["sessionsIndex"] as? JsonArray ?: return@launch
                val merged = HashMap<String, String>()
                for (entry in entries) {
                    val list = (entry as? JsonObject)?.get("list") as? JsonArray ?: continue
                    for (item in list) {
                        val obj = item as? JsonObject ?: continue
                        val id = (obj["sessionId"] as? JsonPrimitive)?.contentOrNull
                            ?: (obj["taskId"] as? JsonPrimitive)?.contentOrNull
                            ?: continue
                        val preview = (obj["lastAssistantPreview"] as? JsonPrimitive)?.contentOrNull
                        if (!preview.isNullOrBlank()) merged[id] = preview
                    }
                }
                if (merged.isNotEmpty()) previews = previews + merged

                if (syncTasks) {
                    val workspace = WorkspaceItem(key = key, path = key, label = key)
                    val tasks = SessionsIndexProjection.tasks(workspace, entries)
                    // 初始快照还没到时 list 为空，不能当成「任务全没了」。
                    if (tasks.isNotEmpty()) {
                        tasksByWorkspace[key] = tasks
                        syncTrackedTasks()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "读取会话预览失败: ${e.message}")
            }
        }
    }

    private companion object {
        const val TAG = "ZemoteNotifyCoord"
    }
}
