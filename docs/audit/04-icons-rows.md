# 04 · 图标集 + 行组件解剖（Web 3.15.3 → Android）

> **基线**：`E:\Zcode_android\archive\pre-3.16.1-auth\Reference_web\3.15.3\assets\index-BGa3KiRe.js`（6,592,643 B）
> `index-BD2BL4Cc.css`（455,351 B）、`createLucideIcon-BsjirBgz.js`、各 `*-<hash>.js` 图标 chunk。
> **对照**：`android/app/src/main/kotlin/app/zemote/ui/chat/timeline/` 下 `LucideIcons.kt`、`ToolIcons.kt`、`ToolCallRow.kt`、`TurnHeaderRow.kt`、`ReasoningRow.kt`、`SubagentRow.kt`、`MarkerRow.kt`、`ToolGroupRow.kt`、`UserInputRow.kt`、`HookTrailerRow.kt`、`AssistantTextRow.kt`。
> **偏移口径**：`index-BGa3KiRe.js` 的 **UTF-8 字节偏移**（`grep -aob` / `Buffer.slice` 复核）。`CSS@` 指 `index-BD2BL4Cc.css` 字节偏移。
> **方法**：只读字符串切片；未执行 `Reference_web/` 内任何代码。
> **审计日期**：2026-10-18。本文与 `docs/chat/02-05` 结论一致；个别旧偏移已按当前产物重新核验（见 §6）。

---

## 0. 结论摘要

1. **图标体系是 lucide 0.5xx**：24×24 viewBox、`fill:none`、`stroke:currentColor`、`stroke-width:2`、`stroke-linecap/linejoin:round`（`createLucideIcon-BsjirBgz.js`）。时间线行内图标只传 `size-*`、**不传 `strokeWidth`**，所以 16px 图标实际描边 = 2 × 16/24 ≈ **1.33px**（例外：斜杠菜单里的 bot 传 `strokeWidth:1.5`，`@2777327`，不属于时间线行）。
2. **时间线直接使用 48 个 lucide 图标**；Android `LucideIcons.kt` 只有 **10 个**（brain/search/pencil/square-terminal/bot/plug/wrench/file/copy/check），**缺 38 个**；另有 7 个出现在对话页周边（composer 拖拽、分享页、队列、代码评论），合计**缺 45 个**。
3. **点名 6 个图标全部在产物中找到**（`hand`、`shield-check`、`shield-alert`、`notepad-text`、`grip-vertical`、`trash-2`），无一缺失；但其中 `notepad-text`、`grip-vertical` 含 `rect`/`circle` 元素，不能直接喂 `addPathNodes`。
4. **已有 10 个里 5 个 path 版本漂移**：`brain` 是不同 lucide 版本（网页 8 条 path，Kotlin 9 条）；`plug`、`wrench`、`file` 形状不同；`square-terminal`/`bot`/`copy` 仅编码方式不同（rect vs path），几何一致。
5. **行图标尺寸只有三档**：工具行/思考/回合头/折叠箭头 **16px**；marker/hook/状态/todo 状态 **14px**；MCP 详情内小按钮 **12px**（`size-3`）。没有 20/24px 的行内图标。
6. **工具行没有状态图标、没有 spinner、没有耗时**。运行中唯一动效是 kindLabel 的 4s 渐变扫光（`animated-gradient-text`）；Android 现在用 2s 透明度呼吸近似，且没有做渐变。
7. **图标颜色只有 5 个 token**：`foreground-subtle`（工具图标/marker/hook 状态默认）、`foreground-subtlest`（kindLabel/摘要/思考/chevron）、`warning`（hook blocked/timedOut）、`destructive`（hook failed/工具错误）、`success`（复制成功/todo 完成）。
8. **kindLabel 与图标是两套独立映射**：图标按 family/渲染器选，kindLabel 按 i18n key 选。Android 用工具名子串同时推两者，导致 `TodoWrite→写入/铅笔`、`ReadSessionContext→读取/放大镜`、`TaskOutput→原样/Wrench` 等系统性误判。
9. **turnHeader 行本身是调试占位**（`turn · origin · state`），正常时间线不渲染；用户看到的是历史折叠头 `Ftn`，唯一图标是 chevron-right 16px、`foreground-subtlest`、`opacity-70`。
10. **没有 `finalAnswer` 行类型**。它只是该轮最后一个 `assistantText`，无图标、无独立样式；Android 的 `finalAnswerRowIdByTurn` 是派生逻辑，方向正确。

---

## 1. 行解剖表

> 「证据」列给的是组件定义或关键 class 的字节偏移；同一行多个偏移用 `/` 分隔。
> 「Compose 现状」按 `timeline/` 现有实现描述；「差距」只列会影响观感/语义的项。

