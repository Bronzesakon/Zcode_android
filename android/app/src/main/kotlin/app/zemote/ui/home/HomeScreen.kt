package app.zemote.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zemote.data.AppTheme
import app.zemote.engine.RelayState
import app.zemote.ui.chat.TaskItem
import app.zemote.ui.chat.WorkspaceItem
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteRadius
import app.zemote.ui.theme.ZemoteSpacing
import app.zemote.ui.theme.ZemoteType
import kotlin.math.roundToInt

/**
 * 「对话」Tab。
 *
 * 布局（UI-SPEC §1）：
 * ```
 * 页头（单行电脑名 + 状态 + 刷新 + 主题）
 * [异常横幅]
 * 已置顶（抽出）
 * 工作区横条（默认折叠）
 *   任务行（未读自动展开时只显示未读）
 * ```
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onRefresh: () -> Unit,
    onToggleWorkspace: (String) -> Unit,
    onOpenTask: (WorkspaceItem, TaskItem) -> Unit,
    onNewConversation: (WorkspaceItem) -> Unit,
    onDismissError: () -> Unit,
    onPin: (WorkspaceItem, TaskItem, Boolean) -> Unit,
    onArchive: (WorkspaceItem, TaskItem) -> Unit,
    onDelete: (WorkspaceItem, TaskItem) -> Unit,
    onOpenPairing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalZemoteColors.current

    Column(modifier = modifier.fillMaxSize().background(colors.background)) {
        HomeHeader(
            deviceName = state.deviceName,
            relayState = state.relayState,
        )
        HorizontalDivider(color = colors.border)

        if (state.error != null) {
            ErrorBanner(text = state.error, onRetry = onRefresh, onDismiss = onDismissError)
        }
        if (state.bridgeDegraded != null) {
            ErrorBanner(
                text = "连接已降级：${state.bridgeDegraded}",
                onRetry = onRefresh,
                onDismiss = null,
            )
        }

        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (state.visibleWorkspaces.isEmpty()) {
                EmptyState(
                    loading = state.loading,
                    paired = state.deviceName != null,
                    error = state.error,
                    onRefresh = onRefresh,
                    onOpenPairing = onOpenPairing,
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (state.pinnedTasks.isNotEmpty()) {
                        item(key = "pinned-header") {
                            SectionLabel("已置顶")
                        }
                        items(state.pinnedTasks, key = { "pinned-${it.id}" }) { task ->
                            val workspace = state.visibleWorkspaces.firstOrNull {
                                it.tasks.any { t -> t.id == task.id }
                            }
                            if (workspace != null) {
                                TaskRow(
                                    task = task,
                                    preview = state.previewOf(task),
                                    busy = task.id in state.busyTasks,
                                    onOpen = { onOpenTask(workspace, task) },
                                    onPin = { onPin(workspace, task, false) },
                                    onArchive = { onArchive(workspace, task) },
                                    onDelete = { onDelete(workspace, task) },
                                )
                            }
                        }
                    }

                    items(state.visibleWorkspaces, key = { it.key }) { workspace ->
                        WorkspaceCard(
                            workspace = workspace,
                            expanded = state.isExpanded(workspace),
                            tasks = state.tasksOf(workspace),
                            canShowAll = state.canShowAll(workspace),
                            previewOf = state::previewOf,
                            busyTasks = state.busyTasks,
                            onToggle = { onToggleWorkspace(workspace.key) },
                            onNewConversation = { onNewConversation(workspace) },
                            onOpenTask = { onOpenTask(workspace, it) },
                            onPin = { onPin(workspace, it, true) },
                            onArchive = { onArchive(workspace, it) },
                            onDelete = { onDelete(workspace, it) },
                        )
                    }

                    item(key = "bottom-space") { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(
    deviceName: String?,
    relayState: RelayState,
) {
    val colors = LocalZemoteColors.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.header)
            .statusBarsPadding()
            .height(54.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = deviceName ?: "未连接电脑",
            modifier = Modifier.weight(1f),
            style = ZemoteType.uiLg,
            fontWeight = FontWeight.Medium,
            color = colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(statusColor(relayState, colors)),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = statusText(relayState),
            style = ZemoteType.uiSm,
            color = colors.foregroundSubtle,
            maxLines = 1,
        )
    }
}

@Composable
private fun ErrorBanner(text: String, onRetry: () -> Unit, onDismiss: (() -> Unit)?) {
    val colors = LocalZemoteColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.destructive.copy(alpha = 0.10f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = colors.destructive,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = ZemoteType.uiSm,
            color = colors.destructive,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onRetry) { Text("重试", style = ZemoteType.uiSm) }
        if (onDismiss != null) {
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "关闭", modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    val colors = LocalZemoteColors.current
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        style = ZemoteType.uiXs,
        color = colors.foregroundSubtlest,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun WorkspaceCard(
    workspace: WorkspaceItem,
    expanded: Boolean,
    tasks: List<TaskItem>,
    canShowAll: Boolean,
    previewOf: (TaskItem) -> String?,
    busyTasks: Set<String>,
    onToggle: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenTask: (TaskItem) -> Unit,
    onPin: (TaskItem) -> Unit,
    onArchive: (TaskItem) -> Unit,
    onDelete: (TaskItem) -> Unit,
) {
    val colors = LocalZemoteColors.current
    val isRemote = workspace.kind == "remote"

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = colors.foregroundSubtle,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surface),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    tint = colors.foregroundSubtle,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = workspace.label.ifBlank { workspace.key },
                        style = ZemoteType.uiBase,
                        fontWeight = FontWeight.Medium,
                        color = colors.foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isRemote) Tag("远程")
                }
                Text(
                    text = workspace.path,
                    style = ZemoteType.uiSm.copy(fontFamily = FontFamily.Monospace),
                    color = colors.foregroundSubtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                val error = workspace.lastError
                if (!error.isNullOrBlank()) {
                    Text(
                        text = error,
                        style = ZemoteType.uiXs,
                        color = colors.destructive,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = relativeTime(workspace.tasks.maxOfOrNull { it.updatedAt ?: 0L } ?: 0L),
                    style = ZemoteType.uiXs,
                    color = colors.foregroundSubtle,
                )
                IconButton(onClick = onNewConversation, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "新建对话",
                        tint = colors.foregroundSubtle,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        if (expanded && tasks.isNotEmpty()) {
            Column(Modifier.padding(start = 28.dp, end = 8.dp)) {
                tasks.forEach { task ->
                    TaskRow(
                        task = task,
                        preview = previewOf(task),
                        busy = task.id in busyTasks,
                        onOpen = { onOpenTask(task) },
                        onPin = { onPin(task) },
                        onArchive = { onArchive(task) },
                        onDelete = { onDelete(task) },
                    )
                }
                if (canShowAll) {
                    TextButton(onClick = onToggle, modifier = Modifier.padding(start = 4.dp)) {
                        Text("查看全部对话", style = ZemoteType.uiSm)
                    }
                }
            }
        }
        HorizontalDivider(color = colors.border.copy(alpha = 0.5f))
    }
}

@Composable
private fun Tag(text: String) {
    val colors = LocalZemoteColors.current
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(colors.surface)
            .padding(horizontal = 6.dp, vertical = 1.dp),
        style = ZemoteType.ui2xs,
        color = colors.foregroundSubtle,
    )
}

/**
 * 任务行 + 左滑三按钮。
 *
 * 没有用 `SwipeToDismissBox`：它把"滑动"当成删除意图，松手就回弹，露出的按钮点不到。
 * 这里用最小实现：内容整体偏移，露出固定宽度的动作区；松手按半程阈值吸附。
 */
