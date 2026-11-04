# 02 · 网页版对话时间线：turnHeader 与 userInput 一比一取证

取证对象：`E:\Zcode_android\archive\pre-3.16.1-auth\Reference_web\3.15.3\assets\index-BGa3KiRe.js`（6,592,643 B）与 `index-BD2BL4Cc.css`（455,343 B），辅助 `IntlProvider-C-26GNLk.js`、`validation-CqXZOLXO.js`、`src-AL-_ONNp.js`。
方法：只读 `node -e` 字节切片 + 偏移检索，未执行应用代码。偏移均为**字节偏移**，格式 `文件:偏移`。文末附 token 实测值。

---

## 0. 先纠正一个命名陷阱：`turnHeader` 行 ≠ 屏幕上那行“已工作”

- 时间线每个 turn unit 由 `Utn`（即 `Htn`）渲染：`index-BGa3KiRe.js:3395800`（`var Utn=(0,Q.memo)(Htn)`）、`:3387419`（`function Htn`）。
- `Htn` 把 `unit.header`（就是投影里的 `turnHeader` 行）当作**数据**使用，真正画出来的是 `Ftn`：
  - `Ftn` 定义：`index-BGa3KiRe.js:3377560`
  - 由 `Itn` 插入到流中：`index-BGa3KiRe.js:3379516`（`T=b&&y===x` 处，`x=flowItems.findIndex(e=>e.kind!=="userInput")`）
  - `Ltn` 负责把 `workSegments` 拆成多个 `Itn`：`index-BGa3KiRe.js:3382083`
- 通用行分发 `btn` 里确实有 `case 'turnHeader': return jsx(dtn,{row:e})`：`index-BGa3KiRe.js:3368297`；但 `dtn` 只是一个调试占位：
  ```jsx
  <TZ rowId={e.rowId}
      className="border-b border-[var(--color-border)] py-1 text-ui-sm text-[var(--color-foreground-subtle)]">
    turn · {e.origin} · {e.state}
  </TZ>
  ```
  定义 `index-BGa3KiRe.js:3360901`。正常时间线不会把 `turnHeader` 行送进 `kZ/btn`（`header` 被单独消费），因此**用户实际看到的是 `Ftn`，不是 `dtn`**。分享页更直接：`case 'turnHeader': return null`（`index-BGa3KiRe.js:3463721` 附近）。
- 投影 schema 确认字段：`validation-CqXZOLXO.js:177821`（`kind:'turnHeader'`、`origin:['userInput','backgroundResult','goalContinuation','editRerun','workflowLaunch']`、`state:['running','completedSuccess','completedInterrupted','failed']`、`workSegments`、`fileChanges{additions,deletions,files,state:'active'|'reverted'}`）。

---

## 1. turnHeader（实际渲染组件 `Ftn`）

### 1.1 DOM 结构与完整 class

源码：`index-BGa3KiRe.js:3377560`（`function Ftn({segment,open,highspeed,highspeedMeta,sourceCommandIds})`）。

```jsx
<div class={[
  "flex w-full flex-wrap items-center justify-between gap-2 border-b",
  highspeed
    ? "border-icon-purple/30 pb-3 pl-1"
    : "border-[var(--color-border)]/50 pb-2"
].join(" ")}>

  <CollapsibleTrigger asChild>              // U_ = Radix trigger，data-slot="collapsible-trigger"
    <button type="button"
      data-testid={Ba(Si, e.key)}
      data-history-open={String(open)}
      class="group/history-message inline-flex max-w-full items-center gap-2 text-left
             text-ui-base text-foreground-subtle
             focus-visible:outline-none focus-visible:ring-2
             focus-visible:ring-[var(--color-input-border-focused)]">

      <span class={["truncate", highspeed && "font-medium leading-5 text-icon-purple"].join(" ")}
            data-highspeed-duration={highspeed ? "true" : undefined}>
        {文案}
      </span>

      {!assistantHistoryDefaultOpen && (
        <ChevronRight aria-hidden
          class="size-4 shrink-0 text-[var(--color-foreground-subtlest)] opacity-70
                 transition-transform rotate-90|rotate-0" />
      )}
    </button>
  </CollapsibleTrigger>

  {highspeed && (
    <div class="ml-auto flex shrink-0 items-center gap-2 text-icon-purple">
      {savedDuration && (
        <span class="text-ui-sm font-medium leading-4" data-highspeed-saved-duration="true">
          节省约 {duration}
        </span>
      )}
      <span class="rounded-full bg-hover px-2 py-0.5 text-ui-sm leading-4"
            data-highspeed-status-tag="true">HighSpeed</span>
    </div>
  )}
</div>
```

- 外层 `border-b` 为 1px；普通态 `pb-2`（8px），HighSpeed 态 `pb-3`（12px）+ `pl-1`（4px）。
- `gap-2` = 8px；`flex-wrap` 允许 HighSpeed 右侧块换行。
- 文案字号 `text-ui-base` = 14px；未设置 `leading-*`，继承 `html{line-height:1.5}`（`index-BD2BL4Cc.css:20684`），即行高 21px。HighSpeed 时追加 `leading-5` = 20px。
- 颜色 `text-foreground-subtle`；HighSpeed 时 `text-icon-purple`。
- 折叠箭头：ChevronRight 16px（`size-4`），颜色 `--color-foreground-subtlest`，`opacity:.7`，`transition-transform`（默认 150ms `cubic-bezier(.4,0,.2,1)`，`index-BD2BL4Cc.css:10917`）；展开时 `rotate-90`，折叠时 `rotate-0`。
- 无状态图标、无失败图标、无 spinner；运行中仅靠文案（`工作中 …`）表达。
- `U_`/`H_`/`W_` 定义：`index-BGa3KiRe.js:735501` / `:735427` / `:735583`（`data-slot="collapsible-trigger"/"collapsible"/"collapsible-content"`）。

