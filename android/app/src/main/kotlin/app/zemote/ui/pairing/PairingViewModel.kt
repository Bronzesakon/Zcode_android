package app.zemote.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zemote.auth.RemoteTokenException
import app.zemote.data.AuthStore
import app.zemote.data.DeviceRecord
import app.zemote.engine.EngineState
import app.zemote.pairing.PairingUrl
import app.zemote.pairing.PreflightResult
import app.zemote.pairing.QrScanner
import app.zemote.pairing.RejectReason
import app.zemote.ui.EnginePort
import app.zemote.ui.devices.DeviceRepository
import app.zemote.ui.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 配对页的状态。
 *
 * @property input 粘贴框里的文本（界面受控输入；扫码结果也会写进来，方便用户核对）。
 * @property busy 正在扫码 / 建会话 / 入库。此时界面应禁用按钮。
 * @property rejection 前置筛查拒绝的**语义**（文案由界面层决定）。
 * @property error 引擎或持久化失败的收敛文案。
 * @property paired 成功入库的设备；非空时界面返回设备列表。
 * @property notice 一次性提示（例如「连接仍在进行中」）。
 * @property needsLogin 3.16.1 新链接需要先 OAuth 登录；界面据此打开登录页。
 * @property pendingUrl 等待登录完成后继续配对的链接。
 */
data class PairingUiState(
    val input: String = "",
    val busy: Boolean = false,
    val rejection: RejectReason? = null,
    val error: String? = null,
    val paired: DeviceRecord? = null,
    val notice: String? = null,
    val needsLogin: Boolean = false,
    val pendingUrl: String? = null,
)

/**
 * 配对：扫码 / 粘贴 → [PairingUrl.preflight] → 建会话 → 存设备。
 *
 * ## 顺序与失败语义（重要）
 *
 * 1. **前置筛查**：拒绝就直接给语义，不碰引擎（`PairingUrl` 的判定集合是 Rust 解析器的超集，
 *    所以它放行的链接仍可能被引擎回 `badInput`——那个错误原样展示，不翻译第二遍）。
 * 2. **建会话**：[EnginePort.ensureSession] 后等 [EngineState.Running] 或 [EngineState.Fatal]
 *    （上限 [connectTimeoutMs]）。
 *    - `Fatal` ⇒ **不存设备**：URL 不合法 / ABI 不匹配，存下来只会得到一条永远连不通的记录；
 *    - 超时 ⇒ **照存**：凭据本身没问题，多半只是网络慢；给一条提示让用户知道连接还在进行；
 *    - `Running` ⇒ 正常路径。
 * 3. **存设备**：`addDevice`（同 host + 同 sid 就地更新，不产生重复记录），再设为当前设备。
 *
 * 任何一步的失败都收敛成 [PairingUiState.error]，**不抛给界面**。
 *
 * @param repository 设备持久化面。
 * @param scanner 扫码能力；未接入时是 `UnavailableQrScanner`（`scan()` 返回 `null` = 用户取消）。
 * @param engine 引擎会话面。
 * @param connectTimeoutMs 等会话建立的上限；单测注入小值。
 */
