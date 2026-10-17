package app.zemote.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ZemoteEngine] 的事件循环与公开 API。
 *
 * 全部跑在 `runTest` 的虚拟时间 + `UnconfinedTestDispatcher` 上：
 * - 引擎的 native 调用在调用线程上**同步**跑完，断言不需要等待；
 * - 超时/定时器走虚拟时间，`advanceTimeBy` 一推就到，不睡真实时间。
 *
 * 这也顺带验证了契约 §11 的可测性要求：引擎逻辑不依赖真实线程。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ZemoteEngineTest {

    private val fixedNow = 1_760_000_000_000L

    private val wsUrl = "https://zcode.z.ai/remote/v4?sid=s&hash=h&t=1"

    private fun TestScope.newEngine(bridge: FakeNativeBridge): ZemoteEngine = ZemoteEngine(
        bridge = bridge,
        scope = backgroundScope,
        clock = { fixedNow },
        dispatcher = UnconfinedTestDispatcher(testScheduler),
    )

    private fun config() = EngineConfig(url = wsUrl)

    private fun cmd(method: String, vararg extra: Pair<String, JsonElement>): JsonObject =
        buildJsonObject {
            put("method", method)
            extra.forEach { (k, v) -> put(k, v) }
        }

    // ----------------------------------------------------------------------
    // start
    // ----------------------------------------------------------------------

    @Test
    fun startCreatesSessionThenFeedsStart() = runTest {
        val bridge = FakeNativeBridge()
        val engine = newEngine(bridge)

        engine.start(config())

        assertEquals(EngineState.Running(1), engine.state.value)
        // 顺序：先 ABI 探针，再建会话，最后发 start
        assertEquals(listOf("abiVersion", "create", "step"), bridge.invocations.map { it.method })
        assertEquals(listOf("""{"type":"start"}"""), bridge.stepInputs())
        // 所有带 handle 的调用都用 create 返回的那个
        assertTrue(bridge.invocations.filter { it.method == "step" }.all { it.handle == 1L })

        val createJson =
            Json.parseToJsonElement(bridge.invocations.first { it.method == "create" }.payload)
                as JsonObject
        assertEquals(wsUrl, createJson["url"]!!.jsonPrimitive.content)
        assertEquals(fixedNow, createJson["nowMs"]!!.jsonPrimitive.long)
        assertEquals("android", createJson["targetPlatform"]!!.jsonPrimitive.content)
    }

    @Test
    fun startIsIdempotent() = runTest {
        val bridge = FakeNativeBridge()
        val engine = newEngine(bridge)

        engine.start(config())
        engine.start(config())

        assertEquals(1, bridge.methodCount("create"))
        assertEquals(1, bridge.methodCount("abiVersion"))
    }

    @Test
    fun createEventsAreNotLost() = runTest {
        val bridge = FakeNativeBridge()
        bridge.createResponse = envelopeOf(
            handle = 1,
            events = listOf(
                """{"type":"relay_log","line":"[relay] boot"}""",
                """{"type":"relay_state","state":"connecting"}""",
                """{"type":"relay_timer_start","timer":"waiting","delayMs":30000,"repeat":false}""",
            ),
        )
        val engine = newEngine(bridge)

        engine.start(config())

        assertEquals(RelayState.Connecting, engine.relayState.value)
        assertTrue(engine.logs.value.contains("[relay] boot"))

        // 构造期起的定时器真的被起了
        advanceTimeBy(30_001)
        assertTrue(
            bridge.stepInputs().contains("""{"type":"relay_timer_fired","timer":"waiting"}"""),
        )
    }

    @Test
    fun relayFailureIsExposedToTheUi() = runTest {
        val bridge = FakeNativeBridge()
        bridge.createResponse = envelopeOf(
            handle = 1,
            events = listOf("""{"type":"relay_failure","reason":"session-not-found","message":null}"""),
        )
        val engine = newEngine(bridge)

        engine.start(config())

        assertEquals(
            EngineEvent.RelayFailure("session-not-found", null),
            engine.relayFailure.value,
        )
    }

    @Test
    fun abiMismatchEntersFatalWithoutCreatingASession() = runTest {
        val bridge = FakeNativeBridge()
        bridge.abiVersionResponse =
            """{"ok":true,"abi":2,"protocolVersion":"0.2.0","features":[]}"""
        val engine = newEngine(bridge)

        engine.start(config())

        val state = engine.state.value
        assertTrue("应进入 Fatal：$state", state is EngineState.Fatal)
        assertTrue((state as EngineState.Fatal).reason.contains("ABI"))
        // 结构错位时不要带着错位的结构跑下去（契约 §4）
        assertEquals(0, bridge.methodCount("create"))
    }

    @Test
    fun unparseableAbiProbeEntersFatal() = runTest {
        val bridge = FakeNativeBridge()
        bridge.abiVersionResponse = "not json at all"
        val engine = newEngine(bridge)

        engine.start(config())

        assertTrue(engine.state.value is EngineState.Fatal)
        assertEquals(0, bridge.methodCount("create"))
    }

    @Test
    fun createFailureEntersFatalButKeepsItsEvents() = runTest {
        val bridge = FakeNativeBridge()
        bridge.createResponse =
            """{"ok":false,"error":{"kind":"badInput","message":"bad url"},"events":[{"type":"log","line":"parse failed"}]}"""
        val engine = newEngine(bridge)

        engine.start(config())

        assertTrue(engine.state.value is EngineState.Fatal)
        // 出错前的事件不能丢（契约 §3）
        assertTrue(engine.logs.value.contains("parse failed"))
        assertEquals("badInput", engine.lastError.value?.kind)
    }

    // ----------------------------------------------------------------------
    // 挂起 API 与 completed 配对
    // ----------------------------------------------------------------------

    @Test
    fun completedResolvesTheMatchingDeferred() = runTest {
        val bridge = FakeNativeBridge()
        val engine = newEngine(bridge)
        engine.start(config())
        val caller = UnconfinedTestDispatcher(testScheduler)

        bridge.callHandler = { envelopeOf(operation = "op-a") }
        val a = async(caller) { engine.call(cmd("bootstrap")) }

        bridge.callHandler = { envelopeOf(operation = "op-b") }
        val b = async(caller) { engine.call(cmd("list_workspaces")) }

        // 故意颠倒完成顺序：必须按 token 配对，而不是按发起先后
        bridge.callHandler = {
            envelopeOf(
                events = listOf(
                    completedEvent("op-b", payloadOutcome("""{"workspace":"B"}""")),
                    completedEvent("op-a", payloadOutcome("""{"workspace":"A"}""")),
                ),
            )
        }
        engine.pokeRelay()

        assertEquals("""{"workspace":"B"}""", b.await().toString())
        assertEquals("""{"workspace":"A"}""", a.await().toString())
    }

    @Test
    fun completedInTheSameEnvelopeStillResolves() = runTest {
        val bridge = FakeNativeBridge()
        val engine = newEngine(bridge)
        engine.start(config())

        // 同步完成：completed 就藏在 nativeCall 本次返回的 events 里
        bridge.callHandler = {
            envelopeOf(
                operation = "op-sync",
                events = listOf(completedEvent("op-sync", payloadOutcome("""{"fast":true}"""))),
            )
        }

        assertEquals("""{"fast":true}""", engine.bootstrap().toString())
    }

    @Test
    fun failedOutcomeSurfacesAsException() = runTest {
        val bridge = FakeNativeBridge()
        val engine = newEngine(bridge)
        engine.start(config())
        val caller = UnconfinedTestDispatcher(testScheduler)

        bridge.callHandler = { envelopeOf(operation = "op-x") }
        val call = async(caller) { runCatching { engine.call(cmd("bootstrap")) } }

        bridge.callHandler = {
            envelopeOf(
                events = listOf(completedEvent("op-x", failedOutcome("timeout", "no pairing"))),
            )
        }
        engine.pokeRelay()

        val failure = call.await().exceptionOrNull()
        assertTrue("应抛 EngineException：$failure", failure is EngineException)
        assertEquals("timeout", (failure as EngineException).error.kind)
    }

    @Test
    fun hostSideTimeoutBacksUpTheCoreTimer() = runTest {
        val bridge = FakeNativeBridge()
        val engine = newEngine(bridge)
        engine.start(config())
        val caller = UnconfinedTestDispatcher(testScheduler)

        // 永不 completed —— 模拟核心的定时器事件丢了 / 宿主漏回喂 timer_fired
        bridge.callHandler = { envelopeOf(operation = "op-hang") }
        // 用 runCatching 包住：失败的 async 子协程会取消测试作用域，那样异常就不在
        // await() 上暴露了（这里要验证的正是挂起调用自身的行为）
        val call = async(caller) {
            runCatching {
                engine.call(cmd("wait_paired", "timeoutMs" to Json.parseToJsonElement("1000")))
            }
        }

        // 宿主兜底 = 核心给的 1000ms + 5000ms 余量
        advanceTimeBy(6_001)

        val failure = call.await().exceptionOrNull()
        assertTrue("应抛 EngineException：$failure", failure is EngineException)
        assertEquals("timeout", (failure as EngineException).error.kind)
    }

    @Test
    fun errorEnvelopeStillDeliversItsEvents() = runTest {
        val bridge = FakeNativeBridge()
        val engine = newEngine(bridge)
        engine.start(config())

        bridge.callHandler = {
            """{"ok":false,"error":{"kind":"invalidState","message":"not paired"},"events":[{"type":"relay_state","state":"error"}]}"""
        }

        val failure = runCatching { engine.call(cmd("bootstrap")) }.exceptionOrNull()
        assertTrue(failure is EngineException)
        assertEquals("invalidState", (failure as EngineException).error.kind)
        // 出错前的事件没丢（契约 §3）
        assertEquals(RelayState.Error, engine.relayState.value)
        assertEquals("invalidState", engine.lastError.value?.kind)
        // 非 panic 的错误不致命，引擎继续可用
        assertEquals(EngineState.Running(1), engine.state.value)
    }

    @Test
    fun openBridgeResolvesSessionId() = runTest {
        val bridge = FakeNativeBridge()
        val engine = newEngine(bridge)
        engine.start(config())
        val caller = UnconfinedTestDispatcher(testScheduler)

        bridge.callHandler = { envelopeOf(operation = "req-open") }
        val call = async(caller) { engine.openBridge("wk", "task-1") }

        bridge.callHandler = {
            envelopeOf(
                events = listOf(
                    completedEvent("req-open", """{"kind":"bridge_opened","session":9}"""),
                ),
            )
        }
        engine.pokeRelay()

        assertEquals(9L, call.await())

        val open = bridge.callCommands().first { it["method"]!!.jsonPrimitive.content == "open_bridge" }
        assertEquals("wk", open["workspaceKey"]!!.jsonPrimitive.content)
        assertEquals("task-1", open["taskId"]!!.jsonPrimitive.content)
        assertEquals(30_000L, open["timeoutMs"]!!.jsonPrimitive.long)
    }

    @Test
    fun openBridgeSendsNullTaskIdAsAnExplicitKey() = runTest {
        val bridge = FakeNativeBridge()
        val engine = newEngine(bridge)
        engine.start(config())
        val caller = UnconfinedTestDispatcher(testScheduler)

        bridge.callHandler = { envelopeOf(operation = "req-open") }
        val call = async(caller) { engine.openBridge("wk", null) }
        bridge.callHandler = {
            envelopeOf(
                events = listOf(completedEvent("req-open", """{"kind":"bridge_opened","session":3}""")),
            )
        }
        engine.pokeRelay()
        assertEquals(3L, call.await())

        val open = bridge.callCommands().first { it["method"]!!.jsonPrimitive.content == "open_bridge" }
        assertTrue("taskId 键不能省（契约 §3）", open.containsKey("taskId"))
        assertEquals(JsonNull, open["taskId"])
    }

    @Test
    fun waitPairedAndWaitHealthyCarryTheirTimeouts() = runTest {
        val bridge = FakeNativeBridge()
        val engine = newEngine(bridge)
        engine.start(config())
        val caller = UnconfinedTestDispatcher(testScheduler)

        bridge.callHandler = { envelopeOf(operation = "pair:3") }
        val paired = async(caller) { engine.waitPaired(12_345) }
        bridge.callHandler = {
            envelopeOf(events = listOf(completedEvent("pair:3", """{"kind":"ok"}""")))
        }
        engine.pokeRelay()
        paired.await()

        val waitCmd = bridge.callCommands().first { it["method"]!!.jsonPrimitive.content == "wait_paired" }
        assertEquals(12_345L, waitCmd["timeoutMs"]!!.jsonPrimitive.long)

        bridge.callHandler = { envelopeOf(operation = "healthy:7") }
        val healthy = async(caller) { engine.waitHealthy(7, 45_000) }
        bridge.callHandler = {
            envelopeOf(events = listOf(completedEvent("healthy:7", """{"kind":"ok"}""")))
        }
        engine.pokeRelay()
        healthy.await()

        val healthyCmd =
            bridge.callCommands().first { it["method"]!!.jsonPrimitive.content == "bridge_wait_healthy" }
        assertEquals(7L, healthyCmd["session"]!!.jsonPrimitive.long)
        assertEquals(45_000L, healthyCmd["timeoutMs"]!!.jsonPrimitive.long)
    }

    @Test
    fun fireAndForgetCommandsDoNotSuspend() = runTest {
        val bridge = FakeNativeBridge()
        val engine = newEngine(bridge)
        engine.start(config())

        bridge.callHandler = {
            envelopeOf(
                operation = null,
                events = listOf("""{"type":"workspace_list_updated","value":{"workspaces":[]}}"""),
            )
        }

        engine.pokeRelay()
        engine.sendMobileViewState("wk", null)
        val reconnect = engine.reconnectWorkspace("wk")

        assertEquals(JsonNull, reconnect)
        assertEquals(
            listOf("poke_relay", "send_mobile_view_state", "reconnect_workspace"),
            bridge.callCommands().map { it["method"]!!.jsonPrimitive.content },
        )
        val view = bridge.callCommands().first {
            it["method"]!!.jsonPrimitive.content == "send_mobile_view_state"
        }
        assertEquals("wk", view["workspaceKey"]!!.jsonPrimitive.content)
        assertTrue(view.containsKey("taskId"))
        assertEquals(JsonNull, view["taskId"])
    }

    @Test
    fun snapshotUsesTheQueryChannel() = runTest {
        val bridge = FakeNativeBridge()
        bridge.queryResponse =
            """{"ok":true,"snapshot":{"disposed":false,"relay":{"state":"paired","wasPaired":true}}}"""
        val engine = newEngine(bridge)
        engine.start(config())

        val snapshot = engine.snapshot() as JsonObject

        assertEquals("paired", (snapshot["relay"] as JsonObject)["state"]!!.jsonPrimitive.content)
        assertEquals(
            """{"query":"snapshot"}""",
            bridge.invocations.first { it.method == "query" }.payload,
        )
    }

    // ----------------------------------------------------------------------
    // 定时器接线
    // ----------------------------------------------------------------------

    @Test
    fun timersStartRefireAndCancel() = runTest {
        val bridge = FakeNativeBridge()
        bridge.createResponse = envelopeOf(
            handle = 1,
            events = listOf(
                """{"type":"timer_start","token":7,"delayMs":30000}""",
                """{"type":"relay_timer_start","timer":"heartbeat","delayMs":10000,"repeat":true}""",
            ),
        )
        val engine = newEngine(bridge)
        engine.start(config())

        val heartbeat = """{"type":"relay_timer_fired","timer":"heartbeat"}"""
        val facade = """{"type":"timer_fired","token":7}"""

        advanceTimeBy(10_001)
        assertEquals(1, bridge.stepInputs().count { it == heartbeat })

        // repeat=true 由宿主自行重排
        advanceTimeBy(10_000)
        assertEquals(2, bridge.stepInputs().count { it == heartbeat })

        advanceTimeBy(10_001)
        assertEquals(3, bridge.stepInputs().count { it == heartbeat })
        assertEquals(1, bridge.stepInputs().count { it == facade })

        // 撤两套定时器（relay_timer_cancel 重复一次，验证幂等）
        bridge.callHandler = {
            envelopeOf(
                events = listOf(
                    """{"type":"timer_cancel","token":7}""",
                    """{"type":"relay_timer_cancel","timer":"heartbeat"}""",
                    """{"type":"relay_timer_cancel","timer":"heartbeat"}""",
                ),
            )
        }
        engine.pokeRelay()

        advanceTimeBy(120_000)
        assertEquals(3, bridge.stepInputs().count { it == heartbeat })
        assertEquals(1, bridge.stepInputs().count { it == facade })
    }

    // ----------------------------------------------------------------------
    // Fatal / 防御
    // ----------------------------------------------------------------------

    @Test
    fun panicFromCallEntersFatalAndStopsCallingNative() = runTest {
        val bridge = FakeNativeBridge()
        val engine = newEngine(bridge)
        engine.start(config())

        bridge.callHandler = {
            """{"ok":false,"error":{"kind":"panic","message":"index out of bounds at src/x.rs:42"},"events":[{"type":"log","line":"before-panic"}]}"""
        }

        val failure = runCatching { engine.pokeRelay() }.exceptionOrNull()
        assertTrue(failure is EngineException)

        val state = engine.state.value
        assertTrue("应进入 Fatal：$state", state is EngineState.Fatal)
        assertTrue((state as EngineState.Fatal).reason.contains("panic"))
        assertTrue(engine.logs.value.contains("before-panic"))

        // Fatal 之后不再碰 native，但进程活着
        val callsBefore = bridge.methodCount("call")
        runCatching { engine.pokeRelay() }
        assertEquals(callsBefore, bridge.methodCount("call"))
    }

    @Test
    fun panicFromStepEntersFatalAndStopsFeeding() = runTest {
        val bridge = FakeNativeBridge()
        bridge.createResponse = envelopeOf(
            handle = 1,
            events = listOf("""{"type":"timer_start","token":1,"delayMs":1000}"""),
        )
        val engine = newEngine(bridge)
        engine.start(config())

        bridge.stepHandler = {
            """{"ok":false,"error":{"kind":"panic","message":"boom"},"events":[]}"""
        }
        advanceTimeBy(1_001)
        assertTrue(engine.state.value is EngineState.Fatal)

        val stepsBefore = bridge.methodCount("step")
        advanceTimeBy(120_000)
        assertEquals(stepsBefore, bridge.methodCount("step"))
    }

    @Test
    fun nativeThrowingIsContained() = runTest {
        val bridge = FakeNativeBridge()
        bridge.createResponse = envelopeOf(
            handle = 1,
            events = listOf("""{"type":"timer_start","token":1,"delayMs":1000}"""),
        )
        val engine = newEngine(bridge)
        engine.start(config())

        bridge.throwOnCall = true
        val failure = runCatching { engine.pokeRelay() }.exceptionOrNull()
        assertTrue(failure is EngineException)
        assertEquals("protocol", (failure as EngineException).error.kind)
        // 引擎没崩，也没进 Fatal
        assertEquals(EngineState.Running(1), engine.state.value)

        bridge.throwOnCall = false
        bridge.throwOnStep = true
        val stepsBefore = bridge.methodCount("step")
        advanceTimeBy(1_001) // 定时器到点 → 回喂 step → 抛异常
        assertEquals(stepsBefore + 1, bridge.methodCount("step"))
        assertEquals(EngineState.Running(1), engine.state.value)
    }

    @Test
    fun fatalWakesUpWaitingCallers() = runTest {
        val bridge = FakeNativeBridge()
        val engine = newEngine(bridge)
        engine.start(config())
        val caller = UnconfinedTestDispatcher(testScheduler)

        bridge.callHandler = { envelopeOf(operation = "op-wait") }
        val call = async(caller) { runCatching { engine.call(cmd("bootstrap")) } }

        // 另一个调用触发 panic → Fatal，等待中的调用必须被唤醒而不是干等宿主超时
        bridge.callHandler = {
            """{"ok":false,"error":{"kind":"panic","message":"boom"},"events":[]}"""
        }
        runCatching { engine.pokeRelay() }

        val failure = call.await().exceptionOrNull()
        assertTrue("等待中的调用应被唤醒：$failure", failure is EngineException)
        assertTrue(engine.state.value is EngineState.Fatal)
    }

    // ----------------------------------------------------------------------
    // dispose / 事件流
    // ----------------------------------------------------------------------

    @Test
    fun disposeStopsTimersAndIsIdempotent() = runTest {
        val bridge = FakeNativeBridge()
        bridge.createResponse = envelopeOf(
            handle = 1,
            events = listOf(
                """{"type":"relay_timer_start","timer":"heartbeat","delayMs":1000,"repeat":true}""",
            ),
        )
        val engine = newEngine(bridge)
        engine.start(config())

        advanceTimeBy(1_001)
        assertTrue(
            bridge.stepInputs().contains("""{"type":"relay_timer_fired","timer":"heartbeat"}"""),
        )

        engine.dispose()

        assertEquals(EngineState.Disposed, engine.state.value)
        assertEquals(1, bridge.methodCount("dispose"))
        assertEquals(1L, bridge.invocations.first { it.method == "dispose" }.handle)

        val stepsBefore = bridge.methodCount("step")
        advanceTimeBy(120_000)
        assertEquals(stepsBefore, bridge.methodCount("step"))

        engine.dispose()
        assertEquals(1, bridge.methodCount("dispose"))
    }

    @Test
    fun disposeWakesUpWaitingCallers() = runTest {
        val bridge = FakeNativeBridge()
        val engine = newEngine(bridge)
        engine.start(config())
        val caller = UnconfinedTestDispatcher(testScheduler)

        bridge.callHandler = { envelopeOf(operation = "op-wait") }
        val call = async(caller) { runCatching { engine.call(cmd("bootstrap")) } }

        engine.dispose()

        val failure = call.await().exceptionOrNull()
        assertTrue(failure is EngineException)
        assertEquals("invalidState", (failure as EngineException).error.kind)
    }

    @Test
    fun eventsFlowMirrorsEverything() = runTest {
        val bridge = FakeNativeBridge()
        bridge.createResponse = envelopeOf(
            handle = 1,
            events = listOf(
                """{"type":"relay_state","state":"paired"}""",
                """{"type":"log","line":"hello"}""",
            ),
        )
        val engine = newEngine(bridge)

        val seen = mutableListOf<EngineEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.events.collect { seen += it }
        }

        engine.start(config())
        advanceUntilIdle()

        assertTrue(seen.any { it is EngineEvent.RelayStateChanged })
        assertTrue(seen.any { it is EngineEvent.Log })
    }

    @Test
    fun logsAreBounded() = runTest {
        val bridge = FakeNativeBridge()
        val engine = newEngine(bridge)
        engine.start(config())

        bridge.callHandler = {
            envelopeOf(
                events = (1..800).map { """{"type":"log","line":"line-$it"}""" },
            )
        }
        engine.pokeRelay()

        // 环形缓冲有上限：调试页不会无限增长
        assertTrue("日志应有上限，实际 ${engine.logs.value.size}", engine.logs.value.size <= 500)
        assertEquals("line-800", engine.logs.value.last())
        assertFalse(engine.logs.value.contains("line-1"))
    }
}
