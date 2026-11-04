# ZCode 3.17.1 关键组件结构树与状态组合

> 与 `06-class-map.md` 配套。树来自 `assets/index-Dwhx77cK.js` 的编译后 JSX 调用（`(0,$.jsx)` / `(0,$.jsxs)`），
> 不是运行时 DOM。`{...}` 表示条件分支；`*` 表示列表渲染；class 为 bundle 中的真实字面量（过长处用 `…`）。
> 组件名：`sbn` / `r0` 等是压缩标识；`WorkspaceShell` 等是本报告赋予的语义名。

---

## 1. Shell（应用壳）

### 1.1 总树

```
sbn (AppShell 外层)
└─ div[data-workspace-shell="true"]
   class="relative flex h-full min-h-0 w-full overflow-hidden
          [anchor-scope:--sidebar-task-list-divider]
          {isWebRemoteControl && max-md:flex-col}"
   ├─ div[data-panel=""][data-workspace-sidebar-panel="true"]#sidebar
   │  class="w-[var(--workspace-sidebar-panel-width)] max-w-[50%] flex-none
   │         overflow-hidden duration-200 ease-out transition-[width,opacity]
   │         data-[workspace-sidebar-resizing=true]:transition-opacity
   │         {visible ? opacity-100 : pointer-events-none opacity-0}"
   │  └─ aside #sidebar
   │     class="h-full overflow-hidden select-none"
   │     └─ v2[scope="workspace-sidebar"] (Panel)
   │        └─ zOt (sidebar error boundary?) > WEt
   │           └─ Ci() = sLt  WorkspaceSidebarContent
   │
   ├─ div[role="separator"][data-testid="resizable-handle"]
   │  class="group/handle relative z-10 flex h-full w-1 shrink-0 touch-none
   │         cursor-ew-resize items-center justify-center bg-transparent
   │         outline-none [app-region:no-drag] focus:outline-none
   │         focus-visible:ring-0"
   │  └─ ::after
   │     class="pointer-events-none absolute rounded-full
   │            bg-foreground-subtlest/50
   │            platform-{mac|windows|linux}-desktop:bg-resize-handle
   │            opacity-0 transition-opacity content-['']
   │            inset-y-[var(--workspace-panel-radius)] w-0.5
   │            hover:opacity-100 data-[separator=hover]:opacity-100
   │            data-[separator=active]:opacity-100
   │            focus-visible:opacity-100"
   │
   ├─ span[data-sidebar-divider-extension="true"]
   │  class="pointer-events-none absolute h-px w-1 bg-border/50
   │         [left:anchor(--sidebar-task-list-divider_right,-4px)]
   │         [top:anchor(--sidebar-task-list-divider_top,-1px)]"
   │
   └─ div[data-panel=""]#content
      class="flex min-w-[320px] flex-1 flex-col {desktop ? pr-1 pb-1 : p-0}"
      ├─ div class="w-full [app-region:drag] {win ? h-1.5 : h-1}"   (窗口拖拽留白)
      └─ U8[layoutId="workspace-body-layout"] (分栏组)
         class="min-h-0 flex-1 {remote && max-md:!flex-col}"
         ├─ W8#conversation-column (分栏面板)
         │  └─ ilt (conversation column wrapper)
         │     └─ U8[orientation="vertical"][layoutId="workspace-conversation-column-layout"]
         │        class="h-full min-h-0"
         │        ├─ W8#conversation
         │        │  └─ section[data-workspace-conversation-frame="true"]
         │        │     class="relative flex h-full min-h-0 flex-1 flex-col
         │        │            overflow-hidden bg-background
         │        │            {rounded ? rounded-[var(--workspace-panel-radius)] border border-border
         │        │                      : platform-specific}
         │        │            border-[0.5px] {!split && shadow-xs}"
         │        │     ├─ v2[scope="workspace-header"] > VLn  WorkspaceHeader
         │        │     └─ Bkn  ChatPage
         │        └─ W8#side-pane (可拖拽分栏)
         │           └─ SidePaneFrame
         │
         └─ (side pane 也可作为浮动/全宽层，见 1.4)
```

### 1.2 侧栏内容 `sLt`

```
aside[data-testid=PHe] class="flex h-full min-h-0 flex-col overflow-hidden"
├─ div class="h-12 shrink-0 [app-region:drag] {remote && max-md:hidden}"
├─ div class="relative min-h-0 flex-1 overflow-clip"
│  └─ div[data-project-sidebar-panel="navigation"]
│     class="absolute inset-0 flex min-h-0 flex-col"
│     ├─ fAt  SidebarPrimaryNavigation
│     │  div[data-testid="sidebar-primary-navigation"]
│     │  class="flex flex-col gap-1 px-2 py-3 {remote && !switcher && max-md:pt-11}"
│     │  ├─ dAt 设备切换（web remote）
│     │  ├─ POt 新建任务
│     │  ├─ 自动化按钮  class="w-full justify-start gap-2 {active && bg-selected text-foreground}"
│     │  └─ 插件商店按钮 class="w-full justify-start gap-2 {active && bg-selected text-foreground}"
│     │
│     └─ div[data-testid="sidebar-task-list-region"]
│        class="flex min-h-0 flex-1 flex-col border-t border-border/50 pt-3
│               [anchor-name:--sidebar-task-list-divider]"
│        ├─ oAt 视图工具栏（projects / grouped / timeline / unread）
│        └─ div class="relative flex min-h-0 flex-1 flex-col"
│           └─ wj (ScrollArea) class="overflow-x-hidden"
│              └─ div class="flex flex-col gap-3 pb-3"
│                 ├─ mAt  HomeOnlyWorkspaces  (data-testid="home-only-workspaces")
│                 ├─ bR  TaskList
│                 │  ├─ pinned:  bR kind="pinned"  → div[data-testid="project-pinned-tasks"]
│                 │  ├─ projects: Sbt 置顶项目 + aAt 项目树
│                 │  ├─ grouped: MOt 任务分组
│                 │  └─ timeline/unread: bR kind=... → div[data-testid="project-timeline-tasks"]
│                 └─ loading / error 提示
└─ _Pt  SidebarFooter（主题/语言/账户/升级）
```

