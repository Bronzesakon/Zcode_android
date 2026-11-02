# ZCode Web 3.16.1 登录与信息流审计（当前权威规格）

**审计启动：2026-10-26**
**状态：Round 1 / Round 2 全部完成；最终规格已合成到 [`AUTH-FLOW-FINAL.md`](AUTH-FLOW-FINAL.md)。**
**实现：Rust P0/P1 与 Kotlin P0/登录 P2 已落地并通过 389 个 Android 单测 + `cargo check` + `cargo ndk` 构建；剩余数据流项见最终规格 §9。**
**基线：`Reference_web/3.16.1/`（公开部署快照，只读）。**
**旧基线：`archive/pre-3.16.1-auth/`（3.15.3 网页快照 + 旧协议审计，只读历史证据，不作当前规格）。**

## 为什么重做

3.16.1 把远控认证从「链接 `hash` + HMAC proof」整体换成「OAuth JWT → 终端短 token」。
旧审计（3.15.3）里关于 `hash`、`auth_challenge`、`/ws`、bootstrap 顶层 `tasks` 的结论
全部作废。本目录只回答 3.16.1 的事实，并以**可复现坐标**为准。

## 审计源与指纹

| 文件 | 大小（B） | SHA-256 |
|---|---:|---|
| `Reference_web/3.16.1/index.html` | 18,357 | `c4c7c7ff87bfb19a0e13b0cc2e4fbdb7be034af07a5aac1b1723ebada51e9d22` |
| `Reference_web/3.16.1/assets/index-DbEHT6Gg.js` | 6,836,503 | `dc0d8128b394d624a003af8c9afa22f77c9dbcdc30fbabef15b85df605e3c821` |
| `Reference_web/3.16.1/assets/src-uVqBd_Eq.js` | 70,714 | `93813e7e2b9ff43625fced61589000aed65788d3e65e66d4ace6f7494bcaaa91` |
| `Reference_web/3.16.1/assets/validation-BBf1FA1a.js` | 331,153 | `7686853747e58b524a84a15ddff6cd5fd92b1870ea7dc86781888c7b294d8962` |
| `Reference_web/3.16.1/assets/IntlProvider-Be9akOJ8.js` | 932,634 | `3cc6ba5331dc6025214d55e64c3a1d3115eb4f9324eadf5621ab3a9abc0ec0d6` |
| `Reference_web/3.16.1/_manifest.json` | 1,065,344 | `1e3ef2bdf7f97e14013d41421abde9ab661d2b724c2b1a2731b9f1565b22444c` |

辅助源（只读，不执行）：

- 桌面端 `C:\Users\wdn32\AppData\Local\Programs\ZCode\resources\app.asar`（3.16.1 同发布）。
  用 `node tools/asar.mjs grep/cat/stat` 查看。桌面端证据用于确认 relay/token/配对与
  bootstrap 的**服务端/主机契约**；网页客户端行为仍以网页快照为准。
- 本项目实现：`rust/crates/protocol/src/connection_params.rs`、`relay_client.rs`、
  `rust/crates/ffi/src/session.rs`、`android/app/src/main/kotlin/app/zemote/auth/OAuth.kt`、
  `ui/EnginePort.kt`、`ui/auth/*`、`ui/home/HomeViewModel.kt`、`ui/chat/*`。

## 四路深挖（Round 1）

| # | 报告 | 回答什么 |
|---|---|---|
| 01 | [`round1/01-entry-and-gating.md`](round1/01-entry-and-gating.md) | 入口/版本选择、链接参数、登录门控、路由与域名 |
| 02 | [`round1/02-oauth-login.md`](round1/02-oauth-login.md) | Z.ai / BigModel 授权、state、回调、token 交换、凭据存储 |
| 03 | [`round1/03-terminal-token-relay.md`](round1/03-terminal-token-relay.md) | `/api/v1/remote/token`、ws2、auth_init/auth_ack/pair_status、401/错误/重连 |
| 04 | [`round1/04-session-dataflow.md`](round1/04-session-dataflow.md) | paired 后 bootstrap/workspace-list/sessions-index/bridge/RPC 数据流 |

每份报告必须包含：结论摘要、完整时序/状态机、逐字段表、全部分支与错误路径、
与当前 Rust/Kotlin 的差异、证据索引（文件 + UTF-8 字节偏移 + 原文片段）、未解问题。

**Round 1 已落盘：**

