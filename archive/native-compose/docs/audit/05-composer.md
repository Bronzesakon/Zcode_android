# 05 · 输入区与工具栏：网页规范 × Android 现状审计

> **审计基线**：archive/pre-3.16.1-auth/Reference_web/3.15.3（2026-10-15 快照）
> **网页证据**：`archive/pre-3.16.1-auth/Reference_web/3.15.3/assets/index-BGa3KiRe.js`（6,592,643 B）、`index-BD2BL4Cc.css`（455,351 B）、`IntlProvider-C-26GNLk.js`（855,577 B）、`button-B-6dbTqe.js`（5,317 B）
> **Android 证据**：`android/app/src/main/kotlin/app/zemote/ui/chat/ChatScreen.kt`、`ModelSheet.kt`、`ModelCatalog.kt`
> **偏移口径**：UTF-8 字节偏移（`Buffer.indexOf`）。未注明文件时均指 `index-BGa3KiRe.js`；`CSS:` 前缀指 `index-BD2BL4Cc.css`；`Intl:` 指 `IntlProvider-C-26GNLk.js`；`Button:` 指 `button-B-6dbTqe.js`。
> **只读声明**：本次仅静态读取与取证，未执行 Reference_web 中任何代码，未修改任何源码；除本报告外无文件变更。
> **与既有文档的关系**：`docs/chat/06-composer.md` 已给出网页侧完整取证；本文复验其关键偏移，并补齐「控件尺寸 / 折叠规则 / 状态机」后逐项对照 Android。

---

## 0. 结论摘要

1. **输入卡片 shell 对齐度最高**：16px 圆角、12px 内边距、12px 子项间距、1px 默认/聚焦边框，Android 全部命中；hover 态按触屏省略属合理适配，唯一缺的是 `focus-within` 时的**背景色切换**（`input-focused`）。
2. **工具栏是最大观感偏差区**：网页所有控件统一 **28px 高 / 14px 字**；Android 实际高度混成 **28（+）、32（发送/停止）、约 40（模型 TextButton）、约 23（Mode/Effort chip）**，字号 **10sp**，一行里基线明显不齐。
3. **Mode 与 Effort 被做成了两个裸文本 chip**：网页是「图标 + 本地化名称 + 描述」的菜单；Effort 默认是 brain 图标 + 名称 + chevron，容器不足时退化为 **brain + 4px 竖向进度条（绿色 success 填充）**，再不足才收成纯图标。Android 直接显示 `build`/`high` 原始值，没有 Plan 复选框、没有图标、没有进度条。
4. **模型选择器的数据规则基本对齐**：默认 provider `glm` 省略前缀、当前值不在目录时不合成菜单项、按 provider 分组，Android 都做到了；网页是 provider 二级子菜单，Android 用平铺分组 + 底部 sheet，属于合理适配。但触发按钮缺 provider 前缀分离、缺 chevron、溢出时收成 package 图标的折叠未实现。
5. **附件只实现了文件 pill 的骨架**：没有图片缩略图、没有 `queued/uploading/committing/ready/failed` 状态机、没有失败重试，文件名 12sp（网页 14sp）。
6. **队列卡片与待交互卡片顺序颠倒**：网页是「队列 → 待交互 → 输入区」，Android 是「待交互 → 队列 → 输入区」；队列还被简化成多行散卡，缺立即发送/编辑/删除（Rust 侧 `sendQueuedNow`/`editQueueItem`/`deleteQueueItem` 已可用，仅 UI 未接）。
7. **待交互卡片配色与结构不符**：网页是 `bg-popover` + `border-border` + `rounded-2xl`，选项是整行 listbox；Android 用了蓝色 `interactionAskSurface` + warning 35% 边框，选项是横排 TextButton。
8. **Android 特有缺口**：输入区只有 `imePadding()`，没有 `navigationBarsPadding()`；在 `enableEdgeToEdge()` 下，键盘收起时底部 16dp 内边距可能被系统导航条压住。

---

## 1. 输入卡片 shell

### 1.1 规范

| 元素 | 网页值 / 结构 | 证据偏移 |
| --- | --- | --- |
| 卡片外壳 | `relative flex flex-col gap-3 overflow-hidden rounded-2xl border border-input-border bg-input p-3 transition-colors` | 2093538 |
| 圆角 | `rounded-2xl` = 1rem = **16px** | CSS:10343 |
| 内边距 / 子项间距 | `p-3` = 12px；`gap-3` = 12px | 2093538 |
| 默认边框 | `border-input-border` → `--color-input-border` → `--color-border`：浅色黑 10% / 深色白 10% | CSS:14276、CSS:12844、CSS:400653、CSS:399277 |
| hover 边框 | `hover:border-input-border-hover`：浅色黑 20% / 深色白 30% | 2093538、CSS:14317、CSS:13018、CSS:400694、CSS:399438 |
| focus 边框 | `focus-within:!border-input-border-focused` → brand（浅 `sky-400` #00bcff / 深 `sky-500` #00a6f4） | 2093685、CSS:14370、CSS:11831、CSS:400747、CSS:398312 |
| 默认背景 | `bg-input`：浅白 / 深 `neutral-800` #262626 | CSS:14197、CSS:400567 |
| focus 背景 | `focus-within:bg-input-focused`：浅 `neutral-50` / 深 `neutral-950` | 2093685、CSS:14230、CSS:400606 |
| 拖拽高亮 | `border-brand bg-input-focused ring-1 ring-brand/30` | 2093769 |
| 过渡 | `transition-colors`，150ms | CSS:10917 |
| 宽度 | 卡片 `w-full`，不设 max-width；宽度由 dock 内容层决定 | 2319850、2726932 |
| dock 内边距 | 非居中空态 `px-4 pb-4`（左右/底各 16px，**无顶部**） | 3474014 |
| 底部安全区 | **没有** `env(safe-area-inset-bottom)`；全包唯一安全区工具类未被 composer 使用 | CSS:27997、3474014 |
| 空态 | 居中时 `max-w-2xl`（672px）+ `mt-3`，不加 `px-4 pb-4` | 2319436、3473717 |

### 1.2 对照

