# 鸿蒙薄壳：登录页「应用内完成」现行实现参考

调查对象：`E:\Zcode_harmony`（Stage 模型 / ArkTS，包名 `com.zcode.control`）
调查性质：**只读取证**，未修改被调查项目的任何文件。
取证版本：`493fcd6 chore: remove android-shell, keep Harmony-only`（2026-11-04），工作区干净（`git status --short` 无输出）。
凭据约定：本报告只记录 URL **形态**（scheme / host / path 判据），不含任何真实 sid / hash / mid / token 原值。

涉及文件（全部为绝对路径）：

| 文件 | 行数 | 职责 |
|---|---|---|
| `E:\Zcode_harmony\entry\src\main\ets\pages\Index.ets` | 1583 | 唯一页面：Web 组件、URL 消费、状态栏查表、返回键 |
| `E:\Zcode_harmony\entry\src\main\ets\entryability\EntryAbility.ets` | 308 | 窗口/全屏/状态栏、深链与快捷方式入口 |
| `E:\Zcode_harmony\entry\src\main\ets\common\RemoteUrlManager.ets` | 63 | URL 校验 + Preferences 持久化 |
| `E:\Zcode_harmony\entry\src\main\module.json5` | 95 | 深链 skills、权限、快捷方式注册 |
| `E:\Zcode_harmony\entry\src\main\resources\*\element\color.json` | — | `status_bar_bg` 四端限定色 |
| `E:\Zcode_harmony\entry\src\main\ets\common\AllowedFileExtensions.ets` | 34 | 上传可选后缀（与本次问题无关，仅上传域引用） |

全项目只有 **一个** `Web(...)` 组件实例：`Index.ets:882`（`grep -rn "Web(" entry/src/main/ets/` 命中结果为 `Index.ets:882` 及两处名为 `onPageStateFromWeb` 的方法，非组件）。`main_pages.json` 只声明 `pages/Index` 一页。

---

## 1. Web 组件配置（逐属性）

组件声明与全部属性（`Index.ets:882-1027`）：

| 属性 | 值 | 行号 | 说明 |
|---|---|---|---|
| `src` | `this.remoteUrl` | 882 | 只来自「已校验并持久化的连接 URL」，见 §2 |
| `controller` | `this.controller` | 882 | `webview.WebviewController`（`Index.ets:456`） |
| `domStorageAccess` | `true` | 883 | |
| `javaScriptAccess` | `true` | 884 | |
| `fileAccess` | `true` | 885 | |
| `mixedMode` | `1` | 886 | SDK 实证 `E:\DevEco Studio\sdk\default\openharmony\ets\component\web.d.ts:723-741`：`0=All / 1=Compatible / 2=None` → **Compatible（宽松）** |
| `imageAccess` | `true` | 887 | |
| `zoomAccess` | `false` | 889 | 注释：禁止双指/双击缩放，套壳页按窗口宽度自适应 |
| `metaViewport` | `true` | 893 | 显式开启，覆盖 ArkWeb「UA 含 Mobile 才解析 viewport」的默认关 |
| `darkMode` | `2` | 894 | `web.d.ts:957-977`：`0=Off / 1=On / 2=Auto` → **Auto（跟随系统，向网页传 prefers-color-scheme）** |
| `backgroundColor` | `this.webBgColor` | 895 | 开屏壳底色（亮 `#FAFAFA` / 暗 `#171717`，`Index.ets:59-60, 464`） |
| `geolocationAccess` | `true` | 896 | |
| `javaScriptProxy` | `{object: barBridge, name: 'zcodeStatusBar', methodList: ['onPageState'], controller}` | 899-904 | 唯一注入桥，**只回推状态名，不回推色值** |

**未使用**（grep 全仓 `entry/src/main/ets/` 无命中）：`forceDarkAccess`、`onUrlLoadIntercept`、`onInterceptRequest`、`onOverrideUrlLoading`、`onRefreshAccessedHistory`、`onDownloadStart`（改用 `WebDownloadDelegate`，见 §5）。
即：**页面导航的唯一闸门是 `onLoadIntercept`**（1013-1027），不存在另设的 URL 拦截层。

