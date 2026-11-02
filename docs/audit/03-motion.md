# 03 — 动效标准规范（Web 3.15.3 → Compose）

> 只读审计：除本文件外未修改任何文件；`Reference_web/` 未执行其中代码；未提交 git。
> 数据源：
> - `archive/pre-3.16.1-auth/Reference_web/3.15.3/assets/index-BGa3KiRe.js`（6,592,643 B）
> - `archive/pre-3.16.1-auth/Reference_web/3.15.3/assets/index-BD2BL4Cc.css`（455,351 B）
> - `archive/pre-3.16.1-auth/Reference_web/3.15.3/assets/button-B-6dbTqe.js`（按钮基类）
> - `docs/chat/01..08`（仅作交叉参考，本文数值全部回产物重新核对）
> - `android/app/src/main/kotlin/app/zemote/ui/**`
>
> 偏移口径：`js:字节` / `css:字节` 均为 UTF-8 **字节**偏移（Python `len(s[:i].encode())` 实测；JS 含中文，字符偏移≠字节偏移）。CSS 为单行压缩文件，无行号。
> 审计日期：2026-10-17。方法：字符串切片 + 正则定位 + CSS 规则解析，未运行应用。

---

## 0. 结论摘要

1. **对话页没有统一的动效语言，只有 4 组固定配方。** ①默认 150ms `cubic-bezier(.4,0,.2,1)`（Tailwind `transition-*` 默认，`css:10917`/`10952`）；②展开/收起 300ms `ease-in-out`（共享 `W_` 折叠容器，`js:736071`）；③文字换字 300ms `cubic-bezier(.4,0,.2,1)`（framer-motion，`js:870478`）；④流式入场 900ms `cubic-bezier(.16,1,.3,1)`（CSS keyframes，`css:425586`）。除此之外的时长（100/200/260/180）都是局部特例。
2. **framer-motion 只出现在少数几处，且全部与"内容切换"有关**：工具/子代理摘要换字（`DWe`，popLayout）、模型按钮文案换字（`KV`，popLayout）、dock 模式切换（`Can`）、分享侧栏/分享卡、diff 数字翻滚（`gXt`）。**时间线行本身没有任何 framer-motion 包裹**，行 mount/unmount 无动画。
3. **展开/收起实际是 300ms，不是 200ms。** 共享折叠容器 `W_` 同时带 `duration-300 ease-in-out`，Tailwind 的 `--tw-duration`/`--tw-ease` 会被 keyframes 动画消费（`js:736071`，`css:262887`/`260516`）。`docs/chat/04-toolcall.md:123` 的"0.2s ease-out"是误读（0.2s 只是没有 `duration-300` 时的兜底值）。
4. **chevron 有两档**：工具摘要箭头 200ms `ease-out`（`cubic-bezier(0,0,.2,1)`，`js:874714`）；轮次/思考/工具详情箭头 150ms `cubic-bezier(.4,0,.2,1)`（`js:3398034`、`js:3345839`、`js:885959`）。
5. **dock 的 260ms 入场只发生在"模式切换"（chat ↔ confirmation/share）**，不是首次出现。`AnimatePresence initial={false}` + `mode="sync"` + `key=mode`（`js:3533485`/`3534046`/`3534090`）：首帧不播；`chat` ↔ `confirmation` 切换时新层 260ms 从 `translateY(32px) scale(.96)` 进入、旧层 180ms 到 `scale(.97)` 退出。`docs/chat/06-composer.md:30` 的"出现/切换有入场动画"应更正为"仅切换"。
6. **发送/停止是纯条件渲染，没有交叉淡入、没有旋转、没有 framer-motion**（`js:2317962` vs `js:2318231`）。停止只在"运行中且输入为空"时出现。
7. **队列卡片没有入场/退场动画**，只有拖拽排序的 dnd-kit 200ms `ease`（`js:2628887`）；暂停横幅无动画。
8. **模型菜单是 Radix DropdownMenu，100ms 淡入+95% 缩放+2px 方向滑入**（`js:715576`，`css:180342`）；Compose 侧模型选择器是 `Dialog` 底部弹层，机制和时长都对不上。
9. **附件上传进度环没有补间**：SVG `strokeDasharray` 直接跟 `uploadProgress` 跳变（`js:2314241`）；只有工具进度条的确定态有 `transition-[width] duration-200`（`js:737544`）。
10. **Compose 侧动画数量不少，但几乎全部使用默认 spec**：`AnimatedVisibility(expandVertically()+fadeIn())` 默认 spring（`ToolCallRow.kt:177`、`ToolGroupRow.kt:133`、`ReasoningRow.kt:107`），`animateFloatAsState` 默认 spring（`TurnHeaderRow.kt:51`、`ToolGroupRow.kt:90`），`animateContentSize()` 默认 spring（`UserInputRow.kt:126`）。**`ZemoteDuration`/`ZemoteEasing` 已定义但零调用**（`theme/Dimens.kt:122`/`:160`）。
11. **最大偏差是"轮次折叠"**：网页整段历史 300ms 高度+淡出（`W_`），Compose 直接重算 `visibleUnits` 让行瞬间消失/出现（`Timeline.kt:114`/`:176`），没有任何过渡。
12. **Compose 多出三类网页没有的动画**：运行态标签的透明度呼吸（2s，`ToolCallRow.kt:409`、`ReasoningRow.kt:84`、`MarkerRow.kt:138`）、工具行转圈 spinner（`RowComponents.kt:59`）、`StreamingDot` 1.1s 呼吸点（定义在 `RowComponents.kt:85`，**全项目零调用**）。网页对应位置是 4s 线性渐变扫光，不是透明度呼吸，也没有 spinner。

