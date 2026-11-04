package app.zemote.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 生产环境那份 DataStore；名字固定，改了等于丢用户数据。 */
private val Context.zemoteDeviceDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "zemote_device_store")

/**
 * 设备列表、当前设备与应用设置的持久化。
 *
 * ## 为什么不是单例
 *
 * [dataStore] 与 [cipher] 都从构造函数注入：JVM 单测能拿临时目录建一份真的 DataStore
 * （`PreferenceDataStoreFactory.create`）配一个假 cipher 跑，不需要 Robolectric、不碰 Keystore。
 * 生产接线用 [DeviceStore.create]。
 *
 * ## 内存明文 / 落盘密文
 *
 * 对外暴露的 [DeviceRecord.remoteUrl] 永远是**明文**（legacy 链接里的 `hash` 要做 HMAC 证明；
 * 3.16.1 新链接的 JWT 不在这里，见 [AuthStore]）。
 * 写盘时由 [cipher] 加密成 `enc:v1:<base64(iv||ciphertext)>`，磁盘上只有密文；
 * 读盘时解密回明文。加解密只发生在存储边界这一处，别的地方不要自己动。
 *
 * ## 去重
 *
 * 同 `host` + 同 `sid` 视为同一台设备（legacy 的 `hash`/`t` 每次配对都会变，不能拿整条 URL 比）。
 * 重复添加**不新增记录**，而是就地更新已有记录：换上新凭据（URL 里的 `t` 也跟着新）、
 * 更新显示名（只有显式传了非空 [DeviceRecord.name] 才改）、把最后已知 relay 状态复位成
 * [RelayStatus.IDLE]（旧连接已作废）。**保留**：`id`、`addedAt`（添加时间指首次添加）、
 * `lastConnectedAt`、`sortKey`（列表顺序不会因为重新配对而跳动）。
 *
 * ## 密钥丢失的降级路径
 *
 * Android Keystore 的密钥在卸载应用 / 清除数据后会丢，旧密文永久解不开。此时：
 *  - **不抛异常**：读路径捕获 [SecretCipherException]，把该条记录降级成
 *    `remoteUrl == null` / `needsRepair == true`，其余字段（名字、host、时间）照常可用；
 *  - **不返回空串**：`null` 与「空 URL」是两回事，界面据此提示「该设备需要重新配对」；
 *  - **不毁数据**：降级只发生在读投影上，写盘时**原样保留**那条解不开的密文（改名、改状态
 *    都不会把它抹掉），所以密钥丢失期间不会连带丢数据；用户重新配对时会用新密钥覆盖它。
 *
 * ## 事务与并发
 *
 * 所有写操作都在**单个** `DataStore.edit` 里完成「读-改-写」——DataStore 的 `edit` 本身串行，
 * 但只要读和写跨了两次 `edit` 就有丢更新的窗口，所以本类里没有「先 `data` 再 `edit`」的写法。
 *
 * ## schema 版本
 *
 * 新项目，没有历史数据要迁移。但磁盘上留了 `schemaVersion`（preferences 里的
 * [KEY_SCHEMA_VERSION] 与 JSON 信封里的 `schemaVersion` 各一份），并开了 `ignoreUnknownKeys`
 * 做前向兼容读：将来加字段/加版本时，旧版本读新数据不会炸，也留好了迁移的分派点。
 *
 * ## DataStore 实例与文件路径（必须「一个文件一个实例」）
 *
 * DataStore 强制**同一个文件只能有一个活着的 `DataStore` 实例**：第二个实例一建，
 * 第一个的写盘就会炸（`IllegalStateException: There are multiple DataStores active for the
 * same file`，或者写盘时报 `Unable to rename …preferences_pb.tmp` —— 两个实例抢同一个
 * `.tmp` / 目标文件）。所以：
 *
 *  - **生产代码只用 [DeviceStore.create] 建**：它走 `Context.preferencesDataStore` 委托，
 *    该委托本身就是「按 Context 缓存的单例」，一个进程内只会有一个实例；建议由 `Application`
 *    持有这一份并往下传（ViewModel 里不要再 `create` 一遍）。
 *  - **不要**在两个地方各自 `PreferenceDataStoreFactory.create { 同一个文件 }`。
 *  - 构造函数注入 [dataStore] 的用法（DI / 单测）意味着**调用方负责**这个约束：同一条路径
 *    只能喂给一个 `DeviceStore`。测试里每个用例要用独立的临时目录/文件名。
 *
 * @param dataStore 注入的 DataStore（生产是应用私有 preferences DataStore，测试是临时目录）。
 *   同一条文件路径只能有一个存活的实例，见类文档。
 * @param cipher 凭据加解密实现（生产是 [KeystoreSecretCipher]，测试是假实现）。
 * @param clock 取当前时间（epoch millis），测试可注入固定值。
 * @param idFactory 生成本地设备 id，测试可注入确定性序列。
 */
class DeviceStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { DeviceRecord.newId() },
) {

    /** 设备列表，按 [DeviceRecord.sortKey] 升序。列表一变就发射。 */
    val devices: Flow<List<DeviceRecord>> = dataStore.data
        .map { prefs -> readDevices(prefs) }
        // 解密要走 Keystore IPC，别在 UI 线程上做（map 默认跑在收集方上下文里）。
        .flowOn(Dispatchers.IO)

    /** 当前选中设备的 id；没有选中为 `null`。 */
    val currentDeviceId: Flow<String?> = dataStore.data
        .map { prefs -> prefs[KEY_CURRENT_DEVICE_ID] }
        .flowOn(Dispatchers.IO)

    /** 当前选中设备；id 指向的设备已被删除时为 `null`。 */
    val currentDevice: Flow<DeviceRecord?> = dataStore.data
        .map { prefs ->
            val id = prefs[KEY_CURRENT_DEVICE_ID] ?: return@map null
            readDevices(prefs).firstOrNull { it.id == id }
        }
        .flowOn(Dispatchers.IO)

    /** 应用设置；没写过或数据损坏时返回默认值。 */
    val settings: Flow<AppSettings> = dataStore.data
        .map { prefs -> readSettings(prefs) }
        .flowOn(Dispatchers.IO)

    /** 按 id 取单台设备（`null` 表示不存在）。 */
    suspend fun device(id: String): DeviceRecord? =
        devices.first().firstOrNull { it.id == id }

    /**
     * 添加一台设备（扫码 / 粘贴链接）。
     *
     * 若已存在同 `host` + 同 `sid` 的记录，则按类文档里的规则**更新**它而不是新增。
     * 返回入库后的记录（`remoteUrl` 为明文）。
     *
     * @param url 远控 URL，必须能解析出 host 与 sid。
     * @param name 显式显示名；为空则用 URL 的 `name` 参数，再兜底到 host。
     * @throws IllegalArgumentException URL 解析不出 `sid`（存下来也连不通，宁可当场报错）。
     * @throws SecretCipherException 加密失败（Keystore 不可用）——此时**不写盘**，不落明文。
     */
    suspend fun addDevice(url: String, name: String? = null): DeviceRecord {
        val plainUrl = url.trim()
        val parts = RemoteUrlParser.parse(plainUrl)
            ?: throw IllegalArgumentException(
                "远控 URL 无法解析（需要 https://<host>/remote/…?sid=…）：$url"
            )
        // 先加密再进事务：加密失败就整体失败，绝不留下「已写一半」的列表。
        val encryptedUrl = cipher.encrypt(plainUrl)
        val explicitName = name?.trim()?.takeIf { it.isNotEmpty() }
        val now = clock()
        var saved: StoredDevice? = null

        dataStore.edit { prefs ->
            val list = readStoredDevices(prefs, strict = true).toMutableList()
            val index = list.indexOfFirst { it.host == parts.host && it.sid == parts.sid }
            val record = if (index >= 0) {
                val existing = list[index]
                existing.copy(
                    name = explicitName ?: existing.name,
                    encryptedUrl = encryptedUrl,
                    lastRelayStatus = RelayStatus.IDLE,
                ).also { list[index] = it }
            } else {
                StoredDevice(
                    id = idFactory(),
                    name = explicitName ?: parts.deviceName ?: parts.host,
                    encryptedUrl = encryptedUrl,
                    host = parts.host,
                    sid = parts.sid,
                    addedAt = now,
                    lastConnectedAt = null,
                    lastRelayStatus = RelayStatus.IDLE,
                    sortKey = (list.maxOfOrNull { it.sortKey } ?: 0L) + 1L,
                ).also { list += it }
            }
            saved = record
            writeDevices(prefs, list)
        }

        // 明文就在手上，不需要再解密一遍。
        return checkNotNull(saved).toRecord(plainUrl)
    }

    /** 删除设备；若删的正是当前设备，顺带清掉当前选中（同一个事务里）。 */
    suspend fun removeDevice(id: String): Boolean {
        var removed = false
        dataStore.edit { prefs ->
            val list = readStoredDevices(prefs, strict = true).toMutableList()
            removed = list.removeAll { it.id == id }
            if (removed) {
                writeDevices(prefs, list)
                if (prefs[KEY_CURRENT_DEVICE_ID] == id) prefs.remove(KEY_CURRENT_DEVICE_ID)
            }
        }
        return removed
    }

    /** 改显示名。空白名字忽略，返回是否真的改了。 */
    suspend fun renameDevice(id: String, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        return mutateDevice(id) { it.copy(name = trimmed) }
    }

    /** 选中当前设备；传 `null` 表示取消选中。id 不存在时抛 [IllegalArgumentException]。 */
    suspend fun setCurrentDevice(id: String?) {
        dataStore.edit { prefs ->
            if (id == null) {
                prefs.remove(KEY_CURRENT_DEVICE_ID)
                return@edit
            }
            require(readStoredDevices(prefs, strict = true).any { it.id == id }) {
                "未知设备 id：$id"
            }
            prefs[KEY_CURRENT_DEVICE_ID] = id
        }
    }

    /** 记录一次成功连接（更新 [DeviceRecord.lastConnectedAt]）。 */
    suspend fun markConnected(id: String, at: Long = clock()): Boolean =
        mutateDevice(id) { it.copy(lastConnectedAt = at) }

    /**
     * 记录最后一次已知的 relay 状态。
     *
     * 进入 `paired` 时顺带清掉上一次的失败原因——那已经是历史，继续显示会让用户
     * 在连接正常时仍看到一条红色错误。
     */
    suspend fun setRelayStatus(id: String, status: RelayStatus): Boolean =
        mutateDevice(id) {
            it.copy(
                lastRelayStatus = status,
                lastRelayError = if (status == RelayStatus.PAIRED) null else it.lastRelayError,
                lastRelayErrorMessage =
                    if (status == RelayStatus.PAIRED) null else it.lastRelayErrorMessage,
            )
        }

    /** 记录最后一次 relay 失败的原因与详情；传 `null` 表示清空。 */
    suspend fun setRelayFailure(id: String, reason: String?, message: String?): Boolean =
        mutateDevice(id) {
            it.copy(lastRelayError = reason, lastRelayErrorMessage = message)
        }

    /** 直接设置排序键（拖拽排序用）。 */
    suspend fun setSortKey(id: String, sortKey: Long): Boolean =
        mutateDevice(id) { it.copy(sortKey = sortKey) }

    /** 覆盖全部设置（写盘前归一化）。 */
    suspend fun setSettings(settings: AppSettings) {
        val normalized = settings.normalized()
        dataStore.edit { prefs -> prefs[KEY_SETTINGS] = encodeSettings(normalized) }
    }

    /** 读-改-写设置，整个变换在同一个事务里完成（并发更新不会互相覆盖）。 */
    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            val next = transform(readSettings(prefs)).normalized()
            prefs[KEY_SETTINGS] = encodeSettings(next)
        }
    }

    // ------------------------------------------------------------------
    // 内部：读
    // ------------------------------------------------------------------

    private fun readDevices(prefs: Preferences): List<DeviceRecord> =
        readStoredDevices(prefs, strict = false)
            .map { it.toRecord() }
            .sortedBy { it.sortKey }

    private fun readSettings(prefs: Preferences): AppSettings {
        val raw = prefs[KEY_SETTINGS] ?: return AppSettings()
        return try {
            json.decodeFromString(AppSettings.serializer(), raw)
        } catch (_: SerializationException) {
            // 设置是非关键数据，损坏就回到默认值，不拦着应用启动。
            AppSettings()
        }
    }

    /**
     * @param strict 写路径传 `true`：数据读不出来时抛错**拒绝覆盖**（否则一次改名就会把
     *   整份设备列表抹成一条）。读路径传 `false`：损坏就当空列表，界面不炸。
     */
    private fun readStoredDevices(prefs: Preferences, strict: Boolean): List<StoredDevice> {
        val raw = prefs[KEY_DEVICES] ?: return emptyList()
        return try {
            json.decodeFromString(StoredDeviceList.serializer(), raw).devices
        } catch (e: SerializationException) {
            if (strict) throw IllegalStateException("设备列表数据损坏，拒绝覆盖以免丢数据", e)
            emptyList()
        }
    }

    /**
     * 解密单条记录的 URL。解不开（密钥丢失 / 密文损坏）时返回 `null`——这是降级路径的
     * 唯一出口，`null` 会被 [DeviceRecord.needsRepair] 显式暴露给界面。
     */
    private fun StoredDevice.decryptUrl(): String? = encryptedUrl?.let {
        try {
            cipher.decrypt(it)
        } catch (_: SecretCipherException) {
            null
        }
    }

    private fun StoredDevice.toRecord(plainUrl: String?): DeviceRecord = DeviceRecord(
        id = id,
        name = name,
        remoteUrl = plainUrl,
        host = host,
        sid = sid,
        addedAt = addedAt,
        lastConnectedAt = lastConnectedAt,
        lastRelayStatus = lastRelayStatus,
        lastRelayError = lastRelayError,
        lastRelayErrorMessage = lastRelayErrorMessage,
        sortKey = sortKey,
    )

    private fun StoredDevice.toRecord(): DeviceRecord = toRecord(decryptUrl())

    // ------------------------------------------------------------------
    // 内部：写
    // ------------------------------------------------------------------

    /** 单条记录的字段更新：读-改-写在同一个事务里。找不到该 id 返回 `false`。 */
    private suspend fun mutateDevice(id: String, transform: (StoredDevice) -> StoredDevice): Boolean {
        var changed = false
        dataStore.edit { prefs ->
            val list = readStoredDevices(prefs, strict = true).toMutableList()
            val index = list.indexOfFirst { it.id == id }
            if (index >= 0) {
                val updated = transform(list[index])
                if (updated != list[index]) {
                    list[index] = updated
                    writeDevices(prefs, list)
                    changed = true
                }
            }
        }
        return changed
    }

    private fun writeDevices(prefs: MutablePreferences, devices: List<StoredDevice>) {
        prefs[KEY_DEVICES] = json.encodeToString(
            StoredDeviceList.serializer(),
            StoredDeviceList(schemaVersion = SCHEMA_VERSION, devices = devices),
        )
        prefs[KEY_SCHEMA_VERSION] = SCHEMA_VERSION
    }

    private fun encodeSettings(settings: AppSettings): String =
        json.encodeToString(AppSettings.serializer(), settings)

    companion object {
        /** 当前磁盘 schema 版本。加字段 / 改结构就 +1，并在读取处分派迁移。 */
        const val SCHEMA_VERSION = 1

        private val KEY_DEVICES = stringPreferencesKey("zemote_devices_v1")
        private val KEY_CURRENT_DEVICE_ID = stringPreferencesKey("zemote_current_device_id")
        private val KEY_SETTINGS = stringPreferencesKey("zemote_settings_v1")
        private val KEY_SCHEMA_VERSION = intPreferencesKey("zemote_schema_version")

        /**
         * `ignoreUnknownKeys`：将来磁盘上有新字段时旧代码也能读（前向兼容）。
         * `encodeDefaults`：默认值也写出来，磁盘上能一眼看全字段。
         */
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /**
         * 生产接线：应用私有 DataStore + Keystore 加密。
         *
         * **这是生产环境唯一的入口**：`preferencesDataStore` 委托按 Context 缓存实例，
         * 保证「一个文件一个 DataStore」（见类文档里那条硬约束）。
         *
         * 在 `Application` 里建**一份**并往下传；别在每个 ViewModel / Composable 里各调一次
         * `create`（虽然委托会复用同一个 DataStore，但白造对象、也容易让人误以为可以随便建）。
         */
        fun create(
            context: Context,
            cipher: SecretCipher = KeystoreSecretCipher(),
        ): DeviceStore = DeviceStore(
            dataStore = context.applicationContext.zemoteDeviceDataStore,
            cipher = cipher,
        )
    }
}

/**
 * 设备列表的磁盘形态。
 *
 * 与内存模型 [DeviceRecord] **刻意分开**：这里的 URL 是密文，字段名也换成 `encryptedUrl`，
 * 这样「把内存模型直接序列化落盘」这种失误在类型上就写不出来。加字段时记得同步两个类。
 */
@Serializable
private data class StoredDeviceList(
    val schemaVersion: Int = DeviceStore.SCHEMA_VERSION,
    val devices: List<StoredDevice> = emptyList(),
)

/**
 * 单台设备的磁盘形态。
 *
 * [encryptedUrl] 是 [SecretCipher] 产出的密文（`enc:v1:…`）；为 `null` 只可能来自历史/导入数据。
 * 解密失败的记录**不会**把它清空——写路径只改自己要改的字段，密文原样留着。
 */
@Serializable
private data class StoredDevice(
    val id: String,
    val name: String,
    val encryptedUrl: String? = null,
    val host: String,
    val sid: String,
    val addedAt: Long,
    val lastConnectedAt: Long? = null,
    val lastRelayStatus: RelayStatus = RelayStatus.DEFAULT,
    val lastRelayError: String? = null,
    val lastRelayErrorMessage: String? = null,
    val sortKey: Long,
)
