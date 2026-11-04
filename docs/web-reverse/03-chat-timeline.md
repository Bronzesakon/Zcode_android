# 03 · ZCode 3.17.1 网页版「对话页时间线」布局与样式全量还原

> **基线**：`Reference_web/3.17.1/`（只读）
> - 主包：`assets/index-Dwhx77cK.js`（6,986,617 B）
> - 样式：`assets/index-BgVjt5KE.css`（485,230 B，单行压缩）
> - Markdown 渲染器：`assets/chunk-BO2N2NFS-DYyAIm7S.js`（streamdown 打包产物）
> - 按钮：`assets/button-P7AG3MDX.js`
> - 文案：`assets/IntlProvider-CneXitqp.js`（zh-CN）
>
> **偏移口径**：本文所有 `index-Dwhx77cK.js:<数字>` 均为 **UTF-8 字节偏移**（`Buffer.indexOf` 可复现），不是字符偏移。
> **方法**：只读字节切片 + 正则检索 + 组件逐行阅读，未执行应用代码。CSS 为单行文件，故按 **选择器原文 + 声明原文** 引用。
> **标注**：`confirmed`＝代码/样式直接证据；`inferred`＝由多条 confirmed 组合推导；`unresolved`＝打包产物内无法确认。
> **审计日期**：2026-11-03。

---

## 0. 结论摘要（先看这 20 条）

| # | 结论 | 证据 | 置信 |
|---|---|---|---|
| 1 | 对话页最外层是 `section[data-workspace-conversation-frame]`：`relative flex h-full min-h-0 flex-1 flex-col overflow-hidden bg-background`；再内层 `@container/conversation relative flex min-h-0 flex-1 flex-col` 才是容器查询锚点 | `index-Dwhx77cK.js:6074142`（frame）、`4820409`（container） | confirmed |
| 2 | 页面顶栏是 `header`：高 **48px**、`border-b`、`p-2`、`gap-2`；左标题 `font-semibold` 继承 14px | `index-Dwhx77cK.js:5283197` | confirmed |
| 3 | 时间线根：`relative flex min-h-0 flex-1 flex-col`，不含任何宽度约束 | `index-Dwhx77cK.js:4561785` | confirmed |
| 4 | 滚动容器：`min-h-0 flex-1 overflow-x-hidden overflow-y-auto [scrollbar-gutter:stable_both-edges]`；滚动条两侧常驻占位 | `index-Dwhx77cK.js:4592864` | confirmed |
| 5 | 内容列宽：默认 `w-full`；容器 ≥864px → `calc(100% - 96px)` 且 `max-width:896px`；≥1280px → `calc(100% - 384px)` 且 `max-width:1152px`；始终 `mx-auto` | `index-Dwhx77cK.js:3851103`、CSS `@container conversation (width>=864px)` / `(width>=1280px)` | confirmed |
| 6 | 有右侧摘要面板（`summaryPanelLayout!=='none'`）且容器 ≥1280px 时，内容列再 `translateX(-168px)` | `index-Dwhx77cK.js:3851540`（`-translate-x-42`＝42×4px） | confirmed |
| 7 | 每个 turn 是 `section[data-turn-id]`：`gap-5 px-4 @md/conversation:px-6 pb-5`，顶部 `pt-14`（56px），首个 turn 若被时间线接管则 `pt-0` | `index-Dwhx77cK.js:4539038` | confirmed |
| 8 | `@md/conversation` 的真实断点是 **28rem＝448px**，不是 768px（既有 `docs/chat/03` 的 768px 说法有误） | CSS `@container conversation (width>=28rem){.\@md\/conversation\:px-6{...}}` | confirmed |
| 9 | 用户气泡最终外观被一条专用 CSS 覆盖：背景 `--color-user-message-bubble`（暗色 `#ffffff14`）、`border-width:0`、圆角 12px；支持 `corner-shape:squircle` 的浏览器为 24px 方圆 | CSS `[data-v4-user-input-bubble]{...}`（@472529 与 @473785） | confirmed |
| 10 | 用户气泡最大宽度：容器 ≥624px 时 576px（`max-w-xl`），否则 `max-w-full`；右对齐 | `index-Dwhx77cK.js:3902504` | confirmed |
| 11 | assistant 文本无气泡：`w-full text-ui-base`，Markdown 根 **14px / 1.75（24.5px）/ letter-spacing .025em** | `index-Dwhx77cK.js:4508774`、`2925476` | confirmed |
| 12 | Markdown 块间距 **16px**（streamdown 根 `space-y-4`）；首元素 `mt-0`、末元素 `mb-0` | `chunk-BO2N2NFS-DYyAIm7S.js`（`space-y-4 whitespace-normal ...`） | confirmed |
| 13 | 思考行默认折叠、无背景无边框；触发器 16px 图标 + 14px 文案；内容区 `max-h-240px` 可滚，左 1px 竖线 + `pl-14px` + `ml-8px` | `index-Dwhx77cK.js:4472614`、`4474619`、`4477287` | confirmed |
| 14 | 「工作中/已工作」行是 turn 级 `R2`：`border-b` 50% 透明 + `pb-2`（8px），文案 14px `foreground-subtle`，右侧 16px chevron | `index-Dwhx77cK.js:4528423` | confirmed |
| 15 | 行级分隔/时间标记 `timelineMarker`：两侧 1px 细线 + 中间 14px 文案，`py-2`、`gap-3`；运行中文字为流动渐变 | `index-Dwhx77cK.js:4512864`、`4514134` | confirmed |
| 16 | 回到底部按钮：`Button size=icon`（28×28）`rounded-full bg-card`，16px 箭头图标；无 dock 时 `bottom-3`(12px)，有 dock 时浮在 dock 上方 `mb-2`(8px) | `index-Dwhx77cK.js:4571221`、`4596888` | confirmed |
| 17 | 「加载更早」**没有任何可见 UI**（无骨架、无 spinner、无文字）；只有滚动容器上的 `data-loading-older` 属性 + 自动预取 | `index-Dwhx77cK.js:4593135`、`4556269`（`scrollTop<=max(64,2×clientHeight)`） | confirmed |
| 18 | 虚拟化：`overscan=2`（3.15.3 是 8）；未测量项估算值＝**视口高度**（`clientHeight\|\|72`），不是固定 72px | `index-Dwhx77cK.js:4570886`（`Zwn=2`）、`4561785`（`estimate(..., clientHeight\|\|72)`） | confirmed |
| 19 | 空态：问候语 20–30px 自适应、居中、`max-w-2xl`；上方留白 `basis-[29dvh]`（最小 52px），下方 `min-h-4 flex-1` | `index-Dwhx77cK.js:3523322`、`4594663` | confirmed |
| 20 | 未知行 `default: return null`，不渲染任何兜底 UI | `index-Dwhx77cK.js:4519608` | confirmed |

---

## 1. 页面骨架与滚动容器

### 1.1 外层框架（工作区 → 对话列）

```text
section[data-workspace-conversation-frame]      ← relative flex h-full min-h-0 flex-1 flex-col overflow-hidden bg-background
├─ header                                       ← 页面顶栏，h-12(48px) border-b
└─ div[data-testid=...][data-session-id][data-v4-conversation-drop-target]
   ├─ div[data-v4-pane-actions=floating]        ← 悬浮「工作区徽标 + 关闭面板」（绝对定位，Jen/qen）
   └─ div.@container/conversation.relative.flex.min-h-0.flex-1.flex-col   ← 容器查询锚点
      ├─ 状态面板 / 队列 / 交互卡（条件渲染）
      └─ div.relative.flex.min-h-0.flex-1.flex-col   ← 时间线根（fTn）
```

| 节点 | class（原文） | 尺寸/效果 | 证据 |
|---|---|---|---|
| frame | `relative flex h-full min-h-0 flex-1 flex-col overflow-hidden bg-background` | 全高、裁切 | `index-Dwhx77cK.js:6074142` |
| container 锚点 | `@container/conversation relative flex min-h-0 flex-1 flex-col` | 建立 `conversation` 容器查询 | `index-Dwhx77cK.js:4820409` |
| 时间线根 | `relative flex min-h-0 flex-1 flex-col`（`compactForRemoteControl` 时追加 `min-w-0`） | — | `index-Dwhx77cK.js:4561785` |

> `@container/conversation` 的 CSS 实体为 `.\@container\/conversation{container:conversation/inline-size}`（CSS `@layer utilities`），即命名容器 + `inline-size`。下文所有 `@min-[…]/conversation` 断点都是**对话列自身宽度**（不是窗口宽度）。`confirmed`。

### 1.2 顶栏 header

```text
header[data-workspace-header-variant=draft|task]
  class="relative flex w-full shrink-0 h-12 border-b border-transparent|border-border/50"
└─ div class="flex h-12 flex-1 min-w-0 items-center justify-between gap-2 overflow-hidden p-2 [app-region:drag] transition-[padding] duration-300"
   ├─ div（左：工作区/标题/分支/状态，[app-region:no-drag]）
   └─ div（右：工具按钮、窗口控制）
```

| 项 | 值 | 证据 |
|---|---|---|
| 高度 | 48px（`h-12`＝12×4） | CSS `.h-12{height:calc(var(--spacing)*12)}` |
| 底边 | 1px；`task` 变体 `border-border/50`，`draft` 变体 `border-transparent` | `index-Dwhx77cK.js:5283197` |
| 内边距 | 8px（`p-2`） | 同上 |
| 标题 | `flex min-w-12 max-w-100 ... truncate font-semibold text-foreground`，继承 14px | 同上 |

