# ZCode 3.17.1 Web 产物：样式级反混淆、组件类名映射与规范化 CSS

> 范围：`Reference_web/3.17.1/`（2026-10-30 公共部署快照，2581 个文件，46,583,133 B）。
> 目标：把 `assets/index-Dwhx77cK.js` + `assets/index-BgVjt5KE.css` 的样式信息还原到「可重写」程度，供原生 Compose 对齐。
> 结论先行：**本产物没有 source map；但 class 名并没有被 hash/minify**。UI 由 Tailwind v4 utility 字符串 + 少量保留语义名的组件 class 组成。因此可恢复的是「组件结构 + 每个元素的完整 class 组合 + 对应 CSS 规则 + 状态属性」，不能恢复的是原组件名、props 类型、条件表达式与业务源码。

---

## 1. 产物结构

### 1.1 文件与加载方式

| 文件 | 大小 | 作用 |
| --- | ---: | --- |
| `index.html` | 18,554 B | 入口；内联主题引导脚本（`zcode-theme`，默认 `zai-dark`）、内联 favicon |
| `assets/index-Dwhx77cK.js` | 6,986,617 B | 主 bundle；React 运行时、应用壳、聊天页、输入区、工具卡片等 |
| `assets/index-BgVjt5KE.css` | 485,222 B | 主样式；Tailwind v4 产物 + 组件语义 class + KaTeX/xterm 样式 |
| `assets/IntlProvider-CneXitqp.js` | 972,628 B | i18n 消息目录（中/英），含 `chat.*` / `appHeader.*` / `sidebar*` 等 key |
| `assets/*.js` | 共 2490 个 | 按需 lazy chunk：语言高亮、预览器、图表、PDF/Office 渲染等 |
| `_manifest.json` / `_manifest.md` | 1.07 MB / 279 KB | 部署快照清单（URL、字节数、SHA-256、referencedBy） |

主 JS 基本是「按 chunk 一行」的压缩产物（最大单行 476,866 字符），不是逐行可读源码。

### 1.2 没有 source map

- JS 与 CSS 中均无 `sourceMappingURL`；`assets/` 下无 `.map` 文件。
- 压缩器只改了 JS 标识符（`WorkspaceShell` → `sbn`、`ToolCallBlock` → `r0`）和 chunk 文件名，**没有改 CSS class 字符串**。
- 因此不存在「class 名 → 原 class 名」的哈希映射可还原；本报告的「原始 class」就是 bundle 中真实出现的 `className` 字面量。

### 1.3 样式技术栈（证据）

| 技术 | 证据 |
| --- | --- |
| Tailwind CSS v4 | `@layer theme, base, components, utilities`；`--tw-*` 变量；`@property`；`@container/*`；任意值语法 `w-[...]`、`data-[...]:` |
| Radix UI / shadcn 风格封装 | `data-radix-popper-content-wrapper`、`data-state=open/closed`、`data-slot=...`、`data-side`、`data-align` |
| Lexical | 编辑器代码中的 `LexicalChatInput`、`namespace:"ChatInput"`、`registerRootListener` |
| React | `(0,$.jsx)` / `(0,$.jsxs)` 编译输出、`Q.memo` / `Q.useSyncExternalStore` |
| 虚拟列表 | `getVirtualItems` / `measureElement` / `scrollToIndex` / `getTotalSize`（TanStack Virtual 风格 API） |
| 其它（按需 chunk） | xterm、KaTeX、streamdown/remark/rehype、shiki、framer-motion、各类 Office/PDF 预览器 |

### 1.4 命名分层

1. **Tailwind utility**（绝大多数）：`flex`、`min-w-0`、`text-ui-base`、`bg-surface`、`rounded-xl`、`data-[state=open]:...`。
2. **保留语义 class**（少量，但信息量最高）：
   - `composer-thought-*`（思考等级滑杆）
   - `highspeed-composer-surface` / `highspeed-share-dialog__*` / `highspeed-sidebar-share-card__*`
   - `wf-*`（workflow 时间线/卡片/表情）
   - `zcode-*`（加载、流式、跑马灯、入场动画）
   - `prompt-mention`、`file-application-more-trigger`、`onboarding-logo-sweep`、`task-title-marquee`、`text-wrap-phrase`
   - `platform-mac-desktop` / `platform-windows-desktop` / `platform-linux-desktop`
   - `theme-zai-dark` / `theme-zai-light` / `dark`
3. **JS 语义钩子 class**（CSS 无规则，仅作为选择器/查询锚点）：`chat-composer-region`、`not-prose`、`group/*`、`peer/*`。

---

## 2. 反混淆方法

### 2.1 CSS 解析

自写解析器（`docs/web-reverse/tools/parse_css.py`，已删除）把 485 KB 单行 CSS 拆成 4,753 条规则：

| 类型 | 数量 |
| --- | ---: |
| 普通规则 | 4,578 |
| `@property` | 95 |
| `@keyframes` | 59 |
| `@font-face` 等块 | 20 |
| 无块 at-rule | 1 |

同时建立：

- 选择器 → class token 索引（3,694 个 class 选择器）；
- at-rule 栈（`@layer` / `@media` / `@supports` / `@container`）；
- 声明列表（属性 → 值，保留 `!important`）。

CSS 末尾 `@layer utilities{` 少一个收尾 `}`（压缩器行为），解析时按括号平衡补齐，否则会把后续 59 个 keyframes 全部吞进 utilities 层。

### 2.2 JS 扫描

`docs/web-reverse/tools/scan_js.py`（已删除）在 6.99 MB JS 中提取 16,098 条「带 class 的字面量」记录：

- 8,840 条 `className:` 属性（template / string / 表达式三种）；
- 7,258 条其它字面量（常量、`cn(...)` 参数、`Z(...)` 参数），用 CSS class 集合反查命中；
- 每条记录保存：字符偏移、行号、±500 字符上下文、邻近 `data-testid` / `aria-label` / i18n message id。

> 偏移是 **Python 解码后的字符偏移**，不是 `rg -b` 的字节偏移；含中文的 bundle 两者差异很大，本报告全部使用字符偏移。

### 2.3 交叉验证

