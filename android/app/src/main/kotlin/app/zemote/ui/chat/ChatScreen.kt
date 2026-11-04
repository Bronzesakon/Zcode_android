package app.zemote.ui.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.zemote.engine.RelayState
import app.zemote.ui.chat.media.ImageViewerDialog
import app.zemote.ui.chat.media.fetchImageBytes
import app.zemote.ui.chat.timeline.ConversationTimeline
import app.zemote.ui.chat.timeline.statusLabel
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteHeight
import app.zemote.ui.theme.ZemoteRadius
import app.zemote.ui.theme.ZemoteSpacing
import app.zemote.ui.theme.ZemoteType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Calendar

/**
 * 对话页（UI-SPEC §2 / UI-CONFIRM §1.1）。
 *
 * ```
 * 顶栏 44dp（返回 + 标题）
 * 页内头（工作区名 + 等宽路径 + ⋮）
 * 时间线（9 种行，见 timeline 包；空对话换成欢迎页）
 * 底部 dock（队列卡 / 待交互卡 / composer）
 * ```
 */
@Composable
fun ChatScreen(
    state: ChatUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    /** 错误条「重试」：优先重发失败的动作（如模式切换），没有时由调用方回退到刷新。 */
    onRetry: () -> Unit = onRefresh,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onLoadOlder: () -> Unit,
    onPin: (Boolean) -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
    onSwitchMode: (String) -> Unit,
    onSwitchThought: (String) -> Unit,
    onSwitchModel: (String, String) -> Unit = { _, _ -> },
    onLoadModelView: () -> Unit = {},
    onResolveInteraction: (String, String?, String?, String?, JsonObject?) -> Unit,
    onResumeQueue: () -> Unit = {},
    onDismissError: () -> Unit,
    onAttach: (String, String, ByteArray) -> Unit = { _, _, _ -> },
    onRemoveAttachment: (Int) -> Unit = {},
    onLoadAttachment: suspend (String) -> ByteArray? = { null },
    onDraftRestored: () -> Unit = {},
    /** 时间线回报「可见区已远离最老端」：窗口裁剪的安全信号。 */
    onWindowTrimSafe: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = LocalZemoteColors.current
    val context = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var subagentDetail by remember { mutableStateOf<JsonObject?>(null) }
    var previewKey by remember { mutableStateOf<String?>(null) }

    // 发送失败时把文本还给用户：ViewModel 只负责记录，输入框状态在这里。
    // 如果用户在发送在途时又输入了新内容，把失败文本放在前面而不是覆盖（两段都不能丢）。
    LaunchedEffect(state.draftRestore) {
        val restore = state.draftRestore ?: return@LaunchedEffect
        input = if (input.isBlank()) restore else restore + "\n" + input
        onDraftRestored()
    }

    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            readPickedFile(context, uri)?.let { (name, mime, bytes) ->
                onAttach(name, mime, bytes)
            }
        }
    }

    val imageLoader: suspend (String) -> ByteArray? = { key ->
        if (key.startsWith("http://") || key.startsWith("https://")) {
            fetchImageBytes(key)
        } else {
            onLoadAttachment(key)
        }
    }

    Column(modifier = modifier.fillMaxSize().background(colors.background)) {
        ChatTopBar(
            title = state.selectedTask?.title?.takeIf { it.isNotBlank() }
                ?: if (state.selectedTaskId == null) "新对话" else "未命名",
            // 只显示工作区文件夹名；磁盘路径不再占一行。
            subtitle = state.selectedWorkspace?.let { workspace ->
                workspace.label.takeIf { it.isNotBlank() }
                    ?: workspace.path.substringAfterLast('/').substringAfterLast('\\')
            },
            showActions = state.selectedTaskId != null,
            pinned = state.selectedTask?.pinned == true,
            menuOpen = menuOpen,
            onMenuOpenChange = { menuOpen = it },
            onBack = onBack,
            onPin = onPin,
            onRename = { menuOpen = false; renameOpen = true },
            onArchive = { menuOpen = false; onArchive() },
            onDelete = { menuOpen = false; deleteConfirm = true },
        )

        // 对话页也要能看见连接状态：否则桥降级/掉线时只能看到消息不再更新。
        val connectionText = when (state.relayState) {
            RelayState.Reconnecting -> "连接中断，正在重连…"
            RelayState.Connecting, RelayState.Authenticating -> "正在连接…"
            RelayState.Waiting -> "等待桌面端连接…"
            RelayState.Error, RelayState.Kicked, RelayState.Closed -> "连接已断开"
            else -> null
        }
        val statusText = state.error
            ?: state.bridgeDegraded?.let { "工作区连接已降级：$it" }
            ?: connectionText
            ?: if (state.relayFailureVisible) {
                "连接中断${state.relayFailure?.reason?.let { "：$it" }.orEmpty()}"
            } else {
                null
            }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            // 已选中任务但当前任务的首个对话快照还没到（本地缓存/快照都未应用）：
            // 给轻量骨架，别先闪一屏「没有可展示内容」再跳成时间线。
            val showSkeleton = state.selectedTaskId != null && !state.rowsReady
            val showWelcome = state.selectedTaskId == null && state.rows.isEmpty()
            when {
                showSkeleton -> ConversationSkeleton()

                showWelcome -> WelcomeEmpty(
                    workspace = state.selectedWorkspace,
                    onSuggestion = { prompt -> input = prompt },
                )

                else -> ConversationTimeline(
                    rows = state.rows,
                    isRunning = state.isRunning,
                    canLoadOlder = state.canLoadOlder,
                    loadingOlder = state.loadingOlder,
                    onLoadOlder = onLoadOlder,
                    onImageClick = { key -> previewKey = key },
                    imageLoader = imageLoader,
                    onSubagentClick = { subagentDetail = it },
                    onWindowTrimSafe = onWindowTrimSafe,
                )
            }

            // 骨架本身已经在表达「正在加载对话…」，不再叠全屏遮罩；
            // 初次进页（还没有选中任务）仍保留遮罩挡住空页操作。
            if (state.loading && !showSkeleton) {
                LoadingOverlay()
            }

            if (statusText != null) {
                StatusNotice(
                    text = statusText,
                    onRetry = onRetry,
                    onDismiss = onDismissError,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(
                            start = ZemoteSpacing.space3,
                            end = ZemoteSpacing.space3,
                            top = ZemoteSpacing.space3,
                        ),
                )
            }
        }

        // 底部 dock：队列卡、待交互卡固定在输入框上方（网页顺序：队列 → 待交互 → 输入）。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 键盘弹起时只让出一次空间：IME 与导航栏取并集（取较大者），
                // 不叠 imePadding + navigationBarsPadding，否则 adjustResize 下会多推一条键盘高度。
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(
                    start = ZemoteSpacing.space4,
                    end = ZemoteSpacing.space4,
                    bottom = ZemoteSpacing.space4,
                ),
            verticalArrangement = Arrangement.spacedBy(ZemoteSpacing.space2),
        ) {
            QueueCards(
                queue = state.queueItems,
                running = state.isRunning,
                autoDrain = state.queueAutoDrain,
                pauseReason = state.queuePauseReason,
                onResume = onResumeQueue,
            )
            InteractionCard(state.pendingInteractions, onResolveInteraction)
            ChatComposer(
                value = input,
                onValueChange = { input = it },
                sending = state.sending,
                uploading = state.uploading,
                uploadIndex = state.uploadIndex,
                uploadProgress = state.uploadProgress,
                running = state.isRunning,
                hasHistory = state.rows.isNotEmpty(),
                attachments = state.attachments,
                model = modelLabel(state.config),
                mode = state.config?.get("mode").asString() ?: state.draftMode,
                thought = state.config?.get("thought").asString(),
                levels = currentModelReasoningLevels(state.modelView, state.config)
                    .ifEmpty {
                        (state.config?.get("thoughtLevels") as? JsonArray)
                            ?.mapNotNull { it.asString() }
                            .orEmpty()
                    },
                onSend = {
                    val text = input.trim()
                    if (text.isNotEmpty() || state.attachments.isNotEmpty()) {
                        onSend(text)
                        input = ""
                    }
                },
                onStop = onStop,
                onOpenModel = {
                    showModelSheet = true
                    onLoadModelView()
                },
                onPickAttachment = { picker.launch(arrayOf("*/*")) },
                onRemoveAttachment = onRemoveAttachment,
                onMode = onSwitchMode,
                onThought = onSwitchThought,
            )
        }
    }

    if (showModelSheet) {
        ModelSheet(
            config = state.config,
            view = state.modelView,
            loading = state.modelViewLoading,
            error = state.modelViewError,
            onDismiss = { showModelSheet = false },
            onReload = onLoadModelView,
            onPick = { provider, model ->
                onSwitchModel(provider, model)
                showModelSheet = false
            },
        )
    }

    if (renameOpen) {
        var draft by remember { mutableStateOf(state.selectedTask?.title.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text("重命名任务") },
            text = {
                TokenTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = "任务标题",
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    renameOpen = false
                    if (draft.isNotBlank()) onRename(draft.trim())
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameOpen = false }) { Text("取消") } },
        )
    }

    if (deleteConfirm) {
        AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            title = { Text("删除这个任务？") },
            text = { Text("删除后无法从手机端恢复。") },
            confirmButton = {
                TextButton(onClick = { deleteConfirm = false; onDelete() }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirm = false }) { Text("取消") }
            },
        )
    }

    subagentDetail?.let { row ->
        SubagentSidePane(row = row, onDismiss = { subagentDetail = null })
    }

    previewKey?.let { key ->
        ImageViewerDialog(key = key, loader = imageLoader, onDismiss = { previewKey = null })
    }
}

