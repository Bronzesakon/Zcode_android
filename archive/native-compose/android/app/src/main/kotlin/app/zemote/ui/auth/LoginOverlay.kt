package app.zemote.ui.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.zemote.ui.AppContainer

/**
 * 登录页的全屏覆盖层。
 *
 * 由配对页 / 首页在「需要登录」时叠在自己的内容之上；登录成功或关闭后由调用方收起。
 * 覆盖层与宿主路由共享 ViewModelStore，因此登录过程中旋转屏幕不会丢授权请求。
 */
@Composable
fun LoginOverlay(
    container: AppContainer,
    onSuccess: () -> Unit,
    onClose: () -> Unit,
) {
    val viewModel: LoginViewModel = viewModel(factory = container.loginFactory())
    val state by viewModel.state.collectAsStateWithLifecycle()

    LoginScreen(
        state = state,
        onStart = viewModel::start,
        onCallback = viewModel::onCallback,
        onCancelWebView = viewModel::cancelWebView,
        onClose = onClose,
        onSuccess = {
            viewModel.consumeCompleted()
            onSuccess()
        },
    )
}
