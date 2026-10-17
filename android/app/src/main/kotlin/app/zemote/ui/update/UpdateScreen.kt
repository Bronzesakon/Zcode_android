package app.zemote.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.zemote.update.InstallResult

/**
 * 关于与更新页（检查 → 下载 → 安装）。
 *
 * 无状态：状态来自 [UpdateUiState]，动作走回调，接线在 `AppNavHost`。
 *
 * 本文件**不做视觉决策**（颜色/字号/间距/圆角/阴影/动效一律不设），需要设计的位置留 `TODO(UI)`。
 * 进度先用文字表达（`TODO(UI)` 里注明换成进度条），避免在骨架里定任何尺寸/颜色。
 */
@Composable
fun UpdateScreen(
    state: UpdateUiState,
    onBack: () -> Unit,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    onOpenInstallSettings: () -> Unit,
    onDismissError: () -> Unit,
    onDismissInstallResult: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // TODO(UI): 页头（返回 + 标题）。
        Row {
            Button(onClick = onBack) { Text("返回") }
            Text(text = "关于与更新")
        }

        Text(text = "当前版本（versionCode）：${state.currentVersionCode}")

        Row {
            Button(onClick = onCheck, enabled = state.phase != UpdatePhase.Checking) {
                Text(text = if (state.phase == UpdatePhase.Checking) "正在检查…" else "检查更新")
            }
        }

        when (state.phase) {
            UpdatePhase.Idle -> Text(text = "点「检查更新」拉取最新版本清单。")

            UpdatePhase.Checking -> {
                // TODO(UI): 检查中的转圈。
                CircularProgressIndicator()
                Text(text = "正在检查…")
            }

            UpdatePhase.UpToDate -> Text(text = "已经是最新版本。")

            UpdatePhase.NoRelease -> Text(text = "还没有发布过预发布版本（不是错误）。")

            UpdatePhase.UpdateAvailable, UpdatePhase.Downloading, UpdatePhase.ReadyToInstall -> {
                state.manifest?.let { manifest ->
                    Text(text = "新版本：${manifest.versionName}（versionCode ${manifest.versionCode}）")
                    Text(text = "包大小：${manifest.sizeBytes} 字节，ABI ${manifest.abi}")
                }
            }

            UpdatePhase.Failed -> Unit // 失败文案统一走下面的 error 条
        }

        when (state.phase) {
            UpdatePhase.UpdateAvailable -> {
                Button(onClick = onDownload, enabled = state.canDownload) { Text("下载更新包") }
            }

            UpdatePhase.Downloading -> {
                // TODO(UI): 进度条（progress.fraction == null 时用不确定进度条）。
                val progress = state.progress
                Text(
                    text = if (progress?.fraction == null) {
                        "正在下载…（总长未知）已下载 ${progress?.bytesDownloaded ?: 0} 字节"
                    } else {
                        "正在下载… ${(progress.fraction!! * 100).toInt()}%" +
                            "（${progress.bytesDownloaded}/${progress.totalBytes} 字节）"
                    },
                )
                Button(onClick = onCancelDownload) { Text("取消下载") }
            }

            UpdatePhase.ReadyToInstall -> {
                Text(text = "已下载并通过校验，可以安装。")
                Button(onClick = onInstall) { Text("安装") }
            }

            else -> Unit
        }

        state.releasePageUrl?.let { url ->
            // TODO(UI): 用 Intent（ACTION_VIEW）打开发布页；现在只把地址显示出来。
            Text(text = "发布页：$url")
        }

        state.installResult?.let { result ->
            Text(text = result.userMessage)
            if (result is InstallResult.NeedInstallPermission) {
                // 系统「安装未知应用」开关没开：引导用户去授权页。
                Button(onClick = onOpenInstallSettings) { Text("去授权") }
            }
            Button(onClick = onDismissInstallResult) { Text("好") }
        }

        state.error?.let { message ->
            // TODO(UI): 错误条。
            Text(text = message)
            Button(onClick = onDismissError) { Text("知道了") }
        }
    }
}