class PairingViewModel(
    private val repository: DeviceRepository,
    private val scanner: QrScanner,
    private val engine: EnginePort,
    private val authStore: AuthStore,
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
) : ViewModel() {

    private val _state = MutableStateFlow(PairingUiState())

    /** 界面唯一的状态入口。 */
    val state: StateFlow<PairingUiState> = _state.asStateFlow()

    /** 粘贴框内容变化。清掉上一次的拒绝原因与错误（用户已经在改了）。 */
    fun onInputChanged(text: String) {
        _state.update { it.copy(input = text, rejection = null, error = null) }
    }

    /** 扫码：拿到的原文写进 [PairingUiState.input]，然后直接走 [submit]。 */
    fun scan() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, rejection = null, error = null) }
            val raw = try {
                scanner.scan()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.toUserMessage()) }
                return@launch
            }
            // null = 用户取消（或扫码能力未接入）：既不是错误也不是成功，安静收场。
            if (raw == null) {
                _state.update { it.copy(busy = false) }
                return@launch
            }
            _state.update { it.copy(input = raw.trim()) }
            runSubmit(raw)
        }
    }

    /** 提交粘贴框里的文本。 */
    fun submit() = submit(_state.value.input)

    /** 提交一段文本（扫码与粘贴共用这条路径）。 */
    fun submit(raw: String) {
        if (_state.value.busy) return
        viewModelScope.launch { runSubmit(raw) }
    }

    /** 清掉一次性提示。 */
    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private suspend fun runSubmit(raw: String) {
        _state.update { it.copy(busy = true, rejection = null, error = null) }
        try {
            when (val preflight = PairingUrl.preflight(raw)) {
                is PreflightResult.Rejected ->
                    _state.update { it.copy(busy = false, rejection = preflight.reason) }

                is PreflightResult.Ok -> {
                    val url = preflight.normalized
                    val token = authStore.token()
                    // 3.16.1 新链接没有 hash：没有 JWT 就先去登录，登录成功后再继续。
                    if (token == null && !PairingUrl.hasPassHash(url)) {
                        _state.update {
                            it.copy(
                                busy = false,
                                needsLogin = true,
                                pendingUrl = url,
                                error = null,
                            )
                        }
                        return
                    }
                    // 先记下 URL：换 token / 建会话失败时还能用同一条链接重试。
                    _state.update { it.copy(pendingUrl = url) }
                    connectAndSave(url, token)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteTokenException) {
            if (e.authRequired) authStore.clear()
            _state.update {
                it.copy(
                    busy = false,
                    needsLogin = e.authRequired,
                    error = e.toUserMessage(),
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(busy = false, error = e.toUserMessage()) }
        }
    }

    /**
     * 从登录页返回：拿到 JWT 就继续配对；用户取消则收起登录引导（保留输入，方便重试）。
     */
    fun onLoginReturn() {
        val pending = _state.value.pendingUrl
        viewModelScope.launch {
            val token = authStore.token()
            _state.update {
                it.copy(
                    needsLogin = false,
                    busy = token != null && pending != null,
                    // 成功配对后由 connectAndSave 清空；失败时保留，方便用户重试同一条链接。
                    pendingUrl = it.pendingUrl,
                )
            }
            if (token != null && pending != null) {
                try {
                    connectAndSave(pending, token)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RemoteTokenException) {
                    if (e.authRequired) authStore.clear()
                    _state.update {
                        it.copy(
                            busy = false,
                            needsLogin = e.authRequired,
                            error = e.toUserMessage(),
                        )
                    }
                } catch (e: Exception) {
                    _state.update { it.copy(busy = false, error = e.toUserMessage()) }
                }
            }
        }
    }

    /** 用户关掉了登录页。 */
    fun cancelLogin() {
        _state.update { it.copy(needsLogin = false, busy = false) }
    }

    private suspend fun connectAndSave(url: String, token: String?) {
        engine.ensureSession(url, token)
        val settled = withTimeoutOrNull(connectTimeoutMs) {
            engine.engineState.first {
                it is EngineState.Running || it is EngineState.Fatal
            }
        }

        if (settled is EngineState.Fatal) {
            _state.update {
                it.copy(busy = false, error = "建会话失败：${settled.reason}")
            }
            return
        }

        val record = try {
            repository.addDevice(url, PairingUrl.deviceLabel(url))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(busy = false, error = e.toUserMessage()) }
            return
        }

        try {
            repository.setCurrentDevice(record.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 选中失败不影响「配对成功」这个事实（设备已在列表里），不把它升级成错误。
            _state.update { it.copy(notice = "设备已保存，但没能设为当前设备：${e.toUserMessage()}") }
        }

        _state.update {
            it.copy(
                busy = false,
                paired = record,
                pendingUrl = null,
                notice = it.notice ?: if (settled == null) "设备已保存，连接仍在进行中" else null,
            )
        }
    }

    companion object {
        /**
         * 等会话建立的上限。
         *
         * 正常路径上 `start` 是「核对 ABI → nativeCreate → 发 start」三步，几毫秒到一秒；
         * 给 15 秒是为了容忍冷启动与慢设备。**超时不等于失败**（见类文档）。
         */
        const val CONNECT_TIMEOUT_MS: Long = 15_000
    }
}
