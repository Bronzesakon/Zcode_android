package app.zemote.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 完成卡上的「知道了」动作。
 *
 * 为什么需要动作而不是让用户滑掉：被提升的卡必须是 ongoing，而 ongoing 通知在
 * AOSP 上用户滑不掉。所以用户正在看的那张「已完成」卡必须自带出口——就是这里。
 * 抢不到提升位的完成卡会降级成普通可滑除通知，滑掉即可，App 不感知。
 *
 * 不导出（exported=false）：只有本应用自己的 PendingIntent 能到达。
 */
class CardDismissReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, -1)
        if (id < 0) return
        Notifier.get(context).dismissFinishedCard(id)
    }

    companion object {
        const val ACTION_DISMISS = "app.zemote.action.DISMISS_CARD"
        const val EXTRA_ID = "card_id"
    }
}

/**
 * 下载进度卡的「暂停」按钮。
 *
 * 本模块只负责把点击变成一次回调；真正的暂停逻辑属于下载器，主线通过
 * [DownloadActions.onPause] 接线。这样 notify 包不需要依赖更新模块。
 */
class DownloadPauseReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        DownloadActions.onPause?.invoke()
    }

    companion object {
        const val ACTION_PAUSE = "app.zemote.action.DOWNLOAD_PAUSE"
    }
}

/**
 * 下载进度卡的「取消」按钮：先通知业务侧取消，再撤掉进度卡。
 */
class DownloadCancelReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        DownloadActions.onCancel?.invoke()
        Notifier.get(context).clearDownload()
    }

    companion object {
        const val ACTION_CANCEL = "app.zemote.action.DOWNLOAD_CANCEL"
    }
}

/**
 * 下载卡两个按钮的接线点。
 *
 * 用函数变量而不是让 notify 包直接引用下载器：依赖方向保持「更新模块 → notify」，
 * 而不是反向。两个字段都允许为空（没有下载任务时点击不会发生）。
 */
object DownloadActions {
    @Volatile
    var onPause: (() -> Unit)? = null

    @Volatile
    var onCancel: (() -> Unit)? = null
}
