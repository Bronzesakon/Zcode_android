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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.zemote.ui.chat.markdown.MarkdownText
import app.zemote.ui.chat.media.ImageViewerDialog
import app.zemote.ui.chat.media.RemoteImage
import app.zemote.ui.chat.media.fetchImageBytes
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/**
 * 对话页（UI-SPEC §2）。
 *
 * ```
 * 顶栏（返回 + 标题）
 * 头部（工作区名 + 路径 + ⋮ 菜单）
 * 时间线（9 种行类型）
 * 待交互卡片 / 队列卡片
 * 输入区（文本 + 附件 + 模型 + 发送/停止）
 * ```
 *
 * 无状态：状态来自 [ChatUiState]，动作走回调，接线在 `AppNavHost`。
 *
 * 折叠规则（与用户确认过的行为）：
 * - `turnHeader` 是段标题（工作中/已工作 + 时长），点击折叠整轮的推理/工具行；
 * - `reasoning` / `toolCall` 默认只留一行，所在轮次有流式行时自动展开；
 *   该轮流式结束后自动折回（除非用户手动展开过）；
 * - `subagent` 点击进下一屏（这里是全屏对话框，行本身只留一行摘要）。
 */
@Composable
fun ChatScreen(
    state: ChatUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onLoadOlder: () -> Unit,
    onPin: (Boolean) -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
    onSwitchMode: (String) -> Unit,
    onSwitchThought: (String) -> Unit,
    onResolveInteraction: (String, String?) -> Unit,
    onDismissError: () -> Unit,
    onAttach: (String, String, ByteArray) -> Unit = { _, _, _ -> },
    onRemoveAttachment: (Int) -> Unit = {},
    onLoadAttachment: suspend (String) -> ByteArray? = { null },
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

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val expandedTurns = remember { mutableStateOf(setOf<String>()) }
    val collapsedTurns = remember { mutableStateOf(setOf<String>()) }
    val openTools = remember { mutableStateOf(setOf<Long>()) }
    val openReasoning = remember { mutableStateOf(setOf<Long>()) }

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

    val runningTurns = remember(state.rows) {
        state.rows.filter { it.isStreaming() }
            .mapNotNull { it["turnId"].asString() }
            .toSet()
    }

    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= info.totalItemsCount - 2
        }
    }

    LaunchedEffect(state.rows.size, state.isRunning) {
        if (state.rows.isNotEmpty() && atBottom) {
            listState.animateScrollToItem(state.rows.lastIndex)
        }
    }

    Column(modifier = modifier.fillMaxSize().background(colors.background)) {
        ChatTopBar(title = state.selectedTask?.title ?: "新对话", onBack = onBack)

        ChatHeader(
            workspace = state.selectedWorkspace,
            pinned = state.selectedTask?.pinned == true,
            menuOpen = menuOpen,
            onMenuOpenChange = { menuOpen = it },
            onPin = onPin,
            onRename = { menuOpen = false; renameOpen = true },
            onArchive = { menuOpen = false; onArchive() },
            onDelete = { menuOpen = false; deleteConfirm = true },
        )

        if (state.loading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(1.dp),
                color = colors.foregroundSubtle,
                trackColor = colors.border,
            )
        }

        if (state.error != null) {
            ChatErrorBanner(state.error, onRefresh, onDismissError)
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.rows.isEmpty()) {
                EmptyChat(running = state.isRunning, isNew = state.selectedTaskId == null)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp,
                    ),
                ) {
                    items(state.rows, key = { it.rowId() }) { row ->
                        val turnId = row["turnId"].asString()
                        val kind = row.kind()
                        val isWorkRow = kind in WORK_ROW_KINDS
                        val expanded = when {
                            turnId == null -> true
                            turnId in expandedTurns.value -> true
                            turnId in collapsedTurns.value -> false
                            else -> turnId in runningTurns
                        }
                        if (isWorkRow && !expanded) return@items

                        when (kind) {
                            "turnHeader" -> TurnHeaderRow(
                                row = row,
                                expanded = expanded,
                                onToggle = {
                                    val id = turnId.orEmpty()
                                    if (expanded) {
                                        expandedTurns.value -= id
                                        collapsedTurns.value += id
                                    } else {
                                        expandedTurns.value += id
                                        collapsedTurns.value -= id
                                    }
                                },
                            )

                            "userInput" -> UserInputRow(
                                row = row,
                                onImageClick = { key -> previewKey = key },
                                imageLoader = imageLoader,
                            )

                            "assistantText" -> AssistantTextRow(
                                row = row,
                                onImageClick = { key -> previewKey = key },
                            )

                            "reasoning" -> ReasoningRow(
                                row = row,
                                open = row.rowId() in openReasoning.value || row.isStreaming(),
                                onToggle = {
                                    if (row.rowId() in openReasoning.value) {
                                        openReasoning.value -= row.rowId()
                                    } else {
                                        openReasoning.value += row.rowId()
                                    }
                                },
                            )

                            "toolCall" -> ToolCallRow(
                                row = row,
                                open = row.rowId() in openTools.value || row.isStreaming(),
                                onToggle = {
                                    if (row.rowId() in openTools.value) {
                                        openTools.value -= row.rowId()
                                    } else {
                                        openTools.value += row.rowId()
                                    }
                                },
                            )

                            "artifact" -> ArtifactRow(row)

                            "subagent" -> SubagentRow(row) { subagentDetail = row }

                            "hookInvocation" -> HookRow(row)

                            "timelineMarker" -> MarkerRow(row)

                            else -> UnknownRow(row)
                        }
                    }

                    if (state.totalCount > state.rows.size) {
                        item(key = "load-older") {
                            TextButton(
                                onClick = onLoadOlder,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("加载更早的消息", style = ZemoteType.uiSm) }
                        }
                    }
                }
            }

            if (!atBottom && state.rows.isNotEmpty()) {
                Surface(
                    onClick = {
                        scope.launch {
                            if (state.rows.isNotEmpty()) {
                                listState.animateScrollToItem(state.rows.lastIndex)
                            }
                        }
                    },
                    shape = CircleShape,
                    color = colors.card,
                    shadowElevation = 2.dp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                ) {
                    Icon(
                        Icons.Default.ArrowUpward,
                        contentDescription = "回到底部",
                        tint = colors.foregroundSubtle,
                        modifier = Modifier.padding(8.dp).size(18.dp),
                    )
                }
            }
        }

        InteractionCard(state.pendingInteractions, onResolveInteraction)
        QueueCards(state.queueItems, state.isRunning)

        Composer(
            value = input,
            onValueChange = { input = it },
            sending = state.sending,
            uploading = state.uploading,
            running = state.isRunning,
            attachments = state.attachments,
            model = state.config?.get("model").asString(),
            mode = state.config?.get("mode").asString(),
            onSend = {
                val text = input.trim()
                if (text.isNotEmpty() || state.attachments.isNotEmpty()) {
                    onSend(text)
                    input = ""
                }
            },
            onStop = onStop,
            onOpenModel = { showModelSheet = true },
            onPickAttachment = { picker.launch(arrayOf("*/*")) },
            onRemoveAttachment = onRemoveAttachment,
        )
    }

    if (showModelSheet) {
        ModelSheet(
            config = state.config,
            onDismiss = { showModelSheet = false },
            onMode = { mode -> onSwitchMode(mode); showModelSheet = false },
            onThought = { thought -> onSwitchThought(thought); showModelSheet = false },
        )
    }

    if (renameOpen) {
        var draft by remember { mutableStateOf(state.selectedTask?.title.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text("重命名任务") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
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
        SubagentDetailDialog(row = row, onDismiss = { subagentDetail = null })
    }

    previewKey?.let { key ->
        ImageViewerDialog(key = key, loader = imageLoader, onDismiss = { previewKey = null })
    }
}

private val WORK_ROW_KINDS = setOf("reasoning", "toolCall", "hookInvocation", "subagent")

// ---------------------------------------------------------------------------
// 顶栏 / 头部
// ---------------------------------------------------------------------------

@Composable
private fun ChatTopBar(title: String, onBack: () -> Unit) {
    val colors = LocalZemoteColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.header)
            .statusBarsPadding()
            .height(44.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.ArrowBack, contentDescription = "返回", modifier = Modifier.size(18.dp))
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = ZemoteType.uiBase,
            fontWeight = FontWeight.Medium,
            color = colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    HorizontalDivider(color = colors.border)
}

