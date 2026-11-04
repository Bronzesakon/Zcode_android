# 05 · ZCode 3.17.1 输入区 / 附件 / 浮层 / 设置页 全量样式逆向

> **取证基线**：ZCode 3.17.1 官方网页打包产物
> - 主包：`E:/Zcode_android/Reference_web/3.17.1/assets/index-Dwhx77cK.js`（6,986,617 B）
> - 样式：`E:/Zcode_android/Reference_web/3.17.1/assets/index-BgVjt5KE.css`（485,230 B）
> - 文案：`assets/IntlProvider-CneXitqp.js`（858,773 B）
> - 快捷键默认值：`assets/src-BSClmRM9.js`
> - 按钮基类：`assets/button-P7AG3MDX.js`
> - Toast 宿主：`assets/pptxElementReference-C5CWcv9D.js`（共享 chunk，命名与内容无关）
>
> **偏移口径**：文件按 UTF-8 解码后的**字符偏移**（Python `str` 索引），不是字节偏移。
> **只读取证**：未执行任何应用代码，未修改任何源码；本次取证用的临时探针脚本已删除（`tools/` 下其余文件非本次生成）。
> **单位换算**：网页 `px` 与 Compose `dp` 按 1:1 映射（Android `dp` 即密度无关像素）。

---

## 0. 一句话结论

3.17.1 的输入区是「**0.5px 边框 + 16px 圆角 + 12px 内边距 + 三态边框**」的卡片，外层套一层 `chat-composer-input-surface`；工具栏为「**左：`+` 动作菜单 / Mode / 计划标记**」+「**右：合并式模型菜单（模型 + 思考滑杆）/ 停止或发送（圆形）**」。3.17.1 相对 3.15.3 的**结构性变化**有四处：

1. 输入卡片边框从 `1px` 改为 **`0.5px`**，并新增 `shadow-(--shadow-composer-soft)`（仅 zai-light 有值，其余主题为 `0 0 #0000`）。
2. 发送/停止按钮从 `rounded-lg` 改为 **`rounded-full`**（圆形）。
3. 模型选择器改为 **合并菜单**（`w-72`）：菜单内同时包含「模型列表（`max-h-60`）」与「推理强度滑杆（h-8）」，不再只有 `w-48 max-h-72` 的模型列表。
4. dock 内容层水平内边距从 `px-4` 改为 **`px-2`**，并新增 `transition-[width,max-width,transform] duration-150`。

浮层体系是 Radix 语义 + 自定义皮肤：菜单统一 `z-[60]`、`rounded-lg`、`bg-menu`、`border-popover-border`、`p-1`、`shadow-md`；对话框 `z-50`、`rounded-2xl`、`bg-popover`；Toast 独立宿主 `z-[9999]`、`rounded-2xl`、`bg-toast/60 + backdrop-blur-xl`。

---

## 1. 全局度量与设计令牌

### 1.1 尺寸基准

| 项 | 值 | 证据 |
| --- | --- | --- |
| 间距单位 `--spacing` | **0.25rem = 4px** | CSS `index-BgVjt5KE.css:12096` |
| 字号基准 `--ui-font-size` | **14px**（`:root`） | CSS `:417974` 区块 |
| 圆角 `--radius-xs/sm/md/lg/xl/2xl/3xl` | **2 / 4 / 6 / 8 / 12 / 16 / 24 px** | CSS `:12096` 之后 |
| `rounded-full` | `calc(infinity * 1px)`（Tailwind v4） | 由类名语义确认；CSS 未见显式 token |

### 1.2 字体层级（`--ui-font-size: 14px` 时）

| Token | 计算式 | 实际 px | 行高 | 用途 | 证据 |
| --- | --- | --- | --- | --- | --- |
| `text-ui-settings-title-lg` | +16 | **30** | 1.2 | 设置页标题（`lg:` 断点） | CSS `:13978`、`:299541` |
| `text-ui-settings-title` | +10 | **24** | 4/3 ≈ 1.333 | 设置页标题（默认） | CSS `:152674` |
| `text-ui-xl` | +4 | **18** | — | 少量强调 | CSS `:14000` 区块 |
| `text-ui-lg` | +2 | **16** | — | 对话框标题、BottomSheet 标题 | CSS `:153781` |
| `text-ui-base` | = | **14** | 由 `leading-*` 指定 | 正文/输入/按钮 | CSS `:153687` |
| `text-ui-caption` | −1 | **13** | — | 建议提示词按钮 | CSS `:153687` 区块 |
| `text-ui-sm` | −2 | **12** | — | 说明文字、tooltip、快捷键 | CSS `:153821` |
| `text-ui-xs` | −4 | **10** | — | 徽标、kbd、队列 meta | CSS `:153901` |
| `text-ui-2xs` | −5 | **9** | — | 极少 | CSS `:14000` 区块 |
| `text-mobile-input-safe` | 固定 | **16** | 24（配 `leading-6`） | 移动端输入（防 iOS 聚焦缩放） | CSS `:11436` 区块；JS `:3130337` |

常用行高：`leading-5` = 20px，`leading-6` = 24px，`leading-relaxed` = 1.625。

### 1.3 颜色令牌（四主题）

主题分组：`:root`（基础浅色）→ `.dark`（基础深色）→ `.theme-zai-light` → `.theme-zai-dark`。产品默认使用 **zai** 两套（`settings.appearance` 的主题选项为 `system / zai-dark / zai-light`，JS `:6382422`）。

| Token | :root 浅 | .dark | zai-light | zai-dark | 用途 |
| --- | --- | --- | --- | --- | --- |
| `--color-background` | `neutral-50` | `neutral-900` | **#f8f8f8** | **#161616** | 页面底 |
| `--color-foreground` | `neutral-700` | `neutral-200` | `neutral-800` | `neutral-300` | 主文字 |
| `--color-foreground-subtle` | 700/60% | 200/60% | #26262699 | #d4d4d499 | 次要文字 |
| `--color-foreground-subtlest` | 700/40% | 200/30% | #26262666 | #d4d4d44d | 占位/最弱 |
| `--color-border` | #0a0a0a1a | #fafafa1a | **#0d0d0d1a** | **#ffffff1a** | 通用边框 |
| `--color-border-hover` | #0a0a0a33 | #fafafa4d | **#0d0d0d26** | **#ffffff26** | 边框 hover |
| `--color-hover` | `neutral-200` | 白 10% | #0d0d0d0d | #ffffff0d | hover 底 |
| `--color-selected` | #0a0a0a1a | 白 10% | #0d0d0d0d | #ffffff1a | 选中底 |
| `--color-surface` | #0a0a0a08 | 白 5% | #0d0d0d08 | #ffffff0d | 浅色块/队列底 |
| `--color-surface-hover` | #0a0a0a0d | 白 10% | #0d0d0d0d | #ffffff1a | 块 hover |
| `--color-card` | #fff | `neutral-800` | **#fff** | **#2b2b2b** | 卡片 |
| `--color-popover` | #fff | `neutral-800` | **#fff** | **#2b2b2b** | 弹层/对话框 |
| `--color-popover-border` | `--color-border` | `--color-border` | `--color-border` | `--color-border` | 弹层边框 |
| `--color-menu` | #fff | `neutral-950` | **#fff** | **#2b2b2b** | 菜单底 |
| `--color-menu-hover` | `neutral-100` | `neutral-900` | **#f0f0f0** | **#363636** | 菜单项高亮 |
| `--color-input` | #fff | `neutral-800` | **#fff** | **#2b2b2b** | 输入底 |
| `--color-input-border` | `--color-border` | `--color-border` | `--color-border` | `--color-border` | 输入边框 |
| `--color-input-border-hover` | `--color-border-hover` | 同左 | 同左 | 同左 | 输入 hover |
| `--color-input-border-focused` | **`--color-brand`** | **`--color-brand`** | **`--color-border-hover`** | **`--color-border-hover`** | 输入聚焦边框 |
| `--color-input-focused` | `neutral-50` | `neutral-950` | **`--color-input`** | **`--color-input`** | 聚焦底 |
| `--color-tooltip` | `neutral-100` | `neutral-900` | **#f0f0f0** | **#2b2b2b** | tooltip 底 |
| `--color-tooltip-foreground` | `neutral-950` | `neutral-50` | **#0d0d0d** | **#f8f8f8** | tooltip 文字 |
| `--color-tooltip-tag` | `neutral-200` | `neutral-800` | **#e6e6e6** | **#363636** | kbd 底 |
| `--color-tooltip-tag-foreground` | #0a0a0a99 | #fafafa99 | **#5c5c5c** | **#adadad** | kbd 文字 |
| `--color-toast` | #fff | `neutral-900` | **#fff** | **#2b2b2b** | Toast 底 |
| `--color-brand` | `sky-400` | `sky-500` | **#000** | **#fff** | 品牌/发送按钮 |
| `--color-primary` | `neutral-950` | `neutral-50` | **#000** | **#fff** | 主按钮/开关 |
| `--color-primary-foreground` | `neutral-50` | `neutral-950` | **#fff** | **#000** | 主按钮文字 |
| `--color-secondary` | `neutral-300` | `neutral-700` | **#e6e6e6** | **#363636** | 次按钮/停止 |
| `--color-accent` | `sky-50` | sky-950/50% | **#ebf4ff** | **#001d3d** | 高亮底 |
| `--color-destructive` | `red-600` | `red-500` | **#e03131** | **#ff5c5c** | 危险 |
| `--color-warning` | `yellow-600` | `yellow-500` | **#e07b00** | **#ff8a30** | 警告 |
| `--color-success` | `green-600` | `green-500` | **#1e8a3e** | **#46bf72** | 成功/思考滑杆 |
| `--color-panel` | `neutral-100` | `neutral-900` | **#fff** | **#202020** | 侧栏面板 |
| `--color-remote-foreground-muted` | **#595959** | **#b3b3b3** | **#595959** | **#b3b3b3** | 设置侧栏未选中项 |

> **复刻要点（zai 主题）**：聚焦**不改变背景**（`--color-input-focused = --color-input`），只把边框从 `#0d0d0d1a` 提到 `#0d0d0d26`（深色为 `#ffffff1a → #ffffff26`）。基础主题才是「边框变 brand 色 + 背景变 neutral-50/950」。

### 1.4 阴影 / 动效 / 层级

