# TODO / 路线与决策台账

> 2026-11-04 起，`Zcode_android` 的主线是 **WebView 薄壳**；原「Rust 核心 + Kotlin/Compose 界面」
> 已归档到 `archive/native-compose/`，仅作参考与迁移来源。
> 同日完成两项审计：[3.17.1 锚点](android/3.17.1-anchor-audit.md)、
> [Rust 核心可用度](android/rust-carrier-assessment.md)；语言方向 **统一到 Kotlin**（Rust 转规格）。
> **承载计划与 3.17.1 修复方案已逐条拍板（§2、§3），可直接开工。**

## 1. 已敲定的薄壳要求（不可漂移）

1. **仓库形态**：薄壳独享 `Zcode_android`；壳工程在 `android/`；`applicationId = com.zcode.remote`；
   签名用壳自己的 keystore（`android/scratch/zcode-remote-release.p12`，别名 `zcode-remote`）。
2. **不做深度合并**：不做一个 App 内 Web/原生切换；原生 Compose 不参与构建、不分发。
3. **前台零干预**：页面持有唯一 relay 连接；原生只做只读观测（WebSocket hook + 帧旁路），
   不关页面 socket、不替页面重连、不在健康路径 reload。
4. **后台保活**：前台服务（`specialUse`）+ `PARTIAL_WAKE_LOCK` + `WifiLock` + 渲染优先级；
   每 10s 原生驱动一次页面心跳（`pair_status_query`）；页面仍持有连接。
5. **回前台**：立即交还/停止原生干预；健康路径不重载；只有页面进入终态（KICKED）或进程死亡后的
   冷启动才重载，且必须保留 `history.state.zcodeMobilePage='chat'` 与 relay `mobileViewState`。
6. **KICKED 不露脸**：原生 logo 罩在后台就盖上，新文档首个 logo/状态帧画出才撤；失败屏不允许闪现。
7. **文档**：自撰 Android 文档在 `docs/android/`；厂商文档本地-only + manifest；
   原生文档随归档；鸿蒙仓库不再包含任何 android-shell 痕迹。
8. **CI**：壳的 workflow 原样接管（JS 106 项 + Kotlin 单测 + APK + `android-pre` 滚动发布）；
   不带 Rust/NDK；版本源 `android/gradle.properties: zcodeBaseVersion`；资产 `zcode-remote.apk`。
9. **明确不做**：把 Rust 拉进构建链（语言统一到 Kotlin）；用 DOM 选择器复刻 UI；
   把页面当“纯渲染层”由原生喂数据。
10. **前台轻推保持现状**（2026-11-04 拍板）：**不**新增 `healthy/stuck/terminal` 三分类；
    保留现有合成 `online`/`pageshow` 轻推与「终态才重载」；不恢复“壳主动关 socket”那套。

## 2. 承载（Native Carrier）：计划已敲定

> 术语：原 `Tier2` → **原生承载（Native Carrier）**，实现类 `core/NativeRelayClient.kt`。

目标：后台接管**只在页面链路判死/后台保活需要时**启用；健康前台仍由页面持有，不引入第二条连接。

### 2.1 Rust 核心评估结论（2026-11-04，已完成）

- **认证**：Rust 已覆盖 3.17.1——`ws2?token=&mid=`（`connection_params.rs:518-548`）、token 模式
  `auth_init`（`relay_client.rs:556-590`）；legacy HMAC 仍兼容。壳的 Kotlin 客户端只有 legacy HMAC，
  这是它在 3.17.1 上不启动的**唯一硬缺口**。
- **数据面/配对/心跳**：Rust 齐备（bootstrap、开桥、分片 + CRC + ack、会话订阅、sessions-index，519 单测）；
  与 3.17.1 快照实测一致（心跳 5s±1、ack 15s）。
- **缺口**：多工作区**服务权/轮换**与**接管/交还/判死状态机** Rust 零实现——在 Kotlin
  （`RelayBridge`、`HandoffState`、`CarrierHandback`、`ShellRuntime`），可直接复用。
- **决定**：**不引入第二种语言/构建链**；按 Rust 实现（含 3.17.1 快照核对过的常量）移植进 Kotlin。

### 2.2 最小功能集（承载拔）

- [ ] 给 `NativeRelayClient` 补 **ws2/token 认证**（注入层移交页面实际使用的 URL + token；保留 legacy 回退）。
- [ ] **交还不变式**：停承载 → 确认 socket close → 才允许页面重载/重拨（见 2.3）。
- [ ] 判死：入站帧静默 ≥35s（3 个泵周期 + 余量），保留“页面刚动过 socket 等它 20s”护栏。
- [ ] **不得重开** `controller/tasks-index` 的原生订阅（曾致桌面端 host 崩溃；运行态第三条源只走页面转发）。
- [ ] `onRenderProcessGone`（纳入本拔，见 2.5）。