| 行 | 结构（关键 class / 层级） | 图标 | 尺寸 | 颜色 | 证据偏移 | Compose 现状 | 差距 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **turnHeader**（可见态 `Ftn`） | `div.flex.w-full.flex-wrap.items-center.justify-between.gap-2.border-b.border-[var(--color-border)]/50.pb-2` → `button.group/history-message.inline-flex.max-w-full.items-center.gap-2.text-ui-base.text-foreground-subtle` → `span.truncate` + chevron | chevron-right（无状态图标） | 16px（`size-4`） | 文字 `foreground-subtle`；chevron `foreground-subtlest` + `opacity-70` | `3396621`（Ftn）/ `3397901`（chevron） | `TurnHeaderRow`：文案 + Material `ChevronRight` 16dp + `HorizontalDivider`；`top=40dp, bottom=8dp` | chevron 形状是 Material；网页无 40dp 上边距（间距由 turn `gap-5` 提供）；网页 divider 是 `pb-2` 后 1px `border`，颜色 50% |
| **userInput** | `div.group/user-row.flex.flex-col.items-end` → 附件区 `div[data-v4-user-input-attachments].mb-2.flex.max-w-xl.flex-col.items-end.gap-2` → 气泡 `div[data-v4-user-input-bubble].flex.max-w-full.flex-col.gap-2.rounded-xl.rounded-tr-xs.border.border-border.bg-surface.px-4.py-3.text-ui-base.text-foreground.@min-[624px]/conversation:max-w-xl` → 正文 `div[data-conversation-selectable].relative.min-w-0.flex-1` → 折叠容器 `div[data-v4-user-input-collapsible-content]`（`max-height:120px`，`[mask-image:linear-gradient(...70%,transparent)]`） | 气泡内**无图标**；附件 pill 有文件类型图标 16px；hover 操作条 copy/edit 14px；折叠按钮 chevron-down/up 16px | 16 / 14 / 16px | 正文 `foreground`；pill 图标 `foreground-subtle`；操作条 `foreground-subtle`；source/status 标签 `foreground-subtlest` | `3365727`（atn）/ `2778688`（bubble）/ `3371772`（attachments） | `UserInputRow`：576dp 上限、12/2/12/12 圆角、surface+border、16/12 内边距；120dp 折叠 + 30% 渐隐 + 圆形按钮 | 附件 pill 用 Material `InsertDriveFile`，网页按文件类型取图标；无 hover 操作条（移动端可接受）；折叠按钮是 Material `ExpandMore` |
| **reasoning 折叠** | `Collapsible.Root.not-prose.flex.flex-col.w-full` → `button.group/reasoning.inline-flex.max-w-full.min-w-0.items-center.gap-2.self-start.text-ui-base.transition-colors.w-full` → brain + `span[data-reasoning-label]` + `·` + 流式末行 + chevron | brain；chevron-right | 16px / 16px | brain `foreground-subtlest`；label `foreground-subtlest`（`font-medium`）；流式末行 `foreground-subtle`；chevron `foreground-subtlest` | `3344779`（trigger）/ `3345068`（label）/ `3345711`（chevron） | `ReasoningRow`：`LucideIcons.Brain` 16dp + 6dp spacer + label + `ExpandChevron` 14dp | spacer 6dp（网页 gap-2=8px）；chevron 14dp 且**常显**（网页 16px、默认 `opacity-0`，hover 才现）；流式用透明度呼吸，网页是 4s 渐变 |
| **reasoning 展开** | `Collapsible.Content.group/collapsible-content.overflow-hidden` → `div.pt-3` → `div.max-h-60.space-y-2.overflow-auto.text-ui-base.text-foreground-subtlest.ml-2.border-border.border-l.pl-3.5` → `div.min-w-0.whitespace-pre-wrap.break-words.text-foreground-subtlest` | 无 | — | `foreground-subtlest` | `3346957` / `3347161` | `ReasoningRow`：`top=12dp`、左竖线 1dp、`start=14dp`、`max=240dp`、上下 24dp 渐隐常显 | 结构一致；渐隐遮罩网页只在溢出时出现；行高网页 21px（1.5），Kotlin 用 `fontSize*1.5` 等价 |
| **toolCall 折叠** | `div.w-full[data-tool-call-id][data-tool-name][data-status]` → `div.group/tool-summary.inline-flex.max-w-full.cursor-pointer.items-center.gap-2.self-start.text-left.text-ui-base` → `span.shrink-0.text-foreground-subtlest.[&_svg]:text-foreground-subtlest`(icon) + `span.tool-summary-kind-label.font-medium.whitespace-nowrap.shrink-0` + `span.min-w-0.shrink-0`(kindDetail) + `span...rounded.border.bg-background-alt.px-1.5.py-0.5.text-ui-xs`(sourceLabel) + `div.tool-summary-content.min-w-0.flex.max-w-full.items-center.gap-2.text-foreground-subtlest` + chevron | family 图标（见 §2） | 16px | 图标 `foreground-subtle`；kindLabel 运行中 `animated-gradient-text`，否则 `foreground-subtlest`；摘要 `foreground-subtlest`；chevron `foreground-subtlest` | `873537`（jWe）/ `872203`（kWe）/ `872896`（AWe）/ `877156`（渐变） | `ToolCallRow`：图标 16dp + 8dp + Medium kindLabel + `·` + 摘要；点击整行 | 图标形状/映射差距大（§2）；chevron 常显（网页 hover 才现）；无 `sourceLabel` 徽章；kindLabel 用呼吸代替渐变 |
| **toolCall 展开** | `Collapsible.Content.text-popover-foreground.outline-none` → `div.pt-2` → 渲染器内容。shell：`div.space-y-3.mb-2.rounded-xl.border.border-border.bg-panel.px-4.py-3` + 命令 `grid.grid-cols-[1ch_minmax(0,1fr)]`（`max-h-[3lh]`→`[10lh]`）+ 输出 `max-h-[5lh]`；通用：`h4` Parameters/Result + `max-h-60` 块；MCP：短文本 `rounded-xl.border.bg-card`，长文本 `max-h-72` | shell 展开链接 chevron-down 12px；MCP「查看调用详情」chevron-right 14px；其余无 | 12 / 14px | `foreground-subtle` / `foreground-subtlest`；错误 `destructive` | `875900`（content）/ `2919460`（shell）/ `882944`（MCP） | `ToolCallRow`：shell `$` + 3/10 行 + 100dp 输出；MCP 160 字符分流 + 288dp；通用 MonoBlock | 展开区左右 10dp 是自加（网页只有 `pt-2`，内容自带 padding）；shell 输出限高 100dp vs 网页 5 行（`5lh`，约 100px，接近）；通用/MCP 的标题硬编码英文，与网页一致 |
| **toolGroup** | 组头 = 合成 toolCall，复用 `jWe`；组内 `div.ml-2.space-y-2.border-border.border-l.pl-3.5`，子行 `showIcon=false` | explore=search；terminal=square-terminal；changes=pencil | 16px | 同 toolCall | `3141314`（`M3t` 缩进类）/ `2946910`/`3053689`/`2932715` | `ToolGroupRow`：`clip(RoundedCornerShape(8dp)).padding(10/8)` + 图标 + label + summary + ExpandMore 14dp；组内 8dp + 1dp + 14dp | 组头多出 8dp 圆角背景内边距（网页无）；chevron 用 ExpandMore 14dp 旋转 180°（网页 chevron-right 16px 旋转 90°，hover 才现）；分组阈值/默认开合与网页一致 |
| **subagent（合并行）** | 复用 agent 的 `Kb`：`[bot] [子智能体] [彩色 mono agent 名] [·] [摘要]`；`canToggle=false` + `summaryAction` → `div role=button`，**无 chevron** | bot | 16px | bot `foreground-subtle`；kindLabel `foreground-subtlest`（运行中渐变）；agent 名 8 色（sky/cyan/emerald/orange/pink/violet/rose/amber 700/300） | `2941743`（bZt）/ `2939959`（vZt）/ `2913779`（DXt 调色板） | `ToolCallRow(merged=true)`：`LucideIcons.Bot` 16dp + 「子智能体」+ agent 名（`agentColor` 用 trajectory/brand/success/warning 8 色）+ `·` + 摘要 | 调色板来源不同（网页是 tailwind 700/300，Android 是主题 token）；其余结构一致 |
| **subagent（退化行）** | `div[data-row-id]` → `div.text-ui-sm.text-[var(--color-foreground-subtle)]`：`{type} · {status} — {summary}` | 无 | — | `foreground-subtle`（12px） | `3387073`（ytn） | `SubagentRow`：uiSm + foregroundSubtle + 自然换行 | 一致 |
| **marker** | `div/button[data-row-kind="timelineMarker"].flex.w-full.items-center.gap-3.px-4.py-2.text-ui-base.text-[var(--color-foreground-subtle)]` → `div.h-px.min-w-8.flex-1.bg-border/50` + `span.inline-flex.min-w-0.shrink.items-center.justify-center.gap-1.5.text-center.leading-5`（icon + label）+ 另一条线 | compact=archive；forkNotice=git-branch；modelChange=arrow-right-left（缺 from 时无）；goalVerify=goal | 14px（`size-3.5`） | 图标/文字 `foreground-subtle`；运行态隐藏图标 + `animated-gradient-text` | `3380615`（gtn）/ `3381889`（_tn） | `MarkerRow`：Material Archive/CallSplit/SwapHoriz/Flag 14dp + 文字；运行态呼吸 | 4 个图标形状全部不同；其余（分隔线 1dp/50%、min 32dp、12dp gap）一致 |
| **hook** | 不是行：回合底部 `Button variant=ghost size=icon-sm`（`size-6`）→ `svg.lucide-anchor.size-3.5` + `span.sr-only 钩子`；点击 Popover `w-80`（320px）→ 标题 `div.shrink-0.border-b.px-3.py-2 > h3` + `ul > li.min-w-0.px-3.py-1.5.text-ui-sm` | anchor；状态：loader-circle / shield-alert / clock-alert / circle-slash-2 / circle-x | 入口 14px；状态 14px | 入口继承 ghost（`foreground-subtle`，hover `foreground`）；blocked/timedOut `warning`；failed `destructive`；其余 `foreground-subtle` | `3351945`（Yen）/ `3350443`（qen）/ `3350993`（Jen） | `HookTrailerRow`：24dp IconButton + Material Anchor 14dp；Popover 320dp；状态图标 12dp + 12dp spinner | 状态图标尺寸 12dp（网页 14px）且全是 Material；`clock-alert` 用 Warning 顶替；无 hover 淡入（移动端可接受） |
| **finalAnswer** | **不是独立行**：该轮最后一个 `assistantText`。`div[data-row-id]` → `div[data-conversation-selectable].w-full.text-ui-base` → markdown 根 | 无 | — | 正文 `foreground` | `3358302`（ltn）/ `3335294`（TZ） | `AssistantTextRow`：MarkdownText | 无图标差异；Android 缺 hover 操作条（copy/retry/fork），不影响图标 |

