package app.zemote.ui.chat.timeline

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.zemote.ui.chat.asBool
import app.zemote.ui.chat.asObj
import app.zemote.ui.chat.asString
import app.zemote.ui.chat.isStreaming
import app.zemote.ui.chat.kind
import app.zemote.ui.chat.rowId
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteRadius
import app.zemote.ui.theme.ZemoteSpacing
import app.zemote.ui.theme.ZemoteType
import kotlinx.serialization.json.JsonObject

/**
 * 行上的长按操作。
 *
 * 网页 3.17.1 的行级动作只由行数据里的 `actions` 决定：
 * - 用户行：`复制`，`canEdit` 时可 `编辑`，`canRetry` 时可 `重试`；
 * - 助手行：`复制`，`canRetry` 时可 `重试`，`canFork` 且整轮输出已完成时可 `分叉`。
 *
 * 没有 `actions` 的行（工具、产物、标记等）不出长按菜单。
 */
internal enum class RowAction { Copy, Edit, Retry, Fork }

/** 行数据里的 `actions` 三连。 */
internal data class RowActionFlags(
    val canRetry: Boolean = false,
    val canEdit: Boolean = false,
    val canFork: Boolean = false,
)

internal fun rowActionFlags(row: JsonObject): RowActionFlags {
    val actions = row["actions"].asObj() ?: return RowActionFlags()
    return RowActionFlags(
        canRetry = actions["canRetry"].asBool() == true,
        canEdit = actions["canEdit"].asBool() == true,
        canFork = actions["canFork"].asBool() == true,
    )
}

/**
 * 助手行所在轮次是否已经输出完成。
 *
 * 网页的助手行操作条只在 `row.state === 'complete'` 时渲染；这里用
 * 「不是流式」近似，缺省 `state` 视为已完成（历史行不会带 streaming）。
 */
internal fun isRowOutputComplete(row: JsonObject): Boolean =
    row.kind() == "assistantText" && !row.isStreaming()

/** 长按菜单应显示的动作列表（顺序固定：复制 → 编辑 → 重试 → 分叉）。 */
internal fun availableRowActions(row: JsonObject): List<RowAction> {
    val kind = row.kind()
    val flags = rowActionFlags(row)
    val hasText = row["text"].asString()?.isNotBlank() == true
    val complete = isRowOutputComplete(row)
    val actions = ArrayList<RowAction>(4)
    if (kind == "userInput" || kind == "assistantText") {
        if (hasText) actions += RowAction.Copy
    }
    if (kind == "userInput") {
        if (flags.canEdit) actions += RowAction.Edit
        if (flags.canRetry) actions += RowAction.Retry
    }
    if (kind == "assistantText") {
        if (flags.canRetry && complete) actions += RowAction.Retry
        if (flags.canFork && complete) actions += RowAction.Fork
    }
    return actions
}

private fun RowAction.label(): String = when (this) {
    RowAction.Copy -> "复制"
    RowAction.Edit -> "编辑"
    RowAction.Retry -> "重试"
    RowAction.Fork -> "分叉"
}

private fun RowAction.icon(): ImageVector = when (this) {
    RowAction.Copy -> Icons.Default.ContentCopy
    RowAction.Edit -> Icons.Default.Edit
    RowAction.Retry -> Icons.Default.Refresh
    RowAction.Fork -> Icons.Default.CallSplit
}

/**
 * 宿主提供的行操作实现。
 *
 * 复制由行组件自己完成；编辑/重试/分叉需要会话层能力，由 ChatScreen 通过
 * [LocalTimelineRowActions] 注入。未注入时菜单项仍按 `actions` 显示，但不可点。
 */
internal class TimelineRowActionHandler(
    val onEdit: ((JsonObject) -> Unit)? = null,
    val onRetry: ((JsonObject) -> Unit)? = null,
    val onFork: ((JsonObject) -> Unit)? = null,
)

internal val LocalTimelineRowActions = staticCompositionLocalOf<TimelineRowActionHandler?> { null }

/**
 * 长按浮层：把 [row] 包一层长按手势，并在原位弹出操作菜单。
 *
 * [content] 收到应当应用到行根节点的修饰符（长按手势）。
 */
@Composable
internal fun RowActionContainer(
    row: JsonObject,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable (Modifier) -> Unit,
) {
    var menuOpen by remember(row.rowId()) { mutableStateOf(false) }
    val hasActions = remember(row) { availableRowActions(row).isNotEmpty() }
    val interaction = remember(row.rowId()) { MutableInteractionSource() }
    val gesture = Modifier.combinedClickable(
        interactionSource = interaction,
        indication = null,
        onClickLabel = if (onClick != null) "展开或收起" else null,
        onLongClickLabel = if (hasActions) "更多操作" else null,
        onLongClick = if (hasActions) {
            { menuOpen = true }
        } else {
            null
        },
        onClick = { onClick?.invoke() },
    )

    Box(modifier) {
        content(gesture)
        RowActionMenu(row = row, expanded = menuOpen, onDismiss = { menuOpen = false })
    }
}

/** 自绘外观的下拉菜单（`bg-menu` + 1px 边框 + 8dp 圆角）。 */
@Composable
private fun RowActionMenu(
    row: JsonObject,
    expanded: Boolean,
    onDismiss: () -> Unit,
) {
    if (!expanded) return
    val colors = LocalZemoteColors.current
    val context = LocalContext.current
    val handler = LocalTimelineRowActions.current
    val actions = remember(row) { availableRowActions(row) }

    DropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss,
        shape = ZemoteRadius.lgShape,
        containerColor = colors.menu,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, colors.popoverBorder),
    ) {
        actions.forEach { action ->
            val enabled = when (action) {
                RowAction.Copy -> true
                RowAction.Edit -> handler?.onEdit != null
                RowAction.Retry -> handler?.onRetry != null
                RowAction.Fork -> handler?.onFork != null
            }
            DropdownMenuItem(
                text = {
                    Text(
                        text = action.label(),
                        style = ZemoteType.uiBase,
                        color = if (enabled) colors.foreground else colors.foregroundSubtlest,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = action.icon(),
                        contentDescription = null,
                        tint = if (enabled) colors.foregroundSubtle else colors.foregroundSubtlest,
                        modifier = Modifier.size(ZemoteSpacing.space3_5),
                    )
                },
                enabled = enabled,
                colors = MenuDefaults.itemColors(
                    textColor = colors.foreground,
                    leadingIconColor = colors.foregroundSubtle,
                    disabledTextColor = colors.foregroundSubtlest,
                    disabledLeadingIconColor = colors.foregroundSubtlest,
                ),
                onClick = {
                    onDismiss()
                    when (action) {
                        RowAction.Copy -> copyMessage(context, row["text"].asString().orEmpty())
                        RowAction.Edit -> handler?.onEdit?.invoke(row)
                        RowAction.Retry -> handler?.onRetry?.invoke(row)
                        RowAction.Fork -> handler?.onFork?.invoke(row)
                    }
                },
            )
        }
    }
}

/** 复制成功后就地提示（网页图标 copy→check 1.2s；移动端用短 toast 等价）。 */
private fun copyMessage(context: Context, text: String) {
    if (text.isBlank()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("message", text))
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
}