关键回调：

| 回调 | 行号 | 行为 |
|---|---|---|
| `onControllerAttached` | 907-915 | `controller.getUserAgent() + ' ZCode-HarmonyOS/1.0'` → `setCustomUserAgent`（909-910，UA **追加**而非替换）；并注册下载代理 |
| `onPageBegin` | 916-951 | 复位 loading/error；记录 `currentPageUrl`（922，**该字段写入后全仓无读取，死状态**）；按 URL 先给登录页初始配色（926-946）；注入系统语言覆写 |
| `onProgressChange` | 952-956 | 顶部 3px 进度条 |
| `onFirstContentfulPaint` | 957-960 | 安装状态栏观察脚本 |
| `onPageEnd` | 961-966 | 兜底再装一次观察脚本（幂等） |
| `onErrorReceive` | 967-977 | 仅主框架 + 无网络 → 原生错误页 |
| `onHttpErrorReceive` | 978-991 | `statusCode >= 400` 且主框架 → 原生错误页 |
| `onRenderExited` | 992-1001 | `renderExitReason === 2`（渲染进程崩溃）→ `controller.refresh()` |
| `onShowFileSelector` | 1002-1012 | 存下 `handleFileList` 句柄 → `showUploadModal = true` |
| `onLoadIntercept` | 1013-1027 | 见下 |

### 1.1 `onLoadIntercept` 是「登录留在应用内」的实现点

```
1017  const requestUrl: string = event.data.getRequestUrl();
1018  // 非可信域名的页面跳转：仅拦截主框架导航，放行子请求（下载/资源等）。
1019  // 登录必须留在本 WebView：网页把 OAuth nonce 存在 sessionStorage，
1020  // 交给系统浏览器后回调页读不到 nonce，会报 “OAuth CSRF 检测失败”。
1021  if (!this.isTrustedWebDomain(requestUrl) && event.data.isMainFrame()
1022    && !this.isDownloadUrl(requestUrl)) {
1023    this.openInSystemBrowser(requestUrl);
1024    return true;
1025  }
1026  return false;
```

三条判据同时成立才**外抛到系统浏览器**：非可信域名 + 主框架 + 非下载链接。可信域名定义（`Index.ets:1351-1366`）：

- `z.ai` 及任意 `*.z.ai`（含 `chat.z.ai` 的 OAuth 授权页）
- **`bigmodel.cn` 及任意 `*.bigmodel.cn`（BigModel 登录页）** ← 这是"登录页在应用内完成"的直接机制
- URL 解析异常时**保守放行**（`return true`，1363-1364）

注释还额外说明了 `!isMainFrame()` 放行的理由：子资源（API/图片/下载）不拦，只有整页跳转才可能外抛。

### 1.2 白名单边界

- **没有 URL 白名单配置文件**，也没有域名清单常量表；可信域判据硬编码在 `isTrustedWebDomain`（1356-1366）。
- **没有路径级白名单**用于放行。`/remote` 前缀只用于「入口 URL 校验」和「状态栏判态」，不参与导航放行。
- 下载链接豁免清单是硬编码后缀数组（`Index.ets:1370-1379`，约 45 个扩展名，含 `.zip/.pdf/.apk/.md` 等）。

---

## 2. URL 规则：校验、规范化、持久化、进入路径

### 2.1 校验（唯一入口闸门）

`RemoteUrlManager.ets:21-41` `isValidRemoteUrl(target)`，三条同时满足：

1. `parsed.protocol === 'https:'`（28）—— 只认 https
2. `hostname === 'z.ai' || hostname.endsWith('.z.ai')`（32）—— **只认 z.ai 域，不含 bigmodel.cn**
3. `pathname.startsWith('/remote')`（37）—— **限死 `/remote` 前缀，不要求精确 `/remote/v4`**

任何异常（`catch`）→ `false`（38-40）。空串 → `false`（22-24）。

### 2.2 规范化

