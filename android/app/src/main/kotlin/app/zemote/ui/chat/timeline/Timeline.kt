package app.zemote.ui.chat.timeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.zemote.log.ZLog
import app.zemote.ui.chat.asLong
import app.zemote.ui.chat.asObj
import app.zemote.ui.chat.asString
import app.zemote.ui.chat.isStreaming
import app.zemote.ui.chat.kind
import app.zemote.ui.chat.rowId
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteSpacing
import app.zemote.ui.theme.ZemoteType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * 对话时间线。
 *
 * ## 底锚定（reverseLayout）
 *
 * 列表以 `reverseLayout` 渲染：index 0 = 最新行 = 视觉底部。停在底部时，流式追加/长高
 * 在**同一布局回合**把旧行顶上去——先让位、再追加，没有"先溢出再补偿滚动"的一帧滞后。
 * 程序化贴底只剩兜底职责（IME、锚点漂移、换会话归位）。
 *
 * ## 跟随（follow）
 *
 * 用户停在底部时新内容自动可见；主动上滚后退出跟随，新行只累计不抢滚动；回到底部恢复。
 *
 * ## 静默历史
 *
 * 视觉顶部（最高 index）约一屏半以内时自动调 [onLoadOlder]；向前插入只发生在列表末尾
 * （高 index 侧），不会改变底部锚点。
 */
