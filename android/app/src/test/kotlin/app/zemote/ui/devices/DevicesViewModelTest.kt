package app.zemote.ui.devices

import app.zemote.engine.RelayState
import app.zemote.ui.FakeDeviceRepository
import app.zemote.ui.FakeEnginePort
import app.zemote.ui.MainDispatcherRule
import app.zemote.ui.deviceRecord
import app.zemote.ui.engineError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 设备列表的状态映射：加载 / 就绪 / 失败三态 + 打开、删除、重命名三条动作路径。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DevicesViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    // ------------------------------------------------------------------
    // 三态
    // ------------------------------------------------------------------

    @Test
    fun `读到列表后从加载态进入就绪态`() = runTest(mainRule.dispatcher) {
        val repository = FakeDeviceRepository(initialDevices = listOf(deviceRecord()))

        val viewModel = DevicesViewModel(repository = repository, engine = FakeEnginePort())
        advanceUntilIdle()

        assertFalse(viewModel.state.value.loading)
        assertEquals(listOf("d1"), viewModel.state.value.devices.map { it.id })
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `空列表也是就绪态`() = runTest(mainRule.dispatcher) {
        val viewModel = DevicesViewModel(FakeDeviceRepository(), FakeEnginePort())
        advanceUntilIdle()

        assertFalse(viewModel.state.value.loading)
        assertTrue(viewModel.state.value.devices.isEmpty())
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `读盘失败收敛成错误态而不是抛异常`() = runTest(mainRule.dispatcher) {
        val repository = FakeDeviceRepository().apply { readFailure = IllegalStateException("磁盘坏了") }

        val viewModel = DevicesViewModel(repository, FakeEnginePort())
        advanceUntilIdle()

        assertFalse(viewModel.state.value.loading)
        assertEquals("IllegalStateException: 磁盘坏了", viewModel.state.value.error)
    }

    @Test
    fun `retry 重新订阅并在恢复后回到就绪态`() = runTest(mainRule.dispatcher) {
        val repository = FakeDeviceRepository(initialDevices = listOf(deviceRecord()))
            .apply { readFailure = IllegalStateException("临时故障") }
        val viewModel = DevicesViewModel(repository, FakeEnginePort())
        advanceUntilIdle()
        assertTrue(viewModel.state.value.error != null)

        repository.readFailure = null
        viewModel.retry()
        advanceUntilIdle()

        assertNull(viewModel.state.value.error)
        assertFalse(viewModel.state.value.loading)
        assertEquals(listOf("d1"), viewModel.state.value.devices.map { it.id })
    }

    @Test
    fun `dismissError 清掉错误条`() = runTest(mainRule.dispatcher) {
        val repository = FakeDeviceRepository().apply { readFailure = IllegalStateException("x") }
        val viewModel = DevicesViewModel(repository, FakeEnginePort())
        advanceUntilIdle()

        viewModel.dismissError()

        assertNull(viewModel.state.value.error)
    }

    // ------------------------------------------------------------------
    // 当前设备与 relay 状态
    // ------------------------------------------------------------------

    @Test
    fun `当前设备从持久化流映射进状态`() = runTest(mainRule.dispatcher) {
        val repository = FakeDeviceRepository(
            initialDevices = listOf(deviceRecord(id = "d1"), deviceRecord(id = "d2", sid = "s2")),
            initialCurrentId = "d2",
        )
        val viewModel = DevicesViewModel(repository, FakeEnginePort())
        advanceUntilIdle()

        assertEquals("d2", viewModel.state.value.currentDeviceId)
        assertEquals("d2", viewModel.state.value.currentDevice?.id)
    }

    @Test
    fun `relay 状态映射进状态`() = runTest(mainRule.dispatcher) {
        val engine = FakeEnginePort()
        val viewModel = DevicesViewModel(FakeDeviceRepository(), engine)
        advanceUntilIdle()
        assertEquals(RelayState.Idle, viewModel.state.value.relayState)

        engine.relayStateFlow.value = RelayState.Paired
        advanceUntilIdle()

        assertEquals(RelayState.Paired, viewModel.state.value.relayState)
    }

    // ------------------------------------------------------------------
    // 打开设备
    // ------------------------------------------------------------------

    @Test
    fun `打开设备会设为当前设备并建会话`() = runTest(mainRule.dispatcher) {
        val repository = FakeDeviceRepository(initialDevices = listOf(deviceRecord()))
        val engine = FakeEnginePort()
        val viewModel = DevicesViewModel(repository, engine)
        advanceUntilIdle()

        viewModel.openDevice("d1")
        advanceUntilIdle()

        assertEquals(listOf("d1"), repository.setCurrentIds)
        assertEquals(listOf(deviceRecord().remoteUrl), engine.ensuredUrls)
    }

    @Test
    fun `凭据不可用的设备只给提示不建会话`() = runTest(mainRule.dispatcher) {
        val broken = deviceRecord(id = "d1", remoteUrl = null)
        val repository = FakeDeviceRepository(initialDevices = listOf(broken))
        val engine = FakeEnginePort()
        val viewModel = DevicesViewModel(repository, engine)
        advanceUntilIdle()

        viewModel.openDevice("d1")
        advanceUntilIdle()

        assertTrue(engine.ensuredUrls.isEmpty())
        assertTrue(viewModel.state.value.notice!!.contains("重新配对"))
    }

    @Test
    fun `打开不存在的设备是空操作`() = runTest(mainRule.dispatcher) {
        val repository = FakeDeviceRepository(initialDevices = listOf(deviceRecord()))
        val engine = FakeEnginePort()
        val viewModel = DevicesViewModel(repository, engine)
        advanceUntilIdle()

        viewModel.openDevice("不存在")
        advanceUntilIdle()

        assertTrue(repository.setCurrentIds.isEmpty())
        assertTrue(engine.ensuredUrls.isEmpty())
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `设为当前设备失败收敛成错误态`() = runTest(mainRule.dispatcher) {
        val repository = FakeDeviceRepository(initialDevices = listOf(deviceRecord()))
            .apply { writeFailure = engineError(kind = "invalidState", message = "未知设备 id") }
        val engine = FakeEnginePort()
        val viewModel = DevicesViewModel(repository, engine)
        advanceUntilIdle()

        viewModel.openDevice("d1")
        advanceUntilIdle()

        assertEquals("当前状态不允许这个操作：未知设备 id", viewModel.state.value.error)
        assertTrue(engine.ensuredUrls.isEmpty())
    }

    // ------------------------------------------------------------------
    // 删除 / 重命名
    // ------------------------------------------------------------------

    @Test
    fun `删除设备后给一次性提示`() = runTest(mainRule.dispatcher) {
        val repository = FakeDeviceRepository(initialDevices = listOf(deviceRecord()))
        val viewModel = DevicesViewModel(repository, FakeEnginePort())
        advanceUntilIdle()

        viewModel.removeDevice("d1")
        advanceUntilIdle()

        assertEquals(listOf("d1"), repository.removedIds)
        assertEquals("已删除设备", viewModel.state.value.notice)

        viewModel.dismissNotice()
        assertNull(viewModel.state.value.notice)
    }

    @Test
    fun `删除不存在的设备也收敛成提示`() = runTest(mainRule.dispatcher) {
        val viewModel = DevicesViewModel(FakeDeviceRepository(), FakeEnginePort())
        advanceUntilIdle()

        viewModel.removeDevice("ghost")
        advanceUntilIdle()

        assertEquals("设备已不存在（可能已被删除）", viewModel.state.value.notice)
    }

    @Test
    fun `删除失败收敛成错误态`() = runTest(mainRule.dispatcher) {
        val repository = FakeDeviceRepository(initialDevices = listOf(deviceRecord()))
            .apply { writeFailure = IllegalStateException("列表数据损坏") }
        val viewModel = DevicesViewModel(repository, FakeEnginePort())
        advanceUntilIdle()

        viewModel.removeDevice("d1")
        advanceUntilIdle()

        assertEquals("IllegalStateException: 列表数据损坏", viewModel.state.value.error)
    }

    @Test
    fun `重命名成功给提示`() = runTest(mainRule.dispatcher) {
        val repository = FakeDeviceRepository(initialDevices = listOf(deviceRecord()))
        val viewModel = DevicesViewModel(repository, FakeEnginePort())
        advanceUntilIdle()

        viewModel.renameDevice("d1", "书房电脑")
        advanceUntilIdle()

        assertEquals(listOf("d1" to "书房电脑"), repository.renames)
        assertEquals("已重命名", viewModel.state.value.notice)
    }

    @Test
    fun `重命名成同名不算改动`() = runTest(mainRule.dispatcher) {
        val repository = FakeDeviceRepository(initialDevices = listOf(deviceRecord(name = "桌面")))
        val viewModel = DevicesViewModel(repository, FakeEnginePort())
        advanceUntilIdle()

        viewModel.renameDevice("d1", "桌面")
        advanceUntilIdle()

        assertEquals("名字没有变化（空白名字会被忽略）", viewModel.state.value.notice)
    }
}
