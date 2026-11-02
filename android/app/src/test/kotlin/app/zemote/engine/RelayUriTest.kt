package app.zemote.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 重连换 terminal token 时的 URL 改写。
 *
 * 只替换 `token` 参数，`mid` 等其余参数与顺序必须原样保留；token 字符集
 * `[A-Za-z0-9_-]` 不需要百分号编码。
 */
class RelayUriTest {

    private val base = "wss://zcode.z.ai/api/v1/remote/ws2"

    @Test
    fun replacesTokenAndKeepsFollowingParams() {
        assertEquals(
            "$base?token=NEW&mid=m1",
            RelayUri.withToken("$base?token=OLD&mid=m1", "NEW"),
        )
    }

    @Test
    fun replacesTokenWhenItIsNotTheFirstParam() {
        assertEquals(
            "$base?mid=m1&token=NEW",
            RelayUri.withToken("$base?mid=m1&token=OLD", "NEW"),
        )
    }

    @Test
    fun leavesUriWithoutTokenUntouched() {
        val uri = "$base?mid=m1"
        assertEquals(uri, RelayUri.withToken(uri, "NEW"))
    }

    @Test
    fun tokenCharactersAreNotEncoded() {
        assertEquals(
            "$base?token=abc_DEF-123&mid=m1",
            RelayUri.withToken("$base?token=OLD&mid=m1", "abc_DEF-123"),
        )
    }
}
