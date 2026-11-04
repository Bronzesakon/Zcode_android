# 05 · 杂项行取证：artifact / subagent / hookInvocation / timelineMarker

> 取证对象：网页版 3.15.3 对话时间线（只读，不执行应用代码）。
> 数据源（绝对路径）：
> - `E:\Zcode_android\archive\pre-3.16.1-auth\Reference_web\3.15.3\assets\index-BGa3KiRe.js`
> - `E:\Zcode_android\archive\pre-3.16.1-auth\Reference_web\3.15.3\assets\validation-CqXZOLXO.js`（行 schema）
> - `E:\Zcode_android\archive\pre-3.16.1-auth\Reference_web\3.15.3\assets\IntlProvider-C-26GNLk.js`（中英文案）
> - `E:\Zcode_android\archive\pre-3.16.1-auth\Reference_web\3.15.3\assets\index-BD2BL4Cc.css`（设计 token / 动画）
> - `E:\Zcode_android\archive\pre-3.16.1-auth\Reference_web\3.15.3\assets\button-B-6dbTqe.js`（按钮变体）
> - `E:\Zcode_android\docs\remote-v4\18-remote-v4-功能域-对话渲染与流式更新.md`（对照）
>
> 偏移说明：下文 `文件:数字` 均为 **UTF-8 字节偏移**（不是字符下标），由 `Buffer.byteLength(prefix,'utf8')` 计算。

---

## 0. 结论先行

1. **artifact**：主时间线是一张**静态卡片**（圆角 `rounded-lg`、`bg-card`、`border-card-border`），图标**不按 artifactType 区分**，恒为 lucide `file`；副标题是 `ARTIFACTTYPE · {sizeBytes} bytes`（原始字节数、硬编码英文单位）；**没有点击行为、没有状态展示**。分享页另有一套 `rounded-xl` 卡片，按扩展名取 material icon，带“预览”按钮 `window.open`。
2. **subagent**：时间线里没有独立“折叠卡片”。能与 Agent 工具调用配对的 subagent 行会被**合并进该工具调用的 summary 行**（bot 图标 + “子智能体” + 彩色 agent 名 + 描述 + `·`），整行 `role=button`，点击打开**右侧 side-pane tab**；不能配对的 subagent 行退化为一行纯文本 `type · status — summaryText`。没有首字母头像，状态在折叠行里基本不可见。
3. **hookInvocation**：**根本不是时间线行**。它被 `PZ()` 从行流里摘出，归入 `turn.hookInvocations`，只在回合底部渲染一个 **icon-only 按钮**（anchor 图标，`sr-only` 文案“钩子”）；点击弹出 **Popover** 列表，逐条展示 `eventName + 来源 + 耗时 + 状态图标/文案`。不存在“行内展开”。
4. **timelineMarker**：居中标签，左右各一条 `1px × flex-1` 分隔线（`min-w-8`、`bg-border/50`），文字 `text-ui-base`/`leading-5`、`foreground-subtle`；schema 有 **9 个分支 / 8 个不同 type 字符串**，但 3.15.3 渲染器**只实现 4 个**（compact / forkNotice / modelChange / goalVerify），其余 5 个 `default: return null` 被丢弃。
5. **动画**：四种行**都没有行级进入/退出动画**。只有内部动画：marker 运行态渐变文字（4s 循环）、subagent summary 文字切换（300ms 上滑淡入淡出）、hook 按钮淡入（opacity transition）与 popover（100ms fade+zoom）、折叠容器（300ms 高度+淡入淡出）。
6. **turn 折叠**：`hookInvocation` 永远不在行流内；尾部 `artifact` 与 `lane=turnTailBoundary` 的 marker 被剥到 `tailRows` 常显；`lane=lightBoundary` 的 marker 进 `leadingBoundaryRows` 常显；`subagent`、中段 `artifact`、无 lane/`assistantWork` 的 marker 进 `assistantWorkRows`，**会被“已处理/已工作”折叠头隐藏**。

---

## 1. 行模型与分派

### 1.1 schema 位置（`validation-CqXZOLXO.js`）

| 行 | 证据 |
| --- | --- |
| artifact | `validation-CqXZOLXO.js:180305`：`kind:N('artifact')`，字段 `artifactVersionId / logicalArtifactKey / displayName / artifactType:['pdf','pptx','docx','xlsx','image','html','md','text'] / mimeType / sizeBytes / sha256 / ref / state:'current'` |
| subagent | `validation-CqXZOLXO.js:180595`：`parentToolCallId? / subagentType / status:['running','success','failed','cancelled'] / summaryText / childSessionId? / backgrounded? / workId? / startedAt? / endedAt?` |
| hookInvocation | `validation-CqXZOLXO.js:181644`；executions 元素 `gh` 在 `validation-CqXZOLXO.js:181190` |
| timelineMarker | `validation-CqXZOLXO.js:183011`；marker 联合 `vh` 在 `validation-CqXZOLXO.js:182073` |

marker 联合共 **9 个分支**（`validation-CqXZOLXO.js:182073`）：

```
compact | forkNotice | forkCreated | modelChange(必填 from) | modelChange(from 可选)
| goalSet | goalVerify | retryNotice | checkpointRestored
```

即 8 个不同 `type` 字符串，`modelChange` 占两个分支，所以文档 18 的“9 种 type（modelChange ×2）”成立。

### 1.2 主时间线行分派

`index-BGa3KiRe.js:3387358`（函数 `btn`，memo 为 `xtn`；`case` 分支从 `:3388187` 起）：

