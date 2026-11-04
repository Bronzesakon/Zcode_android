# 路径二调研：原生 WebView 壳 · 前台伪装 · 防重载

> 调研日期：2026-11-04
> 参考壳：`E:\Zcode_harmony\android-shell`（2026-09-18 基线，16,789 行 Kotlin/JS）
> 网页基线：`Reference_web/3.17.1/assets/index-Dwhx77cK.js`（6,986,617 B）
> 结论分级：**【源码实证】**＝bundle 原文直接支持；**【真机实证】**＝参考壳真机日志；
> **【平台实证】**＝AOSP/Chromium 源码或官方文档；**【推断】**＝证据强但缺一环；**【待验证】**＝需真机。
> 本文只做调研，未改动任何代码；与 README 现有「不拿 WebView/Vue 当样式兜底」的架构边界冲突，
> 若采纳路径二需同步改写 README / ARCHITECTURE。

---

## 0. 结论摘要（先读这一节）

1. **参考项目确实是「原生 WebView 薄壳」**：`MainActivity` 只放一个 WebView，业务全部由
   document-start 注入的 `inject.js`（4,021 行）+ 原生保活/通知层承担。它已经把这条路走通过：
   后台存活 **29 分 26 秒**、后台心跳 176 次、收到 214 帧【真机实证】。

2. **它明确试过并停用了「伪装前台」**：把 `document.visibilityState` 谎报成 `visible`、
   吞掉全部生命周期事件。真机结论是**两头都亏**——挡不住 Chromium 的定时器节流
   （10s 心跳在 7 分钟后台里只跑了 6 次，约 1 次/分钟），又让页面不知道自己去过后台、
   回前台后不再走自己的 `suspend → recover` 通路，订阅永远拿不到 ack。
   代码里 `SHELL_VISIBILITY_HIJACK=false` 留了墓碑【真机实证】。

3. **网页自己不会重载**。3.17.1 里 `location.reload()` 只有 4 处：React 错误边界的
   「重试」按钮、失败屏的手动按钮；连接层暴露的 `reloadPage` **只有定义、没有任何调用点**。
   所有「回来白屏/重新加载」都是**宿主或系统**造成的，不是网页自愈【源码实证】。

4. **可行的路径二应当是「不撒谎的保活 + 只读壳」**：
   前台服务 + 唤醒锁 + 渲染优先级 + 原生 10s 心跳泵 + document-start 钉路由；
   **壳不主动 reload、不关页面 socket、不替页面重连**；只有页面进入终态（KICKED）或进程
   被杀后的冷启动才 reload/重载，且必须带路由与任务预设。

5. **「伪装前台」只建议作为可开关的实验，且必须改成修正版**：
   只改 `document.visibilityState/hidden` 的 getter，**不吞事件**；回前台时先判链路，
   链路已死才用「合成 `freeze` → 合成 `visibilitychange/resume`」回放一次页面的
   `suspend → recover` 周期。这正好补上参考壳旧实现的缺口。**上线前必须真机 spike**，
   因为它会改变页面自己的 `mobile-task-view-update`（查看状态）上报语义【待验证】。

6. **Android 侧没有干净的「让后台 WebView 保持可见」的公开手段**：
   WebView 可见性 = `attached && viewVisible && windowVisible`（AwContents），前台服务不改变
   window 可见性；`WebView.setWindowVisibility` 是隐藏 API（参考壳 CI 报 Unresolved reference）。
   唯一非常规做法是把 WebView 挂到始终可见的悬浮窗（需要 `SYSTEM_ALERT_WINDOW`，
   ColorOS 已有「悬浮显示」误报前科），不建议产品化。PiP 是唯一合规的「真前台」，
   但用户可见、耗电、熄屏即失效。

---

## 1. 参考壳解剖（android-shell）

### 1.1 规模与分层【源码实证】

