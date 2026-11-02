# 06 · 网页版对话输入区 / 队列 / 待交互卡片 / 模型选择器 取证

> **审计基线**：3.15.3（2026-10-15 快照）
> 主包 `E:\Zcode_android\archive\pre-3.16.1-auth\Reference_web\3.15.3\assets\index-BGa3KiRe.js`（6,592,643 B）
> 样式 `E:\Zcode_android\archive\pre-3.16.1-auth\Reference_web\3.15.3\assets\index-BD2BL4Cc.css`（455,351 B）
> schema `E:\Zcode_android\archive\pre-3.16.1-auth\Reference_web\3.15.3\assets\validation-CqXZOLXO.js`（307,369 B）
> 常量/快捷命令 `E:\Zcode_android\archive\pre-3.16.1-auth\Reference_web\3.15.3\assets\src-AL-_ONNp.js`（82,282 B）
> 文案 `E:\Zcode_android\archive\pre-3.16.1-auth\Reference_web\3.15.3\assets\IntlProvider-C-26GNLk.js`（855,577 B）
> **偏移口径**：UTF-8 字节偏移（`Buffer.indexOf`）。本文所有坐标均按字节重挖。
> **只读取证，未执行任何应用代码，未改任何源码。**

---

## 0. 一句话结论

输入区是一个 **Lexical contenteditable 编辑器**，外壳固定为「**16px 圆角 + 12px 内边距 + 三态边框**」的卡片；工具栏由「**左侧 + 号动作菜单 / Mode 按钮**」和「**右侧模型选择器 / 思考等级 / 发送或停止**」组成。输入区上方依次叠放 **队列卡片**（无折叠、无 autoDrain 开关，只有暂停横幅 + 继续）和 **待交互卡片**（权限 / 用户输入，内联卡片，不是全屏弹窗）。模型选择器是 **按 provider 分组的二级菜单**，当前值不在列表时**不会合成菜单项**，只退化为占位文案。

---

## 1. DOM 位置与层级

### 1.1 时间线底部的 sticky dock

| 结论 | 证据 |
| --- | --- |
| 输入区不在时间线滚动容器内，而是时间线底部的 **sticky dock** | `index-BGa3KiRe.js:3473660` `data-v4-composer-dock`，类 `pointer-events-none z-20 flex w-full justify-center`；非居中空态时为 `sticky bottom-0`（`:3473747`） |
| dock 内容层宽度 = 对话容器宽度，水平/底部各 16px 内边距 | `index-BGa3KiRe.js:3473792` `data-v4-composer-dock-content`，类含 `px-4 pb-4`（`:3474014`） |
| 时间线最后一条内容与 dock 之间只有滚动内容自身的 16px 收尾 | `index-BGa3KiRe.js:3471385` `after:min-h-4` |
| 居中空态（新会话）改为 `mt-3`，且不加 `px-4 pb-4` | `index-BGa3KiRe.js:3473717` `mt-3 shrink-0`；`:3474014` 的 `!le&&px-4 pb-4` 中 `le` 为居中空态 |
| dock 出现/切换有入场动画：`translateY(32px) scale(.96)` → `translateY(0) scale(1)`，260ms `cubic-bezier(.23,1,.32,1)`；退出 180ms、`scale(.97)` | `index-BGa3KiRe.js:3514068-3514181`（`_an=.26`、`van=.18`、`yan=[.23,1,.32,1]`、`rQ/ban/xan`）；`San/Can` 在 `:3514225`、`:3514544` |
| dock 外层是 framer-motion 的 `origin-bottom self-end`，模式切换用 `mode:"sync"` | `index-BGa3KiRe.js:3534046` `data-conversation-bottom-dock-transition-layer` |

### 1.2 dock 内子元素顺序

在同一个 fragment 中，子元素顺序为：

```
配额/额度横幅 → pendingCommand 恢复条 → workspaceHookAdmission 条
→ 队列卡片 (wHt)
→ 待交互卡片 (Msn)
→ 输入区 (KLt / GLt)
→ 建议面板
```

| 元素 | 证据 |
| --- | --- |
| 队列卡片 | `index-BGa3KiRe.js:3680006` `conversation-queue` |
| 待交互卡片 | `index-BGa3KiRe.js:3680211` `conversation-interactions` |
| 输入区 | `index-BGa3KiRe.js:3678328` `conversation-composer` |
| 三者都在 `Can` 包裹的 dock 里 | `index-BGa3KiRe.js:3660193` 起；`Can` 定义 `:3514544` |

> 结论：**队列卡片和待交互卡片都在输入区上方**，不是浮层；它们与输入区同属底部 dock，随 dock 一起出现/隐藏。

---

## 2. Composer 容器

### 2.1 外层 region 与最大宽度

| 项 | 值 | 证据 |
| --- | --- | --- |
| region | `chat-composer-region z-20 w-full shrink-0 @container/composer` | `index-BGa3KiRe.js:2319373` |
| 空态居中时额外 `max-w-2xl` | 672px | `index-BGa3KiRe.js:2319436`；`--container-2xl:42rem` @ `index-BD2BL4Cc.css:9389` |
| dock 内容默认宽度 | `<864px`：`w-full`；`>=864px`：`calc(100% - 6rem)` 且 `max-w-4xl`（896px）；`>=1280px`：`calc(100% - 24rem)` 且 `max-w-6xl`（1152px） | 常量 `BWt/HWt/VWt` @ `index-BGa3KiRe.js:2726932-2726953`；`Qe=WWt(...)` @ `:3454834`；容器值 @ `index-BD2BL4Cc.css:9433/9477` |
| 输入卡片自身 | `w-full`，不设 max-width | `index-BGa3KiRe.js:2319850` |

> 注意：**896/1152px 是 dock 内容层的上限，不是输入卡片单独的上限**。输入卡片填满 dock 内容层，再扣除 `px-4` 的 32px。

### 2.2 圆角、背景、边框、内边距

输入卡片（`EAt` 的 shell）类名逐字：

```
relative flex flex-col gap-3 overflow-hidden rounded-2xl border border-input-border
bg-input p-3 transition-colors
hover:border-input-border-hover
focus-within:!border-input-border-focused focus-within:bg-input-focused
```

证据：`index-BGa3KiRe.js:2093538`。

| 项 | 值 | 证据 |
| --- | --- | --- |
| 圆角 | `rounded-2xl` = 1rem = **16px** | `index-BD2BL4Cc.css:10343` |
| 内边距 | `p-3` = 12px | `index-BGa3KiRe.js:2093538` |
| 子项间距 | `gap-3` = 12px | 同上 |
| 默认背景 | `bg-input` → `--color-input` | 类 @ `:2093538`；token 见下 |
| 默认边框 | `border-input-border` → `--color-input-border` | 同上 |
| 边框过渡 | `transition-colors`，默认 150ms | `index-BD2BL4Cc.css:10917` `--default-transition-duration:.15s` |
| 聚焦 | `focus-within:!border-input-border-focused focus-within:bg-input-focused` | `index-BGa3KiRe.js:2093685` |
| 拖拽/高亮 | `border-brand bg-input-focused ring-1 ring-brand/30` | `index-BGa3KiRe.js:2093769` |
| 输入卡片内部滚动裁切 | `overflow-hidden` | `:2093538` |

### 2.3 三态边框 token（浅色 / 深色）

