# 09 · 工具分组（ToolGroupRow）展开布局审计（web 3.17.1）

> 范围：对话时间线里 explore / terminal / changes 分组**展开后**的组头、组内容缩进与竖线、子行（ToolCallRow）间距与样式；顺带核对组头 summary 文案 / 图标 / chevron。
> 对照产物：`Reference_web/3.17.1/assets/index-Dwhx77cK.js`（下称 JS）、`index-BgVjt5KE.css`（下称 CSS）、`IntlProvider-CneXitqp.js`（下称 Intl）。
> 当前实现：`android/app/src/main/kotlin/app/zemote/ui/chat/timeline/ToolGroupRow.kt`、`ToolCallRow.kt`、`RowComponents.kt`、`Timeline.kt`、`ui/theme/Dimens.kt`、`Type.kt`。
> 口径：CSS px → Android dp **1:1**；字号按 sp、fontScale = 1；默认主题。px 与 dp 在本文中可互换，只有 sp 受系统字号缩放。
> 约束：只读审计，未改任何源码；所有“当前值”由代码语义推导。

---

## 0. 结论（先看这个）

1. **用户报告的“第一子项与组头间距不对”属实，且是少了 8dp**：web 的组内容是 `pt-2` = **8px**（`JS@1142600`、`JS@1146455`），当前 `ToolGroupRow.kt:127-138` 的组内容 Column **没有任何 top padding** → 组头与第一条子行间距 **0dp**，差值 **−8dp**。
2. **同一个 8px 也是左侧竖线的起点**：web 竖线从“组头底部 + 8px”开始；当前 `drawBehind` 从组头底部开始 → **竖线向上多出 8dp**，视觉上像挂在组头下沿。修 top padding 时**必须把 padding 放在 `drawBehind` 之前**（见 §6 F1）。
3. **子行文本缩进少 1dp**：web = `ml-2` 8 + `border-l` 1 + `pl-3.5` 14 = **23px**；当前 = `padding(start=8)` + `padding(start=14)` = **22dp**。1px 竖线是 `drawBehind` 画的，**没有占布局宽度**。`ToolGroupRow.kt:72-73` 的 kdoc 写“文字左缘落在 23dp”，与实现不符。
4. **子行之间 8dp 是对的**（web `space-y-2` = 8px；`ToolGroupRow.kt:147` 的 `Spacer(8.dp)` 等价）；但**上一子行展开时** web 是 8（终端的 `mb-2`）+ 8（`space-y-2`）= **16px**，当前只有 8dp。最后一条展开子行到组底同理差 8dp（`ShellDetails` 缺 `mb-2`）。
5. **组头图标**：web 16px、有效色 `foreground-subtlest`；当前 16dp、`foregroundSubtle`（更深）、`offset(y=1.dp)`。尺寸对，**颜色/图标形状/垂直位置有偏差**。
6. **组头 chevron**：web 16px、`inline-flex self-start`（紧跟摘要文本，8px 间距）、hover/展开才显形；当前 14dp、被 `weight(1f)` 顶到最右、常显。**尺寸 −2dp + 位置不同**。
7. **组头 summary 文案与 web 有实质差异**：web 用英文逗号 + 空格 `, `；explore 分 search/list/file/fetch 四个桶；execute 有“已停止”计数；changes 有文件 chips 与运行中 diffCount。当前是中文逗号、缺桶、缺停止计数、缺 chips。运行中组头 web 显示“正在执行/最后动作”，当前仍显示计数。
8. **组默认展开违背 web**：3.17.1 所有组默认**折叠**（`aw.get(R)??!1`，`JS@1143360`），当前 explore/terminal 的 `defaultOpen=true`（`ToolGroupRow.kt:44-45`），`Timeline.kt:368` 直接采用 → 首次进入就展开。
9. **动画时长/缓动与 web 不符**：当前组/工具内容用 **200ms ease-out**；3.17.1 的 `nx` 实际是 **300ms ease-in-out**（`nx` 类里显式的 `duration-300 ease-in-out` 覆盖了 keyframe 的 `.2s` fallback，`JS@994252`、`CSS@192545`、`CSS@193002`、`CSS@277125`）。chevron 旋转的 **200ms ease-out 才是对的**（`JS@1142036`）。
10. **不会因为“全高绘制 + 裁剪”产生残影/跳动**：`CollapsibleContent` 用全高测量、顶部对齐、外层裁剪，与 CSS 的 height 动画同构；修正后的 8dp `pt-2` 在首帧就完整存在（被裁剪的是底部），不会被动画缩放。fade 也不是多余：web 的 `nx` 内层 `div` 本身有 300ms fade-in/out（`JS@994252`）。

---

## 1. web 原始证据（不引用文档表格，直接读产物）

### 1.1 JS（index-Dwhx77cK.js，字节偏移）

