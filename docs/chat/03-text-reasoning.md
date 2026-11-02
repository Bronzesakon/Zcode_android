# 对话文本与推理外观取证（assistantText / reasoning）— Web 3.15.3

> **基线**：`E:\Zcode_android\archive\pre-3.16.1-auth\Reference_web\3.15.3\`
> `assets/index-BGa3KiRe.js`（6,592,643 B，mtime 2026-10-16 21:02）
> `assets/index-BD2BL4Cc.css`（455,351 B）
> `assets/IntlProvider-C-26GNLk.js`（i18n 文案）
> `assets/chunk-BO2N2NFS-CP8HYAja.js`（streamdown 渲染器）
> `assets/src-AL-_ONNp.js`（testid 常量）
>
> **偏移口径**：UTF-8 **字节**偏移（`node -e "…indexOf('KEY')"` 可复现）。
> **方法**：只读字符串切片 + 组件定义逐行阅读；未执行应用代码。
> **审计日期**：2026-10-17。本文只描述**网页版 3.15.3** 的事实；「不存在」的项也给了否定证据。

---

## 0. 结论速览（给壳实现直接抄）

| 项 | 事实 |
| --- | --- |
| assistantText 外观 | **无气泡、无边框、无背景**，纯文档流；外层行间距 `gap-5`=**20px**，turn 左右内边距 16px（≥md 24px） |
| 正文字体 | `--font-sans`，`text-ui-base`=**14px**，`leading-[1.75]`=**24.5px**，`tracking-wide`=0.025em |
| 段间距 | markdown 根 `space-y-4` → **16px**（段落自身 margin 0） |
| 流式光标 | **没有**。无 caret、无闪烁、无 `▍`；streamdown 的 `animated/isAnimating` 均传 `false`，`caret` 未传 |
| 流式渐变文字 | 只在 **reasoning 折叠态的「正在思考」** 上：`gradient-flow` **4s linear infinite**，`background-size:300% 100%` |
| reasoning 折叠 | lucide **brain** 图标 16px + 「思考 · 持续了 N 秒」14px `foreground-subtlest` + hover 才出现的 chevron-right；**无背景/无边框/无圆角/无内边距** |
| reasoning 展开 | `max-h-60`=**240px** 滚动区，`pt-3`=12px，左侧 1px 竖线 + `pl-3.5`=14px、`ml-2`=8px；**纯文本 `whitespace-pre-wrap`，不是 Markdown** |
| reasoning 默认 | **默认折叠**（`defaultOpen=false`）；流式**不会自动展开**，只把最后一行作为跑马灯预览放进折叠头 |
| 代码块 | `rounded-xl` 12px、`border-border`、`bg-card`、`my-4`；正文 **12px / 20px** 等宽；**无最大高度、无 30 行折叠** |
| 高亮 | shiki `github-light` / `github-dark`（默认）；**流式期间关闭高亮** |
| 复制反馈 | 消息复制：图标 copy→check（`text-success`）**1.2s**；代码块复制：copy→check **2s**；表格复制：**toast 3s**（无图标切换） |

---

## 1. assistantText：容器、间距、字体、排版

### 1.1 DOM 链（从外到内）

```text
section[data-turn-id]                      ← 每个 turn 一节
  └ div.group/assistant-turn.flex.flex-col.gap-5
      ├ (reasoning 行) …（同为 gap-5 子项）
      └ div[data-row-id][data-testid]       ← TZ 渲染单元，无样式
          └ div[data-conversation-selectable="true"].w-full.text-ui-base
              └ Markdown 根（MessageResponse）
                  └ div.space-y-4.whitespace-normal…
