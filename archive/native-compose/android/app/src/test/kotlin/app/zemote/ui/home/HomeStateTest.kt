package app.zemote.ui.home

import app.zemote.ui.chat.TaskItem
import app.zemote.ui.chat.WorkspaceItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 首页项目树的纯逻辑：分组/列表投影、置顶抽取、计数、视图切换。
 *
 * 不依赖 Compose / ViewModel；[HomeUiState] 直接构造。
 */
class HomeStateTest {

    @After
    fun tearDown() {
        HomeViewPreference.reset()
    }

    // ------------------------------------------------------------------
    // 置顶抽取与任务计数
    // ------------------------------------------------------------------

    @Test
    fun `置顶任务抽到独立区 项目内不重复`() {
        val pinned = task("pinned", updatedAt = 900, pinned = true, unreadAt = 800)
        val normal = task("normal", updatedAt = 700)
        val archivedPinned = task("archived-pinned", updatedAt = 600, pinned = true, archived = true)
        val state = HomeUiState(
            workspaces = listOf(
                workspace("/p/one", listOf(pinned, normal, archivedPinned)),
            ),
        )

        val pinnedEntries = state.pinnedEntries
        assertEquals(listOf("pinned"), pinnedEntries.map { it.task.id })
        assertEquals("/p/one", pinnedEntries.single().workspace.key)

        val section = state.projectSections.single()
        assertTrue(
            "置顶任务的未读不该让项目再展开一次（它已在独立区）",
            section.tasks.isEmpty(),
        )
        assertTrue("项目自己的未读点仍要看得到", section.hasUnread)
        assertFalse("置顶任务不能在项目内再出现", section.tasks.any { it.pinned })
        assertTrue("归档的置顶任务不进入置顶区", state.pinnedEntries.none { it.task.archived })
    }

    @Test
    fun `项目任务数与未读数按网页口径 不含归档但含置顶`() {
        val state = HomeUiState(
            workspaces = listOf(
                workspace(
                    "/p/one",
                    listOf(
                        task("a", unreadAt = 1),
                        task("b", pinned = true, unreadAt = 2),
                        task("c", archived = true, unreadAt = 3),
                    ),
                ),
                workspace("/p/two", listOf(task("d", unreadAt = 4))),
            ),
        )

        val one = state.projectSections.first { it.workspace.key == "/p/one" }
        assertEquals("a + b（c 归档不计）", 2, one.totalTasks)
        assertEquals(2, one.unreadCount)
        assertTrue(one.hasUnread)

        // a + b + d（c 归档不计）
        assertEquals(3, state.totalTaskCount)
        assertEquals(3, state.unreadCount)
    }

    @Test
    fun `conversation 伪工作区不进入项目树`() {
        val state = HomeUiState(
            workspaces = listOf(
                workspace("/p/one", listOf(task("a"))),
                workspace("/p/conv", listOf(task("conv")), purpose = "conversation"),
            ),
        )

        assertEquals(listOf("/p/one"), state.projectSections.map { it.workspace.key })
        assertEquals(listOf("a"), state.timelineEntries.map { it.task.id })
    }

    // ------------------------------------------------------------------
    // 分组视图
    // ------------------------------------------------------------------

    @Test
    fun `分组视图按项目分组合并任务并按最近更新时间排序`() {
        val older = workspace("/p/older", listOf(task("o1", updatedAt = 100)))
        val newer = workspace(
            "/p/newer",
            listOf(task("n1", updatedAt = 300), task("n2", updatedAt = 200)),
        )
        val state = HomeUiState(
            workspaces = listOf(older, newer),
            // 分组视图的展开是会话态：显式展开才能看到任务。
            expanded = setOf("/p/newer", "/p/older"),
        )

        assertEquals(
            listOf("/p/newer", "/p/older"),
            state.projectSections.map { it.workspace.key },
        )
        assertEquals(
            listOf("n1", "n2"),
            state.projectSections.first().tasks.map { it.id },
        )
    }

    @Test
    fun `有未读自动展开只显示未读 显式展开显示全部`() {
        val unread = task("unread", updatedAt = 200, unreadAt = 150)
        val read = task("read", updatedAt = 100)
        val ws = workspace("/p/one", listOf(unread, read))
        val state = HomeUiState(workspaces = listOf(ws))

        val auto = state.projectSections.single()
        assertTrue(auto.expanded)
        assertEquals(listOf("unread"), auto.tasks.map { it.id })
        assertTrue(auto.canShowAll)

        val expanded = state.copy(expanded = setOf("/p/one"))
        val all = expanded.projectSections.single()
        assertEquals(listOf("unread", "read"), all.tasks.map { it.id })
        assertFalse(all.canShowAll)
    }

