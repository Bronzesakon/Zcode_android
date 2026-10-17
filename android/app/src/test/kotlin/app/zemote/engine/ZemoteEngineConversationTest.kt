package app.zemote.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §13 对话层与定时任务层在引擎上的接线（`docs/FFI.md` §13）。
 *
 * 断言分两类：
 * - **逐字段**核对发出去的 JSON（`FakeNativeBridge.callCommands()`）——信封里错一个键名，
 *   Rust 侧会回 `badInput`，那是线上才发现的故障；
 * - **令牌形态**：对话层的完成令牌是 `conv:<key>:<op>:<n>`、定时任务层是
 *   `automation:<op>:<n>`，与核心的 `bootstrap-*` 不同，但引擎只做 `operation` ↔ `token`
 *   配对，不解析形态——这里用真实形态跑一遍，确认配对成立。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ZemoteEngineConversationTest {

    private val fixedNow = 1_760_000_000_000L

    private val wsUrl = "https://zcode.z.ai/remote/v4?sid=s&hash=h&t=1"

    private fun TestScope.newEngine(bridge: FakeNativeBridge): ZemoteEngine = ZemoteEngine(
        bridge = bridge,
        scope = backgroundScope,
        clock = { fixedNow },
        dispatcher = UnconfinedTestDispatcher(testScheduler),
    )

    private fun TestScope.startedEngine(bridge: FakeNativeBridge): ZemoteEngine =
        newEngine(bridge).also { it.start(EngineConfig(url = wsUrl)) }

    private fun scopeJson(): JsonObject = buildJsonObject { put("workspacePath", "/repo") }

    private fun keys(obj: JsonObject): Set<String> = obj.keys

    // ------------------------------------------------------------------
    // conversation_open
    // ------------------------------------------------------------------

    @Test
    fun conversationOpenSendsTheExactEnvelopeAndReturnsTheKey() = runTest {
        val bridge = FakeNativeBridge()
        val engine = startedEngine(bridge)

        // Rust 侧 `ConversationLayer::open`：信封的 operation 是 key，**并且**额外补一条
        // 同 token 的顶层 completed（`conversation.rs` 的 open()）。
        bridge.callHandler = {
            envelopeOf(
                operation = "id-1",
                events = listOf(completedEvent("id-1", """{"kind":"ok"}""")),
            )
        }

        val key = engine.conversationOpen(session = 7, scope = scopeJson())

        assertEquals("id-1", key)
        val sent = bridge.callCommands().last()
        assertEquals(setOf("method", "session", "scope"), keys(sent))
        assertEquals("conversation_open", sent["method"]!!.jsonPrimitive.content)
        assertEquals(7L, (sent["session"] as JsonPrimitive).long)
        assertEquals(scopeJson(), sent["scope"])
    }

    /**
     * 「该不该等 completed」的**行为证据**：Rust 侧现在补那条顶层 `completed`，所以走通用
     * 路径立刻返回；不补的话这里就会等到宿主兜底超时。这条测试锁住前一种行为——
     * 哪天 `conversation.rs` 不再补 `completed`，它会红。
     */
    @Test
    fun conversationOpenWaitsForTheTopLevelCompletedItGetsFromRust() = runTest {
        val bridge = FakeNativeBridge()
        val engine = startedEngine(bridge)

        // 只给 operation，**不给** completed：引擎必须一直等到兜底超时（30s + 5s 余量）
        bridge.callHandler = { envelopeOf(operation = "id-1") }
        val call = async(UnconfinedTestDispatcher(testScheduler)) {
            runCatching { engine.conversationOpen(session = 7, scope = scopeJson()) }
        }

        advanceTimeBy(34_000)
        assertTrue("34s 时不该已经失败", call.isActive)

        advanceTimeBy(1_500)
        val failure = call.await().exceptionOrNull()
        assertTrue("应抛 EngineException：$failure", failure is EngineException)
        assertEquals("timeout", (failure as EngineException).error.kind)
    }

    @Test
    fun conversationOpenWithoutOperationIsAProtocolFailure() = runTest {
        val bridge = FakeNativeBridge()
        val engine = startedEngine(bridge)
        bridge.callHandler = { envelopeOf(operation = null) }

        val failure = runCatching { engine.conversationOpen(7, scopeJson()) }.exceptionOrNull()

        assertTrue(failure is EngineException)
        assertEquals("protocol", (failure as EngineException).error.kind)
    }

    @Test
    fun conversationOpenSurfacesInvalidStateForAnUnknownBridge() = runTest {
        val bridge = FakeNativeBridge()
        val engine = startedEngine(bridge)
        bridge.callHandler = {
            """{"ok":false,"error":{"kind":"invalidState","message":"未知桥会话 7"},"events":[]}"""
        }

        val failure = runCatching { engine.conversationOpen(7, scopeJson()) }.exceptionOrNull()

        assertEquals("invalidState", (failure as EngineException).error.kind)
    }

    // ------------------------------------------------------------------
    // conversation_call
    // ------------------------------------------------------------------

    @Test
    fun conversationCallSendsTheExactEnvelope() = runTest {
        val bridge = FakeNativeBridge()
        val engine = startedEngine(bridge)
        bridge.callHandler = { envelopeOf(operation = "conv:id-1:sendText:4") }

        val call = async(UnconfinedTestDispatcher(testScheduler)) {
            engine.conversationCall(
                session = 7,
                key = "id-1",
                op = "sendText",
                args = buildJsonObject {
                    put("sessionId", "s-1")
                    put("text", "你好")
                },
            )
        }

        val sent = bridge.callCommands().last()
        assertEquals(setOf("method", "session", "key", "op", "args"), keys(sent))
        assertEquals("conversation_call", sent["method"]!!.jsonPrimitive.content)
        assertEquals(7L, (sent["session"] as JsonPrimitive).long)
        assertEquals("id-1", sent["key"]!!.jsonPrimitive.content)
        assertEquals("sendText", sent["op"]!!.jsonPrimitive.content)
        assertEquals(
            buildJsonObject { put("sessionId", "s-1"); put("text", "你好") },
            sent["args"],
        )

        call.cancel()
    }

    @Test
    fun conversationCallResolvesByTheConvPrefixedToken() = runTest {
        val bridge = FakeNativeBridge()
        val engine = startedEngine(bridge)
        val caller = UnconfinedTestDispatcher(testScheduler)

        bridge.callHandler = { envelopeOf(operation = "conv:id-1:sendText:4") }
        val call = async(caller) {
            engine.conversationCall(7, "id-1", "sendText", JsonObject(emptyMap()))
        }

        // 别的对话/核心的令牌先完成：必须按 token 精确配对，不能被误唤醒
        bridge.callHandler = {
            envelopeOf(
                events = listOf(
                    completedEvent("bootstrap-abc", payloadOutcome("""{"nope":true}""")),
                    completedEvent(
                        "conv:id-1:sendText:4",
                        payloadOutcome("""{"accepted":true}"""),
                    ),
                ),
            )
        }
        engine.pokeRelay()

        assertEquals("""{"accepted":true}""", call.await().toString())
    }

    @Test
    fun conversationCallWithNoOperationReturnsImmediately() = runTest {
        val bridge = FakeNativeBridge()
        val engine = startedEngine(bridge)
        // 例如已握手时的幂等 handshake：Rust 侧没有 opAccepted，也就没有 operation
        bridge.callHandler = { envelopeOf(operation = null) }

        val result = engine.conversationCall(7, "id-1", "handshake", JsonObject(emptyMap()))

        assertEquals(JsonNull, result)
    }

    /**
     * §13.5.1 的 `args.timeoutSecs` 必须进宿主兜底：`createSession` 默认 90s，
     * 若按默认 30s 兜底，核心还没到点就会被宿主判超时（假失败）。
     */
    @Test
    fun conversationCallHonoursTimeoutSecsInItsHostFallback() = runTest {
        val bridge = FakeNativeBridge()
        val engine = startedEngine(bridge)
        bridge.callHandler = { envelopeOf(operation = "conv:id-1:createSession:1") }
        val call = async(UnconfinedTestDispatcher(testScheduler)) {
            runCatching {
                engine.conversationCall(
                    7,
                    "id-1",
                    "createSession",
                    buildJsonObject {
                        put("workspaceId", "w-1")
                        put("timeoutSecs", 90)
                    },
                )
            }
        }

        // 默认兜底（35s）已经过去了，但这条声明了 90s ⇒ 还在等
        advanceTimeBy(40_000)
        assertTrue("声明了 timeoutSecs=90 就不该在 35s 兜底", call.isActive)

        advanceTimeBy(90_000 - 40_000 + 5_000 + 100)
        val failure = call.await().exceptionOrNull()
        assertEquals("timeout", (failure as EngineException).error.kind)
    }

    // ------------------------------------------------------------------
    // conversation_close
    // ------------------------------------------------------------------

    @Test
    fun conversationCloseSendsTheExactEnvelopeAndDoesNotWait() = runTest {
        val bridge = FakeNativeBridge()
        val engine = startedEngine(bridge)
        bridge.callHandler = { envelopeOf(operation = null) }

        // 不挂起：没有 operation ⇒ 立刻返回（若它去等 completed，这条测试会挂到测试超时）
        engine.conversationClose(session = 7, key = "id-1")

        val sent = bridge.callCommands().last()
        assertEquals(setOf("method", "session", "key"), keys(sent))
        assertEquals("conversation_close", sent["method"]!!.jsonPrimitive.content)
        assertEquals(7L, (sent["session"] as JsonPrimitive).long)
        assertEquals("id-1", sent["key"]!!.jsonPrimitive.content)
    }

    /**
     * **已知局限的回归**：`conversation_close` 不为在飞操作补 `completed`（Rust 侧
     * `ConversationLayer::close` 只清本层挂起登记），所以那个 Deferred 只能等宿主兜底超时。
     * 这条测试就是那条兜底的证据。
     */
    @Test
    fun inFlightCallAfterCloseEndsOnlyViaTheHostFallbackTimeout() = runTest {
        val bridge = FakeNativeBridge()
        val engine = startedEngine(bridge)

        // 先让它挂上：operation 有、completed 永不到
        bridge.callHandler = { envelopeOf(operation = "conv:id-1:sendText:9") }
        val call = async(UnconfinedTestDispatcher(testScheduler)) {
            runCatching {
                engine.conversationCall(7, "id-1", "sendText", JsonObject(emptyMap()))
            }
        }
        advanceTimeBy(1)
        assertTrue("这条调用应该还在等 completed", call.isActive)

        // close 不补 completed（Rust 侧只清本层挂起登记）
        bridge.callHandler = { envelopeOf(operation = null) }
        engine.conversationClose(session = 7, key = "id-1")
        advanceTimeBy(1)
        assertTrue("close 之后它仍然在等（这正是已知局限）", call.isActive)

        advanceTimeBy(35_100)
        val failure = call.await().exceptionOrNull()
        assertTrue("应抛 EngineException：$failure", failure is EngineException)
        assertEquals("timeout", (failure as EngineException).error.kind)
    }

    // ------------------------------------------------------------------
    // automation_call
    // ------------------------------------------------------------------

    @Test
    fun automationCallSendsTheExactEnvelopeAndResolvesByAutomationToken() = runTest {
        val bridge = FakeNativeBridge()
        val engine = startedEngine(bridge)
        val caller = UnconfinedTestDispatcher(testScheduler)

        bridge.callHandler = { envelopeOf(operation = "automation:list:3") }
        val call = async(caller) {
            engine.automationCall(session = 7, op = "list", args = JsonObject(emptyMap()))
        }

        val sent = bridge.callCommands().last()
        assertEquals(setOf("method", "session", "op", "args"), keys(sent))
        assertEquals("automation_call", sent["method"]!!.jsonPrimitive.content)
        assertEquals(7L, (sent["session"] as JsonPrimitive).long)
        assertEquals("list", sent["op"]!!.jsonPrimitive.content)
        assertEquals(JsonObject(emptyMap()), sent["args"])

        bridge.callHandler = {
            envelopeOf(
                events = listOf(
                    completedEvent(
                        "automation:list:3",
                        payloadOutcome("""[{"automationId":"a-1","title":"日报"}]"""),
                    ),
                ),
            )
        }
        engine.pokeRelay()

        assertEquals("""[{"automationId":"a-1","title":"日报"}]""", call.await().toString())
    }

    @Test
    fun automationCallSetEnabledCarriesItsArgs() = runTest {
        val bridge = FakeNativeBridge()
        val engine = startedEngine(bridge)
        bridge.callHandler = { envelopeOf(operation = "automation:setEnabled:5") }
        val call = async(UnconfinedTestDispatcher(testScheduler)) {
            engine.automationCall(
                session = 3,
                op = "setEnabled",
                args = buildJsonObject {
                    put("automationId", "a-1")
                    put("enabled", true)
                },
            )
        }

        val sent = bridge.callCommands().last()
        assertEquals("setEnabled", sent["op"]!!.jsonPrimitive.content)
        assertEquals(3L, (sent["session"] as JsonPrimitive).long)
        assertEquals(
            buildJsonObject { put("automationId", "a-1"); put("enabled", true) },
            sent["args"],
        )
        call.cancel()
    }

    // ------------------------------------------------------------------
    // conversation_event
    // ------------------------------------------------------------------

    /** §13.4：`conversation_event` 必须被解析成 [EngineEvent.ConversationEvent] 而不是被丢掉。 */
    @Test
    fun conversationEventsAreParsedAndSurfacedOnTheEventsFlow() = runTest {
        val bridge = FakeNativeBridge()
        val engine = startedEngine(bridge)

        val received = mutableListOf<EngineEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.events.collect { received += it }
        }

        // 真实形状（`codec::conversation_event_envelope` + §13.5.2 的 camelCase 体）
        bridge.callHandler = {
            envelopeOf(
                events = listOf(
                    """{"type":"conversation_event","session":1,"key":"id-1","event":{"type":"opAccepted","token":"sendText:4","op":"sendText"}}""",
                    """{"type":"conversation_event","session":1,"key":"id-1","event":{"type":"rowsUpdated","sessionId":"s-1","frame":null,"ready":true,"seq":3,"totalCount":12,"firstRowId":"r-1","rowCount":4}}""",
                ),
            )
        }
        engine.pokeRelay()

        val events = received.filterIsInstance<EngineEvent.ConversationEvent>()
        assertEquals(2, events.size)
        assertEquals(1L, events[0].session)
        assertEquals("id-1", events[0].key)
        val first = events[0].event as JsonObject
        assertEquals("opAccepted", first["type"]!!.jsonPrimitive.content)
        assertEquals("sendText:4", first["token"]!!.jsonPrimitive.content)
        assertEquals("sendText", first["op"]!!.jsonPrimitive.content)
        val second = events[1].event as JsonObject
        assertEquals("rowsUpdated", second["type"]!!.jsonPrimitive.content)
        assertEquals(3L, (second["seq"] as JsonPrimitive).long)
    }

    /** §7.3 的裸 `channel` 事件（不属于任何 conversation）仍要能过——路由不能把它们吃掉。 */
    @Test
    fun bareChannelEventsStillPassThrough() = runTest {
        val bridge = FakeNativeBridge()
        val engine = startedEngine(bridge)

        val received = mutableListOf<EngineEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.events.collect { received += it }
        }

        bridge.callHandler = {
            envelopeOf(
                events = listOf(
                    """{"type":"channel","event":{"type":"promise_success","id":1,"data":{"a":1}}}""",
                ),
            )
        }
        engine.pokeRelay()

        val channel = received.filterIsInstance<EngineEvent.Channel>().single()
        assertEquals(
            ChannelEvent.PromiseSuccess(1, buildJsonObject { put("a", 1) }),
            channel.event,
        )
        assertNull(received.filterIsInstance<EngineEvent.ConversationEvent>().firstOrNull())
    }
}
