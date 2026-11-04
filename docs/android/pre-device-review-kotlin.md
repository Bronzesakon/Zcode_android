# 上真机前的只读代码审查（Kotlin）

- 审查范围：`0b03c56..HEAD` 中声明的两个提交 `6188987`（3.17.1 适配 + 原生承载修复）与 `d02c2f5`（close-confirm 收紧到 100ms）。
- 审查方式：只读。全部证据来自 `git diff 0b03c56..d02c2f5` 与完整文件上下文，**未编译、未在真机或模拟器运行**。凡涉及运行时行为的推断都按"未验证"标注。
- 行号基准：**提交 `d02c2f5`**。审查期间仓库 HEAD 已前进到 `6cdda43 feat(download)`（不在本次范围内），该提交插入了下载器约 56 行，`MainActivity.kt` 第 527 行之后的行号会比本报告 **+56**。`ShellRuntime.kt` / `NativeRelayClient.kt` / 其余 core 文件行号不受影响。
- 凭据纪律：报告内所有 URL、sid/hash/mid/token 一律用占位符，未摘录任何真实值。

---

## P0（上机即坏）

### P0-1 自我接管遮罩可能永久滞留，整屏挡死输入

- 位置：`android/app/src/main/java/com/zcode/remote/ShellRuntime.kt:2411`（`handoffCover.carrierMatched(failureEpoch, true, document)`）、`android/app/src/main/java/com/zcode/remote/core/HandoffCoverState.kt:19-33`、`:41-48`
- 触发条件：回前台那一拍，页面把后台积压的 KICKED 在"重载真正派发之前"报上来（`pagefailure` 的 `mainHandler.post` 先于 `requestPageReload` 的 post 执行），且此刻 `carrierRanThisStint` 仍为真。
- 证据：
  - `HandoffCoverState.carrierMatched` 第一行 `if (!away || document.isEmpty() || ...) return false`——`away=false` 时直接拒绝建罩，说明该参数就是为"前台不得建罩"设计的；调用点却写死 `true`。
  - 该调用把 `returning` 置为 `false`（`carrierMatched` 内 `returning = false`）。
  - 此后 `pageStarted()`（`:41`）第一句 `if (!visible || !returning) return` 空转，`renderedCandidate` / `pagePresented` 同样被 `!returning` 短路 ⇒ 新文档就绪也不会撤罩。
  - `beginReturn()` 只在前后台**跃迁**时调用（`ShellRuntime.kt:655`），前台内创建的罩赶不上这一次跃迁，而 `expire()`（`:103`）要求 `returning == true`，也没有别处给它排超时。
  - 兜底清除只有 `dismissHandoffCover("开始新的后台窗口")`（`ShellRuntime.kt:735`），即用户必须**再切一次后台**才恢复。
  - `handoffCover` 在布局里是 `clickable="true" focusable="true"` 的全屏 View（`res/layout/activity_main.xml`），罩在就是全屏吃触摸。
- 影响：页面其实已经恢复连接，但用户看到 logo 罩、点不动任何东西，只能靠切后台自救。属于"回前台偶发变砖"。
- 建议修法：把 `away` 参数改成 `userIsAway()`（前台直接拒绝建罩，交给已经在路上的重载）；若仍要在前台盖罩，则改为"排一次 `expire(epoch)`"并让 `pageStarted()` 容忍 `returning=false`。
- 状态：**未验证**（概率完全取决于两条 `mainHandler.post` 的先后，需真机日志 `交接过渡：自我接管…` 与"快速交接：开始网页重载"的时序确认）。

---

## P1（特定时序坏）

### P1-1 `closeCarrierWindow()` 漏掉四处承载停止路径，之后的外部抢占会被当成自我接管

