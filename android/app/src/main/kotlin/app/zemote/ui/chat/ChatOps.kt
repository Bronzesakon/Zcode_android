package app.zemote.ui.chat

import app.zemote.engine.EngineException
import app.zemote.engine.NativeError
import app.zemote.ui.EnginePort
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 对话层能力的**唯一入口**（`docs/FFI.md` §13.2 的 `conversation_call` 透传面）。
 *
 * ## 为什么是一条 `op` 透传，而不是 `sendText()` / `setModel()` 一堆方法
 *
 * 对话层的 `op` 有几十个（发消息、建会话、切模型、订阅、行区间、附件分块、CAS 重试……），
 * 清单以 Rust 实现为准（`docs/FFI.md` §13.5.1）。逐个展开成 Kotlin 方法等于把协议知识搬进
 * 表现层——正是这次重写要避免的。所以这里只留一个信封：`op` 名字 + `args` 对象，
 * **表现层不理解它们**。
 *
 * ## 会话/key 由实现内部持有（接口上不出现）
 *
 * `conversation_call` 需要 `session` 与 `key`，但 [ChatViewModel] 只调 `call(op, args)`。
 * 加参数会改到 ViewModel（本次改动范围之外），所以这两个值由 [EngineChatOps] **自己**持有：
 * 第一次 `call` 时惰性 `conversation_open`，之后按 `session` 缓存（换桥会话自动重开）。
 */
interface ChatOps {

    /**
     * 透传一条对话层操作。
     *
     * @param op 操作名（`"sendText"` 等；取值以 Rust 侧为准，未知 op 会回 `badInput`）。
     * @param args 操作参数，原样透传。
     * @param scope `conversation_open` 的 scope（`{workspacePath, workspaceIdentity?}`）。
     *   `null` = 用实现里的默认闭包（仅为兼容旧调用点；生产调用点应显式传）。
     * @throws app.zemote.engine.EngineException 引擎侧失败（`badInput` / `timeout` / `invalidState` …）。
     */
    suspend fun call(op: String, args: JsonObject, scope: JsonObject? = null): JsonElement

    companion object {
        /**
         * 发一条用户消息。
         *
         * 名字与 `args` 形状取自 `docs/FFI.md` §13.5.1 的 `sendText` 行：
         * `sendText` 的必填键是 `sessionId` 与 `text`。**`sessionId` 由 Rust 侧从 `args` 里取**，
         * 界面层拿不到会话 id，所以这里只发 `{"text": ...}`——缺 `sessionId` 时 Rust 回
         * `badInput`（明确失败，不会静默发错会话）。
         */
        const val OP_SEND_TEXT: String = "sendText"

        /** `sendText` 的 args 键名。 */
        const val ARG_TEXT: String = "text"
    }
}

/**
 * 对话层能力不可用。
 *
 * **§13 接线后 `EngineChatOps` 不再抛它**；保留这个类型只是因为 `ui/UiFailure.kt` 会把它
 * 映射成一句收敛文案（那是表现层的既有约定）。真正的失败路径现在走
 * [EngineException]（`invalidState` / `badInput` / `timeout`）。
 */
class ChatOpsUnavailable(message: String) : Exception(message)

/** 一次对话的引用：桥会话号 + `conversation_open` 返回的 key（`docs/FFI.md` §13.2）。 */
data class ConversationRef(val session: Long, val key: String)

/**
 * 生产实现：惰性开对话，然后把 [op] 包成 §13.2 的信封交给引擎。
 *
 * 信封逐字对应契约（`rust/crates/ffi/src/session.rs` 的 `ConversationCall` 分支）：
 * ```json
 * {"method":"conversation_call","session":1,"key":"<conversation_open 返回的 operation>",
 *  "op":"sendText","args":{...}}
 * ```
 *
 * ## 生命周期
 *
 * - `session` / `scope` 是**取当前值**的闭包（不是构造时快照）：桥会话与工作区都会变；
 * - 第一次 `call` 时 `conversation_open` 拿 key，之后缓存；缓存的 `session` 与当前不一致
 *   （换了桥）就重开——旧 conversation 归 Rust 侧的桥销毁路径回收；
 * - 并发 `call` 由 [mutex] 串行化，避免同一时刻开出两个对话。
 *
 * @param engine 引擎会话面（§13 的 `conversation_*`）。
 * @param session 取当前桥会话号（生产是 `engine.bridgeSession.value`）；`null` ⇒ 抛
 *   `invalidState`（还没开桥，对话层无从谈起）。
 * @param defaultScope 调用点没显式传 scope 时的兜底（至少含 `workspacePath`）。
 */
class EngineChatOps(
    private val engine: EnginePort,
    private val session: () -> Long? = { engine.bridgeSession.value },
    private val defaultScope: () -> JsonObject = { JsonObject(emptyMap()) },
) : ChatOps {

    private val mutex = Mutex()

    private var opened: ConversationRef? = null

    /** 上次开对话用的 scope 键；与 `session` 一起决定能否复用。 */
    private var openedScopeKey: String? = null

    override suspend fun call(op: String, args: JsonObject, scope: JsonObject?): JsonElement {
        val ref = ensureConversation(scope ?: defaultScope())
        return engine.conversationCall(
            session = ref.session,
            key = ref.key,
            op = op,
            args = args,
        )
    }

    /** 取当前对话；没有、桥换了、或换了工作区就重开。 */
    private suspend fun ensureConversation(scope: JsonObject): ConversationRef = mutex.withLock {
        val session = session() ?: throw EngineException(
            NativeError("invalidState", "还没有桥会话：先打开一个工作区再发对话操作"),
        )
        val scopeKey = conversationKeyOf(scope)
        opened?.takeIf { it.session == session && openedScopeKey == scopeKey }?.let {
            return@withLock it
        }

        val key = engine.conversationOpen(session, scope)
        openedScopeKey = scopeKey
        return@withLock ConversationRef(session = session, key = key).also { opened = it }
    }

    /**
     * 与 Rust 的 `conversation_key` 一致：`workspaceIdentity ?? workspacePath`
     * （`workspaceIdentity` 为 null/缺席时回落到路径）。
     */
    private fun conversationKeyOf(scope: JsonObject): String? =
        (scope["workspaceIdentity"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: (scope["workspacePath"] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