@Composable
internal fun ConversationTimeline(
    rows: List<JsonObject>,
    isRunning: Boolean,
    canLoadOlder: Boolean,
    loadingOlder: Boolean,
    onLoadOlder: () -> Unit,
    onImageClick: (String) -> Unit,
    imageLoader: suspend (String) -> ByteArray?,
    onSubagentClick: (JsonObject) -> Unit,
    /** 可见区是否已远离最老端；窗口裁剪只在这个信号为 true 时进行（10 审计 §3）。 */
    onWindowTrimSafe: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val colors = LocalZemoteColors.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val expandedTurns = remember { mutableStateOf(setOf<String>()) }
    val collapsedTurns = remember { mutableStateOf(setOf<String>()) }
    val openTools = remember { mutableStateOf(setOf<Long>()) }
    val openReasoning = remember { mutableStateOf(setOf<Long>()) }
    val openGroups = remember { mutableStateOf(setOf<Long>()) }
    val closedGroups = remember { mutableStateOf(setOf<Long>()) }

    // 行窗口会因「向前加载历史」被拼接：先按 rowId 排序、按 rowId 去重，
    // 保证单元构建、LazyColumn key 和折叠判定都基于同一份稳定顺序。
    val orderedRows = remember(rows) { normalizeTimelineRows(rows) }
    val units = remember(orderedRows) { buildTimelineUnitsOrdered(orderedRows) }

    val runningTurns = remember(orderedRows) {
        orderedRows.filter { it.isStreaming() }
            .mapNotNull { it["turnId"].asString() }
            .toSet()
    }

    // 网页端 `assistantHistoryDefaultOpen`：运行中、失败/中断、以及「有历史行但没有最终
    // 回答」的轮次默认展开，且不可折叠；普通完成的轮次默认折叠。
    val defaultOpenTurns = remember(orderedRows) { defaultOpenTurnIds(orderedRows) }
    val finalAnswerRowIds = remember(orderedRows) { finalAnswerRowIdByTurn(orderedRows) }

    fun isExpanded(turnId: String?): Boolean =
        isTurnExpanded(turnId, defaultOpenTurns, expandedTurns.value, collapsedTurns.value)

    fun isCollapsible(turnId: String?): Boolean =
        turnId != null && turnId !in defaultOpenTurns

    val visibleUnits = visibleTimelineUnits(
        units = units,
        defaultOpenTurns = defaultOpenTurns,
        expandedTurns = expandedTurns.value,
        collapsedTurns = collapsedTurns.value,
        runningTurns = runningTurns,
        finalAnswerRowIds = finalAnswerRowIds,
    )
    // 交给 LazyColumn 的 key 必须全局唯一：缺 rowId 的匿名行、重复内容行在这里
    // 兜底追加 `#n`，绝不把重复 key 交给 LazyColumn（重复 key 会直接崩溃）。
    val unitKeys = remember(visibleUnits) { uniqueTimelineKeys(visibleUnits) }

    // reverseLayout 的坐标真理：firstVisibleItemIndex/ScrollOffset 就是「距视觉底」的坐标，
    // (0,0) = 准确贴底。不要再用可见项的几何反推（08 审计 P0-2：旧公式在真底部恒 false）。
    val bottomEpsilonPx = with(density) { BOTTOM_EPSILON_DP.dp.roundToPx() }
    val atBottom by remember(listState, bottomEpsilonPx) {
        derivedStateOf {
            listState.layoutInfo.totalItemsCount > 0 &&
                listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset <= bottomEpsilonPx
        }
    }

    val nearTop by remember(listState) {
        derivedStateOf {
            // 视觉顶部 = 最高 index。按像素算距顶（网页阈值 max(64px, 2×视口高)）；
            // 内容装得下（不可滚动）时永不预取。
            val info = listState.layoutInfo
            if (!listState.canScrollBackward && !listState.canScrollForward) {
                return@derivedStateOf false
            }
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
            val contentTop = info.viewportEndOffset - info.afterContentPadding
            val distanceFromTop = contentTop - (last.offset + last.size)
            distanceFromTop <= maxOf(
                with(density) { 64.dp.roundToPx() },
                2 * info.viewportSize.height,
            )
        }
    }

    // 可见区距最老端 > 3 屏才允许窗口裁剪（10 审计 §3：安全裁剪距离）。
    // 与 nearTop 同一像素口径，但阈值更保守；不可滚动时不动数据。
    val farFromTop by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
            val contentTop = info.viewportEndOffset - info.afterContentPadding
            val distanceFromTop = contentTop - (last.offset + last.size)
            distanceFromTop > 3 * info.viewportSize.height
        }
    }
    val currentTrimCallback = rememberUpdatedState(onWindowTrimSafe)
    LaunchedEffect(Unit) {
        snapshotFlow { farFromTop }.collect { currentTrimCallback.value(it) }
    }

    // 跟随是「用户意图」而不是「当前位置」：程序化滚动或向前插入历史时 atBottom
    // 会短暂为 false，不能据此退出跟随，否则预取一插入就把视口丢在历史中间。
    // 手指一开始拖动就立即退出跟随；松手后可能还有惯性滚动，必须等滚动真正停止
    // 再按最终位置决定是否恢复，避免「松手瞬间仍在底部」把 following 抢回 true。
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    var following by remember { mutableStateOf(true) }
    var userDragged by remember { mutableStateOf(false) }

    LaunchedEffect(dragging) {
        if (dragging) {
            userDragged = true
            following = false
            ZLog.ui("timeline", "drag start → following=false units=${visibleUnits.size}")
            return@LaunchedEffect
        }
        if (!userDragged) return@LaunchedEffect
        // 松手后可能还有惯性滚动：等它停下并留一段稳定期，再以最终位置定意图。
        snapshotFlow { listState.isScrollInProgress }.first { !it }
        delay(SETTLE_DELAY_MS)
        following = atBottom && !listState.isScrollInProgress
        ZLog.ui("timeline", "drag end → following=$following atBottom=$atBottom")
    }

    // 新窗口从空变为有内容（首次加载/切换会话）时重新以贴底为意图：不沿用上一个
    // 会话「已上滚」的旧状态。reverseLayout 的初始锚点就是底部（index 0），一般无需滚动；
    // 复用 listState 换会话时才需要显式归位。
    LaunchedEffect(rows.isEmpty()) {
        if (rows.isEmpty()) return@LaunchedEffect
        following = true
        listState.pinToBottom()
        ZLog.ui(
            "timeline",
            "content appeared → follow+bottom rows=${orderedRows.size} units=${visibleUnits.size}",
        )
    }

    // 内容变化时的跟随（承重逻辑）：reverseLayout **不会**因为 index 0 插入而自动跟随
    // （LazyList 按首个可见项的 key 锚定），新行/新单元必须显式 pin；行内长高由锚点天然保持。
    // 只统计最后一个 turn 的流式签名，避免每个 delta 对全量 1300 行做 sumOf（08 审计 P2-4）。
    val latestUnits by rememberUpdatedState(visibleUnits)
    val latestContentSignature by rememberUpdatedState(
        run {
            val lastTurn = orderedRows.lastOrNull()?.get("turnId").asString()
            val tail = orderedRows.takeLastWhile { it["turnId"].asString() == lastTurn }
            tail.sumOf { row ->
                if (row.isStreaming()) row.contentSignature().toLong() else 0L
            } + (orderedRows.lastOrNull()?.contentSignature() ?: 0)
        },
    )
    LaunchedEffect(Unit) {
        snapshotFlow {
            Triple(latestUnits.size, latestUnits.lastOrNull()?.lastRowId, latestContentSignature)
        }
            .collect {
                if (dragging || !following || latestUnits.isEmpty()) return@collect
                // 已贴底时 no-op；新行插入会把位置顶到 (1,0)，这里必须补一次 pin。
                if (!atBottom) {
                    ZLog.ui("timeline", "follow pin units=${latestUnits.size}")
                    listState.pinToBottom()
                }
            }
    }

    // 视口高度变化（IME/分屏）：跟随时贴回底边；非跟随时补偿同样的位移，
    // 让屏幕上正在读的内容保持不动。
    LaunchedEffect(Unit) {
        var previousHeight = listState.layoutInfo.viewportSize.height
        snapshotFlow { listState.layoutInfo.viewportSize.height }
            .distinctUntilChanged()
            .collect { height ->
                val delta = previousHeight - height
                previousHeight = height
                if (delta == 0 || latestUnits.isEmpty()) return@collect
                if (following) {
                    if (!atBottom) listState.pinToBottom()
                } else {
                    // reverse：视口底边上移 delta，向后滚 delta 让内容跟随，屏幕位置不变。
                    listState.scrollBy(-delta.toFloat())
                }
            }
    }

    // 静默向前翻历史：滚动驱动、一次手势最多一页。
    // 到达视觉顶部拉一页后解除武装；只有当视口离开阈值区（新内容插在上方后自然发生）
    // 才重新武装。不再用「rows.size 变化 + 1.5s 无限重试」——那会在 pin 抖动时自激，
    // 把整段历史自动拉完（08/10 审计）。
    val canLoadRef = rememberUpdatedState(canLoadOlder)
    val loadingOlderRef = rememberUpdatedState(loadingOlder)
    val onLoadOlderRef = rememberUpdatedState(onLoadOlder)
    var pagingArmed by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        snapshotFlow { nearTop }.collect { top ->
            if (!top) {
                pagingArmed = true
                return@collect
            }
            if (pagingArmed && canLoadRef.value && !loadingOlderRef.value) {
                pagingArmed = false
                ZLog.ui("timeline", "loadOlder trigger nearTop rows=${orderedRows.size}")
                onLoadOlderRef.value()
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        if (rows.isEmpty()) {
            EmptyTimeline(running = isRunning)
        } else {
            // reverseLayout：index 0 = 最新行 = 视觉底部。底锚定让流式追加/长高在同一
            // 布局回合把旧行顶上去（先让位、再追加），不需要事后 scrollToItem 补偿。
            val bottomFirstUnits = remember(visibleUnits) { visibleUnits.asReversed() }
            val bottomFirstKeys = remember(unitKeys) { unitKeys.asReversed() }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = TimelineContentPadding,
                reverseLayout = true,
                // 网页 turn 内 flow items 是 `gap-5`（20px）；turn 之间再加 turnHeader 的 36dp。
                verticalArrangement = Arrangement.spacedBy(ZemoteSpacing.space5),
            ) {
                itemsIndexed(bottomFirstUnits, key = { index, _ -> bottomFirstKeys[index] }) { _, unit ->
                    Box(Modifier.fillMaxWidth()) {
                        when (unit) {
                            is TimelineUnit.Single -> {
                                val row = unit.row
                                val turnId = row["turnId"].asString()
                                when (row.kind()) {
                                    "turnHeader" -> TurnHeaderRow(
                                        row = row,
                                        expanded = isExpanded(turnId),
                                        collapsible = isCollapsible(turnId),
                                        onToggle = {
                                            if (isCollapsible(turnId)) {
                                                val id = turnId.orEmpty()
                                                if (isExpanded(turnId)) {
                                                    expandedTurns.value -= id
                                                    collapsedTurns.value += id
                                                } else {
                                                    expandedTurns.value += id
                                                    collapsedTurns.value -= id
                                                }
                                            }
                                        },
                                    )

                                    "userInput" -> UserInputRow(
                                        row = row,
                                        onImageClick = onImageClick,
                                        imageLoader = imageLoader,
                                    )

                                    "assistantText" -> AssistantTextRow(row, onImageClick)

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
                                        open = row.rowId() in openTools.value,
                                        onToggle = {
                                            if (row.rowId() in openTools.value) {
                                                openTools.value -= row.rowId()
                                            } else {
                                                openTools.value += row.rowId()
                                            }
                                        },
                                        subagent = unit.subagent,
                                        onSubagentClick = unit.subagent?.let {
                                            { onSubagentClick(it) }
                                        },
                                    )

                                    "artifact" -> ArtifactRow(row)

                                    "subagent" -> SubagentRow(
                                        row = row,
                                        onClick = { onSubagentClick(row) },
                                    )

                                    "hookInvocation" -> Unit

                                    "timelineMarker" -> MarkerRow(row)

                                    else -> UnknownRow(row)
                                }
                            }

                            is TimelineUnit.HookTrailer -> HookTrailerRow(unit.hooks)

                            is TimelineUnit.Group -> {
                                // 展开态的身份基准与 Group.key 一致：运行中的组只追加到尾部，
                                // 用组尾会让每次追加都丢掉用户手动展开/收起的选择（09 P2-3）。
                                val key = unit.rows.first().rowId()
                                val open = when {
                                    key in openGroups.value -> true
                                    key in closedGroups.value -> false
                                    else -> unit.family.defaultOpen
                                }
                                ToolGroupRow(
                                    family = unit.family,
                                    rows = unit.rows,
                                    open = open,
                                    onToggle = {
                                        if (open) {
                                            openGroups.value -= key
                                            closedGroups.value += key
                                        } else {
                                            openGroups.value += key
                                            closedGroups.value -= key
                                        }
                                    },
                                    onRowToggle = { id ->
                                        if (id in openTools.value) {
                                            openTools.value -= id
                                        } else {
                                            openTools.value += id
                                        }
                                    },
                                    isRowOpen = { id -> id in openTools.value },
                                )
                            }
                        }
                    }
                }
            }
        }

        // 右侧滚动进度指示条（timeline/TimelineScrollIndicator.kt）。空时间线不显示，
        // 避免 LazyColumn 不在树上时读到上一次的 layoutInfo。
        if (visibleUnits.isNotEmpty()) {
            TimelineScrollIndicator(
                listState = listState,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        // 非跟随（用户在读历史）时底部渐隐，提示下面还有新内容。
        AnimatedVisibility(
            visible = !following && visibleUnits.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, colors.background),
                        ),
                    ),
            )
        }

        // 显隐跟「用户意图」走而不是「是否恰好贴底」：插入新行的一帧里 atBottom
        // 会短暂为 false，用它会闪一下（08 审计 P1-3）。网页端按钮无显隐动画。
        if (!following && visibleUnits.isNotEmpty()) {
            Surface(
                onClick = {
                    following = true
                    scope.launch {
                        // 瞬时贴底（最新行底边对齐视口底部），不是动画滚到顶部对齐。
                        listState.pinToBottom()
                    }
                },
                shape = CircleShape,
                color = colors.card,
                shadowElevation = 3.dp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
            ) {
                Icon(
                    Icons.Default.ArrowDownward,
                    contentDescription = "回到底部",
                    tint = colors.foregroundSubtle,
                    modifier = Modifier.padding(8.dp).size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyTimeline(running: Boolean) {
    val colors = LocalZemoteColors.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = if (running) "正在加载对话…" else "这个对话还没有内容",
            style = ZemoteType.uiSm,
            color = colors.foregroundSubtle,
        )
    }
}

@Composable
private fun UnknownRow(row: JsonObject) {
    val colors = LocalZemoteColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(
            text = "[${row.kind()}]",
            style = ZemoteType.ui2xs,
            color = colors.foregroundSubtlest,
        )
    }
}

