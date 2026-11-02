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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zemote.ui.chat.asObj
import app.zemote.ui.chat.asString
import app.zemote.ui.chat.isStreaming
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteColors
import app.zemote.ui.theme.ZemoteType
import kotlinx.serialization.json.JsonObject

/**
 * 工具调用行。
 *
 * 折叠：`[工具图标] [状态] 工具名 摘要 … 耗时 [箭头]`；
 * 展开：输入 / 输出 / 错误三个分区（等宽、限高滚动）。
 */
@Composable
internal fun ToolCallRow(
    row: JsonObject,
    open: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    showIcon: Boolean = true,
    subagent: JsonObject? = null,
    onSubagentClick: (() -> Unit)? = null,
) {
    val colors = LocalZemoteColors.current
    val status = row["status"].asString().orEmpty()
    val name = row["toolName"].asString().orEmpty().ifBlank { "tool" }
    val summary = toolSummary(row)
    val duration = durationMs(row)
    val streaming = row.isStreaming()
    val merged = subagent != null

    Column(modifier = modifier.fillMaxWidth()) {
        // 网页 read / search / message 明确 canToggle:false：不响应点击、无展开内容。
        val canToggle = toolFamilyOf(name) != ToolFamily.EXPLORE &&
            !name.contains("message", ignoreCase = true) &&
            !name.contains("respond", ignoreCase = true)
        val failureLabel = when (status) {
            "error", "failed" -> "执行失败"
            "cancelled" -> "已停止"
            else -> null
        }
        if (merged) {
            val sub = subagent!!
            val subRunning = sub["status"].asString() == "running"
            val agentName = sub["subagentType"].asString().orEmpty()
            val subSummary = sub["summaryText"].asString().orEmpty()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (onSubagentClick != null) {
                            Modifier.clickable(onClick = onSubagentClick)
                        } else {
                            Modifier
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = LucideIcons.Bot,
                    contentDescription = null,
                    tint = colors.foregroundSubtlest,
                    modifier = Modifier.size(16.dp).offset(y = 1.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "子智能体",
                    style = ZemoteType.uiBase,
                    color = colors.foregroundSubtlest,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = if (subRunning) {
                        Modifier.graphicsLayer(alpha = runningAlpha())
                    } else {
                        Modifier
                    },
                )
                if (agentName.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = agentName,
                        style = ZemoteType.uiBase.copy(fontFamily = FontFamily.Monospace),
                        color = agentColor(agentName, colors),
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text("·", style = ZemoteType.uiBase, color = colors.foregroundSubtlest)
                Spacer(Modifier.width(8.dp))
                AnimatedSummaryText(
                    text = subSummary,
                    style = ZemoteType.uiBase,
                    color = colors.foregroundSubtlest,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (canToggle) Modifier.clickable(onClick = onToggle) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showIcon) {
                    Icon(
                        imageVector = toolIcon(name),
                        contentDescription = null,
                        tint = colors.foregroundSubtlest,
                        modifier = Modifier.size(16.dp).offset(y = 1.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = toolKindLabel(name, status),
                    style = ZemoteType.uiBase,
                    color = colors.foregroundSubtlest,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = if (streaming) Modifier.graphicsLayer(alpha = runningAlpha()) else Modifier,
                )
                if (summary.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text("·", style = ZemoteType.uiBase, color = colors.foregroundSubtlest)
                    Spacer(Modifier.width(8.dp))
                    AnimatedSummaryText(
                        text = summary,
                        style = ZemoteType.uiBase,
                        color = colors.foregroundSubtlest,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (failureLabel != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = failureLabel,
                        style = ZemoteType.uiBase,
                        color = colors.destructive,
                        maxLines = 1,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = canToggle && open && !merged,
            enter = expandVertically(animationSpec = ZemoteCollapseSizeSpec) +
                fadeIn(animationSpec = ZemoteCollapseAlphaSpec),
            exit = shrinkVertically(animationSpec = ZemoteCollapseSizeSpec) +
                fadeOut(animationSpec = ZemoteCollapseAlphaSpec),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            ) {
                when {
                    toolFamilyOf(name) == ToolFamily.TERMINAL ->
                        ShellDetails(row = row, running = streaming)

                    name.contains("mcp", ignoreCase = true) -> McpDetails(row)

                    else -> GenericDetails(row)
                }
            }
        }
    }
}

/** 通用工具展开：输入 / 输出 / 进度 / 错误。 */
@Composable
private fun GenericDetails(row: JsonObject) {
    val colors = LocalZemoteColors.current
    val inputText = row["inputText"].asString().orEmpty()
    if (inputText.isNotBlank()) {
        MonoBlock("Parameters", inputText)
    }
    val outputText = row["output"].asObj()?.get("text").asString()
        ?: row["outputPreview"].asString()
        ?: ""
    if (outputText.isNotBlank()) {
        Spacer(Modifier.size(6.dp))
        MonoBlock("Result", outputText)
    }
    val progress = row["progress"].asString()
    if (!progress.isNullOrBlank()) {
        Spacer(Modifier.size(6.dp))
        Text(progress, style = ZemoteType.uiXs, color = colors.foregroundSubtle)
    }
    val error = row["error"].asObj()?.get("message").asString()
    if (!error.isNullOrBlank()) {
        Spacer(Modifier.size(6.dp))
        Text(
            text = "Error",
            style = ZemoteType.uiBase,
            color = colors.destructive,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.size(2.dp))
        Text(
            text = error,
            style = ZemoteType.uiXs.copy(fontFamily = FontFamily.Monospace),
            color = colors.destructive,
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.destructive.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                .padding(12.dp),
        )
    }
}

/**
 * shell 展开（网页 `PXt`）：命令块 `$` 提示符，折叠 3 行 / 展开 10 行；
 * 输出块 5 行滚动，运行中自动跟随底部，用户上滚后暂停跟随。
 */
@Composable
private fun ShellDetails(row: JsonObject, running: Boolean) {
    val colors = LocalZemoteColors.current
    val command = row["inputText"].asString().orEmpty()
    val output = row["output"].asObj()?.get("text").asString()
        ?: row["outputPreview"].asString()
        ?: ""
    var commandExpanded by remember { mutableStateOf(false) }
    val commandScroll = rememberScrollState()

    if (command.isNotBlank()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text(
                text = "$",
                style = ZemoteType.uiBase.copy(fontFamily = FontFamily.Monospace),
                color = colors.foregroundSubtle,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = command,
                style = ZemoteType.uiBase.copy(fontFamily = FontFamily.Monospace),
                color = colors.foreground,
                maxLines = if (commandExpanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (commandExpanded) {
                            Modifier
                                .heightIn(max = 200.dp)
                                .verticalScroll(commandScroll)
                        } else {
                            Modifier
                        },
                    ),
            )
        }
        if (command.lines().size > 3) {
            Text(
                text = if (commandExpanded) "收起" else "展开命令",
                style = ZemoteType.uiSm,
                color = colors.foregroundSubtle,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable { commandExpanded = !commandExpanded },
            )
        }
    }

    val outputScroll = rememberScrollState()
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(outputScroll) {
        snapshotFlow { outputScroll.isScrollInProgress to outputScroll.value }
            .collect { (active, value) ->
                if (!active) follow = value >= outputScroll.maxValue - 8
            }
    }
    LaunchedEffect(output) {
        if (running && follow) outputScroll.scrollTo(outputScroll.maxValue)
    }

    Spacer(Modifier.size(6.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 100.dp)
            .verticalScroll(outputScroll),
    ) {
        Text(
            text = output.ifBlank { if (running) "" else "没有输出。" },
            style = ZemoteType.uiBase.copy(
                fontFamily = FontFamily.Monospace,
                lineHeight = 20.sp,
            ),
            color = colors.foregroundSubtle,
        )
    }
}

/**
 * MCP 展开（网页 `YWe`）：错误优先；短结果（≤160 字符、单行、非 JSON）用段落，
 * 否则用最大 288dp 的等宽结果块。
 */
@Composable
private fun McpDetails(row: JsonObject) {
    val colors = LocalZemoteColors.current
    val error = row["error"].asObj()?.get("message").asString()
    val output = row["output"].asObj()?.get("text").asString()
        ?: row["outputPreview"].asString()
        ?: ""

    if (!error.isNullOrBlank()) {
        Text("结果", style = ZemoteType.uiBase, color = colors.destructive, fontWeight = FontWeight.Medium)
        Spacer(Modifier.size(4.dp))
        Text(
            text = error,
            style = ZemoteType.uiBase,
            color = colors.destructive,
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.destructive.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                .padding(12.dp),
        )
        return
    }
    if (output.isBlank()) return

    val short = output.length <= 160 &&
        !output.contains('\n') &&
        !output.trimStart().startsWith("{")
    if (short) {
        Text(
            text = output,
            style = ZemoteType.uiBase,
            color = colors.foregroundSubtle,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                .padding(12.dp),
        )
    } else {
        Text("结果", style = ZemoteType.uiBase, color = colors.foreground, fontWeight = FontWeight.Medium)
        Spacer(Modifier.size(4.dp))
        Text(
            text = output,
            style = ZemoteType.uiXs.copy(fontFamily = FontFamily.Monospace),
            color = colors.foregroundSubtle,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 288.dp)
                .verticalScroll(rememberScrollState())
                .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                .padding(12.dp),
        )
    }
}

@Composable
internal fun MonoBlock(label: String, text: String, modifier: Modifier = Modifier) {
    val colors = LocalZemoteColors.current
    Column(modifier.fillMaxWidth()) {
        Text(
            text = label.uppercase(),
            style = ZemoteType.uiBase,
            color = colors.foregroundSubtle,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.size(2.dp))
        Text(
            text = text,
            style = ZemoteType.uiXs.copy(fontFamily = FontFamily.Monospace),
            color = colors.foregroundSubtle,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState()),
        )
    }
}

/** 运行中的工具名做 2s 呼吸（网页是 4s 渐变扫光；Compose 侧用透明度近似）。 */
@Composable
private fun runningAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "tool-running")
    val alpha by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "tool-running-alpha",
    )
    return alpha
}

/** 子智能体名字的彩色等宽文本（网页按名字从 8 色调色板取色）。 */
private fun agentColor(name: String, colors: ZemoteColors): Color {
    val palette = listOf(
        colors.trajectoryUser,
        colors.trajectoryAssistant,
        colors.trajectoryReasoning,
        colors.trajectoryToolCall,
        colors.trajectoryToolResult,
        colors.brand,
        colors.success,
        colors.warning,
    )
    return palette[Math.floorMod(name.hashCode(), palette.size)]
}