任务行 `CDt`：

```
div.group/task-row
  class="flex min-h-7 w-full min-w-0 flex-col justify-center rounded-lg
         border border-transparent pl-2.5 pr-1 text-left text-ui-base
         transition-[background-color,border-color,color,opacity]
         {active && bg-selected} {hover && hover:bg-surface-hover}
         {dragging && opacity-0}
         {dragOverlay && pointer-events-none cursor-grabbing border border-border
                         bg-background shadow-lg opacity-100}"
└─ span class="flex h-7 w-full min-w-0 items-center gap-2"
   ├─ CL 标题跑马灯  class="min-w-0 flex-1 overflow-hidden whitespace-nowrap
   │                    {overflow && task-title-marquee} text-foreground"
   │  └─ span[data-task-title-marquee-track]
   │     class="task-title-marquee-track inline-flex w-max min-w-max items-center gap-6"
   │     ├─ span[data-task-title-copy="original"] class="shrink-0"
   │     └─ {overflow && span[data-task-title-copy="duplicate"][aria-hidden] class="shrink-0"}
   └─ span class="ml-auto flex shrink-0 items-center gap-1.5 text-ui-sm text-foreground-subtle"
      ├─ 交互徽标 / 状态点 / 时间
      └─ 悬停操作（置顶/归档/更多）
```

项目行 `Kkt`：

```
div[data-testid="virtual-project-row"] class="group/project-row ml-2 flex h-8
    min-w-0 items-center gap-1 rounded-lg pr-1 hover:bg-surface-hover"
├─ span class="flex min-w-0 flex-1 shrink-0"
│  └─ button[data-testid="virtual-project-toggle"]
│     class="flex h-8 min-w-0 flex-1 justify-start gap-2 rounded-lg
│            pl-2.5 pr-1 text-left text-foreground hover:bg-transparent"
│     ├─ span class="flex size-4 shrink-0 items-center justify-center"
│     │  ├─ 设备/状态图标
│     │  └─ {pinned && 置顶图标 group-hover/project-row:hidden}
│     ├─ span class="flex min-w-0 items-baseline gap-2"
│     │  ├─ span class="truncate text-ui-base text-foreground-subtle"
│     │  └─ {remote && span[data-testid="project-device"] class="max-w-24 truncate text-ui-sm text-remote-foreground-muted"}
│     └─ {!open && online && unread && span[data-workspace-unread-indicator] class="size-1.5 rounded-full bg-icon-blue"}
└─ div[data-testid="virtual-project-actions"]
   class="flex shrink-0 items-center gap-1 opacity-0 transition-opacity
          group-hover/project-row:opacity-100 group-focus-within/project-row:opacity-100
          [&:has([data-state=open])]:opacity-100 [@media(hover:none)]:opacity-100"
```

### 1.3 顶栏 `VLn`

```
header[data-testid=lWe][data-workspace-header-variant="task|draft"]
class="@container/workspace-header relative flex w-full shrink-0 h-12 border-b
       {draft ? border-transparent : border-border/50}"
├─ {draft && dropActive && div[data-testid="new-task-draft-drop-mask"]
│  class="absolute inset-0 z-40 bg-accent/55 backdrop-blur-sm
│         pointer-events-auto [app-region:no-drag]"}
└─ div class="flex h-12 flex-1 min-w-0 items-center justify-between gap-2
             overflow-hidden p-2 [app-region:drag]
             transition-[padding] duration-300 {mac padding class}"
   ├─ zLn（左：面包屑/项目名/任务标题/git 分支/状态）
   └─ RLn（右：终端、侧面板、帮助、窗口控制）
```

macOS 窗口控制让位 padding：`pl-38` / `pl-44` / `pl-48` / `pl-58` / `pl-66`（`BLn`）。

### 1.4 右侧面板 `SidePaneFrame`

