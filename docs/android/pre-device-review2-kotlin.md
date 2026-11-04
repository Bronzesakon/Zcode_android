# 安卓壳第二审查·Kotlin/修复复核（pre-device-review2-kotlin）

- 审查对象：`edfe1a8` + `21c7a76` 两个修复提交本身（基线 `6cdda43`，diff 范围 `android/app/src/main/java`、`android/app/src/main/res`、`android/app/src/test`，res 无改动）。
- 审查方式：只读。取全量 diff（649 行）后逐文件读完整上下文（ShellRuntime.kt 2889 行、NativeRelayClient.kt 1241 行、MainActivity.kt 1366 行、HandoffCoverState.kt、DesktopVersion.kt、DownloadPlan.kt、WebAppBridge.kt、RemoteUrl.kt、inject.js 相关段、AndroidManifest.xml、两份单测）。
- 共四轮：R1 按任务清单逐项 → R1 新发现 6（P1×1 + P2×5）；R2 换关键词/反向 grep（"谁还调它/它还调谁"）→ 新发现 1（P2×1）；R3 对每个"判没问题"结论做反证 + 逐 hunk 最坏时序推演 → 0；R4 跨文件触点 grep + res/布局复核 → 0。R3、R4 连续两轮零新发现，收尾。
- 未真机实测（只读约束）；单测未运行（运行会写 build 产物），纯逻辑结论均经人工推演。
- 凭证纪律：全文不含真实 sid/hash/mid/token；单测里出现的是占位符 `<SID>`/`<MID>`（DesktopVersionTest.kt:34-79）。

---

## P0（上机即坏）

无。

## P1（特定时序坏）

### P1-1 渲染器重建的"回扫码页"分支把已销毁的 WebView 留在字段里，此后所有加载路径全部落在死实例上

- 位置：`android/app/src/main/java/com/zcode/remote/MainActivity.kt:608-618`（rebuildWebViewAfterRendererGone）、`:640-651`（detachAndDestroy）、`:728-738`（applyUrl）、`:747-752`（showSetup）
- 触发条件：`onRenderProcessGone` 发生在**非可恢复页**上——`dead.url` 为空、`about:blank`、或 OAuth 回调页（`zcode.z.ai/web-remote/callback?code=…`，路径不以 `/remote` 开头，`RemoteUrl.isAllowed=false`）。此时 `url==null` 走 showSetup。
- 证据：`detachAndDestroy(dead)`（613 行）**先于**分支执行，`dead.destroy()` 已调用；`webView` 字段只在 `:198`（onCreate）与 `:627`（重建成功分支）两处赋值——showSetup 分支不换新实例。此后 `showSetup()` 里的 `webView.visibility`（749 行）作用在已销毁实例上；用户扫码/粘贴/手动输入后 `applyUrl`（731 行 `webView.visibility`、735 行 `installInjection()`→`WebViewCompat.addDocumentStartJavaScript`、737 行 `loadUrl`）与返回键 `webView.canGoBack()`（937 行）全部打在死实例上。`WebView.destroy()` 的契约是"之后不得再调任何方法"，实际行为（静默 no-op 或抛 IllegalStateException）取决于 WebView 实现——无论哪种，壳都永久卡死在接入页（唯一出路是杀进程重启）。本分支是本轮修复新引入的（旧代码无校验、必重建）。
- 建议修法（≤3 行）：`url==null` 分支在 `showSetup()` 前也换上新实例：`webView = WebView(this)` + `binding.root.addView(webView, 0)` + `bridgeAttached=false; injectionRegistered=false; configureWebView()`；或把 `detachAndDestroy` 挪到分支判定之后、为 setup 路径先造新实例。

## P2（质量/加固）

### P2-1 桥"冷启动首挂"把整条注入链押在一个未受保护的回调顺序不变量上，失效即整文档静默失联

- 位置：`android/app/src/main/java/com/zcode/remote/MainActivity.kt:291-294`（configureWebView 不再挂桥）、`:368`（onPageStarted 首挂）；`android/app/src/main/assets/inject.js:70-84`（`var bridge = G.ZCodeShell || null` 一次性捕获 + `post()` 空桥直接 return）、`:216-232`（`config()` 首调无桥即永久缓存 defaults）
- 触发条件：任何引擎形态下 `onPageStarted` 未先于 document-start 脚本执行（本次按 Chromium `DidStartNavigation → 文档创建` 的顺序判定为成立，但这是**新引入的承重假设**——修复前桥在 configureWebView 就挂好，不依赖回调顺序）。
- 证据：document-start 捕获 `bridge=null` 后，该文档的 `post()` 永远丢弃（ready/pagestate/liveness/pagefailure 全部静默），`config()` 永久返回 defaults（restoreChat、运行集种子全丢）；无任何重试或取证行。
- 建议修法：inject.js `post()` 里懒重捕获：`if (!bridge) bridge = G.ZCodeShell || null;`；`config()` 在 `!bridge` 分支**不写 `cachedConfig`**（每次重试），一行改动消掉整个假设。

