package app.zemote.ui.chat.rich

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteColors
import app.zemote.ui.theme.ZemoteSpacing

/**
 * 消息流里的富内容块：KaTeX 公式或 mermaid 图。
 *
 * 渲染完全离线：所有资源都在 `assets/rich/`，WebView 禁止一切网络加载，
 * 页面用 `file:///android_asset/rich/` 作为 base URL 由 [WebView.loadDataWithBaseURL] 载入。
 *
 * 高度由页面里的 `AndroidBridge.onHeight(cssPx)` 回传；WebView 的 CSS px 与 dp 1:1，
 * 所以直接把回调值当作 dp 使用。页面报错（公式语法错误、不支持的图类型等）时回调
 * `AndroidBridge.onError(message)`，本组件退化为等宽源码文本。
 *
 * 主题：前景/边框/节点底色/底色全部按当前 `LocalZemoteColors` 注入页面，
 * 不用 `prefers-color-scheme` 或系统深色判断，保证跟随应用内主题；
 * WebView 自身背景设为透明，并把页面底色设成同一颜色，避免加载瞬间白底闪。
 *
 * 注意：mermaid 只支持 flowchart/graph 与 sequenceDiagram（见 `assets/rich/README.md`）。
 */
@Composable
fun RichBlock(
    type: RichBlockType,
    source: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = LocalZemoteColors.current
    val dark = colors.background.luminance() < 0.5f
    val html = remember(type, source, colors, dark) {
        buildHtml(context, type, source, colors, dark)
    }

    // 每个 html 版本独立一套状态；key() 让 AndroidView 在内容变化时重建，
    // 避免旧的 JS 回调写到已经不再被读取的旧状态上。
    key(type, source, dark) {
        var failure by remember { mutableStateOf<String?>(null) }
        var heightPx by remember { mutableIntStateOf(0) }

        if (failure != null) {
            Text(
                text = source,
                modifier = modifier
                    .fillMaxWidth()
                    .padding(vertical = ZemoteSpacing.space1),
                color = colors.foreground,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
        } else {
            AndroidView(
                modifier = modifier
                    .fillMaxWidth()
                    .height(if (heightPx > 0) heightPx.dp else 1.dp),
                factory = { ctx ->
                    createWebView(
                        context = ctx,
                        html = html,
                        onHeight = { heightPx = it },
                        onError = { failure = it },
                    )
                },
                onRelease = { it.destroy() },
            )
        }
    }
}

enum class RichBlockType {
    MATH_INLINE,
    MATH_BLOCK,
    MERMAID,
}

@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(
    context: Context,
    html: String,
    onHeight: (Int) -> Unit,
    onError: (String) -> Unit,
): WebView {
    return WebView(context).apply {
        // 先给 WebView 本身一个透明底，再由页面把 html/body 底色画成主题色，
        // 这样第一次绘制之前也不会露出系统默认的白色。
        setBackgroundColor(AndroidColor.TRANSPARENT)
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        isNestedScrollingEnabled = false
        isFocusable = false

        settings.apply {
            javaScriptEnabled = true
            blockNetworkLoads = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            databaseEnabled = false
            loadsImagesAutomatically = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            cacheMode = WebSettings.LOAD_NO_CACHE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // 颜色由我们按主题注入，关掉系统强制反色，避免二次染色。
                isAlgorithmicDarkeningAllowed = false
            }
        }

        addJavascriptInterface(RichBridge(onHeight, onError), "AndroidBridge")

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    onError(error?.description?.toString() ?: "load error")
                }
            }
        }

        loadDataWithBaseURL(
            "file:///android_asset/rich/",
            html,
            "text/html",
            "UTF-8",
            null,
        )
    }
}

/**
 * Public on purpose: `addJavascriptInterface` reflects over the class and needs
 * an accessible type with public methods named exactly `onHeight` / `onError`.
 */
class RichBridge(
    private val onHeight: (Int) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onHeight(height: Int) {
        val clamped = height.coerceIn(1, 20_000)
        main.post { onHeight(clamped) }
    }

    @JavascriptInterface
    fun onError(message: String?) {
        val text = message?.take(500)?.takeIf { it.isNotBlank() } ?: "render error"
        main.post { onError(text) }
    }
}

private fun buildHtml(
    context: Context,
    type: RichBlockType,
    source: String,
    colors: ZemoteColors,
    dark: Boolean,
): String {
    val template = context.assets
        .open("rich/index.html")
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText() }

    val sourceB64 = Base64.encodeToString(source.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    return template
        .replace("__TYPE__", if (type == RichBlockType.MERMAID) "mermaid" else "math")
        .replace("__DISPLAY__", if (type == RichBlockType.MATH_BLOCK) "1" else "0")
        .replace("__THEME__", if (dark) "dark" else "light")
        .replace("__FG__", colors.foreground.toCssHex())
        .replace("__LINE__", colors.foregroundSubtle.toCssHex())
        .replace("__NODE__", colors.card.toCssHex())
        .replace("__NOTE__", colors.accent.toCssHex())
        .replace("__NOTE_LINE__", colors.warning.toCssHex())
        .replace("__BG__", colors.background.toCssHex())
        .replace("__SCHEME__", if (dark) "dark" else "light")
        .replace("__SOURCE_B64__", sourceB64)
}

/**
 * Compose Color → `#RRGGBB`。
 *
 * 页面里的令牌都是有底色的不透明语义色；带 alpha 的几个（accent / warning）
 * 直接取 RGB，透明部分由页面背景兜底，不影响可读性。
 */
private fun Color.toCssHex(): String {
    val rgb = toArgb() and 0xFFFFFF
    return "#" + rgb.toString(16).padStart(6, '0')
}
