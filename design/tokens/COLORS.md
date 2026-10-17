# 颜色令牌（colors）

**来源**：`Reference_web/3.15.3/assets/index-BD2BL4Cc.css`（remote v4 / 3.15.3 快照，2026-10-15 抓取，
455,351 B，SHA-256 `40894faf46c4aaf3508873aaa4e808eb1d41343029f6d8aa070f5b6a997ebc2f`）；
辅以 `Reference_web/3.15.3/index.html`（启动壳与 `theme-color`）。
**技术栈**：React + Tailwind CSS v4 + lucide。CSS 是压缩产物，本文件所有结论都标注了选择器/条件出处。

配套数据：`tokens/colors.json`（逐条原始值 + 上下文 + 换算值，本文件不重复整张表）。

---

## 1. 令牌三类与规模

| 类别 | JSON 键 | 数量 | 说明 |
|---|---|---:|---|
| (a) Tailwind 基础调色板 | `base_palette` | **127** | `--color-{家族}-{档位}`，值是 `oklch(...)` 原文；外加 `--color-white`/`--color-black`。共 20 个色家族，档位是**按实际使用裁剪过的**（Tailwind v4 会 tree-shake 没用到的主题变量），每个家族只保留 2–10 档，不是完整 11 档。 |
| (b) 应用语义令牌 | `semantic` | **181** | `--color-*` 中不属于调色板的：`--color-background`、`--color-accent`、`--color-foreground-subtle`、`--color-icon-purple`、`--color-git-added`、`--color-file-node` 等。**界面复刻主要用这一类。** |
| (c) 其它颜色相关变量 | `other_color` | **96** | 渐变、阴影色、透明度变体、prose 配色：`--animated-gradient-text-soft`、`--tw-gradient-*`、`--tw-shadow-*`、`--lucide-subtlest-opacity`、`--lucide-stroke` 等。 |
| — 非颜色变量（仅索引） | `non_color_token_index` | 147 | 字号/间距/圆角/动效等，只有名字，不属本文件范围。 |

全 CSS 共 **1,538 条**自定义属性声明，**551 个**不同变量名，全部已解析入库（解析结果与正则直数逐一核对一致）。

`colors.json` 每个令牌记录：全部声明（`context` / `selector` / `at_rules` / `conditions` / 字节偏移 `offset` /
原始值 `raw` / 该声明在各主题下的 `hex`），加上合并后的 `light`、`dark` 生效值。
引用型（`var()` / `color-mix()`）额外给 `reference_target` 与 `reference_form`，并已静态解析到最终值。

---

## 2. 明暗两套：机制与优先级（**这是复刻时最容易搞错的一点**）

同一个变量名在 4 个位置出现，**不是简单的「`:root` 是亮、`.dark` 是暗」**：

| 选择器 | 层 | 角色 |
|---|---|---|
| `:root,:host` | `@layer theme` | Tailwind v4 基础调色板 + 中性默认值（**亮色基准**） |
| `.dark` | 无 layer | 暗色覆盖 |
| `.theme-zai-light` | 无 layer | **应用自己的亮色主题（zai-light）** |
| `.theme-zai-dark` | 无 layer | **应用自己的暗色主题（zai-dark）** |

关键事实：

1. **`@layer` 内的规则优先级低于无 layer 规则**，所以 `.theme-zai-*` 一定压过 `@layer theme` 里的 `:root,:host`。
2. `index.html` 里 `DEFAULT_THEME = "zai-dark"`，暗色时给 `<html>` 加 `.dark`；即实际运行时
   **亮 = `:root` + `.theme-zai-light`**，**暗 = `.dark` + `.theme-zai-dark`**。zai 主题只定义 143 个令牌，
   其余全部回落到 `:root` / `.dark`。
3. `@supports (color:color-mix(in lab, red, red))` 分支**总是生效**（现代引擎都支持），且排在
   它的 hex 兜底之后 → 同优先级下**后者胜出**。所以 `--color-border` 真正取的是
   `color-mix(in oklab, var(--color-neutral-950) 10%, transparent)`，而前面那句 `#0a0a0a1a` 只是给老引擎的兜底。
