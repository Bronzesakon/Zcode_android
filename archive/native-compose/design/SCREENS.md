# SCREENS —— remote v4 (3.15.3) 界面盘点 / Compose 复刻施工图

> **基线**：`Reference_web/3.15.3/`（2026-10-15 抓取的线上快照，见 `_manifest.md`）。
> 入口 `index.html` → 主包 `assets/index-BGa3KiRe.js`（6,592,643 B / **6,474,389 字符**，单行压缩）、
> 样式 `assets/index-BD2BL4Cc.css`（455,343 B）。
> **偏移口径**：本文所有 `@N` 均为 **UTF-8 解码后的字符偏移**，复现：
> `python -c "d=open('assets/index-BGa3KiRe.js',encoding='utf-8',errors='replace').read();print(d[N-400:N+400])"`。
> 偏移只对**本快照**成立；稳定的是符号/字面量。
>
> **交叉核对**（姊妹仓库只读）：`Zcode_harmony/Docs/15,18,19,20,10-remote-v4-*.md`（基线为旧构建 `index-B-ilXaCQ.js`）。
> 两版结构一致的地方本文直接采信；**凡新构建未复核的旧结论均标注**。

---

## 0. 一句话

手机远控页是**两页外壳**（`home` / `chat`）+ 一批**覆盖层**；对话页是一套**虚拟列表时间线 + 行类型分派 + 底部输入坞**；
所有界面**几乎全靠 Tailwind 工具类自绘**，但底下压着一层 **Radix UI 原语 + shadcn 风格封装组件**（`data-slot` 105 个），
外加 cmdk / Framer Motion / streamdown / shiki / katex / Lexical 等重型依赖。

---

## 1. 视图总览

### 1.1 分层

```
index.html 启动壳（zcode-boot-loading，纯 HTML/CSS，先于 JS）
└─ 应用根
   ├─ 【A】手机远控外壳  WebRemoteControlMobileShell  @4,338,165   ← 手机访问 /remote/v4 走这条
   │    ├─ A1 home  任务首页（工作区/任务列表）
   │    └─ A2 chat  对话页
   │         ├─ 覆盖层：side pane 抽屉 / chat overlay / 重连提示 / loading 遮罩
   │         └─ 对话内容 = 时间线 + composer dock
   └─ 【B】桌面工作区外壳（同一份 bundle 的另一分支，手机上不可达）
        ├─ B1 侧栏（工作区/任务树 + 命令面板入口）
        ├─ B2 工作区主视图：对话 / automations / plugin-store / settings
        ├─ B3 设置面板
        ├─ B4 侧窗格（side pane：终端/浏览器/Git/预览）
        ├─ B5 远控管理卡（二维码 + 复制链接 + bot channel）← 桌面端「手机远控」入口
        └─ B6 share 分享页
```

**门控（已复核，3.15.3）** @5,595,644：

```
rn && wn && M ? <Frame title={`ZCode / ${...}`} showHeader isDesktop><WebRemoteControlMobileShell/></Frame> : null
```

- `rn` = `isWebRemoteControl`（`/remote/v4` 入口判据）、`M` = `webRemoteControlWorkspaceSwitcher`（prop 名逐字，@5,595,900 区）。
- **`wn`（第二个门控条件）未在本次静态检索中定位 → UNKNOWN**（旧文 20 篇 §2.1 记为 `bn`，同一问题）。
- 结论：**A 与 B 互斥**。手机外壳渲染时桌面外壳不渲染 ⇒ 手机上不存在设置页/侧窗格/终端。
- `Frame`（组件 `uZ` @5,270,000 区）只输出 `div[data-desktop-window-frame] > div.relative.flex-1`，
  `showHeader` 在渲染体内**未被使用** ⇒ 手机端无桌面标题栏，两条栏由手机外壳自己提供。

### 1.2 手机可达视图清单

| # | 视图 | 判据 / 进入方式 | 主要区域 | 关键组件 |
|---|---|---|---|---|
| A1 | **任务首页 home** | 默认；`history.state.zcodeMobilePage !== 'chat'`（`Mbn()` @4,338,1xx） | ①页头（标题+连接态+主题菜单）②公告卡 ③分组头（标题+统计+折叠/整理/排序/刷新）④工作区分组卡 ⑤任务行 | `Ebn` @4,320,123 区；`webRemoteControl.mobileHome.*` 22 键 |
| A2 | **对话页 chat** | 点任务 / 新建任务 → `setPage('chat')` + `history.pushState({zcodeMobilePage:'chat'})`（`jbn()` @4,338,1xx） | ①返回栏 h-11 ②工作区/任务头（`renderChatHeader`）③时间线滚动区 ④composer dock | 根节点 `section[data-mobile-page="chat"]` @4,340,023 |
| A2-o1 | **侧窗格抽屉** | `isSidePaneOpen`（手机端专属形态） | 右侧滑入抽屉 | `div[data-mobile-side-pane-overlay="true"]`，宽 `w-[min(88vw,28rem)]` @4,340,0xx |
| A2-o2 | **对话覆盖层** | `chatOverlay`（设置/automations 等以覆盖层形式进入） | 全屏 `absolute inset-0 z-40` | `div[data-mobile-chat-overlay="true"]` @4,340,0xx |
| A2-o3 | **重连提示条** | 传输态 `reconnecting` | 顶部居中 toast | `div[data-web-remote-control-reconnect-notice="true"]` @4,342,288 |
| A2-o4 | **加载遮罩** | 首次加载/切换工作区 | 全屏半透明 + 转圈 | `bg-background/72 backdrop-blur-[1px]` + `animate-spin` @4,340,0xx |
| B5 | 扫码/配对（远控管理卡） | **桌面端**面板；手机上不可达 | 连接状态卡 + 二维码 + 复制链接/刷新二维码 + bot channel | `webRemoteControl.mobileQr.*`、`data-testid="web-remote-control-connection-card"` @4,557,888 |

> **重要边界**：`mobileQr.*` / `botChannel.*` / `triggerStatus.*` / `failure.*` 这四族键属于**桌面端远控管理面板**
> （手机扫桌面码的那一侧），**不是手机外壳的界面**。手机外壳实际用到的键面是：
> `mobileHome.*`(22) + `mobileShell.*`(3) + `themeMenu.trigger`(1) + `status*`/`statusDetail*`/`statusTag.*`(14) +
> `openTask`/`noTasks`/`untitled` 等零星文案。逐键核对依据：88 个 `webRemoteControl.*` 键的逐族计数见 §1.3。

### 1.3 i18n 键面即视图面（88 键，逐族）

实测（`re.findall(r'webRemoteControl\.[A-Za-z0-9_.]+')` 去重后 **88 键**，逐族计数）：

| 键族 | 键数 | 归属 |
|---|---|---|
| `mobileHome.*` | **22** | **A1 手机首页** |
| `mobileShell.*` | 3 | **A2 对话页**（`backHome` / `chatTitle` / `reconnecting`） |
| `mobileQr.*`(2) + `botChannel.*`(5) + `trigger`(1) + `triggerStatus.*`(6) + `failure.*`(12) + `copyLink*`(4) + `refreshQr.*`(5) + `qrAlt`(1) + `generating`(1) | **37** | **B5 桌面远控管理面板** |
| `status.*`(6) + `statusDetail.*`(6) + `statusTag.*`(2) | 14 | 六态连接文案（A1 页头 + B5 卡片共用） |
| `title` / `description` / `openTask` / `noTasks` / `startFailed` / `stop` / `stopFailed` / `stopSuccess` / `taskStatus.` / `themeMenu.trigger` / `collapseNavigation` / `expandNavigation` | 12 | 混合（`themeMenu.trigger` 属 A1/A2 页头；`collapseNavigation`/`expandNavigation` 属 B2 窄屏导航手柄） |

