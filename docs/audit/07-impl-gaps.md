# 07 · 网页取证 ↔ 已落地实现 差距清单（T1）

> **范围**：只列 T1（必须与网页一致）的差距。T2（触摸目标、IME、安全区、ripple、hover-only 等平台适配）集中在 §5，**不算 bug**。
> **网页基线**：Web 3.15.3，`archive/pre-3.16.1-auth/Reference_web/3.15.3/assets/index-BGa3KiRe.js` / `index-BD2BL4Cc.css`；证据优先引用 `docs/chat/01…08` 的结论与偏移，补充处直接标 bundle 偏移。
> **代码基线**：`android/app/src/main/kotlin/app/zemote/ui/chat/`（审计时 `Timeline.kt` 17:29、`ChatViewModel.kt` 17:30 为最新版本，其余文件 12:xx 之后未再变动）。
> **方法**：只读审计；逐项对照「网页 DOM/类名/数值」与 Compose 实现，未运行 App。
> **计数**：P0 **1** 条；P1 **19** 条；P2 **23** 条。

---

## 0. 结论摘要

1. **颜色令牌本身没有差距**：`Color.kt` 的默认主题取值与 `docs/chat/07` §2.1 逐条一致（亮 `background #FAFAFA` / `foreground #404040` / `brand #00BCFF`，暗 `#171717` / `#E5E5E5` / `#00A6F4`，`input-border-focused = brand`），差距全部来自「用错令牌/用错结构」，不是令牌值。
2. **最严重的是交互闭环**：`userInput` 类型的待交互卡片只读 `payload.options`，不读 `questions`、不提供 `freeText`/「忽略」，`resolveInteraction` 也只能传 `optionId` → ask-user-question 场景下用户无法作答（P0）。
3. **轮次结构**是第二大块：turnHeader 与用户气泡的顺序依赖 rowId、header 的 40dp 顶部内边距放错位置、折叠无动画、折叠时中间 assistantText 不隐藏（P1）。
4. **输入区三个选择器**（模型 / Effort / Mode）当前是 10sp 纯文字 chip，网页是 14px + lucide 图标 + 独立控件（Effort 还有 brain 图标与竖向进度条），且 Mode 菜单缺 Plan 复选框（P1）。
5. 其余差距集中在工具行摘要（diff、路径缩短、explore 计数）、子代理 side-pane 内容、Artifact 卡片、Markdown 图片与排版细节。

**最严重的 5 条**：P0-1（待交互无法作答）、P1-3（turnHeader/气泡顺序）、P1-4（轮次间距）、P1-7（流式不跟随）、P1-9（Read/Search 被做成可展开）。

---

## 1. P0

### P0-1 待交互卡片无法完成 `userInput` 交互（无 questions / freeText / 忽略）

- **现象**：`ask-user-question` / elicitation 到达时，卡片只渲染 `payload.options`；`questions[]`（表单式提问）完全不渲染。若 `options` 为空（questions 型 payload 常见），卡片只剩标题和一段 prompt，没有任何可点选项；也没有「忽略」或自由输入。
- **期望值**：网页按 `payload.kind` 分两套卡片：`permission` 用 `options[].kind`（allowOnce/allowAlways/deny/custom）+ 末尾自由文本；`userInput` 用 `questions[currentQuestionIndex]`（题号 n/m、上一题/下一题、多选、选项 value/label）+ 末尾 textarea + 「忽略」/「继续/提交」。`resolveInteraction` 支持 `optionId` / `freeText` / `action` / `content`。
- **证据**：`docs/chat/06-composer.md` §7.2–7.3（选项、自由输入、忽略、倒计时）；`docs/chat/08-behavior-protocol.md` §2.3（`UserInputPayload.questions`、`freeText: boolean`）、§2.5（`resolveInteraction` 参数）。
- **当前实现**：`ChatScreen.kt:426-473`（只读 `payload["options"]`，`TextButton` 直接传 `optionId`）；`ChatViewModel.kt:891-905`（只 put `optionId`，无 `freeText`/`action`/`content`）。
- **建议修法**：按 `payload.kind` 拆两个卡片，补齐 `questions`/`freeText`/「忽略」，并让 `resolveInteraction` 透传 `freeText`、`action`、`content`。

---

## 2. P1

### P1-1 用户气泡正文按 Markdown 渲染（网页是纯文本 pre-wrap）

- **现象**：用户输入的 `**粗体**`、`# 标题`、`- 列表`、``` 代码围栏 ``` 会被解析成富文本；网页把用户消息当纯文本，只保留换行与空白。
- **期望值**：`whitespace-pre-wrap break-words` 的纯文本节点，14px/1.5；不解析 Markdown。
- **证据**：`docs/chat/02-turn-user.md` §4.2/§4.4（`data-v4-user-input-collapsible-content` + `whitespace-pre-wrap`，`children:e` 是字符串，bundle `2755536` 区）。
- **当前实现**：`timeline/UserInputRow.kt:78`、`:129`（`MarkdownText(...)`）。
- **建议修法**：气泡正文改 `Text(text, style = uiBase, softWrap = true)`，仅保留 `\n`；Markdown 只用于助手正文。

### P1-2 用户附件渲染在气泡内部（网页在气泡上方独立块）

