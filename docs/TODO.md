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

- [x] 给 `NativeRelayClient` 补 **ws2/token 认证**（注入层移交页面实际使用的 URL + token；保留 legacy 回退）。
      → 2026-11-04：`RelayCreds.authToken` + `tokenMode`；`auth_init` 无 `role`、`client_ts` 用秒；
      `auth_challenge` 在 token 模式忽略；`auth_ack` 校验 `device_sid`；心跳 10s → **5s**。
      `inject.js` 的凭证移交不再要求 `hash`（3.17.1 链接没有它）。
- [x] **交还不变式**：停承载 → 确认 socket close → 才允许页面重载/重拨（见 2.3）。
      → `NativeRelayClient.stopAndAwaitClose()`（local cancel 的 `onClosed/onFailure` 即确认，
      250ms 超时放行）。
- [ ] 判死：入站帧静默 ≥35s（3 个泵周期 + 余量），保留“页面刚动过 socket 等它 20s”护栏。
- [ ] **不得重开** `controller/tasks-index` 的原生订阅（曾致桌面端 host 崩溃；运行态第三条源只走页面转发）。
- [x] `onRenderProcessGone`（纳入本拔，见 2.5）。

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
- [x] 目标：回前台到 reload 开始 **<300ms**；同时**优化现有交还路径代码缩短耗时**（用户明确要求）。
      → 2026-11-04：`stopAndAwaitClose` 把 reload 挂在关闭确认上（超时 250ms 放行），
      并打一行「socket 已确认关闭/超时放行，等待 Nms」——**真机计时待验收**。

**KICKED 归因**（用户自定义语义，替换旧的 `KICKED_HEAL_CAP=2` 计数）：

| KICKED 来源 | 判定依据 | 动作 |
| --- | --- | --- |
| **本壳后台承载造成的**（自我接管） | 落在承载 dial 窗口内（`carrierDialedAt` + 期望的 workspace/session） | **自动恢复**：**检测到 KICKED/终态即盖上加载中遮罩**（后台就盖、前台不露一秒），遮罩延伸到新文档就绪；交还后重载回原任务 |
| **前台被其它对话/设备抢占** | 不在承载窗口内 | **不做恢复**：只记日志，页面按官方流程走（官方 kicked 文案/终态），不抢回来（**不罩**） |

> 依据：3.17.1 页面收到 KICKED 会先自愈一次（`kickedDelay()`：2–5s 重连，60s 内第二次才
> `session-conflict` 终态）。自我归因时页面自愈必然再被踢 ⇒ 壳应罩住等待并重载；
> 外部归因时页面自愈是官方设计，壳不得干预。
> **新版 kicked 页面在自我归因路径上不得上屏**（2026-11-04 用户明确要求）。

> **2026-11-04 落地**：注入层删除 `KICKED_HEAL_CAP`/`KICKED_HEAL_STORE`/`healKickedOnForeground`
> （不再自己 reload）；改为 `__zcodeShellExpectCarrier(at, workspace, session)` 的拨号窗口
> （原生 `startNativeCarrier` 开、`clearCarrierState` 关，窗口 5 分钟硬上限），窗口内**只记日志**，
> 窗口外记「外部抢占，壳不动手」。原生 `pagefailure(kind=kicked)`：`carrierMayHaveKickedPage()`
> 为真 → `handoffCover.carrierMatched()` **即刻盖罩**且不再 `pageFailed` 撤罩。

### 2.4 多工作区：先验证“能否并行”，不预设轮换

**已核实的事实**：Rust 能开多座桥，但**没有任何服务权/轮换策略**（全仓 0 命中）；
「桌面端同一时刻只服务一座桥、服务权归最近订阅成功者」是壳在 **3.15.3** 真机测得的行为
（出处 `docs/18` §3.3、`docs/android/31` §A）——3.17.1 是否仍如此**未验证**。

- [ ] **实验卡（并行订阅可行性）**——本实验决定实现形态，先做实验再写策略：
      → **开关已就位（2026-11-04）**：`carrier_parallel_on` / `carrier_parallel_off`（adb / DiagReceiver）：
      开覆盖（page ∪ 在跑工作区，≤5）+ `BridgeManager.rotateEnabled=false`，每 12s 打一行
      `parallel coverage: N bridges · <工作区>=<收帧年龄>`。实验本身**待真机**（需桌面端有在跑任务）。
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