| 元素 | 网页值 / 结构 | 证据偏移 | Compose 现状 | 差距 | 优先级 |
| --- | --- | --- | --- | --- | --- |
| 卡片圆角 | 16px | 2093538 / CSS:10343 | `RoundedCornerShape(16.dp)`，`ChatScreen.kt:593` | 一致 | — |
| 内边距 / 间距 | 12px / 12px | 2093538 | `padding(12.dp)` + `spacedBy(12.dp)`，`ChatScreen.kt:600-601` | 一致 | — |
| 默认边框 | 1px 10% | 2093538 | `colors.inputBorder`，`ChatScreen.kt:595-599` | 一致 | — |
| focus 边框 | brand | 2093685 | `colors.inputBorderFocused`，`ChatScreen.kt:597` | 一致 | — |
| focus 背景 | `input-focused` | 2093685 / CSS:14230 | 未使用 `colors.inputFocused`（已定义，`Color.kt:592`） | 聚焦时卡片仍为白底/深灰底，缺一层状态反馈 | P2 |
| hover 边框 | 20%/30% | 2093538 / CSS:14317 | 无 hover 处理 | 触屏无 hover，属合理适配（T2）；`inputBorderHover` token 已备 | P2 |
| 拖拽高亮 | brand 边框 + ring | 2093769 | 无 | 移动端无拖放，T2 | P2 |
| 宽度 | `w-full` | 2319850 | `fillMaxWidth()`，`ChatScreen.kt:590-592` | 一致（手机全宽合理） | — |
| 外边距 | 左右/底 16px | 3474014 | `start/end/bottom = 16.dp`，`ChatScreen.kt:586` | 一致 | — |
| 底部安全区 | 网页无；Android 需要 | 3474014 | 仅 `imePadding()`，`ChatScreen.kt:584`；`MainActivity` 已 `enableEdgeToEdge()` | 键盘收起时底部可能压住导航条 | **P0（Android 适配）** |
| 空态居中 | `max-w-2xl` 居中 | 2319436 | 无「新会话空态」分支，始终时间线 + 输入区 | 未实现空态布局 | P2 |

> 复刻口径：网页「三态边框」是 **默认 / hover / focus**；Android 触屏应保留 **默认 / focus（边框+背景）**，hover 可保留 token 但不必实现。

---

## 2. 编辑器

### 2.1 规范

| 元素 | 网页值 / 结构 | 证据偏移 |
| --- | --- | --- |
| 编辑器 | Lexical `contenteditable`，`data-testid=chat-input` | 2078646、2080066 |
| 最小高度 | `min-h-10` = **40px** | 2080066 |
| 最大高度 | `max-h-40` = **160px**，超出 `overflow-y-auto` | 2080066 |
| 字号/行高（桌面） | `text-ui-base leading-5` = **14/20** | 2067850、CSS:11189、CSS:396932 |
| 字号/行高（移动 web-remote） | `text-mobile-input-safe leading-6` = **16/24** | 2067850、CSS:11436 |
| 行数 | 无 `rows`；按像素裁切（桌面 160/20 = 8 行；移动 160/24 ≈ 6.6 行） | 2080066 + 2067850 |
| 占位层 | `pointer-events-none absolute left-0 top-0`；compact 加 `line-clamp-2`；色 `foreground-subtlest` | 2079880 |
| 占位分支 | 新任务：「向 ZCode 提问，使用 @ 添加上下文，使用 / 选择命令或能力」；移动端短版「向 ZCode 提问…」；有历史未运行「提出后续修改要求」；运行中「继续输入以排队后续修改」 | 2105740、Intl:287988、288094、288149、288207 |
| Enter | 桌面 Enter 发送 / Shift+Enter 换行；移动 web-remote `enterSubmits=false`，**Enter 只换行** | 2067589、2071447、2297715 |
| IME 组合 | `isComposing` 时不发送 | 2071447 |

### 2.2 对照

| 元素 | 网页值 / 结构 | 证据偏移 | Compose 现状 | 差距 | 优先级 |
| --- | --- | --- | --- | --- | --- |
| 编辑器实现 | Lexical contenteditable | 2078646 | `BasicTextField`，`ChatScreen.kt:677-700` | 等价实现 | — |
| 最小高度 | 40px | 2080066 | `heightIn(min = 40.dp)`，`ChatScreen.kt:683` | 一致 | — |
| 最大高度 | 160px + 内部滚动 | 2080066 | `heightIn(max = 160.dp)`，`ChatScreen.kt:683` | 一致 | — |
| 字号/行高 | 移动 16/24 | 2067850 | `ZemoteType.mobileInputSafe`（16sp/24sp），`Type.kt:95` | 一致；sp 跟随系统字号属合理适配（T2） | — |
| 行数上限 | 按像素裁切 ≈6.6 行 | 2080066 | 额外 `maxLines = 8`，`ChatScreen.kt:685` | 实际被 160dp 截断，`maxLines=8` 冗余；不影响结果 | P2 |
| 占位分支 | 4 种 | 2105740 | running / hasHistory / else 三种，`ChatScreen.kt:672-676` | 与移动端短版一致（新任务短文案） | — |
| 占位色 | `foreground-subtlest` | 2079880 | `colors.foregroundSubtlest`，`ChatScreen.kt:694` | 一致 | — |
| 占位截断 | compact `line-clamp-2` | 2079880 | 无 `maxLines`，可无限换行 | 长占位会撑高卡片 | P2 |
| Enter | 移动端只换行 | 2067589 | `ImeAction.Default`，`ChatScreen.kt:687` | 一致 | — |

---

## 3. 工具栏骨架

### 3.1 规范

| 元素 | 网页值 / 结构 | 证据偏移 |
| --- | --- | --- |
| 工具栏 | `group/toolbar flex items-end gap-3` | 2094468 |
| 左区 | `flex min-w-0 flex-1 items-center`，`data-composer-leading-actions` | 2094468 |
| 左区内容 | `flex shrink-0 items-center gap-1`（4px） | 2094468 |
| 右区 | `ml-auto flex shrink-0 items-center justify-end gap-1.5`（6px） | 2094468 |
| 控件基准尺寸 | 按钮基类 `text-ui-base/relaxed`；`default` = `h-7`（28px）；`icon-md` = `size-7`（28px）+ `rounded-lg`（8px）+ 图标 16px；`icon-lg` = `size-8`（32px） | Button:2210、Button:4853 |
| 禁用态 | 基类 `disabled:opacity-50` | Button:2210 |
| 窄容器折叠 | 按 `data-composer-collapse-priority` 0→3 依次隐藏标签，然后隐藏 provider 前缀，再把 Effort 收成图标，最后把模型按钮收成纯图标 | 2087915、2088938、2088709、2288637 |

### 3.2 对照