- **现象**：图片/文件 pill 被放进 `bg-surface` 气泡里，和正文共用内边距；网页附件块在气泡**上方**、右对齐、`mb-2`、`gap-2`，与气泡分离。
- **期望值**：`附件块（media 行 + pill 行，右对齐，间距 8dp） → 气泡（正文）`；图片 80×80/12dp 圆角，文件 pill 高 32dp、全圆角、无边框。
- **证据**：`docs/chat/02-turn-user.md` §4.2 DOM（`data-v4-user-input-attachments` 在 `data-v4-user-input-bubble` 之前）、§5.1–5.2。
- **当前实现**：`timeline/UserInputRow.kt:80-101`（附件在气泡 `Column` 内，正文后）。
- **建议修法**：把附件区移到气泡 `Column` 之前，作为右对齐的独立块，媒体与 pill 分两行。

### P1-3 turnHeader 与 userInput 的顺序依赖 rowId（网页固定「气泡在前」）

- **现象**：`ConversationTimeline` 直接按 `rowId` 升序渲染；`TimelineUnitsTest` 构造的轮次是 `turnHeader → userInput`。若服务端也按此顺序下发，界面会把「已工作…」标题画在用户气泡**上方**。网页 `Htn` 无论 header 在行流何处，都先渲染 `visibleUserInputs`，再把 `Itn`（header + 历史）插到第一个非 userInput 项之前。
- **期望值**：每个 turn 固定 `userInput(s) → turnHeader → 历史行 → 最终回答`。
- **证据**：`docs/chat/02-turn-user.md` §0（`Itn` 插入点 `flowItems.findIndex(e=>e.kind!=="userInput")`，`3379516`）、§1.2；`TimelineUnitsTest.kt:248`（测试假定 header 在 userInput 前）。
- **当前实现**：`timeline/Timeline.kt:94-95`（按 rowId 归一化）、`:176-199`（逐行原序渲染，无 turn 级重排）。
- **建议修法**：构建单元时按 turn 重排：先输出该 turn 的 `userInput`，再输出 `turnHeader` 与其余行。

### P1-4 轮次/标题间距错误（header 的 40dp 顶部内边距塞在轮内）

- **现象**：`TurnHeaderRow` 固定 `padding(top = 40.dp, bottom = 8.dp)`，LazyColumn 再 `spacedBy(16.dp)`：
  - header 在气泡之后时，「气泡 → 标题」= 16+40 = **56dp**（网页 20dp）；
  - header 是轮次首项时，轮次之间 = 16+40 = **56dp**（网页 `pb-5 + pt-14` = 76dp）；
  - 首轮顶部 = 4+40 = **44dp**（网页 56dp）。
- **期望值**：turn 之间 20dp + 非首轮 `pt 56dp`；header 与用户气泡 `mt 20dp`、自身 `pb 8dp` + 1px 底边。
- **证据**：`docs/chat/02-turn-user.md` §1.2（`[&>*+*]:mt-5`、section `pt-14 pb-5`）；`docs/chat/01-timeline.md` §8.2。
- **当前实现**：`timeline/TurnHeaderRow.kt:61`；`timeline/Timeline.kt:174`。
- **建议修法**：去掉 header 的 40dp，改为轮次级「非首轮 padding-top 56dp、轮间 gap 20dp、header 距气泡 20dp」。

### P1-5 轮次折叠没有 300ms 动画

- **现象**：点击 header 后，工作行被 `filter` 立即移除/插入，高度瞬变；网页是 Collapsible 高度 0↔内容高度 + 淡入淡出，300ms ease-in-out。
- **期望值**：`animate-collapsible-down/up` 300ms `cubic-bezier(.4,0,.2,1)`，内容同时 fade 300ms。
- **证据**：`docs/chat/02-turn-user.md` §2.3（`duration-300 ease-in-out`，`735583`）；`docs/CHAT-PAGE-SPEC.md` §3.1。
- **当前实现**：`timeline/Timeline.kt:504-523`（纯过滤，无 `AnimatedVisibility`/`animateContentSize`）。
- **建议修法**：把同一 turn 的工作行包进一个 `AnimatedVisibility`（expand/shrink + fade，300ms），header 与最终回答留在外部。

### P1-6 折叠时中间 `assistantText` 不隐藏

- **现象**：`WORK_ROW_KINDS` 只含 reasoning/toolCall/hookInvocation/subagent；一轮中「工具调用之间」的助手文字（如「我先读一下文件」）在折叠后仍然可见。网页折叠后只保留用户气泡、header、最终回答、变更卡。
- **期望值**：折叠隐藏「最终回答之前的全部历史行」，包括中间 assistantText；只保留该轮最后一个 assistantText。
- **证据**：`docs/chat/02-turn-user.md` §2.1–2.2（`W_` 包住 assistantHistory 分组；折叠后可见内容清单）。
- **当前实现**：`timeline/RowFormat.kt:19-20`；`timeline/Timeline.kt:504-523`（仅按 kind 判定工作行）。
- **建议修法**：折叠判定改为「rowId < 该轮最后一个 assistantText 的所有行」，而不是按 kind 白名单。

### P1-7 流式增长不跟随；自动滚底用了平滑动画

- **现象**：自动滚底只以「最后一个单元的 `lastRowId` 变化」为触发。最后一条 assistantText 流式增长时 rowId 不变 → 不滚动，用户被落在后面；且使用 `animateScrollToItem`（平滑），网页要求即时滚底。
- **期望值**：`following=true` 时，每次行数/总高变化后立即滚到底；只在用户上滚后停止。
- **证据**：`docs/chat/01-timeline.md` §9.2（「每次行数/总高变化后滚到底；不要用平滑动画」）；`docs/CHAT-PAGE-SPEC.md` §2。
- **当前实现**：`timeline/Timeline.kt:147-151`（key = `lastRowId`，`animateScrollToItem`）。
- **建议修法**：以「内容总高/最后一项尺寸变化」为触发，使用 `scrollToItem`（非动画）；流式文本变化也触发。

### P1-8 跟随退出阈值不对（网页上滚 1px 即退出）