| 层 | 文件 | 规模 | 职责 |
| --- | --- | --- | --- |
| 原生宿主 | `MainActivity.kt` | 1,169 行 | WebView 配置、注入时序、文件选择、通知点击定位、自愈重载 |
| 运行时 | `ShellRuntime.kt` | 2,598 行 | 前后台交接、原生承载、心跳泵、诊断、状态编排 |
| 协议观测/承载 | `RelayBridge.kt` / `Tier2Probe.kt` / `RelayWire.kt` | 1,342 + 1,073 + 767 行 | 只读观测页面帧 + 可选的原生直连 relay（Tier2） |
| 注入层 | `assets/inject.js` | 4,021 行 | document-start 注入：WS hook、心跳、体征、定位、只读取证 |
| 线协议 | `assets/zcode-protocol.js` | 1,961 行 | 页面帧的编解码与 RPC 取证 |
| 通知/卡片 | `notify/*` | 约 1,000 行 | 4 渠道 + 7 类通知 + Android 16 Live Updates |

### 1.2 WebView 配置（照抄清单）【源码实证】

| 配置 | 值 | 理由 |
| --- | --- | --- |
| `javaScriptEnabled` / `domStorageEnabled` | true | SPA 必需 |
| `mediaPlaybackRequiresUserGesture` | false | 页面音频/视频 |
| `mixedContentMode` | NEVER_ALLOW | 安全 |
| `cacheMode` | `LOAD_DEFAULT` | 走 HTTP 缓存，重载更快 |
| `useWideViewPort` / `loadWithOverviewMode` | **不设** | 响应式 SPA 会被改坏视口 |
| UA | **不改** | 页面自己做移动端检测，改 UA 会改行为 |
| `setRendererPriorityPolicy` | `RENDERER_PRIORITY_IMPORTANT, false` | 降低后台渲染进程被回收的概率（实例方法） |
| `onPause()` | **永不调用** | 会 suspend 页面定时器（含心跳） |
| 返回键 | `moveTaskToBack(true)` | 保住 Activity/WebView/socket |
| `configChanges` | `orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|density|uiMode` | 防旋转/深色切换重建 Activity |
| `launchMode` | `singleTop` | 防重复实例 |
| document-start | `WebViewCompat.addDocumentStartJavaScript`（不支持时加载后补注） | 必须早于页面首帧，才能拿到第一个 WebSocket |

URL 与登录（照抄清单）：

- 只接受 `https` + `z.ai`/子域 + 路径以 `/remote` 开头（防恶意二维码把注入脚本指向攻击者域）；
- OAuth 授权页（`chat.z.ai/api/oauth/authorize`、`bigmodel.cn/login`）与回调
  （`zcode.z.ai/web-remote/callback`）**必须留在同一个 WebView**：登录 nonce 存在
  `sessionStorage`，交给系统浏览器会报「OAuth CSRF 检测失败」；
- 壳**不要改写 URL 的 `app_version`**：它来自桌面端扫码链接，改了会与桌面端版本闸门打架。

### 1.3 后台保活的四个支点【真机实证】

| 支点 | 实现 |
| --- | --- |
| 前台服务 | `KeepAliveService`，`foregroundServiceType="specialUse"`（避开 Android 15 `dataSync` 6h/24h 配额），`START_STICKY`，`onTaskRemoved` 不自杀 |
| 启动时机 | 只在前台启动（Android 12+ 禁止后台启动 FGS） |
| 唤醒锁 | `PARTIAL_WAKE_LOCK`，只在「已离开且承载在跑」时持有 |
| 渲染优先级 | `setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT, false)` |

实测：后台 30 分 4 秒，注入层心跳 118 次 / 原生泵发令 120 次 / 收到 214 帧；
另一轮 29 分 26 秒 / 176 次心跳。**判据是「心跳真的执行了」，不是「又收到多少帧」**
（帧可以在一个定时器都不跑的情况下继续到达）【真机实证】。

### 1.4 原生心跳泵（关键机制）

- `ShellRuntime` 的 `mainHandler` 每 **10s** `evaluateJavascript("window.__zcodeShellHeartbeat()")`，
  首拍退后台后 3–6s。
