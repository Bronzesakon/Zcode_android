package app.zemote.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [DataStoreAuthStore] 的 JVM 单测：真 DataStore + 假 cipher，不碰 Keystore。 */
class AuthStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var file: File
    private var now = 1_000L
    private var fileSeq = 0

    @Before
    fun setUp() {
        AndroidApiLevel.forceApi26()
        open()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun open() {
        file = File(tempFolder.root, "auth-${++fileSeq}.preferences_pb")
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
    }

    private fun store(cipher: SecretCipher = FakeSecretCipher()): DataStoreAuthStore =
        DataStoreAuthStore(dataStore = dataStore, cipher = cipher, clock = { now })

    @Test
    fun `save exposes account and token`() = runTest {
        val store = store()
        store.save(
            provider = "zai",
            jwt = "JWT-SECRET",
            displayName = "Alice",
            avatarUrl = "https://example.com/a.png",
            expiresAt = 5_000L,
        )

        assertEquals("JWT-SECRET", store.token())
        val account = store.account.first()!!
        assertEquals("zai", account.provider)
        assertEquals("Alice", account.displayName)
        assertEquals("https://example.com/a.png", account.avatarUrl)
    }

    @Test
    fun `jwt is never stored in plaintext`() = runTest {
        store().save(provider = "zai", jwt = "JWT-SECRET", displayName = "Alice")
        // DataStore 是 protobuf；明文 JWT 若落盘会直接出现在字节里。
        val raw = file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains("JWT-SECRET"))
    }

    /** `header.payload.signature`；payload 里只有 `exp`（秒）。 */
    private fun jwt(expSeconds: Long?): String {
        fun b64(text: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
        val payload = if (expSeconds == null) "{}" else """{"exp":$expSeconds}"""
        return "${b64("""{"alg":"none"}""")}.${b64(payload)}.sig"
    }

    @Test
    fun `jwt exp with 30s margin decides expiry`() = runTest {
        val store = store()
        // exp = 100s；余量 30s ⇒ 本地在 70s 起视为过期。
        store.save(provider = "zai", jwt = jwt(100), displayName = "Alice")

        now = 69_999L
        assertEquals(jwt(100), store.token())
        assertEquals("Alice", store.account.first()?.displayName)

        now = 70_000L
        assertNull(store.token())
        assertNull(store.account.first())
    }

    @Test
    fun `expires_in is diagnostic only and never gates`() = runTest {
        val store = store()
        // 没有 exp 的 JWT：即使保存了已到期的 expiresAt，也交给服务端 401 判定。
        store.save(provider = "zai", jwt = "JWT", displayName = "Alice", expiresAt = 2_000L)
        now = 2_000L
        assertEquals("JWT", store.token())
        // 账号的 expiresAt 也只来自 JWT exp（这里没有），不来自 expires_in。
        assertNull(store.account.first()?.expiresAt)
    }

    @Test
    fun `clear removes account`() = runTest {
        val store = store()
        store.save(provider = "bigmodel", jwt = "JWT", displayName = "Bob")
        store.clear()
        assertNull(store.token())
        assertNull(store.account.first())
    }

    @Test
    fun `undecryptable ciphertext degrades to logged out`() = runTest {
        val store = store()
        store.save(provider = "zai", jwt = "JWT", displayName = "Alice")
        // 换一把解不开旧密文的 cipher（模拟 Keystore 密钥丢失）。
        val broken = object : SecretCipher {
            override fun encrypt(plain: String): String = "enc:v1:broken"
            override fun decrypt(stored: String): String = throw SecretCipherException("lost")
        }
        val reopened = DataStoreAuthStore(dataStore = dataStore, cipher = broken, clock = { now })
        assertNull(reopened.token())
        assertNull(reopened.account.first())
    }
}
