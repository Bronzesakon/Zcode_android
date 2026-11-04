# 更新日志

本文件是正式 Release 的正文来源：`.github/scripts/release-notes.ps1` 会取 `vX.Y.Z` tag 对应的
`## [X.Y.Z]` 段作为 Release 说明，**缺该段会让发布任务直接失败**（宁可不发，也不发空正文）。
所以打 tag 之前，先在这里补好对应段落。

格式沿用 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

## [0.1.0]

重写版本。把上游 Flutter 实现（归档在 `Reference_code/`，作为协议逆向的唯一权威参照）换成
**Rust 协议核心 + 原生 Kotlin/Compose 外壳**；界面按官方 Web 远控端（快照在 `Reference_web/`）
一比一复刻。

分层与数据流见 `docs/ARCHITECTURE.md`；各层契约见 `docs/FFI.md`、`docs/ENGINE-API.md`、
`rust/crates/protocol/PORTING.md`。

### 新增

**Rust 协议核心**（`rust/crates/protocol`，纯逻辑、同步、无 I/O、不读时钟）

- 逐模块移植原 Dart 协议层：连接参数解析、设备信息、HMAC 配对证明、IEEE CRC-32、CSPRNG 的 ID 生成、
  IPC 值编解码、RPC 分帧与重组、中继连接状态机、通道 RPC、定时任务、对话状态机，以及
  **对话传输层**（握手、订阅、看门狗、CAS 重试、行区间、附件分块上传）。
- 时间与 I/O 全部由宿主提供（核心只发 `TimerStart`/`TimerCancel` 事件），因此整条链路可以在主机上
  用假时钟 + 假 socket 完整测试。
- 模块与 Dart 源文件一一对应，跨模块共用类型从 `prelude` 再导出（不搬家，保留可追溯性）。

**FFI 绑定层**（`rust/crates/ffi` + `docs/FFI.md`）

- **零回调设计**：不要求 Kotlin 实现任何 Rust trait。Rust 侧用记录型 transport 把
  `connect`/`send_text`/`close` 记成事件随返回值吐出，于是 FFI 面只有 6 个函数、全是 JSON 字符串进出。
- 契约冻结并版本化（`nativeAbiVersion` 的 `abi`），Kotlin 启动时核对，不一致直接报错而不是带着错位结构跑。
- `nativeCreate` 支持 `dryRun`：只解析远控 URL 不建会话，供配对界面取权威的 `sid`/`name`/去重键，
  避免 Kotlin 侧重复实现 query 解码语义。
- 每个 JNI 入口 `catch_unwind`，panic 转成一次失败调用而不是 SIGABRT。

**Kotlin 宿主层**（`android/`）

- **引擎**：协程事件循环，执行 `ws_*` 动作、驱动两套独立命名空间的定时器、把 socket 与定时器事件回喂核心、
  按令牌解析挂起操作。所有 native 调用收敛在专用单线程 dispatcher（契约要求同一 handle 不可重入）。
- **持久化**：设备列表与设置的 DataStore 存储；远控 URL 里的配对密钥材料用 Android Keystore
  AES-GCM 加密后落盘（**内存明文、落盘密文**）；密钥丢失时降级为"需重新配对"，不崩也不静默。
- **配对**：URL 前置筛查（只查必要条件，权威校验仍在 Rust）、设备去重、扫码接口。
- **应用内更新**：读发布清单、断点续传、MD5 完整性校验、FileProvider 拉起系统安装器。
- **表现层**：ViewModel + 手写导航状态机（4~5 个界面，零依赖、可单测）。界面样式本次不实现。

**设计基线**（`design/`）

- 从 Web 快照抽取：551 个颜色令牌（明暗两套，oklch→sRGB 转换对照真实 Chromium 引擎逐项校验）、
  字号阶梯、4px 间距栅格、247 个图标及线宽约定、12 个视图清单、协议地图（含 35 条待证实项）。
- 已生成对应的 Compose 主题（`Color.kt`/`Type.kt`/`Dimens.kt`/`Theme.kt`）。

**CI**（`.github/workflows/android.yml`）

- 推 `main` 滚动覆写预发布 `android-pre`（资产名固定 + `--clobber`，下载链接永不失效）；
  打 `v*` tag 发正式 Release，正文取本文件的对应段落。
- 构建前先跑 Rust 全 workspace 测试与 Kotlin 单测；打包后自检 `.so` 里存在 JNI 入口符号
  （符号名对不上时 APK 能装能起、一调就 `UnsatisfiedLinkError`，单测发现不了）。

### 安全

- 使用**本项目专属**的发布签名（别名 `zemote`），不复用 android-shell 项目的签名材料 ——
  共用签名只对同一个 `applicationId` 有意义。
- 签名材料与 `key.properties` 均不入库；密码只存在于本地文件与 GitHub Secrets。
- 远控凭据（URL 中的 `hash`）加密落盘；FFI 的任何返回值都不含 `passHash`（有测试断言）。
- 自更新在拉起安装器前校验**下载包的签名与当前应用同源**（用本机已装应用的签名比对，
  攻击者没有私钥就伪造不出能通过这一步的包）；`REQUEST_INSTALL_PACKAGES` 已声明。
- release profile 刻意保留 `panic = "unwind"`（未设 `abort`），使解析网络字节时的 panic 可被捕获。

### 变更

- 应用标识为 `app.zemote`；只出 **arm64-v8a** 单 ABI（现有真机均为 arm64），因此不再需要
  `--split-per-abi` 带来的每 ABI versionCode 偏移。
- `versionCode` 沿用构建时间方案（UTC+8 的 `yyMMddHH`），保证每次构建都能覆盖安装此前任何构建；
  `versionName` 在分支构建为 `<VERSION>-dev.<yyyyMMdd.HHmm>`，tag 构建取 tag 版本。
- 版本号基准从 `pubspec.yaml` 改为仓库根目录的 `VERSION` 文件。

### 移除

- 旧 Flutter 实现与其构建工作流：源码整体归档到 `Reference_code/`（只读参照，不再构建），
  Flutter/Dart 工具链已从本机卸载。

[Unreleased]: https://github.com/Bronzesakon/Zcode_android/compare/android-pre...HEAD
[0.1.0]: https://github.com/Bronzesakon/Zcode_android/releases/tag/v0.1.0