---

## 2. 工具 kindLabel 与图标映射

### 2.1 分类链路（网页）

```
ToolCallBlock (V3t @3141823)
  → px(toolCall) 归一化 family（@898208；辅助表 src-AL-_ONNp.js@55970）
  → j3t(props) 按 kind/family/工具名选渲染器（@3140010）
  → 渲染器把 icon（size-4）+ kindLabel + kindDetail + 摘要传给 ToolLayout (LWe @875949)
```

### 2.2 family → 渲染器 → 图标 → kindLabel

| family / 场景 | 渲染器 | lucide 图标 | 图标定义偏移 | kindLabel（zh，运行中→结束） | Android `toolIcon()` | Android `toolKindLabel()` | 判定 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `file-read`（Read） | `zXt @2924434` | **search** | `124125` | 读取 / **正在读取** | Search | 读取 / 正在读取 | 图标 ✅ 文案 ✅ |
| `file-write`（Write/Edit/ApplyPatch） | `wXt @2908170` | **pencil** | `118816` | 写入/编辑/删除（正在…/已…） | Pencil | 写入/编辑/删除 | ✅ |
| `search`（Grep/Glob/WebSearch/WebFetch） | `UXt @2926826` | **search** | `124125` | **搜索 / 正在搜索** | Grep/Glob→Search；WebFetch→**Language** | **查找**（错） | WebFetch 图标错；文案错 |
| `explore` | `tZt @2932715` | **search** | `124125` | 查阅 | Search（名字含 explore） | 查阅 | ✅ |
| `shell`（Bash） | `PXt @2919469` | **square-terminal** | `128290` | 终端 / 正在执行 | SquareTerminal | 终端 / 正在执行 | ✅ |
| `agent`（Agent/Task） | `bZt @2941743` | **bot** | `85291` | 子智能体（kindDetail=彩色 agent 名） | Bot | 子智能体 | ✅ |
| MCP 工具 | `YWe @882944` | **plug** | `119755` | MCP | Plug | MCP | ✅（path 版本旧） |
| `todo`（TodoRead/TodoWrite） | `O3t @3136589` | **list-todo** | `107356` | 待办 / 正在更新待办 | **TodoRead→Search、TodoWrite→Pencil** | **读取/写入** | ❌ 子串误判 |
| `ask-user-question`（AskUserQuestion） | `A3t @3138638` | **circle-question-mark** | `90733` | 已询问 / 正在询问 | Wrench（兜底） | 原样工具名 | ❌ |
| `plan-guidance`（EnterPlanMode） | `I4t @3111045` | **notepad-text** | `115484` | 已开启 Plan Mode | Wrench | 原样 | ❌ |
| `session-context`（ReadSessionContext） | `U4t @3113746` | **book-open-text** | `84416` | 已读取上下文 / 正在读取上下文 | **Search**（含 read） | 读取 | ❌ 图标 |
| `message`→RespondToCoordinator | `q4t @3116436` | **reply** | `122620` | 回复 / 正在回复 | Wrench | 原样 | ❌ |
| `message`→SendMessage | `Q4t @3118516` | **send** | `124363` | 消息 / 正在发送消息 | Wrench | 原样 | ❌ |
| `task-control`→TaskOutput | `v3t @3130703` | **file-output** | `97514` | 任务输出 / 正在获取任务输出 | Wrench | 原样 | ❌ |
| `task-control`→TaskStop | `C3t @3133920` | **circle-stop** | `91009` | 停止任务 / 正在停止任务 | Wrench | 原样 | ❌ |
| `skill`（Skill） | `n3t @3121720` | **wand-sparkles** | `134639` | 技能 / 正在运行技能 | **Book**（Material） | 原样 | ❌ |
| `goal`（GoalRead） | `a4t @3097155` | **goal** | `102049` | Goal | **Search**（含 read） | 读取 | ❌ |
| `node-repl`（js*） | `N4t @3105460` | **square-mouse-pointer** | `127945` | Node.js | Wrench | 原样 | ❌ |
| workflow→Create/Amend/Fill | `T0t @3038510` | **workflow** | `135518` | 工作流草稿/正在编写工作流 | Wrench | 原样 | ❌ |
| workflow→saveWorkflow | `Z2t @3092869` | **save** | `123435` | 已保存/正在保存 | Wrench | 原样 | ❌ |
| workflow→listSavedWorkflows | `k2t @3077607` | **library** | `106516` | 已保存的工作流 / 正在列出… | Wrench | 原样 | ❌ |
| workflow→getWorkflowRun | `f2t @3065203` | **gauge** | `100373` | 工作流实例 / 正在获取 | Wrench | 原样 | ❌ |
| workflow→listWorkflowRuns | `j2t @3080696` | **history** | `103733` | 工作流实例 / 正在列出… | Wrench | 原样 | ❌ |
| workflow→evalWorkflowSnippet | `P0t @3048900` | **flask-conical** | `99338` | 片段执行 | **Code**（Material） | 原样 | ❌ |
| workflow→resumeWorkflowRun | `R2t @3085966` | **rotate-ccw** | `123157` | 已恢复 / 正在恢复 | Wrench | 原样 | ❌ |
| workflow→listModels | `w2t @3073225` | **cpu** | `94323` | 可用模型 / 正在列出模型 | Wrench | 原样 | ❌ |
| workflow→escalate | `A0t @3045499` | **message-circle-question-mark** | `110974` | 问题 | Wrench | 原样 | ❌ |
| workflow→resolveWorkflowQuestion | `U2t @3088900` | **message-circle-reply** | `111270` | 已回答/正在回答 | Wrench | 原样 | ❌ |
| workflow→submit_result | `u3t @3124979` | **clipboard-check** | `91482` | 已提交结果/正在提交结果 | Wrench | 原样 | ❌ |
| `switch-mode`（ExitPlanMode） | 计划卡 `f3t @3127066` | **notepad-text** | `115484` | Plan（卡片头） | Wrench | 原样 | ❌ |
| `cuaGroup` / CUA | `Gqt @2817044` | **mouse-pointer-click**（无 app 图标时） | `114553` | 电脑控制 | Wrench | 原样 | ❌ |
| 默认/未知 | `aY @3055522` | **wrench** | `135768` | kind 首字母大写 | Wrench | 原样 | ✅ 图标；文案不同 |
| 组头 explore | 合成 `Explore` | **search** | `124125` | 查阅 | Search | 查阅 | ✅ |
| 组头 terminal | 合成 `ExecuteGroup` | **square-terminal** | `128290` | 终端 | Terminal（Material） | 终端 | 形状差异 |
| 组头 changes | 合成 `ChangesGroup` | **pencil** | `118816` | 更改 | Edit（Material） | 更改 | 形状差异 |
| todo 行内状态 | `D3t @3136331` | completed=**circle-check** / in_progress=**arrow-right** / pending=**circle** | `89366` / `82563` / `91241` | —（只显示图标） | 无 | — | ❌ 未实现 |