> 对话区**没有**第二条“会话标题栏”；`Jen`（`index-Dwhx77cK.js:3774241`）只渲染悬浮的 `data-v4-pane-actions`（工作区徽标 28px 高 + 关闭按钮 28px），绝对定位 `left-2 top-2 z-20`。

### 1.3 滚动容器

```text
div[data-testid=…][data-v4-timeline-scroll]
    [data-v4-timeline-scroll-locked=false]
    [data-markdown-table-layout-root=true]
    [data-row-count][data-window-row-count][data-render-unit-count][data-total-row-count]
    [data-following=true|false][data-loading-older=true|false]
    class="min-h-0 flex-1 overflow-x-hidden overflow-y-auto
           [scrollbar-gutter:stable_both-edges]
           [--markdown-table-layout-left-inset:16px]
           [--markdown-table-layout-right-inset:16px]
           max-md:[--markdown-table-layout-left-inset:8px]
           max-md:[--markdown-table-layout-right-inset:8px]
           [min-w-0]                      ← compactForRemoteControl
           [mb-[calc(var(--v4-floating-composer-dock-height,0px)+12px)] pb-4]  ← 浮动 dock
           [@min-[864px]/conversation:[--markdown-table-layout-left-inset:48px]] ← 存在 ≥2 个 turn 时
           [!overflow-y-hidden]           ← backgroundScrollLocked"
└─ div class="flex min-h-full flex-col"   ← 普通模式
       | class="flex min-h-full flex-col items-center px-2
                before:block before:min-h-[52px] before:w-full before:shrink before:basis-[29dvh] before:content-['']
                after:block after:min-h-4 after:w-full after:flex-1 after:content-['']"   ← 居中空态
   style="overflow-anchor:none"
```

| 项 | 值 | 证据 |
|---|---|---|
| 滚动条槽 | `scrollbar-gutter:stable both-edges`（左右各预留一条） | class 原文，`index-Dwhx77cK.js:4592864` |
| 横向 | `overflow-x-hidden` | 同上 |
| 锚定 | `overflow-anchor:none`（禁用浏览器滚动锚定，改由 JS 补偿） | 同上 |
| 表格左右 inset | 16px；视口 <768px 降为 8px；容器 ≥864px 且 ≥2 turn 时左侧 48px | 同上 |
| 底部渐隐遮罩 | 非贴底时 `mask-image: linear-gradient(to bottom, black 0, black (h-120)px, transparent (h-96)px, transparent 100%)`，即**距底 96px 完全透明、从距底 120px 起羽化 24px**；贴底（≤48px）或浮动 dock 时 `mask-image:none` | `index-Dwhx77cK.js:4561785`（`Ut`：`eTn=96`、`$wn=24`） |
| 滚动条视觉 | 容器透明；`[data-scrollbar-active]` 时 thumb 用 `--color-border`；hover `#4040404d`；1s 无操作回落透明 | CSS `[data-scrollbar-active]{--zcode-scrollbar-thumb:var(--color-border)}` |

### 1.4 内容列宽（关键）

`ht = Inn({centeredEmptyLayout, statusPanelLayout})`，`mt = znn(statusPanelLayout)`：

| 场景 | class | 实际宽度 | 证据 |
|---|---|---|---|
| 居中空态 | `max-w-2xl` | ≤672px | `index-Dwhx77cK.js:3851103`（`Mnn`） |
| 正常（无摘要面板 `none`） | `w-full @min-[864px]/conversation:w-[calc(100%_-_6rem)] @min-[864px]/conversation:max-w-4xl @min-[1280px]/conversation:w-[calc(100%_-_24rem)] @min-[1280px]/conversation:max-w-6xl` | <864：100%；864–1280：min(容器-96, 896)；≥1280：min(容器-384, 1152) | `index-Dwhx77cK.js:3851103`（`Pnn`） |
| 有摘要面板（非 `none`） | 同上（`Nnn` 与 `Pnn` 字符串完全相同） | 同上 | 同上 |
| 额外位移 | `@min-[1280px]/conversation:-translate-x-42`（仅 `statusPanelLayout!=='none'`） | 左移 168px | `index-Dwhx77cK.js:3851540`（`Fnn`） |

> 所有列容器都带 `relative mx-auto w-full shrink-0`，虚拟历史列额外带 `transition-[width,max-width,transform] duration-150 ease-out`（≥1280 时只过渡 transform）。
> 断点 624/864/1280 均以 **`@container/conversation` 的宽度**（即对话列宽）为基准，不是 `window.innerWidth`。
> `confirmed`（三处 `data-v4-timeline-content-column` 共用同一 `ht/mt`，`index-Dwhx77cK.js:4594663`/`4595701`/`4596154`）。

### 1.5 底部：composer dock / 回到底部 / 加载更早

```text
div[data-v4-composer-dock] class="pointer-events-none z-20 flex w-full justify-center
      sticky bottom-0 | absolute inset-x-0 bottom-0(浮动) | mt-3 shrink-0(空态居中) | shrink-0"
└─ div[data-v4-composer-dock-content]
     class="pointer-events-auto relative z-10 w-full shrink-0
            transition-[width,max-width,transform] duration-150 ease-out
            px-2 pb-4  ← 非空态、非浮动
            {ht} {mt}"
   └─ div[data-v4-back-to-bottom-anchor=composer-dock].relative
      ├─ (Ze) 回到底部按钮：absolute bottom-full left-1/2 z-30 mb-2 -translate-x-1/2 shadow-sm
      └─ dock 内容
```

| 项 | 值 | 证据 |
|---|---|---|
| dock 定位 | 默认 `sticky bottom-0`；浮动模式 `absolute inset-x-0 bottom-0`；空态居中 `mt-3 shrink-0` | `index-Dwhx77cK.js:4596614` |
| dock 内容内边距 | `px-2 pb-4`（8px / 16px），并复用列宽 `ht` | 同上 |
| 无 dock 时按钮 | `absolute bottom-3 left-1/2 -translate-x-1/2 shadow-sm` → 距底 **12px** | `index-Dwhx77cK.js:4597187` |
| 有 dock 时按钮 | `absolute bottom-full left-1/2 mb-2 -translate-x-1/2` → dock 上沿再上 **8px** | `index-Dwhx77cK.js:4596979` |
| 按钮本体 | `Button variant=outline size=icon`（28×28）+ `rounded-full bg-card hover:bg-card-selected` + 16px 图标 | `index-Dwhx77cK.js:4571221`；button 尺寸表 `button-P7AG3MDX.js`（`icon:size-7`） |
| 显隐 | 无过渡；条件为「不跟随 且 有渲染单元」（`dwn(e,t)=!e&&t>0`） | `index-Dwhx77cK.js:4555601` 附近 |

**加载更早**：`confirmed` 无任何 UI 元素。滚动时仅当 `scrollTop <= max(64, 2×clientHeight)` 且 `canLoadOlder && !loadingOlder` 触发 `onLoadOlder()`（`index-Dwhx77cK.js:4556201`/`4556269`）。容器上仅有 `data-loading-older` 属性。

### 1.6 虚拟化参数（3.17.1 实测）

| 项 | 值 | 证据 |
|---|---|---|
| `overscan` | **2** | `index-Dwhx77cK.js:4570886`（`Zwn=2`） |
| `estimateSize` | 缓存值；未命中用 `clientHeight \|\| 72`（**视口高度**） | `index-Dwhx77cK.js:4561785`（`bt`）；缓存类 `nwn` 在 `4446428` |
| 尺寸缓存上限 | 4000 条 | `index-Dwhx77cK.js:4446428`（`twn=4e3`） |
| `getItemKey` | `unit.key`（缺失回退 index） | `index-Dwhx77cK.js:4561785`（`yt`） |
| `scrollMargin` | header slot 高度（`ResizeObserver` 实测） | 同上（`ee` / `V`） |
| 虚拟项定位 | `absolute left-0 top-0 w-full` + `transform: translateY(start - scrollMargin)` | `index-Dwhx77cK.js:4595701` |
| 活尾 | 正在运行的最后一个 turn 移出虚拟列表，单独 `data-v4-running-live-tail` 渲染 | `index-Dwhx77cK.js:4596154` |
| 首个 turn 顶部 | 无 headerSlot、历史已尽、`totalCount<=rows.length` 时，wrapper 加 `pt-14` 并给 section `pt-0` | `index-Dwhx77cK.js:4561785`（`be`） |

---

## 2. turn 单元结构

```text
div[data-index][data-v4-turn-unit][data-turn-id]  class="absolute left-0 top-0 w-full [pt-14]"
└─ section[data-turn-id][data-turn-key][data-highspeed-gradient]
     class="relative mx-auto flex w-full flex-col gap-5 px-4 @md/conversation:px-6 pb-5
            [pt-0 | pt-14]
            [highspeed-animated-gradient-text]"
   ├─ F2（leadingBoundaryRows 行）
   ├─ div.group/assistant-turn.flex.w-full.flex-col.gap-5
   │   ├─ (有标题模式) ICn  ← 工作流/后台结果：标题 + 折叠历史
   │   ├─ (普通模式) MCn
   │   │   └─ div class="flex flex-col gap-5 [&>[data-zcode-chat-loading-slot]:not(:first-child)]:-mt-2"
   │   │      ├─ jCn × N（每个 workSegment 一个）
   │   │      │   └─ CollapsibleRoot class="history-message flex flex-col
   │   │      │        [&>*+*:not([data-slot='collapsible-content'])]:mt-5"
   │   │      │      ├─ (运行中) R2 工作中
   │   │      │      ├─ 行 / L2 行组 / 折叠历史
   │   │      │      └─ (结束后) R2 已工作
   │   │      └─ I2 加载槽
   │   ├─ Lxn（工作流完成摘要）
   │   ├─ Oyn（工作流 digest）
   │   ├─ kCn / ACn（自动化卡片 / 任务卡片）
   │   ├─ jvn（文件变更摘要）
   │   ├─ L2（browserTurnEndRows）
   │   ├─ iCn（最新 assistant 动作条：复制/赞/踩/fork/重试/时间）
   │   └─ bCn / nLt（高速模式页脚 / 指标）
   └─ hin（topicPreparations）
```

