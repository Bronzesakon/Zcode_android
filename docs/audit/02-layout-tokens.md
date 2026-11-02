# 02 — 布局令牌标准规范（Web 3.15.3 → Compose）

> 只读审计：除本文件外未修改任何文件；`Reference_web/` 未执行其中代码。
> 数据源：
> - `archive/pre-3.16.1-auth/Reference_web/3.15.3/assets/index-BD2BL4Cc.css`（455,351 B，SHA-256 `40894faf46c4aaf3508873aaa4e808eb1d41343029f6d8aa070f5b6a997ebc2f`，实测一致）
> - `archive/pre-3.16.1-auth/Reference_web/3.15.3/assets/index-BGa3KiRe.js`（6,592,643 B）
> - `design/tokens/layout.json`、`android/app/src/main/kotlin/app/zemote/ui/theme/Dimens.kt`
> - `docs/chat/01..08`（仅作交叉参考，本文数值均回产物重新核对）
>
> 偏移口径：`css:字节` / `js:字节` 均为文件**字节**偏移（Python 字节索引 / `grep -abo` 实测）。CSS 是单行压缩文件，没有行号。
> 统计口径：`js` 里的间距/圆角频次来自静态字符串扫描（对 `className` 一类字符串按 token 计数），同一字符串里的条件分支只计一次，因此是「出现次数」而非运行时 DOM 次数。

---

## 0. 结论摘要

1. **4px 基准成立，但不是严格 4px 网格。** 全站只有一个 `--spacing:.25rem`（`css:9267`），所有间距工具类都是 `calc(var(--spacing) * N)`；N 实际出现 0.5 / 0.75 / 1.25 / 1.5 / 2.5 / 3.5 / 4.5 / 7.5 / 8.5 等子步，即实际刻度含 **2px、3px、5px、6px、10px、14px、18px、30px、34px**。对话页高频值仍是 2/4/6/8/12/16/20/24px。
2. **圆角是 Tailwind 默认刻度，没有品牌改写。** `xs/sm/md/lg/xl/2xl/3xl = 2/4/6/8/12/16/24px`（`css:10247`–`css:10361`）；裸 `.rounded` = 4px（`css:87153`）；`.rounded-full` 是 `calc(infinity*1px)` 的浮点结果 3.40282e38px（`css:87868`），Compose 对应 `CircleShape`。
3. **边框默认 1px，颜色由语义令牌给出。** `.border` = 1px（`css:89896`），preflight 是 `border:0 solid`（`css:20519`），所以不写颜色时继承 `currentColor`；对话页统一用 `border-border`（`css:92922`）。亮色 `--color-border = #0A0A0A1A`（`css:12844`），暗色 `#FAFAFA1A`（`css:399277`），与 `Color.kt` 的 `borderArgb` 完全一致。
4. **阴影只有 6 档被使用，且只有 3 个变量。** 主题层只定义 `--shadow-sm/md/lg`（`css:10381`/`10441`/`10504`）；`shadow-xs/xl/2xl` 是工具类内联值（`css:163225`/`162968`/`160477`），没有对应变量。对话页实际用到 `shadow-xs/sm/md/lg/2xl` 与 `shadow-xl/5`。
5. **对话内容列有两级容器宽度。** `<864px` 全宽；`>=864px` 宽 `calc(100% - 96px)` 且 `max-w-4xl = 896px`；`>=1280px` 宽 `calc(100% - 384px)` 且 `max-w-6xl = 1152px`，同时 `translateX(-168px)`（`js:2726953`，规则在 `css:285593` / `css:286161`）。864px 不是容器令牌，是页面自定义容器查询阈值。
6. **Compose 令牌层基本齐全，但对话页几乎不用。** `ZemoteSpacing` 只被 `MarkdownText.kt`、`CodeBlock.kt`、`HomeScreen.kt` 引用；`ChatScreen.kt`、`timeline/*` 全部是裸 dp。`ZemoteBlur/ZemoteDuration/ZemoteEasing/ZemoteBreakpoint/ZemoteContainer/ZemoteZIndex/ZemoteAspectVideo` 目前**零调用**。
7. **缺三类令牌：边框宽度、阴影/海拔、容器查询断点。** Compose 侧没有任何 `border width` 常量、没有 `elevation` 常量；`ZemoteBreakpoint` 没有 448/624/864 三个对话容器阈值。
8. **裸值数值大多正确，问题在“无语义 + 分散”。** 圆角 16/12/8/6/2、间距 16/12/8/6/4/2、边框 1dp、阴影 elevation 2/3/8 都散落在调用点；`999.dp` 出现两次，应改 `CircleShape`。
9. **最大结构差距是响应式。** Compose 没有 `864/1280` 内容列收窄、没有 `@md(448px):px-6`、没有 `@624px` 气泡 `max-w-xl` 的容器查询；`UserInputRow` 的 576dp 上限是硬编码且无条件生效。

---

## 1. 令牌表

### 1.1 间距（Spacing）

#### 1.1.1 基准与常用档位

| 名称 | 网页值 | 证据偏移 | Compose 现状 | 差距 |
|---|---|---|---|---|
| `--spacing`（唯一基准） | `0.25rem` = 4px | `css:9267` | `ZemoteSpacing.Unit = 4.dp` | 一致 |
| `.gap-0.5` | 2px | `css:80991` | `space0_5 = 2.dp` | 一致 |
| `.gap-1` | 4px | `css:81031` | `space1 = 4.dp` | 一致 |
| `.gap-1.5` | 6px | `css:81067` | `space1_5 = 6.dp` | 一致 |
| `.gap-2` | 8px | `css:81108` | `space2 = 8.dp` | 一致 |
| `.gap-3` | 12px | `css:81185` | `space3 = 12.dp` | 一致 |
| `.gap-4` | 16px | `css:81221` | `space4 = 16.dp` | 一致 |
| `.gap-5` | 20px | `css:81257` | `space5 = 20.dp` | 一致 |
| `.p-1.5` | 6px | `css:135510` | `space1_5` | 一致 |
| `.p-3` | 12px | `css:135634` | `space3` | 一致 |
| `.px-2` | 8px | `css:136249` | `space2` | 一致 |
| `.px-3` | 12px | `css:136346` | `space3` | 一致 |
| `.px-4` | 16px | `css:136443` | `space4` | 一致 |
| `.px-6` | 24px | `css:136535` | `space6` | 一致 |
| `.py-1.5` | 6px | `css:137099` | `space1_5` | 一致 |
| `.py-2` | 8px | `css:137149` | `space2` | 一致 |
| `.py-3` | 12px | `css:137244` | `space3` | 一致 |
| `.mt-0.5` | 2px | `css:49548` | `space0_5` | 一致 |

