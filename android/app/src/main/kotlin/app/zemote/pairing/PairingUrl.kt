package app.zemote.pairing

/**
 * 配对链接（桌面端 ZCode 生成的远控 URL，也是二维码内容）。
 *
 * - 3.16.1+：`https://zcode.z.ai/remote/v4?app_version=...&sid=...&mid=...&name=...`
 *   （无 `hash` / `t`；认证改为登录后取得的 OAuth JWT）
 * - legacy：`https://zcode.z.ai/remote/v4?sid=...&hash=...&t=...&mid=...&name=...`
 *
 * ## 这不是权威校验
 *
 * 权威解析器是 Rust 的 `ZemoteConnectionParams::parse`
 * （`rust/crates/protocol/src/connection_params.rs`，对应 Dart
 * `Reference_code/lib/protocol/connection_params.dart`）。本文件只回答一个问题：
 * **"这个串值不值得拿去试一次"**，好给用户即时反馈。真正的合法性（`t` 是不是数字、
 * 端口、百分号编码、host 长什么样……）全部交给引擎：`nativeCreate` 对非法 URL 返回
 * `badInput`，那个错误要**原样**呈现给用户，不要在这里翻译第二遍。
 *
 * ## 硬约束：只许比 Rust 更宽松
 *
 * 本文件的判定集合必须是 `ZemoteConnectionParams::parse` 接受集合的**超集**。少一条
 * 必要条件的代价只是"多跑一次引擎、多弹一个 badInput"；多一条自创规则的代价是
 * **假阴性**——用户手里明明是 Rust 能解析的链接，却被前置筛查拦死。所以：
 *
 * - 只查"有没有"：scheme 是不是 http(s)/wss、有没有 host、有没有 `sid`；
 * - 不查"合不合法"：`t` 不做数字检查，path 不做 `/remote/` 检查，host 不做白名单
 *   （Rust 的 `parse` 完全不看 host，任何 host 都能解析，基于 host 的拒绝必然制造假阴性）；
 * - 唯一的例外是 [RejectReason.MISSING_HOST]：Rust 的 `parse` 连空 host 都收（它不校验
 *   host），但空 host 的链接生成不出可用的中继地址（`relay_ws_uri` 会是 `wss:///ws`），
 *   所以这里按"缺 host"提前拒掉，属于有意的、面向可用性的收紧。
 *
 * ## 与 Rust 对齐的解析细节
 *
 * 下面这些行为是照抄 `connection_params.rs` 的（否则会出现假阴性）：
 *
 * - 先整体 `trim()`（Rust `parse` 里 `raw.trim()`）；
 * - 先切 `#`（fragment），再切 `?`（query）——`#` 之后的 `?` 属于 fragment；
 * - scheme 取第一个 `:` 之前、且不含 `/` 的那段，并小写化；
 * - 只有 `//` 开头才有 authority；
 * - query 按 `&` 切段、取段内**第一个** `=`、空段丢弃、`=x`（空键）整段丢弃、
 *   无 `=` 的段值为空串、键与值都做百分号解码（`+` → 空格，非法 `%XX` 按字面 `%`）、
 *   **同名键后者胜**；
 * - 取到值后 `trim()`，空串视为"没有这个参数"。
 */
object PairingUrl {

    /** 放行的 scheme。`wss` 必须在内：Rust 的 `parse` 明确接受 `https` 与 `wss`。 */
    private val ACCEPTED_SCHEMES = setOf("http", "https", "wss")

