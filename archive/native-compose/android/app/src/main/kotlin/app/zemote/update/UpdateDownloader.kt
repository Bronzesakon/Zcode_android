package app.zemote.update

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext

/** 一次下载请求：清单 + 已解析好的两条直链（来自 [UpdateChecker.UpdateAvailable]）。 */
data class DownloadRequest(
    val manifest: VersionManifest,
    val apkUrl: String,
    val md5Url: String,
)

/**
 * 把更新包下到 [directory]（生产上是 `getFilesDir()/updates`，与 `res/xml/file_paths.xml`
 * 白名单里的 `files-path updates/` 对应）。
 *
 * ## 断点续传
 * 临时文件 `<最终名>.part` 一直存在，直到校验通过才改名。重来时先读它的长度 N，带
 * `Range: bytes=N-` 请求：
 * - **206** → 从 N 处续写（`FileOutputStream(append = true)`），并把已下的 N 字节先喂给
 *   摘要，保证最终 MD5 覆盖的是**整份文件**而不是本次续下的片段。
 * - **200**（服务端不认 Range，GitHub 的重定向链路在某些情况下会这样）→ 丢弃残留，
 *   从 0 重写，摘要 reset。若不清零就会拼出一段错位的内容——MD5 能发现，但白下一遍。
 * - **416** → 本地残留已经 >= 远端全长，无法续传：删掉重下。这里刻意不做"416 说明已经下完"
 *   的推断，那种小聪明在服务端换了包时会静默装错包。
 *
 * 取消（协程 cancel）时**保留** `.part`：已下的前缀正是下次续传的起点。
 *
 * ## 校验
 * 边下边算 MD5（[MessageDigest] 增量喂），下完比对清单的 `sizeBytes` 与 `.md5` 资产，
 * 两者都通过才 `renameTo` 成最终文件名。任何一步不过就删掉 `.part` 并返回失败——
 * **绝不**把没校验通过的包交给安装器。
 *
 * 注意 MD5 在这里是完整性校验，不是安全边界（见 `UpdateModels.kt` 里 `hexDigest` 的注释）。
 */
