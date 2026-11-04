package app.zemote.engine

import app.zemote.core.NativeBridge
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * [NativeBridge] 的测试替身。
 *
 * JVM 单测加载不了 `libzemote_ffi.so`（[app.zemote.core.NativeCore] 的
 * `System.loadLibrary` 只对 arm64 Android 有效），引擎的全部逻辑靠本替身在宿主 JVM 上跑。
 *
 * 用法：把每个方法的返回信封改成脚本化的 lambda，然后断言 [invocations] 里收到了什么。
 * 所有集合都是并发安全的（虽然引擎按契约 §11 是单线程，替身不该成为断言噪声的来源）。
 */
class FakeNativeBridge : NativeBridge {

    /** 一次 native 调用的记录。 */
    data class Invocation(val method: String, val handle: Long?, val payload: String)

    val invocations = CopyOnWriteArrayList<Invocation>()

    /** `nativeAbiVersion` 的返回。默认是 ABI 2（与 [EXPECTED_ABI] 一致）。 */
    var abiVersionResponse: String =
        """{"ok":true,"abi":2,"protocolVersion":"0.1.0","features":[]}"""

    /** `nativeCreate` 的返回。 */
    var createResponse: String = """{"ok":true,"handle":1,"events":[]}"""

    /** `nativeQuery` 的返回。 */
    var queryResponse: String = """{"ok":true,"snapshot":{"disposed":false}}"""

    /** `nativeDispose` 的返回。 */
    var disposeResponse: String = """{"ok":true,"events":[]}"""

    /** `nativeCall` 的返回（入参是命令 JSON 文本）。 */
    var callHandler: (String) -> String = { envelopeOf(operation = null) }

    /** `nativeStep` 的返回（入参是输入 JSON 文本）。 */
    var stepHandler: (String) -> String = { envelopeOf() }

    /** 让 `nativeCall` 抛异常，验证引擎的防御路径。 */
    var throwOnCall: Boolean = false

    /** 让 `nativeStep` 抛异常，验证引擎的防御路径。 */
    var throwOnStep: Boolean = false

    override fun abiVersion(): String {
        invocations += Invocation("abiVersion", null, "")
        return abiVersionResponse
    }

    override fun create(configJson: String): String {
        invocations += Invocation("create", null, configJson)
        return createResponse
    }

    override fun step(handle: Long, inputJson: String, nowMs: Long): String {
        invocations += Invocation("step", handle, inputJson)
        if (throwOnStep) throw IllegalStateException("fake step failure")
        return stepHandler(inputJson)
    }

    override fun call(handle: Long, commandJson: String, nowMs: Long): String {
        invocations += Invocation("call", handle, commandJson)
        if (throwOnCall) throw IllegalStateException("fake call failure")
        return callHandler(commandJson)
    }

    override fun query(handle: Long, queryJson: String): String {
        invocations += Invocation("query", handle, queryJson)
        return queryResponse
    }

    override fun dispose(handle: Long, nowMs: Long): String {
        invocations += Invocation("dispose", handle, "")
        return disposeResponse
    }

    // ---- 断言辅助 ----

    /** 收到的全部 `nativeStep` 输入（原样 JSON 文本）。 */
    fun stepInputs(): List<String> = invocations.filter { it.method == "step" }.map { it.payload }

    /** 收到的全部 `nativeCall` 命令（解析后的 JSON 对象）。 */
    fun callCommands(): List<JsonObject> =
        invocations.filter { it.method == "call" }
            .map { Json.parseToJsonElement(it.payload) as JsonObject }

    fun methodCount(method: String): Int = invocations.count { it.method == method }
}

// ---------------------------------------------------------------------------
// 信封 / 事件构造（测试专用）
// ---------------------------------------------------------------------------

/** 造一个返回信封。`null` 的键一律省略（引擎的解析对缺键与显式 null 都容忍）。 */
internal fun envelopeOf(
    ok: Boolean = true,
    events: List<String> = emptyList(),
    error: String? = null,
    handle: Long? = null,
    operation: String? = null,
    snapshot: String? = null,
): String = buildJsonObject {
    put("ok", ok)
    put(
        "events",
        buildJsonArray { events.forEach { add(Json.parseToJsonElement(it)) } },
    )
    if (error != null) put("error", Json.parseToJsonElement(error))
    if (handle != null) put("handle", handle)
    if (operation != null) put("operation", JsonPrimitive(operation))
    if (snapshot != null) put("snapshot", Json.parseToJsonElement(snapshot))
}.toString()

/** `{"type":"completed","token":...,"outcome":...}`。 */
internal fun completedEvent(token: String, outcome: String): String =
    buildJsonObject {
        put("type", "completed")
        put("token", token)
        put("outcome", Json.parseToJsonElement(outcome))
    }.toString()

/** `{"kind":"payload","value":...}`。 */
internal fun payloadOutcome(value: String): String =
    """{"kind":"payload","value":$value}"""

/** `{"kind":"failed","error":{...}}`。 */
internal fun failedOutcome(kind: String, message: String = "boom"): String =
    """{"kind":"failed","error":{"kind":"$kind","message":"$message"}}"""

/** 把 JsonElement 写成紧凑文本，便于与 [FakeNativeBridge.stepInputs] 比对。 */
internal fun JsonElement.compact(): String = toString()