| 事实 | 证据（偏移 + 原文片段） |
| --- | --- |
| 组内容缩进常量 | `JS@4267724`：``var jgn=`ml-2 space-y-2 border-border border-l pl-3.5 border-border` `` |
| explore 组 renderContent | `JS@4059733`：`className:'ml-2 space-y-2 border-border border-l pl-3.5 border-border'`；同段 `JS@4059869`：`showIcon:!1` |
| changes 组 renderContent | `JS@4075050`：同上（末尾少一个 `border-border`，无影响）；`JS@4075228`：`showIcon:!1` |
| execute 组 renderContent | `JS@4180639`：同上；`JS@4180761`：`showIcon:!1` |
| ToolLayout 内容包裹 | `JS@1142600`：``v0e=`pt-2` ``；`JS@1146455`：`className:v0e` 包在 `nx` 里 → 所有 renderContent 前都有 **8px** |
| `nx`（CollapsibleContent） | `JS@994252`：`overflow-hidden data-[state=open]:animate-collapsible-down ... transition-none duration-300 ease-in-out` + `data-[state=open]:[&>div]:animate-in ... fade-in-0` / `data-[state=closed]:[&>div]:animate-out ... fade-out-0` |
| 组头 summary 行 | `JS@1141280`：`group/tool-summary inline-flex max-w-full cursor-pointer items-center gap-2 self-start ...`（**没有 padding；explore/terminal 组头宽度=内容宽**；changes 的 chips 内部另有 `flex-1`，但整行仍是 `inline-flex`，chevron 在 trigger 内容之后） |
| summary 内容容器 | `JS@1140539`：`tool-summary-content min-w-0 flex max-w-full items-center gap-2 ...`；只有 `prioritizePrimaryText` 才加 `flex-1 overflow-hidden`（组头不传） |
| chevron | `JS@1142036`：`size-4 text-foreground-subtlest opacity-0 transition-transform transition-opacity duration-200 ease-out ... group-hover/tool-summary:opacity-100 shrink-0` + open 时 `rotate-90 opacity-100` |
| 图标色覆盖 | `JS@1139788`：图标 span 类 `shrink-0 text-foreground-subtlest [&_svg]:text-foreground-subtlest`；组图标自身虽写 `text-foreground-subtle`（`JS@4053605` / `JS@4179507` / `JS@4071405`），但 `[&_svg]` 的 `.class svg` 特异度更高，**实际渲染为 subtlest** |
| 组默认折叠 | `JS@1143360`：`[z,B]=useState(()=>aw.get(R)??!1)` |
| 关闭延迟卸载 | `JS@1142549`：`g0e=300`（与 300ms 动画 + fill-mode:forwards 一致） |
| 终端卡片 | `JS@4044828`：`space-y-3 mb-2 rounded-xl border border-border bg-panel px-4 py-3` |
| execute 计数拼接 | `JS@4179617`（`Ipn`）：`n.join(', ')`；failed/stopped 两个可选段 |
| explore 计数拼接 | `JS@4055757`（`Jcn`）：fetch/search/list/file 四桶，`n.join(', ')`，空时 `0 个文件` |
| 运行中组头 | `JS@4059260` 起（`eln`）/ `JS@4180033` 起（`Lpn`）：`primaryText` 在 running 时取最后子工具动作 / `正在执行` + 最后命令 |

### 1.2 CSS（index-BgVjt5KE.css，字节偏移）

| 规则 | 值 | 证据 |
| --- | --- | --- |
| `--spacing` | `0.25rem` = 4px | `CSS@12096` |
| `.space-y-2` | `> :not(:last-child) { margin-block-end: 8px }` | `CSS@90752` |
| `.ml-2` | `margin-left: 8px` | `CSS@57273` |
| `.border-l` | `border-left-width: 1px` | `CSS@99047` |
| `.pl-3.5` | `padding-left: 14px` | `CSS@150720` |
| `.pt-2` | `padding-top: 8px` | `CSS@147875` |
| `html,:host` | `line-height: 1.5` → 14px 文本 = 21px 行盒 | `CSS@25121` |
| `--ui-font-size` | 14px | `CSS@418020` |
| `.size-4` | 16×16 | `CSS@59683` |
| `.duration-300` | `--tw-duration: .3s` | `CSS@192545` |
| `.ease-in-out` | `--tw-ease: var(--ease-in-out)` | `CSS@193002` |
| `[data-state=open]/[closed]` 动画 | `animation: collapsible-* var(--tw-animation-duration, var(--tw-duration, .2s)) var(--tw-ease, ease-out) ...` | `CSS@277125` / `CSS@275355` |
| `@property --tw-animation-duration` | `syntax:"*"; inherits:false`，**无 initial-value** → `var()` 走 fallback，最终取 `--tw-duration: .3s`、`--tw-ease: ease-in-out` | `CSS@363798` |
| keyframes | `collapsible-down: 0%{height:0} → to{height:var(...)}`；**只动 height，无 opacity** | `CSS@483063` |
| `--color-border` | light `#0a0a0a1a` / dark `#fafafa1a` | `CSS@15870` / `CSS@420177` |