| 项 | 值 | 证据 |
| --- | --- | --- |
| `shadow-xs` | `0 1px 2px 0 #0000000d` | CSS `:174181` 区块 |
| `shadow-sm` | `0 1px 3px 0 #0000001a, 0 1px 2px -1px #0000001a` | CSS token |
| `shadow-md` | `0 4px 6px -1px #0000001a, 0 2px 4px -2px #0000001a` | CSS token |
| `shadow-lg` | `0 10px 15px -3px #0000001a, 0 4px 6px -4px #0000001a` | CSS token |
| `shadow-xl` | `0 20px 25px -5px #0000001a, 0 8px 10px -6px #0000001a` | CSS `:174181` |
| `shadow-2xl` | `0 25px 50px -12px #00000040` | CSS `:171690` |
| `--shadow-composer-soft` | `:root`/`.dark`/`.theme-zai-dark` = **`0 0 #0000`**；`.theme-zai-light` = **`0 1px 3px #0000000a, 0 6px 18px #0000000a`** | CSS `:417974`、`:432992` |
| 菜单动效 | `duration-100`，`fade-in-0` + `zoom-in-95`，方向 slide 2（`0.5rem`） | JS `:972916` |
| 对话框动效 | `duration-100`，fade + zoom-95 | JS `:971166` |
| Toast 动效 | `200ms cubic-bezier(0.77, 0, 0.175, 1)`，位移 + 缩放 | 见 §4.11 |
| dock 宽度过渡 | `transition-[width,max-width,transform] duration-150 ease-out` | JS `:4488160` |
| z-index 体系 | dock/region `z-20`；回到顶部 `z-30`；对话框/AlertDialog/Tooltip/HoverCard `z-50`；菜单/Popover/Select `z-[60]`；Toast `z-[9999]` | 各处 |

---

## 2. Composer（输入区）

### 2.1 DOM 结构树

```
div.chat-composer-region.z-20.w-full.shrink-0.@container/composer   [data-testid=v4-composer]
├─ input[type=file].hidden                                        (附件选择器)
├─ div.mb-6.w-full.shrink-0                                       (错误/重连横幅，可选)
├─ div.mb-2.flex...                                               (会话初始化失败，可选)
└─ div.chat-composer-input-surface.relative.w-full
   ├─ div (highspeed 卡片，可选)
   ├─ div[role=alert].mb-2.rounded-lg...                          (选择数量超限，可选)
   └─ form.relative
      ├─ div.absolute.inset-x-0.bottom-full.z-20                  (弹层挂载锚点)
      └─ div[data-composer-layout=stacked|inline]
         ├─ div.pointer-events-none.absolute.inset-0.z-10...      (拖拽遮罩，可选)
         ├─ div (topContent：附件条)
         │  ├─ div[data-composer-file-attachments-row=true].flex.flex-wrap.gap-2
         │  └─ div[data-composer-context-attachments-row=true].flex.flex-wrap.gap-2
         ├─ div.relative.flex-1
         │  └─ div.relative
         │     ├─ div.pointer-events-none.absolute.left-0.top-0   (placeholder)
         │     └─ div[contenteditable=true].min-h-10.max-h-40.overflow-y-auto
         └─ div.group/toolbar.flex.items-end.gap-3
            ├─ div[data-composer-leading-actions].flex.min-w-0.flex-1.items-center
            │  └─ div[data-composer-leading-content].flex.shrink-0.items-center.gap-1
            │     ├─ span[data-composer-action-menu]              (+ 动作菜单)
            │     ├─ (leadingActions)
            │     └─ span (mode 切换容器)
            └─ div[data-composer-trailing-actions].ml-auto.flex.shrink-0.items-center.justify-end.gap-1.5
               ├─ 取消按钮 (仅 submitting，ghost icon-lg)
               ├─ (betweenCancelAndSubmitAction)
               └─ submitControl = div.flex.min-w-0.items-center.gap-1
                  ├─ 模型/思考合并选择器
                  └─ 停止 (secondary icon-md rounded-full) 或 发送 (submit icon-md rounded-full bg-brand)
```

证据：region `JS:3418165`；surface `JS:3419441`；form/shell `JS:3154850`、`JS:3158794`；编辑器 `JS:3144161`；工具栏与发送 `JS:3159700`；附件行 `JS:3409487`；模型+停止/发送组 `JS:3412000` 之后。

### 2.2 容器度量

| 项 | 值 | 证据 |
| --- | --- | --- |
| region 类 | `chat-composer-region z-20 w-full shrink-0 @container/composer`；居中空态加 `max-w-2xl`（**672px**） | `JS:3418165` |
| surface 类 | `chat-composer-input-surface relative w-full`；可选 `rounded-2xl bg-surface shadow-xl/5` | `JS:3419441` |
| 表单外壳类（逐字） | `relative flex flex-col gap-3 overflow-hidden rounded-2xl border-[0.5px] border-input-border bg-input p-3 shadow-(--shadow-composer-soft) transition-colors hover:border-input-border-hover focus-within:!border-input-border-focused focus-within:bg-input-focused` | `JS:3158794` |
| 圆角 | **16px**（`rounded-2xl`） | 同上 |
| 内边距 | **12px**（`p-3`） | 同上 |
| 子项间距 | **12px**（`gap-3`） | 同上 |
| 边框 | **0.5px**（`border-[0.5px]`，CSS `:98287`） | 同上 |
| 默认背景/边框 | `--color-input` / `--color-input-border` | §1.3 |
| hover | 边框 → `--color-input-border-hover` | 同上 |
| focus-within | 边框 → `--color-input-border-focused`，底 → `--color-input-focused`；**无 ring** | 同上 |
| 拖拽/高亮 | `border-brand bg-input-focused ring-1 ring-brand/30` + 全屏遮罩 `bg-accent/55 backdrop-blur-sm`，中央胶囊 `rounded-full border border-border bg-accent px-4 py-2 text-ui-base shadow-sm` + 图标 `size-4` | `JS:3158794` 区块 |
| `data-composer-layout=inline` | `flex-row flex-wrap items-end gap-2 p-2`（浮动输入坞变体） | 同上 |
| dock 内容宽度 | `<864px`：`w-full`；`≥864px`：`calc(100% - 6rem)` 且 `max-w-4xl`（896px）；`≥1280px`：`calc(100% - 24rem)` 且 `max-w-6xl`（1152px） | `JS:3744050`（`Nnn/Pnn`） |
| dock 内边距 | **`px-2 pb-4`**（8px / 16px）；居中空态 `mt-3` 且不加 padding | `JS:4488160` |
| dock 定位 | `pointer-events-none z-20 flex w-full justify-center`；非浮动时 `sticky bottom-0`，空态 `mt-3 shrink-0` | `JS:4488160` |

### 2.3 输入区（Lexical contenteditable）

| 项 | 值 | 证据 |
| --- | --- | --- |
| 编辑器类 | `min-h-10 max-h-40 overflow-y-auto {字号} text-foreground outline-none` | `JS:3144161` |
| 最小高度 | **40px** | 同上 |
| 最大高度 | **160px**，超出内部滚动 | 同上 |
| 字号（桌面） | `text-ui-base leading-5` = **14/20** | `JS:3130337`（`eJ`） |
| 字号（移动/防缩放） | `text-mobile-input-safe leading-6` = **16/24** | 同上 |
| 外层容器 | `relative flex-1` | `JS:3144161` |
| 主题 | Lexical `theme.paragraph = "m-0"`（段落无外边距） | `JS:3142693` |
| 可编辑性 | `editable: !disabled` | `JS:3142693` 区块 |
| 禁用态 | 编辑器不可编辑；工具栏按钮 `disabled:opacity-50 disabled:pointer-events-none`（按钮基类） | `button-P7AG3MDX.js` |

> 代码里**没有 `rows` 属性**，高度完全由 40/160px 夹逼；桌面 20px 行高约 **8 行**，移动 24px 行高约 **6.6 行**（像素裁切，不按行计数）。

### 2.4 Placeholder

| 项 | 值 | 证据 |
| --- | --- | --- |
| 层类 | `pointer-events-none absolute left-0 top-0`；compact 时加 `line-clamp-2`；字号同编辑器；颜色 `text-foreground-subtlest` | `JS:3144161` |
| 新任务 | 「向 ZCode 提问，使用 @ 添加上下文，使用 / 选择命令或能力」 | `IntlProvider:255145` |
| 新任务（移动） | 「向 ZCode 提问…」 | 同上 |
| 有历史、未运行 | 「提出后续修改要求」 | 同上 |
| 有历史、运行中 | 「继续输入以排队后续修改」 | 同上 |
| 远端不可发送 | 「`remote.conversation.sendUnavailable`」文案（同时右侧显示 `max-w-40 text-ui-sm text-foreground-subtle` 提示） | `JS:3412000` 区块 |

### 2.5 工具栏骨架

| 区域 | 类名 | 证据 |
| --- | --- | --- |
| 工具栏 | `group/toolbar flex items-end gap-3`（inline 变体 `shrink-0 gap-1.5`） | `JS:3158794` |
| 左侧区 | `flex min-w-0 flex-1 items-center` | 同上 |
| 左侧内容 | `flex shrink-0 items-center gap-1`（4px） | 同上 |
| 右侧区 | `ml-auto flex shrink-0 items-center justify-end gap-1.5`（6px） | 同上 |

### 2.6 发送 / 停止 / 取消

| 控件 | 类名（逐字） | 尺寸 | 证据 |
| --- | --- | --- | --- |
| 发送 | `type=submit`，`size=icon-md`，`gap-1 rounded-full bg-brand text-ui-base text-foreground-inverse hover:bg-brand/80`，图标 `size-4` | **28×28**，圆角全圆 | `JS:3159700`；`JS:3412000` |
| 发送中 | 图标换成 `size-4` 旋转 loader，按钮 `disabled` | 28×28 | 同上 |
| 停止 | `variant=secondary`，`size=icon-md`，`rounded-full`，图标 `size-4 fill-current`；仅 `canStop && 输入为空` 时替换发送 | 28×28 | `JS:3412000` |
| 取消（提交中） | `variant=ghost`，`size=icon-lg`（**32×32**，`rounded-lg`），图标 `size-4`，tooltip 带 `Esc` | 32×32 | `JS:3159700` |
| 按钮基类 | `group/button inline-flex shrink-0 items-center justify-center rounded-md border border-transparent bg-clip-padding text-ui-base/relaxed whitespace-nowrap transition-colors outline-none select-none disabled:pointer-events-none disabled:opacity-50` | — | `button-P7AG3MDX.js:2623` |
| tooltip | 发送：「发送」或「加入队列」+ `Enter`；停止：「停止生成」+ `Esc` | — | `IntlProvider:255444`、`:257930`；`JS:3159700` |

> **没有**发送↔停止的交叉淡入动画；二者是条件渲染二选一（`JS:3412000`）。

### 2.7 附件条

附件行位于编辑器**上方**（`topContent`），两行容器：

| 行 | 类名 | 证据 |
| --- | --- | --- |
| 文件附件 | `flex max-w-full flex-col items-start gap-2` 内的 `flex max-w-full flex-wrap gap-2`，`data-composer-file-attachments-row=true` | `JS:3409487` |
| 上下文附件 | `flex max-w-full flex-wrap items-center gap-2`，`data-composer-context-attachments-row=true` | `JS:3412000` 之后 |

