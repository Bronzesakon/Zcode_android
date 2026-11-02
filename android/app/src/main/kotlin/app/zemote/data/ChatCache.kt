package app.zemote.data

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** 一个任务的完整对话历史缓存（schema v2）。 */
data class CachedConversation(
    val rows: List<JsonObject>,
    val logEpoch: String?,
    val firstRowId: Long?,
    val savedAt: Long,
)

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
 * - 每个 `(workspaceKey, taskId)` 一个文件，内容为完整历史（`rows`）与元数据
 *   （`logEpoch`、`firstRowId`、`savedAt`），schema v2；旧版只有 `rows` 的文件仍可读；
 * - **不设行数/文件数/体积上限**：长对话经过多轮上下文压缩后仍需完整历史；
 * - 只按时间淘汰：超过 [MAX_AGE_MS]（3 天）未写入的缓存文件在下次写入时删除；
 * - 原子写：先写 `.tmp` 再 rename，rename 失败时 delete + rename；
 * - 读损坏/解析失败返回 `null`（[load] 返回空列表）；写失败静默；
 * - 清理由 `ChatViewModel` 在任务删除后调用 [remove]。
 */
class ChatCache internal constructor(root: File) {

    /** 公开入口：落到 `filesDir/chat-cache`。 */
    constructor(context: Context) : this(File(context.applicationContext.filesDir, "chat-cache"))

    private val dir = root.apply { mkdirs() }

    private val json = Json { ignoreUnknownKeys = true }

    private val lock = Any()

    /** 读缓存；没有、损坏或解析失败时返回 `null`。 */
    fun loadConversation(workspaceKey: String, taskId: String): CachedConversation? = synchronized(lock) {
        val file = fileFor(workspaceKey, taskId)
        if (!file.exists()) return null
        return try {
            val root = json.parseToJsonElement(file.readText()) as? JsonObject ?: return null
            val rows = root["rows"] as? JsonArray ?: return null
            CachedConversation(
                rows = rows.mapNotNull { it as? JsonObject },
                logEpoch = (root["logEpoch"] as? JsonPrimitive)?.contentOrNull,
                firstRowId = (root["firstRowId"] as? JsonPrimitive)?.longOrNull,
                savedAt = (root["savedAt"] as? JsonPrimitive)?.longOrNull ?: file.lastModified(),
            )
        } catch (e: Exception) {
            null
        }
    }

    /** 覆盖写一个任务的完整历史与元数据；空 rows 不落盘。 */
    fun saveConversation(
        workspaceKey: String,
        taskId: String,
        rows: List<JsonObject>,
        logEpoch: String?,
        firstRowId: Long?,
    ) {
        if (rows.isEmpty()) return
        // 完整历史原样落盘：不截断。淘汰只按时间（3 天未写入）。
        val payload = encode(workspaceKey, taskId, rows, logEpoch, firstRowId)
        synchronized(lock) {
            try {
                val file = fileFor(workspaceKey, taskId)
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(payload)
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

    private fun encode(
        workspaceKey: String,
        taskId: String,
        rows: List<JsonObject>,
        logEpoch: String?,
        firstRowId: Long?,
    ): String = buildJsonObject {
        put("version", SCHEMA_VERSION)
        put("workspaceKey", workspaceKey)
        put("taskId", taskId)
        put("savedAt", System.currentTimeMillis())
        put("logEpoch", logEpoch?.let { JsonPrimitive(it) } ?: JsonNull)
        put("firstRowId", firstRowId?.let { JsonPrimitive(it) } ?: JsonNull)
        put("rows", JsonArray(rows))
    }.toString()

    /** 读缓存行；没有或损坏时返回空列表（兼容旧调用点）。 */
    fun load(workspaceKey: String, taskId: String): List<JsonObject> =
        loadConversation(workspaceKey, taskId)?.rows ?: emptyList()

    /** 覆盖写一个任务的行窗口（兼容旧调用点，不携带元数据）。 */
    fun save(workspaceKey: String, taskId: String, rows: List<JsonObject>) {
        saveConversation(workspaceKey, taskId, rows, logEpoch = null, firstRowId = null)
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

    /** 删除超过 [MAX_AGE_MS] 未写入的缓存（时间淘汰，不做体积/数量上限）。 */
    private fun pruneLocked() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        files.filter { it.lastModified() < cutoff }.forEach { it.delete() }
    }

    private companion object {
        const val SCHEMA_VERSION = 2
        /** 3 天未写入即淘汰。 */
        const val MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000
    }
}
