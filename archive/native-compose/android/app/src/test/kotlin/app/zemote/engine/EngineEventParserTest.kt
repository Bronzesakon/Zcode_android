package app.zemote.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 契约 §7 全部事件类型的解析（表驱动）。
 *
 * 这张表就是「Kotlin 侧理解的协议面」的清单——Rust 侧一旦改了某个 `type` 名或字段名，
 * 这里会先红，而不是等到 UI 上出现空白。
 */
class EngineEventParserTest {

    private fun parse(json: String): EngineEvent =
        EventParser.parse(Json.parseToJsonElement(json))

    @Test
    fun parsesEveryKnownEventType() {
        val payload = o("zcode_type" to s("bootstrap-response"), "requestId" to s("r1"))

        val cases: List<Pair<String, EngineEvent>> = listOf(
            // ---- §7.1 传输动作 ----
            j("ws_connect", "generation" to n(1), "uri" to s("wss://zcode.z.ai/ws?mid=abc")) to
                EngineEvent.WsConnect(1, "wss://zcode.z.ai/ws?mid=abc"),
            j("ws_send_text", "generation" to n(2), "text" to s("""{"type":"auth_init"}""")) to
                EngineEvent.WsSendText(2, """{"type":"auth_init"}"""),
            j(
                "ws_close",
                "generation" to n(3),
                "code" to n(3000),
                "reason" to s("debug-drop"),
            ) to EngineEvent.WsClose(3, 3000, "debug-drop"),
            // code/reason 可为 null（对应 Dart 的无参 sink.close()），键不省
            j(
                "ws_close",
                "generation" to n(3),
                "code" to JsonNull,
                "reason" to JsonNull,
            ) to EngineEvent.WsClose(3, null, null),

            // ---- §7.2 relay 层 ----
            j("relay_state", "state" to s("paired")) to
                EngineEvent.RelayStateChanged(RelayState.Paired),
            j("relay_log", "line" to s("[relay] state -> RelayState.paired")) to
                EngineEvent.RelayLog("[relay] state -> RelayState.paired"),
            j("relay_payload", "payload" to payload) to EngineEvent.RelayPayload(payload),
            j("relay_failure", "reason" to s("session-conflict"), "message" to JsonNull) to
                EngineEvent.RelayFailure("session-conflict", null),
            j(
                "relay_timer_start",
                "timer" to s("heartbeat"),
                "delayMs" to n(10_000),
                "repeat" to b(true),
            ) to EngineEvent.RelayTimerStart(RelayTimerSlot.Heartbeat, 10_000, true),
            j(
                "relay_timer_start",
                "timer" to s("reconnect"),
                "delayMs" to n(3_000),
                "repeat" to b(false),
            ) to EngineEvent.RelayTimerStart(RelayTimerSlot.Reconnect, 3_000, false),
            j("relay_timer_cancel", "timer" to s("heartbeat")) to
                EngineEvent.RelayTimerCancel(RelayTimerSlot.Heartbeat),

            // ---- §7.3 门面层 ----
            j("log", "line" to s("[zemote] bootstrap")) to EngineEvent.Log("[zemote] bootstrap"),
            j("timer_start", "token" to n(7), "delayMs" to n(30_000)) to
                EngineEvent.TimerStart(7, 30_000),
            j("timer_cancel", "token" to n(7)) to EngineEvent.TimerCancel(7),
            j("workspace_list_updated", "value" to payload) to
                EngineEvent.WorkspaceListUpdated(payload),
            j("bridge_degraded", "session" to n(1), "degraded" to s("reopen-failed: timeout")) to
                EngineEvent.BridgeDegraded(1, "reopen-failed: timeout"),
            // degraded 为 null 表示已恢复健康
            j("bridge_degraded", "session" to n(1), "degraded" to JsonNull) to
                EngineEvent.BridgeDegraded(1, null),
            j(
                "app_error",
                "reason" to s("desktop-disconnected"),
                "error" to s("bye"),
            ) to EngineEvent.AppError("desktop-disconnected", "bye"),
            // 两个字段都可缺席（对端 schema 里都是 optional）
            j("app_error") to EngineEvent.AppError(null, null),
            j("bridge_recovered", "session" to n(1), "count" to n(2)) to
                EngineEvent.BridgeRecovered(1, 2),
            j("bridge_stack_attached", "session" to n(1)) to EngineEvent.BridgeStackAttached(1),
            j("bridge_stack_disposed", "session" to n(1)) to EngineEvent.BridgeStackDisposed(1),
            j(
                "conversation_opened",
                "session" to n(1),
                "key" to s("conv:1"),
                "scope" to payload,
            ) to EngineEvent.ConversationOpened(1, "conv:1", payload),

            // ---- §7.4 channel.event ----
            j("channel", "event" to je("promise_success", "id" to n(1), "data" to payload)) to
                EngineEvent.Channel(ChannelEvent.PromiseSuccess(1, payload)),
            j(
                "channel",
                "event" to je(
                    "promise_error",
                    "id" to n(2),
                    "error" to o("message" to s("nope"), "data" to payload),
                ),
            ) to EngineEvent.Channel(
                ChannelEvent.PromiseError(2, ChannelRpcError("nope", payload)),
            ),
            j(
                "channel",
                "event" to je(
                    "promise_error_obj",
                    "id" to n(3),
                    "error" to o("message" to s("{code: 7}"), "data" to payload),
                ),
            ) to EngineEvent.Channel(
                ChannelEvent.PromiseErrorObj(3, ChannelRpcError("{code: 7}", payload)),
            ),
            j("channel", "event" to je("event_fire", "id" to n(4), "data" to payload)) to
                EngineEvent.Channel(ChannelEvent.EventFire(4, payload)),

            // ---- §13.4 对话事件 ----
            j(
                "conversation_event",
                "session" to n(1),
                "key" to s("conv:1"),
                "event" to o("kind" to s("snapshot")),
            ) to EngineEvent.ConversationEvent(1, "conv:1", o("kind" to s("snapshot"))),

            // ---- §7.5 completed ----
            j(
                "completed",
                "token" to s("req-1"),
                "outcome" to o("kind" to s("payload"), "value" to payload),
            ) to EngineEvent.Completed("req-1", CompletionOutcome.Payload(payload)),
            j(
                "completed",
                "token" to s("req-2"),
                "outcome" to o("kind" to s("bridge_opened"), "session" to n(9)),
            ) to EngineEvent.Completed("req-2", CompletionOutcome.BridgeOpened(9)),
            j(
                "completed",
                "token" to s("pair:3"),
                "outcome" to o("kind" to s("ok")),
            ) to EngineEvent.Completed("pair:3", CompletionOutcome.Ok),
            j(
                "completed",
                "token" to s("req-4"),
                "outcome" to o(
                    "kind" to s("failed"),
                    "error" to o("kind" to s("timeout"), "message" to s("timed out")),
                ),
            ) to EngineEvent.Completed(
                "req-4",
                CompletionOutcome.Failed(NativeError("timeout", "timed out")),
            ),
        )

        cases.forEach { (raw, expected) ->
            assertEquals("事件解析不符：$raw", expected, parse(raw))
        }
    }

