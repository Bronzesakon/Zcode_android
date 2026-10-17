package app.zemote.engine

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「宿主 → 核心」回喂输入（契约 §6）的逐字段核对。
 *
 * 这是宿主与核心之间唯一的回喂通道：字段名写错、`generation` 带错、`null` 被省略成缺键，
 * 都会让核心按错误的代次/错误的码处理，而且**不会报错**——只会静默走错分支。
 */
class SocketInputsTest {

    @Test
    fun socketReady() {
        assertEquals("""{"type":"socket_ready","generation":3}""", SocketInputs.ready(3).toString())
    }

    @Test
    fun textFrameCarriesRawTextVerbatim() {
        val raw = """{"type":"auth_challenge","nonce":"abc-123"}"""
        val obj = SocketInputs.textFrame(2, raw)

        assertEquals(setOf("type", "generation", "text"), obj.keys)
        assertEquals("text_frame", obj["type"]!!.jsonPrimitive.content)
        assertEquals(2L, obj["generation"]!!.jsonPrimitive.long)
        // 原样透传：核心要自己解析这段文本
        assertEquals(raw, obj["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun binaryFrameCarriesBase64NotDecodedBytes() {
        // 契约 §6：宿主不要预解码，核心要 base64 文本
        val obj = SocketInputs.binaryFrame(6, encodeBase64(byteArrayOf(1, 2, 3)))

        assertEquals(setOf("type", "generation", "base64"), obj.keys)
        assertEquals("binary_frame", obj["type"]!!.jsonPrimitive.content)
        assertEquals(6L, obj["generation"]!!.jsonPrimitive.long)
        assertEquals("AQID", obj["base64"]!!.jsonPrimitive.content)
    }

    @Test
    fun socketFailedUsesThrowableToString() {
        assertEquals(
            """{"type":"socket_failed","generation":4,"error":"java.net.ConnectException: refused"}""",
            SocketInputs.failed(4, "java.net.ConnectException: refused").toString(),
        )
    }

    @Test
    fun socketClosedKeepsNullKeys() {
        // 契约 §3：键永不省略，null 是合法值
        assertEquals(
            """{"type":"socket_closed","generation":5,"code":1006,"reason":null}""",
            SocketInputs.closed(5, 1006, null).toString(),
        )
        assertEquals(
            """{"type":"socket_closed","generation":5,"code":3000,"reason":"debug-drop"}""",
            SocketInputs.closed(5, 3000, "debug-drop").toString(),
        )

        val obj = SocketInputs.closed(5, 1006, null)
        assertSame(JsonNull, obj["reason"])
        assertTrue(obj.containsKey("reason"))
    }

    @Test
    fun timerInputs() {
        assertEquals(
            """{"type":"relay_timer_fired","timer":"heartbeat"}""",
            TimerInputs.relayFired(RelayTimerSlot.Heartbeat).toString(),
        )
        assertEquals(
            """{"type":"relay_timer_fired","timer":"rewait"}""",
            TimerInputs.relayFired(RelayTimerSlot.Rewait).toString(),
        )
        // 门面 token 与 relay 槽位是两套命名空间，输入形态也不同
        assertEquals("""{"type":"timer_fired","token":7}""", TimerInputs.facadeFired(7).toString())
        assertEquals("""{"type":"start"}""", TimerInputs.start().toString())
        assertEquals("""{"type":"poke"}""", TimerInputs.poke().toString())
    }

    @Test
    fun base64IsStandardAlphabetNotUrlSafe() {
        // 0xFB 0xFF 0xBF → 62/63/62/63 → "+/+/"（url-safe 会给出 "-_-_"）
        assertEquals("+/+/", encodeBase64(byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0xBF.toByte())))
    }

    @Test
    fun closeCodeSanitizing() {
        // 契约 §7.1：code 可为 null（对应 Dart 的无参 sink.close()）→ 1000
        assertEquals(1000, sanitizeCloseCode(null))
        assertEquals(1000, sanitizeCloseCode(1000))
        // 3000..4999 是应用自定义码，debug-drop 走的就是 3000
        assertEquals(3000, sanitizeCloseCode(3000))
        assertEquals(4999, sanitizeCloseCode(4999))
        // OkHttp 只接受 1000 与 3000..4999，其它（含 1006 异常关闭）会抛
        assertEquals(1000, sanitizeCloseCode(1006))
        assertEquals(1000, sanitizeCloseCode(1001))
        assertEquals(1000, sanitizeCloseCode(5000))
        assertEquals(1000, sanitizeCloseCode(-1))
    }

    @Test
    fun configTargetPlatformDefaultsToAndroid() {
        val json = EngineConfig(url = "https://example.invalid/remote/v4?sid=s&hash=h&t=1")
            .toJson(nowMs = 1L)
        assertEquals("android", json["targetPlatform"]!!.jsonPrimitive.content)
        assertEquals(false, json["isWeb"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(JsonNull, json["appVersion"])
        assertEquals(JsonNull, json["deviceName"])
        assertEquals(JsonNull, json["theme"])
        assertEquals(JsonNull, json["mid"])
    }

    @Test
    fun configRoundTripsThroughJson() {
        val json = EngineConfig(url = "u", appVersion = "1.0.0").toJson(nowMs = 5L)
        assertEquals(JsonPrimitive("u"), json["url"])
        assertEquals(JsonPrimitive(5L), json["nowMs"])
    }
}