@Composable
private fun ChatHeader(
    workspace: WorkspaceItem?,
    pinned: Boolean,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
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
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = workspace?.label ?: "工作区",
                style = ZemoteType.uiSm,
                color = colors.foreground,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = workspace?.path.orEmpty(),
                style = ZemoteType.uiXs.copy(fontFamily = FontFamily.Monospace),
                color = colors.foregroundSubtle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Box {
            IconButton(onClick = { onMenuOpenChange(true) }) {
                Icon(Icons.Default.MoreVert, contentDescription = "更多", tint = colors.foregroundSubtle)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
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
    HorizontalDivider(color = colors.border)
}

@Composable
private fun ChatErrorBanner(text: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val colors = LocalZemoteColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.destructive.copy(alpha = 0.10f))
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.ErrorOutline, null, tint = colors.destructive, modifier = Modifier.size(16.dp))
        Text(
            text = text,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            style = ZemoteType.uiSm,
            color = colors.destructive,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onRetry) { Text("重试", style = ZemoteType.uiSm) }
        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Close, "关闭", modifier = Modifier.size(16.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// 行渲染
// ---------------------------------------------------------------------------

@Composable
private fun TurnHeaderRow(row: JsonObject, expanded: Boolean, onToggle: () -> Unit) {
    val colors = LocalZemoteColors.current
    val state = row["state"].asString()
    val running = state == "running"
    val duration = durationMs(row)
    val label = when (state) {
        "running" -> "工作中 ${formatDuration(duration)}"
        "completedInterrupted" -> "已停止 · ${formatDuration(duration)}"
        "failed" -> "已失败 · ${formatDuration(duration)}"
        else -> "已工作 ${formatDuration(duration)}"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (running) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = colors.foregroundSubtle,
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = ZemoteType.uiSm, color = colors.foregroundSubtle)
        Spacer(Modifier.width(4.dp))
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            tint = colors.foregroundSubtle,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.weight(1f))
        HorizontalDivider(color = colors.border.copy(alpha = 0.5f), modifier = Modifier.width(24.dp))
    }
}

