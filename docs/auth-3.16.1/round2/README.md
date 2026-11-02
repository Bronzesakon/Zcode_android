# Round 2 交叉验证派单

Round 1 四份报告落盘后启动。四路各自独立重读源码，不信任 Round 1；按
`../crosscheck/README.md` 的裁决格式输出。

| 路 | 输出 | 主责范围 | 必须重点挑战 |
|---|---|---|---|
| A | `crosscheck/01-entry-oauth.md` | 入口/门控 + OAuth | `state` 语义、`app_return_to`、provider 选择、回调清理、存储键/过期、刷新/重载门控 |
| B | `crosscheck/02-token-relay.md` | terminal token + ws2/配对 | token 每次换/缓存/刷新、`expires_in`、401 条件、`auth_init.meta`、错误码/关闭码、重连 |
| C | `crosscheck/03-dataflow.md` | paired 后数据流 | bootstrap 无顶层 `tasks`、sessions-index 条目 schema、`phase` 映射、pinned/archived/unread 来源、subscribe 无 `completed` |
| D | `crosscheck/04-end-to-end.md` | 端到端一致性 + 实现差异 | 四份报告之间的矛盾、字段名/顺序/单位错误、旧基线残留、网页/桌面端证据混淆、Rust/Kotlin 差异矩阵 |

## 共同要求

- 输入：`round1/01`–`04`、`Reference_web/3.16.1/`、桌面端 `app.asar`（只读）、
  `rust/`、`android/`、`archive/pre-3.16.1-auth/`（仅用于识别旧结论残留）。
- 每条裁决必须带坐标；`CONTRADICTED` 必须给出正确结论 + 新坐标 + 原文。
- 不修改 Round 1 报告；只写自己的 crosscheck 文件。
- 最终回复父对话 ≤15 行：报告路径 + `CONFIRMED/CONTRADICTED/MISSING` 数量 + 3–5 条最重要纠错。
- 不执行快照 JS，不联网，不依赖真机。