### 2.3 尺寸/颜色统一规则

- **工具图标**：全部 `className="size-4 shrink-0 text-foreground-subtle"`（16px）。外层 `span` 的 `text-foreground-subtlest` 被 svg 自身的 `text-foreground-subtle` 覆盖（`kWe @872203`）。
- **kindLabel**：`font-medium whitespace-nowrap shrink-0`；运行中 `animated-gradient-text`，否则 `text-foreground-subtlest`（`LWe @875949` 内 `me`，`877156`）。
- **摘要/分隔符**：`text-foreground-subtlest`（`AWe @872896`）。
- **chevron**：`size-4 text-foreground-subtlest opacity-0`，`group-hover/tool-summary:opacity-100`；展开 `rotate-90 opacity-100`；`duration-200 ease-out`。
- **组内子行**：`showIcon=false`，整行不渲染图标（`kWe` 的 `i?…:null`）。

---

## 3. 缺失图标清单（含 path）

> **口径**：以 `LucideIcons.kt` 现有 10 个为基线；「缺失」= 没有同名 lucide path（即使 Android 用了 Material 近似也算缺失）。
> **结论：时间线 48 个图标全部能在产物中找到 path（0 个“产物未找到”）；Android 缺 38 个，周边再缺 7 个，共 45 个。**
> 元素记号：`path:` = `<path d>`；`circle:cx,cy,r`；`rect:x,y,w,h,rx`。

