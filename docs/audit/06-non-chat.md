# 06 · 非对话页：网页盘点与 Android 对齐建议

- 上游快照：`archive/pre-3.16.1-auth/Reference_web/3.15.3/`（主包 `assets/index-BGa3KiRe.js`、样式 `assets/index-BD2BL4Cc.css`、文案目录 `assets/IntlProvider-C-26GNLk.js`）
- Android 对照：`android/app/src/main/kotlin/app/zemote/ui/{home,settings,pairing,devices,update,nav}`、`ui/theme/{Color,Dimens,Type}.kt`
- 取证方式：只读静态解析（未在浏览器/设备上跑页面，未取 computed-style）。**偏移均为 UTF-8 字节偏移**，与 `docs/audit/01-typography.md` 口径一致；JS 是单行 bundle，偏移指向符号/字符串起始处。
- 文案中英对照取自 `IntlProvider-C-26GNLk.js` 的两套 catalog（zh 在前、en 在后，同 key 取第一次出现）。

---

## 1. 结论摘要

1. **网页 3.15.3 是「Tab 工作台」而不是多路由站点**：静态检索没有 `createBrowserRouter` / `useRoutes` / 路由表（命中 0），页面由 tab（`kind: workspace | settings`）、侧栏、弹窗组成；`/remote/v4` 只是入口。
2. **非对话页分两层，不能混为一谈**：
   - **桌面 Web 工作台**：设置 Tab（16 个 section + 引导入口）、任务/工作区侧栏、登录页、SSH/Server/WSL/Docker 远程连接向导、更新弹窗、移动端远控弹窗。
   - **手机远控壳（`/remote/v4` 打开后）**：只有 **任务首页** 与 **对话页** 两态（`data-mobile-page=chat|home`），顶栏只有主题菜单；**没有设置页、没有设备页、没有扫码配对页**。
3. **工作区/任务列表确实有**，而且是两套：桌面侧栏（`workspaceSidebar.*` + `taskList.*`）与手机远控任务首页（`webRemoteControl.mobileHome.*`）。手机首页有提示卡、工作区/任务汇总、按工作区/时间线整理、按创建/更新时间排序。
4. **设置有**：3 个分组、16 个 section（`basics` / `agentCapabilities` / `dataAndStats`），外加底部虚线「引导」入口。设置是「268px 侧栏 + 内容区（max-w-4xl）」的两栏结构，窄屏（`max-lg`）收成 68px 图标栏。
5. **模型管理有**，位于「设置 → 模型设置」：服务商列表、模型行、连接方式徽标、新增供应商（目录/自定义端点）、编辑/删除模型等；不是独立顶层页。
6. **配对形态与 Android 不同**：桌面 Web 有「移动端远程控制」弹窗（二维码 + 链接 + 刷新/停止）；手机 Web **没有扫码页**，扫到链接后直接进入「连接中转 → 设备鉴权 → 等待桌面配对 → 同步工作区」四步状态卡。
7. **未发现设备列表页**：全库没有设备列表的 i18n 命名空间、路由或页面组件；只有 `browser.permission.device*`（浏览器 USB/蓝牙等权限）和 `webRemoteControl` 的当前会话状态。因此「设备列表」在网页端**无对应**。
8. **Android 现状**：`HomeScreen` 已实现（任务首页）；`SettingsHomeScreen` + `SettingsSectionScreen` 已实现，但除连接/通知/外观/模型外多为占位；`QrScanScreen` 已实现；`DevicesScreen` / `PairingScreen` / `UpdateScreen` 是占位且前两者**没有接入导航**；`SettingsScreen.kt` 是未被引用的旧版。
9. **对齐原则**：手机端应保留 MD3 原生导航外壳（底部双 Tab、列表→详情、全屏扫码、系统返回），但**信息架构、行/卡片结构、字号/间距/圆角/控件规格对齐网页**；不要照搬 268px 侧栏和 hover 交互。

---

## 2. 页面矩阵

> 「网页是否有」严格按静态证据判断；没有找到页面/路由/i18n 命名空间的写「网页无对应」，不臆造。
> Android 文件均相对 `android/app/src/main/kotlin/app/zemote/ui/`。