class UpdateDownloader(
    private val fetcher: HttpFetcher,
    /** 下载目录。生产上传 `getFilesDir()/updates`。 */
    val directory: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val bufferSize: Int = 64 * 1024,
) {

    /**
     * 下载 + 校验。流上依次发 [DownloadEvent.Progress]，最后**必定**发一次
     * [DownloadEvent.Finished]（正常路径与业务失败路径都会发；只有协程取消不会，
     * 取消按协程惯例抛 [CancellationException]）。
     *
     * 用 `channelFlow` 而不是 `flow`：读流是阻塞 IO，要整段放进 [ioDispatcher]，
     * 而 `flow` 的 emit 不允许跨上下文。
     */
    fun download(request: DownloadRequest): Flow<DownloadEvent> = channelFlow {
        val result = withContext(ioDispatcher) {
            runDownload(request) { progress -> send(DownloadEvent.Progress(progress)) }
        }
        send(DownloadEvent.Finished(result))
    }

    /** 最终文件名：`zemote-<versionCode>-<abi>-<md5 前 8 位>.apk`。 */
    fun targetFile(manifest: VersionManifest, md5: String): File =
        File(directory, "zemote-${manifest.versionCode}-${manifest.abi}-${md5.take(8)}.apk")

    /**
     * 清理下载目录，留下 [keep]（通常是被安装的那个包）。返回删掉的条目数。
     *
     * 刻意不提供"启动安装器后立刻删包"：系统安装器是异步读这个文件的，删早了会装失败。
     * 旧 Flutter 实现是把路径存进 SharedPreferences、下次启动再删，这里把时机交给调用方
     * （装完回来、或下次启动时调这个方法）。
     */
    fun pruneDownloads(keep: File? = null): Int {
        val entries = directory.listFiles() ?: return 0
        val keepPath = keep?.absolutePath
        var removed = 0
        for (entry in entries) {
            if (entry.isFile && entry.absolutePath != keepPath && entry.delete()) removed++
        }
        return removed
    }

    private suspend fun runDownload(
        request: DownloadRequest,
        onProgress: suspend (DownloadProgress) -> Unit,
    ): DownloadResult {
        val manifest = request.manifest
        try {
            if (!directory.isDirectory && !directory.mkdirs()) {
                return DownloadResult.Failed(
                    DownloadFailure.Local,
                    "无法创建下载目录 ${directory.absolutePath}",
                )
            }

            // 校验值先拿：它同时决定本地文件名。把 md5 前缀写进文件名，是为了让
            // "同一小时内两次构建共用同一个 versionCode"时不会拿上一版残留的 .part 续传。
            val expectedMd5 = fetchExpectedMd5(request.md5Url)
            val target = targetFile(manifest, expectedMd5)
            val part = File(directory, "${target.name}.part")

            // 0. 缓存直装：上次下完且校验通过的包还在，就不必再走网络。
            if (target.isFile && target.length() == manifest.sizeBytes) {
                val cached = md5OfFile(target)
                if (cached == expectedMd5) {
                    onProgress(DownloadProgress(manifest.sizeBytes, manifest.sizeBytes, manifest.sizeBytes))
                    return DownloadResult.Success(target, cached, manifest.sizeBytes)
                }
                // 缓存对不上（被人动过 / 下坏了）：删掉，走正常下载。
                target.delete()
            }

            // 1. 续传基线。
            var start = if (part.isFile) part.length() else 0L
            if (start > manifest.sizeBytes) {
                // 比清单还长：脏残留，重来。
                part.delete()
                start = 0
            }
            val digest = MessageDigest.getInstance("MD5")
            if (start > 0) {
                try {
                    FileInputStream(part).use { input ->
                        val buffer = ByteArray(bufferSize)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                        }
                    }
                } catch (e: IOException) {
                    // 残留读不动就当没有，从 0 下。
                    part.delete()
                    start = 0
                    digest.reset()
                }
            }

            // 2. 请求。带 Range 时服务端可能回 206 / 200 / 416。
            var response = fetcher.fetch(request.apkUrl, start.takeIf { it > 0 })
            if (response.statusCode == 416 && start > 0) {
                response.close()
                part.delete()
                start = 0
                digest.reset()
                response = fetcher.fetch(request.apkUrl, null)
            }

            val resuming = start > 0 && response.statusCode == 206
            if (response.statusCode != 200 && !resuming) {
                val code = response.statusCode
                response.close()
                return DownloadResult.Failed(DownloadFailure.Http, "下载返回 HTTP $code")
            }
            if (start > 0 && !resuming) {
                // 服务端忽略了 Range，必须从头写，否则内容会错位。
                part.delete()
                start = 0
                digest.reset()
            }

            val total = manifest.sizeBytes
            val received = response.use { res ->
                res.body.use { input ->
                    val output = try {
                        BufferedOutputStream(FileOutputStream(part, start > 0), bufferSize)
                    } catch (e: IOException) {
                        return DownloadResult.Failed(
                            DownloadFailure.Local,
                            "无法写入 ${part.absolutePath}：${e.message}",
                        )
                    }
                    var bytes = start
                    try {
                        onProgress(DownloadProgress(bytes, total, start))
                        val buffer = ByteArray(bufferSize)
                        while (true) {
                            // 取消点：放在读之前，取消后已下的部分留在 .part 里。
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            bytes += read
                            onProgress(DownloadProgress(bytes, total, start))
                        }
                        output.flush()
                        bytes
                    } finally {
                        // 取消或异常也要落盘：这段前缀正是下次续传的起点。
                        runCatching { output.flush() }
                        runCatching { output.close() }
                    }
                }
            }

            if (received < total) {
                // 流提前结束（服务端断开，或清单比实际包大）。保留 .part，下次接着下。
                return DownloadResult.Failed(
                    DownloadFailure.Network,
                    "响应提前结束：$received/$total 字节",
                )
            }
            if (received > total) {
                part.delete()
                return DownloadResult.SizeMismatch(total, received)
            }

            // 3. 双重校验：字节数 + MD5。都过了才改名。
            val actualSize = part.length()
            if (actualSize != total) {
                part.delete()
                return DownloadResult.SizeMismatch(total, actualSize)
            }
            val actualMd5 = try {
                md5OfFile(part)
            } catch (e: IOException) {
                part.delete()
                return DownloadResult.Failed(DownloadFailure.Local, "校验时读不了文件：${e.message}")
            }
            if (actualMd5 != expectedMd5) {
                // 校验不过：删临时文件，且不产出最终文件。
                part.delete()
                return DownloadResult.ChecksumMismatch(expectedMd5, actualMd5)
            }

            target.delete()
            if (!part.renameTo(target)) {
                part.delete()
                return DownloadResult.Failed(
                    DownloadFailure.Local,
                    "改名失败：${part.name} -> ${target.name}",
                )
            }
            return DownloadResult.Success(target, actualMd5, actualSize)
        } catch (e: CancellationException) {
            // 取消不是失败：.part 留着，下次续传。
            throw e
        } catch (e: HttpStatusException) {
            return DownloadResult.Failed(DownloadFailure.Http, e.message ?: "HTTP ${e.code}")
        } catch (e: MalformedAssetException) {
            return DownloadResult.Failed(DownloadFailure.Manifest, e.message ?: "校验文件不可用")
        } catch (e: OversizedResponseException) {
            return DownloadResult.Failed(DownloadFailure.Manifest, e.message ?: "校验文件过大")
        } catch (e: IOException) {
            return DownloadResult.Failed(
                DownloadFailure.Network,
                "${e::class.java.simpleName}: ${e.message ?: "无详情"}",
            )
        } catch (e: Exception) {
            // 兜底：让调用方的 collect 永远收得到结果，不至于挂在半路。
            return DownloadResult.Failed(
                DownloadFailure.Unexpected,
                "${e::class.java.simpleName}: ${e.message ?: "无详情"}",
            )
        }
    }

    private suspend fun fetchExpectedMd5(url: String): String {
        val response = fetcher.fetch(url)
        response.use {
            if (it.statusCode !in 200..299) throw HttpStatusException(it.statusCode, url)
            val text = it.body.readLimited(MAX_MD5_BYTES).toString(Charsets.UTF_8)
            return parseMd5Hex(text)
                ?: throw MalformedAssetException("校验文件里没有 32 位 MD5：${text.take(64)}")
        }
    }

    private fun md5OfFile(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(bufferSize)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.hexDigest()
    }

    private companion object {
        /** `.md5` 资产就几十字节，给足余量即可。 */
        const val MAX_MD5_BYTES = 8 * 1024L
    }
}