### 1.3 Intl（IntlProvider-CneXitqp.js，字节偏移）

| key | 值 | 偏移 |
| --- | --- | --- |
| `chat.toolCall.explore.label` | 查阅 | `Intl@401708` |
| `chat.toolCall.explore.bucket.search` | 搜索 | `Intl@401747` |
| `chat.toolCall.explore.bucket.list` | 列表 | `Intl@401851` |
| `chat.toolCall.explore.bucket.file` | 文件 | `Intl@401951` |
| `chat.toolCall.explore.bucket.fetch` | 次网页抓取 | `Intl@354150` |
| `chat.toolCall.explore.emptySummary` | 0 个文件 | `Intl@402051` |
| `chat.toolCall.executeGroup.label` | 终端 | `Intl@355686` |
| `chat.toolCall.executeGroup.command` | {count} 个命令 | `Intl@356195` |
| `chat.toolCall.executeGroup.failed` | {count} 个失败 | `Intl@356307` |
| `chat.toolCall.executeGroup.stopped` | {count} 个已停止 | `Intl@356370` |
| `chat.toolCall.changesGroup.label` | 更改 | `Intl@355727` |
| `chat.toolCall.changesGroup.file` | {count} 个文件 | `Intl@355771` |

---

## 2. 组头（summary 行）对照

> 组头 = web 的完整 `ToolLayout` summary（`showIcon=true`、`canToggle=true`），不是特殊组件。

| 项 | web 实测 | 证据 | Compose 当前 | 差值 |
| --- | --- | --- | --- | --- |
| 组头上 padding | 0 | `JS@1141280` 无 `py-*`；`ToolLayout` 根 `w-full flex flex-col` 无 padding | 0（`ToolGroupRow.kt:89-95` Row 无 padding） | 0 |
| 组头下 padding | 0（内容另有 `pt-2` 8px） | 同上 + `JS@1142600` | 0 | 0 |
| 组头左/右 padding | 0（左右由时间线容器统一给） | `JS@1141280` `self-start`，无 `px-*` | 0 | 0 |
| 行高 | 21px（14 × 1.5） | `CSS@25121` + `CSS@418020` | 21dp（`uiBase` 14sp/21sp，`Type.kt:91-96`） | 0（fontScale=1） |
| 字号 / 字重（kindLabel） | 14px / 500 / `foreground-subtlest` | `JS@1141280` 的 `G`：`font-medium whitespace-nowrap shrink-0` + `text-foreground-subtlest` | 14sp / Medium / `foregroundSubtlest`（`ToolGroupRow.kt:102-107`） | 0 |
| 行内 gap | 8px（`gap-2`） | `JS@1141280` | 8dp（`ToolGroupRow.kt:94`） | 0 |
| 图标尺寸 | 16px | `JS@4053605` / `JS@4179507` / `JS@4071405` 的 `size-4`；`CSS@59683` | 16dp（`ToolGroupRow.kt:100`） | 0 |
| 图标颜色 | `foreground-subtlest`（有效值） | `JS@1139788` 的 `[&_svg]:text-foreground-subtlest` 特异度胜出 | `foregroundSubtle`（`ToolGroupRow.kt:99`） | 偏深：subtle=60% vs subtlest=40% |
| 图标垂直位置 | `items-center`，无 offset | `JS@1141280` | `.offset(y = 1.dp)`（`ToolGroupRow.kt:100`） | +1dp |
| 图标形状 | lucide `search` / `square-terminal` / `pencil` | `JS@4053605`（Ucn=Ep）/`JS@4179507`（Ppn=Fp）/`JS@4071405`（bln=cp） | Material `Search` / `Terminal` / `Edit`（`ToolGroupRow.kt:44-46`） | 形状不同（LucideIcons 里已有 Search/SquareTerminal/Pencil） |
| `·` 分隔 | `shrink-0 text-foreground-subtlest`，两侧 gap 8 | `JS@1140539` + `JS@1141280` | 独立 Text，`spacedBy(8)`，色 subtlest | 0 |
| 摘要文本 | 14px / 400 / `foreground-subtlest`；explore/terminal 组头内容宽、`min-w-0` 可截断 | `JS@1140539` | 14sp / 400 / subtlest；`weight(1f)`（`ToolGroupRow.kt:109-114`） | 文本样式 0；**宽度策略不同** |
| chevron 尺寸 | 16px（`size-4`） | `JS@1142036`；`CSS@59683` | 14dp（`RowComponents.kt:134`） | **−2dp** |
| chevron 位置 | 紧跟摘要内容，固定 gap 8，行是 `inline-flex self-start` | `JS@1141280`、`JS@1140539`（explore/terminal 组头不传 `prioritizePrimaryText`） | `weight(1f)` 把 Text 撑满剩余宽 → chevron 顶到最右 | 短摘要时差值 = 行宽 − 触发器内容宽（随摘要长度变化；changes 组的 chips 内部另有 `flex-1`，chevron 仍在 trigger 之后） |
| chevron 显隐 | 默认 `opacity-0`，hover 或展开才 100% | `JS@1142036` | 常显 | 触屏无 hover，常显可接受；但尺寸/位置应改 |
| chevron 旋转 | `rotate-0 → rotate-90`，200ms ease-out | `JS@1142036`（`duration-200 ease-out`） | 200ms `ZemoteEaseOut`（`RowComponents.kt:126`） | 0（**保持 200**） |
| 点击区域 | 内容宽（`inline-flex self-start`） | `JS@1141280` | 整行 `fillMaxWidth().softClickable` | 触屏适配，可保留 |
| 运行中 kindLabel | `animated-gradient-text` 渐变扫光 | `JS@1141280` 的 `G` + `isRunning` | 恒为 `foregroundSubtlest` | 运行态缺少高亮 |
| 失败状态文字 | explore 组可显示点线下划线状态（`showFailureStatus`） | `eln`（`JS@4059260` 起） | 无 | 缺（仅 explore 组会出现） |