**六态连接词汇表**（`status.*` + `statusDetail.*` 各 6 值，3.15.3 复核与旧文 20 篇 §4 一致）：
`idle` / `connecting` / `starting` / `running` / `active` / `error`；`statusTag.phone` / `statusTag.ready`。

---

## 2. A1 任务首页（施工图）

来源：`Ebn`（`WebRemoteControlMobileTaskHome`），@4,323,700–4,326,000。

```
section.flex.h-full.min-h-0.flex-col.bg-background.text-foreground
├─ header.shrink-0.border-b.border-border.bg-header.px-4.py-3
│  └─ div.flex.min-w-0.items-start.justify-between.gap-3
│     ├─ div.min-w-0
│     │  ├─ div.truncate.text-ui-lg.font-medium          ← mobileHome.title
│     │  └─ div.mt-1.text-ui-base.text-foreground-subtle ← mobileHome.connected / .disconnected
│     └─ div.flex.shrink-0.items-center.gap-1
│        └─ [主题菜单 wbn]                                  ← DropdownMenu，见 §5.3
└─ div.min-h-0.flex-1.overflow-y-auto.px-3.py-3
   ├─ div.rounded-lg.border.border-card-border.bg-card.p-3.text-ui-base/relaxed.text-foreground-subtle
   │                                                        ← mobileHome.notice 公告卡
   └─ div.mt-4.flex.items-start.justify-between.gap-3        ← 分组头
      ├─ div.min-w-0
      │  ├─ h1.text-ui-base.font-medium                     ← mobileHome.sectionTitle
      │  └─ p.mt-1.text-ui-base.text-foreground-subtle      ← mobileHome.summary（taskCount/workspaceCount 插值）
      └─ div.flex.shrink-0.items-center.gap-1
         ├─ [折叠全部] Button variant=ghost size=icon-sm    ← 仅 organizeBy==='workspace' 时出现
         ├─ [整理菜单] DropdownMenu，Content className="w-52 min-w-52"
         │  ├─ Label  mobileHome.organize
         │  ├─ RadioGroup value=organizeBy  ← workspace | timeline
         │  ├─ Separator
         │  ├─ Label  mobileHome.sortBy
         │  └─ RadioGroup value=sortBy      ← created | updated
         └─ [刷新] Button ghost icon-sm
```

**工作区分组卡**（`organizeBy === 'workspace'`）：

```
div.flex.min-w-0.items-center.gap-2.px-3.py-3           ← 卡头
├─ button.flex.min-w-0.flex-1.items-center.gap-2        ← 展开/折叠（aria-expanded）
│  ├─ span.flex.size-8.shrink-0.items-center.justify-center.rounded-lg.bg-surface.text-foreground-subtle
│  │    图标：workspacePurpose==='conversation' ? 对话图标 : kind==='remote' ? 远程图标 : 本地图标
│  └─ span.min-w-0.flex-1
│     ├─ span.flex.min-w-0.items-center.gap-2
│     │  ├─ span.truncate.text-ui-base.font-medium.text-foreground   ← 工作区名
│     │  ├─ span.shrink-0.rounded-full.border.border-border.bg-surface.px-1.5.py-0.5.text-ui-xs.leading-none.text-foreground-subtle
│     │  │    ← workspaceKind.conversation | .remote | .local
│     │  └─ span.shrink-0.rounded-full.border.border-destructive/40.bg-card.px-1.5.py-0.5.text-ui-xs.leading-none.text-destructive
│     │       ← mobileHome.disconnected（仅离线时）
│     ├─ span.mt-1.block.truncate.font-mono.text-ui-base.text-foreground-subtle  ← 工作区路径
│     ├─ span.mt-1.block.truncate.text-ui-base.text-destructive ← lastConnectionError
│     └─ span.mt-1.block.text-ui-base.text-foreground-subtle    ← mobileHome.updatedAt（时间）
└─ span.flex.shrink-0.items-center.gap-2.text-ui-base.text-foreground-subtle
   ├─ span[data-workspace-unread-indicator].h-1.5.w-1.5.rounded-full.bg-sky-500.dark:bg-sky-400
   ├─ taskCount 文案
   └─ 展开/折叠指示图标（size-4）
+ button（sm, outline）：mobileHome.reconnect（转圈）或连接动作 —— 桌面/远程工作区不同分支
```

**任务行**（`li > button`，`data-testid` 为运行期生成、`data-state=selected|idle`）：

```
button.flex.min-h-12.w-full.min-w-0.items-center.gap-2.rounded-lg.px-2.5.py-2.text-left
       .transition-colors.disabled:cursor-wait
├─ span.relative.flex.size-4.shrink-0.items-center.justify-center   ← 状态槽
│  ├─ 运行中 → 图标 .size-4.animate-spin.text-foreground-subtle
│  ├─ 未读   → span[data-unread-indicator].h-1.5.w-1.5.rounded-full.bg-sky-500
│  ├─ 置顶   → 图钉图标 .size-4.text-foreground-subtle
│  └─ 否则   → null
└─ span.min-w-0.flex-1
   ├─ span.block.truncate.text-ui-base.text-foreground            ← 任务标题（空则 taskList.untitled）
   ├─ span.mt-1.flex.min-w-0.items-center.gap-1.5.text-ui-base.text-foreground-subtle  ← 更新时间
   ├─ 可选 workflowActivity 徽标（density=compact）
   └─ span.inline-flex.shrink-0.items-center.gap-1.rounded-full.border.px-1.5.py-0.5.text-ui-xs.leading-none
      状态徽标：running → 转圈 / completed → 勾 / error → 叉 / 其它 → null
      文案 webRemoteControl.taskStatus.${status}（动态键）
```

**`organizeBy === 'timeline'` 分支**：任务行改为 `li[data-web-remote-task-row]` @4,463,050，
`class="group/task-item relative flex w-full cursor-pointer items-center gap-2 rounded-lg
transition-[background-color,border-color,box-shadow]"`，选中态 `bg-selected`、悬停 `hover:bg-surface-hover`，
右侧 `data-task-row-actions`（pin / archive / delete 的 DropdownMenu）。

---

## 3. A2 对话页（重点）

### 3.1 页骨架（逐层）

来源 @4,340,023 起（`Nbn` 的 chat 分支）。

```
section.flex.h-full.min-h-0.min-w-0.flex-col.bg-background.text-foreground   [data-mobile-page="chat"]
├─ ① 返回栏  div.flex.h-11.shrink-0.items-center.gap-2.border-b.border-border.bg-header.px-2
│  ├─ Button variant=ghost size=icon-sm  aria-label=mobileShell.backHome   （chevron-left size-4）
│  ├─ span.min-w-0.flex-1.truncate.text-ui-base.font-medium                ← mobileShell.chatTitle
│  └─ [主题菜单 wbn]
├─ ② renderChatHeader()  → 工作区/任务头（fxn，variant=draft|task，simplifyForNarrowRemote:true）
│    含：任务标题、变更摘要、分支、git 摘要、侧窗格/终端开关、新建任务等（桌面同款组件的窄屏简化形态）
└─ ③ div.relative.flex.min-h-0.min-w-0.flex-1.flex-col.overflow-hidden
   ├─ main.flex.h-full.min-h-0.min-w-0.flex-1.flex-col.overflow-hidden
   │     [data-mobile-chat-surface="inert"]（侧窗格打开时 inert 冻结）
   │  └─ chatContent = 对话组件（§3.2 起）
   ├─ [覆盖层 A] div.absolute.inset-0.z-30  [data-mobile-side-pane-overlay="true"]
   │  ├─ button.absolute.inset-0.bg-background/60.backdrop-blur-[1px]     ← 点遮罩关闭
   │  └─ div.absolute.top-0.right-0.h-full.w-[min(88vw,28rem)].max-w-full
   │        .border-l.border-border.bg-background.shadow-2xl
   │        .transition-transform.duration-200.ease-out
   │        （开：translate-x-0 / 关：translate-x-full）
   └─ [覆盖层 B] div.pointer-events-none.absolute.inset-0.z-40  [data-mobile-chat-overlay="true"]
+ [覆盖层 C] div.absolute.inset-0.z-40.flex.items-center.justify-center.bg-background/72.backdrop-blur-[1px]
   └─ div.flex.items-center.gap-2.rounded-lg.border.border-border.bg-card.px-3.py-2.text-ui-base
        .text-foreground-subtle.shadow-sm  +  转圈 size-4.animate-spin   ← common.loading
+ [覆盖层 D] div.pointer-events-none.absolute.top-3.left-1/2.z-50  [data-web-remote-control-reconnect-notice]
   └─ div.flex.max-w-[calc(100vw-1.5rem)].items-center.gap-2.rounded-lg.border.border-border
        .bg-toast.px-3.py-2.text-ui-base.text-foreground.shadow-lg
```

