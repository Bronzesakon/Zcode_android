package app.zemote.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zemote.auth.OAuthCallback
import app.zemote.auth.OAuthFlow
import app.zemote.auth.OAuthProvider
import app.zemote.auth.OAuthRequest
import app.zemote.auth.OAuthTokenClient
import app.zemote.data.AuthStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 登录页状态。
 *
 * @property provider 已选择的平台；`null` = 还在选择页。
 * @property request 当前授权请求（含 nonce 与授权 URL）；`null` = 不显示 WebView。
 * @property busy 正在用授权码换 JWT。
 * @property error 授权/交换失败的收敛文案。
 * @property completed 登录成功且 JWT 已落盘。
 */
data class LoginUiState(
    val provider: OAuthProvider? = null,
    val request: OAuthRequest? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val completed: Boolean = false,
)

/**
 * Z.ai / BigModel OAuth 登录。
 *
 * 流程与网页端 3.16.1 一致：
 * 1. 选平台 → 生成 `state`（base64url JSON，含一次性 nonce）→ 打开授权页；
 * 2. 授权页回跳 `https://zcode.z.ai/remote/v4?...code=...&state=...`；
 * 3. 校验 nonce → `POST /api/v1/oauth/token` 换 JWT；
 * 4. JWT 交给 [AuthStore] 加密落盘。
 *
 * WebView 由界面层持有；本类只处理 URL 与网络，因此可在 JVM 单测里完整覆盖。
 */
class LoginViewModel(
    private val authStore: AuthStore,
    private val tokenClient: OAuthTokenClient,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    /** 选择平台并打开授权页。 */
    fun start(provider: OAuthProvider) {
        if (_state.value.busy || _state.value.completed) return
        _state.value = LoginUiState(
            provider = provider,
            request = OAuthFlow.create(provider),
        )
    }

    /** WebView 捕获到回调 URL。重复回调由 [LoginUiState.busy] / [LoginUiState.completed] 去重。 */
    fun onCallback(url: String) {
        val current = _state.value
        val request = current.request ?: return
        if (current.busy || current.completed) return
        if (!OAuthFlow.isCallback(url)) return

        when (val callback = OAuthFlow.parseCallback(url, request.nonce)) {
            OAuthCallback.NotCallback -> Unit
            is OAuthCallback.Failure -> _state.update {
                it.copy(error = "授权失败：${callback.message}", request = null)
            }
            is OAuthCallback.Invalid -> _state.update {
                it.copy(error = callback.message, request = null)
            }
            is OAuthCallback.Code -> exchange(request, callback.code)
        }
    }

    /** 关闭 WebView，回到平台选择页（保留错误提示）。 */
    fun cancelWebView() {
        if (_state.value.busy) return
        _state.update { it.copy(request = null) }
    }

    /** 界面已消费「登录成功」事件；下次再打开登录页时从选择页开始。 */
    fun consumeCompleted() {
        _state.update { it.copy(completed = false) }
    }

    /** 重新发起当前平台的授权（nonce 会重新生成）。 */
    fun retry() {
        val provider = _state.value.provider ?: return
        _state.value = LoginUiState(
            provider = provider,
            request = OAuthFlow.create(provider),
        )
    }

    private fun exchange(request: OAuthRequest, code: String) {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val session = tokenClient.exchange(
                    provider = request.provider,
                    code = code,
                    state = request.state,
                )
                authStore.save(
                    provider = session.provider.wire,
                    jwt = session.jwt,
                    displayName = session.displayName,
                    avatarUrl = session.avatarUrl,
                    expiresAt = session.expiresAt,
                )
                _state.update { it.copy(busy = false, completed = true, request = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        busy = false,
                        request = null,
                        error = e.message ?: "登录失败，请重试",
                    )
                }
            }
        }
    }
}