| 元素 | 网页值 / 结构 | 证据偏移 | Compose 现状 | 差距 | 优先级 |
| --- | --- | --- | --- | --- | --- |
| 工具栏间距 | 左右区 `gap-3` = 12px | 2094468 | `spacedBy(12.dp)`，`ChatScreen.kt:704` | 一致 | — |
| 垂直对齐 | `items-end` | 2094468 | `Alignment.CenterVertically`，`ChatScreen.kt:703` | 控件高度不齐时基线错位 | P2 |
| 左区内容间距 | `gap-1` = 4px | 2094468 | 无间距（`+` 与 Mode 紧贴），`ChatScreen.kt:706-724` | 缺 4dp | P1 |
| 右区间距 | `gap-1.5` = 6px | 2094468 | `spacedBy(6.dp)`，`ChatScreen.kt:727` | 一致 | — |
| 控件高度 | 全部 28px | Button:4853 | `+` 28、发送/停止 32、模型 TextButton 40（M3 默认最小高）、Mode/Effort chip ≈23 | **一行四种高度**，最影响观感 | **P0** |
| 控件字号 | 14px | Button:2210 | Mode/Effort/模型均 `uiXs` = 10sp，`ChatScreen.kt:736、859` | 整体小一号 | **P0** |
| 窄容器折叠 | 有完整优先级算法 | 2087915 | 完全没有 | 手机宽度下网页会逐步收成图标；Android 始终满排，长模型名靠 110dp 截断 | P2（T2 可接受，但需产品决策） |

---

## 4. `+` 动作菜单

### 4.1 规范

| 元素 | 网页值 / 结构 | 证据偏移 |
| --- | --- | --- |
| 触发按钮 | `q` ghost / `icon-md`（28px / 8px 圆角 / 16px plus 图标） | 2085984、Button:4853 |
| aria / 文案 | 「添加上下文」（`chat.composer.actionMenu`） | 2091576、Intl:357940 |
| 行为 | 点击打开 **菜单**，不是直接开文件选择器 | 2083089、2085600 |
| 菜单方向 | `side:"top"`、`align:"start"`、`sideOffset:0`、宽度等于触发按钮宽度、`max-w-[calc(100vw-1rem)]` | 2086300 |
| 分区 | `add`（附件 / 目标 goal / 工作流 workflow）→ `plugins` → `files` → `sessions`；`add` 为空时整体不渲染 | 2083400-2084700、2085000 区 |
| 附件项 | 回形针 `paperclip` + `text-ui-base font-medium`，`data-testid=chat-attachment-menu-item` | 2083089 区、Intl:357389 |
| 菜单项尺寸 | 图标 16px、单行截断 | 2084600 区 |

### 4.2 对照

| 元素 | 网页值 / 结构 | 证据偏移 | Compose 现状 | 差距 | 优先级 |
| --- | --- | --- | --- | --- | --- |
| 触发按钮 | ghost 28px、rounded-lg、16px 图标 | 2085984 / Button:4853 | `IconButton(28.dp)` + 20dp 图标，`ChatScreen.kt:710-717` | 图标偏大 4dp；无 8dp 圆角底 | P2 |
| 点击行为 | 打开动作菜单 | 2085600 | 直接 `picker.launch("*/*")`，`ChatScreen.kt:246` | 无菜单层 | P1 |
| 菜单分区 | add / plugins / files / sessions | 2083400-2084700 | 无 | 缺 goal、workflow、plugins、files、sessions 五类入口 | P1 |
| 附件入口 | 菜单第一项「附件」 | 2083089 | 由 `+` 直接替代 | 功能可达，但不符合原结构 | P1 |
| 菜单定位 | top / start / 触发宽度 | 2086300 | 无 | — | P1（随菜单一起补） |

> Android 若短期不做完整菜单，至少应把 `+` 的行为与 web 的「第一项附件」对齐，并在图标/尺寸上保持 28px/16px；goal/workflow 属于功能缺口，不是纯视觉。

---

## 5. Mode 选择器

### 5.1 规范

| 元素 | 网页值 / 结构 | 证据偏移 |
| --- | --- | --- |
| 触发按钮 | ghost / `sm` + 显式 `h-7 gap-1 rounded-lg px-2 text-ui-base`；图标 16px + 名称 + chevron 14px | 2272248 |
| 图标映射 | `build/default` → `hand`；`plan` → `notepad-text`；`guarded` → `shield-check`；`yolo` → `shield-alert` | 2242036 |
| 颜色 | `yolo` → `text-warning`；`guarded` → `text-icon-blue` | 2272248 |
| 窄容器 | `data-composer-compact=true` → `w-7 px-0`，只留图标 | 2272287、2272320 |
| 下拉 | `side:"top"`、`sideOffset:4`、`w-64`（256px） | 2272775 |
| 首项 Plan | 复选框 `min-h-13`（52px）、`items-start gap-3 py-2`，切换 `plan` / `plan-off` | 2273018 |
| 其余选项 | `min-h-13`，图标 18px（`size-4.5`）+ 名称 + 描述；`data-testid=chat-mode-select-item` | 2273506、2245010 |
| 名称/描述 | build「变更前确认 / 改文件前先问我。」；guarded「自主模式 / 在有风险时询问」；plan「计划模式 / 编辑前先出计划。」；yolo「完全访问 / 减少确认次数。」 | Intl:369991-370200 |
| Plan 标记 | `v4-composer-plan-marker`：竖分隔线 + 「计划」+ 关闭按钮 | 2273879 |
| `edit` 兼容 | 当前值 `edit` 不在列表时用 `{id:"edit",name:"Edit"}` 兜底 | 2270968 |

### 5.2 对照

| 元素 | 网页值 / 结构 | 证据偏移 | Compose 现状 | 差距 | 优先级 |
| --- | --- | --- | --- | --- | --- |
| 触发外观 | 28px 高、8px 圆角、14px 字、16px 图标 | 2272248 | 胶囊 chip（999dp）、10sp、无图标，`ChatScreen.kt:718-723`、`841-881` | 形状/字号/图标全不符 | **P0** |
| 标签 | 本地化名称 | Intl:369991 | 显示原始 `build`/`guarded`/`yolo`，`ChatScreen.kt:719、859` | 未本地化 | **P0** |
| 图标 | hand / notepad / shield-check / shield-alert | 2242036 | 无 | 缺图标 | P1 |
| 颜色 | yolo warning、guarded icon-blue | 2272248 | 统一 `foregroundSubtle`，`ChatScreen.kt:859` | 缺语义色 | P2 |
| 下拉宽度 | 256px | 2272775 | 默认 DropdownMenu 宽度，`ChatScreen.kt:867` | 宽度随内容 | P2 |
| 选项结构 | 52px 高、图标+名称+描述 | 2273018、2273506 | 纯文本 14sp 菜单项 | 缺图标/描述/高度 | P1 |
| Plan 首项 | 复选框 + 独立 plan 状态 | 2273018 | 无 | **无 Plan 模式入口** | P1 |
| Plan 标记 | 竖线 + 计划 + 关闭 | 2273879 | 无 | 缺 | P1 |
| 选项集合 | build/guarded/yolo + plan 独立 | 2270968 | build/guarded/yolo；未知值追加，`ChatScreen.kt:796-804` | 集合基本一致，缺 plan | P1 |