@Composable
private fun TaskRow(
    task: TaskItem,
    preview: String?,
    busy: Boolean,
    onOpen: () -> Unit,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalZemoteColors.current
    val density = LocalDensity.current
    val actionWidth = 72.dp
    val maxRevealPx = with(density) { (actionWidth * 3).toPx() }
    var offsetX by remember(task.id) { mutableFloatStateOf(0f) }

    Box(Modifier.fillMaxWidth()) {
        // 动作区（在内容下面）
        Row(
            modifier = Modifier
                .matchParentSize()
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SwipeAction("置顶", colors.surface, colors.foreground, onPin)
            SwipeAction("归档", colors.surface, colors.foreground, onArchive)
            SwipeAction("删除", colors.destructive, colors.destructiveForeground, onDelete)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .clip(RoundedCornerShape(8.dp))
                .background(colors.background)
                .clickable {
                    if (offsetX < -1f) offsetX = 0f else onOpen()
                }
                .pointerInput(task.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            offsetX = if (offsetX < -maxRevealPx / 2f) -maxRevealPx else 0f
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            offsetX = (offsetX + dragAmount).coerceIn(-maxRevealPx, 0f)
                        },
                    )
                }
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TaskStatusSlot(task)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = task.title?.takeIf { it.isNotBlank() } ?: "未命名",
                    style = ZemoteType.uiBase,
                    color = colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (preview != null && (task.unreadAt != null || task.running)) {
                    Text(
                        text = preview,
                        style = ZemoteType.uiSm,
                        color = colors.foregroundSubtle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = relativeTime(task.updatedAt ?: 0L),
                    style = ZemoteType.uiXs,
                    color = colors.foregroundSubtle,
                )
                StatusPill(task)
            }
            if (busy) {
                Spacer(Modifier.width(6.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = colors.foregroundSubtle,
                )
            }
        }
    }
}