详见 §3。

### 2.8 `+` 动作菜单

| 项 | 值 | 证据 |
| --- | --- | --- |
| 触发 | `ghost` + `icon-md`，`gap-1 rounded-lg text-ui-base`，图标 `size-4`，`aria-label` =「添加上下文」 | `JS:3147251` |
| 容器 | 包裹在 `span[data-composer-action-menu] flex shrink-0 items-center` | `JS:3154850` |
| 弹层 | Popover：`w-(--radix-popover-trigger-width) max-w-[calc(100vw-1rem)] gap-0 overflow-visible border-0 bg-transparent p-0 shadow-none`，`side=top align=start sideOffset=0` | `JS:3147251` |
| 面板 | `mb-1 overflow-hidden rounded-2xl border border-border bg-menu shadow-xs` | `JS:3098118`（`Wq`） |
| 列表 | `max-h-56 overflow-y-auto px-1 py-0.75`（224px / 4px / 3px），虚拟滚动 | 同上 |
| 分区标题 | `flex h-8 items-center px-3 text-ui-base font-semibold uppercase tracking-wide text-foreground-subtle` | `JS` `B1t` |
| 选项行 | `flex h-8 w-full items-center gap-3 rounded-xl px-3 text-left transition-colors`；选中 `bg-selected`，hover `bg-hover`，禁用 `opacity-50 cursor-not-allowed` | `JS:3101561` |
| 分区 | `add`（附件 / 目标 / 工作流）、`plugins`、`files`、`sessions`；`add` 为空则不渲染 | `JS:3147251` |
| 底部快捷键 | `flex flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3 text-ui-sm text-foreground-subtle`；按键块 `size-5 rounded bg-tooltip-tag font-mono` | 同上 |

### 2.9 模型选择器（3.17.1 合并式）

| 项 | 值 | 证据 |
| --- | --- | --- |
| 触发类 | `w-fit justify-between gap-1 rounded-lg pl-2 pr-1.5 text-ui-base whitespace-nowrap`，`variant=ghost size=default`（**h-7 = 28px**） | `JS:3339568` |
| 触发 data | `data-composer-combined-model=true`、`data-composer-collapse-priority=3`、`data-model-current-value`、`data-chat-toolbar-popover-trigger` | 同上 |
| 打开态宽度 | 内联样式 `--composer-model-open-width: max(触发宽度, 8rem)`；打开时 `px-2 w-(--…) min-w-32 max-w-(--…)` | 同上 |
| 内容 | 左侧模型图标 `size-4`；标签 `h-[1.3em] leading-[1.25]`；右侧 chevron `size-3.5 text-foreground-subtle`；加载时 `animate-spin` | `JS:3214959`、`JS:3339568` |
| 菜单宽度 | **`w-72`（288px）**，`overflow-y-hidden`；非合并场景为 `w-48 max-h-72 overflow-y-auto` | `JS:3339568` |
| 菜单结构 | 分隔线 → 分组标签（`text-ui-sm text-foreground-subtle`）→ provider 连接行 → 模型列表 `max-h-60`（240px）→ 分隔线 → 「推理强度」滑杆 | `JS:3327065` |
| 模型项 | `min-h-7 gap-2 rounded-md px-2 py-1`，选中右侧 `check size-4 text-foreground-subtle`；锁定项 `data-disabled` | `JS:485995`（`Kh`） |
| 底部动作 | `min-h-8 gap-2 px-2`，`data-model-footer-action=manage-models`（「管理模型」） | `JS:3339568` |
| 分组标签 | `min-h-8 items-center px-2 py-1`；provider 徽标 `rounded-full pr-1.5 text-ui-sm` | `JS:3336800` 区块 |

### 2.10 思考等级（Effort）

| 形态 | 表现 | 证据 |
| --- | --- | --- |
| 独立控件（`select`） | `variant=ghost size=default` + 指示箭头 `size-3.5`，类 `gap-1 rounded-lg px-1.5 py-1.5 text-ui-base`（**h-7**）；下拉 `position=popper side=top align=start sideOffset=4 collisionPadding=8` | `JS:3348689` |
| 选项文本 | `first-letter:uppercase`，按 `chat.toolbar.thoughtLevel.value.*` 本地化：关闭/开启/极低/低/中/高/极高/最高/极致 | `JS:3348689`；`IntlProvider:267853` |
| 单档位 | 渲染静态 `span`（`data-thought-level-fixed=true`），不弹菜单 | `JS:3348047` |
| 非 select | 普通按钮，点击循环档位 | `JS:3348689` |
| 竖向指示条（旧） | `w-1 self-stretch rounded-full bg-current/10`，内部 `bg-success` 高度百分比，`transition-[height] duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)]` | `JS:3348047` |
| 紧凑态 | `group-data-[composer-compact]/thought:hidden` 隐藏文字，只留图标/指示条 | 同上 |

**合并菜单里的滑杆（3.17.1 新增）**：

| 元素 | 类名 | 证据 |
| --- | --- | --- |
| 容器 | `composer-thought-slider relative h-8 w-full rounded-full`；禁用 `data-disabled=true` → `opacity:.5` | `JS:3329700`；CSS `:130` |
| 轨道 | `absolute inset-x-0 top-1 h-6 overflow-hidden rounded-full bg-foreground/10`，内描边 `border border-foreground/10` | `JS:3329700` |
| 填充 | `composer-thought-slider-fill absolute inset-y-0 left-0 rounded-full bg-success`；`data-max` 时叠加渐变；`data-highspeed` 时蓝/紫 | `JS:3329700`；CSS `:130` |
| 档位点 | 容器 `absolute inset-x-3.5 inset-y-0 flex items-center justify-between`；点 `size-1 rounded-full`，`data-filled` 白/30%，`data-hovered` 放大 1.75 | `JS:3329700`；CSS `:130` |
| 拇指 | `composer-thought-slider-thumb absolute top-0.5 size-7 rounded-full shadow-sm`，`left: calc((100% - 28px) * progress)` | 同上 |
| 原生 input | `composer-thought-range absolute inset-0 m-0 h-8 w-full appearance-none bg-transparent` | 同上 |
| 轨道长度公式 | 填充宽 `calc(14px + (100% - 28px) * p)`；拇指左 `calc((100% - 28px) * p)` | 同上 |
| 聚焦 | `.composer-thought-slider:has(input:focus-visible){outline:2px solid var(--color-ring); outline-offset:2px}` | CSS `:130` |

> 注意：`--color-ring` 在 3.17.1 CSS 中**未定义**（0 命中），该 outline 实际会回退为无效值 → 浏览器不绘制。Compose 复刻建议用 `border-hover` 或品牌色自绘焦点环，并标注为「网页缺陷」。

### 2.11 Mode 选择器

| 项 | 值 | 证据 |
| --- | --- | --- |
| 位置 | 左侧 leading content，`data-composer-collapse-priority=1` | `JS:3363383` |
| 触发 | `variant=ghost size=sm`，类 `group/mode h-7 gap-1 rounded-lg px-2 text-ui-base data-[composer-compact=true]:w-7 data-[composer-compact=true]:px-0` | 同上 |
| 图标 | `size-4`；`yolo` → `text-warning`，`guarded` → `text-icon-blue` | 同上 |
| 菜单 | `side=top sideOffset=4`，**`w-64`（256px）** | 同上 |
| 首项 | Plan 复选框：`min-h-13 items-start gap-3 py-2`（**52px 高**），图标 `size-4.5`，标题 + `text-ui-sm text-foreground-subtle` 说明 | 同上 |
| 其余项 | Radio：`min-h-13 items-start gap-3 py-2`，`data-testid=chat-mode-select-item` | 同上 |
| Plan 标记 | `data-testid=v4-composer-plan-marker`，`flex items-center gap-1`，竖线 `h-3 w-px bg-border`，删除按钮 ghost sm（`data-composer-collapse-priority=2`） | 同上 |
| 文案 | 变更前确认 / 自动编辑 / 计划模式 / 自主模式 / 完全访问；说明：改文件前先问我 / 自动编辑文件 / 编辑前先出计划 / 在有风险时询问 / 减少确认次数 | `IntlProvider:321426`、`:321579` |

### 2.12 队列卡片（`queueItems`）

| 项 | 值 | 证据 |
| --- | --- | --- |
| 位置 | dock 内，quota 横幅 / pendingCommand 之后、待交互卡片之前、输入区之上 | `JS:4708637` |
| 容器 | `relative z-0 w-full overflow-hidden rounded-t-2xl border border-border bg-surface p-1 backdrop-blur-md` + `-mb-7 pb-7`（上收 28px、下补 28px，让输入卡压住下沿） | `JS:3675858` |
| data | `data-queue-count`、`data-queue-auto-drain` | 同上 |
| 空队列 | `items.length===0` 返回 `null` | 同上 |
| 折叠 | **无折叠/展开**，永远展开全部条目，无 max-height | 同上 |
| 暂停横幅 | `mb-1 flex min-h-10 items-center gap-3 rounded-xl border border-border/70 bg-surface-raised px-3 py-2 text-ui-base text-foreground`；「继续」ghost sm | 同上 |
| 横幅文案 | stopped：由于你中断了当前响应，队列已暂停；error：由于当前响应出错，队列已暂停（内容未丢失）；其它：队列已暂停 | `IntlProvider:258735` |
| 条目 `li` | `relative flex items-center gap-2 rounded-xl px-1.5 py-1 pr-1 transition-colors hover:bg-hover/30`；拖拽中 `bg-hover/40 shadow-sm`；编辑待处理 `opacity-60` | `JS:3671700` |
| 拖拽手柄 | ghost `icon-md`，`shrink-0 cursor-grab touch-none text-foreground-subtlest active:cursor-grabbing`，图标 `size-4` | 同上 |
| 正文 | `flex min-w-0 flex-1 items-center gap-2 truncate text-ui-base text-foreground`；compact 条目 `font-mono` | 同上 |
| 附件摘要 | `truncate text-ui-sm text-foreground-subtle`，文件名逗号拼接 | `JS:3673200` |
| 立即发送 | `variant=secondary size=default data-icon=inline-start`，图标 `size-3.5`；文案「立即」 | `JS:3673200` |
| 编辑 | ghost `icon-md`，图标 `size-4` | 同上 |
| 删除 | ghost `icon-md`，图标 `size-4` | 同上 |
| 发送确认对话框 | `data-testid=v4-paused-queue-send-dialog`；标题「发送消息？」，说明「你即将发送一条消息。要清除之前已排队的 {count} 条消息吗？」，按钮「清空队列」「发送消息」 | `JS:1002125`；`IntlProvider:258943` |