| 证据 | 用途 |
| --- | --- |
| CSS class 出现次数与选择器形态 | 区分 utility 与组件语义 class；识别 `data-*` 状态变体 |
| `data-testid`（715 个唯一值） | 定位组件边界，如 `chat-input`、`chat-model-select-trigger`、`tool-summary-trigger`、`v4-session-quota-banner` |
| `data-*` 属性（892 个唯一键） | 还原状态组合，如 `data-v4-turn-unit`、`data-composer-layout`、`data-highspeed` |
| i18n message id | 确认区域与语义，如 `chat.toolCall.execute.*`、`chat.reasoning.*`、`chat.empty.greeting.*` |
| `displayName=` | 19 个 displayName（含模板占位），可读示例：`ToolCallBlock`、`ToolLayout`、`Reasoning`、`ReasoningTrigger`、`ReasoningContent`、`MessageResponse`、`DiffViewer`、`TaskList` |
| 编译后 JSX 嵌套 | 还原标签层级、条件分支、子组件调用关系 |

### 2.4 命名与置信度

- 语义类名统一为 `z-<region>-<component>-<part>`，例如 `z-shell-workspace-root`、`z-composer-thought-fill`、`z-timeline-user-bubble`。
- 置信度：
  - **high**：在组件定义处直接读到 class 字符串，且有 `data-*` / `data-testid` / 组件名佐证；
  - **medium**：class 字符串来自共享子组件，或由 `Z(...)` 条件拼接，基础字面量可确认但状态分支为推断；
  - **low**：仅凭上下文推断用途（本次未使用 low，全部为 high/medium）。

---

## 3. 局限（必须与结论一起看）

1. **无法恢复原组件名与 props**：JS 标识符已压缩；只有 19 个 `displayName`（其中 15 个具体组件名）与少量 i18n/testid 可反推语义名。报告中的 `WorkspaceShell`、`ComposerShell` 等是本报告赋予的语义名，不是原码名。
2. **动态 class 只恢复基础分支**：`Z(\`base\`, cond && \`state\`)` 这类调用，报告记录 `base` 为原始 class，条件分支记入 `extra` 或规范化 CSS 的 `state/alt class` 注释；没有枚举所有运行时组合。
3. **CSS 级联是近似合并**：规范化 CSS 按原 CSS 规则出现顺序合并同名属性（后者覆盖前者），但没有在浏览器里做 computed style 验证；简写/长写、`@layer` 优先级、`!important` 的最终结果仍需以浏览器为准。
4. **`group-*` / `peer-*` / `:has()` 组合变体不可等价改写**：这些规则依赖祖先/兄弟状态，规范化 CSS 只保留原始选择器注释，不强行翻译。
5. **运行时 CSS 变量不在 CSS 中**：`--workspace-sidebar-panel-width`、`--workspace-panel-radius`、`--v4-draft-greeting-font-size`、`--v4-floating-composer-dock-height` 由 JS 内联 style 写入；CSS 里只有 `var(..., fallback)`。
6. **范围**：主 bundle 与主 CSS 做了穷举扫描；lazy chunk 只对语义 class 做了抽查（如 `previewPane*`、`pptxRenderer*`），没有逐 chunk 建映射。
7. **没有运行时 DOM**：组件树来自编译后 JSX 调用，不是实际渲染结果；列表虚拟化、Portal、条件渲染会改变真实 DOM。
8. **两个 token 无 CSS 规则**：`chat-composer-region`、`not-prose`。前者是布局查询锚点，后者是 typography 插件约定；均不影响样式还原。

---

## 4. 类名映射表

列说明：

- **语义 ID**：本报告稳定标识，对应 `class-map.json.entries[].id`。
- **组件**：语义组件名（非原码名），括号内为压缩标识或函数名（见 JSON `componentId`）。
- **原始 class**：bundle 中真实 `className` 字面量（截断显示；完整值在 JSON 与 `components.normalized.css` 注释中）。
- **基础 CSS**：只取「精确 `.class`」规则合并后的前 3 条声明；完整规则、变体、at-rule 在 `components.normalized.css`。
- 所有条目均附证据与置信度，完整字段见 `artifacts/class-map.json`。

### Shell