    /**
     * 前置筛查。[Ok] 只代表"可以拿去试"，不代表引擎一定收。
     */
    fun preflight(raw: String): PreflightResult {
        val parts = split(raw) ?: return PreflightResult.Rejected(RejectReason.EMPTY)
        if (parts.scheme.isEmpty()) return PreflightResult.Rejected(RejectReason.NOT_A_URL)
        if (parts.scheme !in ACCEPTED_SCHEMES) {
            return PreflightResult.Rejected(RejectReason.SCHEME_NOT_HTTP)
        }
        if (parts.host.isEmpty()) return PreflightResult.Rejected(RejectReason.MISSING_HOST)
        val params = queryParams(parts.query)
        // `sid` 只查"解码 + trim 后非空"——这正是 Rust `_get` 的语义
        // （空串视为不存在），不是额外发明的规则。
        if (params.value("sid").isEmpty()) return PreflightResult.Rejected(RejectReason.MISSING_SID)
        // 3.16.1 起链接不再带 `hash`（认证改为 OAuth token），所以这里不再要求它。
        // 旧链接带 `hash` 也照常放行；真正的认证材料由 PairingViewModel 在登录后注入。
        return PreflightResult.Ok(parts.normalized)
    }

    /**
     * 取 query 里的 `name` 做默认显示名（对应 Rust 的 `device_name`）。
     *
     * 语义与 Rust `_get(uri, "name")` 一致：百分号解码 → `trim()` → 空串当没有。
     */
    fun deviceLabel(raw: String): String? =
        split(raw)?.let { queryParams(it.query).value("name").ifEmpty { null } }

    /**
     * 旧链接是否带 `hash`。
     *
     * 3.16.1 起新链接不带 `hash`，必须先用 OAuth 登录拿 JWT；旧链接可以继续走 HMAC proof。
     * 缺 host / 缺 sid 时返回 `false`（这种串本来就会被 preflight 拒掉）。
     */
    fun hasPassHash(raw: String): Boolean =
        split(raw)?.let { queryParams(it.query).value("hash").isNotEmpty() } ?: false

    /**
     * 设备去重键：`host` + `sid`（与持久化层的规则一致——同 host 同 sid 视为同一台设备）。
     *
     * - host 用小写形式（Rust 解析时就把 host 小写化了，引擎看到的也是小写）；
     * - sid 用解码 + trim 后的值（同上，引擎看到的 `device_sid` 就是这个）；
     * - 刻意**不含端口**：规则就是 host + sid，带上端口会让同一台设备按端口裂成两条；
     * - 缺 host 或缺 sid 时返回 null（这种串本来就进不了配对流程）。
     */
    fun dedupeKey(raw: String): String? {
        val parts = split(raw) ?: return null
        val host = asciiLower(parts.host)
        val sid = queryParams(parts.query).value("sid")
        if (host.isEmpty() || sid.isEmpty()) return null
        // `|` 不是 RFC 3986 reg-name 的合法字符，host 里不会出现，故不会撞键。
        return "$host|$sid"
    }

    // ------------------------------------------------------------------
    // 内部实现：一个最小 URI 切分器，逐条对齐 connection_params.rs 的 `Uri::parse`
    // ------------------------------------------------------------------

    private class Parts(
        /** 已小写；空串 = 没有 scheme。 */
        val scheme: String,
        /** 含末尾 `@`，原样保留（Rust 解析后丢弃 userinfo，这里只为规范化时原样带回去）。 */
        val userinfo: String,
        /** 未小写的 host；IPv6 保留方括号。 */
        val host: String,
        /** 含前导 `:`，原样（含空端口 `host:` 的情况）。 */
        val portSuffix: String,
        /** path + `?query` + `#fragment`，**原样**（不重排、不丢弃 query 参数）。 */
        val rest: String,
        /** `?` 之后、`#` 之前的原样文本；无 query 时为 null。 */
        val query: String?,
    ) {
        /**
         * 只抹平显然无意义的差异：scheme / host 小写化。query 与 fragment 一个字都不动
         * ——Rust 是按原串解析的。
         */
        val normalized: String
            get() = buildString {
                append(scheme)
                append("://")
                append(userinfo)
                append(asciiLower(host))
                append(portSuffix)
                append(rest)
            }
    }