---

## 6. 模型选择器

### 6.1 规范

| 元素 | 网页值 / 结构 | 证据偏移 |
| --- | --- | --- |
| 触发按钮 | ghost / `default` + `w-fit justify-between gap-1 rounded-lg pl-2 pr-1.5 text-ui-base whitespace-nowrap`（28px 高） | 2168094 |
| 当前值 | `providerPrefix`（独立 span，类 `composer-provider-prefix`）+ `modelLabel`；默认 provider `glm` 省略前缀 | 2287833、1770155 |
| 前缀折叠 | 容器不足时先隐藏 `composer-provider-prefix` | 2088709 |
| 标签可见性 | composer 传 `triggerLabelClassName`（`block ... group-data-[composer-model-icon=true]/toolbar:hidden`），`labelVisibilityClassName` 被 `wNt` 覆盖、不生效；标签始终显示 | 2287833、2164097 |
| 收成纯图标 | 工具栏溢出算法最后一步设 `data-composer-model-icon=true` → `size-7 p-0`，标签隐藏、显示 package 图标（`Sl`） | 2088938、2288637、115803 |
| 右侧指示 | chevron-down 14px `text-foreground-subtle` | 2168570 |
| 加载态 | loader 14px `animate-spin` | 2168473 |
| 下拉位置/尺寸 | `side:"top"`、`align:"start"`；有 provider 分组时 `w-max min-w-48 max-w-[calc(100vw-2rem)]`；无分组时 `w-48 max-h-72 overflow-y-auto`（192×288） | 2168853、2168895 |
| 分组 | 有分组时始终渲染 provider 二级子菜单：`min-h-8` 头部 + 子列表 `max-h-72`；`showProviderLevel=false` 才退化为单组平铺 | 2168700、2163095 |
| 选中 | `data-model-option-selected=true` + 右侧 check 16px | 2164885 |
| 锁定 | `data-model-option-locked=true`，禁用 + 运行中提示 | 2164842 |
| 底部动作 | sticky footer `data-model-footer-action`（可含「管理模型」） | 2170117、2170337 |
| 当前值不在目录 | 不合成菜单项；未命中时触发按钮回退到 fallback 文案（composer 未开 `canManage`，通常无 value/placeholder） | 2253701 |

### 6.2 对照

| 元素 | 网页值 / 结构 | 证据偏移 | Compose 现状 | 差距 | 优先级 |
| --- | --- | --- | --- | --- | --- |
| 触发按钮 | 28px 高、14px 字、pl-2/pr-1.5 | 2168094 | M3 `TextButton`（最小 40dp）、`uiXs` 10sp、`widthIn(max=110.dp)`，`ChatScreen.kt:729-743` | 高度/字号/内边距不符 | **P0** |
| 当前值格式 | `provider/model`，glm 省略前缀 | 1770155 | `modelLabel()` 同规则，`ChatScreen.kt:883-889` | 一致 | — |
| 前缀独立折叠 | `composer-provider-prefix` | 2088709 | 单字符串，无法只隐藏前缀 | 折叠能力缺失 | P2 |
| chevron | 14px subtle | 2168570 | 无 | 缺指示箭头 | P1 |
| 溢出折叠 | 工具栏放不下时最后收成 28px package 图标 | 2088938、2288637 | 始终显示截断文本（max 110dp） | 手机窄屏下网页会收成图标；Android 保留文字可读性更好，可视为 T2 | P2 |
| 打开形态 | 锚定 popover（top/start） | 2168895 | 全屏宽底部 sheet，`ModelSheet.kt:65-84` | 移动端合理适配（T2） | — |
| 分组 | provider 二级子菜单 | 2168700 | 平铺 provider 分组（头部 + 全部模型），`ModelSheet.kt:143-169` | 结构与网页不同；触屏上平铺更易用，属 T2 | P2 |
| 选中 | check 16px | 2164885 | check 16dp，`ModelSheet.kt:194-201` | 一致 | — |
| 当前值不在目录 | 不合成项 | 2253701 | 不合成；顶部显示当前 provider/model，`ModelSheet.kt:91-107` | 一致（更明确） | — |
| 锁定项 | 运行中锁定 + 提示 | 2164842 | 无锁定，运行中仍可切 | 行为差异 | P2 |
| 底部「管理模型」 | sticky footer | 2170337 | 无（设置页另有入口） | T2 | — |
| 目录来源 | `model-selection.getView` | 2270128 | 同源解析，`ModelCatalog.kt:23-44` | 一致 | — |

---

## 7. Effort（思考等级）

### 7.1 规范

| 元素 | 网页值 / 结构 | 证据偏移 |
| --- | --- | --- |
| 图标 | `brain` 16px `text-current` | 2248979 |
| 竖向进度条 | `w-1 self-stretch rounded-full bg-current/10`（4px 宽）；内部 `bg-success`，高度按档位百分比；`transition-[height] duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)]` | 2249090、2249387 |
| 进度计算 | `(当前档位在有效档位中的序号 + 1) / 有效档位数 × 100%`；排除 `disabled/false/no/none/...` | 2247400 区、2245617 区 |
| 触发按钮 | ghost `select` `default` + `gap-1 rounded-lg px-1.5 py-1.5 text-ui-base`（28px 高） | 2249796 |
| 下拉 | Radix Select `popper`、`side:"top"`、`align:"start"`、`sideOffset:4` | 2243388 |
| 档位来源 | 模型目录 `config.optionSpecs.reasoningLevel.values`；缺失/空则不显示控件 | 2270128 |
| 档位文案 | `first-letter:uppercase`；本地化：关闭/开启/极低/低/中/高/极高/最高/极致 | 2243740、Intl:304400-304560 |
| 单档位 | 静态 span（`data-thought-level-fixed=true`），不弹菜单 | 2249878 |
| 非 select | 普通按钮，点击循环 | 2251100 |
| 非法当前值 | 默认不显示；`showInvalidCurrentValue` 才显示原值 | 2247200 区 |
| 空值 | `chat.toolbar.thoughtLevel.placeholder`「选择思考档位」 | 2247300 区、Intl:304290 |
| 三态外观 | ① 默认：brain + 名称 + chevron；② `data-composer-compact=true`（折叠优先级 3 命中）：brain + 竖向进度条，名称与 chevron 隐藏；③ `data-composer-compact=icon`：仅 brain，按钮收成 `size-7` | 2249090、2249163、2247400 区 |
| 进度条显示条件 | 独立 `LH` 且未传 `composerCollapsePriority` 时按容器宽 `hidden @sm/composer:inline-flex @xl/composer:hidden`；composer 传入 priority=3，因此只在 `compact=true` 状态显示 | 2249090、2289052 |
| 标签断点 | composer 调用显式传 `labelVisibilityClassName:inline-flex`（始终显示名称）；compact=true 时 `group-data-[composer-compact]/thought:hidden` | 2249163、2289052 |

