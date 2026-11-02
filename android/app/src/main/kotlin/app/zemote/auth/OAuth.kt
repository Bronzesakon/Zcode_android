package app.zemote.auth

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Web 远控（3.16.1+）支持的登录平台。
 *
 * 线上文案与网页端 `loginAction` / `loginRegion` 一致：
 * - `zai`：全球，授权页在 `chat.z.ai`；
 * - `bigmodel`：中国，授权页在 `bigmodel.cn`。
 *
 * 两端账号数据不互通，必须选与电脑端相同的平台。
 */
enum class OAuthProvider(
    val wire: String,
    val actionLabel: String,
    val regionLabel: String,
) {
    ZAI("zai", "连接 Z.ai 继续使用", "全球"),
    BIGMODEL("bigmodel", "连接 BigModel 继续使用", "中国"),
    ;

    companion object {
        fun fromWire(value: String?): OAuthProvider? =
            entries.firstOrNull { it.wire == value }
    }
}

/**
 * 3.16.1 网页端硬编码的 OAuth 参数（见 `Reference_web/3.16.1/assets/index-DbEHT6Gg.js`
 * 的 `VITE_ZAI_OAUTH_*` / `VITE_BIGMODEL_OAUTH_*` 与 `validation-BBf1FA1a.js` 的 `Vd`）。
 */
object OAuthConfig {
    /** 网页端 `webRemoteCallbackUrl`：`${base}/remote/v4`。 */
    const val REDIRECT_URI: String = "https://zcode.z.ai/remote/v4"

    /** Z.ai 授权入口（`${VITE_ZAI_OAUTH_ORIGIN}/api/oauth/authorize`）。 */
    const val ZAI_AUTHORIZE_URL: String = "https://chat.z.ai/api/oauth/authorize"

    /** BigModel 授权入口（`${VITE_BIGMODEL_OAUTH_ORIGIN}/login`）。 */
    const val BIGMODEL_AUTHORIZE_URL: String = "https://bigmodel.cn/login"

    /** Z.ai OAuth client id（网页端 `VITE_ZAI_OAUTH_CLIENT_ID`）。 */
    const val ZAI_CLIENT_ID: String = "client_P8X5CMWmlaRO9gyO-KSqtg"

    /** BigModel 授权 appId（网页端 `VITE_BIGMODEL_OAUTH_APP_ID`，缺省 `zcode`）。 */
    const val BIGMODEL_APP_ID: String = "zcode"

    /** 换取 JWT 的接口（网页端 `tokenUrl: "/api/v1/oauth/token"`）。 */
    const val TOKEN_URL: String = "https://zcode.z.ai/api/v1/oauth/token"

    /** 登录成功后回调页所在的 host；用于把 WebView 的任意跳转与真正的回调区分开。 */
    const val CALLBACK_HOST: String = "zcode.z.ai"

    /** 回调页路径。 */
    const val CALLBACK_PATH: String = "/remote/v4"
}

/** 一次 OAuth 授权的本地状态。`state` 是 base64url(JSON)，与网页端 `v7n` 同构。 */
data class OAuthRequest(
    val provider: OAuthProvider,
    val nonce: String,
    val state: String,
    val authorizeUrl: String,
)

/** 回调解析结果。 */
sealed interface OAuthCallback {
    /** 拿到授权码（`code` 或 BigModel 的 `authCode`）。 */
    data class Code(val code: String) : OAuthCallback

    /** 授权页带回了 `error`。 */
    data class Failure(val message: String) : OAuthCallback

    /** 不是回调 URL（WebView 继续正常加载）。 */
    data object NotCallback : OAuthCallback

    /** 是回调 URL，但 state / code 不合法。 */
    data class Invalid(val message: String) : OAuthCallback
}

/** 登录成功后的凭据（JWT 交给 [app.zemote.data.AuthStore] 加密落盘）。 */
data class OAuthSession(
    val provider: OAuthProvider,
    val jwt: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val expiresAt: Long? = null,
)

class OAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 一次 terminal token 交换的结果。`token` 是 22 字符短 token，**不是** OAuth JWT。 */
data class RemoteToken(val token: String, val expiresInSecs: Long? = null)