### 3.1 点名核对（任务列出的 6 个）

| 图标 | 产物是否存在 | 定义偏移 | 元素构成 | 使用位置 |
| --- | --- | --- | --- | --- |
| `hand` | ✅ | `103312` | 4×path | composer 拖拽遮罩 `size-4 text-foreground`（`3681976`） |
| `shield-check` | ✅ | `126005` | 2×path | 分享页披露条 `size-5 text-warning`（`3503968`） |
| `shield-alert` | ✅ | `125740` | 3×path | hook 状态 blocked `size-3.5 text-warning`（`3350645`） |
| `notepad-text` | ✅ | `115484` | 3×path + **1×rect** + 3×path | plan-guidance 行图标 `size-4`（`3110299`）、Plan 卡头（`3128433`） |
| `grip-vertical` | ✅ | `102952` | **6×circle** | 队列拖拽把手 `size-4`（`2635042`） |
| `trash-2` | ✅ | `130975` | 5×path | 代码评论删除 `size-3.5`（`2125165`）等 |

**path 原文**（可直接转 Compose）：

```text
hand:
  path:M18 11V6a2 2 0 0 0-2-2a2 2 0 0 0-2 2
  path:M14 10V4a2 2 0 0 0-2-2a2 2 0 0 0-2 2v2
  path:M10 10.5V6a2 2 0 0 0-2-2a2 2 0 0 0-2 2v8
  path:M18 8a2 2 0 1 1 4 0v6a8 8 0 0 1-8 8h-2c-2.8 0-4.5-.86-5.99-2.34l-3.6-3.6a2 2 0 0 1 2.83-2.82L7 15

shield-check:
  path:M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z
  path:m9 12 2 2 4-4

shield-alert:
  path:M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z
  path:M12 8v4
  path:M12 16h.01

notepad-text:
  path:M8 2v4
  path:M12 2v4
  path:M16 2v4
  rect:4,4,16,18,2
  path:M8 10h6
  path:M8 14h8
  path:M8 18h5

grip-vertical:
  circle:9,12,1
  circle:9,5,1
  circle:9,19,1
  circle:15,12,1
  circle:15,5,1
  circle:15,19,1

trash-2:
  path:M10 11v6
  path:M14 11v6
  path:M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6
  path:M3 6h18
  path:M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2
```

### 3.2 时间线缺失 38 个（P0/P1/P2 见 §4）