### 1.2 垂直间距（关键：header 在用户气泡与助手内容之间）

- `Itn` 的 Collapsible 根节点 class：
  `history-message flex flex-col [&>*+*:not([data-slot='collapsible-content'])]:mt-5`
  （`index-BGa3KiRe.js:3379516`）→ 任何“非第一个且非 collapsible-content”的直接子节点获得 `margin-top:20px`。header 属于此类。
- 因此：有用户气泡时，header 与用户气泡间距 **20px**；header 本身 `pb-2`（8px）+ 1px 下边框；header 之后的第一个历史块由 `W_` 内部 `pt-5`（20px）或兄弟 `mt-5` 提供 20px。
- 更外层 turn section：`px-4 @md/conversation:px-6 pb-5 pt-14`（16/24px 横向、20px 底、56px 顶；`ne` 为真时 `pt-0`）——`index-BGa3KiRe.js:3392393`（`data-turn-id` 在 `:3392293`）。
- 同级 `div.group/assistant-turn` 与 section 均为 `gap-5`（20px）。

### 1.3 文案模板（zh-CN / en）

生成逻辑：`index-BGa3KiRe.js:3377560`；时长格式化 `OZ`：`index-BGa3KiRe.js:3369557`；数字与单位拼接 `DZ`：`index-BGa3KiRe.js:3369468`。

| 状态 | 条件 | zh-CN | en |
|---|---|---|---|
| 运行中 | `workStatus.state==='running'` | `工作中 {duration}` | `Working for {duration}` |
| 完成且有耗时 | `state==='completed' && duration` | `已工作 {duration}` | `Worked for {duration}` |
| 完成且无耗时 | `state==='completed'` | `已处理` | `Worked` |
| 中断（普通） | `state==='interrupted'` | `已停止` | `Stopped` |
| 中断（机器人话题且 `interruptionReason==='newMessage'`） | `_lt(sourceCommandIds)` 为真 | `已被新消息中断` | `Interrupted by a new message` |
| 失败 | `state==='failed'` | **没有失败专用文案**，落到 `已工作 X` / `已处理` | 同左 |

- i18n 原文：`IntlProvider-C-26GNLk.js:222772`（zh）、`:620487`（en）。
- `_lt` 定义：`index-BGa3KiRe.js:1582116`（读取 bot-group topic 的 `progress.interruptionReason==='newMessage'`）。
- `workStatus.state` 只可能由 `Cnn` 产出 `running | interrupted | completed`：`index-BGa3KiRe.js:3406661`。`failed` 不会进入 `interrupted` 分支，所以**没有“失败”标签**。
- `executionKind==='controlOnly'` 时 `Cnn` 返回 `undefined`（`:3406661`）→ 该段**根本不渲染 header**（`Itn` 的 `b=workStatus!==undefined` 为 false）。
- `isInterrupted` 仅当 `header.state==='completedInterrupted'` 或会话阶段为 interrupted：`index-BGa3KiRe.js:3413059`（`Jnn`/`Ynn`）。

时长格式（`OZ`，`:3369557`）：
- `seconds = max(1, round(ms/1000))`；拆成 天/时/分/秒；只输出前两个非零单位；全为 0 时输出 `1 秒` 之类的最小秒数。
- zh-CN 数字与单位之间**有空格**：`1 分 20 秒`；en 无空格：`1m 20s`（`DZ`，`:3369468`）。
- 单位 i18n：`chat.history.duration.day/hour/minute/second` = `天/时/分/秒`、`d/h/m/s`（`IntlProvider-C-26GNLk.js:223050`、`:620847`）。

### 1.4 HighSpeed 变体

- 条件：`highspeed`（`Htn` 里由 `HN(...)`/`userInput.highspeed` 判定，`index-BGa3KiRe.js:3387419`）。
- 文案变紫、`font-medium`、`leading-5`；下边框 `border-icon-purple/30`；`pb-3 pl-1`。
- 右侧块：`节省约 {duration}`（`chat.highspeed.savedDuration`，`IntlProvider-C-26GNLk.js:324096`）+ 圆角胶囊 `HighSpeed`（`chat.highspeed.statusTag`，同处）。
- 整个 section 追加 `highspeed-animated-gradient-text` 与 `data-highspeed-gradient="true"`（`index-BGa3KiRe.js:3392339`、`:3392492`）。

### 1.5 `backgroundResult` 的替代头部（`Vtn`）

- 当 `header.origin==='backgroundResult'` 且 `originMeta.workId/title` 非空、`backgroundSource ∈ {bash,subagent,workflow}` 时，`ztn` 返回标题（`index-BGa3KiRe.js:3384532`），`Htn` 改走 `Vtn`（`:3384874`），**完全不渲染 `Ftn`**。
- `Vtn` 的标题行：
  ```jsx
  <div class="flex w-full border-b border-[var(--color-border)]/50 pb-2">
    <div data-testid=...
         class="min-w-0 whitespace-pre-wrap break-words text-left text-ui-base
                text-[var(--color-foreground-subtle)]">{title}</div>
  </div>
  ```
- 若存在 workflow 通知（`Btn`），则用 workflow 通知卡替代该标题行（`:3384874` 内 `h?...`）。
- 用户气泡仍照常先渲染（`Htn` 的 `d.map`）。

---

## 2. 点击折叠：折叠了什么、怎么动画、是否持久

源码：`Itn` `index-BGa3KiRe.js:3379516`；`W_` `:735583`。

### 2.1 折叠范围

- `H_`（Radix Collapsible）包住整个 segment 的 flow items，但只有被 `W_`（CollapsibleContent）包住的节点会随 `open=false` 隐藏。
- `W_` 只包两类内容：
  1. `assistantHistory` 分组（`jZ` 的 `historyContainer` 分支，`index-BGa3KiRe.js:3374873`）；
  2. `cuaGroup` 且 `flowKind==='assistantHistory'`（`index-BGa3KiRe.js:3379516` 内 `W_` 分支）。
