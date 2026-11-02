# 工具调用行（toolCall）一比一取证 — Web 3.15.3

> **基线**：`E:\Zcode_android\archive\pre-3.16.1-auth\Reference_web\3.15.3\`
> `assets/index-BGa3KiRe.js`（6,592,643 B）
> `assets/index-BD2BL4Cc.css`（455,351 B）
> 辅助源（仅用于文案/家族表，文中逐条标注）：`assets/IntlProvider-C-26GNLk.js`、`assets/src-AL-_ONNp.js`
>
> **偏移口径**：UTF-8 **字节**偏移（`grep -o -b` / `Buffer.slice`，`node -e` 可复现）。
> **方法**：只读字符串切片 + 组件定义逐行阅读；**未执行应用代码**。
> **审计日期**：2026-10-17。本文只描述**网页版 3.15.3** 的事实；「不存在」的项也给了否定证据。
> 文中 `@123` 均指 `index-BGa3KiRe.js` 的字节偏移；`CSS@123` 指 `index-BD2BL4Cc.css`。

---

## 0. 结论速览（给壳实现直接抄）

| 项 | 事实 | 证据 |
| --- | --- | --- |
| 行外壳 | 所有工具行共用同一个 `ToolLayout`（`LWe`），非每工具独立 DOM | `@875949` |
| 折叠行高度/字号 | 单行 `inline-flex items-center gap-2`，`text-ui-base`=**14px**，行内 gap=**8px** | `@873917`、`CSS@396932` |
| 图标 | 统一 `size-4`=**16px**，`text-foreground-subtle`（浅色 `#40404099`／深色 `#e5e5e599`） | `@2916722` 等、`CSS@18159` |
| 状态图标 | **没有**。状态不以图标/圆点/转圈表达 | `@875949` 区间内 `animate-spin`/`text-destructive` 计数为 0 |
| 状态动画 | 仅一处：**运行中**把 kindLabel 换成 `animated-gradient-text`（4s linear 无限渐变） | `@877156`、`CSS@341028` |
| 耗时 | **行内不显示耗时**（任何工具行都没有 elapsed/duration） | `@875949`、`@2919460` |
| chevron | `chevron-right` 16px，**默认 opacity-0**，hover 才出现；展开时 `rotate-90`，200ms | `@873917`、`@66528` |
| 默认折叠 | 默认**全部折叠**；无「运行中自动展开」 | `@875949` |
| 展开态持久化 | 模块级 `Map`（`Gb`）按 toolId 记忆，**不落盘、不按轮次重置** | `@875815` |
| 摘要换字动画 | 新旧文案上下滑 300ms + 800ms 节流 | `@869696`、`@869718` |
| 分组默认 | explore=**开**、terminal=**开**、changes=**关** | `@3394283`；`archive/pre-3.16.1-auth/docs/desktop/13-settings通道与键表.md:142-144` |
| 分组阈值 | **≥2 条连续同类才成组**；单条退化为普通行 | `@3187896` |
| 审批 | 工具行**没有按钮**；审批是独立卡片（`ssn`），卡片里再嵌一行只读预览 | `ssn @3579711`、`@3582708` |
| backgrounded | **无任何视觉差异**，只用于「本 turn 是否仍在活动」判定 | `@3431382` |
| workId | 工具行**不渲染** workId；只在后台任务列表里做 `data-work-id` | `@2747200` 附近 |

---

## 1. 组件拓扑与折叠行

### 1.1 渲染链

```text
ToolCallBlock (V3t, @3141823)
  ├─ px(toolCall)            → 家族分类（见 §2.1）
  ├─ MKt(toolCall, workspace)→ displayModel（showInput/showOutput/inlinePreview…）
  ├─ j3t(props)              → 按 kind / family / 工具名选渲染器（`j3t @3140010`）
  └─ 渲染器（zXt / wXt / PXt / tZt / bZt / YWe / aY …）
        └─ Kb = ToolLayout (LWe, @875949)
              ├─ H_  Radix Collapsible 根（@735817）  className="w-full flex flex-col"
              ├─ jWe 折叠行（@873537）
              │    ├─ kWe 左半：icon / kindLabel / kindDetail / sourceLabel（@872203）
              │    └─ AWe 右半：分隔符 / 摘要文字 / diffCount / statusNode（@872896）
              │    └─ chevron-right（仅 canToggle 时）
              └─ W_  CollapsibleContent（@735973）
                    className="text-popover-foreground outline-none"
                    └─ div.pt-2（8px）→ renderContent()
```

`ToolCallBlock` 外层还包一层：

```html
<div class="w-full" data-testid="…" data-tool-call-id="…"
     data-tool-name="…" data-status="…" [data-zcode-tool-stream-animate="true"]>
```
`@3144727`。

### 1.2 折叠行 DOM（`jWe`，`@873537`）

**可折叠（canToggle=true）时**（`@873917`）：

```html
<div role="button" tabindex="0" aria-expanded={open} aria-label="展开/收起工具详情"
     title={title}
     class="group/tool-summary inline-flex max-w-full cursor-pointer items-center gap-2
            self-start text-left text-ui-base transition-colors
            focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-input-border-focused">
  <!-- kWe -->
  <span class="shrink-0 text-foreground-subtlest [&_svg]:text-foreground-subtlest"> {icon} </span>
  <span class="tool-summary-kind-label font-medium whitespace-nowrap shrink-0 …"> {kindLabel} </span>
  <span class="min-w-0 shrink-0"> {kindDetail} </span>
  <span class="shrink-0 rounded border border-border bg-background-alt px-1.5 py-0.5
               text-ui-xs leading-none text-foreground-subtlest"> {sourceLabel} </span>
  <!-- AWe -->
  <div class="tool-summary-content min-w-0 flex max-w-full items-center gap-2 text-foreground-subtlest">
    <span class="shrink-0 text-foreground-subtlest"> {separator} </span>
    …primaryText / secondaryText / trailing(diffCount)…
    …statusNode…
  </div>
  <svg class="size-4 text-foreground-subtlest opacity-0 transition-transform transition-opacity
              duration-200 ease-out will-change-transform
              group-hover/tool-summary:opacity-100 shrink-0
              [rotate-90 opacity-100 when open]">
</div>
```

- 不可折叠时（`canToggle=false`）：容器换成 `group/tool-summary cursor-default flex w-full items-center gap-2 …`（`@874977`），**不渲染 chevron**。
- 带 `summaryAction` 时（如 agent 行「在右侧打开」）：容器是 `div role=button`（`@873763`），**不渲染 chevron、不渲染展开内容**。
- `kindDetail` 只在少数渲染器出现：MCP（服务名）、agent（颜色点+名字）、workflow 草稿（序号）。
- `sourceLabel` 文案 `chat.toolCall.source.subAgent` = 「子智能体 / SubAgent」（`IntlProvider-C-26GNLk.js@305857` 块），仅在子代理深度 >0 时出现。

### 1.3 尺寸 / 字号 / 颜色 token

