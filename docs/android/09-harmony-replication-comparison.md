# 复刻安卓壳的鸿蒙落地对照（2026-09-17）

> **这份文档是什么**：把 `android-shell/`（已落地的安卓薄壳）里**后台链路**的每一步，
> 逐项对照到鸿蒙（ArkWeb + 系统能力）能用什么、缺什么、会踩什么。它是**落地对照**，
> 不是新调研：安卓侧事实来自只读阅读实现与归档，鸿蒙侧事实来自官方文档与本机 SDK 直读，
> 后台行为与实况窗的**专题结论不在这里重复**，直接引用同目录已产出的篇目。
>
> **来源**：① `android-shell/README.md`（文末「交接文本区」= 现状锚点）、
> `android-shell/docs/15-后台原生承载-独立设计.md`、`16-后台60秒墙-根因取证与原生承载.md`、
> `17-多工作区多任务订阅-理论推导.md`（只读）；② 关键代码只读阅读
> `ShellRuntime.kt` / `core/RelayBridge.kt` / `core/Tier2Probe.kt` / `core/TaskStore.kt` /
> `core/PromotionPolicy.kt` / `notify/Notifier.kt` / `notify/LiveUpdate.kt` / `KeepAliveService.kt` /
> `WebAppBridge.kt` / `MainActivity.kt`（只看关键函数）；③ 鸿蒙侧：
> 本机 DevEco 离线官方文档库（`hms_docs`，devecocli 本地索引）、本机 HarmonyOS SDK **API 24** 的
> `.d.ts` 直读、官方页面检索（developer.huawei.com）、官方抓取镜像（GitHub `liasica/harmonyos-skills`
> 的 `harmonyos-guides` 目录）。检索/取证日期 **2026-09-17**。
>
> **状态标记**：`【官方原文】`= 官方文档/SDK 声明原文（可复查 `documentId` 或链接）；
> `【官方镜像原文】`= 官方页面抓取镜像；`【二手来源】`= 社区/博客，仅作旁证；
> `【推断】`= 由原文推出的工程结论，未真机验证；`【未验证】`= 本文列出的待验证项。
>
> **范围排除**：安卓壳的**「状态栏沉浸」与「滚动条自绘」两项注入不在本次对照范围**——
> 它们是界面层注入，与后台链路无耦合（鸿蒙侧现状各自的替代做法不在本文展开）。
>
> **一句话结论**：**复刻「功能」可行，复刻「架构」不可行**——鸿蒙官方对"客户端后台扛长连接"
> 这条路线是**直接劝退**（无后台任务几秒挂起、网络 2s 冻结 / 12s 释放、终端类会话被点名不适用长时任务）。
> 前台套壳 + 注入 + 通知/实况窗这一半可以照搬；后台"判死 → 原生接管 → 回前台交还"这一半
> **在鸿蒙上没有等价物**，且唯一沾边的 `DATA_TRANSFER` 长时任务被官方绑上"进度更新必须是**实况窗**"的条件，
> 而实况窗对"AI 对话/远程任务进度"没有对应场景 —— **这就是最大风险点**（见 §6）。

---

## 1. 链路总览（含每一步的平台能力/应用协议标注）

安卓壳的后台链路，按 `README`+`docs/16`+`docs/17` 的现行形态画出（**箭头 = 因果顺序，不是代码调用栈**）：

```
[前台常态] 网页自己在跑
  WebView 载入 zcode.z.ai/remote/v4
     ├─ 平台能力：Android WebView（Chromium）+ addDocumentStartJavaScript document-start 注入
     └─ 应用层协议：无（网页自带的 relay WebSocket 客户端）——壳只是"挂原型钩子旁观"
  注入层 hook WebSocket.prototype → 拿到页面那条 relay socket 的收发旁路
     ├─ 平台能力：WebViewCompat.addDocumentStartJavaScript（必须在 loadUrl 之前）
     └─ 应用层协议：relay 明文帧（pair_status_query/ack、sessions-index、rpc-frame）

[步骤 1] 判死（链路僵死判定）
  判据：应用在后台 且 注入层"最早一帧入站" ≥ 35s（=3 个泵周期 + 余量）
  背景：退后台约 60–70s 后 Chromium 网络栈整条停止工作（socket 仍 OPEN、paired 仍 true、
        探针照发却零 ack、无 close 事件、连页面 close() 都卡 CLOSING）
     ├─ 平台能力：前台服务 specialUse 的 mainHandler 每 10s 驱动一次 evaluateJavascript 探针
     └─ 应用层协议：pair_status_query → pair_status_ack（"有没有 ack"就是判活信号）

[步骤 2] 原生接管（换承载层）
  动作：Tier2Probe.start(creds, durationMs = 0) 持久模式
     ├─ 平台能力：前台服务（进程活着）+ PARTIAL_WAKE_LOCK（熄屏 CPU 醒着）+ OkHttp WebSocket
     └─ 应用层协议：auth proof（relayProof）→ 配对 → 补三帧
        mobile-diagnostic(state-transition:paired) / mobile-view-state-update(activeWorkspaceKey) /
        bootstrap-request → workspace-list-request
  反向不变式：页面链路一旦自己活了（入站帧 < 35s），原生立刻交还——任何时刻只有一侧持连接

[步骤 3] 开桥 / 握手 / 订阅（每个工作区一座桥）
  beginCoverage(workspaces)（间隔 300ms，逐桥独立）
     ├─ 平台能力：无（纯应用层协议，共用同一条原生 WebSocket 多路复用）
     └─ 应用层协议：workspace-bridge-open → helloConversationV4 → initializeConversationV4(clientHello,
        protocolVersion=3) → **先 subscribeConversationV4（逐会话）** → subscribeSessionsIndexV4
        (runtimePolicy=existing-only) → listen(sessions-index)；帧格式 rpc-frame（base64 + crc32 + 分片 1MiB）
        + rpc-frame-ack（身份三元组 bridgeSessionId + bridgeGeneration + recoveryId）
  重锚/轮换：**每 12s 一拍**（`LIVE_PROGRESS_POLL_MS=12s` × `LIVE_REANCHOR_EVERY_POLLS=1`）——
      其余桥发 resync(forceSnapshot)；**"最饿的那座"不等超时、直接整桥回收换服务权**
      （门槛 `ROTATE_MIN_STARVE_MS=8s`；**规则：门槛必须小于拍长**）；跨连接冷却 3 条 / 10 分钟

[步骤 4] 任务正文抽取（"跟手"的数据源）
  三条流汇进 TaskStore，优先级 **controller > 会话流 > sessions-index 持久态**：
     ├─ 平台能力：无（应用层解析）
     └─ 应用层协议：① controller/tasks-index.liveStatus（**已关**：该订阅会让桌面端 host 进程
        uncaughtException 自毁）② 会话流 turnHeader.state（在用兜底）③ sessions-index.phase（持久态）
        正文只认 assistantText 最后一段（对齐网页 lastAssistantPreview），toolCall/subagent 不上卡片

[步骤 5] 提升策略（"给谁卡位"）
  PromotionPolicy.choose：**等待确认优先 → 最近活动**，MAX_PROMOTED = 2
     ├─ 平台能力：Android 16 Live Updates 的 promoted 名额（ColorOS 渲染为流体云）
     └─ 应用层策略：TaskStore.Update 的**空更新守卫**（"四个列表全空"= 什么都没变，不得当成运行集为空）

[步骤 6] 通知 / 实况窗（用户可见反馈）
  三个渠道（重要性创建时定死）：running_tasks=LOW 常驻 / task_attention=DEFAULT / task_completed=DEFAULT
  每条运行中任务一条常驻通知（标题 `状态 · 任务名`，正文=活进展）+ 组摘要，更新节流 ~900ms
     ├─ 平台能力：NotificationManager 渠道 + POST_PROMOTED_NOTIFICATIONS（普通权限，声明即有资格）
     └─ 应用层协议：通知 extras `android.requestPromotedOngoing` / `android.shortCriticalText`；
        点击通知 = EXTRA_TASK_TITLE 原文（未装饰）做查找键 → 页面 DOM 定位（最脆弱的一环）

[步骤 7] 回前台交还
  动作：tier2 disposed → 页面可见性恢复后 Chromium 网络栈复活 → 页面自己的 recoverConnection 重拨（0.9s）
     ├─ 平台能力：释放 wake lock / 停止前台服务承载；可见性恢复是 Chromium 自己的行为
     └─ 应用层协议：交还兜底 `__zcodeShellAfterCarrierReturn`（8s、无 OPEN socket、零入站帧才重载一次，5 分钟限流）
```