- 因此折叠隐藏的是：**最终回答之前的推理（reasoning）与工具调用/探索/终端/变更分组等历史行**。
- 不折叠：用户气泡、`turnHeader` 本身、最终 `assistantText`、最终回答之后的 `assistantWork/assistantTailRows`、自动化卡片、`fileChanges` 卡、footer 操作条。
- 折叠后不显示摘要/预览；`W_` 直接由 Radix 隐藏，仅保留 header + 用户气泡 + 最终回答。

### 2.2 折叠后的可见内容

`用户气泡 → “已工作 5 秒 / 已处理 / 已停止” header → 最终助手回答 → （若有）文件变更卡/自动化卡/操作条`。
展开后，历史行插入在 header 与最终回答之间（`W_` 内层 `div.pt-5`，`:3374873`）。

### 2.3 动画参数

`W_` class（`index-BGa3KiRe.js:735583`）：

```
group/collapsible-content overflow-hidden
data-[state=open]:animate-collapsible-down
data-[state=closed]:animate-collapsible-up
data-[state=closed]:[animation-fill-mode:forwards]
transition-none duration-300 ease-in-out
```
内层：
```
transition-none duration-300 ease-in-out
group-data-[state=open]/collapsible-content:animate-in
group-data-[state=open]/collapsible-content:fade-in-0
group-data-[state=closed]/collapsible-content:animate-out
group-data-[state=closed]/collapsible-content:fade-out-0
group-data-[state=closed]/collapsible-content:[animation-fill-mode:forwards]
```

- 高度动画 keyframes：`collapsible-down` / `collapsible-up`，`0 ↔ var(--radix-collapsible-content-height)`（`index-BD2BL4Cc.css:453133`、`:453344`）。
- 实际时长/缓动：`duration-300`（`--tw-duration:.3s`，`index-BD2BL4Cc.css:180518`）+ `ease-in-out`（`cubic-bezier(.4,0,.2,1)`，`:10606`）→ **300ms ease-in-out**；内层同时 300ms 淡入/淡出；关闭时 fill-mode forwards。
- `transition-none` 保证不叠加 transition，只跑 keyframes。
- 未做 `prefers-reduced-motion` 降级（该类未出现 `motion-reduce`）。

### 2.4 默认开合与可折叠性

- `assistantHistoryDefaultOpen` 计算：`index-BGa3KiRe.js:3408106`（`Ann`）：
  `!timelineOnly && (forceOpenHistory || (isLastTurn && workStatus.state==='running') || (segmentCount===1 && 无可见最终回答 && 有历史行))`
- `forceOpenHistory = Jnn(...)`：turn 为 `completedInterrupted` / `failed` 或会话阶段 interrupted/error（`index-BGa3KiRe.js:3412939`）。
- `Itn` 内：`C = assistantHistoryDefaultOpen ? true : v`，`onOpenChange = assistantHistoryDefaultOpen ? undefined : y`（`:3379516`）。
  → **默认展开时不可折叠**（运行中、失败/中断、无最终回答），且不渲染折叠箭头（`e.assistantHistoryDefaultOpen ? null : <ChevronRight/>`）。
- 普通完成且有最终回答：默认折叠，可点击展开。

### 2.5 持久化

- 仅 `useState`（`let[v,y]=useState(e.assistantHistoryDefaultOpen)`），并用 `useEffect(()=>y(e.assistantHistoryDefaultOpen),[e.assistantHistoryDefaultOpen,e.key])` 在 segment key/默认值变化时重置（`index-BGa3KiRe.js:3379516`）。
- **不写 localStorage/sessionStorage，刷新即回到默认值**。
- 折叠状态通过 `data-history-open={String(open)}` 与 Radix 的 `data-state` 暴露给测试/CSS（`:735583`、`:3379516`）。

---

## 3. `fileChanges` 与 `workSegments`

### 3.1 `workSegments`：渲染，且每个 segment 一个 header

- `Ltn`：`v = workSegments.length>0 ? (length===1 ? [{...seg[0], assistantHistoryDefaultOpen}] : workSegments) : [单段回退]`，然后 `v.map(segment => <Itn segment=.../>)`（`index-BGa3KiRe.js:3382083`）。
- 每个 `Itn` 都插入自己的 `Ftn`（`:3379516`）。
- 分段触发：`guided===true` 的 userInput 会开新段（`Onn`，`index-BGa3KiRe.js:3407229`）。
- 每段耗时来源：`workSegments[i].activeMs` / `endedAt-startedAt` / 运行中 `nowMs-startedAt`；无分段数据时用相邻 trigger 的 `createdAt` 差值（`knn`，`:3407444`）。
- 因此多段 turn 会出现多行“已工作 …”，各自独立折叠。

### 3.2 `fileChanges`：不在 header 里，单独一张可折叠卡