| 语义 ID | 组件 | 原始 class（截断） | 基础 CSS（前 3 项） | 用途 | 置信度 |
| --- | --- | --- | --- | --- | --- |
| `shell.workspace-root` | WorkspaceShell | `relative flex h-full min-h-0 w-full overflow-hidden [anchor-s…` | `position:relative; display:flex; height:100%` | 应用工作区根容器：左右分栏、裁剪溢出，为侧栏分隔线提供 anchor-scope。 | high |
| `shell.sidebar-panel` | SidebarPanel | `w-[var(--workspace-sidebar-panel-width)] max-w-[50%] flex-non…` | `width:var(--workspace-sidebar-panel-width); max-width:50%; flex:none` | 左侧项目侧栏容器；宽度来自 JS 内联变量，折叠时透明度过渡。 | high |
| `shell.sidebar-aside` | SidebarAside | `h-full overflow-hidden select-none` | `height:100%; overflow:hidden; -webkit-user-select:none` | 侧栏内容区，禁止文本选择（桌面壳行为）。 | high |
| `shell.resize-handle` | ResizableHandle | `group/handle relative z-10 flex h-full w-1 shrink-0 touch-non…` | `position:relative; z-index:10; display:flex` | 侧栏拖拽分隔条：1px 视觉线由 after 伪元素绘制，hover/拖拽时显现。 | high |
| `shell.divider-extension` | SidebarDividerExtension | `pointer-events-none absolute h-px w-1 bg-border/50 [left:anch…` | `pointer-events:none; position:absolute; top:anchor(--sidebar-task-list-divide…` | 用 CSS anchor 定位把侧栏分隔线延伸到拖拽手柄，形成连续分割线。 | high |
| `shell.content-column` | WorkspaceContent | `flex min-w-[320px] flex-1 flex-col` | `display:flex; min-width:320px; flex:1` | 主内容列（对话 + 侧面板），窄屏最小宽度 320px，桌面留 4px 内边距。 | high |
| `shell.conversation-frame` | ConversationFrame | `relative flex h-full min-h-0 flex-1 flex-col overflow-hidden …` | `position:relative; display:flex; height:100%` | 对话主面板外框：圆角/描边/阴影由平台与分栏状态决定。 | high |
| `shell.side-frame` | SidePaneFrame | `h-full overflow-hidden bg-background` | `height:100%; overflow:hidden; background-color:var(--color-background)` | 右侧多标签侧面板外框（浏览器/代码/终端等）。 | high |
| `shell.workspace-header` | WorkspaceHeader | `@container/workspace-header relative flex w-full shrink-0 h-1…` | `container:workspace-header/inline-size; position:relative; display:flex` | 工作区顶栏：48px 高，draft 态透明底边，task 态细分隔线。 | high |
| `shell.header-row` | WorkspaceHeaderRow | `flex h-12 flex-1 min-w-0 items-center justify-between gap-2 o…` | `display:flex; height:calc(var(--spacing) * 12); min-width:calc(var(--spacing)…` | 顶栏内容行：可拖拽窗口，左侧标题/分支、右侧动作。 | high |
| `shell.primary-nav` | SidebarPrimaryNavigation | `flex flex-col gap-1 px-2 py-3` | `display:flex; flex-direction:column; gap:calc(var(--spacing) * 1)` | 侧栏顶部主导航（新任务/自动化/插件/设备切换）。 | high |
| `shell.sidebar-content` | WorkspaceSidebarContent | `flex h-full min-h-0 flex-col overflow-hidden` | `display:flex; height:100%; min-height:calc(var(--spacing) * 0)` | 项目侧栏整体：顶部拖拽条 + 导航 + 任务列表 + 底部账户区。 | high |
| `shell.task-region` | SidebarTaskRegion | `flex min-h-0 flex-1 flex-col border-t border-border/50 pt-3 […` | `display:flex; min-height:calc(var(--spacing) * 0); flex:1` | 任务列表区域，顶部锚点供分隔线扩展定位。 | high |
| `shell.task-row` | TaskRow | `flex min-h-7 w-full min-w-0 flex-col justify-center rounded-l…` | `display:flex; min-height:calc(var(--spacing) * 7); width:100%` | 侧栏任务行：28px 高，选中/悬停/拖拽三态。 | high |
| `shell.task-row-inner` | TaskRowInner | `flex h-7 w-full min-w-0 items-center gap-2` | `display:flex; height:calc(var(--spacing) * 7); width:100%` | 任务行内容行：标题 marquee + 状态/时间/操作。 | high |
| `shell.project-row` | ProjectRow | `group/project-row ml-2 flex h-8 min-w-0 items-center gap-1 ro…` | `margin-left:calc(var(--spacing) * 2); display:flex; height:calc(var(--spacing…` | 侧栏项目行：32px 高，悬停显示操作按钮。 | high |
| `shell.sidebar-drag-strip` | SidebarDragStrip | `h-12 shrink-0 [app-region:drag]` | `height:calc(var(--spacing) * 12); flex-shrink:0; app-region:drag` | 侧栏顶部 48px 窗口拖拽区（macOS 红绿灯让位）。 | high |
| `shell.sidebar-nav-panel` | SidebarNavigationPanel | `absolute inset-0 flex min-h-0 flex-col` | `position:absolute; inset:calc(var(--spacing) * 0); display:flex` | 侧栏导航面板绝对定位容器，供滑入/滑出过渡。 | high |

### Home

| 语义 ID | 组件 | 原始 class（截断） | 基础 CSS（前 3 项） | 用途 | 置信度 |
| --- | --- | --- | --- | --- | --- |
| `home.greeting` | DraftGreeting | `relative mb-10 flex w-full max-w-2xl flex-col items-center ju…` | `position:relative; margin-bottom:calc(var(--spacing) * 10); display:flex` | 空会话首屏问候语容器（含背景 logo）。 | high |
| `home.greeting-text` | DraftGreetingText | `relative z-10 w-full px-4 text-center font-medium text-foregr…` | `position:relative; z-index:10; width:100%` | 问候语文本：字号按可用宽度动态计算（20–30px）。 | high |
| `home.logo` | DraftLogo | `pointer-events-none absolute left-1/2 top-1/2 aspect-[5/4] w-…` | `pointer-events:none; position:absolute; top:50%` | 问候语背后的 ZCode 标志水印（浅色 SVG / 深色位图）。 | high |
| `home.suggestions-chips` | SuggestedPromptsChips | `w-full min-w-0 overflow-x-auto [scrollbar-width:none] [&::-we…` | `width:100%; min-width:calc(var(--spacing) * 0); overflow-x:auto` | 首屏推荐提示词横向滚动条（隐藏滚动条）。 | high |
| `home.suggestions-chip-group` | SuggestedPromptsChipsGroup | `mx-auto flex w-max items-center gap-4` | `margin-inline:auto; display:flex; width:max-content` | 推荐提示词按钮组，水平居中。 | high |
| `home.suggestion-chip` | SuggestedPromptChip | `zcode-draft-prompt-waterfall h-8 min-w-0 justify-start gap-1.…` | `height:calc(var(--spacing) * 8); min-width:calc(var(--spacing) * 0); justify-…` | 推荐提示词胶囊：32px 高，逐个瀑布式入场。 | high |
| `home.suggestions-list` | SuggestedPromptsList | `w-full min-w-0 px-2 py-4` | `width:100%; min-width:calc(var(--spacing) * 0); padding-inline:calc(var(--spa…` | 推荐提示词列表容器（onboarding 场景）。 | high |
| `home.suggestion-list-item` | SuggestedPromptListItem | `flex w-full items-center gap-3 rounded-xl p-3 text-left text-…` | `display:flex; width:100%; align-items:center` | 推荐提示词列表项：图标 + 文案。 | high |
| `home.suggestions-slot` | SuggestedPromptsSlot | `h-8` | `height:calc(var(--spacing) * 8)` | 推荐提示词插槽：chips 布局固定 32px 高。 | high |

### Composer

