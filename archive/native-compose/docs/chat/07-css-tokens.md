# 07 — 对话详情页 CSS 令牌与动画（Web 3.15.3）

> 只读取证，未修改任何代码。
> 数据源：
> - `E:\Zcode_android\archive\pre-3.16.1-auth\Reference_web\3.15.3\assets\index-BD2BL4Cc.css`（455,351 B）
> - `E:\Zcode_android\archive\pre-3.16.1-auth\Reference_web\3.15.3\assets\index-BGa3KiRe.js`（6,592,643 B，class 名来源）
> - `E:\Zcode_android\design\tokens\*.json`（对照；注意其 light/dark 解析混入了 `theme-zai-*` 皮肤，本文数值均重新从默认 `:root` / `.dark` 解析）
>
> 偏移写法：`css:字节` = CSS 文件字节偏移；`js:字节` = JS 文件字节偏移；无偏移者给出 CSS 选择器。

---

## 0. 结论速览（Compose 先看这一节）

对话详情页在 DOM 上的主干是：

```
chat-composer-region (@container/composer)
└─ [data-v4-timeline-scroll]  ← 滚动容器，同时是 @container/conversation
   ├─ [data-v4-timeline-message-layer]
   │  ├─ [data-v4-timeline-content-column] (历史虚拟列表)
   │  ├─ [data-v4-running-live-tail]        (正在跑的 turn)
   │  └─ [data-v4-pending-guide-list]       (排队 steer)
   └─ [data-v4-composer-dock] → composer
```

- 页面字体基准：`--ui-font-size: 14px`（`css:396924`，用户可在 12–20px 调整），`text-ui-base = 14px`，`text-ui-sm = 12px`，`text-ui-caption = 13px`，`text-ui-lg = 16px`，`text-ui-xl = 18px`，`text-ui-xs = 10px`，`text-ui-2xs = 9px`（`css:11189` 起）。
- 对话正文：14px / 24.5px（`leading-[1.75]`）/ 字距 `0.025em`（`js:1701334`）。
- 主背景：亮 `#FAFAFA`，暗 `#171717`；正文：亮 `#404040`，暗 `#E5E5E5`。
- 用户气泡：圆角 12dp、右上角 2dp、内边距 16×12dp、1px 边框、`surface` 底色（`js:2760717`）。
- 工具调用摘要：14px，图标/文字 gap 8dp，展开内容 padding-top 8dp（`js:873526`、`js:875504`）。
- 推理块：14px，最大高 240dp，左竖线 1px + 左内边距 14dp + 左外边距 8dp（`js:3328138`）。
- 代码块：圆角 12dp、1px 边框、外间距 16dp、字号 12px、行高 20px（`js:731040`、`js:1703591`）。
- Composer：圆角 16dp、1px 边框、内边距 12dp、gap 12dp（`js:2086052`）。
- 动画：流式文字 900ms `cubic-bezier(.16,1,.3,1)`；进度条 1200ms；渐变思考文字 4000ms；折叠 200/300ms；光标 1000ms step-end。

---

## 1. 对话时间线相关 class 完整清单与声明

### 1.1 时间线容器

| 位置 | class（原样） | 解析后的关键声明 |
|---|---|---|
| 滚动容器 `js:3451675`（`data-v4-timeline-scroll`） | `min-h-0 flex-1 overflow-x-hidden overflow-y-auto [scrollbar-gutter:stable] [--markdown-table-layout-left-inset:16px] [--markdown-table-layout-right-inset:16px] max-md:[--markdown-table-layout-left-inset:8px] max-md:[--markdown-table-layout-right-inset:8px]` + 条件 `!overflow-y-hidden` / `min-w-0` / `@min-[864px]/conversation:[--markdown-table-layout-left-inset:48px]` | `min-height:0; flex:1 1 0%; overflow-x:hidden; overflow-y:auto; scrollbar-gutter:stable;` 表格左右 inset 16px（<768px 为 8px，容器 ≥864px 左 48px） |
| 消息层 `js:3452712` | `relative w-full flex-1 [mask-repeat:no-repeat] [-webkit-mask-repeat:no-repeat]` | `position:relative; width:100%; flex:1 1 0%; mask-repeat:no-repeat`（滚动淡出蒙版由 JS 写入 `mask-image`，`js:3440570`） |
| 内容列（历史）`js:2709133` / `js:2708949` | `w-full @min-[864px]/conversation:w-[calc(100%_-_6rem)] @min-[864px]/conversation:max-w-4xl @min-[1280px]/conversation:w-[calc(100%_-_24rem)] @min-[1280px]/conversation:max-w-6xl` | 宽度 100% → ≥864px `calc(100% - 96px)`、max 896px → ≥1280px `calc(100% - 384px)`、max 1152px |
| 内容列偏移 `js:2709317` | `@min-[1280px]/conversation:-translate-x-42` | ≥1280px 时 `translateX(-168px)` |
| 空态居中列 `js:2708934` | `max-w-2xl` | `max-width:672px` |
| 列过渡 `js:3454484` | `transition-[width,max-width,transform] duration-150 ease-out @min-[1280px]/conversation:transition-[transform]` | 150ms `cubic-bezier(0,0,.2,1)`；≥1280px 只过渡 transform |
| 外层 `js:3454484` 附近 | `relative flex min-h-0 flex-1 flex-col` | `position:relative; display:flex; min-height:0; flex:1; flex-direction:column` |

### 1.2 Turn（一问一答单元）

| 位置 | class | 解析 |
|---|---|---|
| `js:3392393`（`<section data-turn-id>`） | `relative mx-auto flex w-full flex-col gap-5 px-4 @md/conversation:px-6 pb-5` + `pt-0` 或 `pt-14` + 条件 `highspeed-animated-gradient-text` | `position:relative; margin-inline:auto; display:flex; width:100%; flex-direction:column; gap:20px; padding-inline:16px; padding-bottom:20px;` 容器 ≥448px 时 `padding-inline:24px`；首 turn `padding-top:0`，其余 `padding-top:56px` |
| `js:3392661` | `group/assistant-turn flex w-full flex-col gap-5` | `display:flex; width:100%; flex-direction:column; gap:20px` |
| 行组 `js:3375615` | `flex flex-col gap-4` | `display:flex; flex-direction:column; gap:16px` |
| 待发送 steer 列表 `js:3396423` | `flex flex-col gap-5 px-4 pt-5` | gap 20px、padding 16px、padding-top 20px |

### 1.3 行（Row）

| 位置 | class | 解析 |
|---|---|---|
| 行壳 `js:3335302` `TZ` | 仅 `data-row-id`，className 由调用方传入 | 无默认样式 |
| 用户行 `js:3352150` | `group/user-row flex flex-col items-end` | `display:flex; flex-direction:column; align-items:flex-end` |
| 助手行 `js:3358717` | `group/assistant-row` | 无布局声明；子层 `w-full text-ui-base`（14px） |
| 用户消息气泡 `js:2760717` | `flex max-w-full flex-col gap-2 rounded-xl rounded-tr-xs border border-border bg-surface px-4 py-3 text-ui-base text-foreground @min-[624px]/conversation:max-w-xl` | `display:flex; max-width:100%; flex-direction:column; gap:8px; border-radius:12px; border-top-right-radius:2px; border:1px solid var(--color-border); background:var(--color-surface); padding:12px 16px; font-size:14px; color:var(--color-foreground);` 容器 ≥624px 时 `max-width:576px` |
| 用户附件区 `js:3352830` | `mb-2 flex max-w-xl flex-col items-end gap-2` | margin-bottom 8px、max-width 576px、flex column、align-end、gap 8px |
| 用户状态/来源 `js:3354301` | `mt-1 text-right text-ui-sm text-foreground-subtlest` | margin-top 4px、右对齐、12px、subtlest |
| 消息操作条 | `mt-1` + `opacity-0 transition-opacity group-hover/user-row:opacity-100 focus-within:opacity-100`（compact 模式 `opacity-100`） | 默认透明，hover/focus 淡入，150ms |
| 助手操作条 `js:3392700` 附近 | `flex items-center gap-1` + 同样的 hover 显隐 | gap 4px |

### 1.4 推理（Reasoning）

| 位置 | class | 解析 |
|---|---|---|
| 触发器 `js:3325757` | `group/reasoning inline-flex max-w-full min-w-0 items-center gap-2 self-start text-ui-base transition-colors` | inline-flex、max-width 100%、min-width 0、gap 8px、self-start、14px、颜色过渡 150ms |
| 内容外层 `js:3328138` | `max-h-60 space-y-2 overflow-auto text-ui-base text-foreground-subtlest` + `ml-2 border-border border-l pl-3.5`（`variant=default`） | `max-height:240px; overflow:auto;` 子项间距 8px；`margin-left:8px; border-left:1px solid var(--color-border); padding-left:14px;` 字号 14px、颜色 subtlest |
| 文本内层 `js:3328138` | `min-w-0 whitespace-pre-wrap break-words text-foreground-subtlest` | 预换行、断词 |
| 包裹层 | `pt-3` | padding-top 12px |
| 流式标题 | `animated-gradient-text font-medium` | 见 §3 渐变动画 |

### 1.5 工具调用（Tool call）

| 位置 | class | 解析 |
|---|---|---|
| 工具块外壳 `js:3126478` | `w-full` + `data-tool-call-id` / `data-status` / `data-zcode-tool-stream-animate` | width 100% |
| ToolLayout 根 `js:875504` | `w-full flex flex-col` | 宽 100%、纵向 flex |
| 摘要触发器 `js:873526` | `group/tool-summary inline-flex max-w-full cursor-pointer items-center gap-2 self-start text-left text-ui-base transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-input-border-focused` | inline-flex、max-width 100%、cursor pointer、gap 8px、self-start、14px；focus ring 2px `--color-input-border-focused` |
| 展开内容外层 `js:875504` | `text-popover-foreground outline-none` | 文字色 popover-foreground、无 outline |
| 展开内容内层 `js:875547` | `pt-2` | padding-top 8px |
| 运行中标签 | `font-medium whitespace-nowrap shrink-0` + `animated-gradient-text` | 14px 渐变文字 |
| 进度条 `js:736550` / `js:736857` | 容器 `inline-flex min-w-0 items-center gap-2`；轨道 `relative inline-block h-1 w-16 shrink-0 overflow-hidden rounded-full bg-surface-hover`；指示 `tool-call-progress-indeterminate absolute inset-y-0 w-1/3 rounded-full bg-brand`；文本 `min-w-0 truncate text-ui-sm text-foreground-subtle` | 轨道高 4px、宽 64px、圆角 full；指示条宽 1/3、品牌色；文本 12px |
| 工具状态/子行 | `text-ui-sm text-[var(--color-foreground-subtle)]`（`js:3336xxx`） | 12px、subtle |
| AskUserQuestion 内容 `js:3120788` | `ml-2 space-y-2 border-l border-border pl-3.5` | 与推理相同的左竖线：ml 8px、border-left 1px、pl 14px、子项 gap 8px |
| 问题文本 | `text-ui-base font-medium leading-5 text-foreground` | 14px/20px、medium |
| 回答文本 | `text-ui-base leading-5 text-foreground-subtle` | 14px/20px、subtle |
| Artifact 行 `js:3335441` | 行 `px-4 py-1`；卡片 `flex items-center gap-2 rounded-lg border border-card-border bg-card px-3 py-2` | 行 padding 16/4；卡片圆角 8、边框 1px、padding 12/8、gap 8 |
| 时间线标记 `js:3361700` | `flex w-full items-center gap-3 px-4 py-2 text-ui-base text-[var(--color-foreground-subtle)]` | 宽 100%、gap 12px、padding 16/8、14px |
| 标记分隔线 | `h-px min-w-8 flex-1 bg-border/50` | 1px 高、最小 32px、50% 边框色 |

### 1.6 Markdown 正文

| 位置 | class | 解析 |
|---|---|---|
| 根 `js:1701334` | `size-full text-ui-base leading-[1.75] tracking-wide [&>*:first-child]:mt-0 [&>*:last-child]:mb-0` + `whitespace-pre-wrap break-words` | 14px、行高 24.5px、字距 0.025em（0.35px）、首尾外边距归零 |
| 标题 `js:1691231` | h1 `mt-6 mb-4 text-ui-xl font-semibold`；h2 `mt-6 mb-4 text-ui-lg font-semibold`；h3/h4 `mt-6 mb-4 text-ui-base font-semibold`；h5 `… font-medium`；h6 `… font-normal` | 上 24px / 下 16px；18/16/14px |
| 段落 `js:1665203` | 无 class（继承根） | 14px/24.5px |
| 强调 | `font-medium` | 500 |
| 引用 `js:1632584` 前 | `my-4 border-border border-l-2 pl-3 text-foreground-subtle [&_p]:my-0 [&_p+p]:mt-2` | 上下 16px、左 2px 边框、左内边距 12px、subtle |
| 无序列表 | `my-3 list-outside list-disc space-y-1.5 pl-5 marker:text-foreground-subtlest [&_ul]:my-1.5 [&_ol]:my-1.5` | 上下 12px、子项间距 6px、左内边距 20px |
| 有序列表 | `my-3 list-inside list-decimal space-y-1.5 pl-0 marker:text-foreground-subtlest [&_ul]:my-1.5 [&_ol]:my-1.5` | 上下 12px、子项间距 6px |
| 列表项 | `pl-1 [&>p]:my-0 [&>p]:inline` | 左内边距 4px |
| 行内代码 `js:1703432` | `rounded-md bg-markdown-inline-code/50 mx-0.5 px-1.5 py-0.5 font-mono text-ui-sm` | 圆角 6px、底色 `#E5E5E580`/`#40404080`、左右外边距 2px、padding 2px 6px、等宽 12px |
| 代码块（markdown 内）`js:1703591` | `my-4 border border-border bg-card` | 上下 16px、1px 边框、卡片底色 |
| 表格外框 `js:1646814` | `my-0 flex min-w-0 flex-col gap-2`；内框 `group/markdown-table-frame relative w-full`；框体 `relative w-full overflow-hidden rounded-xl border border-border`；滚动 `!scrollbar-hide w-full overflow-x-auto overflow-y-visible`；表 `w-max min-w-full border-separate border-spacing-0 text-ui-base` | 圆角 12px、1px 边框、表宽 max-content 且至少 100%、14px |
| 表头 `js:1649810` | `border-border border-b px-3 py-2 text-left font-normal text-foreground-subtlest min-w-16 max-w-md whitespace-normal break-words` | padding 12/8、下边框、normal、12px 最小宽 64px、最大 448px |
| 单元格 `js:1649957` | `border-border border-b px-3 py-2 text-foreground align-top min-w-16 max-w-md whitespace-normal break-words` | 同上，顶对齐 |
| 行 `js:1649598` | `transition-colors last:[&>td]:border-b-0 hover:bg-hover/20` | hover 底色 20% hover 色 |

### 1.7 代码块

| 位置 | class | 解析 |
|---|---|---|
| 容器 `js:731040`（`BFe`） | `group relative w-full overflow-hidden rounded-xl bg-background text-foreground` | `position:relative; width:100%; overflow:hidden; border-radius:12px; background:#FAFAFA/#171717; color:var(--color-foreground)` |
| 头部 `js:731377` | `flex items-center justify-between gap-3 px-3 py-2 text-ui-base text-muted-foreground` | 高约 34px、padding 12/8、gap 12px、14px；**注意 `text-muted-foreground` 在本 CSS 中不存在（见 §1.10）** |
| 头部左组 | `flex items-center gap-2`；文件名 `font-mono`；右组 `-my-1 -mr-1 flex items-center gap-1` | gap 8/4px、负边距 4px |
| 内容区 `js:731040` | `p-2 pt-0 pb-3` | padding 8px、top 0、bottom 12px |
| 行号/代码字体 | `--diffs-font-size:12px`（默认设置 `js:1188132`）；`--diffs-font-family:var(--font-mono)`；`--diffs-line-height:20px` | 12px/20px 等宽 |

### 1.8 Composer

| 位置 | class | 解析 |
|---|---|---|
| 区域 `js:2311291` | `chat-composer-region z-20 w-full shrink-0 @container/composer` + 条件 `max-w-2xl` | `z-index:20; width:100%; flex-shrink:0; max-width:672px`（条件） |
| 输入表面 `js:2311768` | `chat-composer-input-surface relative w-full` + 条件 `rounded-2xl bg-surface shadow-xl/5` | `position:relative; width:100%; border-radius:16px; background:var(--color-surface);` 阴影 `0 20px 25px -5px rgba(0,0,0,.05), 0 8px 10px -6px rgba(0,0,0,.05)` |
| 输入壳 `js:2086052` | `relative flex flex-col gap-3 overflow-hidden rounded-2xl border border-input-border bg-input p-3 transition-colors hover:border-input-border-hover focus-within:!border-input-border-focused focus-within:bg-input-focused` | 圆角 16px、1px 边框、padding 12px、gap 12px、`transition-colors 150ms` |
| 壳内错误条 | `relative z-10 mb-2 w-full rounded-lg border border-[var(--color-warning)]/30 bg-[var(--color-warning)]/10 px-3 py-2 text-ui-base text-foreground` | 圆角 8px、警示色 30%/10%、padding 12/8 |
| 底部 dock `js:3454527` | `pointer-events-none z-20 flex w-full justify-center` + `mt-3 shrink-0` / `shrink-0` / `sticky bottom-0` | 粘底、z 20、水平居中 |
| dock 内容 `js:3454710` | `pointer-events-auto relative z-10 w-full shrink-0 transition-[width,max-width,transform] duration-150 ease-out @min-[1280px]/conversation:transition-[transform]` + `px-4 pb-4` | padding 16px、底 16px、150ms |
| 回到底部按钮 `js:3454710` 附近 | `pointer-events-auto absolute bottom-full left-1/2 z-30 mb-2 -translate-x-1/2 shadow-sm` | 绝对定位、水平居中、margin-bottom 8px、shadow-sm |
| 草稿建议 `js:2537487` | `zcode-draft-prompt-waterfall h-8 min-w-0 justify-start gap-1.5 overflow-hidden rounded-lg px-3 text-left text-ui-caption font-normal leading-4.5 text-foreground` | 高 32px、gap 6px、圆角 8px、padding 12px、13px/18px |