- 调用点：`Htn` 内 `!l && e.header?.fileChanges ? <q8t header={e.header} .../> : null`（`index-BGa3KiRe.js:3393942`，`q8t` 定义 `:3181167`）。`l=TO()` 为 office 界面模式（`function TO`，`index-BGa3KiRe.js:1388395` 返回 `interfaceMode==='office'`），**office 模式隐藏**。
- 位置：在 turn section 的助手内容之后、`browserTurnEndRows` 之前；与前后卡片由 `gap-5`（20px）分隔。
- 结构（`index-BGa3KiRe.js:3183550` 起）：
  ```jsx
  <Collapsible class="overflow-hidden rounded-xl border border-border bg-card shadow-none">
    <div class="flex h-10 items-center justify-between gap-3 px-2 transition-colors hover:bg-hover">
      <CollapsibleTrigger asChild>
        <button class="flex h-full min-w-0 flex-1 items-center gap-2 px-1 text-left
                       text-ui-base text-foreground ...">
          <ChevronRight class="size-3.5 shrink-0 text-foreground-subtlest transition-transform rotate-90|rotate-0"/>
          <span class="min-w-0 truncate font-medium">{N} 个文件已更改</span>
          <span class="shrink-0 tabular-nums">
            <span class="text-diff-added">+{additions}</span>
            <span class="text-diff-removed">-{deletions}</span>
          </span>
          {reverted && <span class="shrink-0 rounded-sm bg-input px-1.5 py-0.5 text-ui-xs
                                     text-foreground-subtle">已撤销</span>}
        </button>
      </CollapsibleTrigger>
      <Button variant="ghost" size="sm" disabled={!canRewindFiles}>
        <UndoIcon/> <span>撤销</span>
      </Button>
    </div>
    <CollapsibleContent>
      <div class="grid w-full border-t border-border">
        {每文件：<div class="w-full bg-background/50 overflow-hidden">
          <div class="flex w-full items-center gap-1 px-2 py-2 ...">
            文件路径（文件名 font-medium + 路径 foreground-subtlest）
            <span class="tabular-nums"><span class="text-diff-added">+n</span>
                                     <span class="text-diff-removed">-n</span></span>
            <Button variant="outline">审查</Button>
          </div>
        </div>}
      </div>
    </CollapsibleContent>
  </Collapsible>
  ```
- 文案：
  - `{count} 个文件已更改`：`chat.changeSummary.filesChanged.one/other`（zh 两者同文，`IntlProvider-C-26GNLk.js:91287`）。
  - 展开/收起 aria：`展开已更改文件` / `收起已更改文件`。
  - `已撤销`：`chat.changeSummary.reverted`；按钮 `撤销`：`chat.changeSummary.rewind`；行按钮 `审查`：`chat.changeSummary.review`（同段 i18n）。
  - diff 不可用：`暂时无法预览这份 Diff。`（`chat.changeSummary.diffUnavailable`）。
- 颜色：`+` `text-diff-added`、`-` `text-diff-removed`；卡片 `bg-card`、`border-border`、`rounded-xl`（12px）。
- 条件：`header.fileChanges.files<=0` 直接不渲染；`state==='reverted'` 显示“已撤销”徽标；`canRewindFiles` 控制撤销按钮可用性。
- `dtn` 不渲染 `fileChanges`/`workSegments`（`:3360901` 只有 `turn · origin · state`）。

---

## 4. userInput 气泡

### 4.1 组件链

- 行分发：`btn` 的 `case 'userInput': <otn .../>`（`index-BGa3KiRe.js:3368297`）。
- `otn`：若 `men(row)` 返回多条（bot-group topic 多消息），包一层
  `<div data-v4-topic-messages="true" class="flex min-w-0 flex-col gap-3">`，每条一个
  `<div data-topic-message-id=...><atn .../></div>`；否则直接 `<atn/>`（`:3355370`；`men` 定义 `:3313637`）。
- `atn`：`:3346698`。编辑态返回 `TZ class="flex flex-col items-end"` + 内嵌 composer（`EAt`，`w-full max-w-xl`、`shellClassName="min-h-32"`）；非编辑态返回下述只读结构。

### 4.2 只读 DOM（右对齐）

```jsx
<div data-row-id={rowId} data-testid=...
     class="group/user-row flex flex-col items-end">

  {/* 定时/自动化来源 */}
  {isAutomation && (
    <div data-v4-user-input-automation-origin="schedule"
         class="mb-1 flex max-w-xl items-center gap-1 text-ui-sm text-foreground-subtle">
      <CronIcon class="size-3.5 shrink-0"/>
      <span>由定时任务发送</span>
    </div>
  )}

  {/* bot group 发送者 */}
  {botGroupSource && (
    <div data-v4-user-input-source="true" class="mb-2 flex min-w-0 max-w-full">
      <Gzt source={botGroupSource}/>   {/* “Lark · 昵称”，12px subtle，hover 变 foreground */}
    </div>
  )}

  {/* 附件区 */}
  {hasAttachmentsOrContext && (
    <div data-v4-user-input-attachments="true"
         class="mb-2 flex max-w-xl flex-col items-end gap-2">
      {quotes && <div data-v4-user-input-quotes="true" class="flex max-w-full justify-end">…</div>}
      {media && (
        <div data-v4-user-input-media-attachments="true"
             class="flex max-w-full flex-wrap justify-end gap-2">
          <EZ attachmentKind="media" directItems .../>
        </div>
      )}
      {pills && (
        <div data-v4-user-input-attachment-pills="true"
             class="flex max-w-full flex-wrap justify-end gap-2">
          <EZ attachmentKind="file" directItems .../>
          <CodeCommentChips/> <WebElementChips/> <PptxChips/> <QuoteChips/> <PluginUiChips/>
        </div>
      )}
    </div>
  )}

  {/* 气泡 */}
  <div data-v4-user-input-bubble="true"
       class="flex max-w-full flex-col gap-2 rounded-xl rounded-tr-xs
              border border-border bg-surface px-4 py-3
              text-ui-base text-foreground
              @min-[624px]/conversation:max-w-xl">
    <div data-conversation-selectable="true" class="relative min-w-0 flex-1">
      <div data-v4-user-input-collapsible-content="true"
           style={{maxHeight: "120px" | `${max(scrollHeight,120)}px`}}
           class="min-w-0 overflow-hidden whitespace-pre-wrap break-words
                  transition-[max-height] duration-300 ease-out motion-reduce:transition-none
                  [mask-image:linear-gradient(to_bottom,black_0%,black_70%,transparent_100%)]">
        {正文}
      </div>
      {overflow && <展开/收起圆形按钮/>}
    </div>
    {epilogue && <工作流引擎附加说明（可展开 pre）/>}
  </div>

  {source.kind==='genUi'   && <div data-v4-user-input-source="genUi"
      class="mt-1 text-right text-ui-sm text-foreground-subtlest">来自交互视图 {name}</div>}
  {source.kind==='pluginUi'&& <div data-v4-user-input-source={pluginId}
      class="mt-1 text-right text-ui-sm text-foreground-subtlest">来自插件 {name}</div>}
  {status && <div data-v4-user-input-status="true" aria-live="polite"
      class="mt-1 text-right text-ui-sm text-foreground-subtlest">{status}</div>}

  {/* hover 操作 */}
  <div class="mt-1 flex items-center gap-1
              opacity-0 transition-opacity
              group-hover/user-row:opacity-100 focus-within:opacity-100">
    <CopyButton/> {canEdit && <EditButton/>}
  </div>
</div>
```

