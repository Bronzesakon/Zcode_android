package app.zemote.data

import app.zemote.ui.chat.TaskItem
import app.zemote.ui.chat.WorkspaceItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [HomeCache] 的落盘/读取、设备隔离与损坏容错。 */
class HomeCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newCache(): HomeCache = HomeCache(tempFolder.root)

    private fun workspace(key: String): WorkspaceItem = WorkspaceItem(
        key = key,
        path = "/repo/$key",
        label = key,
        kind = "local",
        purpose = "project",
        projectId = "p-$key",
        hostId = "local",
        tasks = listOf(
            TaskItem(
                id = "task-1",
                title = "修首页缓存",
                displayStatus = "running",
                hasBackgroundWork = true,
                updatedAt = 1_700_000_000_000,
                pinned = true,
                workspaceKey = key,
                workspacePath = "/repo/$key",
            ),
        ),
    )

    @Test
    fun save_then_load_roundTripsWorkspacesAndPreviews() {
        val cache = newCache()
        cache.save("dev-1", listOf(workspace("a")), mapOf("task-1" to "正在执行"))

        val loaded = cache.load("dev-1")
        assertNotNull(loaded)
        assertEquals(1, loaded!!.workspaces.size)
        assertEquals("a", loaded.workspaces[0].key)
        assertEquals("running", loaded.workspaces[0].tasks[0].displayStatus)
        assertEquals(true, loaded.workspaces[0].tasks[0].pinned)
        assertEquals("正在执行", loaded.previews["task-1"])
    }

    @Test
    fun load_rejectsDifferentDevice() {
        val cache = newCache()
        cache.save("dev-1", listOf(workspace("a")), emptyMap())

        assertNull(cache.load("dev-2"))
    }

    @Test
    fun save_ignoresEmptyWorkspaceList() {
        val cache = newCache()
        cache.save("dev-1", emptyList(), emptyMap())

        assertNull(cache.load("dev-1"))
    }

    @Test
    fun load_ignoresCorruptFile() {
        val cache = newCache()
        java.io.File(tempFolder.root, "home-cache.json").writeText("not-json")

        assertNull(cache.load("dev-1"))
    }
}
