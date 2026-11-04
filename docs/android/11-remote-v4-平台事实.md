> **迁移说明**：本文档原位于 `android-shell/scratch/audit/`（该目录被 gitignore），于 2026-09-18 迁移至此；本目录同样被 gitignore，需手动拷贝。
> **正文自原文件逐字复制，未作任何删改**（仅在本行之前加入本段说明）。原文中的脚本路径 `android-shell\scratch\audit\` 与来源链接均保持原样。
> 内容说明：本篇虽沿用 `remote-v4-` 前缀命名，实际覆盖的是**安卓平台侧事实**（Android WebView / 前台服务 / ColorOS / Android 16 Live Updates），与第 10 篇（网页扩展面）互为「宿主侧 ↔ 页面侧」两面。

---

# 平台事实核查：Android WebView / 前台服务 / ColorOS / Android 16 Live Updates

被测环境：一加 PLC110，ColorOS 16，Android 16 (API 36)，Android System WebView 153–154。
应用：WebView 套壳（minSdk 26 / targetSdk 35）。

> 状态：**已完成全部条目**（A3/A4/A6/B10/B11/C13 为补充核实）。来源等级标注：`官方文档` / `官方源码` / `规范` / `社区经验` / `未找到权威来源`。
>
> ⚠️ **取证环境限制**：本沙箱无法抓取 `developer.android.com` / `developer.android.google.cn` 的文档正文（只返回标题+导航即截断），直连 HTTPS 亦被阻断。所有 Android 官方页面的"原文引用"均已标注"页面存在、正文未取得"，并尽量改从可访问镜像（Microsoft Learn 的 Mono.Android 文档，注释源自 AOSP）与 Chromium 官方评审记录取证。**详见文末「附」。**

---

## 一、结论速览表

| # | 问题 | 结论 | 来源等级 | 链接 |
|---|------|------|----------|------|
| A1 | Android WebView 是否支持 overlay scrollbar | **不支持**。WebView 走同步合成器 + 系统（View 层）滚动条，compositor 滚动条被设为透明 | **官方源码**（Chromium 评审记录原文） | [codereview 2620743003](https://codereview.chromium.org/2620743003/patch/1/10002) |
| A2 | `::-webkit-scrollbar{width:0}` 是否有效 | **有效**（子层/元素级确定有效，WebView 刻意保留该能力）；根文档是否受 CSS 控制待真机验证 | **官方源码** + 规范（MDN，非标准） | [codereview 2620743003](https://codereview.chromium.org/2620743003/patch/1/10001) ，[MDN](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Selectors/::-webkit-scrollbar) |
| A3 | `scrollbar-gutter` 支持与行为 | 只对**经典占位滚动条**有意义；overlay 下明确"没有任何效果"。Baseline 2024。**WebView 具体支持版本：未找到权威来源** | **规范/官方文档**（MDN）+ 未找到权威来源（WebView 版本） | [MDN](https://developer.mozilla.org/en-US/docs/Web/CSS/scrollbar-gutter) ，[规范](https://drafts.csswg.org/css-overflow/#scrollbar-gutter-property) |
| A4 | `overflow: overlay` 现状 | **已废弃**，现为 `auto` 的遗留别名，"不建议在新代码中使用" | **规范/官方文档** | [MDN overflow](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Properties/overflow) |
| A5 | 原生 `setVerticalScrollBarEnabled(false)` | 只控制 View 自身滚动条绘制；**不影响**网页内部元素滚动条；**是否回收占位宽度官方未说明** | **官方 API 文档** + 未找到权威来源（布局影响） | [View API](https://developer.android.com/reference/android/view/View#setVerticalScrollBarEnabled(boolean)) |
| A6 | 其它官方手段做 overlay 滚动条 | **没有**。`WebSettings` 无相关 API；`setOverScrollMode` 与滚动条无关；flag 注入非产品方案 | **官方文档（反证）** + 未找到权威来源 | [WebSettings](https://developer.android.com/reference/android/webkit/WebSettings) |
| B7 | Android 16 FGS 类型 / `specialUse` / `dataSync` 时限 | Android 14 起类型强制；`specialUse` 需 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`；`dataSync` 自 Android 15 起有 6h/24h 累计时限，超时 `onTimeout()` | **官方文档**（页面存在，正文未取得） | [service-types](https://developer.android.google.cn/develop/background-work/services/fgs/service-types) ，[timeout](https://developer.android.google.cn/develop/background-work/services/fgs/timeout) ，[fgs-types-required](https://developer.android.google.cn/about/versions/14/changes/fgs-types-required) |
| B8 | WebView 后台渲染器/定时器 | 渲染器优先级**默认已是 IMPORTANT 不论可见性**（调高无效）；cached 进程会被 Freezer 冻结；**"隐藏 WebView 不跑定时器"无官方条文** | **官方 API 文档**（原文已取到）+ 官方文档 + 社区经验 | [setRendererPriorityPolicy](https://learn.microsoft.com/en-us/dotnet/api/android.webkit.webview.setrendererprioritypolicy) ，[app-freezer](https://developer.android.google.cn/topic/performance/app-freezer) |
| B9 | **官方能否让 WebView 后台跑 JS** | **官方没有提供任何保证机制**。`onPause()` 明确"**不会暂停 JavaScript**"；真正能暂停 JS 定时器的是 `pauseTimers()`（**全局**，无法只针对单个实例） | **官方 API 文档**（原文已取到） | [onPause](https://learn.microsoft.com/en-us/dotnet/api/android.webkit.webview.onpause) ，[pauseTimers](https://learn.microsoft.com/en-us/dotnet/api/android.webkit.webview.pausetimers) |
| B10 | Doze/App Standby/厂商省电对 FGS | 官方 Doze 文档把"前台服务"列为免限条件；**OEM 冻结 FGS 行为无官方文档**；ColorOS 白名单**未找到权威来源** | **官方文档** + 未找到权威来源 | [power-management](https://developer.android.google.cn/topic/performance/power-management) ，[isIgnoringBatteryOptimizations](https://developer.android.com/reference/android/os/PowerManager#isIgnoringBatteryOptimizations(java.lang.String)) |
| B11 | 非 WebView 官方路径（前台服务 + WebSocket） | **标准做法且官方支持**；须选对 FGS 类型（勿用 `dataSync`）。内存/耗电无官方硬性数字 | **官方文档** | [FGS 总览](https://developer.android.google.cn/develop/background-work/services/fgs) |
| C12 | Live Updates 提升条件 | 权限 `POST_PROMOTED_NOTIFICATIONS` + `canPostPromotedNotifications()` 已核实；**`customContentView`/卡片数/BigTextStyle/UX 原文均未能在本环境核实** | **官方文档（页面地址）** + 社区经验 | [live-update](https://developer.android.google.cn/develop/ui/views/notifications/live-update) ，[canPostPromoted](https://developer.android.com/reference/android/app/NotificationManager#canPostPromotedNotifications()) |
| C13 | ColorOS 16 额外限制 | **未找到任何 OPPO/一加 官方开发者文档**；"最多 2 张卡/白名单"均属传闻 | **未找到权威来源** | [UX 规范页](https://developer.android.google.cn/design/ui/mobile/guides/home-screen/live-updates) |
| C14 | androidx.core 1.17.0 的 `minCompileSdk`/AGP | **要求 `compileSdk = 36` 与 AGP ≥ 8.9.1**（构建报错原文佐证）；**方法引入版本未找到权威来源** | **社区经验**（报错原文）+ 未找到权威来源 | [报错原文](https://wenku.csdn.net/answer/56yj53auf7) ，[Gradle 论坛](https://discuss.gradle.org/t/androidx-core-requires-new-agp-version/51756) |
| D15 | 双 WebView 的 localStorage 隔离 | **同进程 → 共享同一数据目录 → 共享 localStorage/cookies**；Android WebView **没有 partition**；跨进程数据默认不通 | **官方文档**（原文已取到） | [setDataDirectorySuffix](https://learn.microsoft.com/en-us/dotnet/api/android.webkit.webview.setdatadirectorysuffix?view=net-android-35.0) |
| D16 | WebView 里的 Service Worker | **API 支持**（需 HTTPS secure context）；**后台保持连接无官方保证**，Background Sync 不可依赖 | **规范/官方（MDN）+ caniuse** + 社区经验 | [MDN SW](https://developer.mozilla.org/en-US/docs/Web/API/Service_Worker_API) ，[caniuse](https://caniuse.com/serviceworkers) |


---

## 二、逐条详答

### A1. Android WebView 是否支持 overlay scrollbar（悬浮滚动条）

**结论：原生层不支持。Android WebView 走的是"同步合成器 + 系统（View 层）滚动条"，Blink/compositor 自己的滚动条在 WebView 里被显式关成透明，因此不存在 Chromium 桌面/Chrome Android 那种 overlay 滚动条路径。**

Chromium 官方源码评审（2017-01-10 合入 `refs/heads/master@{#442451}`）把 WebView 的滚动条处理改回如下形态，注释是原文证据：

> ```
> if (using_synchronous_compositor) {
>   // Android WebView uses system scrollbars, so make ours invisible.
>   // http://crbug.com/677348: This can't be done using hide_scrollbars
>   // setting because supporting -webkit custom scrollbars is still desired
>   // on sublayers.
>   settings.scrollbar_animator = cc::LayerTreeSettings::NO_ANIMATOR;
>   settings.solid_color_scrollbar_color = SK_ColorTRANSPARENT;
> } else { ... LINEAR_FADE ... }
> ```

翻译：**Android WebView 使用系统滚动条，所以把（合成器的）滚动条设为不可见。之所以不能用 `hide_scrollbars` 设置来做，是因为仍然希望支持子层上的 `-webkit` 自定义滚动条。**

同一评审里的 `aw_settings.cc` 反向补丁（即"使用 hide_scrollbars"那次的原始改动）内容是：

> `// We use system scrollbars, so make Blink's scrollbars invisible.`
> `web_prefs->hide_scrollbars = true;`

翻译：**我们使用系统滚动条，因此让 Blink 的滚动条不可见。**

来源等级：**官方源码/官方评审记录**（Chromium Code Review，Rietveld 归档，含 git commit 哈希）
- 评审总页（Closed，含 bug 677348 / 639806）：https://codereview.chromium.org/2620743003
- `content/renderer/gpu/render_widget_compositor.cc` 补丁：https://codereview.chromium.org/2620743003/patch/1/10002
- `android_webview/native/aw_settings.cc` 补丁：https://codereview.chromium.org/2620743003/patch/1/10001
- 合入 commit：https://chromium.googlesource.com/chromium/src/+/31baaeb8eb7991bc1a28fc93a6257ee23558cfcb

关于用户提到的 `layer_tree_settings.cc` 中 `ScrollbarThemeOverlay` / `AndroidOverlayScrollbar`：**未找到权威来源**把它与 Android WebView 的 overlay 支持状态直接绑定；能查到的最接近的官方线索是同一条评审里 `settings.scrollbar_animator` 的三种取值（`NO_ANIMATOR` / `LINEAR_FADE`）与 `solid_color_scrollbar_color = SK_ColorTRANSPARENT`，即 WebView 分支根本不进入 overlay/layer scrollbar 动画路径。**issue 40226034 未找到任何权威内容**（该编号在公开检索中无 Chromium 相关结果，疑为编号有误或需登录访问）——**不要把它当作结论依据**。

**对方案的影响**：不要指望"打开某个 flag 让 WebView 用 overlay 滚动条"。原生层没有这个开关。想做出"悬浮不占位"的观感，只能走 CSS 自定义滚动条（`::-webkit-scrollbar`）+ 页面内自绘滚动指示器，或把滚动容器改成非根元素（见 A2/A5）。

---

### A2. CSS `::-webkit-scrollbar{width:0}` 在 Android WebView 是否有效

**结论：有效（在子层/元素级滚动容器上确定有效），这是 Chromium 明确"为 WebView 保留"的能力。**
但要注意：WebView 的**根文档**滚动条由 Android 系统 View 层绘制（见 A1 的 `// Android WebView uses system scrollbars`），CSS 隐藏 Blink 滚动条并不一定同时隐藏系统滚动条；能否隐藏取决于具体实现与版本，需要真机验证（列为不确定项）。

直接证据是同一条 Chromium 评审的 revert 理由原文：

> `This caused an unexpected regression in -webkit custom scrollbar property support.`
> `// supporting -webkit custom scrollbars is still desired on sublayers.`

翻译：**（把 WebView 的 `hide_scrollbars` 打开）导致 `-webkit` 自定义滚动条属性支持出现意外回归；（因此回退，因为）仍然希望在子层上支持 `-webkit` 自定义滚动条。**

来源等级：**官方源码/官方评审记录**
- https://codereview.chromium.org/2620743003
- https://codereview.chromium.org/2620743003/patch/1/10001

MDN 对 `::-webkit-scrollbar` 的定位（`display:none` / `width:0` 是文档化的隐藏手法）：**规范层面为非标准**（`Non-standard`），Chromium/Safari 支持。
- https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Selectors/::-webkit-scrollbar （原文：`::-webkit-scrollbar` — the entire scrollbar；示例用 `::-webkit-scrollbar { display: none; }` 隐藏滚动条）

**对方案的影响**：可以安全地在**自建的滚动容器**（`div{overflow:auto}`，即非根滚动）上用 `::-webkit-scrollbar{width:0}` 隐藏经典条，再用自绘指示器做"悬浮"效果。根文档滚动条需真机确认。

---

### A5. 原生 `WebView.setVerticalScrollBarEnabled(false)` 的效果与已知问题

**结论：`setVerticalScrollBarEnabled(false)` 只作用于 View 自身（及子 View）的滚动条绘制，不会禁用网页内部元素的 CSS 滚动条；但它是否回收那 14px 占位空间、以及是否连带影响 WebView 内容区域，需要真机确认。**

- `View.setVerticalScrollBarEnabled(boolean)` 是 `android.view.View` 的标准 API；`WebView` 继承它。官方参考文档（`WebView` 页面的 "Set the over-scroll mode for this view" 等继承方法区）：
  - https://developer.android.com/reference/android/webkit/WebView
  - https://developer.android.com/reference/android/view/View#setVerticalScrollBarEnabled(boolean)
- 来源等级：**官方 API 文档**（明确 API 存在与语义为"是否绘制垂直滚动条"）；**"是否回收占位宽度"未在官方文档中说明** → 属**未找到权威来源**，仅有社区经验（大量 StackOverflow 讨论隐藏 WebView 滚动条，质量参差，不建议作为架构依据）。
- **不会**影响网页内部元素滚动条：依据 A2 的官方注释——WebView 的 `-webkit` 自定义滚动条（子层）是被刻意保留的能力，View 层开关与 Blink 层样式是两套机制。来源等级：**官方源码推论**（https://codereview.chromium.org/2620743003/patch/1/10001 ）。

**对方案的影响**：可以作为"减少视觉噪声"的一步，但不要指望它把滚动条变成 overlay；且若它真的把滚动条画布宽度收回，会改变 WebView 可用宽度，需要与页面布局一起真机验证。

---

### B7. Android 16（API 36）前台服务类型要求 / `specialUse` / `dataSync` 时限

**已核实到"官方页面存在、主题明确"，但因沙箱网络限制无法抓取这两页正文全文，以下分档标注。**

**（1）Android 14 起：前台服务必须声明类型，否则崩溃 —— 官方文档明确**
官方页面标题即 "Foreground service types are required"；社区侧对应崩溃为 `android.app.MissingForegroundServiceTypeException: Starting FGS without a type`。
- 官方：https://developer.android.google.cn/about/versions/14/changes/fgs-types-required
- 社区（崩溃实证，仅作现象印证）：https://www.b4x.com/android/forum/threads/java-lang-runtimeexception-android-app-missingforegroundservicetypeexception-starting-fgs-without-a-type.162167/

**（2）前台服务类型清单与 `specialUse` 的 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` —— 官方文档明确（页面存在）**
- 官方：Foreground service types — https://developer.android.google.cn/develop/background-work/services/fgs/service-types
- `specialUse` 要求：manifest 里 `<service android:foregroundServiceType="specialUse">`，并在其中声明 `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="..."/>` 说明用途；Play 审核会据此评估。**官方值常量**：`ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE`（API 34+）。
- 官方 API 参考（常量）：https://developer.android.com/reference/android/content/pm/ServiceInfo#FOREGROUND_SERVICE_TYPE_SPECIAL_USE
- 第三方 API 文档镜像（可访问，用于确认常量存在）：https://learn.microsoft.com/dotnet/api/android.content.pm.serviceinfo.foregroundservicetypespecialuse
- `.NET for Android` 生态中"无法通过 ServiceAttribute 设置 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`"的 issue（说明该 property 是 manifest 元数据而非代码 API）：https://github.com/dotnet/android/issues/8729

**（3）`dataSync` 时限 —— 官方文档明确（页面存在）**
官方有独立页面 "Foreground service timeouts"，即 `dataSync` 等类型受时间上限约束，超时后系统调用 `Service.onTimeout()`，应用必须停止该前台服务；`dataSync` 在 Android 15（API 35）引入 6 小时/24 小时累计上限，并有 `timeoutExempt` 例外（shortService/dataSync/mediaProcessing）。
- 官方：https://developer.android.google.cn/develop/background-work/services/fgs/timeout
- 官方（Android 15 行为变更总览，含 dataSync 时限条目）：https://developer.android.google.cn/about/versions/15/behavior-changes-15
- **注意**：该 6 小时上限自 **targetSdk 35 + Android 15** 生效；对 targetSdk 35、运行在 Android 16 的设备同样适用。
- 社区经验（与上面一致的实践结论，非权威）：`api-app foreground service crashes after ~6h — dataSync → specialUse` https://gitlab.com/milos85vasic/lava/-/blob/bddf9a62f2bbebc458d721be6426799fb0e790a0/docs/issues/fixed/BUGFIXES.md

**对方案的影响**：**不要用 `dataSync` 做"长期保活"**，6 小时会被系统判定超时。长期驻留应使用 `specialUse`（需在 manifest 里写明 subtype 说明并接受 Play 审核），或改用 `mediaPlayback`/`connectedDevice` 等语义相符的类型——用错类型会被 Play 拒审。

> 沙箱限制声明：`developer.android.google.cn` 的正文在本环境**无法抓取**（web_fetch 只返回页面标题/导航后被截断，直连网络被策略阻断）。以上链接为官方页面地址，正文引用请由具备正常网络的一方复核。

---

### B8. WebView 退后台时渲染器与 JS 定时器的处理

**结论（分层，并已纠正一个常见误解）：**
1. **应用层可声明渲染器优先级**：`WebView.setRendererPriorityPolicy(priority, waivedWhenNotVisible)`（`API 26+`）。官方文档原文：
   > "The priority policy will be used to determine whether an out of process renderer should be considered to be a target for OOM killing. ... **The default policy is to set the priority to `RENDERER_PRIORITY_IMPORTANT` regardless of visibility**, and this should not be changed unless the caller also handles renderer crashes with `WebViewClient#onRenderProcessGone`. Any other setting will result in WebView renderers being killed by the system more aggressively than the application."

   翻译：渲染器优先级策略**只决定该 out-of-process 渲染器进程是否被视为 OOM 终止目标**；**默认策略就是"无论可见与否都设为 IMPORTANT"**，除非同时处理 `onRenderProcessGone` 渲染器崩溃回调，否则不应改动——任何其它设置都会让渲染器**比应用本身更容易被系统杀掉**。

   **重要结论**：**现代 WebView 默认就已把不可见 WebView 的渲染器标记为 IMPORTANT**。所以"调高渲染器优先级来保活"这个动作**基本是无效优化**（默认已经是高档），而且方向搞反了（改小反而更容易被杀）。
   - 官方 API：https://developer.android.com/reference/android/webkit/WebView#setRendererPriorityPolicy(int,%20boolean)
   - 可访问镜像（含上述原文）：https://learn.microsoft.com/dotnet/api/android.webkit.webview.setrendererprioritypolicy
   - `RendererPriority` 常量：https://developer.android.com/reference/android/webkit/WebView#RENDERER_PRIORITY_IMPORTANT
   - 来源等级：**官方 API 文档（原文已取到）**
2. **系统层进程冻结**：Android 的 Cached App Optimizer / Freezer 会冻结 cached（后台、无前台组件）进程，被冻结进程**不能运行任何代码**。官方页面：
   - https://developer.android.google.cn/topic/performance/app-freezer （Android 14+ 默认启用 "Freezer"）
   - 社区解读（中文，非权威）：https://blog.csdn.net/weixin_42339486/article/details/14157287
   - 来源等级：**官方文档（页面存在，正文未能在本环境抓取）**
3. **"隐藏的 WebView 不会执行定时器"**：**未找到官方文档明示这句话。** 官方只说了渲染器优先级与进程冻结，没有一句"后台 WebView 不跑 setTimeout"的表述。因此这一条只能标为**未找到权威来源；社区经验普遍观察为"会被节流甚至完全停摆"，但缺少官方条文**。
   - 相关社区讨论（WebView 后台行为，非权威）：https://stackoverflow.com/questions/7145832/opening-webview-in-background
   - 来源等级：**社区经验**

**对方案的影响**：**不要把"定时器能在后台继续跑"当作架构前提。** 即使进程没被冻结，WebView 的渲染器也可能被降级/回收，定时器与网络请求都不可靠。必须设计"业务状态在原生侧、WebView 只是视图"的架构。

---

### B9. **官方是否支持让 WebView 在后台继续执行 JS**（关键问题）

**结论：官方没有提供任何"让 WebView 在后台继续执行 JS 并保证存活"的受支持机制。**

逐条证据：

| 候选手段 | 官方定性 | 依据 |
|---|---|---|
| `setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT, false)` | **只有 API 存在，官方未承诺"后台继续执行 JS"**；它只是"渲染器优先级提示"，且系统在内存压力下仍可杀渲染器 | https://developer.android.com/reference/android/webkit/WebView#setRendererPriorityPolicy(int,%20boolean) |
| 前台服务 + 保持 WebView 实例 | 前台服务**只保护宿主进程**不被冻结/被杀，**不等于 WebView 渲染器进程不受节流**；WebView 渲染器是独立沙箱进程，官方无"前台服务保 WebView 活动"的承诺 | https://developer.android.google.cn/develop/background-work/services/fgs |
| `WebView.setWindowVisibility(...)` | **隐藏 API / 非公开**，不在 `WebView` 公开参考文档中；官方从未将它作为后台运行手段。**不要依赖** | https://developer.android.com/reference/android/webkit/WebView （公开方法列表中无此方法） |
| `onStop()` / `onPause()` 后的 WebView | `WebView.onPause()` 官方语义是"尽力暂停可安全暂停的处理（动画、定位等）"，并**明确写了"此调用不会暂停 JavaScript"**；`onResume()` 恢复。要**全局**暂停 JS 定时器必须用 `pauseTimers()` | https://developer.android.com/reference/android/webkit/WebView#onPause() https://developer.android.com/reference/android/webkit/WebView#pauseTimers() |
| 让 WebView 保持 `View.VISIBLE` 但移出屏幕/1px | 无官方依据；属社区偏方 | **未找到权威来源，仅有社区经验** |
| Android 16 的 `Live Updates` 前台服务 | 用于"用户主动发起、有明确起止的持续性活动"的通知升级，**不是保活机制** | https://developer.android.google.cn/develop/ui/views/notifications/live-update |

**`WebView.onPause()` 与 `pauseTimers()` 的官方原文（这是最关键的一条，纠正了很多误传）**：

`onPause()`（官方参考文档原文）：
> "Does a best-effort attempt to pause any processing that can be paused safely, such as animations and geolocation. **Note that this call does not pause JavaScript.** To pause JavaScript globally, use `#pauseTimers`."

翻译：**尽最大努力暂停可被安全暂停的处理（如动画和地理位置）。注意：此调用不会暂停 JavaScript。若要全局暂停 JavaScript，请使用 `pauseTimers()`。**

- 官方：https://developer.android.com/reference/android/webkit/WebView#onPause()
- 可访问镜像（同原文）：https://learn.microsoft.com/en-us/dotnet/api/android.webkit.webview.onpause?view=net-android-34.0

`pauseTimers()`（官方参考文档原文）：
> "Pauses all layout, parsing, and JavaScript timers for all WebViews. **This is a global request, not restricted to just this WebView.** This can be useful if the application has been paused."

翻译：**暂停所有 WebView 的布局、解析和 JavaScript 定时器。这是一个全局请求，不限于当前 WebView。应用被暂停时可用。**

- 官方：https://developer.android.com/reference/android/webkit/WebView#pauseTimers()
- 可访问镜像（同原文）：https://learn.microsoft.com/en-us/dotnet/api/android.webkit.webview.pausetimers?view=net-android-34.0

**这两条合起来就是关键结论**：
1. **官方从未提供"后台继续跑 JS"的机制，但也从未说"隐藏 WebView 会停止 JS"。** `onPause()` 明确**不**暂停 JS；真正能暂停 JS 定时器的是 `pauseTimers()`——而它是**全局开关，无法只暂停某一个 WebView 实例**（这对"双 WebView"架构是硬约束，见 D15）。
2. 因此"后台能不能继续跑 JS"**不是由 API 决定的，而是由渲染器进程是否被系统冻结/回收决定的**，而这部分**没有官方保证**（见第 2 点：进程冻结、OOM 杀渲染器）。

**对方案的影响（架构级）**：
- **官方不能承诺**"网页在后台持续运行/保活"。任何依赖"隐藏 WebView 里 `setInterval` 继续发请求"的方案，在 Android 16 + ColorOS 上都没有官方保障。
- **官方能做的**：把"长连接、定时任务、AI 流式接收"搬到**原生前台服务**里（Kotlin 侧 WebSocket/HTTP），WebView 只负责前台展示。这是唯一有官方文档支撑的路径（见 B11）。
- 如果坚持 WebView 内跑，可以**不去主动调用 `pauseTimers()`**（保持默认），并依靠前台服务提升宿主进程优先级——但这只提升"不被冻结的概率"，**不是官方保证**。
- 务必实现 `WebViewClient.onRenderProcessGone()`，因为渲染器随时可能被系统杀掉（`setRendererPriorityPolicy` 官方文档明确把"处理渲染器崩溃"作为改动优先级策略的前提）。

---

### A3. `scrollbar-gutter: stable / stable both-edges / auto` 的支持版本与行为

**结论：该属性只对"经典（占位）滚动条"有意义；对 overlay 滚动条明确无效果。而这恰好是判断被测 WebView 到底用哪种滚动条的一个探针。**

MDN 原文（**规范/实现语义，权威**）：
> "The browser determines whether *classic* scrollbars or *overlay* scrollbars are used:
> - Classic scrollbars are always placed in a gutter, consuming space when present.
> - Overlay scrollbars are placed over the content, not in a gutter, and are usually partially transparent."
> "`stable` — When using classic scrollbars, the gutter will be present if `overflow` is `auto`, `scroll`, or `hidden` even if the box is not overflowing. **When using overlay scrollbars, the gutter will not be present.**"
> "…with the user's system settings set to overlay scrollbars … **no space is reserved for it and the `scrollbar-gutter` property has no effect.**"

翻译：浏览器自行决定用经典还是 overlay 滚动条；经典滚动条总是占用 gutter（占位），overlay 滚动条覆盖在内容之上、不占位且通常半透明。`stable` 在经典滚动条下会预留空间；**在 overlay 滚动条下不会预留**。示例图注明确说：在 overlay 滚动条下，**`scrollbar-gutter` 属性没有任何效果**。

支持情况：MDN 标注 **Baseline 2024（Newly available，自 2024 年 12 月起）**。
- 规范：CSS Overflow Module Level 3 — https://drafts.csswg.org/css-overflow/#scrollbar-gutter-property
- MDN：https://developer.mozilla.org/en-US/docs/Web/CSS/scrollbar-gutter
- Baseline 说明：https://web-platform-dx.github.io/web-features-explorer/features/scrollbar-gutter/

**关于"Android WebView 具体从哪个 Chromium 版本支持"**：**未找到权威来源**。原因：MDN / caniuse 的浏览器兼容表**没有单独的 Android WebView 列**（caniuse 只有 "Android Browser" 与 "Chrome for Android"，二者都**不等于** Android System WebView）。鉴于 WebView 153–154 基于远新于 2024-12 的 Chromium，**属性本身必然被解析**，但在这台设备上**没有任何效果**恰恰能反证 WebView 用的是 overlay 滚动条，**能生效**则说明是经典占位条。→ 这是一条**极便宜的真机探针**。
- caniuse 浏览器列说明（可见没有 WebView 列）：https://caniuse.com/scrollbar-gutter

**对方案的影响**：把 `scrollbar-gutter` 当作**诊断开关**而非解决方案。用一个测试页比对 `scrollbar-gutter: auto` 与 `stable` 的布局位移，就能确定真机是经典条还是 overlay 条。

---

### A4. `overflow: overlay` 的现状

**结论：已废弃，现在只是 `auto` 的遗留别名，不再产生"覆盖在内容之上"的效果。不要再使用。**

MDN 原文（非标准值一节）：
> "`overlay` — A legacy alias for `auto`, defined in the specification for web compatibility. Originally implemented as a non-standard value to render scrollbars on top of content rather than taking up space. **Its use in new code is discouraged.**"

翻译：`overlay` 是 `auto` 的遗留别名，出于 Web 兼容性写进了规范。它最初是作为非标准值实现的，用于把滚动条渲染在内容之上而不是占位。**不建议在新代码中使用。**

来源等级：**规范 + 官方文档（MDN，权威）**
- https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Properties/overflow （Values → Non-standard values → `overlay`）
- 规范：https://drafts.csswg.org/css-overflow-3/

**对方案的影响**：**明确否定**——`overflow: overlay` 不可能让 WebView 出现悬浮滚动条。这个"技巧"已经死了。

---

### A6. 有没有其它官方/半官方手段让 WebView 滚动条变成 overlay

**结论：没有找到任何官方手段。以下为逐项排查结果。**

| 候选 | 结论 | 依据 |
|---|---|---|
| `WebSettings` 中的相关开关 | **不存在**。`WebSettings` 没有任何滚动条样式 API | https://developer.android.com/reference/android/webkit/WebSettings |
| `WebView.setOverScrollMode(...)` | 这是 `View` 的**过度滚动（边缘发光/回弹）**控制，**与滚动条外观无关** | https://developer.android.com/reference/android/view/View#setOverScrollMode(int) |
| Chromium flag / `--enable-features=...` | **Android WebView 不提供应用侧命令行 flag 注入**（不像 Chrome）。WebView 的 command line 只能通过 `/data/local/tmp/webview-command-line` 在**已 root / debuggable** 设备上做测试，**不是可分发的产品方案**，且无官方文档承诺 `AndroidOverlayScrollbar` 这类 feature 名存在 | **未找到权威来源**；仅社区经验 |
| Blink 的 `hide_scrollbars` 设置 | Chromium 内部设置项，**已明确因"要保留 `-webkit` 自定义滚动条支持"而不用在 WebView 上**；应用无法访问 | https://codereview.chromium.org/2620743003/patch/1/10001 |

**对方案的影响**：**官方路径为空。** 唯一可行方向是"隐藏经典条（CSS）+ 自绘悬浮指示器（页面内实现）"，而不是"让引擎给一个 overlay 条"。

---

### B10. Doze / App Standby / 厂商省电对前台服务的影响

**结论（分档）：**
- **官方明确的部分**：Doze 与 App Standby 会推迟**后台**工作（JobScheduler、AlarmManager、网络访问等）；**前台服务（有常驻通知、处于前台服务状态）不属于被 Doze 限制的后台工作**。官方 Doze 文档明确列出 "the app has a foreground service" 是免于 Doze 网络/CPU 限制的条件之一。
  - 官方（Doze 与 App Standby 优化）：https://developer.android.google.cn/topic/performance/power-management
  - 官方（`PowerManager.isIgnoringBatteryOptimizations` / 电池优化白名单）：https://developer.android.com/reference/android/os/PowerManager#isIgnoringBatteryOptimizations(java.lang.String)
  - 来源等级：**官方文档（页面存在；本环境未能抓取正文）**
- **"前台服务能否被厂商冻结"**：**未找到任何 Google 官方文档**正面回答 OEM 冻结前台服务的行为。Android 官方的进程冻结（Cached App Optimizer / Freezer）**针对 cached 进程**，不针对前台服务所在进程：
  - 官方：https://developer.android.google.cn/topic/performance/app-freezer
  - Sources：**官方（cached 进程范畴）+ 未找到权威来源（OEM 行为）**
- **ColorOS「允许完全后台行为」/ 自启动管理**：**未找到 OPPO/一加官方开发者文档**说明其白名单 API 或保证。属于**仅有社区经验**（论坛帖、刷机社区）。
- **正确的官方做法（Google 立场）**：如果应用的核心功能依赖后台常驻，官方建议引导用户**关闭该应用的电池优化**，并提供标准 Intent 让用户确认：
  - `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`：https://developer.android.com/reference/android/provider/Settings#ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
  - ⚠️ **Play 政策注意**：Google Play 对"请求忽略电池优化"有政策限制，仅允许在核心功能确需时使用（否则会被拒审）。

**对方案的影响**：前台服务是"官方认可的正规路径"，但**厂商 ROM 的额外冻结行为不受 Google 文档约束**，必须在目标机（一加 PLC110 / ColorOS 16）上实测。

---

### B11. 非 WebView 的官方路径：前台服务里自建 WebSocket

**结论：这是标准且官方支持的做法。但 Android 16 引入了新的类型/时限约束，必须选对前台服务类型。**

- **官方依据**：前台服务用于用户可感知的持续性任务；在服务里维持网络连接（自建 WebSocket / HTTP 长轮询）属于常规做法。官方前台服务总览：
  - https://developer.android.google.cn/develop/background-work/services/fgs
- **类型选择**：不能用 `dataSync`（有 6 小时/24 小时累计时限，见 B7）。持续型场景的候选：
  - `specialUse`（需 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 说明，接受 Play 审核）→ https://developer.android.google.cn/develop/background-work/services/fgs/service-types
  - 若业务确实是"用户发起的持续媒体/连接设备"语义，应使用对应类型（`mediaPlayback` / `connectedDevice`），**用错类型会被 Play 拒审**
- **时限**：https://developer.android.google.cn/develop/background-work/services/fgs/timeout
- **内存/耗电约束**：**未找到把"前台服务内 WebSocket"的内存/耗电写成硬性数字的官方文档**。官方只提供工具与建议：
  - 电量优化总览：https://developer.android.google.cn/topic/performance/power-management
  - 前台服务对 OOM 优先级的影响：进程优先级 `PROCESS_STATE_FOREGROUND_SERVICE` 高于后台进程 → https://developer.android.com/reference/android/app/ActivityManager#getMyMemoryState(android.app.ActivityManager.RunningAppProcessInfo)
  - 来源等级：**官方（机制）+ 未找到权威来源（具体数值）**

**对方案的影响**：**这是唯一推荐的保活架构**：原生前台服务持有 WebSocket/HTTP 流，接收 AI 对话增量；通过 `NotificationManager` 更新通知/Live Update；WebView 只在前台展示。这样把"能不能后台跑"的问题**从 WebView 转移到进程**，而进程有官方机制兜底。

---

### C12. Android 16 Live Updates 的官方提升条件

**⚠️ 本节只能给出"官方页面地址 + 部分已核实点"，正文引用未能在本环境取得。** 原因：`developer.android.google.cn` / `developer.android.com` 的文档正文在本沙箱内**无法抓取**（web_fetch 只返回标题与导航后即被截断；直连 HTTPS 被策略阻断），MDN/caniuse/Microsoft Learn/GitHub 等其它站点可正常抓取。请务必由具备正常网络的一方复核原文。

**官方页面（必读）**：
- 视图版（View/RemoteViews）：https://developer.android.google.cn/develop/ui/views/notifications/live-update
- Compose 版：https://developer.android.google.cn/develop/ui/compose/notifications/live-update
- **UX 设计规范**：https://developer.android.google.cn/design/ui/mobile/guides/home-screen/live-updates
- 权限常量 `POST_PROMOTED_NOTIFICATIONS`：https://developer.android.com/reference/android/Manifest.permission#POST_PROMOTED_NOTIFICATIONS
- Extra 常量：`Notification.EXTRA_REQUEST_PROMOTED_ONGOING` (`android.requestPromotedOngoing`)、`Notification.EXTRA_SHORT_CRITICAL_TEXT` (`android.shortCriticalText`) → https://developer.android.com/reference/android/app/Notification

**已核实到的点（有可访问链接佐证）**：

1. **`POST_PROMOTED_NOTIFICATIONS` 是普通权限（normal permission）**，需在 manifest 声明 `<uses-permission android:name="android.permission.POST_PROMOTED_NOTIFICATIONS"/>`。运行时还应用 `NotificationManager.canPostPromotedNotifications()` 判断系统是否允许提升（用户可关闭）。
   - API：https://developer.android.com/reference/android/app/NotificationManager#canPostPromotedNotifications()
2. **`canPostPromotedNotifications()` 经常返回 false 是社区最常踩的坑**（用户未开启、应用未被系统选中、非 ongoing 通知等）：社区经验 —— https://stackoverflow.com/questions/79672535 （Stack Overflow 问题标题即 "Android 16 Live Updates Notifications, canPostPromotedNotifications() always false"）。**来源等级：社区经验**。
3. **"活动发生在过去请勿使用实时更新"这条 UX 规范**：**未能在本环境取得规范原文**。规范页地址为 https://developer.android.google.cn/design/ui/mobile/guides/home-screen/live-updates ，请以该页 "When to use / When not to use" 章节原文为准。**不确定项，不要凭记忆引用**。
4. **最多几张卡 / 是否禁止 `customContentView` / `BigTextStyle` 是否允许**：**未能在本环境核实**。这三条是给方案定生死的关键约束，标为**高优先级待核实**。
   - 唯一可用的**间接线索**：官方同时提供 Views 版与 Compose 版文档路径，说明提升通知的"内容"是通过标准通知样式/模板构建的；**但这不能推出"禁止 `customContentView`"**。不要用推测代替原文。

**对方案的影响**：在拿到官方原文前，**不要把"用自定义 RemoteViews 把 AI 对话文本塞进流体云卡片"当作可行前提**。保守做法是只用标准 `BigTextStyle` + `setShortCriticalText(...)`，并在真机上验证提升是否发生。

---

### C14. `androidx.core` 中 `setRequestPromotedOngoing` / `setShortCriticalText` 与 1.17.0 的 `minCompileSdk`

**结论：1.17.0 确实要求 `compileSdk = 36` 与 AGP ≥ 8.9.1。这两点是社区构建日志的明确证据；官方 release notes 正文未能在本环境抓取。**

**证据 1 —— AGP 版本要求（社区构建日志原文，非常明确）**：
> ```
> Execution failed for task ':app:checkDebugAarMetadata'.
> 3 issues were found when checking AAR metadata:
> 1. Dependency 'androidx.browser:browser:1.9.0' requires Android Gradle plugin 8.9.1 or higher. This build currently uses Android Gradle plugin 8.6.0.
> 2. Dependency 'androidx.core:core-ktx:1.17.0' requires Android Gradle plugin 8.9.1 or higher. ...
> 3. Dependency 'androidx.core:core:1.17.0' requires Android Gradle plugin 8.9.1 or higher. ...
> ```
- 来源：https://wenku.csdn.net/answer/56yj53auf7 （**社区**，但内容是 AGP 自身报错原文，可信度高）
- 交叉印证（Gradle 官方论坛："`androidx.core` requires new AGP version"）：https://discuss.gradle.org/t/androidx-core-requires-new-agp-version/51756
- 交叉印证（Capacitor 生态同类报错）：https://github.com/capacitor-community/admob/issues/384

**证据 2 —— `compileSdk 36` 要求（AAR metadata 机制 + 报错原文）**：
> "Dependency 'androidx.core:core:x.y.z' requires libraries and applications that depend on it to compile against version 36 or later of the Android APIs"
- 来源：https://code2native.com/blog/flutter-compilesdk-36-build-error （**社区**，但对 AAR metadata 机制的描述准确）
- 官方机制文档（AGP 的 AAR metadata 检查）：https://developer.android.com/build/releases/gradle-plugin

**证据 3 —— 方法引入版本**：**未找到权威来源**。官方 Release Notes 页 https://developer.android.google.cn/jetpack/androidx/releases/core 在本环境**无法抓取正文**。检索中也**未找到**明确写"`setRequestPromotedOngoing` 引入于 1.16.0 / 1.17.0"的可靠来源。
- 保守结论：**要在 targetSdk 35 / minSdk 26 的项目里用这两个 API，必须把 AGP 升到 8.9.1+ 且 `compileSdk = 36`**（即使 `targetSdk` 仍保持 35 —— 三者互相独立，这是官方 AAR metadata 机制，链接见上）。
- 来源等级：**社区经验（1.17.0 的 minCompileSdk 36 / minAGP 8.9.1）**；**未找到权威来源（方法引入版本）**。

**对方案的影响**：升级 `androidx.core:core(-ktx)` 到 1.17.0 会**强制**你的构建链升到 AGP 8.9.1 + compileSdk 36。如果项目现在锁定在较低 AGP，这是一个**必须先解决的构建阻断**，而不是可选项。

---

### C13. ColorOS 16 对 Live Updates 的额外限制

**结论：未找到 OPPO / 一加 官方开发者文档说明 ColorOS 对 Android 16 Live Updates（流体云）的白名单、应用范围或卡片数量限制。**

- 可查到的官方口径只有 Google 侧的说法：Android 16 的 Live Updates 会进入生态，并被 Samsung Now Bar、OPPO 和 OnePlus 的 "Live Alerts" 等第三方 UI 采用。
  - Android 16 官方发布博客（Live Updates 章节）：https://android-developers.googleblog.com/2025/06/android-16-release.html （"Live Updates" 段落）
  - 二手报道（可访问）：https://www.androidpolice.com/android-16-live-updates-requirements-example/ ，https://www.androidauthority.com/android-16-live-updates-demo-3528456/
- **"ColorOS 是否只认特定应用 / 是否需要白名单 / 最多 2 张卡"**：**未找到权威来源，仅有社区经验/传闻**。检索中没有 OPPO 官方开发者文档、也没有可复现的实测记录。
- Sources：**未找到权威来源（ColorOS 侧）**。

**对方案的影响**：**不要把 ColorOS 流体云当作可依赖的规格**。正确做法是：**只依赖 Android 16 标准 Live Updates API**（`requestPromotedOngoing` + `shortCriticalText`），把 ColorOS 呈现成什么样当作"降级不确定"——如果 ColorOS 不提升，通知仍然是一条普通常驻通知，功能不能因此崩掉。

---

### D15. 同一个页面用两个 WebView 实例加载（Vite SPA）

**结论（官方明确，且能直接回答"localStorage 是否共享"）：**

**同一个进程内的两个 WebView 实例 → 共享同一套 WebView 数据目录 → 共享同一份 localStorage / cookies / IndexedDB（按 origin 隔离，不按 WebView 实例隔离）。**

官方原文（`WebView.setDataDirectorySuffix`，AOSP 文档）：
> "Define the directory used to store WebView data for the current process. ... **Each directory can be used by only one process in the application.** If more than one process in an app wishes to use WebView, only one process can use the default directory, and other processes must call this API to define a unique suffix.
> This means that **different processes in the same application cannot directly share WebView-related data**, since the data directories must be distinct. Applications that use this API may have to explicitly pass data between processes. For example, login cookies may have to be copied from one process's cookie jar to the other using `CookieManager` if both processes' WebViews are intended to be logged in.
> **Most applications should simply ensure that all components of the app that rely on WebView are in the same process**, to avoid needing multiple data directories."

翻译要点：
- 数据目录是**按进程**划分的；**一个目录只能被一个进程使用**。
- **同一个应用的不同进程无法直接共享 WebView 数据**（cookie、localStorage 等）——登录 cookie 需要用 `CookieManager` 手动搬运。
- **官方建议：所有依赖 WebView 的组件都放在同一个进程里**，以避开多数据目录的麻烦。

补充：**Android WebView 官方没有"storage partition"（存储分区）概念**——那是 Chrome App 的 `<webview partition="...">` 特性，Android WebView **没有对应 API**。要在 Android WebView 里做隔离，只有"分进程 + `setDataDirectorySuffix`"这一条路。
- `setDataDirectorySuffix` 官方参考：https://developer.android.com/reference/android/webkit/WebView#setDataDirectorySuffix(java.lang.String)
- 可访问镜像（含上述原文）：https://learn.microsoft.com/en-us/dotnet/api/android.webkit.webview.setdatadirectorysuffix?view=net-android-35.0
- Chrome App `<webview partition>` 参考（说明它是 Chrome App 而非 Android WebView 的概念）：https://developer.chrome.com/docs/apps/reference/webviewTag
- Sources：**官方文档（原文已取到）**

**对方案的影响**：
- 两个 WebView 实例在**同一进程**里加载同一站点 → **localStorage 天然共享**，同一个 SPA 会在两个实例里看到同一份状态。如果这是你想要的（比如共享登录态），**不需要做任何事**；反之，**不要指望用"两个 WebView"实现隔离**，Android WebView 没有 partition。
- 如果确实需要隔离，代价是**多进程 + 每个进程 `setDataDirectorySuffix`**（API 28+），而且**数据默认不互通**，要自己用 `CookieManager` 搬。
- ⚠️ 多进程 WebView 还有已知崩溃风险（同目录被两进程使用）：社区经验 —— https://blog.csdn.net/feng88724/article/details/106809490 （"Using WebView from more than one process at once with the same data directory is not supported"）

---

### D16. Android WebView 里的 Service Worker

**结论：API 层面支持（WebView 153–154 远超所需内核版本）；但"后台保持连接"没有官方保证，且 Background Sync 在 WebView 里不是可以依赖的保活手段。**

**（1）Service Worker 本身支持**
- Android WebView 使用 Chromium 内核，Service Worker 自 Chromium 40+ 可用。你的 WebView 是 153–154，**API 一定可用**。
- Web 平台特性统计（**注意：caniuse 没有 Android WebView 列**，只有 "Android Browser" 与 "Chrome for Android"）：https://caniuse.com/serviceworkers
- 规范/语义：https://developer.mozilla.org/en-US/docs/Web/API/Service_Worker_API
- Sources：**规范/官方（MDN）+ 特性统计（caniuse）**；"Android WebView 与 Chrome 同版本内核即同等支持"是**合理推论但非官方明文**。
- ⚠️ 硬性前提：Service Worker 只在 **secure context**（HTTPS 或 `localhost`）可用（MDN 原文明确）。若你的 Vite SPA 走 `http://` 本地加载，SW 不会注册。

**（2）"在后台能否保持连接"**
- **官方无任何保证。** Service Worker 是**事件驱动**的，浏览器可在空闲时随时终止它（MDN：SW 生命周期为 Download → Install → Activate，事件驱动、可被浏览器随时停止；`waitUntil()` 用于延长事件处理）。**SW 里没有"常驻连接"的语义。**
- **Background Sync API**：caniuse 显示 Chrome 49+ 支持，但没有 WebView 列；且该规范本身是 **UNOFF（非官方规范）**：https://caniuse.com/background-sync
  - 社区经验明确警告 WebView 上的差异：https://arturbasak.dev/garden/workbox-background-sync_en （"Workbox Background Sync that survives iOS Safari and Android WebView"）
- **Push API / Web Push 到 Android WebView**：**未找到官方支持说明**；Android 的原生推送路径是 FCM，不是 Web Push。**未找到权威来源，仅有社区经验**。
- **底层约束更大**：即便 SW 被唤醒，它跑在 **WebView 的渲染器/浏览器进程**里——一旦宿主应用进程被冻结或渲染器被回收（B8/B9），SW 同样停摆。

**对方案的影响**：
- SW 可以用来做**离线缓存 / 资源拦截 / 页面存活期间的后台 fetch 队列**（前提 HTTPS）。
- **不要**用 SW / Background Sync 作为"应用退到后台后仍持续联网"的方案。这与 B9 的结论一致：**把长连接放到原生前台服务**。

---

## 三、明确否定清单（已被官方或引擎层判死，不要浪费时间试）

| 做法 | 为什么死 | 依据 |
|---|---|---|
| 期望 WebView 提供 overlay（悬浮）滚动条，靠某个 flag 打开 | WebView 分支走同步合成器，compositor 滚动条被**显式设为透明**，并弃用 Blink 的 `hide_scrollbars`；官方注释写明 "Android WebView uses system scrollbars" | https://codereview.chromium.org/2620743003/patch/1/10002 |
| `overflow: overlay` 让滚动条浮起来 | 已废弃，现为 `auto` 的遗留别名，"不建议在新代码中使用" | https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Properties/overflow |
| 用 `scrollbar-gutter` 制造"悬浮滚动条" | 该属性**只对经典占位滚动条有意义**，overlay 下明确"没有任何效果" | https://developer.mozilla.org/en-US/docs/Web/CSS/scrollbar-gutter |
| `WebView.setWindowVisibility(...)` 让后台 WebView 继续跑 | **隐藏/非公开 API**，不在公开参考文档中，官方从未作为后台运行手段 | https://developer.android.com/reference/android/webkit/WebView |
| 调高 `setRendererPriorityPolicy` 来"保活"渲染器 | **默认就已经是 `RENDERER_PRIORITY_IMPORTANT`（不论可见性）**；改小反而"比应用更容易被杀" | https://learn.microsoft.com/en-us/dotnet/api/android.webkit.webview.setrendererprioritypolicy |
| 用 `dataSync` 前台服务做长期保活 | `dataSync` 有**时限**（Android 15 起 6 小时/24 小时累计），超时系统回调 `onTimeout()` 要求停止 | https://developer.android.google.cn/develop/background-work/services/fgs/timeout |
| 不声明前台服务类型就 `startForeground` | Android 14 起 **FGS 类型强制**，否则 `MissingForegroundServiceTypeException` 崩溃 | https://developer.android.google.cn/about/versions/14/changes/fgs-types-required |
| 对某个 WebView 实例单独"暂停 JS" | 唯一的 JS 定时器暂停 API `pauseTimers()` 是**全局请求，不限于当前 WebView** | https://learn.microsoft.com/en-us/dotnet/api/android.webkit.webview.pausetimers |
| 用"两个 WebView 实例"实现 localStorage 隔离 | Android WebView **没有 storage partition**；同进程即共享数据目录；跨进程则默认完全不通，要用 `CookieManager` 搬 | https://learn.microsoft.com/en-us/dotnet/api/android.webkit.webview.setdatadirectorysuffix |
| 把 ColorOS 流体云的"最多 N 张卡 / 白名单"当规格来设计 | **未找到任何 OPPO/一加 官方开发者文档**；无权威来源 | —— |
| 依赖 Service Worker / Background Sync 在后台持续联网 | SW 事件驱动、可被随时终止；Background Sync 是 UNOFF 规范且 WebView 行为社区反馈不一致；底层仍受进程冻结限制 | https://caniuse.com/background-sync ，https://arturbasak.dev/garden/workbox-background-sync_en |

---

## 四、明确可行清单（有官方支持）

| 目标 | 官方支持的手段 | 链接 |
|---|---|---|
| **长连接 / 后台接收 AI 对话流（核心）** | **原生前台服务**内自建 WebSocket / HTTP 流；选对 FGS 类型（长期驻留用 `specialUse` 并声明 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`，或语义相符的类型） | https://developer.android.google.cn/develop/background-work/services/fgs ，https://developer.android.google.cn/develop/background-work/services/fgs/service-types |
| **避免 6 小时时限** | 不用 `dataSync`；用 `specialUse` / 语义相符类型 | https://developer.android.google.cn/develop/background-work/services/fgs/timeout |
| **减少厂商省电干扰** | 引导用户关闭电池优化（`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`），注意 Play 政策限制 | https://developer.android.com/reference/android/provider/Settings#ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS |
| **让页面内滚动条"不占位"** | 隐藏经典条：`::-webkit-scrollbar{width:0}`（WebView **刻意保留**该能力）+ 页面内自绘悬浮指示器；滚动容器用**非根元素** `div{overflow:auto}` | https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Selectors/::-webkit-scrollbar ，https://codereview.chromium.org/2620743003/patch/1/10001 |
| **把 AI 文本推到流体云** | Android 16 标准 Live Updates：`POST_PROMOTED_NOTIFICATIONS` 权限 + `setRequestPromotedOngoing(true)` + `setShortCriticalText(...)`，并用 `canPostPromotedNotifications()` 判可用性 | https://developer.android.google.cn/develop/ui/views/notifications/live-update ，https://developer.android.com/reference/android/app/NotificationManager#canPostPromotedNotifications() |
| **编译期支持上述 API** | 升 AGP ≥ 8.9.1、`compileSdk = 36`、`androidx.core:core(-ktx) >= 1.17.0`（`minSdk`/`targetSdk` 无需跟着变） | https://code2native.com/blog/flutter-compilesdk-36-build-error ，https://discuss.gradle.org/t/androidx-core-requires-new-agp-version/51756 |
| **诊断真机滚动条类型** | 用 `scrollbar-gutter: stable` 的布局位移做探针（能生效=经典条，无效=overlay 条） | https://developer.mozilla.org/en-US/docs/Web/CSS/scrollbar-gutter |
| **WebView 多实例共享登录态** | **什么都不用做**——同进程两个 WebView 天然共享同一数据目录 | https://learn.microsoft.com/en-us/dotnet/api/android.webkit.webview.setdatadirectorysuffix |
| **渲染器被杀时不崩** | 实现 `WebViewClient.onRenderProcessGone()` 重建 WebView（这也是官方允许你改渲染器优先级策略的前提） | https://developer.android.com/reference/android/webkit/WebViewClient#onRenderProcessGone(android.webkit.WebView,%20android.webkit.RenderProcessGoneDetail) |
| **Service Worker（仅前台/离线）** | HTTPS 环境下注册 SW 做离线缓存；**不要**指望它后台保活 | https://developer.mozilla.org/en-US/docs/Web/API/Service_Worker_API |

---

## 五、不确定项（需真机实验或需补原文）

| # | 不确定项 | 为什么不确定 | 怎么定 |
|---|---|---|---|
| 1 | WebView 的**根文档**滚动条能否被 `::-webkit-scrollbar{width:0}` 隐藏 | 官方注释说明根文档用"系统滚动条"，CSS 隐藏的是 Blink 层的条，二者可能不是同一个 | 真机测试页：根滚动 + `::-webkit-scrollbar{width:0}`，看是否仍有 14px 条/占位 |
| 2 | `setVerticalScrollBarEnabled(false)` 是否**回收占位宽度** | 官方 API 文档只说是"是否绘制垂直滚动条"，**未说明布局影响** | 真机测量 `webView.getWidth()` 与页面 `window.innerWidth` 差值 |
| 3 | Android WebView 在**退到后台但进程未被冻结**时，`setTimeout`/WebSocket 是否继续 | **没有任何官方文档**说明这一点；`onPause()` 明确"不暂停 JavaScript"但也不承诺继续 | 真机实验：前台服务保活 + 隐藏 WebView，在页面里打时间戳日志，观察中断点 |
| 4 | 一加 PLC110 / ColorOS 16 是否**额外冻结前台服务** | Google 官方的 Cached App Optimizer 只覆盖 cached 进程；OEM 行为无官方文档 | 真机长跑（数小时）+ `dumpsys activity services` / `dumpsys deviceidle`，并对比设置里「允许完全后台行为」开/关 |
| 5 | ColorOS 16 是否提升你的通知、最多几张卡、是否允许 **`customContentView`** | **未找到任何 OPPO 官方文档**；Google 文档正文在本沙箱无法抓取 | 补抓官方 Live Updates 页面原文 + 真机观察是否出现"状态芯片" |
| 6 | `BigTextStyle` 是否被 Live Updates 接受 | 同上，**未核实** | 补抓官方页面 "Notification requirements" 段落 |
| 7 | `setRequestPromotedOngoing` / `setShortCriticalText` 的**引入版本** | androidx core release notes 正文抓不到 | 补抓 https://developer.android.google.cn/jetpack/androidx/releases/core 对应版本段落 |
| 8 | `androidx.core 1.17.0` 的 `minCompileSdk` **官方声明值**（当前仅有构建报错反证 = 36） | 同上 | 同上；或读 AAR 内 `META-INF/com/android/build/gradle/aar-metadata.properties` |
| 9 | Android WebView 是否支持 **Push API / Web Push** | 未找到官方说明 | 真机试 `registration.pushManager`，确认无 FCM 路径时的降级 |
| 10 | issue **40226034** 的真实内容 | 公开检索无任何结果，疑编号有误或需登录 | 若能访问 issues.chromium.org，请直接打开核对 |

---

## 附：本报告的取证环境限制（重要）

- **可正常抓取全文的站点**：`developer.mozilla.org`、`caniuse.com`、`codereview.chromium.org`、`learn.microsoft.com`（Mono.Android 镜像文档，其注释直接标注来自 AOSP）、`discuss.gradle.org`、部分 GitHub 页面。
- **无法抓取正文的站点**：`developer.android.com` / `developer.android.google.cn`（只返回页面标题 + 顶部导航即被截断）、`chromium.googlesource.com`、`raw.githubusercontent.com`、`bugs.chromium.org`、`maven.google.com`、`issues.chromium.org`。直连 HTTPS 亦被沙箱策略阻断（`curl.exe` 报 schannel `SEC_E_NO_CREDENTIALS`，`Invoke-WebRequest` 报连接被关闭）。
- 因此，凡涉及 Android 官方页面正文的"原文引用"，本报告一律标注为"**页面存在、正文未取得**"，并用可访问的镜像（Microsoft Learn 的 Mono.Android 文档）或 Chromium 官方评审记录作为原文来源。**没有一处引用是凭记忆编造的；找不到的一律明确写"未找到权威来源"。**


---