| # | 页面 | 网页是否有 | 证据 / 偏移 | Android 现状 | 建议 |
|---|---|---|---|---|---|
| 1 | 手机任务首页（工作区 + 任务） | **有** | `webRemoteControl.mobileHome.*`（JS:4432549）；header `px-4 py-3`、提示卡、`sectionTitle`+summary、按工作区/时间线整理、任务行 `min-h-12 rounded-lg px-2.5 py-2` | `home/HomeScreen.kt:92` 已实现：54dp 页头、工作区卡、任务行、左滑三动作、空态 | **对齐网页信息结构**：补提示卡、`N 个工作区 · M 个任务` 汇总、整理/排序入口、工作区头（类型徽标 + 任务数 + chevron）；列表手势保留原生 |
| 2 | 桌面任务/工作区侧栏 | **有（侧栏，非独立页）** | `taskList.*`、`workspaceSidebar.*`；任务行 `Z4`（JS:4767279）：`rounded-lg pl-2.5 pr-1 py-1`，active `bg-selected`，hover `bg-surface-hover`，状态点 `h-1.5 w-1.5`；空态 `taskList.noTasks`（JS:4797262）`px-3 py-2 text-ui-base text-foreground-subtle` | 无侧栏；用底部 Tab + 首页列表替代 | **不做侧栏**；把行解剖/空态/分组标签规格复用到首页 |
| 3 | 设置入口 / 设置侧栏 | **有（桌面 Web；手机远控无）** | 分组 `r$n`（JS:6223137）：`basics / agentCapabilities / dataAndStats`；16 个 section 的 `i$n`；布局 `grid-cols-[68px_minmax(0,1fr)] lg:grid-cols-[268px_minmax(0,1fr)]`（JS:6263570）；侧栏项 `v$n`（JS:6245269）`h-8 rounded-xl px-2.5 gap-2`，active `bg-surface-hover`，窄屏 `size-10` 图标栏 | `settings/SettingsHomeScreen.kt:43`：列表→详情；4 组 18 项（多出本机「连接/通知」）；顶部大号 About 横条 | **对齐分组/顺序/行规格**；保留 MD3 列表→详情；About 横条改普通行或保留为品牌入口（见 #23） |
| 4 | 设置 · 常规 | **有** | `l$n`（JS:6225159）；行组件 `y1`（JS:3899823）`border-t px-4 py-3`，`grid 1fr 192px`（wide 为 `280px`）；label `ui-base font-medium`，desc `mt-1 ui-base leading-6 subtle` | `SettingsSectionScreen.kt:422` 已接语言/交互行为/动态工作流/搜索/对话显示/工具分组/任务/网络/存储终端/桌面更新 | **对齐行式布局**（分隔线、右侧固定控制列）；控件换原生（Switch/下拉/输入） |
| 5 | 设置 · 外观 | **有** | `settings.appearance.*`：界面设置 + 代码设置两段；主题卡、字号、代码行号/换行/字号；`f$n` 里 `h3 text-ui-lg font-semibold` + `p ui-base leading-6 subtle` | `SettingsSectionScreen.kt:271` 已实现主题三选一、界面/代码字号滑杆、桌面代码预览开关 | **对齐三段结构**（界面设置 / 代码设置 / 预览）；主题选择用原生单选/卡片，不用网页下拉 |
| 6 | 设置 · 模型与服务商（模型管理） | **有** | `settings.modelProvider.*`（JS:5794134 起）；标题「模型设置」、描述「管理自定义模型供应商」、`addProviderTitle`、目录/自定义端点、API 格式、Base URL/API Key、连接方式徽标、模型行开关、编辑/删除 | `SettingsSectionScreen.kt:582` 已实现：每个服务商一张 `SectionCard`、展开模型行、开关、新增/重命名/删除 Dialog | **对齐 IA**：服务商卡（名称 + 模板 + 模型数 + 启用态）→ 模型行（ID/内置/可选 + 开关/删除）→ 连接方式徽标；新增走原生底部弹层，保留目录/自定义两条路径 |
| 7 | 设置 · 记忆 | **有** | `settings.memory` / `settings.memoryDescription` | 已接 2 个开关（跨会话记忆、主动建议） | 对齐行结构即可 |
| 8 | 设置 · 子智能体 | **有** | `settings.subagents.*`；空态「没有找到子智能体」 | 占位（`RemoteSection` else 分支） | 对齐列表 + 启用态 + 空态；原生列表 |
| 9 | 设置 · 插件（含市场/详情） | **有** | `settings.plugins.*`、`settings.plugins.store.*`、`settings.plugins.detail.*`；空态「没有找到插件 / 暂无插件市场 / 选择一个插件查看详情」 | 占位 | 先做列表 + 安装/启用 + 空态；市场/详情作为二级页后置 |
| 10 | 设置 · MCP 服务器 | **有** | `settings.mcp.*`；空态 `settings.mcp.emptyTitle`（JS:6080163）；分组「已配置 / Plugin MCP」；状态徽标 | 占位 | 对齐分组、状态徽标（已连接/连接中/未连接/插件未启用）、空态 |
| 11 | 设置 · 技能 | **有** | `settings.skills.*`；空态「没有可用技能」；启用开关 | 占位 | 对齐列表 + 启用开关 + 空态 |
| 12 | 设置 · 命令 | **有** | `settings.commands.*`；搜索、增删改、空描述 | 占位 | 对齐列表 + 搜索 + 编辑表单 |
| 13 | 设置 · 自动化（定时任务） | **有** | `settings.automations.*`、`automations.*`；Beta 徽标 | 占位 | 对齐列表 + 启停 + 立即运行；Beta 徽标用原生 chip |
| 14 | 设置 · 钩子 | **有** | `settings.hooks.*`；总开关 + 条目 | 占位 | 对齐列表 + 总开关 |
| 15 | 设置 · 浏览器控制 | **有（桌面专属）** | `settings.browser.*`（控制开关、缓存、导入、安全） | `BROWSER` 只读占位 | **保持只读**（手机端无内置浏览器控制意义） |
| 16 | 设置 · 电脑控制 | **有（仅桌面端显示）** | `i$n` 含 `computerUse`；`a$n` 在非桌面时过滤掉 | `COMPUTER_USE` 只读占位 | **隐藏或保持只读**；手机端不展示入口更合理 |
| 17 | 设置 · 键盘快捷键 | **有（桌面专属）** | `settings.shortcuts.*` | `SHORTCUTS` 只读占位 | **保持只读** |
| 18 | 设置 · 工作区搜索范围 | **有（桌面专属）** | `settings.workspaceFileSearch.*` | `WORKSPACE_SEARCH` 只读占位 | **保持只读或隐藏** |
| 19 | 设置 · 使用统计 | **有** | `settings.usage.*`；两个 tab「应用用量 / 个人套餐」；范围「全部/近 7 日/近 30 日」；空态 `settings.usage.emptyTitle`（JS:5966406）；图表 chunk `AppUsage*Chart` | `USAGE` 走 `RemoteSection` else → 占位「界面正在接线」 | **对齐信息架构**（tab、时间范围、汇总、空态）；图表可用原生 Canvas 后置，不阻塞 |
| 20 | 引导 / Onboarding | **有（设置底部虚线入口 + 向导弹窗）** | `settings.onboarding`、`onboarding.*`（欢迎、迁移步骤、Skills/MCP/插件/命令/AGENTS.md） | 无 | **保持现状（不做）**：手机端无数据迁移场景 |
| 21 | 配对页 | **手机端无对应；桌面端有配对弹窗** | 桌面：`webRemoteControl.title`（JS:4666813）、`mobileQr.title/description`、二维码、刷新/停止、Bot Channel；手机：连接状态卡 `$9`（JS:6574361）四步 | `pairing/QrScanScreen.kt:88` 全屏相机 + 中央框 + 手电筒 + 相册 + 粘贴；`AppNavHost.kt:200` 底部黑色状态覆盖层 | **保持原生扫码**（手机 Web 做不到）；把连接中/失败/等待的覆盖层改成网页同款卡片（max-w-lg、状态点、四步清单） |
| 22 | 设备列表 / 设备管理 | **网页无对应** | 无设备列表页面/路由/i18n；仅 `browser.permission.device*` 与 `webRemoteControl` 当前会话状态 | `devices/DevicesScreen.kt:36` 占位；`NavState.kt` 无 `Route.Devices`，`DevicesViewModel` 工厂未被使用 → **不可达死代码** | **删除或并入「设置 → 连接」**；若产品要多设备，再按 MD3 原生列表实现（网页无参照，不强行对齐） |
| 23 | 关于与更新 | **无独立页（更新弹窗）** | `updateDialog.*`、`updateReady.*`、`updateAvailable.tooltip`；无 about 页面路由，仅 `titleBar.menu.help.about`「关于 ZCode」菜单项 | `update/UpdateScreen.kt:21` 独立页，但 UI 是占位（纯文字 + 朴素 Button） | **采用 MD3 原生页**（手机适合整页），视觉对齐 `updateDialog`：版本行、更新说明、下载进度、重启更新 |
| 24 | 登录 / 账号 | **有（桌面/网页登录页）** | `login.*`（OAuth、API Key、过期重登） | 无 | **保持现状（不做）**：Android 用配对模型，不需要账号登录 |
| 25 | 远程连接向导（SSH/Server/WSL/Docker） | **有（桌面工作台）** | `remote.*`：选择方式 → 填配置 → 连接中 → 选目录 | 无 | **保持现状（不做）**：手机端不发起远程环境连接 |
| 26 | 任务搜索 / 命令面板 | **有（浮层，不是页）** | `taskSearch.*`、`commandCenter.*`、`quickPick.*` | 无 | 可后置；手机端优先做首页内搜索即可 |

