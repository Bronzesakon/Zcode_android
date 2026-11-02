package app.zemote.ui.chat.timeline

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import app.zemote.ui.chat.asString
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteSpacing
import app.zemote.ui.theme.ZemoteType
import kotlinx.serialization.json.JsonObject

/**
 * 轮次段标题：`工作中 1分20秒` / `已工作 3分` / `已处理` / `已停止`。
 *
 * 网页端没有状态图标/spinner，运行中只靠文案表达；chevron-right 16dp，
 * 展开时旋转 90°，默认半透明。
 */
@Composable
internal fun TurnHeaderRow(
    row: JsonObject,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    collapsible: Boolean = true,
) {
    val colors = LocalZemoteColors.current
    val label = turnHeaderLabel(row["state"].asString(), durationMs(row))
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        label = "turn-chevron",
    )

    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (collapsible) Modifier.clickable(onClick = onToggle) else Modifier)
                // 网页 turn section 的 pt-14（56dp）减去时间线行间距 20dp = 36dp。
                .padding(top = ZemoteSpacing.space9, bottom = ZemoteSpacing.space2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZemoteSpacing.space2),
        ) {
            Text(
                text = label,
                style = ZemoteType.uiBase,
                color = colors.foregroundSubtle,
            )
            if (collapsible) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = colors.foregroundSubtlest.copy(alpha = 0.7f),
                    modifier = Modifier
                        .size(ZemoteSpacing.space4)
                        .rotate(rotation),
                )
            }
        }
        HorizontalDivider(color = colors.border.copy(alpha = 0.5f))
    }
}
