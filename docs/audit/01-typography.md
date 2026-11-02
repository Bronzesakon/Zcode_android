# 01 · 排版（Typography）标准规范

- 上游快照：`archive/pre-3.16.1-auth/Reference_web/3.15.3/`（CSS `assets/index-BD2BL4Cc.css`、主包 `assets/index-BGa3KiRe.js`、`index.html`）
- 对照实现：`android/app/src/main/kotlin/app/zemote/ui/theme/Type.kt`（`ZemoteType` 所在文件）及聊天/Markdown 渲染层
- 取证方式：只读静态解析（UTF-8 字节偏移由脚本逐字符累计得到，与文件字节一一对应）；未在浏览器/设备上做 computed-style 实测

---

## 1. 结论摘要

1. **字号阶梯是应用自有的 7 档 ui 尺度**，不是 Tailwind 的 `--text-xs/sm/base`：基准 `--ui-font-size: 14px`，其余 6 档全部是 `calc(var(--ui-font-size) ± Npx)`；`--ui-font-size` 在 CSS 全表只声明一次（无 media/dark 覆盖），运行时由 JS 写入根节点，取值 **12–20 的整数、默认 14**。
2. **字重只有 4 档**：400 / 500 / 600 / 700（`--font-weight-normal/medium/semibold/bold`），CSS 里没有 100/200/300/800/900 的工具类。
3. **行高分两层**：`ui-*` 字号工具类**没有配对的 line-height**，UI 外壳（菜单、气泡、对话框、表单）继承 preflight 的 `html,:host{line-height:1.5}`；**聊天 Markdown 正文根节点显式写 `leading-[1.75]`**（14px → 24.5px），这是阅读面唯一的大行高。
4. **字距同理分两层**：聊天 Markdown 根节点写 `tracking-wide` = `.025em`（14px 时 0.35px）；标题类常用 `tracking-tight` = `-.025em`；工作流标签专用 `--tracking-wf-label: .09em`；UI 外壳默认 0。
5. **字体族**：`--font-sans` 是纯通用栈（`ui-sans-serif, system-ui, sans-serif` + emoji 族，**不含任何 CJK 字体**）；`--font-mono` 显式列了 `Microsoft YaHei UI / Microsoft YaHei / PingFang SC / Noto Sans CJK SC`。`index.html` 的 bootstrap 内联样式把 `body` 改成 `Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif`，但 **快照里没有任何 Inter 字体文件**，实际会回落到系统 UI 字体。
6. **@font-face 共 20 条，全部是 KaTeX 数学字体**（`KaTeX_AMS/Caligraphic/Fraktur/Main/Math/SansSerif/Script/Size1-4/Typewriter`），没有任何正文/界面字体被打包。
7. **代码排版**：行内代码 `font-mono text-ui-sm`（12px，行高继承 1.75）；围栏代码块由 diffs 引擎渲染，默认 `font-size 13px / line-height 20px`，但应用把 `--diffs-font-size` 覆盖为设置项 `fontSizePx`（默认 **12**，范围 12–20），行高仍是 **20px**；代码块头部语言名是 `text-ui-base` + `font-mono`（14px）。
8. **窄容器/移动端没有任何媒体查询改字号**（对全部 `@media` 与 `@container` 块做了 `font-size/line-height/letter-spacing` 扫描，零命中）。移动端差异全部来自 JS 类切换：`sm:`(≥640) / `md:`(≥768) 变体、以及 `(max-width:767px) and (hover:none) and (pointer:coarse)` 判定下把输入框换成 `text-mobile-input-safe leading-6`（16/24）——且这条只在 **web-remote-control 模式**生效。
9. **最大差距**：Compose 的 `ZemoteType` 只固化了「字号 + 1.5 行高」，缺字距/标题行高/标题字重令牌；聊天层又把 **strong 写成 700（网页是 500）**、**h1–h3 写成 700（网页 600）**、**h6 写成 12sp/500（网页 14/400）**、**表格写成 12sp（网页 14）**、**行内代码不缩字号（网页 12）**，是当前排版偏差最集中的地方。

