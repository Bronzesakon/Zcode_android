# 02 外壳 / 导航 / 首页（项目树·任务列表）逆向规范

> 目标：为 Android Compose 1:1 重写提供可直接施工的布局、尺寸、颜色、状态与交互规范。
> 唯一依据：官方 3.17.1 网页产物（`E:/Zcode_android/Reference_web/3.17.1/`）。
> 产出日期：2026-11-03。文中 px 均为 CSS px，Compose 侧按 1 CSS px = 1 dp 映射。

**证据等级**
- `confirmed`：打包源码与 CSS 规则双证，或源码中为显式常量。
- `inferred`：类名语义 + Tailwind v4 规则推导，未见运行态截图。
- `unresolved`：产物内缺少定义、或需运行态才能确定。

**源文件**
- `Reference_web/3.17.1/assets/index-Dwhx77cK.js`（6,986,617 B，主包；所有组件在此）
- `Reference_web/3.17.1/assets/index-BgVjt5KE.css`（485,230 B，Tailwind v4 产物）
- `Reference_web/3.17.1/assets/IntlProvider-CneXitqp.js`（i18n，默认 zh-CN）
- `Reference_web/3.17.1/assets/button-P7AG3MDX.js`（Button cva 尺寸表）
- `Reference_web/3.17.1/index.html`（启动壳、字体、主题引导）

---

## 1. 关键结论（先看这 12 条）

1. **桌面外壳是「左侧栏 + 右内容区」，没有底部 Tab 栏**。全包没有底部导航组件（唯一的 `tabBar` 是桌面窗口框架的未使用 prop，不是导航）；`docs/UI-SPEC.md` 的底部双 Tab 是安卓自定义，不是网页结构。`confirmed`
2. **侧栏默认宽 264px，可拖拽范围 264px–50% 容器宽**，拖拽以 200ms ease-out 过渡，宽度写入 `localStorage["zcode:workspace-shell:sidebar-width-px"]`。`confirmed`（JS 常量 `MXn=264/u7=264/NXn=0.5`，byte 6,037,884；CSS `transition-[width,opacity] duration-200 ease-out`）
3. **侧栏隐藏时不是「窄轨」**：面板宽度变为 0、`opacity-0` + `pointer-events-none`；只有桌面原生端（Electron）才留 4/6px 轨道，浏览器版没有。`confirmed`
4. **顶栏不是通栏**，而是左上角绝对定位浮层：外层 `absolute h-14 z-20`，按钮 28px；浏览器版**没有侧栏折叠按钮**（只有桌面 Win/Linux 的 logo 按钮、macOS 的图标按钮）。`confirmed`
5. **移动端（<768px）只在 Web 远控壳里生效**：侧栏变成顶部导航面板，高度 `min(50vh-48px, 352px)`，折叠阈值 24px，拖拽阈值 4px。`confirmed`（`(max-width: 767px)`，byte 6,037,789）
6. **首页就是侧栏里的项目树**，4 种视图：`projects`（按项目）/`grouped`（按分组）/`timeline`（最近更新）/`unread`（按未读），视图选择存在 `zcode-project-sidebar-preferences`。`confirmed`
7. **行高（按类名计算）**：项目行 32px；默认任务行 32px；时间线任务行约 62px；分组任务行 28px；分组头 32px；区块标题 28px。`confirmed`（尺寸类名均为固定值）
8. **项目行没有「任务数」**，只有 6px 未读点、设备标签、离线/连接状态文案；任务数徽标只出现在分组头。`confirmed`（项目行 JSX 无 count 节点）
9. **置顶**：任务置顶独立成「已置顶」区（标题 14px medium，最多 20 条 + 显示更多/更少）；置顶项目单独成列表但**没有标题**。`confirmed`
10. **未读点**：任务 7px、项目 6px，颜色 `bg-icon-blue`（zai-dark `#80beff`，zai-light `#0066dd`），不是 sky-500。`confirmed`
11. **没有骨架屏**：加载态是一行 12/14px 文案或一行 spinner；空态是 14px 文案，没有插画。`confirmed`
12. **默认主题 `zai-dark`**：底 `#161616`、侧栏 `#161616`、顶栏/面板 `#202020`、正文 `#d4d4d4`、边框 `#ffffff1a`、菜单 `#2b2b2b`。`confirmed`

---

## 2. 设计令牌

### 2.1 尺寸基准

| 项 | 值 | 证据 |
|---|---|---|
| 间距单位 `--spacing` | 0.25rem = 4px | CSS `:root{--spacing:.25rem}` |
| 根字号 | 16px（浏览器默认，html 未改） | CSS `html,:host{...font-family:var(--default-font-family...)}` |
| UI 基准字号 `--ui-font-size` | 14px | CSS `:root{--ui-font-size:14px}`（byte 417,968 附近） |
| 根行高 | 1.5 | CSS `html,:host{line-height:1.5}` |
| rem | 16px | 同上 |

Tailwind 间距换算：`n` → `4n px`；`2.5`→10px，`3.5`→14px，`8.5`→34px，`0.5`→2px，`1.5`→6px。

### 2.2 颜色 token（zai-dark 为默认；zai-light 为浅色）

| Token | zai-dark | zai-light | 用途 |
|---|---|---|---|
| `--color-background` | `#161616` | `#f8f8f8` | 全局底色、会话面板底 |
| `--color-sidebar` | `#161616` | `#f0f0f0` | 侧栏/移动导航面板底 |
| `--color-header` | `#202020` | `#ffffff` | 顶栏 |
| `--color-panel` | `#202020` | `#ffffff` | 面板 |
| `--color-foreground` | `#d4d4d4`（neutral-300） | `#262626`（neutral-800） | 主文字 |
| `--color-foreground-subtle` | `#d4d4d499` | `#26262699` | 次级文字（项目名/时间） |
| `--color-foreground-subtlest` | `#d4d4d44d` | `#26262666` | 三级文字（区块标题/空态） |
| `--color-border` | `#ffffff1a` | `#0d0d0d1a` | 边框/分割线 |
| `--color-border-hover` | `#ffffff26` | `#0d0d0d26` | 边框 hover |
| `--color-hover` | `#ffffff0d` | `#0d0d0d0d` | 按钮 ghost hover |
| `--color-selected` | `#ffffff1a` | `#0d0d0d0d` | 选中行/按下 |
| `--color-surface` | `#ffffff0d` | `#0d0d0d08` | 轻表面（徽标、输入） |
| `--color-surface-hover` | `#ffffff1a` | `#0d0d0d0d` | 行 hover（注意 dark 下与 selected 同值） |
| `--color-menu` | `#2b2b2b` | `#ffffff` | 菜单/浮层底 |
| `--color-menu-hover` | `#363636` | `#f0f0f0` | 菜单项高亮 |
| `--color-popover` | `#2b2b2b` | `#ffffff` | Popover |
| `--color-card` | `#2b2b2b` | `#ffffff` | 卡片 |
| `--color-tag` | `#363636` | `#e6e6e6` | 标签底（`bg-tag/50`） |
| `--color-icon-blue` | `#80beff` | `#0066dd` | 未读点、拖放指示 |
| `--color-success` | `#46bf72` | `#1e8a3e` | 成功/在线 |
| `--color-destructive` | `#ff5c5c` | `#e03131` | 错误/危险 |
| `--color-warning` | `#ff8a30` | `#e07b00` | 警告 |
| `--color-warning-surface` | `color-mix(#ff8a30 16%, transparent)`（回退 `#cd890029`） | `color-mix(#e07b00 12%, transparent)`（回退 `#cd89001f`） | 警告底（离线任务徽标） |
| `--color-warning-surface-foreground` | `#ffa159` | `#914e00` | 警告文字 |
| `--color-remote-foreground-muted` | `#b3b3b3` | `#595959` | 设备名/远端弱文字 |
| `--color-accent` | `#001d3d` | `#ebf4ff` | 拖放高亮底 |
| `--color-brand` | `#ffffff` | `#000000` | 品牌色 |
| `--color-primary` | `#ffffff` | `#000000` | 主按钮 |
| `--color-primary-foreground` | `#000000` | `#ffffff` | 主按钮文字 |

`confirmed`：以上取自 CSS `.theme-zai-dark` / `.theme-zai-light` 块（`index-BgVjt5KE.css`，token 段约 397,955–433,000 字节）。

### 2.3 字体

| Token | 值 | 说明 |
|---|---|---|
| `--ui-font-size` | 14px | 基准 |
| `text-ui-2xs` | 9px | 极少用 |
| `text-ui-xs` | 10px | 快捷键提示、更新徽标 |
| `text-ui-sm` | 12px | 元数据、设备名、状态 |
| `text-ui-caption` | 13px | 说明文字 |
| `text-ui-base` | 14px | 行标题、按钮、菜单 |
| `text-ui-lg` | 16px | 大按钮 |
| `text-ui-xl` | 18px | 标题 |
| 字重 | 400 / 500 (`font-medium`) / 600 (`font-semibold`) | 区块标题 500，用户名 600 |
| 字体栈 | `Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif`（index.html body）；CSS `--font-sans: ui-sans-serif, system-ui,...` | 实际优先 Inter |
| 等宽 | `--font-mono: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", "Microsoft YaHei UI", ...` | 路径/连接地址 |

`confirmed`：CSS `--text-ui-*: calc(var(--ui-font-size) ± npx)`；index.html `body{font-family:Inter,...}`。

### 2.4 圆角 / 阴影 / 动效 / 断点 / 层级