**要点**：手机对话页**没有底栏**；底部只有 composer dock（§3.8）。页面纵向用 `h-full / min-h-0 / flex-col`
三段式（栏 / 栏 / 弹性滚动区），Compose 侧对应 `Column(Modifier.fillMaxSize())` + `weight(1f)`。

### 3.2 时间线容器（滚动区 + 虚拟列表）

来源 @3,451,132。这是**整个对话页的承重件**。

```
div
  className="min-h-0 flex-1 overflow-x-hidden overflow-y-auto
             [scrollbar-gutter:stable]
             [--markdown-table-layout-left-inset:16px] [--markdown-table-layout-right-inset:16px]
             max-md:[--markdown-table-layout-left-inset:8px] max-md:[--markdown-table-layout-right-inset:8px]"
  data-v4-timeline-scroll="true"
  data-v4-timeline-scroll-locked={y ? 'true':'false'}
  data-markdown-table-layout-root="true"
  data-row-count / data-window-row-count / data-render-unit-count / data-total-row-count
  data-following={Re ? 'false':'true'}
  data-loading-older={p ? 'true':'false'}
  onScroll / onWheelCapture / onPointer*Capture / onTouch*Capture / onKeyDownCapture
└─ div  （三种布局变体）
   ├─ 空会话草稿态：flex min-h-full flex-col items-center px-4
   │     before:min-h-[52px] before:basis-[29dvh]  ← 用 ::before 撑出 29dvh 的顶部留白
   │     after:min-h-4 after:flex-1
   ├─ 窄屏居中态：flex min-h-full flex-col items-center justify-center gap-4 px-4
   └─ 默认：flex min-h-full flex-col
      ├─ 虚拟化历史行：div.absolute.left-0.top-0.w-full  style=translateY(...)  [data-v4-turn-unit] [data-turn-id]
      ├─ 正在流式的最后单元：div[data-v4-running-live-tail="true"] @3,453,742（不进虚拟列表，直接渲染）
      ├─ 待发送队列/引导列表：data-v4-pending-guide-list
      └─ 时间线头槽位：data-v4-timeline-header-slot
```

- **虚拟列表**：`virtualizer` 字面量 34 处（TanStack Virtual 打包后改名，`tanstack` 0 命中）——与旧文 18 篇 §4 一致。
- **渲染单元切分**：最后一个 `isRunning` 单元被切出来当 liveUnit，其余进虚拟化（旧文 18 篇 §4.1 `bst`）。
  **3.15.3 复核**：`data-v4-running-live-tail` 仍为独立节点，机制保留。
- **吸底按钮**：`[data-v4-back-to-bottom-anchor="composer-dock"] button`（@1,633,168 的选择器常量），
  文案 `chat.scrollToBottom`，形态 `absolute bottom-full left-1/2 z-30 mb-2 -translate-x-1/2 shadow-sm`。

### 3.3 行类型 → 渲染器 → Tailwind 特征

行类型分派（`row.kind`，旧文 18 篇 §5 表；3.15.3 各渲染器锚点已复核存在）：

| `kind` | 渲染特征锚点（3.15.3 偏移） | 结构要点 |
|---|---|---|
| `userInput` | `data-v4-user-input-bubble` @3,457,769 | 气泡：`flex max-w-full flex-col gap-2 rounded-xl rounded-tr-xs border border-border bg-surface px-4 py-3 text-ui-base text-foreground @min-[624px]/conversation:max-w-xl`（**右上角小圆角**区分用户） |
| `assistantText` | `data-markdown-*` 族 @3,4xx | 正文走 markdown 管线（§3.6） |
| `reasoning` | `data-reasoning-content` @3,327,931 | 折叠块；展开体 `max-h-60 space-y-2 overflow-auto text-ui-base text-foreground-subtlest`，默认变体 `ml-2 border-border border-l pl-3.5`（**左侧竖线缩进**）；`data-reasoning-content-variant`、`data-reasoning-scroll-mask`（`none`/`top`/`bottom`/`both` 渐隐遮罩）、`data-reasoning-streaming-line/-text/-roll/-mask` |
| `toolCall` | `data-tool-call-id` @3,329,849 | 外层 `div.w-full`，挂 `data-tool-call-id` / `data-tool-name` / `data-status` / `data-zcode-tool-stream-animate`；组件 `ToolCallBlock`（`displayName='ToolCallBlock'`）；`family==='todo'` 且「隐藏 todo」时返回 `null` |
| `subagent` | `data-subagent-avatar` @2,942,828 | 头像为**自绘 SVG 小脸**（`viewBox 0 0 20 20`，`rect rx=7` 身体 + 眼睛/嘴），`data-face-state` / `data-expression` / `data-motion="idle"`；摘要走 `summaryText` 流式 |
| `artifact` | `data-artifact-kind` / `-id` / `-version` @2,987,394 | 产物 pill（`wf-pill`），有 `pill` / `link` 两种 variant；版本 ≥2 显示版本徽标 |
| `turnHeader` | `data-turn-id` @3,45x | 轮次头，`turnId` 挂在它上面 |
| `timelineMarker` | — | 时间线标记 |

**工具调用族的专用子组件**（都在主包内，均为自绘）：`data-trajectory-*` 共 60+ 个属性（@5,360,156 区）是
**轨迹/审计视图**的完整 DOM 契约；`data-workflow-*` 共 90+ 个属性（@3,064,855 区）是**工作流卡片/运行列表**的契约；
`data-highspeed-*` 是高速模式卡片。这些是复刻量最大的自绘组件群。

### 3.4 用户消息气泡与附件

```
div[data-v4-user-input-bubble]                                    @3,457,769
├─ 正文（markdown 内联渲染，sKt）
├─ contentParts：channelMention / plugin 提及 → 专用渲染器（_Kt）
└─ 附件（@3,343,643 / 3,457,451）
   ├─ inline 形态：data-v4-user-input-attachment-pill="true"
   │   h-12 w-fit max-w-full gap-2 rounded-lg border border-border bg-surface p-1.5 pr-6
   │   [--attachment-bg:var(--color-surface)]
   └─ media 形态：data-v4-user-input-media-attachment="true"
       ├─ 图片：relative size-12 overflow-hidden rounded-lg bg-surface p-0
       │         after:absolute after:inset-0 after:rounded-lg after:border after:border-border
       └─ 视频：relative size-20 overflow-hidden rounded-xl bg-surface p-0（同上 after 边框技巧）
       data-v4-user-edit-attachment-kind = image | video | pdf | file
       data-v4-user-input-attachments / -media-attachments / -quotes / -status / -command
       data-v4-user-input-source / -automation-origin / -collapsible-content / -epilogue(-body)
```

**上传态**（`data-upload-status` / `data-composer-attachment-kind`）：`uploading`（带百分比）/ `failed` / `ready`，
文案 `chat.attachments.upload.${status}`；上传协议（384 KiB 分片、`sha256:`、秒传、续传）见旧文 19 篇 §2，
**协议与 UI 解耦**——复刻 UI 只需处理四种 `uploadStatus` 视觉。

### 3.5 流式更新（视觉语义）