```text
[已有但 path 版本漂移]
brain            @85811   8×path（网页版；Kotlin 是 9×path 的另一版本）
  path:M12 18V5
  path:M15 13a4.17 4.17 0 0 1-3-4 4.17 4.17 0 0 1-3 4
  path:M17.598 6.5A3 3 0 1 0 12 5a3 3 0 1 0-5.598 1.5
  path:M17.997 5.125a4 4 0 0 1 2.526 5.77
  path:M18 18a4 4 0 0 0 2-7.464
  path:M19.967 17.483A4 4 0 1 1 12 18a4 4 0 1 1-7.967-.517
  path:M6 18a4 4 0 0 1-2-7.464
  path:M6.003 5.125a4 4 0 0 0-2.526 5.77
plug             @119755  path:M12 22v-5 | path:M15 8V2 | path:M17 8a1 1 0 0 1 1 1v4a4 4 0 0 1-4 4h-4a4 4 0 0 1-4-4V9a1 1 0 0 1 1-1z | path:M9 8V2
wrench           @135768  path:M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.106-3.105c.32-.322.863-.22.983.218a6 6 0 0 1-8.259 7.057l-7.91 7.91a1 1 0 0 1-2.999-3l7.91-7.91a6 6 0 0 1 7.057-8.259c.438.12.54.662.219.984z
file             @98799   path:M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z | path:M14 2v5a1 1 0 0 0 1 1h5
square-terminal  @128290  path:m7 11 2-2-2-2 | path:M11 13h4 | rect:3,3,18,18,2
bot              @85291   path:M12 8V4H8 | rect:4,8,16,12,2 | path:M2 14h2 | path:M20 14h2 | path:M15 13v2 | path:M9 13v2
copy             @93734   rect:8,8,14,14,2 | path:M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2

[缺失]
chevron-right    chunk: chevron-right-d4l8UGZW.js   path:m9 18 6-6-6-6
chevron-down     @88600   path:m6 9 6 6 6-6
chevron-up       @88675   path:m18 15-6-6-6 6
archive          @81575   rect:2,3,20,5,1 | path:M4 8v11a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8 | path:M10 12h4
git-branch       @100836  path:M15 6a9 9 0 0 0-9 9V3 | circle:18,6,3 | circle:6,18,3
arrow-right-left @82291   path:m16 3 4 4-4 4 | path:M20 7H4 | path:m8 21-4-4 4-4 | path:M4 17h16
goal             @102049  path:M12 13V2l8 4-8 4 | path:M20.561 10.222a9 9 0 1 1-12.55-5.29 | path:M8.002 9.997a5 5 0 1 0 8.9 2.02
anchor           @80932   path:M12 6v16 | path:m19 13 2-1a9 9 0 0 1-18 0l2 1 | path:M9 11h6 | circle:12,4,2
clock-alert      @92772   path:M12 6v6l4 2 | path:M20 12v5 | path:M20 21h.01 | path:M21.25 8.2A10 10 0 1 0 16 21.16
circle-slash-2   @90862   circle:12,12,10 | path:M22 2 2 22
circle-x         @91164   circle:12,12,10 | path:m15 9-6 6 | path:m9 9 6 6
circle-check     @89366   circle:12,12,10 | path:m9 12 2 2 4-4
circle           @91241   circle:12,12,10
arrow-right      @82563   path:M5 12h14 | path:m12 5 7 7-7 7
loader-circle    chunk: loader-circle-CyMPv0Nz.js   path:M21 12a9 9 0 1 1-6.219-8.56
list-todo        @107356  path:M13 5h8 | path:M13 12h8 | path:M13 19h8 | path:m3 17 2 2 4-4 | rect:3,4,6,6,1
circle-question-mark @90733  circle:12,12,10 | path:M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3 | path:M12 17h.01
book-open-text   @84416   path:M12 7v14 | path:M16 12h2 | path:M16 8h2 | path:M3 18a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h5a4 4 0 0 1 4 4 4 4 0 0 1 4-4h5a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1h-6a3 3 0 0 0-3 3 3 3 0 0 0-3-3z | path:M6 12h2 | path:M6 8h2
reply            @122620  path:M20 18v-2a4 4 0 0 0-4-4H4 | path:m9 17-5-5 5-5
send             @124363  path:M14.536 21.686a.5.5 0 0 0 .937-.024l6.5-19a.496.496 0 0 0-.635-.635l-19 6.5a.5.5 0 0 0-.024.937l7.93 3.18a2 2 0 0 1 1.112 1.11z | path:m21.854 2.147-10.94 10.939
wand-sparkles    @134639  path:m21.64 3.64-1.28-1.28a1.21 1.21 0 0 0-1.72 0L2.36 18.64a1.21 1.21 0 0 0 0 1.72l1.28 1.28a1.2 1.2 0 0 0 1.72 0L21.64 5.36a1.2 1.2 0 0 0 0-1.72 | path:m14 7 3 3 | path:M5 6v4 | path:M19 14v4 | path:M10 2v2 | path:M7 8H3 | path:M21 16h-4 | path:M11 3H9
file-output      @97514   path:M4.226 20.925A2 2 0 0 0 6 22h12a2 2 0 0 0 2-2V8a2.4 2.4 0 0 0-.706-1.706l-3.588-3.588A2.4 2.4 0 0 0 14 2H6a2 2 0 0 0-2 2v3.127 | path:M14 2v5a1 1 0 0 0 1 1h5 | path:m5 11-3 3 | path:m5 17-3-3h10
circle-stop      @91009   circle:12,12,10 | rect:9,9,6,6,1
workflow         @135518  rect:3,3,8,8,2 | path:M7 11v4a2 2 0 0 0 2 2h4 | rect:13,13,8,8,2
save             @123435  path:M15.2 3a2 2 0 0 1 1.4.6l3.8 3.8a2 2 0 0 1 .6 1.4V19a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z | path:M17 21v-7a1 1 0 0 0-1-1H8a1 1 0 0 0-1 1v7 | path:M7 3v4a1 1 0 0 0 1 1h7
library          @106516  path:m16 6 4 14 | path:M12 6v14 | path:M8 8v12 | path:M4 4v16
gauge            @100373  path:m12 14 4-4 | path:M3.34 19a10 10 0 1 1 17.32 0
history          @103733  path:M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8 | path:M3 3v5h5 | path:M12 7v5l4 2
flask-conical    @99338   path:M14 2v6a2 2 0 0 0 .245.96l5.51 10.08A2 2 0 0 1 18 22H6a2 2 0 0 1-1.755-2.96l5.51-10.08A2 2 0 0 0 10 8V2 | path:M6.453 15h11.094 | path:M8.5 2h7
rotate-ccw       @123157  path:M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8 | path:M3 3v5h5
cpu              @94323   path:M12 20v2 | path:M12 2v2 | path:M17 20v2 | path:M17 2v2 | path:M2 12h2 | path:M2 17h2 | path:M2 7h2 | path:M20 12h2 | path:M20 17h2 | path:M20 7h2 | path:M7 20v2 | path:M7 2v2 | rect:4,4,16,16,2 | rect:8,8,8,8,1
message-circle-question-mark @110974  path:M2.992 16.342a2 2 0 0 1 .094 1.167l-1.065 3.29a1 1 0 0 0 1.236 1.168l3.413-.998a2 2 0 0 1 1.099.092 10 10 0 1 0-4.777-4.719 | path:M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3 | path:M12 17h.01
message-circle-reply @111270  path:M2.992 16.342a2 2 0 0 1 .094 1.167l-1.065 3.29a1 1 0 0 0 1.236 1.168l3.413-.998a2 2 0 0 1 1.099.092 10 10 0 1 0-4.777-4.719 | path:m10 15-3-3 3-3 | path:M7 12h8a2 2 0 0 1 2 2v1
clipboard-check  @91482   rect:8,2,8,4,1 | path:M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2 | path:m9 14 2 2 4-4
square-mouse-pointer @127945  path:M12.034 12.681a.498.498 0 0 1 .647-.647l9 3.5a.5.5 0 0 1-.033.943l-3.444 1.068a1 1 0 0 0-.66.66l-1.067 3.443a.5.5 0 0 1-.943.033z | path:M21 11V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h6
mouse-pointer-click @114553  path:M14 4.1 12 6 | path:m5.1 8-2.9-.8 | path:m6 12-1.9 2 | path:M7.2 2.2 8 5.1 | path:M9.037 9.69a.498.498 0 0 1 .653-.653l11 4.5a.5.5 0 0 1-.074.949l-4.349 1.041a1 1 0 0 0-.74.739l-1.04 4.35a.5.5 0 0 1-.95.074z
```

### 3.3 周边缺失 7 个（非时间线行）

