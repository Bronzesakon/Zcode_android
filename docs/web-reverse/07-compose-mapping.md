# 07 · Web 3.17.1 → Compose 落地映射索引

> 用途：把 `docs/web-reverse/01–06` 的逆向结论接到具体 Kotlin 文件上，作为逐页对齐的施工索引。
> 每一行的「报告」列给出权威参数（尺寸/颜色/动效），本文不重复数值，只记「去哪查、改哪个文件、当前状态」。
> 状态口径：`已对齐` = 按报告参数实现并真机看过；`部分` = 实现了但已知偏差；`未动` = 还是旧基线。

## 0. 主题与令牌（全局）

| Web 令牌 | 报告 | Compose 落点 | 状态 |
| --- | --- | --- | --- |
| 四主题 436 token / 默认 zai-dark | `01-tokens-global.md` §3–§5、`artifacts/tokens.reference.css` | `ui/theme/Color.kt`、`ZaiColors.kt`、`Theme.kt`、`ThemeController.kt` | 部分（zai-dark 主链路已用，浅色/其他主题未逐 token 校对） |
| 字号/行高（ui-sm/base、mono 14/20 等） | `01` §6、`03` §8.4 | `ui/theme/Type.kt`（`ZemoteType`） | 部分 |
| 间距乘数（4px 基） | `01` §7、附录 A | `ui/theme/Dimens.kt`（`ZemoteSpacing`） | 已对齐 |
| 圆角（12/16 等） | `01` §8 | `ui/theme/Dimens.kt`（`ZemoteRadius`） | 部分 |
| 动效（150ms 默认、200ms ease-out 折叠、300ms ease-in-out 思考、chevron 200ms） | `01` §动画表、`03` §3.4、`04` §2.5 | `ui/chat/timeline/RowComponents.kt`（`ZemoteEaseOut/ZemoteEaseInOut`、`ZemoteToolCollapseMs/ZemoteReasoningCollapseMs`）、`CollapsibleContent` | 已对齐（2026-11-03 重写，一次测量 + 高度裁剪） |
| 类名→语义映射（94 条） | `06-class-map.md`、`artifacts/class-map.json`、`components.normalized.css` | 查询用；无直接运行时对应 | 参考资产 |

## 1. 外壳 / 首页

| Web 结构 | 报告 | Compose 落点 | 状态 |
| --- | --- | --- | --- |
| 顶栏 48px、侧栏 264px、无底部 Tab（浏览器版） | `02` §2–§3、§8.1 | `ui/MainScreen.kt`、`MainTabBar.kt`（自绘底栏） | 部分（移动端保留底栏，是否砍掉待定） |
| 项目树行 32px、分组头、置顶/项目分组 | `02` §4、§8.3 | `ui/home/HomeScreen.kt` | 部分 |
| 无骨架屏、缓存优先出内容 | `02` §4 | `HomeViewModel`（HomeCache 先铺）+ `HomeScreen` | 已对齐 |
| 任务状态徽章/呼吸动画 | `02` §4.6、`01` §动画表 | `HomeScreen` | 未动 |
| 长文本截断/行省略规则 | `02` §6.10 | `HomeScreen` | 未动 |

## 2. 对话页骨架 / 时间线

| Web 结构 | 报告 | Compose 落点 | 状态 |
| --- | --- | --- | --- |
| header 48px、列宽断点（移动单列） | `03` §2、§8.1 | `ui/chat/ChatScreen.kt` | 部分 |
| 行级 DOM/间距（turn 间 20px、flow gap 20px） | `03` §8.2–§8.3 | `Timeline.kt`（`TimelineContentPadding`、`spacedBy(space5)`） | 已对齐 |
| turn header（文案/chevron 90°/200ms） | `03` §3.2 | `TurnHeaderRow.kt` | 已对齐（chevron/时长 2026-11-03） |
| reasoning（折叠头、流式尾行、竖线、自动收起、300ms ease-in-out、逐词揭示） | `03` §3.4 | `ReasoningRow.kt`、`StreamingReveal.kt` | 已对齐（逐词揭示 2026-11-04，待真机复验） |
| reasoning 240px 内滚 | `03` §3.4 | `ReasoningRow.kt`（**有意偏离**：用户要求完整展开，不设 maxHeight） | 偏离（记录在案） |
| assistant 正文 Markdown 14/1.75、动作条 hover | `03` §3.3 | `AssistantTextRow.kt`、`markdown/*`、`RowActions.kt` | 部分 |
| 用户输入行（max-height 120px 折叠 + 遮罩） | `03` §3.1 | `UserInputRow.kt`（`animateContentSize`） | 部分 |
| marker/subagent/hook/artifact 行 | `03` §3.5、`04` §6–§7 | `MarkerRow.kt`、`SubagentRow.kt`、`HookTrailerRow.kt`、`ArtifactRow.kt` | 部分 |
| 贴底跟随/滚动渐隐 | `03` §5 | `Timeline.kt`（following、snapshotFlow 贴底、IME 视口联动） | 已对齐 |