---

## 1. 动效表

> 机制标记：`[CSS-T]` = CSS transition；`[CSS-A]` = CSS keyframes animation；`[FM]` = framer-motion（JS 驱动）；`[无]` = 条件渲染/瞬时切换。
> 曲线简写：`std` = `cubic-bezier(.4,0,.2,1)`；`out` = `cubic-bezier(0,0,.2,1)`；`expressive` = `cubic-bezier(.16,1,.3,1)`；`emph` = `cubic-bezier(.23,1,.32,1)`。

### 1.1 轮次 / 折叠

| 元素 | 触发 | 时长 | 曲线 | 属性 | 证据偏移 | Compose 现状 | 差距 |
|---|---|---|---|---|---|---|---|
| 轮次历史整段折叠（assistant history） | 点 turn header | 300ms | std（`ease-in-out`） | height `0 ↔ var(--radix-collapsible-content-height)`；内层 opacity `0↔1`；关闭 `fill-mode:forwards` | `[CSS-A]` `js:736071`（`W_`）；`css:262887`、`css:260516`（工具类默认 .2s）、`css:453130`、`css:453341`（keyframes）、`css:180512`（duration-300）、`css:180969`（ease-in-out） | 无动画：`visibleUnits` 重算后行直接增删（`Timeline.kt:114`、`:176`） | **无过渡**，内容瞬移；行高跳变 |
| turn header chevron | 展开/收起 | 150ms | std | transform `rotate(0deg) → rotate(90deg)` | `[CSS-T]` `js:3398034`（`transition-transform` + `rotate-90/0`）；默认值 `css:10917`/`10952`；工具类 `css:180013` | `animateFloatAsState(90f/0f)` 未指定 spec（`TurnHeaderRow.kt:51`）→ 默认 spring | 时长不可控（弹簧无固定 ms）；图标为 ChevronRight，方向一致 |
| 用户超长消息折叠 | 点圆形展开/收起 | 300ms | out（`ease-out`） | `max-height: 120px ↔ auto` | `[CSS-T]` `js:2773552`（`data-v4-user-input-collapsible-content`）、`js:2773695`（`transition-[max-height] duration-300 ease-out motion-reduce:transition-none`） | `Modifier.animateContentSize()` 未指定 spec（`UserInputRow.kt:126`）→ 默认 spring | 曲线/时长不一致；无 reduced-motion 处理 |
| 用户消息底部渐隐 | 折叠态常驻 | 静态 | — | `mask-image: linear-gradient(black 0%, black 70%, transparent 100%)` | `[CSS]` `js:2773552` 同串 | 30% 高度渐变 Box（`UserInputRow.kt:145`） | 静态一致 |
| 时间线行 mount/unmount | 新行/删行 | 无 | — | — | `[无]` `docs/chat/01:568`（行进入/退出不存在） | 无 | 一致 |
| 工具块流式入场 | 首次以流式身份出现（`streamingEntranceActive`，每 key 一次，1000ms 后撤销标记） | 900ms | expressive | opacity `0→1`；`will-change:opacity` | `[CSS-A]` `js:3144819`（`data-zcode-tool-stream-animate`）、`js:3142384`（`streamingEntranceActive`）、`js:3141379`（`P3t=1e3`）；`css:425586` | 无 | 新工具行直接出现，无淡入 |
| 预览卡流式入场 | `data-zcode-stream-animate` + 每项 `--zcode-stream-animation-delay` 错峰 | 900ms + delay | expressive | opacity | `[CSS-A]` `js:3336438`、`js:3336480`；`css:425269` | `ArtifactRow` 无动画 | 无错峰淡入 |
| 折叠关闭动画（`data-zcode-collapsible-animate-close`） | 关闭 | 300ms | std | height→0 + 子元素淡出 | `[CSS-A]` `css:425748`、`css:425877`、`css:426014`、`css:426109` | — | **死规则**：JS 全库 0 命中该属性（`js` 搜索 `data-zcode-collapsible-animate-close` = 0），不要实现 |

### 1.2 工具 / 思考 / 流式