> `bg-surface-raised` 在 CSS 中**仍 0 命中**（3.15.3 同样缺失）→ 暂停横幅背景实际回退透明，只剩边框。Compose 复刻建议用 `surface-hover` 或 `card`。

### 2.13 快捷键

| 动作 | 默认绑定 | 证据 |
| --- | --- | --- |
| `composerSend` | **Enter** | `src-BSClmRM9.js:5334` |
| `composerInsertNewline` | **Shift+Enter** | `src-BSClmRM9.js:5414` |
| `composerStop`（Esc 语义） | 输入区 `onKeyDown` 捕获 `Escape` → `onCancel`（有 `onCancel` 且未提交时） | `JS:3154850` |
| 发送判定 | 未按 Shift/Ctrl/Meta、非输入法组合、有非空文本（或允许空发） | `JS:3130337`（`tJ`） |
| Ctrl/Cmd+Enter | 若存在 `onModifiedSubmit`，走「立即发送 / 加入队列」 | `JS:3130337`（`J0t`） |
| 工具栏快捷键提示 | 模型 `m`、模式 `ctrlShiftM`、思考等级 `t` | `JS:3348689`（`t7t`） |
| 发送按钮 tooltip | `Enter`（`enterSubmits` 为真时） | `JS:3159700` |

### 2.14 状态矩阵

| 状态 | 视觉/行为 | 证据 |
| --- | --- | --- |
| 默认 | 边框 `input-border`，底 `input` | `JS:3158794` |
| hover（卡片） | 边框 `input-border-hover` | 同上 |
| focus-within | 边框 `input-border-focused`，底 `input-focused`；无 ring | 同上 |
| 拖拽 | `border-brand bg-input-focused ring-1 ring-brand/30` + 遮罩胶囊 | 同上 |
| 输入为空 | 发送按钮 `disabled`（除非 `allowSubmitWhenEmpty`） | `JS:3154850` |
| 附件未就绪 | 发送 `disabled`（`hasUnreadyAttachments`） | `JS:3412000` 区块 |
| 运行中 + 输入为空 | 发送 → 停止（secondary，圆形） | `JS:3412000` |
| 提交中 | 发送图标换 loader；额外出现 ghost `icon-lg` 取消按钮 | `JS:3159700` |
| 禁用 | 编辑器 `editable=false`；按钮 `opacity-50 pointer-events-none` | `JS:3142693`；button 基类 |
| 远端不可发送 | 显示「发送不可用」提示，发送禁用 | `JS:3412000` 区块 |
| 窄容器 | 按 `data-composer-collapse-priority` 0→3 依次折叠；再隐藏 provider 前缀；再隐藏模型文字只留图标；最后思考控件变图标 | `JS:3152682`（`O2t`） |
| 错误横幅 | `mb-6 w-full shrink-0` 包裹；初始化失败 `mb-2 flex flex-wrap items-center gap-2 rounded-lg border border-card-border bg-card px-3 py-2 text-ui-base` | `JS:3418165` |

### 2.15 移动端与安全区

| 项 | 结论 | 证据 |
| --- | --- | --- |
| 输入字号 | 移动端 `text-mobile-input-safe leading-6`（16/24），避免 iOS 聚焦缩放 | `JS:3130337` |
| placeholder | compact 时 `line-clamp-2` | `JS:3144161` |
| Enter 行为 | 移动 web-remote 下 `enterSubmits=false`（Enter 只换行，靠按钮发送） | `JS:3130337`（`G0t`） |
| Composer 安全区 | **仍未实现**：dock 只有 `px-2 pb-4`，无 `env(safe-area-inset-bottom)` | `JS:4488160` |
| 使用安全区的组件 | Toast：`bottom-[calc(1rem+env(safe-area-inset-bottom))]`；BottomSheet：`pb-[calc(env(safe-area-inset-bottom)+12px)]`；移动页头：`pt-[calc(env(safe-area-inset-top)+12px)]` | Toast chunk；`JS:6821073`；`JS:6819257` |

---

## 3. 附件（详细）

### 3.1 容器与变体

附件组件基类（`T6e`）支持三种变体：`grid`（`size-24 rounded-lg`）、`inline`（`h-8 rounded-md border p-1 pr-2.5`）、`list`（`w-full gap-3 rounded-lg border p-3`）。Composer 通过 `className` 覆盖为下列尺寸（Tailwind merge 后生效）。

证据：`JS:1213939`（`T6e`）；覆盖见 `JS:3409487`。

### 3.2 图片缩略图

| 项 | 值 | 证据 |
| --- | --- | --- |
| 外框 | `relative size-12 overflow-hidden rounded-lg bg-surface`（**48×48**） | `JS:3409487` |
| 描边 | `after:pointer-events-none after:absolute after:inset-0 after:rounded-lg after:border after:border-border after:content-['']` | 同上 |
| 内图 | `size-full rounded-none` | 同上 |
| 打开 | 点击进入图片预览（`onOpen`） | 同上 |
| data | `data-composer-attachment-kind=image|video|pdf|file`、`data-upload-status` | 同上 |

### 3.3 文件 pill

| 项 | 值 | 证据 |
| --- | --- | --- |
| 外框 | `h-12 w-fit max-w-full min-w-0 gap-2 rounded-lg border border-border bg-surface p-1.5 pr-6`（**48px 高 / 6px 内边距 / 24px 右留白**） | `JS:3409487` |
| CSS 变量 | `[--attachment-bg:var(--color-surface)]` | 同上 |
| hover | `hover:bg-surface-hover` | 同上 |
| 图标容器 | `relative shrink-0 size-9 rounded-md bg-background`（**36×36**） | 同上 |
| 文件图标 | `size-4`（16px） | 同上 |
| 文本区 | `min-w-0 max-w-40 flex-1`（最大 **160px**） | `JS:3412000` 区块 |
| 文件名 | `block truncate text-ui-base font-medium text-foreground` | 同上 |
| 类型/大小 | `block truncate text-ui-sm font-normal text-foreground-subtle` | 同上 |
| 剪贴板文本 | 不显示文件图标，改用 `max-w-48 text-ui-base text-foreground` 文本块 | 同上 |

### 3.4 上传进度 / 失败 / 重试 / 删除

| 状态 | 表现 | 证据 |
| --- | --- | --- |
| `uploading`（图片） | 覆盖层 `absolute inset-0 grid place-items-center rounded-lg bg-background/85 text-[7px] font-semibold text-foreground`；SVG `viewBox 0 0 24 24`、`r=9`、`strokeWidth=2`、`pathLength=100`、`strokeDasharray="${progress} 100"`、整体 `-rotate-90 text-brand`；背景圆 `stroke-border`；失败时 `stroke-destructive` 且中心显示 `!` | `JS:3409487`、`JS:3412000` |
| `uploading`（文件） | 右侧 `max-w-28 truncate text-ui-sm font-normal text-foreground-subtle`，文本 `NN%` | `JS:3412000` 区块 |
| `failed`（文件） | 同上但 `text-destructive`，文本「上传失败：{message}」 | 同上 |
| 重试按钮 | `grid size-5 shrink-0 place-items-center rounded-md text-destructive hover:bg-hover focus-visible:ring-2 focus-visible:ring-input-border-focused`，图标 `size-3` | 同上 |
| 删除按钮 | ghost `icon-xs` + `absolute right-0.5 top-0.5 z-20 size-3.5 rounded-full bg-primary p-0 text-primary-foreground opacity-0 transition-opacity hover:bg-primary/80 focus-visible:opacity-100 [@media(hover:none)]:opacity-100`，图标 `size-2.5`（**14×14 按钮 / 10px 图标**） | 同上 |
| `ready` | 进度层消失；`showComplete` 时短暂显示「上传完成」 | 同上 |
| `queued` | 「等待上传」 | `IntlProvider:311529` |
| `waitingSession` | 「正在等待会话」 | 同上 |
| 上传中 | 「正在上传 {progress}%」 | 同上 |
| 完成 | 「上传完成」 | 同上 |

### 3.5 上下文附件（非文件）

同一行内依次渲染：评论（comments）、上下文（contexts）、画板、引用（references）、其他引用。容器 `flex max-w-full flex-wrap items-center gap-2`，`data-composer-context-attachments-row=true`。证据：`JS:3412000` 区块。

---

## 4. 浮层体系

### 4.1 总表

| 浮层 | 组件 | 层级 | 圆角 | 背景 | 边框 | 阴影 | 遮罩 | 关闭方式 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| DropdownMenu | Radix | `z-[60]` | 8px | `bg-menu` | `border-popover-border` | `shadow-md` | 无（modal 默认 true，仅锁滚动） | 点击外部 / Esc / 选项 |
| Popover | Radix | `z-[60]` | 12px | `bg-popover` | `border-border` | `shadow-md` | 无（非模态） | 点击外部 / Esc |
| ContextMenu | Radix | `z-[60]` | 8px | `bg-menu` | `border-popover-border` | `shadow-md` | 无 | 点击外部 / Esc |
| Tooltip | Radix | `z-50` | 8px | `bg-tooltip` | `border-border` | 无 | 无 | 移出 / Esc |
| HoverCard | Radix | `z-50` | 8px | `bg-popover` | `ring-1 ring-foreground/10` | `shadow-md` | 无 | 移出 |
| Dialog | Radix | `z-50` | 16px | `bg-popover` | `border-popover-border` | `shadow-md` | `bg-black/60` + `backdrop-blur-xs` | 外部点击 / Esc / 关闭按钮 |
| AlertDialog | Radix | `z-50` | 16px | `bg-popover/98` | 无（`ring-border`） | `shadow-2xl` | `bg-black/60` + blur | Esc / 取消（默认不允许外部关闭） |
| BottomSheet | Radix Dialog | `z-50` | 上 16px | `bg-popover` | `border-t` | `shadow-2xl` | `bg-black/45` | 外部点击 / Esc |
| SidePane | 自定义 | `z-40` | 0 | `bg-background` | `border-l` | `shadow-2xl` | `bg-background/60` + blur 1px | 点遮罩 / 关闭 |
| Toast | 自定义宿主 | `z-[9999]` | 16px | `bg-toast/60` + `backdrop-blur-xl` | `border-border` | `shadow-lg` | 无 | 自动消失 / 关闭按钮 / 动作 |

### 4.2 DropdownMenu

```
content: z-[60] flex flex-col gap-0.5
         max-h-(--radix-dropdown-menu-content-available-height)
         max-w-(--radix-dropdown-menu-content-available-width)
         min-w-32 origin-(--radix-dropdown-menu-content-transform-origin)
         overflow-x-hidden overflow-y-auto rounded-lg border border-popover-border
         bg-menu p-1 text-foreground !shadow-md duration-100
         data-[side=*]:slide-in-from-* data-open:animate-in fade-in-0 zoom-in-95
         data-closed:animate-out fade-out-0 zoom-out-95
```

