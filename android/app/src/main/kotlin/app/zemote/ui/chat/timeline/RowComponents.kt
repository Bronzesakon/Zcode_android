package app.zemote.ui.chat.timeline

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.zemote.ui.theme.LocalZemoteColors

/** 网页折叠统一 300ms `ease-in-out`（cubic-bezier(.42,0,.58,1)）。 */
internal val ZemoteCollapseEasing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
internal val ZemoteCollapseSizeSpec = tween<IntSize>(300, easing = ZemoteCollapseEasing)
internal val ZemoteCollapseAlphaSpec = tween<Float>(300, easing = ZemoteCollapseEasing)

/** 展开/收起箭头：200ms 旋转（网页 chevron-right 0°→90°）。 */
@Composable
internal fun ExpandChevron(expanded: Boolean, tint: Color, modifier: Modifier = Modifier) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(200),
        label = "chevron",
    )
    Icon(
        imageVector = Icons.Default.ExpandMore,
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(14.dp).rotate(rotation),
    )
}

/** 工具/子代理/思考的状态图标。 */
@Composable
internal fun StatusGlyph(status: String, modifier: Modifier = Modifier) {
    val colors = LocalZemoteColors.current
    when (status) {
        "running", "inputStreaming", "pendingApproval" -> CircularProgressIndicator(
            modifier = modifier.size(12.dp),
            strokeWidth = 1.5.dp,
            color = colors.foregroundSubtle,
        )

        "success", "completed" -> Icon(
            Icons.Default.Check,
            contentDescription = null,
            tint = colors.success,
            modifier = modifier.size(14.dp),
        )

        "error", "failed" -> Icon(
            Icons.Default.Close,
            contentDescription = null,
            tint = colors.destructive,
            modifier = modifier.size(14.dp),
        )

        else -> Box(modifier.size(12.dp))
    }
}

/** 流式中的呼吸点（1.1s 一循环，透明度 0.35→1）。 */
@Composable
internal fun StreamingDot(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "streaming")
    val alpha by transition.animateFloat(
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
            .background(color.copy(alpha = alpha), CircleShape),
    )
}

/** 行左侧的竖向细线（工作行的缩进引导）。 */
@Composable
internal fun WorkGuide(modifier: Modifier = Modifier) {
    val colors = LocalZemoteColors.current
    Box(
        modifier = modifier
            .size(width = 2.dp, height = 16.dp)
            .background(colors.border.copy(alpha = 0.6f), CircleShape),
    )
}

/**
 * 摘要文字切换：新旧文案上下滑 300ms + 淡入淡出（网页 `0.3s cubic-bezier(.4,0,.2,1)`）。
 */
@Composable
internal fun AnimatedSummaryText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            (slideInVertically(tween(300)) { it / 3 } + fadeIn(tween(300))) togetherWith
                (slideOutVertically(tween(300)) { -it / 3 } + fadeOut(tween(300))) using
                SizeTransform(clip = false)
        },
        label = "summary",
        modifier = modifier,
    ) { value ->
        Text(
            text = value,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