- 注入层 `heartbeatTick()` 在 `!appForeground` 时向**页面自己的 socket** 发一帧顶层
  `pair_status_query`（`device_sid` + `client_ts`），喂住桌面端配对状态。
- 泵的两跳都必须是**异步消息**，否则会被 Choreographer 同步屏障饿死（曾迟到 3 分 26 秒）。
- 只在「设置 → 电池 → 应用耗电管理」放行后才真正有效；ColorOS 实测出现过
  「+15s 定时消息迟到 10 分 30 秒、进程 CPU 十分钟零 tick」【真机实证】。

### 1.5 只读壳契约（最重要的工程结论）【真机实证】

参考壳在 2026-09-15 用真机日志把「壳主动干预页面」定罪为**自伤**：

- `nudgeReconnect` 在「进对话 5s 未出详情」时 40 秒内拆了 10 次页面 socket；
- `forceReconnect` 在每次回前台、入站帧静默 >60s 时先拆再说；
- 拆掉的每一次都是页面正在用的那条 relay 连接，用户看到的就是「转圈 / 要重连好几次」。

对照实验：关掉全部干预后，页面自己的订阅 ack 之后 **5 分钟零生命周期事件**。
由此定下 `SHELL_READ_ONLY=true`：**壳永不关闭页面 socket、永不自动重载页面**。
对页面的写入面只保留七处（滚动条 CSS、后台补心跳、KICKED 自愈重载、通知定位点击、
死链兜底重载、定位预设+重载、回前台对话恢复），且每一处都有「页面已经失败」的判据与限流。

### 1.6 已知边界

| 现象 | 结论 |
| --- | --- |
| 后台每 100–120s 页面自己关一次 relay socket | 页面自己的 `reconnectAfterStaleWaiting` 看门狗；公开 API 拦不住；**不要再投入**【真机实证】 |
| ColorOS 弹「正在当前页面悬浮显示」 | 壳没有 `SYSTEM_ALERT_WINDOW`、没有 overlay 窗口；疑似流体云提升或系统误判；**未定位** |
| 渲染进程被杀 | **全仓没有 `onRenderProcessGone`**；默认行为是应用崩溃 → 冷启动 → 页面重新加载（见 §4） |

---

## 2. 页面语义（3.17.1，源码实证）

### 2.1 生命周期 observer `kir`

```js
function kir({documentTarget:e,windowTarget:t,onSuspend:n,onRecover:r}){
  let i=!1,
      a=()=>{i=!0,n()},
      o=e=>{i&&(i=!1,r(e))},
      s=()=>{if(e.visibilityState===`hidden`){a();return} o(`visible`)},
      c=()=>o(`pageshow`), l=()=>o(`online`), u=()=>o(`resume`),
      d=()=>a(), f=()=>a();
  return e.addEventListener(`visibilitychange`,s),
         e.addEventListener(`freeze`,f),
         e.addEventListener(`resume`,u),
         t.addEventListener(`pageshow`,c),
         t.addEventListener(`pagehide`,d),
         t.addEventListener(`online`,l), …}
```

要点：

- **只有真正读到 `hidden` 才 suspend**；恢复回调 `o` 还要求 `i` 为真（此前确实 suspend 过）。
- 恢复事件白名单（3.17.1 仍在）：`visible | pageshow | online | resume`。
- `suspend()` 只停心跳、清重连/等待定时器、置 `suspended`，**不关闭 socket、不卸载对话视图**。
- 因此「不告诉页面去过后台」= 页面永不 suspend，也永不 recover；旧劫持正是卡在这里。

### 2.2 页面传输的恢复梯子

```js
async recoverConnection(e=15e3){
  if(this.state===`suspended` && this.wasPaired && this.socket?.readyState===OPEN
     && (this.setState(`reconnecting`), this.sendPairStatusQuery()))
    try{await this.waitForPaired(3e3);return}catch{}
  this.reconnectAttempt=0; this.reconnectNow(); await this.waitForPaired(e)
}
```

