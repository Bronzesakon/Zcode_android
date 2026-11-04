# 04 · 工具调用 / 终端 / 编辑 diff / MCP / 子代理 / Artifact 预览 —— Web 3.17.1 全量取证

> **基线**：`E:\Zcode_android\Reference_web\3.17.1\`
> - `assets/index-Dwhx77cK.js`（6,986,617 B）
> - `assets/index-BgVjt5KE.css`（485,230 B）
> - 辅助：`assets/IntlProvider-CneXitqp.js`（中英文案）、`assets/button-P7AG3MDX.js`（按钮尺寸）、`assets/pierre-light-BvNaMxx5.js` / `pierre-dark-C4O2lTUw.js`（代码高亮主题）
>
> **口径**：文中 `JS@123` / `CSS@123` 均为 **UTF-8 字节偏移**（`grep -o -b` / `Buffer.slice` 可复现）。方法为只读字符串切片 + 组件逐行阅读，**未执行应用代码、未在浏览器里点开真实会话**。
> 取证日期：2026-11-03。本文只描述 **网页版 3.17.1** 的事实；「不存在」项也给出否定证据。
> 目标：给原生 Compose 1:1 重写提供可直接抄的 DOM 结构、class 列表、状态表与精确尺寸/颜色/间距。

---

## 0. 结论速览（给壳实现直接抄）

| 项 | 3.17.1 事实 | 证据 |
| --- | --- | --- |
| 行外壳 | 所有工具行共用 `ToolLayout`（`y0e`），不是每个工具独立 DOM | `JS@1142611` |
| 折叠行布局 | `inline-flex items-center gap-2`，`text-ui-base`=**14px**，行内 gap=**8px** | `JS@1141280`、`CSS@153687` |
| 行高 | 未显式设 line-height，继承 `html{line-height:1.5}` → **约 21px**（14×1.5） | `CSS@25121` |
| 图标 | 统一 `size-4`=**16px**；svg 自身类 `text-foreground-subtle`，但父 span 的 `[&_svg]:text-foreground-subtlest` 生成 `.parent svg`（特异性 0-1-1）胜出 → **实际取 subtlest** | `JS@1139566`、`CSS@342121` |
| 状态图标 | **没有**。没有勾、没有转圈、没有成功/失败圆点 | ToolLayout 区间 `text-success`/`text-destructive`/`animate-pulse` 计数 0 |
| 状态动画 | 仅 **运行中** 把 kindLabel 换成 `animated-gradient-text`（4s linear 无限扫光） | `JS@1143xxx`、`CSS@361863` |
| 耗时 | **折叠行不显示耗时**。ToolLayout 区间 `elapsed`/`durationMs` 计数 0 | 否定证据 |
| chevron | `chevron-right` 16px，默认 `opacity-0`，hover 才 `opacity-100`；展开 `rotate-90`，200ms | `JS@1141280` |
| 默认折叠 | **全部折叠**；模块级 `Map aw` 按 `persistOpenKey ?? toolId` 记忆，不落盘 | `JS@1142478` |
| 运行中自动展开 | **没有**（`autoOpen` 仅 pptx 卡片用，工具行传 `!1`） | `JS@4033803` |
| 分组阈值 | **连续 + ≥2 条** 才成组；单条退化为普通行 | `JS@4298612` |
| 分组默认 | explore/terminal 分组**开启**，changes 分组**关闭**；但组本身默认**折叠**（组开关 ≠ 展开态） | `JS@4526xxx`、`JS@1142478` |
| 组内缩进 | `ml-2 space-y-2 border-border border-l pl-3.5` = 8 / 8 / 1px / 14px；子行 `showIcon:false` | `JS@4267724` |
| 终端卡片 | `rounded-xl border border-border bg-panel px-4 py-3`，命令 3lh↔10lh，输出 5lh | `JS@4044154` |
| diff 预览 | `max-h-60 rounded-xl border bg-card`；行号 48px gutter、增删 14%/18% 底色、左 3px 条 | `JS@4027725`、`JS@3950472` |
| MCP 结果 | 短结果 ≤160 字符单行非 JSON → 段落；否则 `max-h-72` CodeBlock | `JS@1149613` |
| 子代理 | bot 图标 + 「子智能体」+ 彩色 mono 名 + `·` + 描述；**不可行内展开**，点击开右侧面板 | `JS@4068311` |
| hook | **不是时间线行**；回合底部 anchor 图标按钮 → Popover 列表 | `JS@4483349` |
| artifact | 主时间线是**静态卡片**：`rounded-lg border-card-border bg-card px-3 py-2`，无点击、无状态 | `JS@4485877` |
| 预览卡片 | `rounded-xl border-card-border bg-card p-3 pr-4`，44×44 图标盒，整卡覆盖按钮 | `JS@4467495` |
| 代码块 | `rounded-xl bg-background`；header `px-3 py-2`；body `p-2 pt-0 pb-3`；内容 `@pierre/diffs` | `JS@990778`、`JS@921294` |
| 缺失 token | `--color-muted` / `--color-outline` / `--color-on-surface*` **在 3.17.1 CSS 中不存在** → `bg-muted/*`、`text-muted-foreground`、`border-outline/60` 全是 no-op | `CSS` 计数 0 |

---

## 1. 证据、口径与设计 token

### 1.1 组件拓扑

```text
ToolCallBlock (Bgn, JS@4268233)
  ├─ 计算 status / displayModel / sourceLabel / childToolList
  ├─ Agn(ce) 选渲染器 (JS@4266314)
  │    ├─ shell            → kcn  (JS@4044154)
  │    ├─ file-write       → ycn  (JS@4032864)
  │    ├─ file-read        → Pcn  (JS@4049127)
  │    ├─ search           → Bcn  (JS@4052234)
  │    ├─ web-fetch        → Lcn  (JS@4050xxx)
  │    ├─ explore          → eln  (JS@4059260)
  │    ├─ agent            → yln  (JS@4068311)
  │    ├─ MCP              → j0e  (JS@1149613)
  │    ├─ changesGroup     → Eln  (JS@4073212)
  │    ├─ executeGroup     → Lpn  (JS@4180033)
  │    └─ default          → f1   (JS@4181xxx)
  └─ ToolLayout (ow → y0e, JS@1142611)
       ├─ Collapsible root  class="w-full flex flex-col"
       ├─ summary row       h0e (JS@1140900)
       │    ├─ left  p0e (JS@1139566): icon / kindLabel / kindDetail / sourceLabel
       │    └─ right m0e (JS@1140259): separator / primary / secondary / diffCount / statusNode
       └─ CollapsibleContent class="text-popover-foreground outline-none" → div.pt-2 → renderContent
```

外层行：

```html
<div class="w-full"
     data-testid="…" data-tool-call-id="…" data-tool-name="…"
     data-status="…" [data-zcode-tool-stream-animate="true"]>
```
`JS@4268xxx`。时间线行包装 `N2` 是 `py-0`（`JS@4485726`），行间距由父级 `flex flex-col gap-4` 提供（16px）。

### 1.2 字号 / 字体 / 圆角 token

| token | 值 | 证据 |
| --- | --- | --- |
| `--ui-font-size` | **14px** | `CSS@418015` |
| `--text-ui-base` | 14px | `CSS@14172` |
| `--text-ui-caption` | 13px | `CSS@14207` |
| `--text-ui-sm` | 12px（14−2） | `CSS@14257` |
| `--text-ui-xs` | 10px（14−4） | `CSS@14302` |
| `--text-ui-lg` / `xl` | 16 / 18px | `CSS@14127` / `CSS@14082` |
| 基础行高 | `html,:host{line-height:1.5}` → 14px 文本约 21px | `CSS@25121` |
| `--font-sans` | `ui-sans-serif, system-ui, sans-serif, "Apple Color Emoji", "Segoe UI Emoji", "Segoe UI Symbol", "Noto Color Emoji"` | `CSS@6131` |
| `--font-mono` | `ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", "Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC", monospace` | `CSS@6258` |
| `rounded` / `md` / `lg` / `xl` / `2xl` | 4 / 6 / 8 / 12 / 16px | `CSS@13075` 区块 |
| 间距单位 | `--spacing` = 4px；`gap-2`=8、`gap-3`=12、`px-3`=12、`px-4`=16、`pl-3.5`=14、`py-2`=8、`py-3`=12 | Tailwind v4 |

> `text-ui-*` 只设 `font-size`，**不设 line-height**；除非元素另有 `leading-*`，否则继承 1.5。

### 1.3 主题 token（4 套）

| token | light | dark | zai-light | zai-dark |
| --- | --- | --- | --- | --- |
| `background` | neutral-50 `#fafafa` | neutral-900 `#171717` | `#f8f8f8` | `#161616` |
| `panel` | neutral-100 `#f5f5f5` | neutral-900 `#171717` | `#ffffff` | `#202020` |
| `card` | white `#ffffff` | neutral-800 `#262626` | `#ffffff` | `#2b2b2b` |
| `border` | `#0a0a0a1a` | `#fafafa1a` | `#0d0d0d1a` | `#ffffff1a` |
| `foreground` | neutral-700 `#404040` | neutral-200 `#e5e5e5` | neutral-800 `#262626` | neutral-300 `#d4d4d4` |
| `foreground-subtle` | `#40404099` | `#e5e5e599` | `#26262699` | `#d4d4d499` |
| `foreground-subtlest` | `#40404066` | `#e5e5e54d` | `#26262666` | `#d4d4d44d` |
| `background-alt` | `#f5f5f599` | `#26262699` | `#fafafab3` | `#e5e5e599` |
| `surface` | `#0a0a0a08` | `#ffffff0d` | `#0d0d0d08` | `#ffffff0d` |
| `surface-hover` | `#0a0a0a0d` | `#ffffff1a` | `#0d0d0d0d` | `#ffffff1a` |
| `brand` | sky-400 `#38bdf8` | sky-500 `#0ea5e9` | `#000000` | `#ffffff` |
| `success` | green-600 `#16a34a` | green-500 `#22c55e` | `#1e8a3e` | `#46bf72` |
| `warning` | yellow-600 `#ca8a04` | yellow-500 `#eab308` | `#e07b00` | `#ff8a30` |
| `destructive` | red-600 `#dc2626` | red-500 `#ef4444` | `#e03131` | `#ff5c5c` |
| `diff-added` | green-600 | green-500 | `#1e8a3e` | `#46bf72` |
| `diff-removed` | red-600 | red-500 | `#e03131` | `#ff5c5c` |
| `input` | white | neutral-800 | `#ffffff` | `#2b2b2b` |

证据：`CSS@14529`（background）、`CSS@16487`（panel）、`CSS@17174`（card）、`CSS@15870`（border）、`CSS@21548`（foreground）、`CSS@21592`（subtle）、`CSS@21828`（subtlest）、`CSS@14572`（background-alt）、`CSS@16567`（surface）、`CSS@16941`（surface-hover）、`CSS@14814`（brand）、`CSS@22103`（success）、`CSS@22292`（destructive）、`CSS@22396`（warning）、`CSS@23054`/`CSS@23096`（diff）；dark 段 `CSS@418895` 起；zai 段 `CSS@433145` / `CSS@440802` 起。

> oklch 值对应的 hex 为 Tailwind 标准调色板近似值；zai 主题是硬编码 hex，精确。

### 1.4 重要负面证据：3.17.1 缺失一批 token

对 `index-BgVjt5KE.css` 做整串计数：

```text
--color-muted:            0
.bg-muted                 0
.text-muted-foreground    0
--color-outline:          0
.border-outline           0
--color-on-surface:       0
--color-on-surface-muted: 0
```

因此以下 JSX 里的 class **不产生任何样式**（背景/边框/弱化色都没有）：

| 位置 | 写了但无效的 class | 实际效果 |
| --- | --- | --- |
| 工具输入区（`Parameters`） | `bg-muted/50` | 无背景 |
| 工具输出区（`Result`） | `bg-muted/50` | 无背景（错误态 `bg-destructive/10` 有效） |
| 内嵌文本预览 | `bg-muted/15` | 无背景（markdown 分支的 `border border-border` 有效） |
| 计划结果 | `border-outline/60 bg-muted/15` | 无边框、无背景 |
| 计划路径 chip | `bg-muted/70` | 无背景 |
| 快照提示 | `bg-muted/40` | 无背景（`border-border/70` 有效） |
| 所有 `text-muted-foreground` 标题 | — | 颜色继承 `foreground` |
| 代码块 wrap 按钮激活态 | `bg-muted` | 无背景 |
| 计划结果文字 | `text-on-surface-muted` / `text-on-surface` | 颜色继承 |

证据：`CSS` 全串计数 0；对照 `bg-background-alt/40`、`bg-destructive/10`、`bg-card`、`bg-panel`、`bg-surface`、`bg-input` 均存在。**Compose 重写不要给这些区域加背景/边框**，否则会比网页「更重」。

---

## 2. 工具折叠行（ToolLayout）

### 2.1 三种 summary 容器

**(A) 可折叠（canToggle=true，无 summaryAction）** —— `JS@1141280`：

```html
<div role="button" tabindex="0" aria-expanded={open}
     aria-label="展开工具详情 / 收起工具详情" title={title}
     class="group/tool-summary inline-flex max-w-full cursor-pointer items-center gap-2
            self-start text-left text-ui-base transition-colors
            focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-input-border-focused">
  <!-- p0e 左半 -->
  <span class="shrink-0 text-foreground-subtlest [&_svg]:text-foreground-subtlest">{icon}</span>
  <span class="tool-summary-kind-label font-medium whitespace-nowrap shrink-0
               {running ? 'animated-gradient-text' : 'text-foreground-subtlest'}">{kindLabel}</span>
  <span class="min-w-0 shrink-0">{kindDetail}</span>
  <span class="shrink-0 rounded border border-border bg-background-alt px-1.5 py-0.5
               text-ui-xs leading-none text-foreground-subtlest">{sourceLabel}</span>
  <!-- m0e 右半 -->
  <div class="tool-summary-content min-w-0 flex max-w-full items-center gap-2 text-foreground-subtlest">
    <span class="shrink-0 text-foreground-subtlest">{separator ·}</span>
    <span class="relative inline-flex min-w-0 items-center gap-2 overflow-hidden align-middle">
      <span class="inline-flex min-w-0 items-center gap-2">{primaryText}{secondaryText}</span>
    </span>
    {trailingText /* diffCount */}
    {statusNode}
  </div>
  <svg class="size-4 text-foreground-subtlest opacity-0 transition-transform transition-opacity
              duration-200 ease-out will-change-transform
              group-hover/tool-summary:opacity-100 shrink-0 rotate-90 opacity-100(open)">
