package app.zemote.ui.chat.timeline

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工具分组与钩子入口的纯逻辑测试。 */
class TimelineUnitsTest {

    private fun row(
        id: Long,
        kind: String,
        turnId: String = "turn-1",
        toolName: String? = null,
        streaming: Boolean = false,
        state: String? = null,
        text: String? = null,
    ): JsonObject = buildJsonObject {
        put("rowId", id)
        put("kind", kind)
        put("turnId", turnId)
        if (toolName != null) put("toolName", toolName)
        if (streaming) put("streaming", true)
        if (state != null) put("state", state)
        if (text != null) put("text", text)
    }

    /** 单元覆盖的 rowId，按渲染顺序展开。 */
    private fun TimelineUnit.ids(): List<Long> = when (this) {
        is TimelineUnit.Single -> listOf(row["rowId"].toString().toLong())
        is TimelineUnit.Group -> rows.map { it["rowId"].toString().toLong() }
        is TimelineUnit.HookTrailer -> hooks.map { it["rowId"].toString().toLong() }
    }

    @Test
    fun `连续两条同家族工具合成一组`() {
        val units = buildTimelineUnits(
            listOf(
                row(1, "turnHeader"),
                row(2, "toolCall", toolName = "Read"),
                row(3, "toolCall", toolName = "Grep"),
                row(4, "assistantText"),
            ),
        )

        assertEquals(3, units.size)
        val group = units[1] as TimelineUnit.Group
        assertEquals(ToolFamily.EXPLORE, group.family)
        assertEquals(listOf(2L, 3L), group.rows.map { it["rowId"].toString().toLong() })
        assertTrue(units[2] is TimelineUnit.Single)
    }

    @Test
    fun `单条工具不成组`() {
        val units = buildTimelineUnits(listOf(row(1, "toolCall", toolName = "Read")))
        assertTrue(units.single() is TimelineUnit.Single)
    }

    @Test
    fun `不同家族相邻不成组`() {
        val units = buildTimelineUnits(
            listOf(
                row(1, "toolCall", toolName = "Read"),
                row(2, "toolCall", toolName = "Bash"),
            ),
        )
        assertEquals(2, units.size)
        assertTrue(units.all { it is TimelineUnit.Single })
    }

    @Test
    fun `跨轮次不成组`() {
        val units = buildTimelineUnits(
            listOf(
                row(1, "toolCall", turnId = "t1", toolName = "Read"),
                row(2, "toolCall", turnId = "t2", toolName = "Read"),
            ),
        )
        assertEquals(2, units.size)
        assertTrue(units.all { it is TimelineUnit.Single })
    }

    @Test
    fun `未知工具保持独立行`() {
        val units = buildTimelineUnits(
            listOf(
                row(1, "toolCall", toolName = "mcp__foo"),
                row(2, "toolCall", toolName = "mcp__bar"),
            ),
        )
        assertEquals(2, units.size)
        assertTrue(units.all { it is TimelineUnit.Single })
    }

    @Test
    fun `hookInvocation 不进入行流并归到轮次末尾`() {
        val hook = buildJsonObject {
            put("rowId", 5)
            put("kind", "hookInvocation")
            put("turnId", "turn-1")
            put("hookEventName", "PreToolUse")
        }
        val units = buildTimelineUnits(
            listOf(
                row(1, "turnHeader"),
                hook,
                row(2, "assistantText"),
                row(3, "assistantText", turnId = "turn-2"),
            ),
        )

        // turnHeader / assistantText / HookTrailer(turn-1) / assistantText(turn-2)
        assertEquals(4, units.size)
        assertTrue(units.none { it is TimelineUnit.Single && it.row["kind"].toString().contains("hook") })
        val trailer = units[2] as TimelineUnit.HookTrailer
        assertEquals("turn-1", trailer.turnId)
        assertEquals(1, trailer.hooks.size)
    }

    @Test
    fun `钩子执行只保留 didExecute 并按 hookIndex 排序`() {
        val hook = buildJsonObject {
            put("hookEventName", "PostToolUse")
            put(
                "executions",
                kotlinx.serialization.json.buildJsonArray {
                    add(
                        buildJsonObject {
                            put("hookIndex", 2)
                            put("didExecute", true)
                            put("state", "completed")
                            put("outcome", "success")
                            put("durationMs", 1200)
                            put("sourceKind", "user")
                        },
                    )
                    add(
                        buildJsonObject {
                            put("hookIndex", 0)
                            put("didExecute", false)
                            put("state", "completed")
                        },
                    )
                    add(
                        buildJsonObject {
                            put("hookIndex", 1)
                            put("didExecute", true)
                            put("state", "failed")
                            put("durationMs", 300)
                            put("sourceKind", "plugin")
                            put("blockReason", "denied")
                        },
                    )
                },
            )
        }

        val executions = flattenHookExecutions(listOf(hook))

        assertEquals(2, executions.size)
        assertEquals(300L, executions[0].durationMs)
        assertEquals("failed", executions[0].status)
        assertEquals("plugin", executions[0].sourceKind)
        assertEquals("denied", executions[0].blockReason)
        assertEquals(1200L, executions[1].durationMs)
        assertNull(executions[1].status)
    }

