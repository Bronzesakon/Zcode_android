package app.zemote.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URI
import java.net.URLDecoder
import java.util.UUID

/**
 * relay 连接状态。
 *
 * 取值与 Rust 侧 `RelayState`（`rust/crates/protocol/src/relay_client.rs`）以及 FFI 事件
 * `relay_state`（`docs/FFI.md`：`{"type":"relay_state","state":"paired"}`）的 9 个小写名
 * 一一对应。落盘/序列化用的就是这些小写名，宿主可以直接把协议层字符串丢给
 * [fromWire]，不需要再写一张映射表。
 */
@Serializable
enum class RelayStatus {
    @SerialName("idle")
    IDLE,

    @SerialName("connecting")
    CONNECTING,

    @SerialName("authenticating")
    AUTHENTICATING,

    @SerialName("waiting")
    WAITING,

    @SerialName("paired")
    PAIRED,

    @SerialName("reconnecting")
    RECONNECTING,

    @SerialName("error")
    ERROR,

    @SerialName("kicked")
    KICKED,

    @SerialName("closed")
    CLOSED,
    ;

    /** 序列化用的字符串（`"paired"`），与协议层一致。 */
    val wireName: String
        get() = serializer().descriptor.getElementName(ordinal)

    companion object {
        /** 没有已知状态时的取值（等价于「还没连过」）。 */
        val DEFAULT: RelayStatus = IDLE

        /**
         * 由协议层字符串还原状态。
         *
         * 未知值（协议新增了状态、或日志里混进别的东西）**不抛异常**，回落到 [DEFAULT]——
         * 持久化层不该因为协议扩展而崩掉界面。
         */
        fun fromWire(value: String?): RelayStatus {
            if (value == null) return DEFAULT
            val descriptor = serializer().descriptor
            return entries.firstOrNull { descriptor.getElementName(it.ordinal) == value } ?: DEFAULT
        }
    }
}

/**
 * 一台已添加的远控设备。
 *
 * ## 内存明文 / 落盘密文（重要）
 *
 * [remoteUrl] 在**内存里始终是明文**：
 * - 3.16.1+：`https://zcode.z.ai/remote/v4?app_version=…&sid=…&mid=…&name=…`
 *   （认证用 OAuth JWT，JWT 存在 [AuthStore]，不在 URL 里）；
 * - legacy：`https://zcode.z.ai/remote/v4?sid=…&hash=<passHash>&t=…&mid=…&name=…`
 *   （`hash` 是配对密钥材料，Rust 侧拿它做 HMAC 证明）。
 * 引擎要拿它去握手，所以内存中必须是可用的明文。
 *
 * 但它**绝不明文落盘**：[DeviceStore] 在写盘时用 [SecretCipher] 把整条 URL 加密，
 * 磁盘上只有 `enc:v1:<base64(iv||ciphertext)>`；读盘时再解密回明文。
 * 加解密只发生在「进出存储」这一个边界上，别的地方不要自己动。
 *
 * 本类带 `@Serializable` 是为了让模型本身可被序列化（导出/备份、跨进程传递都用得上）；
 * **不要**把 `Json.encodeToString(DeviceRecord)` 的结果直接写盘——那会把明文凭据落盘。
 * 落盘用的是 `DeviceStore` 内部那份密文形态（见 DeviceStore.kt）。
 *
 * ## 凭据不可用（需要重新配对）
 *
 * [remoteUrl] 为 `null` 表示**这条记录的凭据拿不到**：Android Keystore 里的密钥在
 * 卸载应用 / 清除数据后会丢，之前写下的密文就再也解不开。[DeviceStore] 遇到这种情况
 * 不会抛异常，也不会返回空串，而是把该条记录降级成 `remoteUrl == null` 暴露出来，
 * 由界面提示「该设备需要重新配对」。见 [needsRepair]。
 *
 * @property id 本地生成的设备 id（UUID），与桌面端的 sid 无关，删掉再加会换新的。
 * @property name 显示名；来源优先级：用户显式命名 > URL 的 `name` 参数 > host。
 * @property remoteUrl 明文远控 URL（含配对 hash）；`null` ⇒ 凭据不可用，需重新配对。
 * @property host 远控 URL 的 host（如 `zcode.z.ai`）。明文存储，用于展示与去重。
 * @property sid 远控 URL 的 `sid` 查询参数。明文存储，用于去重。
 * @property addedAt 首次添加时间（epoch millis）。重复添加同一台设备时**保持不变**。
 * @property lastConnectedAt 最后一次连接时间（epoch millis）；从未连接过为 `null`。
 * @property lastRelayStatus 最后一次已知的 relay 状态。
 * @property lastRelayError 最后一次 relay 失败的原因码（`auth-failed` / `invalid-mobile-connection` …）；
 *   进入 `paired` 后清空。只用于诊断展示，不参与连接决策。
 * @property lastRelayErrorMessage 最后一次 relay 失败的详情（服务端 message / 关闭原因），可能为空。
 * @property sortKey 列表排序键（越小越靠前）。新设备取「当前最大 + 1」追加到末尾；
 *   用独立整数而不是时间戳，是为了同一毫秒内连续添加也能有稳定顺序。
 */