---

## 3. 网页非对话页解剖（证据）

### 3.1 设置页骨架

```
┌ 68px（窄屏）/ 268px（lg） ┬ 内容区（max-w-4xl） ─────────────┐
│ 返回工作区                 │ 面包屑（h-12）                  │
│ 分组标签                   │ 标题（text-2xl / lg:text-3xl）  │
│  · 图标 + 标题（h-8）      │ 卡片（rounded-xl border bg-card）│
│ 分组标签                   │  · 行（border-t px-4 py-3）     │
│  · …                       │  · 行                           │
│ 虚线「引导」               │                                 │
│ 底部：主题/语言/用量/账号  │                                 │
└────────────────────────────┴─────────────────────────────────┘
```

- 外层：`relative grid h-screen grid-cols-[68px_minmax(0,1fr)] lg:grid-cols-[268px_minmax(0,1fr)]`（JS:6263570）。
- 侧栏项（`v$n`，JS:6245269）：`flex h-8 w-full items-center gap-2 rounded-xl px-2.5`；图标 `size-4`；文字 `truncate text-ui-base text-foreground`；激活 `bg-surface-hover text-foreground`，未激活 `text-foreground-subtle hover:bg-surface-hover`；窄屏 `max-lg:size-10 max-lg:justify-center`（只剩图标）。
- 分组标签：`px-2.5 pb-1 text-ui-sm font-medium text-foreground-subtlest`；窄屏 `sr-only`。
- 内容容器 `v1`（JS:3898261）：`mx-auto w-full max-w-4xl px-4 pb-8 pt-0 lg:px-8 lg:pb-10`。
- 内容头：`h-12` 面包屑；section 标题 `text-2xl font-semibold tracking-tight lg:text-3xl`；有子 tab 时（usage）用 tab 替代标题。
- 面板框：`rounded-xl border border-border bg-background`（桌面端 p-1 时 `rounded-[5px]`）。

