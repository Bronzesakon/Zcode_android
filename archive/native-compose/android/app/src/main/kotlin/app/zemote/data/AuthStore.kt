package app.zemote.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.zemote.auth.JwtExpiry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 生产环境那份 DataStore；名字固定，改了等于丢登录态。 */
private val Context.zemoteAuthDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "zemote_auth_store")

/**
 * 已登录账号（**不含 JWT**）。
 *
 * JWT 只存在于 [AuthStore] 的磁盘密文里，通过 [AuthStore.token] 按需取出；
 * 界面层只需要展示名/头像/平台。
 */
data class AuthAccount(
    val provider: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val expiresAt: Long? = null,
) {
    /** 过期时间已知且已到。未知过期时间不视为过期（以服务端 401 为准）。 */
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
        expiresAt != null && now >= expiresAt
}

/**
 * 登录态的读写面。
 *
 * 生产实现是 [DataStoreAuthStore]；JVM 单测用内存假实现，避免碰 DataStore / Keystore。
 */
interface AuthStore {
    /** 当前账号（不含 JWT）；未登录或凭据损坏时为 `null`。 */
    val account: Flow<AuthAccount?>

    /** 取出可用的 JWT；未登录、已过期或密文损坏时为 `null`。 */
    suspend fun token(): String?

    /** 保存一次成功登录。 */
    suspend fun save(
        provider: String,
        jwt: String,
        displayName: String,
        avatarUrl: String? = null,
        expiresAt: Long? = null,
    )

    /** 退出登录。 */
    suspend fun clear()
}

/**
 * 3.16.1 新增的 OAuth 登录态（Z.ai / BigModel）。
 *
 * ## 为什么单独一个 DataStore
 *
 * 设备凭据（[DeviceStore]）与账号凭据是两条独立的生命周期：退出登录不应该删掉已配对设备，
 * 换账号也不应该改写设备记录。分成两个 preferences 文件，互不干扰。
 *
 * ## 内存明文 / 落盘密文
 *
 * JWT 在内存里以明文交给引擎（relay WS 的 `?token=`），但**绝不明文落盘**：写盘前用
 * [SecretCipher] 加密成 `enc:v1:…`。读不出来（Keystore 密钥丢失）时降级为「未登录」，
 * 不抛异常、不覆盖原密文——用户重新登录即可覆盖。
 */
class DataStoreAuthStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
    private val clock: () -> Long = System::currentTimeMillis,
) : AuthStore {

    /** 当前账号（不含 JWT）；未登录或凭据损坏时为 `null`。 */
    override val account: Flow<AuthAccount?> = dataStore.data
        .map { prefs -> readStored(prefs)?.toAccount() }
        .flowOn(Dispatchers.IO)

    /**
     * 取出可用的 JWT；未登录、已过期或密文损坏时为 `null`。
     *
     * 过期只看 JWT 自己的 `exp`（3.16.1 网页 `src@31023`，留 30s 余量）。登录响应里的
     * `expires_in` 只落盘做诊断，**不**参与拦截——否则服务端续期/时钟偏差会让本地提前登出。
     */
    override suspend fun token(): String? = withContext(Dispatchers.IO) {
        val stored = readStored(dataStore.data.first()) ?: return@withContext null
        val jwt = try {
            cipher.decrypt(stored.encryptedJwt)
        } catch (_: SecretCipherException) {
            return@withContext null
        }
        if (JwtExpiry.isExpired(jwt, clock())) return@withContext null
        jwt
    }

    /** 保存一次成功登录。JWT 加密失败时整体失败，**不落明文**。 */
    override suspend fun save(
        provider: String,
        jwt: String,
        displayName: String,
        avatarUrl: String?,
        expiresAt: Long?,
    ) {
        withContext(Dispatchers.IO) {
            val encrypted = cipher.encrypt(jwt)
            val stored = StoredAuth(
                provider = provider,
                encryptedJwt = encrypted,
                displayName = displayName,
                avatarUrl = avatarUrl,
                expiresAt = expiresAt,
            )
            dataStore.edit { prefs ->
                prefs[KEY_AUTH] = json.encodeToString(StoredAuth.serializer(), stored)
                prefs[KEY_SCHEMA_VERSION] = SCHEMA_VERSION
            }
        }
    }

    /** 退出登录：清掉账号与 JWT。设备列表不受影响。 */
    override suspend fun clear() {
        dataStore.edit { prefs -> prefs.remove(KEY_AUTH) }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private fun readStored(prefs: Preferences): StoredAuth? {
        val raw = prefs[KEY_AUTH] ?: return null
        return try {
            json.decodeFromString(StoredAuth.serializer(), raw)
        } catch (_: SerializationException) {
            null
        }
    }

    private fun StoredAuth.toAccount(): AuthAccount? {
        // 密文解不开 / JWT 已过期时仍然展示账号名没有意义（用户需要重新登录），直接当未登录。
        val jwt = try {
            cipher.decrypt(encryptedJwt)
        } catch (_: SecretCipherException) {
            return null
        }
        if (JwtExpiry.isExpired(jwt, clock())) return null
        return AuthAccount(
            provider = provider,
            displayName = displayName,
            avatarUrl = avatarUrl,
            // 与 token() 的判定保持同一来源：JWT exp - 30s 余量。
            expiresAt = JwtExpiry.expiresAt(jwt)?.let { it - JwtExpiry.MARGIN_MS },
        )
    }

    companion object {
        const val SCHEMA_VERSION = 1

        private val KEY_AUTH = stringPreferencesKey("zemote_auth_v1")
        private val KEY_SCHEMA_VERSION = intPreferencesKey("zemote_auth_schema_version")

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** 生产接线：应用私有 DataStore + Keystore 加密。 */
        fun create(
            context: Context,
            cipher: SecretCipher = KeystoreSecretCipher(),
        ): AuthStore = DataStoreAuthStore(
            dataStore = context.applicationContext.zemoteAuthDataStore,
            cipher = cipher,
        )
    }
}

/** 磁盘形态：JWT 是密文，字段名显式区分于内存模型。 */
@Serializable
private data class StoredAuth(
    val provider: String,
    val encryptedJwt: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val expiresAt: Long? = null,
)