| 状态 | Token | 浅色 | 深色 | 证据 |
| --- | --- | --- | --- | --- |
| 默认边框 | `--color-input-border` | `var(--color-border)` = 黑 10% | 白 10% | 浅 `index-BD2BL4Cc.css:14276`；深 `:400653`；`--color-border` 浅 `:12844`、深 `:399277` |
| hover 边框 | `--color-input-border-hover` | `var(--color-border-hover)` = 黑 20% | 白 30% | 浅 `:14317`；深 `:400694`；`--color-border-hover` 浅 `:13018`、深 `:399438` |
| focus 边框 | `--color-input-border-focused` | `var(--color-brand)` = `sky-400` | `var(--color-brand)` = `sky-500` | 浅 `:14370`、深 `:400747`；`--color-brand` 浅 `:11831`、深 `:398312` |
| 默认背景 | `--color-input` | `#fff` | `neutral-800` | 浅 `:14197`；深 `:400567` |
| focus 背景 | `--color-input-focused` | `neutral-50` | `neutral-950` | 浅 `:14230`；深 `:400606` |

> 复刻要点：**聚焦只改边框色 + 背景色，不额外加 ring**；ring 只在拖拽/高亮态出现。

### 2.4 与时间线的间距、底部安全区

| 项 | 结论 | 证据 |
| --- | --- | --- |
| 与时间线 | 输入区是 sticky dock，不占滚动流；时间线滚动内容用 `after:min-h-4` 留 16px 收尾 | `index-BGa3KiRe.js:3471385` |
| dock 自身 | 水平 16px、底部 16px；没有顶部 padding | `index-BGa3KiRe.js:3474014` |
| 居中空态 | `mt-3`（12px），且不加 `px-4 pb-4` | `index-BGa3KiRe.js:3473717` |
| 底部安全区 | **没有实现**：主包 `safe-area` 0 命中；CSS 唯一相关工具类是 `bottom-[calc(1rem+env(safe-area-inset-bottom))]`，未被 composer 使用 | `index-BD2BL4Cc.css:27997`；composer 类 @ `index-BGa3KiRe.js:3474014` |
| 队列与输入区重叠 | 队列卡片 `-mb-7 pb-7`，即向上收 28px、底部补 28px，让输入卡片压住队列卡片下沿 | `index-BGa3KiRe.js:2637871` |

### 2.5 Highspeed 变体（同结构，覆盖外观）

`chat-composer-input-surface` 只做 `border-color: transparent`；Highspeed 时由 `.highspeed-composer-surface` 覆盖为 16px 圆角、1.04s 边框/阴影过渡、透明边框，并按 `data-phase` 切换 glow。证据：`index-BD2BL4Cc.css:378186`、`:378234`；phase 规则 `:378477` 起。

---

## 3. 工具栏

### 3.1 工具栏骨架

| 项 | 类名 | 证据 |
| --- | --- | --- |
| 工具栏 | `group/toolbar flex items-end gap-3` | `index-BGa3KiRe.js:2094468` |
| 左侧区 | `flex min-w-0 flex-1 items-center`，`data-composer-leading-actions` | `:2087955` |
| 左侧内容 | `flex shrink-0 items-center gap-1`，`data-composer-leading-content` | `:2088008` |
| 右侧区 | `ml-auto flex shrink-0 items-center justify-end gap-1.5`，`data-composer-trailing-actions` | `:2088268` |

### 3.2 左侧：附件/上下文按钮（`+`）

| 项 | 结论 | 证据 |
| --- | --- | --- |
| 触发按钮 | 不是单独的「回形针」按钮，而是 **`+` 动作菜单**：ghost、`icon-md`、`rounded-lg`、图标 `plus` | `index-BGa3KiRe.js:2083089` `SAt`；触发按钮 @ `:2084900` 区；图标 `Pl=plus` @ `:119850` |
| aria / 文案 | `chat.composer.actionMenu` = 「添加上下文」 | `index-BGa3KiRe.js:2091576`；文案 `IntlProvider-C-26GNLk.js:357940` |
| 菜单方向 | `side:"top"`、`align:"start"`、宽度等于触发按钮宽度、`max-w-[calc(100vw-1rem)]` | `index-BGa3KiRe.js:2084750` 区 |
| 菜单分区 | `add`（附件/目标/工作流）、`plugins`、`files`、`sessions`；`add` 为空时整体不显示 | `index-BGa3KiRe.js:2085000-2085600` 区 |
| 附件项 | 回形针 `Ol=paperclip`，`data-testid` 为 `chat-attachment-menu-item` | 图标 @ `:117331`；test id @ `src-AL-_ONNp.js:28976` 区（`chat-attachment-button`/`chat-attachment-menu-item`） |
| 拖拽提示 | `chat.attachments.dragHint` = 「松开以添加附件」；工作区文件为「松开以引用此文件或目录」 | `IntlProvider-C-26GNLk.js:288324`；`index-BGa3KiRe.js:3682131` |
| 最大附件数 | `chat.attachments.maxFiles` = 「最多只能添加 {count} 个附件」 | `IntlProvider-C-26GNLk.js:355004` |

> 复刻要点：**工具栏没有常驻附件按钮**；附件入口在 `+` 菜单第一项。若 Android 需要常驻回形针，属于产品差异，不是网页原样。

### 3.3 右侧：模型选择器

| 项 | 结论 | 证据 |
| --- | --- | --- |
| 位置 | 右侧区第一个控件（`Yn` 的模型 span） | `index-BGa3KiRe.js:2317187`；`submitControl:Yn` @ `:2321007` |
| 触发按钮 | ghost、`default`、`w-fit justify-between gap-1 rounded-lg pl-2 pr-1.5 text-ui-base whitespace-nowrap` | `index-BGa3KiRe.js:2168094` |
| 当前值展示 | `KV` 标签：`providerPrefix`（单独类 `composer-provider-prefix`）+ `modelLabel`；窄容器隐藏文字只留图标 | `index-BGa3KiRe.js:2287833`、`:2288870`；`data-model-current-value` @ `:2168018` |
| 右侧指示 | `chevron-down`，`size-3.5 text-foreground-subtle` | `index-BGa3KiRe.js:2168570`；`nc=chevron-down` @ `:88597` |
| 加载态 | 转圈 `tl=loader`，`size-3.5 animate-spin` | `index-BGa3KiRe.js:2168473`；图标 @ `:108200` |
| 下拉方向 | `side:"top"`、`align:"start"` | `index-BGa3KiRe.js:2164239`（`contentSide` 默认 `top`） |
| 下拉宽度 | composer 场景 `w-48 max-h-72 overflow-y-auto`（192px 宽 / 288px 高） | `index-BGa3KiRe.js:2168895` |
| 分组结构 | 按 provider 分组：provider 头 + 子菜单；每组 `max-h-72`；也支持 `directItems` 平铺 | `index-BGa3KiRe.js:2167284`、`:2169693` |
| 选中项 | `data-model-option-selected="true"`，右侧 `check` | `index-BGa3KiRe.js:2164885`；`tc=check` @ `:88531` |
| 锁定项 | `data-model-option-locked="true"`，禁用 + tooltip（运行中锁定提示） | `index-BGa3KiRe.js:2164842`；提示 `chat.toolbar.modelSwitch.lockedByRunningTask` |
| 底部动作 | sticky footer，`data-model-footer-action`，可含「管理模型」 | `index-BGa3KiRe.js:2170337`；管理模型只在非 web-remote 出现（`pIt` @ `:2245554`） |
| 触发按钮 data | `data-model-current-value`、`data-testid=chat-model-select-trigger` | `index-BGa3KiRe.js:2168018`；`src-AL-_ONNp.js:31030` |

### 3.4 右侧：思考等级（Effort）