| 项 | 值 | 证据 |
| --- | --- | --- |
| 基础字号 `--ui-font-size` | **14px** | `CSS@396932` |
| `.text-ui-base` | `var(--text-ui-base)` = 14px | `CSS@11100` 区块 |
| `.text-ui-xs` | 14−4 = **10px**（sourceLabel 徽章） | `CSS@11100` |
| `.text-ui-sm` | 14−2 = **12px** | 同上 |
| 行内 gap | `gap-2` = **8px** | `@873917` |
| 行间距（时间线） | `flex flex-col gap-4` = **16px** | `@3394613` |
| 图标 | `size-4` = **16px** | 各 `var X=jsx(icon,{className:"size-4 shrink-0 text-foreground-subtle"})` |
| 展开内容上内边距 | `pt-2` = **8px** | `@875938` |
| `foreground-subtle` | 浅 `#40404099` / 深 `#e5e5e599` | `CSS@18159` |
| `foreground-subtlest` | 浅 `#40404066` / 深 `#e5e5e54d` | `CSS@18355` |
| `background-alt` | 浅 `#f5f5f599` / 深 `#26262699` | `CSS@11589` |
| `border` | 浅 `#0a0a0a1a` / 深 `#fafafa1a` | `CSS@12844` |
| `panel` | 浅 `neutral-100` / 深 `neutral-900` | `CSS@13461` |

> 注意：CSS 里**没有** `.tool-summary-kind-label` 规则（`grep -c` = 0），它只是语义钩子；颜色完全由 JSX 上的 `text-foreground-subtlest` / `animated-gradient-text` 决定。

### 1.4 展开容器与折叠动画

- `W_`（`@735973`）：`group/collapsible-content overflow-hidden data-[state=open]:animate-collapsible-down data-[state=closed]:animate-collapsible-up data-[state=closed]:[animation-fill-mode:forwards]`。
- 动画：`collapsible-down` / `collapsible-up`，时长 `--tw-duration` 默认 **0.2s**，`ease-out`，高度 0 ↔ `--radix-collapsible-content-height`（`CSS@262910`、`CSS@453130`）。
- 关闭后内容**延迟 300ms 卸载**（`PWe=300`，`@875887`），以便播完退出动画。
- 展开内容外层 `text-popover-foreground outline-none`，内层 `div.pt-2`（`@875895`、`@875938`）。

---

## 2. toolName → 图标 全映射

### 2.1 分类器（三层）

1. `j3t(props)` 先看 `kind`：
   - `changesGroup → DZt`
   - `executeGroup → B0t`
   - `cuaGroup → Gqt`
   - 含 CUA 特征 → `zqt`
   - `n1t saveWorkflow → Z2t`；`r1t listSavedWorkflows → k2t`；`o1t getWorkflowRun → f2t`；`s1t listWorkflowRuns → j2t`；`c1t evalWorkflowSnippet → P0t`；`l1t resumeWorkflowRun → R2t`；`u1t listModels → w2t`；`i1t escalate → A0t`；`a1t resolveWorkflowQuestion → U2t`
   - `family==='node-repl' → N4t`
   - 插件 UI → `M0e`，否则 MCP（`KWe`）→ `YWe`
   - 其余 `switch(family)`：`plan-guidance→I4t`、`agent→bZt`、`todo→O3t`、`ask-user-question→A3t`、`message→(RespondToCoordinator?q4t:Q4t)`、`task-control→(TaskOutput?v3t:C3t)`、`skill→n3t`、`workflow→(submit_result?u3t:T0t)`、`session-context→U4t`、`file-read→zXt`、`file-write→wXt`、`explore→tZt`、`switch-mode→f3t`、`search→UXt`、`shell→PXt`、`goal→a4t`、`default→aY`
   - 证据：`j3t` 定义于 `@3140010`
2. `px(toolCall)`（`@898208`）把 `toolName/kind/title/raw._meta.zcode` 归一化后查表；`aie` 归一化 → `Or` 家族（辅助源 `src-AL-_ONNp.js@55970` 的 `Xu` 表）。
3. 找不到时用正则兜底 `jGe`（`file-read` / `file-write` / `shell` / `search` / `explore`），再不行 `wGe = {family:'unknown'}`。

### 2.2 家族 → 渲染器 → 图标

| family / 场景 | 渲染函数 | icon 变量 | lucide 图标 | 证据（icon 定义） |
| --- | --- | --- | --- | --- |
| `file-read`（Read 等） | `zXt @2924434` | `FXt` | **search** | `@2921606` |
| `file-write`（Edit/Write/ApplyPatch） | `wXt @2908170` | `yXt` | **pencil** | `@2907677` |
| `search`（Grep/Glob/WebSearch/WebFetch） | `UXt @2926817` | `BXt` | **search** | `@2925454` |
| `explore` | `tZt @2932715` | `WXt` | **search** | `@2927568` |
| `shell`（Bash/terminal） | `PXt @2919460` | `MXt` | **square-terminal** | `@2916722` |
| `agent`（Task/Agent） | `bZt @2941743` | `hZt` | **bot** | `@2938318` |
| `changesGroup` | `DZt @2946910` | `xZt` | **pencil** | `@2945099` |
| `executeGroup` | `B0t @3053689` | `L0t` | **square-terminal** | `@3053159` |
| `cuaGroup` / CUA | `Gqt @2817044` | `TK`=`fqt` | **mouse-pointer-click**（无 app 图标时） | `@2784153`、`@2784082`；`monitor` 仅分享视图 `@114041` |
| MCP 工具 | `YWe @882944` | `zWe` | **plug** | `@881208` |
| `todo`（TodoRead/TodoWrite） | `O3t @3136589` | `w3t` | **list-todo** | `@3135987` |
| `ask-user-question` | `A3t @3138638` | `k3t` | **circle-question-mark** | `@3138563` |
| `plan-guidance`（EnterPlanMode） | `I4t @3111045` | `P4t` | **notepad-text** | `@3110295` |
| `session-context` | `U4t @3113746` | `L4t` | **book-open-text** | `@3112440` |
| `message` → RespondToCoordinator | `q4t @3116436` | `W4t` | **reply** | `@3116058` |
| `message` → SendMessage | `Q4t @3118516` | `J4t` | **send** | `@3117557` |
| `skill` | `n3t @3121720` | `$4t` | **wand-sparkles** | `@3120765` |
| `task-control` → TaskOutput | `v3t @3130703` | `p3t` | **file-output** | `@3130149` |
| `task-control` → TaskStop | `C3t @3133920` | `y3t` | **circle-stop** | `@3132873` |
| workflow → CreateWorkflow/AmendWorkflow/FillWorkflowHole | `T0t @3038510` | `RJ` | **workflow** | `@3012116` |
| workflow → saveWorkflow | `Z2t @3092869` | `W2t` | **save** | `@3091546` |
| workflow → listSavedWorkflows | `k2t @3077607` | `T2t` | **library** | `@3076338` |
| workflow → getWorkflowRun | `f2t @3065203` | `d2t` | **gauge** | `@3065128` |
| workflow → listWorkflowRuns | `j2t @3080696` | `A2t` | **history** | `@3080620` |
| workflow → evalWorkflowSnippet | `P0t @3048900` | `N0t` | **flask-conical** | `@3048824` |
| workflow → resumeWorkflowRun | `R2t @3085966` | `I2t` / `F2t` | **rotate-ccw**（普通行）/ **workflow**（已有关联 run 时用卡片 `P2t`） | `@3085880`、`@3085805` |
| workflow → listModels | `w2t @3073225` | `mY` | **cpu** | `@3070727` |
| workflow → escalate | `A0t @3045499` | `E0t` | **message-circle-question-mark** | `@3045003` |
| workflow → resolveWorkflowQuestion | `U2t @3088900` | `z2t` | **message-circle-reply** | `@3088403` |
| workflow → submit_result | `u3t @3124979` | `r3t` | **clipboard-check** | `@3124184` |
| `node-repl`（js 工具） | `N4t @3105460` | `NY` | **square-mouse-pointer**（有 app 时改用 app 图标） | `@3103772` |
| `goal` | `a4t @3097155` | `Q2t` | **goal** | `@3096012` |
| `switch-mode`（ExitPlanMode / 计划模式切换） | `f3t @3127066` | 不用 Kb 行；卡片头 `xl` = **notepad-text** | — | `@3127066`（卡片头 `size-4 text-foreground-subtle`） |
| **默认/未知工具** | `aY @3055522` | `V0t` | **wrench** | `@3055447` |
| 权限预览行（审批卡内） | `osn @3579230` | `Bon` | **wrench** | `@3574987` |