- `case 'timelineMarker' → _tn`
- `case 'subagent' → ytn`
- `case 'artifact' → ttn`
- `case 'turnHeader' → dtn`（但 turnHeader 行在归约时已被消费，见 §7）
- `default → null`（`hookInvocation` 落到这里，永远不渲染）

分享页另一套分派在 `index-BGa3KiRe.js:3480452` 附近（`ZZ`）：
`artifact → Din`、`timelineMarker` 内联、`subagent / hookInvocation / turnHeader → null`。

---

## 2. artifact

### 2.1 主时间线卡片（`ttn`）

证据：`index-BGa3KiRe.js:3354469`（组件定义）、`index-BGa3KiRe.js:3354985`（类型/大小行）、`index-BGa3KiRe.js:3388440`（分派）。

DOM / 类名（逐层）：

```
div[data-row-id][data-testid]            ← TZ，className="px-4 py-1"
  div.flex.items-center.gap-2.rounded-lg.border.border-card-border.bg-card.px-3.py-2
    svg(file, size-4, shrink-0, text-foreground-subtle, aria-hidden)
    div.min-w-0.flex-1
      p.truncate.text-ui-base.text-foreground            → displayName
      p.text-ui-sm.text-foreground-subtle                → `${artifactType.toUpperCase()} · ${sizeBytes} bytes`
```

- **不是 pill**，是卡片；圆角 `rounded-lg`（8px），内边距 `px-3 py-2`（12/8px），图标与文字 `gap-2`（8px），外层行 `px-4 py-1`。
- **图标不按 artifactType 切换**：恒为 `kc`，即 lucide **file**（`index-BGa3KiRe.js:98796`：`kc=To('file', …)`），`size-4`、`text-foreground-subtle`。
- **文件名**：`displayName`，单行 `truncate`，`text-ui-base`（14px）、`text-foreground`。
- **类型与大小**：`artifactType.toUpperCase()`（无 i18n，例如 `PDF`/`PPTX`/`IMAGE`）+ ` · ` + **原始 `sizeBytes` + 硬编码字符串 ` bytes`**（`index-BGa3KiRe.js:3354985`）。注意：这里**没有** KB/MB 换算，也不是 `QB()`。
- **状态**：schema 只有 `state:'current'`（`validation-CqXZOLXO.js:180305` 同段），组件完全不读 `state`，**无状态徽标/颜色/禁用态**。
- **点击**：无 `onClick`、无 `<a>`、无 `role=button`、无 hover 类；**纯静态**。
- **DOM 锚点**：只有 `data-row-id`、`data-testid`（`TZ` 定义在 `index-BGa3KiRe.js:3354322`），没有 `data-row-kind`。

### 2.2 分享页卡片（`Din`）

证据：`index-BGa3KiRe.js:3480452`（组件）、`index-BGa3KiRe.js:3480229`（类型标签表 `Ein`）、`index-BGa3KiRe.js:3481779`（预览按钮）。

```
div[data-conversation-share-row-kind="artifact"].py-1
  div.flex.w-full.items-center.gap-3.rounded-xl.border.border-card-border.bg-card.p-3.pr-4.text-foreground
    div.flex.size-11.shrink-0.items-center.justify-center.rounded-md.bg-background.text-foreground-subtle
      img[width=24][height=24]                       ← 按扩展名的 material icon
    div.flex.min-w-0.flex-1.flex-col.gap-1
      p.truncate.text-ui-base.font-medium.leading-5   → displayName
      p.truncate.text-ui-base.leading-5.text-foreground-subtlest → `${label} · ${QB(sizeBytes)}`
    [右侧]
      div[data-share-preview-button].flex.h-7...rounded-lg.border.border-border.bg-input
        button.flex.h-7.items-center.gap-1.px-2.text-ui-sm.text-foreground
```

- **图标按扩展名**：`vg(displayName)` → `tMe()` → `$je` 扩展名表（`index-BGa3KiRe.js:671361`）→ `oMe()` 拼 `/remote/v4/3.15.3/material-icons/<name>.svg`（`index-BGa3KiRe.js:673068`）。映射：`pdf→pdf`、`pptx→powerpoint`、`docx→word`、`xlsx→table`、`png/jpg/jpeg→image`、`html→html`、`md→markdown`、`txt→document`；无扩展名 → `document`。图标容器 44×44（`size-11`），图片 24×24；加载失败回退内联 SVG 或 `document.svg`。
- **类型标签**：`Ein`（`index-BGa3KiRe.js:3480229`）只覆盖 `pdf/docx/xlsx/pptx/md/text/html`；**`image` 不在表里**，会回退成 `artifactType.toUpperCase()` = `IMAGE`。对应文案（`IntlProvider-C-26GNLk.js:127693` 起）：`网站 · HTML`、`文档 · MD`、`文本 · TXT`、`文档 · DOCX`、`表格 · XLSX`、`演示文稿 · PPTX`、`文档 · PDF`。
- **大小**：`QB()`（`index-BGa3KiRe.js:2102507`）：`>=1MB` → `x MB`（`>=10` 取 0 位小数，否则 1 位）；否则 `ceil(bytes/1024) KB`。
- **点击**：有 `url` 时只渲染“预览”按钮，`onClick=()=>window.open(url,'_blank','noopener,noreferrer')`（`index-BGa3KiRe.js:3481779` 附近）；没有 URL 但能解析出工作区相对路径时，渲染 `OpenSplitButton`（`a`，target `{type:'file',path,title,label,previewSource}`），可走本机“打开/用其他应用打开”。**没有下载按钮**。
- 该卡片只在**会话分享页**使用；主应用时间线不会走 `Din`。

