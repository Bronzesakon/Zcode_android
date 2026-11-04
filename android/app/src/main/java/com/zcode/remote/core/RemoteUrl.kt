package com.zcode.remote.core

import android.net.Uri
import java.util.Locale

/**
 * The remote-control URL, plus the validation the shell applies to it.
 *
 * Rules mirror the HarmonyOS build's `RemoteUrlManager` so both shells accept
 * exactly the same links:
 *   * scheme must be https
 *   * host must be `z.ai` or a subdomain of it
 *   * path must start with `/remote`
 *
 * Validation matters here: the URL is what the user scans off their desktop, and
 * it embeds session credentials. Refusing anything else means a malicious QR
 * code cannot point the shell (which injects a script into whatever it loads) at
 * an attacker-controlled origin.
 */
object RemoteUrl {

    private const val ALLOWED_ROOT_HOST = "z.ai"
    private const val REQUIRED_PATH_PREFIX = "/remote"

    /**
     * Hosts whose pages may load in the WebView even though they are not the
     * remote page itself. OAuth needs them:
     *   * chat.z.ai/api/oauth/authorize — Z.ai authorization
     *   * bigmodel.cn/login            — BigModel authorization
     *   * zcode.z.ai/web-remote/callback — the callback that exchanges the code
     *
     * The login nonce lives in the WebView's sessionStorage, so the authorize
     * page and the callback must share this WebView's storage context. Handing
     * either one to the system browser leaves the callback without the nonce
     * and the page reports "OAuth CSRF 检测失败".
     */
    private val TRUSTED_NAVIGATION_HOSTS = listOf("z.ai", "bigmodel.cn")

    /** Returns a normalised URL string, or null when [raw] is not acceptable. */
    fun normalize(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val uri = try {
            Uri.parse(trimmed)
        } catch (e: Exception) {
            return null
        }
        if (!isAllowed(uri)) return null
        return uri.toString()
    }

    fun isAllowed(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme != "https") return false
        val host = uri.host?.lowercase(Locale.US) ?: return false
        if (host != ALLOWED_ROOT_HOST && !host.endsWith(".$ALLOWED_ROOT_HOST")) return false
        val path = uri.path.orEmpty()
        return path.startsWith(REQUIRED_PATH_PREFIX)
    }

    /**
     * Whether the WebView may navigate to [uri] itself.
     *
     * Deliberately wider than [isAllowed]: that one validates the scanned
     * remote-control link and must stay strict, while this one also lets the
     * first-party OAuth pages load in-place. Non-https URLs (intent://,
     * weixin://, …) still go to the system.
     */
    fun isTrustedNavigation(uri: Uri): Boolean {
        if (uri.scheme?.lowercase(Locale.US) != "https") return false
        val host = uri.host?.lowercase(Locale.US) ?: return false
        return TRUSTED_NAVIGATION_HOSTS.any { root -> host == root || host.endsWith(".$root") }
    }

    /**
     * A display-safe form: the query string is where `sid`/`hash`/`mid` live, so
     * it is replaced with a marker before the URL is ever shown or logged.
     */
    fun toDisplayString(url: String): String {
        val uri = try {
            Uri.parse(url)
        } catch (e: Exception) {
            return "<无效链接>"
        }
        val host = uri.host ?: return "<无效链接>"
        val path = uri.path.orEmpty()
        val hasQuery = !uri.query.isNullOrEmpty()
        return buildString {
            append("https://")
            append(host)
            append(path)
            if (hasQuery) append("?<凭据已隐藏>")
        }
    }

    /** Extracts the first plausible remote URL from arbitrary scanned text. */
    fun extractFromScannedText(text: String?): String? {
        val value = text?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (normalize(value) != null) return normalize(value)
        // A QR payload may wrap the link in extra text; look for the first URL.
        val marker = value.indexOf("https://")
        if (marker < 0) return null
        val candidate = value.substring(marker).trim().split(Regex("\\s+")).firstOrNull()
        return normalize(candidate)
    }

    /** The origin rule handed to addDocumentStartJavaScript. */
    fun originRule(url: String): String {
        val host = try {
            Uri.parse(url).host
        } catch (e: Exception) {
            null
        }
        return "https://${host ?: ALLOWED_ROOT_HOST}"
    }
}