> 关键反直觉点：**Read / Grep / Glob / Explore 全都用 search（放大镜）图标**；file-write 与 changesGroup 用 pencil；未知工具用 wrench。
> 共享图标常量：`Gl = search`（`@124122`）、`Al = pencil`、`eu = square-terminal`、`Ys = bot`、`Nl = plug`、`Wl = save`、`Tle = list-todo`、`lc = circle-question-mark`。

### 2.3 图标尺寸与颜色

- 所有工具图标：`className="size-4 shrink-0 text-foreground-subtle"`（16px，次要前景色）。
- 外层再包一层 `span.shrink-0.text-foreground-subtlest [&_svg]:text-foreground-subtlest`（`kWe`，`@872203`）——**实际渲染色由 svg 自身的 `text-foreground-subtle` 胜出**（更具体的元素类）。
- 分组/子行里 `showIcon={false}` 时**整行不渲染图标**（explore/changes/execute 的子行、多文件写入的子行）。

### 2.4 toolName 归一化与家族表（辅助源）

`src-AL-_ONNp.js@55567` 的 `Yu` 列表（大小写不敏感）：

```
Read Write Edit ApplyPatch Bash Glob Grep WebFetch WebSearch web_search
TodoRead TodoWrite GoalRead ReadSessionContext AskUserQuestion
SendMessage RespondToCoordinator TaskOutput TaskStop
js js_reset js_add_node_module_dir
mcp__node_repl__js mcp__node_repl__js_reset mcp__node_repl__js_add_node_module_dir
Agent Task Skill CreateWorkflow AmendWorkflow FillWorkflowHole submit_result
```

`Xu`（`@55970`）映射：`Read→file-read`、`Write/Edit/ApplyPatch→file-write`、`Bash→shell`、`Glob/Grep/WebFetch/WebSearch/web_search→search`、`TodoRead/TodoWrite→todo`、`GoalRead→goal`、`ReadSessionContext→session-context`、`AskUserQuestion→ask-user-question`、`SendMessage/RespondToCoordinator→message`、`TaskOutput/TaskStop→task-control`、`js*→node-repl`、`Agent/Task→agent`、`Skill→skill`、`CreateWorkflow/AmendWorkflow/FillWorkflowHole/submit_result→workflow`。

状态映射 `Ku`（`@55182`）：

```
input-streaming → chat.toolCall.status.pending
input-available → chat.toolCall.status.running
output-available→ chat.toolCall.status.completed
output-error    → chat.toolCall.status.failed
output-denied   → chat.toolCall.status.denied
```

---

## 3. 状态机

### 3.1 状态枚举与归一化

行上的 `toolCall.status`（`archive/pre-3.16.1-auth/docs/remote-v4/18-...md:353`）：`inputStreaming | pendingApproval | running | success | error | cancelled`。

渲染前归一化 `OKt`（`@2782540`）+ `DKt`（`@2782381`）：

| 行状态 | 归一化后 |
| --- | --- |
| `inputStreaming` | `input-streaming` |
| `pendingApproval` | `input-streaming`（同「等待中」） |
| `running` | `input-available` |
| `success` | `output-available` |
| `error` | `output-error` |
| `cancelled` | `output-error`，另有 `stopped` 分支 |
| `denied`（部分工具） | `output-denied` |

`isRunning = yee(normalizedStatus)`，即 `input-streaming` 或 `input-available`（`@3142896` 处 `U=yee(V)`）。
> 因此 **pendingApproval 也算「运行中」**：kindLabel 会走渐变流动动画。

### 3.2 状态文案

`Zt(normalized, rawStatus)` → 文案 id（`IntlProvider-C-26GNLk.js@305857` 起）：

| 状态 | 中文 | English |
| --- | --- | --- |
| pending | 等待中 | Pending |
| running | 执行中 | Running |
| completed | 已执行 | Completed |
| failed | 执行失败 | Failed |
| denied | 已拒绝 | Denied |
| stopped | 已停止 | Stopped |

但**多数工具行不显示状态文案**：`ToolLayout` 里 `G=(showStatusLabel||showFailureStatus) && statusLabel!=null`（`@876877`）。只有：
- `showFailureStatus`（失败时）→ 显示；
- 少数渲染器显式 `showStatusLabel`（message、TaskOutput、TaskStop）；
- MCP 把状态/进度放进 `secondaryText`。

### 3.3 视觉：没有状态图标 / 转圈 / 脉冲 / 耗时

对 `ToolLayout` 全区间 `@875949–@879950` 做计数：

```
text-destructive: 0   text-warning: 0   text-success: 0
animate-spin: 0       animate-pulse: 0
```

结论（否定证据）：
- 行首图标**只由工具类型决定**，不随状态变化；
- 没有 spinner、没有脉冲点、没有成功/失败着色；
- **折叠行不显示耗时**。工具行渲染器里没有 elapsed 计算；`startedAt` 只出现在 workflow 卡片（`@3063602`、`@3064064`）与 subagent 步骤（`@3063300` 附近）。`chat.longRunning.elapsedSeconds` 只用于长任务横幅（`@2663936`）。展开详情里只有 CUA 有 payload 自带的 `duration` 字段（`chat.toolCall.cua.details.duration`），不是实时计时。

### 3.4 运行中：kindLabel 渐变流动（唯一状态动画）

`me = J('font-medium whitespace-nowrap shrink-0', isRunning ? 'animated-gradient-text' : 'text-foreground-subtlest')`（`@877156`）。

`CSS@341028`：

```css
.animated-gradient-text{
  background: linear-gradient(90deg, strong 0%, strong 34%, soft 50%, strong 66%, strong 100%);
  color: transparent; -webkit-text-fill-color: transparent;
  background-size: 300% 100%; -webkit-background-clip: text; background-clip: text;
  animation: 4s linear infinite gradient-flow; display: inline-block;
}
@keyframes gradient-flow{0%{background-position:100% 0}50%{background-position:0 0}}
```
（`CSS@342695`）

浅色主题变量：`--animated-gradient-text-strong:#0a0a0a`、`--animated-gradient-text-soft:#0a0a0a38`（`CSS@11500` 区块）。

> 效果：运行中 kindLabel（如「终端」「读取」「MCP」）文字变成 4 秒一轮的明暗扫光；结束后变回 `foreground-subtlest` 纯色。

### 3.5 失败：状态文字 + tooltip + 复制

失败时 `statusLabel` 以 `span.whitespace-nowrap.underline.decoration-dotted.underline-offset-2.cursor-help` 渲染，**颜色继承 `text-foreground-subtlest`（不是红色）**（`@876500` 附近）。