@Composable
private fun SwipeAction(
    label: String,
    background: Color,
    foreground: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(72.dp)
            .height(52.dp)
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = ZemoteType.uiSm, color = foreground)
    }
}

@Composable
private fun TaskStatusSlot(task: TaskItem) {
    val colors = LocalZemoteColors.current
    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
        when {
            task.running -> CircularProgressIndicator(
                modifier = Modifier.size(13.dp),
                strokeWidth = 1.5.dp,
                color = colors.foregroundSubtle,
            )
            task.unreadAt != null -> Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(unreadDotColor(colors)),
            )
            task.pinned -> Icon(
                Icons.Default.PushPin,
                contentDescription = "已置顶",
                tint = colors.foregroundSubtle,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

@Composable
private fun StatusPill(task: TaskItem) {
    val colors = LocalZemoteColors.current
    val (label, icon, tint) = when (task.displayStatus) {
        "running" -> Triple("运行中", Icons.Default.Sync, colors.foregroundSubtle)
        "completed" -> Triple("已完成", Icons.Default.Check, colors.foregroundSubtle)
        "error" -> Triple("错误", Icons.Default.ErrorOutline, colors.destructive)
        else -> return
    }
    Row(
        modifier = Modifier
            .padding(top = 2.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(colors.surface)
            .padding(horizontal = 6.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(10.dp))
        Text(label, style = ZemoteType.ui2xs, color = tint)
    }
}

@Composable
private fun EmptyState(
    loading: Boolean,
    paired: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onOpenPairing: () -> Unit,
) {
    val colors = LocalZemoteColors.current
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when {
            loading -> CircularProgressIndicator(color = colors.foregroundSubtle)
            !paired -> {
                Text("还没有连接电脑", style = ZemoteType.uiLg, color = colors.foreground)
                Spacer(Modifier.height(8.dp))
                Text(
                    "在电脑端 ZCode 打开「手机远控」，扫码或粘贴链接即可连接。",
                    style = ZemoteType.uiSm,
                    color = colors.foregroundSubtle,
                )
                Spacer(Modifier.height(20.dp))
                ButtonPrimary("扫码配对", onOpenPairing)
            }
            error != null -> {
                Text(error, style = ZemoteType.uiSm, color = colors.destructive)
                Spacer(Modifier.height(12.dp))
                ButtonPrimary("重试", onRefresh)
            }
            else -> {
                Text("这台电脑还没有工作区", style = ZemoteType.uiBase, color = colors.foreground)
                Spacer(Modifier.height(8.dp))
                Text("在电脑端打开一个项目后再刷新。", style = ZemoteType.uiSm, color = colors.foregroundSubtle)
                Spacer(Modifier.height(16.dp))
                ButtonPrimary("刷新", onRefresh)
            }
        }
    }
}

@Composable
private fun ButtonPrimary(text: String, onClick: () -> Unit) {
    val colors = LocalZemoteColors.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = colors.primary,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
            style = ZemoteType.uiBase,
            color = colors.primaryForeground,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ---------------------------------------------------------------------------
// 展示辅助
// ---------------------------------------------------------------------------

private fun statusText(state: RelayState): String = when (state) {
    RelayState.Idle -> "未连接"
    RelayState.Connecting -> "连接中"
    RelayState.Authenticating -> "认证中"
    RelayState.Waiting -> "等待配对"
    RelayState.Paired -> "已连接"
    RelayState.Reconnecting -> "重连中"
    RelayState.Error -> "连接错误"
    RelayState.Kicked -> "已被接管"
    RelayState.Closed -> "已断开"
}

private fun statusColor(state: RelayState, colors: app.zemote.ui.theme.ZemoteColors): Color =
    when (state) {
        RelayState.Paired -> colors.success
        RelayState.Connecting, RelayState.Authenticating,
        RelayState.Waiting, RelayState.Reconnecting -> colors.warning
        RelayState.Error, RelayState.Kicked -> colors.destructive
        else -> colors.foregroundSubtlest
    }

/** 未读点：网页 `bg-sky-500 dark:bg-sky-400`。 */
private fun unreadDotColor(colors: app.zemote.ui.theme.ZemoteColors): Color =
    if (colors.background.luminance() > 0.5f) Color(0xFF0284C7) else Color(0xFF38BDF8)

/** 相对时间：2m / 1h / 昨天 / 10-12。 */
internal fun relativeTime(ms: Long): String {
    if (ms <= 0L) return ""
    val now = System.currentTimeMillis()
    val delta = now - ms
    if (delta < 0) return "刚刚"
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour
    return when {
        delta < minute -> "刚刚"
        delta < hour -> "${delta / minute}m"
        delta < day -> "${delta / hour}h"
        delta < 2 * day -> "昨天"
        delta < 7 * day -> "${delta / day}d"
        else -> {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
            val m = cal.get(java.util.Calendar.MONTH) + 1
            val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
            "$m-$d"
        }
    }
}
