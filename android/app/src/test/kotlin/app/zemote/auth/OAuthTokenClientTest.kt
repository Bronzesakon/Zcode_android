package app.zemote.auth

import java.util.Base64
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 授权码 → JWT 的响应归一化。
 *
 * 网页 `A9` 同时接受驼峰与蛇形 user；`Y5n` 给裸 base64 头像补 `data:` 前缀；本地过期只认
 * JWT `exp`（不是 `expires_in`）。这些都在 [OAuthTokenClient] 里做，用 OkHttp 拦截器直接
 * 返回固定响应，不碰网络。
 */
class OAuthTokenClientTest {

    private fun jwt(expSeconds: Long?): String {
        fun b64(text: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
        val payload = if (expSeconds == null) "{}" else """{"exp":$expSeconds}"""
        return "${b64("""{"alg":"none"}""")}.${b64(payload)}.sig"
    }

    private fun http(responseBody: String): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responseBody.toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    @Test
    fun `camelCase user and bare base64 avatar are normalized`() = runBlocking {
        val token = jwt(1_000)
        val body = """
            {"code":0,"msg":"ok","data":{
              "token":"$token",
              "zai":{"access_token":"at"},
              "user":{"id":"u1","username":"alice","displayName":"Alice",
                      "avatarUrl":"iVBORw0KGgoAAAANSUhEUg"},
              "expires_in":3600
            }}
        """.trimIndent()

        val session = OAuthTokenClient(client = http(body))
            .exchange(OAuthProvider.ZAI, code = "c", state = "s")

        assertEquals("Alice", session.displayName)
        assertEquals("data:image/png;base64,iVBORw0KGgoAAAANSUhEUg", session.avatarUrl)
        // 只认 JWT exp；expires_in=3600 不参与。
        assertEquals(1_000_000L, session.expiresAt)
    }

    @Test
    fun `snake case user falls back to name then email then user_id`() = runBlocking {
        val token = jwt(null)
        val body = """
            {"code":0,"data":{
              "token":"$token",
              "bigmodel":{"access_token":"at"},
              "user":{"user_id":"u-9","name":"  Bob  ","avatar":"https://x/a.png"}
            }}
        """.trimIndent()

        val session = OAuthTokenClient(client = http(body))
            .exchange(OAuthProvider.BIGMODEL, code = "c", state = "s")

        assertEquals("Bob", session.displayName)
        assertEquals("https://x/a.png", session.avatarUrl)
        assertEquals(null, session.expiresAt)
    }
}