4. `@media (prefers-color-scheme:dark)` **确实存在**，但**没有任何 `--color-*` 令牌在里面被重新赋值**。
   全 CSS 共 37 条声明落在该媒体查询内，全部是 Tailwind 的 `.dark\:*` 工具类变体
   （`.dark\:prose-invert` 里的 18 个 `--tw-prose-*`、`.dark\:invert` 里的 `--tw-invert`、
   `--lucide-stroke`/`--lucide-filter`，以及若干 `.dark\:text-*`/`.dark\:ring-*`）。
   **本构建里 `dark:` 变体一律编译成媒体查询，没有任何 `.dark` 类选择器形式的变体**
   （已核实：`.where(.dark` 出现 0 次）。
   → **`--color-*` 语义令牌跟随 `.dark` 类（应用主题），而 `.dark:` 工具类跟随系统偏好**，
   两者在浏览器里可以不一致。Android 侧对应关系：语义令牌跟应用主题，
   `.dark:` 工具类跟 `isSystemInDarkTheme()`。
5. 主题切换完全靠 class，`index.html` 自己读 `localStorage["zcode-theme"]` 或
   `matchMedia("(prefers-color-scheme: dark)")` 来加 `.dark`。

`colors.json` 的 `_meta.cascade_model` 记录了完整优先栈；`light` / `dark` 两个字段就是按该栈算出的生效值。

**跨主题差异举例**（说明为什么不能只抄一套）：

| 令牌 | light | dark |
|---|---|---|
| `--color-background` | `#F8F8F8` | `#161616` |
| `--color-sidebar` | `#F0F0F0` | `#161616` |
| `--color-panel` | `#FFFFFF` | `#202020` |
| `--color-card` | `#FFFFFF` | `#2B2B2B` |
| `--color-foreground` | `#262626` | `#D4D4D4` |
| `--color-border` | `#0D0D0D1A` | `#FFFFFF1A` |
| `--color-accent` | `#EBF4FF` | `#001D3D` |
| `--color-primary` | `#000000` | `#FFFFFF` |

（`index.html` 的 `theme-color` 也印证：暗 `#161616`、亮 `#f8f8f8`。）

---

## 3. 换算方法与精度

调色板值是 `oklch(...)`，必须自己换算。用的公式（`float64` 全程）：

```
oklch(L C H)  ->  oklab(L a b):   a = C·cos(H°),  b = C·sin(H°)
oklab         ->  linear sRGB:    用 Bjorn Ottosson 参考实现的两组精确矩阵
                                  (LMS←linear RGB, Oklab←LMS', 及其逆矩阵)
linear sRGB   ->  sRGB:           IEC 61966-2-1 / CSS Color 4 传递函数
                                  x ≤ 0.0031308 ? 12.92x : 1.055·x^(1/2.4) − 0.055
sRGB          ->  8bit:           逐通道 clamp 到 [0,1]，×255 后 **四舍五入（round-half-up）**
```

- **色域裁剪用「逐通道 clamp」，不用 CSS Color 4 的 chroma-reduction 色域映射算法**。
  实测对比 Chromium：逐通道 clamp 125 个调色板令牌中 **120 个逐位精确**（其余 5 个差 1/255）；
  色域映射算法只有 116/125，**更差**，故弃用。
- **必须用 round-half-up**：Python 内置 `round()` 是「四舍六入五取偶」，会把 alpha `0.7`
  算成 178 而不是浏览器的 179（`0.7×255 = 178.5`）。
- `color-mix(in oklab, A p%, B)`：按 CSS Color 4 语义做**预乘 alpha** 的 oklab 插值；
  `color-mix(in srgb, ...)`（仅 `--color-usage-heatmap-*` 用）在 sRGB 空间插值。

**验证**：把快照 CSS **原样**喂给真实 Chromium 引擎（Edge `--headless=new`），逐个令牌读回
计算后的颜色，与本文数值比对：

