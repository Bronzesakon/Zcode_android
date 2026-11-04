package app.zemote.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PairingUrl] 的表驱动单测。
 *
 * 最重要的一组是 [notRejectedWhenRustWouldParseIt]：它把 `connection_params.rs` 里
 * `ZemoteConnectionParams::parse` 的**正向**用例（以及若干故意"更宽松"的用例）搬过来，
 * 断言 preflight 不拒绝它们。前置筛查的唯一硬约束是"只许比 Rust 宽松"，
 * 这组测试就是防"写得太严"的守门员。
 */
class PairingUrlTest {

    // ------------------------------------------------------------------
    // 1. Rust 能解析（或 Rust 会拒但属于"交给 Rust 判"）的串，preflight 必须放行
    // ------------------------------------------------------------------

    private val mustNotBeRejected = listOf(
        // ---- 从 connection_params.rs 搬来的正向 parse 用例（6 条） ----
        "https://zcode.z.ai/remote/v4?sid=s&hash=h&t=1&mid=m",
        "https://zcode.z.ai:8443/remote/v4?sid=s&hash=h&t=1",
        "https://zcode.z.ai/remote/v4?sid=s&hash=h&t=1",
        "https://zcode.z.ai/remote/v4?sid= SID &hash= HASH &t=1",
        "https://zcode.z.ai/remote/v4?sid=s&hash=h&t=1&mid=&name=",
        "https://zcode.z.ai/remote/v4?sid=s&hash=h&t=1&theme=dark",
        // ---- 3.16.1 新格式：无 hash / t，认证改为 OAuth token ----
        "https://zcode.z.ai/remote/v4?app_version=3.16.1&sid=s&mid=m&name=Mac",
        "https://zcode.z.ai/remote/v4?app_version=3.16.1&sid=s",
        // ---- `wss` 也是 parse 明确接受的 scheme（拒它就是假阴性） ----
        "wss://zcode.z.ai/remote/v4?sid=s&hash=h&t=1",
        // ---- 下面这些 Rust 会拒，但那是"交给 Rust 判"的取值合法性，preflight 不许多嘴 ----
        "http://localhost:3000/remote/v4?sid=s&hash=h&t=1", // scheme 不是 https/wss
        "https://zcode.z.ai/remote/v4?sid=s&hash=h&t=abc", // t 不是数字
        "https://zcode.z.ai/remote/v4?sid=s&hash=h", // 缺 t
        // ---- Rust 完全不看 host：别的站点也必须放行（拒它 = 假阴性） ----
        "https://evil.example.com/remote/v4?sid=s&hash=h&t=1",
        "https://127.0.0.1:99999/remote/v4?sid=s&hash=h&t=1", // 端口越界，Rust 自己会判
        // ---- 任务要求的边界：大小写、首尾空白、fragment、参数顺序、百分号编码 ----
        "HTTPS://ZCODE.Z.AI/remote/v4?sid=s&hash=h&t=1",
        "  https://zcode.z.ai/remote/v4?sid=s&hash=h&t=1  ",
        "https://zcode.z.ai/remote/v4?sid=s&hash=h&t=1#frag",
        "https://zcode.z.ai/remote/v4?sid=s&hash=h&t=1#frag?sid=nope&hash=nope",
        "https://zcode.z.ai/remote/v4?t=1&hash=h&sid=s",
        "https://zcode.z.ai/remote/v4?sid=s%3D1&hash=h%26x&t=1&name=Desk%20Top",
        "https://zcode.z.ai/remote/v4?%73id=%73&hash=h&t=1", // 百分号编码的**键**
        "https://zcode.z.ai/remote/v4?sid=x&sid=y&hash=h&t=1", // 重复键后者胜
        "https://zcode.z.ai/remote/v4?sid=s&hash=h&t=1&mid=",
        "https://[::1]:8443/remote/v4?sid=s&hash=h&t=1",
        "https://user:pw@zcode.z.ai/remote/v4?sid=s&hash=h&t=1",
        "https://zcode.z.ai/remote/v4?sid=s&hash=h&t=1&name=" + "a".repeat(100_000), // 超长
    )

    @Test
    fun notRejectedWhenRustWouldParseIt() {
        for (url in mustNotBeRejected) {
            val result = PairingUrl.preflight(url)
            assertTrue(
                "preflight 不该拒绝这个串（比 Rust 严 = 假阴性）：$url -> $result",
                result is PreflightResult.Ok,
            )
        }
    }

