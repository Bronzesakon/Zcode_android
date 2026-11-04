package app.zemote.ui.chat.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.zemote.ui.chat.asLong
import app.zemote.ui.chat.asString
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteRadius
import app.zemote.ui.theme.ZemoteSpacing
import app.zemote.ui.theme.ZemoteType
import kotlinx.serialization.json.JsonObject

/**
 * 产物卡（主时间线口径）：静态卡片，类型图标 + 文件名 + 大小，版本 ≥2 显示版本徽标。
 *
 * 网页端主时间线里产物不可点击（预览按钮只在分享页）；这里保持只读。
 */
@Composable
internal fun ArtifactRow(row: JsonObject, modifier: Modifier = Modifier) {
    val colors = LocalZemoteColors.current
    val name = remember(row) { row["displayName"].asString() ?: "产物" }
    val type = remember(row) { row["artifactType"].asString().orEmpty() }
    val meta = remember(row) { artifactMeta(type, row["sizeBytes"].asLong()) }
    val version = remember(row) {
        artifactVersionBadge(
            row["version"].asLong() ?: row["artifactVersion"].asLong(),
        )
    }
    val icon = remember(row) {
        artifactIcon(type.ifBlank { name.substringAfterLast('.', "") })
    }

    Row(
        modifier = modifier
            .padding(vertical = ZemoteSpacing.space1)
            .clip(ZemoteRadius.lgShape)
            .background(colors.card)
            .border(1.dp, colors.cardBorder, ZemoteRadius.lgShape)
            .padding(horizontal = ZemoteSpacing.space3, vertical = ZemoteSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZemoteSpacing.space2),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.foregroundSubtle,
            modifier = Modifier.size(ZemoteSpacing.space4),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = ZemoteType.uiBase,
                color = colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = ZemoteType.uiSm,
                    color = colors.foregroundSubtle,
                    maxLines = 1,
                )
            }
        }
        if (version != null) {
            Spacer(Modifier.width(ZemoteSpacing.space0_5))
            Text(
                text = version,
                style = ZemoteType.uiXs,
                color = colors.foregroundSubtle,
                modifier = Modifier
                    .clip(ZemoteRadius.smShape)
                    .background(colors.input)
                    .padding(horizontal = ZemoteSpacing.space1_5, vertical = ZemoteSpacing.space0_5),
            )
        }
    }
}