- **现象**：只按「距底 ≤48dp」判定 following；用户上滚 20dp 仍被视为跟随，下一条新行到达时会被强行拉回底部。网页对滚轮/触摸上滚是**立即**退出（1px 即可），只有普通拖动/滚动条才等 >48px。
- **期望值**：任何上滚手势立即 `following=false`；距底 >48px 保持非跟随。
- **证据**：`docs/chat/01-timeline.md` §1.2/§1.3；`docs/CHAT-PAGE-SPEC.md` §2。
- **当前实现**：`timeline/Timeline.kt:123-143`（只有 `atBottom` → `following`）。
- **建议修法**：监听 `listState.isScrollInProgress` + `firstVisibleItemScrollOffset` 变化方向，向上位移即置 false；`atBottom` 仅作为恢复条件。

### P1-9 Read / Search 行被做成可展开（网页 `canToggle=false`）

- **现象**：所有 toolCall 行都绑定了 `clickable(onToggle)`，Read/Search 也能展开出 Parameters/Result；网页 file-read、search、message(RespondToCoordinator) 明确 `canToggle:false`、`content:null`、无 chevron、无展开内容。
- **期望值**：Read/Search 行只读，不响应点击、不展开。
- **证据**：`docs/chat/04-toolcall.md` §4.2（`canToggle:false`）、§4.3、§7（不可折叠清单）。
- **当前实现**：`timeline/ToolCallRow.kt:138-141`（无条件 `clickable`）、`:177-196`（无条件 `AnimatedVisibility`）。
- **建议修法**：按 family 计算 `canToggle`（read/search/message = false），false 时不加 clickable、不渲染展开区。

### P1-10 工具失败行没有任何失败标记

- **现象**：`error`/`failed`/`cancelled` 的工具行与成功行完全一样（无状态文字、无颜色、无图标）；网页在失败时显示「执行失败」等状态文字（`showFailureStatus`），折叠行即可见。
- **期望值**：失败行在摘要后显示「执行失败」/「已停止」文字（网页 14px，`text-destructive` 或 subtle，按渲染器而定）。
- **证据**：`docs/chat/04-toolcall.md` §3.2（`G=(showStatusLabel||showFailureStatus) && statusLabel!=null`）、§3.3。
- **当前实现**：`timeline/ToolCallRow.kt:138-174`（只渲染 kindLabel + summary，无 status）。
- **建议修法**：`status in {error,failed,cancelled}` 时在摘要尾部追加本地化状态文案。

### P1-11 工具行摘要缺 diff、路径缩短、explore 计数

- **现象**：
  - write/edit 行没有 `+N/−N`；
  - read 行直接显示原始 `path`，没有「去 workspace 前缀，否则 basename」；
  - 单条 explore 行显示原始 query/description，没有 `N searches, N files` 计数；
  - shell 命令未剥 `bash -lc` 外壳（只按原样显示）。
- **期望值**：write 行 `+N`（`text-diff-added`）/`−N`（`text-diff-removed`），font-mono tabular-nums；read 行文件 chip + 缩短路径；explore 行计数桶；shell 剥壳后只显示真实命令。
- **证据**：`docs/chat/04-toolcall.md` §4.2（路径缩短 `xg`）、§4.4（`Tq` 剥壳）、§4.5（diffCount `_q`）、§4.6（explore 计数）。
- **当前实现**：`timeline/RowFormat.kt:68-83`（只取第一个候选字段）；`timeline/ToolCallRow.kt:161-174`（无 diff/计数）。
- **建议修法**：按 toolName 分支生成 summary 模型（primary/secondary/trailing），补齐 diff、路径缩短与 explore 计数。

### P1-12 子代理 side-pane 不渲染子会话

- **现象**：点击合并后的「子智能体」行，打开的是一个只显示 `subagentType / status / summaryText` 的说明面板；网页打开的是**子会话完整时间线**（复用同一套行渲染器，只读、无 composer、无返回键，仅关闭 tab）。
- **期望值**：右侧 side-pane 渲染 `childSessionId` 对应的会话（行流 + 工具行外观一致），顶部有 tab 标题与关闭按钮。
- **证据**：`docs/chat/05-misc-rows.md` §3.3（`ozn` → `AQ`，`readOnly:!0`，`Sa = i ? null : <Composer>`）。
- **当前实现**：`ChatScreen.kt:896-950`（`SubagentSidePane` 只渲染摘要文本）。
- **建议修法**：side-pane 接入 `childSessionId` 的会话快照与行渲染；摘要只作为标题/空态。

### P1-13 工具栏模型 / Effort / Mode 控件字号与结构不符

- **现象**：
  - 模型按钮是 `uiXs`（10sp）纯文字 `TextButton`，无 chevron；网页 `text-ui-base`（14px）+ `chevron-down` 14px。
  - Effort 是 10sp 文字 chip；网页是 `brain` 16px + 竖向进度条（`w-1 bg-current/10`）+ 14px 文案、`h-7`。
  - Mode 是 10sp 文字 chip，无图标；网页按 mode 显示 `hand/notepad-text/shield-check/shield-alert` 16px，14px 文案，`yolo` warning 色、`guarded` icon-blue 色。
- **期望值**：三个控件均为 14px、`h-7`、`rounded-lg`，带各自图标；Effort 带进度条；窄容器才折叠成图标。
- **证据**：`docs/chat/06-composer.md` §3.3（模型触发按钮类名）、§3.4（Effort）、§3.5（Mode）。
- **当前实现**：`ChatScreen.kt:729-751`（模型/Effort）、`:718-724`（Mode）、`:843-881`（`ChipMenu` 统一 10sp + chevron）。
- **建议修法**：为三个控件分别实现 14px 触发按钮与图标；Effort 加 brain + 竖向进度条；Mode 按值着色。

