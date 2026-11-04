package app.zemote

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.zemote.log.ZLog
import app.zemote.ui.AppContainer
import app.zemote.ui.theme.LocalThemeController
import app.zemote.ui.theme.ThemeController
import app.zemote.ui.theme.ZemoteTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须最先安装：Compose、引擎、AppContainer 里的任何日志都要有落点。
        ZLog.install(applicationContext)
        ZLog.ui("activity", "onCreate intent=${intent.action ?: "-"} flags=0x${intent.flags.toString(16)}")
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 通知点击（含进程被杀后冷启动）先落到容器，再由导航层消费。
        AppContainer.get(this).onNotifyIntent(intent)
        setContent {
            val context = LocalContext.current
            val themeController = remember { ThemeController(context) }
            CompositionLocalProvider(LocalThemeController provides themeController) {
                ZemoteTheme {
                    AppRoot()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ZLog.ui("activity", "onStart")
        AppContainer.get(this).notifications.onAppForeground(true)
    }

    override fun onResume() {
        super.onResume()
        ZLog.ui("activity", "onResume")
    }

    override fun onPause() {
        ZLog.ui("activity", "onPause")
        super.onPause()
    }

    override fun onStop() {
        ZLog.ui("activity", "onStop")
        AppContainer.get(this).notifications.onAppForeground(false)
        super.onStop()
    }

    override fun onDestroy() {
        ZLog.ui("activity", "onDestroy finishing=$isFinishing changingConfigurations=$isChangingConfigurations")
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        ZLog.ui("activity", "onNewIntent action=${intent.action ?: "-"}")
        setIntent(intent)
        AppContainer.get(this).onNotifyIntent(intent)
    }
}
