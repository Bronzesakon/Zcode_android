# 由于ZCode 在登录状态下会静默RSA打包整个工作区及完整Git 历史上传至云端对象存储，本项目宣布永久停更！

https://linux.do/t/topic/2917956

https://linux.do/t/topic/2917923

推荐使用[paseo](https://github.com/getpaseo/paseo)和[oh-my-pi](https://www.pi-gui.com/)，完美替代
---
<div align="center">

# Zemote

**手机/平板上的 ZCode 远程控制客户端** — 独立复刻官方 Web 远程控制协议（protocol reimplementation）

[![Flutter](https://img.shields.io/badge/Flutter-3.5%2B-blue.svg?logo=flutter)](https://flutter.dev)
[![Dart](https://img.shields.io/badge/Dart-3.5%2B-0175C2.svg?logo=dart)](https://dart.dev)
[![CI](https://github.com/HumanAILoop/zemote/actions/workflows/build-apk.yml/badge.svg)](https://github.com/HumanAILoop/zemote/actions/workflows/build-apk.yml)
[![Release](https://img.shields.io/github/v/release/HumanAILoop/zemote)](https://github.com/HumanAILoop/zemote/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20Web%20%7C%20Windows-lightgrey.svg)](#平台)

在手机/平板上**同时管理多台桌面设备**：扫码或粘贴链接添加设备 → 连接桌面 ZCode → 浏览任务列表、收发对话、查看文件变更、管理模型供应商与用量配额。

> ⚠️ 本项目为**协议复刻**，与官方客户端无任何代码关联。请遵守 ZCode 服务条款与当地法律法规，仅用于连接你自己的设备。

</div>

---

## 本机开发环境

> fork 本地实测（2026-10-16），记录本机（Windows）上实际可用的路径，方便直接复制命令。与上游项目本身无关。

| 用途 | 路径 | 说明 |
|------|------|------|
| Flutter SDK | `E:\AndroidSdk\flutter`（3.47.7 stable / Dart 3.13.5） | 2026-10-16 装在此处，刻意不占 C 盘；可执行文件在 `E:\AndroidSdk\flutter\bin\flutter.bat` |
| Android SDK | `E:\AndroidSdk` | `ANDROID_HOME` 与 `ANDROID_SDK_ROOT` 均已指向 |
| build-tools | `E:\AndroidSdk\build-tools\{34.0.0,35.0.0,36.0.0}` | 含 `apksigner`（验签用） |
| platforms | `E:\AndroidSdk\platforms\{android-35,android-37.0}` | 无 NDK、无模拟器、无 AVD |
| adb | `E:\AndroidSdk\platform-tools\adb.exe`（1.0.41） | 真机：`PLC110`（Android 17 / SDK 37 / arm64-v8a）、`192.168.0.1:5555` 的 `MU5358`（Android 15 / SDK 35 / arm64-v8a） |
| JDK | `E:\DevEco Studio\jbr\bin`（JBR 25，含 keytool/javac） | 本机唯一的 JDK，`JAVA_HOME` 为空；AGP 8.13.2 期望 JDK 17，本地构建若报 JDK 相关错误从这里查 |
| Gradle | wrapper 指定 9.1.0（首次构建自动下载） | `~/.gradle/wrapper/dists` 里已有 8.9 / 9.6.1 |
| 签名（本地） | `android/key.properties` → `scratch/zemote-release.p12`（别名 `zemote`） | `scratch/` 与 `key.properties` 都不入库；密码在 `scratch/keystore-password.txt`，模板见 `android/key.properties.example` |

本机构建与真机安装：

```bash
flutter build apk --release --target-platform android-arm64
E:\AndroidSdk\platform-tools\adb.exe install -r build/app/outputs/flutter-apk/app-release.apk
```

> 主要构建路径仍是 **GitHub Actions**（见[更新与签名](#更新与签名)）；本机环境用于调试与真机验证。

---

## 目录

- [反馈与 AI 自动修复](#反馈与-ai-自动修复)
- [功能特性](#功能特性)
- [平台](#平台)
- [本机开发环境](#本机开发环境)
- [快速开始](#快速开始)
- [更新与签名](#更新与签名)
- [架构](#架构)
- [测试](#测试)
- [项目结构](#项目结构)
- [安全提示](#安全提示)
- [技术栈](#技术栈)
- [贡献](#贡献)
- [Changelog](#changelog)
- [License](#license)

---

## 反馈与 AI 自动修复

**欢迎大家提交 [Issue](https://github.com/HumanAILoop/zemote/issues)！** 🎉

无论是 Bug 反馈、功能建议，还是界面 / 交互体验问题，都欢迎通过 Issue 提出。本项目采用 **AI 全自动修复** 流程：你提交的 Issue 会被自动分析、定位、修复，并随新的 Beta 版本发布，通常无需人工介入。

为了让自动修复更准确、更快，建议在 Issue 中尽量写清楚：

- **问题现象**：发生了什么，你期望发生什么
- **复现步骤**：怎样稳定复现
- **环境信息**：设备型号、Android 版本、App 版本（设置页可见）
- **相关日志 / 截图**：可在设置页「诊断中心 / 协议日志」中导出

提交后请留意 Issue 回复与新的 Beta Release。

---

## 功能特性

### 多设备并发
- 多台设备可同时在线，一键切换无需重连
- 设备列表独立显示每台的连接状态（连接中 / 已连接 / 错误）
- 顶部设备切换栏随时跳转，未连接设备可直接连接或添加新设备

### 添加设备
- 扫码（`mobile_scanner`）或粘贴远程控制 URL（`https://zcode.z.ai/remote/v4?sid=...&hash=...&t=...`）

### 任务管理
- 任务 / 置顶 / 已归档三栏，搜索过滤、下拉刷新、滑动置顶/归档、重命名、删除
- 实时订阅 `sessions-index`，与 workspace-list 推送合并去重
- 未读标记、最近预览文本、运行状态点

### 对话（Conversation V4）
- 流式回复、推理过程展示、斜杠命令（`/`）、Skills（`$`）、模型/模式/思考级别切换
- **辅助对话**：为当前会话开启独立侧对话（`createSelectionSideSession`），互不干扰并行提问
- 排队消息管理、目标（goal）指令、暂停/恢复
- 附件上传/预览、文件变更 diff、文件回滚、编辑用户消息、点赞/点踩

### 协议层
完整复刻官方 relay 握手、配对证明（HMAC-SHA256）、rpc-frame 分片传输（CRC32 校验 + ack）、IPC 值编解码、V4 快照/增量协议。

### 调试工具
- 协议日志页（relay / IPC / V4 帧）
- RPC（relay payload）调试器
- Channel RPC 调试器

### 其他
- 模型供应商管理（添加/启停/删除）、用量/配额/订阅查看
- 浅色/深色/跟随系统主题、字体缩放、中英双语
- **更新检测**：启动自动检查 GitHub 最新发布，Android 端一键下载 APK 并调用系统安装器升级
- **后台任务通知（Android）**：任务运行中时通知栏静默常驻并实时更新最新进展；任务完成静默提醒（不弹窗打扰）；点击通知直达对应对话

## 平台

| 平台 | 状态 |
|------|------|
| Android | ✅ 主要目标平台 |
| Web | ✅ 可用（调试 / 快速预览） |
| Windows | ⚙️ 桌面端可用（未重点优化） |
| iOS / macOS / Linux | 未验证（理论上可构建） |

## 快速开始

### 前置条件

- Flutter SDK（本项目 `sdk: ^3.5.0`）
- 桌面端已安装并打开 **ZCode**（zcode.z.ai）

### 获取远程控制 URL

桌面 ZCode → 远程控制 → 生成二维码 / 复制链接，形如：

```
https://zcode.z.ai/remote/v4?sid=...&hash=...&t=...&mid=...&name=...
```

### 运行

```bash
# Android
flutter run

# Web（快速预览）
flutter run -d chrome

# Windows
flutter run -d windows
```

应用内：**添加设备** → 扫码或粘贴 URL → 连接桌面 → 完成配对 → 进入任务列表。

### 构建 APK

```bash
flutter build apk --release --target-platform android-arm64
# 产物：单个 arm64-v8a APK（build/app/outputs/flutter-apk/app-release.apk）
# 本项目只发布 arm64-v8a；armeabi-v7a / x86_64 设备与模拟器无法安装。
```

## 更新与签名

- 应用内置**更新检测**：按设备 CPU 自动选择 APK，支持断点续传、缓存安装包直装、MD5 校验和安装完成后自动清理。
- 发布流程（`.github/workflows/flutter-android.yml`）：
  - **日常**：推 `main` → Actions 跑 `flutter analyze` + `flutter test` + web 编译冒烟，构建 arm64-v8a 的签名 APK 与 MD5，并**滚动覆写**预发布 `android-pre`。资产名固定，下载链接始终指向最新构建（如 `…/releases/download/android-pre/zemote-arm64-v8a.apk`）。
  - **正式**：`git tag vX.Y.Z` → 推送 → 发布正式 Release，说明取自 `CHANGELOG.md` 的 `## [X.Y.Z]` 段（缺该段会直接失败，不会发出空正文）。
  - `versionCode` 取构建时间（UTC+8，`yyMMddHH`），保证每次都能覆盖安装此前任何构建；构建不启用 `--split-per-abi`，因此不会叠加 Flutter 的每 ABI 偏移。
- 签名说明：release APK 使用本项目**专属**的 keystore 签名（本地 `android/key.properties` → `scratch/zemote-release.p12`，别名 `zemote`，PKCS12；`scratch/` 不入库），CI 用 GitHub Secrets 里同一份 `.p12` 的 base64。签名材料刻意不与 android-shell、也不与上游 zemote 共用——共用签名只对同一个 applicationId 有意义，而本应用的包名 `app.zemote` 是独立的。
- CI 需要仓库 Secrets：`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_PASSWORD`、`ANDROID_KEY_ALIAS`（本仓库已设置）。未设置时构建仍会成功，但回退 debug 签名并给出告警——这样的 APK 无法覆盖安装正式签名版本。
- **换签名＝换应用身份**：一旦发布过用某份密钥签名的 APK，换密钥后已装设备无法覆盖安装（需卸载重装）。轮换密钥请同时更新 `scratch/` 本地材料与上述 4 个 Secrets。

## 架构

```
┌────────────────────────────────────────────────────────────┐
│ UI (lib/ui)     设备列表 / 任务 / 对话 / 设置 / 调试器        │
├────────────────────────────────────────────────────────────┤
│ State (lib/state)  AccountStore · AppSession · LogStore    │
├────────────────────────────────────────────────────────────┤
│ Facade (lib/protocol/zemote_client.dart)                    │
│   relay → 配对 → bootstrap → workspace-bridge → channel RPC │
├────────────────────────────────────────────────────────────┤
│ Protocol (纯 Dart，可单测)                                  │
│   RelayClient · RpcFrameTransport · ChannelClient           │
│   IpcCodec · Conversation(V4) · Proof(HMAC-SHA256)          │
└────────────────────────────────────────────────────────────┘
```

协议分层示意：

| 层 | 职责 |
|---|---|
| `connection_params.dart` | 远程控制 URL 解析 + relay WS 地址 |
| `proof.dart` | 配对证明（HMAC-SHA256 / base64url） |
| `relay_client.dart` | relay WebSocket 连接 + 心跳 + 重连 |
| `rpc_transport.dart` | rpc-frame 分片/重组/CRC32 校验/ack |
| `ipc_codec.dart` | 值编解码 + 13 字节 IPC 帧解析 |
| `channel_client.dart` | IPC channel RPC 调用/事件订阅 |
| `conversation.dart` | Conversation V4 / sessions-index 快照+增量 |
| `zemote_client.dart` | 高层门面：bootstrap / bridge / 断线恢复 |

## 测试

```bash
# 单元测试（协议编解码 / 状态机 / delta 应用 / 更新检测等）
flutter test

# 集成测试：需要真实桌面 + 自己的远程控制 URL，通过环境变量注入
$env:ZEMOTE_PROBE_URL="https://zcode.z.ai/remote/v4?sid=..."
flutter test integration_test
```

## 项目结构

```
lib/
├── main.dart                 # 应用入口 + 主题/字号注入 + 启动更新检测
├── protocol/                 # ZCode 协议复刻（纯 Dart，可单测）
├── state/                    # 应用状态（多设备账号 / 连接管理 / 日志）
├── ui/                       # 界面（设备 / 任务 / 对话 / 设置 / 调试器）
└── update/                   # 更新检测 + Android 下载安装
test/                         # 单元测试
integration_test/             # 对真实桌面的集成测试（需 ZEMOTE_PROBE_URL）
```

## 安全提示

- 远程控制 URL 包含设备凭据（`sid` / `hash`），相当于设备的访问凭证，**切勿提交到版本库或分享给他人**。
- 集成测试通过环境变量 `ZEMOTE_PROBE_URL` 注入 URL，不写死在测试代码里；`.gitignore` 已忽略 `.env`、`*.remote.*`、签名文件（`*.jks` / `key.properties`）等。
- 如凭据意外泄露，请在桌面端 ZCode 中重新生成远程控制二维码（旧凭据立即失效）。

## 技术栈

- [Flutter](https://flutter.dev) / Dart
- [web_socket_channel](https://pub.dev/packages/web_socket_channel) — relay 长连接
- [crypto](https://pub.dev/packages/crypto) — HMAC-SHA256 配对证明
- [mobile_scanner](https://pub.dev/packages/mobile_scanner) — 扫码
- [zxing2](https://pub.dev/packages/zxing2) — 纯 Dart 二维码解码（图片识别）
- [shared_preferences](https://pub.dev/packages/shared_preferences) — 账号持久化
- [image_picker](https://pub.dev/packages/image_picker) / [file_picker](https://pub.dev/packages/file_picker) — 附件
- [flutter_markdown](https://pub.dev/packages/flutter_markdown) — Markdown 渲染
- [http](https://pub.dev/packages/http) — 更新检测（GitHub Releases API）

## 贡献

欢迎提交 [Issue](https://github.com/HumanAILoop/zemote/issues) 与 Pull Request。

- **提交 Issue**：最省事的方式。问题会被 **AI 全自动分析并修复**，详见 [反馈与 AI 自动修复](#反馈与-ai-自动修复)。
- **提交 Pull Request**：贡献代码前请确保：
  1. `flutter analyze` 无告警
  2. `flutter test` 全部通过
  3. 集成测试改动需注明需要真实桌面环境

## Changelog

版本变更记录见 [CHANGELOG.md](CHANGELOG.md)。

## License

[MIT](LICENSE)

## 免责声明

本项目为个人学习与互操作目的，对 ZCode 远程控制协议的独立复刻，非官方出品。使用者须自行承担风险与合规责任。
