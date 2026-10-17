package app.zemote.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 定时器两套命名空间（契约 §7.2 / §7.3）的起 / 撤 / 周期重排。
 *
 * 用 `runTest` 的虚拟时间驱动，不睡真实时间。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimerSchedulerTest {

    @Test
    fun periodicRelayTimerRefiresUntilCancelled() = runTest {
        val fires = mutableListOf<Long>()
        val scheduler = TimerScheduler(this)

        scheduler.startRelay(RelayTimerSlot.Heartbeat, 10_000, repeat = true) {
            fires += testScheduler.currentTime
        }

        advanceTimeBy(10_001)
        assertEquals(listOf(10_000L), fires)

        // 周期定时器由宿主自行重排：不重排就只响一次
        advanceTimeBy(10_000)
        assertEquals(listOf(10_000L, 20_000L), fires)

        scheduler.cancelRelay(RelayTimerSlot.Heartbeat)
        advanceTimeBy(60_000)
        assertEquals(listOf(10_000L, 20_000L), fires)
        assertTrue(scheduler.activeRelaySlots().isEmpty())
    }

    @Test
    fun singleShotRelayTimerFiresOnce() = runTest {
        var count = 0
        val scheduler = TimerScheduler(this)

        scheduler.startRelay(RelayTimerSlot.Waiting, 500, repeat = false) { count++ }

        advanceTimeBy(499)
        assertEquals(0, count)
        advanceTimeBy(2)
        assertEquals(1, count)
        advanceTimeBy(60_000)
        assertEquals(1, count)
        assertTrue(scheduler.activeRelaySlots().isEmpty())
    }

    @Test
    fun restartingSameRelaySlotReplacesTheOldTimer() = runTest {
        val fires = mutableListOf<String>()
        val scheduler = TimerScheduler(this)

        scheduler.startRelay(RelayTimerSlot.Reconnect, 5_000, repeat = false) { fires += "first" }
        advanceTimeBy(1_000)
        // 同槽位再起 = 先撤旧的（对应 Dart `_reconnectTimer?.cancel(); _reconnectTimer = Timer(...)`）
        scheduler.startRelay(RelayTimerSlot.Reconnect, 5_000, repeat = false) { fires += "second" }

        advanceTimeBy(4_001)
        assertTrue("旧定时器必须被撤掉，实际触发：$fires", fires.isEmpty())

        advanceTimeBy(1_000)
        assertEquals(listOf("second"), fires)
    }

    @Test
    fun cancelIsIdempotent() = runTest {
        val scheduler = TimerScheduler(this)

        // 契约 §7.2：槽位为空时 cancel 是 no-op
        scheduler.cancelRelay(RelayTimerSlot.Heartbeat)
        scheduler.cancelRelay(RelayTimerSlot.Heartbeat)
        scheduler.cancelRelay(RelayTimerSlot.Rewait)
        scheduler.cancelToken(99)
        scheduler.cancelToken(99)

        assertTrue(scheduler.activeRelaySlots().isEmpty())
        assertTrue(scheduler.activeTokens().isEmpty())
    }

    @Test
    fun facadeTokensAreOneShotAndReusable() = runTest {
        val fires = mutableListOf<Long>()
        val scheduler = TimerScheduler(this)

        scheduler.startToken(7, 30_000) { fires += 7 }
        scheduler.startToken(8, 1_000) { fires += 8 }

        advanceTimeBy(1_001)
        assertEquals(listOf(8L), fires)
        assertEquals(setOf(7L), scheduler.activeTokens())

        advanceTimeBy(30_000)
        assertEquals(listOf(8L, 7L), fires)
        assertTrue(scheduler.activeTokens().isEmpty())

        // 同一个 token 可以复用（核心的 token 单调递增，但撤销后重用不应出错）
        scheduler.startToken(7, 100) { fires += -7 }
        advanceTimeBy(101)
        assertEquals(listOf(8L, 7L, -7L), fires)

        scheduler.cancelToken(7)
    }

    @Test
    fun theTwoNamespacesDoNotInterfere() = runTest {
        val fires = mutableListOf<String>()
        val scheduler = TimerScheduler(this)

        scheduler.startRelay(RelayTimerSlot.Heartbeat, 1_000, repeat = true) { fires += "hb" }
        scheduler.startToken(1, 1_000) { fires += "t1" }

        advanceTimeBy(1_001)
        assertEquals(2, fires.size)
        assertTrue(fires.containsAll(listOf("hb", "t1")))

        // 撤门面 token 不影响 relay 槽位
        scheduler.cancelToken(1)
        advanceTimeBy(1_000)
        assertEquals(1, fires.count { it == "t1" })
        assertEquals(2, fires.count { it == "hb" })

        scheduler.cancelAll()
        val hb = fires.count { it == "hb" }
        advanceTimeBy(60_000)
        assertEquals(hb, fires.count { it == "hb" })
        assertTrue(scheduler.activeRelaySlots().isEmpty())
        assertTrue(scheduler.activeTokens().isEmpty())
    }
}