```

| 节点 | 类名（原文） | 偏移 |
| --- | --- | --- |
| 渲染单元 `TZ` | 无 className 默认值，仅 `data-row-id` / `data-testid` | `index-BGa3KiRe.js@3335294` |
| assistantText 组件 `ltn` | 外层 `TZ` + `group/assistant-row` | `@3358302` |
| 文本容器 | `w-full text-ui-base` + `data-conversation-selectable="true"` | `@3358766` |
| Markdown 根 | `size-full text-ui-base leading-[1.75] tracking-wide [&>*:first-child]:mt-0 [&>*:last-child]:mb-0`，再拼 `whitespace-pre-wrap break-words` | `@1701334`、`@1700771` |
| streamdown 自带根类 | `space-y-4 whitespace-normal [&>*:first-child]:mt-0 [&>*:last-child]:mb-0` | `chunk-BO2N2NFS-CP8HYAja.js@430222` |

> **空白处理**：`whitespace-normal`（streamdown）与 `whitespace-pre-wrap`（应用）同时存在，CSS 中 `.whitespace-pre-wrap` 定义在后（`index-BD2BL4Cc.css@146954` vs `@146804`）→ **pre-wrap 生效**，源码软换行会被保留为换行。

### 1.2 容器间距（关键数字）

turn 节根类（原文）：

```text
relative mx-auto flex w-full flex-col gap-5 px-4 @md/conversation:px-6 pb-5  [+ pt-14 | pt-0]
```

- 偏移：`index-BGa3KiRe.js@3392393`（`pt-14` 在 `@3392481`，条件 `ne ? 'pt-0' : 'pt-14'`；`ne` = 该 turn 是带标题/工作流通知的 `backgroundResult`，判定 `ztn`/`Btn` @3384532/3384714）。
- `gap-5` = **20px**：同一 turn 内 userInput / reasoning / assistantText / 工具摘要之间的统一间距。
- `px-4` = **16px**；容器查询 `@md/conversation`（≥768px）→ `px-6` = **24px**。
- `pb-5` = **20px**；`pt-14` = **56px**（该 turn 没有可渲染 header 时；有 header 时 `pt-0`）。
- 助手动作条：`mt-1`（4px），且默认 `opacity-0`，`group-hover/assistant-row` 或 `focus-within` 时显示（远端紧凑模式恒显）——`@3360305`、`@3358302`。

内容列宽度（非文本专属，但决定换行宽度）：`index-BGa3KiRe.js@2708934`

```text
w-full
@min-[864px]/conversation:w-[calc(100%_-_6rem)]  max-w-4xl   (896px)
@min-[1280px]/conversation:w-[calc(100%_-_24rem)] max-w-6xl   (1152px)
```

### 1.3 字体、字号、行高、颜色

| 项 | 值 | 证据 |
| --- | --- | --- |
| 字体族 | `ui-sans-serif, system-ui, sans-serif, "Apple Color Emoji", "Segoe UI Emoji", "Segoe UI Symbol", "Noto Color Emoji"` | `index-BD2BL4Cc.css@3604` |
| 等宽 | `ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", "Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC", monospace` | `@3731` |
| 基准字号 | `--ui-font-size:14px` | `@396924` |
| 字号阶 | base 14 / caption 13 / sm 12 / xs 10 / 2xs 9 / lg 16 / xl 18（`calc(ui-font-size ± n)`） | `@11468` |
| 正文行高 | `1.75`（24.5px） | `.leading-[1.75]` `@144600` |
| 字距 | `tracking-wide` = 0.025em | `@10114` |
| 正文颜色 | 容器**不设色**，继承应用外壳 `text-foreground` | 外壳 `@3268700`；token 见下 |

颜色 token（`:root` 浅色 / `.dark` 深色）：

| token | 浅色 | 深色 | 偏移 |
| --- | --- | --- | --- |
| `--color-foreground` | `neutral-700` `oklch(37.1% 0 0)`≈#404040 | `neutral-200` `oklch(92.2% 0 0)`≈#e5e5e5 | css `@18115` / `@405851` |
| `--color-foreground-subtle` | `#40404099`（60%） | `#e5e5e599`（60%） | `@18159` / `@405895` |
| `--color-foreground-subtlest` | `#40404066`（40%） | `#e5e5e54d`（30%） | `@18355` / `@406079` |
| `--color-border` | `#0a0a0a1a`（10%） | `#fafafa1a`（10%） | `@12844` / `@399269` |
| `--color-card` | `#fff` | `neutral-800`≈#262626 | `@13949` / `@400251` |
| `--color-background` | `neutral-50`≈#fafafa | `neutral-900`≈#171717 | `@11546` / `.dark@397951` |
| `--color-hover` | `neutral-200` | `surface-hover`（白 10%） | `@13204` / `@399603` |
| `--color-tag`（行内代码底色） | `neutral-200` | `neutral-700` | `@19846` / `@407851` |
| `--color-icon-blue`（链接） | `sky-500` `oklch(68.5% .169 237.323)` | `sky-600` `oklch(58.8% .158 241.966)` | `@11866` / `@401957` |
| `--color-success` | `green-600` | `green-500` | `@18630` / `@406342` |

> 另有 `theme-zai-light` / `theme-zai-dark` 覆盖（`@414651` / `@421232`）：foreground `neutral-800`/`neutral-300`，subtle `#26262699`/`#d4d4d499`，subtlest `#26262666`/`#d4d4d44d`。

### 1.4 块级排版（Markdown 自定义组件）

所有组件映射在 `index-BGa3KiRe.js@1702500–1703900`（`lN` 的 `components` 表）。

