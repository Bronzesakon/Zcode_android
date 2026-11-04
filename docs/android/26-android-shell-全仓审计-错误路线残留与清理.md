# 全仓审计：错误路线残留与清理（2026-09-16 下午）

> **这份文档是什么**：对 `android-shell` 做的一次全面代码审计与清理记录。
> 缘起是用户的一句话——**"此前一百多轮 CI 都走在了错误的道路上，积攒了很多无效代码"**，
> 所以这次不是修 bug，而是把"因为当时以为要那样做"而留下的代码系统性清掉。
>
> **方法**：四个子代理并行只读审计（Tier2 原生层 / Tier1 注入层 / 胶水与开关层 / 全仓残留），
> 每条结论都带 `文件:行`；本会话逐条落地并用门禁 + 单测 + 本机编译验证。
>
> **状态**：第一轮清理已落地并通过全部门禁（见 §6）；§5 是刻意**没做**的部分，原因写在里面。

---

## 1. 一句话结论

**四个开关长期恒为"关"，它们封死的代码构成了本项目最大的一坨死代码**：
`AUTO_TAKEOVER_ENABLED=false`（老自动接管）、`SHELL_VISIBILITY_HIJACK=false`（可见性劫持）、
`SHELL_READ_ONLY=true`（只读壳）、`controllerStreamEnabled=false`（controller 流）。
本轮把前三个封死的实现删掉了（保留守卫与日志），第四个当时**刻意保留**（**4a 轮已随订阅实现一起删除**，取证见 §4）。

> **存活数（2026-09-18 之后逐行核对 + 4a 轮更新）**：这四个常量**今天只有一个还在代码里**——
> `SHELL_READ_ONLY=true`（`inject.js`）。`AUTO_TAKEOVER_ENABLED` 与 `SHELL_VISIBILITY_HIJACK`
> 在本轮已从代码删除（只在本文件留史）；`controllerStreamEnabled` 也已在**4a 轮随订阅实现一起删除**
> （§4 那一行是它的取证）。所以"四个开关"是**历史口径**（本文件记录的那四个），不是现状清单。

---

## 2. 已删除（本轮落地）

### 2.1 Kotlin 死代码

| 位置 | 内容 | 为什么是死的 |
| --- | --- | --- |
| `ShellRuntime.kt` | `AUTO_TAKEOVER_ENABLED` + `scheduleBackgroundTakeover` 的死分支 + `maybeTakeOverInBackground` + `takeOverNow` + `rendererProbeToken` | 总闸恒 false；整条"退后台 5s 自动接管"路径永不执行，且与新承载路径重复 |
| `ShellRuntime.kt` | `checkTier1Silence` + `forceTier1SilenceCheckForTest` + `tier1_silence_test` 指令 | 唯一效果是调 `maybeTakeOverInBackground`，而它第一行就 return |
| `ShellRuntime.kt` | `maybeNudgeStalledPage` + `nudgeCount/lastNudgeAt/pendingNudgeAt/pendingNudgeMark/nudgeEscalated` + `NUDGE_*` ×3 | **零调用者**（全仓只剩注释提到它）；`nudgeMark()` 保留（承载防误判在用） |
| `ShellRuntime.kt` | `externalPickerActive` + `setExternalPickerActive`（含 `MainActivity` 3 处调用） | 唯一作用是"文件选择器期间不接管"，而接管早已不走那条路 |
| `ShellRuntime.kt` | `lastLivenessAt` + `TIER2_TAKEOVER_SILENCE_MS` / `RENDERER_PROBE_MS` / `BACKGROUND_TAKEOVER_DELAY_MS` | 只服务上面这些死分支 |
| `ShellRuntime.kt` | `livenessSnapshot()`、重复的 `maybeStartNativeCarrier` doc 注释 | 零调用者 / 重复 |
| `core/Diagnostics.kt` | `listener` 字段 + `setListener()` | 零调用者（设置页直读 `asText()`） |
| `core/NotifyState.kt` | `NotifyUpdate.hasRunning`、`isTerminal()` | 零读取方 |
| `core/SessionsIndexState.kt` | `workspaceId`、`ready`、`resetState()` | 只写不读 / 零调用者（`resetState` 在 `ControllerTasksState` 上另有活的同名函数） |
| `core/RelayBridge.kt` | `hasConversationSubscriptions()` | 零调用者 |
| `core/RelayWire.kt` | `requestBody()`、`listenBody()`、`REQ_PROMISE_CANCEL` | 零调用者（`BridgeSession` 自己拼 body，`encodeBody` 仍在用） |
| `MainActivity.kt` | `notifyForegroundState()`、`EXTRA_WORKSPACE_KEY` | 零调用者 / 写了没人读 |
| `notify/Notifier.kt` | 两处 `putExtra(EXTRA_WORKSPACE_KEY, …)` | 同上 |

### 2.2 资源（整文件级）

| 位置 | 内容 |
| --- | --- |
| `res/values/colors.xml` | **整文件删除**：唯一的色 `ic_launcher_background` 零引用（自适应图标用的是 `@drawable/ic_launcher_background` 那张 PNG） |
| `res/values/styles.xml` | **整文件删除**：7 个 style（`SettingsSection` / `SettingsCard` / … / `SettingsDivider`）全零引用——设置页早已整体换成 MiuiX |
| `res/values/miuix_styles.xml` | `MiuixRowValue` 零引用 |
| `res/values/themes.xml` | `Theme.ZcodeRemote.Splash` 零引用（清单里只挂 `Theme.ZcodeRemote` 与 `.Miuix`） |
| `res/values/miuix_colors.xml` + `values-night/` | 各删 9 个零引用色：`miuix_error` / `miuix_tertiary_container` / `miuix_on_tertiary_container` / `miuix_background` / `miuix_surface_variant` / `miuix_on_surface_secondary` / `miuix_disabled_on_surface` / `miuix_on_surface_container` / `miuix_on_surface_container_variant`（21 → 12） |

### 2.3 注入层（`inject.js`）

| 位置 | 内容 | 为什么是死的 |
| --- | --- | --- |
| 可见性劫持实现 | 谎报 `visible` + `Object.defineProperty` 一串 + 吞 `lifecycle` 事件 + `LIFECYCLE_EVENTS` + `SHELL_VISIBILITY_HIJACK` | 常量恒 false；且它本身就是"让页面不知道自己去过后台"的自伤源 |
| `forceReconnect` | 关掉页面 socket 逼它重连的实体 | `SHELL_READ_ONLY` 早退之后的代码永不执行；真机定罪它是自伤（40 秒拆 10 次） |
| `nudgeReconnect` | 同上（"轻推"） | 同上 |
| `reloadForMissingConversation` | 两级刷新 + `sessionStorage` 限流 + 连续上限 | 同上 |
| `stallReloadIfAllowed` | 刷新间隔 + 连续上限 + `location.reload()` | 同上；**整函数已于 4b（2026-09-18）随看门狗决策机器一并删除**（不再"保留守卫与日志"），见 §2.6 |

> 其余**四处都保留守卫与那行日志**——日志本身是"壳没有在骗页面/没有动页面"的诊断量，
> 删掉它会让判据速查表里的几条失效。（`stallReloadIfAllowed` 是例外：4b 已把它整函数删除。）

### 2.4 单测与注释

- `RelayBridgeTest.kt`：删掉 `assertTrue(state.ready)` 与 snapshot 里的 `workspaceId`（钉的是已删字段）。
- `tools/inject.test.js` 头注释：仍写"covered: the visibility spoof"，与 `:527` 那条**断言"不劫持"**的测试自相矛盾 → 改成只读契约。

### 2.5 `.gitignore`

- 根 `.gitignore` 补 `/*.apk`、`/*.apk.md5`：此前 `zcode-remote-pre93/pre95.apk` 及 `.md5` 处于**未忽略**状态（子目录规则不覆盖根），`git add -A` 会直接提交 13MB 二进制。

### 2.6 注入层卡死看门狗**决策机器**（**4b 轮**，2026-09-18——不是 §2.1–2.5 的那一轮）

§2.3 记的是"把三个终点掏空成一行拒绝日志"，这一节记的是**把决策机器本身拆掉**：就是 §5 当年
列为"值得单独一轮 + 真机回归"的那一块（原 §5 首行、原 §3.9 ④ 那条候选，两处都已随之移出）。
逐项对账在 `.dsh-audit/BATCH4-INVENTORY-4b.md`（43 个清点项）与 `.dsh-audit/BATCH4-REPORT-4b.md`；
代码里的权威记叙是 `inject.js` 的 §5c 段头（改后逐条点名"勿重走"）。

| 删除项 | 内容 |
| --- | --- |
| 决策机器 | `stallArm`（布防）/ `stallFire`（判定）/ `stallReloadIfAllowed`（末档）/ `stallCancel`（撤防）/ `stallState` 及其 8 个字段（含与看门狗共用的"放弃态"闩锁） |
| 巡检 | 心跳里的巡检块、1s 前台巡检 `conversationStreamStallTick` + `startConversationStreamMonitor`（连同 boot 调用点）、`CONVERSATION_STALE_MS` |
| 僵尸订阅指纹 | `zombieSuspected` + `ZOMBIE_FRAME_SILENCE_MS` / `ZOMBIE_ACTIVITY_FRESH_MS` / `zombieSilenceS` |
| 常量与事件 | `STALL_NUDGE_MS` / `STALL_REARM_COOLDOWN_MS` / `PAGE_LOG_STALL_EVENTS` / 页面日志**失败**事件的布防分支 |
| 导出面 | `stall` 导出对象里的 `arm` / `cancel` / `fire`（删后不删就是悬空引用） |
| 日志文案 | 布防／撤防／放弃／僵尸档／"跳过轻推"／"不自动重载页面（看门狗停止干预…）"整族（清点报告 G1–G9 全部） |

**幸存并仍在工作的（本节"量具"口径）**：`readVitals` / `vitalsStalled` / `postPageVitals` /
`nudgeReconnect`（拒绝动手的记账）/ `notePageLogEvent`（含页面日志 → 就绪的**恢复分支**）/
§5b 的"进对话铁判准"（**判据一字未动**，只删掉两处对已删符号的调用）/ `SHELL_READ_ONLY` /
`healDeadLinkOnResume` / `healKickedOnForeground`。

