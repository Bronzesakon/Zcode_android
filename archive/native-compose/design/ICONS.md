# ICONS.md —— 网页版图标清单与 Compose 取用方案

来源：`Reference_web/3.15.3/`（remote v4 / 3.15.3 快照，2026-10-15 抓取）。
图标库：**lucide**（`lucide-react`，通过 `createLucideIcon` 生成）。
本文所有结论都可在快照内复核；不确定处标 `UNKNOWN`。

---

## 0. 结论速览

| 项 | 结论 |
|---|---|
| 图标库 | lucide（`lucide-react`），非自绘、非 iconfont |
| 组件工厂 | `createLucideIcon(name, iconNode)`，位于 `assets/createLucideIcon-BsjirBgz.js` |
| 实际使用图标数 | **247**（242 个 lucide 标准图标 + 5 个 App 自定义图标） |
| 命名 | 代码里是 **kebab-case**（`chevron-right`、`circle-question-mark`）；React 组件名是 PascalCase（`ChevronRight`） |
| 默认 `size` | **24**（lucide 默认），实际绝大多数被 Tailwind `size-*` 类覆盖为 16 / 14 / 12 / 20 px |
| 默认 `strokeWidth` | lucide 出厂 **2**，**本 App 在根节点用 IconContext 全局改成 `1.5`** |
| 默认 `color` | `currentColor`（继承 CSS `color`），另受 `--lucide-stroke` / `--lucide-filter` 两个 CSS 变量二次加工 |
| Compose 推荐 | `io.github.thelacspace:lucide-compose:1.16.0`（242/242 覆盖，且用 CompositionLocal 暴露 strokeWidth，能对齐 1.5）；缺口与 5 个自定义图标用快照路径数据补 |

---

## 1. 判定依据与覆盖率（重要：先说清"我怎么知道的"）

主包 `assets/index-BGa3KiRe.js`（6,592,643 B）与 1,018 个业务 chunk 都太大，不能整读。做法是
**按结构特征精确定位**，而不是按 assets 文件数（assets 里共 2,489 个 JS，其中 1,482 个是 lucide 图标 chunk）。

### 三条互不重叠的证据链

1. **内联定义（主证据，236 个）**
   主包里有 236 处 `` To(`<kebab-name>`, <iconNode>) `` 调用，`To` 就是 `createLucideIcon`
   （`import{n as Aie,t as To}from"./createLucideIcon-BsjirBgz.js"`，该 chunk 导出 `{u as n, p as t}`）。
   这些图标的 path 数据被直接内联进主包 —— 这正是 App 源码里 `import { X } from "lucide-react"` 的产物。
   命中 236 个名字，其中 5 个是 PascalCase 的自定义图标（见 §3）。

2. **独立 chunk 静态导入（11 个）**
   少数图标被主包与懒加载模块**共享**，因此 Vite 单独拆成 chunk（形如 `chevron-left-CQf3fHU7.js`，
   真正定义图标；`chevron-left-C4NHWXBT.js` 只是转出的 shim）。
   遍历 1,007 个非图标文件里的 `from"./<file>.js"`，命中 11 个名字：
   `chevron-left` `chevron-right` `file-down` `info` `triangle-alert` `x` `loader-circle`
   `mouse-pointer-2` `sparkles` `zoom-in` `zoom-out`
   （导入方：`index-BGa3KiRe.js`、`imeComposition-*.js`、`pdf-viewer-*.js`、`pptx-preview-viewer-*.js`）

3. **动态注册表（不是"使用清单"，是"目录"）**
   主包有 `var yRt={ "air-vent":()=>Y(()=>import('./air-vent-*.js')), ... }`，即 lucide 的
   `dynamicIconImports`（1,482 项，覆盖全部被拆出的图标 chunk）。它只有一个消费者：
   `function EU({fallback,name,...})`（即 lucide 的 `DynamicIcon`），主包里仅 **3 处**调用，
   且 `name` 全部来自**运行时数据**（模板 `iconName` / 插件配置），不是硬编码 UI 图标。
   → **不能把 yRt 的 1,482 个名字当成"网页用了这些图标"**，它只是 lucide 全量目录。

### 覆盖率结论