- 位置：`ShellRuntime.kt:1490`（pump 自愈交还"页面链路已恢复"）、`:1361`（`carrier_off`）、`:2074`（手动停止）、`:482`（`mayAuthenticate` 里的"前台已返回，取消尚未完成的原生配对"）；对照 `:1636`（只有 `clearCarrierState()` 里调 `closeCarrierWindow()`）
- 触发条件：后台承载被上面任一路径停掉（页面自己恢复最常见），5 分钟内另一台设备/另一个对话把页面顶掉。
- 证据：
  - `openCarrierWindow()` 在 `startNativeCarrier`（`:1957`）里开窗；页面侧的归因窗口是 `carrierWindow.at` + 5 分钟上限（`assets/inject.js:1310-1336`）。
  - `:1490` 交还后只重置 `carrierStarted/carrierStartedEarly/handbackDeferredLogged`，不碰窗口；`carrierRanThisStint` 也仍为真。
  - 结果两侧口径同时错：页面按注入层窗口判定"自我接管"（只记日志、不自愈），原生按 `carrierMayHaveKickedPage()`（`:1298` = `isRunning() || carrierRanThisStint`）也判"自我接管" ⇒ 走 P0-1 那条建罩分支，而这次确实**不是**我们造成的。
- 影响：外部抢占被静默吞掉（页面停在官方 KICKED 终态、罩可能卡住），用户既看不到冲突提示，也没有自动恢复。
- 建议修法：把"停承载 + 关窗口"收成一个私有函数（如 `stopCarrierAndCloseWindow(reason)`），上述四处全部改走它；`carrierRanThisStint` 一并在此复位（或加时间上界）。

### P1-2 版本闸门跳过重载时，`carrierRanThisStint` 永不复位 ⇒ 整个前台会话都武装着"自我接管"

- 位置：`ShellRuntime.kt:1914-1922`（`requestPageReload` 命中 `versionGateBlocked` 后 `return`）、复位点只有 `:2744`（`onPageStarted`）与 `:2772`（`onLeftRemotePage`）
- 触发条件：页面正停在"桌面版本已更新"modal 上时回前台（`requestPageReload` 被跳过 ⇒ 不会触发 `onPageStarted`）。
- 证据：`carrierRanThisStint = false` 的所有写点：`:713`（`reloadExistingPage=false` 冷启动分支）、`:748`（退后台时）、`:2744`（`onPageStarted` 且非 away）、`:2772`（离开远程页）。重载被闸门③跳过时这四条都不成立，标志保持 `true` 直到下一次真正的页面加载。
- 影响：此后任何一次外部抢占都落进"自我接管"分支（P0-1 同一条链），且页面侧窗口早已关闭 ⇒ 两侧归因互相矛盾，用户看不到官方提示。
- 建议修法：`requestPageReload` 被闸门跳过时顺手 `carrierRanThisStint = false`（或给该标志加时间上界，例如与承载"本次后台"绑定的时间戳比对）。

### P1-3 `stopAndAwaitClose` 的"关闭确认"只是本机 `cancel()` 回调，桥 socket 仍在另一个线程关

- 位置：`android/app/src/main/java/com/zcode/remote/core/NativeRelayClient.kt:504-538`（`stopAndAwaitClose`）、`:546-590`（`stop`）、`:576`（`s?.cancel()`）、`:580-588`（`manager.disposeEverything` 另起 Thread）
- 触发条件：每次回前台交还（有任务在跑时必走）。
- 证据：
  - `signalSocketClosed` 由 `onFailure`/`onClosed` 触发（`:1154`、`:1165`），而 `stop()` 用的是 `cancel()` 而不是 `close(1000, …)` ⇒ 回调只证明**本机** socket 已被取消，不证明 relay 侧已释放控制端槽位。
  - 注释自己承认这一点：`s?.cancel()` 上方写着 "A graceful close may still flush a queued authentication proof and kick the page"。
  - 主 socket 之外，`BridgeManager.disposeEverything`（`RelayBridge.kt:1336-1349`，逐桥 `closeBridge()`）是在 `stop()` 里 **新开线程**执行的，`stopAndAwaitClose` 完全没有等待它 ⇒ "先停承载再重载"的不变式只覆盖了主 socket。
  - 超时只有 100ms（`:169`），一旦超时放行，重载会在旧连接仍可能存活时发出；而页面侧的 KICKED 自愈已删除（`inject.js` 移除 `healKickedOnForeground`，`noteRelayKicked` 现在只记日志），一旦真被再顶一次就是终态，只能用户手点"重新连接"。
