package app.zemote.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导航状态机：纯函数，无 Android 依赖，所以这里逐条覆盖返回栈的边界。
 */
class NavStateTest {

    // ------------------------------------------------------------------
    // 根与不变式
    // ------------------------------------------------------------------

    @Test
    fun `of 默认根是主界面`() {
        val state = NavState.of()
        assertEquals(Route.Main(), state.current)
        assertEquals(1, state.depth)
        assertFalse(state.canGoBack)
    }

    @Test
    fun `of 可以指定别的根`() {
        val state = NavState.of(Route.Main(MainTab.SETTINGS))
        assertEquals(Route.Main(MainTab.SETTINGS), state.current)
        assertFalse(state.canGoBack)
    }

    @Test
    fun `空栈被拒绝`() {
        assertThrows(IllegalArgumentException::class.java) { NavState(emptyList()) }
    }

    // ------------------------------------------------------------------
    // push
    // ------------------------------------------------------------------

    @Test
    fun `push 把路由压到栈顶`() {
        val state = NavState.of().push(Route.Pairing)
        assertEquals(Route.Pairing, state.current)
        assertEquals(listOf(Route.Main(), Route.Pairing), state.stack)
        assertEquals(2, state.depth)
        assertTrue(state.canGoBack)
    }

    @Test
    fun `push 不修改原状态`() {
        val root = NavState.of()
        val pushed = root.push(Route.AboutUpdate)
        assertEquals(listOf<Route>(Route.Main()), root.stack)
        assertNotEquals(root, pushed)
    }

    @Test
    fun `连续 push 同一路由只入栈一次（防连点）`() {
        val state = NavState.of().push(Route.Pairing).push(Route.Pairing)
        assertEquals(listOf(Route.Main(), Route.Pairing), state.stack)
    }

    @Test
    fun `参数不同的同一路由照常入栈`() {
        val state = NavState.of()
            .push(Route.Chat(deviceId = "d1", workspaceKey = "w1"))
            .push(Route.Chat(deviceId = "d1", workspaceKey = "w2"))
        assertEquals(3, state.depth)
    }

    @Test
    fun `同参数的路由视为同一路由`() {
        val state = NavState.of()
            .push(Route.Chat(deviceId = "d1", workspaceKey = "w1"))
            .push(Route.Chat(deviceId = "d1", workspaceKey = "w1"))
        assertEquals(2, state.depth)
    }

    @Test
    fun `设备不同的对话是不同路由`() {
        val a = NavState.of().push(Route.Chat(deviceId = "d1"))
        val b = a.push(Route.Chat(deviceId = "d2"))
        assertEquals(3, b.depth)
    }

    // ------------------------------------------------------------------
    // pop
    // ------------------------------------------------------------------

    @Test
    fun `pop 弹掉栈顶回到上一屏`() {
        val state = NavState.of()
            .push(Route.Pairing)
            .push(Route.SettingsSection("general"))
            .pop()
        assertEquals(Route.Pairing, state.current)
        assertEquals(2, state.depth)
        assertTrue(state.canGoBack)
    }

    @Test
    fun `根上 pop 是空操作（返回原对象，不抛）`() {
        val root = NavState.of()
        val popped = root.pop()
        assertSame(root, popped)
        assertEquals(Route.Main(), popped.current)
        assertFalse(popped.canGoBack)
    }

    @Test
    fun `反复 pop 到根后保持稳定`() {
        val state = NavState.of()
            .push(Route.Pairing)
            .push(Route.SettingsSection("general"))
            .pop()
            .pop()
            .pop()
            .pop()
        assertEquals(listOf<Route>(Route.Main()), state.stack)
    }

    @Test
    fun `push 后 pop 回到原状态`() {
        val root = NavState.of()
        assertEquals(root, root.push(Route.AboutUpdate).pop())
    }

    // ------------------------------------------------------------------
    // replaceRoot
    // ------------------------------------------------------------------

    @Test
    fun `replaceRoot 把整条栈换成单条`() {
        val state = NavState.of()
            .push(Route.Pairing)
            .push(Route.SettingsSection("general"))
            .replaceRoot(Route.Main(MainTab.HOME))
        assertEquals(listOf<Route>(Route.Main(MainTab.HOME)), state.stack)
        assertFalse(state.canGoBack)
    }

    @Test
    fun `replaceRoot 可以换成非主界面的根`() {
        val state = NavState.of()
            .push(Route.SettingsSection("general"))
            .replaceRoot(Route.Chat(deviceId = "d9"))
        assertEquals(listOf<Route>(Route.Chat(deviceId = "d9")), state.stack)
        assertEquals(Route.Chat(deviceId = "d9"), state.current)
    }

    @Test
    fun `replaceRoot 到当前路由也会丢掉历史`() {
        val state = NavState.of()
            .push(Route.SettingsSection("general"))
            .replaceRoot(Route.Main(MainTab.SETTINGS))
        assertEquals(1, state.depth)
        assertEquals(Route.Main(MainTab.SETTINGS), state.current)
    }

    // ------------------------------------------------------------------
    // NavViewModel（跨配置变更的持有者）
    // ------------------------------------------------------------------

    @Test
    fun `NavViewModel 暴露状态并转发三个动作`() {
        val nav = NavViewModel()
        assertEquals(NavState.of(), nav.state.value)

        nav.push(Route.Chat(deviceId = "d1"))
        assertEquals(Route.Chat(deviceId = "d1"), nav.state.value.current)

        nav.push(Route.SettingsSection("general"))
        assertEquals(3, nav.state.value.depth)

        nav.pop()
        assertEquals(Route.Chat(deviceId = "d1"), nav.state.value.current)

        nav.replaceRoot(Route.Main())
        assertEquals(NavState.of(), nav.state.value)
    }

    @Test
    fun `NavViewModel 可以带初始状态构造`() {
        val nav = NavViewModel(NavState.of(Route.Pairing))
        assertEquals(Route.Pairing, nav.state.value.current)
    }
}