@Composable
private fun UserInputRow(
    row: JsonObject,
    onImageClick: (String) -> Unit,
    imageLoader: suspend (String) -> ByteArray?,
) {
    val colors = LocalZemoteColors.current
    val text = row["text"].asString().orEmpty()
    val attachments = row["attachments"] as? JsonArray
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .clip(RoundedCornerShape(12.dp, 2.dp, 12.dp, 12.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(12.dp, 2.dp, 12.dp, 12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (text.isNotBlank()) {
                MarkdownText(text = text, onImageClick = onImageClick)
            }
            if (!attachments.isNullOrEmpty()) {
                Spacer(Modifier.height(6.dp))
                attachments.forEach { item ->
                    val obj = item as? JsonObject ?: return@forEach
                    val mime = obj["mime"].asString().orEmpty()
                    val ref = obj["ref"].asString()
                    if (mime.startsWith("image/") && ref != null) {
                        RemoteImage(
                            key = ref,
                            loader = imageLoader,
                            modifier = Modifier
                                .width(160.dp)
                                .height(120.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.background),
                            onClick = { onImageClick(ref) },
                        )
                        Spacer(Modifier.height(4.dp))
                    } else {
                        AttachmentPill(obj)
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentPill(obj: JsonObject) {
    val colors = LocalZemoteColors.current
    val name = obj["fileName"].asString() ?: obj["name"].asString() ?: "附件"
    val bytes = obj["bytes"].asLong() ?: obj["sizeBytes"].asLong()
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.background)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.InsertDriveFile, null, tint = colors.foregroundSubtle, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (bytes != null) "$name · ${formatBytes(bytes)}" else name,
            style = ZemoteType.uiSm,
            color = colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AssistantTextRow(row: JsonObject, onImageClick: (String) -> Unit) {
    val colors = LocalZemoteColors.current
    val text = row["text"].asString().orEmpty()
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        MarkdownText(text = text, onImageClick = onImageClick)
        if (row.isStreaming()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.5.dp,
                    color = colors.foregroundSubtle,
                )
                Spacer(Modifier.width(6.dp))
                Text("正在生成…", style = ZemoteType.uiXs, color = colors.foregroundSubtle)
            }
        }
    }
}

@Composable
private fun ReasoningRow(row: JsonObject, open: Boolean, onToggle: () -> Unit) {
    val colors = LocalZemoteColors.current
    val duration = row["durationMs"].asLong() ?: 0L
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 4.dp, bottom = 4.dp)
            .border(
                width = 1.dp,
                color = colors.border.copy(alpha = 0.6f),
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (duration > 0) "思考 · ${formatDuration(duration)}" else "思考",
                style = ZemoteType.uiXs,
                color = colors.foregroundSubtle,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = colors.foregroundSubtle,
                modifier = Modifier.size(14.dp),
            )
        }
        if (open) {
            Spacer(Modifier.height(6.dp))
            MarkdownText(
                text = row["text"].asString().orEmpty(),
                modifier = Modifier.heightIn(max = 320.dp),
            )
        }
    }
}

@Composable
private fun ToolCallRow(row: JsonObject, open: Boolean, onToggle: () -> Unit) {
    val colors = LocalZemoteColors.current
    val status = row["status"].asString().orEmpty()
    val name = row["toolName"].asString().orEmpty().ifBlank { "tool" }
    val summary = toolSummary(row)
    val duration = durationMs(row)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface.copy(alpha = 0.55f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolStatusIcon(status)
            Spacer(Modifier.width(6.dp))
            Text(
                text = name,
                style = ZemoteType.uiSm.copy(fontFamily = FontFamily.Monospace),
                color = colors.foreground,
                fontWeight = FontWeight.Medium,
            )
            if (summary.isNotBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = summary,
                    style = ZemoteType.uiXs.copy(fontFamily = FontFamily.Monospace),
                    color = colors.foregroundSubtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (duration > 0) {
                Text(
                    text = formatDuration(duration),
                    style = ZemoteType.ui2xs,
                    color = colors.foregroundSubtle,
                )
                Spacer(Modifier.width(4.dp))
            }
            Icon(
                if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = colors.foregroundSubtle,
                modifier = Modifier.size(14.dp),
            )
        }
        if (open) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
            ) {
                val inputText = row["inputText"].asString().orEmpty()
                if (inputText.isNotBlank()) {
                    MonoBlock("输入", inputText)
                }
                val outputText = row["output"].asObj()?.get("text").asString()
                    ?: row["outputPreview"].asString()
                    ?: ""
                if (outputText.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    MonoBlock("输出", outputText)
                }
                val error = row["error"].asObj()?.get("message").asString()
                if (!error.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(error, style = ZemoteType.uiXs, color = colors.destructive)
                }
            }
        }
    }
}