### 2.3 与 artifact 相关但不同的面

主应用里另有一组“预览卡片”（assistant preview cards，`Sen/Cen`，`index-BGa3KiRe.js:3336438` 附近），按扩展名显示图标、标题、副标题，并带 `OpenSplitButton`；它们由工具输出推导，不是 `artifact` 行。不要与 `ttn` 混淆。

---

## 3. subagent

### 3.1 两条渲染路径

**A. 合并进 Agent 工具行（常见路径）**

`B8t` 会把 `subagent` 行与对应 agent 工具调用配对（`index-BGa3KiRe.js:3189648` 的 `H8t`；配对表由 `z8t` 生成）。渲染：

```
div[data-row-id][data-conversation-selectable][data-testid]
  ToolCallBlock(agent)  ← 最终 bZt
```

`bZt`（`index-BGa3KiRe.js:2941743`）传给 `Kb/ToolLayout` 的参数：

- `icon = hZt`：`hZt=(0,$.jsx)(Ys,{className:'size-4 shrink-0 text-foreground-subtle'})`，`Ys=To('bot')`（`index-BGa3KiRe.js:2938322` / `:85288`）→ **lucide bot 图标**，不是首字母。
- `kindLabel = '子智能体'`（`chat.toolCall.agent.fallback`，`IntlProvider-C-26GNLk.js:353551`）。
- `kindDetail = vZt({color,name})`：agent 类型名，`font-mono text-ui-base font-medium leading-[1.5] max-w-36 truncate`，颜色取 `DXt` 调色板（sky/cyan/emerald/orange/pink/violet/rose/amber，浅色 700、深色 300；`index-BGa3KiRe.js:2939950` 与 `index-BGa3KiRe.js:2913779`）。没有 agent 名时该段为空。
- `primaryText`：运行中显示**最新子工具动作摘要**（`eZt → $Xt`，如“读取 …”“运行 …”），非运行显示 agent 描述（`fZt`，优先 `title` → `input.description` → `subagent_type` → 输出 summary）。渲染为 `<span class="truncate">`，**单行省略**；完整内容在原生 `title` tooltip。
- `summaryContentSeparator = '·'`，`animateSummaryContent = true`，`canToggle = false`，`summaryAction = {onActivate, testId, ariaLabel:'在右侧打开'}`。
- 折叠行结构（`Kb` 的 summary row，`index-BGa3KiRe.js:873537` / `:872203` / `:872896`）：
  `[bot icon] [子智能体] [彩色 agent 名] · [摘要文本]`，外层 `inline-flex max-w-full items-center gap-2 self-start text-ui-base`。
  `canToggle=false` 且 `summaryAction` 存在 → 走 action 分支：`div role=button tabindex=0 cursor-pointer`，**没有 chevron**（chevron 只在可折叠且无 action 时出现）。

**B. 不能配对的退化行（`ytn`）**

`index-BGa3KiRe.js:3387073`：

```
div[data-row-id][data-testid]
  div.text-ui-sm.text-[var(--color-foreground-subtle)]
    {subagentType} · {status}{summaryText ? ` — ${summaryText}` : ''}
```

- 纯文本、无图标、无折叠、无点击、无截断（会自然换行）；`TZ` 未传 `className`，因此**行本身没有内边距**，只靠父级 `flex-col gap-4` 与其他行分隔。
- 这是唯一把 `status` 直接写出来的时间线位置。

### 3.2 状态（running / success / failed / cancelled）

- schema：`status:['running','success','failed','cancelled']`（`validation-CqXZOLXO.js:180595`）。
- **合并行不显示状态文字**：`bZt` 没有传 `statusLabel`，`showFailureStatus` 虽然按 `toolCall.status==='failed'` 设置，但 `Kb` 只有在 `statusLabel != null` 时才渲染失败徽标（`index-BGa3KiRe.js:875949` 的 `G=(k||A)&&E!=null`）→ 失败 subagent 的折叠行**没有可见失败标记**。
- 运行态唯一视觉：`isRunning` 为真时 kindLabel 加 `animated-gradient-text`（`index-BGa3KiRe.js:875949` 的 `me`）；图标、布局不变。
- `status` 参与“是否在流”判定：`Knn` 中 `subagent → backgrounded!==true && status==='running'`（`index-BGa3KiRe.js:3431382`）。
- 状态文案只在退化行（A/B 的 B）与右侧背景任务面板出现；时间线折叠行不出现。

### 3.3 点击 → 子页面（右侧 side pane tab）

