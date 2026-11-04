# 上机前审查（第二轮·注入层修复本身）— 2026-11-05

- **范围**：`git diff 6cdda43..HEAD -- android/app/src/main/assets android/tools`（提交 `edfe1a8`、`21c7a76`）所引入修复的独立复审；为核对口径连带读了 `ShellRuntime.kt` / `MainActivity.kt` / `WebAppBridge.kt` 的对应消费端。
- **方法**：通读 diff 全部 hunk + inject.js 全文（4184 行）+ 原生消费点；JS 测试实跑（115/115 通过）；Kotlin 测试未复跑（避免构建产物写入，沿用 `edfe1a8` 提交说明的 180/180）。
- **结论**：P0×0、P1×0、P2×5。修复本身方向与实现均成立，P2 全部是日志真值/记账/死状态层面的打磨项，无用户可见行为回归。

---

## P0

无。

## P1

无。

## P2

### P2-A 承载归因窗口不跨文档存活：承载运行中页面重载后，新文档把自我 KICK 记成"外部抢占"（日志失真，行为不受影响）

- **位置**：`android/app/src/main/assets/inject.js:1339-1357`（`kickedByOwnCarrier`，`carrierWindow.at<=0` 直接判外）、`android/app/src/main/java/com/zcode/remote/ShellRuntime.kt:2034`（`openCarrierWindow` 全仓唯一调用点在 `startNativeCarrier`，:1992）
- **触发**：自我接管链（后台 KICK → 原生盖罩 + `requestPageReload` 重载）后承载仍在跑，新文档自身再被踢（承载重拨/重试夺回控制权）——新文档里窗口恒为 `at=0`，注入层日志记"外部抢占，壳不动手"，而原生 `kickedIsSelfAttributed()`（`ShellRuntime.kt:1315-1327`）判自我接管并照常盖罩。
- **证据**：`onPageStarted`（`ShellRuntime.kt:2842-2852`）只清 `versionGateBlocked`，不重开窗口；两套判据其余分叉来源同族——拨号瞬间 `pageWorkspaceKey` 与注入侧 `lastPageWorkspace` 的竞态（同为 pagews 流的镜像，错拍才分叉）、双侧同时被踢时页面 KICK 先于原生关窗 evaluateJs 落地、注入侧用墙钟 `Date.now()` 对原生 `System.currentTimeMillis()`（墙钟跳变影响 60s 判定，原生用 `elapsedRealtime`）。
- **影响**：仅注入层日志（`noteRelayKicked` 只写 diag，inject.js:1429-1442）；盖罩/重载行为完全由原生侧判据决定（`ShellRuntime.kt:2492`）。归因日志正是这套机制的产出物，最需要它的现场（承载中重载后的第二次 KICK）反而失真。
- **建议修法**：原生在 `onPageStarted` 时若 `NativeRelayClient.isRunning()` 则补发一次 `openCarrierWindow()`（一行 evaluateJs）；或在 `noteRelayKicked` 日志里注明"跨文档窗口不可见，归因以原生为准"。

### P2-B 档 3 恢复被闸门③拒绝时，原生的恢复记账已先行消费（闸门阻断期烧恢复预算）

- **位置**：`android/app/src/main/assets/inject.js:1862-1866`（`__zcodeShellRecoverConversation` 闸门③守卫，返回 false）；`android/app/src/main/java/com/zcode/remote/ShellRuntime.kt:529-533`（`recordConversationRecovery(reason)` 在 `evaluateJs` 之前执行，evaluateJs 丢弃返回值）
- **触发**：版本闸门 modal 阻断中 + 回前台复判 `chat=false`（modal 正好顶掉对话视图，S3 形状成立）→ 原生记账 +1 并调用恢复 → 注入层拒绝（不重载、不碰 history.state）。连续两次回前台都撞闸门即烧光 `ConversationRecoveryPolicy.CAP=2`（`core/ConversationRecoveryPolicy.kt:47`）。
- **证据**：原生在档 3 路径上没有 `versionGateBlocked` 检查（该位只在 `requestPageReload`，:1944 被消费）；计数清零条件是"快照 chat=true"而非配对成功（`ConversationRecoveryPolicy.kt:23-33`），modal 挡着时永远不会成立——但用户点 modal 自己的"重新加载"落回 chat 后即复位，所以有界。
- **影响**：闸门解除后若 S3 再现，自动恢复可能被"已连续恢复 2 次"拒绝，退化成用户手动点进任务（正是策略设计的兜底形态）；危害是记账把"没发生的重载"也算成了"发生了"。
- **建议修法**：原生档 3 路径在 `recordConversationRecovery` 前加 `if (versionGateBlocked) return`（与 `requestPageReload` 同口径），一行守卫。