---

## 3. 组内容（缩进 / 竖线 / 子行间距）对照

### 3.1 几何图

```
web（px，默认主题，fontScale 无关）                Compose 当前（dp）

┌ 组头 summary 21 ─────────────────────┐          ┌ 组头 21 ──────────────────────────┐
│ icon16  label  ·  summary     chevron│          │ icon16  label  ·  summary … chevron│
└──────────────────────────────────────┘          └───────────────────────────────────┘
        ↕ pt-2 = 8  ← 当前缺失                        ↕ 0
┌ div.ml-2  x=8 ───────────────────────┐          ┌ Column  x=8 ──────────────────────┐
││ border-l 1px  x=8..9                │          ││ drawRect 1dp  x=8..9             │
││                                     │          ││                                  │
││ 子行 1（21）文本 x=23               │          ││ 子行 1（21）文本 x=22            │
││        ↕ space-y-2 = 8              │          ││        ↕ Spacer = 8              │
││ 子行 2（21）                        │          ││ 子行 2（21）                     │
││        ↕ …                          │          ││        ↕ …                       │
││ 子行 n（21，展开时 + card + mb-2）  │          ││ 子行 n（21，展开时 card 无 mb）  │
└──────────────────────────────────────┘          └──────────────────────────────────┘
        border-l 从 y=29 起、到最后一子行底          线从 y=21 起（早 8）、到最后一子行底
```

### 3.2 对照表

| 项 | web 实测 | 证据 | Compose 当前 | 差值 |
| --- | --- | --- | --- | --- |
| 组头 → 第一子行 | **8px**（`pt-2`） | `JS@1142600` + `JS@1146455`；`CSS@147875` | **0dp**（`ToolGroupRow.kt:127-138` 无 top padding） | **−8dp** |
| 子行之间（都折叠） | **8px**（`space-y-2`） | `JS@4267724`；`CSS@90752` | 8dp（`ToolGroupRow.kt:146-148`） | 0 |
| 子行之间（上一子行展开，shell） | **16px** = card `mb-2` 8 + `space-y-2` 8 | `JS@4044828` + `CSS@90752` | **8dp**（card 无 `mb-2`） | **−8dp** |
| 最后子行 → 组尾（子行折叠） | **0** | `space-y-2` 只给 `:not(:last-child)`；`CSS@90752` | 0 | 0 |
| 最后子行 → 组尾（最后子行展开，shell） | **8px**（card `mb-2` 在子行高度内） | `JS@4044828` | **0dp**（card 无 `mb-2`） | **−8dp** |
| 竖线 left | **8px** | `ml-2`，`CSS@57273` | 8dp（`ToolGroupRow.kt:130`） | 0 |
| 竖线宽度 | **1px** | `border-l`，`CSS@99047` | 1dp（`ToolGroupRow.kt:132-136`） | 0 |
| 竖线 top | 组头底 + **8px**（`pt-2` 之后） | `JS@1142600` + 组内容 div | 组头底（**早 8dp**） | **−8dp** |
| 竖线 bottom | 最后一子行底（+8 若最后子行展开） | 内容盒高度 | 最后一子行底（展开时少 8） | 0 / −8dp |
| 子行文本 indent | **23px** = 8 + 1 + 14 | `CSS@57273` + `CSS@99047` + `CSS@150720` | **22dp** = 8 + 14（1px 线不占布局） | **−1dp** |
| 子行图标 | **无**（`showIcon:false`） | `JS@4059869` / `JS@4075228` / `JS@4180761` | 无（`ToolGroupRow.kt:174-179` 传 `showIcon=false`） | 0 |
| 子行行高 | **21px** | `CSS@25121` | 21dp（`uiBase`） | 0（fontScale=1） |
| 子行字号 | 14px（label 500 / summary 400） | `JS@1141280` 的 `G`；`JS@1140539` | 14sp / Medium / Normal | 0 |
| 子行 chevron | 16px，紧跟摘要内容（`inline-flex self-start`），hover/展开才显 | `JS@1142036`、`JS@1141280` | 14dp，常显，被 `weight(1f)` 顶到最右 | **−2dp + 位置不同** |
| 子行展开内容顶距 | **8px**（`pt-2`） | `JS@1142600`/`JS@1146455` | 8dp（`ToolCallRow.kt:220-224`） | 0 |
| 子行展开内容底距（shell 卡） | **8px**（`mb-2`） | `JS@4044828` | **0dp**（`ShellDetails` 无 bottom spacer） | **−8dp** |