</div>
```

**(B) 不可折叠（canToggle=false）** —— `JS@1142xxx`：

```html
<div data-testid="…" class="group/tool-summary cursor-default flex w-full items-center gap-2
     text-left text-ui-base transition-colors focus-visible:outline-none" title={title}>
  …同样的左右两半…
</div>
```
- **不渲染 chevron**；容器从 `inline-flex/self-start` 变成 `flex w-full`。
- Read / Search / WebFetch / Agent / MCP(pending|stopped) 走这一支。

**(C) summaryAction（整行可点，如 Agent）** —— `JS@1140xxx`：

```html
<div data-testid="…" role="button" tabindex="0" aria-label="在右侧打开"
     class="group/tool-summary inline-flex max-w-full cursor-pointer items-center gap-2
            self-start text-left text-ui-base transition-colors focus-visible:outline-none
            focus-visible:ring-2 focus-visible:ring-input-border-focused" title={title}>
  …左右两半…
</div>
```
- **不渲染 chevron，也不渲染展开内容**（`V = summaryAction !== undefined` 时 `H=false`）。

### 2.2 尺寸 / 间距 / 颜色表

| 项 | 值 | 证据 |
| --- | --- | --- |
| 行内 gap | **8px** (`gap-2`) | `JS@1141280` |
| 基础字号 | **14px** | `text-ui-base` |
| 行高 | 约 **21px**（继承 1.5） | `CSS@25121` |
| 图标 | 16×16 (`size-4`) | 各渲染器 |
| 图标颜色 | **foreground-subtlest**（父级 `[&_svg]` 规则胜出） | `CSS@342121` |
| kindLabel | 14px / **500** / `whitespace-nowrap`；非运行 `foreground-subtlest` | `JS@1143xxx` |
| kindDetail | 14px；默认继承 `foreground`；MCP 显式 `foreground-subtle`；Agent 彩色 mono | 各渲染器 |
| sourceLabel 徽章 | 10px / `leading-none` / `px-1.5 py-0.5` / `rounded` 4px / border + `bg-background-alt` / `foreground-subtlest` | `JS@1139566` |
| 摘要容器 | 14px / `foreground-subtlest` / gap 8；`prioritizePrimaryText` 时加 `flex-1 overflow-hidden` | `JS@1140259` |
| 窄容器 | `prioritizePrimaryText` 时 kindLabel / sourceLabel 在容器 <360px 隐藏（`@max-[360px]/conversation:hidden`） | `JS@1139566` |
| separator | `·` / `shrink-0` / `foreground-subtlest` | `JS@1140259` |
| diffCount | `font-mono leading-none tabular-nums` / `+N` `text-diff-added` / `-N` `text-diff-removed` / gap 4px | `JS@4030111` |
| statusNode | `inline-flex shrink-0 items-center gap-1.5`（6px） | `JS@1145265` |
| chevron | 16px / `foreground-subtlest` / opacity 0→100 / rotate 0→90 / 200ms ease-out | `JS@1141280` |
| 焦点环 | 2px `ring-input-border-focused`（品牌色） | `JS@1141280` |
| 行 hover | **只有 chevron 淡入**；无背景、无文字变色 | 否定证据 |
| 行 active/pressed | **没有** | 否定证据 |

### 2.3 状态表

状态归一化 `Tin`（`JS@3907923`）：

| 行 status | 归一化（AI SDK） | isRunning | 折叠行视觉 |
| --- | --- | --- | --- |
| `pending` | `input-streaming` | **是** | kindLabel 渐变扫光；无状态文字 |
| `in_progress` | `input-available` | **是** | kindLabel 渐变扫光；无状态文字 |
| `completed` | `output-available` | 否 | kindLabel 纯 `foreground-subtlest` |
| `failed` | `output-error` | 否 | kindLabel 纯色 + **状态文字点线下划线 + tooltip** |
| `stopped` | `output-error` | 否 | **无**点线状态文字；MCP 在 secondary 显示 `· 已停止`；组状态为 stopped |
| `denied` | `output-denied` | 否 | 默认不显示状态文字（除非渲染器显式 showStatusLabel） |

- `isRunning = Or(normalized)`，按 3.15.3 同构实现推断为 `input-streaming || input-available`（**未在 3.17.1 里逐字读到函数体，标注为推断**）。
- 状态文案：等待中 / 执行中 / 已执行 / 执行失败 / 已拒绝 / 已停止（`IntlProvider-CneXitqp.js`）。
- **多数行不显示状态文案**：只有 `showStatusLabel || showFailureStatus` 且 `statusLabel != null` 才渲染（`JS@1145xxx`）。实际只有失败时（以及 MCP 的失败/停止 secondary）可见。

### 2.4 失败态（唯一带交互的 summary 元素）

失败时（`showFailureStatus=true` 且 `statusLabel != null`）。若 `statusTooltip` 是非空字符串，则渲染点线 + tooltip；否则只渲染纯文本 `<span class="whitespace-nowrap">{statusLabel}</span>`：

```html
<Tooltip>
  <TooltipTrigger asChild>
    <span class="whitespace-nowrap underline decoration-dotted underline-offset-2 cursor-help">
      执行失败
    </span>
  </TooltipTrigger>
  <TooltipContent side="top" align="start" class="max-w-96">
    <div class="flex max-w-96 items-center gap-2">
      <span class="line-clamp-3 min-w-0 flex-1 whitespace-pre-wrap break-words">{error}</span>
      <button class="ghost icon-md shrink-0" title="复制 / 已复制">
        <svg class="size-3">copy | check</svg>
      </button>
    </div>
  </TooltipContent>
</Tooltip>
```

- 状态文字颜色**继承 `foreground-subtlest`，不是红色**；失败不改变图标/行色。
- tooltip 最大宽 **384px**（`max-w-96`），正文 3 行截断；复制按钮 28×28，图标 12px，复制后 1500ms 复位。
- 失败 tooltip 是 ToolLayout 内**唯一按钮**。

### 2.5 展开容器 / 动画 / 持久化

```html
<div class="w-full flex flex-col" data-state="open|closed">   <!-- Radix Collapsible root -->
  …summary…
  <div class="text-popover-foreground outline-none
              overflow-hidden
              data-[state=open]:animate-collapsible-down
              data-[state=closed]:animate-collapsible-up
              data-[state=closed]:[animation-fill-mode:forwards]">
    <div class="pt-2">   <!-- 8px -->
      {renderContent()}
    </div>
  </div>
</div>
```

| 规则 | 事实 | 证据 |
| --- | --- | --- |
| 初始 | 折叠（`aw` 无记录 → false） | `JS@1142478` |
| 展开态记忆 | 模块级 `Map aw`，key = `persistOpenKey ?? toolId`；**不落盘、不按轮次重置** | `JS@1142478` |
| 点击 | `aw.set(key, open)` | `JS@1145xxx` |
| 动画 | `collapsible-down/up`，**0.2s ease-out**，高度 0 ↔ `--radix-collapsible-content-height` | `CSS@277125`、`CSS@275355`、`CSS@483009` |
| 关闭卸载 | 延迟 **300ms** 卸载内容（播完收起动画） | `JS@1142xxx`（`g0e=300`） |
| autoOpen | 工具行一律 `!1`；仅 pptx 工件卡片用 | `JS@4033803` |
| autoCollapseOnComplete | 仅 Agent 行传 `true`（但 Agent 不可折叠，见 §7.1） | `JS@4068311` |
| forceOpen | 仅权限预览行使用；工具行默认 false | 各渲染器 |
| 内容上边距 | `pt-2` = **8px** | `JS@1142xxx` |

### 2.6 流式入场

- 新 toolId 且 `streamingEntranceActive` 为真时，外层加 `data-zcode-tool-stream-animate="true"`，**1s 后移除**（`Ngn=1e3`）。
- CSS：`[data-zcode-tool-stream-animate=true]{will-change:opacity;animation:.9s cubic-bezier(.16,1,.3,1) both zcode-stream-text-in}`（`CSS@450627`），0.9s 纯淡入。
- 同一 key 只播一次；已播 key Map 上限 800（`JS@4268xxx`）。

### 2.7 摘要换字动画

`explore` / `executeGroup` / `changesGroup` / `agent` 开启 `animateSummaryContent`：

- 只有 `summaryContentKey` 变化才播；300ms 上下滑（旧 `y:-0.8em` 退出，新 `y:0.8em` 进入），`popLayout`。
- 800ms 节流锁；锁期间新变化排队（最多 2 条）；锁结束后丢弃过老项，只取最新。
- `prefers-reduced-motion` 或 `disableSummaryContentAnimation` → 直接替换。
- 证据：`JS@1138xxx`（`DWe` 等价实现）、常量 800/250/2/0.3。

### 2.8 toolName → 图标（3.17.1 实测）

| family / 场景 | 渲染器 | 图标变量 | lucide |
| --- | --- | --- | --- |
| `file-read`（Read） | `Pcn` | `Acn` | **search**（`Ep=Yo('search')`，`JS@382431`） |
| `search`（Grep/Glob/WebSearch/ToolSearch） | `Bcn`/`Vcn` | `Rcn` | **search** |
| `explore` 组 | `eln` | `Ucn` | **search** |
| `file-write`（Write/Edit/ApplyPatch） | `ycn` | `mcn` | **pencil**（`cp=Yo('pencil')`，`JS@376978`） |
| `changesGroup` | `Eln` | `bln` | **pencil** |
| `shell`（Bash/terminal） | `kcn` | `Dcn` | **square-terminal**（`Fp=Yo('square-terminal')`，`JS@386596`） |
| `executeGroup` | `Lpn` | `Ppn` | **square-terminal** |
| `agent`（Task/Agent/SubAgent） | `yln` | `mln` | **bot**（`wd=Yo('bot')`，`JS@339657`） |
| MCP 工具 | `j0e` | `x0e` | **plug**（`fp=Yo('plug')`，`JS@377916`） |
| `web-fetch`（WebFetch） | `Lcn` | `Fcn` | **globe**（`bf=Yo('globe')`，`JS@358020`） |
| artifact 行 | `ZSn` | `cf` | **file**（`cf=Yo('file')`，`JS@354223`） |
| 默认/未知工具 | `f1` | `Rpn` | **wrench**（`Yp=Yo('wrench')`，`JS@394725`） |

> 关键反直觉点：**Read / Grep / Glob / Explore 全部是放大镜**；file-write 与 changes 是铅笔；未知工具是扳手。

---

## 3. 工具分组（explore / execute / changes）

### 3.1 开关与阈值

| 键 | 默认 | 证据 |
| --- | --- | --- |
| `toolGroupingExploreEnabled` | **true** | `JS@4526109` 起 |
| `toolGroupingTerminalEnabled` | **true** | 同上 |
| `toolGroupingChangesEnabled` | **false** | 同上 |

触发（`Svn`，`JS@4298612`）：

1. 过滤 reasoning（按 `messageStreamShowReasoning`）；过滤「shell 且还没解析出命令」的运行行（`gvn`）。
2. 顺序扫描；`cuaGroup` 原样输出；被 subagent 认领的 `subagent` 行跳过。
3. 非 explore 行：
   - changes 开 && `hvn`（file-write 家族）→ 连续收集；**1 条 → 普通行**，≥2 → `changesGroup`；
   - 否则 terminal 开 && `mvn`（shell 且不是 explore-like）→ 连续收集；1 → 普通行，≥2 → `executeGroup`；
   - 否则普通行。
4. explore 行（`pvn`）：
   - explore 开关关 → 普通行；
   - 开 → 连续收集 `pvn` 行；1 → 普通行，≥2 → `exploreGroup`。
5. 组状态 `_vn`：`active ? 'in_progress' : (任一 cancelled ? 'stopped' : 'completed')`；`active = stageTailIsRunning && 组一直延伸到列表末尾`。

> **只有连续才合并**；中间夹一条别的工具就断组。**单条不成组**。

### 3.2 成员判定

| 组 | 成员 |
| --- | --- |
| explore | `file-read` / `search` / `web-fetch` / `explore` 一律 true；`file-write` false；`shell` 需只读命令且无写操作/重定向 |
| terminal | `shell` 且 **不是** explore-like（即命令为空或含写/重定向/非只读） |
| changes | `file-write` 家族 |

explore 的 shell 只读正则（`JS@4294600` 区块）：

```text
只读: \b(rg|grep|find|ls|cat|head|tail|wc|stat|pwd|which|readlink|tree|sed\s+-n|
        get-childitem|gci|dir|get-content|gc|type|select-string|sls|get-location|
        test-path|resolve-path)\b | ^git\s+(status|log|show|diff)\b
