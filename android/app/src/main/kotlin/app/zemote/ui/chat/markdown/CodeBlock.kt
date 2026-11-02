package app.zemote.ui.chat.markdown

// ---------------------------------------------------------------------------
//  代码块渲染
//
//  版式对齐 Web 端：整块一个浅色底、顶部一行「语言 + 复制」、正文等宽字体、
//  不自动换行（横向滚动），超过 30 行折叠。
//
//  取舍：
//  - 复制走 LocalClipboardManager（系统剪贴板），不额外引依赖。
//  - 折叠只截断显示，复制永远复制完整代码。
//  - 着色结果用 remember(code, language) 缓存；流式更新时只有代码变化才重新分词。
//  - 不用 WebView / 不联网。
// ---------------------------------------------------------------------------

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zemote.ui.chat.timeline.LucideIcons
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteRadius
import app.zemote.ui.theme.ZemoteSpacing
import app.zemote.ui.theme.ZemoteType
import kotlinx.coroutines.delay

/** 超过这个行数就折叠，与 Web 端代码块的行数阈值保持一致。 */
private const val COLLAPSE_LINE_THRESHOLD = 30

/**
 * 代码块。
 *
 * [language] 为空或未识别时按纯文本渲染，但仍保留复制与横向滚动。
 * 传入 `diff` / `patch` 时按增删行着色（见 [MarkdownHighlighter]）。
 */
@Composable
fun CodeBlock(
    code: String,
    language: String?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalZemoteColors.current
    val clipboard = LocalClipboardManager.current

    var expanded by remember(code) { mutableStateOf(false) }
    var copied by remember(code) { mutableStateOf(false) }

    val lineCount = remember(code) {
        if (code.isEmpty()) 0 else code.trimEnd('\n').count { it == '\n' } + 1
    }
    val collapsed = lineCount > COLLAPSE_LINE_THRESHOLD && !expanded
    val visibleCode = remember(code, collapsed) {
        if (collapsed) {
            code.lineSequence().take(COLLAPSE_LINE_THRESHOLD).joinToString("\n")
        } else {
            code
        }
    }
    val highlighted = remember(visibleCode, language, colors) {
        MarkdownHighlighter.highlight(visibleCode, language, colors)
    }
    val scrollState = rememberScrollState()

    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(ZemoteRadius.lgShape)
            .background(colors.card)
            .border(1.dp, colors.border, ZemoteRadius.lgShape),
    ) {
        // ---- 顶部：文件图标 + 语言 + 复制（网页 header 无下边框）----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = ZemoteSpacing.space3, end = ZemoteSpacing.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = LucideIcons.File,
                contentDescription = null,
                tint = colors.foreground,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(ZemoteSpacing.space2))
            Text(
                text = (language?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "text"),
                style = ZemoteType.uiBase.copy(
                    fontFamily = FontFamily.Monospace,
                    color = colors.foreground,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(ZemoteRadius.smShape)
                    .clickable {
                        clipboard.setText(AnnotatedString(code))
                        copied = true
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (copied) LucideIcons.Check else LucideIcons.Copy,
                    contentDescription = "复制代码",
                    tint = colors.foreground,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        // ---- 代码正文：12/20 等宽、不换行 + 横向滚动 ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState),
        ) {
            Text(
                text = highlighted,
                style = ZemoteType.mono.copy(
                    color = colors.foreground,
                    fontSize = 12.sp,
                    lineHeight = 20.sp,
                ),
                softWrap = false,
                modifier = Modifier.padding(
                    start = ZemoteSpacing.space3,
                    end = ZemoteSpacing.space2,
                    bottom = ZemoteSpacing.space3,
                ),
            )
        }

        // ---- 折叠 / 展开（移动端保留的可用性增强）----
        if (lineCount > COLLAPSE_LINE_THRESHOLD) {
            Text(
                text = if (expanded) "收起" else "展开全部 $lineCount 行",
                style = ZemoteType.uiSm.copy(color = colors.foregroundSubtle),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(
                        start = ZemoteSpacing.space3,
                        end = ZemoteSpacing.space3,
                        bottom = ZemoteSpacing.space2,
                    ),
            )
        }
    }
}
