package app.zemote.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

/** 把下载好的 APK 交给系统安装器之后的结果。每个分支都带一句能直接给用户看的话。 */
sealed class InstallResult {
    /** 可直接展示给用户的说明。失败路径不留空白，避免"点了没反应"。 */
    abstract val userMessage: String

    /** 安装器已拉起，剩下由系统界面处理。 */
    data object Launched : InstallResult() {
        override val userMessage = "已打开系统安装器，请在弹窗里确认安装。"
    }

    /**
     * Android 8+ 的"安装未知应用"开关没给本应用打开。调用方应展示 [userMessage]，
     * 并在用户同意后调 [UpdateInstaller.openUnknownSourcesSettings]。
     */
    data object NeedInstallPermission : InstallResult() {
        override val userMessage =
            "需要先允许 Zemote 安装未知应用：设置 → 应用 → Zemote → 安装未知应用 → 允许，然后重试。"
    }

    /** 要装的包不在本地（多半是安装前被清理掉了）。 */
    data class MissingFile(val path: String) : InstallResult() {
        override val userMessage = "安装包不存在，请重新下载：$path"
    }

    /**
     * 下载到的包与当前应用**不是同一把密钥签的**。
     *
     * [expected] / [actual] 是 SHA-256 指纹，只写日志给排查用；给用户的话里不出现，
     * 那对用户没有意义。
     */
    data class SignerMismatch(val expected: String, val actual: String) : InstallResult() {
        override val userMessage =
            "这个安装包的签名与当前应用不一致，已拒绝安装。请到官方发布页重新下载。"
    }

    data class Failed(val detail: String) : InstallResult() {
        override val userMessage = "启动安装器失败：$detail"
    }
}

/**
 * 用 [FileProvider] 把 APK 交给系统安装器。
 *
 * 为什么必须走 FileProvider：Android 7 起对第三方应用直接暴露 `file://` 会抛
 * `FileUriExposedException`。authority 用 `${applicationId}.fileprovider`（debug 包带
 * `.debug` 后缀，靠 `context.packageName` 取值才不会和 release 撞车），路径白名单在
 * `res/xml/file_paths.xml`，只放开了 `updates/`。
 *
 * 注意：本类**只负责拉起系统安装器**，不做静默安装（那需要 PackageInstaller 会话）。
 * `AndroidManifest.xml` 已声明 `REQUEST_INSTALL_PACKAGES`；即便声明了，用户仍要手动为
 * Zemote 打开"安装未知应用"，见 [NeedInstallPermission]。
 */
class UpdateInstaller(private val context: Context) {

    /**
     * 是否已允许安装未知应用。`canRequestPackageInstalls()` 是 API 26 引入的，
     * 本项目 minSdk 就是 26，所以不需要版本判断。
     */
    fun canRequestPackageInstalls(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** 打开"安装未知应用"授权页。返回是否成功拉起。 */
    fun openUnknownSourcesSettings(): Boolean {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).apply { addNewTaskFlagIfNeeded() }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /**
     * 下载到的 APK 是否与**当前运行的应用**由同一把密钥签名。
     *
     * 为什么必须查这一步：更新清单与 `.md5` 来自同一个 release，**能替换 APK 的人同样能替换
     * 校验值** —— MD5 只证明"传输没坏"，不证明"包是官方发的"。而签名比对用的是本机已安装
     * 应用的签名，攻击者没有我们的私钥，伪造不出能通过这一步的包。这是自更新链路上唯一一个
     * 真正防篡改的检查。
     *
     * 顺带解决另一件事：签名不同源时系统安装器本来也会拒绝
     * （`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），提前查出来能给用户一句明确的话，
     * 而不是让他对着系统的失败弹窗发呆。
     *
     * 注意：debug 包与 release 包签名不同，所以**用 debug 包去装 CI 出的正式包会被这里拦下**
     * —— 这是正确行为（装了也会失败）。要测真机安装请用与目标包同源的构建。
     */
    fun hasSameSignerAsInstalled(apk: File): Boolean {
        val mine = signerDigests(installedInfo()) ?: return false
        val theirs = signerDigests(archiveInfo(apk) ?: return false) ?: return false
        return mine.isNotEmpty() && mine == theirs
    }

    /** 当前应用与给定 APK 的签名指纹（十六进制 SHA-256），仅用于日志与排查。 */
    fun signerFingerprints(apk: File): Pair<List<String>, List<String>> {
        val mine = signerDigests(installedInfo()).orEmpty().toList()
        val theirs = signerDigests(archiveInfo(apk)).orEmpty().toList()
        return mine to theirs
    }

    /** 发 `ACTION_VIEW` + `application/vnd.android.package-archive`。 */
    fun install(apk: File): InstallResult {
        if (!apk.isFile) return InstallResult.MissingFile(apk.absolutePath)
        if (!canRequestPackageInstalls()) return InstallResult.NeedInstallPermission

        // 签名校验放在拉起安装器之前，且在这里内部做——调用方绕不过去。
        val (mine, theirs) = signerFingerprints(apk)
        if (mine.isEmpty() || mine.toSet() != theirs.toSet()) {
            return InstallResult.SignerMismatch(
                expected = mine.joinToString(",").ifEmpty { "(读不到本应用签名)" },
                actual = theirs.joinToString(",").ifEmpty { "(读不到安装包签名)" },
            )
        }

        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        } catch (e: IllegalArgumentException) {
            // 路径不在 file_paths.xml 白名单里。属于配置错误，别静默失败。
            return InstallResult.Failed("安装包不在 FileProvider 白名单内（检查 res/xml/file_paths.xml）：${e.message}")
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            // 不给这个 flag，安装器拿到 URI 也读不到内容。
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addNewTaskFlagIfNeeded()
        }
        return try {
            context.startActivity(intent)
            InstallResult.Launched
        } catch (e: ActivityNotFoundException) {
            InstallResult.Failed("系统里没有能处理 APK 安装的应用（部分定制 ROM 移除了安装器）")
        } catch (e: SecurityException) {
            // 未知来源开关在 startActivity 阶段被拦，会以 SecurityException 冒出来。
            InstallResult.NeedInstallPermission
        }
    }

    private fun Intent.addNewTaskFlagIfNeeded() {
        // 从非 Activity 上下文启动必须带 NEW_TASK；从 Activity 启动则不该带，
        // 否则安装器会被塞进一个新任务栈。
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    // ----------------------------------------------------------------------
    // 签名读取
    // ----------------------------------------------------------------------

    /** API 28 起用 `GET_SIGNING_CERTIFICATES`；26/27 只能退回已废弃的 `GET_SIGNATURES`。 */
    private fun signerFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    private fun installedInfo(): PackageInfo? = try {
        context.packageManager.getPackageInfo(context.packageName, signerFlags())
    } catch (e: PackageManager.NameNotFoundException) {
        // 自己的包查不到基本不可能发生；返回 null 让调用方按"校验失败"处理（fail-closed）。
        null
    }

    private fun archiveInfo(apk: File): PackageInfo? =
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, signerFlags())

    /**
     * 取一个包的**实际签名者**指纹集合。
     *
     * 用 `apkContentsSigners`（而不是 `signingCertificateHistory`）：前者是"这个包实际由谁签的"，
     * 后者含密钥轮换前的旧证书，拿历史去比会把"旧密钥签的包"也放进来。
     */
    private fun signerDigests(info: PackageInfo?): Set<String>? {
        if (info == null) return null
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        return signatures.orEmpty().mapTo(LinkedHashSet()) { sha256Hex(it.toByteArray()) }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
