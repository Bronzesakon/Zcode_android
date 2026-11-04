package app.zemote.auth

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [RemoteTokenClient]：3.16.1 的 OAuth JWT → ws2 terminal token 交换。
 *
 * 用 OkHttp `Interceptor` 直接返回响应（不引 MockWebServer）：要验的就是请求形状
 * （`Authorization: Bearer`、body `{"role":"terminal"}`）与响应解析
 * （`data.token` / `data.expires_in`）。
 */
class RemoteTokenClientTest {

    private fun http(
        code: Int,
        responseBody: String,
        onRequest: (Request, String) -> Unit = { _, _ -> },
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val body = request.body?.let { requestBody ->
                Buffer().also(requestBody::writeTo).readUtf8()
            }.orEmpty()
            onRequest(request, body)
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "Unauthorized")
                .body(responseBody.toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    @Test
    fun `用 OAuth JWT 换 terminal token 并解析 expires_in`() {
        var auth: String? = null
        var body: String? = null
        var url: String? = null
        val http = http(
            code = 200,
            responseBody = """
                {"code":0,"msg":"ok","data":{"token":"abcdefghijklmnopqrstuA","expires_in":3600}}
            """.trimIndent(),
        ) { request, requestBody ->
            auth = request.header("Authorization")
            body = requestBody
            url = request.url.toString()
        }
        val client = RemoteTokenClient(client = http, baseUrl = { "https://test" })

        val token = runBlocking { client.exchange("zcode.z.ai", "jwt-1") }

        assertEquals("abcdefghijklmnopqrstuA", token.token)
        assertEquals(3600L, token.expiresInSecs)
        assertEquals("https://test/api/v1/remote/token", url)
        assertEquals("Bearer jwt-1", auth)
        assertEquals("""{"role":"terminal"}""", body)
    }

    private fun expectFailure(body: String, httpCode: Int = 200): RemoteTokenException {
        val client = RemoteTokenClient(
            client = http(code = httpCode, responseBody = body),
            baseUrl = { "https://test" },
        )
        try {
            runBlocking { client.exchange("zcode.z.ai", "jwt-1") }
        } catch (e: RemoteTokenException) {
            return e
        }
        throw AssertionError("应该抛 RemoteTokenException")
    }

    @Test
    fun `token 不符合 22 字符 schema 时失败`() {
        val e = expectFailure("""{"code":0,"data":{"token":"short","expires_in":3600}}""")
        assertEquals("relay-unavailable", e.reason)
        assertTrue(e.message.orEmpty().contains("token"))
    }

    @Test
    fun `expires_in 缺失或非正数时失败`() {
        assertEquals(
            "relay-unavailable",
            expectFailure(
                """{"code":0,"data":{"token":"abcdefghijklmnopqrstuA"}}""",
            ).reason,
        )
        assertEquals(
            "relay-unavailable",
            expectFailure(
                """{"code":0,"data":{"token":"abcdefghijklmnopqrstuA","expires_in":0}}""",
            ).reason,
        )
    }

    @Test
    fun `缺 code 按失败处理`() {
        val e = expectFailure(
            """{"data":{"token":"abcdefghijklmnopqrstuA","expires_in":3600}}""",
        )
        assertEquals("relay-unavailable", e.reason)
    }

    @Test
    fun `401 映射为 auth-required`() {
        val client = RemoteTokenClient(
            client = http(code = 401, responseBody = ""),
            baseUrl = { "https://test" },
        )

        try {
            runBlocking { client.exchange("zcode.z.ai", "jwt-1") }
            fail("应该抛 RemoteTokenException")
        } catch (e: RemoteTokenException) {
            assertTrue("401 必须被识别成登录失效：${e.reason}", e.authRequired)
        }
    }
}
