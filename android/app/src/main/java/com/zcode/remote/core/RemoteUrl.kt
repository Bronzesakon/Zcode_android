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
     *
     * D1（2026-11-04）：口径与鸿蒙一致 = `z.ai` + `*.z.ai` + `bigmodel.cn` +
     * `*.bigmodel.cn`。[isTrustedHost] 同时接受根域与其任意子域，所以两项就够。
     */
    private val TRUSTED_NAVIGATION_HOSTS = listOf("z.ai", "bigmodel.cn")

    /**
     * 下载链接的判定范围（D2）：**非可信域 + 主框架 + 非下载** 三条同时成立才外抛
     * 系统浏览器。下载不能外抛——那会把用户从壳里踢出去，而且服务端带 Content-Disposition
     * 的链接看着就是一次普通导航。
     *
     * 只认路径后缀，不认 host：`zcode.z.ai/.../x.zip` 与 `github.com/x.zip` 一样处理。
     */
    private val DOWNLOAD_EXTENSIONS = setOf(
        "zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz",
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv", "tsv",
        "txt", "md", "json", "xml", "yaml", "yml", "log",
        "apk", "aab", "ipa", "dmg", "exe", "msi", "deb", "rpm",
        "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "ico",
        "mp3", "mp4", "wav", "m4a", "mov", "webm", "avi", "mkv",
    )

    /** 登录/授权面（D3）：只由 host + path 前缀判定，不碰 Cookie、不碰存储。 */
    enum class AuthSurface {
        /** `*.bigmodel.cn` — 恒亮。 */
        BIGMODEL,

        /** `chat.z.ai` + `/auth/oauth/authorize` — 授权确认页。 */
        ZAI_CONSENT,

        /** `chat.z.ai` + `/auth…` — 手机号/扫码登录页。 */
        ZAI_LOGIN,

        /** `zcode.z.ai` + `/web-remote`、`/share`、`/cn/share`。 */
        ZCODE,
    }

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
        return isTrustedHost(host)
    }

    /** Host 等于任一可信根域或其子域。大小写不敏感，纯字符串（可单测）。 */
    fun isTrustedHost(host: String): Boolean {
        val normalised = host.lowercase(Locale.US)
        return TRUSTED_NAVIGATION_HOSTS.any { root ->
            normalised == root || normalised.endsWith(".$root")
        }
    }

    /** 路径是否以已知下载后缀结尾（忽略 query / fragment，大小写不敏感）。 */
    fun isDownloadUrl(url: String): Boolean {
        val withoutFragment = url.substringBefore('#')
        val path = withoutFragment.substringBefore('?')
        val last = path.substringAfterLast('/')
        if (last.isEmpty() || last.indexOf('.') < 0) return false
        val extension = last.substringAfterLast('.').lowercase(Locale.US)
        return extension.isNotEmpty() && extension in DOWNLOAD_EXTENSIONS
    }

    /** 登录/授权面判定（D3）：host + path 前缀，其它一律 null。 */
    fun authSurfaceOf(host: String, path: String): AuthSurface? {
        val normalisedHost = host.lowercase(Locale.US)
        val normalisedPath = path.ifEmpty { "/" }
        if (normalisedHost == "bigmodel.cn" || normalisedHost.endsWith(".bigmodel.cn")) {
            return AuthSurface.BIGMODEL
        }
        if (normalisedHost == "chat.z.ai" || normalisedHost.endsWith(".chat.z.ai")) {
            if (normalisedPath.startsWith("/auth/oauth/authorize")) return AuthSurface.ZAI_CONSENT
            if (normalisedPath.startsWith("/auth")) return AuthSurface.ZAI_LOGIN
            return null
        }
        if (normalisedHost == "zcode.z.ai" || normalisedHost.endsWith(".zcode.z.ai")) {
            val share = normalisedPath.startsWith("/cn/share") || normalisedPath.startsWith("/share")
            if (normalisedPath.startsWith("/web-remote") || share) return AuthSurface.ZCODE
        }
        return null
    }

    fun authSurfaceOf(uri: Uri): AuthSurface? =
        authSurfaceOf(uri.host.orEmpty(), uri.path.orEmpty())

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
