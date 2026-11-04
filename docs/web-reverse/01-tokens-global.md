# 01 — 全局设计令牌与基础样式（Web 3.17.1）

> 只读取证。除本文件与 `artifacts/tokens.reference.css` 外，未修改 `app/`、`rust/`、`docs/` 任何既有文件；`Reference_web/` 内代码未执行。
>
> 数据源（唯一）：
> - `Reference_web/3.17.1/assets/index-BgVjt5KE.css` — 485,230 B（485,222 字符，1 行），SHA-256 `a87cd7d83ed2c12be5ad43a92d73f4771f5b6bb9d5b7bdf08d809ce538fb2b56`。
> - `Reference_web/3.17.1/assets/index-Dwhx77cK.js` — 6,986,617 B，SHA-256 `149691e76443c3983b3aa8930ad1563f86892bbe84538cd211b866feeb3127b6`。
> - `Reference_web/3.17.1/index.html` — 18,542 B（内含首屏主题脚本）。
>
> 偏移口径：`css:<n>` / `js:<n>` 为文件字符偏移（Python 文本索引；CSS 仅 12 个非 ASCII 字节，与字节偏移差 ≤4，引用只看数量级）。CSS 是单行压缩文件，没有行号。
> 置信度：**confirmed** = 直接读到声明/字符串；**inferred** = 由多条声明与运行时类名交叉推出；**unresolved** = 引用存在但定义缺失或无法从产物判定。

---

## 0. 结论速览（Compose 先看这一节）

1. **主题有四套，不是两套。** `:root,:host`（默认亮 + 全部色板）、`.dark`、`.theme-zai-light`、`.theme-zai-dark`；后两者是 ZAI 单色皮肤。单行 token 去重后共 **436** 个 `--*` 属性（含 2 个构建器内部开关 `--lightningcss-*`）：默认亮 406、`.dark` 覆盖 205、ZAI 亮 163、ZAI 暗 162。
2. **3.17.1 出厂默认是 `zai-dark`，不是 `dark`。** 证据：JS 里 `theme: sc(zo('zcode-theme') || 'zai-dark')`（`js:80578` 附近）；`index.html` 首屏脚本 `const DEFAULT_THEME = "zai-dark"`、`BROWSER_THEME_COLORS = { dark: "#161616", light: "#f8f8f8" }`；`<meta name="theme-color" content="#161616">`。`setTheme()` 把用户选的 `light/dark` 归一成 `zai-light/zai-dark`（`sc()`，`js:78653`）。原生重写若只做 `:root`/`.dark` 两套，会丢掉实际观感。
3. **默认（ZAI 暗）关键值**：背景 `#161616`、正文 `#D4D4D4`、面板 `#202020`、卡片/弹层 `#2B2B2B`、侧栏 `#161616`、边框 `#FFFFFF1A`、品牌 `#FFFFFF`、破坏 `#FF5C5C`、成功 `#46BF72`、警告 `#FF8A30`。
4. **字号是运行时可调的。** `--ui-font-size` 默认 `14px`，JS `ac()` 夹到 **12–20 整数**（`js:79000` 附近），写入 `html` 内联样式；`--text-ui-*` 全部由它 `calc()` 派生。基础行高 1.5（`html,:host`，`css:25069`）。
5. **间距只有一根轴**：`--spacing: .25rem`（4px），所有 `calc(var(--spacing) * N)` 派生；实际出现 0.5 起步的非 4 倍子步（2/3/5/6/10/14/18/26/30/34px…）。
6. **圆角是 Tailwind 默认刻度，无品牌改写**：xs/sm/md/lg/xl/2xl/3xl = **2/4/6/8/12/16/24px**；裸 `.rounded`=4px；`.rounded-full`=`3.40282e38px`（≈无穷，Compose 用 `CircleShape`）。
7. **阴影只有 3 个主题变量**（`--shadow-sm/md/lg`），`xs/xl/2xl` 是工具类内联值；`--shadow-composer-soft` 在默认两套里被显式置为 `0 0 #0000`（无阴影），只有 ZAI 亮给了真实柔光阴影。
8. **全局焦点环被禁用。** 无层级规则 `:focus,:focus-visible{box-shadow:none!important;outline:none!important}`（`css:418601`）会压掉所有 `focus-visible:ring-2`（unlayered + `!important`）。组件里 93 处 `focus-visible:ring-2` 在实际级联中失效；`--color-ring` 根本没有定义。Compose 应按“意图”实现 2px 品牌色环，并知道 Web 端当前是不可见的。
9. **页面没有 html/body 背景色。** `html,body,#root{background:0 0!important}`（`css:417879`），真正底色画在根 div 的 `bg-background text-foreground` 类上（`js:6538772`、`js:4295017`）。
10. **断点**：40/48/64/80/96rem = 640/768/1024/1280/1536px；另有一套容器查询阈值 216/280/380/420/448/480/560/624/640/864/1280px（`@container conversation|composer|workspace-header|share|…`）。

---

## 1. 主题机制与级联

### 1.1 运行时类名

```js
// js:79169 附近
function oc(e){ return e==='system' ? Qne() : (e==='dark'||e==='zai-dark') ? 'dark' : 'light' }
function sc(e){ return e==='dark' ? 'zai-dark' : e==='light' ? 'zai-light' : e }
function cc(e){
  let t = oc(e), n = e==='system' ? (t==='dark'?'zai-dark':'zai-light') : sc(e);
  document.documentElement.classList.toggle('dark', t==='dark');
  document.documentElement.classList.toggle('theme-zai-light', n==='zai-light');
  document.documentElement.classList.toggle('theme-zai-dark', n==='zai-dark');
  ere(t)  // 同步 meta theme-color / color-scheme
}
```

| 用户选择 | html class | 生效色板 | 置信度 |
|---|---|---|---|
| `system`（系统暗） | `dark theme-zai-dark` | ZAI 暗 | confirmed |
| `system`（系统亮） | `theme-zai-light` | ZAI 亮 | confirmed |
| `dark` → 归一 `zai-dark` | `dark theme-zai-dark` | ZAI 暗 | confirmed |
| `light` → 归一 `zai-light` | `theme-zai-light` | ZAI 亮 | confirmed |
| `zai-dark` | `dark theme-zai-dark` | ZAI 暗 | confirmed |
| `zai-light` | `theme-zai-light` | ZAI 亮 | confirmed |
| 首屏脚本（JS 未执行） | `dark`（或无） | 默认亮 / 默认暗 | confirmed |

### 1.2 源码顺序（决定同特异度覆盖）

| 族 | 选择器 | 首个主块偏移 | 末个主块偏移 | 说明 |
|---|---|---|---|---|
| 色板 + 默认亮 | `:root,:host`（`@layer theme`） | `css:6119` | `css:~30000` | 25 个分块，逐块合并 |
| 默认暗 | `.dark` | `css:418808` | `css:432826` | 另有 `.dark,.theme-zai-dark` 共享块 `css:398529` |
| ZAI 亮 | `.theme-zai-light` | `css:432975` | `css:439026` | 在 `.dark` 之后 |
| ZAI 暗 | `.theme-zai-dark` | `css:440701` | `css:472799` | 在最后，覆盖 `.dark` |
| 运行时/全局 | `:root`（无 layer） | `css:417968` | — | `--ui-font-size`、`--shadow-composer-soft` 等 |

> `@layer theme` 内的声明优先级低于任何无层级规则；因此 `css:417968` 的无层级 `:root` 会覆盖主题层里的同名 token。实测只有 `--shadow-composer-soft` 同时出现在两处，最终默认亮/暗为 `0 0 #0000`。

### 1.3 皮肤覆盖矩阵（语义色）

- 并集 210 个 `--color-*` 语义名（不含色板）；其中 **27 个只在皮肤里定义**，默认亮 `:root` 完全没有：`--color-command-node(-hover)`、`--color-context-breakdown-1..7`、`--color-diff-added/removed-foreground`、`--color-file-node-hover`、`--color-git-none`、`--color-interaction-ask-{foreground,surface}`、`--color-plugin-node(-hover)`、`--color-popover-header`、`--color-session-node(-hover)`、`--color-skill-node(-hover)`、`--color-subagent-node(-hover)`、`--color-tab`、`--color-tab-active`、`--color-tab-border`。
- 影响：**默认亮主题下这些 token 解析失败**（`var()` 无 fallback → 声明 IACVT，元素拿到初始值）。ZAI 亮/暗和默认暗都有定义，所以只影响“裸 `:root`”场景（首屏一帧或未执行 JS 的分享页）。原生重写应以 ZAI 两套为基准，同时给这些 token 补一套亮色值。**inferred**（由缺失声明 + 使用点推出）。

---

## 2. 颜色令牌

### 2.1 色板原语（132 个，`:root,:host`，`@layer theme`）

格式：`原始 CSS 值 → sRGB 十六进制`。原始值全部是 Tailwind v4 的 `oklch()`；hex 由本仓库脚本按 CSS Color 4 公式换算（红 500 得 `#fb2c36`、中性 50 得 `#fafafa`，与 Tailwind v4 官方一致，可作换算校验）。

| 色板 | 档位 | 值 |
|---|---|---|
| `amber` | 50 | `oklch(98.7% .022 95.277)` → `#fffbeb` |
| `amber` | 300 | `oklch(87.9% .169 91.605)` → `#ffd230` |
| `amber` | 400 | `oklch(82.8% .189 84.429)` → `#ffb900` |
| `amber` | 500 | `oklch(76.9% .188 70.08)` → `#fe9a00` |
| `amber` | 600 | `oklch(66.6% .179 58.318)` → `#e17100` |
| `amber` | 700 | `oklch(55.5% .163 48.998)` → `#bb4d00` |
| `amber` | 900 | `oklch(41.4% .112 45.904)` → `#7b3306` |
| `blue` | 50 | `oklch(97% .014 254.604)` → `#eff6ff` |
| `blue` | 100 | `oklch(93.2% .032 255.585)` → `#dbeafe` |
| `blue` | 200 | `oklch(88.2% .059 254.128)` → `#bedbff` |
| `blue` | 300 | `oklch(80.9% .105 251.813)` → `#8ec5ff` |
| `blue` | 400 | `oklch(70.7% .165 254.624)` → `#51a2ff` |
| `blue` | 500 | `oklch(62.3% .214 259.815)` → `#2b7fff` |
| `blue` | 600 | `oklch(54.6% .245 262.881)` → `#155dfc` |
| `blue` | 700 | `oklch(48.8% .243 264.376)` → `#1447e6` |
| `blue` | 900 | `oklch(37.9% .146 265.522)` → `#1c398e` |
| `blue` | 950 | `oklch(28.2% .091 267.935)` → `#162456` |
| `cyan` | 50 | `oklch(98.4% .019 200.873)` → `#ecfeff` |
| `cyan` | 100 | `oklch(95.6% .045 203.388)` → `#cefafe` |
| `cyan` | 300 | `oklch(86.5% .127 207.078)` → `#53eafd` |
| `cyan` | 400 | `oklch(78.9% .154 211.53)` → `#00d3f2` |
| `cyan` | 500 | `oklch(71.5% .143 215.221)` → `#00b8db` |
| `cyan` | 600 | `oklch(60.9% .126 221.723)` → `#0092b8` |
| `cyan` | 700 | `oklch(52% .105 223.128)` → `#007595` |
| `cyan` | 900 | `oklch(39.8% .07 227.392)` → `#104e64` |
| `emerald` | 50 | `oklch(97.9% .021 166.113)` → `#ecfdf5` |
| `emerald` | 300 | `oklch(84.5% .143 164.978)` → `#5ee9b5` |
| `emerald` | 400 | `oklch(76.5% .177 163.223)` → `#00d492` |
| `emerald` | 500 | `oklch(69.6% .17 162.48)` → `#00bc7d` |
| `emerald` | 600 | `oklch(59.6% .145 163.225)` → `#009966` |
| `emerald` | 700 | `oklch(50.8% .118 165.612)` → `#007a55` |
| `emerald` | 900 | `oklch(37.8% .077 168.94)` → `#004f3b` |
| `fuchsia` | 500 | `oklch(66.7% .295 322.15)` → `#e12afb` |
| `fuchsia` | 600 | `oklch(59.1% .293 322.896)` → `#c800de` |
| `gray` | 100 | `oklch(96.7% .003 264.542)` → `#f3f4f6` |
| `gray` | 400 | `oklch(70.7% .022 261.325)` → `#99a1af` |
| `gray` | 700 | `oklch(37.3% .034 259.733)` → `#364153` |
| `gray` | 900 | `oklch(21% .034 264.665)` → `#101828` |
| `green` | 50 | `oklch(98.2% .018 155.826)` → `#f0fdf4` |
| `green` | 100 | `oklch(96.2% .044 156.743)` → `#dcfce7` |
| `green` | 200 | `oklch(92.5% .084 155.995)` → `#b9f8cf` |
| `green` | 300 | `oklch(87.1% .15 154.449)` → `#7bf1a8` |
| `green` | 400 | `oklch(79.2% .209 151.711)` → `#05df72` |
| `green` | 500 | `oklch(72.3% .219 149.579)` → `#00c950` |
| `green` | 600 | `oklch(62.7% .194 149.214)` → `#00a63e` |
| `green` | 700 | `oklch(52.7% .154 150.069)` → `#008236` |
| `green` | 900 | `oklch(39.3% .095 152.535)` → `#0d542b` |
| `green` | 950 | `oklch(26.6% .065 152.934)` → `#032e15` |
| `indigo` | 400 | `oklch(67.3% .182 276.935)` → `#7c86ff` |
| `indigo` | 500 | `oklch(58.5% .233 277.117)` → `#615fff` |
| `neutral` | 50 | `oklch(98.5% 0 0)` → `#fafafa` |
| `neutral` | 100 | `oklch(97% 0 0)` → `#f5f5f5` |
| `neutral` | 200 | `oklch(92.2% 0 0)` → `#e5e5e5` |
| `neutral` | 300 | `oklch(87% 0 0)` → `#d4d4d4` |
| `neutral` | 500 | `oklch(55.6% 0 0)` → `#737373` |
| `neutral` | 700 | `oklch(37.1% 0 0)` → `#404040` |
| `neutral` | 800 | `oklch(26.9% 0 0)` → `#262626` |
| `neutral` | 900 | `oklch(20.5% 0 0)` → `#171717` |
| `neutral` | 950 | `oklch(14.5% 0 0)` → `#0a0a0a` |
| `orange` | 50 | `oklch(98% .016 73.684)` → `#fff7ed` |
| `orange` | 100 | `oklch(95.4% .038 75.164)` → `#ffedd4` |
| `orange` | 300 | `oklch(83.7% .128 66.29)` → `#ffb86a` |
| `orange` | 400 | `oklch(75% .183 55.934)` → `#ff8904` |
| `orange` | 500 | `oklch(70.5% .213 47.604)` → `#ff6900` |
| `orange` | 600 | `oklch(64.6% .222 41.116)` → `#f54900` |
| `orange` | 700 | `oklch(55.3% .195 38.402)` → `#ca3500` |
| `orange` | 900 | `oklch(40.8% .123 38.172)` → `#7e2a0c` |
| `pink` | 50 | `oklch(97.1% .014 343.198)` → `#fdf2f8` |
| `pink` | 300 | `oklch(82.3% .12 346.018)` → `#fda5d5` |
| `pink` | 400 | `oklch(71.8% .202 349.761)` → `#fb64b6` |
| `pink` | 700 | `oklch(52.5% .223 3.958)` → `#c6005c` |
| `pink` | 900 | `oklch(40.8% .153 2.432)` → `#861043` |
| `purple` | 500 | `oklch(62.7% .265 303.9)` → `#ad46ff` |
| `red` | 50 | `oklch(97.1% .013 17.38)` → `#fef2f2` |
| `red` | 100 | `oklch(93.6% .032 17.717)` → `#ffe2e2` |
| `red` | 300 | `oklch(80.8% .114 19.571)` → `#ffa2a2` |
| `red` | 400 | `oklch(70.4% .191 22.216)` → `#ff6467` |
| `red` | 500 | `oklch(63.7% .237 25.331)` → `#fb2c36` |
| `red` | 600 | `oklch(57.7% .245 27.325)` → `#e7000b` |
| `red` | 700 | `oklch(50.5% .213 27.518)` → `#c10007` |
| `red` | 800 | `oklch(44.4% .177 26.899)` → `#9f0712` |
| `red` | 900 | `oklch(39.6% .141 25.723)` → `#82181a` |
| `rose` | 50 | `oklch(96.9% .015 12.422)` → `#fff1f2` |
| `rose` | 300 | `oklch(81% .117 11.638)` → `#ffa1ad` |
| `rose` | 400 | `oklch(71.2% .194 13.428)` → `#ff637e` |
| `rose` | 500 | `oklch(64.5% .246 16.439)` → `#ff2056` |
| `rose` | 600 | `oklch(58.6% .253 17.585)` → `#ec003f` |
| `rose` | 700 | `oklch(51.4% .222 16.935)` → `#c70036` |
| `rose` | 900 | `oklch(41% .159 10.272)` → `#8b0836` |
| `sky` | 50 | `oklch(97.7% .013 236.62)` → `#f0f9ff` |
| `sky` | 100 | `oklch(95.1% .026 236.824)` → `#dff2fe` |
| `sky` | 200 | `oklch(90.1% .058 230.902)` → `#b8e6fe` |
| `sky` | 300 | `oklch(82.8% .111 230.318)` → `#74d4ff` |
| `sky` | 400 | `oklch(74.6% .16 232.661)` → `#00bcff` |
| `sky` | 500 | `oklch(68.5% .169 237.323)` → `#00a6f4` |
| `sky` | 600 | `oklch(58.8% .158 241.966)` → `#0084d1` |
| `sky` | 700 | `oklch(50% .134 242.749)` → `#0069a8` |
| `sky` | 900 | `oklch(39.1% .09 240.876)` → `#024a70` |
| `sky` | 950 | `oklch(29.3% .066 243.157)` → `#052f4a` |
| `slate` | 50 | `oklch(98.4% .003 247.858)` → `#f8fafc` |
| `slate` | 200 | `oklch(92.9% .013 255.508)` → `#e2e8f0` |
| `slate` | 300 | `oklch(86.9% .022 252.894)` → `#cad5e2` |
| `slate` | 500 | `oklch(55.4% .046 257.417)` → `#62748e` |
| `slate` | 600 | `oklch(44.6% .043 257.281)` → `#45556c` |
| `slate` | 700 | `oklch(37.2% .044 257.287)` → `#314158` |
| `slate` | 900 | `oklch(20.8% .042 265.755)` → `#0f172b` |
| `teal` | 300 | `oklch(85.5% .138 181.071)` → `#46ecd5` |
| `teal` | 400 | `oklch(77.7% .152 181.912)` → `#00d5be` |
| `teal` | 600 | `oklch(60% .118 184.704)` → `#009689` |
| `violet` | 50 | `oklch(96.9% .016 293.756)` → `#f5f3ff` |
| `violet` | 200 | `oklch(89.4% .057 293.283)` → `#ddd6ff` |
| `violet` | 300 | `oklch(81.1% .111 293.571)` → `#c4b4ff` |
| `violet` | 400 | `oklch(70.2% .183 293.541)` → `#a684ff` |
| `violet` | 500 | `oklch(60.6% .25 292.717)` → `#8e51ff` |
| `violet` | 600 | `oklch(54.1% .281 293.009)` → `#7f22fe` |
| `violet` | 700 | `oklch(49.1% .27 292.581)` → `#7008e7` |
| `violet` | 900 | `oklch(38% .189 293.745)` → `#4d179a` |
| `violet` | 950 | `oklch(28.3% .141 291.089)` → `#2f0d68` |
| `yellow` | 100 | `oklch(97.3% .071 103.193)` → `#fef9c2` |
| `yellow` | 300 | `oklch(90.5% .182 98.111)` → `#ffdf20` |
| `yellow` | 400 | `oklch(85.2% .199 91.936)` → `#fdc700` |
| `yellow` | 500 | `oklch(79.5% .184 86.047)` → `#f0b100` |
| `yellow` | 600 | `oklch(68.1% .162 75.834)` → `#d08700` |
| `yellow` | 700 | `oklch(55.4% .135 66.442)` → `#a65f00` |
| `yellow` | 800 | `oklch(47.6% .114 61.907)` → `#894b00` |
| `yellow` | 900 | `oklch(42.1% .095 57.708)` → `#733e0a` |
| `zinc` | 100 | `oklch(96.7% .001 286.375)` → `#f4f4f5` |
| `zinc` | 300 | `oklch(87.1% .006 286.286)` → `#d4d4d8` |
| `zinc` | 400 | `oklch(70.5% .015 286.067)` → `#9f9fa9` |
| `zinc` | 500 | `oklch(55.2% .016 285.938)` → `#71717b` |
| `zinc` | 800 | `oklch(27.4% .006 286.033)` → `#27272a` |
| `zinc` | 950 | `oklch(14.1% .005 285.823)` → `#09090b` |

