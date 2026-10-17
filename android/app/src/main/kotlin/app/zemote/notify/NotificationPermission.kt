package app.zemote.notify

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * `POST_NOTIFICATIONS` 的检查与申请。
 *
 * 申请时机很重要：等「有任务在跑」才申请时，用户通常正在别的 App 里，系统弹窗
 * 会被忽略。调用方应在用户进入主界面、注意力还在本应用时调用 [requestIfNeeded]。
 *
 * API 32 及以下没有这个运行时权限，只看系统的通知总开关即可。
 */
object NotificationPermission {

    /** 默认 requestCode；Activity 的 `onRequestPermissionsResult` 按它分流。 */
    const val REQUEST_CODE = 7001

    /** 是否已经可以发通知（运行时权限 + 系统通知总开关）。 */
    fun isGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 未授权时发起申请。
     *
     * @return true 表示这次真的弹了申请（调用方可在回调里再读 [isGranted]）；
     *   false 表示无需申请（低版本 / 已授权）。
     */
    fun requestIfNeeded(activity: Activity, requestCode: Int = REQUEST_CODE): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (isGranted(activity)) return false
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            requestCode,
        )
        return true
    }
}
