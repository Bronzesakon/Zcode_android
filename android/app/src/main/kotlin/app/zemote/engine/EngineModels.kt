package app.zemote.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

// ---------------------------------------------------------------------------
// 信封（契约 §3 / §4 / §5 / §8 / §9）
// ---------------------------------------------------------------------------

/**
 * `error` 字段。`kind` 取值：`panic` / `badInput` / `timeout` / `invalidState` / `protocol`。
 *
 * `message` 可为 `null`（契约 §3 的「不省略键」：`Option::None` 一律序列化成 `null`）。
 * 注意契约 §12：panic 的原始 message 可能含 Rust 内部路径，**不要原样透给用户可见文案**，
 * 只用于日志/调试页。
 */
@Serializable
data class NativeError(
    val kind: String,
    val message: String? = null,
)

/**
 * 每一个 native 方法返回值的统一信封。
 *
 * 这是若干返回形态的并集（契约 §3 只有 `ok`/`error`/`events`；§4 另有 `abi` 等；
 * §5 有 `handle`；§8 有 `operation`；§9 有 `snapshot`），故除 `ok` 外全部可空。
 *
 * `events` 即使 `ok=false` 也存在（可能为空数组）——契约 §3 明说出错前的事件不能丢。
 * 对 §4 的探针返回（示例里没有 `events` 键）这里给空列表兜底。
 */
@Serializable
data class NativeEnvelope(
    val ok: Boolean,
    val events: List<JsonElement> = emptyList(),
    val error: NativeError? = null,
    /** `nativeCreate` 返回的 u64 handle。 */
    val handle: Long? = null,
    /** `nativeCall` 返回的挂起操作令牌；`null` 表示该命令不产生挂起操作。 */
    val operation: String? = null,
    /** `nativeQuery` 返回的快照。 */
    val snapshot: JsonElement? = null,
    /** `nativeAbiVersion` 返回的 ABI 版本号。 */
    val abi: Int? = null,
    /** `nativeAbiVersion` 返回的协议版本串。 */
    val protocolVersion: String? = null,
    /** `nativeAbiVersion` 返回的可选能力列表（当前为空占位）。 */
    val features: List<String>? = null,
)

/** 解析信封用的 Json 实例：容忍未知键，缺键走默认值。 */
internal val envelopeJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/** 解析一条信封；返回 `null` 表示 native 返回的不是合法信封（属协议级故障）。 */
internal fun decodeEnvelope(raw: String): NativeEnvelope? =
    runCatching { envelopeJson.decodeFromString<NativeEnvelope>(raw) }.getOrNull()

// ---------------------------------------------------------------------------
// relay 状态（契约 §7.2）
// ---------------------------------------------------------------------------

/** `relay_state` 的 9 个取值，顺序与 `RelayState`（Rust）一致。 */
enum class RelayState(val wire: String) {
    Idle("idle"),
    Connecting("connecting"),
    Authenticating("authenticating"),
    Waiting("waiting"),
    Paired("paired"),
    Reconnecting("reconnecting"),
    Error("error"),
    Kicked("kicked"),
    Closed("closed"),
    ;