- socket 还开着 ⇒ 发 `pair_status_query` + 等 3s；
- 否则 `reconnectNow()` 重拨，最多等 15s；
- 重拨成功（`onStateChange('paired')`）后：桥健康 ⇒ **只重拨，不重 bootstrap、不重订阅**；
  桥 degraded ⇒ 重新 bootstrap → `workspace-bridge-open` → 整树重挂 → 新订阅。

**这解释了「回来内容不回来」**：只要页面认为传输健康，它就不会重开工作区；
若旧订阅在桌面端已经丢了，页面自己不会知道。

### 2.3 版本闸门 `Pir`（会触发导航/重载）

```js
check(){
  … requestBootstrap().then(a=>{
    let s=a.desktopAppVersion?.trim();
    if(s){
      let t=new URL(e.getUrl());
      if(t.pathname.replace(/\/+$/,``)===`/remote/v4`
         && t.searchParams.get(`app_version`)!==s){
        t.searchParams.set(`app_version`,s);
        r=t.toString();
        e.isPageReady()?e.onMismatch(s):o();   // ← o() = navigate(新 URL)
        return
      }
    }
  })
}
```

- 桌面端版本 ≠ URL 的 `app_version` ⇒ 页面被 `isBlocked()` 卡住；页面已就绪则弹「版本不一致」，
  否则直接 `navigate` 新 URL（= 整页重载）。
- **这是唯一会由页面自己发起的整页加载**，触发条件是用户升级了桌面端。
- 对策：壳不要自己写死 `app_version`；让页面从 bootstrap 拿；接受这一次重载，但要保留路由。

### 2.4 页面没有「自愈式 reload」【源码实证】

`index-Dwhx77cK.js` 中 `location.reload` 仅 4 处：

| 位置 | 用途 |
| --- | --- |
| React 错误边界 `handleReload` | 用户点「重新加载」 |
| 另一个错误边界 `handleReload` | 同上 |
| 失败屏 `bar()` 的 `onReload` | 用户点按钮；`device-not-owned` 时反而是「切换账号」 |
| 连接层 options `reloadPage:()=>window.location.reload()` | **全 bundle 只有定义，无调用点（死参数）** |

整页加载（`location.replace` / `location.href`）只有三类来源：

1. **版本闸门**：`navigate: e => window.location.replace(e)`（§2.3）；
2. **登录/授权回调**：`onSuccess/onRetry → window.location.replace(app_return_to)`；
3. **用户点击**错误页/失败屏按钮。

⇒ 页面**不会**因为「连接断了/内容没出来」自己整页重载；重载来自宿主、系统、
版本闸门或用户动作。**「网页自己重载」在 /remote/v4 上不成立。**

### 2.5 页面态与「回到哪个会话」【源码实证】

| 通路 | 写入者 | 跨 reload | 跨进程死亡 | 备注 |
| --- | --- | --- | --- | --- |
| `history.state.zcodeMobilePage==='chat'` | 页面进入对话时 `pushState` | ✅ | ❌ | 冷启动为 null ⇒ 落 home |
| relay `mobileViewState.activeTaskId` | 页面/原生承载 | ✅ | ✅ | 决定开哪座桥、哪个任务 |
| `localStorage['zcode-v4-last-session:v1:<ws>']` | 页面 hook | ✅ | ✅ | **在 /remote/v4 上是死代码** |

槽位在 3.17.1 的变化：

```js
function vAn(e){return e.enabled
  && (e.rendererReload || e.restoreOnStartup===!0)
  && e.activeSessionId===null && e.draftFocusVersion===0}
…
yAn({…, enabled:!sn, restoreOnStartup:!!(B&&In), …})   // sn = !!webRemoteControlWorkspaceSwitcher
```

虽然新增了 `restoreOnStartup`（不再只认 reload），但 `enabled=!switcher` 在 Web 远控入口
恒为 false ⇒ **槽位机制在手机上仍然是死代码**【源码实证】。
真正有效的只有：`history.state`（跨 reload）+ relay `mobileViewState`（跨进程）。