| 面 | 证据 | 复刻含义 |
|---|---|---|
| 增量 op 流 | 旧文 18 篇 §1.2：`row.delta{rowId,path,append}`，path ∈ `text`/`inputText`/`output.text`/`summaryText` | **追加语义**，不是整行重推 ⇒ Compose 侧按字段 append 即可 |
| 无本地节流 | 旧文 18 篇 §2（3.15.3 未见节流痕迹） | 帧频 = 更新频；用渲染单元 + 虚拟列表吸收 |
| 流式动画锚点 | `data-zcode-stream-animate`、`data-zcode-tool-stream-animate`（@3,329,849）、`data-zcode-tool-stream-animate` | 流式期给节点挂动画类 |
| 打字/加载动画 | `data-zcode-chat-loading-animate` @2,763,001（`role=status`，`animate-spin` 转圈 size-4/size-6） | 加载指示器 |
| 工具进度条 | `.tool-call-progress-indeterminate`（CSS @423,776 区，`left:-33% → 100%` 1.2s 循环） | 不定进度条动画 |
| 工作流动效 | `.wf-slide` `.32s`、`.wf-arrive`、`.wf-foldable`（CSS @440,364 区） | 卡片入场/折叠动效 |

### 3.6 Markdown / 代码 / 公式 / 表格

- **Markdown 引擎 = `streamdown`**（`streamdown` 字面量 12 处，@1,632,588 起；每个块挂 `data-streamdown="<block>"`）：
  `unordered-list` / `ordered-list` / `list-item` 已见；块级属性还有 `data-markdown-list`（`unordered|ordered`）、
  `data-markdown-blockquote`、`data-markdown-image`（+`-gallery` / `-state` / `-trigger` / `-local-image`）、
  `data-markdown-preview`。
- **列表样式**：`ol` → `my-3 list-inside list-decimal space-y-1.5 pl-0 marker:text-foreground-subtlest`，
  嵌套覆盖 `[&_ul]:my-1.5 [&_ol]:my-1.5`；`li` → `pl-1 [&>p]:my-0 [&>p]:inline`。
- **表格**：`data-markdown-table-layout-root="true"`（挂在时间线根，@3,451,216）、`-frame`、`-edge-shadow`、
  `-toolbar`、`-sticky-scrollbar`、`-virtual-scroll-*`；左右 inset 用 CSS 变量 `--markdown-table-layout-left/right-inset`
  控制（默认 16px，`max-md` 8px，`@min-[864px]/conversation` 48px）⇒ **表格可横向滚动 + 粘性滚动条 + 边缘阴影**。
- **代码高亮 = `shiki`**（20 处，主包内）；**diff = `diffs.worker`**（Web Worker）+ `data-diff-viewer` / `data-diff` /
  `data-diffs-header` / `data-inline-diff-preview` / `data-lightweight-diff-preview`；代码节点带 `data-code`。
- **公式 = `katex`**（6 处，主包 @57,253 区；KaTeX 字体 60+ 个 woff2/woff/ttf 在 `assets/` 里）。
- **图表/流程图**：`mermaid` 43 处（`data-mermaid-block`）、`cytoscape` 1 处、`chart-*` chunk（§7）。
- **图片预览**：`data-image-thumbnail-trigger`、`data-markdown-image-trigger`、`data-attachment-video-play-overlay`。

### 3.7 滚动与自动吸底

| 面 | 实现（证据） |
|---|---|
| 滚动容器 | `div[data-v4-timeline-scroll]`，`overflow-y-auto` + `[scrollbar-gutter:stable]`（**预留滚动条槽位，防抖动**） |
| 是否跟随 | **不是"滚到底"布尔**：由窗口首行是否越过 `firstRowId` + 当前 turn 头是否在窗口内算出（旧文 18 篇 §4.2），结果落到 `data-following` |
| 吸底实现 | 用 `sticky bottom-0` 的 composer dock + 顶部 `before:basis-[29dvh]` 占位 + `style:{overflowAnchor:'none'}`（@3,451,132 区）⇒ **显式关闭浏览器滚动锚定，自己管理** |
| 锁定 | `data-v4-timeline-scroll-locked`：重排/测量期间锁滚动；`!overflow-y-hidden` |
| 加载更早 | `data-loading-older`（区分"窗口空"与"会话空"，靠 `data-total-row-count`） |
| 回到底部按钮 | `[data-v4-back-to-bottom-anchor="composer-dock"]`，`chat.scrollToBottom` |
| 触摸/滚轮劫持 | `onWheelCapture` / `onTouchStart|Move|End|CancelCapture` / `onPointer*Capture` 全部接管 |

**Compose 落点**：`LazyColumn` + `rememberLazyListState`，用 `firstVisibleItemIndex` 复刻 `data-following` 判据；
`scrollbar-gutter` 对应 `Modifier.padding(end = 滚动条宽)`；`overflowAnchor:none` 在 Compose 里天然不存在（无需处理）。

### 3.8 底部输入区（composer dock + 会话工作台）

#### 3.8.1 坞（dock）容器 @3,454,481

```
div[data-v4-composer-dock="true"]
  className="pointer-events-none z-20 flex w-full justify-center
             " + (空会话 ? "mt-3 shrink-0" : 对话中 ? "shrink-0" : "sticky bottom-0")
└─ div[data-v4-composer-dock-content="true"]
     className="pointer-events-auto relative z-10 w-full shrink-0
                transition-[width,max-width,transform] duration-150 ease-out
                @min-[1280px]/conversation:transition-[transform]" + 宽度/内边距变量
   └─ div[data-v4-back-to-bottom-anchor="composer-dock"].relative
      ├─ [回到底部按钮]（条件渲染）
      └─ 输入表单（下）
```

#### 3.8.2 表单主体（@2,086,095 起）

```
form.relative
├─ div.absolute.inset-x-0.bottom-full.z-20                    ← 悬浮层槽位（@提及面板等）
├─ div.relative.flex.flex-col.gap-3.overflow-hidden.rounded-2xl
│     .border.border-input-border.bg-input.p-3
│     .transition-colors
│     .hover:border-input-border-hover
│     .focus-within:!border-input-border-focused.focus-within:bg-input-focused
│     + (拖拽/聚焦时) .border-brand.bg-input-focused.ring-1.ring-brand/30
│  ├─ [拖拽遮罩] div.pointer-events-none.absolute.inset-0.z-10.flex.items-center.justify-center
│  │     .rounded-2xl.bg-accent/55.backdrop-blur-sm
│  │     └─ div.flex.items-center.gap-2.rounded-full.border.border-border.bg-accent.px-4.py-2
│  ├─ 附件行     [data-composer-file-attachments-row]      （§3.4 的 pill/grid 形态）
│  ├─ 上下文附件行 [data-composer-context-attachments-row]
│  ├─ 编辑器/文本区（Lexical；见 UNKNOWN-1）
│  └─ div.group/toolbar.flex.items-end.gap-3
│     ├─ div.flex.min-w-0.flex-1.items-center   [data-composer-leading-actions]
│     │  └─ div.flex.shrink-0.items-center.gap-1 [data-composer-leading-content]
│     │     ├─ 附件/斜杠命令菜单（SAt，含 showPlugins）
│     │     └─ 其它前置槽位
│     └─ div.ml-auto.flex.shrink-0.items-center.justify-end.gap-1.5 [data-composer-trailing-actions]
│        ├─ [停止/中断] Button ghost size=icon-lg（Esc 快捷键，Tooltip）
│        ├─ 模型选择 / 思考级别（data-thought / data-model / data-plan-enabled 隐藏探针 span）
│        └─ [发送] Button type=submit size=icon-md data-testid="chat-send-button"
│              className="gap-1 rounded-lg bg-brand text-ui-base text-foreground-inverse hover:bg-brand/80"
│              发送中显示另一种图标
```