- tooltip：`side=top align=start max-w-96`，正文 `line-clamp-3 whitespace-pre-wrap break-words`，右侧 ghost 图标按钮 `size-3`，`copy → check` 1.5s 后复位（`ne` 定时器 1500ms）。复制走 `navigator.clipboard.writeText`（`@877883`），dotted 下划线在 `@878194`。
- 文案 `chat.toolCall.copyError` = 「复制 / Copy」，复制后 `chat.toolCall.copyError.copied`。
- 错误文本来源 `ET(toolCall)`（`@1195280`）：`error.message` → `output.error/message` → `raw.rawOutput.error/message` → `raw.status` → 兜底。

### 3.6 progress（仅 MCP / workflow）

**MCP**（`YWe`，`@882944`）：`status==='in_progress'` 时取 `raw.progress`（`WFe @736746`），渲染 `GFe @736860`：

```html
<span role="progressbar" aria-valuemin=0 aria-valuemax=1 [aria-valuenow=fraction]
      class="inline-flex min-w-0 items-center gap-2">
  <span class="text-foreground-subtlest">·</span>
  <span class="relative inline-block h-1 w-16 shrink-0 overflow-hidden rounded-full bg-surface-hover">
    <span class="tool-call-progress-indeterminate absolute inset-y-0 w-1/3 rounded-full bg-brand"></span>
    <!-- 或 -->
    <span class="absolute inset-y-0 left-0 rounded-full bg-brand transition-[width] duration-200"
          style="width:NN%"></span>
  </span>
  <span class="min-w-0 truncate text-ui-sm text-foreground-subtle">{message}</span>
</span>
```
- 尺寸：**高 4px（h-1）× 宽 64px（w-16）**，圆角全圆。
- 不确定进度：`tool-call-progress-indeterminate` = `1.2s ease-in-out infinite`，`left:-33% → 100%`；`prefers-reduced-motion` 时改为满宽静止（`CSS@423974`）。
- 确定进度：宽度 200ms 过渡。

**Workflow 卡片**：状态灯 `S0t`（`@3038267`，`wf-lamp size-2 rounded-full`），类映射 `EG @2655798`：

| 状态 | 类 |
| --- | --- |
| done | `bg-success` |
| failed | `bg-destructive ring-2 ring-destructive/30` |
| pending | `border-[1.5px] border-foreground-subtlest bg-transparent` |
| running | `animate-pulse bg-warning motion-reduce:animate-none` |

文字色 `OG @2656176`：pending `text-foreground-subtle`、running `text-warning`、completed `text-success`、errored `text-destructive`。

### 3.7 todo 行内状态图标

`D3t @3136331`：

| 状态 | 图标 | 类 |
| --- | --- | --- |
| completed | `circle-check` | `size-3.5 shrink-0 text-success` |
| in_progress | `arrow-right` | `size-3.5 shrink-0 text-foreground` |
| pending | `circle` | `size-3.5 shrink-0 text-foreground-subtlest` |

todo 标题类 `T3t`：pending `text-foreground-subtle`、in_progress `text-foreground`、completed `text-foreground-subtlest line-through`（`@3136010`）。

---

## 4. 折叠单行摘要（summary）的来源与格式化

### 4.1 通用：primaryText / secondaryText / trailing

| 槽位 | 用途 | 类 |
| --- | --- | --- |
| `primaryText` | 主摘要（文件名、命令、查询、任务名） | 由各渲染器给 span；普遍 `truncate` |
| `secondaryText` | 次摘要（路径、命令代码、计数） | 容器 `text-foreground-subtlest`；路径 span 额外 `@max-[360px]/conversation:hidden` |
| `trailingText` | diffCount（+N/−N） | `font-mono leading-none tabular-nums` |
| `statusNode` | 状态文案/进度 | 同上 |
| `separator` | `·` | `shrink-0 text-foreground-subtlest` |

### 4.2 read（`zXt @2924434`）

- `primaryText`：文件 chip（`Mq @2923694`）——`button`（可点开 code viewer）或 `span`，内容 `[16px 文件图标][fileName truncate]`，`gap-1.5`（6px），`text-foreground-subtle`，hover 下划线；`title=path`。
- `secondaryText`：`yq(filePath)` → 缩短路径（见下）。
- `canToggle:false`、`content:null` → **Read 行不可展开**。
- kindLabel：运行中「正在读取」，结束「读取」。

**路径缩短** `xg @675327`：

```
hg(e)  // 反斜杠→斜杠
lMe(path, workspacePath):
  path === workspace ? "" :
  path.startsWith(workspace + "/") ? path.slice(len+1) : path
→ 无 workspace 或非绝对路径时 cg(e)  // basename
```
（`hg @672028`、`lMe @673899`、`cg @669924`、`ug @670282` 判绝对路径）

### 4.3 search（`UXt @2926817`）

`HXt(intl, input) @2925595`：

1. `input` 是字符串 → 「查找 {query}」/「Find {query}」；空 → 「查找」。
2. `input.parsed_cmd` 数组：
   - `type==='list_files'` → 有 `cwd` 时「列出 {cwd}」，否则「列出」；
   - `type∈{search,grep,glob}` → 取 `pattern/query/path`，「查找 {query}」。
3. 兜底依次找 `search_query/searchQuery/query/pattern/path/url/prompt/target/name` → 「查找 {query}」。
4. 都没有 → 「查找」。

`primaryText` = `<span class="truncate">`；`canToggle:false`、`content:null` → **Search 行不可展开**。

### 4.4 shell / 终端（`PXt @2919460`）

- 命令提取 `wq @2916862`：
  - 字符串 → trim；
  - 字符串数组 → 优先 `-lc` 后一项，否则 join(` `)；
  - 对象 → 依次 `command/cmd/script`，再 `parsed_cmd[].cmd/command/script`。
- `Tq @2917653` 额外剥壳：`/^(?:\/bin\/)?(zsh|bash|sh)\s+-lc\s+(...)$/` → 只留真实命令；数组同样处理 `-lc`。
- `secondaryText` = `<code class="min-w-0 truncate font-sans">{command}</code>`（**只做 CSS 省略，不做字符截断**）。
- 非 office 模式下 `primaryText` 为 `null`，`title` = 命令全文（hover 可看全）。
- kindLabel：运行中「正在执行 / Running」，结束「终端 / Terminal」。

### 4.5 file-write / edit（`wXt @2908170`）

- kindLabel `bq @2907037`：
  - `operationKind==='write'` → 运行「正在写入」/结束「写入」；
  - `'delete'` → 「正在删除」/「删除」；
  - 其余 → 「正在编辑」/「编辑」。
- `primaryText`：
  - 单文件 → 文件 chip（同 read）；
  - 多文件（`rawFileSummaries.length>1`）→ `<span>{count} 个文件</span>`。
- `secondaryText`：单文件时缩短路径。
- `trailingText` = diffCount `_q @2905408`：`+N`（`text-diff-added`）/`-N`（`text-diff-removed`），`font-mono tabular-nums`，逐字符入场动画 `_Xt @2904993`。
- 展开时 `hideDiffCountWhenOpen=true`（多文件时）→ 展开后隐藏 diffCount。

### 4.6 explore（`tZt @2932715`）