/**
 * terminal token 交换失败。
 *
 * [reason] 与 relay 的失败原因同词：`auth-required` 表示 OAuth JWT 已失效，必须重新登录；
 * `relay-unavailable` 表示中继暂时不可用（网络/5xx）。
 */
class RemoteTokenException(
    val reason: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    val authRequired: Boolean get() = reason == "auth-required"
}

/**
 * 授权 URL 与回调的纯逻辑（无 Android / 无网络，JVM 单测直接覆盖）。
 *
 * 参数与网页端逐字对齐：
 * - Z.ai：`?redirect_uri=…&response_type=code&client_id=…&state=…`
 * - BigModel：`?redirect=…&appId=zcode&state=…`
 */
object OAuthFlow {

    private val json = Json { ignoreUnknownKeys = true }

    fun create(
        provider: OAuthProvider,
        redirectUri: String = OAuthConfig.REDIRECT_URI,
        nonce: String = java.util.UUID.randomUUID().toString(),
    ): OAuthRequest {
        val state = encodeState(nonce, redirectUri)
        val url = when (provider) {
            OAuthProvider.ZAI -> buildString {
                append(OAuthConfig.ZAI_AUTHORIZE_URL)
                append("?redirect_uri=").append(enc(redirectUri))
                append("&response_type=code")
                append("&client_id=").append(enc(OAuthConfig.ZAI_CLIENT_ID))
                append("&state=").append(enc(state))
            }
            OAuthProvider.BIGMODEL -> buildString {
                append(OAuthConfig.BIGMODEL_AUTHORIZE_URL)
                append("?redirect=").append(enc(redirectUri))
                append("&appId=").append(enc(OAuthConfig.BIGMODEL_APP_ID))
                append("&state=").append(enc(state))
            }
        }
        return OAuthRequest(provider = provider, nonce = nonce, state = state, authorizeUrl = url)
    }

    /**
     * 判断一个 URL 是不是授权回调。
     *
     * 与网页端 `H9n` 同义：路径为 `/remote/v4`（允许末尾 `/`），且同时带 `state` 与
     * `code` / `authCode` / `error` 之一。
     */
    fun isCallback(url: String, redirectUri: String = OAuthConfig.REDIRECT_URI): Boolean {
        val uri = parse(url) ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val expected = parse(redirectUri) ?: return false
        if (!uri.host.equals(expected.host, ignoreCase = true)) return false
        if (uri.path.trimEnd('/') != OAuthConfig.CALLBACK_PATH) return false
        val params = query(uri.rawQuery)
        return params["state"] != null &&
            (params["code"] != null || params["authCode"] != null || params["error"] != null)
    }

    /**
     * 解析回调并校验 `state.nonce`。
     *
     * @param expectedNonce 发起授权时生成、只存在内存里的 nonce；不匹配视为 CSRF。
     */
    fun parseCallback(url: String, expectedNonce: String): OAuthCallback {
        if (!isCallback(url)) return OAuthCallback.NotCallback
        val uri = parse(url) ?: return OAuthCallback.Invalid("回调 URL 无法解析")
        val params = query(uri.rawQuery)
        val state = params["state"] ?: return OAuthCallback.Invalid("回调缺少 state")
        val decoded = decodeState(state) ?: return OAuthCallback.Invalid("state 不是合法格式")
        val nonce = decoded["nonce"]?.jsonPrimitive?.contentOrNull
        if (nonce.isNullOrBlank() || nonce != expectedNonce) {
            return OAuthCallback.Invalid("OAuth CSRF 校验失败")
        }
        params["error"]?.let { return OAuthCallback.Failure(it) }
        // 网页端 `authCode` 覆盖 `code`（index@6710263 附近），不是相反。
        val code = params["authCode"] ?: params["code"]
        if (code.isNullOrBlank()) return OAuthCallback.Invalid("回调缺少授权码")
        return OAuthCallback.Code(code)
    }