### P1-14 运行中未锁定模型切换

- **现象**：任务运行中仍可打开模型菜单并切换；网页对运行中不可用的模型加 `data-model-option-locked`（禁用 + tooltip「运行中锁定提示」）。
- **期望值**：运行中禁用模型切换（或按服务端锁定项逐条禁用），并给提示。
- **证据**：`docs/chat/06-composer.md` §3.3（`data-model-option-locked`、`chat.toolbar.modelSwitch.lockedByRunningTask`）。
- **当前实现**：`ModelSheet.kt:51-59`（签名无 `isRunning`）、`:160-167`（所有项都可点）；`ChatScreen.kt:253-266` 未传运行态。
- **建议修法**：把 `state.isRunning` 传入 `ModelSheet`，运行中禁用全部/锁定项并显示提示。

### P1-15 输入区图片附件不显示 48dp 缩略图

- **现象**：所有待发送附件都渲染成「文件 pill + 通用文件图标」；图片附件没有缩略图。网页图片附件是 48×48、8dp 圆角、`bg-surface`、1px 描边。
- **期望值**：`mime.startsWith("image/")` 时渲染 48dp 缩略图（无图时回退文件图标 16px），删除按钮 14px 右上角。
- **证据**：`docs/chat/06-composer.md` §5.1。
- **当前实现**：`ChatScreen.kt:603-670`（统一文件 pill，`InsertDriveFile` 图标）。
- **建议修法**：按 mime 分支渲染图片缩略图（复用 `RemoteImage`/本地 bytes），文件才走 pill。

### P1-16 Artifact 卡片结构与网页不符

- **现象**：当前是单行 `Row`：文件名与 `TYPE · N bytes` 用两个空格拼在同一行，无边框；网页是卡片内两行（文件名 14px foreground / 副标题 12px subtle），卡片 `rounded-lg + 1px border-card-border + bg-card`，行外 `px-4 py-1`。
- **期望值**：`Row(px-4 py-1) → Column 卡片(border, bg-card, px-3 py-2) → [lucide file 16] + Column{displayName 14 / TYPE · sizeBytes bytes 12}`；图标恒为 file，不按类型切换。
- **证据**：`docs/chat/05-misc-rows.md` §2.1（`ttn`，`3354469`/`3354985`）。
- **当前实现**：`timeline/ArtifactRow.kt:39-72`（单行拼接、无 border、Material `Description`、副标题 mono）。
- **建议修法**：改为两行 + 卡片边框，图标换 lucide file，副标题 12px sans。

### P1-17 Markdown 图片不渲染（只显示占位卡）

- **现象**：助手正文里的 `![alt](url)` 被渲染成「IMG + alt + url」的可点卡片；网页直接渲染图片（单图 `max-w-1/2`、`max-h-90` 360px、12dp 圆角 + 1px 边框，多图 128px 高网格）。
- **期望值**：实际加载并显示图片；加载态有 shimmer/loader；点击进灯箱。
- **证据**：`docs/chat/03-text-reasoning.md` §7.2（`dpt`，`@1665831`）。
- **当前实现**：`markdown/MarkdownText.kt:285-325`（`ImageCard` 占位）。
- **建议修法**：接入图片加载（复用 `media/RemoteImage` 的 loader），按单图/多图两套尺寸渲染，失败再回退占位。

### P1-18 内容列无最大宽度与断点内边距（平板/折叠屏）

- **现象**：LazyColumn 始终 `fillMaxSize` + 左右 16dp，无内容列上限；网页 `<864px` 全宽、`≥864px` `calc(100%-6rem)` 且 `max-w-4xl`(896px)、`≥1280px` `max-w-6xl`(1152px)；`≥448px` 左右内边距 24dp。
- **期望值**：按容器宽度套用 16/24dp 内边距与 896/1152dp 居中上限（输入区同样跟随 dock 内容宽度）。
- **证据**：`docs/chat/01-timeline.md` §8.2–8.4；`docs/chat/06-composer.md` §2.1。
- **当前实现**：`timeline/Timeline.kt:170-175`（`TimelineContentPadding` 固定 16/16/4/20，无 maxWidth）；`ChatScreen.kt:581-587`（composer 固定 16dp）。
- **建议修法**：用 `BoxWithConstraints` 计算内容列宽度与左右 padding，时间线与 composer 共用同一宽度约束。

### P1-19 Mode 菜单缺 Plan 复选框（`planEnabled` 未接线）

- **现象**：Mode 菜单只有 build/guarded/yolo（+兼容值），没有首项 Plan 复选框；`config.planEnabled` 从未读取或写入。
- **期望值**：Mode 下拉首项是 Plan 复选框（切换 `plan`/`plan-off`），其余选项为 build/guarded/yolo，各带图标与描述。
- **证据**：`docs/chat/06-composer.md` §3.5（首项 Plan 复选框 `min-h-13`）、§8.5。
- **当前实现**：`ChatScreen.kt:796-804`（`modeOptions` 明确排除 plan）；`ChatViewModel.kt:871-887`（只切 mode，无 plan 开关）。
- **建议修法**：Mode 菜单首项加 Plan 复选框，新增 `setPlanEnabled`/对应 op，并读 `config.planEnabled`。

---

## 3. P2

### P2-1 turnHeader 文案细节