### 1.9 队列（Queue）与交互（Interaction）

| 位置 | class | 解析 |
|---|---|---|
| 队列容器 `js:2620174` | `relative z-0 w-full overflow-hidden rounded-t-2xl border border-border bg-surface p-1 backdrop-blur-md` + `-mb-7 pb-7` | 上圆角 16px、1px 边框、padding 4px、backdrop-blur 12px、下内边距 28px / 负外边距 28px |
| 队列项 `js:2616899` | `relative flex items-center gap-2 rounded-xl px-1.5 py-1 pr-1 transition-colors hover:bg-hover/30` + 拖拽 `bg-hover/40 shadow-sm` / 编辑中 `opacity-60` | 圆角 12px、padding 4px 6px、右 4px、gap 8px、hover 30% |
| 队列项文本 | `flex min-w-0 flex-1 items-center gap-2 truncate text-ui-base text-foreground`；compact 时 `font-mono` | 14px |
| 队列项次级文本 | `truncate text-ui-sm text-foreground-subtle` | 12px |
| 交互徽标 `js:4650879` | `relative inline-flex h-5 shrink-0 items-center justify-center overflow-hidden rounded-full border border-transparent bg-interaction-confirmation-surface px-2 text-ui-sm font-medium text-interaction-confirmation-foreground` | 高 20px、圆角 full、padding 0 8px、12px medium；底色 `#F0FDF4`/`#032E1573`，文字 `#008236`/`#7BF1A8` |
| 倒计时填充 `js:4649836` | `zcode-task-interaction-countdown-fill absolute inset-0 origin-left bg-interaction-ask-fill transition-opacity group-hover/interaction-badge:opacity-0 ...` | 绝对铺满、左原点缩放、底色 `#00C95033`/`#05DF723D`、hover 淡出 |
| 交互次级按钮 `js:2194438` | `rounded-md bg-interaction-confirmation-surface px-1 text-ui-sm text-interaction-confirmation-foreground hover:bg-interaction-confirmation-surface` | 圆角 6px、padding 0 4px、12px |

### 1.10 JS 中引用但 CSS 不存在的 class（重要）

以下 class 在 JS 组件里出现，但 `index-BD2BL4Cc.css` 没有对应规则，Compose 复刻时不要臆造：

`text-muted-foreground`、`bg-muted`、`bg-muted/40`、`bg-surface-raised`、`text-on-surface`、`border-panel-border`、`ring-ring/50`。

> 说明：`text-ui-xl` 的工具类实际存在（`.text-ui-xl`，`css:11099` 定义变量），只是标题组件通过变量 `tht` 间接引用，未出现在上表 token 统计里。

---

## 2. 所有用到的 CSS 变量

### 2.1 颜色（默认主题，非 `theme-zai` 皮肤）

解析链：`:root,:host`（`css:3592` 起、`css:11546` 起）+ `.dark`（`css:397951` 起）。下表为最终 sRGB 十六进制（8 位含 alpha）。

| 变量 | 亮色 | 暗色 | 用途 |
|---|---|---|---|
| `--color-background` | `#FAFAFA` | `#171717` | 页面/代码块底 |
| `--color-background-alt` | `#F5F5F599` | `#26262699` | 次级底 |
| `--color-background-win-alt` | `#E5E5E5` | `#262626` | 窗口次级底 |
| `--color-surface` | `#0A0A0A08` | `#FFFFFF0D` | 用户气泡/队列/输入表面 |
| `--color-surface-hover` | `#0A0A0A0D` | `#FFFFFF1A` | 进度条轨道 |
| `--color-hover` | `#E5E5E5` | `#FFFFFF1A` | hover 底 |
| `--color-selected` | `#0A0A0A1A` | `#FFFFFF1A` | 选中 |
| `--color-border` | `#0A0A0A1A` | `#FAFAFA1A` | 1px 边框 |
| `--color-border-hover` | `#0A0A0A33` | `#FAFAFA4D` | 边框 hover |
| `--color-header` | `#F5F5F5` | `#171717` | 顶栏 |
| `--color-panel` | `#F5F5F5` | `#171717` | 面板 |
| `--color-sidebar` | `#F5F5F5` | `#0A0A0A` | 侧栏 |
| `--color-card` | `#FFFFFF` | `#262626` | 代码块/卡片 |
| `--color-card-selected` | `#E5E5E5` | `#404040` | 卡片选中 |
| `--color-card-border` | `#0A0A0A1A` | `#FAFAFA1A` | 卡片边框 |
| `--color-popover` | `#FFFFFF` | `#262626` | 弹层 |
| `--color-popover-foreground` | `#404040` | `#E5E5E5` | 弹层文字/工具展开 |
| `--color-popover-border` | `#0A0A0A1A` | `#FAFAFA1A` | 弹层边框 |
| `--color-input` | `#FFFFFF` | `#262626` | 输入框底 |
| `--color-input-focused` | `#FAFAFA` | `#0A0A0A` | 输入聚焦底 |
| `--color-input-border` | `#0A0A0A1A` | `#FAFAFA1A` | 输入边框 |
| `--color-input-border-hover` | `#0A0A0A33` | `#FAFAFA4D` | 输入边框 hover |
| `--color-input-border-focused` | `#00BCFF` | `#00A6F4` | 聚焦环/品牌 |
| `--color-brand` | `#00BCFF` | `#00A6F4` | 品牌色（进度条、焦点） |
| `--color-foreground` | `#404040` | `#E5E5E5` | 正文 |
| `--color-foreground-subtle` | `#40404099` | `#E5E5E599` | 次级文字 |
| `--color-foreground-subtlest` | `#40404066` | `#E5E5E54D` | 三级文字/标记 |
| `--color-foreground-inverse` | `#FFFFFF` | `#FFFFFF` | 反色文字 |
| `--color-markdown-inline-code` | `#E5E5E5` | `#404040` | 行内代码底（50% 使用） |
| `--color-tag` | `#E5E5E5` | `#404040` | 标签底 |
| `--color-accent` | `#F0F9FF` | `#052F4A80` | 强调底 |
| `--color-menu` | `#FFFFFF` | `#0A0A0A` | 菜单 |
| `--color-menu-hover` | `#F5F5F5` | `#171717` | 菜单 hover |
| `--color-primary` | `#0A0A0A` | `#FAFAFA` | 主色 |
| `--color-primary-foreground` | `#FAFAFA` | `#0A0A0A` | 主色前景 |
| `--color-secondary` | `#D4D4D4` | `#404040` | 次色 |
| `--color-success` | `#00A63E` | `#00C950` | 成功 |
| `--color-destructive` | `#E7000B` | `#FB2C36` | 危险 |
| `--color-warning` | `#D08700` | `#F0B100` | 警告 |
| `--color-warning-foreground` | `#FFFFFF` | `#FFFFFF` | 警告前景 |
| `--color-diff-added` | `#00A63E` | `#00C950` | diff 增 |
| `--color-diff-removed` | `#E7000B` | `#FB2C36` | diff 删 |
| `--color-git-modified` | `#E17100` | `#FFB900` | git 修改 |
| `--color-git-added` | `#009689` | `#00D5BE` | git 新增 |
| `--color-git-deleted` | `#E7000B` | `#FF6467` | git 删除 |
| `--color-git-renamed` | `#0084D1` | `#00BCFF` | git 重命名 |
| `--color-git-untracked` | `#009689` | `#00D5BE` | git 未跟踪 |
| `--color-tooltip` | `#F5F5F5` | `#171717` | tooltip |
| `--color-tooltip-foreground` | `#0A0A0A` | `#FAFAFA` | tooltip 文字 |
| `--color-tooltip-tag` | `#E5E5E5` | `#262626` | tooltip 标签 |
| `--color-tooltip-tag-foreground` | `#0A0A0A99` | `#FAFAFA99` | tooltip 标签文字 |
| `--color-interaction-ask-surface` | 未定义（继承/无） | `#052F4A80` | 询问卡底 |
| `--color-interaction-ask-foreground` | 未定义 | `#74D4FF` | 询问卡文字 |
| `--color-interaction-ask-fill` | `#00C95033` | `#05DF723D` | 倒计时填充 |
| `--color-interaction-confirmation-surface` | `#F0FDF4` | `#032E1573` | 确认徽标底 |
| `--color-interaction-confirmation-foreground` | `#008236` | `#7BF1A8` | 确认徽标文字 |
| `--color-trajectory-user` | `#2563EB` | `#60A5FA` | 轨迹：用户 |
| `--color-trajectory-assistant` | `#0F766E` | `#2DD4BF` | 轨迹：助手 |
| `--color-trajectory-reasoning` | `#7C3AED` | `#A78BFA` | 轨迹：推理 |
| `--color-trajectory-tool-call` | `#D97706` | `#F59E0B` | 轨迹：工具调用 |
| `--color-trajectory-tool-result` | `#0284C7` | `#38BDF8` | 轨迹：工具结果 |
| `--color-file-node` | `#0084D124` | `#00BCFF2E` | 文件节点底 |
| `--color-file-node-foreground` | `#0084D1` | `#74D4FF` | 文件节点文字 |
| `--color-skill-node-foreground` | `#7F22FE` | `#C4B4FF` | skill 节点 |
| `--color-command-node-foreground` | `#45556C` | `#CAD5E2` | command 节点 |
| `--color-subagent-node-foreground` | `#6F7A2F` | `#B7BD75` | subagent 节点 |
| `--color-session-node-foreground` | `#009689` | `#46ECD5` | session 节点 |
| `--color-plugin-node-foreground` | `#E17100` | `#FFD230` | plugin 节点 |
| `--color-idle-task` | `#7F22FE` | `#A684FF` | 空闲任务 |
| `--color-idle-task-surface` | `#F5F3FF` | `#2F0D6880` | 空闲任务底 |
| `--color-find-highlight` | `#FDE68A` | `#713F12` | 查找高亮 |
| `--color-find-highlight-active` | `#FACC15` | `#A16207` | 当前查找项 |

基础调色板（两套主题共用，`css:3592` 起）：

| 变量 | 值 | | 变量 | 值 |
|---|---|---|---|---|
| `--color-neutral-50` | `#FAFAFA` | | `--color-sky-50` | `#F0F9FF` |
| `--color-neutral-100` | `#F5F5F5` | | `--color-sky-200` | `#B8E6FE` |
| `--color-neutral-200` | `#E5E5E5` | | `--color-sky-300` | `#74D4FF` |
| `--color-neutral-300` | `#D4D4D4` | | `--color-sky-400` | `#00BCFF` |
| `--color-neutral-500` | `#737373` | | `--color-sky-500` | `#00A6F4` |
| `--color-neutral-700` | `#404040` | | `--color-sky-600` | `#0084D1` |
| `--color-neutral-800` | `#262626` | | `--color-sky-950` | `#052F4A` |
| `--color-neutral-900` | `#171717` | | `--color-green-400` | `#05DF72` |
| `--color-neutral-950` | `#0A0A0A` | | `--color-green-500` | `#00C950` |
| `--color-white` | `#FFFFFF` | | `--color-green-600` | `#00A63E` |
| `--color-black` | `#000000` | | `--color-red-500` | `#FB2C36` |
| `--color-amber-400` | `#FFB900` | | `--color-red-600` | `#E7000B` |
| `--color-amber-600` | `#E17100` | | `--color-violet-400` | `#A684FF` |
| `--color-yellow-500` | `#F0B100` | | `--color-violet-600` | `#7F22FE` |
| `--color-yellow-600` | `#D08700` | | `--color-teal-400` | `#00D5BE` |
| `--color-cyan-400` | `#00D3F2` | | `--color-fuchsia-600` | `#C800DE` |
| `--color-slate-300` | `#CAD5E2` | | `--color-indigo-400` | `#7C86FF` |

> 注意：`design/tokens/colors.json` 的 `light` 列被 `.theme-zai-light` 覆盖（例如 background `#F8F8F8`、brand `#000000`），与默认 `:root` 不一致；本文以默认 `:root` 为准。

### 2.2 圆角

| 变量 | 值 | 偏移 |
|---|---|---|
| `--radius-xs` | 2px | `css:10247` |
| `--radius-sm` | 4px | `css:10267` |
| `--radius-md` | 6px | `css:10286` |
| `--radius-lg` | 8px | `css:10306` |
| `--radius-xl` | 12px | `css:10324` |
| `--radius-2xl` | 16px | `css:10343` |
| `--radius-3xl` | 24px | `css:10361` |
| `rounded-full` | `3.40282e38px`（无限） | `.rounded-full` |

### 2.3 间距

| 变量 | 值 | 偏移 |
|---|---|---|
| `--spacing` | `0.25rem` = 4px | `css:9267` |

所有 `p/m/gap/space/size/w/h/inset/translate` 工具类都按 `calc(var(--spacing) * N)` 计算。

### 2.4 字号 / 行高 / 字重 / 字距

| 变量 | 值 | 偏移 |
|---|---|---|
| `--ui-font-size` | 14px | `css:396924` |
| `--text-ui-xl` | `calc(ui + 4px)` = 18px | `css:11099` |
| `--text-ui-lg` | `calc(ui + 2px)` = 16px | 同上 |
| `--text-ui-base` | `var(--ui-font-size)` = 14px | `css:11189` |
| `--text-ui-caption` | `calc(ui - 1px)` = 13px | 同上 |
| `--text-ui-sm` | `calc(ui - 2px)` = 12px | 同上 |
| `--text-ui-xs` | `calc(ui - 4px)` = 10px | 同上 |
| `--text-ui-2xs` | `calc(ui - 5px)` = 9px | 同上 |
| `--text-mobile-input-safe` | 16px | `css:11436` |
| `--text-xs/sm/base/lg/xl/2xl/3xl/4xl` | 12/14/16/18/20/24/30/36px | `css:9400` 起 |
| `--leading-tight/snug/normal/relaxed` | 1.25 / 1.375 / 1.5 / 1.625 | `css:10160` 起 |
| `--font-weight-normal/medium/semibold/bold` | 400 / 500 / 600 / 700 | `css:9967` 起 |
| `--tracking-tight/normal/wide/widest` | -0.025em / 0 / 0.025em / 0.1em | `css:10067` 起 |
| `--font-sans` | `ui-sans-serif, system-ui, sans-serif, ...` | `css:3592` |
| `--font-mono` | `ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, ...` | `css:3592` |

### 2.5 阴影

| 变量 | 值 | 偏移 |
|---|---|---|
| `--shadow-sm` | `0 1px 3px 0 #0000001a, 0 1px 2px -1px #0000001a` | `css:10381` |
| `--shadow-md` | `0 4px 6px -1px #0000001a, 0 2px 4px -2px #0000001a` | `css:10441` |
| `--shadow-lg` | `0 10px 15px -3px #0000001a, 0 4px 6px -4px #0000001a` | `css:10504` |
| `shadow-xl/5` | `0 20px 25px -5px rgba(0,0,0,.05), 0 8px 10px -6px rgba(0,0,0,.05)` | `.shadow-xl\/5` |
| `shadow-xs` | `0 1px 2px 0 #0000000d` | `.shadow-xs` |

### 2.6 过渡

| 变量 | 值 | 偏移 |
|---|---|---|
| `--default-transition-duration` | `.15s` | `css:10917` |
| `--default-transition-timing-function` | `cubic-bezier(.4, 0, .2, 1)` | `css:10960` 附近（紧随 duration） |
| `--ease-out` | `cubic-bezier(0, 0, .2, 1)` | `css:10569` |
| `--ease-in-out` | `cubic-bezier(.4, 0, .2, 1)` | `css:10606` |

`transition-colors` 覆盖 color/background-color/border-color/outline-color/text-decoration-color/fill/stroke/gradient；`transition-opacity` 只覆盖 opacity；`transition-transform` 覆盖 transform/translate/scale/rotate。

### 2.7 动画变量

| 变量 | 值 | 偏移 |
|---|---|---|
| `--animate-spin` | `spin 1s linear infinite` | `css:10647` |
| `--animate-ping` | `ping 1s cubic-bezier(0, 0, .2, 1) infinite` | `css:10686` |
| `--animate-pulse` | `pulse 2s cubic-bezier(.4, 0, .6, 1) infinite` | `css:10744` |

---

## 3. Keyframes 与使用点

### 3.1 对话页直接使用的动画