**关键 Tailwind 特征**：`rounded-2xl`（比气泡的 `rounded-xl` 大一档）、`bg-input`（不是 `bg-card`）、
三态边框 `input-border → -hover → -focused`、`focus-within:bg-input-focused`、`p-3`、`gap-3`。

#### 3.8.3 会话工作台相关子块

| 块 | 锚点 | 说明 |
|---|---|---|
| 草稿欢迎页 | `data-v4-draft-greeting` @2,082,4xx | 大标题**按可用宽度动态算字号**（`--v4-draft-greeting-font-size`，ResizeObserver 测量），配一个 5:4 的装饰图形 + mask 渐隐 |
| 建议提示 | `data-v4-draft-suggested-prompts`（+`-group` / `-slot`） | 横滚 chips 形态：`overflow-x-auto [scrollbar-width:none] [&::-webkit-scrollbar]:hidden`，chip 有瀑布式入场延迟 `--zcode-draft-prompt-waterfall-delay`（`i*65ms`） |
| 消息队列 | `data-v4-queue-item-id` / `-queue-count` / `-queue-drag-handle` / `-queue-auto-drain` | 排队消息可拖拽排序 |
| 待发送引导 | `data-v4-pending-guide-list` | |
| 输入路由 | `data-input-routing` | 排队语义见旧文 19 篇 §1.5（**未展开 → UNKNOWN**） |
| 工作台/侧窗格 | `data-side-pane-*`（20+ 属性）、`data-v4-pane-actions` @2,562,977 | 手机端以右侧抽屉呈现（§3.1 覆盖层 A） |
| 会话内查找 | `data-conversation-find-ignore`、`data-conversation-selectable` | 文本选择引用 |

---

## 4. 导航与全局外观

### 4.1 顶栏 / 底栏

| 位置 | 手机外壳（A） | 桌面外壳（B） |
|---|---|---|
| 顶部栏 1 | A1：`header.shrink-0.border-b.border-border.bg-header.px-4.py-3`（标题+连接态+主题菜单） | 桌面标题栏（`Frame` + 窗口控件，`max-md` 不适用） |
| 顶部栏 2 | A2：`div.flex.h-11.shrink-0.items-center.gap-2.border-b.border-border.bg-header.px-2`（返回+标题+主题菜单） | 工作区头 `fxn`（variant draft/task） |
| 顶部栏 3 | A2：`renderChatHeader()` → 工作区/任务头（`simplifyForNarrowRemote:true`） | 同上，完整形态 |
| 底栏 | **无**。底部只有 composer dock | 状态栏（`data-status-section*`）、终端标签栏等 |
| 侧栏 | 无侧栏；改为覆盖层抽屉 + `renderChatHeader` 里的入口 | `aside[data-testid=...]` 工作区侧栏，可拖拽调宽（`data-testid="resizable-handle"`） |

### 4.2 宽屏 / 窄屏差异

1. **两套外壳互斥**（§1.1 门控）：`/remote/v4` 手机入口 → 手机外壳；桌面工作区 → 桌面外壳。
2. **桌面外壳的窄屏降级**：侧栏在 `isWebRemoteControl` 且 `max-md` 时变成**顶部横向导航面板**
   （`data-web-remote-navigation-scroll` @4,858,648）：
   - 高度 `max-md:!h-[var(--web-remote-navigation-height)]`、`max-md:!w-full`、`max-md:border-b`、`max-md:bg-sidebar`；
   - 收起态 `max-md:!h-0 max-md:!basis-0`；
   - 展开手柄：`button[data-web-remote-navigation-resize-handle]`，
     `absolute left-1/2 z-30 hidden -translate-x-1/2 -translate-y-1/2 touch-none cursor-ns-resize max-md:inline-flex`，
     `aria-controls="web-remote-control-navigation-panel"`，文案 `webRemoteControl.expandNavigation` / `collapseNavigation`。
3. **CSS 断点**（`index-BD2BL4Cc.css` 实测）：Tailwind v4 默认 `40rem / 48rem / 64rem / 80rem / 96rem`，
   另有 `width<=480px`、`height<=740px`、`hover:hover`、`hover:none`、`prefers-reduced-motion`、
   `prefers-color-scheme`、`forced-colors`。
   **容器查询**在对话页大量使用：`@min-[624px]/conversation`、`@min-[864px]/conversation`、`@min-[1280px]/conversation`。
4. **对话列宽**：由 `Qe` / `Ze` 变量控制，`transition-[width,max-width,transform] duration-150 ease-out` ⇒ 列宽变化有动效。

### 4.3 主题（明暗）

| 面 | 证据 |
|---|---|
| 启动期主题（纯 HTML，先于 JS） | `index.html` 内联脚本：`localStorage['zcode-theme']`，默认 `zai-dark`；写 `documentElement.classList.toggle('dark')`、`data-zcode-bootstrap-theme`、`data-zcode-browser-theme-surface`、`color-scheme`、`meta[name=theme-color]`（dark `#161616` / light `#f8f8f8`） |
| 引导快照 | `sessionStorage['zcode:web-remote-control:bootstrap-shell']`，含 `{theme, background, foreground, border, card}` → 写入 `--zcode-bootstrap-bg/-fg/-border/-card` |
| 运行时主题类 | `theme-zai-dark` / `theme-zai-light`（旧文 20 篇 §5.1；本快照 CSS 中 `.dark` 出现 **265** 次，`oklch(` **164** 次） |
| 三态选项 | `Cbn = ['system','zai-dark','zai-light']` @4,320,123；UI 为 DropdownMenu RadioGroup，`Content className="w-48"`，`align="end"`，触发按钮 `aria-label=webRemoteControl.themeMenu.trigger` + 调色板图标 `size-4` |
| 主题令牌 | `--color-background` 70 处、`--color-foreground` 162 处；`:root` 令牌块 @377,120 起。**完整色值见 `design/tokens/COLORS.md`（本篇不展开）** |
| 暗色语法 | Tailwind 工具类里大量 `dark:` 前缀（例：`bg-sky-500 dark:bg-sky-400`）⇒ **class 策略，非 media 查询** |

**Compose 落点**：一个 `ZcodeTheme`（`darkColorScheme` / `lightColorScheme`）+ `LocalZcodeColors` 提供
`background / foreground / foregroundSubtle / foregroundSubtlest / card / cardBorder / surface / surfaceHover /
hover / selected / header / toast / input / inputBorder(-Hover/-Focused) / inputFocused / border / borderHover /
primary / secondary / destructive / accent / brand / popover / muted / success` 等语义槽。

### 4.4 滚动条（全局，CSS @423,394 区，逐字）

```css
* { scrollbar-width: auto; scrollbar-color: var(--color-border) transparent }
::-webkit-scrollbar { width: 14px; height: 14px }
::-webkit-scrollbar-track { background: 0 0 }
::-webkit-scrollbar-thumb {
  background: var(--color-border);
  background-clip: padding-box;
  border: 3px solid #0000;      /* 透明边框 → 视觉细条 */
  border-radius: 9999px;
  min-width: 32px; min-height: 32px;
}
::-webkit-scrollbar-corner { background: 0 0 }
```

- 工具类 `.scrollbar-hide` / `.!scrollbar-hide` → `scrollbar-width:none` + `::-webkit-scrollbar{display:none}`。
- 终端 `.terminal-xterm-shell .xterm-viewport`、PPTX 渲染面 `[data-zcode-pptx-render-surface]` 内**强制隐藏**滚动条。
- 对话时间线容器用 `[scrollbar-gutter:stable]` 预留槽位。
- **Compose 落点**：14px 宽、thumb 用 `border` 色、圆角 9999、`padding-box` 内缩 3px ⇒ 可用
  `Modifier.drawWithContent` + 自绘 thumb，或 Compose 1.7+ 的 `Modifier.verticalScroll` 自绘指示器。

### 4.5 字体与间距基调