CSS 实际发出的 spacing 乘数共 **68 个**（含负值）：`-42,-12,-10,-7,-6,-5,-4,-3,-2,-1.5,-1,-.5,0,.5,.75,1,1.25,1.5,2,2.5,3,3.5,4,4.5,5,6,7,7.5,8,8.5,9,10,11,12,13,14,15,16,18,20,24,25,28,29,32,36,38,39,40,44,48,50,52,56,58,60,64,66,72,80,84,90,96,100,128,168,3000,3001`。
其中 `100/128/168/3000/3001` 是 `sr-only`、虚拟占位等离屏技巧（168 = `translate-x-42`，3000/3001 = 12000/12004px），不是视觉布局值。

#### 1.1.2 对话页实际使用频次（静态字符串扫描，`js:2,000,000–3,500,000`）

| 工具类 | px | 次数 | | 工具类 | px | 次数 |
|---|---:|---:|---|---|---:|---:|
| `gap-2` | 8 | 133 | | `px-1.5` | 6 | 17 |
| `gap-1` | 4 | 64 | | `py-1` | 4 | 16 |
| `gap-1.5` | 6 | 46 | | `py-3` | 12 | 16 |
| `py-2` | 8 | 46 | | `px-1` | 4 | 14 |
| `gap-3` | 12 | 45 | | `space-y-2` | 8 | 14 |
| `px-3` | 12 | 44 | | `mt-1` | 4 | 13 |
| `px-2` | 8 | 43 | | `px-5` | 20 | 13 |
| `px-4` | 16 | 30 | | `gap-0.5` | 2 | 12 |
| `mt-0.5` | 2 | 23 | | `mb-2` | 8 | 11 |
| `p-3` | 12 | 20 | | `px-2.5` | 10 | 11 |
| `py-1.5` | 6 | 20 | | `py-0.5` | 2 | 11 |
| `gap-4` | 16 | 10 | | `gap-5` | 20 | 9 |
| `p-4` | 16 | 9 | | `px-6` | 24 | 9 |

结论：对话页的间距主力是 **2/4/6/8/12/16px**，20px 只用于 turn 内大块间隔（`gap-5`）与 `px-5`；24px 只出现在 `px-6`（容器 >=448px 的 turn 横向内边距）。

### 1.2 圆角（Radius）

| 名称 | 网页值 | 证据偏移 | Compose 现状 | 差距 |
|---|---|---|---|---|
| `--radius-xs` / `.rounded-xs` | 2px | `css:10247` / `css:88111` | `ZemoteRadius.xs = 2.dp` | 一致 |
| `--radius-sm` / `.rounded-sm` | 4px | `css:10267` / `css:88025` | `sm = 4.dp` | 一致 |
| `--radius-md` / `.rounded-md` | 6px | `css:10286` / `css:87952` | `md = 6.dp` | 一致 |
| `--radius-lg` / `.rounded-lg` | 8px | `css:10306` / `css:87909` | `lg = 8.dp` | 一致 |
| `--radius-xl` / `.rounded-xl` | 12px | `css:10324` / `css:88068` | `xl = 12.dp` | 一致 |
| `--radius-2xl` / `.rounded-2xl` | 16px | `css:10343` / `css:87183` | `xxl = 16.dp` | 一致 |
| `--radius-3xl` / `.rounded-3xl` | 24px | `css:10361` / `css:87285` | `xxxl = 24.dp` | 一致 |
| 裸 `.rounded` | 4px | `css:87153` | `sm` | 一致 |
| `.rounded-none` | 0 | `css:87995` | `noneShape` | 一致 |
| `.rounded-full` | 3.40282e38px（= infinity） | `css:87868` | `fullShape = CircleShape` | 一致；调用点仍有 `999.dp` |
| `.rounded-tr-xs`（用户气泡右上角） | 2px | `css:89335` | `RoundedCornerShape(topEnd = 2.dp)` | 值一致，未走令牌 |

CSS 里绕过令牌的圆角字面量：`1, 2, 3.2, 4, 5, 6, 7, 8, 9, 10, 12, 16, 20, 99, 999, 9999` px，以及 `35% / 50%`、`calc(var(--radius-sm) - 2px)`、`inherit`。对话页只用到 2/6/8/12/16/999（999 应视为 full）。

对话页圆角频次（同口径）：`rounded-lg` 72、`rounded-full` 52、`rounded-md` 33、`rounded-xl` 33、`rounded-2xl` 15、`rounded-sm` 14、裸 `rounded` 4、`rounded-none` 4、`rounded-tr-xs` 2、`rounded-3xl` 1。

### 1.3 边框（Border）

