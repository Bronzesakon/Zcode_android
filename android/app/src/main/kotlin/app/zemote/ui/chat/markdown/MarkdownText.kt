package app.zemote.ui.chat.markdown

// ---------------------------------------------------------------------------
//  Markdown 渲染入口
//
//  公开 API 只有 MarkdownText（代码块另见 CodeBlock.kt）。渲染策略：
//  - 块级节点各自一个 Composable，块之间用 8dp 间距（ZemoteSpacing.space2）；
//  - 行内节点先拼成 AnnotatedString 再一次性交给 Text，避免每个词一个 Composable；
//  - 链接点击走 LocalUriHandler，图片点击回调给调用方（运行时不联网、不加载图片）；
//  - 解析结果用 remember(text) 缓存，流式刷新时只重解析变化后的文本。
//
//  取舍：不包 SelectionContainer。聊天时间线本身通常已提供长按选择，这里再包一层
//  会和链接点击手势抢事件；文本仍可被外层选择。
// ---------------------------------------------------------------------------

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.zemote.ui.chat.rich.RichBlock
import app.zemote.ui.chat.rich.RichBlockType
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteColors
import app.zemote.ui.theme.ZemoteRadius
import app.zemote.ui.theme.ZemoteSpacing
import app.zemote.ui.theme.ZemoteType

private const val TAG_URL = "zemote-md-url"
private const val TAG_IMAGE = "zemote-md-image"