| 子项 | 类名 | 证据 |
| --- | --- | --- |
| item | `group/dropdown-menu-item relative flex min-h-7 cursor-default items-center gap-2 rounded-md px-2 py-1 text-ui-base/relaxed text-foreground outline-hidden select-none data-[highlighted]:bg-menu-hover data-disabled:text-foreground-subtlest data-disabled:pointer-events-none` | `JS:973776` |
| item（destructive） | `data-[variant=destructive]:text-destructive data-[highlighted]:bg-destructive data-[highlighted]:text-destructive-foreground` | 同上 |
| checkbox / radio item | `relative flex min-h-7 items-center gap-2 rounded-md py-1.5 pr-8 pl-2`，指示器 `absolute right-2`，勾 `size-4 text-foreground-subtle` | `JS:974690`、`:975660` |
| label | `px-2 py-1.5 text-ui-base font-medium text-foreground-subtlest` | `JS:976463` |
| separator | `-mx-1 my-1 h-px bg-border` | `JS:976669` |
| shortcut | `ml-auto text-ui-xs tracking-widest text-foreground-subtlest group-data-[highlighted]:text-foreground-subtle` | `JS:976813` |
| sub-trigger | `group/... flex min-h-7 cursor-default items-center gap-2 rounded-md px-2 py-1 text-ui-base data-[state=open]:bg-menu-hover`，右侧 `chevron-right size-4` | `JS:976813` |
| sub-content | `z-[60] ... min-w-32 rounded-lg border border-popover-border bg-menu p-1 shadow-md duration-100` | `JS:977844` |

默认 `align="start"`、`sideOffset=2`。证据：`JS:972916`。

### 4.3 Popover

```
z-[60] flex w-72 origin-(--radix-popover-content-transform-origin) flex-col gap-4
rounded-xl bg-popover p-2.5 text-ui-base text-popover-foreground shadow-md
border border-border overflow-hidden outline-hidden duration-100
+ slide/fade/zoom 动效
```

默认 `align="center"`、`sideOffset=4`。证据：`pptxElementReference-C5CWcv9D.js` 内 `ho()`（`data-slot=popover-content`）。

> 注意：**模型菜单用的是 DropdownMenu（`Ob`）而不是 Popover**；`+` 动作菜单用 Popover 外壳但内容自绘（`border-0 bg-transparent p-0 shadow-none`）。

### 4.4 ContextMenu

```
z-[60] flex flex-col gap-0.5 min-w-44 origin-(--radix-context-menu-content-transform-origin)
overflow-hidden rounded-lg border border-popover-border bg-menu p-1 text-foreground
shadow-md duration-100 + slide/fade/zoom
```

证据：`JS:2266038`。

### 4.5 Tooltip + Kbd

| 项 | 类名 | 证据 |
| --- | --- | --- |
| content | `z-50 inline-flex w-fit max-w-xs origin-(--radix-tooltip-content-transform-origin) items-center gap-1.5 rounded-lg border border-border bg-tooltip px-3 py-1.5 text-ui-sm text-tooltip-foreground has-data-[slot=kbd]:pr-1.5` + slide/fade/zoom | `JS:925824` |
| provider | `delayDuration` 默认 **0**；`skipDelayDuration=0`、`disableHoverableContent=true` | `JS:924790` |
| 默认方位 | `side=top`、`align=center`、`sideOffset=2` | `JS:925824`（`xy`） |
| kbd | `rounded-md h-4 inline-flex shrink-0 items-center bg-tooltip-tag px-1.5 text-ui-xs font-medium text-tooltip-tag-foreground`；macOS 用系统字体，其它 `font-mono` | `JS:925824` |

### 4.6 HoverCard

`z-50 w-72 rounded-lg bg-popover p-2.5 text-ui-base/relaxed text-popover-foreground shadow-md ring-1 ring-foreground/10`，默认 `sideOffset=4`。证据：`JS:1212138`。

### 4.7 Dialog

| 部件 | 类名 | 证据 |
| --- | --- | --- |
| overlay | `fixed inset-0 isolate z-50 bg-black/60 duration-100 supports-backdrop-filter:backdrop-blur-xs` + fade | `JS:970584` |
| content | `fixed top-1/2 left-1/2 z-50 grid w-full max-w-[calc(100%-2rem)] -translate-x-1/2 -translate-y-1/2 gap-4 rounded-2xl border border-popover-border bg-popover p-4 text-ui-base/relaxed text-foreground shadow-md duration-100 outline-none [app-region:no-drag]` | `JS:971166` |
| header | `flex flex-col gap-1` | `JS:971819` |
| footer | `flex flex-col-reverse gap-2 sm:flex-row sm:justify-end` | `JS:971979` |
| title | `text-ui-base font-medium text-foreground` | `JS:972250` |
| description | `text-ui-base/relaxed text-foreground-subtle *:[a]:underline *:[a]:underline-offset-3` | 同上 |
| 关闭按钮 | `absolute top-4 right-4 z-20 hover:bg-surface-hover`，`size=icon-sm`（24×24），图标默认尺寸 | `JS:971166` |

### 4.8 AlertDialog（二次确认）

| 部件 | 类名 | 证据 |
| --- | --- | --- |
| overlay | 同 Dialog（`bg-black/60` + blur） | `JS:2047942` |
| content | `fixed top-1/2 left-1/2 z-50 grid w-full max-w-[calc(100%-2rem)] -translate-x-1/2 -translate-y-1/2 gap-5 rounded-2xl border-none bg-popover/98 p-5 text-ui-base/relaxed text-foreground ring-border shadow-2xl duration-100 outline-none sm:max-w-md` | `JS:2048463` |
| header | `flex flex-col gap-2 text-left` | 同上 |
| footer | `flex flex-col-reverse gap-2 sm:flex-row sm:justify-end` | 同上 |
| title | `text-ui-lg font-semibold text-foreground`（**16px**） | 同上 |
| description | `whitespace-pre-line pt-0.5 text-ui-base leading-6 text-foreground-subtle` | `JS:2049405` |
| action | 复用按钮基类，可 `variant=destructive` | `JS:2049611` |
| cancel | 默认 `variant=outline` | `JS:2049760` |

**危险操作实例**（设置 → 浏览器 → 清除全部）：

- 行按钮：`size=lg`、`variant=destructive`、`min-w-24`（96px）。
- 确认框：标题「清除全部内置浏览器数据？」，说明「这会退出内置浏览器中已登录的网站，并删除 Cookie、站点数据和缓存。此操作不可撤销。」，按钮「取消」/「确认清除」（destructive，带 spinner）。
- 证据：`JS:6338212`；`IntlProvider` 同段。

### 4.9 BottomSheet（移动端抽屉）

```
overlay: fixed inset-0 z-50 bg-black/45 duration-150 + fade
content: fixed inset-x-0 bottom-0 z-50 mx-auto flex max-h-[85dvh] w-full max-w-md flex-col
         rounded-t-2xl border-t border-popover-border bg-popover
         pb-[calc(env(safe-area-inset-bottom)+12px)] text-foreground shadow-2xl outline-none
         duration-200 slide-in-from-bottom / slide-out-to-bottom
handle:  mx-auto mt-2 h-1 w-9 shrink-0 rounded-full bg-foreground-subtlest
header:  px-4 pb-2 pt-3
title:   text-ui-lg font-semibold
desc:    mt-1 text-ui-sm text-foreground-subtle
```

证据：`JS:6821073`。

### 4.10 SidePane（右侧抽屉/侧栏）

| 项 | 类名 | 证据 |
| --- | --- | --- |
| 遮罩 | `absolute inset-0 bg-background/60 backdrop-blur-[1px]` | `JS:5148736` |
| 面板 | `absolute top-0 right-0 h-full w-[min(88vw,28rem)] max-w-full border-l border-border bg-background shadow-2xl transition-transform duration-200 ease-out`，开 `translate-x-0` / 关 `translate-x-full` | 同上 |
| 层级 | 容器 `pointer-events-none absolute inset-0 z-40` | 同上 |

### 4.11 Toast

宿主挂载到 `document.body` 下的 `#zcode-toast-host`；按位置渲染 4 个固定容器 + 锚点容器。证据：`pptxElementReference-C5CWcv9D.js:238632` 起。

| 位置 | 容器类 |
| --- | --- |
| `top-center` | `fixed top-16 left-1/2 z-[9999] flex -translate-x-1/2 flex-col items-center gap-2` |
| `top-right` | `fixed right-4 top-16 z-[9999] flex max-w-[calc(100vw-2rem)] flex-col items-end gap-2` |
| `bottom-center` | `fixed bottom-[calc(1rem+env(safe-area-inset-bottom))] left-1/2 z-[9999] flex -translate-x-1/2 flex-col items-center gap-2` |
| `bottom-left` | `fixed bottom-[calc(1rem+env(safe-area-inset-bottom))] left-4 z-[9999] flex flex-col items-start gap-2` |

单条 Toast：

```
rounded-2xl border bg-toast/60 text-ui-base shadow-lg backdrop-blur-xl
transition-[transform,opacity] duration-200 ease-[cubic-bezier(0.77,0,0.175,1)]
```

| 变体 | 宽度/外观 | 内部 |
| --- | --- | --- |
| `info` / `warning` / `success` | `w-[min(536px,calc(100vw-2rem))] border-border text-foreground` | `flex items-center gap-4 px-4`，图标 `size-4`，正文 `py-3 leading-5`；可选进度条 `h-1 bg-surface-hover` |
| `update` | `origin-bottom-left w-[min(300px,calc(100vw-1rem))] border-popover-border shadow-lg` | `grid grid-cols-[auto_minmax(0,1fr)_auto] gap-2 px-2.5 py-2`，圆点 `size-2 rounded-full bg-primary/80 ring-4 ring-accent` |
| `source-server-update` | `w-[min(480px,calc(100vw-2rem))] border-popover-border` | `flex items-start gap-3 px-4 py-3`，标题 14/500，正文 12/20 subtle，动作按钮 `h-8 rounded-md bg-primary px-3`，关闭 `size-7 rounded-md` |
| 默认 | `border-border px-4 py-3 text-foreground whitespace-pre-line` | 纯文本 |

| 行为 | 值 |
| --- | --- |
| 默认停留 | **3000ms**（`eo=3e3`） |
| 退场延时 | **200ms**（`to=200`） |
| 入场/退场 | 顶部：`-translate-y-1 scale-[0.98] opacity-0` → `translate-y-0 scale-100 opacity-100`；底部：`translate-y-1 scale-[0.98]` → `translate-y-0 scale-100`；右侧：`translate-x-[calc(100%+1rem)]` → `translate-x-0` |
| 去重 | `dedupeKey` 相同则替换 |
| 锚点 | `anchorId` → 跟随 DOM 元素水平居中（ResizeObserver + MutationObserver + scroll/resize 监听） |

