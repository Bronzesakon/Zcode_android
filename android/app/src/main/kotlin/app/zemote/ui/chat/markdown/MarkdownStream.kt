package app.zemote.ui.chat.markdown

// ---------------------------------------------------------------------------
//  流式文本采样
//
//  流式助手文本每个 delta 都是一次新的组合：不节流的话，Markdown 会以 delta 的
//  频率被全量解析（几十次/秒），主线程被解析+排版占满，帧率掉、滑动抖。
//
//  做法是「采样」而不是「等流式结束」：窗口内最多渲染一次，窗口到期时补渲染
//  最后一次提交的文本，所以流式停下后一定停在最终内容上（不会丢尾帧）。
//
//  立即渲染（不缓冲）的三种情况：
//   1. 首次出现（accepted == null）；
//   2. 新文本不是上一帧的纯追加（换消息、整段替换、重新生成、截断）；
//   3. 距上一次渲染超过 [STREAM_MAX_GAP_MS]（长时间停顿后又说了一句）。
//  只有「紧接上一次、纯追加」的流式 delta 才会被短暂缓冲，从而保证切换消息时
//  永远看不到上一条文本（key 不匹配的旧结果直接丢弃）。
// ---------------------------------------------------------------------------

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** 采样窗口：60–100ms 之间，足够隐藏逐字重排，又不至于让流式看起来卡住。 */
internal const val STREAM_THROTTLE_MS = 80L

/** 超过这个间隔仍「像流式」的更新视为新内容，立即渲染。 */
internal const val STREAM_MAX_GAP_MS = 1_000L

/**
 * 纯 Kotlin 的流式采样器（不做 Compose 依赖，单测直接驱动时间戳）。
 *
 * 时间单位由调用方决定，只要是单调递增的毫秒即可。
 */
internal class StreamTextThrottle(
    private val windowMs: Long = STREAM_THROTTLE_MS,
    private val maxStreamingGapMs: Long = STREAM_MAX_GAP_MS,
) {

    private var accepted: String? = null
    private var acceptedAtMs: Long = 0L
    private var pending: String? = null

    init {
        require(windowMs > 0L) { "windowMs must be positive" }
        require(maxStreamingGapMs >= windowMs) { "maxStreamingGapMs must cover windowMs" }
    }

    /**
     * 提交一帧文本，返回「当前应该渲染的文本」。
     *
     * 返回上一帧文本表示这一帧被缓冲（窗口内采样）；缓冲的最后一帧由 [poll] 交付。
     */
    fun offer(text: String, nowMs: Long): String {
        val current = accepted ?: return accept(text, nowMs)
        if (text == current) {
            // 当前文本才是权威内容，缓冲里更旧的内容不能再被翻出来。
            pending = null
            return current
        }
        val appends = text.length > current.length && text.startsWith(current)
        val gap = nowMs - acceptedAtMs
        if (!appends || gap > maxStreamingGapMs || gap >= windowMs) {
            return accept(text, nowMs)
        }
        pending = text
        return current
    }

    /** 窗口到期后取最后一次缓冲的文本；没有缓冲或窗口未到返回 null。 */
    fun poll(nowMs: Long): String? {
        val buffered = pending ?: return null
        if (nowMs - acceptedAtMs < windowMs) return null
        return accept(buffered, nowMs)
    }

    /** 距下一次 [poll] 可交付还有多少毫秒；-1 表示没有待交付内容。 */
    fun millisUntilPoll(nowMs: Long): Long {
        if (pending == null) return -1L
        val remain = windowMs - (nowMs - acceptedAtMs)
        return if (remain <= 0L) 0L else remain
    }

    private fun accept(text: String, nowMs: Long): String {
        accepted = text
        acceptedAtMs = nowMs
        pending = null
        return text
    }
}

/**
 * 组合层的流式采样：返回值是当前应该渲染的文本。
 *
 * 组合期只做纯决策（首帧/换消息/非流式都会立即返回新文本）；被缓冲的帧由
 * [LaunchedEffect] 在窗口到期后交付并触发一次重组。
 */
@Composable
internal fun rememberStreamThrottledText(
    text: String,
    windowMs: Long = STREAM_THROTTLE_MS,
): String {
    val throttle = remember { StreamTextThrottle(windowMs = windowMs) }
    var revision by remember { mutableIntStateOf(0) }

    // revision 参与 key：poll 交付最后一帧后自增一次，让这里重新取到新文本。
    val visible = remember(text, revision) { throttle.offer(text, nowMs()) }

    LaunchedEffect(text) {
        while (true) {
            val wait = throttle.millisUntilPoll(nowMs())
            if (wait < 0L) break
            if (wait > 0L) delay(wait)
            if (throttle.poll(nowMs()) != null) revision++ else break
        }
    }
    return visible
}

private fun nowMs(): Long = System.nanoTime() / 1_000_000L