---

## 2. 证据表

> 文件简写：**CSS** = `archive/pre-3.16.1-auth/Reference_web/3.15.3/assets/index-BD2BL4Cc.css`；**JS** = `archive/pre-3.16.1-auth/Reference_web/3.15.3/assets/index-BGa3KiRe.js`；**HTML** = `archive/pre-3.16.1-auth/Reference_web/3.15.3/index.html`。
> 偏移均为 **UTF-8 字节偏移**（从 0 起）。

### 2.1 令牌定义

| token / 类名 | 值 | 文件:字节偏移 |
|---|---|---|
| `--font-sans` | `ui-sans-serif, system-ui, sans-serif, "Apple Color Emoji", "Segoe UI Emoji", "Segoe UI Symbol", "Noto Color Emoji"` | CSS:3604 |
| `--font-mono` | `ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", "Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC", monospace` | CSS:3731 |
| `--default-font-family` | `var(--font-sans)` | CSS:11016 |
| `--ui-font-size` | `14px`（全表唯一声明，在 `:root`） | CSS:396932 |
| `--text-ui-2xs` | `calc(var(--ui-font-size) - 5px)` → **9px** | CSS:11364 |
| `--text-ui-xs` | `calc(... - 4px)` → **10px** | CSS:11319 |
| `--text-ui-sm` | `calc(... - 2px)` → **12px** | CSS:11274 |
| `--text-ui-caption` | `calc(... - 1px)` → **13px** | CSS:11224 |
| `--text-ui-base` | `var(--ui-font-size)` → **14px** | CSS:11189 |
| `--text-ui-lg` | `calc(... + 2px)` → **16px** | CSS:11144 |
| `--text-ui-xl` | `calc(... + 4px)` → **18px** | CSS:11099 |
| `--text-mobile-input-safe` | `16px`（固定值，不随 ui 基准缩放） | CSS:11436 |
| `--font-weight-normal/medium/semibold/bold` | `400 / 500 / 600 / 700` | CSS:9967 起 |
| `--tracking-tight` | `-.025em` | CSS:10067 |
| `--tracking-wide` | `.025em` | CSS:10114 |
| `--tracking-widest` | `.1em` | CSS:10137 |
| `--tracking-wf-label` | `.09em`（工作流专用） | CSS:11208 |
| `--leading-tight` | `1.25` | CSS:10160 |
| `--leading-snug` | `1.375` | CSS:10181 |
| `--leading-normal` | `1.5` | CSS:10202 |
| `--leading-relaxed` | `1.625` | CSS:10223 |
| preflight 根行高 | `html,:host{... line-height:1.5; font-family:var(--default-font-family,...)}` | CSS:20632 |
| preflight 代码族 | `code,kbd,samp,pre{font-family:var(--default-mono-font-family,...); font-size:1em}` | CSS:21423 |
| `@font-face` | 20 条，全部 `font-family: KaTeX_*`；无 Inter/无正文网络字体 | CSS:347284 起 |

### 2.2 工具类

