package app.zemote.ui.pairing

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.zemote.pairing.RejectReason

/**
 * 配对页（扫码 / 粘贴链接 → 建会话 → 存设备）。
 *
 * 无状态：状态来自 [PairingUiState]，动作走回调，接线在 `AppNavHost`。
 *
 * 本文件**不做视觉决策**（颜色/字号/间距/圆角/阴影/动效一律不设），需要设计的位置留 `TODO(UI)`。
 * 唯一的例外是 [BasicTextField]：它是 foundation 的无装饰原语，不设样式就没有样式，
 * 而「粘贴链接」是这个界面存在的理由，去掉它界面就没法用。
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
    Column(modifier = Modifier.fillMaxSize()) {
        // TODO(UI): 返回栏（网页版对话页是 h-11 的 header，这里配对页要对齐它的形态）。
        Row {
            Button(onClick = onBack) { Text("返回") }
            Text(text = "配对设备")
        }

        Text(text = "扫桌面端 ZCode 的远控二维码，或把远控链接粘贴到下面。")

        // TODO(UI): 输入框样式（网页版是 shadcn Input：三态边框 + bg-input）。
        BasicTextField(
            value = state.input,
            onValueChange = onInputChanged,
            enabled = !state.busy,
        )

        Row {
            // 扫码能力尚未接入时 scanner.scan() 返回 null（视为用户取消），按钮照常可点。
            Button(onClick = onScan, enabled = !state.busy) { Text("扫码") }
            Button(onClick = onSubmit, enabled = !state.busy) { Text("连接并保存") }
        }

        if (state.busy) {
            // TODO(UI): 加载态（转圈 + 遮罩）。
            CircularProgressIndicator()
            Text(text = "正在建会话…")
        }

        state.rejection?.let { reason ->
            // 前置筛查的拒绝原因：语义来自 PairingUrl，文案在这里（TODO(i18n)）。
            Text(text = reason.toMessage())
        }

        state.error?.let { message ->
            // TODO(UI): 错误条。
            Text(text = message)
        }

        state.notice?.let { message ->
            Text(text = message)
            Button(onClick = onDismissNotice) { Text("好") }
        }

        state.paired?.let { device ->
            // 入库成功：`AppNavHost` 看到 paired 非空就会退回设备列表。
            Text(text = "已配对：${device.name}（${device.host}）")
        }
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
    RejectReason.MISSING_HASH -> "链接里没有 hash 参数。"
    RejectReason.NOT_ZEMOTE_LINK -> "这看起来不是 Zemote 的远控链接。"
}