---

## 4. 子行（ToolCallRow）自身样式对照

> 组内子行走完整 `ToolLayout`，只是 `showIcon:false`；所以它的 summary 行、chevron、展开容器都要按 ToolLayout 口径。

| 项 | web | 证据 | Compose 当前 | 差值 |
| --- | --- | --- | --- | --- |
| summary 容器 | `inline-flex max-w-full items-center gap-2 self-start` | `JS@1141280` | `Row(fillMaxWidth, spacedBy(8))` | 宽度策略不同（同 §2） |
| 行高 | 21px | `CSS@25121` | 21dp | 0 |
| label | 14px / 500 / subtlest；运行中渐变 | `JS@1141280` 的 `G` | 14sp / Medium / `foregroundSubtlest`；运行中 `foreground` + 呼吸 alpha（`ToolCallRow.kt:176-188`） | 运行态近似，可接受 |
| 分隔 `·` | `gap-2` 两侧 | `JS@1140539` | `spacedBy(8)` | 0 |
| summary 文本 | 14px / 400 / subtlest；`min-w-0` 截断 | `JS@1140539` | 14sp / 400 / subtlest；`weight(1f)` | 0（宽度策略同 §2） |
| 组内图标 | 无 | `showIcon:false` | 无 | 0 |
| chevron | 16px，inline，hover/open 显 | `JS@1142036` | 14dp，右对齐，常显 | −2dp + 位置 |
| 展开时 secondary 文本 | shell 行 `hideSecondaryTextWhenOpen:true` → 展开后命令摘要消失 | `JS@4045626`（kcn 的 `ow` 调用） | 展开后仍显示摘要 | 内容差异（行高仍 21） |
| 失败状态文字 | 继承 `foreground-subtlest`，点线下划线 + tooltip；**不是红色** | `JS@1144858`（`underline decoration-dotted`）；§2.4 | `colors.destructive` 红色纯文本（`ToolCallRow.kt:200-207`） | 颜色/装饰不同 |
| 展开卡（shell） | `space-y-3 mb-2 rounded-xl border bg-panel px-4 py-3` | `JS@4044828` | 12dp 圆角、1dp 边框、16/12 padding、无 `mb-2` | 仅缺 8dp 底距 |
| 展开卡命令字号 | `font-mono`、14/20（`leading-5`） | `JS@4038967`（Tcn） | Mono 14/20（`MonoBaseStyle`/`MonoOutputStyle`） | 0 |

---

## 5. 组头 summary 文案 / 图标 / chevron 细项

### 5.1 文案（当前 `groupSummary`，`ToolGroupRow.kt:182-201`）

| 场景 | web 实测 | 证据 | Compose 当前 | 差 |
| --- | --- | --- | --- | --- |
| explore 非运行 | 四桶按 `fetch, search, list, file` 顺序，`"{n} {桶名}"` 用英文逗号 + 空格连接；fetch 桶文案是 `次网页抓取`；空 → `0 个文件` | `Jcn` `JS@4055757`；Intl@354150/401747/401851/401951/402051 | `"N 次搜索，M 个文件"`；fallback `"N 次查阅"`（中文逗号、量词不同、无 list/fetch 桶） | 文案 + 桶 |
| explore 运行 | 最后子工具的动作摘要（如“正在读取 + 文件 chip”） | `eln`，`JS@4059260` 起；§3.4 | 计数 | 语义不同 |
| execute 非运行 | `"{N} 个命令[, {M} 个失败][, {K} 个已停止]"`，`join(', ')` | `Ipn` `JS@4179617`；Intl@356195/356307/356370 | `"N 个命令"` + `"，M 个失败"`；**缺已停止**，中文逗号 | 文案 + 缺项 |
| execute 运行 | `正在执行` + 最后命令（`<code class="min-w-0 truncate font-sans">`） | `Lpn`，`JS@4180033` 起；§3.4 | 计数 | 语义不同 |
| changes 非运行 | 关闭时：多文件 `"{N} 个文件" · 文件 chips`（单文件直接 chip）；展开后 `expandedPrimaryText` = `"{N} 个文件"`（chips 隐藏） | `Eln`，`JS@4073212` 起；§3.4 | 恒 `"N 个文件"` | 关闭态缺 chips |
| changes 运行 | `"{N} 个文件"` + `·` + 最后一条 kindLabel/chip/路径 + 运行中 diffCount（展开后隐藏） | 同上；`hideDiffCountWhenOpen`；§3.4 | 无 | 缺 secondary + diffCount |
| 空 explore | `0 个文件` | Intl@402051 | `N 次查阅` | 文案 |
| 默认展开 | 折叠 | `JS@1143360` | explore/terminal 展开（`defaultOpen=true`） | 行为 |

