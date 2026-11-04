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
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 图片加载与全屏预览。
 *
 * 不引 Coil：整个应用只有两种来源（`http(s)` 的 Markdown 图片、`attachmentRead` 的附件），
 * 用 HttpURLConnection + BitmapFactory 足够。缓存全部进程内且有界（原图字节与解码结果
 * 都按条数 + 字节数双封顶），退出即释放；解码一律在后台线程并做缩略图降采样。
 */

/** 附件缩略图边长（UI-CONFIRM §1.1：图片 48dp 缩略图）。 */
val AttachmentThumbSize: Dp = 48.dp

/**
 * 原图字节缓存：条数与总字节双上限。
 *
 * 之前是无界 ConcurrentHashMap + 单张 32MB 上限：浏览过的每张图都常驻内存，
 * 图片一多就是几百 MB。现在只保留最近几张，退出进程自然释放。
 */
private class ByteImageCache(
    private val maxEntries: Int = 6,
    private val maxTotalBytes: Long = 32L * 1024 * 1024,
) {
    private val map = LinkedHashMap<String, ByteArray>(8, 0.75f, true)
    private var totalBytes = 0L

    @Synchronized
    fun get(key: String): ByteArray? = map[key]

    @Synchronized
    fun put(key: String, bytes: ByteArray) {
        // 单张就超预算：不入缓存，免得把其它图全部挤掉。
        if (bytes.size > maxTotalBytes) return
        map.remove(key)?.let { totalBytes -= it.size }
        map[key] = bytes
        totalBytes += bytes.size
        val iterator = map.entries.iterator()
        while (iterator.hasNext() && (map.size > maxEntries || totalBytes > maxTotalBytes)) {
            totalBytes -= iterator.next().value.size
            iterator.remove()
        }
    }
}

/** 解码结果缓存键：同一张图按不同解码目标（缩略图 / 预览）分别缓存。 */
private data class BitmapKey(val key: String, val maxDimensionPx: Int)

/** 解码结果缓存：条数与总像素字节双上限，避免大图把缓存撑成几十 MB。 */
private class BitmapImageCache(
    private val maxEntries: Int = 12,
    private val maxTotalBytes: Long = 24L * 1024 * 1024,
) {
    private val map = LinkedHashMap<BitmapKey, ImageBitmap>(16, 0.75f, true)
    private var totalBytes = 0L

    @Synchronized
    fun get(key: BitmapKey): ImageBitmap? = map[key]

    @Synchronized
    fun put(key: BitmapKey, bitmap: ImageBitmap) {
        val bytes = bytesOf(bitmap)
        // 单张就超预算：不入缓存（仍然渲染，只是下次要重新解码）。
        if (bytes > maxTotalBytes) return
        map.remove(key)?.let { totalBytes -= bytesOf(it) }
        map[key] = bitmap
        totalBytes += bytes
        val iterator = map.entries.iterator()
        while (iterator.hasNext() && (map.size > maxEntries || totalBytes > maxTotalBytes)) {
            totalBytes -= bytesOf(iterator.next().value)
            iterator.remove()
        }
    }

    /** ARGB_8888 每像素 4 字节。 */
    private fun bytesOf(bitmap: ImageBitmap): Long =
        bitmap.width.toLong() * bitmap.height.toLong() * 4L
}

private val imageBytesCache = ByteImageCache()
private val imageBitmapCache = BitmapImageCache()

/**
 * 未显式给缩略图尺寸时的解码目标（像素，最长边）。
 *
 * 注意是「目标」不是硬上限：采样按 2 的幂取整，实际最长边落在 [目标, 2×目标)，
 * 这样小图不会被放大，缩略图也不会因为采样过度而发虚。
 */
private const val DEFAULT_MAX_DECODE_PX = 1024

/** 全屏预览的解码目标：手机屏幕上 1.5K 长边足够清晰，放大 2x 内仍可看。 */
private const val PREVIEW_MAX_DECODE_PX = 1_536

/**
 * 计算 BitmapFactory 的 inSampleSize（2 的幂）。
 *
 * 取「采样后最长边仍不小于 [maxDimension]」的最大 2 的幂，因此结果落在
 * [maxDimension, 2×maxDimension)：既不会把缩略图采样到发虚，也把内存封在
 * 目标的 4 倍像素以内（4000px 原图按 ARGB_8888 整张解就是 64MB）。
 */
