package app.zemote.data

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 对话行的本地缓存。
 *
 * ## 为什么需要
 *
 * 手机端每次打开任务都要等桌面端回一份完整快照；网络慢或桌面端 agent 没起来时，
 * 页面会先空一段时间。缓存让「打开过的对话」立刻有内容，快照到达后再覆盖。
 *
 * ## 边界
 *
 * - 每个 `(workspaceKey, taskId)` 一个文件，内容只有行窗口（`rows` 原文）与保存时间；
 * - 最多保留 [MAX_ROWS] 行、最多 [MAX_FILES] 个任务，按最近写入淘汰；
 * - 写失败静默（缓存是尽力而为，不能影响对话本身）；
 * - 清理由 `ChatViewModel` 在任务删除后调用 [remove]。
 */
class ChatCache(context: Context) {

    private val dir = File(context.applicationContext.filesDir, "chat-cache").apply { mkdirs() }

    private val json = Json { ignoreUnknownKeys = true }

    private val lock = Any()

    /** 读缓存；没有或损坏时返回空列表。 */
    fun load(workspaceKey: String, taskId: String): List<JsonObject> = synchronized(lock) {
        val file = fileFor(workspaceKey, taskId)
        if (!file.exists()) return emptyList()
        return try {
            val root = json.parseToJsonElement(file.readText()) as? JsonObject
            (root?.get("rows") as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                .orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 覆盖写一个任务的窗口。 */
    fun save(workspaceKey: String, taskId: String, rows: List<JsonObject>) {
        if (rows.isEmpty()) return
        val trimmed = rows.takeLast(MAX_ROWS)
        synchronized(lock) {
            try {
                val file = fileFor(workspaceKey, taskId)
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(
                    buildJsonObject {
                        put("workspaceKey", workspaceKey)
                        put("taskId", taskId)
                        put("savedAt", System.currentTimeMillis())
                        put("rows", JsonArray(trimmed))
                    }.toString(),
                )
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
                pruneLocked()
            } catch (e: Exception) {
                // 缓存失败不影响对话。
            }
        }
    }

    /** 删除一个任务的缓存（任务被删除时调用）。 */
    fun remove(workspaceKey: String, taskId: String) {
        synchronized(lock) { fileFor(workspaceKey, taskId).delete() }
    }

    private fun fileFor(workspaceKey: String, taskId: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$workspaceKey\u0000$taskId".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return File(dir, "$digest.json")
    }

    private fun pruneLocked() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedByDescending { it.lastModified() }
            .drop(MAX_FILES)
            .forEach { it.delete() }
    }

    private companion object {
        const val MAX_ROWS = 400
        const val MAX_FILES = 60
    }
}
