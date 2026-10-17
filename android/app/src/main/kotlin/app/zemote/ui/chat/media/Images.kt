package app.zemote.ui.chat.media

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.zemote.ui.theme.ZemoteType
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 图片加载与全屏预览。
 *
 * 不引 Coil：整个应用只有两种来源（`http(s)` 的 Markdown 图片、`attachmentRead` 的附件），
 * 用 HttpURLConnection + BitmapFactory 足够；缓存只做进程内、按 key 去重，退出即释放。
 */

private val imageCache = ConcurrentHashMap<String, ByteArray>()

private fun decode(bytes: ByteArray?): ImageBitmap? {
    if (bytes == null || bytes.isEmpty()) return null
    return try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (e: OutOfMemoryError) {
        null
    }
}

/** 下载一张 http(s) 图片；失败返回 `null`。 */
suspend fun fetchImageBytes(url: String): ByteArray? {
    imageCache[url]?.let { return it }
    if (!url.startsWith("http://") && !url.startsWith("https://")) return null
    return withContext(Dispatchers.IO) {
        try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "image/*")
            }
            try {
                if (connection.responseCode !in 200..299) return@withContext null
                val bytes = connection.inputStream.use { it.readBytes() }
                if (bytes.size > MAX_IMAGE_BYTES) return@withContext null
                imageCache[url] = bytes
                bytes
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * 异步取一张图并渲染。
 *
 * @param key 缓存键（URL 或附件 ref）。
 * @param loader 取字节；返回 `null` 时不渲染。
 */
@Composable
fun RemoteImage(
    key: String,
    loader: suspend (String) -> ByteArray?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    var bitmap by remember(key) { mutableStateOf(decode(imageCache[key])) }
    var failed by remember(key) { mutableStateOf(false) }

    LaunchedEffect(key) {
        if (bitmap != null) return@LaunchedEffect
        val bytes = loader(key)
        if (bytes != null) imageCache[key] = bytes
        bitmap = decode(bytes)
        failed = bitmap == null
    }

    Box(
        modifier = modifier
            .then(
                if (onClick != null) {
                    Modifier.pointerInput(key) {
                        detectTapGestures(onTap = { onClick() })
                    }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        when {
            image != null -> Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            failed -> Text(
                "图片加载失败",
                style = ZemoteType.ui2xs,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )

            else -> Text("加载中…", style = ZemoteType.ui2xs, color = Color.White.copy(alpha = 0.7f))
        }
    }
}

/** 全屏预览：双指缩放、单击关闭。 */
@Composable
fun ImageViewerDialog(
    key: String,
    loader: suspend (String) -> ByteArray?,
    onDismiss: () -> Unit,
) {
    var bytes by remember(key) { mutableStateOf(imageCache[key]) }
    LaunchedEffect(key) {
        if (bytes == null) bytes = loader(key)
    }
    val image = remember(bytes) { decode(bytes) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var scale by remember { mutableStateOf(1f) }
        var offsetX by remember { mutableStateOf(0f) }
        var offsetY by remember { mutableStateOf(0f) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        if (scale > 1f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        }
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 2000.dp)
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY,
                        ),
                )
            } else {
                Text("图片不可用", color = Color.White, style = ZemoteType.uiSm)
            }
        }
    }
}

private const val MAX_IMAGE_BYTES = 32 * 1024 * 1024
