package app.zemote.ui.devices

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.zemote.data.DeviceRecord

/**
 * 设备列表（对应网页版 A1 任务首页的**外壳部分**：页头 + 设备行）。
 *
 * ## 这一层只负责「把状态显示出来」
 *
 * 全部是**无状态** composable：状态由 [DevicesUiState] 传入，动作由回调传出，
 * 不在这里取 ViewModel（接线在 `AppNavHost`）。这样界面层后续换样式时不用碰任何逻辑，
 * 也不会在预览里意外触发副作用。
 *
 * ## 刻意没有做的事
 *
 * 本文件**不做任何视觉决策**：没有颜色、没有字号、没有间距/圆角/阴影、没有动效，
 * 一律用最朴素的 `Column` / `Row` / `Text` / `Button` 把状态摆出来，
 * 需要视觉设计的位置留了 `TODO(UI)`。文案按中文写在状态里（`TODO(i18n)` 在 ViewModel 侧）。
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
    // 重命名的行内编辑状态：纯界面状态，不进 ViewModel（没有跨界面语义）。
    var renamingId by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        // TODO(UI): 页头 —— 标题、连接态（webRemoteControl.status.* 六态文案 + 图标）、主题菜单。
        Text(text = "Zemote")
        Text(text = "连接状态：${state.relayState.wire}")

        Row {
            Button(onClick = onAddDevice) { Text("扫码 / 粘贴配对链接") }
            Button(onClick = onOpenSettings) { Text("设置") }
            Button(onClick = onOpenAboutUpdate) { Text("关于与更新") }
        }

        if (state.error != null) {
            // TODO(UI): 错误条（网页版是 text-destructive 的一行 + 重试按钮）。
            Text(text = state.error)
            Button(onClick = onRetry) { Text("重试") }
            Button(onClick = onDismissError) { Text("知道了") }
        }

        if (state.notice != null) {
            // TODO(UI): 一次性提示条（自动消失的 toast）。
            Text(text = state.notice)
            Button(onClick = onDismissNotice) { Text("好") }
        }

        when {
            state.loading && state.devices.isEmpty() -> {
                // TODO(UI): 加载态（骨架 / 转圈）。
                CircularProgressIndicator()
                Text(text = "正在读取设备列表…")
            }

            state.devices.isEmpty() -> {
                Text(text = "还没有设备。点「扫码 / 粘贴配对链接」添加一台。")
            }

            else -> {
                LazyColumn {
                    items(items = state.devices, key = { it.id }) { device ->
                        DeviceRow(
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
                }
            }
        }
    }
}

/** 单台设备一行。 */
@Composable
private fun DeviceRow(
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
    Column {
        Row {
            // TODO(UI): 当前设备标记（网页版用图标 + 选中态底色 bg-selected）。
            Text(text = if (isCurrent) "[当前] " else "")
            Text(text = device.name)
            Text(text = "  ${device.host}")
        }

        // TODO(UI): 六态连接文案（status.* 的 6 个值）与状态徽标；这里先透出协议原值。
        Text(text = "最后已知状态：${device.lastRelayStatus.wireName}")

        // 诊断用：最近一次 relay 失败的原因码与详情（成功配对后由 RelayStatusWriter 清空）。
        val lastError = device.lastRelayError
        if (!lastError.isNullOrBlank()) {
            Text(
                text = "最后错误：$lastError" +
                    (device.lastRelayErrorMessage?.takeIf { it.isNotBlank() }
                        ?.let { "（$it）" } ?: ""),
            )
        }

        // TODO(UI): 时间格式化（相对时间 / 本地时间）；这里先透出原始毫秒。
        Text(
            text = device.lastConnectedAt
                ?.let { "最后连接：$it" }
                ?: "从未连接",
        )

        if (device.needsRepair) {
            // Keystore 密钥丢失 ⇒ 密文解不开，只能重新配对（见 DeviceRecord.needsRepair）。
            Text(text = "凭据不可用：这台设备需要重新配对")
        }

        Row {
            Button(onClick = onOpen) { Text("打开") }
            Button(onClick = onStartRename) { Text("重命名") }
            Button(onClick = onRemove) { Text("删除") }
        }

        if (renaming) {
            Row {
                // TODO(UI): 输入框样式（网页版用 shadcn 的 Input + 三态边框）。
                BasicTextField(value = renameDraft, onValueChange = onRenameDraftChange)
                Button(onClick = onCommitRename) { Text("保存") }
                Button(onClick = onCancelRename) { Text("取消") }
            }
        }
    }
}