### P2-2 `carrierLostControlAtMs` 不随新承载启动复位，重启承载的自我 KICK 可被误判为外部抢占

- 位置：`android/app/src/main/java/com/zcode/remote/ShellRuntime.kt:1315-1324`（kickedIsSelfAttributed 先判 lostControl 再判 isRunning）、`:1982-2024`（startNativeCarrier 未清 `carrierLostControlAtMs`）
- 触发条件：T0 外部接管踢掉承载（onKicked 记账）→ 用户回前台（stopCarrierAndCloseWindow，lostControl 仍为 T0）→ 10s 内再次退后台、重拨并在 T0+~7.5s 配对成功（0ms 起拨 + 1~2s 配对）→ 新承载踢页面的 KICKED 在 T0+10s 内到达。
- 证据：kickedIsSelfAttributed 第一分支 `elapsed - carrierLostControlAtMs <= 10_000L` 直接 `return false`——**新承载自己的踢**被归外部：不盖罩、不重载（2516-2522 行只记日志），页面滞留 KICKED 终态。经"自动重连"路径（NativeRelayClient.kt:1010-1048 固定 10s 延时 + 配对 1-2s）推演落在 ≥10.5s、恰好逃出窗口；只有"手动停→快速重启"能命中，窗口窄但真实。startNativeCarrier 会重开归因窗口（openCarrierWindow）却不清失去控制权的记账，语义不自洽。
- 建议修法：`startNativeCarrier` 开头加 `carrierLostControlAtMs = 0L`（新承载实例的 KICK 理应重新归自己）。

### P2-3 requestPageReload 的 10s 取消守卫一次性消费 pageReloadEpoch，取消后该周期再无自动重载

- 位置：`android/app/src/main/java/com/zcode/remote/ShellRuntime.kt:1941-1966`（1950 行在 post 外置位 `pageReloadEpoch = epoch`，1956-1961 行在 post 内因 lostControl 取消）
- 触发条件：外部接管踢掉承载（T0 记账）→ 用户 10s 内回前台 → 回前台交还的 `requestPageReload`（:697）被守卫取消。
- 证据：epoch 在取消前已被消费，`pageReloadEpoch == epoch` 的去重使同周期内任何后续 reload 请求（含 :2507 自我接管路径）全部早退；页面滞留官方冲突页，直到用户再切一次后台/前台（新 epoch）或手动刷新。守卫本身防"反顶接管方"是对的，但取消不该吞掉唯一一次恢复机会。
- 建议修法：取消分支回滚 `pageReloadEpoch = -1L` 再 return（或把置位挪进 post、确认执行时才置）。

### P2-4 stop() 改优雅 close 后，写队列中"刚入队未落网"的 auth proof 仍会先于 close 帧冲出去

- 位置：`android/app/src/main/java/com/zcode/remote/core/NativeRelayClient.kt:594-602`（`s?.close(1000, "handback")`，旧 `cancel()` 注释点名的场景）、`:1077-1099`（auth 应答在 lifecycleLock 内 enqueue）
- 触发条件：用户回前台与 auth_challenge 应答同拍——应答先 enqueue（锁内原子）→ stop() 后拿到锁 close(1000)，OkHttp writer 按序冲帧：proof 先出 → relay 配对 → 踢页面 → 才轮到 close 帧。
- 证据：锁只保证"enqueue 与 isCurrent 检查原子"，管不住 stop() 之后的 writer flush 顺序；旧代码正是为此用 cancel()。影响有界：交还后的 requestPageReload 会重载自愈，但页面在交还瞬间可能被多踢一次。真机复现窗口极窄。
- 建议修法：接受现状，但把 :597-598 的注释改准确（"锁封死 enqueue，封不死 writer 已排队的帧"），或在 close 前补一行说明该残差由交还重载兜底。

### P2-5 下载重名文件行为未验证，最坏情况第二次同名下载静默失败

