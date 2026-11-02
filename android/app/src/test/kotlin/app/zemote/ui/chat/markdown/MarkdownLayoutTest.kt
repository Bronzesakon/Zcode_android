package app.zemote.ui.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 渲染层纯逻辑的回归检查：块间距（网页 margin collapse 的近似）与代码块折叠。
 * Compose 布局本身不在单测覆盖范围内。
 */
class MarkdownLayoutTest {

    @Test
    fun `块间距按网页 margin 取较大者`() {
        val paragraph = MdBlock.Paragraph(emptyList())
        val heading = MdBlock.Heading(2, emptyList())
        val list = MdBlock.BulletList(items = emptyList(), ordered = false, start = 1)
        val code = MdBlock.Code("", "kotlin")

        // 段落 16；标题上 24 / 下 16；列表 12；代码 16。
        assertEquals(16f, markdownBlockGap(paragraph, paragraph, inQuote = false).value, 0.01f)
        assertEquals(24f, markdownBlockGap(paragraph, heading, inQuote = false).value, 0.01f)
        assertEquals(16f, markdownBlockGap(heading, paragraph, inQuote = false).value, 0.01f)
        assertEquals(12f, markdownBlockGap(list, list, inQuote = false).value, 0.01f)
        assertEquals(16f, markdownBlockGap(paragraph, code, inQuote = false).value, 0.01f)
        // 引用内段落按网页 p+p 的 8dp 收紧。
        assertEquals(8f, markdownBlockGap(paragraph, paragraph, inQuote = true).value, 0.01f)
    }

    @Test
    fun `代码块超 30 行折叠`() {
        val code = (1..40).joinToString("\n") { "line$it" }
        assertEquals(40, codeLineCount(code))
        assertEquals(30, collapseCode(code, 30).lines().size)
        assertEquals("line1\nline2", collapseCode("line1\nline2\nline3", 2))
        // 行数不受末尾换行影响。
        assertEquals(3, codeLineCount("a\nb\nc\n"))
        assertEquals(0, codeLineCount(""))
    }
}
