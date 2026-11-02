package app.zemote.ui.chat.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.zemote.ui.chat.asLong
import app.zemote.ui.chat.asString
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteType
import kotlinx.serialization.json.JsonObject

/**
 * 产物卡（主时间线口径）：静态卡片，图标恒为文件，副标题 `TYPE · N bytes`。
 *
 * 网页端主时间线里产物不可点击（预览按钮只在分享页）；这里保持只读。
 */
@Composable
internal fun ArtifactRow(row: JsonObject, modifier: Modifier = Modifier) {
    val colors = LocalZemoteColors.current
    val name = row["displayName"].asString() ?: "产物"
    val type = row["artifactType"].asString().orEmpty().uppercase()
    val size = row["sizeBytes"].asLong()

    Row(
        modifier = modifier
            .padding(start = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.card)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Description,
            contentDescription = null,
            tint = colors.foregroundSubtle,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = name,
            style = ZemoteType.uiBase,
            color = colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (type.isNotBlank() || size != null) {
            Text(
                text = listOfNotNull(
                    type.takeIf { it.isNotBlank() },
                    size?.let { "$it bytes" },
                ).joinToString(" · ").let { "  $it" },
                style = ZemoteType.uiSm.copy(fontFamily = FontFamily.Monospace),
                color = colors.foregroundSubtle,
                maxLines = 1,
            )
        }
    }
}