- 位置：`android/app/src/main/java/com/zcode/remote/MainActivity.kt:562-598`（enqueueDownload，586-591 行两个目的档分支）；`android/app/src/main/java/com/zcode/remote/core/DownloadPlan.kt:88-96`
- 触发条件：同一文件名在公共 Downloads（API≥29）或应用私有 Downloads（API≤28）已存在时再次下载。
- 证据：`DownloadManager` 对已存在目标文件是覆盖还是 `STATUS_FILE_ERROR` 未实测（只读约束，两代系统行为还可能不同）；`setDestinationInExternalPublicDir` 无 WRITE 权限在 API 29+ 属平台惯例可用路径（DownloadManager 自持权限），按惯例判 OK。异常路径只记异常类名（596 行）不泄 URL，日志面干净。
- 建议修法：入队前探测目标文件存在即加序号后缀（`name-2.pdf`）；上真机后把实际行为补进本节。

### P2-6 onConfigurationChanged 只重算既有档位，不重算登录面的 narrow 变体

- 位置：`android/app/src/main/java/com/zcode/remote/MainActivity.kt:927-933`（onConfigurationChanged 只调 applyStatusBarSurface）、`:884-889`（narrow 仅在 onPageStartedSurface 里随 `screenWidthDp` 计算）
- 触发条件：在登录/授权面上旋转屏幕且 `screenWidthDp` 跨越 `AUTH_NARROW_MAX_DP`。
- 证据：`pageBarState` 里的 narrow 档是加载时算好的定值，onConfigurationChanged 的重算（`darkFor(surfaceState, pageTheme, isSystemDark())`）只会更新系统深浅分量，宽度档停在旧值，下次整页加载才纠正。纯外观、低频。
- 建议修法：onConfigurationChanged 里若当前是 auth 面（`RemoteUrl.authSurfaceOf(webView.url)` 非空），补一次 `onPageStartedSurface(Uri.parse(url))`。

---

## 核查后判定没问题（避免下轮重复怀疑）

