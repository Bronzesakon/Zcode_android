package app.zemote.ui.chat.timeline

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
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
import app.zemote.ui.theme.ZemoteSpacing
import app.zemote.ui.theme.ZemoteType
import kotlinx.serialization.json.JsonObject

/**
 * 工具调用行。
 *
 * 折叠（网页口径）：`[工具图标] 工具名 · 摘要 [箭头]`，无状态勾/叉、无耗时；
 * 运行中工具名高亮并呼吸，结束后回到普通灰。展开内容按 web 卡片边距渲染。
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
    // 只依赖行内容的派生值：行对象没变时（哪怕是别处的状态更新导致的整行重组）
    // 直接复用，避免重复做 Json 查找、lowercase、摘要压平与图标家族判定。
    val status = remember(row) { row["status"].asString().orEmpty() }
    val name = remember(row) { row["toolName"].asString().orEmpty().ifBlank { "tool" } }
    val summary = remember(row) { toolSummary(row) }
    val streaming = remember(row) { row.isStreaming() }
    val merged = subagent != null
    // 网页 read / search / message 明确 canToggle:false：不响应点击、无展开内容。
    val family = remember(name) { toolFamilyOf(name) }
    val canToggle = remember(family, name) {
        family != ToolFamily.EXPLORE &&
            !name.contains("message", ignoreCase = true) &&
            !name.contains("respond", ignoreCase = true)
    }
    val kindLabel = remember(name, status) { toolKindLabel(name, status) }
    val toolIconVector = remember(name) { toolIcon(name) }
    val failureLabel = remember(status) {
        when (status) {
            "error", "failed" -> "执行失败"
            "cancelled" -> "已停止"
            else -> null
        }
    }
    val isShell = family == ToolFamily.TERMINAL
    val isMcp = remember(name) { name.contains("mcp", ignoreCase = true) }
    // 呼吸透明度只在 graphicsLayer 的 block 里读取，动画帧不再触发整行重组。
    // merged 行用的是 subagent 自己的状态，不需要为工具行再挂一条动画。
    val runningAlpha = if (streaming && !merged) rememberRunningAlpha() else null
    val destructiveTint = remember(colors) { colors.destructive.copy(alpha = 0.1f) }

    Column(modifier = modifier.fillMaxWidth()) {
        if (merged) {
            val sub = subagent!!
            val subRunning = remember(sub) { sub["status"].asString() == "running" }
            val agentName = remember(sub) { sub["subagentType"].asString().orEmpty() }
            val subSummary = remember(sub) { sub["summaryText"].asString().orEmpty() }
            val subAlpha = if (subRunning) rememberRunningAlpha() else null
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
                horizontalArrangement = Arrangement.spacedBy(ZemoteSpacing.space2),
            ) {
                Icon(
                    imageVector = LucideIcons.Bot,
                    contentDescription = null,
                    tint = colors.foregroundSubtlest,
                    modifier = Modifier.size(ZemoteSpacing.space4),
                )
                Text(
                    text = "子智能体",
                    style = ZemoteType.uiBase,
                    color = colors.foregroundSubtlest,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = if (subAlpha != null) {
                        Modifier.graphicsLayer { alpha = subAlpha.value }
                    } else {
                        Modifier
                    },
                )
                if (agentName.isNotBlank()) {
                    Text(
                        text = agentName,
                        style = MonoBaseStyle,
                        color = agentColor(agentName, colors),
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text("·", style = ZemoteType.uiBase, color = colors.foregroundSubtlest)
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
                    .then(if (canToggle) Modifier.softClickable(onToggle) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
                // 组内子行没有图标，但「名称 · 摘要」之间仍需 8dp；统一用 spacedBy 保证
                // 有无图标时文字间距一致，不再出现「编辑·路径」挤在一起。
                horizontalArrangement = Arrangement.spacedBy(ZemoteSpacing.space2),
            ) {
                if (showIcon) {
                    Icon(
                        imageVector = toolIconVector,
                        contentDescription = null,
                        tint = colors.foregroundSubtlest,
                        modifier = Modifier.size(ZemoteSpacing.space4),
                    )
                }
                Text(
                    text = kindLabel,
                    style = ZemoteType.uiBase,
                    // 网页运行中的工具名有渐变高亮；原生用更亮的字色 + 呼吸透明度近似。
                    color = if (streaming) colors.foreground else colors.foregroundSubtlest,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = if (runningAlpha != null) {
                        Modifier.graphicsLayer { alpha = runningAlpha.value }
                    } else {
                        Modifier
                    },
                )
                if (summary.isNotBlank()) {
                    Text("·", style = ZemoteType.uiBase, color = colors.foregroundSubtlest)
                    AnimatedSummaryText(
                        text = summary,
                        style = ZemoteType.uiBase,
                        color = colors.foregroundSubtlest,
                        // web summary 是 inline-flex，chevron 紧跟文本而不是被推到行尾。
                        modifier = Modifier.weight(1f, fill = false),
                    )
                } else {
                    Spacer(Modifier.weight(1f, fill = false))
                }
                if (failureLabel != null) {
                    Text(
                        text = failureLabel,
                        style = ZemoteType.uiBase,
                        color = colors.foregroundSubtlest,
                        maxLines = 1,
                    )
                }
                if (canToggle) {
                    ExpandChevron(expanded = open, tint = colors.foregroundSubtlest)
                }
            }
        }

        // 网页工具折叠：高度 0↔内容高，300ms ease-in-out（09 F5）。
        CollapsibleContent(
            visible = canToggle && open && !merged,
            durationMillis = ZemoteToolContentCollapseMs,
            easing = ZemoteEaseInOut,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = ZemoteSpacing.space2),
            ) {
                when {
                    isShell -> ShellDetails(row = row, running = streaming)

                    isMcp -> McpDetails(row, destructiveTint)

                    else -> GenericDetails(row, destructiveTint)
                }
                // 网页展开卡 `mb-2`：展开子行到下一子行 16dp、到组底多 8dp（09 F2）。
                if (isShell) Spacer(Modifier.size(ZemoteSpacing.space2))
            }
        }
    }
}

/** 工具详情里单个文本块的渲染上限（原始数据不截断，复制仍完整）。 */
private const val DETAIL_TEXT_RENDER_LIMIT = 20_000