| 间距层级 | 值 | 证据 |
|---|---|---|
| turn 根 `gap-5` | 20px | CSS `.gap-5{gap:calc(var(--spacing)*5)}` |
| turn 左右 `px-4` → `@md(448px)` `px-6` | 16px → 24px | `index-Dwhx77cK.js:4539038` |
| turn 底 `pb-5` | 20px | 同上 |
| turn 顶 `pt-14` | 56px | 同上 |
| assistant-turn `gap-5` | 20px | 同上 |
| history-message 兄弟间距 `mt-5` | 20px（折叠内容除外） | `index-Dwhx77cK.js:4530379` |
| 行组 `L2` `gap-4` | 16px | `index-Dwhx77cK.js:4520755` 附近 |

> `confirmed`：`data-turn-unit` 外层是绝对定位、`section` 是相对布局；turn 的 `mx-auto` 与列容器 `mx-auto` 叠加但宽度由列容器约束，section 自身 `w-full`。

---

## 3. 行级还原

### 3.0 行分发器

```js
switch (row.kind) {
  case 'userInput':      → nCn   （→ tCn → uin）
  case 'assistantText':  → aCn
  case 'reasoning':      → T0(rowId) ? oCn : null
  case 'turnHeader':     → sCn   （调试占位）
  case 'timelineMarker': → pCn   （→ fCn）
  case 'toolCall':       → （todo 且未开启 showTodos 时 null）mCn
  case 'subagent':       → hCn
  case 'artifact':       → ZSn
  default:               → null
}
```

证据：`index-Dwhx77cK.js:4519608`（`function gCn`）。
行包裹 `N2`：`<div data-row-id={rowId} data-testid=... className={className}>`，**无默认样式**（`index-Dwhx77cK.js:4485726`）。

---

### 3.1 turn header（「工作中 / 已工作」）— `R2`

```text
div class="flex w-full flex-wrap items-center justify-between gap-2 border-b
           border-[var(--color-border)]/50 pb-2
           | border-icon-purple/30 pb-3 pl-1        ← highspeed"
├─ CollapsibleTrigger asChild
│  └─ button[data-history-open=true|false]
│       class="group/history-message inline-flex max-w-full items-center gap-2 text-left
│              text-ui-base text-foreground-subtle
│              focus-visible:outline-none focus-visible:ring-2
│              focus-visible:ring-[var(--color-input-border-focused)]"
│     ├─ span class="truncate [font-medium leading-5 text-icon-purple]"  data-highspeed-duration
│     └─ ChevronRight class="size-4 shrink-0 text-[var(--color-foreground-subtlest)]
│                           opacity-70 transition-transform rotate-90|rotate-0"
└─ (highspeed) div class="ml-auto flex shrink-0 items-center gap-2 text-icon-purple"
   ├─ span class="text-ui-sm font-medium leading-4"  已节省时长
   └─ span class="rounded-full bg-hover px-2 py-0.5 text-ui-sm leading-4"  状态胶囊
```

| 项 | 值 |
|---|---|
| 文案（zh-CN） | `工作中 {duration}` / `已工作 {duration}` / `已处理` / `已停止` / `已被新消息中断` |
| 字号 | 14px（`text-ui-base`），行高继承 1.5 → 21px；highspeed 时 `leading-5`=20px |
| 颜色 | `--color-foreground-subtle`（暗色 `#d4d4d499`）；highspeed 用 `--color-icon-purple`（`#a888f2`） |
| 底边 | 1px，`border-border/50` → `rgba(255,255,255,.1)` 再乘 50% ≈ **5% 白**；`pb-2`=8px；highspeed `pb-3`=12px + `pl-1` |
| 图标 | chevron 16px，透明度 0.7，展开旋转 90° |
| 时长格式 | 最多取两级（天/时/分/秒）；<1s 按 1s；例：`已工作 1 分 20 秒` |

证据：组件 `index-Dwhx77cK.js:4528423`；文案 `IntlProvider-CneXitqp.js:257446`；时长 `index-Dwhx77cK.js:2993279`（`LH`，`c.slice(0,2)`）。

**出现位置（confirmed）**：`jCn` 内，`y = !turnRunning && workStatus && state!=='running'` 时插在第一个非 `userInput` 流项之前；运行中则插在最后一个 `assistantHistory|assistantWork` 之前（`index-Dwhx77cK.js:4530379`）。`ICn` 中同样在 `!isRunning && workStatus` 时渲染（`4535672`）。

---

### 3.2 用户消息 — `userInput`

```text
div[data-row-id][data-testid]
└─ div class="group/user-row flex flex-col items-end"
   ├─ (定时任务来源) div[data-v4-user-input-automation-origin=schedule]
   │    class="mb-1 flex max-w-xl items-center gap-1 text-ui-sm text-foreground-subtle"
   │    ├─ CalendarClock size-3.5
   │    └─ span 由定时任务发送
   ├─ (bot 群来源) div[data-v4-user-input-source] class="mb-2 flex min-w-0 max-w-full"
   ├─ (附件区) div[data-v4-user-input-attachments] class="mb-2 flex max-w-xl flex-col items-end gap-2"
   │    ├─ div[data-v4-user-input-quotes]        （引用卡片，justify-end）
   │    ├─ div[data-v4-user-input-media-attachments] class="flex max-w-full flex-wrap justify-end gap-2"
   │    │    └─ 图片/视频：size-12(48) 或 size-20(80)，rounded-lg/xl，bg-surface，after 描边
   │    └─ div[data-v4-user-input-attachment-pills] class="flex max-w-full flex-wrap justify-end gap-2"
   │         └─ 文件胶囊：h-12 w-fit rounded-lg border bg-surface p-1.5 pr-6（或 rounded-full px-3 py-1.5）
   ├─ div[data-v4-user-input-bubble]
   │    class="flex max-w-full flex-col gap-2 rounded-xl rounded-tr-xs border border-border
   │           bg-surface px-4 py-3 text-ui-base text-foreground
   │           @min-[624px]/conversation:max-w-xl"
   │    ├─ div[data-conversation-selectable=true] class="relative min-w-0 flex-1"
   │    │   └─ div[data-v4-user-input-collapsible-content]
   │    │        style="max-height:120px | 实测高度"
   │    │        class="min-w-0 overflow-hidden whitespace-pre-wrap break-words
   │    │               transition-[max-height] duration-300 ease-out
   │    │               [mask-image:linear-gradient(to bottom,black 0%,black 70%,transparent 100%)]"
   │    │      └─ 文本 / 富文本 part
   │    │   └─ (超长) 展开按钮：Button size=sm variant=outline rounded-full bg-background shadow-sm
   │    └─ (epilogue) div[data-v4-user-input-epilogue]
   ├─ (genUi/pluginUi 来源) div[data-v4-user-input-source] class="mt-1 text-right text-ui-sm text-foreground-subtlest"
   ├─ (状态) div[data-v4-user-input-status] class="mt-1 text-right text-ui-sm text-foreground-subtlest" aria-live=polite
   └─ 动作条 class="mt-1 flex items-center gap-1 opacity-0 transition-opacity
                   group-hover/user-row:opacity-100 focus-within:opacity-100"
      ├─ 复制（Button ghost icon-sm = 24×24，图标 14px）
      └─ 编辑（同上）
```

**最终视觉（CSS 覆盖后，confirmed）**：

| 项 | 计算值 | 证据 |
|---|---|---|
| 背景 | `--color-user-message-bubble` = `#ffffff14`（白 8%） | CSS `[data-v4-user-input-bubble]{background-color:var(--color-user-message-bubble);border-width:0;border-top-right-radius:12px}` |
| 边框 | **0**（Tailwind 的 `border` 被覆盖掉） | 同上 |
| 圆角 | 12px；支持 `corner-shape:squircle` 时 24px（四角） | CSS `@supports (corner-shape:squircle){[data-v4-user-input-bubble]{corner-shape:squircle;border-radius:24px}}` + 后续 `border-top-right-radius:24px` |
| 内边距 | 16px 横 / 12px 纵 | `px-4 py-3` |
| 行内间距 | 8px（文本 ↔ epilogue） | `gap-2` |
| 字号/行高 | 14px / 21px（1.5） | `text-ui-base` + 根 `line-height:1.5` |
| 最大宽 | 576px（容器 ≥624px）；否则 100% | `@min-[624px]/conversation:max-w-xl`；`--container-xl:36rem` |
| 对齐 | 右对齐（`items-end`） | `group/user-row flex flex-col items-end` |
| 长文本折叠 | `max-height:120px`；内容 `scrollHeight > 121px` 才出现展开按钮；遮罩最后 30% | `index-Dwhx77cK.js:3896756`（`Qrn`/`Zrn`，`Xrn=1`） |
| 文本换行 | `whitespace-pre-wrap break-words` | 同上 |

> 用户文本**不是 Markdown**：`lin` 只把 `text` 与 mention/command part 顺序输出（`index-Dwhx77cK.js:3795173`）。`confirmed`。

