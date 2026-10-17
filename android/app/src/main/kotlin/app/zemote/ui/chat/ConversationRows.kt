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
 * 对齐网页端 `parts-DcYGwy2q.js` 的 5 种 `row.*` op（`docs/remote-v4/18` §1.2）：
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

    /** 应用一批 deltas。认不出的 op 静默忽略（与网页端一致）。 */
    fun apply(window: Window, deltas: JsonArray): Window {
        var rows = window.rows
        var total = window.totalCount
        var first = window.firstRowId

        for (element in deltas) {
            val op = element as? JsonObject ?: continue
            when (op["op"].asString()) {
                "row.appended" -> {
                    val row = op["row"] as? JsonObject ?: continue
                    rows = rows + row
                    total += 1
                    if (first == null) first = row["rowId"].asLong()
                }

                "row.upserted" -> {
                    val row = op["row"] as? JsonObject ?: continue
                    val id = row["rowId"].asLong() ?: continue
                    val index = rows.indexOfFirst { it["rowId"].asLong() == id }
                    if (index >= 0) {
                        rows = rows.toMutableList().also { it[index] = row }
                    }
                }

                "row.removed" -> {
                    val from = op["fromRowId"].asLong() ?: continue
                    val kept = rows.filter { (it["rowId"].asLong() ?: 0L) < from }
                    val removed = rows.size - kept.size
                    val clearsAll = first != null && from <= first
                    rows = kept
                    total = if (clearsAll) 0 else (total - removed).coerceAtLeast(0)
                    if (clearsAll) first = null
                }

                "row.delta" -> {
                    val id = op["rowId"].asLong() ?: continue
                    val path = op["path"].asString() ?: continue
                    val append = op["append"].asString() ?: ""
                    val index = rows.indexOfFirst { it["rowId"].asLong() == id }
                    if (index >= 0) {
                        val updated = appendPath(rows[index], path, append)
                        rows = rows.toMutableList().also { it[index] = updated }
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