/** 贴底判定容差（与网页 48px 对齐）：再往上滚超过这个距离就视为「离开底部」。 */
private const val BOTTOM_EPSILON_DP = 48

/** 松手后等待惯性滚动稳定的时间，期间不恢复跟随。 */
private const val SETTLE_DELAY_MS = 100L

/** 列表内边距：常量实例，完整历史下不会在每次重组时重新分配。 */
private val TimelineContentPadding =
    PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 20.dp)

/** 时间线渲染单元：单行，或连续同家族工具合并出的分组。 */
internal sealed interface TimelineUnit {
    val key: Any
    val turnId: String?
    val isWork: Boolean

    /** 单元内最后一行的 `rowId`；折叠判定用它比较「最终回答」的位置。 */
    val lastRowId: Long

    class Single(
        val row: JsonObject,
        /** 与这条 Agent 工具调用配对的 subagent 行（网页端会合并进工具摘要行）。 */
        val subagent: JsonObject? = null,
    ) : TimelineUnit {
        // 缺 rowId 时退回「轮次 + 类型 + 正文 hash」的内容键；同键重复由
        // uniqueTimelineKeys 追加序号，避免所有匿名行都撞在 0 上。
        override val key: Any get() = row["rowId"].asLong() ?: "anon:${row.contentKeyFragment()}"
        override val turnId: String? get() = row["turnId"].asString()
        override val isWork: Boolean get() = row.kind() in WORK_ROW_KINDS
        override val lastRowId: Long get() = row.rowId()
    }

