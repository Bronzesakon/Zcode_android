package app.zemote.ui.pairing

import app.zemote.engine.EngineState
import app.zemote.pairing.QrScanner
import app.zemote.pairing.RejectReason
import app.zemote.ui.FakeAuthStore
import app.zemote.ui.FakeDeviceRepository
import app.zemote.ui.FakeEnginePort
import app.zemote.ui.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** [QrScanner] 的替身：返回脚本化的原文，或抛异常。 */
private class FakeQrScanner(
    var result: String? = null,
    var failure: Throwable? = null,
) : QrScanner {

    var scans: Int = 0
        private set

    override suspend fun scan(): String? {
        scans += 1
        failure?.let { throw it }
        return result
    }
}

/**
 * 配对流程：前置筛查（拒绝 / 放行）→ 建会话（Running / Fatal / 超时）→ 存设备。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairingViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val validUrl = "https://zcode.z.ai/remote/v4?sid=s1&hash=h1&t=1&name=%E4%B9%A6%E6%88%BF"

    private fun viewModel(
        repository: FakeDeviceRepository = FakeDeviceRepository(),
        scanner: QrScanner = FakeQrScanner(),
        engine: FakeEnginePort = FakeEnginePort(),
        authStore: FakeAuthStore = FakeAuthStore(),
        connectTimeoutMs: Long = 50,
    ) = PairingViewModel(
        repository = repository,
        scanner = scanner,
        engine = engine,
        authStore = authStore,
        connectTimeoutMs = connectTimeoutMs,
    )

    // ------------------------------------------------------------------
    // 前置筛查
    // ------------------------------------------------------------------

    @Test
    fun `空输入被前置筛查拒绝`() = runTest(mainRule.dispatcher) {
        val repository = FakeDeviceRepository()
        val engine = FakeEnginePort()
        val model = viewModel(repository = repository, engine = engine)
        advanceUntilIdle()

        model.submit("   ")
        advanceUntilIdle()

        assertEquals(RejectReason.EMPTY, model.state.value.rejection)
        assertFalse(model.state.value.busy)
        assertTrue(engine.ensuredUrls.isEmpty())
        assertTrue(repository.addedUrls.isEmpty())
    }

    @Test
    fun `不是链接被拒绝`() = runTest(mainRule.dispatcher) {
        val model = viewModel()
        model.submit("这是一段话")
        advanceUntilIdle()

        assertEquals(RejectReason.NOT_A_URL, model.state.value.rejection)
    }

    @Test
    fun `新链接没有登录时进入登录流程`() = runTest(mainRule.dispatcher) {
        val engine = FakeEnginePort()
        val model = viewModel(engine = engine)
        model.submit("https://zcode.z.ai/remote/v4?app_version=3.16.1&sid=s1")
        advanceUntilIdle()

        assertTrue(model.state.value.needsLogin)
        assertEquals(
            "https://zcode.z.ai/remote/v4?app_version=3.16.1&sid=s1",
            model.state.value.pendingUrl,
        )
        assertTrue(engine.ensuredUrls.isEmpty())
    }

    @Test
    fun `已有登录态时新链接直接建会话并带上 token`() = runTest(mainRule.dispatcher) {
        val engine = FakeEnginePort()
        val model = viewModel(engine = engine, authStore = FakeAuthStore(initialToken = "JWT"))
        model.submit("https://zcode.z.ai/remote/v4?app_version=3.16.1&sid=s1")
        advanceUntilIdle()

        assertEquals(listOf("https://zcode.z.ai/remote/v4?app_version=3.16.1&sid=s1"), engine.ensuredUrls)
        assertEquals(listOf<String?>("JWT"), engine.ensuredTokens)
        assertFalse(model.state.value.needsLogin)
    }

    @Test
    fun `缺少 sid 被拒绝`() = runTest(mainRule.dispatcher) {
        val model = viewModel()
        model.submit("https://zcode.z.ai/remote/v4?hash=h1")
        advanceUntilIdle()

        assertEquals(RejectReason.MISSING_SID, model.state.value.rejection)
    }

    @Test
    fun `非 http 协议被拒绝`() = runTest(mainRule.dispatcher) {
        val model = viewModel()
        model.submit("ftp://zcode.z.ai/remote/v4?sid=s1&hash=h1")
        advanceUntilIdle()

        assertEquals(RejectReason.SCHEME_NOT_HTTP, model.state.value.rejection)
    }

    @Test
    fun `改输入会清掉上一次的拒绝原因`() = runTest(mainRule.dispatcher) {
        val model = viewModel()
        model.submit("乱写")
        advanceUntilIdle()
        assertTrue(model.state.value.rejection != null)

        model.onInputChanged("https://")

        assertNull(model.state.value.rejection)
        assertEquals("https://", model.state.value.input)
    }

    // ------------------------------------------------------------------
    // 成功路径
    // ------------------------------------------------------------------

    @Test
    fun `成功路径 建会话后存设备并设为当前设备`() = runTest(mainRule.dispatcher) {
        val repository = FakeDeviceRepository()
        val engine = FakeEnginePort()
        val model = viewModel(repository = repository, engine = engine)
        advanceUntilIdle()

        model.submit(validUrl)
        advanceUntilIdle()

        // 传给引擎的是规范化后的 URL（scheme/host 小写、首尾空白去掉）
        assertEquals(listOf(validUrl), engine.ensuredUrls)
        // 存设备用的是规范化 URL，显示名取 URL 的 name 参数
        assertEquals(listOf(validUrl), repository.addedUrls)
        assertEquals(listOf("书房"), repository.addedNames)
        assertEquals(listOf("new-device"), repository.setCurrentIds)

        val state = model.state.value
        assertEquals("new-device", state.paired?.id)
        assertFalse(state.busy)
        assertNull(state.rejection)
        assertNull(state.error)
    }

    @Test
    fun `粘贴框里的文本也会走同一条路径`() = runTest(mainRule.dispatcher) {
        val repository = FakeDeviceRepository()
        val model = viewModel(repository = repository)
        advanceUntilIdle()

        model.onInputChanged(validUrl)
        model.submit()
        advanceUntilIdle()

        assertEquals(listOf(validUrl), repository.addedUrls)
    }

    // ------------------------------------------------------------------
    // 建会话失败
    // ------------------------------------------------------------------

    @Test
    fun `引擎 Fatal 时不存设备并给出原因`() = runTest(mainRule.dispatcher) {
        val repository = FakeDeviceRepository()
        val engine = FakeEnginePort().apply {
            ensureSessionHook = { engineStateFlow.value = EngineState.Fatal("FFI ABI 不匹配", "detail") }
        }
        val model = viewModel(repository = repository, engine = engine)
        advanceUntilIdle()

        model.submit(validUrl)
        advanceUntilIdle()

        assertTrue(model.state.value.error!!.contains("建会话失败：FFI ABI 不匹配"))
        // detail 是给日志的，不能出现在用户可见文案里
        assertFalse(model.state.value.error!!.contains("detail"))
        assertTrue(repository.addedUrls.isEmpty())
        assertNull(model.state.value.paired)
    }

    @Test
    fun `等会话超时仍保存设备并给提示`() = runTest(mainRule.dispatcher) {
        val repository = FakeDeviceRepository()
        // 引擎一直停在 Idle（模拟网络慢 / 核心没回事件）
        val engine = FakeEnginePort().apply { ensureSessionHook = { } }
        val model = viewModel(repository = repository, engine = engine, connectTimeoutMs = 50)
        advanceUntilIdle()

        model.submit(validUrl)
        advanceUntilIdle()

        assertEquals(listOf(validUrl), repository.addedUrls)
        assertEquals("设备已保存，连接仍在进行中", model.state.value.notice)
        assertNull(model.state.value.error)
    }

    @Test
    fun `存设备失败收敛成错误态且不标成功`() = runTest(mainRule.dispatcher) {
        val repository = FakeDeviceRepository().apply {
            writeFailure = IllegalArgumentException("远控 URL 无法解析（需要 sid）")
        }
        val model = viewModel(repository = repository)
        advanceUntilIdle()

        model.submit(validUrl)
        advanceUntilIdle()

        assertEquals("远控 URL 无法解析（需要 sid）", model.state.value.error)
        assertNull(model.state.value.paired)
        assertFalse(model.state.value.busy)
    }

    @Test
    fun `设为当前设备失败不否定配对成功`() = runTest(mainRule.dispatcher) {
        val repository = FakeDeviceRepository().apply {
            setCurrentFailure = IllegalStateException("写盘失败")
        }
        val model = viewModel(repository = repository)
        advanceUntilIdle()

        model.submit(validUrl)
        advanceUntilIdle()

        assertEquals("new-device", model.state.value.paired?.id)
        assertTrue(model.state.value.notice!!.contains("没能设为当前设备"))
    }

    // ------------------------------------------------------------------
    // 扫码
    // ------------------------------------------------------------------

    @Test
    fun `扫码返回 null 视为用户取消`() = runTest(mainRule.dispatcher) {
        val scanner = FakeQrScanner(result = null)
        val engine = FakeEnginePort()
        val model = viewModel(scanner = scanner, engine = engine)
        advanceUntilIdle()

        model.scan()
        advanceUntilIdle()

        assertEquals(1, scanner.scans)
        assertFalse(model.state.value.busy)
        assertNull(model.state.value.rejection)
        assertNull(model.state.value.error)
        assertTrue(engine.ensuredUrls.isEmpty())
    }

    @Test
    fun `扫码结果写进输入框并直接提交`() = runTest(mainRule.dispatcher) {
        val repository = FakeDeviceRepository()
        val scanner = FakeQrScanner(result = "  $validUrl  ")
        val model = viewModel(repository = repository, scanner = scanner)
        advanceUntilIdle()

        model.scan()
        advanceUntilIdle()

        assertEquals(validUrl, model.state.value.input)
        assertEquals(listOf(validUrl), repository.addedUrls)
    }

    @Test
    fun `扫码失败收敛成错误态`() = runTest(mainRule.dispatcher) {
        val scanner = FakeQrScanner(failure = IllegalStateException("相机不可用"))
        val model = viewModel(scanner = scanner)
        advanceUntilIdle()

        model.scan()
        advanceUntilIdle()

        assertEquals("IllegalStateException: 相机不可用", model.state.value.error)
        assertFalse(model.state.value.busy)
    }

    // ------------------------------------------------------------------
    // 并发保护
    // ------------------------------------------------------------------

    @Test
    fun `建会话期间重复提交被忽略`() = runTest(mainRule.dispatcher) {
        val repository = FakeDeviceRepository()
        // 停在「等会话」上：状态不推进，busy 保持 true，直到虚拟时间走完超时
        val engine = FakeEnginePort().apply { ensureSessionHook = { } }
        val model = viewModel(repository = repository, engine = engine, connectTimeoutMs = 50)
        advanceUntilIdle()

        model.submit(validUrl)
        assertTrue(model.state.value.busy)

        model.submit(validUrl)
        model.submit(validUrl)
        advanceUntilIdle()

        // 三次提交只落一次设备
        assertEquals(1, repository.addedUrls.size)
    }
}