| 项 | 结论 | 证据 |
| --- | --- | --- |
| 位置 | 模型选择器之后、发送/停止之前 | `index-BGa3KiRe.js:2289044`（`je` 分支） |
| 图标 | `brain`，`size-4 text-current` | `index-BGa3KiRe.js:2248979`；`Xs=brain` @ `:85808` |
| 进度条 | 竖向 `w-1 self-stretch rounded-full bg-current/10`，内部高度按档位百分比，`transition-[height] duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)]` | `index-BGa3KiRe.js:2249050` |
| 触发按钮 | ghost、`default`、`gap-1 rounded-lg px-1.5 py-1.5 text-ui-base`，`h-7` | `index-BGa3KiRe.js:2250433` |
| 下拉 | Radix Select，`position:"popper"`、`side:"top"`、`align:"start"`、`sideOffset:4` | `index-BGa3KiRe.js:2243388` |
| 选项文本 | `first-letter:uppercase`；按 `chat.toolbar.thoughtLevel.value.*` 本地化 | `index-BGa3KiRe.js:2243740`；`cIt` @ `:2246868` |
| 单档位 | 只有一个选项且当前值命中时，渲染静态 span（`data-thought-level-fixed="true"`），不弹菜单 | `index-BGa3KiRe.js:2249878` |
| 非 select | 渲染普通按钮，点击循环档位 | `index-BGa3KiRe.js:2251100` 区 |
| 当前值非法 | 默认不显示非法值；`showInvalidCurrentValue` 为真时才显示原始值 | `index-BGa3KiRe.js:2247200` 区 |
| 空值占位 | `chat.toolbar.thoughtLevel.placeholder` | `index-BGa3KiRe.js:2247300` 区 |

### 3.5 左侧：Mode 选择器

| 项 | 结论 | 证据 |
| --- | --- | --- |
| 位置 | 左侧 leading actions（`Xn` 的第一个子项） | `index-BGa3KiRe.js:2318742`；`bLt` @ `:2274844` |
| 触发按钮 | ghost、`sm`、`h-7 gap-1 rounded-lg px-2 text-ui-base`；窄容器 `data-composer-compact=true` 时 `w-7 px-0` | `index-BGa3KiRe.js:2272237` |
| 图标 | 按 mode 切换：`build/default` → `hand`；`plan` → `notepad-text`；`guarded` → `shield-check`；`yolo` → `shield-alert` | `index-BGa3KiRe.js:2242036` `PH`；图标 @ `:103309`、`:115481`、`:126002`、`:125737` |
| 颜色 | `yolo` 用 `text-warning`；`guarded` 用 `text-icon-blue` | `index-BGa3KiRe.js:2272237` |
| 下拉 | `side:"top"`、`sideOffset:4`、`w-64`（256px） | `index-BGa3KiRe.js:2272740`、`:2272764` |
| 首项 | **Plan 复选框**：`min-h-13 items-start gap-3 py-2`，切换 `plan` / `plan-off` | `index-BGa3KiRe.js:2272980`；`min-h-13` @ `:2244832` |
| 其余选项 | `min-h-13`，图标 + 名称 + 描述；`data-testid=chat-mode-select-item` | `index-BGa3KiRe.js:2273506`；`src-AL-_ONNp.js:31193` |
| Mode 值列表 | `build`（变更前确认）、`guarded`（自主模式）、`plan`（计划模式）、`yolo`（完全访问）；`edit` 作为兼容值 | `src-AL-_ONNp.js:49494` 起（`eu` 四项）；`tu` 含 `edit`；中文文案 `IntlProvider-C-26GNLk.js:369928-370500` |
| Plan 标记 | `data-testid=v4-composer-plan-marker`，竖分隔线 + 「计划」+ 删除按钮 | `index-BGa3KiRe.js:2273879` |

> **「Mode/Effort 分组」结论**：模型下拉菜单里**没有** Mode/Effort 分组；Mode、Effort 是工具栏上两个独立控件。模型下拉只按 provider 分组。不要把三者塞进同一个菜单。

### 3.6 发送 / 停止按钮

| 项 | 结论 | 证据 |
| --- | --- | --- |
| 发送按钮 | `type=submit`、`size=icon-md`、`rounded-lg bg-brand text-ui-base text-foreground-inverse hover:bg-brand/80`；图标 `arrow-up`（`Ws`），`size-4` | `index-BGa3KiRe.js:2318403`；`Ws=arrow-up` @ `:83089` |
| 发送中 | 图标替换为加载图标（`aF`），`size-4`；按钮 disabled | `index-BGa3KiRe.js:2318510` |
| 发送 tooltip | 标题为「发送」或「加入队列」；`enterSubmits` 为真时快捷键提示 `Enter` | `index-BGa3KiRe.js:2318300` 区；`chat.send` @ `IntlProvider-C-26GNLk.js:288450`；`chat.queue.enqueue` @ `:292203` |
| 停止按钮 | 仅当 `canStop && 输入为空`；`variant:"secondary"`、`size:"icon-md"`；图标 `square`（`tu`），`fill-current`；tooltip「停止生成」+ `Esc` | `index-BGa3KiRe.js:2317945`、`:2318023`；`tu=square` @ `:128388`；`chat.stop` @ `IntlProvider-C-26GNLk.js:291509` |
| 切换动画 | **没有专门的图标交叉淡入/旋转动画**；发送/停止是条件渲染二选一。唯一的动画是 dock 的模式切换入场 | `index-BGa3KiRe.js:2317945` vs `:2318403`；dock 动画 @ `:3514068` |
| 停止点击 | 走 `conversation.composer.message / stop` 遥测，`resultSource:"optimistic_projection"` | `index-BGa3KiRe.js:2318058` |
| 发送点击 | 走 `conversation.composer.message / send`，先 `recordSendClick`，再提交 | `index-BGa3KiRe.js:2304700` 区 |

### 3.7 窄容器自动折叠

输入区有基于容器查询的自动折叠：`CAt` 先按 `data-composer-collapse-priority` 0→3 依次加 `data-composer-compact`，再把 provider 前缀隐藏（`composerProviderCompact`），最后把模型按钮收成纯图标（`composerModelIcon`）。证据：`index-BGa3KiRe.js:2087915` `CAt`、`:2088938` `wAt`（ResizeObserver + 克隆节点测量）。

---

## 4. 输入框

### 4.1 结构与占位

| 项 | 结论 | 证据 |
| --- | --- | --- |
| 编辑器 | Lexical contenteditable，`data-testid=chat-input`，`aria-placeholder` | `index-BGa3KiRe.js:2078646` `_At`；`:2080066` |
| 占位层 | `pointer-events-none absolute left-0 top-0 text-foreground-subtlest`；compact 模式加 `line-clamp-2` | `index-BGa3KiRe.js:2079880` |
| 占位文案（新任务） | 「向 ZCode 提问，使用 @ 添加上下文，使用 / 选择命令或能力」 | `IntlProvider-C-26GNLk.js:287988` |
| 占位文案（新任务移动端） | 「向 ZCode 提问…」 | `IntlProvider-C-26GNLk.js:288094` |
| 占位文案（有历史、未运行） | 「提出后续修改要求」 | `IntlProvider-C-26GNLk.js:288149` |
| 占位文案（有历史、运行中） | 「继续输入以排队后续修改」 | `IntlProvider-C-26GNLk.js:288207` |
| 占位选择函数 | `ojt({compactNewTask,hasHistoryMessages,isTaskProcessing})` | `index-BGa3KiRe.js:2105740` |

### 4.2 字号、行高、最大高度

