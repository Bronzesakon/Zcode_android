package app.zemote.auth

import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.16.1 OAuth 授权 URL / state / 回调的纯逻辑测试。 */
class OAuthFlowTest {

    private fun query(url: String, key: String): String? {
        val raw = url.substringAfter('?', "")
        for (pair in raw.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            if (pair.substring(0, eq) == key) {
                return URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
            }
        }
        return null
    }

    @Test
    fun `zai authorize url matches web client`() {
        val request = OAuthFlow.create(OAuthProvider.ZAI, nonce = "nonce-1")
        assertEquals(OAuthConfig.ZAI_AUTHORIZE_URL, request.authorizeUrl.substringBefore('?'))
        assertEquals(OAuthConfig.REDIRECT_URI, query(request.authorizeUrl, "redirect_uri"))
        assertEquals("code", query(request.authorizeUrl, "response_type"))
        assertEquals(OAuthConfig.ZAI_CLIENT_ID, query(request.authorizeUrl, "client_id"))
        assertEquals(request.state, query(request.authorizeUrl, "state"))
    }

    @Test
    fun `bigmodel authorize url matches web client`() {
        val request = OAuthFlow.create(OAuthProvider.BIGMODEL, nonce = "nonce-2")
        assertEquals(OAuthConfig.BIGMODEL_AUTHORIZE_URL, request.authorizeUrl.substringBefore('?'))
        assertEquals(OAuthConfig.REDIRECT_URI, query(request.authorizeUrl, "redirect"))
        assertEquals(OAuthConfig.BIGMODEL_APP_ID, query(request.authorizeUrl, "appId"))
        assertEquals(request.state, query(request.authorizeUrl, "state"))
    }

    @Test
    fun `state round trips nonce and return url`() {
        val state = OAuthFlow.encodeState("nonce-3", OAuthConfig.REDIRECT_URI)
        val decoded = OAuthFlow.decodeState(state)!!
        assertEquals("nonce-3", decoded["nonce"]!!.toString().trim('"'))
        assertEquals(OAuthConfig.REDIRECT_URI, decoded["app_return_to"]!!.toString().trim('"'))
    }

    @Test
    fun `callback detection matches web path and params`() {
        val state = OAuthFlow.encodeState("n", OAuthConfig.REDIRECT_URI)
        assertTrue(
            OAuthFlow.isCallback("https://zcode.z.ai/remote/v4?code=c&state=$state"),
        )
        assertTrue(
            OAuthFlow.isCallback("https://zcode.z.ai/remote/v4/?authCode=c&state=$state"),
        )
        assertTrue(
            OAuthFlow.isCallback("https://zcode.z.ai/remote/v4?error=denied&state=$state"),
        )
        // 缺 state / 缺 code / 错 host / 错 path 都不是回调。
        assertFalse(OAuthFlow.isCallback("https://zcode.z.ai/remote/v4?code=c"))
        assertFalse(OAuthFlow.isCallback("https://zcode.z.ai/remote/v4?state=$state"))
        assertFalse(OAuthFlow.isCallback("https://evil.example.com/remote/v4?code=c&state=$state"))
        assertFalse(OAuthFlow.isCallback("https://zcode.z.ai/other?code=c&state=$state"))
        assertFalse(OAuthFlow.isCallback("http://zcode.z.ai/remote/v4?code=c&state=$state"))
    }

    @Test
    fun `callback parses code and verifies nonce`() {
        val state = OAuthFlow.encodeState("nonce-ok", OAuthConfig.REDIRECT_URI)
        val url = "https://zcode.z.ai/remote/v4?code=CODE-1&state=$state"
        assertEquals(OAuthCallback.Code("CODE-1"), OAuthFlow.parseCallback(url, "nonce-ok"))
        assertTrue(OAuthFlow.parseCallback(url, "other") is OAuthCallback.Invalid)
    }

    @Test
    fun `authCode overrides code when both are present`() {
        val state = OAuthFlow.encodeState("nonce-ok", OAuthConfig.REDIRECT_URI)
        assertEquals(
            OAuthCallback.Code("AUTH-CODE"),
            OAuthFlow.parseCallback(
                "https://zcode.z.ai/remote/v4?code=PLAIN-CODE&authCode=AUTH-CODE&state=$state",
                "nonce-ok",
            ),
        )
    }

    @Test
    fun `callback supports bigmodel authCode and error`() {
        val state = OAuthFlow.encodeState("nonce-ok", OAuthConfig.REDIRECT_URI)
        assertEquals(
            OAuthCallback.Code("BM-CODE"),
            OAuthFlow.parseCallback(
                "https://zcode.z.ai/remote/v4?authCode=BM-CODE&state=$state",
                "nonce-ok",
            ),
        )
        assertEquals(
            OAuthCallback.Failure("access_denied"),
            OAuthFlow.parseCallback(
                "https://zcode.z.ai/remote/v4?error=access_denied&state=$state",
                "nonce-ok",
            ),
        )
    }
}