写入: \b(sed\s+-i|perl\s+-pi|tee|mv|cp|rm|mkdir|rmdir|touch|truncate|chmod|chown|
        remove-item|del|erase|set-content|add-content|clear-content|out-file|new-item|
        move-item|copy-item|rename-item|set-item)\b |
        ^git\s+(add|commit|rm|mv|checkout|switch|restore|reset|clean|revert|cherry-pick|merge|rebase)\b
重定向: (^|[^\d<])>>?\s*\S | &>\s*\S
循环: \b(for|while)\b（必须同时命中只读）
```

### 3.3 组头 / 子行缩进

三组完全一致（常量 `jgn`，`JS@4267724`）：

```html
<div class="ml-2 space-y-2 border-border border-l pl-3.5 border-border">
  {child rows, showIcon:false}
</div>
```

= 左外边距 **8px**、行间 **8px**、左侧 **1px** 竖线、左内边距 **14px**；子行一律不显示图标。

组头本身就是一个完整 `ToolLayout`（同样的 summary 行、同样的折叠规则、同样的 0.2s 动画）。

### 3.4 三组摘要与计数

| 组 | 图标 | kindLabel | 运行中 primary | 非运行 primary | expandedPrimary | secondary |
| --- | --- | --- | --- | --- | --- | --- |
| explore | search | 查阅 | 最后一个子工具的动作摘要（如「正在读取 + 文件 chip」） | `"{N} 搜索, {M} 文件"`（fetch/list/file 桶，逗号+空格） | 计数桶 | 运行中为最后子工具次摘要；展开后 null |
| execute | square-terminal | 终端 | `正在执行`（span，subtle） | `"{N} 个命令[, {M} 个失败][, {K} 个已停止]"` | 计数桶 | 运行中为最后命令 `<code truncate font-sans>`；展开后 null |
| changes | pencil | 更改 | `"{N} 个文件"`（shrink-0 span） | `"{N} 个文件" + · + 文件 chip 列表` | `"{N} 个文件"` | 运行中为 `· 最后一条 kindLabel + chip + 路径`；展开后 null |

- explore 空桶 → `0 个文件`（`chat.toolCall.explore.emptySummary`）。
- execute 计数用英文逗号 `, ` 连接（中文界面也是 `,`）。
- changes 的 `diffCount` **只在运行中**显示，且 `hideDiffCountWhenOpen:true`（展开后隐藏）。
- changes 文件 chip 列表会按可用宽度测量并折叠成 `+N`（`Tln`，`JS@4072xxx`；chip 间距 8px，尾部预留 24px）。
- 组头 `title`：explore 运行中为最后子工具 title，非运行为 `s.title`；execute 运行中为命令，非运行为 `s.title`；changes 为 `r.title`。

### 3.5 组动画

- 组头摘要换字：同 §2.7（300ms + 800ms 节流）。
- 组展开/收起：同 §2.5（0.2s height）。
- 子行没有 stagger/入场动画。

### 3.6 组默认展开？——纠正一个常见误读

`toolGrouping*Enabled` 是「是否允许分组」，**不是「组是否默认展开」**。3.17.1 的 ToolLayout 默认状态来自 `aw`（空 Map）→ 全部折叠。因此：

- explore / terminal 组**默认存在但折叠**；
- changes 组默认不存在（开关关）；
- 用户点开后才记忆在内存 Map。

3.15.3 文档里「分组默认 explore=开、terminal=开、changes=关」若被理解为「默认展开」是错的；它只描述分组开关。

---

## 4. 终端 / shell

### 4.1 折叠行

`kcn`（`JS@4044154`）：

```text
icon = square-terminal 16
kindLabel = 运行中「正在执行」/ 结束「终端」
primaryText = null（非 office 模式，且有命令时）
secondaryText = <code class="min-w-0 truncate font-sans">{command}</code>
statusLabel = 状态文案；showFailureStatus = status==='failed'
title = toolCall.title
hideSecondaryTextWhenOpen = true
canToggle = true
```

- 行结构：`[终端图标] 终端 [命令 code 截断]`；命令只做 CSS 省略，不做字符截断；hover 看原生 title 全文。
- 命令提取 `kQ`：字符串 trim；字符串数组优先 `-lc` 后一项，否则 join 空格；对象依次 `command/cmd/script/parsed_cmd`。
- 额外剥壳 `AQ`：`/^(?:\/bin\/)?(zsh|bash|sh)\s+-lc\s+([\s\S]+)$/` → 只留真实命令；数组同样找 `-lc`。

### 4.2 展开卡片 DOM

```html
<div data-testid="execute-detail"
     class="space-y-3 mb-2 rounded-xl border border-border bg-panel px-4 py-3">
  <div class="space-y-1">
    <div class="flex items-start gap-2 font-sans text-ui-base text-foreground">
      {command block}
    </div>
  </div>
  {output block | 没有输出。}
</div>
```

- 卡片：`rounded-xl` 12px、`border 1px`、`bg-panel`、`px-4 py-3`（16/12）、`space-y-3` 12px、`mb-2` 8px。
- 卡片外层还有 ToolLayout 的 `pt-2` 8px。
- `bg-panel`：light `#f5f5f5`、dark `#171717`、zai-light `#fff`、zai-dark `#202020`。

### 4.3 命令块（`Tcn`，`JS@4038976`）

```html
<div class="min-w-0 flex-1 grid grid-cols-[1ch_minmax(0,1fr)] gap-x-2 gap-y-1 font-mono">
  <div id={id} data-testid="execute-command" tabindex={expanded?0:undefined}
       class="col-span-2 -mr-3 pr-3 min-w-0 leading-5
              max-h-[3lh] overflow-hidden            <!-- 折叠 -->
              max-h-[10lh] overflow-y-auto">         <!-- 展开 -->
    <div class="flex items-start gap-2">
      <span data-testid="execute-command-prompt" class="shrink-0 text-foreground-subtle">$</span>
      <pre class="min-w-0 flex-1 whitespace-pre-wrap break-words">{command}</pre>
    </div>
  </div>
  <button data-command-preview-toggle
          class="col-start-2 justify-self-start border-0 px-0 font-sans text-ui-sm
                 font-normal text-foreground-subtle focus-visible:underline"
          aria-expanded={expanded} aria-controls={id}>
    展开命令 | 收起
    <svg class="size-3 rotate-180(expanded)">chevron-down</svg>
  </button>
</div>
```

| 项 | 值 |
| --- | --- |
| 字体 | `font-mono`（见 §1.2 栈） |
| 字号 / 行高 | 14px / **20px**（`leading-5`） |
| 列 | `1ch` 提示符 + `minmax(0,1fr)` 命令；`gap-x-2` 8px、`gap-y-1` 4px |
| 折叠最大高 | **3lh = 60px**，`overflow-hidden` |
| 展开最大高 | **10lh = 200px**，`overflow-y-auto` |
| 右侧补偿 | `-mr-3 pr-3`（抵消卡片右内边距 12px，让滚动条贴边） |
| 展开/收起按钮 | 仅在 `scrollHeight > clientHeight+1` 时出现；12px；`chevron-down` 12px；展开旋转 180° |
| 展开时滚动 | `scrollTop = 0` |

### 4.4 输出块（`Ecn`，`JS@4040471`）

```html
<div data-testid="bash-output-scroll" data-following={following}
     class="min-w-0 max-w-full max-h-[5lh] flex-none overflow-auto leading-5" tabindex="0">
  <pre data-testid="bash-result-output | bash-output-preview-full"
       class="whitespace-pre-wrap break-words font-mono text-ui-base leading-5
              text-foreground-subtle">{text}</pre>
</div>
```

| 项 | 值 |
| --- | --- |
| 最大高 | **5lh = 100px**，`overflow-auto` |
| 字体 | mono 14px / 20px |
| 颜色 | `foreground-subtle` |
| 自动跟随 | 运行中 `scrollTop = scrollHeight`；用户上滚（距底 >8px）暂停；回到底部恢复 |
| `data-following` | `true`/`false`，仅供测试/调试 |
| 无输出 | `<p class="font-mono text-ui-base text-foreground-subtle">没有输出。</p>` |
| 错误 | 失败时 `errorText ?? output` 直接作为文本进同一个 `<pre>`；没有独立红色错误块 |

> 运行中优先显示 `raw.outputPreview.fullText`（流式预览）；结束后用完整 output。输出块**没有复制按钮、没有行号、没有 ANSI 颜色解析**（纯文本）。

### 4.5 终端尺寸/颜色总表

| 项 | 值 |
| --- | --- |
| 卡片圆角 | 12px |
| 卡片边框 | 1px `--color-border` |
| 卡片背景 | `--color-panel` |
| 卡片内边距 | 16 / 12px |
| 命令/输出间距 | 12px（`space-y-3`） |
| 卡片下边距 | 8px |
| 命令折叠/展开 | 60 / 200px |
| 输出最大高 | 100px |
| 提示符 | `$`，`foreground-subtle` |
| 命令文字 | `foreground`（卡片内） |
| 输出文字 | `foreground-subtle` |
| 等宽字体 | `--font-mono` |
| 行高 | 20px |

---

## 5. 编辑 / 写入 / diff

### 5.1 折叠行

`ycn`（`JS@4032864`）：

```text
icon = pencil 16
kindLabel = 写入/正在写入 | 删除/正在删除 | 编辑/正在编辑
primaryText = 单文件: 文件 chip；多文件: "{count} 个文件"
secondaryText = 单文件: 缩短路径（subtlest，<360px 容器隐藏）
diffCount = Σ changeStat（+N / -N）
statusLabel = 失败时「执行失败」
showFailureStatus = failed || raw.status==='failed' || errorText
hideDiffCountWhenOpen = 多文件
canToggle = true
prioritizePrimaryText = true
title = toolCall.title
```

文件 chip（`CQ`，`JS@4030777`）：

```html
<button|span class="inline-flex min-w-0 max-w-full items-center gap-1.5
                     text-foreground-subtle [hover:underline]"
        title={fullPath}>
  <img|svg class="size-4 shrink-0" />   <!-- 文件类型图标 16px -->
  <span class="min-w-0 truncate">{basename}</span>
</button|span>
```
- 单文件 chip 可点击（打开 code viewer）时 hover 下划线；不可点击时是 span。
- 多文件时 chip 列表用 `,` 连接（`pcn`）。

