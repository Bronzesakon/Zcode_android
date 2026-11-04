package app.zemote.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.zemote.data.AppSettings
import app.zemote.data.AppTheme

/**
 * 设置页（主题 / 语言 / 字号 / 代码字号 / 详细协议帧日志 / 更新通道）。
 *
 * 无状态：状态来自 [SettingsUiState]，动作走回调，接线在 `AppNavHost`。
 *
 * 本文件**不做视觉决策**（颜色/字号/间距/圆角/阴影/动效一律不设），需要设计的位置留 `TODO(UI)`。
 * 控件一律用 [Button] 的朴素形态（例如布尔项用「已开启 / 已关闭」按钮），
 * 界面层后续换成 `Switch` / `RadioGroup` / `Slider` 时不需要动任何逻辑。
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onThemeChange: (AppTheme) -> Unit,
    onLocaleChange: (String) -> Unit,
    onTextScaleChange: (Float) -> Unit,
    onCodeFontSizeChange: (Float) -> Unit,
    onVerboseProtocolFramesChange: (Boolean) -> Unit,
    onReceiveBetaUpdatesChange: (Boolean) -> Unit,
    onReset: () -> Unit,
    onOpenAboutUpdate: () -> Unit,
    onDismissError: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // TODO(UI): 页头（返回 + 标题）。
        Row {
            Button(onClick = onBack) { Text("返回") }
            Text(text = "设置")
        }

        if (state.loading) {
            CircularProgressIndicator()
            Text(text = "正在读取设置…")
        }

        state.error?.let { message ->
            // TODO(UI): 错误条。
            Text(text = message)
            Button(onClick = onDismissError) { Text("知道了") }
        }

        state.notice?.let { message ->
            Text(text = message)
            Button(onClick = onDismissNotice) { Text("好") }
        }

        // ---- 主题 ----
        // TODO(UI): 主题选择（网页版是 DropdownMenu + RadioGroup，三态）。
        Text(text = "主题")
        Row {
            AppTheme.entries.forEach { theme ->
                Button(onClick = { onThemeChange(theme) }) {
                    Text(text = theme.name + if (theme == state.settings.theme) " ✓" else "")
                }
            }
        }

        // ---- 语言 ----
        // TODO(UI): 语言选择（当前只有 zh-CN / en-US 两套）。
        Text(text = "界面语言")
        Row {
            Button(onClick = { onLocaleChange(ZH_CN) }) {
                Text(text = ZH_CN + if (state.settings.locale == ZH_CN) " ✓" else "")
            }
            Button(onClick = { onLocaleChange(EN_US) }) {
                Text(text = EN_US + if (state.settings.locale == EN_US) " ✓" else "")
            }
        }

        // ---- 界面字号 ----
        // TODO(UI): 字号档位（网页版是语义字号令牌；这里是倍率，用滑动条更合适）。
        Text(text = "界面字号倍率：${state.settings.textScale}")
        Row {
            Button(onClick = { onTextScaleChange(AppSettings.TEXT_SCALE_MIN) }) { Text("最小") }
            Button(onClick = { onTextScaleChange(1.0f) }) { Text("默认") }
            Button(onClick = { onTextScaleChange(AppSettings.TEXT_SCALE_MAX) }) { Text("最大") }
        }

        // ---- 代码字号 ----
        Text(text = "代码字号：${state.settings.codeFontSize}")
        Row {
            Button(onClick = { onCodeFontSizeChange(AppSettings.CODE_FONT_SIZE_MIN) }) { Text("最小") }
            Button(onClick = { onCodeFontSizeChange(AppSettings.CODE_FONT_SIZE_MAX) }) { Text("最大") }
        }

        // ---- 布尔项（朴素按钮形态，界面层换成 Switch）----
        // TODO(UI): 开关样式。
        Text(text = "记录完整 relay 帧（可能含敏感数据）")
        Button(onClick = {
            onVerboseProtocolFramesChange(!state.settings.verboseProtocolFrames)
        }) {
            Text(text = if (state.settings.verboseProtocolFrames) "已开启" else "已关闭")
        }

        Text(text = "接收 Beta 更新")
        Button(onClick = {
            onReceiveBetaUpdatesChange(!state.settings.receiveBetaUpdates)
        }) {
            Text(text = if (state.settings.receiveBetaUpdates) "已开启" else "已关闭")
        }

        Row {
            Button(onClick = onReset) { Text("恢复默认设置") }
            Button(onClick = onOpenAboutUpdate) { Text("关于与更新") }
        }
    }
}

/** 目前支持的两种界面语言（与 `AppSettings.locale` 的取值一致）。 */
private const val ZH_CN = "zh-CN"
private const val EN_US = "en-US"