| 名称 | 网页值 | 证据偏移 | Compose 现状 | 差距 |
|---|---|---|---|---|
| 默认边框宽度 `.border` | 1px | `css:89896` | 无令牌；调用点写 `1.dp` | 建议新增 `ZemoteBorderWidth.hairline` |
| `.border-2` | 2px | `css:90018` | 无令牌 | 对话页未用 |
| `.border-[1.5px]` | 1.5px | `css:90018` 后 | 仅图标 `strokeWidth = 1.5.dp` | 不是布局边框 |
| `.border-[1.33px]` | 1.33px | `css:90018` 后 | 无 | 不建议复刻（高分屏舍入） |
| preflight 边框默认 | `border:0 solid`，未写颜色时 `currentColor` | `css:20519` | Compose 必须显式传色 | 无差异，但提醒不要漏色 |
| `.border-border` | `var(--color-border)` | `css:92922` | `colors.border` | 一致 |
| `.border-input-border` | `var(--color-input-border)` | `css:96519` | `colors.inputBorder` | 一致 |
| `--color-border` 亮 | `#0A0A0A1A`（neutral-950 10%） | `css:12844` | `borderArgb = 0x1A0A0A0A` | 一致 |
| `--color-border` 暗 | `#FAFAFA1A`（neutral-50 10%） | `css:399277` | `0x1AFAFAFA` | 一致 |
| `--color-border-hover` 亮 / 暗 | `#0A0A0A33` / `#FAFAFA4D` | `css:13018` / `css:399438` | `0x330A0A0A` / `0x4DFAFAFA` | 一致 |
| `--color-input-border` | `= --color-border` | `css:14276` | `inputBorder` | 一致 |
| `--color-input-border-focused` | `= --color-brand` | `css:14370` | `inputBorderFocused` | 一致 |

对话页边框类频次：`border-border` 73、裸 `border` 61、`border-t` 27、`border-b` 13、`border-l` 8、`border-0` 6、`border-r` 6、`border-[1.5px]` 4、`border-dashed` 4、`border-transparent` 4。**对话页没有 2px 边框**，全部是 1px hairline。

### 1.4 阴影（Shadow / Elevation）

| 名称 | 网页值 | 证据偏移 | Compose 现状 | 差距 |
|---|---|---|---|---|
| `.shadow-xs` | `0 1px 2px 0 #0000000D` | `css:163225`（**无 `--shadow-xs` 变量**） | 无令牌 | 建议 `elevation.xs` |
| `.shadow-sm` | `0 1px 3px 0 #0000001A, 0 1px 2px -1px #0000001A` | `css:10381` / `css:162717` | 无令牌；`UserInputRow.kt:197` elevation 2dp | 近似 |
| `.shadow-md` | `0 4px 6px -1px #0000001A, 0 2px 4px -2px #0000001A` | `css:10441` / `css:162103` | 无令牌；`Timeline.kt:317` elevation 3dp | 近似 |
| `.shadow-lg` | `0 10px 15px -3px #0000001A, 0 4px 6px -4px #0000001A` | `css:10504` / `css:161847` | 无令牌；`HookTrailerRow.kt:91` elevation 8dp | 近似 |
| `.shadow-xl` | `0 20px 25px -5px #0000001A, 0 8px 10px -6px #0000001A` | `css:162968`（无变量） | 无 | 未使用（仅 `shadow-xl/5`） |
| `.shadow-2xl` | `0 25px 50px -12px #00000040` | `css:160477`（无变量） | 无 | 未映射 |
| `.shadow`（裸） | 同 `shadow-sm` | `css:160229` | 无 | 不建议使用 |
| `.shadow-none` | `0 0 #0000` | `css:162358` | 无令牌 | 可用 `Modifier` 不叠加 |

要点：
- `--shadow-xs / xl / 2xl` **不存在**，只有 `.shadow-*` 工具类内联；`--shadow-sm/md/lg` 才是主题变量。
- 阴影色默认 `#0000001A`（10%），`shadow-2xl` 是 `#00000040`（25%）；`shadow-xl/5` 表示把阴影色降到 5%（`js:2319922`，highspeed 输入卡）。
- Compose 的 `shadowElevation` 是 Material 的 ambient/spot 阴影，**不等于** CSS 的 offset+blur，只能近似映射；1:1 复刻需自定义 `drawBehind` 或 `Modifier.shadow` + 颜色。

### 1.5 容器宽度与断点

| 名称 | 网页值 | 证据偏移 | Compose 现状 | 差距 |
|---|---|---|---|---|
| `--container-xs` | 320px | `css:9284` | `ZemoteContainer.xs` | 一致 |
| `--container-sm` | 384px | `css:9305` | `sm` | 一致 |
| `--container-md` | 448px | `css:9326` | `md` | 一致 |
| `--container-lg` | 512px | `css:9347` | `lg` | 一致 |
| `--container-xl` | 576px | `css:9368` | `xl` | 一致 |
| `--container-2xl` | 672px | `css:9389` | `xxl` | 一致 |
| `--container-3xl` | 768px | `css:9411` | `xxxl` | 一致 |
| `--container-4xl` | 896px | `css:9433` | `x4xl` | 一致 |
| `--container-5xl` | 1024px | `css:9455` | `x5xl` | 一致 |
| `--container-6xl` | 1152px | `css:9477` | `x6xl` | 一致 |
| 断点 sm / md / lg / xl / 2xl | 640 / 768 / 1024 / 1280 / 1536px | `css:29493` / `29543` / `29593` / `29643` / `29693` | `ZemoteBreakpoint` | 值一致，零调用 |
| 窄屏 | `width<=480px` | `css:396314` | `narrowPhone` | 零调用 |
| 矮视口 | `height<=740px` | `css:340045` | `shortViewport` | 零调用 |
| 对话容器 `@md/conversation` | 448px（28rem） | `css:287382` | 无 | 未实现 |
| 气泡容器 `@min-[624px]` | 624px → `max-w-xl` 576px | `css:285366` | `UserInputRow.kt:71` 硬编码 576dp | 值对、机制缺 |
| 内容列 `@min-[864px]` | `w = calc(100% - 96px)`，`max-w-4xl = 896px` | `css:285593`，类串 `js:2726953` | 无 | 结构差距 |
| 内容列 `@min-[1280px]` | `w = calc(100% - 384px)`，`max-w-6xl = 1152px` | `css:286161`，类串 `js:2726953` | 无 | 结构差距 |
| 内容列位移 `@1280` | `translateX(-168px)`（`-translate-x-42`） | `js:2726953` | 无 | 结构差距 |
| 空态列 | `max-w-2xl = 672px` | `js:2726937` | 无 | 未实现 |
| Composer 容器 | `@container/composer`，可选 `max-w-2xl = 672px` | `js:2319373` | 无 | 未实现 |