| 动画名 | 定义（字节） | 使用 class / 选择器 | 时长 / 缓动 / 次数 |
|---|---|---|---|
| `zcode-stream-text-in` | `css:424246` `0%{opacity:0}to{opacity:1}` | `[data-zcode-stream-animate=true]`（`css:425294`） | `.9s cubic-bezier(.16,1,.3,1) var(--zcode-stream-animation-delay,0s) both` |
| 同上 | 同上 | `[data-zcode-tool-stream-animate=true]`、`[data-zcode-chat-loading-animate=true]`（`css:425675`） | `.9s cubic-bezier(.16,1,.3,1) both` |
| `zcode-stream-marker-in` | `css:425169` `0%{color:#0000}to{color:var(--color-foreground-subtlest)}` | `[data-zcode-stream-marker-animate=true]::marker`（`css:425469`） | `.9s cubic-bezier(.16,1,.3,1) var(--zcode-stream-animation-delay,0s) both` |
| `tool-call-progress-indeterminate` | `css:424068` `0%{left:-33%}to{left:100%}` | `.tool-call-progress-indeterminate`（`css:423999`） | `1.2s ease-in-out infinite` |
| `zcode-draft-prompt-waterfall` | `css:424305` `0%{opacity:0;clip-path:inset(0 0 100%);transform:translateY(-12px)}to{opacity:1;clip-path:inset(0);transform:translateY(0)}` | `.zcode-draft-prompt-waterfall`（`css:424499`） | `.26s cubic-bezier(.22,1,.36,1) var(--zcode-draft-prompt-waterfall-delay,0s) both`；延迟 = index × 65ms（`js:2537487`） |
| `markdown-image-loading-shimmer` | `css:424707` `0%{background-position:100% 0}to{background-position:-100% 0}` | `.markdown-image-loading-shimmer`（`css:425020`） | `1.8s linear infinite`；背景 240% 宽、135deg 三段渐变 |
| `zcode-collapsible-up` | `css:426006` `0%{height:var(--radix-collapsible-content-height)}to{height:0}` | `[data-zcode-collapsible-animate-close=true][data-state=closed]`（`css:425803`） | `.3s ease-in-out forwards` |
| `zcode-collapsible-fade-out` | `css:426101` `0%{opacity:1}to{opacity:0}` | 同上 `>*`（`css:425934`） | `.3s ease-in-out forwards` |
| `zcode-alarm-ring` | `css:427011` `0%,to{rotate(-10deg)}50%{rotate(10deg)}` | `.animate-zcode-alarm-ring`（`css:426959`） | `.6s ease-in-out infinite` |
| `zcode-reaction-pop` | `css:427099` `0%{scale(0)}45%{scale(1.3)}70%{scale(.95)}to{scale(1)}` | `.zcode-reaction-burst>svg`（`css:426608`） | `.45s cubic-bezier(.34,1.56,.64,1)` |
| `zcode-reaction-particles` | `css:427224` 粒子 box-shadow 扩散 | `.zcode-reaction-burst:after`（`css:426819`） | `.56s cubic-bezier(.16,1,.3,1) 60ms forwards` |
| `zcode-task-interaction-countdown` | `css:428013` `0%{scaleX(var(--zcode-interaction-progress,1))}to{scaleX(0)}` | `.zcode-task-interaction-countdown-fill`（`css:428177`） | `var(--zcode-interaction-remaining-ms,240s) linear forwards` |
| `gradient-flow` | `css:342687` `0%{background-position:100% 0}50%{background-position:0 0}to{background-position:0 0}` | `.animated-gradient-text`（`css:341468`） | `4s linear infinite` |
| `cua-group-gradient-flow` | `css:342510` | `.cua-group-gradient-text`（`css:342408`） | `1s linear .5s infinite` |

> `.animated-gradient-text` 的变量：亮色 `--animated-gradient-text-strong:#0A0A0A`、`--animated-gradient-text-soft:#0A0A0A38`；暗色 `#FFFFFF`、`#FFFFFF33`（`css:11466`、`css:397951`）。`.highspeed-animated-gradient-text` 下改为紫色：strong `--color-icon-purple`（亮 `#E12AFB`、暗 `#C800DE`）、soft `#E12AFB38`（`css:341650`）。
| `pulse` | `css:452280` `50%{opacity:.5}` | `.animate-pulse` / `motion-safe:animate-pulse` | `2s cubic-bezier(.4,0,.6,1) infinite` |
| `spin` | `css:452182` `to{rotate(360deg)}` | `.animate-spin` | `1s linear infinite` |
| `ping` | `css:452227` `75%,to{opacity:0;scale(2)}` | `.animate-ping` | `1s cubic-bezier(0,0,.2,1) infinite` |
| `collapsible-down` | `css:453122` | `.data-\[state=open\]\:animate-collapsible-down`（`css:262944`） | `.2s ease-out` |
| `collapsible-up` | `css:453333` | `.data-\[state=closed\]\:animate-collapsible-up`（`css:260575`） | `.2s ease-out` |
| `enter` | `css:452313` | `.animate-in` / `data-open:animate-in`（`css:75130`、`css:303632`） | `.15s ease` |
| `exit` | `css:452601` | `.animate-out` / `data-closed:animate-out`（`css:201227`、`css:304896`） | `.15s ease` |

### 3.2 工作流时间线（工具调用内嵌）动画

| 动画 | 定义 | 使用 | 时长 |
|---|---|---|---|
| `wf-stream-flow` | `css:431737` `0%,to{opacity:.3}40%{opacity:1}` | `.wf-stream-flow`（`css:431857`） | `1.2s var(--wf-ease,ease-in-out) infinite` |
| `wf-caret` | `css:445595` `50%{opacity:0}` | `.wf-caret`（`css:440016`） | `1s step-end infinite`（光标闪烁） |
| `wf-slide` | `css:445678` `0%{translateX(var(--wf-slide,0px))}` | `.wf-slide`（`css:440214`） | `.32s var(--wf-ease)` |
| `wf-arrive` | `css:445317` `0%{opacity:0;translateY(4px)}` | `.wf-arrive`（`css:439549`） | `var(--wf-t-enter) var(--wf-ease)` |
| `wf-mark` | `css:445378` `0%{opacity:0;scale(.6)}` | `.wf-mark`（`css:439632`） | `.18s var(--wf-ease)` |
| `wf-unfold` | `css:445431` `0%{opacity:0;translateY(-3px)}` | `.wf-unfold`（`css:439775`） | `var(--wf-t-enter) var(--wf-ease)` |
| `wf-swap` | `css:445493` `0%{opacity:0;translateY(3px)}` | `.wf-swap`（`css:439837`） | `var(--wf-t-enter) var(--wf-ease)` |
| `wf-draw` | `css:445552` `to{stroke-dashoffset:0}` | `.wf-draw`（`css:439959`） | `.42s var(--wf-ease) forwards` |
| `wf-rail-grow` | `css:445630` `0%{scaleX(0)}` | `.wf-rail-grow`（`css:440089`） | `var(--wf-t-base) var(--wf-ease)` |
| `wf-land` | `css:441324` `0%{opacity:0;scale(.6)}` | `.wf-land` / `.wf-landed`（`css:440153`/`css:441284`） | `var(--wf-t-enter)` / `1.2s` |
| `wf-ws-land` | `css:442271` 背景闪一下 | `.wf-ws-landed`（`css:441230`） | `1.4s var(--wf-ease) 1` |
| `wf-ws-shine` | `css:442394` `to{background-position:200% 0}` | `.wf-ws-shine`（`css:442228`） | `2.4s linear infinite` |
| `wf-beat` | `css:445744` 警示光环脉冲 | `.wf-lamp-running`（`css:441068`） | `var(--wf-beat,1.6s) var(--wf-ease,ease-in-out) infinite` |
| `wf-fill-stroke` | `css:438482` `0%{stroke-dashoffset:1px}to{0}` | `.wf-fill-frame-fresh`（`css:438437`） | `.42s var(--wf-ease)` |
| `wf-face-*` | `css:443989`–`css:445201` | `.wf-face[data-motion=...]` | 90–600ms 不等（仅工作流头像） |

### 3.3 高速模式（highspeed）动画

| 动画 | 定义 | 使用 | 时长 |
|---|---|---|---|
| `highspeed-inline-countdown-enter` | `css:380345` `0%{opacity:0;translateY(35%)}to{...0}` | `.highspeed-inline-countdown`（`css:380266`） | `.42s cubic-bezier(.23,1,.32,1) both` |
| `highspeed-model-trigger-sweep` | `css:381810` 背景扫光 | `.highspeed-model-trigger-label-sweep.is-sweep-active`（`css:381735`） | `.8s cubic-bezier(.23,1,.32,1) both` |
| `highspeed-share-dialog-fade-up` | `css:395435` | 分享弹窗元素 | `.42–.46s cubic-bezier(.22,1,.36,1)` + 延迟 |
| `highspeed-share-dialog-fill-grow` | `css:395554` | 图表条 | `1.2s cubic-bezier(.33,0,.16,1) .52s both` |

### 3.4 其他（对话页外围/提示）

| 动画 | 定义 | 使用 | 时长 |
|---|---|---|---|
| `workspace-remote-connecting-breathe` | `css:427639` | `.workspace-remote-connecting-breathe`（`css:428464`） | `1.8s ease-in-out infinite` |
| `browser-use-operation-breathe` | `css:429527` | `.browser-use-operation-breathe`（`css:429725`） | `.9s ease-in-out infinite` |
| `task-search-result-highlight` | `css:430107` | `.task-search-result-highlight`（`css:430560`） | `1.2s ease-out both` |
| `fork-highlight-pulse` | `css:430618` | `[data-fork-highlight=true]`（`css:430978`） | `1.6s ease-out` |
| `zcode-update-charge-sweep` | `css:429243` | `.zcode-update-charge-progress [data-slot=progress-indicator]:after`（`css:429139`） | `1.15s cubic-bezier(.65,0,.35,1) infinite` |

### 3.5 减少动效

所有上述动画都有 `@media (prefers-reduced-motion:reduce)` 兜底为 `animation:none`（例如 `css:424223`、`css:424691`、`css:425153`、`css:426443`、`css:446293`）。Compose 端对应 `LocalMotionDurationScale` 或系统“移除动画”开关。

---

## 4. Tailwind 间距到 px 映射（对话页实际出现的值）

`--spacing = 4px`（`css:9267`）。

| Token | px | 对话页用例 |
|---|---|---|
| `0` | 0 | `pt-0`、`p-0` |
| `0.5` | 2 | `mx-0.5`、`py-0.5` |
| `1` | 4 | `gap-1`、`p-1`、`py-1`、`mt-1`、`pr-1` |
| `1.5` | 6 | `gap-1.5`、`px-1.5`、`space-y-1.5` |
| `2` | 8 | `gap-2`、`pt-2`、`p-2`、`mb-2`、`ml-2` |
| `2.5` | 10 | 少量徽标 |
| `3` | 12 | `gap-3`、`p-3`、`px-3`、`py-3`、`pt-3`、`mt-3` |
| `3.5` | 14 | `pl-3.5`（推理/问答左竖线） |
| `4` | 16 | `px-4`、`pb-4`、`gap-4`、`my-4`、`pt-4` |
| `4.5` | 18 | `size-4.5` |
| `5` | 20 | `gap-5`、`pb-5`、`pt-5`、`px-5` |
| `6` | 24 | `px-6`（@md）、`mt-6`（标题） |
| `7` | 28 | `pb-7`、`-mb-7` |
| `8` | 32 | `h-8`、`min-w-8` |
| `9` | 36 | 少量 |
| `10` | 40 | `min-h-10` |
| `11` | 44 | `size-11` |
| `12` | 48 | 少量 |
| `13` | 52 | 少量 |
| `14` | 56 | `pt-14`（turn 顶部） |
| `20` | 80 | 少量 |
| `28` | 112 | 少量 |
| `32` | 128 | 少量 |
| `40` | 160 | 少量 |
| `42` | 168 | `-translate-x-42`（≥1280 内容列左移） |
| `48` | 192 | 少量 |
| `56` | 224 | 少量 |
| `60` | 240 | `max-h-60`（推理块 240px） |
| `64` | 256 | `w-64`/`max-w-64` |
| `72` | 288 | 少量 |
| `80` | 320 | `w-80` |
| `96` | 384 | 少量 |

固定容器宽：`max-w-xl=576px`、`max-w-2xl=672px`、`max-w-4xl=896px`、`max-w-6xl=1152px`（`css:9368` 起）。

---

## 5. 代码块语法高亮

### 5.1 实现方式

- 运行时高亮器：`@pierre/diffs`（`shiki-wasm` 内核，`js:657192` 附近），不是传统 `.hljs-*` class。
- 每个 token 以内联 CSS 变量输出，选择器为 `[data-line] span`（`js:527363`）：
  - `--diffs-token-light` / `--diffs-token-dark`（文字色）
  - `--diffs-token-light-bg` / `--diffs-token-dark-bg`（token 背景）
  - `--diffs-token-light-font-weight` / `--diffs-token-dark-font-weight`
  - `--diffs-token-light-font-style` / `--diffs-token-dark-font-style`
  - `--diffs-token-light-text-decoration` / `--diffs-token-dark-text-decoration`
- 主题级变量：`--diffs-light`、`--diffs-dark`、`--diffs-light-bg`、`--diffs-dark-bg`、`--diffs-bg`、`--diffs-fg`、`--diffs-font-size`、`--diffs-line-height`、`--diffs-tab-size`、`--diffs-font-family`。
- diff 变量：`--diffs-added-light:#0dbe4e`、`--diffs-added-dark:#5ecc71`、`--diffs-modified-light:#009fff`、`--diffs-modified-dark:#69b1ff`、`--diffs-deleted-light:#ff2e3f`、`--diffs-deleted-dark:#ff6762`（`js:523293`）。
- 默认字号 12px（`gT.fontSizePx=12`，`js:1188132`），默认行高 20px（`--diffs-line-height:20px`，`js:523293`）。
- 代码块容器把 `--diffs-bg` / `--diffs-light-bg` / `--diffs-dark-bg` 覆盖为 `var(--color-card)`，并设 `--diffs-gap-block:0px`（`js:733046`）。

### 5.2 默认主题 token 颜色

默认 `lightTheme=github-light`、`darkTheme=github-dark`（`js:1188132`），主题文件：
`assets/github-light-SGEO0im-.js`（`JSON.parse` 起始约字节 30）、`assets/github-dark-Cz-mMriX.js`（同）。

| Scope | Light | Dark |
|---|---|---|
| `editor.background` | `#FFFFFF` | `#24292E` |
| `editor.foreground` | `#24292E` | `#E1E4E8` |
| `comment` / `punctuation.definition.comment` | `#6A737D` | `#6A737D` |
| `constant` / `variable.other.constant` / `variable.language` | `#005CC5` | `#79B8FF` |
| `entity` / `entity.name` | `#6F42C1` | `#B392F0` |
| `variable.parameter.function` | `#24292E` | `#E1E4E8` |
| `entity.name.tag` | `#22863A` | `#85E89D` |
| `keyword` / `storage` / `storage.type` | `#D73A49` | `#F97583` |
| `string` / `punctuation.definition.string` | `#032F62` | `#9ECBFF` |
| `support` / `meta.property-name` / `support.constant` | `#005CC5` | `#79B8FF` |
| `variable` | `#E36209` | `#FFAB70` |
| `variable.other` | `#24292E` | `#E1E4E8` |
| `invalid.*` | `#B31D28` italic | `#FDAEB7` italic |
| `markup.heading` | `#005CC5` bold | `#79B8FF` bold |
| `markup.quote` | `#22863A` | `#85E89D` |
| `markup.inline.raw` | `#005CC5` | `#79B8FF` |
| `markup.inserted` | `#22863A` on `#F0FFF4` | `#85E89D` on `#144620` |
| `markup.deleted` | `#B31D28` on `#FFEEF0` | `#FDAEB7` on `#86181D` |
| `markup.changed` | `#E36209` on `#FFEBDA` | `#FFAB70` on `#C24E00` |
| `meta.diff.range` | `#6F42C1` bold | `#B392F0` bold |
| `textLink.foreground` | `#0366D6` | `#79B8FF` |
| `textCodeBlock.background` | `#F6F8FA` | `#2F363D` |

> 对话页代码块的实际底色由 `--color-card` 决定（亮 `#FFFFFF` / 暗 `#262626`），不是主题里的 `editor.background`；主题色只用于 token。

---

## 6. 响应式断点与平板/手机差异

### 6.1 容器查询（对话页主用）

| 容器 | 条件 | 影响 |
|---|---|---|
| `conversation` | `not (width>=360px)` | `.@max-[360px]/conversation:hidden`（`css:283567`） |
| `conversation` | `>=624px` | 用户气泡 `max-width:576px`（`css:285358`） |
| `conversation` | `>=864px` | 内容列 `w calc(100% - 96px)`、`max-w 896px`、表格左 inset 48px、导航可见、translate 0（`css:285585`） |
| `conversation` | `>=1280px` | 内容列 `w calc(100% - 384px)`、`max-w 1152px`、`-translate-x-42`（-168px）、`px-0`（`css:286153`） |
| `conversation` | `>=28rem (448px)` | turn `px-6`（`css:287374`） |
| `composer` | `not (width>=480px)` | `@max-[480px]/composer:hidden` / `inline-flex`（`css:283039`） |
| `composer` | `>=24rem (384px)` | `@sm/composer` 显示切换 |
| `composer` | `>=32rem (512px)` | `@lg/composer:hidden`（`css:287374` 附近） |
| `composer` | `>=36rem (576px)` | `@xl/composer:block/hidden/inline-flex/h-7/gap-1/pl-2/pr-1.5` |
| `composer` | `not (width>=24rem)` | `@max-sm/composer`：size-7、gap-0、p-0、居中 |
| `composer` | `not (width>=36rem)` | `@max-xl/composer`：size-7、gap-0、p-0、居中 |