| 面 | 值 / 证据 |
|---|---|
| 字体族 | `Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif`（`index.html` body 规则 @122）；`font-family` 在 CSS 中 56 处 |
| 等宽 | `font-mono` 用于工作区路径、任务头路径 |
| 语义字号 | `--text-ui-caption` / `--text-ui-xs` / `--text-ui-sm` / `--text-ui-base` / `--text-ui-lg` / `--text-ui-xl`（`.text-ui-*` 工具类，CSS @142,588–143,646 区） |
| 行高 | 语义变体 `.text-ui-base/relaxed` → `line-height: var(--leading-relaxed)`；另有 `leading-3/4/4.5/...` |
| 字重 | `font-medium` 为主（标题/键值），正文默认 |
| 圆角基调 | `rounded-sm`(0.25) / `rounded-md` / `rounded-lg`(0.5) / `rounded-xl`(0.75) / `rounded-2xl`(1) / `rounded-full`；**用户气泡 `rounded-xl + rounded-tr-xs`**（右上角小圆角）是签名式细节 |
| 间距基调 | 4px 栅格（Tailwind v4 `--spacing`）；`px-3 py-3`（首页正文）、`px-4 py-3`（页头）、`p-3`（composer）、`gap-1 / gap-1.5 / gap-2 / gap-3` |
| 阴影 | `shadow-sm`（浮层小）、`shadow-lg`（toast）、`shadow-2xl`（侧抽屉）、`shadow-md`（路径徽标） |
| 动效 | `duration-150 ease-out`（列宽/宽度）、`duration-200 ease-out`（抽屉/遮罩透明度）、`.wf-slide .32s`、`tool-call-progress-indeterminate 1.2s`；全部有 `motion-reduce:` 对应项 |
| 尺寸档 | Button `size` 档：`xs(h-5) / sm(h-6) / default(h-7) / icon-sm / icon-md / icon-lg / lg`（@225,662 的 variant 表） |

---

## 5. 自绘 vs 组件库

### 5.1 结论

**不是"纯 Tailwind 自绘"，也不是"用了现成 UI 库的成品组件"**，而是第三种：**Tailwind v4 工具类 + Radix UI 无头原语 + 自建的 shadcn 风格封装层**。业务组件（时间线、气泡、工具调用块、工作流卡、轨迹视图）**全部自绘**。

### 5.2 判定依据（逐条）

| 证据 | 位置 | 说明 |
|---|---|---|
| `Symbol.for('radix-ui')` + `Primitive.${t}` + `data-radix-collection-item` | @136,790 | Radix 的 `Primitive` 工厂逐字存在 ⇒ **确定使用 Radix 原语** |
| `@radix-ui/react-visually-hidden` | @147,091 | 唯一保留原名的包路径（其余被打包改名） |
| `radix` 字面量 121 处；`data-radix-*` 属性 11 处 | 主包 | `data-radix-menu-content` / `-collection-item` / `-select-viewport` / `-scroll-area-viewport` / `--radix-collapsible-content-height` / `--radix-dropdown-menu-content-available-width` |
| `cmdk` 47 处（`[cmdk-group=""]`、`[cmdk-item=""]`、`cmdk-item-select`） | @1,468,741 | 命令面板用 **cmdk** |
| **`data-slot` 属性 105 个**（shadcn/ui 的标志性约定） | 主包 | 完整清单见 §5.3 ⇒ 封装层是 shadcn 风格 |
| `motion` 108 处 / `framer` 3 处 / `data-framer-portal-id` | 主包 | 动效用 Framer Motion |
| `lucide` 1 处 + `createLucideIcon-*.js`（`index.html` modulepreload） | @66,081 / `assets/createLucideIcon-BsjirBgz.js` | 图标 = **lucide**，每个图标一个 chunk（`assets/a-arrow-down-*.js` 等 2,500+ 个） |
| `streamdown` 12 处 | @1,632,588 | Markdown 渲染引擎 |
| `shiki` 20 / `katex` 6 / `mermaid` 43 / `lexical` 45 / `xterm` 82 / `ansi` 580 / `zod` 47 | 主包 | 高亮 / 公式 / 图 / 富文本输入 / 终端 / ANSI / 协议校验 |
| `shadcn` / `class-variance-authority` / `tailwind-merge` / `clsx` / `cva(` / `zustand` / `tanstack` **均 0 命中** | — | 这些**不是**判据（打包改名），**不要**用"grep 不到"下反向结论 |
| `Button` variant 表逐字存在（`variant:{input,default,outline,secondary,ghost,destructive}` + `size:{xs,sm,default,...}`） | @225,662 | 自建封装层的 API 形态（cva 风格，但 cva 本体被改名） |

### 5.3 shadcn 风格封装层的组件清单（`data-slot` 105 值）

`alert` `alert-description` `alert-dialog(+action/cancel/content/description/footer/header/overlay/portal/title)` `alert-title`
`avatar(+fallback/image)` `badge` `button` `card(+action/content/description/header/title)` `checkbox(+checked-icon/indicator/indeterminate-icon)`
`collapsible(+content/trigger)` `command(+empty/group/input/input-wrapper/item/list/shortcut)`
`context-menu(+content/item/separator/sub/sub-content/sub-trigger/trigger)` `dialog(+close/content/description/footer/header/overlay/portal/title/trigger)`
`dropdown-menu(+checkbox-item/checkbox-item-indicator/content/item/label/radio-group/radio-item/radio-item-indicator/separator/shortcut/sub/sub-content/sub-trigger/trigger)`
`hover-card(+content/portal/trigger)` `input-group(+addon/control)` `kbd` `kbd-group` `label` `progress(+indicator)`
`scroll-area(+scrollbar/thumb/viewport)` `select(+content/item/scroll-down-button/scroll-up-button/trigger/value)`
`switch(+thumb)` `tabs(+content/list/trigger)` `tooltip(+content/provider/trigger)`

**手机外壳实际用到的子集**（其余属桌面外壳）：
`button`（6 variant × 7 size）、`dropdown-menu`（首页整理菜单 + 主题菜单）、`tooltip`、`badge`（状态 pill 是手写的，不是 `badge`）、
`scroll-area`（可能）、`collapsible`（工作区分组）。

### 5.4 纯自绘的部分（Compose 必须自己写）

时间线虚拟列表、行类型分派、用户气泡、思考折叠块、工具调用块（含 60+ `data-trajectory-*` 子面）、
子代理 SVG 小脸头像、工作流卡片/运行列表（90+ `data-workflow-*` 子面）、高速模式卡片、
markdown 表格粘性滚动条与边缘阴影、附件 pill/grid、composer dock、草稿欢迎页动态字号、回到底部按钮、
滚动条样式、轨迹/审计视图。

---

## 6. 复刻优先级（从最先做到最后）

排序依据：**协议相关性强弱**（强 ⇒ 先做，因为要跟 Rust 协议层联调、越早暴露不匹配越好） × **交互复杂度**（低 ⇒ 先做，先立骨架）。

