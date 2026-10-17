package app.zemote.ui.update

import app.zemote.ui.FakeApkInstaller
import app.zemote.ui.MainDispatcherRule
import app.zemote.update.APK_ASSET
import app.zemote.update.APK_URL
import app.zemote.update.API_URL
import app.zemote.update.InstallResult
import app.zemote.update.MANIFEST_ASSET
import app.zemote.update.MANIFEST_URL
import app.zemote.update.MD5_ASSET
import app.zemote.update.MD5_URL
import app.zemote.update.StubFetcher
import app.zemote.update.UpdateCheckResult
import app.zemote.update.UpdateChecker
import app.zemote.update.UpdateDownloader
import app.zemote.update.UpdateFailure
import app.zemote.update.fakeApk
import app.zemote.update.manifestJson
import app.zemote.update.md5Text
import app.zemote.update.releaseJson
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 更新页的状态映射：检查（四种结果）→ 下载（进度 / 成功 / 校验失败）→ 安装（三种结果）。
 *
 * 用真实的 [UpdateChecker] / [UpdateDownloader] + 假 `HttpFetcher`（`StubFetcher`）：
 * 这两层本来就可注入，跑真实现能顺带验证「事件序列 → 状态」的映射没写错。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @get:Rule
    val temp = TemporaryFolder()

    private val apkBytes: ByteArray = fakeApk(4 * 1024)

    /** 一份「有更新、能下载、校验能过」的完整桩。 */
    private fun workingFetcher(manifestVersionCode: Int = 26_101_623): StubFetcher = StubFetcher()
        .text(
            API_URL,
            200,
            releaseJson(
                MANIFEST_ASSET to MANIFEST_URL,
                APK_ASSET to APK_URL,
                MD5_ASSET to MD5_URL,
            ),
        )
        .text(
            MANIFEST_URL,
            200,
            manifestJson(versionCode = manifestVersionCode, sizeBytes = apkBytes.size.toLong()),
        )
        .text(MD5_URL, 200, md5Text(apkBytes))
        .binary(APK_URL, apkBytes)

    private fun viewModel(
        fetcher: StubFetcher,
        installer: FakeApkInstaller = FakeApkInstaller(),
        currentVersionCode: Int = 1,
    ): UpdateViewModel = UpdateViewModel(
        checker = UpdateChecker(fetcher = fetcher, currentVersionCode = currentVersionCode),
        downloader = UpdateDownloader(
            fetcher = fetcher,
            directory = temp.newFolder("updates"),
            ioDispatcher = mainRule.dispatcher,
        ),
        installer = installer,
        currentVersionCode = currentVersionCode,
    )

    // ------------------------------------------------------------------
    // 检查
    // ------------------------------------------------------------------

    @Test
    fun `检查到有更新`() = runTest(mainRule.dispatcher) {
        val model = viewModel(workingFetcher())
        assertEquals(UpdatePhase.Idle, model.state.value.phase)
        assertEquals(1, model.state.value.currentVersionCode)

        model.check()
        advanceUntilIdle()

        assertEquals(UpdatePhase.UpdateAvailable, model.state.value.phase)
        assertEquals(26_101_623, model.state.value.manifest?.versionCode)
        assertEquals("https://github.com/Bronzesakon/Zcode_android/releases/tag/android-pre", model.state.value.releasePageUrl)
        assertTrue(model.state.value.canDownload)
        assertNull(model.state.value.error)
    }

    @Test
    fun `已是最新`() = runTest(mainRule.dispatcher) {
        val model = viewModel(workingFetcher(manifestVersionCode = 1), currentVersionCode = 1)

        model.check()
        advanceUntilIdle()

        assertEquals(UpdatePhase.UpToDate, model.state.value.phase)
        assertNull(model.state.value.manifest)
        assertNull(model.state.value.error)
    }

    @Test
    fun `还没发过预发布版本 不算失败`() = runTest(mainRule.dispatcher) {
        // StubFetcher 对没配置的 URL 返回 404
        val model = viewModel(StubFetcher())

        model.check()
        advanceUntilIdle()

        assertEquals(UpdatePhase.NoRelease, model.state.value.phase)
        assertNull(model.state.value.error)
        assertNull(model.state.value.failure)
    }

    @Test
    fun `网络失败收敛成错误态并给出分类`() = runTest(mainRule.dispatcher) {
        val model = viewModel(StubFetcher().failing(API_URL))

        model.check()
        advanceUntilIdle()

        assertEquals(UpdatePhase.Failed, model.state.value.phase)
        assertEquals(UpdateFailure.Network, model.state.value.failure)
        assertTrue(model.state.value.error!!.contains("connect timed out"))
        assertTrue(!model.state.value.canDownload)
    }

    @Test
    fun `清单里没有清单资产时分类为资产异常`() = runTest(mainRule.dispatcher) {
        val fetcher = StubFetcher()
            .text(API_URL, 200, releaseJson(APK_ASSET to APK_URL, MD5_ASSET to MD5_URL))
        val model = viewModel(fetcher)

        model.check()
        advanceUntilIdle()

        assertEquals(UpdatePhase.Failed, model.state.value.phase)
        assertEquals(UpdateFailure.InvalidAsset, model.state.value.failure)
    }

    @Test
    fun `dismissError 不清掉已经拿到的更新结果`() = runTest(mainRule.dispatcher) {
        val model = viewModel(workingFetcher())
        model.check()
        advanceUntilIdle()
        assertEquals(UpdatePhase.UpdateAvailable, model.state.value.phase)

        model.dismissError()

        assertNull(model.state.value.error)
        assertEquals(UpdatePhase.UpdateAvailable, model.state.value.phase)
    }

    // ------------------------------------------------------------------
    // 下载
    // ------------------------------------------------------------------

    @Test
    fun `没有可下载的更新时下载是空操作`() = runTest(mainRule.dispatcher) {
        val model = viewModel(workingFetcher())

        model.download()
        advanceUntilIdle()

        assertEquals(UpdatePhase.Idle, model.state.value.phase)
        assertNull(model.state.value.downloadedFile)
    }

    @Test
    fun `下载成功后进入待安装并带上文件`() = runTest(mainRule.dispatcher) {
        val model = viewModel(workingFetcher())
        model.check()
        advanceUntilIdle()

        model.download()
        advanceUntilIdle()

        val state = model.state.value
        assertEquals(UpdatePhase.ReadyToInstall, state.phase)
        val file = state.downloadedFile
        assertNotNull(file)
        assertTrue(file!!.isFile)
        assertEquals(apkBytes.size.toLong(), file.length())
        // 进度落在 100%
        assertEquals(1.0f, state.progress?.fraction)
        assertNull(state.error)
    }

    @Test
    fun `下载校验失败时进入失败态且不产出文件`() = runTest(mainRule.dispatcher) {
        val fetcher = workingFetcher().text(MD5_URL, 200, "${"0".repeat(32)}  $APK_ASSET\n")
        val model = viewModel(fetcher)
        model.check()
        advanceUntilIdle()

        model.download()
        advanceUntilIdle()

        assertEquals(UpdatePhase.Failed, model.state.value.phase)
        assertTrue(model.state.value.error!!.contains("MD5"))
        assertNull(model.state.value.downloadedFile)
    }

    @Test
    fun `下载中途断流时进入失败态`() = runTest(mainRule.dispatcher) {
        val fetcher = workingFetcher()
        // 覆盖 APK 直链：声明全长但只给一半就断
        fetcher.truncating(APK_URL, apkBytes, apkBytes.size / 2)
        val model = viewModel(fetcher)
        model.check()
        advanceUntilIdle()

        model.download()
        advanceUntilIdle()

        assertEquals(UpdatePhase.Failed, model.state.value.phase)
        assertNull(model.state.value.downloadedFile)
    }

    // ------------------------------------------------------------------
    // 安装
    // ------------------------------------------------------------------

    @Test
    fun `没有下载好的包时安装给错误`() = runTest(mainRule.dispatcher) {
        val model = viewModel(workingFetcher())

        model.install()
        advanceUntilIdle()

        assertEquals("还没有下载好的安装包", model.state.value.error)
    }

    @Test
    fun `安装成功把结果映射进状态`() = runTest(mainRule.dispatcher) {
        val installer = FakeApkInstaller().apply { result = InstallResult.Launched }
        val model = viewModel(workingFetcher(), installer = installer)
        model.check()
        advanceUntilIdle()
        model.download()
        advanceUntilIdle()

        model.install()
        advanceUntilIdle()

        assertEquals(InstallResult.Launched, model.state.value.installResult)
        assertEquals(1, installer.installed.size)
        assertTrue(installer.installed[0].isFile)

        model.dismissInstallResult()
        assertNull(model.state.value.installResult)
    }

    @Test
    fun `需要授权时给出可展示的提示并可跳授权页`() = runTest(mainRule.dispatcher) {
        val installer = FakeApkInstaller().apply { result = InstallResult.NeedInstallPermission }
        val model = viewModel(workingFetcher(), installer = installer)
        model.check()
        advanceUntilIdle()
        model.download()
        advanceUntilIdle()

        model.install()
        advanceUntilIdle()
        assertTrue(model.state.value.installResult is InstallResult.NeedInstallPermission)

        model.openUnknownSourcesSettings()
        advanceUntilIdle()

        assertNull(model.state.value.error)
    }

    @Test
    fun `授权页打不开时给出手动指引`() = runTest(mainRule.dispatcher) {
        val installer = FakeApkInstaller().apply { settingsOpened = false }
        val model = viewModel(workingFetcher(), installer = installer)

        model.openUnknownSourcesSettings()
        advanceUntilIdle()

        assertTrue(model.state.value.error!!.contains("手动"))
    }

    @Test
    fun `签名不一致的安装结果原样映射`() = runTest(mainRule.dispatcher) {
        val installer = FakeApkInstaller().apply {
            result = InstallResult.SignerMismatch(expected = "aa", actual = "bb")
        }
        val model = viewModel(workingFetcher(), installer = installer)
        model.check()
        advanceUntilIdle()
        model.download()
        advanceUntilIdle()

        model.install()
        advanceUntilIdle()

        val result = model.state.value.installResult
        assertTrue(result is InstallResult.SignerMismatch)
        assertTrue((result as InstallResult.SignerMismatch).userMessage.contains("签名"))
    }

    @Test
    fun `包不存在时结果映射成 MissingFile`() = runTest(mainRule.dispatcher) {
        val installer = FakeApkInstaller().apply {
            result = InstallResult.MissingFile("/data/x.apk")
        }
        val model = viewModel(workingFetcher(), installer = installer)
        model.check()
        advanceUntilIdle()
        model.download()
        advanceUntilIdle()

        model.install()
        advanceUntilIdle()

        assertTrue(model.state.value.installResult is InstallResult.MissingFile)
    }

    @Test
    fun `取消下载把状态退回可下载`() = runTest(mainRule.dispatcher) {
        val model = viewModel(workingFetcher())
        model.check()
        advanceUntilIdle()

        model.cancelDownload()
        advanceUntilIdle()

        assertEquals(UpdatePhase.UpdateAvailable, model.state.value.phase)
    }

    @Test
    fun `下载结果里的文件落在下载目录`() = runTest(mainRule.dispatcher) {
        val directory = temp.newFolder("updates")
        val fetcher = workingFetcher()
        val model = UpdateViewModel(
            checker = UpdateChecker(fetcher = fetcher, currentVersionCode = 1),
            downloader = UpdateDownloader(
                fetcher = fetcher,
                directory = directory,
                ioDispatcher = mainRule.dispatcher,
            ),
            installer = FakeApkInstaller(),
            currentVersionCode = 1,
        )
        model.check()
        advanceUntilIdle()
        model.download()
        advanceUntilIdle()

        val file = model.state.value.downloadedFile
        assertNotNull(file)
        assertEquals(directory, file!!.parentFile)
        assertEquals(
            setOf<File>(file),
            directory.listFiles()!!.toSet(),
        )
    }

    @Test
    fun `再次检查变成已是最新时会清掉旧的清单`() = runTest(mainRule.dispatcher) {
        val fetcher = workingFetcher()
        val model = viewModel(fetcher)
        model.check()
        advanceUntilIdle()
        assertNotNull(model.state.value.manifest)

        // 同一台机器上把清单改成同一个 versionCode：再检查一次应当回到「已是最新」
        fetcher.text(
            MANIFEST_URL,
            200,
            manifestJson(versionCode = 1, sizeBytes = apkBytes.size.toLong()),
        )
        model.check()
        advanceUntilIdle()

        assertEquals(UpdatePhase.UpToDate, model.state.value.phase)
        assertNull(model.state.value.manifest)
        assertNull(model.state.value.releasePageUrl)
    }
}