### 7.2 对照

| 元素 | 网页值 / 结构 | 证据偏移 | Compose 现状 | 差距 | 优先级 |
| --- | --- | --- | --- | --- | --- |
| brain 图标 | 16px | 2248979 | 无 | 缺 | **P0** |
| 竖向进度条 | 4px 宽、绿色填充、高度=档位百分比；属于 `compact=true` 中间态 | 2249090、2249387 | 无 | 缺 | **P0** |
| 三态折叠 | 默认名称 / compact 进度条 / icon 纯图标 | 2249090、2249163 | 始终一个文本 chip | 无折叠态 | P1 |
| 触发外观 | 28px 高、8px 圆角、14px 字 | 2249796 | 胶囊 chip、10sp，`ChatScreen.kt:744-751`、`841-881` | 形状/字号不符 | **P0** |
| 档位文案 | 本地化（低/中/高…） | Intl:304400 | 原始 `low`/`high`，`ChatScreen.kt:746` | 未本地化 | **P0** |
| 档位来源 | 仅模型目录 | 2270128 | 目录为空时回退 `config.thoughtLevels`，`ChatScreen.kt:228-233` | 网页不显示时 Android 仍显示 | P1 |
| 单档位 | 静态 span | 2249878 | 仍可点开菜单 | 多一次无意义交互 | P2 |
| 下拉定位 | popper top/start | 2243388 | 默认 DropdownMenu | 位置随锚点，基本可用 | P2 |
| 空值占位 | 「选择思考档位」 | 2247300 | 无（levels 为空直接不渲染） | 一致（不渲染） | — |
| 非法当前值 | 默认隐藏 | 2247200 | 直接显示原值 | 小偏差 | P2 |

---

## 8. 发送 / 停止按钮

### 8.1 规范

| 元素 | 网页值 / 结构 | 证据偏移 |
| --- | --- | --- |
| 发送 | `type=submit`、`size=icon-md`（28px / 8px 圆角）、`bg-brand text-foreground-inverse hover:bg-brand/80`、图标 `arrow-up` 16px | 2318403、Button:4853 |
| 发送中 | 图标换 loader 16px，按钮 disabled | 2318510 |
| 禁用 | 基类 `disabled:opacity-50` | Button:2210 |
| 停止 | `variant=secondary`、`size=icon-md`（28px），图标 `square` 16px `fill-current` | 2318049 |
| 停止显示条件 | `canStop && 输入为空`（`Cn = gn && !vn`） | 2304664 |
| 切换 | 条件渲染二选一，**无交叉淡入/旋转动画** | 2317945 / 2318403 |
| tooltip | 发送「发送」/「加入队列」+ Enter；停止「停止生成」+ Esc | 2318300 区、Intl:288450、292203、291509 |

### 8.2 对照

| 元素 | 网页值 / 结构 | 证据偏移 | Compose 现状 | 差距 | 优先级 |
| --- | --- | --- | --- | --- | --- |
| 发送尺寸 | 28px | 2318403 | 32dp，`ChatScreen.kt:772-789` | 大 4dp | P1 |
| 发送底色/图标 | brand + 16px arrow-up | 2318403 | `colors.brand` + 16dp ArrowUpward | 一致 | — |
| 禁用态 | opacity 50% | Button:2210 | brand alpha 0.4，`ChatScreen.kt:777` | 近似 | P2 |
| 发送中 | loader 图标 + disabled | 2318510 | 图标仍为 arrow-up，仅禁用点击，`ChatScreen.kt:770-786` | 缺 loading 反馈 | P2 |
| 停止尺寸 | 28px secondary | 2318049 | 32dp secondary，`ChatScreen.kt:753-768` | 大 4dp | P1 |
| 停止图标 | 16px 实心方 | 2318049 | 16dp `Icons.Default.Stop` | 一致 | — |
| 显示条件 | 运行中且输入为空 | 2304664 | `running && value.isBlank()`，`ChatScreen.kt:753` | 一致 | — |
| 切换动画 | 无 | 2317945 / 2318403 | 条件渲染 | 一致 | — |
| tooltip/快捷键 | 有 | 2318300 | 无 | 触屏无 tooltip，T2 | — |

---

## 9. 附件 chips 与上传进度

### 9.1 规范

| 元素 | 网页值 / 结构 | 证据偏移 |
| --- | --- | --- |
| 两行 | 文件附件行 `data-composer-file-attachments-row`；上下文附件行 `data-composer-context-attachments-row`（评论/网页/引用等） | 2311467、2316469 |
| 图片 chip | `size-12`（48×48）、`rounded-lg`、`bg-surface`、`overflow-hidden`，`after` 内描边 `border-border` | 2312354 |
| 文件 pill | `h-12`（48px）、`w-fit max-w-full`、`gap-2`、`rounded-lg`、`border border-border`、`bg-surface`、`p-1.5 pr-6`、hover `bg-surface-hover` | 2312526 |
| 图标框 | `size-9`（36px）、`rounded-md`、`bg-background`；文件图标 16px | 2313340 |
| 文件名 | `text-ui-base font-medium` 单行截断，最大宽 160px | 2314553、2314624 |
| 元信息 | `text-ui-sm text-foreground-subtle`，内容是**扩展名大写**（PNG/PDF） | 2314861、2293710 |
| 删除按钮 | `size-3.5`（14px）圆形、`bg-primary`、`absolute right-0.5 top-0.5`；`opacity-0`，hover/focus 显示，无 hover 设备常显 | 2315925 |
| 上传进度（图片） | 48px 覆盖层 `bg-background/85`；SVG `viewBox 0 0 24 24`、`r=9`、`strokeWidth=2`、`pathLength=100`、`strokeDasharray="${progress} 100"`、`-rotate-90`；7px 百分比 | 2313730、2313799 |
| 上传进度（文件） | 进度文字 `NN%` 放在元信息行 | 2314241 |
| 失败（图片） | 圆环 `stroke-destructive` + 中心 `!` | 2313730 |
| 失败（文件） | 红色文字 + 20px 重试按钮（`rotate-ccw`） | 2315400、2315893 区 |
| 状态文案 | `queued`「等待上传」；`waitingSession`「正在等待会话」；`uploading`「正在上传 {progress}%」；`committing`「正在完成上传」；`ready`「上传完成」 | Intl:357279、357341、357389、357429、357510 |
| 发送门控 | `hasUnreadyAttachments = some(uploadStatus !== "ready")` → 禁用发送 | 2122957 |
| 数量上限 | `chat.attachments.maxFiles` | Intl:355004 |

