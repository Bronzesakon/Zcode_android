package app.zemote.engine

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ws_*` 传输动作（契约 §7.1）的 OkHttp 适配。
 *
 * 用注入的 opener 拿到 OkHttp 的 [WebSocketListener] 并手动触发回调——不碰网络、不需要
 * MockWebServer。重点覆盖两件容易出错的事：
 *
 * 1. 一个 generation **只上报一次终止**（OkHttp 可能 `onFailure` 与 `onClosed` 都回调）；
 * 2. 收到新的 `ws_connect` 后旧代次**必须静音**（契约 §7.1 的 RelayTransport 文档）。
 */
class RelayWebSocketTest {

    /** OkHttp 4.x 的 [WebSocket] 是 Java 接口，故 `request()` / `queueSize()` 是方法而非属性。 */
    private class FakeSocket(private val request: Request) : WebSocket {
        var closedWith: Pair<Int, String?>? = null
        var cancelled = false
        val sent = mutableListOf<String>()

        override fun request(): Request = request

        override fun queueSize(): Long = 0L

        override fun send(text: String): Boolean {
            sent += text
            return true
        }

        override fun send(bytes: ByteString): Boolean = true

        override fun close(code: Int, reason: String?): Boolean {
            closedWith = code to reason
            return true
        }

        override fun cancel() {
            cancelled = true
        }
    }

    private class RecordingListener : RelayWebSocket.Listener {
        val calls = mutableListOf<String>()

        override fun onOpen(generation: Long) {
            calls += "open:$generation"
        }

        override fun onText(generation: Long, text: String) {
            calls += "text:$generation:$text"
        }

        override fun onBinary(generation: Long, base64: String) {
            calls += "binary:$generation:$base64"
        }

        override fun onFailure(generation: Long, error: String) {
            calls += "failure:$generation:$error"
        }

        override fun onClosed(generation: Long, code: Int, reason: String?) {
            calls += "closed:$generation:$code:$reason"
        }
    }

    private class Harness {
        val listeners = mutableListOf<WebSocketListener>()
        val sockets = mutableListOf<FakeSocket>()
        val relay = RelayWebSocket { request, listener ->
            listeners += listener
            FakeSocket(request).also { sockets += it }
        }
    }

    private fun response(request: Request): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(101)
        .message("Switching Protocols")
        .build()

    @Test
    fun openAndMessagesMapToListenerCalls() {
        val h = Harness()
        val listener = RecordingListener()
        h.relay.connect(1, "wss://example.invalid/ws?mid=abc", listener)

        val okHttpListener = h.listeners.single()
        val socket = h.sockets.single()
        assertEquals(
            // OkHttp 的 Request.Builder 会把 ws:/wss: 静默改写成 http:/https:（它只认 HttpUrl）。
            // 对建连无影响：newWebSocket 用这个 https URL 走 TLS + Upgrade。
            "https://example.invalid/ws?mid=abc",
            socket.request().url.toString(),
        )

        okHttpListener.onOpen(socket, response(socket.request()))
        okHttpListener.onMessage(socket, """{"type":"auth_challenge","nonce":"n1"}""")
        okHttpListener.onMessage(socket, byteArrayOf(1, 2, 3).toByteString())

        assertEquals(
            listOf(
                "open:1",
                """text:1:{"type":"auth_challenge","nonce":"n1"}""",
                // 二进制帧按契约 §6 走 base64，不预解码
                "binary:1:AQID",
            ),
            listener.calls,
        )
    }

    @Test
    fun onlyOneTerminalReportPerGenerationFailureThenClose() {
        val h = Harness()
        val listener = RecordingListener()
        h.relay.connect(1, "wss://example.invalid/ws", listener)

        val okHttpListener = h.listeners.single()
        val socket = h.sockets.single()

        okHttpListener.onOpen(socket, response(socket.request()))
        // OkHttp 在连接异常后可能两个回调都来；核心只期待一次终止输入
        okHttpListener.onFailure(socket, java.net.ConnectException("refused"), null)
        okHttpListener.onClosed(socket, 1006, "")

        assertEquals(
            listOf("open:1", "failure:1:java.net.ConnectException: refused"),
            listener.calls,
        )
        assertTrue(h.relay.activeGenerations().isEmpty())
    }

    @Test
    fun onlyOneTerminalReportPerGenerationCloseThenFailure() {
        val h = Harness()
        val listener = RecordingListener()
        h.relay.connect(1, "wss://example.invalid/ws", listener)

        val okHttpListener = h.listeners.single()
        val socket = h.sockets.single()

        // 空 reason 归一成 null（契约 §7.3 的示例里 reason 可为 null）
        okHttpListener.onClosed(socket, 1000, "")
        okHttpListener.onFailure(socket, RuntimeException("late"), null)

        assertEquals(listOf("closed:1:1000:null"), listener.calls)
    }

    @Test
    fun lateFramesOfASupersededGenerationAreDropped() {
        val h = Harness()
        val first = RecordingListener()
        val second = RecordingListener()

        h.relay.connect(1, "wss://example.invalid/ws", first)
        val firstOkHttp = h.listeners[0]
        val firstSocket = h.sockets[0]
        firstOkHttp.onOpen(firstSocket, response(firstSocket.request()))

        // 新代次到来：旧 socket 必须被 cancel 且不再投递任何帧
        h.relay.connect(2, "wss://example.invalid/ws", second)
        assertTrue("旧 socket 必须被 cancel", firstSocket.cancelled)

        val secondOkHttp = h.listeners[1]
        val secondSocket = h.sockets[1]

        firstOkHttp.onMessage(firstSocket, "late frame")
        firstOkHttp.onClosed(firstSocket, 1006, "gone")
        secondOkHttp.onOpen(secondSocket, response(secondSocket.request()))
        secondOkHttp.onMessage(secondSocket, "fresh frame")

        assertEquals(listOf("open:1"), first.calls)
        assertEquals(listOf("open:2", "text:2:fresh frame"), second.calls)
    }

    @Test
    fun sendTextPassesTheFrameVerbatim() {
        val h = Harness()
        h.relay.connect(1, "wss://example.invalid/ws", RecordingListener())
        val socket = h.sockets.single()

        // 契约 §7.1：核心给的是已序列化好的紧凑 JSON，宿主直接发，不重新序列化
        val text = """{"type":"auth_init","device_sid":"sid","meta":{"platform":"android"}}"""
        h.relay.sendText(1, text)

        assertEquals(listOf(text), socket.sent)
        // 未知代次是 no-op，不抛
        h.relay.sendText(99, "ignored")
        assertEquals(listOf(text), socket.sent)
    }

    @Test
    fun closeUsesDefaultAndSanitizedCodes() {
        val h = Harness()
        h.relay.connect(1, "wss://example.invalid/ws", RecordingListener())
        val socket = h.sockets.single()

        // 契约 §7.1：code/reason 可为 null → 1000 / ""
        h.relay.close(1, null, null)
        assertEquals(1000 to "", socket.closedWith)

        h.relay.close(1, 3000, "debug-drop")
        assertEquals(3000 to "debug-drop", socket.closedWith)

        // 1006（异常关闭）不是 OkHttp close() 的合法码，收敛成 1000 而不是抛异常
        h.relay.close(1, 1006, "abnormal")
        assertEquals(1000 to "abnormal", socket.closedWith)

        // 未知代次是 no-op
        h.relay.close(99, 3000, "nope")
        assertEquals(1000 to "abnormal", socket.closedWith)
    }

    @Test
    fun closeDoesNotSilenceTheGeneration() {
        val h = Harness()
        val listener = RecordingListener()
        h.relay.connect(1, "wss://example.invalid/ws", listener)
        val okHttpListener = h.listeners.single()
        val socket = h.sockets.single()

        okHttpListener.onOpen(socket, response(socket.request()))
        // 核心主动关连接（debug-drop 演练）：随后的 socket_closed 必须照常上报，
        // 核心靠它推进重连状态机
        h.relay.close(1, 3000, "debug-drop")
        okHttpListener.onClosed(socket, 3000, "debug-drop")

        assertEquals(listOf("open:1", "closed:1:3000:debug-drop"), listener.calls)
    }

    @Test
    fun closeAllCancelsAndSilences() {
        val h = Harness()
        val listener = RecordingListener()
        h.relay.connect(1, "wss://example.invalid/ws", listener)
        val okHttpListener = h.listeners.single()
        val socket = h.sockets.single()

        h.relay.closeAll()

        assertTrue(socket.cancelled)
        assertTrue(h.relay.activeGenerations().isEmpty())
        okHttpListener.onFailure(socket, RuntimeException("after closeAll"), null)
        assertTrue("closeAll 之后不得再上报终止：${listener.calls}", listener.calls.isEmpty())
    }
}
