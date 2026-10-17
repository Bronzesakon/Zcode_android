package app.zemote.notify

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Android 16 Live Updates —— ColorOS 16 把它渲染成**流体云**。
 *
 * ## 为什么手写 extras 而不调 API
 *
 * `Notification.Builder.setRequestPromotedOngoing()` / `setShortCriticalText()` 是
 * API 36 新增；androidx 的封装只存在于 androidx.core **1.17.0**，而那个 AAR 声明
 * `minCompileSdk=36` / `minAGP=8.9.1`，采用它会迫使 Gradle/AGP 一起升级。可是平台侧
 * 的效果就是往通知 extras 里写两个键（androidx 源码里也是这么实现的）：
 *
 * ```
 * EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
 * EXTRA_SHORT_CRITICAL_TEXT      = "android.shortCriticalText"
 * ```
 *
 * 本项目用 androidx.core 1.15.0、compileSdk 36，所以直接写这两个字面量键。低于
 * API 36 时它们是惰性无害的；本对象的所有方法在低版本上都是 no-op / false。
 *
 * ## 提升条件（平台要求全部满足）
 *
 * 标准样式（BigText 算）· manifest 声明 `POST_PROMOTED_NOTIFICATIONS` · 请求提升 ·
 * ongoing · 有 contentTitle · 无自定义视图 · 非组摘要 · 未着色 · 渠道重要性高于
 * `IMPORTANCE_MIN`。OEM 还可以追加条件。[describeEligibility] 把这些条件写成一行
 * 诊断日志——真机上「流体云为什么没出现」通常只能靠它判断。
 */
object LiveUpdate {

    /** Live Updates 从 Android 16 开始提供。 */
    const val MIN_API = 36

    private const val TAG = "ZemoteLiveUpdate"

    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    private const val EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText"

    /**
     * 系统是否愿意接受本应用的提升请求：Android 16+，且用户没有在设置里关掉
     * 本应用的「推广通知」（`NotificationManager.canPostPromotedNotifications`，
     * API 36 新增，compileSdk 虽是 36，但为了与平台签名解耦仍走反射）。
     */
    fun canPostPromotedNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < MIN_API) return false
        return try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return false
            val method = NotificationManager::class.java.getMethod("canPostPromotedNotifications")
            method.invoke(manager) as? Boolean ?: false
        } catch (e: Exception) {
            Log.w(TAG, "查询流体云可用性失败: ${e.message}")
            false
        }
    }

    /**
     * 标记 [builder] 请求提升，[shortCriticalText] 是状态栏芯片文案
     * （「运行中」「等待确认」「已完成」…）。低于 API 36 直接返回。
     *
     * 调用方仍要保证其余提升条件：ongoing、有 contentTitle、非组摘要、渠道重要性
     * 不是 MIN、没有自定义视图。
     */
    fun requestPromotion(builder: NotificationCompat.Builder, shortCriticalText: String?) {
        if (Build.VERSION.SDK_INT < MIN_API) return
        val extras = Bundle()
        extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
        if (!shortCriticalText.isNullOrEmpty()) {
            extras.putString(EXTRA_SHORT_CRITICAL_TEXT, shortCriticalText)
        }
        builder.addExtras(extras)
    }

    /**
     * 把每一项提升条件写成人能读的一行，供诊断日志使用。
     *
     * @param channelImportanceMin 调用方查到的渠道重要性是否为 `IMPORTANCE_MIN`
     *   （是 ⇒ 这一条不满足）。渠道信息属于 Notifier，故由调用方传入。
     */
    fun describeEligibility(context: Context, channelImportanceMin: Boolean = false): String {
        if (Build.VERSION.SDK_INT < MIN_API) {
            return NotifyStrings.eligibilityBelowApi(Build.VERSION.SDK_INT)
        }
        val allowed = canPostPromotedNotifications(context)
        return buildString {
            append(NotifyStrings.ELIGIBILITY_PREFIX)
            append(if (allowed) NotifyStrings.ELIGIBILITY_AVAILABLE else NotifyStrings.ELIGIBILITY_BLOCKED)
            append(NotifyStrings.ELIGIBILITY_CONDITIONS)
            append(NotifyStrings.ELIGIBILITY_CHANNEL)
            append(
                if (channelImportanceMin) NotifyStrings.ELIGIBILITY_NO else NotifyStrings.ELIGIBILITY_YES,
            )
        }
    }
}
