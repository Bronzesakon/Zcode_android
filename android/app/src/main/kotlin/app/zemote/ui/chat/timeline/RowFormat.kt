package app.zemote.ui.chat.timeline

import app.zemote.ui.chat.asLong
import app.zemote.ui.chat.asObj
import app.zemote.ui.chat.asString
import java.util.Locale
import kotlinx.serialization.json.JsonObject

/**
 * 时间线渲染共用的格式化与判据。
 *
 * 数值口径与网页端一致：
 * - 时长优先 `activeMs`（turnHeader），其次 `durationMs`（reasoning/hook），
 *   最后用 `startedAt`/`endedAt` 求差；
 * - 小于 1 秒保留一位小数，避免「0秒」这种看起来像坏了的文案。
 */

/** 属于「一轮工作」的行：turnHeader 折叠时一起隐藏。 */
internal val WORK_ROW_KINDS: Set<String> =
    setOf("reasoning", "toolCall", "hookInvocation", "subagent")

/** 行的持续时长（毫秒）；无从计算时为 0。 */
internal fun durationMs(row: JsonObject): Long {
    row["activeMs"].asLong()?.let { return it }
    row["durationMs"].asLong()?.let { return it }
    val start = row["startedAt"].asLong() ?: return 0L
    val end = row["endedAt"].asLong() ?: System.currentTimeMillis()
    return (end - start).coerceAtLeast(0L)
}

/** `2分13秒` / `1时05分` / `0.4秒`。 */
internal fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0秒"
    if (ms < 1000) return String.format(Locale.US, "%.1f秒", ms / 1000.0)
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}时${minutes}分"
        minutes > 0 -> "${minutes}分${seconds}秒"
        else -> "${seconds}秒"
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(Locale.US, "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}

internal fun statusLabel(status: String): String = when (status) {
    "running" -> "运行中"
    "inputStreaming" -> "输入中"
    "pendingApproval" -> "等待确认"
    "success", "completed" -> "已完成"
    "failed", "error" -> "失败"
    "cancelled" -> "已取消"
    else -> status
}

/**
 * 工具行的单行摘要。
 *
 * 优先取结构化输入里的常见路径/命令键；没有结构化输入时退回 `inputText` 的第一行。
 */
internal fun toolSummary(row: JsonObject): String {
    val input = row["input"].asObj()
    val candidates = listOf(
        "path", "file_path", "filePath", "command", "cmd", "query", "pattern",
        "url", "description", "prompt", "name",
    )
    for (key in candidates) {
        input?.get(key).asString()?.takeIf { it.isNotBlank() }?.let { return singleLine(it) }
    }
    return singleLine(
        row["inputText"].asString().orEmpty()
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            .orEmpty(),
    )
}

/** 通知/单行摘要用：折叠所有空白。 */
internal fun singleLine(value: String): String = value.replace(Regex("\\s+"), " ").trim()