**读这张图要先接受一条底线**：安卓壳之所以长成这个形状，是因为「**换承载层**」是它唯一出路
（页面侧自救被实测证伪）。鸿蒙侧这一步不存在官方通道（详见 §2、§4）。

> **增量（2026-09-17 19:00 / 19:15 两个提交：`34303f1`、`406f7ea`）** —— 本仓库安卓侧在同一时间仍在演进，
> 上面这张图已按最新代码更新，另外三件事影响本对照的读法：
>
> 1. **轮换节奏 24s → 12s（真机 153 验收）**：拍长 = `LIVE_PROGRESS_POLL_MS`(12s) × `LIVE_REANCHOR_EVERY_POLLS`(2→1)；
>    实测两桥**严格 12s 交替**、重锚失败 0、卡片撤回 0、单卡更新间隔 10–23s（原 24–41s），代价 ~4 次回收/分钟。
>    并沉淀一条坑：**主动轮换门槛必须小于拍长**——门槛 15s + 拍长 12s 时，每拍饿桥只静默 12s 不够格，
>    变成每两拍才轮换，**实测仍是 24s，等于没改**（所以有第二次提交把门槛降到 8s）。
> 2. **E1 结论改写（对复刻有直接含义）**：桌面端**能同时开多座桥，但同一时刻只服务一座**
>    （服务权归最近订阅成功者）；旧的"1 成功 6 被拒"证据被确认为 host 之死污染。
>    这是**桌面端行为、与客户端平台无关**——鸿蒙侧只要走订阅链路，同样会遇到"服务权/交接"问题，
>    只是鸿蒙把后台窗口压缩到 2s/12s，为后台做轮换的意义大幅下降。
> 3. **流体云卡片"消失→重建"根因修复**：`TaskStore` 用"四个列表全空"表示"什么都没变"，
>    而 `turnStateSink` 无条件 `applyUpdate` → 空列表被当成"当前运行集为空"发布 → 提升集合清空 →
>    ColorOS 收回卡片 → 0.9s 后重建（每次桥回收必现）。已在 `turnStateSink`（空更新直接 return）
>    与 `applyUpdate`（真正的"运行集变空"必带 `removedIds`）两处加守卫。
>    映射到鸿蒙侧就是 `07`/`09` 里"通知/实况窗状态机"的同型风险：**"无变化"与"变空"必须是两个信号**。

---

## 2. 逐项对照表

> 列义：**安卓壳的机制 → 鸿蒙对应能力/API → 官方文档 → 差异与风险**。
> 「本机 SDK」= 本机 `E:\DevEco Studio\sdk\default\openharmony`（API 24）`.d.ts` 直读；
> 带 ※ 的链接由官方 `documentId` 的 slug 直接拼出（未被逐页打开，见 §7 说明）。

### 2.1 承载页面

| 项 | 内容 |
| --- | --- |
| 安卓壳 | `WebView` 载入 `zcode.z.ai/remote/v4`；**不调 `webView.onPause()`**（会挂起 WebView 定时器，掐掉页面 relay 心跳）；返回键用 `moveTaskToBack(true)` 保连接；`setRendererPriorityPolicy(IMPORTANT, false)` |
| 鸿蒙能力 | ArkWeb `Web` 组件（`Web({src, controller})`）+ `WebviewController`；`onActive()` / `onInactive()` 由应用显式通知激活态 |
| 官方文档 | `onInactive()` 声明原文【本机 SDK 直读，`@ohos.web.webview.d.ts:4087`】：*"any content that can be safely paused will be paused as much as possible, such as animation and geographical location. **However, JavaScript will not be paused. To pause JavaScript globally, please use `pauseAllTimers`. To reactivate the Web component, call `onActive`.**"*；`pauseAllTimers()` / `resumeAllTimers()`（static，API 9 起）。指南见 [使用Web组件加载页面](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/web-page-loading-with-web-components)※ |
| 差异与风险 | ① **"隐藏"与"暂停"是两个动作**：`onInactive()` 是**应用自己调**的，官方明确它**不暂停 JS**（要暂停得自己调 `pauseAllTimers`）；而"退到后台页面会不会被冻结"由**进程是否被挂起**决定，与这个 API 无关——官方 FAQ 明确"H5 定时器退后台不执行，解法是短时任务"【官方原文，FAQ `faqs-arkweb-172`】。所以安卓"别调 `onPause()`"这条经验**不能平移**，要按 06 篇的"断电模型"设计；② 反过来说，**ArkWeb 不会自动为你省电**——只要进程活着页面全速跑，会直接撞上后台 CPU 配额（见 §4）；③ BFCache 的存在（`SuspendType.ENTER_BACK_FORWARD_CACHE` / `ENTER_BACKGROUND`）意味着"回前台"可能是**恢复**而不是重载，策略要分叉（07 篇 / 08 篇展开）【官方原文，`@ohos.web.webview.d.ts` 的 `SuspendType` 枚举】 |

### 2.2 后台定时 / 长连接

| 项 | 内容 |
| --- | --- |
| 安卓壳 | 前台服务 `specialUse`（保**进程**）+ `PARTIAL_WAKE_LOCK`（保 **CPU**）+ `Handler.createAsync` 每 10s 异步泵 `evaluateJavascript` 探针，顶替被节流的页面心跳；后台网络栈会死，靠"原生接管"换承载层 |
| 鸿蒙能力 | **短时任务** `requestSuspendDelay`（延迟挂起）；**长时任务** `startBackgroundRunning` + `module.json5` 的 `backgroundModes` + `ohos.permission.KEEP_BACKGROUND_RUNNING`；延迟任务 `workScheduler`；代理提醒 `reminderAgentManager` |
| 官方文档 | [长时任务(ArkTS)](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/continuous-task)、[短时任务(ArkTS)](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/transient-task)※、`Background Tasks Kit简介`（本机离线库 `开发指南/Background_Tasks_Kit_后台任务开发服务/Background_Tasks_Kit简介/background-task-overview`） |
| 官方硬数字 | 短时任务：**单次配额最大 3 分钟、单日（24h）默认 10 分钟、低电量单次 1 分钟**；官方示例注释"**退到后台 5s 内必须申请**"【官方原文，FAQ `faqs-arkweb-172`】。长时任务：一个 UIAbility 申请数 **API 21 起最多 10 个**（API 20 及以前**仅 1 个**，多任务要拆多个 UIAbility）；申请后**系统在通知栏发通知，用户删通知即自动停止任务**；"**不得修改长时任务通知内容**"【官方原文，`continuous-task` + `bgtask-design-formula`】 |
| 差异与风险 | ① **不是"节流"是"断电"**：无后台任务几秒即挂起，网络资源 **2s 冻结 / 12s 释放（socket 被 Abort，连接对象不释放，回前台复用必失败）**——行为差异与逐条对照见 **[06](06-ArkWeb后台行为-节流冻结与网络管控.md)**，此处不复述；② 安卓壳最有价值的那条经验（`PARTIAL_WAKE_LOCK` 让熄屏后 CPU 醒着）**在鸿蒙没有对等杠杆**：`runningLock.create` 的 `BACKGROUND` 类型**官方标注已废弃、不建议使用**，且"无长时任务的应用退到后台应释放资源，避免阻止系统休眠"【官方原文，最佳实践 `bpta-reasonable-system-use`】；③ 长时任务通知是**系统发的**，不能像安卓那样自定义常驻通知内容；④ 违规有处罚（"恶意保活"会被**禁止使用长时任务**并要求盖章整改） |

