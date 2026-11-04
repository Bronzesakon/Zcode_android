package app.zemote.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.zemote.MainActivity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 所有通知渲染。
 *
 * ## 四个渠道（创建后 App 改不了重要性，所以一开始就分好）
 *
 *   keepalive       IMPORTANCE_MIN      前台服务自己的静默常驻通知
 *   running_tasks   IMPORTANCE_LOW      每个运行中任务一条常驻卡 + 组摘要 + 下载进度卡
 *   task_completed  IMPORTANCE_DEFAULT  任务结束时的可听提醒
 *   task_attention  IMPORTANCE_DEFAULT  任务等待用户确认时的可听提醒
 *
 * 「运行中要静、完成/待确认要响」只能靠分渠道实现：渠道重要性在创建后不可修改，
 * 把同一张卡换渠道也不可靠（会丢用户已设置的渠道偏好）。
 *
 * ## 卡片生命周期
 *
 * - 运行中：每个任务一条 `ongoing` 通知，id 由调用方按 `(workspace, session)` 派生
 *   并保持稳定；每次进展都用**同一个 id** `notify` 覆盖，卡片原地刷新。
 * - 结束：**不撤卡、不换 id**，同一条记录把状态词改成「已完成/已中断/已失败」，
 *   并挂一个「知道了」动作。被提升的卡必须是 ongoing，而 ongoing 在 AOSP 上用户
 *   滑不掉——这就是那个动作存在的唯一理由。
 * - 让位：完成卡抢不到提升位时重发成**不带提升**的普通可滑除通知，记录不丢。
 *
 * 流体云不是独立子系统：它就是上面这些常驻通知里被系统挑中的那几条。请求提升的
 * 唯一动作是 [LiveUpdate.requestPromotion]，选择哪几条由 [PromotionPolicy] 决定。
 */
