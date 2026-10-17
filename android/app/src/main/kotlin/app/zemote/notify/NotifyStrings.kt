package app.zemote.notify

/**
 * 通知与流体云的全部用户可见文案。
 *
 * 为什么先集中在这里：这些字符串最终要搬进 `res/values/strings.xml`（多语言），
 * 但本次改动被限定在 `app.zemote.notify` 包内，不能碰 `res/`。集中成一个纯 Kotlin
 * 对象后，搬运时只需把常量替换成 `context.getString(...)`，不必在渲染代码里逐个
 * 翻找散落的字面量；也避免同一句「运行中」在标题、芯片、诊断日志里出现三种写法。
 */
object NotifyStrings {

    // ---- 渠道（重要性创建后 App 不能改，名字/描述只在首次创建时生效）----
    const val CHANNEL_KEEPALIVE_NAME = "后台运行"
    const val CHANNEL_KEEPALIVE_DESC = "保持与桌面的连接；静默常驻，不打扰"
    const val CHANNEL_RUNNING_NAME = "运行中的任务"
    const val CHANNEL_RUNNING_DESC = "每个运行中的任务一条常驻通知；静默更新"
    const val CHANNEL_COMPLETED_NAME = "任务完成"
    const val CHANNEL_COMPLETED_DESC = "任务结束时的提醒"
    const val CHANNEL_ATTENTION_NAME = "需要处理"
    const val CHANNEL_ATTENTION_DESC = "任务等待你确认时的提醒"

    // ---- 状态词（流体云芯片也用它，所以必须短）----
    const val STATUS_RUNNING = "运行中"
    const val STATUS_WAITING = "等待确认"
    const val STATUS_COMPLETED = "已完成"
    const val STATUS_INTERRUPTED = "已中断"
    const val STATUS_FAILED = "已失败"

    // ---- 前台服务 ----
    const val SERVICE_TITLE_IDLE = "Zemote 正在后台运行"
    const val SERVICE_TEXT_IDLE = "保持与桌面的连接"
    fun serviceTitleRunning(count: Int): String = "$count 个任务运行中"

    // ---- 任务卡 ----
    const val GROUP_RUNNING_TITLE = "运行中的任务"
    fun groupSummary(count: Int): String = "$count 个任务运行中"

    /**
     * 卡片标题 = `状态 · 任务名`。
     *
     * 标题只给系统一行（流体云卡片尤其如此），任务名为空时只留状态词，
     * 不留下一个孤零零的分隔符。
     */
    fun taskTitle(status: String, title: String): String =
        if (title.isEmpty()) status else "$status · $title"

    // ---- 完成 / 待确认 ----
    const val COMPLETED_TITLE = "任务完成"
    const val COMPLETED_TITLE_ENDED = "任务已结束"
    const val ATTENTION_TITLE = "需要你的处理"
    const val ACTION_DISMISS = "知道了"

    // ---- 下载 ----
    const val DOWNLOAD_TITLE = "下载更新"
    const val DOWNLOAD_TEXT = "正在下载安装包"
    const val DOWNLOAD_INDETERMINATE = "下载中"
    const val ACTION_PAUSE = "暂停"
    const val ACTION_CANCEL = "取消"
    fun downloadText(percent: Int): String = "正在下载安装包 $percent%"
    fun downloadChip(percent: Int): String = "下载中 $percent%"

    // ---- 流体云资格自检（诊断日志）----
    fun eligibilityBelowApi(api: Int): String =
        "流体云: 本机为 API $api，需 Android 16(API 36) 及以上"

    const val ELIGIBILITY_PREFIX = "流体云: "
    const val ELIGIBILITY_AVAILABLE = "可用"
    const val ELIGIBILITY_BLOCKED = "系统已关闭本应用的推广通知"
    const val ELIGIBILITY_CONDITIONS =
        " · 样式=BigText ✓ · ongoing ✓ · 有标题 ✓ · 无自定义视图 ✓ · 非组摘要 ✓ · 未着色 ✓"
    const val ELIGIBILITY_CHANNEL = " · 渠道重要性>MIN "
    const val ELIGIBILITY_YES = "✓"
    const val ELIGIBILITY_NO = "✗"
}
