# TODO / 路线与决策台账

> 2026-11-04 起，`Zcode_android` 的主线是 **WebView 薄壳**；原「Rust 核心 + Kotlin/Compose 界面」
> 已归档到 `archive/native-compose/`，仅作参考与迁移来源。

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
8. **CI**：壳的 workflow 原样接管（JS 106 项 + Kotlin 122 项 + APK + `android-pre` 滚动发布）；
   去掉 Rust/NDK；版本源 `android/gradle.properties: zcodeBaseVersion`；资产 `zcode-remote.apk`。
9. **明确不做**：移植壳的 Tier2（legacy HMAC 在 3.16.1+ 无 `hash`，3.17.1 上不会启动）；
   用 DOM 选择器复刻 UI；把页面当“纯渲染层”由原生喂数据。

## 2. Rust 核心最小替换（P3，先评估后移植）

目标：后台接管**只在页面链路判死时**启用，用 Rust 核心做 carrier，替代壳的 Tier2；
健康路径仍然页面持有，不引入第二条连接。

### 2.1 第一步：评估 Rust 核心可用度

- [ ] 认证：OAuth JWT / `auth_token` / `ws2?token=` 是否覆盖 3.16.1+（`connection_params.rs` 已有 token 模式）。
- [ ] 配对：`auth_init` / `pair_status_query` / `KICKED` 终态处理是否与页面语义一致。
- [ ] 数据面：bootstrap、workspace-bridge-open、conversation 订阅/快照/增量、RPC 分片与 CRC。
- [ ] 多工作区：能否开多座桥；是否理解“桌面端同一时刻只服务一座桥”的服务权/轮换约束。
- [ ] 状态：任务运行态三源、会话索引、通知所需字段（displayStatus/workspacePath/title/updatedAt…）。
- [ ] 生命周期：能否在无 UI、可取消、无副作用（不写页面状态）的 carrier 模式下运行。
- [ ] 结论：输出“可直接复用 / 需补 / 不可用”清单，再决定最小功能集。

### 2.2 第二步：最小功能集（只做后台通知与连接延续）

- [ ] 认证与配对（含 KICKED 处理）。
- [ ] bootstrap + 只开“在跑任务所在工作区”的桥（上限 5）。
- [ ] 订阅在跑会话的进度/状态；任务完成/待确认事件。
- [ ] 把事件喂给现有 `NotificationCoordinator` / Live Update；不碰页面 UI。
- [ ] 交还：停 carrier → 确认 socket close → 页面重载；失败才 reload，健康路径不接管。
- [ ] 判死：入站帧静默 ≥35s（3 个泵周期 + 余量），或用页面体征/ACK 做更强证据。
- [ ] 复刻参考壳的 `HandoffState` / `CarrierHandback` / 判死/交还单测。

### 2.3 第三步：验收

- [ ] 后台 30 分钟：页面/原生至少一侧持续收帧；通知不丢。
- [ ] 健康路径：退后台 10 分钟回前台 **0 重载**、对话位置不丢。
- [ ] 故障路径：页面 socket 判死 → 原生接管 → 回前台交还 → KICKED 罩住 → 重载后回到原任务。
- [ ] 快速切应用（<5s）不触发接管、不 KICK、不重载。
- [ ] 单控制端：任意时刻只有一条 `/ws`；无重复 bridge、无 `rpc-transport-fault`。

## 3. 迁移状态

| 项 | 状态 |
| --- | --- |
| 鸿蒙仓库清除 android-shell / README / 记忆 | ✅ 2026-11-04，commit `493fcd6` |
| 原生归档到 `archive/native-compose/`（tag `native-compose-final`） | ✅ commit `c37349e` |
| 壳工程导入 `android/`（快照导入） | ✅ commit `1231133` |
| Android 文档迁入 `docs/android/` | ✅ commit `6d239bb` |
| CI 换成壳的 workflow / release-notes | ✅ 已适配路径，待首次 CI 验证 |
| history patch 删除 | ✅ 代码 + CI 拉取步骤均已删除 |
| 本地构建/单测 | ✅ JS 106 项通过；Kotlin 单测通过；`app-debug.apk` 7.1 MB |
| GitHub Secrets 换成壳 keystore | ⏳ 迁移提交后、首次 CI 前 |
| 根 README 重写 | ✅ |
| 3.17.1 锚点回归 | ⏳ 待做 |

## 4. 已知风险 / 待办

- [ ] 壳最后验证基线是 3.15.3；需按 3.17.1 重跑 37 条锚点审计。
- [ ] 删除 `RemoteHistoryPatch` 与 CI 里的 3.15.3 bundle 拉取（已删 CI 步骤；代码待删）。
- [ ] `onRenderProcessGone` 尚未实现（渲染进程被杀会崩溃/冷启动）。
- [ ] 签名 Secrets 更新前，CI 出包会回退 debug 签名。
- [ ] 壳的 `RemoteHistoryPatch` 删除后，首屏 60 行历史窗口优化消失（接受）。
- [ ] `docs/android/vendor`、`coloros` 约 91 MB 本地-only，换机器需手动拷贝。
EOF