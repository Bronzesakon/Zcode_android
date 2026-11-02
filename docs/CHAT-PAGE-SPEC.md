# 对话详情页复刻规格（Web 3.15.3 → Compose）

> 来源：`docs/chat/01…08` 八份取证报告（每条结论在报告里都带 `文件:字节偏移`）。
> 本文只写「实现时照抄什么」，不重复取证过程。数值单位：Web px ≈ Compose dp。

## 0. 已落地 / 未落地

| 项 | 状态 |
| --- | --- |
| 9 种行渲染拆分为 `ui/chat/timeline/*.kt` | ✅ |
| 时间线跟随、静默历史、回到底部 | ✅ |
| 输入区三态边框、附件 chips、Mode/Effort 独立选择器 | ✅ |
| 协议状态从 `rowsUpdated` 快照 + `state.updated` 归约 | ✅ |
| 本地行缓存 | ✅ |
| `hookInvocation` 归入轮次底部（网页不在行流） | ✅ 轮次末尾 anchor 按钮 + 弹层 |
| 工具分组（explore/terminal/changes） | ✅ |
| 子代理右侧 side-pane（网页无返回键） | ✅ |
| subagent 合并进 Agent 工具摘要行 | ✅ 按 `parentToolCallId` 配对 |
| 工具默认折叠 / 摘要 300ms 换字 / chevron 200ms | ✅ |
| shell（3/10 行命令、5 行跟随输出）/ MCP（288dp 结果）展开 | ✅ |
| 用户气泡 120dp 折叠 + 渐隐 + 展开按钮 | ✅ |
| 思考展开上下 24dp 渐隐、左线全高 | ✅ |
| 轮次默认开合（运行/失败/中断/无最终回答不可折叠） | ✅ |
| 队列暂停横幅 + 继续 | ✅ |
| 附件上传进度环 + 7px 百分比 | ✅ `attachmentProgress` 事件 |
| 顶栏合并为单行（返回 + 标题 + 路径 + 更多） | ✅ |
| 工具行 lucide 图标 + kindLabel + 摘要结构 | ✅ |
| Markdown 代码块（`bg-card` + 文件/语言/复制头） | ✅ |
| 对话内 provider/model 二级选择器 | ✅ `modelSelectionView` + `switchModelConfig` |
| 颜色令牌重生成（`colors.json` 被 theme-zai 污染） | ✅ 按默认主题重新生成 |

## 1. 时间线容器与 turn

| 项 | 值 |
| --- | --- |
| turn 之间 | `gap 20dp`；非首轮 `padding-top 56dp`，末轮 `padding-bottom 20dp` |
| 行组内 | `gap 16dp` |
| 左右内边距 | 16dp（容器 ≥448dp 时 24dp） |
| 内容列最大宽 | 896dp（≥864dp）/ 1152dp（≥1280dp） |
| 顶部 | 首轮 `pt 0`；**顶栏单行**：返回 + 标题 + 工作区路径 + 更多（48dp + 状态栏） |
| 底部 | 20dp；底部渐隐遮罩只在非贴底时出现 |
| 滚动容器 | 无 padding；`scrollbar-gutter: stable` |

Compose 对应：`LazyColumn(contentPadding = 16/16/4/20, verticalArrangement = spacedBy(16.dp))`，
`TurnHeaderRow` 顶部 40dp（40+16=56）。

## 2. 跟随与静默历史

- **贴底判据**：距底 ≤ 48px → following；任何上滚立即退出（1px 即可）。
- **新行**：只有 following 时才自动滚底；不显示「N 条新消息」计数（网页没有）。
- **回到底部按钮**：`arrow-down` 16px，圆形 28px，无显隐动画；非贴底且行数 > 0 时显示。
- **静默历史触发**：距顶 ≤ `max(64px, 2×视口高)`，并发由 `loadingOlder` 去重，无 spinner、无占位行。
- **游标**：`beforeRowId = 当前窗口最老一行`（`rows.first().rowId`），不是快照投影头。
- **合并**：只保留 `rowId < 当前 window[0].rowId` 的行，拼到队首；`rowId` 为 LazyColumn key，prepend 不跳。
- **CAS**：响应 `atLogEpoch` 必须等于当前 `logEpoch`，且当前首行未变，否则整批丢弃。
- **首轮不完整**：窗口首行的 `turnId` 在窗口内找不到 `turnHeader` → 静默补拉一次。

