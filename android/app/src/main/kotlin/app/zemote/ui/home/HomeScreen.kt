package app.zemote.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zemote.engine.RelayState
import app.zemote.ui.chat.TaskItem
import app.zemote.ui.chat.WorkspaceItem
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteRadius
import app.zemote.ui.theme.ZemoteSpacing
import app.zemote.ui.theme.ZemoteType

/**
 * 「对话」Tab —— 3.17.1 项目树。
 *
 * 结构：
 * ```
 * 页头（电脑名 + 连接态）
 * [异常横幅]
 * 工具条（任务 / 分组·列表 / 刷新 / 新建）
 * 已置顶（抽出，独立成区）
 * 分组视图：项目行 → 展开后的任务行
 * 列表视图：今天 / 昨天 / 更早 → 任务行（带项目名）
 * ```
 *
 * 行高跟网页：项目行 32dp、任务行 28dp；14sp 基准、1.5 行高、4dp 栅格。
 * 任务操作只走行尾 ⋮ 菜单（置顶 / 归档 / 删除），不实现左滑。
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
    val view by HomeViewPreference.view.collectAsStateWithLifecycle()
    val createWorkspace = state.visibleWorkspaces.firstOrNull()

    Column(modifier = modifier.fillMaxSize().background(colors.background)) {
        HomeHeader(
            deviceName = state.deviceName,
            relayState = state.relayState,
            onOpenPairing = onOpenPairing,
        )
        HorizontalDivider(color = colors.border)

        if (state.error != null) {
            ErrorBanner(
                text = state.error,
                onRetry = onRefresh,
                onDismiss = onDismissError,
                onRepair = if (state.relayState != RelayState.Paired) onOpenPairing else null,
            )
        }
        if (state.bridgeDegraded != null) {
            ErrorBanner(
                text = "连接已降级：${state.bridgeDegraded}",
                onRetry = onRefresh,
                onDismiss = null,
            )
        }

        HomeToolbar(
            view = view,
            loading = state.loading,
            canCreate = createWorkspace != null,
            onSelectView = { HomeViewPreference.select(it) },
            onRefresh = onRefresh,
            onNewConversation = { createWorkspace?.let(onNewConversation) },
        )

        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                state.visibleWorkspaces.isNotEmpty() -> HomeTree(
                    state = state,
                    view = view,
                    onToggleWorkspace = onToggleWorkspace,
                    onOpenTask = onOpenTask,
                    onNewConversation = onNewConversation,
                    onPin = onPin,
                    onArchive = onArchive,
                    onDelete = onDelete,
                )

                state.loading -> HomeSkeleton()

                else -> EmptyState(
                    loading = false,
                    paired = state.deviceName != null,
                    error = state.error,
                    onRefresh = onRefresh,
                    onOpenPairing = onOpenPairing,
                )
            }
        }
    }
}

@Composable
private fun HomeHeader(
    deviceName: String?,
    relayState: RelayState,
    onOpenPairing: () -> Unit,
) {
    val colors = LocalZemoteColors.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.header)
            .statusBarsPadding()
            .height(ZemoteSpacing.of(13.5f))
            .padding(horizontal = ZemoteSpacing.space3_5),
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
        // 常驻入口：即使已经有一台设备，也能随时重新扫码/粘贴链接覆盖它。
        DenseIconButton(
            icon = Icons.Default.QrCodeScanner,
            description = "扫码或粘贴链接",
            onClick = onOpenPairing,
            size = 32.dp,
            iconSize = 18.dp,
        )
        Dot(color = statusColor(relayState, colors), size = 7.dp)
        Spacer(Modifier.width(ZemoteSpacing.space1_5))
        Text(
            text = statusText(relayState),
            style = ZemoteType.uiSm,
            color = colors.foregroundSubtle,
            maxLines = 1,
        )
    }
}

/**
 * 顶部工具条：标题 + 视图切换 + 刷新 + 新建。
 *
 * 刷新按钮与下拉刷新并存（H3）；视图只影响本次会话（[HomeViewPreference]）。
 */
