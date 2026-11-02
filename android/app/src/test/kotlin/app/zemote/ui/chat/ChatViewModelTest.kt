package app.zemote.ui.chat

import app.zemote.engine.EngineEvent
import app.zemote.engine.EngineState
import app.zemote.engine.RelayState
import app.zemote.ui.FakeAuthStore
import app.zemote.ui.FakeChatOps
import app.zemote.ui.FakeDeviceRepository
import app.zemote.ui.FakeEnginePort
import app.zemote.ui.MainDispatcherRule
import app.zemote.ui.deviceRecord
import app.zemote.ui.engineError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 对话页：进页引导、工作区投影、桥操作、对话事件流、以及**用假 [ChatOps]** 跑通的发送路径。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    /**
     * 权威形状（对端 3.15.3）：`workspaces` 8 键、`tasks` 是**顶层兄弟数组**、
     * `workspaceIdentity` 有值才出现。键规则 `identity?.trim() || path`。
     */
    private fun workspacesJson(): JsonElement = Json.parseToJsonElement(
        """
        {"workspaces":[
          {"workspacePath":"/home/u/proj","workspaceIdentity":"id-proj","label":"主工作区","kind":"remote",
           "connectionState":"connected"},
          {"workspacePath":"/home/u/backup","label":"备用","kind":"local"}
        ],
        "tasks":[
          {"taskId":"t1","title":"修 bug","workspacePath":"/home/u/proj","workspaceIdentity":"id-proj",
           "displayStatus":"running","updatedAt":123,"unreadAt":456}
        ],
        "activeWorkspaceKey":"id-proj","activeTaskId":"t1"}
        """.trimIndent(),
    )

    private fun viewModel(
        repository: FakeDeviceRepository = FakeDeviceRepository(initialDevices = listOf(deviceRecord())),
        engine: FakeEnginePort = FakeEnginePort(),
        authStore: FakeAuthStore = FakeAuthStore(),
        chatOps: FakeChatOps = FakeChatOps(),
        deviceId: String = "d1",
        workspaceKey: String? = null,
        taskId: String? = null,
    ) = ChatViewModel(
        repository = repository,
        engine = engine,
        authStore = authStore,
        chatOps = chatOps,
        deviceId = deviceId,
        initialWorkspaceKey = workspaceKey,
        initialTaskId = taskId,
    )

    // ------------------------------------------------------------------
    // 进页引导
    // ------------------------------------------------------------------

    @Test
    fun `设备不存在时给出错误且不建会话`() = runTest(mainRule.dispatcher) {
        val engine = FakeEnginePort()
        val model = viewModel(engine = engine, deviceId = "不存在")
        advanceUntilIdle()

        model.onEnter()
        advanceUntilIdle()

        assertEquals("设备不存在（可能已被删除）", model.state.value.error)
        assertTrue(engine.ensuredUrls.isEmpty())
    }

    @Test
    fun `凭据不可用时提示重新配对且不建会话`() = runTest(mainRule.dispatcher) {
        val repository = FakeDeviceRepository(
            initialDevices = listOf(deviceRecord(remoteUrl = null)),
        )
        val engine = FakeEnginePort()
        val model = viewModel(repository = repository, engine = engine)
        advanceUntilIdle()

        model.onEnter()
        advanceUntilIdle()

        assertTrue(model.state.value.credentialsMissing)
        assertTrue(model.state.value.error!!.contains("需要重新配对"))
        assertTrue(engine.ensuredUrls.isEmpty())
    }

    @Test
    fun `进页会建会话并加载工作区`() = runTest(mainRule.dispatcher) {
        val engine = FakeEnginePort().apply { bootstrapResult = workspacesJson() }
        val model = viewModel(engine = engine)
        advanceUntilIdle()

        model.onEnter()
        advanceUntilIdle()

        assertEquals(listOf(deviceRecord().remoteUrl), engine.ensuredUrls)
        assertFalse(model.state.value.loading)
        assertNull(model.state.value.error)

        val workspaces = model.state.value.workspaces
        // 键规则：identity 优先，没有 identity 才回落到路径。
        assertEquals(listOf("id-proj", "/home/u/backup"), workspaces.map { it.key })
        assertEquals("主工作区", workspaces[0].name)
        assertEquals("/home/u/proj", workspaces[0].path)
        assertEquals("id-proj", workspaces[0].identity)
        assertEquals("remote", workspaces[0].kind)
        assertEquals("connected", workspaces[0].connectionState)
        // 3.16.1 的 bootstrap 不带任务；任务由 sessions-index 订阅提供，这里应为空。
        assertEquals(0, workspaces[0].taskCount)
        // 没有 label 的工作区用路径末段兜底
        assertEquals("备用", workspaces[1].name)
        assertEquals(0, workspaces[1].taskCount)
    }

    @Test
    fun `重复 onEnter 只生效一次`() = runTest(mainRule.dispatcher) {
        val engine = FakeEnginePort()
        val model = viewModel(engine = engine)
        advanceUntilIdle()

        model.onEnter()
        model.onEnter()
        model.onEnter()
        advanceUntilIdle()

        assertEquals(1, engine.ensuredUrls.size)
    }

    @Test
    fun `路由带的工作区在进页时自动打开`() = runTest(mainRule.dispatcher) {
        val engine = FakeEnginePort()
        val model = viewModel(engine = engine, workspaceKey = "w1", taskId = "t9")
        advanceUntilIdle()

        model.onEnter()
        advanceUntilIdle()

        assertEquals(listOf("w1" to "t9"), engine.openedBridges)
        assertEquals(listOf("w1" to "t9"), engine.pushedViewStates)
        assertEquals(7L, model.state.value.bridgeSession)
        assertEquals("w1", model.state.value.selectedWorkspaceKey)
        assertEquals("t9", model.state.value.selectedTaskId)
    }

    @Test
    fun `引擎状态与桥状态映射进状态`() = runTest(mainRule.dispatcher) {
        val engine = FakeEnginePort()
        val model = viewModel(engine = engine)
        advanceUntilIdle()

        engine.engineStateFlow.value = EngineState.Fatal("Rust 核心发生 panic", "内部路径")
        engine.relayStateFlow.value = RelayState.Reconnecting
        engine.bridgeSessionFlow.value = 42L
        advanceUntilIdle()

        assertEquals(EngineState.Fatal("Rust 核心发生 panic", "内部路径"), model.state.value.engineState)
        assertEquals(RelayState.Reconnecting, model.state.value.relayState)
        // 全局 `bridgeSession` 是「最近一次 openBridge」，可能是别的页面/工作区的桥；
        // 本页身份只能由 ensureRouteBridge 设置，不能被它覆盖（否则事件门禁会丢帧）。
        assertNull(model.state.value.bridgeSession)
    }

    // ------------------------------------------------------------------
    // 加载与失败三态
    // ------------------------------------------------------------------

    @Test
    fun `刷新失败收敛成错误态并结束加载`() = runTest(mainRule.dispatcher) {
        val engine = FakeEnginePort().apply { bootstrapFailure = engineError("timeout", "操作超时") }
        val model = viewModel(engine = engine)
        advanceUntilIdle()

        model.onEnter()
        advanceUntilIdle()

        assertEquals("操作超时：操作超时", model.state.value.error)
        assertFalse(model.state.value.loading)
    }

    @Test
    fun `等配对失败收敛成错误态`() = runTest(mainRule.dispatcher) {
        val engine = FakeEnginePort().apply { waitPairedFailure = engineError("timeout", "等配对超时") }
        val model = viewModel(engine = engine)
        advanceUntilIdle()

        model.onEnter()
        advanceUntilIdle()

        assertEquals("操作超时：等配对超时", model.state.value.error)
    }

    @Test
    fun `打开工作区失败收敛成错误态`() = runTest(mainRule.dispatcher) {
        val engine = FakeEnginePort().apply { openBridgeFailure = engineError("badInput", "未知工作区") }
        val model = viewModel(engine = engine)
        advanceUntilIdle()

        model.onEnter()
        advanceUntilIdle()
        model.openWorkspace("w1")
        advanceUntilIdle()

        assertEquals("参数被引擎拒绝：未知工作区", model.state.value.error)
        assertFalse(model.state.value.loading)
    }

    @Test
    fun `dismissError 清掉错误条`() = runTest(mainRule.dispatcher) {
        val engine = FakeEnginePort().apply { bootstrapFailure = engineError() }
        val model = viewModel(engine = engine)
        advanceUntilIdle()
        model.onEnter()
        advanceUntilIdle()
        assertTrue(model.state.value.error != null)

        model.dismissError()

        assertNull(model.state.value.error)
    }

    // ------------------------------------------------------------------
    // 发送（假 ChatOps）
    // ------------------------------------------------------------------

    @Test
    fun `发送文本走 ChatOps 的 sendText 且 args 带 text`() = runTest(mainRule.dispatcher) {
        val chatOps = FakeChatOps().apply { result = JsonPrimitive("ok") }
        val model = viewModel(chatOps = chatOps)
        advanceUntilIdle()

        // `sendText` 的 `sessionId` 是必填；协议里 `taskId === sessionId`。
        model.selectTask("t1")
        model.sendText("  你好  ")
        advanceUntilIdle()

        assertEquals(1, chatOps.calls.size)
        assertEquals(ChatOps.OP_SEND_TEXT, chatOps.calls[0].first)
        assertEquals("t1", chatOps.calls[0].second["sessionId"]!!.jsonPrimitive.content)
        assertEquals("你好", chatOps.calls[0].second["text"]!!.jsonPrimitive.content)
        // 3.17.1 的 sendText 强制带 mode/planEnabled（没有 config 时默认 build/false）。
        assertEquals("build", chatOps.calls[0].second["mode"]!!.jsonPrimitive.content)
        assertFalse(chatOps.calls[0].second["planEnabled"]!!.jsonPrimitive.boolean)
        assertFalse(model.state.value.sending)
        assertNull(model.state.value.error)
    }

    @Test
    fun `发送失败把文本回填输入框`() = runTest(mainRule.dispatcher) {
        val chatOps = FakeChatOps().apply { failure = engineError("timeout", "操作超时") }
        val model = viewModel(chatOps = chatOps)
        advanceUntilIdle()

        model.selectTask("t1")
        model.sendText("  你好  ")
        advanceUntilIdle()

        // 界面在点击发送时已清空输入框；失败必须由 ViewModel 交回文本。
        assertEquals("你好", model.state.value.draftRestore)
        assertFalse(model.state.value.sending)

        model.consumeDraftRestore()
        assertNull(model.state.value.draftRestore)
    }

    @Test
    fun `没有选中任务时走新建会话，工作区未就绪则明确报错`() = runTest(mainRule.dispatcher) {
        val chatOps = FakeChatOps()
        val model = viewModel(chatOps = chatOps)
        advanceUntilIdle()

        model.sendText("你好")
        advanceUntilIdle()

        assertTrue(chatOps.calls.isEmpty())
        assertEquals("工作区还没就绪，请稍后再试", model.state.value.error)
    }

    @Test
    fun `新建会话用 workspaceKey 兜底，首条消息走 sendText`() = runTest(mainRule.dispatcher) {
        val engine = FakeEnginePort().apply { bootstrapResult = workspacesJson() }
        val chatOps = FakeChatOps().apply { result = JsonPrimitive("new-1") }
        val model = viewModel(
            engine = engine,
            chatOps = chatOps,
            workspaceKey = "id-proj",
            taskId = null,
        )
        advanceUntilIdle()
        model.onEnter()
        advanceUntilIdle()

        model.sendText("hi")
        advanceUntilIdle()

        val create = chatOps.calls.firstOrNull { it.first == "createSession" }
        assertTrue("应发送 createSession，实际 ${chatOps.calls.map { it.first }}", create != null)
        // 3.17.1 预热路径用 workspaceKey（identity 优先），不是裸路径。
        assertEquals("id-proj", create!!.second["workspaceId"]!!.jsonPrimitive.content)
        // 首条消息不随 createSession 提交，而是等拿到 sessionId 后单独 sendText。
        assertFalse(create.second.containsKey("firstText"))
        assertEquals(90L, create.second["timeoutSecs"]!!.jsonPrimitive.long)
        val send = chatOps.calls.lastOrNull { it.first == ChatOps.OP_SEND_TEXT }
        assertTrue("应发送 sendText，实际 ${chatOps.calls.map { it.first }}", send != null)
        assertEquals("new-1", send!!.second["sessionId"]!!.jsonPrimitive.content)
        assertEquals("hi", send.second["text"]!!.jsonPrimitive.content)
        assertNull(model.state.value.error)
    }

    @Test
    fun `对话层未接线时给出准确文案`() = runTest(mainRule.dispatcher) {
        val chatOps = FakeChatOps().apply {
            failure = ChatOpsUnavailable("对话层尚未接线（docs/FFI.md §13 待实现）：还没有 key")
        }
        val model = viewModel(chatOps = chatOps)
        advanceUntilIdle()

        model.selectTask("t1")
        model.sendText("你好")
        advanceUntilIdle()

        assertEquals(
            "对话层尚未接线（docs/FFI.md §13 待实现）：还没有 key",
            model.state.value.error,
        )
        assertFalse(model.state.value.sending)
    }

    @Test
    fun `空白文本不发送`() = runTest(mainRule.dispatcher) {
        val chatOps = FakeChatOps()
        val model = viewModel(chatOps = chatOps)
        advanceUntilIdle()

        model.sendText("   ")
        model.sendText("")
        advanceUntilIdle()

        assertTrue(chatOps.calls.isEmpty())
    }

    @Test
    fun `引擎失败时发送收敛成错误态`() = runTest(mainRule.dispatcher) {
        val chatOps = FakeChatOps().apply { failure = engineError("invalidState", "引擎尚未启动") }
        val model = viewModel(chatOps = chatOps)
        advanceUntilIdle()

        model.selectTask("t1")
        model.sendText("你好")
        advanceUntilIdle()

        assertEquals("当前状态不允许这个操作：引擎尚未启动", model.state.value.error)
    }

    @Test
    fun `callOp 失败返回 null 并写错误`() = runTest(mainRule.dispatcher) {
        val chatOps = FakeChatOps().apply { failure = engineError("badInput", "未知 op") }
        val model = viewModel(chatOps = chatOps)
        advanceUntilIdle()

        val result = model.callOp("whatever", buildJsonObject { put("a", 1) })

        assertNull(result)
        assertEquals("参数被引擎拒绝：未知 op", model.state.value.error)
    }

    @Test
    fun `callOp 成功返回引擎给的 payload`() = runTest(mainRule.dispatcher) {
        val chatOps = FakeChatOps().apply { result = JsonPrimitive("done") }
        val model = viewModel(chatOps = chatOps)
        advanceUntilIdle()

        val result = model.callOp("whatever", buildJsonObject { })

        assertEquals(JsonPrimitive("done"), result)
    }

    // ------------------------------------------------------------------
    // 对话事件流
    // ------------------------------------------------------------------

    @Test
    fun `对话事件进流并带上类型与原始体`() = runTest(mainRule.dispatcher) {
        val engine = FakeEnginePort()
        val model = viewModel(engine = engine)
        advanceUntilIdle()

        engine.eventsFlow.emit(
            EngineEvent.ConversationEvent(
                session = 7,
                key = "k1",
                event = Json.parseToJsonElement("""{"type":"rows_updated","rows":[]}"""),
            ),
        )
        advanceUntilIdle()

        val events = model.state.value.conversationEvents
        assertEquals(1, events.size)
        assertEquals("rows_updated", events[0].type)
        assertEquals(7L, events[0].session)
        assertEquals("k1", events[0].key)
        assertEquals(1L, events[0].seq)
    }

    @Test
    fun `没有 type 的事件也不炸`() = runTest(mainRule.dispatcher) {
        val engine = FakeEnginePort()
        val model = viewModel(engine = engine)
        advanceUntilIdle()

        engine.eventsFlow.emit(
            EngineEvent.ConversationEvent(session = 1, key = "k", event = JsonPrimitive("裸字符串")),
        )
        advanceUntilIdle()

        assertEquals("", model.state.value.conversationEvents[0].type)
    }

    @Test
    fun `对话事件流有上限 只保留最近的`() = runTest(mainRule.dispatcher) {
        val engine = FakeEnginePort()
        val model = viewModel(engine = engine)
        advanceUntilIdle()

        repeat(ChatViewModel.MAX_CONVERSATION_EVENTS + 5) { index ->
            engine.eventsFlow.emit(
                EngineEvent.ConversationEvent(
                    session = 1,
                    key = "k",
                    event = buildJsonObject { put("type", "e$index") },
                ),
            )
        }
        advanceUntilIdle()

        val events = model.state.value.conversationEvents
        assertEquals(ChatViewModel.MAX_CONVERSATION_EVENTS, events.size)
        assertEquals("e5", events.first().type)
        assertEquals("e${ChatViewModel.MAX_CONVERSATION_EVENTS + 4}", events.last().type)
    }

    @Test
    fun `clearConversationEvents 清空事件流`() = runTest(mainRule.dispatcher) {
        val engine = FakeEnginePort()
        val model = viewModel(engine = engine)
        advanceUntilIdle()
        engine.eventsFlow.emit(
            EngineEvent.ConversationEvent(session = 1, key = "k", event = JsonPrimitive("x")),
        )
        advanceUntilIdle()
        assertTrue(model.state.value.conversationEvents.isNotEmpty())

        model.clearConversationEvents()

        assertTrue(model.state.value.conversationEvents.isEmpty())
    }

    @Test
    fun `workspace_list_updated 事件替换工作区投影`() = runTest(mainRule.dispatcher) {
        val engine = FakeEnginePort()
        val model = viewModel(engine = engine)
        advanceUntilIdle()

        engine.eventsFlow.emit(EngineEvent.WorkspaceListUpdated(workspacesJson()))
        advanceUntilIdle()

        assertEquals(
            listOf("id-proj", "/home/u/backup"),
            model.state.value.workspaces.map { it.key },
        )
    }

    @Test
    fun `合法的空快照会清空投影`() = runTest(mainRule.dispatcher) {
        val engine = FakeEnginePort().apply { bootstrapResult = workspacesJson() }
        val model = viewModel(engine = engine)
        advanceUntilIdle()
        model.onEnter()
        advanceUntilIdle()
        assertEquals(2, model.state.value.workspaces.size)

        // 整表快照语义：空表就是真的空，必须覆盖（旧实现会卡在旧数据上）。
        engine.eventsFlow.emit(
            EngineEvent.WorkspaceListUpdated(
                Json.parseToJsonElement("""{"workspaces":[],"tasks":[]}"""),
            ),
        )
        advanceUntilIdle()

        assertTrue(model.state.value.workspaces.isEmpty())
    }

    @Test
    fun `认不出的工作区事件不覆盖已有投影`() = runTest(mainRule.dispatcher) {
        val engine = FakeEnginePort().apply { bootstrapResult = workspacesJson() }
        val model = viewModel(engine = engine)
        advanceUntilIdle()
        model.onEnter()
        advanceUntilIdle()
        assertEquals(2, model.state.value.workspaces.size)

        engine.eventsFlow.emit(EngineEvent.WorkspaceListUpdated(JsonPrimitive("看不懂")))
        advanceUntilIdle()

        assertEquals(2, model.state.value.workspaces.size)
    }

    @Test
    fun `桥降级与恢复映射进状态`() = runTest(mainRule.dispatcher) {
        val engine = FakeEnginePort()
        val model = viewModel(engine = engine)
        advanceUntilIdle()

        engine.eventsFlow.emit(EngineEvent.BridgeDegraded(session = 1, degraded = "通道未就绪"))
        advanceUntilIdle()
        assertEquals("通道未就绪", model.state.value.bridgeDegraded)

        engine.eventsFlow.emit(EngineEvent.BridgeRecovered(session = 1, count = 3))
        advanceUntilIdle()
        assertNull(model.state.value.bridgeDegraded)
    }

    @Test
    fun `relay 失败在 paired 之后不再显示`() = runTest(mainRule.dispatcher) {
        val engine = FakeEnginePort(initialRelayState = RelayState.Reconnecting)
        val model = viewModel(engine = engine)
        advanceUntilIdle()

        engine.relayFailureFlow.value = EngineEvent.RelayFailure(reason = "socket", message = "断了")
        advanceUntilIdle()
        assertTrue(model.state.value.relayFailureVisible)
        assertEquals("socket", model.state.value.relayFailure?.reason)

        // relayFailure 不会自动清空，但 paired 之后不该再挡在界面上
        engine.relayStateFlow.value = RelayState.Paired
        advanceUntilIdle()
        assertFalse(model.state.value.relayFailureVisible)
    }

    // ------------------------------------------------------------------
    // 其它
    // ------------------------------------------------------------------

    @Test
    fun `回前台会 poke relay`() = runTest(mainRule.dispatcher) {
        val engine = FakeEnginePort()
        val model = viewModel(engine = engine)
        advanceUntilIdle()

        model.onForeground()
        advanceUntilIdle()

        assertEquals(1, engine.pokeCount)
    }

    @Test
    fun `poke 失败收敛成错误态`() = runTest(mainRule.dispatcher) {
        val engine = FakeEnginePort().apply { pokeFailure = engineError("invalidState", "没有会话") }
        val model = viewModel(engine = engine)
        advanceUntilIdle()

        model.onForeground()
        advanceUntilIdle()

        assertEquals("当前状态不允许这个操作：没有会话", model.state.value.error)
    }

    @Test
    fun `selectTask 只改焦点`() = runTest(mainRule.dispatcher) {
        val model = viewModel()
        advanceUntilIdle()

        model.selectTask("t7")

        assertEquals("t7", model.state.value.selectedTaskId)
    }

    @Test
    fun `设备名与凭据状态从持久化流映射`() = runTest(mainRule.dispatcher) {
        val repository = FakeDeviceRepository(initialDevices = listOf(deviceRecord(name = "书房电脑")))
        val model = viewModel(repository = repository)
        advanceUntilIdle()

        assertEquals("书房电脑", model.state.value.deviceName)
        assertFalse(model.state.value.credentialsMissing)

        repository.devicesFlow.value = listOf(deviceRecord(name = "书房电脑", remoteUrl = null))
        advanceUntilIdle()

        assertTrue(model.state.value.credentialsMissing)
    }

    // ------------------------------------------------------------------
    // 工作区投影（防御性解析）
    // ------------------------------------------------------------------

    @Test
    fun `投影接受裸数组`() {
        val json = Json.parseToJsonElement("""[{"workspacePath":"/a"},{"workspacePath":"/b"}]""")
        assertEquals(listOf("/a", "/b"), WorkspaceProjection.from(json).map { it.key })
    }

    @Test
    fun `投影接受 items 作为兜底键`() {
        val json = Json.parseToJsonElement("""{"items":[{"workspacePath":"/a"}]}""")
        assertEquals(listOf("/a"), WorkspaceProjection.from(json).map { it.key })
    }

    @Test
    fun `投影对认不出的形状返回空列表而不是抛异常`() {
        assertTrue(WorkspaceProjection.from(null).isEmpty())
        assertTrue(WorkspaceProjection.from(JsonPrimitive("字符串")).isEmpty())
        assertTrue(WorkspaceProjection.from(JsonArray(emptyList())).isEmpty())
        assertTrue(WorkspaceProjection.from(Json.parseToJsonElement("""{"别的键":1}""")).isEmpty())
    }

    @Test
    fun `投影跳过没有 workspacePath 的条目`() {
        val json = Json.parseToJsonElement(
            """[{"label":"没有路径"},{"workspacePath":""},{"workspacePath":"/ok"}]""",
        )
        assertEquals(listOf("/ok"), WorkspaceProjection.from(json).map { it.key })
    }

    @Test
    fun `键规则是 identity 优先 且只 trim identity`() {
        val json = Json.parseToJsonElement(
            """[{"workspacePath":" /p ","workspaceIdentity":" id "},{"workspacePath":" /q "}]""",
        )
        val items = WorkspaceProjection.from(json)
        assertEquals("id", items[0].key)
        // 路径不 trim（对端 resolveWorkspaceKey 只 trim identity）
        assertEquals(" /p ", items[0].path)
        assertEquals(" /q ", items[1].key)
    }

    @Test
    fun `投影不把非字符串当字符串`() {
        // workspacePath 不是字符串 ⇒ 这条记录认不出来，整条跳过
        val unreadable = Json.parseToJsonElement("""[{"workspacePath":123,"label":"x"}]""")
        assertTrue(WorkspaceProjection.from(unreadable).isEmpty())

        // workspacePath 是字符串但别的字段类型不对 ⇒ 保留记录，坏字段当「没有」
        val partial = Json.parseToJsonElement(
            """[{"workspacePath":"/w","label":true,"kind":7,"connectionState":"yes"}]""",
        )
        val workspace = WorkspaceProjection.from(partial).single()
        assertEquals("/w", workspace.key)
        assertEquals("w", workspace.name) // label 坏 → 路径末段兜底
        assertNull(workspace.kind)
        assertEquals("yes", workspace.connectionState)
    }

    @Test
    fun `顶层 tasks 已不是 bootstrap 形状 必须忽略`() {
        // 3.16.1 的 bootstrap / workspace-list 都没有顶层 tasks；任务由 sessions-index 提供。
        val json: JsonObject = Json.parseToJsonElement(
            """
            {"workspaces":[{"workspacePath":"/w"}],
             "tasks":[{"taskId":"t1","title":"任务","workspacePath":"/w","pinned":true}],
             "activeWorkspaceKey":"/w","activeTaskId":"t1"}
            """.trimIndent(),
        ) as JsonObject
        val snapshot = WorkspaceProjection.snapshotOrNull(json)!!
        assertEquals(0, snapshot.workspaces.single().taskCount)
        assertEquals("/w", snapshot.activeWorkspaceKey)
        assertEquals("t1", snapshot.activeTaskId)
    }

    @Test
    fun `workspace-list-updated 合并时保留本地 tasks`() {
        val old = listOf(
            WorkspaceItem(
                key = "/w",
                path = "/w",
                label = "old",
                tasks = listOf(TaskItem(id = "t1", title = "任务")),
            ),
        )
        val updated = listOf(WorkspaceItem(key = "/w", path = "/w", label = "new"))

        val merged = WorkspaceProjection.merge(old, updated).single()

        assertEquals("new", merged.label)
        assertEquals(1, merged.tasks.size)
        assertEquals("t1", merged.tasks.single().id)
    }

    @Test
    fun `任务不再从工作区对象里取`() {
        // 旧形状（tasks 嵌在工作区里）必须被忽略——协议里它是顶层兄弟数组。
        val json = Json.parseToJsonElement(
            """{"workspaces":[{"workspacePath":"/w","tasks":[{"taskId":"t1"}]}]}""",
        )
        assertEquals(0, WorkspaceProjection.from(json).single().taskCount)
    }
}
