package app.zemote.ui.pairing

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zemote.pairing.CameraQrScanner
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 扫码配对页：全屏相机取景 + 中央扫描框 + 手电筒 + 相册识别 + 粘贴链接。
 *
 * ## 职责边界
 *
 * 这里只负责「拿到一段文本」：扫码 / 相册 / 粘贴三条路径最终都走 [onResult]，
 * 文本的合法性（`PairingUrl.preflight`）与建会话仍然属于 `PairingViewModel`。
 * 因此本页**不需要** ViewModel，也不需要知道 `QrScanner` 接口——相机能力由内部的
 * [CameraQrScanner] 直接持有，界面销毁时一并释放。
 *
 * ## 为什么配色写死黑/白
 *
 * 相机取景是内容主体，顶栏 / 底栏直接压在实时画面上。如果跟随应用主题，浅色主题下
 * 会得到「白底白字」或「黑底黑字」的不可读组合；这里按相机类界面的惯例固定为
 * 黑底 + 白字 + 半透明压条，不读 `MaterialTheme`（这是本页唯一偏离主题令牌的地方）。
 *
 * ## 权限
 *
 * - 首次进入自动申请 CAMERA；
 * - 被拒时整页换成「去设置开启」+「改用粘贴链接」，相机与相册之外仍有可用路径；
 * - 从系统设置返回（ON_RESUME）会重新核对权限，用户在设置里打开后无需重进页面。
 *
 * @param onClose 关闭本页（顶栏「关闭」）。
 * @param onResult 识别 / 粘贴成功，参数是**已 trim** 的原始文本；只会回调一次。
 * @param modifier 外部布局修饰符（默认铺满）。
 */
@Composable
fun QrScanScreen(
    onClose: () -> Unit,
    onResult: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var cameraGranted by remember { mutableStateOf(context.hasCameraPermission()) }
    var pasteDialogVisible by remember { mutableStateOf(false) }
    var decoding by remember { mutableStateOf(false) }
    var galleryError by remember { mutableStateOf<String?>(null) }

    // 三条路径共用「只交付一次」的闸门：扫码回调与相册回调可能几乎同时到达。
    val delivered = remember { AtomicBoolean(false) }
    val currentOnResult by rememberUpdatedState(onResult)
    val deliver: (String) -> Unit = { raw ->
        val text = raw.trim()
        if (text.isNotEmpty() && delivered.compareAndSet(false, true)) {
            currentOnResult(text)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> cameraGranted = granted }

    LaunchedEffect(Unit) {
        if (!cameraGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // 用户可能去系统设置里打开权限再回来；回前台时补一次检查。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !cameraGranted) {
                cameraGranted = context.hasCameraPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // PreviewView 与 scanner 的生命周期跟随本页：离开组合即 close()，相机随之释放。
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // COMPATIBLE 用 TextureView：与 Compose 叠层、截图都更稳，代价是多一次拷贝。
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val scanner = remember(previewView, lifecycleOwner) {
        CameraQrScanner(context = context, lifecycleOwner = lifecycleOwner, previewView = previewView)
    }
    DisposableEffect(scanner) {
        onDispose { scanner.close() }
    }

    val torchAvailable by scanner.torchAvailable.collectAsStateWithLifecycle()
    val torchOn by scanner.torchOn.collectAsStateWithLifecycle()
    val cameraError by scanner.error.collectAsStateWithLifecycle()

    // 扫码挂起在 LaunchedEffect 里：页面离开 / 权限变化时自动取消，scanner 内部会解绑相机。
    LaunchedEffect(scanner, cameraGranted) {
        if (!cameraGranted) return@LaunchedEffect
        val raw = try {
            scanner.scan()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "扫码失败", e)
            null
        }
        if (raw != null) deliver(raw)
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                decoding = true
                galleryError = null
                val raw = try {
                    scanner.decodeFromGallery(uri)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "相册图片识别失败", e)
                    null
                } finally {
                    decoding = false
                }
                if (raw != null) {
                    deliver(raw)
                } else {
                    galleryError = "没能从这张图片里识别出二维码"
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (cameraGranted) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize(),
            )

            // 中央扫描框。
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(ScanFrameSize)
                    .border(2.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(16.dp)),
            )
            Text(
                text = "将二维码放入框内",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = ScanFrameSize / 2 + 16.dp),
            )
        } else {
            PermissionDenied(
                onOpenSettings = { context.openAppSettings() },
                onUsePaste = { pasteDialogVisible = true },
            )
        }

        // 顶栏：关闭 + 手电筒。
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onClose,
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
            ) {
                Text("关闭")
            }
            TextButton(
                onClick = { scanner.toggleTorch() },
                enabled = torchAvailable,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color.White,
                    disabledContentColor = Color.White.copy(alpha = 0.4f),
                ),
            ) {
                Text(if (torchOn) "关闭手电筒" else "手电筒")
            }
        }

        // 底栏：相册 / 粘贴 + 相机错误提示。
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (cameraGranted) {
                Text(
                    text = "对准桌面端 ZCode 的远控二维码",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
            }
            cameraError?.let { message ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "相机不可用：$message",
                    color = ErrorTextColor,
                    textAlign = TextAlign.Center,
                )
            }
            galleryError?.let { message ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = message,
                    color = ErrorTextColor,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ScanActionButton(
                    text = if (decoding) "识别中…" else "从相册选择",
                    enabled = !decoding,
                    onClick = { galleryLauncher.launch("image/*") },
                )
                ScanActionButton(
                    text = "粘贴链接",
                    enabled = true,
                    onClick = { pasteDialogVisible = true },
                )
            }
        }
    }

    if (pasteDialogVisible) {
        PasteLinkDialog(
            onDismiss = { pasteDialogVisible = false },
            onConfirm = { text ->
                pasteDialogVisible = false
                deliver(text)
            },
        )
    }
}

