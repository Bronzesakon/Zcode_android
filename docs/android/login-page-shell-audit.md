# 登录页 × 安卓壳 —— 导航 / 状态栏取色 / 注入路径假设 审计

> **性质**：只读审计，本次**未修改任何代码**（唯一的写动作是本报告文件的创建）。
> **代码基线**：`E:\Zcode_android\android`，工作树干净，HEAD `ad08cba`（本次逐文件读取的
> 内容即该工作树现状）。全部 `文件:行:列` 均为本次读取的实际坐标；列按 UTF-8 字符列计。
> **背景**：最新版网页新增 bigmodel / zai 登录页、新布局与新配色，状态栏取色色值疑似变化。
> **凭据红线**：本报告不含任何 sid / hash / mid / token 原文；涉及 URL 一律只到 host + path。

---

## 0. 结论速览

| # | 问题 | 现行表现（代码实证） | 证据 | 性质 |
|---|---|---|---|---|
| 1 | 登录页导航 | `chat.z.ai`、`bigmodel.cn` **都在白名单内 → 留在本 WebView**；白名单外的域一律丢给系统浏览器（登录 nonce 会丢） | `MainActivity.kt:307:21`、`RemoteUrl.kt:37:5`、`MainActivity.kt:317:42` | 已验证 |
| 2 | 登录页上的状态栏色 | 登录页**永远不会上报**状态/主题 → 条带**永久停在 boot-shell 固定色**（浅 `#FAFAFA` / 深 `#171717`） | `MainActivity.kt:339:17`、`676-697`、`PageBarColor.kt:85:5` | 已验证；"缝是否可见"需真机 |
| 3 | 登录页上的状态栏图标 | `pageTheme=null` 且系统深色 → **深色图标压在 `#171717` 条带上**（不可见） | `MainActivity.kt:692:50`、`695:54`、`PageBarColor.kt:123:5` | 代码实证；真机未验证 |
| 4 | 注入层 | document-start 装在**整个 `*.z.ai`** 上，但脚本首行有 `/remote` 路径闸 → 非 `/remote` 页**整体 no-op**；`bigmodel.cn` 连装都不装 | `MainActivity.kt:505:17`、`519:25`、`545:9` | 已验证 |
| 5 | 启动与持久化 | 冷启动直进；持久化值**只**由 `applyUrl()` 写；页面自己 `location.replace()` 改 URL **不回写**；**无**"URL 变化就回退"逻辑 | `MainActivity.kt:240-247`、`560:9`；全仓 grep 见 §2 | 已验证 |
| 6 | 会话 / 配对 / 通知 | `relayCreds` 常驻内存**无清理点**；任务库只在远程页 `ready` 时复位 → 登录页上**陈旧"运行中"通知与死凭证都留着** | `ShellRuntime.kt:1919:5`、`2252:21`、`2287:17`、`2591-2595` | 已验证 |

**一句话**：壳**不会**拒绝登录页，也**不会**在登录页上误动作（注入整体 no-op、桥已摘除）；
它真正的问题只有两个半——**状态栏条带停在 boot-shell 色**、**深色下图标与条带同色**，
以及**登录页之后原生侧的陈旧状态（relaycreds / 运行态通知）没人清**。

---

## 1. URL 导航处理

### 1.1 `shouldOverrideUrlLoading` 的四个分支

`MainActivity.kt:301:13` 起，主帧导航按下面顺序判定：

| 分支 | 判据 | 动作 | 证据 |
|---|---|---|---|
| 非主帧 | `!request.isForMainFrame` | 放行（子帧不干预） | `MainActivity.kt:305:17` |
| 受信域 | `RemoteUrl.isTrustedNavigation(uri)` | **留在本 WebView**（返回 false），并按"是不是远程页"对齐桥的挂载 | `MainActivity.kt:307:21`、`311:21` |
| 其它 | 兜底 | `Intent.ACTION_VIEW` 交给系统浏览器，返回 true；`ActivityNotFoundException` 也返回 true（静默） | `MainActivity.kt:315:9`、`317:42`、`319-321` |
| 服务端 301/302 | 不经本回调 | 由 `onPageStarted` 按**落地文档的 URL** 再对齐一次桥 | `MainActivity.kt:330-332` |

最后一行是登录页的关键兜底：代码注释自己写明"服务端重定向未必经过
`shouldOverrideUrlLoading`"，所以 `/remote/v4` → 登录页的 302 链路即使跳过本回调，
桥的摘除仍会在新文档起步时补上（`332:17`）。

### 1.2 两套域名规则，语义不同（易混）

| 函数 | 规则 | 谁在用 |
|---|---|---|
| `isAllowed` | `https` + host 必须 `z.ai` 或 `*.z.ai` + **path 必须以 `/remote` 开头** | ① 扫码/粘贴/手动输入的 URL 校验；② 桥挂载；③ 补注注入闸 | `RemoteUrl.kt:52-59`、`56:9`、`58:9`、`23:5` |
| `isTrustedNavigation` | 只要 `https` + host 属于 `["z.ai", "bigmodel.cn"]`（含子域），**不看 path** | `shouldOverrideUrlLoading` 的留/外放判据 | `RemoteUrl.kt:69:5`、`37:5`、`72:13` |