### 9.2 对照

| 元素 | 网页值 / 结构 | 证据偏移 | Compose 现状 | 差距 | 优先级 |
| --- | --- | --- | --- | --- | --- |
| 行结构 | 文件行 + 上下文行，`flex-wrap gap-2` | 2311467、2316469 | 单行 `horizontalScroll`，`ChatScreen.kt:603-670` | 无上下文附件行；横滚属窄屏合理适配（T2） | P2 |
| 图片 chip | 48×48 缩略图 | 2312354 | **始终文件图标**，无缩略图，`ChatScreen.kt:628-633` | 图片附件看不出内容 | P1 |
| 文件 pill | 48px 高、p-1.5 pr-6 | 2312526 | 48dp、6/2 padding、8dp 圆角、border，`ChatScreen.kt:612-618` | 基本一致（右侧留给删除按钮） | — |
| 图标框 | 36px / 6px 圆角 / background | 2313340 | 36dp / 6dp / `colors.background`，`ChatScreen.kt:621-627` | 一致 | — |
| 文件名 | 14px medium | 2314624 | 12sp，`ChatScreen.kt:642` | 小一号 | P1 |
| 元信息 | 12px，扩展名大写 | 2314861 | 9sp，字节大小，`ChatScreen.kt:650-655` | 字号小、内容不同 | P2 |
| 删除按钮 | 14px 圆、右上角、常显（触屏） | 2315925 | 28dp IconButton、行内右侧、常显，`ChatScreen.kt:657-667` | 偏大且位置不同 | P2 |
| 上传进度 | 48px 环 + 7px %（图片）/ 文本 %（文件） | 2313730、2314241 | 36dp 环 + 7sp %，`ChatScreen.kt:634-636、806-839` | 环偏小；仅当前附件显示 | P2 |
| 失败态/重试 | 有 | 2313730、2315400 | 无；`ChatAttachment` 没有 `uploadStatus` 字段，`ChatViewModel.kt:131-135` | 失败只能整条重发 | P1 |
| 状态文案 | 5 种 | Intl:357279-357510 | 只有 `NN%` 或大小 | 缺 queued/waitingSession/committing/ready | P1 |
| 发送门控 | 所有附件 ready 才可发 | 2122957 | `!uploading`，`ChatScreen.kt:770-771` | 单值状态，无法表达逐附件失败 | P2 |
| 数量上限 | 有 | Intl:355004 | `MAX_ATTACHMENTS`，`ChatViewModel.kt:325` | 一致 | — |

---

## 10. 队列卡片

### 10.1 规范

| 元素 | 网页值 / 结构 | 证据偏移 |
| --- | --- | --- |
| 位置 | dock 内、输入区上方；顺序为 **队列 → 待交互 → 输入区** | 3678323 区、3680006、3680211 |
| 容器 | `relative z-0 w-full overflow-hidden rounded-t-2xl border border-border bg-surface p-1 backdrop-blur-md` + `-mb-7 pb-7` | 2637766、2637871 |
| 空队列 | `items.length===0` 返回 `null` | 2636901 |
| 暂停横幅 | `mb-1 flex min-h-10 items-center gap-3 rounded-xl border border-border/70 bg-surface-raised px-3 py-2 text-ui-base` | 2637957 |
| 暂停文案 | stopped / error / 通用 | Intl:292684 起 |
| 继续按钮 | ghost `sm`，右对齐 | 2638364 |
| 队列项 | `relative flex items-center gap-2 rounded-xl px-1.5 py-1 pr-1 transition-colors hover:bg-hover/30` | 2634491 |
| 拖拽手柄 | ghost `icon-md`、`grip-vertical`、`cursor-grab` | 2634793 |
| 正文 | `text-ui-base` 单行/多行截断；compact 用 mono `/compact` | 2635150 |
| 来源/引用/附件 | 来源徽标 + 引用列表 + 附件名逗号拼接（12px subtle） | 2635300、2635476、2635541 |
| 立即发送 | secondary + `arrow-up-from-line` +「立即」 | 2635692 |
| 编辑 / 删除 | ghost icon-md pencil / trash-2 | 2636238、2636589 |
| 状态 | 不显示文字状态，用 `data-dispatch-state` | 2634334 |
| 动画 | 无入场动画；拖拽排序由 dnd-kit 提供 | 2637766 |

### 10.2 对照

| 元素 | 网页值 / 结构 | 证据偏移 | Compose 现状 | 差距 | 优先级 |
| --- | --- | --- | --- | --- | --- |
| 顺序 | 队列 → 待交互 → 输入区 | 3678323 区 | 待交互 → 队列 → 输入区，`ChatScreen.kt:206-213` | **顺序颠倒** | P1 |
| 容器 | 单张 `rounded-t-2xl` 卡片、p-1、`-mb-7 pb-7` 压入输入区 | 2637766、2637871 | 无整卡；每项一条 8dp 圆角 surface 行，`ChatScreen.kt:485-547` | 结构与重叠关系都不同 | P1 |
| 水平内边距 | dock `px-4` = 16px | 3474014 | 12dp，`ChatScreen.kt:488` | 差 4dp | P2 |
| 空队列 | 不渲染 | 2636901 | 一致，`ChatScreen.kt:484` | — | — |
| 暂停横幅 | 12px 圆角、min-h-10、`bg-surface-raised` | 2637957 | 12dp 圆角、surface 背景、0.7 边框，`ChatScreen.kt:490-518` | 基本一致；`surface-raised` 在 CSS 0 命中，Android 用 surface 属修复 | P2 |
| 队列项 | 12px 圆角、px-1.5 py-1、14px 正文 | 2634491、2635150 | 8dp 圆角、10/6、10sp 正文，`ChatScreen.kt:523-539` | 圆角/字号/内边距偏小 | P1 |
| 拖拽手柄 | 有 | 2634793 | 无 | 移动端长按拖拽可实现，T2/P2 | P2 |
| 立即 / 编辑 / 删除 | 三个动作 | 2635692、2636238、2636589 | 无 | **功能缺失**；Rust 侧 op 已就绪（`conversation.rs:113-116`） | P1 |
| 状态文字 | 无（data 属性） | 2634334 | 显示「排队中/待发送」，`ChatScreen.kt:540-544` | 多出非规范文案 | P2 |
| 附件/引用/来源 | 有 | 2635300-2635541 | 无 | 信息缺失 | P2 |

