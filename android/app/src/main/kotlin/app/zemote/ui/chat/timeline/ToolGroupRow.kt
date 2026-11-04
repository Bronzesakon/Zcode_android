package app.zemote.ui.chat.timeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.zemote.ui.chat.asString
import app.zemote.ui.chat.rowId
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteSpacing
import app.zemote.ui.theme.ZemoteType
import kotlinx.serialization.json.JsonObject

/** 工具分组家族。 */
internal enum class ToolFamily(
    val label: String,
    val icon: ImageVector,
    val defaultOpen: Boolean,
) {
    // 网页 3.17.1：所有分组默认折叠（`aw.get(R) ?? !1`，09 审计 F7）。
    EXPLORE("查阅", LucideIcons.Search, false),
    TERMINAL("终端", LucideIcons.SquareTerminal, false),
    CHANGES("更改", LucideIcons.Pencil, false),
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
 * 组头 = 图标 + 家族名 + 计数。展开区按网页 `ml-2 space-y-2 border-l pl-3.5`：
 * 左侧外距 8dp、1px 竖线、左内距 14dp，子行间距 8dp；子行不显示图标和状态勾，
 * 文字左缘落在 23dp，与组头标题（24dp）基本对齐。
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
    val summary = remember(family, rows) { groupSummary(family, rows) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .softClickable(onToggle),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZemoteSpacing.space2),
        ) {
            Icon(
                family.icon,
                contentDescription = null,
                tint = colors.foregroundSubtlest,
                modifier = Modifier.size(ZemoteSpacing.space4),
            )
            Text(
                text = family.label,
                style = ZemoteType.uiBase,
                fontWeight = FontWeight.Medium,
                color = colors.foregroundSubtlest,
            )
            Text("·", style = ZemoteType.uiBase, color = colors.foregroundSubtlest)
            AnimatedSummaryText(
                text = summary,
                style = ZemoteType.uiBase,
                color = colors.foregroundSubtlest,
                // 网页 summary 是 inline-flex：摘要只占内容宽，chevron 紧跟其后（09 F3）。
                modifier = Modifier.weight(1f, fill = false),
            )
            ExpandChevron(expanded = open, tint = colors.foregroundSubtlest)
        }

        // 组展开/收起：网页 `duration-300 ease-in-out`（09 F5）。
        CollapsibleContent(
            visible = open,
            durationMillis = ZemoteToolContentCollapseMs,
            easing = ZemoteEaseInOut,
        ) {
            // 网页：pt-2（8dp）+ ml-2（8dp）+ border-l（1px，不占布局）+ pl-3.5（14dp），
            // 行间 space-y-2（8dp）。竖线用 drawBehind 画：IntrinsicSize.Min 会向上查询
            // 父布局（SubcomposeLayout）的 intrinsic 尺寸，Compose 直接抛
            // IllegalStateException（上翻历史即崩）。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // pt-2 必须在 drawBehind 之前：竖线从组头下方 8dp 才开始（09 F1）。
                    .padding(top = ZemoteSpacing.space2)
                    .padding(start = ZemoteSpacing.space2)
                    .drawBehind {
                        drawRect(
                            color = colors.border,
                            topLeft = Offset.Zero,
                            size = Size(1.dp.toPx(), size.height),
                        )
                    }
                    // 1px 边框不占布局，用 +1dp 把子行内容左缘补到 web 的 23dp。
                    .padding(start = ZemoteSpacing.space3_5 + 1.dp),
            ) {
                rows.forEachIndexed { index, row ->
                    GroupChildRow(
                        row = row,
                        isRowOpen = isRowOpen,
                        onRowToggle = onRowToggle,
                    )
                    if (index != rows.lastIndex) {
                        Spacer(Modifier.size(ZemoteSpacing.space2))
                    }
                }
            }
        }
    }
}

/**
 * 组内子行：把「本行是否展开」的读取收进自己的重组作用域。
 *
 * [isRowOpen] 读的是一个包含所有工具行 id 的集合，直接在组内容里调用会让任意一行的
 * 展开/折叠都触发整组重组；这里用 [derivedStateOf] 把「集合变化」收敛成「本行布尔值
 * 变化」，只有真正受影响的那一行会重组（展开/折叠语义不变）。
 */
@Composable
private fun GroupChildRow(
    row: JsonObject,
    isRowOpen: (Long) -> Boolean,
    onRowToggle: (Long) -> Unit,
) {
    val rowId = remember(row) { row.rowIdOf() }
    // 父层每次重组都会传新 lambda，用 rememberUpdatedState 持有最新引用：
    // derived 状态本身按 rowId 记住，不会因 lambda 换实例而重建。
    val currentIsRowOpen = rememberUpdatedState(isRowOpen)
    val open = remember(rowId) { derivedStateOf { currentIsRowOpen.value(rowId) } }
    val currentToggle = rememberUpdatedState(onRowToggle)
    ToolCallRow(
        row = row,
        open = open.value,
        onToggle = remember(rowId) { { currentToggle.value(rowId) } },
        showIcon = false,
    )
}

private fun groupSummary(family: ToolFamily, rows: List<JsonObject>): String = when (family) {
    ToolFamily.EXPLORE -> {
        val searches = rows.count {
            val name = it["toolName"].asString().orEmpty().lowercase()
            name.contains("grep") || name.contains("search") || name.contains("find")
        }
        val files = rows.size - searches
        listOfNotNull(
            searches.takeIf { it > 0 }?.let { "$it 搜索" },
            files.takeIf { it > 0 }?.let { "$it 文件" },
        ).joinToString(", ").ifBlank { "${rows.size} 查阅" }
    }

    ToolFamily.TERMINAL -> {
        val failed = rows.count { it["status"].asString() in FailedStatuses }
        val stopped = rows.count { it["status"].asString() == "cancelled" }
        listOfNotNull(
            "${rows.size} 个命令",
            failed.takeIf { it > 0 }?.let { "$it 个失败" },
            stopped.takeIf { it > 0 }?.let { "$it 个已停止" },
        ).joinToString(", ")
    }

    ToolFamily.CHANGES -> "${rows.size} 个文件"
}

private val FailedStatuses = setOf("error", "failed")

/** `rowId` 的本地快捷读取（避免把扩展 import 散布到每个文件）。 */
private fun JsonObject.rowIdOf(): Long = rowId()