- **现象/期望**：
  - `failed` 且有耗时：网页落 `已工作 {时长}`，当前落 `已处理`；
  - 缺 `已被新消息中断`（bot-group 话题 `interruptionReason==='newMessage'`）；
  - 时长格式：网页 `1 分 20 秒`（数字与单位有空格、`seconds=max(1,round(ms/1000))`、只输出前两个非零单位），当前 `1分20秒`、<1s 显示 `0.4秒`、`1时0分`；
  - HighSpeed 变体（紫色文案、`节省约 …` + `HighSpeed` 胶囊）未实现；
  - `origin==='backgroundResult'` 的纯标题头（`Vtn`）未实现。
- **证据**：`docs/chat/02-turn-user.md` §1.3（文案表/`OZ`/`DZ`）、§1.4、§1.5。
- **当前实现**：`timeline/TurnHeaderRow.kt:44-50`；`timeline/RowFormat.kt:32-44`。
- **修法**：按状态表补齐分支，时长格式化改为「max(1,round) + 单位空格 + 前两个非零单位」。

### P2-2 turnHeader 视觉细节

- **现象/期望**：chevron 用 Material 填充 `ChevronRight`，网页是 lucide 线性；旋转用默认 spring，网页 `transition-transform` 150ms；底部分隔线当前全不透明 `border`，网页 `border/50`。
- **证据**：`docs/chat/02-turn-user.md` §1.1（`rotate-90`、`opacity-70`、`transition-transform` 150ms）、§1.1 类名 `border-[var(--color-border)]/50`。
- **当前实现**：`timeline/TurnHeaderRow.kt:51-54`（无 spec）、`:72`（Material 图标）、`:79`（`colors.border`）。
- **修法**：换 lucide chevron-right、`tween(150)`、分隔线 `border.copy(alpha=.5f)`。

### P2-3 工具行图标与 kindLabel 细节

- **现象/期望**：
  - 图标实际渲染色：网页 svg 为 `foreground-subtle`，当前统一 `foregroundSubtlest`（更浅）；
  - `WebFetch`/`WebSearch` 等 search 家族：网页用 search 图标 + 「查找」，当前 WebFetch 落到 `Language` 图标且 kindLabel 直接显示 `WebFetch`；
  - 未知工具 kindLabel：网页 `kind` 首字母大写，当前直接显示原始 toolName。
- **证据**：`docs/chat/04-toolcall.md` §2.2（图标表）、§2.3（颜色）、§4.10（默认 kindLabel）。
- **当前实现**：`timeline/ToolIcons.kt:19-55`、`:62-90`；`timeline/ToolCallRow.kt:144-160`。
- **修法**：按 family 表而不是字符串前缀匹配；未知工具回退 `kind`/「工具调用」。

### P2-4 工具展开分区结构

- **现象/期望**：`Parameters` 当前直接显示 `inputText`，网页是 `JSON.stringify(input,null,2)` 的 json 高亮代码块；`Result` 当前显示 `output.text`/`outputPreview`，网页对象要 stringify、字符串按 json 高亮；MCP 短结果网页有 `bg-card` 底色，长结果有「结果」头 + 换行/复制按钮 + 「查看调用详情」折叠。
- **证据**：`docs/chat/04-toolcall.md` §5.2、§5.3、§5.5。
- **当前实现**：`timeline/ToolCallRow.kt:202-240`（Generic）、`:330-381`（MCP）。
- **修法**：统一走带语言的 `CodeBlock`，补齐 MCP 头部工具条与「查看调用详情」。

### P2-5 shell 展开卡片外观

- **现象/期望**：网页是 `rounded-xl border bg-panel px-4 py-3` 卡片；当前只有内容、无卡片底色/边框/内边距；「展开命令/收起」网页带 12px chevron，当前纯文字。
- **证据**：`docs/chat/04-toolcall.md` §5.4（`execute-detail`、`AXt`）。
- **当前实现**：`timeline/ToolCallRow.kt:247-323`。
- **修法**：外包 `bg-panel + border + rounded-xl + 12/16dp`，展开链接加 chevron。

### P2-6 摘要换字动画范围与节流

- **现象/期望**：网页只对 explore/executeGroup/agent 启用，且 800ms 锁 + 250ms 丢弃策略；当前所有工具摘要每次变化都播 300ms，无节流。
- **证据**：`docs/chat/04-toolcall.md` §4.11（`vWe=800`、`yWe=250`、`bWe=2`）。
- **当前实现**：`timeline/RowComponents.kt:117-142`；`timeline/ToolCallRow.kt:165-170`。
- **修法**：加 `animateSummaryContent` 开关与 800ms 节流，其余工具直接换字。

### P2-7 工具分组头与摘要细节

- **现象/期望**：
  - 组头当前有 10/8dp 内边距、`foreground` 文案、常显 chevron；网页组头是同一个 `Kb` 折叠行（无内边距、kindLabel subtlest、chevron 仅 hover）。
  - terminal 组摘要缺「，K 个已停止」；explore 成员判定当前按工具名，网页还包含只读 shell、排除写/改类 shell；组头 running 摘要当前始终是计数，网页运行中显示最后一个子工具动作。
  - 分组扫描会被夹在中间的 subagent 行打断（网页在分组前已跳过被认领的 subagent 行）。
- **证据**：`docs/chat/04-toolcall.md` §6.3–6.6。
- **当前实现**：`timeline/ToolGroupRow.kt:97-131`、`:167-186`；`timeline/Timeline.kt:584-591`。
- **修法**：组头复用 `ToolCallRow` 的折叠行样式；摘要按网页模板补全；分组扫描跳过被认领的 subagent 行。

### P2-8 思考行细节