---

## 11. 待交互卡片（权限 / 用户输入）

### 11.1 规范

| 元素 | 网页值 / 结构 | 证据偏移 |
| --- | --- | --- |
| 位置 | dock 内，队列之后、输入区之前；内联卡片，不是全屏弹窗 | 3680211、3550505 |
| 外壳（用户输入） | `w-full shrink-0` → `relative z-1 flex w-full max-h-[min(72dvh,42rem)] flex-col overflow-hidden rounded-2xl border border-border bg-popover shadow-xs` | 3550505、3550578 |
| 内层 | `flex min-h-0 flex-1 flex-col gap-3 p-3`；body 可滚动 `overflow-y-auto overscroll-contain pr-1` | 3550505 |
| 外壳（权限） | `w-full overflow-hidden rounded-2xl border border-border bg-popover shadow-xs` + 内层 `flex flex-col gap-3 p-3` | 3582959、3583075 |
| 标题 | `text-ui-base font-medium leading-tight text-foreground-subtle` + 来源徽标 | 3583075 区 |
| 正文 | `text-ui-base leading-5 text-foreground` | 3583416 |
| 选项容器 | `role=listbox`，`space-y-1` | 3584139 |
| 选项行 | `flex w-full items-center gap-3 rounded-xl px-3 py-2 text-left`；选中 `bg-selected`，hover `bg-hover`；序号 `1.`（20px 列） | 3584980、3546894 |
| 自由输入 | 选项末尾追加 `textarea rows=1` | 3586681、3548000 |
| 键盘提示 | 「使用 Tab / 上下键选择，回车确认」 | 3587568、Intl:364041 |
| 倒计时 | 仅用户输入卡最后 60 秒显示 `{seconds}秒`；权限卡没有倒计时 | 3539294、3550162、3579711 |
| 选择规则 | 取 `pendingInteractions` 中第一条 permission/userInput | 3595800 |

### 11.2 对照

| 元素 | 网页值 / 结构 | 证据偏移 | Compose 现状 | 差距 | 优先级 |
| --- | --- | --- | --- | --- | --- |
| 位置/顺序 | 队列之后、输入区之前 | 3680211 | 队列之前，`ChatScreen.kt:206-213` | 顺序颠倒 | P1 |
| 外壳 | `bg-popover` + `border-border` + 16px 圆角 | 3550505、3582959 | `interactionAskSurface`（蓝底）+ warning 35% 边框 + 12dp 圆角，`ChatScreen.kt:441-448` | 配色/边框/圆角不符 | P1 |
| 最大高度 | `min(72dvh,42rem)` 内部滚动 | 3550578 | 无上限 | 长内容会顶满屏幕 | P2 |
| 标题 | 14px medium subtle | 3583075 | 12sp foreground，`ChatScreen.kt:450` | 字号/颜色偏差 | P2 |
| 正文 | 14px/20 foreground | 3583416 | 10sp subtle，`ChatScreen.kt:452-457` | 小两号 | P1 |
| 选项 | 整行 listbox、52px 高、序号、选中底 | 3584980 | 横排 TextButton、10sp，`ChatScreen.kt:459-471` | 结构与尺寸不符 | P1 |
| 自由输入 | textarea | 3586681 | 无 | 缺 | P1 |
| 键盘提示/底部确认 | 有 | 3587568 | 无 | T2（触屏无键盘提示） | — |
| 倒计时 | 仅用户输入最后 60s | 3539294 | 无 | 缺 | P1 |
| 权限卡无倒计时 | 是 | 3579711 | 无 | 一致 | — |
| 只显示第一条 | 是 | 3595800 | `firstOrNull()`，`ChatScreen.kt:428` | 一致 | — |

---

## 12. Android 合理适配（T2，不建议按网页原样改）

| 差异 | 理由 |
| --- | --- |
| 无 hover 三态、无 drag 高亮、无 ring | 触屏没有 hover；拖放上下文入口在移动端不成立。保留 `inputBorderHover` token 即可。 |
| 占位文案用移动短版「向 ZCode 提问…」 | 网页 `text-mobile-input-safe` 分支同款。 |
| Enter 只换行、点击按钮发送 | 网页 `isMobileTextInputViewport` 分支同款。 |
| 模型选择器用底部 sheet 而非锚定 popover | 手机窄屏下 sheet 更易点、可滚动；网页是贴按钮的二级子菜单，触屏命中率差。 |
| 模型菜单 provider 平铺而非二级 submenu | 触屏上二级子菜单可用性差；网页只在 `showProviderLevel=false` 时才平铺。 |
| 附件行横向滚动而非 `flex-wrap` | 窄屏避免输入卡片被附件撑高。 |
| 队列/待交互卡片放在正常流里而非 sticky dock | Android 无「浮层 dock」必要；键盘弹起时由 IME inset 处理。 |
| 无 tooltip / 快捷键提示 / 键盘 Tab 导航 | 触屏无对应输入方式。 |
| 无拖拽排序、无附件粘贴/工作区引用 | 移动端能力边界；可后续增量。 |
| 暂停横幅用 `surface` 而非 `surface-raised` | `surface-raised` 在 CSS 中 0 命中，网页实际可能透明；Android 用 surface 更可读。 |
| 字号用 sp 而非固定 px | Android 无障碍要求跟随系统字号。 |
| 上传进度环画在 36dp 图标框内 | 文件 pill 的图标框就是 36dp；网页文件 pill 用文字百分比，两种都可接受。 |

---

## 13. 建议改动清单（按优先级）

### P0