1. **closeCarrierWindowAfter 的 token 复用/漏关**：`openCarrierWindow`（ShellRuntime.kt:2034-2042）使在途的"宽限后关闭"闭包失配（token 比对 2054 行），推演了"停→5s 内重启""停两次""onKicked 立关再 stamp"多个序列，均无漏关、无误关新窗口。
2. **onKicked 的 mainHandler.post 与 pagefailure 的 post 乱序**：两序皆有界——pagefailure 先到：isRunning=true 判自我→罩+reload，onKicked 后到使 reload 在 post 内取消，罩由 15s expire 收尾；onKicked 先到：lostControl 10s 内判外部，壳不动手。无永挂路径。
3. **宽限期内用户手动重连成功后又被再踢的 5s 残差**：KICKED 帧内容无法区分"我方迟到帧"与"外部新踢"，5s 宽限是已拍板的启发式折衷（ShellRuntime.kt:1290-1296），残差接受，不建议再加机制。
4. **stopAndAwaitClose 双计数**（NativeRelayClient.kt:507-564）：watcher 以 socket **实例**为键且在 stop() 之前注册；并发 stop 使本方 stop() 早退（568 行）→ onBridgesDisposed 不触发 → pending 不归零 → 100ms 超时 deliver(false) 兜底；快照与注册间隙插入重连只导致 confirmed=false 误报，reload 照发（设计内的降级）。AtomicBoolean 防双重交付，超时后 oneDone 的 deliver(true) 被 ate。
5. **closeWatchers 永久滞留**：不可能——每条目必被（onClosed/onFailure 回调 | 100ms 超时）其一移除；signalSocketClosed 对未注册 socket 是 no-op（561-564 行）。共享 `closeConfirmTimer`（daemon，永不 cancel）的 TimerTask 体不抛（deliver 内 try/catch），不会毒死 Timer 线程。
6. **stop() 里 close(1000) 与 lifetime.end() 先后**：watcher 触发不依赖 isCurrent/lifetime（signalSocketClosed 在任何早退判断之前，1180/1190 行）；对端不应答时 OkHttp 迟早 onFailure，100ms 超时先到——onClosed 不来也不会卡交还。
7. **rendererGone 可见分支与 foregroundRecovery/carrierMatched 相互作用**（HandoffCoverState.kt:112-137、44-56）：转换后 carrierEpoch 钉 -1，coverForRendererGone 必排程 expire(-1)（ShellRuntime.kt:2236-2242）兜底；"转换后再被 carrierMatched 覆盖回 returning=false"需要"渲染器死+后台再来一发自归属 KICKED"叠加，行为回到修复前的承载罩语义（beginReturn 接管），非新滞留。
8. **pageFailed/carrierKicked 在 foregroundRecovery 之后**：sourceDocument="" 使老文档失败帧可提前撤罩（罩闪一下即恢复，无滞留）；carrierKicked 同代次即清，正确放行冲突页。
9. **前台 KICKED 的释放闭环**：foregroundRecovery→reload→onPageStarted→pageStarted(loadStarted)→ready→pageReady→logo→renderedCandidate→postVisualStateCallback→pagePresented（MainActivity.kt:239-253 的 requestId 即 revision，闭环成立）；reload 被 epoch 去重或守卫取消时 15s expire 兜底，均不永挂。
10. **DesktopVersion %2B**：VALID 白名单 `[0-9A-Za-z._+-]` 内仅 `+` 需要 query 编码，其余字符 query 安全；`URLSearchParams.get` 解回 `+`；替换已存在参数取首个（页面 get 同取首个）；测试用 `<SID>`/`<MID>` 占位符无真实凭据。
11. **onPageCommitVisible 的 surface 重算**：远程页结构性保证 boot 报告（document-start 后第一个 mutation 拍）先于首帧、真实报告晚于 React 挂载，回落窗口夹在两报之间；auth 面无回推、正是该修的格。残差（commit-visible 晚于内容报告且页面长期无状态变化→条带停在 boot 档；任何后续 pagestate 回推都会按 832-843 行的去重逻辑纠正）留真机观察点。
12. **onConfigurationChanged 幂等性**：manifest 已声明 `uiMode`（AndroidManifest.xml:56）；applyStatusBarSurface 纯重算当前字段，webhook 到来前调用安全（无注入依赖）。
13. **onRenderProcessGone 的 isFinishing/isDestroyed 早退**（MainActivity.kt:475）：死窗口不再加新视图，返回 true 仅防连带杀进程，正确。
14. **ready 分支的日志卫生**：href 只落 path（`substringBefore('?')`+`substringBefore('#')`，ShellRuntime.kt:2554-2556）；relayCreds==null 警告只记状态不带值。
15. **DownloadPlan 纯逻辑**：scheme 白名单、host 去 userinfo/端口、文件名清洗（路径分隔/控制字符/超长保扩展/dotfile）、`+` 保护解码（路径语义不是 form 语义）、`filename*` 优先级均逐条验证；cookie 只进请求头不进日志（580-582、594 行只落文件名+host）。
16. **evaluateJs 线程面**：一律经 mainHandler.post（ShellRuntime.kt:2183-2196），stampCarrierStopped 从泵/诊断路径调用安全；pagefailure/onKicked/pagestate 等 bridge 入口全部 post 回主线程。
17. **改动 API 的调用面完备性**（反向 grep）：`stopCarrierAndCloseWindow` 收口了诊断开关（1396）、链路自愈（1522）、手动停止（2156）三路；旧的 `closeCarrierWindow` 无残留调用者；`carrierMayHaveKickedPage` 仅剩通知定位一路使用（MainActivity.kt:1117，语义正确）；`handoffCover.*` 全部调用点在主线程。
18. **manifest/布局**：无 res 改动；布局 id 为 `@+id/webview`（activity_main.xml:22），diff 里注释从 `binding.webView` 改为 `binding.webview` 使注释与真实生成字段一致（纯注释修正）。

## 自查轮次记录

| 轮次 | 动作 | 新发现 |
| --- | --- | --- |
| R1 | 按任务 8 项清单逐项核对（归因状态机/stopAndAwaitClose/rendererGone 分支/重建路径/%2B/UI 钩子/下载器） | 6（P1-1、P2-1~P2-5） |
| R2 | 换关键词反向 grep：`handoffCover.`、`withAppVersion`、`NativeRelayClient.stop`、`stopAndAwaitClose` 的全部调用方；读 HandoffState/WebAppBridge/inject.js config | 1（P2-6） |
| R3 | 对"判没问题"结论逐条反证（双计数、token 序列、post 乱序、pagestate 时序、无边界的 5s 残差）；逐 hunk 最坏时序推演 | 0 |
| R4 | 跨文件触点 grep（KeepAliveService/DiagReceiver/其余文件无直连）；res diff 复核；布局 id 核对 | 0 |

R3、R4 连续两轮零新发现，收尾。上真机后建议优先验证：P1-1（OAuth 回调页上杀渲染器）、P2-5（重名下载）、核查清单第 11 条的条带回落窗口。
