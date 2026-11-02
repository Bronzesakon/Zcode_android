package app.zemote.ui.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.zemote.ui.chat.media.ImageViewerDialog
import app.zemote.ui.chat.media.fetchImageBytes
import app.zemote.ui.chat.timeline.ConversationTimeline
import app.zemote.ui.chat.timeline.formatBytes
import app.zemote.ui.chat.timeline.statusLabel
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 对话页（UI-SPEC §2）。
 *
 * ```
 * 顶栏（返回 + 标题）
 * 头部（工作区名 + 路径 + ⋮ 菜单）
 * 时间线（9 种行，见 timeline 包）
 * 待交互卡片 / 队列卡片
 * 输入区（文本 + 附件 + 模型 + 发送/停止）
 * ```
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
    onSwitchModel: (String, String) -> Unit = { _, _ -> },
    onLoadModelView: () -> Unit = {},
    onResolveInteraction: (String, String?, String?, String?, JsonObject?) -> Unit,
    onResumeQueue: () -> Unit = {},
    onDismissError: () -> Unit,
    onAttach: (String, String, ByteArray) -> Unit = { _, _, _ -> },
    onRemoveAttachment: (Int) -> Unit = {},
    onLoadAttachment: suspend (String) -> ByteArray? = { null },
    onDraftRestored: () -> Unit = {},
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
        ChatHeaderBar(
            title = state.selectedTask?.title ?: "新对话",
            workspace = state.selectedWorkspace,
            pinned = state.selectedTask?.pinned == true,
            menuOpen = menuOpen,
            onMenuOpenChange = { menuOpen = it },
            onBack = onBack,
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

        // 对话页也要能看见连接状态：否则桥降级/掉线时只能看到消息不再更新。
        val statusText = state.error
            ?: state.bridgeDegraded?.let { "工作区连接已降级：$it" }
            ?: if (state.relayFailureVisible) {
                "连接中断${state.relayFailure?.reason?.let { "：$it" }.orEmpty()}"
            } else {
                null
            }
        if (statusText != null) {
            ChatErrorBanner(statusText, onRefresh, onDismissError)
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            ConversationTimeline(
                rows = state.rows,
                isRunning = state.isRunning,
                canLoadOlder = state.canLoadOlder,
                loadingOlder = state.loadingOlder,
                onLoadOlder = onLoadOlder,
                onImageClick = { key -> previewKey = key },
                imageLoader = imageLoader,
                onSubagentClick = { subagentDetail = it },
            )
        }

        InteractionCard(state.pendingInteractions, onResolveInteraction)
        QueueCards(
            queue = state.queueItems,
            running = state.isRunning,
            autoDrain = state.queueAutoDrain,
            pauseReason = state.queuePauseReason,
            onResume = onResumeQueue,
        )

        Composer(
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
            mode = state.config?.get("mode").asString(),
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
        SubagentSidePane(row = row, onDismiss = { subagentDetail = null })
    }

    previewKey?.let { key ->
        ImageViewerDialog(key = key, loader = imageLoader, onDismiss = { previewKey = null })
    }
}

// ---------------------------------------------------------------------------
// 顶栏 / 头部
// ---------------------------------------------------------------------------