- [x] `onRenderProcessGone` 返回 `true`（阻止应用被杀）→ 盖上 logo 罩 → 重建 WebView，
      加载原 URL + 钉 `history.state` + relay `mobileViewState`；不闪失败页、不落冷启动。
      → 2026-11-04：`MainActivity.rebuildWebViewAfterRendererGone()` 移除并销毁旧实例、
      在根布局第 0 层插入新 WebView、重装 document-start 与桥后按原 URL（带 `app_version`）加载；
      罩走 `HandoffCoverState.rendererGone()`（15s 超时），新文档首个 logo/页面帧撤罩。
      `history.state` 由同一条 document-start 路径（`config().restoreChat`）钉。

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

- [x] ① **document-start 重钉 `history.state`**：闸门 replace 会抹掉 `pinChatRoute` 预设，重钉救回
      （需确认 `restoreChat` 为真，见报告 §4.1）。
      → 2026-11-04：改用 `sessionStorage['zcodeShellPinChat']` 记住"离开时在对话页"，
      不再依赖那一刻的 `config().restoreChat`（它可能已被消费掉）；页面回到首页时清标记。
- [x] ② **让壳的 URL 带上 `app_version`（装载时拼参数）**——**已定方案**（用户 2026-11-04 批准）：
      从页面 bootstrap 帧学到 `desktopAppVersion` 后，只在加载/重载时把它拼进请求 URL；
      **不写回 `Prefs`、不新增 URL 监听、拿不到值就不猜**。
      → `core/DesktopVersion`（内存单值 + 纯字符串拼接，已存在则替换）+ 注入层
      `bootstrap-response → post('desktopversion')`。
- [x] ③ **重载前探测** `dialog[aria-labelledby="remote-version-title"]`，命中即判「版本闸门阻断」，
      **不重载**、只记日志。
      → 注入层在页面状态那一拍探一次（变化才发 `versiongate`），原生 `requestPageReload`
      **同步**查位跳过；注入层自己的两处 `location.reload()` 也先查。
- [ ] 顺带：URL 白名单参数已含 `app_version`（`Xnr=new Set(['app_version','sid','mid','name'])`），
      加分参数不会破坏页面契约。

### 3.2 KICKED 语义

→ 已并入 §2.3（归因策略替换旧 `KICKED_HEAL_CAP=2` 计数）。

### 3.3 其余

- [x] P1：`NARROW_QUERY` 同步新断点 `(max-width:767px) and (hover:none) and (pointer:coarse)`；
      `button[aria-haspopup="menu"]` 已是 Radix 运行时属性，建议加第二判据。
      → 2026-11-04：两处都改；第二判据 = 同容器 `button[aria-label]` 命中主题文案
      （`选择主题` / `Switch theme`）。JS 测试 +1。
- [ ] 日志沉降：`window.zcode?.log` 在 /remote/v4 包内已消失（仅 officeFilePreview chunk 有），
      3.17.1 改用 `mobile-log` 家族帧——壳未实现，桌面端「拉取移动端日志」目前无应答者。
- [ ] 4 处换落点：`chat-error-banner`（收回主包）、i18n（`IntlProvider-CneXitqp.js`）、
      `controller/tasks-index`（`parts-Ba2kLaRj.js`）、3.17.1 已无 `src-*.js`。
- [x] `zcodeMobilePage` 存活、`reloadPage` 确认死参数（全仓 1 处定义、0 调用）——复核为真。

## 4. 登录页纳入壳内 + 状态栏新配色（2026-11-04 新增范围，方案照鸿蒙）

现状取证：`docs/android/login-page-shell-audit.md`（安卓侧）+ `docs/android/harmony-login-reference.md`（鸿蒙口径）。

- 已天然成立：`chat.z.ai/api/oauth/authorize`、`bigmodel.cn/login`、`zcode.z.ai/web-remote/callback`
  都在受信域内（`RemoteUrl.kt:37`）⇒ 留在同一 WebView、同一存储上下文；注入层被 `/remote` 路径闸挡
  （`MainActivity.kt:505`）、JS 桥摘除（`:311/332`）⇒ 登录页上壳不误动作。
- 唯一走不通：**白名单外的域**被外抛系统浏览器 ⇒ `sessionStorage` 里的 OAuth nonce 丢、回调必 CSRF 失败；
  且壳**没有深链 intent-filter**，出壳回不来。