### 2.6 3.17.1 新增：`mobile-task-view-update`

`WebRemoteControlTaskViewReporter` 在 `visibilitychange/freeze/resume/pagehide/pageshow` 时上报：

```js
r=()=>n(document.visibilityState===`visible` ? f.current.view : null)
```

可见 ⇒ 上报当前查看的 `{workspace,taskId}`；隐藏 ⇒ 上报 `null`。
**这是「伪装可见」最大的语义副作用**：后台时桌面端会一直以为用户还在看那个任务，
可能影响未读/通知策略【待验证，桌面端消费面未知】。

---

## 3. 「伪装前台」到底能骗到什么

### 3.1 三层拆解

| 层 | 手段 | 能解决 | 解决不了 | 证据 |
| --- | --- | --- | --- | --- |
| **JS 页面状态机** | 改 `document.visibilityState/hidden` getter；吞/合成生命周期事件 | 让页面不 `suspend()`、不停心跳；让页面在指定时刻走自己的 `recover()` | Chromium 的定时器节流、rAF 暂停、渲染进程冻结 | 【真机实证】+【源码实证】 |
| **Android 窗口** | 保持 Activity 可见（PiP/悬浮窗/1px overlay） | 真正的 `visible`：节流豁免、rAF 继续、页面不 suspend | 熄屏/系统冻结；合规与厂商弹窗 | 【平台实证】 |
| **Android 进程** | FGS + wake lock + renderer priority + 电池白名单 | 进程不死、网络可用、`onmessage` 继续派发 | JS 定时器节流（前台服务不改变 window 可见性） | 【平台实证】 |

### 3.2 WebView 可见性的判定【平台实证】

`AwContents.java`：

```java
boolean visible = mIsAttachedToWindow && mIsViewVisible && mIsWindowVisible;
```

| 场景 | viewVisible | windowVisible | attached | visibilityState |
| --- | --- | --- | --- | --- |
| 正常显示 | ✅ | ✅ | ✅ | `visible` |
| `setVisibility(GONE/INVISIBLE)` | ❌ | ✅ | ✅ | `hidden` |
| Activity 退后台 | ✅ | ❌ | ✅ | `hidden` |
| 多窗口/对话框部分遮挡 | ✅ | ✅ | ✅ | 通常 `visible` |
| `removeView` / detach | ✅ | ✅ | ❌ | `hidden` |
| `WebView.onPause()` | — | — | — | `hidden` |
| 仅 `pauseTimers()` | 不变 | 不变 | 不变 | 不变（JS 定时器全局硬暂停） |

⇒ **前台服务不会让 `mIsWindowVisible` 变 true**；想真可见，必须让承载 WebView 的窗口可见。

### 3.3 旧「可见性劫持」的真机判决【真机实证】

参考壳 2026-09-15 的实现：谎报 `visible` + `Object.defineProperty` + 吞掉
`visibilitychange/freeze/pagehide`。真机结果：

- **挡不住节流**：后台 7 分钟，页面自己的 10s 心跳只跳了 6 次（约 1 次/分钟）；
- **掐掉自救**：页面既不知道去过后台，也不知道回来了；回前台后 socket 重连成功
  （`1006` → 1s 内 open），但 `conversation subscription` 永远没有 ack，对话 0 行，
  只有重启应用（全新 bootstrap）才恢复；
- 结论：`SHELL_VISIBILITY_HIJACK=false`，历史实现整段删除，只留日志。

### 3.4 修正版「软伪装」设计（建议先 spike）

目标：保留「页面不 suspend」的收益，同时保住「回前台能自救」。

