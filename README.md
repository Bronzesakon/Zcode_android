# Zemote（重写版）

手机/平板上的 **ZCode 远程控制客户端**。本仓库正在把上游 Flutter 实现**重写为原生安卓应用**：
协议核心用 **Rust**，界面用 **Kotlin + Jetpack Compose** 按官方网页版一比一复刻。

上游 Flutter 实现已冻结、不再构建，原样保留在 `Reference_code/` —— 它是对远程控制网页协议逆向的
**唯一权威参照**，本次重写的全部协议行为以它为准。

> ⚠️ 本项目为**协议复刻**，与官方客户端无任何代码关联。请遵守 ZCode 服务条款与当地法律法规，
> 仅用于连接你自己的设备。

---

## 本机开发环境（E:\Zcode_android）

已经装好并实测可用的工具链。**换机器时按这张表配**；`C:` 盘上不放 SDK（这是硬约束）。

| 组件 | 路径 / 版本 | 说明 |
|---|---|---|
| Android SDK | `E:\AndroidSdk`（`ANDROID_HOME` / `ANDROID_SDK_ROOT` 已设） | build-tools `36.0.0`、platform `android-36`、platform-tools `37.0.1`；licenses 已全部接受 |
| Android NDK | `E:\AndroidSdk\ndk\28.2.13676358` | 交叉编译 Rust 与 AGP strip `.so` 都要它；版本固定，勿随意升级 |
| **JDK 17** | `E:\AndroidSdk\jdk-17`（Temurin 17.0.20.1） | **必须用完整 JDK**，原因见下 |
| Gradle | 9.1.0（`android/gradlew`，发行包已缓存） | 由 wrapper 自动使用，无需另装 |
| Rust | stable + target `aarch64-linux-android` | crates 源已换清华 TUNA 镜像（`~/.cargo/config.toml`） |
| cargo-ndk | 4.1.2 | 本地交叉编译的便利工具；CI 里不依赖它（见「构建」） |
| 真机 | `PLC110`（Android 17 / arm64）、`192.168.0.1:5555` 的 `MU5358`（Android 15 / arm64） | 两台都是 arm64-v8a，这是只出单 ABI 的依据 |

### 为什么必须用 `E:\AndroidSdk\jdk-17`，而不是 DevEco 自带的 JBR

本机原本唯一的 Java 是 DevEco Studio 的 JBR 25，它是**精简运行时：没有 `bin/jlink.exe`，也没有
`jmods/`**。AGP 在 `compileSdk >= 31` 时要用 `jlink` 生成 `core-for-system-modules.jar`，
缺了就报 `jlink executable ... does not exist`，卡在 `:app:compileDebugJavaWithJavac`。
所以另装了一份完整 Temurin JDK 17，并在**用户级** `~/.gradle/gradle.properties` 里指向它：

```properties
org.gradle.java.home=E:/AndroidSdk/jdk-17
```

放在用户级而不是仓库里，是为了让仓库不含机器相关路径。改过这项之后要先 `./gradlew --stop`
再构建，否则会复用按旧 JDK 启动的 daemon。

---

## 仓库布局

| 路径 | 内容 | 状态 |
|---|---|---|
| `rust/` | **Rust 侧**：`crates/protocol`（纯逻辑协议核心）+ `crates/ffi`（JNI 绑定层） | `cargo test --workspace` 全绿 |
| `android/` | **Kotlin 外壳**：引擎 / 持久化 / 配对 / 更新 / 表现层（界面样式未实现） | 单测全绿，debug / release 均可构建 |
| `design/` | 从网页快照抽取的设计基线：设计令牌、图标清单、界面清单、协议地图 | 已完成 |
| `docs/` | 架构图与各层契约：`ARCHITECTURE.md`、`FFI.md`、`ENGINE-API.md` | 已完成 |
| `docs/remote-v4/` | 网页端 remote-v4 协议文档（按 **3.15.3** 重写；源在 `Zcode_harmony/Docs/`） | 只读镜像 |
| `docs/desktop/` | **桌面端（对端）协议逆向 12 篇**（`app.asar` 3.15.3） | 权威对端规格 |
| `tools/` | `asar.mjs`：只读读取 Electron `app.asar`（`ls`/`cat`/`dump`/`grep`/`stat`） | 审计工具 |
| `Reference_code/` | 上游 Flutter 客户端全部源码（Dart 约 21k 行 + 平台胶水 + 原有测试） | 冻结，只读参照 |
| `Reference_web/` | 官方网页版快照（remote v4 / 3.15.3，2026-10-15 抓取） | 只读参照；按该快照自带约定**仅本机保留、不入 Git** |
| `scratch/` | 本地签名材料（`zemote-release.p12`、密码、base64） | **不入库** |
| `VERSION` | 版本号基准（单行），CI 据此生成 `versionName` | — |
| `CHANGELOG.md` | 正式 Release 的正文来源（见「CI」） | — |

> 姊妹仓库 `Zcode_harmony` 另存有更早的两份网页快照、13 份安卓壳分区审计、以及 MIUIX 组件库副本
> （`android-shell/docs/miuix/`），需要时从那边取。

