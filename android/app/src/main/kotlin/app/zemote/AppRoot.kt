package app.zemote

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zemote.data.AppTheme
import app.zemote.log.ZLog
import app.zemote.ui.AppContainer
import app.zemote.ui.NotificationGate
import app.zemote.ui.nav.AppNavHost
import app.zemote.ui.theme.ZemoteTheme

/**
 * 应用根节点：装配 [AppContainer] + 导航宿主。
 *
 * ## 为什么容器在这里取
 *
 * `AndroidManifest.xml` 不在本次改动范围内，没有地方挂 `Application` 子类，所以容器用
 * [AppContainer.get] 的进程级单例（理由与 `DeviceStore` 单实例的要求见 [AppContainer] 的文档）。
 * `remember(context)` 只是避免每次重组都去问一次单例。
 *
 * ## 主题
 *
 * 主题由**设置**驱动（`AppSettings.theme`）：跟随系统 / 深色 / 浅色。
 * 这里再包一层 [ZemoteTheme] 是为了让「设置里改主题」真的生效——`MainActivity` 那层
 * `ZemoteTheme` 用的是系统默认值，是给还没读到设置时的首帧兜底的。
 *
 * TODO(UI): 界面层接管主题时，把 `MainActivity` 外层那个 `ZemoteTheme` 去掉，
 *   只留这里的这一层（现在嵌套两层是无害的，但重复）。
 */
@Composable
fun AppRoot() {
    val context = LocalContext.current
    // 兜底：Activity 之外的入口（预览/测试宿主）也应把日志底座装起来；幂等。
    remember(context) { ZLog.install(context.applicationContext) }
    val container = remember(context) { AppContainer.get(context) }
    val settings by container.appSettings.collectAsStateWithLifecycle()

    val darkTheme = when (settings.theme) {
        AppTheme.DARK -> true
        AppTheme.LIGHT -> false
        AppTheme.SYSTEM -> isSystemInDarkTheme()
    }

    LaunchedEffect(Unit) { ZLog.ui("root", "first composition") }
    LaunchedEffect(settings.theme) { ZLog.ui("root", "theme=${settings.theme}") }
    DisposableEffect(Unit) { onDispose { ZLog.ui("root", "composition disposed") } }

    ZemoteTheme(darkTheme = darkTheme) {
        // 颜色令牌（含底色）在 ui/theme 里，这里只是把根节点的背景铺满。
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // 每次启动检查通知权限；未授权时进不去主界面（UI-SPEC §0）。
            NotificationGate {
                AppNavHost(container = container)
            }
        }
    }
}