### 4.12 点击外部关闭

| 浮层 | 行为 | 依据 |
| --- | --- | --- |
| DropdownMenu | Radix 默认 `modal=true`：点击外部关闭、锁 body 滚动、焦点圈定 | Radix 语义 + `Eb/Ob` 无 `modal=false` 覆盖 |
| Popover | Radix 默认非模态：点击外部关闭，不锁滚动 | `pptxElementReference` `L/mo/ho` |
| ContextMenu | 点击外部关闭 | Radix 语义 |
| Tooltip | 无外部关闭（移出即关） | Radix 语义 |
| Dialog / AlertDialog | 点击遮罩关闭（AlertDialog 默认不关，业务里也未见 `onInteractOutside` 放行） | `JS:971166`、`:2048463` |
| BottomSheet | 点击遮罩关闭 | `JS:6821073` |
| SidePane | 点遮罩关闭 | `JS:5148736` |
| Toast | **不响应外部点击**（无遮罩，pointer-events 只在自身） | Toast chunk |

---

## 5. 设置页

### 5.1 整体布局

```
section[data-settings-content-frame]  flex min-h-0 flex-col
├─ div[data-settings-top-inset]       h-1 / h-1.5（拖拽区）
└─ div[data-settings-panel-frame]     relative flex flex-col min-h-0 h-full
                                      border border-border bg-background rounded-xl（紧凑时 rounded-md）
   ├─ div (绝对定位设置入口，right-0 top-0 h-10 w-10)
   ├─ div.flex.h-12.shrink-0          面包屑行
   │  └─ div.min-w-0.flex-1            breadcrumb（px-2.5，gap-0，text-ui-base/relaxed）
   └─ main.min-h-0.flex-1.overflow-y-auto.[scrollbar-gutter:stable]
      └─ div.mx-auto.w-full.max-w-4xl.px-4.pb-8.pt-0.lg:px-8.lg:pb-10.flex.flex-col.gap-8
         ├─ 标题行（flex flex-wrap items-center justify-between gap-3）
         └─ div.space-y-8（各分组卡）
```

证据：`JS:6480700`；容器常量 `JS:2478921`（`FR`）；面包屑 `JS:4784248`。

| 项 | 值 |
| --- | --- |
| 内容最大宽度 | **896px**（`max-w-4xl`） |
| 水平内边距 | 16px（`px-4`），`≥1024px` 为 32px（`lg:px-8`） |
| 底部内边距 | 32px，`lg` 40px |
| 分组间距 | **32px**（`gap-8` / `space-y-8`） |
| 面板圆角 | 12px（`rounded-xl`；紧凑 `rounded-md` 6px） |
| 面包屑行高 | **48px**（`h-12`） |
| 设置标题 | `text-ui-settings-title font-semibold tracking-tight`（**24px**），`lg` 30px；`titleBadge` 为 `h-6 rounded-md border px-2 text-ui-xs font-semibold` |

### 5.2 侧栏

| 项 | 类名 | 证据 |
| --- | --- | --- |
| aside | `min-w-0` | `JS:6478700` |
| 顶部拖拽区 | `h-12 [app-region:drag]` | 同上 |
| 返回按钮区 | `px-2 pb-3 pt-3` | 同上 |
| 返回按钮 | ghost `size=lg`，`m-1 w-[calc(100%-0.5rem)] justify-start gap-2 rounded-xl bg-surface px-1.5 text-remote-foreground-muted hover:bg-surface-hover hover:text-foreground` | 同上 |
| nav | `flex-1 overflow-y-auto px-2 pb-3` | `JS:6480143` |
| 分组容器 | `space-y-4`；组内 `space-y-1`；`n>0` 时 `max-lg:border-t max-lg:border-border max-lg:pt-3` | 同上 |
| 分组标题 | `px-2.5 pb-1 text-ui-sm font-medium text-remote-foreground-muted`（`max-lg:sr-only`） | 同上 |
| 导航项 | `flex h-8 w-full items-center gap-2 rounded-xl px-2.5 text-left transition-colors`；选中 `bg-surface-hover text-foreground`，未选中 `text-remote-foreground-muted hover:bg-surface-hover hover:text-foreground`；`max-lg:size-10 justify-center` | `JS:6459441` |
| 图标 | `size-4`（16px） | 同上 |
| 文字 | `truncate text-ui-base text-foreground` | 同上 |

### 5.3 分组卡与行

| 部件 | 类名 | 证据 |
| --- | --- | --- |
| 分组卡 | `overflow-hidden rounded-xl border border-border bg-card py-0 shadow-none`，内容 `space-y-0 px-0` | `JS:2481246` |
| 行 | `border-t border-border px-4 py-3 first:border-t-0` | `JS:2480483` |
| 行网格 | `grid items-center gap-4`；默认 `grid-cols-[minmax(0,1fr)_192px]`；`controlLayout=wide` 为 `grid-cols-1 sm:grid-cols-[minmax(0,1fr)_280px]` | 同上 |
| 行标题 | `text-ui-base font-medium text-foreground`（14/500） | 同上 |
| 行说明 | `mt-1 text-ui-base leading-6 text-foreground-subtle`（14/24） | 同上 |
| 控件区 | `flex w-full flex-nowrap items-center justify-end gap-2` | 同上 |
| 行详情（detail） | `mt-3`（宽布局时放进右列） | 同上 |

### 5.4 表单控件

| 控件 | 规格 | 证据 |
| --- | --- | --- |
| 输入框（`input`，size lg） | `h-8 rounded-lg px-3 py-1.5 text-ui-base`，底 `bg-input`，边框 `input-border`，hover `input-border-hover`，focus `input-border-focused` + `bg-input-focused`，`placeholder:text-foreground-subtlest`，禁用 `opacity-50`，错误 `aria-invalid:border-destructive aria-invalid:ring-2 aria-invalid:ring-destructive/20` | `pptxElementReference` `fo`；`JS:4783420` |
| 输入框（settings 内联） | `rounded-lg border border-input-border bg-input px-2 py-2 text-ui-base` | `JS:4272461` |
| 选择器触发器（lg） | `h-8 rounded-lg pl-3 pr-2 text-ui-base`，右侧 `chevron-down size-3.5 text-foreground-subtle`；设置页常用 `w-[260px] justify-between` | `JS:482700`、`:6386226` |
| 选择器菜单 | `z-[60] max-h-(--radix-select-content-available-height) min-w-32 rounded-lg border border-popover-border bg-menu p-1 shadow-md duration-100` | `JS:484565` |
| 选择器项 | `relative flex min-h-7 w-full items-center gap-2 rounded-md px-2 py-1 text-ui-base/relaxed`，选中右侧勾 `size-4` | `JS:485995` |
| 开关（default） | `h-[18px] w-[32px] rounded-full p-px`，选中 `bg-primary`，未选 `bg-primary/30`，滑块 `size-4 rounded-full bg-primary-foreground`，选中位移 `translate-x-[calc(100%-2px)]`；禁用 `opacity-50` | `JS:1797415` |
| 开关（sm） | `h-[16px] w-[28px]`，滑块 `size-3.5` | 同上 |
| 按钮（lg） | `h-8 gap-1 rounded-lg px-2.5 text-ui-base`，图标 16px | `button-P7AG3MDX.js:2623` |
| 按钮（default） | `h-7 gap-1 px-2`，图标 14px | 同上 |
| 危险按钮 | `variant=destructive`（`bg-destructive text-destructive-foreground hover:bg-destructive/90`）+ `min-w-24` | 同上；`JS:6332725` |
| 搜索框 | `h-9 rounded-xl pl-9` + 左侧放大镜 `absolute left-3 size-4`，清除按钮 `icon-sm rounded-full` | `JS:1979179` |
| 表单提示条 | 成功：`flex min-h-10 items-center gap-2 rounded-lg border border-success/30 bg-success/10 px-3 py-2 text-success`；失败：同结构 `destructive/30`、`destructive/10`、`text-destructive`；文字 `text-ui-sm font-medium` | `JS:6091600` |

### 5.5 危险操作

| 模式 | 做法 |
| --- | --- |
| 行内 | 行标题 + 说明（含「此操作不可撤销」）+ 右侧 destructive 按钮（`min-w-24`） |
| 二次确认 | AlertDialog（§4.8）：标题 16px semibold、说明 14/24 subtle、底部右对齐 `取消`(outline) / `确认清除`(destructive) |
| 执行中 | 按钮内 `size-4 animate-spin`，按钮与取消同时 `disabled` |

### 5.6 状态与空态

| 状态 | 表现 | 证据 |
| --- | --- | --- |
| 加载 | 骨架块 `animate-pulse rounded bg-surface` | `JS:1617230` 区块 |
| 空列表 | 无统一 EmptyState 组件；搜索无结果显示 `px-4 py-3 text-ui-base text-foreground-subtlest` | `JS:3098118`（`Wq` 空态分支） |
| 错误 | `role=alert` 提示条（§5.4） | `JS:6091600` |
| 禁用 | 控件 `disabled:opacity-50 disabled:pointer-events-none` | button/input 基类 |
| 保存中 | 按钮 spinner + `disabled` | `JS:6332725` |

### 5.7 分区清单（3.17.1）

| 分组 | 分区（id / 中文标题） |
| --- | --- |
| **基础设置**（`basics`） | `general` 常规 · `appearance` 外观 · `modelProvider` 模型设置 · `browser` 浏览器 · `remoteDevices` 远程设备 · `computerUse` 电脑操作 · `shortcuts` 键盘快捷键 · `workspaceFileSearch` 工作区文件搜索 |
| **Agent 能力**（`agentCapabilities`） | `personalization` 个性化 · `memory` 记忆 · `subagents` 子智能体 · `plugin` 插件 · `mcp` MCP 服务器 · `skill` 技能 · `commands` 命令 · `automations` 自动化 · `hooks` 钩子 |
| **数据与统计**（`dataAndStats`） | `dataSync` 数据同步 · `usage` 用量 · `projects` 项目管理 · `archives` 归档 |

证据：`JS:6382422`、`:6382487`、`:6382558` 起；中文标题 `IntlProvider` 同段。

---

## 6. Compose 1:1 对照表

> 约定：`dp = CSS px`；颜色用 `ZemoteColors` 语义令牌；尺寸常量集中在 `ComposerDimens`。

### 6.1 Composer