| 项 | 值 | 证据 |
|---|---|---|
| 圆角 xs / sm / md / lg / xl / 2xl / 3xl | 2 / 4 / 6 / 8 / 12 / 16 / 24 px | CSS `--radius-*` |
| 面板圆角 `--workspace-panel-radius` | 12px（Web/非桌面；macOS<26 与 Windows 为 6px，Linux 12px） | JS `c7()` byte 6,037,300 附近 |
| `shadow-xs` | `0 1px 2px 0 #0000000d` | CSS |
| `shadow-sm` | `0 1px 3px 0 #0000001a, 0 1px 2px -1px #0000001a` | CSS |
| `shadow-md` | `0 4px 6px -1px #0000001a, 0 2px 4px -2px #0000001a` | CSS |
| `shadow-lg` | `0 10px 15px -3px #0000001a, 0 4px 6px -4px #0000001a` | CSS |
| 默认过渡 | 150ms `cubic-bezier(.4,0,.2,1)` | CSS `--default-transition-duration:.15s` |
| ease-out | `cubic-bezier(0,0,.2,1)` | CSS `--ease-out` |
| 侧栏宽度动画 | 200ms ease-out | `duration-200 ease-out` |
| 折叠内容动画 | 300ms ease-in-out（Radix collapsible，高度过渡） | `nx` 组件：`duration-300 ease-in-out` + `animate-collapsible-*` |
| 分组内容动画 | 200ms ease-out（grid-template-rows + opacity） | `transition-[grid-template-rows,opacity] duration-200 ease-out` |
| 任务标题跑马灯 | 悬停 1s 后启动，速度 40px/s，最短 6s，首尾淡入 0.15，左右渐隐 24px | JS 常量 `bDt=1000, vDt=40, bL=6, xDt=0.15` |
| 远程连接呼吸 | 1.8s ease-in-out infinite | CSS `@keyframes workspace-remote-connecting-breathe` |
| 断点 | sm 640 / md 768 / lg 1024 / xl 1280 px | CSS `@media (width>=48rem)` 等；`max-md` = `not all and (width>=48rem)` |
| 移动判定 | `(max-width: 767px)` | JS 常量 `OXn` |
| z-index 阶梯 | 10 拖拽线 / 20 顶栏浮层 / 30 移动折叠按钮 / 40 拖放遮罩与切换遮罩 / 50 对话框 / 60 菜单与 Select | CSS `.z-10/20/30/40/50/60` |

`confirmed`（除面板圆角的平台分支为 `confirmed` 常量、实际平台取值 `inferred`）。

---

## 3. 桌面外壳（WorkspaceShellLayout）

组件：`QXn`（`WorkspaceShellLayout`），入口 `ZXn`（memo 包装）。主渲染见 `index-Dwhx77cK.js` byte 6,070,456 起。

### 3.1 结构树（含 class 列表）

```text
div[data-workspace-shell="true"]
  class="relative flex h-full min-h-0 w-full overflow-hidden
         [anchor-scope:--sidebar-task-list-divider]"
  style="--workspace-sidebar-panel-width:{visible?Wn:pad}px;
         --workspace-sidebar-width:{Wn}px;
         --workspace-panel-radius:{12}px;
         --workspace-resize-handle-inset:{16}px;
         [--web-remote-navigation-height:{h}px]"      ← 仅 Web 远控壳
│
├─ div[data-panel][data-workspace-sidebar-panel="true"][id="sidebar"]
│  class="w-[var(--workspace-sidebar-panel-width)] max-w-[50%] flex-none overflow-hidden
│         duration-200 ease-out transition-[width,opacity]
│         data-[workspace-sidebar-resizing=true]:transition-opacity
│         {cr?opacity-100:pointer-events-none opacity-0}"
│  class(移动)="max-md:!h-[var(--web-remote-navigation-height)] max-md:!min-h-0
│               max-md:!w-full max-md:!max-w-none max-md:!flex-none max-md:!basis-auto
│               max-md:border-b max-md:border-border max-md:bg-sidebar"
│  class(移动·折叠)="max-md:!h-0 max-md:!min-h-0 max-md:!w-full max-md:!max-w-none
│                    max-md:!flex-none max-md:!basis-0 max-md:!border-b-0"
│  └─ aside[id="web-remote-control-navigation-panel"(仅远控)] class="h-full overflow-hidden select-none
│            max-md:min-h-0 max-md:flex-1 {!Fr && max-md:hidden}"
│     aria-hidden="{!cr || !Fr}"
│     └─ <侧栏内容：见 §5>
│
├─ div[role="separator"][aria-controls="sidebar"][data-testid="resizable-handle"]
│  class="group/handle relative z-10 flex h-full w-1 shrink-0 touch-none
│         cursor-ew-resize items-center justify-center bg-transparent outline-none
│         [app-region:no-drag] focus:outline-none focus-visible:ring-0
│         after:pointer-events-none after:absolute after:rounded-full
│         after:bg-foreground-subtlest/50 after:opacity-0
│         after:transition-opacity after:content-['']
│         after:inset-y-[var(--workspace-panel-radius)] after:w-0.5
│         hover:after:opacity-100 data-[separator=hover]:after:opacity-100
│         data-[separator=active]:after:opacity-100 focus-visible:after:opacity-100
│         [[data-workspace-sidebar-resizing=true]_&]:after:opacity-100
│         {sn && max-md:hidden}"
│  aria-orientation="vertical" aria-valuemin="264" aria-valuenow="{round(width)}"
│  仅 cr && Fr 时渲染
│
├─ span[data-sidebar-divider-extension="true"] aria-hidden
│  class="pointer-events-none absolute h-px w-1 bg-border/50
│         [left:anchor(--sidebar-task-list-divider_right,-4px)]
│         [top:anchor(--sidebar-task-list-divider_top,-1px)] {sn && max-md:hidden}"
│  仅 cr && Fr 时渲染（把侧栏分割线延伸到拖拽柄）
│
└─ div[data-panel][id="content"]
   class="flex min-w-[320px] flex-1 flex-col {p-0|pr-1 pb-1|pr-1.5 pb-1.5}
          {sn && max-md:!w-full max-md:!min-w-0 max-md:!flex-1 max-md:!basis-0}"
   ├─ div class="w-full [app-region:drag] {h-1|h-1.5}"     ← 顶部拖拽条
   └─ U8(layoutId="workspace-body-layout", panelIds=["conversation-column","browser"])
      class="min-h-0 flex-1 {max-md:!flex-col}"
      ├─ W8[id="conversation-column"] minSize=35% defaultSize=52%(右侧面板打开时)
      │  └─ 垂直分栏：conversation / terminal
      │     └─ section[data-workspace-conversation-frame="true"]
      │        class="relative flex h-full min-h-0 flex-1 flex-col overflow-hidden bg-background
      │               rounded-[var(--workspace-panel-radius)] border border-border
      │               border-[0.5px] shadow-xs
      │               max-md:rounded-none max-md:border-x-0 max-md:border-b-0"
      │        ├─ header[data-testid="workspace-header"]  ← §3.6
      │        ├─ button[data-web-remote-navigation-resize-handle]（仅远控移动） ← §4
      │        └─ div class="min-h-0 flex-1 overflow-hidden" > 主内容
      └─ W8[id="browser"]（右侧面板，min 35%）
```

`confirmed`：所有 class 字符串逐字取自 JSX（byte 6,070,456–6,076,300）。

### 3.2 外壳尺寸表

| 元素 | 尺寸 | 说明 |
|---|---|---|
| 外壳 | 100% × 100%，`min-h-0`，`overflow-hidden` | 根 `h-full` |
| 侧栏面板 | 宽 = `--workspace-sidebar-panel-width`；`max-width:50%`；`flex:none` | 隐藏时 0 |
| 侧栏面板最大宽 | 容器宽 50% | `max-w-[50%]` |
| 拖拽柄 | 宽 4px（`w-1`），高 100% | 视觉线 2px（`w-0.5`） |
| 拖拽线上下内缩 | `--workspace-panel-radius` = 12px | `after:inset-y-[var(--workspace-panel-radius)]` |
| 内容面板 | `flex:1`，`min-width:320px` | 移动端 `min-w-0` |
| 内容面板内边距 | Web 0；桌面 Win `pr-1.5 pb-1.5`（6px）；mac/Linux `pr-1 pb-1`（4px） | `p-0` / `pr-1.5 pb-1.5` |
| 顶部拖拽条 | 4px（非 Win）/ 6px（Win） | `h-1` / `h-1.5` |
| 会话框边框 | 0.5px（`border-[0.5px]`）+ 1px 主题边框 | 圆角 12，`shadow-xs` |
| 侧栏宽度默认 | 264px | `MXn=264` |
| 侧栏宽度最小 | 264px | `u7=264` |
| 侧栏宽度最大 | `min(拖动值, 容器宽 × 0.5)`，且 ≥264 | `d7()` |
| 键盘步进 | ±16px；Home=264；End=50% | `IXn=16`, `NXn=0.5` |
| 自动收起阈值 | 会话列 < 360px 收侧栏；< 480px 收右侧面板 | `VXn=360`, `zXn=480` |
| 自动收起防抖 | 300ms | `HXn=300` |

`confirmed`。

### 3.3 侧栏宽度逻辑（可直接照抄）

```js
// byte 6,037,884 起
MXn = 264            // 默认宽
u7  = 264            // 最小宽
NXn = 0.5            // 最大 = 容器 50%
IXn = 16             // 键盘步进
PXn = "zcode:workspace-shell:sidebar-width-px"   // localStorage key
FXn = "react-resizable-panels:workspace-shell-layout:sidebar:content" // 旧百分比 key

function clamp(width, containerWidth) {
  const max = containerWidth > 0 ? Math.max(264, containerWidth * 0.5) : Infinity;
  return Math.round(Math.max(264, Math.min(width, max)));
}
```

- 启动：先读 `PXn`；无值则读 `FXn` 的 `sidebar` 百分比 × 容器宽；再无则 264。
- 拖拽：`pointerdown` 记录起点；`pointermove` 实时 `clamp(startW + dx)`；`pointerup` 持久化。
- 拖拽期间给 shell 与侧栏面板加 `data-workspace-sidebar-resizing="true"`，拖拽线常显。
- 键盘：左右箭头 ±16，Home 264，End 50%，均持久化。
- `aria-valuemin=264`、`aria-valuenow` 实时更新。

`confirmed`。

### 3.4 折叠/展开

| 状态 | 侧栏面板 | aside | 拖拽柄 | 分割线延伸 |
|---|---|---|---|---|
| 展开 | 宽 = Wn，`opacity-100` | 可见 | 渲染 | 渲染 |
| 折叠 | 宽 = 0（Web）/ 4px 或 6px（桌面），`opacity-0` + `pointer-events-none` | 不可见 | 不渲染 | 不渲染 |