| 类名 | 计算值 | 文件:字节偏移 |
|---|---|---|
| `.text-ui-2xs` / `.text-ui-xs` / `.text-ui-sm` / `.text-ui-caption` / `.text-ui-base` / `.text-ui-lg` / `.text-ui-xl` | 仅 `font-size: var(--text-ui-*)`，**无 line-height** | CSS:143518 / 143774 / 143694 / 143604 / 143560 / 143654 / 143734 |
| `.text-mobile-input-safe` | `font-size: var(--text-mobile-input-safe)` = 16px | CSS:143454 |
| `.text-ui-base/relaxed` | 14px / `line-height: 1.625` | CSS:142596 |
| `.text-ui-sm/relaxed` | 12px / 1.625 | CSS:142684 |
| `.text-ui-xs/relaxed` | 10px / 1.625 | CSS:142768 |
| `.leading-3` / `.leading-5` / `.leading-6` | `calc(var(--spacing)*N)` = 12 / 20 / 24px（`--spacing:.25rem`） | CSS:143814 / 144079 / 144165 |
| `.leading-[1.75]` | `line-height: 1.75`（无单位） | CSS:144601 |
| `.leading-tight/snug/normal/relaxed` | 1.25 / 1.375 / 1.5 / 1.625 | CSS:145162 / 145083 / 144910 / 144995 |
| `.tracking-tight` / `.tracking-wide` / `.tracking-widest` / `.tracking-wf-label` | `-.025em` / `.025em` / `.1em` / `.09em` | CSS:146177 / 146364 / 146450 / 146266 |
| `.font-normal/medium/semibold/bold` | 400 / 500 / 600 / 700 | CSS:145426 / 145332 / 145520 / 145244 |
| `.prose-sm`（工具输出区） | `font-size:.875rem; line-height:1.71429`（14px/24px） | CSS:43419 |

### 2.3 真实界面用法（JS 侧）

| 场景 | 类名 / 值 | 文件:字节偏移 |
|---|---|---|
| **聊天 Markdown 正文根** | `size-full text-ui-base leading-[1.75] tracking-wide` → **14px / 24.5px / 0.35px** | JS:1706080 |
| 正文 `strong` | `font-medium` → **500**（不是 700） | JS:1707980 |
| 行内代码 | `rounded-md bg-markdown-inline-code/50 mx-0.5 px-1.5 py-0.5 font-mono text-ui-sm` → **12px mono**，行高继承 24.5px | JS:1708072 |
| 标题映射 h1–h6 | `h1 text-ui-xl font-semibold` / `h2 text-ui-lg font-semibold` / `h3 text-ui-base font-semibold` / `h4 text-ui-base font-semibold` / `h5 text-ui-base font-medium` / `h6 text-ui-base font-normal`；外边距 `mt-6 mb-4`（24/16px）；行高继承 1.75 | JS:1695839、JS:1696007 |
| 表格 | `w-max min-w-full border-separate border-spacing-0 text-ui-base` → **14px**，行高 24.5px | JS:1651366 |
| 围栏代码块默认字号 | `codePreviewSettings` 默认 `{fontSizePx:12}`（设置项钳制 12–20） | JS:1189108 |
| 围栏代码块组件默认 | `fontSizePx:s=14`（聊天调用方实际传 12） | JS:732611 |
| 代码块 diffs 默认 | `font-size: var(--diffs-font-size,13px)` / `line-height: var(--diffs-line-height,20px)`；应用覆盖 font-size、**不覆盖 line-height** | JS:523546 / JS:523586 |
| 代码块头部 | `px-3 py-2 text-ui-base text-muted-foreground` + 语言名 `font-mono` → **14px mono** | JS:731755、JS:732340 |
| 用户气泡 | `... px-4 py-3 text-ui-base text-foreground`（**无 leading** → 继承 1.5 = 21px） | JS:2778688 |
| 输入框（桌面/普通移动） | `text-ui-base leading-5` → **14px / 20px** | JS:2067800 |
| 输入框（移动 + 防 iOS 缩放） | `text-mobile-input-safe leading-6` → **16px / 24px** | JS:2067800 |
| 移动判定条件 | `(max-width: 767px) and (hover: none) and (pointer: coarse)` | JS:2066958 |
| 触发条件 | `avoidIosInputFocusZoom = isMobileTextInputViewport && isWebRemoteControl`（仅网页远控模式） | JS:2067813、JS:2320862 |
| UI 字号运行时可调 | `localStorage["zcode-ui-font-size-px"]`，`clamp(12, round(v), 20)`，默认 14，写入 `--ui-font-size` | JS:1189128、JS:1189447 |
| 工件/文档标题阶梯（bridge fallback） | xs 16 / sm 18 / md 20 / lg 24 / xl 30 / 2xl 36 / 3xl 48；行高 1.25 / 1.25 / 1.25 / 1.2 / 1.2 / 1.1 / 1.1 | JS:78462 起 |
| 工具输出 Markdown（非聊天正文） | `prose prose-sm max-w-none text-ui-sm leading-relaxed` + `prose-h1:text-ui-lg prose-h2:text-ui-base prose-h3:text-ui-base` → 12px/1.625 | JS:3856618 |
| 自动化数字输入 | `text-mobile-input-safe leading-6 tracking-[-0.18px] md:text-ui-base md:leading-5` → 移动 16/24/-0.18px，≥768 14/20 | JS:3926406 |
| `index.html` body 字体覆盖 | `font-family: Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif`（无对应字体文件） | HTML:7884 |

