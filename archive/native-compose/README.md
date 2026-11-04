# native-compose（已归档）

> **状态：已弃用，仅作参考与迁移来源。** 2026-11-04 起，`Zcode_android` 的主线是
> WebView 薄壳；本目录保存原「Rust 协议核心 + Kotlin/Compose 界面」的最终状态。

- 归档 tag：`native-compose-final`（对应提交 `c69601f`）
- `android/`：原 Compose 应用（`app.zemote`）
- `rust/`：原 Rust 协议核心（只保留源码，不含 `target/`）
- `docs/`：原原生 UI/引擎文档（UI-SPEC、CHAT-PAGE-SPEC、audit、chat 等）
- `design/`：原设计令牌与界面规范
- `.github/`：原 Rust/Compose CI（仅存档，不参与当前构建）
- `scratch/`：原签名材料与网页逆向缓存（本地-only，不入 Git）

## 构建（如确需复现）

需要 JDK 17、Android SDK 36、NDK `28.2.13676358`：

```bash
cd archive/native-compose/android
./gradlew :app:assembleDebug
```

Rust 侧在 `archive/native-compose/rust`，按原 CI 的方式交叉编译为 arm64-v8a。
当前仓库的 CI **不构建本目录**。