内容列公式（原样，`js:2726953`）：

```
w-full
@min-[864px]/conversation:w-[calc(100%_-_6rem)]  @min-[864px]/conversation:max-w-4xl
@min-[1280px]/conversation:w-[calc(100%_-_24rem)] @min-[1280px]/conversation:max-w-6xl
```

即：<864 全宽；864–1279 收 96px、封顶 896px；>=1280 收 384px、封顶 1152px，并整体左移 168px（给右侧面板让位）。移动端主要落在第一档，但平板/折叠屏展开会进入第二档。

### 1.6 z-index

| 名称 | 网页值 | 证据偏移 | Compose 现状 | 差距 |
|---|---|---|---|---|
| `z-0/1/2/10/20/30/40/50` | 0/1/2/10/20/30/40/50 | `css:28872`（`z-10`）等 | `ZemoteZIndex` 语义枚举 | 零调用 |
| `z-[60]` | 60 | `css:28995` | 无 | 建议补 `dragOverlay` |
| `z-[100]` | 100 | `css:29016` | 无 | 无对应场景 |
| `z-[9998]` | 9998 | `css:29039` | 无 | 逃生值 |
| `z-[9999]` | 9999 | `css:29064` | `toast = 9999` | 一致 |
| `z-[2147483647]` | 2147483647 | `css:29089` | 无 | 不建议照抄 |

对话页频次：`z-10` 14、`z-20` 7、`z-[1]` 6、`z-0` 3、`z-50` 3、`z-30` 2、`z-40` 2、`z-[2]` 2。语义：`z-20` = composer region / dock，`z-50` = popover/menu，`z-10` = 卡片内部覆盖层。

### 1.7 组件级内边距 / 外边距（对话页与输入区）

| 组件 | 网页 class（原样） | 解析值 | 证据偏移 | Compose 现状 | 差距 |
|---|---|---|---|---|---|
| Turn 段 | `relative mx-auto flex w-full flex-col gap-5 px-4 @md/conversation:px-6 pb-5` + `pt-0`/`pt-14` | gap 20；px 16（>=448 为 24）；pb 20；pt 0 或 56 | `js:3411454` | `Timeline.kt:174` gap 16；`Timeline.kt:368` padding 16/16/4/20 | gap 16≠20；缺 >=448 的 24；缺首段 56 顶距 |
| 行组 | `flex flex-col gap-4` | gap 16 | `docs/chat/07 §1.2`（转引） | `Timeline.kt:174` | 一致 |
| 用户气泡 | `flex max-w-full flex-col gap-2 rounded-xl rounded-tr-xs border border-border bg-surface px-4 py-3` + `@min-[624px]:max-w-xl` | gap 8；r 12/右上 2；1px；p 16/12；max 576 | `js:2778733`、`js:3476950` | `UserInputRow.kt:63/71/74/75` | 数值一致；624 容器查询缺失 |
| Composer dock 内容层 | `pointer-events-auto relative z-10 w-full shrink-0 ... px-4 pb-4` | px 16、pb 16 | `js:3473660` | `ChatScreen.kt:586` | 一致 |
| Composer 壳 | `relative flex flex-col gap-3 overflow-hidden rounded-2xl border border-input-border bg-input p-3` | gap 12；r 16；1px；p 12 | `js:2093538` | `ChatScreen.kt:593/596/598/600/601` | 一致 |
| Composer region | `chat-composer-region z-20 w-full shrink-0 @container/composer`（条件 `max-w-2xl`） | z 20；容器查询；可选 max 672 | `js:2319373` | 无 | 机制缺 |
| 时间线滚动内层 | `flex min-h-full flex-col items-center px-4 before:min-h-[52px] before:basis-[29dvh] after:min-h-4` | px 16；顶部虚拟占位 29dvh 且至少 52px；底部 16 | `js:3470808` / `js:3471228` | `Timeline.kt:368` | 顶部占位缺失（Compose 只有 4dp） |
| 推理块 | `max-h-60`、左边线 1px、`pl-3.5`、`pt-3` | 240 / 1 / 14 / 12 | `docs/chat/07`（转引） | `ReasoningRow.kt:115/121/127/129` | 数值一致 |
| 工具行摘要 | 图标/文字 gap 8、展开 padding-top 8 | 8 / 8 | `docs/chat/07`（转引） | `ToolCallRow.kt:103/289` | 基本一致 |
| Hook 弹层 | `shadow-lg` + 圆角 12 | 8 / 12 | 转引 | `HookTrailerRow.kt:89/91` | 近似 |

---

## 2. 建议新增的 spacing / radius / border / elevation 常量清单

### 2.1 `ZemoteSpacing`（补缺口，不重排现有）

对话页不需要新档位；下列是 CSS 已实际发出、但 `Dimens.kt` 没有具名常量的档位。若只做对话页可先不加，做全应用复刻时再补：

```kotlin
val space7_5: Dp = of(7.5f)   // 30px
val space8_5: Dp = of(8.5f)   // 34px
val space13: Dp  = of(13)     // 52px
val space15: Dp  = of(15)     // 60px
val space25: Dp  = of(25)     // 100px
val space28: Dp  = of(28)     // 112px
val space29: Dp  = of(29)     // 116px
val space36: Dp  = of(36)     // 144px
val space38: Dp  = of(38)     // 152px
val space39: Dp  = of(39)     // 156px
val space44: Dp  = of(44)     // 176px
val space50: Dp  = of(50)     // 200px
val space52: Dp  = of(52)     // 208px
val space58: Dp  = of(58)     // 232px
val space60: Dp  = of(60)     // 240px
val space66: Dp  = of(66)     // 264px
val space84: Dp  = of(84)     // 336px
val space90: Dp  = of(90)     // 360px
val space100: Dp = of(100)    // 400px
val space168: Dp = of(168)    // 672px
```

