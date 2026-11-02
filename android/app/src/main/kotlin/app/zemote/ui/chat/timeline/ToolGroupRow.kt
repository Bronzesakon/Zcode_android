package app.zemote.ui.chat.timeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.zemote.ui.chat.asString
import app.zemote.ui.chat.rowId
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteType
import kotlinx.serialization.json.JsonObject

/** 工具分组家族。 */
internal enum class ToolFamily(
    val label: String,
    val icon: ImageVector,
    val defaultOpen: Boolean,
) {
    EXPLORE("查阅", Icons.Default.Search, true),
    TERMINAL("终端", Icons.Default.Terminal, true),
    CHANGES("更改", Icons.Default.Edit, false),
}

/** 把工具名归到分组家族；不参与分组的工具返回 `null`。 */
internal fun toolFamilyOf(toolName: String): ToolFamily? {
    val name = toolName.lowercase()
    return when {
        name.contains("read") || name.contains("grep") || name.contains("glob") ||
            name.contains("search") || name.contains("find") || name.contains("list") ||
            name == "ls" -> ToolFamily.EXPLORE

        name.contains("bash") || name.contains("shell") || name.contains("terminal") ||
            name.contains("exec") || name.contains("command") -> ToolFamily.TERMINAL

        name.contains("edit") || name.contains("write") || name.contains("patch") ||
            name.contains("replace") || name.contains("change") ||
            name.contains("delete") || name.contains("remove") -> ToolFamily.CHANGES

        else -> null
    }
}

/**
 * 工具分组：连续 ≥2 条同家族工具合并成一组。
 *
 * 组头 = 图标 + 家族名 + 计数；组内子行隐藏图标、缩进
 * `ml 8dp + 1px 竖线 + pl 14dp`、行间 8dp。
 */
@Composable
internal fun ToolGroupRow(
    family: ToolFamily,
    rows: List<JsonObject>,
    open: Boolean,
    onToggle: () -> Unit,
    onRowToggle: (Long) -> Unit,
    isRowOpen: (Long) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalZemoteColors.current
    val rotation by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        label = "group-chevron",
    )
    val summary = groupSummary(family, rows)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onToggle)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                family.icon,
                contentDescription = null,
                tint = colors.foregroundSubtle,
                modifier = Modifier.size(16.dp).offset(y = 1.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = family.label,
                style = ZemoteType.uiBase,
                fontWeight = FontWeight.Medium,
                color = colors.foreground,
            )
            Spacer(Modifier.width(8.dp))
            AnimatedSummaryText(
                text = summary,
                style = ZemoteType.uiBase,
                color = colors.foregroundSubtle,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = null,
                tint = colors.foregroundSubtle,
                modifier = Modifier.size(14.dp).rotate(rotation),
            )
        }

        AnimatedVisibility(
            visible = open,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp),
            ) {
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(colors.border),
                )
                Column(
                    modifier = Modifier.padding(start = 14.dp).fillMaxWidth(),
                ) {
                    rows.forEach { row ->
                        ToolCallRow(
                            row = row,
                            open = isRowOpen(row.rowIdOf()),
                            onToggle = { onRowToggle(row.rowIdOf()) },
                            showIcon = false,
                        )
                        Spacer(Modifier.size(8.dp))
                    }
                }
            }
        }
    }
}

private fun groupSummary(family: ToolFamily, rows: List<JsonObject>): String = when (family) {
    ToolFamily.EXPLORE -> {
        val searches = rows.count {
            val name = it["toolName"].asString().orEmpty().lowercase()
            name.contains("grep") || name.contains("search") || name.contains("find")
        }
        val files = rows.size - searches
        listOfNotNull(
            searches.takeIf { it > 0 }?.let { "$it 次搜索" },
            files.takeIf { it > 0 }?.let { "$it 个文件" },
        ).joinToString("，").ifBlank { "${rows.size} 次查阅" }
    }

    ToolFamily.TERMINAL -> {
        val failed = rows.count { it["status"].asString() == "error" }
        "${rows.size} 个命令" + if (failed > 0) "，$failed 个失败" else ""
    }

    ToolFamily.CHANGES -> "${rows.size} 个文件"
}

/** `rowId` 的本地快捷读取（避免把扩展 import 散布到每个文件）。 */
private fun JsonObject.rowIdOf(): Long = rowId()