| 元素 | 类名（原文） | 偏移 | 折算 |
| --- | --- | --- | --- |
| `p` | 无 className（`cpt`） | `@1665195` | margin 0；块间距由根 `space-y-4` 给 **16px** |
| `strong` | `font-medium` | `@1702660` 区 | **medium（500），不是 bold** |
| `h1` | `mt-6 mb-4 text-ui-xl font-semibold` | `@1691231` | 上 24 / 下 16；**18px** 600 |
| `h2` | `mt-6 mb-4 text-ui-lg font-semibold` | 同上 | 上 24 / 下 16；**16px** 600 |
| `h3` | `mt-6 mb-4 text-ui-base font-semibold` | 同上 | 上 24 / 下 16；**14px** 600 |
| `h4` | `mt-6 mb-4 text-ui-base font-semibold` | 同上 | 同 h3 |
| `h5` | `mt-6 mb-4 text-ui-base font-medium` | 同上 | 14px 500 |
| `h6` | `mt-6 mb-4 text-ui-base font-normal` | 同上 | 14px 400 |
| `ul` | `my-3 list-outside list-disc space-y-1.5 pl-5 marker:text-foreground-subtlest` + `[&_ul]:my-1.5 [&_ol]:my-1.5` | `@1632439` | 上下 12px、缩进 20px、项间 6px、圆点色 subtlest |
| `ol` | `my-3 list-inside list-decimal space-y-1.5 pl-0 marker:text-foreground-subtlest` + 同上 | `@1632700` | **list-inside**（序号在文本内），缩进 0 |
| `li` | `pl-1 [&>p]:my-0 [&>p]:inline` | `@1632884` | 左 4px；段落在 li 内不换块 |
| `blockquote` | `my-4 border-border border-l-2 pl-3 text-foreground-subtle` + `[&_p]:my-0 [&_p+p]:mt-2` | `@1632156`/`@1632239` | 上下 16px；**2px** 左竖线；左 12px；色 subtle（60%） |
| `hr` | 未覆盖，用 streamdown 默认 | `chunk@420015` 的 `Qy` 映射 | — |

> **margin 竞争关系**：`space-y-4` 编译为 `:where(.space-y-4 > :not(:last-child))`（特异性 0，css `@83410`），因此带 `my-*`/`mt-*` 的块（列表 12px、引用 16px、标题 24/16px、代码块 16px、表格 `my-0`）**以自己的 margin 为准**；只有无 margin 的 `p` 吃到 16px。

### 1.5 表格（详见 §7）

表格根 `my-0 flex min-w-0 flex-col gap-2`（`@1645852`）→ **垂直 margin 为 0**，靠内部 toolbar + `gap-2`（8px）构成视觉间隔。

---

## 2. 流式状态：光标 / 闪烁 / 渐变

### 2.1 assistantText：**没有任何流式视觉特效**

- `lN`（MessageResponse）在流式时把 streamdown 切到 `mode='streaming'`，但显式关闭动画：
  `animated:!1,isAnimating:!1`（`index-BGa3KiRe.js@1704078`），且**未传 `caret`**。
- streamdown 的 caret 机制本身存在：`--streamdown-caret` + `[&>*:last-child]:after:content-[var(--streamdown-caret)]`（`chunk@429920`，css `@329932`），字符表为 `{block:' ▋', circle:' ●'}`（`chunk@426159` 区）。**本应用未启用**，所以 DOM 里不会出现 `▋/●`。
- 全文件搜索 `streamdown-caret` 在 `index-BGa3KiRe.js` 中**零命中**；CSS 中也没有 `[data-streamdown]` 选择器。
- 也没有 `animate-pulse` / `animate-caret-blink` / `blink` 用于文本；`animate-pulse` 仅用于进度条、骨架屏、运行中小圆点等（`@1519600`、`@1555600`、`@2638100`、`@3276800`）。
- 结论：流式时文本只是**逐字增长**；流式结束**无需移除任何光标**。

### 2.2 reasoning 折叠头：唯一的流式动画

| 元素 | 实现 | 数值 | 偏移 |
| --- | --- | --- | --- |
| 「正在思考」 | `span.animated-gradient-text.font-medium` | 见下 | `@3324746` |
| 渐变背景 | `linear-gradient(90deg, strong 0%, strong 34%, soft 50%, strong 66%, strong 100%)`，`background-size:300% 100%`，`background-clip:text`，`color:transparent` | — | css `@341019` |
| 动画 | `animation: 4s linear infinite gradient-flow` | **4s，无限循环，linear** | css `@341019` |
| 关键帧 | `0%{background-position:100% 0} 50%{background-position:0 0} 100%{background-position:0 0}` | — | css `@342687` |
| 浅色渐变端点 | strong `#0a0a0a` / soft `#0a0a0a38` | — | css `@11466`/`@11506` |
| 深色渐变端点 | strong `#fff` / soft `#fff3` | — | css `@397957`/`@397994` |
| 高速模式变体 | `.highspeed-animated-gradient-text .animated-gradient-text` 改紫：strong=`--color-icon-purple`，soft=`#e12afb38` | — | css `@341587` |
| 减少动效 | `animated-gradient-text` **没有** `motion-reduce` 处理（仍会动）；只有 `cua-group-gradient-text` 有 | — | css `@342500` 区 |

**流式最后一行预览**（折叠时才出现）：