- 主包内联 236 + 独立 chunk 11 = **247**（去重后），其中 242 个是 lucide 标准名。
- 主包里全部 3 处 `DynamicIcon` 的 fallback 是 App 自己的图标组件（如 `bpn={target:…,activity:…,file:…,list:…}`），
  说明 UI 主体走的是**内联/静态导入**，不是按名字动态加载。
- 边界与未穷尽项：
  - 若某图标**只在 `DynamicIcon` 里按数据名出现**，无法静态判定 → 视为不可知（不影响硬编码 UI 的复刻）。
  - 我未逐字节反编译业务 chunk 的 JSX 以统计"每个图标用了几次"；247 是**名字集合**，不是频次。
  - 交叉核对的 `Zcode_harmony/Docs/18/19/20-功能域-*.md` **在工作区不存在**（`Zcode_harmony/` 目录缺失），
    因此本次**没有**做该交叉核对 → 标 `UNKNOWN`（清单仅由快照得出）。

---

## 2. 命名与取用方式

### 2.1 命名

- 代码/数据里：**kebab-case**。证据：图标 chunk 调用 `` e(`air-vent`, t) ``、注册表 key `"air-vent"`、
  DOM class `lucide-<kebab>`。
- React 组件标识：PascalCase。`createLucideIcon` 内部做双向转换
  （`i()` = Pascal→kebab；`a()`/`o()` = kebab→Pascal），DOM 上同时挂 `lucide`、`lucide-<kebab>`、`lucide-<Pascal>` 三个 class。
- **自定义图标用 PascalCase 直接当名字**（`To(\`WindowMaximize\`, …)`），class 会变成 `lucide-window-maximize`。

### 2.2 有没有统一封装？

- **没有** App 级统一 `<Icon name size strokeWidth>` 封装用于常规 UI。
  常规 UI 就是直接渲染 lucide 组件，靠 **Tailwind class + IconContext** 控制外观。
- 只有 3 个"数据驱动图标"包装器用了 `DynamicIcon`（名字来自数据）：
  `DRt({name})`（size-4 / strokeWidth 2）、`xpn({className,iconName,name})`、`Kpn({className,iconName,name})`（size 16 / strokeWidth 2）。
- 全局外观由 **IconContext** 注入，位于包裹整个 App 的 `p9()`：
  `(0,$.jsx)(Aie,{strokeWidth:K2n, children: …})`，其中 `K2n = 1.5`。

### 2.3 默认值来源

`createLucideIcon-BsjirBgz.js` 内（原样）：

```
s = { xmlns, width:24, height:24, viewBox:"0 0 24 24", fill:"none",
      stroke:"currentColor", strokeWidth:2, strokeLinecap:"round", strokeLinejoin:"round" }
```

组件签名：`({color,size,strokeWidth,absoluteStrokeWidth,className,children,iconNode,...})`，
从 IconContext 取默认：`{size=24, strokeWidth=2, absoluteStrokeWidth=false, color='currentColor'}`。
`absoluteStrokeWidth` 为真时 `strokeWidth = strokeWidth * 24 / size`；本 App **全局为 false**（`absoluteStrokeWidth` 在主包出现 0 次）。

**优先级**：显式 prop > IconContext > lucide 出厂默认。本 App 的 IconContext 只覆盖了 `strokeWidth`（=1.5），
`size` / `color` 仍用出厂默认，再由每个调用点的 Tailwind class 决定实际尺寸与颜色。

---

## 3. 实际使用的 247 个图标

### 3.1 lucide 标准图标（242）

以下 11 个走独立 chunk（与懒加载预览模块共享）：`chevron-left` `chevron-right` `file-down` `info`
`loader-circle` `mouse-pointer-2` `sparkles` `triangle-alert` `x` `zoom-in` `zoom-out`；
其余 231 个内联在主包。

