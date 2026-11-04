package app.zemote.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.zemote.ui.common.NoticeBar
import app.zemote.ui.common.OutlineActionButton
import app.zemote.ui.common.PageHeader
import app.zemote.ui.common.PrimaryActionButton
import app.zemote.ui.common.SettingsDivider
import app.zemote.ui.common.SettingsGroupHeader
import app.zemote.ui.common.SettingsRow
import app.zemote.ui.common.ZemoteCard
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteType
import app.zemote.update.InstallResult

/**
 * 关于与更新页（检查 → 下载 → 安装）。
 *
 * 无状态：状态来自 [UpdateUiState]，动作走回调，接线在 `AppNavHost`。
 * 视觉：44dp 页头、12dp 圆角卡片、行式信息、4dp 栅格；下载进度用 [LinearProgressIndicator]。
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
    val colors = LocalZemoteColors.current

    Column(modifier = Modifier.fillMaxSize().background(colors.background)) {
        PageHeader(title = "关于与更新", onBack = onBack)

        state.error?.let { message ->
            NoticeBar(text = message, destructive = true, onClose = onDismissError)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(title = "当前版本") {
                SettingsRow(
                    title = "版本号",
                    trailing = {
                        Text(
                            text = "${state.currentVersionCode}",
                            style = ZemoteType.uiSm.copy(fontFamily = FontFamily.Monospace),
                            color = colors.foregroundSubtle,
                        )
                    },
                )
                state.releasePageUrl?.let { url ->
                    SettingsDivider()
                    SettingsRow(
                        title = "发布页",
                        description = url,
                    )
                }
            }

            SectionCard(title = "更新") {
                when (state.phase) {
                    UpdatePhase.Idle -> {
                        Text(
                            text = "点「检查更新」拉取最新版本清单。",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            style = ZemoteType.uiSm,
                            color = colors.foregroundSubtle,
                        )
                        ActionRow {
                            PrimaryActionButton("检查更新", onClick = onCheck)
                        }
                    }

                    UpdatePhase.Checking -> {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Text("正在检查…", style = ZemoteType.uiSm, color = colors.foregroundSubtle)
                        }
                    }

                    UpdatePhase.UpToDate -> {
                        Text(
                            text = "已经是最新版本。",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            style = ZemoteType.uiSm,
                            color = colors.foregroundSubtle,
                        )
                        ActionRow {
                            OutlineActionButton("再次检查", onClick = onCheck)
                        }
                    }

                    UpdatePhase.NoRelease -> {
                        Text(
                            text = "还没有发布过预发布版本（不是错误）。",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            style = ZemoteType.uiSm,
                            color = colors.foregroundSubtle,
                        )
                        ActionRow {
                            OutlineActionButton("再次检查", onClick = onCheck)
                        }
                    }

                    UpdatePhase.UpdateAvailable,
                    UpdatePhase.Downloading,
                    UpdatePhase.ReadyToInstall -> {
                        state.manifest?.let { manifest ->
                            SettingsRow(
                                title = "新版本 ${manifest.versionName}",
                                description = "versionCode ${manifest.versionCode} · ABI ${manifest.abi}",
                            )
                            SettingsDivider()
                            SettingsRow(
                                title = "包大小",
                                trailing = {
                                    Text(
                                        text = "${manifest.sizeBytes} 字节",
                                        style = ZemoteType.uiSm.copy(fontFamily = FontFamily.Monospace),
                                        color = colors.foregroundSubtle,
                                    )
                                },
                            )
                        }
                        when (state.phase) {
                            UpdatePhase.UpdateAvailable -> ActionRow {
                                PrimaryActionButton(
                                    text = "下载更新包",
                                    onClick = onDownload,
                                    enabled = state.canDownload,
                                )
                            }

                            UpdatePhase.Downloading -> {
                                val progress = state.progress
                                val fraction = progress?.fraction
                                Column(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (fraction != null) {
                                        LinearProgressIndicator(
                                            progress = { fraction },
                                            modifier = Modifier.fillMaxWidth(),
                                            color = colors.primary,
                                            trackColor = colors.border,
                                        )
                                        Text(
                                            text = "正在下载… ${(fraction * 100).toInt()}%" +
                                                "（${progress.bytesDownloaded}/${progress.totalBytes} 字节）",
                                            style = ZemoteType.uiSm,
                                            color = colors.foregroundSubtle,
                                        )
                                    } else {
                                        LinearProgressIndicator(
                                            modifier = Modifier.fillMaxWidth(),
                                            color = colors.primary,
                                            trackColor = colors.border,
                                        )
                                        Text(
                                            text = "正在下载…（总长未知）已下载 ${progress?.bytesDownloaded ?: 0} 字节",
                                            style = ZemoteType.uiSm,
                                            color = colors.foregroundSubtle,
                                        )
                                    }
                                }
                                ActionRow {
                                    OutlineActionButton("取消下载", onClick = onCancelDownload)
                                }
                            }

                            UpdatePhase.ReadyToInstall -> {
                                Text(
                                    text = "已下载并通过校验，可以安装。",
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    style = ZemoteType.uiSm,
                                    color = colors.foregroundSubtle,
                                )
                                ActionRow {
                                    PrimaryActionButton("安装", onClick = onInstall)
                                }
                            }

                            else -> Unit
                        }
                    }

                    UpdatePhase.Failed -> {
                        Text(
                            text = "检查更新失败。",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            style = ZemoteType.uiSm,
                            color = colors.destructive,
                        )
                        ActionRow {
                            OutlineActionButton("重试", onClick = onCheck)
                        }
                    }
                }
            }

            state.installResult?.let { result ->
                SectionCard(title = "安装") {
                    Text(
                        text = result.userMessage,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = ZemoteType.uiSm,
                        color = colors.foregroundSubtle,
                    )
                    ActionRow {
                        if (result is InstallResult.NeedInstallPermission) {
                            // 系统「安装未知应用」开关没开：引导用户去授权页。
                            PrimaryActionButton("去授权", onClick = onOpenInstallSettings)
                        }
                        OutlineActionButton("知道了", onClick = onDismissInstallResult)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 分组：分组标题 + 12dp 圆角卡片（视觉件见 `ui/common`）。 */
@Composable
private fun SectionCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column {
        SettingsGroupHeader(title)
        ZemoteCard(content = content)
    }
}

/** 卡片底部动作行：左对齐、4dp 栅格、上边 1px 分隔线。 */
@Composable
private fun ActionRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    SettingsDivider()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}