| 语义 ID | 组件 | 原始 class（截断） | 基础 CSS（前 3 项） | 用途 | 置信度 |
| --- | --- | --- | --- | --- | --- |
| `composer.region` | ComposerRegion | `chat-composer-region z-20 w-full shrink-0 @container/composer` | `container:composer/inline-size; z-index:20; width:100%` | 输入区根容器：container query 上下文，浮层 z-20。 | high |
| `composer.surface` | ComposerSurface | `chat-composer-input-surface relative w-full` | `position:relative; width:100%; border-color:#0000` | 输入框表面层：承载 highspeed 动画背景与编辑器。 | high |
| `composer.highspeed-surface` | HighspeedComposerSurface | `highspeed-composer-surface` | `isolation:isolate; border-radius:16px; transition:border-color 1.04s cubic-be…` | 高速模式输入框：紫色描边/辉光，按 data-phase 切换。 | high |
| `composer.shell` | ComposerShell | `relative flex flex-col gap-3 overflow-hidden rounded-2xl bord…` | `position:relative; display:flex; flex-direction:column` | 输入框卡片：默认纵向，inline 模式横排；聚焦/拖拽换边框色。 | high |
| `composer.editor-wrap` | ComposerEditorWrap | `relative flex-1` | `position:relative; flex:1` | Lexical 富文本编辑器外层。 | high |
| `composer.editor` | ComposerEditor | `min-h-10 max-h-40 overflow-y-auto text-foreground outline-none` | `max-height:calc(var(--spacing) * 40); min-height:calc(var(--spacing) * 10); o…` | 可编辑输入区：最小 40px、最大 160px 后滚动。 | high |
| `composer.placeholder` | ComposerPlaceholder | `pointer-events-none absolute left-0 top-0 line-clamp-2 text-f…` | `pointer-events:none; position:absolute; top:calc(var(--spacing) * 0)` | 空输入占位文案，最多两行。 | high |
| `composer.toolbar` | ComposerToolbar | `group/toolbar flex items-end gap-3` | `display:flex; align-items:flex-end; gap:calc(var(--spacing) * 3)` | 输入框底部工具栏：左侧附件/模式，右侧模型/发送。 | high |
| `composer.leading-actions` | ComposerLeadingActions | `flex min-w-0 flex-1 items-center` | `display:flex; min-width:calc(var(--spacing) * 0); flex:1` | 工具栏左侧动作区，承载宽度折叠逻辑。 | high |
| `composer.leading-content` | ComposerLeadingContent | `flex shrink-0 items-center gap-1` | `display:flex; flex-shrink:0; align-items:center` | 左侧动作内容组。 | high |
| `composer.trailing-actions` | ComposerTrailingActions | `ml-auto flex shrink-0 items-center justify-end gap-1.5` | `margin-left:auto; display:flex; flex-shrink:0` | 工具栏右侧：停止/发送/模型选择。 | high |
| `composer.send-button` | ComposerSendButton | `cursor-pointer gap-1 rounded-full bg-brand text-ui-base text-…` | `cursor:pointer; gap:calc(var(--spacing) * 1); border-radius:3.40282e38px` | 发送按钮：品牌色圆形，禁用时降透明度。 | high |
| `composer.stop-button` | ComposerStopButton | `rounded-full` | `border-radius:3.40282e38px` | 停止生成按钮（圆形）。 | high |
| `composer.attachment-row` | AttachmentRow | `flex max-w-full flex-wrap gap-2` | `display:flex; max-width:100%; flex-wrap:wrap` | 输入框上方附件行，自动换行。 | high |
| `composer.attachment-inline` | AttachmentItemInline | `h-12 w-fit max-w-full min-w-0 gap-2 rounded-lg border border-…` | `height:calc(var(--spacing) * 12); width:fit-content; max-width:100%` | 文件附件卡片（非图片）：48px 高，右侧留删除按钮位。 | high |
| `composer.attachment-grid` | AttachmentItemGrid | `relative size-12 overflow-hidden rounded-lg bg-surface after:…` | `position:relative; width:calc(var(--spacing) * 12); height:calc(var(--spacing…` | 图片/视频缩略图附件：48×48 圆角方块。 | high |
| `composer.thought-slider` | ThoughtSlider | `composer-thought-slider relative h-8 w-full rounded-full` | `--composer-thought-slider-thumb:var(--color-white); --composer-thought-max-ac…` | 思考等级滑杆：32px 高，含轨道/填充/拇指/刻度点。 | high |
| `composer.thought-track` | ThoughtSliderTrack | `absolute inset-x-0 top-1 h-6 overflow-hidden rounded-full bg-…` | `position:absolute; inset-inline:calc(var(--spacing) * 0); top:calc(var(--spac…` | 滑杆轨道：24px 高，内描边由子元素绘制。 | high |
| `composer.thought-fill` | ThoughtSliderFill | `composer-thought-slider-fill absolute inset-y-0 left-0 overfl…` | `background:var(--color-success); transition:width .12s ease-out; position:abs…` | 滑杆进度填充；满级时显示气泡动画，highspeed 时变蓝紫渐变。 | high |
| `composer.thought-thumb` | ThoughtSliderThumb | `composer-thought-slider-thumb absolute top-0.5 size-7 rounded…` | `background:var(--composer-thought-slider-thumb); transition:left .12s ease-ou…` | 滑杆拇指：28px 白色圆点。 | high |
| `composer.thought-stop` | ThoughtSliderStop | `composer-thought-slider-stop size-1 shrink-0 rounded-full` | `background:var(--color-foreground-subtlest); transition:scale .12s ease-out,o…` | 等级刻度点：4px，悬停放大 1.75 倍。 | high |
| `composer.thought-range` | ThoughtSliderInput | `composer-thought-range absolute inset-0 m-0 h-8 w-full cursor…` | `position:absolute; inset:calc(var(--spacing) * 0); margin:calc(var(--spacing)…` | 透明原生 range 覆盖层，负责指针/键盘交互。 | high |
| `composer.drag-overlay` | ComposerDragOverlay | `pointer-events-none absolute inset-0 z-10 flex items-center j…` | `pointer-events:none; position:absolute; inset:calc(var(--spacing) * 0)` | 文件拖入输入框时的半透明遮罩。 | high |
| `composer.drag-pill` | ComposerDragPill | `flex items-center gap-2 rounded-full border border-border bg-…` | `display:flex; align-items:center; gap:calc(var(--spacing) * 2)` | 拖拽提示胶囊（图标 + 文案）。 | high |
| `composer.queue-resume` | QueueResumeBar | `relative z-0 w-full overflow-hidden rounded-t-2xl border bord…` | `position:relative; z-index:0; width:100%` | 队列恢复条：与输入框顶部叠接的圆角卡片。 | medium |
| `composer.model-trigger` | ModelSelectTrigger | `min-w-0 rounded-full text-ui-base/relaxed` | `min-width:calc(var(--spacing) * 0); border-radius:3.40282e38px; font-size:var…` | 模型选择触发器：胶囊形，窄容器折叠为图标。 | high |
| `composer.thought-compact` | ThoughtControlCompact | `group/thought shrink-0 data-[composer-compact=icon]:size-7 da…` | `flex-shrink:0` | 思考等级控件的紧凑态：折叠为 28px 图标按钮。 | medium |