/**
 * 把一段 Markdown 渲染成 Compose 文本。
 *
 * - 支持：段落、ATX 标题 1–6、无序/有序/任务列表（可嵌套）、引用、分隔线、
 *   围栏代码块、GFM 表格、图片、链接，以及行内粗体/斜体/删除线/代码/链接/转义。
 * - 流式安全：未闭合的围栏与强调符号降级为普通文本，不抛异常。
 * - 不联网：图片只渲染占位卡片，点击通过 [onImageClick] 交给调用方处理。
 *
 * 解析结果按 [text] 缓存，长文本滚动时不会重复分词。
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    onImageClick: (url: String) -> Unit = {},
) {
    val blocks = remember(text) { MarkdownParser.parse(text) }
    MarkdownBlocks(
        blocks = blocks,
        modifier = modifier,
        onImageClick = onImageClick,
        spacing = ZemoteSpacing.space2,
    )
}

// ===========================================================================
//  块级渲染
// ===========================================================================

@Composable
private fun MarkdownBlocks(
    blocks: List<MdBlock>,
    modifier: Modifier = Modifier,
    onImageClick: (String) -> Unit,
    spacing: Dp,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        blocks.forEach { block ->
            MarkdownBlock(block, onImageClick)
        }
    }
}

@Composable
private fun MarkdownBlock(block: MdBlock, onImageClick: (String) -> Unit) {
    val colors = LocalZemoteColors.current
    when (block) {
        is MdBlock.Paragraph -> {
            val math = (block.inlines.singleOrNull() as? MdInline.Text)?.text?.trim()
            if (math != null && math.startsWith("$$") && math.endsWith("$$") && math.length > 4) {
                RichBlock(RichBlockType.MATH_BLOCK, math.substring(2, math.length - 2))
            } else {
                InlineText(
                    inlines = block.inlines,
                    style = ZemoteType.uiBase.copy(color = colors.foreground),
                    onImageClick = onImageClick,
                )
            }
        }

        is MdBlock.Heading -> {
            val base = when (block.level) {
                1 -> ZemoteType.uiXl.copy(fontWeight = FontWeight.Bold)
                2 -> ZemoteType.uiLg.copy(fontWeight = FontWeight.Bold)
                3 -> ZemoteType.uiBase.copy(fontWeight = FontWeight.Bold)
                4 -> ZemoteType.uiBase.copy(fontWeight = FontWeight.SemiBold)
                5 -> ZemoteType.uiBase.copy(fontWeight = FontWeight.Medium)
                else -> ZemoteType.uiSm.copy(fontWeight = FontWeight.Medium)
            }
            val style = if (block.level == 6) {
                base.copy(color = colors.foregroundSubtle)
            } else {
                base.copy(color = colors.foreground)
            }
            InlineText(
                inlines = block.inlines,
                style = style,
                modifier = if (block.level <= 2) {
                    Modifier.padding(top = ZemoteSpacing.space1)
                } else {
                    Modifier
                },
                onImageClick = onImageClick,
            )
        }

        is MdBlock.BulletList -> ListBlock(block, onImageClick)

        is MdBlock.Quote -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .clip(ZemoteRadius.smShape)
                    .background(colors.borderHover),
            )
            MarkdownBlocks(
                blocks = block.blocks,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = ZemoteSpacing.space3),
                onImageClick = onImageClick,
                spacing = ZemoteSpacing.space1,
            )
        }

        is MdBlock.Rule -> HorizontalDivider(
            color = colors.border,
            thickness = 1.dp,
        )

        is MdBlock.Code -> when (block.language?.lowercase()) {
            // 公式与图都走离线 WebView（assets/rich）；其余语言仍用原生代码块。
            "mermaid" -> RichBlock(RichBlockType.MERMAID, block.code)
            "math", "latex", "katex", "tex" -> RichBlock(RichBlockType.MATH_BLOCK, block.code)
            else -> CodeBlock(code = block.code, language = block.language)
        }

        is MdBlock.Table -> TableBlock(block, onImageClick)

        is MdBlock.Image -> ImageCard(block.alt, block.url, onImageClick)
    }
}

@Composable
private fun ListBlock(list: MdBlock.BulletList, onImageClick: (String) -> Unit) {
    val colors = LocalZemoteColors.current
    Column(verticalArrangement = Arrangement.spacedBy(ZemoteSpacing.space1)) {
        list.items.forEachIndexed { index, item ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                if (item.checked != null) {
                    Box(
                        modifier = Modifier
                            .widthIn(min = 24.dp)
                            .padding(top = 3.dp, end = ZemoteSpacing.space1),
                    ) {
                        TaskMarker(item.checked)
                    }
                } else {
                    Text(
                        text = if (list.ordered) "${list.start + index}." else "•",
                        style = ZemoteType.uiBase.copy(
                            color = colors.foregroundSubtle,
                            fontWeight = FontWeight.Medium,
                        ),
                        modifier = Modifier
                            .widthIn(min = 24.dp)
                            .padding(top = 1.dp, end = ZemoteSpacing.space1),
                    )
                }
                MarkdownBlocks(
                    blocks = item.blocks,
                    modifier = Modifier.weight(1f),
                    onImageClick = onImageClick,
                    spacing = ZemoteSpacing.space1,
                )
            }
        }
    }
}

/** 任务列表的方框。手绘比 M3 Checkbox 轻，也不会带 48dp 的最小点击区。 */
@Composable
private fun TaskMarker(checked: Boolean) {
    val colors = LocalZemoteColors.current
    Box(
        modifier = Modifier
            .size(14.dp)
            .clip(ZemoteRadius.xsShape)
            .background(if (checked) colors.iconBlue else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (checked) colors.iconBlue else colors.borderHover,
                shape = ZemoteRadius.xsShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Text(
                text = "✓",
                style = ZemoteType.ui2xs.copy(
                    color = colors.foregroundInverse,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
    }
}

/**
 * 图片占位卡片。
 *
 * 刻意不加载远程图片：本项目运行时不联网、也没有图片加载依赖。
 * 点击把 URL 交给调用方（例如打开系统浏览器或内置查看器）。
 */
@Composable
private fun ImageCard(alt: String, url: String, onImageClick: (String) -> Unit) {
    val colors = LocalZemoteColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZemoteRadius.lgShape)
            .background(colors.card)
            .border(1.dp, colors.border, ZemoteRadius.lgShape)
            .clickable { onImageClick(url) }
            .padding(ZemoteSpacing.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(ZemoteRadius.mdShape)
                .background(colors.markdownInlineCode),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "IMG",
                style = ZemoteType.ui2xs.copy(color = colors.foregroundSubtle),
            )
        }
        Spacer(modifier = Modifier.width(ZemoteSpacing.space3))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = alt.ifBlank { "图片" },
                style = ZemoteType.uiBase.copy(color = colors.foreground),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = url,
                style = ZemoteType.uiSm.copy(color = colors.foregroundSubtle),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ===========================================================================
//  表格
// ===========================================================================

@Composable
private fun TableBlock(table: MdBlock.Table, onImageClick: (String) -> Unit) {
    val colors = LocalZemoteColors.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val cellStyle = ZemoteType.uiSm.copy(color = colors.foreground)
    val headerStyle = cellStyle.copy(fontWeight = FontWeight.SemiBold)

    val annotatedRows = remember(table, colors) {
        (listOf(table.header) + table.rows).map { row ->
            row.map { cell -> buildInlineAnnotated(cell, colors) }
        }
    }

    // 用 TextMeasurer 量出每列最大宽度，保证跨行对齐；宽度夹在 48–280dp，
    // 超长单元格在列内换行，整张表横向滚动。
    val widths = remember(annotatedRows, cellStyle, density) {
        val columns = annotatedRows.maxOfOrNull { it.size } ?: 0
        List(columns) { column ->
            val maxPx = annotatedRows
                .mapNotNull { it.getOrNull(column) }
                .maxOfOrNull {
                    measurer.measure(it, cellStyle, softWrap = false).size.width
                } ?: 0
            with(density) {
                (maxPx.toDp() + ZemoteSpacing.space4).coerceIn(48.dp, 280.dp)
            }
        }
    }

    val scrollState = rememberScrollState()
    val tableWidth = widths.fold(0.dp) { acc, width -> acc + width }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZemoteRadius.mdShape)
            .border(1.dp, colors.border, ZemoteRadius.mdShape),
    ) {
        Column(modifier = Modifier.horizontalScroll(scrollState)) {
            annotatedRows.forEachIndexed { rowIndex, row ->
                if (rowIndex > 0) {
                    // 横向滚动里 fillMaxWidth 拿不到有限约束，必须显式给表格总宽
                    HorizontalDivider(
                        modifier = Modifier.width(tableWidth),
                        color = colors.border,
                    )
                }
                Row(
                    modifier = Modifier.background(
                        if (rowIndex == 0) colors.backgroundAlt else Color.Transparent,
                    ),
                ) {
                    widths.forEachIndexed { column, width ->
                        val cell = row.getOrNull(column) ?: AnnotatedString("")
                        val align = table.align.getOrNull(column) ?: MdAlign.None
                        AnnotatedText(
                            annotated = cell,
                            style = if (rowIndex == 0) headerStyle else cellStyle,
                            modifier = Modifier
                                .width(width)
                                .padding(
                                    horizontal = ZemoteSpacing.space2,
                                    vertical = ZemoteSpacing.space1_5,
                                )
                                .fillMaxWidth(),
                            textAlign = when (align) {
                                MdAlign.Center -> TextAlign.Center
                                MdAlign.End -> TextAlign.End
                                MdAlign.Start, MdAlign.None -> TextAlign.Start
                            },
                            onImageClick = onImageClick,
                        )
                    }
                }
            }
        }
    }
}