建议再补语义别名（对话页专用，避免调用点继续写 16/12/8）：

```kotlin
object ZemoteChatSpacing {
    val gutter = ZemoteSpacing.space4        // 16，turn/滚动层横向
    val turnGap = ZemoteSpacing.space5       // 20，turn 内块间隔
    val rowGap = ZemoteSpacing.space4        // 16，行组间隔
    val composerPadding = ZemoteSpacing.space3 // 12
    val dockPadding = ZemoteSpacing.space4   // 16
    val chipGap = ZemoteSpacing.space1_5     // 6
}
```

### 2.2 `ZemoteRadius`（刻度已齐，补语义 shape）

```kotlin
object ZemoteChatShape {
    /** 用户气泡：rounded-xl + rounded-tr-xs */
    val userBubble: Shape = RoundedCornerShape(topStart = 12.dp, topEnd = 2.dp,
                                               bottomEnd = 12.dp, bottomStart = 12.dp)
    /** Composer：rounded-2xl */
    val composer: Shape = ZemoteRadius.xxlShape
    /** 胶囊/chip：rounded-full */
    val pill: Shape = ZemoteRadius.fullShape
}
```

不要新增 `full: Dp`；`999.dp` 统一换成 `CircleShape`（见 §3）。

### 2.3 `ZemoteBorderWidth`（新增）

```kotlin
object ZemoteBorderWidth {
    /** CSS .border = 1px；对话页全部边框都用它 */
    val hairline: Dp = 1.dp
    /** CSS .border-2 = 2px；对话页未用，保留给选中态/强调 */
    val emphasis: Dp = 2.dp
}
```

`1.5px` / `1.33px` 只出现在图标描边，不要混进边框令牌；图标仍用 `strokeWidth = 1.5.dp`。

### 2.4 `ZemoteElevation`（新增，CSS 阴影的近似映射）

```kotlin
object ZemoteElevation {
    val xs: Dp = 1.dp   // .shadow-xs  0 1px 2px rgba(0,0,0,.05)
    val sm: Dp = 2.dp   // .shadow-sm  0 1px 3px / 0 1px 2px
    val md: Dp = 4.dp   // .shadow-md  0 4px 6px / 0 2px 4px
    val lg: Dp = 8.dp   // .shadow-lg  0 10px 15px / 0 4px 6px
    val xl: Dp = 12.dp  // .shadow-xl  0 20px 25px / 0 8px 10px
    val xxl: Dp = 16.dp // .shadow-2xl 0 25px 50px
}
```

必须注明：这是**近似**，Compose elevation 与 CSS offset/blur 不是同一模型；`shadow-2xl` 的 25% 黑与 `shadow-xl/5` 的 5% 黑无法用单一 elevation 表达，需要时用 `Modifier.shadow(elevation, shape, ambientColor, spotColor)` 指定颜色。

### 2.5 容器与断点补充

```kotlin
object ZemoteChatContainer {
    val emptyStateMax: Dp = 672.dp   // max-w-2xl
    val composerMax: Dp = 672.dp     // max-w-2xl（composer region）
    val bubbleMax: Dp = 576.dp       // max-w-xl（@624 气泡）
    val columnMax: Dp = 896.dp       // max-w-4xl（@864）
    val columnMaxWide: Dp = 1152.dp  // max-w-6xl（@1280）
    val columnInset: Dp = 96.dp      // 6rem
    val columnInsetWide: Dp = 384.dp // 24rem
    val columnShiftWide: Dp = 168.dp // -translate-x-42
}

object ZemoteChatBreakpoint {        // 容器查询阈值（不是窗口断点）
    val md: Dp = 448.dp              // @md/conversation（28rem）
    val bubbleWide: Dp = 624.dp      // @min-[624px]/conversation
    val columnWide: Dp = 864.dp      // @min-[864px]/conversation
    val columnUltra: Dp = 1280.dp    // @min-[1280px]/conversation
}
```

### 2.6 z-index 补充

```kotlin
object ZemoteZIndex {
    // 现有 background/content/sticky/dropdown/overlay/modal/toast 保留
    const val composerDock = 20  // chat-composer-region / dock
    const val popover = 50       // menu/popover
    const val dragOverlay = 60   // z-[60]
}
```

---

## 3. 调用点裸值清单（硬编码 dp）

> 说明：只列对话页/输入区与 theme 直接相关项；`size(...)` 这类纯图标尺寸也一并列出，方便判断哪些该进令牌、哪些应留在组件内。
> 路径前缀统一为 `android/app/src/main/kotlin/app/zemote/`。

### 3.1 `ui/chat/ChatScreen.kt`