| 元素 | 触发 | 时长 | 曲线 | 属性 | 证据偏移 | Compose 现状 | 差距 |
|---|---|---|---|---|---|---|---|
| 工具摘要换字（primary/secondary text） | contentKey 变化（explore/agent 常开；changes/execute/cua 仅运行中；展开时关闭） | 300ms | std | y `0.8em→0`（新）/ `→-0.8em`（旧）；opacity `0→1` / `→0`；popLayout（新旧共存，不做宽度补间） | `[FM]` `js:870478`（`DWe`）；`js:869682`（`xWe={duration:.3,ease:[.4,0,.2,1]}`）；节流 `vWe=800`、合并窗 `yWe=250`、队列上限 `bWe=2`；`js:876291`（`animateContent` 传参） | `AnimatedContent` 300ms，slide `it/3`、`SizeTransform(clip=false)`（`RowComponents.kt:124`） | 时长/曲线一致；**滑动距离**（it/3≈6.7px vs 0.8em≈11.2px）、**有宽度动画**（网页没有）、**无 800ms 节流**（流式高频更新会连续触发） |
| 思考中的流式预览行 | 推理文本变化 | 300ms | std | 同上（`DWe`，contentKey=`reasoning-line:*`） | `[FM]` `js:3345482` | 无预览行（ReasoningRow 只有标题+内容） | 缺功能，不只是动效 |
| 推理标题 chevron | 展开/收起 | 150ms | std | transform `rotate 0↔90`；opacity `0↔1`（hover 才显形） | `[CSS-T]` `js:3345839`（`transition-opacity transition-transform`） | `ExpandChevron` 200ms tween（`RowComponents.kt:41`） | 时长 200 vs 150；图标 ExpandMore 0→180（网页 chevron-right 0→90） |
| 推理内容展开/收起 | 点标题 | 300ms | std | height + 内层 fade | `[CSS-A]` `js:3346942`（`W_`） | `AnimatedVisibility(expandVertically()+fadeIn())`（`ReasoningRow.kt:107`）→ 默认 spring | 无固定 300ms；无 fill-forwards；关闭后卸载时机不同 |
| 工具详情展开/收起 | 点摘要行 | 300ms | std | height + 内层 fade | `[CSS-A]` `js:736071`（`W_`，工具渲染器 `js:879740` 调用） | `AnimatedVisibility` 默认 spring（`ToolCallRow.kt:177`） | 同上 |
| 工具分组展开/收起 | 点分组头 | 300ms | std | 同上 | `[CSS-A]` `js:736071` | `AnimatedVisibility` 默认 spring（`ToolGroupRow.kt:133`） | 同上 |
| 工具摘要 chevron | hover 显形 / 展开 | 200ms | out | transform rotate 0↔90；opacity 0→1（hover 或展开） | `[CSS-T]` `js:874714`（`transition-transform transition-opacity duration-200 ease-out`） | **工具行没有箭头**；只有分组行箭头 spring（`ToolGroupRow.kt:90`） | 缺箭头（展开态也没有）；分组箭头时长不可控 |
| 工具详情 chevron | 展开 | 150ms | std | transform rotate 0→90 | `[CSS-T]` `js:885959` | 同 ExpandChevron 200ms | 150 vs 200 |
| 工具运行态标签 | 运行中常驻 | 4s | linear | `background-position 100%→0→0`（渐变扫光，无限） | `[CSS-A]` `css:341027`（`.animated-gradient-text`）、`css:342695`（`gradient-flow`） | 透明度呼吸 2s Reverse（`ToolCallRow.kt:409`） | **机制不同**：扫光 vs 呼吸；周期 4s vs 2s |
| 思考标题「思考中」 | 运行中常驻 | 4s | linear | 同上 | `[CSS-A]` `css:341027` | 透明度呼吸 2s（`ReasoningRow.kt:84`） | 同上 |
| 时间线 marker 运行态 | 运行中常驻 | 4s | linear | 同上 | `[CSS-A]` `css:341027` | 透明度呼吸 2s（`MarkerRow.kt:138`） | 同上 |
| 工具进度条（不确定态） | `progress.fraction` 缺省 | 1200ms | `ease-in-out`（CSS 关键字） | `left:-33% → 100%`，无限 | `[CSS-A]` `js:737384`；`css:423973`；reduced-motion 下 `animation:none`（`css:424178`） | 无进度条 | 缺组件 |
| 工具进度条（确定态） | fraction 变化 | 200ms | std（默认） | width | `[CSS-T]` `js:737544`（`transition-[width] duration-200`） | 无 | 缺组件 |
| 思考内容滚动 | 流式追加 | 无 | — | `scrollTop=scrollHeight`、`scrollLeft=scrollWidth` 直接赋值 | `[JS]` `js:3342679`（`Ien`） | `rememberScrollState` + 无自动滚动 | 功能差异；无动画 |
| hook 状态 spinner | 运行中 | 1s | linear | rotate 360° 无限 | `[CSS-A]` `js:3350542`（`animate-spin motion-reduce:animate-none`）；`css:452190`（`spin 1s linear infinite`） | `CircularProgressIndicator`（`HookTrailerRow.kt:172`） | Material 默认弧线/时长，不是纯旋转；可接受但需统一 |
| 新行/加载 spinner 容器 | 加载 | 900ms 淡入 + 1s 旋转 | expressive | opacity + rotate | `[CSS-A]` `js:2781020`（`data-zcode-chat-loading-animate`）；`css:425586` | `LinearProgressIndicator`（`ChatScreen.kt:182`） | 形态不同 |

