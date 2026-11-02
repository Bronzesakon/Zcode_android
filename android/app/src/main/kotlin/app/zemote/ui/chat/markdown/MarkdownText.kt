package app.zemote.ui.chat.markdown

// ---------------------------------------------------------------------------
//  Markdown 渲染入口
//
//  公开 API 只有 MarkdownText（代码块另见 CodeBlock.kt）。渲染策略：
//  - 块级节点各自一个 Composable；块间距按网页的 margin 规则近似（见 markdownBlockGap）；
//  - 行内节点先拼成 AnnotatedString 再一次性交给 Text，避免每个词一个 Composable；
//  - 链接点击走 LocalUriHandler，图片点击回调给调用方（运行时不依赖图片库）；
//  - 解析结果用 remember(text) 缓存，流式刷新时只重解析变化后的文本。
//
//  版式口径（docs/chat/03 §1.3、07 与 UI-CONFIRM §1.1/§2.3）：
//  - 正文 14sp / 1.75 行高（网页 Markdown 根 `leading-[1.75]`，14px → 24.5px）；
//  - 标题 18/16/14 阶梯，h1–h4 semibold、h5 medium、h6 normal，行高同样按 1.75 折算；
//  - 列表 ul 缩进 20dp、项间 6dp，标记色 foregroundSubtlest；
//  - 引用 2dp 左竖线 + 12dp 左内边距，正文 foregroundSubtle；
//  - 表格 12dp 圆角、1px 网格、表头加粗；
//  - 行内代码 12sp 等宽 + markdownInlineCode 50% 底。
//
//  取舍：不包 SelectionContainer。聊天时间线本身通常已提供长按选择，这里再包一层
//  会和链接点击手势抢事件；文本仍可被外层选择。
// ---------------------------------------------------------------------------

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.ui.unit.sp
import app.zemote.ui.chat.media.ImageThumbGrid
import app.zemote.ui.chat.media.RemoteImage
import app.zemote.ui.chat.media.fetchImageBytes
import app.zemote.ui.chat.rich.RichBlock
import app.zemote.ui.chat.rich.RichBlockType
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteColors
import app.zemote.ui.theme.ZemoteRadius
import app.zemote.ui.theme.ZemoteSpacing
import app.zemote.ui.theme.ZemoteType

private const val TAG_URL = "zemote-md-url"
private const val TAG_IMAGE = "zemote-md-image"

/** 无序列表缩进：网页 `pl-5` = 20dp。 */
private val BulletIndent: Dp = ZemoteSpacing.space5

/** 有序列表给「10.」这类两位序号留出余量。 */
private val OrderedIndent: Dp = ZemoteSpacing.space6

/**
 * Markdown 根行高。
 *
 * 网页 Markdown 根是 `text-ui-base leading-[1.75]`（14px → 24.5px），块内文本都继承它，
 * 所以按各自字号 × 1.75 折算；代码块固定 20px、UI/工具行仍是 1.5，都不走这里。
 */
private fun TextStyle.withMarkdownLeading(): TextStyle =
    copy(lineHeight = (fontSize.value * 1.75f).sp)

/**
 * 把一段 Markdown 渲染成 Compose 文本。
 *
 * - 支持：段落、ATX 标题 1–6、无序/有序/任务列表（可嵌套）、引用、分隔线、
 *   围栏代码块、GFM 表格、图片、链接，以及行内粗体/斜体/删除线/代码/链接/转义。
 * - 流式安全：未闭合的围栏与强调符号降级为普通文本，不抛异常。
 * - 图片只做缩略图 + 点击回调，实际取图与全屏预览由调用方（media/Images.kt）处理。
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
    )
}

// ===========================================================================
//  块级渲染
// ===========================================================================

/**
 * 相邻两块之间的距离。
 *
 * 网页用 `space-y-4`（16px）加各块自带 margin，靠 CSS margin collapse 取「较大者」。
 * Compose 没有 margin collapse，这里显式取 max(上一块下边距, 下一块上边距)：
 * 段落 16、标题上 24 / 下 16、列表 12、引用 / 代码 / 表格 / 图片 16，常见组合与网页一致。
 * 引用内部按网页 `[&_p]:my-0 [&_p+p]:mt-2` 收紧成 8。
 */