```text
hand            @103312   （见 §3.1）
shield-check    @126005   （见 §3.1）
grip-vertical   @102952   （见 §3.1）
trash-2         @130975   （见 §3.1）
x               chunk: x-nPqwV043.js     path:M18 6 6 18 | path:m6 6 12 12
info            chunk: info-B4elOYue.js  circle:12,12,10 | path:M12 16v-4 | path:M12 8h.01
triangle-alert  chunk: triangle-alert-CLvTmKyJ.js  path:m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3 | path:M12 9v4 | path:M12 17h.01
```

### 3.4 Compose 落地注意

- `addPathNodes(d)` **只能吃 path**。`rect`/`circle`/`line` 必须先转 path，或在 `ImageVector.Builder` 里用 `PathBuilder` 画。
- 圆：`M(cx-r),cy a r,r 0 1,0 (2r),0 a r,r 0 1,0 -(2r),0`。
- 圆角矩形：`M(x+rx),y h(w-2rx) a rx,rx 0 0,1 rx,rx v(h-2ry) a rx,rx 0 0,1 -rx,rx h-(w-2rx) a rx,rx 0 0,1 -rx,-rx v-(h-2ry) a rx,rx 0 0,1 rx,-rx z`（`ry` 缺省 = `rx`）。
- 描边不要写死 2f 后按 24dp 渲染再缩到 16dp——这与网页等价（网页也是 viewBox 24 + width 16）。若直接按 16dp viewport 建模，描边要改成 2×16/24≈1.33。
- `Icons.Default.*` 是 Material 填充风格，和 lucide 细线差异明显；本次审计把「用 Material 顶替」一律计为缺失。

---

## 4. 建议补齐顺序

**P0（一眼可辨、影响 90% 对话）**

1. `chevron-right` / `chevron-down` / `chevron-up`：替换 `TurnHeaderRow`、`ReasoningRow`、`ExpandChevron`、`ToolGroupRow`、`UserInputRow` 的 Material 箭头；顺带把 reasoning/工具行 chevron 改成「默认透明、hover/展开可见」的语义（移动端可常显但透明度降到 0.4）。
2. `brain` 换成网页版 8-path（当前 Kotlin 是另一版本，形状不同）。
3. marker 四件套：`archive`、`git-branch`、`arrow-right-left`、`goal`。
4. hook 状态五件套：`anchor`、`loader-circle`、`shield-alert`、`clock-alert`、`circle-slash-2`、`circle-x`（anchor 可保留 Material 近似，其余必须换）。
5. 工具家族主力：`search`（已有，保留）、`pencil`（已有）、`square-terminal`（已有）、`bot`（已有）、`plug`（建议换新 path）、`wrench`（建议换新 path）、`file`（建议换新 path）。

**P1（工具行正确性）**

6. 修 `toolIcon()`/`toolKindLabel()` 的判定顺序与覆盖：`todo*` 必须先于 `read/write`；`ReadSessionContext` 走 `book-open-text`；`GoalRead` 走 `goal`；`WebFetch/WebSearch` 走 `search`（不是 Language）；`TaskOutput/TaskStop`、`SendMessage/RespondToCoordinator`、`AskUserQuestion`、`EnterPlanMode`、`Skill`、`js*` 各自独立分支。
7. 补 `list-todo`、`circle-question-mark`、`book-open-text`、`reply`、`send`、`file-output`、`circle-stop`、`wand-sparkles`、`notepad-text`、`square-mouse-pointer`。
8. todo 行内状态：`circle-check`（success）、`arrow-right`（foreground）、`circle`（subtlest），14px。

**P2（workflow / 长尾）**

9. `workflow`、`save`、`library`、`gauge`、`history`、`flask-conical`、`rotate-ccw`、`cpu`、`message-circle-question-mark`、`message-circle-reply`、`clipboard-check`。
10. `mouse-pointer-click`（CUA）、`circle-check`/`circle`/`arrow-right` 之外的空态图标。
11. 周边：`hand`（composer 拖拽）、`grip-vertical`（队列）、`trash-2`（代码评论）、`shield-check`（分享披露）、`x`、`info`、`triangle-alert`。

**同时建议修的行的差距（与图标解耦）**

- `TurnHeaderRow` 去掉 40dp 上边距，改为依赖时间线 `gap-5`；divider 用 `border/50`。
- `ReasoningRow` spacer 6dp→8dp；chevron 16dp；展开渐隐只在溢出时出现。
- `ToolGroupRow` 去掉组头 8dp 圆角背景与 10/8 内边距（网页组头就是普通工具行）。
- `ToolCallRow` 运行中 kindLabel 改为真渐变扫光（4s、300% background-size），不要整行呼吸。
- `HookTrailerRow` 状态图标 12dp→14dp。
- `ToolCallRow` 的 `val duration` 是死代码，可删（网页本来就不显示耗时）。

---

## 5. 关键 DOM / class 速查

