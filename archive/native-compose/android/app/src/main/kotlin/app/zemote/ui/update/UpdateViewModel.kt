package app.zemote.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zemote.ui.toUserMessage
import app.zemote.update.DownloadEvent
import app.zemote.update.DownloadProgress
import app.zemote.update.DownloadRequest
import app.zemote.update.DownloadResult
import app.zemote.update.InstallResult
import app.zemote.update.UpdateCheckResult
import app.zemote.update.UpdateChecker
import app.zemote.update.UpdateDownloader
import app.zemote.update.UpdateFailure
import app.zemote.update.VersionManifest
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 「关于与更新」页的阶段。
 *
 * 一个字段表达整页形态，而不是 `loading/error/data` 三件套：这条流程是**单向推进**的
 * （检查 → 有更新 → 下载 → 待安装），每步都会覆盖上一步，用枚举最不容易出「两个状态同时为真」
 * 的界面 bug。
 */
enum class UpdatePhase {
    /** 还没检查过。 */
    Idle,

    /** 正在拉版本清单。 */
    Checking,

    /** 已是最新（含装了更新的内测包的情况）。 */
    UpToDate,

    /** 还没发过滚动预发布——不是错误，界面不该弹「检查失败」。 */
    NoRelease,

    /** 有更新，可以下载。 */
    UpdateAvailable,

    /** 正在下载（进度看 [UpdateUiState.progress]）。 */
    Downloading,

    /** 下载并通过校验，可以交给系统安装器。 */
    ReadyToInstall,

    /** 检查 / 下载失败（分类看 [UpdateUiState.failure]，文案看 [UpdateUiState.error]）。 */
    Failed,
}

/**
 * 「关于与更新」页的状态。
 *
 * @property currentVersionCode 当前安装包的 versionCode（`BuildConfig.VERSION_CODE`）。
 * @property progress 下载进度；`fraction == null` 时界面显示不确定进度条。
 * @property failure 检查失败的**分类**（决定界面给「重试」还是「去网页下载」）。
 * @property error 可展示的失败文案（检查 / 下载都可能填）。
 * @property installResult 交给系统安装器的结果；[InstallResult.userMessage] 可直接展示。
 */
data class UpdateUiState(
    val currentVersionCode: Int,
    val phase: UpdatePhase = UpdatePhase.Idle,
    val manifest: VersionManifest? = null,
    val releasePageUrl: String? = null,
    val progress: DownloadProgress? = null,
    val downloadedFile: File? = null,
    val failure: UpdateFailure? = null,
    val error: String? = null,
    val installResult: InstallResult? = null,
) {
    /** 有可下载的更新。 */
    val canDownload: Boolean
        get() = phase == UpdatePhase.UpdateAvailable && manifest != null
}

/**
 * 安装能力。
 *
 * 抽成接口的唯一原因：真实实现 [app.zemote.update.UpdateInstaller] 要 `Context`
 * （`FileProvider` + `PackageManager` 签名比对），**JVM 单测构造不出来**。
 * 表现层只关心「交出去的结果」。
 */
interface ApkInstaller {

    /** 把已下载并校验过的 APK 交给系统安装器。 */
    fun install(apk: File): InstallResult

    /** 打开「安装未知应用」授权页。返回是否成功拉起。 */
    fun openUnknownSourcesSettings(): Boolean
}

/**
 * 下载进度卡的通知出口。
 *
 * 单独抽出来是为了让 [UpdateViewModel] 不依赖 `Context`（JVM 单测里构造不出通知）。
 */
interface DownloadNotificationSink {

    /** 进度；`indeterminate` 时百分比未知。 */
    fun onProgress(percent: Int, indeterminate: Boolean)

    /** 下载结束（成功、失败或取消），撤掉进度卡。 */
    fun onFinished()
}

/**
 * 应用内更新：检查 → 下载（含进度与续传）→ 安装。
 *
 * 三层的实现直接注入（它们本来就全部依赖注入、可在 JVM 单测里用假 `HttpFetcher` 跑），
 * 只有安装那一步要经过 [ApkInstaller] 这道窄接口。
 *
 * 取消语义：下载跑在 [viewModelScope] 里，界面销毁 / [cancelDownload] 都会取消协程；
 * `UpdateDownloader` 在取消时**保留** `.part`，下次继续时自动续传。
 *
 * @param checker 版本清单检查器。
 * @param downloader 下载 + 校验器（生产目录是 `getFilesDir()/updates`）。
 * @param installer 系统安装器。
 * @param currentVersionCode 当前安装包的 versionCode，展示用。
 * @param downloadNotifications 下载进度卡（`null` = 不发）。
 */