### 2.3 原生 ↔ 网页通道

| 项 | 内容 |
| --- | --- |
| 安卓壳 | ① `addDocumentStartJavaScript`（**document-start 主道**，必须早于 `loadUrl`，用来 hook `WebSocket.prototype` 拿页面首帧）；② `addJavascriptInterface`（唯一对象 `window.ZCodeShell`，两个方法 `postMessage(json)` / `config()`，后者同步返回）；③ `evaluateJavascript` 驱动探针；④ 无 WebMessagePort |
| 鸿蒙能力 | ① **`javaScriptOnDocumentStart(scripts: Array<ScriptItem>)`**（Web 组件属性，API 11 起，**官方明确"脚本先于页面任何 JS 执行"**）；② `javaScriptProxy({object, name, methodList, controller})`（组件属性，**仅支持注册一个对象、只能声明方法不能声明属性**）或 `registerJavaScriptProxy`（控制器方法，**支持多个对象**，与 `deleteJavaScriptRegister` 配合防泄漏）；③ `runJavaScript` / `runJavaScriptExt`（**必须在 UI 线程调用**，异步回 Promise/回调；`runJavaScript` 只回 string，`runJavaScriptExt` 支持 `JsMessageType`）；④ `createWebMessagePorts()` + `postMessageEvent` + `onMessageEvent`（双向端口通道） |
| 官方文档 | [前端页面调用应用侧函数](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/web-in-page-app-function-invoking)※（javaScriptProxy / registerJavaScriptProxy）、[应用侧调用前端页面函数](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/web-in-app-frontend-page-function-invoking)（runJavaScript / runJavaScriptExt）、[建立应用侧与前端页面数据通道](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/web-app-page-data-channel)（WebMessagePort）；`javaScriptOnDocumentStart` 语义见本机 SDK `component/web.d.ts:10511` 与 FAQ `faqs-arkweb-59` / `faqs-arkweb-42` |
| 差异与风险 | ① **安卓壳最贵的一段弯路在鸿蒙不需要重演**：`javaScriptOnDocumentStart` 就是官方 document-start 钩子，安卓侧"三道时机 + 原型层收编"（README 已知问题 F）**不要照搬**；② 但两条约束与安卓不同：`javaScriptProxy` **只能一个对象**（安卓 `addJavascriptInterface` 可加多个），要多对象得用 `registerJavaScriptProxy` 并处理注册生效/注销；③ `javaScriptOnDocumentStart` 官方注明**按脚本内容的字典序执行、不按数组顺序**，且**同内容脚本会被静默去重**（重复注入时沿用首次的 `scriptRules`）——若注入层有先后依赖，官方建议改用 `runJavaScriptOnDocumentStart`；④ `runJavaScript` 必须 UI 线程：安卓壳把泵放在 `mainHandler` 的经验**方向一致**（也要走 UI 线程），但"两条跳都必须是异步消息，否则被 Choreographer 同步屏障饿死"这条**是 Android 特有**，不必然成立【推断】 |

### 2.4 任务与工作区状态存储

| 项 | 内容 |
| --- | --- |
| 安卓壳 | `TaskStore` 是**纯内存**（无 Android 类型、可 JVM 单测）：`workspaces` / `persistedTasks`(SI 持久态) / `livePhases`(controller) / `conversationPhases`(会话流) / `livePreviews`；`RunningNotification.id` 由 `sessionId` 稳定派生；跨页面重载靠原生进程活着（`WebAppBridge.config()` 把上一轮运行集种给注入层） |
| 鸿蒙能力 | 同构：进程内 `class`（ArkTS）单例即可承载同样的分层；需要落盘时用 `@ohos.data.preferences`（轻量键值）或 `relationalStore`（关系型，SQLite 能力） |
| 官方文档 | `@ohos.data.preferences`（本机离线库 `@ohos.data.preferences (用户首选项)`）；选型对照见 FAQ `faqs-local-database-management-53`；注意 **`put` 之后必须 `flush()` 才持久化**（FAQ `faqs-local-database-management-28`） |
| 差异与风险 | ① **存储本身不是风险**，风险在"谁来供数"：安卓的 `TaskStore` 数据来自原生接管那条链，鸿蒙上**没有那条链**（§2.2），所以这个 store 在鸿蒙侧要么只由页面注入供数（前台有效），要么靠通知/实况窗的载荷（见 §2.5/2.6）；② 安卓侧 `TaskStore` 也**没有持久化**（进程死即丢，靠页面重载重建）——**不要**为了"鸿蒙更规范"顺手加落盘，那会引入"内存态与落盘态不一致"的新 bug 面；③ 「空 Update = 什么都没变」这条不变式（README 实现要点 19）是**纯应用层逻辑，可原样搬运**，与平台无关 |

### 2.5 用户可见反馈（常驻 / 进度通知，含后台发通知的限制）

| 项 | 内容 |
| --- | --- |
| 安卓壳 | 三渠道（`running_tasks` LOW 静音常驻 / `task_attention` / `task_completed` DEFAULT）；每运行中任务一条常驻通知 + 组摘要；更新节流 ~900ms，完成/待确认立即发；点击通知拉起并尝试 DOM 定位 |
| 鸿蒙能力 | Notification Kit：`notificationManager.publish` / `cancel`，`NotificationRequest` 支持 **`isOngoing`（常驻）/ `isUnremovable` / `groupName`（分组）/ `badgeNumber`（角标）/ `progress`（进度条）/ `notificationSlotType`（渠道）**；渠道用 `addSlot(SlotType)` 创建；点击跳转用 `WantAgent`（`OperationType.START_ABILITY`）；通知授权用 `requestEnableNotification()` |
| 官方文档 | [发布通知](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/publish-notification)※、[管理通知渠道](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/notification-slot)※、`@ohos.notificationManager`（本机离线库）；字段见本机 SDK `api/notification/notificationRequest.d.ts:146/162/170/399/512` |
| 差异与风险 | ① **渠道模型不同**：安卓渠道重要性"创建后不可改"、靠分渠道实现"待确认要响、运行中要静"；鸿蒙的 `SlotType` 决定整套提醒方式（横幅/锁屏/铃声），且**横幅默认关闭、由用户在设置里开**，用户还可通过"设置 > 通知和状态栏"逐渠道管理【官方原文，`notification-slot`】——"完成要响"不能像安卓那样一创建就定死，要**提前引导用户开授权/横幅**；② **后台发布限制**：未查到官方"应用在后台不得发布本地通知"的明文禁令；能确定的是"发布前需通知开关开启"（FAQ `faqs-notification-kit-8`）与"进程被挂起时通知也发不出去"【推断】——**如果进程被挂起，无论权限如何都发不出**，所以这一步仍受 §2.2 的约束；③ 长时任务本身会占用一条系统通知，**它不可改内容**，与"每任务一条常驻通知"是两套东西，别混用 |