### P2-C 死代码删除后的只写残留：`foregroundEpoch` 与 `carrierWindow.session` 已零读者

- **位置**：`inject.js:1249`（声明）、`inject.js:3077`（唯一写点，`foregroundEpoch += 1`）——`__zcodeShellAfterCarrierReturn` 删除后全仓无读者（grep 仅此两行）；`inject.js:1318`（`carrierWindow.session` 赋值后无任何读取，`kickedByOwnCarrier` 只读 `.at`/`.workspace`）
- **触发**：静态状态，不触发。
- **证据**：`grep -n foregroundEpoch inject.js` = 1249/3077 两行；`carrierWindow.session` 的消费者在本 diff 改造的同一代码块里被核对为零（此残留早于本 diff，但就在本次改造的块内）。
- **影响**：与 docs/18 一贯的"只写不读即删"纪律相悖；误导后人以为存在跨前台窗口的 epoch 判据。
- **建议修法**：删除 `foregroundEpoch` 与其自增行；`carrierWindow.session` 要么删、要么让 `noteRelayKicked` 的日志带上它（本来就该有）。

### P2-D 死代码删除后的注释残留："三道回前台兜底"仍把 `afterCarrierReturn` 数作一员（4 处），另有一处旧机制名 `healKickedOnForeground`

- **位置**：`inject.js:2339`、`android/tools/inject.test.js:2159`、`android/app/src/main/java/com/zcode/remote/ShellRuntime.kt:236`、`ShellRuntime.kt:510-511`（四处"KICKED 自愈 / healDeadLinkOnResume / afterCarrierReturn"三道并列）；`ShellRuntime.kt:1283`（"注入层那条 KICKED 自愈（`healKickedOnForeground`）"——该名已随归因语义改造作废，`45758dc` 只同步了注入层注释）
- **触发**：静态。
- **证据**：`grep -rn AfterCarrierReturn android/`（排除 .md）仅命中注释，无调用点；`healKickedOnForeground` 全仓零定义。
- **影响**：文档指向已删除的函数，按注释找"第三道兜底"会扑空。
- **建议修法**：四处"三道"改"两道"并删去第三个名字；`ShellRuntime.kt:1283` 改述为现行的归因语义（KICKED 帧由页面看见 + 原生归因）。

### P2-E 原生焚 pin 先于导航结果：外部链接 `startActivity` 失败路径白焚一次标记

- **位置**：`android/app/src/main/java/com/zcode/remote/MainActivity.kt:346-357`（外域分支先 `evaluateJavascript(removeItem)` 再 `startActivity`，异常时 `return true` 吞掉）
- **触发**：用户在对话页（pin='1' 存活）点外域链接 → `ActivityNotFoundException` → WebView 实际没有导航，pin 已被焚；之后同一文档若发生闸门 replace，新文档 `pinnedBefore()=false` 且 `restoreChat=false`（一次性已消费）→ 用户落回首页而非对话。
- **证据**：`shouldOverrideUrlLoading` 外域分支的 removeItem 无条件执行；trusted-but-not-allowed 分支（登录）导航必然发生（return false），不受影响。
- **影响**：窄触发（外链点击失败 + 之后恰逢闸门 replace）、后果轻（少一次自动回到对话）。方向正确（离开远程页该焚），只是时机早于"导航真的发生"。
- **建议修法**：外域分支把焚毁移到 `startActivity` 成功之后；或改到 `onPageStarted` 里判定新 URL 非 `/remote` 时焚。

---

## 自查轮次记录

| 轮次 | 动作 | 新发现 |
|---|---|---|
| ① 主清单 | 按 6 个审查条目逐项核对（含通读 diff 全部 hunk、原生消费端） | 5（P2-A..P2-E） |
| ② 变体 grep | `ExpectCarrier/expect_carrier`、`zcodeShellPinChat/PIN_CHAT/pinnedBefore`、`versiongate/version_gate/versionGate`、`AfterCarrierReturn`、`healKickedOnForeground`、`replaceState`、`desktopAppVersion/app_version/desktopVersion` 全库（js+kt）重扫 | +1 处位置（`ShellRuntime.kt:1283` 旧机制名，并入 P2-D） |
| ③ 反证+最坏时序 | 每个"没问题"结论做反证；每个 hunk 问最坏时序（详见下清单的"反证"栏） | 0（P2-A 补充"双侧同踢"子形态与墙钟子项，计入条目细化） |
| ④ 对照第一轮报告 | `pre-device-review-inject.md` P1-1..3/P2-1..7 逐项核对修复落地（P2-5 已在 Kotlin 侧以 safePage 修复、P2-7 留档有据、孤儿常量 `FALLBACK_RELOAD_GAP_MS/FALLBACK_STORE_AT` 已不存在）+ 凭证红线复查 | 0 |

