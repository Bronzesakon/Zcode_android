package app.zemote.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zemote.data.DeviceRecord
import app.zemote.engine.RelayState
import app.zemote.ui.EnginePort
import app.zemote.ui.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 设备列表页的状态。
 *
 * 三态用 [loading] + [error] 两个字段表达（而不是 sealed class）：设备列表在**已经显示过**
 * 之后仍然会失败（读盘错误、写盘错误），这时应该保留旧列表 + 顶部一条错误条，
 * 而不是把界面整个换成错误页。首次加载时 `devices` 为空 + `loading = true`，
 * 界面据此显示加载态。
 */
data class DevicesUiState(
    /** 首帧还在读盘（之后任何刷新都不再回到 true，避免列表闪一下）。 */
    val loading: Boolean = true,
    val devices: List<DeviceRecord> = emptyList(),
    /** 当前选中设备的 id；`null` = 没有选中。 */
    val currentDeviceId: String? = null,
    /** 当前会话的 relay 状态（页头「已连接 / 未连接」的数据源）。 */
    val relayState: RelayState = RelayState.Idle,
    /** 失败收敛后的一句话；非空时界面显示错误条。 */
    val error: String? = null,
    /** 一次性提示（已删除 / 已重命名 / 需要重新配对）；界面消费后调 [DevicesViewModel.dismissNotice]。 */
    val notice: String? = null,
) {
    /** 当前选中设备；id 指向的设备已被删除时为 `null`。 */
    val currentDevice: DeviceRecord?
        get() = devices.firstOrNull { it.id == currentDeviceId }
}

/**
 * 设备列表：列表 + 当前设备 + 增删改 + 打开设备。
 *
 * ## 连接由「打开设备」驱动
 *
 * 打开一台设备做两件事：把它设为当前设备（持久化），并让引擎会话切到它的 URL
 * （[EnginePort.ensureSession]，幂等）。**连接不在这里等结果**：`start` 是非挂起的，
 * 进度通过 [EnginePort.engineState] / [EnginePort.relayState] 观察，界面读
 * [DevicesUiState.relayState] 就能显示六态。
 *
 * 设备行上显示的连接信息是**持久化的最后已知状态**（`DeviceRecord.lastRelayStatus`），
 * 因为列表里那些设备并没有活着的会话。
 *
 * @param repository 设备与设置的持久化面（生产是 `StoreDeviceRepository`）。
 * @param engine 引擎会话面。
 */
class DevicesViewModel(
    private val repository: DeviceRepository,
    private val engine: EnginePort,
) : ViewModel() {

    private val _state = MutableStateFlow(DevicesUiState())

    /** 界面唯一的状态入口。 */
    val state: StateFlow<DevicesUiState> = _state.asStateFlow()

    private val observers = mutableListOf<Job>()

    init {
        observe()
    }

    /** 读盘失败后的「重试」：重新订阅一次（DataStore 的流是冷的，重订阅就是重读）。 */
    fun retry() {
        _state.update { it.copy(loading = true, error = null) }
        observe()
    }

    /**
     * 打开一台设备：设为当前设备，并把引擎会话切到它。
     *
     * 凭据不可用（Keystore 密钥丢失 ⇒ `remoteUrl == null`）时不建会话，只给一条提示
     * ——这种设备需要重新配对（见 `DeviceRecord.needsRepair`）。
     */
    fun openDevice(id: String) {
        val device = _state.value.devices.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            try {
                repository.setCurrentDevice(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
                return@launch
            }
            val url = device.remoteUrl
            if (url == null) {
                _state.update { it.copy(notice = "「${device.name}」的凭据不可用，需要重新配对") }
                return@launch
            }
            engine.ensureSession(url)
        }
    }

    /** 删除一台设备（删的正是当前设备时，持久化层会顺带清掉当前选中）。 */
    fun removeDevice(id: String) {
        viewModelScope.launch {
            try {
                val removed = repository.removeDevice(id)
                _state.update {
                    it.copy(notice = if (removed) "已删除设备" else "设备已不存在（可能已被删除）")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /** 改显示名。空白名字会被持久化层忽略。 */
    fun renameDevice(id: String, name: String) {
        viewModelScope.launch {
            try {
                val changed = repository.renameDevice(id, name)
                _state.update {
                    it.copy(notice = if (changed) "已重命名" else "名字没有变化（空白名字会被忽略）")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /** 清掉一次性提示。 */
    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    /** 清掉错误条。 */
    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private fun observe() {
        observers.forEach { it.cancel() }
        observers.clear()
        observers += collect(repository.devices) { list ->
            _state.update { it.copy(loading = false, devices = list) }
        }
        observers += collect(repository.currentDevice) { device ->
            _state.update { it.copy(currentDeviceId = device?.id) }
        }
        observers += collect(engine.relayState) { relay ->
            _state.update { it.copy(relayState = relay) }
        }
    }

    /**
     * 订阅一条流并把它映射进状态；**失败不抛**，收敛成 [DevicesUiState.error]。
     *
     * 订阅中断后不再自动重试（避免坏数据把界面钉在重试循环里），由用户点「重试」走 [retry]。
     */
    private fun <T> collect(flow: Flow<T>, onValue: (T) -> Unit): Job =
        viewModelScope.launch {
            try {
                flow.collect(onValue)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.toUserMessage()) }
            }
        }

    private fun fail(e: Throwable) {
        _state.update { it.copy(error = e.toUserMessage()) }
    }
}