    class Group(val family: ToolFamily, val rows: List<JsonObject>) : TimelineUnit {
        // 身份基准取组首行：运行中的组只会往尾部追加，组首不变，key 稳定 → 展开态和
        // 视口锚点不会被每次追加打断。代价是向前翻历史把更早的同族工具并进组首时
        // 会换 key；prepend 频率远低于流式追加，且只影响正读顶部的那一个组（08 P2-3）。
        override val key: Any
            get() = rows.first()["rowId"].asLong()?.let { "tool-group:$it" }
                ?: "tool-group:${turnId.orEmpty()}:${family.name}:${rows.first().contentKeyFragment()}"
        override val turnId: String? get() = rows.first()["turnId"].asString()
        override val isWork: Boolean get() = true
        override val lastRowId: Long get() = rows.last().rowId()
    }

    /** 轮次底部的钩子入口；不是时间线行，不参与折叠。 */
    class HookTrailer(override val turnId: String?, val hooks: List<JsonObject>) : TimelineUnit {
        override val key: Any
            get() {
                turnId?.let { return "hook-trailer:$it" }
                val firstId = hooks.first()["rowId"].asLong()
                return if (firstId != null) {
                    "hook-trailer:$firstId"
                } else {
                    "hook-trailer:anon:${hooks.size}:${hooks.first().contentKeyFragment()}"
                }
            }
        override val isWork: Boolean get() = false
        override val lastRowId: Long get() = hooks.last().rowId()
    }
}