@Composable
private fun HomeToolbar(
    view: HomeView,
    loading: Boolean,
    canCreate: Boolean,
    onSelectView: (HomeView) -> Unit,
    onRefresh: () -> Unit,
    onNewConversation: () -> Unit,
) {
    val colors = LocalZemoteColors.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ZemoteSpacing.space10)
            .padding(horizontal = ZemoteSpacing.space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZemoteSpacing.space1),
    ) {
        Text(
            text = "任务",
            modifier = Modifier.weight(1f),
            style = ZemoteType.uiBase,
            fontWeight = FontWeight.Medium,
            color = colors.foregroundSubtle,
            maxLines = 1,
        )
        ViewSwitch(view = view, onSelect = onSelectView)
        Spacer(Modifier.width(ZemoteSpacing.space0_5))
        if (loading) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 1.5.dp,
                    color = colors.foregroundSubtle,
                )
            }
        } else {
            DenseIconButton(
                icon = Icons.Default.Refresh,
                description = "刷新",
                onClick = onRefresh,
            )
        }
        DenseIconButton(
            icon = Icons.Default.Add,
            description = "新建对话",
            onClick = onNewConversation,
            enabled = canCreate,
            iconSize = 18.dp,
        )
    }
}

/** 分段控件：分组 / 列表（网页视图菜单的两项）。 */
@Composable
private fun ViewSwitch(
    view: HomeView,
    onSelect: (HomeView) -> Unit,
) {
    val colors = LocalZemoteColors.current
    val shape = RoundedCornerShape(ZemoteRadius.lg)

    Row(
        modifier = Modifier
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.border, shape)
            .padding(ZemoteSpacing.space0_5),
        horizontalArrangement = Arrangement.spacedBy(ZemoteSpacing.space0_5),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeView.entries.forEach { item ->
            val selected = item == view
            Box(
                modifier = Modifier
                    .height(ZemoteSpacing.space6)
                    .clip(RoundedCornerShape(ZemoteRadius.md))
                    .background(if (selected) colors.selected else Color.Transparent)
                    .clickable { onSelect(item) }
                    .padding(horizontal = ZemoteSpacing.space2),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = item.label,
                    style = ZemoteType.uiSm,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    color = if (selected) colors.foreground else colors.foregroundSubtle,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun HomeTree(
    state: HomeUiState,
    view: HomeView,
    onToggleWorkspace: (String) -> Unit,
    onOpenTask: (WorkspaceItem, TaskItem) -> Unit,
    onNewConversation: (WorkspaceItem) -> Unit,
    onPin: (WorkspaceItem, TaskItem, Boolean) -> Unit,
    onArchive: (WorkspaceItem, TaskItem) -> Unit,
    onDelete: (WorkspaceItem, TaskItem) -> Unit,
) {
    val listState = rememberLazyListState()
    // 分组/列表切换时回到顶部，避免沿用上一视图的滚动位置（首行被工具栏裁掉）。
    LaunchedEffect(view) { listState.scrollToItem(0) }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        if (state.pinnedEntries.isNotEmpty()) {
            item(key = "pinned-label") { SectionLabel("已置顶") }
            items(
                state.pinnedEntries,
                // 去重键与 web 一致：workspaceKey + taskId（跨 host 可能同名 id）。
                key = { "pinned-${it.workspace.key}-${it.task.id}" },
            ) { entry ->
                TaskRow(
                    task = entry.task,
                    busy = entry.task.id in state.busyTasks,
                    projectLabel = null,
                    onClick = { onOpenTask(entry.workspace, entry.task) },
                    onPin = { pinned -> onPin(entry.workspace, entry.task, pinned) },
                    onArchive = { onArchive(entry.workspace, entry.task) },
                    onDelete = { onDelete(entry.workspace, entry.task) },
                    modifier = Modifier.padding(horizontal = ZemoteSpacing.space2),
                )
            }
            item(key = "pinned-gap") { Spacer(Modifier.height(ZemoteSpacing.space1)) }
        }

        when (view) {
            HomeView.Grouped -> {
                item(key = "projects-label") { SectionLabel("项目") }
                items(state.projectSections, key = { "project-${it.workspace.key}" }) { section ->
                    ProjectGroup(
                        section = section,
                        busyTasks = state.busyTasks,
                        onToggle = { onToggleWorkspace(section.workspace.key) },
                        onOpenTask = { task -> onOpenTask(section.workspace, task) },
                        onNewConversation = { onNewConversation(section.workspace) },
                        onPin = { task, pinned -> onPin(section.workspace, task, pinned) },
                        onArchive = { task -> onArchive(section.workspace, task) },
                        onDelete = { task -> onDelete(section.workspace, task) },
                    )
                }
            }

            HomeView.Timeline -> {
                state.timelineDays().forEach { day ->
                    item(key = "day-${day.label}") { DayLabel(day.label) }
                    items(
                        day.entries,
                        key = { "timeline-${it.workspace.key}-${it.task.id}" },
                    ) { entry ->
                        TaskRow(
                            task = entry.task,
                            busy = entry.task.id in state.busyTasks,
                            projectLabel = workspaceDisplayLabel(entry.workspace),
                            onClick = { onOpenTask(entry.workspace, entry.task) },
                            onPin = { pinned -> onPin(entry.workspace, entry.task, pinned) },
                            onArchive = { onArchive(entry.workspace, entry.task) },
                            onDelete = { onDelete(entry.workspace, entry.task) },
                            modifier = Modifier.padding(horizontal = ZemoteSpacing.space2),
                        )
                    }
                }
            }
        }

        item(key = "bottom-space") { Spacer(Modifier.height(ZemoteSpacing.space6)) }
    }
}

/** 项目行（32dp）+ 展开后的任务行；任务行与项目图标左对齐（网页同为 18px）。 */
@Composable
private fun ProjectGroup(
    section: ProjectSection,
    busyTasks: Set<String>,
    onToggle: () -> Unit,
    onOpenTask: (TaskItem) -> Unit,
    onNewConversation: () -> Unit,
    onPin: (TaskItem, Boolean) -> Unit,
    onArchive: (TaskItem) -> Unit,
    onDelete: (TaskItem) -> Unit,
) {
    val colors = LocalZemoteColors.current

    Column(Modifier.fillMaxWidth().padding(horizontal = ZemoteSpacing.space2)) {
        ProjectRow(
            section = section,
            onToggle = onToggle,
            onNewConversation = onNewConversation,
        )
        if (section.expanded) {
            Column(Modifier.fillMaxWidth()) {
                section.tasks.forEach { task ->
                    key(task.id) {
                        TaskRow(
                            task = task,
                            busy = task.id in busyTasks,
                            projectLabel = null,
                            onClick = { onOpenTask(task) },
                            onPin = { pinned -> onPin(task, pinned) },
                            onArchive = { onArchive(task) },
                            onDelete = { onDelete(task) },
                        )
                    }
                }
                if (section.canShowAll) {
                    Text(
                        text = "查看全部对话",
                        modifier = Modifier
                            .clip(RoundedCornerShape(ZemoteRadius.sm))
                            .clickable(onClick = onToggle)
                            .padding(
                                start = ZemoteSpacing.space6,
                                top = ZemoteSpacing.space0_5,
                                bottom = ZemoteSpacing.space1_5,
                                end = ZemoteSpacing.space2,
                            ),
                        style = ZemoteType.uiSm,
                        color = colors.foregroundSubtle,
                    )
                }
                if (section.tasks.isEmpty() && section.totalTasks == 0) {
                    Text(
                        text = "暂无任务",
                        modifier = Modifier.padding(
                            start = ZemoteSpacing.space6,
                            top = ZemoteSpacing.space0_5,
                            bottom = ZemoteSpacing.space1_5,
                        ),
                        style = ZemoteType.uiSm,
                        color = colors.foregroundSubtlest,
                    )
                }
            }
        }
        Spacer(Modifier.height(ZemoteSpacing.space0_5))
    }
}

@Composable
private fun ProjectRow(
    section: ProjectSection,
    onToggle: () -> Unit,
    onNewConversation: () -> Unit,
) {
    val colors = LocalZemoteColors.current
    val workspace = section.workspace

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ZemoteSpacing.space8)
            .clip(RoundedCornerShape(ZemoteRadius.lg))
            .clickable(onClick = onToggle)
            .padding(start = ZemoteSpacing.space2, end = ZemoteSpacing.space0_5),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (section.expanded) Icons.Outlined.FolderOpen else Icons.Outlined.Folder,
            contentDescription = if (section.expanded) "收起项目" else "展开项目",
            tint = colors.foregroundSubtle,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(ZemoteSpacing.space2))

        // 项目名 + 设备/离线标签：名称可截断，标签不被压缩。
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = workspaceDisplayLabel(workspace),
                modifier = Modifier.weight(1f, fill = false),
                style = ZemoteType.uiBase,
                color = colors.foregroundSubtle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (workspace.kind == "remote") {
                Spacer(Modifier.width(ZemoteSpacing.space1_5))
                Text(
                    text = "远程",
                    style = ZemoteType.uiSm,
                    color = colors.foregroundSubtlest,
                    maxLines = 1,
                )
            }
            if (section.hasUnread && !section.expanded) {
                Spacer(Modifier.width(ZemoteSpacing.space1_5))
                Dot(color = colors.iconBlue)
            }
            if (section.offline) {
                Spacer(Modifier.width(ZemoteSpacing.space1_5))
                Text(
                    text = "离线",
                    style = ZemoteType.uiSm,
                    color = colors.foregroundSubtle,
                    maxLines = 1,
                )
            }
        }

        Spacer(Modifier.width(ZemoteSpacing.space1_5))
        Text(
            text = section.totalTasks.toString(),
            style = ZemoteType.uiSm,
            color = colors.foregroundSubtle,
            maxLines = 1,
        )
        Spacer(Modifier.width(ZemoteSpacing.space0_5))
        ProjectOverflowMenu(
            key = workspace.key,
            onNewConversation = onNewConversation,
        )
    }
}

