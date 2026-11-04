package app.zemote.update

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新检测：版本比较与失败分类。
 * 全程用 [StubFetcher]，不碰网络。
 */
class UpdateCheckerTest {

    private val current = 26101501 // 比清单里的 26101623 旧

    private fun checker(
        fetcher: StubFetcher,
        currentVersionCode: Int = current,
        maxManifestBytes: Long = 256 * 1024,
    ) = UpdateChecker(
        fetcher = fetcher,
        currentVersionCode = currentVersionCode,
        maxManifestBytes = maxManifestBytes,
    )

    /** 一个"正常发布"的桩：release 里有清单 + APK + md5 三个资产。 */
    private fun healthyFetcher(
        manifestBody: String = manifestJson(),
        md5Body: String = md5Text(fakeApk()),
        apkBody: ByteArray = fakeApk(),
    ): StubFetcher = StubFetcher().apply {
        text(API_URL, 200, releaseJson(MANIFEST_ASSET to MANIFEST_URL, APK_ASSET to APK_URL, MD5_ASSET to MD5_URL))
        text(MANIFEST_URL, 200, manifestBody)
        text(MD5_URL, 200, md5Body)
        binary(APK_URL, apkBody)
    }

    @Test
    fun `清单 versionCode 更新时给出更新`() = runTest {
        val result = checker(healthyFetcher()).check()

        val available = result as UpdateCheckResult.UpdateAvailable
        assertEquals(26101623, available.manifest.versionCode)
        assertEquals("0.1.0-dev.20261016.2314", available.manifest.versionName)
        assertEquals(APK_URL, available.apkUrl)
        assertEquals(MD5_URL, available.md5Url)
        assertEquals(UpdateConfig.RELEASE_PAGE_URL, available.releasePageUrl)
    }

    @Test
    fun `清单 versionCode 相同时视为无更新`() = runTest {
        val fetcher = healthyFetcher(manifestBody = manifestJson(versionCode = current))
        val result = checker(fetcher).check()

        assertEquals(UpdateCheckResult.UpToDate(current), result)
        // 无更新就不该再去下 APK。
        assertEquals(0, fetcher.callCount(APK_URL))
    }

    @Test
    fun `清单 versionCode 更旧时视为无更新`() = runTest {
        // 用户手上可能装了更新的内测包，或者滚动发布被回滚过一次。
        val fetcher = healthyFetcher(manifestBody = manifestJson(versionCode = current - 1))
        assertEquals(UpdateCheckResult.UpToDate(current), checker(fetcher).check())
    }

    @Test
    fun `versionName 变了但 versionCode 没变时视为无更新`() = runTest {
        // 同一小时内两次构建共用同一个 code，此时不提示更新。
        val fetcher = healthyFetcher(
            manifestBody = manifestJson(versionCode = current, versionName = "0.1.0-dev.20261016.2359"),
        )
        assertEquals(UpdateCheckResult.UpToDate(current), checker(fetcher).check())
    }

    @Test
    fun `release 不存在时返回 NoPublishedRelease 而不是失败`() = runTest {
        val fetcher = StubFetcher().text(API_URL, 404, """{"message": "Not Found"}""")
        val result = checker(fetcher).check()

        assertTrue(result is UpdateCheckResult.NoPublishedRelease)
    }

    @Test
    fun `release 返回 403 限流时归类为 Http`() = runTest {
        val fetcher = StubFetcher().text(API_URL, 403, """{"message": "rate limit"}""")
        val result = checker(fetcher).check() as UpdateCheckResult.Failed

        assertEquals(UpdateFailure.Http, result.reason)
        assertTrue(result.detail.contains("403"))
    }

    @Test
    fun `release 返回 500 时归类为 Http`() = runTest {
        val fetcher = StubFetcher().text(API_URL, 500, "boom")
        assertEquals(UpdateFailure.Http, (checker(fetcher).check() as UpdateCheckResult.Failed).reason)
    }

    @Test
    fun `release 请求网络异常时归类为 Network`() = runTest {
        val fetcher = StubFetcher().failing(API_URL, IOException("connect timed out"))
        val result = checker(fetcher).check() as UpdateCheckResult.Failed

        assertEquals(UpdateFailure.Network, result.reason)
        assertTrue(result.detail.contains("connect timed out"))
    }

    @Test
    fun `清单请求网络异常时归类为 Network`() = runTest {
        val fetcher = healthyFetcher().failing(MANIFEST_URL, IOException("unexpected end of stream"))
        assertEquals(UpdateFailure.Network, (checker(fetcher).check() as UpdateCheckResult.Failed).reason)
    }

    @Test
    fun `release 响应不是 JSON 时归类为 MalformedManifest`() = runTest {
        val fetcher = StubFetcher().text(API_URL, 200, "<html>gateway error</html>")
        assertEquals(
            UpdateFailure.MalformedManifest,
            (checker(fetcher).check() as UpdateCheckResult.Failed).reason,
        )
    }

    @Test
    fun `清单 JSON 坏掉时归类为 MalformedManifest`() = runTest {
        val fetcher = healthyFetcher(manifestBody = """{"versionCode": 26101623,""")
        val result = checker(fetcher).check() as UpdateCheckResult.Failed

        assertEquals(UpdateFailure.MalformedManifest, result.reason)
        assertTrue(result.detail.contains("版本清单解析失败"))
    }