### 2.3 接管时机 / 交还顺序 / KICKED 归因（2026-11-04 拍板）

**触发时机**（策略层，不是默认行为）：

| 情形 | 动作 |
| --- | --- |
| 退后台 + **有在跑任务** | **0ms** 启动承载 dial |
| 退后台 + 无在跑任务 | 延迟 **30s**（仍无任务就不启动） |
| dial 之前回到前台 | **取消**，不踢页面 |
| 短切换保护 | 保留现有 200ms/500ms 档 |

> 已知代价（用户已接受）：有任务时每次退后台都会踢页面 ⇒ 回前台必经**一次受控重载**
> （logo 罩住、保留对话位置）。这是"后台通知实时性"的价钱。

**交还顺序**（回前台）：先停承载 → **等 `NativeRelayClient` 的 socket close 确认**
（状态机 `NATIVE_OWNED → RELEASED`）→ 再 `reload`；**不用固定延时**。
- [ ] 目标：回前台到 reload 开始 **<300ms**；同时**优化现有交还路径代码缩短耗时**（用户明确要求）。

**KICKED 归因**（用户自定义语义，替换旧的 `KICKED_HEAL_CAP=2` 计数）：

| KICKED 来源 | 判定依据 | 动作 |
| --- | --- | --- |
| **本壳后台承载造成的**（自我接管） | 落在承载 dial 窗口内（`carrierDialedAt` + 期望的 workspace/session） | **自动恢复**：页面遮罩**延伸**到新文档就绪；交还后重载回原任务 |
| **前台被其它对话/设备抢占** | 不在承载窗口内 | **不做恢复**：只记日志，页面按官方流程走（官方 kicked 文案/终态），不抢回来 |

> 依据：3.17.1 页面收到 KICKED 会先自愈一次（`kickedDelay()`：2–5s 重连，60s 内第二次才
> `session-conflict` 终态）。自我归因时页面自愈必然再被踢 ⇒ 壳应罩住等待并重载；
> 外部归因时页面自愈是官方设计，壳不得干预。

### 2.4 多工作区：先验证“能否并行”，不预设轮换

**已核实的事实**：Rust 能开多座桥，但**没有任何服务权/轮换策略**（全仓 0 命中）；
「桌面端同一时刻只服务一座桥、服务权归最近订阅成功者」是壳在 **3.15.3** 真机测得的行为
（出处 `docs/18` §3.3、`docs/android/31` §A）——3.17.1 是否仍如此**未验证**。

- [ ] **实验卡（并行订阅可行性）**——本实验决定实现形态，先做实验再写策略：
  - **做法**：承载配对成功后，立即为在跑任务所在工作区**全部开桥**（≤5）并**同时**订阅各桥会话；
    不引入 12s 轮换（观察窗 ≥10 分钟）。
  - **判据**：① 每座桥是否都能持续收到 snapshot/增量（按桥分别统计入站帧）；
    ② 是否出现 `rpc-transport-fault`；③ 日志/桌面端是否显示服务权在被抢占（后订阅把先订阅饿死）。
  - **分支**：若各桥持续收帧 ⇒ **改为全并行订阅**（删掉宏观轮换，只留按需重锚）；
    若确认单服务权 ⇒ 保留轮换，但把"先全开"作为首轮策略（先拿到全部工作区的首份快照），
    轮换拍保持 12s、只 resync 最饿的一座。
- [ ] 覆盖目标来源：3.16.1+ 的 bootstrap/workspace-list **不含 `tasks`**，运行工作区仍靠页面转发
  （`controller/tasks-index` 只走页面，不原生订阅）。

### 2.5 onRenderProcessGone（本拔内做）

- [ ] `onRenderProcessGone` 返回 `true`（阻止应用被杀）→ 盖上 logo 罩 → 重建 WebView，
      加载原 URL + 钉 `history.state` + relay `mobileViewState`；不闪失败页、不落冷启动。

### 2.6 验收（真机）

- [ ] 后台 30 分钟：页面/原生至少一侧持续收帧；通知不丢。
- [ ] 触发时机：退后台 0ms（有任务）接管；无任务 30s 不启动；5s 内切回不踢、不重载。
- [ ] 交还：回前台 <300ms 开始 reload；无二次 KICKED；罩到新文档就绪才撤。
- [ ] KICKED 归因：自我接管踢掉 → 自动恢复回原任务；外部抢占踢掉 → 壳**不动手**。
- [ ] 单控制端：任意时刻只有一条 `/ws`；无重复 bridge、无 `rpc-transport-fault`（并行实验除外）。

## 3. 3.17.1 适配清单

