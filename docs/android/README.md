# Android 文档（原 android-shell 知识库）

> 2026-11-04 从 `Zcode_harmony` 迁入。Android 薄壳主工程在仓库的 `android/`，
> 本目录只保存平台事实、壳机制、分区审计与厂商文档快照。
> **鸿蒙仓库已不再包含 android-shell 子项目，也不保留其文档。**

## 入库文档（自撰）

| 组 | 文件 |
| --- | --- |
| 平台事实 | `11-remote-v4-平台事实.md`（Android WebView / 前台服务 / ColorOS / Live Updates） |
| 禁区与真机配方 | `12-安卓壳-禁区陷阱与真机配方.md` |
| 注入审计 | `22-remote-v4-壳端注入点审计-主题-滚动条-会话加载.md`、`23-remote-v4-进对话无内容与左下角转圈-现场分析.md` |
| 后台承载 | `25-android-shell-后台原生承载-独立设计.md`（与壳内 `docs/15` 同文） |
| 全仓审计 | `26-android-shell-全仓审计-错误路线残留与清理.md`（与壳内 `docs/18` 同文） |
| 功能域 | `29`–`34`（通知/诊断/多工作区/运行态/TaskStore/看门狗） |
| 分区审计 | `35`–`47`（relay-core、shell-runtime、carrier、activities、notify、core-state、inject-js a–d、protocol-js、resources、tests） |
| 性能 | `安卓壳-快速交接与性能收窄.md` |
| 鸿蒙对照（移出鸿蒙） | `09-harmony-replication-comparison.md` |
| 迁移文档（移出鸿蒙） | `harmony-migration.md`（原 `安卓薄壳迁移文档.md`） |

## 本地-only（不入 Git）

| 目录 | 内容 | 规模 |
| --- | --- | --- |
| `vendor/` | Android 文件选择 / Material 3 / Android 16 M3 Expressive 厂商文档快照（56 篇 + 469 图） | 约 3 MB |
| `vendor/miuix/` | miuix 组件库源码（Compose Multiplatform，Apache-2.0；已去 `.git`）——App 设置页配色/度量规范的出处，后续界面重写备用 | 约 22 MB |
| `coloros/` | OPPO/ColorOS 官方文档 149 篇 + Android 官方 6 篇 | 约 88 MB |

版权归原厂商所有，仅供本地离线查阅；已由 `.gitignore` 排除。换机器需手动拷贝，
来源清单见 `vendor/README.md` 与 `coloros/README.md`。

## 与网页/协议文档的关系

remote-v4 的 13 篇网页/协议文档已在 `archive/pre-3.16.1-auth/docs/remote-v4/`；
3.17.1 的逆向规格在 `docs/web-reverse/`。本目录只放 Android 宿主侧材料。

## 术语（2026-11-04 更名，代码与文档已全量替换）

| 现行 | 旧称 | 指什么 |
| --- | --- | --- |
| **原生承载（Native Carrier）** | `Tier2` | 页面链路判死后，由原生态持唯一 relay 连接替页面承载（接管 → 交还）。实现类 `core/NativeRelayClient.kt`；语言方向已定**统一到 Kotlin**，Rust 核心转规格 |
| **页面侧／页面链路** | `Tier1` | 页面自己持连接、原生只读观测的那一层；看门狗 `startPageLinkWatchdog` |

诊断指令跟着改名：`tier2_test|tier2_stop|tier2_takeover` → `carrier_test|carrier_stop|carrier_takeover`；
日志前缀 `Tier2:` → `承载:`。旧装机日志仍为旧字样，按此对照。