### Chat Timeline

| 语义 ID | 组件 | 原始 class（截断） | 基础 CSS（前 3 项） | 用途 | 置信度 |
| --- | --- | --- | --- | --- | --- |
| `timeline.root` | Timeline | `relative flex min-h-0 flex-1 flex-col` | `position:relative; display:flex; min-height:calc(var(--spacing) * 0)` | 对话时间线根容器。 | high |
| `timeline.scroller` | TimelineScroller | `min-h-0 flex-1 overflow-x-hidden overflow-y-auto [scrollbar-g…` | `min-height:calc(var(--spacing) * 0); flex:1; overflow-x:hidden` | 虚拟滚动容器；滚动条槽两侧稳定，表格布局内缩变量。 | high |
| `timeline.inner` | TimelineInner | `flex min-h-full flex-col` | `display:flex; min-height:100%; flex-direction:column` | 时间线内容内层：空态居中或顶部 29dvh 留白。 | high |
| `timeline.message-layer` | TimelineMessageLayer | `relative w-full flex-1 [mask-repeat:no-repeat] [-webkit-mask-…` | `position:relative; width:100%; flex:1` | 消息层：承载 header slot、虚拟历史、运行尾部和 guide 列表。 | high |
| `timeline.content-column` | TimelineContentColumn | `relative mx-auto w-full shrink-0 transition-[width,max-width,…` | `position:relative; margin-inline:auto; width:100%` | 内容列：宽度随侧面板开合过渡，≥1280px 只过渡 transform。 | high |
| `timeline.turn-unit` | TurnUnit | `absolute left-0 top-0 w-full` | `position:absolute; top:calc(var(--spacing) * 0); left:calc(var(--spacing) * 0)` | 虚拟列表中的单轮容器：绝对定位 + translateY。 | high |
| `timeline.assistant-turn` | AssistantTurn | `group/assistant-turn flex w-full flex-col gap-5` | `display:flex; width:100%; flex-direction:column` | 助手回合内容：20px 间距的纵向栈。 | high |
| `timeline.user-bubble` | UserInputBubble | `flex max-w-full flex-col gap-2 rounded-xl rounded-tr-xs borde…` | `display:flex; max-width:100%; flex-direction:column` | 用户消息气泡：右上角小圆角，≥624px 最大宽 36rem。 | high |
| `timeline.user-collapsible` | UserInputCollapsible | `min-w-0 overflow-hidden whitespace-pre-wrap break-words trans…` | `min-width:calc(var(--spacing) * 0); overflow:hidden; overflow-wrap:break-word` | 长用户消息折叠：默认 120px，超过 120px 显示展开按钮。 | high |
| `timeline.assistant-text` | AssistantTextRow | `w-full text-ui-base` | `width:100%; font-size:var(--text-ui-base)` | 助手 Markdown 正文行。 | high |
| `timeline.assistant-actions` | AssistantActions | `mt-1` | `margin-top:calc(var(--spacing) * 1)` | 助手消息操作条：默认悬停显现。 | high |
| `timeline.reasoning-root` | Reasoning | `not-prose flex flex-col` | `display:flex; flex-direction:column` | 思考过程折叠根（Radix Collapsible）。 | high |
| `timeline.reasoning-trigger` | ReasoningTrigger | `group/reasoning inline-flex max-w-full min-w-0 items-center g…` | `display:inline-flex; max-width:100%; min-width:calc(var(--spacing) * 0)` | 思考过程摘要触发条：图标 + “思考 · N 秒” + 流式末行。 | high |
| `timeline.reasoning-streaming` | ReasoningStreamingLabel | `animated-gradient-text font-medium` | `--tw-font-weight:var(--font-weight-medium); font-weight:var(--font-weight-med…` | 流式思考中的渐变文字。 | high |
| `timeline.reasoning-content` | ReasoningContent | `pt-3` | `padding-top:calc(var(--spacing) * 3)` | 思考过程内容区外层。 | high |
| `timeline.reasoning-body` | ReasoningBody | `max-h-60 space-y-2 overflow-auto text-ui-base text-foreground…` | `max-height:calc(var(--spacing) * 60); overflow:auto; font-size:var(--text-ui-…` | 思考正文：最大 240px 滚动，左侧竖线缩进。 | high |
| `timeline.marker` | TimelineMarker | `flex w-full items-center gap-3 px-4 py-2 text-ui-base text-[v…` | `display:flex; width:100%; align-items:center` | 时间线分隔标记（压缩/模型切换等）：两侧细线 + 居中标签。 | high |
| `timeline.turn-header` | TurnHeaderRow | `border-b border-[var(--color-border)] py-1 text-ui-sm text-[v…` | `border-bottom-style:var(--tw-border-style); border-bottom-width:1px; border-c…` | 调试用回合头（origin/state），通常不展示给最终用户。 | medium |
| `timeline.composer-dock` | ComposerDock | `pointer-events-none z-20 flex w-full justify-center` | `pointer-events:none; z-index:20; display:flex` | 时间线底部输入框停靠层：sticky / absolute / static 三模式。 | high |
| `timeline.dock-content` | ComposerDockContent | `pointer-events-auto relative z-10 w-full shrink-0 transition-…` | `pointer-events:auto; position:relative; z-index:10` | 停靠内容宽度过渡层。 | high |
| `timeline.scroll-to-bottom` | ScrollToBottomButton | `pointer-events-auto absolute bottom-full left-1/2 z-30 mb-2 -…` | `pointer-events:auto; position:absolute; bottom:100%` | 回到底部按钮：悬于输入框上方或时间线底部。 | high |
| `timeline.turn-navigator` | TurnNavigator | `pointer-events-none invisible absolute inset-y-0 left-0 z-10 …` | `pointer-events:none; visibility:hidden; position:absolute` | 左侧回合导航条：≥864px 显示，按用户提问定位。 | high |
| `timeline.navigator-tick` | TurnNavigatorTick | `block h-0.5 w-3 origin-left rounded-full transition-[height,o…` | `display:block; height:calc(var(--spacing) * .5); width:calc(var(--spacing) * 3)` | 导航条刻度：2px 高短线，按距离缩放/着色。 | high |