```
div[data-workspace-side-frame="true"]
class="h-full overflow-hidden bg-background {!embedded && border-[0.5px] shadow-xs}
       {mobile && max-md:rounded-none max-md:border-0}
       {embedded && min-w-0} {mobile && max-md:!w-full}
       {overlay && absolute inset-0 z-0}"
└─ v2[scope="workspace-side-pane"] (Panel)
   └─ rA (Tabs) class="relative h-full gap-0"
      ├─ iA (TabsList)
      │  class="flex justify-start w-full rounded-none p-0 border-0
      │         border-b border-border/50 bg-transparent shadow-none
      │         !h-12 overflow-hidden {drag && [app-region:drag] [&_button]:[app-region:no-drag]}"
      │  ├─ div class="flex h-full shrink-0 items-center p-2"（标签菜单）
      │  ├─ div[data-side-pane-tabs-viewport] class="min-w-0 flex-1 overflow-x-auto
      │  │   !scrollbar-hide h-12 items-center {mask gradients}"
      │  │  └─ div[data-side-pane-tabs-content] class="flex w-full gap-1 py-2.5"
      │  │     └─ OKn Tab*（浏览器/代码/终端/审查/白板/子代理/工作流…）
      │  └─ div class="ml-auto flex h-full shrink-0 items-center gap-1 px-2"
      └─ div[data-side-pane-content-slot] class="relative min-h-0 flex-1 isolate {embedded && min-w-0}"
```

### 1.5 Shell 状态组合

| 状态 | 选择器/属性 | 样式效果 |
| --- | --- | --- |
| 侧栏可见 | `opacity-100` | 侧栏可交互 |
| 侧栏折叠 | `pointer-events-none opacity-0` | 宽度过渡 + 透明 |
| 侧栏拖拽中 | `[data-workspace-sidebar-resizing=true]` | 侧栏只过渡 opacity；手柄 after 常显 |
| 对话分栏 | `U8/W8` + `data-panel` | 左右可拖拽；`conversation-column` 默认 52% |
| 对话面板圆角 | `rounded-[var(--workspace-panel-radius)] border border-border` | 非最大化窗口 |
| 最大化/分屏 | `rounded-none border-0` | 贴合窗口 |
| Web remote 移动端 | `max-md:flex-col` + `max-md:!w-full` | 侧栏变顶部抽屉 |
| draft 拖拽 | `[data-testid=new-task-draft-drop-mask]` | 全屏高亮遮罩 |

---

## 2. Home（空会话首屏）

Home 不是独立路由，而是 `Timeline` 的 `emptyState` + 底部 `ComposerDock`。空态由 `c9t`（问候）与 `Ken`（推荐提示词）组成。

### 2.1 树

```
Timeline (fTn / dTn)
└─ div class="flex min-h-full flex-col items-center justify-center gap-4 px-2"
   （centerEmptyStateWithDock；否则顶部 before:basis-[29dvh] 留白）
   ├─ div[data-testid=HHe] class="w-full"
   │  └─ c9t  DraftGreeting
   │     └─ div class="relative mb-10 flex w-full max-w-2xl flex-col
   │                   items-center justify-center gap-6 text-foreground sm:mb-8"
   │        ├─ div[aria-hidden="true"]
   │        │  class="pointer-events-none absolute left-1/2 top-1/2 aspect-[5/4]
   │        │         w-[min(72vw,25rem)] -mt-10 -translate-x-1/2 -translate-y-1/2
   │        │         text-foreground-subtlest"
   │        │  └─ l9t DraftLogo
   │        │     ├─ svg（浅色）class="opacity-70 dark:hidden [mask-image:...]"
   │        │     └─ img[data-v4-draft-logo="dark"]（深色）class="hidden dark:block"
   │        └─ p[data-v4-draft-greeting="true"]
   │           class="relative z-10 w-full px-4 text-center font-medium text-foreground
   │                  {compact ? text-xl/[1.2] : text-[length:var(--v4-draft-greeting-font-size)]/[1.2]}"
   │           ├─ span[aria-hidden="true"] class="pointer-events-none invisible absolute
   │           │   whitespace-nowrap text-3xl/[1.2]"（测量用）
   │           └─ span（实际问候语，按小时切换 morning/noon/...）
   │
   ├─ Ken  SuggestedPrompts
   │  └─ div[data-v4-draft-suggested-prompts-slot="true"] class="{!list && h-8}"
   │     └─ F9t[layout="chips|list"]
   │
   └─ ComposerDock（见 §3）
```

### 2.2 推荐提示词（chips）

```
div[data-v4-draft-suggested-prompts="true"]
class="w-full min-w-0 overflow-x-auto [scrollbar-width:none]
       [&::-webkit-scrollbar]:hidden"
└─ div[data-v4-draft-suggested-prompts-group="true"]
   class="mx-auto flex w-max items-center gap-4"
   └─ button[data-draft-suggested-prompt=ID]
      class="zcode-draft-prompt-waterfall h-8 min-w-0 justify-start gap-1.5
             overflow-hidden rounded-lg px-3 text-left text-ui-caption
             font-normal leading-4.5 text-foreground"
      style="--zcode-draft-prompt-waterfall-delay: {index*65}ms"
      ├─ span[data-draft-suggested-prompt-icon]
      │  class="flex size-4 shrink-0 items-center justify-center text-foreground
      │         opacity-70 transition-opacity group-hover/button:opacity-100"
      └─ span[data-draft-suggested-prompt-text]
         class="min-w-0 max-w-64 truncate text-ui-base text-foreground
                opacity-70 transition-opacity group-hover/button:opacity-100"
```

### 2.3 推荐提示词（list / onboarding）