### 2.4 窄容器 / 移动端覆盖（负面证据）

- 对 CSS 中全部 `@media` 块做 `font-size|line-height|letter-spacing|--ui-font-size` 扫描：只命中 `sm:`(≥40rem)、`md:`(≥48rem)、`lg:`(≥64rem) 的**放大**变体（`sm:text-ui-lg`、`md:text-ui-base`、`md:leading-5`、`md:leading-relaxed`），**没有任何缩小/移动专用字号**。
- 对全部 30 个 `@container` 块做同样扫描：**零命中**（容器查询只改布局，不改排版）。
- 结论：移动端正文仍是 14px/24.5px，与桌面一致；唯一的移动专属字号是输入框的 16px 防缩放档。

---

## 3. 与 Compose 现状逐项差距

| # | 项 | Web 实测 | Compose 现状 | 判定 |
|---|---|---|---|---|
| 1 | 字号阶梯 | 9/10/12/13/14/16/18 px | `Type.kt:87-96` 9/10/12/13/14/16/18 sp | 一致 |
| 2 | UI 外壳行高 | 继承 1.5（14→21） | `Type.kt:84` 统一 `px*1.5` | 一致 |
| 3 | 聊天正文行高 | 1.75（14→24.5） | `MarkdownText.kt:137` 24.5sp | 一致 |
| 4 | 聊天正文字距 | `.025em`（14→0.35px） | `MarkdownText.kt:138` 0.35sp | 一致 |
| 5 | 字距令牌 | tight `-.025em` / wide `.025em` / widest `.1em` / wf-label `.09em` | **无任何 tracking 令牌**，仅 Markdown 正文硬编码 0.35sp | 缺令牌 |
| 6 | 正文 `strong` | **500**（`font-medium`） | `MarkdownText.kt:504` `FontWeight.Bold`（700） | **偏差**（最显眼） |
| 7 | 标题 h1 | 18px / 600 / 行高 31.5 | `MarkdownText.kt:147` 18sp / **700** / 行高 27（uiXl×1.5） | 字重偏粗、行高偏紧 |
| 8 | 标题 h2 | 16px / 600 / 28 | `:148` 16sp / **700** / 24 | 同上 |
| 9 | 标题 h3 | 14px / 600 / 24.5 | `:149` 14sp / **700** / 21 | 同上 |
| 10 | 标题 h4 | 14px / 600 / 24.5 | `:150` 14sp / SemiBold / 21 | 行高偏紧 |
| 11 | 标题 h5 | 14px / 500 / 24.5 | `:151` 14sp / Medium / 21 | 行高偏紧 |
| 12 | 标题 h6 | **14px / 400** / 24.5 | `:152` **12sp / Medium** / 18 | **偏差**（字号+字重+行高） |
| 13 | 标题外边距 | 上 24 / 下 16 | `:163` 仅 h1/h2 上 4dp，块间距 4dp | 明显偏小（布局项） |
| 14 | 行内代码 | **12px mono** | `MarkdownText.kt:494-502` 只换 mono，**字号仍 14sp** | **偏差** |
| 15 | 围栏代码 | 12px / 20px mono | `CodeBlock.kt:155-159` 12sp / 20sp mono | 一致 |
| 16 | 代码块头部 | 14px mono / 行高 24.5 | `CodeBlock.kt:120` uiBase mono / 行高 21 | 行高偏紧（轻微） |
| 17 | 表格 | **14px** / 24.5 / 0.35 | `MarkdownText.kt:336` **12sp** / 18 / 0 | **偏差**（整体小一号） |
| 18 | 用户气泡 | 14px / 21（1.5） | 未在本次逐行核对（沿用 uiBase） | 待核对 |
| 19 | 输入框 | 14px / 20；移动+远控 16px / 24 | `ChatScreen.kt:684,693` 恒用 mobileInputSafe 16sp / 24sp | 移动端偏大（可接受，但非网页口径） |
| 20 | 移动端字号 | 无缩放，正文仍 14px | 同（无缩放逻辑） | 一致 |
| 21 | 字体族 sans | 通用栈、无 CJK 显式族，实际回落系统 UI 字体 | `Type.kt:77` `FontFamily.Default` | 语义一致 |
| 22 | 字体族 mono | 显式含 CJK 族 | `Type.kt:78` `FontFamily.Monospace` | 大体一致；CJK 是否等宽取决于系统 fallback（未验证） |
| 23 | 打包字体 | 仅 KaTeX，无 Inter | 未打包字体 | 一致 |
| 24 | 用户可调 UI 字号 | 12–20 整数（localStorage） | 无此设置；依赖系统字体缩放 | 功能缺失（非缺陷） |
| 25 | 标题字距 | 页面级标题常用 `tracking-tight`（-0.025em） | 未应用 | 缺令牌 |