### 2.6 实况窗

| 项 | 内容 |
| --- | --- |
| 安卓壳 | ColorOS 流体云 = Android 16 **Live Updates**（`POST_PROMOTED_NOTIFICATIONS` + 在常驻通知上写 extras `android.requestPromotedOngoing` / `android.shortCriticalText`）；产品上限 `PromotionPolicy.MAX_PROMOTED = 2`；完成卡片 15s 后自撤 |
| 鸿蒙能力 | Live View Kit（端侧 `liveViewManager.startLiveView / updateLiveView / stopLiveView`；云侧 Push Kit） |
| 官方文档 | 同目录实况窗专题 **[01](01-实况窗-数量约束与并发上限.md)–[05](05-实况窗-设计规范与版本矩阵.md)**：数量/流控 [01](01-实况窗-数量约束与并发上限.md)、开通与权益 [02](02-实况窗-开通与权益申请.md)、本地 API [03](03-实况窗-本地API-创建更新停止.md)、Push 通道 [04](04-实况窗-Push与端云协同.md)、设计规范与场景准入 [05](05-实况窗-设计规范与版本矩阵.md) |
| 差异与风险 | ① **复刻更重**：安卓是"在已有常驻通知上请求提升"，**零申请、声明权限即可**；鸿蒙是**独立服务 + 两道权益门**（调试门 5 工作日；正式门要求**已上架且月活 ≥1000、逐场景评审、全程约 15 工作日**）【官方原文，见 02 篇 §1.1/§1.3】；② **场景准入对不上**：官方场景表里 `PROGRESS` 明确限定"**用户手动开启**的文件上传/下载"，"**后台自动**"被列为**不适用**；`TIMER` 明确排除 **"AI 对话时长"**【官方原文，见 05 篇 §3.2】——远程任务进度天然属于"非用户在本机手动开启"，**按此名义申请通过概率低**【推断，05 篇同结论】；③ 生命周期更硬：单个实况窗**最长 8 小时**，**>2h 未更新降级、>4h 未更新清除**；应用级流控**创建 10 次/秒、更新 20 次/秒**（超出静默丢弃）【官方原文，01 篇】；④ **本地创建依赖应用进程运行**（进程死了就没人更新）【官方原文，01 篇 §2.2】——这与安卓"promoted 通知由系统渲染、进程死了卡片也还在"是**本质差异** |

### 2.7 后台被系统回收后的恢复

| 项 | 内容 |
| --- | --- |
| 安卓壳 | 进程被 ROM 杀（ColorOS 实测 285ms `o-stop(40)`）→ 前台服务 `START_STICKY` 拉起服务；`onTaskRemoved` 不自杀；状态**不落盘**，靠页面重载重建；诊断判据用 `SurvivalVerdict`（只信原生侧 delta） |
| 鸿蒙能力 | ① `from '@kit.AbilityKit'` 的 `appRecovery`（`enableAppRecovery` + `RestartFlag` + `SaveOccasionFlag` + `restartApp` / `saveAppState`，配合 `UIAbility.onSaveState()`，重启时状态经 `want.wantParams` 回填）；② 延迟任务 `workScheduler`（退后台即挂起，满足条件由系统拉起 **Extension 进程**执行，**单次回调最长 2 分钟**）；③ 通知 + WantAgent 拉起应用 |
| 官方文档 | [应用恢复开发指导](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/apprecovery-guidelines)、`@ohos.app.ability.appRecovery`（本机 SDK）、[应用重启](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/app-restart)※ |
| 差异与风险 | ① **没有 `START_STICKY` 的等价物**：`appRecovery` 只覆盖**崩溃 / 冻屏**后的"自恢复重启"，**不覆盖被系统正常终止/回收**的场景【官方原文："系统默认对异常的处理方式为进程退出……应用非预期退出后的下一次启动会恢复先前的状态和数据"，`apprecovery-guidelines`】；② 也就是说，安卓壳"服务被拉回来继续跟手"这一段**在鸿蒙要改成"下次启动时对齐"**；③ 延迟任务「单次 2 分钟」量级远小于"长时间跟手"，**不能当后台订阅用**；④ 恢复后要重新建立页面/桥状态（安卓壳的 `reset()` + 注入种子那套思路可搬，但数据源不同） |

### 2.8 进程存活判定与"卡死"判据

| 项 | 内容 |
| --- | --- |
| 安卓壳 | **Tier2 判死 = 入站帧静默 ≥35s**（3 泵周期 + 余量）+ 两条护栏（页面刚动过 socket 等它 20s；空 ack 不算健康）；另有"活进展是否推进"与"流体云 when 是否前进"的双信号交叉核对（README 交接区"两次误报"纪律） |
| 鸿蒙能力（可用信号，全为本机 SDK 直读） | ① **`onRenderProcessNotResponding` / `onRenderProcessResponding`**（Web 组件属性，API 12）：*"If the Web component can't handle the input event, or can't navigate to the new URL within a reasonable time range, the Web page process is considered unresponsive"*；② **`onRenderExited`**（渲染进程退出及原因：OOM / crash / 正常）；③ **`backgroundTaskManager.on('continuousTaskCancel')`**（API 15，带 `ContinuousTaskCancelInfo.id/reason`）与 **`on('continuousTaskSuspend')`**（API 20，被系统标记暂停态）；④ **`getAllContinuousTasks()`**（API 20，**含暂停态**，可用于"重启后自查有没有残留任务"）；⑤ `UIAbility.onBackground/onForeground` + `windowStage` 事件（前后台切换）；⑥ 自建探针：`@ohos.net.webSocket` 心跳 ack 的静默时长（与安卓同构，但门槛量级要改） |
| 官方文档 | 本机 SDK `component/web.d.ts:10801/10811`、`api/@ohos.web.webview.d.ts`、`api/@ohos.resourceschedule.backgroundTaskManager.d.ts` |
| 差异与风险 | ① **判据量级必须重定**：安卓 35s 是因为 Chromium 在 60–70s 才死；鸿蒙官方给的冻结线是 **2s / 12s**，继续用 35s 会在"早就断了"的窗口里误判为健康【推断；行为差异见 [06](06-ArkWeb后台行为-节流冻结与网络管控.md)】；② **鸿蒙多了两个系统级信号**（`onRenderProcessNotResponding`、`onRenderExited`）——安卓侧只能靠计时推断"卡死"（还要排除 Choreographer 同步屏障等一堆假象），鸿蒙有官方回调，**优先用回调，计时只做兜底**【推断】；③ "卡死"与"被挂起"要分开：`continuousTaskCancel/Suspend` 是**系统告诉你任务没了**，renderer 回调是**页面自己卡了**，两者判据与处置不同；④ 安卓"只信原生侧 delta、不信页面侧绝对值"这条纪律**在任何平台都成立**，建议保留 |

### 2.9 补充对照：官方文档里没有直接对应项的三处

| 安卓壳的机制 | 鸿蒙的对应情况 |
| --- | --- |
| `POST_NOTIFICATIONS` 运行时权限（API 33+） | `notificationManager.requestEnableNotification()` 弹窗授权（**仅弹一次**），退路是引导用户去设置里开（FAQ `faqs-notification-kit-8`） |
| 通知点击后在页面 DOM 里定位任务（D11，最脆弱） | 同样脆弱：`WantAgent` 只能把参数带进应用，定位仍要 `runJavaScript` 打 DOM；建议保持"优雅降级为仅打开应用"的既有设计 |
| 后台 CPU 醒着（`PARTIAL_WAKE_LOCK`） | `runningLock.create` 的 `BACKGROUND` 类型**官方标注废弃、不建议使用**；无长时任务时应主动释放资源【官方原文，`bpta-reasonable-system-use`】——**没有合法等价物** |