- 过渡：`transition-[width,opacity] duration-200 ease-out`；拖拽中改为只过渡 opacity。
- 折叠不记忆：可见性由宿主传入的 `isSidebarVisible` 控制；本包内**没有**侧栏折叠状态的 localStorage 持久化（只有宽度持久化）。Web 远控跨 workspace 跳转时用 `sessionStorage["zcode:web-remote-control:collapse-navigation-once"]` 一次性收起。
- 浏览器宽屏**没有可见的折叠按钮**（见 §3.5 与 §9）；折叠可由宿主快捷键触发（`workspaceSidebar.toggleSidebar` 仅用于桌面端按钮）。

`confirmed`（折叠视觉）；「浏览器无折叠按钮」为 `confirmed`（BAn 中 `A = windows || linuxDesktop`，mac 才渲染图标按钮）。

### 3.5 顶栏浮层（TopOverlayer）

组件 `BAn`，渲染于 shell 根节点末尾（byte 4,865,201 附近）。

```text
div class="@container/topoverlayer pointer-events-none absolute h-14 flex left-0 top-0 z-20 w-fit
           {A && mt-px} {windows? top-1.5 : (linuxDesktop? top-1)}"
    style="{visible? width:var(--workspace-sidebar-panel-width)}"
└─ div class="flex items-center {mac? h-14} {A? h-12} {A? pl-3 ml-px} {mac? pt-1/pl-3/pl-5}"
   style="{mac? paddingLeft:96px} {windows? paddingRight:136px}"
   └─ div class="pointer-events-auto flex items-center gap-1 shrink-0 [app-region:no-drag]"
      ├─ [Win/Linux] button 28px（icon-md, rounded-lg, group relative overflow-hidden）
      │   ├─ span size-5（logo 20px 渐变方块，hover 淡出）
      │   └─ icon size-4（sidebar 图标，hover 淡入）
      ├─ [macOS] button 28px（sidebar 图标 16px）
      ├─ [非 hideTaskNavigationButtons] 后退 28px + 前进 28px（图标 16px）
      ├─ div class="inline-flex overflow-hidden transition-[opacity,width] duration-300 ease-out
      │              {show? w-7 opacity-100 : pointer-events-none w-0 opacity-0}"
      │   └─ button 28px（新建任务图标 16px）
      └─ 更新徽标（可选）：h-5 rounded-full bg-success text-success-foreground text-ui-xs
```

尺寸：外层高 56px；mac 内层 56px；Win/Linux 内层 48px 且 `top-1.5`（6px）、`mt-px`、`pl-3`；按钮 28×28；图标 16px；按钮间距 4px；新建任务显隐动画 300ms。

`confirmed`。浏览器（非桌面）只有后退/前进/新建任务，无折叠按钮。

### 3.6 内容区顶栏（Workspace Header）

```text
header[data-testid="workspace-header"][data-workspace-header-variant="task|draft"]
  class="@container/workspace-header relative flex w-full shrink-0 h-12 border-b
         {draft? border-transparent : border-border/50}"
├─ [draft 拖放] div class="absolute inset-0 z-40 bg-accent/55 backdrop-blur-sm pointer-events-auto"
└─ div class="flex h-12 flex-1 min-w-0 items-center justify-between gap-2 overflow-hidden p-2
            [app-region:drag] transition-[padding] duration-300 {pl-38|pl-44|pl-58|pl-66...}"
   ├─ 左：工作区/任务标题区（zLn）
   └─ 右：动作区（RLn）— 终端/浏览器/侧面板/帮助等
```

- 高 48px，内边距 8px，下边框 `border-border/50`（50% 透明）。
- 侧栏折叠时左侧 padding 变大以避开顶栏浮层：非 mac `pl-38`=152px（有更新徽标 `pl-44`=176px）；mac `pl-58`=232 / `pl-66`=264 / `pl-38` / `pl-48`。
- 这是「内容区顶栏」，不是侧栏的一部分。

`confirmed`。

### 3.7 面板与分割线

- 会话面板：圆角 12、0.5px 边框、`shadow-xs`；右侧面板打开时圆角与边框仍为同一套。
- 侧栏与内容之间没有独立 1px 分隔线：视觉分割由侧栏内的拖拽柄 2px 线与 `span[data-sidebar-divider-extension]`（1px 高、4px 宽、`bg-border/50`）组成，锚定到 `--sidebar-task-list-divider`。
- 侧栏内部：主操作区与任务列表之间是 `border-t border-border/50`（50% 透明 1px）。
- 任务列表工具条与滚动区之间无分割线（靠 `pt-3` 间距）。

`confirmed`。

### 3.8 关于「Tab」

- **没有底部 Tab、没有顶部 Tab 栏、没有 workspace 标签条**。全包唯一的 `tabBar` 是桌面窗口框架组件 `m2` 的未使用 prop（`index-Dwhx77cK.js` byte 4,402,884 附近）。
- 导航语义由三部分组成：
  1. 侧栏「视图」下拉（radio 菜单）：按项目 / 按分组 / 最近更新 / 按未读；
  2. 侧栏区块（可折叠、可拖拽排序）：项目 / 任务；
  3. 右侧面板的页签（终端 / 浏览器 / 审查等）——属于 side pane 组件，不在本次外壳范围内。
- 因此 Compose 重写时**不要**照搬 `docs/UI-SPEC.md` 的底部双 Tab；如果需要移动端底部导航，应作为安卓平台化增强另行设计，而不是「还原网页」。

`confirmed`（`tabBar` 仅作为 `m2` 的 prop 出现，且 shell 渲染 `m2` 时未传入）。

---

## 4. 移动端外壳（Web 远控壳，<768px）

仅在 `isWebRemoteControlShell` 为真时启用；普通桌面浏览器窄窗口不会切换。

### 4.1 切换条件与状态

| 项 | 值 | 证据 |
|---|---|---|
| 断点 | `(max-width: 767px)` | `OXn`，byte 6,037,789 |
| 面板默认高 | `round(min(max(0, viewportH*0.5 - 48), 352))` | `_Xn()`，`dXn=0.5, mXn=48, fXn=352` |
| 折叠阈值 | 高度 ≤24px 视为折叠（归零） | `pXn=24` |
| 最大高 | `viewportH - 48 - 168` | `vXn()`，`mXn=48, hXn=168` |
| 拖拽阈值 | 4px | `jXn=4` |
| 折叠后 | 面板 `h-0`、`basis-0`、无下边框；aside `max-md:hidden` | 面板 class 分支 |
| 展开后 | 面板 `h-[var(--web-remote-navigation-height)]`、全宽、`border-b`、`bg-sidebar` | 同上 |
| 折叠按钮 | 28×28（icon-md），绝对居中 `left-1/2`，`z-30`，`top-0`（无 header）/`top-12`（有 header） | `[data-web-remote-navigation-resize-handle]` byte 6,076,115 |
| 折叠按钮样式 | `border-border bg-surface text-foreground-subtle shadow-sm hover:bg-surface-hover hover:text-foreground`，图标 16px | 同 |
| 跨 workspace 记忆 | `sessionStorage["zcode:web-remote-control:collapse-navigation-once"]` | `kXn` |

`confirmed`。

### 4.2 移动端结构

```text
div[data-workspace-shell] class="... max-md:flex-col"
├─ div[data-panel][data-workspace-sidebar-panel] class="max-md:!h-[var(--web-remote-navigation-height)]
│     max-md:!w-full max-md:border-b max-md:border-border max-md:bg-sidebar"
│  └─ aside#web-remote-control-navigation-panel class="max-md:min-h-0 max-md:flex-1"
│     └─ 侧栏内容（主操作区 + 任务列表 + 页脚）
└─ div[data-panel][id=content] class="max-md:!w-full max-md:!min-w-0 max-md:!flex-1 max-md:!basis-0"
   ├─ 顶部拖拽条
   ├─ 会话框（header 48px）
   └─ button[data-web-remote-navigation-resize-handle]
      class="absolute left-1/2 z-30 hidden -translate-x-1/2 -translate-y-1/2 touch-none
             cursor-ns-resize border-border bg-surface text-foreground-subtle shadow-sm
             hover:bg-surface-hover hover:text-foreground max-md:inline-flex"
      aria-expanded="{!collapsed}" aria-controls="web-remote-control-navigation-panel"
      aria-label="展开任务和工作区 / 收起任务和工作区"
```

- 移动端侧栏内主操作区额外 `max-md:pt-11`（44px）以避开顶栏浮层（仅当没有设备切换器时）。
- 页脚仍在导航面板内（`shrink-0`），高度不足时列表区压缩。
- 侧栏 `aside` 的拖拽 spacer（h-12）在移动端 `max-md:hidden`。

`confirmed`（结构）；页脚在小高度下的实际可见性 `inferred`。

### 4.3 移动端高度公式（Compose 可直接用）

```kotlin
fun navHeight(viewportPx: Int): Int {
    val max = (viewportPx * 0.5f - 48f).coerceIn(0f, 352f)
    return max.roundToInt()
}
fun clampNav(h: Int, viewportPx: Int): Int {
    val max = (viewportPx - 48 - 168).coerceAtLeast(0)
    val v = h.coerceIn(0, max)
    return if (v <= 24) 0 else v
}
fun toggleNav(current: Int, collapsed: Boolean, viewportPx: Int): Int =
    if (!collapsed && current > 24) 0 else navHeight(viewportPx)
```

`confirmed`（常量与公式）。

---

## 5. 侧栏内部

组件 `sLt`（`WorkspaceSidebar`），`data-testid="sidebar"`（`PHe`）。

### 5.1 结构树