**扫码校验会拒什么**：host 不在 `*.z.ai` 下的新域名一律 `normalize()==null` →
`error_bad_url`「链接无效，请确认是 zcode.z.ai 的远程控制链接」（`RemoteUrl.kt:40-50`、
`strings.xml:18`）。**新路径不受限**：只要以 `/remote` 开头就收（`/remote/v5`、`/remote__test__`
都过）；反向地，`/remote-x` 这类同前缀异路径也会被放行——`startsWith` 没有边界判断（`58:9`）。

对照仓内文档：3.16.1 规格里登录页正是
`https://chat.z.ai/api/oauth/authorize`（zai）与 `https://bigmodel.cn/login`（bigmodel）
（`docs/auth-3.16.1/AUTH-FLOW-FINAL.md:115`、`:116`），回调只认 pathname `/remote/v4` 或
`/remote/v4/` 且**不校验 host**（同文件 `:148`），登录 nonce 存 `sessionStorage`、回调做
**字符串全等**校验，不匹配即报「OAuth CSRF 检测失败」（同文件 `:140-142`）。
这两个 host **已在**白名单里。

### 1.3 四种落点的实际表现

| 登录页落点 | 是否留壳内 | 桥 | 注入 | 状态栏 |
|---|---|---|---|---|
| `chat.z.ai/api/oauth/authorize`（zai） | ✅ 是（`*.z.ai`） | 摘除（`isAllowed` path 非 `/remote`） | no-op（路径闸） | 停 boot-shell 色 |
| `bigmodel.cn/login`（bigmodel） | ✅ 是（根域） | 摘除 | **连 document-start 都没装**（规则只覆盖 `*.z.ai`） | 停 boot-shell 色 |
| `zcode.z.ai/web-remote/callback` | ✅ 是 | 摘除 | no-op | 同上 |
| 白名单外的域（含第三方登录 provider、任何新域名） | ❌ 否 → `ACTION_VIEW` | 不变（留在旧文档） | 旧文档继续 | 停旧文档的最后一个状态 |

（回调落点两家说法不一：壳自己的注释与 `docs/WEBVIEW-SHELL.md:83` 写
`zcode.z.ai/web-remote/callback`，3.16.1 规格说回调就在 `/remote/v4` 上
（`AUTH-FLOW-FINAL.md:148`）。两种落点壳的行为都落在上表第 3/1 行——**不影响本次结论**。）

第四行是**唯一会让"壳内登录"彻底失败**的形态：登录页一旦离开 WebView，
`sessionStorage` 里的 pending nonce 就不在同一个存储上下文里，
回调回到壳内时必然 nonce 全等校验失败（文档结论，见上引）。

**登录完成的回跳**（文档实证）：回调页成功后 `location.replace(app_return_to ?? '/remote/v4')`，
且回跳 URL 会把 `app_version,sid,mid,name` 从**当前 href** 复制过去（`AUTH-FLOW-FINAL.md:153`、
`:137-138`）。因此回跳后 host 仍是 `zcode.z.ai`、path 仍是 `/remote/v4` → 壳把桥重新挂上、
document-start 重新注入，链路按正常远程页恢复。**这条契约不需要壳做任何事**。

### 1.4 壳**没有**深链入口

`AndroidManifest.xml:62-65` 的 intent-filter 只有 `MAIN` / `LAUNCHER`，**没有** `data` 规则。
所以：URL 只能来自扫码 / 粘贴 / 手动输入 / 已存偏好（`MainActivity.kt:125-137`、`616-647`）。
若登录页被丢到系统浏览器里，用户即使在那儿登录成功，也**没有 URL 通路把结果交回壳**。

---

## 2. 启动与持久化

- **存哪儿**：`Prefs.remoteUrl`（键 `remote_url`），与其它诊断位共用 `zcode_remote` 偏好文件；
  文件被 `data_extraction_rules` 排除在云备份/设备迁移之外（`Prefs.kt:19:5`、`:123`、类注释）。
- **冷启动是否直进**：是。`onCreate` 里只要 `intent` 不特别指定且已存 URL，就直接
  `applyUrl(stored)` 加载，不弹接入页（`MainActivity.kt:240-247`）；`showSetup()` 只在
  **没有任何已存 URL**时出现（`:242-243`）。
- **唯一写点**：`applyUrl()` 里的 `prefs.remoteUrl = url`（`MainActivity.kt:560:9`），
  调用者只有扫码结果、粘贴、手动输入三处（`:135`、`:624`、`:643`）。
- **页面自己改 URL（含 `location.replace()`、`history.pushState`、query 变化）**：
  壳**不会**跟着更新持久化值。全仓读点只有 `MainActivity.kt:241 / 572 / 595 / 806 / 812`、
  `SettingsActivity.kt:71`（设置页展示）、`ShellRuntime.kt:1614`（原生网络自检取 host）；
  **没有** `doUpdateVisitedHistory`、没有 `webView.url` 比较、没有任何"URL 变了就回退/重载"逻辑
  （grep 结论，见附录 B）。
