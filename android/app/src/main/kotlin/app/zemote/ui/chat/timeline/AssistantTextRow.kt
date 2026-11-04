package app.zemote.ui.chat.timeline

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.zemote.ui.chat.asString
import app.zemote.ui.chat.markdown.MarkdownText
import kotlinx.serialization.json.JsonObject

/**
 * 助手正文：左对齐全宽、纯文档流（无气泡/边框/背景），Markdown 渲染。
 *
 * 行高 1.75 由 Markdown 渲染层负责（工具/状态行仍是 1.5），这里不覆盖。
 * 网页端正文没有流式光标或闪烁动画，这里也不加——流式进度由工具/思考行体现。
 * 行级动作（复制 / 重试 / 分叉）按 `row.actions` 与「整轮输出已完成」判定，
 * 长按出菜单。
 */
@Composable
internal fun AssistantTextRow(
    row: JsonObject,
    onImageClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = remember(row) { row["text"].asString().orEmpty() }
    // 历史正文可能非常长；整段交给 Markdown 解析 + 一次性布局在上翻历史时会卡死。
    val rendered = remember(text) { clampTextForRender(text, ASSISTANT_TEXT_RENDER_LIMIT) }
    RowActionContainer(row = row, modifier = modifier.fillMaxWidth()) { gesture ->
        Column(modifier = Modifier.fillMaxWidth().then(gesture)) {
            MarkdownText(text = rendered, onImageClick = onImageClick)
        }
    }
}

/** 单条助手正文的渲染上限；正常消息远低于此值。 */
private const val ASSISTANT_TEXT_RENDER_LIMIT = 120_000