---

### 3.3 assistant 文本 — `assistantText`

```text
div[data-row-id][data-testid] class="group/assistant-row"
├─ div[data-conversation-selectable=true] class="w-full text-ui-base"
│   └─ Markdown 根（见 §4）
├─ (可选) div.mt-3  代码评论卡
├─ (可选) div.mt-3  预览卡（PPTX/网页/文件）
└─ (state=complete 且非 hide/defer) 动作条
     class="mt-1 flex items-center gap-1 opacity-0 transition-opacity
            group-hover/assistant-row:opacity-100 focus-within:opacity-100
            has-[[data-bot-group-delivery]]:opacity-100"
     ├─ 复制  ├─ 赞  ├─ 踩  ├─ fork  ├─ 重试
     ├─ (hook) 钩子按钮  ├─ 时间戳（text-ui-sm text-foreground-subtlest）
     └─ (群投递) 投递状态
```

| 项 | 值 | 证据 |
|---|---|---|
| 容器 | 无背景/边框/圆角；`w-full` | `index-Dwhx77cK.js:4508774` |
| 字号 | 14px（`text-ui-base`），正文行高 24.5px | `index-Dwhx77cK.js:2925476`（Markdown 根 `leading-[1.75]`） |
| 动作条显隐 | 默认 `opacity-0`，hover 行 / `focus-within` / 群投递时 100% | 同上 |
| 动作条间距 | `mt-1`=4px，按钮间 `gap-1`=4px | 同上 |
| 时间戳 | 仅当天 `HH:mm`；昨天 `昨天 HH:mm`；更早 `M/D HH:mm`；跨年 `YYYY/M/D HH:mm` | `index-Dwhx77cK.js:4485195`（`XSn`） |

---

### 3.4 思考（reasoning）

```text
div[data-row-id][data-testid]
└─ CollapsibleRoot class="not-prose flex flex-col w-full"
   ├─ CollapsibleTrigger class="group/reasoning inline-flex max-w-full min-w-0 items-center gap-2
   │                           self-start text-ui-base transition-colors"
   │   ├─ Brain size-4 text-foreground-subtlest
   │   ├─ span[data-reasoning-label] class="shrink-0 whitespace-nowrap"
   │   ├─ span "·"  class="shrink-0 text-foreground-subtlest"
   │   ├─ span[data-reasoning-streaming-line][data-reasoning-streaming-roll]
   │   │    class="min-w-0 flex-1 overflow-hidden whitespace-nowrap text-foreground-subtle"
   │   │    style="mask-image:linear-gradient(to right,transparent 0,black 16px,black calc(100% - 16px),transparent 100%)"
   │   │    └─ span[data-reasoning-streaming-text] class="inline-block min-w-max"
   │   └─ ChevronRight class="size-4 shrink-0 text-foreground-subtlest
   │                        rotate-90 opacity-100 | rotate-0 opacity-0 group-hover/reasoning:opacity-100"
   └─ CollapsibleContent[data-reasoning-content][data-reasoning-content-variant=default|nested]
        class="overflow-hidden data-[state=open]:animate-collapsible-down
               data-[state=closed]:animate-collapsible-up duration-300 ease-in-out"
      └─ div.pt-3
         └─ div[data-reasoning-scroll-mask=none|top|bottom|both]
              class="max-h-60 space-y-2 overflow-auto text-ui-base text-foreground-subtlest
                     [ml-2 border-border border-l pl-3.5]"   ← 仅 default 变体
            └─ div class="min-w-0 whitespace-pre-wrap break-words text-foreground-subtlest"
```

| 项 | 值 | 证据 |
|---|---|---|
| 默认状态 | 折叠（`defaultOpen=false`） | `index-Dwhx77cK.js:4472614` |
| 流式标题 | `正在思考` + 流动渐变文字（`animated-gradient-text`，4s linear infinite，background-size 300%×100%） | `index-Dwhx77cK.js:4474619`；CSS `.animated-gradient-text` |
| 完成标题 | `思考 · 持续了 N 秒`（N=1 时 `持续了几秒`）；字色 `foreground-subtlest` | `IntlProvider-CneXitqp.js:259449` |
| 流式尾行 | 只取最后一行非空文本，单行省略 + 左右 16px 渐隐遮罩 | `index-Dwhx77cK.js:4474619`（`PSn`/`ISn`） |
| 内容区 | `max-h-60`=240px，`overflow:auto`，`space-y-2`=8px，`pt-3`=12px | `index-Dwhx77cK.js:4477287` |
| 内容排版 | 纯文本 `whitespace-pre-wrap`，**不是 Markdown** | 同上 |
| 左侧竖线 | `border-l` 1px `--color-border`；`pl-3.5`=14px；`ml-2`=8px | 同上 |
| 自动收起 | 流式结束且用户未手动操作时自动折叠（`autoCollapseKey` 变化） | `index-Dwhx77cK.js:4364435`（`kSn`） |
| 折叠动画 | 高度 300ms ease-in-out + 内容 fade-in/out | `index-Dwhx77cK.js:993451`（`nx`）+ CSS `@keyframes collapsible-down/up` |

---

### 3.5 分隔线 / 时间标记 — `timelineMarker`

```text
div[data-row-id][data-row-kind=timelineMarker][data-marker-type][data-status][data-origin]
    [data-source-command-id][data-testid]
    class="flex w-full items-center gap-3 px-4 py-2 text-ui-base text-[var(--color-foreground-subtle)]
           [group/marker text-left transition-colors hover:text-[var(--color-foreground)]]"
├─ div[aria-hidden] class="h-px min-w-8 flex-1 bg-border/50"
├─ span class="inline-flex min-w-0 shrink items-center justify-center gap-1.5 text-center leading-5"
│   ├─ (icon 14px, 可选)
│   └─ span class="min-w-0 break-words [animated-gradient-text font-medium] [underline-offset-4 group-hover/marker:underline]"
└─ div[aria-hidden] class="h-px min-w-8 flex-1 bg-border/50"
```

| marker.type | 图标 | 文案（zh-CN，实测） | 运行态 |
|---|---|---|---|
| `compact` | `cCn`（lucide，14px） | 压缩：`正在压缩上下文` / `上下文已是最新，无需压缩` / `上下文压缩已中断` / `上下文压缩失败` / `上下文已压缩` / `上下文已自动压缩`；优化模式改为 `正在优化对话` / `已优化对话` | `status==='running'` → 渐变文字 |
| `forkNotice` | `lCn`（14px） | `从对话中派生` | 否 |
| `modelChange` | `dCn`（14px） | 有来源：`模型已切换 {from} → {to}`；无来源：`正在使用 {model}` | 否 |
| `goalVerify` | `uCn`（14px） | `第 {count} 次迭代` + ` · ` + `目标校验中` / `目标已完成，任务结束` / `目标未完成，任务继续` | `outcome==='running'` → 渐变文字 |

证据：`index-Dwhx77cK.js:4512864`（`fCn`）、`4514134`（`pCn`）。
可点击时（`forkNotice` 且存在 `onNavigateToRow`）渲染为 `<button>`，否则 `<div>`；两者 class 相同。

> 时间线**没有日期分割线**（无 `chat.history.date*` 文案，全量 i18n 检索 0 命中）。`confirmed`。

---

### 3.6 `turnHeader` 行（调试占位）

```text
div[data-row-id][data-testid]
  class="border-b border-[var(--color-border)] py-1 text-ui-sm text-[var(--color-foreground-subtle)]"
  → 文本 "turn · {origin} · {state}"
```

证据：`index-Dwhx77cK.js:4512205`（`sCn`）。正常时间线不会把该行送进分发器（`header` 被 turn 单元单独消费），因此**用户不可见**；`inferred`（与 3.15.3 文档结论一致）。

---

### 3.7 hook（钩子）

hook 不是独立行，而是 assistant 动作条里的一个入口：

```text
Popover
├─ Tooltip → TooltipTrigger asChild → PopoverTrigger asChild
│  └─ Button variant=ghost size=icon-sm（24×24）
│       class="…（外部传入）" data-testid=…
│       ├─ Webhook size-3.5（14px）
│       └─ span.sr-only 钩子
└─ PopoverContent align=start side=top sideOffset=6 collisionPadding=16
     class="w-80 max-w-[calc(100vw-2rem)] gap-0 overflow-hidden border-popover-border p-0"
     style="max-height:min(20rem, var(--radix-popover-content-available-height))"
   ├─ div class="shrink-0 border-b border-border px-3 py-2"
   │   └─ h3 class="text-ui-base font-medium text-foreground" 钩子
   └─ ul class="min-h-0 overflow-y-auto py-0.5"
      └─ li class="min-w-0 px-3 py-1.5 text-ui-sm"
         ├─ span class="min-w-0 flex-1 break-words font-mono text-foreground" 事件名
         └─ div class="flex min-w-0 flex-wrap items-center justify-end gap-2 text-ui-xs text-foreground-subtle"
            ├─ 来源（用户/工作区/插件）
            ├─ 时长（`123ms` / `1.23s`）
            └─ 状态图标（14px）+ 状态 + 阻塞原因（warning）
```

证据：`index-Dwhx77cK.js:4483348`（`GSn`）、`4482396`（`WSn`）、`4481529`（时长格式）。
尺寸：按钮 24px；弹层宽 320px、最大高 320px。

---

### 3.8 子代理 — `subagent`

**旧式行（`kind==='subagent'`）**：