决定（**照抄鸿蒙**，2026-11-04 拍板）：

- [x] **D1 可信域**：`z.ai` + `*.z.ai` + `bigmodel.cn` + `*.bigmodel.cn`（安卓现为两个根域 + 子域匹配，核对等价）。
      → 已等价；判定抽成纯函数 `RemoteUrl.isTrustedHost`，单测钉死（含 `evilz.ai` / `z.ai.evil.com` 反例）。
- [x] **D2 导航闸门**：只有「非可信域 + 主框架 + 非下载」三条同时成立才外抛系统浏览器；**子资源与下载一律放行**。
      → `RemoteUrl.isDownloadUrl`（后缀表）+ `shouldOverrideUrlLoading` 先判下载；主框架之外照旧放行。
- [x] **D3 登录判定只认 host + path 前缀**（不碰 Cookie、无登出清理、回调由网页在同 WebView 自洽）：
      `bigmodel.cn`（恒亮）、`chat.z.ai/auth/oauth/authorize`、`chat.z.ai/auth*`、`zcode.z.ai(/web-remote|/share|/cn/share)`。
      → `RemoteUrl.authSurfaceOf(host, path)`（返回 `AuthSurface` 枚举，纯字符串）。
- [x] **D4 登录页专用固定色表**（8 个 `auth-*` 状态名，随**网页**主题、不随系统深浅；无主题信号时 zcode 默认暗）：
      - `auth-bigmodel`（`*.bigmodel.cn`）：`#F7F8FA` **恒亮**
      - `auth-zai-consent-light/dark`（`chat.z.ai` + `/auth/oauth/authorize`）：`#F8F8F8` / `#161616`
      - `auth-zai-login-light`（`chat.z.ai` + `/auth*`）：`#F5F6F8`（窄屏 `max-width:512px` → `#FFFFFF`）
      - `auth-zai-login-dark`：`#141618`
      → `PageBarState` 扩到 12 项（4 页面态 + 8 登录态），登录态带 `fixedDark` 位、`resolve` 忽略
      `dark` 参数；`auth-zai-login-light-narrow` 单独一项（≤512dp 由 `screenWidthDp` 近似）。
- [x] **D5 先给初值防闪色**：`onPageBegin` 直接按 URL 给状态名与色值（不等注入层回推），再由页面回推纠正。
      → `MainActivity.onPageStartedSurface()`；登录页无注入层，初值即终值（已写进注释）。
- [x] **D6 boot / KICKED / 配对等待底色对齐鸿蒙表**：boot 未着主题 `#FAFAFA`/`#171717`；
      已着主题 / KICKED / 配对等待 `#F8F8F8`/`#161616`——按此核对并替换 `PageBarColor.kt:85-92`
      与 Android 资源里的 boot 色（替代"真机截图取色"的口径）。
      → `PageBarColor` 八个字面量核对**已与鸿蒙一致，未改值**；Android 资源侧新增
      `values/colors.xml` + `values-night/colors.xml`（`page_bar_boot`），根布局与交接罩改引用它
      （原先写死 `#FFF8F8F8`，深色冷启动会闪浅色）。
- [x] **D7 离开远程页全清**（用户拍板）：清 `relayCreds`（防死凭证拨号）+ 停承载 + `store.reset()`
      （运行态/通知一并复位），由"非 remote 页 / 登录页判定"触发。
      → `ShellRuntime.onLeftRemotePage()`，由 `MainActivity.onPageStarted` 在 `!isAllowed` 时调用。
- [x] **D8 图标明暗纯 bug**：`MainActivity.kt:692/695` 改用解析后的 `dark`（登录页 `pageTheme` 恒 null
      ⇒ 系统深色下"深图标压 `#171717`"）。
      → 解析收进 `PageBarColor.darkFor(state, theme, systemDark)`；`appearanceLightStatusBars(dark)` 只吃
      解析结果，颜色与图标同一个输入。
- [ ] **D9 路径闸收窄（待真机）**：先确认登录页 URL；若落在 `/remote` 前缀下（如 `/remote/v4/login`），
      注入的路径闸与 `isAllowed` 收窄为**精确白名单**，避免 `pinChatRoute`/WS hook/滚动条/刷新按钮在登录页生效。