/** 任务行（28dp）：状态槽 + 标题 + [项目名] + 时间 + 行尾 ⋮。 */
@Composable
private fun TaskRow(
    task: TaskItem,
    busy: Boolean,
    projectLabel: String?,
    onClick: () -> Unit,
    onPin: (Boolean) -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalZemoteColors.current
    val time = relativeTime(task.updatedAt ?: 0L)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ZemoteSpacing.space7)
            .clip(RoundedCornerShape(ZemoteRadius.lg))
            .clickable(onClick = onClick)
            .padding(start = ZemoteSpacing.space2, end = ZemoteSpacing.space0_5),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TaskStatusSlot(task)
        Spacer(Modifier.width(ZemoteSpacing.space2))
        Text(
            text = task.title?.takeIf { it.isNotBlank() } ?: "未命名",
            modifier = Modifier.weight(1f),
            style = ZemoteType.uiBase,
            color = colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val label = projectLabel?.takeIf { it.isNotBlank() }
        if (label != null) {
            Spacer(Modifier.width(ZemoteSpacing.space1_5))
            Text(
                text = label,
                modifier = Modifier.widthIn(max = ZemoteSpacing.space24),
                style = ZemoteType.uiSm,
                color = colors.foregroundSubtlest,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (time.isNotEmpty()) {
            Spacer(Modifier.width(ZemoteSpacing.space1_5))
            Text(
                text = time,
                style = ZemoteType.uiSm,
                color = colors.foregroundSubtle,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(ZemoteSpacing.space0_5))
        TaskOverflowMenu(
            task = task,
            busy = busy,
            onPin = onPin,
            onArchive = onArchive,
            onDelete = onDelete,
        )
    }
}

/** 状态槽：运行中 > 错误 > 未读 > 置顶（与网页判据一致）。 */
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

            task.displayStatus == "error" -> Dot(color = colors.destructive)

            task.unreadAt != null -> Dot(color = unreadDotColor(colors))

            task.pinned -> Icon(
                imageVector = Icons.Default.PushPin,
                contentDescription = "已置顶",
                tint = colors.foregroundSubtle,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

@Composable
private fun ProjectOverflowMenu(key: String, onNewConversation: () -> Unit) {
    var open by remember(key) { mutableStateOf(false) }

    Box(contentAlignment = Alignment.Center) {
        DenseIconButton(
            icon = Icons.Default.MoreVert,
            description = "项目操作",
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("新建对话", style = ZemoteType.uiBase) },
                leadingIcon = {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                onClick = {
                    open = false
                    onNewConversation()
                },
            )
        }
    }
}

/** 行尾 ⋮：置顶 / 归档 / 删除（删除二次确认）。 */
@Composable
private fun TaskOverflowMenu(
    task: TaskItem,
    busy: Boolean,
    onPin: (Boolean) -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalZemoteColors.current
    var open by remember(task.id) { mutableStateOf(false) }
    var confirmDelete by remember(task.id) { mutableStateOf(false) }

    Box(contentAlignment = Alignment.Center) {
        DenseIconButton(
            icon = Icons.Default.MoreVert,
            description = "任务操作",
            onClick = { open = true },
            enabled = !busy,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(if (task.pinned) "取消置顶" else "置顶", style = ZemoteType.uiBase) },
                leadingIcon = {
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = null,
                        tint = colors.foregroundSubtle,
                        modifier = Modifier.size(16.dp),
                    )
                },
                onClick = {
                    open = false
                    onPin(!task.pinned)
                },
            )
            DropdownMenuItem(
                text = { Text("归档", style = ZemoteType.uiBase) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Archive,
                        contentDescription = null,
                        tint = colors.foregroundSubtle,
                        modifier = Modifier.size(16.dp),
                    )
                },
                onClick = {
                    open = false
                    onArchive()
                },
            )
            DropdownMenuItem(
                text = { Text("删除", style = ZemoteType.uiBase, color = colors.destructive) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = colors.destructive,
                        modifier = Modifier.size(16.dp),
                    )
                },
                onClick = {
                    open = false
                    confirmDelete = true
                },
            )
        }
    }

    if (confirmDelete) {
        val title = task.title?.takeIf { it.isNotBlank() } ?: "未命名"
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除任务？", style = ZemoteType.uiLg, color = colors.foreground) },
            text = {
                Text(
                    text = "「${title}」将被删除，且无法恢复。",
                    style = ZemoteType.uiSm,
                    color = colors.foregroundSubtle,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) {
                    Text("删除", style = ZemoteType.uiBase, color = colors.destructive)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("取消", style = ZemoteType.uiBase, color = colors.foregroundSubtle)
                }
            },
            containerColor = colors.popover,
            titleContentColor = colors.foreground,
            textContentColor = colors.foregroundSubtle,
            shape = RoundedCornerShape(ZemoteRadius.xl),
        )
    }
}

