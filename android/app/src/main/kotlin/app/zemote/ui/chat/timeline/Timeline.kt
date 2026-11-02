package app.zemote.ui.chat.timeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.mutableIntStateOf
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
import app.zemote.ui.chat.asLong
import app.zemote.ui.chat.asString
import app.zemote.ui.chat.isStreaming
import app.zemote.ui.chat.kind
import app.zemote.ui.chat.rowId
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteSpacing
import app.zemote.ui.theme.ZemoteType
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * 对话时间线。
 *
 * ## 跟随（follow）
 *
 * 用户停在底部时，新行/流式更新自动滚底；用户主动上滚后退出跟随，新行只累计
 * 「N 条新消息」角标，不抢滚动位置。回到底部后角标清零、恢复跟随。
 *
 * ## 静默历史
 *
 * 距离顶部 3 行以内时自动调 [onLoadOlder]，没有按钮、没有占位跳变；列表以
 * `rowId` 为 key，向前插入不会改变当前视口锚点。
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

    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
            last.index >= info.totalItemsCount - 1 &&
                info.viewportEndOffset - (last.offset + last.size) <= with(density) { 48.dp.roundToPx() }
        }
    }
    val nearTop by remember {
        derivedStateOf {
            // 网页按像素预取（距顶 max(64px, 2×视口高)）；LazyColumn 拿不到精确累计高度，
            // 用「首个可见行索引」近似：约一屏半的提前量。
            listState.firstVisibleItemIndex <= PREFETCH_ROWS
        }
    }

    // 跟随是「用户意图」而不是「当前位置」：程序化滚动或向前插入历史时 atBottom
    // 会短暂为 false，不能据此退出跟随，否则预取一插入就把视口丢在历史中间。
    // 只有用户手指拖动时才按当前位置重算意图。
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    var following by remember { mutableStateOf(true) }

    LaunchedEffect(dragging, atBottom) {
        if (dragging) {
            following = atBottom
        } else if (atBottom) {
            following = true
        }
    }

    // 单一长驻效果：不随 key 重启，避免预取连续插入时把 scrollToItem 反复取消。
    // 同时观察「单元数」与「最后一行内容长度」，流式文本增长也会跟随。
    val latestUnits by rememberUpdatedState(visibleUnits)
    val latestLastContent by rememberUpdatedState(
        orderedRows.lastOrNull()?.contentSignature() ?: 0,
    )
    LaunchedEffect(Unit) {
        snapshotFlow {
            Triple(latestUnits.size, latestUnits.lastOrNull()?.lastRowId, latestLastContent)
        }.collectLatest {
            if (following && latestUnits.isNotEmpty()) {
                listState.scrollToItem(latestUnits.lastIndex)
            }
        }
    }

    // 静默向前翻历史：接近顶部就自动取，不显示按钮。
    // 每个「行数」只请求一次：加载失败（行数不变）时不会随 loadingOlder 翻转反复重试；
    // 成功插入旧行后行数变化，仍在顶部才继续取下一页。
    var olderRequestRowCount by remember { mutableIntStateOf(-1) }
    LaunchedEffect(nearTop, canLoadOlder, loadingOlder, orderedRows.size) {
        if (!nearTop || !canLoadOlder) {
            olderRequestRowCount = -1
        } else if (!loadingOlder && olderRequestRowCount != orderedRows.size) {
            olderRequestRowCount = orderedRows.size
            onLoadOlder()
        }
    }

    Box(modifier.fillMaxSize()) {
        if (rows.isEmpty()) {
            EmptyTimeline(running = isRunning)
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = TimelineContentPadding,
                // 网页 turn 内 flow items 是 `gap-5`（20px）；turn 之间再加 turnHeader 的 36dp。
                verticalArrangement = Arrangement.spacedBy(ZemoteSpacing.space5),
            ) {
                items(visibleUnits, key = { it.key }) { unit ->
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

        // 非贴底时底部渐隐，提示下面还有内容。
        AnimatedVisibility(
            visible = !atBottom && visibleUnits.isNotEmpty(),
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

        // 网页端按钮无显隐动画。
        if (!atBottom && visibleUnits.isNotEmpty()) {
            Surface(
                onClick = {
                    scope.launch {
                        listState.animateScrollToItem(visibleUnits.lastIndex)
                        following = true
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
private fun EmptyTimeline(running: Boolean) {    val colors = LocalZemoteColors.current
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

/**
 * 提前取历史的行数阈值。
 *
 * 网页按像素预取（距顶 `max(64px, 2×视口高)`）；`LazyColumn` 不暴露精确的累计高度，
 * 这里用「首个可见行索引」近似，约一屏半的提前量。
 */
private const val PREFETCH_ROWS = 16

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
        override val key: Any get() = row.rowId()
        override val turnId: String? get() = row["turnId"].asString()
        override val isWork: Boolean get() = row.kind() in WORK_ROW_KINDS
        override val lastRowId: Long get() = row.rowId()
    }

    class Group(val family: ToolFamily, val rows: List<JsonObject>) : TimelineUnit {
        override val key: Any get() = "tool-group:${rows.first().rowId()}"
        override val turnId: String? get() = rows.first()["turnId"].asString()
        override val isWork: Boolean get() = true
        override val lastRowId: Long get() = rows.last().rowId()
    }

    /** 轮次底部的钩子入口；不是时间线行，不参与折叠。 */
    class HookTrailer(override val turnId: String?, val hooks: List<JsonObject>) : TimelineUnit {
        override val key: Any get() = "hook-trailer:${turnId ?: hooks.first().rowId()}"
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
    val anonymous = ArrayList<JsonObject>()
    for (row in rows) {
        val id = row["rowId"].asLong()
        if (id == null) {
            anonymous += row
            ordered = false
            continue
        }
        if (id < previous) ordered = false
        previous = id
        // put 返回旧值即重复 rowId：保留最后一份内容，位置随后按 rowId 重排。
        if (byId.put(id, row) != null) ordered = false
    }
    if (ordered && anonymous.isEmpty()) return rows
    val sorted = byId.entries.sortedBy { it.key }.map { it.value }
    return if (anonymous.isEmpty()) sorted else sorted + anonymous
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
        (get("output")?.toString()?.length ?: 0)