- 整行点击：`summaryAction.onActivate` → `V8t`（`index-BGa3KiRe.js:3189399`）→ `onOpenSubagentSession({rootSessionId, parentSessionId: sessionId, childSessionId, subagentType, title})`；遥测 `featureId:'conversation.subagent', action:'open_side_pane'`。
- 仅当 `childSessionId && sessionId && onOpenSubagentSession` 三者都存在时可点（`index-BGa3KiRe.js:3189648` 的 `o=!!(r&&t.sessionId&&t.onOpenSubagentSession)`）；否则仍是 summary 行但无 action。
- **tab 模型**：`aKe()` 生成 id `subagent-session:<workspaceKey>:<rootSessionId|parentSessionId>:<childSessionId>`（`index-BGa3KiRe.js:906338`）。
- **标题**：可见 tab 标题 `title.trim() || '子智能体'`（`index-BGa3KiRe.js:5531758` 的 `l8`）；tooltip/aria 用 `subagentType.trim() || '子智能体'`（`index-BGa3KiRe.js:5536120`）。`sidePane.subagent = 子智能体`（`IntlProvider-C-26GNLk.js:65672`）。
- **tab 条外观**（`index-BGa3KiRe.js:5525098` 的 `qRn`）：
  - 外层 `h-7`、`rounded-md`、`px-1.5 pr-2`、`text-ui-base font-medium`；
  - 非激活：`text-foreground-subtle`，hover `text-foreground` + `bg-hover`；激活：`bg-selected text-foreground`；
  - 内容 `YRn`（`index-BGa3KiRe.js:5527888`）：图标容器 `size-4`，图标 `size-3.5`，标题单行、右侧渐隐 mask；
  - 关闭按钮 `x`（lucide `x`，`size-3`）绝对定位右上，**仅激活或 hover/focus-within 时可见**；中键关闭。
  - 整个 tab 条：`h-12`、`border-b border-border/50`（`index-BGa3KiRe.js:5663632`）。
- **tab 内容**：`ozn`（`index-BGa3KiRe.js:5540752`）→ 同一套会话视图 `AQ`（`index-BGa3KiRe.js:3617379`），参数 `readOnly:!0, openTrigger:'subagent'`。
  - **没有“返回”按钮**；关闭 tab（X）即返回。
  - `readOnly` 为真时 **composer 不渲染**：`Sa = i ? null : <Composer…>`（`index-BGa3KiRe.js:3676958`）。
  - 面板顶部有浮层操作区 `Vzt/Hzt`（`index-BGa3KiRe.js:2580359` / `:2581703`）：`sr-only` 标题、可选工作区徽标（`h-7`、`rounded-md`、`border`、`bg-popover`、`font-mono`）、可选关闭窗格按钮（`icon-md`、`bg-popover shadow-md`）。
  - **消息流与工具行**：复用主时间线的行模型 `PZ()` 与同一批行渲染器（assistantText / reasoning / toolCall / artifact / timelineMarker），因此工具行外观与主对话一致；subagent 子页里的 `subagent`/`hookInvocation` 行为同 §7。
  - 另有兄弟 tab 类型 `subagent-directory`（“子智能体目录”），不是本行点击的落点。

### 3.4 “首字母”

3.15.3 时间线**不使用首字母头像**。subagent 相关 UI 的图标只有两种：
- 时间线/侧栏：lucide `bot`（`index-BGa3KiRe.js:85288`）；
- 目录 tab：lucide 目录图标（`$c`，`index-BGa3KiRe.js:5528907` 的 `o8`）。
名字用彩色等宽文本表示身份，不用首字母。

---

## 4. hookInvocation

### 4.1 它不是行

- 归约 `PZ()`（`index-BGa3KiRe.js:3435018`）里：
  `if (Rnn(t)) { e.hookInvocations.push(t); continue }`（`Rnn = kind==='hookInvocation'`）。
- `renderRows` 过滤 `Bnn`（`index-BGa3KiRe.js:3430886`）：`userInput` 保留，`turnHeader`/`hookInvocation` 剔除。
- 因此主时间线**没有 hookInvocation 行**，也没有 `data-row-id`。

### 4.2 回合底部的入口按钮

证据：`index-BGa3KiRe.js:3351945`（`Yen`）、`index-BGa3KiRe.js:3409196`（显示门控）、`index-BGa3KiRe.js:3413481`（按钮容器透明度）。

- 显示条件：`!timelineOnly && !isRunning && hookInvocations.some(h => h.executions.some(x => x.didExecute))`（`index-BGa3KiRe.js:3409196`）。**没有执行过任何 hook（didExecute=false）就完全不显示**；`hookCount` 字段不参与 UI。
- 按钮：`Button variant='ghost' size='icon-sm'` → `size-6`、图标 `size-3`（`button-B-6dbTqe.js:4796`），实际图标类 `size-3.5`。
- **图标**：lucide **anchor**（`Fs=To('anchor')`，`index-BGa3KiRe.js:80929`），`className='size-3.5'`。
- **文案**：没有可见文字，只有 `<span class="sr-only">钩子</span>` 和 `aria-label`；`chat.hooks.label = 钩子`（`IntlProvider-C-26GNLk.js:289483`）。
- 可见性：`opacity-0 transition-opacity group-hover/assistant-turn:opacity-100 focus-within:opacity-100 has-[[data-bot-group-delivery]]:opacity-100`；远程精简模式 `compactForRemoteControl` 时恒 `opacity-100`（`index-BGa3KiRe.js:3413481`）。
- hover：ghost 变体 `hover:bg-hover hover:text-foreground`，`aria-expanded:bg-hover`（`button-B-6dbTqe.js:3491`）。

### 4.3 点击展开 → Popover（不是行内展开）

证据：`index-BGa3KiRe.js:3351945` 的 `Yen` 返回体；Popover 参数在 `index-BGa3KiRe.js:3351945` 之后（`side:'top', align:'start', sideOffset:6, collisionPadding:16, sticky:'always'`）。

- 结构：
  ```
  PopoverContent
    class: w-80 max-w-[calc(100vw-2rem)] gap-0 overflow-hidden border-popover-border p-0
    style: maxHeight: min(20rem, var(--radix-popover-content-available-height))
    div.shrink-0.border-b.border-border.px-3.py-2
      h3.text-ui-base.font-medium.text-foreground  → 钩子
    ul.min-h-0.overflow-y-auto.py-0.5
      li × N
  ```