- **两条重载路径要分清**（登录页场景会用到）：
  - `reloadPage()` 用**已存 URL**（`MainActivity.kt:572-576`）——扫码重配、`ACTION_RELOAD`、
    加载失败面板的"重新加载"走这条；
  - `setPageReloader{}` 里的 `binding.webview.reload()` 重载**当前 URL**（`MainActivity.kt:213:17`）
    ——快速交接/回前台那条路走这条。若当前停的是登录页，它就重载登录页。
- **已知后果（与登录无关但同源）**：页面在 `pathname === '/remote/v4'` 且 `app_version` 与
  `desktopAppVersion` 不一致时会整页 `location.replace` 成新 URL（3.17.1 版本闸门，
  见 `docs/android/3.17.1-anchor-audit.md:226` 及 §4.1）。壳不回写 URL，所以**每次重载都会
  再触发一次这次整文档导航**。登录成功后回跳 URL 也带 `app_version`，同一问题沿用。

---

## 3. 状态栏与主题

### 3.1 取色机制：既不是 `meta theme-color`，也不是 CSS 变量读取，更不是像素采样

现行的三段式是**"注入层报名字 + 原生查固定表"**：

1. 注入层算两个**名字**：`state ∈ {boot-shell, boot, main-header, main-surface}` 与
   `theme ∈ {light, dark}`，经 `post('pagestate', …)` 交给原生（契约注释 `inject.js:3207-3213`，
   实际发送点 `3343-3345`）。
2. 原生查表：`PageBarColor.stateOf/themeOf/resolve`（`PageBarColor.kt:28-52`、`67:23`、`71:23`、
   `99-104`）。
3. Activity 应用：`applyStatusBarSurface()`（`MainActivity.kt:676-697`）。

**契约明文**："the shell never reads a colour out of the page at runtime"（`MainActivity.kt:649-656`；
`PageBarColor.kt:11-26`）。全仓 grep `theme-color` **零命中**，状态栏这条链上没有任何
"读页面颜色"的动作。⚠️ 但"不读页面颜色"**不是全仓铁律**：`inject.js:3737-3740` 就
`getComputedStyle(scroller).getPropertyValue('--color-border')` 拿页面令牌去刷自绘滚动条
指示条的滑块底色——那是壳自己画的悬浮条，与状态栏无关，但它证明"读一个 CSS 变量"
在本仓已有先例（若 M1 选"读 CSS 变量"方案，可援引此例，见 §6.1）。

### 3.2 名字是怎么来的（数据源）

| 维度 | 判据 | 证据 |
|---|---|---|
| 状态 | `.zcode-boot-loading` 存在 → `boot`/`boot-shell`；否则 `.bg-background-win-alt` 不存在 → `boot`/`boot-shell`；存在则看窄/宽断点 → `main-header`/`main-surface` | `inject.js:3271-3287`、`3276:13`、`3277:13`、`3278:13` |
| 主题 | `html[data-zcode-browser-theme-surface]`（`light`/`dark`）→ 其次 `html.style.colorScheme` → 最后 `matchMedia('(prefers-color-scheme: dark)')` | `inject.js:3289-3311`、`3292:13`、`3310:9` |
| 单独一段 boot 的切点 | `<html>` 上的 `theme-zai-*` 类 | `inject.js:3261-3269`、`3279-3281` |
| 断点 | `NARROW_QUERY = '(max-width: 767px)'` | `inject.js:3244:5` |
| 重报时机 | `MutationObserver`（`class`/`style`/`data-zcode-browser-theme-surface`）+ 两个 media query 监听 + `onPageStarted` 强制回落 + `onResume` 主动索要 | `inject.js:3437-3458`、`MainActivity.kt:339:17`、`723-725` |
| 明暗谁说了算 | **页面报告的优先，系统夜间模式只作兜底** | `PageBarColor.kt:24-26`、`MainActivity.kt:677:9` |

### 3.3 应用点与"写死色值"清单

| 位置 | 值 | 证据 |
|---|---|---|
| boot-shell（未挂主题类的预渲染壳） | 浅 `#FAFAFA` / 深 `#171717` | `PageBarColor.kt:85:5`、`86` |
| boot（已挂主题类 + KICKED/接管/配对等待等状态页） | 浅 `#F8F8F8` / 深 `#161616` | `PageBarColor.kt:87`、`88` |
| main-header（窄布局，页面自己的标题栏在条带下） | 浅 `#FFFFFF` / 深 `#202020` | `PageBarColor.kt:89`、`90` |
| main-surface（宽布局，壳区在条带下） | 浅 `#ECECEE` / 深 `#2B2B2B` | `PageBarColor.kt:91`、`92` |
| 主界面根布局默认底色（条带就是它的背景） | `#FFF8F8F8` | `activity_main.xml:16`（id）、`:19`（色值） |
| 交接 logo 罩底色（默认值；运行时被覆盖为 boot 色） | `#FFF8F8F8`；`handoffCover.setBackgroundColor(resolve(BOOT, dark))` | `activity_main.xml:30`（id）、`:33`（色值）、`MainActivity.kt:680:9` |
| 交接 logo 圆角方块渐变/描边 | `#000000` → `#151718`，描边 `#1AFFFFFF` | `drawable/bg_handoff_logo.xml` |
| 设置页（纯原生页）条带 | 浅 `#F7F7F7` / 深 `#000000`（`miuix_surface`），图标跟系统 | `SettingsActivity.kt:62-67`、`values/miuix_colors.xml:26`、`values-night/miuix_colors.xml:12` |
| 系统栏本身 | 一律透明（`SystemBarStyle.*(Color.TRANSPARENT)`）；`themes.xml` 明确**不设** `statusBarColor`/`navigationBarColor` | `WindowInsets.kt:33-38`、`themes.xml:13-16` |