| # | 报告 | 状态 |
|---|---|---|
| 01 | [`round1/01-entry-and-gating.md`](round1/01-entry-and-gating.md) | 完成（58.6 KB） |
| 02 | [`round1/02-oauth-login.md`](round1/02-oauth-login.md) | 完成（51.0 KB） |
| 03 | [`round1/03-terminal-token-relay.md`](round1/03-terminal-token-relay.md) | 完成（58.9 KB） |
| 04 | [`round1/04-session-dataflow.md`](round1/04-session-dataflow.md) | 完成（68.8 KB） |

## 交叉验证（Round 2）

Round 1 已完成，另开 4 路只读交叉审计。**四路已全部落盘：**

| 路 | 报告 | 裁决统计 |
|---|---|---|
| A | [`crosscheck/01-entry-oauth.md`](crosscheck/01-entry-oauth.md) | CONFIRMED 35 / CONTRADICTED 14 / MISSING 18 / UNVERIFIED 2 |
| B | [`crosscheck/02-token-relay.md`](crosscheck/02-token-relay.md) | CONFIRMED 25 / CONTRADICTED 3 / MISSING 12 / UNVERIFIED 2 |
| C | [`crosscheck/03-dataflow.md`](crosscheck/03-dataflow.md) | 任务基底、cvt 继承、relay 路径等 4 处核心纠错 |
| D | [`crosscheck/04-end-to-end.md`](crosscheck/04-end-to-end.md) | CONFIRMED 35 / CONTRADICTED 9 / UNVERIFIED 6 |

每路：

1. 独立重读 3.16.1 源，不信任 Round 1 结论；
2. 对 Round 1 的每条关键结论给出 `CONFIRMED` / `CONTRADICTED` / `MISSING`；
3. 专门找「字段名对但语义错」「只在某个分支成立」「旧基线残留」「网页与桌面端证据混淆」；
4. 输出 `crosscheck/0X-*.md`，只回 ≤15 行摘要。

Round 2 已通过；最终规格 + 实现检查清单见 [`AUTH-FLOW-FINAL.md`](AUTH-FLOW-FINAL.md)。

## 纪律

- 每条结论必须有可复现坐标；无法确认标 `UNVERIFIED`；推测标 `SPECULATION`。
- 不执行快照中的 JS，不联网，不依赖真机。
- 不把旧基线（3.15.3）结论带进当前规格；需要对比时显式标 `[旧基线]`。
- 不把「网页客户端行为」与「桌面端/服务端行为」混写；分别标注证据来源。
- 不修改源码；审计只写 `docs/auth-3.16.1/`。

## 已裁决问题（原「待验证」）

1. **terminal token 每次建连都重换**：网页 `connect()` 第一行 `await getToken()`，桌面同构；
   `expires_in` 三端都不读，无主动刷新。Android 已在 `WsConnect(generation>1)` 前重换。
2. **ws2 升级 401 不可解析**：网页/桌面都拿不到握手 HTTP 状态，只得到 error + close(1006)，
   按可重试网络错误无限退避；唯一对策是每次重连换 token。见最终规格 §7。
3. **`auth_init.meta` 不参与配对判定**（只作展示/诊断）；配对由 `auth_ack.device_sid` 与
   `pair_status` 决定。
4. **bootstrap 确认没有顶层 `tasks`**；任务由 sessions-index 提供，`pinned/archived/unreadAt`
   另有来源。
5. **`pinned/archived` 来自 `readTree.tasks` / `zcodeTaskService`；`unreadAt` 来自
   `getTaskMeta`**，sessions-index 条目不提供这些字段。
6. **订阅 wire 没有顶层 `completed`**；Rust 在 ack 后合成 host-level `Completed`。注释已改。
7. **登录态只认 JWT `exp`（30s 余量）**；`expires_in` 只做诊断，不参与拦截。

## 仍未验证（不预设答案）

1. 远端 host 是否实现 `window-controller` / `project` RPC（决定 `readTree` 是否可用）。
2. terminal token 是否一次性、`expires_in` 真实数值。
3. ws2 升级 401 的精确条件（客户端结构性不可见）。
4. `terminal_sid` 的用途（三端 schema 有、代码都不读）。
5. `data.user` 的真实字段形态；`avatar` 是 URL 还是裸 base64。
6. `mobile-task-view-*` 的完整握手（需要 Rust 识别并上抛新事件）。
