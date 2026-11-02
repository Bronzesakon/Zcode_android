# 旧基线结论撤销表（3.15.3 → 3.16.1）

**用途：** 防止旧审计结论被误带进 3.16.1 实现。左列来自归档
`archive/pre-3.16.1-auth/`；右列是 3.16.1 的替代结论（最终以 Round 2 交叉验证为准）。

| # | 旧基线（3.15.3）结论 | 3.16.1 状态 | 替代结论 / 证据方向 |
|---|---|---|---|
| 1 | 配对链接恒写 `sid` / `hash` / `t`，`hash` 是 HMAC key | **撤销** | 3.16.1 链接为 `app_version` / `sid` / `mid` / `name`，无 `hash` / `t`；认证改 OAuth |
| 2 | 链接解析必需集 `{sid,hash,t}` | **撤销** | `sid` 必填；`app_version` / `mid` / `name` 可选；无 `hash` / `t` |
| 3 | relay WS 为 `wss://<host>/ws?mid=…` | **撤销** | 3.16.1 为 `wss://<host>/api/v1/remote/ws2?token=<terminal token>&mid=…` |
| 4 | `auth_init` 带 `role:"terminal"`，`client_ts` 毫秒 | **撤销** | token 模式 `auth_init` **无 role**，`client_ts` 为 Unix 秒 |
| 5 | `auth_init` → `auth_challenge` → `auth_response`(HMAC) | **撤销** | 3.16.1 token 模式直接 `auth_init` → `auth_ack` / `pair_status`，无 challenge/proof |
| 6 | 桌面端 `device_register_init` + `auth_init role:"device"` + HMAC | **旧基线** | 桌面端 3.16.1 改为 `/api/v1/remote/token`（`role:"device"`）换短 token；以 Round 1/2 桌面端证据为准 |
| 7 | 手机端把 OAuth JWT 直接放进 ws2 `?token=` | **错误实现** | ws2 需要 `POST /api/v1/remote/token`（`{"role":"terminal"}`）换来的 22 字符短 token |
| 8 | bootstrap `result` 必带顶层 `tasks`（16 键） | **撤销** | 3.16.1 `buildBootstrapResult` 只有 `workspaces` / `initialViewState` / `mobileViewState` / `mobileTaskViewEpoch` 等；任务来自 `sessionsIndex.list` |
| 9 | 任务字段 `taskId` / `displayStatus` / `unreadAt` / `pinned` / `archived` 来自 bootstrap `tasks` | **需重定义** | sessions-index 条目 schema 为 `sessionId` / `workspaceId` / `title` / `phase` / `lastOutcome` / `hasBackgroundWork` / `lastActivityAt` / `lastAssistantPreview` / `createdAt`；pinned/archived/unread 真实来源待 Round 1/2 确认 |
| 10 | `subscribeSessionsIndex` 会返回顶层 `completed` | **撤销** | Rust 侧订阅流程只发 `Subscribed`，没有 `completed`；宿主必须用「发送后不等」路径 |
| 11 | 移动端 relay WS 地址硬编码 `wss://zcode.z.ai/ws` | **撤销** | 3.16.1 使用 `<host>/api/v1/remote/ws2`，host 来自链接/环境 |
| 12 | `app_version` 只用于选择 `/remote/v3` 或 `/remote/v4` | **部分保留** | 仍影响入口资源版本；3.16.1 还影响 `auth_init.meta.version` 与 API 环境，需 Round 1 确认 |
| 13 | 登录不是远控前置条件 | **撤销** | 3.16.1 新链接必须先登录 Z.ai/BigModel，拿 OAuth JWT，再换 terminal token |
| 14 | 旧 `hash` 的 base64 `+` / `/` 编码陷阱 | **不再适用** | 新链接无 `hash`；不要把旧编码结论套到 OAuth JWT/terminal token |
| 15 | 旧基线 `docs/remote-v4` / `docs/desktop` 可作当前规格 | **撤销** | 已移入 `archive/pre-3.16.1-auth/`，只读历史证据 |

## 使用方式

- 实现/评审时先查本表；任何与右列冲突的旧文档结论一律不采用。
- Round 2 交叉审计必须专门检查：Round 1 报告是否误用了左列任意一条。
- 若右列本身被 Round 2 推翻，以 `crosscheck/` 的裁决和最终 `AUTH-FLOW-FINAL.md` 为准。
