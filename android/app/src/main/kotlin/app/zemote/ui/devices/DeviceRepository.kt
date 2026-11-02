package app.zemote.ui.devices

import android.util.Log
import app.zemote.data.AppSettings
import app.zemote.data.DeviceRecord
import app.zemote.data.DeviceStore
import app.zemote.data.RelayStatus
import app.zemote.ui.EnginePort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 表现层看到的「设备列表 + 应用设置」持久化面。
 *
 * ## 为什么这里要一层接口，而不是直接注入 [DeviceStore]
 *
 * [DeviceStore] 是**具体类**（不是接口），它自己需要一个 `DataStore<Preferences>` 和一个
 * `SecretCipher` 才能构造。直接把它塞进 ViewModel 有两个实际代价：
 *
 * 1. **单测会被 DataStore 的文件语义绑住**：在 JVM 上跑真 DataStore 要建临时目录，
 *    而 DataStore 的原子改名在 Windows 上会抛 `IOException`（见 `FileStorage.kt`，
 *    兄弟层的 `DeviceStoreTest` 正因此在 Windows 上红着）。ViewModel 的单测目的是
 *    **验状态映射**（加载 / 成功 / 失败三态），不该顺带验一遍磁盘语义。
 * 2. **失败路径造不出来**：读盘失败、写盘失败、Keystore 密钥丢失这些分支，
 *    用真实现很难在单测里稳定触发，用假实现是一行。
 *
 * 生产侧只有一个实现 [StoreDeviceRepository]，它**不创建** [DeviceStore]——实例由
 * `AppContainer` 唯一装配（`DeviceStore` 要求同一路径只有一个实例，见它的 KDoc）。
 */
interface DeviceRepository {

    /** 设备列表，按 `sortKey` 升序。列表一变就发射。 */
    val devices: Flow<List<DeviceRecord>>

    /** 当前选中设备；没有选中、或 id 指向的设备已删除时为 `null`。 */
    val currentDevice: Flow<DeviceRecord?>

    /** 应用设置；没写过或数据损坏时是默认值。 */
    val settings: Flow<AppSettings>

    /**
     * 添加设备（扫码 / 粘贴）。
     *
     * 同 host + 同 sid 视为同一台设备：不新增记录，而是就地更新凭据与显示名。
     *
     * @throws IllegalArgumentException URL 解析不出 `sid`。
     * @throws app.zemote.data.SecretCipherException 加密失败（Keystore 不可用，不落明文）。
     */
    suspend fun addDevice(url: String, name: String? = null): DeviceRecord

    /** 删除设备；删的正是当前设备时顺带清掉当前选中。返回是否真的删了。 */
    suspend fun removeDevice(id: String): Boolean

    /** 选中当前设备；`null` = 取消选中。id 不存在时抛 [IllegalArgumentException]。 */
    suspend fun setCurrentDevice(id: String?)

    /** 改显示名。空白名字忽略，返回是否真的改了。 */
    suspend fun renameDevice(id: String, name: String): Boolean

    /** 读-改-写设置（整个变换在同一个事务里，并发更新不互相覆盖）。 */
    suspend fun updateSettings(transform: (AppSettings) -> AppSettings)

    /**
     * 回写「最后已知的连接状态」：`lastRelayStatus`，以及（可选的）`lastConnectedAt`。
     *
     * 设备列表里那些设备并没有活着的会话，所以行上显示的就是这份持久化值——由
     * [RelayStatusWriter] 在引擎状态**真的变了**时调用。
     *
     * `connectedAt` 为 `null` 时**不碰** `lastConnectedAt`（只有进入 `paired` 才更新它，
     * 否则每次重连都会把「最后一次连接时间」刷成现在）。
     *
     * @return 是否真的写进去了（设备不存在 / 值没变时返回 `false`，不写盘）。
     */
    suspend fun recordRelayStatus(
        id: String,
        status: RelayStatus,
        connectedAt: Long? = null,
    ): Boolean

    /**
     * 回写最后一次 relay 失败的原因与详情（诊断用）。传 `null` 清空。
     *
     * 与 [recordRelayStatus] 分开：失败事件与状态迁移不是一一对应的（例如 `waiting`
     * 超时既会切到 `error`，也会带一条 `invalid-mobile-connection`），分开写不会互相覆盖。
     */
    suspend fun recordRelayFailure(id: String, reason: String?, message: String?): Boolean
}

/** 生产实现：逐字转发到 [DeviceStore]。 */
class StoreDeviceRepository(private val store: DeviceStore) : DeviceRepository {

    override val devices: Flow<List<DeviceRecord>> = store.devices

    override val currentDevice: Flow<DeviceRecord?> = store.currentDevice

    override val settings: Flow<AppSettings> = store.settings

    override suspend fun addDevice(url: String, name: String?): DeviceRecord =
        store.addDevice(url = url, name = name)

    override suspend fun removeDevice(id: String): Boolean = store.removeDevice(id)

    override suspend fun setCurrentDevice(id: String?) = store.setCurrentDevice(id)

    override suspend fun renameDevice(id: String, name: String): Boolean =
        store.renameDevice(id = id, name = name)