条带几何：根视图顶部 padding = 状态栏 + 挖孔内缩，底部**故意不消费**（`WindowInsets.kt:76-86`、
`82`）。所以"条带色"就是 `binding.root.setBackgroundColor(color)`（`MainActivity.kt:684:9`），
WebView 自己也刷同色以消灭首绘前白缝（`690:9`）。

### 3.4 图标明暗的判定——**这里有一个既有 bug，登录页会把它变成常态**

```kotlin
// MainActivity.kt:677  ← 颜色用的是带系统兜底的 dark
val dark = pageTheme?.let { it == PageTheme.DARK } ?: isSystemDark()
// MainActivity.kt:692 / 695  ← 图标却用未解析的 pageTheme
controller.isAppearanceLightStatusBars = PageBarColor.appearanceLightStatusBars(pageTheme)
// PageBarColor.kt:123  ← theme == null 时返回 true（"浅色表面 → 画深色图标"）
fun appearanceLightStatusBars(theme: PageTheme?): Boolean = theme != PageTheme.DARK
```

**结论**：页面**还没报主题**（`pageTheme == null`）时，颜色按系统夜景取（深色 → `#171717`），
图标却按"页面说浅色"取 → **深色图标画在近黑条带上**。远程页上这个窗口只有几十毫秒
（document-start 注入后 `schedulePageState()` 立刻首报，`inject.js:3458`，且主题判据有
`prefers-color-scheme` 兜底 → 首报就跟系统一致）；**登录页没有注入，`pageTheme` 永远是 null
→ 系统深色下这个"深图标压深底"是**持续**状态**。冷启动时 `onCreate` 也会先走一次同色不同图的
分支（`MainActivity.kt:191`）。

### 3.5 登录页上的具体表现

1. 新文档起步时 `onPageStarted` 强制 `BOOT_SHELL + null`（`MainActivity.kt:339:17`）——
   这一行的用意是"新文档从第一段 boot 段重新开始"，对登录页同样生效。
2. 之后**不会再有第二次上报**（登录页没有注入层，`pagestate` 无人发）。
3. 于是条带 = `resolve(BOOT_SHELL, 系统夜景)` = 浅 `#FAFAFA` / 深 `#171717`；
   而登录页（bigmodel / zai 新配色）的顶面若是纯白、品牌色或另一档灰，就会在状态栏下
   看出一条**恒定接缝**；深色下再叠加 §3.4 的图标问题。
4. 同理，`NARROW_QUERY`（`inject.js:3244:5`）与新页面已收窄的断点
   `(max-width: 767px) and (hover: none) and (pointer: coarse)`（`3.17.1-anchor-audit.md:137`）
   不一致时，**远程页自身**也可能把 `main-header`/`main-surface` 取反（折叠屏接鼠标、桌面模式）。
5. **最需要真机核对的一条**：`PageBarColor.kt:85-92` 这八个字面量是按 **2026-09-11 的
   stylesheet 快照**抄的（类注释 `:15-16`）。"最新版新配色"如果动了
   `--color-background` / 头部 surface / `.bg-background-win-alt` 的值，那么**不只是登录页**，
   `/remote/v4` 全站的条带都会出现 1–2px 接缝——这是"状态栏取色色值也有变化"的最直接解释。

---

## 4. 注入层的路径假设

### 4.1 三道闸（决定"注入在登录页上到底跑不跑"）

| 闸 | 位置 | 内容 | 对登录页的效果 |
|---|---|---|---|
| ① document-start 规则集 | `MainActivity.kt:519:25` | `https://z.ai`、`https://*.z.ai` | `chat.z.ai` **会被装上**（子域匹配）；`bigmodel.cn` **不会** |
| ② 脚本首行包裹器 | `MainActivity.kt:505:17` | `if(location.pathname.indexOf('/remote')!==0)return;` | 非 `/remote` 开头的路径 → **整个脚本体直接返回** |
| ③ 加载期补注 | `MainActivity.kt:545:9`、`541-548` | 只在 `RemoteUrl.isAllowed(Uri.parse(url))` 时补注 | 登录页/回调页 **不补注** |

注入在**文档早期**就装（`installInjection()` 先于 `loadUrl`，`MainActivity.kt:566-568`），
且三道闸互相独立；任何一道命中都不会有第二次机会（`ensureInjectionScript` 里脚本内容幂等，
`:496-508`）。桥是第四件独立的事：`setBridgeAttached(RemoteUrl.isAllowed(…))`
（`:311:21`、`:332:17`）→ 登录页上 **`window.ZCodeShell` 已被移除**（`531-539`）。