源码锚点：根 `group/user-row flex flex-col items-end` `:3352150`；附件块 `data-v4-user-input-attachments` `:3352743`；媒体块 `:3353090`；pills 块 `:3353447`；genUi/pluginUi 标签 `:3354355`/`:3354514`；status `:3354732`；操作条 `:3354973`；气泡 `bKt` `:2760524`。

### 4.3 气泡度量（实测 token）

> class 合并规则：`J(...)` 不是简单 clsx，而是 `twMerge(clsx(...))`，并对 `font-size` 组注册了 `text-ui-xl/lg/base/caption/sm/xs/mobile-input-safe`（`index-BGa3KiRe.js:67111` 的 `import{t as J}from"./utils-ZHnEKfov.js"`；`utils-ZHnEKfov.js:1`）。因此冲突类**后写覆盖前写**（例如 pill 的 `rounded-full` 覆盖基础 `rounded-md`、`bg-surface` 覆盖 `bg-background`、`border-0` 覆盖 `border`），不要按 CSS 源顺序推断。

| 项 | 值 | 依据 |
|---|---|---|
| 对齐 | 右对齐（父级 `items-end`） | `index-BGa3KiRe.js:3352150` |
| 最大宽度 | 容器 <624px：`max-w-full`；≥624px：`max-w-xl` = 36rem = **576px** | `:2760524`；`--container-xl:36rem` `index-BD2BL4Cc.css:9267` |
| 圆角 | 左上 12px、右上 **2px**、右下 12px、左下 12px | `rounded-xl` `index-BD2BL4Cc.css:88060`；`rounded-tr-xs` `:89370`；`--radius-xl:.75rem` `:10324`；`--radius-xs:.125rem` `:10247` |
| 背景 | `bg-surface` | `index-BD2BL4Cc.css:119328` |
| 明色背景 | `--color-surface:#0a0a0a08`（neutral-950 3% 透明）叠在 `neutral-50` 上 | `index-BD2BL4Cc.css:13541`、`:11546` |
| 暗色背景 | `.dark --color-surface:#ffffff0d`（white 5%）叠在 `neutral-900` 上 | `index-BD2BL4Cc.css:399924`、`:398030` |
| 边框 | 1px `border-border`；明 `#0a0a0a1a`（10%），暗 `#fafafa1a`（10%） | `:12844`、`:399269` |
| 内边距 | 水平 16px（`px-4`）、垂直 12px（`py-3`） | `:136435`、`:137236` |
| 内部 gap | 8px（正文与 epilogue 之间） | `.gap-2` `:81100` |
| 字体 | `--font-sans` = `ui-sans-serif, system-ui, sans-serif, "Apple Color Emoji", …` | `index-BD2BL4Cc.css:3604` |
| 字号 | 14px（`text-ui-base`；`--ui-font-size:14px`） | `:396924`、`:143553` |
| 行高 | 继承 `html{line-height:1.5}` → 21px；命令/文件等内联片段用 `leading-6`（24px） | `:20684`；`_Kt` `index-BGa3KiRe.js:2758295` |
| 文字色 | `text-foreground`：明 neutral-700，暗 neutral-200 | `:18115`、`:405851` |

### 4.4 换行与超长折叠

- 正文：`whitespace-pre-wrap break-words`（保留 `\n`，长词/URL 断行）——`index-BGa3KiRe.js:2755536`。
- 超长：`scrollHeight > 121px` 即折叠；折叠高度固定 **120px**；底部 30% 用线性渐变遮罩淡出；展开后高度 = `max(scrollHeight,120)px`。
  - 阈值/高度：`iKt=1`、`aKt(e){return e>120+1}`、`oKt(e,t){return `${e?Math.max(t,120):120}px`}`（`index-BGa3KiRe.js:2754550` 附近）。
  - 过渡：`transition-[max-height] duration-300 ease-out` + `motion-reduce:transition-none`（`:2755536`）。
  - 按钮：`variant=outline size=sm`，覆盖为 `rounded-full bg-background shadow-sm backdrop-blur-sm`；折叠时 `absolute inset-x-0 bottom-0 mx-auto`，展开时 `w-full pt-2`；图标 ChevronDown/Up 16px；aria-label `展开`/`收起`（`chat.message.expand/collapse`，`IntlProvider-C-26GNLk.js:221958`）。
  - 展开状态 `u` 在 `[text,rowId]` 变化时重置为 false（`:2754550` 的 `useEffect`），**不持久化**。
- 空文本：若 `text.trim()` 为空且无 epilogue，`bKt` 直接返回 `null`，只显示附件（`:2760524`）。

### 4.5 epilogue（`epilogueStart` 之后的工作流说明）