### 3.2 设置卡片与行

- 卡片 `b1`（JS:3900586）：`rounded-xl border border-border bg-card py-0 shadow-none`。
- 行 `y1`（JS:3899823）：
  - 容器：`border-t border-border px-4 py-3 first:border-t-0`。
  - 栅格：`grid items-center gap-4 grid-cols-[minmax(0,1fr)_192px]`；`controlLayout=wide` 时 `grid-cols-1 sm:grid-cols-[minmax(0,1fr)_280px]`。
  - 左列：标题 `text-ui-base font-medium text-foreground`；描述 `mt-1 text-ui-base leading-6 text-foreground-subtle`。
  - 右列：`flex w-full flex-nowrap items-center justify-end gap-2`。
  - `wide` 时 detail（输入框等）放在右列，否则放在行下方 `mt-3`。
- 结论：网页是**行式设置**（label/description 左、control 右），不是「每项一张小卡」。Android 当前 `SwitchRow`/`SelectRow` 也是行式，但外层 `SectionCard` 带 12dp 圆角和内边距、行间无分隔线，视觉上更像卡片组。

### 3.3 控件规格

| 控件 | 网页规格 | 证据 |
|---|---|---|
| Switch | 默认 `h-[18px] w-[32px]`，sm `h-[16px] w-[28px]`；`rounded-full`；选中 `bg-primary`，未选 `bg-primary/30`；滑块 `size-4`（sm `size-3.5`），选中位移 `calc(100%-2px)` | `kA` JS:1521085 |
| 文本输入 | `rounded-lg border border-input-border bg-input px-2 py-2 text-ui-base`；hover `border-input-border-hover`；focus `border-input-border-focused` + `bg-input-focused` | JS:3265877 区 |
| Select 触发器 | 尺寸 `xs h-5 / sm h-6 / default h-7 / lg h-8`；圆角 `sm/md/md/lg`；input 变体同输入框三态 | JS:225103 区 |
| Button | 尺寸 `xs h-5 / sm h-6 / default h-7 / lg h-8`；图标尺寸 `icon-xs size-5 / icon-sm size-6 / icon-md size-7 / icon-lg size-8`；变体 default/outline/secondary/ghost/destructive/warning | `assets/button-B-6dbTqe.js` |
| 空态 | `rounded-xl border border-dashed border-border bg-transparent px-4 py-10 text-center`；标题 `ui-base font-medium`，描述 `ui-sm subtle`，动作 `gap-2` | `V5` JS:5992066 |
| 确认弹窗 | 标题/描述/取消/确认；破坏性操作确认按钮 `destructive` | `confirmDialog.*` 文案；`taskList.deleteAllArchived*` 调用处 |
| 状态徽标 | `rounded-full border px-1.5 py-0.5 text-ui-xs leading-none`，按状态着色 | mobileHome 任务行 |