### 4.2 各条逻辑在登录页上的走向

| 逻辑 | 位置 | 对 `/remote/v4` 路径的假设 | 在"非 `/remote` 页面"上 |
|---|---|---|---|
| 网络自检 `__zcodeShellNetProbe` | `inject.js:2670-2691`、默认目标写死 `location.origin + '/remote/v4'`（`2673:13`） | 硬编码 `/remote/v4` | **no-op**（整套注入没跑） |
| `__zcodeShellNudgeRecover` | `inject.js:2621:5`、`2604-2616` | 合成 `online`/`pageshow`、必要时关页面 socket | no-op（原生调用点还有 `&&` 守卫：`ShellRuntime.kt:1662`） |
| `pinChatRoute()` | `inject.js:232:5`、document-start 调用点 `246:5`（条件 `config().restoreChat === true`，配置来自 `ShellRuntime.kt:1867:5`） | 把 `history.state.zcodeMobilePage='chat'` 钉上，让页面启动即回对话页 | no-op |
| 日志沉降（`window.zcode.log`） | `inject.js:91-137`；可见性劫持已是**纯日志**（`283-285`） | 供给页面日志出口 | no-op |
| 凭证移交 `maybePostRelayCreds` | `inject.js:303-327`、从 `location.href` 取 `sid`/`hash`/`mid`（`309:13`） | 需要页面建 relay socket 且有 URL 凭证 | no-op |
| KICKED 处理 / 自愈 | `inject.js:1079`、`1341-1377`、`KICKED_HEAL_CAP=2`（`:1188`） | 页面 socket 上的 `error` 帧 + 回前台 `reload()` | no-op（没有 hook 就没有帧） |
| 滚动条归零 CSS / 自绘指示条 | 安装点 `inject.js:3986-3987`、节头 `3468-3488` | 假设页面自带 `::-webkit-scrollbar{width:14px}` | no-op |
| 对话页刷新按钮 | `inject.js:3911-3981`、锚点 `section[data-mobile-page="chat"]`（`3917:13`） | 注入到对话页头部主题按钮左侧 | no-op |
| 卡死看门狗 / 5b 铁判准 / 体征上报 | `inject.js:2184-2200`、`3374-3397` | DOM/页面日志/RPC 信标 | no-op |
| `post('ready')` | `inject.js:4005:5` | 首个桥消息 | no-op → 原生**永远等不到 ready** |

**结论（已验证）**：只要登录页路径不以 `/remote` 开头，注入层**整体 no-op**——
不误动作、不碰 DOM、不发业务消息；`bigmodel.cn` 更是连脚本都没装。
**唯一残余**是对 `/remote` 前缀**没有边界判断**（与 `RemoteUrl.kt:58:9` 同病）：
`/remoteanything` 也会放行。

### 4.3 反向风险：如果新登录页落在 `/remote` 前缀下（**未验证，需真机确认 URL**）

若登录页实际路径是 `/remote/v4/login`、`/remote/login` 这类形式，则① 闸放行 + ③
`isAllowed` 也放行（host、path 双满足）→ **全套注入在登录页上跑起来**，后果不可忽略：

- `pinChatRoute()` 会在登录页的 `history.state` 上钉 `zcodeMobilePage:'chat'`（`inject.js:232-253`）
  ——页面自己的路由/渲染可能据此认为"已在对话页"；
- WebSocket hook 接管登录页的 `window.WebSocket`（`inject.js:329-359`）；
- 滚动条 CSS 归零 + 自绘指示条、刷新按钮注入（`3986`、`3911`）会作用到登录页布局；
- 桥**不会**被摘除（`isAllowed` 为真）→ 登录页能拿到 `ZCodeShell.postMessage/config`；
- 若登录页 URL 上带 `sid`/`hash`（例如带参回跳），且页面此时建了 socket，
  `maybePostRelayCreds` 会把凭证交给原生（`inject.js:303-327`）。

**判据**：这一条只需在真机上确认登录页的**完整 URL**（`Diagnostics` 日志里的
「网页开始加载」/「网页加载完成 · host+path」两行即可，`MainActivity.kt:329`、`389-394`）。
若确认是 `chat.z.ai/api/oauth/authorize` 或 `bigmodel.cn/login`，本节全部不成立。

---

## 5. 登录页出现后：会话 / 配对 / 通知链路