| 项 | 值 | 证据 |
| --- | --- | --- |
| 桌面 | `text-ui-base` = 14px，`leading-5` = 20px | `index-BD2BL4Cc.css:396932`（`--ui-font-size:14px`）、`:11189`（`--text-ui-base`）；类 @ `index-BGa3KiRe.js:2067850` |
| 移动端 web-remote | `text-mobile-input-safe` = 16px，`leading-6` = 24px | `index-BD2BL4Cc.css:11436`；类 @ `index-BGa3KiRe.js:2067850` |
| 最小高度 | `min-h-10` = 40px | `index-BGa3KiRe.js:2080066` |
| 最大高度 | `max-h-40` = 160px，超出内部滚动 `overflow-y-auto` | 同上 |
| 最大行数（推导） | 桌面 160/20 = **8 行**；移动安全字号 160/24 ≈ **6.6 行**（实际按像素裁切，不是按 rows 计数） | 由 `:2080066` + `:2067850` 推导 |
| 编辑器宽度 | 外层 `relative flex-1`，占满输入卡片可用宽度 | `index-BGa3KiRe.js:2080231` |

> 代码里**没有 `rows` 属性**；高度完全由 `min-h-10/max-h-40` 控制。不要按「最多 N 行」实现成固定 rows。

### 4.3 Enter / Shift+Enter / 移动端换行

| 场景 | 行为 | 证据 |
| --- | --- | --- |
| 默认快捷键 | `composerSend = ["Enter"]`；`composerInsertNewline = ["Shift+Enter"]` | `src-AL-_ONNp.js:5237`、`:5317` |
| 快捷键解析 | 先匹配 `composerInsertNewline`（返回 newline），再匹配 `composerSend`（返回 send） | `index-BGa3KiRe.js:1959881` `LTt` |
| 桌面 Enter | 发送（`enterSubmits=true`） | `index-BGa3KiRe.js:2071447` `oAt` |
| 桌面 Shift+Enter | 插入换行 | 同上；`RTt` @ `:1960041` |
| Ctrl/Cmd+Enter | 若存在 `onModifiedSubmit`，按 `followupMode` 走「立即发送」或「加入队列」 | `index-BGa3KiRe.js:1758523` `ayt`；`AP` @ `:1763137` |
| 移动端 web-remote | `enterSubmits = !(preferEnterNewline && isMobileTextInputViewport)` → **Enter 只换行**，发送要点按钮 | `index-BGa3KiRe.js:2067589` `Vkt`；`:2297715` |
| IME 组合输入 | `isComposing` 时直接返回，不发送 | `index-BGa3KiRe.js:2071447` |
| 禁用态 | 按下 Enter 直接 `preventDefault`，不插入 | 同上 |

---

## 5. 附件 chips

附件行位于输入编辑器**上方**（`topContent`），分两行：

| 行 | data 属性 | 证据 |
| --- | --- | --- |
| 文件附件 | `data-composer-file-attachments-row="true"` | `index-BGa3KiRe.js:2311467` |
| 上下文附件（评论/网页/引用等） | `data-composer-context-attachments-row="true"` | `index-BGa3KiRe.js:2316469` |

### 5.1 图片缩略图

| 项 | 值 | 证据 |
| --- | --- | --- |
| 外框 | `size-12` = 48×48px，`rounded-lg` = 8px，`bg-surface`，`overflow-hidden` | `index-BGa3KiRe.js:2312354` |
| 外框描边 | `after:absolute after:inset-0 after:rounded-lg after:border after:border-border` | 同上 |
| 内图 | `size-full`，`rounded-none` | `index-BGa3KiRe.js:2312354` 区 |
| 非图片回退 | 文件图标 `size-4` | 同上 |
| 删除按钮 | 绝对定位 `right-0.5 top-0.5`，`size-3.5`（14px）圆形，`bg-primary`，`opacity-0` → hover/focus 显示；无 hover 设备常显 | `index-BGa3KiRe.js:2315893` |

### 5.2 文件 pill

| 项 | 值 | 证据 |
| --- | --- | --- |
| 外框 | `h-12`（48px）、`w-fit max-w-full`、`gap-2`、`rounded-lg`、`border border-border`、`bg-surface`、`p-1.5 pr-6` | `index-BGa3KiRe.js:2312526` |
| 图标容器 | `size-9`（36px），`rounded-md bg-background` | `index-BGa3KiRe.js:2313340` |
| 文件图标 | `size-4` | 同上 |
| 文本区 | `min-w-0 max-w-40 flex-1`（最大 160px） | `index-BGa3KiRe.js:2314553` |
| 文件名 | `text-ui-base font-medium text-foreground`，单行截断 | `index-BGa3KiRe.js:2314624` |
| 类型/大小 | `text-ui-sm font-normal text-foreground-subtle` | `index-BGa3KiRe.js:2314861` |
| 剪贴板文本 | 使用 `max-w-48 text-ui-base text-foreground` 的文本块，不显示文件图标 | `index-BGa3KiRe.js:2314475` |

### 5.3 上传进度

| 状态 | 展示 | 证据 |
| --- | --- | --- |
| `uploading` | 图片：48px 覆盖层 + 圆环进度；文本 `NN%`，7px 字号；文件：文本 `NN%` | `index-BGa3KiRe.js:2313730`、`:2314241`、`:2315068` |
| `failed` | 图片：圆环改 `stroke-destructive`，中心显示 `!`；文件：文本 `text-destructive` + 20px 重试按钮 | `index-BGa3KiRe.js:2313730`、`:2315893` 区；重试图标 `rotate-ccw` @ `:123154` |
| `ready` | 进度层消失；`showComplete` 时短暂显示「上传完成」，300ms 后清除 | `index-BGa3KiRe.js:2110830` |
| `queued` | 文案「等待上传」 | `IntlProvider-C-26GNLk.js:357341` |
| `waitingSession` | 文案「正在等待会话」 | `IntlProvider-C-26GNLk.js:357279` |
| `ready` 文案 | 「上传完成」 | `IntlProvider-C-26GNLk.js:357510` |
| 发送门控 | `hasUnreadyAttachments = attachments.some(uploadStatus !== "ready")`，为真时发送按钮禁用 | `index-BGa3KiRe.js:2122957` |

> 进度环细节：`viewBox 0 0 24 24`、`r=9`、`strokeWidth=2`、`pathLength=100`、`strokeDasharray="${progress} 100"`、整体 `-rotate-90`；背景圆 `stroke-border`。证据：`index-BGa3KiRe.js:2313730-2314241`。

---

## 6. 队列卡片（`queueItems`）

### 6.1 位置与容器

| 项 | 结论 | 证据 |
| --- | --- | --- |
| 位置 | 输入区上方，dock 内；在 workspaceHookAdmission 之后、待交互卡片之前 | `index-BGa3KiRe.js:3680006` |
| 容器类 | `relative z-0 w-full overflow-hidden rounded-t-2xl border border-border bg-surface p-1 backdrop-blur-md` + `-mb-7 pb-7` | `index-BGa3KiRe.js:2637766` |
| 顶部圆角 | `rounded-t-2xl` = 16px（只圆上面两角） | `index-BD2BL4Cc.css:10343` |
| 背景 | `bg-surface`：浅色黑 3% / 深色白 5%，`backdrop-blur-md` = 12px | `index-BD2BL4Cc.css:13541`、`:399932`；blur token `:10833` |
| 层级 | 队列 `z-0`，输入区 `z-20`，输入卡片压住队列下沿 | `index-BGa3KiRe.js:2637766`、`:2319373` |
| 空队列 | `items.length===0` 时整体返回 `null`，不渲染 | `index-BGa3KiRe.js:2636901` `CHt` 首行 |
| data 属性 | `data-queue-count`、`data-queue-auto-drain="true/false"` | `index-BGa3KiRe.js:2637668`、`:2637703` |
| test id | `v4-queue` | `src-AL-_ONNp.js:32501` |

### 6.2 折叠 / 展开