**无规范化**。`RemoteUrlManager.ets:5` 明确写：「存完整 URL（含全部 query），不做参数级解析/改写」。校验只 `parseURL` 读取，不回写。
另一处"规范化"是语言覆写：`Index.ets:1302-1342` 把系统语言转 BCP47 并 `Object.defineProperty` 覆盖 `navigator.language / languages`。

### 2.3 持久化

- 存储介质：`@ohos.data.preferences`，文件名 **`zcode_remote`**，键 **`remote_url`**（`RemoteUrlManager.ets:14-15`）
- 读：`loadRemoteUrl`（44-52），异常/类型不符返回 `''`
- 写：`saveRemoteUrl`（55-63），`putSync` + `flushSync`，失败静默（不阻断热切换）
- 模型：**单连接覆盖式**（无多连接列表，55-63 注释）
- 冷启动传递：`EntryAbility.ets:68-69` 读入 `AppStorage['bootRemoteUrl']` → `Index.ets:510-511` 消费

### 2.4 进入路径（三条）

| 路径 | 入口 | 校验 | 落库 |
|---|---|---|---|
| 冷启动读存档 | `EntryAbility.ets:68-69` → `Index.ets:510-511` | 存档写入时已校验 | — |
| 扫码（主入口） | `Index.ets:830-869` `startScan`；结果 `isValidRemoteUrl(scanned)`（845） | 通过才 `saveRemoteUrl`（847），否则自绘 toast（857-860） | 是 |
| 深链 / 快捷方式 | `EntryAbility.ets:301-307` `handleDeepLink`（`want.uri` 过 `isValidRemoteUrl`）→ `AppStorage['deepLinkUrl']`；`Index.ets:542-544`（冷启动）/ `558-562`（热）→ `consumeDeepLink`（564-577）再 `saveRemoteUrl` | 是（EntryAbility 侧） | 是 |

深链声明在 `module.json5:55-70`：

```
scheme: "https", host: "zcode.z.ai", pathStartWith: "/remote", domainVerify: true
```

注意：深链 host 只声明 `zcode.z.ai`（单主机），而代码侧 `isValidRemoteUrl` 放行整个 `*.z.ai` + `/remote` 前缀——**两者口径不一致**：代码比 manifest 宽。

快捷方式「扫码连接」：`shortcuts_config.json` 传 `parameters.action = "scan"` → `EntryAbility.ets:287-296` 写入 `AppStorage['scanAction']` → `Index.ets:537-540 / 550-555` 拉起扫码。快捷方式语义是**显式重扫**，已有连接也照常拉起（注释 535-537：原实现的 `!hasConnection` 条件是 bug）。

深链热启动的另一处细节：`Index.ets:564-577` 在**已连接**时走 `loadControlPage`（热切换），未连接时只置 `hasConnection = true`，由 build 首次渲染直接以 `remoteUrl` 为准。

---

## 3. 登录相关（bigmodel / zai / 第三方登录 / Cookie）

### 3.1 结论先说

**鸿蒙侧没有任何原生登录 UI、没有表单、没有 token 解析、没有 Cookie 读写 API 调用。**
登录页在应用内完成，靠的是 §1.1 的 `onLoadIntercept` 白名单「**不拦，任其在同一 WebView 内加载**」，外加一串**只为状态栏取色**的登录页识别与配色分支。

### 3.2 逐项对照