/**
 * 行窗口归一化：按 `rowId` 升序，并只合并**完全相同 rowId** 的重复行。
 *
 * 「向前加载历史」会把新取到的旧行拼到窗口前面，批次边界可能重叠；这里保证
 * 单元构建、LazyColumn key 与折叠判定使用同一份稳定顺序。去重只针对 rowId，
 * 不会误删新加载的旧行。
 */
internal fun normalizeTimelineRows(rows: List<JsonObject>): List<JsonObject> {
    if (rows.size < 2) return rows
    var ordered = true
    var previous = Long.MIN_VALUE
    val byId = LinkedHashMap<Long, JsonObject>(rows.size)
    // 匿名行（缺 rowId）不能简单拼到末尾——那是视觉最顶/最旧端，流式行会「跳上去」。
    // 记住它前面最近一条有 id 的行，排序后原样贴回该行后面（08 审计 §5.2）。
    val anonymous = ArrayList<Pair<Long, JsonObject>>()
    var lastId = Long.MIN_VALUE
    for (row in rows) {
        val id = row["rowId"].asLong()
        if (id == null) {
            anonymous += lastId to row
            ordered = false
            continue
        }
        if (id < previous) ordered = false
        previous = id
        lastId = id
        // put 返回旧值即重复 rowId：保留最后一份内容，位置随后按 rowId 重排。
        if (byId.put(id, row) != null) ordered = false
    }
    if (ordered && anonymous.isEmpty()) return rows
    val sorted = byId.entries.sortedBy { it.key }.map { it.value }
    if (anonymous.isEmpty()) return sorted
    // 无锚点（排在所有有 id 行之前）的保持在最前，其余跟在自己锚点行之后。
    val byAnchor = anonymous.groupBy({ it.first }, { it.second })
    val result = ArrayList<JsonObject>(rows.size)
    byAnchor[Long.MIN_VALUE]?.let { result += it }
    for (row in sorted) {
        result += row
        byAnchor[row["rowId"].asLong()]?.let { result += it }
    }
    return result
}