1. **document-start 只改 getter，不吞事件**：
   ```js
   Object.defineProperty(Document.prototype, 'visibilityState',
     {configurable:true, get:()=>'visible'});
   Object.defineProperty(Document.prototype, 'hidden',
     {configurable:true, get:()=>false});
   ```
   真实 `visibilitychange` 照常派发；页面的 `s()` 读到 `visible`，`i` 保持 false ⇒ 不 suspend；
   `TaskViewReporter` 也会读到 `visible`（副作用见 §2.6）。
2. **后台**：原生泵每 10s 驱动 `__zcodeShellHeartbeat()`，向页面 socket 补
   `pair_status_query`；WS `onmessage` 继续把帧喂给页面，React 状态继续更新。
3. **回前台**：先判链路
   - `socket OPEN && paired && 最近 N 秒有入站帧` ⇒ **什么都不做**（页面从未 suspend，订阅还在）；
   - 否则 ⇒ **生命周期回放**：先 `document.dispatchEvent(new Event('freeze'))`
     （让 observer 置 `i=true` 并走 `suspend()`），再
     `document.dispatchEvent(new Event('visibilitychange'))` 或 `window.dispatchEvent(new Event('pageshow'))`
     ⇒ 触发页面自己的 `recoverConnection()`。
   - 回放后 M 秒仍无入站帧/仍 0 行 ⇒ 才考虑「钉 `history.state` + reload」一次（5 分钟限流）。
4. **开关化**：`spoofVisibility` 默认关闭；打开时记录诊断行，便于 A/B。

**这个设计尚未在真机验证。** 必须验证的四件事：

- 页面是否真的不 suspend、后台是否仍能收到并处理会话帧；
- 回放 `freeze` 是否被页面 observer 接受（`freeze` 事件在 Android WebView 上本来不派发，
  但合成事件仍会走监听器）；
- `TaskViewReporter` 的「后台仍报查看中」是否影响桌面端通知/未读；
- ColorOS 在「完全后台行为」放行/不放行两种设置下的差异。

### 3.5 Android 窗口层的现实选项

| 方案 | 可行性 | 代价/风险 |
| --- | --- | --- |
| PiP（画中画） | 公开 API，Activity 保持可见/resumed | 用户可见、耗电、熄屏失效；不是「隐身后台」 |
| 1px / 0 尺寸悬浮窗 + WebView | 社区做法，需 `SYSTEM_ALERT_WINDOW`；WebView 需要从 Activity reparent 到 overlay | 权限敏感、Play 政策、ColorOS「悬浮显示」弹窗、输入法/触摸异常；参考壳明确未实现 |
| `WebView.setWindowVisibility` | 隐藏 API | CI `Unresolved reference`；隐藏 API 限制；**不要再试** |
| VirtualDisplay + MediaProjection | 需用户授权投屏 | 系统状态栏投屏提示；不可接受 |
| 只保进程（FGS） | ✅ 已验证 | 不能解除 JS 节流；需要原生泵 |

---

## 4. 重载成因与消除

| # | 成因 | 判据/现象 | 能否避免 | 做法 |
| --- | --- | --- | --- | --- |
| 1 | **应用进程被系统杀** | 冷启动；`performance.navigation.type==='navigate'`；`history.state` 归零 | 只能降低概率 | FGS + wake lock + renderer priority + 电池白名单 + 厂商自启引导；`START_STICKY`；`onTaskRemoved` 不自杀 |
| 2 | **WebView 渲染进程被杀** | `onRenderProcessGone`；页面空白/崩溃 | 不能完全避免 | 必须实现 `onRenderProcessGone` 并 `return true`（否则应用崩溃）；重建 WebView 后用路由+任务预设恢复 |
| 3 | **Activity 重建** | 旋转/深色/分屏后 `onCreate` | ✅ | `configChanges` 全量声明；`singleTop` |
| 4 | **壳主动 reload** | 日志里 `页面加载完成(第 N 次回调)` | ✅ | 只读壳：不因内容没出来 reload；只保留 KICKED 终态自愈（cap 2）与冷启动预设 |
| 5 | **版本闸门** | 桌面端版本 ≠ URL `app_version`；页面弹「版本不一致」/navigate | 部分 | 壳不写死 `app_version`；接受这一次，保留 `history.state` 与任务预设 |
| 6 | **页面错误/崩溃** | React 错误边界、失败屏 | 只能用户点 | 不自动点；壳可观测并上报 |
| 7 | **WebView 主框架加载失败** | `onReceivedError` + 系统错误页 | 部分 | 有网时重试一次；无网时显示壳自己的错误页（不自动 reload 循环） |
| 8 | **页面部署新版本** | URL 指向新 hash 的 JS | 只能接受 | 正常重载；缓存策略 `LOAD_DEFAULT`，不要强制离线包 |