- 触发条件：`isStreaming && !isOpen`，取 `text` 最后一个非空行（去尾空白），`Ren`（`@3323759`）。
- 结构：`span.min-w-0.flex-1.overflow-hidden.whitespace-nowrap.text-foreground-subtle`，`data-reasoning-streaming-line/roll/mask`（`@3325757` 区）。
- 自动滚到行尾：`Ien(e){e.scrollLeft=e.scrollWidth}`（`@3323659`）。
- 溢出时才加左右 16px 渐隐遮罩：`linear-gradient(to right, transparent 0, black 16px, black calc(100% - 16px), transparent 100%)`（`@3323926`/`@3324032`）。
- 换行滚动动画（`DWe`，`@870087`）：
  - 单行停留 **800ms**（`vWe=800`，`@869305`）；
  - 最小刷新间隔 **250ms**（`yWe=250`，`@869313`）；
  - 队列最多 2 条（`bWe=2`，`@869321`）；
  - 进出场 `{duration:0.3s, ease:[.4,0,.2,1]}`，`y:0.8em→0`、`opacity:0→1`；退场 `y:-0.8em`（`@869327`、`@870087` 区）；
  - `prefers-reduced-motion` 时退化为纯文本（`EWe`/`wWe`，`@869700` 区）。

### 2.3 流式结束后的收尾

- 「正在思考」→ 静态「思考 · 持续了 N 秒」；渐变类移除（`bZ` 三元分支，`@3324746` 起）。
- 最后一行预览 `l` 变 `null` → 预览 span 从 DOM 移除（`@3324200` 区）。
- 代码块：流式时 `enableSyntaxHighlighting:!v`、`renderMermaid:!v` → **流式期间不高亮、不渲染 Mermaid**；结束后才启用（`@1703591` 区、`@1703809`）。

---

## 3. reasoning 折叠态（默认态）完整外观

组件：`utn`（`index-BGa3KiRe.js@3360411`）→ `yZ`/`bZ`/`xZ`（`@3321900–3328560`）。

### 3.1 结构

```text
div[data-row-id]                                  ← TZ，无样式
  └ Collapsible.Root (not-prose flex flex-col, w-full)
      ├ Collapsible.Trigger  <button data-testid="chat-reasoning-trigger">
      │    ├ svg.lucide-brain.size-4
      │    ├ span[data-reasoning-label]  「思考 · 持续了 N 秒」/「正在思考」
      │    ├ span 「·」
      │    ├ span[data-reasoning-streaming-line] 最后一行预览（仅流式且折叠）
      │    └ svg.lucide-chevron-right.size-4
      └ Collapsible.Content  data-testid="chat-reasoning-content"
```

### 3.2 折叠行样式

触发按钮类（原文，`@3325757`）：

```text
group/reasoning inline-flex max-w-full min-w-0 items-center gap-2 self-start
text-ui-base transition-colors w-full
```

| 项 | 值 | 证据 |
| --- | --- | --- |
| 背景 | **透明**（无 `bg-*`） | 类名 |
| 边框 / 圆角 | **无** | 类名 |
| 内边距 | **无**（0） | 类名 |
| 图标 | lucide **brain**，`size-4`=**16px**，`shrink-0`，`text-foreground-subtlest` | `Xs` 定义 `@85808`；用法 `@3325757` 区 |
| 图标描边 | lucide 默认 `stroke-width:2`、`viewBox 0 0 24 24`、`stroke:currentColor` | `createLucideIcon-BsjirBgz.js@`（`s={…strokeWidth:2}`） |
| 字号 | **14px**（`text-ui-base`） | css `@143552` |
| 行高 | 继承 1.5 → **21px** | css `@20632` |
| 主文案 | 「思考」`font-medium` + `text-foreground-subtlest` | `@3324746` 区 |
| 分隔符 | 「·」`font-normal` + `text-foreground-subtlest` | 同上 |
| 时长 | 「持续了 N 秒」/「持续了几秒」`font-normal` + `text-foreground-subtlest` | 同上 |
| 元素间距 | `gap-2` = **8px** | 类名 |
| 展开箭头 | lucide **chevron-right**，16px；`rotate-0 opacity-0`，`group-hover/reasoning:opacity-100`；展开时 `rotate-90 opacity-100`；`transition-opacity transition-transform`（默认 150ms） | `Do` 定义 `@66500` 区；用法 `@3325757` 区 |
| 上下间距 | 作为 `gap-5` 列的子项 → 与相邻行 **20px** | `@3392393` |
| 高度 | ≈ 21px（文字行高；图标 16px 居中） | — |

### 3.3 文案模板（zh-CN，`IntlProvider-C-26GNLk.js@224762`）

