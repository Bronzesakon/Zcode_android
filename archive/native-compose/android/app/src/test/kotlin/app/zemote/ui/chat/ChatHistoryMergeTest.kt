package app.zemote.ui.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    /** 缓存/旧窗口与快照之间断开时，旧前缀整段丢弃，绝不留永远补不上的洞。 */
    @Test
    fun `drops a non-contiguous old prefix instead of leaving a hole`() {
        val existing = listOf(row(1), row(2), row(3))
        // 会话在离线期间前进到了 100：3 + 1 != 100，中间 4..99 缺页。
        val window = listOf(row(100), row(101))

        val merged = mergeSnapshotRows(existing, window)

        assertEquals(listOf(100L, 101L), ids(merged))
    }

    /** 窗口超限只从最老端丢到目标行数；未超限时原样返回（不复制）。 */
    @Test
    fun `trims the window from the oldest end when over budget`() {
        val rows = (1L..650L).map { row(it) }

        val trimmed = trimWindowRows(rows, maxRows = 600, targetRows = 400)

        assertEquals(400, trimmed.size)
        assertEquals(251L, trimmed.first().rowId())
        assertEquals(650L, trimmed.last().rowId())
        val withinBudget = rows.take(600)
        assertSame(withinBudget, trimWindowRows(withinBudget, maxRows = 600, targetRows = 400))
    }

    // ------------------------------------------------------------------
    // ConversationRows.apply：一批 deltas 一次归约
    // ------------------------------------------------------------------

    /** 一批 deltas 里的 ops 必须一次性生效：追加、替换、截断、忽略未知/缺失行。 */
    @Test
    fun `applies a batch of deltas in one pass`() {
        val window = ConversationRows.Window(
            rows = listOf(row(1), row(2)),
            totalCount = 2,
            firstRowId = 1,
        )
        val deltas = JsonArray(
            listOf(
                buildJsonObject {
                    put("op", "row.delta")
                    put("rowId", 1)
                    put("path", "text")
                    put("append", "!")
                },
                // 窗口里没有 99：整条丢弃（网页同款）。
                buildJsonObject {
                    put("op", "row.upserted")
                    put("row", row(99, "外来"))
                },
                buildJsonObject {
                    put("op", "row.delta")
                    put("rowId", 2)
                    put("path", "text")
                    put("append", "?")
                },
                buildJsonObject {
                    put("op", "row.appended")
                    put("row", row(3))
                },
                buildJsonObject { put("op", "state.updated") },
                buildJsonObject { put("op", "看不懂的 op") },
            ),
        )

        val result = ConversationRows.apply(window, deltas)

        assertEquals(listOf(1L, 2L, 3L), ids(result.rows))
        assertEquals("r1!", result.rows[0]["text"].asString())
        assertEquals("r2?", result.rows[1]["text"].asString())
        assertEquals(3L, result.totalCount)
        assertEquals(1L, result.firstRowId)
    }

    /** 重复 `rowId` 与逐条 `indexOfFirst` 同语义：只命中第一次出现的那行。 */
    @Test
    fun `repeated rowId targets the first occurrence only`() {
        val window = ConversationRows.Window(
            rows = listOf(row(1, "a"), row(1, "b")),
            totalCount = 2,
            firstRowId = 1,
        )
        val deltas = JsonArray(
            listOf(
                buildJsonObject {
                    put("op", "row.delta")
                    put("rowId", 1)
                    put("path", "text")
                    put("append", "!")
                },
            ),
        )

        val result = ConversationRows.apply(window, deltas)

        assertEquals(2, result.rows.size)
        assertEquals("a!", result.rows[0]["text"].asString())
        assertEquals("b", result.rows[1]["text"].asString())
    }

    /**
     * `row.appended` 对已存在的 rowId 按 upsert 处理：重发同一行不能变成两条。
     *
     * 网页端原始归约器是「不去重」，但重连/快照重叠时会重发同一行；出现两条同 key
     * 的行后，`normalizeTimelineRows` 又会只留最后一份——两条路径语义不一致（10 P2-11）。
     */
    @Test
    fun `row appended upserts an existing rowId`() {
        val window = ConversationRows.Window(
            rows = listOf(row(1)),
            totalCount = 1,
            firstRowId = 1,
        )
        val deltas = JsonArray(
            listOf(
                buildJsonObject { put("op", "row.appended"); put("row", row(1, "dup")) },
            ),
        )

        val result = ConversationRows.apply(window, deltas)

        assertEquals(listOf(1L), ids(result.rows))
        assertEquals("dup", result.rows.single()["text"].asString())
        assertEquals(1L, result.totalCount)
    }

    /** 批量追加/修改同一行：一次归约内按序生效，行数不变。 */
    @Test
    fun `many deltas on the same row are applied in order`() {
        val rows = (1L..50L).map { row(it) }
        val window = ConversationRows.Window(rows = rows, totalCount = 50, firstRowId = 1)
        val deltas = JsonArray(
            (1..200).map {
                buildJsonObject {
                    put("op", "row.delta")
                    put("rowId", 25)
                    put("path", "text")
                    put("append", "x")
                }
            },
        )

        val result = ConversationRows.apply(window, deltas)

        assertEquals(50, result.rows.size)
        assertEquals("r25" + "x".repeat(200), result.rows[24]["text"].asString())
        assertEquals("r24", result.rows[23]["text"].asString())
        assertEquals("r26", result.rows[25]["text"].asString())
    }

    /** `row.removed` 按 id 截断（不是删一条）；截到会话首行时总数与 firstRowId 一起归零。 */
    @Test
    fun `row removed truncates by rowId`() {
        val window = ConversationRows.Window(
            rows = listOf(row(1), row(2), row(3)),
            totalCount = 3,
            firstRowId = 1,
        )

        val partial = ConversationRows.apply(
            window,
            JsonArray(
                listOf(
                    buildJsonObject { put("op", "row.removed"); put("fromRowId", 2) },
                ),
            ),
        )
        assertEquals(listOf(1L), ids(partial.rows))
        assertEquals(1L, partial.totalCount)
        assertEquals(1L, partial.firstRowId)

        val cleared = ConversationRows.apply(
            window,
            JsonArray(
                listOf(
                    buildJsonObject { put("op", "row.removed"); put("fromRowId", 1) },
                ),
            ),
        )
        assertTrue(cleared.rows.isEmpty())
        assertEquals(0L, cleared.totalCount)
        assertNull(cleared.firstRowId)
    }

    // ------------------------------------------------------------------
    // 翻页口径（与网页 `canLoadOlder` / `hasMore` 对齐）
    // ------------------------------------------------------------------

    @Test
    fun `canLoadOlder 只由窗口首行与会话首行决定`() {
        assertTrue(canLoadOlderAt(listOf(row(5)), firstRowId = 1, historyExhausted = false))
        assertFalse(canLoadOlderAt(listOf(row(1)), firstRowId = 1, historyExhausted = false))
        assertFalse(canLoadOlderAt(emptyList(), firstRowId = 1, historyExhausted = false))
        assertFalse(canLoadOlderAt(listOf(row(5)), firstRowId = null, historyExhausted = false))
        // 已确认取完时压平，不再请求。
        assertFalse(canLoadOlderAt(listOf(row(5)), firstRowId = 1, historyExhausted = true))
    }

    @Test
    fun `只有 hasMore 为假或空页未声明还有时才算取完`() {
        assertTrue(isOlderExhausted(hasMore = false, returnedCount = 60))
        assertTrue(isOlderExhausted(hasMore = false, returnedCount = 0))
        assertTrue(isOlderExhausted(hasMore = null, returnedCount = 0))
        // 部分页（不足一页）绝不等于取完：旧实现会在这里永久锁死翻页。
        assertFalse(isOlderExhausted(hasMore = true, returnedCount = 5))
        assertFalse(isOlderExhausted(hasMore = null, returnedCount = 5))
        assertFalse(isOlderExhausted(hasMore = true, returnedCount = 0))
    }
}
