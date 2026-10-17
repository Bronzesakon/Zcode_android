package app.zemote.update

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 更新器唯一的网络出口。
 *
 * 抽这一层的理由是可测性，不是"架构好看"：单测必须能在不联网、也不给项目加
 * `mockwebserver` 依赖的前提下，构造出 206 续传、服务端忽略 Range 返回 200、
 * 416、中途断流这些响应（见 `app/src/test/kotlin/app/zemote/update/`）。
 *
 * [rangeStart] 非空时实现必须带上 `Range: bytes=<n>-` 请求头；服务端可以不认，
 * 由调用方根据状态码处理。
 */
interface HttpFetcher {
    suspend fun fetch(url: String, rangeStart: Long? = null): HttpResponse
}

/**
 * 一次 HTTP 响应。[headers] 的键统一小写，避免 fake 与真实实现的大小写差异。
 * 用完必须 [close]（否则 OkHttp 的连接不会归还连接池）。
 */
class HttpResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: InputStream,
) : Closeable {

    val contentLength: Long?
        get() = headers["content-length"]?.toLongOrNull()

    /** 206 的 `Content-Range: bytes a-b/total` 里的 total；拿不到就是 null。 */
    val contentRangeTotal: Long?
        get() = headers["content-range"]
            ?.substringAfterLast('/')
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != "*" }
            ?.toLongOrNull()

    override fun close() {
        body.close()
    }
}

/** 状态码不在 2xx（404 由调用方单独处理）。不是 IOException：它不是网络故障。 */
internal class HttpStatusException(val code: Int, val url: String) :
    Exception("HTTP $code $url")

/** 响应体超过上限。也不是网络故障，归到"数据有问题"。 */
internal class OversizedResponseException(message: String) : Exception(message)

/** 校验文件读不出 MD5 之类的内容问题。 */
internal class MalformedAssetException(message: String) : Exception(message)

/** 最多读 [limit] 字节；超了直接报错，避免被一个超大响应把内存吃掉。 */
internal fun InputStream.readLimited(limit: Long): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > limit) throw OversizedResponseException("响应体超过 $limit 字节")
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

/**
 * OkHttp 实现。依赖已随中继 WebSocket 客户端一起加好（`okhttp:5.5.0`）。
 *
 * 超时故意不设 `callTimeout`：那会把"整次调用"计时，下载大包时会被误杀。
 * `readTimeout` 是每次读的间隔上限，既能干掉卡死的连接，又不影响慢速下载。
 */
class OkHttpHttpFetcher(
    private val client: OkHttpClient = defaultClient(),
    private val userAgent: String = DEFAULT_USER_AGENT,
) : HttpFetcher {

    override suspend fun fetch(url: String, rangeStart: Long?): HttpResponse =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                // GitHub API 要求带 User-Agent，缺了直接 403。显式设置，不依赖 OkHttp 的默认值。
                .header("User-Agent", userAgent)
                .header("Accept", "application/vnd.github+json")
                .apply { if (rangeStart != null) header("Range", "bytes=$rangeStart-") }
                .build()
            val response = client.newCall(request).execute()
            val body = response.body ?: run {
                response.close()
                throw IOException("响应体为空：$url")
            }
            HttpResponse(
                statusCode = response.code,
                headers = response.headers.names()
                    .associate { it.lowercase() to (response.header(it) ?: "") },
                body = body.byteStream(),
            )
        }

    companion object {
        const val DEFAULT_USER_AGENT = "Zemote-Android-Updater"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