class UpdateViewModel(
    private val checker: UpdateChecker,
    private val downloader: UpdateDownloader,
    private val installer: ApkInstaller,
    currentVersionCode: Int,
    private val downloadNotifications: DownloadNotificationSink? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(UpdateUiState(currentVersionCode = currentVersionCode))

    /** 界面唯一的状态入口。 */
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    /** 检查成功时拿到的下载请求（下载按钮要用的两条直链）。 */
    private var pendingRequest: DownloadRequest? = null

    private var downloadJob: Job? = null

    /** 检查更新。重复调用被忽略（避免连点发起多次请求，吃掉 GitHub 的匿名限流）。 */
    fun check() {
        if (_state.value.phase == UpdatePhase.Checking) return
        viewModelScope.launch {
            _state.update {
                it.copy(phase = UpdatePhase.Checking, error = null, failure = null, installResult = null)
            }
            val result = try {
                checker.check()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // UpdateChecker 内部已把已知失败收敛成 Failed；这里是兜底，别让界面卡在 Checking。
                UpdateCheckResult.Failed(UpdateFailure.Network, e.toUserMessage())
            }
            applyCheckResult(result)
        }
    }

    /** 下载并校验。没有可下载的更新时无副作用。 */
    fun download() {
        val request = pendingRequest ?: return
        if (_state.value.phase == UpdatePhase.Downloading) return
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    phase = UpdatePhase.Downloading,
                    progress = null,
                    error = null,
                    installResult = null,
                )
            }
            try {
                downloader.download(request).collect { event ->
                    when (event) {
                        is DownloadEvent.Progress -> {
                            _state.update { it.copy(progress = event.progress) }
                            val fraction = event.progress.fraction
                            downloadNotifications?.onProgress(
                                percent = ((fraction ?: 0f) * 100).toInt().coerceIn(0, 100),
                                indeterminate = fraction == null,
                            )
                        }

                        is DownloadEvent.Finished -> {
                            applyDownloadResult(event.result)
                            downloadNotifications?.onFinished()
                        }
                    }
                }
            } catch (e: CancellationException) {
                downloadNotifications?.onFinished()
                // 取消不是失败：`.part` 留着下次续传；状态由 cancelDownload() 复位。
                throw e
            } catch (e: Exception) {
                downloadNotifications?.onFinished()
                _state.update { it.copy(phase = UpdatePhase.Failed, error = e.toUserMessage()) }
            }
        }
    }

    /** 取消下载（已下的部分保留，下次续传）。 */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _state.update {
            it.copy(phase = if (pendingRequest != null) UpdatePhase.UpdateAvailable else UpdatePhase.Idle)
        }
    }

    /** 把下载好的包交给系统安装器。 */
    fun install() {
        val file = _state.value.downloadedFile
        if (file == null) {
            _state.update { it.copy(error = "还没有下载好的安装包") }
            return
        }
        _state.update { it.copy(installResult = installer.install(file)) }
    }

    /** 打开「安装未知应用」授权页（[InstallResult.NeedInstallPermission] 之后的下一步）。 */
    fun openUnknownSourcesSettings() {
        val opened = installer.openUnknownSourcesSettings()
        if (!opened) {
            _state.update { it.copy(error = "没能打开系统授权页，请手动到「设置 → 应用 → Zemote」里允许安装未知应用") }
        }
    }

    /** 清掉安装结果提示。 */
    fun dismissInstallResult() {
        _state.update { it.copy(installResult = null) }
    }

    /** 清掉错误文案。 */
    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private fun applyCheckResult(result: UpdateCheckResult) {
        when (result) {
            is UpdateCheckResult.UpToDate -> {
                pendingRequest = null
                _state.update {
                    it.copy(
                        phase = UpdatePhase.UpToDate,
                        manifest = null,
                        releasePageUrl = null,
                        progress = null,
                        downloadedFile = null,
                    )
                }
            }

            is UpdateCheckResult.UpdateAvailable -> {
                pendingRequest = DownloadRequest(
                    manifest = result.manifest,
                    apkUrl = result.apkUrl,
                    md5Url = result.md5Url,
                )
                _state.update {
                    it.copy(
                        phase = UpdatePhase.UpdateAvailable,
                        manifest = result.manifest,
                        releasePageUrl = result.releasePageUrl,
                        progress = null,
                        downloadedFile = null,
                    )
                }
            }

            is UpdateCheckResult.NoPublishedRelease -> {
                pendingRequest = null
                _state.update {
                    it.copy(phase = UpdatePhase.NoRelease, error = null, failure = null)
                }
            }

            is UpdateCheckResult.Failed -> {
                pendingRequest = null
                _state.update {
                    it.copy(phase = UpdatePhase.Failed, failure = result.reason, error = result.detail)
                }
            }
        }
    }

    private fun applyDownloadResult(result: DownloadResult) {
        when (result) {
            is DownloadResult.Success -> _state.update {
                it.copy(
                    phase = UpdatePhase.ReadyToInstall,
                    downloadedFile = result.file,
                    // 下满了：进度条落在 100%（界面据 fraction == 1f 判断完成）。
                    progress = DownloadProgress(
                        bytesDownloaded = result.sizeBytes,
                        totalBytes = result.sizeBytes,
                        resumedFrom = 0,
                    ),
                )
            }

            is DownloadResult.ChecksumMismatch -> _state.update {
                it.copy(
                    phase = UpdatePhase.Failed,
                    error = "下载校验失败（MD5 不符）：期望 ${result.expectedMd5}，实际 ${result.actualMd5}",
                )
            }

            is DownloadResult.SizeMismatch -> _state.update {
                it.copy(
                    phase = UpdatePhase.Failed,
                    error = "下载字节数不符：期望 ${result.expectedBytes}，实际 ${result.actualBytes}",
                )
            }

            is DownloadResult.Failed -> _state.update {
                it.copy(phase = UpdatePhase.Failed, error = "${result.reason}：${result.detail}")
            }
        }
    }
}
