package app.zemote.ui.chat.media

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteRadius
import app.zemote.ui.theme.ZemoteSpacing
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

/** 附件缩略图边长（UI-CONFIRM §1.1：图片 48dp 缩略图）。 */
val AttachmentThumbSize: Dp = 48.dp

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
 * 加载中用极简进度环、失败用 broken-image 占位，两者都走 theme 令牌，
 * 不再写死白色文字（浅色主题下原来会看不见）。
 *
 * @param key 缓存键（URL 或附件 ref）。
 * @param loader 取字节；返回 `null` 时显示失败占位。
 * @param contentScale 缩略图用 [ContentScale.Crop]，预览用 [ContentScale.Fit]。
 */
@Composable
fun RemoteImage(
    key: String,
    loader: suspend (String) -> ByteArray?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
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

    val description = when {
        failed -> "图片加载失败"
        bitmap != null && onClick != null -> "图片，点击查看大图"
        bitmap != null -> "图片"
        else -> "图片加载中"
    }

    Box(
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Image
            },
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        when {
            image != null -> Image(
                bitmap = image,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )

            failed -> Icon(
                imageVector = Icons.Default.BrokenImage,
                contentDescription = null,
                tint = LocalZemoteColors.current.foregroundSubtle,
                modifier = Modifier.size(20.dp),
            )

            else -> CircularProgressIndicator(
                color = LocalZemoteColors.current.foregroundSubtle,
                strokeWidth = 1.5.dp,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * 多图缩略图网格。
 *
 * 尺寸/圆角由调用方决定：
 *  - 只读用户气泡：80×80dp、圆角 12（[ZemoteRadius.xl]）、gap 8；
 *  - 编辑态附件：48dp（[AttachmentThumbSize]，默认值）；
 *  - Markdown 连续图片：96dp、每行 3 张。
 * 点击把原图 key 交给调用方打开 [ImageViewerDialog]。
 */
@Composable
fun ImageThumbGrid(
    keys: List<String>,
    loader: suspend (String) -> ByteArray?,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    thumbSize: Dp = AttachmentThumbSize,
    thumbShape: Shape = ZemoteRadius.mdShape,
    maxPerRow: Int = 4,
) {
    if (keys.isEmpty()) return
    val colors = LocalZemoteColors.current
    val perRow = maxPerRow.coerceAtLeast(1)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(ZemoteSpacing.space2),
    ) {
        keys.chunked(perRow).forEach { rowKeys ->
            Row(horizontalArrangement = Arrangement.spacedBy(ZemoteSpacing.space2)) {
                rowKeys.forEach { key ->
                    RemoteImage(
                        key = key,
                        loader = loader,
                        modifier = Modifier
                            .size(thumbSize)
                            .clip(thumbShape)
                            .border(1.dp, colors.border, thumbShape)
                            .background(colors.backgroundAlt),
                        onClick = { onClick(key) },
                    )
                }
            }
        }
    }
}

/** 全屏预览：双指缩放、拖动、单击关闭（保留原有手势）。 */
@Composable
fun ImageViewerDialog(
    key: String,
    loader: suspend (String) -> ByteArray?,
    onDismiss: () -> Unit,
) {
    var bytes by remember(key) { mutableStateOf(imageCache[key]) }
    var loading by remember(key) { mutableStateOf(bytes == null) }
    LaunchedEffect(key) {
        if (bytes == null) {
            bytes = loader(key)
            loading = false
        }
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
                        } else {
                            // 缩回 1x 时归位，避免图片停在屏幕外
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) }
                .semantics(mergeDescendants = true) {
                    contentDescription = "图片预览，点击关闭"
                    role = Role.Button
                },
            contentAlignment = Alignment.Center,
        ) {
            when {
                image != null -> Image(
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

                loading -> CircularProgressIndicator(
                    color = Color.White.copy(alpha = 0.8f),
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(28.dp),
                )

                else -> Text(
                    text = "图片不可用",
                    color = Color.White,
                    style = ZemoteType.uiSm,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private const val MAX_IMAGE_BYTES = 32 * 1024 * 1024