```
div[data-v4-draft-suggested-prompts="true"] class="w-full min-w-0 px-2 py-4"
├─ div class="flex items-center justify-between gap-2 px-3 pb-2
│              text-ui-sm text-foreground-subtle"
└─ ul class="m-0 flex list-none flex-col p-0"
   └─ li > button[data-draft-suggested-prompt=ID]
      class="flex w-full items-center gap-3 rounded-xl p-3 text-left text-ui-base
             text-foreground hover:bg-hover focus-visible:outline-none
             focus-visible:ring-2 focus-visible:ring-input-border-focused
             disabled:opacity-50"
      ├─ span class="flex size-6 shrink-0 items-center justify-center
      │              overflow-hidden rounded-md bg-surface p-px"
      └─ span class="min-w-0 flex-1 break-words"
```

### 2.4 Home 状态

| 状态 | 条件 | 效果 |
| --- | --- | --- |
| 居中空态 | `centerEmptyStateWithDock` | 问候 + 输入框整体垂直居中 |
| 顶部留白 | 非居中 | `before:basis-[29dvh]`，内容从 29% 视口高度开始 |
| 问候字号 | 可用宽度 / 自然宽度 | 20–30px 动态（`--v4-draft-greeting-font-size`） |
| 远程紧凑 | `compactForRemoteControl` | 问候固定 `text-xl`，字号 20px |
| 推荐词 chips | `layout=chips` | 横向滚动，32px 高，65ms 逐个入场 |
| 推荐词 list | `layout=list` | 纵向列表，带标题与关闭/刷新 |

---

## 3. Composer（输入区）

### 3.1 总树

```
div[data-testid=MWe][data-input-routing]
class="chat-composer-region z-20 w-full shrink-0 @container/composer {narrow && max-w-2xl}"
├─ input[type="file"][multiple] class="hidden"
├─ {error && 错误/重连横幅}
├─ div[data-highspeed-composer][data-highspeed-entrance]
│  class="chat-composer-input-surface relative w-full
│         {rounded && rounded-2xl bg-surface shadow-xl/5}
│         {highspeed && highspeed-composer-surface}
│         {placeholderPresented && highspeed-placeholder-presented}"
│  ├─ Bqt 高速模式卡片背景
│  ├─ {topContent && div class="relative z-10 p-1.5 flex min-w-0 flex-wrap items-center gap-0"}
│  ├─ {limit && div[role=alert] class="relative z-10 mb-2 w-full rounded-lg
│  │   border border-[var(--color-warning)]/30 bg-[var(--color-warning)]/10
│  │   px-3 py-2 text-ui-base text-foreground"}
│  └─ j2t  PromptInputShell
│     └─ form class="relative {className}"
│        └─ div[data-composer-layout="stacked|inline"]
│           class="relative flex flex-col gap-3 overflow-hidden rounded-2xl
│                  border-[0.5px] border-input-border bg-input p-3
│                  shadow-(--shadow-composer-soft) transition-colors
│                  hover:border-input-border-hover
│                  focus-within:!border-input-border-focused
│                  focus-within:bg-input-focused
│                  {inline && flex-row flex-wrap items-end gap-2 p-2}
│                  {dragActive && border-brand bg-input-focused ring-1 ring-brand/30}
│                  {shellClassName}"
│           ├─ {dragActive && DragOverlay}
│           ├─ {topContent}
│           ├─ {inline ? ActionMenu : null}
│           ├─ {leadingContent ? div class="flex min-w-0 items-start gap-2 {inline && flex-1}"
│           │                 ├─ div class="shrink-0"
│           │                 └─ div class="min-w-0 flex-1" > x2t Editor}
│           │              : x2t Editor}
│           ├─ div.group/toolbar class="group/toolbar flex items-end gap-3 {inline && shrink-0 gap-1.5}"
│           │  ├─ div[data-composer-leading-actions="true"] class="flex min-w-0 flex-1 items-center"
│           │  │  └─ div[data-composer-leading-content="true"] class="flex shrink-0 items-center gap-1"
│           │  │     ├─ {inline ? null : ActionMenu}
│           │  │     ├─ leadingActions
│           │  │     └─ {modeSwitchRef && span class="flex shrink-0 items-center"}
│           │  └─ div[data-composer-trailing-actions="true"]
│           │     class="ml-auto flex shrink-0 items-center justify-end gap-1.5"
│           │     ├─ {cancel && StopButton}
│           │     ├─ betweenCancelAndSubmitAction
│           │     └─ submitControl（模型选择 + SendButton）
│           └─ {attachmentError && p class="flex items-start gap-2 p-3 text-ui-base text-warning"}
└─ 预览/确认对话框（图片、附件、队列发送确认）
```

### 3.2 编辑器 `x2t`

```
div class="relative flex-1"
└─ LexicalComposer
   └─ div class="relative"
      ├─ ContentEditable[data-testid=chat-input]
      │  class="min-h-10 max-h-40 overflow-y-auto text-foreground outline-none
      │         {avoidIosInputFocusZoom class}"
      ├─ 占位符 div class="pointer-events-none absolute left-0 top-0
      │                  {compactPlaceholder && line-clamp-2}
      │                  {iosZoomClass} text-foreground-subtlest"
      ├─ 提及/斜杠面板（Z1t / N0t）
      ├─ 键盘/提交插件
      └─ 历史/粘贴/输入法插件
```

### 3.3 附件