### 3.4 手机任务首页（`webRemoteControl.mobileHome`）

结构（JS:4432549 起）：

```
section.h-full.bg-background
├ header.shrink-0.border-b.border-border.bg-header.px-4.py-3
│  ├ 标题 text-ui-lg font-medium（「ZCode 远程控制」）
│  ├ 副标题 mt-1 text-ui-base text-foreground-subtle（「已连接到当前桌面窗口」）
│  └ 右侧主题菜单（icon-sm）
└ div.min-h-0.flex-1.overflow-y-auto.px-3.py-3
   ├ 提示卡 rounded-lg border-card-border bg-card p-3 text-ui-base/relaxed subtle
   ├ 段落头 mt-4：标题 text-ui-base font-medium + summary「N 个工作区 · M 个任务」
   │  └ 右侧：收起全部（workspace 模式）+ 整理菜单（按工作区/按时间线、按创建/更新时间）
   ├ 工作区组（每个）
   │  ├ 头部：名称 + 类型徽标（本地/对话/远程）+ 断开徽标
   │  │        路径 font-mono text-ui-base text-foreground-subtlest
   │  │        最近更新时间 text-ui-base subtle
   │  │        右侧：未读点 h-1.5 w-1.5 + 「N 个任务」+ chevron / 新建按钮
   │  └ ul.border-t.border-card-border.px-2.py-2
   │     ├ 空态 li.px-2.py-2 text-ui-base subtle（「这个工作区暂无任务」）
   │     └ 任务 button.min-h-12.rounded-lg.px-2.5.py-2
   │        ├ 状态槽 size-4（转圈 / 未读点）
   │        ├ 标题 truncate text-ui-base
   │        ├ 更新时间 mt-1 text-ui-base subtle
   │        └ 状态胶囊 rounded-full border px-1.5 py-0.5 text-ui-xs
   └ （时间线模式：按日期分组）
```

### 3.5 手机端连接状态卡（配对后的等待/重连）

- 组件 `$9`（JS:6574361）：`h-dvh w-screen bg-background` → `mx-auto max-w-lg px-4` 居中 → `section.w-full rounded-xl border border-card-border bg-card p-5`。
- 顶部：`size-2 rounded-full bg-warning animate-pulse` + `h1 text-ui-xs font-medium`。
- 描述：`mt-2 text-ui-xs/relaxed text-foreground-subtle`。
- 四步：`mt-4 grid gap-2`，每步 `rounded-lg border border-border bg-surface px-3 py-2 text-ui-xs text-foreground-subtle`。
- 状态文案（`H6n`，JS:6572509）：connecting / authenticating / waiting / reconnecting / paired / suspended，四步固定为「连接中转服务 → 设备鉴权 → 等待桌面端配对 → 同步工作区」。

### 3.6 空态与列表

| 场景 | 网页表现 | 证据 |
|---|---|---|
| 侧栏无任务 | 一行 `px-3 py-2 text-ui-base text-foreground-subtle`「暂无任务」 | JS:4797262 |
| 手机首页工作区无任务 | `li px-2 py-2 text-ui-base text-foreground-subtle`「这个工作区暂无任务」 | mobileHome 区 |
| 设置列表空态（MCP/技能/插件/子智能体） | 虚线卡片 `px-4 py-10`，标题 + 描述 + 动作 | `V5` JS:5992066 |
| 使用统计无数据 | `settings.usage.emptyTitle/emptyDescription` + 同一空态组件 | JS:5966406 区 |
| 任务列表加载 | `flex items-center gap-2 px-2.5 py-1 text-ui-base text-foreground-subtlest` + spinner +「正在获取任务...」 | `X2` JS:4561668 区 |

---

## 4. Android 现状与关键差距

### 4.1 已落地

