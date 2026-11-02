package app.zemote.ui.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.zemote.pairing.RejectReason
import app.zemote.ui.common.NoticeBar
import app.zemote.ui.common.OutlineActionButton
import app.zemote.ui.common.PageHeader
import app.zemote.ui.common.PrimaryActionButton
import app.zemote.ui.common.SettingsGroupHeader
import app.zemote.ui.common.ZemoteCard
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteRadius
import app.zemote.ui.theme.ZemoteType

/**
 * 配对页（手动粘贴链接 / 从扫码页转来）。
 *
 * 无状态：状态来自 [PairingUiState]，动作走回调，接线在 `AppNavHost`。
 * 视觉：44dp 页头、12dp 圆角卡片、8dp 圆角输入框（`input` 底 + `inputBorder` 边框）、
 * 4dp 栅格；业务逻辑（前置筛查、建会话、入库）全部留在 [PairingViewModel]。
 */
@Composable
fun PairingScreen(
    state: PairingUiState,
    onInputChanged: (String) -> Unit,
    onScan: () -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    val colors = LocalZemoteColors.current

    Column(Modifier.fillMaxSize().background(colors.background)) {
        PageHeader(title = "配对电脑", onBack = onBack)

        state.notice?.let { message ->
            NoticeBar(text = message, onClose = onDismissNotice)
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "扫桌面端 ZCode 的远控二维码，或把远控链接粘贴到下面。",
                style = ZemoteType.uiSm,
                color = colors.foregroundSubtle,
            )

            SettingsGroupHeader("远控链接")
            ZemoteCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinkField(
                        value = state.input,
                        enabled = !state.busy,
                        onValueChange = onInputChanged,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // 扫码能力尚未接入时 scanner.scan() 返回 null（视为用户取消），按钮照常可点。
                        OutlineActionButton("扫码", onClick = onScan, enabled = !state.busy)
                        PrimaryActionButton("连接并保存", onClick = onSubmit, enabled = !state.busy)
                    }
                }
            }

            if (state.busy) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("正在建会话…", style = ZemoteType.uiSm, color = colors.foregroundSubtle)
                }
            }

            state.rejection?.let { reason ->
                // 前置筛查的拒绝原因：语义来自 PairingUrl，文案在这里（TODO(i18n)）。
                Text(reason.toMessage(), style = ZemoteType.uiSm, color = colors.destructive)
            }

            state.error?.let { message ->
                Text(message, style = ZemoteType.uiSm, color = colors.destructive)
            }

            state.paired?.let { device ->
                // 入库成功：`AppNavHost` 看到 paired 非空就会退回主界面。
                Text(
                    text = "已配对：${device.name}（${device.host}）",
                    style = ZemoteType.uiSm,
                    color = colors.success,
                )
            }
        }
    }
}

/** 链接输入框：`input` 底、1px `inputBorder`、8dp 圆角，空值时显示占位。 */
@Composable
private fun LinkField(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    val colors = LocalZemoteColors.current
    val shape = RoundedCornerShape(ZemoteRadius.lg)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp)
            .clip(shape)
            .background(colors.input)
            .border(1.dp, colors.inputBorder, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) {
            Text(
                text = "https://…/remote/v4?sid=…&hash=…",
                style = ZemoteType.uiSm.copy(fontFamily = FontFamily.Monospace),
                color = colors.foregroundSubtlest,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            textStyle = ZemoteType.uiSm.copy(
                fontFamily = FontFamily.Monospace,
                color = colors.foreground,
            ),
            cursorBrush = SolidColor(colors.foreground),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 拒绝原因的文案。
 *
 * 文案属于界面层（`PairingUrl` 只给语义），所以映射放在这里而不是引擎/配对层。
 *
 * TODO(i18n): 接入 `AppSettings.locale` 时改成查表。
 */
private fun RejectReason.toMessage(): String = when (this) {
    RejectReason.EMPTY -> "还没有内容：请扫码，或把远控链接粘贴进来。"
    RejectReason.NOT_A_URL -> "这不像一个链接：需要以 http(s):// 或 wss:// 开头。"
    RejectReason.SCHEME_NOT_HTTP -> "只支持 http / https / wss 的链接。"
    RejectReason.MISSING_HOST -> "链接里没有主机名（host）。"
    RejectReason.MISSING_SID -> "链接里没有 sid 参数。"
    RejectReason.NOT_ZEMOTE_LINK -> "这看起来不是 Zemote 的远控链接。"
}