```
activity  alarm-clock  anchor  app-window  archive  archive-x  arrow-down  arrow-down-to-line
arrow-left  arrow-left-from-line  arrow-right  arrow-right-left  arrow-right-to-line
arrow-up  arrow-up-from-line  arrow-up-right  arrow-up-to-line  astroid  at-sign  ban  bell
blocks  book-open  book-open-text  bot  bot-message-square  brain  briefcase-business  bug  cable
calendar-clock  calendar-days  camera  chart-column  chart-line  check  chevron-down  chevron-left
chevron-right  chevron-up  chevrons-down  chevrons-up  circle  circle-alert  circle-check
circle-check-big  circle-dashed  circle-minus  circle-pause  circle-play  circle-plus
circle-question-mark  circle-slash-2  circle-stop  circle-x  clipboard  clipboard-check
clipboard-paste  clipboard-pen-line  clock  clock-3  clock-alert  cloud  cloud-download
cloud-upload  code-xml  copy  cpu  crown  download  earth  ellipsis  eraser  external-link  eye
eye-off  file  file-clock  file-code-corner  file-diff  file-down  file-output  file-pen-line
file-search  file-text  files  flask-conical  folder  folder-open  folder-plus  folder-symlink
gauge  gift  git-branch  git-commit-horizontal  git-commit-vertical  git-graph  git-merge  globe
goal  graduation-cap  grip-horizontal  grip-vertical  hand  hash  history  hourglass  house  image
image-off  image-plus  import  inbox  info  key-round  keyboard  library  lightbulb  list
list-checks  list-filter  list-todo  list-tree  loader  loader-circle  lock  lock-keyhole  log-in
log-out  map  map-pin  maximize  maximize-2  megaphone  message-circle  message-circle-check
message-circle-plus  message-circle-question-mark  message-circle-reply  message-square
message-square-text  messages-square  mic  minimize-2  minus  monitor  monitor-cog
monitor-smartphone  monitor-up  moon  mouse-pointer-2  mouse-pointer-click  music  music-2  network
notepad-text  package  palette  panel-left-close  panel-left-open  panel-right-close
panel-right-open  panels-top-left  paperclip  pause  pen  pen-tool  pencil  pencil-ruler  pin
pin-off  play  plug  plus  power  presentation  puzzle  qr-code  quote  redo-2  refresh-ccw
refresh-cw  repeat-2  reply  rocket  rotate-ccw  save  scale  scroll-text  search  send  server
settings  settings-2  share  shield  shield-alert  shield-check  sliders-horizontal  smartphone
sparkle  sparkles  square  square-arrow-right-enter  square-code  square-kanban
square-mouse-pointer  square-slash  square-terminal  store  sun  table  tag  target  terminal
text-wrap  thumbs-down  thumbs-up  trash-2  trending-up  trending-up-down  triangle-alert
undo-2  unlink  unplug  upload  usb  user  user-round  user-round-pen  users  video  volume-2
wand-sparkles  waypoints  webhook  workflow  wrench  x  zap  zoom-in  zoom-out
```

### 3.2 App 自定义图标（5，需手绘/自建）

这 5 个**不在 lucide 里**，是 App 用 `createLucideIcon` 自己定义的（PascalCase 名字）：

`WindowMaximize` `WindowRestore` `DynamicWorkflowAlwaysOn` `DynamicWorkflowOnCommand` `DynamicWorkflowOff`

它们的 path 数据同样内联在主包，可按 §5.2 的方法从快照还原。

> 注：`render-process-gone` 看似图标名，实为 **Electron 事件名**（`render-process-gone` 监听器），
> 不是图标，已排除。

---

## 4. 尺寸 / 描边 / 颜色的实际取值（1:1 复刻最易漏的点）

### 4.1 尺寸

- lucide 出厂 `width/height = 24`（SVG 属性）。实际尺寸由 Tailwind 类覆盖：
  `.size-4{width:calc(var(--spacing) * 4); height:calc(var(--spacing) * 4)}`，而 `--spacing: .25rem`。
- `:root` 未发现覆盖根 `font-size`（1rem = 16px），故：
  | class | 计算 | px |
  |---|---|---|
  | `size-3` | 3 × .25rem | 12 |
  | `size-3.5` | 3.5 × .25rem | 14 |
  | `size-4` | 4 × .25rem | **16（最常用）** |
  | `size-5` | 5 × .25rem | 20 |
  | `size-6` | 6 × .25rem | 24 |
  | `size-8` | 8 × .25rem | 32 |