/** 每轮「最终回答」所在行的 rowId：该轮最后一个 `assistantText`。 */
internal fun finalAnswerRowIdByTurn(rows: List<JsonObject>): Map<String, Long> {
    val result = LinkedHashMap<String, Long>()
    rows.forEach { row ->
        if (row.kind() != "assistantText") return@forEach
        val turnId = row["turnId"].asString() ?: return@forEach
        val rowId = row["rowId"].asLong() ?: return@forEach
        result[turnId] = rowId
    }
    return result
}

/**
 * 默认展开的轮次（网页端 `assistantHistoryDefaultOpen`）。
 *
 * 运行中（段标题状态或仍有流式行）、`completedInterrupted` / `failed`、
 * 以及「有工作行但之后没有最终回答」的轮次默认展开且不可折叠。
 */
internal fun defaultOpenTurnIds(rows: List<JsonObject>): Set<String> {
    val states = mutableMapOf<String, String?>()
    val lastWork = mutableMapOf<String, Int>()
    val lastAnswer = mutableMapOf<String, Int>()
    val streamingTurns = mutableSetOf<String>()
    rows.forEachIndexed { index, row ->
        val turnId = row["turnId"].asString() ?: return@forEachIndexed
        when (row.kind()) {
            "turnHeader" -> states[turnId] = row["state"].asString()
            "assistantText" -> lastAnswer[turnId] = index
            in WORK_ROW_KINDS -> lastWork[turnId] = index
        }
        if (row.isStreaming()) streamingTurns += turnId
    }
    val turns = LinkedHashSet<String>()
    turns += states.keys
    turns += lastWork.keys
    turns += lastAnswer.keys
    turns += streamingTurns
    return turns.filterTo(mutableSetOf()) { turnId ->
        val state = states[turnId]
        val work = lastWork[turnId]
        val hasFinalAnswer = work != null && (lastAnswer[turnId] ?: -1) > work
        state == "running" ||
            state == "completedInterrupted" ||
            state == "failed" ||
            turnId in streamingTurns ||
            (work != null && !hasFinalAnswer)
    }
}

