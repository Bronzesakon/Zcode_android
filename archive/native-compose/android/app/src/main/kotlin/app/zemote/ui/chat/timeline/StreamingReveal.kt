package app.zemote.ui.chat.timeline

/**
 * 流式文本「逐词揭示」的纯函数部分。
 *
 * 桥的 `row.delta` 实测约 150ms 一批（桌面端对移动桥做了合帧）；网页端思考正文是
 * 纯文本节点、没有逐字动画，观感差异来自更新颗粒度。原生侧把每批增量摊到
 * 80–200ms 内、在帧时钟上按词补完，就能得到网页那种逐词的观感。
 *
 * 这里只放**无 Compose 依赖**的节奏与边界计算，JVM 单测直接覆盖；
 * 帧循环在 [ReasoningRow.rememberRevealedText]。
 */
internal object StreamingReveal {

    /** 单批增量上限：超过视为快照/历史加载，直接整段显示，不做逐词追赶。 */
    const val MAX_STEP = 400

    /** 超过这个长度的正文不做逐帧重排（文本布局成本会盖过动画收益）。 */
    const val MAX_CHARS = 20_000

    /** 单批增量的动画时长：字数越多摊得越开，封顶 200ms（与桥的合帧节奏同量级）。 */
    fun durationMs(delta: Int): Int = (80 + delta * 12).coerceAtMost(200)

    /** smoothstep，接近网页 ease-in-out 的手感。 */
    fun eased(t: Float): Float = t * t * (3f - 2f * t)

    /**
     * 进度 [t]（0..1）下应显示到 [text] 的哪个下标（不含）。
     *
     * 结果不会越过 [target]；未到终点时对齐到词/代理对边界，避免半个单词或半个
     * emoji 蹦出来。CJK 没有词边界，逐字推进。
     */
    fun revealIndex(text: String, start: Int, target: Int, t: Float): Int {
        if (target <= start) return target
        val raw = start + ((target - start) * eased(t)).toInt()
        return if (raw >= target) target else extendToBoundary(text, raw, target)
    }

    /** 停在半个代理对/半个英文单词中间时，向后补齐到安全边界（最多 8 字符）。 */
    fun extendToBoundary(text: String, index: Int, limit: Int): Int {
        if (index <= 0 || index >= limit) return index.coerceIn(0, limit)
        var next = index
        if (text[next - 1].isHighSurrogate() && next < limit && text[next].isLowSurrogate()) {
            next += 1
        }
        if (!text[next - 1].isAsciiWord() || !text[next].isAsciiWord()) return next
        val wordEnd = minOf(limit, next + 8)
        while (next < wordEnd && text[next].isAsciiWord()) next += 1
        return next
    }

    private fun Char.isAsciiWord(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '_'
}