### 5.2 图标

- web 组图标：`Ucn = Ep(search)`、`Ppn = Fp(square-terminal)`、`bln = cp(pencil)`，均 `size-4`；有效色 `foreground-subtlest`（`JS@1139788` 的 `[&_svg]` 覆盖）。
- 当前：`Icons.Default.Search` / `Terminal` / `Edit`，16dp，`foregroundSubtle`，额外 `offset(y=1.dp)`。
- 建议：直接用同文件已有的 `LucideIcons.Search` / `SquareTerminal` / `Pencil`（`ToolIcons.kt` 已在用），tint 改 `foregroundSubtlest`，去掉 offset（严格 1:1）。

### 5.3 chevron

- web：16px，`opacity-0` 默认，hover 或 open 才 100%，`rotate-0 → rotate-90` 200ms ease-out。
- 当前：14dp，常显，200ms ease-out（时长正确），`weight(1f)` 导致位置在最右。
- 移动端无 hover，常显是合理适配；**尺寸与位置应改**。

---

## 6. 精确修法（仅给目标值，不改文件）

### F1（P0）组内容补 `pt-2` = 8dp，并让竖线从 8dp 后开始

`ToolGroupRow.kt:127-138` 当前：

```kotlin
Column(
    modifier = Modifier
        .fillMaxWidth()
        .padding(start = ZemoteSpacing.space2)   // 8
        .drawBehind { /* 1dp 竖线 */ }
        .padding(start = ZemoteSpacing.space3_5) // 14
)
```

应改为：

```kotlin
Column(
    modifier = Modifier
        .fillMaxWidth()
        .padding(top = ZemoteSpacing.space2)     // ① 新增：web pt-2 = 8
        .padding(start = ZemoteSpacing.space2)   // 8
        .drawBehind { /* 1dp 竖线，从 y=8 开始 */ }
        .padding(start = ZemoteSpacing.space3_5 + 1.dp) // ② 14 + border 1 = 15
)
```

- ① 必须在 `CollapsibleContent` 内部（当前就在），这样 8dp 属于被动画/裁剪的内容：展开时首帧即完整 8dp（顶部锚定，和 CSS height 动画一致），收起时随内容一起消失。
- ① 必须放在 `drawBehind` **之前**，否则竖线会把 8dp 也画上（= 竖线仍从组头底开始，只是整体下移）。
- ② 修复“1px border 不占布局”的 1dp：web 子行内容左缘 = 8 + 1 + 14 = 23；当前 = 8 + 14 = 22。
- 结果：组头→首子行 **8dp**；竖线 y 从组头底 +8 开始；子行文本 x=**23dp**。

### F2（P0）shell 展开卡补 `mb-2` = 8dp 底距

`ToolCallRow.kt:220-232` 的内容 Column 里、`when { ... }` 之后加：

```kotlin
if (isShell) Spacer(Modifier.size(ZemoteSpacing.space2)) // web card mb-2 = 8
```

- 必须加在**父 Column**（内容容器）里，而不是卡片自身的 `padding(bottom=8)`（后者会跑进边框内）。
- 效果：最后一子行展开时组底多 8dp；上一子行展开时到下一子行 = 16dp（8 mb + 8 space-y），与 web 一致。
- 同类问题：MCP 展开内容 web 是 `mb-2 space-y-3 py-1`（`JS@1150828`），`McpDetails` 也没有 `mb-2`；changes 子行的 diff 容器同样有 `mb-2`（`04 §9.1`），但 `ToolCallRow` 目前走 `GenericDetails`、根本没有 diff 区域，属于另一处功能缺口，不在本次间距修复范围。

### F3（P1）chevron 16dp + 跟随文本（inline），不顶到最右

- `RowComponents.kt:134`：`.size(14.dp)` → `.size(16.dp)`（web `size-4` = 16）。`ExpandChevron` 的 200ms/ease-out 保持不变。
- `ToolGroupRow.kt:109-114`：`AnimatedSummaryText(..., modifier = Modifier.weight(1f, fill = false))`。
- `ToolCallRow.kt:191-196`：同上；`ToolCallRow.kt:198` 的 `Spacer(Modifier.weight(1f))` 也改 `weight(1f, fill = false)`。
- 原因：web summary 行是 `inline-flex ... self-start`，摘要只占内容宽，chevron 固定 8px 跟在其后；`weight(1f)`（fill=true）会把 Text 撑满剩余宽度，chevron 被推到行尾。
- 若 HookTrailerRow 的 web chevron 是 14px，给 `ExpandChevron` 加 `size: Dp = 16.dp` 参数，调用处按需覆盖；不要为了 hook 把默认值留在 14。

