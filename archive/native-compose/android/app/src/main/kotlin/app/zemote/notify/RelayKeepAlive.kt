package app.zemote.notify

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log

/**
 * 后台连接保活：CPU 唤醒锁 + Wi-Fi 锁。
 *
 * 前台服务只能保证进程不被回收，**不保证息屏/切后台后 CPU 和网络还在跑**：
 * Doze / 厂商省电会冻结进程，relay 心跳和 WebSocket 读写一起停掉，socket 随后被
 * 系统掐死（日志里进程 PID 没变、socket 却在 onStop 后几秒断开）。这里：
 *
 * - `PARTIAL_WAKE_LOCK`：保持 CPU 运行，心跳定时器才能按时触发；
 * - `WifiLock(HIGH_PERF)`：保持 Wi-Fi 射频不休眠（移动数据由 CPU 锁 + 周期心跳维持）。
 *
 * 只在前台服务开启且**应用不在前台**（或仍有任务在跑）时持有，回前台立即释放，
 * 避免亮屏使用时无谓耗电。
 */
internal class RelayKeepAlive(context: Context) {

    private val power = context.getSystemService(PowerManager::class.java)

    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)

    private var lock: PowerManager.WakeLock? = null

    private var wifiLock: WifiManager.WifiLock? = null

    @Synchronized
    fun setActive(active: Boolean) {
        if (active) {
            if (lock == null) {
                lock = runCatching {
                    power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                }.onFailure {
                    Log.w("RelayKeepAlive", "获取 CPU 唤醒锁失败: ${it.message}")
                }.getOrNull()
            }
            if (wifiLock == null) {
                wifiLock = runCatching {
                    @Suppress("DEPRECATION")
                    wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, TAG)?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                }.onFailure {
                    Log.w("RelayKeepAlive", "获取 Wi-Fi 锁失败: ${it.message}")
                }.getOrNull()
            }
        } else {
            lock?.let { held ->
                runCatching { if (held.isHeld) held.release() }
                lock = null
            }
            wifiLock?.let { held ->
                runCatching { if (held.isHeld) held.release() }
                wifiLock = null
            }
        }
    }

    private companion object {
        const val TAG = "zemote:relay"
    }
}