- 触发：点击 anchor 按钮；`onOpenAutoFocus` 被阻止（不抢焦点）。
- 无空态：`Wen()` 过滤后长度为 0 时整个按钮返回 `null`（`index-BGa3KiRe.js:3349740`）。

### 4.4 executions 的逐条展示

`Wen()`（`index-BGa3KiRe.js:3349740`）：把每个 hookInvocation 的 `executions` 按 `hookIndex` 升序排序，**只保留 `didExecute===true`**，拼成：

```
{ key, blockReason?, durationMs?(state!=='running'), eventName, sourceKind, status }
```

`Jen()` 单条渲染（`index-BGa3KiRe.js:3350993`）：

```
li.min-w-0.px-3.py-1.5.text-ui-sm
  div.flex.min-w-0.items-center.gap-2
    span.min-w-0.flex-1.break-words.font-mono.text-foreground   → hookEventName（原始英文枚举）
    div.flex.min-w-0.flex-wrap.items-center.justify-end.gap-2.text-ui-xs.text-foreground-subtle
      span                                                       → 来源：用户 / 工作区 / 插件
      span.text-foreground-subtlest                              → 耗时
      span.inline-flex.min-w-0.items-center.gap-1                → [状态图标] + 状态文案
        span.text-warning                                        → '：' + blockReason（仅 blocked）
```

- 一行文案模板（视觉顺序）：`{hookEventName} … {source} {duration} {statusIcon}{statusLabel}[：{blockReason}]`。
- **来源**：`chat.hooks.source.user=用户`、`project=工作区`、`plugin=插件`（`IntlProvider-C-26GNLk.js:289511` 起）。
- **耗时**：`Gen()`（`index-BGa3KiRe.js:3350125`）：`<1000ms → "{整数}ms"`；否则秒，`<10000` 保留 2 位小数，否则 1 位。
- **状态判定**：`Ken()`（`index-BGa3KiRe.js:3350228`）：`state==='running' → running`；`outcome==='timed_out' → timedOut`；`cancelled → cancelled`；`blocked → blocked`；`state==='failed' || outcome==='failed' → failed`；**其余（completed/success）→ null**。
- **状态颜色/图标**（`qen`，`index-BGa3KiRe.js:3350443`）：
  | status | 图标 | 颜色 |
  | --- | --- | --- |
  | running | loader-circle（`animate-spin`，`motion-reduce:animate-none`） | `text-foreground-subtle` |
  | blocked | shield-alert | `text-warning` |
  | timedOut | clock-alert | `text-warning` |
  | cancelled | circle-slash-2 | `text-foreground-subtle` |
  | failed | circle-x | `text-destructive` |
  | completed | **不渲染**（Ken 返回 null） | — |
- 状态文案：`chat.hooks.state.running=运行中`、`completed=已完成`、`blocked=已阻止`、`failed=失败`、`cancelled=已取消`、`timedOut=已超时`（`IntlProvider-C-26GNLk.js:289621` 起）。注意 `已完成` 实际不会出现（Ken 对 completed 返回 null）。
- `pluginName`、`toolName`、`displayName`、`hookCount` 在当前列表项里**未使用**。

---

## 5. timelineMarker

### 5.1 居中标签结构与样式

证据：`index-BGa3KiRe.js:3380615`（`gtn`）。

```
div/button[data-row-id][data-row-kind="timelineMarker"][data-marker-type][data-status]
          [data-origin][data-source-command-id][data-testid]
  class: flex w-full items-center gap-3 px-4 py-2
         text-ui-base text-[var(--color-foreground-subtle)]
  ├─ div[aria-hidden] class="h-px min-w-8 flex-1 bg-border/50"
  ├─ span class="inline-flex min-w-0 shrink items-center justify-center gap-1.5 text-center leading-5"
  │    ├─ icon (size-3.5 shrink-0 text-[var(--color-foreground-subtle)])   ← 运行态不渲染
  │    └─ span class="min-w-0 break-words"
  │         + running: " animated-gradient-text font-medium"
  │         + clickable: " underline-offset-4 group-hover/marker:underline"
  └─ div[aria-hidden] class="h-px min-w-8 flex-1 bg-border/50"
```

精确数值：
- 行高由内容决定：`py-2`（上下各 8px），左右 `px-4`（16px）；三段 `gap-3`（12px）。
- 分隔线：高 `1px`、`flex-1`、最小宽 `min-w-8`（32px）、颜色 `bg-border/50`。浅色主题 `--color-border:#0a0a0a1a`（`index-BD2BL4Cc.css:12844`），50% 后约 `rgba(10,10,10,0.05)`；深色 `#fafafa1a`（`index-BD2BL4Cc.css:399277`），50% 后约 `rgba(250,250,250,0.05)`。
- 文字：`text-ui-base` = 14px（`--ui-font-size:14px`，`index-BD2BL4Cc.css:396932`；`--text-ui-base` 定义 `index-BD2BL4Cc.css:11189`），`leading-5` = 20px，颜色 `foreground-subtle`：浅色 `#40404099`（`index-BD2BL4Cc.css:18159`），深色 `#e5e5e599`（`index-BD2BL4Cc.css:405903`）。
- 图标：`size-3.5`（14px），同 `foreground-subtle`。
- 可点击时：整行变 `<button type=button>`，加 `group/marker text-left transition-colors hover:text-[var(--color-foreground)]`，标签 hover 下划线（`underline-offset-4`）。不可点击时是 `<div>`。
- 运行态：图标隐藏，标签加 `animated-gradient-text font-medium`；该 class 见 `index-BD2BL4Cc.css:341027`（`background-size:300% 100%`、文字透明、`animation:4s linear infinite gradient-flow`；关键帧 `index-BD2BL4Cc.css:342695`；深色变量 `#fff / #fff3`）。
- `data-status` 取值：`compact → marker.status`；`goalVerify → marker.outcome`；`forkNotice → 'created'`；`modelChange → 'applied'`（`index-BGa3KiRe.js:3381889` 附近）。