@Serializable
data class DeviceRecord(
    val id: String,
    val name: String,
    val remoteUrl: String?,
    val host: String,
    val sid: String,
    val addedAt: Long,
    val lastConnectedAt: Long? = null,
    val lastRelayStatus: RelayStatus = RelayStatus.DEFAULT,
    val lastRelayError: String? = null,
    val lastRelayErrorMessage: String? = null,
    val sortKey: Long,
) {
    /** 凭据不可用（Keystore 密钥丢失或密文损坏）⇒ 这台设备需要重新扫码/粘贴链接配对。 */
    val needsRepair: Boolean
        get() = remoteUrl == null

    /** 去重键：同 host + 同 sid 视为同一台设备。 */
    val dedupKey: String
        get() = dedupKeyOf(host, sid)

    companion object {
        /** 去重键的构造规则，[DeviceStore] 与测试共用同一份实现。 */
        fun dedupKeyOf(host: String, sid: String): String = "$host|$sid"

        /** 本地设备 id：随机 UUID（v4）。 */
        fun newId(): String = UUID.randomUUID().toString()
    }
}

/**
 * 远控 URL 里持久化层需要的那几个字段。
 *
 * 完整协议参数（`t` / `app_version` / `theme` …）由 Rust 侧 `connection_params.rs` 解析，
 * 这里只取「展示 + 去重」要用的部分，避免在持久化层复制一份协议模型。
 */
internal data class RemoteUrlParts(
    val host: String,
    val sid: String,
    /** URL 的 `name` 参数，作为显示名的兜底。 */
    val deviceName: String?,
)

/**
 * 远控 URL 的最小解析器（`https://zcode.z.ai/remote/v4?sid=…&hash=…&t=…&mid=…&name=…`）。
 *
 * 只认 `https` / `wss` + 有 host + 有非空 `sid` 的 URL：没有 sid 的链接根本连不上桌面端，
 * 存下来只会变成一条永远连不通的死记录（旧实现会照存，这里选择直接报错）。
 */
internal object RemoteUrlParser {

    fun parse(raw: String): RemoteUrlParts? {
        val uri = try {
            URI(raw.trim())
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "wss") return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val query = parseQuery(uri.rawQuery)
        val sid = query["sid"]?.takeIf { it.isNotBlank() } ?: return null
        return RemoteUrlParts(
            host = host,
            sid = sid,
            deviceName = query["name"]?.takeIf { it.isNotBlank() },
        )
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val key = if (eq < 0) pair else pair.substring(0, eq)
            val value = if (eq < 0) "" else pair.substring(eq + 1)
            out[decode(key)] = decode(value)
        }
        return out
    }

    private fun decode(value: String): String = try {
        // 用 String 字符集的旧重载：URLDecoder.decode(String, Charset) 要 API 33。
        URLDecoder.decode(value, "UTF-8")
    } catch (_: Exception) {
        value
    }
}

/** 外观主题。与 `design/` 的「跟随系统 / 深色 / 浅色」三态对应。 */
@Serializable
enum class AppTheme {
    @SerialName("system")
    SYSTEM,

    @SerialName("light")
    LIGHT,

    @SerialName("dark")
    DARK,
}

/**
 * 应用设置（非凭据类的偏好，明文存盘即可）。
 *
 * 字段对齐旧实现 `Reference_code/lib/state/ui_settings.dart` 的 `UiSettings`
 * （locale / textScale / codeFontSize）以及设置页里的主题、详细帧日志、更新通道开关。
 */
@Serializable
data class AppSettings(
    /** 界面语言：`zh-CN` / `en-US`。 */
    val locale: String = DEFAULT_LOCALE,
    val theme: AppTheme = AppTheme.SYSTEM,
    /** 界面字号倍率，落盘前夹在 [TEXT_SCALE_MIN]..[TEXT_SCALE_MAX]。 */
    val textScale: Float = 1.0f,
    /** 代码字号，落盘前夹在 [CODE_FONT_SIZE_MIN]..[CODE_FONT_SIZE_MAX]。 */
    val codeFontSize: Float = 12.5f,
    /** 记录完整 relay 帧（可能含敏感数据，默认关）。 */
    val verboseProtocolFrames: Boolean = false,
    /** 更新通道是否包含 Beta 版。 */
    val receiveBetaUpdates: Boolean = false,
    /** 通知总开关（关掉后不发任何系统通知，但连接与前台服务继续）。 */
    val notificationEnabled: Boolean = true,
    /** 完成/审批通知是否响铃与震动。 */
    val notificationSound: Boolean = true,
    /** 事件勾选：任务完成。 */
    val notifyOnComplete: Boolean = true,
    /** 事件勾选：任务出错。 */
    val notifyOnError: Boolean = true,
    /** 事件勾选：等待授权/输入。 */
    val notifyOnAttention: Boolean = true,
    /** 后台常驻（前台服务）。关掉后系统可能回收连接。 */
    val foregroundServiceEnabled: Boolean = true,
) {
    /**
     * 把越界的值收回到合法区间。
     *
     * 旧实现是「在 setter 里 clamp」，这里改成写盘前统一归一：外部（导入的 JSON、
     * 手改过的文件）塞进来的脏值也会被拦住，而不是原样落盘。
     */
    fun normalized(): AppSettings = copy(
        locale = locale.trim().ifBlank { DEFAULT_LOCALE },
        textScale = textScale.coerceIn(TEXT_SCALE_MIN, TEXT_SCALE_MAX),
        codeFontSize = codeFontSize.coerceIn(CODE_FONT_SIZE_MIN, CODE_FONT_SIZE_MAX),
    )

    companion object {
        const val DEFAULT_LOCALE = "zh-CN"
        const val TEXT_SCALE_MIN = 0.8f
        const val TEXT_SCALE_MAX = 1.4f
        const val CODE_FONT_SIZE_MIN = 10f
        const val CODE_FONT_SIZE_MAX = 20f
    }
}
