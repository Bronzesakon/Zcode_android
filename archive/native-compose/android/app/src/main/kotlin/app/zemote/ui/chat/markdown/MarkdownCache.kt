package app.zemote.ui.chat.markdown

// ---------------------------------------------------------------------------
//  Markdown 渲染缓存
//
//  背景：流式输出时每个 delta 都会让 MarkdownText 重新组合。没有缓存时，组合期
//  会重复做三件与文本长度成正比的事——全量 parse、逐块 buildInlineAnnotated、
//  代码高亮，全在主线程，长消息 + 高频 delta 直接掉帧。
//
//  策略：
//   - 解析：按 text 做有界 LRU。AST 与颜色/字号无关，所以样式参数不进 key；
//     依赖样式的产物（行内 AnnotatedString、高亮）各自带主题做 key。
//   - 行内：按 (inlines, colors) 做有界 LRU。LazyColumn 回收后再滚回来时可直接
//     复用，不必重建 AnnotatedString。
//   - 超长文本不进解析缓存（见 MAX_CACHEABLE_TEXT），避免单条消息把整个缓存挤爆。
//   - 全部进程内、无磁盘持久化；线程安全用 @Synchronized（成本可忽略，且组合期
//     只有主线程访问）。
// ---------------------------------------------------------------------------

import androidx.compose.ui.text.AnnotatedString
import app.zemote.ui.theme.ZemoteColors

/** 极简有界 LRU（accessOrder = true，容量固定）。 */
internal class LruCache<K, V>(private val maxEntries: Int) {

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private val map = object : LinkedHashMap<K, V>(INITIAL_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean =
            size > maxEntries
    }

    /** 供单测断言命中；生产代码不读。 */
    internal var hits: Int = 0
        private set
    internal var misses: Int = 0
        private set

    @Synchronized
    fun get(key: K): V? {
        val value = map[key]
        if (value == null) misses++ else hits++
        return value
    }

    @Synchronized
    fun put(key: K, value: V) {
        map[key] = value
    }

    @Synchronized
    fun getOrPut(key: K, build: () -> V): V {
        map[key]?.let {
            hits++
            return it
        }
        misses++
        val value = build()
        map[key] = value
        return value
    }

    @Synchronized
    fun clear() {
        map.clear()
        hits = 0
        misses = 0
    }

    @Synchronized
    fun size(): Int = map.size

    private companion object {
        const val INITIAL_CAPACITY = 16
    }
}

/**
 * 块级解析结果缓存。
 *
 * key 只有文本：解析结果（[MdBlock]）不依赖颜色/字号/主题，样式在渲染期才注入，
 * 所以把样式放进 key 只会制造无谓的 miss。样式相关的缓存见 [MarkdownInlineCache]
 * 与 [MarkdownHighlighter]。
 */
internal object MarkdownParseCache {

    /** 128 条覆盖「当前消息 + 来回滚动过的几屏」，避免 fling 回头时重新解析。 */
    internal const val MAX_ENTRIES = 128

    /** 超过这个长度的文本不缓存：流式期间 AST 与文本同量级，缓存收益低于内存成本。 */
    internal const val MAX_CACHEABLE_TEXT = 64_000

    private val cache = LruCache<String, List<MdBlock>>(MAX_ENTRIES)

    fun blocks(text: String): List<MdBlock> {
        if (text.length > MAX_CACHEABLE_TEXT) return MarkdownParser.parse(text)
        return cache.getOrPut(text) { MarkdownParser.parse(text) }
    }

    internal fun hitCount(): Int = cache.hits
    internal fun missCount(): Int = cache.misses
    internal fun size(): Int = cache.size()

    internal fun clear() = cache.clear()
}

/**
 * 行内 AnnotatedString 缓存。
 *
 * key = (行内节点树, 主题颜色)：两棵结构相等的节点树必然产出等价的 AnnotatedString，
 * 可以安全共享（AnnotatedString 不可变）。
 */
internal object MarkdownInlineCache {

    /** 256 条：一条长消息本身就有几十个段落/表格单元格，64 条会在上下滚动时反复淘汰。 */
    internal const val MAX_ENTRIES = 256

    private data class Key(val inlines: List<MdInline>, val colors: ZemoteColors)

    private val cache = LruCache<Key, AnnotatedString>(MAX_ENTRIES)

    fun annotated(
        inlines: List<MdInline>,
        colors: ZemoteColors,
        build: () -> AnnotatedString,
    ): AnnotatedString = cache.getOrPut(Key(inlines, colors), build)

    internal fun hitCount(): Int = cache.hits

    internal fun size(): Int = cache.size()

    internal fun clear() = cache.clear()
}