### 5.2 9 个 schema 分支的文案与图标

渲染器 `_tn`（`index-BGa3KiRe.js:3381889`）的 `switch(marker.type)`，`default: return null`。

| # | `marker.type` | 图标 | 文案（zh-CN） | 3.15.3 是否渲染 |
| --- | --- | --- | --- | --- |
| 1 | `compact` | lucide **archive**（`Rs`，`index-BGa3KiRe.js:81572`） | 见下 6 种状态 | ✅ |
| 2 | `forkNotice` | lucide **git-branch**（`Fc`，`index-BGa3KiRe.js:100833`） | `从对话中派生`（`IntlProvider-C-26GNLk.js:289137`） | ✅ |
| 3 | `forkCreated` | — | — | ❌ `null` |
| 4 | `modelChange`（from 必填） | lucide **arrow-right-left**（`Voe`，`index-BGa3KiRe.js:82287`） | `模型已切换 {from} → {to}`（`IntlProvider-C-26GNLk.js:296741`） | ✅ |
| 5 | `modelChange`（from 可选、缺省） | **无图标**（`icon:null`） | `正在使用 {model}`（`IntlProvider-C-26GNLk.js:296803`） | ✅ |
| 6 | `goalSet` | — | — | ❌ `null` |
| 7 | `goalVerify` | lucide **goal**（`zc`，`index-BGa3KiRe.js:102046`） | `第 {count} 次迭代 · {checking|complete|incomplete}` | ✅ |
| 8 | `retryNotice` | — | — | ❌ `null` |
| 9 | `checkpointRestored` | — | — | ❌ `null` |

`compact` 的 6 种状态文案（`index-BGa3KiRe.js:3381889` 起）：

| 条件 | i18n id | zh-CN |
| --- | --- | --- |
| `status==='running'`（普通） | `chat.contextCompaction.started` | 正在压缩上下文 |
| `status==='noop'` | `chat.contextCompaction.skipped` | 上下文已是最新，无需压缩 |
| `status==='cancelled'` | `chat.contextCompaction.interrupted` | 上下文压缩已中断 |
| `status==='failed'` | `chat.contextCompaction.failed` | 上下文压缩失败 |
| `success && origin==='auto'`（非运行） | `chat.contextCompaction.completedAuto` | 上下文已自动压缩 |
| 其余 `success` | `chat.contextCompaction.completed` | 上下文已压缩 |

特例：`r = TO()`（办公模式）且 `origin==='auto'` 且运行中时，文案前缀切到 `chat.contextOptimization.*`：`正在优化对话 / 无需优化对话 / 已停止优化对话 / 优化对话失败 / 已优化对话`（`IntlProvider-C-26GNLk.js:130006` 起）。

`goalVerify` 的文案：先 `chat.summaryPanel.goalIterationValue` = `第 {count} 次迭代`（`IntlProvider-C-26GNLk.js:299191`），再 ` · `，再：
- `outcome==='running'` → `chat.goalVerification.checking` = 目标校验中
- `outcome==='pass'` → `chat.goalVerification.complete` = 目标已完成，任务结束
- 其他 → `chat.goalVerification.incomplete` = 目标未完成，任务继续

（`IntlProvider-C-26GNLk.js:297077 / :297196 / :297128`）

### 5.3 点击

- 只有 `forkNotice` 且上下文提供 `onNavigateToRow` 时才可点（`index-BGa3KiRe.js:3381889` 的 `c`）：`onClick = () => onNavigateToRow(marker.parentSessionId, marker.parentRowId)`，跳到派生来源行。
- 其他 marker 一律无点击。
- `compact` 的 `sourceCommandId` 另有副作用：撤销/清理命令条目时使用（`index-BGa3KiRe.js:2649677`，`timelineMarker && marker.type==='compact' && sourceCommandId`），与 UI 点击无关。

### 5.4 分享页的内联 marker

`index-BGa3KiRe.js:3480452` 之后的 `ZZ`：只有 `compact(status==='success')` 和 `modelChange` 会返回文本（`hin`，`index-BGa3KiRe.js:3475435`），其余返回 null。DOM 为 `div[data-conversation-share-row-kind="timelineMarker"][data-marker-type]`，类名同主时间线（`px-4 py-2 gap-3 text-ui-base text-foreground-subtle`），但**没有运行态、没有图标、没有点击**。

---

## 6. 进入/退出动画、hover、点击反馈

### 6.1 行级进入/退出

- 四种行**都没有** mount/unmount 动画类：`ttn`/`ytn`/`gtn`/`TZ` 上均无 `animate-in`、`fade-in`、`data-zcode-stream-animate` 等。
- 唯一的“流式入场”标记 `data-zcode-stream-animate` 在 `index-BGa3KiRe.js:3336438`，属于 **assistant preview cards**，不属于这四种行。
- 行被折叠/删除时也没有 exit 动画（无 `AnimatePresence` 包裹行列表）。

