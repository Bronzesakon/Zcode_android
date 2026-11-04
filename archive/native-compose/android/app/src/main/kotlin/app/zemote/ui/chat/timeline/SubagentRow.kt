package app.zemote.ui.chat.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.zemote.ui.chat.asLong
import app.zemote.ui.chat.asString
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteRadius
import app.zemote.ui.theme.ZemoteSpacing
import app.zemote.ui.theme.ZemoteType
import kotlinx.serialization.json.JsonObject

/**
 * 无法与 Agent 工具调用配对的 subagent 卡片：类型 / 状态 / 摘要 / 耗时。
 *
 * 能配对的行会被合并进工具摘要行（见 [ToolCallRow]）；这张卡片负责剩下的行，
 * 有 `onClick` 时整卡可点（进入子代理页）。
 */
@Composable
internal fun SubagentRow(
    row: JsonObject,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalZemoteColors.current
    val type = remember(row) { row["subagentType"].asString() ?: "子代理" }
    val status = remember(row) { statusLabel(row["status"].asString().orEmpty()) }
    val summary = remember(row) { row["summaryText"].asString().orEmpty() }
    // 时长可能落到 `endedAt - startedAt`（结果是「此刻」），不做缓存以保持原来的刷新节奏。
    val elapsed = durationMs(row)

    Row(
        modifier = modifier
            .padding(vertical = ZemoteSpacing.space1)
            .clip(ZemoteRadius.lgShape)
            .background(colors.card)
            .border(1.dp, colors.cardBorder, ZemoteRadius.lgShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = ZemoteSpacing.space3, vertical = ZemoteSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZemoteSpacing.space2),
    ) {
        Icon(
            imageVector = LucideIcons.Bot,
            contentDescription = null,
            tint = colors.foregroundSubtle,
            modifier = Modifier.size(ZemoteSpacing.space4),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZemoteSpacing.space1_5),
            ) {
                Text(
                    text = type,
                    style = SubagentTypeStyle,
                    color = colors.foreground,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = status,
                    style = ZemoteType.uiSm,
                    color = colors.foregroundSubtle,
                    maxLines = 1,
                )
            }
            if (summary.isNotBlank()) {
                Text(
                    text = summary,
                    style = ZemoteType.uiBase,
                    color = colors.foregroundSubtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (elapsed > 0) {
            Spacer(Modifier.width(ZemoteSpacing.space0_5))
            Text(
                text = formatDuration(elapsed),
                style = ZemoteType.uiSm,
                color = colors.foregroundSubtlest,
                maxLines = 1,
            )
        }
    }
}

/** 子代理类型名的等宽样式（组合外常量）。 */
private val SubagentTypeStyle = ZemoteType.uiBase.copy(fontFamily = FontFamily.Monospace)