| 序 | 内容 | 协议相关性 | 交互复杂度 | 理由 |
|---|---|---|---|---|
| 1 | **令牌与主题骨架**（`tokens/colors.json` → `ZcodeTheme`；`--text-ui-*` 字号；`--spacing` 4px 栅格；`rounded-*` 档位） | 无 | 极低 | 一切界面的地基；先定死语义色槽与字号档，后续组件不再返工 |
| 2 | **A2 对话页骨架**：三段式 Column + 返回栏 h-11 + 时间线滚动容器 + composer dock 空壳 | 弱 | 低 | 把"最常驻、最承重"的布局定下来；`data-mobile-page`/`data-v4-timeline-scroll` 的 DOM 语义直接映射到 Compose 结构 |
| 3 | **时间线行渲染（只读）**：`userInput` 气泡 + `assistantText`（markdown 纯文本/粗体/列表）+ `turnHeader` | **强**（`row.kind` 分派 + 窗口/`totalCount`） | 中 | 协议第一入口；先把"能看见消息"跑通，验证 `row.appended/upserted/removed` 与窗口语义 |
| 4 | **流式追加**：`row.delta` 四路（`text`/`inputText`/`output.text`/`summaryText`）+ liveUnit 直挂 + 打字指示 | **极强** | 中 | 流式是最容易做错的协议面（append 而非重推、只在窗口内生效）；越早验证越省事 |
| 5 | **跟随/吸底 FSM**：`data-following` 等价物 + 回到底部按钮 + `scrollbar-gutter` 等价内边距 + 加载更早 | 中（依赖窗口状态） | 中高 | 体验分水岭；但判据依赖 #3/#4 的窗口状态，必须在其后 |
| 6 | **底部输入区**：composer dock 容器（`rounded-2xl bg-input` 三态边框）+ 文本输入 + 发送按钮 + 附件 pill（含 4 态上传） | **强**（附件分片协议 `attachmentBeginV4`） | 中高 | 有输入才能验证 `userInput` 行与附件协议；UI 结构清晰（§3.8）可照抄 |
| 7 | **工具调用块 + 思考块**（折叠/展开、状态色、流式动画、todo 族隐藏规则） | **强**（`toolCall` 行的 `inputText`/`output.text`） | 高 | 子面最多（60+ `data-trajectory-*`）；先做通用折叠壳，再逐个 toolName 特化 |
| 8 | **A1 任务首页**：页头 + 公告卡 + 分组头（整理/排序菜单）+ 工作区分组卡 + 任务行 + 六态连接文案 | 中（`workspace-list` / `sessions-index`） | 中 | 依赖工作区列表与任务索引协议，但视觉相对独立、可并行；放在对话页之后是因为"先用得上" |
| 9 | **覆盖层**：侧窗格抽屉（88vw/28rem）+ 重连提示 toast + 加载遮罩 + 对话覆盖层 | 中（传输态 `reconnecting`） | 中 | 依赖 #2 骨架与传输态；结构简单（§3.1），可批量做 |
| 10 | **代码块 / 表格 / 公式 / 图片 / diff** | 弱 | 高 | 纯渲染能力，协议无关；工作量与依赖（shiki/katex/mermaid 等价物）都大，且没有它们对话仍可用 |
| 11 | **子代理 / 产物 / 工作流卡片 / 轨迹视图** | 中（`subagent`/`artifact` 行） | 极高 | 自绘子面最多（90+ `data-workflow-*`）；出现频率低于正文 |
| 12 | **草稿欢迎页 / 建议提示 / 消息队列 / 会话工作台侧栏** | 弱 | 中 | 锦上添花；动态字号、拖拽排序、瀑布入场都是细节成本 |
| 13 | **桌面端专属（B1–B6）**：侧栏、设置、侧窗格终端/浏览器、远控管理卡（二维码） | 弱（B5 才涉及 relay 配对） | 极高 | 手机外壳已与桌面外壳互斥（§1.1）⇒ **不在手机复刻范围内**；仅当要做"手机当主机/桌面形态"时才排 |

**一句话**：先"令牌 → 布局骨架 → 只读时间线 → 流式 → 吸底 → 输入"，再横向铺开工具块与首页，
渲染重型件（代码/表格/公式）与桌面外壳放最后。

---

## 7. 依赖的 chunk（懒加载，复刻时按需取舍）

| 类 | 3.15.3 快照中的 chunk | 体积 |
|---|---|---|
| Office 表格预览 | `previewPaneOfficeXlsxContent-*` | MB 级 |
| PPTX 预览 | `pptxRendererPreviewEngine-*` | MB 级 |
| Office 旧版文档 | `previewPaneOfficeLegacyDocContent-*` | MB 级 |
| PDF | `pdf-viewer-*` | 数百 KB |
| 图表 | `CartesianChart-CSIIRRMW.js`(30KB) `Bar-lys7utGI.js`(16KB) `Line-CIToBz0O.js`(14KB) `AppUsageModelUsagePieChart-DZDybh1C.js`(20KB) `ArtifactChartView-Dvxhyy-S.js`(20KB) `AppUsageDailyModelTrendChart-ewqVA1Ez.js`(4.5KB) | |
| 图标 | `assets/a-arrow-down-CamatvE_.js`(311B) 等 **2,500+ 个** lucide 图标 chunk | 单文件极小 |
| i18n | `IntlProvider-C-26GNLk.js` | 855,577 B |
| 语法高亮 | `abap-*` / `ada-*` / `angular-ts-*` 等（每语言两套 hash 重复产物） | 十几 KB – 数百 KB |
| 公式 | `katex-DefY6ydN.js` + 60+ KaTeX 字体（woff2/woff/ttf） | |
| 其它 | `officeFilePreview-*`、`usageStatsUiParts-*`、`appUsageChartPalette-*`、`keyboardShortcuts-*` | |

> 注意：`index.html` 的 `<link rel="modulepreload">` 列出 **~55 个**入口依赖（含 `react`/`react-dom`/`jsx-runtime`/
> `createLucideIcon`/`katex`/`officeFilePreview`/`IntlProvider`），可作为"首屏必需 vs 懒加载"的分界线。

---

## 8. UNKNOWN 清单（不猜，逐条给复现方向）

| # | 未知项 | 已确认的部分 | 复现方向 |
|---|---|---|---|
| U1 | **Lexical 编辑器的 DOM 结构与样式** | `data-lexical-editor` 在主包 **0 命中**；`lexical` 45 处、`data-lexical-text/-cursor/-decorator/-linebreak` 在属性表里；`editorStateJson` 为持久化字段 | 去 chunk 找 Lexical 运行时（旧文 19 篇 §1.1 记簇在旧包 830k–1,077k）；本快照未定位 |
| U2 | **手机外壳门控第二条件 `wn`** | 已确认 `rn`=isWebRemoteControl、`M`=webRemoteControlWorkspaceSwitcher（@5,595,644） | 反查 `wn` 的来源变量名（父组件解构） |
| U3 | **`data-testid` 的具体字面值** | 120 个 `data-testid` 值中绝大多数是**压缩后的变量名**（如 `AWt`/`Bee`/`Vne`）；仅少数是字面量：`web-remote-control-connection-card`、`web-remote-control-copy-link-row`、`chat-send-button`、`archived-tasks-toolbar-actions`、`resizable-handle`、`workspace-drag-overlay`、`workflow-saved-chip`、`workflow-run-artifact-version` | 需运行时 dump DOM（静态不可枚举） |
| U4 | **手机端能否进入桌面设置页** | 手机外壳与桌面外壳互斥（§1.1）；`webRemoteControl.*` 88 键中无手机设置族 | 需真机点测；静态判据倾向于"不能" |
| U5 | **`mobileHome` 的排序键与分组规则细节** | 已知 `sortBy ∈ {created, updated}`、`organizeBy ∈ {workspace, timeline}`、`collapseAll`、`updatedAt` | 排序实现（按 `updatedAt` 还是 `lastActivityAt`）与分组边界未取证 |
| U6 | **`inputRouting` 排队语义** | 字段存在（旧文 19 篇 §1.5）；UI 有 `data-queue-*` 7 个属性 | 排队策略未展开 |
| U7 | **`data-v4-timeline-header-slot` 的内容** | 槽位存在 | 填充者未定位 |
| U8 | **`chat-error-banner`** | 旧文 18 篇 §7.1 记该 testid 在主包；**本快照 `chat-error-banner` 0 命中** | 可能已改名/移除；本快照未找到等价锚点 |
| U9 | **主题令牌的明暗具体色值** | `:root` @377,120 起；`--color-background` 70 处、`oklch(` 164 处 | 属 `design/tokens/`（另一篇），本篇不展开 |
| U10 | **`Frame` 的 `showHeader` 实际效果** | `uZ` 渲染体未使用 `showHeader`；桌面标题栏组件未定位 | 桌面形态未取证（手机端无影响） |

---