| 验证 | 结果 |
|---|---|
| 全部 311 个 `--color-*` 令牌 × 明暗两套（与 Chromium computed color 比对） | 622 次潜在比对中 **616 次可比对，616 次完全一致，0 处不符**；余 6 次是 3 个数值型 `--color-*-opacity` 令牌，本身没有颜色可比 |
| 125 个 `oklch()` 字面量调色板令牌（与 Chromium 渲染像素比对） | 120 精确，最差差 **1/255** |

> 交叉验证的覆盖范围：311 个 `--color-*` 令牌（每套主题各一次比对）。**非 `--color-*` 的 240 个变量
> （渐变、阴影、`--tw-prose-*`、`--tw-*` 内部量、字号间距等）没有逐条与浏览器比对**，
> 它们多数本身不是单一颜色。其中 `--tw-prose-*` 是**建模推断**：它们的暗色值来自
> `@media (prefers-color-scheme:dark)` 分支，本文按「系统偏好 = 应用主题」的假设归入 dark，
> 真实浏览器里若系统偏好与应用主题不一致，这两组会分叉（见第 2 节第 4 条）。

**重要提醒：不要用 CSS 里 Tailwind 自己生成的 hex 兜底值。** 它们是旧版 Tailwind 转换器的产物，
已经过时 —— 例如 `red-600` 兜底写 `#E40014`，而浏览器实际渲染 `#E7000B`。
`colors.json` 里的值跟浏览器一致，也就是界面**真正画出来**的颜色。

---

## 4. 映射到 Compose

### 4.1 十六进制 → Compose `Color`

CSS 是 `#RRGGBBAA`（alpha 在**后**），Compose 是 `0xAARRGGBB`（alpha 在**前**），**必须重排**：

```kotlin
// CSS #F8F8F8      -> Color(0xFFF8F8F8)          不透明补 FF
// CSS #0D0D0D1A    -> Color(0x1A0D0D0D)          alpha 0x1A 移到前面
// CSS #F8F8F8B3    -> Color(0xB3F8F8F8)
val Background: Color = Color(0xFFF8F8F8)
```

> 注意 `Color(0xFFF8F8F8)` 是 `Long` 重载（ARGB）；写成 `Color(0xFFF8F8F8.toInt())` 会变成 `Int` 重载，语义不同，别混。

### 4.2 建议的组织方式

按**语义**建字段（不要按调色板档位建字段），明暗各一份实例，用 `CompositionLocal` 下发：