/** 命令串上限：超过 3 行/200dp 展示本来也看不全，避免超长单行参与整段布局。 */
private const val DETAIL_COMMAND_RENDER_LIMIT = 4_000

/** 通用工具展开：输入 / 输出 / 进度 / 错误。 */
@Composable
private fun GenericDetails(row: JsonObject, errorBackground: Color) {
    val colors = LocalZemoteColors.current
    val inputText = remember(row) { row["inputText"].asString().orEmpty() }
    if (inputText.isNotBlank()) {
        MonoBlock("Parameters", inputText)
    }
    val outputText = remember(row) {
        row["output"].asObj()?.get("text").asString()
            ?: row["outputPreview"].asString()
            ?: ""
    }
    if (outputText.isNotBlank()) {
        Spacer(Modifier.size(ZemoteSpacing.space2))
        MonoBlock("Result", outputText)
    }
    val progress = remember(row) { row["progress"].asString() }
    if (!progress.isNullOrBlank()) {
        Spacer(Modifier.size(ZemoteSpacing.space2))
        Text(progress, style = ZemoteType.uiXs, color = colors.foregroundSubtle)
    }
    val error = remember(row) { row["error"].asObj()?.get("message").asString() }
    if (!error.isNullOrBlank()) {
        Spacer(Modifier.size(ZemoteSpacing.space2))
        Text(
            text = "Error",
            style = ZemoteType.uiBase,
            color = colors.destructive,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.size(2.dp))
        Text(
            text = clampTextForRender(error, DETAIL_TEXT_RENDER_LIMIT),
            style = MonoXsStyle,
            color = colors.destructive,
            modifier = Modifier
                .fillMaxWidth()
                .background(errorBackground, DetailErrorShape)
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
    val command = remember(row) { row["inputText"].asString().orEmpty() }
    val output = remember(row) {
        row["output"].asObj()?.get("text").asString()
            ?: row["outputPreview"].asString()
            ?: ""
    }
    var commandExpanded by remember { mutableStateOf(false) }
    val commandScroll = rememberScrollState()

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

    // 网页 `PXt` 卡片：rounded-xl border bg-panel px-4 py-3，区块间 space-y-3（12dp）。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.panel, DetailCardShape)
            .border(1.dp, colors.border, DetailCardShape)
            .padding(horizontal = ZemoteSpacing.space4, vertical = ZemoteSpacing.space3),
    ) {
        if (command.isNotBlank()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(
                    text = "$",
                    style = MonoBaseStyle,
                    color = colors.foregroundSubtle,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = clampTextForRender(command, DETAIL_COMMAND_RENDER_LIMIT),
                    style = MonoBaseStyle,
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
            if (lineCountExceeds(command, 3)) {
                Text(
                    text = if (commandExpanded) "收起" else "展开命令",
                    style = ZemoteType.uiSm,
                    color = colors.foregroundSubtle,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clip(ToolRowShape)
                        .clickable { commandExpanded = !commandExpanded },
                )
            }
            Spacer(Modifier.size(ZemoteSpacing.space3))
        }

        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 100.dp)
                .verticalScroll(outputScroll),
        ) {
            Text(
                text = clampTextForRender(
                    output.ifBlank { if (running) "" else "没有输出。" },
                    DETAIL_TEXT_RENDER_LIMIT,
                ),
                style = MonoOutputStyle,
                color = colors.foregroundSubtle,
            )
        }
    }
}