/** 轮次是否展开；默认展开的轮次优先于用户手动折叠状态。 */
internal fun isTurnExpanded(
    turnId: String?,
    defaultOpenTurns: Set<String>,
    expandedTurns: Set<String>,
    collapsedTurns: Set<String>,
): Boolean = when {
    turnId == null -> true
    turnId in defaultOpenTurns -> true
    turnId in expandedTurns -> true
    turnId in collapsedTurns -> false
    else -> false
}

/**
 * 过滤折叠后的可见单元。
 *
 * 折叠只隐藏「最终回答之前」的工作行：段标题、用户气泡、最终回答、变更卡
 * （`artifact`）等非工作行始终保留；最终回答之后的工作行也保留。
 */
internal fun visibleTimelineUnits(
    units: List<TimelineUnit>,
    defaultOpenTurns: Set<String> = emptySet(),
    expandedTurns: Set<String> = emptySet(),
    collapsedTurns: Set<String> = emptySet(),
    runningTurns: Set<String> = emptySet(),
    finalAnswerRowIds: Map<String, Long> = emptyMap(),
): List<TimelineUnit> = units.filter { unit ->
    when {
        // 运行中的轮次不显示钩子入口（网页端 `!isRunning`）。
        unit is TimelineUnit.HookTrailer -> unit.turnId !in runningTurns
        !unit.isWork -> true
        isTurnExpanded(unit.turnId, defaultOpenTurns, expandedTurns, collapsedTurns) -> true
        else -> {
            val finalAnswer = unit.turnId?.let { finalAnswerRowIds[it] }
            // 没有最终回答时折叠隐藏全部工作行；有最终回答时只隐藏它之前的工作行。
            finalAnswer != null && unit.lastRowId > finalAnswer
        }
    }
}

/**
 * 归约渲染单元：同一轮次内连续 ≥2 条同家族工具调用合并为一组。
 *
 * `hookInvocation` 不进入行流（网页端同样摘除），而是归到所属轮次末尾的入口按钮。
 * 单条工具、未知工具、以及跨轮次的工具都保持独立行。
 */
internal fun buildTimelineUnits(rows: List<JsonObject>): List<TimelineUnit> =
    buildTimelineUnitsOrdered(normalizeTimelineRows(rows))

/** 输入必须已由 [normalizeTimelineRows] 按 rowId 排序去重。 */
private fun buildTimelineUnitsOrdered(rows: List<JsonObject>): List<TimelineUnit> {
    val hooksByTurn = LinkedHashMap<String?, MutableList<JsonObject>>()
    rows.forEach { row ->
        if (row.kind() != "hookInvocation") return@forEach
        hooksByTurn.getOrPut(row["turnId"].asString()) { mutableListOf() } += row
    }

    // Agent 工具调用与 subagent 行配对：配对成功时 subagent 合并进工具摘要行，不再单独渲染。
    val toolCallIds = rows.asSequence()
        .filter { it.kind() == "toolCall" }
        .mapNotNull { it["toolCallId"].asString() }
        .toSet()
    val subagentsByParent = LinkedHashMap<String, JsonObject>()
    rows.forEach { row ->
        if (row.kind() != "subagent") return@forEach
        val parent = row["parentToolCallId"].asString() ?: return@forEach
        subagentsByParent.putIfAbsent(parent, row)
    }

    val units = ArrayList<TimelineUnit>(rows.size)
    var i = 0
    while (i < rows.size) {
        val row = rows[i]
        if (row.kind() == "hookInvocation") {
            i++
            continue
        }
        if (row.kind() == "subagent" &&
            row["parentToolCallId"].asString() in toolCallIds
        ) {
            i++
            continue
        }
        if (row.kind() != "toolCall") {
            units += TimelineUnit.Single(row)
            i++
            continue
        }
        val turnId = row["turnId"].asString()
        val family = toolFamilyOf(row["toolName"].asString().orEmpty())
        // 没有 turnId 就无法确认轮次边界，保持独立行，避免把不同轮次的工具合并。
        if (family == null || turnId == null) {
            units += TimelineUnit.Single(
                row = row,
                subagent = subagentsByParent[row["toolCallId"].asString()],
            )
            i++
            continue
        }
        var j = i + 1
        while (j < rows.size) {
            val next = rows[j]
            if (next.kind() != "toolCall") break
            if (next["turnId"].asString() != turnId) break
            if (toolFamilyOf(next["toolName"].asString().orEmpty()) != family) break
            j++
        }
        if (j - i >= 2) {
            units += TimelineUnit.Group(family, rows.subList(i, j).toList())
            i = j
        } else {
            units += TimelineUnit.Single(row)
            i++
        }
    }

    // 把钩子入口插到所属轮次的最后一个可见单元之后。
    hooksByTurn.forEach { (turnId, hooks) ->
        val at = units.indexOfLast { it.turnId == turnId }
        if (at >= 0) {
            units.add(at + 1, TimelineUnit.HookTrailer(turnId, hooks))
        }
    }
    return units
}

