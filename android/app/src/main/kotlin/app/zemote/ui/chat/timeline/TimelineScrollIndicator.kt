package app.zemote.ui.chat.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.zemote.ui.theme.LocalZemoteColors

/**
 * 对话时间线右侧的细滚动进度条（3dp 轨道 + thumb）。
 *
 * - 只用 [Canvas]：滚动时 `derivedStateOf` 命中同一份快照，读取发生在绘制阶段，
 *   只失效 draw，不触发重组，也没有动画循环；
 * - thumb 高度 = 视口 / 估算内容高度（最小 24dp），位置按首个可见项的比例；
 * - 内容装得下（不可滚动）时整条隐藏，不留视觉噪声。
 *
 * 由 `ConversationTimeline` 叠在列表右缘放置；本组件只负责绘制，不接管滚动。
 */
@Composable
internal fun TimelineScrollIndicator(
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val colors = LocalZemoteColors.current
    val minThumbPx = with(LocalDensity.current) { MinThumbHeight.toPx() }

    // 只在绘制阶段读 value：布局快照变化时走 draw 失效，不重组整棵时间线。
    val metrics = remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo
            val total = info.totalItemsCount
            if (total == 0 || visible.isEmpty()) return@derivedStateOf null
            // 可滚性只看 LazyList 自己的判定，别用「估算行高」猜（08 §4.6）。
            if (!listState.canScrollBackward && !listState.canScrollForward) {
                return@derivedStateOf null
            }

            val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
            if (viewport <= 0f) return@derivedStateOf null

            // reverseLayout 下 visible 按 index 升序：first() 是视觉最底，last() 是视觉最顶。
            // 「距底」坐标 = first.index + 首项被滚过的比例（reverse 下 first.offset ≤ 0）。
            val first = visible.first()
            val last = visible.last()
            val span = (last.index - first.index).coerceAtLeast(1)
            val withinFirst = ((-first.offset).coerceIn(0, first.size).toFloat() /
                first.size.coerceAtLeast(1))
            val fromBottomUnits = first.index + withinFirst
            val maxUnits = (total - 1 - span).coerceAtLeast(1)
            // 顶部 = 0，底部 = 1。旧实现直接拿「距底」当进度，方向整体反了（08 P1-1）。
            val progressFromTop = (1f - (fromBottomUnits / maxUnits)).coerceIn(0f, 1f)

            // thumb 高度 = 视口 / 内容总高。用可见项平均高估算总高，比固定 72dp 更贴近
            // 真实内容；夹上下限只为避免极端行高下 thumb 消失或糊满整条。
            val averageItemPx = visible.sumOf { it.size }.toFloat() / visible.size
            val estimatedContentPx = (averageItemPx * total).coerceAtLeast(1f)
            ScrollMetrics(
                thumbFraction = (viewport / estimatedContentPx).coerceIn(0.02f, 1f),
                progressFromTop = progressFromTop,
            )
        }
    }

    Canvas(
        modifier = modifier
            .width(BarWidth)
            .fillMaxHeight()
            .semantics { contentDescription = "对话进度" },
    ) {
        val m = metrics.value ?: return@Canvas
        val trackHeight = size.height
        if (trackHeight <= 0f) return@Canvas

        // 视口比最小 thumb 还矮时退化为整条，避免 coerceIn(min > max) 抛异常。
        val thumbHeight = (trackHeight * m.thumbFraction)
            .coerceAtLeast(minThumbPx)
            .coerceAtMost(trackHeight)
        val thumbTop = (trackHeight - thumbHeight) * m.progressFromTop.coerceIn(0f, 1f)
        val corner = CornerRadius(size.width / 2f)

        drawRoundRect(
            color = colors.foreground.copy(alpha = 0.08f),
            size = size,
            cornerRadius = corner,
        )
        drawRoundRect(
            color = colors.foregroundSubtle,
            topLeft = Offset(0f, thumbTop),
            size = Size(size.width, thumbHeight),
            cornerRadius = corner,
        )
    }
}

/** draw 阶段的最小状态：相等时 derivedStateOf 不再触发重绘。 */
private data class ScrollMetrics(val thumbFraction: Float, val progressFromTop: Float)

private val BarWidth = 3.dp
private val MinThumbHeight = 24.dp