> 注：`#RRGGBBAA` 是 CSS 8 位写法；Compose 需转 `0xAARRGGBB`。oklch 超出 sRGB 的色相在 P3 屏上浏览器会显示更饱和的原色，hex 只是 sRGB 近似。**confirmed**（原始值）/ **inferred**（hex 换算）。

### 2.2 语义颜色（完整 210 个，四套主题）

单元格 = 解析后的 sRGB hex；`*` = 该族**直接声明**了此 token，未标 `*` = 继承前序主题（ZAI 亮继承 `:root`，ZAI 暗继承 `:root`+`.dark`）。`—` = 该族解析不到（仅默认亮出现，见 §1.3）。完整原始表达式（`var()`/`color-mix()`）见 `artifacts/tokens.reference.css`。

| Token | 默认亮 `:root` | 默认暗 `.dark` | ZAI 亮 | ZAI 暗 | 直接声明 |
|---|---|---|---|---|---|
| `--color-accent` | `#f0f9ff`* | `#052f4a80`* | `#ebf4ff`* | `#001d3d`* | LDZL |
| `--color-background` | `#fafafa`* | `#171717`* | `#f8f8f8`* | `#161616`* | LDZL |
| `--color-background-alt` | `#f5f5f599`* | `#26262699`* | `#f8f8f8b3`* | `#2b2b2b99`* | LDZL |
| `--color-background-win-alt` | `#e5e5e5`* | `#262626`* | `#ececee`* | `#2b2b2b`* | LDZL |
| `--color-black` | `#000000`* | `#000000` | `#000000` | `#000000` | L |
| `--color-border` | `#0a0a0a1a`* | `#fafafa1a`* | `#0d0d0d1a`* | `#ffffff1a`* | LDZL |
| `--color-border-hover` | `#0a0a0a33`* | `#fafafa4d`* | `#0d0d0d26`* | `#ffffff26`* | LDZL |
| `--color-brand` | `#00bcff`* | `#00a6f4`* | `#000000`* | `#ffffff`* | LDZL |
| `--color-card` | `#ffffff`* | `#262626`* | `#ffffff`* | `#2b2b2b`* | LDZL |
| `--color-card-border` | `#0a0a0a1a`* | `#fafafa1a`* | `#0d0d0d1a`* | `#ffffff1a`* | LDZL |
| `--color-card-selected` | `#e5e5e5`* | `#404040`* | `#ffffff`* | `#2b2b2b`* | LDZL |
| `--color-command-node` | `—` | `#cad5e229`* | `#5662701a`* | `#9aa6b424`* | DZL |
| `--color-command-node-foreground` | `#45556c`* | `#cad5e2`* | `#566270`* | `#b5c0cc`* | LDZL |
| `--color-command-node-hover` | `—` | `#cad5e238`* | `#56627029`* | `#9aa6b433`* | DZL |
| `--color-context-breakdown-1` | `—` | `#00bcff`* | `#0b7fff`* | `#4099ff`* | DZL |
| `--color-context-breakdown-2` | `—` | `#74d4ff`* | `#338fff`* | `#66adff`* | DZL |
| `--color-context-breakdown-3` | `—` | `#b8e6fe`* | `#5ca7ff`* | `#80beff`* | DZL |
| `--color-context-breakdown-4` | `—` | `#c4eaff`* | `#85bbff`* | `#9dceff`* | DZL |
| `--color-context-breakdown-5` | `—` | `#ccedff`* | `#acd0ff`* | `#b9ddff`* | DZL |
| `--color-context-breakdown-6` | `—` | `#d5f0ff`* | `#c8ddff`* | `#d0e8ff`* | DZL |
| `--color-context-breakdown-7` | `—` | `#ddf3ff`* | `#e0ecff`* | `#e4f1ff`* | DZL |
| `--color-destructive` | `#e7000b`* | `#fb2c36`* | `#e03131`* | `#ff5c5c`* | LDZL |
| `--color-destructive-foreground` | `#ffffff`* | `#ffffff`* | `#ffffff`* | `#ffffff`* | LDZL |
| `--color-destructive-surface` | `#e7000b1a`* | `#fb2c3629`* | `#e031311a`* | `#ff5c5c29`* | LDZL |
| `--color-destructive-surface-foreground` | `#9f0712`* | `#ffa2a2`* | `#b42020`* | `#ff8585`* | LDZL |
| `--color-diff-added` | `#00a63e`* | `#00c950`* | `#1e8a3e`* | `#46bf72`* | LDZL |
| `--color-diff-added-foreground` | `—` | `#ffffff`* | `#ffffff`* | `#000000`* | DZL |
| `--color-diff-removed` | `#e7000b`* | `#fb2c36`* | `#e03131`* | `#ff5c5c`* | LDZL |
| `--color-diff-removed-foreground` | `—` | `#ffffff`* | `#ffffff`* | `#000000`* | DZL |
| `--color-feedback-privacy-hint` | `#d08700`* | `#ffdf20`* | `#d08700`* | `#ffdf20`* | LDZL |
| `--color-file-node` | `#0084d124`* | `#00bcff2e`* | `#1a70b81a`* | `#70aee029`* | LDZL |
| `--color-file-node-foreground` | `#0084d1`* | `#74d4ff`* | `#1a70b8`* | `#8fc5ef`* | LDZL |
| `--color-file-node-hover` | `—` | `#00bcff3d`* | `#1a70b829`* | `#70aee038`* | DZL |
| `--color-find-highlight` | `#fde68a`* | `#713f12`* | `#fff4eb`* | `#542500`* | LDZL |
| `--color-find-highlight-active` | `#facc15`* | `#a16207`* | `#ffb26b`* | `#ff8a30`* | LDZL |
| `--color-foreground` | `#404040`* | `#e5e5e5`* | `#262626`* | `#d4d4d4`* | LDZL |
| `--color-foreground-inverse` | `#ffffff`* | `#ffffff`* | `#ffffff`* | `#000000`* | LDZL |
| `--color-foreground-subtle` | `#40404099`* | `#e5e5e599`* | `#26262699`* | `#d4d4d499`* | LDZL |
| `--color-foreground-subtlest` | `#40404066`* | `#e5e5e54d`* | `#26262666`* | `#d4d4d44d`* | LDZL |
| `--color-git-added` | `#009689`* | `#00d5be`* | `#1e8a3e`* | `#46bf72`* | LDZL |
| `--color-git-deleted` | `#e7000b`* | `#ff6467`* | `#e03131`* | `#ff5c5c`* | LDZL |
| `--color-git-descendant` | `#e17100`* | `#ffb900`* | `#e07b00`* | `#ff8a30`* | LDZL |
| `--color-git-ignored` | `#40404066`* | `#e5e5e54d`* | `#26262666`* | `#d4d4d44d`* | LDZL |
| `--color-git-modified` | `#e17100`* | `#ffb900`* | `#e07b00`* | `#ff8a30`* | LDZL |
| `--color-git-none` | `—` | `#e5e5e5`* | `#262626`* | `#d4d4d4`* | DZL |
| `--color-git-renamed` | `#0084d1`* | `#00bcff`* | `#0b7fff`* | `#4099ff`* | LDZL |
| `--color-git-untracked` | `#009689`* | `#00d5be`* | `#1e8a3e`* | `#46bf72`* | LDZL |
| `--color-header` | `#f5f5f5`* | `#171717`* | `#ffffff`* | `#202020`* | LDZL |
| `--color-highspeed-accent` | `#9e77ed`* | `#b392ff`* | `#9e77ed` | `#b392ff` | LD |
| `--color-highspeed-composer-border-hover` | `#6041ff`* | `#6041ff` | `#6041ff` | `#6041ff` | L |
| `--color-highspeed-composer-border-idle` | `#6041ff`* | `#6041ff` | `#6041ff` | `#6041ff` | L |
| `--color-highspeed-composer-border-pre-animation` | `#0a0a0a1a`* | `#00000000`* | `#0d0d0d1a` | `#00000000` | LD |
| `--color-highspeed-composer-surface` | `#ffffff`* | `#151515`* | `#ffffff` | `#151515` | LD |
| `--color-highspeed-glow-01` | `#6041ff03`* | `#a888f203`* | `#6041ff03` | `#a888f203` | LD |
| `--color-highspeed-glow-04` | `#6041ff0a`* | `#a888f20a`* | `#6041ff0a` | `#a888f20a` | LD |
| `--color-highspeed-glow-15` | `#6041ff26`* | `#a888f226`* | `#6041ff26` | `#a888f226` | LD |
| `--color-highspeed-glow-26` | `#6041ff42`* | `#a888f242`* | `#6041ff42` | `#a888f242` | LD |
| `--color-highspeed-model-sweep-highlight` | `#b392ff`* | `#f0e9ff`* | `#b392ff` | `#f0e9ff` | LD |
| `--color-highspeed-particle` | `#7453b0c2`* | `#b392ffc2`* | `#7453b0c2` | `#b392ffc2` | LD |
| `--color-highspeed-particle-highlight` | `#7453b0`* | `#b392ff`* | `#7453b0` | `#b392ff` | LD |
| `--color-highspeed-particle-highlight-glow` | `#7453b061`* | `#b392ffad`* | `#7453b061` | `#b392ffad` | LD |
| `--color-highspeed-share-card-view-border` | `#0000002e`* | `#ffffff70`* | `#0000002e` | `#ffffff70` | LD |
| `--color-highspeed-share-card-view-end` | `#9999990f`* | `#9999990f` | `#9999990f` | `#9999990f` | L |
| `--color-highspeed-share-card-view-start` | `#ffffff0f`* | `#ffffff0f` | `#ffffff0f` | `#ffffff0f` | L |
| `--color-highspeed-share-control-hover` | `#0d0d0d0a`* | `#ffffff1a`* | `#0d0d0d0a` | `#ffffff1a` | LD |
| `--color-highspeed-share-dialog-bars-baseline` | `#0d0d0d0d`* | `#ffffff2e`* | `#0d0d0d0d` | `#ffffff2e` | LD |
| `--color-highspeed-share-dialog-bars-end` | `#80d68d`* | `#82d990`* | `#80d68d` | `#82d990` | LD |
| `--color-highspeed-share-dialog-bars-mid` | `#f0c76e`* | `#f1c86f`* | `#f0c76e` | `#f1c86f` | LD |
| `--color-highspeed-share-dialog-bars-start` | `#ed6c70`* | `#ef6f72`* | `#ed6c70` | `#ef6f72` | LD |
| `--color-highspeed-share-dialog-benefit-text` | `#5c5c5c`* | `#adadad`* | `#5c5c5c` | `#adadad` | LD |
| `--color-highspeed-share-dialog-border` | `#0000001a`* | `#ffffff1f`* | `#0000001a` | `#ffffff1f` | LD |
| `--color-highspeed-share-dialog-control-hover` | `#00000012`* | `#ffffff1a`* | `#00000012` | `#ffffff1a` | LD |
| `--color-highspeed-share-dialog-download-foreground` | `#ffffff`* | `#ffffff` | `#ffffff` | `#ffffff` | L |
| `--color-highspeed-share-dialog-download-hover-surface` | `#1f1f1f`* | `#1f1f1f` | `#1f1f1f` | `#1f1f1f` | L |
| `--color-highspeed-share-dialog-download-surface` | `#000000`* | `#000000` | `#000000` | `#000000` | L |
| `--color-highspeed-share-dialog-foreground` | `#161518`* | `#f7f5fa`* | `#161518` | `#f7f5fa` | LD |
| `--color-highspeed-share-dialog-input-border` | `#0000001a`* | `#ffffff1a`* | `#0000001a` | `#ffffff1a` | LD |
| `--color-highspeed-share-dialog-logo-border` | `#ffffff1f`* | `#ffffff1f` | `#ffffff1f` | `#ffffff1f` | L |
| `--color-highspeed-share-dialog-logo-surface` | `#2d2d2d`* | `#2d2d2d` | `#2d2d2d` | `#2d2d2d` | L |
| `--color-highspeed-share-dialog-marker-glow` | `#6bd77d99`* | `#73dd8499`* | `#6bd77d99` | `#73dd8499` | LD |
| `--color-highspeed-share-dialog-muted` | `#00000094`* | `#e7e4eda3`* | `#00000094` | `#e7e4eda3` | LD |
| `--color-highspeed-share-dialog-noise-dark-opacity` | `ERR`* | `ERR`* | `ERR` | `ERR` | LD |
| `--color-highspeed-share-dialog-noise-light-opacity` | `ERR`* | `ERR`* | `ERR` | `ERR` | LD |
| `--color-highspeed-share-dialog-noise-opacity` | `ERR`* | `ERR`* | `ERR` | `ERR` | LD |
| `--color-highspeed-share-dialog-pill-text` | `#5c5c5c`* | `#adadad`* | `#5c5c5c` | `#adadad` | LD |
| `--color-highspeed-share-dialog-stats-border` | `#0000001a`* | `#ffffff1a`* | `#0000001a` | `#ffffff1a` | LD |
| `--color-highspeed-share-dialog-surface` | `#ffffff`* | `#101010`* | `#ffffff` | `#101010` | LD |
| `--color-highspeed-share-foreground` | `#161518`* | `#e7e4ed`* | `#161518` | `#e7e4ed` | LD |
| `--color-highspeed-share-foreground-subtle` | `#000000cc`* | `#c8c2d2`* | `#000000cc` | `#c8c2d2` | LD |
| `--color-hover` | `#e5e5e5`* | `#ffffff1a`* | `#0d0d0d0d`* | `#ffffff0d`* | LDZL |
| `--color-icon-blue` | `#00a6f4`* | `#0084d1` | `#0066dd` | `#80beff` | L |
| `--color-icon-purple` | `#e12afb`* | `#c800de` | `#9e77ed` | `#a888f2` | L |
| `--color-idle-task` | `#7f22fe`* | `#a684ff`* | `#9e77ed`* | `#7b5ce5`* | LDZL |
| `--color-idle-task-surface` | `#f5f3ff`* | `#2f0d6880`* | `#f5f3ff`* | `#160d38`* | LDZL |
| `--color-input` | `#ffffff`* | `#262626`* | `#ffffff`* | `#2b2b2b`* | LDZL |
| `--color-input-border` | `#0a0a0a1a`* | `#fafafa1a`* | `#0d0d0d1a`* | `#ffffff1a`* | LDZL |
| `--color-input-border-focused` | `#00bcff`* | `#00a6f4`* | `#0d0d0d26`* | `#ffffff26`* | LDZL |
| `--color-input-border-hover` | `#0a0a0a33`* | `#fafafa4d`* | `#0d0d0d26`* | `#ffffff26`* | LDZL |
| `--color-input-focused` | `#fafafa`* | `#0a0a0a`* | `#ffffff`* | `#2b2b2b`* | LDZL |
| `--color-interaction-ask-fill` | `#00c95033`* | `#05df723d`* | `#46bf7233`* | `#46bf723d`* | LDZL |
| `--color-interaction-ask-foreground` | `—` | `#74d4ff`* | `#0066dd`* | `#80beff`* | DZL |
| `--color-interaction-ask-surface` | `—` | `#052f4a80`* | `#ebf4ff`* | `#001d3d`* | DZL |
| `--color-interaction-confirmation-foreground` | `#008236`* | `#7bf1a8`* | `#166b32`* | `#87d9a4`* | LDZL |
| `--color-interaction-confirmation-surface` | `#f0fdf4`* | `#032e1573`* | `#eaf7ee`* | `#46bf7229`* | LDZL |
| `--color-markdown-inline-code` | `#e5e5e5`* | `#404040` | `#e6e6e6` | `#363636` | L |
| `--color-menu` | `#ffffff`* | `#0a0a0a`* | `#ffffff`* | `#2b2b2b`* | LDZL |
| `--color-menu-hover` | `#f5f5f5`* | `#171717`* | `#f0f0f0`* | `#363636`* | LDZL |
| `--color-panel` | `#f5f5f5`* | `#171717`* | `#ffffff`* | `#202020`* | LDZL |
| `--color-plugin-node` | `—` | `#ffb9002e`* | `#b87a1a1a`* | `#e0ae7029`* | DZL |
| `--color-plugin-node-foreground` | `#e17100`* | `#ffd230`* | `#b87a1a`* | `#efc58f`* | LDZL |
| `--color-plugin-node-hover` | `—` | `#ffb9003d`* | `#b87a1a29`* | `#e0ae7038`* | DZL |
| `--color-plugin-paid-plan-badge` | `#ffe3c8`* | `#5a341b`* | `#ffe3c8`* | `#5a341b`* | LDZL |
| `--color-plugin-paid-plan-badge-foreground` | `#4b280f`* | `#ffd8b8`* | `#4b280f`* | `#ffd8b8`* | LDZL |
| `--color-popover` | `#ffffff`* | `#262626`* | `#ffffff`* | `#2b2b2b`* | LDZL |
| `--color-popover-border` | `#0a0a0a1a`* | `#fafafa1a`* | `#0d0d0d1a`* | `#ffffff1a`* | LDZL |
| `--color-popover-foreground` | `#404040`* | `#e5e5e5`* | `#262626`* | `#d4d4d4`* | LDZL |
| `--color-popover-header` | `—` | `#404040`* | `#f8f8f8`* | `#202020`* | DZL |
| `--color-primary` | `#0a0a0a`* | `#fafafa`* | `#000000`* | `#ffffff`* | LDZL |
| `--color-primary-foreground` | `#fafafa`* | `#0a0a0a`* | `#ffffff`* | `#000000`* | LDZL |
| `--color-progress-surface` | `#eff6ff`* | `#162456`* | `#eff6ff`* | `#4682f029`* | LDZL |
| `--color-progress-surface-foreground` | `#1447e6`* | `#8ec5ff`* | `#1447e6`* | `#8ec5ff`* | LDZL |
| `--color-remote-continuity-panel` | `#202124`* | `#202124` | `#202124` | `#202124` | L |
| `--color-remote-continuity-panel-foreground` | `#f8f8f8`* | `#f8f8f8` | `#f8f8f8` | `#f8f8f8` | L |
| `--color-remote-continuity-panel-subtle` | `#a7a7ad`* | `#a7a7ad` | `#a7a7ad` | `#a7a7ad` | L |
| `--color-remote-continuity-panel-surface` | `#2c2d30`* | `#2c2d30` | `#2c2d30` | `#2c2d30` | L |
| `--color-remote-docker-border` | `#ddd6ff`* | `#7008e7`* | `#d4caff`* | `#9f84ff6b`* | LDZL |
| `--color-remote-docker-foreground` | `#7008e7`* | `#c4b4ff`* | `#6047b4`* | `#bfaaff`* | LDZL |
| `--color-remote-docker-surface` | `#f5f3ff`* | `#8e51ff2e`* | `#f2efff`* | `#9f84ff29`* | LDZL |
| `--color-remote-foreground-muted` | `#595959`* | `#b3b3b3`* | `#595959`* | `#b3b3b3`* | LDZL |
| `--color-remote-ssh-border` | `#b8e6fe`* | `#0069a8`* | `#bad9ff`* | `#4099ff6b`* | LDZL |
| `--color-remote-ssh-foreground` | `#0069a8`* | `#74d4ff`* | `#0867ce`* | `#8fc0ff`* | LDZL |
| `--color-remote-ssh-surface` | `#f0f9ff`* | `#00a6f42e`* | `#ebf4ff`* | `#4099ff29`* | LDZL |
| `--color-remote-wsl-border` | `#b9f8cf`* | `#008236`* | `#b7e0c2`* | `#46bf726b`* | LDZL |
| `--color-remote-wsl-foreground` | `#008236`* | `#7bf1a8`* | `#166b32`* | `#87d9a4`* | LDZL |
| `--color-remote-wsl-surface` | `#f0fdf4`* | `#00c9502e`* | `#eaf7ee`* | `#46bf7229`* | LDZL |
| `--color-resize-handle` | `#00a6f4`* | `#00bcff`* | `#00a6f4` | `#00bcff` | LD |
| `--color-secondary` | `#d4d4d4`* | `#404040`* | `#e6e6e6`* | `#363636`* | LDZL |
| `--color-selected` | `#0a0a0a1a`* | `#ffffff1a`* | `#0d0d0d0d`* | `#ffffff1a`* | LDZL |
| `--color-session-node` | `—` | `#00d5be2e`* | `#14807a1a`* | `#68c4bc29`* | DZL |
| `--color-session-node-foreground` | `#009689`* | `#46ecd5`* | `#14807a`* | `#93d8d2`* | LDZL |
| `--color-session-node-hover` | `—` | `#00d5be3d`* | `#14807a29`* | `#68c4bc38`* | DZL |
| `--color-sidebar` | `#f5f5f5`* | `#0a0a0a`* | `#f0f0f0`* | `#161616`* | LDZL |
| `--color-skill-node` | `—` | `#a684ff2e`* | `#7453b01a`* | `#a689da29`* | DZL |
| `--color-skill-node-foreground` | `#7f22fe`* | `#c4b4ff`* | `#7453b0`* | `#bda5e6`* | LDZL |
| `--color-skill-node-hover` | `—` | `#a684ff3d`* | `#7453b029`* | `#a689da38`* | DZL |
| `--color-subagent-node` | `—` | `#b7bd7500`* | `#6f7a2f1a`* | `#b7bd7529`* | DZL |
| `--color-subagent-node-foreground` | `#6f7a2f`* | `#b7bd75`* | `#6f7a2f`* | `#c8cd90`* | LDZL |
| `--color-subagent-node-hover` | `—` | `#b7bd7501`* | `#6f7a2f29`* | `#b7bd7538`* | DZL |
| `--color-success` | `#00a63e`* | `#00c950`* | `#1e8a3e`* | `#46bf72`* | LDZL |
| `--color-success-foreground` | `#ffffff`* | `#ffffff`* | `#ffffff`* | `#000000`* | LDZL |
| `--color-success-surface` | `#f0fdf4`* | `#032e1573`* | `#eaf7ee`* | `#46bf7229`* | LDZL |
| `--color-success-surface-foreground` | `#008236`* | `#7bf1a8`* | `#166b32`* | `#87d9a4`* | LDZL |
| `--color-surface` | `#0a0a0a08`* | `#ffffff0d`* | `#0d0d0d08`* | `#ffffff0d`* | LDZL |
| `--color-surface-hover` | `#0a0a0a0d`* | `#ffffff1a`* | `#0d0d0d0d`* | `#ffffff1a`* | LDZL |
| `--color-tab` | `—` | `#262626`* | `#f0f0f0`* | `#202020`* | DZL |
| `--color-tab-active` | `—` | `#0a0a0a`* | `#ffffff`* | `#161616`* | DZL |
| `--color-tab-border` | `—` | `#fafafa1a`* | `#0d0d0d1a`* | `#ffffff1a`* | DZL |
| `--color-tag` | `#e5e5e5`* | `#404040`* | `#e6e6e6`* | `#363636`* | LDZL |
| `--color-terminal-bg` | `#fafafa`* | `#0a0a0a`* | `#f8f8f8`* | `#161616`* | LDZL |
| `--color-terminal-black` | `#404040`* | `#262626`* | `#5c5c5c`* | `#363636`* | LDZL |
| `--color-terminal-blue` | `#00a6f4`* | `#0084d1`* | `#0b7fff`* | `#4099ff`* | LDZL |
| `--color-terminal-bright-black` | `#737373`* | `#737373`* | `#888888`* | `#747474`* | LDZL |
| `--color-terminal-bright-blue` | `#00a6f4`* | `#0084d1`* | `#0066dd`* | `#80beff`* | LDZL |
| `--color-terminal-bright-cyan` | `#00b8db`* | `#0092b8`* | `#0aa7a7`* | `#8ee5e5`* | LDZL |
| `--color-terminal-bright-green` | `#00c950`* | `#00a63e`* | `#1e8a3e`* | `#87d9a4`* | LDZL |
| `--color-terminal-bright-magenta` | `#e12afb`* | `#c800de`* | `#9e77ed`* | `#a888f2`* | LDZL |
| `--color-terminal-bright-red` | `#fb2c36`* | `#e7000b`* | `#e03131`* | `#ff9999`* | LDZL |
| `--color-terminal-bright-white` | `#0a0a0a`* | `#fafafa`* | `#0d0d0d`* | `#f8f8f8`* | LDZL |
| `--color-terminal-bright-yellow` | `#f0b100`* | `#d08700`* | `#e07b00`* | `#ffb26b`* | LDZL |
| `--color-terminal-cursor` | `#0a0a0a`* | `#fafafa`* | `#0d0d0d`* | `#f8f8f8`* | LDZL |
| `--color-terminal-cursor-accent` | `#fafafa`* | `#0a0a0a`* | `#f8f8f8`* | `#161616`* | LDZL |
| `--color-terminal-cyan` | `#00b8db`* | `#0092b8`* | `#0aa7a7`* | `#42c8c8`* | LDZL |
| `--color-terminal-fg` | `#0a0a0a`* | `#fafafa`* | `#262626`* | `#d4d4d4`* | LDZL |
| `--color-terminal-green` | `#00c950`* | `#00a63e`* | `#1e8a3e`* | `#46bf72`* | LDZL |
| `--color-terminal-magenta` | `#e12afb`* | `#c800de`* | `#9e77ed`* | `#7b5ce5`* | LDZL |
| `--color-terminal-red` | `#fb2c36`* | `#e7000b`* | `#e03131`* | `#ff5c5c`* | LDZL |
| `--color-terminal-selection` | `#00a6f452`* | `#00a6f442`* | `#0b7fff38`* | `#4099ff47`* | LDZL |
| `--color-terminal-selection-inactive` | `#00a6f438`* | `#fafafa29`* | `#0d0d0d1a`* | `#ffffff1a`* | LDZL |
| `--color-terminal-white` | `#737373`* | `#e5e5e5`* | `#adadad`* | `#adadad`* | LDZL |
| `--color-terminal-yellow` | `#f0b100`* | `#d08700`* | `#e07b00`* | `#ff8a30`* | LDZL |
| `--color-toast` | `#ffffff`* | `#171717`* | `#ffffff`* | `#2b2b2b`* | LDZL |
| `--color-tooltip` | `#f5f5f5`* | `#171717`* | `#f0f0f0`* | `#2b2b2b`* | LDZL |
| `--color-tooltip-foreground` | `#0a0a0a`* | `#fafafa`* | `#0d0d0d`* | `#f8f8f8`* | LDZL |
| `--color-tooltip-tag` | `#e5e5e5`* | `#262626`* | `#e6e6e6`* | `#363636`* | LDZL |
| `--color-tooltip-tag-foreground` | `#0a0a0a99`* | `#fafafa99`* | `#5c5c5c`* | `#adadad`* | LDZL |
| `--color-trajectory-assistant` | `#0f766e`* | `#2dd4bf`* | `#0f766e`* | `#2dd4bf`* | LDZL |
| `--color-trajectory-reasoning` | `#7c3aed`* | `#a78bfa`* | `#7c3aed`* | `#a78bfa`* | LDZL |
| `--color-trajectory-tool-call` | `#d97706`* | `#f59e0b`* | `#d97706`* | `#f59e0b`* | LDZL |
| `--color-trajectory-tool-result` | `#0284c7`* | `#38bdf8`* | `#0284c7`* | `#38bdf8`* | LDZL |
| `--color-trajectory-user` | `#2563eb`* | `#60a5fa`* | `#2563eb`* | `#60a5fa`* | LDZL |
| `--color-usage-chart-1` | `#0084d1`* | `#00a6f4`* | `#0b7fff`* | `#4099ff`* | LDZL |
| `--color-usage-chart-2` | `#009689`* | `#00d5be`* | `#1e8a3e`* | `#46bf72`* | LDZL |
| `--color-usage-chart-3` | `#7f22fe`* | `#a684ff`* | `#9e77ed`* | `#7b5ce5`* | LDZL |
| `--color-usage-chart-4` | `#ff2056`* | `#ff637e`* | `#e03131`* | `#ff5c5c`* | LDZL |
| `--color-usage-chart-5` | `#615fff`* | `#7c86ff`* | `#e07b00`* | `#ff8a30`* | LDZL |
| `--color-usage-chart-6` | `#0092b8`* | `#00d3f2`* | `#0aa7a7`* | `#42c8c8`* | LDZL |
| `--color-usage-heatmap-0` | `#0a0a0a08`* | `#ffffff0d`* | `#0d0d0d08`* | `#ffffff0d`* | LDZL |
| `--color-usage-heatmap-1` | `#1291d434`* | `#4ec6ff47`* | `#1470dc34`* | `#5fa8ff47`* | LDZL |
| `--color-usage-heatmap-2` | `#099de661`* | `#33c1ff72`* | `#1078f061`* | `#4fa0ff73`* | LDZL |
| `--color-usage-heatmap-3` | `#0381cc97`* | `#21beffa3`* | `#0d7cf997`* | `#479cffa3`* | LDZL |
| `--color-usage-heatmap-4` | `#0168a7d2`* | `#76d5ffca`* | `#0165dbd3`* | `#82bfffca`* | LDZL |
| `--color-user-message-bubble` | `#0a0a0a0d`* | `#ffffff14`* | `#0d0d0d0d`* | `#ffffff14`* | LDZL |
| `--color-warning` | `#d08700`* | `#f0b100`* | `#e07b00`* | `#ff8a30`* | LDZL |
| `--color-warning-foreground` | `#ffffff`* | `#ffffff`* | `#ffffff`* | `#000000`* | LDZL |
| `--color-warning-surface` | `#d087001f`* | `#f0b10029`* | `#e07b001f`* | `#ff8a3029`* | LDZL |
| `--color-warning-surface-foreground` | `#894b00`* | `#fdc700`* | `#914e00`* | `#ffa159`* | LDZL |
| `--color-white` | `#ffffff`* | `#ffffff` | `#ffffff` | `#ffffff` | L |
| `--color-workflow-rule` | `#0a0a0a0e`* | `#ffffff11`* | `#0d0d0d0e`* | `#ffffff11`* | LDZL |
| `--color-workflow-trace` | `#0a0a0a4d`* | `#ffffff59`* | `#0d0d0d4d`* | `#ffffff59`* | LDZL |
| `--color-workflow-trace-strong` | `#0a0a0a8c`* | `#ffffff99`* | `#0d0d0d8c`* | `#ffffff99`* | LDZL |

