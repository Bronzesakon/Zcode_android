package app.zemote.data

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [ChatCache] schema v2 的读写、截断、损坏与旧格式兼容测试。 */
class ChatCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    private fun newCache(): ChatCache = ChatCache(tempFolder.root)

    private fun row(id: Int): JsonObject = buildJsonObject {
        put("id", id)
        put("text", "message-$id")
    }

    /** 与 [ChatCache] 的 fileFor 保持一致，用于直接写/读缓存文件。 */
    private fun cacheFile(workspaceKey: String, taskId: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$workspaceKey\u0000$taskId".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return File(tempFolder.root, "$digest.json")
    }

    @Test
    fun prune_deletesCacheOlderThanThreeDays() {
        val cache = newCache()
        cache.saveConversation("ws", "old", listOf(row(1)), null, null)
        cache.saveConversation("ws", "recent", listOf(row(2)), null, null)

        val fourDaysAgo = System.currentTimeMillis() - 4L * 24 * 60 * 60 * 1000
        assertTrue(cacheFile("ws", "old").setLastModified(fourDaysAgo))

        // 任意一次写入触发淘汰
        cache.saveConversation("ws", "fresh", listOf(row(3)), null, null)

        assertNull(cache.loadConversation("ws", "old"))
        assertNotNull(cache.loadConversation("ws", "recent"))
        assertNotNull(cache.loadConversation("ws", "fresh"))
    }

    @Test
    fun prune_keepsFilesYoungerThanThreeDays() {
        val cache = newCache()
        cache.saveConversation("ws", "two-days", listOf(row(1)), null, null)
        val twoDaysAgo = System.currentTimeMillis() - 2L * 24 * 60 * 60 * 1000
        assertTrue(cacheFile("ws", "two-days").setLastModified(twoDaysAgo))

        cache.saveConversation("ws", "fresh", listOf(row(2)), null, null)

        assertNotNull(cache.loadConversation("ws", "two-days"))
        assertNotNull(cache.loadConversation("ws", "fresh"))
    }

    @Test
    fun roundTrip_preservesRowsAndMetadata() {
        val cache = newCache()
        val rows = listOf(row(1), row(2), row(3))

        cache.saveConversation("ws-1", "task-1", rows, logEpoch = "epoch-a", firstRowId = 42L)
        val loaded = cache.loadConversation("ws-1", "task-1")

        assertNotNull(loaded)
        assertEquals(rows, loaded!!.rows)
        assertEquals("epoch-a", loaded.logEpoch)
        assertEquals(42L, loaded.firstRowId)
        assertTrue("savedAt 应为正数", loaded.savedAt > 0L)
        // 旧 API 委托新实现
        assertEquals(rows, cache.load("ws-1", "task-1"))
    }

    @Test
    fun save_writesSchemaVersionTwoFile() {
        val cache = newCache()
        cache.saveConversation("ws", "t", listOf(row(1)), "e", 1L)

        val root = json.parseToJsonElement(cacheFile("ws", "t").readText()) as JsonObject
        assertEquals(2, (root["version"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toInt())
        assertEquals("ws", (root["workspaceKey"] as? kotlinx.serialization.json.JsonPrimitive)?.content)
        assertEquals("t", (root["taskId"] as? kotlinx.serialization.json.JsonPrimitive)?.content)
    }

    @Test
    fun save_keepsOnlyTailWindow() {
        val cache = newCache()
        val rows = (0 until 5005).map { row(it) }

        cache.saveConversation("ws", "big", rows, null, null)
        val loaded = cache.loadConversation("ws", "big")

        assertNotNull(loaded)
        // 只缓存尾部窗口：完整历史留在服务端，滚动时 rowsRange 按需拉。
        assertEquals(400, loaded!!.rows.size)
        assertEquals(rows.takeLast(400), loaded.rows)
    }

    @Test
    fun remove_deletesCachedConversation() {
        val cache = newCache()
        cache.saveConversation("ws", "gone", listOf(row(1)), "e", 1L)
        assertNotNull(cache.loadConversation("ws", "gone"))

        cache.remove("ws", "gone")

        assertNull(cache.loadConversation("ws", "gone"))
        assertTrue(cache.load("ws", "gone").isEmpty())
        assertTrue(!cacheFile("ws", "gone").exists())
    }

    @Test
    fun corruptJson_returnsNullAndEmptyList() {
        val cache = newCache()
        cacheFile("ws", "bad").apply {
            parentFile.mkdirs()
            writeText("{ this is not json")
        }

        assertNull(cache.loadConversation("ws", "bad"))
        assertTrue(cache.load("ws", "bad").isEmpty())
    }

    @Test
    fun legacyFormat_withOnlyRows_isReadable() {
        val cache = newCache()
        cacheFile("ws", "v1").apply {
            parentFile.mkdirs()
            writeText(
                buildJsonObject {
                    put("version", 1)
                    put("workspaceKey", "ws")
                    put("taskId", "v1")
                    put("savedAt", 1234L)
                    put("rows", JsonArray(listOf(row(7), row(8))))
                }.toString(),
            )
        }

        val loaded = cache.loadConversation("ws", "v1")

        assertNotNull(loaded)
        assertEquals(listOf(row(7), row(8)), loaded!!.rows)
        assertNull(loaded.logEpoch)
        assertNull(loaded.firstRowId)
        assertEquals(1234L, loaded.savedAt)
        assertEquals(listOf(row(7), row(8)), cache.load("ws", "v1"))
    }

    @Test
    fun legacyFormat_withoutVersionAndMetadata_isReadable() {
        val cache = newCache()
        cacheFile("ws", "bare").apply {
            parentFile.mkdirs()
            writeText("""{"rows":[{"id":1,"text":"message-1"}]}""")
        }

        val loaded = cache.loadConversation("ws", "bare")

        assertNotNull(loaded)
        assertEquals(listOf(row(1)), loaded!!.rows)
        assertNull(loaded.logEpoch)
        assertNull(loaded.firstRowId)
    }

    /**
     * 写入前按字节收缩：单行很大的历史（工具输出）不能写出一个下次进页会被丢弃的超限文件。
     *
     * 5 × 2MB 文本 > 6MiB 上限；必须还能读回、且只剩尾部若干行。
     */
    @Test
    fun oversizedPayload_isTrimmedByBytesAndStillReadable() {
        val cache = newCache()
        val blob = "x".repeat(2 * 1024 * 1024)
        val rows = (1..5).map { id ->
            buildJsonObject {
                put("rowId", id)
                put("kind", "toolCall")
                put("text", blob)
            }
        }

        cache.saveConversation("ws", "big", rows, logEpoch = "e", firstRowId = 1L)

        val file = cacheFile("ws", "big")
        assertTrue("缓存文件不应超过读取上限", file.length() <= 6L * 1024 * 1024)
        val loaded = cache.loadConversation("ws", "big")
        assertNotNull(loaded)
        assertTrue("只保留尾部行", loaded!!.rows.size < rows.size)
        assertEquals("5", loaded.rows.last()["rowId"].toString())
    }
}