### Tool

| 语义 ID | 组件 | 原始 class（截断） | 基础 CSS（前 3 项） | 用途 | 置信度 |
| --- | --- | --- | --- | --- | --- |
| `tool.block` | ToolCallBlock | `w-full` | `width:100%` | 工具调用卡片根；data-tool-name/status 驱动具体渲染器。 | high |
| `tool.nested-list` | ToolCallNestedList | `ml-2 space-y-2 border-border border-l pl-3.5 border-border` | `margin-left:calc(var(--spacing) * 2); border-left-style:var(--tw-border-style…` | 子工具调用列表：左侧竖线 + 14px 缩进。 | high |
| `tool.layout` | ToolLayout | `w-full flex flex-col` | `display:flex; width:100%; flex-direction:column` | 工具卡片布局根（Collapsible）。 | high |
| `tool.summary-trigger` | ToolSummaryTrigger | `group/tool-summary inline-flex max-w-full cursor-pointer item…` | `display:inline-flex; max-width:100%; cursor:pointer` | 工具摘要行（可展开）：图标 + 动作名 + 文件/命令摘要 + 状态。 | high |
| `tool.summary-static` | ToolSummaryStatic | `group/tool-summary cursor-default flex w-full items-center ga…` | `display:flex; width:100%; cursor:default` | 不可展开工具的摘要行。 | high |
| `tool.chevron` | ToolChevron | `size-4 text-foreground-subtlest opacity-0 transition-transfor…` | `width:calc(var(--spacing) * 4); height:calc(var(--spacing) * 4); flex-shrink:0` | 展开箭头：悬停显现，展开旋转 90°。 | high |
| `tool.kind-label` | ToolKindLabel | `font-medium whitespace-nowrap shrink-0` | `flex-shrink:0; --tw-font-weight:var(--font-weight-medium); font-weight:var(--…` | 工具类型标签（如“读取”“终端”）：运行中渐变，结束后灰字。 | high |
| `tool.content` | ToolContent | `text-popover-foreground outline-none` | `color:var(--color-popover-foreground); --tw-outline-style:none; outline-style…` | 工具详情内容容器。 | high |
| `tool.content-pad` | ToolContentPad | `pt-2` | `padding-top:calc(var(--spacing) * 2)` | 工具详情内容顶部 8px 间距。 | high |
| `tool.execute-detail` | ExecuteDetail | `space-y-3 mb-2 rounded-xl border border-border bg-panel px-4 …` | `margin-bottom:calc(var(--spacing) * 2); border-radius:var(--radius-xl); borde…` | 终端/命令工具详情卡片：圆角面板 + 12px 内边距。 | high |
| `tool.execute-command` | ExecuteCommand | `flex items-start gap-2 font-sans text-ui-base text-foreground` | `display:flex; align-items:flex-start; gap:calc(var(--spacing) * 2)` | 命令文本行（代码字体由子组件提供）。 | high |
| `tool.execute-empty` | ExecuteEmptyOutput | `font-mono text-ui-base text-foreground-subtle` | `font-family:var(--font-mono); font-size:var(--text-ui-base); color:var(--colo…` | 无输出提示。 | high |
| `tool.file-link` | ToolFileLink | `inline-flex min-w-0 max-w-full cursor-pointer items-center ga…` | `display:inline-flex; max-width:100%; min-width:calc(var(--spacing) * 0)` | 工具摘要中的可点击文件链接。 | high |
| `tool.file-name` | ToolFileName | `min-w-0 truncate` | `min-width:calc(var(--spacing) * 0); text-overflow:ellipsis; white-space:nowrap` | 文件名截断。 | high |
| `tool.error-trigger` | ToolErrorTrigger | `whitespace-nowrap underline decoration-dotted underline-offse…` | `cursor:help; white-space:nowrap; text-decoration-line:underline` | 失败状态文字：点线下划线 + 帮助光标。 | high |
| `tool.error-popover` | ToolErrorPopover | `flex max-w-96 items-center gap-2` | `display:flex; max-width:calc(var(--spacing) * 96); align-items:center` | 错误详情浮层行：错误文本 + 复制按钮。 | high |
| `tool.error-text` | ToolErrorText | `line-clamp-3 min-w-0 flex-1 whitespace-pre-wrap break-words` | `-webkit-line-clamp:3; -webkit-box-orient:vertical; display:-webkit-box` | 错误文本最多 3 行。 | high |

---

## 5. 组件级语义 CSS（非 Tailwind utility）

这些 class 在 CSS 中有独立规则，且多数带伪元素/属性/动画；是「反混淆」中最接近原始设计意图的部分。

