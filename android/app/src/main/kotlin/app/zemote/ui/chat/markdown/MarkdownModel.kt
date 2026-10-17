package app.zemote.ui.chat.markdown

// ---------------------------------------------------------------------------
//  Markdown 中间表示（AST）
//
//  只覆盖聊天消息里真正会出现的语法子集：标题、段落、列表、引用、分隔线、
//  围栏代码块、GFM 表格、图片、链接，以及行内强调/删除线/行内代码。
//  公式与 mermaid 由别的模块处理，这里刻意不建对应节点。
//
//  全部 internal：渲染器只通过 MarkdownText / CodeBlock 两个公开入口暴露，
//  调用方不需要知道 AST 的存在，后续换解析实现也不会破坏调用方。
// ---------------------------------------------------------------------------

/** 块级节点。 */
internal sealed interface MdBlock {
    data class Paragraph(val inlines: List<MdInline>) : MdBlock

    data class Heading(val level: Int, val inlines: List<MdInline>) : MdBlock

    /** 无序（[ordered] = false）或有序列表；[start] 是有序列表的起始序号。 */
    data class BulletList(
        val items: List<MdListItem>,
        val ordered: Boolean,
        val start: Int,
    ) : MdBlock

    data class Quote(val blocks: List<MdBlock>) : MdBlock

    data object Rule : MdBlock

    data class Code(val code: String, val language: String?) : MdBlock

    data class Table(
        val header: List<List<MdInline>>,
        val rows: List<List<List<MdInline>>>,
        val align: List<MdAlign>,
    ) : MdBlock

    /** 独占一行的图片。行内出现的图片降级成 [MdInline.Image]。 */
    data class Image(val alt: String, val url: String) : MdBlock
}

/** 列表项：[checked] 非空表示 GFM 任务列表。 */
internal data class MdListItem(
    val blocks: List<MdBlock>,
    val checked: Boolean? = null,
)

internal enum class MdAlign { Start, Center, End, None }

/** 行内节点。 */
internal sealed interface MdInline {
    data class Text(val text: String) : MdInline

    data class Strong(val children: List<MdInline>) : MdInline

    data class Emph(val children: List<MdInline>) : MdInline

    data class Strike(val children: List<MdInline>) : MdInline

    data class Code(val text: String) : MdInline

    data class Link(val href: String, val children: List<MdInline>) : MdInline

    data class Image(val alt: String, val url: String) : MdInline
}