**没有折叠/展开。** `CHt` 只有两个 state：`resumePending` 与 dnd sensors；没有 collapsed/expanded 状态。队列卡片永远展开渲染全部 `items`，高度随条目增长，没有 max-height/内部滚动。证据：`index-BGa3KiRe.js:2636901`（`useState` 只有 `[c,l]=useState(!1)` 与 sortable sensors）、`:2637766`（容器无 max-h/overflow-y）。

### 6.3 autoDrain 开关

**没有 autoDrain 开关。** 页面只消费 `queue.autoDrain` 布尔值：

- `autoDrain=true`：不显示暂停横幅。
- `autoDrain=false`：显示暂停横幅 + 「继续」按钮；点击调用 `setAutoDrain({autoDrain:true})`。
- `pauseReason`：`stopped` / `manual` / `error` → 三种文案。
- 没有把 autoDrain 从 true 切到 false 的 UI。

证据：`index-BGa3KiRe.js:2637871`（`e.autoDrain?null:<横幅>`）、`:2637930`（横幅）、`:2638364`（继续按钮）、`:3661841`（`setAutoDrain` 只传 `true`）；`pauseReason` 文案 `:2638199`。

### 6.4 暂停横幅

| 项 | 类名 / 值 | 证据 |
| --- | --- | --- |
| 横幅 | `mb-1 flex min-h-10 items-center gap-3 rounded-xl border border-border/70 bg-surface-raised px-3 py-2 text-ui-base text-foreground` | `index-BGa3KiRe.js:2637957` |
| 文案 | `stopped`：「由于你中断了当前响应，队列已暂停」；`error`：「由于当前响应出错，队列已暂停（内容未丢失）」；其它：「队列已暂停」 | `IntlProvider-C-26GNLk.js:292684` 起 |
| 继续按钮 | ghost、`sm`、`shrink-0 text-foreground-subtle hover:text-foreground`；aria 为「继续按顺序自动发送队列中的内容」 | `index-BGa3KiRe.js:2638364`；文案 `IntlProvider-C-26GNLk.js:292901` |
| 注意 | `bg-surface-raised` 在 CSS 中 **0 命中**，该 token 未生成，实际背景可能回退为透明 | 类 @ `index-BGa3KiRe.js:2637957`；CSS 全文无 `surface-raised` |

### 6.5 每条队列项的结构

队列项 `li` 类：`relative flex items-center gap-2 rounded-xl px-1.5 py-1 pr-1 transition-colors hover:bg-hover/30`；拖拽中 `bg-hover/40 shadow-sm`；编辑待处理 `opacity-60`。证据：`index-BGa3KiRe.js:2634491`、`:2634592`、`:2634623`。

| 区域 | 内容 | 证据 |
| --- | --- | --- |
| 拖拽手柄 | ghost、`icon-md`、`grip-vertical`，`cursor-grab`，`touch-none`，`data-v4-queue-drag-handle` | `index-BGa3KiRe.js:2634793`；图标 `Vc` @ `:102949` |
| 正文 | 单行/多行截断，`text-ui-base text-foreground`；compact 类型用 `font-mono` 且显示 `/compact` | `index-BGa3KiRe.js:2635205` 区 |
| 来源徽标 | `Gzt` 渲染 botGroupSource | `index-BGa3KiRe.js:2635300` |
| 引用 | `rU` 引用列表 | `index-BGa3KiRe.js:2635476` |
| 附件 | 文件名逗号拼接，`text-ui-sm text-foreground-subtle` | `index-BGa3KiRe.js:2635541` |
| 立即发送 | `secondary`、`default`、`data-icon=inline-start`，图标 `arrow-up-from-line`，文案「立即」；compact 用「立即」(`runNow`) | `index-BGa3KiRe.js:2635692`；`Hs` @ `:82707`；文案 `IntlProvider-C-26GNLk.js:292322` |
| 编辑 | ghost、`icon-md`、`pencil`，tooltip「编辑」 | `index-BGa3KiRe.js:2636238`；`Al=pencil` @ `:118813` |
| 删除 | ghost、`icon-md`、`trash-2`，tooltip「移除待发送消息」 | `index-BGa3KiRe.js:2636589`；`iu=trash-2` @ `:130972` |
| 状态 | 不显示文字状态；用 `data-dispatch-state`（`queued` / `reserved` / `promoting`）与 `data-kind`、`data-edit-pending` 表达 | `index-BGa3KiRe.js:2634334`、`:2634403`、`:2634442` |
| 禁用 | `dispatch.state !== "queued"` 或编辑待处理时，拖拽/立即发送/编辑/删除都 disabled | `index-BGa3KiRe.js:2633602` 区 |
| test id | `v4-queue-item`、`v4-queue-item-send-now`、`v4-queue-item-edit`、`v4-queue-item-delete` | `src-AL-_ONNp.js:32564-32633` |

### 6.6 guide 队列项的分流

`delivery.admitted === "guide"` 的项**不进入队列卡片**，而是被拆到 `pendingGuides` 交给时间线渲染；`visibleQueue` 只保留其余项。证据：`index-BGa3KiRe.js:2638971` `THt`、`:2639024`、`:2639079`。

### 6.7 动画

| 项 | 结论 | 证据 |
| --- | --- | --- |
| 拖拽排序 | dnd-kit sortable；`transform` + `transition` 由 `useSortable` 提供；拖拽中 `shadow-sm`，zIndex 10 | `index-BGa3KiRe.js:2633602` 区 |
| 颜色 | `transition-colors`，默认 150ms | `index-BGa3KiRe.js:2634491`；CSS `:10917` |
| 入场/退场 | 队列卡片本身没有 AnimatePresence/入场动画；只有 dock 的 260ms 动画 | `index-BGa3KiRe.js:2637766`；dock @ `:3514225` |
| 暂停横幅 | 无动画，条件渲染 | `index-BGa3KiRe.js:2637871` |

---

## 7. 待审批 / 待输入卡片

### 7.1 位置与选择规则

| 项 | 结论 | 证据 |
| --- | --- | --- |
| 位置 | dock 内，队列卡片之后、输入区之前；不是全屏弹窗 | `index-BGa3KiRe.js:3680211` |
| 选择 | 优先取 `pendingInteractions` 中 `payload.kind === "permission" || "userInput"` 的第一条；`workspaceHookReview` 单独走另一条链路 | `index-BGa3KiRe.js:3595800`、`:3595900` 区 |
| 卡片宽度 | `w-full shrink-0` | `index-BGa3KiRe.js:3550505` 区 |
| 卡片外壳 | `rounded-2xl border border-border bg-popover shadow-xs`，内层 `p-3` | `index-BGa3KiRe.js:3582959`（权限）；`:3550505`（用户输入） |
| 最大高度 | 用户输入卡片 `max-h-[min(72dvh,42rem)]`，内部 body 滚动 | `index-BGa3KiRe.js:3550578` |
| 背景 | `bg-popover`：浅色白 / 深色 `neutral-800` | `index-BD2BL4Cc.css:14068`、`:400384` |

### 7.2 权限审批卡片（`permission`）