| 状态 | 文案 | 说明 |
| --- | --- | --- |
| 流式中 + 折叠 | **正在思考** | `chat.reasoning.thinking`，带渐变动画 |
| 流式中 + 展开 | **思考 · 持续了 {N} 秒**（首秒前为「思考 · 持续了几秒」） | `chat.reasoning.thought` + `durationSeconds` / `durationFewSeconds` |
| 已完成 + 有 `durationMs` | **思考 · 持续了 N 秒**，`N = max(1, ceil(durationMs/1000))` | `utn` `@3360411` |
| 已完成 + 无 `durationMs` | **思考 · 持续了几秒** | 同上 |

> **没有「已思考」文案**。全库 `chat.reasoning.*` 只有 4 个 key（`thinking/thought/durationFewSeconds/durationSeconds`），不存在「已思考」。

### 3.4 不可见条件

- `state==='streaming' && text.length===0` → 整行 **return null**（`@3360411`）。
- 非首条 reasoning 且设置 `messageStreamShowReasoning !== true` → 不渲染；首条 reasoning（`messageStreamFirstReasoningRowId`）始终渲染。判定函数：
  `function yX(e,t){return t.messageStreamShowReasoning===!0 || t.messageStreamFirstReasoningRowId===e}`（`@3164324`）。默认值为 `true`（`@6140558`：`It(e.messageStreamShowReasoning??!0)`）。

---

## 4. reasoning 展开态外观

内容组件 `xZ`（`@3327800–3328500`）：

```text
Collapsible.Content (overflow-hidden, animate-collapsible-down/up 300ms)
  └ div.pt-3
      └ div.max-h-60.space-y-2.overflow-auto.text-ui-base.text-foreground-subtlest
           .ml-2.border-border.border-l.pl-3.5     ← 仅 variant="default"
        └ div.min-w-0.whitespace-pre-wrap.break-words.text-foreground-subtlest
             └ {纯文本}
```

| 项 | 值 | 证据 |
| --- | --- | --- |
| 最大高度 | `max-h-60` = **240px** | `@3328138` |
| 滚动 | `overflow-auto`；溢出时上下 24px 渐隐遮罩 | `@3328138`、`fB` `@2006686`（`cB=24`） |
| 上间距 | `pt-3` = **12px** | `@3327800` 区 |
| 左侧竖线 | `border-l` 1px `var(--color-border)`；`pl-3.5`=14px；`ml-2`=8px | `@3328226` |
| 行内间距 | `space-y-2`=8px（内容为单文本节点，实际无子块） | `@3328138` |
| 字体 | 与正文同族；`text-ui-base`=**14px**；行高继承 1.5 → **21px** | — |
| 颜色 | `text-foreground-subtlest`（浅 40% / 深 30% alpha） | token 见 §1.3 |
| 是否 Markdown | **否**。子节点是 `e.text` 字符串，容器 `whitespace-pre-wrap break-words`，只保留换行与空白 | `@3327800` 区 |
| 变体 `nested` | **不加** `ml-2 border-l pl-3.5`（仅 `default` 加）；用于 CUA 组内嵌 reasoning | `@3328226`、`@3373200`/`@3374150` |
| 展开/收起动画 | Radix Collapsible：高度 0↔`--radix-collapsible-content-height`，**300ms ease-in-out**；内层 fade-in/out 300ms；收起后内容在 **300ms** 后卸载（`jen=300`） | `W_` `@735583`；keyframes css `@453122`/`@453333`；`jen` `@3321948` |
| 自动跟随底部 | 用户距底 ≤ **2px** 时，内容增长自动滚到底；否则保持位置 | `Pen/Fen` `@3322081`（`Men=2`） |

> `data-*` 锚点：`data-reasoning-content="true"`、`data-reasoning-content-variant="default|nested"`、`data-reasoning-scroll-mask="none|top|bottom|both"`；testid `chat-reasoning-content`（`src-AL-_ONNp.js@31259`）。

---

## 5. reasoning 自动展开 / 自动折叠规则

### 5.1 状态机（`yZ`，`@3322400` 区）

```js
yZ = ({ isStreaming, autoCollapseKey, open, defaultOpen=false, onOpenChange, duration })
```