---

## 3. 官方文档阅读顺序清单（按"复刻链路"顺序）

> 建议**按这个顺序读**：先定"能不能在后台活"（4–7），再定"前台怎么接"（1–3），最后才是"用户看什么"（8–9）。
> 带 ※ 的链接由官方 `documentId` 的 slug 直接拼出（未逐页打开，见 §7）。

| # | 读什么 | 为什么在这个位置 | 链接 / 出处 |
| --- | --- | --- | --- |
| 1 | ArkWeb 承载与生命周期（`Web` 组件、`onActive/onInactive`、`pauseAllTimers`） | 先确认"页面怎么被装载"，以及"隐藏 ≠ 暂停" | 本机 SDK `component/web.d.ts`、`@ohos.web.webview.d.ts`；[使用Web组件加载页面](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/web-page-loading-with-web-components)※ |
| 2 | 注入三件套：`javaScriptOnDocumentStart` → `javaScriptProxy`/`registerJavaScriptProxy` → `runJavaScript` | 对照安卓壳的"注入 + 通道"整层，确认哪些弯路不用走 | [前端页面调用应用侧函数](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/web-in-page-app-function-invoking)※、[应用侧调用前端页面函数](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/web-in-app-frontend-page-function-invoking)、FAQ `faqs-arkweb-59`/`faqs-arkweb-42` |
| 3 | `WebMessagePort` 数据通道 | 需要"原生主动推 + 页面回 ack"时比字符串注入更干净 | [建立应用侧与前端页面数据通道](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/web-app-page-data-channel) |
| 4 | **Background Tasks Kit 简介**（挂起定义、四类后台任务、资源配额） | **先读这份**，它就是"能不能后台跑"的总纲 | 本机离线库 `开发指南/Background_Tasks_Kit_后台任务开发服务/Background_Tasks_Kit简介/background-task-overview` |
| 5 | **短时任务**（配额与申请时机） | 覆盖"切后台瞬间的收尾"，是唯一能立刻试的手段 | [短时任务(ArkTS)](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/transient-task)※ |
| 6 | **长时任务**（类型表、一致性校验、通知规则、数量限制） | 决定 `DATA_TRANSFER` 这条路到底能不能走（结论：要走就得配实况窗） | [长时任务(ArkTS)](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/continuous-task) |
| 7 | 长时任务接入规范与违规处罚 + 网络/系统资源合理使用 | 决定"做不成会不会被罚"（恶意保活红线） | 本机离线库 `开发指南/Background_Tasks_Kit_后台任务开发服务/Background_Tasks_Kit接入规范/bgtask-design-formula`、`最佳实践/应用功耗优化/后台任务低功耗/后台软件资源合理使用/后台系统资源合理使用/bpta-reasonable-system-use` |
| 8 | Notification Kit：发布通知 / 管理通知渠道 / `NotificationRequest` 字段 | 常驻与进度通知的落点；渠道与提醒方式的规则和安卓不同 | [发布通知](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/publish-notification)※、[管理通知渠道](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/notification-slot)※ |
| 9 | Live View Kit：**01–05 篇** + 本文 §2.6 | 提升卡位；**先看权益与场景准入，再看 API**，否则会做一半卡住 | [01](01-实况窗-数量约束与并发上限.md) [02](02-实况窗-开通与权益申请.md) [03](03-实况窗-本地API-创建更新停止.md) [04](04-实况窗-Push与端云协同.md) [05](05-实况窗-设计规范与版本矩阵.md) |
| 10 | 应用恢复 / 应用重启 / 延迟任务 | 进程被回收后"怎么回来"、以及"回来时还剩什么" | [应用恢复开发指导](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/apprecovery-guidelines)、`@ohos.app.ability.appRecovery`（本机 SDK） |
| 11 | 后台行为专题与 ArkWeb 能力清单（**06 / 07 / 08 篇**） | 把 4–7 的结论落到"具体时刻与具体 API"，作为实现期常查手册 | [06](06-ArkWeb后台行为-节流冻结与网络管控.md)；**07**（后台保活官方方式全集）与 **08**（ArkWeb 能力与 API 清单）为本轮并行产出的同目录篇目，文件名以 `README.md` 索引为准 |

---

## 4. 鸿蒙侧独有的坑

> 只列**会改变方案选择**的，逐条给依据；不重复 [06](06-ArkWeb后台行为-节流冻结与网络管控.md) 已展开的官方原文。

1. **后台不是"慢下来"而是"断电"**【依据：06 篇 §1，官方 FAQ `faqs-network-95`】。
   无后台任务几秒挂起；网络资源 **2s 冻结 / 12s 释放**，socket 被 Abort 但**连接对象不释放**，
   回前台继续用旧对象**必失败**。安卓壳"后台僵尸连接"的排查经验在这里**不适用**——
   鸿蒙根本不给僵尸存活到 60s 的机会。**判活/重连必须按"断电恢复"设计**。

2. **终端 / 心跳类长连接被官方点名劝退**【依据：06 篇 §2.3 引官方 FAQ 原文】。
   官方原文直接说"**纯粹的终端会话或闲置 TCP 不属于合法的高优先级后台服务**"，
   并给出唯一推荐架构：**"服务端会话托管 + 客户端前台断线秒连"**。
   安卓壳那套"后台自己扛住连接 + 判死接管"**整层路线在鸿蒙不成立**——
   这与 06 篇的结论一致，且官方连"备选方案"都写清了（大文件真实传输才可申 `dataTransfer`，**传完必须立即释放**）。

3. **`DATA_TRANSFER` 长时任务被绑死在"实况窗"上**（本文最要命的一条，06 篇未展开）【官方原文，`continuous-task`】：
   > "在数据传输时，应用需要更新进度，**如果进度长时间（首次更新超过 10 分钟）未更新，数据传输的长时任务会被取消。
   > 更新进度的通知类型必须为实况窗**。"

   推论【推断】：想在鸿蒙后台"边收帧边更新进度"，**进度更新必须走实况窗**；
   而实况窗对"AI 对话 / 远程任务进度"没有匹配场景（见 §2.6）。**于是后台链路被间接绑到实况窗权益上**——
   这是"复刻架构不可行"的技术闭环原因，不只是政策原因。

4. **`DATA_TRANSFER` 还有一条陷阱**：用 `@ohos.request` 把上传下载**托管给系统**时，
   **即使申请了 `DATA_TRANSFER` 后台任务，应用退后台时还是会被挂起**【官方原文，`continuous-task`】。
   即"托管"与"自持连接"二选一，不能又要系统代理又要自己的 socket 活着。

5. **长时任务通知不是你的通知**：申请成功后**系统在通知栏发关联通知**，
   **用户删除该通知 = 系统自动停止长时任务**；接入规范明文"**不得修改长时任务通知内容**"
   （唯一的例外是 `DATA_TRANSFER` 的进度更新，且"更新后的内容必须用于此场景"）
   【官方原文，`continuous-task` + `bgtask-design-formula`】。
   安卓壳"每任务一条自定义常驻通知 + 组摘要"能够保留（那是普通通知），
   但**别指望把长时任务通知当承载状态的那条**。

