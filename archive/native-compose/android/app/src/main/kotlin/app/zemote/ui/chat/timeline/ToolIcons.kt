package app.zemote.ui.chat.timeline

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Book
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 工具名 → lucide 图标（与网页端 `j3t` 的家族映射一致）。
 *
 * 网页是 24×24 细线图标，Material 的填充图标形状差异明显，所以主要家族用
 * [LucideIcons]；少数网页没有专门图标的工具退回 Material。
 */
internal fun toolIcon(toolName: String): ImageVector {
    val name = toolName.lowercase()
    return when {
        // MCP / 插件 / agent 优先，避免工具名里的 read/edit 抢走图标。
        name.contains("mcp") || name.contains("plugin") -> LucideIcons.Plug

        name.contains("agent") || name.contains("subagent") -> LucideIcons.Bot

        // file-read / search / explore 统一用放大镜。
        name.contains("read") || name.contains("grep") || name.contains("glob") ||
            name.contains("explore") || name.contains("search") || name.contains("find") ||
            name.contains("list") || name == "ls" -> LucideIcons.Search

        // file-write 统一用铅笔。
        name.contains("edit") || name.contains("write") || name.contains("patch") ||
            name.contains("replace") || name.contains("change") -> LucideIcons.Pencil

        name.contains("bash") || name.contains("shell") || name.contains("terminal") ||
            name.contains("exec") || name.contains("command") -> LucideIcons.SquareTerminal

        name.contains("todo") || name.contains("plan") -> Icons.Default.Checklist

        name.contains("web") || name.contains("fetch") || name.contains("browser") ->
            Icons.Default.Language

        name.contains("image") || name.contains("screenshot") -> Icons.Default.Image

        name.contains("memory") || name.contains("recall") -> Icons.Default.Memory

        name.contains("notebook") || name.contains("skill") -> Icons.Default.Book

        name.contains("code") || name.contains("eval") -> Icons.Default.Code

        // 未知工具：扳手。
        else -> LucideIcons.Wrench
    }
}

/**
 * 工具行的 kindLabel（网页端各渲染器的固定文案）。
 *
 * 运行中部分工具会换成「正在…」文案；失败/完成后回到名词。
 */
internal fun toolKindLabel(toolName: String, status: String): String {
    val name = toolName.lowercase()
    val running = status == "running" || status == "inputStreaming" || status == "pendingApproval"
    fun r(done: String, doing: String) = if (running) doing else done
    return when {
        // MCP / 插件的 kindLabel 固定为 MCP，优先于工具名里的 read/edit 等字样。
        name.contains("mcp") || name.contains("plugin") -> "MCP"

        name.contains("agent") || name.contains("subagent") -> "子智能体"

        // 具体工具优先于通用子串（todo / task-control / workflow 等）。
        name.contains("todowrite") || name.contains("todo_write") -> r("待办", "正在更新待办")
        name.contains("todoread") || name.contains("todo_read") -> r("待办", "正在读取待办")
        name.contains("askuserquestion") || name.contains("ask-user-question") -> r("已询问", "正在询问")
        name.contains("enterplanmode") || name.contains("plan-guidance") -> "已开启 Plan Mode"
        name.contains("readsessioncontext") || name.contains("session-context") ->
            r("已读取上下文", "正在读取上下文")
        name.contains("respondtocoordinator") -> r("回复", "正在回复")
        name.contains("sendmessage") -> r("消息", "正在发送消息")
        name.contains("taskoutput") -> r("任务输出", "正在获取任务输出")
        name.contains("taskstop") -> r("停止任务", "正在停止任务")
        name.contains("skill") -> r("技能", "正在运行技能")
        name.contains("goalread") || name == "goal" -> "Goal"
        name.startsWith("js") || name.contains("node-repl") -> "Node.js"

        name.contains("saveworkflow") -> r("已保存", "正在保存")
        name.contains("listsavedworkflows") -> r("已保存的工作流", "正在列出工作流")
        name.contains("getworkflowrun") || name.contains("listworkflowruns") ->
            r("工作流实例", "正在获取工作流实例")
        name.contains("evalworkflowsnippet") -> "片段执行"
        name.contains("resumeworkflowrun") -> r("已恢复", "正在恢复")
        name.contains("listmodels") -> r("可用模型", "正在列出模型")
        name.contains("escalate") -> "问题"
        name.contains("resolveworkflowquestion") -> r("已回答", "正在回答")
        name.contains("submit_result") || name.contains("submitresult") ->
            r("已提交结果", "正在提交结果")
        name.contains("workflow") -> r("工作流草稿", "正在编写工作流")
        name.contains("exitplanmode") || name.contains("switch-mode") -> "Plan"
        name.contains("cua") -> "电脑控制"

        name.contains("bash") || name.contains("shell") || name.contains("terminal") ||
            name.contains("exec") || name.contains("command") -> r("终端", "正在执行")

        name.contains("read") -> r("读取", "正在读取")

        name.contains("grep") || name.contains("glob") || name.contains("search") ||
            name.contains("find") || name.contains("list") || name == "ls" ->
            r("搜索", "正在搜索")

        name.contains("write") -> r("写入", "正在写入")

        name.contains("delete") || name.contains("remove") -> r("删除", "正在删除")

        name.contains("edit") || name.contains("patch") || name.contains("replace") ||
            name.contains("change") -> r("编辑", "正在编辑")

        name.contains("explore") -> "查阅"

        else -> toolName.ifBlank { "工具调用" }
    }
}

/** artifactType → 图标。 */
internal fun artifactIcon(type: String): ImageVector = when (type.lowercase()) {
    "image" -> Icons.Default.Image
    "md", "text" -> Icons.Default.Description
    "html" -> Icons.Default.Code
    else -> Icons.Default.Description
}