diffCount（`SQ`，`JS@4030111`）：

```html
<span class="inline-flex items-center gap-1 whitespace-nowrap font-mono leading-none tabular-nums">
  <span class="inline-flex items-center text-diff-added" title="+N" aria-label="+N">+{N}</span>
  <span class="inline-flex items-center text-diff-removed" title="-N" aria-label="-N">-{N}</span>
</span>
```
- 数字逐字符入场动画（`animateInitial` 时）：`rotateX -90→0`、`y -0.45em→0`、`opacity 0→1`，160ms `cubic-bezier(.4,0,.2,1)`；数字宽 0.66em、`:`/`.` 0.34em、其他 0.7em，高 1.15em。
- `changeStat` 来自 raw 汇总；added/removed 都为 0 → 不渲染。

### 5.2 单文件展开

```html
<div class="space-y-3">
  {patch ? <EditInlineDiffContent /> : null}
  <ToolDetailBody />   <!-- VZ -->
  <SnapshotNotice />
</div>
```

- 有 patch 时，`inlinePreviewOverride = {type:'none'}`，避免再渲染文本预览。
- 无 patch 时走通用详情（输入/输出/内嵌预览）。
- `space-y-3` = 12px。

### 5.3 多文件展开

```html
<div class="ml-2 space-y-2 border-border border-l pl-3.5">
  {每个文件一个独立 ToolLayout 子行，showIcon:false，canToggle:true}
</div>
```

- 外层缩进 8 / 8 / 1px / 14px。
- 每个子行有自己的 kindLabel（按 operationKind）、文件 chip、路径、diffCount。
- 子行 `animateDiffCountOnMount:true`。
- 父行展开后隐藏父 diffCount（`hideDiffCountWhenOpen`）。

### 5.4 Inline diff 预览 DOM

`xQ`（`JS@4027725`）：

```html
<div class="space-y-3">
  <div class="mb-2 max-h-60 overflow-auto rounded-xl border border-border bg-card"
       data-inline-diff-preview="true">
    <LightweightDiff class="h-full bg-card" ... />
  </div>
</div>
```

`Yan`（`JS@3950472`）：

```html
<div class="w-full min-w-0 overflow-auto bg-background h-full bg-card"
     <!-- 根同时带 bg-background 与传入的 bg-card；外层容器已由 bg-card 包裹 -->
     data-lightweight-diff-preview="true"
     data-inline-diff-highlight-language="…"
     data-inline-diff-highlight-theme="github-light|github-dark"
     data-lightweight-diff-highlighted="true|false">
  <div class="min-w-full font-mono leading-relaxed text-foreground [w-max]"
       data-lightweight-diff-scroll-content="true"
       style="font-size:12px">
    <!-- 每个 patch 行 -->
    <div class="flex min-w-full w-full" style="rowStyle">
      <span aria-hidden="true"
            class="sticky left-0 z-[1] w-12 shrink-0 select-none border-r border-border
                   px-2 text-right tabular-nums {text-diff-added|text-diff-removed|text-foreground-subtlest}"
            style="gutterStyle">{index+1}</span>
      <code class="block flex-1 px-3 whitespace-pre|whitespace-pre-wrap break-words">{content}</code>
    </div>
    <!-- 截断提示行 -->
    <div class="px-3 py-1 text-foreground-subtle">Diff 预览已截断：…省略了 {count} 行。</div>
  </div>
</div>
```

| 项 | 值 |
| --- | --- |
| 容器最大高 | **240px**（`max-h-60`），内部滚动 |
| 容器圆角/边框/背景 | 12px / 1px `border` / `bg-card` |
| 字体 | `font-mono` |
| 字号 | **12px**（`codePreviewSettings.fontSizePx` 默认 12） |
| 行高 | `leading-relaxed` = **1.625 → 19.5px** |
| 代码左右内边距 | `px-3` = 12px |
| 行号 gutter | `w-12` = **48px**；`px-2` = 8px；右对齐；`tabular-nums`；`border-r` 1px |
| 行号内容 | **顺序 index+1**，不是源文件真实行号（见 §5.6） |
| 增行底色 | `color-mix(in srgb, var(--color-diff-added) 14%, transparent)` |
| 删行底色 | `color-mix(in srgb, var(--color-diff-removed) 14%, transparent)` |
| 增/删左条 | `box-shadow: inset 3px 0 0 var(--color-diff-added/removed)` |
| 增/删行号底色 | 同色 18% 与 `var(--color-background)` 混合；左条 3px；文字 diff 色 |
| 上下文行号 | `text-foreground-subtlest`；gutter 背景 `var(--color-background)` |
| 上下文行 | 无底色、无左条 |
| 代码文字色 | `text-foreground`（token 高亮时由 shiki 内联色覆盖） |
| 换行 | 默认 `whitespace-pre`（横向滚动）；开启 `wrapLongLines` 后 `pre-wrap break-words` |
| 截断行 | `px-3 py-1` 12/4，`text-foreground-subtle` |

**hunk 头**：`@@ -a,b +c,d @@` 被当作 context 行原样显示（`qan` 只识别 `+`/`-`/空格），没有特殊颜色/折叠；`diff --git`、`index`、`---`、`+++` 在解析阶段被剥掉（`Fk`/`wst`），不显示。

### 5.5 行号、上下文、折叠、统计

| 项 | 事实 |
| --- | --- |
| 行号 | 渲染的是 **patch 行数组下标 +1**（`children:n+1`），不是文件真实行号；hunk 头也占一行 |
| 上下文行 | 以空格开头的行；去掉前导空格后显示；无底色；行号 subtlest |
| 增/删行 | 以 `+`/`-` 开头；去掉标记后显示；底色 + 左 3px 条 + 行号色 |
| 折叠 | **没有 per-hunk / per-file 折叠**；只有整个工具行的折叠；预览超过 240px 内部滚动 |
| 截断 | patch 超过 **800 行**（`ost=800`）时保留首 400 + 尾 399，中间插入截断提示；提示文案 `diff.preview.truncatedLines` |
| 统计 | `+N/-N` 来自 `changeStat`，不是从 patch 现算；颜色 `diff-added/removed`；无「N 行变更」文字 |
| 高亮 | shiki（`github-light`/`github-dark`）；>120,000 字符跳过；语言按文件扩展名/内容推断，失败回退 `diff`/`log` |

### 5.6 路径显示

`Iy(e,t)`（`JS@934442`）：

1. 反斜杠→斜杠、去尾斜杠；
2. 有 workspace 且路径以 workspace 开头 → 去掉 workspace 前缀；
3. 无 workspace 且是相对路径 → **basename**；
4. 绝对路径 → 原样。

- 折叠行主 chip 只显示 **basename**；次摘要显示缩短路径；`title` 是完整路径。
- 次摘要类：`min-w-0 truncate text-foreground-subtlest @max-[360px]/conversation:hidden`（容器 <360px 隐藏）。
- `kindLabel` 在 `prioritizePrimaryText` 时同样 <360px 隐藏。

### 5.7 代码查看器（超出本文范围，给指针）

文件 chip 点击打开 code viewer；其中 diff 用 `@pierre/diffs` 的 `DiffViewer`（`JS@5333800` 区块）：

- 小 patch（≤1200 行、≤180k 字符、≤1200 hunk 行等）→ 轻量 `acn`；
- 大 patch → `DiffViewer`，`h-full w-full overflow-auto`，`data-diff-viewer`。

---

## 6. 读取 / 搜索 / MCP

### 6.1 Read（`Pcn`，`JS@4049127`）

```text
icon = search 16
kindLabel = 正在读取 / 读取
primaryText = 文件 chip（可点击打开 code viewer；hover 下划线）
secondaryText = 缩短路径（subtlest）
canToggle = false
content = null
statusLabel / showFailureStatus = failed
title = toolCall.title
```

- **不可展开**：没有 chevron、没有详情区。
- 路径提取 `Ncn`：`input.parsed_cmd[type=read].path/name` → `filePath/file_path/path/filename` → `raw.rawInput` → `raw` 内容里的 `<path>` 标签。
- 路径是目录时 chip 用目录图标，且不可点开 code viewer。

### 6.2 Search（`Bcn`，`JS@4052234`）

```text
icon = search 16
kindLabel = 正在搜索 / 搜索
primaryText = <span class="truncate">查找 {query} | 列出 {cwd} | 查找</span>
canToggle = false
content = null
statusLabel / showFailureStatus = failed
title = toolCall.title
```

- `zcn` 提取顺序：字符串 input → `parsed_cmd`（`list_files`→列出 cwd；`search/grep/glob`→pattern/query/path）→ `search_query/searchQuery/query/pattern/path/url/prompt/target/name` → 「查找」。
- ToolSearch 变体（`Vcn`）：`kindLabelOverride=''`（不显示 kindLabel），primary 为「搜索工具：{query}」或「搜索工具」，title `ToolSearch`，不显示 snapshot notice。
- **不可展开**。

### 6.3 WebFetch（`Lcn`，`JS@4050xxx`）

```text
icon = globe 16
kindLabel = 正在抓取 / 网页抓取
primaryText = <span class="truncate">{url}</span>
canToggle = false
content = null
statusLabel / showFailureStatus = failed
```

- URL 从 `input.url` 取；空则空字符串。
- **不可展开**。

### 6.4 MCP 折叠行（`j0e`，`JS@1149613`）

```text
icon = plug 16
kindLabel = "MCP"（硬编码，不走 i18n）
kindDetail = 服务名（<span class="text-foreground-subtle">）
primaryText = 工具名（snake_case → Title Case，去掉服务名前缀）
separator = ·
secondaryText = 进度条（in_progress） | "· 已停止"（stopped） | "· 执行失败"（failed） | 无（pending）
statusLabel = failed/stopped 时的 JSX span
showFailureStatus = failed
canToggle = !(pending || stopped)
```

- 工具名美化 `lw`：`trim` → `[-_]` 转空格 → 首字母大写；`A0e` 去掉 `"{server} "` 前缀。
- 服务名 `k0e`：`plugin:xxx` → 取最后一段；否则整串。
- MCP 识别 `O0e`：`raw.display.kind === 'mcp_tool' && serverName` 或工具名前缀 `mcp__...`。
- `pending` / `stopped` **不可展开**（因此 renderContent 里的 pending 段落实际是死代码）。

### 6.5 MCP 展开内容（`JS@1149xxx`）

```html
<div class="mb-2 space-y-3 py-1" data-testid="mcp-expanded-content">
  <!-- 1) 错误 -->
  <section class="space-y-1.5">
    <h4 class="text-ui-base font-medium text-destructive">结果</h4>
    <p class="whitespace-pre-wrap break-words rounded-lg bg-destructive/10
              px-3 py-2 text-ui-base text-destructive"
       data-testid="mcp-error-surface">{error}</p>
  </section>

  <!-- 2) 短结果（≤160 字符、单行、非 JSON） -->
  <p class="break-words rounded-xl border border-border bg-card px-3 py-2
            text-ui-base text-foreground-subtle"
     data-testid="mcp-result-surface">{text.trim()}</p>

  <!-- 3) 长结果 -->
  <div class="max-h-72 overflow-auto rounded-xl border border-border bg-card"
       data-testid="mcp-result-surface">
    <CodeBlock class="bg-card" language="json|log" wrapLongLines
               renderMermaid={false} enableSyntaxHighlighting={isJson}
               headerClass="pl-3 pr-2 pt-2">
      <Header>
        <span class="text-ui-base font-medium text-foreground-subtle">结果</span>
        <Button icon-md ghost title="切换自动换行">wrap</Button>
        <Button icon-md ghost title="复制结果">copy</Button>
      </Header>
    </CodeBlock>
  </div>

  <!-- 4) 调用详情（仅当有 description 或 input） -->
  <Collapsible class="group/details">
    <Trigger asChild>
      <Button variant="ghost" size="sm"
              class="-ml-2 text-foreground-subtlest hover:bg-transparent hover:text-foreground
                     aria-expanded:bg-transparent aria-expanded:text-foreground">
        <svg class="size-3.5 transition-transform group-data-[state=open]/details:rotate-90">chevron-right</svg>
        查看调用详情
      </Button>
    </Trigger>
    <Content>
      <div class="space-y-3 pt-2">
        <section class="space-y-1.5">
          <h4 class="text-ui-sm font-medium text-foreground-subtlest">工具说明</h4>
          <p class="whitespace-pre-wrap break-words text-ui-base text-foreground-subtle">{description}</p>
        </section>
        <section class="space-y-1.5">
          <h4 class="text-ui-sm font-medium text-foreground-subtlest">调用参数</h4>
          <div class="max-h-72 overflow-auto rounded-xl border border-border bg-card">
            <CodeBlock language="json" code={JSON.stringify(input,null,2)} />
          </div>
        </section>
      </div>
    </Content>
  </Collapsible>
</div>
```