**体征量具新接线（读真机日志的人必看）**：`heartbeatTick` 现在**兼作体征上报**——
读一帧 DOM 体征 → `vitalsStalled` 判卡态 → `postPageVitals(why)`，`why` 取值集合已变为
**`stalled`**（卡态**边沿**，立刻一帧）/ **`periodic`**（其余情况，距上次 ≥60s 一帧）/
**`giveup`**（§5b 末档记账）。旧值 `arm` / `cancel` **已随决策机器消失**——按它们检索真机日志
一条都找不到；原生侧打印格式仍是 `页面体征(<why>): {…}`（`ShellRuntime.kt` 的 `pagevitals` 分支）。
量具失败不影响心跳（整拍 try/catch 吞掉）；DOM 读不到时整拍跳过、不推进闸门。

> ⚠️ **那行被删掉的 `setInterval` 取证**：旧 §5 在这里写过一句"每 1s 一跳"的旁证
> （`setInterval(conversationStreamStallTick, 1000)`）并附了一个 `inject.js` 行号——
> **该引用已随 §5c 一并移除**：1s 巡检本体与它的 boot 调用点都不存在了，那个行号无处可指，
> 别再照它去找代码。

**行数与验证**：4b 自测 `inject.js` 3714 → 3577 行（对批次前快照：+128 / −267）。
⚠️ 订正本文时（同日稍后）该文件**仍在被其他子代理编辑**，快照对差已变成 +137 / −293
⟹ **行数以第 4 批收工时的统一对账为准，此处不写死**。单测与 `node --check` 当时全绿
（**测试项数刻意不写在本文件**：第 4 批收工时由父代理统一更新口径）。
**真机回归：本批未做**（审计机无设备）——重点待复核项是 `页面体征(stalled|periodic)` 是否按
"卡态边沿 / 每 60s"的节奏出现，以及 §5b 的各条行（清点报告 K1–K8：信标/就绪/换代跳过/两次到点的拒绝记账）在真机上是否一字不变。

---

## 3. 多工作区覆盖（`docs/17` §7 的落地）

这是"下一个方向"的功能改动，与清理同批做掉：

| 改动 | 位置 | 说明 |
| --- | --- | --- |
| 单键过滤 → 显式清单 | `Tier2Probe.startCoverage()` | 三档：① 显式清单（多工作区/诊断）② 页面工作区（默认，已验收的生产路径）③ 都没有 → `ordered.take(3)`。**不再全量** |
| 上限 | `Tier2Probe.DEFAULT_MAX_COVERAGE = 3`，传给 `BridgeManager(maxWorkspaces=…)` | 每座桥 = 桌面端一条常驻会话 |
| 失败退避 | `Tier2Probe.recordBridgeResult` / `cooledDown` + `BridgeManager.onBridgeResult` | 同一工作区连续 2 次开桥失败 → 冷却 10 分钟；**显式清单不受冷却影响**（E1 要能看到失败） |
| 独立开关 | `ShellRuntime.multiWorkspaceCoverage`（adb：`multi_ws_on/off`，默认关） | 照 `carrierEnabled` 的样子，验证通过再谈改默认 |
| 诊断覆盖清单 | `ShellRuntime.coverageOverride` + `coverage_ws:<k1>,<k2>` / `coverage_ws_clear` | 专供 E1 做干净 A/B，不必真起第二个任务 |
| 目标计算 | `ShellRuntime.carrierCoverageTargets()` | 优先级：诊断清单 > 开关（page ∪ 有在跑任务，上限 3）> 只开页面工作区 |
| 修不变式 | `carrier_now` 补 `acquireCarrierWakeLock()` | 原来它置 `carrierStarted=true` 却不持锁，破坏"承载在跑才持锁" |
| 判据 | `stall_state` 增打印 `多工作区=… 覆盖=…` | 一眼验收 |

**E1 怎么跑**（不需要用户配合）：

```bash
ADB="C:/Program Files/UotanToolbox/Bin/platform-tools/adb.exe"
bc() { MSYS_NO_PATHCONV=1 "$ADB" -s "$S" shell am broadcast \
  -n com.zcode.remote/.DiagReceiver -a com.zcode.remote.action.DIAG --es diag_cmd "$1"; }
bc "coverage_ws:E:\\MiMo ASR Assistant_harmony,<另一个key>"   # 设 3 座
bc stall_state        # 确认"覆盖=…"
bc carrier_now        # 手动接管（会开这几座桥）
```

判据：① 每座各自 `bridge ready for <key>` 或 `bridge open failed for <path>: <reason>`；
② 桌面端 `unregistered host` / `uncaughtException` **不增长**（2026-09-16 基线 7 / 14）；
③ 桌面端日志出现 `workspaceKeyCount: ≥2`。**E2 仍需用户**在另一个工作区起任务。

### 3.1 E1 真机结果（2026-09-16 傍晚，146）：**通过**，并暴露一个新问题

**跑法**：两个工作区各一个在跑任务（`E:\Zcode_harmony` 与 `C:\Users\wdn32\.zcode\workspace\default`），
页面上分别停留过；退后台后 **100 秒**承载接管（`16:53:25 退后台 → 16:55:05 接管`）。

| 判据 | 实测 |
| --- | --- |
| 桌面端能否同时**开**多座桥 | ✅ `Tier2: 多工作区覆盖 2 座：…` → **两条 `bridge ready for …`** → `tier2 active coverage: 2 workspace(s)` |
| 非页面工作区的会话能否拿到正文（E2/E3 合并） | ✅ 两条 `subscribed conversation for sess_…` + 两条 `活进展 <key>：…`，两座桥的正文各自推进（A 122→162、B 191→227，共 10 分钟） |
| 流体云 | ✅ 两张卡都 `PROMOTED_ONGOING`（`运行中 · …` + `shortCriticalText=运行中`），编号分属两条对话 |
| ⚠️ **能否同时"收帧"** | ❌ **不能——同一时刻只有一座桥被服务**（见 §3.3，147 实测的铁证）；所以更准确的说法是"**轮流跟手**"，不是"并行跟手" |
| 我此前的"1 成功 6 被拒"旧证据 | ❌ 确认为 **host 之死污染**；桌面端本身接受多桥 |

**新问题（本批已修，见 §3.2）：重锚并发 → 整桥回收循环。**

```
16:57:17.170（同一毫秒）[E:\Zcode_harmony] resyncConversationV4  +  [default] resyncConversationV4
16:57:17.456  reanchor conversation for sess_46da…(force snapshot)   ← 一个 0.3s 成功
16:57:25.147  conversation resync failed for sess_ec94…: timed out   ← 另一个正好 8.0s 超时
16:57:30.350  tier2 bridge recycled for …default (1 session(s) restored)
```
**每 24s 必有一座桥超时 → 整桥回收，输家交替**；10 分钟 **25 次回收 / 25 次超时 / 26 次重锚成功**。
内容没断（饿死窗口最长约 96s 后由回收救回），但代价是每 24s 拆建一次握手 + 重订阅。
⇒ `docs/17` §8 那句"3 桥 ≈ 0.125 次/s，**可接受**"要按"**每 24s 一次整桥回收**"重估。

### 3.2 修法（**最终形态**，148）

| # | 改动 | 位置 |
| --- | --- | --- |
| ① **重锚串行**（保留） | `BridgeManager.reanchorProgress()` 整体互斥 + **单线程逐桥**，桥间 `REANCHOR_GAP_MS=500ms`（回收后留 3s）；`BridgeSession.reanchorConversations` → **阻塞式** `reanchorConversationsBlocking(): String?`（不再自己起线程、把失败原因交回调用方） | `RelayBridge.kt` |
| ② **回收门槛：失败即回收**（146 原行为，**中间版本曾改成"连续 3 轮"，已回退**） | 理由是 §3.3 的真机结论：**回收是唯一的"换服务"手段**，回收周期 = 两张卡的轮换周期。延迟回收只会让未被服务的那座桥干等（147 实测 70s 零刷新）。失败日志带上"最近收帧 Ns 前"（`sinceLastConvFrameMs()`，由 `convLastFrameAtMs` 支撑） | 同上 |

**已删除**（不要重加）：`starvedSessions()` / `CONV_STARVE_MS` / `reanchorFailStreak` / `REANCHOR_FAILS_BEFORE_RECYCLE` —— "等 N 轮再回收"整条思路已被 §3.3 证伪。

**判据**：`reanchor failed for <key>：…；最近收帧 Ns 前 ⇒ 整桥回收（换服务权）` 每轮最多一次，
且**两座桥交替**；任一座桥的静默不超过 ~24s（8s 超时 + 重建）。

> ③（把 resync 超时 8s 放宽到 15–20s）**没做**：它治的是症状且有副作用（拉长重锚临界路径）；
> 只有在"输的那座是答晚了而不是没人答"时才值得加——可用一次临时抬超时的实验判定。

### 3.3 147 实测：① 无效、② 造成可见停滞 —— 真机制是"**一座桥独占服务**"（2026-09-16 晚）

147（① 串行重锚 + ② 三轮才回收）真机复跑，两条对话仍在两个工作区：

```
18:21:39  subscribed A(sess_ec94…) → bridge ready A     ← A 拿到快照（正文 18:21:29）
18:21:41  subscribed B(sess_46da…) → bridge ready B     ← B 拿到快照（正文 18:21:21）
18:21:57 / 18:22:21 / 18:22:48   reanchor …sess_46da… (force snapshot)   ← 三轮**全是 B 成功**
18:21:56 / 18:22:20 / 18:22:44   reanchor failed …sess_ec94… timed out   ← 三轮**全是 A 失败**
          ↑ 期间 A 一个字都没到（"最近收帧 65s 前"），B 每 14–17s 一段、滞后 4–8s
18:22:49  subscribed A（回收后重订）→ A **立刻**恢复（滞后 7s、每 12–16s 一段）
          同时 B 就此停住（18:22:43 之后再无帧；B 的 reanchor 18:23:09/18:23:33 开始连续失败）
```

**结论（三条，都改变了前面的判断）**：

