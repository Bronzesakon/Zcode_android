package app.zemote.ui.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * `sessionsIndex[i].list` → 任务列表。
 *
 * ## 为什么需要它
 *
 * 3.16.1 的 bootstrap **不再带顶层 `tasks`**：桌面端 `buildBootstrapResult` 只返回
 * `windowControlSessionId` / `desktopAppVersion` / `workspaces` / `initialViewState` /
 * `mobileViewState` / `mobileTaskViewEpoch`。任务由每个工作区的
 * `subscribeSessionsIndexV4` 提供（`sessionsIndex[i].list`）。
 *
 * 条目 schema（桌面端 `out/host/chunk-5YNPFY3L.js` 的 `O_`）：
 * `sessionId` / `workspaceId` / `parentSessionId?` / `title` / `titleSource?` /
 * `lastOutcome?` / `phase` / `sessionEnded` / `hasBackgroundWork` /
 * `workflowActivity?` / `pendingInteraction?` / `pendingInteractionSummary?` /
 * `goalStatus?` / `lastActivityAt` / `lastAssistantPreview?` / `createdAt`。
 *
 * `phase` 枚举：`draft` / `prewarming` / `running` / `completedSuccess` /
 * `completedInterrupted` / `error`。
 *
 * `pinned` / `archived` / `unreadAt` **不在这个 schema 里**，也不在 `cvt` 的继承范围里；
 * 它们只来自 `readTree.tasks` / `zcodeTaskService.getTaskMeta`。这里不再假装能从条目读到，
 * 一律留默认值，等任务元数据通道接入后由上层合并。
 */
internal object SessionsIndexProjection {

    /** 把 `sessionsIndex` 数组（可能有多个订阅）里的全部条目映射成任务。 */
    fun tasks(workspace: WorkspaceItem, sessionsIndex: JsonArray): List<TaskItem> {
        val out = mutableListOf<TaskItem>()
        for (entry in sessionsIndex) {
            val list = (entry as? JsonObject)?.get("list") as? JsonArray ?: continue
            for (item in list) {
                val obj = item as? JsonObject ?: continue
                toTask(workspace, obj)?.let { out += it }
            }
        }
        return out
    }

    /** `sessionId → lastAssistantPreview`（只取非空预览）。 */
    fun previews(sessionsIndex: JsonArray): Map<String, String> {
        val out = HashMap<String, String>()
        for (entry in sessionsIndex) {
            val list = (entry as? JsonObject)?.get("list") as? JsonArray ?: continue
            for (item in list) {
                val obj = item as? JsonObject ?: continue
                val id = obj["sessionId"].asString() ?: obj["taskId"].asString() ?: continue
                val preview = obj["lastAssistantPreview"].asString()
                if (!preview.isNullOrBlank()) out[id] = preview
            }
        }
        return out
    }

    private fun toTask(workspace: WorkspaceItem, obj: JsonObject): TaskItem? {
        // `sessionId` 就是协议里的 `taskId`（见 TaskItem 的文档）。
        val id = obj["sessionId"].asString() ?: obj["taskId"].asString() ?: return null
        val phase = obj["phase"].asString()
        val hasBackground = obj["hasBackgroundWork"].asBool() == true
        // 状态只看 phase；`hasBackgroundWork` 是独立标记，不并入 running。
        val status = when (phase) {
            "running", "prewarming" -> "running"
            "completedSuccess", "completedInterrupted" -> "completed"
            "error" -> "error"
            else -> null
        }
        return TaskItem(
            id = id,
            title = obj["title"].asString(),
            titleSource = obj["titleSource"].asString(),
            displayStatus = status,
            hasBackgroundWork = hasBackground,
            updatedAt = obj["lastActivityAt"].asLong() ?: obj["updatedAt"].asLong(),
            createdAt = obj["createdAt"].asLong(),
            provider = obj["provider"].asString(),
            workspaceKey = workspace.key,
            workspaceLabel = workspace.label,
            workspaceKind = workspace.kind,
            remoteSessionId = obj["remoteSessionId"].asString(),
        )
    }
}