    // ------------------------------------------------------------------
    // 2. 该拒的必须拒
    // ------------------------------------------------------------------

    private val mustBeRejected = mapOf(
        "" to RejectReason.EMPTY,
        "   " to RejectReason.EMPTY,
        "\n\t " to RejectReason.EMPTY,
        "just some plain text" to RejectReason.NOT_A_URL,
        "zcode.z.ai/remote/v4?sid=s&hash=h" to RejectReason.NOT_A_URL,
        "//zcode.z.ai/remote/v4?sid=s&hash=h" to RejectReason.NOT_A_URL,
        "https//zcode.z.ai/remote/v4?sid=s&hash=h" to RejectReason.NOT_A_URL,
        "ftp://zcode.z.ai/remote/v4?sid=s&hash=h" to RejectReason.SCHEME_NOT_HTTP,
        "ws://zcode.z.ai/ws?sid=s&hash=h" to RejectReason.SCHEME_NOT_HTTP,
        "file:///etc/passwd" to RejectReason.SCHEME_NOT_HTTP,
        "file:///etc/passwd?sid=s&hash=h" to RejectReason.SCHEME_NOT_HTTP,
        "mailto:someone@example.com?sid=s&hash=h" to RejectReason.SCHEME_NOT_HTTP,
        "https:///remote/v4?sid=s&hash=h&t=1" to RejectReason.MISSING_HOST,
        "https://:8443/remote/v4?sid=s&hash=h&t=1" to RejectReason.MISSING_HOST,
        "https:?sid=s&hash=h&t=1" to RejectReason.MISSING_HOST,
        "https://" to RejectReason.MISSING_HOST,
        "https://zcode.z.ai/remote/v4?hash=h&t=1" to RejectReason.MISSING_SID,
        "https://zcode.z.ai/remote/v4" to RejectReason.MISSING_SID,
        "https://zcode.z.ai/remote/v4?sid=&hash=h&t=1" to RejectReason.MISSING_SID,
        "https://zcode.z.ai/remote/v4?sid=%20&hash=h&t=1" to RejectReason.MISSING_SID,
        "https://zcode.z.ai/remote/v4?sid=x&sid=&hash=h&t=1" to RejectReason.MISSING_SID,
        "https://zcode.z.ai/remote/v4?=sid&hash=h&t=1" to RejectReason.MISSING_SID,
        "https://zcode.z.ai/remote/v4#sid=s&hash=h" to RejectReason.MISSING_SID, // query 在 fragment 里
    )

    @Test
    fun rejectsOnlyForTheStatedReasons() {
        for ((url, expected) in mustBeRejected) {
            val result = PairingUrl.preflight(url)
            assertEquals("拒绝原因不符：$url", expected, (result as? PreflightResult.Rejected)?.reason)
        }
    }

    // ------------------------------------------------------------------
    // 3. 规范化：只抹平 scheme / host 大小写与首尾空白，query 一个字都不动
    // ------------------------------------------------------------------

    private val normalizations = mapOf(
        "  HTTPS://ZCode.Z.AI/remote/v4?sid=S&hash=H&t=1  " to
            "https://zcode.z.ai/remote/v4?sid=S&hash=H&t=1",
        "https://zcode.z.ai/remote/v4?t=1&hash=h&sid=s" to
            "https://zcode.z.ai/remote/v4?t=1&hash=h&sid=s",
        "https://User@ZCode.Z.ai:8443/remote/v4?sid=s&hash=h" to
            "https://User@zcode.z.ai:8443/remote/v4?sid=s&hash=h",
        "https://[::1]:8443/remote/v4?sid=s&hash=h" to
            "https://[::1]:8443/remote/v4?sid=s&hash=h",
        "https://ZCODE.Z.AI/remote/v4?sid=s%3D1&hash=h#Frag" to
            "https://zcode.z.ai/remote/v4?sid=s%3D1&hash=h#Frag",
        "http://LOCALHOST:3000/remote/v4?sid=s&hash=h" to
            "http://localhost:3000/remote/v4?sid=s&hash=h",
    )

    @Test
    fun normalizesSchemeAndHostOnly() {
        for ((raw, expected) in normalizations) {
            assertEquals(expected, (PairingUrl.preflight(raw) as PreflightResult.Ok).normalized)
        }
    }