| 检查项 | 结论 | 证据 |
|---|---|---|
| `bigmodel` 字样 | **有**，仅用作**可信域**与**状态栏色名**，非登录逻辑 | `Index.ets:10, 66, 111, 193, 788-789, 1359-1361` |
| `zai` / `z.ai` 登录路径识别 | **有**，`chat.z.ai/auth`、`/auth/oauth/authorize` | `Index.ets:196-201, 791-797` |
| 第三方登录（微信/GitHub 等）专门处理 | **无**（无任何第三方 provider 名单或分支） | 全仓 grep 无 `wechat/github/google/apple` 等 |
| Cookie 读取 / 写入 / 清理 | **完全无** | `grep -rni "cookie\|WebCookieManager\|deleteAllCookies\|clearCache" ets/` → 无命中 |
| 清 Cookie / 登出处理 | **无** | 同上；无 logout 相关分支 |
| `onRefreshAccessedHistory` | **完全无** | `grep` 无命中 |
| 登录成功后的回调/Token 处理 | **无**（回调由网页自身在同一 WebView 完成） | 见 §1.1 注释 1019-1020 |
| 登录页禁止注入 | **无实际禁止**（仅注释声称禁止，代码实际会注入） | 见 §3.4 |
| 登录页禁止插刷新按钮 | **有**（仅此一处功能性排除） | `Index.ets:349-350`（JS：`if (authState() !== '') { return; }`） |

### 3.3 登录页的**唯一**实质处理：状态栏配色

登录/授权页被识别成 8 个状态名（`Index.ets:191-211` JS 侧、`783-808` ArkTS 侧 `authStateForUrl`，两处判据同源）：

| 状态名 | 触发条件 | 固定底色 | 行号 |
|---|---|---|---|
| `auth-bigmodel` | host ∈ `*.bigmodel.cn` | `#FFF7F8FA`（**恒亮，不看主题**） | 66, 193, 788-789 |
| `auth-zai-consent-light/dark` | `chat.z.ai` 且 path 前缀 `/auth/oauth/authorize` | `#FFF8F8F8` / `#FF161616` | 70-71, 196-198 |
| `auth-zai-login-{phone-}light` | `chat.z.ai` 且 path 前缀 `/auth`（非 oauth/authorize） | `#FFF5F6F8` / 窄屏（`max-width:512px`）时 `#FFFFFFFF` | 67-68, 199-200, 181-187 |
| `auth-zai-login-dark` / `-phone-dark` | 同上 + `pageIsDark()` | `#FF141618` | 69, 628-631 |
| `auth-zcode-light/dark` | `zcode.z.ai`/`*.zcode.z.ai` 且 path 前缀 `/web-remote`、`/share`、`/cn/share` | `#FFF8F8F8` / `#FF161616`；**无主题信号时默认暗** | 72-73, 203-208, 799-803 |

主题判定不看系统深浅：`pageIsDark()` 依次读 `data-zcode-browser-theme-surface` → `<html>` class 里的 `dark`/`theme-zai-dark` → 退回 `prefers-color-scheme`（`Index.ets:141-156`）。注释明确「BigModel 恒亮，chat.z.ai 默认亮，zcode /web-remote 默认暗」（65, 190）。

进入登录页时的配色有**两段**：先由 `onPageBegin` 按 URL 给初始值（`Index.ets:926-933`，注释 924-925 说明这样做是为了避免先闪一下 boot 色再被纠正），再由观察脚本按 DOM/主题精确纠正（`onPageStateFromWeb`，727-770，`base.indexOf('auth-') === 0` 分支 743-755）。

### 3.4 两处**死代码 / 失实注释**（值得安卓侧注意，勿照抄）

1. `Index.ets:505-507` 字段注释称 `currentPageUrl`「用于把状态栏观察脚本限定在远程控制页，不注入到登录授权页或 OAuth 回调页」——但该字段在 `922` 写入后**全仓无任何读取**，实际不承担任何 gating。
2. `Index.ets:811-821` `isRemoteControlPage()` **定义后全仓无调用**（唯一"远程控制页"判据的实体是 `RemoteUrlManager.isValidRemoteUrl` 与 `isTrustedWebDomain`）。
3. 与之矛盾的是 `Index.ets:773` 的注释："远程页与登录/授权页都要装：脚本自己按 host 过滤"，这才是实际情况：`installStatusBarObserver` 在 `onFirstContentfulPaint`(959) 与 `onPageEnd`(965) **无条件注入**，真正的 host 过滤在注入脚本内部：`Index.ets:107-111`（`if (!hostIs('z.ai') && !hostIs('bigmodel.cn')) { return 'skip'; }`）。