### 1.3 Dock / 输入区 / 队列 / 模型 / 附件

| 元素 | 触发 | 时长 | 曲线 | 属性 | 证据偏移 | Compose 现状 | 差距 |
|---|---|---|---|---|---|---|---|
| dock 内容层切换 | `mode` 变化（`chat` ↔ `confirmation`；分享模式另有 selection-panel/scrim） | 进入 260ms / 退出 180ms | emph | `translate3d(0,32px,0) scale(.96)` → `translate3d(0,0,0) scale(1)`；退出到 `scale(.97)`；`origin-bottom`、`will-change-transform` | `[FM]` `js:3533308`（`_an=.26, van=.18, yan=[.23,1,.32,1], han=.96, gan=.97`）、`js:3533485`（`San`）、`js:3534046`（`conversation-bottom-dock-transition-layer`）、`js:3534090`（mode 属性）、`js:3680805`（`Can` 调用） | 无 dock 概念；输入区是静态底部 Column | **无对应**；若保留 dock 语义，需补 260/180ms 的 scale+translate |
| dock 首次出现 | 草稿 → 会话 | 无 | — | `AnimatePresence initial={false}`，首帧不播 | `[FM]` `js:3533485` | — | 网页也不播；不要给首次出现加动画 |
| dock 内容列宽变化 | 容器宽度变化 | 150ms | out | width/max-width/transform | `[CSS-T]` `js:3472231`、`js:3473843` | 无 | 移动端窄屏不涉及 |
| 输入框边框/背景 | focus / hover | 150ms | std | color / background-color | `[CSS-T]` `js:2093538`（`transition-colors hover:border-input-border-hover focus-within:!border-input-border-focused`） | 边框色瞬时切换（`ChatScreen.kt:579`/`:597`） | 缺 150ms 颜色过渡（观感偏硬） |
| 输入框内部 textarea | — | 无 | — | `!transition-none` | `[无]` `js:2319260` 附近 | 无 | 一致 |
| 发送 / 停止切换 | 运行中且输入为空 ↔ 否则 | 无 | — | 条件渲染二选一（stop: `variant=secondary` + Square；send: `type=submit` + ArrowUp） | `[无]` `js:2317962`（stop 分支）、`js:2318231`（send 分支）；文案 `js:2310290`（`chat.stop`） | 同一位置条件渲染（`ChatScreen.kt:757`） | **一致，不要加动画** |
| 队列卡片出现/删除 | 队列增删 | 无 | — | 条件渲染 | `[无]` `js:2637669`（`data-queue-count`）、`js:2638947`（`wHt`） | 条件渲染（`ChatScreen.kt:476`） | 一致（都无动画） |
| 队列拖拽排序 | 拖动 | 200ms | `ease` | transform（dnd-kit CSS.Transform） | `[JS]` `js:2628887`（`dHt={duration:200,easing:'ease'}`） | 无拖拽排序 | 缺功能 |
| 队列暂停横幅 | 暂停态 | 无 | — | 条件渲染 | `[无]` `js:2638199` | 条件渲染（`ChatScreen.kt:488`） | 一致 |
| 队列确认弹窗 | 发送确认 | 100ms | std | opacity + scale 95% | `[CSS-A]` `js:2322351`（Dialog）+ 基类 `js` dialog-content；`css:180342` | `AlertDialog`（`ChatScreen.kt:275`/`:297`） | Material 默认动画，非 100ms 缩放 |
| 模型菜单（DropdownMenu） | 点模型按钮 | 100ms | std | opacity `0→1`、scale `0.95→1`、方向滑入 2px（按 side） | `[CSS-A]` `js:715576`（`z-[60] ... duration-100 data-open:animate-in fade-in-0 zoom-in-95 data-[side=*]:slide-in-from-*-2`）；基类 `css:75126`（`animate-in` 默认 .15s，被 `duration-100` 覆盖） | `Dialog` 底部弹层（`ModelSheet.kt:65`），无自定义动画 | 机制（下拉 vs 弹层）+ 动画参数都不同 |
| 模型按钮文案换字 | 切换模型 | 200ms | std | y `0.75em→0` / `→-0.75em`，opacity；popLayout | `[FM]` `js:2161440`（`hNt={duration:.2,ease:[.4,0,.2,1]}`）、`js:2168344`（`KV` 调用） | 文本直接替换（`ChatScreen.kt:718`） | 缺 200ms 换字 |
| 模型切换 loading | 切换中 | 1s | linear | 3.5px spinner 旋转 | `[CSS-A]` `js:2168344` 附近（`animate-spin`） | 无 | 缺 loading 指示 |
| Mode / Effort 菜单 | 点 chip | 100ms | std | opacity + scale 95% + 方向滑入 2px | `[CSS-A]` 二者都是 Radix **Select**（`js:2246988` 区 `LH`，`_f` = SelectContent）：`js:227802`（`origin-(--radix-select-content-transform-origin) ... duration-100 data-open:animate-in data-open:fade-in-0 data-open:zoom-in-95`）；`css:180342` | `DropdownMenu`（`ChatScreen.kt:876`） | 组件机制不同（Select vs DropdownMenu）；Material 默认参数未核对 |
| 思考等级竖向填充条 | 切换等级 | 300ms | `cubic-bezier(0.34,1.56,0.64,1)`（轻微回弹） | height | `[CSS-T]` `js:2249342`（`transition-[height] duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)]`） | 无填充条 | 缺组件；这是对话页唯一的回弹曲线 |
| 附件上传进度环 | 上传进度事件 | 无（跳变） | — | `stroke-dasharray: ${progress} 100` | `[JS/SVG]` `js:2314241`（`strokeDasharray`，无 transition 类） | Canvas `drawArc`（`ChatScreen.kt:808`） | 一致（都无补间） |
| 附件删除按钮 | hover | 150ms | std | opacity 0→1 | `[CSS-T]` `js:2315986`（`opacity-0 transition-opacity ... group-hover:opacity-100`） | 常显 IconButton | 触屏无 hover，可接受 |
| 附件上传整体进度条 | 上传中 | 200ms | std | width | `[CSS-T]` `js:737544`（通用 Progress 组件） | 无 | 缺组件（若需要） |
| highspeed 输入框边框/阴影 | highspeed 阶段切换 | 1040ms | `cubic-bezier(.45,0,.55,1)` | border-color / box-shadow | `[CSS-T]` `css:378234`（`.highspeed-composer-surface`） | 无 highspeed | 非对话核心，暂不实现 |
| highspeed 模型标识入场 | 进入 highspeed | 400ms | `cubic-bezier(.55,0,.45,1)` | opacity + `translateY(75%) rotateX(-55deg)` → 0 | `[FM]` `js:2171549`（`ENt`）、`js` 2163717 附近 | 无 | 非核心 |
| 空态建议词瀑布 | 首屏出现 | 260ms + 每项 delay | `cubic-bezier(.22,1,.36,1)` | opacity + `clip-path` + `translateY(-12px)` | `[CSS-A]` `css:424477` | 无空态建议词 | 缺功能 |