| 原始语义 class | 规则数 | 关键声明 | 用途 |
| --- | --- | --- | --- |
| `.composer-thought-slider` | 3 | `--composer-thought-slider-thumb:var(--color-white); --composer-thought-max-accent:var(--color-blue-500); opacity:.5; …` | 思考等级滑杆容器变量 |
| `.composer-thought-slider-fill` | 7 | `background:var(--color-success); transition:width .12s ease-out; background:var(--color-blue-500); content:""; border…` | 滑杆进度填充 |
| `.composer-thought-slider-thumb` | 2 | `background:var(--composer-thought-slider-thumb); transition:left .12s ease-out; transition:none` | 滑杆拇指 |
| `.composer-thought-slider-stop` | 4 | `background:var(--color-foreground-subtlest); transition:scale .12s ease-out,opacity .12s ease-out; background:var(--c…` | 滑杆刻度点 |
| `.composer-thought-bubble` | 2 | `opacity:0; animation:linear infinite composer-thought-bubble-flow; animation:none` | 滑杆满级气泡 |
| `.composer-thought-bubbles` | 1 | `display:none` | 滑杆满级气泡容器 |
| `.composer-thought-meter-fill` | 1 | `background:var(--color-blue-500)` | 紧凑态思考进度条填充 |
| `.highspeed-model-trigger-label-sweep` | 5 | `--highspeed-model-sweep-width:96px; color:#0000; background-image:linear-gradient(90deg, transparent 0%, transparent …` | 高速模式模型名扫光 |
| `.composer-thought-range` | 5 | `background:0 0; height:32px; appearance:none; background:0 0; background:0 0; height:32px` | 透明原生 range |
| `.highspeed-composer-surface` | 10 | `isolation:isolate; border-radius:16px; background-color:var(--color-input-focused)!important; border-color:#0000!impo…` | 高速模式输入框表面 |
| `.prompt-mention` | 2 | `content:""; background:var(--mention-icon-color,transparent) var(--mention-image,none) center / contain no-repeat; bo…` | 输入框 @ 提及图标 |
| `.tool-call-progress-indeterminate` | 2 | `animation:1.2s ease-in-out infinite tool-call-progress-indeterminate; width:100%; animation:none` | 工具进度不确定态动画 |
| `.markdown-image-loading-shimmer` | 2 | `background-color:var(--color-card); background-image:linear-gradient(135deg, var(--color-card) 0%, var(--color-backgr…` | Markdown 图片加载微光 |
| `.zcode-draft-prompt-waterfall` | 2 | `animation:zcode-draft-prompt-waterfall .26s cubic-bezier(.22, 1, .36, 1) var(--zcode-draft-prompt-waterfall-delay,0s)…` | 首屏推荐提示词入场动画 |
| `.task-title-marquee` | 1 | `will-change:transform` | 任务标题跑马灯 |
| `.animated-gradient-text` | 3 | `background:linear-gradient(90deg, var(--animated-gradient-text-strong) 0%, var(--animated-gradient-text-strong) 34%, …` | 运行中渐变文字 |
| `.text-wrap-phrase` | 2 | `overflow-wrap:break-word; word-break:normal; word-break:auto-phrase` | 短语级换行控制 |
| `.workspace-remote-connecting-breathe` | 1 | `animation:1.8s ease-in-out infinite workspace-remote-connecting-breathe` | 远程工作区连接呼吸动画 |
| `.file-application-more-trigger` | 1 | `transform:rotate(180deg)` | 文件应用更多菜单箭头翻转 |
| `.onboarding-logo-sweep` | 4 | `border-radius:inherit; pointer-events:none; background:conic-gradient(#0000 0deg 240deg,#ffffff14 280deg,#ffffffa6 35…` | 引导页 Logo 扫光 |

关键动画（`@keyframes`）名称：

- `composer-thought-bubble-flow`（滑杆满级气泡）
- `zcode-draft-prompt-waterfall`（首屏推荐提示词入场）
- `tool-call-progress-indeterminate`（工具进度不确定态）
- `markdown-image-loading-shimmer`（Markdown 图片加载）
- `gradient-flow`（`animated-gradient-text` 渐变流动）
- `wf-*`（workflow 卡片/时间线/表情，属工具卡片子域）
- `zcode-loading-pixel` / `zcode-stream-text-in` / `zcode-collapsible-*` 等流式与折叠动画

---

## 6. 状态属性与组合

### 6.1 Shell

| 属性 | 取值 | 影响的样式/行为 |
| --- | --- | --- |
| `data-workspace-shell` | `true` | 根容器；`[data-workspace-shell]:has([data-project-sidebar-panel=navigation][aria-hidden=true])` 隐藏分隔线扩展 |
| `data-workspace-sidebar-panel` | `true` / `navigation` | 侧栏面板；`navigation` 面板参与 `:has()` 规则 |
| `data-workspace-sidebar-resizing` | `true` | 拖拽中：侧栏 `transition-property:opacity`，手柄 after 常显 |
| `data-workspace-conversation-frame` | `true` | 对话面板；滚动条/圆角/边框规则 |
| `data-workspace-side-frame` | `true` | 右侧面板；滚动条/边框规则 |
| `data-workspace-header-variant` | `draft` / `task` | 顶栏底边：draft 透明，task `border-border/50` |
| `data-panel` | `""` | 分栏面板标记（`U8/W8` 分栏库） |
| `data-separator` | `hover` / `active` | 拖拽手柄 after 显现 |

### 6.2 Composer

| 属性 | 取值 | 影响的样式/行为 |
| --- | --- | --- |
| `data-composer-layout` | `stacked` / `inline` | 纵向卡片 vs 横排工具条 |
| `data-composer-compact` | `true` / `icon` | 工具栏折叠：`group-data-[composer-compact=...]` 控制隐藏/尺寸 |
| `data-composer-model-icon` | `true` | 模型触发器折叠为图标 |
| `data-composer-provider-compact` | `true` | 隐藏 provider 前缀 |
| `data-composer-file-attachments-row` | `true` | 文件附件行 |
| `data-composer-context-attachments-row` | `true` | 上下文附件行（评论/引用/技能） |
| `data-highspeed-composer` | `true` | 启用高速模式表面 |
| `data-highspeed-entrance` | `animated` / `restore` | 入场动画/恢复态（`restore` 关闭过渡） |
| `data-phase` | `diffusing` / `handoff` / `stable` | 高速表面边框/辉光阶段 |
| `data-disabled` | `true` | 滑杆 50% 透明、禁用指针 |
| `data-max` | `true` | 滑杆满级，显示气泡层 |
| `data-highspeed` | `true` | 滑杆填充切蓝紫渐变 |
| `data-filled` / `data-hovered` | `true` | 刻度点填充/放大 |

### 6.3 Chat Timeline