```
div[data-composer-file-attachments-row="true"] class="flex max-w-full flex-wrap gap-2"
└─ T6e[data-composer-attachment-kind="video|image|pdf|file"]
   [data-upload-status="uploading|failed|ready|..."]
   class="{grid ? relative size-12 overflow-hidden rounded-lg bg-surface
                    after:pointer-events-none after:absolute after:inset-0
                    after:rounded-lg after:border after:border-border after:content-['']
                  : h-12 w-fit max-w-full min-w-0 gap-2 rounded-lg border
                    border-border bg-surface p-1.5 pr-6
                    [--attachment-bg:var(--color-surface)] hover:bg-surface-hover}"
   ├─ div class="relative shrink-0 {grid ? size-full : size-9 rounded-md bg-background}"
   │  ├─ 缩略图/文件图标
   │  └─ {progress && span[role=status] class="absolute inset-0 grid place-items-center
   │       rounded-lg bg-background/85 text-[7px] font-semibold text-foreground"}
   ├─ 文件名/类型
   ├─ {failed && 重试按钮}
   └─ 删除按钮 class="absolute right-0.5 top-0.5 z-20 size-3.5 rounded-full
                      bg-primary p-0 text-primary-foreground opacity-0
                      transition-opacity group-hover:opacity-100
                      focus-visible:opacity-100 [@media(hover:none)]:opacity-100"
```

### 3.4 思考等级滑杆 `H5t`

```
div.composer-thought-slider.relative.h-8.w-full.rounded-full
    [data-disabled={disabled}]
├─ div[aria-hidden="true"] class="pointer-events-none absolute inset-0"
│  ├─ div[data-testid="composer-thought-track"][data-progress]
│  │  class="absolute inset-x-0 top-1 h-6 overflow-hidden rounded-full bg-foreground/10"
│  │  ├─ div class="absolute inset-0 rounded-full border border-foreground/10"
│  │  ├─ div.composer-thought-slider-fill
│  │  │  class="composer-thought-slider-fill absolute inset-y-0 left-0
│  │  │         overflow-hidden rounded-full"
│  │  │  [data-max={atMax}] [data-highspeed={highspeed}]
│  │  │  style="width: calc(14px + (100% - 28px) * {percent/100})"
│  │  │  └─ {atMax && !disabled && div[data-testid="composer-thought-bubbles"]
│  │  │       class="composer-thought-bubbles absolute inset-0"
│  │  │       └─ span.composer-thought-bubble*（随机 top/duration/delay）}
│  │  ├─ div class="absolute inset-x-3.5 inset-y-0 flex items-center justify-between"
│  │  │  └─ span.composer-thought-slider-stop.size-1.shrink-0.rounded-full*
│  │  │     [data-composer-thought-stop] [data-filled] [data-hovered]
│  │  └─ div.composer-thought-slider-thumb
│  │     class="composer-thought-slider-thumb absolute top-0.5 size-7
│  │            rounded-full shadow-sm"
│  │     style="left: calc((100% - 28px) * {percent/100})"
│  └─ input[type="range"].composer-thought-range
│     class="composer-thought-range absolute inset-0 m-0 h-8 w-full
│            cursor-pointer appearance-none bg-transparent outline-none
│            aria-disabled:cursor-default"
│     [data-testid="composer-thought-slider"][data-levels]
```

### 3.5 Composer 状态

| 状态 | 属性/类 | 效果 |
| --- | --- | --- |
| 布局 | `data-composer-layout=stacked` | 纵向：编辑器在上、工具栏在下 |
| 布局 | `data-composer-layout=inline` | 横排：工具栏与编辑器同一行，`p-2` |
| 聚焦 | `focus-within:!border-input-border-focused` | 边框品牌色 + 背景 `input-focused` |
| 拖拽文件 | `border-brand bg-input-focused ring-1 ring-brand/30` | 品牌色描边 + 1px ring |
| 工具栏折叠 | `data-composer-compact=true/icon` | 隐藏/缩小模型、provider、模式控件 |
| 模型图标化 | `data-composer-model-icon=true` | 模型名隐藏，只留图标 |
| 高速模式 | `data-highspeed-composer=true` + `data-phase` | 紫色描边/辉光，`stable` 时呼吸光 |
| 滑杆满级 | `data-max=true` | 填充显示气泡动画 |
| 滑杆高速 | `data-highspeed=true` | 填充变蓝紫渐变 |
| 滑杆禁用 | `data-disabled=true` | 整体 50% 透明，指针禁用 |
| 刻度点 | `data-filled` / `data-hovered` | 填充白色 / 悬停放大 1.75× |

---

## 4. Chat Timeline（消息时间线）

### 4.1 总树 `dTn`