```text
aside[data-testid="sidebar"]
  class="flex h-full min-h-0 flex-col overflow-hidden"
├─ div class="h-12 shrink-0 [app-region:drag] {webRemote? max-md:hidden}"   ← 顶部拖拽留白 48px
└─ div class="relative min-h-0 flex-1 overflow-clip"
   └─ div[data-project-sidebar-panel="navigation"]
      class="absolute inset-0 flex min-h-0 flex-col"
      ├─ div[data-testid="sidebar-primary-navigation"]
      │  class="flex flex-col gap-1 px-2 py-3 {webRemote && !deviceNav ? max-md:pt-11 : ''}"
      │  ├─ [可选] 设备切换按钮（size=lg, w-full justify-start gap-2）
      │  ├─ div[data-testid="task-new-button"] role="group"
      │  │  class="group w-full h-8 rounded-lg inline-flex shrink-0 items-center justify-stretch
      │  │         gap-2 overflow-hidden pl-2.5 pr-2.5 hover:bg-surface-hover hover:text-foreground
      │  │         active:translate-y-0 cursor-pointer {disabled? cursor-not-allowed text-foreground-subtlest}"
      │  │  ├─ 图标 16px（plus）
      │  │  ├─ span.truncate "新建任务"
      │  │  └─ span.ml-auto.text-ui-xs.text-foreground-subtlest（快捷键）
      │  ├─ [非远控] 自动化按钮（size=lg, w-full justify-start gap-2, 激活 bg-selected）
      │  └─ [非远控] 插件市场按钮（同上）
      │
      ├─ div[data-testid="sidebar-task-list-region"]
      │  class="flex min-h-0 flex-1 flex-col border-t border-border/50 pt-3
      │         [anchor-name:--sidebar-task-list-divider]"
      │  ├─ div[data-testid="sidebar-search-toolbar"]
      │  │  class="flex min-w-0 shrink-0 items-center gap-1 px-2 pb-2"
      │  │  ├─ span[data-testid="sidebar-list-title"]
      │  │  │  class="min-w-0 flex-1 truncate px-2.5 text-ui-base font-medium text-foreground-subtle"
      │  │  │  文案："任务列表"
      │  │  ├─ div（分组视图动作挂载点；非 grouped 时 `hidden`）
      │  │  ├─ button[data-testid="sidebar-search-open"]（icon-sm 24，图标 14）
      │  │  └─ Dropdown trigger button[data-testid="sidebar-view-menu"]（icon-sm 24，图标 14）
      │  │     └─ 菜单：4 个 radio item（按项目/按分组/最近更新/按未读）
      │  └─ div class="relative flex min-h-0 flex-1 flex-col"
      │     ├─ div（吸顶挂载点）
      │     └─ div class="min-h-0 flex-1 overflow-y-auto overflow-x-hidden {mask}"  ← 滚动区
      │        └─ div class="flex flex-col gap-3 pb-3"
      │           ├─ [projects] 置顶任务区 + 置顶项目列表 + 区块列表
      │           ├─ [grouped]  置顶任务区 + 分组列表（px-2 pb-4）
      │           ├─ [timeline/unread] 时间线/未读列表（px-2）
      │           ├─ 加载：p class="px-4 text-ui-sm text-foreground-subtle" "加载中..."
      │           └─ 错误：p[role=alert] class="px-4 text-ui-sm text-destructive"
      │
      └─ footer class="flex shrink-0 flex-col gap-2.5 px-4 pt-2 pb-4 {className}"
         ├─ banner（可选）
         └─ div class="flex min-w-0 gap-2"
            ├─ 用户菜单 trigger button（size=lg）
            │  class="min-w-0 flex-1 justify-start gap-2 overflow-hidden rounded-tl-2xl
            │         rounded-bl-2xl border-0 pl-0"
            │  ├─ Avatar 32×32 rounded-full（边框叠加层）
            │  └─ span.truncate.text-ui-base.font-semibold（用户名）
            └─ div class="flex shrink-0 items-center gap-1.5"
               ├─ [桌面] 文件按钮（可选）
               └─ button[data-testid="task-settings-button"]（icon-lg 32，图标 16）
```

`confirmed`：class 与层级逐字取自 JSX（byte 2,787,208 / 6,044,487 起）。

### 5.2 主操作区尺寸

| 元素 | 尺寸 | 间距 |
|---|---|---|
| 容器 | 全宽，`px-2 py-3` | 上下 12px，左右 8px |
| 新建任务行 | 高 32px，`rounded-lg` 8，`pl/pr 10px` | 图标 16 + gap 8 + 文案 14px + 快捷键 10px |
| 自动化/插件行 | 高 32px（size=lg），`rounded-lg` 8，`px-2.5` | 图标 16 + gap 8 |
| 行间距 | `gap-1` = 4px | — |
| 顶部拖拽留白 | 48px | 桌面 |

`confirmed`。

### 5.3 任务列表工具条

| 元素 | 尺寸 | 样式 |
|---|---|---|
| 容器 | 高自适应，`px-2 pb-2`（左 8 / 右 8 / 下 8） | `gap-1` |
| 标题 | 高约 20px，`px-2.5`，14px medium | 颜色 `foreground-subtle`；`truncate` |
| 搜索按钮 | 24×24（icon-sm），图标 14 | ghost；`text-foreground-subtle hover:text-foreground` |
| 视图按钮 | 24×24，图标 14 | 同上 |
| 视图菜单 | 最小宽 128px，圆角 8，`p-1`，`gap-0.5`，`bg-menu`，`shadow-md` | item 最小高 28，`px-2 py-1`，14/1.625 |
| 菜单项 | 图标 16 + 文案 14 | 选中/hover `bg-menu-hover` |

`confirmed`。

### 5.4 滚动区与页脚

- 滚动区：`overflow-y-auto overflow-x-hidden`，上下渐隐 24px（`mask-image: linear-gradient(...black 24px...)`），滚动条为自绘（`data-scrollbar-*`，1s 后淡出）。
- 页脚：`gap-2.5`（10px），`px-4 pt-2 pb-4`（左 16 / 右 16 / 上 8 / 下 16）。
- 用户行：高 32px，头像 32px，`rounded-tl-2xl rounded-bl-2xl`（左上/左下 16px），左内边距 0，gap 8。
- 设置按钮：32×32（icon-lg），图标 16。
- 用户菜单：`min-w-50`（200px）、`max-w-[calc(100vw-2rem)]`；内含语言/主题/界面模式/缩放/额度/奖励/登录登出。

`confirmed`。

---

## 6. 首页列表（项目树 / 任务列表）

### 6.1 视图模型

| 视图 | 标识 | 数据 | 排序 | 分页 |
|---|---|---|---|---|
| 按项目 | `projects` | 置顶任务 + 置顶项目 + 「项目」区块 + 「任务」区块 | 项目：手动排序；任务：`updatedAt` 倒序 | 项目内任务 5 条 + 显示更多（每次 +5） |
| 按分组 | `grouped` | 置顶任务 + 分组节点 + 未分组任务 | 组内手动排序 | 无分页（虚拟列表 >80 条） |
| 最近更新 | `timeline` | 全部任务 | `updatedAt` 倒序（tie: createdAt, taskId） | 20 条 + 显示更多（每次 +20） |
| 按未读 | `unread` | 有 `unreadAt` 的任务 | 同上 | 20 条 + 显示更多 |

- 视图持久化：`localStorage["zcode-project-sidebar-preferences"]` 的 `view`；旧键 `zcode-sidebar-task-preferences.organizeBy` 兼容（`grouped`/`chronological`→`timeline`，默认 `projects`）。
- 区块顺序/展开：`localStorage["zcode-sidebar-purpose-section-preferences"]`（`projectsExpanded`/`conversationsExpanded`/`sectionOrder`）。
- 分组折叠：`localStorage["zcode-grouped-task-collapsed-groups"]`。

`confirmed`（键名与默认值逐字）。

### 6.2 置顶区

```text
div[data-testid="project-pinned-tasks"] class="px-2"
├─ h3 class="px-2.5 py-1 text-ui-base font-medium text-foreground-subtlest"  "已置顶"
├─ (任务行 × N，最多 20，默认)
└─ button[data-task-list-show-more="true"]
   class="ml-8.5 h-auto p-0 text-ui-base text-foreground-subtlest
          hover:bg-transparent focus-visible:text-foreground"
   "显示更多" / "显示更少"
```

- 置顶任务来源：`pinned && !archived`；在「按项目」视图中，项目内任务列表会排除 pinned，避免重复。
- 置顶项目：在置顶任务区之后渲染一个**无标题**的项目行列表（`Sbt`），可拖拽排序；置顶项目在「项目」区块中不再重复。
- 置顶任务超过 20 条才出现「显示更多」；展开后显示全部，按钮变「显示更少」。
- 时间线/未读视图没有独立置顶区（置顶任务按时间混排）。

`confirmed`（结构/阈值）；置顶项目无标题为 `confirmed`（Sbt 渲染裸列表）。

### 6.3 项目行（Project Row）

