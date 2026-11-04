package app.zemote.ui.chat

import app.zemote.ui.EnginePort
import app.zemote.ui.FakeEnginePort
import app.zemote.ui.engineError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ConversationRegistry]：重复/并发 ensure 只建一次、close 才关对话、工作区互不干扰。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationRegistryTest {

    private val emptyScope = JsonObject(emptyMap())

    @Test
    fun repeatedEnsureBuildsOnce() = runTest {
        val port = FakeEnginePort()
        val registry = ConversationRegistry(port, this)

        val first = registry.ensure("ws", null, emptyScope)
        val second = registry.ensure("ws", null, emptyScope)

        assertSame(first, second)
        assertEquals(1, port.openedBridges.size)
        assertEquals(1, port.conversationOpens.size)
        assertEquals(1, port.conversationCalls.size)
        assertEquals("subscribeSessionsIndex", port.conversationCalls.single().op)
        assertEquals(first, registry.handleFor("ws"))
        // 不替调用方推 view state。
        assertTrue(port.pushedViewStates.isEmpty())
    }

    @Test
    fun conversationClosesOnlyOnClose() = runTest {
        val port = FakeEnginePort()
        val registry = ConversationRegistry(port, this)

        val handle = registry.ensure("ws", null, emptyScope)
        assertTrue(port.conversationCloses.isEmpty())
        assertNotNull(registry.handleFor("ws"))

        registry.close("ws")

        assertEquals(listOf(handle.bridge to handle.key), port.conversationCloses)
        assertNull(registry.handleFor("ws"))

        // 再 close 一次无副作用。
        registry.close("ws")
        assertEquals(1, port.conversationCloses.size)
    }

    @Test
    fun differentWorkspacesGetTheirOwnBridge() = runTest {
        val port = FakeEnginePort()
        port.openBridgeSession = 11L
        val registry = ConversationRegistry(port, this)

        val a = registry.ensure("ws-a", null, emptyScope)
        port.openBridgeSession = 12L
        val b = registry.ensure("ws-b", "task-1", emptyScope)

        assertEquals(
            listOf<Pair<String, String?>>("ws-a" to null, "ws-b" to "task-1"),
            port.openedBridges,
        )
        assertNotEquals(a.bridge, b.bridge)
        assertEquals(2, port.conversationOpens.size)
        assertEquals(2, port.conversationCalls.size)
        assertEquals(a, registry.handleFor("ws-a"))
        assertEquals(b, registry.handleFor("ws-b"))
    }

    @Test
    fun concurrentEnsureBuildsOnce() = runTest {
        val port = FakeEnginePort()
        // openBridge 里加一个挂起点，让 16 个 ensure 真正在互斥锁上排队。
        val registry = ConversationRegistry(SlowEngine(port), this)

        coroutineScope {
            repeat(16) {
                launch { registry.ensure("ws", null, emptyScope) }
            }
        }

        assertEquals(1, port.openedBridges.size)
        assertEquals(1, port.conversationOpens.size)
        assertEquals(1, port.conversationCalls.size)
    }

    @Test
    fun failedEnsureIsNotCachedAndCanBeRetried() = runTest {
        val port = FakeEnginePort()
        val failure = engineError()
        port.openBridgeFailure = failure
        val registry = ConversationRegistry(port, this)

        var thrown: Exception? = null
        try {
            registry.ensure("ws", null, emptyScope)
        } catch (e: Exception) {
            thrown = e
        }

        assertSame(failure, thrown)
        assertNull(registry.handleFor("ws"))

        port.openBridgeFailure = null
        val handle = registry.ensure("ws", null, emptyScope)
        assertEquals(handle, registry.handleFor("ws"))
        assertEquals(1, port.conversationOpens.size)
    }

    @Test
    fun closeAllClosesEveryHandle() = runTest {
        val port = FakeEnginePort()
        port.openBridgeSession = 21L
        val registry = ConversationRegistry(port, this)

        val a = registry.ensure("ws-a", null, emptyScope)
        port.openBridgeSession = 22L
        val b = registry.ensure("ws-b", null, emptyScope)

        registry.closeAll()
        advanceUntilIdle()

        assertEquals(
            setOf(a.bridge to a.key, b.bridge to b.key),
            port.conversationCloses.toSet(),
        )
        assertNull(registry.handleFor("ws-a"))
        assertNull(registry.handleFor("ws-b"))
    }

    /** bridge-ready 的权威 key 与请求不同时，`conversation_open` 必须用权威 scope。 */
    @Test
    fun ensureUsesAuthoritativeBridgeKeyForScope() = runTest {
        val port = FakeEnginePort()
        port.snapshotResult = Json.parseToJsonElement(
            """{"bridges":[{"session":${port.openBridgeSession},"workspaceKey":"remote:server:s1:/repo"}]}""",
        )
        val registry = ConversationRegistry(port, this)
        val scope = buildJsonObject {
            put("workspacePath", "/repo")
            put("workspaceIdentity", "remote:server:s1:/repo")
        }

        registry.ensure("ws", null, scope)

        val opened = port.conversationOpens.single().second
        assertEquals("/repo", opened["workspacePath"]!!.jsonPrimitive.content)
        assertEquals(
            "remote:server:s1:/repo",
            opened["workspaceIdentity"]!!.jsonPrimitive.content,
        )
    }

    /** 引擎重建后旧句柄不能再被使用（否则会对着已销毁的桥发命令）。 */
    @Test
    fun handleForIgnoresStaleEngineSession() = runTest {
        val inner = FakeEnginePort()
        val engine = object : EnginePort by inner {
            var serial = 0L
            override val sessionSerial: Long get() = serial
        }
        val registry = ConversationRegistry(engine, this)
        registry.ensure("ws", null, emptyScope)
        assertNotNull(registry.handleFor("ws"))

        engine.serial = 1L
        assertNull(registry.handleFor("ws"))
    }

    /** 给 `openBridge` 注入一个挂起点，用来验证并发 ensure 的互斥。 */
    private class SlowEngine(private val inner: FakeEnginePort) : EnginePort by inner {
        override suspend fun openBridge(
            workspaceKey: String,
            taskId: String?,
            taskHostId: String?,
            project: JsonObject?,
        ): Long {
            delay(10)
            return inner.openBridge(workspaceKey, taskId, taskHostId, project)
        }
    }
}
