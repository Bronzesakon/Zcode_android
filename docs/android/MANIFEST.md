# Android 厂商文档清单（本地-only）

> 本目录下的 `vendor/` 与 `coloros/` 是厂商官方文档的离线快照，版权归原厂商所有，
> 仅供本地查阅；已由 `docs/android/.gitignore` 排除，**不随仓库分发**。
> 换机器时需要从原工程或本地备份手动拷贝。

| 目录 | 内容 | 文件数 | 体积 | 来源 |
| --- | --- | --- | --- | --- |
| `vendor/` | Android 文件/图片选择、Material 3、Android 16 M3 Expressive 规范 | 57（含 README） | 约 3 MB | 原 `android-shell/docs/01`–`04` |
| `vendor/miuix/` | miuix 组件库源码（Compose Multiplatform，Apache-2.0；已去 `.git`） | 源码树 | 约 22 MB | 原 `android-shell/docs/miuix` |
| `coloros/` | OPPO/ColorOS 官方文档 149 篇 + Android 官方 6 篇 | 769 | 约 88 MB | 原 `android-shell/ColorOS_docs/` |

本地备份：`E:\Zcode_android\scratch\android-shell-import\`（完整迁移暂存，清理后以本目录为准）。
原 `android-shell/docs/05-远程网页-remote-v4-20260911`（8.4 MB 历史网页快照）**未迁入**，
已被 `Reference_web/3.16.1`、`Reference_web/3.17.1` 取代。
