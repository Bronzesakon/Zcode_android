package app.zemote.ui.chat.timeline

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行级纯逻辑测试：长按 actions 过滤、折叠/标题文案、摘要与时长格式。
 *
 * 口径来自 `docs/UI-CONFIRM-3.17.1.md` §1.1/§3 与 `docs/chat/02~05`：
 * - 行级动作只由行数据 `actions` 决定；
 * - 分叉只在「整轮输出已完成」的助手行出现；
 * - 时长按网页 `OZ()`：只取前两个非零单位，数字与单位之间带空格。
 */
class TimelineRowLogicTest {

    private fun row(
        kind: String,
        text: String? = null,
        state: String? = null,
        canEdit: Boolean? = null,
        canRetry: Boolean? = null,
        canFork: Boolean? = null,
    ): JsonObject = buildJsonObject {
        put("rowId", 1)
        put("kind", kind)
        if (text != null) put("text", text)
        if (state != null) put("state", state)
        if (canEdit != null || canRetry != null || canFork != null) {
            put(
                "actions",
                buildJsonObject {
                    if (canEdit != null) put("canEdit", canEdit)
                    if (canRetry != null) put("canRetry", canRetry)
                    if (canFork != null) put("canFork", canFork)
                },
            )
        }
    }

    // -----------------------------------------------------------------------
    // 长按菜单：严格按 row.actions
    // -----------------------------------------------------------------------

    @Test
    fun `用户行按 actions 显示复制编辑重试`() {
        assertEquals(
            listOf(RowAction.Copy, RowAction.Edit, RowAction.Retry),
            availableRowActions(row("userInput", text = "hi", canEdit = true, canRetry = true)),
        )
        assertEquals(
            listOf(RowAction.Copy),
            availableRowActions(row("userInput", text = "hi", canEdit = false, canRetry = false)),
        )
        // 空文本没有可复制内容，但编辑/重试仍然按 actions 出现。
        assertEquals(
            listOf(RowAction.Edit),
            availableRowActions(row("userInput", text = " ", canEdit = true)),
        )
    }

    @Test
    fun `助手行分叉只在整轮输出完成后出现`() {
        assertEquals(
            listOf(RowAction.Copy, RowAction.Fork),
            availableRowActions(
                row("assistantText", text = "done", state = "complete", canFork = true),
            ),
        )
        assertEquals(
            listOf(RowAction.Copy),
            availableRowActions(
                row("assistantText", text = "…", state = "streaming", canFork = true),
            ),
        )
        // 没有 canFork 时即使完成也不出现分叉。
        assertEquals(
            listOf(RowAction.Copy),
            availableRowActions(
                row("assistantText", text = "done", state = "complete", canFork = false),
            ),
        )
        // 助手行的重试同样受「输出已完成」约束。
        assertEquals(
            listOf(RowAction.Copy, RowAction.Retry),
            availableRowActions(
                row("assistantText", text = "done", state = "complete", canRetry = true),
            ),
        )
    }

    @Test
    fun `复制对消息行常显而其余动作看 actions`() {
        // 网页的复制按钮不依赖 actions：有正文的用户/助手行总能复制。
        assertEquals(
            listOf(RowAction.Copy),
            availableRowActions(row("userInput", text = "hi")),
        )
        assertEquals(
            listOf(RowAction.Copy),
            availableRowActions(row("assistantText", text = "hi")),
        )
        // 非消息行（工具/产物/标记）没有行级动作。
        assertEquals(
            emptyList<RowAction>(),
            availableRowActions(
                row("toolCall", text = null, canEdit = true, canRetry = true, canFork = true),
            ),
        )
    }

    @Test
    fun `actions 缺省字段按 false 处理`() {
        val flags = rowActionFlags(
            buildJsonObject {
                put("rowId", 3)
                put("kind", "assistantText")
                put("actions", buildJsonObject { put("canFork", true) })
            },
        )
        assertTrue(flags.canFork)
        assertFalse(flags.canEdit)
        assertFalse(flags.canRetry)
    }

    // -----------------------------------------------------------------------
    // 文案与格式
    // -----------------------------------------------------------------------

