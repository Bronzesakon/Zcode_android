package app.zemote.data

import android.content.Context
import app.zemote.ui.chat.WorkspaceItem
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 首页项目树的落盘快照。 */
@Serializable
data class CachedHome(
    val version: Int = SCHEMA_VERSION,
    val savedAt: Long,
    val deviceId: String,
    val workspaces: List<WorkspaceItem>,
    val previews: Map<String, String> = emptyMap(),
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/**
 * 首页「项目 → 任务」的本地缓存。
 *
 * ## 为什么需要
 *
 * `project.readTree` 是桌面端全量项目树，每次冷启动/Activity 重建都要等它回来；
 * 期间首页只能显示骨架。缓存让上一轮的项目/任务/未读/运行态立即出现，网络刷新
 * 在后台覆盖，不再每次进页都从空列表开始。
 *
 * ## 边界
 *
 * - 单个文件（`filesDir/home-cache.json`），按 deviceId 隔离；
 * - 超过 [MAX_AGE_MS] 或损坏直接忽略（下次成功刷新会重写）；
 * - 文件超过 [MAX_FILE_BYTES] 直接丢弃，防止历史脏数据把解析拖死；
 * - 原子写：`.tmp` + rename；写失败静默，不影响首页。
 */
class HomeCache internal constructor(root: File) {

    constructor(context: Context) : this(File(context.applicationContext.filesDir, "home-cache.json"))

    private val file = root

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(deviceId: String): CachedHome? {
        if (!file.exists()) return null
        if (file.length() > MAX_FILE_BYTES) {
            file.delete()
            return null
        }
        return try {
            val cached = json.decodeFromString<CachedHome>(file.readText())
            if (cached.version != CachedHome.SCHEMA_VERSION) return null
            if (cached.deviceId != deviceId) return null
            if (cached.workspaces.isEmpty()) return null
            if (System.currentTimeMillis() - cached.savedAt > MAX_AGE_MS) return null
            cached
        } catch (e: Exception) {
            null
        }
    }

    fun save(
        deviceId: String,
        workspaces: List<WorkspaceItem>,
        previews: Map<String, String>,
    ) {
        if (workspaces.isEmpty()) return
        val payload = try {
            json.encodeToString(
                CachedHome(
                    savedAt = System.currentTimeMillis(),
                    deviceId = deviceId,
                    // 只缓存首页实际会渲染的字段；数量上限防止异常快照把文件撑爆。
                    workspaces = workspaces.take(MAX_WORKSPACES).map { workspace ->
                        workspace.copy(tasks = workspace.tasks.take(MAX_TASKS_PER_WORKSPACE))
                    },
                    previews = previews,
                ),
            )
        } catch (e: Exception) {
            return
        }
        if (payload.length > MAX_FILE_BYTES) return
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(payload)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            // 缓存失败不影响首页。
        }
    }

    companion object {
        /** 3 天未刷新就丢弃：项目树变化快，太旧的缓存比骨架更误导。 */
        private const val MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000
        private const val MAX_FILE_BYTES = 8L * 1024 * 1024
        private const val MAX_WORKSPACES = 1_000
        private const val MAX_TASKS_PER_WORKSPACE = 500
    }
}
