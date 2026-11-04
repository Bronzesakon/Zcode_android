package app.zemote.ui.devices

import app.zemote.data.RelayStatus
import app.zemote.engine.EngineEvent
import app.zemote.engine.RelayState
import app.zemote.ui.FakeDeviceRepository
import app.zemote.ui.FakeEnginePort
import app.zemote.ui.RelayFailureWrite
import app.zemote.ui.RelayStatusWrite
import app.zemote.ui.deviceRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RelayStatusWriter]：把引擎的 relay 状态回写进当前设备记录。
 *
 * 三条纪律各有一条测试：只在**真的变化**时写、`lastConnectedAt` 只在**进入 paired** 时更新、
 * 写盘失败**吞掉并记日志**（不否定连接状态）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayStatusWriterTest {

    private val d1 = deviceRecord(id = "d1", lastRelayStatus = RelayStatus.IDLE)
    private val d2 = deviceRecord(id = "d2", sortKey = 2L)

    private fun writer(
        repository: FakeDeviceRepository,
        engine: FakeEnginePort,
        now: () -> Long = { 1_700_000_500_000L },
        log: MutableList<String> = mutableListOf(),
    ): RelayStatusWriter = RelayStatusWriter(
        repository = repository,
        engine = engine,
        now = now,
        log = { log += it },
    )

    @Test
    fun `只在状态真的变化时写盘`() = runTest {
        val repository = FakeDeviceRepository(initialDevices = listOf(d1), initialCurrentId = "d1")
        val engine = FakeEnginePort()
        val writer = writer(repository, engine)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { writer.watch() }
        advanceUntilIdle()

        // 启动时盘上是 idle、引擎也是 idle ⇒ 一次都不写
        assertTrue("启动时不该白写一次：${repository.relayStatusWrites}", repository.relayStatusWrites.isEmpty())

        engine.relayStateFlow.value = RelayState.Connecting
        advanceUntilIdle()
        engine.relayStateFlow.value = RelayState.Authenticating
        advanceUntilIdle()
        // 同一状态重复发射：状态没变，不写
        engine.relayStateFlow.value = RelayState.Authenticating
        advanceUntilIdle()
        engine.relayStateFlow.value = RelayState.Paired
        advanceUntilIdle()

        assertEquals(
            listOf(
                RelayStatusWrite("d1", RelayStatus.CONNECTING, null),
                RelayStatusWrite("d1", RelayStatus.AUTHENTICATING, null),
                RelayStatusWrite("d1", RelayStatus.PAIRED, 1_700_000_500_000L),
            ),
            repository.relayStatusWrites,
        )
    }

    @Test
    fun `lastConnectedAt 只在进入 paired 时更新`() = runTest {
        val repository = FakeDeviceRepository(initialDevices = listOf(d1), initialCurrentId = "d1")
        val engine = FakeEnginePort()
        val writer = writer(repository, engine)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { writer.watch() }
        advanceUntilIdle()

        engine.relayStateFlow.value = RelayState.Paired
        advanceUntilIdle()
        // 掉线再回来：又是「进入 paired」，时间戳再更新一次（那是新的一次连接）
        engine.relayStateFlow.value = RelayState.Reconnecting
        advanceUntilIdle()
        engine.relayStateFlow.value = RelayState.Paired
        advanceUntilIdle()

        assertEquals(
            listOf(
                RelayStatusWrite("d1", RelayStatus.PAIRED, 1_700_000_500_000L),
                RelayStatusWrite("d1", RelayStatus.RECONNECTING, null),
                RelayStatusWrite("d1", RelayStatus.PAIRED, 1_700_000_500_000L),
            ),
            repository.relayStatusWrites,
        )
        assertTrue(
            "非 paired 的写入不该带时间戳",
            repository.relayStatusWrites.filter { it.status != RelayStatus.PAIRED }
                .all { it.connectedAt == null },
        )
    }

    @Test
    fun `relay 失败原因会回写并在 paired 后清空`() = runTest {
        val repository = FakeDeviceRepository(initialDevices = listOf(d1), initialCurrentId = "d1")
        val engine = FakeEnginePort()
        val writer = writer(repository, engine)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { writer.watch() }
        advanceUntilIdle()

        engine.relayFailureFlow.value = EngineEvent.RelayFailure(
            reason = "invalid-mobile-connection",
            message = "Desktop did not match this mobile connection before the waiting timeout.",
        )
        engine.relayStateFlow.value = RelayState.Error
        advanceUntilIdle()

        assertEquals(
            "失败原因要落盘，否则用户只能看到笼统的超时",
            "invalid-mobile-connection",
            repository.currentFlow.value?.lastRelayError,
        )
        assertEquals(
            "Desktop did not match this mobile connection before the waiting timeout.",
            repository.currentFlow.value?.lastRelayErrorMessage,
        )

        engine.relayStateFlow.value = RelayState.Paired
        advanceUntilIdle()

        assertEquals(null, repository.currentFlow.value?.lastRelayError)
        assertEquals(null, repository.currentFlow.value?.lastRelayErrorMessage)
        assertEquals(
            listOf(
                RelayFailureWrite(
                    "d1",
                    "invalid-mobile-connection",
                    "Desktop did not match this mobile connection before the waiting timeout.",
                ),
                RelayFailureWrite("d1", null, null),
            ),
            repository.relayFailureWrites,
        )
    }

    @Test
    fun `没有当前设备时不写盘`() = runTest {
        val repository = FakeDeviceRepository(initialDevices = listOf(d1))
        val engine = FakeEnginePort()
        val writer = writer(repository, engine)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { writer.watch() }
        advanceUntilIdle()

        engine.relayStateFlow.value = RelayState.Paired
        advanceUntilIdle()

        assertTrue(repository.relayStatusWrites.isEmpty())
    }

    @Test
    fun `换设备后新设备的状态会被写入`() = runTest {
        val repository =
            FakeDeviceRepository(initialDevices = listOf(d1, d2), initialCurrentId = "d1")
        val engine = FakeEnginePort()
        val writer = writer(repository, engine)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { writer.watch() }
        advanceUntilIdle()

        engine.relayStateFlow.value = RelayState.Paired
        advanceUntilIdle()
        repository.currentFlow.value = d2
        advanceUntilIdle()

        assertEquals(
            listOf(
                RelayStatusWrite("d1", RelayStatus.PAIRED, 1_700_000_500_000L),
                // 新设备拿到的是同一个 relay 状态 ⇒ 必须写一次（不能因为「状态没变」跳过）
                RelayStatusWrite("d2", RelayStatus.PAIRED, 1_700_000_500_000L),
            ),
            repository.relayStatusWrites,
        )
    }

    @Test
    fun `写盘失败被吞掉并记日志`() = runTest {
        val repository = FakeDeviceRepository(initialDevices = listOf(d1), initialCurrentId = "d1")
        val engine = FakeEnginePort()
        val log = mutableListOf<String>()
        val writer = writer(repository, engine, log = log)
        repository.writeFailure = IllegalStateException("磁盘满了")
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { writer.watch() }
        advanceUntilIdle()

        engine.relayStateFlow.value = RelayState.Paired
        advanceUntilIdle()

        assertTrue(repository.relayStatusWrites.isEmpty())
        assertEquals(1, log.size)
        assertTrue(
            "日志要带设备与状态：$log",
            log.single().contains("d1") && log.single().contains("PAIRED"),
        )

        // 失败不否定后续：下一次状态变化照样写
        repository.writeFailure = null
        engine.relayStateFlow.value = RelayState.Reconnecting
        advanceUntilIdle()
        assertEquals(
            listOf(RelayStatus.RECONNECTING),
            repository.relayStatusWrites.map { it.status },
        )
    }

    @Test
    fun `状态没变时重复发射不重复写盘`() = runTest {
        val repository = FakeDeviceRepository(initialDevices = listOf(d1), initialCurrentId = "d1")
        val engine = FakeEnginePort(initialRelayState = RelayState.Paired)
        val writer = writer(repository, engine)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { writer.watch() }
        advanceUntilIdle()

        // 盘上是 idle、引擎已经 paired ⇒ 写一次
        assertEquals(1, repository.relayStatusWrites.size)

        // 设备记录因别的字段变化重新发射 → 状态没变，不再写
        repository.devicesFlow.value =
            repository.devicesFlow.value.map { it.copy(name = "改名了") }
        advanceUntilIdle()
        assertEquals(1, repository.relayStatusWrites.size)
    }
}