**一句话**：把 reload 从「修复手段」降级为「终态兜底」。参考壳用真机证明：
壳每次「觉得页面卡了」就 reload，最终伤害的都是健康连接。

---

## 5. 状态保持（重载/冷启动后回到原处）

### 5.1 三条通路

```
页面态：history.state.zcodeMobilePage === 'chat'
        ├─ 跨 location.reload() 存活
        └─ 进程死亡/冷启动 ⇒ null ⇒ 落 home

任务态：relay mobileViewState.activeWorkspaceKey / activeTaskId
        ├─ 跨 reload / 跨进程存活
        └─ 可被页面的空推覆盖；原生承载可补写

会话槽位：localStorage['zcode-v4-last-session:v1:<ws>']
        └─ /remote/v4 上 enabled=false ⇒ 死代码（3.17.1 仍如此）
```

### 5.2 冷启动恢复的正确做法

1. **document-start 先钉路由**（在页面 JS 之前）：
   ```js
   if (!history.state?.zcodeMobilePage)
     history.replaceState({...history.state, zcodeMobilePage:'chat'}, '');
   ```
   这样冷启动也会直接挂载对话视图，不需要「加载完再 reload 一次」。
2. **任务目标交给 relay `mobileViewState`**：页面 bootstrap 后按它选 workspace/task，
   `workspace-bridge-open` 带 `taskId`，对话视图自动订阅。
3. **不要写 localStorage 槽位**：在 /remote/v4 上无效，只会造成「壳以为设了」的假象。
4. 若必须在**已知任务**上打开（通知点击），先写 `history.state` + 用原生承载/协议层
   把 `mobileViewState` 钉到目标任务，再让页面加载；**不要靠「reload 一次」**。

参考壳的 preset 路径（`__zcodeShellPresetTask`）真实起效的是 `replaceState` + reload，
其中 localStorage 写入是无效副产品【源码实证】。

---

## 6. 建议架构（若采纳路径二）

```
┌──────────────────────── Android App 进程 ────────────────────────┐
│  MainActivity（单 Activity）                                      │
│   └─ WebView（文档起点注入 inject.js）                             │
│       ├─ 页面自身：remote/v4 SPA（UI 100% 与网页一致）             │
│       └─ 注入层：WS 观测 / 后台补心跳 / 路由钉住 / 体征 / 诊断      │
│                                                                   │
│  KeepAliveService（specialUse FGS + 常驻通知）                     │
│   └─ 10s 心跳泵（evaluateJavascript）                              │
│                                                                   │
│  （可选，阶段 3）NativeCarrier：页面链路判死时原生直连 relay        │
│   └─ 可复用本仓库 Rust 协议核心，或移植参考壳 Tier2Probe            │
└───────────────────────────────────────────────────────────────────┘
```

### 分阶段

| 阶段 | 内容 | 验收判据 |
| --- | --- | --- |
| **P0 spike** | 最小壳 + 软伪装开关 + 生命周期回放；只跑一页对话 | 后台 10 分钟：页面不 suspend、心跳由泵驱动、回前台不 reload 且 3s 内有新帧 |
| **P1 最小可用** | WebView + document-start 注入 + 文件上传 + 通知权限 + FGS | 冷启动恢复上次对话；旋转不重建；返回键不杀进程 |
| **P2 保活/通知** | 心跳泵 + 从页面帧生成通知 + 流体云 | 后台 30 分钟存活；任务完成能通知；点击定位到任务 |
| **P3 原生承载（可选）** | 页面链路判死时原生接管；交还不变式 | 长后台仍能收帧/通知；交还后页面不断线（参考壳 Tier2 的判据可直接复用） |
| **P4 收敛** | 删除所有「壳主动 reload」；只留终态兜底 | 日志里壳发起的 reload 只出现在 KICKED/冷启动 |

