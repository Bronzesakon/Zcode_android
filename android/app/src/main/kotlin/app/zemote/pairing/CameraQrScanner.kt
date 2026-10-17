package app.zemote.pairing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 用 CameraX + ML Kit 解码二维码的 [QrScanner] 实现。
 *
 * ## 为什么是 CameraX + ML Kit（bundled）
 *
 * - CameraX 的 `Preview` + `ImageAnalysis` 把「取景」与「逐帧分析」拆开：预览交给
 *   `PreviewView`，分析拿到的是 `ImageProxy`，生命周期由 `bindToLifecycle` 统一管，
 *   页面切后台 / 旋转 / 销毁时相机自动释放，不需要自己写 `onPause/onResume`。
 * - ML Kit 用 **bundled** 的 `com.google.mlkit:barcode-scanning`（不是
 *   `play-services-mlkit-*`）：模型随 APK 打包，不依赖设备上的 Google Play 服务，
 *   国内机型也能用；代价只是包体大一点。
 * - 只开 `FORMAT_QR_CODE`：配对二维码只有这一种，少开格式能明显降低单帧耗时。
 *
 * ## 为什么预览视图由调用方注入
 *
 * CameraX 的 `Preview` 需要一个 `SurfaceProvider`，而 `PreviewView` 是 Android View，
 * 只能在界面层创建（Compose 里是 `AndroidView`）。所以本类不自己造视图，而是接收
 * 调用方给的 [PreviewView]。传 `null` 时只绑 `ImageAnalysis`——这样同一个类也能在
 * 没有界面的场景（例如单测外的简单探测）里用。
 *
 * ## 线程模型
 *
 * - `ImageAnalysis` 的分析器跑在**单线程** executor 上：ML Kit 的 `process()` 是异步的，
 *   但同一时刻只允许一个解码任务在飞（[decodeInFlight]），否则会积压 `ImageProxy`
 *   并很快触发「缓冲区耗尽」。
 * - `bindToLifecycle` / `unbindAll` 必须在主线程，[scan] 内部用
 *   `Dispatchers.Main.immediate` 保证这一点。
 * - `pending` 续体只在主线程被写入 / 消费；解码成功的回调由 ML Kit 投递到主线程。
 *
 * ## 生命周期与收尾
 *
 * [scan] 成功拿到第一个非空二维码后**立即解绑相机**：配对页随后就会离开，继续开着
 * 摄像头只是耗电。界面销毁时调用 [close]（`DisposableEffect` 里），它会唤醒还在等的
 * [scan]（返回 `null` = 用户取消）、解绑相机、关闭 ML Kit 客户端并停掉 executor。
 *
 * ## 权限
 *
 * 权限申请属于界面职责（见 `QrScanScreen`）；这里只做防御性检查：没有 CAMERA 权限
 * 时 [scan] 返回 `null` 并打日志，不抛异常——`QrScanner` 的契约里 `null` 就是取消。
 *
 * @param context 任意 Context，内部只保留 `applicationContext`。
 * @param lifecycleOwner 相机绑定的生命周期（Compose 里是 `LocalLifecycleOwner`）。
 * @param previewView 取景视图；`null` = 只做无预览的帧分析。
 */