- `tKt(text, epilogueStart)` 拆成 body + epilogue（`index-BGa3KiRe.js:2753497`）。
- epilogue 在气泡内、正文下方：
  - 触发按钮：`flex items-center gap-1 self-start rounded-md text-ui-xs text-foreground-subtlest hover:text-foreground`，Chevron 12px，文案 `工作流引擎附加说明`（`chat.userInput.epilogue.label`，`IntlProvider-C-26GNLk.js:221958`）。
  - 展开内容：`pre.max-h-60 overflow-auto whitespace-pre-wrap break-words font-mono text-ui-sm text-foreground-subtle`，`data-v4-user-input-epilogue-body`（`index-BGa3KiRe.js:2753670`）。

### 4.6 bot-group 多消息

- `men(row)` 把 `botGroupSource.messages` 拆成多条气泡，外层 `flex min-w-0 flex-col gap-3`（12px），每条独立 `atn` 且 `onEdit=undefined`（`index-BGa3KiRe.js:3313637`、`:3355370`）。
- 每条气泡上方仍有自己的发送者标签（`Gzt`，`mb-2`）。

---

## 5. 附件渲染

组件：`EZ`（`index-BGa3KiRe.js:3336900`）；基础件 `o2e/s2e/c2e/u2e`（`:1173793`/`:1174084`/`:1175362`/`:1177146`）。

### 5.1 图片/视频缩略图（只读）

- 容器：`data-v4-user-input-media-attachments`，`flex max-w-full flex-wrap justify-end gap-2` → **间距 8px**，右对齐换行（`:3353090`）。
- 单项（`attachmentKind="media"`，`v=false`）：
  `relative size-20 overflow-hidden rounded-xl bg-surface p-0
   after:absolute after:inset-0 after:rounded-xl after:border after:border-border
   hover:bg-surface-hover`
  → **80×80px（size-20）、圆角 12px、1px 边框（伪元素）、背景 surface**（`:3336900` 的 `className` 分支；`.size-20` `index-BD2BL4Cc.css:54402`）。
- 图片 `size-full object-cover`；视频额外覆盖 28px 圆形播放按钮 `bg-black/55 text-white backdrop-blur-sm`（`c2e`，`:1175362`）。
- 点击打开预览（`s2e` 为 `role=button tabindex=0`，aria-label `打开图片预览` / `打开视频预览`，i18n `chat.attachments.preview.open*`，`IntlProvider-C-26GNLk.js:274811`）。
- 编辑态（`attachmentKind="all"`）：同一图片变为 **48×48px（size-12）+ 圆角 8px（rounded-lg）**（`:3336900` 的 `v?` 分支）。

### 5.2 文件 pill（只读）

- 容器：`data-v4-user-input-attachment-pills`，`flex max-w-full flex-wrap justify-end gap-2`（`:3353447`）。
- 单项（`attachmentKind="file"`，`variant="inline"`）class：
  `group relative flex h-8 select-none items-center gap-0.5 overflow-hidden rounded-full
   border-0 bg-surface px-3 py-1.5 font-medium text-ui-base transition-all
   bg-surface hover:bg-surface-hover`
  → **高 32px、全圆角、无边框、背景 surface、左右 12px / 上下 6px**（`:3336900`；基础 inline 样式 `:1174084`）。
- 内部：文件类型图标 16px（`yg`，`iconSize:16`）+ 文件名
  `inline-flex min-w-0 max-w-40 items-center gap-1.5`，文件名 `truncate text-ui-base font-medium text-foreground` → **名称最大 160px（max-w-40）**。
- 特殊来源：
  - `topic-history`：不显示文件名，显示 `话题历史 · {count} 条消息`（`chat.attachments.topicHistory`，`IntlProvider-C-26GNLk.js:274811`）。
  - `clipboard-text` 等：同 pill。
- 点击可打开预览（文本/PDF/图片）。

### 5.3 文件 pill（编辑态）

- class：`h-12 w-fit max-w-full min-w-0 gap-2 rounded-lg border border-border bg-surface p-1.5 pr-6`
  → **高 48px、圆角 8px、1px 边框、内边距 6px（右 24px）**。
- 结构：左侧 36px（`size-9`）圆角 6px（`rounded-md`）`bg-background` 图标盒（16px 图标）+ 右侧两行：
  文件名 `text-ui-base font-medium text-foreground`；扩展名/MIME `text-ui-sm text-foreground-subtle`。
- 删除按钮：`absolute top-0.5 right-0.5 z-10 size-3.5 rounded-full p-0 text-primary-foreground
  opacity-0 group-hover:opacity-100 focus-visible:opacity-100 [@media(hover:none)]:opacity-100`
  → **14px 圆形、右上角、hover/触摸设备常显**；X 图标 10px（`:3336900`）。

### 5.4 数量折叠

- **没有“+N / 更多”折叠**：媒体块与 pill 块都是 `flex-wrap`，全部平铺换行；`EZ` 内没有 `slice(0,N)` 或 `more` 计数（`:3336900`–`:3346700` 区间检索无命中）。
- 编辑态/发送端有附件数量上限的提示文案（`chat.attachments.maxFiles`，`IntlProvider-C-26GNLk.js:273887`），但**只读时间线渲染不做数量截断**。

### 5.5 附件区顺序

1. `conversationQuotes`（引用块，`data-v4-user-input-quotes`，仅 botGroupSource 时在媒体块之前）
2. 图片/视频（媒体块）
3. 文件 pill
4. 代码评论 chips
5. 网页元素 chips
6. PPTX chips
7. 对话引用 chips（非 botGroupSource 时）
8. 插件 UI 上下文 chips

（`index-BGa3KiRe.js:3352743`–`:3354200`）

---

## 6. `origin` 是否影响外观

结论：**`atn` 完全不读取 `row.origin` / `row.originMeta` / `row.inputOrigin`**（在 `:3346698`–`:3355370` 区间检索 `origin` 仅命中 `data-v4-user-input-automation-origin` 这个属性名）。origin 的差异只发生在**外层 turn 组装/过滤**。