- kindLabel 固定「查阅 / Explore」。
- 摘要 `YXt @2929526`：对子工具按 `JXt @2929153` 分三桶（`search` / `list` / `file`），输出 `"3 searches, 2 files"`（逗号+空格连接，i18n 复数），全 0 → 「0 个文件 / 0 files」。
- **运行中**：`primaryText` 换成最后一个子工具的动态摘要（`eZt`，含动作词）；`expandedPrimaryText` 才是计数桶；`secondaryText` 取子工具次摘要；`separator='·'`。
- `animateSummaryContent:true`、`summaryContentKey='explore:toolId:running|done:…'`。

### 4.7 todo（`O3t @3136589`）

- `primaryText`：当前 `in_progress`（否则第一个未完成，否则最后一个）任务标题；无则 `todo.panel.currentTask`。
- `secondaryText`：`"已完成/总数"`，`font-mono text-ui-base text-foreground-subtlest`。
- 展开：`div.space-y-1.rounded-xl.bg-surface.px-3.py-2`，每项 `flex items-center gap-2 py-1`（图标+标题）。

### 4.8 agent（`bZt @2941743`）

- kindLabel：agent 名（`_meta.zcode.nickname` → input.nickname → output.nickname → agentType → `SubAgent`）。
- kindDetail：颜色点 + 名字（`vZt`）。
- `primaryText`：`description/summary/message` → `input.description` → `subagent_type` → `agent_type` → output 摘要。
- 运行中 `secondaryText` 显示最后子工具动作（`eZt`）。
- 右侧 `summaryAction`：按钮 aria-label「在右侧打开 / Open on the right」（`chat.toolCall.agent.openInSidePane`）。

### 4.9 mcp（`YWe @882944`）

- kindLabel 固定 `MCP`；kindDetail = 服务名（`text-foreground-subtle`）。
- primaryText = 工具名（`Yb` 把 `snake_case` 变 Title Case）。
- secondaryText = 进度条或状态文案（`·` 分隔）。

### 4.10 默认（`aY @3055522`）

- kindLabel = `kind` 首字母大写；primaryText = `title` 或「工具调用 / Tool call」。

### 4.11 摘要文字切换动画（`DWe @870478`）

常量：`vWe=800`（锁定/节流）、`yWe=250`、`bWe=2`（队列上限）、`xWe={duration:0.3, ease:[0.4,0,0.2,1]}`（`@869696`、`@869704`、`@869712`、`@869718`）。

行为：
- 只有 `contentKey` 变化才播动画；`primaryText` 变化但 key 相同 → 直接替换（`TWe`）。
- 动画：旧内容 `y:-0.8em, opacity:0` 退出，新内容从 `y:0.8em, opacity:0` 进入，`popLayout`，300ms。
- 动画期间（800ms 锁）新变化进入队列（最多 2 条），锁结束后若队列最老项已在锁开始后 250ms 内则丢弃它，只取最新（`CWe`）。
- `prefers-reduced-motion` 或 `disableSummaryContentAnimation` → 直接替换。
- 触发者：explore、executeGroup、agent（`animateSummaryContent:!0`）。

---

## 5. 展开后的分区

### 5.1 通用工具详情（`aY` → `LK @2821947`）

渲染顺序（`displayModel` 决定显示哪些，`MKt @2784573` + 覆盖表 `jKt @2783157`）：

1. **inlinePreview**（`displayModel.inlinePreview.type`）
   - `text`：`Jqt @2822872` —— `max-h-60`（**240px**）`overflow-auto rounded-xl`；markdown 时额外 `border border-border bg-muted/15 px-3 py-3`；非 markdown 用 CodeBlock `bg-muted/15`。
   - `image`：`Yqt @2823464` —— `img`，读取期间显示「正在加载... / Loading...」。
2. **planResult**：`Xqt @2823796` —— `space-y-3 rounded-xl border border-outline/60 bg-muted/15 p-3`；「计划文件 / Plan file」+ 路径 chip（`rounded-md bg-muted/70 px-2 py-1 font-mono`）+ Markdown 正文。
3. **输入**（`showInput && input!==undefined`）→ `qqt @2820819`（见 5.2）。
4. **输出/错误**（`showOutput`）→ `IK @2821203`（见 5.3）。
5. **kind**（`showKind`）→ `<p class="text-ui-base text-muted-foreground">`。
6. 若 `inlinePreview.type==='none'` 且未隐藏 raw fallback：
   `<pre class="px-4 py-3 rounded-xl bg-surface text-ui-xs mt-1 text-foreground-subtle max-h-50 overflow-auto">` 打印 `JSON.stringify(toolCall,null,2)`（`@3056311`，`max-h-50`=**200px**，字号 10px）。

**每类工具的 showInput/showOutput 覆盖**（`jKt @2783157`）：

| 匹配 | showInput | showOutput | showKind | inlinePreview |
| --- | --- | --- | --- | --- |
| 非「write 类」的 file-write（`hx`） | 有 preview 时 false | 仅错误 | 有 preview 时 false | 取 preview |
| `file-read` | 有 preview 时 false | 仅错误 | 有 preview 时 false | 取 preview |
| write 类（`mx`） | 有 preview 时 false | 仅错误 | 有 preview 时 false | 取 preview |
| `shell` | **false** | output 存在或错误 | **false** | none |
| `search` | **false** | output 存在或错误 | **false** | none |
| `goal` | **false** | output 存在或错误 | **false** | none |
| `node-repl` | false | false | false | none |
| preview 为 image | 有 preview 时 false | 仅错误 | 有 preview 时 false | 取 preview |

`planResult` 存在时强制 `showInput:false, showOutput:仅错误`；`errorText` 存在时强制 `inlinePreview:none, showInput:false, showOutput:true, showKind:false`（`@2784800` 附近）。

### 5.2 输入区（`qqt @2820819`）

```html
<div class="space-y-2 overflow-hidden">
  <h4 class="font-medium text-muted-foreground text-ui-base uppercase tracking-wide">Parameters</h4>
  <div class="max-h-60 overflow-auto rounded-md bg-muted/50">
    <CodeBlock language="json" code={JSON.stringify(input,null,2)} />
  </div>
</div>
```
- 标题是**硬编码英文 `Parameters`**（不走 i18n）。
- 最大高度 **240px**，内部滚动。
- JSON 代码块，语法高亮默认开启（`z_` CodeBlock：`fontSizePx=14`、`showLineNumbers=false`、`enableSyntaxHighlighting=true`、`renderMermaid=true`）。

### 5.3 输出 / 错误区（`IK @2821203`）

```html
<div class="space-y-2">
  <h4 class="font-medium text-muted-foreground text-ui-base uppercase tracking-wide">Result | Error</h4>
  <div class="max-h-60 overflow-auto rounded-md text-ui-base [&_table]:w-full
              bg-muted/50 text-foreground  |  bg-destructive/10 text-destructive">
    <div class="whitespace-pre-wrap p-3">{errorText}</div>   <!-- 错误时 -->
    <CodeBlock language="json" code={output} />               <!-- 正常时 -->
  </div>
</div>
```
- 标题硬编码 `Result` / `Error`。
- 最大高度 **240px**，内部滚动。
- 错误面：`bg-destructive/10 text-destructive`，`whitespace-pre-wrap p-3`。
- 正常输出：对象 → `JSON.stringify(...,2)`；字符串 → 直接当 json 代码块（**非 JSON 也按 json 语言高亮**）。

