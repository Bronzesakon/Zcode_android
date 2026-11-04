package app.zemote.ui.chat.markdown

// ---------------------------------------------------------------------------
//  轻量 Markdown 解析器（纯 Kotlin，无第三方依赖）
//
//  取舍：
//  1. 只实现聊天场景需要的 GFM 子集，不做完整 CommonMark（无 HTML 块、无引用式
//     链接、无 setext 标题、无嵌套超过深度的极端结构）。
//  2. 流式安全优先：未闭合的围栏代码块整段降级成普通文本；未闭合的 ** / * / ~~
//     保留字面量。任何输入都不应抛异常。
//  3. 段落内的软换行保留为 \n —— 聊天消息里用户/模型经常靠换行表达结构，
//     Web 端聊天 markdown 也是 breaks:true 的口径。
//  4. 解析是纯函数，结果由调用方用 remember(text) 缓存；本文件不持有状态。
// ---------------------------------------------------------------------------

internal object MarkdownParser {

    /** 块级解析入口。输入为空时返回空列表。 */
    fun parse(text: String): List<MdBlock> {
        if (text.isEmpty()) return emptyList()
        // 统一换行符，避免 CRLF 让行尾的 ** 或表格分隔线匹配不上
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        return parseBlocks(normalized.split('\n'), depth = 0)
    }

    /** 行内解析入口（也方便单测直接调用）。 */
    fun parseInline(text: String): List<MdInline> = parseInlines(text, depth = 0)

    // =======================================================================
    //  块级
    // =======================================================================

    private const val MAX_BLOCK_DEPTH = 16

    private val fenceRe = Regex("""^ {0,3}(`{3,}|~{3,})(.*)$""")
    private val headingRe = Regex("""^ {0,3}(#{1,6})(?:[ \t]+(.*?))?[ \t]*$""")
    private val ruleRe = Regex("""^ {0,3}([-*_])(?:[ \t]*\1){2,}[ \t]*$""")
    private val quoteRe = Regex("""^ {0,3}>[ \t]?(.*)$""")
    private val listRe = Regex("""^([ \t]*)([-+*]|\d{1,9}[.)])([ \t]+)(.*)$""")
    private val tableSepRe =
        Regex("""^[ \t]*\|?[ \t]*:?-{1,}:?[ \t]*(?:\|[ \t]*:?-{1,}:?[ \t]*)*\|?[ \t]*$""")
    private val taskRe = Regex("""^\[([ xX])\][ \t]+(.*)$""")
    /** 标题行尾的 `###` 闭合标记；预先编译，避免每行标题都构造一次正则。 */
    private val trailingHashesRe = Regex("""[ \t]+#+[ \t]*$""")

    private fun parseBlocks(lines: List<String>, depth: Int): List<MdBlock> {
        if (depth > MAX_BLOCK_DEPTH) {
            return listOf(MdBlock.Paragraph(listOf(MdInline.Text(lines.joinToString("\n")))))
        }

        val out = ArrayList<MdBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                i++
                continue
            }

            // ---- 围栏代码块（``` 或 ~~~）----
            val fence = fenceRe.matchEntire(line)
            if (fence != null) {
                val marker = fence.groupValues[1]
                val info = fence.groupValues[2].trim()
                var end = i + 1
                while (end < lines.size && !isClosingFence(lines[end], marker)) end++
                if (end < lines.size) {
                    out += MdBlock.Code(
                        code = lines.subList(i + 1, end).joinToString("\n"),
                        language = info.substringBefore(' ').substringBefore('\t')
                            .ifBlank { null },
                    )
                    i = end + 1
                } else {
                    // 流式输出中围栏还没闭合：整段按普通文本降级，不吞内容也不崩
                    out += MdBlock.Paragraph(
                        listOf(MdInline.Text(lines.subList(i, lines.size).joinToString("\n"))),
                    )
                    i = lines.size
                }
                continue
            }

            // ---- ATX 标题 ----
            val heading = headingRe.matchEntire(line)
            if (heading != null) {
                val level = heading.groupValues[1].length
                val raw = heading.groupValues.getOrNull(2).orEmpty()
                    .replace(trailingHashesRe, "")
                    .trim()
                out += MdBlock.Heading(level, parseInlines(raw, depth + 1))
                i++
                continue
            }