### 1.4 hover / focus / 按压

| 元素 | 触发 | 时长 | 曲线 | 属性 | 证据偏移 | Compose 现状 | 差距 |
|---|---|---|---|---|---|---|---|
| 按钮基类 | hover / active | 150ms | std | color、background-color、border-color、outline-color 等 | `[CSS-T]` `button-B-6dbTqe.js:2786`（`transition-colors`）；默认值 `css:10917`/`10952`；工具类 `css:179264` | 无颜色过渡；`clickable` 自带 ripple | 触屏可保留 ripple；**不要**再叠 scale/颜色补间 |
| 工具摘要整行 | hover | 150ms | std | color | `[CSS-T]` `js:873917`（`transition-colors`） | 无 | 可忽略（无 hover） |
| 工具摘要箭头 | hover | 150ms（opacity）+200ms（transform） | std / out | opacity、rotate | `[CSS-T]` `js:874714` | 无 hover 逻辑 | 触屏可忽略 |
| 推理标题箭头 | hover | 150ms | std | opacity、transform | `[CSS-T]` `js:3345839` | 无 hover 逻辑 | 触屏可忽略 |
| 轮次标题箭头 | 常显 opacity .7 | 150ms | std | transform | `[CSS-T]` `js:3398034` | 常显，旋转 spring | 见 §1.1 |
| 下拉菜单项高亮 | 键盘/指针高亮 | 无 | — | background-color | `[无]` `js:715988`（`data-[highlighted]:bg-menu-hover`，无 transition） | Material 默认无动画 | 一致 |
| 回到底部按钮 | 显隐 | 无 | — | — | `[无]` `docs/chat/01:364`；按钮组件 `js:3450639`（仅 `transition-colors`） | `AnimatedVisibility(fadeIn/fadeOut)`（`Timeline.kt:288`，用于底部渐变，不是按钮） | 网页按钮瞬时；Compose 若给按钮加淡入需注意网页没有 |

---

## 2. 建议统一的 motion 令牌

### 2.1 时长

