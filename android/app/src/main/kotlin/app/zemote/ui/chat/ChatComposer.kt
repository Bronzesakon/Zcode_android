package app.zemote.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zemote.ui.chat.timeline.formatBytes
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteDuration
import app.zemote.ui.theme.ZemoteRadius
import app.zemote.ui.theme.ZemoteSpacing
import app.zemote.ui.theme.ZemoteType
import kotlinx.serialization.json.JsonObject

/**
 * 输入区（网页 `chat-composer-region` 的 Android 复刻）。
 *
 * 结构：`rounded-2xl` 卡片 + `p-3` 内边距 + `gap-3` 子项间距；
 * 附件行 → 多行编辑器（最多 6 行）→ 工具条（附件 / 模型 / 模式 / 思考 / 发送或停止）。
 * 聚焦只改边框色与背景色，不加 ring（报告 06 §2.3）。
 */
@Composable
internal fun ChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    sending: Boolean,
    uploading: Boolean,
    uploadIndex: Int,
    uploadProgress: Float,
    running: Boolean,
    hasHistory: Boolean,
    attachments: List<ChatAttachment>,
    model: String?,
    mode: String?,
    thought: String?,
    levels: List<String>,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onOpenModel: () -> Unit,
    onPickAttachment: () -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    onMode: (String) -> Unit,
    onThought: (String) -> Unit,
) {
    val colors = LocalZemoteColors.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    // 三态：默认透明 / hover / 聚焦（网页只改边框色 + 背景色，不加 ring）。
    val borderColor by animateColorAsState(
        targetValue = when {
            focused -> colors.inputBorderFocused
            hovered -> colors.inputBorderHover
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = ZemoteDuration.Default),
    )
    val surfaceColor by animateColorAsState(
        targetValue = if (focused) colors.inputFocused else colors.input,
        animationSpec = tween(durationMillis = ZemoteDuration.Default),
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ZemoteRadius.xxl))
            .background(surfaceColor)
            .border(1.dp, borderColor, RoundedCornerShape(ZemoteRadius.xxl))
            .padding(ZemoteSpacing.space3),
        verticalArrangement = Arrangement.spacedBy(ZemoteSpacing.space3),
    ) {
        if (attachments.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(ZemoteSpacing.space2),
            ) {
                attachments.forEachIndexed { index, attachment ->
                    AttachmentChip(
                        attachment = attachment,
                        progress = if (uploading && index == uploadIndex) uploadProgress else null,
                        onRemove = { onRemoveAttachment(index) },
                    )
                }
            }
        }

        val placeholder = when {
            running -> "继续输入以排队后续修改"
            hasHistory -> "提出后续修改要求"
            else -> "向 ZCode 提问…"
        }
        Box(Modifier.fillMaxWidth()) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = ZemoteType.mobileInputSafe,
                    color = colors.foregroundSubtlest,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                interactionSource = interaction,
                // min 40dp / max 160dp，超出后编辑器内部滚动（网页 min-h-10 / max-h-40）。
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp, max = 160.dp),
                textStyle = ZemoteType.mobileInputSafe.copy(color = colors.foreground),
                // 移动端网页 Enter 只换行，发送靠按钮（报告 06 §4.3）。
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                cursorBrush = SolidColor(colors.brand),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZemoteSpacing.space1),
            ) {
                ComposerIconButton(
                    icon = Icons.Default.Add,
                    contentDescription = "添加上下文",
                    onClick = onPickAttachment,
                )
                ModeButton(mode = mode, onSelect = onMode)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZemoteSpacing.space1),
            ) {
                ModelButton(model = model, onClick = onOpenModel)
                if (levels.isNotEmpty()) {
                    EffortButton(thought = thought, levels = levels, onSelect = onThought)
                }
                SendStopButton(
                    running = running,
                    inputBlank = value.isBlank(),
                    canSend = (value.isNotBlank() || attachments.isNotEmpty()) && !sending && !uploading,
                    sending = sending,
                    onSend = onSend,
                    onStop = onStop,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 工具条控件（全部自绘，M3 只提供点击/菜单行为）
// ---------------------------------------------------------------------------

@Composable
private fun ComposerIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val colors = LocalZemoteColors.current
    Box(
        modifier = Modifier
            .size(ZemoteSpacing.space9)
            .clip(RoundedCornerShape(ZemoteRadius.lg))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = colors.foregroundSubtle,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun ModelButton(model: String?, onClick: () -> Unit) {
    val colors = LocalZemoteColors.current
    Row(
        modifier = Modifier
            .height(ZemoteSpacing.space9)
            .clip(RoundedCornerShape(ZemoteRadius.lg))
            .clickable(onClick = onClick)
            .padding(horizontal = ZemoteSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = model ?: "选择模型",
            style = ZemoteType.uiBase,
            color = colors.foregroundSubtle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 96.dp),
        )
        Spacer(Modifier.width(ZemoteSpacing.space1))
        Icon(
            Icons.Default.ExpandMore,
            contentDescription = null,
            tint = colors.foregroundSubtlest,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun ModeButton(mode: String?, onSelect: (String) -> Unit) {
    val colors = LocalZemoteColors.current
    var open by remember { mutableStateOf(false) }
    val current = mode?.takeIf { it.isNotBlank() } ?: "build"
    Box {
        Box(
            modifier = Modifier
                .size(ZemoteSpacing.space9)
                .clip(RoundedCornerShape(ZemoteRadius.lg))
                .clickable { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                modeIcon(current),
                contentDescription = "模式：${modeLabel(current)}",
                tint = if (current == "yolo") colors.warning else colors.foregroundSubtle,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(colors.popover),
        ) {
            modeOptions(current).forEach { id ->
                DropdownMenuItem(
                    text = { Text(modeLabel(id), style = ZemoteType.uiBase, color = colors.foreground) },
                    trailingIcon = if (id == current) {
                        {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "当前模式",
                                tint = colors.foreground,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    } else {
                        null
                    },
                    onClick = {
                        open = false
                        onSelect(id)
                    },
                )
            }
        }
    }
}

@Composable
private fun EffortButton(thought: String?, levels: List<String>, onSelect: (String) -> Unit) {
    val colors = LocalZemoteColors.current
    var open by remember { mutableStateOf(false) }
    val current = thought?.takeIf { it in levels } ?: levels.first()
    Box {
        Row(
            modifier = Modifier
                .height(ZemoteSpacing.space9)
                .clip(RoundedCornerShape(ZemoteRadius.lg))
                .clickable { open = true }
                .padding(horizontal = ZemoteSpacing.space1_5),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = "思考等级：$current",
                tint = colors.foregroundSubtle,
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(colors.popover),
        ) {
            levels.forEach { level ->
                DropdownMenuItem(
                    text = { Text(level, style = ZemoteType.uiBase, color = colors.foreground) },
                    trailingIcon = if (level == current) {
                        {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "当前思考等级",
                                tint = colors.foreground,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    } else {
                        null
                    },
                    onClick = {
                        open = false
                        onSelect(level)
                    },
                )
            }
        }
    }
}

@Composable
private fun SendStopButton(
    running: Boolean,
    inputBlank: Boolean,
    canSend: Boolean,
    sending: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val colors = LocalZemoteColors.current
    // 网页：运行中且输入为空才显示停止；有输入时显示发送（加入队列）。
    if (running && inputBlank) {
        Box(
            modifier = Modifier
                .size(ZemoteSpacing.space9)
                .clip(RoundedCornerShape(ZemoteRadius.lg))
                .background(colors.secondary)
                .clickable(onClick = onStop),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Stop,
                contentDescription = "停止生成",
                tint = colors.foreground,
                modifier = Modifier.size(16.dp),
            )
        }
    } else {
        Box(
            modifier = Modifier
                .size(ZemoteSpacing.space9)
                .clip(RoundedCornerShape(ZemoteRadius.lg))
                .background(if (canSend || sending) colors.brand else colors.brand.copy(alpha = 0.4f))
                .clickable(enabled = canSend, onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            if (sending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 1.5.dp,
                    color = colors.foregroundInverse,
                )
            } else {
                Icon(
                    Icons.Default.ArrowUpward,
                    contentDescription = "发送",
                    tint = colors.foregroundInverse,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 附件 chips
// ---------------------------------------------------------------------------

@Composable
private fun AttachmentChip(
    attachment: ChatAttachment,
    progress: Float?,
    onRemove: () -> Unit,
) {
    val colors = LocalZemoteColors.current
    Row(
        modifier = Modifier
            .height(ZemoteSpacing.space12)
            .clip(RoundedCornerShape(ZemoteRadius.lg))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(ZemoteRadius.lg))
            .padding(start = ZemoteSpacing.space1_5, end = ZemoteSpacing.space0_5),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(ZemoteSpacing.space9)
                .clip(RoundedCornerShape(ZemoteRadius.md))
                .background(colors.background),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.InsertDriveFile,
                contentDescription = null,
                tint = colors.foregroundSubtle,
                modifier = Modifier.size(16.dp),
            )
            if (progress != null) {
                UploadProgress(progress)
            }
        }
        Spacer(Modifier.width(ZemoteSpacing.space2))
        Column(Modifier.widthIn(max = 160.dp)) {
            Text(
                text = attachment.name,
                style = ZemoteType.uiBase,
                color = colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (progress != null) {
                    "${(progress * 100).toInt()}%"
                } else {
                    formatBytes(attachment.bytes.size.toLong())
                },
                style = ZemoteType.uiSm,
                color = if (progress != null) colors.brand else colors.foregroundSubtle,
            )
        }
        Box(
            modifier = Modifier
                .size(ZemoteSpacing.space8)
                .clip(RoundedCornerShape(ZemoteRadius.sm))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "移除附件 ${attachment.name}",
                tint = colors.foregroundSubtle,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** 上传进度环：36dp 容器、2dp 描边、7sp 百分比（网页 `r=9 / strokeWidth=2`）。 */
@Composable
private fun UploadProgress(progress: Float) {
    val colors = LocalZemoteColors.current
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background.copy(alpha = 0.85f))
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f)
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(2.dp)) {
            val stroke = 2.dp.toPx()
            drawArc(
                color = colors.border,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = stroke),
            )
            drawArc(
                color = colors.brand,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                style = Stroke(width = stroke),
            )
        }
        Text(
            text = "${(progress * 100).toInt()}%",
            style = ZemoteType.ui2xs.copy(fontSize = 7.sp),
            color = colors.foreground,
        )
    }
}

// ---------------------------------------------------------------------------
// 取值口径
// ---------------------------------------------------------------------------

/** 输入区模型按钮文案：默认 provider `glm` 省略前缀（网页 `uyt` 口径）。 */
internal fun modelLabel(config: JsonObject?): String? {
    val provider = config?.get("provider").asString()
    val model = config?.get("model").asString() ?: return null
    return if (provider.isNullOrBlank() || provider == "glm") model else "$provider/$model"
}

/** 网页端 Mode 菜单不含 plan（plan 由独立开关控制）；兼容值 edit 按需兜底。 */
private fun modeOptions(current: String?): List<String> {
    val base = listOf("build", "guarded", "yolo")
    return if (current != null && current.isNotBlank() && current !in base) {
        base + current
    } else {
        base
    }
}

/** 网页 `src-AL-_ONNp.js:49494` 的 Mode 展示名。 */
private fun modeLabel(id: String): String = when (id) {
    "build" -> "变更前确认"
    "guarded" -> "自主模式"
    "plan" -> "计划模式"
    "yolo" -> "完全访问"
    "edit" -> "编辑"
    else -> id
}

private fun modeIcon(id: String): ImageVector = when (id) {
    "guarded" -> Icons.Default.Lock
    "plan" -> Icons.Default.Description
    "yolo" -> Icons.Default.Warning
    "edit" -> Icons.Default.Edit
    else -> Icons.Default.Code
}