1. **① 没有治好超时**：串行（一次只有一条 resync 在飞）之后，超时照旧——所以"两桥并发争用"
   这个归因**被推翻**（§3.2 里写的"①治本"不成立）。
2. **② 把隐形 churn 变成了可见停滞**：回收不只是浪费，它**是唯一的"换服务"手段**。
   146 每 24s 回收一次 ⇒ 服务权每 24s 换一次 ⇒ 两张卡轮流跟手（谁都不断太久）；
   147 等到第 3 轮（72s）才回收 ⇒ 未被服务的那座桥**整整 70s 一个字都不刷**。
3. **真机制：同一 window 下桌面端只服务一座桥，服务权归"最近一次订阅成功"的那座**。
   铁证：B 的最后一帧时刻 = A 回收重订的时刻（18:22:49），一格不差；反向亦然。
   ⇒ E1 的准确结论是"**能开多桥、能订多会话，但同一时刻只有一座桥在收帧**"，
   "多工作区并行监听"在当前桌面端上只能做到"**轮流**"，**而轮换周期由我们的回收节奏决定**。

**因此下一步的正确方向**（不是再调超时）：
- **回退 ②**（`REANCHOR_FAILS_BEFORE_RECYCLE` 3 → 1，即失败即回收），保留 ①（串行无害、更有序）；
- 更好的做法是**把轮换显式化**：既然服务权归最近订阅者，就按固定周期主动重订"当前没被服务的那座"，
  把黑窗从"等 8s 超时 + 等 N 轮"变成"我们说了算"，并可按各工作区是否有在跑任务加权；
- 桌面端问题登记：**为什么同一 window 只服务一座 workspace bridge**（待桌面端回答）。

### 3.4 "流体云卡片消失重建"的根因：**空更新被当成"运行集为空"发布**（2026-09-16/17，已修）

用户报"退后台后流体云卡片消失又重建"。加了两行诊断（`卡片撤回` / `提升集合变化`）后当场抓到：

```
09-16 19:43:05.796  subscribed conversation for sess_ec94…（回收后新桥订阅）
09-16 19:43:05.865  提升集合变化: []（运行 0 个：）        ← 运行集被抹成 0 → ColorOS 收回卡片
09-16 19:43:06.077  tier2 bridge recycled for E:\Zcode_harmony
09-16 19:43:06.766  提升集合变化: [#814650 #216987]（运行 2 个）  ← 0.9s 后"重建"
```

**每 ~24s 一次、与桥回收同拍**；而 `卡片撤回` 恒为 **0**——通知本体从没被 cancel，是**提升集合**被清空。

**根因**：`TaskStore` 用"四个列表全空"表示**"什么都没变"**（`applyLivePreview` 文本没变/为空、
`applyConversationRunState` 相位没变），而 `ShellRuntime` 的 `turnStateSink` 里 `applyUpdate(update)`
是**无条件调用**的 → `enqueueOngoing` 把 `update.running` 当作**当前运行集** → 一个空列表就把已发布的
运行集抹成 0。触发点是**新桥的第一帧必然重报一次 turnState（而相位并没有变）**，所以每次回收必现。
另两条同类路径（`pushLivePreview` / `onConversationText`）本来就有 `isNotEmpty()` 守卫，**只有这条漏了**。

**修法（两处，150）**：① `turnStateSink` 遇空更新直接 `return@post`；② `applyUpdate` 加兜底守卫
（`!urgent && running.isEmpty() && removedIds.isEmpty()` 一律不发布）。真正的"运行集变空"一定带
`removedIds`（见 `TaskStore.buildUpdate`），不会被挡——真机验证：任务真正结束时 `卡片撤回 id=216987（撤回后运行集 0 个）`
照常打印。

**A/B 铁证**（同一条触发序列）：

| | 修复前（149，19:43:05） | 修复后（150，18:29:44） |
| --- | --- | --- |
| 新桥订阅后 | 69ms 后 `提升集合变化: []（运行 0 个）` | 44ms 后只有 `会话运行态：在跑` |
| 卡片 | 被系统收回 → 0.9s 后重建 | **全程没动过** |
| 2 分钟承载期 | 每 ~24s 一次 | `提升集合变化` 仅启动 2 条、`卡片撤回` 0、采样器零"消失" |

> 排查须知：`onPageReloaded()`（注入层 `ready` 事件）会 `store.reset()` 并**直接**调 `syncRunningTasks`，
> 绕过 `flushOngoing`，所以那条路径的撤回不会打 `卡片撤回`——但它是设计内的（页面重载后确实该清）。

### 3.5 N=2 定为优化目标：主动轮换（151）

**产品约束**（用户 2026-09-17 拍板）：ColorOS 只并发 **2 张**流体云卡（`PromotionPolicy.MAX_PROMOTED=2`），
所以**只按 2 座桥优化**，3 座以上的方案只作**远期规划**（见 `docs/17` §11）。

旧轮换的代价：没被服务的那座**必然**白等 **8s** RPC 超时 + 3s 重建 → 一轮 14–17s，静默窗就是这么来的
（实测 17–21s）。151 改成**主动轮换**：

- 只对"其余桥"（N=2 时＝当前被服务的那座）发 `resync(forceSnapshot)`；
- 对**最饿的那座**（最近收帧距今最久）**不等超时**，直接回收换服务权 → 判据行
  `reanchor rotate for <key>：最近收帧 Ns 前 ⇒ 主动回收换服务权`；
- 门槛 `ROTATE_MIN_STARVE_MS`：桌面端哪天改成并行服务多座桥（两边都很新）时**不乱拆**；
- 于是交接只剩一次重建（~5s），周期由 `LIVE_PROGRESS_POLL_MS × LIVE_REANCHOR_EVERY_POLLS` 决定。

**实测（151，24s 拍）**：`重锚失败 0`（旧版 10 分钟 25 次超时）、两桥严格交替、交接 ~5s、
`提升集合变化` 仅启动 2 条、采样器零"消失"；单卡更新间隔 24–41s。

### 3.6 轮换周期定案 12s（152/153）——**门槛必须小于拍长**

用户要更跟手的节奏，于是把 `LIVE_REANCHOR_EVERY_POLLS` 2 → 1（**拍长 24s → 12s**）。
但要记住：**这个常量决定的是"卡片更新节奏"，不是交接耗时**——桌面端同一时刻只服务一座桥，
"每 N 秒轮换" ⇒ 每座桥被服务 N 秒、静默 N 秒 ⇒ **单卡更新间隔 ≈ 2N**。

**152 第一次没生效（重要教训）**：拍长改了 12s，但 `ROTATE_MIN_STARVE_MS` 还是 15s —— 每拍到点时
饿的那座桥只静默了 12s < 15s ⇒ **跳过**，下一拍（24s）才够格。真机实测 rotate 间隔一格不差是
**24s**，白白以为改了没用：

```
19:04:02 rotate A (+24s)   19:04:26 rotate B (+24s)   19:04:50 rotate A (+24s) …
```

**修法（153）**：`ROTATE_MIN_STARVE_MS` 15s → **8s**（< 12s 拍长）。**规则：门槛必须小于拍长，否则拍长被门槛吃掉。**

**153 实测（12s 拍，两工作区，零异常）**：

```
19:12:55 原生接管 → 19:12:57 多工作区覆盖 2 座 → 19:12:58/59 两桥 ready
19:13:08 rotate A（首拍 13s）→ 19:13:32 rotate B（+24s，首轮相位未稳）
19:13:44 rotate A（+12s）→ 19:13:56 rotate B（+12s）→ 19:14:08 A（+12s）→ 19:14:20 B（+12s）… 严格 12s 交替
```

| 判据 | 结果 |
| --- | --- |
| 轮换节奏 | **12s**（首轮 24s 后稳定） |
| `重锚失败` | **0**；6 次轮换全是主动的，交接 ~5s |
| `卡片撤回` / `提升集合变化` | 0 / 仅启动 2 条（**卡片零抽动**，采样器在承载窗口内 0 次"消失"） |
| 单卡更新间隔 | **10–23s**（24s 拍时是 24–41s） |
| 代价 | ~**4 次回收/分钟**（24s 拍时 ~2.5 次）；桌面端 `unregistered host`/`uncaughtException` 需继续观察（基线 7 / 14） |

**新坑（记下来）**：`multi_ws_on` 是**内存开关，进程重启即丢**。09-17 复跑时进程是新的，日志直接写
`只开页面当前工作区的桥：…` + `tier2 active coverage: 1 workspace(s)`——一轮只测到单桥。**每轮真机测试前先补发**
`multi_ws_on`，并用 `stall_state` 的 `多工作区=true 覆盖=…` 确认。另外**两个任务必须落在不同工作区**，
且**每个工作区都要在页面上打开过**（壳只认识页面打开过的工作区，见 §3.7 的发现链缺口）。

### 3.7 定向接管：发现链落地（154→158，真机验收通过）

**要解决的缺口**：壳只认识"页面打开过的工作区"（`pageWorkspaceKey` 来自页面上报），所以多工作区覆盖
以前要求用户**先把每个工作区在手机上打开一遍**（09-17 因此白跑两轮）。

**子代理只读审计网页 bundle 的结论**（快照 `docs/05`，2026-09-11）：

| 结论 | 证据 |
| --- | --- |
| 手机端"任务首页"那条**可见**的活动态不走 controller topic，走 Flow A 的 `workspace-list-request` | `index:897:363848 (off=4748863)` |
| **`bootstrap-response.result.tasks` 是 schema 必填**（真机 24–25 KB，我们每次在收、此前只记大小） | `src:39:155651 (off=205916)` |
| `workspace-list-response.result.tasks` 是 `.optional()`，**真机也带**（82 个任务） | `src:39:155768 (off=206033)` |
| 危险/安全的判据是显式的：`on*`/`onDynamic*` 一律 `listen`，其余一律 `call` | `index:7:2894/2919/3019 (off=271017/271042/271142)` |
| ⛔ 当年打崩桌面端的那次 listen **channel 打错了**（我们发的是 `zcode-agent.onDynamicControllerFrame`，而它属于 `window-controller`） | 桌面端日志逐字 + `src:41:8839 (off=242119)` |

