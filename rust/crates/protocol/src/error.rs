//! 本 crate 的统一错误类型。
//!
//! Dart 侧用 `FormatException` / `StateError` / `ArgumentError` 抛错；Rust 侧统一收成
//! [`ProtocolError`]，移植时按下表对应，**不要把 panic 当错误路径**（除了明确的不变量违反）。

/// 协议层错误。
#[derive(Debug, thiserror::Error)]
pub enum ProtocolError {
    /// 帧/载荷格式不合法（对应 Dart 的 `FormatException`）。
    #[error("invalid frame: {0}")]
    InvalidFrame(String),

    /// 读到未知的值类型标签（对应 `Sm()`/`Cm()` 的未知 tag 分支）。
    #[error("unsupported value tag: {0}")]
    UnsupportedTag(u8),

    /// 数据不足（对应 ValueReader 越界时的 `FormatException`）。
    #[error("truncated: needed {needed} bytes, {remaining} remaining")]
    Truncated { needed: usize, remaining: usize },

    /// 超过 Dart 侧写死的上限（如 `maxContainerItems` / `maxValueBytes`）。
    #[error("limit exceeded: {0}")]
    LimitExceeded(String),

    /// 状态机不接受该输入（对应 Dart 的 `StateError`）。
    #[error("invalid state: {0}")]
    InvalidState(String),

    /// JSON 解析/序列化失败（对应 Dart 的 `jsonDecode` 抛错）。
    #[error("json: {0}")]
    Json(#[from] serde_json::Error),

    /// 字节不是合法 UTF-8。
    #[error("utf8: {0}")]
    Utf8(#[from] std::string::FromUtf8Error),

    /// 其它（移植时如果 Dart 的错误语义不属于上面任何一类，用这个并写明）。
    #[error("{0}")]
    Other(String),
}

/// 本 crate 的 Result 别名。
pub type Result<T> = std::result::Result<T, ProtocolError>;