| 区域 | 内容 | 证据 |
| --- | --- | --- |
| 标题 | 「需要权限」+ 来源徽标（子智能体等） | `index-BGa3KiRe.js:3549626`；文案 `IntlProvider-C-26GNLk.js:363109` |
| 正文 | `summary` / `description`，`text-ui-base leading-5 text-foreground` | `index-BGa3KiRe.js:3583416` |
| 特殊正文 | 工作流显示脚本/设置；`switch-mode` 显示旋转图标 + 「实施计划」 | `index-BGa3KiRe.js:3583516`；文案 `IntlProvider-C-26GNLk.js:371328` |
| 选项容器 | `role="listbox"`，`space-y-1` | `index-BGa3KiRe.js:3584139`、`:3584206` |
| 选项排序 | 按 kind 排序：`allowOnce`(0) → `allowAlways`(1) → `fullAccess`(1.5) → `rejectOnce`(2) → `rejectAlways`(3) → `custom`(4) | `index-BGa3KiRe.js:3555556` `gon`、`:3555710` `_on` |
| 选项行 | `flex w-full items-center gap-3 rounded-xl px-3 py-2 text-left outline-none transition-colors`；选中 `bg-selected`，hover `hover:bg-hover`；序号 `1.` `2.`；`data-permission-option-kind` | `index-BGa3KiRe.js:3584980` |
| 选项标签 | `chat.permission.approve`「允许」/ `approveAlways`「始终允许」/ `deny`「拒绝」/ `denyAlways`「始终拒绝」；可带说明 | `IntlProvider-C-26GNLk.js:363198`、`:363233`、`:363926`、`:363958` |
| 自由输入 | 选项列表末尾追加一行 `textarea`（`rows:1`），`data-permission-feedback-option`；placeholder「告诉模型接下来应该怎么做...」 | `index-BGa3KiRe.js:3586681`；文案 `IntlProvider-C-26GNLk.js:3586640` |
| 键盘 | 数字键 1/2/3 选择；Tab/上下切换；Enter 确认；Esc 关闭 | `index-BGa3KiRe.js:3581600` 区 |
| 底部 | 键盘提示「使用 Tab / 上下键选择，回车确认」+ 品牌色确认按钮 | `index-BGa3KiRe.js:3587568`；文案 `IntlProvider-C-26GNLk.js:364041` |
| 超时/自动确认 | **没有**。`ssn` 不接收 `autoResolution`，权限卡片没有倒计时/自动确认提示 | `index-BGa3KiRe.js:3579711` `ssn` 参数表 |

### 7.3 用户输入卡片（`userInput`）

| 区域 | 内容 | 证据 |
| --- | --- | --- |
| 标题 | 优先 `header`；无 header 用「需要确认」；计划审批用「需要权限」+「实施计划」 | `index-BGa3KiRe.js:3549626`、`:3549687`；文案 `IntlProvider-C-26GNLk.js:363109`、`:371328` |
| 正文 | `question` / `prompt`，`whitespace-pre-wrap break-words leading-6`；过长且移动端时 `line-clamp-4` + 展开按钮 | `index-BGa3KiRe.js:3551387` |
| 题号 | `n / m`；上一题/下一题按钮 | `index-BGa3KiRe.js:3551600` 区 |
| 倒计时 | 仅最后 60 秒显示；按钮 `{seconds}秒`，`data-elicitation-countdown-seconds` | `index-BGa3KiRe.js:3539294` `oQ=6e4`、`:3539647` `eon`、`:3550162`；文案 `IntlProvider-C-26GNLk.js:368269` |
| 选项容器 | `role="listbox"` 或 `role="group"`；`space-y-1` | `index-BGa3KiRe.js:3552874`、`:3552959` |
| 选项行 | `flex w-full items-center gap-3 rounded-xl px-3 py-2`；选中 `bg-selected`；多选为 checkbox 方块；序号 `1.` | `index-BGa3KiRe.js:3546894` |
| 自由输入 | 选项列表最后一行是 `textarea`（`rows:1`、`wrap:"soft"`、`max-h-[5lh]`），placeholder「输入你的回答...」 | `index-BGa3KiRe.js:3548000`；文案 `IntlProvider-C-26GNLk.js:3548672` |
| 底部 | 键盘提示「使用 Tab / 上下键选择，回车或空格选中」+「忽略」+「继续/提交」 | `index-BGa3KiRe.js:3554013`；文案 `IntlProvider-C-26GNLk.js:367714`、`:368034`、`:368142` |
| 自动继续 | 自动解析状态 `hiddenGrace` / `visibleCountdown` / `snoozed`；超时自动继续后时间线提示「未回答，已自动继续」 | schema `validation-CqXZOLXO.js:186783`、`:186869`；文案 `IntlProvider-C-26GNLk.js:368853` |
| 无问题 fallback | 无 `questions` 时退化为 **fixed inset-0 z-[100] 全屏遮罩 + max-w-lg 卡片**（旧式弹窗） | `index-BGa3KiRe.js:3593747` `Dsn`、`:3594231` |

> 自动确认提示的准确口径：**用户输入卡片**只在 `autoResolution` 的 `visibleCountdown` 阶段、且剩余 < 60s 时显示秒数按钮；**权限卡片没有倒计时**。没有「N 秒后自动允许」这类文字。

---

## 8. 模型选择器的数据来源与「当前值合成」规则

### 8.1 `config` 字段（协议权威）

`validation-CqXZOLXO.js:183097-183300`：

```js
xh = {
  modelSelection: F.optional(),
  provider: S(),
  model: S(),
  thought: S(),
  thoughtLevels: D(S()).default([]),
  followupMode: M(["queue","guide"]),
  mode: S().default("build"),
  planEnabled: T().optional(),
  permissionGrant: O({interactionId: S().min(1)}).optional(),
  planTransition: O({toolCallId: S(), planEnabled: T()}).optional()
}
```

| 字段 | 偏移 | 用途 |
| --- | --- | --- |
| `provider` / `model` | `validation-CqXZOLXO.js:183134` | 当前模型 |
| `thought` | `:183157` | 当前思考等级 |
| `thoughtLevels` | `:183169` | 快照携带的档位（UI 实际以模型目录为准） |
| `followupMode` | `:183202` | `queue` / `guide`，决定 Ctrl+Enter 走队列还是立即 |
| `mode` | `:183236` | 默认 `build` |
| `modelSelection` | `:183106` | 完整选择（providerId/modelId/options） |

FFI 侧同名字段：`docs/FFI.md:548-549`（`config`, `currentModel`, `currentThought`, `currentMode`, `thoughtLevels`, `queue`, `queueItems`, `autoDrain`, `pendingInteractions`）。

### 8.2 数据来源

| 来源 | 结论 | 证据 |
| --- | --- | --- |
| 会话快照 `config` | `re = hLt(l ?? {})`，把 `config` 或 `modelSelection` 归一成 `{modelSelection, provider, model, thought, thoughtLevels, followupMode, mode}` | `index-BGa3KiRe.js:2270558` `hLt` |
| 模型目录 `modelSelectionView` | `lr = Qn.state.status === "ready" ? Qn.state.view : null`，即模型选择服务的当前 view；含 `providers[].models[].config.optionSpecs.reasoningLevel`；状态 `ready/loading/error/unavailable` | `index-BGa3KiRe.js:3634417`（`lr`）；`:2270128` `tU`；`:2283176`（`RH` 分组） |
| provider 分组 | `RH(provider, view, labels)`：每个 provider 一个 group，key `registry-provider:<id>`，label 取 access 徽标（Start Plan/Team/Individual）或 `providerName`/`providerId`；item value 为 `RD(providerId, modelId)` | `index-BGa3KiRe.js:2255195` `RH` |
| 默认 provider | `D = provider ?? "glm"` | `index-BGa3KiRe.js:2279165` |
| 当前值归一 | `Te = provider 在目录中 ? RD(provider, model) : model` | `index-BGa3KiRe.js:2284064` |

### 8.3 「当前值合成选项」规则

**没有合成菜单项。** 规则分四步：

1. **归一化当前值**：`Te = !re?.model ? "" : view.providers.some(p => p.providerId === re.provider) ? RD(re.provider, re.model) : re.model`。
   - 证据：`index-BGa3KiRe.js:2284064`（`Te` 的 useMemo）。