// ===========================================================================
//  行内渲染
// ===========================================================================

@Composable
private fun InlineText(
    inlines: List<MdInline>,
    style: TextStyle,
    modifier: Modifier = Modifier,
    onImageClick: (String) -> Unit = {},
) {
    val colors = LocalZemoteColors.current
    val annotated = remember(inlines, colors) { buildInlineAnnotated(inlines, colors) }
    AnnotatedText(
        annotated = annotated,
        style = style,
        modifier = modifier,
        onImageClick = onImageClick,
    )
}

/**
 * 渲染已拼好的 AnnotatedString，并处理其中的链接/图片点击。
 *
 * 用 TextLayoutResult + detectTapGestures 而不是已废弃的 ClickableText：
 * 前者在新版 Compose 里不会告警，也不需要把文本拆成多个 Composable。
 */
@Composable
private fun AnnotatedText(
    annotated: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    onImageClick: (String) -> Unit = {},
) {
    val uriHandler = LocalUriHandler.current
    val currentImageClick by rememberUpdatedState(onImageClick)
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    // 只有真的含链接/图片时才装点击手势：否则普通段落会消费掉 tap，
    // 让外层（消息气泡、列表项）的点击事件收不到。
    val hasAnnotations = remember(annotated) {
        annotated.getStringAnnotations(TAG_URL, 0, annotated.length).isNotEmpty() ||
            annotated.getStringAnnotations(TAG_IMAGE, 0, annotated.length).isNotEmpty()
    }
    val clickableModifier = if (hasAnnotations) {
        modifier.pointerInput(annotated) {
            detectTapGestures { position ->
                val layout = layoutResult ?: return@detectTapGestures
                val offset = layout.getOffsetForPosition(position)
                annotated.getStringAnnotations(TAG_URL, offset, offset)
                    .firstOrNull()
                    ?.let { annotation ->
                        // 没有浏览器/无 handler 时静默失败，不能让聊天页崩
                        runCatching { uriHandler.openUri(annotation.item) }
                    }
                annotated.getStringAnnotations(TAG_IMAGE, offset, offset)
                    .firstOrNull()
                    ?.let { annotation -> currentImageClick(annotation.item) }
            }
        }
    } else {
        modifier
    }

    Text(
        text = annotated,
        style = style,
        textAlign = textAlign,
        modifier = clickableModifier,
        onTextLayout = { layoutResult = it },
    )
}

/** 行内节点 → AnnotatedString。纯函数，调用方按 inlines/主题缓存。 */
private fun buildInlineAnnotated(
    inlines: List<MdInline>,
    colors: ZemoteColors,
): AnnotatedString = buildAnnotatedString {
    fun appendAll(nodes: List<MdInline>) {
        for (node in nodes) {
            when (node) {
                is MdInline.Text -> append(node.text)

                is MdInline.Code -> withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = colors.markdownInlineCode,
                        color = colors.foreground,
                    ),
                ) {
                    append(node.text)
                }

                is MdInline.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    appendAll(node.children)
                }

                is MdInline.Emph -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    appendAll(node.children)
                }

                is MdInline.Strike -> withStyle(
                    SpanStyle(textDecoration = TextDecoration.LineThrough),
                ) {
                    appendAll(node.children)
                }

                is MdInline.Link -> {
                    pushStringAnnotation(TAG_URL, node.href)
                    withStyle(
                        SpanStyle(
                            color = colors.iconBlue,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ) {
                        appendAll(node.children)
                    }
                    pop()
                }

                is MdInline.Image -> {
                    pushStringAnnotation(TAG_IMAGE, node.url)
                    withStyle(SpanStyle(color = colors.iconBlue)) {
                        append(node.alt.ifBlank { node.url })
                    }
                    pop()
                }
            }
        }
    }

    appendAll(inlines)
}