```text
div[data-row-id][data-testid]
└─ div class="text-ui-sm text-[var(--color-foreground-subtle)]"
   文本：`{subagentType} · {status}[ — {summaryText}]`
```

证据：`index-Dwhx77cK.js:4519322`（`hCn`）。

**实际主流形态（`agentToolCall` 分组）**：由 `L2` 分组为 `agentToolCall`，交给 `wvn` 渲染成工具卡（`r0`），外层仍是
`div[data-row-id][data-conversation-selectable=true][data-testid]`（`index-Dwhx77cK.js:4300930` 附近）。
卡片本体属 `docs/chat/04-toolcall.md` 范围，此处不展开。

---

### 3.9 artifact — `artifact`

```text
div[data-row-id][data-testid] class="px-4 py-1"
└─ div class="flex items-center gap-2 rounded-lg border border-card-border bg-card px-3 py-2"
   ├─ FileBox size-4 shrink-0 text-foreground-subtle
   └─ div class="min-w-0 flex-1"
      ├─ p class="truncate text-ui-base text-foreground"       displayName
      └─ p class="text-ui-sm text-foreground-subtle"           `{TYPE} · {size} bytes`
```

证据：`index-Dwhx77cK.js:4485873`（`ZSn`）。
尺寸：行内边距 16px/4px；卡片 8px 圆角、1px 边框、`#2b2b2b` 背景、12px/8px 内边距、8px 图标间距。

---

### 3.10 未知行

`default: return null`（`index-Dwhx77cK.js:4519608`）。**无兜底 UI、无占位、无错误提示**。`confirmed`。
（分享页另有 `unsupportedRowCount` 提示条，但不在对话页路径上。）

---

## 4. Markdown 正文排版

### 4.1 根容器

```text
div class="space-y-4 whitespace-normal [&>*:first-child]:mt-0 [&>*:last-child]:mb-0
            size-full text-ui-base leading-[1.75] tracking-wide"
```

| 项 | 值 | 证据 |
|---|---|---|
| 字号 | 14px | `--text-ui-base:var(--ui-font-size)`，`--ui-font-size:14px` |
| 行高 | **1.75 → 24.5px** | `leading-[1.75]`（`index-Dwhx77cK.js:2925476`） |
| 字距 | **0.025em → 0.35px** | `tracking-wide`（CSS `--tracking-wide`） |
| 块间距 | **16px** | streamdown 根 `space-y-4`（`chunk-BO2N2NFS-DYyAIm7S.js`） |
| 空白 | **`white-space: normal`**（streamdown 根类）；`whitespace-pre-wrap break-words` 只挂在错误边界 `LUt` 的 className 上，仅当 Markdown 渲染抛错、降级为纯文本时才生效 | `index-Dwhx77cK.js:2925476`（`C` vs `w`）、`2823114`（`LUt.render` 无错时直接返回 children） |
| 断词 | `break-words` 在错误降级文本上；正常 Markdown 由元素自身 `break-words`/`wrap-anywhere` 控制（表格、链接等） | 同上 |
| 首末边距 | 首元素 `mt-0`，末元素 `mb-0` | 同上 |

### 4.2 元素级

| 元素 | class（原文） | 计算值 | 证据 |
|---|---|---|---|
| h1 | `mt-6 mb-4 text-ui-xl font-semibold` | 18px/600，上 24 下 16 | `index-Dwhx77cK.js:2914326` |
| h2 | `mt-6 mb-4 text-ui-lg font-semibold` | 16px/600 | 同上 |
| h3 | `mt-6 mb-4 text-ui-base font-semibold` | 14px/600 | 同上 |
| h4 | `mt-6 mb-4 text-ui-base font-semibold` | 14px/600 | 同上 |
| h5 | `mt-6 mb-4 text-ui-base font-medium` | 14px/500 | 同上 |
| h6 | `mt-6 mb-4 text-ui-base font-normal` | 14px/400 | 同上 |
| p | 无 class（Tailwind preflight 已清 margin） | 块间距由 `space-y-4` 提供 | `index-Dwhx77cK.js:2876686` |
| ul | `my-3 list-outside list-disc space-y-1.5 pl-5 marker:text-foreground-subtlest [&_ul]:my-1.5 [&_ol]:my-1.5` | 12px 外边距、20px 缩进、6px 项距 | `index-Dwhx77cK.js:2833684` 区域 |
| ol | `my-3 list-inside list-decimal space-y-1.5 pl-0 marker:…` | 12px、无额外缩进 | 同上 |
| li | `pl-1 [&>p]:my-0 [&>p]:inline` | 4px 左内边距，段落内联 | 同上 |
| blockquote | `my-4 border-border border-l-2 pl-3 text-foreground-subtle [&_p]:my-0 [&_p+p]:mt-2` | 16px 外边距、2px 竖线、12px 缩进 | 同上 |
| a（内链/外链） | `wrap-anywhere text-ui-base font-medium text-icon-blue no-underline decoration-dotted underline-offset-4 hover:underline` | `#80beff`，无下划线，hover 下划线 | `index-Dwhx77cK.js:2914041` |
| strong | `font-medium` | 500 | `index-Dwhx77cK.js:2925476` |
| 行内 code | `rounded-md bg-markdown-inline-code/50 mx-0.5 px-1.5 py-0.5 font-mono text-ui-sm` | 6px 圆角、12px 字、2px/6px/2px 内距、背景 `#36363680`（zai-dark 的 `--color-tag`） | 同上；CSS `.bg-markdown-inline-code\/50` |
| 代码块外框 | 单元素：基类 `group relative w-full overflow-hidden rounded-xl bg-background text-foreground` + 传入 `my-4 border border-border bg-card`；`.bg-card` 在 CSS 中位于 `.bg-background` 之后 → **实际底色 `#2b2b2b`** | 16px 外边距、1px 边框、12px 圆角 | `index-Dwhx77cK.js:989621`；CSS `.bg-card`(118042) > `.bg-background`(113382) |
| 代码块头 | `flex items-center justify-between gap-3 px-3 py-2 text-ui-base text-muted-foreground` | 12px/8px 内距、12px 间距；语言名 `font-mono lowercase`；文件图标 16px | `index-Dwhx77cK.js:989876` |
| 代码块体 | `p-2 pt-0 pb-3`；代码字号 **12px**（默认 `fontSizePx:12`），默认显示行号、默认不换行 | — | `index-Dwhx77cK.js:989923`；默认值 `ic` @77528 |
| 图片 | 触发器 `my-4 block w-fit max-w-1/2 cursor-zoom-in overflow-hidden rounded-xl`；`img` `h-auto max-h-90 max-w-full rounded-xl border border-border bg-background object-cover` | 12px 圆角、最大高 360px、最多占列宽 50% | `index-Dwhx77cK.js:2871490` |
| 图片加载态 | `markdown-image-loading-shimmer absolute inset-0 flex size-full …`；尺寸锁定 `!h-44 !w-44` | 176×176 占位 + shimmer 1.4s | `index-Dwhx77cK.js:2877313` |
| 图片组 | `my-4 grid grid-cols-1 gap-2 sm:grid-cols-2 md:flex md:flex-wrap`，组内缩略图 `h-32`（移动）/`h-44`（≥768px） | 8px 间距 | `index-Dwhx77cK.js:2871490`（`FBt`） |

### 4.3 表格

```text
div class="my-0 flex min-w-0 flex-col gap-2"
├─ div[data-markdown-table-toolbar] class="flex items-center justify-end gap-1"
│   └─ 最多 4 个 ghost 图标按钮（复制 Markdown / 下载 CSV / 全屏预览 / 滚动模式），图标 14px
├─ div[data-markdown-table-frame][data-markdown-table-virtual-scroll-sticky]
│   class="group/markdown-table-frame relative w-full"
│   └─ div class="min-w-full w-max [transition-[max-width] duration-200]"
│      └─ div class="relative w-full overflow-hidden rounded-xl border border-border"
│         ├─ div class="!scrollbar-hide w-full overflow-x-auto overflow-y-visible"
│         │   └─ table[data-streamdown=table] class="w-max min-w-full border-separate border-spacing-0 text-ui-base"
│         └─ 左右边缘阴影：absolute inset-y-0 z-10 w-6，inset shadow 12px
└─ div[data-markdown-table-virtual-scroll-sticky] class="pointer-events-none py-1 …"
   └─ div[data-markdown-table-virtual-scroll-track] class="mt-1 h-3.5 w-full touch-none rounded-full bg-foreground/3"
      └─ 滑块 class="h-3 rounded-full bg-foreground/20 hover:bg-foreground/40"
```

| 元素 | class | 计算值 |
|---|---|---|
| table | `w-max min-w-full border-separate border-spacing-0 text-ui-base` | 内容驱动宽度，可横向滚动 |
| th | `border-border border-b px-3 py-2 text-left font-normal text-foreground-subtlest min-w-16 max-w-md whitespace-normal break-words` | 12px/8px、最小 64px、最大 448px |
| td | `border-border border-b px-3 py-2 text-foreground align-top min-w-16 max-w-md whitespace-normal break-words` | 同上 |
| tr | `transition-colors last:[&>td]:border-b-0 hover:bg-hover/20` | 悬停 5% 白 |
| 自定义滚动条 | 轨道高 14px、滑块高 12px，悬停才显示 | 同上 |

证据：`index-Dwhx77cK.js:2837247`（`iBt`）；单元格 `2757029`–`2757568`。

### 4.4 附件（用户消息侧，非 Markdown）