| 项 | 值 |
| --- | --- |
| 展开内容外边距 | `mb-2` 8px + `py-1` 4px |
| 区块间距 | `space-y-3` 12px |
| 短结果阈值 | **160 字符**、单行、非 JSON（`S0e=160`） |
| 长结果最大高 | **288px**（`max-h-72`） |
| 错误块 | `rounded-lg` 8px、`bg-destructive/10`、`px-3 py-2`、`text-destructive` |
| 短结果块 | `rounded-xl` 12px、`border`、`bg-card`、`px-3 py-2` |
| 结果标题 | 「结果」（失败时也是「结果」，只是红色） |
| 进度条 | `h-1 w-16`（4×64）、`rounded-full`、`bg-surface-hover`、填充 `bg-brand`；不确定态 1.2s 循环；确定态宽度 200ms |
| 详情按钮 | ghost sm（24 高）、`-ml-2`、14px、chevron 14px |

**空/错误状态**：

| 状态 | 渲染 |
| --- | --- |
| `failed` | 红色「结果」标题 + 错误段落；summary 里 `· 执行失败` 点线下划线 |
| `pending` | **折叠行不显示任何状态**（secondary 为空）；**不可展开**（内容里的 pending 段落不可达） |
| `stopped` | summary 里 `· 已停止`；**不可展开** |
| completed 且无 output | 展开后 `<p class="text-ui-base text-foreground-subtle">{statusLabel}</p>`（「已执行」） |
| completed 有短文本 | 段落 |
| completed 有长文本 | CodeBlock |
| completed 输出是 JSON | 长/短判定仍先看 160 字符；长结果按 `json` 高亮，否则 `log` |

### 6.6 通用工具详情（`VZ`，`JS@3946291`）

渲染顺序：

1. **inlinePreview**
   - `text`：`max-h-60 overflow-auto rounded-xl bg-muted/15`（**无背景**）；markdown 时加 `border border-border px-3 py-3`；非 markdown 用 CodeBlock。
   - `image`：`<img src alt>`，**无 class、无尺寸约束**；加载中/失败显示纯文本「正在加载... / 预览加载失败 / 预览不可用」。
2. **planResult**：`space-y-3 rounded-xl border border-outline/60 bg-muted/15 p-3`（**边框/背景都是 no-op**）；「计划文件」+ 路径 chip `rounded-md bg-muted/70 px-2 py-1 font-mono`（**无背景**）+ Markdown。
3. **输入**（`showInput && input!==undefined`）：
   ```html
   <div class="space-y-2 overflow-hidden">
     <h4 class="font-medium text-muted-foreground text-ui-base uppercase tracking-wide">Parameters</h4>
     <div class="max-h-60 overflow-auto rounded-md bg-muted/50">
       <CodeBlock language="json" code={JSON.stringify(input,null,2)} />
     </div>
   </div>
   ```
   - 标题硬编码英文 `Parameters`；`text-muted-foreground` 是 no-op → 继承前景色。
   - 最大高 240px；无背景。
4. **输出/错误**（`BZ`，`JS@3945547`）：
   ```html
   <div class="space-y-2">
     <h4 class="font-medium text-muted-foreground text-ui-base uppercase tracking-wide">Result | Error</h4>
     <div class="max-h-60 overflow-auto rounded-md text-ui-base [&_table]:w-full
                 bg-muted/50 text-foreground | bg-destructive/10 text-destructive">
       <div class="whitespace-pre-wrap p-3">{error}</div>   <!-- 错误 -->
       <CodeBlock language="json" code={output} />          <!-- 正常 -->
     </div>
   </div>
   ```
   - 标题硬编码 `Result`/`Error`；正常背景 no-op；错误背景有效。
   - 最大高 240px。
5. **kind**（`showKind`）：`<p class="text-ui-base text-muted-foreground">`（颜色 no-op）。
6. **raw fallback**（`inlinePreview.type==='none'` 且未隐藏）：
   ```html
   <pre class="px-4 py-3 rounded-xl bg-surface text-ui-xs mt-1
               text-foreground-subtle max-h-50 overflow-auto">
     {JSON.stringify(toolCall,null,2)}
   </pre>
   ```
   - 10px 字号、200px 最大高、`bg-surface` 有效。

**每类工具的 showInput/showOutput 覆盖**（`kin`，`JS@3907xxx`）：

| 匹配 | showInput | showOutput | showKind | inlinePreview |
| --- | --- | --- | --- | --- |
| 非 write 类 file-write | 有 preview 时 false | 仅错误 | 有 preview 时 false | 取 preview |
| file-read | 有 preview 时 false | 仅错误 | 有 preview 时 false | 取 preview |
| write 类 | 有 preview 时 false | 仅错误 | 有 preview 时 false | 取 preview |
| shell | **false** | output 存在或错误 | false | none |
| search / web-fetch | **false** | output 存在或错误 | false | none |
| goal | **false** | output 存在或错误 | false | none |
| node-repl | false | false | false | none |
| preview 为 image | 有 preview 时 false | 仅错误 | 有 preview 时 false | 取 image |

`planResult` 强制 `showInput:false, showOutput:仅错误`；`errorText` 强制 `inlinePreview:none, showInput:false, showOutput:true, showKind:false`。

### 6.7 限高 / 空态总表

| 区域 | 最大高 | 滚动 | 空态 |
| --- | --- | --- | --- |
| 输入 Parameters | 240px | 内部 | 无（input undefined 不渲染） |
| 输出 Result/Error | 240px | 内部 | 无（无 output/error 不渲染） |
| inlinePreview text | 240px | 内部 | 无 |
| inlinePreview image | 无 | 无 | 纯文本「正在加载... / 预览加载失败 / 预览不可用」 |
| planResult | 无 | 无 | 无 |
| raw fallback | 200px | 内部 | 无 |
| MCP 短结果 | 无 | 无 | 无 |
| MCP 长结果 | 288px | 内部 | 无 |
| MCP 参数 | 288px | 内部 | 无 |
| shell 命令 | 60/200px | 折叠隐藏 / 展开滚动 | 无 |
| shell 输出 | 100px | 内部 | 「没有输出。」 |
| inline diff | 240px | 内部 | 无 |
| agent prompt | 200px | 内部 | 无 |
| agent thought/output | 256px | 内部 | 无 |
| CodeBlock（工具内） | 由外层控制 | 内部 | 无 |
| hook 列表 | 320px | 内部 | 无（无 didExecute 不显示按钮） |

---

## 7. 子代理 / hook / artifact / 图片 / 预览

### 7.1 Agent（子代理合并行，`yln`，`JS@4068311`）

```html
<div class="group/tool-summary inline-flex max-w-full cursor-pointer items-center gap-2
            self-start text-left text-ui-base transition-colors" role="button" tabindex="0"
     aria-label="在右侧打开" title={description}>
  <span class="shrink-0 text-foreground-subtlest [&_svg]:text-foreground-subtlest">
    <svg class="size-4 shrink-0 text-foreground-subtle">bot</svg>
  </span>
  <span class="tool-summary-kind-label font-medium whitespace-nowrap shrink-0
               {running ? 'animated-gradient-text' : 'text-foreground-subtlest'}">子智能体</span>
  <span class="min-w-0 shrink-0">
    <span class="inline-flex max-w-36 items-center truncate font-mono text-ui-base font-medium
                 leading-[1.5] text-sky-700 dark:text-sky-300">{agentName}</span>
  </span>
  <div class="tool-summary-content min-w-0 flex max-w-full items-center gap-2 text-foreground-subtlest">
    <span class="shrink-0 text-foreground-subtlest">·</span>
    <span class="relative inline-flex min-w-0 items-center gap-2 overflow-hidden align-middle">
      <span class="inline-flex min-w-0 items-center gap-2">
        <span class="truncate">{description / last child action}</span>
      </span>
    </span>
  </div>
</div>
```

| 项 | 值 |
| --- | --- |
| 图标 | bot 16 |
| kindLabel | 「子智能体」（`chat.toolCall.agent.fallback`） |
| kindDetail | agent 名，mono 14 / 500 / `leading-[1.5]` / `max-w-36` 144px / truncate |
| 颜色 | sky/cyan/emerald/orange/pink/violet/rose/amber；浅色 700、深色 300 |
| primaryText | 运行中=最后子工具动作摘要；非运行=`description/summary/message` → `input.description` → `subagent_type` → output 摘要 |
| separator | `·` |
| canToggle | **false** |
| summaryAction | 有 childSessionId 时 `{ariaLabel:'在右侧打开', onActivate}` |
| sourceLabel | 显式 `undefined`（**子代理徽章不显示**） |
| autoCollapseOnComplete | true（但不可折叠，无实际效果） |
| 状态 | 运行中 kindLabel 渐变；失败时 `showFailureStatus` + `statusLabel` 可见（点线 + tooltip） |
| 点击 | 整行 → 右侧 side pane tab（`subagent-session:<workspace>:<root>:<child>`） |

**关键**：`canToggle:false` + `forceOpen:false` → ToolLayout **永远不渲染 renderContent**。代码里那段 `ml-2 space-y-3 border-border border-l pl-3.5` 的「Agent prompt / thought / output / 子工具列表 / 后台活动」在时间线上**不可达**（死代码）。子代理详情只能通过右侧面板查看。

### 7.2 Subagent 退化行（`hCn`，`JS@4519323`）

不能与 Agent 工具行配对时：

```html
<div data-row-id="…" data-testid="…">
  <div class="text-ui-sm text-[var(--color-foreground-subtle)]">
    {subagentType} · {status}{summaryText ? ` — ${summaryText}` : ''}
  </div>
</div>
```

- 纯文本、无图标、无折叠、无点击、无截断；行本身无内边距，只靠父级 `gap-4` 分隔。
- 这是时间线上**唯一直接显示 subagent status** 的位置。

### 7.3 Hook（不是行，`GSn`，`JS@4483349`）

**入口**：回合底部操作区，anchor 图标按钮：

```html
<Button variant="ghost" size="icon-sm" aria-label="钩子" data-testid="…"
        class="opacity-0 transition-opacity group-hover/assistant-turn:opacity-100
               focus-within:opacity-100 has-[[data-bot-group-delivery]]:opacity-100">
  <svg class="size-3.5">anchor</svg>
  <span class="sr-only">钩子</span>
</Button>
```

- 24×24 命中区，图标 14px；无可见文字；hover 回合才淡入。
- 显示条件：回合结束 + 至少一条 `execution.didExecute === true`；无 didExecute 整个按钮返回 null。

**Popover**（点击）：

```html
<PopoverContent side="top" align="start" sideOffset="6" collisionPadding="16" sticky="always"
                style="max-height:min(20rem, var(--radix-popover-content-available-height))"
                class="w-80 max-w-[calc(100vw-2rem)] gap-0 overflow-hidden
                       border-popover-border p-0">
  <div class="shrink-0 border-b border-border px-3 py-2">
    <h3 class="text-ui-base font-medium text-foreground">钩子</h3>
  </div>
  <ul class="min-h-0 overflow-y-auto py-0.5">
    <li class="min-w-0 px-3 py-1.5 text-ui-sm">
      <div class="flex min-w-0 items-center gap-2">
        <span class="min-w-0 flex-1 break-words font-mono text-foreground">{eventName}</span>
        <div class="flex min-w-0 flex-wrap items-center justify-end gap-2 text-ui-xs text-foreground-subtle">
          <span>{来源}</span>
          <span class="text-foreground-subtlest">{duration}</span>
          <span class="inline-flex min-w-0 items-center gap-1">
            {statusIcon}{statusLabel}
            <span class="min-w-0 break-words text-warning">：{blockReason}</span>
          </span>
        </div>
      </div>
    </li>
  </ul>
</PopoverContent>
```