@Composable
private fun DenseIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    size: Dp = 24.dp,
    iconSize: Dp = 14.dp,
) {
    val colors = LocalZemoteColors.current
    val tint = if (enabled) {
        colors.foregroundSubtle
    } else {
        colors.foregroundSubtle.copy(alpha = colors.foregroundSubtle.alpha * 0.4f)
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(ZemoteRadius.md))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    val colors = LocalZemoteColors.current
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = ZemoteSpacing.space4,
                end = ZemoteSpacing.space4,
                top = ZemoteSpacing.space2_5,
                bottom = ZemoteSpacing.space1,
            ),
        style = ZemoteType.uiBase,
        color = colors.foregroundSubtlest,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun DayLabel(text: String) {
    val colors = LocalZemoteColors.current
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = ZemoteSpacing.space4,
                end = ZemoteSpacing.space4,
                top = ZemoteSpacing.space2,
                bottom = ZemoteSpacing.space0_5,
            ),
        style = ZemoteType.uiBase,
        color = colors.foregroundSubtle,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun ErrorBanner(
    text: String,
    onRetry: () -> Unit,
    onDismiss: (() -> Unit)?,
    onRepair: (() -> Unit)? = null,
) {
    val colors = LocalZemoteColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.destructive.copy(alpha = 0.10f))
            .padding(horizontal = ZemoteSpacing.space3, vertical = ZemoteSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZemoteSpacing.space2),
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
        if (onRepair != null) {
            TextButton(onClick = onRepair) { Text("重新扫码", style = ZemoteType.uiSm) }
        }
        TextButton(onClick = onRetry) { Text("重试", style = ZemoteType.uiSm) }
        if (onDismiss != null) {
            DenseIconButton(
                icon = Icons.Default.Close,
                description = "关闭",
                onClick = onDismiss,
            )
        }
    }
}