| 文件 | 内容 |
|---|---|
| `home/HomeScreen.kt` | 任务首页：54dp 页头、异常横幅、下拉刷新、已置顶区、工作区卡（折叠/展开）、任务行（状态槽/预览/状态 pill）、左滑置顶/归档/删除、多种空态（加载/未配对/错误/无工作区） |
| `settings/SettingsHomeScreen.kt` | 设置首页：About 横条 + 4 组分类列表（34dp 图标、标题/副标题/只读徽标、chevron、分隔线） |
| `settings/SettingsSectionScreen.kt` | 二级页：M3 TopAppBar + `SectionCard`；连接/通知/外观/模型已接；其余 `RemoteSection` 占位或只读提示 |
| `pairing/QrScanScreen.kt` | 全屏 CameraX + ML Kit；手电筒、相册、粘贴、权限被拒兜底 |
| `update/UpdateScreen.kt` | 关于与更新页，逻辑齐全但 UI 为占位 |

### 4.2 未接入 / 死代码

| 文件 | 状态 | 证据 |
|---|---|---|
| `settings/SettingsScreen.kt` | 旧版设置页，**无任何调用点** | 全工程仅函数定义，`AppNavHost` 使用 `SettingsHomeScreen`/`SettingsSectionScreen` |
| `pairing/PairingScreen.kt` | 旧版配对表单，**无调用点** | `AppNavHost.kt:185` 的 `PairingRouteContent` 直接用 `QrScanScreen` |
| `devices/DevicesScreen.kt` | 占位，**无路由** | `NavState.kt:12-44` 只有 Main/Pairing/Chat/SettingsSection/AboutUpdate；`DevicesViewModel` 工厂未被调用 |
| `update/UpdateScreen.kt` | 已接路由，但视觉为占位 | `AppNavHost.kt:341` `UpdateRoute` |

### 4.3 关键差距（按影响排序）

1. **设置项行规格**：网页行是 `border-t + px-4 py-3 + 1fr/192px` 的「label 左、control 右」；Android `SectionCard` 是整卡内边距、行间无分隔线、`SwitchRow` 只有 `py-6dp`。这是设置页覆盖面积最大的偏差。
2. **控件尺寸/形状**：Android 用 M3 默认 `Button`（40dp 高、全圆角）和 `Switch`（52×32）；网页按钮 `h-5/6/7/8` + `radius 4/6/8`，Switch 32×18。视觉密度差一档。
3. **设置首页分组**：网页 3 组、16 项、无 About 大横条；Android 4 组、18 项、顶部主色 About 横条。多出的「连接/通知」是合理本机项，但分组标题/顺序/行样式需要统一。
4. **任务首页信息结构**：网页手机首页有「连接说明卡 + 工作区/任务汇总 + 整理/排序 + 工作区类型徽标 + 任务数 + 状态胶囊」；Android 缺提示卡、汇总、整理排序，工作区头是 32dp 图标 + 路径 + `+`，任务行高 8dp 内边距。
5. **配对等待态**：Android 是底部黑色半透明条；网页是居中卡片 + 状态点 + 四步清单。连接失败/被踢/超时的文案也应按网页 `webRemoteControl.failure.*` 分类。
6. **设备页**：网页无对应，Android 页面不可达；继续保留会形成「设计无参照、代码无入口」的死代码。
7. **使用统计**：网页有完整 tab/范围/汇总/图表/空态；Android 仍是「界面正在接线」占位。
8. **关于与更新**：网页只有弹窗；Android 是整页占位。整页是合理的移动端适配，但内容/状态机需要按 `updateDialog.*` 补齐。

---

## 5. 非对话页应共享的令牌与组件清单

### 5.1 令牌（已由 `design/tokens/` 生成，直接复用）

| 类别 | 令牌 | 值 / 对应 |
|---|---|---|
| 字号 | `ZemoteType.ui2xs / uiXs / uiSm / uiCaption / uiBase / uiLg / uiXl` | 9 / 10 / 12 / 13 / 14 / 16 / 18sp，行高 ×1.5；详见 `docs/audit/01-typography.md` |
| 字重 | `ZemoteFontWeight.Normal/Medium/Semibold/Bold` | 400 / 500 / 600 / 700 |
| 间距 | `ZemoteSpacing.Unit` | 4dp；`of(n)` |
| 圆角 | `ZemoteRadius.xs/sm/md/lg/xl/xxl/xxxl` | 2 / 4 / 6 / 8 / 12 / 16 / 24dp；`fullShape = CircleShape` |
| 动效 | `ZemoteDuration.Default` 150ms；`ZemoteEasing.inOut` | 网页 `--default-transition-duration: .15s`、`cubic-bezier(.4,0,.2,1)` |
| 颜色 | `ZemoteColors.background / header / card / cardBorder / surface / surfaceHover / selected / border / borderHover / foreground / foregroundSubtle / foregroundSubtlest / primary / primaryForeground / destructive / success / warning` | 与网页 `--color-*` 同名同义；`Color.kt` 已生成，不要在页面里写死 |