/**
 * MCP 展开（网页 `YWe`）：错误优先；短结果（≤160 字符、单行、非 JSON）用段落，
 * 否则用最大 288dp 的等宽结果块。
 */
@Composable
private fun McpDetails(row: JsonObject, errorBackground: Color) {
    val colors = LocalZemoteColors.current
    val error = remember(row) { row["error"].asObj()?.get("message").asString() }
    val output = remember(row) {
        row["output"].asObj()?.get("text").asString()
            ?: row["outputPreview"].asString()
            ?: ""
    }

    if (!error.isNullOrBlank()) {
        Text("结果", style = ZemoteType.uiBase, color = colors.destructive, fontWeight = FontWeight.Medium)
        Spacer(Modifier.size(ZemoteSpacing.space2))
        Text(
            text = error,
            style = ZemoteType.uiBase,
            color = colors.destructive,
            modifier = Modifier
                .fillMaxWidth()
                .background(errorBackground, DetailErrorShape)
                .padding(ZemoteSpacing.space3),
        )
        return
    }
    if (output.isBlank()) return

    val short = remember(output) {
        output.length <= 160 &&
            !output.contains('\n') &&
            !output.trimStart().startsWith("{")
    }
    if (short) {
        // 网页短结果：px-3 py-2 的普通段落，不套卡片边框。
        Text(
            text = output,
            style = ZemoteType.uiBase,
            color = colors.foregroundSubtle,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ZemoteSpacing.space3, vertical = ZemoteSpacing.space2),
        )
    } else {
        Text("结果", style = ZemoteType.uiBase, color = colors.foreground, fontWeight = FontWeight.Medium)
        Spacer(Modifier.size(ZemoteSpacing.space2))
        Text(
            text = clampTextForRender(output, DETAIL_TEXT_RENDER_LIMIT),
            style = MonoXsStyle,
            color = colors.foregroundSubtle,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 288.dp)
                .verticalScroll(rememberScrollState())
                .background(
                    remember(colors) { colors.backgroundAlt.copy(alpha = 0.5f) },
                    DetailSectionShape,
                )
                .padding(ZemoteSpacing.space3),
        )
    }
}

@Composable
internal fun MonoBlock(label: String, text: String, modifier: Modifier = Modifier) {
    val colors = LocalZemoteColors.current
    Column(modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = ZemoteType.uiBase,
            color = colors.foregroundSubtle,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.size(ZemoteSpacing.space2))
        Text(
            text = clampTextForRender(text, DETAIL_TEXT_RENDER_LIMIT),
            style = MonoBlockStyle,
            color = colors.foregroundSubtle,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState())
                .background(
                    remember(colors) { colors.backgroundAlt.copy(alpha = 0.5f) },
                    DetailSectionShape,
                )
                .padding(ZemoteSpacing.space3),
        )
    }
}

/**
 * 运行中的工具名做 2s 呼吸（网页是 4s 渐变扫光；Compose 侧用透明度近似）。
 *
 * 返回 `State` 而不是直接返回当前值：调用方在 `graphicsLayer { alpha = ... }` 的
 * block 里读取，动画帧只更新图层，不会每帧重组整行。
 */
@Composable
private fun rememberRunningAlpha(): State<Float> {
    val transition = rememberInfiniteTransition(label = "tool-running")
    return transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "tool-running-alpha",
    )
}

/** 子智能体名字的彩色等宽文本（网页按名字从 8 色调色板取色）。 */
@Composable
private fun agentColor(name: String, colors: ZemoteColors): Color {
    val palette = remember(colors) {
        arrayOf(
            colors.trajectoryUser,
            colors.trajectoryAssistant,
            colors.trajectoryReasoning,
            colors.trajectoryToolCall,
            colors.trajectoryToolResult,
            colors.brand,
            colors.success,
            colors.warning,
        )
    }
    return palette[Math.floorMod(name.hashCode(), palette.size)]
}

// 组合外常量：等宽/行高组合的文字样式与详情卡片形状不随组合变化，没必要每次新建。
private val MonoBaseStyle = ZemoteType.uiBase.copy(fontFamily = FontFamily.Monospace)
private val MonoXsStyle = ZemoteType.uiXs.copy(fontFamily = FontFamily.Monospace)
private val MonoOutputStyle = ZemoteType.uiBase.copy(
    fontFamily = FontFamily.Monospace,
    lineHeight = 20.sp,
)
private val MonoBlockStyle = ZemoteType.mono.copy(
    fontSize = ZemoteType.uiSm.fontSize,
    lineHeight = 20.sp,
)
private val DetailErrorShape = RoundedCornerShape(6.dp)
private val DetailSectionShape = RoundedCornerShape(6.dp)
private val DetailCardShape = RoundedCornerShape(12.dp)
/** 折叠工具行的按压区域：网页没有整行底色，只用小圆角 ripple 提示可点。 */
private val ToolRowShape = RoundedCornerShape(6.dp)