| 令牌 | 值 | 语义 | 网页来源 |
|---|---|---|---|
| `Instant` | 0ms | 发送/停止切换、队列增删、回到底部、菜单项高亮 | `[无]` |
| `Fast` | 100ms | 下拉/弹窗出现（模型菜单） | `css:180342`、`js:715576` |
| `Base` | 150ms | hover/focus/颜色、chevron 旋转、输入框边框 | `css:10917` |
| `Emphasize` | 200ms | 工具摘要换字、工具箭头、模型文案换字、进度条宽度 | `js:869682`、`js:874714`、`js:2161440`、`js:737544` |
| `Expand` | 300ms | 所有折叠容器（轮次/思考/工具/分组）、用户消息折叠、换字（工具摘要） | `js:736071`、`js:2773695`、`js:869682` |
| `StreamIn` | 900ms | 工具块/预览卡流式淡入 | `css:425586` |
| `DockIn` | 260ms | dock 模式切换进入 | `js:3533308` |
| `DockOut` | 180ms | dock 模式切换退出 | `js:3533308` |
| `LoopSlow` | 4000ms | 运行态渐变扫光（linear，无限） | `css:341027` |
| `LoopSpin` | 1000ms | spinner（linear，无限） | `css:452190` |
| `LoopProgress` | 1200ms | 不确定进度条（ease-in-out，无限） | `css:423973` |

### 2.2 曲线

| 令牌 | 值 | 语义 | 网页来源 |
|---|---|---|---|
| `Standard` | `CubicBezierEasing(0.4f, 0f, 0.2f, 1f)` | 默认：折叠、换字、颜色、chevron | `css:10606`（`--ease-in-out`）、`css:10952` |
| `Out` | `CubicBezierEasing(0f, 0f, 0.2f, 1f)` | 工具箭头、用户消息折叠 | `css:10569`（`--ease-out`） |
| `Emphasized` | `CubicBezierEasing(0.23f, 1f, 0.32f, 1f)` | dock 进出 | `js:3533308`（`yan`） |
| `Expressive` | `CubicBezierEasing(0.16f, 1f, 0.3f, 1f)` | 流式入场淡入 | `css:425586` |
| `Overshoot` | `CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)` | **仅**思考等级竖向填充条 | `js:2249342` |
| `Linear` | `LinearEasing` | 扫光、spinner | `css:341027`、`css:452190` |

### 2.3 落地建议（Compose）

现有 `ZemoteDuration`/`ZemoteEasing`（`android/app/src/main/kotlin/app/zemote/ui/theme/Dimens.kt:122`、`:160`）保留并补齐：

```kotlin
object ZemoteMotion {
    const val Instant = 0
    const val Fast = 100
    const val Base = 150
    const val Emphasize = 200
    const val Expand = 300
    const val StreamIn = 900
    const val DockIn = 260
    const val DockOut = 180
    const val LoopSlow = 4000
    const val LoopSpin = 1000
    const val LoopProgress = 1200

    val Standard = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    val Out = CubicBezierEasing(0f, 0f, 0.2f, 1f)
    val Emphasized = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
    val Expressive = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    fun <T> standard(duration: Int = Base) = tween<T>(duration, easing = Standard)
    fun <T> expand() = tween<T>(Expand, easing = Standard)
    fun <T> streamIn() = tween<T>(StreamIn, easing = Expressive)
}
```

配套约定：

1. **所有 `AnimatedVisibility` 必须显式给 `transitionSpec`**，禁止依赖默认 spring：折叠统一
   `expandVertically(ZemoteMotion.expand()) + fadeIn(ZemoteMotion.expand())` /
   `shrinkVertically(ZemoteMotion.expand()) + fadeOut(ZemoteMotion.expand())`。
2. **所有 `animateFloatAsState` 必须显式给 `animationSpec`**：chevron 用
   `tween(if (toolSummary) 200 else 150, easing = if (toolSummary) Out else Standard)`。
3. **`animateContentSize` 改为显式 spec**：`animateContentSize(tween(300, easing = Out))`（用户消息折叠）。
4. **换字组件 `AnimatedSummaryText` 补三项**：滑动距离改 `0.8em`（按字号换算）、去掉宽度补间（用 `SizeTransform(clip=false)` 仍会动画宽高，建议改为固定宽度容器 + 绝对定位叠放，等价 popLayout）、加 800ms 节流（末次文本在 250ms 内合并）。
5. **reduced-motion**：新增 `LocalMotionEnabled`，用 `ValueAnimator.areAnimatorsEnabled()`（API 26+）判断；关闭时所有时长归 0、无限动画停止（网页在 `css:426174` 关闭 stream/tool/loading，framer-motion 三处用 `matchMedia` 关闭）。
6. **不要新增 spring**：对话页唯一的回弹曲线是思考等级竖向填充条的 300ms `cubic-bezier(0.34,1.56,0.64,1)`（`js:2249342`），其余一律无回弹；Compose 现有 spring 应全部换成显式 tween。

