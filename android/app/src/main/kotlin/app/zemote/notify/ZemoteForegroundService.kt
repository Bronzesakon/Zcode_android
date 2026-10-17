package app.zemote.notify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * 保活前台服务：让进程（以及进程里的连接）在退到后台后继续活着。
 *
 * 它的通知是 `keepalive` 渠道的 MIN 静默常驻通知，不参与流体云提升——这条通知的
 * 职责只是满足「前台服务必须有通知」的平台要求，内容越安静越好。
 *
 * 为什么用 `specialUse` 而不是 `dataSync`：后者在 Android 15 上有每 24 小时 6 小时
 * 的累计上限，到点会被系统静默停掉，连接也就断了。`specialUse` 需要在 manifest 里
 * 同时声明 `FOREGROUND_SERVICE_SPECIAL_USE` 权限、`foregroundServiceType="specialUse"`
 * 和 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 属性（缺属性在 Android 14+ 会抛异常）。
 * 这些 manifest 条目由主线补齐；本文件只负责调用。
 */
class ZemoteForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        Notifier.get(this).ensureChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notifier = Notifier.get(this)
        val runningCount = intent?.getIntExtra(EXTRA_RUNNING_COUNT, 0) ?: 0
        val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty()
        val notification = notifier.buildServiceNotification(
            runningCount,
            text.ifEmpty { NotifyStrings.SERVICE_TEXT_IDLE },
        )
        try {
            ServiceCompat.startForeground(
                this,
                Notifier.ID_SERVICE,
                notification,
                foregroundServiceType(),
            )
        } catch (e: Exception) {
            // Android 14+ 在 manifest 缺 specialUse 属性/权限时会抛。这里大声记日志
            // 而不是直接崩：通知本身仍然可用，只是进程不再受前台服务保护。
            Log.e(TAG, "startForeground 失败: ${e.message}")
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 从最近任务划掉应用时，默认会把整个进程带走。保活服务的语义是「连接继续」，
        // 所以这里什么都不做；START_STICKY 会让系统在合适时机把它拉回来。
        Log.i(TAG, "任务从最近任务中移除，保活服务继续运行")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.i(TAG, "保活服务已停止")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

    companion object {

        private const val TAG = "ZemoteForegroundService"
        private const val EXTRA_RUNNING_COUNT = "running_count"
        private const val EXTRA_TEXT = "text"

        /** 启动（或再次启动）前台服务；重复调用会刷新常驻通知内容。 */
        fun start(
            context: Context,
            runningCount: Int = 0,
            text: String = NotifyStrings.SERVICE_TEXT_IDLE,
        ) {
            val intent = Intent(context, ZemoteForegroundService::class.java).apply {
                putExtra(EXTRA_RUNNING_COUNT, runningCount)
                putExtra(EXTRA_TEXT, text)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** 停止服务并撤掉常驻通知。 */
        fun stop(context: Context) {
            context.stopService(Intent(context, ZemoteForegroundService::class.java))
        }

        /**
         * 只刷新常驻通知，不重启服务。
         *
         * 注意它不满足「startForegroundService 后必须 startForeground」的平台要求——
         * 服务没在跑时请用 [start]。
         */
        fun update(context: Context, runningCount: Int, text: String) {
            val notifier = Notifier.get(context)
            notifier.postServiceNotification(
                notifier.buildServiceNotification(runningCount, text),
            )
        }

        /** 用运行集刷新常驻通知（正文由 [Notifier.runningBody] 生成）。 */
        fun update(context: Context, running: List<RunningTask>) {
            val notifier = Notifier.get(context)
            val text = Notifier.runningBody(running).ifEmpty { NotifyStrings.SERVICE_TEXT_IDLE }
            notifier.postServiceNotification(
                notifier.buildServiceNotification(running.size, text),
            )
        }
    }
}
