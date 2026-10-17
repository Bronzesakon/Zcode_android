//! ZCode 远程控制协议（Rust 实现）
//!
//! 本 crate 是 `Reference_code/lib/protocol/*.dart` 的忠实移植。那份 Dart 实现是对
//! 远程控制网页协议逆向的结果，**行为以它为准**：线上格式一个字节都不能变。
//!
//! ## 边界
//! **纯逻辑、同步、无 I/O**。WebSocket、存储、计时、权限等宿主能力由 Kotlin 侧实现；
//! 需要宿主的地方在本 crate 里只定义 trait，实现留给宿主。
//!
//! ## 移植契约
//! 命名与类型映射、硬性要求、交付方式见 `PORTING.md`。
//!
//! ## 怎么导入
//! 模块按 Dart 源文件一一对应（`relay_client.dart` → [`relay_client`]），需要精确定位某个
//! 函数时直接按模块导入。跨模块共用的类型与工具用 [`prelude`]，那是唯一的公共导入面。
//!
//! ## 主线收口时定下的两件事（都有证据，别再翻案）
//!
//! ### 1. 不启用 `serde_json` 的 `preserve_order`
//! 多个模块的 `PORT-NOTE` 提到：Dart 的 `Map` 保插入序，而 `serde_json` 默认按键字典序，
//! 因此我们序列化出的 JSON 键序与 Dart 不同。核实结论是**不影响协议正确性**，故不引入
//! `indexmap` 依赖：
//! - 涉及键序的只有两处。其一是错误消息/日志文本（[`channel_client::dart_to_string`]、
//!   [`channel_client::dart_json_to_string`]，调用点就是 `ChannelRpcError` 的消息构造），
//!   纯展示。
//! - 其二是线上字节：[`rpc_transport`] 的 `checksum` 是**发送方对自己序列化出的那份字节**
//!   算的（Dart `RpcFrameTransport.sendMessage` 的 `Crc32.hexOf(bytes)`），接收方对**自己
//!   收到的字节**重算（`acceptPayload` 里的 `Crc32.hexOf(message)`）——两边算的是同一串
//!   字节，自洽，与键序无关。对端只做 JSON 解析，不会拿我们的字节去重新序列化比对。
//! - [`proof::calculate_proof`] 的 HMAC 输入是 `'$nonce|$role|$deviceSid'` 这种拼接字符串，
//!   与 JSON 无关。
//!
//! ### 2. [`ipc_codec::IpcFraming`] / [`ipc_codec::IpcFrameParser`] 在应用内没有调用点
//! 全仓核实：这两个类型（13 字节 IPC 帧壳）在 `Reference_code/lib/` 里只出现在自己的定义处，
//! 唯一的消费者是 `test/ipc_codec_test.dart`；桥接路径明确不走它
//! （`zemote_client.dart:328-329`）。同文件的 [`ipc_codec::ValueWriter`] /
//! [`ipc_codec::ValueReader`] / [`ipc_codec::encode_value`] / [`ipc_codec::decode_value`]
//! **是活的**，`channel_client.dart:36,56` 正在用，那才是通道 RPC 的真实线格式。
//! 因此：值编解码器是核心路径，帧壳保留但只作为协议完整性参考与测试对象，**不要**据此
//! 去接桥接层。

pub mod error;

pub mod automation_client;
pub mod channel_client;
pub mod connection_params;
pub mod conversation;
pub mod conversation_transport;
pub mod crc32;
pub mod device_info;
pub mod id;
pub mod ipc_codec;
pub mod proof;
pub mod relay_client;
pub mod rpc_transport;
pub mod zemote_client;

pub use error::{ProtocolError, Result};

/// 跨模块共用的类型与工具。
///
/// 这些项**没有搬家**，只是从各自模块再导出——模块与 Dart 源文件的一一对应关系是移植的
/// 可追溯性基础（`PORTING.md` 硬性要求 7），为了"看起来更整齐"去重排 13k 行不划算。
/// 需要精确对应某个 Dart 函数时仍可按模块导入。
pub mod prelude {
    pub use crate::error::{ProtocolError, Result};

    // 连接与设备身份
    pub use crate::connection_params::{Uri, ZemoteConnectionParams};
    pub use crate::device_info::{zemote_platform_name, DeviceInfo, TargetPlatform};

    // 线格式：IPC 值模型与编解码（通道 RPC 的真实格式）
    pub use crate::ipc_codec::{
        decode_from_slice, decode_value, encode_to_vec, encode_value, Value, ValueReader,
        ValueWriter,
    };

    // JSON 与 Dart 动态值之间的投影（通道调用与错误消息共用）
    pub use crate::channel_client::{
        dart_json_to_string, dart_to_string, json_as_dart_int, to_dart_view, ChannelRpc, Channels,
    };

    // 对话层与宿主共用的 JSON 映射别名（Dart 里就是 `Map<String, dynamic>`）
    pub use crate::conversation::JsonMap;

    // 随机源：可注入，便于测试与宿主替换
    pub use crate::id::{OsSecureRandom, SecureRandom};

    // 握手证明
    pub use crate::proof::calculate_proof;

    // RPC 分帧与重组
    pub use crate::rpc_transport::{FrameSink, RpcFramePayload, RpcFrameTransport};
}