### 6.2 内部动画

| 对象 | 动画 | 证据 |
| --- | --- | --- |
| marker 运行态文字 | 4s 线性无限渐变位移；`gradient-flow`：`0% → 100% 0`，`50% → 0 0`，`100% → 0 0` | `index-BD2BL4Cc.css:341027`、`:342695` |
| subagent 摘要文字切换 | framer-motion `AnimatePresence mode="popLayout"`；进入 `{y:'0.8em', opacity:0} → {y:0, opacity:1}`；退出 `{y:'-0.8em', opacity:0}`；`0.3s cubic-bezier(.4,0,.2,1)`；队列最多 2 条、800ms 节流；`prefers-reduced-motion` 时禁用 | `index-BGa3KiRe.js:870478`（`DWe`）、`index-BGa3KiRe.js:869718`（常量 `vWe=800, yWe=250, bWe=2, xWe={duration:0.3,ease:[.4,0,.2,1]}`） |
| hook 按钮出现 | `transition-opacity`（默认 150ms）从 `opacity-0` 到 hover/focus 时 `opacity-100` | `index-BGa3KiRe.js:3413481`、`index-BD2BL4Cc.css:10917` |
| hook Popover | Radix `data-open:animate-in fade-in-0 zoom-in-95` / `data-closed:animate-out fade-out-0 zoom-out-95`，`duration-100` | `index-BGa3KiRe.js:713840`（popover 基类） |
| 折叠容器（历史/工具展开） | `animate-collapsible-down/up`，`duration-300 ease-in-out`，内容同时 `fade-in-0/fade-out-0` | `index-BGa3KiRe.js:736397` |

### 6.3 hover

| 行 | hover 效果 |
| --- | --- |
| artifact（主） | **无**（无任何 hover 类） |
| artifact（分享） | 仅预览按钮容器 `hover:border-border-hover`（`transition-all`） |
| subagent（合并行） | 仅 `cursor-pointer`；无背景/文字变化；原生 `title` tooltip 显示完整描述 |
| subagent（退化行） | 无 |
| hook 按钮 | ghost：`hover:bg-hover hover:text-foreground`；`aria-expanded:bg-hover` |
| hook 列表项 | 无 hover（静态 `li`） |
| timelineMarker（可点击） | 整行文字 `foreground-subtle → foreground`；标签出现下划线（`underline-offset-4`） |
| timelineMarker（不可点击） | 无 |

### 6.4 点击反馈

- 都没有 `active:`/scale/ripple 之类的按压反馈。
- artifact 主卡片：点击无任何事发生。
- subagent 合并行：按下即打开右侧 tab（`role=button` + `tabIndex=0`，Enter/Space 同效，`OWe` 处理，`index-BGa3KiRe.js:873537` 附近）。
- hook：点击打开/关闭 Popover（`aria-expanded` 同步）。
- marker（forkNotice）：点击立即跳转；`transition-colors` 150ms。

---

## 7. 是否参与 turn 折叠

### 7.1 归约规则

`PZ()`（`index-BGa3KiRe.js:3435018`）：

```
turnHeader      → turn.header（不进入行流）
userInput       → turn.userInputs
hookInvocation  → turn.hookInvocations（不进入行流）
其余            → turn.assistantWorkRows
```

`Ynn()`（`index-BGa3KiRe.js:3432120`）：

- `Gnn`（`lane==='lightBoundary'`）→ `leadingBoundaryRows`，在回合最前面直接渲染（`index-BGa3KiRe.js:3411600`）。
- `Unn()`（`index-BGa3KiRe.js:3431064`）：从 `assistantWorkRows` 尾部连续剥离 `lane==='turnTailBoundary'` 的 marker 与 `artifact` → `tailRows`。
- `tailRows` 在折叠容器**之后**渲染，始终可见（`index-BGa3KiRe.js:3414396`）。
- 剩余行 → `flowRows` → 进入 `workSegments` 的历史折叠区（`Itn`，`index-BGa3KiRe.js:3398577`）。

### 7.2 逐行结论

| 行 | 是否被折叠 | 说明 |
| --- | --- | --- |
| `hookInvocation` | **不参与** | 不在行流内；只以回合底部按钮出现，且该按钮在回合运行中/无 didExecute 时隐藏。 |
| `artifact` | **取决于位置** | 位于回合**尾部**（其后只有 turnTailBoundary/artifact）→ `tailRows`，常显；位于回合**中段** → 进入历史折叠区，会被隐藏。 |
| `subagent` | **参与** | 属于 `assistantWorkRows`，折叠时隐藏；运行中的 subagent 会让 `isRunning` 为真，从而强制展开历史（`Qnn`，`index-BGa3KiRe.js:3434167`）。 |
| `timelineMarker` | **取决于 lane** | `lane='lightBoundary'` → 回合前常显；`lane='turnTailBoundary'` 且在尾部 → 常显；无 lane / `assistantWork` → 参与折叠。 |