- **现象/期望**：
  - `durationMs` 缺失/0 时网页是「思考 · 持续了几秒」，当前只显示「思考」；
  - 流式中展开时网页显示「思考 · 持续了 N 秒」，当前始终「正在思考」；
  - 折叠态流式最后一行预览（跑马灯 + 左右 16px 渐隐）未实现；
  - 展开区缺 `ml 8dp`（当前竖线从 x=0 开始）；
  - 时长按整秒 `max(1,ceil)`，当前复用 `formatDuration` 可能出现 `1分20秒`。
- **证据**：`docs/chat/03-text-reasoning.md` §2.2、§3.1、§3.3、§4。
- **当前实现**：`timeline/ReasoningRow.kt:63-69`、`:71-105`、`:107-166`。
- **修法**：按展开/流式状态组合文案，补最后一行预览，展开区加 `padding(start = 8.dp)`。

### P2-9 子代理合并行与退化行细节

- **现象/期望**：合并行用 `subagentType` 作彩色名、`summaryText` 作摘要；网页 kindDetail 取 nickname/agentType，运行中摘要显示最后一个子工具动作，调色板是 sky/cyan/emerald/orange/pink/violet/rose/amber 固定 8 色。退化行网页是原始 `status` 枚举，当前本地化成「运行中/已完成」。
- **证据**：`docs/chat/05-misc-rows.md` §3.1（`vZt`/`fZt`/`eZt`）、§3.2；`docs/chat/04-toolcall.md` §4.8。
- **当前实现**：`timeline/ToolCallRow.kt:80-136`、`:423-435`；`timeline/SubagentRow.kt:19-31`。
- **修法**：补齐 nickname 与运行中动作摘要，使用网页固定调色板；退化行状态按网页口径。

### P2-10 marker 细节

- **现象/期望**：图标当前用 Material（Archive/CallSplit/SwapHoriz/Flag），网页是 lucide archive/git-branch/arrow-right-left/goal；行缺 `px-4 py-2`（当前仅容器 16dp）；`modelChange` 文案缺 provider 前缀（网页 `fromProvider/model → toProvider/model`，默认 glm 才省略）；`forkNotice` 在网页可点击跳转，当前不可点；办公模式的 compact 文案未实现。
- **证据**：`docs/chat/05-misc-rows.md` §5.1–5.3；bundle `3363578`（`fromProvider`/`toProvider`）。
- **当前实现**：`timeline/MarkerRow.kt:53-59`、`:62-97`、`:101-133`。
- **修法**：换 lucide 图标、补行内边距、provider 前缀与点击跳转。

### P2-11 hook 弹层细节

- **现象/期望**：弹层头部当前是 `bg-surface` 且无下边框，网页是 `px-3 py-2` + `border-b`；状态图标用 Material Warning/Block/Cancel，网页是 shield-alert/clock-alert/circle-slash-2/circle-x；`sourceKind` 缺失时当前显示「内部」，网页没有该来源。
- **证据**：`docs/chat/05-misc-rows.md` §4.3–4.4（状态图标表）。
- **当前实现**：`timeline/HookTrailerRow.kt:87-117`、`:187-215`。
- **修法**：头部改 popover + border-b，图标换 lucide，未知来源不渲染。

### P2-12 代码块细节

- **现象/期望**：头部缺垂直内边距（当前 `Row` 只有左右 padding，头部高度 ≈16dp；网页 `px-3 py-2` 上下各 8px）；复制反馈当前 1.5s，网页 2s；网页 header 还有条件显示的「自动换行」按钮，当前没有。
- **证据**：`docs/chat/03-text-reasoning.md` §6.1–6.2。
- **当前实现**：`markdown/CodeBlock.kt:97-145`、`:90-95`。
- **修法**：header 加 `padding(vertical = 8.dp)`，复制复位改 2000ms，补 wrap 按钮（可选）。

### P2-13 正文排版细节

- **现象/期望**：
  - `strong` 网页 `font-medium`，当前 `Bold`；
  - h1/h2/h3 网页 `font-semibold`，当前 `Bold`；h6 网页 14px normal foreground，当前 12px Medium + subtle；
  - 列表：网页 ul `my-3`、`pl-5`(20px)、项间 6px、marker subtlest；ol `list-inside` 序号在文本内；当前项间 4px、marker 4dp 宽、序号在文本外；
  - 引用：网页 2px 左线 + `pl-3` + `text-foreground-subtle`，当前 3dp + `borderHover` + 正文 foreground；
  - 行内代码：网页 12px、6dp 圆角、`px-1.5 py-0.5`、底色 50% alpha，当前仅等宽 + 底色；
  - 链接：网页 `font-medium`、默认无下划线、点状下划线，当前实线下划线；
  - 表格：网页有复制/下载/预览工具条、`th` subtlest、单元格 `px-3 py-2`、min 64/max 448，当前无工具条、样式不同。
- **证据**：`docs/chat/03-text-reasoning.md` §1.4、§7.1、§7.3、§7.4。
- **当前实现**：`markdown/MarkdownText.kt:145-169`、`:171-211`、`:214-248`、`:331-408`、`:485-543`。
- **修法**：按网页 class 表逐项对齐字重/间距/颜色/工具条。

### P2-14 输入区外观细节

- **现象/期望**：聚焦时网页同时改边框色与背景（`bg-input-focused`），当前只改边框；附件 pill 文件名网页 14px medium、类型/大小 12px，当前 12px/10px；上传环网页图片 48px 覆盖层（文件只显示 `NN%` 文本），当前环画在 36dp 图标盒内；队列卡片网页 `-mb-7 pb-7` 压住输入区下沿，当前无重叠；队列项网页有拖拽手柄/立即/编辑/删除，当前只有文本 + 状态。
- **证据**：`docs/chat/06-composer.md` §2.3、§5.2–5.3、§6.1、§6.5。
- **当前实现**：`ChatScreen.kt:590-600`、`:603-670`、`:806-839`、`:476-548`。
- **修法**：补 focus 背景、字号层级、48dp 上传层与队列项操作/重叠。