| file:line | 裸值 | 建议 |
|---|---|---|
| `ui/chat/ChatScreen.kt:337` | `height(48.dp)` | 顶栏高度，组件常量 |
| `ui/chat/ChatScreen.kt:338` | `padding(start = 2.dp, end = 2.dp)` | `space0_5` |
| `ui/chat/ChatScreen.kt:341` | `size(36.dp)` | 图标按钮尺寸，组件常量 |
| `ui/chat/ChatScreen.kt:342` | `size(18.dp)` | 图标尺寸 |
| `ui/chat/ChatScreen.kt:360` | `padding(start = 8.dp)` | `space2` |
| `ui/chat/ChatScreen.kt:361` | `widthIn(max = 150.dp)` | 标题最大宽，组件常量 |
| `ui/chat/ChatScreen.kt:402` | `padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)` | `space3/space1/space1_5/space1_5` |
| `ui/chat/ChatScreen.kt:405` | `size(16.dp)` | 图标尺寸 |
| `ui/chat/ChatScreen.kt:408` | `padding(horizontal = 8.dp)` | `space2` |
| `ui/chat/ChatScreen.kt:415` | `size(28.dp)` | 图标按钮尺寸 |
| `ui/chat/ChatScreen.kt:416` | `size(16.dp)` | 图标尺寸 |
| `ui/chat/ChatScreen.kt:444` | `padding(horizontal = 12.dp, vertical = 4.dp)` | `space3/space1` |
| `ui/chat/ChatScreen.kt:445` | `RoundedCornerShape(12.dp)` | `ZemoteRadius.xlShape` |
| `ui/chat/ChatScreen.kt:447` | `border(1.dp, ..., RoundedCornerShape(12.dp))` | `BorderWidth.hairline` + `xlShape` |
| `ui/chat/ChatScreen.kt:448` | `padding(12.dp)` | `space3` |
| `ui/chat/ChatScreen.kt:456` | `padding(top = 4.dp)` | `space1` |
| `ui/chat/ChatScreen.kt:460` | `height(8.dp)` | `space2` |
| `ui/chat/ChatScreen.kt:461` | `spacedBy(8.dp)` | `space2` |
| `ui/chat/ChatScreen.kt:488` | `padding(horizontal = 12.dp, vertical = 2.dp)` | `space3/space0_5` |
| `ui/chat/ChatScreen.kt:494` | `padding(bottom = 4.dp)` | `space1` |
| `ui/chat/ChatScreen.kt:495` | `RoundedCornerShape(12.dp)` | `xlShape` |
| `ui/chat/ChatScreen.kt:497` | `border(1.dp, ..., RoundedCornerShape(12.dp))` | `hairline` + `xlShape` |
| `ui/chat/ChatScreen.kt:498` | `padding(horizontal = 12.dp, vertical = 8.dp)` | `space3/space2` |
| `ui/chat/ChatScreen.kt:526` | `padding(vertical = 2.dp)` | `space0_5` |
| `ui/chat/ChatScreen.kt:527` | `RoundedCornerShape(8.dp)` | `lgShape` |
| `ui/chat/ChatScreen.kt:529` | `padding(horizontal = 10.dp, vertical = 6.dp)` | `space2_5/space1_5` |
| **`ui/chat/ChatScreen.kt:586`** | `padding(start/end/bottom = 16.dp)` | `dockPadding`（space4） |
| **`ui/chat/ChatScreen.kt:593`** | `RoundedCornerShape(16.dp)` | `ZemoteRadius.xxlShape` |
| **`ui/chat/ChatScreen.kt:596`** | `width = 1.dp` | `ZemoteBorderWidth.hairline` |
| **`ui/chat/ChatScreen.kt:598`** | `RoundedCornerShape(16.dp)` | `xxlShape` |
| **`ui/chat/ChatScreen.kt:600`** | `padding(12.dp)` | `space3` |
| **`ui/chat/ChatScreen.kt:601`** | `spacedBy(12.dp)` | `space3` |
| `ui/chat/ChatScreen.kt:608` | `spacedBy(8.dp)` | `space2` |
| `ui/chat/ChatScreen.kt:614` | `height(48.dp)` | 附件 chip 高度 |
| `ui/chat/ChatScreen.kt:615` | `RoundedCornerShape(8.dp)` | `lgShape` |
| `ui/chat/ChatScreen.kt:617` | `border(1.dp, ..., RoundedCornerShape(8.dp))` | `hairline` + `lgShape` |
| `ui/chat/ChatScreen.kt:618` | `padding(start = 6.dp, end = 2.dp)` | `space1_5/space0_5` |
| `ui/chat/ChatScreen.kt:624` | `RoundedCornerShape(6.dp)` | `mdShape` |
| `ui/chat/ChatScreen.kt:638` | `width(8.dp)` | `space2` |
| `ui/chat/ChatScreen.kt:639` | `widthIn(max = 160.dp)` | 文件名最大宽 |
| `ui/chat/ChatScreen.kt:683` | `heightIn(min = 40.dp, max = 160.dp)` | 输入框高度区间 |
| `ui/chat/ChatScreen.kt:704` | `spacedBy(12.dp)` | `space3` |
| `ui/chat/ChatScreen.kt:727` | `spacedBy(6.dp)` | `space1_5` |
| `ui/chat/ChatScreen.kt:732` | `PaddingValues(horizontal = 6.dp, vertical = 0.dp)` | `space1_5/space0` |
| `ui/chat/ChatScreen.kt:733` | `widthIn(max = 110.dp)` | 模型名最大宽 |
| `ui/chat/ChatScreen.kt:756` / `:774` | `size(32.dp)` + `RoundedCornerShape(8.dp)` | `lgShape` |
| **`ui/chat/ChatScreen.kt:854`** | `RoundedCornerShape(999.dp)` | 改 `ZemoteRadius.fullShape` |
| `ui/chat/ChatScreen.kt:856` | `padding(horizontal = 8.dp, vertical = 4.dp)` | `space2/space1` |
| `ui/chat/ChatScreen.kt:904` | `420.dp` | 侧栏宽度上限 |
| `ui/chat/ChatScreen.kt:919` | `padding(16.dp)` | `space4` |

### 3.2 `ui/chat/timeline/Timeline.kt`

| file:line | 裸值 | 建议 |
|---|---|---|
| `timeline/Timeline.kt:128` | `48.dp.roundToPx()` | 近顶阈值，组件常量 |
| **`timeline/Timeline.kt:174`** | `spacedBy(16.dp)` | web turn gap 20 → 应改 `space5` 或按行类型区分 |
| `timeline/Timeline.kt:297` | `height(40.dp)` | 回到底部按钮 |
| **`timeline/Timeline.kt:317`** | `shadowElevation = 3.dp` | `ZemoteElevation.md`（近似） |
| `timeline/Timeline.kt:318` | `padding(bottom = 10.dp)` | `space2_5` |
| `timeline/Timeline.kt:324` | `padding(8.dp).size(16.dp)` | `space2` + 图标尺寸 |
| `timeline/Timeline.kt:348` | `padding(vertical = 2.dp)` | `space0_5` |
| **`timeline/Timeline.kt:368`** | `PaddingValues(16/16/4/20.dp)` | `space4/space4/space1/space5`；顶部 4 与 web 的 52+29dvh 占位不符 |