| 项 | 值 |
| --- | --- |
| 宽度 | 320px；`max-w-[calc(100vw-2rem)]` |
| 最大高 | `min(20rem, 可用高度)` = 320px |
| 头部 | `px-3 py-2` 12/8，底部 1px border |
| 列表 | `py-0.5` 2px，内部滚动 |
| 行 | `px-3 py-1.5` 12/6，12px |
| eventName | mono 12（继承 `text-ui-sm`），foreground |
| 来源 | 用户 / 工作区 / 插件，10px subtle |
| 耗时 | 10px subtlest；`<1000ms → "{n}ms"`；否则秒（<10s 2 位小数，否则 1 位） |
| 状态图标 | running: loader-circle 14 灰、animate-spin；blocked: shield-alert 14 warning；timedOut: clock-alert 14 warning；cancelled: circle-slash-2 14 subtle；failed: circle-x 14 destructive；completed: **不渲染** |
| 状态文案 | 运行中 / 已完成 / 已阻止 / 失败 / 已取消 / 已超时（completed 实际不出现） |
| blockReason | warning 色，前缀「：」 |

- 只保留 `didExecute` 的执行；按 `hookIndex` 升序。
- `pluginName` / `toolName` / `displayName` / `hookCount` 当前列表项未使用。

### 7.4 Artifact 主时间线（`ZSn`，`JS@4485877`）

```html
<div data-row-id="…" data-testid="…" class="px-4 py-1">
  <div class="flex items-center gap-2 rounded-lg border border-card-border bg-card px-3 py-2">
    <svg class="size-4 shrink-0 text-foreground-subtle" aria-hidden="true">file</svg>
    <div class="min-w-0 flex-1">
      <p class="truncate text-ui-base text-foreground">{displayName}</p>
      <p class="text-ui-sm text-foreground-subtle">{TYPE} · {sizeBytes} bytes</p>
    </div>
  </div>
</div>
```

| 项 | 值 |
| --- | --- |
| 行 | `px-4 py-1` 16/4 |
| 卡片 | `rounded-lg` 8px、`border-card-border`、`bg-card`、`px-3 py-2` 12/8 |
| 图标 | **file** 16px，不按 artifactType 切换 |
| 标题 | 14px foreground，单行省略 |
| 副标题 | 12px subtle，`TYPE · {原始字节数} bytes`（无 KB/MB 换算、硬编码英文单位） |
| 状态 | 无（schema 的 `state` 不读） |
| 点击 | **无**；无 hover、无 role、无 tabindex |
| 动画 | 无 |

### 7.5 Artifact 分享页卡片（`zTn`，`JS@4609937`）

```html
<div data-conversation-share-row-kind="artifact" class="py-1">
  <div class="flex w-full items-center gap-3 rounded-xl border border-card-border bg-card
              p-3 pr-4 text-foreground">
    <div class="flex size-11 shrink-0 items-center justify-center rounded-md bg-background
                text-foreground-subtle">
      <img width="24" height="24" />   <!-- 按扩展名的 material icon -->
    </div>
    <div class="flex min-w-0 flex-1 flex-col gap-1">
      <p class="truncate text-ui-base font-medium leading-5">{displayName}</p>
      <p class="truncate text-ui-base leading-5 text-foreground-subtlest">{label} · {size}</p>
    </div>
    <div data-share-preview-button
         class="flex h-7 shrink-0 items-center overflow-hidden rounded-lg border border-border
                bg-input transition-all hover:border-border-hover">
      <button class="flex h-7 items-center gap-1 px-2 text-ui-sm text-foreground">预览</button>
    </div>
  </div>
</div>
```

- 图标 44×44 盒、24×24 图；按扩展名（pdf/powerpoint/word/table/image/html/markdown/document）。
- 副标题 `label · size`；`mJ`：≥1MB → `x.x MB`（≥10 取 0 位），否则 `ceil(bytes/1024) KB`。
- 预览按钮 28 高、`rounded-lg`、`bg-input`、12px；点击 `window.open(url,'_blank','noopener,noreferrer')`。
- 没有下载按钮。

### 7.6 Assistant 预览卡片（`JS@4467495`）

```html
<div data-preview-card-title="…" data-zcode-stream-animate="true"
     style="--zcode-stream-animation-delay:{n}ms"
     class="relative flex w-full items-center gap-3 rounded-xl border border-card-border
            bg-card p-3 pr-4 text-foreground">
  <button class="absolute inset-0 cursor-pointer rounded-xl
                 disabled:pointer-events-none disabled:cursor-default"
          aria-label="打开卡片 {title}"></button>
  <div class="flex size-11 shrink-0 items-center justify-center rounded-md bg-background
              text-foreground-subtle">
    <img|svg class="size-6" />   <!-- 24px -->
  </div>
  <div class="min-w-0 flex flex-1 flex-col gap-1">
    <div class="truncate text-ui-base font-medium leading-5">{title}</div>
    <div class="truncate text-ui-base leading-5 text-foreground-subtlest">{subtitle}</div>
  </div>
  <div class="relative z-10 shrink-0">{action}</div>
</div>
```

- 圆角 12、边框 card-border、背景 card、`p-3 pr-4` 12/16、gap 12。
- 图标盒 44×44、圆角 6、`bg-background`；图标 24。
- 标题 14/500/20；副标题 14/20/subtlest。
- 整卡覆盖透明按钮；右侧 action 在 `z-10`。
- 入场：`data-zcode-stream-animate` 0.9s 淡入 + 每卡 delay。

### 7.7 图片内嵌预览（工具详情）

`Gan`（`JS@3948xxx`）：

```html
<img src={url} alt={title} />
```

- **没有 class、没有圆角、没有最大宽高、没有边框**；由图片原始尺寸撑开。
- 失败/不可用/加载中只显示纯文本 `<p>`：
  - 正在加载...（`chat.toolCall.loading`）
  - 预览加载失败（`chat.toolCall.previewFailed`）
  - 预览不可用（`chat.toolCall.previewUnavailable`）

### 7.8 Workflow artifact 预览（简，`Ixn`，`JS@4423165`）

- 容器 `absolute inset-0 flex flex-col overflow-hidden p-2.5 [justify-content:safe_center]`（preset）或 `absolute inset-0`。
- 图片：`img.absolute.inset-0.h-full.w-full.object-cover`，`data-preview-mode="image"`。
- CSV：`table.w-full.border-collapse.font-mono.text-ui-caption.tabular-nums`；表头行 `border-border font-sans text-ui-sm font-medium uppercase tracking-wide text-foreground-subtlest`；其余 `border-[var(--color-workflow-rule)]`。
- Markdown：`UV`，`w-full min-w-0 break-words text-foreground`。
- 文本：`pre.m-0.whitespace-pre-wrap.break-words.font-mono.text-ui-caption.text-foreground`。
- 底部渐隐：`absolute inset-x-0 bottom-0 h-[28%]`，`linear-gradient(to bottom, transparent, var(--color-panel))`。

---

## 8. 代码块 / 高亮主题

### 8.1 CodeBlock DOM（`Zb`，`JS@990778`）

```html
<div class="group relative w-full overflow-hidden rounded-xl bg-background text-foreground"
     data-language="{language}"
     style="contain-intrinsic-size:auto 200px;content-visibility:auto">
  {header?}
  <div class="p-2 pt-0 pb-3">
    <div class="h-full w-full overflow-auto" data-language="{language}"
         style="color-scheme:light|dark;
                --diffs-bg:var(--color-card);
                --diffs-light-bg:var(--color-card);
                --diffs-dark-bg:var(--color-card);
                --diffs-font-family:var(--font-mono);
                --diffs-font-size:14px">
      <!-- @pierre/diffs 渲染 -->
    </div>
  </div>
</div>
```

Header（可选，`Jb`）：

```html
<div class="flex items-center justify-between gap-3 px-3 py-2 text-ui-base text-muted-foreground">
  <div class="flex items-center gap-2 min-w-0">
    <img class="shrink-0" width="16" height="16" />   <!-- 按语言合成文件名的图标 -->
    <span class="font-mono truncate lowercase">{language}</span>
  </div>
  <div class="-my-1 -mr-1 flex items-center gap-1">
    <Button icon-md ghost title="自动换行">wrap</Button>
    <Button icon-md ghost title="复制代码">copy</Button>
  </div>
</div>
```

| 项 | 值 |
| --- | --- |
| 外层 | `rounded-xl` 12px、`bg-background`、`overflow-hidden` |
| header 内边距 | `px-3 py-2` 12/8；左右 gap 12 |
| header 字号 | 14px；语言名 `font-mono lowercase truncate` |
| header 按钮 | 28×28（icon-md）、ghost、图标 14px；按钮组 `-my-1 -mr-1` 抵消 |
| body 内边距 | `p-2 pt-0 pb-3` = 左右 8、上 0、下 12 |
| 代码背景 | `--diffs-bg: var(--color-card)`（外层是 background，代码面是 card） |
| 字体 | `--font-mono` |
| 字号 | **14px**（`Zb` 默认 `fontSizePx=14`） |
| 行高 | **20px**（diffs 默认 `--diffs-line-height:20px`） |
| 滚动 | 根 `overflow-auto`；外层 `overflow-hidden`；`scrollbar-gutter:stable` |
| 行号 | 默认 `showLineNumbers=false`；开启后 gutter 最小 3ch、左 padding 2ch、右 2px 背景色分隔 |
| 行内边距 | `[data-line]{padding-inline:1ch}`；classic 指示器时左额外 1ch |
| 复制 | `navigator.clipboard.writeText`；成功 check 图标 2s；标题「复制代码」 |
| 换行 | 默认 `wrapLongLines=false`；按钮切换；激活态 `bg-muted`（**no-op**） |
| Mermaid | `renderMermaid` 默认 true；有预览按钮（放大查看/关闭预览） |

### 8.2 内容渲染（`@pierre/diffs`，`rLe`，`JS@921294`）

- 根：`h-full w-full overflow-auto`；`data-language`。
- options：`disableFileHeader:true`、`disableLineNumbers:!showLineNumbers`、`overflow:'wrap'|'scroll'`、`theme`、`lineHoverHighlight:'disabled'`（工具场景无行选择）。
- 样式变量：`--diffs-font-family:var(--font-mono)`、`--diffs-font-size:{n}px`、`--diffs-bg/light-bg/dark-bg:var(--color-background)`（随后被 `Zb` 覆盖为 `--color-card`）。
- `@pierre/diffs` 内置 CSS 关键值（`JS@781616` 起）：
  - `font-size: var(--diffs-font-size, 13px)`；`line-height: var(--diffs-line-height, 20px)`；
  - `[data-line],[data-column-number],[data-no-newline]{padding-inline:1ch}`（`JS@811207`）；
  - `[data-column-number]{text-align:right;user-select:none;color:var(--diffs-fg-number);padding-left:2ch}`（`JS@813093`）；
  - `[data-code]{...padding-top:var(--diffs-gap-block,8px);...scrollbar-gutter:stable;overflow:scroll clip}`（`JS@800466`）；
  - `[data-file-info]{padding:10px;font-weight:700}`（`JS@785123`）；
  - gutter 分隔为 2px 背景色，不是可见边框。

### 8.3 高亮主题

| 场景 | 主题 | 证据 |
| --- | --- | --- |
| 轻量 diff 预览 | **shiki `github-light` / `github-dark`** | `JS@4026xxx`（`Jsn`） |
| 代码块（CodeBlock） | `@pierre/diffs` 默认 **pierre-light / pierre-dark**（未显式传 theme 时） | `JS@489xxx`（`Qh`） |
| 工具内 JSON/log CodeBlock | 同上；MCP 结果显式 `enableSyntaxHighlighting={isJson}` | `JS@1149xxx` |
| 代码查看器 | `@pierre/diffs`，主题由 codePreviewSettings 决定 | `JS@5333xxx` |

- shiki 高亮超过 **120,000 字符**直接跳过，按纯文本渲染。
- 轻量 diff 的语言由 `zw(path, patch)`：扩展名映射 → shebang（python/bash/sh）→ 含 diff 特征 → `diff`/`log`。
- 高亮 token 以 `<span style="color:...">` 内联输出；无高亮时按纯文本行输出。

### 8.4 代码块 vs inline diff 的差异

| 项 | CodeBlock（`Zb`） | inline diff（`Yan`） |
| --- | --- | --- |
| 字号 | 14px | **12px** |
| 行高 | 20px | **19.5px**（1.625） |
| 行号 | 默认关 | 默认开（settings） |
| gutter 宽 | 3ch 最小 | **48px** |
| 行内边距 | 1ch | **12px**（`px-3`） |
| 背景 | 外层 background / 代码面 card | card + 增删 14%/18% 混合 |
| 左条 | 无 | 增删 3px inset |
| 最大高 | 由外层 | 240px |
| 复制 | 有 | 无 |
| 换行按钮 | 有 | 无（settings 控制） |

