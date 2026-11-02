package app.zemote.ui.chat.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Anchor
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.zemote.ui.chat.asBool
import app.zemote.ui.chat.asLong
import app.zemote.ui.chat.asString
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteRadius
import app.zemote.ui.theme.ZemoteSpacing
import app.zemote.ui.theme.ZemoteType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 轮次底部的钩子入口。
 *
 * 网页端把 `hookInvocation` 从行流里摘出来，只在回合尾部渲染一个入口；确认稿要求
 * 这里显示成**一行**（事件名 + 执行数 + 状态 + 耗时）。点击展开逐条执行列表。
 * 移动端没有 hover，入口常显。
 */
@Composable
internal fun HookTrailerRow(
    hooks: List<JsonObject>,
    modifier: Modifier = Modifier,
) {
    val colors = LocalZemoteColors.current
    val executions = remember(hooks) { flattenHookExecutions(hooks) }
    val summary = remember(executions) { hookSummary(executions) } ?: return
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(ZemoteRadius.md)

    Box(modifier.padding(vertical = ZemoteSpacing.space1)) {
        Row(
            modifier = Modifier
                .clip(shape)
                .clickable { open = !open }
                .padding(horizontal = ZemoteSpacing.space2, vertical = ZemoteSpacing.space1),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZemoteSpacing.space1_5),
        ) {
            Icon(
                Icons.Default.Anchor,
                contentDescription = "钩子",
                tint = colors.foregroundSubtlest,
                modifier = Modifier.size(ZemoteSpacing.space3_5),
            )
            Text(
                text = summary.title,
                style = ZemoteType.uiBase,
                color = colors.foregroundSubtle,
                maxLines = 1,
            )
            summary.status?.let { status ->
                Text(
                    text = hookStatusLabel(status),
                    style = ZemoteType.uiSm,
                    color = hookStatusColor(status),
                    maxLines = 1,
                )
            }
            summary.durationMs?.let { duration ->
                Text(
                    text = formatHookDuration(duration),
                    style = ZemoteType.uiSm,
                    color = colors.foregroundSubtlest,
                    maxLines = 1,
                )
            }
            ExpandChevron(expanded = open, tint = colors.foregroundSubtlest)
        }

        if (open) {
            HookPopover(executions = executions, onDismiss = { open = false })
        }
    }
}

@Composable
private fun HookPopover(
    executions: List<HookExecution>,
    onDismiss: () -> Unit,
) {
    val colors = LocalZemoteColors.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = colors.card,
            shadowElevation = 8.dp,
            modifier = Modifier.width(320.dp),
        ) {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.surface)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "钩子",
                        style = ZemoteType.uiBase,
                        color = colors.foreground,
                    )
                }
                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp).padding(vertical = 2.dp),
                ) {
                    items(executions) { execution ->
                        HookExecutionItem(execution)
                    }
                }
            }
        }
    }
}

@Composable
private fun HookExecutionItem(execution: HookExecution) {
    val colors = LocalZemoteColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = execution.eventName,
            style = ZemoteType.uiSm.copy(fontFamily = FontFamily.Monospace),
            color = colors.foreground,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = sourceLabel(execution.sourceKind),
            style = ZemoteType.uiXs,
            color = colors.foregroundSubtle,
        )
        execution.durationMs?.let { duration ->
            Text(
                text = formatHookDuration(duration),
                style = ZemoteType.uiXs,
                color = colors.foregroundSubtlest,
            )
        }
        execution.status?.let { status ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                HookStatusGlyph(status)
                Spacer(Modifier.width(4.dp))
                Text(
                    text = hookStatusLabel(status),
                    style = ZemoteType.uiXs,
                    color = hookStatusColor(status),
                )
            }
        }
        execution.blockReason?.let { reason ->
            Text(
                text = "：$reason",
                style = ZemoteType.uiXs,
                color = colors.warning,
            )
        }
    }
}

@Composable
private fun HookStatusGlyph(status: String) {
    val colors = LocalZemoteColors.current
    when (status) {
        "running" -> CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            strokeWidth = 1.5.dp,
            color = colors.foregroundSubtle,
        )

        else -> Icon(
            imageVector = hookStatusIcon(status),
            contentDescription = null,
            tint = hookStatusColor(status),
            modifier = Modifier.size(12.dp),
        )
    }
}

private fun hookStatusIcon(status: String): ImageVector = when (status) {
    "blocked", "timedOut" -> Icons.Default.Warning
    "cancelled" -> Icons.Default.Block
    "failed" -> Icons.Default.Cancel
    else -> Icons.Default.Anchor
}