    /** `base64url(JSON.stringify({nonce, app_return_to}))`，与网页端 `v7n` 同构。 */
    fun encodeState(nonce: String, appReturnTo: String): String {
        val payload = buildString {
            append("{\"nonce\":")
            append(JsonPrimitive(nonce).toString())
            append(",\"app_return_to\":")
            append(JsonPrimitive(appReturnTo).toString())
            append('}')
        }
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toByteArray(Charsets.UTF_8))
    }

    fun decodeState(state: String): JsonObject? = try {
        val padded = state.padEnd((state.length + 3) / 4 * 4, '=')
        val bytes = Base64.getUrlDecoder().decode(padded)
        json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
    } catch (_: Exception) {
        null
    }

    private fun parse(url: String): URI? = try {
        URI(url.trim())
    } catch (_: Exception) {
        null
    }

    private fun query(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val key = if (eq < 0) pair else pair.substring(0, eq)
            val value = if (eq < 0) "" else pair.substring(eq + 1)
            out[dec(key)] = dec(value)
        }
        return out
    }

    private fun dec(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: Exception) {
        value
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

/**
 * 授权码 → JWT 的交换客户端。
 *
 * 请求体与网页端 `Q5n.exchangeToken` 完全一致：
 * `POST /api/v1/oauth/token`，`Content-Type: application/json`，
 * `{provider, code, redirect_uri, state}`。
 *
 * 响应只取两样东西：
 * - `data.token`：zcode JWT（relay WS 的 `?token=`）；
 * - `data.<provider>.access_token`：只用来确认平台正确，**不落盘**。
 */
class OAuthTokenClient(
    private val client: OkHttpClient = defaultClient(),
    private val tokenUrl: String = OAuthConfig.TOKEN_URL,
) {

    suspend fun exchange(
        provider: OAuthProvider,
        code: String,
        state: String,
        redirectUri: String = OAuthConfig.REDIRECT_URI,
    ): OAuthSession = withContext(Dispatchers.IO) {
        val body = buildString {
            append("{\"provider\":")
            append(JsonPrimitive(provider.wire).toString())
            append(",\"code\":")
            append(JsonPrimitive(code).toString())
            append(",\"redirect_uri\":")
            append(JsonPrimitive(redirectUri).toString())
            append(",\"state\":")
            append(JsonPrimitive(state).toString())
            append('}')
        }
        val request = Request.Builder()
            .url(tokenUrl)
            .header("Accept", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            throw OAuthException("登录请求失败：${e.message}", e)
        }
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw OAuthException("登录失败（HTTP ${it.code}）")
            }
            parseTokenResponse(provider, text)
        }
    }

    private fun parseTokenResponse(provider: OAuthProvider, text: String): OAuthSession {
        val root = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw OAuthException("登录响应不是合法 JSON", e)
        }
        val code = root["code"]?.jsonPrimitive?.intOrNull
        if (code != null && code != 0) {
            val msg = root["msg"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            throw OAuthException(msg ?: "登录失败（code=$code）")
        }
        val data = root["data"]?.jsonObject
            ?: throw OAuthException("登录响应缺少 data")
        val jwt = data["token"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (jwt.isEmpty()) throw OAuthException("登录响应缺少 data.token")
        val providerData = data[provider.wire]?.jsonObject
        val accessToken = providerData?.get("access_token")?.jsonPrimitive?.contentOrNull
            ?.trim().orEmpty()
        if (accessToken.isEmpty()) {
            throw OAuthException("登录响应缺少 data.${provider.wire}.access_token")
        }
        val user = data["user"]?.jsonObject
        fun field(vararg keys: String): String? {
            for (key in keys) {
                val value = user?.get(key)?.jsonPrimitive?.contentOrNull?.trim()
                if (!value.isNullOrEmpty()) return value
            }
            return null
        }
        // 网页 `A9`：先认驼峰 {id,username,displayName}，再回落蛇形 name/email/user_id。
        val displayName = field("displayName", "name", "email", "user_id", "username", "id")
            ?: "已登录"
        val avatar = normalizeAvatar(field("avatarUrl", "avatar", "avatar_url"))
        // 本地过期只认 JWT 的 exp；data.expires_in 只做诊断，不参与拦截。
        val expiresAt = JwtExpiry.expiresAt(jwt)
        return OAuthSession(
            provider = provider,
            jwt = jwt,
            displayName = displayName,
            avatarUrl = avatar,
            expiresAt = expiresAt,
        )
    }

    /**
     * 网页 `Y5n`：`data:` / `http(s)://` 原样；纯 base64（≥16 字符）补
     * `data:image/png;base64,`；其余原样。
     */
    private fun normalizeAvatar(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (value.startsWith("data:") || value.startsWith("http://") || value.startsWith("https://")) {
            return value
        }
        if (value.length >= 16 && BARE_BASE64.matches(value)) {
            return "data:image/png;base64,$value"
        }
        return value
    }

    companion object {
        private val BARE_BASE64 = Regex("^[A-Za-z0-9+/]+={0,2}$")

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }
}