| 项 | 现状 | 证据 |
|---|---|---|
| relay 凭证 | 注入层移交后**只驻内存**（`NativeRelayClient` 的 `RelayCreds`），`ShellRuntime.relayCreds` **没有任何置空点**（全仓仅 1919 声明、2252 赋值、1534/1729/1874/1977/1995/2006 读取） | `ShellRuntime.kt:1919:5`、`2252:21` |
| 是否会被误用 | 会：退后台且"已知有在跑任务"时 `maybeStartHandoffCarrier` 用**这份旧凭证**起原生承载（触发条件 `userIsAway()` + `store.runningTaskRefs()` 非空） | `ShellRuntime.kt:1870-1878`、`1874:9`；`1595-1601`（`clearCarrierState()` 存在，但只清承载状态、**不碰 relayCreds**，且不在导航路径上） |
| 任务库 | `store.reset()` + 通知同步**只在远程页 `ready` 桥消息**里发生 → 登录页没有 ready ⇒ 上一份文档的"运行中"状态、在跑任务通知、前台服务的"正在运行 N 个任务"文案**全部保留为陈旧值** | `ShellRuntime.kt:2287:17`→`2591-2595`；`2534-2543`；`TaskStore.kt:798` |
| 心跳泵 | 每 10s 发 `window.__zcodeShellHeartbeat && …`，有 `&&` 守卫 → 登录页上纯 no-op、不报错、不触发重载 | `ShellRuntime.kt:1047-1053`、`1052:33` |
| 注入就绪位 | `onPageStarted` 复位（`2583-2588`）；登录页永不 ready ⇒ 通知定位 `tryLocate` 重试 40×600ms 后记一行「放弃定位」；adb 诊断指令队列同样打空 | `MainActivity.kt:855-885`、`858:9`；`InjectionReadiness.kt:45-50` |
| 交接 logo 罩 | 只在原生承载配对成功后出现，且 `beginReturn()` 后挂 15s 超时 → **不会**永久罩住登录页 | `ShellRuntime.kt:654-657`；`HandoffCoverState.kt:97-99` |
| 状态栏相关跨页 JS | `onResume`、`onPageCommitVisible` 的求值都带 `&& window.__zcodeShellReportPageState` 守卫 → 登录页上纯 no-op | `MainActivity.kt:723-725`、`358-360` |
| 返回键 | 有历史就 `goBack()`，接入页才 `finish()`，否则 `moveTaskToBack`（保 Activity/WebView） | `MainActivity.kt:735-748`、`737:63` |
| 通知权限申请 | 只在 `onPageFinished` 触发一次（与页面内容无关） | `MainActivity.kt:398`、`771-783` |

**净结论**：登录页不会"炸"，但原生侧有**两处陈旧状态没人清**——
① 死掉的 `relayCreds`（会被退后台的承载拨号复用，失败后只留日志）；
② 陈旧的运行态/通知（直到下一次在远程页拿到 `ready` 才复位）。
通知链路本身不会"误发"：注入层不上报就没有新数据源（`TaskStore` 只吃桥转发的帧）。

---

## 6. 结论清单

### 6.1 要支持"登录页也在壳内完成"，必须改的点

| # | 点 | 一句话理由 | 证据 |
|---|---|---|---|
| **M1** | **给登录页/非远程页一个状态栏色源** | 登录页无注入 ⇒ 永不上报 ⇒ 条带永久停在 boot-shell 色；登录页新配色一变就是恒定接缝 | `MainActivity.kt:339:17`、`676-697`；`PageBarColor.kt:28-52`、`85:5` |
| **M2** | **修图标明暗：用解析后的 `dark` 而不是 `pageTheme`** | `pageTheme==null` + 系统深色 ⇒ 深色图标压 `#171717`（登录页上这是常态，远程页上只是毫秒级窗口） | `MainActivity.kt:677:9` vs `692:50`、`695:54`；`PageBarColor.kt:123:5` |
| **M3** | **核对并补全"留壳内"的域名白名单** | 白名单只有 `z.ai` / `bigmodel.cn` 两棵；新登录链路的任何中间域（尤其第三方 provider）不在其中就会被丢给系统浏览器，`sessionStorage` nonce 随之失效 | `RemoteUrl.kt:37:5`、`69-73`；`MainActivity.kt:307:21`、`317:42` |
| **M4** | **确认登录页 URL 是否落在 `/remote` 前缀下；是则把注入闸收窄成"精确远程页白名单"** | 前缀闸（`indexOf('/remote')!==0`）与 `isAllowed`（`startsWith('/remote')`）都没边界：`/remote/v4/login` 之类会被当成远程页 → `pinChatRoute` 会往登录页钉 `history.state`、WebSocket 被 hook、滚动条/刷新按钮注入、桥也不摘 | `MainActivity.kt:505:17`、`545:9`；`RemoteUrl.kt:58:9`；`inject.js:232-253`、`329-359`、`3911`、`3986` |
| **M5** | **离开远程页时失效/清掉 `relayCreds`，并决定陈旧运行态是否一并复位** | 凭证常驻内存 → 退后台承载用死凭证拨号（无效承载 + 噪音）；运行态/通知只在 `ready` 复位 → 登录页期间保留陈旧"运行中" | `ShellRuntime.kt:1919:5`、`2252:21`、`1870-1878`、`2287:17`、`2591-2595` |
| **M6** | **拿真机给新配色取色，核对 `PageBarColor` 八个字面量（必要时补第 5、6 档状态）** | 色表是 2026-09-11 stylesheet 快照的拷贝，且契约禁止运行时读色；新 palette 一改就是全站（不只登录页）接缝 | `PageBarColor.kt:15-16`、`85-92` |
| **M7**（可选） | document-start 规则集补 `https://bigmodel.cn` / `https://*.bigmodel.cn` | 现在 `bigmodel.cn` 登录页**连脚本都不装**（规则只覆盖 `*.z.ai`）；若要在这页做日志/状态上报必须先覆盖 | `MainActivity.kt:519:25` |
| **M8**（可选，既有 P2） | `NARROW_QUERY` 对齐页面新断点 | 折叠屏接鼠标/桌面模式下壳与页面判据不一致 ⇒ `main-header`/`main-surface` 取错 | `inject.js:3244:5`；`3.17.1-anchor-audit.md:137`、`321` |

