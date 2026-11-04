# TODO / 路线与决策台账

> 2026-11-04 起，`Zcode_android` 的主线是 **WebView 薄壳**；原「Rust 核心 + Kotlin/Compose 界面」
> 已归档到 `archive/native-compose/`，仅作参考与迁移来源。
> 同日完成两项审计：[3.17.1 锚点](android/3.17.1-anchor-audit.md)、
> [Rust 核心可用度](android/rust-carrier-assessment.md)；语言方向已拍板 **统一到 Kotlin**（Rust 转规格）。

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

## 2. 承载（Native Carrier）：统一到 Kotlin

> 术语：原 `Tier2` → **原生承载（Native Carrier）**，实现类 `core/NativeRelayClient.kt`（2026-11-04 全量更名）。
> 目标不变：后台接管**只在页面链路判死时**启用；健康路径仍由页面持有，不引入第二条连接。

### 2.1 Rust 核心评估结论（2026-11-04，已完成）

- **认证**：Rust 已覆盖 3.17.1——`ws2?token=&mid=`（`connection_params.rs:518-548`）、token 模式
  `auth_init`（`relay_client.rs:556-590`）；legacy HMAC 仍兼容。壳的 Kotlin 客户端只有 legacy HMAC，
  这是它在 3.17.1 上不启动的**唯一硬缺口**。
- **数据面/配对/心跳**：Rust 齐备（bootstrap、开桥、分片 + CRC + ack、会话订阅、sessions-index，519 单测）；
  与 3.17.1 快照实测一致（心跳 5s±1、ack 15s）。
- **缺口**：多工作区**服务权/轮换策略**与**接管/交还/判死状态机** Rust 零实现——这两块在 Kotlin
  （`RelayBridge`、`HandoffState`、`CarrierHandback`、`ShellRuntime`），可直接复用。
- **决定**：目标只有 Android 一个原生消费者 ⇒ **不引入第二种语言/构建链**；
  按 Rust 实现（连同 3.17.1 快照核对过的常量）移植进 Kotlin；Rust 源码留在归档当规格与测试基准。

### 2.2 最小功能集（第一步）

- [ ] 给 `NativeRelayClient` 补 **ws2/token 认证**（注入层移交页面实际使用的 URL + token；保留 legacy 回退）。
- [ ] 修 **KICKED 语义**：3.17.1 页面会在 2–5s 内自愈第一次，60s 内第二次才是终态 `session-conflict`；
      现有 `KICKED_HEAL_CAP=2`（旧假设「第二次就重载」）阈值撞车，必须重定——页面自愈窗内不自作主张。
- [ ] **交还不变式**：停承载 → 确认 socket close → 才允许页面重载/重拨（否则双向互顶到终态死页）。
- [ ] 判死：入站帧静默 ≥35s（3 个泵周期 + 余量），保留“页面刚动过 socket 等它 20s”护栏。
- [ ] **不得重开** `controller/tasks-index` 的原生订阅（曾致桌面端 host 崩溃；运行态第三条源只走页面转发）。

### 2.3 验收（真机）

- [ ] 后台 30 分钟：页面/原生至少一侧持续收帧；通知不丢。
- [ ] 健康路径：退后台 10 分钟回前台 **0 重载**、对话位置不丢。
- [ ] 故障路径：页面 socket 判死 → 原生接管 → 回前台交还 → KICKED 罩住 → 重载后回到原任务。
- [ ] 快速切应用（<5s）不触发接管、不 KICK、不重载。
- [ ] 单控制端：任意时刻只有一条 `/ws`；无重复 bridge、无 `rpc-transport-fault`。

## 3. 3.17.1 适配清单（锚点审计已完成）

报告：[`android/3.17.1-anchor-audit.md`](android/3.17.1-anchor-audit.md)——77 条锚点：存活 73 / 需适配 2 /
消失 1 / 属性不可检索但行为存活 1；**3.15.3 的 37 条基线在 3.17.1 上 0 死**，4 处换落点。

- [ ] **P0 版本闸门**：页面 `Pir()`（主包 @6,925,965）比较 URL `app_version` 与 `desktopAppVersion`；
      不等时未就绪就 `location.replace()` 自行改 URL（**清空 `history.state`，抹掉壳的 `pinChatRoute`**），
      已就绪则弹「桌面版本已更新」modal 并停止一切 relay 发送。壳从不写 `app_version`
      ⇒ 若扫码 URL 不带该参数，闸门必然触发；且 `isBlocked()` 会把壳的轻推（合成 `online`）门死，
      重载只会变成对话框循环。**先实测扫码 URL 是否自带 `app_version`**（未验证），
      再定方案：自己补参数 ／ 重载前探测 `dialog[aria-labelledby="remote-version-title"]` 判阻断不重载。
- [ ] **P0 KICKED 语义**（见 §2.2）。
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
| 3.17.1 锚点审计 | ✅ 2026-11-04，报告 `docs/android/3.17.1-anchor-audit.md` |
| Rust 核心可用度评估 | ✅ 2026-11-04，报告 `docs/android/rust-carrier-assessment.md`；结论「需补」，语言统一到 Kotlin |
| 术语更名（Tier2→原生承载、Tier1→页面侧） | ✅ 代码 + 文档全量替换（commit 见下） |
| 首次 CI 运行 | ⏳ 需要推送 `main` 后验证 |

## 5. 已知风险 / 待办

- [ ] `onRenderProcessGone` 尚未实现（渲染进程被杀会崩溃/冷启动）。
- [ ] P0（版本闸门、KICKED 语义）修复前，壳在 3.17.1 上处于「页面持有连接」的降级态：
      后台卡片覆盖依赖页面存活，原生承载不启动。
- [ ] `docs/android/vendor`（含 miuix 约 22 MB）、`coloros`（88 MB）本地-only，换机器需手动拷贝；
      清单见 `docs/android/MANIFEST.md`。
- [ ] 签名材料三件套在 `android/scratch/`（p12 + 口令 + base64），备份丢失即无法覆盖安装。
- [ ] 承载方案的真机验证未做（Kotlin 客户端的 3.17.1 适配未实测；Rust 侧旁证更硬）。
