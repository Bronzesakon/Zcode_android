package app.zemote.ui.chat.markdown

import app.zemote.ui.chat.media.calculateSampleSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 渲染性能相关纯逻辑的回归检查：
 *  - Markdown 解析缓存（命中复用同一份 AST、容量有界、超长文本不入缓存）；
 *  - 流式采样器（窗口内采样、保留最后一帧、换消息丢弃旧文本、非流式立即放行）；
 *  - 代码高亮超长降级阈值；
 *  - 图片降采样的 inSampleSize 计算。
 *
 * Compose 组合/绘制本身不在单测覆盖范围内。
 */
class MarkdownPerfTest {

    // -----------------------------------------------------------------------
    //  解析缓存
    // -----------------------------------------------------------------------

    @Test
    fun `解析缓存命中复用同一份 AST`() {
        MarkdownParseCache.clear()
        val text = "# 标题\n\n正文 **加粗** 与 `code`\n\n| a | b |\n| --- | --- |\n| 1 | 2 |"

        val first = MarkdownParseCache.blocks(text)
        val second = MarkdownParseCache.blocks(text)

        assertSame(first, second)
        assertTrue(first.isNotEmpty())
        assertEquals(1, MarkdownParseCache.hitCount())
        // text 不同则各自解析，不会串内容。
        assertNotSame(first, MarkdownParseCache.blocks("另一条消息"))
    }

    @Test
    fun `解析缓存容量有界`() {
        MarkdownParseCache.clear()
        repeat(MarkdownParseCache.MAX_ENTRIES + 8) { index ->
            MarkdownParseCache.blocks("消息 $index\n\n正文 $index")
        }
        assertEquals(MarkdownParseCache.MAX_ENTRIES, MarkdownParseCache.size())
    }

    @Test
    fun `超长文本不进解析缓存`() {
        MarkdownParseCache.clear()
        val huge = "x".repeat(MarkdownParseCache.MAX_CACHEABLE_TEXT + 1)
        val blocks = MarkdownParseCache.blocks(huge)
        assertEquals(1, blocks.size)
        assertEquals(0, MarkdownParseCache.size())
    }

    @Test
    fun `LRU 按访问顺序淘汰最久未使用项`() {
        val cache = LruCache<Int, String>(3)
        cache.put(1, "a")
        cache.put(2, "b")
        cache.put(3, "c")
        // 访问 1 让它变成最近使用，随后插入 4 应淘汰 2。
        assertEquals("a", cache.get(1))
        cache.put(4, "d")

        assertNull(cache.get(2))
        assertEquals("a", cache.get(1))
        assertEquals("c", cache.get(3))
        assertEquals("d", cache.get(4))
        assertEquals(3, cache.size())
    }

    // -----------------------------------------------------------------------
    //  流式采样
    // -----------------------------------------------------------------------

    @Test
    fun `流式追加在窗口内采样并保留最后一帧`() {
        val throttle = StreamTextThrottle(windowMs = 80)

        // 首帧立即。
        assertEquals("let", throttle.offer("let", nowMs = 0))
        // 窗口内的纯追加只返回上一帧。
        assertEquals("let", throttle.offer("let a", nowMs = 10))
        assertEquals("let", throttle.offer("let a = 1", nowMs = 20))
        assertEquals(60L, throttle.millisUntilPoll(nowMs = 20))

        // 窗口未到期不交付。
        assertNull(throttle.poll(nowMs = 79))
        // 到期交付最后一帧（不是中间那次 "let a"）。
        assertEquals("let a = 1", throttle.poll(nowMs = 80))
        // 交付后没有待处理内容。
        assertNull(throttle.poll(nowMs = 500))
        assertEquals(-1L, throttle.millisUntilPoll(nowMs = 500))
    }

    @Test
    fun `窗口到期后继续追加会再次采样`() {
        val throttle = StreamTextThrottle(windowMs = 80)
        assertEquals("a", throttle.offer("a", nowMs = 0))
        assertEquals("a", throttle.offer("ab", nowMs = 10))
        assertEquals("ab", throttle.poll(nowMs = 80))

        // 下一段追加从新的 accepted 时间重新计时。
        assertEquals("ab", throttle.offer("abc", nowMs = 90))
        assertEquals("abc", throttle.poll(nowMs = 160))
    }

    @Test
    fun `换消息丢弃旧缓冲并立即返回新文本`() {
        val throttle = StreamTextThrottle(windowMs = 80)
        assertEquals("第一条", throttle.offer("第一条", nowMs = 0))
        // 已经被缓冲但还没交付的追加，在新消息到达时必须丢弃。
        assertEquals("第一条", throttle.offer("第一条 的追加", nowMs = 10))
        assertEquals("另一条完全不同的消息", throttle.offer("另一条完全不同的消息", nowMs = 20))
        assertNull(throttle.poll(nowMs = 10_000))
    }

    @Test
    fun `重发当前文本会清掉旧缓冲`() {
        val throttle = StreamTextThrottle(windowMs = 80)
        throttle.offer("第一段", nowMs = 0)
        throttle.offer("第一段 追加", nowMs = 10)
        // 文本回退到当前已渲染内容：缓冲里的旧内容不能再翻出来。
        assertEquals("第一段", throttle.offer("第一段", nowMs = 20))
        assertNull(throttle.poll(nowMs = 500))
    }

    @Test
    fun `停顿超过阈值后按新内容立即渲染`() {
        val throttle = StreamTextThrottle(windowMs = 80, maxStreamingGapMs = 1_000)
        throttle.offer("a", nowMs = 0)
        assertEquals("ab", throttle.offer("ab", nowMs = 1_500))
        assertEquals(-1L, throttle.millisUntilPoll(nowMs = 1_500))
    }

    @Test
    fun `非纯追加立即渲染`() {
        val throttle = StreamTextThrottle(windowMs = 80)
        throttle.offer("hello", nowMs = 0)
        // 同一窗口内但内容不是追加（重新生成/替换）→ 立即。
        assertEquals("HELLO", throttle.offer("HELLO", nowMs = 5))
        // 截断也立即。
        assertEquals("HEL", throttle.offer("HEL", nowMs = 6))
    }

    // -----------------------------------------------------------------------
    //  代码高亮降级
    // -----------------------------------------------------------------------

    @Test
    fun `超长代码按纯文本降级`() {
        val atLimit = "x".repeat(MarkdownHighlighter.MAX_HIGHLIGHT_CHARS)
        val overLimit = "x".repeat(MarkdownHighlighter.MAX_HIGHLIGHT_CHARS + 1)

        assertFalse(MarkdownHighlighter.exceedsHighlightBudget(atLimit))
        assertTrue(MarkdownHighlighter.exceedsHighlightBudget(overLimit))
        assertFalse(MarkdownHighlighter.exceedsHighlightBudget("val a = 1"))
    }

    // -----------------------------------------------------------------------
    //  图片降采样
    // -----------------------------------------------------------------------

    @Test
    fun `长图按 2 的幂降采样到目标附近`() {
        // 结果落在 [目标, 2×目标)：小图不放大，大图不整张进内存。
        assertEquals(1, calculateSampleSize(800, 600, 1024))
        assertEquals(1, calculateSampleSize(1600, 1200, 1024))
        assertEquals(2, calculateSampleSize(4000, 3000, 1024))
        assertEquals(4, calculateSampleSize(4000, 3000, 512))
        // 非法尺寸/上限退回不采样，交给 BitmapFactory 自己判空。
        assertEquals(1, calculateSampleSize(0, 0, 1024))
        assertEquals(1, calculateSampleSize(4000, 3000, 0))
    }
}