## 9. 复刻对照速查表（Tailwind 类 → Compose）

| Tailwind 特征 | 出现处 | Compose 对应 |
|---|---|---|
| `flex h-full min-h-0 min-w-0 flex-col` | 页骨架 | `Column(Modifier.fillMaxSize())` |
| `shrink-0` | 两条栏 | 无 `weight()` 的固定高度子项 |
| `min-h-0 flex-1 overflow-y-auto` | 首页正文 / 时间线 | `Modifier.weight(1f).verticalScroll(...)` / `LazyColumn` |
| `border-b border-border bg-header px-4 py-3` | 首页页头 | `Surface(color = header)` + `Divider` + `padding(16.dp, 12.dp)` |
| `h-11 ... border-b border-border bg-header px-2` | 对话返回栏 | 44.dp 高 `Row`（h-11 = 2.75rem = 44dp） |
| `rounded-xl rounded-tr-xs` | 用户气泡 | `RoundedCornerShape(12.dp, 2.dp, 12.dp, 12.dp)`（xl=12dp, xs=2dp） |
| `rounded-2xl border border-input-border bg-input p-3` | composer | `RoundedCornerShape(16.dp)` + `border(1.dp, inputBorder)` + `background(input)` + `padding(12.dp)` |
| `focus-within:!border-input-border-focused focus-within:bg-input-focused` | composer 聚焦态 | `Modifier.onFocusChanged` 驱动边框/底色 |
| `size-4 / size-3.5 / size-3 / size-8 / size-12 / size-20` | 图标与缩略图 | 16 / 14 / 12 / 32 / 48 / 80 dp |
| `w-[min(88vw,28rem)]` | 侧抽屉 | `widthIn(max = 448.dp).fillMaxWidth(0.88f)` |
| `bg-background/60` `bg-background/72` `bg-accent/55` | 遮罩 | `Color.copy(alpha = 0.60f / 0.72f / 0.55f)` |
| `backdrop-blur-[1px]` `backdrop-blur-sm` | 遮罩 | Compose 无原生 backdrop blur；可用 `RenderEffect`(API 31+) 或降级为纯 alpha |
| `translate-x-0 / translate-x-full transition-transform duration-200 ease-out` | 抽屉开合 | `AnimatedVisibility(slideInHorizontally(tween(200, easing = LinearOutSlowInEasing)))` |
| `animate-spin` | 所有加载指示 | `Modifier.rotate(infiniteRepeatable(tween(1000, easing = LinearEasing)))` |
| `bg-sky-500 dark:bg-sky-400` | 未读点 | 语义色槽 `unreadDot`（明暗各一值） |
| `font-mono` | 路径 | `FontFamily.Monospace` |
| `text-ui-base/relaxed` | 多数正文 | `fontSize = 13.sp`（以 tokens 为准）+ `lineHeight` |
| `[scrollbar-gutter:stable]` | 时间线 | 滚动区右侧预留滚动条宽度 |
| `marker:text-foreground-subtlest` | 有序列表序号 | 自绘序号文本色 |

> 具体数值以 `design/tokens/typography.json` / `layout.json` 为准（本篇只给类名→语义的映射关系）。

---

## 10. 出处索引

**主来源**（`Reference_web/3.15.3/`，偏移均为 `index-BGa3KiRe.js` 的字符偏移）

| 面 | 锚点 | 偏移 |
|---|---|---|
| 启动壳 + 主题引导脚本 + modulepreload 清单 | `index.html` | 1–313 |
| 手机外壳挂载门控 | `rn&&wn&&M?...<Nbn/>` | 5,595,644 |
| 手机外壳调度器（home/chat） | `Nbn` | 4,338,165 |
| home/chat 判据 | `Mbn` / `jbn` / `Z0` | 4,338,1xx |
| 对话页根节点 | `data-mobile-page="chat"` | 4,340,023 |
| 侧窗格抽屉 | `data-mobile-side-pane-overlay` | 4,340,0xx |
| 重连提示 | `data-web-remote-control-reconnect-notice` | 4,342,288 |
| 首页页头 / 分组头 / 整理菜单 | `webRemoteControl.mobileHome.title` | 4,324,299 |
| 主题菜单 | `webRemoteControl.themeMenu.trigger` | 4,320,123 |
| 首页任务行（workspace 形态） | `mobileHome.workspaceEmpty` 区 | 4,325,9xx |
| 首页任务行（timeline 形态） | `data-web-remote-task-row` | 4,463,050 |
| 时间线滚动容器 DOM 契约 | `data-v4-timeline-scroll` | 3,451,132 |
| live tail | `data-v4-running-live-tail` | 3,453,742 |
| composer dock | `data-v4-composer-dock` | 3,454,481 |
| composer 表单主体 | `data-composer-leading-actions` | 2,080,467 |
| 发送按钮 | `chat-send-button` | 2,088,069 |
| 用户气泡 | `data-v4-user-input-bubble` | 3,457,769 |
| 附件 pill | `data-v4-user-input-attachment-pill` | 3,343,643 |
| 思考块 | `data-reasoning-content` | 3,327,931 |
| 工具调用块 | `data-tool-call-id` | 3,329,849 |
| 子代理头像 | `data-subagent-avatar` | 2,942,828 |
| 产物 pill | `data-artifact-kind` | 2,987,394 |
| 加载指示 | `data-zcode-chat-loading-animate` | 2,763,001 |
| markdown 表格布局 | `data-markdown-table-layout-root` | 1,633,038 |
| streamdown 块 | `data-streamdown` | 1,632,588 |
| Button variant/size 表 | `variant:{input:` | 225,662 |
| Radix 原语 | `Symbol.for('radix-ui')` | 136,790 |
| cmdk | `[cmdk-group=""]` | 1,468,741 |
| 窄屏导航面板 | `data-web-remote-navigation-scroll` | 4,858,648 |
| 窄屏展开手柄 | `data-web-remote-navigation-resize-handle` | 5,601,7xx |
| 桌面设置面板 | `data-settings-panel-frame` | 6,153,996 |
| 桌面远控管理卡 / 二维码 | `webRemoteControl.mobileQr.title` | 4,557,888 |
| 桌面侧窗格浮层动作 | `data-v4-pane-actions` | 2,562,977 |
| 轨迹视图契约 | `data-trajectory-timeline` | 5,360,156 |
| 工作流运行行 | `data-workflow-run-row` | 3,064,855 |
| CSS：全局滚动条 | `::-webkit-scrollbar{width:14px` | `index-BD2BL4Cc.css` @423,394 |
| CSS：语义字号工具类 | `.text-ui-lg` | `index-BD2BL4Cc.css` @143,646 |
| CSS：`:root` 令牌块 | `:root{--color-highspeed-accent` | `index-BD2BL4Cc.css` @377,120 |
| CSS：不定进度条动画 | `tool-call-progress-indeterminate` | `index-BD2BL4Cc.css` @423,776 |

**交叉核对**（`E:\Zcode_harmony\Docs\`，基线为旧构建 `index-B-ilXaCQ.js`，仅作结构旁证）

- `15-remote-v4-网页架构与模块地图.md`：构建形态、主包/chunk 分工、锚点→文件扫描法
- `18-remote-v4-功能域-对话渲染与流式更新.md`：ops 判别联合、行类型枚举、虚拟列表与跟随判据、DOM 契约表
- `19-remote-v4-功能域-输入附件与文件预览.md`：Lexical、双套草稿键、384 KiB 分片协议、预览 chunk 清单
- `20-remote-v4-功能域-导航列表与全局外观.md`：URL 分派、两页外壳、六态文案、主题类与 i18n 键数
- `10-remote-v4-网页扩展面与可观测面穷尽盘点.md`：`window.zcode.log`、CustomEvent、localStorage 面

---

*盘点基线：remote v4 / 3.15.3（快照 2026-10-15）。偏移只对本快照成立；升级后请按 §10 的锚点重新定位。*