### 2.3 关键语义别名（Compose 取名建议）

| 语义 | Token | 默认亮 | 默认暗 | ZAI 亮 | ZAI 暗 | 置信度 |
|---|---|---|---|---|---|---|
| 页面底色 | `--color-background` | `#FAFAFA` | `#171717` | `#F8F8F8` | `#161616` | confirmed |
| 页面底色（半透明叠层） | `--color-background-alt` | `#F5F5F599` | `#26262699` | `#F8F8F8B3` | `#2B2B2B99` | confirmed |
| 窗口备用底色 | `--color-background-win-alt` | `#E5E5E5` | `#262626` | `#ECECEE` | `#2B2B2B` | confirmed |
| 正文 | `--color-foreground` | `#404040` | `#E5E5E5` | `#262626` | `#D4D4D4` | confirmed |
| 正文反色 | `--color-foreground-inverse` | `#FFFFFF` | `#FFFFFF` | `#FFFFFF` | `#000000` | confirmed |
| 次级文字 | `--color-foreground-subtle` | `#40404099` | `#E5E5E599` | `#26262699` | `#D4D4D499` | confirmed |
| 三级文字 | `--color-foreground-subtlest` | `#40404066` | `#E5E5E54D` | `#26262666` | `#D4D4D44D` | confirmed |
| 面板 | `--color-panel` | `#F5F5F5` | `#171717` | `#FFFFFF` | `#202020` | confirmed |
| 侧栏 | `--color-sidebar` | `#F5F5F5` | `#0A0A0A` | `#F0F0F0` | `#161616` | confirmed |
| 卡片 | `--color-card` | `#FFFFFF` | `#262626` | `#FFFFFF` | `#2B2B2B` | confirmed |
| 弹层 | `--color-popover` | `#FFFFFF` | `#262626` | `#FFFFFF` | `#2B2B2B` | confirmed |
| 菜单 | `--color-menu` | `#FFFFFF` | `#0A0A0A` | `#FFFFFF` | `#2B2B2B` | confirmed |
| 输入框底 | `--color-input` | `#FFFFFF` | `#262626` | `#FFFFFF` | `#2B2B2B` | confirmed |
| 边框 | `--color-border` | `#0A0A0A1A` | `#FAFAFA1A` | `#0D0D0D1A` | `#FFFFFF1A` | confirmed |
| 边框 hover | `--color-border-hover` | `#0A0A0A33` | `#FAFAFA4D` | `#0D0D0D26` | `#FFFFFF26` | confirmed |
| 品牌 | `--color-brand` | `#00BCFF` | `#00A6F4` | `#000000` | `#FFFFFF` | confirmed |
| 输入框聚焦边 | `--color-input-border-focused` | `#00BCFF` | `#00A6F4` | `#0D0D0D26` | `#FFFFFF26` | confirmed |
| 主按钮 | `--color-primary` | `#0A0A0A` | `#FAFAFA` | `#000000` | `#FFFFFF` | confirmed |
| 主按钮前景 | `--color-primary-foreground` | `#FAFAFA` | `#0A0A0A` | `#FFFFFF` | `#000000` | confirmed |
| 次按钮 | `--color-secondary` | `#D4D4D4` | `#404040` | `#E6E6E6` | `#363636` | confirmed |
| 悬停 | `--color-hover` | `#E5E5E5` | `#FFFFFF1A` | `#0D0D0D0D` | `#FFFFFF0D` | confirmed |
| 选中 | `--color-selected` | `#0A0A0A1A` | `#FFFFFF1A` | `#0D0D0D0D` | `#FFFFFF1A` | confirmed |
| 表面叠层 | `--color-surface` | `#0A0A0A08` | `#FFFFFF0D` | `#0D0D0D08` | `#FFFFFF0D` | confirmed |
| 表面 hover | `--color-surface-hover` | `#0A0A0A0D` | `#FFFFFF1A` | `#0D0D0D0D` | `#FFFFFF1A` | confirmed |
| 标签 | `--color-tag` | `#E5E5E5` | `#404040` | `#E6E6E6` | `#363636` | confirmed |
| 破坏 | `--color-destructive` | `#E7000B` | `#FB2C36` | `#E03131` | `#FF5C5C` | confirmed |
| 破坏前景 | `--color-destructive-foreground` | `#FFFFFF` | `#FFFFFF` | `#FFFFFF` | `#FFFFFF` | confirmed |
| 破坏表面 | `--color-destructive-surface` | `#E7000B1A` | `#FB2C3629` | `#E031311A` | `#FF5C5C29` | confirmed |
| 成功 | `--color-success` | `#00A63E` | `#00C950` | `#1E8A3E` | `#46BF72` | confirmed |
| 成功表面 | `--color-success-surface` | `#F0FDF4` | `#032E1573` | `#EAF7EE` | `#46BF7229` | confirmed |
| 警告 | `--color-warning` | `#D08700` | `#F0B100` | `#E07B00` | `#FF8A30` | confirmed |
| 警告表面 | `--color-warning-surface` | `#D087001F` | `#F0B10029` | `#E07B001F` | `#FF8A3029` | confirmed |
| 强调 | `--color-accent` | `#F0F9FF` | `#052F4A80` | `#EBF4FF` | `#001D3D` | confirmed |
| 用户气泡 | `--color-user-message-bubble` | `#0A0A0A0D` | `#FFFFFF14` | `#0D0D0D0D` | `#FFFFFF14` | confirmed |
| 提示气泡 | `--color-tooltip` | `#F5F5F5` | `#171717` | `#F0F0F0` | `#2B2B2B` | confirmed |
| 提示前景 | `--color-tooltip-foreground` | `#0A0A0A` | `#FAFAFA` | `#0D0D0D` | `#F8F8F8` | confirmed |
| 查找高亮 | `--color-find-highlight` | `#FDE68A` | `#713F12` | `#FFF4EB` | `#542500` | confirmed |
| 查找高亮（当前） | `--color-find-highlight-active` | `#FACC15` | `#A16207` | `#FFB26B` | `#FF8A30` | confirmed |