```text
section[data-project-id][data-project-host][data-project-name][data-primary-root-status]
  class="min-w-0 rounded-xl {dropTarget? bg-accent}"
├─ div[data-testid="project-session-drop-target"]  （拖入时）
│  class="pointer-events-none absolute inset-0 z-10 rounded-xl border-2 border-icon-blue"
├─ div[data-testid="project-drop-indicator"][data-drop-position="before|after"]
│  class="pointer-events-none absolute left-5 right-2 z-20 h-0.5 bg-icon-blue
│         {before? top-0 -translate-y-1/2 : bottom-0 translate-y-1/2}"
│  └─ span class="absolute -left-1 top-1/2 size-2 -translate-y-1/2 rounded-full
│                  border-2 border-icon-blue bg-sidebar"
└─ HoverCard trigger
   └─ div[data-testid="virtual-project-row"][tabIndex=0]
      class="group/project-row ml-2 flex h-8 min-w-0 items-center gap-1 rounded-lg pr-1
             hover:bg-surface-hover"
      ├─ span class="flex min-w-0 flex-1 shrink-0"
      │  └─ button class="flex h-8 min-w-0 flex-1 justify-start gap-2 rounded-lg
      │                   pl-2.5 pr-1 text-left text-foreground hover:bg-transparent
      │                   aria-expanded:bg-transparent {primaryRootMissing? opacity-50}"
      │     ├─ span class="flex size-4 shrink-0 items-center justify-center"
      │     │  ├─ 图标 16（本地文件夹 / 远端云图标 / 连接状态图标）
      │     │  └─ [pinned] 图钉 16（默认 hidden，hover 显示；文件夹 hover 隐藏）
      │     ├─ span class="flex min-w-0 items-baseline gap-2"
      │     │  ├─ span class="truncate text-ui-base text-foreground-subtle"   项目名
      │     │  └─ span[data-testid="project-device"]
      │     │     class="max-w-24 truncate text-ui-sm text-remote-foreground-muted"  设备名
      │     ├─ [折叠 && 在线 && 有未读] span[data-testid="virtual-project-unread"]
      │     │  class="size-1.5 shrink-0 rounded-full bg-icon-blue"
      │     │  role="img" aria-label="项目有未读任务"
      │     ├─ [legacySingleRoot] 徽标 "单目录"（12px, foreground-subtle）
      │     └─ [离线/未解析] span[data-testid="project-status-label"] class="text-ui-sm
      │        {pending? D.foreground : text-foreground-subtle}"
      │        文案：离线 / 请选择项目 / 连接中 / 需要处理 等
      └─ div[data-testid="virtual-project-actions"]
         class="flex shrink-0 items-center gap-1 opacity-0 transition-opacity
                group-hover/project-row:opacity-100 group-focus-within/project-row:opacity-100
                [&:has([data-state=open])]:opacity-100 [@media(hover:none)]:opacity-100"
         ├─ button[data-testid="virtual-project-menu"]（icon-sm 24，图标 14，MoreHorizontal）
         ├─ [多根] 文件菜单按钮（icon-sm 24，图标 14）
         │  [单根] 文件按钮（icon-sm 24，图标 14）
         └─ 新建任务按钮（icon-sm 24，图标 14，Plus）
   └─ 折叠内容（Radix Collapsible，300ms）
      └─ div class="pl-2 pt-1" > 任务列表（§6.4，每项目 5 条 + 显示更多）
```

**尺寸表**

| 项 | 值 |
|---|---|
| 行高 | 32px（h-8） |
| 外边距 | 左 8px（`ml-2`），右 4px（`pr-1`） |
| 内边距 | 左 10px（`pl-2.5`），右 4px（`pr-1`） |
| 图标槽 | 16×16 |
| 图标与文字间距 | 8px（`gap-2`） |
| 名称 | 14px / 400 / `foreground-subtle`，单行截断 |
| 设备名 | 12px / 400 / `remote-foreground-muted`，最大宽 96px（`max-w-24`），截断 |
| 未读点 | 6×6（`size-1.5`），`icon-blue` |
| 状态文案 | 12px，`foreground-subtle`（pending 用 tone 前景色） |
| 行圆角 | 8px（`rounded-lg`） |
| 动作按钮 | 24×24，图标 14，间距 4px |
| 动作显隐 | 默认 opacity 0；hover / focus-within / 菜单打开 / 无 hover 设备时 opacity 100 |
| 项目下任务列表 | 左缩进 8px + 上 4px（`pl-2 pt-1`） |
| 拖放高亮 | 外层 `rounded-xl` + `bg-accent`；内描边 2px `icon-blue` |
| 拖放插入线 | 高 2px（`h-0.5`），左 20 / 右 8，圆点 8px |

`confirmed`。

**项目菜单项**（顺序）：新建任务、显示文件树（每个 root 一项）、编辑项目、置顶/取消置顶、删除项目、从本机移除、管理项目…；离线时额外「连接」。菜单宽 176px（`w-44` 上下文菜单）或 `min-w-44`。

`confirmed`。

### 6.4 任务行（默认视图）

```text
li[data-testid="task-item"][data-task-item-key="{workspaceKey}\0{taskId}"]
  class="group/task-item flex cursor-pointer gap-2 rounded-lg pl-2.5 pr-1 py-1
         transition-[background-color,border-color,box-shadow] items-center
         {active? bg-selected : hover:bg-surface-hover}"
  tabIndex=0
├─ div class="relative flex size-4 shrink-0 items-center justify-center"
│  ├─ span class="flex size-4 items-center justify-center transition-opacity
│  │              {loading? animate-spin} {showPin? hidden}"
│  │  ├─ error:  span[data-error-indicator] class="h-1.5 w-1.5 rounded-full bg-destructive"
│  │  ├─ unread: span[data-unread-indicator] class="size-[7px] rounded-full bg-icon-blue"
│  │  └─ running/prewarming: spinner 16px（text-foreground-subtle）
│  └─ [showPin] Tooltip > button（icon-xs 20，图标 16，pin）
│
└─ div class="flex min-w-0 flex-1 flex-col"
   └─ div class="flex min-w-0 items-center gap-2"
      ├─ div class="relative min-w-0 flex h-6 flex-1 flex-wrap items-center gap-1.5"
      │  ├─ [手机操作中] 图标 16（绝对定位 -left-6，text-success）
      │  ├─ 远程连接异常徽标（h-5, bg-warning-surface, 10px, warning 前景）
      │  ├─ 标题（CL 组件）：class="min-w-0 flex-1 overflow-hidden whitespace-nowrap"
      │  │  └─ span.task-title-marquee-track.inline-flex.w-max.min-w-max.items-center.gap-6
      │  │     ├─ span[data-task-title-copy=original]（14px, text-foreground）
      │  │     └─ [溢出时] span[data-task-title-copy=duplicate] aria-hidden（跑马灯副本）
      │  ├─ 交互徽标（等待确认，h-5 rounded-full bg-interaction-confirmation-surface，
      │  │  12px medium；可显示倒计时填充层）
      │  └─ 变更统计（+N/-N，12px，diff 色）
      ├─ span[data-task-row-metadata]
      │  class="mr-0.5 flex shrink-0 items-center gap-1 text-ui-sm text-foreground-subtle"
      │  ├─ 定时任务图标 14 / 闲时任务图标 14
      │  └─ 时间文案（刚刚/N分/N小时/N天）
      └─ span[data-task-row-actions] class="flex shrink-0 items-center gap-0.5"
         ├─ 文件按钮（icon-sm 24，图标 14）— hover/无 hover 设备时
         └─ 归档按钮（icon-sm 24，图标 14）— hover 时；确认态变 destructive "确认"
```

**尺寸表**

| 项 | 值 |
|---|---|
| 行高 | 32px（`py-1` 8 + 内容 `h-6` 24） |
| 左/右内边距 | 10px / 4px |
| 行圆角 | 8px |
| 状态槽 | 16×16；未读点 7px；错误点 6px；spinner 16px |
| 图标与内容间距 | 8px（`gap-2`） |
| 标题 | 14px / 400 / `foreground`；单行；溢出时 hover 跑马灯 |
| 元数据 | 12px / 400 / `foreground-subtle`；图标 14；右外边距 2px |
| 动作按钮 | 24×24，图标 14，间距 2px |
| 图钉 | 置顶任务在状态为 `none` 时状态槽常显图钉；任意任务 hover/focus 时也显示（置顶=取消置顶，非置顶=置顶），同时隐藏未读/运行状态 |
| 行间距（同一列表） | 2px（`space-y-0.5`）；项目任务之间 2px（每项 `py-px`） |
| hover | `bg-surface-hover`（dark 与 selected 同为 `#ffffff1a`） |
| 选中 | `bg-selected`（持久） |
| 无 hover 设备 | 动作常显（`hoverEnabled=false` 时按 `[@media(hover:none)]` 或 `isMobileActive` 显示） |

`confirmed`。

### 6.5 时间线任务行

与默认行同源，差异：

- `li` 增加 `items-start py-1.5`；状态槽 `mt-0.5`。
- 内容为两行：
  1. 标题行（`h-6`，同默认，但标题与徽标在同一行）。
  2. 元数据行：`flex min-w-0 items-center justify-between gap-2 text-ui-base text-foreground-subtle h-6`。
     - 左：工作区/项目标签（`truncate`）+ `·` + 任务来源标签（若有）。
     - 右：变更统计、定时/闲时图标、时间（`mr-1`）。
- 行高约 62px（12 + 24 + 2 + 24）。
- 时间线日期标题：`h3` class `px-2.5 pt-2 pb-1 text-ui-base font-medium text-foreground-subtle`；分组键：today/yesterday/daysAgo/thisWeek/lastWeek/thisMonth/lastMonth/older。

`confirmed`（类名与结构）；62px 为 `inferred`（按行盒相加，无运行态像素）。

### 6.6 分组视图（Grouped）

```text
div[data-testid="task-grouped-view"] class="px-2 pb-4"
├─ 草稿行（无激活任务时）
├─ 分组节点
│  └─ div[data-grouped-group-item-id][data-task-group-id][data-task-group-title][data-group-collapsed]
│     class="relative pt-2.5 pb-0 transition-[width,max-width] duration-200 ease-out"
│     ├─ 组头 div class="mb-0.5 flex h-8 items-center gap-1 rounded-lg border border-transparent
│     │                  pl-1.5 pr-1 text-ui-base text-foreground
│     │                  hover:bg-surface-hover"
│     │  ├─ 颜色圆点 20×20（rounded-full，内含 12px 图标）
│     │  ├─ 组名（14px，truncate；可内联重命名）
│     │  ├─ 展开/折叠 chevron 14px（foreground-subtlest）
│     │  ├─ 数量徽标 class="inline-flex min-w-5 shrink-0 items-center justify-center
│     │  │                   rounded-full bg-tag/50 px-1.5 py-0.5 text-ui-sm font-medium
│     │  │                   leading-none text-foreground-subtle"
│     │  └─ 新建任务按钮（icon-sm 24，图标 14）
│     ├─ 组内容（grid 展开动画 200ms）
│     │  └─ div class="ml-4 border-l py-px pl-2 {colorBorder}"
│     │     ├─ 草稿行（若在该组）
│     │     ├─ 空组：button class="flex h-8 w-full items-center justify-center gap-1.5
│     │     │        rounded-lg border border-dashed border-border px-2 py-2 text-center
│     │     │        text-ui-base text-foreground-subtle hover:border-border-hover
│     │     │        hover:bg-surface-hover hover:text-foreground"
│     │     └─ 分组任务行
│     └─ 底部占位 div class="h-2.5"（折叠时 h-0）
├─ 空态：div class="px-3 py-2 text-ui-base text-foreground-subtle" "暂无任务"
└─ 错误：p[role=alert] class="px-3 text-ui-sm text-destructive"
```