internal fun markdownBlockGap(previous: MdBlock, next: MdBlock, inQuote: Boolean): Dp {
    val prevBottom = when (previous) {
        is MdBlock.Paragraph -> if (inQuote) ZemoteSpacing.space2 else ZemoteSpacing.space4
        is MdBlock.BulletList -> ZemoteSpacing.space3
        // 标题 / 引用 / 分隔线 / 代码 / 表格 / 图片都是 my-4 = 16dp。
        else -> ZemoteSpacing.space4
    }
    val nextTop = when (next) {
        is MdBlock.Paragraph -> if (inQuote) ZemoteSpacing.space2 else ZemoteSpacing.space4
        is MdBlock.BulletList -> ZemoteSpacing.space3
        is MdBlock.Heading -> ZemoteSpacing.space6
        else -> ZemoteSpacing.space4
    }
    return if (prevBottom >= nextTop) prevBottom else nextTop
}

@Composable
private fun MarkdownBlocks(
    blocks: List<MdBlock>,
    modifier: Modifier = Modifier,
    onImageClick: (String) -> Unit,
    inQuote: Boolean = false,
) {
    Column(modifier = modifier) {
        var index = 0
        while (index < blocks.size) {
            val block = blocks[index]

            // 连续图片合成网格：单图是缩略图卡片，多图是缩略图网格（网页多图走 grid）。
            if (block is MdBlock.Image) {
                var end = index
                while (end < blocks.size && blocks[end] is MdBlock.Image) end++
                val images = blocks.subList(index, end).map { it as MdBlock.Image }
                val gap = if (index == 0) 0.dp else markdownBlockGap(blocks[index - 1], block, inQuote)
                Box(modifier = Modifier.padding(top = gap)) {
                    if (images.size == 1) {
                        SingleImage(images[0], onImageClick)
                    } else {
                        ImageThumbGrid(
                            keys = images.map { it.url },
                            loader = { url -> fetchImageBytes(url) },
                            onClick = onImageClick,
                            thumbSize = 96.dp,
                            maxPerRow = 3,
                        )
                    }
                }
                index = end
                continue
            }

            val gap = if (index == 0) 0.dp else markdownBlockGap(blocks[index - 1], block, inQuote)
            Box(modifier = Modifier.padding(top = gap)) {
                MarkdownBlock(block, onImageClick, inQuote)
            }
            index++
        }
    }
}

@Composable
private fun MarkdownBlock(block: MdBlock, onImageClick: (String) -> Unit, inQuote: Boolean) {
    val colors = LocalZemoteColors.current
    when (block) {
        is MdBlock.Paragraph -> {
            val math = (block.inlines.singleOrNull() as? MdInline.Text)?.text?.trim()
            if (math != null && math.startsWith("$$") && math.endsWith("$$") && math.length > 4) {
                RichBlock(RichBlockType.MATH_BLOCK, math.substring(2, math.length - 2))
            } else {
                InlineText(
                    inlines = block.inlines,
                    // 正文 14sp / 1.75 行高（网页 leading-[1.75]）。
                    style = ZemoteType.uiBase.copy(
                        color = if (inQuote) colors.foregroundSubtle else colors.foreground,
                    ).withMarkdownLeading(),
                    onImageClick = onImageClick,
                )
            }
        }

        is MdBlock.Heading -> {
            // 网页标题阶梯：h1 18 / h2 16 / h3–h4 14 semibold、h5 medium、h6 normal。
            val base = when (block.level) {
                1 -> ZemoteType.uiXl.copy(fontWeight = FontWeight.SemiBold)
                2 -> ZemoteType.uiLg.copy(fontWeight = FontWeight.SemiBold)
                3 -> ZemoteType.uiBase.copy(fontWeight = FontWeight.SemiBold)
                4 -> ZemoteType.uiBase.copy(fontWeight = FontWeight.SemiBold)
                5 -> ZemoteType.uiBase.copy(fontWeight = FontWeight.Medium)
                else -> ZemoteType.uiBase
            }
            InlineText(
                inlines = block.inlines,
                style = base.copy(color = colors.foreground).withMarkdownLeading(),
                onImageClick = onImageClick,
            )
        }

        is MdBlock.BulletList -> ListBlock(block, onImageClick, inQuote)

        is MdBlock.Quote -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            // 网页 border-l-2：2px 竖线，不带圆角。
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(colors.border),
            )
            MarkdownBlocks(
                blocks = block.blocks,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = ZemoteSpacing.space3),
                onImageClick = onImageClick,
                inQuote = true,
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

        // 图片在 MarkdownBlocks 里成组处理；这里保留单图分支以保持 when 穷尽。
        is MdBlock.Image -> SingleImage(block, onImageClick)
    }
}