1. **补底部安全区**：`ChatScreen.kt:581-587`，在 `imePadding()` 前加 `navigationBarsPadding()`（或改为 `windowInsetsPadding(WindowInsets.navigationBars)`），避免 edge-to-edge 下输入卡片压住导航条。
2. **统一工具栏控件为 28dp / 14sp**：`ChatScreen.kt:701-791`
   - `+`：保持 28dp 视觉尺寸（图标 16dp），触摸区用外层 48dp 容器扩展，不改视觉。
   - 模型触发：`ChatScreen.kt:729-743` 用 28dp 高的 `Row`（`clickable`）替换 M3 `TextButton`，字号 `ZemoteType.uiBase`。
   - Mode/Effort：`ChatScreen.kt:718-723、744-751` 改为 28dp 高、8dp 圆角、`uiBase`。
   - 发送/停止：`ChatScreen.kt:753-789` 32dp → 28dp。
3. **Mode 选择器补全**：`ChatScreen.kt:718-723、796-804、841-881`
   - 图标映射（hand / notepad-text / shield-check / shield-alert，16dp）。
   - 本地化名称与描述（Intl:369991-370200）。
   - 菜单首项 Plan 复选框 + `plan`/`plan-off`；计划标记（竖线 + 「计划」+ 关闭）。
   - 语义色：yolo `colors.warning`，guarded `colors.iconBlue`。
4. **Effort 选择器补全**：`ChatScreen.kt:744-751、841-881`
   - brain 图标 16dp；默认态 = brain + 本地化名称 + chevron。
   - 容器不足时进入 compact 态：隐藏名称/chevron，显示 4dp 竖向进度条（轨道 `foreground.copy(alpha=0.1)`，填充 `colors.success`，高度按档位序号百分比）；再不足收成 28dp 纯 brain。
   - 档位文案本地化（Intl:304400-304560）。
   - `ChatScreen.kt:228-233` 去掉 `thoughtLevels` 回退（目录未就绪时宁可不显示，符合网页口径）。
5. **修正卡片顺序**：`ChatScreen.kt:206-213`，把 `QueueCards` 放到 `InteractionCard` 之前。
6. **队列卡片结构**：`ChatScreen.kt:475-548`
   - 单张 `rounded-t-2xl` 容器（`bgSurface` + `border` + `padding(4.dp)`），并保留与输入卡片 28dp 的重叠（`offset`/负 margin 等价实现）。
   - 每项 `rounded-xl`、`px 6dp / py 4dp`、14sp 正文。
   - 接 `sendQueuedNow` / `editQueueItem` / `deleteQueueItem`（Rust `conversation.rs:113-116` 已允许，`conversation_transport.rs:965-968` 已分发）。
7. **待交互卡片对齐**：`ChatScreen.kt:425-473`
   - 外壳改 `colors.popover` + `colors.border` + 16dp 圆角 + 12dp 内边距。
   - 选项改整行 listbox（`rounded-xl`、`px 12dp / py 8dp`、序号、选中 `colors.selected`）。
   - 标题 14sp medium subtle，正文 14/20 foreground。

### P1

8. **输入卡片 focus 背景**：`ChatScreen.kt:590-601`，`background(if (focused) colors.inputFocused else colors.input)`。
9. **`+` 菜单**：`ChatScreen.kt:710-717、246`，至少恢复「附件」菜单项；完整实现 add/plugins/files/sessions 分组。图标 16dp。
10. **附件图片缩略图与状态机**：`ChatScreen.kt:603-670`、`ChatViewModel.kt:131-141`
    - `ChatAttachment` 增加 `uploadStatus`、`id`、可选缩略图/本地 URI。
    - 图片 chip 48×48 缩略图 + 失败 `!` + 重试。
    - 文件名 14sp、元信息 12sp（扩展名大写）。
11. **待交互卡片自由输入与倒计时**：`ChatScreen.kt:459-471`，补 textarea 行与最后 60s 倒计时（仅 userInput）。
12. **模型触发 chevron 与字号**：`ChatScreen.kt:729-743`，补 14dp chevron；`uiBase`。
13. **队列项动作**：`ChatScreen.kt:520-546`，补「立即」「编辑」「删除」三个 28dp ghost 图标按钮。

### P2

14. 占位文案 `maxLines = 2`（compact 两行截断）：`ChatScreen.kt:691-695`。
15. 去掉队列项「排队中/待发送」文字状态（网页不显示文字状态）：`ChatScreen.kt:540-544`。
16. 模型前缀独立折叠：`ChatScreen.kt:883-889` 返回前缀/模型两段，触发按钮分别渲染。
17. 工具栏 `items-end` 对齐：`ChatScreen.kt:703`。
18. 发送中 loading 图标：`ChatScreen.kt:782-787`。
19. 附件删除按钮改 14dp 圆形、右上角叠加：`ChatScreen.kt:657-667`。

---

## 14. 未验证项

1. **Compose 实测尺寸**：`TextButton` 最小 40dp、`IconButton` 28dp、ChipMenu ≈23dp 均为 Material3 默认值与代码推算，未在真机/模拟器测量；若主题覆盖了 `ButtonDefaults.MinHeight`，模型按钮实际高度可能不同。
2. **工具栏溢出折叠**：`CAt` 通过克隆节点测量 `leading + trailing + gap` 是否超过容器，再按优先级隐藏；实际触发宽度、各优先级隐藏后的观感未在浏览器实测。`labelVisibilityClassName` 的 `@sm` 断点在 composer 路径被 `triggerLabelClassName` 覆盖，未实测。
3. **Effort 三态切换的实际渲染**：composer 传入 `composerCollapsePriority:3`，按类名推导为「默认名称 → compact 进度条 → icon」；`data-composer-compact` 的值在折叠算法里是 `true`/`icon`，进度条类只匹配 `true`，未在浏览器实测三态切换点。
4. **`--color-surface-raised`**：CSS 全文 0 命中，暂停横幅最终计算背景无法确认；Android 用 `surface` 是有意修复。
5. **图片附件缩略图**：网页用 `objectUrl`；Android 侧从 `ByteArray` 解码缩略图的内存/耗时未评估。
6. **键盘弹起时的重叠**：队列 `-mb-7 pb-7` 与输入卡片的层叠在 Android IME 弹起、时间线滚动时的表现未实测。
7. **队列/交互卡片的 sticky 行为**：Android 当前放在普通 Column 流中，长队列会把输入区推离底部；是否需要限制高度/内部滚动未与产品确认。

---

*本报告只做静态取证；所有网页偏移均可在对应文件中用 `Buffer.indexOf` 复现。*
