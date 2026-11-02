package app.zemote.ui.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 快照窗口合并规则（`docs/CONVERSATION-LIFECYCLE.md` §4）。
 *
 * 已生成的行必须固定：只有窗口内的行以快照为准，窗口之前的历史（缓存/翻页）保留。
 */
class ChatHistoryMergeTest {

    private fun row(id: Long, text: String = "r$id"): JsonObject = buildJsonObject {
        put("rowId", id)
        put("kind", "assistantText")
        put("text", text)
    }

    private fun ids(rows: List<JsonObject>): List<Long> = rows.map { it.rowId() }

    @Test
    fun `keeps older rows outside the snapshot window`() {
        val existing = listOf(row(1), row(2), row(3))
        val window = listOf(row(3), row(4))

        val merged = mergeSnapshotRows(existing, window)

        assertEquals(listOf(1L, 2L, 3L, 4L), ids(merged))
    }

    @Test
    fun `window wins for the same rowId`() {
        val existing = listOf(row(2, "old"), row(3, "old"))
        val window = listOf(row(2, "new"))

        val merged = mergeSnapshotRows(existing, window)

        assertEquals(listOf(2L), ids(merged))
        assertEquals("new", merged.single()["text"].asString())
    }

    @Test
    fun `result is sorted by rowId`() {
        val existing = listOf(row(3), row(1))
        val window = listOf(row(6), row(4))

        val merged = mergeSnapshotRows(existing, window)

        assertEquals(listOf(1L, 3L, 4L, 6L), ids(merged))
    }

    @Test
    fun `empty window keeps existing rows`() {
        val existing = listOf(row(1), row(2))

        val merged = mergeSnapshotRows(existing, emptyList())

        assertEquals(listOf(1L, 2L), ids(merged))
    }
}