---

## 9. 精确尺寸 / 颜色 / 间距总表

### 9.1 间距

| 项 | 值 |
| --- | --- |
| 时间线行间距 | 16px（父级 `gap-4`） |
| 工具行内 gap | 8px |
| 工具行上下内边距 | 0（`py-0`） |
| 展开内容上边距 | 8px（`pt-2`） |
| 组内行间距 | 8px（`space-y-2`） |
| 组缩进 | 左外 8、左内 14、左线 1px |
| 终端卡片 | 16/12，内部 12，下 8 |
| 终端命令/输出 | 12（`space-y-3`） |
| 编辑单文件内容 | 12（`space-y-3`） |
| 编辑多文件 | 8（`space-y-2`）+ 8/14/1 |
| MCP 展开 | 4 上下 + 12 区块 |
| diff 容器 | 下 8（`mb-2`） |
| 代码块 header | 12/8 |
| 代码块 body | 8/0/12 |
| artifact 行 | 16/4；卡片 12/8 |
| 预览卡片 | 12/16 |
| hook 头部 | 12/8；行 12/6 |
| 工具 tooltip | 最大宽 384 |

### 9.2 尺寸

| 项 | 值 |
| --- | --- |
| 工具图标 | 16 |
| chevron | 16 |
| 文件 chip 图标 | 16 |
| artifact 图标 | 16 |
| preview/artifact 分享图标盒 | 44；图标 24 |
| agent 名最大宽 | 144 |
| 源徽章字号 | 10 |
| 行高 | 约 21（14×1.5） |
| 终端命令折叠/展开 | 60 / 200 |
| 终端输出 | 100 |
| 工具输入/输出 | 240 |
| MCP 结果/参数 | 288 |
| inline diff | 240 |
| raw fallback | 200 |
| agent prompt | 200 |
| agent thought/output | 256 |
| hook 弹层 | 320 宽 / 320 高 |
| diff gutter | 48 |
| diff 行号字号 | 12 |
| code block 字号/行高 | 14 / 20 |
| MCP 进度条 | 4×64 |
| 按钮 icon-sm / icon-md | 24 / 28 |
| 状态徽章 | 10 / leading-none |

### 9.3 颜色（关键）

| 用途 | 值 |
| --- | --- |
| 工具图标 | `foreground-subtlest` |
| kindLabel 非运行 | `foreground-subtlest` |
| kindLabel 运行 | 渐变 strong/soft（light `#0a0a0a` / `#0a0a0a38`；dark `#fff` / `#fff3`） |
| 摘要文字 | `foreground-subtlest` |
| 文件 chip | `foreground-subtle` |
| 次路径 | `foreground-subtlest` |
| 源徽章 | `foreground-subtlest` + `bg-background-alt` + `border` |
| diff 增/删 | `--color-diff-added` / `--color-diff-removed` |
| 失败状态文字 | 继承 `foreground-subtlest`（**不是红色**） |
| MCP 错误 | `text-destructive` + `bg-destructive/10` |
| 终端卡片 | `bg-panel` + `border` |
| 终端输出 | `foreground-subtle` |
| 代码块外层 | `bg-background` |
| 代码块代码面 | `bg-card` |
| artifact 卡片 | `bg-card` + `border-card-border` |
| hook warning | `text-warning` |
| hook failed | `text-destructive` |
| MCP 进度 | `bg-brand` on `bg-surface-hover` |

### 9.4 圆角

| 元素 | 圆角 |
| --- | --- |
| 源徽章 | 4 |
| 文件 chip | 无（inline） |
| 工具输入/输出 | 6 |
| artifact 主卡片 | 8 |
| 终端卡片 / diff 容器 / MCP 结果 / 代码块 / 预览卡片 / 分享 artifact / 快照 | 12 |
| MCP 错误块 | 8 |
| 预览按钮 | 8 |
| 进度条 | full |

---

## 10. 网页上本来就没有的东西（不要凭直觉加）

| 直觉项 | 3.17.1 事实 | 证据 |
| --- | --- | --- |
| 状态勾/圆点/转圈 | **没有**（ToolLayout 区间 `text-success`/`text-destructive`/`animate-pulse` 计数 0） | 否定证据 |
| 每行耗时/秒表 | **没有**（`elapsed`/`durationMs` 计数 0） | 否定证据 |
| 成功/失败着色 | 没有；失败只加点线状态文字，图标/行色不变 | §2.4 |
| 行 hover 背景 | 没有；只有 chevron 淡入 | §2.2 |
| 行 active/pressed/ripple | 没有 | 否定证据 |
| 组头计数徽章 | 没有；计数是普通文本 | §3.4 |
| 组子行 stagger 入场 | 没有 | §3.5 |
| Read/Search/WebFetch 展开 | 没有（canToggle=false） | §6 |
| Agent 行内展开 | 没有（canToggle=false + summaryAction） | §7.1 |
| MCP pending/stopped 展开 | 没有（canToggle=false） | §6.4 |
| diff per-hunk/per-file 折叠 | 没有；只有整行折叠 + 240px 滚动 + 800 行截断 | §5.5 |
| diff 真实文件行号 | 没有；是 patch 行下标+1 | §5.5 |
| 终端输出 ANSI 颜色 | 没有；纯文本 `<pre>` | §4.4 |
| 终端输出复制按钮 | 没有 | §4.4 |
| artifact 点击/预览/下载（主时间线） | 没有；纯静态卡片 | §7.4 |
| artifact 状态徽标 | 没有 | §7.4 |
| 图片预览圆角/限高 | 没有；裸 `<img>` | §7.7 |
| 工具输入/输出区背景 | **没有**（`bg-muted/50` token 缺失，no-op） | §1.4 |
| 计划结果边框/背景 | **没有**（`border-outline/60`、`bg-muted/15` no-op） | §1.4 |
| 快照提示背景 | **没有**（`bg-muted/40` no-op） | §1.4 |
| 代码块 wrap 激活背景 | **没有**（`bg-muted` no-op） | §1.4 |
| 工具行源徽章（Agent 自身） | 没有（agent 传 `sourceLabel:undefined`） | §7.1 |
| 子代理首字母头像 | 没有；bot 图标 + 彩色 mono 名 | §7.1 |

---

## 11. 与 `docs/chat/04-toolcall.md` / `05-misc-rows.md` 的差异（不盲信）

### 11.1 对 04-toolcall.md（3.15.3）的修正/补充

| # | 3.15.3 文档说法 | 3.17.1 事实 | 影响 |
| --- | --- | --- | --- |
| 1 | 状态枚举 `inputStreaming/pendingApproval/running/success/error/cancelled` | 3.17.1 行 status 是 `pending/in_progress/completed/failed/stopped/denied`，再归一化为 AI SDK `input-streaming/...` | 状态机要按 3.17.1 重写 |
| 2 | 图标 svg 自身 `text-foreground-subtle` 胜出 | 父 span 的 `[&_svg]:text-foreground-subtlest` 生成 `.parent svg`（0-1-1）胜出 → **实际 subtlest** | Compose 用 subtlest |
| 3 | 分组默认「explore=开、terminal=开、changes=关」被表述为默认展开 | 这是**分组开关**；组本身默认**折叠** | 默认状态别做错 |
| 4 | Agent 展开有 `ml-2 space-y-3...` 内容 | `canToggle:false` + `forceOpen:false` → 该内容**不可达**（死代码）；详情在右侧面板 | 不要实现行内 Agent 详情 |
| 5 | Agent 失败折叠行无可见标记 | 3.17.1 Agent 传了 `statusLabel` + `showFailureStatus` → **失败时可见点线「执行失败」+ tooltip** | 失败态要加 |
| 6 | 工具输入/输出 `bg-muted/50`、预览 `bg-muted/15`、计划 `border-outline/60` | 3.17.1 **这些 token 不存在**，背景/边框都不渲染 | 不要加背景/边框 |
| 7 | shell 命令块无 `-mr-3 pr-3` | 3.17.1 命令容器有 `-mr-3 pr-3`（滚动条贴边） | 右补偿 12px |
| 8 | shell 输出 5lh、命令 3lh/10lh | 一致 | — |
| 9 | MCP 结果短阈值 160、长 288、参数 288 | 一致 | — |
| 10 | 文件写入只描述了通用 patch 预览 | 3.17.1 有完整 **lightweight diff**：240px、12px 字号、48px gutter、14%/18% 底色、3px 左条、800 行截断、行号=下标+1 | 新增一整节 |
| 11 | diffCount 只说了 +N/-N | 3.17.1 有逐字符 160ms rotateX 入场动画、`aria-label`/`role=text` | 可选实现 |
| 12 | MCP 失败 secondary 无 `·` | 3.17.1 失败/停止都带 `·` 前缀 | 文案前缀 |
| 13 | `chat.toolCall.agent.thought` 英文硬编码 | 3.17.1 是 i18n「子智能体思考」 | 文案 |
| 14 | 源徽章 Agent 会显示 | 3.17.1 Agent 显式 `sourceLabel:undefined`；只有 `depth>0 或 parentToolUseId` 且未被 `suppressSourceLabel` 抑制的行才显示 | 徽章规则 |
| 15 | 状态指示器不存在 | 3.17.1 ToolLayout 新增 `statusIndicator` 槽（`inline-flex gap-1.5`），但仅 workflow 草稿使用 | 工具行仍无 |

### 11.2 对 05-misc-rows.md（3.15.3）的修正/补充

| # | 3.15.3 文档说法 | 3.17.1 事实 |
| --- | --- | --- |
| 1 | artifact 主时间线 `rounded-lg bg-card border-card-border px-3 py-2`，图标 file | **一致** |
| 2 | artifact 副标题 `TYPE · {sizeBytes} bytes`，无换算 | **一致** |
| 3 | artifact 无点击/无状态 | **一致** |
| 4 | 分享页 artifact `rounded-xl p-3 pr-4`、44×44 图标盒、预览按钮 | **一致** |
| 5 | subagent 合并进 Agent 行，bot 图标、彩色 mono 名、`·`、`role=button`、无 chevron | **一致** |
| 6 | subagent 失败折叠行无可见标记 | **不成立**：3.17.1 Agent 失败会显示点线状态 |
| 7 | subagent 退化行 `type · status — summary`、12px subtle、无内边距 | **一致** |
| 8 | hook 不是行；回合底部 anchor 按钮；Popover 320 宽 / 320 高；列表只保留 didExecute | **一致** |
| 9 | hook 按钮 `opacity-0` → hover 回合 `opacity-100` | **一致** |
| 10 | hook 状态图标/颜色/文案 | **一致**（running 灰 spinner、blocked/timedOut warning、cancelled subtle、failed destructive、completed 不渲染） |
| 11 | 四种行无行级进入/退出动画 | **一致**（artifact/subagent 无；tool 行有可选 0.9s 流式淡入） |
| 12 | timelineMarker 只有 4/9 有 UI | 本文未复核，沿用 05 的结论；3.17.1 可能新增，需另文 |

### 11.3 3.15.3 文档未覆盖、3.17.1 新增/值得单独记的点

1. **`statusIndicator` 槽**：ToolLayout 新增，位于 statusNode 内、状态文字前，`gap-1.5`；仅 workflow 草稿传值（小灯），工具行不传。
2. **`web-fetch` 家族**：globe 图标、不可展开、explore 组成员。
3. **`ToolSearch` / `LocalToolSearch`**：专用渲染器 `Vcn`，无 kindLabel，primary「搜索工具：…」。
4. **MCP 进度条**：4×64，`bg-brand`，不确定 1.2s；确定 200ms。
5. **lightweight diff**：完整尺寸/颜色/截断/行号语义（见 §5）。
6. **CodeBlock 改用 `@pierre/diffs`**：header/body 结构与 3.15.3 的 CodeBlock 默认值不同；工具内 14px/20px、无行号、无换行。
7. **缺失 token 回归**：`muted/outline/on-surface` 在 3.17.1 CSS 中不存在（见 §1.4）。
8. **changes 组文件 chip 溢出测量**：`+N` 折叠、尾部 24px 预留。
9. **MCP pending/stopped 不可展开**：pending surface 是死代码。
10. **Agent 行内详情不可达**：`canToggle:false` + `summaryAction` 直接短路。

---

## 12. 给 Compose 的移植清单（按优先级）