### 2.4 颜色语义实现规则

- **`color-mix(in oklab, X p%, transparent)` = X 的 alpha 为 p%**（预乘混合后反预乘，色相不变）。本仓库所有 `-surface`/`-subtle` 半透明令牌都是这一形式。**confirmed**（CSS Color 5 预乘规则 + 逐项换算）。
- **`color-mix(in oklab, A p%, B)`** 是真正的双色混合，例如 `--color-usage-heatmap-*`、`--color-context-breakdown-4..7`（与白/表面混合）。做 Compose 时必须按公式算，不能当 alpha 处理。**confirmed**。
- `--color-card-border` 恒等于 `--color-border`；`--color-popover-border`、`--color-input-border`、`--color-tab-border` 同。**confirmed**。
- 暗色不是亮色的机械反转：默认暗的 `--color-brand` 从 sky-400→sky-500，`--color-sidebar` 从 neutral-100→neutral-950，`--color-menu` 从白→neutral-950（比 card 更黑）。**confirmed**。
- ZAI 皮肤把品牌色降为纯黑/纯白，把 `--color-input-border-focused` 降为 `--color-border-hover`（不再有蓝色焦点），并把 `--color-foreground` 提一档（亮 800 / 暗 300）。**confirmed**。

---

## 3. 字体与排版

### 3.1 字体族

| Token | 值 | 证据 | 置信度 |
|---|---|---|---|
| `--font-sans` | `ui-sans-serif, system-ui, sans-serif, "Apple Color Emoji", "Segoe UI Emoji", "Segoe UI Symbol", "Noto Color Emoji"` | `:root,:host`（`css:6119`） | confirmed |
| `--font-mono` | `ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", "Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC", monospace` | 同上 | confirmed |
| `--default-font-family` | `var(--font-sans)` | 同上 | confirmed |
| `--default-mono-font-family` | `var(--font-mono)` | 同上 | confirmed |
| 基础字体设置 | `html{font-family:var(--default-font-family, …); line-height:1.5; tab-size:4; -webkit-text-size-adjust:100%; -webkit-tap-highlight-color:transparent; font-feature-settings:normal; font-variation-settings:normal}` | `html,:host`，`css:25069` | confirmed |

> `--font-sans` 没有 CJK 字体；中文靠系统字体回退。`--font-mono` 显式补了 `Microsoft YaHei UI / Microsoft YaHei / PingFang SC / Noto Sans CJK SC`。Compose 侧等宽文本应使用与 mono 一致的 CJK 回退，否则中英混排宽度会跳。**confirmed**。
> JS 里另有一套“产物/分享”用字体回退（`ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, "PingFang SC", "Microsoft YaHei", sans-serif`，`js:89526` 附近），只用于 artifact 渲染，不是主 UI。**confirmed**。

### 3.2 字号阶

两套并存：**Tailwind 静态阶**（`text-xs…text-3xl`）与**运行时 UI 阶**（`text-ui-*`）。主 UI 用后者。

| Token | 值 | 计算值（ui=14 时） | 行高 | 用途 | 置信度 |
|---|---|---|---|---|---|
| `--text-ui-2xs` | `calc(var(--ui-font-size) - 5px)` | 9px | 继承（1.5） | 极小角标 | confirmed |
| `--text-ui-xs` | `calc(var(--ui-font-size) - 4px)` | 10px | 1.5 | 角标/徽标 | confirmed |
| `--text-ui-sm` | `calc(var(--ui-font-size) - 2px)` | 12px | 1.5 | 次级说明 | confirmed |
| `--text-ui-caption` | `calc(var(--ui-font-size) - 1px)` | 13px | 1.5 | 注释 | confirmed |
| `--text-ui-base` | `var(--ui-font-size)` | 14px | 1.5 | 正文/控件 | confirmed |
| `--text-ui-lg` | `calc(var(--ui-font-size) + 2px)` | 16px | 1.5 | 小标题 | confirmed |
| `--text-ui-xl` | `calc(var(--ui-font-size) + 4px)` | 18px | 1.5 | 区块标题 | confirmed |
| `--text-ui-settings-title` | `calc(var(--ui-font-size) + 10px)` | 24px | `calc(4/3)`=1.3333 | 设置页标题 | confirmed |
| `--text-ui-settings-title-lg` | `calc(var(--ui-font-size) + 16px)` | 30px | 1.2 | 设置页大标题 | confirmed |
| `--text-xs` | `.75rem` | 12px | `calc(1/.75)`=1.3333 | Tailwind | confirmed |
| `--text-sm` | `.875rem` | 14px | `calc(1.25/.875)`≈1.4286 | Tailwind | confirmed |
| `--text-base` | `1rem` | 16px | `calc(1.5/1)`=1.5 | Tailwind | confirmed |
| `--text-lg` | `1.125rem` | 18px | `calc(1.75/1.125)`≈1.5556 | Tailwind | confirmed |
| `--text-xl` | `1.25rem` | 20px | `calc(1.75/1.25)`=1.4 | Tailwind | confirmed |
| `--text-2xl` | `1.5rem` | 24px | `calc(2/1.5)`=1.3333 | Tailwind | confirmed |
| `--text-3xl` | `1.875rem` | 30px | `calc(2.25/1.875)`=1.2 | Tailwind | confirmed |
| `--text-mobile-input-safe` | `16px` | 16px | 1.5 | 移动端输入框防 iOS 缩放 | confirmed |