@Composable
private fun ListBlock(
    list: MdBlock.BulletList,
    onImageClick: (String) -> Unit,
    inQuote: Boolean,
) {
    val colors = LocalZemoteColors.current
    val indent = if (list.ordered) OrderedIndent else BulletIndent
    Column(
        // 网页 space-y-1.5：项间 6dp。
        verticalArrangement = Arrangement.spacedBy(ZemoteSpacing.space1_5),
    ) {
        list.items.forEachIndexed { index, item ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                if (item.checked != null) {
                    Box(
                        modifier = Modifier
                            .width(indent)
                            .padding(top = 3.dp, end = ZemoteSpacing.space1),
                    ) {
                        TaskMarker(item.checked)
                    }
                } else {
                    Text(
                        text = if (list.ordered) "${list.start + index}." else "•",
                        style = ZemoteType.uiBase.copy(
                            // 网页 marker:text-foreground-subtlest。
                            color = colors.foregroundSubtlest,
                        ).withMarkdownLeading(),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier
                            .width(indent)
                            .padding(end = ZemoteSpacing.space1),
                    )
                }
                MarkdownBlocks(
                    blocks = item.blocks,
                    // 网页 li pl-1：内容再左移 4dp。
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = ZemoteSpacing.space1),
                    onImageClick = onImageClick,
                    inQuote = inQuote,
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

// ===========================================================================
//  图片
// ===========================================================================

/**
 * 单张图片的缩略图卡片。
 *
 * 网页单图最大 360px 高、容器 50% 宽；手机上取 240×150 的固定缩略图，
 * 点击交给调用方打开全屏预览（手势见 media/ImageViewerDialog）。
 */
@Composable
private fun SingleImage(image: MdBlock.Image, onImageClick: (String) -> Unit) {
    val colors = LocalZemoteColors.current
    Column {
        RemoteImage(
            key = image.url,
            loader = { url -> fetchImageBytes(url) },
            modifier = Modifier
                .width(240.dp)
                .height(150.dp)
                .clip(ZemoteRadius.xlShape)
                .border(1.dp, colors.border, ZemoteRadius.xlShape)
                .background(colors.backgroundAlt),
            contentScale = ContentScale.Crop,
            onClick = { onImageClick(image.url) },
        )
        if (image.alt.isNotBlank()) {
            Text(
                text = image.alt,
                style = ZemoteType.uiSm.copy(color = colors.foregroundSubtle),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = ZemoteSpacing.space1),
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
    // 表格文本同样继承 Markdown 根的 1.75 行高。
    val cellStyle = ZemoteType.uiBase.copy(color = colors.foreground).withMarkdownLeading()
    // 表头加粗（UI-SPEC §2.3）。
    val headerStyle = cellStyle.copy(fontWeight = FontWeight.SemiBold)

    // 网页单元格 px-3 py-2。
    val cellPaddingH = ZemoteSpacing.space3
    val cellPaddingV = ZemoteSpacing.space2

    val annotatedRows = remember(table, colors) {
        (listOf(table.header) + table.rows).map { row ->
            row.map { cell -> buildInlineAnnotated(cell, colors) }
        }
    }

    // 量出每列内容的自然宽度后加左右内边距；网页 min-w-16 / max-w-md 即 64–448dp。
    val measuredWidths = remember(annotatedRows, cellStyle, density) {
        val columns = annotatedRows.maxOfOrNull { it.size } ?: 0
        List(columns) { column ->
            val maxPx = annotatedRows
                .mapNotNull { it.getOrNull(column) }
                .maxOfOrNull {
                    measurer.measure(it, cellStyle, softWrap = false).size.width
                } ?: 0
            with(density) {
                (maxPx.toDp() + cellPaddingH * 2f).coerceIn(64.dp, 448.dp)
            }
        }
    }

    val scrollState = rememberScrollState()
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZemoteRadius.xlShape)
            .border(1.dp, colors.border, ZemoteRadius.xlShape),
    ) {
        val total = measuredWidths.fold(0.dp) { acc, width -> acc + width }
        // 窄表把剩余宽度均摊到各列，避免右侧留白；宽表保持自然宽度并横向滚动。
        val widths = if (measuredWidths.isNotEmpty() &&
            maxWidth.value.isFinite() &&
            total < maxWidth
        ) {
            val extra = (maxWidth - total) / measuredWidths.size.toFloat()
            measuredWidths.map { it + extra }
        } else {
            measuredWidths
        }

        Column(modifier = Modifier.horizontalScroll(scrollState)) {
            annotatedRows.forEachIndexed { rowIndex, row ->
                val isHeader = rowIndex == 0
                val lastRow = rowIndex == annotatedRows.lastIndex
                Row(
                    modifier = Modifier.background(
                        if (isHeader) colors.backgroundAlt else Color.Transparent,
                    ),
                ) {
                    widths.forEachIndexed { column, width ->
                        val cell = row.getOrNull(column) ?: AnnotatedString("")
                        val lastColumn = column == widths.lastIndex
                        val align = table.align.getOrNull(column) ?: MdAlign.None
                        AnnotatedText(
                            annotated = cell,
                            style = if (isHeader) headerStyle else cellStyle,
                            modifier = Modifier
                                .width(width)
                                .drawBehind {
                                    // 1px 网格：列右侧一条、行下方一条，最后一行/列不画，避免和外框重叠。
                                    val stroke = 1.dp.toPx()
                                    if (!lastColumn) {
                                        drawLine(
                                            color = colors.border,
                                            start = Offset(size.width - stroke / 2, 0f),
                                            end = Offset(size.width - stroke / 2, size.height),
                                            strokeWidth = stroke,
                                        )
                                    }
                                    if (!lastRow) {
                                        drawLine(
                                            color = colors.border,
                                            start = Offset(0f, size.height - stroke / 2),
                                            end = Offset(size.width, size.height - stroke / 2),
                                            strokeWidth = stroke,
                                        )
                                    }
                                }
                                .padding(
                                    horizontal = cellPaddingH,
                                    vertical = cellPaddingV,
                                ),
                            // 网页表头恒左对齐，单元格按列对齐。
                            textAlign = if (isHeader) {
                                TextAlign.Start
                            } else {
                                when (align) {
                                    MdAlign.Center -> TextAlign.Center
                                    MdAlign.End -> TextAlign.End
                                    MdAlign.Start, MdAlign.None -> TextAlign.Start
                                }
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
                    // 网页 rounded-md + bg-markdown-inline-code/50 + 12px 等宽。
                    // Compose 的 SpanStyle 背景不支持圆角，只能保留 50% 底色。
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        background = colors.markdownInlineCode.copy(alpha = 0.5f),
                        color = colors.foreground,
                    ),
                ) {
                    append(node.text)
                }

                is MdInline.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
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
                            fontWeight = FontWeight.Medium,
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