```text
turnHeader（可见）
div.flex.w-full.flex-wrap.items-center.justify-between.gap-2.border-b
    .border-[var(--color-border)]/50.pb-2
  button.group/history-message.inline-flex.max-w-full.items-center.gap-2
         .text-left.text-ui-base.text-foreground-subtle
    span.truncate
    svg.lucide-chevron-right.size-4.shrink-0
        .text-[var(--color-foreground-subtlest)].opacity-70
        .transition-transform.rotate-90|rotate-0

userInput
div.group/user-row.flex.flex-col.items-end
  div[data-v4-user-input-attachments].mb-2.flex.max-w-xl.flex-col.items-end.gap-2
  div[data-v4-user-input-bubble].flex.max-w-full.flex-col.gap-2.rounded-xl.rounded-tr-xs
      .border.border-border.bg-surface.px-4.py-3.text-ui-base.text-foreground
      .@min-[624px]/conversation:max-w-xl
    div[data-conversation-selectable].relative.min-w-0.flex-1
      div[data-v4-user-input-collapsible-content]
          .min-w-0.overflow-hidden.whitespace-pre-wrap.break-words
          .transition-[max-height].duration-300.ease-out
  div.mt-1.flex.items-center.gap-1.opacity-0.transition-opacity
      .group-hover/user-row:opacity-100.focus-within:opacity-100
    [copy 14px] [pencil 14px]

reasoning
button.group/reasoning.inline-flex.max-w-full.min-w-0.items-center.gap-2
       .self-start.text-ui-base.transition-colors.w-full
  svg.lucide-brain.size-4.shrink-0.text-foreground-subtlest
  span[data-reasoning-label].shrink-0.whitespace-nowrap
  span ·
  span[data-reasoning-streaming-line].min-w-0.flex-1.overflow-hidden
       .whitespace-nowrap.text-foreground-subtle
  svg.lucide-chevron-right.size-4.shrink-0.text-foreground-subtlest
      .rotate-0.opacity-0.group-hover/reasoning:opacity-100
Collapsible.Content.group/collapsible-content.overflow-hidden
  div.pt-3
    div.max-h-60.space-y-2.overflow-auto.text-ui-base.text-foreground-subtlest
        .ml-2.border-border.border-l.pl-3.5
      div.min-w-0.whitespace-pre-wrap.break-words.text-foreground-subtlest

toolCall
div.group/tool-summary.inline-flex.max-w-full.cursor-pointer.items-center.gap-2
    .self-start.text-left.text-ui-base.transition-colors
  span.shrink-0.text-foreground-subtlest.[&_svg]:text-foreground-subtlest
    svg.lucide-*.size-4.shrink-0.text-foreground-subtle
  span.tool-summary-kind-label.font-medium.whitespace-nowrap.shrink-0
       [animated-gradient-text | text-foreground-subtlest]
  span.min-w-0.shrink-0                       ← kindDetail
  span.shrink-0.rounded.border.border-border.bg-background-alt
       .px-1.5.py-0.5.text-ui-xs.leading-none.text-foreground-subtlest
  div.tool-summary-content.min-w-0.flex.max-w-full.items-center.gap-2
      .text-foreground-subtlest
    span.shrink-0 ·                            ← separator
    …primary / secondary / diff / status…
  svg.lucide-chevron-right.size-4.text-foreground-subtlest.opacity-0
      .transition-transform.transition-opacity.duration-200.ease-out
      .group-hover/tool-summary:opacity-100
      [open: rotate-90 opacity-100]
Collapsible.Content.text-popover-foreground.outline-none
  div.pt-2

toolGroup
div.ml-2.space-y-2.border-border.border-l.pl-3.5
  [child ToolCallRow showIcon=false] ×N

subagent（合并）
[bot 16] [子智能体] [彩色 mono agent 名 max-w-36 truncate] [·] [摘要 truncate]
div role=button tabindex=0（summaryAction，无 chevron）

subagent（退化）
div.text-ui-sm.text-[var(--color-foreground-subtle)]
    {subagentType} · {status} — {summaryText}

marker
div/button[data-row-kind=timelineMarker].flex.w-full.items-center.gap-3.px-4.py-2
    .text-ui-base.text-[var(--color-foreground-subtle)]
  div.h-px.min-w-8.flex-1.bg-border/50
  span.inline-flex.min-w-0.shrink.items-center.justify-center.gap-1.5
       .text-center.leading-5
    svg.size-3.5.shrink-0.text-[var(--color-foreground-subtle)]   ← running 时隐藏
    span.min-w-0.break-words[.animated-gradient-text.font-medium]
  div.h-px.min-w-8.flex-1.bg-border/50

hook
Button.variant=ghost.size=icon-sm（size-6）
  svg.lucide-anchor.size-3.5
  span.sr-only 钩子
Popover.w-80.max-w-[calc(100vw-2rem)].gap-0.overflow-hidden.p-0
  div.shrink-0.border-b.border-border.px-3.py-2 > h3.text-ui-base.font-medium.text-foreground
  ul.min-h-0.overflow-y-auto.py-0.5
    li.min-w-0.px-3.py-1.5.text-ui-sm
      div.flex.min-w-0.items-center.gap-2
        span.min-w-0.flex-1.break-words.font-mono.text-foreground
        div.flex.min-w-0.flex-wrap.items-center.justify-end.gap-2.text-ui-xs
            .text-foreground-subtle
          span[source] span.text-foreground-subtlest[duration]
          span.inline-flex.min-w-0.items-center.gap-1
            svg.size-3.5 + statusLabel

finalAnswer = 该轮最后一个 assistantText
div[data-conversation-selectable].w-full.text-ui-base
  Markdown 根（space-y-4 …）
```

---

## 6. 未验证 / 边界

1. **旧文档偏移偏差**：`docs/chat/02-05` 里部分偏移（如 `Ftn@3377560`、`atn@3346698`）与当前产物的 `grep -aob` 结果（`3396621`、`3365727`）相差 1–20KB；本文以当前产物复核值为准。差异原因未查明（可能是不同 build 或字符/字节口径混用）。
2. **`terminal`（`@130003`）**：确认存在，但未在时间线行内找到使用点（组头用 `square-terminal`）；可能是代码块/其它面板使用，未逐处穷举。
3. **CUA 组内部事件列表**的逐行图标未取证（只确认组头 `mouse-pointer-click` 与 `animated-gradient-text`）。
4. **插件 UI / Gen UI 行**（`M0e`/`j0e`）走另一套渲染，未纳入。
5. **`switch-mode` 计划卡**只确认卡头 `notepad-text` 16px；卡片正文图标未穷举。
6. **图标 chunk 的 hash 会随构建变化**：`chevron-right-d4l8UGZW.js`、`loader-circle-CyMPv0Nz.js`、`x-nPqwV043.js`、`info-B4elOYue.js`、`triangle-alert-CLvTmKyJ.js` 是当前 3.15.3 产物名；升级版本后需按 `To('name', …)` 重新定位。
7. **“hover 才显示 chevron”** 是桌面交互；Android 无 hover，需产品决策（建议常显 + 低透明度），本文不把它算作错误。
8. 所有「不存在」结论基于字符串/定义检索；若运行时存在未打包分支，需真机复核。