| 形态 | class | 尺寸 |
|---|---|---|
| 图片/视频（`all` 模式） | `relative size-12 overflow-hidden rounded-lg bg-surface p-0 after:…border after:border-border` | 48×48 |
| 媒体（`media` 模式） | `relative size-20 overflow-hidden rounded-xl …` | 80×80 |
| 文件胶囊（`all`） | `h-12 w-fit max-w-full min-w-0 gap-2 rounded-lg border border-border bg-surface p-1.5 pr-6` | 高 48、左 6 右 24 |
| 内联胶囊（`media` 模式里的非媒体） | `rounded-full border-0 bg-surface px-3 py-1.5` | 12px/6px |
| 文件名 | `block truncate text-ui-base font-medium text-foreground` | 14px/500 |
| 副标题 | `block truncate text-ui-sm font-normal text-foreground-subtle` | 12px/400 |

证据：`index-Dwhx77cK.js:4487329`（`P2`）。

---

## 5. 状态：流式 / 加载 / 空态 / 错误态

### 5.1 流式

| 项 | 事实 | 证据 |
|---|---|---|
| 流式判定 | `row.state==='streaming'`；Markdown 传 `streaming`，`parseIncompleteMarkdown:true` | `index-Dwhx77cK.js:4508774`、`2925476` |
| 光标/caret | **没有**。`UV` 未传 `caret`；streamdown 仅在 `caret` 存在时生成 `--streamdown-caret` | `index-Dwhx77cK.js:2925476`；`chunk-BO2N2NFS-DYyAIm7S.js`（`Ce` 依赖 `g`） |
| 逐字淡入 | **assistant 正文没有**；`[data-zcode-stream-animate=true]{animation:zcode-stream-text-in .9s cubic-bezier(.16,1,.3,1) …}` 只用于预览卡/工具流 | CSS `[data-zcode-stream-animate=true]`；`index-Dwhx77cK.js:4462697` |
| 思考流式 | 折叠头显示 `正在思考` 渐变字 + 最后一行跑马灯；内容区不自动展开 | `index-Dwhx77cK.js:4474619` |
| 代码块 | 流式期间关闭语法高亮（`enableSyntaxHighlighting:!streaming`） | `index-Dwhx77cK.js:2925476` |
| 结束后 | reasoning 自动折叠；assistant 动作条出现（hover 才可见） | `index-Dwhx77cK.js:4364435` |

### 5.2 「工作中」加载槽（`I2`）

```text
div[data-zcode-chat-loading-slot] class="min-h-5"
└─ (重试中) 重试行 | (eligible) div[data-zcode-chat-loading-animate=true]
     class="inline-flex w-fit items-center gap-2.5"
     role=status aria-label=加载中
   ├─ span[aria-hidden] class="grid shrink-0 size-[13px] grid-cols-3 grid-rows-3 gap-[1.3px] text-foreground"
   │   └─ 9 × span.zcode-loading-pixel.size-full.rounded-[1px].bg-current
   │        style: opacity 0.15（中心 0.07）；duration 950ms；
   │               delay = 顺序位 ×110ms（顺序 [0,1,2,5,8,7,6,3]，中心不动）
   ├─ span.zcode-loading-label.text-ui-base.font-medium → 工作中
   └─ span.font-mono.text-ui-caption.tabular-nums.text-foreground-subtle → 计时（zh：`5.3 秒` / `1 分 5.3 秒`）
```

| 项 | 值 | 证据 |
|---|---|---|
| 槽最小高 | 20px（`min-h-5`） | `index-Dwhx77cK.js:4523032` |
| 像素格 | 13×13，3×3，gap 1.3px，圆角 1px | `index-Dwhx77cK.js:3905619` |
| 像素动画 | `zcode-loading-pixel-on` 0.65s ease-in-out infinite；峰值透明度 0.75（暗色 1） | CSS `@keyframes zcode-loading-pixel-on`；`.dark .zcode-loading-pixel{--loading-pixel-peak:1}` |
| 文字 | 14px/500，渐变流光 `zcode-loading-shimmer` 1.4s linear infinite | CSS `.zcode-loading-label` |
| 计时 | 13px 等宽、tabular-nums、`foreground-subtle`；每 100ms 刷新；格式为 `时`/`分`/`秒.十分位`（最多 时+分+秒，秒保留 1 位小数；zh 用空格分隔） | `index-Dwhx77cK.js:3906042`（`xin`）、`2993279`（`vin`） |
| 行间距 | `gap-2.5`=10px | 同上 |
| 显示条件 | 最后一 turn 且运行中、未被交互/工具阻塞、无 pendingApproval | `index-Dwhx77cK.js:4293676`（`Q_n`） |

### 5.3 空态

```text
div[data-testid=…] class="w-full"
└─ div class="relative mb-10 flex w-full max-w-2xl flex-col items-center justify-center gap-6
            text-foreground sm:mb-8"
   ├─ div[aria-hidden] class="pointer-events-none absolute left-1/2 top-1/2 aspect-[5/4]
   │        w-[min(72vw,25rem)] -mt-10 -translate-x-1/2 -translate-y-1/2 text-foreground-subtlest"
   │   └─ 装饰 logo（SVG 描边 / 暗色位图），底部 70% 起渐隐
   └─ p[data-v4-draft-greeting]
        style="--v4-draft-greeting-font-size: 20–30px"
        class="relative z-10 w-full px-4 text-center font-medium text-foreground
               text-[length:var(--v4-draft-greeting-font-size)]/[1.2]"
      └─ 问候语（按时段整句：`早上好呀，新的一天开始啦` / `上午好呀，有什么想让我帮忙的吗` / `中午好呀，要不要先休息一下` / `下午好呀，接下来交给我吧` / `晚上好呀，今天辛苦啦` / `夜深啦，别忘了照顾好自己哦`；办公模式为 `今天有什么工作，交给我吧`）
```

| 项 | 值 | 证据 |
|---|---|---|
| 容器宽 | ≤672px（`max-w-2xl`） | `index-Dwhx77cK.js:3523322` |
| 上留白 | 滚动内容 `before` 块 `min-h-[52px] basis-[29dvh]` | `index-Dwhx77cK.js:4594663` |
| 下留白 | `after` 块 `min-h-4 flex-1` | 同上 |
| 问候字号 | 20–30px 自适应（按可用宽/自然宽比例，`WY=20`、`GY=30`），行高 1.2，字重 500 | `index-Dwhx77cK.js:3523322`（`s9t`） |
| 装饰图 | `aspect-[5/4]`、宽 `min(72vw, 25rem)`、上移 40px、透明度 70% | 同上 |
| 元素间距 | `gap-6`=24px；`mb-10`=40px（`sm` 以上 `mb-8`=32px） | 同上 |

> 空态只在「无 session / draft」时传入 `emptyState`；远端非 draft 无消息时是 `p-6 text-center text-ui-base text-foreground-subtle` 的占位（`index-Dwhx77cK.js:4823900`）。`confirmed`。

### 5.4 错误态

```text
div class="flex flex-1 flex-col items-center justify-center gap-3 p-4 text-ui-base"
├─ p class="max-w-full break-words text-center font-mono text-destructive"   ← 错误原文
└─ div class="flex flex-wrap items-center justify-center gap-2"
   ├─ Button variant=outline 反馈
   └─ Button（默认） 重新连接
```

证据：`index-Dwhx77cK.js:3893762`（`Wrn`）。错误态**替换整个时间线**（父级三元），不叠加在列表上。`confirmed`。

---

## 6. 交互态汇总

| 元素 | hover | active / focus | 证据 |
|---|---|---|---|
| 用户/assistant 动作条 | `opacity-0 → 100`（行 hover 或 `focus-within`） | — | `index-Dwhx77cK.js:4508774`、`4497138` |
| 动作按钮（ghost icon-sm） | `hover:bg-hover`（`#ffffff0d`） | `focus-visible` 由 Button 基类提供 | `button-P7AG3MDX.js` |
| turn header 按钮 | 文字不变；chevron 常显 | `focus-visible:ring-2 ring-[var(--color-input-border-focused)]` | `index-Dwhx77cK.js:4528423` |
| reasoning 触发器 | chevron `opacity-0 → 100`；`transition-colors` | 同上（无 ring） | `index-Dwhx77cK.js:4474619` |
| marker | `hover:text-foreground`；可点击时 `group-hover/marker:underline` | — | `index-Dwhx77cK.js:4512864` |
| 表格行 | `hover:bg-hover/20` | — | `index-Dwhx77cK.js:2757029` |
| 表格滚动条滑块 | `hover:bg-foreground/40` | — | `index-Dwhx77cK.js:2837247` |
| 回到底部按钮 | `hover:bg-card-selected` | — | `index-Dwhx77cK.js:4571221` |
| 链接 | `hover:underline`（点线下划线，offset 4px） | — | `index-Dwhx77cK.js:2914041` |
| 图片 | `cursor-zoom-in` | `focus-visible:ring-2 ring-ring ring-offset-2` | `index-Dwhx77cK.js:2871490` |

> 全局 `prefers-reduced-motion: reduce` 会关闭 loading 像素、loading 文字、折叠动画、stream 动画（CSS 三条 media 规则）。`confirmed`。

---

## 7. 设计 token（3.17.1 暗色默认 `theme-zai-dark`）

> 默认主题：`index.html` 把 `zai-dark` 归一化为 `dark` 并加 `html.dark`；应用侧 `cc(theme)` 同时切换 `dark` 与 `theme-zai-dark` 两个 class（`index-Dwhx77cK.js:79390`）。两套变量数值大体一致，但前景色不同：`.theme-zai-dark` 声明在 `.dark` 之后（CSS 偏移 445398 > 427273），故**默认 zai-dark 下 `--color-foreground = neutral-300 = #d4d4d4`**；若用户选的是普通 `dark`（非 zai），则为 `neutral-200 = #e5e5e5`。`confirmed`。