/** 加载骨架：3 组「项目行 + 2 条任务行」，尺寸与真实行一致。 */
@Composable
private fun HomeSkeleton() {
    Column(Modifier.fillMaxSize().padding(horizontal = ZemoteSpacing.space3, vertical = ZemoteSpacing.space2)) {
        repeat(3) { index ->
            Row(
                modifier = Modifier.fillMaxWidth().height(ZemoteSpacing.space8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkeletonBar(width = 16.dp, height = 16.dp)
                Spacer(Modifier.width(ZemoteSpacing.space2))
                SkeletonBar(width = if (index % 2 == 0) 120.dp else 88.dp, height = 12.dp)
                Spacer(Modifier.weight(1f))
                SkeletonBar(width = 20.dp, height = 10.dp)
            }
            Column(Modifier.padding(start = ZemoteSpacing.space6, top = ZemoteSpacing.space1)) {
                repeat(2) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(ZemoteSpacing.space7),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SkeletonBar(width = 8.dp, height = 8.dp, shape = CircleShape)
                        Spacer(Modifier.width(ZemoteSpacing.space2))
                        SkeletonBar(width = 160.dp, height = 12.dp)
                    }
                }
            }
            Spacer(Modifier.height(ZemoteSpacing.space2_5))
        }
    }
}