> 说明：`Type.kt` 顶部注释把「ui-* 行高按 1.5 折算」当作全局口径，对 UI 外壳成立；**聊天 Markdown 是例外（1.75 + .025em）**，该例外目前只存在于 `MarkdownText.kt` 的局部 copy，没有沉淀成令牌。

---

## 4. 建议的 Compose 令牌

原则：数值全部用 **sp**（跟随系统字号缩放）；字距按「em × 该档字号」换算，写死成对应 sp，避免运行时再算；字体族沿用 `FontFamily.Default` / `FontFamily.Monospace`（网页未打包任何界面字体）。

```kotlin
// --- 字重（与 web 完全一致，4 档） ---
object ZemoteFontWeight {
    val Normal = FontWeight.Normal      // 400
    val Medium = FontWeight.Medium      // 500
    val Semibold = FontWeight.SemiBold  // 600
    val Bold = FontWeight.Bold          // 700
}

// --- 字距（em 令牌 → 按字号换算的 sp 常量） ---
object ZemoteTracking {
    // -.025em
    fun tight(sizeSp: Float) = (-0.025f * sizeSp).sp
    // .025em（聊天正文 14sp → 0.35sp）
    fun wide(sizeSp: Float) = (0.025f * sizeSp).sp
    // .09em（工作流标签）
    fun wfLabel(sizeSp: Float) = (0.09f * sizeSp).sp
    val Tight14 = (-0.35f).sp
    val Wide14 = 0.35f.sp
    val Wide16 = 0.40f.sp
    val Wide18 = 0.45f.sp
    val WfLabel14 = 1.26f.sp
}

// --- UI 外壳阶梯（行高 1.5，字距 0） ---
ui2xs   = 9.sp  / 13.5.sp
uiXs    = 10.sp / 15.sp
uiSm    = 12.sp / 18.sp
uiCaption = 13.sp / 19.5.sp
uiBase  = 14.sp / 21.sp
uiLg    = 16.sp / 24.sp
uiXl    = 18.sp / 27.sp
mobileInputSafe = 16.sp / 24.sp

// --- 聊天 Markdown 阅读面（新增/收敛为令牌） ---
markdownBody   = 14.sp / 24.5.sp / letterSpacing 0.35.sp / Normal
markdownStrong = 500（不要 700）
markdownH1     = 18.sp / 31.5.sp / 0.35.sp / Semibold
markdownH2     = 16.sp / 28.sp   / 0.40.sp / Semibold
markdownH3     = 14.sp / 24.5.sp / 0.35.sp / Semibold
markdownH4     = 14.sp / 24.5.sp / 0.35.sp / Semibold
markdownH5     = 14.sp / 24.5.sp / 0.35.sp / Medium
markdownH6     = 14.sp / 24.5.sp / 0.35.sp / Normal
markdownInlineCode = 12.sp mono / 24.5.sp（字距随正文）
markdownCodeBlock  = 12.sp mono / 20.sp / 字距 0
markdownCodeHeader = 14.sp mono / 24.5.sp
markdownTable      = 14.sp / 24.5.sp / 0.35.sp
markdownQuote      = 同正文 + foregroundSubtle
markdownUserBubble = 14.sp / 21.sp / 字距 0

// --- 其它 ---
codeBlock（diff/终端）= 12.sp mono / 20.sp
badge / kbd / tooltip-tag = 10.sp / 15.sp / Medium
```