@Composable
private fun MonoBlock(label: String, text: String) {
    val colors = LocalZemoteColors.current
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = ZemoteType.ui2xs, color = colors.foregroundSubtlest)
        Spacer(Modifier.height(2.dp))
        Text(
            text = text,
            style = ZemoteType.uiXs.copy(fontFamily = FontFamily.Monospace),
            color = colors.foregroundSubtle,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun ToolStatusIcon(status: String) {
    val colors = LocalZemoteColors.current
    when (status) {
        "running", "inputStreaming", "pendingApproval" -> CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            strokeWidth = 1.5.dp,
            color = colors.foregroundSubtle,
        )

        "success" -> Icon(Icons.Default.Check, null, tint = colors.success, modifier = Modifier.size(14.dp))
        "error" -> Icon(Icons.Default.Close, null, tint = colors.destructive, modifier = Modifier.size(14.dp))
        else -> Box(Modifier.size(12.dp))
    }
}

@Composable
private fun ArtifactRow(row: JsonObject) {
    val colors = LocalZemoteColors.current
    val name = row["displayName"].asString() ?: "产物"
    val size = row["sizeBytes"].asLong()
    Row(
        modifier = Modifier
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(colors.surface)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.InsertDriveFile, null, tint = colors.foregroundSubtle, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(name, style = ZemoteType.uiXs, color = colors.foreground)
        if (size != null) {
            Text(" · ${formatBytes(size)}", style = ZemoteType.ui2xs, color = colors.foregroundSubtle)
        }
    }
}