**分组任务行**

```text
div[role=button][data-grouped-task-key][data-task-item-key][data-testid="task-item"]
  class="group/task-row flex min-h-7 w-full min-w-0 flex-col justify-center rounded-lg
         border border-transparent pl-2.5 pr-1 text-left text-ui-base
         transition-[background-color,border-color,color,opacity]
         {active? bg-selected : hover:bg-surface-hover}"
└─ span class="flex h-7 w-full min-w-0 items-center gap-2"
   ├─ 标题（CL，14px，text-foreground）
   └─ span class="ml-auto flex shrink-0 items-center gap-1.5 text-ui-sm text-foreground-subtle"
      ├─ 交互徽标 / 变更徽标（h-5）
      ├─ 状态：spinner 14 或 未读点 6px
      ├─ 定时/闲时图标 14
      ├─ 时间（12px）
      └─ hover 动作：文件 / 移动到顶部 / 关闭（各 24，图标 14）
```

- 分组头高 32px；任务行高 28px（`h-7`）；组内左边框 1px + 左内边距 8px + 左外边距 16px。
- 分组头可拖拽排序；组内任务可跨组拖拽；拖拽覆盖层 `shadow-lg` + `border-border` + `bg-background`。
- 组头吸顶：`div.px-2.pb-1` + `div class="flex h-8 items-center gap-1 rounded-lg border border-border
  bg-background pl-1.5 pr-1 text-ui-base text-foreground shadow-sm"`。

`confirmed`。

### 6.7 草稿行（分组视图）

```text
div[data-testid="grouped-draft-task"][role=button][tabIndex=0]
  class="group/task-row flex h-7 w-full min-w-0 cursor-pointer items-center gap-2
         rounded-lg pl-2.5 pr-1 text-left text-ui-base transition-colors
         border border-dashed
         {active? bg-selected border-primary/35 : bg-success/10 border-primary/20 hover:bg-success/14}"
├─ span.truncate.text-foreground-subtlest "新建任务"
└─ span class="ml-auto flex shrink-0 items-center gap-1.5 text-ui-sm text-foreground-subtle"
   ├─ 工作区标签（max-w-24 truncate rounded-full bg-tag/50 px-1.5 py-0.5）
   └─ 状态点 6px（bg-green-500 dark:bg-green-400）→ hover 变关闭按钮
```

`confirmed`。

### 6.8 更多菜单（任务右键 / 行尾菜单）

- 触发器：任务行 `onContextMenu`；项目行菜单按钮；分组头菜单。
- 容器：`z-[60] flex flex-col gap-0.5 min-w-44 overflow-hidden rounded-lg border
  border-popover-border bg-menu p-1 text-foreground shadow-md duration-100`。
- 菜单项：`relative flex min-h-7 cursor-default items-center gap-2 rounded-md px-2 py-1
  text-ui-base/relaxed text-foreground data-[highlighted]:bg-menu-hover`；
  disabled：`text-foreground-subtlest`、`pointer-events-none`、opacity 保持 100。
- 分隔线：`-mx-1 my-1 h-px bg-border`。
- 任务菜单项顺序：置顶/取消置顶、重命名、归档/取消归档、标记为未读、重启任务（可选）、分隔线、打开方式、复制路径、复制任务路径、复制日志路径、复制会话 ID、前往配置、查看调用轨迹、反馈问题。
- 菜单宽度：任务菜单 `w-52`（208px）；分组菜单 `w-44`（176px）；项目菜单 `min-w-44`。
- 动画：`data-open:animate-in fade-in-0 zoom-in-95`，100ms。

`confirmed`。

### 6.9 空态 / 加载 / 错误 / 离线

| 场景 | DOM | 文案 | 样式 |
|---|---|---|---|
| 侧栏列表加载 | `p` | 加载中... | `px-4 text-ui-sm text-foreground-subtle` |
| 侧栏列表错误 | `p[role=alert]` | 错误文本 | `px-4 text-ui-sm text-destructive` |
| 项目任务空 | `div[data-testid=project-no-tasks]` | 暂无任务 | `px-8.5 py-2 text-ui-base text-foreground-subtlest` |
| 未读视图空 | 同上 | 暂无未读任务 | 同上 |
| 分组视图空 | `div` | 暂无任务 | `px-3 py-2 text-ui-base text-foreground-subtle` |
| 分组错误 | `p[role=alert]` | 错误文本 | `px-3 text-ui-sm text-destructive` |
| 单任务列表加载行 | `div` | 正在获取任务... | `flex items-center gap-2 px-2.5 py-1 text-ui-base text-foreground-subtlest` + spinner 16 |
| 分组空组 | 虚线按钮 | 新建任务，或拖拽到这里。 | 高 32，`border-dashed border-border`，14px |
| 项目离线 | 状态文案 | 离线 / 连接中 / 需要处理 / 连接失败 | 12px；pending 用 tone 前景色 |
| 项目主目录缺失 | 按钮 `opacity-50` | — | 禁止展开/新建 |
| 任务远端连接未知 | 徽标 | 远程连接暂不可用 | `h-5 bg-warning-surface px-2 text-ui-xs font-medium text-warning-surface-foreground` |
| 工作区目录不可用 | 禁用行 + tooltip | 工作区目录不存在或无法访问… | `workspaceSidebar.unavailableLocalDirectory` |

- **没有骨架屏**：首页没有 skeleton 组件（全包 skeleton 只用于插件商店/图表）。
- 空态不使用插画/图标，纯文本。
- 错误不占满屏，作为列表内一行红字。

`confirmed`。

### 6.10 长文本与截断

| 位置 | 规则 |
|---|---|
| 项目名 | `truncate`（单行省略） |
| 设备名 | `max-w-24`（96px）+ `truncate` |
| 任务标题 | `overflow-hidden whitespace-nowrap`；溢出时 hover 跑马灯（副本 gap 24px，40px/s，1s 延迟，最短 6s） |
| 时间线工作区标签 | `truncate` |
| 任务来源标签 | `max-w-28`（112px）+ `truncate` |
| 分组名 | `truncate`，重命名时按文本宽度测量（`ResizeObserver`） |
| 区块标题 | `truncate` |
| 用户菜单名字 | `truncate` |
| 悬停卡路径 | `break-all` + `font-mono` |
| 菜单项 | 默认不截断；个别 `truncate` |

`confirmed`。

---

## 7. 状态矩阵

| 状态 | 触发 | 视觉 | 证据 |
|---|---|---|---|
| hover（行） | 鼠标进入 | `bg-surface-hover`；动作按钮 opacity 0→100 | `hover:bg-surface-hover` |
| hover（ghost 按钮） | 鼠标进入 | `bg-hover`（5%） | Button cva `ghost` |
| active/选中（行） | 当前任务 | `bg-selected`（10%） | `bg-selected` |
| focus（行） | Tab | 无自定义外框；行可 Enter/Space 打开 | `tabIndex=0` + keydown |
| focus-visible（按钮） | 键盘 | `ring-2 ring-ring/30` 或 `ring-1 ring-input-border-focused`；`--color-ring` 未定义（见 §10） | 类名 |
| disabled（按钮） | 禁用 | `opacity-50` + `pointer-events-none` | Button cva |
| disabled（新建任务行） | 禁用 | `cursor-not-allowed text-foreground-subtlest`，hover 不变色 | 类名 |
| aria-expanded（ghost） | 菜单展开 | `bg-hover` | Button cva |
| 未读 | `unreadAt != null` | 7px 蓝点（任务）/ 6px（项目） | `bg-icon-blue` |
| 运行中 | `phase=running/prewarming` | spinner 16px（行）/ 14px（分组行） | `animate-spin` |
| 错误 | `phase=error` 或 `status=error` | 6px 红点（`bg-destructive`） | `data-error-indicator` |
| 等待确认 | `pendingInteraction` | h-5 圆角徽标 + 倒计时填充 | `bg-interaction-confirmation-surface` |
| 手机操作中 | `mobileActiveTaskKey` | 16px 图标（success），行内绝对定位 | `data-mobile-active-task` |
| 归档确认 | 行内确认态 | 归档按钮变 destructive "确认"；点击外部/Esc 取消 | `data-archive-confirming-task-id` |
| 拖拽中 | DnD | 源行 `opacity-0` 或 `opacity-85`；覆盖层 `shadow-lg` | 类名 |
| 拖放目标 | 拖入项目 | 项目外框 `bg-accent` + 2px `icon-blue` 描边 | 类名 |
| 侧栏拖拽 | 拖动分割线 | 分割线 opacity 100；shell 加 `data-workspace-sidebar-resizing=true` | JS |
| 侧栏折叠 | 隐藏 | 宽 0 + opacity 0 + 不可点击 | 类名 |
| 离线 | 设备/项目离线 | 状态文案 + 文件/新建动作隐藏 + 展开禁用；菜单仍可用（含「连接」）；连接图标 cloud-off | DA() |
| 连接中 | connecting | 呼吸动画（项目行远端标识） | `workspace-remote-connecting-breathe` |
| 加载 | 首次加载 | 文本/行内 spinner，无骨架 | 见 §6.9 |

---

## 8. Compose 尺寸表（可直接抄）

### 8.1 外壳

