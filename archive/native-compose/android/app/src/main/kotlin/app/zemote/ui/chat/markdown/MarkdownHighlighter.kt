package app.zemote.ui.chat.markdown

// ---------------------------------------------------------------------------
//  轻量语法着色器
//
//  设计取舍：
//  1. 只做四类 token：关键字 / 字符串 / 注释 / 数字，其余一律普通文本。
//     聊天代码块的字号本来就小，颜色再细分反而更吵。
//  2. 自己写状态机而不是引 prism/highlight.js：不引依赖、不联网，且输出是
//     AnnotatedString，渲染层可以直接复用。
//  3. 输出 token 按原顺序拼接后与输入逐字符相等（着色不改内容），所以复制按钮
//     复制的始终是原始代码。
//  4. 未识别的语言直接整段普通文本，宁可不染色也不乱染。
//  5. 结果按 (code, language, colors) 进有界 LRU：滚动回看/同内容多次组合时直接复用。
//  6. 超长代码（[MAX_HIGHLIGHT_CHARS]）直接去色成纯文本——只去掉着色，不改字号/行高/
//     折叠等结构；避免主线程对超大输入做整段分词与 AnnotatedString 构造。
// ---------------------------------------------------------------------------

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import app.zemote.ui.theme.ZemoteColors

internal object MarkdownHighlighter {

    /**
     * 着色预算：超过就退回纯文本。
     *
     * 代码块折叠只保留 30 行，但单行可能是几百 KB 的压缩 JSON；这种输入在做
     * AnnotatedString 拼装时会产生大量 SpanStyle + substring，主线程扛不住。
     */
    internal const val MAX_HIGHLIGHT_CHARS = 32_000

    private const val CACHE_ENTRIES = 48

    private data class HighlightKey(
        val code: String,
        val language: String?,
        val colors: ZemoteColors,
    )

    private val cache = LruCache<HighlightKey, AnnotatedString>(CACHE_ENTRIES)

    private enum class Kind { Plain, Keyword, String, Comment, Number }

    private data class Token(val text: String, val kind: Kind)

    private class Profile(
        val keywords: Set<String>,
        val lineComments: List<String> = listOf("//"),
        val blockComments: List<Pair<String, String>> = listOf("/*" to "*/"),
        val tripleQuote: Boolean = false,
        /** JS/TS 的模板字符串用反引号。 */
        val backtickString: Boolean = false,
        /** `#` 只有位于行首或空白之后才算注释（shell 的 `$#`、yaml 的 `a#b`）。 */
        val hashNeedsBoundary: Boolean = false,
        val yamlKeys: Boolean = false,
    )

    /** 把代码转成带颜色的 AnnotatedString。调用方无需额外缓存，这里自带 LRU。 */
    fun highlight(code: String, language: String?, colors: ZemoteColors): AnnotatedString =
        cache.getOrPut(HighlightKey(code, language, colors)) {
            if (exceedsHighlightBudget(code)) {
                // 超长：只去色（Text 的 style 已带前景色），结构/换行/缩进原样保留。
                AnnotatedString(code)
            } else {
                renderHighlighted(code, language, colors)
            }
        }

    /** 单测用：判断这段代码是否已超出着色预算（超出即纯文本渲染）。 */
    internal fun exceedsHighlightBudget(code: String): Boolean = code.length > MAX_HIGHLIGHT_CHARS

    private fun renderHighlighted(
        code: String,
        language: String?,
        colors: ZemoteColors,
    ): AnnotatedString {
        val lang = normalize(language)
        if (lang == "diff" || lang == "patch") return highlightDiff(code, colors)

        val tokens = tokenize(code, lang)
        return buildAnnotatedString {
            for (token in tokens) {
                val color = when (token.kind) {
                    Kind.Keyword -> colors.iconPurple
                    Kind.String -> colors.success
                    Kind.Comment -> colors.foregroundSubtle
                    Kind.Number -> colors.warning
                    Kind.Plain -> colors.foreground
                }
                withStyle(SpanStyle(color = color)) { append(token.text) }
            }
        }
    }

