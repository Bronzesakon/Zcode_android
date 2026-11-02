package app.zemote.ui.chat.timeline

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.InsertDriveFile
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.zemote.ui.chat.asLong
import app.zemote.ui.chat.asString
import app.zemote.ui.chat.media.RemoteImage
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteType
import app.zemote.ui.theme.ZemoteType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 用户消息：右侧气泡（88% 宽），图片附件渲染缩略图，其余附件渲染文件 pill。
 */
@Composable
internal fun UserInputRow(
    row: JsonObject,
    onImageClick: (String) -> Unit,
    imageLoader: suspend (String) -> ByteArray?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalZemoteColors.current
    val text = row["text"].asString().orEmpty()
    val attachments = row["attachments"] as? JsonArray
    val shape = RoundedCornerShape(topStart = 12.dp, topEnd = 2.dp, bottomEnd = 12.dp, bottomStart = 12.dp)

    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 0.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 576.dp)
                .clip(shape)
                .background(colors.surface)
                .border(1.dp, colors.border, shape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (text.isNotBlank()) {
                CollapsibleBubbleText(text = text)
            }
            if (!attachments.isNullOrEmpty()) {
                Spacer(Modifier.height(6.dp))
                attachments.forEach { item ->
                    val obj = item as? JsonObject ?: return@forEach
                    val mime = obj["mime"].asString().orEmpty()
                    val ref = obj["ref"].asString()
                    if (mime.startsWith("image/") && ref != null) {
                        RemoteImage(
                            key = ref,
                            loader = imageLoader,
                            modifier = Modifier
                                .size(80.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.background),
                            onClick = { onImageClick(ref) },
                        )
                        Spacer(Modifier.height(8.dp))
                    } else {
                        AttachmentPill(obj)
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

/**
 * 超长用户消息折叠到 120dp。
 *
 * 网页端：`scrollHeight > 121px` 才折叠，折叠高度 120px，底部 30% 渐隐，
 * 圆形「展开/收起」按钮浮在底部；展开后高度回到内容高度、按钮移到正文下方。
 * 展开状态不持久化，文本变化时重置。
 */
@Composable
private fun CollapsibleBubbleText(text: String) {
    val colors = LocalZemoteColors.current
    var expanded by remember(text) { mutableStateOf(false) }
    val density = LocalDensity.current

    SubcomposeLayout(
        modifier = Modifier
            .clipToBounds()
            .animateContentSize(),
    ) { constraints ->
        val body = subcompose("body") {
            // 网页把用户输入当纯文本（whitespace-pre-wrap），不解析 Markdown。
            Text(
                text = text,
                style = ZemoteType.uiBase,
                color = colors.foreground,
                softWrap = true,
            )
        }.first().measure(Constraints(maxWidth = constraints.maxWidth))

        val limit = with(density) { COLLAPSED_HEIGHT.roundToPx() }
        val overflow = body.height > limit + with(density) { 1.dp.roundToPx() }
        val collapsed = overflow && !expanded
        val bodyHeight = if (collapsed) limit else body.height
        val contentWidth = body.width.coerceAtMost(constraints.maxWidth)

        val fade = if (collapsed) {
            subcompose("fade") {
                Box(
                    Modifier
                        .widthIn(max = with(density) { contentWidth.toDp() })
                        .height(COLLAPSED_HEIGHT * 0.3f)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, colors.background),
                            ),
                        ),
                )
            }.first().measure(Constraints(maxWidth = contentWidth))
        } else {
            null
        }

        val toggle = if (overflow) {
            subcompose("toggle") {
                ExpandToggle(
                    expanded = expanded,
                    onToggle = { expanded = !expanded },
                )
            }.first().measure(Constraints(maxWidth = contentWidth))
        } else {
            null
        }

        val totalHeight = if (collapsed) {
            bodyHeight
        } else {
            bodyHeight + (toggle?.height ?: 0)
        }

        layout(contentWidth, totalHeight) {
            body.place(0, 0)
            fade?.place(0, bodyHeight - fade.height)
            if (toggle != null) {
                if (collapsed) {
                    toggle.place((contentWidth - toggle.width) / 2, bodyHeight - toggle.height)
                } else {
                    toggle.place(0, bodyHeight)
                }
            }
        }
    }
}

@Composable
private fun ExpandToggle(expanded: Boolean, onToggle: () -> Unit) {
    val colors = LocalZemoteColors.current
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            onClick = onToggle,
            shape = CircleShape,
            color = colors.background,
            shadowElevation = 2.dp,
        ) {
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = colors.foregroundSubtle,
                modifier = Modifier
                    .padding(4.dp)
                    .size(16.dp)
                    .rotate(if (expanded) 180f else 0f),
            )
        }
    }
}

private val COLLAPSED_HEIGHT = 120.dp

@Composable
internal fun AttachmentPill(obj: JsonObject, modifier: Modifier = Modifier) {
    val colors = LocalZemoteColors.current
    val name = obj["fileName"].asString() ?: obj["name"].asString() ?: "附件"
    val bytes = obj["bytes"].asLong() ?: obj["sizeBytes"].asLong()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(colors.surface)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.InsertDriveFile,
            contentDescription = null,
            tint = colors.foregroundSubtle,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (bytes != null) "$name · ${formatBytes(bytes)}" else name,
            style = ZemoteType.uiSm,
            color = colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