轮③、轮④连续两轮零新发现，收尾。

## 核查后判定没问题（含反证）

1. **归因新语义（条目 1）**
   - `lastPageWorkspace` 只由页面自己的 `workspace-bridge-open` 出站帧更新（inject.js:1146-1154），换工作区跟随、登出/整页导航随文档销毁；误判面要求"原生 `pageWorkspaceKey` 与注入侧镜像分叉"，两者同源同帧，仅错拍可分叉 → 已并入 P2-A，不构成反向误判（信息缺失时两侧都退回时间判据，inject.js:1347-1350 的条件设计正确）。
   - 60s 上界与原生 5s 宽限关窗（`stampCarrierStopped` → `closeCarrierWindowAfter(5000)`，ShellRuntime.kt:2065-2068）口径一致；60s 只是进程被杀兜底 ✔。
   - `__zcodeShellExpectCarrier` 暴露面：页面伪造只能改注入层日志，原生归因不读它（注释 inject.js:1323-1325 与事实相符）✔。
2. **pin 用完即焚（条目 2）**
   - "先钉后记"两个分支的 `storeSet` 都在 `pinChatRoute()` 之后、同一 try 内（inject.js:267-269、282-283），replaceState 抛错不留标记 ✔（较修复前"先记后钉"严格更优）。
   - "原生焚了、文档又写回"不存在：'1' 的唯一写入点是新文档 document-start（严格晚于旧文档上的原生 removeItem），旧文档的 `noteChatViewEntered` 只会写 ''（inject.js:3447）✔。
   - 焚烧竞态里谁后写谁赢：三个写点值相容（''与 remove 等价），不存在值复活路径 ✔。
3. **闸门③ `[open]` + 限频（条目 3）**
   - `showModal`/`close` 同步翻转 `open`；合并拍读到终态，边沿各报一帧，原生 `if (blocked == versionGateBlocked) return` 去重（ShellRuntime.kt:2464）；新文档两侧一致复位（原生 `onPageStarted` :2848、注入 `versionGateProbedAt=0` 使首拍必探测）✔。
   - "已阻断时每拍扫描"的注释论据（DOM 基本不动）不完全严谨——闸门只门死发送侧，入站帧仍可驱动 DOM——但探测频率与修复前（每拍无条件扫）持平，严格无回归；CSS 动画不经 attributeFilter（class/style），不触发 observer ✔。
   - 限频 1s 对交还决策：交还读原生缓存位（:1944），modal 出现 <1s 内交还最多多一次无效重载；收敛有硬保证——三处 `loadUrl` 全部过 `DesktopVersion.withAppVersion`（MainActivity.kt:636/737/744），学到版本后的重载直接消除闸门 ✔。
4. **desktopversion 去重（条目 4）**：`lastReportedDesktopVersion` 每文档重置 → 重连/刷新后必重报一次，原生 `learn` 等值短路幂等（DesktopVersion.kt:30）；`learned` 进程内存、随进程与 WebView 同生命周期，无"原生忘了、文档记得"的漏报组合；同文档内版本变化照发 ✔。
5. **死代码删除连带（条目 5）**：`RESUME_HEAL_MIN_GAP_MS`/`lastResumeHealAt` 仍由 `healDeadLinkOnResume` 消费（inject.js:1236-1237、1405-1420）、`sockets` 由 describeRelay/injectPayload/sendControlFrame 消费 ✔；删除的 4 条测试所覆盖的行为由原生 close-confirm 后的必然重载 + 既有 deadlink 测试（inject.test.js:642-701）承接，未发现无覆盖回归 ✔（孤儿变量见 P2-C）。
6. **只读契约（条目 6）**：`location.reload()` 全仓 3 处——`healDeadLinkOnResume`（:1422，三判据+闸门③+限流，且闸门拒绝不烧限流时间戳 :1411 先于 :1416）、`__zcodeShellRecoverConversation`（:1872，闸门③+原生跨进程预算）、刷新按钮（:4135，用户显式点击）；`history.replaceState` 仅 `pinChatRoute` 一处（:245）；本轮新增路径全部是 post/读（`versiongate` :3382、`desktopversion` :1178、pin 的 sessionStorage 读写）；socket 写入面无新增（`sendControlFrame` 后台补心跳、诊断指令为既有政策内写入）；桥入口不落原始消息日志（WebAppBridge.kt:22-25），`ready` 帧的 href 原生只取 documentId 且日志已脱敏为 path（ShellRuntime.kt ready 分支）✔。

## 未验证项

- 承载运行中页面重载后再被踢的实际频率（P2-A 的触发面）需真机日志佐证；本报告只证明日志失真路径存在。
- Kotlin 测试套件本轮未复跑（构建产物写入约束），以 `edfe1a8` 提交说明的 180/180 为准。