    private fun normalize(language: String?): String =
        language.orEmpty().trim().lowercase()
            .removePrefix("language-")
            .let {
                when (it) {
                    "kt", "kts" -> "kotlin"
                    "js", "jsx", "mjs", "cjs" -> "javascript"
                    "ts", "tsx" -> "typescript"
                    "py" -> "python"
                    "rs" -> "rust"
                    "sh", "bash", "zsh", "shell", "console" -> "shell"
                    "yml" -> "yaml"
                    "html", "svg", "xhtml" -> "xml"
                    "patch" -> "diff"
                    else -> it
                }
            }

    // =======================================================================
    //  diff / patch
    // =======================================================================

    private fun highlightDiff(code: String, colors: ZemoteColors): AnnotatedString =
        buildAnnotatedString {
            val lines = code.split('\n')
            lines.forEachIndexed { index, line ->
                val color = when {
                    line.startsWith("@@") -> colors.iconPurple
                    line.startsWith("+") -> colors.diffAdded
                    line.startsWith("-") -> colors.diffRemoved
                    else -> colors.foreground
                }
                val background = when {
                    line.startsWith("+") -> colors.diffAdded.copy(alpha = 0.12f)
                    line.startsWith("-") -> colors.diffRemoved.copy(alpha = 0.12f)
                    else -> Color.Unspecified
                }
                withStyle(SpanStyle(color = color, background = background)) {
                    append(line)
                }
                if (index < lines.lastIndex) {
                    // 换行不染色，避免行尾出现一条突兀的色带
                    withStyle(SpanStyle(color = colors.foreground)) { append('\n') }
                }
            }
        }

    // =======================================================================
    //  tokenizer
    // =======================================================================

    private fun tokenize(code: String, lang: String): List<Token> {
        if (code.isEmpty()) return emptyList()
        return when (lang) {
            "kotlin" -> scanCLike(code, kotlinProfile)
            "java" -> scanCLike(code, javaProfile)
            "rust" -> scanCLike(code, rustProfile)
            "javascript", "typescript" -> scanCLike(code, jsProfile)
            "python" -> scanCLike(code, pythonProfile)
            "json" -> scanCLike(code, jsonProfile)
            "shell" -> scanCLike(code, shellProfile)
            "yaml" -> scanCLike(code, yamlProfile)
            "xml" -> scanXml(code)
            else -> listOf(Token(code, Kind.Plain))
        }
    }

    private fun scanCLike(code: String, p: Profile): List<Token> {
        val out = ArrayList<Token>()
        var plainStart = 0
        var i = 0

        fun flushPlain(end: Int) {
            if (end > plainStart) out += Token(code.substring(plainStart, end), Kind.Plain)
        }

        fun emit(start: Int, end: Int, kind: Kind) {
            flushPlain(start)
            if (end > start) out += Token(code.substring(start, end), kind)
            plainStart = end
        }

        while (i < code.length) {
            // 块注释
            val block = p.blockComments.firstOrNull { code.startsWith(it.first, i) }
            if (block != null) {
                val rawEnd = code.indexOf(block.second, i + block.first.length)
                val end = if (rawEnd < 0) code.length else rawEnd + block.second.length
                emit(i, end, Kind.Comment)
                i = end
                continue
            }

            // 行注释
            val lineComment = p.lineComments.firstOrNull { marker ->
                code.startsWith(marker, i) &&
                    (marker != "#" || !p.hashNeedsBoundary || isBoundaryBefore(code, i))
            }
            if (lineComment != null) {
                val rawEnd = code.indexOf('\n', i)
                val end = if (rawEnd < 0) code.length else rawEnd
                emit(i, end, Kind.Comment)
                i = end
                continue
            }

            val c = code[i]

            // 字符串（python 额外支持三引号）
            if (c == '"' || c == '\'' || (p.backtickString && c == '`')) {
                val end = scanString(code, i, c, p.tripleQuote)
                emit(i, end, Kind.String)
                i = end
                continue
            }

            // 数字：前面不能紧贴标识符（foo1 里的 1 不是独立数字）
            if (c.isDigit() && (i == 0 || !isIdentChar(code[i - 1]))) {
                var j = i
                while (j < code.length &&
                    (code[j].isLetterOrDigit() || code[j] == '.' || code[j] == '_')
                ) {
                    j++
                }
                emit(i, j, Kind.Number)
                i = j
                continue
            }

            // 标识符 / 关键字 / yaml key
            if (c.isLetter() || c == '_' || c == '$') {
                var j = i
                while (j < code.length && isIdentChar(code[j])) j++
                val word = code.substring(i, j)
                val keyword = p.keywords.contains(word) ||
                    (p.yamlKeys && isYamlKey(code, i, j))
                if (keyword) emit(i, j, Kind.Keyword)
                i = j
                continue
            }

            i++
        }
        flushPlain(code.length)
        return out
    }