### F4（P1）组头图标：Lucide + subtlest + 去 offset

- `ToolGroupRow.kt:44-46`：`Icons.Default.Search/Terminal/Edit` → `LucideIcons.Search/SquareTerminal/Pencil`。
- `ToolGroupRow.kt:99`：`colors.foregroundSubtle` → `colors.foregroundSubtlest`（web 的 `[&_svg]` 覆盖后是 subtlest）。
- `ToolGroupRow.kt:100`：去掉 `.offset(y = 1.dp)`（web `items-center` 无偏移）。若产品保留光学补偿，需在注释里标明“网页无此 1dp”。
- 同步：`ToolCallRow.kt:172`（独立工具行）tint 也是 `foregroundSubtle`，按 §2.2 应为 `foregroundSubtlest`；`ToolCallRow.kt:126`/`:173` 的 `offset(y=1.dp)` 同样不在 web。

### F5（P1）内容折叠动画：300ms ease-in-out（chevron 仍 200ms ease-out）

- `RowComponents.kt:54`：`ZemoteToolCollapseMs = 200` 保留给 chevron 旋转（web `duration-200 ease-out`）。
- 新增（或复用 `ZemoteReasoningCollapseMs`）：`internal const val ZemoteToolContentCollapseMs = 300`。
- `ToolGroupRow.kt:121-122`、`ToolCallRow.kt:217-218`：`durationMillis = ZemoteToolContentCollapseMs`、`easing = ZemoteEaseInOut`。
- 证据：`nx` 类显式 `duration-300 ease-in-out`（`JS@994252`）；`duration-300` 设 `--tw-duration: .3s`（`CSS@192545`），`ease-in-out` 设 `--tw-ease`（`CSS@193002`），动画简写读这两个变量（`CSS@277125`），`@property --tw-animation-duration` 无 initial-value（`CSS@363798`）→ 最终 300ms/ease-in-out，而不是 fallback 的 .2s/ease-out。`g0e=300`（`JS@1142549`）也印证 300ms。
- 不要改 `ExpandChevron` 的 200ms；它对应的是 `duration-200 ease-out`（`JS@1142036`）。

### F6（P2）组头文案

`ToolGroupRow.kt:182-201`：

- explore：按 `qcn`（`JS@4055317`）分 search/list/file/fetch 桶，文案用 Intl 的 `搜索/列表/文件/次网页抓取`，`joinToString(", ")`，空 → `"0 个文件"`。最小改法即使不分桶，也至少把 `"N 次搜索，M 个文件"` 改成 `"N 搜索, M 文件"`。
- execute：`listOfNotNull("${rows.size} 个命令", failed, stopped).joinToString(", ")`；补 stopped（按现有 status 归一化，web 统计 `failed`/`stopped`）。
- changes：保留 `"N 个文件"`；文件 chips 是独立功能，不在间距审计内。
- 运行中：execute 组头显示 `正在执行` + 最后命令；explore 显示最后子工具动作；当前计数只在非运行时显示。需要组头接收 `isRunning` + 最后子行摘要。

### F7（P2）组默认折叠

- `ToolGroupRow.kt:44-46` 的 `defaultOpen` 全部改 `false`，或删掉该字段。
- `Timeline.kt:368` 的 `unit.family.defaultOpen` 改为 `false`（web `aw.get(R)??!1`，`JS@1143360`）。

### F8（P2）子行失败态颜色

- `ToolCallRow.kt:200-207`：`colors.destructive` → `colors.foregroundSubtlest`；如需点线下划线，用 `TextDecoration.Underline` 近似（Compose 无 dotted underline 原生样式，可后续用 drawBehind 画点线）。

---

## 7. 与 `CollapsibleContent` 200ms 动画叠加的分析

### 7.1 几何上不会产生残影/跳动

- `CollapsibleContent`（`RowComponents.kt:82-114`）用 `SubcomposeLayout` 以固定宽 + 无限高**测量一次**内容，然后逐帧只改外层 `layout(width, height=contentHeight * progress)` 并 `clipToBounds()`；内容本身 `place(0,0)`、顶部对齐。
- 这与 web 的 `@keyframes collapsible-*`（`CSS@483063`：`height: 0 ↔ var(--radix-collapsible-content-height)` + `overflow-hidden`）同构：**顶部固定、底部被裁**。
- 修正 F1 后，8dp `pt-2` 是内容的一部分，首帧就完整存在（不会被 `progress` 缩放成 0→8）；竖线按全高绘制后被外层裁剪，收起时从底部向上缩，和 CSS border 行为一致。**没有“线长不同步”的跳动**。
- 当前真正会让人觉得“贴在一起”的原因是缺 8dp：展开过程中竖线从组头下沿就开始，且第一条子行紧贴组头；修正后线从 +8 开始、子行从 +8 开始。