- [x] **D10 刷新按钮对齐鸿蒙**（2026-11-04 完成）：宽屏锚点链——`header[data-testid="workspace-header"]`
      内最后一个菜单按钮（实测=帮助菜单，落在**右上角**）→ 缺席退 `side-pane-toggle` → `terminal-toggle`
      的 testid 直锚（原实现只锚 `section[data-mobile-page="chat"]`，宽屏/平板上按钮根本不出现）；
      登录/授权页不插（`isAuthSurface()`：bigmodel 全系 / `chat.z.ai/auth*` / zcode `/web-remote|/share|/cn/share`）；
      克隆 className 时剥掉 `!bg-selected` 打开态高亮。JS 测试 +3（109/109 通过）。
- **不照抄鸿蒙的坑**：`currentPageUrl`（写了不读）、`isRemoteControlPage()`（定义不调用）、失实注释；
  深链 manifest 只声明 `zcode.z.ai` 而代码放行 `*.z.ai` 的口径不一致（安卓直接对齐代码口径）。

**未验证前提**：登录全链路域是否真只有这四个（鸿蒙口径是代码口径、非实测清单）；
新登录页 URL 归属（在不在 `/remote` 前缀下）；鸿蒙色表是否已是线上最新 palette（真机阶段复核）。

## 5. 迁移状态

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
| 登录页/状态栏新增范围 | ✅ 方案照鸿蒙（本文件 §4；两份取证报告在 `docs/android/`） |
| 3.17.1 适配 + 承载修复（批次 0/1） | ✅ 2026-11-04 代码落地：JS 115/115、Kotlin 170/170、`assembleDebug` 通过；**真机验收见 §2.6** |
| 首次 CI 运行 | ⏳ 需要推送 `main` 后验证 |

## 6. 已知风险 / 待办

- [ ] 承载与闸门修复后、真机验收前，3.17.1 仍按「页面持有连接」验证：后台卡片覆盖依赖页面存活，
      承载新语义（0ms/30s/归因遮罩）未经真机检验前不作数。
- [ ] **批次 2 真机待量**：交还路径"close 确认"常态耗时 + 回前台到 reload 总时长 <300ms
      （超时上限已压到 **100ms**，`NativeRelayClient.CLOSE_CONFIRM_TIMEOUT_MS`，原 250ms——
      250ms 会让最坏情况贴穿预算；常态走 close 回调远快于超时，若实测常态 >100ms 再回调该值）。
- [x] **系统下载器已接**（2026-11-04）：`setDownloadListener` → `DownloadManager`
      （带 WebView cookie + UA，公共 Downloads；API ≤28 退应用私有目录），文件名/清洗/mime
      由纯逻辑 `core/DownloadPlan.kt` 决定（9 条单测）。剩余缺口：`MODE_SAVE`（网页请求"另存为"）
      仍未实现；`blob:`/`data:` 下载无法交给系统下载器（只记日志，页面内生成的文件需后续做 JS 侧兜底）。
- [ ] Kotlin 客户端的 3.17.1 适配**未实测**（Rust 侧旁证更硬）；token 移植后必须真机验证一次。
- [ ] `docs/android/vendor`（含 miuix 约 22 MB）、`coloros`（88 MB）本地-only，换机器需手动拷贝；
      清单见 `docs/android/MANIFEST.md`。
- [ ] 签名材料三件套在 `android/scratch/`（p12 + 口令 + base64），备份丢失即无法覆盖安装。
- [ ] 未验证前提：扫码 URL 是否自带 `app_version`（决定闸门是否必然触发；方案已按"不带"设计，不受阻）。

## 7. 上机前独立审查（2026-11-04，两路只读代理）

报告：`docs/android/pre-device-review-kotlin.md`（P0×1、P1×5、P2×9）、
`docs/android/pre-device-review-inject.md`（P0×0、P1×3、P2×7）。以下为**已修**与**留档**。

### 已修（含单测）

- [x] **P0 前台 KICK 罩永久滞留**：新增 `HandoffCoverState.foregroundRecovery()`——前台建罩时
      `returning=true`，由新文档首帧或超时撤罩；前台自我接管路径改为"建罩 + `requestPageReload`
      + 15s 超时"（旧写法 `carrierMatched(…, away=true)` 在前台把 `returning` 置假，罩永挂并吃触摸）。