    @Test
    fun normalizationIsIdempotent() {
        for (url in mustNotBeRejected + normalizations.keys) {
            val once = (PairingUrl.preflight(url) as PreflightResult.Ok).normalized
            val twice = (PairingUrl.preflight(once) as PreflightResult.Ok).normalized
            assertEquals("规范化不幂等：$url", once, twice)
        }
    }

    // ------------------------------------------------------------------
    // 4. deviceLabel（对应 Rust 的 device_name）
    // ------------------------------------------------------------------

    @Test
    fun deviceLabelReadsNameParameter() {
        assertEquals("Desk Top", PairingUrl.deviceLabel("https://zcode.z.ai/remote/v4?sid=s&hash=h&name=Desk%20Top"))
        assertEquals("My PC", PairingUrl.deviceLabel("https://zcode.z.ai/remote/v4?sid=s&hash=h&name=  My PC  "))
        assertEquals("John Smith", PairingUrl.deviceLabel("https://zcode.z.ai/remote/v4?sid=s&hash=h&name=John+Smith"))
        assertEquals("甲", PairingUrl.deviceLabel("https://zcode.z.ai/remote/v4?sid=s&hash=h&name=%E7%94%B2"))
        // 空值 / 缺失 / 非 URL 一律 null（Rust 的 `_get` 把空串当不存在）
        assertNull(PairingUrl.deviceLabel("https://zcode.z.ai/remote/v4?sid=s&hash=h&name="))
        assertNull(PairingUrl.deviceLabel("https://zcode.z.ai/remote/v4?sid=s&hash=h&name=%20"))
        assertNull(PairingUrl.deviceLabel("https://zcode.z.ai/remote/v4?sid=s&hash=h"))
        assertNull(PairingUrl.deviceLabel("plain text"))
        assertNull(PairingUrl.deviceLabel(""))
    }

    // ------------------------------------------------------------------
    // 5. dedupeKey（host + sid）
    // ------------------------------------------------------------------

    @Test
    fun dedupeKeyIsHostPlusSid() {
        val base = "https://ZCode.Z.AI/remote/v4?sid=ABC&hash=h&t=1"
        assertEquals("zcode.z.ai|ABC", PairingUrl.dedupeKey(base))
        // host 大小写不敏感
        assertEquals(PairingUrl.dedupeKey(base), PairingUrl.dedupeKey("https://zcode.z.ai/remote/v4?sid=ABC&hash=h&t=1"))
        // 同 host 同 sid 视为同一台设备 —— 路径/端口/hash 都不参与（按约定的去重规则）
        assertEquals(PairingUrl.dedupeKey(base), PairingUrl.dedupeKey("https://zcode.z.ai:8443/other?sid=ABC&hash=zzz"))
        // sid 走解码 + trim（与引擎看到的 device_sid 一致）
        assertEquals(PairingUrl.dedupeKey(base), PairingUrl.dedupeKey("https://zcode.z.ai/remote/v4?sid=%20ABC%20&hash=h"))
        // 不同 sid / 不同 host 必须分开
        assertNotEquals(PairingUrl.dedupeKey(base), PairingUrl.dedupeKey("https://zcode.z.ai/remote/v4?sid=XYZ&hash=h"))
        assertNotEquals(PairingUrl.dedupeKey(base), PairingUrl.dedupeKey("https://other.example.com/remote/v4?sid=ABC&hash=h"))
    }

    @Test
    fun dedupeKeyIsNullWithoutHostOrSid() {
        assertNull(PairingUrl.dedupeKey("https://zcode.z.ai/remote/v4?hash=h"))
        assertNull(PairingUrl.dedupeKey("https://zcode.z.ai/remote/v4?sid=&hash=h"))
        assertNull(PairingUrl.dedupeKey("https:///remote/v4?sid=s&hash=h"))
        assertNull(PairingUrl.dedupeKey("plain text"))
        assertNull(PairingUrl.dedupeKey(""))
    }

    // ------------------------------------------------------------------
    // 6. NOT_ZEMOTE_LINK 是预留分支：preflight 永不返回它
    // ------------------------------------------------------------------

    @Test
    fun foreignHostIsNotReportedAsNotZemoteLink() {
        val result = PairingUrl.preflight("https://not-zcode.example.org/remote/v4?sid=s&hash=h&t=1")
        assertTrue("host 不是 zcode.z.ai 也不许拒（Rust 不看 host）", result is PreflightResult.Ok)
        assertTrue(RejectReason.NOT_ZEMOTE_LINK in RejectReason.values())
    }
}
