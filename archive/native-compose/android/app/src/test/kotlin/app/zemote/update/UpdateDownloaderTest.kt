package app.zemote.update

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 下载 + 校验：断点续传、边下边算 MD5、校验不过不留包。
 * 全部走 [StubFetcher]，不碰网络；目录用 [TemporaryFolder]。
 */
class UpdateDownloaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun newDir(): File = temp.newFolder("updates")

    private fun request(sizeBytes: Long) = DownloadRequest(
        manifest = manifest(sizeBytes = sizeBytes),
        apkUrl = APK_URL,
        md5Url = MD5_URL,
    )

    private fun apkStub(body: ByteArray, ignoreRange: Boolean = false) = StubFetcher().apply {
        binary(APK_URL, body, ignoreRange)
        text(MD5_URL, 200, md5Text(body))
    }

    private suspend fun drain(flow: Flow<DownloadEvent>): Pair<List<DownloadEvent>, DownloadResult> {
        val events = mutableListOf<DownloadEvent>()
        var result: DownloadResult? = null
        flow.collect { event ->
            events += event
            if (event is DownloadEvent.Finished) result = event.result
        }
        return events to requireNotNull(result) { "流结束时必须有 Finished 事件" }
    }

    private fun List<DownloadEvent>.progresses(): List<DownloadProgress> =
        filterIsInstance<DownloadEvent.Progress>().map { it.progress }

    private fun filesIn(dir: File): List<String> =
        dir.listFiles()?.map { it.name }?.sorted() ?: emptyList()

    /** 复现 [UpdateDownloader.targetFile] 的命名，用于构造"已有残留"的场景。 */
    private fun partOf(dir: File, body: ByteArray, sizeBytes: Long): File {
        val target = UpdateDownloader(StubFetcher(), dir)
            .targetFile(manifest(sizeBytes = sizeBytes), body.md5Hex())
        return File(dir, "${target.name}.part")
    }

    @Test
    fun `首次下载：内容正确、part 已改名、进度收敛到 100%`() = runTest {
        val body = fakeApk()
        val fetcher = apkStub(body)
        val dir = newDir()

        val (events, result) = drain(UpdateDownloader(fetcher, dir).download(request(body.size.toLong())))

        val success = result as DownloadResult.Success
        assertEquals(body.md5Hex(), success.md5)
        assertEquals(body.size.toLong(), success.sizeBytes)
        assertArrayEquals(body, success.file.readBytes())
        // 最终目录里只有成品，没有 .part 残留。
        assertEquals(listOf(success.file.name), filesIn(dir))
        assertEquals(null, fetcher.calls.first { it.url == APK_URL }.rangeStart)

        val progress = events.progresses()
        assertEquals(0L, progress.first().resumedFrom)
        assertEquals(1f, progress.last().fraction!!, 1e-4f)
        assertTrue(progress.zipWithNext().all { (a, b) -> b.bytesDownloaded >= a.bytesDownloaded })
        assertEquals(body.size.toLong(), progress.last().bytesDownloaded)
    }

    @Test
    fun `断点续传：带 Range 请求，拼出来的内容与原文件一致`() = runTest {
        val body = fakeApk()
        val dir = newDir()
        val part = partOf(dir, body, body.size.toLong())
        part.writeBytes(body.copyOfRange(0, 40_000))

        val fetcher = apkStub(body)
        val (events, result) = drain(UpdateDownloader(fetcher, dir).download(request(body.size.toLong())))

        assertEquals(40_000L, fetcher.calls.first { it.url == APK_URL }.rangeStart)
        assertArrayEquals(body, (result as DownloadResult.Success).file.readBytes())
        val progress = events.progresses()
        assertEquals(40_000L, progress.first().resumedFrom)
        assertEquals(40_000L, progress.first().bytesDownloaded)
        assertEquals(1f, progress.last().fraction!!, 1e-4f)
    }

    @Test
    fun `服务端忽略 Range 返回 200 时从头重写，不留下错位内容`() = runTest {
        val body = fakeApk()
        val dir = newDir()
        // 残留是脏数据，长度对但内容错——若续写就会拼出错位的包。
        partOf(dir, body, body.size.toLong()).writeBytes(ByteArray(40_000) { 0x7F })

        val fetcher = apkStub(body, ignoreRange = true)
        val (events, result) = drain(UpdateDownloader(fetcher, dir).download(request(body.size.toLong())))

        assertEquals(40_000L, fetcher.calls.first { it.url == APK_URL }.rangeStart)
        assertArrayEquals(body, (result as DownloadResult.Success).file.readBytes())
        // 服务端回 200，等于从头开始。
        assertEquals(0L, events.progresses().last().resumedFrom)
    }

    @Test
    fun `残留偏移越界（416）时删掉残留并全量重下`() = runTest {
        val body = fakeApk(120_000)
        val dir = newDir()
        // 长度刚好等于远端全长但内容是脏的：服务端只能回 416。
        partOf(dir, body, body.size.toLong()).writeBytes(ByteArray(body.size) { 0x11 })

        val fetcher = apkStub(body)
        val (_, result) = drain(UpdateDownloader(fetcher, dir).download(request(body.size.toLong())))

        val apkCalls = fetcher.calls.filter { it.url == APK_URL }
        assertEquals(2, apkCalls.size)
        assertEquals(body.size.toLong(), apkCalls[0].rangeStart)
        assertEquals(null, apkCalls[1].rangeStart)
        assertArrayEquals(body, (result as DownloadResult.Success).file.readBytes())
    }

    @Test
    fun `MD5 不匹配时删掉临时文件且不产出最终文件`() = runTest {
        val body = fakeApk()
        val dir = newDir()
        val fetcher = StubFetcher().apply {
            binary(APK_URL, body)
            // 清单里的 md5 与实际内容对不上。
            text(MD5_URL, 200, "${ByteArray(16) { 0x2A }.md5Hex()}  $APK_ASSET\n")
        }

        val (_, result) = drain(UpdateDownloader(fetcher, dir).download(request(body.size.toLong())))

        val mismatch = result as DownloadResult.ChecksumMismatch
        assertEquals(body.md5Hex(), mismatch.actualMd5)
        assertEquals(0, filesIn(dir).size)
    }

    @Test
    fun `校验文件里没有 MD5 时归类为 Manifest，且不产出任何文件`() = runTest {
        val body = fakeApk()
        val dir = newDir()
        val fetcher = StubFetcher().apply {
            binary(APK_URL, body)
            text(MD5_URL, 200, "not a checksum")
        }

        val (_, result) = drain(UpdateDownloader(fetcher, dir).download(request(body.size.toLong())))

        assertEquals(DownloadFailure.Manifest, (result as DownloadResult.Failed).reason)
        assertEquals(0, filesIn(dir).size)
    }

    @Test
    fun `校验文件 404 时归类为 Http`() = runTest {
        val body = fakeApk()
        val fetcher = StubFetcher().apply {
            binary(APK_URL, body)
            text(MD5_URL, 404, "Not Found")
        }

        val (_, result) = drain(UpdateDownloader(fetcher, newDir()).download(request(body.size.toLong())))

        assertEquals(DownloadFailure.Http, (result as DownloadResult.Failed).reason)
    }

    @Test
    fun `APK 404 时归类为 Http`() = runTest {
        val body = fakeApk()
        val fetcher = StubFetcher().apply { text(MD5_URL, 200, md5Text(body)) }

        val (_, result) = drain(UpdateDownloader(fetcher, newDir()).download(request(body.size.toLong())))

        assertEquals(DownloadFailure.Http, (result as DownloadResult.Failed).reason)
    }

    @Test
    fun `清单 sizeBytes 比实际大时归类为 Network，并保留已下部分`() = runTest {
        val body = fakeApk()
        val dir = newDir()
        val fetcher = apkStub(body)

        val (_, result) = drain(
            UpdateDownloader(fetcher, dir).download(request(body.size.toLong() + 100)),
        )

        assertEquals(DownloadFailure.Network, (result as DownloadResult.Failed).reason)
        val parts = filesIn(dir)
        assertEquals(1, parts.size)
        assertTrue(parts[0].endsWith(".part"))
        assertEquals(body.size.toLong(), File(dir, parts[0]).length())
    }

    @Test
    fun `清单 sizeBytes 比实际小时归类为 SizeMismatch，并删掉临时文件`() = runTest {
        val body = fakeApk()
        val dir = newDir()
        val fetcher = apkStub(body)

        val (_, result) = drain(
            UpdateDownloader(fetcher, dir).download(request(body.size.toLong() - 100)),
        )

        val mismatch = result as DownloadResult.SizeMismatch
        assertEquals(body.size.toLong(), mismatch.actualBytes)
        assertEquals(0, filesIn(dir).size)
    }

    @Test
    fun `下载中途断流归类为 Network，已下部分留在 part 里`() = runTest {
        val body = fakeApk()
        val dir = newDir()
        val fetcher = StubFetcher().apply {
            truncating(APK_URL, body, afterBytes = 40_000)
            text(MD5_URL, 200, md5Text(body))
        }

        val (_, result) = drain(UpdateDownloader(fetcher, dir).download(request(body.size.toLong())))

        assertEquals(DownloadFailure.Network, (result as DownloadResult.Failed).reason)
        val parts = filesIn(dir)
        assertEquals(1, parts.size)
        assertEquals(40_000L, File(dir, parts[0]).length())
    }

    @Test
    fun `已有校验通过的成品时直接复用，不再请求 APK`() = runTest {
        val body = fakeApk()
        val dir = newDir()
        val fetcher = apkStub(body)
        val downloader = UpdateDownloader(fetcher, dir)
        val target = downloader.targetFile(manifest(sizeBytes = body.size.toLong()), body.md5Hex())
        target.writeBytes(body)

        val (events, result) = drain(downloader.download(request(body.size.toLong())))

        assertEquals(target, (result as DownloadResult.Success).file)
        assertEquals(0, fetcher.callCount(APK_URL))
        assertEquals(1, fetcher.callCount(MD5_URL))
        // resumedFrom == 总长 表示"整份都来自本地"。
        assertEquals(body.size.toLong(), events.progresses().last().resumedFrom)
    }

    @Test
    fun `已有成品但内容被改坏时重新下载`() = runTest {
        val body = fakeApk()
        val dir = newDir()
        val fetcher = apkStub(body)
        val downloader = UpdateDownloader(fetcher, dir)
        val target = downloader.targetFile(manifest(sizeBytes = body.size.toLong()), body.md5Hex())
        target.writeBytes(ByteArray(body.size)) // 长度对、内容错

        val (_, result) = drain(downloader.download(request(body.size.toLong())))

        assertArrayEquals(body, (result as DownloadResult.Success).file.readBytes())
        assertEquals(1, fetcher.callCount(APK_URL))
    }

    @Test
    fun `取消下载时保留 part 以便续传`() = runTest {
        val body = fakeApk(1024 * 1024)
        val gate = CountDownLatch(1)
        val fetcher = StubFetcher().apply {
            text(MD5_URL, 200, md5Text(body))
            on(APK_URL) {
                HttpResponse(
                    statusCode = 200,
                    headers = mapOf("content-length" to body.size.toString()),
                    body = PausingInputStream(ByteArrayInputStream(body), gate),
                )
            }
        }
        val dir = newDir()
        val downloader = UpdateDownloader(fetcher, dir)
        val events = Collections.synchronizedList(mutableListOf<DownloadEvent>())

        val job = launch(Dispatchers.Default) {
            downloader.download(request(body.size.toLong())).collect { events += it }
        }
        // 等第一个进度事件真的发生。真实 IO 线程在跑，runTest 的虚拟时间在这里帮不上忙。
        val deadline = System.currentTimeMillis() + 15_000
        while (events.none { it is DownloadEvent.Progress } && System.currentTimeMillis() < deadline) {
            Thread.sleep(2)
        }
        assertTrue("等不到进度事件", events.any { it is DownloadEvent.Progress })

        job.cancelAndJoin()
        gate.countDown()

        assertTrue(job.isCancelled)
        // 取消不是失败：.part 留着，下次从它的长度继续。
        assertTrue(events.none { it is DownloadEvent.Finished })
        val parts = filesIn(dir).filter { it.endsWith(".part") }
        assertEquals(1, parts.size)
        val length = File(dir, parts[0]).length()
        assertTrue("已下的部分应落盘，实际 $length", length > 0)
        assertTrue("取消后不该下完，实际 $length/${body.size}", length < body.size)
    }

    @Test
    fun `pruneDownloads 只留下指定的文件`() = runTest {
        val dir = newDir()
        val keep = File(dir, "zemote-26101623-arm64-v8a-abcdef01.apk").apply { writeText("keep") }
        File(dir, "zemote-26101501-arm64-v8a-12345678.apk").writeText("stale")
        File(dir, "zemote-26101623-arm64-v8a-abcdef01.apk.part").writeText("partial")

        val removed = UpdateDownloader(StubFetcher(), dir).pruneDownloads(keep)

        assertEquals(2, removed)
        assertEquals(listOf(keep.name), filesIn(dir))
    }

    @Test
    fun `目录不存在时自动创建`() = runTest {
        val body = fakeApk()
        val dir = File(temp.root, "nested/updates")
        val fetcher = apkStub(body)

        val (_, result) = drain(UpdateDownloader(fetcher, dir).download(request(body.size.toLong())))

        assertTrue(result is DownloadResult.Success)
        assertTrue(dir.isDirectory)
    }

    @Test
    fun `下载目录不可写时归类为 Local`() = runTest {
        val body = fakeApk()
        // 用一个"父路径是文件"的目录名，mkdirs 必然失败。
        val blocker = File(temp.root, "blocker").apply { writeText("x") }
        val dir = File(blocker, "updates")
        val fetcher = apkStub(body)

        val (_, result) = drain(UpdateDownloader(fetcher, dir).download(request(body.size.toLong())))

        assertEquals(DownloadFailure.Local, (result as DownloadResult.Failed).reason)
    }

    @Test
    fun `断流后再来一次能靠 part 续传成功`() = runTest {
        val body = fakeApk()
        val dir = newDir()
        // 第一次：下到 40_000 字节断流。
        val broken = StubFetcher().apply {
            truncating(APK_URL, body, afterBytes = 40_000)
            text(MD5_URL, 200, md5Text(body))
        }
        val first = drain(UpdateDownloader(broken, dir).download(request(body.size.toLong())))
        assertEquals(DownloadFailure.Network, (first.second as DownloadResult.Failed).reason)

        // 第二次：网络恢复，应该带 Range 续传并最终校验通过。
        val healthy = apkStub(body)
        val second = drain(UpdateDownloader(healthy, dir).download(request(body.size.toLong())))

        assertEquals(40_000L, healthy.calls.first { it.url == APK_URL }.rangeStart)
        assertArrayEquals(body, (second.second as DownloadResult.Success).file.readBytes())
        assertEquals(0, filesIn(dir).count { it.endsWith(".part") })
    }

    @Test
    fun `进度事件里带了可用的 fraction`() = runTest {
        val body = fakeApk()
        val fetcher = apkStub(body)

        val (events, _) = drain(UpdateDownloader(fetcher, newDir()).download(request(body.size.toLong())))

        val fractions = events.progresses().mapNotNull { it.fraction }
        assertEquals(events.progresses().size, fractions.size)
        assertTrue(fractions.all { it in 0f..1f })
        assertEquals(1f, fractions.last(), 1e-4f)
    }

    @Test
    fun `IOException 之外的运行时异常不会把流卡死`() = runTest {
        // 兜底：即使抛出没预料到的异常，调用方也该拿到 Failed 而不是挂住。
        val body = fakeApk()
        val dir = newDir()
        val fetcher = StubFetcher().apply {
            text(MD5_URL, 200, md5Text(body))
            on(APK_URL) { throw RuntimeException("boom") }
        }

        val (_, result) = drain(UpdateDownloader(fetcher, dir).download(request(body.size.toLong())))

        assertTrue(result is DownloadResult.Failed)
    }

    @Test
    fun `网络异常的分类保留原始信息`() = runTest {
        val body = fakeApk()
        val fetcher = StubFetcher().apply {
            text(MD5_URL, 200, md5Text(body))
            failing(APK_URL, IOException("Software caused connection abort"))
        }

        val (_, result) = drain(UpdateDownloader(fetcher, newDir()).download(request(body.size.toLong())))

        val failed = result as DownloadResult.Failed
        assertEquals(DownloadFailure.Network, failed.reason)
        assertTrue(failed.detail.contains("connection abort"))
    }
}