/** 最后一行内容长度的粗略信号：流式文本/输出增长时也要跟随到底部。 */
private fun JsonObject.contentSignature(): Int =
    (get("text").asString()?.length ?: 0) +
        (get("summaryText").asString()?.length ?: 0) +
        (get("inputText").asString()?.length ?: 0) +
        // output 是对象；只读它的 text，别对整棵 JSON 做 toString（08 P2-4）。
        (get("output").asObj()?.get("text").asString()?.length ?: 0)

/** 匿名行（缺 rowId）的内容键片段：turnId + kind + 正文字段 hash。 */
private fun JsonObject.contentKeyFragment(): String {
    val hash = buildString {
        append(get("text").asString().orEmpty())
        append('\u0000')
        append(get("summaryText").asString().orEmpty())
        append('\u0000')
        append(get("inputText").asString().orEmpty())
        append('\u0000')
        append(get("output")?.toString().orEmpty())
    }.hashCode()
    return "${this["turnId"].asString().orEmpty()}:${kind()}:$hash"
}

/**
 * 与 [units] 一一对应的 LazyColumn key，保证全局唯一。
 *
 * 有 rowId 的单元用 rowId（Long）或带前缀的复合字符串；匿名行的内容键仍可能重复，
 * 这里给重复项依次追加 `#n`（直到真正未使用），避免重复 key 让 LazyColumn 崩溃。
 */
internal fun uniqueTimelineKeys(units: List<TimelineUnit>): List<Any> {
    val used = HashSet<Any>(units.size * 2)
    return units.map { unit ->
        val base = unit.key
        if (used.add(base)) return@map base
        var n = 1
        var candidate: Any = "$base#$n"
        while (!used.add(candidate)) {
            n += 1
            candidate = "$base#$n"
        }
        candidate
    }
}

/**
 * 贴底：reverseLayout 下 `(index 0, offset 0)` 就是视觉底边，**不能**用大 offset——
 * 正偏移在反向布局里朝视觉顶部推进，旧实现 `Int.MAX_VALUE / 2` 会直接跳到最旧端。
 *
 * 已经贴底时严格 no-op：无谓的 `scrollToItem` 会清掉 LazyList 的 lastKnownFirstItemKey，
 * 破坏紧随其后的数据变化的 key 锚定（08 审计 §5.2）。滚动异常只影响跟随，不打断渲染。
 */
private suspend fun LazyListState.pinToBottom() {
    if (firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0) return
    try {
        scrollToItem(index = 0, scrollOffset = 0)
    } catch (cancelled: CancellationException) {
        // 区分「组合被取消」与「被另一次滚动打断」：后者不能终止长驻的跟随效果。
        currentCoroutineContext().ensureActive()
        ZLog.ui("timeline", "pinToBottom superseded")
    } catch (error: Exception) {
        ZLog.ui("timeline", "pinToBottom failed: ${error.javaClass.simpleName}: ${error.message}")
    }
}