---

## 构建

### Rust 侧

```bash
cd rust
cargo test --workspace                 # 协议核心 + FFI 层，纯逻辑，主机上直接跑，不需要设备
```

交叉编译到 arm64-v8a（本地）：

```bash
cd rust
cargo ndk -t arm64-v8a -o ../android/app/src/main/jniLibs build --release -p zemote-ffi
```

注意编的是 **`zemote-ffi`** 而不是 `zemote-protocol`：只有前者是 cdylib 且带 JNI 入口
（`Java_app_zemote_core_NativeCore_*`），协议 crate 是纯逻辑的 rlib，它那个 `.so` 是空的。
产物名必须是 `libzemote_ffi.so`，与 Kotlin 侧 `System.loadLibrary("zemote_ffi")` 对应。

CI 里不装 cargo-ndk，而是显式把 NDK 的 clang 作为 linker / CC 传给 cargo —— cargo-ndk 做的事就是
这几个环境变量，只编一个目标时少一个活动部件（见 `.github/workflows/android.yml`）。

### 安卓外壳

```bash
cd android
./gradlew :app:testDebugUnitTest  # 宿主层单测（引擎/持久化/配对/更新/表现层），JVM 上跑，不需要设备
./gradlew :app:assembleDebug      # 快速验证，debug 签名
./gradlew :app:assembleRelease    # 发布路径：R8 混淆 + 正式签名
```

产物在 `android/app/build/outputs/apk/`。release 开了 minify + shrinkResources；有正式签名时读
`android/key.properties`（模板见 `android/key.properties.example`），缺该文件会回退 debug 签名
并打印告警。

```bash
cd android
./gradlew :app:assembleDebug      # 快速验证，debug 签名
./gradlew :app:assembleRelease    # 发布路径：R8 混淆 + 正式签名
```

产物在 `android/app/build/outputs/apk/`。release 开了 minify + shrinkResources；有正式签名时读
`android/key.properties`（模板见 `android/key.properties.example`），缺该文件会回退 debug 签名
并打印告警。

---

## 架构

### 边界：协议核心是纯逻辑

`rust/crates/protocol` 是**纯逻辑、同步、无 I/O** 的 crate：不做网络、不做文件、不做定时器、
不用 async、不依赖 tokio。WebSocket / 存储 / 计时 / 权限 / 通知等宿主能力由 Kotlin 侧实现；
需要宿主的地方，crate 里**只定义 trait**，实现留给宿主。好处是 FFI 边界最小、单测不需要任何
mock 运行时。

模块与 Dart 源文件**一一对应**（`relay_client.dart` → `relay_client.rs`），这是移植可追溯性的基础。
跨模块共用的类型不搬家，统一从 `zemote_protocol::prelude` 再导出。

| Rust 模块 | 对应 Dart | 职责 |
|---|---|---|
| `connection_params` | `connection_params.dart` | 远控 URL 解析（`Uri`、`ZemoteConnectionParams`） |
| `device_info` | `device_info.dart` | 设备信息与平台名 |
| `proof` | `proof.dart` | 配对证明（HMAC-SHA256 + base64url 去 padding） |
| `crc32` | `crc32.dart` | IEEE CRC-32（查表） |
| `id` | `id.dart` | UUID / 请求 id（CSPRNG，随机源可注入） |
| `ipc_codec` | `ipc_codec.dart` | IPC 值编解码（通道 RPC 的真实线格式） |
| `rpc_transport` | `rpc_transport.dart` | 逻辑消息分片、CRC 校验、重组 |
| `relay_client` | `relay_client.dart` | 中继连接状态机（心跳、退避重连、关闭码） |
| `channel_client` | `channel_client.dart` | 通道 RPC 与事件分发 |
| `automation_client` | `automation_client.dart` | 定时任务管理 |
| `conversation` | `conversation.dart` | 对话状态机、增量、CAS 记账、订阅核心 |
| `conversation_transport` | `conversation.dart`（`ConversationTransport` 类） | 对话传输层：握手、订阅、看门狗、CAS 重试、行区间、附件分块上传 |
| `zemote_client` | `zemote_client.dart` | 集成层：把上面几层串成一次完整会话 |

移植契约（命名映射、硬性要求、已决事项）见 `rust/crates/protocol/PORTING.md` —— 那份文档同时是
后续并行移植的派单契约。

### 宿主能力怎么接（**零回调**）

核心不碰 I/O，于是把"需要外界做的事"抽象成 trait（`RelayTransport` / `FrameSink` /
`ChannelRpc` / `SecureRandom`）。但**Kotlin 不实现这些 trait** —— FFI 层在 Rust 侧用一个
**记录型 transport** 把它们记成事件、随返回值吐给宿主。于是：

- Kotlin 只需要处理 JSON 事件、喂回 JSON 输入，不碰任何 Rust 类型；
- FFI 面只有 6 个函数（`docs/FFI.md` §2），全是字符串进出，没有对象生命周期、没有回调线程、
  没有 JNI 局部引用管理；