// ---------------------------------------------------------------------------
// 顶栏 / 页内头
// ---------------------------------------------------------------------------

/**
 * 合并后的对话页顶栏：返回 + 任务标题/工作区文件夹名 + 任务操作。
 *
 * 原来的「44dp 标题栏 + 48dp 工作区行 + 分割线」合并成这一栏：磁盘路径不再显示，
 * 工作区文件夹名作为标题下的副标题，⋮ 移到标题行右侧；底部不再画分割线。
 */
@Composable
private fun ChatTopBar(
    title: String,
    subtitle: String?,
    showActions: Boolean,
    pinned: Boolean,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onPin: (Boolean) -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalZemoteColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.header)
            .statusBarsPadding()
            .heightIn(min = 56.dp)
            .padding(horizontal = ZemoteSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(ZemoteHeight.Control)
                .clip(RoundedCornerShape(ZemoteRadius.lg))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.ArrowBack,
                contentDescription = "返回",
                tint = colors.foreground,
                modifier = Modifier.size(16.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = ZemoteSpacing.space1),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                style = ZemoteType.uiBase,
                fontWeight = FontWeight.Medium,
                color = colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = ZemoteType.uiSm,
                    color = colors.foregroundSubtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (showActions) {
            Box {
                Box(
                    modifier = Modifier
                        .size(ZemoteHeight.Control)
                        .clip(RoundedCornerShape(ZemoteRadius.lg))
                        .clickable { onMenuOpenChange(true) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "更多",
                        tint = colors.foregroundSubtle,
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { onMenuOpenChange(false) },
                    modifier = Modifier.background(colors.popover),
                ) {
                    DropdownMenuItem(
                        text = { Text(if (pinned) "取消置顶" else "置顶") },
                        leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null) },
                        onClick = { onMenuOpenChange(false); onPin(!pinned) },
                    )
                    DropdownMenuItem(text = { Text("重命名") }, onClick = onRename)
                    DropdownMenuItem(
                        text = { Text("归档") },
                        leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) },
                        onClick = onArchive,
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = onDelete,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 覆盖层：重连提示 / 加载遮罩
// ---------------------------------------------------------------------------

@Composable
private fun StatusNotice(
    text: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalZemoteColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(ZemoteRadius.lg))
            .clip(RoundedCornerShape(ZemoteRadius.lg))
            .background(colors.toast)
            .border(1.dp, colors.border, RoundedCornerShape(ZemoteRadius.lg))
            .padding(start = ZemoteSpacing.space3, end = ZemoteSpacing.space1, top = ZemoteSpacing.space2, bottom = ZemoteSpacing.space2)
            .semantics { liveRegion = LiveRegionMode.Assertive },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = colors.destructive,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            modifier = Modifier.weight(1f).padding(horizontal = ZemoteSpacing.space2),
            style = ZemoteType.uiBase,
            color = colors.foreground,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "重试",
            style = ZemoteType.uiBase,
            color = colors.foregroundSubtle,
            modifier = Modifier
                .clip(RoundedCornerShape(ZemoteRadius.md))
                .clickable(onClick = onRetry)
                .padding(horizontal = ZemoteSpacing.space2, vertical = ZemoteSpacing.space1),
        )
        Box(
            modifier = Modifier
                .size(ZemoteSpacing.space8)
                .clip(RoundedCornerShape(ZemoteRadius.md))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "关闭",
                tint = colors.foregroundSubtle,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 网页覆盖层 C：`bg-background/72` + 居中卡（`common.loading`）。 */
@Composable
private fun LoadingOverlay() {
    val colors = LocalZemoteColors.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background.copy(alpha = 0.72f))
            // 遮罩吞掉点击/滚动，避免加载中操作到底层时间线。
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .shadow(4.dp, RoundedCornerShape(ZemoteRadius.lg))
                .clip(RoundedCornerShape(ZemoteRadius.lg))
                .background(colors.card)
                .border(1.dp, colors.border, RoundedCornerShape(ZemoteRadius.lg))
                .padding(horizontal = ZemoteSpacing.space3, vertical = ZemoteSpacing.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 1.5.dp,
                color = colors.foregroundSubtle,
            )
            Spacer(Modifier.width(ZemoteSpacing.space2))
            Text(
                text = "加载中...",
                style = ZemoteType.uiBase,
                color = colors.foregroundSubtle,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 空对话：欢迎页 / 加载骨架
// ---------------------------------------------------------------------------

private data class DraftSuggestion(val label: String, val prompt: String)

/** 文案取自 `Reference_web/3.17.1` 的 `chat.draft.suggestedPrompt.*`。 */
private val DraftSuggestions = listOf(
    DraftSuggestion(
        label = "检查近 7 天的 commit",
        prompt = "检查当前工作区近 7 天的 Git commit，概括主要改动并指出潜在风险。",
    ),
    DraftSuggestion(
        label = "制作一份 PDF",
        prompt = "根据当前工作区内容制作一份 PDF 文档。",
    ),
)

/**
 * 新对话空页（C1）：工作区名 + 路径 + 网页欢迎语 + 建议。
 *
 * 欢迎语来自 `chat.empty.greeting.*`，按小时切换（与网页 `a9t` 同口径）。
 */
@Composable
private fun WelcomeEmpty(workspace: WorkspaceItem?, onSuggestion: (String) -> Unit) {
    val colors = LocalZemoteColors.current
    val greeting = remember {
        greetingForHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 网页空态顶部留白：before:basis-[29dvh]。先取值，避免嵌套 DSL 作用域遮蔽。
        val topSpace = maxHeight * 0.20f
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = ZemoteSpacing.space4),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(topSpace))
            Text(
                text = greeting,
                style = ZemoteType.uiXl,
                fontWeight = FontWeight.Medium,
                color = colors.foreground,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = ZemoteSpacing.space2),
            )
            Spacer(Modifier.height(ZemoteSpacing.space3))
            Text(
                text = workspace?.label?.takeIf { it.isNotBlank() } ?: "未选择工作区",
                style = ZemoteType.uiBase,
                fontWeight = FontWeight.Medium,
                color = colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val path = workspace?.path.orEmpty()
            if (path.isNotBlank()) {
                Text(
                    text = path,
                    style = ZemoteType.uiSm.copy(fontFamily = FontFamily.Monospace),
                    color = colors.foregroundSubtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(
                        start = ZemoteSpacing.space4,
                        end = ZemoteSpacing.space4,
                        top = ZemoteSpacing.space0_5,
                    ),
                )
            }
            Spacer(Modifier.height(ZemoteSpacing.space6))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(ZemoteSpacing.space2),
                ) {
                    DraftSuggestions.forEach { suggestion ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(ZemoteRadius.lg))
                                .background(colors.surface)
                                .border(1.dp, colors.border, RoundedCornerShape(ZemoteRadius.lg))
                                .clickable { onSuggestion(suggestion.prompt) }
                                .padding(
                                    horizontal = ZemoteSpacing.space3,
                                    vertical = ZemoteSpacing.space2,
                                ),
                        ) {
                            Text(
                                text = suggestion.label,
                                style = ZemoteType.uiBase,
                                color = colors.foreground,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * 已选中任务、首个对话快照尚未应用时的占位骨架。
 *
 * 静态灰条 + 一行「正在加载对话…」：不转圈、不做无限动画，也不遮挡 composer/队列卡。
 */
@Composable
private fun ConversationSkeleton() {
    val colors = LocalZemoteColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = ZemoteSpacing.space4, vertical = ZemoteSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(ZemoteSpacing.space4),
    ) {
        Text(
            text = "正在加载对话…",
            style = ZemoteType.uiSm,
            color = colors.foregroundSubtle,
        )
        SkeletonBar(widthFraction = 0.42f, height = 14.dp, color = colors.foregroundSubtlest)
        SkeletonBar(widthFraction = 0.86f, height = 10.dp, color = colors.foregroundSubtlest)
        SkeletonBar(widthFraction = 0.72f, height = 10.dp, color = colors.foregroundSubtlest)
        Spacer(Modifier.height(ZemoteSpacing.space2))
        SkeletonBar(widthFraction = 0.55f, height = 14.dp, color = colors.foregroundSubtlest)
        SkeletonBar(widthFraction = 0.80f, height = 10.dp, color = colors.foregroundSubtlest)
        SkeletonBar(widthFraction = 0.64f, height = 10.dp, color = colors.foregroundSubtlest)
        SkeletonBar(widthFraction = 0.30f, height = 10.dp, color = colors.foregroundSubtlest)
    }
}

@Composable
private fun SkeletonBar(widthFraction: Float, height: Dp, color: Color) {
    Box(
        Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(RoundedCornerShape(ZemoteRadius.sm))
            .background(color),
    )
}

/** 网页 `a9t`：按小时返回欢迎语键。 */
private fun greetingForHour(hour: Int): String = when {
    hour in 5..8 -> "早上好呀，新的一天开始啦"
    hour in 9..11 -> "上午好呀，有什么想让我帮忙的吗"
    hour in 12..13 -> "中午好呀，要不要先休息一下"
    hour in 14..17 -> "下午好呀，接下来交给我吧"
    hour in 18..22 -> "晚上好呀，今天辛苦啦"
    else -> "夜深啦，别忘了照顾好自己哦"
}

// ---------------------------------------------------------------------------
// 待交互 / 队列
// ---------------------------------------------------------------------------

@Composable
private fun InteractionCard(
    interactions: JsonArray?,
    onResolve: (String, String?, String?, String?, JsonObject?) -> Unit,
) {
    val colors = LocalZemoteColors.current
    val first = (interactions?.firstOrNull() as? JsonObject) ?: return
    val payload = first["payload"].asObj() ?: return
    val interactionId = first["interactionId"].asString().orEmpty()
    val kind = payload["kind"].asString()
    val options = payload["options"] as? JsonArray
    val questions = payload["questions"] as? JsonArray
    val freeText = payload["freeText"].asBool() == true
    val title = if (kind == "permission") {
        "需要权限 · ${payload["toolName"].asString().orEmpty()}"
    } else {
        "等待你的输入"
    }
    val detail = payload["summary"].asString()
        ?: payload["prompt"].asString()
        ?: ""

    // 表单答案：question 文本 → 选中的 value 列表（与网页 `content.answers` 同构）。
    val answers = remember(interactionId) { mutableStateMapOf<String, List<String>>() }
    var freeInput by remember(interactionId) { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ZemoteRadius.xxl))
            .background(colors.popover)
            .border(1.dp, colors.border, RoundedCornerShape(ZemoteRadius.xxl))
            .padding(ZemoteSpacing.space3),
    ) {
        Text(
            text = title,
            style = ZemoteType.uiBase,
            fontWeight = FontWeight.Medium,
            color = colors.foreground,
        )
        if (detail.isNotBlank()) {
            Text(
                text = detail,
                style = ZemoteType.uiSm,
                color = colors.foregroundSubtle,
                modifier = Modifier.padding(top = ZemoteSpacing.space1),
            )
        }

        // 表单式 userInput：逐题渲染选项，提交时带 content.answers。
        if (questions != null && questions.isNotEmpty()) {
            questions.forEachIndexed { index, element ->
                val question = element as? JsonObject ?: return@forEachIndexed
                val text = question["question"].asString().orEmpty()
                val label = question["header"].asString()?.takeIf { it.isNotBlank() } ?: text
                val multi = question["multiSelect"].asBool() == true
                val selected = answers[text].orEmpty()
                Text(
                    text = "${index + 1}. $label",
                    style = ZemoteType.uiBase,
                    color = colors.foreground,
                    modifier = Modifier.padding(top = ZemoteSpacing.space2),
                )
                val qOptions = question["options"] as? JsonArray
                if (qOptions != null && qOptions.isNotEmpty()) {
                    Column(
                        modifier = Modifier.padding(top = ZemoteSpacing.space1),
                        verticalArrangement = Arrangement.spacedBy(ZemoteSpacing.space0_5),
                    ) {
                        qOptions.forEach { opt ->
                            val obj = opt as? JsonObject ?: return@forEach
                            val value = obj["value"].asString() ?: return@forEach
                            val optionLabel = obj["label"].asString() ?: value
                            val isSelected = value in selected
                            OptionRow(
                                label = optionLabel,
                                selected = isSelected,
                                onClick = {
                                    val next = if (multi) {
                                        if (isSelected) selected - value else selected + value
                                    } else {
                                        listOf(value)
                                    }
                                    if (next.isEmpty()) {
                                        answers.remove(text)
                                    } else {
                                        answers[text] = next
                                    }
                                },
                            )
                        }
                    }
                }
            }
            ActionButton(
                text = "提交答案",
                enabled = answers.isNotEmpty(),
                modifier = Modifier.align(Alignment.End).padding(top = ZemoteSpacing.space2),
                onClick = {
                    val content = buildJsonObject {
                        put(
                            "answers",
                            buildJsonObject {
                                answers.forEach { (question, values) ->
                                    put(
                                        question,
                                        JsonArray(values.map { JsonPrimitive(it) }),
                                    )
                                }
                            },
                        )
                    }
                    onResolve(interactionId, null, null, "accept", content)
                },
            )
        } else if (options != null && options.isNotEmpty()) {
            // 权限卡片：optionId + action=accept + content{value,label,kind}。
            Column(
                modifier = Modifier.padding(top = ZemoteSpacing.space2),
                verticalArrangement = Arrangement.spacedBy(ZemoteSpacing.space0_5),
            ) {
                options.forEachIndexed { index, option ->
                    val obj = option as? JsonObject ?: return@forEachIndexed
                    val optionId = obj["optionId"].asString()
                    val label = obj["label"].asString()?.takeIf { it.isNotBlank() }
                        ?: permissionOptionLabel(obj["kind"].asString())
                        ?: optionId
                        ?: "确定"
                    OptionRow(
                        index = index + 1,
                        label = label,
                        selected = false,
                        onClick = {
                            val content = buildJsonObject {
                                obj["value"]?.let { put("value", it) }
                                obj["label"]?.let { put("label", it) }
                                obj["kind"]?.let { put("kind", it) }
                            }
                            onResolve(
                                interactionId,
                                optionId,
                                null,
                                "accept",
                                content.takeIf { it.isNotEmpty() },
                            )
                        },
                    )
                }
            }
        }

        if (freeText) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = ZemoteSpacing.space2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TokenTextField(
                    value = freeInput,
                    onValueChange = { freeInput = it },
                    placeholder = "输入回复…",
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(ZemoteSpacing.space2))
                Box(
                    modifier = Modifier
                        .size(ZemoteSpacing.space9)
                        .clip(RoundedCornerShape(ZemoteRadius.lg))
                        .background(
                            if (freeInput.isNotBlank()) {
                                colors.brand
                            } else {
                                colors.brand.copy(alpha = 0.4f)
                            },
                        )
                        .clickable(enabled = freeInput.isNotBlank()) {
                            onResolve(interactionId, null, freeInput.trim(), null, null)
                            freeInput = ""
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = "发送回复",
                        tint = colors.foregroundInverse,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    index: Int? = null,
) {
    val colors = LocalZemoteColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ZemoteRadius.xl))
            .background(if (selected) colors.selected else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = ZemoteSpacing.space3, vertical = ZemoteSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Text(
                text = "$index.",
                style = ZemoteType.uiBase,
                color = colors.foregroundSubtle,
                modifier = Modifier.width(ZemoteSpacing.space5),
            )
        }
        Text(
            text = label,
            style = ZemoteType.uiBase,
            color = colors.foreground,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = colors.foreground,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalZemoteColors.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(ZemoteRadius.lg))
            .background(if (enabled) colors.brand else colors.brand.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = ZemoteSpacing.space3, vertical = ZemoteSpacing.space2),
    ) {
        Text(
            text = text,
            style = ZemoteType.uiBase,
            color = colors.foregroundInverse,
        )
    }
}

/** 权限选项的兜底文案（网页 `_optionLabel` 同款）。 */
private fun permissionOptionLabel(kind: String?): String? = when (kind) {
    "allowOnce" -> "允许一次"
    "allowAlways" -> "总是允许"
    "deny" -> "拒绝"
    "custom" -> "自定义"
    else -> null
}

@Composable
private fun QueueCards(
    queue: JsonArray?,
    running: Boolean,
    autoDrain: Boolean,
    pauseReason: String?,
    onResume: () -> Unit,
) {
    val colors = LocalZemoteColors.current
    if (queue.isNullOrEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ZemoteRadius.xxl))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(ZemoteRadius.xxl))
            .padding(ZemoteSpacing.space1),
    ) {
        Text(
            text = "待发送消息（${queue.size}）",
            style = ZemoteType.uiSm,
            color = colors.foregroundSubtle,
            modifier = Modifier.padding(
                start = ZemoteSpacing.space2,
                top = ZemoteSpacing.space1,
                bottom = ZemoteSpacing.space1,
            ),
        )
        if (!autoDrain) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = ZemoteSpacing.space1)
                    .clip(RoundedCornerShape(ZemoteRadius.xl))
                    .background(colors.card)
                    .border(1.dp, colors.border.copy(alpha = 0.7f), RoundedCornerShape(ZemoteRadius.xl))
                    .padding(
                        start = ZemoteSpacing.space3,
                        end = ZemoteSpacing.space1,
                        top = ZemoteSpacing.space2,
                        bottom = ZemoteSpacing.space2,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when (pauseReason) {
                        "stopped" -> "由于你中断了当前响应，队列已暂停"
                        "error" -> "由于当前响应出错，队列已暂停（内容未丢失）"
                        else -> "队列已暂停"
                    },
                    modifier = Modifier.weight(1f),
                    style = ZemoteType.uiBase,
                    color = colors.foreground,
                )
                Text(
                    text = "继续",
                    style = ZemoteType.uiBase,
                    color = colors.foregroundSubtle,
                    modifier = Modifier
                        .clip(RoundedCornerShape(ZemoteRadius.md))
                        .clickable(onClick = onResume)
                        .padding(horizontal = ZemoteSpacing.space2, vertical = ZemoteSpacing.space1),
                )
            }
        }
        queue.forEachIndexed { index, item ->
            val obj = item as? JsonObject ?: return@forEachIndexed
            val text = obj["text"].asString().orEmpty()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(ZemoteRadius.xl))
                    .padding(horizontal = ZemoteSpacing.space2, vertical = ZemoteSpacing.space1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${index + 1}.",
                    style = ZemoteType.uiSm,
                    color = colors.foregroundSubtle,
                    modifier = Modifier.width(ZemoteSpacing.space5),
                )
                Text(
                    text = text,
                    modifier = Modifier.weight(1f),
                    style = ZemoteType.uiBase,
                    color = colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (running) "排队中" else "待发送",
                    style = ZemoteType.uiXs,
                    color = colors.foregroundSubtlest,
                    modifier = Modifier.padding(start = ZemoteSpacing.space2),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 小输入框（替掉 M3 OutlinedTextField 的默认描边）
// ---------------------------------------------------------------------------

@Composable
private fun TokenTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
) {
    val colors = LocalZemoteColors.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(ZemoteRadius.lg))
            .background(colors.input)
            .border(1.dp, colors.inputBorder, RoundedCornerShape(ZemoteRadius.lg))
            .padding(horizontal = ZemoteSpacing.space3, vertical = ZemoteSpacing.space2_5),
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = ZemoteType.uiBase,
                color = colors.foregroundSubtlest,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = ZemoteType.uiBase.copy(color = colors.foreground),
            singleLine = singleLine,
            cursorBrush = SolidColor(colors.brand),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---------------------------------------------------------------------------
