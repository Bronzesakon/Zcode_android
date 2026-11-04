package app.zemote.ui.chat.timeline

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zemote.ui.chat.asLong
import app.zemote.ui.chat.asObj
import app.zemote.ui.chat.asString
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteSpacing
import app.zemote.ui.theme.ZemoteType
import kotlinx.serialization.json.JsonObject

/**
 * 时间线标记：居中标签，左右各一条 `1px × flex-1` 分隔线。
 *
 * 只渲染网页端实现的 4 种：`compact` / `forkNotice` / `modelChange` / `goalVerify`，
 * 其余 `marker.type` 与网页一样丢弃。
 */
@Composable
internal fun MarkerRow(row: JsonObject, modifier: Modifier = Modifier) {
    val colors = LocalZemoteColors.current
    val marker = row["marker"].asObj() ?: return
    val type = remember(row) { marker["type"].asString().orEmpty() }

    val running = remember(row) {
        when (type) {
            "compact" -> marker["status"].asString() == "running"
            "goalVerify" -> marker["outcome"].asString() == "running"
            else -> false
        }
    }
    val icon: ImageVector? = remember(row) {
        when (type) {
            "compact" -> Icons.Default.Archive
            "forkNotice" -> Icons.Default.CallSplit
            "modelChange" -> if (marker["fromModel"].asString() != null) Icons.Default.SwapHoriz else null
            "goalVerify" -> Icons.Default.Flag
            else -> null
        }
    }
    val label = remember(row) { markerLabel(type, marker) } ?: return
    val dividerColor = remember(colors) { colors.border.copy(alpha = 0.5f) }
    val runningAlpha = if (running) rememberMarkerRunningAlpha() else null

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = ZemoteSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZemoteSpacing.space3),
    ) {
        HorizontalDivider(
            Modifier.weight(1f).widthIn(min = ZemoteSpacing.space8),
            color = dividerColor,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZemoteSpacing.space1_5),
        ) {
            // 运行态网页隐藏图标、文字走渐变扫光；这里用透明度呼吸近似。
            if (icon != null && !running) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.foregroundSubtle,
                    modifier = Modifier.size(ZemoteSpacing.space3_5),
                )
            }
            Text(
                text = label,
                style = MarkerLabelStyle,
                color = colors.foregroundSubtle,
                textAlign = TextAlign.Center,
                // 呼吸透明度在 layer block 里读取，动画帧不再重组本行。
                modifier = Modifier.graphicsLayer { this.alpha = runningAlpha?.value ?: 1f },
            )
        }
        HorizontalDivider(
            Modifier.weight(1f).widthIn(min = ZemoteSpacing.space8),
            color = dividerColor,
        )
    }
}

/** 标记文案行高 20sp（网页 `text-ui-base` + leading-[20px]）。 */
private val MarkerLabelStyle = ZemoteType.uiBase.copy(lineHeight = 20.sp)

/** 标记文案；返回 `null` 表示该 type 不渲染。 */
private fun markerLabel(type: String, marker: JsonObject): String? = when (type) {
    "compact" -> when (marker["status"].asString()) {
        "running" -> "正在压缩上下文"
        "noop" -> "上下文已是最新，无需压缩"
        "cancelled" -> "上下文压缩已中断"
        "failed" -> "上下文压缩失败"
        else -> if (marker["origin"].asString() == "auto") {
            "上下文已自动压缩"
        } else {
            "上下文已压缩"
        }
    }

    "forkNotice" -> "从对话中派生"

    "modelChange" -> {
        val from = marker["fromModel"].asString()
        val to = marker["toModel"].asString()
        if (from != null) "模型已切换 $from → $to" else "正在使用 $to"
    }

    "goalVerify" -> {
        val iteration = marker["iteration"].asLong() ?: 0L
        val outcome = when (marker["outcome"].asString()) {
            "running" -> "目标校验中"
            "pass" -> "目标已完成，任务结束"
            else -> "目标未完成，任务继续"
        }
        "第 $iteration 次迭代 · $outcome"
    }

    else -> null
}

/** 运行态文字 2s 呼吸（网页是 4s 渐变扫光）；在 layer block 里读取，不触发重组。 */
@Composable
private fun rememberMarkerRunningAlpha(): State<Float> {
    val transition = rememberInfiniteTransition(label = "marker-running")
    return transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "marker-running-alpha",
    )
}