## 3. 工具 / 终端 / diff / 产物

| Web 结构 | 报告 | Compose 落点 | 状态 |
| --- | --- | --- | --- |
| ToolLayout 折叠行（8px gap、14px 文本、16px 图标、subtlest、无勾/无耗时） | `04` §1–§2 | `ToolCallRow.kt` | 已对齐 |
| 三态状态机（pending/running 渐变、failed 点线） | `04` §2.3 | `ToolCallRow.kt` | 部分（running 用呼吸近似，4s 扫光未做） |
| 终端卡片（panel、12px 圆角、命令 3lh↔10lh、输出 5lh、跟随） | `04` §4 | `ToolCallRow.kt`（`ShellDetails`） | 部分 |
| 文件写入/diff（chip、240px 预览、48px gutter、3px 左条、800 行截断） | `04` §5 | `ToolCallRow.kt` + `markdown/MarkdownHighlighter.kt`（diff 着色） | 部分 |
| MCP 卡片 | `04` §6 | `ToolCallRow.kt`（`McpDetails`） | 部分 |
| 分组（连续+≥2、缩进 8/8/1/14、组默认折叠、组头同 ToolLayout） | `04` §3 | `ToolGroupRow.kt` | 已对齐 |
| 展开动画 200ms ease-out、一次测量 | `04` §2.5 | `RowComponents.kt`（`CollapsibleContent`） | 已对齐 |
| 代码块（12px 圆角、复制 2s、30 行折叠为移动端自加） | `04` §12 条目、`03` §6 | `markdown/CodeBlock.kt` | 部分 |
| 「网页没有的不要加」清单 | `04` §10 | 各 Row 文件 | 部分残留（自查） |

## 4. 输入区 / 浮层 / 设置

| Web 结构 | 报告 | Compose 落点 | 状态 |
| --- | --- | --- | --- |
| 输入卡片（16px 圆角、0.5px 边框、12px padding、hover/focus 边框） | `05` §6.1 | `ChatComposer.kt` | 部分（0.5dp 边框/28dp 按钮已落，hover/focus 态未做） |
| 工具栏（+ / 模式 / 模型 / 思考 / 发送 28dp） | `05` §6.1、`docs/audit/07` | `ChatComposer.kt` | 已对齐（模式菜单含 plan、未知值归一化） |
| 队列卡 / 待交互卡 | `05` §5 | `ChatScreen.kt`（`QueueCards`、`InteractionCard`） | 部分 |
| 模型选择二级菜单 | `05` §4 | `ModelSheet.kt` | 部分 |
| DropdownMenu/Popover/Dialog/Toast 参数 | `05` §6.3 | `common/Components.kt` + 各弹层 | 未动（沿用 M3 默认） |
| 设置页表单 | `05` §6.4 | `settings/*` | 未动 |

## 5. 与外观无关、但同批改掉的行为

| 项 | 位置 | 说明 |
| --- | --- | --- |
| 订阅 6s ack + 自动重连恢复 | `ChatViewModel.kt`（`subscribeConversation`/`recoverSubscription`） | 冷启动复用预览旧桥导致 ack 不回；自愈一次后仍失败才报错 |
| 全量快照风暴治理 | `ChatViewModel.kt`（`requestConversationRefresh`）、`NotificationCoordinator.kt` | stateChanged 不再拉快照；索引 10s/通知 1s 合并 |
| 历史大文本渲染上限 | `RowComponents.kt`（`clampTextForRender`）+ `ToolCallRow/AssistantTextRow` | 防「上翻历史闪退」的第一道护栏（根因待 crash.log 确认） |
| 流式逐词揭示 | `StreamingReveal.kt` + `ReasoningRow.kt` | 桥 150ms 合帧 → 帧时钟逐词补齐；`StreamingRevealTest` 覆盖边界 |

## 6. 施工顺序建议（下一批）

1. 真机复验本批（思考流畅度、上翻不闪退、折叠动画手感、订阅自愈）。
2. `04` §12 移植清单的 1–5（ToolLayout 细节、状态机、图标映射、终端卡片、diff）——对话页信息密度最高、差异最显眼。
3. `03` §8 对照表过一遍时间线行高/字色（reasoning 流式尾行的左右 16px 渐隐遮罩是移动端没做的）。
4. `02` 首页行高/徽章/截断。
5. `05` 浮层/设置统一换参数（DropdownMenu 28dp 项高、Dialog 16px 圆角等）。
6. 每完成一批，回填本文状态列（覆写，不留「仍有效」的旧状态）。
