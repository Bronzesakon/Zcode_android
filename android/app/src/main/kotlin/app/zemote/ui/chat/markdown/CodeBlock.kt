package app.zemote.ui.chat.markdown

// ---------------------------------------------------------------------------
//  代码块渲染
//
//  版式对齐 Web 端（docs/chat/03 §6、07 §1.7）：整块 12dp 圆角 + 1px 边框 + card 底，
//  顶部一行「文件图标 + 语言标签 + 复制」，正文 12/20 等宽、不自动换行（横向滚动），
//  超过 30 行折叠（UI-CONFIRM C4）。
//
//  取舍：
//  - 复制走 LocalClipboardManager（系统剪贴板），不额外引依赖；复制始终是完整代码。
//  - 折叠只截断显示，不改变着色结果之外的任何内容。
//  - 着色结果由 MarkdownHighlighter 内部按 (code, language, colors) 做有界 LRU；
//    这里再用 remember 挡住同一组合内的重复查表。超长代码由高亮器去色降级。
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
import androidx.compose.material3.HorizontalDivider
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

/** 超过这个行数就折叠（UI-CONFIRM C4：超 30 行折叠）。 */
private const val COLLAPSE_LINE_THRESHOLD = 30

/** 复制成功后的勾选反馈时长，与网页代码块一致（2000ms）。 */
private const val COPY_FEEDBACK_MS = 2_000L

/** 代码行数；末尾换行不算一行。 */
internal fun codeLineCount(code: String): Int {
    if (code.isEmpty()) return 0
    val trimmed = code.trimEnd('\n')
    if (trimmed.isEmpty()) return 0
    return trimmed.count { it == '\n' } + 1
}

/** 折叠时只保留前 [limit] 行（不补省略号，避免复制/选择时混入假内容）。 */
internal fun collapseCode(code: String, limit: Int): String {
    if (limit <= 0) return ""
    val trimmed = code.trimEnd('\n')
    if (trimmed.isEmpty()) return code
    return trimmed.lineSequence().take(limit).joinToString("\n")
}

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

    val lineCount = remember(code) { codeLineCount(code) }
    val collapsible = lineCount > COLLAPSE_LINE_THRESHOLD
    val visibleCode = remember(code, collapsible, expanded) {
        if (collapsible && !expanded) collapseCode(code, COLLAPSE_LINE_THRESHOLD) else code
    }
    val highlighted = remember(visibleCode, language, colors) {
        MarkdownHighlighter.highlight(visibleCode, language, colors)
    }
    val scrollState = rememberScrollState()

    LaunchedEffect(copied) {
        if (copied) {
            delay(COPY_FEEDBACK_MS)
            copied = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(ZemoteRadius.xlShape)
            .background(colors.card)
            .border(1.dp, colors.border, ZemoteRadius.xlShape),
    ) {
        // ---- 顶部：文件图标 + 语言 + 复制（网页 header 无下边框）----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = ZemoteSpacing.space3,
                    end = ZemoteSpacing.space2,
                    top = ZemoteSpacing.space1,
                    bottom = ZemoteSpacing.space1,
                ),
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
                text = language?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "text",
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
                    // 网页图标按钮 rounded-lg = 8dp。
                    .clip(ZemoteRadius.lgShape)
                    .clickable {
                        clipboard.setText(AnnotatedString(code))
                        copied = true
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (copied) LucideIcons.Check else LucideIcons.Copy,
                    contentDescription = if (copied) "已复制" else "复制代码",
                    tint = colors.foreground,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        // ---- 代码正文：12/20 等宽、保留缩进、不换行 + 横向滚动 ----
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
                    end = ZemoteSpacing.space3,
                    bottom = ZemoteSpacing.space3,
                ),
            )
        }

        // ---- 折叠 / 展开（网页没有，移动端按 UI-CONFIRM C4 加的）----
        if (collapsible) {
            HorizontalDivider(color = colors.border)
            Text(
                text = if (expanded) "收起" else "展开全部 $lineCount 行",
                style = ZemoteType.uiSm.copy(color = colors.foregroundSubtle),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(
                        start = ZemoteSpacing.space3,
                        end = ZemoteSpacing.space3,
                        top = ZemoteSpacing.space2,
                        bottom = ZemoteSpacing.space2,
                    ),
            )
        }
    }
}