- 主包里 `size-N` 类的出现频次（含非图标元素，仅作参考）：
  `4 → 681`、`3.5 → 467`、`3 → 146`、`5 → 113`、`6 → 44`、`8 → 31`、`2.5 → 29`、`1.5 → 26`。
- 数据驱动包装器显式传 `size: 16`。
- 结论：**默认 24，但 UI 里 16px 是主基调，其次 14px / 12px / 20px**。

### 4.2 描边宽度（关键差异点）

- lucide 出厂 `strokeWidth = 2`；**本 App 全局改为 `1.5`**（IconContext，见 §2.2）。
- 少数显式覆盖（主包内统计）：`strokeWidth:2`（14 处，多为数据驱动包装器）、
  `strokeWidth:1.33`（14 处，某个 `size-4` 的 `animate-spin` 加载图标）、
  `strokeWidth:4/3`（≈1.333，分享按钮图标）、`strokeWidth:1`（4 处）、`strokeWidth:1.5`（3 处）。
  另 `strokeWidth:1.35` 出现在**自定义工作流 SVG**（`wf-ink` 动画路径）上，不是 lucide 图标。
- `strokeLinecap = strokeLinejoin = round`，`fill = none`（出厂，未改）。
- **换算**：strokeWidth 定义在 24 视口内，随尺寸缩放。size=16 时 web 实际线宽 = `1.5 × 16/24 = 1.0px`；
  size=24 时 = `1.5px`。Compose 若用 ImageVector（viewport 24）并设 `strokeLineWidth = 1.5f`，缩放行为一致。

### 4.3 颜色

- lucide 设 `stroke="currentColor"` → 图标颜色 = 元素的 CSS `color`（Tailwind `text-*` / `--color-foreground*`）。
- CSS 二次加工（`index-BD2BL4Cc.css`）：
  ```
  .lucide[stroke=currentColor]{stroke:var(--lucide-stroke,currentColor); filter:var(--lucide-filter,none)}
  ```
  - `--lucide-stroke`：默认 `currentColor`；部分变体用 `rgb(from currentColor r g b / 1)`（等价 currentColor）。
  - `--lucide-filter`：**"subtlest" 变体用 `opacity(var(--lucide-subtlest-opacity))`**，
    该变量 **light = .4 / dark = .3**。→ 同一图标可仅靠 CSS 变量变淡，Compose 侧需用 `alpha` 复刻。
- 主题令牌：`--color-foreground-subtle` / `--color-foreground-subtlest`（明暗各一套，见 `design/tokens/COLORS.md`）。

---

## 5. Compose 侧方案

### 5.1 方案对比