## 3. 行

### 3.1 turnHeader
- 文案：`工作中 {时长}` / `已工作 {时长}` / `已处理`（完成但无耗时）/ `已停止` / `已被新消息中断`；失败落到 `已工作`/`已处理`，**无失败专用文案**。
- 无状态图标、无 spinner；chevron-right 16dp（`foreground-subtlest` 70%），展开旋转 90°；header 底部 1px 边框 + `pb 8dp`。
- 折叠只隐藏「最终回答之前的历史行」；用户气泡、header、最终回答、变更卡常显。
- **默认开合**：运行中、`completedInterrupted`/`failed`、以及「有历史行但无最终回答」的轮次默认展开且不可折叠（不渲染箭头）；普通完成默认折叠、可点击。
- 折叠动画 300ms ease-in-out；状态仅内存，不持久化。

### 3.2 userInput
- 右对齐；`max-width 576dp`（容器 ≥624dp）；圆角 12/2/12/12；`bg-surface` + 1px border。
- 内边距 16×12dp；正文 14px/1.5。
- 图片缩略图 80×80dp、圆角 12dp、间距 8dp；文件 pill 高 32dp、全圆角、无边框。
- 超长气泡折叠到 120dp + 底部 30% 渐隐（`scrollHeight > 121px` 才折叠）；圆形展开/收起按钮浮在底部，展开后移到正文下方；展开状态不持久化。
- 不显示时间戳；hover 操作条只有复制/编辑（移动端不做 hover）。

### 3.3 assistantText
- 无气泡/边框/背景，纯文档流；正文 14px、行高 1.75、字距 0.025em。
- **代码块**：`rounded-xl` 12dp、1px `border`、`bg-card`（暗色 `#262626`）；头部 `文件图标 16dp + 小写语言名（14px mono） + 复制图标 14dp`；正文 **12px / 20px** 等宽、横向滚动；无深色方块边框。
- **没有流式光标/闪烁/渐变**；流式进度只体现在思考/工具行。
- 段距 16dp；标题 24/16px；列表缩进 12/20px；引用 2px 左线。

### 3.4 reasoning
- 折叠：brain 图标 16dp + `思考 · 持续了 N 秒`（14px，`foreground-subtlest`）+ chevron；无背景/边框/内边距。
- 展开：`max-height 240dp` 滚动；`ml 8dp` + 1px 左线 + `pl 14dp`；14/21；**纯文本 pre-wrap，不是 Markdown**；上下各 24dp 渐隐遮罩。
- 流式：「正在思考」渐变文字 4s（Compose 用透明度呼吸近似）。
- 自动展开/折回：按用户确认的版本（流式展开、该轮结束折回），与网页「默认折叠」不同，属于**用户指定偏离**。

### 3.5 toolCall
- 折叠行：`[lucide 图标 16dp] [kindLabel 14px medium] · [primary/secondary 14px]`，行内 gap 8dp；图标与文字统一 `foreground-subtlest`；**无背景/无边框/无 chevron**（网页 chevron 仅 hover 显示，移动端没有 hover）。
- kindLabel：终端=「终端/正在执行」、读取=「读取/正在读取」、查找、写入/编辑/删除（运行中加「正在」）、查阅、MCP、子智能体。
- 图标：lucide 线性图标（search / pencil / square-terminal / plug / bot / wrench），不是 Material 填充图标。
- 摘要：终端显示命令、读取显示路径、查找显示 query；font-sans 单行省略。
- **默认全部折叠**（无「运行中自动展开」）；展开标签 `Parameters` / `Result` / `Error`（硬编码英文），240dp 滚动。
- 摘要：路径去 workspace 前缀否则 basename；命令只省略不截断；write 显示 `+N/−N`；explore 显示 `N searches, N files`。
- 展开：`Parameters` / `Result|Error`，均 240dp 滚动；shell 命令 3/10 行、输出 5 行跟随；MCP 结果 288dp。
- 分组：explore/terminal 默认开、changes 默认关；**连续且 ≥2 条**才成组。
- 运行中唯一动效：kindLabel 渐变扫光 4s（Compose 用透明度呼吸）。
- 审批按钮不在行内，在独立权限卡片。

