package app.zemote.ui.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析器的最小回归检查：块级结构、行内样式、流式降级。
 * 渲染层（Compose）不在单测覆盖范围内，这里只锁纯解析行为。
 */
class MarkdownParserTest {

    @Test
    fun `块级结构与标题级别`() {
        val blocks = MarkdownParser.parse(
            "# 一级\n\n正文\n\n---\n\n> 引用\n\n```kotlin\nval a = 1\n```",
        )
        assertTrue(blocks[0] is MdBlock.Heading)
        assertEquals(1, (blocks[0] as MdBlock.Heading).level)
        assertTrue(blocks[1] is MdBlock.Paragraph)
        assertTrue(blocks[2] is MdBlock.Rule)
        assertTrue(blocks[3] is MdBlock.Quote)
        val code = blocks[4] as MdBlock.Code
        assertEquals("val a = 1", code.code)
        assertEquals("kotlin", code.language)
    }

    @Test
    fun `列表任务与一层嵌套`() {
        val list = MarkdownParser.parse("- a\n- [x] done\n  - nested\n- b")
            .single() as MdBlock.BulletList
        assertEquals(3, list.items.size)
        assertEquals(true, list.items[1].checked)
        assertTrue(list.items[1].blocks.any { it is MdBlock.BulletList })
    }

    @Test
    fun `有序列表保留起始序号`() {
        val list = MarkdownParser.parse("3. three\n4. four").single() as MdBlock.BulletList
        assertEquals(true, list.ordered)
        assertEquals(3, list.start)
    }

    @Test
    fun `GFM 表格与对齐`() {
        val table = MarkdownParser.parse("| a | b |\n|:--|--:|\n| 1 | 2 |")
            .single() as MdBlock.Table
        assertEquals(2, table.header.size)
        assertEquals(1, table.rows.size)
        assertEquals(MdAlign.Start, table.align[0])
        assertEquals(MdAlign.End, table.align[1])
    }

    @Test
    fun `单列表格`() {
        val table = MarkdownParser.parse("| a |\n| --- |\n| 1 |")
            .single() as MdBlock.Table
        assertEquals(1, table.header.size)
        assertEquals(1, table.rows.size)
    }

    @Test
    fun `行内样式与转义`() {
        val paragraph = MarkdownParser.parse(
            "**b** *i* ~~s~~ `c` [l](https://x.y) \\*lit\\*",
        ).single() as MdBlock.Paragraph
        val nodes = paragraph.inlines
        val styled = nodes.filterNot { it is MdInline.Text }
        assertTrue(styled[0] is MdInline.Strong)
        assertTrue(styled[1] is MdInline.Emph)
        assertTrue(styled[2] is MdInline.Strike)
        assertTrue(styled[3] is MdInline.Code)
        assertTrue(styled[4] is MdInline.Link)
        assertEquals("https://x.y", (styled[4] as MdInline.Link).href)
        // 链接后的空格与转义文本会合并成一个 Text 节点
        assertEquals(" *lit*", (nodes.last() as MdInline.Text).text)
    }

    @Test
    fun `未闭合围栏降级为普通文本`() {
        val blocks = MarkdownParser.parse("```kotlin\nval a = 1")
        assertTrue(blocks.single() is MdBlock.Paragraph)
    }

    @Test
    fun `未闭合强调保留字面量`() {
        val paragraph = MarkdownParser.parse("a **b").single() as MdBlock.Paragraph
        assertEquals("a **b", (paragraph.inlines.single() as MdInline.Text).text)
    }

    @Test
    fun `独占一行的图片提升为块级图片`() {
        val image = MarkdownParser.parse("![alt](https://x/y.png)").single() as MdBlock.Image
        assertEquals("alt", image.alt)
        assertEquals("https://x/y.png", image.url)
    }
}