**字段形状踩坑（两个同名不同义的类型）**：

- `Lue`＝`controller/tasks-index` 的元素：**嵌套** `{address, meta, membership, sourceAvailability, liveStatus(5 值，含 waiting), activity?}`（`index:2:80856`）；
- `Ng`＝bootstrap / workspace-list 的 `tasks` 元素：**扁平 14 键**
  `taskId, title, workspacePath, workspaceIdentity?, remoteSessionId?, workspaceLabel, workspaceKind, createdAt, updatedAt, provider?, unreadAt?, displayStatus, pinned?, archived?`，
  运行态叫 **`displayStatus`**，**4 值** `idle|running|completed|error`（`src:39:154166/154171`）。

我先按 `Lue` 的名字写解析器 → 真机摘要**全是 `?`**（`159` 形状探针实测）。**定性手段**：加一行
**只落键名**的形状探针（`承载发现形状：tasks[0]=[…] · 状态类字段: displayStatus=running`）当场定案，
子代理续答给出 `Ng` 的逐字 schema 印证。工作区键必须用**与页面逐字同规则**的
`workspaceIdentity?.trim() || workspacePath`（页面 `GD`/`Co`，`src:44:3789`；顺序不能反——远程工作区必须用 identity）。

**实现**：`RelayBridge.RelayTaskDigest` 解析两处已在收的响应 → `displayStatus ∈ {running}`（`waiting`/`prewarming` 容错）
→ `Tier2Probe.discoveredCoverage()` → `startCoverage()` 的覆盖目标。

**接线时机（154 的 bug，已修）**：发现链数据要**配对之后**才有，而覆盖清单原来在
`maybeStartNativeCarrier` 里算（日志 `发现链 0 个`）→ 把并入点搬到 `startCoverage()`（配对后 1.2 s）；
只在多工作区开关打开时并入，`coverage_ws:` 诊断清单不并入（E1 要"只用它"）。

**真机验收（158，页面只显示过 A）**：

```
19:51:42 承载覆盖目标（启动前）：1 座（page=E:\Zcode_harmony · store 在跑 1 个）
19:51:43 承载发现[bootstrap]：82 个任务 · 有活动 2 · running×2/completed×80 ·
         running@C:\Users\…\default | running@E:\Zcode_harmony
19:51:44 承载发现[workspace-list]：82 个任务 · 有活动 2（与 bootstrap 一致，交叉验证）
19:51:44 Tier2: 发现链给出 2 个有活动任务的工作区 ⇒ 多工作区覆盖 2 座
19:51:46/47 bridge ready ×2（**B 从没在手机上打开过**）
19:51:54 / 19:52:07 / 19:52:19  rotate 每 12s 两桥交替
         两张卡同时 PROMOTED_ONGOING 且正文各自推进；重锚失败 0、卡片撤回 0、提升集合变化仅启动 2 条
```

**遗留（都没做）**：① `Ng.remoteSessionId` 还没用上——用了可以省掉"等 SI 才知道 sessionId"这一步；
② `window-controller.listTaskList`（**call，安全**）可做周期性刷新源（带 `title` 与 `activity.lastActivityAt`）；
③ 页面自己订的 `controller/tasks-index` 帧仍是**零写入的第三条源**，未接。

### 3.8 上限 3 → 5，且轮换改成与 N 无关（159，**只做理论验证**）

用户 2026-09-17 拍板：**把理论上限推到 5、保证轮换不冲突，理论验证通过即可；5 并发的真机实验在鸿蒙侧做。**

| 改动 | 位置 | 说明 |
| --- | --- | --- |
| 覆盖上限 3 → **5** | `Tier2Probe.DEFAULT_MAX_COVERAGE`、`ShellRuntime.MULTI_WS_COVERAGE_CAP`（**必须同步改**；引擎侧 `BridgeManager.maxWorkspaces` 构造默认是 12，是这两处把 5 传进去） | **成本取舍，不是平台/协议限制**：每座桥 = 桌面端一条常驻会话 + 每轮一次回收握手 |
| 一轮耗时与 N 解耦 | `RelayBridge.reanchorProgress` | **只对"被服务的那座"（最近有帧的）发 resync**、**只轮换"最饿的一座"**，其余这一轮什么都不做——它们发出去必然白等 8s 超时（对端同一时刻只应答一座）还会升级成整桥回收。⇒ **一轮恒 ~5s、握手频率恒 1 次/12s，与 N 无关**。单桥（N=1）照常 resync、不轮换 |

**理论结论（写进 README「实现要点」20 固化）**：

- **握手成本与 N 无关**（每轮只回收一座）⇒ 抬高上限**不会**让轮询互相冲突；
- **代价落在单卡刷新节奏**：每轮 12s、N 座轮流 ⇒ 单卡间隔 ≈ **N × 12s**
  （N=2 ~24s / N=3 ~36s / **N=5 ~60s**），窗内仍连跳几段；
- **卡片不设上限（2026-09-18 用户否决硬上限）**：这条曾经的理由"ColorOS 只并发提升 2 张流体云卡"被用户现场推翻（三个任务同时跑 ⇒ 手机三张卡同时显示、dump 里三条 `PROMOTED_ONGOING`，而当时我们只请求了 2 个 ⇒ **系统自己也会多提**）。本地官方资料里也没有任何"每应用最多几张"的数字。现在在跑的任务有几个提升几个，界限只加在"留到用户清掉"的完成卡上（`MAX_FINISHED_PROMOTED = 3`）。
- `docs/17` §11.2 那张"N=3 一轮 23–26s ⇒ 丢拍"的表**不再成立**（旧写法：给每座桥都发 resync）。

**未做**：Android 侧 N≥3 的真机验证（委托鸿蒙侧）。若要在本仓库验：起 3–5 个在跑任务
（发现链自动给出工作区，不必手动开清单），判据＝每座桥都拿到过服务窗 / 各桥最大静默 ≈ (N−1)×12s /
`重锚失败 0` / 桌面端 `unregistered host`·`uncaughtException` 不增长（基线 7 / 14）。

### 3.9 清理轮（2026-09-17）：删 D7 开关 + 修鸿蒙侧审计 6 条 + 通用死代码

执行方式：**一个子代理统一下文 + 三个嵌套只读审计代理**，**每段一提交、四道门禁全绿再动下一段**。
八笔提交（`1eaa771` `3ba59c1` `2a28f7b` `0c2df11` `f09b0d5` `a91069b` `e8cb66b` `11573e4`），
只含 `android-shell/` 下 15 个文件；JS 94 → **95 项**、Kotlin 92 项不变。

**① 删除 D7「订阅所有工作区」开关**（用户拍板；它是 Tier1 在**页面 socket** 上给所有工作区开桥的实现，
2026-09-15 真机 A/B 定罪，功能已被承载 + 发现链取代）：`Prefs`（键 + 字段）、设置页（开关 + 文案 + 布局行）、
`WebAppBridge`（配置字段 + 兜底 JSON）、`inject.js`（默认 config / client 选项 / onStatus 重试块 /
**整个 3b 节 `maybeStartActive`** / `__zcodeShellSetSubscribeAll` / ready 帧字段）、
`zcode-protocol.js`（构造字段 / status 的 `active` / `_scheduleReopen` / `start()` / `retryStart` / `dispose`）。
**JS 侧"永远被动"的等价行为**：开关字段彻底不存在 ⇒ 客户端没有"我该主动开桥"这个状态；
测试把"关＝零写入"换成"**默认＝零写入**"（新增"默认配置下配对后不主动开桥"，断言零
`workspace-list-request` / 零 `bridge-open` / 零订阅），另留一条**删除护栏**断言
（`client.subscribeAll === undefined`，有人把开关加回来就红）。保留：`_passive`/`_pageOwned`/
`_requestedBridgeIds`/幂等、`SHELL_READ_ONLY` 两处守卫、`pageCoverage`。

**② 鸿蒙侧交接审计 6 条：全部 Fix**

| # | 问题 | 修法 |
| --- | --- | --- |
| 1 | "一轮恒 ~5s"只在单会话时成立：`reanchorConversationsBlocking()` 对**该桥每个会话**串行 `callBlocking(8s)` + 等帧 `3s` ⇒ 一轮 = 会话数 × 11s（某工作区跑 3 个任务时最坏 33s ≫ 12s 拍，CAS 静默丢拍） | **一轮只拉该桥最饿的那一个会话**（`convLastFrameAtMs` 最小；从没收到过帧＝最饿）；RPC `8_000→4_000`、等帧 `3_000→2_000`；rotate 日志打**双方会话数**（`桥 N 座 · 被服务那座 S 个会话 · 本座 M 个会话`）。⇒ **一轮 ≤ ~9s，与桥数、会话数都无关** |
| 2 | 失败路径一轮回收两座（`served` 失败回收后仍去回收 `starved`）⇒ ≈14s > 12s 拍，且两张卡同时消失重建 | `err != null` → 回收 served + sleep 3s + **`return@Thread`**；把"**一轮最多一次握手**"写成不变式 |
| 3 | 注释说"显式覆盖不受失败冷却影响"，代码却 `filterNot { cooledDown }`（E1 会静默打不通） | **按注释改代码**：`coverageIsDiagnostic == true` 时豁免冷却（两边各自打日志）；另修两处同样写错的 KDoc |
| 4 | `explicit = (coverageWorkspaces + discovered).distinct()` 未截断 ⇒ 日志可能报 >5 座而实际只开 5 座 | `explicit = wanted.take(DEFAULT_MAX_COVERAGE)` + 超限时把取法写进日志 |
| 5 | 两处常量"必须同步"只靠注释 | `Tier2Probe.DEFAULT_MAX_COVERAGE` 改 **`internal const val`**，`ShellRuntime.MULTI_WS_COVERAGE_CAP = Tier2Probe.DEFAULT_MAX_COVERAGE`（编译期兜底） |
| 6 | ① `keyOf` 的 `workspaceLabel` 兜底会产出页面/桌面端都不认的键；② `carrierCoverageTargets()` 里 `discoveredCoverage()` 是死调用；③ 全平局场景未说明 | ① 删兜底（label 只进诊断摘要）；② 删死调用 + 注释指向真正的并入点；③ 加注释说明"全平局＝首轮正常形态，不引入排序状态" |