| token | 值 | 用途 |
|---|---|---|
| `--color-background` | `#161616` | 页面/代码块内底 |
| `--color-foreground` | `#d4d4d4`（neutral-300；普通 dark 为 `#e5e5e5`） | 正文 |
| `--color-foreground-subtle` | `#d4d4d499`（60%；普通 dark 为 `#e5e5e599`） | 次级文字、turn header、blockquote |
| `--color-foreground-subtlest` | `#d4d4d44d`（30%；普通 dark 为 `#e5e5e54d`） | 三级文字、marker 图标、reasoning |
| `--color-border` | `#ffffff1a`（10%） | 1px 边框、细线 |
| `--color-border-hover` | `#ffffff26`（15%） | 边框 hover |
| `--color-hover` | `#ffffff0d`（5%） | ghost 按钮 hover、表格行 hover 基色 |
| `--color-selected` | `#ffffff1a` | 选中 |
| `--color-surface` | `#ffffff0d`（5%） | 附件底、代码块外层 |
| `--color-user-message-bubble` | `#ffffff14`（8%） | 用户气泡 |
| `--color-surface-hover` | `#ffffff1a` | 表面 hover |
| `--color-card` | `#2b2b2b` | 卡片、代码块、回到底部按钮 |
| `--color-card-selected` | `var(--color-input)` = `#2b2b2b` | 卡片 hover |
| `--color-popover` | `#2b2b2b` | 弹层 |
| `--color-input` | `#2b2b2b` | 输入框 |
| `--color-icon-blue` | `#80beff` | 链接 |
| `--color-icon-purple` | `#a888f2` | 高速模式强调 |
| `--color-success` | `#46bf72` | 成功 |
| `--color-warning` | `#ff8a30` | 警告 |
| `--color-destructive` | `#ff5c5c` | 错误 |
| `--color-markdown-inline-code` | `var(--color-tag)` = `#363636`（zai-dark；普通 dark 为 neutral-700 `#404040`），50% 使用 | 行内代码底 |
| `--color-panel-border` | 未定义 | genUi 状态卡边框实际不生效（`inferred`） |

**尺度 token**

| token | 值 |
|---|---|
| `--spacing` | 4px（所有 Tailwind 间距倍数） |
| `--radius-xs / sm / md / lg / xl / 2xl / 3xl` | 2 / 4 / 6 / 8 / 12 / 16 / 24 px |
| `--container-2xl / 4xl / 6xl / xl` | 672 / 896 / 1152 / 576 px |
| `--ui-font-size` | 14px（可配 12–20，localStorage `zcode-ui-font-size-px`） |
| `--text-ui-2xs / xs / sm / base / caption / lg / xl` | 9 / 10 / 12 / 14 / 13 / 16 / 18 px |
| 根行高 | 1.5（`html,:host{line-height:1.5}`） |
| 字体 | `--font-sans: ui-sans-serif, system-ui, sans-serif, "Apple Color Emoji", …`；`index.html` 的 `body` 再指定 `Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif` |
| 等宽 | `ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, … , "Microsoft YaHei UI", "PingFang SC", "Noto Sans CJK SC", monospace` |

---

## 8. Compose 尺寸对照表（1 CSS px = 1 dp）

### 8.1 页面骨架

| Web | 值 | Compose |
|---|---|---|
| 顶栏高 `h-12` | 48dp | `Modifier.height(48.dp)` |
| 顶栏内边距 `p-2` | 8dp | `padding(8.dp)` |
| 顶栏底边 | 1dp `Color.White.copy(alpha=.102f)` | `HorizontalDivider(thickness=1.dp, color=...)` |
| 滚动条槽 | 两侧各一条 | `contentPadding` 或 `verticalScroll` + 自定义条 |
| 内容列最大宽（<864） | 100% | `fillMaxWidth()` |
| （864–1280） | `min(w-96, 896)` | `widthIn(max=896.dp).padding(horizontal=48.dp)` |
| （≥1280） | `min(w-384, 1152)` | `widthIn(max=1152.dp).padding(horizontal=192.dp)` |
| 摘要面板位移 | -168dp | `offset(x=(-168).dp)` |

> 建议实现：`BoxWithConstraints` 读 `maxWidth`，按 864/1280 两档计算 `contentWidth = min(maxWidth - inset, cap)`，再 `Modifier.width(contentWidth).align(CenterHorizontally)`。

### 8.2 间距

| 语义 | px | dp |
|---|---|---|
| turn 横向 `px-4 / px-6` | 16 / 24 | 16 / 24 |
| turn 纵向 `pt-14 / pb-5` | 56 / 20 | 56 / 20 |
| turn 内 `gap-5` | 20 | 20 |
| assistant-turn `gap-5` | 20 | 20 |
| history-message 兄弟 `mt-5` | 20 | 20 |
| 行组 `gap-4` | 16 | 16 |
| Markdown 块间距 `space-y-4` | 16 | 16 |
| 用户气泡 `gap-2` | 8 | 8 |
| reasoning `gap-2` | 8 | 8 |
| marker `gap-3` | 12 | 12 |
| 动作条 `gap-1` / `mt-1` | 4 / 4 | 4 / 4 |
| 加载槽 `gap-2.5` | 10 | 10 |

### 8.3 行级尺寸

| 元素 | px | dp / 说明 |
|---|---|---|
| 用户气泡内边距 | 16×12 | `padding(horizontal=16.dp, vertical=12.dp)` |
| 用户气泡圆角 | 12（squircle 24） | `RoundedCornerShape(12.dp)` |
| 用户气泡最大宽 | 576 | `widthIn(max=576.dp)` |
| 思考内容最大高 | 240 | `heightIn(max=240.dp)` |
| 思考内容缩进 | 左 8 + 14，竖线 1 | `padding(start=8.dp)` + `drawBehind` 1dp 线 + `padding(start=14.dp)` |
| turn header 下边距 | 8 | `padding(bottom=8.dp)` + 1dp 线 |
| turn header chevron | 16 | `Icon(Modifier.size(16.dp))` |
| marker 细线 | 1dp 高，最小 32dp 宽 | `HorizontalDivider` + `weight(1f)` |
| marker 行内边距 | 16×8 | `padding(horizontal=16.dp, vertical=8.dp)` |
| 回到底部按钮 | 28×28，图标 16 | `Box(Modifier.size(28.dp).clip(CircleShape))` |
| 动作按钮 | 24×24，图标 14 | `IconButton(Modifier.size(24.dp))` |
| 附件图片（小） | 48×48，圆角 8 | `size(48.dp)` |
| 附件图片（媒体） | 80×80，圆角 12 | `size(80.dp)` |
| artifact 卡片 | 内 12×8，圆角 8，图标 16 | — |
| 表格单元格 | 12×8，min 64，max 448 | `widthIn(min=64.dp, max=448.dp)` |
| 表格滚动条 | 轨 14 / 滑 12 | — |
| 图片最大高 | 360 | `heightIn(max=360.dp)` |
| 空态问候字号 | 20–30 | `fontSize = (20..30).dp` 动态 |

### 8.4 文字

| token | px | Compose |
|---|---|---|
| 正文 | 14 / 21（1.5） | `fontSize = 14.sp, lineHeight = 21.sp` |
| Markdown 正文 | 14 / 24.5 | `lineHeight = 24.5.sp`, `letterSpacing = 0.35.sp` |
| 次级 12 | 12 / 18 | `12.sp / 18.sp` |
| 三级 10 | 10 / 15 | `10.sp / 15.sp` |
| 最小 9 | 9 / 13.5 | `9.sp / 13.5.sp` |
| 标题 h1 | 18 / 600 | `18.sp, FontWeight.SemiBold` |
| h2 | 16 / 600 | `16.sp, SemiBold` |
| 计时器 | 13 / mono | `13.sp, FontFamily.Monospace, FontFeatureSettings("tnum")` |
| 代码 | 12 / mono | `12.sp, FontFamily.Monospace` |

### 8.5 颜色（Compose）

```kotlin
val Bg           = Color(0xFF161616)
val Fg           = Color(0xFFD4D4D4)
val FgSubtle     = Color(0xFFD4D4D4).copy(alpha = 0.60f)
val FgSubtlest   = Color(0xFFD4D4D4).copy(alpha = 0.30f)
val Border       = Color(0xFFFFFFFF).copy(alpha = 0.102f)
val Hover        = Color(0xFFFFFFFF).copy(alpha = 0.05f)
val Surface      = Color(0xFFFFFFFF).copy(alpha = 0.05f)
val UserBubble   = Color(0xFFFFFFFF).copy(alpha = 0.08f)
val Card         = Color(0xFF2B2B2B)
val IconBlue     = Color(0xFF80BEFF)
val IconPurple   = Color(0xFFA888F2)
val Success      = Color(0xFF46BF72)
val Warning      = Color(0xFFFF8A30)
val Destructive  = Color(0xFFFF5C5C)
val InlineCode   = Color(0xFF363636).copy(alpha = 0.50f)
```

---

## 9. 与既有 `docs/chat/01-03`（3.15.3）的差异

> 既有文档基线为 3.15.3（`archive/pre-3.16.1-auth/Reference_web/3.15.3/`）。下表只列**以 3.17.1 打包产物为准**时需修正/新增的点。