```
div class="relative flex min-h-0 flex-1 flex-col {compact && min-w-0}"
├─ Ywn（选区侧聊浮层）
├─ uTn（选区捕获）
├─ GCn  TurnNavigator
│  └─ nav[data-testid=xGe] class="pointer-events-none invisible absolute inset-y-0
│     left-0 z-10 w-12 -translate-x-2 opacity-0 transition-[opacity,transform,visibility]
│     duration-150 ease-out motion-reduce:transition-none
│     @min-[864px]/conversation:visible @min-[864px]/conversation:translate-x-0
│     @min-[864px]/conversation:opacity-100"
│     └─ div class="!scrollbar-hide pointer-events-auto absolute left-3 top-1/2
│        max-h-[calc(100%-6rem)] w-9 -translate-y-1/2 overflow-y-auto py-1"
│        └─ WCn tick*（按钮 + 刻度线 + tooltip）
├─ div[data-testid=xWe][data-v4-timeline-scroll="true"]
│  [data-v4-timeline-scroll-locked] [data-markdown-table-layout-root]
│  [data-row-count] [data-window-row-count] [data-render-unit-count]
│  [data-total-row-count] [data-following] [data-loading-older]
│  class="min-h-0 flex-1 overflow-x-hidden overflow-y-auto
│         [scrollbar-gutter:stable_both-edges]
│         [--markdown-table-layout-left-inset:16px]
│         [--markdown-table-layout-right-inset:16px]
│         max-md:[--markdown-table-layout-left-inset:8px]
│         max-md:[--markdown-table-layout-right-inset:8px]
│         {locked && !overflow-y-hidden} {compact && min-w-0}
│         {floatingDock && mb-[calc(var(--v4-floating-composer-dock-height,0px)+12px)] pb-4}"
│  └─ div class="{emptyCentered ? flex min-h-full flex-col items-center justify-center
│                          gap-4 px-2
│                        : topAligned ? flex min-h-full flex-col items-center px-2
│                          before:block before:min-h-[52px] before:w-full before:shrink
│                          before:basis-[29dvh] before:content-['']
│                          after:block after:min-h-4 after:w-full after:flex-1
│                          after:content-['']
│                        : flex min-h-full flex-col}"
│     style="overflow-anchor:none"
│     ├─ {unitCount===0 && !emptyState && 空态占位}
│     └─ div[data-v4-timeline-message-layer="true"][data-sandbox-page-container="true"]
│        class="relative w-full flex-1 [mask-repeat:no-repeat] [-webkit-mask-repeat:no-repeat]"
│        ├─ {headerSlot && div[data-v4-timeline-header-slot]
│        │   [data-v4-timeline-content-column] class="relative mx-auto w-full shrink-0"}
│        ├─ div[data-v4-timeline-virtual-history][data-v4-timeline-content-column]
│        │   class="relative mx-auto w-full shrink-0
│        │          transition-[width,max-width,transform] duration-150 ease-out
│        │          @min-[1280px]/conversation:transition-[transform]"
│        │   style="height: {totalSize}px"
│        │   └─ div[data-index][data-v4-turn-unit="true"][data-turn-id]
│        │      class="absolute left-0 top-0 w-full {first && pt-14}"
│        │      style="transform: translateY({start-offset}px)"
│        │      └─ RCn  TurnUnit
│        ├─ {liveTail && div[data-index][data-v4-running-live-tail]
│        │   [data-v4-turn-unit][data-turn-id][data-v4-timeline-content-column]
│        │   class="relative mx-auto w-full shrink-0 transition-[width,max-width,transform]
│        │          duration-150 ease-out @min-[1280px]/conversation:transition-[transform]"}
│        └─ {pendingGuides && div[data-v4-timeline-content-column]
│            class="relative mx-auto w-full shrink-0"
│            └─ VCn  PendingGuideList}
├─ {composerDock && div[data-v4-composer-dock="true"]
│  class="pointer-events-none z-20 flex w-full justify-center
│         {floating ? absolute inset-x-0 bottom-0
│                   : centerEmpty ? mt-3 shrink-0
│                   : sticky bottom-0}"
│  └─ div[data-v4-composer-dock-content="true"]
│     class="pointer-events-auto relative z-10 w-full shrink-0
│            transition-[width,max-width,transform] duration-150 ease-out
│            @min-[1280px]/conversation:transition-[transform]"
│     └─ div[data-v4-back-to-bottom-anchor="composer-dock"] class="relative"
│        ├─ {showScrollToBottom && sTn}
│        └─ ComposerRegion}
└─ {showScrollToBottom && !composerDock && sTn}
```

### 4.2 TurnUnit `LCn` / `RCn`

```
div class="group/assistant-turn flex w-full flex-col gap-5"
├─ leadingBoundaryRows.map(F2)
├─ {hasAssistantBody
│   ? <>
│       {userInputRows.map(F2 + edit)}
│       {highspeed ? ICn : MCn}（助手主体）
│     </>
│   : MCn}
├─ {completion && Lxn}
├─ {digests && Oyn}
├─ {automationCards && kCn}
├─ {taskCards && ACn}
├─ {!history && header.fileChanges && jvn}
├─ {browserTurnEndRows && L2}
├─ {showActions
│   ? iCn（复制/重试/反馈，class="flex items-center gap-1
│          {compact ? opacity-100 : opacity-0 transition-opacity
│           group-hover/assistant-turn:opacity-100 focus-within:opacity-100
│           has-[[data-bot-group-delivery]]:opacity-100}"）
│   : sUt（hook 调用 + 来源命令）}
├─ {highspeedFooter && bCn}
└─ {assistantTailRows && L2}
```

### 4.3 行分派 `F2` / `gCn`