| 规则 | 事实 | 证据 |
| --- | --- | --- |
| 初始状态 | **折叠**（`defaultOpen=false`；`utn` 未传 `open`，非受控） | `@3322400` 区 |
| 流式时自动展开 | **没有**。代码中不存在 `setOpen(true)` 的自动路径；流式只驱动折叠头的「正在思考 + 最后一行预览」 | `@3322400` 区 |
| 自动折叠 key | `utn` 传 `autoCollapseKey = isStreaming ? null : row.state` | `@3360411` |
| 自动折叠条件 | `key != null && key !== prevKey && !userInteracted` → `setOpen(false)`（`Nen`，`@3321969`） | — |
| 触发时机 | 流式结束（`null → 'complete'|'interrupted'`）时执行一次 | 同上 |
| 实际可见效果 | 因为初始就是折叠，**通常看不到变化**；只有「曾被程序性打开且用户从未交互」时才会真正折回 | 逻辑推导 |
| 用户手动展开后 | `onOpenChange` 把 `userInteracted=true`（`g.current=true`），此后**永久跳过**自动折叠 → **保持展开** | `@3322400` 区（`b` 回调） |
| 用户手动收起后 | 同样置位 `userInteracted`，不会因流式结束再变化 | 同上 |
| 跨行/重挂载 | 展开状态是组件本地 state，**无持久化 key**；时间线虚拟列表滚出视口会卸载行，再滚回时**回到折叠** | `TZ`/虚拟列表 `@3438379` 区 |
| 时长计时 | 仅在**展开**时每 1000ms 更新；折叠时冻结；流式结束时用 `ceil((now-start)/1000)` 收口 | `@3322400` 区（`vZ=1e3`） |
| 受控 `duration` | 若 `row.durationMs` 存在，`utn` 传 `duration` 受控值，覆盖本地计时 | `@3360411` |

### 5.2 一句话

> **默认折叠；流式不自动展开；流式结束只在「用户从未交互」时执行一次自动折叠（对默认折叠态不可见）；用户手动展开后保持展开，直到该行被虚拟列表卸载。**

---

## 6. 代码块

### 6.1 结构

```text
div.group.relative.w-full.overflow-hidden.rounded-xl.bg-background.text-foreground
    .my-4.border.border-border.bg-card            ← 实际底色 bg-card 胜出
  ├ header.flex.items-center.justify-between.gap-3.px-3.py-2.text-ui-base
  │        .text-muted-foreground.pl-3.pr-2.pt-2
  │   ├ div.flex.items-center.gap-2
  │   │   ├ <file icon 16px>
  │   │   └ span.font-mono.truncate.lowercase      ← 语言标签
  │   └ div.-my-1.-mr-1.flex.items-center.gap-1
  │       ├ [Mermaid 放大预览按钮]（仅 mermaid）
  │       ├ [自动换行按钮]（showWrapButton）
  │       └ [复制按钮]
  └ div.p-2.pt-0.pb-3
      └ div.h-full.w-full.overflow-auto             ← diffs 渲染器
          └ pre > code（等宽 12px / 20px）
```

| 项 | 值 | 证据 |
| --- | --- | --- |
| 外层 | `rounded-xl`=**12px**，`border` 1px `--color-border`，`bg-card`，`overflow-hidden`，`my-4`=**16px** | `BFe` `@730965`；调用处 `@1703591` |
| 底色 | `bg-card`：浅 `#fff` / 深 `#262626`（`bg-card` 在 CSS 中晚于 `bg-background`，`@109405` > `@104745`） | — |
| 性能 | `content-visibility:auto`，`contain-intrinsic-size:auto 200px` | `@730965` |
| 头部内边距 | 左 12 / 右 8 / 上 8 / 下 8 px（`px-3 py-2` + `pl-3 pr-2 pt-2`） | `I_` `@731220` |
| 头部字号/颜色 | **14px**；`text-muted-foreground` 类**未生成**（css 无 `.text-muted-foreground{`）→ 实际继承 `text-foreground` | `@731377` |
| 语言标签 | `font-mono lowercase truncate`；无语言时显示 `text`；前置文件图标 16px | `@731220` 区 |
| 头部按钮 | `variant=ghost size=icon-md` → **28×28px**，`rounded-lg`，图标 **14px**（`size-3.5`）；间距 `gap-1`=4px | `button-B-6dbTqe.js@4853`；`@733675`/`@734156`/`@734637` |
| 代码字号 | **12px**（`codePreviewSettings.fontSizePx` 默认 12） | `@1188124`；`Gje` `@662834` |
| 代码行高 | **20px**（diffs 默认 `--diffs-line-height:20px`，应用未覆盖） | `@523000` |
| 代码字体 | `--font-mono` | `Gje` `@662834` |
| 行号 | **关闭**（`z_` 的 `showLineNumbers` 默认 `false` → `disableLineNumbers:true`） | `@732121` |
| 最大高度 | **无**。只有 `overflow-hidden`（外层）与 `overflow-auto`（内层横向/纵向） | 类名 |
| 30 行折叠 | **不存在**。无 `codeBlock.showMore/showLess/expand/collapse` 文案；`codeBlock.*` 仅 13 个 key（copyCode/wrapLines/mermaid.*）；无阈值常量、无按钮 | i18n 全量枚举；`@661000–737000` 无 `collapse/lineLimit/showMore` |
| 语法高亮 | shiki，默认 `github-light` / `github-dark`；流式期间 `enableSyntaxHighlighting=false` | `@1188124`；`@1703591` 区 |
| 横向 | `wrapLongLines=false` 默认 → 横向滚动；开启后 `overflow:wrap` | `@1188124`；`Gje` |
| Mermaid | 流式期间不渲染；自动渲染上限 `maxSourceChars=20000`、`maxLines=600`、`maxComplexityScore=1500`；预览块 `max-h-[420px]` | `@676437`、`@711771` |