补充：
- `turnHeader` 本身**从不作为行渲染**（`PZ` 已消费；主分派里的 `dtn` 实际不可达）。
- 整回合若只有 marker 而没有 userInput（`timelineOnly`），不进入折叠逻辑（`Ynn` 的 `l` 分支）。
- 折叠头（`Itn`，`index-BGa3KiRe.js:3397534` 附近）：
  - `border-b`（高速模式 `border-icon-purple/30`），文字 `text-ui-base text-foreground-subtle`；
  - 文案：`已处理` / `已工作 {duration}` / `正在工作 {duration}` / `已停止` / `已被新消息中断`（`IntlProvider-C-26GNLk.js:290935` 起、`:222844`、`:223037`、`:223079`）；
  - 右侧 chevron `size-4`，展开时 `rotate-90`，`transition-transform`；默认展开时**不显示** chevron（`index-BGa3KiRe.js:3397901`）。
- 默认展开条件（`Qnn`，`index-BGa3KiRe.js:3434167`）：回合被中断、最后回合正在运行、或只有一个 work segment 且尚无 assistant 正文。其余情况默认折叠，用户可点开。

---

## 8. 与文档 18 的对照与修正

文档 18 第 357–367 行给出的结论基本正确，但有两处需要按本版实际代码修正：

1. **“渲染分派（两处，均非主时间线）”表述不准确。**
   主时间线确有独立分派 `btn`（`index-BGa3KiRe.js:3388187` 起），并且**会渲染** `artifact`（`ttn`）、`subagent`（`ytn`）、`timelineMarker`（`_tn`）。文档描述的是分享页 `ZZ` 的分派。
2. **`subagent` / `hookInvocation` / `turnHeader` 的“return null”只适用于分享页。**
   主时间线里：`subagent` 有退化行/合并行；`hookInvocation` 根本不进 `renderRows`；`turnHeader` 在归约阶段已被消费。

另外补一条文档未写的关键事实：**9 个 marker schema 分支中只有 4 个有 UI**，`forkCreated`、`goalSet`、`retryNotice`、`checkpointRestored` 在 3.15.3 前端会被静默丢弃（`default:return null`，`index-BGa3KiRe.js:3381889`）。如果服务端下发这些 marker，壳侧**不应期待任何 DOM**。

---

## 9. 壳实现清单（可直接照抄的数值）

### 9.1 artifact（主时间线）

| 项 | 值 |
| --- | --- |
| 行外边距 | `px-4 py-1`（16 / 4px） |
| 卡片 | `rounded-lg`、`border 1px var(--color-card-border)`、`bg-card`、`px-3 py-2` |
| 图标 | lucide `file`，16×16，`foreground-subtle` |
| 主文字 | `text-ui-base`(14px)、`foreground`、单行省略 |
| 副文字 | `text-ui-sm`(12px)、`foreground-subtle`、`TYPE · {n} bytes` |
| 交互 | 无 |
| 状态 | 无 |

### 9.2 subagent（合并行）

| 项 | 值 |
| --- | --- |
| 结构 | bot(16) + “子智能体” + 彩色 mono agent 名（max 144px 省略） + `·` + 摘要（单行省略） |
| 运行态 | kindLabel 渐变文字；无 spinner |
| 失败态 | 折叠行无可见标记（需依赖右侧面板/详情） |
| 点击 | 整行，`aria-label=在右侧打开`，打开 side pane tab |
| 子页 | tab 标题 = title/子智能体；无返回键；无 composer；同一消息流与工具行 |

### 9.3 hookInvocation

| 项 | 值 |
| --- | --- |
| 位置 | 回合底部操作区，不是行 |
| 入口 | anchor 图标 24×24 按钮（图标 14px），`sr-only` “钩子”，hover 淡入 |
| 显示条件 | 回合结束 + 至少一条 `didExecute` |
| 展开 | Popover，320px 宽，最大高 `min(20rem, 可用高度)`，顶部标题 + 可滚动列表 |
| 列表行 | `eventName`（mono）+ 来源 + 耗时 + 状态图标/文案；仅 didExecute |
| 状态色 | running 灰、blocked/timedOut 黄、cancelled 灰、failed 红、completed 无 |

### 9.4 timelineMarker

| 项 | 值 |
| --- | --- |
| 行 | `flex items-center gap-3 px-4 py-2`，14px/20px，`foreground-subtle` |
| 分隔线 | 1px、`flex-1`、`min-width:32px`、`border 颜色 50%` |
| 图标 | 14px；运行态隐藏 |
| 运行态 | 4s 渐变文字 |
| 可点击 | 仅 forkNotice；hover 变 `foreground` + 下划线 |
| 已实现 type | compact(archive) / forkNotice(git-branch) / modelChange(arrow-right-left 或无色) / goalVerify(goal) |
| 未实现 type | forkCreated / goalSet / retryNotice / checkpointRestored → 不渲染 |

---

## 10. 未验证 / 边界

- 服务端是否真的会下发 `forkCreated`/`goalSet`/`retryNotice`/`checkpointRestored`：schema 允许，但 3.15.3 前端无 UI；本次未抓取真实会话验证其出现频率。
- 原生 `title` tooltip 的呈现完全由浏览器决定，无法从 CSS 取证。
- 主时间线 artifact 卡片在“分享选择模式”下是否有额外覆盖层：`ttn` 本身无选择相关属性；分享选择逻辑作用于 `data-conversation-selectable` 行，而 `ttn` 未设置该属性（`index-BGa3KiRe.js:3354469`），因此按代码推断**不可被勾选**；未在真实 UI 中点击验证。
- `--color-warning` / `--color-destructive` 的具体色值随主题（浅色 yellow-600/red-600，深色 yellow-500/red-500；zai 主题另有 `#e07b00`/`#e03131` 与 `#ff8a30`/`#ff5c5c`），`index-BD2BL4Cc.css:18923` / `:18819` / `:415360` / `:421937`。