@Composable
private fun SubagentRow(row: JsonObject, onClick: () -> Unit) {
    val colors = LocalZemoteColors.current
    val type = row["subagentType"].asString() ?: "子代理"
    val status = row["status"].asString().orEmpty()
    val summary = row["summaryText"].asString().orEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surface)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(26.dp).clip(CircleShape).background(colors.card),
            contentAlignment = Alignment.Center,
        ) {
            Text(type.take(1).uppercase(), style = ZemoteType.uiXs, color = colors.foregroundSubtle)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(type, style = ZemoteType.uiSm, color = colors.foreground, fontWeight = FontWeight.Medium)
            if (summary.isNotBlank()) {
                Text(
                    summary,
                    style = ZemoteType.uiXs,
                    color = colors.foregroundSubtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(statusLabel(status), style = ZemoteType.ui2xs, color = colors.foregroundSubtle)
    }
}

@Composable
private fun HookRow(row: JsonObject) {
    val colors = LocalZemoteColors.current
    val event = row["hookEventName"].asString() ?: "hook"
    val count = row["hookCount"].asLong() ?: 1L
    val state = row["state"].asString().orEmpty()
    Text(
        text = "$event · $count 个钩子 · ${statusLabel(state)}",
        style = ZemoteType.uiXs,
        color = colors.foregroundSubtle,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun MarkerRow(row: JsonObject) {
    val colors = LocalZemoteColors.current
    val type = row["marker"].asObj()?.get("type").asString().orEmpty()
    val label = when (type) {
        "compact" -> "上下文已压缩"
        "forkNotice" -> "已分叉"
        "forkCreated" -> "新分叉已创建"
        "modelChange" -> "模型切换"
        "goalSet" -> "目标已设置"
        "goalVerify" -> "目标已校验"
        "retryNotice" -> "已重试"
        "checkpointRestored" -> "检查点已恢复"
        else -> type.ifBlank { "标记" }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        HorizontalDivider(Modifier.width(24.dp), color = colors.border)
        Text(
            text = "  $label  ",
            style = ZemoteType.ui2xs,
            color = colors.foregroundSubtlest,
            textAlign = TextAlign.Center,
        )
        HorizontalDivider(Modifier.width(24.dp), color = colors.border)
    }
}

@Composable
private fun UnknownRow(row: JsonObject) {
    val colors = LocalZemoteColors.current
    Text(
        text = "[${row.kind()}]",
        style = ZemoteType.ui2xs,
        color = colors.foregroundSubtlest,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

// ---------------------------------------------------------------------------
// 待交互 / 队列
// ---------------------------------------------------------------------------

@Composable
private fun InteractionCard(interactions: JsonArray?, onResolve: (String, String?) -> Unit) {
    val colors = LocalZemoteColors.current
    val first = (interactions?.firstOrNull() as? JsonObject) ?: return
    val payload = first["payload"].asObj() ?: return
    val kind = payload["kind"].asString()
    val options = payload["options"] as? JsonArray
    val title = if (kind == "permission") {
        "权限请求 · ${payload["toolName"].asString().orEmpty()}"
    } else {
        "等待你的输入"
    }
    val detail = payload["summary"].asString()
        ?: payload["prompt"].asString()
        ?: ""

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.interactionAskSurface)
            .border(1.dp, colors.warning.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Text(title, style = ZemoteType.uiSm, color = colors.foreground, fontWeight = FontWeight.Medium)
        if (detail.isNotBlank()) {
            Text(
                detail,
                style = ZemoteType.uiXs,
                color = colors.foregroundSubtle,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (options != null && options.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { option ->
                    val obj = option as? JsonObject ?: return@forEach
                    val label = obj["label"].asString() ?: obj["value"].asString() ?: "确定"
                    val optionId = obj["optionId"].asString()
                    TextButton(onClick = {
                        onResolve(first["interactionId"].asString().orEmpty(), optionId)
                    }) { Text(label, style = ZemoteType.uiXs) }
                }
            }
        }
    }
}

@Composable
private fun QueueCards(queue: JsonArray?, running: Boolean) {
    val colors = LocalZemoteColors.current
    if (queue.isNullOrEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        queue.forEach { item ->
            val obj = item as? JsonObject ?: return@forEach
            val text = obj["text"].asString().orEmpty()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surface)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = text,
                    modifier = Modifier.weight(1f),
                    style = ZemoteType.uiXs,
                    color = colors.foregroundSubtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (running) "排队中" else "待发送",
                    style = ZemoteType.ui2xs,
                    color = colors.foregroundSubtlest,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 输入区
// ---------------------------------------------------------------------------

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    sending: Boolean,
    uploading: Boolean,
    running: Boolean,
    attachments: List<ChatAttachment>,
    model: String?,
    mode: String?,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onOpenModel: () -> Unit,
    onPickAttachment: () -> Unit,
    onRemoveAttachment: (Int) -> Unit,
) {
    val colors = LocalZemoteColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        if (attachments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                attachments.forEachIndexed { index, attachment ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.surface)
                            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${attachment.name} · ${formatBytes(attachment.bytes.size.toLong())}",
                            style = ZemoteType.uiXs,
                            color = colors.foreground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 200.dp),
                        )
                        IconButton(
                            onClick = { onRemoveAttachment(index) },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "移除附件",
                                tint = colors.foregroundSubtle,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.input)
                .border(1.dp, colors.inputBorder, RoundedCornerShape(16.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(onClick = onPickAttachment, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.AttachFile, "附件", tint = colors.foregroundSubtle, modifier = Modifier.size(18.dp))
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                textStyle = ZemoteType.uiBase.copy(color = colors.foreground),
                maxLines = 6,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text("发消息给 ZCode…", style = ZemoteType.uiBase, color = colors.foregroundSubtlest)
                    }
                    inner()
                },
            )
            if (running) {
                IconButton(onClick = onStop, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.Stop, "停止", tint = colors.destructive, modifier = Modifier.size(18.dp))
                }
            } else {
                IconButton(
                    onClick = onSend,
                    enabled = (value.isNotBlank() || attachments.isNotEmpty()) && !sending,
                    modifier = Modifier.size(34.dp),
                ) {
                    if (sending || uploading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 1.5.dp)
                    } else {
                        Icon(
                            Icons.Default.Send,
                            "发送",
                            tint = if (value.isNotBlank() || attachments.isNotEmpty()) {
                                colors.primary
                            } else {
                                colors.foregroundSubtlest
                            },
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onOpenModel) {
                Text(
                    text = listOfNotNull(model, mode).joinToString(" · ").ifBlank { "模型" },
                    style = ZemoteType.ui2xs,
                    color = colors.foregroundSubtle,
                )
            }
            Spacer(Modifier.weight(1f))
            Text("↵ 发送", style = ZemoteType.ui2xs, color = colors.foregroundSubtlest)
        }
    }
}

@Composable
private fun ModelSheet(
    config: JsonObject?,
    onDismiss: () -> Unit,
    onMode: (String) -> Unit,
    onThought: (String) -> Unit,
) {
    val colors = LocalZemoteColors.current
    val provider = config?.get("provider").asString()
    val model = config?.get("model").asString()
    val currentMode = config?.get("mode").asString()
    val thought = config?.get("thought").asString()
    val levels = (config?.get("thoughtLevels") as? JsonArray)
        ?.mapNotNull { it.asString() }
        .orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("模型与模式") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = listOfNotNull(provider, model).joinToString(" / ").ifBlank { "未提供" },
                    style = ZemoteType.uiSm,
                    color = colors.foreground,
                )
                Spacer(Modifier.height(12.dp))
                Text("模式", style = ZemoteType.uiXs, color = colors.foregroundSubtle)
                listOf("build", "edit", "plan", "yolo").forEach { mode ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onMode(mode) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(mode, Modifier.weight(1f), style = ZemoteType.uiBase)
                        if (mode == currentMode) {
                            Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                if (levels.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("思考级别", style = ZemoteType.uiXs, color = colors.foregroundSubtle)
                    levels.forEach { level ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onThought(level) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(level, Modifier.weight(1f), style = ZemoteType.uiBase)
                            if (level == thought) {
                                Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

// ---------------------------------------------------------------------------
// 空态 / 子代理详情
// ---------------------------------------------------------------------------

@Composable
private fun EmptyChat(running: Boolean, isNew: Boolean) {
    val colors = LocalZemoteColors.current
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (running) {
            CircularProgressIndicator(color = colors.foregroundSubtle)
            Spacer(Modifier.height(12.dp))
            Text("正在加载对话…", style = ZemoteType.uiSm, color = colors.foregroundSubtle)
        } else {
            Text(
                text = if (isNew) "开始一个新对话" else "这个对话还没有内容",
                style = ZemoteType.uiLg,
                color = colors.foreground,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (isNew) "输入第一条消息后会在电脑端创建会话。" else "在下方输入框继续对话。",
                style = ZemoteType.uiSm,
                color = colors.foregroundSubtle,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SubagentDetailDialog(row: JsonObject, onDismiss: () -> Unit) {
    val colors = LocalZemoteColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(row["subagentType"].asString() ?: "子代理") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "状态：${statusLabel(row["status"].asString().orEmpty())}",
                    style = ZemoteType.uiSm,
                    color = colors.foregroundSubtle,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = row["summaryText"].asString().orEmpty().ifBlank { "（无摘要）" },
                    style = ZemoteType.uiSm,
                    color = colors.foreground,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

// ---------------------------------------------------------------------------
// 展示辅助
// ---------------------------------------------------------------------------

private fun durationMs(row: JsonObject): Long {
    row["activeMs"].asLong()?.let { return it }
    row["durationMs"].asLong()?.let { return it }
    val start = row["startedAt"].asLong() ?: return 0L
    val end = row["endedAt"].asLong() ?: System.currentTimeMillis()
    return (end - start).coerceAtLeast(0L)
}

internal fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0秒"
    if (ms < 1000) return String.format(Locale.US, "%.1f秒", ms / 1000.0)
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}时${minutes}分"
        minutes > 0 -> "${minutes}分${seconds}秒"
        else -> "${seconds}秒"
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(Locale.US, "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}

private fun toolSummary(row: JsonObject): String {
    val input = row["input"].asObj()
    val candidates = listOf("path", "file_path", "command", "query", "pattern", "url", "description")
    for (key in candidates) {
        input?.get(key).asString()?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return row["inputText"].asString().orEmpty()
        .lineSequence()
        .firstOrNull { it.isNotBlank() }
        .orEmpty()
}

private fun statusLabel(status: String): String = when (status) {
    "running" -> "运行中"
    "success", "completed" -> "已完成"
    "failed", "error" -> "失败"
    "cancelled" -> "已取消"
    else -> status
}

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
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@withContext null
        Triple(name, mime, bytes)
    } catch (e: Exception) {
        null
    }
}
