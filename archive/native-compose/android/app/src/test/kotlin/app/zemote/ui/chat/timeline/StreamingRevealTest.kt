package app.zemote.ui.chat.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式逐词揭示的边界计算：英文按词、CJK 逐字、emoji 不切半、大进度直达终点。
 * 这套逻辑跑在帧时钟上，错了会直接把半截字符渲染给用户。
 */
class StreamingRevealTest {

    @Test
    fun `进度到 1 直接给终点`() {
        val text = "hello world"
        assertEquals(text.length, StreamingReveal.revealIndex(text, 0, text.length, 1f))
    }

    @Test
    fun `终点不大于起点时直接给终点`() {
        assertEquals(4, StreamingReveal.revealIndex("abcd", 4, 4, 0f))
        assertEquals(2, StreamingReveal.revealIndex("abcd", 5, 2, 0.5f))
    }

    @Test
    fun `英文单词中间不截断`() {
        // raw 停在 "hello" 的中间也要补到词尾（下标 5）。
        assertEquals(5, StreamingReveal.revealIndex("hello world", 0, 11, 0.2f))
    }

    @Test
    fun `空格处停下，不吞下一个词`() {
        // raw=2 停在 "ab|cd" 的空格前，一次只揭示 "ab"。
        assertEquals(2, StreamingReveal.extendToBoundary("ab cd", 2, 5))
    }

    @Test
    fun `CJK 逐字推进不凑词`() {
        assertEquals(2, StreamingReveal.extendToBoundary("你好世界", 2, 4))
        assertEquals(3, StreamingReveal.extendToBoundary("你好世界", 3, 4))
    }

    @Test
    fun `emoji 代理对不切半`() {
        val text = "a😀b"
        // raw=2 落在高代理与低代理之间，必须补齐整个 emoji。
        assertEquals(3, StreamingReveal.extendToBoundary(text, 2, 4))
    }

    @Test
    fun `词边界补齐最多 8 字符`() {
        val text = "a".repeat(50) + "b"
        // 全是 ASCII 词字符：从 2 出发最多补 8 个，不会一口气把 50 个都吐出来。
        val next = StreamingReveal.extendToBoundary(text, 2, text.length)
        assertEquals(10, next)
    }

    @Test
    fun `索引越界直接原样返回`() {
        assertEquals(0, StreamingReveal.extendToBoundary("abc", 0, 3))
        assertEquals(3, StreamingReveal.extendToBoundary("abc", 3, 3))
        assertEquals(3, StreamingReveal.extendToBoundary("abc", 5, 3))
    }

    @Test
    fun `时长随增量增长但封顶 200ms`() {
        assertEquals(80, StreamingReveal.durationMs(0))
        assertEquals(80 + 12 * 5, StreamingReveal.durationMs(5))
        assertEquals(200, StreamingReveal.durationMs(400))
    }

    @Test
    fun `eased 是单调的 smoothstep`() {
        assertEquals(0f, StreamingReveal.eased(0f))
        assertEquals(1f, StreamingReveal.eased(1f))
        val half = StreamingReveal.eased(0.5f)
        assertEquals(0.5f, half, 1e-6f)
        var previous = -1f
        for (step in 0..20) {
            val value = StreamingReveal.eased(step / 20f)
            assertTrue("eased 应单调递增", value >= previous)
            previous = value
        }
    }
}