网页 CSS 变量取值（下表是 **zai 主题**的直接值；默认主题的另一套值以 `Color.kt` / `docs/audit/07-impl-gaps.md` §0 为准，两者语义相同）：

| 变量 | 亮色 | 暗色 |
|---|---|---|
| `--color-background` | `#f8f8f8` | `#161616` |
| `--color-header` | `#fff` | `#202020` |
| `--color-card` | `#fff` | `#2b2b2b` |
| `--color-surface` | `#0a0a0a08` | `#ffffff0d` |
| `--color-surface-hover` | `#0a0a0a0d` | `#ffffff1a` |
| `--color-selected` | `#0a0a0a1a` | `#ffffff1a` |
| `--color-border` | `#0a0a0a1a` | `#fafafa1a` |
| `--color-border-hover` | `#0a0a0a33` | `#fafafa4d` |
| `--color-input` | `#fff` | `#2b2b2b` |
| `--color-foreground` | neutral-700（默认）/ neutral-800（zai） | neutral-200（默认）/ neutral-300（zai） |
| `--color-foreground-subtle` | 60% 前景 | 60% 前景 |
| `--color-foreground-subtlest` | 40% 前景 | 30% 前景 |
| `--color-primary` | `#000` | `#fff` |
| `--color-destructive` | `#e03131` | `#ff5c5c` |
| `--color-success` | `#1e8a3e` | `#46bf72` |
| `--color-warning` | `#e07b00` | `#ff8a30` |

### 5.2 组件清单（建议在 `ui/common/` 或各页内统一）

| 组件 | 规格 | 用途 |
|---|---|---|
| `SettingsGroupHeader` | `uiXs` / `Medium` / `foregroundSubtlest`；`padding(start=16,end=16,top=18,bottom=6)`（网页 `px-2.5 pb-1 ui-sm`） | 设置分组标题 |
| `SettingsCard` | `fillMaxWidth` + `radius 12dp` + `card` 背景 + `border` 1dp；内边距由行控制 | 设置卡片组 |
| `SettingsRow` | 最小高度 48dp；`horizontal 16dp / vertical 12dp`；标题 `uiBase Medium`；描述 `uiSm`（网页 `ui-base leading-6`，移动端可降一档）；右侧控制区固定宽度（192dp / wide 280dp，手机改为自适应） | 所有设置项 |
| `SettingsDivider` | `border` 色，`start inset 16dp`（网页 `border-t` 全宽，移动端建议左侧内缩对齐文字） | 行间分隔 |
| `SettingsSwitchRow` | `SettingsRow` + `Switch`；开关色 `primary` / `primary.copy(alpha=.3)`；触摸目标 ≥48dp | 布尔设置 |
| `SettingsSelectRow` | 标题 + 当前值（`uiSm` `foregroundSubtle`）+ chevron；点击弹 `ModalBottomSheet` / 下拉 | 枚举设置 |
| `SettingsTextFieldRow` | 标题 + 值（等宽 `uiSm`）+ chevron；点击弹输入对话框或行内编辑 | 文本/路径设置 |
| `ReadOnlyBadge` | `rounded-full` + `surface` 底 + `ui2xs` + `foregroundSubtle`；文案「桌面端」 | 只读项 |
| `EmptyState` | `radius 12dp` + 虚线 `border` + `padding(16dp, 40dp)`；标题 `uiBase Medium`、描述 `uiSm`、动作行 `gap 8dp` | 所有列表空态 |
| `TaskRow` | 最小高度 48dp；`radius 8dp`；`padding(horizontal 10dp, vertical 8dp)`；状态槽 16dp；标题 `uiBase` 单行；元数据 `uiSm`/`uiXs`；active `selected` | 首页/侧栏任务 |
| `WorkspaceHeader` | 图标/展开箭头 + 名称 `uiBase Medium` + 类型徽标 + 路径（等宽 `uiSm` `foregroundSubtle`）+ 任务数 + chevron/新建 | 首页工作区分组 |
| `StatusPill` | `rounded-full` + `border` + `px 6dp / py 2dp` + `ui2xs`；running/completed/error 三色 | 任务状态 |
| `NoticeCard` | `radius 8dp` + `card` 底 + `cardBorder` 1dp + `padding 12dp`；`uiSm` `foregroundSubtle` | 首页连接说明 |
| `ConnectionStatusCard` | 居中 `maxWidth 512dp`；`radius 12dp` + `card` 底 + `cardBorder`；状态点 8dp + 标题 `uiXs Medium` + 描述 `uiXs` + 四步 `radius 8dp` `surface` 行 | 配对/重连等待 |
| `PrimaryButton` | 高度 36–40dp（网页 lg 32dp，移动端触摸目标放大）；`radius 8dp`；`primary` 底 | 主操作 |
| `OutlineButton` | 高度 36–40dp；`border` + 透明底 | 次操作 |
| `GhostIconButton` | 32–40dp 方形；`foregroundSubtle`，按下 `surfaceHover` | 行内动作 |
| `ConfirmDialog` | M3 `AlertDialog`；破坏性确认按钮 `destructive`；标题/描述/取消/确认 | 删除、刷新二维码、断开连接 |