- [x] **P1 归因窗口漏关**：新增 `stopCarrierAndCloseWindow()` 收口诊断关闭/页面自愈/手动停止三条路；
      `clearCarrierState()` 改为 `stampCarrierStopped()`（停承载后**5s 宽限**再关页面窗口，覆盖
      "KICKED 帧晚到"）；KICKED 归因改用更严的 `kickedIsSelfAttributed()`（运行中或停后 5s 内），
      `carrierMayHaveKickedPage()` 保留给"点通知定位"。
- [x] **P1 交还确认只覆盖主 socket**：`stop()` 改优雅 `close(1000)`；`stopAndAwaitClose` 同时等
      主 socket 关闭确认**与**各工作区桥 `disposeEverything` 完成（两者都到才 `confirmed=true`）。
- [x] **P1 渲染器重建**：`isFinishing/isDestroyed` 直接返回；恢复 URL 必须过 `RemoteUrl.isAllowed`
      （拒 `about:blank`/错误页/一次性回调 URL），不可恢复时摘除并销毁旧 WebView 后回扫码页。
- [x] **P1 桥残留在登录页**：`configureWebView()` 不再无条件 `addJavascriptInterface`，
      桥由 `setBridgeAttached` 独占（重建后 `bridgeAttached=false` 与实参 false 相等的早退不再漏）。
- [x] **注入层 P1**：版本闸门探测限频（未阻断 1s/次，已阻断每拍——那时 DOM 不动、扫描很便宜）；
      pin 标记在**失败态**（KICKED/error）不再清除；承载窗口上限 5min→60s（原生停后 5s 主动关窗）。

### 留档未修 → 已修（2026-11-05 第二轮）

- [x] KICKED 归因补**身份匹配**：窗口 workspace ≠ 页面当前 workspace ⇒ 外部抢占（两侧未知时退回时间判据）；
  承载**自己被踢**（`onKicked`）时立刻关窗并记"失去控制权"，10s 内的页面 KICK 一律归外部，
  且取消已排定的重载（避免"反顶接管方"）。
- [x] 删除死代码 `__zcodeShellAfterCarrierReturn`（零调用点；职责被 close-confirm 后的必然重载覆盖；
  `android/README.md` 对它的描述属历史口径）——连带删除其 4 条测试，注入层不再有绕过闸门③的 reload 入口。
- [x] `__zcodeShellRecoverConversation` 补闸门③守卫（阻断时不重载、不碰 history.state）。
- [x] `desktopversion` 去重（同值不发帧）。
- [x] pin 标记**用完即焚**（重钉后立即清；写入移到 `pinChatRoute()` 之后）+ 原生在离开远程页的
  导航（auth/外域）上 best-effort `removeItem`——登出重登不再被拽回旧对话。
- [x] 凭证脱敏：`ready` 日志只落 path（原样 href 可能带 sid，预存在问题就此了结）。
- [x] 闸门③判据收紧 `[open]`（关闭态常驻 dialog 不再永久误判）+ 探测限频（未阻断 1s/次、已阻断每拍）。
- [x] `DesktopVersion` 把 `+` 编码为 `%2B`（否则页面读到空格、闸门判不等）。
- [x] `rendererGone()` 在已有罩时转可撤形态并允许 `expire(-1)` 兜底；交还确认计时器共享化并在成功路径取消。
- [x] `onConfigurationChanged`（uiMode 不重建 ⇒ 登录面条带重算）+ `onPageCommitVisible` 复用（登录面 SPA 跳转对档）。

### 仍然留档（需真机/产品决策）

- 下载判定仍按"后缀表留在 WebView"实现：**无后缀 + Content-Disposition: attachment 的外域下载仍会外抛**
  （交给系统浏览器下载）。要根治需放弃外抛、让 DownloadListener 当权威——涉及 D2 语义，待拍板。
- 登录面 dark 位用系统夜景代替"网页主题"（无纠正路径）：需真机核对 chat.z.ai 登录页是否严格跟随
  `prefers-color-scheme`，否则要另找信号源。
- bfcache 返回远程页时凭证缺失（已加诊断日志）：真正的补发机制待真机验证 bfcache 行为后定。
- `__zcodeShellExpectCarrier` 暴露页面世界（自伤型，已在注释显式承认）；`NARROW_QUERY` 对 <3.17.1 页面偏宽
  （回退版本才遇到）。
- 真机待量：close 确认常态耗时（现已含桥）、前台自我接管罩的时序、`blur/logo` 首帧撤罩、
  承载被外部踢掉后的整链表现（新逻辑）。
