package app.zemote.ui.chat.timeline

import app.zemote.ui.chat.asLong
import app.zemote.ui.chat.asObj
import app.zemote.ui.chat.asString
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToLong
import kotlinx.serialization.json.JsonObject

/**
 * 时间线渲染共用的格式化与判据。
 *
 * 数值口径与网页端一致：
 * - 时长优先 `activeMs`（turnHeader），其次 `durationMs`（reasoning/hook），
 *   最后用 `startedAt`/`endedAt` 求差；
 * - 展示按网页 `OZ()` 取整到秒并只保留两个单位（见 [formatDuration]）。
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

/**
 * 网页端 `OZ()`：秒数 = `max(1, round(ms/1000))`，拆成天/时/分/秒，
 * **只输出前两个非零单位**，中文数字与单位之间带空格。
 *
 * 例：`80_000` → `1 分 20 秒`；`3_600_000` → `1 时`；`400` → `1 秒`。
 */
internal fun formatDuration(ms: Long): String {
    val totalSeconds = max(1L, (ms / 1000.0).roundToLong())
    var rest = totalSeconds
    val parts = ArrayList<Pair<Long, String>>(2)
    for ((size, unit) in DURATION_UNITS) {
        val count = rest / size
        rest %= size
        if (count > 0 && parts.size < 2) parts += count to unit
    }
    return if (parts.isEmpty()) {
        "1 秒"
    } else {
        parts.joinToString(" ") { (count, unit) -> "$count $unit" }
    }
}

private val DURATION_UNITS = listOf(
    86_400L to "天",
    3_600L to "时",
    60L to "分",
    1L to "秒",
)

/**
 * 工作段标题文案（网页 `Ftn`）：运行中「工作中 {duration}」、完成「已工作 {duration}」/
 * 「已处理」、中断「已停止」。网页对 `failed` 没有专用文案，落到「已工作/已处理」。
 */
internal fun turnHeaderLabel(state: String?, durationMs: Long): String = when (state) {
    "running" -> if (durationMs > 0) "工作中 ${formatDuration(durationMs)}" else "工作中"
    "completedInterrupted" -> "已停止"
    "completedSuccess" -> if (durationMs > 0) "已工作 ${formatDuration(durationMs)}" else "已处理"
    "failed" -> if (durationMs > 0) "已工作 ${formatDuration(durationMs)}" else "已处理"
    else -> if (durationMs > 0) "已工作 ${formatDuration(durationMs)}" else "已处理"
}

/**
 * 思考行折叠标题（网页 `bZ`）：
 * - 流式中且折叠 → 「正在思考」；
 * - 其余（含流式中展开）→ 「思考 · {duration}」，没有耗时就只显示「思考」。
 */
internal fun reasoningLabel(streaming: Boolean, open: Boolean, durationMs: Long): String = when {
    streaming && !open -> "正在思考"
    durationMs > 0 -> "思考 · ${formatDuration(durationMs)}"
    else -> "思考"
}

/**
 * 产物副标题：`TYPE · size`（网页主时间线口径）。
 *
 * 类型为空时只留大小；大小为 `null` 时只留类型；都没有返回空串。
 */
internal fun artifactMeta(type: String, sizeBytes: Long?): String = listOfNotNull(
    type.uppercase().takeIf { it.isNotBlank() },
    sizeBytes?.let(::formatBytes),
).joinToString(" · ")

/** 产物版本徽标：网页只在版本 ≥2 时显示。 */
internal fun artifactVersionBadge(version: Long?): String? =
    version?.takeIf { it >= 2 }?.let { "v$it" }

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
