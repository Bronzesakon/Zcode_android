package app.zemote.update

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 应用内更新用到的常量。
 *
 * 为什么读清单、不解析 release 标题：滚动预发布的 tag 恒为 `android-pre`（见
 * `.github/workflows/android.yml` 的 B 段），tag 本身判断不出新旧；标题里虽然带了
 * versionName 与构建时间，但那是给人看的文本，格式一变更新器就瞎。CI 在打包后额外
 * 产出 `zemote-version.json`，更新器只读它。
 *
 * 旧 Flutter 实现里"按 CPU 选 APK"那一步（`selectUpdateAsset`）已删除：现在只出
 * arm64-v8a 单包，`abi` 字段只用于交叉校验清单，不做选择。
 */
object UpdateConfig {
    /** 发布仓库。 */
    const val REPO = "Bronzesakon/Zcode_android"

    /** 日常构建的滚动预发布 tag；正式版是 `vX.Y.Z` 的 release，另有流程。 */
    const val ROLLING_TAG = "android-pre"

    /** 版本清单的资产名。 */
    const val MANIFEST_ASSET = "zemote-version.json"

    /** 唯一 ABI。清单里的 abi 与它不一致就拒绝，避免误装到别的包。 */
    const val ABI = "arm64-v8a"

    /** 取滚动预发布的 release 对象（含资产列表与 browser_download_url）。 */
    const val RELEASE_API_URL = "https://api.github.com/repos/$REPO/releases/tags/$ROLLING_TAG"

    /** 给人看的发布页：更新失败时让用户自己去下载。 */
    const val RELEASE_PAGE_URL = "https://github.com/$REPO/releases/tag/$ROLLING_TAG"

    /**
     * 清单解析器。`ignoreUnknownKeys` 是刻意开的：清单以后加字段（例如签名摘要、
     * 最低支持版本）不应该让旧客户端直接报错。但**必填字段缺失仍然是错误**——那说明
     * CI 换了格式，宁可报"清单格式异常"也不要拿缺省值去比较版本。
     */
    val json: Json = Json { ignoreUnknownKeys = true }
}

/**
 * GitHub 上 `zemote-version.json` 的镜像，字段与 CI 生成的一一对应。
 *
 * 版本比较只看 [versionCode]（yyMMddHH，UTC+8）：它是单调递增的整数，不涉及
 * 语义化版本的预发布段比较；[versionName] 只用于展示。
 */
@Serializable
data class VersionManifest(
    val versionCode: Int,
    val versionName: String,
    val abi: String,
    val apkAsset: String,
    val md5Asset: String,
    val sizeBytes: Long,
)

/** 更新检测的结果。 */
sealed class UpdateCheckResult {
    /** 已经是最新（含清单 versionCode 更旧的情况，例如用户装了更新的内测包）。 */
    data class UpToDate(val currentVersionCode: Int) : UpdateCheckResult()

    /** 有更新，[apkUrl] / [md5Url] 是解析后的直链。 */
    data class UpdateAvailable(
        val manifest: VersionManifest,
        val apkUrl: String,
        val md5Url: String,
        val releasePageUrl: String,
    ) : UpdateCheckResult()

    /** 还没发过滚动预发布（release 404）。不是错误，不该弹"检查失败"。 */
    data class NoPublishedRelease(val detail: String) : UpdateCheckResult()

    /** 检查失败，[reason] 用于区分是网络问题还是数据问题。 */
    data class Failed(val reason: UpdateFailure, val detail: String) : UpdateCheckResult()
}

/** 更新检测失败的分类。UI 可以据此决定"重试"还是"去网页下载"。 */
enum class UpdateFailure {
    /** 连不上 / 超时 / 读到一半断了。 */
    Network,

    /** 连上了但状态码不对（403 限流、5xx 等）。 */
    Http,

    /** 清单本身读不懂：JSON 坏了、必填字段缺失、sizeBytes 非正。 */
    MalformedManifest,

    /** 清单能读，但它指向的资产在 release 里不存在。 */
    InvalidAsset,
}

/**
 * 下载进度。[totalBytes] 取清单里的 sizeBytes（权威值），拿不到时为 null，
 * 此时 [fraction] 为 null，UI 应显示不确定进度条。
 */
data class DownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    /** 本次从哪个偏移开始续传；0 表示从头下。等于总长表示命中了本地缓存。 */
    val resumedFrom: Long,
) {
    val fraction: Float?
        get() {
            val total = totalBytes ?: return null
            if (total <= 0) return null
            return (bytesDownloaded.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
        }
}

/**
 * 下载流的事件。终止事件只有 [Finished] 一个，它一定在流结束时发出一次——
 * 这样调用方 `collect` 到 Finished 就知道结果，不必再额外订阅一个回调。
 */
sealed class DownloadEvent {
    data class Progress(val progress: DownloadProgress) : DownloadEvent()
    data class Finished(val result: DownloadResult) : DownloadEvent()
}

/** 下载结果。只有 [Success] 才允许进入安装流程。 */
sealed class DownloadResult {
    /** 校验通过，[file] 已是最终文件名（`.part` 已改名）。 */
    data class Success(val file: java.io.File, val md5: String, val sizeBytes: Long) : DownloadResult()

    /** MD5 不符：临时文件已删除，未产出最终文件。 */
    data class ChecksumMismatch(val expectedMd5: String, val actualMd5: String) : DownloadResult()

    /** 字节数不符：临时文件已删除，未产出最终文件。 */
    data class SizeMismatch(val expectedBytes: Long, val actualBytes: Long) : DownloadResult()

    data class Failed(val reason: DownloadFailure, val detail: String) : DownloadResult()
}

/** 下载失败的分类。 */
enum class DownloadFailure {
    /** 网络异常（含读到一半断流）。已下的部分保留在 `.part`，下次可续传。 */
    Network,

    /** 状态码不对。 */
    Http,

    /** 校验文件读不出来 / 里面没有 MD5。 */
    Manifest,

    /** 本地文件系统出错（建目录、改名、写盘）。 */
    Local,

    /**
     * 没预料到的异常。有这一档是为了让调用方的 `collect` 永远拿得到结果：
     * 更新流程挂在半路却不报错，比报一个含糊的错更难查。
     */
    Unexpected,
}

private val MD5_HEX = Regex("\\b[0-9a-fA-F]{32}\\b")

/**
 * 从 `.md5` 资产内容里抠出 32 位十六进制摘要（`md5sum` 的输出形如
 * `<hex>  zemote-arm64-v8a.apk`，也可能是裸 hex）。抠不出来返回 null。
 */
fun parseMd5Hex(content: String): String? = MD5_HEX.find(content)?.value?.lowercase()

/** 计算字节数组的 MD5 十六进制小写串。 */
fun ByteArray.md5Hex(): String =
    MessageDigest.getInstance("MD5").digest(this).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

/**
 * 把消息摘要里的当前值转成十六进制小写串。
 *
 * 注意：MD5 在这里是**完整性校验**——对齐旧实现、并与服务端发布的 `.md5` 资产一致，
 * 用来发现"下到一半断了""续传拼错了""缓存是别的版本的"这类问题。它**不是安全边界**：
 * MD5 已被证明可构造碰撞，而清单与校验值又来自同一个源，能改 APK 的人同样能改
 * `.md5`。真正的防篡改要靠 APK 签名（系统安装器会验），不要拿这里的校验当挡箭牌。
 */
internal fun MessageDigest.hexDigest(): String =
    digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