### P2-15 模型选择器细节

- **现象/期望**：网页是锚定在按钮上方的 `w-48 max-h-72` 下拉、按 provider 分组；当前是全屏底部 sheet（平台适配，见 §5）。sheet 内 provider 头样式、分组项选中态基本一致；但切换模型后未校验 `thought` 是否在新模型 `reasoningLevel.values` 内（网页 `currentValue` 非法时置空）。
- **证据**：`docs/chat/06-composer.md` §3.3、§8.4。
- **当前实现**：`ModelSheet.kt:143-169`；`ChatViewModel.kt:922-949`。
- **修法**：切换后若 `thought` 不在新模型档位内则置空/取第一档。

### P2-16 时间线底部遮罩与回到底部按钮

- **现象/期望**：网页遮罩距底 120px 开始淡出、最后 96px 全透明；当前 40dp 渐变。回到底部按钮网页 28dp（`size-7`）+ 1px outline + `bg-card`，当前 32dp、无边框；位置网页在 composer dock 上方 8dp，当前在时间线内 10dp。
- **证据**：`docs/chat/01-timeline.md` §5.2、§7.4。
- **当前实现**：`timeline/Timeline.kt:288-327`。
- **修法**：遮罩改 96/120dp，按钮 28dp + border + 8dp 间距。

### P2-17 历史预取阈值按行数近似

- **现象/期望**：网页按像素 `距顶 ≤ max(64px, 2×视口高)`；当前 `firstVisibleItemIndex ≤ 16`。长/短行混合时触发时机偏差明显。
- **证据**：`docs/chat/01-timeline.md` §2.1；`docs/CHAT-PAGE-SPEC.md` §2。
- **当前实现**：`timeline/Timeline.kt:131-137`、`:156-164`、`:364`。
- **修法**：用 `listState.layoutInfo` 的 `visibleItemsInfo.first().offset` 与视口高计算像素距离。

### P2-18 默认展开判定与网页条件不完全一致

- **现象/期望**：网页条件是 `isLastTurn && running`，或 `segmentCount===1 && 无可见最终回答 && 有历史行`；当前任意 running 轮次、任意「无最终回答」轮次都默认展开，且不区分多 workSegment。实际历史里通常只影响旧轮次。
- **证据**：`docs/chat/02-turn-user.md` §2.4（`Ann` 公式）、`docs/chat/05-misc-rows.md` §7.2。
- **当前实现**：`timeline/Timeline.kt:453-482`。
- **修法**：补 `isLastTurn` 与 segment 数条件。

### P2-19 中段 artifact / marker 不参与折叠

- **现象/期望**：网页中段 artifact 与无 lane 的 marker 属于 `assistantWorkRows`，折叠时隐藏；尾部 artifact 与 `turnTailBoundary` marker 常显。当前 artifact/marker 永远可见（`isWork=false`）。
- **证据**：`docs/chat/05-misc-rows.md` §7.2。
- **当前实现**：`timeline/Timeline.kt:504-523`；`timeline/RowFormat.kt:19-20`。
- **修法**：按位置（是否在最后一个 assistantText 之前）与 lane 判定是否可折叠。

### P2-20 hook 入口的运行判定

- **现象/期望**：网页用 turn 的 `isRunning` 隐藏入口；当前用「该轮存在流式行」的 `runningTurns`，工具行 running 但无 `state=streaming` 时入口仍显示。
- **证据**：`docs/chat/05-misc-rows.md` §4.2（`!isRunning`）。
- **当前实现**：`timeline/Timeline.kt:97-101`、`:514`。
- **修法**：把 `state.isRunning` 传入时间线并按 turn 运行态过滤。

### P2-21 队列 guide 项未分流

- **现象/期望**：网页 `delivery.admitted==='guide'` 的队列项不进队列卡片，改由时间线 `pendingGuides` 渲染；当前全部按普通队列项显示。
- **证据**：`docs/chat/06-composer.md` §6.6。
- **当前实现**：`ChatScreen.kt:520-546`（无 `admitted` 过滤）。
- **修法**：过滤 guide 项并在时间线尾部渲染 pendingGuides。

### P2-22 fileChanges 卡片缺失

- **现象/期望**：网页 turn 内单独一张可折叠文件变更卡（`+N/−N`、文件数）；当前没有该卡片。
- **证据**：`docs/chat/02-turn-user.md` §3.2。
- **当前实现**：`timeline/Timeline.kt` 无对应分支；`ChatViewModel` 未消费 `fileChanges`。
- **修法**：从 `turnHeader.fileChanges` 渲染变更卡（或明确记录为未实现功能）。

### P2-23 图标体系混用 Material 填充图标

- **现象/期望**：网页对话页工具/标记/弹层图标统一 lucide 线性（24×24、stroke 2）。当前部分已内嵌 lucide（brain/bot/search/pencil/square-terminal/plug/wrench/file/copy/check），但仍混用 Material 填充图标：分组头（Search/Terminal/Edit）、marker（Archive/CallSplit/SwapHoriz/Flag）、artifact（Description）、hook（Anchor/Warning/Block/Cancel）、回到底部（ArrowDownward）、输入区（Add/ArrowUpward/Stop/InsertDriveFile）。
- **证据**：`docs/chat/04-toolcall.md` §2.2；`docs/chat/05-misc-rows.md` §5.2；`docs/chat/06-composer.md` §3.2/§3.6。
- **当前实现**：`timeline/ToolGroupRow.kt:49-51`；`timeline/MarkerRow.kt:53-58`；`timeline/ArtifactRow.kt:48`；`timeline/HookTrailerRow.kt:68-71`、`:187-192`；`ChatScreen.kt:710-716`、`:782-786`、`:761-767`。
- **修法**：把缺的 lucide path 补进 `LucideIcons`，替换 Material 图标。

