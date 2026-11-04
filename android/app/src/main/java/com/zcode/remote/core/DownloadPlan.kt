package com.zcode.remote.core

import java.util.Locale

/**
 * 一次下载的纯逻辑：把 WebView `onDownloadStart` 的四个参数变成一个可交给系统
 * `DownloadManager` 的计划。
 *
 * 为什么抽出来、且不碰 `android.net.Uri`：文件名来自 `Content-Disposition`（两种写法：
 * `filename=` 与 RFC 5987 的 `filename*=UTF-8''…`）、URL 末段要 percent-decode、还要清洗
 * 路径分隔符与控制字符——全是"看着对、边界一错就写坏文件名"的地方。纯字符串实现可以在
 * JVM 单测里逐条钉死，也不受 android.jar 桩限制。
 */
object DownloadPlan {

    data class Plan(
        /** 原始 http(s) 地址（可能带凭证，**不得进日志**）。 */
        val url: String,
        /** 仅主机名，供日志/描述用（无 userinfo、无端口、无路径）。 */
        val host: String,
        /** 已清洗的文件名（无路径分隔符，可安全传给 DownloadManager）。 */
        val fileName: String,
        val mimeType: String,
    )

    private val BAD_FILE_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F\\u007F]")
    private const val MAX_NAME_LENGTH = 120
    private const val FALLBACK_NAME = "download"

    private val MIME_EXTENSIONS = mapOf(
        "application/pdf" to "pdf",
        "application/zip" to "zip",
        "application/json" to "json",
        "application/xml" to "xml",
        "application/octet-stream" to "bin",
        "application/vnd.android.package-archive" to "apk",
        "text/plain" to "txt",
        "text/csv" to "csv",
        "text/markdown" to "md",
        "image/png" to "png",
        "image/jpeg" to "jpg",
        "image/gif" to "gif",
        "image/webp" to "webp",
        "audio/mpeg" to "mp3",
        "video/mp4" to "mp4",
    )

    /**
     * 只有 http(s) 能交给系统下载器：`blob:` / `data:` 是没有可重放地址的页面内对象，
     * 交给 `DownloadManager` 只会得到一个空文件。返回 null 时调用方应记一行日志并放弃。
     */
    fun of(url: String?, contentDisposition: String?, mimeType: String?): Plan? {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val scheme = trimmed.substringBefore(':', "").lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") return null
        val host = hostOf(trimmed) ?: return null
        val type = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.US).orEmpty()
        return Plan(
            url = trimmed,
            host = host,
            fileName = fileNameOf(trimmed, contentDisposition, type),
            mimeType = type.ifEmpty { "application/octet-stream" },
        )
    }

    /** authority 里的主机名（去 userinfo、去端口、小写）；解析不出返回 null。 */
    fun hostOf(url: String): String? {
        val afterScheme = url.substringAfter("://", "")
        if (afterScheme.isEmpty()) return null
        val authority = afterScheme.substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        if (authority.isEmpty()) return null
        val hostPort = authority.substringAfterLast('@')
        val host = if (hostPort.startsWith("[")) {
            hostPort.substringBefore(']').removePrefix("[")
        } else {
            hostPort.substringBefore(':')
        }
        return host.lowercase(Locale.US).ifEmpty { null }
    }

    /**
     * 文件名优先级：`Content-Disposition`（`filename*` 先于 `filename`）→ URL 末段 →
     * `download`。清洗后没有扩展名时按 mime 补一个，否则系统文件管理器认不出类型。
     */
    fun fileNameOf(url: String, contentDisposition: String?, mimeType: String?): String {
        val fromHeader = contentDisposition?.let { nameFromDisposition(it) }
        val fromUrl = urlPathTail(url)
        val sanitised = sanitise(fromHeader ?: fromUrl)
        if (sanitised.isNotEmpty() && sanitised.contains('.')) return sanitised
        val base = sanitised.ifEmpty { FALLBACK_NAME }
        val extension = extensionFor(mimeType)
        return if (extension.isEmpty()) base else "$base.$extension"
    }

    /** RFC 5987 的 `filename*=UTF-8''<pct>` 与普通 `filename="..."` / `filename=...`。 */
    private fun nameFromDisposition(header: String): String? {
        val extended = Regex("filename\\*\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.get(1)?.trim()?.trim('"')
        if (!extended.isNullOrEmpty()) {
            val encoded = extended.substringAfter("''", extended)
            return decode(encoded)
        }
        val plain = Regex("filename\\s*=\\s*(\"[^\"]*\"|[^;]+)", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.get(1)?.trim()?.trim('"')
        return plain?.takeIf { it.isNotEmpty() }
    }

    /** URL 路径最后一段（去 query/fragment、percent-decode）；没有路径段时返回空串。 */
    private fun urlPathTail(url: String): String {
        val path = url.substringBefore('#')
            .substringBefore('?')
            .substringAfter("://", "")
            .substringAfter('/', "")
        if (path.isEmpty()) return ""
        return decode(path.substringAfterLast('/'))
    }

    private fun decode(value: String): String {
        // 路径语义里 '+' 是字面加号，不是空格：先保护它再按 form 规则解码。
        return try {
            java.net.URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
        } catch (e: Exception) {
            value
        }
    }

    private fun sanitise(name: String): String {
        val cleaned = name.replace(BAD_FILE_CHARS, "_")
            .trim()
            .trimStart('.')
        if (cleaned.length <= MAX_NAME_LENGTH) return cleaned
        val dot = cleaned.lastIndexOf('.')
        return if (dot in 1 until cleaned.length - 1) {
            val extension = cleaned.substring(dot)
            cleaned.substring(0, dot).take((MAX_NAME_LENGTH - extension.length).coerceAtLeast(1)) + extension
        } else {
            cleaned.take(MAX_NAME_LENGTH)
        }
    }

    private fun extensionFor(mimeType: String?): String {
        val type = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.US).orEmpty()
        if (type.isEmpty()) return ""
        MIME_EXTENSIONS[type]?.let { return it }
        // application/vnd.openxmlformats-officedocument.* 之类按子类型兜底
        if (type.startsWith("image/")) return type.substringAfter('/')
        if (type.startsWith("audio/")) return type.substringAfter('/')
        if (type.startsWith("video/")) return type.substringAfter('/')
        return ""
    }
}