落地建议（按性价比排序）：

1. 改 `MarkdownText.kt`：`Strong` 700→500；h1–h3 700→600；h6 改 14sp/Normal；表格 12sp→14sp 且行高 24.5sp/字距 0.35sp；行内代码加 `fontSize = 12.sp`（行高保持 24.5sp）。
2. 把 `leading-[1.75]`、`tracking-wide`、标题阶梯、行内代码/代码块/表格尺寸收敛进 `ZemoteType`（新增 `ZemoteTracking` 与 `markdown*` 一组），避免继续在渲染层散落硬编码。
3. 标题外边距若要 1:1，按 24dp 上 / 16dp 下落地（当前 4dp 明显偏小，属布局差距，可另行排期）。
4. 输入框：Android 上保留 16sp 防缩放是合理偏离；若要严格对齐网页，普通模式改 14sp/20sp，仅在远控/粗指针场景用 `mobileInputSafe`。

---

## 5. 未验证项

1. **未做浏览器实测**：所有 px 值由 class + token 静态推导（rem 按 16px 根字号），未读取 computed style；继承链（尤其 `leading-[1.75]` 对标题/表格的实际生效）未在真实渲染中确认。
2. **Inter 是否由宿主系统提供**：快照未打包 Inter，`index.html` 的 Inter 栈在 macOS/Windows 上实际命中哪个字体（`-apple-system` / Segoe UI / 回退 sans-serif）未验证。
3. **懒加载 chunk 未逐一枚举**：排版统计以主包 `index-BGa3KiRe.js` + CSS 为准，`IntlProvider` 无排版类；其它按需 chunk（图表、编辑器、工作流画布）可能另有局部字号，未纳入。
4. **移动端是否另有非 `FB()` 路径提升输入字号**：仅按 `FB/Ukt/avoidIosInputFocusZoom` 调用链判断；未穷举所有 `<input>`/contenteditable。
5. **`--diffs-line-height` 是否被运行时覆盖**：只找到 2 处默认值（20px），未见写入点；若桌面客户端另有注入，代码块行高可能不同。
6. **CJK 在 `FontFamily.Monospace` 下的实际字形**：Android 系统 fallback 是否命中等宽 CJK 未在设备上验证。
7. **字重 500 的 `strong` 是否被全局 CSS 覆盖**：聊天根节点不在 `.prose` 内，理论上不会被 prose 的 `font-weight:600` 命中，但未跑 computed style 复核。