### 6.2 复制按钮

`V_`（`@734637`）：

- tooltip / aria-label：**复制代码**（`codeBlock.copyCode`，`IntlProvider@65857`）。
- 点击：`navigator.clipboard.writeText(code)`；成功后 `setCopied(true)`，`setTimeout(…, 2000)` 复位。
- 图标：`yc`（lucide **copy**，14px）→ `tc`（lucide **check**，14px）；**无颜色变化**（`@88531`/`@93731`）。
- 无 toast；失败走可选 `onError`（聊天场景未传）。

---

## 7. 表格、图片、链接、行内代码

### 7.1 Markdown 表格

组件 `bft`（`@1636644`）；结构（`@1645600–1647600`）：

```text
div.my-0.flex.min-w-0.flex-col.gap-2
  ├ div.flex.items-center.justify-end.gap-1[data-markdown-table-toolbar]
  │   ├ 复制 Markdown / 下载 CSV / 预览表格 / 展开·收回滚动区域
  └ div.group/markdown-table-frame.relative.w-full
      └ div.min-w-full.w-max > div.relative.w-full.overflow-hidden.rounded-xl.border.border-border
          └ div.!scrollbar-hide.w-full.overflow-x-auto.overflow-y-visible
              └ table.w-max.min-w-full.border-separate.border-spacing-0.text-ui-base
```

| 项 | 值 | 偏移 |
| --- | --- | --- |
| 外框 | `rounded-xl`=**12px**，1px `--color-border`，`overflow-hidden` | `@1646435` |
| 表格 | `border-separate border-spacing-0`，`text-ui-base`=14px | `@1647029` |
| `th` | `border-b px-3 py-2 text-left font-normal text-foreground-subtlest min-w-16 max-w-md whitespace-normal break-words` | `@1649744`/`@1649810` |
| `td` | `border-b px-3 py-2 text-foreground align-top min-w-16 max-w-md whitespace-normal break-words` | `@1649958`/`@1650024` |
| `tr` | `transition-colors last:[&>td]:border-b-0 hover:bg-hover/20` | `@1649599`/`@1649665` |
| 单元格尺寸 | 最小 64px（`min-w-16`）、最大 448px（`max-w-md`）；内边距 12/8px | — |
| 横向滚动 | 隐藏滚动条 + 左右 24px 渐隐（`w-6` + inset shadow） | `@1646435` 区 |
| 预览弹窗 | `rounded-2xl p-4`，最大 `calc(100vh-8rem)`，`bg-background` | `@1647600` 区 |
| 工具按钮 | `ghost size=icon-md` 28px，图标 14px；文案见下 | `hM` `@1649231` |

表格工具文案（`IntlProvider`）：`复制 Markdown` / `下载 CSV` / `预览表格` / `展开表格滚动区域` / `收回表格滚动区域` / `已复制 Markdown 表格` / `复制表格失败：{error}`。

### 7.2 图片

组件 `dpt`（`@1665831`）；类常量 `@1660024`/`@1660222`：

| 项 | 值 |
| --- | --- |
| 单图触发器 | `my-4 block w-fit max-w-1/2 cursor-zoom-in overflow-hidden rounded-xl outline-none focus-visible:ring-2 focus-visible:ring-ring` |
| `img` | `h-auto max-h-90 max-w-full rounded-xl border border-border bg-background object-cover` |
| 最大高度 | `max-h-90` = **360px**；最大宽度 = **50%** 容器宽 |
| 圆角/描边 | 12px；1px `--color-border` |
| 懒加载 | `loading="lazy"`、`draggable=false` |
| 多图（≥2） | `my-4 grid grid-cols-1 gap-2`，缩略图 `h-32 w-full max-w-none`（128px 高），点击进灯箱 |
| 加载态 | `markdown-image-loading-shimmer` 覆盖层 + 居中 loader 图标 24px |

### 7.3 链接

- 普通链接类（`$mt`，`@1690946`/`@1690951`）：
  `wrap-anywhere text-ui-base font-medium text-icon-blue no-underline decoration-dotted underline-offset-4 hover:underline`
- 颜色：`--color-icon-blue` = 浅 sky-500 / 深 sky-600（见 §1.3）。
- 外链（http/https）渲染为 `<button>`（`Vht`，`@1696603`）并追加 `cursor-pointer bg-transparent p-0 text-left`；左键默认交给系统/内嵌浏览器，`metaKey/ctrlKey` 强制外部；右键菜单含「打开」等。
- 工作区文件链接走 `Uht`，类 `eht`：`inline-flex max-w-full items-baseline gap-1 align-baseline text-icon-blue text-ui-base no-underline decoration-dotted underline-offset-4 hover:underline`（`@1691072`）。

### 7.4 行内代码

