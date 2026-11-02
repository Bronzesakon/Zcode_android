package app.zemote.ui.chat.timeline

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.zemote.ui.chat.asString
import app.zemote.ui.chat.markdown.MarkdownText
import kotlinx.serialization.json.JsonObject

/**
 * 助手正文：纯文档流（无气泡/边框/背景），Markdown 渲染。
 *
 * 网页端正文没有流式光标或闪烁动画，这里也不加——流式进度由工具/思考行体现。
 */
@Composable
internal fun AssistantTextRow(
    row: JsonObject,
    onImageClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 0.dp)) {
        MarkdownText(text = row["text"].asString().orEmpty(), onImageClick = onImageClick)
    }
}
