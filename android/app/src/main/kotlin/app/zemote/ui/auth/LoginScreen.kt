package app.zemote.ui.auth

import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.zemote.auth.OAuthFlow
import app.zemote.auth.OAuthProvider
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteRadius
import app.zemote.ui.theme.ZemoteType

/**
 * 登录页：平台选择 + 授权 WebView。
 *
 * 与网页端 3.16.1 的登录页一致：
 * - 标题「登录后继续使用 Web 远程控制」；
 * - 说明「请选择与电脑端相同的登录平台，并使用同一账号。」；
 * - 两个入口「连接 Z.ai 继续使用 / 全球」「连接 BigModel 继续使用 / 中国」。
 *
 * 授权页在 WebView 内完成；`zcode.z.ai/remote/v4?...code=...` 被拦截后交给
 * [LoginViewModel]，**不会**真的加载远控页面。
 *
 * 视觉：44dp 页头、12dp 圆角按钮、4dp 栅格，颜色/字号全部走令牌。
 */
@Composable
fun LoginScreen(
    state: LoginUiState,
    onStart: (OAuthProvider) -> Unit,
    onCallback: (String) -> Unit,
    onCancelWebView: () -> Unit,
    onClose: () -> Unit,
    onSuccess: () -> Unit,
) {
    val colors = LocalZemoteColors.current

    LaunchedEffect(state.completed) {
        if (state.completed) onSuccess()
    }

    BackHandler {
        if (state.request != null) onCancelWebView() else onClose()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colors.background,
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            LoginHeader(
                title = if (state.request == null) "登录" else "正在登录",
                onClose = onClose,
            )
            if (state.request == null) {
                ProviderChooser(
                    busy = state.busy,
                    error = state.error,
                    onStart = onStart,
                )
            } else {
                Box(Modifier.fillMaxSize()) {
                    key(state.request.state) {
                        OAuthWebView(
                            url = state.request.authorizeUrl,
                            onCallback = onCallback,
                        )
                    }
                    if (state.busy) {
                        Surface(
                            color = colors.background.copy(alpha = 0.86f),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "正在完成登录…",
                                    style = ZemoteType.uiSm,
                                    color = colors.foregroundSubtle,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 44dp 页头：左「关闭」、居中标题、底部 1px 分隔线。 */
@Composable
private fun LoginHeader(title: String, onClose: () -> Unit) {
    val colors = LocalZemoteColors.current
    Column(Modifier.fillMaxWidth().background(colors.header)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) {
                Text("关闭", style = ZemoteType.uiBase, color = colors.foregroundSubtle)
            }
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = ZemoteType.uiBase,
                fontWeight = FontWeight.Medium,
                color = colors.foreground,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 与左侧「关闭」等宽，保证标题真正居中。
            Spacer(Modifier.width(64.dp))
        }
        HorizontalDivider(color = colors.border)
    }
}

@Composable
private fun ProviderChooser(
    busy: Boolean,
    error: String?,
    onStart: (OAuthProvider) -> Unit,
) {
    val colors = LocalZemoteColors.current
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "登录后继续使用 Web 远程控制",
            style = ZemoteType.uiXl,
            fontWeight = FontWeight.Medium,
            color = colors.foreground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "请选择与电脑端相同的登录平台，并使用同一账号。",
            style = ZemoteType.uiSm,
            color = colors.foregroundSubtle,
        )
        Spacer(Modifier.height(24.dp))

        OAuthProvider.entries.forEach { provider ->
            ProviderButton(provider = provider, enabled = !busy, onClick = { onStart(provider) })
            Spacer(Modifier.height(12.dp))
        }

        if (error != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = error,
                style = ZemoteType.uiSm,
                color = colors.destructive,
                textAlign = TextAlign.Start,
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Z.ai 与 BigModel 的账号数据不互通。请确认与电脑端选择了同一平台。",
            style = ZemoteType.uiXs,
            color = colors.foregroundSubtlest,
        )
    }
}

@Composable
private fun ProviderButton(provider: OAuthProvider, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalZemoteColors.current
    val shape = RoundedCornerShape(ZemoteRadius.xl)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        color = colors.primary.copy(alpha = if (enabled) 1f else 0.35f),
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = provider.actionLabel,
                style = ZemoteType.uiBase,
                fontWeight = FontWeight.Medium,
                color = colors.primaryForeground.copy(alpha = if (enabled) 1f else 0.5f),
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(colors.primaryForeground.copy(alpha = 0.14f))
                    .border(
                        width = 1.dp,
                        color = colors.primaryForeground.copy(alpha = 0.28f),
                        shape = RoundedCornerShape(999.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = provider.regionLabel,
                    style = ZemoteType.ui2xs,
                    color = colors.primaryForeground.copy(alpha = if (enabled) 1f else 0.5f),
                )
            }
        }
    }
}

@Composable
private fun OAuthWebView(url: String, onCallback: (String) -> Unit) {
    val context = LocalContext.current
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            WebView(context).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                // 部分登录页会针对 WebView 的 "wv" 标记做拦截；去掉它，保留正常 UA。
                settings.userAgentString = settings.userAgentString?.replace("; wv", "")
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = OAuthWebViewClient(onCallback)
                loadUrl(url)
            }
        },
        onRelease = { it.stopLoading(); it.destroy() },
    )
}

private class OAuthWebViewClient(
    private val onCallback: (String) -> Unit,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        if (OAuthFlow.isCallback(url)) {
            onCallback(url)
            return true
        }
        return false
    }

    @Deprecated("Android 7 以下兼容入口；minSdk 26，保留只为覆盖部分定制 WebView。")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        if (OAuthFlow.isCallback(url)) {
            onCallback(url)
            return true
        }
        return false
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        // 兜底：某些登录页用 JS 改 location，个别 WebView 不会走 shouldOverrideUrlLoading。
        if (OAuthFlow.isCallback(url)) onCallback(url)
    }
}
