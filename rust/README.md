# rust/ —— ZCode 远程控制协议（Rust 核心）

对 `Reference_code/lib/protocol/*.dart` 的忠实移植。那份 Dart 实现是对远程控制网页协议**逆向**的结果，
是本 crate 行为的唯一准绳。

## 结构

```
rust/
├── Cargo.toml                     # workspace
└── crates/protocol/               # zemote-protocol
    ├── Cargo.toml
    ├── PORTING.md                 # ★ 移植契约（命名映射、边界、硬性要求）
    └── src/
        ├── lib.rs                 # 模块声明 + 边界说明
        ├── error.rs               # 统一错误类型 ProtocolError
        └── *.rs                   # 每个文件对应一个 Dart 文件
```

## 边界（决定架构的那条）

`zemote-protocol` 是**纯逻辑、同步、无 I/O** 的 crate：不做网络、文件、定时器，不用 async。
WebSocket / 存储 / 计时 / 权限等由 Kotlin 宿主实现；需要宿主能力处本 crate 只定义 trait。
好处是 FFI 边界最小（UniFFI 好接）、单测不需要任何运行时。

## 本机开发

```bash
cd rust
cargo test -p zemote-protocol          # 单测（不需要 Android SDK）
cargo clippy -p zemote-protocol        # 静态检查
```

本机已配置 crates.io 走清华 TUNA 稀疏索引（`~/.cargo/config.toml`，直连很慢；撤销即删该文件）。

## Android 交叉编译（arm64-v8a）

工具链已就绪：target `aarch64-linux-android`、`cargo-ndk 4.1.2`、
NDK `28.2.13676358`（`E:\AndroidSdk\ndk`）。

```bash
cd rust
# 产出 libzemote_protocol.so（arm64-v8a）
cargo ndk -t arm64-v8a -o ../android/app/src/main/jniLibs build --release -p zemote-protocol
```

CI 里**不用** cargo-ndk，而是显式把 NDK 的 clang 交给 cargo（cargo-ndk 做的事就是这几个变量，
只编一个目标时少一个活动部件）：

```bash
toolchain="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
export CC_aarch64_linux_android="$toolchain/aarch64-linux-android26-clang"
export AR_aarch64_linux_android="$toolchain/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$toolchain/aarch64-linux-android26-clang"
cargo build --release --target aarch64-linux-android -p zemote-protocol
```

（`26` 是 minSdk，clang wrapper 的名字带 API level。Windows 本机上对应文件带 `.cmd` 后缀，
所以本地还是用 cargo-ndk 更省事。）

约定：
- 只出 **arm64-v8a** 单 ABI（与旧项目发布约定一致；现有真机都是 arm64）。
- `release` profile：`lto = "thin"` / `codegen-units = 1` / `strip = "symbols"`。
  **刻意不设 `panic = "abort"`** —— 解析网络来的字节，任何一处越界或 unwrap 都会 panic；
  保持 unwind 才能让 FFI 层用 `catch_unwind` 把它转成 Java 异常，而不是让整个应用 SIGABRT。
  代价是二进制约大几十 KB。
- `crate-type` 含 `cdylib`（给 Android）与 `rlib`（给宿主测试）。
- **当前 `.so` 还没有 `extern "C"` 入口**：FFI 层尚未编写，所以现在打进 APK 的 `.so` 是个空壳
  （`llvm-nm --dynamic` 无符号）。写 JNI 入口时记得每个都包 `catch_unwind`。
