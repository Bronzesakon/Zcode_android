package app.zemote.ui.chat.timeline

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.zemote.ui.chat.asLong
import app.zemote.ui.chat.asString
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteSpacing
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
    val duration = remember(row) { row["durationMs"].asLong() ?: 0L }
    val streaming = remember(row) { row["state"].asString() == "streaming" }
    val label = remember(streaming, open, duration) {
        reasoningLabel(streaming = streaming, open = open, durationMs = duration)
    }
    val text = remember(row) { row["text"].asString().orEmpty() }
    // 桥的增量实测约 150ms 一批；直接整段替换会“每跳一下”。展开且流式时把新文本
    // 在帧时钟上按词逐字补出来，观感对齐网页的逐词更新。
    val shownText = rememberRevealedText(text = text, streaming = streaming && open)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .softClickable(onToggle),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZemoteSpacing.space2),
        ) {
            Icon(
                imageVector = LucideIcons.Brain,
                contentDescription = null,
                tint = colors.foregroundSubtlest,
                // 文本行高 21sp 的字形视觉中心比行盒中心略低，图标下移 1dp 对齐。
                modifier = Modifier.size(ZemoteSpacing.space4).offset(y = 1.dp),
            )
            if (streaming && !open) {
                val alpha = rememberThinkingAlpha()
                Text(
                    text = label,
                    style = ZemoteType.uiBase,
                    color = colors.foregroundSubtle,
                    // 呼吸透明度在 layer block 里读取，动画帧不再重组本行。
                    modifier = Modifier.graphicsLayer { this.alpha = alpha.value },
                )
            } else {
                Text(text = label, style = ZemoteType.uiBase, color = colors.foregroundSubtlest)
            }
            Spacer(Modifier.weight(1f))
            ExpandChevron(expanded = open, tint = colors.foregroundSubtlest)
        }

        // 展开即完整展开：不设 maxHeight、不做内部滚动，也不截断流式正文。
        // 折叠/展开动画按网页 reasoning 口径：高度 0↔内容高，300ms ease-in-out；
        // 流式结束自动收起时同样走这条动画（之前是瞬间消失）。
        CollapsibleContent(
            visible = open,
            durationMillis = ZemoteReasoningCollapseMs,
            easing = ZemoteEaseInOut,
        ) {
            Box(
                modifier = Modifier
                    .padding(top = ZemoteSpacing.space3, start = ZemoteSpacing.space2)
                    .fillMaxWidth()
                    .drawBehind {
                        drawRect(
                            color = colors.border,
                            topLeft = Offset.Zero,
                            size = Size(1.dp.toPx(), size.height),
                        )
                    },
            ) {
                Text(
                    text = shownText,
                    style = ReasoningBodyStyle,
                    color = colors.foregroundSubtlest,
                    modifier = Modifier
                        .padding(start = ZemoteSpacing.space3_5)
                        .fillMaxWidth(),
                )
            }
        }
    }
}

/** 正文行高 1.5（网页 `--text-ui-base` 继承行高）；组合外常量，避免每次组合新建样式。 */
private val ReasoningBodyStyle = ZemoteType.uiBase.copy(
    lineHeight = ZemoteType.uiBase.fontSize * 1.5f,
)

/**
 * 流式思考的逐词揭示（节奏与边界计算见 [StreamingReveal]）。
 *
 * - 折叠/历史/大跳：直接整段显示，保证滚动与展开不因逐帧重排掉帧；
 * - 展开且流式：每批增量在帧时钟上摊到 80–200ms 补完。
 */
@Composable
private fun rememberRevealedText(text: String, streaming: Boolean): String {
    var revealed by remember { mutableIntStateOf(text.length) }
    LaunchedEffect(text, streaming) {
        if (!streaming || text.length <= revealed) {
            revealed = text.length
            return@LaunchedEffect
        }
        val start = revealed
        val target = text.length
        if (target - start > StreamingReveal.MAX_STEP || target > StreamingReveal.MAX_CHARS) {
            revealed = target
            return@LaunchedEffect
        }
        val durationMs = StreamingReveal.durationMs(target - start)
        val startNanos = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val t = ((now - startNanos) / 1_000_000f / durationMs).coerceIn(0f, 1f)
            val next = StreamingReveal.revealIndex(text, start, target, t)
            if (next > revealed) revealed = next
            if (t >= 1f) break
        }
    }
    return remember(text, revealed) {
        text.substring(0, revealed.coerceIn(0, text.length))
    }
}

/** 流式思考只渲染尾部窗口；240dp 视口显示不下全文，全文重排是滚动卡顿主因。 */
private const val STREAMING_REASONING_TAIL_CHARS = 2_000

/** 流式折叠态 2s 呼吸透明度；在 layer block 里读取，不触发重组。 */
@Composable
private fun rememberThinkingAlpha(): State<Float> {
    val transition = rememberInfiniteTransition(label = "thinking")
    return transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "thinking-alpha",
    )
}
