package app.zemote.ui.chat.timeline

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import app.zemote.ui.chat.markdown.MarkdownText
import app.zemote.ui.chat.media.ImageThumbGrid
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteContainer
import app.zemote.ui.theme.ZemoteRadius
import app.zemote.ui.theme.ZemoteSpacing
import app.zemote.ui.theme.ZemoteType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 用户消息：右对齐气泡（`rounded-xl` + 右上角小圆角），正文 Markdown 内联，
 * 图片 80dp 缩略图网格，其余附件 32dp 文件 pill。
 *
 * 网页只给用户行挂了 `复制`/`编辑` 两个行级动作，长按菜单据此显示
 * （`canEdit` 由行的 `actions` 决定，见 [availableRowActions]）。
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
    // 网页 rounded-xl（12px）+ rounded-tr-xs（2px）。
    val shape = RoundedCornerShape(
        topStart = ZemoteRadius.xl,
        topEnd = ZemoteRadius.xs,
        bottomEnd = ZemoteRadius.xl,
        bottomStart = ZemoteRadius.xl,
    )
    val items = attachments?.mapNotNull { it as? JsonObject }.orEmpty()
    val mediaKeys = items.mapNotNull { item ->
        val mime = item["mime"].asString().orEmpty()
        item["ref"].asString()?.takeIf { mime.startsWith("image/") }
    }
    val files = items.filter { item ->
        val mime = item["mime"].asString().orEmpty()
        !(mime.startsWith("image/") && item["ref"].asString() != null)
    }

    RowActionContainer(row = row, modifier = modifier.fillMaxWidth()) { gesture ->
        Column(
            modifier = Modifier.fillMaxWidth().then(gesture),
            horizontalAlignment = Alignment.End,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = ZemoteContainer.xl)
                    .clip(shape)
                    .background(colors.surface)
                    .border(1.dp, colors.border, shape)
                    .padding(
                        horizontal = ZemoteSpacing.space4,
                        vertical = ZemoteSpacing.space3,
                    ),
                verticalArrangement = Arrangement.spacedBy(ZemoteSpacing.space2),
            ) {
                if (text.isNotBlank()) {
                    CollapsibleBubbleText(text = text, onImageClick = onImageClick)
                }
                if (mediaKeys.isNotEmpty()) {
                    // 只读气泡的图片是 80dp、圆角 12、gap 8（48dp/圆角 8 只用于编辑态）。
                    ImageThumbGrid(
                        keys = mediaKeys,
                        loader = imageLoader,
                        onClick = onImageClick,
                        thumbSize = READ_ONLY_THUMB_SIZE,
                    )
                }
                files.forEach { item -> AttachmentPill(item) }
            }
        }
    }
}

/** 网页 `size-20` = 80px，只读用户气泡里的图片缩略图。 */
private val READ_ONLY_THUMB_SIZE = 80.dp

/**
 * 超长用户消息折叠到 120dp。
 *
 * 网页端：`scrollHeight > 121px` 才折叠，折叠高度 120px，底部 30% 渐隐，
 * 圆形「展开/收起」按钮浮在底部；展开后高度回到内容高度、按钮移到正文下方。
 * 展开状态不持久化，文本变化时重置。
 */
@Composable
private fun CollapsibleBubbleText(text: String, onImageClick: (String) -> Unit) {
    val colors = LocalZemoteColors.current
    var expanded by remember(text) { mutableStateOf(false) }
    val density = LocalDensity.current

    SubcomposeLayout(
        modifier = Modifier
            .clipToBounds()
            .animateContentSize(),
    ) { constraints ->
        val body = subcompose("body") {
            MarkdownText(
                text = text,
                onImageClick = onImageClick,
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
                        .width(with(density) { contentWidth.toDp() })
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
        modifier = Modifier.fillMaxWidth().padding(top = ZemoteSpacing.space2),
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
                    .padding(ZemoteSpacing.space1)
                    .size(ZemoteSpacing.space4)
                    .rotate(if (expanded) 180f else 0f),
            )
        }
    }
}

private val COLLAPSED_HEIGHT = 120.dp

/** 只读附件 pill：32dp 高、全圆角、`bg-surface`（网页 `h-8 rounded-full`）。 */
@Composable
internal fun AttachmentPill(obj: JsonObject, modifier: Modifier = Modifier) {
    val colors = LocalZemoteColors.current
    val name = obj["fileName"].asString() ?: obj["name"].asString() ?: "附件"
    val bytes = obj["bytes"].asLong() ?: obj["sizeBytes"].asLong()
    Row(
        modifier = modifier
            .height(32.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(colors.surface)
            .padding(horizontal = ZemoteSpacing.space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZemoteSpacing.space1_5),
    ) {
        Icon(
            Icons.Default.InsertDriveFile,
            contentDescription = null,
            tint = colors.foregroundSubtle,
            modifier = Modifier.size(ZemoteSpacing.space4),
        )
        Text(
            text = name,
            style = ZemoteType.uiBase.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
            color = colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (bytes != null) {
            Text(
                text = formatBytes(bytes),
                style = ZemoteType.uiSm,
                color = colors.foregroundSubtle,
                maxLines = 1,
            )
        }
    }
}