### 3.6 artifact
- 静态卡片：`rounded-lg`、`bg-card`、图标恒为文件。
- 副标题：`TYPE · {sizeBytes} bytes`（原始字节，不换算）。
- 主时间线不可点击。

### 3.7 subagent
- 可与 Agent 工具调用合并进该工具摘要行（bot 图标 + 「子智能体」+ 彩色 mono agent 名 + `·` + 描述）；配对键 `parentToolCallId` → `toolCallId`。合并行不可折叠，整行点击打开 side-pane。
- 无法配对的退化行：纯文本 `{subagentType} · {status} — {summaryText}`，无图标/点击。
- 点击打开右侧 side-pane（无返回键、无 composer）；失败态在折叠行无标记。
- 我们当前：配对成功即合并，退化行独立渲染 + 右侧 side-pane（屏宽 90%、上限 420dp，点遮罩关闭）。

### 3.8 hookInvocation
- 网页**不在行流**：归约到 `turn.hookInvocations`，在回合底部显示 anchor 图标按钮（hover 淡入），点击弹 Popover。
- 我们当前：已从行流摘除，回合末尾 anchor 按钮 + 弹层列表（只列 `didExecute=true`，按 `hookIndex` 排序）。

### 3.9 timelineMarker
- 居中标签：左右 `flex-1` 1px `border/50` 分隔线；14px/20px `foreground-subtle`。
- 只实现 4 种：`compact`（archive）、`forkNotice`（git-branch）、`modelChange`（arrow-right-left）、`goalVerify`（goal）；其余丢弃。

## 4. 输入区

| 项 | 值 |
| --- | --- |
| 外壳 | 圆角 16dp、1px 边框（idle / hover / focus 三态）、内边距 12dp |
| 输入 | 14/20（移动安全 16/24）；`min-h 40dp`、`max-h 160dp`（约 8 行） |
| 工具栏 | 同一行内：左 `+`、Mode；右侧模型、Effort、发送/停止（不再有第二行工具栏） |
| 发送/停止 | `arrow-up` / `square`，无切换动画 |
| 附件 chip | 图片 48dp；文件 pill 高 48dp、图标 36dp、删除 14dp |
| 上传进度 | 48dp 圆环 + 7px 百分比（`attachmentProgress` 事件驱动，逐附件显示） |
| 队列 | 输入区上方、压入下沿；无折叠、无 autoDrain 开关，只有暂停横幅 + 「继续」（已实现，`setAutoDrain`） |
| 待交互 | 内联在输入区上方；权限卡无倒计时；用户输入卡最后 60s 显示倒计时 |
| 模型选择器 | 按 provider 分组的二级菜单；当前值不在目录时只显示占位，不合成菜单项；选中调用 `switchModelConfig` |

### 4.1 模型目录与「对话内选择」

- **可以**在对话内切换 provider/model：模型按钮打开按 provider 分组的菜单，选中项调用
  `switchModelConfig {provider, model, thought}`；当前值来自会话 `config.provider/model`。
- 目录来源是桌面 `model-selection.getView` 通道（`{revision, providers:[{providerId,
  providerName, models:[{modelId, config}]}]}`）。我们在 Rust 侧新增了 conversation op
  `modelSelectionView`（内部转 `model-selection.getView`），Kotlin 侧解析同一份视图。
- Effort 档位来自当前模型的 `config.optionSpecs.reasoningLevel.values`，不是
  `config.thoughtLevels`；目录缺失时才回退到 `thoughtLevels`。
- **设置页**只管理 provider/model 列表（启用/禁用、增删个人 provider/model、凭据），
  不负责「当前会话选哪个模型」；两者不要混在一个入口里。

## 5. 动画

