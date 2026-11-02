package app.zemote.ui.home

import app.zemote.ui.FakeAuthStore
import app.zemote.ui.FakeDeviceRepository
import app.zemote.ui.FakeEnginePort
import app.zemote.ui.MainDispatcherRule
import app.zemote.ui.chat.ConversationRegistry
import app.zemote.ui.deviceRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 首页的「全部项目 + 任务基底」：readTree 必须补全 bootstrap 里没有的项目，
 * 任务按 projectId 归到项目主根，未读可被清掉。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private fun ownerModel(engine: FakeEnginePort): HomeViewModel {
        val repository = FakeDeviceRepository(initialDevices = listOf(deviceRecord()))
        return HomeViewModel(
            repository = repository,
            engine = engine,
            authStore = FakeAuthStore(),
            registry = ConversationRegistry(engine, kotlinx.coroutines.CoroutineScope(mainRule.dispatcher)),
        )
    }

    @Test
    fun `readTree 补全 bootstrap 之外的项目并把多根任务归到项目`() = runTest(mainRule.dispatcher) {
        val engine = FakeEnginePort().apply {
            bootstrapResult = Json.parseToJsonElement(
                """
                {"workspaces":[
                  {"workspacePath":"/p/tab","label":"tab","kind":"local"}
                ]}
                """.trimIndent(),
            )
            channelCallResult = Json.parseToJsonElement(
                """
                {
                  "projects":[
                    {"id":"proj-1","hostId":"local","name":"P1",
                     "roots":[{"rootPath":"/p/one","isPrimary":true}]},
                    {"id":"proj-2","hostId":"local","name":"P2",
                     "roots":[{"rootPath":"/p/two","isPrimary":true}]}
                  ],
                  "tasks":[
                    {"taskId":"t1","workspacePath":"/p/one","projectId":"proj-1",
                     "hostId":"local","title":"A","pinned":true,"unreadAt":123},
                    {"taskId":"t2","workspacePath":"/p/two/root2","projectId":"proj-2",
                     "hostId":"local","title":"B"}
                  ]
                }
                """.trimIndent(),
            )
        }
        val model = ownerModel(engine)
        model.onEnter()
        advanceUntilIdle()

        val workspaces = model.state.value.workspaces
        val keys = workspaces.map { it.key }
        assertTrue("bootstrap 工作区应保留: $keys", "/p/tab" in keys)
        assertTrue("readTree 项目 1 应出现: $keys", "/p/one" in keys)
        assertTrue("readTree 项目 2 应出现: $keys", "/p/two" in keys)

        val one = workspaces.first { it.key == "/p/one" }
        val t1 = one.tasks.first { it.id == "t1" }
        assertTrue("readTree 的 pinned 必须保留", t1.pinned)
        assertEquals(123L, t1.unreadAt)

        // t2 的 workspacePath 是 /p/two/root2（非主根），按 projectId 归到 /p/two。
        val two = workspaces.first { it.key == "/p/two" }
        assertNotNull(two.tasks.firstOrNull { it.id == "t2" })
        assertEquals("/p/two/root2", two.tasks.first { it.id == "t2" }.workspacePath)
    }

    @Test
    fun `标记已读后本地 unreadAt 立刻清空`() = runTest(mainRule.dispatcher) {
        val engine = FakeEnginePort().apply {
            bootstrapResult = Json.parseToJsonElement(
                """{"workspaces":[{"workspacePath":"/p/tab","label":"tab","kind":"local"}]}""",
            )
            channelCallResult = Json.parseToJsonElement(
                """
                {"projects":[{"id":"proj-1","hostId":"local","name":"P1",
                  "roots":[{"rootPath":"/p/one","isPrimary":true}]}],
                 "tasks":[{"taskId":"t1","workspacePath":"/p/one","projectId":"proj-1",
                  "hostId":"local","title":"A","unreadAt":123}]}
                """.trimIndent(),
            )
        }
        val model = ownerModel(engine)
        model.onEnter()
        advanceUntilIdle()

        val workspace = model.state.value.workspaces.first { it.key == "/p/one" }
        val task = workspace.tasks.first { it.id == "t1" }
        model.markRead(task, workspace)
        advanceUntilIdle()

        val after = model.state.value.workspaces
            .first { it.key == "/p/one" }
            .tasks
            .first { it.id == "t1" }
        assertNull("已读不能在本地保留旧 unreadAt", after.unreadAt)
        assertTrue(
            engine.channelCalls.any { it.method == "setTaskUnread" },
        )
    }
}
