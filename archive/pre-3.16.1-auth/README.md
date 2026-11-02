# 归档：3.16.1 之前的登录 / 远控协议基线

**归档日期：2026-10-26**
**状态：只读历史证据。不要作为当前实现依据。**

## 为什么归档

3.16.1 把远控认证从「链接里的 `hash` + HMAC proof」整体换成了「OAuth JWT → 终端短 token」：

- 远控链接不再带 `hash` / `t`，改为 `app_version` / `sid` / `mid` / `name`；
- relay 从 `wss://<host>/ws?mid=…` 改为 `wss://<host>/api/v1/remote/ws2?token=…&mid=…`；
- 手机必须先登录 Z.ai 或 BigModel，拿 OAuth JWT，再用 JWT 调
  `POST /api/v1/remote/token`（`{"role":"terminal"}`）换 22 字符终端 token；
- `auth_init` 不再带 `role`，`client_ts` 改为 Unix 秒，不再有 `auth_challenge` / HMAC。

因此旧基线里关于「链接 hash、HMAC proof、auth_challenge、ws 路径、bootstrap 顶层 tasks」
的结论全部作废。保留这些材料只为追溯与对照，不作为新实现规格。

## 归档内容

| 路径 | 内容 | 旧基线 |
|---|---|---|
| `Reference_web/3.15.3/` | 3.15.3 网页快照（只读，仅本机保留，不入 Git） | 2026-10-15 抓取；主包 `assets/index-BGa3KiRe.js` 6,592,643 B；SHA-256 `ce2e2fc4…b88fd` |
| `docs/remote-v4/` | 3.15.3 网页端 remote-v4 协议审计镜像（13 篇） | 2026-10-17 按 3.15.3 重写 |
| `docs/desktop/` | 3.15.3 桌面端（对端）协议逆向（含 `05-配对与身份.md`） | `app.asar` 3.15.3，build `d7ca8bee` |
| `docs/_audit/` | 旧派单契约与桌面端 chunk 抽取副本 | 工作用材料 |

## 使用纪律

1. **只读**：不要修改、覆盖或重新生成这些文件。
2. **不作为当前规格**：任何与 3.16.1 冲突的旧结论，以 `Reference_web/3.16.1/` 和
   `docs/auth-3.16.1/` 为准。
3. 归档文档内部的交叉引用仍指向旧路径（例如 `docs/remote-v4/...`），这是历史原貌；
   需要追溯时按本目录的相对路径查找。
4. 旧网页快照按 `Reference_web/` 的既有约定**不入 Git**；归档目录里的其他 Markdown
   是否入库由仓库策略决定。
