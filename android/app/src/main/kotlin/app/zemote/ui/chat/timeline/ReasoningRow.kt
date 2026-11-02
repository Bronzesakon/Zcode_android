package app.zemote.ui.chat.timeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.zemote.ui.chat.asLong
import app.zemote.ui.chat.asString
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteType
import kotlinx.serialization.json.JsonObject

/**
 * 思考行。
 *
 * 折叠态（网页口径）：`brain 图标 + 思考 · 持续了 N 秒 + chevron`，无背景/边框；
 * 展开态：左侧 1px 竖线 + 14dp 缩进，纯文本（**不是 Markdown**），最大 240dp 滚动。
 *
 * 展开/折回策略按用户确认的版本：流式中自动展开，该轮流式结束后自动折回
 * （由时间线传入的 [open] 决定）。
 */
@Composable
internal fun ReasoningRow(
    row: JsonObject,
    open: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalZemoteColors.current
    val duration = row["durationMs"].asLong() ?: 0L
    val streaming = row["state"].asString() == "streaming"
    val label = when {
        streaming -> "正在思考"
        duration > 0 -> "思考 · 持续了 ${formatDuration(duration)}"
        else -> "思考"
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = LucideIcons.Brain,
                contentDescription = null,
                tint = colors.foregroundSubtlest,
                // 文本行高 21sp 的字形视觉中心比行盒中心略低，图标下移 1dp 对齐。
                modifier = Modifier.size(16.dp).offset(y = 1.dp),
            )
            Spacer(Modifier.width(6.dp))
            if (streaming) {
                val transition = rememberInfiniteTransition(label = "thinking")
                val alpha by transition.animateFloat(
                    initialValue = 0.45f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2000),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "thinking-alpha",
                )
                Text(
                    text = label,
                    style = ZemoteType.uiBase,
                    color = colors.foregroundSubtle,
                    modifier = Modifier.graphicsLayer(alpha = alpha),
                )
            } else {
                Text(text = label, style = ZemoteType.uiBase, color = colors.foregroundSubtlest)
            }
            Spacer(Modifier.weight(1f))
            ExpandChevron(expanded = open, tint = colors.foregroundSubtlest)
        }

        AnimatedVisibility(
            visible = open,
            enter = expandVertically(animationSpec = ZemoteCollapseSizeSpec) +
                fadeIn(animationSpec = ZemoteCollapseAlphaSpec),
            exit = shrinkVertically(animationSpec = ZemoteCollapseSizeSpec) +
                fadeOut(animationSpec = ZemoteCollapseAlphaSpec),
        ) {
            val scroll = rememberScrollState()
            Box(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .drawBehind {
                        drawRect(
                            color = colors.border,
                            topLeft = Offset.Zero,
                            size = Size(1.dp.toPx(), size.height),
                        )
                    },
            ) {
                Box(
                    modifier = Modifier
                        .padding(start = 14.dp)
                        .fillMaxWidth()
                        .heightIn(max = 240.dp),
                ) {
                    Text(
                        text = row["text"].asString().orEmpty(),
                        style = ZemoteType.uiBase.copy(
                            lineHeight = ZemoteType.uiBase.fontSize * 1.5f,
                        ),
                        color = colors.foregroundSubtlest,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scroll),
                    )
                    // 上下各 24dp 渐隐，提示还有未显示的内容。
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(24.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(colors.background, Color.Transparent),
                                ),
                            ),
                    )
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(24.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, colors.background),
                                ),
                            ),
                    )
                }
            }
        }
    }
}