### 7.2 fade 不是多余，但时长/缓动错了

- web 的 `nx` 除了高度动画，还给内层 `div` 加了 `animate-in fade-in-0`（open）/`animate-out fade-out-0`（closed），时长同样 300ms ease-in-out（`JS@994252`；`duration-300 ease-in-out` 同时作用于内层 div）。
- Compose 的 `graphicsLayer { alpha = progress.value }`（`RowComponents.kt:94`）在语义上等价：alpha 与高度共用同一个 `progress`，所以 fade 曲线与高度曲线一致。**这不是“额外叠加”，不需要去掉**。
- 但当前 `progress` 的 tween 是 200ms ease-out，web 是 300ms ease-in-out：展开/收起会**早 100ms 结束**，且起步更“冲”。修正 F5 后二者一致。
- 另一处小差异：Radix 在 open 时把 `--radix-collapsible-content-height` 固定为打开瞬间的内容高度；Compose 每帧按当前内容高度测量。若展开动画期间子内容还在增长（流式输出），Android 会跟着长高，web 会在动画结束后跳到 auto。对静态分组展开无影响；若要绝对 1:1，需要缓存打开瞬间高度，但这会让流式内容被裁剪，**不建议**。

### 7.3 结论

- **不会产生残影/跳动**：绘制方式、裁剪方向、顶部锚定都与 web 一致；fade 也是 web 有的。
- 需要修的是**几何缺失（8dp + 1dp + 8dp 底距）**与**动画参数（300ms/ease-in-out）**；200ms 不是 web 的真实值。

---

## 8. 与 `docs/web-reverse/04-tool-terminal-artifacts.md` 的出入

| 文档位置 | 文档说法 | 3.17.1 原始证据 | 结论 |
| --- | --- | --- | --- |
| §2.5 / §3.5 | 组/工具折叠 **0.2s ease-out** | `nx` 显式 `duration-300 ease-in-out`（`JS@994252`），`duration-300`/`ease-in-out` 覆盖 keyframe fallback（`CSS@192545`/`CSS@193002`/`CSS@277125`）；卸载延迟 300ms（`JS@1142549`） | 文档引的是 fallback 规则；**实际 300ms ease-in-out** |
| §3.3 / §9.1 | 组内 `ml-2 space-y-2 border-l pl-3.5` = 8/8/1/14 | `JS@4267724`、`CSS@90752`/`57273`/`99047`/`150720` | **正确** |
| §9.1 | 组内行间距 8px | 同上 | 对“都折叠”成立；**未覆盖展开子行 card 的 `mb-2`**（`JS@4044828`） |
| §3.6 | 组默认折叠 | `JS@1143360` | **正确**；当前代码 `defaultOpen=true` 违背 |
| §10 | 组头计数是普通文本、无徽章；子行无 stagger | 当前实现一致 | 无需改 |

---

## 9. 未验证 / 需真机确认

1. **Text 行盒是否精确 21dp**：`Type.kt` 给 14sp/21sp；Compose（BOM 2026.06.01）默认 `includeFontPadding=false` 时应为 21dp，但不同字体/系统可能有 1–2dp 字体 padding。这只是“行高”差异，不影响 8dp 顶距的结论。
2. **子行缩进的 1dp**：在 xxhdpi 上 1dp ≈ 2.75px，视觉上很细；但 `23 vs 22` 是可测的布局差，且 kdoc 已写明 23。
3. **status 归一化**：web 的 stopped 统计用 `status === 'stopped'`；Android 数据里可能是 `cancelled`，需按现有映射确认后再补“已停止”。
4. **HookTrailerRow 的 chevron 尺寸**：若把 `ExpandChevron` 默认改成 16dp，需确认 hook 行 web 是否也是 16；不是则加 `size` 参数覆盖。
5. **组头运行态渐变**：当前没有传给组头的 `isRunning`；补文案/渐变属于功能改动，需产品确认。

---

## 10. 复核清单（改完后逐项打勾）

- [ ] 组头底部到第一条子行 = 8dp（含展开/收起动画首帧）。
- [ ] 竖线起点 = 组头底 + 8dp；左缘 = 8dp；宽 1dp；终点 = 最后一子行底（最后子行展开时再 +8dp）。
- [ ] 子行文本左缘 = 23dp（与组头 kindLabel 的 24dp 差 1dp，与 web 相同）。
- [ ] 子行之间 = 8dp；上一子行展开（shell）= 16dp。
- [ ] 组头图标 = 16dp、Lucide 形状、subtlest、无 1dp offset。
- [ ] 组头/子行 chevron = 16dp、紧跟文本、8dp gap；旋转 200ms ease-out。
- [ ] 组内容展开 = 300ms ease-in-out；chevron 旋转 = 200ms ease-out。
- [ ] 组头文案 = `, ` 分隔；explore 四桶；execute 含已停止；changes 保留“N 个文件”。
- [ ] 组默认折叠。