| origin | 外观/行为 | 依据 |
|---|---|---|
| `realUser` | 无差异；仅用于 turn navigator 计数、分享选择、跟随判定 | `:1121950`（B1e）、`:1122749`（H1e）、`:3391061`、`:3397649` |
| `backgroundResult` | 用户气泡仍正常显示；若 `originMeta.backgroundSource ∈ {bash,subagent,workflow}` 且有 title，则 turn 头部改为 `Vtn` 的纯标题行（无“已工作”行） | `:3384532`、`:3384874`、`:3392293` |
| `goalContinuation` | **无任何渲染分支**；字符串只出现在投影 schema | `validation-CqXZOLXO.js:177821` |
| `mailbox` | **无渲染分支**；i18n `chat.message.mailbox.from`（`来自 {sessionId} 的新消息`）在本 bundle 中无引用 | `IntlProvider-C-26GNLk.js:221908`；全量检索无使用 |
| `synthetic` | **无渲染分支**（bundle 内 `synthetic` 均与模型/压缩相关，非 userInput origin） | `index-BGa3KiRe.js:1329403`、`:1360323` |
| `workflowLaunch` | 该 userInput 会被从可见流中**过滤掉**（若 header 或任一 userInput 带 `workflowLaunch`），改由工作流启动/完成卡与 digest 呈现 | `gnn/_nn` `:3405613`/`:3405741`；`Ynn` `:3413059` |

另外三种**非 origin 的来源标签**（会改变外观）：

1. **定时/自动化任务**：`Xre(sourceCommandId)` 命中 `automationId:schedule:<ts>` 或 `automationId:manual:` 时，在气泡上方显示
   `<div data-v4-user-input-automation-origin="schedule" class="mb-1 flex max-w-xl items-center gap-1 text-ui-sm text-foreground-subtle">`
   + 定时图标 14px + `由定时任务发送`（`chat.message.sentByScheduledTask`，`IntlProvider-C-26GNLk.js:220871`）。
   `Xre` = `src-AL-_ONNp.js:48991`（`Yl`），import 别名见 `index-BGa3KiRe.js:65700` 附近。
2. **bot group 发送者**：`data-v4-user-input-source="true"`，`mb-2 flex min-w-0 max-w-full`，内容 `Lark/飞书 · 昵称`，`text-ui-sm text-foreground-subtle`，hover 变 `foreground`，带 sender 详情 tooltip（`Gzt`，`index-BGa3KiRe.js:2565154`）。
3. **genUi / pluginUi**：气泡下方 `mt-1 text-right text-ui-sm text-foreground-subtlest`，文案 `来自交互视图 {name}` / `来自插件 {name}`（`index-BGa3KiRe.js:3354355`、`:3354514`；i18n `IntlProvider-C-26GNLk.js:49830`）。

`inputOrigin: 'desktop' | 'mobile'` 在渲染层**未被读取**。

---

## 7. hover / 长按操作

### 7.1 行 hover 操作条（userInput）

- 位置：气泡（及 source/status 标签）之后，父级右对齐；`mt-1`（4px）。
- 容器：`iN` = `flex items-center gap-1`（`index-BGa3KiRe.js:1690133`）。
- 可见性：`opacity-0 transition-opacity group-hover/user-row:opacity-100 focus-within:opacity-100`
  （`index-BGa3KiRe.js:3354973`）；`compactForRemoteControl` 时改为 `opacity-100`（同处 `J(...)` 分支）。
- 按钮：`aN` = ghost `size=icon-sm`（**28×28px**，`index-BD2BL4Cc.css` 对应 Button 尺寸；`icon-sm: size-7 p-0` 见 `index-BGa3KiRe.js:1481699` 的同类映射），tooltip `side="bottom"`（`:1690245`）。
- 只有两个动作：
  1. **复制**：`ntn`（`index-BGa3KiRe.js:3336036`），Copy 图标 14px（`size-3.5`），label `复制`（`chat.message.copy`）；成功后替换为 Check 图标 `text-success`，**1200ms** 后复原。
  2. **编辑**：仅当 `onEdit` 存在且 `row.entityId` 存在时渲染（`:3354973`），图标 14px，label `编辑`（`chat.message.edit`）。点击后 `atn` 切换到内嵌编辑器（`EAt`，`max-w-xl`、`min-h-32`），并带 `对话 + 文件重置` 按钮（`chat.edit.resetConversationAndFiles`，tooltip `与文件一起重置`，`IntlProvider-C-26GNLk.js:92936`）。
- **没有**重试、fork、引用、更多菜单（无 `…` 按钮、无 dropdown）。
- **没有自定义长按/右键菜单**：`atn` 内无 `onContextMenu` / `onPointerDown` 长按计时器。
- 移动端：操作条默认 `opacity-0` 且没有 `[@media(hover:none)]:opacity-100`，只有 `compactForRemoteControl` 才常显。

### 7.2 “引用”的真实入口：文本选择浮动工具条

- 气泡正文带 `data-conversation-selectable="true"`（`sKt`，`index-BGa3KiRe.js:2754550`）。
- 在时间线内选中文本后，`Irn` 渲染 portal 工具条（`index-BGa3KiRe.js:3426256`）：
  `fixed z-50 flex overflow-hidden rounded-lg border border-popover-border bg-menu text-ui-sm text-foreground shadow-md`，宽度 `max-content`，`max-width:min(576, innerWidth-24)`。
- 两个按钮 + 中间 1px 分隔线：
  - `添加到当前任务`（`chat.selections.addToTask`）
  - `在辅助对话中提问`（`chat.selections.askInSideChat`，禁用时 50% 透明）
- 超长选区显示警告 `单条引用最多 8,000 个字符。`（`chat.selections.limit.single`，`IntlProvider-C-26GNLk.js:48599`）。
- 该工具条是文本选择触发（桌面拖选、移动端长按选择），不是行级长按菜单。