优先级建议：**M2 是纯 bug 修复（无论是否支持登录页都该修，成本最低）**；
M6 决定"登录页到底差多少色"；M1 是"登录页看起来不别扭"的必答题；
M4 只需先确认真机 URL 再决定动不动；M3 若第三方登录存在则升为 P0（否则登录根本走不完）。

### 6.2 不改也不会坏的点（验证过，别为登录页顺手动它们）

1. **桥的摘/挂已经正确**：登录页（含服务端 302 直达）都会摘掉 `ZCodeShell`，回远程页/回调页
   再挂回（`MainActivity.kt:311:21`、`332:17`、`531-539`）。登录页拿不到 JS 桥是**有意的安全面**。
2. **注入路径闸保证非 `/remote` 页面整体 no-op**（`MainActivity.kt:505:17`）：`pinChatRoute`、
   WebSocket hook、凭证移交、滚动条、刷新按钮、KICKED 自愈、看门狗**都不会在登录页跑**。
3. **所有跨页 JS 调用都有 `&&` 守卫**（`ShellRuntime.kt:1052:33`、`1662`；`MainActivity.kt:358-360`、
   `723-725`、`869-879`）→ 登录页上不抛错、不误判。
4. **定位与诊断会自然放弃**：`tryLocate` 40 次后放弃并记一行（`MainActivity.kt:858-866`）；
   `dispatchJsDiag` 走同一就绪位（`InjectionReadiness.kt`）。
5. **交接 logo 罩有 15s 超时**（`ShellRuntime.kt:654-657`；`HandoffCoverState.kt:97-99`）→ 不会卡住。
6. **持久化 URL 不会被登录页污染**：没有任何 URL 监听/回写逻辑（§2），所以"重载回原远程链接"
   这条退路始终有效；扫码/粘贴/手动输入/设置页"换个链接"也都还在
   （`MainActivity.kt:560:9`、`600-647`；`SettingsActivity.kt:75-78`）。
7. **冷启动直进不变**（`MainActivity.kt:240-247`）；没有新增/改动启动路径的必要。
8. **返回键语义无需改**：登录页有历史就回远程页，没历史就退到后台（`735-748`）。

### 6.3 已验证 / 未验证

**已验证（仓内代码或仓内文档实证）**

- 导航留/外放判据、两套域名规则、外链落系统浏览器、服务端重定向的桥兜底（§1.1–1.3）。
- 扫码校验对新域名拒绝、对 `/remote*` 新路径放行（§1.2）。
- 无 deep-link intent-filter（§1.4）。
- 持久化只有一个写点、无 URL 监听、无回退逻辑；两条重载路径的 URL 来源不同（§2）。
- 取色机制（名字 + 固定表）、名字来源、应用点、全部写死色值与资源坐标（§3.1–3.3）。
- 图标明暗判定与 `pageTheme == null` 的错配（§3.4）——代码可推，**现象强度**未实测。
- 三道注入闸、各条逻辑对 `/remote` 的路径假设、非 `/remote` 页面上的 no-op（§4.1–4.2）。
- `relayCreds` 无清理点、`store.reset()` 只由 `ready` 触发、泵与跨页调用的守卫（§5）。

**未验证（需真机 / 需最新版页面）**

1. **登录页的完整 URL**（host + path）：决定 M4 是否成立（`/remote` 前缀 vs 独立路径）。
   取法：`Diagnostics` 的「网页开始加载」与「网页加载完成 · host+path」两行。
2. **登录页顶面真实色值**（浅/深两套）：决定 M1 的缝有多明显、M6 要补哪一档色。
3. **新配色是否改了四个 surface**（`--color-background` / header / win-alt / KICKED 状态页）：
   决定"色值变化"是全站还是仅登录页（M6）。
4. **登录页是否仍用老锚点**：`.zcode-boot-loading`、`.bg-background-win-alt`、
   `data-zcode-browser-theme-surface`。若新布局改名 → **远程页自己**的状态栏也会退回 boot 档
   （`inject.js:3271-3311`）。
5. **第三方登录按钮的打开方式**：若走 `target=_blank` 或 `window.open`，壳既没有
   `setSupportMultipleWindows` 也没有 `onCreateWindow`（全仓 grep 无命中）→ 可能点不动；
   若走白名单外域 → 直接外放系统浏览器。两种都要真机点一次。