- 影响：目标场景（回前台后页面停在"已被其他设备接管"且无人恢复）依然可复现；这是本轮最核心的修复点，属于"改了一半"。
- 建议修法：确认改成 `close(1000, "")` 并以 `onClosed`（对端 close ack）作为 `confirmed=true` 的充分条件，`cancel()` 只作超时兜底；把 bridge 关闭也纳入同一个 awaiting（或至少先同步 `disposeEverything` 再派发重载）。

### P1-4 渲染器重建不校验待恢复的 URL，也没有 Activity 存活守卫

- 位置：`android/app/src/main/java/com/zcode/remote/MainActivity.kt:532-536`（`val url = dead.url?.takeIf { it.isNotEmpty() } ?: prefs.remoteUrl`）、`:443-453`（`onRenderProcessGone` 直接 `return true`）
- 触发条件：(a) 渲染器在 `about:blank` / `chrome-error://` / 一次性 OAuth 回调 URL 上死亡；(b) 渲染器在 Activity 已 `finish()`/`onDestroy()` 之后死亡（后台被系统回收、用户划掉任务）。
- 证据：
  - `takeIf { it.isNotEmpty() }` 只排除了空串，`about:blank` 会原样 `loadUrl` ⇒ 白屏、无 `showSetup()`、无错误页，且 `injectStable` 直接 `return`（`:624` 的 `isAllowed` 闸），注入层与就绪位全都不恢复。
  - OAuth 回调 URL 里带一次性 code，重放会得到授权失败页。
  - Activity 已销毁时 `WebView(this)` + `binding.root.addView(...)` 会在已死的窗口上建实例；`return true` 又明确阻止系统杀进程 ⇒ 进程带着一个未销毁的 WebView 与一个已销毁的 Activity 活着。
  - `url == null` 的分支（`:533-536`）先 `showSetup()` 就返回，死 WebView **既没 removeView 也没 destroy**，而 `webView` 字段仍指向它。
- 建议修法：入口先判 `if (isFinishing || isDestroyed) return true`；URL 用 `RemoteUrl.isAllowed` 校验，不过关就走 `showSetup()`（该路径也要 `removeView` + `destroy`）。

### P1-5 重建路径下 `bridgeAttached=false` 与 `configureWebView()` 的无条件 `addJavascriptInterface` 打架，桥会留在登录页

- 位置：`MainActivity.kt:553-557`（`bridgeAttached = false` → `configureWebView()` → `setBridgeAttached(...)`）、`:287`（`configureWebView` 内无条件 `webView.addJavascriptInterface(WebAppBridge(prefs), BRIDGE_NAME)`）、`:610-618`（`setBridgeAttached` 首行 `if (attached == bridgeAttached) return`）
- 触发条件：渲染器在 `chat.z.ai` / `bigmodel.cn` / `zcode.z.ai` 非 `/remote` 文档上死亡并重建。
- 证据：`configureWebView()` 刚把桥挂上，随后 `bridgeAttached` 被置 `false` 再调 `setBridgeAttached(isAllowed(url))`；当 `isAllowed` 为 `false` 时实参 `false` 与字段 `false` 相等 ⇒ **函数直接返回，没有 `removeJavascriptInterface`**。桥（`WebAppBridge` 暴露 `store.workspaces()`、`runningTaskRefs()`、`passiveObserve` 等）就留在 OAuth 文档上，与 `shouldOverrideUrlLoading` 注释里"桥只给远程页，登录页不暴露"（`:324-331`）相反。
- 建议修法：重建时不要手动改 `bridgeAttached`，改为先 `removeJavascriptInterface` 再把字段置 `false`；或让 `configureWebView` 不挂桥、桥只由 `setBridgeAttached` 管理。