### 7.3 turnHeader 的交互

- 仅一个动作：点击整行 header 切换折叠（见 §2）。
- 无 hover 变色、无复制/编辑/重试/fork；焦点态为 2px `input-border-focused` ring。
- HighSpeed 右侧 `节省约…/HighSpeed` 不是按钮。

---

## 8. 时间戳与日期分隔线

- **userInput 行不显示时间戳**：`atn` 不读取 `row.createdAt`（`:3346698`–`:3355370` 检索无 `createdAt`）；turn section 也不渲染日期。
- **主时间线没有日期分隔线**：全 bundle 无 `dateDivider/dayDivider/isSameDay/toDateString`；`dateSeparator` 仅出现在 pdf.js 布局（`index-BGa3KiRe.js:5403960`），与聊天无关。
- 唯一的时间显示在**助手操作条**（`ctn`，`index-BGa3KiRe.js:3355759`）：
  - 末尾 `<span class="select-none text-ui-sm text-foreground-subtlest">{time}</span>`；
  - 格式 `etn`（`:3334763`）：当天 `HH:mm`；昨天 `昨天 HH:mm`（`chat.message.time.yesterday`）；同年 `M/D HH:mm`；跨年 `YYYY/M/D HH:mm`（`Intl.DateTimeFormat`，`:3334763`）。
  - 该操作条默认 `opacity-0`，仅在助手行 hover / `focus-within` 时出现（`index-BGa3KiRe.js:3358302` 的 `className`）。
- `timelineMarker`（`gtn`，`:3361558`）是唯一的分隔式行：左右 `h-px flex-1 bg-border/50` 细线 + 居中图标 14px + 文案，`px-4 py-2 text-ui-base text-foreground-subtle`，运行态文案用 `animated-gradient-text font-medium`，可点击时 hover 变 `foreground` 并加下划线。类型包括 `compact / forkNotice / modelChange / goalVerify`（`_tn`，`:3362832`），**没有日期型 marker**。

---

## 9. token 实测值（明/暗）

| token | 明色 | 暗色 | 偏移 |
|---|---|---|---|
| `--color-background` | `neutral-50` = `oklch(98.5% 0 0)` | `neutral-900` = `oklch(20.5% 0 0)` | `index-BD2BL4Cc.css:11546` / `:398030` |
| `--color-surface` | `#0a0a0a08`（3%） | `#ffffff0d`（5%） | `:13541` / `:399924` |
| `--color-surface-hover` | `#0a0a0a0d`（5%） | `#ffffff1a`（10%） | `:13625` / `:400002` |
| `--color-border` | `#0a0a0a1a`（10%） | `#fafafa1a`（10%） | `:12844` / `:399269` |
| `--color-border-hover` | `#0a0a0a33`（20%） | `#fafafa4d`（30%） | `:12927` / `:399430` |
| `--color-foreground` | `neutral-700` = `oklch(37.1% 0 0)` | `neutral-200` = `oklch(92.2% 0 0)` | `:18115` / `:405851` |
| `--color-foreground-subtle` | `#40404099`（60%） | `#e5e5e599`（60%） | `:18159` / `:405895` |
| `--color-foreground-subtlest` | `#40404066`（40%） | `#e5e5e54d`（30%） | `:18253` / `:406079` |
| `--color-card` | `#fff` | `neutral-800` = `oklch(26.9% 0 0)` | `:13949` / `:400251` |
| `--color-hover` | `neutral-200` = `oklch(92.2% 0 0)` | `surface-hover` = `#ffffff1a` | `:13107` / `:399603` |
| `--color-icon-purple` | `fuchsia-500` = `oklch(66.7% .295 322.15)` | `fuchsia-600` = `oklch(59.1% .293 322.896)` | `:11918` / `:402007` |
| `--color-diff-added` | `green-600` = `oklch(62.7% .194 149.214)` | `green-500` = `oklch(72.3% .219 149.579)` | `:19076` / `:406922` |
| `--color-diff-removed` | `red-600` = `oklch(57.7% .245 27.325)` | `red-500` = `oklch(63.7% .237 25.331)` | `:19118` / `:407026` |

其他度量：
- `--spacing: .25rem`（4px）`index-BD2BL4Cc.css:9267`；`--container-xl: 36rem` 同处。
- `--radius-xs: .125rem`、`--radius-sm: .25rem`、`--radius-md: .375rem`、`--radius-lg: .5rem`、`--radius-xl: .75rem`：`:10247`–`:10324`。
- `--ui-font-size: 14px`：`:396924`；`text-ui-base=14`、`text-ui-sm=12`、`text-ui-xs=10`：`:11099`–`:11341`。
- `html{line-height:1.5}`：`:20684`。
- 默认 transition：`.15s cubic-bezier(.4,0,.2,1)`：`:10917`；`duration-300`：`:180518`；`--ease-in-out: cubic-bezier(.4,0,.2,1)`：`:10606`。
- `collapsible-down/up` keyframes：`:453133` / `:453344`。

---

## 10. 未验证 / 边界

- 未在真实浏览器中运行验证像素值；以上为 class + token 的静态推导（rem 按 16px 计算）。若应用根字号被用户改大，`rem` 类尺寸会同比放大，而 `--ui-font-size` 固定 14px。
- `--color-surface` 为半透明，最终视觉色取决于叠放的页面底色；表中给的是原始 alpha 值。
- `dtn`（`turn · origin · state`）是否在极端回退路径中出现未做运行时验证；正常 unit 组装（`Htn`）不会把 `turnHeader` 行交给 `kZ/btn`。
- 失败态（`state:'failed'`）没有专用文案，这是代码事实；是否有其他上层逻辑把 failed 改写为 `completedInterrupted` 未进一步追踪。
