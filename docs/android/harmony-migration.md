# 安卓薄壳 App 迁移文档

> **这份文档是什么**:① 安卓薄壳子工程 `android-shell/` 的实现全貌;② 它的机制向本目录的鸿蒙工程（`entry/src/main/ets`）迁移的可行性结论、平台鸿沟、分阶段路线与开工前待定项。
> **给谁看**:接手"把安卓壳搬到鸿蒙"这件事的新对话。读完这一份应能独立开工,不必先翻聊天记录。
> **口径日期**:2026-09-20。当日完成了 7 路并行只读审计 + 3 轮定向调研（VPN/长时任务/后台流量实测）,结论已全部并入本文。
>
> **两条纪律**
> 1. **凭据不落纸**:`sid` / `hash` / `mid` 是远控凭证,任何文档、日志、输出里一律用占位符。
> 2. **`Docs/` 目录被 gitignore**（`Docs/.gitignore` 内容为 `*`）:`git status` 看不见、提交带不走、**ripgrep 会静默跳过**（在那里"全仓搜过没有"是假结论）。换机器必须手动拷贝。

---

## 1. 结论摘要（先读这一节）

| 问题 | 结论 |
| --- | --- |
| **安卓壳建成了吗** | 建成并已装机运行。Kotlin 主源 **10,654 行 / 31 文件**,注入层 JS **5,723 行 / 2 文件**;Kotlin 单测 **131 个**、Node 用例 **90 个**;CI 全绿,`pre` 分支滚动预发布 |
| **能迁移到鸿蒙吗** | **功能可复刻,架构不可复刻**。页面侧注入层与纯逻辑层（约 2,050 行）可逐行译成 ArkTS;**后台原生接管（判死→原生拨号→交还）在鸿蒙没有技术闭环** |
| **最省钱的一笔** | **不重写 relay 原生三层**（`RelayWire` + `RelayBridge` + `Tier2Probe` = **3,036 行 / 15–30 人日**）—— 它只在做后台接管时才需要 |
| **最危险的鸿沟** | **验证能力为零**:DevTools 通路、落盘日志、诊断入口在鸿蒙侧 grep 全 0 ⇒ 迁移的**第 0 步必须是打通取证**,不是写功能 |
| **后台到底能拿到什么** | 前台:原生自建长连接**没有任何限制**,随时可做。后台:**系统级**网络管控（不是浏览器内核问题,原生同样受限）;官方例外通道（长时任务）在类型匹配与实测时长上都不够用;**VPN 不是保活手段** |
| **现在卡在哪** | **鸿蒙真机不可达**（`hdc` 只有 COM 口）⇒ P0 开不了工;另有一批要拍板的决策（见 §9） |

---

## 2. 两个工程的现状

### 2.1 安卓壳 `android-shell/`（功能基线）

| 项 | 值 |
| --- | --- |
| 包名 / 应用名 | `com.zcode.remote` / 「ZCode 远程」 |
| 形态 | 原生 Kotlin（AndroidX）薄壳:WebView 加载 `zcode.z.ai/remote/v4`,注入脚本 hook 网页 WebSocket 读协议帧,**不嗅探 DOM** |
| 规模 | 主源 **10,654 行 / 31 文件**（最大:`ShellRuntime.kt` 2,422、`RelayBridge.kt` 1,342、`MainActivity.kt` 1,050、`Tier2Probe.kt` 927、`TaskStore.kt` 880、`RelayWire.kt` 767、`Notifier.kt` 582） |
| 注入层 | `app/src/main/assets/inject.js`（3,804 行）+ `zcode-protocol.js`（1,919 行）,全部 Web 标准 API |
| 测试 | Kotlin 单测 **131 个 `@Test` / 13 类 / 2,636 行**（纯 JVM 零 Android 依赖）+ Node **90 条** + 2 个一秒级结构检查脚本 |
| CI / 发布 | 仓库根 `.github/workflows/android-shell.yml`（468 行,4 job）;推 `pre` 覆盖式滚动预发布,`v*` tag 发正式版 |
| 已实现的系统能力 | 通知（4 渠道 7 类）、**流体云实时卡片**（Android 16 Live Updates）、前台服务保活、**后台原生接管 relay**、回前台交还与对话恢复、多工作区轮换、诊断日志与 adb 取证 |

### 2.2 鸿蒙壳 `entry/`（迁移的接收端）

| 项 | 值 |
| --- | --- |
| 包名 / 形态 | `com.zcode.control`;Stage 模型,ArkTS + hvigor,单 `entry` 模块;API 6.1.1(24);`deviceTypes: phone + tablet` |
| 规模 | **1,448 行 ArkTS**（`Index.ets` 1,068 / `EntryAbility.ets` 268 / `common/` 97 / backup 15） |
| 权限 | 仅 2 条:`INTERNET`、`GET_NETWORK_INFO` |
| 已有能力 | 扫码连接 + URL 校验与持久化;状态栏三态固定色 + 沉浸式占位;**上传模态框 + 下载代理**;返回键与渲染崩溃恢复;前台屏幕常亮;深浅色/语言跟随;深链 `https://zcode.z.ai/remote*` |
| 完全没有 | 通知、实况窗、后台任务四件套、WantAgent、原生长连接、document-start 注入、`WebMessagePort`、会话索引/状态层、多工作区、页面观测与看门狗、落盘日志、设置页、**DevTools 通路**;无 CI,单测仅模板 |

> 详细的逐项清单与 grep 证据见 §6。

### 2.3 可用的参考资产