---

## P2（代码质量 / 健壮性）

### P2-1 `stopAndAwaitClose` 每次新建 `Timer(true)`，且从不在成功路径 cancel

- 位置：`NativeRelayClient.kt:529-537`
- 证据：`java.util.Timer(true)` 每次调用创建一个 daemon 线程；成功确认后已排队的 TimerTask 仍会在 +100ms 后跑一遍（`closeWatchers.remove` 是空操作）。线程靠 GC 回收（`TimerThread` 的 reaper），不是硬泄漏，但每次回前台都留一具壳；`closeWatchers`（`:152`）也没有 `stop()` 时的整体清理，只靠各自超时移除。
- 建议：持有一个共享 `Timer("carrier-close-confirm", true)` 字段，`schedule` 后 `cancel()` 掉本任务的 `TimerTask`，并在 `stop()` 里 `closeWatchers.clear()`。

### P2-2 系统深浅切换（`uiMode`）不会重绘登录面条带

- 位置：`android/app/src/main/AndroidManifest.xml:56`（`android:configChanges` 含 `uiMode`）、`MainActivity.kt` 无 `onConfigurationChanged`
- 证据：Activity 不重建，登录页又没有注入层回推（`onPageStateReported` 永不触发），`applyStatusBarSurface()` 只在 `onResume` / 页面加载 / 罩变化时跑。系统切深色后停在登录页，条带与图标会保持旧值到下一次导航。
- 建议：覆写 `onConfigurationChanged`，在 `uiMode` 变化且当前 `pageBarState.fixedDark != null` 时重跑 `onPageStartedSurface` 等价逻辑。

### P2-3 登录面的 `dark` 位用系统夜景代替"网页主题"，与 D4 的口径不符且无纠正路径

- 位置：`MainActivity.kt:786-800`（`:795` 调 `authStateFor(surface, isSystemDark(), narrow)`）、`core/PageBarColor.kt:178-190`
- 证据：`PageBarState` 的注释写明 auth 面"follow the *web page's* theme, never the system's"，但原生能拿到的只有 `isSystemDark()`；一旦页面自己存的偏好与系统不一致（例如页面跟随后端主题设置），条带会整段错色，且因为登录页没有注入层，**没有任何纠正机制**。`fixedDark` 位本身确实绕过了系统深浅（`darkFor` 与 `resolve` 都忽略入参），这一点是对的。
- 建议：真机核对 chat.z.ai 登录页是否严格跟随 `prefers-color-scheme`；若否则需要另一条信号（例如页面 URL 上的主题参数），并把结论写进 D4。

### P2-4 登录面之间的 SPA 跳转不会刷新条带档位

- 位置：`MainActivity.kt:786-800`（只在 `onPageStarted` 里调用）
- 证据：`/auth/oauth/authorize` → `/auth` → `/auth/login` 若为同文档路由跳转，`onPageStarted` 不再触发，条带停在授权页那档（`AUTH_CONSENT_*`），与登录页色值（`#F5F6F8` / `#141618`）不同。
- 建议：这是纯观感项；若要修，在 `onPageCommitVisible` 里也跑一次 `onPageStartedSurface(Uri.parse(url))`。

### P2-5 `isDownloadUrl` 的后缀表放行面偏大，而真正"无扩展名的 attachment"仍被外抛

- 位置：`core/RemoteUrl.kt:49-56`、`:118-125`；调用点 `MainActivity.kt:319-322`
- 证据：
  - `.md/.json/.xml/.txt/.log/.csv` 等普通文档页被当成下载 ⇒ 外部链接留在 WebView 而不是交给浏览器（行为变化，非崩溃）。
  - 反向漏判更关键：`https://<外部域>/download?id=1`（服务端用 `Content-Disposition: attachment` 触发下载）不在表内 ⇒ `shouldOverrideUrlLoading` 走"外部链接交给系统浏览器"分支，用户被踢出壳。D2 的"下载不外抛"只对带已知后缀的 URL 成立。
