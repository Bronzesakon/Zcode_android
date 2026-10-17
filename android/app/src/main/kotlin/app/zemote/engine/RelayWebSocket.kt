package app.zemote.engine

import java.util.Base64
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * `ws_connect` / `ws_send_text` / `ws_close` 三个传输动作的 OkHttp 实现（契约 §7.1）。
 *
 * 一个 [generation] 就是一条连接（= Rust 侧 `_socketGeneration`，即 socket 句柄）。
 * 三类回调会被翻译成 `nativeStep` 的输入（[SocketInputs]），翻译在 [ZemoteEngine] 里做，
 * 本类只负责「把 OkHttp 的异步回调变成一次 [Listener] 调用」。
 *
 * ## 一个 generation 只上报一次终止
 *
 * OkHttp 对同一条连接可能既调 `onFailure` 又调 `onClosed`（尤其是主动 `cancel()` 之后），
 * 而核心只期待一次终止输入。本类用 [silenced] 集合做闸门：
 *
 * - 收到新的 `ws_connect` 时，**旧代次整体静音**并 `cancel()`（契约：收到 connect 后必须停止
 *   投递旧代次的帧，否则旧 socket 会一直占资源）；
 * - 任意一条连接第一次上报终止（`onFailure` 或 `onClosed`）后就静音自己，后续回调丢弃。
 *
 * 主动 `ws_close` **不**静音：核心是故意关掉它再等 `socket_closed` 回来驱动重连
 * （`debug_drop_socket` 的演练路径）。
 *
 * ## 线程
 *
 * [Listener] 回调在 OkHttp 的 I/O 线程上触发。调用方（[ZemoteEngine]）必须把这些回调
 * 转投到引擎的专用单线程 dispatcher 上再调 native。
 */
class RelayWebSocket(
    private val open: (Request, WebSocketListener) -> WebSocket = { request, listener ->
        sharedClient.newWebSocket(request, listener)
    },
) {

    /** 传输层回调。实现方负责把回调转投到引擎线程。 */
    interface Listener {
        /** 建连成功 → 回 `{"type":"socket_ready","generation":N}`。 */
        fun onOpen(generation: Long)

        /** 收到文本帧 → 回 `{"type":"text_frame",...}`。 */
        fun onText(generation: Long, text: String)

        /** 收到二进制帧（已 base64）→ 回 `{"type":"binary_frame",...}`。 */
        fun onBinary(generation: Long, base64: String)

        /** 建连失败/连接异常 → 回 `{"type":"socket_failed","generation":N,"error":e.toString()}`。 */
        fun onFailure(generation: Long, error: String)

        /** 连接关闭 → 回 `{"type":"socket_closed","generation":N,"code":...,"reason":...}`。 */
        fun onClosed(generation: Long, code: Int, reason: String?)
    }

    private val lock = Any()

    /** 活动连接，按代次索引。 */
    private val sockets = LinkedHashMap<Long, WebSocket>()

    /** 已静音（终止已上报 / 被新代次取代）的代次集合。 */
    private val silenced = HashSet<Long>()

    /**
     * 执行 `ws_connect`：建一条新连接。
     *
     * 会先静音并 `cancel()` 所有旧代次——契约 §7.1 的 RelayTransport 文档明说「收到 connect
     * 之后，宿主必须停止向本客户端投递旧代次的帧」。
     */
    fun connect(generation: Long, uri: String, listener: Listener) {
        val superseded = synchronized(lock) {
            silenced.remove(generation)
            val old = sockets.values.toList()
            silenced.addAll(sockets.keys)
            sockets.clear()
            old
        }
        superseded.forEach { runCatching { it.cancel() } }

        val request = Request.Builder().url(uri).build()
        val webSocket = open(request, Adapter(generation, listener))
        synchronized(lock) { sockets[generation] = webSocket }
    }

    /**
     * 执行 `ws_send_text`。
     *
     * [text] 是核心**已经序列化好的紧凑 JSON**（契约 §7.1），原样发出，不重新序列化。
     */
    fun sendText(generation: Long, text: String) {
        val webSocket = synchronized(lock) { sockets[generation] } ?: return
        // send 返回 false 表示帧已排队但连接正在关闭/队列满；此时核心会通过
        // socket_closed 走重连路径，这里不额外上报。
        runCatching { webSocket.send(text) }
    }

    /**
     * 执行 `ws_close`。`code ?: 1000`、`reason ?: ""`（契约 §7.1 / §7.3 的语义）。
     *
     * 不静音该代次：核心要靠随后的 `socket_closed` 输入推进状态机。
     */
    fun close(generation: Long, code: Int?, reason: String?) {
        val webSocket = synchronized(lock) { sockets[generation] } ?: return
        val safeCode = sanitizeCloseCode(code)
        runCatching { webSocket.close(safeCode, reason ?: "") }
    }

    /** 撤销全部连接（`dispose` 用）。全部静音后再 `cancel()`，不产生终止上报。 */
    fun closeAll() {
        val all = synchronized(lock) {
            silenced.addAll(sockets.keys)
            val snapshot = sockets.values.toList()
            sockets.clear()
            snapshot
        }
        all.forEach { runCatching { it.cancel() } }
    }

    /** 当前有连接登记的代次（调试/测试用）。 */
    fun activeGenerations(): Set<Long> = synchronized(lock) { sockets.keys.toSet() }

    private inner class Adapter(
        private val generation: Long,
        private val listener: Listener,
    ) : WebSocketListener() {

        private fun isSilenced(): Boolean = synchronized(lock) { generation in silenced }

        /** 抢一次终止上报权：已静音则返回 false（迟到回调/被取代）。 */
        private fun claimTerminal(): Boolean = synchronized(lock) {
            if (generation in silenced) {
                false
            } else {
                silenced.add(generation)
                sockets.remove(generation)
                true
            }
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (isSilenced()) return
            listener.onOpen(generation)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (isSilenced()) return
            listener.onText(generation, text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (isSilenced()) return
            listener.onBinary(generation, encodeBase64(bytes.toByteArray()))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!claimTerminal()) return
            // 契约 §6：error 用 Dart 的 `e.toString()`，Kotlin 侧对应 Throwable.toString()。
            listener.onFailure(generation, t.toString())
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!claimTerminal()) return
            listener.onClosed(generation, code, reason.ifEmpty { null })
        }
    }

    companion object {
        /**
         * 进程级共享客户端：OkHttp 的连接池与 dispatcher 就是为复用设计的。
         *
         * `readTimeout` 归零——WebSocket 是长连接，读超时会把它误杀；探活由核心的
         * `pair_status_query` 心跳（10s）负责，不用 OkHttp 的 ping。
         */
        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
        }
    }
}

/**
 * OkHttp 的 `WebSocket.close(code, reason)` 只接受 `1000` 与 `3000..4999`，
 * 其它码（如 `1006`）会抛 `IllegalArgumentException`。
 *
 * 契约 §7.1 的 `code` 可空、可为任意 i32，这里收敛成合法值；`null` → `1000`。
 */
internal fun sanitizeCloseCode(code: Int?): Int = when {
    code == null -> 1000
    code == 1000 -> 1000
    code in 3000..4999 -> code
    else -> 1000
}

/** 标准 base64（**非 url-safe**，契约 §6 的 `binary_frame.base64`）。 */
internal fun encodeBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
