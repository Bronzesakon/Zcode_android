package app.zemote.ui.chat

import app.zemote.engine.EngineException
import app.zemote.ui.EnginePort
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 进程级「工作区 → 桥 + 对话」保活注册表。
 *
 * ## 为什么是进程级
 *
 * 一条工作区的对话层是**长连接上的状态**：桥会话号与 conversation key 都活在核心进程里，
 * `conversation_open` 的订阅（`subscribeSessionsIndex` 等）也只在核心侧持续收帧。把它们
 * 挂在 ViewModel 上意味着「离开页面 = 断订阅」，重新进页面就要重建桥、重开对话、重订阅，
 * 期间推送的增量全部丢失。所以持有者必须是比界面活得久的东西：本对象由应用级容器创建，
 * 生命周期 = 进程。
 *
 * ## 为什么不在 detach 时 close
 *
 * `detach`（离开页面、配置变更、ViewModel 重建）只是**界面**不再看这条工作区，不代表
 * 用户不再关心它：后台刷新、通知、返回页面后的即时内容都依赖订阅仍在。close 只应在
 * 语义上真的不需要时发生——目前唯一入口是任务删除（[close]）与进程收尾（[closeAll]）。
 * 而且 `conversation_close` 会让在飞调用只能等宿主超时（见 [EnginePort.conversationClose]），
 * 频繁开关的代价远高于保留。
 *
 * ## 并发
 *
 * [ensure] / [close] 用同一把 [Mutex] 串行化：重复或并发 ensure 只会建一次，失败的构建
 * 不会留下半成品 Handle（异常原样抛给调用方）。[handleFor] 是非挂起的热路径，读
 * [ConcurrentHashMap]，不参与加锁。
 *
 * @param engine 引擎会话面；本对象只借用它的桥/对话方法，不接管其生命周期。
 * @param scope 应用级作用域，仅用于 [closeAll] 这类非挂起收尾（它必须发起挂起的
 *   `conversationClose`）。
 */