- 建议：把"是否外抛"改成只挡明确的外部导航（`isTrustedNavigation` 之外一律外抛），下载判定留给 `setDownloadListener`（它本来就是权威信号）。

### P2-6 `DesktopVersion` 拼参不做百分号编码，`+` 语义有坑

- 位置：`core/DesktopVersion.kt:36-66`
- 证据：`VALID = ^[0-9A-Za-z._+-]{1,40}$` 允许 `+`，而 query 里的裸 `+` 会被服务端解码成空格（`3.17.1+build` → `3.17.1 build`），闸门比较随即不等。
- 建议：`parse` 里把 `+` 编码为 `%2B`（或从字符集里去掉 `+`）。

### P2-7 `relaycreds` 单文档只发一次 + 原生侧 D7 清空，bfcache 返回会留下"无凭证"状态

- 位置：`assets/inject.js:330-360`（`relayCredsPosted` 文档级一次性）、`ShellRuntime.kt:2766-2781`（`onLeftRemotePage` 清 `relayCreds`）
- 证据：用户点"登录"→ 到 `chat.z.ai`（非 `/remote` ⇒ 原生清 creds）→ 按返回键从 bfcache 恢复远程页时，`relayCredsPosted` 仍是上一份文档的 `true`，不会重发；此后 `maybeStartHandoffCarrier` 因为 `relayCreds == null` 永远不拨号，后台通知静默，直到用户做一次整页重载。
- 建议：原生在收到 `ready`（远程页回来）时把"凭证缺失"记一行日志，或让注入层在 `visibilitychange`/重连时允许补发一次（`relayCredsPosted` 改成按 socket 代次而非文档）。
- 状态：**未验证**（取决于 WebView 返回时是 bfcache 恢复还是整页重载）。

### P2-8 `HandoffCoverState.rendererGone()` 在已有罩时静默失效，且不排超时

- 位置：`core/HandoffCoverState.kt:89-101`、`ShellRuntime.kt:2154-2160`
- 证据：`if (visible) return false` ⇒ `coverForRendererGone` 不 `notifyHandoffCover` 也不排 15s 兜底；若此时正挂着承载罩（`returning=false`），重建后的新文档无法撤罩（同 P0-1 机理）。
- 建议：`rendererGone()` 在已可见时也把 `returning` 置真并返回 true，让重建后的释放路径接管。

### P2-9 注释与实现不一致（`binding.webView`）

- 位置：`MainActivity.kt:83`
- 证据：注释写 "read this field rather than `binding.webView`"，实际字段是 `binding.webview`（小写 v，见 `res/layout/activity_main.xml` 的 `@+id/webview`）。不影响行为，但会误导下一个改这里的人。

---

## 构建配置 / Manifest（第 6 类）

- **release 未开 minify/shrink**：`android/app/build.gradle.kts` 里 `isMinifyEnabled = false`、`isShrinkResources = false` ⇒ 本轮新增的 `DesktopVersion`、`PageBarColor` 新枚举常量、`AuthSurface`、`HandoffCoverState.rendererGone` 等**不存在被 R8 剥掉的风险**。若日后打开 minify，需要重点看：`WebAppBridge` 的 `@JavascriptInterface`（方法名即 JS 契约）、`org.json` 反射无、枚举 `values()` 无。
- **Manifest 结论（对本轮提交）**：不需要新增权限。`ACCESS_NETWORK_STATE`、`WAKE_LOCK`、前台服务、通知权限均已在位；本轮没有引入新的系统服务调用。
- **对范围外的新提交 `6cdda43`（下载器）的提示**：它对 API ≤28 走 `setDestinationInExternalFilesDir`（应用私有目录），因此**确实不需要** `WRITE_EXTERNAL_STORAGE`；一旦改成 API ≤28 也写公共 `Downloads`，就必须补该权限（`maxSdkVersion="28"`）。`DownloadManager` 的通知由 download provider 自己发，不需要我们的 `POST_NOTIFICATIONS`。另：`enqueueDownload` 把 `CookieManager` 里该 URL 的 cookie 透传给下载请求，属于有意为之但值得在真机上确认只对同一 host 生效。