    @Test
    fun parsesAllRelayStates() {
        val expected = mapOf(
            "idle" to RelayState.Idle,
            "connecting" to RelayState.Connecting,
            "authenticating" to RelayState.Authenticating,
            "waiting" to RelayState.Waiting,
            "paired" to RelayState.Paired,
            "reconnecting" to RelayState.Reconnecting,
            "error" to RelayState.Error,
            "kicked" to RelayState.Kicked,
            "closed" to RelayState.Closed,
        )
        expected.forEach { (wire, state) ->
            assertEquals(
                EngineEvent.RelayStateChanged(state),
                parse(j("relay_state", "state" to s(wire))),
            )
        }
    }

    @Test
    fun parsesAllRelayTimerSlots() {
        val slots = mapOf(
            "heartbeat" to RelayTimerSlot.Heartbeat,
            "waiting" to RelayTimerSlot.Waiting,
            "reconnect" to RelayTimerSlot.Reconnect,
            "rewait" to RelayTimerSlot.Rewait,
        )
        slots.forEach { (wire, slot) ->
            assertEquals(
                EngineEvent.RelayTimerCancel(slot),
                parse(j("relay_timer_cancel", "timer" to s(wire))),
            )
        }
    }

    @Test
    fun unknownTypesDegradeInsteadOfThrowing() {
        val cases = listOf(
            j("brand_new_event", "x" to n(1)),
            j("relay_state", "state" to s("teleported")),
            j("relay_timer_start", "timer" to s("nope"), "delayMs" to n(1), "repeat" to b(false)),
        )
        cases.forEach { raw ->
            assertTrue("应降级为 Unknown：$raw", parse(raw) is EngineEvent.Unknown)
        }

        // channel 的未知变体同样降级，不影响外层 channel 事件
        val channel = parse(
            j("channel", "event" to je("brand_new_channel_event", "id" to n(1))),
        )
        val inner = (channel as EngineEvent.Channel).event
        assertTrue(inner is ChannelEvent.Unknown)

        // completed 的未知 kind
        val completed = parse(
            j("completed", "token" to s("t"), "outcome" to o("kind" to s("future_kind"))),
        )
        val outcome = (completed as EngineEvent.Completed).outcome
        assertTrue(outcome is CompletionOutcome.Unknown)
    }