internal fun calculateSampleSize(width: Int, height: Int, maxDimension: Int): Int {
    if (width <= 0 || height <= 0 || maxDimension <= 0) return 1
    val longest = maxOf(width, height)
    var sample = 1
    // 上限 1 shl 20：防止恶意/损坏的尺寸头把 sample 乘到溢出。
    while (sample < (1 shl 20) && longest / (sample * 2) >= maxDimension) {
        sample *= 2
    }
    return sample
}

/** 解码（含降采样）。调用方必须放到后台线程：这里只做纯 CPU 工作。 */
private fun decodeSampled(bytes: ByteArray?, maxDimensionPx: Int): ImageBitmap? {
    if (bytes == null || bytes.isEmpty()) return null
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sample = calculateSampleSize(bounds.outWidth, bounds.outHeight, maxDimensionPx)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    } catch (e: OutOfMemoryError) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}

/** 下载一张 http(s) 图片；失败返回 `null`。 */
suspend fun fetchImageBytes(url: String): ByteArray? {
    imageBytesCache.get(url)?.let { return it }
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
                imageBytesCache.put(url, bytes)
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
 * 解码全部在后台线程且按缩略图尺寸降采样；解码结果与原始字节都走有界缓存，
 * 命中时首帧直接出图（不会先闪一下进度环）。
 *
 * 加载中用极简进度环、失败用 broken-image 占位，两者都走 theme 令牌，
 * 不再写死白色文字（浅色主题下原来会看不见）。
 *
 * @param key 缓存键（URL 或附件 ref）。
 * @param loader 取字节；返回 `null` 时显示失败占位。
 * @param contentScale 缩略图用 [ContentScale.Crop]，预览用 [ContentScale.Fit]。
 * @param maxDecodeSize 解码目标（最长边，实际结果落在 [目标, 2×目标)）；
 *   `null` 时用 [DEFAULT_MAX_DECODE_PX]。
 */
@Composable
fun RemoteImage(
    key: String,
    loader: suspend (String) -> ByteArray?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    onClick: (() -> Unit)? = null,
    maxDecodeSize: Dp? = null,
) {
    val density = LocalDensity.current
    val maxPx = remember(density, maxDecodeSize) {
        if (maxDecodeSize == null) {
            DEFAULT_MAX_DECODE_PX
        } else {
            with(density) { maxDecodeSize.toPx() }.toInt().coerceAtLeast(64)
        }
    }
    val cacheKey = remember(key, maxPx) { BitmapKey(key, maxPx) }

    var bitmap by remember(cacheKey) { mutableStateOf(imageBitmapCache.get(cacheKey)) }
    var failed by remember(cacheKey) { mutableStateOf(false) }

    LaunchedEffect(cacheKey) {
        if (bitmap != null) return@LaunchedEffect
        val decoded = withContext(Dispatchers.Default) {
            val bytes = loader(key) ?: return@withContext null
            imageBytesCache.put(key, bytes)
            decodeSampled(bytes, maxPx)
        }
        if (decoded != null) {
            imageBitmapCache.put(cacheKey, decoded)
            bitmap = decoded
        } else {
            failed = true
        }
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
                        // 缩略图按显示尺寸上限解码，避免整张原图进内存。
                        maxDecodeSize = thumbSize,
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
    val cacheKey = remember(key) { BitmapKey(key, PREVIEW_MAX_DECODE_PX) }
    var image by remember(cacheKey) { mutableStateOf(imageBitmapCache.get(cacheKey)) }
    var loading by remember(cacheKey) { mutableStateOf(image == null) }
    LaunchedEffect(cacheKey) {
        if (image != null) {
            loading = false
            return@LaunchedEffect
        }
        val decoded = withContext(Dispatchers.Default) {
            val bytes = loader(key) ?: return@withContext null
            imageBytesCache.put(key, bytes)
            decodeSampled(bytes, PREVIEW_MAX_DECODE_PX)
        }
        image = decoded
        loading = false
    }
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
            // 局部 val 供 smart cast：委托属性（by remember）不能直接 smart cast。
            val shown = image
            when {
                shown != null -> Image(
                    bitmap = shown,
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