- **`text-ui-*` 工具类只设 `font-size`，不设 `line-height`**（`.text-ui-base{font-size:var(--text-ui-base)}`，`@layer utilities`）。所以“14/21”是继承 1.5 的结果；页面里若要 24.5px 行高需显式 `leading-[1.75]`。**confirmed**。
- 不存在 `--text-4xl/5xl/…`（`grep` 为 0），也不存在 `--text-ui-*--line-height`（除 settings title）。**confirmed**。
- 运行时字号：`--ui-font-size:14px` 由无层级 `:root` 给出（`css:417968`），JS `Vne()` 用 `document.documentElement.style.setProperty('--ui-font-size', n+'px')` 覆盖；`ac()` 夹取 `Math.min(20, Math.max(12, Math.round(n)))`，持久化键 `zcode-ui-font-size-px`（`js:78900` 附近）。**confirmed**。

### 3.3 行高、字重、字距

| Token | 值 | 证据 | 置信度 |
|---|---|---|---|
| `--leading-tight` | `1.25` | `:root,:host` | confirmed |
| `--leading-snug` | `1.375` | 同上 | confirmed |
| `--leading-normal` | `1.5` | 同上 | confirmed |
| `--leading-relaxed` | `1.625` | 同上 | confirmed |
| `--font-weight-normal` | `400` | 同上 | confirmed |
| `--font-weight-medium` | `500` | 同上 | confirmed |
| `--font-weight-semibold` | `600` | 同上 | confirmed |
| `--font-weight-bold` | `700` | 同上 | confirmed |
| `--tracking-tight` | `-.025em` | 同上 | confirmed |
| `--tracking-normal` | `0em` | 同上 | confirmed |
| `--tracking-wide` | `.025em` | 同上 | confirmed |
| `--tracking-widest` | `.1em` | 同上 | confirmed |
| `--tracking-wf-label` | `.09em` | 同上 | confirmed |
| 基础行高 | `html{line-height:1.5}` | `css:25069` | confirmed |
| 工作流行高 | `--tw-leading` 出现 1 / 1.15 / 1.25 / 1.5 / 1.6 / 1.75 / 17px / 18px / 26px / 34px | `@layer utilities` | confirmed |

> 正文常见组合（来自 JS 类名，非新 token）：`text-ui-base` + `leading-[1.75]`（14/24.5）+ `tracking-wide`（0.025em）。**confirmed**（`js:1701334` 等，3.15.3 文档同值，3.17.1 复核仍在）。

### 3.4 标题 / 正文 / 小字 / 代码的完整取值

| 场景 | 字号 | 行高 | 字重 | 字距 | 证据 |
|---|---|---|---|---|---|
| 页面大标题（设置） | 30px（`text-ui-settings-title-lg`） | 1.2 | 600（`font-semibold`） | 0 | `--text-ui-settings-title-lg` |
| 页面标题（设置） | 24px（`text-ui-settings-title`） | 1.3333 | 600 | 0 | `--text-ui-settings-title` |
| Markdown H1 | 18px（`text-ui-xl`） | 1.5 | 600 | 0.025em | `js` 类名 `text-ui-xl font-semibold` |
| Markdown H2 | 16px（`text-ui-lg`） | 1.5 | 600 | 0.025em | `js` 类名 `text-ui-lg font-semibold` |
| Markdown H3/H4 | 14px（`text-ui-base`） | 1.75 | 600 | 0.025em | 3.15.3 文档 + 3.17.1 类名复核 |
| 正文 | 14px | 1.75（24.5px） | 400 | 0.025em | `js:1701334` |
| 次级说明 | 12px（`text-ui-sm`） | 1.5 | 400 | 0 | `.text-ui-sm` |
| 角标 | 10px（`text-ui-xs`） | 1.5 | 500 | 0 | `.text-ui-xs` |
| 行内代码 | 12px（`text-ui-sm`）+ `font-mono` | 1.5 | 400 | 0 | `rounded-md bg-markdown-inline-code/50 …` |
| 代码块 | 12px（`text-ui-sm`） | 20px（`--tw-leading: calc(var(--spacing)*5)`） | 400 | 0 | 3.15.3 文档 + 3.17.1 工具类 |
| 按钮/控件 | 14px | 1.5 | 500 | 0 | `.text-ui-base` + `font-medium` |

> “标题 600 / 正文 400 / 次级 500”是全局字重语义；代码块实际常用 12px/20px（不随 `--ui-font-size` 变，除非显式用 `text-ui-*`）。**inferred**（字号来自 token，具体 12/20 来自组合类名）。

---

## 4. 间距、尺寸、圆角、边框

### 4.1 间距阶

| Token | 值 | 计算值 | 证据 | 置信度 |
|---|---|---|---|---|
| `--spacing` | `.25rem` | **4px** | `:root,:host`（`css:6119`） | confirmed |

- 常规间距工具类都是 `calc(var(--spacing) * N)`（任意值 `p-[7px]` 之类除外）；CSS 中实际出现的乘数（节选，完整见附录 A）：`.5, .75, 1, 1.25, 1.5, 2, 2.5, 3, 3.25, 3.5, 4, 4.5, 5, 6, 6.5, 7, 7.5, 8, 8.5, 9, 10, 11, 12, 13, 14, 15, 16, 18, 20, 24, 25, 28, 29, 32, 36, 38, 39, 40, 44, 48, 50, 52, 56, 58, 60, 64, 66, 72, 80, 84, 90, 96, 100, 128, 168, 3000, 3001`。**confirmed**。
- 因此刻度含 **2 / 3 / 5 / 6 / 10 / 14 / 18 / 26 / 30 / 34px** 等非 4 倍值；`168`=`translate-x-42`，`3000/3001` 是离屏技巧（12000/12004px），不是视觉值。**confirmed**。
- 高频实际值：2/4/6/8/12/16/20/24/32/40/48px。**inferred**（按乘数出现次数）。

### 4.2 圆角

| Token | 值 | px | 证据 | 置信度 |
|---|---|---|---|---|
| `--radius-xs` | `.125rem` | 2 | `:root,:host` | confirmed |
| `--radius-sm` | `.25rem` | 4 | 同上 | confirmed |
| `--radius-md` | `.375rem` | 6 | 同上 | confirmed |
| `--radius-lg` | `.5rem` | 8 | 同上 | confirmed |
| `--radius-xl` | `.75rem` | 12 | 同上 | confirmed |
| `--radius-2xl` | `1rem` | 16 | 同上 | confirmed |
| `--radius-3xl` | `1.5rem` | 24 | 同上 | confirmed |
| 裸 `.rounded` | `.25rem` | 4 | `.rounded`，`css:95298` | confirmed |
| `.rounded-full` | `3.40282e38px`（`calc(infinity*1px)` 的浮点结果） | ∞ | `.rounded-full`，`css:96048` | confirmed |
| 任意值 | `1/2/4/5/6/7/8/9/10/16px` | — | `@layer utilities` 内的 `.rounded-[…]` | confirmed |

> 没有 `--radius`（基名）与 `--radius-4xl`；`rounded-4xl` 不存在。**confirmed**。

### 4.3 边框宽度

| 类 | 宽度 | 证据 | 置信度 |
|---|---|---|---|
| `.border-0` | 0 | `@layer utilities` | confirmed |
| `.border` / `.border-t/b/l/r` | 1px | `css:98102` | confirmed |
| `.border-2` | 2px | `@layer utilities` | confirmed |
| 全局默认 | `*{border:0 solid}` | `css:24906` | confirmed |
| `--tw-border-style` 初值 | `solid` | `@property` | confirmed |

> 实际 UI 的边框几乎全是 1px；2px 仅用于强调/选中。不写颜色时 `border-color: currentColor`（preflight 只设 `border:0 solid`）。**confirmed**。

### 4.4 尺寸类（宽度/高度）

本主题没有 `--size-*` 刻度；`w-*`/`h-*`/`size-*` 用 `calc(var(--spacing) * N)`，`max-w-*` 用 `--container-*`。**confirmed**。
`--aspect-video: 16 / 9` 是唯一 aspect token。**confirmed**。

---

## 5. 阴影与模糊

### 5.1 阴影

| Token | 值 | 证据 | 置信度 |
|---|---|---|---|
| `--shadow-sm` | `0 1px 3px 0 #0000001a, 0 1px 2px -1px #0000001a` | `:root,:host` | confirmed |
| `--shadow-md` | `0 4px 6px -1px #0000001a, 0 2px 4px -2px #0000001a` | 同上 | confirmed |
| `--shadow-lg` | `0 10px 15px -3px #0000001a, 0 4px 6px -4px #0000001a` | 同上 | confirmed |
| `.shadow-xs`（内联） | `0 1px 2px 0 #0000000d` | `@layer utilities` | confirmed |
| `.shadow`（内联，= sm） | `0 1px 3px 0 #0000001a, 0 1px 2px -1px #0000001a` | 同上 | confirmed |
| `.shadow-xl`（内联） | `0 20px 25px -5px #0000001a, 0 8px 10px -6px #0000001a` | 同上 | confirmed |
| `.shadow-2xl`（内联） | `0 25px 50px -12px #00000040` | 同上 | confirmed |
| `--shadow-composer-soft` | 默认亮/暗 `0 0 #0000`；ZAI 亮 `0 1px 3px #0000000a, 0 6px 18px #0000000a`；ZAI 暗无声明→`0 0 #0000` | `:root`（`css:417968`）/`.theme-zai-light` | confirmed |
| `--highspeed-share-dialog-box-shadow`（亮） | `0 22px 6px #0000, 0 14px 6px #00000003, 0 8px 5px #0000000d, 0 3px 3px #00000017, 0 1px 2px #0000001a` | `:root` | confirmed |
| 同上（暗） | `0 63px 18px #0000, 0 41px 16px #00000003, 0 23px 14px #0000000d, 0 10px 10px #00000017, 0 3px 6px #0000001a` | `.dark,.theme-zai-dark` | confirmed |
| 阴影色默认 | `--tw-shadow-color` 缺省时内联 `#0000001a`；`--tw-shadow-alpha:100%` | `.shadow*` + `@property` | confirmed |
| ring 阴影 | `0 0 0 calc(Npx + var(--tw-ring-offset-width)) var(--tw-ring-color, currentcolor)` | `.ring-*` | confirmed |

> 阴影不随主题反转（暗色也用黑色阴影）；暗色里靠边框和层级区分。**confirmed**。
> `shadow-(--shadow-composer-soft)` 是唯一“语义阴影”用法；因默认主题把它置空，Composer 在默认主题下**无阴影**，只有 ZAI 亮有柔光。**confirmed**。

### 5.2 模糊

| Token | 值 | 证据 | 置信度 |
|---|---|---|---|
| `--blur-xs` | `4px` | `:root,:host` | confirmed |
| `--blur-sm` | `8px` | 同上 | confirmed |
| `--blur-md` | `12px` | 同上 | confirmed |
| `--blur-xl` | `24px` | 同上 | confirmed |
| `--blur-2xl` | `40px` | 同上 | confirmed |
| `--blur-3xl` | `64px` | 同上 | confirmed |
| 实际 backdrop-blur 用值 | `1px / 2px / 8px` + 上面 6 档 | `--tw-backdrop-blur` | confirmed |
| 无 `--blur`（基名）/ `--blur-lg` | — | grep 为 0 | confirmed |

### 5.3 透明度

| 项 | 值 | 证据 | 置信度 |
|---|---|---|---|
| 工具类刻度 | `.opacity-0 / 30 / 35 / 40 / 50 / 55 / 60 / 70 / 80 / 85 / 90 / 100` → `0 / .3 / .35 / .4 / .5 / .55 / .6 / .7 / .8 / .85 / .9 / 1` | `@layer utilities` | confirmed |
| 无 `.opacity-5/10/20/25/75/95` | 未生成 | grep 为 0 | confirmed |
| 图标降透明 | `--lucide-subtlest-opacity`：默认亮/暗 `.4`，ZAI 暗 `.3`；通过 `--lucide-filter: opacity(…)` 作用于 `svg.lucide` | token + `@layer base` | confirmed |
| 状态透明 | 禁用 `.4/.5/.7`；滑块禁用 `.5`；hover 容器 `.0 → 1`；`group-hover` 常用 `0/100` | 组件类 | confirmed |
| 遮罩 | 全屏遮罩 `bg-black/40`（`rgba(0,0,0,.4)`） | `js` 类名 | confirmed |
| `--tw-opacity` | `@property` 注册，`syntax:"*"`，无初值 | `@property` | confirmed |

> 透明度没有主题变量；只有 `--lucide-subtlest-opacity` 是语义化的。`/40`、`/50` 这类斜杠透明度是 Tailwind 在颜色上直接生成 `color-mix` 或 8 位 hex。**confirmed**。

---

## 6. 动效

### 6.1 过渡默认值

| Token | 值 | 证据 | 置信度 |
|---|---|---|---|
| `--default-transition-duration` | `.15s` | `:root,:host` | confirmed |
| `--default-transition-timing-function` | `cubic-bezier(.4, 0, .2, 1)` | 同上 | confirmed |
| `--ease-out` | `cubic-bezier(0, 0, .2, 1)` | 同上 | confirmed |
| `--ease-in-out` | `cubic-bezier(.4, 0, .2, 1)` | 同上 | confirmed |

> 没有 `--ease-in`、`--ease-linear`、`--duration-*` 主题变量；时长是工具类内联 `--tw-duration`。**confirmed**。

### 6.2 实际出现的时长 / 缓动

| 类别 | 值 | 出现处 | 置信度 |
|---|---|---|---|
| 时长 | `.1s .12s .15s .16s .18s .2s .23s .24s .3s .32s .42s .46s .5s .6s .8s 1s 1.2s 1.4s 1.6s 1.8s 2s 2.4s 4s 5s` | `--tw-duration` / `transition-*` / `animation` | confirmed |
| 缓动 | `cubic-bezier(.4,0,.2,1)`（默认）、`cubic-bezier(0,0,.2,1)`（out）、`cubic-bezier(.34,1.56,.64,1)`（回弹）、`cubic-bezier(.77,0,.175,1)`（in-out 强）、`cubic-bezier(.16,1,.3,1)`、`cubic-bezier(.22,1,.36,1)`、`cubic-bezier(.23,1,.32,1)`、`cubic-bezier(.33,0,.16,1)`、`cubic-bezier(.22,.61,.36,1)`（工作流 `--wf-ease`）、`linear`、`ease-in-out`、`step-end` | `@layer utilities` / 组件类 | confirmed |