| 组件 | 尺寸 (dp) | 间距/内边距 | 圆角 | 说明 |
|---|---|---|---|---|
| Shell 根 | 全屏 | 0 | 0 | `Row`/`Column` 切换 |
| 侧栏面板（桌面） | 宽 264（默认），min 264，max 50% 容器 | 0 | 0 | 动画 200ms |
| 侧栏面板（移动） | 高 `min(50vh-48,352)`，全宽 | 0 | 0 | <768 |
| 拖拽柄 | 4 × 全高 | 线 2px，上下内缩 12 | full | 命中区 4dp（可扩到 8） |
| 内容面板 | `weight(1f)`，min 320 | 0（Web） | 0 | 移动 min 0 |
| 顶部拖拽条 | 4（Win 6） | — | — | 仅桌面 |
| 会话框 | 全高 | 边框 0.5 | 12 | `shadow-xs` |
| 顶栏浮层 | 高 56 | 按钮 28，间距 4 | — | z=20 |
| 内容顶栏 | 高 48 | 8 内边距，下边框 1 | 0 | `border/50` |

### 8.2 侧栏

| 组件 | 尺寸 (dp) | 间距/内边距 | 圆角 | 字体 |
|---|---|---|---|---|
| 顶部留白 | 48 | — | — | — |
| 主操作区 | 自适应 | px 8 / py 12，gap 4 | — | — |
| 新建任务行 | 32 | pl/pr 10，gap 8 | 8 | 14/400 |
| 自动化/插件行 | 32 | px 10，gap 8 | 8 | 14/400 |
| 任务列表区 | `weight(1f)` | 上边框 1，pt 12 | — | — |
| 工具条 | 自适应 | px 8，pb 8，gap 4 | — | — |
| 标题 | 21（行高 1.5） | px 10 | — | 14/500 subtle |
| 图标按钮 | 24 | — | 6（Button icon-sm 默认 rounded-md） | 图标 14 |
| 滚动区 | `weight(1f)` | — | — | 上下渐隐 24 |
| 列表容器 | 自适应 | gap 12，pb 12 | — | — |
| 页脚 | 自适应 | px 16 / pt 8 / pb 16，gap 10 | — | — |
| 用户行 | 32 | 左 0 / 右 8，gap 8 | 左上/左下 16 | 14/600 |
| 头像 | 32 | — | full | — |
| 设置按钮 | 32 | — | 8 | 图标 16 |

### 8.3 列表

| 组件 | 高度 (dp) | 左/右内边距 | 圆角 | 字号 |
|---|---|---|---|---|
| 置顶标题 | 29（py 4 + 行高 21） | 10 | — | 14/500 subtlest |
| 区块标题 | 28（h-7） | 10 | — | 14/500 subtlest |
| 区块容器 | — | 左右 8，区块间 12 | — | — |
| 项目行 | 32 | 10 / 4 | 8 | 14/400 subtle |
| 项目下任务区 | — | 左 8，上 4 | — | — |
| 任务行（默认） | 32 | 10 / 4 | 8 | 14/400 |
| 任务行（时间线） | 约 62 | 10 / 4 | 8 | 14/400 |
| 任务行（分组） | 28 | 10 / 4 | 8 | 14/400 |
| 草稿行 | 28 | 10 / 4 | 8 | 14/400 |
| 分组头 | 32 | 6 / 4 | 8 | 14/400 |
| 分组内容 | — | 左 24（ml-4 + pl-2 + 边框） | — | — |
| 分组空组按钮 | 32 | 8 | 8 | 14/400 |
| 菜单容器 | — | 4 | 8 | 14/400 |
| 菜单项 | 28 | 8 | 6 | 14/400 |
| 悬停卡 | — | 12 | 12 | 12/400 |
| 空态 | — | 左右 34，上下 8 | — | 14/400 subtlest |
| 加载行 | — | 10 / 4，py 4 | — | 14/400 subtlest |

### 8.4 Compose 骨架（建议）

```kotlin
// 外壳：桌面
Row(Modifier.fillMaxSize()) {
    AnimatedVisibility(visible = sidebarVisible) {
        SidebarPanel(Modifier.width(sidebarWidth.dp).fillMaxHeight())
    }
    if (sidebarVisible) {
        ResizeHandle(
            Modifier.width(4.dp).fillMaxHeight(),
            onDrag = { sidebarWidth = clamp(sidebarWidth + it, max = containerWidth * 0.5f) }
        )
    }
    Column(Modifier.weight(1f).fillMaxHeight()) {
        DragStrip(if (windows) 6.dp else 4.dp)
        ConversationFrame(Modifier.weight(1f)) { ... }
    }
}

// 外壳：移动（<768dp，Web 远控语义）
Column(Modifier.fillMaxSize()) {
    if (navExpanded) {
        NavigationPanel(Modifier.fillMaxWidth().height(navHeight.dp))
    }
    Box(Modifier.weight(1f)) {
        ConversationFrame(Modifier.fillMaxSize())
        CollapseToggle(Modifier.align(Alignment.TopCenter).offset(y = headerHeight))
    }
}

// 侧栏
Column(Modifier.fillMaxHeight()) {
    Spacer(Modifier.height(48.dp))                 // 桌面拖拽留白；移动隐藏
    Column(Modifier.weight(1f)) {
        PrimaryNav()                                // 新建任务 + 自动化 + 插件
        Column(Modifier.weight(1f).border(top = 1.dp, color = border.copy(alpha = .5f))) {
            TaskListToolbar()                       // 标题 + 搜索 + 视图
            TaskListScroll(Modifier.weight(1f))     // 置顶/项目/任务
        }
        SidebarFooter()                             // 用户 + 设置
    }
}
```

---

## 9. 与既有文档的差异（以打包产物为准）

对照 `docs/UI-SPEC.md`（2026-10-17）与 `docs/UI-CONFIRM-3.17.1.md`（2026-11-02）：

| # | 既有口径 | 3.17.1 网页产物 | 判定 |
|---|---|---|---|
| 1 | 底部双 Tab：对话/设置 | **无底部 Tab**；导航是左侧栏（移动端为顶部可折叠导航面板） | 冲突，网页无此结构 |
| 2 | 首页页头 54dp（工作区名 + 状态） | 内容顶栏 **48px**（h-12），侧栏另有 48px 拖拽留白 + 56px 顶栏浮层 | 数值冲突 |
| 3 | 工作区横条：图标/名称/路径/更新时间/+ | 3.17.1 为**项目行**：图标/名称/设备标签/未读点/状态/动作；无路径、无更新时间、无「+」（新建任务在动作区） | 结构冲突（UI-CONFIRM 已改项目树，但细节仍不同） |
| 4 | 显示任务数与未读数 | 项目行**无任务数**；只有未读点（6px）。任务数仅出现在分组头徽标 | 冲突 |
| 5 | 任务行 `min-h-12`（≥48dp） | 默认任务行 **32px**；时间线约 62px；分组 28px | 冲突（关键） |
| 6 | 左滑露出置顶/归档/删除 | **无左滑**；hover 动作按钮（文件/归档/图钉）+ 右键菜单 | 冲突 |
| 7 | 加载骨架屏（3 条横条） | **无骨架**；一行文字或行内 spinner | 冲突 |
| 8 | 空态：居中图标 + 主/次按钮 | 列表内一行 14px 文案（`px-8.5 py-2`），无图标无按钮 | 冲突 |
| 9 | 未读点用 sky-500 | 用 `--color-icon-blue`：dark `#80beff`、light `#0066dd`（非 sky-500 `#00a5ef`） | 颜色冲突 |
| 10 | 置顶任务独立成区、原工作区不重复 | 一致；但置顶**项目**是独立列表且无标题，位于置顶任务之后 | 部分一致 |
| 11 | 折叠状态不跨启动记忆 | 项目/区块展开状态**持久化**到 localStorage（`zcode-sidebar-purpose-section-preferences`）；分组折叠也持久化 | 冲突 |
| 12 | 刷新按钮 + 下拉刷新 | 侧栏**无刷新按钮、无下拉刷新**；数据靠推送/重新订阅 | 冲突 |
| 13 | 主题跟随系统，设置里切换 | 侧栏页脚菜单可直接切 `system / zai-dark / zai-light` | 一致（入口不同） |
| 14 | 圆角：任务行 8 | 一致（`rounded-lg` 8）；项目行 8；菜单 8；会话框 12 | 一致 |
| 15 | UI-CONFIRM：UI 行/工具行行高 1.5（21px） | 产物 `html{line-height:1.5}`，UI 组件无独立 line-height token（继承 1.5）；14px → 21px | 一致 |
| 16 | 默认主题底 #161616、字 #D4D4D4、卡片 #2B2B2B、边框 #FFFFFF1A | 全部一致；但「卡片」在首页实际是 `menu/card #2b2b2b`，侧栏底是 `#161616` | 一致 |

---

## 10. 未解决项（unresolved）

1. **`--color-ring` / `--color-outline` / `--color-muted` 未在产物 CSS 中定义**：`focus-visible:ring-ring/30`、`border-outline/40` 等类无对应规则或变量为空，实际渲染颜色无法静态确定（可能回退 `currentColor` 或无效）。Compose 侧建议用 `--color-border-hover` 或 `icon-blue` 替代，并在真机对照。
2. **字体实际渲染**：`index.html` 指定 Inter 优先，但 CSS `--font-sans` 是 `ui-sans-serif/system-ui`；若未安装 Inter，实际为系统字体。Compose 需按平台决定是否打包 Inter。
3. **`text-ui-*` 无独立 line-height token**：继承 `html{line-height:1.5}`。Compose 建议显式设 1.5 倍行高；`text-ui-base/relaxed` 为 1.625。
4. **移动端导航面板内页脚可见性**：代码上页脚在面板内 `shrink-0`，但 352px 高度下列表区会被压缩；运行态未验证是否出现滚动/裁切。
5. **自动收起与侧栏宽度联动的边界**：会话列 <360px 自动收起只在 `view==="chat"` 时生效，且 300ms 防抖；多次缩放时的实际行为未运行验证。
6. **拖拽排序落点算法**（before/after 判定、跨组移动）只做了结构级阅读，未逐行验证所有分支。
7. **`prefers-reduced-motion` 覆盖不完整**：跑马灯、呼吸动画有降级；侧栏宽度/折叠动画无显式降级。
8. **顶栏浮层与移动导航的叠放**：`max-md:pt-11` 与 `--web-remote-navigation-height` 的组合依赖运行时高度，未在真机验证。
9. **项目行「设备名」省略规则**：`hideDeviceName` 由桌面设置控制；Web 远控下是否隐藏未运行验证。
10. **i18n 文案完整性**：`project.manageProjects` 键不存在（实际用 `projectManagement.manage`），其余已核对；不同语言下文案长度会影响截断表现。