6. **登录页会不会自己做整文档跳转**（zai ↔ bigmodel 互跳、语言切换）：只影响色表分档与日志噪声。
7. 文档结论（未在本机实测）：登录 nonce 在 `sessionStorage`、外放系统浏览器必然
   `OAuth CSRF 检测失败`（`AUTH-FLOW-FINAL.md:140-142`）；登录页具体是
   `chat.z.ai/api/oauth/authorize` / `bigmodel.cn/login`（同文件 `:115-116`，**3.16.1 基线**）。

---

## 附录 A 本次未覆盖 / 明确假设

- 未做真机验证，未连设备、未取图、未抓包；所有"表现"均为代码路径推演。
- **假设登录页是"新文档"**（整页导航/`location.replace`）。若新登录页是 `/remote/v4` 内的
  **SPA 路由**（`history.pushState`），则 `WebViewClient` 回调**一次都不触发**：
  桥不会摘、注入不会重跑、`onPageStarted` 不会回落状态栏——此时 §1/§4/§5 的多条结论都要按
  "同一文档内换视图"重判（状态栏会沿用页面最后一次上报的状态，反而可能更接近正确）。
  这条最需要真机确认（区分办法同样是一行 `location.href` 的诊断日志）。
- 未评估 OAuth 在 WebView 里的 Cookie/三方 Cookie 策略、`mixedContentMode = NEVER_ALLOW`
  （`MainActivity.kt:267`）对登录页资源的潜在影响。

## 附录 B 证据索引（坐标复核用）

| 主题 | 坐标 |
|---|---|
| `shouldOverrideUrlLoading` 全分支 | `MainActivity.kt:301:13`–`322` |
| 桥挂载对齐（回调 + 落地文档两处） | `MainActivity.kt:311:21`、`332:17`、`531-539` |
| 外链 → 系统浏览器 | `MainActivity.kt:315:9`–`321` |
| 注入包裹器路径闸 | `MainActivity.kt:504-507`、`505:17` |
| document-start 规则集 | `MainActivity.kt:518-522`、`519:25` |
| 补注闸（`isAllowed`） | `MainActivity.kt:541-548`、`545:9` |
| 「不读页面颜色」契约 | `MainActivity.kt:649-656`；`PageBarColor.kt:11-26` |
| 状态栏应用点 | `MainActivity.kt:676-697`、`677:9`、`680:9`、`684:9`、`690:9`、`692:50`、`695:54` |
| 新文档强制 boot-shell | `MainActivity.kt:339:17` |
| 回前台索要状态 | `MainActivity.kt:713-726` |
| 持久化唯一写点 / 冷启动直进 | `MainActivity.kt:560:9`、`240-247` |
| 两条重载路径 | `MainActivity.kt:207-215`、`213:17`、`571-576` |
| 返回键 | `MainActivity.kt:734-749`、`737:63` |
| 域名规则 | `RemoteUrl.kt:22-23`、`37:5`、`40-59`、`56:9`、`58:9`、`69-73` |
| 偏好键 | `Prefs.kt:19:5`、`122-129` |
| 色表与图标规则 | `PageBarColor.kt:28-52`、`85:5`–`92`、`99-104`、`113-116`、`123:5` |
| 窗口 inset / 系统栏风格 | `WindowInsets.kt:26-39`、`33-38`、`76-86`、`82` |
| 布局写死色 | `activity_main.xml:16`/`:19`（root）、`:30`/`:33`（handoffCover）；`drawable/bg_handoff_logo.xml`；`themes.xml:13-16` |
| 设置页条带 | `SettingsActivity.kt:62-67`；`values/miuix_colors.xml:26`；`values-night/miuix_colors.xml:12` |
| 全仓唯一"读页面颜色"处 | `inject.js:3731-3743`、`3737:13`（自绘滚动条滑块取 `--color-border`） |
| 注入层状态/主题名字 | `inject.js:3207-3245`、`3261-3269`、`3271-3287`、`3289-3311`、`3316-3346`、`3437-3458`、`3462-3466` |
| 注入层路径假设逐条 | `inject.js:55:5`、`232:5`、`246:5`、`304:9`、`309:13`、`329-359`、`1079:17`、`1080:17`、`1188`、`1341-1377`、`2604-2616`、`2621:5`、`2673:13`、`3911-3981`、`3986-3987`、`4005:5` |
| 原生侧凭证/承载/状态复位 | `ShellRuntime.kt:1867:5`、`1874:9`、`1919:5`、`2252:21`、`2287:17`、`2534-2543`、`2583-2588`、`2591-2595`、`654-657`、`1047-1053`、`1052:33`、`1614-1622`、`1662` |
| 就绪位 | `InjectionReadiness.kt:26-51` |
| 交接罩状态机 | `HandoffCoverState.kt:41-73`、`97-99` |
| 任务库复位 | `TaskStore.kt:798-816` |
| 登录规格（3.16.1 文档，代理证据） | `docs/auth-3.16.1/AUTH-FLOW-FINAL.md:88-96`、`115-116`、`133-153`、`185-190` |
| 新断点 / 主题锚点（3.17.1 文档） | `docs/android/3.17.1-anchor-audit.md:83`、`137`、`201`、`203`、`226`、`321` |