| # | 项 | 3.15.3（旧文档） | 3.17.1（本文） | 置信 |
|---|---|---|---|---|
| 1 | 虚拟化 `overscan` | 8 | **2**（`Zwn=2`，`index-Dwhx77cK.js:4570886`） | confirmed |
| 2 | 未测量单元估算 | 固定 72px | **视口高度**（`clientHeight\|\|72`），仍以 72 兜底 | confirmed |
| 3 | 用户气泡背景/边框 | `bg-surface` + 1px `border-border` | CSS 覆盖为 `--color-user-message-bubble`（`#ffffff14`）+ `border-width:0`；圆角 12（squircle 24） | confirmed（3.15.3 CSS 无该覆盖规则） |
| 4 | `@md/conversation` 断点 | 旧文档写 768px | **448px**（28rem）；3.15.3 也是 448px，旧文档笔误 | confirmed |
| 5 | turn 动作条 | `mt-1 opacity-0`（同） | 同，但新增 `has-[[data-bot-group-delivery]]:opacity-100`（群投递常显） | confirmed |
| 6 | 用户消息长文本折叠 | 旧文档未记 | `max-height:120px` + 70% 处遮罩 + 展开按钮（`scrollHeight > 121px` 才出现） | confirmed |
| 7 | turn header 文案 | `已工作 {duration}` 等（同） | 同；新增 highspeed 分支（紫色、`pb-3`、节省时长 + 状态胶囊） | confirmed |
| 8 | 底部渐隐遮罩 | 旧文档未记 | 非贴底时底部 96px 渐隐（羽化 24px） | confirmed |
| 9 | 空态 | `max-w-2xl`（同） | 新增 `before: basis-[29dvh]` / `after: min-h-4 flex-1` 精确留白 | confirmed |
| 10 | 加载槽 | 旧文档未记细节 | 13×13 九宫格像素 + 0.65s/110ms 阶梯 + `工作中` 流光 + 计时 13px | confirmed |
| 11 | 行内代码背景 | 旧文档未记 | `--color-markdown-inline-code` = `--color-tag`（zai-dark `#363636`），50% | confirmed |
| 12 | 表格 | 旧文档未记 | 圆角 12 外框 + 隐藏原生滚动条 + 14/12px 自定义滚动条 + 最多 4 个工具栏按钮 | confirmed |
| 13 | 图片 | 旧文档未记 | 最大高 360、最多占列宽 50%、加载态 176×176 shimmer | confirmed |
| 14 | Markdown 空白处理 | 旧文档称 `whitespace-pre-wrap` 覆盖 `whitespace-normal`，源码软换行会保留 | **不成立**：3.17.1（3.15.3 同构）把 `whitespace-pre-wrap break-words` 传给错误边界 `LUt`，正常渲染的 Markdown 根只有 `whitespace-normal`；只有渲染抛错降级为纯文本时才 pre-wrap | confirmed |

> 旧文档中仍然成立的关键结论（已交叉核对）：贴底阈值 48px、退出跟随的即时性、`canLoadOlder` 表达历史耗尽、prepend 手动补偿、`overflow-anchor:none`、无「新消息」pill、回到底部按钮 28px/16px 图标、无加载骨架。

---

## 10. 未解决 / 需真机确认

| # | 项 | 原因 | 建议 |
|---|---|---|---|
| 1 | 代码块行高 | 代码渲染走 `@pierre/diffs`，行高由该库内联样式决定，打包 CSS 未命中 | 以真机 DevTools 量 `line-height`；暂按 12px 字号 + 1.6 估算 |
| 2 | `text-muted-foreground` | 类名存在但 CSS 无对应规则（`--color-muted-foreground` 未定义） | 视为“无颜色覆盖”，继承 `foreground` |
| 3 | `--color-panel-border` | genUi 状态卡用到，但变量未定义 | 视为透明/继承 |
| 4 | squircle 圆角 | 仅在支持 `corner-shape` 的 Chromium 生效 | Compose 侧统一用 12dp |
| 5 | 字体 | 页面实际字体取决于 `index.html` 的 Inter 是否可用 | Android 侧建议打包 Inter 或回落系统无衬线 |
| 6 | 滚动条宽度 | `scrollbar-gutter:stable both-edges` 的具体槽宽由 UA 决定（Chromium 约 15px） | Compose 用固定 8dp 细条近似 |
| 7 | 表格「滚动模式」展开行为 | 涉及 JS 状态与测量，未逐行读完 | 首版可只做横向滚动 |

---

## 附录 A：关键组件字节偏移速查

| 组件 | 偏移（`index-Dwhx77cK.js`） |
|---|---|
| 时间线控制器 `Bwn`（滚动/跟随/虚拟化/回到底部） | `4561785` |
| 时间线组件 `dTn` / `fTn` | `4572292` |
| 滚动容器 JSX / `data-loading-older` | `4592864` / `4593135` |
| 列宽常量 `Mnn/Nnn/Pnn/Fnn` + `Inn`/`znn` | `3851103` / `3851540` |
| turn 单元 `LCn`（`section`） | `4539038` |
| 行分发 `gCn` / `_Cn` | `4519608` / `4520755` |
| turn 段落 `jCn` / `MCn` / `ICn` | `4530379` / `4532869` / `4535672` |
| turn header `R2` | `4528423` |
| 用户消息 `nCn` → `tCn` → `uin` | `4505815` / `4497138` / `3902504` |
| 用户文本折叠 `$rn` | `3896756` |
| 附件 `P2` | `4487329` |
| assistant 文本 `aCn` | `4508774` |
| Markdown 根 `UV` / 标题 `HV` / 标题表 `hUt` | `2925476` / `2924996` / `2914326` |
| 链接常量 `pUt` | `2914041` |
| reasoning `D2` / `O2` / `k2` | `4472614` / `4474619` / `4477287` |
| marker `fCn` / `pCn` | `4512864` / `4514134` |
| turnHeader 调试行 `sCn` | `4512205` |
| hook `GSn` / 列表项 `WSn` | `4483348` / `4482396` |
| subagent `hCn` | `4519322` |
| artifact `ZSn` | `4485873` |
| 行包裹 `N2` | `4485726` |
| 回到底部 `sTn` | `4571221` |
| 加载槽 `I2` / 计时器 `xin` / 像素格 `bin` | `4523032` / `3906042` / `3905619` |
| 空态问候 `c9t` | `3523322` |
| 错误态 `Wrn` | `3893762` |
| 页头 `VLn` / 悬浮 pane 动作 `qen` | `5283197` / `3774241` |
| 表格 `iBt` / 单元格 | `2837247` / `2757029` |
| 代码块 `Zb` / 框 `ZBe` / 头 `Jb` | `989923` / `989621` / `989876` |
| 图片 `lVt` / 常量 `NBt`/`PBt` | `2877313` / `2871490` |
| 跟随阈值 `H2` / 预取 `mwn`/`hwn` | `4555601` / `4556201` / `4556269` |
| 虚拟化常量 `Xwn..iTn` | `4570886` |

## 附录 B：CSS 关键规则原文

| 选择器 | 声明 | 作用 |
|---|---|---|
| `html,:host` | `line-height:1.5;font-family:var(--default-font-family,…)` | 全局行高/字体 |
| `.text-ui-base` | `font-size:var(--text-ui-base)` | 14px，行高继承 |
| `.text-ui-base\/relaxed` | `font-size:var(--text-ui-base);line-height:var(--leading-relaxed)` | 14px/1.625（按钮） |
| `.text-ui-sm` / `.text-ui-xs` / `.text-ui-2xs` / `.text-ui-lg` / `.text-ui-xl` / `.text-ui-caption` | `calc(var(--ui-font-size) ± n px)` | 12/10/9/16/18/13 |
| `.tracking-wide` | `letter-spacing:var(--tracking-wide)` | 0.025em |
| `.space-y-4>:not(:last-child)` | `margin-block-end:calc(var(--spacing)*4)` | Markdown 块间距 16px |
| `.bg-surface` | `background-color:var(--color-surface)` | 5% 白 |
| `.border-border` | `border-color:var(--color-border)` | 10% 白 |
| `.bg-markdown-inline-code\/50` | `background-color:color-mix(in oklab, var(--color-markdown-inline-code) 50%, transparent)` | 行内代码底 |
| `[data-v4-user-input-bubble]` | `background-color:var(--color-user-message-bubble);border-width:0;border-top-right-radius:12px` | 气泡最终外观 |
| `@supports (corner-shape:squircle)` | `[data-v4-user-input-bubble]{corner-shape:squircle;border-radius:24px}` | 方圆气泡 |
| `.animated-gradient-text` | `background:linear-gradient(90deg,…);background-size:300% 100%;animation:4s linear infinite gradient-flow;-webkit-background-clip:text` | 思考/运行中标记 |
| `.zcode-loading-label` | `background-image:linear-gradient(90deg,…);animation:1.4s linear infinite zcode-loading-shimmer` | 「工作中」流光 |
| `.zcode-loading-pixel` | `animation:.65s ease-in-out infinite zcode-loading-pixel-on` | 九宫格像素 |
| `@keyframes collapsible-down/up` | `height:0 ↔ var(--radix-collapsible-content-height)` | 思考/历史折叠 |
| `[data-zcode-stream-animate=true]` | `animation:zcode-stream-text-in .9s cubic-bezier(.16,1,.3,1) …` | 卡片淡入（非正文） |
| `[data-scrollbar-active]` | `--zcode-scrollbar-thumb:var(--color-border)` | 滚动条显影 |
| `.dark .zcode-loading-pixel` | `--loading-pixel-peak:1` | 暗色像素峰值 |
