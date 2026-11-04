package app.zemote.notify

/**
 * 任务状态词。
 *
 * live（运行中/等待确认）与收尾（已完成/已中断/已失败）是两组语义：
 * 前者是流体云芯片上会随任务变化的词，后者是卡片「原地改状态」后的定格词。
 * 状态词必须短——芯片最大宽度只有 96dp，显示不全时系统会只留图标。
 */
enum class TaskStatus {
    RUNNING,
    WAITING,
    COMPLETED,
    INTERRUPTED,
    FAILED,
    ;

    /** 通知标题与流体云芯片共用的短状态词。 */
    val label: String
        get() = when (this) {
            RUNNING -> NotifyStrings.STATUS_RUNNING
            WAITING -> NotifyStrings.STATUS_WAITING
            COMPLETED -> NotifyStrings.STATUS_COMPLETED
            INTERRUPTED -> NotifyStrings.STATUS_INTERRUPTED
            FAILED -> NotifyStrings.STATUS_FAILED
        }

    /** 终态：卡片不再随进展刷新，只等用户点「知道了」或滑掉。 */
    val isFinished: Boolean
        get() = this == COMPLETED || this == INTERRUPTED || this == FAILED
}

/**
 * 决定哪些卡片向系统请求流体云（Android 16 Live Updates）提升位。
 *
 * 排序规则：
 *   1. **等待确认**的任务优先（它们是要用户动手的那一类）；
 *   2. 其次按最近活动时间；
 *   3. 被留住的**已完成**卡排在所有在跑的任务之后，且只取最新
 *      [MAX_FINISHED_PROMOTED] 张。
 *
 * **没有「总共几张」的上限**：在跑的任务有几个就提升几个。曾按「ColorOS 只并发
 * 提升 2 张」设过 `MAX_PROMOTED = 2`，但真机上三个任务同跑时三张卡同时显示，
 * `dumpsys notification` 里也是三条 `PROMOTED_ONGOING`——那是我们自己拍的门，
 * 不是平台的墙。真正会无限增长的只有「留到用户清掉」的完成卡，所以界限只加在
 * 它们身上；超出者不是消失，而是降级成普通可滑除通知（记录不丢）。
 *
 * 本文件刻意保持**纯 Kotlin**（不 import 任何 Android 类），便于在 JVM 单测里
 * 逐条钉住排序与上限。
 */
object PromotionPolicy {

    /**
     * 最多几张**已完成**卡可以继续占提升位。
     *
     * 完成卡会一直留到用户清掉，不设限会越积越多、最后把在跑的任务挤出芯片位。
     */
    const val MAX_FINISHED_PROMOTED = 3

    /** 参与排序的运行中任务（只取策略需要的字段，避免策略依赖渲染模型）。 */
    data class Running(
        val id: Int,
        val status: TaskStatus,
        val activityAt: Long,
    )

    /** 一张仍在账本里的完成卡。 */
    data class Finished(
        val id: Int,
        val completedAt: Long,
    )

    /**
     * @return 所有运行中任务的 id（已按优先级排序），加上最新的
     *   [maxFinished] 张完成卡。
     *
     * 完成卡永远**输给**在跑的任务：芯片位是给「正在发生的事」的，十分钟前
     * 结束的任务不能把正在跑的任务挤下去。丢掉卡位不等于删卡——它只是降级成
     * 普通可滑除通知（见 `Notifier.syncRunningTasks`）。
     */
    fun choose(
        running: List<Running>,
        finished: List<Finished> = emptyList(),
        maxFinished: Int = MAX_FINISHED_PROMOTED,
    ): Set<Int> {
        val live = running
            .sortedWith(
                compareByDescending<Running> { it.status == TaskStatus.WAITING }
                    .thenByDescending { it.activityAt },
            )
            .map { it.id }
        val done = if (maxFinished <= 0) {
            emptyList()
        } else {
            finished.sortedByDescending { it.completedAt }.take(maxFinished).map { it.id }
        }
        // LinkedHashSet：顺序即优先级序，也是「提升集合变化」日志的顺序。
        return (live + done).toCollection(LinkedHashSet<Int>())
    }
}