---

## 3. 不应加动画的地方（明确清单）

1. **发送 ↔ 停止按钮**：网页是条件渲染二选一（`js:2317962`/`js:2318231`）。不要做交叉淡入、旋转、缩放或颜色补间。
2. **队列卡片出现/删除**：无 `AnimatePresence`、无入场类（`js:2638947`）。不要加 `AnimatedVisibility`；拖拽排序 200ms 仅用于 transform。
3. **队列暂停横幅**：条件渲染，无动画（`js:2638199`）。
4. **附件上传进度环**：`strokeDasharray` 直接跟随事件（`js:2314241`）。不要给圆环加 300ms 补间（会滞后于真实进度）；百分比文字也不要滚动动画。
5. **回到底部按钮显隐**：网页瞬时（`docs/chat/01:364`），不要加 fade/slide。
6. **时间线行 mount/unmount**：网页没有行级动画（`docs/chat/01:568`）；只有工具块/预览卡的 900ms 流式淡入。不要给 userInput/assistantText/marker 加行级入场。
7. **工具行状态图标**：工具摘要本身没有 spinner/状态圆点（`docs/chat/04:22`）；只有 hook 行、"加载完整字段"按钮、模型切换有 spinner。Compose 当前在 `StatusGlyph` 里给 running 加 `CircularProgressIndicator`（`RowComponents.kt:59`）属于**额外动画**，应改为静态图标或删除。
8. **运行态标签不要用透明度呼吸**：网页是 4s 线性渐变扫光（`css:341027`）。若暂不实现扫光，**宁可不加动画**，也不要保留 2s 呼吸（周期、机制都不对）。
9. **模型菜单项高亮**：无 transition（`js:715988`）。不要加颜色过渡。
10. **助手正文流式**：无打字机、无 caret、无逐字淡入（`docs/chat/03:23`、`:441`）。不要加。
11. **思考内容滚动**：网页直接赋值 `scrollTop`（无 smooth）；不要加 `animateScrollToItem` 式平滑滚动。
12. **工具摘要换字范围**：只动 primary/secondary text；leading icon、kindLabel、sourceLabel、statusNode 不参与（`js:872532` 附近）。不要给整行做位移。
13. **折叠容器不要回弹**：300ms `ease-in-out`，无 overshoot；不要用 `spring(dampingRatio<1)`。
14. **菜单/弹窗不要加弹性**：100ms 已是最快档；Material 默认的 120ms 缩放已够，不要加 overshoot。

---

## 4. Compose 现状清单（逐条）

| 位置 | 调用 | 问题 |
|---|---|---|
| `timeline/RowComponents.kt:41` | `animateFloatAsState(..., tween(200))` | 默认 easing = `FastOutSlowInEasing` = `(.4,0,.2,1)`，与网页工具箭头 `out` 不符；图标 ExpandMore 0→180 vs chevron-right 0→90 |
| `timeline/RowComponents.kt:85` | `StreamingDot`（1.1s alpha 呼吸） | **全项目零调用**，死代码；网页无此元素 |
| `timeline/RowComponents.kt:124` | `AnimatedContent` 300ms，slide `it/3`，`SizeTransform` | 滑动距离偏小；宽度补间网页没有；无 800ms 节流 |
| `timeline/TurnHeaderRow.kt:51` | `animateFloatAsState` 无 spec | 默认 spring；应为 150ms `Standard` |
| `timeline/ToolGroupRow.kt:90` | `animateFloatAsState` 无 spec | 默认 spring；应为 150ms |
| `timeline/ToolGroupRow.kt:133` | `AnimatedVisibility` 无 spec | 默认 spring；应为 300ms `Standard` |
| `timeline/ToolCallRow.kt:177` | `AnimatedVisibility` 无 spec | 同上 |
| `timeline/ToolCallRow.kt:409` | 2s alpha 呼吸 | 网页 4s 渐变扫光；机制不符 |
| `timeline/ReasoningRow.kt:84` | 2s alpha 呼吸 | 同上 |
| `timeline/ReasoningRow.kt:107` | `AnimatedVisibility` 无 spec | 应为 300ms `Standard` |
| `timeline/MarkerRow.kt:138` | 2s alpha 呼吸 | 同上 |
| `timeline/UserInputRow.kt:126` | `animateContentSize()` 无 spec | 默认 spring；应为 300ms `Out` |
| `timeline/UserInputRow.kt:215` | 展开按钮图标 `rotate(180f)` 瞬时 | 网页无旋转（图标互换），一致，无需改 |
| `timeline/Timeline.kt:114`/`:176` | `visibleUnits` 重算，行直接增删 | 轮次折叠无 300ms 高度+淡出（最大偏差） |
| `timeline/Timeline.kt:288` | 底部渐变 `AnimatedVisibility(fadeIn/fadeOut)` 无 spec | 网页底部渐变是静态的；显隐是否该有过渡未验证，建议瞬时 |
| `chat/ChatScreen.kt:579`/`:597` | 焦点边框色瞬时切换 | 网页 150ms `transition-colors` |
| `chat/ChatScreen.kt:757` | 发送/停止条件渲染 | 与网页一致，保持 |
| `chat/ChatScreen.kt:808` | Canvas 进度环，无补间 | 与网页一致，保持 |
| `chat/ChatScreen.kt:476` | 队列条件渲染，无动画 | 与网页一致（但缺拖拽排序） |
| `chat/ModelSheet.kt:65` | `Dialog` 底部弹层，无自定义动画 | 网页是 100ms dropdown；机制/参数都不同 |
| `chat/ChatScreen.kt:876` | `DropdownMenu`（Mode/Effort） | 网页是 Radix Select：100ms fade+zoom95%+方向滑入（`js:227802`）；Compose 是 DropdownMenu，机制/参数不同 |
| `theme/Dimens.kt:122`/`:160` | `ZemoteDuration`/`ZemoteEasing` | **零调用**，需接到上述调用点 |
| 全局 | 无 reduced-motion 处理 | 网页 stream/collapse 有降级；Compose 无 |

