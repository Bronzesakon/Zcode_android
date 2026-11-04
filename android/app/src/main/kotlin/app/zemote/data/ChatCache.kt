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
 * - 每个 `(workspaceKey, taskId)` 一个文件，内容为**最近 [MAX_ROWS] 行**与元数据
 *   （`logEpoch`、`firstRowId`、`savedAt`），schema v2；旧版只有 `rows` 的文件仍可读；
 * - 显式上限：每个任务最多 [MAX_ROWS] 行、文件最大 [MAX_FILE_BYTES]；更早历史由
 *   `rowsRange` 按需拉取。整段历史落盘再整段读进 Compose 会把长对话变成几百 MB
 *   的堆占用，这是之前低内存闪退的直接原因；
 * - 只按时间淘汰：超过 [MAX_AGE_MS]（3 天）未写入的缓存文件在下次写入时删除；
 * - 原子写：先写 `.tmp` 再 rename，rename 失败时 delete + rename；
 * - 读损坏/解析失败/超限旧文件返回 `null`（[load] 返回空列表）；写失败静默；
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
        // 旧版本写下的「完整历史」文件可能有几十 MB；解析它本身就是一次 OOM 风险，
        // 直接丢弃，让本次从服务器拿尾部窗口重新建立有界缓存。
        if (file.length() > MAX_FILE_BYTES) {
            file.delete()
            return null
        }
        return try {
            val root = json.parseToJsonElement(file.readText()) as? JsonObject ?: return null
            val rows = root["rows"] as? JsonArray ?: return null
            val parsed = rows.mapNotNull { it as? JsonObject }
            CachedConversation(
                // 只保留尾部：打开对话先看到最近内容，更早历史滚动时再 rowsRange。
                rows = if (parsed.size > MAX_ROWS) parsed.takeLast(MAX_ROWS) else parsed,
                logEpoch = (root["logEpoch"] as? JsonPrimitive)?.contentOrNull,
                firstRowId = (root["firstRowId"] as? JsonPrimitive)?.longOrNull,
                savedAt = (root["savedAt"] as? JsonPrimitive)?.longOrNull ?: file.lastModified(),
            )
        } catch (e: Exception) {
            null
        }
    }

    /** 覆盖写一个任务的最近行窗口与元数据；空 rows 不落盘。 */
    fun saveConversation(
        workspaceKey: String,
        taskId: String,
        rows: List<JsonObject>,
        logEpoch: String?,
        firstRowId: Long?,
    ) {
        if (rows.isEmpty()) return
        // 只写尾部窗口：完整历史留在服务端，滚动时按需 rowsRange。行数上限之外还要
        // 按**字节**收缩：单行可能带几 MB 工具输出，只按 400 行截断仍可能写出超过
        // 读取上限的文件，下次进页被整份丢弃（10 审计 P1-10）。
        var trimmed = if (rows.size > MAX_ROWS) rows.takeLast(MAX_ROWS) else rows
        var payload = encode(workspaceKey, taskId, trimmed, logEpoch, firstRowId)
        while (trimmed.size > 1 && payload.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) {
            // 每次收缩 1/4，几十 MB 的极端窗口也只需几次编码。
            val keep = (trimmed.size * 3 / 4).coerceAtLeast(1)
            if (keep == trimmed.size) break
            trimmed = trimmed.takeLast(keep)
            payload = encode(workspaceKey, taskId, trimmed, logEpoch, firstRowId)
        }
        if (payload.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) return
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

    /** 删除超过 [MAX_AGE_MS] 未写入的缓存（行数/体积上限在读写路径单独约束）。 */
    private fun pruneLocked() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        files.filter { it.lastModified() < cutoff }.forEach { it.delete() }
    }

    private companion object {
        const val SCHEMA_VERSION = 2

        /** 每个任务最多缓存的尾部行数。400 行足够首屏 + 回滚几屏，更早历史按需拉。 */
        const val MAX_ROWS = 400

        /** 超过这个体积的旧缓存直接丢弃，避免解析时把进程顶到 OOM。 */
        const val MAX_FILE_BYTES = 6L * 1024 * 1024

        /** 3 天未写入即淘汰。 */
        const val MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000
    }
}