    @Test
    fun `运行中的钩子不显示耗时`() {
        val hook = buildJsonObject {
            put("hookEventName", "Stop")
            put(
                "executions",
                kotlinx.serialization.json.buildJsonArray {
                    add(
                        buildJsonObject {
                            put("hookIndex", 0)
                            put("didExecute", true)
                            put("state", "running")
                            put("durationMs", 900)
                        },
                    )
                },
            )
        }

        val execution = flattenHookExecutions(listOf(hook)).single()
        assertEquals("running", execution.status)
        assertNull(execution.durationMs)
    }

    @Test
    fun `钩子耗时格式`() {
        assertEquals("500ms", formatHookDuration(500))
        assertEquals("1.50s", formatHookDuration(1500))
        assertEquals("9.99s", formatHookDuration(9990))
        assertEquals("15.0s", formatHookDuration(15000))
    }

    @Test
    fun `工具 kindLabel 与运行态文案`() {
        assertEquals("终端", toolKindLabel("Bash", "success"))
        assertEquals("正在执行", toolKindLabel("Bash", "running"))
        assertEquals("读取", toolKindLabel("Read", "success"))
        assertEquals("正在读取", toolKindLabel("Read", "running"))
        assertEquals("编辑", toolKindLabel("Edit", "success"))
        assertEquals("正在编辑", toolKindLabel("Edit", "running"))
        assertEquals("查阅", toolKindLabel("Explore", "success"))
        assertEquals("MCP", toolKindLabel("mcp__fs__read", "success"))
    }

    @Test
    fun `已完成的钩子不渲染状态`() {
        val hook = buildJsonObject {
            put("hookEventName", "SessionStart")
            put(
                "executions",
                kotlinx.serialization.json.buildJsonArray {
                    add(
                        buildJsonObject {
                            put("hookIndex", 0)
                            put("didExecute", true)
                            put("state", "completed")
                            put("outcome", "success")
                        },
                    )
                },
            )
        }

        val execution = flattenHookExecutions(listOf(hook)).single()
        assertNull(execution.status)
        assertFalse(execution.eventName.isBlank())
    }

    @Test
    fun `三轮完整历史不跨轮合并且段标题归属本轮`() {
        var id = 1L
        val rows = buildList {
            repeat(3) { index ->
                val turn = "t${index + 1}"
                add(row(id++, "turnHeader", turnId = turn))
                add(row(id++, "userInput", turnId = turn))
                add(row(id++, "reasoning", turnId = turn))
                add(row(id++, "toolCall", turnId = turn, toolName = "Read"))
                add(row(id++, "toolCall", turnId = turn, toolName = "Grep"))
                add(row(id++, "assistantText", turnId = turn))
            }
        }

        val units = buildTimelineUnits(rows)

        // 每轮 5 个单元：header / userInput / reasoning / tool-group / 最终回答。
        assertEquals(15, units.size)
        units.chunked(5).forEachIndexed { index, turnUnits ->
            val turn = "t${index + 1}"
            assertTrue("轮次 $turn 的单元混入了其他轮次", turnUnits.all { it.turnId == turn })
            val header = turnUnits[0] as TimelineUnit.Single
            assertEquals("turnHeader", header.row["kind"].toString().trim('"'))
            val group = turnUnits[3] as TimelineUnit.Group
            assertEquals(turn, group.turnId)
            assertEquals(2, group.rows.size)
            assertEquals(
                1,
                turnUnits.count {
                    it is TimelineUnit.Single &&
                        it.row["kind"].toString().trim('"') == "turnHeader"
                },
            )
        }
    }

    @Test
    fun `向前加载历史按 rowId 合并去重且不丢旧行`() {
        val older = (1L..4L).map { row(it, "userInput", turnId = "t1") }
        val current = (101L..105L).map { row(it, "assistantText", turnId = "t2") }
        // 批次边界重叠：101 出现两次，且拼接顺序被打乱（当前窗口在前、旧页在后）。
        val duplicated = row(101, "assistantText", turnId = "t2", text = "新内容")
        val units = buildTimelineUnits(current + older + duplicated)
        val ids = units.flatMap { it.ids() }

        assertEquals((1L..4L).toList() + (101L..105L).toList(), ids)
        assertEquals(ids.size, ids.distinct().size)
        // 重复 rowId 保留最后一份内容，而不是把旧行整批丢弃。
        val retained = (units.first { 101L in it.ids() } as TimelineUnit.Single).row
        assertEquals("新内容", retained["text"].toString().trim('"'))
    }