### 3.3 `ui/chat/timeline/UserInputRow.kt`

| file:line | 裸值 | 建议 |
|---|---|---|
| **`timeline/UserInputRow.kt:63`** | `RoundedCornerShape(12/2/12/12.dp)` | `ZemoteChatShape.userBubble` |
| **`timeline/UserInputRow.kt:71`** | `widthIn(max = 576.dp)` | `bubbleMax`，并补 624 容器查询 |
| **`timeline/UserInputRow.kt:74`** | `border(1.dp, ...)` | `hairline` |
| **`timeline/UserInputRow.kt:75`** | `padding(horizontal = 16.dp, vertical = 12.dp)` | `space4/space3` |
| `timeline/UserInputRow.kt:81` | `height(6.dp)` | `space1_5` |
| `timeline/UserInputRow.kt:91` / `:92` | `size(80.dp)` + `RoundedCornerShape(12.dp)` | `xlShape` |
| `timeline/UserInputRow.kt:96` / `:99` | `height(8.dp)` | `space2` |
| `timeline/UserInputRow.kt:190` | `padding(top = 8.dp)` | `space2` |
| **`timeline/UserInputRow.kt:197`** | `shadowElevation = 2.dp` | `ZemoteElevation.sm` |
| `timeline/UserInputRow.kt:204` / `:205` | `padding(4.dp)` + `size(16.dp)` | `space1` + 图标尺寸 |
| `timeline/UserInputRow.kt:212` | `COLLAPSED_HEIGHT = 120.dp` | 折叠高度，组件常量 |
| **`timeline/UserInputRow.kt:221`** | `RoundedCornerShape(999.dp)` | 改 `fullShape` |
| `timeline/UserInputRow.kt:223` | `padding(horizontal = 12.dp, vertical = 7.dp)` | `space3` + 7dp（无令牌，建议改 8 或加 `space1_75`） |
| `timeline/UserInputRow.kt:230` / `:232` | `size(16.dp)` + `width(6.dp)` | `space1_5` |

### 3.4 `ui/chat/timeline/ToolCallRow.kt`

| file:line | 裸值 | 建议 |
|---|---|---|
| `timeline/ToolCallRow.kt:101` / `:149` | `size(16.dp)` | 图标尺寸 |
| `timeline/ToolCallRow.kt:103` / `:117` / `:127` / `:129` / `:151` / `:162` / `:164` / `:263` | `width(8.dp)` | `space2` |
| `timeline/ToolCallRow.kt:185` | `padding(10/10/8/8.dp)` | `space2_5/space2` |
| `timeline/ToolCallRow.kt:212` / `:217` / `:222` / `:307` | `size(6.dp)` | `space1_5` |
| `timeline/ToolCallRow.kt:229` / `:339` / `:368` / `:393` | `size(2.dp/4.dp)` | `space0_5/space1` |
| `timeline/ToolCallRow.kt:236` / `:346` | `RoundedCornerShape(8.dp)` + `padding(12.dp)` | `lgShape` + `space3` |
| `timeline/ToolCallRow.kt:275` | `heightIn(max = 200.dp)` | 输出最大高 |
| `timeline/ToolCallRow.kt:289` | `padding(top = 4.dp)` | `space1` |
| `timeline/ToolCallRow.kt:311` | `heightIn(max = 100.dp)` | 输出最大高 |
| `timeline/ToolCallRow.kt:363` / `:377` | `border(1.dp, ..., RoundedCornerShape(12.dp))` + `padding(12.dp)` | `hairline` + `xlShape` + `space3` |
| `timeline/ToolCallRow.kt:375` / `:400` | `heightIn(max = 288/240.dp)` | 输出最大高 |

### 3.5 其余时间线行