**③ 通用死代码**：删 3 个 Kotlin 零引用符号 + 1 未用导入、4 个 JS 零调用点函数
（`clientHello` / `SessionsIndexState.reset` / `takeObservedCounts` / `retryStart`）、
测试侧 1 个恒真断言块 + 编辑残留 + 陈旧横幅。

**④ 保留并列为下一轮候选**（本轮**刻意不删**）：
- `zcode-protocol.js` 的 **active 段（约 1120/2908 行生产跑不到）**，含 `CONVERSATION_SUBSCRIBE_ENABLED=false`
  与 `MAX_REOPENS_PER_BRIDGE=0`；`_forgetBridge` 虽在段内但**仍活着**（`_dropRedundantBridge`/`_handleDegraded` 调它）。
- `RpcFrameAssembler.purgeStale` 零调用——它是"分片丢失后清理半成品"的**未接线补救**（`_assemblies` 现无上限），删了等于钉死泄漏。
- `inject.js` `startHeartbeat`/`HEARTBEAT_MS` 零调用（现由原生泵驱动探针，页面自己那条心跳是页面自己的）。
- `lastForcedReconnectAt`（`inject.js`）**全仓从未赋值** ⇒ `forceReconnect` 里那个"180s 限流门"恒假永不进入，
  而注释自称它修好了崩循环——**是决策不是清扫**（恢复写方 vs 连门一起删）。
- ~~看门狗/兜底壳里的零引用常量与只写字段（合计 ~95 行）——就是那个"模块壳"。~~ **已于 4b（2026-09-18）随决策机器整块删除 ⟹ 从本节候选移出**，记在 §2.6；真机回归待下一次装机。
- Kotlin 只写不读：`TaskSnapshot.hasBackgroundWork`、`Workspace.source` / `.path` / `.identity`
  （一族，一起留或一起删）、`SessionEntry.parentSessionId`；
  RISKY（有测试依赖）：`TaskStore.removeWorkspace`、`hasRunningTasks`、`AttentionEvent.interactionId`。

**⑤ 不确定项（别当已验证）**：本机**无设备**，八笔提交只有门禁 + 单测背书；
`reanchorConversationsBlocking` / `reanchorProgress` / 上限截断 / 冷却豁免 / `RelayTaskDigest.keyOf`
**在 Kotlin 侧零测试覆盖**（补测要先给 `BridgeSession`/`BridgeManager` 造可注入的 channels seam，本轮没做）。

**⑥ 需真机回归（按优先级）**：① 段 2+3：`reanchor rotate … （桥 2 座 · 被服务那座 S 个会话 · 本座 M 个会话）`
是否**每 12s 一拍**、一轮 ≤~9s、失败时**只回收一座**；② 段 4：`多工作区覆盖 N 座（上限 5）` 与实际开桥数一致、
`coverage_ws:` 里冷却中的工作区**照开**；③ 段 5：发现链仍能给出工作区（`keyOf` 不再拿 label 兜底）；
④ 段 1：设置页"高级"卡片只剩诊断两行、通知/实况窗照常、ready 日志变成 `注入脚本已就绪 (href=…)`；
⑤ 段 6 纯删除，常规回归即可。

### 3.10 提前接管：正向真机验收（162）+ 抓到一个交还误判并修掉（163，2026-09-17 夜）

**为什么有这一段**：162 只在「无在跑任务」的场景验过（`工作区相位 … completedSuccess×25`，
条件正确地不触发、判死路径照旧接管）；**正向**（有在跑任务 + 停在概览页 + 退后台）是留给新会话的。

**场景（用户起任务，我按交接文本 §4.0 跑）**：`工作区相位 … running×1`（#667882 /
`sess_1b79377f-d001-4320-bf86-adc16bb0b1fa`，工作区 `C:\Users\wdn32\.zcode\workspace\default`）；
页面用 CDP 读到停在**工作区概览页**（`7 个工作区 · 39 个任务`、无 composer）；
22:48:11 应用进入后台（HOME）。

**① 判据（162，全部出现；退后台到桥就绪 ~43s，未走 ~100s 的判死路径）**

| 时刻 | 日志 |
| --- | --- |
| 22:48:41.257 | `后台原生承载：候选提前接管（页面最近 72s 前 收到会话帧 · 1 个在跑任务），观察 10s 后动手` |
| 22:48:51.259 | `后台原生承载：**提前接管**——页面活着但最近 82s 前 收到会话帧（没人喂卡片），且有 1 个在跑任务` |
| 22:48:51.733 | `Tier2: ★接管配对成功（matched）——页面连接已被顶掉` + `页面连接被顶掉（relay 返回 KICKED，应用在后台）` |
| 22:48:51.951 | `承载发现[bootstrap]：86 个任务 · 有活动 1 · running×1/completed×84/error×1` |
| 22:48:53.102 | `Tier2: 发现链给出 1 个有活动任务的工作区` → 22:48:54.015 `bridge ready …`（`zcshell-bridge-mu5nair5-ehfu41`） |

**② 卡片跟手（同刻三方核对：`android.text` + `when` + 日志那一拍，规矩见 README「写给自己」）**

| 卡片 `when` | 卡片正文开头 | 日志同刻 `活进展` |
| --- | --- | --- |
| 22:51:03 | `**根因找到了，而且证据很硬。** src/models.rs…` | 22:51:03.284 同文 |
| 22:51:41 | `## 部署完成，但你要的 glm-5.3-flash 被上游拒了…` | 22:51:41.115 同文 |
| 22:52:49 | `**账号池已自愈**（1/2 个账号可用）…` | 22:52:49.007 同文 |

**③ 交还（回前台，22:54:13 用 `am start -f 0x20000000`）**：
`KICKED 自愈：1.5s 后重载页面（第 1/2 次），让页面重新配对` → 22:54:15.583 网页开始加载
→ 22:54:16.189 `relay socket open (#1)` → 加载完成 466ms → 页面重新配对/订阅；**只重载一次**
（无第 2/2 次）。后台存活检查：`时长 6 分 2 秒，心跳 36 次（首次在退后台后 8s），323 帧，配对 16 次 → 保活成立`。

**④ 发现：接管后 10s 被「误判链路已恢复」交还，同一拍又接管回来（**已修，163**）**

```
22:49:01.278 WARN 后台原生承载：页面链路已恢复（最新入站帧 16s 前），原生交还
22:49:01.281 WARN Tier2: 关闭（页面链路已恢复）存活=10s 心跳=1 ack=1
22:49:01.287 WARN 后台原生承载：**提前接管**——…          ← 同一次调用里又接管
22:49:02.079 WARN Tier2: ★接管配对成功（matched）          ← 重新配对
22:49:04.520 DEBUG bridge ready …（第二座桥）              ← 22:49:05.288 第一座才拆完（停止是异步的）
```

**根因**：反向不变式「页面链路一旦自己活了，原生立刻交还」是用 `lastInboundAgeMs` 判"活了"的——
这在**判死**路径上成立（那时页面已静默 35s，之后再来帧一定是它自己回来了），但对**提前接管**
不成立：接管时桌面端会给页面发一帧 `KICKED`，页面随即 `dispose` 掉 socket
（22:48:51.776 `socket.close() … X2t.dispose ← at Object.onFailure`），而**这一帧本身**就刷新了
`lastInboundAt` ⇒ 10s 后 `age=16s < 35s` ⇒ 壳把连接交还给一个**已经进终态**的页面。
只因为页面 close 让 `socketsClosed` 变化、被 20s socket 守卫挡住，才没连续抖——**是运气不是设计**。
本次两座桥交接恰好无缝（旧座 22:49:05.288 拆完时新座 22:49:04.520 已 ready）所以卡片没断，
**时序稍差就是 ~10s 空窗**。

**修法（163）**：新增纯函数判定器 `core/CarrierHandback.kt`（JVM 单测 `CarrierHandbackTest`，7 条）：

- `pageIsServing(socketState, paired, convFrameAgoMs, convSilenceMs)` ＝ socket `OPEN` **且** 仍 `paired`
  **且** 最近 30s 内收到过 `conversation/` 帧（＝提前接管的判据反过来读；概览页 `-1` 不算）；
- `shouldHandBack(pageLinkAlive, startedEarly, pageIsServing)`：判死路径照旧（链路活了就交还），
  **提前接管路径要求页面"又在服务了"**；不满足时壳保持承载并只写一行
  `后台原生承载：提前接管暂不交还（页面最近 16s 前 收到会话帧 · socket=-1 paired=false）——age<35s 只说明收到过帧，不说明页面在跟会话`。
- `ShellRuntime` 记住本次承载的**来路**（`carrierStartedEarly = age < STALL_SILENCE_MS`，即启动那一刻页面是否还活着），
  并在回前台交还 / 关开关 / `clearCarrierState` 三处复位；另加一条自愈：`carrierStarted` 与
  `Tier2Probe.isRunning()` 不一致时（Tier2 配对失败/桌面端拒桥自停）先对齐标志，免得新分支把不存在的承载一直当在跑。
- 顺手单源：会话帧年龄的文案抽成 `convAgoText()`（原来两处内联三元，现在三处共用）。

**⑤ 发现 2（**未改，待用户拍板**）：轮次边界处卡片撤回 → ~1s 后重建**

4 次 `卡片撤回`：22:41:33 / 22:43:50→22:43:51.6 重建 / 22:51:50→22:51:51.5 重建 / 22:52:56（真实完成，此后不再重建）。
中间两次对应 `会话运行态：结束` 0.4–0.55s 后又 `在跑`（22:43:50.690→51.093、22:51:50.550→51.100），
即 **agent 一轮结束、下一轮立刻开始**——壳忠实跟随（运行集一瞬为空 ⇒ 撤回 ⇒ 又非空 ⇒ 重建），
与 §3.4 修掉的"**假**空更新"不是同一根因，但**用户看到的现象一样**（卡片消失再出现、`when` 归零）。
可选修法：撤回加 2–3s 去抖（窗口内新轮次开始就取消撤回），或"完成"与"新一轮"合并成同一条卡。