    @Test
    fun `时长只取前两个非零单位且数字单位间带空格`() {
        assertEquals("1 秒", formatDuration(400))
        assertEquals("1 分 20 秒", formatDuration(80_000))
        assertEquals("1 时", formatDuration(3_600_000))
        assertEquals("1 时 1 分", formatDuration(3_700_000))
        assertEquals("1 天 1 时", formatDuration(90_060_000))
        assertEquals("2 分 13 秒", formatDuration(133_000))
    }

    @Test
    fun `工作段标题覆盖运行完成中断与失败`() {
        assertEquals("工作中 1 分 5 秒", turnHeaderLabel("running", 65_000))
        assertEquals("工作中", turnHeaderLabel("running", 0))
        assertEquals("已工作 5 秒", turnHeaderLabel("completedSuccess", 5_000))
        assertEquals("已处理", turnHeaderLabel("completedSuccess", 0))
        assertEquals("已停止", turnHeaderLabel("completedInterrupted", 9_000))
        // 网页对 failed 没有专用文案，落到「已工作/已处理」。
        assertEquals("已工作 5 秒", turnHeaderLabel("failed", 5_000))
        assertEquals("已处理", turnHeaderLabel("failed", 0))
    }

    @Test
    fun `思考标题折叠与展开区分运行状态`() {
        assertEquals("正在思考", reasoningLabel(streaming = true, open = false, durationMs = 0))
        assertEquals("思考 · 3 秒", reasoningLabel(streaming = true, open = true, durationMs = 3_000))
        assertEquals("思考 · 3 秒", reasoningLabel(streaming = false, open = false, durationMs = 3_000))
        assertEquals("思考", reasoningLabel(streaming = false, open = false, durationMs = 0))
    }

    @Test
    fun `产物副标题与版本徽标`() {
        assertEquals("PDF · 1 KB", artifactMeta("pdf", 1_536))
        assertEquals("900 B", artifactMeta("", 900))
        assertEquals("MD", artifactMeta("md", null))
        assertEquals("", artifactMeta("", null))
        assertNull(artifactVersionBadge(1))
        assertEquals("v2", artifactVersionBadge(2))
        assertNull(artifactVersionBadge(null))
    }

    @Test
    fun `工具摘要优先结构化输入并压成单行`() {
        val structured = buildJsonObject {
            put("rowId", 1)
            put("kind", "toolCall")
            put("toolName", "Read")
            put(
                "input",
                buildJsonObject {
                    put("path", "/work/src/a.kt")
                    put("query", "ignored")
                },
            )
            put("inputText", "{\"path\":\"/work/src/a.kt\"}")
        }
        assertEquals("/work/src/a.kt", toolSummary(structured))

        val plain = buildJsonObject {
            put("rowId", 2)
            put("kind", "toolCall")
            put("toolName", "Bash")
            put("inputText", "\n  echo   hi\nsecond line")
        }
        assertEquals("echo hi", toolSummary(plain))
    }

    @Test
    fun `钩子摘要聚合状态与总耗时`() {
        val executions = listOf(
            HookExecution("PostToolUse", "user", 300, "failed", null),
            HookExecution("PostToolUse", "plugin", 1_200, null, null),
        )
        val summary = hookSummary(executions)!!
        assertEquals("PostToolUse · 2 次执行", summary.title)
        assertEquals("failed", summary.status)
        assertEquals(1_500L, summary.durationMs)

        // 仍有执行在跑时不显示总耗时。
        val running = hookSummary(
            listOf(
                HookExecution("Stop", null, 900, "running", null),
                HookExecution("Stop", null, 100, null, null),
            ),
        )!!
        assertEquals("running", running.status)
        assertNull(running.durationMs)

        // 事件名不一致时统一显示「钩子」。
        assertEquals(
            "钩子 · 2 次执行",
            hookSummary(
                listOf(
                    HookExecution("PreToolUse", null, 10, null, null),
                    HookExecution("PostToolUse", null, 20, null, null),
                ),
            )!!.title,
        )
        assertNull(hookSummary(emptyList()))
    }
}