class CameraQrScanner(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView? = null,
) : QrScanner, AutoCloseable {

    private val appContext: Context = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)

    /** 单线程：保证同一时刻只有一个 `ImageProxy` 被 ML Kit 占用。 */
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "zemote-qr-decode").apply { isDaemon = true }
    }

    private val barcodeScanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build(),
    )

    private val _torchAvailable = MutableStateFlow(false)

    /** 当前相机是否有闪光灯；`bind` 成功后才可信。 */
    val torchAvailable: StateFlow<Boolean> = _torchAvailable.asStateFlow()

    private val _torchOn = MutableStateFlow(false)

    /** 手电筒是否打开（以 CameraX 的调用结果为准，失败时保持原值）。 */
    val torchOn: StateFlow<Boolean> = _torchOn.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)

    /** 相机启动 / 绑定失败的用户可读原因；成功绑定后清空。 */
    val error: StateFlow<String?> = _error.asStateFlow()

    @Volatile
    private var closed = false

    /** 正在等待结果的续体；同一时刻最多一个（[scan] 有重入保护）。 */
    @Volatile
    private var pending: CancellableContinuation<String?>? = null

    private val scanning = AtomicBoolean(false)
    private val decodeInFlight = AtomicBoolean(false)
    private val decodeFailureLogged = AtomicBoolean(false)

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null

    /**
     * 绑定相机并等待第一个二维码。
     *
     * @return 扫到的原始文本（未 trim、未校验）；`null` = 用户取消、没有权限、
     *   相机启动失败，或已经 [close]。
     */
    override suspend fun scan(): String? {
        if (closed) return null
        if (!hasCameraPermission()) {
            Log.w(TAG, "没有 CAMERA 权限，scan() 视为用户取消")
            return null
        }
        if (!scanning.compareAndSet(false, true)) {
            Log.w(TAG, "scan() 已在运行，忽略重复调用")
            return null
        }

        _error.value = null
        try {
            val provider = awaitCameraProvider()
            return withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine<String?> { continuation ->
                    if (closed || !continuation.isActive) {
                        if (continuation.isActive) continuation.resume(null)
                        return@suspendCancellableCoroutine
                    }

                    pending = continuation
                    continuation.invokeOnCancellation {
                        pending = null
                        // 取消可能发生在任意线程；解绑必须在主线程。
                        mainExecutor.execute { unbind() }
                    }

                    try {
                        bind(provider)
                    } catch (e: Exception) {
                        Log.w(TAG, "相机启动失败", e)
                        pending = null
                        _error.value = e.message ?: "相机启动失败"
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            }
        } catch (e: CancellationException) {
            // 页面离开 / 权限变化导致的正常取消：不要翻译成错误。
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "取 CameraProvider 失败", e)
            _error.value = e.message ?: "相机不可用"
            return null
        } finally {
            scanning.set(false)
        }
    }

    /**
     * 从相册图片里解二维码（不经过相机）。
     *
     * 用 `InputImage.fromFilePath` 让 ML Kit 自己解码图片；读文件 / 解码都放到 IO
     * 线程，避免大图卡住主线程。识别不到二维码时返回 `null`（不是异常）。
     *
     * @param uri `ActivityResultContracts.GetContent` 返回的 content URI。
     */
    suspend fun decodeFromGallery(uri: Uri): String? {
        if (closed) return null

        val image = try {
            withContext(Dispatchers.IO) { InputImage.fromFilePath(appContext, uri) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "读取相册图片失败", e)
            return null
        } catch (e: SecurityException) {
            Log.w(TAG, "没有读取该图片的权限", e)
            return null
        } catch (e: Exception) {
            Log.w(TAG, "无法解码该图片", e)
            return null
        }

        if (closed) return null

        return suspendCancellableCoroutine<String?> { continuation ->
            barcodeScanner.process(image)
                .addOnSuccessListener { barcodes ->
                    if (continuation.isActive) continuation.resume(barcodes.firstRawValue())
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "相册图片二维码解码失败", e)
                    if (continuation.isActive) continuation.resume(null)
                }
                .addOnCanceledListener {
                    if (continuation.isActive) continuation.resume(null)
                }
        }
    }

    /** 切换手电筒。没有闪光灯或相机还没绑定时静默忽略。 */
    fun toggleTorch() = setTorch(!_torchOn.value)

    /** 打开 / 关闭手电筒；以 CameraX 的实际调用结果回写状态。 */
    fun setTorch(enabled: Boolean) {
        val camera = camera ?: return
        if (!camera.cameraInfo.hasFlashUnit()) return
        camera.cameraControl.enableTorch(enabled).addListener(
            { _torchOn.value = enabled },
            mainExecutor,
        )
    }

    /**
     * 释放相机、ML Kit 客户端与线程池。
     *
     * 可重复调用；界面销毁时必须调一次，否则分析线程与 ML Kit 模型会一直挂着。
     */
    override fun close() {
        if (closed) return
        closed = true

        val continuation = pending
        pending = null
        if (continuation != null && continuation.isActive) continuation.resume(null)

        mainExecutor.execute { unbind() }
        runCatching { barcodeScanner.close() }
            .onFailure { Log.w(TAG, "关闭 ML Kit 客户端失败", it) }
        analysisExecutor.shutdown()
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private suspend fun awaitCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(appContext)
            future.addListener(
                {
                    try {
                        val provider = future.get()
                        if (continuation.isActive) continuation.resume(provider)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                },
                mainExecutor,
            )
        }

    /** 主线程：把取景与分析用例绑到生命周期上。 */
    private fun bind(provider: ProcessCameraProvider) {
        // 重复绑定（例如用户取消后再次扫码）先清掉旧用例。
        provider.unbindAll()

        val analysis = ImageAnalysis.Builder()
            // 解码慢于相机出帧时丢弃旧帧，只保留最新一帧，避免队列无限增长。
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply { setAnalyzer(analysisExecutor, ::analyze) }

        val useCases = mutableListOf<UseCase>(analysis)
        previewView?.let { view ->
            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(view.surfaceProvider)
            }
            useCases += preview
        }

        val bound = provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            *useCases.toTypedArray(),
        )

        cameraProvider = provider
        camera = bound
        decodeFailureLogged.set(false)
        _torchAvailable.value = bound.cameraInfo.hasFlashUnit()
        _torchOn.value = false
        _error.value = null
    }

    /** 分析线程：每一帧提交给 ML Kit，同一时刻只允许一个任务在飞。 */
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        if (closed || !decodeInFlight.compareAndSet(false, true)) {
            proxy.close()
            return
        }

        val mediaImage = proxy.image
        if (mediaImage == null) {
            decodeInFlight.set(false)
            proxy.close()
            return
        }

        try {
            // fromMediaImage 也可能因为像素格式异常而抛错，所以放进 try：否则
            // decodeInFlight 会永远为 true、proxy 也不会被关闭。
            val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
            barcodeScanner.process(image)                .addOnSuccessListener { barcodes ->
                    barcodes.firstRawValue()?.let(::deliver)
                }
                .addOnFailureListener { e ->
                    // 失败可能每帧重复，只记第一条，避免刷爆日志。
                    if (decodeFailureLogged.compareAndSet(false, true)) {
                        Log.w(TAG, "二维码解码失败", e)
                    }
                }
                .addOnCompleteListener {
                    decodeInFlight.set(false)
                    // 必须等 ML Kit 读完 mediaImage 才能归还缓冲区。
                    proxy.close()
                }
        } catch (e: Exception) {
            decodeInFlight.set(false)
            proxy.close()
            if (decodeFailureLogged.compareAndSet(false, true)) {
                Log.w(TAG, "提交解码任务失败", e)
            }
        }
    }

    /** 主线程：把结果交给 [scan] 的等待者，并立刻停掉相机。 */
    private fun deliver(raw: String) {
        val continuation = pending ?: return
        pending = null
        if (continuation.isActive) continuation.resume(raw)
        mainExecutor.execute { unbind() }
    }

    /** 主线程：解绑所有用例并复位手电筒状态。 */
    private fun unbind() {
        camera = null
        _torchOn.value = false
        _torchAvailable.value = false
        runCatching { cameraProvider?.unbindAll() }
            .onFailure { Log.w(TAG, "解绑相机失败", it) }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /** 取第一个有非空文本的码；同一帧里有多个码时按 ML Kit 返回顺序取第一个。 */
    private fun List<Barcode>.firstRawValue(): String? =
        firstNotNullOfOrNull { barcode ->
            barcode.rawValue?.takeIf { it.isNotBlank() }
        }

    private companion object {
        const val TAG = "CameraQrScanner"
    }
}