6. **数量限制会撞上"多工作区多任务"**：API 20 及以前**一个 UIAbility 只能申请 1 个长时任务**，
   API 21 起最多 10 个；多任务要么升级到 API 21+，要么**拆多个 UIAbility**
   【官方原文，`continuous-task` §约束与限制】。而安卓壳的"多工作区 = 多座桥"与之无关
   （桥是应用层协议，不是平台任务），**别把两件事混成一件**。

7. **违规是会被处罚的**：违反长时任务场景/恶意保活 → 收到违规通知，
   严重者**立刻在违规场景禁用长时任务**，整改要提交**带公章的《整改承诺声明》**
   【官方原文，`bgtask-design-formula`】。安卓侧 ColorOS 的"用户手动放行"在这里不通用，
   **这是对开发者的账号级约束**，比 ROM 白名单重得多。

8. **三方应用没有 `ServiceExtensionAbility`**，`appRecovery` 只覆盖崩溃/冻屏自恢复，
   **没有 `START_STICKY` 式"被杀后自动拉回"**（§2.7）【官方原文 + FAQ `faqs-background-tasks-1`】。
   "保活"这个词在鸿蒙要按"合法任务类型 + 用户可感知"重新定义。

9. **后台资源配额是硬闸**（06 篇已引）：内存 2GB 连续 1 分钟超限 → **后台终止进程**；
   后台 CPU 10 分钟均 >10% / 20 分钟均 >7.5% / 60 分钟均 >2.5% / 120 分钟均 >1.3% → **结束进程**；
   IO 24 小时 1GB【官方原文，FAQ `faqs-background-tasks-6`】。
   坑在于：**ArkWeb 不会自己省电**（§2.1），一个持续解码帧的页面很容易把"后台 CPU"这条闸撞上——
   安卓壳实测"解码合计 90–310ms/10s"【二手来源：本项目真机日志】在鸿蒙侧没有对应基线，需要自测。

10. **实况窗的权益门槛会让"复刻"更重**（§2.6）：安卓侧 `POST_PROMOTED_NOTIFICATIONS`
    是**普通权限、声明即具备资格**；鸿蒙侧要**调试门 5 工作日 + 上线门（已上架 & 月活 ≥1000 & 逐场景评审，约 15 工作日）**，
    且**本地实况窗依赖进程运行**——进程一挂，卡片就没人更新。**"复刻更重"不是成本问题，是可做性问题。**

11. **MDM 的"防冻结/不可关停"是误读区**：6.0.2(22) 起的这些能力属企业设备管理/企业空间，
    普通三方应用**用不了**【官方原文，`@ohos.enterprise.applicationManager`；06 篇 §2.7 已告警】。

12. **渠道与提醒方式的模型差异**（§2.5）：鸿蒙 `SlotType` 决定整套提醒方式，
    **横幅默认关闭且由用户开**；安卓"渠道重要性创建时定死"的经验不能平移。

13. **注入**：`javaScriptProxy` **只能一个对象、只能方法**；`javaScriptOnDocumentStart`
    **按字典序执行 + 同内容静默去重**（§2.3）——若注入脚本之间有序依赖，用官方推荐的数组序接口。

14. **`runJavaScript` 只能在 UI 线程调用**；返回能力上 `runJavaScript` 只回 string，
    要结构化数据用 `runJavaScriptExt`（本机 SDK / FAQ `faqs-arkweb-21`）。

---

## 5. 未验证项与真机实验清单（步骤 + 判据）

> 排序原则：**先回答会否决方案的**。E1–E4 决定"后台这条路是否存在"；E5–E8 决定"前台与恢复怎么实现"。
> 与 [06](06-ArkWeb后台行为-节流冻结与网络管控.md) §6 的 E1–E6 互补，不重复其网络断点测量。
> 设备：本机可用的鸿蒙真机（`dsh-hdc-bridge` 的 `hdc_list_targets` / `hdc_shell` 可直连）。

| # | 要回答的问题（决定什么） | 实验步骤 | 判据 |
| --- | --- | --- | --- |
| **E1** | **`DATA_TRANSFER` 长时任务 + 实况窗进度，是不是唯一可行的后台保活组合**（决定后台方案是否存在） | ① `module.json5` 加 `backgroundModes: ["dataTransfer"]` + `ohos.permission.KEEP_BACKGROUND_RUNNING`；② 在 `onBackground` 调 `startBackgroundRunning(context, ['dataTransfer'], wantAgent)`；③ 同时按官方示例发**实况窗进度**；④ 让页面/原生只做低频心跳（模拟"没有大流量"）；⑤ 挂后台 30 分钟 | ① 长时任务是否在若干分钟内被 `on('continuousTaskCancel')` 收回（reason 是什么）；② 若收回，说明**官方"一致性校验"对低频心跳判违规被实测确认**；③ 若撑住 30 分钟，记录实况窗是否仍在更新（进程活着的间接证据） |
| **E2** | **`DATA_TRANSFER` 长时任务下，ArkWeb 页面里的 WebSocket 能不能持续存活**（决定"页面自己扛"是否有救） | ① 页面建 WebSocket，服务端记录最后收包时间；② 申请 dataTransfer 长时任务后退后台；③ 每 30s 从服务端读一次"最后收包距现在多久"；④ 30 分钟 | 若断点落在 **2s 或 12s**（官方网络冻结/释放线）⇒ 页面侧无救，与安卓"页面侧自救被证伪"同结论；若能持续收包 ⇒ 需要复核 06 篇"网络 2s/12s"是否对**同进程 Web 内核**同样适用（这条实测会决定 06 篇结论的适用范围，不是推翻它） |
| **E3** | **短时任务期间网络是否可续用**（决定"切后台瞬间收尾"能做到多少） | ① `onBackground` 内 5s 内 `requestSuspendDelay`；② 页面/原生每 5s 发心跳；③ 3 分钟到点 | 3 分钟内心跳是否**全部**送达（官方把定时器问题指向短时任务，但**未明写短时任务能否解冻网络**）；配额耗尽的第 2 次申请是否失败 |
| **E4** | **实况窗在"调测白名单"下能否真的创建/更新**（决定 §2.6 的权益风险是否可绕过调试阶段） | ① AGC 申请**调试门**权益（5 工作日）；② 调测设备管理按 Push Token 加白本机；③ 端侧 `isLiveViewEnabled()` → `startLiveView` → `updateLiveView`；④ 用两个**非白名单**设备重复 | 白名单设备：`getActiveLiveView(id)` 能查到、通知中心出现卡片；非白名单：报错码是否为权益类（对照 03 篇 §4.5 错误码表）⇒ 明确"能不能先跑通再谈上架" |
| **E5** | **`javaScriptOnDocumentStart` 能否在页面首个 WebSocket 之前完成 hook**（对照安卓 B12 注入失效弯路） | ① 用 `javaScriptOnDocumentStart` 注入 hook `WebSocket.prototype` 的脚本；② 冷启动 + SPA 路由切换 + `location.reload()` 各 20 次；③ 记录"hook 生效时是否已存在活动 socket" | 每次加载都有 hook 生效标记，且**没有一次**出现"注入晚于页面首帧连接"（安卓壳的 `注入未在 document-start 生效` 同类现场）⇒ 若出现，先查 `scriptRules` 与同内容去重规则 |
| **E6** | **回前台是"恢复"还是"重载"**（决定交还策略与状态是否要重建） | ① 页面打点 `onPageShow/onPageHide`、`performance.navigation` 与自定义序号；② 后台 30s / 10 分钟各一次切回；③ 对比前后 DOM 状态与 JS 变量 | 命中 BFCache ⇒ 页面状态保留、`onPageShow` 触发；未命中 ⇒ 走重载。**判据必须两边都记**，否则会把"重载"误读成"恢复" |
| **E7** | **挂起/冻结后旧网络对象能否复用**（决定重连写法） | ① 挂起后回前台，直接用旧 `WebSocket`/旧 `httpRequest` 发一帧；② 再试 `close()` + `connect()` 新建 | 旧对象是否报 **2301103/2301107**（06 篇 E3 同源）⇒ 统一改成"先 close 再 connect"，不做复用分支 |
| **E8** | **`onRenderProcessNotResponding` 的实际触发阈值**（决定"卡死判据"能不能替代自建计时） | ① 页面注入死循环阻塞主线程（`while(true)`）；② 记录回调触发时刻与 `RenderProcessNotRespondingData.reason`；③ 分别在**前台/后台带长时任务/后台无任务**三种状态各跑一次 | 三种状态下回调**是否都触发**、时延多少 ⇒ 若后台被挂起时回调也不来，则"卡死判据"仍必须自建（回到 §2.8 的静默计时） |
| **E9** | **通知通道在后台的真实表现**（决定"后台能不能提醒"） | ① 申请通知授权 + 引导开横幅；② 挂后台后用 `notificationManager.publish` 发一条 `isOngoing` 通知与一条带 `progress` 的通知；③ 再在**无任何后台任务**（进程已挂起）时重复 | 长时任务期间通知是否立刻出现；进程挂起时是否出现/被延迟到回前台 ⇒ 明确"通知不是后台兜底"的边界 |
| **E10** | **进程被系统终止后的可观测性与恢复面**（决定"下次启动对齐"怎么做） | ① 挂后台让系统回收（或 `hdc shell` 强杀）；② 观察 `getAllContinuousTasks()` / 长时任务残留；③ 用通知点击 + WantAgent 拉起，检查 `onCreate(want)` 里能拿到什么 | 长时任务是否被自动清理；`appRecovery` 是否**不**触发（预期不触发）；恢复后哪些状态需要重建（工作区/会话/最后序列号）⇒ 形成"持久化清单"的最小集 |