### 6.3 关键动画清单（59 个 `@keyframes`）

| 名称 | 时长 / 缓动 / 循环 | 用途 | 置信度 |
|---|---|---|---|
| `enter` / `exit` | `.15s`，`var(--tw-ease, ease)`，1 次 | Radix `data-open/closed` 进出场（`animate-in/out`） | confirmed |
| `collapsible-down/up` | `.2s ease-out` | 折叠面板 | confirmed |
| `accordion-down/up` | `.2s ease-out` | 手风琴 | confirmed |
| `spin` | `1s linear infinite` | 加载环 | confirmed |
| `pulse` | `2s cubic-bezier(.4,0,.6,1) infinite` | 骨架/呼吸 | confirmed |
| `ping` | `1s cubic-bezier(0,.2,1) infinite` | 状态点 | confirmed |
| `gradient-flow` | `4s linear infinite` | `animated-gradient-text` | confirmed |
| `tool-call-progress-indeterminate` | `1.2s ease-in-out infinite` | 工具调用不确定进度条 | confirmed |
| `composer-thought-bubble-flow` | `linear infinite` | Composer 思考气泡 | confirmed |
| `markdown-image-loading-shimmer` | `1.8s linear infinite` | 图片占位微光 | confirmed |
| `zcode-alarm-ring` | `.6s ease-in-out infinite` | 提醒铃 | confirmed |
| `zcode-stream-text-in` / `zcode-stream-marker-in` / `zcode-collapsible-up` / `zcode-collapsible-fade-out` | 组件内声明 | 流式文字/标记 | confirmed |
| `wf-*`（24 个） | `--wf-t-fast .12s / --wf-t-base .16s / --wf-t-enter .2s / --wf-t-ink .32s / --wf-beat 1.6s`，缓动 `cubic-bezier(.22,.61,.36,1)` | 工作流画布 | confirmed |
| `task-search-result-highlight` | `1.2s ease-out both` | 搜索命中高亮 | confirmed |
| `highspeed-share-dialog-*` | `.42s/.46s cubic-bezier(.22,1,.36,1)`，`1.2s cubic-bezier(.33,0,.16,1)` | 分享卡片 | confirmed |
| 其余（`onboarding-logo-border-sweep`、`wf-face-*`、`zcode-reaction-*`、`zcode-loading-*`…） | 见 `@keyframes` 名 | 局部装饰 | confirmed |

### 6.4 减动效

- 没有全局 `@media (prefers-reduced-motion: reduce){*{animation:none}}`；每个动画单独在 `@media (prefers-reduced-motion:reduce)` 里 `animation:none` / `transition:none`，现有 **35 条**这样的规则，覆盖 composer 思考、工具进度、流式文字、工作流、分享卡、图片 shimmer 等。**confirmed**。
- hover 类效果统一包在 `@media (hover:hover)` 里（203 条），触摸设备不会触发 hover。**confirmed**。
- `motion-safe:animate-pulse` 只在 `no-preference` 时启用；`motion-reduce:*` 提供 `animate-none/transition-none` 等逃生口。**confirmed**。
- `html[data-document-hidden] *` 强制 `transition:none!important; animation:none!important`（`css:418243`），用于文档隐藏/后台时省电。**confirmed**。

---

## 7. 层级（z-index）

| 值 | 语义/用途 | 证据 | 置信度 |
|---|---|---|---|
| `0 / 1 / 2` | 局部堆叠 | `.z-0/.z-1/.z-2` | confirmed |
| `10 / 20 / 30 / 40` | 常规浮层 | `.z-10/.z-20/.z-30/.z-40` | confirmed |
| `19` | 特殊组件（表格/工具条） | `.z-[19]` | confirmed |
| `50` | Tooltip / Popover / Dialog / Toast 常用层 | `.z-50`（JS 18 处，如 tooltip-content） | confirmed |
| `60` | Select / DropdownMenu 内容层 | `.z-[60]`（`js` 6 处） | confirmed |
| `61` | 二级浮层 | `.z-[61]` | confirmed |
| `100` | 全屏遮罩层（验证码/全屏弹层） | `.z-[100]`（`js` 1 处） | confirmed |
| `9998` | 调试/日志浮窗 | `.z-[9998]`（`js` 1 处） | confirmed |
| `9999` | 最高常规层（工具类存在，未见业务使用） | `.z-[9999]` | confirmed |
| `2147483647` | 不可见 iframe/验证码挂载点 | `.z-[2147483647]`（`js` 1 处） | confirmed |

> 没有 `--z-*` 主题变量；层级是散落的字面量。**confirmed**。

---

## 8. 容器、断点、响应式

### 8.1 容器宽度刻度（`--container-*`）

| Token | 值 | px | 证据 | 置信度 |
|---|---|---|---|---|
| `--container-xs` | `20rem` | 320 | `:root,:host` | confirmed |
| `--container-sm` | `24rem` | 384 | 同上 | confirmed |
| `--container-md` | `28rem` | 448 | 同上 | confirmed |
| `--container-lg` | `32rem` | 512 | 同上 | confirmed |
| `--container-xl` | `36rem` | 576 | 同上 | confirmed |
| `--container-2xl` | `42rem` | 672 | 同上 | confirmed |
| `--container-3xl` | `48rem` | 768 | 同上 | confirmed |
| `--container-4xl` | `56rem` | 896 | 同上 | confirmed |
| `--container-5xl` | `64rem` | 1024 | 同上 | confirmed |
| `--container-6xl` | `72rem` | 1152 | 同上 | confirmed |

> **没有 `--container-7xl`**；`max-w-7xl` 不生成。**confirmed**。

### 8.2 媒体断点

| 名称 | 条件 | px | 证据 | 置信度 |
|---|---|---|---|---|
| sm | `@media (width>=40rem)` | 640 | 111 条规则 | confirmed |
| md | `@media (width>=48rem)` | 768 | 59 条；`max-md` = `not all and (width>=48rem)` 56 条 | confirmed |
| lg | `@media (width>=64rem)` | 1024 | 20 条 | confirmed |
| xl | `@media (width>=80rem)` | 1280 | 4 条 | confirmed |
| 2xl | `@media (width>=96rem)` | 1536 | 1 条 | confirmed |
| 移动判定 | `(max-width: 767px)` + `(hover: none) and (pointer: coarse)` | <768 | `js:3129504`（`R0t`）| confirmed |
| 移动端 UI 判定 | `(max-width: 767px)` | <768 | `js:5925153`（`OXn`）| confirmed |
| 矮屏 | `(height<=740px)` / `(height>=36rem)` | — | 8+8 条 | confirmed |

### 8.3 容器查询阈值（`@container`）

| 容器名 | 阈值 | 用途 | 证据 |
|---|---|---|---|
| `conversation` | 360/624/864/1280px，28rem | 对话列宽、气泡宽度、表格 inset | `@container conversation (width>=864px)` 等 |
| `composer` | 24rem/32rem/36rem/480px | 输入区布局 | `.@min-[…]/composer` |
| `workspace-header` | 420/560px | 顶栏收缩 | `@container workspace-header not (width>=560px)` |
| `share` | 480/640px | 分享页栅格 | `@container share (width>=640px)` |
| `topoverlayer` | 216/280px | 顶部浮层 | `@container topoverlayer` |
| 其它 | `source-server-devices` 36rem、`plugin-store` 40rem、`launcher` 36rem、`wf-artifacts` 380px、`wf-card` 480px | — | — |

> 这些都是**容器查询**，不是视口断点；Compose 里对应“按父容器宽度分支”。**confirmed**。

### 8.4 页面/对话容器宽度（实测类名）

| 场景 | 类名 / 值 | 解析 | 证据 | 置信度 |
|---|---|---|---|---|
| 页面根容器（各页一致） | `flex h-dvh … flex-col overflow-hidden bg-background text-foreground`（onboarding 为 `relative flex h-dvh w-full min-h-0 …`） | 100dvh 满屏、无圆角 | `js:6538772`、`js:4295017` | confirmed |
| 设置页栅格（窄） | `grid-cols-[68px_minmax(0,1fr)]` | 左侧分区栏 68px | `js:6478419` | confirmed |
| 设置页栅格（lg） | `lg:grid-cols-[268px_minmax(0,1fr)]` | 左侧分区栏 268px | 同上 | confirmed |
| 对话列（空态） | `max-w-2xl` | 672px | `js:3744184`（`Mnn`） | confirmed |
| 对话列（<864px） | `w-full` | 100% | `js:3744368`（`Nnn/Pnn`） | confirmed |
| 对话列（≥864px） | `w-[calc(100%-6rem)] max-w-4xl` | 减 96px，上限 896px | 同上 + `@container conversation (width>=864px)` | confirmed |
| 对话列（≥1280px） | `w-[calc(100%-24rem)] max-w-6xl` + `-translate-x-42` | 减 384px，上限 1152px，左移 168px | 同上 + `@container conversation (width>=1280px)` | confirmed |
| 居中弹窗 | `max-w-md` = 448px / `max-w-lg` 512 / `max-w-xl` 576 / `max-w-2xl` 672 | — | `max-w-*` 用法统计 | confirmed |
| 移动底部弹层 | `fixed inset-x-0 bottom-0 z-50 max-h-[85dvh] w-full max-w-md rounded-t-2xl` + 安全区 padding | 448px 上限、上圆角 16px | `js`（safe-area 片段） | confirmed |

### 8.5 移动端 / 桌面端差异

| 差异 | 移动 | 桌面 | 证据 | 置信度 |
|---|---|---|---|---|
| 输入框字号 | 16px（`text-mobile-input-safe`） | `text-ui-base`（14px） | `max-md:text-mobile-input-safe`（8 处） | confirmed |
| 弹层形态 | 底部 sheet（`rounded-t-2xl`，`max-h-[85dvh]`，安全区） | 居中 dialog | `js` 片段 | confirmed |
| hover 效果 | 不触发（`@media (hover:none)` / `hover:hover` 门控） | 触发 | 203 条 `@media (hover:hover)` | confirmed |
| 设置页分区栏 | 68px 轨（栅格默认） | 268px（lg） | `js:6478419` | confirmed |
| 安全区 | `env(safe-area-inset-top/bottom)` + 12px | 0 | 3 个 arbitrary 类 | confirmed |
| 布局判定 | `(max-width:767px) and (hover:none) and (pointer:coarse)` | 其它 | `js:3129504` | confirmed |

> `--text-mobile-input-safe` 不是“字号 16 的全局规则”，只是给移动端输入控件用的 token；`max-md:` 前缀在 768px 以下生效。**confirmed**。

---

## 9. 全局 reset 与基础样式

### 9.1 reset（`@layer base`，`css:24906` 起）

| 规则 | 声明 | 证据 | 置信度 |
|---|---|---|---|
| `*,:after,:before,::backdrop` | `box-sizing:border-box; border:0 solid; margin:0; padding:0` | `css:24906` | confirmed |
| `::file-selector-button` | 同上 + `font:inherit; …; border-radius:0` | `@layer base` | confirmed |
| `html,:host` | `line-height:1.5; tab-size:4; text-size-adjust:100%; tap-highlight-color:transparent` | `css:25069` | confirmed |
| `hr` | `height:0; color:inherit; border-top-width:1px` | 同上 | confirmed |
| `h1..h6` | `font-size:inherit; font-weight:inherit` | 同上 | confirmed |
| `a` | `color:inherit; text-decoration:inherit` | 同上 | confirmed |
| `b,strong` | `font-weight:bolder` | 同上 | confirmed |
| `code,kbd,samp,pre` | `font-family:var(--default-mono-font-family); font-size:1em` | 同上 | confirmed |
| `small` | `font-size:80%` | 同上 | confirmed |
| `ol,ul,menu` | `list-style:none` | 同上 | confirmed |
| `img,svg,video,canvas,audio,iframe,embed,object` | `display:block; vertical-align:middle` | 同上 | confirmed |
| `img,video` | `max-width:100%; height:auto` | 同上 | confirmed |
| `button,input,select,optgroup,textarea` | `font:inherit; letter-spacing:inherit; color:inherit; opacity:1; background:transparent; border-radius:0` | 同上 | confirmed |
| `textarea` | `resize:vertical` | 同上 | confirmed |
| `[hidden]:not([hidden=until-found])` | `display:none!important` | 同上 | confirmed |
| `::placeholder` | `opacity:1`；支持 color-mix 时 `color: color-mix(in oklab, currentColor 50%, transparent)` | `css:27184/27297/27376` | confirmed |

### 9.2 html / body / #root

| 选择器 | 声明 | 证据 | 置信度 |
|---|---|---|---|
| `html,:host` | 字体、`line-height:1.5`、`tab-size:4`、`-webkit-text-size-adjust:100%`、`-webkit-tap-highlight-color:transparent` | `css:25069` | confirmed |
| `html,body,#root` | `width:100%; min-width:0; height:100%; min-height:0; background:0 0!important` | `css:417879` | confirmed |
| `body` | `margin:0; overflow:hidden` | `css:418213` | confirmed |
| `#root` | `height:100dvh; overflow:hidden` | `css:418565` | confirmed |
| 根 div（React） | `bg-background text-foreground`；壳层 `flex h-dvh … flex-col overflow-hidden` | `js:6538772`、`js:4295017` | confirmed |
| `color-scheme` | 由 JS 写 `html.style.colorScheme = 'light'\|'dark'`（`ere()`），并同步 `<meta name="theme-color">` | `js:78653` | confirmed |

> 结论：**html/body 永远透明、无背景图、无背景色**；页面底色完全由根 div 的 `background-color: var(--color-background)` 决定。Compose 应把背景画在最外层 Box 上，而不是 window 背景。**confirmed**。

### 9.3 滚动条

| 选择器/规则 | 声明 | 证据 | 置信度 |
|---|---|---|---|
| `::-webkit-scrollbar` | `width:14px; height:14px` | `css:448434` | confirmed |
| `::-webkit-scrollbar-track` | `background:0 0`（透明） | 同上 | confirmed |
| `::-webkit-scrollbar-thumb` | `background:var(--color-border); background-clip:padding-box; border:3px solid #0000; border-radius:9999px; min-width:32px; min-height:32px` | 同上 | confirmed |
| `::-webkit-scrollbar-corner` | `background:0 0` | 同上 | confirmed |
| `.scrollbar-hide` / `[scrollbar-width:none]` | `scrollbar-width:none`；`::-webkit-scrollbar{display:none}` | `@layer utilities` | confirmed |
| `[scrollbar-gutter:stable]` | `scrollbar-gutter:stable`（对话滚动容器） | `@layer utilities` + JS 8 处 | confirmed |
| 面板专用 thumb | `:is([data-project-sidebar-panel],[data-workspace-conversation-frame],[data-workspace-side-frame])::-webkit-scrollbar-thumb{background-color:var(--zcode-scrollbar-thumb)}` | `@property --zcode-scrollbar-thumb` + 规则 | confirmed |
| thumb 状态 | `[data-scrollbar-active]{--zcode-scrollbar-thumb:var(--color-border)}`；hover `foreground 30%`；dragging `foreground 45%`；默认 `transparent` | 同上 | confirmed |
| Firefox | 无 `scrollbar-color`/`scrollbar-width` 全局声明 | grep 为 0 | confirmed |