    /** 切分。[null] 表示输入 trim 后为空串。 */
    private fun split(raw: String): Parts? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        // 1) fragment 先切（`#` 之后的 `?` 属于 fragment），再切 query。
        val hashAt = trimmed.indexOf('#')
        val beforeFragment = if (hashAt >= 0) trimmed.substring(0, hashAt) else trimmed
        val questionAt = beforeFragment.indexOf('?')
        val beforeQuery = if (questionAt >= 0) beforeFragment.substring(0, questionAt) else beforeFragment
        val query = if (questionAt >= 0) beforeFragment.substring(questionAt + 1) else null

        // 2) scheme：第一个 `:` 之前且不含 `/` 才可能是 scheme（Dart `_makeScheme` 的前置条件）。
        val colonAt = beforeQuery.indexOf(':')
        val scheme = if (colonAt > 0 && !beforeQuery.substring(0, colonAt).contains('/')) {
            asciiLower(beforeQuery.substring(0, colonAt))
        } else {
            ""
        }

        // 3) authority：只有 `//` 开头才有；没有就是空 host。
        val authorityStart = if (scheme.isNotEmpty() && beforeQuery.startsWith("//", colonAt + 1)) {
            colonAt + 3
        } else {
            -1
        }
        val authority = if (authorityStart >= 0) {
            // 在 beforeQuery 上取（Rust 也是在已切掉 ?/# 的串上找第一个 '/'）。
            val body = beforeQuery.substring(authorityStart)
            val slashAt = body.indexOf('/')
            if (slashAt >= 0) body.substring(0, slashAt) else body
        } else {
            ""
        }

        // 4) rest：从 authority 结束处取到原串末尾——这样 path / query / fragment 全部原样保留。
        val rest = if (authorityStart >= 0) trimmed.substring(authorityStart + authority.length) else ""

        val at = authority.lastIndexOf('@')
        val userinfo = if (at >= 0) authority.substring(0, at + 1) else ""
        val hostPort = if (at >= 0) authority.substring(at + 1) else authority
        val (host, portSuffix) = splitHostPort(hostPort)

