package app.zemote

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.zemote.ui.AppContainer
import app.zemote.ui.theme.ZemoteTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 通知点击（含进程被杀后冷启动）先落到容器，再由导航层消费。
        AppContainer.get(this).onNotifyIntent(intent)
        setContent {
            ZemoteTheme {
                AppRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        AppContainer.get(this).onNotifyIntent(intent)
    }
}