### 5.4 shell 展开（`PXt` 的 `renderContent`，卡片 `execute-detail` `@2920108`）

```html
<div class="space-y-3 mb-2 rounded-xl border border-border bg-panel px-4 py-3">
  <div class="space-y-1">
    <div class="flex items-start gap-2 font-sans text-ui-base text-foreground"> …命令… </div>
  </div>
  <div> …输出… </div>
</div>
```

**命令块**（`AXt @2914273`）：
- `grid grid-cols-[1ch_minmax(0,1fr)] gap-x-2 gap-y-1 font-mono`；
- `$` 提示符 `text-foreground-subtle`；
- `<pre class="min-w-0 flex-1 whitespace-pre-wrap break-words">`；
- 折叠：`max-h-[3lh] overflow-hidden`（**3 行**）；展开：`max-h-[10lh] overflow-y-auto`（**10 行**）；
- 仅在内容溢出时出现「展开命令 / Show full command」↔「收起 / Collapse」链接按钮（`text-ui-sm text-foreground-subtle`，`chevron-down` 12px，展开时 `rotate-180`）；`ResizeObserver` 判断 `scrollHeight > clientHeight+1`。

**输出块**（`jXt @2915768`）：
- `max-h-[5lh]`（**5 行**）`overflow-auto`，`leading-5`；
- `<pre class="whitespace-pre-wrap break-words font-mono text-ui-base leading-5 text-foreground-subtle">`；
- 运行中**自动跟随底部**：`scrollTop = scrollHeight`；用户上滚（距底 >8px）后暂停跟随，回到底部再恢复；
- 无输出且非运行 → 「没有输出。 / No output.」（`chat.toolCall.execute.noOutput`）。

### 5.5 MCP 展开（`YWe`，`@882944`）

顺序：
1. **错误**（`failed`）：
   `<section class="space-y-1.5"><h4 class="text-ui-base font-medium text-destructive">结果</h4><p class="whitespace-pre-wrap break-words rounded-lg bg-destructive/10 px-3 py-2 text-ui-base text-destructive">`。
2. **结果**：
   - 短文本（≤**160** 字符、单行、非 JSON，`HWe @881479`；`BWe=160 @881283`）→ 段落 `rounded-xl border border-border bg-card px-3 py-2 text-ui-base text-foreground-subtle`；
   - 否则 → `max-h-72`（**288px**）`overflow-auto rounded-xl border border-border bg-card` 的 CodeBlock；可 JSON 解析时 `language=json`，否则 `log`；`wrapLongLines:true`；头部工具条：标题「结果」+ 自动换行按钮 + 复制结果按钮（图标 `size-3`）。
3. **pending** → 状态文案段落；**stopped** → `·` + 状态文案。
4. **「查看调用详情 / View call details」** 折叠（`group/details`）：
   - 触发按钮 ghost `size=sm`，`chevron-right size-3.5` 展开时 `rotate-90`；
   - 内容 `space-y-3 pt-2`：
     - 描述 `h4 text-ui-sm font-medium text-foreground-subtlest`（「工具说明」）+ `p text-ui-base text-foreground-subtle`；
     - 参数 `h4`（「调用参数」）+ `max-h-72 overflow-auto rounded-xl border bg-card` 的 JSON CodeBlock。

### 5.6 agent 展开（`bZt`）

`div.ml-2.space-y-3.border-border.border-l.pl-3.5`（8px 左移 / 12px 竖排间距 / 1px 竖线 / 14px 左内边距）依次：
- 后台启动/活动条（`yZt`）：`outputFile`、活动文案；
- prompt（`mZt`，Markdown）；
- thought（`_Zt`，标题「Agent thought」）；
- output（`_Zt`，标题「Agent output」）；
- 子工具列表（`gZt`，每行 `ZY` 递归渲染）。

后台文案（`IntlProvider`）：「启动 / Launch」「启动中 / Launching」「已启动 / Launched」「启动失败 / Launch failed」「活动 / Activity」「后台运行中，等待输出」「等待子智能体回传」「后台运行中，正在同步输出」「已收到子智能体回传」。

### 5.7 file-write 展开

- 单文件：`space-y-3` →（若有 patch）`gq` diff 预览 + `LK` 文件预览 + snapshot 提示。
- 多文件：`div.ml-2.space-y-2.border-border.border-l.pl-3.5`，每个文件一行 `TXt`（`showIcon:false`，各自可折叠）。

### 5.8 截断与「加载完整数据」

`qb @879942`：当 `snapshotRefs.length>0` 时显示：

```html
<div class="flex flex-col gap-2 rounded-xl border border-border/70 bg-muted/40 px-3 py-2
            text-ui-base text-foreground-subtle sm:flex-row sm:items-center sm:justify-between">
  <span>该工具有 {fields} 个字段被裁剪，当前显示预览 {previewBytes} / {fullBytes}</span>
  <button class="h-7 px-2 text-ui-base" variant=secondary size=sm>
    [spinner] 加载完整工具数据
  </button>
</div>
```
- 字节格式化 `RWe`：≥1MB → `x.x MB`；≥1KB → `x.x KB`；否则 `N B`。
- 按钮态：`加载完整工具数据` → `正在加载...` → 失败 `加载失败，重试`；loading 时 `loader-circle size-3 animate-spin`。
- **客户端不对 output 做字符截断**：唯一的体积控制是服务端 `snapshotRefs` 的 preview/full 机制。

### 5.9 代码高亮

`z_` CodeBlock（`@732499`）默认：`enableSyntaxHighlighting=true`、`fontSizePx=14`、`showLineNumbers=false`、`wrapLongLines=false`、`renderMermaid=true`。MCP 结果与参数均开启高亮/换行开关。

---

## 6. 工具分组（explore / terminal / changes）

### 6.1 开关与默认值

| 键 | 默认 | 证据 |
| --- | --- | --- |
| `toolGroupingExploreEnabled` | **true** | `@3394283`；`archive/pre-3.16.1-auth/docs/desktop/13-settings通道与键表.md:142` |
| `toolGroupingTerminalEnabled` | **true** | `@3394338`；同上 `:143` |
| `toolGroupingChangesEnabled` | **false** | `@3394395`；同上 `:144` |

读取处：`t.toolGroupingExploreEnabled ?? !0` 等（`@3394283`）；设置页默认值另见 `@3635500` 区块、`@3645200`。

### 6.2 触发条件（`B8t @3187896`）

预处理（`@3187896` 起）：

1. 过滤掉「不该参与分组的行」：`reasoning` 行按 `messageStreamShowReasoning` 决定；`P8t`（**shell 且还没解析出命令**的 `inputStreaming`/`running` 行）一律不分组（`P8t @3185981`）。
2. 按顺序扫描：
   - `cuaGroup` → 原样输出；
   - 已被 subagent 认领的 `subagent` 行 → 跳过；
   - agent 工具行 → `agentToolCall`；
   - **非 explore** 行：
     - `enableChangesGrouping && N8t(row)`（file-write 家族）→ 收集**连续** file-write 行；**长度=1 → 普通行**，≥2 → `changesGroup`；
     - 否则 `enableTerminalGrouping && M8t(row)`（shell 家族且不是 explore-like）→ 收集连续行；1 → 普通行，≥2 → `executeGroup`；
     - 否则普通行；
   - **explore 行**：`enableExploreGrouping` 关 → 普通行；开 → 收集连续 `j8t` 行；1 → 普通行，≥2 → `exploreGroup`。