        return Parts(scheme, userinfo, host, portSuffix, rest, query)
    }

    /** 对应 Rust `split_host_port` 的方括号 IPv6 / 普通 reg-name 两条分支。 */
    private fun splitHostPort(hostPort: String): Pair<String, String> {
        if (hostPort.startsWith("[")) {
            val end = hostPort.indexOf(']')
            // 缺 `]` 时 Rust 直接判非法（parse → None），这里按整段当 host，宁可宽松。
            if (end < 0) return hostPort to ""
            return hostPort.substring(0, end + 1) to hostPort.substring(end + 1)
        }
        val colon = hostPort.lastIndexOf(':')
        if (colon < 0) return hostPort to ""
        return hostPort.substring(0, colon) to hostPort.substring(colon)
    }

    /**
     * 对应 Rust `split_query_string`：空段丢弃、`=x` 整段丢弃、无 `=` 取空串、
     * 键与值都解码、同名键后者胜。
     */
    private fun queryParams(query: String?): Map<String, String> {
        if (query == null) return emptyMap()
        val map = HashMap<String, String>()
        for (segment in query.split('&')) {
            if (segment.isEmpty()) continue
            val eq = segment.indexOf('=')
            when {
                eq == 0 -> Unit // 空键（`=x`）整段丢弃
                eq < 0 -> map[decodeQueryComponent(segment)] = ""
                else -> map[decodeQueryComponent(segment.substring(0, eq))] =
                    decodeQueryComponent(segment.substring(eq + 1))
            }
        }
        return map
    }

    /** Rust `_get`：解码后的值 `trim()`，空串当不存在。 */
    private fun Map<String, String>.value(key: String): String = this[key].orEmpty().trim()

    /**
     * 对应 Rust `decode_query_component`：`%XX` 按字节解码、`+` → 空格、
     * 非法/截断的转义按字面 `%` 处理并前进一个字节。
     *
     * 唯一的放宽：非法 UTF-8 在 Rust 那里会让 `parse` 返回 None，这里退化成替换字符
     * （多试一次而已，不会误拒）。
     */
    private fun decodeQueryComponent(raw: String): String {
        if (raw.indexOf('%') < 0 && raw.indexOf('+') < 0) return raw
        val src = raw.toByteArray(Charsets.UTF_8)
        val out = ByteArray(src.size)
        var written = 0
        var i = 0
        while (i < src.size) {
            val byte = src[i].toInt() and 0xFF
            val hi = if (byte == PERCENT && i + 2 < src.size) hexValue(src[i + 1]) else -1
            val lo = if (hi >= 0) hexValue(src[i + 2]) else -1
            when {
                hi >= 0 && lo >= 0 -> {
                    out[written++] = (hi * 16 + lo).toByte()
                    i += 3
                }
                byte == PLUS -> {
                    out[written++] = SPACE.toByte()
                    i += 1
                }
                else -> {
                    out[written++] = src[i]
                    i += 1
                }
            }
        }
        return String(out, 0, written, Charsets.UTF_8)
    }

    private fun hexValue(byte: Byte): Int {
        val c = byte.toInt() and 0xFF
        return when (c) {
            in 0x30..0x39 -> c - 0x30 // 0-9
            in 0x61..0x66 -> c - 0x61 + 10 // a-f
            in 0x41..0x46 -> c - 0x41 + 10 // A-F
            else -> -1
        }
    }

    /** 对应 Rust 的 `to_ascii_lowercase()`：**只**动 A-Z，非 ASCII 一律不碰。 */
    private fun asciiLower(text: String): String {
        var needsLowering = false
        for (c in text) if (c in 'A'..'Z') { needsLowering = true; break }
        if (!needsLowering) return text
        return buildString(text.length) {
            for (c in text) append(if (c in 'A'..'Z') c + 32 else c)
        }
    }

    private const val PERCENT = 0x25 // '%'
    private const val PLUS = 0x2B // '+'
    private const val SPACE = 0x20 // ' '
}

/** [PairingUrl.preflight] 的结果。 */
sealed interface PreflightResult {
    /**
     * 可以拿去调引擎建会话。[normalized] 只抹平了 scheme / host 大小写与首尾空白；
     * 引擎仍可能回 `badInput`——那个错误要原样给用户看。
     */
    data class Ok(val normalized: String) : PreflightResult

    /** 连试都不用试。[reason] 的文案由界面层决定。 */
    data class Rejected(val reason: RejectReason) : PreflightResult
}

/** 前置筛查的拒绝原因（只给语义，不给文案）。 */
enum class RejectReason {
    /** 空串，或只有空白。 */
    EMPTY,

    /** 连 `scheme:` 都没有：纯文本、`host/path?...`、`//host/...` 都算。 */
    NOT_A_URL,

    /** 有 scheme，但不是 `http` / `https` / `wss`（如 `file:`、`ftp:`、`ws:`）。 */
    SCHEME_NOT_HTTP,

    /** 没有 host（`https:///x?...`、`https://:8443/x?...`、`https:?sid=...`）。 */
    MISSING_HOST,

    /** 没有可用的 `sid`（缺失，或解码 + trim 后为空）。 */
    MISSING_SID,

    /**
     * 看起来是别的站点的链接。
     *
     * **当前 [PairingUrl.preflight] 永不返回它**：Rust 的 `ZemoteConnectionParams::parse`
     * 根本不看 host，任何 host 只要 scheme / `sid` / `hash` / `t` 成立就能解析，
     * 所以"host 不是 zcode.z.ai 就拒"必然是假阴性——用户拿别的域名/自建中继的链接
     * 也会被拦死。保留该枚举值是为了让界面层将来能表达这类提示，
     * 以及在真有权威白名单时在这里接入。
     */
    NOT_ZEMOTE_LINK,
}