/** 权限被拒时的整页兜底：设置入口 + 不依赖相机的粘贴路径。 */
@Composable
private fun PermissionDenied(
    onOpenSettings: () -> Unit,
    onUsePaste: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "需要相机权限才能扫码",
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "也可以不开相机：从相册选一张二维码截图，或直接粘贴配对链接。",
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onOpenSettings) {
            Text("去设置开启")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = onUsePaste,
            colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
        ) {
            Text("改用粘贴链接")
        }
    }
}

/**
 * 粘贴链接对话框。
 *
 * 输入框状态放在对话框内部：每次打开都是一张白纸，不会把上一次失败的内容带进来。
 * 确认时只 trim 并回调，合法性判断留给 `PairingUrl.preflight`。
 */
@Composable
private fun PasteLinkDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("粘贴配对链接") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("https://…/remote/v4?sid=…&hash=…") },
                minLines = 2,
                maxLines = 4,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank(),
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

/** 相机页底栏按钮：黑底上的半透明白，不跟随应用主题。 */
@Composable
private fun ScanActionButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White.copy(alpha = 0.18f),
            contentColor = Color.White,
            disabledContainerColor = Color.White.copy(alpha = 0.08f),
            disabledContentColor = Color.White.copy(alpha = 0.4f),
        ),
    ) {
        Text(text)
    }
}

// ----------------------------------------------------------------------
// 平台小工具
// ----------------------------------------------------------------------

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

/** 打开本应用的系统设置页（权限被永久拒绝后唯一的补救入口）。 */
private fun Context.openAppSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { startActivity(intent) }
        .onFailure { Log.w(TAG, "打不开应用设置页", it) }
}

/** 扫描框边长；取 260dp，在常见手机宽度下留出足够取景余量。 */
private val ScanFrameSize = 260.dp

/** 黑底上的错误色（不读主题：主题的 error 在浅色下会偏暗）。 */
private val ErrorTextColor = Color(0xFFFFB4AB)

private const val TAG = "QrScanScreen"