3. 组是否「活动」：`active = stageTailIsRunning && 该组一直延伸到列表末尾`。
4. 组状态 `F8t`：`active ? 'in_progress' : (任一 cancelled ? 'stopped' : 'completed')`。

> 注意：**只有「连续」才会合并**；中间夹一条别的工具就断组。**单条不成组**（这条最容易移植错）。

### 6.3 explore 组的成员判定（`j8t` → `b8t @3182251`）

- `file-write` → **false**（永不进 explore 组）；
- `file-read` / `search` / `explore` → **true**；
- `shell` → 需要看命令：
  - 命令列表 `m8t @3180971` 为空 → false；
  - 命中写/改类 `g8t`（`sed -i`、`tee`、`mv`、`cp`、`rm`、`mkdir`、`git add/commit/...`）→ false；
  - 命中重定向 `_8t`（`>`、`>>`、`&>`）→ false；
  - 命中只读类 `h8t`（`rg|grep|find|ls|cat|head|tail|wc|stat|pwd|which|readlink|tree|sed -n|Get-ChildItem|dir|Get-Content|type|Select-String|Get-Location|Test-Path|Resolve-Path` 或 `git status|log|show|diff`）→ **true**；
  - 含循环 `v8t`（`for|while`）且命中只读 → true；否则 false。
- 正则原文见 `@3181587`（h8t）、`@3181799`（g8t）、`@3182099`（_8t）、`@3182133`（v8t）。

terminal 组判定 `x8t @3182600`：`family==='shell' && !y8t && !b8t`（`y8t` = shell 且命令列表为空）。

### 6.4 组头（合成 toolCall）

`I8t @3186234 / L8t @3186550 / R8t @3186872` 分别造出：

| 组 | toolId | toolName / kind / title | 状态 |
| --- | --- | --- | --- |
| explore | `explore:${首个 toolCallId}` | `Explore / Explore / Explore` | `F8t` |
| execute | `execute:${…}` | `ExecuteGroup / executeGroup / Execute` | `F8t` |
| changes | `changes:${…}` | `ChangesGroup / changesGroup / Changes` | `active?'in_progress':'completed'` |

组头文案与图标：

| 组 | kindLabel | 图标 |
| --- | --- | --- |
| explore | 「查阅 / Explore」（`chat.toolCall.explore.label`） | search |
| terminal | 「终端 / Terminal」（`chat.toolCall.executeGroup.label`） | square-terminal |
| changes | 「更改 / Changes」（`chat.toolCall.changesGroup.label`） | pencil |

### 6.5 组内缩进

三组完全一致：

```html
<div class="ml-2 space-y-2 border-border border-l pl-3.5">
```
= 左外边距 **8px**、行间 **8px**、左侧 **1px** 竖线、左内边距 **14px**；子行一律 `showIcon:false`。
（`DZt @2946910`、`B0t @3053689`、`tZt @2932715`；常量类 `M3t @3141314`）

### 6.6 组折叠与组摘要

- 组头本身就是一个 `Kb`，折叠规则与普通行完全一致（同一个 `Gb` Map、同一条 0.2s 折叠动画）。
- **explore**：`primaryText` 运行中 = 最后一个子工具动态摘要；非运行 = 计数桶；`expandedPrimaryText` = 计数桶；`expandedSecondaryText=null`；`separator='·'`；`animateSummaryContent:true`。
- **terminal**：运行中 `primaryText` = 「正在执行」+ `secondaryText` = 最后一条命令（`<code truncate>`）；非运行 `primaryText` = `z0t` 统计；`expandedPrimaryText` = `z0t` 统计；`expandedSecondaryText=null`；`separator='·'`。
  - `z0t @3053273`：`"{N} 个命令[, {M} 个失败][, {K} 个已停止]"`（`command.one/other`、`failed`、`stopped`）。
- **changes**：
  - `primaryText`：单文件 → 文件 chip；多文件 → `span.inline-flex … gap-2` = 「{N} 个文件」+ `·` + 文件 chip 列表；
  - `expandedPrimaryText`：最后一条子工具 kindLabel + `·` + 文件 chip + 缩短路径；
  - `trailingText` = 最后一条文件变更的 diffCount（`animateInitial:true`）；
  - 文件 chip 列表 `EZt @2945520` 会按可用宽度测量并折叠成 `+N`。

### 6.7 组动画

- 组头摘要换字：同 §4.11（300ms 上下滑 + 800ms 节流）。
- 组展开/收起：同 §1.4（0.2s height 动画）。
- 组本身**没有**额外入场动画；新行入场见 §7.4。

---

## 7. 默认折叠 / 流式展开 / 折回：精确规则

`ToolLayout` 的状态源（`LWe @875949`）：

```
R  = persistOpenKey ?? toolId
z  = Gb.get(R) ?? false            // 模块级 Map，@875815
V  = summaryAction ? false : (forceOpen || (canToggle && z))
te = 过渡用的「保持挂载」标志
oe = (V || te) ? (renderContent() ?? content) : null
```

| 规则 | 结论 | 证据 |
| --- | --- | --- |
| 初始 | **折叠**（`Gb` 无记录 → false） | `@875949` |
| 用户点击 | 写 `Gb.set(R, open)`；同 toolId 的后续重渲染保持 | `@879103` |
| 持久化范围 | 模块级 `Map`，**页面刷新即丢**；无 localStorage | `@875815` |
| 按轮次重置 | **没有**。未发现任何 `Gb.clear()` / 按 turn 重置 | `grep Gb.clear` 无命中 |
| 运行中自动展开 | **没有**（`autoOpen` 默认 false，工具行未传 true） | `@876040` |
| `forceOpen` | 仅权限预览行使用（见 §9）；其余工具传 false | `asn @3578682`、`rsn @3578296` |
| `autoOpen` | 仅 pptx 工件卡片使用，工具行传入 `autoOpen:!1` | `@2909109`、`@3139638` |
| `autoCollapseOnComplete` | **只有 agent 行**开启：`isRunning` 由 true→false 时 `Gb.set(R,false)` + 收起 | `@2944459`、`@877400` |
| 关闭后内容卸载 | 延迟 **300ms**（播完收起动画） | `@875887` |
| 不可折叠 | read / search / message(RespondToCoordinator) / 权限预览：`canToggle:false`，无 chevron、无内容 | `@2924434`、`@2926817`、`@3116436` |
| `hideSecondaryTextWhenOpen` | 展开后隐藏次摘要（shell 用） | `@2919460` |
| 展开内容是否缓存 | `renderContent` 为回调，仅展开时调用；关闭后 300ms 丢弃 | `@875949` |

### 7.4 流式入场动画

`V3t` 的 `streamingEntranceActive`（默认 **false**）+ `streamingEntranceKeyPrefix`：

- 新 toolId 且 `streamingEntranceActive` 为真时，`data-zcode-tool-stream-animate="true"`，**1s 后移除**（`P3t=1e3 @3141387`）。
- CSS：`[data-zcode-tool-stream-animate=true]{will-change:opacity;animation:.9s cubic-bezier(.16,1,.3,1) both zcode-stream-text-in}`（`CSS@425592`），即 0.9s 淡入。
- 同一 key 只播一次（`XY` Map 记录已播 key，上限 800 条）。

> 结论：**「流式展开」并不存在**；流式只影响 kindLabel 渐变与（可选的）行淡入。