即：**登录页也在应用内被注入观察脚本**（会装 MutationObserver、可能插刷新按钮——但被 349-350 的 auth 判据挡掉按钮）；注入只做只读 DOM 检查，不改页面节点（除刷新按钮，且登录页不插）。

---

## 4. 状态栏与配色

### 4.1 窗口层（EntryAbility）

`EntryAbility.ets:105-183` `onWindowStageCreate`：

- `mainWindowClass = windowStage.getMainWindowSync()`（109）
- `setImmersiveModeEnabledState(true)`（119，失败仅 warn）
- `setWindowLayoutFullScreen(true)` **预加载一次**（124，`.then/.catch` 均记 hilog）
- `loadContent('pages/Index')`（148）
- `setWindowLayoutFullScreen(true)` **加载后再声明一次**（158）——注释 153-154 记录 P70 实测：loadContent 之前调用会被窗口管理器忽略，窗口仍从状态栏下方开始（`drawableTop` 探测在 161-165）
- `avoidAreaChange` 监听（138-142，仅 `TYPE_SYSTEM`）→ `syncTopAvoid()`
- `onConfigurationUpdate`（84-103）：**必须用回调入参** `newConfig.colorMode`，因为此时 `this.context.config` 尚未刷新（注释 90-91 明确这是踩过的坑：沿用会拿到上一档模式导致状态栏与页面反相）

`syncTopAvoid()`（263-274）把 `getWindowAvoidArea(TYPE_SYSTEM).topRect.height` 写进 `AppStorage['topAvoidPx']`；**必须在 loadContent 之后读**（注释 179：之前读会得到 0，网页会顶到屏幕最上沿）。

### 4.2 状态栏三态与色值口径

**背景色 = 透明**，由页面顶部占位 Row 提供实色（`EntryAbility.ets:203-213` 注释即官方推荐做法；`setWindowSystemBarProperties` 直接赋色会在启动/退出过渡动画期间就让状态栏突兀变色，FAQ faqs-arkui-1652）。

`EntryAbility.ets:214-241 applySystemBar()`：

- `statusBarColor: '#00000000'`（223，恒透明）
- `statusBarContentColor: isDark ? '#FFFFFF' : '#000000'`（224，常量 30-31；只看**系统深浅**）
- `setWindowBackgroundColor(resolveStatusBarBg())`（237）—— 窗口底色兜底启动过渡期闪白

`resolveStatusBarBg()`（247-257）读 `app.color.status_bar_bg`，四端限定目录实测：

| 目录 | `status_bar_bg` / `start_window_background` |
|---|---|
| `base` | `#FFFAFAFA` |
| `dark` | `#FF171717` |
| `tablet` | `#FFFAFAFA` |
| `tablet-dark` | `#FF171717` |

四端**都已统一为 Boot 壳色**（亮 `#FAFAFA` / 暗 `#171717`），即资源只负责"启动到网页首绘"这段的兜底。

### 4.3 页面层：真正驱动状态栏的是「网页状态查表」

- `Index.ets:875-878`：顶部占位 `Row`，高 `topAvoidPx` px，底色 `this.statusBarColor`（`@State`，初值 `$r('app.color.status_bar_bg')`，467）
- `Index.ets:685-702 applySystemBarColor(color, darkBackground)`：
  - `useTransparent = (this.windowDrawableTop === 0)`（689）→ 透明 + Row 覆盖；否则**回退实色**，避免露系统白底（注释 683-684、677-679：P70 实测窗口可能仍被排在状态栏下方、占位 Row 高度为 0）
  - `statusBarContentColor` 由**传入的 `darkBackground`**（即网页底部亮暗）决定，**不是**系统深浅
- `Index.ets:705-724 refreshWindowDrawableTop()`：读 `getWindowProperties().drawableRect.top`，`onPageShow` 时查一次 + 300ms 后复查（529-530）
- `Index.ets:1212`：根节点 `expandSafeArea([SYSTEM], [TOP, BOTTOM])`（注释 1210-1211：这样通知面板的 scrim 才能把 Row 与页面一起压暗，底部同时贴底）

