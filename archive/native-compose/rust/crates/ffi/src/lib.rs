//! FFI 绑定层：把**纯逻辑**的协议核心（`zemote-protocol`）以「JSON 进 / JSON 出」的方式
//! 暴露给 Kotlin 宿主。
//!
//! **契约是 `docs/FFI.md`（冻结）。** 本 crate 逐条实现它：Rust 侧与 Kotlin 侧各自照文档实现，
//! 两边都不需要看对方代码就能对齐。改任何 schema 都要 +1 `abi`。
//!
//! ## 设计要点（§1）
//!
//! * **零回调**：Kotlin 不实现任何 Rust trait。协议 crate 的 `RelayTransport` 由
//!   [`RecordingTransport`] 实现——它不碰网络，只把 `connect` / `send_text` / `close`
//!   记下来，随 `step()` 的返回值当事件吐给宿主（§1.1、§7.1）。
//! * **宿主驱动全部 I/O 与时钟**：WebSocket、定时器、持久化都在 Kotlin 侧；核心是纯状态机，
//!   时间一律由宿主传入 `nowMs`（§1.2）。
//! * FFI 面全是 `String`（JSON）进、`String`（JSON）出：没有对象生命周期、没有回调线程、
//!   没有 JNI 局部引用管理。
//!
//! ## 模块
//!
//! | 模块 | 职责 | 契约 |
//! |---|---|---|
//! | [`codec`] | JSON ↔ Rust 类型映射、信封、事件序列化 | §3–§9、§13.4/§13.5.2 |
//! | [`recording_transport`] | 记录型 `RelayTransport`（§1.1 的核心） | §1.1、§7.1 |
//! | [`conversation`] | 对话层与定时任务层的接线（token 重映射、事件路由、超时清扫） | §13 |
//! | [`session`] | 一个 handle 的编排（create/step/call/query/dispose/snapshot） | §5、§6、§8、§9、§10 |
//! | [`registry`] | 全局 handle 注册表（`static` + `Mutex`，锁中毒可恢复） | §11 |
//! | [`jni_bridge`] | 6 个 JNI 入口 + `catch_unwind` | §2、§12 |
//!
//! ## §13
//!
//! 对话层与定时任务层（`conversation_open` / `conversation_call` / `conversation_close` /
//! `automation_call`）已接入，见 [`conversation`]。**`nativeStep` 的输入表不变**（仍是 §6
//! 那一套）：§13 需要的宿主能力（`add_listener` / `cancel_listener` / `bridge_wait_healthy` /
//! 通道调用超时）全在 Rust 侧完成——宿主按契约拿不到桥 `ChannelClient` 的内部 id。
//!
//! ## 测试
//!
//! 全部测试都在主机侧跑，**不经过 JNI**（`jni_bridge` 的入口只做搬运，业务在 [`registry`]）：
//!
//! ```text
//! cd rust && cargo test -p zemote-ffi
//! ```

pub mod codec;
pub mod conversation;
pub mod jni_bridge;
pub mod recording_transport;
pub mod registry;
pub mod session;

pub use codec::BadInput;
pub use conversation::ConversationLayer;
pub use recording_transport::{Action, RecordingTransport};
pub use registry::{abi_version, call, create, dispose, query, step};
pub use session::Session;