            // ---- 分隔线 ----
            if (ruleRe.matches(line)) {
                out += MdBlock.Rule
                i++
                continue
            }

            // ---- 引用（连续 > 行整体递归解析）----
            if (quoteRe.matches(line)) {
                val inner = ArrayList<String>()
                while (i < lines.size) {
                    val q = quoteRe.matchEntire(lines[i]) ?: break
                    inner += q.groupValues[1]
                    i++
                }
                val blocks = parseBlocks(inner, depth + 1)
                if (blocks.isNotEmpty()) out += MdBlock.Quote(blocks)
                continue
            }

            // ---- GFM 表格（当前行 + 下一行分隔线）----
            if (isTableStart(lines, i)) {
                val headerCells = splitTableRow(lines[i])
                val align = splitTableRow(lines[i + 1]).map { parseAlign(it) }
                i += 2
                val rows = ArrayList<List<List<MdInline>>>()
                while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) {
                    val cells = splitTableRow(lines[i])
                    rows += List(headerCells.size) { c ->
                        parseInlines(cells.getOrElse(c) { "" }, depth + 1)
                    }
                    i++
                }
                out += MdBlock.Table(
                    header = headerCells.map { parseInlines(it, depth + 1) },
                    rows = rows,
                    align = align,
                )
                continue
            }

            // ---- 列表（同层连续项；缩进更深的行递归成嵌套列表）----
            if (listRe.matches(line)) {
                val (block, next) = parseList(lines, i, depth)
                out += block
                i = next
                continue
            }

            // ---- 段落 ----
            val para = ArrayList<String>()
            while (i < lines.size) {
                if (para.isNotEmpty() && isBlockStart(lines, i)) break
                if (para.isEmpty() && isBlockStart(lines, i)) break
                para += lines[i]
                i++
            }
            if (para.isEmpty()) {
                // 理论上走不到；防御性前进一行，杜绝死循环
                i++
                continue
            }
            val joined = para.joinToString("\n")
            val inlines = parseInlines(joined, depth + 1)
            // 独占一行的图片提升为块级图片，避免在段落里挤成小链接
            if (inlines.size == 1 && inlines[0] is MdInline.Image) {
                val image = inlines[0] as MdInline.Image
                out += MdBlock.Image(image.alt, image.url)
            } else {
                out += MdBlock.Paragraph(inlines)
            }
        }
        return out
    }

    private fun isBlockStart(lines: List<String>, index: Int): Boolean {
        val line = lines[index]
        return line.isBlank() ||
            fenceRe.matches(line) ||
            headingRe.matches(line) ||
            ruleRe.matches(line) ||
            quoteRe.matches(line) ||
            listRe.matches(line) ||
            isTableStart(lines, index)
    }

    private fun isClosingFence(line: String, fence: String): Boolean {
        val trimmed = line.trimStart(' ')
        if (line.length - trimmed.length > 3) return false
        val ch = fence[0]
        val run = trimmed.takeWhile { it == ch }.length
        if (run < fence.length) return false
        return trimmed.substring(run).isBlank()
    }

    private fun isTableStart(lines: List<String>, index: Int): Boolean {
        val header = lines.getOrNull(index) ?: return false
        if (!header.contains('|')) return false
        val sep = lines.getOrNull(index + 1) ?: return false
        if (!tableSepRe.matches(sep)) return false
        return splitTableRow(header).size == splitTableRow(sep).size
    }

    private fun parseAlign(cell: String): MdAlign {
        val s = cell.trim()
        val left = s.startsWith(":")
        val right = s.endsWith(":")
        return when {
            left && right -> MdAlign.Center
            left -> MdAlign.Start
            right -> MdAlign.End
            else -> MdAlign.None
        }
    }

    /**
     * 按未转义、且不在行内代码里的 `|` 切单元格。
     * 保留 `\|` 原样，交给行内解析器反转义。
     */
    private fun splitTableRow(row: String): List<String> {
        var s = row.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
        if (s.isEmpty()) return emptyList()

        val cells = ArrayList<String>()
        val current = StringBuilder()
        var inCode = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length -> {
                    current.append(c).append(s[i + 1])
                    i += 2
                }

                c == '`' -> {
                    inCode = !inCode
                    current.append(c)
                    i++
                }

                c == '|' && !inCode -> {
                    cells += current.toString().trim()
                    current.clear()
                    i++
                }

                else -> {
                    current.append(c)
                    i++
                }
            }
        }
        cells += current.toString().trim()
        return cells
    }

    /**
     * 解析一组同层列表项。
     *
     * 返回「列表块 + 下一处未消费的行号」。缩进大于当前层标记宽度的行会被
     * 去掉基准缩进后塞进列表项内容，再由递归 parseBlocks 识别成嵌套列表。
     */
    private fun parseList(
        lines: List<String>,
        startIndex: Int,
        depth: Int,
    ): Pair<MdBlock, Int> {
        val first = listRe.matchEntire(lines[startIndex])!!
        val baseIndent = indentWidth(first.groupValues[1])
        val ordered = first.groupValues[2].first().isDigit()
        val startNumber = if (ordered) {
            first.groupValues[2].dropLast(1).toIntOrNull() ?: 1
        } else {
            1
        }

        val items = ArrayList<MdListItem>()
        var i = startIndex

        while (i < lines.size) {
            val line = lines[i]

            // 空行：只有后面还跟着同层同类型列表项时才继续（loose list）
            if (line.isBlank()) {
                var k = i + 1
                while (k < lines.size && lines[k].isBlank()) k++
                val nextMarker = lines.getOrNull(k)?.let { listRe.matchEntire(it) }
                if (nextMarker != null &&
                    indentWidth(nextMarker.groupValues[1]) == baseIndent &&
                    nextMarker.groupValues[2].first().isDigit() == ordered
                ) {
                    i = k
                    continue
                }
                break
            }

            val marker = listRe.matchEntire(line) ?: break
            if (indentWidth(marker.groupValues[1]) != baseIndent ||
                marker.groupValues[2].first().isDigit() != ordered
            ) {
                break
            }

            val markerWidth = marker.groupValues[2].length + marker.groupValues[3].length
            val contentIndent = baseIndent + markerWidth
            var firstLine = marker.groupValues[4]
            var checked: Boolean? = null
            taskRe.matchEntire(firstLine)?.let { task ->
                checked = task.groupValues[1] != " "
                firstLine = task.groupValues[2]
            }

            val content = ArrayList<String>()
            content += firstLine
            i++

            // 收集本项后续行：缩进更深的行属于本项（可能是嵌套列表）
            while (i < lines.size) {
                val next = lines[i]
                if (next.isBlank()) {
                    var k = i + 1
                    while (k < lines.size && lines[k].isBlank()) k++
                    val following = lines.getOrNull(k) ?: break
                    if (indentWidth(following) > baseIndent) {
                        repeat(k - i) { content += "" }
                        i = k
                        continue
                    }
                    break
                }
                val nestedMarker = listRe.matchEntire(next)
                if (nestedMarker != null &&
                    indentWidth(nestedMarker.groupValues[1]) == baseIndent
                ) {
                    break // 下一个同层项
                }
                val indent = indentWidth(next)
                if (indent > baseIndent) {
                    content += stripIndent(next, minOf(indent, contentIndent))
                    i++
                } else {
                    break
                }
            }

            val blocks = parseBlocks(content, depth + 1)
                .ifEmpty { listOf(MdBlock.Paragraph(emptyList())) }
            items += MdListItem(blocks, checked)
        }

        return MdBlock.BulletList(items, ordered, startNumber) to i
    }

    private fun indentWidth(line: String): Int {
        var columns = 0
        for (c in line) {
            when (c) {
                ' ' -> columns++
                '\t' -> columns += 4
                else -> return columns
            }
        }
        return columns
    }

    private fun stripIndent(line: String, columns: Int): String {
        var consumed = 0
        var i = 0
        while (i < line.length && consumed < columns) {
            when (line[i]) {
                ' ' -> {
                    consumed++
                    i++
                }

                '\t' -> {
                    consumed += 4
                    i++
                }

                else -> return line.substring(i)
            }
        }
        return line.substring(i)
    }

    // =======================================================================
    //  行内
    // =======================================================================

    private const val MAX_INLINE_DEPTH = 12
    private const val ESCAPABLE = "\\`*_{}[]()#+-.!|~>"

    private fun parseInlines(text: String, depth: Int): List<MdInline> {
        if (text.isEmpty()) return emptyList()
        if (depth > MAX_INLINE_DEPTH) return listOf(MdInline.Text(text))

        val out = ArrayList<MdInline>()
        val buffer = StringBuilder()

        fun flush() {
            if (buffer.isNotEmpty()) {
                out += MdInline.Text(buffer.toString())
                buffer.clear()
            }
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]

            // 反斜杠转义：只对 Markdown 标点生效，`\a` 这类保留反斜杠
            if (c == '\\') {
                val next = text.getOrNull(i + 1)
                if (next != null && ESCAPABLE.indexOf(next) >= 0) {
                    buffer.append(next)
                    i += 2
                } else {
                    buffer.append(c)
                    i++
                }
                continue
            }

            // 行内代码：等长反引号串配对，未闭合就按字面量
            if (c == '`') {
                val run = countRun(text, i, '`')
                val close = findBacktickClose(text, i + run, run)
                if (close >= 0) {
                    flush()
                    var content = text.substring(i + run, close)
                    if (content.length >= 2 &&
                        content.startsWith(" ") &&
                        content.endsWith(" ") &&
                        content.isNotBlank()
                    ) {
                        content = content.substring(1, content.length - 1)
                    }
                    out += MdInline.Code(content)
                    i = close + run
                } else {
                    buffer.append(text, i, i + run)
                    i += run
                }
                continue
            }

            // 图片 ![alt](url)：先于链接判断
            if (c == '!' && text.getOrNull(i + 1) == '[') {
                val link = parseLinkAt(text, i + 1)
                if (link != null) {
                    flush()
                    out += MdInline.Image(link.label, link.href)
                    i = link.end
                } else {
                    buffer.append(c)
                    i++
                }
                continue
            }

            // 链接 [text](url)
            if (c == '[') {
                val link = parseLinkAt(text, i)
                if (link != null) {
                    flush()
                    out += MdInline.Link(link.href, parseInlines(link.label, depth + 1))
                    i = link.end
                } else {
                    buffer.append(c)
                    i++
                }
                continue
            }

            // 自动链接 <https://...>
            if (c == '<') {
                val close = text.indexOf('>', i + 1)
                if (close > i + 1) {
                    val candidate = text.substring(i + 1, close)
                    if (candidate.startsWith("http://") ||
                        candidate.startsWith("https://") ||
                        candidate.startsWith("mailto:")
                    ) {
                        flush()
                        out += MdInline.Link(candidate, listOf(MdInline.Text(candidate)))
                        i = close + 1
                        continue
                    }
                }
                buffer.append(c)
                i++
                continue
            }

            // 强调 / 删除线
            if (c == '*' || c == '_' || c == '~') {
                val double = text.getOrNull(i + 1) == c
                val delim = when {
                    c == '~' && double -> "~~"
                    c == '~' -> null
                    double -> "$c$c"
                    else -> c.toString()
                }
                val canOpen = delim != null &&
                    text.getOrNull(i + delim.length)?.isWhitespace() != true &&
                    // 下划线不做词内强调（foo_bar_baz 保持原样）
                    !(c == '_' && i > 0 && text[i - 1].isLetterOrDigit())
                if (delim == null || !canOpen) {
                    buffer.append(c)
                    i++
                    continue
                }
                val close = findClosingDelimiter(text, i + delim.length, delim)
                if (close < 0) {
                    // 流式输出中未闭合：保留字面量，绝不吞掉后面的内容
                    buffer.append(text, i, i + delim.length)
                    i += delim.length
                    continue
                }
                flush()
                val children = parseInlines(
                    text.substring(i + delim.length, close),
                    depth + 1,
                )
                out += when (delim) {
                    "**", "__" -> MdInline.Strong(children)
                    "~~" -> MdInline.Strike(children)
                    else -> MdInline.Emph(children)
                }
                i = close + delim.length
                continue
            }

            buffer.append(c)
            i++
        }
        flush()
        return mergeText(out)
    }

    private data class ParsedLink(val label: String, val href: String, val end: Int)

    /** [bracket] 指向 `[`，成功时 [ParsedLink.end] 指向 `)` 之后。 */
    private fun parseLinkAt(text: String, bracket: Int): ParsedLink? {
        var depth = 0
        var i = bracket
        var closeBracket = -1
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' -> i += 2
                c == '`' -> {
                    val run = countRun(text, i, '`')
                    val close = findBacktickClose(text, i + run, run)
                    i = if (close >= 0) close + run else i + run
                }

                c == '[' -> {
                    depth++
                    i++
                }

                c == ']' -> {
                    depth--
                    if (depth == 0) {
                        closeBracket = i
                        i = text.length
                    } else {
                        i++
                    }
                }

                else -> i++
            }
        }
        if (closeBracket < 0) return null
        if (text.getOrNull(closeBracket + 1) != '(') return null

        var parenDepth = 0
        var j = closeBracket + 1
        var closeParen = -1
        while (j < text.length) {
            val c = text[j]
            when {
                c == '\\' -> j += 2
                c == '(' -> {
                    parenDepth++
                    j++
                }

                c == ')' -> {
                    parenDepth--
                    if (parenDepth == 0) {
                        closeParen = j
                        j = text.length
                    } else {
                        j++
                    }
                }

                else -> j++
            }
        }
        if (closeParen < 0) return null

        val label = text.substring(bracket + 1, closeBracket)
        var destination = text.substring(closeBracket + 2, closeParen).trim()
        if (destination.isEmpty()) return null
        // 去掉 <...> 包裹与可选 title（title 在聊天里没有展示价值）
        if (destination.startsWith("<")) {
            val end = destination.indexOf('>')
            if (end > 0) destination = destination.substring(1, end)
        } else {
            val space = destination.indexOfFirst { it == ' ' || it == '\t' || it == '\n' }
            if (space >= 0) destination = destination.substring(0, space)
        }
        destination = destination.trim()
        if (destination.isEmpty()) return null
        return ParsedLink(label, destination, closeParen + 1)
    }

    private fun countRun(text: String, start: Int, ch: Char): Int {
        var i = start
        while (i < text.length && text[i] == ch) i++
        return i - start
    }

    private fun findBacktickClose(text: String, from: Int, run: Int): Int {
        var i = from
        while (i < text.length) {
            if (text[i] == '`') {
                val r = countRun(text, i, '`')
                if (r == run) return i
                i += r
            } else {
                i++
            }
        }
        return -1
    }

    /**
     * 找强调/删除线的闭合分隔符。
     *
     * 跳过转义与行内代码；单字符分隔符只接受长度为 1 的 run，这样
     * `*a **b** c*` 不会把内层 `**` 误当成外层 `*` 的闭合。
     */
    private fun findClosingDelimiter(text: String, from: Int, delim: String): Int {
        val ch = delim[0]
        val need = delim.length
        var i = from
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' -> i += 2

                c == '`' -> {
                    val run = countRun(text, i, '`')
                    val close = findBacktickClose(text, i + run, run)
                    i = if (close >= 0) close + run else i + run
                }

                c == ch -> {
                    val run = countRun(text, i, ch)
                    val canClose = i > from && !text[i - 1].isWhitespace()
                    if (canClose && run >= need && (need > 1 || run == 1)) return i
                    i += run
                }

                else -> i++
            }
        }
        return -1
    }

    private fun mergeText(nodes: List<MdInline>): List<MdInline> {
        if (nodes.size < 2) return nodes
        val merged = ArrayList<MdInline>(nodes.size)
        for (node in nodes) {
            val last = merged.lastOrNull()
            if (node is MdInline.Text && last is MdInline.Text) {
                merged[merged.lastIndex] = MdInline.Text(last.text + node.text)
            } else {
                merged += node
            }
        }
        return merged
    }

}