class Notifier(context: Context) {

    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)
    private val platform = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    /**
     * 「已完成」卡的账本。
     *
     * `title` 是**已装饰**的显示标题（`已完成 · 任务名`），`locateTitle` 才是
     * 点击定位要用的原始任务名——两者不能混用（见 [RunningTask.title] 的说明）。
     */
    private data class FinishedCard(
        val workspaceKey: String,
        val sessionId: String,
        val locateTitle: String,
        val title: String,
        val body: String,
        val status: TaskStatus,
        val completedAt: Long,
        /** 是否正占着提升位（决定 ongoing：平台要求被提升的卡必须 ongoing）。 */
        val promoted: Boolean = false,
        /** 这一版内容有没有真的发出去过（新建时 false，发完置 true）。 */
        val posted: Boolean = false,
    )

    private val finishedCards = ConcurrentHashMap<Int, FinishedCard>()

    init {
        // 渠道必须先存在，否则 API 26+ 上的 notify 会静默丢弃。构造时保证一次，
        // 调用方就不必记得先调 ensureChannels()（该方法是幂等的）。
        ensureChannels()
    }

    /** 用户是否还允许本应用发通知（API 33+ 的 POST_NOTIFICATIONS 也包含在这里）。 */
    fun notificationsEnabled(): Boolean = manager.areNotificationsEnabled()

    // ------------------------------------------------------------------ 渠道

    /** 幂等：重复调用只会更新渠道名字/描述，不会改重要性。 */
    fun ensureChannels() {
        val running = NotificationChannel(
            CHANNEL_RUNNING,
            NotifyStrings.CHANNEL_RUNNING_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = NotifyStrings.CHANNEL_RUNNING_DESC
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        val attention = NotificationChannel(
            CHANNEL_ATTENTION,
            NotifyStrings.CHANNEL_ATTENTION_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = NotifyStrings.CHANNEL_ATTENTION_DESC
        }
        val completed = NotificationChannel(
            CHANNEL_COMPLETED,
            NotifyStrings.CHANNEL_COMPLETED_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = NotifyStrings.CHANNEL_COMPLETED_DESC
        }
        val keepAlive = NotificationChannel(
            CHANNEL_KEEPALIVE,
            NotifyStrings.CHANNEL_KEEPALIVE_NAME,
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = NotifyStrings.CHANNEL_KEEPALIVE_DESC
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannels(listOf(running, attention, completed, keepAlive))
        adoptOrphanFinishedCards()
    }

    /**
     * 认领上一个进程遗留的完成卡。
     *
     * 为什么需要：被提升的完成卡必须是 ongoing，而 ongoing 通知用户滑不掉。进程死了
     * 以后账本一起消失，那张卡会永久占着提升位且没有任何出口。重发一遍**不带提升**
     * 的版本即可：可见性不变，但它降级成普通可滑除通知，用户能自己清掉。
     *
     * 用 marker extra 而不是 id 号段来识别：完成卡和运行卡共用 id，号段区分不开。
     */
    private fun adoptOrphanFinishedCards() {
        val mgr = platform ?: return
        val active = try {
            mgr.activeNotifications
        } catch (e: Exception) {
            Log.w(TAG, "读取活动通知失败: ${e.message}")
            return
        }
        for (record in active) {
            val id = record.id
            if (finishedCards.containsKey(id)) continue
            val extras = record.notification.extras ?: continue
            if (!extras.getBoolean(EXTRA_FINISHED_CARD)) continue
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            Log.i(TAG, "认领上次进程遗留的完成卡 (id=$id)：降级为可滑除的通知")
            val builder = NotificationCompat.Builder(appContext, CHANNEL_RUNNING)
                .setSmallIcon(SMALL_ICON)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setOngoing(false)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .addAction(0, NotifyStrings.ACTION_DISMISS, dismissIntent(id))
                .addExtras(finishedCardExtras())
            try {
                manager.notify(id, builder.build())
            } catch (e: SecurityException) {
                Log.w(TAG, "认领完成卡失败: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------ 前台服务

    /** 前台服务的常驻通知；空闲态就是「连接保持中」。 */
    fun buildServiceNotification(runningCount: Int, text: String): Notification {
        val title = if (runningCount > 0) {
            NotifyStrings.serviceTitleRunning(runningCount)
        } else {
            NotifyStrings.SERVICE_TITLE_IDLE
        }
        val body = text.ifEmpty { NotifyStrings.SERVICE_TEXT_IDLE }
        return NotificationCompat.Builder(appContext, CHANNEL_KEEPALIVE)
            .setSmallIcon(SMALL_ICON)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(appIntent(REQUEST_SERVICE))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
    }

    fun postServiceNotification(notification: Notification) {
        if (!notificationsEnabled()) return
        try {
            manager.notify(ID_SERVICE, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "更新常驻通知失败: ${e.message}")
        }
    }

    // -------------------------------------------------------- 运行中任务卡

    /**
     * 用 [running] 替换当前运行集，撤回 [removedIds] 里的卡，并把 [completed]
     * 里的任务原地改成收尾状态。
     *
     * **空更新 ≠ 运行集为空**：调用方必须只在「真的没有运行中任务、也没有留存
     * 完成卡」时才传空表。把一次无关的空更新当成运行集清零，会让 ColorOS 立刻
     * 收回流体云卡片、下一拍再提升时看起来像「卡片消失又重建」。
     */
    fun syncRunningTasks(
        running: List<RunningTask>,
        removedIds: Collection<Int> = emptyList(),
        completed: List<FinishedTask> = emptyList(),
    ) {
        if (!notificationsEnabled()) return
        val now = System.currentTimeMillis()

        // 1) 结束：同一条记录改状态。不撤、不另开 id —— 用户看到的是卡片原地变字。
        for (event in completed) {
            finishedCards[event.id] = FinishedCard(
                workspaceKey = event.workspaceKey,
                sessionId = event.sessionId,
                locateTitle = event.title,
                title = NotifyStrings.taskTitle(event.status.label, singleLine(event.title)),
                body = singleLine(event.body).ifEmpty { workspaceNameOf(event.workspaceKey) },
                status = event.status,
                completedAt = if (event.completedAt > 0L) event.completedAt else now,
            )
            Log.i(TAG, "完成卡原地改状态 id=${event.id}（${event.status.label}）——不撤卡、不换 id")
        }

        // 2) 撤回消失的任务卡；走进完成态的卡不撤（它不是走了，是改了状态）。
        for (id in removedIds) {
            if (finishedCards.containsKey(id)) continue
            manager.cancel(id)
        }

        // 3) 新一轮落在同一张卡上：完成态结束，下面的 notify 把它覆盖回运行中。
        for (task in running) finishedCards.remove(task.id)

        // 4) 选提升集合。组摘要刻意不参与：Android 拒绝提升组摘要，而且它会重复
        //    每张任务卡已经说过的话。
        val promoted = PromotionPolicy.choose(
            running = running.map { PromotionPolicy.Running(it.id, it.status, it.activityAt) },
            finished = finishedCards.map { PromotionPolicy.Finished(it.key, it.value.completedAt) },
        )
        logPromotionChanges(promoted, running)

        if (running.isEmpty() && finishedCards.isEmpty()) {
            manager.cancel(ID_GROUP_SUMMARY)
            return
        }

        // 5) 运行中：每任务一条常驻卡，同一 id 原地刷新。
        for (task in running) {
            val body = singleLine(task.body).ifEmpty { workspaceNameOf(task.workspaceKey) }
            val builder = NotificationCompat.Builder(appContext, CHANNEL_RUNNING)
                .setSmallIcon(SMALL_ICON)
                .setContentTitle(NotifyStrings.taskTitle(task.status.label, singleLine(task.title)))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(taskIntent(task.workspaceKey, task.sessionId, task.title, task.id))
                .setGroup(GROUP_RUNNING)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
            if (task.id in promoted) LiveUpdate.requestPromotion(builder, task.status.label)
            try {
                manager.notify(task.id, builder.build())
            } catch (e: SecurityException) {
                Log.w(TAG, "更新任务通知失败: ${e.message}")
            }
        }

        // 6) 完成卡只在「提升状态变了」或「还没发过」时重发一次——那正是它降级成
        //    可滑除通知、或重新回到芯片位的时刻；其余时候一个字都不用动。
        for ((id, card) in finishedCards) {
            val isPromoted = id in promoted
            if (card.posted && card.promoted == isPromoted) continue
            val updated = card.copy(promoted = isPromoted, posted = true)
            finishedCards[id] = updated
            postFinishedCard(id, updated)
        }

        // 7) 组摘要：长任务列表默认折叠。它不请求提升。
        val summary = NotificationCompat.Builder(appContext, CHANNEL_RUNNING)
            .setSmallIcon(SMALL_ICON)
            .setContentTitle(NotifyStrings.GROUP_RUNNING_TITLE)
            .setContentText(NotifyStrings.groupSummary(running.size))
            .setGroup(GROUP_RUNNING)
            .setGroupSummary(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        try {
            manager.notify(ID_GROUP_SUMMARY, summary)
        } catch (e: SecurityException) {
            Log.w(TAG, "更新群组摘要失败: ${e.message}")
        }
    }

    // ------------------------------------------------------------ 完成 / 待确认

    /**
     * 一条可听、可滑除的完成记录（`task_completed` 渠道）。
     *
     * 它与 [syncRunningTasks] 里原地改状态的完成卡是**两件事**：这条是持久的提醒
     * 记录，可以随时清掉；那张卡是用户正在看的实时卡片，用同一个 id 改字。
     * 两者同时存在是刻意的——只留卡片会漏掉「任务结束时用户没在看通知栏」的场景。
     */
    fun notifyCompleted(task: FinishedTask) {
        if (!notificationsEnabled()) return
        val title = if (task.status == TaskStatus.INTERRUPTED || task.status == TaskStatus.FAILED) {
            NotifyStrings.COMPLETED_TITLE_ENDED
        } else {
            NotifyStrings.COMPLETED_TITLE
        }
        val name = singleLine(task.title)
        val body = singleLine(task.body)
        val text = if (body.isEmpty()) name else "$name\n$body"
        val id = nextId()
        val notification = NotificationCompat.Builder(appContext, CHANNEL_COMPLETED)
            .setSmallIcon(SMALL_ICON)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(taskIntent(task.workspaceKey, task.sessionId, task.title, id))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "发送完成通知失败: ${e.message}")
        }
    }

    /**
     * 待确认的有声提醒。
     *
     * 常驻卡已经原地变成「等待确认」了，但 `running_tasks` 是 LOW 静音渠道；
     * 渠道重要性不能临时改，所以在 `task_attention` 上再补一条可听通知。
     */
    fun notifyAttention(task: AttentionTask) {
        if (!notificationsEnabled()) return
        val name = singleLine(task.title)
        val body = singleLine(task.body)
        val text = if (body.isEmpty()) name else "$name\n$body"
        val id = nextId()
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ATTENTION)
            .setSmallIcon(SMALL_ICON)
            .setContentTitle(NotifyStrings.ATTENTION_TITLE)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(taskIntent(task.workspaceKey, task.sessionId, task.title, id))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "发送待确认通知失败: ${e.message}")
        }
    }

    /** 用户点了「知道了」（或滑掉一张降级后的完成卡）：这张卡到此为止。 */
    fun dismissFinishedCard(id: Int) {
        finishedCards.remove(id)
        manager.cancel(id)
        Log.i(TAG, "完成卡已清除 (id=$id)")
    }

    /**
     * 渲染一张完成卡——**始终使用它作为运行卡时的同一个 id**，平台因此更新的是
     * 用户已经在看的那条记录，而不是新建一条。
     *
     * `ongoing` 跟随提升状态：平台要求被提升的通知必须 ongoing，而 ongoing 通知
     * 用户滑不掉。抢不到提升位时 `ongoing = false` + `autoCancel`，它就是一則
     * 普通可滑除通知——「留到用户清掉」在这个平台上的实际含义。
     */
    private fun postFinishedCard(id: Int, card: FinishedCard) {
        val builder = NotificationCompat.Builder(appContext, CHANNEL_RUNNING)
            .setSmallIcon(SMALL_ICON)
            .setContentTitle(card.title)
            .setContentText(card.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(card.body))
            .setContentIntent(taskIntent(card.workspaceKey, card.sessionId, card.locateTitle, id))
            .setOngoing(card.promoted)
            .setAutoCancel(!card.promoted)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .addAction(0, NotifyStrings.ACTION_DISMISS, dismissIntent(id))
            .addExtras(finishedCardExtras())
        if (card.promoted) LiveUpdate.requestPromotion(builder, card.status.label)
        try {
            manager.notify(id, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "更新完成卡失败: ${e.message}")
        }
    }

    // -------------------------------------------------------------- 下载进度

    /**
     * 下载进度卡。
     *
     * 用 `setProgress` + BigTextStyle（BigText 是允许提升的标准样式之一，进度条由
     * 平台的进度 extras 渲染）。API 36 上同时请求提升，ColorOS 会把它显示成流体云；
     * 低版本就是一条普通的带进度条常驻通知。暂停/取消走两个广播 Receiver，业务侧
     * 通过 [DownloadActions] 接线。
     */
    fun updateDownload(progress: DownloadProgress) {
        if (!notificationsEnabled()) return
        val percent = progress.percent.coerceIn(0, 100)
        val text = progress.text.ifEmpty {
            if (progress.indeterminate) NotifyStrings.DOWNLOAD_TEXT else NotifyStrings.downloadText(percent)
        }
        val builder = NotificationCompat.Builder(appContext, CHANNEL_RUNNING)
            .setSmallIcon(SMALL_ICON_DOWNLOAD)
            .setContentTitle(progress.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setProgress(100, percent, progress.indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .addAction(
                0,
                NotifyStrings.ACTION_PAUSE,
                downloadIntent(DownloadPauseReceiver::class.java, DownloadPauseReceiver.ACTION_PAUSE, REQUEST_PAUSE),
            )
            .addAction(
                0,
                NotifyStrings.ACTION_CANCEL,
                downloadIntent(DownloadCancelReceiver::class.java, DownloadCancelReceiver.ACTION_CANCEL, REQUEST_CANCEL),
            )
        val chip = progress.shortCriticalText
            ?: if (progress.indeterminate) NotifyStrings.DOWNLOAD_INDETERMINATE else NotifyStrings.downloadChip(percent)
        LiveUpdate.requestPromotion(builder, chip)
        try {
            manager.notify(ID_DOWNLOAD, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "更新下载进度失败: ${e.message}")
        }
    }

    fun clearDownload() {
        manager.cancel(ID_DOWNLOAD)
    }

    // ------------------------------------------------------------------ 清理

    /** 撤掉全部通知（诊断/退出用）。前台服务通知也会一起没掉。 */
    fun clearAll() {
        manager.cancelAll()
        finishedCards.clear()
        Log.i(TAG, "已清除全部通知")
    }

    // ------------------------------------------------------------------ 私有

    private fun appIntent(requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            appContext,
            requestCode,
            Intent(appContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * 点击卡片 → 打开应用并把定位参数带进去。
     *
     * 必须带**未装饰**的任务名：页面里没有「运行中 · 」前缀。工作区键也一起带，
     * 页面被原生承载顶掉时，壳需要靠 `(workspaceKey, sessionId)` 预设重载后要回到
     * 哪个任务。MainActivity 目前还没消费这些 extra，接线在主线完成。
     */
    private fun taskIntent(
        workspaceKey: String,
        sessionId: String,
        title: String,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            action = ACTION_LOCATE_TASK
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_TASK_TITLE, title)
            if (workspaceKey.isNotEmpty()) putExtra(EXTRA_WORKSPACE_KEY, workspaceKey)
        }
        return PendingIntent.getActivity(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun dismissIntent(id: Int): PendingIntent {
        val intent = Intent(appContext, CardDismissReceiver::class.java).apply {
            action = CardDismissReceiver.ACTION_DISMISS
            putExtra(CardDismissReceiver.EXTRA_ID, id)
        }
        return PendingIntent.getBroadcast(
            appContext,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun downloadIntent(receiver: Class<*>, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(appContext, receiver).apply { this.action = action }
        return PendingIntent.getBroadcast(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 标记「这是我们的完成卡」，供进程重启后的认领扫描识别。 */
    private fun finishedCardExtras(): Bundle = Bundle().apply {
        putBoolean(EXTRA_FINISHED_CARD, true)
    }

    /**
     * 提升集合变化与资格自检各记一行日志。
     *
     * 真机上报「流体云卡片消失又重建」而通知本体从没被撤过时，唯一能看见的原因就是
     * **提升集合变了**（ColorOS 会收回不再提升的卡，下一拍再提升就像重建）。
     * 资格自检只在提升数量变化时打，避免每个预览增量都刷屏。
     */
    private fun logPromotionChanges(promoted: Set<Int>, running: List<RunningTask>) {
        if (promoted != lastPromotedIds) {
            lastPromotedIds = promoted
            Log.i(TAG, "提升集合变化: [${promoted.joinToString(" ") { "#$it" }}]（运行 ${running.size} 个）")
        }
        if ((running.isNotEmpty() || finishedCards.isNotEmpty()) && lastPromotedCount != promoted.size) {
            lastPromotedCount = promoted.size
            val channel = platform?.getNotificationChannel(CHANNEL_RUNNING)
            val atOrBelowMin = channel?.importance?.let { it <= NotificationManager.IMPORTANCE_MIN } ?: false
            Log.i(TAG, LiveUpdate.describeEligibility(appContext, channelImportanceMin = atOrBelowMin))
        }
    }

    /** 瞬态通知（完成/待确认）的 id 号段，与任务卡号段分开，互不覆盖。 */
    private val transientId = AtomicInteger(ID_TRANSIENT_BASE)

    private fun nextId(): Int {
        while (true) {
            val current = transientId.get()
            val next = if (current >= ID_TRANSIENT_BASE + 100_000) {
                ID_TRANSIENT_BASE + 1
            } else {
                current + 1
            }
            if (transientId.compareAndSet(current, next)) return next
        }
    }

    /** 上一次的提升集合（诊断用）。 */
    private var lastPromotedIds: Set<Int> = emptySet()

    /** 上一次的提升数量（决定资格自检日志打不打）。 */
    private var lastPromotedCount = -1

    /** 工作区键的最后一段路径，用作没有进展文本时的正文兜底。 */
    private fun workspaceNameOf(key: String): String {
        val name = key.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
        return name.ifEmpty { key }
    }

    companion object {

        const val CHANNEL_RUNNING = "running_tasks"
        const val CHANNEL_ATTENTION = "task_attention"
        const val CHANNEL_COMPLETED = "task_completed"
        const val CHANNEL_KEEPALIVE = "keepalive"

        const val GROUP_RUNNING = "running_tasks"

        /** 前台服务通知 / 空闲态。 */
        const val ID_SERVICE = 1

        /** 「N 个任务运行中」组摘要。 */
        const val ID_GROUP_SUMMARY = 2

        /** 下载进度卡。 */
        const val ID_DOWNLOAD = 3

        // 通知点击的定位参数。MainActivity 尚未消费，主线接线时直接复用这些键名。
        const val ACTION_LOCATE_TASK = "app.zemote.action.LOCATE_TASK"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_TASK_TITLE = "task_title"
        const val EXTRA_WORKSPACE_KEY = "workspace_key"

        /**
         * 小图标暂用系统状态栏图标：本次改动限定在 notify 包内，不能往 `res/drawable`
         * 加资源。主线补一个纯白 vector（`ic_stat_zemote`）后把这里换掉即可。
         */
        private val SMALL_ICON = android.R.drawable.stat_notify_more
        private val SMALL_ICON_DOWNLOAD = android.R.drawable.stat_sys_download

        /** 完成卡的 marker extra（进程重启后认领用）。 */
        private const val EXTRA_FINISHED_CARD = "zemote.finishedCard"

        private const val ID_TRANSIENT_BASE = 2_000_000

        private const val REQUEST_SERVICE = 10
        private const val REQUEST_PAUSE = 11
        private const val REQUEST_CANCEL = 12

        private const val TAG = "ZemoteNotifier"

        @Volatile
        private var instance: Notifier? = null

        /** 进程级单例；业务侧不必自己持有 Notifier。 */
        fun get(context: Context): Notifier {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: Notifier(context.applicationContext).also { instance = it }
            }
        }

        /**
         * 由 `(workspaceKey, sessionId)` 派生的稳定通知 id。
         *
         * 同一任务在整轮运行里 id 恒定——这是「完成时原地改状态」能成立的前提。
         * 号段 [100_000, 900_000) 与固定 id（1/2/3）和瞬态号段（≥2_000_000）都不重叠。
         */
        fun notificationIdFor(workspaceKey: String, sessionId: String): Int {
            val raw = 31 * workspaceKey.hashCode() + sessionId.hashCode()
            return (raw and 0x7FFFFFFF) % 800_000 + 100_000
        }

        /**
         * 前台服务通知的正文：最多 5 条 `• 标题 — 正文`，总长超过 600 字截断。
         * 标题已经带状态前缀，正文不再重复。
         */
        fun runningBody(tasks: List<RunningTask>): String {
            if (tasks.isEmpty()) return ""
            val lines = tasks.take(5).map {
                "• ${NotifyStrings.taskTitle(it.status.label, singleLine(it.title))} — ${singleLine(it.body)}"
            }
            val text = lines.joinToString("\n")
            return if (text.length > 600) text.take(597) + "…" else text
        }
    }
}

/**
 * 单行折叠：通知标题只给系统一行（流体云卡片尤其如此），正文里的换行会把标题
 * 挤成两行、吃掉正文那一行。定位用的原始标题不经过这个函数。
 */
private fun singleLine(value: String): String = value.replace(Regex("\\s+"), " ").trim()