---

## 4. 已核对一致 / 不算差距

| 项 | 结论 |
| --- | --- |
| 颜色令牌值 | `Color.kt:773-1134` 与 `docs/chat/07` §2.1 一致：`background #FAFAFA/#171717`、`foreground #404040/#E5E5E5`、`brand #00BCFF/#00A6F4`、`inputBorderFocused = brand`、`surface #0A0A0A08/#FFFFFF0D`、`card #FFFFFF/#262626`、`secondary #D4D4D4/#404040`、`warning #D08700/#F0B100`。 |
| 行类型分派 / hook 摘流 | 9 种 kind 均有分支；`hookInvocation` 不进行流、按 turn 尾部 anchor 渲染，只保留 `didExecute=true` 并按 `hookIndex` 排序。 |
| 工具默认折叠 / 分组阈值 | 工具默认全折叠；explore/terminal/changes 默认开/开/关；连续 ≥2 条才成组、单条退化。 |
| 折叠默认开合（主干） | running / completedInterrupted / failed / 无最终回答 → 默认展开且不渲染箭头。 |
| 代码块 30 行折叠 | 网页不存在，但这是**用户明确要求的偏离**（`docs/CHAT-PAGE-SPEC.md` §8），不计为差距。 |
| 思考行自动展开/折回 | 流式展开、轮末折回是用户指定偏离（`docs/CHAT-PAGE-SPEC.md` §3.4），不计为差距。 |
| assistantText 流式光标 | 双方都没有，已一致。 |
| 审批按钮不在工具行内 | 一致（当前也无行内审批按钮）。 |

---

## 5. T2 平台适配（单独标注，不算 bug）

1. **触摸目标**：工具行 21dp、子代理行 21dp、推理行 21dp、分组头 37dp、发送/停止 32dp、Mode/Effort chip ≈23dp、附件删除 28dp。详见 `docs/audit/08-a11y-color.md` §3；移动端可接受视觉尺寸，但建议用 `minimumInteractiveComponentSize`/透明热区补到 48dp。
2. **IME / 键盘**：`imePadding()`（`ChatScreen.kt:584`）+ `ImeAction.Default` 让 Enter 只换行，符合移动端网页口径（`docs/chat/06` §4.3）。
3. **安全区**：网页 composer 本身没有 `env(safe-area-inset-bottom)`；当前也未加 `navigationBarsPadding`，与网页一致；如需手势导航留白属于产品选择。
4. **ripple / 按压反馈**：网页没有 `active:` 反馈；Compose `clickable` 自带 ripple，属于平台适配。
5. **hover-only 控件**：网页工具 chevron、思考 chevron、hook 按钮、用户消息复制/编辑条只在 hover 出现；移动端无 hover，当前常显或省略是正确适配。
6. **弹层形态**：模型选择器网页是锚定下拉，当前用底部 sheet；子代理网页是右侧 side-pane，当前是 90% 宽 Dialog。属于移动端形态适配。
7. **字体缩放**：`ZemoteType` 全部用 `sp`，跟随系统字号，符合 Android 无障碍；网页是固定 px。
8. **滚动条 / scrollbar-gutter**：网页 `scrollbar-gutter: stable` 防抖动；Android 无此概念，不需要复刻。
9. **选择/复制**：网页文本可选择复制、消息 hover 复制；当前未包 `SelectionContainer`，属于移动端交互取舍。

---

## 6. 建议修复顺序

1. **P0-1** 待交互卡片（阻断任务继续）。
2. **P1-3 / P1-4 / P1-5 / P1-6** 轮次结构四件套（顺序、间距、折叠动画、折叠范围）——同一处改动，收益最大。
3. **P1-7 / P1-8** 跟随状态机（流式增长、上滚退出）。
4. **P1-9 / P1-10 / P1-11** 工具行可折叠性、失败标记、摘要结构。
5. **P1-13 / P1-14 / P1-19** 输入区三个选择器 + Plan 复选框 + 运行中锁定。
6. **P1-1 / P1-2 / P1-15** 用户消息纯文本、附件位置、图片缩略图。
7. 其余 P1/P2 按模块批量处理（Artifact、子代理 side-pane、Markdown 排版/图片）。

---

## 7. 证据索引

- 时间线容器/间距/跟随/遮罩：`docs/chat/01-timeline.md` §1–§9。
- turnHeader 文案/开合、userInput 气泡/附件：`docs/chat/02-turn-user.md` §0–§5。
- assistantText 排版、reasoning、代码块、表格/图片/链接：`docs/chat/03-text-reasoning.md` §1–§7。
- 工具行、图标、摘要、展开、分组：`docs/chat/04-toolcall.md` §1–§7。
- artifact / subagent / hook / marker：`docs/chat/05-misc-rows.md` §2–§7。
- 输入区、附件、队列、待交互、模型选择器：`docs/chat/06-composer.md` §2–§9。
- 颜色/尺寸/动画令牌：`docs/chat/07-css-tokens.md` §2、§7。
- 协议与交互参数：`docs/chat/08-behavior-protocol.md` §2、§5、§7。
- 汇总规格：`docs/CHAT-PAGE-SPEC.md` §0–§8。
- 代码：`android/app/src/main/kotlin/app/zemote/ui/chat/`（行号见各条）。
