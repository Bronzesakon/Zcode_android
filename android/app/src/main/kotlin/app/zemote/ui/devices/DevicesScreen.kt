package app.zemote.ui.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.zemote.data.DeviceRecord
import app.zemote.data.RelayStatus
import app.zemote.ui.common.EmptyState
import app.zemote.ui.common.GhostIconButton
import app.zemote.ui.common.NoticeBar
import app.zemote.ui.common.OutlineActionButton
import app.zemote.ui.common.PageHeader
import app.zemote.ui.common.PrimaryActionButton
import app.zemote.ui.common.ReadOnlyBadge
import app.zemote.ui.common.SettingsDivider
import app.zemote.ui.common.StatusDot
import app.zemote.ui.common.ZemoteCard
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteRadius
import app.zemote.ui.theme.ZemoteType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 设备列表（对应网页版 A1 任务首页的**外壳部分**：页头 + 设备行）。
 *
 * 无状态：状态由 [DevicesUiState] 传入，动作由回调传出，不在这里取 ViewModel。
 * 视觉走令牌：44dp 页头、12dp 圆角卡片 + 1px 边框、行式布局、4dp 栅格；
 * 业务逻辑（重命名行内编辑、删除、打开）与 ViewModel 行为不变。
 */
@Composable
fun DevicesScreen(
    state: DevicesUiState,
    onOpenDevice: (String) -> Unit,
    onRemoveDevice: (String) -> Unit,
    onRenameDevice: (String, String) -> Unit,
    onAddDevice: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAboutUpdate: () -> Unit,
    onRetry: () -> Unit,
    onDismissNotice: () -> Unit,
    onDismissError: () -> Unit,
) {
    val colors = LocalZemoteColors.current
    // 重命名的行内编辑状态：纯界面状态，不进 ViewModel（没有跨界面语义）。
    var renamingId by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().background(colors.background)) {
        PageHeader(
            title = "电脑",
            actions = {
                GhostIconButton(Icons.Default.Settings, "设置", onOpenSettings)
                GhostIconButton(Icons.Default.Info, "关于与更新", onOpenAboutUpdate)
            },
        )

        state.error?.let { message ->
            NoticeBar(text = message, destructive = true, onClose = onDismissError)
        }
        state.notice?.let { message ->
            NoticeBar(text = message, onClose = onDismissNotice)
        }

        when {
            state.loading && state.devices.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Text("正在读取设备列表…", style = ZemoteType.uiSm, color = colors.foregroundSubtle)
                    }
                }
            }

            state.devices.isEmpty() -> {
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    Spacer(Modifier.height(12.dp))
                    EmptyState(
                        title = "还没有连接电脑",
                        description = "扫码或粘贴远控链接，配对一台电脑。",
                        action = { PrimaryActionButton("扫码 / 粘贴配对链接", onAddDevice) },
                    )
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "add") {
                        PrimaryActionButton(
                            text = "扫码 / 粘贴配对链接",
                            onClick = onAddDevice,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    items(items = state.devices, key = { it.id }) { device ->
                        DeviceCard(
                            device = device,
                            isCurrent = device.id == state.currentDeviceId,
                            renaming = renamingId == device.id,
                            renameDraft = renameDraft,
                            onRenameDraftChange = { renameDraft = it },
                            onOpen = { onOpenDevice(device.id) },
                            onStartRename = {
                                renamingId = device.id
                                renameDraft = device.name
                            },
                            onCommitRename = {
                                onRenameDevice(device.id, renameDraft)
                                renamingId = null
                            },
                            onCancelRename = { renamingId = null },
                            onRemove = { onRemoveDevice(device.id) },
                        )
                    }
                    item(key = "bottom") { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }
}

/** 单台设备一张卡片。 */
@Composable
private fun DeviceCard(
    device: DeviceRecord,
    isCurrent: Boolean,
    renaming: Boolean,
    renameDraft: String,
    onRenameDraftChange: (String) -> Unit,
    onOpen: () -> Unit,
    onStartRename: () -> Unit,
    onCommitRename: () -> Unit,
    onCancelRename: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LocalZemoteColors.current

    ZemoteCard {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = device.name,
                    modifier = Modifier.weight(1f, fill = false),
                    style = ZemoteType.uiBase,
                    fontWeight = FontWeight.Medium,
                    color = colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isCurrent) ReadOnlyBadge("当前")
                if (device.needsRepair) ReadOnlyBadge("需重新配对")
            }
            Text(
                text = device.host,
                style = ZemoteType.uiXs.copy(fontFamily = FontFamily.Monospace),
                color = colors.foregroundSubtle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusDot(color = relayColor(device.lastRelayStatus))
                Text(
                    text = "最后已知状态：${device.lastRelayStatus.wireName}",
                    style = ZemoteType.uiSm,
                    color = colors.foregroundSubtle,
                )
            }

            // 诊断用：最近一次 relay 失败的原因码与详情（成功配对后由 RelayStatusWriter 清空）。
            val lastError = device.lastRelayError
            if (!lastError.isNullOrBlank()) {
                Text(
                    text = "最后错误：$lastError" +
                        (device.lastRelayErrorMessage?.takeIf { it.isNotBlank() }
                            ?.let { "（$it）" } ?: ""),
                    style = ZemoteType.uiXs,
                    color = colors.destructive,
                )
            }

            Text(
                text = device.lastConnectedAt
                    ?.let { "最后连接：${formatTime(it)}" }
                    ?: "从未连接",
                style = ZemoteType.uiXs,
                color = colors.foregroundSubtlest,
            )

            if (device.needsRepair) {
                // Keystore 密钥丢失 ⇒ 密文解不开，只能重新配对（见 DeviceRecord.needsRepair）。
                Text(
                    text = "凭据不可用：这台设备需要重新配对",
                    style = ZemoteType.uiXs,
                    color = colors.warning,
                )
            }
        }

        if (renaming) {
            SettingsDivider()
            RenameField(
                value = renameDraft,
                onValueChange = onRenameDraftChange,
                onCommit = onCommitRename,
                onCancel = onCancelRename,
            )
        }

        SettingsDivider()
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PrimaryActionButton("打开", onClick = onOpen)
            OutlineActionButton("重命名", onClick = onStartRename)
            OutlineActionButton("删除", onClick = onRemove, destructive = true)
        }
    }
}

/** 重命名输入行：`input` 底 + 三态边框里的默认态，8dp 圆角。 */
@Composable
private fun RenameField(
    value: String,
    onValueChange: (String) -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = LocalZemoteColors.current
    val shape = RoundedCornerShape(ZemoteRadius.lg)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = ZemoteType.uiBase.copy(color = colors.foreground),
            cursorBrush = SolidColor(colors.foreground),
            modifier = Modifier
                .weight(1f)
                .clip(shape)
                .background(colors.input)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
        PrimaryActionButton("保存", onClick = onCommit)
        OutlineActionButton("取消", onClick = onCancel)
    }
}

@Composable
private fun relayColor(status: RelayStatus): Color {
    val colors = LocalZemoteColors.current
    return when (status) {
        RelayStatus.PAIRED -> colors.success
        RelayStatus.CONNECTING,
        RelayStatus.AUTHENTICATING,
        RelayStatus.WAITING,
        RelayStatus.RECONNECTING,
        -> colors.warning
        RelayStatus.ERROR,
        RelayStatus.KICKED,
        -> colors.destructive
        RelayStatus.CLOSED,
        RelayStatus.IDLE,
        -> colors.foregroundSubtlest
    }
}

private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun formatTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(timeFormat)