    override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) =
        store.updateSettings(transform)

    override suspend fun recordRelayStatus(
        id: String,
        status: RelayStatus,
        connectedAt: Long?,
    ): Boolean {
        // 两次 `mutateDevice`（DeviceStore 没有「一次改两个字段」的公开入口，data/ 不归本层改）。
        // 只有进入 paired 那一次会走到第二个调用。
        val statusChanged = store.setRelayStatus(id, status)
        val connectedChanged =
            if (connectedAt != null) store.markConnected(id, connectedAt) else false
        return statusChanged || connectedChanged
    }

    override suspend fun recordRelayFailure(
        id: String,
        reason: String?,
        message: String?,
    ): Boolean = store.setRelayFailure(id = id, reason = reason, message = message)
}

/**
 * 把引擎的 relay 状态回写进**当前设备**的记录。
 *
 * ## 为什么需要它
 *
 * 设备列表显示的是 `DeviceRecord.lastRelayStatus`——一份持久化的「最后已知状态」。没有本类
 * 的话它永远停在配对那一刻的值，用户看到的是历史值（重连/掉线都不反映）。
 *
 * ## 三条纪律
 *
 * 1. **只在状态真的变了时写**：relay 状态会频繁抖（`connecting` ↔ `reconnecting` ↔ …），
 *    每次变化都写盘等于把 DataStore 当热路径用。这里记住「上次写进去的 (设备, 状态)」，
 *    一样就跳过。
 * 2. **`lastConnectedAt` 只在进入 `paired` 时更新**：每次重连都刷时间戳会让「最后一次连接」
 *    失去意义。
 * 3. **写盘失败吞掉并记日志**：设备记录只是展示用的历史值，写盘失败不该让连接状态更新失败。
 *    但 `CancellationException` 照旧上抛（那是取消，不是失败）。
 *
 * 挂载点由调用方决定（生产是 `AppContainer` 的进程级作用域，不是界面作用域——离开设备页之后
 * relay 照样会变）。本类**不创建** [DeviceStore]，只走注入的 [DeviceRepository]
 * （`DeviceStore` 要求同一路径只有一个实例）。
 */
class RelayStatusWriter(
    private val repository: DeviceRepository,
    private val engine: EnginePort,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) {

    /**
     * 起一个收集任务（挂在 [scope] 下；取消它就停）。返回 [Job] 便于测试等待/取消。
     */
    fun start(scope: CoroutineScope): Job = scope.launch { watch() }

    /** 收集循环本体（抽出来是为了单测能直接跑，不必自己建 scope）。 */
    suspend fun watch() {
        // 已写进存储的 (设备 id, 状态)；null = 还没写过。
        var written: Pair<String, RelayStatus>? = null
        // 已写进存储的 (设备 id, 原因码, 详情)；null = 还没写过失败。
        var writtenFailure: Triple<String, String?, String?>? = null
        combine(
            repository.currentDevice,
            engine.relayState,
            engine.relayFailure,
        ) { device, relay, failure -> Triple(device, relay, failure) }
            .collect { (device, relay, failure) ->
                if (device == null) {
                    written = null
                    writtenFailure = null
                    return@collect
                }
                val status = RelayStatus.fromWire(relay.wire)
                // 首次以**持久化值**为基准：盘上已经是这个状态就不写（否则每次启动都白写一次）。
                val previous = written ?: (device.id to device.lastRelayStatus)
                val statusChanged = previous.first != device.id || previous.second != status
                if (statusChanged) {
                    val enteringPaired = status == RelayStatus.PAIRED &&
                        !(previous.first == device.id && previous.second == RelayStatus.PAIRED)
                    // 先记下「打算写什么」：写盘可能失败，但失败重试不该被同一条状态反复触发
                    // （下一次真的变化时再试）。这也让「只在变化时写」与写盘结果解耦。
                    written = device.id to status
                    try {
                        repository.recordRelayStatus(
                            id = device.id,
                            status = status,
                            connectedAt = if (enteringPaired) now() else null,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log("回写 relay 状态失败（device=${device.id}, status=$status）：$e")
                    }
                }

                // 失败原因与状态分开写：`waiting` 超时会同时产生 `error` 状态和一条
                // `invalid-mobile-connection` 失败，后者才是用户要看的诊断信息。
                if (status == RelayStatus.PAIRED) {
                    if (writtenFailure != null) {
                        try {
                            repository.recordRelayFailure(device.id, null, null)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log("清空 relay 失败原因失败（device=${device.id}）：$e")
                        }
                        writtenFailure = null
                    }
                } else if (failure != null) {
                    val key = Triple(device.id, failure.reason, failure.message)
                    if (key != writtenFailure) {
                        writtenFailure = key
                        try {
                            repository.recordRelayFailure(
                                id = device.id,
                                reason = failure.reason,
                                message = failure.message,
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log(
                                "回写 relay 失败原因失败（device=${device.id}, " +
                                    "reason=${failure.reason}）：$e",
                            )
                        }
                    }
                }
            }
    }

    private companion object {
        const val TAG = "RelayStatusWriter"
    }
}
