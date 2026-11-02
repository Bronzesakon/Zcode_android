package app.zemote.auth

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * OAuth JWT 的本地过期判定（3.16.1 网页 `src@31023` 的同构实现）。
 *
 * 网页只认 JWT 的 `exp`，并留 30s 余量；`data.expires_in` 不参与拦截（三端都不读）。
 * 解析失败（不是三段式 / payload 不是 JSON / 没有 `exp`）时视为「过期时间未知」，
 * 不拦截，交给服务端 401 兜底。
 */
object JwtExpiry {

    /** 提前 30s 视为过期，避免请求发出时刚好跨过 `exp`。 */
    const val MARGIN_MS: Long = 30_000L

    private val json = Json { ignoreUnknownKeys = true }

    /** JWT 的 `exp`（毫秒）；无法解析或没有 `exp` 时为 `null`。 */
    fun expiresAt(jwt: String): Long? {
        val payload = jwt.split('.').getOrNull(1) ?: return null
        val decoded = try {
            val padded = payload.padEnd((payload.length + 3) / 4 * 4, '=')
            String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
        } catch (_: Exception) {
            return null
        }
        val exp = try {
            json.parseToJsonElement(decoded).jsonObject["exp"]?.jsonPrimitive?.longOrNull
        } catch (_: Exception) {
            return null
        }
        return exp?.takeIf { it > 0 }?.times(1000)
    }

    /** 已过期（含 30s 余量）；`exp` 未知时返回 `false`。 */
    fun isExpired(jwt: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val expiresAt = expiresAt(jwt) ?: return false
        return nowMs >= expiresAt - MARGIN_MS
    }
}