@Composable
private fun hookStatusColor(status: String) = when (status) {
    "blocked", "timedOut" -> LocalZemoteColors.current.warning
    "failed" -> LocalZemoteColors.current.destructive
    else -> LocalZemoteColors.current.foregroundSubtle
}

internal fun hookStatusLabel(status: String): String = when (status) {
    "running" -> "运行中"
    "blocked" -> "已阻止"
    "failed" -> "失败"
    "cancelled" -> "已取消"
    "timedOut" -> "已超时"
    else -> "已完成"
}

private fun sourceLabel(sourceKind: String?): String = when (sourceKind) {
    "user" -> "用户"
    "project" -> "工作区"
    "plugin" -> "插件"
    else -> "内部"
}

/**
 * 网页端 `Gen()`：`<1s` 用整数毫秒；`<10s` 保留两位小数，否则一位。
 */
internal fun formatHookDuration(ms: Long): String {
    if (ms < 1000) return "${ms}ms"
    val seconds = ms / 1000.0
    return if (seconds < 10) String.format("%.2fs", seconds)
    else String.format("%.1fs", seconds)
}

/** 钩子入口的一行摘要：事件名/执行数 + 聚合状态 + 总耗时。 */
internal data class HookSummary(
    val title: String,
    val status: String?,
    val durationMs: Long?,
)

/** 聚合状态的优先级：失败 > 超时 > 阻止 > 运行中 > 取消。 */
private val HOOK_STATUS_PRIORITY = listOf("failed", "timedOut", "blocked", "running", "cancelled")

/**
 * 把执行列表折成一行摘要。
 *
 * 事件名一致时显示事件名（`PostToolUse · 3 次执行`），否则统一用「钩子」；
 * 有执行仍在运行时不显示总耗时（与逐条列表的「运行中不显示耗时」一致）。
 */
internal fun hookSummary(executions: List<HookExecution>): HookSummary? {
    if (executions.isEmpty()) return null
    val names = executions.map { it.eventName }.distinct()
    val title = (names.singleOrNull() ?: "钩子") + " · ${executions.size} 次执行"
    val status = HOOK_STATUS_PRIORITY.firstOrNull { candidate ->
        executions.any { it.status == candidate }
    }
    val running = executions.any { it.status == "running" }
    val durations = executions.mapNotNull { it.durationMs }
    val duration = when {
        running || durations.isEmpty() -> null
        else -> durations.sum()
    }
    return HookSummary(title = title, status = status, durationMs = duration)
}

/** 一条可展示的钩子执行（父行的 `hookEventName` + 子执行的状态）。 */
internal data class HookExecution(
    val eventName: String,
    val sourceKind: String?,
    val durationMs: Long?,
    val status: String?,
    val blockReason: String?,
)

/**
 * 展平 `hookInvocation.executions`：只保留 `didExecute === true`，按 `hookIndex` 升序。
 */
internal fun flattenHookExecutions(hooks: List<JsonObject>): List<HookExecution> {
    val out = ArrayList<HookExecution>()
    hooks.forEach { hook ->
        val eventName = hook["hookEventName"].asString().orEmpty().ifBlank { "hook" }
        val executions = hook["executions"] as? JsonArray ?: return@forEach
        executions
            .mapNotNull { it as? JsonObject }
            .filter { it["didExecute"].asBool() == true }
            .sortedBy { it["hookIndex"].asLong() ?: 0L }
            .forEach { execution ->
                val state = execution["state"].asString()
                out += HookExecution(
                    eventName = eventName,
                    sourceKind = execution["sourceKind"].asString(),
                    durationMs = execution["durationMs"].asLong().takeIf { state != "running" },
                    status = hookExecutionStatus(execution),
                    blockReason = execution["blockReason"].asString(),
                )
            }
    }
    return out
}

/** 网页端 `Ken()` 的状态判定；`completed`/`success` 返回 `null`（不渲染状态）。 */
private fun hookExecutionStatus(execution: JsonObject): String? {
    val state = execution["state"].asString()
    val outcome = execution["outcome"].asString()
    return when {
        state == "running" -> "running"
        outcome == "timed_out" -> "timedOut"
        outcome == "cancelled" -> "cancelled"
        outcome == "blocked" -> "blocked"
        state == "failed" || outcome == "failed" -> "failed"
        else -> null
    }
}
