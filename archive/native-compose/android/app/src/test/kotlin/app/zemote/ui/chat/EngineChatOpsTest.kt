package app.zemote.ui.chat

import app.zemote.engine.EngineException
import app.zemote.ui.FakeEnginePort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EngineChatOps]：惰性开对话 + 按桥会话缓存 + 把 `op` 原样透传。
 *
 * 这里用的是 [FakeEnginePort]（记录 `conversationOpen` / `conversationCall` 的调用），
 * 引擎自己那一层（信封逐字段、令牌配对、超时）由 `ZemoteEngineConversationTest` 覆盖。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EngineChatOpsTest {

    private fun ops(
        engine: FakeEnginePort,
        session: () -> Long? = { engine.bridgeSession.value },
        scope: () -> JsonObject = { buildJsonObject { put("workspacePath", "/repo") } },
    ): EngineChatOps = EngineChatOps(engine = engine, session = session, defaultScope = scope)

    @Test
    fun `第一次 call 才开对话并把 key 用在信封里`() = runTest {
        val engine = FakeEnginePort()
        engine.bridgeSessionFlow.value = 7L
        engine.conversationOpenKey = "id-1"
        engine.conversationCallResult = JsonPrimitive("ok")
        val chatOps = ops(engine)

        assertEquals(0, engine.conversationOpens.size)
        val result = chatOps.call("sendText", buildJsonObject { put("text", "hi") })

        assertEquals(JsonPrimitive("ok"), result)
        assertEquals(listOf(7L to buildJsonObject { put("workspacePath", "/repo") }), engine.conversationOpens)
        val call = engine.conversationCalls.single()
        assertEquals(7L, call.session)
        assertEquals("id-1", call.key)
        assertEquals("sendText", call.op)
        assertEquals(buildJsonObject { put("text", "hi") }, call.args)
    }

    @Test
    fun `同一个桥会话只开一次对话`() = runTest {
        val engine = FakeEnginePort()
        engine.bridgeSessionFlow.value = 7L
        val chatOps = ops(engine)

        chatOps.call("handshake", JsonObject(emptyMap()))
        chatOps.call("subscribe", buildJsonObject { put("sessionId", "s-1") })
        chatOps.call("stop", buildJsonObject { put("sessionId", "s-1") })

        assertEquals(1, engine.conversationOpens.size)
        assertEquals(3, engine.conversationCalls.size)
        assertTrue(engine.conversationCalls.all { it.key == "conv-key" })
    }

    @Test
    fun `换了桥会话会重开对话`() = runTest {
        val engine = FakeEnginePort()
        engine.bridgeSessionFlow.value = 7L
        val chatOps = ops(engine)

        chatOps.call("handshake", JsonObject(emptyMap()))
        engine.conversationOpenKey = "id-2"
        engine.bridgeSessionFlow.value = 8L
        chatOps.call("handshake", JsonObject(emptyMap()))

        assertEquals(listOf(7L, 8L), engine.conversationOpens.map { it.first })
        assertEquals(listOf("conv-key", "id-2"), engine.conversationCalls.map { it.key })
    }

    @Test
    fun `还没有桥会话时抛 invalidState 而不是 ChatOpsUnavailable`() = runTest {
        val engine = FakeEnginePort()
        val chatOps = ops(engine)

        val failure = runCatching {
            chatOps.call("sendText", buildJsonObject { put("text", "hi") })
        }.exceptionOrNull()

        assertTrue("应抛 EngineException：$failure", failure is EngineException)
        assertEquals("invalidState", (failure as EngineException).error.kind)
        assertEquals(0, engine.conversationOpens.size)
        assertEquals(0, engine.conversationCalls.size)
    }

    @Test
    fun `开对话失败会原样上抛且不缓存`() = runTest {
        val engine = FakeEnginePort()
        engine.bridgeSessionFlow.value = 7L
        engine.conversationOpenFailure = EngineException(
            app.zemote.engine.NativeError("badInput", "scope 不合法"),
        )
        val chatOps = ops(engine)

        val first = runCatching { chatOps.call("sendText", JsonObject(emptyMap())) }.exceptionOrNull()
        assertEquals("badInput", (first as EngineException).error.kind)

        // 失败不缓存：下一次会再试
        engine.conversationOpenFailure = null
        chatOps.call("sendText", JsonObject(emptyMap()))
        assertEquals(2, engine.conversationOpens.size)
    }

    @Test
    fun `call 的失败原样上抛`() = runTest {
        val engine = FakeEnginePort()
        engine.bridgeSessionFlow.value = 7L
        engine.conversationCallFailure = EngineException(
            app.zemote.engine.NativeError("badInput", "missing required string arg 'sessionId'"),
        )
        val chatOps = ops(engine)

        val failure = runCatching {
            chatOps.call("sendText", buildJsonObject { put("text", "hi") })
        }.exceptionOrNull()

        assertEquals("badInput", (failure as EngineException).error.kind)
    }

    @Test
    fun `并发 call 只开一次对话`() = runTest {
        val engine = FakeEnginePort()
        engine.bridgeSessionFlow.value = 7L
        val chatOps = ops(engine)
        val dispatcher = UnconfinedTestDispatcher(testScheduler)

        val a = async(dispatcher) { chatOps.call("handshake", JsonObject(emptyMap())) }
        val b = async(dispatcher) { chatOps.call("stop", JsonObject(emptyMap())) }
        a.await()
        b.await()

        assertEquals(1, engine.conversationOpens.size)
        assertEquals(2, engine.conversationCalls.size)
    }

    @Test
    fun `没有返回值的 op 拿到 JsonNull`() = runTest {
        val engine = FakeEnginePort()
        engine.bridgeSessionFlow.value = 7L
        engine.conversationCallResult = JsonNull
        val chatOps = ops(engine)

        assertEquals(JsonNull, chatOps.call("handshake", JsonObject(emptyMap())))
    }
}