@Composable
private fun ChatHeaderBar(
    title: String,
    workspace: WorkspaceItem?,
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
            .height(48.dp)
            .padding(start = 2.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.ArrowBack, contentDescription = "返回", modifier = Modifier.size(18.dp))
        }
        Text(
            text = title,
            style = ZemoteType.uiBase,
            fontWeight = FontWeight.Medium,
            color = colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = workspace?.path.orEmpty(),
            style = ZemoteType.ui2xs.copy(fontFamily = FontFamily.Monospace),
            color = colors.foregroundSubtle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = 8.dp)
                .widthIn(max = 150.dp),
        )
        Box {
            IconButton(onClick = { onMenuOpenChange(true) }, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "更多",
                    tint = colors.foregroundSubtle,
                    modifier = Modifier.size(18.dp),
                )
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
            .semantics { liveRegion = LiveRegionMode.Assertive }
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
        "权限请求 · ${payload["toolName"].asString().orEmpty()}"
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

        // 表单式 userInput：逐题渲染选项，提交时带 content.answers。
        if (questions != null && questions.isNotEmpty()) {
            questions.forEachIndexed { index, element ->
                val question = element as? JsonObject ?: return@forEachIndexed
                val text = question["question"].asString().orEmpty()
                val label = question["header"].asString()?.takeIf { it.isNotBlank() } ?: text
                val multi = question["multiSelect"].asBool() == true
                val selected = answers[text].orEmpty()
                Text(
                    "${index + 1}. $label",
                    style = ZemoteType.uiSm,
                    color = colors.foreground,
                    modifier = Modifier.padding(top = 8.dp),
                )
                val qOptions = question["options"] as? JsonArray
                if (qOptions != null && qOptions.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        qOptions.forEach { opt ->
                            val obj = opt as? JsonObject ?: return@forEach
                            val value = obj["value"].asString() ?: return@forEach
                            val optionLabel = obj["label"].asString() ?: value
                            val isSelected = value in selected
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(
                                        if (isSelected) colors.selected else Color.Transparent,
                                    )
                                    .border(1.dp, colors.border, RoundedCornerShape(999.dp))
                                    .clickable {
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
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    optionLabel,
                                    style = ZemoteType.uiXs,
                                    color = if (isSelected) colors.foreground else colors.foregroundSubtle,
                                )
                            }
                        }
                    }
                }
            }
            TextButton(
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
                enabled = answers.isNotEmpty(),
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("提交答案", style = ZemoteType.uiSm)
            }
        } else if (options != null && options.isNotEmpty()) {
            // 权限卡片：optionId + action=accept + content{value,label,kind}。
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                options.forEach { option ->
                    val obj = option as? JsonObject ?: return@forEach
                    val optionId = obj["optionId"].asString()
                    val label = obj["label"].asString()?.takeIf { it.isNotBlank() }
                        ?: permissionOptionLabel(obj["kind"].asString())
                        ?: optionId
                        ?: "确定"
                    TextButton(onClick = {
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
                    }) { Text(label, style = ZemoteType.uiXs) }
                }
            }
        }

        if (freeText) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = freeInput,
                    onValueChange = { freeInput = it },
                    modifier = Modifier.weight(1f),
                    textStyle = ZemoteType.uiSm.copy(color = colors.foreground),
                    singleLine = true,
                    placeholder = {
                        Text("输入回复…", style = ZemoteType.uiSm, color = colors.foregroundSubtlest)
                    },
                )
                IconButton(
                    onClick = {
                        onResolve(interactionId, null, freeInput.trim(), null, null)
                        freeInput = ""
                    },
                    enabled = freeInput.isNotBlank(),
                ) {
                    Icon(Icons.Default.Send, "发送回复", modifier = Modifier.size(18.dp))
                }
            }
        }
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
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        if (!autoDrain) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.border.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
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
                TextButton(onClick = onResume) {
                    Text(
                        text = "继续",
                        style = ZemoteType.uiSm,
                        color = colors.foregroundSubtle,
                    )
                }
            }
        }
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
    uploadIndex: Int,
    uploadProgress: Float,
    running: Boolean,
    hasHistory: Boolean,
    attachments: List<ChatAttachment>,
    model: String?,
    mode: String?,
    thought: String?,
    levels: List<String>,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onOpenModel: () -> Unit,
    onPickAttachment: () -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    onMode: (String) -> Unit,
    onThought: (String) -> Unit,
) {
    val colors = LocalZemoteColors.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // edge-to-edge：先让出导航条，再让 IME；网页没有安全区，但 Android 必须有。
            .navigationBarsPadding()
            .imePadding()
            // 网页 dock 内容层 px-4 pb-4（无顶部内边距）。
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
    ) {
        // 网页结构：输入卡片 = 附件（topContent）+ 编辑器（独占一行）+ 工具栏
        // （左侧 +/Mode，右侧模型/Effort/发送）。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.input)
                .border(
                    width = 1.dp,
                    color = if (focused) colors.inputBorderFocused else colors.inputBorder,
                    shape = RoundedCornerShape(16.dp),
                )
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (attachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    attachments.forEachIndexed { index, attachment ->
                        val progress = if (uploading && index == uploadIndex) uploadProgress else null
                        Row(
                            modifier = Modifier
                                .height(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.surface)
                                .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                                .padding(start = 6.dp, end = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(colors.background),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.InsertDriveFile,
                                    contentDescription = null,
                                    tint = colors.foregroundSubtle,
                                    modifier = Modifier.size(16.dp),
                                )
                                if (progress != null) {
                                    UploadProgress(progress)
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.widthIn(max = 160.dp)) {
                                Text(
                                    text = attachment.name,
                                    style = ZemoteType.uiSm,
                                    color = colors.foreground,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = if (progress != null) {
                                        "${(progress * 100).toInt()}%"
                                    } else {
                                        formatBytes(attachment.bytes.size.toLong())
                                    },
                                    style = ZemoteType.ui2xs,
                                    color = if (progress != null) colors.brand else colors.foregroundSubtle,
                                )
                            }
                            IconButton(
                                onClick = { onRemoveAttachment(index) },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "移除附件 ${attachment.name}",
                                    tint = colors.foregroundSubtle,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }
            }
            val placeholder = when {
                running -> "继续输入以排队后续修改"
                hasHistory -> "提出后续修改要求"
                else -> "向 ZCode 提问…"
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                interactionSource = interaction,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp, max = 160.dp),
                textStyle = ZemoteType.mobileInputSafe.copy(color = colors.foreground),
                maxLines = 8,
                // 移动端网页 Enter 只换行，发送靠按钮（报告 06 §4.3）。
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth()) {
                        if (value.isEmpty()) {
                            Text(
                                placeholder,
                                style = ZemoteType.mobileInputSafe,
                                color = colors.foregroundSubtlest,
                            )
                        }
                        inner()
                    }
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onPickAttachment, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.Add,
                            "添加上下文",
                            tint = colors.foregroundSubtle,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    ChipMenu(
                        label = mode ?: "build",
                        options = modeOptions(mode),
                        current = mode,
                        onSelect = onMode,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (model != null) {
                        TextButton(
                            onClick = onOpenModel,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier.widthIn(max = 110.dp),
                        ) {
                            Text(
                                text = model,
                                style = ZemoteType.uiXs,
                                color = colors.foregroundSubtle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (levels.isNotEmpty()) {
                        ChipMenu(
                            label = thought ?: levels.first(),
                            options = levels,
                            current = thought,
                            onSelect = onThought,
                        )
                    }
                    // 网页：运行中且输入为空才显示停止；有输入时显示发送（加入队列）。
                    if (running && value.isBlank()) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.secondary)
                                .clickable(onClick = onStop),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Stop,
                                "停止生成",
                                tint = colors.foreground,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    } else {
                        val canSend = (value.isNotBlank() || attachments.isNotEmpty()) &&
                            !sending && !uploading
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (canSend) colors.brand else colors.brand.copy(alpha = 0.4f),
                                )
                                .clickable(enabled = canSend, onClick = onSend),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.ArrowUpward,
                                "发送",
                                tint = colors.foregroundInverse,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun modeOptions(current: String?): List<String> {
    // 网页端 Mode 菜单不含 plan（plan 由独立开关控制）；兼容值 edit 按需兜底。
    val base = listOf("build", "guarded", "yolo")
    return if (current != null && current.isNotBlank() && current !in base) {
        base + current
    } else {
        base
    }
}

/** 上传进度环：36dp 容器、2dp 描边、7sp 百分比（网页 `r=9 / strokeWidth=2`）。 */
@Composable
private fun UploadProgress(progress: Float) {
    val colors = LocalZemoteColors.current
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background.copy(alpha = 0.85f))
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f)
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(2.dp)) {
            val stroke = 2.dp.toPx()
            drawArc(
                color = colors.border,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = stroke),
            )
            drawArc(
                color = colors.brand,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                style = Stroke(width = stroke),
            )
        }
        Text(
            text = "${(progress * 100).toInt()}%",
            style = ZemoteType.ui2xs.copy(fontSize = 7.sp),
            color = colors.foreground,
        )
    }
}

/** 输入区下方的小选择器（Mode / Effort）。 */
@Composable
private fun ChipMenu(
    label: String,
    options: List<String>,
    current: String?,
    onSelect: (String) -> Unit,
) {
    val colors = LocalZemoteColors.current
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .clickable { open = true }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = ZemoteType.uiXs, color = colors.foregroundSubtle)
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = null,
                tint = colors.foregroundSubtlest,
                modifier = Modifier.size(14.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = { open = false; onSelect(option) },
                    trailingIcon = if (option == current) {
                        { Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp)) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
/** 输入区模型按钮文案：默认 provider `glm` 省略前缀（网页 `uyt` 口径）。 */
private fun modelLabel(config: JsonObject?): String? {
    val provider = config?.get("provider").asString()
    val model = config?.get("model").asString() ?: return null
    return if (provider.isNullOrBlank() || provider == "glm") model else "$provider/$model"
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
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "关闭", tint = colors.foregroundSubtle)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "状态：${statusLabel(row["status"].asString().orEmpty())}",
                    style = ZemoteType.uiSm,
                    color = colors.foregroundSubtle,
                )
                Spacer(Modifier.height(12.dp))
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