### 6.2 媒体查询

| 断点 | 值 | 对话页影响 |
|---|---|---|
| `sm` | `width>=40rem` = 640px | 部分按钮/弹窗 |
| `md` | `width>=48rem` = 768px | 表格 inset 32px（`js:1641xxx`：<768 用 8px，<1024 用 16px，≥1024 用 32px） |
| `lg` | `width>=64rem` = 1024px | 表格 inset 32px |
| `xl` | `width>=80rem` = 1280px | 少量 |
| `2xl` | `width>=96rem` = 1536px | 少量 |
| `max-md` | `<768px` | 表格 inset 8px；部分面板隐藏 |
| `max-height:740px` | 高度 ≤740px | 压缩间距（`mt-4`、`gap-1`、`h-10` 等） |
| `hover:none` | 触摸设备 | 操作按钮常显（`opacity-100`） |
| `hover:hover` | 鼠标设备 | 操作按钮 hover 显示 |
| `prefers-reduced-motion:reduce` | — | 所有动画关闭 |
| `prefers-color-scheme:dark` | — | `.dark` 类由 JS 切换，不直接依赖媒体查询 |

### 6.3 手机 vs 平板（对话页）

| 项目 | 手机（<768px） | 平板/桌面（≥864px） |
|---|---|---|
| 内容列宽 | 100% | `calc(100% - 96px)`，max 896px |
| 内容列最大宽 | 672px（空态） | 896px；≥1280px 1152px |
| turn 内边距 | 16px | 16px → ≥448px 24px |
| 用户气泡最大宽 | 100% | 576px（≥624px） |
| 表格左右 inset | 8px | 48px（≥864px 左） |
| 表格滚动条 | 隐藏（`!scrollbar-hide`） | 同 |
| Composer 按钮 | 图标收缩（size-7、gap-0） | 完整（h-7、gap-1） |
| 回到底部按钮 | 绝对定位 bottom-3 | 跟随 dock |
| 侧栏/轨迹面板 | 隐藏（`max-md:hidden`） | ≥1280px 显示 320px 面板 |

---

## 7. Compose 可直接照抄的数值表

### 7.1 颜色（Compose `Color(0xAARRGGBB)`）

| 语义 | Light | Dark |
|---|---|---|
| 页面背景 | `0xFFFAFAFA` | `0xFF171717` |
| 面板/顶栏 | `0xFFF5F5F5` | `0xFF171717` |
| 侧栏 | `0xFFF5F5F5` | `0xFF0A0A0A` |
| 卡片/代码块底 | `0xFFFFFFFF` | `0xFF262626` |
| 弹层 | `0xFFFFFFFF` | `0xFF262626` |
| 输入底 | `0xFFFFFFFF` | `0xFF262626` |
| 输入聚焦底 | `0xFFFAFAFA` | `0xFF0A0A0A` |
| 用户气泡底 `surface` | `0x080A0A0A` | `0x0DFFFFFF` |
| surfaceHover | `0x0D0A0A0A` | `0x1AFFFFFF` |
| hover | `0xFFE5E5E5` | `0x1AFFFFFF` |
| selected | `0x1A0A0A0A` | `0x1AFFFFFF` |
| 边框 | `0x1A0A0A0A` | `0x1AFAFAFA` |
| 边框 hover | `0x330A0A0A` | `0x4DFAFAFA` |
| 正文 | `0xFF404040` | `0xFFE5E5E5` |
| 次级文字 | `0x99404040` | `0x99E5E5E5` |
| 三级文字 | `0x66404040` | `0x4DE5E5E5` |
| 品牌/焦点 | `0xFF00BCFF` | `0xFF00A6F4` |
| 行内代码底（50%） | `0x80E5E5E5` | `0x80404040` |
| 成功 | `0xFF00A63E` | `0xFF00C950` |
| 警告 | `0xFFD08700` | `0xFFF0B100` |
| 危险 | `0xFFE7000B` | `0xFFFB2C36` |
| 确认徽标底 | `0xFFF0FDF4` | `0x73032E15` |
| 确认徽标文字 | `0xFF008236` | `0xFF7BF1A8` |
| 倒计时填充 | `0x3300C950` | `0x3D05DF72` |
| 询问卡底（暗） | — | `0x80052F4A` |
| 询问卡文字（暗） | — | `0xFF74D4FF` |
| 轨迹-用户 | `0xFF2563EB` | `0xFF60A5FA` |
| 轨迹-助手 | `0xFF0F766E` | `0xFF2DD4BF` |
| 轨迹-推理 | `0xFF7C3AED` | `0xFFA78BFA` |
| 轨迹-工具调用 | `0xFFD97706` | `0xFFF59E0B` |
| 轨迹-工具结果 | `0xFF0284C7` | `0xFF38BDF8` |
| 查找高亮 | `0xFFFDE68A` | `0xFF713F12` |
| 当前查找项 | `0xFFFACC15` | `0xFFA16207` |

### 7.2 尺寸 / 圆角 / 间距（dp）

| 项目 | 值 |
|---|---|
| 基准字号 | 14sp（可配置 12–20） |
| 正文行高 | 24.5sp（`leading-[1.75]`） |
| 正文字距 | 0.35sp（0.025em） |
| ui-xl / lg / base / caption / sm / xs / 2xs | 18 / 16 / 14 / 13 / 12 / 10 / 9sp |
| 字重 | normal 400 / medium 500 / semibold 600 / bold 700 |
| 圆角 xs/sm/md/lg/xl/2xl/3xl | 2 / 4 / 6 / 8 / 12 / 16 / 24dp |
| 用户气泡 | 12dp，右上 2dp |
| 代码块 | 12dp |
| 表格 | 12dp |
| Composer 壳 | 16dp |
| 队列容器 | 上 16dp |
| 队列项 | 12dp |
| turn 纵向间距 | 20dp |
| turn 内边距 | 水平 16dp（≥448 容器 24dp），下 20dp，上 56dp（首 turn 0） |
| 行组间距 | 16dp |
| 用户气泡内边距 | 16×12dp，gap 8dp |
| 推理内容 | 左线 1dp，左内边距 14dp，左外边距 8dp，最大高 240dp，子项 gap 8dp |
| 工具摘要 | gap 8dp；展开 padding-top 8dp |
| 工具进度条 | 64×4dp，圆角 full，指示条宽 1/3 |
| 代码块头部 | padding 12×8dp，gap 12dp |
| 代码块内容 | padding 8dp，top 0，bottom 12dp |
| 表格单元格 | padding 12×8dp，最小宽 64dp，最大宽 448dp |
| Composer 壳 | padding 12dp，gap 12dp |
| Composer dock | padding 16dp，底 16dp |
| 队列容器 | padding 4dp，底 28dp，上圆角 16dp |
| 队列项 | padding 4dp 6dp，右 4dp，gap 8dp |
| 交互徽标 | 高 20dp，圆角 full，水平 padding 8dp |
| 时间线标记 | padding 16×8dp，gap 12dp |
| 用户气泡最大宽 | 576dp（容器 ≥624dp） |
| 内容列 | 100% → `calc(100% - 96dp)` max 896dp → `calc(100% - 384dp)` max 1152dp |
| 内容列平移 | ≥1280dp 容器：-168dp |
| 空态列最大宽 | 672dp |
| 代码字号/行高 | 12sp / 20sp |

### 7.3 阴影

| Token | Compose |
|---|---|
| shadow-xs | `0 1dp 2dp rgba(0,0,0,0.051)` |
| shadow-sm | `0 1dp 3dp rgba(0,0,0,0.102), 0 1dp 2dp -1dp rgba(0,0,0,0.102)` |
| shadow-md | `0 4dp 6dp -1dp rgba(0,0,0,0.102), 0 2dp 4dp -2dp rgba(0,0,0,0.102)` |
| shadow-xl/5 | `0 20dp 25dp -5dp rgba(0,0,0,0.05), 0 8dp 10dp -6dp rgba(0,0,0,0.05)` |

### 7.4 动画时长 / 缓动

| 场景 | 时长 | 缓动 | 备注 |
|---|---|---|---|
| 流式文字淡入 | 900ms | `CubicBezier(0.16, 1, 0.3, 1)` | 每个元素可带 delay |
| 工具流式淡入 | 900ms | `CubicBezier(0.16, 1, 0.3, 1)` | `data-zcode-tool-stream-animate` |
| 加载淡入 | 900ms | `CubicBezier(0.16, 1, 0.3, 1)` | |
| 不确定进度条 | 1200ms | `EaseInOut` | `infinite`，left -33%→100% |
| 思考/运行文字渐变 | 4000ms | `Linear` | `infinite`，背景 300% |
| 草稿建议瀑布 | 260ms | `CubicBezier(0.22, 1, 0.36, 1)` | delay = index × 65ms |
| 图片 shimmer | 1800ms | `Linear` | `infinite`，背景 240% |
| 折叠关闭 | 300ms | `EaseInOut` | 高度 + 淡出 |
| Radix 折叠 | 200ms | `EaseOut` | |
| 入场/退场 | 150ms | `Ease` | |
| 光标闪烁 | 1000ms | `steps(1, end)` | 50% opacity 0 |
| 交互倒计时 | 240000ms 默认 | `Linear` | `forwards`，可被 CSS 变量覆盖 |
| 反应弹跳 | 450ms | `CubicBezier(0.34, 1.56, 0.64, 1)` | |
| 反应粒子 | 560ms | `CubicBezier(0.16, 1, 0.3, 1)` | 延迟 60ms |
| 闹钟摇铃 | 600ms | `EaseInOut` | `infinite` |
| 工作流流光 | 1200ms | `EaseInOut` | `infinite` |
| 工作流滑入 | 320ms | `Ease` | |
| 工作流落位 | 1200ms | `Ease` | |
| 工作流标记 | 180ms | `Ease` | |
| 工作流展开 | 320ms | `Ease` | |
| 工作流描边 | 420ms | `Ease` | |
| 工作流警示脉冲 | 1600ms | `EaseInOut` | `infinite` |
| 高速倒计时入场 | 420ms | `CubicBezier(0.23, 1, 0.32, 1)` | |
| 高速扫光 | 800ms | `CubicBezier(0.23, 1, 0.32, 1)` | |
| 通用颜色过渡 | 150ms | `CubicBezier(0.4, 0, 0.2, 1)` | |
| 宽度过渡 | 200ms | `EaseOut` | |
| 慢速过渡 | 300ms | `Ease` | |
| 自旋 | 1000ms | `Linear` | `infinite` |
| 脉冲 | 2000ms | `CubicBezier(0.4, 0, 0.6, 1)` | `infinite` |
| Ping | 1000ms | `CubicBezier(0, 0, 0.2, 1)` | `infinite` |

---

## 附录 A：对话相关 CSS class 声明全表（667 条）

> 由 `index-BD2BL4Cc.css` 直接抽取；`var(--spacing)`=4px、`var(--radius-*)`、`var(--text-ui-*)` 见 §2。`@layer components;@layer utilities` 为 Tailwind 层标记，可忽略。同一 class 在不同 at-rule（含 `prefers-reduced-motion:reduce`、`@media`、`@container`）下的声明会合并到同一行，按出现顺序排列。