| 资产 | 位置 | 用途 |
| --- | --- | --- |
| **知识库 Docs/**（47 篇,六组） | `E:\Zcode_harmony\Docs\`（**gitignore**） | 鸿蒙实况窗 01–05;鸿蒙后台行为 06–09;remote-v4 网页逆向 10–21;壳端机制与取证 22–26;网页快照与更新影响 27–28;安卓壳功能域与分区审计 29–47。索引与时效性见 §10 |
| **clashbox 源码**（鸿蒙 Clash 客户端） | `E:\mimo\参考项目\clashbox` | 鸿蒙上"真 VPN + 长时任务 + 自愈看门狗"的**可读样本**;它的权限清单与保活手法是判断"后台流量可行性"的第一手材料（§5.5） |
| **ScribePad**（鸿蒙听写应用） | `E:\ScribePad`（= `E:\scribepad`） | 已经落地了 `ContinuousTaskManager`（`AUDIO_RECORDING` / `DATA_TRANSFER` 长时任务）+ `BackgroundWorkCoordinator`（通知与任务协调）,是"合法长时任务"的现成写法 |
| zemote（Flutter） | `E:\Zcode_harmony\zemote`（gitignore,非 submodule） | 早期参考:通知判定算法与协议结构;其协议实现**不要复用**（薄壳路线用不上） |
| 本目录工具 | `Docs/tools/cdp.ps1` | 真机 WebView 的 CDP 调试助手（安卓侧用法） |
| 本轮审计报告 | `.workbuddy-ai/migration-review-20260920/`（未跟踪） | 7 份子代理报告 + 3 份专项调研（A–K）,共 4,500+ 行,本文所有 `文件:行号` 级结论的原始出处 |

---

## 3. 已敲定的决策（勿改）

> D1–D14 来自用户;带 † 的是工程师补充决策（可改,但需说明理由）。D14 已按后续事实修订。

| # | 决策项 | 结论 |
| --- | --- | --- |
| D1 | 技术路线 | **薄壳**（WebView 套壳),不做协议自实现的重资产客户端 |
| D2 | 数据获取 | **hook WebSocket 读协议帧**,**非** DOM 嗅探（DOM 路线已被否决） |
| D3 | 壳技术栈 | **原生 Kotlin（AndroidX）**,不用 Flutter |
| D4 | 包名 / 应用名 | `com.zcode.remote` / 「ZCode 远程」 |
| D5 | 子项目目录 | `E:\Zcode_harmony\android-shell\` |
| D6 | 前台服务类型 | `specialUse`（避开 `dataSync` 的 Android 15 6h/24h 上限);必须同时声明 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` |
| D7 | 订阅范围 | **主动订阅所有 workspace** |
| D8 | 常驻通知格式 | 标题 = 任务名;正文 = `状态 · 最新进展` |
| D9 | 常驻通知状态粒度 | 只区分「运行中」/「等待确认」 |
| D10 | 完成通知 | **有提示音 / 震动**,可拉掉 |
| D11 | 点击通知 | 拉起 App **并尝试定位到该任务** |
| D12 | 可见性劫持 | 曾按"预防性实施"落地,**真机证明它不解决节流**,实现已停用删除（§4.16) |
| D13 | 编译 | 完全依赖云端 CI,本机不装安卓工具链 |
| D14 † | 签名 | **本项目专用 keystore**（PKCS12 / 别名 `zcode-remote`),与鸿蒙工程材料无关;**口令一律不入库**（鸿蒙工程 `build-profile.json5` 现存明文口令,属待修项,见 §11) |
| D15 † | 通知渠道划分 | 4 渠道:`keepalive`(MIN)/`running_tasks`(LOW、静音)/`task_attention`(DEFAULT)/`task_completed`(DEFAULT);渠道重要性创建后 App 不可改,必须一次定好 |
| D16 † | 等待确认的通知方式 | 就地更新常驻卡 **+ 补发一条有声提醒**;不依赖"把同一通知换到另一个渠道"这种不可靠行为 |
| D17 † | 常驻通知 ID | 由 `(workspaceKey, sessionId)` 稳定哈希派生（`[100000,900000)`),保证同一任务**就地更新而非新增** |
| D18 † | 通知权限时机 | 首次进入控制页即申请 `POST_NOTIFICATIONS`（不等有任务在跑） |
| D19 † | 扫码 / 语言 / minSdk | ZXing + 剪贴板 + 手输三入口;仅中文;`minSdk 26` |
| D20 † | 发布方式 | 推 `pre` 自动出滚动预发布（资产名固定,下载链接恒定）;`v*` tag 出正式版,说明取自 CHANGELOG 段落 |
| D21 † | 版本号 | `versionName = <zcodeBaseVersion>-pre.<运行号>`;**`versionCode` = 工作流运行号**（单调递增 ⇒ 任何 CI 产物都能覆盖安装上一个）;基准在 `gradle.properties` 的 `zcodeBaseVersion` |
| D22 † | ABI | 不做拆分,单一通用 APK（纯 Kotlin,无 native 库） |

---

## 4. 安卓壳实现全貌

### 4.0 规模与三层结构

```
┌──────────────── 网页（别人托管,壳零引用其资源） ────────────────┐
│ zcode.z.ai/remote/v4 · React 19 + Lexical + TanStack Virtual    │
│ 自带 relay WebSocket（手机 ↔ zcode relay 服务器 ↔ 桌面端）      │
└──────▲────────────────────────────────────┬─────────────────────┘
       │ ① 注入层（inject.js + zcode-protocol.js,document-start 起） │
       │   hook WebSocket → 被动转发协议帧;心跳/体征/档 0 快照/外观  │
       │ ② 桥 window.ZCodeShell.postMessage(event, json) → 原生      │
┌──────▼──────────────────────────────────────────────────────────┐
│ ShellRuntime（2,422 行）= 中央调度:页面消息分发 / 心跳泵 / 判死 / │
│   接管 / 交还 / 通知发布                                        │
│  ├ TaskStore + NotifyState + SessionsIndexState + ControllerTasks│
│  │   → 三源合成"有效相位" → 通知更新（含 3s 完成观察窗）        │
│  ├ Notifier + PromotionPolicy + LiveUpdate → 通知 7 类 + 流体云  │
│  ├ Tier2Probe + RelayBridge + RelayWire → 后台原生 relay 承载    │
│  ├ ShellLog + Diagnostics + DiagReceiver → 诊断与取证            │
│  └ Prefs / RemoteUrl / InjectionReadiness / SurvivalVerdict /    │
│    CarrierHandback / ConversationRecoveryPolicy / PageBarColor   │
│ KeepAliveService（specialUse 前台服务）+ ZcodeRemoteApp + MainActivity │
└──────────────────────────────────────────────────────────────────┘
```

### 4.1 注入层:时序三保险 + 段落地图 + 就绪位

**三保险**（挂载点 `MainActivity.kt:417-467`）:

| # | 时机 | 实现 | 解决什么 |
| --- | --- | --- | --- |
| 1 | **document-start（主道）** | `WebViewCompat.addDocumentStartJavaScript`（`MainActivity.kt:445-448`);`applyUrl()` / `reloadPage()` 里**每次 `loadUrl` 前无条件重装**（`:479` / `:487`,刻意不做去重） | 页面 boot 期就建 socket 并握手,**晚一步就漏首帧** |
| 2 | onPageStarted 补注 | `injectStable()` = `evaluateJavascript`（`MainActivity.kt:300` / `:457-460`） | 主道未知失效时的最早补注点 |
| 3 | onPageFinished 兜底 | 同上（`:330`） | 再兜一道 |

- **脚本形态**:`zcode-protocol.js + "\n" + inject.js` 拼接后缓存（`:429-436`）;幂等守卫 `if (G.__zcodeShellInstalled) return;`（`inject.js:41-46`）。
- **晚注入收编（关键补丁）**:出站观测挂在**原型层**（`proto.send` / `proto.close`,`inject.js:326-341` / `:354-377`),补注前页面已创建的 socket 会在下次 `send()/close()` 时以 `this` 送进来 → `adoptSocket()` 当场补挂监听（`:395-404`),**零重连**;代价只在收编前的窗口缺入站帧（最长约一个心跳周期）。
- **取证行**:若 `document.readyState !== 'loading'`,注入层立刻打一行「注入未在 document-start 生效…」（`inject.js:48-60`）—— 这是"某次加载主道没生效"的唯一现场证据。

**`InjectionReadiness` 就绪位**（`core/InjectionReadiness.kt`,51 行）:
置位 = 注入层末尾 `post('ready')`（`inject.js:3788`）→ `ShellRuntime.kt:2119-2127`;**唯一复位点** = `onPageStarted`（`ShellRuntime.kt:2409-2412`,**先复位再补注**,否则会盖掉一条活着的注入）。类文档记着历史 bug:这条链曾零调用者 ⇒ 就绪位永久 latch 为 `true`。读者三处:诊断派发队列、`tryLocate`、`requestViewSnapshot`（均 40×600ms 重试）。

**`inject.js` 段落地图**（3,804 行,行号为实测）:

| 段 | 行号 | 职责 | 消息名 / 全局 |
| --- | --- | --- | --- |
| 前言 / IIFE | 1-64 | 幂等守卫、晚注入取证行 | `diag` |
| bridge 助手 | 65-82 | `post()` / `diag()`,全部 try 包裹 | — |
| **§0 页面日志汇** | 83-216 | 供 `window.zcode = {log}`（页面唯一日志出口）;从 args 抽机器可读 `{event}` | `pagelog` |
| **§1 可见性劫持** | 217-248 | **已停用**,只留一行日志 | `diag` |
| **§2 WebSocket hook** | 249-499 | 包装 `window.WebSocket`（保留 statics/prototype/name);原型层包 `send`/`close`;socket 索引;**Tier2 凭证移交（只驻内存,不落日志）** | `relaycreds` |
| §2 子块:收编 / 入站解码 | 380-497 | `adoptSocket`;`observeMessage`（string/Blob/ArrayBuffer 统一转文本） | — |
| **§2b 开销量具** | 500-747 | 收帧数/字符/解码耗时、`longtask`、页面加载计时;有流量才打「页面开销」 | `convtext` |
| **§3 协议客户端接线** | 749-869 | `P.RemoteClient`（`zcode-protocol.js` 实例）→ `sessions` / `controllertasks` / `status` | 同名 |
| §3b | 870-881 | 墓碑:原"主动开桥调度器"（D7）**别再加回来** | — |
| §3 子块:发帧 / `observeText` | 883-1082 | 数据帧 `{type:'data',payload}`;**`pair_status_query` 必须走顶层 type 控制帧**（走 data 的探针从来没被 ack 过,那正是"回后台立刻断线"的根因）;逐帧解析出 `pagews` | `pagews` |
| **§4 心跳 / 陈旧 / KICKED** | 1084-1499 | 见 §4.2 | `liveness` |
| **§5 通知点击定位** | 1500-1694 | `clickElement`（完整指针序列,React 认）、`findTaskElement`（先文本匹配再向上找可点祖先,最多 6 层）、`__zcodeShellLocateTask`、`__zcodeShellPresetTask` | 合成点击 + 写 localStorage/history |
| **§6b 档 3 恢复** | 1696-1772 | 钉 `history.state.zcodeMobilePage='chat'` + 300ms 后 reload | 改 history + 整页 reload |
| **§5b 进对话铁判准** | 1775-2145 | 信标（页面 RPC 或 DOM 视图）→ 3s 判定 → 再等 3s;两次都只记账 | `pagevitals(giveup)` |
| **§5c 看门狗量具** | 2147-2380 | `readVitals()`、`vitalsStalled()`、`__zcodeShellViewSnapshot`（档 0 观测量）;决策机器已拆 | `viewsnap` / `pagevitals` |
| §5d / §5e 实验件 | 2382-2520 | `relayKickTest`（开第二条连接验 KICK）、`relayDegradeTest` | 仅 adb 触发 |
| 后台失速自愈实验 | 2522-2710 | `__zcodeShellNudgeRecover`、`__zcodeShellNetProbe`、`__zcodeShellAfterCarrierReturn`（交还兜底:8s + 5 分钟闸门） | 取证用 |
| 造流 compose | 2712-3019 | `__zcodeShellComposeSend`、`__zcodeShellDiag(cmd)` 总入口 | 仅 adb |
| 前台 / 页面状态 / 视口 | 3021-3162 | `__zcodeShellSetAppForeground`（回前台先跑 KICKED 自愈）;视口与滚动条量具 | — |
| **§6 视觉三态 → 状态栏** | 3164-3376 | boot / main-header / main-surface + 主题三信号 + MutationObserver 合帧 | `pagestate` |
| **§7 悬浮滚动条** | 3378-3764 | 归零页面 14px 轨道 + 自绘 overlay（含 gutter 保险） | `diag` |
| boot | 3766-3804 | 安装顺序 → `post('ready')` → `reportLiveness()` | `ready` |

> **纪律**:注入层几乎全是 Web 标准 API —— 这决定了它**可原样搬到任何 WebView**（含 ArkWeb),只有两处例外:document-start 语义差异（§7 的 G11）与滚动条（ArkWeb 本就有 overlay,§7 的 G14）。

### 4.2 心跳与体征（后台唯一的节拍）

- **原生泵**:`PUMP_INTERVAL_MS = 10_000`、**首拍 3s**（`ShellRuntime.kt:964` / `:971`);只在 `userIsAway()` 为真时发令 —— `userIsAway() = 应用在后台 **或** 屏幕已熄灭`（`:1806-1815`;熄屏那条是 2026-09-13 真机补的）。
- **注入层唯一 tick**:`__zcodeShellHeartbeat`（`inject.js:1496-1498`),`heartbeatTick` 全文件仅此一个调用点 ⇒ **前台期间注入层零周期节拍**（与真机统计一致:前台看不到"页面开销"行）。
- **每拍顺序**（`inject.js:1383-1479`）:5s 去重 → 后台计数 → **体征量具**（卡态边沿立即上报,否则每 60s）→ `reportLiveness` → `reportPerf`（有流量才发,顺手带 `convtext`）→ 页面 RPC 窗口统计 → **陈旧检测**（`STALE_MS=90s` × 连续 2 拍,只读壳下"动手"退化为记账）→ **仅后台补心跳** `pair_status_query`（成功则记 `linkWindow.probes++`;前台一帧都不发）。
- **上报字段**（`liveness`）:`inboundFrames` / `pairAcks` / `outboundFrames` / `socketsOpened` / `socketsClosed` / `backgroundTicks` / `backgroundFirstTickDelayMs` / `lastInboundAgoMs` / **`convFrameAgoMs`**（页面最近一次收 conversation 帧距今,−1 = 从未;**这是"提前接管"与"选择性交还"的输入**)/ `paired` / `socketState` / `deviceSidKnown` / `injectedReady`。
- **壳侧记账纪律**:刻意**不拿注入层自述的静默去触发接管**,而是折成 `SystemClock.elapsedRealtime()` 口径自己算（`ShellRuntime.kt:183-224`）;`onLiveness` 用开窗时的三个 base 取 delta 出后台存活结论。

### 4.3 协议层:线格式、握手、三条纪律、一条禁区

**为什么需要它**:网页把 relay 数据放在 `rpc-frame` 里承载 —— **base64 分片 + crc32 校验的 ChannelClient 值流**,不是明文 JSON。页面侧只需被动转发（`zcode-protocol.js` 1,919 行）;**原生侧只有做后台接管时才需要完整编解码**（`RelayWire.kt` 767 行 + `RelayBridge.kt` 1,342 行）。

```
值 tag : 0 null / 1 string / 2,3 bytes / 4 array / 5 JSON / 6 int
请求   : REQ_PROMISE=100 / REQ_EVENT_LISTEN=102 / REQ_EVENT_DISPOSE=103
响应   : RES_INITIALIZE=200 / RES_PROMISE_SUCCESS=201 / RES_PROMISE_ERROR=202
         / RES_PROMISE_ERROR_OBJ=203 / RES_EVENT_FIRE=204
尺寸   : MAX_MESSAGE_BYTES=16MiB / MAX_FRAGMENT_BYTES=1MiB（=桌面端 maxFrameBytes）
         / MAX_FRAGMENTS=64（物理）/ MAX_LOGICAL_FRAGMENTS=1024（逻辑）
```

**认证与配对**（`Tier2Probe.kt:183-188` / `:351-363` / `:784-842`）:
```
connect(wsUrl + "?mid=<deviceMid>")   OkHttp,pingInterval=20s,connectTimeout=10s
 → auth_init(role="terminal")        ← auth_challenge{nonce}
 → auth_response{proof = base64url_nopad(HMAC-SHA256(passHash, "$nonce|terminal|$deviceSid"))}
 ← auth_ack{pair_status:"matched"}
```
> **配对成功即把页面那条连接顶成 KICKED 终态** —— relay 单控制端互斥。这是"交还判据必须分路径"的根因（§4.7）。

**配对后三帧 + 开桥**（`Tier2Probe.kt:420-480` / `:482-648`）:
`mobile-diagnostic(state-transition:paired)` → `mobile-view-state-update(activeWorkspaceKey/TaskId)` → `bootstrap-request`,**1.2s 后**才 `workspace-list-request` → 每工作区 `workspace-bridge-open` → 四步握手。
> **不能跳过 bootstrap**:跳过后桌面端 4.3s 就 `unregistered host` + host 进程 exit 1（`Tier2Probe.kt:400-419` 注释）。

**四步握手（顺序是语义的一部分）**（`RelayBridge.kt:196-244`）:
```
① helloConversationV4                                            (45s 超时)
② initializeConversationV4[{kind:"clientHello", protocolVersion:3,
     clientId:"zcshell-client-…", clientKind:"mobileApp", appVersion:"3.6.5"}]   (45s)
③ 对每个在跑会话:subscribeConversationV4        ← 【必须先于 ④】
④ subscribeSessionsIndexV4[{…, runtimePolicy:"existing-only"}]   (60s) ⇒ subscriptionId
⑤ listenEvent("zcode-agent","onDynamicSessionsIndexFrame", …)
```
- ⚠️ **顺序反了会永久坏**:索引订阅先发,则同桥上 `subscribeConversationV4` **永远不回包**（真机每 36s 重试、连续 12 分钟全超时）。
- ⚠️ **`appVersion:"3.6.5"` 是页面客户端版本,不是本应用版本**（与 `zcode-protocol.js:213` 逐字对齐）—— **不要"修正"**（`RelayBridge.kt:202-206`;`Docs/12` §1.1 不可删除清单）。

**三条纪律 + 一条禁区**（做原生 relay 必须原样复验）:
1. **ack 必带身份三元组**（`bridgeSessionId` + `bridgeGeneration` + `recoveryId`）—— 缺字段会被桌面端静默丢弃,replay buffer 持续累积 ⇒ **约 45s 后整座桥判 degraded**。真机连续三天"新桥好一分钟、随后集体超时、只能靠整桥回收续命"就是它。
2. **对话订阅必须先于索引订阅**（见上）。
3. **`resync*` 的 `base` 必填可空**:无基线必须**显式传 `null`**,省略字段会被 zod 拒收（`expected object, received undefined`）—— 真机上"每条 SI resync 都死于它,索引缺口永远补不上"。
4. ⛔ **绝不发 `listen`（尤其 `onDynamicControllerFrame`）—— 会把桌面端 host 进程打崩**（配对→崩溃固定 4.3s,三次复现）。壳自己订 `controller/tasks-index` 的那整套已整体删除。

**其他实测约束**:绝不传 `visibility`（硬编码 `"background"` 会导致"首屏快照后再无 delta"）;**不无条件重订**（每 12s 无脑重订会把 subscriptionId 换掉 ⇒ `fault.subscription.notOwned` ⇒ 每 24s 回收一次的死循环);`conversationRowsRangeV4` 是**死路**（真机每次 20s 超时,桌面端不回这个包,常量保留只作记录）;SI resync 有 **5s 冷却 + pending 补射**（缺口帧成串到达曾造成 300ms 内 6–10 发并发风暴）。

### 4.4 状态层:三源合成

**三源**（`TaskStore.kt`,880 行）—— **每源都有盲区,不能合并成一条**:

| # | 源 | 语义 | 进入原生的路径 | 盲区 |
| --- | --- | --- | --- | --- |
| ① | **页面自己订的 `controller/tasks-index`** | 全局任务清单,**`liveStatus` 才是"此刻在跑"** | 页面 socket → 注入层被动转发 `post('controllertasks')` → `ControllerTasksState` → `TaskStore.applyLiveTasks` | 骑在页面 socket 上:原生一接管、页面被顶掉,这条流随之死 |
| ② | **`sessions-index`（持久相位）** | 有哪些任务 / 什么状态 / 标题 | ① 页面被动跟随;② 原生承载桥自己订阅 | **按工作区订阅**（页面停在 A 就只看得到 A） |
| ③ | **会话流 `turnHeader.state`** | 最准最实时 | **只在承载里**:桥 → `reportTurnState` → `applyConversationRunState` | 只覆盖承载订过的那几条会话 |

> **第 4 条腿已删除**:壳自己订 `controller/tasks-index` 会让桌面端 host 当场自毁（§4.3 禁区 4）。`RelayWire` 里 5 个 controller 常量**保留作协议名册**。
> ⚠️ 区分清楚:**"原生自订 controller 流"已删**;而**"页面自己那条 controller 流"是活的**,且是**唯一全局运行态源**（删了会让"别的工作区在跑的任务"在手机上彻底看不见）。

**判定规则:谁后到谁算数**（`TaskStore.phaseOverlay`,`TaskStore.kt:643-657`）:
```
liveAt / convAt / siAt = 三条源各自的"报到时刻"
if (liveAt < 0 && convAt < 0)       return null     // 无覆盖层 ⇒ 用 SI 持久相位
if (siAt > liveAt && siAt > convAt) return null     // SI 只在【严格更新】时说了算
return (liveAt >= convAt) ? live : conv            // 覆盖层之间谁后到谁算数,平手取 live
```
- **为什么必须按时刻**:固定优先级（controller > 会话流 > SI）会让"页面被顶掉前那份旧 `running`"盖住桌面端新写入的 `completedSuccess`,卡片永远显示"运行中" —— 真机 2026-09-18 09:29:36 卡死现场。
- 两条配套细节:相位没变**也要盖时刻**（"这条源还在说话"）;`applyLivePreview` 给会话流那份**续期**。
- **终态语义**:会话流只说"停了",不说"怎么停的" ⇒ `running=false` 时**保留 SI 已给的终态**,否则用户点的"中断"会被写成"已完成"。
- **"什么算运行中"只有一个家**:`runningIn(ws)`,含 3s 观察窗内的 `heldRunning`。

**4c 防降级（陈旧读数 + 可信窗口）**（`TaskStore.kt:285-291` / `:340-417` / `:509-535` / `:878`）:
```
触发:注入层检出 sessions-index 序号缺口 ⇒ 发「仅标记帧」(stale/staleOnly),列表是冻结旧读数
离开那一拍（后台或熄屏）且已有陈旧记录 ⇒ armTrustWindow(120s),锚点 = 用户离开
窗口内两条保护:
  guardStaleDowngrade : 陈旧工作区的"在跑"集合【只增不减】（不抹空、不翻终态、可新增）
  staleGateHold       : 接管闸门不许【仅凭"没有在跑任务"】关闸
缺口自愈（收到普通整表帧）⇒ 陈旧事实与可信窗口一并清;窗口过期 ⇒ 丢弃保护 + 记一行可 grep 的日志
```

**`NotifyState` 纯状态机**（`NotifyState.kt:114-212`,**要原样重写的核心**）:
- 状态:`previousPhases`（上拍相位,跳变检测 + 去重）/ `notifiedInteractions`（`interactionId` 全局一次性）/ `pendingCompletions`（观察窗候选）。
- **完成 = 跳变**:上一拍 `phase ∈ {running, prewarming}` → 这一拍 `∈ 终态集合`。单看快照判不出完成,必须跨拍携带相位表。
- **观察窗**:跳变那一拍**只开窗不宣布**,持续停住 `COMPLETION_HOLD_MS = 3000ms` 才宣布;窗内又跑 = **什么都没发生**（不撤卡、不响铃）;**消失不算完成**（将来带终态回来也不补发）;**重跑会再响**。
- 收尾状态词:`completedInterrupted/cancelled → 已中断`;`failed/error → 已失败`;其余 → `已完成`（"宁可说完成,也不无端指控失败"）。
- 相位词表:`RUNNING = {running, prewarming}`;`TERMINAL = {completed, completedSuccess, completedInterrupted, cancelled, failed, error}`;其余（含未知）**既不算跑也不算终态**。

**节流与发布**（`ShellRuntime.kt:2260-2322`）:完成/待确认通知**立即发不节流**;运行中卡更新节流 **900ms**;正文静默窗 **250ms**（`PreviewSettle`）;任务结束后不再有帧 ⇒ `nextCompletionDeadlineMs()` 交给壳 `postDelayed` 到点重算。
> **硬不变式:「空 Update = 什么都没变」,不是"运行集为空"** —— 一旦把空表当当前运行集发布,已发布的运行集会被抹成 0 ⇒ **ColorOS 立刻收回流体云卡片,下一拍再提升就是"卡片消失又重建"**（真机根因取证）。

### 4.5 通知:4 渠道 + 7 类

| # | 种类 | 触发 | 渠道 / 重要性 | id | 可拉掉 |
| --- | --- | --- | --- | --- | --- |
| 1 | 前台服务常驻通知 | `startForeground(ID_SERVICE)`;运行集变化时刷新 | `keepalive` / **MIN** | `ID_SERVICE = 1` | 否 |
| 2 | **每个运行中任务的常驻卡** | `syncRunningTasks` 逐条 notify | `running_tasks` / **LOW**（静音) | `[100000, 900000)`（由 `(ws, sess)` 稳定哈希派生) | 否 |
| 3 | 群组摘要 | 每次 sync | `running_tasks` / LOW | `ID_GROUP_SUMMARY = 2` | 否;**刻意不提升** |
| 4 | **完成通知（持久记录）** | `CompletionEvent` 立即发 | `task_completed` / **DEFAULT**（有声) | 瞬态 `2_000_000 + n` | 是 |
| 5 | 待确认通知 | `AttentionEvent` 立即发 | `task_attention` / **DEFAULT**（有声) | 瞬态 | 是 |
| 6 | **「已完成」卡（原地改状态）** | 走进终态且过 3s 窗后**不撤卡、不换 id**,重建为收尾态 | 仍 `running_tasks` / LOW | **同运行卡 id** | `ongoing = card.promoted` |
| 7 | 孤儿完成卡（认领） | 进程重启后扫 `activeNotifications` 里带 marker 的卡,**不带提升重发** | `running_tasks` / LOW | 原 id | 是（降级为可滑除） |

- **id 号段三分**:`1` / `2` 固定;任务卡 `[1e5, 9e5)`;瞬态 `≥ 2e6`。
- **标题 / 正文格式**（可直接照抄）:
  ```
  formatTitle(label, title) = title.isEmpty ? label : "$label · ${singleLine(title)}"
  formatBody(preview, fallback) = singleLine(preview.trim().ifEmpty { fallback })
  singleLine(s) = s.replace(Regex("\\s+"), " ").trim()
  ```
  `singleLine` 是为了流体云卡片（标题行只有一行,正文那行要留给实时进展）;`displayTitle` **保持原样不折叠**,因为**点击定位器要拿它去匹配页面文本**。

**点击通知定位任务（三层）**:
1. 通知 intent 带 `session_id` / **未装饰** `task_title` / `workspace_key`。
   ⚠️ **已知不对称**:运行中卡的 intent 是内联构造、**缺 `workspace_key`**（`Notifier.kt:276-286`;`Docs/29` §F1 登记为未处置）。
2. Activity 分流（`MainActivity.kt:687-737`）。
3. **"预设 + 重载"三连**（`MainActivity.kt:807-836`;`ShellRuntime.kt:1196-1219`）:判 `carrierMayHaveKickedPage()` → 写页面槽位 + **钉 `history.state.zcodeMobilePage='chat'`** → **先交还承载** → 复位就绪位 → 300ms 后 `reload()`（重载即跳转）。兜底才是 DOM 定位（`MainActivity.kt:746-776`,40×600ms）。
   > ⚠️ **承重机制待复核（两端都适用)**:`inject.js:1652-1669` 的注释断言 `localStorage['zcode-v4-last-session:v1:…']` 被页面读取,但 `Docs/24` §1.3/§1.4 的 bundle 取证说该机制被 `!webRemoteControlWorkspaceSwitcher` **整体短路、在 `/remote/v4` 上从未生效**;真正承重的是 `history.state.zcodeMobilePage==='chat'` + relay 侧 `mobileViewState.taskId`。**迁移时不要把槽位当承重机制**（§9 问题 18）。

### 4.6 系统级实时卡片:流体云 = Android 16 Live Updates 平台路径

**接入方式:零 OPPO SDK**（`notify/LiveUpdate.kt`,109 行 + `AndroidManifest.xml:15-19` 的 `POST_PROMOTED_NOTIFICATIONS`）。OPPO 泛在服务卡片需企业账号 + 定邀白名单 + 授权码;ColorOS 16 直接消费标准 Android 16 Live Updates。全仓 grep `泛在服务|Seedling|oml|授权码` 只命中一条注释。

**唯一实现 = 往通知 extras 手写两个键**（`LiveUpdate.kt:56-92`）:
```
MIN_API = 36
extras["android.requestPromotedOngoing"] = true
extras["android.shortCriticalText"]      = 状态词      // 状态栏芯片文案
canPromote() = 反射调 NotificationManager.canPostPromotedNotifications()
```
> **为什么不调官方 API**:`setRequestPromotedOngoing()` / `setShortCriticalText()` 是 API 36 新增,androidx 封装只在 core 1.17.0（要求 `minCompileSdk=36` / AGP 8.9.1）。平台侧效果就是写这两个 extras。**KDoc 明确留下指示:工具链升到 compileSdk 36 后换回官方 API。**

**没有卡片数据模型** —— 卡片就是标准通知的字段:

| 卡片元素 | 来自 |
| --- | --- |
| 标题行 | `contentTitle = 状态 · 任务名` |
| 正文 | `contentText` + `BigTextStyle`（官方允许提升的四种样式之一,不是 ProgressStyle,无进度条） |
| 状态栏芯片 | `android.shortCriticalText` = 状态词 |
| 「知道了」按钮 | `addAction(0, "知道了", dismissIntent)` |

**生命周期完全寄生在通知记录上**:任务进运行集 ⇒ 新建通知 + 请求提升;每次变化 ⇒ **同 id 覆盖**（原地更新,节流 900ms）;让出提升位 ⇒ **不发 extras** ⇒ 系统收回芯片,通知仍在通知栏;结束 ⇒ **同 id 原地改状态**,若仍被提升则芯片换「已完成」。

**准入上限**（`PromotionPolicy.kt`,70 行）:`MAX_FINISHED_PROMOTED = 3`;**没有总数上限**（在跑几个提升几个）;**完成卡永远排在在跑之后**;优先序 = `等待确认` > `运行中`,同档按 `activityAt` 降序;超出的完成卡**降级成可滑除通知**（不是消失）。
> `MAX_PROMOTED = 2` 已删（`PromotionPolicy.kt:11` 是墓碑注释）。真机三任务同跑时**三张卡同时在屏**、`dumpsys` 三条 `PROMOTED_ONGOING` —— 系统自己也会多提。
> **提升集合变化会记一行日志**「提升集合变化: [#a #b]（运行 N 个:…）」—— 真机判"卡片为什么消失 / 重建"的唯一线索。

**已知限制**:① **不得设置任何 `customContentView` / RemoteViews** ⇒ **标题 marquee 滚动与流体云互斥**;② 规范把"活动发生在过去"列为不当用途 ⇒ **完成卡踩在规范边上（明知风险保留,排障第一步就是去掉它再试）**;③ 被提升的卡必须 ongoing ⇒ **用户滑不掉 ⇒ 必须自带出口（「知道了」按钮存在的唯一理由）**;④ 芯片最大宽度 96dp、文本 <7 字符整段显示;⑤ **`setDeleteIntent` 未实现**:用户拖掉 / 关掉提升后 App 无感知,下一拍仍会重新请求提升（两端共同的既有缺口,迁移时应补）。

### 4.7 后台保活与原生接管

#### 4.7.1 保活的四个支点

| 支点 | 实现 |
| --- | --- |
| 前台服务 | `KeepAliveService`:`specialUse`（非 `dataSync`,后者 Android 15 有 6h/24h 上限）、`START_STICKY`、**`onTaskRemoved` 不自杀**（`KeepAliveService.kt:36-69`） |
| 启动时机 | **前台才启动**（Android 12+ 不许后台启动服务）:`MainActivity.applyUrl` → `ensureServiceRunning`（`MainActivity.kt:480`） |
| 唤醒锁 | `PARTIAL_WAKE_LOCK`（`zcode-remote:carrier`）,**只在"已离开 且 承载在跑"时持有**（`ShellRuntime.kt:1500-1543`） |
| 渲染优先级 | `setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT, false)`（`MainActivity.kt:238-242`,**实例方法**,不是静态) |

两条行为选择:**返回键 `moveTaskToBack(true)`**（保 socket,`MainActivity.kt:629-644`）;**不调 `onPause()`**（`:623-627`）。

#### 4.7.2 "死"的四条判据 + 分档

| 判据 | 定义 / 阈值 | 动作 |
| --- | --- | --- |
| **前置:「用户已离开」** | `userIsAway() = 应用在后台 **或** 熄屏`（`ShellRuntime.kt:1806-1815`） | 所有接管的前置条件 |
| 守卫 0:刚动过 socket | socket 生命周期指纹（`socketsOpened*1e6 + socketsClosed`）在静默期内变过 ⇒ 页面在重拨 | `< 20s` 一律 skip;首次观测只采基线 |
| **档 1:判死接管** | 入站帧静默 ≥ **`STALL_SILENCE_MS = 35s`**（3 个泵周期 + 余量) | 原生拨号接管（`carrierStartedEarly = false`） |
| **档 2:提前接管** | 页面**活着** + 有在跑任务（或 4c 陈旧放行） + 最近 **30s** 无 `conversation/` 帧,带 **10s** 观察窗（`convAgo < 0` = 本轮从未见会话帧 ⇒ 免窗） | 同样接管,但 `carrierStartedEarly = true` ⇒ **交还判据收紧** |
| 页面侧链路自救 | 心跳陈旧 `90s` × 连续 2 拍 ⇒ `forceReconnect`（只读壳下退化为只记账） | 量具 |
| **KICKED 终态** | 原生一配对,桌面端即给页面发 KICKED;页面 `intentionallyClosed=true` 后**不再重连** | 后台被顶:回前台 1.5s 后 reload（`KICKED_HEAL_CAP = 2`,配对成功清零）;**前台被顶不干预**（可能在与另一台控制端抢） |

- **「后台存活」判读**（`SurvivalVerdict.kt`,纯函数 + 单测）:4 分支 + 一行判读文本。**两条防误报硬规则**:① tick 计数用**原生侧 delta**（窗口开时记 base）;② 未知首跳延迟**报未知**,不向下取整成"从没跑"。
- **5s Tier1 静默看门狗**:前台 / 后台 / 熄屏都在岗;**熄屏 / 亮屏是唯一观察点**（屏幕状态没有生命周期回调）—— 它与已拆的"看门狗决策机器"是两台不同的东西。

#### 4.7.3 接管链（时序）

```
退后台 → ① 采"离开视图快照" ② SetAppForeground(false) ③ 启动心跳泵（首拍 3s,之后 10s）
       → 每拍 maybeStartNativeCarrier():三道守卫 → 档 1 或档 2 命中
       → Tier2Probe.start(creds, duration=0, onlyWorkspace=pageWorkspaceKey, coverage=…) + 唤醒锁
       → OkHttp WS 配对（auth_init / challenge / response / ack）【配对即把页面顶成 KICKED】
       → 补三帧 → 1.2s 后开桥:workspace-list-request → 逐工作区 open → 四步握手 → 会话订阅
       → 泵 12s 一拍:先 reanchorProgress（贵）再对每个在跑任务 subscribeProgress（便宜）
       → 桥回调 → progressSink → TaskStore.applyLivePreview → PreviewSettle 静默窗 → 通知 / 流体云
```
- **承载覆盖上限 5**（`Tier2Probe.DEFAULT_MAX_COVERAGE`,单源引用）;**只补不拆**;失败冷却 2 次 / 10 分钟。
- **重挂拍长** `LIVE_PROGRESS_POLL_MS = 12s`（注意:文档与 `ShellRuntime.kt:1504` 注释里曾写"原生 12s 心跳"是**错的**,源码心跳是 **10s**,12s 是重挂拍长）。
- **对话详情是后台"跟手"的主通道**:`ConversationTail` 只维护最后 **60 行**（`RelayWire.kt:623-765`）;重挂 `resyncConversationV4(base, forceSnapshot:true)`,判据是**该会话**收到新帧（不是全局计数）;没有基线（`logEpoch` 空）或 `fromSeq != localSeq` ⇒ 不盲接,走 `recoverConversation` / `forceSnapshot`。

#### 4.7.4 交还（反向不变式:页面链路一旦活了,原生立刻交还）

**判据**（`CarrierHandback.kt`,66 行,纯函数 + 单测）:
```kotlin
pageIsServing(socketState, paired, convFrameAgoMs, convSilenceMs) =
    socketState == SOCKET_OPEN && paired && convFrameAgoMs in 0 until convSilenceMs
shouldHandBack(pageLinkAlive, startedEarly, pageIsServing) =
    pageLinkAlive && (!startedEarly || pageIsServing)
```
- **判死路径**（`startedEarly = false`）:链路一活就交还 —— 那条路只在静默 35s 后才启动,之后来的帧只能是页面自己回来了。
- **提前接管路径**:光"有帧"不够 —— 原生一配对,桌面端就给页面发 KICKED,页面随即 `close()`,而**那一帧本身**刷新了 `lastInboundAt` ⇒ 一泵之后 `age < 35s` 被读成"页面链路已恢复",会把连接**交还给一个已经进终态的页面**（真机 2026-09-17 22:49:01 现场）。所以提前路径要更强的证人:**socket OPEN + 仍 paired + 30s 内有 conversation 帧**。

**交还动作（7 步,`ShellRuntime.kt:611-688`）**:
① 记真实回前台时刻 → ② **先排 3s / 12s 两帧 viewsnap**（排在所有干预之前,因为后面的 `SetAppForeground(true)` 会触发 KICKED 自愈重载、把注入层 runtime 换掉）→ ③ 清候选与 socket 指纹基线 → ④ `SetAppForeground(true)`（页面侧:KICKED 自愈 → 链路静默 >60s 走死链兜底 → 否则不介入）→ ⑤ `Tier2Probe.stop` + 释放唤醒锁 + 停轮询清预览 → ⑥ **兜底重载**（若承载跑过:8s 后既无 OPEN socket 又零新入站帧才 reload 一次）→ ⑦ 停泵 + 用后台窗口的 delta 出「后台存活检查」判定行。
> **为什么不重载页面**:可见性恢复后 Chromium 网络栈复活,页面自己的 `recoverConnection()` 重拨即可（真机 0.9s 自恢复）;只有页面已掉进失败态才兜底重载。

**失败兜底（全部有上限）**:

| 兜底 | 判据 | 动作 | 上限 |
| --- | --- | --- | --- |
| KICKED 自愈 | 后台被顶（`kickedAwayAt`） | 回前台 1.5s 后 reload | 跨 reload 2 次 |
| 回前台死链兜底 | 静默 >60s + 5s 观察窗零新入站帧 + 体征"在对话但 0 行" | reload | 5 分钟限流 |
| 交还兜底 | 8s 后无 OPEN socket 且零新入站帧 | reload | **共用**同一 5 分钟闸门 |
| 档 3 对话恢复 | 档 0 判出 S3 | 钉 `history.state` + reload | 连续 2 次 + 5 分钟 |

> `carrierRanThisStint` 的由来（真机缺陷）:原实现只在"回前台交还"这一条路上安排兜底;承载若在**后台期间**就被停掉（诊断 / 自停）,`isRunning()` 已 false ⇒ 兜底永不安排,而注入层 KICKED 自愈要求页面**亲眼看见** KICKED 帧 ⇒ **两条路同时失效,页面被彻底留下**。

### 4.8 回前台恢复（档 0 判据 / 档 3 动作）

**为什么需要它**:页面自己有恢复通路 `T()`,但它**只认一个信号** —— 传输已降级。壳回前台做的是"交还承载 + 让页面自己重连",**页面认为传输健康** ⇒ 只重拨 socket、不重开工作区、不重订阅;而"回到哪个会话"由 **(a) `history.state.zcodeMobilePage === 'chat'`（跨 reload 存活、不跨进程） + (b) relay 侧 `mobileViewState.activeTaskId`（跨进程存活）** 拼出。**进程被杀后 (a) 归零 ⇒ 重载也落 home ⇒ 内容永远不来（S3,实测 5 分 46 秒）**。

| 档 | 判据 | 动作 |
| --- | --- | --- |
| **档 0 判据** | 退后台那一刻 viewsnap **落盘跨进程**（`Prefs.viewAtLeave`,>2h 作废）→ 回前台 **3s 首判** / **12s 复判**;注入层未就绪时 40×600ms 排队重试 | 只记日志,不动页面 |
| 形态 S1 / S2 | 离开在对话 + 回来还在 / 3s 未回但 12s 回了 | 记「已回到对话视图」→ 档 3 计数清零 |
| **档 3 动作** | 离开在对话 + 12s 仍 `chat = false` | 调 `__zcodeShellRecoverConversation`:钉 `history.state.zcodeMobilePage='chat'` + reload 一次 |
| 档 3 闸门 | `CAP = 2`（连续）+ `MIN_GAP = 5min`;解析失败按 EMPTY 放行一次 | 到顶 = **彻底停手,交回用户** |

> **只在这一档动手**:「3s 时在、12s 时不在」仍只记录 —— 页面那时已呈现对话,中途离开更可能是用户自己的动作,重载会把他硬拽回去。
> **闸门必须跨进程**:进程一死,进程内计数就算归零,所以闸门落在 `Prefs.conversationRecovery`,清零只认"页面自述在对话里"。

### 4.9 多工作区承载与轮换（"服务权"模型）

**为什么要它**:桌面端**同一时刻只服务一座桥**（真机事实）⇒ 想在后台同时跟手多个工作区不可能,**只能轮流**。

| 项 | 规则 |
| --- | --- |
| 覆盖上限 | `MULTI_WS_COVERAGE_CAP = Tier2Probe.DEFAULT_MAX_COVERAGE = 5`（**单源引用**);引擎侧 `BridgeManager(maxWorkspaces = 12)` 只是兜底默认值 |
| 覆盖目标优先级 | ① 诊断清单 `coverage_ws:<k1>,<k2>`（豁免失败冷却）② `multi_ws_on` 时加 page 工作区 ③ **有在跑任务的工作区（不受开关管 —— 那是卡片本体）** ④ 发现链 |
| 发现链 | 从**已经在收的两条响应**解析:`bootstrap-response.result.tasks` 与 `workspace-list-response.result.tasks`;字段是**扁平** `displayStatus ∈ {idle, running, completed, error}`（`waiting` / `prewarming` 容错）;**键 = `workspaceIdentity?.trim() \|\| workspacePath`（顺序不能反）** |
| 覆盖刷新 | 承载期间每 **60s** 重问 `workspace-list-request`,把新出现的活动工作区补开桥;三条纪律:**只补不拆** / 受 10 分钟失败冷却 / 受总上限约束 |
| 失败冷却 | 同一工作区连续 **2** 次开桥失败 ⇒ 冷却 **10 分钟** |
| 开桥串行 | `openLock` 串行化;`preOpenBuffer` 缓冲 ready 之前到达的 rpc-frame（桌面端会抢跑）,桥出生后回放 |
| **轮换** | 服务权归"最近一次订阅成功"那座桥 ⇒ 每 **12s** 一拍:① **只对"被服务的那座"**发 resync（且只拉它**最饿的一条**会话）② **只轮换最饿的一座**（不等超时,直接整桥回收） |
| 门槛关系 | `ROTATE_MIN_STARVE_MS = 8s` **必须小于拍长 12s**（曾用 15s 门槛 + 12s 拍 ⇒ 门槛吃掉拍长,实测等于没改） |
| **不变式** | **一轮最多一次握手**（失败即 `return@Thread`） |
| 整桥回收 | `recycleBridge`:摘除 → 关旧（尽力而为）→ 重建 → 重新握手并恢复原会话订阅;失败 15s 后**一次性**重试 |

### 4.10 外观注入（三态取色 / 滚动条 / 避让）

| 项 | 做法 |
| --- | --- |
| **状态栏三态** | 注入层只上报**名称**（`boot` / `main-header` / `main-surface`） + 主题三信号;原生侧**固定色表 + 运行时零取色**（`core/PageBarColor.kt`） |
| **宽窄判定** | 按网页自己的 `matchMedia('(max-width: 767px)')` 判,与页面 Tailwind `max-md:` 同源 —— **不按设备形态**（平板竖屏曾因此错色） |
| 主题三信号 | `data-zcode-browser-theme-surface` → `style.colorScheme` → `prefers-color-scheme`（降级链) |
| **滚动条（方案 A）** | **归零页面 14px 轨道（含 gutter 保险） + 自绘 overlay 条**;原生关掉 View 层默认条（`MainActivity.kt:254-255`);规格从页面样式表运行时读回。**这是唯一被承认的"网页侧改动"** |
| 避让 | `padForStatusBarAndIme`:顶部 + IME;**底部刻意不消费**（沉浸） |
| 无接缝 | 三态切换无接缝已截图确认（真机） |

> ⚠️ **迁移到鸿蒙时这一域可能"反向"**:`inject.js:3395-3397` 原文写明 —— **ArkWeb 不关 overlay 滚动条**,同一页面在鸿蒙壳里**天然居中**、滚动时出条后淡出。照搬方案 A 很可能**画蛇添足**（§7 的 G14）。

### 4.11 诊断与取证（"验证能力"所在,鸿蒙最缺的一块）

| 件 | 做法 |
| --- | --- |
| **落盘日志** | `ShellLog`:**512KB 轮转**（`logs/zcode-shell.log`,外置私有目录,无线程 / 权限依赖） + 安装**全局崩溃处理器** + 导出 / 清空（`core/ShellLog.kt`） |
| **环形缓冲** | `Diagnostics`:400 条 + **每一行过 `redact`**（剥掉任何 http(s) URL 的 query 与 `/remote` 后的 query ⇒ **凭证永不落盘**） |
| **adb 后台诊断入口** | `DiagReceiver`:`exported = true` + `android:permission="android.permission.DUMP"` 的**广播**入口 —— **后台可调、不碰 Activity 生命周期**（用 `am start` 会把应用拉回前台,可见性一变页面自救就跑了,后台现场就测不了） |
| **诊断指令集** | `carrier_on/off`、`multi_ws_on/off`、`coverage_ws:…`、`net_probe`、`wvdebug_on/off`、`tier2_test/stop/takeover`、`nudge_*` |
| **CDP 取证** | `tools/cdp.mjs` / `cdp-capture.mjs`（`adb forward` 到 devtools socket 后在真机页面上直接求值） |
| **用户可见端** | 设置页查看 / 复制 / 分享日志 |

> **为什么它是"里程碑 0"**:安卓壳的**每一条结论**（后台到底活没活、卡片为什么消失、Chromium 网络栈何时死）都建立在这四件套上。鸿蒙侧这四件全是 0（§6.4）。

### 4.12 上传 / 附件 / 下载

| 项 | 安卓壳 | 备注 |
| --- | --- | --- |
| 上传 | 四个 launcher + `onShowFileChooser`;`UploadMime.normalisePlatform` 把页面 `accept` 归一成 MIME;选择来源对话框（相册 / 文件） | 白名单与鸿蒙侧一致 |
| 保存到设备 | `MODE_SAVE` **未实现** | |
| **下载** | **无**（`DownloadListener` / `onDownloadStart` grep 0 命中） | **鸿蒙壳反而更全**:`onBeforeDownload` / `onDownloadFinish` + 转存公共目录（§6.3） |
| 已知怪现象 | `UploadMime.kt` 里的星号斜杠字面量**不要简化回去**（Kotlin 词法器会误报语法错误,见 §4.16） | |

### 4.13 UI 与设置页

- **主界面没有应用栏** —— 网页就是 chrome;原生只做状态栏避让与返回键。
- **edge-to-edge**:targetSdk 35 强制;`core/WindowInsets.kt` 消费 `systemBars()` + `displayCutout()`;**先 `enableEdgeToEdge` 是关键**（否则低版本双重 padding）。
- **设置页**:M3 基线（MaterialCardView、12dp 圆角、56dp 行高、outlineVariant 分隔线）;**MiuiX 调色板是唯一例外**（含"为何不能引入 miuix 库"的记录）。
- 颜色用 M3 角色 + **动态配色**（Android 12+ 取系统调色板,低版本回落基础配色）;系统栏图标按 `uiMode` 切换 —— 早期"钉死浅色"在暗色下是坏的。
- **设置项**:当前链接、换链接 / 重新加载、通知权限、清除通知、电池优化白名单、订阅所有工作区开关、诊断（查看 / 复制 / 分享日志）、版本。

### 4.14 CI / 构建 / 发布

| 项 | 内容 |
| --- | --- |
| 工作流 | 仓库根 `.github/workflows/android-shell.yml`（468 行,4 job,`working-directory: android-shell`） |
| `js` job | Node 20+ `node --test`（协议 38 + 注入 52 = **90 用例**） + 两个结构检查脚本;**不需要 JDK** ⇒ 秒级反馈 |
| `build` job | **单次 Gradle 调用** `:app:testReleaseUnitTest :app:assembleRelease`;从 Secrets 恢复 keystore;**校验产物不是 debug 签名**;上传 artifact + md5 |
| `prerelease` job | 推 `pre` 自动打 tag 并发布**滚动预发布**（资产名固定 `zcode-remote.apk` ⇒ 下载链接恒定） |
| `release` job | 仅 `v*` tag;`gh release` + `.github/scripts/android-shell-release-notes.ps1`（按 CHANGELOG 段落生成说明） |
| 无 token 读 CI 状态 | `tools/watch_ci.py`（291 行）:解析 job 页面 HTML 的 `data-job-id` + 状态图标 + **annotation 文本**（Actions 日志匿名访问返回 404,但 annotation 会写进 job 页面 HTML） |
| 失败可读性 | Gradle 失败时把关键错误行 grep 出来以 `::error::` 重新输出（`--console=plain` 防 ANSI 干扰） |
| 本地出包 | 本机无 JDK / Android SDK ⇒ 只能走 CI;真机装机用 `adb install` |

> ⚠️ **同一仓库被两个应用共享 tag 空间**:`v1.0.0` 已被鸿蒙版的 Release 占用。安卓若正式发版建议独立命名空间。

### 4.15 测试资产（"迁移没走样"的机械保证）

| 资产 | 规模 | 覆盖什么 |
| --- | --- | --- |
| Kotlin 单测 | **131 个 `@Test` / 13 类 / 2,636 行** | 纯逻辑层:`NotifyState`(795)、`RelayBridge`(424)、`RelayWire`(375)、`ControllerTasksState`(328)、`PromotionPolicy`(127)、`ConversationRecoveryPolicy`(111)、`CarrierHandback`(94)、`SurvivalVerdict`(89)、`PageBarColor`(85)、`UploadMime`(72)、`InjectionReadiness`(64)、`PreviewSettle`(41)、`Tier2Proof`(31) |
| Node JS 测试 | **90 条** | `zcode-protocol.js` 线格式（含手算黄金字节、独立实现的逆向分片器）与 `inject.js` 行为 |
| 结构检查脚本 | 2 个（一秒级,进 `js` job） | 含**"注入就绪位复位接线"断言**（把"历史 bug 不再复发"变成门禁） |

> **诚实口径**:覆盖不到的地方 = 所有真机行为（ColorOS 保活、流体云是否出卡、Chromium 何时死链）—— 那些只能靠 §4.11 的诊断层。

### 4.16 禁区与已删机制（**不要重做已删的东西**）

**已删除且源码中不存在**（grep = 0）:`AUTO_TAKEOVER_ENABLED`、`scheduleBackgroundTakeover`、`maybeTakeOverInBackground`、`checkTier1Silence`、`maybeNudgeStalledPage`、`onLiveTasks`、`liveTaskSink`、`__zcodeShellSetSubscribeAll`、`lastForcedReconnectAt`、`STALL_NUDGE_MS`、可见性劫持实现体、D7 主动开桥调度器、原生自订 controller 订阅链、`MAX_PROMOTED`、完成卡 15s 自撤。

**已删除但注释里留了墓碑**（grep 非 0,**全部落在注释内**）:`controllerStreamEnabled`、`stallArm/Fire/Cancel/State`、`zombieSuspected`、`_requestedBridgeIds`、`openBridge`、`MAX_PROMOTED`。
> **本项目特有的文档纪律**:删除类改动的判据不是"grep 命中为 0",而是**"残留命中是否都在注释里"** —— 墓碑注释比零命中更好,它告诉后来者"这里曾经有什么、为什么删"。

**仍然活着、不许当死代码删**（易误删）:`SHELL_READ_ONLY`（只读壳政策）、体征量具、`runningSessionsProvider`、`ControllerTasksState` + `applyLiveTasks`（**唯一全局运行态源**）、`appVersion = "3.6.5"`（协议镜像常量）、`workspaceId` / `REQ_PROMISE_CANCEL`（JS 侧线格式参考）。

**其他红线**:
- `UploadMime.kt` 里的星号斜杠字面量**不要简化回去**（Kotlin 词法器怪现象:写成单个字面量时 `compileReleaseKotlin` 报 `Syntax error: Expecting a top level declaration`,连续三轮 CI 逐字节相同,同款字面量在别处却合法;已用 `"*" + "/" + "*"` 拼接绕过,注释里写明复现现象）。
- **注入层的"晚注入收编"与原型层 hook 不要动**（伤到它会静默漏首帧）。
- **注入的三道时机一道都不能少**（漏一道整个壳等于不存在）。

### 4.17 真机事实（ColorOS 侧,迁移论证的直接依据）

| # | 事实 | 来源 |
| --- | --- | --- |
| 1 | **仅按 HOME 退后台,ColorOS 285ms 就以 `o-stop(40)` 杀进程**（前台服务在跑、Doze 白名单照样杀） | 真机 `logcat` 取证 |
| 2 | 放行「允许完全后台行为」后同机**后台 14 分钟连续可用**;Doze 白名单**不够**,且每次重装可能重置 | 真机 |
| 3 | **保活成立**:pre.39 实测 **29 分 26 秒**（后台心跳 176 次 = 泵发令 176 次,10s×176 ≈ 29.3 分自洽） | 真机 + 存活判定行 |
| 4 | **「后台 60 秒墙」= Chromium 网络栈死,不是 App 没网**:页面 socket 仍 `readyState=1/paired=true` 却双向零帧 **22 分钟**、页面新建 fetch 挂 76s;同刻**原生裸 TCP 67ms、HTTPS 200/276ms、原生 WS 1 秒配对成功** | 真机 A/B |
| 5 | 流体云**真的出卡**:三张卡可同时在屏、`dumpsys` 三条 `PROMOTED_ONGOING` | 真机 + dumpsys |
| 6 | **"卡片消失又重建"的根因** = 空 Update 被当成"运行集为空"发布,ColorOS 立刻收回;下一拍再提升就"重建" | 真机 |
| 7 | 完成卡**原地改状态**（同 id、`已完成 / 已中断 / 已失败` + 「知道了」动作）真机验收过 | 真机 |
| 8 | **桌面端同一时刻只服务一座桥**（决定轮换设计） | 真机 |
| 9 | 原生承载一配对,**页面立刻收到 KICKED 并进终态**（`intentionallyClosed = true` 后不再重连） | 真机 + 源码 |
| 10 | **后台网络活动的真实频率远低于直觉**（实测见 §5.6）:后台整段 0.005–0.68 帧/秒,空闲期 75 分钟零入站 | 真机日志 21 份复算 |

---

## 5. 后台能力的真相（决定"架构能不能复刻"）

> 这一节回答一个问题:**安卓壳那套"判死 → 原生拨号接管 → 回前台交还",在鸿蒙上有没有技术闭环?**
> 结论:没有。而且**卡点不是"浏览器内核",是系统本身**。下面按"墙在哪一层 → 官方口径的射程 → 例外通道 → VPN 是不是出路 → 实测天花板 → 我们自己的流量画像"逐层给证据。

### 5.1 墙在哪一层:系统级,不是浏览器内核

| | 限制来自哪一层 | 原生网络是否受影响 | 结果 |
| --- | --- | --- | --- |
| **安卓** | WebView 的 **Chromium 内核自己**（后台网络栈整条停) | **不受影响**（真机 A/B:页面 socket 假活 22 分钟的同时,原生裸 TCP 67ms、HTTPS 200/276ms） | 页面死了原生还活着 ⇒ **"原生接管"是绕开内核的手法,可行** |
| **鸿蒙** | **系统对应用的整体管控** | **同样被冻结 / 强制断开** | 页面死时原生也一起死 ⇒ **"接管"这个动作失去对象** |

三条官方原文（主语都是"**应用**",不是 Web 组件）:
1. FAQ《应用前后台切换后出现通信不可达、网络异常》（`faqs-network-95`,官网版 `architecture-guides/common-v1_26-ts_c56-0000002408556513`）:"**应用**切换到后台 2 秒后,**应用的网络资源**会被冻结,并且在 12 秒后进行释放。" 补充:"网络资源被冻结时会 Abort Socket,但是**不会释放连接对象**";回前台复用旧连接对象必失败（2301103 / 2301107）。
2. 最佳实践《网络资源合理使用》（`bpta-reasonable-network-use`）:"**无长时任务的应用**退到后台时,**主动断开 TCP 和 UDP 连接**……如果应用不主动断开 socket 连接,**系统将强制断开 TCP 连接**。应用返回前台后,需主动重新创建连接。"
3. FAQ《App 进入后台是否会回收网络权限》（`faqs-network-104`）:"当应用进入后台时,系统会回收部分权限,**包括网络权限**……需要在后台继续进行网络活动,**需要申请长时任务权限**。"

> **ArkWeb 层面没有独立的网络限制**（`Docs/06` §3:它没有公开的 Chromium 式后台节流规则,行为完全继承进程状态）。所以"浏览器内核"这个归因在鸿蒙是错的 —— 这条纠正很重要,因为它决定了"用原生绕过内核"这条路**在鸿蒙不存在**。

### 5.2 官方口径的射程（含那条最容易误读的否定）

**进程与资源的两级管控**（`background-task-overview` 官方表）:

| 后台任务状态 | 结果 |
| --- | --- |
| **无后台任务** | **几秒后应用挂起** —— 挂起后"无法使用软件资源（公共事件、**定时器**等）和硬件资源（**CPU、网络**、GPS、蓝牙等）" |
| 短时任务 | 单次配额内不被挂起（**单次最大 3 分钟**,单日默认 10 分钟,低电量 1 分钟;退后台 5s 内必须申请） |
| 长时任务 | 不被挂起直到取消;通知由系统发、内容不可改、**用户删通知即停**;类型必须与业务严格匹配 |
| 延迟任务 | 退后台即挂起,满足条件由系统拉起 Extension 执行,**单次 ≤2 分钟** |
| 代理提醒 | 只做提醒,不跑逻辑 |

**那句被反复引用的否定**（`faqs-network-95` 分析结论段,原文照录）:
> "对于 **SSH、Mosh 等终端工具类应用**,系统基于功耗与资源调度考量,判定**纯粹的终端会话或闲置 TCP** 不属于合法的高优先级后台服务,从而强制回收资源。`backgroundTaskManager` 的长时 / 短时任务机制**无法支撑普通三方应用的长时间网络空载或低频心跳保活需求**。"
> 修改建议:采用 **"服务端会话托管 + 客户端前台断线秒连"** 架构;备选里唯一被允许的是"**仅** SFTP 大文件真实传输可按规申请 `dataTransfer` 长时任务,传输完毕必须立即释放,**不能用于 SSH 心跳保活,否则将被系统拉黑**"。

**这句话的射程要点**:
- 判据是两组限定词:"**纯粹的**"终端会话 / 闲置 TCP + "长时间**网络空载或低频心跳**";
- 它**没有**否定"有真实数据流"的连接;
- 但它也**从未承认**"应用自有的消息长连接"可以用长时任务 —— **长时任务类型表里根本没有对应类型**。这是**与那句话完全独立的第二道门**;
- 官方唯一的正面表述来自直播最佳实践:开播端"申请 `dataTransfer` 长时任务 + **持续发垫片以满足流量监控**"—— 即官方承认的门槛是"**流量监控要过线**",而不是"业务像不像 SSH"。

### 5.3 例外通道与它的边界

| 通道 | 能拿到什么 | 边界 |
| --- | --- | --- |
| **短时任务** | 单次 ≤3 分钟不被挂起（网络与定时器可用) | 单日默认 10 分钟配额,用完当日不再刷新;定位是"把一次请求做完",**不能当心跳载体** |
| **`dataTransfer` 长时任务** | 网络与定时器保持 | **无实际大流量吞吐时会被一致性校验判定违规并强杀**;系统枚举里有现成的 `SYSTEM_SUSPEND_DATA_TRANSFER_LOW_SPEED = 4`（**速率低即暂停**）;进度首次超 10 分钟未更新即取消,且**进度通知必须是实况窗** |
| **`taskKeeping` 长时任务** | 最接近"通用后台计算" | 手机 / 平板上需要 **ACL 受限权限 `KEEP_BACKGROUND_RUNNING_SYSTEM`**（AGC 逐案审批);官方典型场景 = 后台自动接单 / 杀毒 |
| 其余长时任务类型 | 各自绑定真实业务 | 音频播放 / 录音 / 定位 / 蓝牙 / 多设备互联 / 通话 —— **远程监控任务进度套不上任何一个** |
| **延迟任务 / 代理提醒** | 补偿与提醒 | 单次 ≤2 分钟;不能承载连接 |
| **Push Kit（云侧）** | 唯一"不依赖进程存活"的官方通道 | 需要服务端发起（我们的数据源是用户自己的桌面端,官方不会替我们发);实况窗远程创建仅 10 类场景,**AI 任务进度不在其中** |

**通用硬约束**（`bgtask-design-formula` 官方原话）:
> 应用不得通过后台任务恶意"保活"。恶意"保活":指的是**未经过用户允许和非正常业务需要**的场景下,应用持续在后台运行。**不得在非用户主动行为触发的场景,申请长时任务后台运行。**

这句的判据是三维:**用户允许 + 正常业务需要 + 类型与业务一致**。"用户主动点击开始"能解决**申请时机**这一条（这点成立),但解决不了类型匹配与业务形态。

### 5.4 VPN 不是保活手段（clashbox 源码解剖）

鸿蒙上确有第三方 Clash 客户端（ClashBox,`github.com/xiaobaigroup/ClashBox`,仅 HarmonyOS NEXT）在侧载状态下建立系统代理并持续跑流量。本机存有它的源码副本（`E:\mimo\参考项目\clashbox`),审计结论:

| 问题 | 事实 |
| --- | --- |
| 它怎么建系统代理 | **真 VPN**:`module.json5` 声明 `ClashVpnAbility`（`type: "vpn"`）;`CommonVpnService.ets:64-76` 用 `vpnExtension.createVpnConnection().create()` 拿 TUN fd,通本地 socket 交给 Go 内核（`libflclash.so`,mihomo + gvisor-ohos 用户态 TUN,104 MB）;默认路由 `0.0.0.0/0` |
| **为什么侧载能跑** | **建 VPN 本来就不需要特权权限** —— 官方《VPN 应用开发指南》里三方 VPN 的能力只是"创建虚拟网卡 + 配置路由",权限只要 `INTERNET`（normal 级）;`MANAGE_VPN` 是系统应用权限,不是三方门槛。它的权限清单只有 8 条普通权限（`INTERNET` / `KEEP_BACKGROUND_RUNNING` / `LOCATION` 系列 / `VIBRATE` / `DETECT_GESTURE` / `SET_WINDOW_TRANSPARENT`),**没有 MANAGE_VPN、没有任何 ACL 声明** |
| 它靠什么保活 | **VPN 进程自己不做任何保活**（`proxy_core` 里 `backgroundTaskManager` 0 命中);存活靠 UI 侧:长时任务（`KEEP_BACKGROUND_RUNNING` + 定位权限 ⇒ 用 `location` 类型） + 常驻通知 + **1 秒看门狗自愈重启** + 透明窗口"模拟画中画"死后重拉。工程里自带"VPN 进程已被系统终结 N 次"的计数器 |
| 关键反向结论 | **VPN 是"被保活者",不是"保活者"**:官方服务生命周期写明,调用 `startVpnExtensionAbility` 的应用进程退出、或 VPN 服务进程销毁时,**系统主动停 VPN**。所以 VPN 不能用来保活别的进程 |
| 对我们的意义 | 我们要的是"自己进程里的一条 WebSocket"。建 VPN **帮不上**（它保不住你的进程),反而多一个被杀点、要接管全机流量、要用户授权、背一个 Go 内核。**这条路直接排除。** |

> 另:侧载本身不是通行证 —— 受限权限（ACL）**未获批却在配置里声明会导致安装失败**（官方《应用权限管控》);侧载包有效期 14 天,实名开发者签名 180 天（后者不构成障碍）。

### 5.5 时间天花板:官方实测与社区案例

| 场景 | 后端实际收到 | 说明 |
| --- | --- | --- |
| 无长时任务退后台 | 约 **16 秒**后断开 | 华为官方博客《后台长时任务 WebSocket 保活方法探索》(2025-04-07) |
| 有长时任务 | 约 **1 分 15 秒**后断开 | 同上 |
| 有长时任务 + 名义 100ms 通信("10 分钟") | **服务端 10 分钟只收到 3 条消息**（间隔 7 分 15 秒、2 分 53 秒) | 同上 —— **这不是"连接活了 10 分钟",而是"客户端大部分时间被冻结、偶发漏出几条"**;且该实验是 OpenHarmony demo、非 NEXT 真机 |
| 社区实测 | **5–8 分钟断开**,伴随 `Continuous task cancelled: timeout` | 公开渠道 |
| **30 分钟以上后台 WebSocket 的成功案例** | **公开渠道为 0** | 多轮检索 |

作者本人的结论也是改用 **Push Kit**;官方 FAQ 的推荐架构是"服务端托管 + 客户端前台断线秒连"。

### 5.6 我们自己的流量画像（21 份真机日志复算）

要判断"我们算不算低频心跳",得先知道我们真实的流量长什么样。实测（全部来自 `android-shell/` 下的真机日志）:

| 时段 | 实测 | 来源 |
| --- | --- | --- |
| 前台突发窗口 | 均值 **14.5 帧/秒**、峰值 18.9 帧/秒、帧均 1.8–2.3 K 字符（21–311 KB/s) | `now.log:92`、`now2.log:134/224/332`、`t2b.log:149` 等 |
| 但突发**是页面自己的订阅握手风暴**,10 秒内收敛 | 39 → 17 → 5 → 1 帧/10s（同窗口 `发帧 ≈ 收帧`) | `m1.log:69-92` |
| 前台空闲 | **1 帧/10s、`0K 字符`** —— 就是 `pair_status_ack` ⇒ **< 0.05 KB/s** | `v5.log:254-258`,跨 21 份日志稳定复现 |
| **后台空闲** | **入站归零**:"后台链路静默 64s → 4547s（泵仍在跳,入站帧停了)",**75 分钟零入站帧** | `v5.log:135-160`、`hour.log:137-356` |
| 后台整段（三档） | 2 帧/396s（**0.005 帧/秒**)、214 帧/1804s（**0.12 帧/秒**)、284 帧/419s（**0.68 帧/秒**,含两次重连) | `v5.log:175`、`README:415`、`README:70` |
| 单帧体积 | 空闲 <512 字符;稳态帧均中位 **683 字符**;突发典型 1.9 K 字符;bootstrap **24–25 KB / 27,832 字符** | 日志 + `Docs/14` |
| 规格侧 | 服务端**逐 op 推、页面零节流零合批**（理论上可以很密) | `Docs/18:73/126/138/139` |

**两条要记住的结论**:
1. **"秒级"不是这条链路的常态**:它只出现在前台的订阅握手瞬间（10 秒内收敛);真实后台是"每 8 秒一帧算好的、空闲时 75 分钟零帧"。全仓检索"秒级"这个说法,没有一处是在描述 relay 活动频率。
2. **但"任务在跑时后台帧率"其实从未被测过**:上面那批日志的 `对话帧` 字段上线较晚,三档后台实测大多来自空闲或订阅不完整的时段。这是唯一还留着口子的地方（§8 的 P5 实验就是为了把它测掉）。

### 5.7 结论:后台能拿到什么

| | 结论 |
| --- | --- |
| **前台** | 原生自建长连接**没有任何限制**:ArkTS 重写 relay 三层（或只做只读旁观）随时可做,唯一代价是工作量 |
| **后台** | 只能拿到**分钟级**（5–8 分钟,乐观 10 分钟),且需要三件事同时成立:用户显式点击触发、一个说得过去的类型、真实流量过线 |
| **拿不到** | **20–30 分钟以上的持续跟手**（安卓壳的实测基线是 29 分 26 秒保活、后台零帧 22 分钟才判死)—— 量级差一到两个数量级 |
| **因此** | **"后台原生接管"整层不做**;省下 relay 原生 3,036 行。替代形态 = "切后台快照 + 回前台秒连 + 对账"（§8 路线 A) |

---

## 6. 鸿蒙工程现状底数（迁移的"接收端"）

### 6.1 工程与配置

| 项 | 值 |
| --- | --- |
| 规模 | **1,448 行 ArkTS**（`Index.ets` 1,068 / `EntryAbility.ets` 268 / `common/` 97 / backup 15) |
| 模块 | 单 `entry` 模块;`com.zcode.control`;API **6.1.1(24)**;`deviceTypes: phone + tablet` |
| 权限 | **只有 2 条**:`INTERNET`、`GET_NETWORK_INFO`（`module.json5:15-34`） |
| 深链 | `https://zcode.z.ai/remote*`（semantic 跳转,`domainVerify: true`,`module.json5:62-70`） |
| 快捷方式 | `action=scan` 路由（`shortcuts_config.json` + `EntryAbility.ets:247-256`） |
| 签名 | ⚠️ `build-profile.json5:10/:14` **明文写着 `storePassword` / `keyPassword` 且已被 git 跟踪**（安全项,§11） |

### 6.2 现有的注入方式（与安卓的差异是本节重点）

| 项 | 鸿蒙现状 |
| --- | --- |
| 注入机制 | `runJavaScript` **事后注入**（`Index.ets:337` FCP 装、`:453-458` onPageEnd 兜底重试、`:813` 语言注入在 onPageBegin) |
| **未用** | `javaScriptOnDocumentStart` / `runJavaScriptOnDocumentStart` —— **grep 0 命中** |
| 脚本体 | `STATUS_BAR_OBSERVER_JS`（`Index.ets:71-144`,约 70 行):**MutationObserver + rAF 合帧去重** + **DOM class 存在性检查**（`.zcode-boot-loading`、`.bg-background-win-alt`） + 同源媒体查询判宽窄 |
| 回推 | `javaScriptProxy('zcodeStatusBar', 'onPageState')`（`Index.ets:419-424`),传三态名 `boot` / `main-header` / `main-surface` |
| 幂等位 | `Index.ets:73-75` |
| 缺 | **无注入就绪位、无注入种子、无补注队列** |
| 历史教训 | 这里最初做的是"运行时取色",反复迭代后**收敛成纯 DOM 标记存在性检查** ⇒ 该网页 DOM 不适合承载精细语义 |

### 6.3 已有能力（迁移时**不要重复造**）

1. **扫码连接 + URL 持久化**:`scanBarcode.startScanForResult`（`Index.ets:349-389`,含取消码 `1000500002` 与无效码 Toast);**URL 校验 `https` ∧ `*.z.ai` ∧ `pathname.startsWith('/remote')`**（`common/RemoteUrlManager.ets:21-41`,与安卓逐字一致);preferences 单键持久化（`:14-15, 44-63`）。
2. **状态栏三态固定色 + 沉浸式占位 Row**（`Index.ets:46-51` 色表、`:395-398` 占位行;`EntryAbility.ets:112-118` 沉浸、`:174-201` 状态栏透明 + 图标色、`:221-234` `getWindowAvoidArea(TYPE_SYSTEM)`);**零运行时取色**（与安卓同款架构）。
3. **上传模态框 + 下载代理**:相册 `photoAccessHelper.PhotoViewPicker`（max 5） + 文件 `DocumentViewPicker.select` + **5 组后缀白名单**（`AllowedFileExtensions.ets`） + 原生模态框（`Index.ets:494-504`、`:565-628`);**下载代理转存公共目录**（`Index.ets:921-1013`,`onDownloadUpdated` 空实现 = 无进度 UI）。
   > **这是唯一"鸿蒙比安卓更全"的域**（安卓没有下载代理）。
4. **返回键与错误恢复**:返回键按设备分化 + 15s 超时 + 主框架错误过滤 + 网络可达判定 + `onRenderExited(reason === 2)` → `refresh()`（`:484-493`,**无重试上限**）。
5. **其他**:前台屏幕常亮、深浅色 / 语言跟随、`showLangRefreshBtn`（定义 / 置位但**从未渲染**）。

### 6.4 完全没有的能力（grep 核实 = 0 命中）

| 域 | 缺什么 |
| --- | --- |
| 通知 | `notificationManager` / `publish` / `addSlot` / 任何通知权限 —— **全无** |
| 系统级卡片 | `liveViewManager` / 实况窗 —— 全无 |
| 后台 | `backgroundTaskManager`、`requestSuspendDelay`、`startBackgroundRunning`、`backgroundModes`、`workScheduler`、`reminderAgentManager`、`KEEP_BACKGROUND_RUNNING` —— **全无**;前后台钩子**只有日志**（`EntryAbility.ets:149-155`);`onActive` / `onInactive` grep 0 |
| 跳转 | `WantAgent` —— 全无 |
| 协议 | 原生长连接 / relay / `webSocket` / `createWebSocket` —— 全无（只 import `connection` 做网络可达性） |
| 注入底座 | `javaScriptOnDocumentStart`、`registerJavaScriptProxy`、`WebMessagePort`、`onInterceptRequest` —— 全无 |
| 状态层 | `TaskStore` 类索引 / 会话索引 / 多工作区 —— 全无（唯一持久化是单键 URL） |
| 观测与取证 | `setWebDebuggingAccess`（**DevTools 通路未打通**)、`onConsole`、落盘日志（只有 hilog 46 处）、诊断入口、`onPageVisible`、`onRenderProcessNotResponding`、`appRecovery` —— 全无 |
| 工程 | **无 CI**（`.github/workflows/` 下只有安卓的)、单测仅模板占位、无设置页 |

### 6.5 需要先补的三项

1. **DevTools 取证通路**（`setWebDebuggingAccess` + `hdc fport`）—— 没有它,后面每条结论都只能靠猜。
2. **document-start 注入底座** + 多对象桥 / `WebMessagePort` 升级。
3. **页面与进程可观测 + 前后台骨架**（`onPageVisible` / `onRenderProcessNotResponding` / `onActive` / `onInactive`）。

---

## 7. 功能对比矩阵与平台鸿沟

### 7.1 对比矩阵

> 列义:**安卓怎么做**（细节见 §4）| **鸿蒙现状** | **判定** | **工作量粗估**（单人、熟悉两端、不含真机排障)。

| # | 功能域 | 安卓怎么做 | 鸿蒙现状 | 判定 | 工作量 |
| --- | --- | --- | --- | --- | --- |
| ① | **注入底座与时序三保险** | document-start 主道 + 两拍补注 + 原型层收编;单脚本拼接;就绪位 | 只有 `runJavaScript` 事后注入;无 document-start、无就绪位 | **需换实现（且更干净)** | 2–4 人日。**不要照搬"三保险",但要保留两拍补注 + 就绪位** |
| ② | **WebSocket hook 与协议解码** | 页面侧 hook + 被动转发;原生侧 3,036 行 | **无** | 页面侧**可原样复刻**;原生侧需换实现,**且是否做取决于 ⑧** | 页面侧 1–2 人日;原生侧 **15–30 人日（★仅当 ⑧ 立项)** |
| ③ | **会话 / 任务索引与运行态** | 三源 + `phaseOverlay` 谁后到谁算数 + 4c 防降级 | **无** | **可原样复刻（纯逻辑)** | ≈1,656 行逐行译 ArkTS + 对应单测 = 5–8 人日 |
| ④ | **通知（常驻 / 完成 / 待确认)** | 4 渠道 7 类 + 完成 = 跳变 + 3s 窗 + 原地改状态 | **无** | 逻辑可复刻,**通道需换实现**;撞两条平台红线（G9 / G10) | 5–8 人日（不含分类权益申请) |
| ⑤ | **系统级实时卡片** | **寄生在通知上的提升**（手写两个 extras,零申请) | **无** | **平台无等价物 → 降级为普通通知** | 工程 5–10 人日 + **权益审批不可控** |
| ⑥ | **点击通知定位任务** | intent 带三参 → 预设 + 先交还 + reload → DOM 兜底 | **无** | **可原样复刻**（"先交还"这步自然消失) | 2–3 人日。⚠️ 承重机制待复核（§4.5 末) |
| ⑦ | **后台保活 / 前台服务** | `specialUse` + `START_STICKY` + wake lock + 渲染优先级 | **无** | **平台无等价物 → 降级**（§5) | 2–4 人日（只做收尾级) |
| ⑧ | **后台原生接管与判死分档** | 判死 35s / 提前接管 30s+10s 窗 → 原生拨号 → 开桥 → 轮换 → 交还 | **无** | **不可行（整层)**（§5.7) | **不做**;省下 ② 的原生一半 |
| ⑨ | **回前台交还与对话恢复** | 档 0（3s / 12s 三形态) + 档 3（钉 state + reload,CAP2 / 5min) | **无** | **可原样复刻（纯逻辑)**;"交还"动作本身消失 | 3–5 人日（判据量级要重定) |
| ⑩ | **多工作区承载与轮换** | 上限 5 + 服务权轮换 + 60s 补桥 + 8s < 12s 门槛 | **无**（单连接覆盖式是既有产品决策) | 前台可复刻（观察面);后台承载式不可行 | 前台观察 1 人日 |
| ⑪ | **外观注入（三态 / 滚动条 / 避让)** | 三态名称上报 + 固定色表 + 归零轨道 / 自绘条 + 顶部与 IME 避让 | **已有对等（除滚动条)** | 三态**已完成**;滚动条**需换实现且可能反收益**（G14) | 键盘避让用 `keyboardAvoidMode` 1–3 人日 |
| ⑫ | **上传 / 附件 / 下载** | 四 launcher + MIME 归一 + 来源对话框;`MODE_SAVE` 未实现;**无下载代理** | **已有且更全** | **基本已完成** | 补 MIME 归一 / 下载进度 = 1–3 人日;**反向**:安卓可从鸿蒙抄下载代理 |
| ⑬ | **诊断日志与真机取证** | 512KB 轮转落盘 + 400 条环形缓冲（redact) + adb 广播入口 + CDP 脚本 | **全无** | **需换实现**（可搬运的只有"设计") | 3–5 人日（**含里程碑 0**) |
| ⑭ | **CI / 构建 / 发布** | 4 job 并行;131 Kotlin 单测 + 90 JS 用例;pre 滚动预发布 | **无 CI**;单测是模板 | **需换实现**（托管 runner 无鸿蒙 SDK) | CI 2–5 人日;单测翻译 3–5 人日 |
| ⑮ | **扫码 / URL / 凭据** | 校验 + 三入口 + 持久化 | **已有对等** | **基本已完成** | 缺口 3 项:备份全开（凭证会被系统备份)、无设置页、`domainVerify` 待验证 = 1–2 人日 |
| ⑯ | **看门狗与页面观测** | Tier1 5s 看门狗（熄屏也算离开) + DOM 体征 + 档 0 快照 | 只有 `onRenderExited` → refresh（**无重试上限**) | **需换实现 + 判据量级重定** | 3–5 人日。**优先用系统回调**（鸿蒙比安卓多 `onRenderProcessNotResponding` 等);判死阈值 **35s → 秒级 / 事件驱动** |

### 7.2 其余平台鸿沟（G1–G8 见 §5,此处为 G9 起）

> `【核实】` = 直读该 Docs 行（Docs 被 gitignore,只能用 ls + Read)。

| # | 鸿沟 | 鸿蒙侧证据 | 降级 / 替代 |
| --- | --- | --- | --- |
| **G9** | **通知分类频控:未接入自分类的应用,本地通知全按"资讯营销消息"处理,限频 2 或 5 条/天** | 【核实】`Docs/07:284-285`（官方《本地通知频次及分类管控通知》) | **"任务结束立刻响"一天可能只能发 2 条** ⇒ 必须**申请自分类权益**,或把完成提醒降级为"常驻卡原地改状态 + 角标"（不发新通知) |
| **G10** | **通知留存 24 条 / 单条 ≤200KB / 10 条每秒、更新 20 条每秒;横幅默认关闭** | 【核实】`Docs/07:281-282`、`Docs/09` §2.5 | "每任务一条常驻卡"低并发成立;高并发按 24 条裁剪。**"完成要响"必须提前引导用户开横幅**（不像安卓"渠道重要性一次定死") |
| **G11** | **`document-start` 注入语义不同:按脚本内容字典序执行、不按数组顺序;同内容脚本静默去重** | 【核实】`Docs/09` §2.3、`Docs/08:126-149` | 用**单脚本**（拼接,与安卓同款);有先后依赖时用 `runJavaScriptOnDocumentStart`。**"晚注入收编"是否成立 = 【待验证】**（取决于补注脚本是否跑在同一 JS 世界) |
| **G12** | **`javaScriptProxy` 只能注册一个对象、只能声明方法不能声明属性** | 【核实】`Docs/08` §2.3.1 | 安卓侧 `window.ZCodeShell` 只有 2 个方法（`postMessage` / `config`)⇒ **单对象够用**;要更多时用 `registerJavaScriptProxy` + `refresh()`（**必须在 `onControllerAttached` 注册并让页面重新加载**) + `deleteJavaScriptRegister` + `permission` 白名单 |
| **G13** | **`runJavaScript` 必须 UI 线程;跨导航 JS 状态不保留** | 【核实】`Docs/08` §2.3.2 | 方向与安卓一致。**好处:`runJavaScript` 有返回值 ⇒ 档 0 快照少一跳**（安卓那条"必须靠桥回传"的弯路不用走) |
| **G14** | **overlay 滚动条:ArkWeb 本就有**（与安卓 WebView 相反) | 【核实】`inject.js:3395-3397` 原文:"ArkWeb does not do that, which is why the same page is centred in the HarmonyOS shell with a bar that appears while scrolling and fades." | **照搬方案 A 很可能反收益**。正解可能是**只保留 gutter 保险 / 或整段不注入**;页面自己的 `::-webkit-scrollbar{width:14px}` 在 ArkWeb 生不生效 **【待验证】** |
| **G15** | **移动设备 Web 实例 >10 时系统主动回收后台页面数据** | 【核实】`Docs/08:32,79,352`;回收粒度**未说明**（`Docs/08:868` 列为未验证) | 不做"每工作区一个 Web 实例" |
| **G16** | **DevTools 通路默认关闭,且官方"不建议在正式发布版本中启用"** | 【核实】`Docs/08` §2.8.1 | 用 **debug 变体 / build flag** 开启 |
| **G17** | **无 adb 广播等价入口**（安卓 `DiagReceiver` 是"后台可调诊断"的唯一入口,且不碰 Activity 生命周期) | 【核实】`DiagReceiver.kt:8-42` 类注释;鸿蒙侧无对应物 | 三选一:应用内诊断面板 / `hdc` + want 拉起（**注意 want 拉起会不会把 Ability 拉到前台 —— 同一个坑**) / 约定 hilog tag 侧过滤 |
| **G18** | `setRendererPriorityPolicy` 是否对等 **【待验证】** | 安卓侧注释明写它是实例方法、用于阻止渲染器后台被降优先级 | 搜 `onRenderProcessNotResponding` 与 `RenderProcessMode`;若确有等价杠杆,写进 ⑯ |
| **G19** | `setDeleteIntent`（检测用户关掉了提升 / 卡片)**安卓侧本身也没实现** | grep = 0 命中 | **两端共同的既有缺口**,迁移时应补（"用户删掉后不要立刻重建") |
| **G20** | **后台资源配额硬闸:后台 CPU 10 分钟均 >10% → 结束进程;内存 2GB 连续 1 分钟超限 → 终止** | 【核实】`Docs/06:31`、`Docs/09` §4.9 | 与"进程挂起"同向;**ArkWeb 不会自己省电** ⇒ 持续解码帧的页面容易撞上 CPU 闸 |

---

## 8. 迁移路线

### 8.1 目标架构:路线 A「前台秒连」（推荐）

这是官方 FAQ 与 `Docs/06`/`Docs/09` 给出的**被官方允许的形态**,也是本路线图的落点。

```
[前台]   页面自持 relay 连接（现状不变）
           └ 壳只做只读旁观:注入层 hook WS + 被动转发 sessions / controller / status
             → 本地 TaskStore 三源合成 → 通知（常驻 / 完成 / 待确认）

[切后台] onBackground（5s 内）:
           ① 采一份 viewsnap 快照（runJavaScript 直接取 —— 有返回值,少一跳)
           ② 落盘（preferences）:离开时刻快照 + 当前在跑任务清单 + 最后会话
           ③ 若需要:短时任务（≤3 分钟）只做收尾（写盘 + 发一条汇总通知)
           ④ 不申请长时任务、不保活 ⇒ 让系统按常规挂起

[后台中] 不承诺实时。用户可见的只有"切后台那一刻的快照"。
           若产品硬要"实时":需要 Push 云侧（要权益 + 场景白名单,AI 任务进度不在其 10 类场景内)

[回前台] onForeground（秒级）:
           ① 判"进程是否被杀过"（拿落盘快照的 age,对应安卓的 2h 作废阈值)
           ② 若进程还在:让页面自己 recoverConnection 重拨
              ⚠️ 若壳自己拨过连接,必须先 close() 再 connect()（旧连接对象底层已释放,复用必失败)
           ③ 档 0 判据（3s / 12s 三形态）→ 落在 S3 时执行档 3（钉 history.state + reload,CAP2 / 5min)
           ④ 与快照对账:哪些任务在后台期间结束了 —— 只能"回前台后从页面重新拉"
```

**必须写进产品说明的能力边界**:后台期间**没有**实时进展、**没有**"任务完成立刻响"（完成通知只能在回前台后补发,或与 G9 的分类权益一起谈)。

> **路线 B（条件性)Push 云侧**:唯一"不依赖进程存活"的官方通道,但远程创建仅 10 类场景（FLIGHT / TAXI / TRAIN / DELIVERY / QUEUE / RENT / EXPRESS / CHECK_IN / TRADE / SUBSCRIBE_TIMER),**AI 远程任务进度不在其中**,且 Push 无法用调测白名单绕过 ⇒ **不作为设计前提**。
> **路线 C（不推荐,记录在案)长时任务 + 实况窗进度**:只有"真有大流量传输"时才合法,且进度更新必须是实况窗 ⇒ 与实况窗权益门槛冲突。（这也是 P5 要实测的那条路。)

### 8.2 各功能域的降级对照

| 域 | 原方案（安卓) | 鸿蒙落地 |
| --- | --- | --- |
| ④ 通知 | 4 渠道 7 类 + 完成 / 待确认双通知 | 同样结构,但**受 G9 / G10 约束**:完成提醒先谈自分类权益;横幅需引导用户开 |
| ⑤ 流体云 | 通知提升（零申请) | **普通通知**（先不做实况窗);实况窗作为"权益拿到之后再补"的可选层 |
| ⑥ 点击定位 | intent → 先交还 → 预设 → reload | `WantAgent` → 预设（钉 `history.state`)→ reload（**"先交还"自然消失**) |
| ⑦ 保活 | 前台服务 + wake lock + START_STICKY | 短时任务只做收尾;**不做保活** |
| ⑧ 接管 | 判死 → 原生拨 WS → 交还 | **不做**;改为"切后台快照 + 回前台秒连 + 对账" |
| ⑨ 交还 | handBack + 兜底重载 | 只保留**档 0 判据 + 档 3 动作** |
| ⑩ 多工作区 | 多桥覆盖 + 12s 轮换 | **前台观察即可**;后台不做 |
| ⑪ 滚动条 | 归零 + 自绘 overlay | **什么都不做**（ArkWeb 本来就有 overlay),或只保留 gutter 保险 |
| ⑬ 诊断 | DiagReceiver 广播 + 落盘日志 | 应用内诊断面板 + 落盘日志 + `hdc hilog`;**DevTools 必须先打通** |

### 8.3 分阶段路线

> 原则:**先把"能不能验证"做出来,再把"能不能后台存活"验掉,最后才写功能。**

| 阶段 | 内容 | 里程碑（可独立验收) | 怎么验证 |
| --- | --- | --- | --- |
| **P0**（0.5–1 周) | **取证通路**:`setWebDebuggingAccess` + `hdc fport` + `onConsole` + 落盘日志骨架 | **M0**:能用 DevTools 读到页面的 `history.state` / `localStorage` / 页面 store;hilog 里能看到页面 console | `hdc shell cat /proc/net/unix \| grep devtools` → `hdc fport tcp:9222 localabstract:webview_devtools_remote_<pid>` → `chrome://inspect`。**判据:能读到一条只有页面知道的运行时值**。⚠️ 官方"不建议 release 启用" ⇒ 用 build flag |
| **P1**（1 周) | **注入底座**:`javaScriptOnDocumentStart` + 单脚本（`zcode-protocol.js + inject.js`) + 就绪位 + 两拍补注 | **M1**:hook 在页面首个 WebSocket 之前生效,且**每次加载都有 ready** | 冷启动 / 路由切换 / `location.reload()` **各 20 次**,统计 ready 计数与"hook 时是否已有活动 socket"。判据:20/20 全拿到 ready,0 次"注入晚于首帧连接" |
| **P2**（1–2 周) | **页面侧旁观层全量移植**:`inject.js` §0 / §2 / §2b / §3 / §5b / §5c / §6 + 本地 `TaskStore` / `NotifyState` / 三源合成（ArkTS) | **M2**:调试面板能看到"工作区数 / 在跑任务数 / 最后会话 / 每源最后报到时刻",且与页面肉眼所见一致 | 造三个场景对照:① 有任务在跑 ② 任务刚结束 ③ 切到另一个工作区。判据:三源时刻表与页面一致,且**场景 ② 上不显示"运行中"**（这正是安卓真机 09:29:36 卡死过的 bug) |
| **P3**（1 周) | **通知通道**:SlotType + 授权引导 + `NotifyState` 全量（3s 观察窗、原地改状态、「知道了」) + `WantAgent` 定位 | **M3**:完成一个任务 → 通知从"运行中"**原地**变"已完成"（不撤卡、不换 id),点通知能回到那个任务 | 与安卓同款判据（dump 通知看 title 是否为 `已完成 · 任务名`)。**⚠️ 同时验 G9**:连发 5 条完成通知,看第 3 条起是否被静默限频 |
| **P4**（1 周) | **补齐非后台域**:外观（滚动条真机评估 + 键盘避让)、上传 / 下载补缺、设置页、诊断面板、备份排除凭证、单测翻译 | **M4**:平台无关的那部分 Kotlin 断言在 hypium 里全绿;`inject.js` 的 52 个 JS 用例原样绿 | `hvigorw assembleHap` + `hdc install` + hypium 本地跑;JS 侧 `node --test`（纯 Node,与安卓同款)。判据:红 = 0 |
| **P5 ⚠️**（1–2 周,**风险闸门,只做实验不写产品代码**) | **后台可行性实测** —— 见下方"P5 实验设计" | **M5**:四个读数 + 一份"能不能后台"的是 / 否结论 | 见下 |
| **P6**（分支) | **若 M5 = 能**:投入 ② 的原生一半 + ⑧ 的替代形态 + ⑨ 的"拉齐"。**若 M5 = 不能（预期)**:走路线 A,把 P5 的产物写成"为什么不做后台"的结论文档,**直接跳 P7** | **M6**:（不能分支)"回前台秒连 + 档 0 判据 + 档 3 恢复"三者真机跑通 | 不能分支:退后台 10 分钟 → 回前台,判据是"3s / 12s 两拍能看到真实的对话视图"（S1 / S2) + 若 S3 则 reload 后回到对话。能分支:桥重连必须先 close 再 connect,且能补上后台期间的输出 |
| **P7**（1–2 周) | **长尾**:⑭ CI / 门禁、⑮ 安全项（签名口令出库、备份排除)、⑯ 系统回调接线（`onRenderProcessNotResponding` / `onActive` / `onInactive` + `onRenderExited` 重试上限) | **M7**:CI（或本地一键脚本)能在 push 前跑完 lint + 单测 + 打包;`onRenderProcessNotResponding` 在真机"页面死循环"时确实触发 | ① 一键脚本退出码 0;② 页面注入 `while(true)`,看回调是否触发、时延多少（前台 / 后台带长时任务 / 后台无任务 三种状态各跑一次) |

**关键顺序理由**:
1. **P0 必须在 P1 之前** —— 没有 CDP / console 就只能靠 hilog 盲猜,安卓侧那种"现场分析"在鸿蒙做不出来。
2. **P5 的结论必须早于 P6** —— ⑧ 不可行会让 ② 的 3,036 行原生 relay 完全没有存在理由（最大一笔可省成本)。
3. **P2 与 P3 可以并行**（P2 的产物是纯逻辑,P3 的通道可以先用手工注入的事件打通)。

#### P5 实验设计（唯一还开着的口子）

§5.6 指出:**"任务在跑时的后台帧率"从未被测过**。这个实验就是把它测掉,顺带把长时任务的真实边界量出来。

**做法**:
1. 造一个**持续输出 30 分钟以上**的任务（桌面端 agent 连续产出文本)。
2. 手机端进控制页 → **用户显式点击**"开始后台跟手" → `onBackground` 里申请 `dataTransfer` 长时任务（带系统通知)。
3. **让原生自己订阅该会话**（不依赖页面,页面此时已被顶掉),或先用页面订阅 + 只注入心跳做对照。
4. 全程记录:入站帧率曲线、`continuousTaskSuspend` / `ContinuousTaskCancel` 的 reason、实际存活时长、是否有 `SYSTEM_SUSPEND_DATA_TRANSFER_LOW_SPEED`。

**类型选择（实测时才知道哪个能用)**:

| 类型 | 与我们的匹配度 | 已知边界 |
| --- | --- | --- |
| `MODE_DATA_TRANSFER` | 最贴"任务进度的实时数据" | 要求**真实大流量吞吐**,低速会被 `SYSTEM_SUSPEND_DATA_TRANSFER_LOW_SPEED` 暂停;进度首次超 10 分钟未更新即取消,且**进度通知必须是实况窗** |
| `MODE_TASK_KEEPING` | 最接近"通用后台计算" | **PC / 2in1 免 ACL**;手机 / 平板需 ACL 受限权限 `KEEP_BACKGROUND_RUNNING_SYSTEM`（AGC 逐案审批) |
| `MODE_MULTI_DEVICE_CONNECTION` | 官方场景写"分布式业务连接、投播" | 是**唯一未被官方点名排除**的类型,但一致性校验如何判未写明 —— **候选,需实测** |
| 音频 / 录音 / 定位 / 蓝牙 / 通话 | 与业务不匹配 | 不要试（会被一致性校验判违规) |

**判据（可判定,不含主观)**:

| 读数 | 含义 |
| --- | --- |
| 30 分钟内帧率稳定在"秒级"且**从未被暂停 / 取消** | "有真实数据流"论点成立 ⇒ 后台跟手有戏,⑧ 值得重估（此时才考虑投 3,036 行) |
| 出现 `LOW_SPEED` 暂停或 `cancelled: timeout` | 论点作废,走路线 A |
| 存活时长 < 10 分钟 | 与官方基线一致,论点作废 |

**官方基线（拿来对照)**:无长时任务约 16 秒断;有长时任务约 1 分 15 秒断;"有长时任务 + 名义通信"的那一档服务端 10 分钟只收到 3 条消息。

---

## 9. 开工前必须回答的问题

### A. 用户 / 产品要拍板

1. **后台"实时"是产品硬需求吗?** 若是,唯一官方通道是 Push 云侧,而它需要权益 + 场景白名单（AI 任务进度不在那 10 类里)⇒ **必须接受"后台期间没有实时进展"**（本路线图推荐接受,走路线 A)。
2. **"任务完成立刻响"能不能降级?** 未接自分类时鸿蒙本地通知**限频 2–5 条/天**（G9)⇒ 要么申请自分类权益,要么改为"常驻卡原地改状态 + 角标"。
3. **实况窗要不要投入?** 投入的话:调试门 5 工作日 + 正式门（已上架 & 月活 ≥1000 & 逐场景评审 ≈15 工作日),且场景准入明确排除"后台自动"与"AI 对话时长"。**建议先不投。**
4. **扫码入口 / 单连接覆盖式**这两条既有产品决策是否维持?（`项目文档.md:42-43` 明写"不做应用内重连入口""不做多会话列表")—— 若维持,⑩ 只做前台观察。
5. **是否接受"通知卡按 24 条上限裁剪"**（G10)?高并发需要产品规则。
6. **是否愿意走 `KEEP_BACKGROUND_RUNNING_SYSTEM`（ACL)申请,去换 `taskKeeping` 长时任务?** 这是 ⑧ 唯一的翻盘路径（官方典型场景是"后台执行自动接单等计算类业务",与远控壳不对口,审批不确定);若不走,⑧ 的结论直接固化。

### B. 账号 / AGC 侧动作（非工程,但会卡工期)

7. 应用**是否已上架?月活是否 ≥1000?**（决定实况窗正式权益是否可能)
8. 是否愿意先申请**实况窗调试门（5 工作日) + 调测设备白名单（100 台,按 Push Token)**?注意 **Push 通道无法用白名单调试**。
9. 是否申请 **Push 自分类权益**（对应 G9 频控红线)?

### C. 需要真机实验才能回答（P5 的输入)

10. **鸿蒙真机是否可达?** 当前 `hdc list targets` 只有 COM3–COM9（UART,addr unknown),**无真机 / 无 tcp 目标** ⇒ **P0 都开不了工**。
11. ArkWeb 页面里的 WebSocket 在真机上的**真实断点**是 2s 还是 12s?
12. **挂起后旧 `WebSocket` 对象能否复用**,是否复现 2301103 / 2301107?
13. P5 的四问（§8.3 的实验设计)。
14. `javaScriptOnDocumentStart` 能否在页面首个 WebSocket 之前完成 hook?（对应 G11)
15. 回前台是"恢复（BFCache)"还是"重载"?（决定档 3 的判据与状态重建面)
16. 页面那条 `::-webkit-scrollbar{width:14px}` 在 ArkWeb 里是否生效 / 占不占 14px?（G14 的决定性读数)
17. 调测白名单设备能否真的 `startLiveView` / `updateLiveView`?
18. **页面"回到上次任务"到底靠什么承重**:`localStorage` 槽位（`inject.js` 注释这么说)还是 `history.state.zcodeMobilePage` + relay 侧 `mobileViewState.taskId`（`Docs/24` 的 bundle 取证说前者在 `/remote/v4` 上从未生效)?**这条决定 ⑥ 能不能成立,而且安卓侧当前实现也押在它上面。**

---

## 10. 资产索引

### 10.1 Docs/ 六组（47 篇;目录被 gitignore)

| 组 | 篇号 | 主题 |
| --- | --- | --- |
| 一 | 01–05 | **鸿蒙实况窗（Live View Kit)**:数量约束 / 开通与权益 / 本地 API / Push 与端云协同 / 设计规范与版本矩阵 |
| 二 | 06–09 | **鸿蒙后台行为与复刻可行性**:ArkWeb 节流冻结与网络管控 / 后台保活官方方式全集 / ArkWeb 能力 API 清单 / **复刻安卓壳的鸿蒙落地对照** |
| 三 | 10–21 | **remote-v4 网页端逆向**:扩展面与可观测面 / 平台事实 / bootstrap 载荷（解析了哪些、完整字段表、合并总表) / 网页架构与模块地图 / relay 协议目录 / 功能域（对话渲染流式、输入附件预览、导航列表与外观) / 回前台对话恢复 |
| 四 | 22–26 | **壳端机制与页面取证**:注入点审计 / 卡死现场分析 / 页面重载恢复逐条取证 / 后台原生承载独立设计（125 KB) / 全仓审计与错误路线残留 |
| 五 | 27–28 | **网页更新影响与快照**:完整快照（2,518 文件 / 36.4 MB) + 锚点巡检脚本 / 更新影响与重适配成本模型 |
| 六 | 29–47 | **安卓壳功能域概览 + 13 份分区审计**:通知与实时卡片 / 诊断日志与通信线层 / 多工作区轮换 / 运行态三源 / 会话任务索引 / 看门狗（29–34);13 个源码分区逐成员审计（35–47,末尾附现状核实表) |

**最有价值的三篇**:`Docs/28`（更新影响 / 壳健壮性 12 缺口 + 21 个静默失效点 / 重适配成本模型)、`Docs/27`（当前构建完整快照 + 锚点巡检,159 条锚点消失 0 / 恒假 0)、`Docs/16` + `Docs/14`（relay 协议目录 + bootstrap 权威字段表)。

**时效性（决定"能不能直接照抄")**:

| 判定 | 涉及篇 | 说明 |
| --- | --- | --- |
| ✅ **当前构建坐标（G3）** | **27、28**，以及 **10、13–24（已全量重写）** | 线上构建 = 快照（`index.html` md5 逐字节一致）。**这 13 篇已于 2026-09-20 全量重写为"基于当前构建的一体报告"**：不再保留旧构建偏移、不再有"修订说明/旧→新对照"叠写；每篇文首「审计基线」给出主包 `index-B-ilXaCQ.js`（4,821,509 B）/ schema `src-wmk2orCZ.js`（285,095 B）/ CSS `index-DR0mqKFV.css`（392,369 B），正文偏移均可在 `Docs/27-…/latest/` 上复现 |
| 🗄️ **旧版整批留档** | 13 篇的旧构建版本 | `Docs/_archive-20260920-旧构建网页审计/`（13 个文件，基于 2026-09-11 构建） |
| ⚠️ **可信度折损（未消除）** | 28 | 它赖以为证的整目录 `.dsh-audit/`（含 15 个复现脚本、5 份过程档）**已从磁盘删除**；可复核物只剩 27 的快照、ANCHOR-REPORT 与 `tools/anchor-audit.js` |

> **重写后的主要结论变化（相对旧版）**：① `window.zcode.log` 在生产模式下**只对 lifecycle 与"关键提示"生效**（普通 level 日志不发给宿主，10 篇）；
> ② localStorage 槽位**不是"恒 disabled"**，而是**只在 `performance.navigation.type==='reload'` 且当前无会话时被消费**，非 reload 时页面还会主动清它（21/24 篇）；
> ③ 恢复通路的信号白名单是 `visible|pageshow|online|resume` 且**必须叠加"传输已降级"**才会重开工作区（21 篇）；
> ④ relay 业务 `zcode_type` 有 **18 个唯一类型名 / 20 个联合成员**，壳端零命中的有 6 个（含 `app-error`）（14/16/13 篇）；
> ⑤ 附件分片是 **384 KiB**、秒传靠服务端 `state:'committed'`、续传靠 `nextChunkIndex`（19 篇）。

**两条入口纪律**:页面**只把 `/remote/v4`（含尾斜杠)认作手机远控入口**（`/remote/v4/latest` 会落到桌面版外壳,抓快照可用、当入口不行);新版桌面端"复制链接"给出的就是 `/remote/v4?sid=…`,**用户拿到的链接天然正确**。

**自查口径修正**:`Docs/11` 虽题为 `remote-v4-平台事实`,内容是**安卓宿主平台**事实（不是网页审计);`Docs/12/26/29–47` 是**安卓壳**审计;`01–09` 是**鸿蒙平台**调研。

**`Docs/09` 需要补写的机制**（它写于 2026-09-17,缺 15 项新机制):
- **结论过期两条**（优先修,会误导实现方向)① §1 步骤 4 写的"优先级 controller > 会话流 > sessions-index"与"controller 已关"都不对:现行是 **`phaseOverlay` 按报到时刻谁后到谁算数**;"已关"指**原生自订**那条链,而**页面自己那条是活的且是唯一全局运行态源**。② §2.6 写的"`MAX_PROMOTED = 2`、完成卡 15s 自撤"两处都已删（现在无总数上限 + 同 id 原地改状态)。
- **完全缺失的 15 项**:档 0 判据、档 2 提前接管、交还闸门、KICKED 自愈 cap、`carrierRanThisStint`、4c 防降级、通知七类与完成判定、注入就绪位与种子、Tier1 5s 看门狗、`SurvivalVerdict` 防误报、协议三纪律 + 一禁区、诊断 / 取证层、外观两处注入、多工作区上限与覆盖规则、**应用层不变式清单**。
- 建议:不重写 `Docs/09`（它是历史快照),新增一篇增量并在其文首加指针。

### 10.2 工具与本地参考

| 位置 | 用途 |
| --- | --- |
| `Docs/tools/cdp.ps1` | 真机 WebView 的 CDP 调试助手（安卓侧用法) |
| `android-shell/tools/` | `watch_ci.py`（无 token 读 CI 状态)、`cdp.mjs` / `cdp-capture.mjs`、结构检查脚本 |
| `E:\mimo\参考项目\clashbox` | 鸿蒙 Clash 客户端源码:VPN 扩展声明、长时任务用法、自愈看门狗的现成样本（§5.4) |
| `E:\ScribePad` | 鸿蒙听写应用:`ContinuousTaskManager` + `BackgroundWorkCoordinator`（合法长时任务 + 通知协调的写法) |
| `.workbuddy-ai/migration-review-20260920/` | 本轮全部审计与调研报告（未跟踪) |

---

## 11. 未处置的冲突与待修项（只读发现,留给后续)

| # | 冲突 / 待修 | 位置 |
| --- | --- | --- |
| 1 | 注释写"原生 12s 心跳",源码是 **10s**（12s 是重挂拍长) | `ShellRuntime.kt:1504` 注释 / `Tier2Probe.kt:338-344` |
| 2 | `Docs/26` §3.9 仍写桥表"保留",实际源码里只剩注释 | `RelayBridge` 桥表 |
| 3 | `Docs/25` 的"页面先关再 dial + 交还默认重载"设计**已被真机推翻**（现行:原生直接配对顶掉页面、回前台不重载) | `Docs/25` vs 现状 |
| 4 | 注释仍写"天花板是 2",与实现（无总数上限)矛盾 | `ShellRuntime.kt:1092` |
| 5 | **运行中卡的 intent 不带 `workspaceKey`**（定位会退化) | `Notifier.kt:276-286`;`Docs/29` §F1 已登记 |
| 6 | `setDeleteIntent` 未实现（用户关掉提升后 App 无感知) | 全仓 grep 0 |
| 7 | `conversationRowsRangeV4` 是死路（真机每次 20s 超时),常量保留只作记录 | `RelayWire.kt:40-49` |
| 8 | 鸿蒙:`EntryAbility.ets:12` 注释称底部由 `expandSafeArea` 负责,但全仓仅此注释命中 | 鸿蒙工程 |
| 9 | 鸿蒙:`showLangRefreshBtn` 定义 / 置位但**从未渲染** | 鸿蒙工程 |
| 10 | 鸿蒙:`项目文档.md` 说"Cookie 持久化保留",源码零 Cookie 代码 | 鸿蒙工程 |
| 11 | 鸿蒙:`build-profile.json5:10/:14` **明文签名口令且已入库** | 鸿蒙工程（安全项) |
| 12 | 两端共同:文档口径"注入三保险"在鸿蒙**不能照搬**（G11 的字典序 / 去重语义) | §7.2 |

---

## 附录:本轮审计与调研的原始报告

| 报告 | 内容 |
| --- | --- |
| `A-docs-web-audit.md` | Docs 网页审计资产盘点与时效性 |
| `B-bg-takeover.md` | 后台原生接管:时序链、判死分档、桥细节、交还、三源、真机事实、已删清单 |
| `C-notify-liveview.md` | 通知 7 类、状态判定算法、流体云、点击定位、移植点 |
| `D-inject-observation-recovery.md` | 注入三保险、`inject.js` 分段表、心跳体征、页面观测、恢复策略、外观注入 |
| `E-other-domains.md` | 多工作区、任务索引、诊断日志、上传下载、凭据 URL、UI、CI、测试资产、已删落点 |
| `F-harmony-status.md` | 鸿蒙工程现状底数与缺口 |
| `G-comparison-matrix.md` | 功能对比矩阵、平台鸿沟、降级路线、分阶段计划、`Docs/09` 补写清单 |
| `H-clashbox-mechanism.md` | ClashBox 代理与保活机制（源码级:VPN 扩展、权限清单、自愈看门狗) |
| `I-web-research.md` | ClashBox 与鸿蒙后台流量的网络调研（含官方与社区出处) |
| `J-official-stance.md` | 官方对"后台长连接"的口径与判例（含 FAQ 全文与官方博客实验条件) |
| `K-background-traffic-rate.md` | 后台网络活动频率取证（21 份真机日志复算) |

> **证据强度声明**:本文所有 `文件:行号` 级结论均来自上表报告的本机直读;`【待验证】` 项（G11 / G14 / G18 与 §9 的 C 组)需鸿蒙真机实验才能定案,而当前 `hdc` 无真机可达。

---

## 20. 第二轮：网页审计全量重写（2026-09-20 晚）

### 20.1 做了什么

把 `Docs/` 里**基于旧构建（G1/G2）的 13 篇 remote-v4 网页审计**（10、13、14、15、16、17、18、19、20、21、22、23、24）
**全量重写为"基于当前线上构建的一体报告"**，并同步了 `Docs/README.md` 与 `Docs/28` 的引用口径：

- **基线统一**：每篇文首统一为「审计基线」块（主包 / schema / CSS / 快照 + 审计日期 + 方法 + 偏移口径），
  不再有"修订说明""旧→新对照""本篇偏移已失效"这类叠写内容；
- **结论重取**：旧正文**只当锚点索引用**（提取字面量 → 在新快照上重新定位 → 重新读代码 → 重新下结论），
  找不到支撑的旧结论删除或降级标注为「外部输入」；
- **一致性**：13 篇互相交叉引用（相对链接），读起来像一次写成的同一套。

### 20.2 前置核实（重写的合法性前提）

**线上当前构建 = 快照**：`curl -s https://zcode.z.ai/remote/v4 | md5sum` 与
`Docs/27-…/latest/index.html` **逐字节一致**（`e9b88b8f42cff9a04d28fa92b719962c`）；
主包 `index-B-ilXaCQ.js`（4,821,509 B）、schema `src-wmk2orCZ.js`（285,095 B）、CSS `index-DR0mqKFV.css`（392,369 B）。

### 20.3 旧版留档

13 篇的旧构建版本整批备份在 **`Docs/_archive-20260920-旧构建网页审计/`**（13 个文件）。

### 20.4 本轮的主要结论变化（相对旧版）

| # | 变化 | 篇 |
| --- | --- | --- |
| 1 | `window.zcode.log` 在生产模式下**只对 lifecycle 与"关键提示"生效**（普通 debug/info/warn/error 既不进 console 也不发宿主）；`__ZCODE_RENDERER_DISABLE_LOGGING__` 是总闸，**壳不该开** | 10 |
| 2 | relay 业务 `zcode_type` 全集 = **18 个唯一类型名 / 20 个联合成员**（含旧文未记的 `platform-request/response`、`bridge-degraded`、`workspace-reconnect-*`）；壳端 **6 个零命中**（含 `app-error`） | 14/16/13 |
| 3 | **恢复通路的信号白名单**是 `visible\|pageshow\|online\|resume`，且**必须叠加"传输已降级"**才会重开工作区与重订阅 | 21 |
| 4 | **localStorage 槽位不是"恒 disabled"**：它**只在 `performance.navigation.type==='reload'` 且当前会话为空时被消费**；非 reload 时页面还会主动清它 ⇒ 壳"写槽位 + reload"的做法**是对的** | 21/24 |
| 5 | 附件分片 = **384 KiB**、秒传靠服务端 `state:'committed'`、续传靠 `nextChunkIndex`；**"草稿无条数上限"经复核确认**（OBS-1） | 19 |
| 6 | 结构事实：**功能几乎全在主包**，2,506 个 chunk 承载重资源；唯一例外是 `data-zcode-browser-theme-surface` 住在 `catalogTree` chunk | 15 |
| 7 | 状态栏三态锚点与**页面 inline bootstrap**（`index.html` 的 `syncBrowserThemeSurface`；权威色值 `#161616`/`#f8f8f8`；`zcode:web-remote-control:bootstrap-shell` 走 **sessionStorage**） | 22 |
| 8 | 卡死三现象在新构建上的机制归因：`readSession` 失败静默吞、relay 单连接按序处理、桥降级后全量恢复（**`desktop-disconnected` 有 15 秒宽限**） | 23 |

### 20.5 过程产物

- 方法简报：`.workbuddy-ai/migration-review-20260920/REWRITE-BRIEF.md`（基线 / 一体报告硬性要求 / 统一头部 / 读法纪律）。
- 取证工具：`.workbuddy-ai/migration-review-20260920/tools/{probe.py,scan.py}`（字符偏移窗口 + 全 assets 符号扫描）。
- 证据留档：`evidence-16-*.txt`、`evidence-15-symbols.txt`。
- 派单文件：`tasks/T*.md`（**未使用**：子代理因上游 provider 故障连续失败，最终由父对话直接完成全部重写）。