---

## 附录 A：选择器/类名证据摘录

以下为 CSS 产物中的原始规则（Tailwind v4，`--spacing=4px`）：

```css
.w-\[var\(--workspace-sidebar-panel-width\)\]{width:var(--workspace-sidebar-panel-width)}
.max-w-\[50\%\]{max-width:50%}
.min-w-\[320px\]{min-width:320px}
.h-8{height:calc(var(--spacing) * 8)}          /* 32px */
.h-7{height:calc(var(--spacing) * 7)}          /* 28px */
.h-6{height:calc(var(--spacing) * 6)}          /* 24px */
.h-12{height:calc(var(--spacing) * 12)}        /* 48px */
.h-14{height:calc(var(--spacing) * 14)}        /* 56px */
.w-1{width:calc(var(--spacing) * 1)}           /* 4px */
.size-4{width:calc(var(--spacing) * 4);height:calc(var(--spacing) * 4)}
.size-3\.5{width:calc(var(--spacing) * 3.5);height:calc(var(--spacing) * 3.5)}
.size-\[7px\]{width:7px;height:7px}
.rounded-lg{border-radius:var(--radius-lg)}    /* 8px */
.rounded-xl{border-radius:var(--radius-xl)}    /* 12px */
.rounded-2xl{border-radius:var(--radius-2xl)}  /* 16px */
.text-ui-base{font-size:var(--text-ui-base)}   /* 14px */
.text-ui-sm{font-size:var(--text-ui-sm)}       /* 12px */
.text-ui-xs{font-size:var(--text-ui-xs)}       /* 10px */
.text-ui-base\/relaxed{font-size:var(--text-ui-base);line-height:var(--leading-relaxed)}
.bg-sidebar{background-color:var(--color-sidebar)}
.bg-header{background-color:var(--color-header)}
.bg-panel{background-color:var(--color-panel)}
.bg-surface-hover{background-color:var(--color-surface-hover)}
.bg-selected{background-color:var(--color-selected)}
.bg-menu{background-color:var(--color-menu)}
.border-border{border-color:var(--color-border)}
.border-border\/50{border-color:color-mix(in oklab, var(--color-border) 50%, transparent)}
.bg-icon-blue{background-color:var(--color-icon-blue)}
.z-10{z-index:10} .z-20{z-index:20} .z-30{z-index:30}
.z-40{z-index:40} .z-50{z-index:50} .z-\[60\]{z-index:60}
.shadow-xs{--tw-shadow:0 1px 2px 0 #0000000d; ...}
.shadow-sm{--tw-shadow:0 1px 3px 0 #0000001a, 0 1px 2px -1px #0000001a; ...}
.shadow-md{--tw-shadow:0 4px 6px -1px #0000001a, 0 2px 4px -2px #0000001a; ...}
@keyframes collapsible-down{0%{height:0}to{height:var(--radix-collapsible-content-height,...)}}
.animate-collapsible-down[data-state=open]{animation:collapsible-down ...}
@keyframes workspace-remote-connecting-breathe{...}
.workspace-remote-connecting-breathe{animation:1.8s ease-in-out infinite ...}
```

**Button 尺寸（`button-P7AG3MDX.js`）**

```text
default: h-7 gap-1 px-2 text-ui-base
xs:      h-5 gap-1 rounded-sm px-2 text-ui-base
sm:      h-6 gap-1 px-2 text-ui-base/relaxed
lg:      h-8 gap-1 rounded-lg px-2.5 text-ui-base
icon:    size-7
icon-xs: size-5 rounded-sm
icon-sm: size-6
icon-md: size-7 rounded-lg
icon-lg: size-8 rounded-lg
ghost:   text-foreground hover:bg-hover hover:text-foreground aria-expanded:bg-hover
```

**关键 JS 常量（byte 偏移，`index-Dwhx77cK.js`）**

| 常量 | 值 | 字节 |
|---|---|---|
| `OXn` | `(max-width: 767px)` | 6,037,789 |
| `MXn` / `u7` | 264 / 264 | 6,037,884 |
| `NXn` | 0.5 | 6,037,893 |
| `PXn` | `zcode:workspace-shell:sidebar-width-px` | 6,037,911 |
| `FXn` | `react-resizable-panels:workspace-shell-layout:sidebar:content` | 6,037,956 |
| `IXn` | 16 | 6,038,019 |
| `zXn` / `VXn` / `HXn` | 480 / 360 / 300 | 6,038,096 / 6,038,111 / 6,038,119 |
| `AXn` / `jXn` | 800 / 4 | 6,037,870 / 6,037,878 |
| `fXn` / `pXn` / `mXn` / `hXn` | 352 / 24 / 48 / 168 | 6,036,618 起 |
| `data-workspace-shell` | — | 6,070,456 |
| `data-web-remote-navigation-resize-handle` | — | 6,076,115 |

---

## 附录 B：首页文案表（zh-CN，默认语言）

| 键 | 文案 |
|---|---|
| `taskList.title` | 任务列表 |
| `taskList.newThread` | 新建任务 |
| `taskList.pinnedSection` | 已置顶 |
| `taskList.noTasks` | 暂无任务 |
| `taskList.noUnreadTasks` | 暂无未读任务 |
| `taskList.loading` | 正在获取任务... |
| `taskList.showMore` / `showLess` | 显示更多 / 显示更少 |
| `taskList.archive` / `unarchive` | 归档任务 / 取消归档任务 |
| `taskList.pin` / `unpin` | 置顶任务 / 取消置顶任务 |
| `taskList.rename` | 重命名任务 |
| `taskList.markAsUnread` | 标记为未读 |
| `taskList.remoteConnectionUnknown` | 远程连接暂不可用 |
| `taskList.mobileActive` | 手机正在操作此任务 |
| `taskList.cronTaskLabel` / `offPeakTaskLabel` | 定时任务 / 闲时任务 |
| `taskList.permissionTag` / `userInputTag` | 等待确认 / 等待确认 |
| `taskList.attentionCount` | {label} · {count} |
| `taskGroup.view` | 按分组 |
| `taskGroup.newGroup` | 新建分组 |
| `taskGroup.expandAll` / `collapseAll` | 展开全部分组 / 收起全部分组 |
| `taskGroup.emptyDropZoneAction` | 新建任务，或拖拽到这里。 |
| `taskGroup.moveToTop` | 移动到顶部 |
| `project.projects` / `tasks` | 项目 / 任务 |
| `project.view` | 筛选视图 |
| `project.viewByProject` | 按项目 |
| `project.viewRecentlyUpdated` | 最近更新 |
| `project.viewUnread` | 按未读 |
| `project.expandAll` / `collapseAll` | 展开全部项目 / 收起全部项目 |
| `project.new` | 新项目 |
| `project.createTask` | 新建任务 |
| `project.pin` / `unpin` | 置顶 / 取消置顶 |
| `project.delete` / `edit` | 删除项目 / 编辑项目 |
| `project.deviceSettings` | 远程设备设置 |
| `project.removeEntry` | 从本机移除 |
| `project.offline` / `unresolved` | 离线 / 请选择项目 |
| `project.hasUnread` | 项目有未读任务 |
| `project.legacySingleRoot` | 单目录 |
| `projectManagement.manage` | 管理项目… |
| `workspaceSidebar.showFileTree` | 查看文件 |
| `workspaceSidebar.resizeSidebar` | 调整侧边栏宽度 |
| `workspaceSidebar.toggleSidebar` | 切换侧边栏 |
| `workspaceSidebar.unavailableLocalDirectory` | 工作区目录不存在或无法访问，当前仅可查看历史记录。恢复该目录后重启 ZCode 即可继续使用。 |
| `webRemoteControl.homeOnlyWorkspaces` | 工作区 |
| `webRemoteControl.noSwitchableWorkspaces` | 没有可切换的工作区 |
| `webRemoteControl.switchDevice` | 切换设备 |
| `webRemoteControl.unnamedDevice` | 未命名设备 |
| `webRemoteControl.expandNavigation` | 展开任务和工作区 |
| `webRemoteControl.collapseNavigation` | 收起任务和工作区 |
| `workspace.openScheduledSettings` | 自动化 |
| `workspace.openPluginsSettings` | 插件市场 |
| `commandCenter.open` | 搜索 |
| `taskTimeline.today` … `older` | 今天 / 昨天 / {days} 天前 / 本周 / 上周 / 本月 / 上月 / 更早 |
| `remote.status.online` / `connecting` / `offline` / `pending` / `failed` | 已连接 / 连接中 / 未连接 / 需要处理 / 连接失败 |
| `remote.action.connect` | 连接 |

---

## 附录 C：DOM 证据索引（byte 偏移）

| 组件 | 起始字节 |
|---|---|
| Shell 布局 `QXn` / `data-workspace-shell` | 6,070,456 |
| 侧栏 `sLt` / `data-project-sidebar-panel` | 6,044,487（另 2,787,208） |
| 侧栏工具条 `sidebar-search-toolbar` | 2,518,116 |
| 项目行 `virtual-project-row` | 2,501,991 |
| 项目动作 `virtual-project-actions` | 2,504,410 |
| 项目区块 `project-section` | 2,516,339 |
| 任务行 `task-item` | 2,401,650 |
| 置顶标题 `taskList.pinnedSection` | 2,499,106 |
| 分组视图 `task-grouped-view` | 2,453,669 |
| 跑马灯 `task-title-marquee` | 2,402,562 |
| 任务行动作 `data-task-row-actions` | 2,481,151 |
| 顶栏浮层 `desktop-top-logo-sidebar-toggle` | 4,865,201 |
| 移动导航折叠按钮 | 6,076,115 |

---

*文档结束。所有 class 字符串均可在 `index-Dwhx77cK.js` 对应字节处逐字复核；CSS 规则可在 `index-BgVjt5KE.css` 中检索同名选择器。*