### 与当前仓库的关系

- **UI 层**：路径二直接删掉 Compose 对话页的长期投入，UI 与网页 1:1 免费获得。
- **Rust 协议核心**：仍可作为 P3 原生承载的底座（它已经实现 relay/RPC/会话订阅），
  但需要补「与页面单控制端互斥的交接状态机」——参考壳的 `HandoffState` /
  `CarrierHandback` / `CarrierHandbackTest` 是现成设计。
- **缓存/性能**：网页自身的 HTTP 缓存 + WebView 的 `LOAD_DEFAULT` 已足够；
  冷启动慢主要是 JS 6.9MB + 首屏 bootstrap，壳能做的是**不重复加载**，不是自己再造缓存。

---

## 7. 风险与未决项

| # | 风险 | 等级 | 说明 |
| --- | --- | --- | --- |
| R1 | 软伪装改变 `mobile-task-view-update` 语义 | **高** | 后台仍上报「正在查看」，可能影响桌面端未读/通知；需真机 + 桌面端行为验证 |
| R2 | 合成 `freeze` 事件是否被页面正确接受 | 中 | 页面监听器在 document 上；合成事件应能触发，但 `freeze` 在 WebView 上本来不派发，需实测 |
| R3 | 后台 JS 定时器节流 | 已知 | 只能靠原生泵；不要指望伪装解除 |
| R4 | 进程/渲染进程被杀 | 高 | 只能降低概率；必须有 `onRenderProcessGone` 与冷启动恢复 |
| R5 | ColorOS 额外限制 | 高 | FGS 也可能被杀；需要电池白名单 + 自启 + 最近任务锁定引导 |
| R6 | 单控制端互斥 | 中 | 原生承载与页面不能同时持有 /ws；交接顺序错会被 KICKED 终态 |
| R7 | 网页版本漂移 | 中 | 注入依赖页面内部符号（`visibilityState`、`history.state`、socket 帧型）；页面改版需回归 |
| R8 | Play 政策 | 中 | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`、`SYSTEM_ALERT_WINDOW` 都是敏感项；侧载可忽略，上架需评估 |
| R9 | 「第二条路」与现有 README 架构边界冲突 | 确定 | 需同步改写 README/ARCHITECTURE，避免文档与现实打架 |

---

## 8. 证据索引

| 结论 | 出处 |
| --- | --- |
| 参考壳后台 29 分 26 秒 / 176 次心跳 | `Zcode_harmony/android-shell/README.md` P0 行、§「首次真机验证」 |
| 可见性劫持停用与真机数据 | 同上「实现要点」6、D12；`docs/18` §2.6 |
| 页面 socket 每 100–120s 自断 | 同上「已知问题 C」 |
| 只读壳七处写入面 | 同上「实现要点」14 |
| WebView 可见性三要素 | `docs/15-后台原生承载-独立设计.md` §5/§6（引 AwContents.java） |
| 生命周期 observer / 恢复梯子 / 版本闸门 | `Reference_web/3.17.1/assets/index-Dwhx77cK.js`（char 6,806,810 / 6,851,054 / 6,808,812） |
| 页面不自己 reload | 同上 `location.reload` 4 处；`reloadPage` 仅定义 |
| 槽位在 /remote/v4 是死代码 | 同上 char 4,736,834（`vAn`）、5,931,757（`enabled:!sn`） |
| 冷启动落 home | 同上 char 5,145,899（`SLn`/`xLn`） |
| `mobile-task-view-update` | 同上 char 6,807,998 |
