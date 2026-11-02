package app.zemote.ui.chat.timeline

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.zemote.ui.chat.asString
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteType
import kotlinx.serialization.json.JsonObject

/**
 * 无法与 Agent 工具调用配对的 subagent 退化行。
 *
 * 网页端是纯文本 `{subagentType} · {status} — {summaryText}`：无图标、无折叠、
 * 无点击、自然换行。能配对的行会被合并进工具摘要行（见 [ToolCallRow]）。
 */
@Composable
internal fun SubagentRow(row: JsonObject, modifier: Modifier = Modifier) {
    val colors = LocalZemoteColors.current
    val type = row["subagentType"].asString() ?: "子代理"
    val status = statusLabel(row["status"].asString().orEmpty())
    val summary = row["summaryText"].asString().orEmpty()

    Text(
        text = "$type · $status" + if (summary.isNotBlank()) " — $summary" else "",
        style = ZemoteType.uiSm,
        color = colors.foregroundSubtle,
        modifier = modifier.fillMaxWidth(),
    )
}