class ConversationRegistry(
    private val engine: EnginePort,
    private val scope: CoroutineScope,
) {

    /** 一条工作区保活所需的全部身份：桥会话号 + conversation key。 */
    data class Handle(
        val workspaceKey: String,
        val bridge: Long,
        val key: String,
        /** bridge-ready 回来的权威 key（identity 归一化/项目草稿可能改变它）。 */
        val effectiveWorkspaceKey: String = workspaceKey,
    )

    private val mutex = Mutex()

    private val handles = ConcurrentHashMap<String, Handle>()

    /** 上次建缓存时的引擎会话序号；引擎重建后旧 bridge/conversation 全部失效。 */
    @Volatile
    private var sessionSerial = -1L

    /**
     * 确保 [workspaceKey] 的桥与对话已建立并订阅了 `sessionsIndex`，返回其 [Handle]。
     *
     * 幂等：已缓存直接返回，不会重开桥/重开对话。首次构建按
     * `openBridge → waitHealthy → conversationOpen → conversationCallNoWait(subscribeSessionsIndex)`
     * 顺序执行；任一步失败都向上抛，且不缓存任何中间结果。
     *
     * 注意：不调用 `sendMobileViewState`——是否把移动端焦点推给桌面由调用方按页面语义决定。
     */
    suspend fun ensure(
        workspaceKey: String,
        taskId: String?,
        conversationScope: JsonObject,
        taskHostId: String? = null,
        project: JsonObject? = null,
    ): Handle = mutex.withLock {
        if (sessionSerial != engine.sessionSerial) {
            // 引擎换了：旧句柄的 bridge/conversation 都不存在了，直接丢，不再 close。
            handles.clear()
            sessionSerial = engine.sessionSerial
        }
        handles[workspaceKey]?.let { return@withLock it }

        val bridge = engine.openBridge(
            workspaceKey = workspaceKey,
            taskId = taskId,
            taskHostId = taskHostId,
            project = project,
        )
        engine.waitHealthy(bridge)
        // `prepareProjectWorkspace` / identity 归一化后，权威 key 在 bridge-ready.bridge 里，
        // 可能与请求值不同（尤其项目草稿与远端 identity）。conversation_open 必须用权威值。
        val effectiveKey = effectiveWorkspaceKey(bridge) ?: workspaceKey
        val key = engine.conversationOpen(bridge, effectiveScope(conversationScope, effectiveKey))
        // 等订阅 ack：对端的 `helloConversationV4` 是单连接语义，多个工作区并发握手只会
        // 回一个。这里用 `conversationCall`（Rust 在 ack 后合成 host-level Completed）
        // 把「开桥 → 握手 → 订阅」串成一条，保证下一个工作区不会抢握手。
        //
        // 但订阅**失败**不等于桥不可用：`existing-only` 在没有活 runtime 时会快速 reject，
        // 此时握手其实已完成，`createSession` 仍必须能发。只有超时才说明握手没完成。
        try {
            engine.conversationCall(
                bridge,
                key,
                "subscribeSessionsIndex",
                // 核心的订阅超时是 60s；宿主只按 args.timeoutSecs 兜底，不传会在 35s 先假超时。
                buildJsonObject { put("timeoutSecs", 60) },
            )
        } catch (e: EngineException) {
            if (e.error.kind == "timeout") throw e
        }

        Handle(
            workspaceKey = workspaceKey,
            bridge = bridge,
            key = key,
            effectiveWorkspaceKey = effectiveKey,
        ).also { handles[workspaceKey] = it }
    }

    /** 已保活的句柄；`null` = 该工作区还没 [ensure] 过、已 [close]，或引擎已重建。不挂起、不加锁。 */
    fun handleFor(workspaceKey: String): Handle? {
        // 引擎重建后旧 bridge/key 都不存在了；在 ensure 之前取到旧句柄会发出必然失败的调用。
        if (sessionSerial != engine.sessionSerial) return null
        return handles[workspaceKey]
    }

    /**
     * 从核心快照读 bridge-ready 回来的权威 `workspaceKey`。
     *
     * `snapshot.bridges[]` 的 `workspaceKey` 是桥建立时对端 `bridge.workspaceKey`，即
     * `workspaceIdentity?.trim() || workspacePath` 的最终值；本地草稿/远端 prepare 后
     * 可能与请求的 key 不同。读不到（旧核心/瞬时错误）时回退到请求值。
     */
    private suspend fun effectiveWorkspaceKey(bridge: Long): String? {
        val snapshot = runCatching { engine.snapshot() }.getOrNull() as? JsonObject ?: return null
        val bridges = snapshot["bridges"] as? JsonArray ?: return null
        return bridges.firstNotNullOfOrNull { element ->
            val obj = element as? JsonObject ?: return@firstNotNullOfOrNull null
            val session = (obj["session"] as? JsonPrimitive)?.content?.toLongOrNull()
            if (session != bridge) return@firstNotNullOfOrNull null
            (obj["workspaceKey"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        }
    }

    /**
     * 用权威 key 重算 `conversation_open` 的 scope。
     *
     * - key 与请求一致：原样返回（保留调用方的 path/identity）。
     * - `remote:` 前缀：key 本身就是 identity，路径取第一个 `/` 之后的部分
     *   （identity 前缀段不含 `/`）。
     * - 其它：本地路径就是 key。
     */
    private fun effectiveScope(requested: JsonObject, effectiveKey: String): JsonObject {
        val requestedKey =
            (requested["workspaceIdentity"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: (requested["workspacePath"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (requestedKey == effectiveKey) return requested
        val slash = effectiveKey.indexOf('/')
        return if (effectiveKey.startsWith("remote:") && slash > 0) {
            buildJsonObject {
                put("workspacePath", effectiveKey.substring(slash))
                put("workspaceIdentity", effectiveKey)
            }
        } else {
            buildJsonObject { put("workspacePath", effectiveKey) }
        }
    }

    /**
     * 释放某工作区的对话并移除缓存。用于任务删除等「真的不再需要」的场景。
     *
     * 未保活时无副作用；`conversationClose` 失败会向上抛且句柄保留（下次还能重试）。
     */
    suspend fun close(workspaceKey: String) {
        mutex.withLock {
            val handle = handles[workspaceKey] ?: return@withLock
            engine.conversationClose(handle.bridge, handle.key)
            handles.remove(workspaceKey)
        }
    }

    /**
     * 遍历并释放全部句柄（进程收尾用）。
     *
     * 非挂起签名只能把工作交给 [scope]；关闭顺序与缓存顺序无关，失败会沿协程向上抛。
     */
    fun closeAll() {
        scope.launch {
            mutex.withLock {
                for (handle in handles.values.toList()) {
                    engine.conversationClose(handle.bridge, handle.key)
                    handles.remove(handle.workspaceKey, handle)
                }
            }
        }
    }
}