```kotlin
@Immutable
data class AppColors(
    val background: Color,        // --color-background
    val backgroundAlt: Color,     // --color-background-alt
    val panel: Color,             // --color-panel
    val sidebar: Color,           // --color-sidebar
    val card: Color,              // --color-card
    val foreground: Color,        // --color-foreground
    val foregroundSubtle: Color,  // --color-foreground-subtle
    val foregroundSubtlest: Color,// --color-foreground-subtlest
    val border: Color,            // --color-border
    val borderHover: Color,       // --color-border-hover
    val accent: Color,            // --color-accent
    val primary: Color,           // --color-primary
    val primaryForeground: Color, // --color-primary-foreground
    val brand: Color,             // --color-brand
    val hover: Color,             // --color-hover
    val selected: Color,          // --color-selected
    val success: Color,           // --color-success
    val destructive: Color,       // --color-destructive
    val warning: Color,           // --color-warning
    val iconBlue: Color,          // --color-icon-blue
    val iconPurple: Color,        // --color-icon-purple
)

val ZaiLightColors = AppColors(
    background         = Color(0xFFF8F8F8),
    backgroundAlt      = Color(0xB3F8F8F8),   // #F8F8F8B3
    panel              = Color(0xFFFFFFFF),
    sidebar            = Color(0xFFF0F0F0),
    card               = Color(0xFFFFFFFF),
    foreground         = Color(0xFF262626),
    foregroundSubtle   = Color(0x99262626),   // #26262699
    foregroundSubtlest = Color(0x66262626),   // #26262666
    border             = Color(0x1A0D0D0D),   // #0D0D0D1A  ← alpha 前移
    borderHover        = Color(0x260D0D0D),   // #0D0D0D26
    accent             = Color(0xFFEBF4FF),
    primary            = Color(0xFF000000),
    primaryForeground  = Color(0xFFFFFFFF),
    brand              = Color(0xFF000000),
    hover              = Color(0x0D0D0D0D),
    selected           = Color(0x0D0D0D0D),
    success            = Color(0xFF1E8A3E),
    destructive        = Color(0xFFE03131),
    warning            = Color(0xFFE07B00),
    iconBlue           = Color(0xFF0066DD),
    iconPurple         = Color(0xFF9E77ED),
)

val ZaiDarkColors = AppColors(
    background         = Color(0xFF161616),
    backgroundAlt      = Color(0x992B2B2B),   // #2B2B2B99
    panel              = Color(0xFF202020),
    sidebar            = Color(0xFF161616),
    card               = Color(0xFF2B2B2B),
    foreground         = Color(0xFFD4D4D4),
    foregroundSubtle   = Color(0x99D4D4D4),
    foregroundSubtlest = Color(0x4DD4D4D4),
    border             = Color(0x1AFFFFFF),   // #FFFFFF1A
    borderHover        = Color(0x26FFFFFF),
    accent             = Color(0xFF001D3D),
    primary            = Color(0xFFFFFFFF),
    primaryForeground  = Color(0xFF000000),
    brand              = Color(0xFFFFFFFF),
    hover              = Color(0x0DFFFFFF),
    selected           = Color(0x1AFFFFFF),
    success            = Color(0xFF46BF72),
    destructive        = Color(0xFFFF5C5C),
    warning            = Color(0xFFFF8A30),
    iconBlue           = Color(0xFF80BEFF),
    iconPurple         = Color(0xFFA888F2),
)
```

`--color-background-alt`、`--color-foreground-subtle/subtlest`、`--color-border`、
`--color-hover`、`--color-selected`、`--color-surface*` 都**自带 alpha**，是半透明叠加层，
Compose 里直接当半透明 `Color` 用（叠在对应底色上），不要自己再调 alpha。

### 4.3 核心语义令牌速查

**背景 / 表面**

| 令牌 | light | dark |
|---|---|---|
| `--color-background` | `#F8F8F8` | `#161616` |
| `--color-background-alt` | `#F8F8F8B3` | `#2B2B2B99` |
| `--color-panel` | `#FFFFFF` | `#202020` |
| `--color-sidebar` | `#F0F0F0` | `#161616` |
| `--color-header` | `#FFFFFF` | `#202020` |
| `--color-card` | `#FFFFFF` | `#2B2B2B` |
| `--color-popover` | `#FFFFFF` | `#2B2B2B` |
| `--color-input` | `#FFFFFF` | `#2B2B2B` |
| `--color-tab` / `--color-tab-active` | `#F0F0F0` / `#FFFFFF` | `#202020` / `#161616` |
| `--color-tooltip` | `#F0F0F0` | `#2B2B2B` |

**文字**

| 令牌 | light | dark |
|---|---|---|
| `--color-foreground` | `#262626` | `#D4D4D4` |
| `--color-foreground-subtle` | `#26262699` | `#D4D4D499` |
| `--color-foreground-subtlest` | `#26262666` | `#D4D4D44D` |
| `--color-foreground-inverse` | `#FFFFFF` | `#000000` |

**描边 / 交互态**

| 令牌 | light | dark |
|---|---|---|
| `--color-border` | `#0D0D0D1A` | `#FFFFFF1A` |
| `--color-border-hover` | `#0D0D0D26` | `#FFFFFF26` |
| `--color-accent` | `#EBF4FF` | `#001D3D` |
| `--color-primary` | `#000000` | `#FFFFFF` |
| `--color-secondary` | `#E6E6E6` | `#363636` |
| `--color-brand` | `#000000` | `#FFFFFF` |
| `--color-hover` | `#0D0D0D0D` | `#FFFFFF0D` |
| `--color-selected` | `#0D0D0D0D` | `#FFFFFF1A` |

**状态 / 语义色**