    @Test
    fun `清单缺 sizeBytes 字段时归类为 MalformedManifest`() = runTest {
        val fetcher = healthyFetcher(
            manifestBody = """{"versionCode": 26101623, "versionName": "0.1.0-dev.x",
                "abi": "arm64-v8a", "apkAsset": "$APK_ASSET", "md5Asset": "$MD5_ASSET"}""",
        )
        val result = checker(fetcher).check() as UpdateCheckResult.Failed

        assertEquals(UpdateFailure.MalformedManifest, result.reason)
        // 缺字段必须是错误：拿缺省值去比较版本会静默地永不更新。
        assertTrue(result.detail.contains("sizeBytes"))
    }

    @Test
    fun `清单缺 versionCode 字段时归类为 MalformedManifest`() = runTest {
        val fetcher = healthyFetcher(manifestBody = """{"versionName": "0.1.0-dev.x"}""")
        assertEquals(
            UpdateFailure.MalformedManifest,
            (checker(fetcher).check() as UpdateCheckResult.Failed).reason,
        )
    }

    @Test
    fun `清单出现未知字段时仍能解析`() = runTest {
        // CI 以后加字段（签名摘要、最低支持版本）不该让旧客户端直接报废。
        val body = manifestJson().trimEnd('}') + """, "sha256": "deadbeef", "notes": "x"}"""
        val result = checker(healthyFetcher(manifestBody = body)).check()

        assertTrue(result is UpdateCheckResult.UpdateAvailable)
    }

    @Test
    fun `清单 sizeBytes 非正时归类为 MalformedManifest`() = runTest {
        val fetcher = healthyFetcher(manifestBody = manifestJson(sizeBytes = 0))
        assertEquals(
            UpdateFailure.MalformedManifest,
            (checker(fetcher).check() as UpdateCheckResult.Failed).reason,
        )
    }

    @Test
    fun `清单 abi 不匹配时归类为 InvalidAsset`() = runTest {
        val fetcher = healthyFetcher(manifestBody = manifestJson(abi = "armeabi-v7a"))
        val result = checker(fetcher).check() as UpdateCheckResult.Failed

        assertEquals(UpdateFailure.InvalidAsset, result.reason)
        assertTrue(result.detail.contains("armeabi-v7a"))
    }

    @Test
    fun `release 里没有清单资产时归类为 InvalidAsset`() = runTest {
        // 清单上线前发的旧 release 就是这个样子。
        val fetcher = StubFetcher().text(
            API_URL,
            200,
            releaseJson(APK_ASSET to APK_URL, MD5_ASSET to MD5_URL),
        )
        val result = checker(fetcher).check() as UpdateCheckResult.Failed

        assertEquals(UpdateFailure.InvalidAsset, result.reason)
        assertTrue(result.detail.contains(MANIFEST_ASSET))
    }

    @Test
    fun `清单指向的 APK 资产不存在时归类为 InvalidAsset`() = runTest {
        val fetcher = StubFetcher().apply {
            text(API_URL, 200, releaseJson(MANIFEST_ASSET to MANIFEST_URL, MD5_ASSET to MD5_URL))
            text(MANIFEST_URL, 200, manifestJson())
        }
        val result = checker(fetcher).check() as UpdateCheckResult.Failed

        assertEquals(UpdateFailure.InvalidAsset, result.reason)
        assertTrue(result.detail.contains(APK_ASSET))
    }

    @Test
    fun `清单指向的 md5 资产不存在时归类为 InvalidAsset`() = runTest {
        val fetcher = StubFetcher().apply {
            text(API_URL, 200, releaseJson(MANIFEST_ASSET to MANIFEST_URL, APK_ASSET to APK_URL))
            text(MANIFEST_URL, 200, manifestJson())
        }
        val result = checker(fetcher).check() as UpdateCheckResult.Failed

        assertEquals(UpdateFailure.InvalidAsset, result.reason)
        assertTrue(result.detail.contains(MD5_ASSET))
    }

    @Test
    fun `清单直链 404 时归类为 InvalidAsset`() = runTest {
        val fetcher = StubFetcher().apply {
            text(API_URL, 200, releaseJson(MANIFEST_ASSET to MANIFEST_URL, APK_ASSET to APK_URL, MD5_ASSET to MD5_URL))
            text(MANIFEST_URL, 404, "Not Found")
        }
        assertEquals(
            UpdateFailure.InvalidAsset,
            (checker(fetcher).check() as UpdateCheckResult.Failed).reason,
        )
    }

    @Test
    fun `清单响应超过上限时归类为 MalformedManifest`() = runTest {
        val fetcher = healthyFetcher()
        assertEquals(
            UpdateFailure.MalformedManifest,
            (checker(fetcher, maxManifestBytes = 16).check() as UpdateCheckResult.Failed).reason,
        )
    }

    @Test
    fun `资产缺少 browser_download_url 时归类为 InvalidAsset`() = runTest {
        val fetcher = StubFetcher().apply {
            text(
                API_URL,
                200,
                releaseJson(MANIFEST_ASSET to MANIFEST_URL, APK_ASSET to "", MD5_ASSET to MD5_URL),
            )
            text(MANIFEST_URL, 200, manifestJson())
        }
        assertEquals(
            UpdateFailure.InvalidAsset,
            (checker(fetcher).check() as UpdateCheckResult.Failed).reason,
        )
    }

    @Test
    fun `只请求一次 release 与一次清单`() = runTest {
        val fetcher = healthyFetcher()
        checker(fetcher).check()

        assertEquals(1, fetcher.callCount(API_URL))
        assertEquals(1, fetcher.callCount(MANIFEST_URL))
    }
}