---

## 8. backgrounded 工具与 workId

### 8.1 数据字段

`toolCall` 行 schema（`archive/pre-3.16.1-auth/docs/remote-v4/18-...md:353`）：`… approvalInteractionId?、backgrounded?、workId?、startedAt?、endedAt?`。

### 8.2 视觉差异：没有

- 全库 `.backgrounded` 只出现 2 次（`@3431501`、`@3431626`），均在 `Knn`：

```js
case 'toolCall': return e.backgrounded !== true &&
  (e.status==='inputStreaming' || e.status==='pendingApproval' || e.status==='running');
```
- 即 backgrounded 只把该行**排除出「本 turn 仍在活动」**的判定（影响滚动跟随/活动指示），**不改变行本身的任何样式、图标、摘要或折叠状态**。
- 工具行渲染器里**没有任何** `backgrounded` 分支（`grep -c` 在渲染区间为 0）。

### 8.3 workId：工具行不渲染

- `workId` 在工具行渲染区间（`@2780000–@3160000`）**零命中**。
- workId 的用途：
  - 后台任务列表项：`data-work-id={workId}`、`data-work-status`、取消按钮（`@2747200` 附近）；
  - 后台任务面板聚合：`runningBashWorks / runningSubagentWorks / runningWorkflowRuns`（`@2730700` 起），用于顶部/底部后台任务条；
  - 取消后台任务的命令参数。
- 结论：**安卓端不要在 toolCall 行上为 backgrounded/workId 设计额外 UI**；它们只影响活动态与后台面板。

---

## 9. 审批（pendingApproval）

### 9.1 工具行内：没有按钮

- `pendingApproval` 在行内只表现为：kindLabel 渐变流动（因为算 isRunning）、状态文案（若显示）为「等待中 / Pending」。
- `ToolLayout` 内**唯一的按钮**是失败状态 tooltip 里的「复制」图标按钮（`@876500` 附近）。
- `approvalInteractionId` 在 web bundle 中**完全没有被读取**（`grep` 零命中）。

### 9.2 审批卡片（独立块，`ssn @3579711`）

触发：快照的 `pendingInteractions` 中 `payload.kind==='permission'`（`@3598137` 附近渲染 `<ssn …>`）。

卡片外观：`w-full overflow-hidden rounded-2xl border border-border bg-popover shadow-xs`，内层 `flex flex-col gap-3 p-3`。

卡片内**复用工具行**做只读预览（`@3582708`）：

```js
re = y==='edit' ? wXt
   : y==='mcp' ? osn
   : y==='skill' ? n3t
   : y==='search' ? UXt
   : y==='execute' ? PXt
   : aY;
```
- 预览行 `isPermissionPreview:true`、`kindLabelOverride='chat.permission.awaitingApproval'`（「等待确认 / Awaiting approval」）、`showInput:false/showOutput:false/showKind:false`；
- 展开策略 `rsn`：
  - `edit` → `{canToggle:false, forceOpen:false}`
  - `mcp | skill | search | execute | workflow | saveWorkflow | fallback` → `{canToggle:false, forceOpen:true}`（**强制展开**，直接把命令/参数摊开）
- 预览行图标 `Bon` = **wrench**（`@3574987`）。

### 9.3 按钮与回调

选项列表（`@3586000` 附近）：
- 每项一行：序号 `1.`（`w-5 text-ui-base font-medium`，选中 `text-foreground`，未选 `text-foreground-subtlest`）+ 选项文本；可带自由文本输入（`lo` 多行输入，`max-h-[5lh]`）。
- 底部：左侧 `info` 图标 + 「使用 Tab / 上下键选择，回车确认」（`chat.permission.keyboardHint`）；右侧主按钮 **「确认 / Confirm」**（`common.confirm`，`bg-brand text-foreground-inverse`，`disabled` 由选项是否可选决定）。
- 键盘：`Tab`/`↑↓` 切换、`Enter` 确认（`onKeyDown` 在 `@3582095` 起：数字 `1/2/3` 直选、`↑↓←→` 切换、`Tab` 循环、`Enter` 确认）。

选项文案（`IntlProvider`）：

| id | 中文 | English |
| --- | --- | --- |
| `chat.permission.approve` | 允许 | Allow |
| `chat.permission.approveAlways` | 始终允许 | Always allow |
| `chat.permission.deny` | 拒绝 | Deny |
| `chat.permission.denyAlways` | 始终拒绝 | Always deny |
| `chat.permission.allowCommand` | 始终允许此命令 | Always allow this command |
| `chat.permission.allowForProject` | （项目内始终允许） | — |
| `chat.permission.fullAccess` | （完全访问） | — |
| `chat.permission.title` | 需要权限 | Permission required |
| `chat.permission.awaitingApproval` | 等待确认 | Awaiting approval |

回调：`onRespond(...)` → 走 interaction 命令通道；失败显示「审批未完成，请重试。 / Approval did not complete. Please retry.」（`text-destructive`）。`allowOnce/allowAlways/rejectOnce/rejectAlways` 的 id 由 `Jon @3576323` 决定。

> 移植要点：审批 UI 与工具行是**两套组件**；工具行只负责「长什么样」，审批卡负责「能不能点」。

---

## 10. 与安卓移植相关的坑（从证据直接推出）

1. **不要给工具行做状态图标/转圈**：网页版没有；加了就不像。
2. **运行中唯一动效是 kindLabel 渐变扫光**（4s，300% 背景）；别把整行做脉冲。
3. **Read/Grep/Glob/Explore 共用放大镜**；Write/Edit/Changes 共用铅笔；未知工具用扳手。
4. **单行不显示耗时**；耗时只属于 workflow 卡片。
5. **Read/Search 行不可展开**（网页版），展开详情只对 shell/write/mcp/agent/todo/ask/workflow 等有效。
6. **默认全折叠，且没有「运行中自动展开」**；展开态按 toolId 记忆在内存里。
7. **分组必须「连续 + ≥2 条」**；单条退化为普通行；explore 组会吞掉只读 shell 命令（`cat/ls/grep/git status`…），但不吞写操作/重定向。
8. **组内缩进固定 8/8/1/14px**，子行不显示图标。
9. **审批按钮在独立卡片里**，卡片内嵌一行强制展开的只读工具预览。
10. **backgrounded/workId 不产生行内视觉差异**。

---

## 11. 未验证 / 留白

- `switch-mode`（`f3t`）不是工具行：它是计划卡片（`section.rounded-xl.border.border-card-border.bg-card`），头部 `notepad-text` 16px + 「Plan」+ 路径 chip + 复制按钮，正文 `max-h-64`（**256px**）带底部渐隐遮罩（`@3127066`）。
- `cuaGroup` 的完整事件流渲染（`Wqt`）只取证了头部：运行中 `icon:null` + kindLabel 隐藏 + `cua-group-gradient-text`（1s linear 无限，`CSS@341028` 之后的规则）；内部事件列表未逐行取证。
- 插件 UI 覆盖（`M0e`/`j0e`）走另一套渲染，未纳入本报告。
- `reasoning`/`assistantText` 的折叠规则见 `docs/chat/03-text-reasoning.md`，本文不重复。
- 所有「不存在」结论均基于 `index-BGa3KiRe.js` 字符串计数；若运行时存在条件分支未打包进 web 版，需以真机/桌面版复核。