> 14px 总宽 + 3px 透明边 → 视觉 thumb 约 8px；hover 才显形（默认 transparent，靠 JS 打 `data-scrollbar-active`）。**inferred**（由声明与状态属性推出）。

### 9.4 选中色 / 插入符 / 焦点 / 禁用

| 项 | 实际值 | 证据 | 置信度 |
|---|---|---|---|
| `::selection` | **无全局规则**，仅 `.xterm .xterm-accessibility-tree ::selection{color:#0000}`；使用浏览器默认选中色 | grep `::selection` 仅 1 条 | confirmed（缺失） |
| `caret-color` | 无全局声明 | grep 为 0 | confirmed |
| 焦点环（机制） | `ring-2` = `box-shadow: 0 0 0 calc(2px + var(--tw-ring-offset-width)) var(--tw-ring-color, currentcolor)`；`--tw-ring-offset-width` 初值 `0`、`--tw-ring-offset-color` 初值 `#fff` | `@property` + `.ring-2` | confirmed |
| 焦点环（实际） | 全局 `:focus,:focus-visible{box-shadow:none!important; outline:none!important}` 把所有非 `!important` 的 ring/outline 压掉 | `css:418601` | confirmed |
| 焦点色来源 | `focus-visible:ring-input-border-focused` → `var(--color-input-border-focused)`；另有 `ring-primary`、`ring-destructive/20\|30\|40`、`ring-foreground/50` | `@layer utilities` | confirmed |
| `--color-ring` | **未定义**；3 处 `outline:2px solid var(--color-ring)`（composer-thought-slider、wf-tile）→ 声明失效 | grep 无定义 | unresolved |
| 禁用（全局） | 无 `:disabled` 全局规则；工具类 `disabled:opacity-40/50/70`、`disabled:cursor-not-allowed`、`disabled:pointer-events-none`、`disabled:text-foreground-subtlest` | `@layer utilities` | confirmed |
| 禁用（组件约定） | 透明度 `.4` / `.5` / `.7` 三档；滑块 `[data-disabled=true]{opacity:.5}` | 同上 | confirmed |
| forced-colors | `@media (forced-colors:active){:focus,:focus-visible{box-shadow:none!important; outline:none!important}}` | `css:418736` | confirmed |

> **Compose 落地建议（inferred）**：焦点环按“意图”实现为 2dp 实色环，颜色 `input-border-focused`（默认主题=品牌色；ZAI=边框 hover 色），offset 0；不要照抄 Web 的“全局压掉”结果，否则键盘可达性为零。

### 9.5 overscroll / touch-action / scroll-behavior

| 项 | 实际值 | 证据 | 置信度 |
|---|---|---|---|
| `scroll-behavior` | 无全局声明（默认 `auto`） | grep 为 0 | confirmed |
| `overscroll-behavior` | 无全局声明；组件用 `.overscroll-contain{overscroll-behavior:contain}`（14 处） | `@layer utilities` + JS | confirmed |
| `touch-action` | 无全局声明；工具类 `touch-manipulation` / `touch-none` / `touch-pan-y`（`--tw-pan-*`） | `@layer utilities` | confirmed |
| 滚动容器 | `body{overflow:hidden}`、`#root{overflow:hidden}`；滚动发生在内部 `overflow-y-auto` 容器 | `css:418213/418565` | confirmed |
| 手势拖拽 | 画布/滑块用 `touch-none` + `cursor-grab` | JS 类名 | confirmed |

### 9.6 安全区

| 用法 | 值 | 证据 | 置信度 |
|---|---|---|---|
| 顶部 | `pt-[calc(env(safe-area-inset-top)+12px)]` | `.pt-[calc(env(safe-area-inset-top)+12px)]`（2 处） | confirmed |
| 底部 | `pb-[calc(env(safe-area-inset-bottom)+12px)]` | 同上（1 处，移动 sheet） | confirmed |
| 悬浮按钮 | `bottom-[calc(1rem+env(safe-area-inset-bottom))]` | 同上 | confirmed |
| Compose | 对应 `WindowInsets.safeDrawing` + 12dp padding | inferred | inferred |

### 9.7 其他全局开关

| 规则 | 声明 | 证据 | 置信度 |
|---|---|---|---|
| `html[data-document-hidden] *`（含 `:before/:after`） | `transition:none!important; animation:none!important` | `css:418243` | confirmed |
| `video[controls],audio[controls]` | `color-scheme:light`；`.dark` 下 `color-scheme:dark`（lightningcss 生成的 `--lightningcss-*` 开关） | `css` 片段 | confirmed |
| `text-wrap-phrase` | `overflow-wrap:break-word; word-break:normal; line-break:strict; text-wrap:pretty`；支持时 `word-break:auto-phrase` | `@layer utilities` | confirmed |
| Lucide 图标 | `svg.lucide[stroke=currentColor]{stroke:var(--lucide-stroke,currentColor); filter:var(--lucide-filter,none)}`；`--lucide-subtlest-opacity` 亮/暗 `.4`、ZAI 暗 `.3` | `@layer base` + token | confirmed |

---

## 10. 自定义属性总表（非颜色 / 非上述刻度）

| Token | 默认亮 | 默认暗 | ZAI 亮 | ZAI 暗 | 直接声明 |
|---|---|---|---|---|---|
| `--animate-ping` | `ping 1s cubic-bezier(0, 0, .2, 1) infinite`* | `ping 1s cubic-bezier(0, 0, .2, 1) infinite` | `ping 1s cubic-bezier(0, 0, .2, 1) infinite` | `ping 1s cubic-bezier(0, 0, .2, 1) infinite` | L |
| `--animate-pulse` | `pulse 2s cubic-bezier(.4, 0, .6, 1) infinite`* | `pulse 2s cubic-bezier(.4, 0, .6, 1) infinite` | `pulse 2s cubic-bezier(.4, 0, .6, 1) infinite` | `pulse 2s cubic-bezier(.4, 0, .6, 1) infinite` | L |
| `--animate-spin` | `spin 1s linear infinite`* | `spin 1s linear infinite` | `spin 1s linear infinite` | `spin 1s linear infinite` | L |
| `--animated-gradient-text-soft` | `#0a0a0a38`* | `#fff3`* | `#0d0d0d38`* | `#ffffff38`* | LDZL |
| `--animated-gradient-text-strong` | `#0a0a0a`* | `#fff`* | `#0d0d0d`* | `#fff`* | LDZL |
| `--aspect-video` | `16 / 9`* | `16 / 9` | `16 / 9` | `16 / 9` | L |
| `--blur-2xl` | `40px`* | `40px` | `40px` | `40px` | L |
| `--blur-3xl` | `64px`* | `64px` | `64px` | `64px` | L |
| `--blur-md` | `12px`* | `12px` | `12px` | `12px` | L |
| `--blur-sm` | `8px`* | `8px` | `8px` | `8px` | L |
| `--blur-xl` | `24px`* | `24px` | `24px` | `24px` | L |
| `--blur-xs` | `4px`* | `4px` | `4px` | `4px` | L |
| `--container-2xl` | `42rem`* | `42rem` | `42rem` | `42rem` | L |
| `--container-3xl` | `48rem`* | `48rem` | `48rem` | `48rem` | L |
| `--container-4xl` | `56rem`* | `56rem` | `56rem` | `56rem` | L |
| `--container-5xl` | `64rem`* | `64rem` | `64rem` | `64rem` | L |
| `--container-6xl` | `72rem`* | `72rem` | `72rem` | `72rem` | L |
| `--container-lg` | `32rem`* | `32rem` | `32rem` | `32rem` | L |
| `--container-md` | `28rem`* | `28rem` | `28rem` | `28rem` | L |
| `--container-sm` | `24rem`* | `24rem` | `24rem` | `24rem` | L |
| `--container-xl` | `36rem`* | `36rem` | `36rem` | `36rem` | L |
| `--container-xs` | `20rem`* | `20rem` | `20rem` | `20rem` | L |
| `--default-font-family` | `var(--font-sans)`* | `var(--font-sans)` | `var(--font-sans)` | `var(--font-sans)` | L |
| `--default-mono-font-family` | `var(--font-mono)`* | `var(--font-mono)` | `var(--font-mono)` | `var(--font-mono)` | L |
| `--default-transition-duration` | `.15s`* | `.15s` | `.15s` | `.15s` | L |
| `--default-transition-timing-function` | `cubic-bezier(.4, 0, .2, 1)`* | `cubic-bezier(.4, 0, .2, 1)` | `cubic-bezier(.4, 0, .2, 1)` | `cubic-bezier(.4, 0, .2, 1)` | L |
| `--ease-in-out` | `cubic-bezier(.4, 0, .2, 1)`* | `cubic-bezier(.4, 0, .2, 1)` | `cubic-bezier(.4, 0, .2, 1)` | `cubic-bezier(.4, 0, .2, 1)` | L |
| `--ease-out` | `cubic-bezier(0, 0, .2, 1)`* | `cubic-bezier(0, 0, .2, 1)` | `cubic-bezier(0, 0, .2, 1)` | `cubic-bezier(0, 0, .2, 1)` | L |
| `--font-mono` | `ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", "Microsoft YaHei U…`* | `ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", "Microsoft YaHei U…` | `ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", "Microsoft YaHei U…` | `ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", "Microsoft YaHei U…` | L |
| `--font-sans` | `ui-sans-serif, system-ui, sans-serif, "Apple Color Emoji", "Segoe UI Emoji", "Segoe UI Symbol", "Noto Color…`* | `ui-sans-serif, system-ui, sans-serif, "Apple Color Emoji", "Segoe UI Emoji", "Segoe UI Symbol", "Noto Color…` | `ui-sans-serif, system-ui, sans-serif, "Apple Color Emoji", "Segoe UI Emoji", "Segoe UI Symbol", "Noto Color…` | `ui-sans-serif, system-ui, sans-serif, "Apple Color Emoji", "Segoe UI Emoji", "Segoe UI Symbol", "Noto Color…` | L |
| `--font-weight-bold` | `700`* | `700` | `700` | `700` | L |
| `--font-weight-medium` | `500`* | `500` | `500` | `500` | L |
| `--font-weight-normal` | `400`* | `400` | `400` | `400` | L |
| `--font-weight-semibold` | `600`* | `600` | `600` | `600` | L |
| `--highspeed-share-card-dark-background-opacity` | `0`* | `1`* | `0` | `1` | LD |
| `--highspeed-share-card-light-background-opacity` | `1`* | `0`* | `1` | `0` | LD |
| `--highspeed-share-dialog-box-shadow` | `0 22px 6px #0000, 0 14px 6px #00000003, 0 8px 5px #0000000d, 0 3px 3px #00000017, 0 1px 2px #0000001a`* | `0 63px 18px #0000, 0 41px 16px #00000003, 0 23px 14px #0000000d, 0 10px 10px #00000017, 0 3px 6px #0000001a`* | `0 22px 6px #0000, 0 14px 6px #00000003, 0 8px 5px #0000000d, 0 3px 3px #00000017, 0 1px 2px #0000001a` | `0 63px 18px #0000, 0 41px 16px #00000003, 0 23px 14px #0000000d, 0 10px 10px #00000017, 0 3px 6px #0000001a` | LD |
| `--highspeed-sidebar-card-outline` | `var(--color-border)`* | `transparent`* | `var(--color-border)` | `transparent` | LD |
| `--highspeed-sidebar-card-surface` | `#fafafa`* | `transparent`* | `#fafafa` | `transparent` | LD |
| `--highspeed-sidebar-model-gradient` | `linear-gradient(90deg, #4830c8 0%, #6041ff 50%, #8955db 100%)`* | `linear-gradient(90deg, #9b85ff 0%, #b09aff 50%, #ceacff 100%)`* | `linear-gradient(90deg, #4830c8 0%, #6041ff 50%, #8955db 100%)` | `linear-gradient(90deg, #9b85ff 0%, #b09aff 50%, #ceacff 100%)` | LD |
| `--highspeed-sidebar-model-icon` | `#6041ff`* | `#b09aff`* | `#6041ff` | `#b09aff` | LD |
| `--leading-normal` | `1.5`* | `1.5` | `1.5` | `1.5` | L |
| `--leading-relaxed` | `1.625`* | `1.625` | `1.625` | `1.625` | L |
| `--leading-snug` | `1.375`* | `1.375` | `1.375` | `1.375` | L |
| `--leading-tight` | `1.25`* | `1.25` | `1.25` | `1.25` | L |
| `--lightningcss-dark` | `—` | `initial`* | `—` | `initial` | D |
| `--lightningcss-light` | `—` | `(空)`* | `—` | `(空)` | D |
| `--loading-pixel-peak` | `—` | `1`* | `—` | `1` | D |
| `--lucide-subtlest-opacity` | `.4`* | `.3`* | `.4`* | `.3`* | LDZL |
| `--radius-2xl` | `1rem`* | `1rem` | `1rem` | `1rem` | L |
| `--radius-3xl` | `1.5rem`* | `1.5rem` | `1.5rem` | `1.5rem` | L |
| `--radius-lg` | `.5rem`* | `.5rem` | `.5rem` | `.5rem` | L |
| `--radius-md` | `.375rem`* | `.375rem` | `.375rem` | `.375rem` | L |
| `--radius-sm` | `.25rem`* | `.25rem` | `.25rem` | `.25rem` | L |
| `--radius-xl` | `.75rem`* | `.75rem` | `.75rem` | `.75rem` | L |
| `--radius-xs` | `.125rem`* | `.125rem` | `.125rem` | `.125rem` | L |
| `--shadow-composer-soft` | `0 0 #0000`* | `0 0 #0000` | `0 1px 3px #0000000a, 0 6px 18px #0000000a`* | `0 0 #0000` | LZ |
| `--shadow-lg` | `0 10px 15px -3px #0000001a, 0 4px 6px -4px #0000001a`* | `0 10px 15px -3px #0000001a, 0 4px 6px -4px #0000001a` | `0 10px 15px -3px #0000001a, 0 4px 6px -4px #0000001a` | `0 10px 15px -3px #0000001a, 0 4px 6px -4px #0000001a` | L |
| `--shadow-md` | `0 4px 6px -1px #0000001a, 0 2px 4px -2px #0000001a`* | `0 4px 6px -1px #0000001a, 0 2px 4px -2px #0000001a` | `0 4px 6px -1px #0000001a, 0 2px 4px -2px #0000001a` | `0 4px 6px -1px #0000001a, 0 2px 4px -2px #0000001a` | L |
| `--shadow-sm` | `0 1px 3px 0 #0000001a, 0 1px 2px -1px #0000001a`* | `0 1px 3px 0 #0000001a, 0 1px 2px -1px #0000001a` | `0 1px 3px 0 #0000001a, 0 1px 2px -1px #0000001a` | `0 1px 3px 0 #0000001a, 0 1px 2px -1px #0000001a` | L |
| `--spacing` | `.25rem`* | `.25rem` | `.25rem` | `.25rem` | L |
| `--text-2xl` | `1.5rem`* | `1.5rem` | `1.5rem` | `1.5rem` | L |
| `--text-2xl--line-height` | `calc(2 / 1.5)`* | `calc(2 / 1.5)` | `calc(2 / 1.5)` | `calc(2 / 1.5)` | L |
| `--text-3xl` | `1.875rem`* | `1.875rem` | `1.875rem` | `1.875rem` | L |
| `--text-3xl--line-height` | `calc(2.25 / 1.875)`* | `calc(2.25 / 1.875)` | `calc(2.25 / 1.875)` | `calc(2.25 / 1.875)` | L |
| `--text-base` | `1rem`* | `1rem` | `1rem` | `1rem` | L |
| `--text-base--line-height` | `calc(1.5 / 1)`* | `calc(1.5 / 1)` | `calc(1.5 / 1)` | `calc(1.5 / 1)` | L |
| `--text-lg` | `1.125rem`* | `1.125rem` | `1.125rem` | `1.125rem` | L |
| `--text-lg--line-height` | `calc(1.75 / 1.125)`* | `calc(1.75 / 1.125)` | `calc(1.75 / 1.125)` | `calc(1.75 / 1.125)` | L |
| `--text-mobile-input-safe` | `16px`* | `16px` | `16px` | `16px` | L |
| `--text-sm` | `.875rem`* | `.875rem` | `.875rem` | `.875rem` | L |
| `--text-sm--line-height` | `calc(1.25 / .875)`* | `calc(1.25 / .875)` | `calc(1.25 / .875)` | `calc(1.25 / .875)` | L |
| `--text-ui-2xs` | `calc(var(--ui-font-size) - 5px)`* | `calc(var(--ui-font-size) - 5px)` | `calc(var(--ui-font-size) - 5px)` | `calc(var(--ui-font-size) - 5px)` | L |
| `--text-ui-base` | `var(--ui-font-size)`* | `var(--ui-font-size)` | `var(--ui-font-size)` | `var(--ui-font-size)` | L |
| `--text-ui-caption` | `calc(var(--ui-font-size) - 1px)`* | `calc(var(--ui-font-size) - 1px)` | `calc(var(--ui-font-size) - 1px)` | `calc(var(--ui-font-size) - 1px)` | L |
| `--text-ui-lg` | `calc(var(--ui-font-size) + 2px)`* | `calc(var(--ui-font-size) + 2px)` | `calc(var(--ui-font-size) + 2px)` | `calc(var(--ui-font-size) + 2px)` | L |
| `--text-ui-settings-title` | `calc(var(--ui-font-size) + 10px)`* | `calc(var(--ui-font-size) + 10px)` | `calc(var(--ui-font-size) + 10px)` | `calc(var(--ui-font-size) + 10px)` | L |
| `--text-ui-settings-title--line-height` | `calc(4 / 3)`* | `calc(4 / 3)` | `calc(4 / 3)` | `calc(4 / 3)` | L |
| `--text-ui-settings-title-lg` | `calc(var(--ui-font-size) + 16px)`* | `calc(var(--ui-font-size) + 16px)` | `calc(var(--ui-font-size) + 16px)` | `calc(var(--ui-font-size) + 16px)` | L |
| `--text-ui-settings-title-lg--line-height` | `1.2`* | `1.2` | `1.2` | `1.2` | L |
| `--text-ui-sm` | `calc(var(--ui-font-size) - 2px)`* | `calc(var(--ui-font-size) - 2px)` | `calc(var(--ui-font-size) - 2px)` | `calc(var(--ui-font-size) - 2px)` | L |
| `--text-ui-xl` | `calc(var(--ui-font-size) + 4px)`* | `calc(var(--ui-font-size) + 4px)` | `calc(var(--ui-font-size) + 4px)` | `calc(var(--ui-font-size) + 4px)` | L |
| `--text-ui-xs` | `calc(var(--ui-font-size) - 4px)`* | `calc(var(--ui-font-size) - 4px)` | `calc(var(--ui-font-size) - 4px)` | `calc(var(--ui-font-size) - 4px)` | L |
| `--text-xl` | `1.25rem`* | `1.25rem` | `1.25rem` | `1.25rem` | L |
| `--text-xl--line-height` | `calc(1.75 / 1.25)`* | `calc(1.75 / 1.25)` | `calc(1.75 / 1.25)` | `calc(1.75 / 1.25)` | L |
| `--text-xs` | `.75rem`* | `.75rem` | `.75rem` | `.75rem` | L |
| `--text-xs--line-height` | `calc(1 / .75)`* | `calc(1 / .75)` | `calc(1 / .75)` | `calc(1 / .75)` | L |
| `--tracking-normal` | `0em`* | `0em` | `0em` | `0em` | L |
| `--tracking-tight` | `-.025em`* | `-.025em` | `-.025em` | `-.025em` | L |
| `--tracking-wf-label` | `.09em`* | `.09em` | `.09em` | `.09em` | L |
| `--tracking-wide` | `.025em`* | `.025em` | `.025em` | `.025em` | L |
| `--tracking-widest` | `.1em`* | `.1em` | `.1em` | `.1em` | L |
| `--ui-font-size` | `14px`* | `14px` | `14px` | `14px` | L |
| `--zcode-scrollbar-thumb` | `transparent`* | `transparent` | `transparent` | `transparent` | L |