| Web 结构/样式 | Compose 实现 | 关键参数 |
| --- | --- | --- |
| `div.chat-composer-region`（`@container`） | `Box(Modifier.fillMaxWidth())` + `BoxWithConstraints` | 居中空态 `widthIn(max = 672.dp)` |
| dock 宽度三档 | `BoxWithConstraints` 分支 | `<864dp` fill；`864–1279dp` `fillMaxWidth(1f) - 96.dp` 且 `max 896.dp`；`≥1280dp` `- 384.dp` 且 `max 1152.dp` |
| dock 内边距 | `Modifier.padding(horizontal = 8.dp, vertical = 0.dp).padding(bottom = 16.dp)` | 3.17.1 为 8dp（旧 16dp） |
| 输入卡片 | `Surface`/`Box` + `border(0.5.dp, color, RoundedCornerShape(16.dp))` | 0.5dp 边框需 `Modifier.drawBehind` 或 `BorderStroke(0.5.dp)`；`background(input)`、`padding(12.dp)`、`verticalArrangement = spacedBy(12.dp)`、`clip(RoundedCornerShape(16.dp))` |
| hover/focus 边框 | `MutableInteractionSource.collectIsHoveredAsState()` / `collectIsFocusedAsState()` | hover→`inputBorderHover`；focus→`inputBorderFocused` + `inputFocused` |
| 阴影 | `Modifier.shadow(elevation = …, shape = RoundedCornerShape(16.dp))` | zai-light 用 0/1/3 + 0/6/18 的柔光，Compose 可用 `shadow(2.dp)` 近似并记录差异 |
| 编辑器 | `BasicTextField` + `TextFieldState` | `minHeight = 40.dp`、`maxHeight = 160.dp`、`verticalScroll`、字号 14.sp / 行高 20.sp（移动 16/24）、颜色 `foreground` |
| placeholder | `decorationBox` 内 `Text` | `color = foregroundSubtlest`、`maxLines = if (compact) 2 else 1`、`overflow = Ellipsis` |
| 工具栏 | `Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = spacedBy(12.dp))` | 左 `weight(1f)`，右 `wrapContentWidth` + `spacedBy(6.dp)` |
| 发送按钮 | `FilledIconButton` 自绘 | `28.dp`、`CircleShape`、`background(brand)`、图标 16.dp、`contentDescription = "发送"` |
| 停止按钮 | `IconButton` 自绘 | `28.dp`、`CircleShape`、`background(secondary)`、`Icons.Filled.Stop` 16.dp |
| 取消按钮 | `IconButton` | `32.dp`、`RoundedCornerShape(8.dp)`、ghost |
| 拖拽遮罩 | `Box(Modifier.matchParentSize().background(accent.copy(alpha=.55f)))` + 中央胶囊 | 胶囊 `RoundedCornerShape(50)`、`padding(h=16,v=8)`、`border(1.dp, border)` |
| 附件行 | `FlowRow(horizontalArrangement = spacedBy(8.dp))` | 图片 48dp；文件 pill 48dp 高 |

### 6.2 附件

| Web | Compose | 参数 |
| --- | --- | --- |
| 图片缩略图 | `Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(surface))` + `border(1.dp, border, shape)` | 内部 `AsyncImage` fill |
| 上传环 | `Canvas` 绘制两圆 | `strokeWidth = 2.dp`、半径按 9/24 比例、`startAngle = -90f`、`sweep = progress*360`、底色 `border`、进度色 `brand`（失败 `destructive`） |
| 百分比文字 | `Text` | `7.sp`、`FontWeight.SemiBold`、居中 |
| 文件 pill | `Row(Modifier.height(48.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, border).background(surface).padding(start=6.dp, top=6.dp, bottom=6.dp, end=24.dp))` | `spacedBy(8.dp)` |
| 图标盒 | `Box(Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)).background(background))` | 图标 16.dp |
| 文本 | `Column(Modifier.widthIn(max = 160.dp))` | 文件名 14.sp/500，元信息 12.sp/400 subtle |
| 进度/失败文本 | `Text(Modifier.widthIn(max = 112.dp))` | 12.sp，失败 `destructive` |
| 重试 | `IconButton(Modifier.size(20.dp))` | `RoundedCornerShape(6.dp)`、图标 12.dp、`destructive` |
| 删除 | `Box(Modifier.size(14.dp).clip(CircleShape).background(primary))` | 图标 10.dp、`primaryForeground`；hover 显示，触屏常显 |

### 6.3 浮层

| Web | Compose | 参数 |
| --- | --- | --- |
| DropdownMenu | `DropdownMenu` + `DropdownMenuItem` | 容器 `RoundedCornerShape(8.dp)`、`background(menu)`、`border(1.dp, popoverBorder)`、`padding(4.dp)`、`verticalArrangement = spacedBy(2.dp)`、`tonalElevation`/`shadow(6.dp)`；项 `MinHeight 28.dp`、`RoundedCornerShape(6.dp)`、`padding(h=8,v=4)` |
| Popover | `Popup` + 自绘 `Column` | `width = 288.dp`、`RoundedCornerShape(12.dp)`、`background(popover)`、`border(1.dp, border)`、`padding(10.dp)`、`spacedBy(16.dp)` |
| ContextMenu | `DropdownMenu`（在 `pointerInput` 长按处展开） | `minWidth = 176.dp`、其余同 DropdownMenu |
| Tooltip | `TooltipBox` / `PlainTooltip` 自绘 | `maxWidth = 320.dp`、`RoundedCornerShape(8.dp)`、`background(tooltip)`、`border(1.dp, border)`、`padding(h=12,v=6)`、12.sp；kbd `16.dp` 高、`RoundedCornerShape(6.dp)`、`background(tooltipTag)`、10.sp |
| Dialog | `Dialog` + `DialogProperties(usePlatformDefaultWidth = false)` | 内容 `widthIn(max = screenWidth - 32.dp)`、`RoundedCornerShape(16.dp)`、`background(popover)`、`border(1.dp, popoverBorder)`、`padding(16.dp)`、`spacedBy(16.dp)`；遮罩 `Color.Black.copy(alpha=.60f)` |
| AlertDialog | `AlertDialog` 自绘 | `RoundedCornerShape(16.dp)`、`background(popover.copy(alpha=.98f))`、`padding(20.dp)`、`spacedBy(20.dp)`、`widthIn(max = 448.dp)`；标题 16.sp/600，说明 14.sp/24.sp subtle |
| BottomSheet | `ModalBottomSheet` | `RoundedCornerShape(topStart=16.dp, topEnd=16.dp)`、`background(popover)`、`dragHandle` 自绘 `36×4.dp` 圆角条、底部 `navigationBarsPadding()` 对应 `safe-area + 12.dp` |
| SidePane | `AnimatedVisibility` + `slideInHorizontally` | 宽 `min(88% 屏宽, 448.dp)`、`shadow(24.dp)`、`border(left)`、200ms `FastOutSlowInEasing` |
| Toast | 自绘 `Box` + `AnimatedVisibility` | 容器 `RoundedCornerShape(16.dp)`、`background(toast.copy(alpha=.6f))`、`border(1.dp, border)`、`shadow(10.dp)`、`padding(h=16,v=12)`、`widthIn(max = 536.dp)`；3000ms 自动消失、200ms 退场 |

### 6.4 设置页

| Web | Compose | 参数 |
| --- | --- | --- |
| 内容容器 | `LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp))` + `widthIn(max = 896.dp)` | `verticalArrangement = spacedBy(32.dp)`；`lg` 32.dp 水平 |
| 标题 | `Text(style = titleLarge)` | 24.sp/600、`letterSpacing = (-0.02).sp`（tracking-tight） |
| 分组卡 | `Column(Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, border).background(card))` | 无内边距，行自带 |
| 行 | `Row(Modifier.fillMaxWidth().padding(h=16,v=12))` + `HorizontalDivider`（首行不画） | 两列：`weight(1f)` + `192.dp`（宽布局 280.dp），`spacedBy(16.dp)`，垂直居中 |
| 行标题/说明 | `Text` | 14.sp/500；说明 14.sp、行高 24.sp、`foregroundSubtle`、`padding(top=4.dp)` |
| 开关 | `Switch` 自绘 | 轨道 `32×18.dp`、`CircleShape`；开 `primary`、关 `primary.copy(alpha=.30f)`；拇指 `16.dp`、`primaryForeground`、右移 2.dp 内缩 |
| 下拉 | `ExposedDropdownMenuBox` | 触发器 `height 32.dp`、`RoundedCornerShape(8.dp)`、`padding(start=12,end=8)`、14.sp、右侧 14.dp chevron |
| 输入 | `OutlinedTextField` 自绘 | `height 32.dp`、`RoundedCornerShape(8.dp)`、`padding(h=12,v=6)`、14.sp |
| 按钮 | `Button` 自绘 | `height 32.dp`、`RoundedCornerShape(8.dp)`、`padding(h=10)`、14.sp、图标 16.dp |
| 危险按钮 | `Button(colors = destructive)` | 同尺寸 + `widthIn(min = 96.dp)` |
| 侧栏项 | `NavigationDrawerItem` 自绘 | `height 32.dp`、`RoundedCornerShape(12.dp)`、`padding(h=10)`、`spacedBy(8.dp)`、图标 16.dp、14.sp |
| 分组标题 | `Text` | 12.sp/500、`remoteForegroundMuted`、`padding(start=10, bottom=4)` |

---

## 7. 与既有文档的差异

### 7.1 对照 `docs/chat/06-composer.md`（3.15.3 基线）

| 项 | 3.15.3 文档 | 3.17.1 实测 | 影响 |
| --- | --- | --- | --- |
| 输入卡边框 | `border`（1px） | **`border-[0.5px]`** | 视觉更细；Compose 用 0.5dp 或 hairline |
| 输入卡阴影 | 无 | `shadow-(--shadow-composer-soft)`（zai-light 有值） | 新增柔光 |
| dock 水平内边距 | `px-4`（16px） | **`px-2`（8px）** | 输入区更宽 |
| 发送/停止圆角 | `rounded-lg`（8px） | **`rounded-full`** | 圆形按钮 |
| 停止按钮图标容器 | `icon-md` + `rounded-lg` | `icon-md` + `rounded-full` | 同上 |
| 模型下拉宽度 | `w-48 max-h-72` | 合并菜单 **`w-72`**；列表 `max-h-60` | 菜单更宽、含滑杆 |
| 模型/思考关系 | 两个独立控件 | **合并菜单**（模型 + 思考滑杆），仍保留独立 thought 控件分支 | 交互变化 |
| 思考滑杆 | 竖向进度条 | 新增 `composer-thought-slider`（h-8 横向、28px 拇指、档位点） | 新增组件 |
| dock 宽度过渡 | 无 | `transition-[width,max-width,transform] duration-150` | 新增动画 |
| 附件删除按钮 | `right-0.5 top-0.5 size-3.5` | 相同 | 无变化 |
| 队列卡片 | 无折叠、`-mb-7 pb-7` | 相同 | 无变化 |
| `bg-surface-raised` | CSS 0 命中 | 仍 0 命中 | 暂停横幅背景透明（既有缺陷） |
| 安全区 | 未实现 | 仍未实现（Toast/BottomSheet 才有） | 一致 |
| 浮层皮肤 | `border` + `bg-popover` | 菜单改 `border-popover-border` + `bg-menu`；Popover `rounded-xl` | 皮肤更新 |

