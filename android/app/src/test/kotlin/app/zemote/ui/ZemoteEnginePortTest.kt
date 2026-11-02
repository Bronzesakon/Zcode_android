package app.zemote.ui

import app.zemote.auth.RemoteTokenException
import app.zemote.engine.EngineException
import app.zemote.engine.EngineState
import app.zemote.engine.FakeNativeBridge
import app.zemote.engine.ZemoteEngine
import app.zemote.engine.completedEvent
import app.zemote.engine.envelopeOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

/**
 * [ZemoteEnginePort]：引擎实例的**替换**逻辑。
 *
 * 用真的 [ZemoteEngine] + [FakeNativeBridge]（引擎层自己的测试替身）+ 测试 dispatcher：
 * 这段逻辑的核心就是「拆旧建新」与「转接不断」，用假引擎测反而测不到。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ZemoteEnginePortTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val urlA = "https://zcode.z.ai/remote/v4?sid=a&hash=ha&t=1"
    private val urlB = "https://zcode.z.ai/remote/v4?sid=b&hash=hb&t=2"

    private var theme = "dark"

    private fun TestScope.newPort(
        bridge: FakeNativeBridge,
        engines: MutableList<ZemoteEngine> = mutableListOf(),
        terminalToken: suspend (host: String, jwt: String) -> String = { _, _ ->
            throw IllegalStateException("这个用例不该换 terminal token")
        },
    ): ZemoteEnginePort = ZemoteEnginePort(
        scope = backgroundScope,
        appVersion = "1.2.3-test",
        deviceName = "test-device",
        theme = { theme },
        terminalTokenProvider = terminalToken,
        newEngine = { scope ->
            ZemoteEngine(
                bridge = bridge,
                scope = scope,
                clock = { 0L },
                dispatcher = UnconfinedTestDispatcher(testScheduler),
            ).also { engines += it }
        },
    )

    private fun createPayload(bridge: FakeNativeBridge, index: Int = 0): JsonObject =
        Json.parseToJsonElement(
            bridge.invocations.filter { it.method == "create" }[index].payload,
        ) as JsonObject

    // ------------------------------------------------------------------
    // 建会话
    // ------------------------------------------------------------------

    @Test
    fun `ensureSession 建会话并记录 URL`() = runTest(mainRule.dispatcher) {
        val bridge = FakeNativeBridge()
        val port = newPort(bridge)
        assertNull(port.connectedUrl.value)
        assertEquals(EngineState.Idle, port.engineState.value)

        port.ensureSession(urlA)
        advanceUntilIdle()

        assertEquals(urlA, port.connectedUrl.value)
        assertEquals(EngineState.Running(1), port.engineState.value)
        assertEquals(1, bridge.methodCount("create"))

        val payload = createPayload(bridge)
        assertEquals(urlA, payload["url"]!!.jsonPrimitive.content)
        assertEquals("1.2.3-test", payload["appVersion"]!!.jsonPrimitive.content)
        assertEquals("test-device", payload["deviceName"]!!.jsonPrimitive.content)
        assertEquals("android", payload["targetPlatform"]!!.jsonPrimitive.content)
        assertEquals("dark", payload["theme"]!!.jsonPrimitive.content)
    }

    @Test
    fun `新链接先换 terminal token 再建会话，重复调用不重复换`() = runTest(mainRule.dispatcher) {
        val bridge = FakeNativeBridge()
        var calls = 0
        val port = newPort(bridge) { host, jwt ->
            calls++
            assertEquals("zcode.z.ai", host)
            assertEquals("jwt-1", jwt)
            "terminal-1"
        }
        val url = "https://zcode.z.ai/remote/v4?sid=a&app_version=3.16.1"

        port.ensureSession(url, "jwt-1")
        advanceUntilIdle()

        assertEquals(1, calls)
        val payload = createPayload(bridge)
        assertEquals("terminal-1", payload["token"]!!.jsonPrimitive.content)

        // 同一个 URL + 同一个 OAuth JWT：幂等，不能再换一次 token，也不能重建会话。
        port.ensureSession(url, "jwt-1")
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(1, bridge.methodCount("create"))
    }

    @Test
    fun `terminal token 交换 401 会抛 auth-required 且不建会话`() = runTest(mainRule.dispatcher) {
        val bridge = FakeNativeBridge()
        val port = newPort(bridge) { _, _ ->
            throw RemoteTokenException("auth-required", "登录已失效")
        }
        val url = "https://zcode.z.ai/remote/v4?sid=a&app_version=3.16.1"

        try {
            port.ensureSession(url, "jwt-1")
            fail("应该抛 RemoteTokenException")
        } catch (e: RemoteTokenException) {
            assertTrue(e.authRequired)
        }

        assertNull(port.connectedUrl.value)
        assertEquals(0, bridge.methodCount("create"))
    }

    @Test
    fun `同一个 URL 重复 ensureSession 是幂等的`() = runTest(mainRule.dispatcher) {
        val bridge = FakeNativeBridge()
        val port = newPort(bridge)

        port.ensureSession(urlA)
        port.ensureSession(urlA)
        port.ensureSession("  $urlA  ")
        advanceUntilIdle()

        assertEquals(1, bridge.methodCount("create"))
        assertEquals(0, bridge.methodCount("dispose"))
    }

    @Test
    fun `空白 URL 被忽略`() = runTest(mainRule.dispatcher) {
        val bridge = FakeNativeBridge()
        val port = newPort(bridge)

        port.ensureSession("   ")
        advanceUntilIdle()

        assertNull(port.connectedUrl.value)
        assertEquals(0, bridge.methodCount("create"))
    }

    @Test
    fun `换 URL 会先拆旧会话再建新会话`() = runTest(mainRule.dispatcher) {
        val bridge = FakeNativeBridge()
        val engines = mutableListOf<ZemoteEngine>()
        val port = newPort(bridge, engines)

        port.ensureSession(urlA)
        advanceUntilIdle()
        port.ensureSession(urlB)
        advanceUntilIdle()

        assertEquals(2, engines.size)
        assertEquals(2, bridge.methodCount("create"))
        assertEquals(1, bridge.methodCount("dispose"))
        assertEquals(urlB, port.connectedUrl.value)
        // 新会话的状态没有被旧会话的 Disposed 盖掉
        assertEquals(EngineState.Running(1), port.engineState.value)
        assertEquals(urlB, createPayload(bridge, index = 1)["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun `主题在建会话时重新取一次`() = runTest(mainRule.dispatcher) {
        val bridge = FakeNativeBridge()
        val port = newPort(bridge)

        port.ensureSession(urlA)
        advanceUntilIdle()
        theme = "light"
        port.ensureSession(urlB)
        advanceUntilIdle()

        assertEquals("dark", createPayload(bridge, 0)["theme"]!!.jsonPrimitive.content)
        assertEquals("light", createPayload(bridge, 1)["theme"]!!.jsonPrimitive.content)
    }

    @Test
    fun `disconnect 复位状态并释放引擎`() = runTest(mainRule.dispatcher) {
        val bridge = FakeNativeBridge()
        val port = newPort(bridge)
        port.ensureSession(urlA)
        advanceUntilIdle()

        port.disconnect()
        advanceUntilIdle()

        assertNull(port.connectedUrl.value)
        assertNull(port.bridgeSession.value)
        assertEquals(EngineState.Idle, port.engineState.value)
        assertEquals(1, bridge.methodCount("dispose"))

        // 再连同一个 URL 会重新建会话（disconnect 之后不是「已连着」）
        port.ensureSession(urlA)
        advanceUntilIdle()
        assertEquals(2, bridge.methodCount("create"))
    }

    // ------------------------------------------------------------------
    // 命令转发
    // ------------------------------------------------------------------

    @Test
    fun `没有会话时挂起 API 抛 EngineException`() = runTest(mainRule.dispatcher) {
        val port = newPort(FakeNativeBridge())

        try {
            port.bootstrap()
            fail("应当抛 EngineException")
        } catch (e: EngineException) {
            assertEquals("invalidState", e.error.kind)
        }
    }

    @Test
    fun `openBridge 把桥会话号记进状态`() = runTest(mainRule.dispatcher) {
        val bridge = FakeNativeBridge().apply {
            callHandler = {
                envelopeOf(
                    operation = "op1",
                    events = listOf(
                        completedEvent("op1", """{"kind":"bridge_opened","session":9}"""),
                    ),
                )
            }
        }
        val port = newPort(bridge)
        port.ensureSession(urlA)
        advanceUntilIdle()

        val session = port.openBridge("w1", "t1")
        advanceUntilIdle()

        assertEquals(9L, session)
        assertEquals(9L, port.bridgeSession.value)
        val command = bridge.callCommands().single { it["method"]?.jsonPrimitive?.content == "open_bridge" }
        assertEquals("w1", command["workspaceKey"]!!.jsonPrimitive.content)
        assertEquals("t1", command["taskId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `call 原样透传命令并返回 payload`() = runTest(mainRule.dispatcher) {
        val bridge = FakeNativeBridge().apply {
            callHandler = {
                envelopeOf(
                    operation = "op2",
                    events = listOf(
                        completedEvent("op2", """{"kind":"payload","value":{"hello":"world"}}"""),
                    ),
                )
            }
        }
        val port = newPort(bridge)
        port.ensureSession(urlA)
        advanceUntilIdle()

        val result = port.call(buildJsonObject { put("method", "conversation_call") })
        advanceUntilIdle()

        assertEquals("""{"hello":"world"}""", result.toString())
        assertTrue(
            bridge.callCommands().any { it["method"]?.jsonPrimitive?.content == "conversation_call" },
        )
    }

    @Test
    fun `不产生挂起操作的命令返回 JsonNull`() = runTest(mainRule.dispatcher) {
        val bridge = FakeNativeBridge()
        val port = newPort(bridge)
        port.ensureSession(urlA)
        advanceUntilIdle()

        val result = port.call(buildJsonObject { put("method", "poke_relay") })

        assertEquals(JsonNull, result)
    }
}