    private fun scanString(code: String, start: Int, quote: Char, triple: Boolean): Int {
        if (triple && quote != '`' &&
            start + 2 < code.length &&
            code[start + 1] == quote &&
            code[start + 2] == quote
        ) {
            val close = code.indexOf("$quote$quote$quote", start + 3)
            return if (close < 0) code.length else close + 3
        }

        var i = start + 1
        while (i < code.length) {
            val c = code[i]
            when {
                c == '\\' -> i += 2
                c == quote -> return i + 1
                // 单行字符串在换行处收尾，避免一个漏引号把后面整段吃掉
                c == '\n' && quote != '`' -> return i
                else -> i++
            }
        }
        return code.length
    }

    private fun isIdentChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '$'

    private fun isBoundaryBefore(code: String, index: Int): Boolean =
        index == 0 || code[index - 1].isWhitespace()

    private fun isYamlKey(code: String, start: Int, end: Int): Boolean {
        var k = start - 1
        while (k >= 0 && (code[k] == ' ' || code[k] == '\t')) k--
        val atLineStart = k < 0 || code[k] == '\n' || code[k] == '-'
        if (!atLineStart) return false
        var j = end
        while (j < code.length && (code[j] == ' ' || code[j] == '\t')) j++
        return j < code.length && code[j] == ':' && code.getOrNull(j + 1) != ':'
    }

    // =======================================================================
    //  XML / HTML
    // =======================================================================

    private fun scanXml(code: String): List<Token> {
        val out = ArrayList<Token>()
        var plainStart = 0
        var i = 0

        fun flushPlain(end: Int) {
            if (end > plainStart) out += Token(code.substring(plainStart, end), Kind.Plain)
        }

        fun emit(start: Int, end: Int, kind: Kind) {
            flushPlain(start)
            if (end > start) out += Token(code.substring(start, end), kind)
            plainStart = end
        }

        while (i < code.length) {
            when {
                code.startsWith("<!--", i) -> {
                    val rawEnd = code.indexOf("-->", i + 4)
                    val end = if (rawEnd < 0) code.length else rawEnd + 3
                    emit(i, end, Kind.Comment)
                    i = end
                }

                code[i] == '<' -> {
                    var j = i + 1
                    if (j < code.length && (code[j] == '/' || code[j] == '!' || code[j] == '?')) j++
                    val nameStart = j
                    while (j < code.length &&
                        (code[j].isLetterOrDigit() || code[j] in "_:-.")
                    ) {
                        j++
                    }
                    if (j > nameStart) {
                        emit(i, j, Kind.Keyword)
                        i = j
                    } else {
                        i++
                    }
                }

                code[i] == '"' || code[i] == '\'' -> {
                    val end = scanString(code, i, code[i], triple = false)
                    emit(i, end, Kind.String)
                    i = end
                }

                code[i].isDigit() && (i == 0 || !isIdentChar(code[i - 1])) -> {
                    var j = i
                    while (j < code.length &&
                        (code[j].isLetterOrDigit() || code[j] == '.' || code[j] == '_')
                    ) {
                        j++
                    }
                    emit(i, j, Kind.Number)
                    i = j
                }

                else -> i++
            }
        }
        flushPlain(code.length)
        return out
    }