### 7.2 对照 `docs/UI-SPEC.md`

| 项 | UI-SPEC 约定 | 3.17.1 网页实测 | 结论 |
| --- | --- | --- | --- |
| 输入坞圆角 | 16dp（rounded-2xl） | 16px | 一致 |
| 卡片圆角 | 12dp（rounded-xl） | 设置分组卡 12px、对话框 16px | 一致（按组件区分） |
| 输入最大行数 | 「最多 6 行」 | `max-h-40`=160px；桌面 20px 行高≈**8 行**，移动 24px≈6.6 行 | **差异**：应按像素夹逼，不按行数 |
| 附件入口 | 「回形针按钮」 | `+` 动作菜单，附件是其第一项 | **差异**：网页无常驻回形针 |
| 附件缩略图 | 48dp | `size-12`=48px | 一致 |
| 模型选择 | 「底部弹层」 | 锚定在输入框上方的 DropdownMenu | **差异**：不是 BottomSheet |
| Mode/Effort | 「与网页完全对齐」 | Mode 独立菜单 `w-64`；Effort 在合并菜单内滑杆 | 需按 3.17.1 更新 |
| 队列卡片 | 序号、文本、删除、立即发送 | **无序号**；有拖拽手柄、文本、立即/编辑/删除 | **差异**：无序号，多拖拽与编辑 |
| 待审批/待输入 | 输入区上方固定卡片 | 一致（`conversation-interactions` 在 composer 之前） | 一致 |
| 主题 | 跟随系统/深色/浅色 | `system / zai-dark / zai-light` | **差异**：主题名与数量 |
| 设置顺序 | 连接/外观/通知/关于 | 基础设置 / Agent 能力 / 数据与统计，共 21 个分区 | **差异**：信息架构不同 |
| 底部安全区 | 未提 | 输入区无安全区；Toast/BottomSheet 有 | 需在 Compose 侧显式补 `navigationBarsPadding()` |

---

## 8. 证据索引

### 8.1 `index-Dwhx77cK.js`（字符偏移）

| 偏移 | 内容 |
| --- | --- |
| 1002125 | v4 测试 id 全量常量（composer/queue/attachment/…） |
| 1213939 | 附件 chip 基类 `T6e`（grid/inline/list 变体） |
| 3142693 | 编辑器组件 `x2t`（Lexical、`editable:!disabled`） |
| 3130337 | 字号助手 `eJ`、发送判定 `tJ/J0t`、移动端 `G0t` |
| 3144161 | 编辑器 contenteditable 类、placeholder 类 |
| 3147251 | `+` 动作菜单 `E2t` |
| 3152682 | 容器折叠算法 `O2t` |
| 3154850 | Composer 表单 `j2t`（props、拖拽、Esc、附件行） |
| 3158794 | 输入卡外壳类（逐字） |
| 3159700 | 工具栏 + 取消/发送按钮 |
| 3213893 | `t6t` 打开态标签动画 |
| 3214959 | `QJ` 模型标签（`h-[1.3em] leading-[1.25]`） |
| 3326386 | `V5t` 思考指示条 |
| 3327065 | `H5t` 合并模型菜单（列表 + 滑杆） |
| 3329700 | 思考滑杆 DOM/几何 |
| 3336800 | provider 连接行/徽标 |
| 3339568 | 合并模型触发器 + 菜单宽度 |
| 3348047 | 思考控件静态/竖向条 |
| 3348689 | 思考 select 触发器 + 快捷键映射 |
| 3363383 | Mode 选择器 + Plan 标记 |
| 3409487 | 附件行/图片/文件/上传环 |
| 3412000 | 附件文本/进度/重试/删除 + 模型+停止/发送组 |
| 3418165 | `chat-composer-region` |
| 3419441 | `chat-composer-input-surface` |
| 3671700 | 队列条目 `li` |
| 3675858 | 队列容器 + 暂停横幅 |
| 3744050 | dock 宽度三档常量 |
| 4488160 | dock 定位与内边距 |
| 4706492 | `conversation-composer` 挂载 |
| 4708637 | dock 子元素顺序（quota → queue → interactions → composer → suggestions） |
| 925824 | Tooltip content + kbd |
| 924790 | Tooltip provider |
| 970584 | Dialog overlay |
| 971166 | Dialog content |
| 971819 | Dialog header/footer/title/description |
| 972916 | DropdownMenu content |
| 973776 | Dropdown item |
| 974690 | Checkbox item |
| 975660 | Radio item |
| 976463 | Label / separator / shortcut / sub |
| 977844 | Sub content |
| 2047942 | AlertDialog overlay/content |
| 2048463 | AlertDialog content |
| 2049405 | AlertDialog title/description/action/cancel |
| 2266038 | ContextMenu content |
| 1212138 | HoverCard content |
| 6821073 | BottomSheet |
| 5148736 | SidePane 抽屉 |
| 2477531 | Card 组件 |
| 2478921 | 设置内容容器 `FR` |
| 2480483 | 设置行 `IR` |
| 2481246 | 设置分组卡 `LR` |
| 6091600 | 表单成功/失败提示条 |
| 6332725 | 设置操作按钮 `Y6n` |
| 6338212 | 危险操作 + AlertDialog 实例 |
| 6382422 | 主题选项、设置分组/分区定义 |
| 6386226 | 常规设置分区 `c5n` |
| 6459441 | 设置侧栏导航项 `Y5n` |
| 6478700 | 设置 aside |
| 6480143 | 设置 nav / 分组标题 |
| 6480700 | 设置内容框架 |
| 4784248 | 设置面包屑 `wjn` |
| 1979179 | 设置搜索框 `MP` |
| 3098118 | 命令面板 `Wq`（+ 菜单内容） |
| 3101561 | 命令面板选项行 |
| 1797415 | Switch 组件 |
| 484565 | Select trigger / content |
| 485995 | Select item |
| 482700 | Select 触发器变体尺寸 |
| 4272461 | 内联输入框 `c2` |

### 8.2 `index-BgVjt5KE.css`（字符偏移）

| 偏移 | 内容 |
| --- | --- |
| 130 | `composer-thought-slider` 全套规则 + keyframes |
| 12096 | `--spacing:.25rem`、容器宽度、`--text-*` |
| 13978 | `--text-ui-settings-title(-lg)` |
| 152674 | `.text-ui-settings-title` |
| 153687 | `.text-ui-base` 等字号类 |
| 95328 | `.rounded-2xl` |
| 98287 | `.border-[0.5px]` |
| 125061 | `.bg-menu` / `.bg-menu-hover` |
| 131450 | `.bg-toast` / `.bg-toast\/60` |
| 131668 | `.bg-tooltip` / `.bg-tooltip-tag` |
| 171491 | `.shadow-(--shadow-composer-soft)` |
| 171690 | `.shadow-2xl` |
| 174181 | `.shadow-xl` / `.shadow-xs` |
| 177833 | `.ring-popover-border` |
| 417968 | `:root` 的 `--shadow-composer-soft:0 0 #0000` |
| 398535 起 | `.theme-zai-dark`（第一段） |
| 432975 起 | `.theme-zai-light`（含 `--shadow-composer-soft` 柔光） |
| 440701 起 | `.theme-zai-dark`（颜色覆盖） |
| 399013 | `.chat-composer-input-surface` / `.highspeed-composer-surface` |

### 8.3 其他 chunk

| 文件 | 内容 |
| --- | --- |
| `button-P7AG3MDX.js:2623` | 按钮基类 + 全部 variant/size |
| `pptxElementReference-C5CWcv9D.js:238632` | Toast 宿主/位置/变体/动效 |
| `pptxElementReference-C5CWcv9D.js`（`fo` 附近） | `input` 组件尺寸 |
| `pptxElementReference-C5CWcv9D.js`（`ho` 附近） | `popover-content` |
| `src-BSClmRM9.js:5334/5414` | `composerSend=Enter`、`composerInsertNewline=Shift+Enter` |
| `IntlProvider-CneXitqp.js:255145` | placeholder 文案 |
| `IntlProvider-CneXitqp.js:255444/257930` | 发送/停止 tooltip |
| `IntlProvider-CneXitqp.js:258485-258943` | 队列文案 |
| `IntlProvider-CneXitqp.js:309232-311529` | 附件文案 |
| `IntlProvider-CneXitqp.js:321426/321579` | Mode 名称/说明 |
| `IntlProvider-CneXitqp.js:267853` | 思考档位文案 |
| `IntlProvider-CneXitqp.js:25549-25668` | `+` 菜单快捷键说明 |
| `IntlProvider-CneXitqp.js`（`settings.title` 段） | 设置标题/分组名 |

---

## 9. 未验证 / 不可从产物确认

1. **0.5px 边框在 Android 的渲染**：Compose `BorderStroke(0.5.dp)` 在低密度屏可能被取整为 1px；需真机验证。网页用 `border-width:.5px`，高 DPI 下为半像素。
2. **`--color-ring` 未定义**：思考滑杆的 `:has(input:focus-visible)` outline 实际不生效（CSS 变量缺失）。Compose 侧不应照抄该 outline。
3. **`bg-surface-raised` 未生成**：队列暂停横幅背景透明，无法确认设计意图是「透明」还是「漏生成 token」。本文按「透明」记录。
4. **`shadow-(--shadow-composer-soft)` 的实际观感**：zai-light 为两层极淡阴影（4% 黑），其余主题为 none；Compose 无法 1:1 复现 CSS 双层阴影，只能用近似 elevation。
5. **主题默认值**：产物包含 `system/zai-light/zai-dark` 三选项，但未运行应用，无法确认首次启动落在哪套；本文以 zai 两套为主表。
6. **`text-ui-settings-title-lg` 的断点**：类定义在 `@media (width>=64rem)`（1024px）内，未在真机验证是否与 `lg:px-8` 同步生效。
7. **Toast 的 `bg-toast/60` 在深色下的实际混色**：`color-mix(in oklab, var(--color-toast) 60%, transparent)`，最终像素依赖背景，无法静态给出单一色值。
8. **Radix 的 modal 行为细节**（滚动锁、焦点圈定、`onInteractOutside` 例外）来自库语义与调用点推断，未逐组件运行验证。
9. **队列/待交互/输入区在 dock 中的精确 DOM 层级**：由 `conversation-queue`/`conversation-interactions`/`conversation-composer` 的 JSX 顺序确认，但外层包裹组件（`Can`/`dTn`）的动画容器未逐一展开。
10. **移动端输入坞的实际安全区**：composer 无 `env(safe-area-inset-bottom)`，但移动壳层是否在更外层补 padding 未验证。
