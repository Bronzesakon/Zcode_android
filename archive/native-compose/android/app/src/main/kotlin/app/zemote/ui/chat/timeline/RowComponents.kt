package app.zemote.ui.chat.timeline

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.zemote.ui.theme.LocalZemoteColors
import kotlin.math.roundToInt

/**
 * 网页的两个折叠缓动（`docs/web-reverse/01` §动画、`03` §3.4、`04` §2.5）：
 * - 工具/分组：`collapsible-down/up` = 200ms `ease-out` = cubic-bezier(0,0,.2,1)；
 * - 思考/历史：300ms `ease-in-out` = cubic-bezier(.4,0,.2,1)。
 */
internal val ZemoteEaseOut = CubicBezierEasing(0f, 0f, 0.2f, 1f)
internal val ZemoteEaseInOut = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

/** 工具/分组折叠：200ms ease-out（网页 `animate-collapsible-*` 默认值）。 */
internal const val ZemoteToolCollapseMs = 200

/** 工具/分组**内容**折叠：300ms ease-in-out（web `duration-300 ease-in-out`，09 F5）。 */
internal const val ZemoteToolContentCollapseMs = 300

/** 思考/历史折叠：300ms ease-in-out（网页 reasoning `duration-300 ease-in-out`）。 */
internal const val ZemoteReasoningCollapseMs = 300

/**
 * 高度可折叠容器：内容**只测量一次**，动画期间只改外层高度并裁剪。
 *
 * 为什么不用 `AnimatedVisibility(expandVertically)`：它每一帧都用变化的 maxHeight
 * 重新测量子树，代码块/终端的文本布局缓存全部失效，展开大段内容时掉帧。这里用
 * [SubcomposeLayout] 以固定宽度 + 无限高度测量一次，逐帧只改 `layout()` 高度，
 * 与网页「内容布局一次、只过渡 `height`」的行为一致。
 */
@Composable
internal fun CollapsibleContent(
    visible: Boolean,
    durationMillis: Int,
    easing: Easing,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val progress = remember { Animatable(if (visible) 1f else 0f) }
    LaunchedEffect(visible) {
        progress.animateTo(
            targetValue = if (visible) 1f else 0f,
            animationSpec = tween(durationMillis = durationMillis, easing = easing),
        )
    }
    SubcomposeLayout(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds(),
    ) { constraints ->
        val width = constraints.maxWidth
        // 完全收起时不组合内容：避免为不可见的终端/diff 付首次组合成本。
        val placeable = if (progress.value > 0f) {
            subcompose("collapsible") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { alpha = progress.value },
                    content = content,
                )
            }.first().measure(
                Constraints(
                    minWidth = width,
                    maxWidth = width,
                    minHeight = 0,
                    maxHeight = Constraints.Infinity,
                ),
            )
        } else {
            null
        }
        val height = ((placeable?.height ?: 0) * progress.value)
            .roundToInt()
            .coerceAtLeast(0)
        layout(width, height) {
            placeable?.place(0, 0)
        }
    }
}

/**
 * 展开/收起箭头：网页 chevron-right 0°→90°，200ms ease-out，尺寸 16dp（`size-4`）。
 *
 * 旋转角度在 `graphicsLayer` 的 block 里读取，动画期间只更新图层，不重组节点。
 */
@Composable
internal fun ExpandChevron(
    expanded: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
) {
    val rotation = animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(durationMillis = ZemoteToolCollapseMs, easing = ZemoteEaseOut),
        label = "chevron",
    )
    Icon(
        imageVector = Icons.Default.ChevronRight,
        contentDescription = null,
        tint = tint,
        modifier = modifier
            .size(size)
            .graphicsLayer { rotationZ = rotation.value },
    )
}

/**
 * 历史行渲染上限。
 *
 * 旧工具输出/助手正文可能是数 MB 的纯文本；Compose 的 Text/Markdown 会一次性布局
 * 整段字符串（没有浏览器的分段排版），上翻历史把这类行组合出来时极易 OOM/卡死。
 * 超出上限只截断**显示**，不截断数据（复制/导出仍用原始行）。
 */
internal fun clampTextForRender(text: String, limit: Int): String =
    if (text.length <= limit) {
        text
    } else {
        text.take(limit) + "\n\n…（内容过长，已省略 ${text.length - limit} 字符）"
    }

/**
 * 可点击行的统一按压反馈：不用默认方角 ripple，改为 6dp 圆角的弱底色，且不裁剪内容。
 *
 * 行首文字/图标与行左缘对齐，直接 `clip` 会把首字符的左上角切掉；这里只绘制背景。
 */
@Composable
internal fun Modifier.softClickable(onClick: () -> Unit): Modifier {
    val colors = LocalZemoteColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val highlight = remember(colors) { colors.foreground.copy(alpha = 0.07f) }
    return this
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
        )
        .drawBehind {
            if (pressed) {
                drawRoundRect(
                    color = highlight,
                    cornerRadius = CornerRadius(6.dp.toPx()),
                )
            }
        }
}

/** 流式中的呼吸点（1.1s 一循环，透明度 0.35→1）。 */@Composable
internal fun StreamingDot(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "streaming")
    val alpha = transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    Box(
        modifier = modifier
            .size(6.dp)
            .graphicsLayer { this.alpha = alpha.value }
            .background(color, CircleShape),
    )
}

/** 行左侧的竖向细线（工作行的缩进引导）。 */
@Composable
internal fun WorkGuide(modifier: Modifier = Modifier) {
    val colors = LocalZemoteColors.current
    val guideColor = remember(colors) { colors.border.copy(alpha = 0.6f) }
    Box(
        modifier = modifier
            .size(width = 2.dp, height = 16.dp)
            .background(guideColor, CircleShape),
    )
}

/**
 * 摘要文字。
 *
 * 原实现用 `AnimatedContent + SizeTransform` 做 300ms 滑动，但每一行工具/分组都带一套
 * 动画容器，滚动时（尤其是流式摘要变化）会持续触发测量与布局。滚动流畅优先，这里
 * 直接渲染单行文本；摘要内容变化仍然即时可见，不影响信息。
 */
@Composable
internal fun AnimatedSummaryText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