---

## 5. 未验证项

1. **Compose 默认 spec 的实际时长**：`expandVertically()`/`fadeIn()`/`animateContentSize()`/`animateFloatAsState` 的默认 spring 时长随位移变化，未逐帧实测；本文只断言"不是 300ms tween"。
2. **Material3 版本相关默认值**：`DropdownMenu`、`Dialog`、`CircularProgressIndicator` 的默认动画参数随 compose-material3 版本变化，未核对该项目锁定的版本常量。
3. **dock 首次出现是否动画**：代码判定为否（`AnimatePresence initial={false}`），未在浏览器运行时验证；也未验证 `mode` 变化时的真实帧率。
4. **`streamingEntranceActive` 的调用方**：未追到具体传参位置（只确认属性存在、每 key 只播一次、1000ms 后撤销）。
5. **`prefers-reduced-motion` 与 Android 系统设置的映射**：网页 `css:426174` 只关闭 4 类动画；Android 侧 `ValueAnimator.areAnimatorsEnabled()` 对 Compose 的 `tween` 是否生效未实测（Compose 动画不走 `ValueAnimator`，需自行读取）。
6. **附件进度环在 100% 时是否有完成态补间**：只确认 `strokeDasharray` 无 transition 类，未实测上传完成瞬间的视觉表现。
7. **`docs/chat/06-composer.md:30` 的"出现/切换有入场动画"**：本文按代码判定为"仅切换"，未做运行时录屏验证。
8. **`docs/chat/04-toolcall.md:123` 的 0.2s/ease-out**：本文判定为 300ms/ease-in-out（`W_` 的 `duration-300 ease-in-out` 覆盖了工具类默认值），未在浏览器 DevTools 里读 computed style 验证。
9. **`tool-call-progress-indeterminate` 在 Compose 是否需要补齐**：产品上是否会出现 fraction 缺省的工具进度，未从数据层确认。
10. **`Mode`/`Effort` Select 的打开方向/碰撞行为**：已确认是 Radix Select（`side=top`、`sideOffset=4`、`collisionPadding=8`），但 popper 在窄屏的实际落位与 `slide-in-from-*` 方向未运行时核对。

---

## 6. 对 `docs/chat/01..08` 的校正

| 文档位置 | 原文 | 校正 |
|---|---|---|
| `docs/chat/04-toolcall.md:123` | 工具折叠 `--tw-duration` 默认 **0.2s**、`ease-out` | 实际 300ms `cubic-bezier(.4,0,.2,1)`：`W_` 自带 `duration-300 ease-in-out`（`js:736071`），Tailwind 动画 shorthand 读取 `--tw-duration`/`--tw-ease` |
| `docs/chat/06-composer.md:30` | "dock 出现/切换有入场动画" | 仅**切换**有：`AnimatePresence initial={false}`，首帧不播（`js:3533485`） |
| `docs/chat/05-misc-rows.md:358` | 折叠容器 300ms | 正确，与本文一致 |
| `docs/chat/03-text-reasoning.md:269` | 300ms ease-in-out | 正确 |
| `docs/chat/02-turn-user.md:182` | 300ms ease-in-out | 正确 |
| `docs/chat/01-timeline.md:429` | 工具块淡入 0.9s expressive | 正确，补充：仅在 `streamingEntranceActive` 首次出现时播放，1000ms 后撤销（`js:3141379`） |
| `docs/chat/01-timeline.md:433` | 折叠关闭 CSS 未接线 | 正确（JS 全库 0 命中） |
| `docs/chat/01-timeline.md:431` | 流式 marker 颜色淡入 | CSS 存在，但 JS 无写入点（`data-zcode-stream-marker-animate` 0 命中），同属死规则 |