**⑥ 环境发现（写给下一次真机回归）**：回前台**不要用裸 `am start`**（`standard` 启动模式 + 隐式
`NEW_TASK` 会**新建** Activity 实例 ⇒ WebView 重建 ⇒ 交接文本里那条"页面重载 + KICKED"）。
用 `am start -n com.zcode.remote/.MainActivity -f 0x20000000`（SINGLE_TOP）：真机实测
`ActivityRecord{6985262}` 未变、`its current task has been brought to the front`、页面零重载。

**⑦ 163 真机复验（2026-09-17 23:02–23:05）：**通过****

场景同 ①（新任务 `#317592` / `sess_a24d1419`，`工作区相位 … running×1`，页面停概览页，
23:02:44 HOME，会话帧已静默 34s）：

| 时刻 | 日志 |
| --- | --- |
| 23:03:15.003 | `候选提前接管（页面最近 66s 前 收到会话帧 · 1 个在跑任务），观察 10s 后动手` |
| 23:03:25.005 | `**提前接管**` |
| 23:03:25.406 | `★接管配对成功（matched）` + `页面连接被顶掉（relay 返回 KICKED，应用在后台）` |
| 23:03:25.577 | `承载发现[bootstrap]：87 个任务 · 有活动 1 · running×1…` |
| 23:03:27.614 | `bridge ready … (zcshell-bridge-mu5nt8vw-xjf3qm)` ← **本次接管只此一座桥** |
| 23:03:35.052 | `后台原生承载：提前接管暂不交还（页面最近 86s 前收到会话帧 · socket=1 paired=true）——age<35s 只说明收到过帧，不说明页面在跟会话` ← **新增的那一行，且只出现一次** |

**反面对照（④ 那三行）在本轮一次都没出现**：`页面链路已恢复…原生交还` **0 次**、
同拍 `**提前接管**` **0 次**、同一次接管内的第二座 `bridge ready` **0 次**；
承载期间 `活进展` 持续推进（`第四百四十六次→第四百五十一次回复`）。

期间 23:03:43 桌面端 `Tier2: 服务端 error code=DEVICE_OFFLINE` → EOF → 10s 重连
（23:03:57 `tier2 bridges disposed (重连重建)` → 23:04:00 重新配对 + 第二座 `bridge ready`）——
这是 README 未结项 4 的**既有现象**（桌面端 host 掉线），**不是抖动复现**，看日志时别混。
回前台（23:04:55）：`Tier2: 关闭（回前台交还）存活=90s` → `KICKED 自愈：1.5s 后重载页面（第 1/2 次）`
→ 23:04:57.979 `relay socket open (#1)`，**只重载一次**；后台存活检查 `2 分 10 秒 / 心跳 13 次 / 380 帧 / 配对 12 次 → 保活成立`。

**⑧ 仍未验（别当已验证）**：① §3.9 ⑥ 那批真机回归（12s 轮换一拍、失败只回收一座、设置页一眼）仍未做；
② ⑤ 的卡片去抖是**决策项**，未改；③ 除 `CarrierHandback` 外，§3.9 ⑤ 的"Kotlin 三段零测试覆盖"不变。

**本段验证**：`check_kotlin_structure.py` OK（37 文件）· `check_resources.py` OK（23 文件）·
JS `node --test` 96/96 · `:app:testReleaseUnitTest` **99 项全绿**（92 + `CarrierHandbackTest` 7）+ `:app:assembleRelease`（`1.0.0-local.163`）。

---

### 3.11 卡片生命周期重构 + 运行态第三条源（164→168，2026-09-18 上午）

**缘起（用户三句话）**：① "不能把流体云按'任务进行中'的嗅探作为创建依据，结束时把卡片标成已完成，
新一轮只刷新现存卡片、不重建吗？" ② "谁告诉你没跑任务？是你的代码没有正确接收状态列表"
③ "前端已暂停，卡片只是停止更新，状态依旧显示运行中"。三句话打中三个不同的缺口。

#### A. 卡片生命周期（164）

**旧形态**：`Notifier.syncRunningTasks` 一见任务离开运行集就 `manager.cancel(id)`；`notifyCompleted`
再发 **另一条 id**（`COMPLETION_CARD_BASE + hash`）的 15s「已完成」卡。轮次缝 0.4–0.55s 时：
撤卡 → 发完成卡 → 0.5s 后重建运行卡。真机 2026-09-17 22:51:50 的 dump 实证：**同一任务的
两张卡同时占着两个提升位**（`id=667882` 运行卡 + `id=980883` 完成卡，正文一模一样），
可听提示也白响一声。

**新形态**（用户拍板"留到用户清掉"）：

| 环节 | 做法 |
| --- | --- |
| 创建依据 | 不变（嗅探到进行中建卡）；**移除改成滞后**：`NotifyState.COMPLETION_HOLD_MS=3s` 观察窗，窗内又跑起来＝什么都没发生（轮次缝直接不算结束） |
| 结束 | **同一条记录改状态**：同一个 id、同一个渠道，标题变 `已完成 · 任务名`、正文冻结、chip 换「已完成」；`Notifier.syncRunningTasks` 跳过对它的 cancel |
| 新一轮 | 同 id `notify()` 覆盖回 `运行中`（`finishedCards.remove(id)`），卡片一秒都没消失 |
| 清掉 | 被提升的卡必须 `ongoing`（平台硬条件）⇒ 挂一个「知道了」动作（`CardDismissReceiver`，未导出）；**让位**给在跑的任务时降级为普通通知（`ongoing=false`）⇒ 那时可以照常滑动清除 |
| 提升位 | `PromotionPolicy.choose(running, finished)`：完成卡参与抢位但**永远排在在跑的任务之后** |
| 观察窗到期 | `Update.nextFlushAtMs` + `ShellRuntime.scheduleCompletionFlush`：任务结束后不再有新帧，完成事件只能靠这一拍送出来 |

**真机证据（09:40–09:41，168）**：`会话运行态：结束` → `完成卡原地改状态 id=667882（登录与保存凭据）
——不撤卡、不换 id`；dump 里 `android.title=已完成 · 登录与保存凭据`、`PROMOTED_ONGOING`、
`actions=1`；`提升集合变化: [#394010 #667882]`（在跑的那张 + 刚完工留着的这张并存）。

#### B. 运行态第三条源（165）：页面自己订的 `controller/tasks-index`

**问题现场**：页面显示 `7 个工作区 · 41 个任务`、任务正在 `C:\...\workspace\default` 里跑，
而壳的 store 只有 `E:\MiMo ASR Assistant_harmony：2 个任务 · completedSuccess×2` ⇒
`runningTaskRefs()` 空 ⇒ 卡片/接管/提前接管全无从谈起。用户的判词成立。

**根因**：壳的运行态只有两条腿——**页面正在跟随的那一个工作区**的 `sessions-index`
（`zcode-protocol.js` 的 `_observeOutboundListen` 按页面自己的 listen 注册被动跟随）+ 已订阅会话的
`turnHeader.state`（承载专属）。而 `RelayBridge.kt:919` 早就写着第三条源"**（未接）**页面自己订的
`controller/tasks-index` 帧 —— 纯被动解析"。

**接法**（零协议写入）：
`zcode-protocol.js` 在 `_observeInboundRpc` 里识别 `data.topic === 'controller/tasks-index'` →
`onControllerTasks({kind:'complete', frame})` → `inject.js` `post('controllertasks', wire)` →
`ShellRuntime.onPageControllerFrame` → **复用 Kotlin 那份带单测的 `ControllerTasksState`**（snapshot /
deltas / 缺口判定都不在 JS 里重写）→ `TaskStore.applyLiveTasks` → `applyUpdate`。
缺口**不重同步**（只读壳不写页面协议），页面自己的订阅很快会补快照。

**证据**：`页面运行态：89 个任务 · 在跑 1 个（C:\Users\wdn32\.zcode\workspace\default）` →
`提升集合变化: [#394010]`。JS 新增一条测试钉"运行态帧必须转给原生，且转发不需要我们知道那个工作区"。

#### C. 承载覆盖修正（166）：桥开错了工作区 ⇒ 正文只剩工作区名

**用户的追问**："能识别任务了，但卡片正文显示 `default`"。日志：
`Tier2: 只开页面当前工作区的桥：E:\MiMo ASR Assistant_harmony`，而同刻
`承载发现[bootstrap]：… running@C:\Users\wdn32\.zcode\workspace\default` ——
页面停在 MiMo，`onlyWorkspace` 就用 MiMo，而 `carrierCoverageTargets()` 里"有在跑任务的工作区"
被 `multi_ws_*`（**内存态，重启即丢**）挡着 ⇒ **没人订阅那条对话** ⇒ 正文落到工作区名兜底。

**修法**：`carrierCoverageTargets()` 里"有在跑任务的工作区"**不再受开关管**（那是卡片的本体，
不是多工作区实验）；开关只决定"页面工作区要不要也覆盖"。修后：
`承载覆盖目标（启动前）：1 座（page=E:\MiMo… · store 在跑 1 个）` →
`Tier2: 多工作区覆盖 1 座：C:\...\default` → `subscribed conversation for sess_a997aba1…` →
`活进展 …：第六十六次回复`，卡片正文 = `第六十八次回复，时间09:28:55`（`when` 09:28:58 / 系统 09:29:08，同刻对齐）。

#### D. 运行态判定：固定优先级 → "谁后到谁算数"（167）

**用户的第三句**："前端已暂停，卡片只是停止更新，状态依旧显示运行中"。日志给出完整链条：

```
09:29:36.182  工作区相位 C:\...\default：29 个任务 · completedInterrupted×2 / completedSuccess×27   ← SI 当场报了结束
                                        （桌面端 DB 同刻写入 completed）
```

而 `TaskStore.phaseOverlay` 是**固定优先级** `controller > 会话流 > SI`：两条"活跃"覆盖层
都还停在 `running`（controller 那份是页面被顶掉前的最后一份快照，从此再也不会更新；会话流那份
是暂停前一帧）⇒ **新到的终态被旧的 running 盖住**，卡片永远"运行中"。