---

## 测试覆盖缺口（第 7 类，只列不写）

已有覆盖（无需补）：`RemoteUrl.isTrustedHost / isDownloadUrl / authSurfaceOf`（`RemoteUrlTest` 逐条钉住，含 `evilz.ai`、`z.ai.evil.com` 这类反向用例，后缀匹配本身**没有**误放）、`DesktopVersion.parse/withAppVersion`、`PageBarColor.darkFor/authStateFor/describe`、`authInitFrame` 两种模式、`HandoffCoverState.rendererGone` + 超时释放。

缺口：

1. `ShellRuntime.onUserPresenceChanged` 交还分支的四个组合（`reloadExistingPage` × `carrierWasRunning` × `reloadNeeded`）一个用例都没有——`ShellRuntime` 整体没有 JVM 测试，而这轮改动恰恰集中在这里。
2. `pagefailure` 的 KICKED 归因两条分支（自我接管 / 外部抢占）零覆盖；P0-1 正是这条分支的状态机错误，用 `HandoffCoverState` 单测就能钉住"前台不得建罩"。
3. `NativeRelayClient.stopAndAwaitClose` 的三种结局（close 先到 / 超时先到 / `socket == null` 立即放行）与"双投递只回调一次"零覆盖；`closeWatchers` 的 remove 竞争也没测。
4. `closeCarrierWindow` 的调用面（P1-1）没有任何测试或断言能发现漏调用点。
5. `DesktopVersion.withAppVersion` 缺 `+`/`%` 的编码用例（P2-6）。
6. `RemoteUrl.isDownloadUrl` 缺"无扩展名 + Content-Disposition"的现实反例（P2-5）。

---

## 本次核查后判定"没有问题"的点（避免重复怀疑）

- `isTrustedHost` 的后缀匹配：要求 `host == root || host.endsWith(".$root")`，`evilz.ai` / `z.ai.evil.com` / `notbigmodel.cn` 均被拒绝，大小写已归一。
- `authSurfaceOf` 的前缀匹配：`/auth/oauth/authorize-x` 会被 `ZAI_CONSENT` 的前缀吃掉、`/authorization` 会落进 `ZAI_LOGIN`，但两者分别与真正的授权页/登录页同属 auth 家族、色值接近；`chat.z.ai` 上其余路径返回 `null`，不会把远程页判成登录面。
- `onPageStartedSurface` 在远程页一律回到 `BOOT_SHELL`，"快速往返残留上一文档头部色"这条被覆盖；auth → 远程页方向没有残留路径。
- `fixedDark` 确实绕过系统深浅：`darkFor` 先取 `state.fixedDark`，`resolve` 对 auth 状态忽略 `dark` 入参。
- `DesktopVersion` 的 `learn/current` 用 `@Volatile`，只读路径单次读快照，无撕裂。
- `epoch` 取消覆盖了延迟拨号：`maybeStartHandoffCarrier` 同时校验 `handoff.isCurrent(epoch, true)` 与 `userIsAway()`，退后台时排队的那一枪在回前台后必然打空。
- `onLeftRemotePage` 清完 `relayCreds` 后，从 OAuth 回到远程页是整页导航 ⇒ 新文档会重新走 `maybePostRelayCreds()`，凭证能回来（唯一的例外见 P2-7）。
- 布局根是 `FrameLayout`，`rebuildWebViewAfterRendererGone` 用 `FrameLayout.LayoutParams` + `addView(fresh, 0)` 不会触发 `ClassCastException`；旧实例先 `removeView` 再 `destroy`，顺序符合 WebView 约定。
- `stopAndAwaitClose` 的 `AtomicBoolean` 确实兜住了"超时先到、close 后到"的双投递；`ConcurrentHashMap.remove` 的原子性保证 watcher 只会被取走一次。
