package app.zemote.update

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 单测共用的网络桩。
 *
 * 刻意不用 `okhttp3.mockwebserver`：它不在 `app/build.gradle.kts` 的依赖里，为几个
 * 用例加一个依赖不值当（而且真实 socket 会让 206/416/断流 这些边界更难构造得确定）。
 * 更新器本来就通过 [HttpFetcher] 接口拿网络，这里实现它即可。
 */
class StubFetcher : HttpFetcher {

    private val routes = mutableMapOf<String, (Long?) -> HttpResponse>()

    /** 记录每次请求，用来断言"有没有带 Range""请求了几次"。 */
    val calls = mutableListOf<FetchCall>()

    fun on(url: String, handler: (Long?) -> HttpResponse) {
        routes[url] = handler
    }

    /** 固定文本响应。返回 this 便于链式配置。 */
    fun text(url: String, status: Int, body: String): StubFetcher = apply {
        on(url) { _ -> textResponse(status, body) }
    }

    /** 支持 Range 的二进制响应；[ignoreRange] 模拟服务端不认 Range（回 200 全量）。 */
    fun binary(url: String, body: ByteArray, ignoreRange: Boolean = false): StubFetcher = apply {
        on(url) { range -> rangedResponse(body, range, ignoreRange) }
    }

    /** 连不上。 */
    fun failing(url: String, error: IOException = IOException("connect timed out")): StubFetcher = apply {
        on(url) { _ -> throw error }
    }

    /** 声明了全长但读到一半断流。 */
    fun truncating(url: String, body: ByteArray, afterBytes: Int): StubFetcher = apply {
        on(url) { _ ->
            HttpResponse(
                statusCode = 200,
                headers = mapOf("content-length" to body.size.toString()),
                body = FailingInputStream(ByteArrayInputStream(body), afterBytes),
            )
        }
    }

    override suspend fun fetch(url: String, rangeStart: Long?): HttpResponse {
        calls += FetchCall(url, rangeStart)
        val handler = routes[url] ?: return textResponse(404, "stub 没有配置这个 URL: $url")
        return handler(rangeStart)
    }

    fun callCount(url: String): Int = calls.count { it.url == url }
}

data class FetchCall(val url: String, val rangeStart: Long?)

fun textResponse(status: Int, body: String): HttpResponse {
    val bytes = body.toByteArray()
    return HttpResponse(
        statusCode = status,
        headers = mapOf("content-length" to bytes.size.toString()),
        body = ByteArrayInputStream(bytes),
    )
}

/**
 * 模拟 HTTP 的 Range 语义：
 * - 没带 Range（或 [ignoreRange]）→ 200 + 全量
 * - 偏移越界 → 416
 * - 否则 → 206 + 片段 + `Content-Range`
 */
fun rangedResponse(body: ByteArray, rangeStart: Long?, ignoreRange: Boolean = false): HttpResponse {
    if (rangeStart == null || rangeStart <= 0L || ignoreRange) {
        return HttpResponse(
            statusCode = 200,
            headers = mapOf("content-length" to body.size.toString()),
            body = ByteArrayInputStream(body),
        )
    }
    if (rangeStart >= body.size) {
        return HttpResponse(416, emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }
    val slice = body.copyOfRange(rangeStart.toInt(), body.size)
    return HttpResponse(
        statusCode = 206,
        headers = mapOf(
            "content-length" to slice.size.toString(),
            "content-range" to "bytes $rangeStart-${body.size - 1}/${body.size}",
        ),
        body = ByteArrayInputStream(slice),
    )
}

/** 服务端资产列表 JSON。 */
fun releaseJson(vararg assets: Pair<String, String>): String {
    val list = assets.joinToString(",") { (name, url) ->
        """{"name": "$name", "browser_download_url": "$url"}"""
    }
    return """
        {"tag_name": "${UpdateConfig.ROLLING_TAG}",
         "html_url": "${UpdateConfig.RELEASE_PAGE_URL}",
         "prerelease": true,
         "assets": [$list]}
    """.trimIndent()
}

/** 版本清单 JSON。 */
fun manifestJson(
    versionCode: Int = 26101623,
    versionName: String = "0.1.0-dev.20261016.2314",
    abi: String = UpdateConfig.ABI,
    apkAsset: String = APK_ASSET,
    md5Asset: String = MD5_ASSET,
    sizeBytes: Long = 1_106_350,
): String =
    """{"versionCode": $versionCode, "versionName": "$versionName", "abi": "$abi",
        "apkAsset": "$apkAsset", "md5Asset": "$md5Asset", "sizeBytes": $sizeBytes}"""

fun manifest(
    versionCode: Int = 26101623,
    versionName: String = "0.1.0-dev.20261016.2314",
    abi: String = UpdateConfig.ABI,
    apkAsset: String = APK_ASSET,
    md5Asset: String = MD5_ASSET,
    sizeBytes: Long,
): VersionManifest = VersionManifest(
    versionCode = versionCode,
    versionName = versionName,
    abi = abi,
    apkAsset = apkAsset,
    md5Asset = md5Asset,
    sizeBytes = sizeBytes,
)

const val API_URL = UpdateConfig.RELEASE_API_URL
const val MANIFEST_URL = "https://example.invalid/zemote-version.json"
const val APK_URL = "https://example.invalid/zemote-arm64-v8a.apk"
const val MD5_URL = "https://example.invalid/zemote-arm64-v8a.apk.md5"
const val MANIFEST_ASSET = UpdateConfig.MANIFEST_ASSET
const val APK_ASSET = "zemote-arm64-v8a.apk"
const val MD5_ASSET = "zemote-arm64-v8a.apk.md5"

/** 可复现的假 APK 内容。 */
fun fakeApk(size: Int = 300 * 1024): ByteArray = ByteArray(size) { ((it * 31 + 7) % 251).toByte() }

fun md5Text(bytes: ByteArray): String = "${bytes.md5Hex()}  $APK_ASSET\n"

/** 读到 [failAfter] 字节后抛 IOException，模拟连接被掐断。 */
private class FailingInputStream(
    private val delegate: InputStream,
    private val failAfter: Int,
) : InputStream() {
    private var served = 0

    override fun read(): Int {
        if (served >= failAfter) throw IOException("connection reset by peer")
        val value = delegate.read()
        if (value >= 0) served++
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (served >= failAfter) throw IOException("connection reset by peer")
        val allowed = minOf(len, failAfter - served)
        val read = delegate.read(b, off, allowed)
        if (read > 0) served += read
        return read
    }

    override fun available(): Int = delegate.available()

    override fun close() = delegate.close()
}

/**
 * 每次读都短暂停一下，给测试线程时间把取消投递进来。
 *
 * 为什么是"短超时轮询"而不是死等一个 latch：协程取消**不会**打断阻塞在非挂起调用里的
 * 线程，死等会让 `cancelAndJoin` 卡住。20ms 的等待足够让取消落到下一次 `ensureActive()`。
 */
class PausingInputStream(
    private val delegate: InputStream,
    private val gate: CountDownLatch,
) : InputStream() {

    override fun read(): Int = read(ByteArray(1), 0, 1)

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        gate.await(20, TimeUnit.MILLISECONDS)
        return delegate.read(b, off, len)
    }

    override fun available(): Int = delegate.available()

    override fun close() = delegate.close()
}