```text
rounded-md bg-markdown-inline-code/50 mx-0.5 px-1.5 py-0.5 font-mono text-ui-sm
```

- 偏移：`index-BGa3KiRe.js@1703432`。
- 圆角 **6px**；左右外边距 2px；内边距 6px/2px；字号 **12px**；等宽字体。
- 底色：`color-mix(in oklab, var(--color-markdown-inline-code) 50%, transparent)`；浅色回退 `#e5e5e580`（css `@115385`）。
- `--color-markdown-inline-code` = `--color-tag`（浅 `neutral-200` / 深 `neutral-700`）。

---

## 8. 复制按钮点击反馈汇总

| 场景 | 图标切换 | 颜色 | 时长 | Toast | 证据 |
| --- | --- | --- | --- | --- | --- |
| assistant 消息复制 | copy → check | check 加 `text-success` | **1200ms** | 无 | `ntn` `@3336036`；`@3336401`；`tc` `@88531` |
| 代码块复制 | copy → check | 无 | **2000ms** | 无 | `V_` `@734637`（`timeout:i=2e3`） |
| Markdown 表格复制 | **无切换** | — | — | **有**，`已复制 Markdown 表格`；默认 toast **3000ms** | `Ee` `@1644657`；toast host `imeComposition-C0HlYpBV.js@`（`Fa=3e3`） |
| 表格下载失败 | — | — | — | 有，`下载表格失败` | `@1644700` 区 |

补充：

- assistant 复制按钮是 `aN`（`ghost` + `icon-sm` = **24×24px**，图标 14px，`rounded-md`），带 tooltip「复制」（`chat.message.copy`），成功时 tooltip **不变**；`chat.message.copy.copied`（「已复制」）在文案表里存在但**全库未被引用**（仅 `IntlProvider-C-26GNLk.js@220956` 定义）。
- 代码块复制按钮 28×28px（`icon-md`），tooltip「复制代码」；mermaid 另有「放大查看」，换行按钮「自动换行」。
- 表格工具按钮 28×28px，无状态切换（复制后不变成勾）。

---

## 9. 明确「不存在」或「未启用」的项（否定证据）

| 问题 | 结论 | 证据 |
| --- | --- | --- |
| assistantText 流式光标 / 闪烁 | **不存在** | `lN` 显式 `animated:!1,isAnimating:!1`（`@1704078`）；无 `caret` 入参；`streamdown-caret` 在主包零命中 |
| reasoning 流式自动展开 | **不存在** | `yZ` 无自动 `setOpen(true)`；`utn` 不传 `open`；`defaultOpen=false` |
| 「已思考」文案 | **不存在** | `chat.reasoning.*` 仅 4 个 key |
| 代码块 30 行折叠 | **不存在** | `codeBlock.*` 无展开/收起 key；`@661000–737000` 无折叠逻辑；无 `max-h` |
| 代码块最大高度 | **不存在** | 外层仅 `overflow-hidden`，内层 `overflow-auto`，无 `max-h-*` |
| reasoning 内容 Markdown 渲染 | **不渲染** | 内容节点为纯字符串 + `whitespace-pre-wrap`（`@3327800` 区） |
| reasoning 展开状态持久化 | **不持久化** | 仅组件本地 state；无 storage/store key |
| 渐变文字 reduced-motion 关闭 | **未关闭** | `.animated-gradient-text` 无 `motion-reduce`（css `@341019`） |

---

## 10. 复现命令（只读）

```bash
cd /e/Zcode_android/archive/pre-3.16.1-auth/Reference_web/3.15.3/assets

# 组件定位
node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');for(const k of ['ltn=(0,Q.memo)','utn=(0,Q.memo)','lN=(0,Q.memo)','group/reasoning inline-flex','max-h-60 space-y-2','size-full text-ui-base leading-[1.75]']){console.log(k,b.indexOf(k))}"

# 切片
node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');console.log(b.slice(3322400,3328560).toString())"
node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');console.log(b.slice(3358302,3360900).toString())"

# CSS token / 动画
node -e "const b=require('fs').readFileSync('index-BD2BL4Cc.css');for(const k of ['.animated-gradient-text{','@keyframes gradient-flow','--color-foreground-subtlest:','.leading-[1.75]']){console.log(k,b.indexOf(k))}"

# 文案
node -e "const b=require('fs').readFileSync('IntlProvider-C-26GNLk.js');console.log(b.slice(224700,224950).toString())"
```

---

## 附：与既有文档的关系

- 数据链路、行类型 schema、`row.delta` 追加语义见 `archive/pre-3.16.1-auth/docs/remote-v4/18-remote-v4-功能域-对话渲染与流式更新.md`；本文只补**外观/排版/流式/折叠**。
- 本文所有偏移基于 3.15.3 的 `index-BGa3KiRe.js` / `index-BD2BL4Cc.css`；旧基线（`index-B-ilXaCQ.js`）偏移作废。