    @Test
    fun nonObjectEventsDegrade() {
        assertTrue(EventParser.parse(JsonPrimitive("oops")) is EngineEvent.Unknown)
        assertTrue(EventParser.parse(JsonNull) is EngineEvent.Unknown)
    }

    @Test
    fun envelopeParsing() {
        val full = decodeEnvelope(
            """{"ok":true,"handle":7,"operation":"bootstrap-abc","events":[]}""",
        )
        assertEquals(true, full?.ok)
        assertEquals(7L, full?.handle)
        assertEquals("bootstrap-abc", full?.operation)

        // 键不省：显式 null 与缺键都要能解析
        val err = decodeEnvelope(
            """{"ok":false,"error":{"kind":"panic","message":null},"events":[],"handle":null}""",
        )
        assertEquals(false, err?.ok)
        assertEquals("panic", err?.error?.kind)
        assertEquals(null, err?.error?.message)
        assertEquals(null, err?.handle)

        // ABI 探针返回（契约 §4 的示例里没有 events 键）
        val abi = decodeEnvelope(
            """{"ok":true,"abi":1,"protocolVersion":"0.1.0","features":["conversation"]}""",
        )
        assertEquals(1, abi?.abi)
        assertEquals(listOf("conversation"), abi?.features)
        assertEquals(emptyList<JsonElement>(), abi?.events)

        // 非 JSON / 非对象 → null（协议级故障，由引擎转成 Fatal 或 lastError）
        assertEquals(null, decodeEnvelope("not json at all"))
        assertEquals(null, decodeEnvelope("[1,2,3]"))
    }

    @Test
    fun configSerializationKeepsEveryKey() {
        val json = EngineConfig(
            url = "https://zcode.z.ai/remote/v4?sid=s&hash=h&t=1",
            isWeb = false,
            targetPlatform = "android",
            appVersion = "1.0.0",
            deviceName = "Pixel 8",
            theme = "dark",
        ).toJson(nowMs = 1_760_000_000_000L)

        assertEquals("https://zcode.z.ai/remote/v4?sid=s&hash=h&t=1", json["url"]!!.asString())
        assertEquals(1_760_000_000_000L, (json["nowMs"] as JsonPrimitive).content.toLong())
        assertEquals(false, (json["isWeb"] as JsonPrimitive).content.toBoolean())
        assertEquals("android", json["targetPlatform"]!!.asString())
        assertEquals("1.0.0", json["appVersion"]!!.asString())
        assertEquals("Pixel 8", json["deviceName"]!!.asString())
        assertEquals("dark", json["theme"]!!.asString())
        // mid 未给 → 键仍在，值为 null（契约 §3：键不省）
        assertTrue(json.containsKey("mid"))
        assertEquals(JsonNull, json["mid"])
    }

    // ----------------------------------------------------------------------
    // 小工具
    // ----------------------------------------------------------------------

    private fun s(value: String): JsonElement = JsonPrimitive(value)

    private fun n(value: Long): JsonElement = JsonPrimitive(value)

    private fun b(value: Boolean): JsonElement = JsonPrimitive(value)

    private fun o(vararg fields: Pair<String, JsonElement>): JsonObject =
        buildJsonObject { fields.forEach { (k, v) -> put(k, v) } }

    /** 与 [j] 同形，但返回对象本身——用于嵌套事件。 */
    private fun je(type: String, vararg fields: Pair<String, JsonElement>): JsonObject =
        buildJsonObject {
            put("type", type)
            fields.forEach { (k, v) -> put(k, v) }
        }

    private fun j(type: String, vararg fields: Pair<String, JsonElement>): String =
        je(type, *fields).toString()

    private fun JsonElement.asString(): String = (this as JsonPrimitive).content
}