---

## 6. 结论与最大风险

**一句话结论**：**鸿蒙侧能复刻的是"前台体验 + 用户可见反馈"，不能复刻"后台自持连接"；
最大风险是后台保活与实况窗权益的强耦合（`DATA_TRANSFER` 的进度更新被官方要求必须是实况窗，
而实况窗没有匹配"远程 AI 任务进度"的场景准入）。**

拆成三条：

1. **可以照搬（风险低）**：前台套壳、注入（`javaScriptOnDocumentStart` 反而比安卓干净）、
   原生 ↔ 网页通道（`javaScriptProxy` / `WebMessagePort` / `runJavaScript`）、
   任务与通知的**应用层逻辑**（`TaskStore` 分层、空更新守卫、`PromotionPolicy` 的择优规则、
   "只信原生侧 delta"的判活纪律）——这些与平台无关或平台能力更强。

2. **必须改造（风险中）**：前后台切换策略、判死判据（35s → 秒级或事件驱动）、
   回前台恢复（BFCache vs 重载）、通知渠道与提醒方式的用户引导。
   依据是鸿蒙把"隐藏"与"暂停"分开、把"后台"实现为断电而非节流（§2.1/§2.8、[06](06-ArkWeb后台行为-节流冻结与网络管控.md)）。

3. **不可行 / 需用户与桌面端决策（风险高）**：
   ①"后台原生接管 relay 连接并订阅任务正文"整层——官方对终端类后台长连接**直接劝退**，
   `DATA_TRANSFER` 对低频心跳会被一致性校验收回，`TASK_KEEPING` 需系统级 ACL；
   ②"后台实时跟手"若必须保留，唯一官方允许的形态是**服务端（桌面端 ZCode）推送 + 客户端前台秒连**
   （Push 通道还要 `SUBSCRIBE_TIMER` 等白名单场景 + 频控，见 [04](04-实况窗-Push与端云协同.md)）；
   ③ 实况窗做"任务进度卡"的**权益通过概率低**（`PROGRESS` 排除"后台自动"、`TIMER` 排除"AI 对话时长"，
   见 [05](05-实况窗-设计规范与版本矩阵.md) §3.2）。

**最大风险点（一句话）**：**把后台能力建立在"实况窗权益"上**——技术上官方把 `DATA_TRANSFER`
的存活条件绑在实况窗进度更新上，政策上实况窗对"远程任务进度"没有准入场景且要求已上架 + 月活 ≥1000；
一旦申请不下来，后台链路会在**实现完成后**才被卡死。**建议先做 E1/E4 两个验证，再决定是否投入后台部分的实现。**

---

## 7. 来源清单

### 7.1 安卓壳侧（只读参考实现）

| # | 来源 | 用途 |
| --- | --- | --- |
| 1 | `android-shell/README.md`（正文「项目现状 / 已知问题 A–F / 实现要点 1–20 / 交接文本区」） | 链路现状、硬数字（35s 判死、10s 泵、MAX_PROMOTED=2、FAULT 冷却、空更新守卫） |
| 2 | `android-shell/docs/15-后台原生承载-独立设计.md` | 协议契约与原生承载的选型（§4 已被推翻，仅参考协议部分） |
| 3 | `android-shell/docs/16-后台60秒墙-根因取证与原生承载.md` | 判死/接管/交还的现行形态与触发条件 |
| 4 | `android-shell/docs/17-多工作区多任务订阅-理论推导.md` | 多工作区/多任务的 N 元能力与桌面端未知 |
| 5 | `ShellRuntime.kt` / `RelayBridge.kt` / `Tier2Probe.kt` / `TaskStore.kt` / `PromotionPolicy.kt` / `Notifier.kt` / `LiveUpdate.kt` / `KeepAliveService.kt` / `WebAppBridge.kt` / `MainActivity.kt` | 关键函数与常量（只看关键函数/类） |

### 7.2 鸿蒙侧（官方文档 / SDK / 官方镜像）