    companion object {
        /** 线上字符串 → 枚举；未知取值返回 `null`（由调用方决定是否降级）。 */
        fun fromWire(value: String): RelayState? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * relay 定时器槽位（契约 §7.2）。
 *
 * **与门面 token（[Long]）是两套独立命名空间**：本枚举每个槽位同一时刻只允许一个定时器；
 * 门面 token 由核心自增，二者不可混用。
 */
enum class RelayTimerSlot(val wire: String) {
    Heartbeat("heartbeat"),
    Waiting("waiting"),
    Reconnect("reconnect"),
    Rewait("rewait"),
    ;

    companion object {
        fun fromWire(value: String): RelayTimerSlot? = entries.firstOrNull { it.wire == value }
    }
}

// ---------------------------------------------------------------------------
// 完成形态（契约 §7.5）
// ---------------------------------------------------------------------------

/** `completed.outcome`。 */
sealed interface CompletionOutcome {
    /** `{"kind":"payload","value":{...}}`。 */
    data class Payload(val value: JsonElement) : CompletionOutcome

    /** `{"kind":"bridge_opened","session":1}`。 */
    data class BridgeOpened(val session: Long) : CompletionOutcome

    /** `{"kind":"ok"}`。 */
    data object Ok : CompletionOutcome

    /** `{"kind":"failed","error":{...}}`。 */
    data class Failed(val error: NativeError) : CompletionOutcome

    /** 未知 `kind`（契约变更时的降级出口，不抛异常）。 */
    data class Unknown(val kind: String, val raw: JsonElement) : CompletionOutcome
}

// ---------------------------------------------------------------------------
// 通道事件（契约 §7.4）
// ---------------------------------------------------------------------------

/** `ChannelRpcError`：`message` 为文本，`data` 原样透传。 */
data class ChannelRpcError(val message: String, val data: JsonElement?)

/** `channel.event` 的变体集合，与 `channel_client.rs` 的 `ChannelEvent` 一一对应。 */
sealed interface ChannelEvent {
    /** `{"type":"promise_success","id":1,"data":{...}}`。 */
    data class PromiseSuccess(val id: Long, val data: JsonElement) : ChannelEvent

    /** `{"type":"promise_error","id":1,"error":{...}}`。 */
    data class PromiseError(val id: Long, val error: ChannelRpcError) : ChannelEvent

    /** `{"type":"promise_error_obj","id":1,"error":{...}}`。 */
    data class PromiseErrorObj(val id: Long, val error: ChannelRpcError) : ChannelEvent

    /** `{"type":"event_fire","id":1,"data":{...}}`。 */
    data class EventFire(val id: Long, val data: JsonElement) : ChannelEvent

    /** 未知变体（降级出口）。 */
    data class Unknown(val type: String, val raw: JsonElement) : ChannelEvent
}

// ---------------------------------------------------------------------------
// 输出事件（契约 §7）
// ---------------------------------------------------------------------------

/** `step` / `call` / `create` / `dispose` 返回的 `events` 数组里的一条。 */
sealed interface EngineEvent {

    // ---- §7.1 传输动作（宿主必须执行）----

    /** `{"type":"ws_connect","generation":1,"uri":"wss://..."}`。 */
    data class WsConnect(val generation: Long, val uri: String) : EngineEvent

    /** `{"type":"ws_send_text","generation":1,"text":"<完整 JSON 文本>"}`。**不要重新序列化**。 */
    data class WsSendText(val generation: Long, val text: String) : EngineEvent

    /** `{"type":"ws_close","generation":1,"code":3000,"reason":"debug-drop"}`；`code`/`reason` 可空。 */
    data class WsClose(val generation: Long, val code: Int?, val reason: String?) : EngineEvent

    // ---- §7.2 relay 层 ----

    /** `{"type":"relay_state","state":"paired"}`。 */
    data class RelayStateChanged(val state: RelayState) : EngineEvent

    /** `{"type":"relay_log","line":"..."}`。 */
    data class RelayLog(val line: String) : EngineEvent

    /** `{"type":"relay_payload","payload":{...}}`。 */
    data class RelayPayload(val payload: JsonElement) : EngineEvent

    /** `{"type":"relay_failure","reason":"...","message":null}`。 */
    data class RelayFailure(val reason: String, val message: String?) : EngineEvent

    /** `{"type":"relay_timer_start","timer":"heartbeat","delayMs":10000,"repeat":true}`。 */
    data class RelayTimerStart(
        val timer: RelayTimerSlot,
        val delayMs: Long,
        val repeat: Boolean,
    ) : EngineEvent

    /** `{"type":"relay_timer_cancel","timer":"heartbeat"}`（幂等）。 */
    data class RelayTimerCancel(val timer: RelayTimerSlot) : EngineEvent

    // ---- §7.3 门面层 ----

    /** `{"type":"log","line":"..."}`。 */
    data class Log(val line: String) : EngineEvent

    /** `{"type":"timer_start","token":7,"delayMs":30000}`。 */
    data class TimerStart(val token: Long, val delayMs: Long) : EngineEvent

    /** `{"type":"timer_cancel","token":7}`（幂等）。 */
    data class TimerCancel(val token: Long) : EngineEvent

    /** `{"type":"workspace_list_updated","value":{...}}`。 */
    data class WorkspaceListUpdated(val value: JsonElement) : EngineEvent

    /** `{"type":"bridge_degraded","session":1,"degraded":"..."}`；`degraded=null` 表示已恢复。 */
    data class BridgeDegraded(val session: Long, val degraded: String?) : EngineEvent

    /**
     * `{"type":"app_error","reason":"desktop-disconnected","error":"..."}`。
     *
     * 对端主动报错。对端在主动下线远控前会发 `desktop-disconnected`；网页端收到后直接
     * dispose。`reason` 的 5 个取值：`session-conflict` / `workspace-closed` /
     * `desktop-disconnected` / `unsupported-action` / `unexpected-error`。
     */
    data class AppError(val reason: String?, val error: String?) : EngineEvent

    /** `{"type":"bridge_recovered","session":1,"count":2}`。 */
    data class BridgeRecovered(val session: Long, val count: Int) : EngineEvent

    /** `{"type":"bridge_stack_attached","session":1}`。 */
    data class BridgeStackAttached(val session: Long) : EngineEvent

    /** `{"type":"bridge_stack_disposed","session":1}`。 */
    data class BridgeStackDisposed(val session: Long) : EngineEvent

    /** `{"type":"conversation_opened","session":1,"key":"...","scope":{...}}`。 */
    data class ConversationOpened(
        val session: Long,
        val key: String,
        val scope: JsonElement,
    ) : EngineEvent

    /** `{"type":"channel","event":{...}}`。 */
    data class Channel(val event: ChannelEvent) : EngineEvent

    /** `{"type":"conversation_event","session":1,"key":"...","event":{...}}`（契约 §13.4）。 */
    data class ConversationEvent(
        val session: Long,
        val key: String,
        val event: JsonElement,
    ) : EngineEvent

    /** `{"type":"completed","token":"...","outcome":{...}}`。 */
    data class Completed(val token: String, val outcome: CompletionOutcome) : EngineEvent

    /** 未知 `type`：降级出口。事件流不因契约新增变体而断掉。 */
    data class Unknown(val type: String, val raw: JsonElement) : EngineEvent
}

// ---------------------------------------------------------------------------
// 引擎状态
// ---------------------------------------------------------------------------

/** 引擎自身生命周期（与 relay 状态 [RelayState] 无关）。 */
sealed interface EngineState {
    /** 尚未 `start`。 */
    data object Idle : EngineState

    /** 正在核对 ABI / 建会话。 */
    data object Starting : EngineState

    /** 会话已建立，`handle` 可用。 */
    data class Running(val handle: Long) : EngineState

    /**
     * 不可恢复：ABI 不匹配、`create` 失败、或 Rust 侧 panic（契约 §4 / §12）。
     *
     * 进入 Fatal 后所有 `bridge.*` 调用停止，但**进程不崩**；UI 应显示 `reason` 并引导重启。
     * `detail` 是给调试页/日志的原文（可能含 Rust 内部路径），不要直接当用户文案。
     */
    data class Fatal(val reason: String, val detail: String? = null) : EngineState

    /** 已 `dispose`。 */
    data object Disposed : EngineState
}

// ---------------------------------------------------------------------------
// 输入构造
// ---------------------------------------------------------------------------

/**
 * `nativeCreate` 的配置（契约 §5）。
 *
 * `url` 必填；其余可空。`nowMs` 由引擎在调用时用 `clock()` 注入（契约：时间一律由宿主传入）。
 * 不省略键——缺省字段序列化成 `null`。
 */
data class EngineConfig(
    val url: String,
    val isWeb: Boolean = false,
    val targetPlatform: String = "android",
    val appVersion: String? = null,
    val deviceName: String? = null,
    val theme: String? = null,
    val mid: String? = null,
    /**
     * ws2 的 **terminal token**，不是 OAuth JWT。
     *
     * 3.16.1 宿主先用 OAuth JWT 调 `POST /api/v1/remote/token`（body `{"role":"terminal"}`）
     * 换一个 22 字符短 token，再放进 `wss://<host>/api/v1/remote/ws2?token=…`。
     * 旧链接（带 `hash`）传 `null`，继续走 HMAC proof。
     *
     * **绝不落盘、绝不进日志**：只存在于本次 `nativeCreate` 的配置里。
     */
    val token: String? = null,
) {
    /** 序列化成 `nativeCreate` 的入参（含 `nowMs`）。 */
    fun toJson(nowMs: Long): JsonObject = buildJsonObject {
        put("url", url)
        put("nowMs", nowMs)
        put("isWeb", isWeb)
        put("targetPlatform", targetPlatform)
        put("appVersion", appVersion.orNull())
        put("deviceName", deviceName.orNull())
        put("theme", theme.orNull())
        put("mid", mid.orNull())
        put("token", token.orNull())
    }
}

private fun String?.orNull(): JsonElement = if (this == null) JsonNull else JsonPrimitive(this)

// ---------------------------------------------------------------------------
// 事件解析（契约 §7）
// ---------------------------------------------------------------------------

/**
 * `events` 数组里的一条 JSON → [EngineEvent]。
 *
 * 未知 `type` 一律降级成 [EngineEvent.Unknown] 而不是抛异常：事件流不能因为契约新增变体
 * （或 Rust 侧多吐了一条）就整条断掉。契约 §4 承诺改 schema 会 +1 `abi`，启动时的 ABI 核对
 * 已经挡住真正的错位，这里的降级只是第二道保险。
 */
internal object EventParser {

    fun parse(raw: JsonElement): EngineEvent {
        val obj = raw as? JsonObject ?: return EngineEvent.Unknown("", raw)
        val type = obj.str("type") ?: return EngineEvent.Unknown("", raw)
        return when (type) {
            // §7.1 传输动作
            "ws_connect" -> EngineEvent.WsConnect(
                generation = obj.long("generation"),
                uri = obj.stringOrEmpty("uri"),
            )

            "ws_send_text" -> EngineEvent.WsSendText(
                generation = obj.long("generation"),
                text = obj.stringOrEmpty("text"),
            )

            "ws_close" -> EngineEvent.WsClose(
                generation = obj.long("generation"),
                code = obj.int("code"),
                reason = obj.str("reason"),
            )

            // §7.2 relay 层
            "relay_state" -> {
                val wire = obj.stringOrEmpty("state")
                RelayState.fromWire(wire)
                    ?.let { EngineEvent.RelayStateChanged(it) }
                    ?: EngineEvent.Unknown(type, raw)
            }

            "relay_log" -> EngineEvent.RelayLog(obj.stringOrEmpty("line"))

            "relay_payload" -> EngineEvent.RelayPayload(obj.element("payload") ?: JsonNull)

            "relay_failure" -> EngineEvent.RelayFailure(
                reason = obj.stringOrEmpty("reason"),
                message = obj.str("message"),
            )

            "relay_timer_start" -> {
                val wire = obj.stringOrEmpty("timer")
                RelayTimerSlot.fromWire(wire)?.let {
                    EngineEvent.RelayTimerStart(
                        timer = it,
                        delayMs = obj.long("delayMs"),
                        repeat = obj.bool("repeat"),
                    )
                } ?: EngineEvent.Unknown(type, raw)
            }

            "relay_timer_cancel" -> {
                RelayTimerSlot.fromWire(obj.stringOrEmpty("timer"))
                    ?.let { EngineEvent.RelayTimerCancel(it) }
                    ?: EngineEvent.Unknown(type, raw)
            }

            // §7.3 门面层
            "log" -> EngineEvent.Log(obj.stringOrEmpty("line"))

            "timer_start" -> EngineEvent.TimerStart(
                token = obj.long("token"),
                delayMs = obj.long("delayMs"),
            )

            "timer_cancel" -> EngineEvent.TimerCancel(obj.long("token"))

            "workspace_list_updated" -> EngineEvent.WorkspaceListUpdated(
                obj.element("value") ?: JsonNull,
            )

            "bridge_degraded" -> EngineEvent.BridgeDegraded(
                session = obj.long("session"),
                degraded = obj.str("degraded"),
            )

            "app_error" -> EngineEvent.AppError(
                reason = obj.str("reason"),
                error = obj.str("error"),
            )

            "bridge_recovered" -> EngineEvent.BridgeRecovered(
                session = obj.long("session"),
                count = obj.int("count") ?: 0,
            )

            "bridge_stack_attached" -> EngineEvent.BridgeStackAttached(obj.long("session"))

            "bridge_stack_disposed" -> EngineEvent.BridgeStackDisposed(obj.long("session"))

            "conversation_opened" -> EngineEvent.ConversationOpened(
                session = obj.long("session"),
                key = obj.stringOrEmpty("key"),
                scope = obj.element("scope") ?: JsonNull,
            )

            "channel" -> EngineEvent.Channel(parseChannelEvent(obj.element("event")))

            "conversation_event" -> EngineEvent.ConversationEvent(
                session = obj.long("session"),
                key = obj.stringOrEmpty("key"),
                event = obj.element("event") ?: JsonNull,
            )

            "completed" -> EngineEvent.Completed(
                token = obj.stringOrEmpty("token"),
                outcome = parseOutcome(obj.element("outcome")),
            )

            else -> EngineEvent.Unknown(type, raw)
        }
    }

    /** §7.4 `channel.event`。 */
    fun parseChannelEvent(raw: JsonElement?): ChannelEvent {
        val obj = raw as? JsonObject ?: return ChannelEvent.Unknown("", raw ?: JsonNull)
        val type = obj.str("type") ?: ""
        return when (type) {
            "promise_success" -> ChannelEvent.PromiseSuccess(
                id = obj.long("id"),
                data = obj.element("data") ?: JsonNull,
            )

            "promise_error" -> ChannelEvent.PromiseError(
                id = obj.long("id"),
                error = parseRpcError(obj.obj("error")),
            )

            "promise_error_obj" -> ChannelEvent.PromiseErrorObj(
                id = obj.long("id"),
                error = parseRpcError(obj.obj("error")),
            )

            "event_fire" -> ChannelEvent.EventFire(
                id = obj.long("id"),
                data = obj.element("data") ?: JsonNull,
            )

            else -> ChannelEvent.Unknown(type, raw)
        }
    }

    /** §7.5 `completed.outcome`。 */
    fun parseOutcome(raw: JsonElement?): CompletionOutcome {
        val obj = raw as? JsonObject
            ?: return CompletionOutcome.Unknown("", raw ?: JsonNull)
        val kind = obj.str("kind") ?: ""
        return when (kind) {
            "payload" -> CompletionOutcome.Payload(obj.element("value") ?: JsonNull)
            "bridge_opened" -> CompletionOutcome.BridgeOpened(obj.long("session"))
            "ok" -> CompletionOutcome.Ok
            "failed" -> CompletionOutcome.Failed(parseError(obj.obj("error")))
            else -> CompletionOutcome.Unknown(kind, raw)
        }
    }

    /** `error` 对象（信封 §3 与 `Completion::Failed` 共用同一形状）。 */
    fun parseError(obj: JsonObject?): NativeError = NativeError(
        kind = obj?.str("kind") ?: "protocol",
        message = obj?.str("message"),
    )

    private fun parseRpcError(obj: JsonObject?): ChannelRpcError = ChannelRpcError(
        message = obj?.str("message") ?: "",
        data = obj?.element("data"),
    )
}

// ---------------------------------------------------------------------------
// 回喂输入构造（契约 §6）
// ---------------------------------------------------------------------------

/**
 * socket 回调 → `nativeStep` 的输入 JSON（契约 §6 的表）。
 *
 * 抽成纯函数是为了能在 JVM 单测里逐字段核对——这部分是宿主与核心之间唯一的「回喂」通道，
 * 一个字段名写错就会让核心按错误代次/错误码处理。
 */
/**
 * ws URL 的纯函数改写。
 *
 * 3.16.1 的重连会换一张新的 terminal token，但 URL 里还有 `mid` 等参数，不能整串重建。
 * token 字符集是 `[A-Za-z0-9_-]`（22 字符），无需再编码。
 */
internal object RelayUri {

    private val tokenParam = Regex("([?&])token=[^&]*")

    /** 把 `token` 查询参数替换成 [token]；URL 里没有 `token` 时原样返回。 */
    fun withToken(uri: String, token: String): String {
        if (!tokenParam.containsMatchIn(uri)) return uri
        return tokenParam.replaceFirst(uri, "$1token=$token")
    }
}

internal object SocketInputs {

    fun ready(generation: Long): JsonObject = buildJsonObject {
        put("type", "socket_ready")
        put("generation", generation)
    }

    fun textFrame(generation: Long, text: String): JsonObject = buildJsonObject {
        put("type", "text_frame")
        put("generation", generation)
        put("text", text)
    }

    /** 二进制帧按契约 §6 走 **base64**，宿主不预解码（核心要 base64 文本）。 */
    fun binaryFrame(generation: Long, base64: String): JsonObject = buildJsonObject {
        put("type", "binary_frame")
        put("generation", generation)
        put("base64", base64)
    }

    fun failed(generation: Long, error: String): JsonObject = buildJsonObject {
        put("type", "socket_failed")
        put("generation", generation)
        put("error", error)
    }

    /** `reason` 为 `null` 时**必须**写 `null`（契约 §3：键不省）。 */
    fun closed(generation: Long, code: Int, reason: String?): JsonObject = buildJsonObject {
        put("type", "socket_closed")
        put("generation", generation)
        put("code", code)
        put("reason", reason?.let { JsonPrimitive(it) } ?: JsonNull)
    }
}

/** 定时器到点 → `nativeStep` 的输入 JSON（契约 §6）。 */
internal object TimerInputs {

    /** relay 槽位到点：`{"type":"relay_timer_fired","timer":"heartbeat"}`。 */
    fun relayFired(slot: RelayTimerSlot): JsonObject = buildJsonObject {
        put("type", "relay_timer_fired")
        put("timer", slot.wire)
    }

    /** 门面 token 到点：`{"type":"timer_fired","token":N}`。 */
    fun facadeFired(token: Long): JsonObject = buildJsonObject {
        put("type", "timer_fired")
        put("token", token)
    }

    /** 回前台探活：`{"type":"poke"}`。 */
    fun poke(): JsonObject = buildJsonObject { put("type", "poke") }

    /** 开始连接：`{"type":"start"}`。 */
    fun start(): JsonObject = buildJsonObject { put("type", "start") }
}

// ---------------------------------------------------------------------------
// 内部 JSON 小工具
// ---------------------------------------------------------------------------

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.long(key: String): Long =
    (this[key] as? JsonPrimitive)?.longOrNull ?: 0L

internal fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.bool(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.element(key: String): JsonElement? = this[key]

internal fun JsonObject.stringOrEmpty(key: String): String =
    (this[key] as? JsonPrimitive)?.content ?: ""
