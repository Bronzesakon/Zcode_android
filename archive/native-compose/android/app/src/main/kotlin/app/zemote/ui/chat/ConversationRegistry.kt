package app.zemote.ui.chat

import app.zemote.engine.EngineException
import app.zemote.log.ZLog
import app.zemote.ui.EnginePort
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
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
        /** `subscribeSessionsIndex` 是否已发过；聊天页不需要等它，首页需要。 */
        val sessionsIndexSubscribed: Boolean = false,
    )

    private val mutex = Mutex()

    private val handles = ConcurrentHashMap<String, Handle>()

    /** 同一订阅的并发等待（键 = workspaceKey + bridge + conversation key）。 */
    private val sessionsIndexAwaits = ConcurrentHashMap<String, IndexAwait>()

    /** 一次 `sessionsIndex` ack 等待：首个调用者负责订阅，其余复用结果。 */
    private class IndexAwait {
        val result = CompletableDeferred<Boolean>()
        val started = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    private fun indexAwaitKey(workspaceKey: String, bridge: Long, key: String): String =
        "$workspaceKey\u0000$bridge\u0000$key"

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
        /**
         * 是否等待 `subscribeSessionsIndex` 的 ack。
         *
         * 首页需要任务活动字段，等；聊天页只需要 conversation 本身，传 `false` 只发不等，
         * 避免把「进对话」卡在一个与内容无关的订阅上。
         */
        awaitSessionsIndex: Boolean = true,
    ): Handle {
        val startedAt = System.nanoTime()
        val seq = ZLog.nextSeq()
        ZLog.vm(
            "registry",
            "#$seq ensure begin key=${clipLog(workspaceKey)} task=${clipLog(taskId ?: "-")} " +
                "host=${clipLog(taskHostId ?: "-")} project=${if (project == null) "no" else "yes"} " +
                "awaitIndex=$awaitSessionsIndex " +
                "scopeKeys=${conversationScope.keys.sorted().joinToString(",")} engineSerial=${engine.sessionSerial}",
        )
        return try {
            val handle = ensureLocked(
                seq = seq,
                startedAt = startedAt,
                workspaceKey = workspaceKey,
                taskId = taskId,
                conversationScope = conversationScope,
                taskHostId = taskHostId,
                project = project,
                awaitSessionsIndex = awaitSessionsIndex,
            )
            // 索引订阅的 ack 必须在**锁外**等：实测锁内 await 曾把进对话卡 61s，
            // 期间所有工作区的 ensure 都被这把全局锁堵住（10 审计 P1-4）。
            if (!awaitSessionsIndex || handle.sessionsIndexSubscribed) {
                handle
            } else {
                awaitSessionsIndexOutsideLock(seq, workspaceKey, handle)
            }
        } catch (e: Throwable) {
            ZLog.vm("registry", "#$seq ensure failed ms=${elapsedMsLog(startedAt)} kind=${e.kindLabel()}", e)
            throw e
        }
    }

    /**
     * 锁外等待 `sessionsIndex` ack；成功后把「已订阅」写回注册表。
     *
     * 同一工作区/桥/对话的并发等待合并成一次 subscribe：首个把 [IndexAwait.started]
     * 从 false 翻成 true 的调用者发送命令，其余 `await()` 复用结果。等待登记发生在
     * [ensureLocked] 的锁内，所以不存在「缓存命中先看到句柄、后看到登记」的窗口。
     */
    private suspend fun awaitSessionsIndexOutsideLock(
        seq: Long,
        workspaceKey: String,
        handle: Handle,
    ): Handle {
        val waitKey = indexAwaitKey(workspaceKey, handle.bridge, handle.key)
        val wait = sessionsIndexAwaits[waitKey] ?: IndexAwait().also {
            sessionsIndexAwaits.putIfAbsent(waitKey, it)
        }
        if (!wait.started.compareAndSet(false, true)) {
            // 已有调用者在等同一个订阅：复用结果，不再重复发 subscribe。
            if (!wait.result.await()) return handle
        } else {
            try {
                val subscribed = subscribeSessionsIndex(
                    seq = seq,
                    bridge = handle.bridge,
                    key = handle.key,
                    await = true,
                )
                wait.result.complete(subscribed)
                if (!subscribed) {
                    // 失败：撤掉登记，让下一次 ensure 能重试（否则会永远停在「未订阅」）。
                    sessionsIndexAwaits.remove(waitKey, wait)
                    return handle
                }
            } catch (e: Throwable) {
                wait.result.completeExceptionally(e)
                sessionsIndexAwaits.remove(waitKey, wait)
                throw e
            }
        }
        val updated = handle.copy(sessionsIndexSubscribed = true)
        mutex.withLock {
            val current = handles[workspaceKey]
            if (current != null && current.bridge == updated.bridge && current.key == updated.key) {
                // 存的就是返回给调用方的那个实例：后续缓存命中必须拿到同一份句柄。
                handles[workspaceKey] = updated
            }
        }
        // 登记必须在句柄更新之后才撤：否则并发命中会在「已订阅但句柄还没更新」的窗口里
        // 看到空登记，再发一次 subscribe。
        sessionsIndexAwaits.remove(waitKey, wait)
        return updated
    }

    private suspend fun ensureLocked(
        seq: Long,
        startedAt: Long,
        workspaceKey: String,
        taskId: String?,
        conversationScope: JsonObject,
        taskHostId: String?,
        project: JsonObject?,
        awaitSessionsIndex: Boolean,
    ): Handle = mutex.withLock {
        if (sessionSerial != engine.sessionSerial) {
            // 引擎换了：旧句柄的 bridge/conversation 都不存在了，直接丢，不再 close。
            ZLog.vm(
                "registry",
                "#$seq engine rebuilt ${sessionSerial} → ${engine.sessionSerial}，丢弃 ${handles.size} 个句柄",
            )
            handles.clear()
            sessionSerial = engine.sessionSerial
        }
        handles[workspaceKey]?.let { existing ->
            // 锁内绝不 await 索引订阅：缓存命中直接返回，需要 ack 的调用方在锁外等。
            // 等待登记在锁内完成，后续并发命中一定能看到它（不会重复 subscribe）。
            if (awaitSessionsIndex && !existing.sessionsIndexSubscribed) {
                sessionsIndexAwaits.putIfAbsent(
                    indexAwaitKey(workspaceKey, existing.bridge, existing.key),
                    IndexAwait(),
                )
            }
            ZLog.vm(
                "registry",
                "#$seq cache hit key=${clipLog(workspaceKey)} bridge=${existing.bridge} " +
                    "key=${clipLog(existing.key)} index=${existing.sessionsIndexSubscribed} " +
                    "ms=${elapsedMsLog(startedAt)}",
            )
            return@withLock existing
        }

        val bridgeStarted = System.nanoTime()
        val bridge = engine.openBridge(
            workspaceKey = workspaceKey,
            taskId = taskId,
            taskHostId = taskHostId,
            project = project,
        )
        ZLog.vm("registry", "#$seq open_bridge done session=$bridge ms=${elapsedMsLog(bridgeStarted)}")
        val healthyStarted = System.nanoTime()
        engine.waitHealthy(bridge)
        ZLog.vm("registry", "#$seq wait_healthy done session=$bridge ms=${elapsedMsLog(healthyStarted)}")
        // `prepareProjectWorkspace` / identity 归一化后，权威 key 在 bridge-ready.bridge 里，
        // 可能与请求值不同（尤其项目草稿与远端 identity）。conversation_open 必须用权威值。
        val effectiveKey = effectiveWorkspaceKey(bridge) ?: workspaceKey
        if (effectiveKey != workspaceKey) {
            ZLog.vm(
                "registry",
                "#$seq effective key ${clipLog(workspaceKey)} → ${clipLog(effectiveKey)}",
            )
        }
        val openStarted = System.nanoTime()
        val key = engine.conversationOpen(bridge, effectiveScope(conversationScope, effectiveKey))
        ZLog.vm(
            "registry",
            "#$seq conversation_open done key=${clipLog(key)} ms=${elapsedMsLog(openStarted)}",
        )
        // 首页需要 sessions-index 的任务活动字段，等 ack；聊天页只需要 conversation
        // 本身，传 awaitSessionsIndex=false 时只发不等，避免把「进对话」卡在
        // 一个与内容无关的订阅上。失败不等于桥不可用（existing-only 会快速 reject）。
        // await=true 的等待由 [ensure] 在锁外完成，这里不阻塞全局锁。
        val sessionsIndexSubscribed = if (awaitSessionsIndex) {
            false
        } else {
            subscribeSessionsIndex(
                seq = seq,
                bridge = bridge,
                key = key,
                await = false,
            )
        }

        Handle(
            workspaceKey = workspaceKey,
            bridge = bridge,
            key = key,
            effectiveWorkspaceKey = effectiveKey,
            sessionsIndexSubscribed = sessionsIndexSubscribed,
        ).also {
            handles[workspaceKey] = it
            if (awaitSessionsIndex) {
                sessionsIndexAwaits.putIfAbsent(
                    indexAwaitKey(workspaceKey, bridge, key),
                    IndexAwait(),
                )
            }
            ZLog.vm(
                "registry",
                "#$seq ensure ready key=${clipLog(workspaceKey)} bridge=$bridge conv=${clipLog(key)} " +
                    "index=$sessionsIndexSubscribed totalMs=${elapsedMsLog(startedAt)}",
            )
        }
    }

    /**
     * 订阅 `sessionsIndex`。
     *
     * @param await `true` 等 Rust 合成的 completed（首页）；`false` 只把命令发出去（聊天）。
     * @return 是否已经发出（`await=false` 时无法确认 ack，按成功发送计）。
     * @throws EngineException `await=true` 且宿主超时；其它订阅错误吞掉，桥仍可用。
     */
    private suspend fun subscribeSessionsIndex(
        seq: Long,
        bridge: Long,
        key: String,
        await: Boolean,
    ): Boolean {
        val subscribeStarted = System.nanoTime()
        val args = buildJsonObject {
            // 核心的订阅超时是 60s；宿主只按 args.timeoutSecs 兜底，不传会在 35s 先假超时。
            put("timeoutSecs", 60)
        }
        if (!await) {
            return try {
                engine.conversationCallNoWait(bridge, key, "subscribeSessionsIndex", args)
                ZLog.vm(
                    "registry",
                    "#$seq subscribeSessionsIndex sent (no-wait) bridge=$bridge key=${clipLog(key)} " +
                        "ms=${elapsedMsLog(subscribeStarted)}",
                )
                true
            } catch (e: EngineException) {
                ZLog.vm(
                    "registry",
                    "#$seq subscribeSessionsIndex send failed kind=${e.error.kind}（吞掉，桥仍可用） " +
                        "ms=${elapsedMsLog(subscribeStarted)}",
                )
                false
            }
        }
        return try {
            engine.conversationCall(bridge, key, "subscribeSessionsIndex", args)
            ZLog.vm(
                "registry",
                "#$seq subscribeSessionsIndex ack bridge=$bridge key=${clipLog(key)} " +
                    "ms=${elapsedMsLog(subscribeStarted)}",
            )
            true
        } catch (e: EngineException) {
            if (e.error.kind == "timeout") {
                ZLog.vm(
                    "registry",
                    "#$seq subscribeSessionsIndex timeout ms=${elapsedMsLog(subscribeStarted)}（向上抛）",
                )
                throw e
            }
            ZLog.vm(
                "registry",
                "#$seq subscribeSessionsIndex failed kind=${e.error.kind}（吞掉，桥仍可用） " +
                    "ms=${elapsedMsLog(subscribeStarted)}",
            )
            false
        }
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
            ZLog.vm("registry", "close key=${clipLog(workspaceKey)} bridge=${handle.bridge} conv=${clipLog(handle.key)}")
            engine.conversationClose(handle.bridge, handle.key)
            handles.remove(workspaceKey)
            clearIndexAwaits(workspaceKey)
        }
    }

    /** 丢弃某工作区残留的索引等待登记（句柄已关闭时它们不会再有人消费）。 */
    private fun clearIndexAwaits(workspaceKey: String) {
        val prefix = "$workspaceKey\u0000"
        sessionsIndexAwaits.keys.removeIf { it.startsWith(prefix) }
    }

    /**
     * 遍历并释放全部句柄（进程收尾用）。
     *
     * 非挂起签名只能把工作交给 [scope]；关闭顺序与缓存顺序无关，失败会沿协程向上抛。
     */
    fun closeAll() {
        scope.launch {
            mutex.withLock {
                ZLog.vm("registry", "closeAll handles=${handles.size}")
                for (handle in handles.values.toList()) {
                    engine.conversationClose(handle.bridge, handle.key)
                    handles.remove(handle.workspaceKey, handle)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 日志小工具（本文件用）
// ---------------------------------------------------------------------------

private fun clipLog(text: String, max: Int = 120): String =
    if (text.length <= max) text else text.take(max) + "…"

private fun elapsedMsLog(startedAtNanos: Long): Long = (System.nanoTime() - startedAtNanos) / 1_000_000

private fun Throwable.kindLabel(): String =
    (this as? EngineException)?.error?.kind ?: javaClass.simpleName