/**
 * OAuth JWT → 远控 **terminal token** 的交换客户端。
 *
 * ## 为什么需要这一步（3.16.1 的关键差异）
 *
 * ws2 的 `?token=` **不是** OAuth JWT。网页端先：
 *
 * ```
 * POST /api/v1/remote/token
 * Authorization: Bearer <OAuth JWT>
 * {"role":"terminal"}
 * ```
 *
 * 拿到一个 22 字符的短 token（`^[A-Za-z0-9_-]{21}[AQgw]$`），再用它连
 * `wss://<host>/api/v1/remote/ws2?token=<短 token>`。桌面端走同一个接口，
 * 只是 `role:"device"`。
 *
 * 见 `Reference_web/3.16.1/assets/src-uVqBd_Eq.js` 的 `N9n` / `io.token`，以及
 * `Reference_web/3.16.1/assets/index-DbEHT6Gg.js` 的 `Er.token.path`。
 *
 * 响应里的 `expires_in` 是秒；当前实现只在建会话时换一次，不主动续期。
 */
class RemoteTokenClient(
    private val client: OkHttpClient = OAuthTokenClient.defaultClient(),
    /** 生产是 `https://<host>`；单测可注入本地 HTTP 服务器。 */
    private val baseUrl: (host: String) -> String = { "https://$it" },
    private val path: String = "/api/v1/remote/token",
) {

    suspend fun exchange(host: String, jwt: String): RemoteToken = withContext(Dispatchers.IO) {
        val url = "${baseUrl(host)}$path"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $jwt")
            .post("""{"role":"terminal"}""".toRequestBody("application/json".toMediaType()))
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            throw RemoteTokenException("relay-unavailable", "无法连接中继：${e.message}", e)
        }
        response.use {
            if (it.code == 401) {
                throw RemoteTokenException("auth-required", "登录已失效，请重新登录")
            }
            if (!it.isSuccessful) {
                throw RemoteTokenException("relay-unavailable", "中继返回 HTTP ${it.code}")
            }
            val text = it.body?.string().orEmpty()
            val root = try {
                Json.parseToJsonElement(text).jsonObject
            } catch (e: Exception) {
                throw RemoteTokenException("relay-unavailable", "中继响应不是合法 JSON", e)
            }
            // 网页 schema 要求 code === 0；缺 code 也按失败处理，避免把错误信封当成功。
            val code = root["code"]?.jsonPrimitive?.intOrNull
            if (code != 0) {
                val reason = if (code == 401) "auth-required" else "relay-unavailable"
                val msg = root["msg"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                throw RemoteTokenException(reason, msg ?: "中继登录失败（code=$code）")
            }
            val data = root["data"]?.jsonObject
            val token = data?.get("token")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (token.isEmpty()) {
                throw RemoteTokenException("relay-unavailable", "中继响应缺少 data.token")
            }
            if (!TOKEN_PATTERN.matches(token)) {
                throw RemoteTokenException(
                    "relay-unavailable",
                    "中继响应 token 格式非法（期望 22 字符 base64url）",
                )
            }
            // schema 要求正整数；缺失或 <=0 说明响应形状不对，宁可报错也不要拿它去连。
            val expiresIn = data?.get("expires_in")?.jsonPrimitive?.longOrNull
            if (expiresIn == null || expiresIn <= 0) {
                throw RemoteTokenException("relay-unavailable", "中继响应 expires_in 非法")
            }
            RemoteToken(token = token, expiresInSecs = expiresIn)
        }
    }

    private companion object {
        /** Web schema：21 个 base64url 字符 + 末位 `[AQgw]`，共 22 字符。 */
        val TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{21}[AQgw]$")
    }
}
