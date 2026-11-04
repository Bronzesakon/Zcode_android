package app.zemote.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.zemote.notify.NotificationPermission
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteType

/**
 * 启动时的通知权限门。
 *
 * 需求（UI-SPEC §0）：每次启动检查 `POST_NOTIFICATIONS`，未授权时**进不去主界面**。
 * 授权后正常渲染 [content]；用户永久拒绝时给「去系统设置」与「退出应用」两个出口，
 * 不反复弹系统对话框。
 */
@Composable
fun NotificationGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(NotificationPermission.isGranted(context)) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = NotificationPermission.isGranted(context) }

    // 从系统设置回来时重新检查。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = NotificationPermission.isGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (granted) {
        content()
    } else {
        PermissionPage(
            onRequest = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onOpenSettings = {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                        putExtra("app_package", context.packageName)
                        putExtra("app_uid", context.applicationInfo.uid)
                    }
                }
                context.startActivity(intent)
            },
            onExit = {
                (context as? android.app.Activity)?.finishAffinity()
            },
        )
    }
}

@Composable
private fun PermissionPage(
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onExit: () -> Unit,
) {
    val colors = LocalZemoteColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Notifications,
                contentDescription = null,
                tint = colors.foreground,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = "需要通知权限",
            style = ZemoteType.uiXl,
            fontWeight = FontWeight.Medium,
            color = colors.foreground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "任务完成、出错或等待你授权时，Zemote 需要通过通知提醒你；后台连接也依赖前台服务通知。",
            style = ZemoteType.uiBase,
            color = colors.foregroundSubtle,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Surface(
            onClick = onRequest,
            shape = RoundedCornerShape(10.dp),
            color = colors.primary,
        ) {
            Text(
                text = "允许通知",
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 11.dp),
                style = ZemoteType.uiBase,
                fontWeight = FontWeight.Medium,
                color = colors.primaryForeground,
            )
        }
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = onOpenSettings) { Text("已拒绝？去系统设置开启") }
        TextButton(onClick = onExit) { Text("退出应用") }
    }
}