### 5.3 原生适配规则（网页 → Android）

1. **触摸目标**：网页 `h-7`（28px）/`h-8`（32px）在手机上升为 40–48dp；视觉高度可保持 32–36dp，用 `minimumInteractiveComponentSize` 或外层 padding 扩大热区。
2. **hover → pressed/ripple**：网页 `hover:bg-surface-hover` 改为 `indication = ripple` 或按下态 `surfaceHover`；不要保留 hover 语义。
3. **Switch**：不要为了 32×18 缩小 M3 Switch 的触摸区；建议保留 M3 尺寸（52×32）或自定义 40×24，颜色对齐 `primary` / `primary/30`。
4. **输入框**：高度 ≥48dp；`radius 8dp`；三态边框（默认 `border`、聚焦 `inputBorderFocused`、错误 `destructive`）；`bg-input`。
5. **导航**：底部双 Tab（对话/设置）保留；设置用列表→详情；不要实现 268px 侧栏或 `max-lg` 图标栏。
6. **安全区**：页头背景延伸到状态栏下方，文字用 `statusBarsPadding()`；列表底部留 `navigationBarsPadding()` + 24dp。
7. **弹层**：新增/编辑用 `ModalBottomSheet` 或 `AlertDialog`；网页 `max-w-2xl` 的表单在手机上全宽。
8. **图标**：网页用 lucide `size-4`（16px）；Android 用 16–18dp 线性图标，保持同一语义。

---

## 6. 未验证项

1. **web-remote 模式下哪些设置 section 被隐藏**：`i$n.filter(e => ZA(e.id))` 的 `ZA` 未展开分析；只能确认 `computerUse` 在非桌面时被过滤。手机远控壳本身没有设置入口，但桌面 Web 的可见 section 可能还受 `ZA` 影响。
2. **手机远控壳是否完全无法进入设置**：静态证据是 `mobileShell` 只有 `home|chat` 两态、顶栏只有主题菜单；未做运行时点击验证。
3. **设备列表是否真的不存在**：结论基于「无路由、无 i18n 命名空间、无页面组件」；不排除运行时动态注入的隐藏入口。
4. **模型设置完整交互**：新增供应商（目录/自定义）、模型编辑、连接方式切换、OAuth/套餐页的具体 DOM 与状态机未逐屏展开，只核对了 i18n 与关键 JSX。
5. **使用统计图表**：`AppUsage*Chart` 的具体绘制、范围切换、空态判定未逐项核对。
6. **Onboarding 向导**：只读到文案与步骤，未展开每个步骤的组件结构。
7. **颜色变量链**：CSS 里大量 `var(--color-neutral-*)` 间接引用，未逐主题解析最终 RGB；表中亮/暗值是关键变量的直接值或已解析值。
8. **实际渲染尺寸**：所有类名/偏移均来自静态 bundle，未在浏览器或 Android 设备上做像素级实测；`dp` 换算建议以设计验收为准。
9. **Android 页面可达性**：`DevicesScreen` / `PairingScreen` / `SettingsScreen` 的「未引用」结论来自全工程 `grep`，未检查反射/动态调用（Kotlin 无此惯例，风险低）。
10. **`SettingsSectionScreen` 各 section 的完成度**：`RemoteSection` 的 `else` 分支统一显示「界面正在接线」，但不同 section 的桌面键是否已读取、是否可写，未逐项验证。

---

## 7. 最值得先对齐的 3 项（执行顺序建议）

1. **设置项行规格 + 控件尺寸**（`SettingsRow` / `SettingsCard` / `Switch` / `Input` / `Button`）：覆盖设置首页和全部二级页，一次改完收益最大。
2. **任务首页信息结构**（提示卡 + 汇总 + 工作区头 + 任务行状态胶囊 + 整理/排序）：手机 Web 有直接对应，且是用户打开 App 的第一屏。
3. **模型设置 IA**（服务商卡 → 模型行 → 连接方式徽标 → 新增/编辑流程）：网页结构成熟，Android 已有逻辑，主要是重排与控件替换。