**修法**：三条源各记**报到时刻**（`livePhasesAt` / `conversationPhasesAt` / `siPhasesAt`），
`phaseOverlay` 改成"**最新报到的那条说了算**"（SI 的相位在 base 里，返回 null 即用它）。配套两条：
- `applyConversationRunState` **相位没变也盖时刻**（"这条源还在说话"）；
- `applyLivePreview`（正文在流）给会话流那份**续期**——否则"正跑着但相位从没变过"的会话会被
  一份新到的持久态报告判成已结束（那会把覆盖层存在的理由整个抹掉）。
- 顺带修一个真 bug：`applyWorkspace/applyLiveTasks/applyConversationRunState` 收 `nowMs`
  但调 `recompute()` 时**没往下传** ⇒ 时间戳与观察窗用了**两个钟**（测试里直接暴露；生产里
  恰好都是真实时钟才没发作）。现在 `nowMs` 一路贯穿。

**测试钉子**：`a newer sessions-index snapshot beats stale live sources`（09:29:36 现场）、
`a newer live report beats the persisted phase`（覆盖层存在的理由不许被这次修改抹掉）。

#### E. 一条误导日志（168）

`ShellRuntime.flushOngoing` 在通知层决定"撤不撤"之前就打 `卡片撤回 id=…`，而完成态那张卡其实
**不撤**（原地改状态）——真机 09:40 读到"卡片撤回 id=667882"与"完成卡原地改状态 id=667882"相邻两行。
现在 `flushOngoing` 只报事实（`运行集移除 id=…`），"卡片撤回"由真正执行 cancel 的 `Notifier` 打。

#### F. 正文"半截词"（169）

**用户现场**：卡片上先出现 `第八十六`，顿一下之后才补成 `第八十六次回复，时间09:56:26`。
日志同刻给出机制：`09:56:39.429 活进展 …：第五百三十`（**半截词**）→ 46ms 后 → `第五百三十四次回复，时间09:56:36`。
agent 的正文是**分块**到达的，而通知更新本身有 900ms 节流（`ONGOING_THROTTLE_MS`）——
那半截词（恰好落在节流窗的起点）在卡片上停了将近一秒。

**修法**：`core/PreviewSettle.kt`（纯函数 + 4 条单测）——正文推送前先等这份文本**安静 250ms**，
但距上次推送**不超过 900ms**（连续流式输出时靠它兜底，卡片不会"卡住不动"）。
`pushLivePreview`（承载那条路）与 `onConversationText`（页面那条路）都走同一个静默窗；
交还时把待推的正文一并丢掉。

**客观核对**（整份日志 57 条正文推送）："某条正文是下一条的严格前缀且间隔 <1.5s"的事件共 **5 次**，
全部在 169 之前（09:56:13 / 09:56:28 / **09:56:39**（用户报的那次）/ 09:57:44 / 09:58:37）；
169 装机后 9 条推送 **0 次**。

#### G. 承载的周期性覆盖刷新（170）

**边界**（§3.11 F 第 1 条）：页面被顶掉后第三条源就停了，而发现链只在**配对那一刻**跑一次
⇒ 承载期间在"没被覆盖的工作区"里新起任务，壳不知道。用户拍板补上。
`Tier2Probe.startCoverageRefresh`：每 **60s** 重问一次 `workspace-list-request`
（响应里的 `tasks` 顺带刷新 `discoveredActiveWorkspaces`），把**新出现的活动工作区**补开桥。
三条纪律：只补不拆 · 受 `BRIDGE_FAIL_COOLDOWN_MS`（10 分钟）失败冷却约束 ·
受 `BridgeManager.remainingCoverageSlots()`（总上限 5）约束。每拍一行 debug 供取证：
真机 `10:10:05 Tier2: 覆盖刷新：已覆盖 2 座 · 发现链 2 座 · 待补 0 座`。

**未验**：**补开桥的正向路径**（需要承载期间在第三个工作区起一个任务；本轮只验到"每拍在跑、待补 0"）。

#### H. 12s 轮换复验（2026-09-18 09:54–09:56，170 之前一版）：**通过**

两个不同工作区各一个持续输出的任务（`C:\...\workspace\default` 与 `E:\Zcode_harmony`），
应用退后台 ⇒ `Tier2: 多工作区覆盖 2 座（上限 5）` + 两座 `bridge ready` +
`提升集合变化: [#462531 #394010]（运行 2 个）`。

| 判据 | 实测 |
| --- | --- |
| 每 12s 一拍、两座交替 | 09:54:44→09:56:20 共 8 次 `reanchor rotate`，**间隔 12.0s**（6/7 个间隔精确 12.0s），目标严格交替（E:\Zcode_harmony ↔ default） |
| 一轮耗时 ≤~9s | 每次 rotate → 下一座 `subscribed conversation` ≈ **4.7–5.3s** |
| 失败时只回收一座 | 全窗口 `reanchor failed` **1 次**、`整桥回收` **1 次**（09:54:24 那一次，只回收了 default） |
| 卡片与正文 | 两座桥各自 `活进展` 持续推进；两张卡 `PROMOTED_ONGOING` |

**一处需要知道的现象（不是 bug）**：09:54:56 → 09:55:20 有一个 **24s** 间隔，原因是那一拍用在了
**被服务那座桥自己的 resync** 上（`09:55:08 resyncConversationV4` + `reanchor conversation …`）——
"一轮最多一次握手"的不变式（§3.9 ③）在起作用：轮换让位给被服务桥的定期重锚。
最坏情况下某张卡的一次刷新会等到 ~24s，而不是 12s。

#### I. 仍未验 / 已知边界

- **覆盖刷新的正向路径已验**（2026-09-18 10:27–10:29，用 `coverage_ws:` 人为制造缺口）：
  `多工作区覆盖 1 座：…default` → 60s 后 `覆盖刷新：已覆盖 1 座 · 发现链 2 座 · 待补 1 座` →
  `覆盖刷新——新发现 1 座有活动任务的工作区，补开桥：E:\Zcode_harmony` → **1.2s 后 `bridge ready`** →
  下一拍 `已覆盖 2 座 · 待补 0 座`。顺带抓到并修掉一个**自己引入的**竞态：刷新落进轮换的"先摘除、关旧、再开新"窗口 ⇒ 重复开桥（`coveredKeys()` 现在含 `recycleInFlight`，
  `beginCoverage` 加"一个工作区一座桥"守卫，549249c）。
- **完成卡跨进程重启不持久化**（用户 2026-09-18 拍板"退化无所谓"）：`finishedCards` 是内存态，
  重启后 `adoptOrphanFinishedCards` 会把上次遗留的那张**认领成普通可滑除通知**，但不再记得它的任务。
- **前台 + 页面停在概览页**时，卡片正文仍可能只有工作区名（用户拍板不改）：前台是只读壳、
  页面自己没在跟那条对话，而承载按设计只在后台跑。**回桌面/熄屏后 ~40s 内会补上**（实测 42–44s，
  判据见 §3.10）；熄屏也算"离开"（`userIsAway()`）。
- 三条源**不能合并成一条**：controller 全局但骑在**页面的 socket** 上（接管即死，原生自己订它会让
  桌面端 host 崩）、SI 两条路都在但**按工作区订阅**（覆盖窄）、会话流最准但**只在承载里**
  （且只覆盖订阅的那几条）。正确形态就是本次这种"多路冗余 + 一条判定"。
- 12s 轮换里的 **24s 例外拍**（被服务桥的定期重锚优先）是既定不变式的副作用，未改。

#### J. 卡片数量上限取消（172）

用户一问："你为啥要设置硬上限啊？"——`PromotionPolicy.MAX_PROMOTED` 曾经是 2，依据两条：官方 UX 话术
（实时更新是给"用户正在跟的一两个活动"用的）+ 我们自己笔记里的**事实性错误**"ColorOS 只并发提升 2 张
流体云卡"。后者被现场推翻：三个任务同时跑时手机**三张卡同时正常显示**，`dumpsys notification` 里是
**三条 `PROMOTED_ONGOING`**（id=462531/394010/234804），而当时我们只请求了 2 个 ⇒ **系统自己也会多提**；
本地官方资料（`ColorOS_docs/06-…`）里也没有任何"每应用最多几张"的数字。

**结论：2 是我们自己拍的门，不是平台的墙。** 一个执行不了的上限只会让"日志说 2 个、现场 3 个"，
还把在跑的任务挡在芯片位外。现在：在跑的任务**有几个提升几个**（天然被覆盖上限 5 与实际任务数约束），
界限只加在"留到用户清掉"的完成卡上（`MAX_FINISHED_PROMOTED = 3`，超出者降级成可滑除通知，记录不丢），
且完成卡**永远排在在跑的任务之后**。真机 172：`提升集合变化: [#394010 #234804 #462531]（运行 3 个）`
与 dump 三条一致。

#### K. 中断 / 失败各有状态词（174/175）

用户："用户点击中断时呢？这个流体云并没有更新这个状态"。两处错：

1. `postFinishedCard` 的状态词**写死** `TaskStatus.COMPLETED`——不管终态是 `completedSuccess` 还是
   `completedInterrupted`，卡片一律宣布"已完成"。
2. **会话流会把"中断"降级**：`turnHeader.state` 只说"停了"（我们一律映射成 `completedSuccess`），
   而 167 之后是"谁后到谁算数"⇒ 它能把 SI 给的 `completedInterrupted` 盖掉。

修法：`TaskStatus` 增 `INTERRUPTED("已中断")` / `FAILED("已失败")`，`NotifyState.finishedStatusOf(phase)`
做映射（未知终态按"已完成"兜底）；`applyConversationRunState(running=false)` **保留 SI 已给出的终态相位**
（会话流只说"停了"，不说"怎么停的"）；日志带上原因（`完成卡原地改状态 id=… （已中断 · 任务名 · 相位 …）`）。
顺带修一个被单测揪出的判定 bug：`phaseOverlay` 里 SI 用了 `>=`，**同一毫秒内两条源都报到时 SI 会赢**，
改成"SI 只在**严格更新**时说了算"。

#### L. 后台卡片提速：45s → ≈7s（176）

用户："在后台状态下冒出来的流体云都得等 20s 体感"。三段叠加，逐条拆掉：