@Composable
private fun SkeletonBar(
    width: Dp,
    height: Dp,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(ZemoteRadius.sm),
) {
    val colors = LocalZemoteColors.current
    Box(
        Modifier
            .size(width = width, height = height)
            .clip(shape)
            .background(colors.surfaceHover),
    )
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
        modifier = Modifier.fillMaxSize().padding(ZemoteSpacing.space8),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when {
            loading -> CircularProgressIndicator(color = colors.foregroundSubtle)

            !paired -> {
                EmptyGlyph(icon = Icons.Default.QrCodeScanner, tint = colors.foregroundSubtle)
                EmptyTitle("还没有连接电脑")
                EmptySubtitle("在电脑端 ZCode 打开「手机远控」，扫码或粘贴链接即可连接。")
                Spacer(Modifier.height(ZemoteSpacing.space5))
                ButtonPrimary("扫码配对", onOpenPairing)
            }

            error != null -> {
                EmptyGlyph(icon = Icons.Default.ErrorOutline, tint = colors.destructive)
                EmptyTitle("加载失败")
                EmptySubtitle(error)
                Spacer(Modifier.height(ZemoteSpacing.space5))
                ButtonPrimary("重试", onRefresh)
                Spacer(Modifier.height(ZemoteSpacing.space1))
                TextButton(onClick = onOpenPairing) {
                    Text("重新扫码 / 覆盖链接", style = ZemoteType.uiSm)
                }
            }

            else -> {
                EmptyGlyph(icon = Icons.Outlined.Folder, tint = colors.foregroundSubtle)
                EmptyTitle("这台电脑还没有项目")
                EmptySubtitle("在电脑端打开一个项目后再刷新。")
                Spacer(Modifier.height(ZemoteSpacing.space5))
                ButtonPrimary("刷新", onRefresh)
            }
        }
    }
}

@Composable
private fun EmptyGlyph(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(ZemoteRadius.xl))
            .background(LocalZemoteColors.current.surface),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun EmptyTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(top = ZemoteSpacing.space3_5),
        style = ZemoteType.uiLg,
        fontWeight = FontWeight.Medium,
        color = LocalZemoteColors.current.foreground,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun EmptySubtitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(top = ZemoteSpacing.space1_5),
        style = ZemoteType.uiSm,
        color = LocalZemoteColors.current.foregroundSubtle,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ButtonPrimary(text: String, onClick: () -> Unit) {
    val colors = LocalZemoteColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(ZemoteRadius.lg))
            .background(colors.primary)
            .clickable(onClick = onClick)
            .padding(horizontal = ZemoteSpacing.space5, vertical = ZemoteSpacing.space2_5),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = ZemoteType.uiBase,
            color = colors.primaryForeground,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun Dot(
    color: Color,
    size: Dp = 6.dp,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
    )
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

/** 任务未读点：网页 `bg-sky-500 dark:bg-sky-400`（项目行未读点用 `bg-icon-blue`）。 */
private fun unreadDotColor(colors: app.zemote.ui.theme.ZemoteColors): Color =
    if (colors.background.luminance() > 0.5f) Color(0xFF0284C7) else Color(0xFF38BDF8)

/** 相对时间：刚刚 / 2m / 1h / 昨天 / 3d / 10-12。 */
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