    @Test
    fun `折叠只隐藏最终回答之前的工作行且默认展开判定正确`() {
        val rows = listOf(
            row(1, "turnHeader", turnId = "t1", state = "completedSuccess"),
            row(2, "userInput", turnId = "t1"),
            row(3, "reasoning", turnId = "t1"),
            row(4, "toolCall", turnId = "t1", toolName = "Read"),
            row(5, "assistantText", turnId = "t1"),
            row(6, "artifact", turnId = "t1"),
        )
        val units = buildTimelineUnits(rows)
        val finalAnswers = finalAnswerRowIdByTurn(rows)

        assertFalse("t1" in defaultOpenTurnIds(rows))
        val collapsed = visibleTimelineUnits(
            units = units,
            collapsedTurns = setOf("t1"),
            finalAnswerRowIds = finalAnswers,
        )
        // header、用户气泡、最终回答、变更卡保留；工作行（reasoning/toolCall）隐藏。
        assertEquals(listOf(1L, 2L, 5L, 6L), collapsed.flatMap { it.ids() })

        val expanded = visibleTimelineUnits(
            units = units,
            expandedTurns = setOf("t1"),
            finalAnswerRowIds = finalAnswers,
        )
        assertEquals((1L..6L).toList(), expanded.flatMap { it.ids() })

        // 最终回答之后的工作行不隐藏（折叠只针对回答之前的工作）。
        val withTrailingWork = rows + row(7, "toolCall", turnId = "t1", toolName = "Bash")
        val trailing = visibleTimelineUnits(
            units = buildTimelineUnits(withTrailingWork),
            collapsedTurns = setOf("t1"),
            finalAnswerRowIds = finalAnswerRowIdByTurn(withTrailingWork),
        )
        assertEquals(listOf(1L, 2L, 5L, 6L, 7L), trailing.flatMap { it.ids() })

        // 默认展开：运行中 / 中断 / 失败 / 有工作行但无最终回答 / 仍有流式行。
        val stateRows = listOf(
            row(11, "turnHeader", turnId = "running", state = "running"),
            row(12, "toolCall", turnId = "running", toolName = "Bash"),
            row(13, "turnHeader", turnId = "interrupted", state = "completedInterrupted"),
            row(14, "assistantText", turnId = "interrupted"),
            row(15, "turnHeader", turnId = "failed", state = "failed"),
            row(16, "assistantText", turnId = "failed"),
            row(17, "turnHeader", turnId = "unanswered", state = "completedSuccess"),
            row(18, "reasoning", turnId = "unanswered"),
            row(19, "turnHeader", turnId = "done", state = "completedSuccess"),
            row(20, "toolCall", turnId = "done", toolName = "Read"),
            row(21, "assistantText", turnId = "done"),
            row(22, "reasoning", turnId = "streaming", state = "streaming"),
        )
        val open = defaultOpenTurnIds(stateRows)
        assertEquals(
            setOf("running", "interrupted", "failed", "unanswered", "streaming"),
            open,
        )
        assertFalse("done" in open)
    }

    @Test
    fun `缺 rowId 的匿名行 key 去重后全局唯一`() {
        fun anon(text: String) = buildJsonObject {
            put("kind", "assistantText")
            put("turnId", "turn-1")
            put("text", text)
        }

        val units = buildTimelineUnits(listOf(anon("same"), anon("same"), anon("same")))
        assertEquals(3, units.size)

        val base = units.first().key
        val keys = uniqueTimelineKeys(units)
        assertEquals(listOf(base, "$base#1", "$base#2"), keys)
        assertEquals(3, keys.toSet().size)
    }

    @Test
    fun `分组 key 取组首行，运行中追加同家族工具后保持不变`() {
        val before = buildTimelineUnits(
            listOf(
                row(3, "toolCall", toolName = "Read"),
                row(4, "toolCall", toolName = "Grep"),
            ),
        ).single() as TimelineUnit.Group

        val after = buildTimelineUnits(
            listOf(
                row(3, "toolCall", toolName = "Read"),
                row(4, "toolCall", toolName = "Grep"),
                row(5, "toolCall", toolName = "Glob"),
            ),
        ).single() as TimelineUnit.Group

        // 运行中的组只会往尾部追加：key 不能跟着组尾变，否则每次追加都丢展开态/锚点。
        assertEquals("tool-group:3", before.key)
        assertEquals(before.key, after.key)
    }

    @Test
    fun `匿名行保持原相对位置，不会被挪到最旧端`() {
        val anonymous = buildJsonObject {
            put("kind", "timelineMarker")
            put("marker", "x")
        }
        val normalized = normalizeTimelineRows(
            listOf(
                row(1, "assistantText"),
                anonymous,
                row(2, "assistantText"),
                row(3, "assistantText"),
            ),
        )

        // 反向布局里列表末尾 = 视觉最顶 = 最旧；匿名行必须留在 1 和 2 之间。
        assertEquals(4, normalized.size)
        assertEquals("1", normalized[0]["rowId"].toString())
        assertNull(normalized[1]["rowId"])
        assertEquals("2", normalized[2]["rowId"].toString())
        assertEquals("3", normalized[3]["rowId"].toString())
    }
}