> 说明：`--lightningcss-*` 是构建器内部开关，`--animated-gradient-text-*` 是流式文字渐变，`--highspeed-*` 是分享卡/高速模式，`--wf-*` 是工作流动画，`--zcode-scrollbar-thumb` 是滚动条状态。`--shadow-composer-soft` 与 `--ui-font-size` 来自无层级 `:root`（`css:417968`），其余来自 `@layer theme`。**confirmed**。

### 10.1 组件级/局部自定义属性（不在主题层）

| 属性 | 值 | 选择器 | 证据 | 置信度 |
|---|---|---|---|---|
| `--markdown-table-layout-left-inset` | 16px（默认）/ 8px（`max-md`）/ 48px（≥864px 容器） | 对话滚动容器 | `js` + 类名 | confirmed |
| `--markdown-table-layout-right-inset` | 16px / 8px | 同上 | 同上 | confirmed |
| `--attachment-bg` | `var(--color-background)` / `var(--color-surface)` | 附件卡 | `@layer utilities` arbitrary 类 | confirmed |
| `--bg` | `linear-gradient(90deg,#0000 calc(50% - var(--spread)),var(--color-background),#0000 calc(50% + var(--spread)))` | 扫光 | 同上 | confirmed |
| `--media-close-surface` | `#00000080` / hover `#000000a6` / 前景 `#fff` | `.cloud-content-dialog` | `css` | confirmed |
| `--share-rail-max-width` | `56rem` / `72rem` | `[data-share-content-rail=true]` | `css` | confirmed |
| `--share-rail-padding` | `1rem` / `2rem` | 同上 | `css` | confirmed |
| `--tab-fade-offset` | `0px` / `1.25rem` | tab 渐隐 | `css` | confirmed |
| `--composer-thought-slider-thumb` | `var(--color-white)` | 思考强度滑块 | `css` | confirmed |
| `--composer-thought-max-accent` | `var(--color-blue-500)` | 同上 | `css` | confirmed |
| `--highspeed-model-sweep-width` | `96px` | 模型名扫光 | `css` | confirmed |
| `--loading-pixel-peak` | `1`（暗色） | 加载像素 | `css` | confirmed |
| `--wf-face-body` / `--wf-face-eye` / `--wf-face-x/y` | `#54b9a6` / `#fff` / `0px` | 工作流表情 | `css` | confirmed |
| `--workspace-panel-radius` | **未定义**（只在 `rounded-[var(--workspace-panel-radius)]` 里被引用） | 工作区面板 | grep 无定义 | unresolved |

### 10.2 `@property` 注册（95 个）

全部是 Tailwind 内部机制属性（`--tw-*`）+ 1 个业务属性：

| 属性 | 注册 | 置信度 |
|---|---|---|
| `--zcode-scrollbar-thumb` | `syntax:"<color>"; inherits:true; initial-value:#0000` | confirmed |
| `--tw-ring-offset-color` | `initial-value:#fff` | confirmed |
| `--tw-ring-offset-width` | `initial-value:0`（`<length>`） | confirmed |
| `--tw-shadow` / `--tw-inset-shadow` / `--tw-ring-shadow` / `--tw-inset-ring-shadow` | `initial-value:0 0 #0000` | confirmed |
| `--tw-border-style` | `initial-value:solid` | confirmed |
| `--tw-content` | `initial-value:""` | confirmed |
| `--tw-duration` / `--tw-ease` / `--tw-leading` / `--tw-font-weight` / `--tw-tracking` | 无初值（`syntax:"*"`） | confirmed |
| 其余 `--tw-translate/scale/rotate/skew/pan/gradient/blur/backdrop-*/…` | 见 `artifacts/tokens.reference.css` 附录 | confirmed |

---

## 11. 未解决项（unresolved）

1. **`--color-ring` 未定义**，但被 3 处 `outline: 2px solid var(--color-ring)` 引用（`.composer-thought-slider:has(input:focus-visible)`、`.wf-tile:has(.wf-tile-hit:focus-visible)`）。同时 `.focus-visible\:outline-ring` / `.ring-ring` 在产物 CSS 里根本没有生成（JS 里有 41 处 `focus-visible:ring-ring` 类名）。判断：源码 token 名与构建产物脱节，Web 端焦点样式实际不可见。**unresolved**（无法从产物确定设计意图色；最接近的是 `--color-brand` 或 `--color-input-border-focused`）。
2. **`--workspace-panel-radius` 未定义**。只能在 Compose 里取 `--radius-*` 的合理值（推测 12–16px）。**unresolved**。
3. **`--shadow-composer-soft` 在默认亮/暗为 `0 0 #0000`**，只有 ZAI 亮有真实阴影；ZAI 暗也没有。这是“默认主题无 Composer 阴影”还是有漏声明，无法从产物判定。**unresolved**（按字面实现：默认无阴影）。
4. **`--color-subagent-node` 暗色是 `oklab(77.6963% -.0361366 .0873579/.18)` 字面量**（不是 `color-mix`），换算 `#B7BD752E`；与 `--color-subagent-node-foreground: #B7BD75` 同色相。**confirmed**（值本身）/ 语义命名一致性 **inferred**。
5. **`--color-git-added` 与 `--color-diff-added` 在默认亮分别用 teal-600 / green-600**（`#0D9488` vs `#00A63E`），ZAI 皮肤里又都变成同一个绿 `#1E8A3E`。哪个才是“git 新增”的正式色，产物无注释。**unresolved**（按 token 名分别实现）。
6. **`::selection` 无自定义**：Compose 若要与 Web 完全一致，应使用平台默认选中色；若产品要求品牌色，属于新增设计，不是 1:1。**confirmed**（缺失）。
7. **`.max-w-6xl` 基类不存在**，只有 `.xl:max-w-6xl`（`css`）与 `@min-[1280px]/conversation:max-w-6xl`（`@container conversation`）两个变体；JS 里 3 处 `max-w-6xl` 全部带前缀，因此实际布局无影响。真正不存在的是 `max-w-7xl` / `--container-7xl`。**confirmed**。
8. **`--ui-font-size` 的上限 20px**：`ac()` 夹 12–20，但 20px 时 `text-ui-settings-title` = 30px、`settings-title-lg` = 36px，是否仍符合设计意图无证据。**inferred**。

---

## 12. 与既有文档的差异（3.15.3 → 3.17.1）

| 项 | `docs/chat/07-css-tokens.md` / `docs/audit/02-layout-tokens.md`（3.15.3） | 3.17.1 实测 | 处理 |
|---|---|---|---|
| 主题默认 | 以 `:root` / `.dark` 描述，`--color-background` 亮 `#FAFAFA`、暗 `#171717` | 出厂默认 `zai-dark`：亮 `#F8F8F8`、暗 `#161616`；`:root`/`.dark` 仍在但只是基础层 | **本文以 ZAI 两套为“默认”**，同时保留 `:root`/`.dark` 列 |
| 品牌色 | 隐含 sky 系 | 默认 `:root`/`.dark` = sky（`#00BCFF`/`#00A6F4`）；ZAI = 纯黑/纯白 | 分开列 |
| 间距/圆角/阴影刻度 | 4px 基准、2/4/6/8/12/16/24、shadow 3 变量 | 完全一致（复核） | 沿用并补 hex |
| 焦点环 | 未单列全局 reset | 新增确认：`:focus,:focus-visible{box-shadow:none!important;outline:none!important}`（`css:418601`） | 本文新增 |
| 滚动条 | 未覆盖 | 14px、thumb 3px 透明边、面板按需显形 | 本文新增 |
| 对话列宽 | 864/1280 两级、`max-w-4xl/6xl`、`translate-x-42` | 一致（`js:3744368`、`@container conversation`） | 沿用 |
| `text-ui-*` | 14px 基准、9/10/12/13/16/18 | 一致，另确认 `settings-title` 24/30 与 `--ui-font-size` 12–20 运行时夹取 | 补全 |

> 结论：3.15.3 的两份文档在间距/圆角/对话布局上仍然准确；**颜色层需要换成 ZAI 皮肤口径**，否则 Compose 的暗色会比 Web 亮一档（`#171717` vs `#161616`）、亮色偏灰（`#FAFAFA` vs `#F8F8F8`），且品牌色会错误地保留蓝色。

---

## 附录 A — 间距乘数全量（CSS 实际出现）

```
0, .5, .75, 1, 1.25, 1.5, 2, 2.5, 3, 3.25, 3.5, 4, 4.5, 5, 6, 6.5, 7, 7.5, 8, 8.5,
9, 10, 11, 12, 13, 14, 15, 16, 18, 20, 24, 25, 28, 29, 32, 36, 38, 39, 40, 44, 48,
50, 52, 56, 58, 60, 64, 66, 72, 80, 84, 90, 96, 100, 128, 168, 3000, 3001
```

负值以 `-` 前缀出现（`-0.5…-42`），用于负 margin/translate。`168` = `translate-x-42`；`3000/3001` = 离屏（12000/12004px）。**confirmed**。

## 附录 B — 复现方法

产物是单行压缩 CSS，直接 `rg` 即可复核本文 90% 的结论：

```bash
CSS=Reference_web/3.17.1/assets/index-BgVjt5KE.css
JS=Reference_web/3.17.1/assets/index-Dwhx77cK.js

# 1) 所有自定义属性（去重）
rg -o -- '--[a-zA-Z0-9-]+:[^;}]*' "$CSS" | sort -u

# 2) 主题块规模与顺序
rg -o '\.dark\{' "$CSS" | wc -l                       # 91
python - <<'PY'                                        # 各主题块首字符偏移
s=open("Reference_web/3.17.1/assets/index-BgVjt5KE.css",encoding="utf-8").read()
for sel in (".dark{",".theme-zai-light{",".theme-zai-dark{"):
    print(sel, [i for i in range(len(s)) if s.startswith(sel,i)][:3])
PY

# 3) 断点与容器查询
rg -o '@media \(width>=[^)]+\)' "$CSS" | sort | uniq -c
rg -o '@container [a-z-]+ \(width>=[^)]+\)' "$CSS" | sort | uniq -c

# 4) 运行时主题类名与默认值
rg -o "theme-zai-(light|dark)" "$JS" | sort | uniq -c
rg -o "DEFAULT_THEME = \"[a-z-]+\"" Reference_web/3.17.1/index.html
```

`oklch()/oklab()/color-mix()` → sRGB 的换算按 CSS Color 4/5 公式实现（预乘 alpha、sRGB 传递函数、half-up 8bit 量化）；校验点：`oklch(98.5% 0 0)=#fafafa`、`oklch(63.7% .237 25.331)=#fb2c36`（Tailwind v4 red-500 官方值）。**confirmed**。

> 本文所有 hex 都可按上述公式从 `artifacts/tokens.reference.css` 的原始表达式重算。