| `row.kind` | 渲染器 | 关键 class/属性 |
| --- | --- | --- |
| `userInput` | `nCn` | `data-v4-user-input-bubble`；长文本折叠 |
| `assistantText` | `aCn` | `w-full text-ui-base`；`group/assistant-row`；Markdown |
| `reasoning` | `oCn` | `D2/O2/k2`；`data-reasoning-content` |
| `turnHeader` | `sCn` | `border-b ... py-1 text-ui-sm`（调试） |
| `timelineMarker` | `pCn` | 两侧细线 + 居中标签 |
| `toolCall` | `mCn` → `r0` | `data-tool-call-id` / `data-tool-name` / `data-status` |
| `subagent` | `hCn` | `text-ui-sm text-[var(--color-foreground-subtle)]` |
| `artifact` | `ZSn` | 产物卡片 |

### 4.4 用户消息 `uin`

```
div[data-v4-user-input-bubble="true"]
class="flex max-w-full flex-col gap-2 rounded-xl rounded-tr-xs
       border border-border bg-surface px-4 py-3 text-ui-base text-foreground
       @min-[624px]/conversation:max-w-xl"
├─ $rn  UserInputCollapsible
│  └─ div[data-conversation-selectable="true"] class="relative min-w-0 flex-1"
│     ├─ div[data-v4-user-input-collapsible-content="true"]
│     │  class="min-w-0 overflow-hidden whitespace-pre-wrap break-words
│     │         transition-[max-height] duration-300 ease-out
│     │         motion-reduce:transition-none
│     │         {!expanded && collapsible && [mask-image:linear-gradient(...)]}"
│     └─ {collapsible && 展开/收起按钮
│         class="rounded-full bg-background shadow-sm backdrop-blur-sm
│                transition-colors"}
└─ {epilogue && Yrn}
   div[data-v4-user-input-epilogue="true"] class="flex min-w-0 flex-col gap-1"
```

### 4.5 思考过程 `D2/O2/k2`

```
D2  Reasoning  class="not-prose flex flex-col"
├─ O2  ReasoningTrigger
│  class="group/reasoning inline-flex max-w-full min-w-0 items-center gap-2
│         self-start text-ui-base transition-colors"
│  ├─ 图标 class="size-4 shrink-0 text-foreground-subtlest"
│  ├─ span[data-reasoning-label] class="shrink-0 whitespace-nowrap"
│  │  └─ {streaming ? span.animated-gradient-text.font-medium
│  │      : span.inline-flex.items-center.gap-2 > "思考 · N 秒"}
│  ├─ {streamingLine && span[data-reasoning-streaming-line]
│  │  class="min-w-0 flex-1 overflow-hidden whitespace-nowrap
│  │         text-foreground-subtle"
│  │  [data-reasoning-streaming-mask] [data-reasoning-streaming-roll]}
│  └─ 展开箭头 class="size-4 shrink-0 text-foreground-subtlest
│                    transition-opacity transition-transform
│                    {open ? rotate-90 opacity-100
│                          : rotate-0 opacity-0 group-hover/reasoning:opacity-100}"
└─ k2  ReasoningContent [data-reasoning-content="true"]
   [data-reasoning-content-variant="default|nested"] [data-testid=cWe]
   └─ div class="pt-3"
      └─ div[data-reasoning-scroll-mask]
         class="max-h-60 space-y-2 overflow-auto text-ui-base
                text-foreground-subtlest {default && ml-2 border-border border-l pl-3.5}"
         └─ div class="min-w-0 whitespace-pre-wrap break-words text-foreground-subtlest"
```

### 4.6 Timeline 状态

| 状态 | 属性 | 效果 |
| --- | --- | --- |
| 贴底跟随 | `data-following=true` | 新内容自动滚到底 |
| 用户上滚 | `data-following=false` | 显示回到底部按钮 |
| 加载历史 | `data-loading-older=true` | 顶部加载指示 |
| 虚拟窗口 | `data-index` + `translateY` | 仅渲染可视窗口 |
| 运行尾部 | `data-v4-running-live-tail` | 运行中回合不进入虚拟历史 |
| 浮动输入框 | `floatingComposerDock` | dock `absolute inset-x-0 bottom-0`，内容底部留 12px |
| 空态居中 | `centerEmptyStateWithDock` | 问候 + 输入框居中 |
| 历史折叠 | `nx[data-history-open]` | 历史区默认折叠，`pt-5` |

---

## 5. Tool（工具调用卡片）

### 5.1 分派

```
r0  ToolCallBlock  class="w-full"
[data-tool-call-id] [data-tool-name] [data-status] [data-zcode-tool-stream-animate]
└─ dZ.Provider (toolId context)
   └─ div class="w-full"
      └─ Agn(toolCall) 选择渲染器：
         ├─ kind=changesGroup      → Eln
         ├─ kind=executeGroup      → Lpn
         ├─ kind=cuaGroup          → zZ
         ├─ ToolSearch/LocalToolSearch → Vcn
         ├─ family=plan-guidance   → Phn
         ├─ family=agent           → yln
         ├─ family=todo            → Dgn
         ├─ family=ask-user-question → kgn
         ├─ family=message         → Ghn / Zhn
         ├─ family=task-control    → _gn / Sgn
         ├─ family=skill           → tgn
         ├─ family=workflow        → lgn / Spn
         ├─ family=session-context → Vhn
         ├─ family=file-read       → Pcn
         ├─ family=file-write      → ycn
         ├─ family=explore         → eln
         ├─ family=switch-mode     → dgn
         ├─ family=web-fetch       → Lcn
         ├─ family=search          → Bcn
         ├─ family=shell           → kcn
         ├─ family=goal            → rhn
         └─ default                → f1
```