| 属性 | 取值 | 影响的样式/行为 |
| --- | --- | --- |
| `data-v4-timeline-scroll` | `true` | 虚拟滚动容器 |
| `data-following` | `true` / `false` | 是否贴底跟随 |
| `data-loading-older` | `true` / `false` | 顶部加载历史 |
| `data-v4-timeline-message-layer` | `true` | 消息层 |
| `data-v4-timeline-content-column` | `true` | 内容列（宽度/transform 过渡） |
| `data-v4-turn-unit` | `true` | 单轮容器；`data-turn-id` 关联回合 |
| `data-v4-running-live-tail` | `true` | 运行中的实时尾部 |
| `data-v4-composer-dock` | `true` | 底部输入框停靠层 |
| `data-v4-user-input-bubble` | `true` | 用户消息气泡 |
| `data-v4-user-input-collapsible-content` | `true` | 长消息折叠内容 |
| `data-reasoning-content` | `true` | 思考内容区 |
| `data-reasoning-content-variant` | `default` / `nested` | 默认左侧竖线缩进 / 嵌套变体 |
| `data-reasoning-scroll-mask` | `none` / `top` / `bottom` / `both` | 思考内容滚动渐隐遮罩 |
| `data-conversation-selectable` | `true` | 可选择文本区域 |

### 6.4 Tool

| 属性 | 取值 | 影响的样式/行为 |
| --- | --- | --- |
| `data-tool-call-id` | 工具调用 ID | 卡片根；定位/动画 key |
| `data-tool-name` | 工具名 | 选择具体渲染器 |
| `data-status` | `running` / `success` / `failed` / ... | 状态标签、错误样式 |
| `data-zcode-tool-stream-animate` | `true` | 流式入场动画 |
| `data-testid="tool-summary-trigger"` | — | 可展开摘要行 |
| `data-testid="execute-detail"` | — | 终端/命令详情面板 |

---

## 7. 关键组件树

完整树、条件分支与状态类见 **`artifacts/component-tree.md`**。这里只给 5 棵主树的根节点：

```
WorkspaceShell [data-workspace-shell]
├─ SidebarPanel [data-workspace-sidebar-panel]
│  └─ aside > WorkspaceSidebarContent > PrimaryNav + TaskRegion + Footer
├─ ResizableHandle [data-testid=resizable-handle]
└─ ContentColumn
   ├─ ConversationFrame [data-workspace-conversation-frame]
   │  ├─ WorkspaceHeader [data-workspace-header-variant]
   │  └─ ChatPage > Timeline > TurnUnit* + ComposerDock
   └─ SidePaneFrame [data-workspace-side-frame]
```

```
ComposerRegion [data-testid=chat-composer-region]
└─ ComposerSurface [chat-composer-input-surface]
   └─ ComposerShell [data-composer-layout]
      ├─ DragOverlay
      ├─ AttachmentRow
      ├─ ComposerEditorWrap > Lexical ContentEditable [data-testid=chat-input]
      └─ ComposerToolbar
         ├─ LeadingActions [data-composer-leading-actions]
         └─ TrailingActions [data-composer-trailing-actions]
```

```
Timeline [data-v4-timeline-scroll]
└─ MessageLayer
   ├─ ContentColumn > TurnUnit [data-v4-turn-unit] > AssistantTurn
   │  ├─ UserInputBubble
   │  ├─ Reasoning
   │  ├─ AssistantTextRow
   │  └─ ToolCallBlock* [data-tool-call-id]
   └─ ComposerDock [data-v4-composer-dock]
```

```
ToolCallBlock [data-tool-call-id]
└─ ToolLayout
   ├─ ToolSummaryTrigger [tool-summary-trigger]
   └─ ToolContent > 家族渲染器
      ├─ shell: ExecuteDetail [execute-detail]
      ├─ file-read/file-write: ToolFileLink
      ├─ search/web-fetch: 结果摘要
      └─ agent/todo/ask-user-question/workflow/...
```

```
Home（空会话）
└─ Timeline.emptyState
   ├─ DraftGreeting [data-v4-draft-greeting]（Logo + 问候）
   ├─ SuggestedPromptsSlot [data-v4-draft-suggested-prompts-slot]
   └─ ComposerDock > ComposerRegion
```

---

## 8. 复现与证据

- CSS 规则解析：`parse_css.py`（临时，已删除；逻辑见本文 §2.1）。
- JS class 扫描：`scan_js.py`（临时，已删除；逻辑见 §2.2）。
- 映射/规范化 CSS 生成：`build_artifacts.py`（临时，已删除；输入为 `css_rules.json` + `js_records.jsonl`）。
- 关键证据偏移（字符偏移）：
  - Shell 根：`JS @5,957,605`（`data-workspace-shell`）
  - 侧栏内容：`JS @2,691,158`（`sLt=(0,Q.memo)`）
  - 主 bundle 输入区：`JS @3,419,441`（`chat-composer-input-surface`）
  - 输入编辑器：`JS @3,142,693`（`function x2t`）
  - 思考滑杆：`JS @3,333,672`（`composer-thought-slider`）
  - 时间线：`JS @4,486,416`（`data-v4-timeline-content-column`）
  - 回合单元：`JS @4,437,719`（`function RCn`）
  - 用户气泡：`JS @3,795,549`（`data-v4-user-input-bubble`）
  - 工具卡片：`JS @4,164,176`（`displayName=ToolCallBlock`）
  - 工具布局：`JS @1,145,709`（`displayName=ToolLayout`）
  - 思考组件：`JS @4,370,741`（`displayName=Reasoning`）
  - 首屏问候：`JS @3,427,797`（`data-v4-draft-greeting`）
  - 推荐提示词：`JS @3,639,296`（`data-v4-draft-suggested-prompts`）

---

## 9. 未解决项

| 项 | 状态 | 说明 |
| --- | --- | --- |
| `chat-composer-region` | 无 CSS 规则 | 仅作为布局查询/测试锚点；宽度/容器查询由父级 `@container/composer` 提供 |
| `not-prose` | 无 CSS 规则 | typography 插件约定；本产物未生成对应规则 |
| `group-*` / `peer-*` / `:has()` 变体 | 仅保留原始选择器 | 无法用单一语义选择器等价表达，见 `components.normalized.css` 的 `raw:` 注释 |
| 动态 `Z(...)` 全部分支 | 未穷举 | 只恢复基础字面量；状态分支在 `extra` / 注释中 |
| lazy chunk 组件 | 抽查 | `previewPane*`、`pptxRenderer*`、图表 chunk 未逐一定义映射 |
| 原组件名/props | 不可恢复 | 压缩后标识符；仅有 19 个 `displayName`（含模板占位）可参考 |
| 运行时 CSS 变量 | 未落 CSS | 由 JS 内联 style 写入，见 §3 第 5 条 |
