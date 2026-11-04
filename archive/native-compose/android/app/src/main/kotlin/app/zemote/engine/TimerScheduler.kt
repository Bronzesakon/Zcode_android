package app.zemote.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 宿主侧定时器，**两套互相独立的命名空间**（契约 §7.2 / §7.3）：
 *
 * 1. **relay 槽位**（[RelayTimerSlot]）：`heartbeat` / `waiting` / `reconnect` / `rewait`。
 *    每个槽位同一时刻只有一个定时器；再起同槽位会**先撤旧的**（对应 Dart 里
 *    `_heartbeatTimer?.cancel(); _heartbeatTimer = Timer(...)` 的写法）。
 * 2. **门面 token**（[Long]）：核心自增的句柄，一次性的，起/撤按 token 配对。
 *
 * 两者不可混用——`relay_timer_start` 的 `repeat` 只对 relay 的 heartbeat 为真，
 * 门面 token 一律单次。
 *
 * 线程约定：**不线程安全**，必须从单一调度器（引擎的专用单线程 dispatcher）访问。
 * 定时器体跑在传入的 [scope] 上，因此 `onFire` 回调天然回到引擎线程。
 *
 * 撤销**幂等**（契约 §7.2 明说 `relay_timer_cancel` 槽位为空时是 no-op）：对没起过的
 * 槽位/token 调 cancel 不报错、不产生副作用。
 */
class TimerScheduler(private val scope: CoroutineScope) {

    private val relayJobs = HashMap<RelayTimerSlot, Job>()
    private val tokenJobs = HashMap<Long, Job>()

    // ----------------------------------------------------------------------
    // relay 槽位
    // ----------------------------------------------------------------------

    /**
     * 起（或重排）一个 relay 槽位定时器。
     *
     * [repeat] 为 `true` 时是**周期**定时器，宿主自行按 [delayMs] 重排（契约 §7.2），
     * 直到被 [cancelRelay] 撤掉；为 `false` 时触发一次后自动失效。
     */
    fun startRelay(slot: RelayTimerSlot, delayMs: Long, repeat: Boolean, onFire: () -> Unit) {
        relayJobs.remove(slot)?.cancel()
        val job = scope.launch {
            val period = delayMs.coerceAtLeast(0L)
            if (repeat) {
                while (isActive) {
                    delay(period)
                    fire(onFire)
                }
            } else {
                delay(period)
                fire(onFire)
            }
        }
        relayJobs[slot] = job
        job.invokeOnCompletion { if (relayJobs[slot] === job) relayJobs.remove(slot) }
    }

    /** 撤掉槽位上的定时器。**幂等**：槽位为空时是 no-op。 */
    fun cancelRelay(slot: RelayTimerSlot) {
        relayJobs.remove(slot)?.cancel()
    }

    /** 当前有定时器的 relay 槽位（调试/测试用）。 */
    fun activeRelaySlots(): Set<RelayTimerSlot> = relayJobs.keys.toSet()

    // ----------------------------------------------------------------------
    // 门面 token
    // ----------------------------------------------------------------------

    /** 起一个门面 token 定时器（单次）。同 token 再起会先撤旧的。 */
    fun startToken(token: Long, delayMs: Long, onFire: () -> Unit) {
        tokenJobs.remove(token)?.cancel()
        val job = scope.launch {
            delay(delayMs.coerceAtLeast(0L))
            fire(onFire)
        }
        tokenJobs[token] = job
        job.invokeOnCompletion { if (tokenJobs[token] === job) tokenJobs.remove(token) }
    }

    /** 撤掉 token 定时器。**幂等**：token 未知时是 no-op。 */
    fun cancelToken(token: Long) {
        tokenJobs.remove(token)?.cancel()
    }

    /** 当前活动的门面 token（调试/测试用）。 */
    fun activeTokens(): Set<Long> = tokenJobs.keys.toSet()

    // ----------------------------------------------------------------------
    // 生命周期
    // ----------------------------------------------------------------------

    /** 撤销全部定时器（两套命名空间一起）。用于 `dispose`。 */
    fun cancelAll() {
        relayJobs.values.forEach { it.cancel() }
        relayJobs.clear()
        tokenJobs.values.forEach { it.cancel() }
        tokenJobs.clear()
    }

    /**
     * 触发回调并吞掉它抛出的异常。
     *
     * 引擎传进来的 `onFire` 自己已经把错误收敛干净（`bridge.step` 全程 `runCatching`），
     * 这一层只是防止一个回调把整个调度器连带弄死：定时器任务抛出的异常会成为
     * `scope` 上的未捕获异常，在 Android 上等于崩溃。
     */
    private inline fun fire(onFire: () -> Unit) {
        runCatching { onFire() }
    }
}