子工具：

```
div class="ml-2 space-y-2 border-border border-l pl-3.5 border-border"
└─ r0 ToolCallBlock*（depth+1，同一分派）
```

### 5.2 ToolLayout `y0e`

```
div class="w-full flex flex-col"
└─ ex  Collapsible
   ├─ h0e  ToolSummary
   │  ├─ {action
   │  │   ? div[data-testid=tool-summary-trigger][role=button]
   │  │     class="group/tool-summary inline-flex max-w-full cursor-pointer
   │  │            items-center gap-2 self-start text-left text-ui-base
   │  │            transition-colors focus-visible:outline-none
   │  │            focus-visible:ring-2 focus-visible:ring-input-border-focused"
   │  │   : canToggle
   │  │   ? div[role=button][data-testid=tool-summary-trigger][aria-expanded]
   │  │     class="group/tool-summary inline-flex max-w-full cursor-pointer
   │  │            items-center gap-2 self-start text-left text-ui-base
   │  │            transition-colors focus-visible:outline-none
   │  │            focus-visible:ring-2 focus-visible:ring-input-border-focused"
   │  │   : div[data-testid=tool-summary-trigger]
   │  │     class="group/tool-summary cursor-default flex w-full items-center
   │  │            gap-2 text-left text-ui-base transition-colors
   │  │            focus-visible:outline-none"}
   │  ├─ 图标
   │  ├─ 类型标签 class="font-medium whitespace-nowrap shrink-0
   │  │                {running ? animated-gradient-text : text-foreground-subtlest}"
   │  ├─ 主文本 / 次文本 / diff 计数
   │  ├─ 状态标签（失败时 `whitespace-nowrap underline decoration-dotted
   │  │                underline-offset-2 cursor-help` + tooltip）
   │  └─ 展开箭头 class="size-4 text-foreground-subtlest opacity-0
   │                  transition-transform transition-opacity duration-200 ease-out
   │                  will-change-transform group-hover/tool-summary:opacity-100 shrink-0
   │                  {open ? rotate-90 opacity-100 : rotate-0}"
   └─ nx  CollapsibleContent class="text-popover-foreground outline-none"
      └─ div class="pt-2"
         └─ renderContent()
```

### 5.3 终端/命令 `kcn`

```
<>
├─ ow  ToolHeader（kindLabel = 终端/运行中；secondaryText = 命令摘要）
└─ sw  快照 refs
renderContent:
div[data-testid="execute-detail"]
class="space-y-3 mb-2 rounded-xl border border-border bg-panel px-4 py-3"
├─ div class="space-y-1"
│  └─ div class="flex items-start gap-2 font-sans text-ui-base text-foreground"
│     └─ Tcn 命令文本（`code.truncate.font-sans`）
└─ {preview || error || output
   ? Ecn 输出块（running 时流式）
   : !running && div class="space-y-1"
       └─ p class="font-mono text-ui-base text-foreground-subtle"（无输出）}
```

### 5.4 文件读取 `Pcn`

```
<>
├─ ow ToolHeader
│  ├─ kindLabel = "读取" / "读取中"
│  ├─ primaryText = LQ 文件链接
│  │  class="inline-flex min-w-0 max-w-full cursor-pointer items-center gap-1.5
│  │         text-foreground-subtle hover:underline"
│  │  ├─ 文件图标 class="size-4 shrink-0"
│  │  └─ 文件名 class="min-w-0 truncate"
│  └─ secondaryText = 文件路径
└─ sw 快照 refs
```

### 5.5 Tool 状态

| 状态 | 属性 | 效果 |
| --- | --- | --- |
| 运行中 | `data-status=running` | 类型标签 `animated-gradient-text`；进度条不确定态 |
| 成功 | `data-status=success` | 类型标签灰字；可折叠详情 |
| 失败 | `data-status=failed` | 状态文字点线下划线；tooltip 显示错误 + 复制 |
| 流式入场 | `data-zcode-tool-stream-animate=true` | 工具卡片流式动画 |
| 展开 | `aria-expanded=true` / `data-state=open` | 箭头旋转 90°，内容 `pt-2` |
| 子工具 | `ml-2 border-l pl-3.5` | 左侧竖线缩进 |
| todo 隐藏 | `showTodoToolCalls !== true` | `family=todo` 直接不渲染 |

---

## 6. 证据与限制

- 树节点来自编译后的 `(0,$.jsx)` / `(0,$.jsxs)` 调用，组件边界以函数/常量定义与 `data-*` 锚点划分。
- 关键偏移（字符偏移）见 `06-class-map.md` §8；完整 class 字面量与 CSS 规则见 `artifacts/class-map.json`。
- 未在浏览器中执行页面，因此：
  - Portal（Dialog/Popover/Tooltip）在真实 DOM 中的挂载位置未验证；
  - 虚拟列表只渲染窗口内节点，树中的 `*` 表示逻辑列表；
  - `Z(...)` / `cn(...)` 的条件分支只保留基础字面量与 `extra`，未枚举全部运行时组合；
  - CSS 层叠结果以 `components.normalized.css` 的合并近似为准，最终像素需浏览器/Compose 实测。
