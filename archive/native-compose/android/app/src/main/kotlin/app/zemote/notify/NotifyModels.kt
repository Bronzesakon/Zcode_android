package app.zemote.notify

/**
 * 一张运行中任务卡的全部内容。
 *
 * `title` 是**未装饰**的任务名：它同时用于通知标题（渲染时才加状态前缀）和点击
 * 定位。定位器拿这个字符串去页面文本里匹配，而页面里没有「运行中 · 」前缀——
 * 把装饰后的标题塞进去会让「点通知跳任务」静默失效。
 */
data class RunningTask(
    val id: Int,
    val title: String,
    val body: String,
    val status: TaskStatus = TaskStatus.RUNNING,
    /** 最近一次活动时间，用于同优先级内的排序（越大越靠前）。 */
    val activityAt: Long = 0L,
    val sessionId: String = "",
    val workspaceKey: String = "",
)

/**
 * 任务收尾事件。
 *
 * `id` 必须与该任务运行卡的 id **相同**：结束不是撤卡重发，而是同一条通知记录
 * 原地改状态。换 id 会让卡片闪一下、可听提示白响，还会让同一任务的两张卡去抢
 * 两个提升位（真机上验证过）。
 */
data class FinishedTask(
    val id: Int,
    val title: String,
    val body: String,
    val status: TaskStatus = TaskStatus.COMPLETED,
    /** 收尾时刻；0 表示由 Notifier 用当前时间兜底。 */
    val completedAt: Long = 0L,
    val sessionId: String = "",
    val workspaceKey: String = "",
)

/**
 * 等待用户确认的任务。
 *
 * 常驻卡本身是静音的（`running_tasks` 渠道 LOW），所以「等待确认」要额外发一条
 * `task_attention` 渠道的有声通知；渠道重要性创建后不能改，这也是为什么它必须
 * 是独立渠道而不是给同一张卡换渠道。
 */
data class AttentionTask(
    val title: String,
    val body: String,
    val sessionId: String = "",
    val workspaceKey: String = "",
)

/**
 * 下载进度卡。
 *
 * @param percent 0..100；`indeterminate = true` 时不画确定进度。
 * @param text 正文；留空时由 Notifier 按百分比补一句默认文案。
 * @param shortCriticalText 流体云芯片文案；留空时用「下载中 N%」。
 */
data class DownloadProgress(
    val percent: Int,
    val text: String = "",
    val indeterminate: Boolean = false,
    val title: String = NotifyStrings.DOWNLOAD_TITLE,
    val shortCriticalText: String? = null,
)
