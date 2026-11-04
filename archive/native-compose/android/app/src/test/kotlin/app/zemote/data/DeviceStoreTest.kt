package app.zemote.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [DeviceStore] 的 JVM 单测：临时目录里建一份真的 preferences DataStore，配 [FakeSecretCipher]。
 * 不依赖 Android 运行时，也不碰 Keystore。
 *
 * 每个用例都拿到**自己的临时目录 + 自己的文件名**：DataStore 强制「一个文件只能有一个实例」，
 * 用例之间共用路径会直接踩到 `IllegalStateException: There are multiple DataStores active
 * for the same file`（或写盘时的 "Unable to rename"）。这里的文件计数保证同一个用例里即使
 * 建了多份 DataStore（例如模拟重启）也不会撞路径。
 */
class DeviceStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var preferencesFile: File
    private var now = 1_000L
    private var idSeq = 0
    private var fileSeq = 0

    @Before
    fun setUp() {
        // 让 DataStore 走 SDK>=26 的 Files.move 分支（真机 minSdk=26 就是这条），
        // 否则 JVM 桩里的 SDK_INT=0 会落到 Windows+JDK17 上必然失败的 renameTo。详见 AndroidApiLevel。
        AndroidApiLevel.forceApi26()
        openDataStore()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** 每个用例一份全新的文件路径，绝不与别的用例（或同用例里上一个 DataStore）共用。 */
    private fun newStoreFile(): File =
        File(tempFolder.root, "store-${++fileSeq}.preferences_pb")

    private fun openDataStore(file: File = newStoreFile()) {
        preferencesFile = file
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
    }

    private fun store(
        cipher: SecretCipher = FakeSecretCipher(),
        clock: () -> Long = { now },
    ): DeviceStore = DeviceStore(
        dataStore = dataStore,
        cipher = cipher,
        clock = clock,
        idFactory = { "id-${++idSeq}" },
    )

    private fun url(
        sid: String = "sid-1",
        hash: String = "HASH-1",
        t: Long = 1_700_000_000L,
        name: String = "Desk",
        host: String = "zcode.z.ai",
    ): String = "https://$host/remote/v4?sid=$sid&hash=$hash&t=$t&mid=mid-1&name=$name"

    private fun diskText(): String = String(preferencesFile.readBytes(), Charsets.ISO_8859_1)

    // ------------------------------------------------------------------
    // 增删改查
    // ------------------------------------------------------------------

    @Test
    fun addDevice_parsesUrlAndExposesPlaintext() = runTest {
        val store = store()
        val record = store.addDevice(url(sid = "sid-42", name = "MacBook"))

        assertEquals("id-1", record.id)
        assertEquals("MacBook", record.name)
        assertEquals("zcode.z.ai", record.host)
        assertEquals("sid-42", record.sid)
        assertEquals(1_000L, record.addedAt)
        assertNull(record.lastConnectedAt)
        assertEquals(RelayStatus.IDLE, record.lastRelayStatus)
        assertFalse(record.needsRepair)
        // 内存里必须是明文（引擎要拿 hash 做 HMAC 证明）
        assertTrue(record.remoteUrl!!.contains("hash=HASH-1"))

        val fromDisk = store.devices.first().single()
        assertEquals(record, fromDisk)
    }

    @Test
    fun addDevice_explicitNameWins_overUrlName() = runTest {
        val store = store()
        val record = store.addDevice(url(name = "FromUrl"), name = "  用户命名  ")
        assertEquals("用户命名", record.name)
    }

    @Test
    fun addDevice_fallsBackToHostWhenNoName() = runTest {
        val store = store()
        val record = store.addDevice("https://zcode.z.ai/remote/v4?sid=s1&hash=h1&t=1")
        assertEquals("zcode.z.ai", record.name)
    }

    @Test
    fun addDevice_rejectsUnparseableOrSidlessUrl() = runTest {
        val store = store()
        assertRejected(store, "not a url")
        assertRejected(store, "https://zcode.z.ai/remote/v4?hash=h&t=1")
        assertRejected(store, "http://zcode.z.ai/remote/v4?sid=s&hash=h&t=1")
        assertTrue(store.devices.first().isEmpty())
    }

    @Test
    fun removeDevice_removesAndClearsCurrentSelection() = runTest {
        val store = store()
        val a = store.addDevice(url(sid = "a"))
        val b = store.addDevice(url(sid = "b"))
        store.setCurrentDevice(a.id)
        assertEquals(a.id, store.currentDeviceId.first())

        assertTrue(store.removeDevice(a.id))
        assertEquals(listOf(b.id), store.devices.first().map { it.id })
        assertNull(store.currentDeviceId.first())
        assertNull(store.currentDevice.first())

        assertFalse("重复删除返回 false", store.removeDevice(a.id))
    }

    @Test
    fun renameDevice_updatesNameAndIgnoresBlank() = runTest {
        val store = store()
        val a = store.addDevice(url(sid = "a"), name = "Old")

        assertTrue(store.renameDevice(a.id, "  New  "))
        assertEquals("New", store.devices.first().single().name)
        assertFalse("空白名字不生效", store.renameDevice(a.id, "   "))
        assertEquals("New", store.devices.first().single().name)
        assertFalse("未知 id 返回 false", store.renameDevice("nope", "X"))
    }

    @Test
    fun setCurrentDevice_persistsAndRejectsUnknownId() = runTest {
        val store = store()
        val a = store.addDevice(url(sid = "a"))
        store.setCurrentDevice(a.id)
        assertEquals(a.id, store.currentDevice.first()!!.id)

        assertRejected { store.setCurrentDevice("ghost") }
        // 原值没被破坏
        assertEquals(a.id, store.currentDeviceId.first())

        store.setCurrentDevice(null)
        assertNull(store.currentDeviceId.first())
    }

    @Test
    fun markConnected_and_setRelayStatus_persist() = runTest {
        val store = store()
        val a = store.addDevice(url(sid = "a"))

        now = 5_000L
        assertTrue(store.markConnected(a.id))
        assertTrue(store.setRelayStatus(a.id, RelayStatus.PAIRED))

        val reloaded = store.devices.first().single()
        assertEquals(5_000L, reloaded.lastConnectedAt)
        assertEquals(RelayStatus.PAIRED, reloaded.lastRelayStatus)
    }

    @Test
    fun devices_areOrderedBySortKey_andReorderable() = runTest {
        val store = store()
        val a = store.addDevice(url(sid = "a"))
        val b = store.addDevice(url(sid = "b"))
        val c = store.addDevice(url(sid = "c"))
        assertEquals(listOf(a.id, b.id, c.id), store.devices.first().map { it.id })
        assertTrue(store.devices.first().zipWithNext().all { (x, y) -> x.sortKey < y.sortKey })

        assertTrue(store.setSortKey(c.id, -1L))
        assertEquals(listOf(c.id, a.id, b.id), store.devices.first().map { it.id })
    }

    // ------------------------------------------------------------------
    // 去重：同 host + 同 sid ⇒ 更新已有记录
    // ------------------------------------------------------------------

    @Test
    fun addDevice_duplicateHostAndSid_updatesExistingRecord() = runTest {
        val store = store()
        val first = store.addDevice(url(sid = "same", hash = "OLD-HASH", t = 100), name = "Desk")
        now = 9_999L
        val second = store.addDevice(url(sid = "same", hash = "NEW-HASH", t = 200), name = "Desk 2")

        val list = store.devices.first()
        assertEquals("不能新增第二条", 1, list.size)
        val merged = list.single()
        assertEquals("id 保持不变", first.id, second.id)
        assertEquals("addedAt 指首次添加，保持", 1_000L, merged.addedAt)
        assertEquals("sortKey 不因重新配对跳动", first.sortKey, merged.sortKey)
        assertEquals("名字更新", "Desk 2", merged.name)
        assertTrue("凭据换成新的", merged.remoteUrl!!.contains("hash=NEW-HASH"))
        assertTrue("URL 里的 t 也换成新的", merged.remoteUrl!!.contains("t=200"))
        assertEquals(RelayStatus.IDLE, merged.lastRelayStatus)
    }

    @Test
    fun addDevice_duplicateWithoutNameKeepsExistingName() = runTest {
        val store = store()
        store.addDevice(url(sid = "same", name = "FromUrl"), name = "用户命名")
        val again = store.addDevice(url(sid = "same", hash = "H2"))
        assertEquals("用户命名", again.name)
        assertEquals(1, store.devices.first().size)
    }

    @Test
    fun addDevice_sameSidDifferentHost_isDifferentDevice() = runTest {
        val store = store()
        store.addDevice(url(sid = "s", host = "a.example.com"))
        store.addDevice(url(sid = "s", host = "b.example.com"))
        assertEquals(2, store.devices.first().size)
    }

    // ------------------------------------------------------------------
    // 加解密往返 + 落盘必须是密文
    // ------------------------------------------------------------------

    @Test
    fun urlRoundTripsThroughCipher() = runTest {
        val store = store()
        val original = url(hash = "ROUND-TRIP-HASH")
        store.addDevice(original)
        assertEquals(original, store.devices.first().single().remoteUrl)
        // 明文不能被原样写进 preferences
        assertFalse(diskText().contains("ROUND-TRIP-HASH"))
        assertTrue(diskText().contains(FakeSecretCipher.PREFIX))
    }

    @Test
    fun diskContainsNoPlaintextCredentials() = runTest {
        val store = store()
        val hash = "SUPER-SECRET-PAIRING-HASH"
        val fullUrl = url(hash = hash)
        store.addDevice(fullUrl, name = "Desk")

        assertTrue("DataStore 文件应该已经落盘", preferencesFile.exists())
        val text = diskText()
        assertFalse("磁盘上不得出现明文 hash", text.contains(hash))
        assertFalse("磁盘上不得出现整条 URL", text.contains(fullUrl))
        assertFalse(text.contains("hash="))
        assertTrue("应该只有密文", text.contains(FakeSecretCipher.PREFIX))
    }

    // ------------------------------------------------------------------
    // 密钥丢失（Keystore 卸载/清数据）⇒ 优雅降级，不抛异常、不返回空串
    // ------------------------------------------------------------------

    @Test
    fun keyLoss_degradesToNeedsRepair_withoutThrowing() = runTest {
        val urlText = url(sid = "lost", hash = "SECRET")
        store(FakeSecretCipher("key-A")).addDevice(urlText, name = "Desk")

        // 换一把密钥 = 卸载重装后 Keystore 里的密钥没了
        val reader = store(FakeSecretCipher("key-B"))
        val record = reader.devices.first().single()

        assertNull("不能返回空串，必须是 null", record.remoteUrl)
        assertTrue(record.needsRepair)
        assertEquals("非凭据字段照常可用", "Desk", record.name)
        assertEquals("zcode.z.ai", record.host)
        assertEquals("lost", record.sid)
        assertEquals(1_000L, record.addedAt)
    }

    @Test
    fun keyLoss_keepsWorkingForOtherOperations_andPreservesCiphertext() = runTest {
        val urlText = url(sid = "lost", hash = "SECRET")
        store(FakeSecretCipher("key-A")).addDevice(urlText, name = "Desk")

        val reader = store(FakeSecretCipher("key-B"))
        val record = reader.devices.first().single()

        // 降级期间改名、改状态、选中都还能用
        assertTrue(reader.renameDevice(record.id, "Desk 2"))
        assertTrue(reader.setRelayStatus(record.id, RelayStatus.ERROR))
        reader.setCurrentDevice(record.id)

        val after = reader.devices.first().single()
        assertEquals("Desk 2", after.name)
        assertEquals(RelayStatus.ERROR, after.lastRelayStatus)
        assertTrue(after.needsRepair)
        // 解不开的密文被原样留着，没有被改名顺手抹掉
        assertTrue(diskText().contains("fake:key-A:"))

        // 重新配对 ⇒ 新密钥加密，设备恢复可用，id 不变
        val repaired = reader.addDevice(urlText, name = "Desk 3")
        assertEquals(record.id, repaired.id)
        assertFalse(repaired.needsRepair)
        assertEquals(urlText, repaired.remoteUrl)
        assertEquals(1, reader.devices.first().size)
    }

    @Test
    fun keyLoss_thenAddNewDevice_stillWorks() = runTest {
        store(FakeSecretCipher("key-A")).addDevice(url(sid = "old"), name = "Old")
        val reader = store(FakeSecretCipher("key-B"))
        val fresh = reader.addDevice(url(sid = "new"), name = "New")

        val list = reader.devices.first()
        assertEquals(2, list.size)
        assertTrue(list.first { it.id == fresh.id }.remoteUrl!!.contains("sid=new"))
        assertTrue(list.first { it.sid == "old" }.needsRepair)
    }

    // ------------------------------------------------------------------
    // 设置
    // ------------------------------------------------------------------

    @Test
    fun settings_defaultsWhenNeverWritten() = runTest {
        assertEquals(AppSettings(), store().settings.first())
    }

    @Test
    fun settings_roundTripThroughUpdateAndSet() = runTest {
        val store = store()
        store.updateSettings { it.copy(locale = "en-US", theme = AppTheme.DARK, textScale = 1.2f) }
        val s = store.settings.first()
        assertEquals("en-US", s.locale)
        assertEquals(AppTheme.DARK, s.theme)
        assertEquals(1.2f, s.textScale, 0.0001f)

        store.setSettings(s.copy(verboseProtocolFrames = true))
        assertTrue(store.settings.first().verboseProtocolFrames)
        assertEquals("en-US", store.settings.first().locale)

        // 换个 DeviceStore 实例读同一份 DataStore，值还在
        assertEquals(store.settings.first(), store().settings.first())
    }

    @Test
    fun settings_areNormalizedBeforePersisting() = runTest {
        val store = store()
        store.setSettings(AppSettings(locale = "   ", textScale = 99f, codeFontSize = 0.5f))
        val s = store.settings.first()
        assertEquals(AppSettings.DEFAULT_LOCALE, s.locale)
        assertEquals(AppSettings.TEXT_SCALE_MAX, s.textScale, 0.0001f)
        assertEquals(AppSettings.CODE_FONT_SIZE_MIN, s.codeFontSize, 0.0001f)
    }

    @Test
    fun settings_doNotLeakIntoDevices() = runTest {
        val store = store()
        store.setSettings(AppSettings(locale = "en-US"))
        store.addDevice(url(sid = "a"))
        assertEquals("en-US", store.settings.first().locale)
        assertEquals(1, store.devices.first().size)
    }

    // ------------------------------------------------------------------
    // 真的重启一次（新 DataStore 实例读同一份文件）
    // ------------------------------------------------------------------

    @Test
    fun dataSurvivesReopen() = runTest {
        val urlText = url(sid = "persisted", hash = "H")
        val first = store()
        first.addDevice(urlText, name = "Desk")
        first.setCurrentDevice("id-1")
        first.updateSettings { it.copy(locale = "en-US") }

        // 关掉旧 DataStore（等价于进程退出），用同一份文件重开
        val file = preferencesFile
        scope.cancel()
        scope.coroutineContext.job.join()
        openDataStore(file)

        val reopened = store()
        val record = reopened.devices.first().single()
        assertEquals("Desk", record.name)
        assertEquals(urlText, record.remoteUrl)
        assertEquals("id-1", reopened.currentDeviceId.first())
        assertEquals("en-US", reopened.settings.first().locale)
    }

    private suspend fun assertRejected(store: DeviceStore, url: String) =
        assertRejected { store.addDevice(url) }

    private suspend fun assertRejected(block: suspend () -> Unit) {
        try {
            block()
            fail("应当抛 IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // 预期：拒绝无法解析的 URL / 未知 id
        }
    }
}