    @Test
    fun `手动折叠后即使有未读也不自动展开`() {
        val ws = workspace("/p/one", listOf(task("a", unreadAt = 1)))
        val state = HomeUiState(workspaces = listOf(ws), collapsed = setOf("/p/one"))

        assertFalse(state.projectSections.single().expanded)
        assertTrue(state.projectSections.single().tasks.isEmpty())
    }

    // ------------------------------------------------------------------
    // 列表视图
    // ------------------------------------------------------------------

    @Test
    fun `列表视图跨项目按时间倒序 且排除置顶与归档`() {
        val state = HomeUiState(
            workspaces = listOf(
                workspace(
                    "/p/one",
                    listOf(
                        task("pinned", updatedAt = 999, pinned = true),
                        task("archived", updatedAt = 998, archived = true),
                        task("a", updatedAt = 10),
                    ),
                ),
                workspace("/p/two", listOf(task("b", updatedAt = 20))),
            ),
        )

        assertEquals(listOf("b", "a"), state.timelineEntries.map { it.task.id })
        assertEquals(listOf("/p/two", "/p/one"), state.timelineEntries.map { it.workspace.key })
    }

    @Test
    fun `列表视图按今天 昨天 更早分节`() {
        val zone = ZoneId.systemDefault()
        val now = LocalDate.of(2026, 11, 2).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        fun at(daysAgo: Long): Long =
            LocalDate.of(2026, 11, 2).minusDays(daysAgo).atTime(10, 0)
                .atZone(zone).toInstant().toEpochMilli()

        val state = HomeUiState(
            workspaces = listOf(
                workspace(
                    "/p/one",
                    listOf(
                        task("today", updatedAt = at(0)),
                        task("yesterday", updatedAt = at(1)),
                        task("older", updatedAt = at(9)),
                    ),
                ),
            ),
        )

        val days = state.timelineDays(now = now)
        assertEquals(listOf("今天", "昨天", "更早"), days.map { it.label })
        assertEquals(listOf("today"), days[0].entries.map { it.task.id })
        assertEquals(listOf("yesterday"), days[1].entries.map { it.task.id })
        assertEquals(listOf("older"), days[2].entries.map { it.task.id })
    }

    // ------------------------------------------------------------------
    // 视图切换与离线标签
    // ------------------------------------------------------------------

    @Test
    fun `视图切换在分组与列表之间往返`() {
        assertEquals(HomeView.Timeline, HomeView.Grouped.toggled())
        assertEquals(HomeView.Grouped, HomeView.Timeline.toggled())

        HomeViewPreference.reset()
        assertEquals(HomeView.Grouped, HomeViewPreference.view.value)

        HomeViewPreference.toggle()
        assertEquals(HomeView.Timeline, HomeViewPreference.view.value)

        HomeViewPreference.select(HomeView.Grouped)
        assertEquals(HomeView.Grouped, HomeViewPreference.view.value)
    }

    @Test
    fun `离线标签依据连接错误或不可用连接态`() {
        assertTrue(workspace("/p/a", emptyList(), lastError = "boom").isOffline())
        assertTrue(workspace("/p/b", emptyList(), connectionState = "offline").isOffline())
        assertTrue(workspace("/p/c", emptyList(), connectionState = "Disconnected").isOffline())
        assertFalse(workspace("/p/d", emptyList(), connectionState = "online").isOffline())
        assertFalse(workspace("/p/e", emptyList()).isOffline())
    }

    // ------------------------------------------------------------------
    // 测试夹具
    // ------------------------------------------------------------------

    private fun workspace(
        key: String,
        tasks: List<TaskItem>,
        kind: String = "local",
        purpose: String = "project",
        connectionState: String? = null,
        lastError: String? = null,
    ) = WorkspaceItem(
        key = key,
        path = key,
        label = key.substringAfterLast('/').ifBlank { key },
        kind = kind,
        purpose = purpose,
        connectionState = connectionState,
        lastError = lastError,
        tasks = tasks,
    )

    private fun task(
        id: String,
        updatedAt: Long? = null,
        pinned: Boolean = false,
        archived: Boolean = false,
        unreadAt: Long? = null,
        title: String = "T-$id",
    ) = TaskItem(
        id = id,
        title = title,
        updatedAt = updatedAt,
        pinned = pinned,
        archived = archived,
        unreadAt = unreadAt,
    )
}