| 方案 | 坐标 | 覆盖（对本文 242 个 lucide 名） | 描边可控 | 评价 |
|---|---|---|---|---|
| **(a) thelacspace** | `io.github.thelacspace:lucide-compose:1.16.0` | **242 / 242 全覆盖** | ✅ `LocalLucideIconConfig`（含 `strokeWidth`、`absoluteStrokeWidth`） | API 与 lucide 的 IconContext **结构一一对应**；但项目极新（GitHub ~1 star、Maven 仅 1 个版本，2026-05 发布） |
| (a') Composables（新版） | `com.composables:icons-lucide-cmp:2.2.1` | 239 / 242（缺 `astroid`、`message-circle-check`、`square-arrow-right-enter`） | ❌ `strokeLineWidth = 2f` **写死在 ImageVector 里** | 生态最成熟、图标数最多；但**无法把线宽改成 1.5**，与网页不一致 |
| (a'') Composables（Android 老版） | `com.composables:icons-lucide:1.1.0` | 233 / 242（缺 9 个较新图标） | ❌ 同上 | 版本旧（2024-12），缺新图标更多 |
| **(b) 从快照生成 `ImageVector` / SVG** | 无（本地生成） | 247 / 247（含 5 个自定义） | ✅ 生成时任意指定 | **与部署版本逐字节同源**，是唯一能保证 1:1 的路径；代价是自己写一个生成脚本 |
| (c) 自建最小图标集 | 无 | 按需 | ✅ | 只有 247 个，规模不大，但纯手工易错、易与 lucide 上游漂移 |

覆盖数据来源：下载各库 sources jar 后，把本文 242 个 kebab 名按各自命名规则
（thelacspace = PascalCase 文件名；cmp 2.2.1 = snake_case；icons-lucide 1.1.0 = PascalCase）逐一比对文件是否存在。

### 5.2 SVG / path 数据从哪取

- **优先从快照还原**：每个图标 chunk 内就是完整 icon node，例如
  `air-vent`：`[[path,{d:"M18 17.5a2.5 2.5 0 1 1-4 2.03V12"}], [path,{d:"M6 12H4a2 2…"}], …]`，
  元素只有 `path{ d }` 和 `circle{ cx,cy,r }`（本例）。包上
  `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">`
  即可得到与网页同源的 SVG。
  数据位置：`Reference_web/3.15.3/assets/<kebab>-<hash>.js`（独立 chunk）或主包内联的 `To(\`<name>\`, <node>)`。
- 上游 lucide 仓库（github.com/lucide-icons/lucide，`icons/*.svg`）可取同名 SVG，但**版本可能漂移**
  （快照用的是较新的 lucide：存在 `circle-question-mark`、`bot-message-square`、`file-code-corner`、
  `square-arrow-right-enter` 等新命名，`circle-help`→`circle-question-mark` 的重命名已发生）。

### 5.3 推荐

**推荐 (a) thelacspace 为主 + (b) 快照生成补齐缺口。**

理由：
1. 它是唯一**同时**满足"242/242 覆盖"与"strokeWidth 可配置"的现成库，且
   `LucideIconConfig(strokeWidth, absoluteStrokeWidth, color, size)` 与 lucide-react 的 IconContext
   几乎逐字段对应，可以直接把网页的全局 `strokeWidth = 1.5` 一比一搬过来：
   ```kotlin
   CompositionLocalProvider(
       LocalLucideIconConfig provides LucideIconConfig(strokeWidth = 1.5f, absoluteStrokeWidth = false)
   ) { App() }
   ```
   然后把网页里的 `className="size-4"` 翻成 `Modifier.size(16.dp)`。
2. 5 个自定义图标（`WindowMaximize` 等）+ 任何个别缺失项，用 §5.2 的脚本从**快照**生成，
   保证与部署版本同源。
3. 风险与对策：thelacspace 项目很新、仅 1 个已发布版本 → 先做一次"随机抽 20 个图标与快照 path 逐点比对"的校验；
   若发现 path 与快照不符（上游版本漂移），整体退回方案 (b)——因为目标是 1:1，宁可全量生成。

**对"与网页一致"的影响（务必注意）：**
- **线宽对齐是本项目最容易翻车的点**。lucide 出厂 2，网页实际 1.5；
  若直接用 Composables 库（写死 2f），16px 图标线宽会是 1.33px 而非 1.0px，肉眼可辨地偏粗。
- Compose 的 `Icon(imageVector, tint = …)` **不能**修改 strokeLineWidth，
  所以要么选一个生成期可传 strokeWidth 的库（thelacspace / 自生成），要么接受偏差。
- 颜色用 `Icon(..., tint = LocalContentColor.current)` 即可对应 `currentColor`；
  "subtlest" 图标记得叠加 `Modifier.alpha(0.4f /* light */ / 0.3f /* dark */)`。
- 尺寸务必用 `Modifier.size(...)` 显式给，别依赖 `Icon` 默认 24dp —— 网页里 16dp 才是主基调。

---

## 6. UNKNOWN / 未验证

1. `Zcode_harmony/Docs/18/19/20-功能域-*.md` **不存在于工作区**，功能域交叉核对**未执行**。
2. 主包内联 236 个图标中，是否**每一个**都在 UI 中被真正渲染（存在被保留但未使用的可能）——**未逐一验证**。
3. 各图标的使用**频次**未统计（只统计了名字集合）。
4. 快照对应的**确切 lucide 版本号**未在 bundle 中找到（`lucide-react` 版本字符串不存在）；
   按新图标名推断为较新版本（`amphora`、`anvil` 存在于注册表），但**具体版本 `UNKNOWN`**。
5. thelacspace 库的图标 **path 是否与快照逐点一致**（上游版本漂移）——**未验证**，需抽样校验。
6. `size-N` 频次统计**包含非图标元素**，不能当作"图标尺寸分布"的精确值。
