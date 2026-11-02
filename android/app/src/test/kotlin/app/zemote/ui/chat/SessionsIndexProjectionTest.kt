package app.zemote.ui.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SessionsIndexProjection]：3.16.1 的任务来源是 `sessionsIndex[i].list`，
 * 不是 bootstrap 的顶层 `tasks`。
 */
class SessionsIndexProjectionTest {

    private val workspace = WorkspaceItem(
        key = "E:\\Zcode_harmony",
        path = "E:\\Zcode_harmony",
        label = "Zcode_harmony",
        kind = "local",
    )

    private fun index(json: String) = Json.parseToJsonElement(json).jsonArray

    @Test
    fun `running session maps to running task`() {
        val tasks = SessionsIndexProjection.tasks(
            workspace,
            index(
                """
                [{"ready":true,"list":[
                  {"sessionId":"s1","title":"修复登录","phase":"running",
                   "hasBackgroundWork":false,"lastActivityAt":1792816465000,
                   "createdAt":1792816400000,"lastAssistantPreview":"正在检查"}
                ]}]
                """.trimIndent(),
            ),
        )

        assertEquals(1, tasks.size)
        assertEquals("s1", tasks[0].id)
        assertEquals("修复登录", tasks[0].title)
        assertEquals("running", tasks[0].displayStatus)
        assertEquals(1792816465000L, tasks[0].updatedAt)
        assertEquals(workspace.key, tasks[0].workspaceKey)
    }

    @Test
    fun `phase maps to the status pill`() {
        val tasks = SessionsIndexProjection.tasks(
            workspace,
            index(
                """
                [{"ready":true,"list":[
                  {"sessionId":"a","title":"A","phase":"completedSuccess","lastActivityAt":1},
                  {"sessionId":"b","title":"B","phase":"error","lastActivityAt":2},
                  {"sessionId":"c","title":"C","phase":"draft","lastActivityAt":3},
                  {"sessionId":"d","title":"D","phase":"prewarming","lastActivityAt":4}
                ]}]
                """.trimIndent(),
            ),
        )

        assertEquals(
            listOf("completed", "error", null, "running"),
            tasks.map { it.displayStatus },
        )
    }

    @Test
    fun `previews come from lastAssistantPreview`() {
        val previews = SessionsIndexProjection.previews(
            index(
                """
                [{"list":[
                  {"sessionId":"s1","lastAssistantPreview":"hello"},
                  {"sessionId":"s2","lastAssistantPreview":""}
                ]}]
                """.trimIndent(),
            ),
        )

        assertEquals(mapOf("s1" to "hello"), previews)
    }
}
