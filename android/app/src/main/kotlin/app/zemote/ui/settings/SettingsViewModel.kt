package app.zemote.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zemote.data.AppSettings
import app.zemote.data.AppTheme
import app.zemote.ui.devices.DeviceRepository
import app.zemote.ui.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 设置页的状态。
 *
 * 三态：`loading = true` 且 `settings` 是默认值 ⇒ 还在读盘；读盘失败 ⇒ [error]；
 * 正常 ⇒ [settings] 是磁盘上的真实值（界面每次改动都会经 `updateSettings` 落盘并回流）。
 */
data class SettingsUiState(
    val loading: Boolean = true,
    val settings: AppSettings = AppSettings(),
    val error: String? = null,
    val notice: String? = null,
)

/**
 * 设置：主题、语言、字号、代码字号、详细协议帧日志、更新通道。
 *
 * ## 每次改动都走「读-改-写」
 *
 * 每个 setter 都调 [DeviceRepository.updateSettings]（整段变换在**同一个事务**里完成），
 * 而不是「先把整个 [AppSettings] 读出来改一个字段再整体写回」——后者在并发下会丢更新
 * （见 `DeviceStore` 的类文档）。归一化（字号 clamp 等）由持久化层负责，这里不重复。
 *
 * 值本身不在这里做本地乐观更新：磁盘是唯一真相，改动会经 `settings` 流回流到 [SettingsUiState]。
 * 失败则收敛成 [SettingsUiState.error]。
 */
class SettingsViewModel(private val repository: DeviceRepository) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())

    /** 界面唯一的状态入口。 */
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                repository.settings.collect { settings ->
                    _state.update { it.copy(loading = false, settings = settings) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.toUserMessage()) }
            }
        }
    }

    /** 主题：跟随系统 / 深色 / 浅色。 */
    fun setTheme(theme: AppTheme) = mutate { it.copy(theme = theme) }

    /** 界面语言（`zh-CN` / `en-US`）。 */
    fun setLocale(locale: String) = mutate { it.copy(locale = locale) }

    /** 界面字号倍率（越界值由持久化层夹回合法区间）。 */
    fun setTextScale(scale: Float) = mutate { it.copy(textScale = scale) }

    /** 代码字号。 */
    fun setCodeFontSize(size: Float) = mutate { it.copy(codeFontSize = size) }

    /** 是否记录完整 relay 帧（可能含敏感数据，默认关）。 */
    fun setVerboseProtocolFrames(enabled: Boolean) = mutate { it.copy(verboseProtocolFrames = enabled) }

    /** 更新通道是否包含 Beta 版。 */
    fun setReceiveBetaUpdates(enabled: Boolean) = mutate { it.copy(receiveBetaUpdates = enabled) }

    /** 通知总开关。 */
    fun setNotificationEnabled(enabled: Boolean) = mutate { it.copy(notificationEnabled = enabled) }

    /** 完成/审批通知是否响铃与震动。 */
    fun setNotificationSound(enabled: Boolean) = mutate { it.copy(notificationSound = enabled) }

    fun setNotifyOnComplete(enabled: Boolean) = mutate { it.copy(notifyOnComplete = enabled) }

    fun setNotifyOnError(enabled: Boolean) = mutate { it.copy(notifyOnError = enabled) }

    fun setNotifyOnAttention(enabled: Boolean) = mutate { it.copy(notifyOnAttention = enabled) }

    /** 后台常驻（前台服务）。 */
    fun setForegroundServiceEnabled(enabled: Boolean) =
        mutate { it.copy(foregroundServiceEnabled = enabled) }

    /** 全部恢复默认值。 */
    fun resetToDefaults() = mutate { AppSettings() }

    /** 清掉提示 / 错误。 */
    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private fun mutate(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            try {
                repository.updateSettings(transform)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toUserMessage()) }
            }
        }
    }
}
