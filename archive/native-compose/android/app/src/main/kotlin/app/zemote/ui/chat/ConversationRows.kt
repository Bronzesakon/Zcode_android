package app.zemote.ui.chat

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
 * 对话行窗口的归约器。
 *
 * 对齐网页端 `parts-DcYGwy2q.js` 的 5 种 `row.*` op（`archive/pre-3.16.1-auth/docs/remote-v4/18` §1.2）：
 * - `row.appended`：追加（不去重）；
 * - `row.upserted`：按 `rowId` 替换，**窗口里找不到就丢弃**；
 * - `row.removed`：保留 `rowId < fromRowId` 的行（按 id 截断，不是删一条）；
 * - `row.delta`：按 `path` 对已有行做**追加**（`text` / `inputText` / `output.text` / `summaryText`）；
 * - `state.updated`：不进行窗口，由 `ChatViewModel` 单独处理。
 *
 * 纯函数、无 Android 依赖，可在 JVM 单测里逐条覆盖。
 */
object ConversationRows {

    /** 行窗口。 */
    data class Window(
        val rows: List<JsonObject> = emptyList(),
        val totalCount: Long = 0,
        val firstRowId: Long? = null,
    )

    /** 从 `payload.snapshot.rows` 读窗口。 */
    fun fromSnapshot(snapshot: JsonObject): Window {
        val rows = snapshot["rows"] as? JsonObject ?: return Window()
        val window = (rows["window"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
        return Window(
            rows = window,
            totalCount = rows["totalCount"].asLong() ?: window.size.toLong(),
            firstRowId = rows["firstRowId"].asLong(),
        )
    }

    /**
     * 应用一批 deltas。认不出的 op 静默忽略（与网页端一致）。
     *
     * 一批 deltas 只复制一次列表，`rowId → 下标` 按需建一次（与逐条 `indexOfFirst`
     * **同语义**：重复 `rowId` 取首次出现的位置）。流式帧里几十条 delta 不会再退化成
     * 「每条都 indexOfFirst + toMutableList」的 O(n²)。
     */
    fun apply(window: Window, deltas: JsonArray): Window {
        if (deltas.isEmpty()) return window
        val rows = window.rows.toMutableList()
        var total = window.totalCount
        var first = window.firstRowId
        var index: MutableMap<Long, Int>? = null

        // 下标表与 `indexOfFirst { it.rowId == id }` 等价：同一 rowId 只记第一次出现的位置，
        // 缺失/非数字 rowId 的行不进表（逐条查找也永远匹配不到它们）。
        fun indexOf(id: Long): Int {
            val map = index ?: HashMap<Long, Int>(rows.size * 2).also { created ->
                rows.forEachIndexed { position, row ->
                    val rowId = row["rowId"].asLong() ?: return@forEachIndexed
                    if (!created.containsKey(rowId)) created[rowId] = position
                }
                index = created
            }
            return map[id] ?: -1
        }

        for (element in deltas) {
            val op = element as? JsonObject ?: continue
            when (op["op"].asString()) {
                "row.appended" -> {
                    val row = op["row"] as? JsonObject ?: continue
                    // 重复 rowId 说明服务端重发了同一行（重连/快照重叠常见）：按 upsert
                    // 替换，而不是再插一条。否则时间线会出现两条同 key 的行，翻页后
                    // normalize 又会保留「最后一份」，前后行为不一致（10 审计 P2-11）。
                    val existingId = row["rowId"].asLong()
                    if (existingId != null) {
                        val existing = indexOf(existingId)
                        if (existing >= 0) {
                            rows[existing] = row
                            continue
                        }
                    }
                    val position = rows.size
                    rows.add(row)
                    total += 1
                    if (first == null) first = row["rowId"].asLong()
                    // 下标表只补首条，保持「重复 rowId 取首次出现」。
                    row["rowId"].asLong()?.let { id ->
                        index?.let { if (!it.containsKey(id)) it[id] = position }
                    }
                }

                "row.upserted" -> {
                    val row = op["row"] as? JsonObject ?: continue
                    val id = row["rowId"].asLong() ?: continue
                    val position = indexOf(id)
                    if (position >= 0) rows[position] = row
                }

                "row.removed" -> {
                    val from = op["fromRowId"].asLong() ?: continue
                    var removed = 0
                    val iterator = rows.iterator()
                    while (iterator.hasNext()) {
                        if ((iterator.next()["rowId"].asLong() ?: 0L) >= from) {
                            iterator.remove()
                            removed += 1
                        }
                    }
                    val clearsAll = first != null && from <= first
                    total = if (clearsAll) 0 else (total - removed).coerceAtLeast(0)
                    if (clearsAll) first = null
                    // 行的下标整体变了：丢掉旧表，下次查找按新列表重建。
                    index = null
                }

                "row.delta" -> {
                    val id = op["rowId"].asLong() ?: continue
                    val path = op["path"].asString() ?: continue
                    val append = op["append"].asString() ?: ""
                    val position = indexOf(id)
                    if (position >= 0) {
                        rows[position] = appendPath(rows[position], path, append)
                    }
                }

                else -> Unit
            }
        }
        return Window(rows = rows, totalCount = total, firstRowId = first)
    }

    /**
     * 把 [append] 追加到 [path] 指向的字符串字段。
     *
     * `path` 是点分路径（`text` / `inputText` / `output.text` / `summaryText`）。
     * 中间层不存在时按需创建对象。
     */
    fun appendPath(row: JsonObject, path: String, append: String): JsonObject {
        val parts = path.split('.').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return row
        return appendAt(row, parts, append)
    }

    private fun appendAt(node: JsonObject, parts: List<String>, append: String): JsonObject {
        val head = parts.first()
        val rest = parts.drop(1)
        val current = node[head]
        val next: JsonElement = if (rest.isEmpty()) {
            JsonPrimitive((current.asString() ?: "") + append)
        } else {
            val child = current as? JsonObject ?: JsonObject(emptyMap())
            appendAt(child, rest, append)
        }
        return JsonObject(node + (head to next))
    }
}

// ---------------------------------------------------------------------------
// 行字段读取（渲染层共用）
// ---------------------------------------------------------------------------

internal fun JsonElement?.asString(): String? = when (this) {
    is JsonPrimitive -> contentOrNull
    else -> null
}

internal fun JsonElement?.asLong(): Long? = when (this) {
    is JsonPrimitive -> longOrNull
    else -> null
}

internal fun JsonElement?.asDouble(): Double? = when (this) {
    is JsonPrimitive -> contentOrNull?.toDoubleOrNull()
    else -> null
}

internal fun JsonElement?.asBool(): Boolean? = when (this) {
    is JsonPrimitive -> booleanOrNull
    else -> null
}

internal fun JsonElement?.asObj(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asArr(): JsonArray? = this as? JsonArray

/** 行的 `kind`。 */
internal fun JsonObject.kind(): String = this["kind"].asString().orEmpty()

/** 行的 `rowId`。 */
internal fun JsonObject.rowId(): Long = this["rowId"].asLong() ?: 0L

/** 行是否处于流式状态（与网页端 `Knn` 判据一致）。 */
internal fun JsonObject.isStreaming(): Boolean = when (kind()) {
    "assistantText", "reasoning" -> this["state"].asString() == "streaming"
    "toolCall" -> this["backgrounded"].asBool() != true &&
        this["status"].asString() in setOf("inputStreaming", "pendingApproval", "running")
    "subagent" -> this["backgrounded"].asBool() != true && this["status"].asString() == "running"
    "timelineMarker" -> (this["marker"] as? JsonObject)?.get("status").asString() == "running"
    else -> false
}

/** 从 `state.updated` 的 patch 里取需要显示的状态字段。 */
internal fun JsonObject.patchValue(key: String): JsonElement? = this[key]