| 令牌 | light | dark |
|---|---|---|
| `--color-success` | `#1E8A3E` | `#46BF72` |
| `--color-destructive` | `#E03131` | `#FF5C5C` |
| `--color-warning` | `#E07B00` | `#FF8A30` |
| `--color-icon-blue` | `#0066DD` | `#80BEFF` |
| `--color-icon-purple` | `#9E77ED` | `#A888F2` |
| `--color-git-added` / `-deleted` / `-modified` / `-renamed` | `#1E8A3E` / `#E03131` / `#E07B00` / `#0B7FFF` | `#46BF72` / `#FF5C5C` / `#FF8A30` / `#4099FF` |
| `--color-file-node` / `-foreground` | `#1A70B81A` / `#1A70B8` | `#70AEE029` / `#8FC5EF` |

其余 181 个语义令牌、127 个调色板值、96 个其它颜色变量见 `colors.json`。

---

## 5. UNKNOWN / 无法静态确定

**真正无法静态确定的只有 2 个**（`colors.json` 顶层 `unknowns`）：

| 令牌 | 原因 |
|---|---|
| `--lucide-stroke` | 值是 `rgb(from currentColor r g b / 1)` —— 相对颜色语法，结果取决于元素继承到的 `color`，静态不可解。lucide 图标描边色，复刻时用 Compose 的 `LocalContentColor` / `tint` 代替。 |
| `--lucide-filter` | 值是 `opacity(.6)` / `opacity(var(--lucide-subtlest-opacity))` —— 是 filter 函数不是颜色，且参数是另一个令牌。 |

**另有 42 个「本身就不是单一颜色」的令牌**（`colors.json` 顶层 `not_single_color`，**不是**不确定，
而是按设计就是复合值，别当 `Color` 用）：

| 原因 | 数量 | 例子 |
|---|---:|---|
| `composite-value` 渐变 / 阴影列表 | 11 | `--highspeed-sidebar-model-gradient`（`linear-gradient(90deg,#4830c8 0%,#6041ff 50%,#8955db 100%)`）、`--shadow-sm/md/lg`、`--tw-shadow`、`--tw-ring-shadow` |
| `numeric-opacity` 纯数字透明度 | 14 | `--lucide-subtlest-opacity`（`.4`/`.3`）、`--color-highspeed-share-dialog-noise-opacity`（`1`/`1`）、`--tw-enter-opacity` |
| `numeric-length-or-time` 长度/时长 | 5 | `--highspeed-model-sweep-width`（`96px`）、`--wf-t-ink`（`.32s`）、`--tw-gradient-*-position` |
| `runtime-plumbing` Tailwind 运行时内部变量 | 12 | `--tw-gradient-position`、`--tw-inset-shadow-color`、`--tw-ring-inset`（值多为 `initial`，只由元素上的工具类赋值，无主题级颜色） |

渐变类要复刻成 Compose `Brush`，阴影类复刻成 `Modifier.shadow` / 自绘，透明度类当 alpha 乘子用。

---

## 6. 复现方式

提取链路（脚本为一次性工具，未随交付物保留；结论已落盘为 `colors.json`）：

1. 手写 CSS 词法扫描器解析压缩 CSS，逐条取出 `--*` 声明及其选择器 / at-rule 上下文 / 字节偏移
   （注意 CSS 选择器里的转义引号，如 `.before\:content-\[\'\'\]`，朴素正则会在那里错位）。
   结果与正则直数比对：1,538 条声明，**完全一致**。
2. 按第 2 节的层叠模型算出生效的 light / dark 值；`oklch`/`oklab`/`color-mix`/`var` 链全部静态解析。
3. 用第 3 节的公式换算 sRGB。
4. 用真实 Chromium 引擎（Edge headless + canvas `getImageData`）交叉验证：**616/616 一致**。

重新核对时的坐标：调色板在 `@layer theme{:root,:host{...}}`（约字节 11,600 起）；
暗色主块 `.dark{...}` 约 398,030；`.theme-zai-light{...}` 约 410,165；`.theme-zai-dark{...}` 约 416,836。