| # | 文档或来源 | 类型 | 链接或 `documentId` |
| --- | --- | --- | --- |
| 1 | 长时任务(ArkTS)（类型表、一致性校验、DATA_TRANSFER 进度必须为实况窗、通知规则、数量限制） | 【官方原文】+【官方镜像原文】 | [harmonyos-guides/continuous-task](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/continuous-task)；镜像 `harmonyos-guides/continuous-task.md`（页面更新 2026-09-09） |
| 2 | 短时任务(ArkTS)（`requestSuspendDelay`；配额数字另见本表 #7 的 FAQ 原文） | 【官方原文】（事实来自 FAQ-172 与本机离线库；页面地址 ※） | [harmonyos-guides/transient-task](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/transient-task)※ |
| 3 | Background Tasks Kit 简介（挂起定义、四类任务、资源配额） | 【本机离线官方库】 | `开发指南/Background_Tasks_Kit_后台任务开发服务/Background_Tasks_Kit简介/background-task-overview` |
| 4 | Background Tasks Kit 接入规范（不得修改任务通知、恶意保活、违规处罚） | 【本机离线官方库】 | `开发指南/Background_Tasks_Kit_后台任务开发服务/Background_Tasks_Kit接入规范/bgtask-design-formula` |
| 5 | 后台任务合理使用（各类型"退后台状态"表） | 【本机离线官方库】 | `最佳实践/应用功耗优化/后台任务低功耗/后台任务合理使用/bpta-use-of-background-tasks` |
| 6 | 后台系统资源合理使用（`runningLock.BACKGROUND` 已废弃、无长时任务应释放资源） | 【本机离线官方库】 | `最佳实践/应用功耗优化/后台任务低功耗/后台软件资源合理使用/后台系统资源合理使用/bpta-reasonable-system-use` |
| 7 | 如何处理 H5 页面的定时器退到后台不执行问题（ArkWeb FAQ + 短时任务配额） | 【本机离线官方库】 | `FAQ/Web框架/Web开发_ArkWeb/如何处理H5页面的定时器退到后台不执行问题/faqs-arkweb-172` |
| 8 | 如何解决应用退至后台 TCP 连接会被中断 | 【本机离线官方库】 | `FAQ/网络/网络_Network/如何解决应用退至后台TCP连接会被中断/faqs-network-73` |
| 9 | App 进入后台，是否会回收网络权限 | 【本机离线官方库】 | `FAQ/网络/网络_Network/App进入后台_是否会回收网络权限/faqs-network-104` |
| 10 | 前端页面调用应用侧函数（`javaScriptProxy` / `registerJavaScriptProxy`） | 【官方原文】（事实另见本表 #15 官方 FAQ；页面地址 ※） | [harmonyos-guides/web-in-page-app-function-invoking](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/web-in-page-app-function-invoking)※ |
| 11 | 应用侧调用前端页面函数（`runJavaScript` / `runJavaScriptExt`） | 【官方原文】 | [harmonyos-guides/web-in-app-frontend-page-function-invoking](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/web-in-app-frontend-page-function-invoking) |
| 12 | 建立应用侧与前端页面数据通道（`createWebMessagePorts` / `postMessage`） | 【官方原文】 | [harmonyos-guides/web-app-page-data-channel](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/web-app-page-data-channel) |
| 13 | Web 组件中，如何在 DOM 树加载前后运行 JS 脚本（`javaScriptOnDocumentStart` / `javaScriptOnDocumentEnd`） | 【本机离线官方库】 | `FAQ/Web框架/Web开发_ArkWeb/Web组件中_如何在DOM树加载前后运行JS脚本/faqs-arkweb-59` |
| 14 | Web 加载的 H5 页面跳转后如何避免注册资源被清空（document-start 注入用法） | 【本机离线官方库】 | `FAQ/Web框架/Web开发_ArkWeb/Web加载的H5页面跳转后_如何避免原有页面注册的资源被清空/faqs-arkweb-42` |
| 15 | `javaScriptProxy` 与 `registerJavaScriptProxy` 的区别（一个 / 多个对象） | 【本机离线官方库】 | `FAQ/Web框架/Web开发_ArkWeb/javaScriptProxy和registerJavaScriptProxy有什么区别_能注册多少个对象/faqs-arkweb-20` |
| 16 | `runJavaScript` 与 `runJavaScriptExt` 的区别与调用时机 | 【本机离线官方库】 | `FAQ/Web框架/Web开发_ArkWeb/Webview的runJavaScript和runJavaScriptExt有什么区别…/faqs-arkweb-21` |
| 17 | 发布通知 | 【官方原文】（slug 经官方镜像头部 `url` 字段确认，页面未逐页打开） | [harmonyos-guides/publish-notification](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/publish-notification)※ |
| 18 | 管理通知渠道（SlotType 提醒方式表；横幅默认关闭） | 【本机离线官方库】 | `开发指南/Notification_Kit_用户通知服务/管理通知渠道/notification-slot`；页面地址 ※ |
| 19 | `@ohos.notificationManager`（`addSlot` / `requestEnableNotification`） | 【本机离线官方库】 | `@ohos.notificationManager (NotificationManager模块)` |
| 20 | 调用 `notificationManager.publish` 发布通知失败（需用户开启通知开关） | 【本机离线官方库】 | `FAQ/用户通知服务_Notification_Kit/调用notificationManager_publish发布通知失败/faqs-notification-kit-8` |
| 21 | 应用恢复开发指导（`appRecovery`；只覆盖崩溃/冻屏自恢复） | 【官方原文】 | [harmonyos-guides/apprecovery-guidelines](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/apprecovery-guidelines) |
| 22 | `@ohos.app.ability.appRecovery`（`enableAppRecovery` / `RestartFlag` / `saveAppState`） | 【本机 SDK 直读】 | `E:\DevEco Studio\sdk\default\openharmony\ets\api\@ohos.app.ability.appRecovery.d.ts`（API 24） |
| 23 | 如何在 Stage 模型中创建后台任务（三方不支持 `ServiceExtensionAbility`） | 【本机离线官方库】 | `FAQ/程序框架/后台任务开发_Background_Tasks/如何在Stage模型中创建后台任务/faqs-background-tasks-1` |
| 24 | 应用运行时进程资源使用规格（内存 2GB / CPU 阶梯 / IO 1GB） | 【本机离线官方库】 | `FAQ/程序框架/后台任务开发_Background_Tasks/应用运行时进程资源使用规格/faqs-background-tasks-6` |
| 25 | ArkWeb `Web` 组件（`javaScriptOnDocumentStart` / `onRenderProcessNotResponding` / `onRenderProcessResponding` / `onRenderExited`） | 【本机 SDK 直读】 | `...\ets\component\web.d.ts:10511 / 10801 / 10811 / 9132`（API 24） |
| 26 | `WebviewController`（`onActive` / `onInactive` / `pauseAllTimers` / `resumeAllTimers`） | 【本机 SDK 直读】 | `...\ets\api\@ohos.web.webview.d.ts:4065 / 4087 / 6022 / 6032`（API 24） |
| 27 | `NotificationRequest` 字段（`isOngoing` / `isUnremovable` / `groupName` / `badgeNumber` / `progress`） | 【本机 SDK 直读】 | `...\ets\api\notification\notificationRequest.d.ts:146 / 162 / 170 / 399 / 512`（API 24） |
| 28 | `@ohos.resourceschedule.backgroundTaskManager`（枚举与回调全集） | 【本机 SDK 直读】 | `...\ets\api\@ohos.resourceschedule.backgroundTaskManager.d.ts`（API 24） |
| 29 | `@ohos.data.preferences`（轻量键值；需 `flush()`） | 【本机离线官方库】 | `@ohos.data.preferences (用户首选项)`；FAQ `faqs-local-database-management-28` |
| 30 | 用户首选项/键值库/关系型库的选型对照 | 【本机离线官方库】 | `FAQ/本地数据和文件/本地数据库管理/PersistentStorage_Preferences_键值型数据库_关系型数据库的区别及适用场景/faqs-local-database-management-53` |

> **※ 链接说明**：带 ※ 的链接是按官方 `documentId` 的 slug（或官方镜像头部的 `url` 字段）直接拼出的官方页面地址
> （命名规则与本文已逐页打开核对的 `continuous-task`、`web-in-app-frontend-page-function-invoking`、
> `web-app-page-data-channel`、`apprecovery-guidelines` 一致），**页面本身未被逐页打开核对**；
> 对应的**内容事实**均来自本机离线官方库 / 官方 SDK / 官方镜像，可按下表的 `documentId` 复查。
> 不带 ※ 的链接均为检索命中并已打开、或来自本机离线官方库的 `documentId`。
>
> **相邻文档**：同目录 `01`–`05`（实况窗专题）、`06-ArkWeb后台行为-节流冻结与网络管控.md`（后台行为专题）、
> `07`（后台保活官方方式全集）、`08`（ArkWeb 能力与 API 清单）——07/08 为本轮并行产出，文件名以 `README.md` 索引为准。

---

*本文档为本地调研快照，仅收录公开官方文档内容与工程推论；连接凭证（sid/hash/mid 等）一律未收录。不随仓库分发（见同目录 `.gitignore`）。*