- 代价是每次 socket 动作多一趟 JSON 序列化，相对网络往返可忽略。

定时器同理不进核心：核心只吐 `TimerStart`/`TimerCancel`，到点由宿主回喂 `TimerFired`。

### Kotlin 侧的分层

| 层 | 位置 | 职责 |
|---|---|---|
| 引擎 | `app/zemote/engine/` | 协程事件循环：执行 `ws_*` 动作、驱动两套命名空间的定时器、把 socket 与定时器事件回喂核心、按令牌解析挂起操作。**单线程调度**（契约要求同一 handle 不可重入） |
| 持久化 | `app/zemote/data/` | 设备列表与设置；配对凭据用 Keystore AES-GCM 加密落盘（内存明文、落盘密文） |
| 配对 | `app/zemote/pairing/` | URL 前置筛查（只查必要条件，权威校验在 Rust）、去重、扫码接口 |
| 更新 | `app/zemote/update/` | 版本清单、断点续传、MD5 完整性校验、同源签名校验、FileProvider 安装 |
| 表现层 | `app/zemote/ui/` | ViewModel + 手写导航状态机（见下「界面」） |

表现层的公开 API 见 `docs/ENGINE-API.md`；**界面样式本次未实现** —— `ui/` 下的屏幕只有结构骨架
与状态接线，需要视觉设计的位置都留了 `// TODO(UI):` 标记。

### 界面

`design/` 里的令牌与清单全部抽取自官方网页版快照，界面按它一比一复刻：

- `design/tokens/colors.json` —— 551 个颜色 token，明暗两套已解析成 sRGB hex（oklch 转换结果对照过真实 Chromium 引擎）
- `design/tokens/typography.json`、`design/tokens/layout.json` —— 字号阶梯（真实基准是应用私有的 `--ui-font-size: 14px`，不是 Tailwind 的 `--text-*`）、4px 间距栅格、圆角、动效、断点
- `design/ICONS.md` —— 实际使用的 247 个图标及线宽约定
- `design/SCREENS.md` —— 12 个视图的逐层结构
- `design/PROTOCOL-MAP.md` —— 消息条目、握手步骤、错误码族与 35 条待证实项

---

## 签名

使用**本项目专属**的 keystore：`scratch/zemote-release.p12`（PKCS12，别名 `zemote`，RSA 4096），
证书 SHA-256 `6A:4F:D8:7F:69:A1:D8:91:11:EE:6F:24:C6:8E:B7:5E:4C:19:79:31:E8:BE:A8:27:72:25:BA:6E:AE:06:6B:5B`。

**不复用 android-shell 项目的签名材料**：共用签名只在两个应用 `applicationId` 相同时才有意义，
而本项目的 `applicationId` 是 `app.zemote`。

签名材料与 `android/key.properties` 都在 `.gitignore` 里，**绝不入库**；密码只存在于本地文件与
GitHub Secrets，不进任何提交或日志。

CI 需要的仓库 Secrets：`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、
`ANDROID_KEY_PASSWORD`、`ANDROID_KEY_ALIAS`。

---

## CI

`.github/workflows/android.yml`（取代了旧的 Flutter 工作流）：

1. **`rust-test`** —— `cargo test --workspace`。Rust 侧全是纯逻辑，几秒钟就能挡掉回归。
2. **`build`** —— 交叉编译 Rust `.so` → 放进 `jniLibs` → 跑 Kotlin 单测 → `assembleRelease` →
   重命名 + MD5 + 生成版本清单 → 上传 artifact。其中两道硬门：
   - 打包前自检 `.so` 里存在 JNI 入口符号（符号名对不上时 APK 能装能起、一调就
     `UnsatisfiedLinkError`，而单测用的是假 bridge，发现不了）；
   - 配置了签名 Secrets 却产出 debug 签名，**直接失败**（宁可红，也不发一个用户装不上的包）。
3. **`prerelease`**（推 `main`）—— **滚动覆写**预发布 `android-pre`：资产名固定 + `--clobber`，
   下载链接始终指向最新构建。
4. **`release`**（推 `v*` tag）—— 正式 Release，正文取 `CHANGELOG.md` 的 `## [X.Y.Z]` 段；
   缺该段直接失败，不发空正文。

发布资产：`zemote-arm64-v8a.apk`、`zemote-arm64-v8a.apk.md5`、`zemote-version.json`
（后者是应用内更新读的版本清单，因为滚动发布的 tag 永远不变，不能靠 tag 判断新旧）。

版本号取自**构建时间**（UTC+8）：`versionCode` = `yyMMddHH`（时钟只向前走，保证每次构建都能覆盖安装
此前任何构建），`versionName` = `<VERSION>-dev.<yyyyMMdd.HHmm>`（分支构建）/ tag 版本（tag 构建）。
UTC+8 用 epoch + 28800 算术换算，不依赖 runner 的 tz 数据库。

只出 **arm64-v8a 单包**，所以不需要 `--split-per-abi` 那套每 ABI 的 versionCode 偏移。