// 子代理详情
// ---------------------------------------------------------------------------

@Composable
private fun SubagentSidePane(row: JsonObject, onDismiss: () -> Unit) {
    val colors = LocalZemoteColors.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // 网页端是右侧 side-pane；手机上按屏宽 90%、上限 420dp。
            val panelWidth = minOf(maxWidth * 0.9f, 420.dp)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(onClick = onDismiss),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(panelWidth)
                    .background(colors.background)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row["subagentType"].asString() ?: "子代理",
                        style = ZemoteType.uiLg,
                        color = colors.foreground,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        modifier = Modifier
                            .size(ZemoteSpacing.space9)
                            .clip(RoundedCornerShape(ZemoteRadius.lg))
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = colors.foregroundSubtle,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Spacer(Modifier.height(ZemoteSpacing.space2))
                Text(
                    text = "状态：${statusLabel(row["status"].asString().orEmpty())}",
                    style = ZemoteType.uiSm,
                    color = colors.foregroundSubtle,
                )
                Spacer(Modifier.height(ZemoteSpacing.space3))
                Text(
                    text = row["summaryText"].asString().orEmpty().ifBlank { "（无摘要）" },
                    style = ZemoteType.uiSm,
                    color = colors.foreground,
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 文件选择
// ---------------------------------------------------------------------------

/** 读取系统文件选择器的结果（名称 / MIME / 字节）；失败返回 `null`。 */
private suspend fun readPickedFile(
    context: Context,
    uri: Uri,
): Triple<String, String, ByteArray>? = withContext(Dispatchers.IO) {
    try {
        val resolver = context.contentResolver
        var name = "attachment"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                name = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: name
            }
        }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        // 有界读取：先按 30MiB 上限截断，避免超大文件整读进内存导致 OOM。
        val out = java.io.ByteArrayOutputStream()
        var total = 0
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > ChatViewModel.MAX_ATTACHMENT_BYTES) return@withContext null
                out.write(buffer, 0, read)
            }
        } ?: return@withContext null
        Triple(name, mime, out.toByteArray())
    } catch (e: Exception) {
        null
    }
}