    // =======================================================================
    //  语言配置
    // =======================================================================

    private val kotlinProfile = Profile(
        keywords = setOf(
            "package", "import", "class", "interface", "object", "fun", "val", "var",
            "if", "else", "when", "for", "while", "do", "return", "break", "continue",
            "try", "catch", "finally", "throw", "is", "as", "in", "out", "by",
            "typealias", "constructor", "init", "super", "this", "null", "true", "false",
            "companion", "data", "sealed", "enum", "annotation", "override", "open",
            "private", "protected", "public", "internal", "abstract", "final",
            "lateinit", "const", "suspend", "inline", "noinline", "crossinline",
            "reified", "vararg", "operator", "infix", "external", "tailrec", "where",
        ),
    )

    private val javaProfile = Profile(
        keywords = setOf(
            "class", "interface", "enum", "extends", "implements", "public", "private",
            "protected", "static", "final", "abstract", "synchronized", "volatile",
            "transient", "native", "void", "boolean", "byte", "char", "short", "int",
            "long", "float", "double", "if", "else", "switch", "case", "default",
            "for", "while", "do", "return", "break", "continue", "try", "catch",
            "finally", "throw", "throws", "new", "this", "super", "null", "true",
            "false", "package", "import", "instanceof", "record", "sealed", "permits",
            "var", "yield",
        ),
    )

    private val rustProfile = Profile(
        keywords = setOf(
            "fn", "let", "mut", "const", "static", "struct", "enum", "trait", "impl",
            "for", "while", "loop", "if", "else", "match", "return", "break",
            "continue", "use", "mod", "pub", "crate", "self", "super", "where", "as",
            "in", "ref", "move", "unsafe", "async", "await", "dyn", "type", "true",
            "false",
        ),
    )

    private val jsProfile = Profile(
        keywords = setOf(
            "const", "let", "var", "function", "return", "if", "else", "for", "while",
            "do", "switch", "case", "default", "break", "continue", "new", "delete",
            "typeof", "instanceof", "in", "of", "this", "super", "class", "extends",
            "implements", "interface", "type", "enum", "import", "export", "from",
            "as", "async", "await", "yield", "try", "catch", "finally", "throw",
            "void", "null", "undefined", "true", "false", "static", "public",
            "private", "protected", "readonly", "declare", "namespace", "abstract",
            "keyof", "infer", "satisfies",
        ),
        backtickString = true,
    )

    private val pythonProfile = Profile(
        keywords = setOf(
            "def", "class", "return", "if", "elif", "else", "for", "while", "break",
            "continue", "pass", "import", "from", "as", "with", "try", "except",
            "finally", "raise", "lambda", "yield", "global", "nonlocal", "assert",
            "del", "in", "is", "not", "and", "or", "None", "True", "False", "async",
            "await", "match", "case",
        ),
        lineComments = listOf("#"),
        blockComments = emptyList(),
        tripleQuote = true,
    )

    private val jsonProfile = Profile(
        keywords = setOf("true", "false", "null"),
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/"),
    )

    private val shellProfile = Profile(
        keywords = setOf(
            "if", "then", "elif", "else", "fi", "for", "while", "until", "do",
            "done", "case", "esac", "function", "in", "select", "time", "return",
            "break", "continue", "local", "export", "readonly", "declare", "typeset",
            "unset", "echo", "printf", "cd", "pwd", "exit", "source", "alias", "set",
            "shift", "test", "eval", "exec", "trap", "wait", "read", "true", "false",
        ),
        lineComments = listOf("#"),
        blockComments = emptyList(),
        hashNeedsBoundary = true,
    )

    private val yamlProfile = Profile(
        keywords = setOf("true", "false", "null", "yes", "no", "on", "off"),
        lineComments = listOf("#"),
        blockComments = emptyList(),
        hashNeedsBoundary = true,
        yamlKeys = true,
    )
}