| class | CSS 声明 |
|---|---|
| `!bg-success/10` | background-color:#00a5441a!important ; background-color:color-mix(in oklab, var(--color-success) 10%, transparent)!important |
| `!bg-transparent` | background-color:#0000!important |
| `!bg-warning/10` | background-color:#cd89001a!important ; background-color:color-mix(in oklab, var(--color-warning) 10%, transparent)!important |
| `!border-0` | border-style:var(--tw-border-style)!important;border-width:0!important |
| `!h-44` | height:calc(var(--spacing) * 44)!important |
| `!max-w-full` | max-width:100%!important |
| `!min-h-5` | min-height:calc(var(--spacing) * 5)!important |
| `!overflow-y-hidden` | overflow-y:hidden!important |
| `!pr-4.5` | padding-right:calc(var(--spacing) * 4.5)!important |
| `!px-0` | padding-inline:calc(var(--spacing) * 0)!important |
| `!py-0` | padding-block:calc(var(--spacing) * 0)!important |
| `!rounded-none` | border-radius:0!important |
| `!rounded-xl` | border-radius:var(--radius-xl)!important |
| `!scrollbar-hide` | scrollbar-width:none!important |
| `!shadow-md` | --tw-shadow:0 4px 6px -1px var(--tw-shadow-color,#0000001a), 0 2px 4px -2px var(--tw-shadow-color,#0000001a)!important;box-shadow:var(--tw-inset-shadow), var(--tw-inset-ring-shadow), var(--tw-ring-offset-shadow), var(--tw-ring-shadow), var(--tw-shadow)!important |
| `!transition-none` | transition-property:none!important |
| `!w-44` | width:calc(var(--spacing) * 44)!important |
| `!w-64` | width:calc(var(--spacing) * 64)!important |
| `!w-80` | width:calc(var(--spacing) * 80)!important |
| `@container/composer` | container:composer/inline-size |
| `@max-[360px]/conversation:hidden` | display:none |
| `@max-[480px]/composer:hidden` | display:none |
| `@max-[480px]/composer:inline-flex` | display:inline-flex |
| `@max-xl/composer:gap-0` | gap:calc(var(--spacing) * 0) |
| `@max-xl/composer:hidden` | display:none |
| `@max-xl/composer:justify-center` | justify-content:center |
| `@max-xl/composer:p-0` | padding:calc(var(--spacing) * 0) |
| `@max-xl/composer:size-7` | width:calc(var(--spacing) * 7);height:calc(var(--spacing) * 7) |
| `@max-xl/composer:w-7` | width:calc(var(--spacing) * 7) |
| `@md/conversation:px-6` | padding-inline:calc(var(--spacing) * 6) |
| `@min-[1280px]/conversation:transition-[transform]` | transition-property:transform;transition-timing-function:var(--tw-ease,var(--default-transition-timing-function));transition-duration:var(--tw-duration,var(--default-transition-duration)) |
| `@min-[864px]/conversation:[--markdown-table-layout-left-inset:48px]` | --markdown-table-layout-left-inset:48px |
| `@min-[864px]/conversation:opacity-100` | opacity:1 |
| `@min-[864px]/conversation:translate-x-0` | --tw-translate-x:calc(var(--spacing) * 0);translate:var(--tw-translate-x) var(--tw-translate-y) |
| `@min-[864px]/conversation:visible` | visibility:visible |
| `@sm/composer:inline-flex` | display:inline-flex |
| `@xl/composer:hidden` | display:none |
| `@xl/composer:inline-flex` | display:inline-flex |
| `[&>*+*:not([data-slot='collapsible-content'])]:mt-5` | margin-top:calc(var(--spacing) * 5) |
| `[&>p]:inline` | display:inline |
| `[&>p]:my-0` | margin-block:calc(var(--spacing) * 0) |
| `[&_[data-radix-scroll-area-viewport]>div]:!block` | display:block!important |
| `[&_[data-radix-scroll-area-viewport]>div]:!w-full` | width:100%!important |
| `[&_[data-slot=checkbox-checked-icon]]:size-2.5` | width:calc(var(--spacing) * 2.5);height:calc(var(--spacing) * 2.5) |
| `[&_[data-slot=checkbox-indeterminate-icon]]:size-2.5` | width:calc(var(--spacing) * 2.5);height:calc(var(--spacing) * 2.5) |
| `[&_[data-slot=scroll-area-thumb]]:!bg-transparent` | background-color:#0000!important |
| `[&_[data-slot=scroll-area-thumb]]:!min-w-1.5` | min-width:calc(var(--spacing) * 1.5)!important |
| `[&_h1]:text-foreground` | color:var(--color-foreground) ; --lucide-stroke:currentColor;--lucide-filter:initial |
| `[&_h2]:text-foreground` | color:var(--color-foreground) ; --lucide-stroke:currentColor;--lucide-filter:initial |
| `[&_h3]:text-foreground` | color:var(--color-foreground) ; --lucide-stroke:currentColor;--lucide-filter:initial |
| `[&_li]:text-foreground-subtle` | color:var(--color-foreground-subtle) ; --lucide-stroke:rgb(from currentColor r g b / 1);--lucide-filter:opacity(.6) |
| `[&_ol]:my-1.5` | margin-block:calc(var(--spacing) * 1.5) |
| `[&_p+p]:mt-2` | margin-top:calc(var(--spacing) * 2) |
| `[&_p]:my-0` | margin-block:calc(var(--spacing) * 0) |
| `[&_p]:text-foreground-subtle` | color:var(--color-foreground-subtle) ; --lucide-stroke:rgb(from currentColor r g b / 1);--lucide-filter:opacity(.6) |
| `[&_svg:not([class*='size-'])]:size-4` | width:calc(var(--spacing) * 4);height:calc(var(--spacing) * 4) |
| `[&_svg]:pointer-events-none` | pointer-events:none |
| `[&_svg]:shrink-0` | flex-shrink:0 |
| `[&_svg]:size-3` | width:calc(var(--spacing) * 3);height:calc(var(--spacing) * 3) |
| `[&_svg]:text-foreground-subtlest` | color:var(--color-foreground-subtlest) ; --lucide-stroke:rgb(from currentColor r g b / 1);--lucide-filter:opacity(var(--lucide-subtlest-opacity)) |
| `[&_th]:bg-background` | background-color:var(--color-background) |
| `[&_th]:sticky` | position:sticky |
| `[&_th]:top-0` | top:calc(var(--spacing) * 0) |
| `[&_th]:z-10` | z-index:10 |
| `[&_ul]:my-1.5` | margin-block:calc(var(--spacing) * 1.5) |
| `[--attachment-bg:var(--color-surface)]` | --attachment-bg:var(--color-surface) |
| `[--markdown-table-layout-left-inset:16px]` | --markdown-table-layout-left-inset:16px |
| `[--markdown-table-layout-right-inset:16px]` | --markdown-table-layout-right-inset:16px |
| `[-webkit-mask-repeat:no-repeat]` | -webkit-mask-repeat:no-repeat |
| `[-webkit-mask-size:100%_100%]` | -webkit-mask-size:100% 100% |
| `[@media(hover:none)]:opacity-100` | opacity:1 |
| `[app-region:no-drag]` | app-region:no-drag |
| `[mask-repeat:no-repeat]` | -webkit-mask-repeat:no-repeat;mask-repeat:no-repeat |
| `[mask-size:100%_100%]` | -webkit-mask-size:100% 100%;mask-size:100% 100% |
| `[perspective:240px]` | perspective:240px |
| `[scrollbar-gutter:stable]` | scrollbar-gutter:stable |
| `absolute` | position:absolute |
| `align-baseline` | vertical-align:baseline |
| `align-middle` | vertical-align:middle |
| `align-top` | vertical-align:top |
| `animate-in` | animation:enter var(--tw-animation-duration,var(--tw-duration,.15s))var(--tw-ease,ease)var(--tw-animation-delay,0s)var(--tw-animation-iteration-count,1)var(--tw-animation-direction,normal)var(--tw-animation-fill-mode,none) |
| `animate-pulse` | animation:var(--animate-pulse) |
| `animate-spin` | animation:var(--animate-spin) |
| `animate-zcode-alarm-ring` | animation:.6s ease-in-out infinite zcode-alarm-ring |
| `animated-gradient-text` | background:linear-gradient(90deg, var(--animated-gradient-text-strong) 0%, var(--animated-gradient-text-strong) 34%, var(--animated-gradient-text-soft) 50%, var(--animated-gradient-text-strong) 66%, var(--animated-gradient-text-strong) 100%);color:#0000;-webkit-text-fill-color:transparent;will-change:background-position;backface-visibility:hidden;background-size:300% 100%;-webkit-background-clip:text;background-clip:text;animation:4s linear infinite gradient-flow;display:inline-block;transform:translateZ(0) ; --animated-gradient-text-strong:var(--color-icon-purple);--animated-gradient-text-soft:#e12afb38 ; --animated-gradient-text-soft:color-mix(in srgb, var(--color-icon-purple) 22%, transparent) |
| `animated-gradient-text-subtle` | --animated-gradient-text-strong:var(--color-foreground-subtle) |
| `aspect-[5/4]` | aspect-ratio:5/4 |
| `backdrop-blur-md` | --tw-backdrop-blur:blur(var(--blur-md));-webkit-backdrop-filter:var(--tw-backdrop-blur,) var(--tw-backdrop-brightness,) var(--tw-backdrop-contrast,) var(--tw-backdrop-grayscale,) var(--tw-backdrop-hue-rotate,) var(--tw-backdrop-invert,) var(--tw-backdrop-opacity,) var(--tw-backdrop-saturate,) var(--tw-backdrop-sepia,);backdrop-filter:var(--tw-backdrop-blur,) var(--tw-backdrop-brightness,) var(--tw-backdrop-contrast,) var(--tw-backdrop-grayscale,) var(--tw-backdrop-hue-rotate,) var(--tw-backdrop-invert,) var(--tw-backdrop-opacity,) var(--tw-backdrop-saturate,) var(--tw-backdrop-sepia,) |
| `backdrop-blur-sm` | --tw-backdrop-blur:blur(var(--blur-sm));-webkit-backdrop-filter:var(--tw-backdrop-blur,) var(--tw-backdrop-brightness,) var(--tw-backdrop-contrast,) var(--tw-backdrop-grayscale,) var(--tw-backdrop-hue-rotate,) var(--tw-backdrop-invert,) var(--tw-backdrop-opacity,) var(--tw-backdrop-saturate,) var(--tw-backdrop-sepia,);backdrop-filter:var(--tw-backdrop-blur,) var(--tw-backdrop-brightness,) var(--tw-backdrop-contrast,) var(--tw-backdrop-grayscale,) var(--tw-backdrop-hue-rotate,) var(--tw-backdrop-invert,) var(--tw-backdrop-opacity,) var(--tw-backdrop-saturate,) var(--tw-backdrop-sepia,) |
| `basis-48` | flex-basis:calc(var(--spacing) * 48) |
| `bg-[var(--color-warning)]/10` | background-color:#cd89001a ; background-color:color-mix(in oklab, var(--color-warning) 10%, transparent) |
| `bg-accent` | background-color:var(--color-accent) |
| `bg-accent/55` | background-color:#f0f9ff8c ; background-color:color-mix(in oklab, var(--color-accent) 55%, transparent) |
| `bg-background` | background-color:var(--color-background) |
| `bg-background-alt` | background-color:var(--color-background-alt) |
| `bg-background/50` | background-color:#fafafa80 ; background-color:color-mix(in oklab, var(--color-background) 50%, transparent) |
| `bg-background/60` | background-color:#fafafa99 ; background-color:color-mix(in oklab, var(--color-background) 60%, transparent) |
| `bg-background/85` | background-color:#fafafad9 ; background-color:color-mix(in oklab, var(--color-background) 85%, transparent) |
| `bg-black/5` | background-color:#0000000d ; background-color:color-mix(in oklab, var(--color-black) 5%, transparent) |
| `bg-black/60` | background-color:#0009 ; background-color:color-mix(in oklab, var(--color-black) 60%, transparent) |
| `bg-border` | background-color:var(--color-border) |
| `bg-border/50` | background-color:color-mix(in srgb, color-mix(in oklab, var(--color-neutral-950) 10%, transparent) 50%, transparent) ; background-color:color-mix(in oklab, var(--color-border) 50%, transparent) |
| `bg-brand` | background-color:var(--color-brand) |
| `bg-card` | background-color:var(--color-card) |
| `bg-current/10` | background-color:currentColor ; background-color:color-mix(in oklab, currentcolor 10%, transparent) |
| `bg-destructive` | background-color:var(--color-destructive) |
| `bg-destructive/10` | background-color:#e400141a ; background-color:color-mix(in oklab, var(--color-destructive) 10%, transparent) |
| `bg-foreground` | background-color:var(--color-foreground) |
| `bg-foreground-subtlest` | background-color:var(--color-foreground-subtlest) |
| `bg-foreground/20` | background-color:#40404033 ; background-color:color-mix(in oklab, var(--color-foreground) 20%, transparent) |
| `bg-foreground/3` | background-color:#40404008 ; background-color:color-mix(in oklab, var(--color-foreground) 3%, transparent) |
| `bg-gradient-to-b` | --tw-gradient-position:to bottom in oklab;background-image:linear-gradient(var(--tw-gradient-stops)) |
| `bg-gradient-to-t` | --tw-gradient-position:to top in oklab;background-image:linear-gradient(var(--tw-gradient-stops)) |
| `bg-hover` | background-color:var(--color-hover) |
| `bg-hover/40` | background-color:#e5e5e566 ; background-color:color-mix(in oklab, var(--color-hover) 40%, transparent) |
| `bg-input` | background-color:var(--color-input) |
| `bg-input-focused` | background-color:var(--color-input-focused) |
| `bg-interaction-ask-fill` | background-color:var(--color-interaction-ask-fill) |
| `bg-interaction-confirmation-surface` | background-color:var(--color-interaction-confirmation-surface) |
| `bg-markdown-inline-code/50` | background-color:#e5e5e580 ; background-color:color-mix(in oklab, var(--color-markdown-inline-code) 50%, transparent) |
| `bg-menu` | background-color:var(--color-menu) |
| `bg-panel` | background-color:var(--color-panel) |
| `bg-popover` | background-color:var(--color-popover) |
| `bg-popover/95` | background-color:#fffffff2 ; background-color:color-mix(in oklab, var(--color-popover) 95%, transparent) |
| `bg-primary` | background-color:var(--color-primary) |
| `bg-selected` | background-color:var(--color-selected) |
| `bg-success` | background-color:var(--color-success) |
| `bg-surface` | background-color:var(--color-surface) |
| `bg-surface-hover` | background-color:var(--color-surface-hover) |
| `bg-tag` | background-color:var(--color-tag) |
| `bg-tooltip` | background-color:var(--color-tooltip) |
| `bg-tooltip-tag` | background-color:var(--color-tooltip-tag) |
| `bg-transparent` | background-color:#0000 |
| `bg-warning` | background-color:var(--color-warning) |
| `bg-warning/10` | background-color:#cd89001a ; background-color:color-mix(in oklab, var(--color-warning) 10%, transparent) |
| `bg-white` | background-color:var(--color-white) |
| `block` | display:block |
| `border` | border-style:var(--tw-border-style);border-width:1px |
| `border-0` | border-style:var(--tw-border-style);border-width:0 |
| `border-[1.5px]` | border-style:var(--tw-border-style);border-width:1.5px |
| `border-[var(--color-border)]` | border-color:var(--color-border) |
| `border-[var(--color-border)]/50` | border-color:color-mix(in srgb, color-mix(in oklab, var(--color-neutral-950) 10%, transparent) 50%, transparent) ; border-color:color-mix(in oklab, var(--color-border) 50%, transparent) |
| `border-[var(--color-warning)]/30` | border-color:#cd89004d ; border-color:color-mix(in oklab, var(--color-warning) 30%, transparent) |
| `border-b` | border-bottom-style:var(--tw-border-style);border-bottom-width:1px ; padding-bottom:calc(var(--spacing) * 3) |
| `border-border` | border-color:var(--color-border) |
| `border-border/70` | border-color:color-mix(in srgb, color-mix(in oklab, var(--color-neutral-950) 10%, transparent) 70%, transparent) ; border-color:color-mix(in oklab, var(--color-border) 70%, transparent) |
| `border-brand` | border-color:var(--color-brand) |
| `border-card-border` | border-color:var(--color-card-border) |
| `border-collapse` | border-collapse:collapse |
| `border-foreground` | border-color:var(--color-foreground) |
| `border-foreground-subtlest` | border-color:var(--color-foreground-subtlest) |
| `border-icon-purple/30` | border-color:#e12afb4d ; border-color:color-mix(in oklab, var(--color-icon-purple) 30%, transparent) |
| `border-input-border` | border-color:var(--color-input-border) |
| `border-l` | border-left-style:var(--tw-border-style);border-left-width:1px |
| `border-l-0` | border-left-style:var(--tw-border-style);border-left-width:0 |
| `border-l-2` | border-left-style:var(--tw-border-style);border-left-width:2px |
| `border-popover-border` | border-color:var(--color-popover-border) |
| `border-r` | border-right-style:var(--tw-border-style);border-right-width:1px |
| `border-separate` | border-collapse:separate |
| `border-spacing-0` | --tw-border-spacing-x:calc(var(--spacing) * 0);--tw-border-spacing-y:calc(var(--spacing) * 0);border-spacing:var(--tw-border-spacing-x) var(--tw-border-spacing-y) |
| `border-t` | border-top-style:var(--tw-border-style);border-top-width:1px ; padding-top:calc(var(--spacing) * 3) |
| `border-transparent` | border-color:#0000 |
| `border-warning` | border-color:var(--color-warning) |
| `border-warning/40` | border-color:#cd890066 ; border-color:color-mix(in oklab, var(--color-warning) 40%, transparent) |
| `bottom-0` | bottom:calc(var(--spacing) * 0) |
| `bottom-3` | bottom:calc(var(--spacing) * 3) |
| `bottom-4` | bottom:calc(var(--spacing) * 4) |
| `bottom-6` | bottom:calc(var(--spacing) * 6) |
| `bottom-full` | bottom:100% |
| `break-all` | word-break:break-all |
| `break-words` | overflow-wrap:break-word |
| `button-gradient` | background-image:linear-gradient(to bottom right,#191a1d,#747689) ; background-color:#484a58;background-image:none |
| `chat-composer-input-surface` | border-color:#0000 |
| `col-span-3` | grid-column:span 3/span 3 |
| `col-span-full` | grid-column:1/-1 |
| `col-start-1` | grid-column-start:1 |
| `content-center` | align-content:center |
| `cursor-default` | cursor:default |
| `cursor-grab` | cursor:grab |
| `cursor-help` | cursor:help |
| `cursor-not-allowed` | cursor:not-allowed |
| `cursor-pointer` | cursor:pointer |
| `cursor-text` | cursor:text |
| `cursor-zoom-in` | cursor:zoom-in |
| `dark` | background-color:#484a58;background-image:none ; --color-highspeed-accent:#b392ff;--color-highspeed-composer-surface:#151515;--color-highspeed-composer-border-pre-animation:transparent;--color-highspeed-glow-01:#a888f203;--color-highspeed-glow-04:#a888f20a;--color-highspeed-glow-15:#a888f226;--color-highspeed-glow-26:#a888f242;--color-highspeed-particle:#b392ffc2;--color-highspeed-particle-highlight:#b392ff;--color-highspeed-particle-highlight-glow:#b392ffad;--color-highspeed-model-sweep-highlight:#f0e9ff ; --highspeed-sidebar-model-icon:#b09aff;--highspeed-sidebar-model-gradient:linear-gradient(90deg, #9b85ff 0%, #b09aff 50%, #ceacff 100%);--highspeed-sidebar-card-surface:transparent;--highspeed-sidebar-card-outline:transparent;--highspeed-share-card-light-background-opacity:0;--highspeed-share-card-dark-background-opacity:1;--color-highspeed-share-card-view-border:#ffffff70;--color-highspeed-share-foreground:#e7e4ed;--color-highspeed-share-foreground-subtle:#c8c2d2;--color-highspeed-share-control-hover:#ffffff1a;--color-highspeed-share-dialog-surface:#101010;--color-highspeed-share-dialog-border:#ffffff1f;--highspeed-share-dialog-box-shadow:0 63px 18px #0000, 0 41px 16px #00000003, 0 23px 14px #0000000d, 0 10px 10px #00000017, 0 3px 6px #0000001a;--color-highspeed-share-dialog-foreground:#f7f5fa;--color-highspeed-share-dialog-muted:#e7e4eda3;--color-highspeed-share-dialog-noise-dark-opacity:.06;--color-highspeed-share-dialog-noise-light-opacity:0;--color-highspeed-share-dialog-noise-opacity:1;--color-highspeed-share-dialog-control-hover:#ffffff1a;--color-highspeed-share-dialog-stats-border:#ffffff1a;--color-highspeed-share-dialog-bars-start:#ef6f72;--color-highspeed-share-dialog-bars-mid:#f1c86f;--color-highspeed-share-dialog-bars-end:#82d990;--color-highspeed-share-dialog-marker-glow:#73dd8499;--color-highspeed-share-dialog-benefit-text:#adadad;--color-highspeed-share-dialog-pill-text:#adadad;--color-highspeed-share-dialog-input-border:#ffffff1a;--color-highspeed-share-dialog-bars-baseline:#ffffff2e ; --animated-gradient-text-strong:#fff;--animated-gradient-text-soft:#fff3;--color-background:var(--color-neutral-900);--color-background-alt:#26262699 ; --color-background-alt:color-mix(in oklab, var(--color-neutral-800) 60%, transparent) ; --color-background-win-alt:var(--color-neutral-800);--color-brand:var(--color-sky-500);--color-trajectory-user:#60a5fa;--color-trajectory-assistant:#2dd4bf;--color-trajectory-reasoning:#a78bfa;--color-trajectory-tool-call:#f59e0b;--color-trajectory-tool-result:#38bdf8;--color-workflow-rule:#fff1 ; --color-workflow-rule:color-mix(in oklab, var(--color-white) 6.5%, transparent) ; --color-workflow-trace:#ffffff59 ; --color-workflow-trace:color-mix(in oklab, var(--color-white) 35%, transparent) ; --color-workflow-trace-strong:#fff9 ; --color-workflow-trace-strong:color-mix(in oklab, var(--color-white) 60%, transparent) ; --color-accent:#052f4a80 ; --color-accent:color-mix(in oklab, var(--color-sky-950) 50%, transparent) ; --color-find-highlight:#713f12;--color-find-highlight-active:#a16207;--color-border:#fafafa1a ; --color-border:color-mix(in oklab, var(--color-neutral-50) 10%, transparent) ; --color-border-hover:#fafafa4d ; --color-border-hover:color-mix(in oklab, var(--color-neutral-50) 30%, transparent) ; --color-hover:var(--color-surface-hover);--color-selected:#ffffff1a ; --color-selected:color-mix(in oklab, var(--color-white) 10%, transparent) ; --color-header:var(--color-neutral-900);--color-panel:var(--color-neutral-900);--color-sidebar:var(--color-neutral-950);--color-surface:#ffffff0d ; --color-surface:color-mix(in oklab, var(--color-white) 5%, transparent) ; --color-surface-hover:#ffffff1a ; --color-surface-hover:color-mix(in oklab, var(--color-white) 10%, transparent) ; --color-card:var(--color-neutral-800);--color-card-selected:var(--color-neutral-700);--color-card-border:var(--color-border);--color-popover:var(--color-neutral-800);--color-popover-foreground:var(--color-foreground);--color-popover-header:var(--color-neutral-700);--color-popover-border:var(--color-border);--color-input:var(--color-neutral-800);--color-input-focused:var(--color-neutral-950);--color-input-border:var(--color-border);--color-input-border-hover:var(--color-border-hover);--color-input-border-focused:var(--color-brand);--color-terminal-bg:var(--color-neutral-950);--color-terminal-fg:var(--color-neutral-50);--color-terminal-cursor:var(--color-neutral-50);--color-terminal-cursor-accent:var(--color-neutral-950);--color-terminal-selection:#00a5ef42 ; --color-terminal-selection:color-mix(in oklab, var(--color-sky-500) 26%, transparent) ; --color-terminal-selection-inactive:oklab(98.5% 0 0/.16) ; --color-terminal-selection-inactive:color-mix(in oklab, var(--color-neutral-50) 16%, transparent) ; --color-terminal-black:var(--color-neutral-800);--color-terminal-red:var(--color-red-600);--color-terminal-green:var(--color-green-600);--color-terminal-yellow:var(--color-yellow-600);--color-terminal-blue:var(--color-sky-600);--color-terminal-magenta:var(--color-fuchsia-600);--color-terminal-cyan:var(--color-cyan-600);--color-terminal-white:var(--color-neutral-200);--color-terminal-bright-black:var(--color-neutral-500);--color-terminal-bright-red:var(--color-red-600);--color-terminal-bright-green:var(--color-green-600);--color-terminal-bright-yellow:var(--color-yellow-600);--color-terminal-bright-blue:var(--color-sky-600);--color-terminal-bright-magenta:var(--color-fuchsia-600);--color-terminal-bright-cyan:var(--color-cyan-600);--color-terminal-bright-white:var(--color-neutral-50);--color-usage-chart-1:var(--color-sky-500);--color-usage-chart-2:var(--color-teal-400);--color-usage-chart-3:var(--color-violet-400);--color-usage-chart-4:var(--color-rose-400);--color-usage-chart-5:var(--color-indigo-400);--color-usage-chart-6:var(--color-cyan-400);--color-context-breakdown-1:var(--color-sky-400);--color-context-breakdown-2:var(--color-sky-300);--color-context-breakdown-3:var(--color-sky-200);--color-context-breakdown-4:#c3eafe ; --color-context-breakdown-4:color-mix(in oklab, var(--color-sky-200) 84%, var(--color-white)) ; --color-context-breakdown-5:#ccedfe ; --color-context-breakdown-5:color-mix(in oklab, var(--color-sky-200) 72%, var(--color-white)) ; --color-context-breakdown-6:#d4f0ff ; --color-context-breakdown-6:color-mix(in oklab, var(--color-sky-200) 60%, var(--color-white)) ; --color-context-breakdown-7:#ddf3ff ; --color-context-breakdown-7:color-mix(in oklab, var(--color-sky-200) 48%, var(--color-white)) ; --color-usage-heatmap-0:color-mix(in srgb, oklch(74.6% .16 232.661) 0%, color-mix(in oklab, var(--color-neutral-950) 3%, transparent)) ; --color-usage-heatmap-0:color-mix(in oklab, var(--color-sky-400) 0%, var(--color-surface)) ; --color-usage-heatmap-1:color-mix(in srgb, oklch(74.6% .16 232.661) 24%, color-mix(in oklab, var(--color-neutral-950) 3%, transparent)) ; --color-usage-heatmap-1:color-mix(in oklab, var(--color-sky-400) 24%, var(--color-surface)) ; --color-usage-heatmap-2:color-mix(in srgb, oklch(74.6% .16 232.661) 42%, color-mix(in oklab, var(--color-neutral-950) 3%, transparent)) ; --color-usage-heatmap-2:color-mix(in oklab, var(--color-sky-400) 42%, var(--color-surface)) ; --color-usage-heatmap-3:color-mix(in srgb, oklch(74.6% .16 232.661) 62%, color-mix(in oklab, var(--color-neutral-950) 3%, transparent)) ; --color-usage-heatmap-3:color-mix(in oklab, var(--color-sky-400) 62%, var(--color-surface)) ; --color-usage-heatmap-4:color-mix(in srgb, oklch(82.8% .111 230.318) 78%, color-mix(in oklab, var(--color-neutral-950) 3%, transparent)) ; --color-usage-heatmap-4:color-mix(in oklab, var(--color-sky-300) 78%, var(--color-surface)) ; --color-tab:var(--color-neutral-800);--color-tab-active:var(--color-neutral-950);--color-tab-border:var(--color-border);--color-menu:var(--color-neutral-950);--color-menu-hover:var(--color-neutral-900);--color-primary:var(--color-neutral-50);--color-primary-foreground:var(--color-neutral-950);--color-secondary:var(--color-neutral-700);--color-interaction-ask-surface:#052f4a80 ; --color-interaction-ask-surface:color-mix(in oklab, var(--color-sky-950) 50%, transparent) ; --color-interaction-ask-foreground:var(--color-sky-300);--color-interaction-ask-fill:#05df723d ; --color-interaction-ask-fill:color-mix(in oklab, var(--color-green-400) 24%, transparent) ; --color-interaction-confirmation-surface:oklab(26.6% -.0578814 .0295761/.45) ; --color-interaction-confirmation-surface:color-mix(in oklab, var(--color-green-950) 45%, transparent) ; --color-interaction-confirmation-foreground:var(--color-green-300);--color-foreground:var(--color-neutral-200);--color-foreground-subtle:#e5e5e599 ; --color-foreground-subtle:color-mix(in oklab, var(--color-neutral-200) 60%, transparent) ; --color-foreground-subtlest:#e5e5e54d ; --color-foreground-subtlest:color-mix(in oklab, var(--color-neutral-200) 30%, transparent) ; --lucide-subtlest-opacity:.3;--color-foreground-inverse:var(--color-white);--color-success:var(--color-green-500);--color-success-foreground:var(--color-foreground-inverse);--color-idle-task:var(--color-violet-400);--color-idle-task-surface:#2f0d6880 ; --color-idle-task-surface:color-mix(in oklab, var(--color-violet-950) 50%, transparent) ; --color-destructive:var(--color-red-500);--color-destructive-foreground:var(--color-foreground-inverse);--color-warning:var(--color-yellow-500);--color-feedback-privacy-hint:var(--color-yellow-300);--color-warning-foreground:var(--color-foreground-inverse);--color-diff-added:var(--color-green-500);--color-diff-added-foreground:var(--color-foreground-inverse);--color-diff-removed:var(--color-red-500);--color-diff-removed-foreground:var(--color-foreground-inverse);--color-git-none:var(--color-foreground);--color-git-modified:var(--color-amber-400);--color-git-added:var(--color-teal-400);--color-git-deleted:var(--color-red-400);--color-git-renamed:var(--color-sky-400);--color-git-untracked:var(--color-teal-400);--color-git-ignored:var(--color-foreground-subtlest);--color-git-descendant:var(--color-amber-400);--color-toast:var(--color-neutral-900);--color-tooltip:var(--color-neutral-900);--color-tooltip-foreground:var(--color-neutral-50);--color-tooltip-tag:var(--color-neutral-800);--color-tooltip-tag-foreground:#fafafa99 ; --color-tooltip-tag-foreground:color-mix(in oklab, var(--color-neutral-50) 60%, transparent) ; --color-tag:var(--color-neutral-700);--color-file-node:#00bcfe2e ; --color-file-node:color-mix(in oklab, var(--color-sky-400) 18%, transparent) ; --color-file-node-hover:#00bcfe3d ; --color-file-node-hover:color-mix(in oklab, var(--color-sky-400) 24%, transparent) ; --color-file-node-foreground:var(--color-sky-300);--color-skill-node:#a685ff2e ; --color-skill-node:color-mix(in oklab, var(--color-violet-400) 18%, transparent) ; --color-skill-node-hover:#a685ff3d ; --color-skill-node-hover:color-mix(in oklab, var(--color-violet-400) 24%, transparent) ; --color-skill-node-foreground:var(--color-violet-300);--color-command-node:#cad5e229 ; --color-command-node:color-mix(in oklab, var(--color-slate-300) 16%, transparent) ; --color-command-node-hover:#cad5e238 ; --color-command-node-hover:color-mix(in oklab, var(--color-slate-300) 22%, transparent) ; --color-command-node-foreground:var(--color-slate-300);--color-subagent-node:oklab(77.6963% -.0361366 .0873579/.18);--color-subagent-node-hover:oklab(77.6963% -.0361366 .0873579/.24);--color-subagent-node-foreground:#b7bd75;--color-session-node:#00d3bd2e ; --color-session-node:color-mix(in oklab, var(--color-teal-400) 18%, transparent) ; --color-session-node-hover:#00d3bd3d ; --color-session-node-hover:color-mix(in oklab, var(--color-teal-400) 24%, transparent) ; --color-session-node-foreground:var(--color-teal-300);--color-plugin-node:#fcbb002e ; --color-plugin-node:color-mix(in oklab, var(--color-amber-400) 18%, transparent) ; --color-plugin-node-hover:#fcbb003d ; --color-plugin-node-hover:color-mix(in oklab, var(--color-amber-400) 24%, transparent) ; --color-plugin-node-foreground:var(--color-amber-300);--color-plugin-paid-plan-badge:#5a341b;--color-plugin-paid-plan-badge-foreground:#ffd8b8 |
| `dark:bg-[#484A58]` | background-color:#484a58 |
| `dark:bg-white/5` | background-color:#ffffff0d ; background-color:color-mix(in oklab, var(--color-white) 5%, transparent) |
| `dark:block` | display:block |
| `dark:hidden` | display:none |
| `decoration-dotted` | text-decoration-style:dotted |
| `duration-100` | --tw-duration:.1s;transition-duration:.1s |
| `duration-150` | --tw-duration:.15s;transition-duration:.15s |
| `duration-200` | --tw-duration:.2s;transition-duration:.2s |
| `duration-300` | --tw-duration:.3s;transition-duration:.3s |
| `duration-500` | --tw-duration:.5s;transition-duration:.5s |
| `ease-out` | --tw-ease:var(--ease-out);transition-timing-function:var(--ease-out) |
| `end` | inset-inline-end:var(--spacing) |
| `fill-current` | fill:currentColor |
| `fixed` | position:fixed |
| `flex` | display:flex |
| `flex-1` | flex:1 |
| `flex-col` | flex-direction:column |
| `flex-wrap` | flex-wrap:wrap |
| `flow-root` | display:flow-root |
| `font-medium` | --tw-font-weight:var(--font-weight-medium);font-weight:var(--font-weight-medium) |
| `font-mono` | font-family:var(--font-mono) |
| `font-normal` | --tw-font-weight:var(--font-weight-normal);font-weight:var(--font-weight-normal) |
| `font-semibold` | --tw-font-weight:var(--font-weight-semibold);font-weight:var(--font-weight-semibold) |
| `from-popover` | --tw-gradient-from:var(--color-popover);--tw-gradient-stops:var(--tw-gradient-via-stops,var(--tw-gradient-position), var(--tw-gradient-from) var(--tw-gradient-from-position), var(--tw-gradient-to) var(--tw-gradient-to-position)) |
| `gap-0` | gap:calc(var(--spacing) * 0) |
| `gap-0.5` | gap:calc(var(--spacing) * .5) |
| `gap-1` | gap:calc(var(--spacing) * 1) |
| `gap-1.5` | gap:calc(var(--spacing) * 1.5) |
| `gap-2` | gap:calc(var(--spacing) * 2) |
| `gap-3` | gap:calc(var(--spacing) * 3) |
| `gap-4` | gap:calc(var(--spacing) * 4) |
| `gap-5` | gap:calc(var(--spacing) * 5) |
| `gap-6` | gap:calc(var(--spacing) * 6) |
| `gap-x-2` | column-gap:calc(var(--spacing) * 2) |
| `gap-x-4` | column-gap:calc(var(--spacing) * 4) |
| `gap-y-0.5` | row-gap:calc(var(--spacing) * .5) |
| `gap-y-1` | row-gap:calc(var(--spacing) * 1) |
| `gap-y-2` | row-gap:calc(var(--spacing) * 2) |
| `grid` | display:grid |
| `grid-cols-1` | grid-template-columns:repeat(1,minmax(0,1fr)) |
| `grid-cols-2` | grid-template-columns:repeat(2,minmax(0,1fr)) |
| `grid-cols-3` | grid-template-columns:repeat(3,minmax(0,1fr)) |
| `h-0.5` | height:calc(var(--spacing) * .5) |
| `h-1` | height:calc(var(--spacing) * 1) |
| `h-1.5` | height:calc(var(--spacing) * 1.5) |
| `h-10` | height:calc(var(--spacing) * 10) |
| `h-12` | height:calc(var(--spacing) * 12) |
| `h-2` | height:calc(var(--spacing) * 2) |
| `h-2.5` | height:calc(var(--spacing) * 2.5) |
| `h-3` | height:calc(var(--spacing) * 3) |
| `h-3.5` | height:calc(var(--spacing) * 3.5) |
| `h-5` | height:calc(var(--spacing) * 5) |
| `h-6` | height:calc(var(--spacing) * 6) |
| `h-7` | height:calc(var(--spacing) * 7) |
| `h-8` | height:calc(var(--spacing) * 8) |
| `h-[1.3em]` | height:1.3em |
| `h-[18px]` | height:18px |
| `h-[85vh]` | height:85vh |
| `h-[calc(100dvh-1rem)]` | height:calc(100dvh - 1rem) |
| `h-auto` | height:auto |
| `h-full` | height:100% |
| `h-px` | height:1px |
| `hidden` | display:none |
| `highspeed-animated-gradient-text` | --animated-gradient-text-strong:var(--color-icon-purple);--animated-gradient-text-soft:#e12afb38 ; --animated-gradient-text-soft:color-mix(in srgb, var(--color-icon-purple) 22%, transparent) |
| `highspeed-composer-surface` | isolation:isolate;border-radius:16px;transition:border-color 1.04s cubic-bezier(.45,0,.55,1),box-shadow 1.04s cubic-bezier(.45,0,.55,1);background-color:var(--color-input)!important;border:1px solid #0000!important |
| `highspeed-inline-countdown` | animation:.42s cubic-bezier(.23,1,.32,1) both highspeed-inline-countdown-enter ; animation:none |
| `highspeed-model-trigger-label-sweep` | --highspeed-model-sweep-width:96px;color:#0000;background-color:var(--color-highspeed-accent);background-image:linear-gradient(90deg, transparent 0%, transparent 6%, var(--color-highspeed-model-sweep-highlight) 22%, var(--color-highspeed-model-sweep-highlight) 34%, var(--color-highspeed-model-sweep-highlight) 66%, var(--color-highspeed-model-sweep-highlight) 78%, transparent 94%, transparent 100%) ; background-image:linear-gradient(90deg, transparent 0%, transparent 6%, color-mix(in oklab, var(--color-highspeed-model-sweep-highlight) 90%, var(--color-highspeed-accent)) 22%, var(--color-highspeed-model-sweep-highlight) 34%, var(--color-highspeed-model-sweep-highlight) 66%, color-mix(in oklab, var(--color-highspeed-model-sweep-highlight) 90%, var(--color-highspeed-accent)) 78%, transparent 94%, transparent 100%) ; background-position:calc(0% - var(--highspeed-model-sweep-width)) 0;background-repeat:no-repeat;background-size:var(--highspeed-model-sweep-width) 100%;-webkit-text-fill-color:transparent;-webkit-background-clip:text;background-clip:text ; color:var(--color-highspeed-accent);-webkit-text-fill-color:currentcolor;background-image:none;animation:none |
| `inline` | display:inline |
| `inline-block` | display:inline-block |
| `inline-flex` | display:inline-flex |
| `inset-0` | inset:calc(var(--spacing) * 0) |
| `inset-x-0` | inset-inline:calc(var(--spacing) * 0) |
| `inset-y-0` | inset-block:calc(var(--spacing) * 0) |
| `invisible` | visibility:hidden |
| `items-baseline` | align-items:baseline |
| `items-center` | align-items:center |
| `items-end` | align-items:flex-end |
| `items-start` | align-items:flex-start |
| `justify-between` | justify-content:space-between |
| `justify-center` | justify-content:center |
| `justify-end` | justify-content:flex-end |
| `justify-start` | justify-content:flex-start |
| `leading-4` | --tw-leading:calc(var(--spacing) * 4);line-height:calc(var(--spacing) * 4) |
| `leading-4.5` | --tw-leading:calc(var(--spacing) * 4.5);line-height:calc(var(--spacing) * 4.5) |
| `leading-5` | --tw-leading:calc(var(--spacing) * 5);line-height:calc(var(--spacing) * 5) |
| `leading-6` | --tw-leading:calc(var(--spacing) * 6);line-height:calc(var(--spacing) * 6) |
| `leading-[1.25]` | --tw-leading:1.25;line-height:1.25 |
| `leading-none` | --tw-leading:1;line-height:1 |
| `leading-normal` | --tw-leading:var(--leading-normal);line-height:var(--leading-normal) |
| `leading-tight` | --tw-leading:var(--leading-tight);line-height:var(--leading-tight) |
| `left-0` | left:calc(var(--spacing) * 0) |
| `left-1/2` | left:50% |
| `left-3` | left:calc(var(--spacing) * 3) |
| `left-4` | left:calc(var(--spacing) * 4) |
| `lg:min-w-[720px]` | min-width:720px |
| `line-clamp-2` | -webkit-line-clamp:2;-webkit-box-orient:vertical;display:-webkit-box;overflow:hidden |
| `line-clamp-3` | -webkit-line-clamp:3;-webkit-box-orient:vertical;display:-webkit-box;overflow:hidden |
| `line-through` | text-decoration-line:line-through |
| `list-decimal` | list-style-type:decimal |
| `list-disc` | list-style-type:disc |
| `list-inside` | list-style-position:inside |
| `list-item` | display:list-item |
| `list-outside` | list-style-position:outside |
| `markdown-image-loading-shimmer` | background-color:var(--color-card);background-image:linear-gradient(135deg, var(--color-card) 0%, var(--color-background) 50%, var(--color-card) 100%);background-size:240% 100%;animation:1.8s linear infinite markdown-image-loading-shimmer ; animation:none |
| `marker:text-foreground-subtlest` | color:var(--color-foreground-subtlest) ; --lucide-stroke:rgb(from currentColor r g b / 1);--lucide-filter:opacity(var(--lucide-subtlest-opacity)) |
| `max-h-25` | max-height:calc(var(--spacing) * 25) |
| `max-h-40` | max-height:calc(var(--spacing) * 40) |
| `max-h-56` | max-height:calc(var(--spacing) * 56) |
| `max-h-60` | max-height:calc(var(--spacing) * 60) |
| `max-h-64` | max-height:calc(var(--spacing) * 64) |
| `max-h-72` | max-height:calc(var(--spacing) * 72) |
| `max-h-90` | max-height:calc(var(--spacing) * 90) |
| `max-h-[262px]` | max-height:262px |
| `max-h-[40vh]` | max-height:40vh |
| `max-h-[5lh]` | max-height:5lh |
| `max-h-[calc(100%-6rem)]` | max-height:calc(100% - 6rem) |
| `max-h-[calc(100vh-2rem)]` | max-height:calc(100vh - 2rem) |
| `max-h-full` | max-height:100% |
| `max-md:[--markdown-table-layout-left-inset:8px]` | --markdown-table-layout-left-inset:8px |
| `max-md:[--markdown-table-layout-right-inset:8px]` | --markdown-table-layout-right-inset:8px |
| `max-md:flex` | display:flex |
| `max-md:hidden` | display:none |
| `max-md:inline-flex` | display:inline-flex |
| `max-md:left-2` | left:calc(var(--spacing) * 2) |
| `max-md:line-clamp-4` | -webkit-line-clamp:4;-webkit-box-orient:vertical;display:-webkit-box;overflow:hidden |
| `max-md:px-2` | padding-inline:calc(var(--spacing) * 2) |
| `max-sm:basis-full` | flex-basis:100% |
| `max-sm:flex-wrap` | flex-wrap:wrap |
| `max-sm:grid-cols-1` | grid-template-columns:repeat(1,minmax(0,1fr)) |
| `max-w-(--radix-dropdown-menu-content-available-width)` | max-width:var(--radix-dropdown-menu-content-available-width) |
| `max-w-1/2` | max-width:50% |
| `max-w-28` | max-width:calc(var(--spacing) * 28) |
| `max-w-2xl` | max-width:var(--container-2xl) |
| `max-w-32` | max-width:calc(var(--spacing) * 32) |
| `max-w-40` | max-width:calc(var(--spacing) * 40) |
| `max-w-48` | max-width:calc(var(--spacing) * 48) |
| `max-w-4xl` | max-width:var(--container-4xl) |
| `max-w-64` | max-width:calc(var(--spacing) * 64) |
| `max-w-72` | max-width:calc(var(--spacing) * 72) |
| `max-w-96` | max-width:calc(var(--spacing) * 96) |
| `max-w-[calc(100vw-1.5rem)]` | max-width:calc(100vw - 1.5rem) |
| `max-w-[calc(100vw-1rem)]` | max-width:calc(100vw - 1rem) |
| `max-w-[calc(100vw-2rem)]` | max-width:calc(100vw - 2rem) |
| `max-w-full` | max-width:100% |
| `max-w-md` | max-width:var(--container-md) |
| `max-w-none` | max-width:none |
| `max-w-xl` | max-width:var(--container-xl) |
| `mb-1` | margin-bottom:calc(var(--spacing) * 1) |
| `mb-10` | margin-bottom:calc(var(--spacing) * 10) |
| `mb-2` | margin-bottom:calc(var(--spacing) * 2) |
| `mb-3` | margin-bottom:calc(var(--spacing) * 3) |
| `mb-4` | margin-bottom:calc(var(--spacing) * 4) |
| `mb-6` | margin-bottom:calc(var(--spacing) * 6) |
| `md:h-44` | height:calc(var(--spacing) * 44) |
| `md:leading-relaxed` | --tw-leading:var(--leading-relaxed);line-height:var(--leading-relaxed) |
| `md:min-w-[640px]` | min-width:640px |
| `md:shrink-0` | flex-shrink:0 |
| `md:w-56` | width:calc(var(--spacing) * 56) |
| `min-h-0` | min-height:calc(var(--spacing) * 0) |
| `min-h-1` | min-height:calc(var(--spacing) * 1) |
| `min-h-10` | min-height:calc(var(--spacing) * 10) |
| `min-h-13` | min-height:calc(var(--spacing) * 13) |
| `min-h-2` | min-height:calc(var(--spacing) * 2) |
| `min-h-32` | min-height:calc(var(--spacing) * 32) |
| `min-h-5` | min-height:calc(var(--spacing) * 5) |
| `min-h-7` | min-height:calc(var(--spacing) * 7) |
| `min-h-8` | min-height:calc(var(--spacing) * 8) |
| `min-h-full` | min-height:100% |
| `min-w-0` | min-width:calc(var(--spacing) * 0) |
| `min-w-1.5` | min-width:calc(var(--spacing) * 1.5) |
| `min-w-10` | min-width:calc(var(--spacing) * 10) |
| `min-w-12` | min-width:calc(var(--spacing) * 12) |
| `min-w-16` | min-width:calc(var(--spacing) * 16) |
| `min-w-2` | min-width:calc(var(--spacing) * 2) |
| `min-w-20` | min-width:calc(var(--spacing) * 20) |
| `min-w-32` | min-width:calc(var(--spacing) * 32) |
| `min-w-40` | min-width:calc(var(--spacing) * 40) |
| `min-w-44` | min-width:calc(var(--spacing) * 44) |
| `min-w-48` | min-width:calc(var(--spacing) * 48) |
| `min-w-8` | min-width:calc(var(--spacing) * 8) |
| `min-w-full` | min-width:100% |
| `min-w-max` | min-width:max-content |
| `ml-0.5` | margin-left:calc(var(--spacing) * .5) |
| `ml-2` | margin-left:calc(var(--spacing) * 2) |
| `ml-auto` | margin-left:auto |
| `motion-reduce:animate-none` | animation:none |
| `motion-reduce:transition-none` | transition-property:none |
| `mr-1` | margin-right:calc(var(--spacing) * 1) |
| `mr-2` | margin-right:calc(var(--spacing) * 2) |
| `mt-0.5` | margin-top:calc(var(--spacing) * .5) |
| `mt-1` | margin-top:calc(var(--spacing) * 1) |
| `mt-2` | margin-top:calc(var(--spacing) * 2) |
| `mt-3` | margin-top:calc(var(--spacing) * 3) |
| `mt-6` | margin-top:calc(var(--spacing) * 6) |
| `mt-px` | margin-top:1px |
| `mx-0.5` | margin-inline:calc(var(--spacing) * .5) |
| `mx-auto` | margin-inline:auto |
| `my-0` | margin-block:calc(var(--spacing) * 0) |
| `my-1` | margin-block:calc(var(--spacing) * 1) |
| `my-3` | margin-block:calc(var(--spacing) * 3) |
| `my-4` | margin-block:calc(var(--spacing) * 4) |
| `object-contain` | object-fit:contain |
| `object-cover` | object-fit:cover |
| `opacity-0` | opacity:0 |
| `opacity-100` | opacity:1 |
| `opacity-50` | opacity:.5 |
| `opacity-60` | opacity:.6 |
| `opacity-70` | opacity:.7 |
| `opacity-80` | opacity:.8 |
| `origin-(--radix-context-menu-content-transform-origin)` | transform-origin:var(--radix-context-menu-content-transform-origin) |
| `origin-bottom` | transform-origin:bottom |
| `origin-left` | transform-origin:0 |
| `outline` | outline-style:var(--tw-outline-style);outline-width:1px |
| `outline-0` | outline-style:var(--tw-outline-style);outline-width:0 |
| `outline-hidden` | --tw-outline-style:none;outline-style:none ; outline-offset:2px;outline:2px solid #0000 |
| `outline-none` | --tw-outline-style:none;outline-style:none |
| `overflow-auto` | overflow:auto |
| `overflow-hidden` | overflow:hidden |
| `overflow-visible` | overflow:visible |
| `overflow-x-auto` | overflow-x:auto |
| `overflow-x-hidden` | overflow-x:hidden |
| `overflow-y-auto` | overflow-y:auto |
| `overflow-y-visible` | overflow-y:visible |
| `overscroll-contain` | overscroll-behavior:contain |
| `p-0` | padding:calc(var(--spacing) * 0) |
| `p-1` | padding:calc(var(--spacing) * 1) |
| `p-1.5` | padding:calc(var(--spacing) * 1.5) |
| `p-10` | padding:calc(var(--spacing) * 10) |
| `p-2` | padding:calc(var(--spacing) * 2) |
| `p-3` | padding:calc(var(--spacing) * 3) |
| `p-3.5` | padding:calc(var(--spacing) * 3.5) |
| `p-4` | padding:calc(var(--spacing) * 4) |
| `p-6` | padding:calc(var(--spacing) * 6) |
| `pb-1` | padding-bottom:calc(var(--spacing) * 1) |
| `pb-12` | padding-bottom:calc(var(--spacing) * 12) |
| `pb-2` | padding-bottom:calc(var(--spacing) * 2) |
| `pb-3` | padding-bottom:calc(var(--spacing) * 3) |
| `pb-4` | padding-bottom:calc(var(--spacing) * 4) |
| `pb-5` | padding-bottom:calc(var(--spacing) * 5) |
| `pb-7` | padding-bottom:calc(var(--spacing) * 7) |
| `pl-0` | padding-left:calc(var(--spacing) * 0) |
| `pl-1` | padding-left:calc(var(--spacing) * 1) |
| `pl-2` | padding-left:calc(var(--spacing) * 2) |
| `pl-3` | padding-left:calc(var(--spacing) * 3) |
| `pl-3.5` | padding-left:calc(var(--spacing) * 3.5) |
| `pl-5` | padding-left:calc(var(--spacing) * 5) |
| `pl-6` | padding-left:calc(var(--spacing) * 6) |
| `place-items-center` | place-items:center |
| `pointer-events-auto` | pointer-events:auto |
| `pointer-events-none` | pointer-events:none |
| `pr-0.5` | padding-right:calc(var(--spacing) * .5) |
| `pr-1` | padding-right:calc(var(--spacing) * 1) |
| `pr-1.5` | padding-right:calc(var(--spacing) * 1.5) |
| `pr-2` | padding-right:calc(var(--spacing) * 2) |
| `pr-3` | padding-right:calc(var(--spacing) * 3) |
| `pr-4` | padding-right:calc(var(--spacing) * 4) |
| `pr-6` | padding-right:calc(var(--spacing) * 6) |
| `pr-8` | padding-right:calc(var(--spacing) * 8) |
| `pr-[5px]` | padding-right:5px |
| `pr-px` | padding-right:1px |
| `pt-0` | padding-top:calc(var(--spacing) * 0) |
| `pt-0.5` | padding-top:calc(var(--spacing) * .5) |
| `pt-1.5` | padding-top:calc(var(--spacing) * 1.5) |
| `pt-14` | padding-top:calc(var(--spacing) * 14) |
| `pt-2` | padding-top:calc(var(--spacing) * 2) |
| `pt-3` | padding-top:calc(var(--spacing) * 3) |
| `pt-4` | padding-top:calc(var(--spacing) * 4) |
| `pt-5` | padding-top:calc(var(--spacing) * 5) |
| `px-0` | padding-inline:calc(var(--spacing) * 0) |
| `px-1` | padding-inline:calc(var(--spacing) * 1) |
| `px-1.5` | padding-inline:calc(var(--spacing) * 1.5) |
| `px-2` | padding-inline:calc(var(--spacing) * 2) |
| `px-2.5` | padding-inline:calc(var(--spacing) * 2.5) |
| `px-3` | padding-inline:calc(var(--spacing) * 3) |
| `px-4` | padding-inline:calc(var(--spacing) * 4) |
| `px-6` | padding-inline:calc(var(--spacing) * 6) |
| `py-0` | padding-block:calc(var(--spacing) * 0) |
| `py-0.5` | padding-block:calc(var(--spacing) * .5) |
| `py-1` | padding-block:calc(var(--spacing) * 1) |
| `py-1.5` | padding-block:calc(var(--spacing) * 1.5) |
| `py-2` | padding-block:calc(var(--spacing) * 2) |
| `py-3` | padding-block:calc(var(--spacing) * 3) |
| `py-4` | padding-block:calc(var(--spacing) * 4) |
| `py-[18px]` | padding-block:18px |
| `py-px` | padding-block:1px |
| `relative` | position:relative |
| `right-0` | right:calc(var(--spacing) * 0) |
| `right-0.5` | right:calc(var(--spacing) * .5) |
| `right-1` | right:calc(var(--spacing) * 1) |
| `right-4` | right:calc(var(--spacing) * 4) |
| `ring-0` | --tw-ring-shadow:var(--tw-ring-inset,) 0 0 0 calc(0px + var(--tw-ring-offset-width)) var(--tw-ring-color,currentcolor);box-shadow:var(--tw-inset-shadow), var(--tw-inset-ring-shadow), var(--tw-ring-offset-shadow), var(--tw-ring-shadow), var(--tw-shadow) |
| `ring-1` | --tw-ring-shadow:var(--tw-ring-inset,) 0 0 0 calc(1px + var(--tw-ring-offset-width)) var(--tw-ring-color,currentcolor);box-shadow:var(--tw-inset-shadow), var(--tw-inset-ring-shadow), var(--tw-ring-offset-shadow), var(--tw-ring-shadow), var(--tw-shadow) |
| `ring-2` | --tw-ring-shadow:var(--tw-ring-inset,) 0 0 0 calc(2px + var(--tw-ring-offset-width)) var(--tw-ring-color,currentcolor);box-shadow:var(--tw-inset-shadow), var(--tw-inset-ring-shadow), var(--tw-ring-offset-shadow), var(--tw-ring-shadow), var(--tw-shadow) |
| `ring-brand/30` | --tw-ring-color:#00bcfe4d ; --tw-ring-color:color-mix(in oklab, var(--color-brand) 30%, transparent) |
| `ring-destructive/30` | --tw-ring-color:#e400144d ; --tw-ring-color:color-mix(in oklab, var(--color-destructive) 30%, transparent) |
| `ring-inset` | --tw-ring-inset:inset |
| `ring-popover-border` | --tw-ring-color:var(--color-popover-border) |
| `rotate-0` | rotate:0deg |
| `rotate-45` | rotate:45deg |
| `rotate-90` | rotate:90deg |
| `rounded` | border-radius:.25rem |
| `rounded-2xl` | border-radius:var(--radius-2xl) |
| `rounded-[8px]` | border-radius:8px |
| `rounded-[inherit]` | border-radius:inherit |
| `rounded-full` | border-radius:3.40282e38px |
| `rounded-l-none` | border-top-left-radius:0;border-bottom-left-radius:0 |
| `rounded-l-xl` | border-top-left-radius:var(--radius-xl);border-bottom-left-radius:var(--radius-xl) |
| `rounded-lg` | border-radius:var(--radius-lg) |
| `rounded-md` | border-radius:var(--radius-md) |
| `rounded-none` | border-radius:0 |
| `rounded-r-lg` | border-top-right-radius:var(--radius-lg);border-bottom-right-radius:var(--radius-lg) |
| `rounded-r-xl` | border-top-right-radius:var(--radius-xl);border-bottom-right-radius:var(--radius-xl) |
| `rounded-sm` | border-radius:var(--radius-sm) |
| `rounded-t-2xl` | border-top-left-radius:var(--radius-2xl);border-top-right-radius:var(--radius-2xl) |
| `rounded-tr-xs` | border-top-right-radius:var(--radius-xs) |
| `rounded-xl` | border-radius:var(--radius-xl) |
| `row-start-1` | grid-row-start:1 |
| `running` | animation-play-state:running |
| `select-none` | -webkit-user-select:none;user-select:none |
| `select-text` | -webkit-user-select:text;user-select:text |
| `self-baseline` | align-self:baseline |
| `self-center` | align-self:center |
| `self-end` | align-self:flex-end |
| `self-start` | align-self:flex-start |
| `self-stretch` | align-self:stretch |
| `shadow-2xl` | --tw-shadow:0 25px 50px -12px var(--tw-shadow-color,#00000040);box-shadow:var(--tw-inset-shadow), var(--tw-inset-ring-shadow), var(--tw-ring-offset-shadow), var(--tw-ring-shadow), var(--tw-shadow) |
| `shadow-lg` | --tw-shadow:0 10px 15px -3px var(--tw-shadow-color,#0000001a), 0 4px 6px -4px var(--tw-shadow-color,#0000001a);box-shadow:var(--tw-inset-shadow), var(--tw-inset-ring-shadow), var(--tw-ring-offset-shadow), var(--tw-ring-shadow), var(--tw-shadow) |
| `shadow-md` | --tw-shadow:0 4px 6px -1px var(--tw-shadow-color,#0000001a), 0 2px 4px -2px var(--tw-shadow-color,#0000001a);box-shadow:var(--tw-inset-shadow), var(--tw-inset-ring-shadow), var(--tw-ring-offset-shadow), var(--tw-ring-shadow), var(--tw-shadow) |
| `shadow-none` | --tw-shadow:0 0 #0000;box-shadow:var(--tw-inset-shadow), var(--tw-inset-ring-shadow), var(--tw-ring-offset-shadow), var(--tw-ring-shadow), var(--tw-shadow) |
| `shadow-sm` | --tw-shadow:0 1px 3px 0 var(--tw-shadow-color,#0000001a), 0 1px 2px -1px var(--tw-shadow-color,#0000001a);box-shadow:var(--tw-inset-shadow), var(--tw-inset-ring-shadow), var(--tw-ring-offset-shadow), var(--tw-ring-shadow), var(--tw-shadow) |
| `shadow-xl/5` | --tw-shadow-alpha:5%;--tw-shadow:0 20px 25px -5px var(--tw-shadow-color,oklab(0% 0 0/.05)), 0 8px 10px -6px var(--tw-shadow-color,oklab(0% 0 0/.05));box-shadow:var(--tw-inset-shadow), var(--tw-inset-ring-shadow), var(--tw-ring-offset-shadow), var(--tw-ring-shadow), var(--tw-shadow) |
| `shadow-xs` | --tw-shadow:0 1px 2px 0 var(--tw-shadow-color,#0000000d);box-shadow:var(--tw-inset-shadow), var(--tw-inset-ring-shadow), var(--tw-ring-offset-shadow), var(--tw-ring-shadow), var(--tw-shadow) |
| `shrink` | flex-shrink:1 |
| `shrink-0` | flex-shrink:0 |
| `size-1.5` | width:calc(var(--spacing) * 1.5);height:calc(var(--spacing) * 1.5) |
| `size-11` | width:calc(var(--spacing) * 11);height:calc(var(--spacing) * 11) |
| `size-12` | width:calc(var(--spacing) * 12);height:calc(var(--spacing) * 12) |
| `size-2` | width:calc(var(--spacing) * 2);height:calc(var(--spacing) * 2) |
| `size-2.5` | width:calc(var(--spacing) * 2.5);height:calc(var(--spacing) * 2.5) |
| `size-20` | width:calc(var(--spacing) * 20);height:calc(var(--spacing) * 20) |
| `size-3` | width:calc(var(--spacing) * 3);height:calc(var(--spacing) * 3) |
| `size-3.5` | width:calc(var(--spacing) * 3.5);height:calc(var(--spacing) * 3.5) |
| `size-4` | width:calc(var(--spacing) * 4);height:calc(var(--spacing) * 4) |
| `size-4.5` | width:calc(var(--spacing) * 4.5);height:calc(var(--spacing) * 4.5) |
| `size-5` | width:calc(var(--spacing) * 5);height:calc(var(--spacing) * 5) |
| `size-6` | width:calc(var(--spacing) * 6);height:calc(var(--spacing) * 6) |
| `size-7` | width:calc(var(--spacing) * 7);height:calc(var(--spacing) * 7) |
| `size-8` | width:calc(var(--spacing) * 8);height:calc(var(--spacing) * 8) |
| `size-9` | width:calc(var(--spacing) * 9);height:calc(var(--spacing) * 9) |
| `size-full` | width:100%;height:100% |
| `sm:flex-row` | flex-direction:row |
| `sm:gap-2` | gap:calc(var(--spacing) * 2) |
| `sm:gap-3` | gap:calc(var(--spacing) * 3) |
| `sm:grid-cols-2` | grid-template-columns:repeat(2,minmax(0,1fr)) |
| `sm:h-[calc(100dvh-2rem)]` | height:calc(100dvh - 2rem) |
| `sm:items-baseline` | align-items:baseline |
| `sm:items-center` | align-items:center |
| `sm:justify-between` | justify-content:space-between |
| `sm:max-h-[calc(100vh-8rem)]` | max-height:calc(100vh - 8rem) |
| `sm:max-w-[calc(100vw-8rem)]` | max-width:calc(100vw - 8rem) |
| `sm:mb-8` | margin-bottom:calc(var(--spacing) * 8) |
| `sm:p-8` | padding:calc(var(--spacing) * 8) |
| `sm:self-auto` | align-self:auto |
| `sm:text-2xl` | font-size:var(--text-2xl);line-height:var(--tw-leading,var(--text-2xl--line-height)) |
| `sm:text-ui-lg` | font-size:var(--text-ui-lg) |
| `sm:w-[calc(100vw-2rem)]` | width:calc(100vw - 2rem) |
| `space-y-0.5` | --tw-space-y-reverse:0;margin-block-start:calc(calc(var(--spacing) * .5) * var(--tw-space-y-reverse));margin-block-end:calc(calc(var(--spacing) * .5) * calc(1 - var(--tw-space-y-reverse))) |
| `space-y-1` | --tw-space-y-reverse:0;margin-block-start:calc(calc(var(--spacing) * 1) * var(--tw-space-y-reverse));margin-block-end:calc(calc(var(--spacing) * 1) * calc(1 - var(--tw-space-y-reverse))) |
| `space-y-1.5` | --tw-space-y-reverse:0;margin-block-start:calc(calc(var(--spacing) * 1.5) * var(--tw-space-y-reverse));margin-block-end:calc(calc(var(--spacing) * 1.5) * calc(1 - var(--tw-space-y-reverse))) |
| `space-y-2` | --tw-space-y-reverse:0;margin-block-start:calc(calc(var(--spacing) * 2) * var(--tw-space-y-reverse));margin-block-end:calc(calc(var(--spacing) * 2) * calc(1 - var(--tw-space-y-reverse))) |
| `space-y-3` | --tw-space-y-reverse:0;margin-block-start:calc(calc(var(--spacing) * 3) * var(--tw-space-y-reverse));margin-block-end:calc(calc(var(--spacing) * 3) * calc(1 - var(--tw-space-y-reverse))) |
| `sr-only` | clip-path:inset(50%);white-space:nowrap;border-width:0;width:1px;height:1px;margin:-1px;padding:0;position:absolute;overflow:hidden |
| `start` | inset-inline-start:var(--spacing) |
| `sticky` | position:sticky |
| `stroke-border` | stroke:var(--color-border) |
| `stroke-current` | stroke:currentColor |
| `stroke-destructive` | stroke:var(--color-destructive) |
| `table` | display:table |
| `tabular-nums` | --tw-numeric-spacing:tabular-nums;font-variant-numeric:var(--tw-ordinal,) var(--tw-slashed-zero,) var(--tw-numeric-figure,) var(--tw-numeric-spacing,) var(--tw-numeric-fraction,) |
| `tag` | position:absolute;right:0 ; left:0;right:auto |
| `text-3xl/[1.2]` | font-size:var(--text-3xl);line-height:1.2 |
| `text-[#191A1D]` | color:#191a1d ; --lucide-stroke:currentColor;--lucide-filter:initial |
| `text-[7px]` | font-size:7px |
| `text-[length:var(--v4-draft-greeting-font-size)]/[1.2]` | font-size:var(--v4-draft-greeting-font-size);line-height:1.2 |
| `text-[var(--color-foreground-subtle)]` | color:var(--color-foreground-subtle) |
| `text-[var(--color-foreground-subtlest)]` | color:var(--color-foreground-subtlest) |
| `text-[var(--color-warning)]` | color:var(--color-warning) |
| `text-brand` | color:var(--color-brand) ; --lucide-stroke:currentColor;--lucide-filter:initial |
| `text-center` | text-align:center |
| `text-current` | color:currentColor ; --lucide-stroke:inherit;--lucide-filter:inherit |
| `text-destructive` | color:var(--color-destructive) ; --lucide-stroke:currentColor;--lucide-filter:initial |
| `text-ellipsis` | text-overflow:ellipsis |
| `text-foreground` | color:var(--color-foreground) ; --lucide-stroke:currentColor;--lucide-filter:initial |
| `text-foreground-inverse` | color:var(--color-foreground-inverse) ; --lucide-stroke:currentColor;--lucide-filter:initial |
| `text-foreground-subtle` | color:var(--color-foreground-subtle) ; --lucide-stroke:rgb(from currentColor r g b / 1);--lucide-filter:opacity(.6) |
| `text-foreground-subtlest` | color:var(--color-foreground-subtlest) ; --lucide-stroke:rgb(from currentColor r g b / 1);--lucide-filter:opacity(var(--lucide-subtlest-opacity)) |
| `text-foreground/85` | color:#404040d9 ; color:color-mix(in oklab, var(--color-foreground) 85%, transparent) ; --lucide-stroke:currentColor;--lucide-filter:initial |
| `text-icon-blue` | color:var(--color-icon-blue) ; --lucide-stroke:currentColor;--lucide-filter:initial |
| `text-icon-purple` | color:var(--color-icon-purple) ; --lucide-stroke:currentColor;--lucide-filter:initial |
| `text-interaction-confirmation-foreground` | color:var(--color-interaction-confirmation-foreground) ; --lucide-stroke:currentColor;--lucide-filter:initial |
| `text-interaction-confirmation-foreground/80` | color:#008138cc ; color:color-mix(in oklab, var(--color-interaction-confirmation-foreground) 80%, transparent) ; --lucide-stroke:currentColor;--lucide-filter:initial |
| `text-left` | text-align:left |
| `text-popover-foreground` | color:var(--color-popover-foreground) ; --lucide-stroke:currentColor;--lucide-filter:initial |
| `text-popover-foreground/80` | color:#404040cc ; color:color-mix(in oklab, var(--color-popover-foreground) 80%, transparent) ; --lucide-stroke:currentColor;--lucide-filter:initial |
| `text-primary-foreground` | color:var(--color-primary-foreground) ; --lucide-stroke:currentColor;--lucide-filter:initial |
| `text-right` | text-align:right |
| `text-success` | color:var(--color-success) ; --lucide-stroke:currentColor;--lucide-filter:initial |
| `text-success-foreground` | color:var(--color-success-foreground) ; --lucide-stroke:currentColor;--lucide-filter:initial |
| `text-tooltip-foreground` | color:var(--color-tooltip-foreground) ; --lucide-stroke:currentColor;--lucide-filter:initial |
| `text-transparent` | color:#0000 ; --lucide-stroke:currentColor;--lucide-filter:initial |
| `text-ui-2xs` | font-size:var(--text-ui-2xs) |
| `text-ui-base` | font-size:var(--text-ui-base) |
| `text-ui-base/relaxed` | font-size:var(--text-ui-base);line-height:var(--leading-relaxed) |
| `text-ui-caption` | font-size:var(--text-ui-caption) |
| `text-ui-lg` | font-size:var(--text-ui-lg) |
| `text-ui-sm` | font-size:var(--text-ui-sm) |
| `text-ui-sm/relaxed` | font-size:var(--text-ui-sm);line-height:var(--leading-relaxed) |
| `text-ui-xl` | font-size:var(--text-ui-xl) |
| `text-ui-xs` | font-size:var(--text-ui-xs) |
| `text-warning` | color:var(--color-warning) ; --lucide-stroke:currentColor;--lucide-filter:initial |
| `text-white` | color:var(--color-white) ; --lucide-stroke:currentColor;--lucide-filter:initial |
| `text-white/90` | color:#ffffffe6 ; color:color-mix(in oklab, var(--color-white) 90%, transparent) ; --lucide-stroke:currentColor;--lucide-filter:initial |
| `text-xl` | font-size:var(--text-xl);line-height:var(--tw-leading,var(--text-xl--line-height)) |
| `text-xl/[1.2]` | font-size:var(--text-xl);line-height:1.2 |
| `to-transparent` | --tw-gradient-to:transparent;--tw-gradient-stops:var(--tw-gradient-via-stops,var(--tw-gradient-position), var(--tw-gradient-from) var(--tw-gradient-from-position), var(--tw-gradient-to) var(--tw-gradient-to-position)) |
| `top-0` | top:calc(var(--spacing) * 0) |
| `top-0.5` | top:calc(var(--spacing) * .5) |
| `top-1/2` | top:50% |
| `top-4` | top:calc(var(--spacing) * 4) |
| `touch-none` | touch-action:none |
| `transition-[height]` | transition-property:height;transition-timing-function:var(--tw-ease,var(--default-transition-timing-function));transition-duration:var(--tw-duration,var(--default-transition-duration)) |
| `transition-[max-width]` | transition-property:max-width;transition-timing-function:var(--tw-ease,var(--default-transition-timing-function));transition-duration:var(--tw-duration,var(--default-transition-duration)) |
| `transition-[width]` | transition-property:width;transition-timing-function:var(--tw-ease,var(--default-transition-timing-function));transition-duration:var(--tw-duration,var(--default-transition-duration)) |
| `transition-all` | transition-property:all;transition-timing-function:var(--tw-ease,var(--default-transition-timing-function));transition-duration:var(--tw-duration,var(--default-transition-duration)) |
| `transition-colors` | transition-property:color,background-color,border-color,outline-color,text-decoration-color,fill,stroke,--tw-gradient-from,--tw-gradient-via,--tw-gradient-to;transition-timing-function:var(--tw-ease,var(--default-transition-timing-function));transition-duration:var(--tw-duration,var(--default-transition-duration)) |
| `transition-opacity` | transition-property:opacity;transition-timing-function:var(--tw-ease,var(--default-transition-timing-function));transition-duration:var(--tw-duration,var(--default-transition-duration)) |
| `transition-transform` | transition-property:transform,translate,scale,rotate;transition-timing-function:var(--tw-ease,var(--default-transition-timing-function));transition-duration:var(--tw-duration,var(--default-transition-duration)) |
| `truncate` | text-overflow:ellipsis;white-space:nowrap;overflow:hidden |
| `underline` | text-decoration-line:underline ; min-height:1px ; border-bottom-style:solid;width:100%;display:inline-block |
| `underline-offset-2` | text-underline-offset:2px |
| `visible` | visibility:visible |
| `w-(--radix-popover-trigger-width)` | width:var(--radix-popover-trigger-width) |
| `w-1` | width:calc(var(--spacing) * 1) |
| `w-1.5` | width:calc(var(--spacing) * 1.5) |
| `w-12` | width:calc(var(--spacing) * 12) |
| `w-16` | width:calc(var(--spacing) * 16) |
| `w-3` | width:calc(var(--spacing) * 3) |
| `w-44` | width:calc(var(--spacing) * 44) |
| `w-48` | width:calc(var(--spacing) * 48) |
| `w-5` | width:calc(var(--spacing) * 5) |
| `w-52` | width:calc(var(--spacing) * 52) |
| `w-6` | width:calc(var(--spacing) * 6) |
| `w-64` | width:calc(var(--spacing) * 64) |
| `w-7` | width:calc(var(--spacing) * 7) |
| `w-80` | width:calc(var(--spacing) * 80) |
| `w-9` | width:calc(var(--spacing) * 9) |
| `w-96` | width:calc(var(--spacing) * 96) |
| `w-[14.375rem]` | width:14.375rem |
| `w-[30px]` | width:30px |
| `w-[54px]` | width:54px |
| `w-[82px]` | width:82px |
| `w-[calc(100vw-1rem)]` | width:calc(100vw - 1rem) |
| `w-auto` | width:auto |
| `w-fit` | width:fit-content |
| `w-full` | width:100% |
| `w-max` | width:max-content |
| `w-px` | width:1px |
| `whitespace-normal` | white-space:normal |
| `whitespace-nowrap` | white-space:nowrap |
| `whitespace-pre-line` | white-space:pre-line |
| `whitespace-pre-wrap` | white-space:pre-wrap |
| `will-change-transform` | will-change:transform |
| `z-0` | z-index:0 |
| `z-1` | z-index:1 |
| `z-10` | z-index:10 |
| `z-20` | z-index:20 |
| `z-30` | z-index:30 |
| `z-40` | z-index:40 |
| `z-50` | z-index:50 |
| `z-[60]` | z-index:60 |
| `zcode-reaction-burst` | display:inline-flex;position:relative ; animation:.45s cubic-bezier(.34,1.56,.64,1) zcode-reaction-pop ; animation:none |
| `zcode-task-interaction-countdown-fill` | animation:zcode-task-interaction-countdown var(--zcode-interaction-remaining-ms,240s) linear forwards ; transform:scaleX(var(--zcode-interaction-progress,1));animation:none |