| file:line | 裸值 | 建议 |
|---|---|---|
| `timeline/ReasoningRow.kt:80` / `:82` | `size(16.dp)` + `width(6.dp)` | 图标 + `space1_5` |
| `timeline/ReasoningRow.kt:115` | `padding(top = 12.dp)` | `space3` |
| `timeline/ReasoningRow.kt:121` | `Size(1.dp.toPx(), ...)` | `hairline` |
| `timeline/ReasoningRow.kt:127` | `padding(start = 14.dp)` | `space3_5` |
| `timeline/ReasoningRow.kt:129` | `heightIn(max = 240.dp)` | 推理块最大高 |
| `timeline/ReasoningRow.kt:146` / `:157` | `height(24.dp)` | 折叠按钮高度 |
| `timeline/ToolGroupRow.kt:100` / `:102` | `RoundedCornerShape(8.dp)` + `padding(10/8.dp)` | `lgShape` + `space2_5/space2` |
| `timeline/ToolGroupRow.kt:109` / `:111` / `:118` / `:159` | `size(16.dp)` / `width(8.dp)` | 图标 + `space2` |
| `timeline/ToolGroupRow.kt:129` | `size(14.dp)` | 图标尺寸 |
| `timeline/ToolGroupRow.kt:141` / `:150` | `padding(start = 8/14.dp)` | `space2/space3_5` |
| `timeline/ToolGroupRow.kt:145` | `width(1.dp)` | `hairline` |
| `timeline/HookTrailerRow.kt:62` | `padding(start = 4.dp, top = 4.dp)` | `space1` |
| `timeline/HookTrailerRow.kt:65` / `:71` | `size(24.dp)` / `size(14.dp)` | 图标尺寸 |
| `timeline/HookTrailerRow.kt:89` | `RoundedCornerShape(12.dp)` | `xlShape` |
| **`timeline/HookTrailerRow.kt:91`** | `shadowElevation = 8.dp` | `ZemoteElevation.lg` |
| `timeline/HookTrailerRow.kt:92` | `width(320.dp)` | 弹层宽 |
| `timeline/HookTrailerRow.kt:99` | `padding(horizontal = 12.dp, vertical = 8.dp)` | `space3/space2` |
| `timeline/HookTrailerRow.kt:108` | `heightIn(max = 320.dp).padding(vertical = 2.dp)` | `space0_5` |
| `timeline/HookTrailerRow.kt:125` / `:127` | `padding(12/6.dp)` + `spacedBy(8.dp)` | `space3/space1_5/space2` |
| `timeline/HookTrailerRow.kt:150` | `width(4.dp)` | `space1` |
| `timeline/HookTrailerRow.kt:173` / `:174` | `size(12.dp)` + `strokeWidth = 1.5.dp` | 图标描边 |
| `timeline/MarkerRow.kt:65` / `:73` | `spacedBy(12.dp)` / `spacedBy(6.dp)` | `space3/space1_5` |
| `timeline/MarkerRow.kt:68` / `:94` | `widthIn(min = 32.dp)` | 分隔线最小宽 |
| `timeline/MarkerRow.kt:81` | `size(14.dp)` | 图标尺寸 |
| `timeline/ArtifactRow.kt:41` / `:42` | `padding(start = 4.dp)` + `RoundedCornerShape(8.dp)` | `space1` + `lgShape` |
| `timeline/ArtifactRow.kt:44` / `:51` / `:53` | `padding(12/10.dp)` + `size(16.dp)` + `width(8.dp)` | `space3/space2_5` + 图标 + `space2` |
| `timeline/TurnHeaderRow.kt:61` / `:63` | `padding(top = 40.dp, bottom = 8.dp)` + `spacedBy(8.dp)` | 顶部 40 是组件常量；`space2` |
| `timeline/AssistantTextRow.kt:24` | `padding(vertical = 0.dp)` | `space0` |
| `markdown/MarkdownText.kt:180` / `:197` | `width(3.dp)` / `thickness = 1.dp` | 引用线 / `hairline` |
| `markdown/MarkdownText.kt:223` / `:236` | `padding(top = 3.dp/1.dp)` | 3dp 无令牌；`space1` |
| `markdown/MarkdownText.kt:256` / `:260` | `size(14.dp)` / `width(1.dp)` | 图标 / `hairline` |
| `markdown/MarkdownText.kt:292` | `border(1.dp, ...)` | `hairline` |
| `markdown/MarkdownText.kt:299` | `size(36.dp)` | 图片占位 |
| `markdown/MarkdownText.kt:356` | `coerceIn(48.dp, 280.dp)` | 表格宽区间 |
| `markdown/CodeBlock.kt:102` | `border(1.dp, ...)` | `hairline` |
| `markdown/CodeBlock.kt:115` / `:130` / `:142` | `size(16/28/14.dp)` | 图标尺寸 |

### 3.6 `ui/chat/ModelSheet.kt`

| file:line | 裸值 | 建议 |
|---|---|---|
| `ModelSheet.kt:80` / `:81` | `heightIn(max = 560.dp)` + `RoundedCornerShape(topStart/topEnd = 16.dp)` | 底部弹层：`xxlShape` |
| `ModelSheet.kt:88` | `padding(start = 16, end = 4, top = 8, bottom = 4.dp)` | `space4/space1/space2/space1` |
| `ModelSheet.kt:108` / `:113` | `size(36.dp)` / `size(18.dp)` | 按钮 / 图标 |
| `ModelSheet.kt:121` | `padding(32.dp)` | `space8` |
| `ModelSheet.kt:125` / `:126` | `size(20.dp)` + `strokeWidth = 1.5.dp` | 图标描边 |
| `ModelSheet.kt:132` | `padding(24.dp)` | `space6` |
| `ModelSheet.kt:153-156` | `padding(16/16/12/4.dp)` | `space4/space3/space1` |
| `ModelSheet.kt:183` / `:195` / `:200` | `padding(16/10.dp)` + `width(8.dp)` + `size(16.dp)` | `space4/space2_5/space2` |

---

## 4. 未验证项

1. **CSS 阴影与 Compose elevation 的视觉等价性未实测。** 本文只给出近似映射；`shadow-2xl` 的 25% 黑、`shadow-xl/5` 的 5% 黑无法用单一 elevation 表达。需要真机截图对比后才能定稿。
2. **Composer dock 内容层的实际最大宽未静态解析出来。** `js:3473660` 的 class 串里宽度来自运行时变量 `Qe`（同一函数内未找到可静态求值的赋值），只能确认它包含 `w-full` 与内容列过渡；是否复用 `max-w-4xl/6xl` 未证实。
3. **JS 频次是静态字符串计数，不等于运行时 DOM 次数。** 条件分支（如 `le?`/`ue?`）里的两套 class 都会各计一次；移动端运行时是否会走不同分支未验证。
4. **864/1280 容器查询在移动端是否触发未验证。** 手机竖屏宽度通常 <864dp，主要影响平板/折叠屏展开；Android 侧尚未引入 `WindowSizeClass`，实际行为需真机确认。
5. **Web 的 `pt-14`（56px）与滚动层 `before:basis-[29dvh]` 顶距，在 Compose 里如何等价尚未定案。** 本文只记录差距，未给出实现方案。
6. **`docs/chat/07` 转引的两条（行组 gap、推理/工具行）未重新定位 JS 偏移。** 其余数值均已回 CSS/JS 复核。
7. **`1.33px` 边框的用途未确认。** 只知其存在于 CSS，未定位到具体组件；移动端高 DPI 下不建议复刻。
8. **z-index 的语义归属只按类名出现位置推断**（composer/dock=20、popover=50、内部覆盖=10），CSS 没有 `--z-*` 变量或注释，无法从产物证明设计意图。