| 段 | 旧 | 新 | 依据 |
| --- | --- | --- | --- |
| 首拍等待 | 10s | **3s** | `PUMP_FIRST_TICK_MS`；第一拍本来就只采基线 + 判候选 |
| socket 守卫 | **20s** | 0（稳态） | 页面在**前台**期间开的 socket，退后台第一拍才第一次被看到，旧写法当成"刚刚在重拨"。现在 `-1`（未知）只采基线、不开静默窗；回前台清基线 |
| 候选观察窗 | 10s | 0（页面从没收到会话帧时） | `convAgo < 0` ＝ 这个页面生命周期内压根没在看任务，不存在"用户正在切页面"的瞬态 |

**实测佐证（旧路径）**：10:47:37 HOME → 10:48:09 候选（+30s = 20s 守卫 + 10s 首拍）→ 10:48:19 接管
（+10s 观察窗）→ 10:48:22 活进展 = **+45s**。新路径：`后台心跳泵已启动（首拍 3s）` → 首拍 `#1 次发令`
（实测 +3.0s）→ 无观察窗直接接管。

**已知代价**：页面**正在**看任务时（`convAgo ≥ 0`）仍然等 10s 观察窗——那是有意的（避开用户在切页面）。

**本段验证**：结构 40 文件 OK · 资源 23 文件 OK · JS **97/97** · Kotlin **111/111**（新增：观察窗 2、完成语义 2、第三条源 1、覆盖层时效 2、正文静默窗 4、终态状态词 1、中断不被降级 1；PromotionPolicyTest 重写为 8 条）。

---

## 4. 保留但已标注（有参考价值，别再当"活的"）

| 位置 | 为什么留 |
| --- | --- |
| `RelayBridge.kt` 的 controller **订阅链**（**4a 轮已整体删除**，本行只留取证，别再照它找代码） | **删除理由 + 历史取证**（原保留理由"协议 schema 与单测的依据"已作废）。**为什么当年必须封死**：这条"壳**自己订** `controller/tasks-index`"的流，会让**桌面端 host 进程当场 `uncaughtException` 并自毁**——原生桥对 `zcode-agent.onDynamicControllerFrame` 的那次 `rpc:listen`：`[rpc:listen] zcode-agent.onDynamicSessionsIndexFrame subscribed`（索引流没问题）→ `[rpc:listen] zcode-agent.onDynamicControllerFrame  FAIL {"name":"Error",…}` → `uncaughtException origin=uncaughtException` → `disposing host resources, reason=uncaughtException:uncaughtException` → `[task-realtime] unregistered host` + `host process (local-1) exited with code 1`；配对→崩溃的间隔**固定 4.3 秒**，01:05 / 01:13 / 01:16 三次复现，每次之后桌面端远程控制整体失效（所有 `workspace-bridge` 回 `desktop-disconnected`）、页面跟着 bootstrap 失败。**它在运行态源里原本提供什么**：controller 那条更精确的 `liveStatus`（判定规则是"**谁后到谁算数**"，时刻表 `TaskStore.livePhasesAt`，底下垫 `sessions-index` 持久态）。**现存两条**＝① 会话流 `turnHeader.state`（→ `TaskStore.applyConversationRunState`，按会话生效、只覆盖承载订过的工作区）② **页面自己订的同一条 `controller/tasks-index`**（`controllertasks` → `TaskStore.applyLiveTasks`，唯一**全局**的一条）⇒ 删掉壳自己那条**只损失精度、不丢全局可见性**。**4a 轮删除范围（3 个文件）**：`RelayBridge.kt` 的整条链（`subscribeControllerTasks` / `onControllerWire` / `resyncController` / `unsubscribeController` + 5 个私有字段 + 开关 `controllerStreamEnabled`）+ `BridgeSession.onLiveTasks` + `BridgeManager.liveTaskSink`；`Tier2Probe.kt` 的 `liveTaskSink` 属性与它在 `BridgeManager(...)` 的命名实参；`ShellRuntime.stopLiveProgressPolling` 的交还臂（该臂当时已是空操作——`Tier2Probe.liveTaskSink` 全仓唯一写入就是 `= null`）。同轮还删了 `RelayBridgeTest.kt` 里喂这条流的 **6 处**脚本臂（它们本来就永不触发）。⚠️ **若要重开该能力**：不是"把开关改 `true`"（**开关已不存在**），而要**重新实现订阅并先复验桌面端**——注意 §3.7 那条更正：channel 当年可能就打错了（`onDynamicControllerFrame` 属于 `window-controller`，我们发的是 `zcode-agent`），照旧写法再来一次就是再崩一次 host。**不在删除范围**：`ControllerTasksState` 与 `TaskStore.applyLiveTasks`（页面路径是活的，见下一行）。 |
| `TaskStore` 三级优先级的 controller 那一档 | **更正（2026-09-18）：不是恒空，它一直在写入。** 页面自己那条全局流就喂它：`inject.js:823` `post('controllertasks', …)` → `ShellRuntime.kt:1529/1538` → `onPageControllerFrame` → `:1631 store.applyLiveTasks`；`TaskStore.kt:249-251` 写 `livePhases`/`liveTitles`/`livePhasesAt`，`:281/289/294/309-311` 读。封死的**只是 Tier2/RelayBridge 侧那条订阅**（`controllerStreamEnabled=false`，`RelayBridge.kt`；该订阅**已于 4a 轮整体删除**，即上一行），不是这份覆盖层。**别删 `applyLiveTasks` 或这几张覆盖层表**（会话流那份同理）：它们是"什么在跑"唯一的全局来源——删了＝别的工作区里在跑的任务**彻底不可见**（`tools/inject.test.js:791` 的注释就是那次当场取证）、承载开错工作区时卡片正文**退化成工作区名**、前台也只剩"页面当前在听的那一个工作区"的 SI 相位（本文件 §3.11 B/C/D） |
| `RelayWire.METHOD_ROWS_RANGE` | 注释已标死路（原生侧永远超时） |
| `tier2_test` / `tier2_stop` / `tier2_takeover` | 实验件，接管语义复验要用 |
| `nudgePageRecovery` + `nudge_now` / `nudge_close` | 现在是它唯一的使用者；纯取证诊断（**更正：README「诊断指令全集」已记录它们**——`l1_test` / `nudge_now` / `nudge_close` 同一行；原文"README 未记录这两条指令，属已知"是错的） |
| `TaskStore.Workspace.source` / `.path` / `.identity` | **这三个是一族，要留一起留、要删一起删（不许两留一删）**：都是形状字段（与 wire 同构），Kotlin 侧没有消费读点——`source` 连兜底自读都没有；`path`/`identity` 只在 `applyWorkspace` 的"非空才覆盖"里自读（`TaskStore.kt:225-226`），而唯一的 `Workspace` 消费者 `WebAppBridge.config()` 只读 `.key`。`source` 不是占位符，是**来源枚举**：`"active"`＝本桥自己订阅来的（`zcode-protocol.js:1687`、`RelayBridge.kt:249`）、`"passive"`＝从页面自己的流旁观的（`zcode-protocol.js:2635`）、壳按 controller 整表补出的工作区写 `"controller"`（`TaskStore.kt:272`）；wire 上的 `source` 键**两侧都有单测钉着**（`RelayBridgeTest.kt:37`、`protocol.test.js:378`）。本轮未动 |

---

## 5. 刻意**没做**的部分（需要用户拍板 / 单独一轮）

> **4b（2026-09-18）更新**：本表原首行"注入层看门狗**整块**（布防/巡检/信标）"**已经做完**
> ⟹ 整行移出本表，改记在 §2.6（含实际删除范围、幸存量具与新接线）。
> 该行旧旁证里的那个 `inject.js:<行号>`（1s `setInterval`）——**该引用已随 §5c 一并移除**：
> 巡检本体与它的 boot 调用点都不存在了，行号无处可指，别再照它去找代码。

| 项 | 现状 | 为什么没顺手做 |
| --- | --- | --- |
| `zcode-protocol.js` 自订阅对话流 | 被 `CONVERSATION_SUBSCRIBE_ENABLED=false` 整体封死 | 大块 JS，需测试兜底；值得单独一轮 + 真机回归（原文"同上"指向的那一行——注入层看门狗整块——**已在 4b 落地**，见 §2.6） |
| `SessionsIndexState.resetState` 之外的 `storeGet/storeSet` 等 | 删掉刷新实现后可能只剩个别调用方 | 待 JS 整块清理时一并处理 |
| `.workbuddy-ai/` | 未跟踪、未忽略 | 是否入库取决于用户（它存的是本项目的记忆与技能） |
| 根目录未跟踪垃圾（16 个 `*.log`、`notif.txt` 950KB、`scratch/` 39M 等） | 全被 `android-shell/.gitignore` 命中，无入库风险 | 纯本机占空间，删不删由用户定 |

---

## 6. 验证（本轮全部通过）

```
python tools/check_kotlin_structure.py   → OK（40 文件）
python tools/check_resources.py          → OK（23 文件）
node --test tools/inject.test.js tools/protocol.test.js  → 97 项全绿（inject 45 · protocol 52）
./gradlew :app:testReleaseUnitTest       → BUILD SUCCESSFUL（Kotlin 单测 111 项 · 11 个测试类）
```

---

## 7. 与既有结论的关系

- **不推翻**任何已验收的结论：只读壳（要点 14）、原生承载（要点 16）全部保持原样。
  （原来与它们并列在这里的 `controllerStreamEnabled=false` 已于 **4a 轮随订阅实现整体删除**——
  封死这条流的结论不变，而且现在**没有代码可打开**了，取证见 §4。）
  **"流体云只提升 2 张卡"不在其中**——它已作废（§3.11 J）：硬上限 2026-09-18 被用户
  否决，`PromotionPolicy.kt:11` 写明**没有"总共几张"的上限**（在跑的任务有几个提几个），界限只加在
  留住的完成卡上（`MAX_FINISHED_PROMOTED = 3`，`PromotionPolicy.kt:34`）。
- **本文与 `docs/17` 配套**：§3 是 `docs/17` §7 设计草案的落地，E1/E2 判据见那里。
- **`docs/15` §4"必须补 window 控制面"仍然作废**（真凶是 controller 流那次 `rpc:listen`）。