1. **先做 ToolLayout 外壳**：三种容器（toggle / non-toggle / action）、8px gap、14px 文本、16px 图标、subtlest 色、源徽章 10px、chevron hover 淡入 200ms、0.2s 高度动画、300ms 卸载。
2. **状态机**：pending/running 渐变 kindLabel（4s 扫光）；failed 点线状态 + tooltip + 复制；**不要**做勾/转圈/耗时。
3. **图标映射**：search / pencil / square-terminal / bot / plug / globe / file / wrench；Read/Search/Explore 共用放大镜。
4. **终端卡片**：panel 背景、12px 圆角、16/12 内边距、命令 3lh↔10lh、输出 5lh、mono 14/20、自动跟随。
5. **文件写入**：文件 chip + 路径 + diffCount；单文件 patch 预览 240px；多文件 8/14/1 缩进；diff 行 48px gutter、14%/18% 底色、3px 左条、12px 字号/19.5 行高、800 行截断。
6. **MCP**：MCP 字面标签 + 服务名；短/长结果；288px；错误红块；调用详情折叠；进度条 4×64。
7. **Read/Search/WebFetch**：不可展开；Read 文件 chip；Search 查询；WebFetch URL。
8. **分组**：连续+≥2；8/8/1/14 缩进；子行无图标；组头同 ToolLayout；组默认折叠。
9. **Agent**：bot + 子智能体 + 彩色 mono 名 + `·` + 描述；整行点击开右侧；不做行内详情。
10. **hook**：回合底部 anchor 24 按钮；Popover 320；只 didExecute；状态图标/颜色。
11. **artifact**：主时间线静态卡片；分享页 44 图标盒 + 预览按钮；预览卡片 44 盒 + 覆盖按钮。
12. **代码块**：12px 圆角、header 12/8、body 8/0/12、mono 14/20、card 代码面、复制 2s、换行按钮。
13. **不要实现**：muted 背景/边框、outline 边框、行 hover 背景、行 active、组计数徽章、diff 真实行号、ANSI、输出复制、artifact 点击、图片圆角。

---

## 13. 未验证 / 边界

1. **`isRunning` 的精确函数体**未逐字读到（minified 名冲突）；按 3.15.3 同构与 `Tin` 映射推断为 `input-streaming || input-available`。若服务端把 `pendingApproval` 另设状态，需复核。
2. **图标实际渲染色**按 CSS 特异性推断为 subtlest；未在浏览器 DevTools 里取 computed style。若 lucide 组件用内联 `style` 覆盖 `color`，结果可能不同。
3. **CodeBlock 未显式传 theme 时** `@pierre/diffs` 的默认主题按库常量 `pierre-light/pierre-dark` 推断；未在真实页面确认浅/深切换。
4. **`body`/`html` 的 1.5 行高**是全局规则；若某容器另有 `leading-*`，以容器为准。
5. **MCP `pending` 内容**在代码里存在但不可达；若未来 `canToggle` 条件改变，pending surface 会重新可见。
6. **Agent 行内详情**同理不可达；右侧面板的 DOM 未纳入本文。
7. **workflow artifact 预览**只取证了容器/图片/CSV/文本的表层 class；完整 preset 渲染未逐项展开。
8. **代码查看器（DiffViewer）**只取证了入口与轻量/完整分支，未展开完整 UI。
9. **`prefers-reduced-motion`** 对 `animated-gradient-text` 是否有覆盖未确认；对 collapsible/stream/summary/diff chars 有覆盖。
10. **分享页**与主时间线是两套渲染；本文只覆盖 artifact 分享卡片，其他分享行未复核。
11. **未抓真实会话**：所有结论来自 bundle 静态阅读；服务端是否真的下发 `denied`、`stopped`、`web-fetch`、`mcp_tool` 等组合，需以真实数据复核。

---

## 附录 A · 关键字节偏移索引

| 主题 | 偏移 |
| --- | --- |
| ToolLayout 主体 | `JS@1142611` |
| summary 行（toggle/action/non-toggle） | `JS@1140900`、`JS@1141280`、`JS@1142xxx` |
| 左半（icon/kindLabel/kindDetail/sourceLabel） | `JS@1139566` |
| 右半（separator/primary/secondary/diffCount/status） | `JS@1140259` |
| 展开状态 Map | `JS@1142478` |
| 失败 tooltip | `JS@1143xxx` |
| 分组算法 | `JS@4298612` |
| 组缩进常量 | `JS@4267724` |
| 组状态/合成 | `JS@4298xxx` |
| shell 渲染器 | `JS@4044154` |
| shell 命令块 | `JS@4038976` |
| shell 输出块 | `JS@4040471` |
| file-write 渲染器 | `JS@4032864` |
| diffCount | `JS@4030111` |
| 文件 chip | `JS@4030777` |
| inline diff 容器 | `JS@4027725` |
| inline diff 行/gutter | `JS@3950472` |
| diff 截断常量 | `JS@1758855` |
| 通用详情 | `JS@3946291` |
| 输入区 | `JS@3945163` |
| 输出区 | `JS@3945547` |
| Read | `JS@4049127` |
| Search | `JS@4052234` |
| Explore | `JS@4059260` |
| MCP | `JS@1149613` |
| MCP 进度 | `JS@995029` |
| Agent | `JS@4068311` |
| Changes 组 | `JS@4073212` |
| Execute 组 | `JS@4180033` |
| ToolCallBlock | `JS@4268233` |
| 渲染器分派 | `JS@4266314` |
| 状态归一化 | `JS@3907923` |
| displayModel | `JS@3910157` |
| artifact 主行 | `JS@4485877` |
| artifact 分享 | `JS@4609937` |
| 预览卡片 | `JS@4467495` |
| hook 按钮/弹层 | `JS@4483349` |
| subagent 退化行 | `JS@4519323` |
| CodeBlock | `JS@990778` |
| CodeBlock 内容 | `JS@921294` |
| diffs CSS 默认 | `JS@781616`、`JS@811207`、`JS@813093`、`JS@800466` |
| 代码预览设置默认 | `JS@77528` |
| 渐变文字 CSS | `CSS@361863` |
| 折叠动画 CSS | `CSS@277125`、`CSS@275355`、`CSS@483009` |
| 进度条 CSS | `CSS@449014` |
| 流式淡入 CSS | `CSS@450627` |
| text-ui 尺寸 | `CSS@153687`、`CSS@14257`、`CSS@14302` |
| 基础行高 | `CSS@25121` |

---

## 附录 B · 文案表（zh-CN）

| key | 文案 |
| --- | --- |
| `chat.toolCall.kind.read` | 读取 |
| `chat.toolCall.read.reading` | 正在读取 |
| `chat.toolCall.read.read` | 已读取 |
| `chat.toolCall.kind.search` | 搜索 |
| `chat.toolCall.search.searching` | 正在搜索 |
| `chat.toolCall.search.find` | 查找 |
| `chat.toolCall.search.findWithQuery` | 查找 {query} |
| `chat.toolCall.search.list` | 列出 |
| `chat.toolCall.search.listIn` | 列出 {cwd} |
| `chat.toolCall.search.tools` | 搜索工具 |
| `chat.toolCall.search.toolsWithQuery` | 搜索工具：{query} |
| `chat.toolCall.kind.webFetch` | 网页抓取 |
| `chat.toolCall.webFetch.fetching` | 正在抓取 |
| `chat.toolCall.kind.edit` | 编辑 |
| `chat.toolCall.kind.write` | 写入 |
| `chat.toolCall.kind.delete` | 删除 |
| `chat.toolCall.edit.editing` | 正在编辑 |
| `chat.toolCall.edit.writing` | 正在写入 |
| `chat.toolCall.edit.deleting` | 正在删除 |
| `chat.toolCall.edit.multipleFiles` | {count} 个文件 |
| `chat.toolCall.kind.terminal` | 终端 |
| `chat.toolCall.execute.running` | 正在执行 |
| `chat.toolCall.execute.execute` | 执行 |
| `chat.toolCall.execute.conciseCompleted` | 已运行命令 |
| `chat.toolCall.execute.ran` | 已执行 |
| `chat.toolCall.execute.expandCommand` | 展开命令 |
| `chat.toolCall.execute.collapseCommand` | 收起 |
| `chat.toolCall.execute.noOutput` | 没有输出。 |
| `chat.toolCall.executeGroup.label` | 终端 |
| `chat.toolCall.executeGroup.command.one/other` | {count} 个命令 |
| `chat.toolCall.executeGroup.failed` | {count} 个失败 |
| `chat.toolCall.executeGroup.stopped` | {count} 个已停止 |
| `chat.toolCall.explore.label` | 查阅 |
| `chat.toolCall.explore.emptySummary` | 0 个文件 |
| `chat.toolCall.explore.bucket.search` | 搜索 |
| `chat.toolCall.explore.bucket.list` | 列表 |
| `chat.toolCall.explore.bucket.file` | 文件 |
| `chat.toolCall.explore.bucket.fetch` | 次网页抓取 |
| `chat.toolCall.explore.bucket.items` | 项 |
| `chat.toolCall.changesGroup.label` | 更改 |
| `chat.toolCall.changesGroup.file.one/other` | {count} 个文件 |
| `chat.toolCall.changesGroup.tool.one/other` | {count} 个工具 |
| `chat.toolCall.agent.fallback` / `label` | 子智能体 |
| `chat.toolCall.agent.prompt` | 提示词 |
| `chat.toolCall.agent.thought` | 子智能体思考 |
| `chat.toolCall.agent.output` | 子智能体输出 |
| `chat.toolCall.agent.openInSidePane` | 在右侧打开 |
| `chat.toolCall.agent.backgroundProcess` | 后台 Agent 过程 |
| `chat.toolCall.agent.backgroundLaunch` | 启动 |
| `chat.toolCall.agent.backgroundActivity` | 活动 |
| `chat.toolCall.agent.outputFile` | 输出文件 |
| `chat.toolCall.mcp.callDetails` | 查看调用详情 |
| `chat.toolCall.mcp.result` | 结果 |
| `chat.toolCall.mcp.copyResult` | 复制结果 |
| `chat.toolCall.mcp.wrapLines` | 切换自动换行 |
| `chat.toolCall.mcp.description` | 工具说明 |
| `chat.toolCall.mcp.parameters` | 调用参数 |
| `chat.toolCall.expandDetails` | 展开工具详情 |
| `chat.toolCall.collapseDetails` | 收起工具详情 |
| `chat.toolCall.copyError` | 复制 |
| `chat.toolCall.copyError.copied` | 已复制 |
| `chat.toolCall.status.pending` | 等待中 |
| `chat.toolCall.status.running` | 执行中 |
| `chat.toolCall.status.completed` | 已执行 |
| `chat.toolCall.status.failed` | 执行失败 |
| `chat.toolCall.status.denied` | 已拒绝 |
| `chat.toolCall.status.stopped` | 已停止 |
| `chat.toolCall.snapshot.notice` | 该工具有 {fields} 个字段被裁剪，当前显示预览 {previewBytes} / {fullBytes} |
| `chat.toolCall.snapshot.loadFull` | 加载完整工具数据 |
| `chat.toolCall.snapshot.loading` | 正在加载... |
| `chat.toolCall.snapshot.retry` | 加载失败，重试 |
| `chat.toolCall.planFile` | 计划文件 |
| `chat.toolCall.loading` | 正在加载... |
| `chat.toolCall.previewFailed` | 预览加载失败 |
| `chat.toolCall.previewUnavailable` | 预览不可用 |
| `chat.toolCall.toolCall` | 工具调用 |
| `chat.toolCall.result` | 结果 |
| `chat.hooks.label` | 钩子 |
| `chat.hooks.source.user` | 用户 |
| `chat.hooks.source.project` | 工作区 |
| `chat.hooks.source.plugin` | 插件 |
| `chat.hooks.state.running` | 运行中 |
| `chat.hooks.state.completed` | 已完成 |
| `chat.hooks.state.blocked` | 已阻止 |
| `chat.hooks.state.failed` | 失败 |
| `chat.hooks.state.cancelled` | 已取消 |
| `chat.hooks.state.timedOut` | 已超时 |
| `codeBlock.copyCode` | 复制代码 |
| `codeBlock.wrapLines` | 自动换行 |
| `codeBlock.mermaid.openPreview` | 放大查看 |
| `codeBlock.mermaid.close` | 关闭预览 |
| `codeBlock.mermaid.ariaLabel` | Mermaid 图表 |
| `diff.preview.truncatedLines` | Diff 预览已截断：为保持界面流畅，省略了 {count} 行。 |