| 场景 | 时长 / 曲线 |
| --- | --- |
| 流式文字淡入 | 900ms `cubic-bezier(.16,1,.3,1)` |
| 渐变思考文字 | 4000ms linear infinite |
| 折叠容器 | 200/300ms |
| 工具块 / 预览卡淡入 | 900ms |
| 工具摘要换字 | 300ms 上下滑 + 淡入淡出（explore / 分组 / agent） |
| 工具 chevron | 200ms 旋转 |
| 内容列宽度过渡 | 150ms ease-out |
| 进度条 | 1200ms |
| 行进入/退出 | **无动画** |
| 回到底部按钮 | **无显隐动画** |

## 6. 协议实现要点

- `rowsUpdated.frame.payload.snapshot` 是**完整对话快照**（含 config/queue/pendingInteractions/control）。
- `state.updated.patch` 是顶层浅替换；`queue`/`config`/`control` 必须整包给。
- `isRunning = control.phase ∈ {running, prewarming}`。
- `row.removed` 删除 `rowId >= fromRowId` 的全部行；`row.upserted` 找不到目标行则忽略。
- `row.delta` 只有 `text` / `inputText` / `output.text` / `summaryText`。
- `resolveInteraction` 参数：`{sessionId, interactionId, optionId?, freeText?, action?, content?}`。
- **对话层快照**：核心 `snapshot()` 只有 relay/桥状态；`conversations` / `sessionsIndex`
  属于对话层，用 `nativeQuery {"query":"conversation","session":…,"key":…}` 取
  （`docs/FFI.md` §9.1，`abi = 2`）。

## 7. 颜色偏差（已修正）

`design/tokens/colors.json` 的 light/dark 字段混入了 `theme-zai-*` 皮肤。已按默认主题
（`:root,:host` + `.dark`，排除 zai，`var()` 递归解析）重新生成 `Color.kt`：

| 令牌 | 旧生成值（zai） | 现生成值（默认主题） |
| --- | --- | --- |
| background | `#F8F8F8` / `#161616` | `#FAFAFA` / `#171717` |
| foreground | `#262626` / `#D4D4D4` | `#404040` / `#E5E5E5` |
| brand | `#000000` / `#FFFFFF` | `#00BCFF` / `#00A6F4` |
| input-border-focused | `#000000` / `#FFFFFF` | `#00BCFF` / `#00A6F4` |

例外：27 个令牌在默认亮色主题里没有定义（只存在于 `.dark` 与 zai 皮肤），亮色值保留
zai-light 兜底；暗色值仍按默认 `.dark` 解析。`colors.json` 本身未改（它是快照抽取物），
重新生成时按同一口径解析。

## 8. 已知剩余差异

无。`sessionsIndex.list` 预览已通过 `conversation` 查询（`abi = 2`）接入，对话页元素与
网页端一致；仅保留用户明确要求的 30 行代码折叠。

> 说明：`流式文字 900ms 淡入` 不适用于主对话文本——报告 03 已确认 `assistantText` 的
> `animated/isAnimating` 均传 `false`、无 caret，该标记只出现在 preview card 上。

## 9. 2026-10-18 审计报告索引

`docs/audit/` 下八份报告把网页产物重新挖了一遍，并给出与当前 Compose 的逐项差距：

| 报告 | 内容 |
| --- | --- |
| `01-typography.md` | 字号/行高/字距/字体栈；Markdown 标题与行内代码的例外 |
| `02-layout-tokens.md` | 间距/圆角/边框/阴影/容器宽度；裸值清单 |
| `03-motion.md` | 时长/曲线/触发点；折叠 300ms 等固定配方 |
| `04-icons-rows.md` | 行解剖、lucide 图标集、kindLabel 映射、缺失图标 |
| `05-composer.md` | 输入卡/工具栏/三选择器/附件/队列/待交互 |
| `06-non-chat.md` | 网页实际非对话页与 Android 原生适配建议 |
| `07-impl-gaps.md` | P0/P1/P2 差距清单（file:line） |
| `08-a11y-color.md` | 对比度、触摸目标、语义/读屏 |

对话保活、缓存与历史预取的契约见 `docs/CONVERSATION-LIFECYCLE.md`。