报告：[`android/3.17.1-anchor-audit.md`](android/3.17.1-anchor-audit.md)——77 条锚点：存活 73 / 需适配 2 /
消失 1 / 属性不可检索但行为存活 1；**3.15.3 的 37 条基线在 3.17.1 上 0 死**，4 处换落点。

### 3.1 版本闸门（方案：三件套全上，2026-11-04 拍板）

页面 `Pir()`（主包 @6,925,965）比较 URL `app_version` 与 `desktopAppVersion`；不等时未就绪就
`location.replace()` 改 URL（**清空 `history.state`**），已就绪则弹「桌面版本已更新」modal 并
停止一切 relay 发送（`isBlocked()` 把壳的轻推门死 ⇒ 重载会变对话框循环）。

- [ ] ① **document-start 重钉 `history.state`**：闸门 replace 会抹掉 `pinChatRoute` 预设，重钉救回
      （需确认 `restoreChat` 为真，见报告 §4.1）。
- [ ] ② **让壳的 URL 带上 `app_version`**：学到 `desktopAppVersion` 后写进下次加载/重载的 URL（治本）；
      拿不到值时**不猜**，退回 ③。
- [ ] ③ **重载前探测** `dialog[aria-labelledby="remote-version-title"]`，命中即判「版本闸门阻断」，
      **不重载**、只记日志。
- [ ] 顺带：URL 白名单参数已含 `app_version`（`Xnr=new Set(['app_version','sid','mid','name'])`），
      加分参数不会破坏页面契约。

### 3.2 KICKED 语义

→ 已并入 §2.3（归因策略替换旧 `KICKED_HEAL_CAP=2` 计数）。

### 3.3 其余

- [ ] P1：`NARROW_QUERY` 同步新断点 `(max-width:767px) and (hover:none) and (pointer:coarse)`；
      `button[aria-haspopup="menu"]` 已是 Radix 运行时属性，建议加第二判据。
- [ ] 日志沉降：`window.zcode?.log` 在 /remote/v4 包内已消失（仅 officeFilePreview chunk 有），
      3.17.1 改用 `mobile-log` 家族帧——壳未实现，桌面端「拉取移动端日志」目前无应答者。
- [ ] 4 处换落点：`chat-error-banner`（收回主包）、i18n（`IntlProvider-CneXitqp.js`）、
      `controller/tasks-index`（`parts-Ba2kLaRj.js`）、3.17.1 已无 `src-*.js`。
- [x] `zcodeMobilePage` 存活、`reloadPage` 确认死参数（全仓 1 处定义、0 调用）——复核为真。

## 4. 迁移状态

| 项 | 状态 |
| --- | --- |
| 鸿蒙仓库清除 android-shell / README / 记忆 | ✅ 2026-11-04，commit `493fcd6` |
| 原生归档到 `archive/native-compose/`（tag `native-compose-final`） | ✅ commit `c37349e` |
| 壳工程导入 `android/`（快照导入） | ✅ commit `1231133` |
| Android 文档迁入 `docs/android/` | ✅ commit `6d239bb` |
| CI 换成壳的 workflow / release-notes | ✅ 已适配路径，待首次 CI 验证 |
| history patch 删除 | ✅ 代码 + CI 拉取步骤均已删除 |
| release 签名 / GitHub Secrets | ✅ 壳 keystore（`CN=ZCode Remote`）；4 个 `ANDROID_*` 已更新 |
| 本地构建/单测（改名后复跑） | ✅ JS 106/106；Kotlin debug 单测 + `assembleDebug` 通过 |
| 3.17.1 锚点审计 / Rust 可用度评估 | ✅ 2026-11-04，报告在 `docs/android/` |
| 术语更名（Tier2→原生承载、Tier1→页面侧） | ✅ 代码 + 文档全量替换，commit `dac579c` |
| 承载计划与 3.17.1 修复方案 | ✅ 2026-11-04 逐条拍板（本文件 §2、§3） |
| 首次 CI 运行 | ⏳ 需要推送 `main` 后验证 |

## 5. 已知风险 / 待办

- [ ] 承载与闸门修复前，3.17.1 上是「页面持有连接」的降级态：后台卡片覆盖依赖页面存活。
- [ ] Kotlin 客户端的 3.17.1 适配**未实测**（Rust 侧旁证更硬）；token 移植后必须真机验证一次。
- [ ] `docs/android/vendor`（含 miuix 约 22 MB）、`coloros`（88 MB）本地-only，换机器需手动拷贝；
      清单见 `docs/android/MANIFEST.md`。
- [ ] 签名材料三件套在 `android/scratch/`（p12 + 口令 + base64），备份丢失即无法覆盖安装。
- [ ] 未验证前提：扫码 URL 是否自带 `app_version`（决定闸门是否必然触发；方案已按"不带"设计，不受阻）。