2. **判定是否命中菜单**：`hIt(Te, groups, canManage, manageLabel)`：
   - `Te.toLowerCase() === "<synthetic>"` → `{value: undefined, placeholder: undefined}`；
   - 命中任一 group 的 item → `{value: Te, placeholder: undefined}`；
   - 未命中：
     - `groups.length === 0 && canManage` → `placeholder = 管理模型文案`；
     - 否则 → 无 value、无 placeholder。
   - 说明：composer 的实际调用是 `hIt(Te, xe, Ce, we)`（4 个参数），第 5 个参数 `i` 未传；因此 `allowUnavailableCustomModelPlaceholder` / `allowUnavailableModelPlaceholder` 两条分支在当前页面**不生效**。`canManage` 由 `pIt(Se)` 决定，web-remote 下恒为 `false`。
   - 证据：`index-BGa3KiRe.js:2253701` `hIt`；`mIt` @ `:2245627`；调用 @ `:2284194`；`pIt` @ `:2245554`。
3. **触发按钮标签**：`uyt({modelGroups, normalizedValue, fallbackLabel, providerId, providerName})`：
   - 未命中 → `fullLabel = fallbackLabel`（管理模型文案或 `chat.toolbar.model.label`），`modelLabel = fallbackLabel`；
   - 命中 → `fullLabel = providerName/modelName`（默认 provider `glm` 时省略前缀），`providerPrefix = "provider/"`，`modelLabel = modelName`。
   - 证据：`index-BGa3KiRe.js:1770155` `uyt`；`lyt` @ `:1769944`；`PP` @ `:1769865`。
4. **隐藏诊断 span**：输入区渲染一个 `hidden` span，带 `data-source`（有配置时 `composer`）、`data-provider`、`data-model`、`data-thought`、`data-thought-levels`、`data-mode`、`data-plan-enabled`、`data-usage-used/max`。证据：`index-BGa3KiRe.js:2286518-2286849`。

### 8.4 思考等级（Effort）规则

- 档位来自 **模型目录**，不是 `config.thoughtLevels`：
  `tU` 读取 `providers[].models[].config.optionSpecs.reasoningLevel`，若不存在或 `values` 为空 → 不显示控件；`currentValue` 只在 `values.includes(thought)` 时保留，否则空串。
  证据：`index-BGa3KiRe.js:2270128` `tU`；`_Lt` @ `:2270865`。
- 工具栏 `je` 的 `options` 就是上述 `values`；`currentValue` 用 `_Lt` 校验。
  证据：`index-BGa3KiRe.js:2285396`（`je` 的 useMemo）、`:2285331`（`Ae`）。
- 单档位 → 静态文本；多档位 → select；非 select → 点击循环。
  证据：`index-BGa3KiRe.js:2248979-2251499`。

### 8.5 Mode 规则

- 当前值：`config.mode ?? "build"`。
- 菜单选项：`Fa()` 去掉 `plan`；`plan` 由首项复选框单独控制（`planEnabled`）。
- 兼容值 `edit`：若 `config.mode === "edit"` 且不在列表中，用 `{id:"edit", name:"Edit"}` 兜底。
- 证据：`index-BGa3KiRe.js:2270968` `yLt`；`src-AL-_ONNp.js:49494`（build/guarded/plan/yolo）；`tu` 含 `edit`。

---

## 9. 输入区完整状态矩阵

### 9.1 状态变量（代码口径）

| 变量 | 含义 | 证据 |
| --- | --- | --- |
| `m` | 外部 disabled：`wee`（连接中/无快照）、`xi`（草稿 runtime rebuilding）、`Ui`（队列项编辑待处理）、`pa.state.blocksSubmit`（额度阻止提交） | `index-BGa3KiRe.js:3677381`；`wee` @ `:3667677`、`xi` @ `:3646799`、`Ui` @ `:3667733` |
| `mn` | `inputRouting.mode ?? "startNow"` | `index-BGa3KiRe.js:2296192` |
| `gn` | `control.canStop` | `index-BGa3KiRe.js:2304382` |
| `vn` | 有可发送内容（trim 文本 / 附件 / 上下文 / 引用） | `index-BGa3KiRe.js:2304416` |
| `bn` | `draftMode || (snapshot !== null && mn !== "reject")` | `index-BGa3KiRe.js:2304579` |
| `xn` | `!hasUnreadyAttachments` | `index-BGa3KiRe.js:2304609` |
| `Sn` | 可发送：`!m && !be && vn && bn && xn && submissionReady` | `index-BGa3KiRe.js:2304638` |
| `Cn` | 显示停止：`gn && !vn` | `index-BGa3KiRe.js:2304664` |
| `be` | 本次发送提交中 | `index-BGa3KiRe.js:2296758` |

### 9.2 状态矩阵

| 状态 | 输入框 | 主按钮 | 次按钮/提示 | 可发送条件 | 证据 |
| --- | --- | --- | --- | --- | --- |
| **空闲/草稿**（无会话） | 可编辑，占位「向 ZCode 提问，使用 @ 添加上下文，使用 / 选择命令或能力」 | 发送（arrow-up） | 无停止 | 有内容且 `bn=true` | `index-BGa3KiRe.js:2105740`、`:2318403` |
| **运行中、输入为空** | 可编辑，占位「继续输入以排队后续修改」 | **停止**（square，secondary） | tooltip「停止生成」+ Esc | 不显示发送 | `index-BGa3KiRe.js:2317945`、`:2304664` |
| **运行中、有输入** | 可编辑 | 发送；aria 可能是「加入队列」 | Ctrl/Cmd+Enter 提示「立即发送」或「加入队列」 | `Sn=true` | `index-BGa3KiRe.js:2310062`、`:1758523` |
| **发送提交中** | 可编辑但发送按钮禁用 | 加载图标（`aF`） | 停止按钮若 `canStop` 仍显示 | `be=true` → `Sn=false` | `index-BGa3KiRe.js:2318510`、`:2320582` |
| **附件上传中** | 可编辑 | 发送禁用 | 附件 chip 显示进度/失败重试 | `xn=false` → `Sn=false`；`prepareForSend` 返回 null 会中止 | `index-BGa3KiRe.js:2304609`、`:2122957`、`:2123352` |
| **停止中** | 可编辑 | 停止按钮保持到 `canStop` 变 false | 无独立 spinner；`control.stopState` 页面不读 | `Cn=gn&&!vn` | `index-BGa3KiRe.js:2304382`；schema `validation-CqXZOLXO.js:184941`；主包 `stopState` 0 命中 |
| **连接中/无快照** | 禁用 | 发送禁用 | 输入卡片仍显示 | `m=true` | `index-BGa3KiRe.js:3667677` |
| **草稿 runtime 重建** | 禁用 | 发送禁用 | — | `m=true`（`xi`） | `index-BGa3KiRe.js:3646799` |
| **队列项编辑待处理** | 禁用 | 发送禁用 | — | `m=true`（`Ui`） | `index-BGa3KiRe.js:3667733` |
| **额度阻止提交** | 禁用 | 发送禁用 | 额度横幅 | `m=true`（`pa.state.blocksSubmit`） | `index-BGa3KiRe.js:3677381` |
| **`inputRouting.mode=reject`** | 禁用 | 发送禁用 | — | `bn=false`；输入 `disabled:m||mn==="reject"` | `index-BGa3KiRe.js:2304579`、`:2320556` |
| **`followupMode=guide`** | 可编辑 | 发送（label 由 `inputRouting.mode` 决定） | Ctrl/Cmd+Enter → 加入队列 | `AP("guide")="queue"` | `index-BGa3KiRe.js:1763137`、`:2310062` |

### 9.3 发送门控细节