### 4.4 固定色表（运行时零取色）

`Index.ets:55-73`，全部为开发期从网页静态资源 + 真机像素双向标定的**常量**：

| 状态面 | 亮 | 暗 | 行号 |
|---|---|---|---|
| Main/Surface（`--color-sidebar`） | `#FFF0F0F0` | `#FF161616` | 55-56 |
| Main/Header（`--color-header`） | `#FFFFFFFF` | `#FF202020` | 57-58 |
| Boot/Shell（预渲染壳未着主题，`:root` 兜底） | `#FFFAFAFA` | `#FF171717` | 59-60 |
| Boot（已着主题 / KICKED / 配对等待状态页） | `#FFF8F8F8` | `#FF161616` | 61-62 |
| auth-*（8 项，见 §3.3） | — | — | 64-73 |

**是否有"跟随网页主题"的机制**：有，且是**事件驱动 + 查表**（非运行时取色）：
注入脚本 `STATUS_BAR_OBSERVER_JS`（`Index.ets:99-422`）用 MutationObserver（306）+ `matchMedia('(max-width: 767px)')` change（295-302）合并到 rAF 帧，状态变化才经 `window.zcodeStatusBar.onPageState(state)`（270）回推**状态名**；原生 `onPageStateFromWeb`（727-770）解析 `-dark/-light` 后缀，查表换色。宽窄屏判据与网页同源（`max-width: 767px`，注释 48-53 记录了原 `deviceInfo.deviceType` 分叉在平板竖屏失效的坑）。

系统深浅变化只在**网页尚未回推过主题**时才影响底色（`webThemeReported` 闸门，`Index.ets:485, 590-592`），因为「网页默认 zai-dark，可与系统相反」（589）。

---

## 5. 其他相关

- **返回键**：`Index.ets:1228-1245` 三级链——① 上传模态框开着 → 关闭并消费；② 手机 + `accessBackward()` → `backward()`；③ 否则 `return false` 走系统默认，由 `EntryAbility.onBackPressed()`（199-201）返回 `true` 兜底「移到后台不销毁」（WebView 会话保留）。平板（非手机）恒 `true` 全吞（1233-1235）。
  - 与登录相关的点：**登录页不特殊对待**，页面内历史（含登录 → 回调）靠 `accessBackward()` 正常回退。
- **渲染崩溃恢复**：`onRenderExited`（992-1001），仅 `renderExitReason === 2` 时 `controller.refresh()`。无历史恢复逻辑，靠网页自身 `location.reload()` 保 `history.state` 的机制（脚本注释 311-312）。
- **上传模态框**：`onShowFileSelector`（1002-1012）→ `@State showUploadModal`（495）→ 原生自绘二选一（相册 `PhotoViewPicker` / 文件 `DocumentViewPicker`，1075-1138, 1540-1582）。**与登录页无交互**：登录页自身不上传时该链路不触发；被裁剪项仅有"拍照"（CameraPicker 已移除，注释 10）。
- **下载代理**：`setupDownloadDelegate`（1436-1481）+ `saveToPublicDownload`（1484-1528），GUID 映射沙箱路径，完成后弹系统另存框。与登录页无交互。
- **前台常亮**：`setKeepScreenOn`（1248-1261），仅 `hasConnection` 时生效（533-534, 1218-1219）。
- **观察脚本的兼职**：在对话页头部注入「刷新页面」按钮（`Index.ets:309-417`），**登录页不插**（349-350）；同理也不改登录页任何节点。

---

## 6. 结论

### 6.1 鸿蒙侧对「登录页在应用内完成」实际做的是什么

**答案是"不拦，任其加载"，并在三处做了配套**，而不是"原生接管登录"：