- `allowSubmitWhenEmpty = 附件 || 上下文 || 引用`，所以「只有附件/引用、没有文本」也可发送。证据：`index-BGa3KiRe.js:2320628`。
- 发送前 `prepareForSend()` 若返回 `null`（附件未就绪）→ 直接中止，不提交。证据：`index-BGa3KiRe.js:2123352`、`:2306258`。
- 发送成功后：清空编辑器、`clearAttachments`、清空上下文/引用；`startNow` 模式立即清空，队列模式保留草稿直到被服务端接收。证据：`index-BGa3KiRe.js:2307815` 区。
- 发送被 `blocked` / `confirmationRequired` 时：恢复草稿、清空临时状态；`confirmationRequired` 弹「发送消息？」确认框（`chat.queue.sendConfirm.*`）。证据：`index-BGa3KiRe.js:2307309`、`:2307546`、`:2322351`。

---

## 10. 复刻到 Android 时的关键差异清单

1. **不要给 composer 加底部安全区**：网页原样没有 `env(safe-area-inset-bottom)`，只有 16px `pb-4`。证据：`index-BGa3KiRe.js:3474014`；CSS 唯一安全区工具类 `:27997` 未被使用。
2. **队列没有折叠/展开**，也没有 autoDrain 开关；只有「暂停横幅 + 继续」。证据：`index-BGa3KiRe.js:2636901`、`:2637871`、`:3661841`。
3. **模型下拉没有 Mode/Effort 分组**；Mode、Effort 是独立控件。证据：`index-BGa3KiRe.js:2163454`（模型菜单）、`:2270968`（Mode）、`:2246988`（Thought）。
4. **当前模型不在目录时不要合成菜单项**，只显示占位文案；`data-model-current-value` 为空。证据：`index-BGa3KiRe.js:2253701`、`:1770155`。
5. **停止态没有单独 UI**：只认 `control.canStop`；`stopState` 协议字段页面不消费。证据：`index-BGa3KiRe.js:2304382`；schema `validation-CqXZOLXO.js:184941`。
6. **附件入口在 `+` 菜单里**，不是常驻回形针。证据：`index-BGa3KiRe.js:2083089`。
7. **队列卡片用 `-mb-7 pb-7` 钻到输入区下面**；Android 若不做重叠，视觉上会多出 28px 空隙。证据：`index-BGa3KiRe.js:2637871`。
8. **暂停横幅的 `bg-surface-raised` 没有对应 CSS token**，网页实际可能是透明背景；复刻时应显式给一个可用的 raised 背景。证据：类 @ `index-BGa3KiRe.js:2637957`；CSS 0 命中。
9. **移动端 web-remote 的 Enter 是换行**，发送靠按钮。证据：`index-BGa3KiRe.js:2067589`、`:2297715` 区。
10. **队列 guide 项不在队列卡片里**，走时间线 `pendingGuides`。证据：`index-BGa3KiRe.js:2638971`。

---

## 11. 证据索引（按主题）

### 11.1 主包 `index-BGa3KiRe.js`

| 主题 | 偏移 |
| --- | --- |
| 输入卡片 shell / 三态 | 2093538、2093685、2093769 |
| 工具栏 | 2087955、2088008、2088268、2094468 |
| `+` 动作菜单 | 2083089、2091576 |
| 输入编辑器 / 占位 / 高度 | 2078646、2080066、2067850 |
| Enter / 快捷键 | 2071447、1959881、1960041 |
| 附件 chips | 2311467、2316469、2312354、2312526、2313340、2313730、2314241、2314553、2315068、2315893 |
| 上传状态 | 2110830、2111277、2122957、2123352 |
| 队列容器 | 2637766、2637871、2637668、2637703 |
| 队列项 | 2633602、2634334、2634403、2634442、2634491、2634592、2634793、2635205、2635541、2635692、2636238、2636589 |
| 队列暂停/继续 | 2637957、2638199、2638364、3661841 |
| guide 分流 | 2638971、2639024、2639079 |
| 待交互卡片位置 | 3680006、3680211、3678328 |
| 权限卡片 | 3579711、3582959、3584206、3584980、3586681、3587568 |
| 用户输入卡片 | 3542571、3546894、3549626、3550162、3550505、3550578、3554013、3554432 |
| 自动解析/倒计时 | 3539294、3539647、3550344 |
| 用户输入 fallback | 3593747、3594231 |
| 模型菜单 | 2163454、2164842、2164885、2168018、2168895、2170337 |
| 模型数据/合成 | 2270128、2270558、2270781、2270865、2253701、2245627、1769944、1770155、2255195 |
| Mode 选择器 | 2270968、2274844、2242036 |
| Thought 选择器 | 2246988、2246669、2246868、2251732 |
| 状态变量 | 2304382、2304416、2304579、2304609、2304638、2304664 |
| 发送/停止 | 2317945、2318058、2318403、2318510 |
| dock 宽度/动画 | 2726932、2726953、3454834、3473660、3473717、3473792、3474014、3514068、3514225、3514544 |
| 时间线收尾 | 3471385 |

### 11.2 `index-BD2BL4Cc.css`

| 主题 | 偏移 |
| --- | --- |
| 圆角 / 字号 | 10343、396932、11189、11436 |
| 容器宽度 | 9389、9433、9477 |
| 浅色 input 三态 | 14197、14230、14276、14317、14370 |
| 浅色 border/brand/surface/popover | 12844、13018、11831、13541、14068 |
| 深色 input 三态 | 400567、400606、400653、400694、400747 |
| 深色 border/brand/surface/popover | 399277、399438、398312、399932、400384 |
| Highspeed | 378186、378234、378477 |
| 安全区工具类 | 27997 |

### 11.3 `validation-CqXZOLXO.js`

| 主题 | 偏移 |
| --- | --- |
| config 字段 | 183097、183134、183157、183169、183202、183236 |
| queue schema | 185672、185801 |
| queue item schema | 163295 |
| pendingInteractions | 189378 |
| pending 形状 | 186783、186869、186940 |
| control / stopState | 184903、184929、184941 |
| inputRouting | 185272、189206 |

### 11.4 `IntlProvider-C-26GNLk.js` / `src-AL-_ONNp.js`

| 主题 | 偏移 |
| --- | --- |
| 占位文案 | 287988、288094、288149、288207 |
| 队列/停止/发送 | 292203、292322、292684、292901、291509、288450 |
| 权限/用户输入 | 363109、364041、367714、368034、368142、368269 |
| 上传文案 | 355004、357389、357940 |
| Mode 文案 | 369928-370500 |
| 快捷键默认值 | `src-AL-_ONNp.js:5237`、`:5317` |
| Mode 值列表 | `src-AL-_ONNp.js:49494` 起 |
| test id | `src-AL-_ONNp.js:28976`、`:29035`、`:31030`、`:31118`、`:31193`、`:32148`、`:32501`、`:32515`、`:32543`、`:32564`、`:32583`、`:32609`、`:32633`、`:32788` |

---

## 12. 未验证 / 不可从产物确认

- `Fa()` 的最终返回顺序与 `edit` 是否出现在 composer 菜单：主包只看到 `Fa()` 调用与 `l.filter(e=>e.id!=="plan")`，`Fa` 的实现被别名/跨 chunk 切分，未逐行确认；但 `src-AL-_ONNp.js:49494` 的四项顺序可作强证据。
- `--color-surface-raised` 未在 CSS 生成，无法确认暂停横幅的最终计算背景色（取决于 Tailwind 构建是否在别处注入）。
- `control.stopState` 页面不消费，无法确认「停止中」是否有服务端驱动的额外视觉状态；当前构建没有。
- 附件上传进度条的实际动画帧率未实测；只确认 CSS 类与 SVG `strokeDasharray` 逻辑。
- 移动端真实安全区表现未在真机复核；产物中 composer 没有安全区处理。

---

*本报告只做静态取证，所有偏移均可在对应文件中用 `Buffer.indexOf` 复现。*