1. **导航闸门只拦截「非可信域 + 主框架 + 非下载」**（`Index.ets:1013-1027`）。可信域 = `z.ai` 全系 + **`bigmodel.cn` 全系**（1356-1366）。因此 `*.bigmodel.cn` 与 `chat.z.ai/auth*` 的整页跳转一律 `return false`，**留在同一个 WebView、同一存储上下文**里完成。
   - 这不是顺带的：注释 1019-1020 写明这是**刻意选择**——"登录必须留在本 WebView：网页把 OAuth nonce 存在 sessionStorage，交给系统浏览器后回调页读不到 nonce，会报 'OAuth CSRF 检测失败'"。
   - 判据只到**域名**，不看路径；**没有** `bigmodel`/`zai` 之外任何登录相关的特殊分支，**没有** Cookie 读写/清理，**没有** `onRefreshAccessedHistory`，**没有**原生登录表单或 token 处理。
2. **登录页被登记进状态栏配色表**（8 个 `auth-*` 状态名，§3.3），并在 `onPageBegin` 先按 URL 给初值以防闪色（926-933）。
3. **登录页排除两处注入副作用**：不插刷新按钮（349-350）；观察脚本本身对非 `z.ai`/`bigmodel.cn` host 直接 `return 'skip'`（111）。但脚本**确实会注入**登录页（959/965 无条件调用），字段注释（505-507）与实际不符，属失实注释。

**没有任何"白名单外就外抛登录"的路径**：只要 host 在 `z.ai` / `bigmodel.cn` 之内，无论 path 是什么，都不会外抛（只拦非可信域）。这也是为什么登录页能整链在应用内走完。

### 6.2 状态栏取色口径（一句话）

**透明状态栏 + 页面顶部占位 Row 提供实色；实色不取自运行时像素，而是"网页回推状态名 → 原生查固定色表"**；表内 12 个色值全部是开发期从网页静态资源 + 真机像素双向标定写死的常量，随页面主题（`-dark/-light` 后缀）切换而不随系统深浅（仅在网页尚未回推时退回系统深浅）。系统状态栏图标明暗由同一状态名的 `dark` 位决定；窗口可绘制区 `drawableRect.top !== 0` 时降级为实色状态栏兜底。

### 6.3 安卓可照搬的点（≤5 条）

1. **可信域名单照抄，且必须含 `bigmodel.cn` 全系**：`z.ai` + `*.z.ai` + `bigmodel.cn` + `*.bigmodel.cn`；主框架导航到名单外才外抛系统浏览器，子资源/下载一律放行。理由是 OAuth nonce 在 WebView `sessionStorage`，外抛必 CSRF 失败（鸿蒙已实测踩坑）。
2. **登录判定只认 host + path 前缀，不碰 Cookie**：`bigmodel.cn`（恒亮）、`chat.z.ai/auth/oauth/authorize`、`chat.z.ai/auth*`、`zcode.z.ai(/web-remote|/share|/cn/share)`；无任何 Cookie 读写、无登出清理、无回调页特判——回调由网页在同一 WebView 内自洽完成。
3. **登录页的配色要单独一张固定色表，不能跟系统深浅**：BigModel 恒亮 `#F7F8FA`、chat.z.ai 登录亮 `#F5F6F8`（窄屏 `#FFFFFF`）/ 暗 `#141618`、consent 亮 `#F8F8F8` / 暗 `#161616`、zcode 亮 `#F8F8F8` / 暗 `#161616`（无主题信号默认暗）；并在页面开始加载时**先按 URL 给初值**，避免先闪 boot 色再纠正。
4. **状态栏底色用"状态名回推 + 查表"，不要运行时从页面取色**：事件驱动（DOM 观察 + 同源 media query）合帧后回推状态名，页面主题与系统主题相反时不打架；配合"透明状态栏 + 顶部占位 Row"，并保留 `drawableTop != 0` 时的实色降级。
5. **别照抄的坑**：`currentPageUrl`（写了不读）、`isRemoteControlPage()`（定义了不调用）、`Index.ets:505-507` 的失实注释；以及深链 manifest 只声明 `zcode.z.ai` 而代码放行整个 `*.z.ai` 的**口径不一致**——安卓侧直接对齐代码口径（`*.z.ai` + `/remote` 前缀）即可，不必复制这个不一致。
