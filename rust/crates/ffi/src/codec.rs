//! JSON ↔ Rust 类型的双向映射（契约 `docs/FFI.md` §3–§9）。
//!
//! 本模块是 FFI 面上**唯一**懂 JSON 形状的地方：入参（§5/§6/§8/§9）在这里解析成协议 crate 的
//! 类型，出参（§3/§4/§7）在这里序列化成 `serde_json::Value`。`session.rs` 只负责编排。
//!
//! ## 命名规则（§7 开头）
//!
//! * Rust 枚举变体名 → `snake_case` 作为 `type`；
//! * 字段名 → `camelCase`；
//! * `Value` / `Payload` / `Map` 原样内联为 JSON 对象；
//! * `Option::None` → `null`，**键不省**（§3）。
//!
//! ## 为什么不省略键
//!
//! §3 明确要求「`Option::None` 一律序列化成 `null`，不跳过字段」，于是 Kotlin 侧解析不需要
//! 处理缺键。实现上因此**全部**用 [`serde_json::json!`] 显式构造，不依赖
//! `#[serde(skip_serializing_if)]`（那个宏是省键的，正好是反的）。

use std::fmt;

use base64::{engine::general_purpose::STANDARD, Engine as _};
use serde_json::{json, Map, Value};

use zemote_protocol::channel_client::ChannelEvent;
use zemote_protocol::conversation_transport::{
    Completion as ConvCompletion, ConversationEvent, TransportError, TransportErrorKind,
};
use zemote_protocol::prelude::to_dart_view;
use zemote_protocol::relay_client::{RelayEvent, RelayInput, RelayTimer};
use zemote_protocol::zemote_client::{
    BridgeSessionId, Completion, TimerId, ZemoteError, ZemoteEvent,
};

use crate::recording_transport::Action;

// ===========================================================================
// 1. 常量与信封（§3 / §4）
// ===========================================================================

/// 契约版本号。**改任何 schema 就 +1**（§4）。
pub const ABI: u64 = 1;

/// 本 .so 的版本（§4 的 `protocolVersion`）。
pub const PROTOCOL_VERSION: &str = env!("CARGO_PKG_VERSION");

/// §4 的 `features`：列出编译进来的可选能力。
///
/// §13 的对话层（`conversation_open` / `conversation_call` / `conversation_close`）与定时任务层
/// （`automation_call`）已接入，故两项都如实列出——Kotlin 侧据此判断该 .so 是否包含对话层。
pub const FEATURES: &[&str] = &["conversation", "automation"];

/// 入参不合法（JSON 解析失败、缺字段、字段类型不符、未知判别式）。
///
/// 一律对应 §3 的 `error.kind = "badInput"`，**不 panic**（§12）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BadInput {
    /// 面向开发者的说明（会进 `error.message`）。
    pub message: String,
}

impl BadInput {
    /// 构造。
    pub fn new(message: impl Into<String>) -> Self {
        BadInput {
            message: message.into(),
        }
    }
}

impl fmt::Display for BadInput {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.message)
    }
}

impl std::error::Error for BadInput {}

/// §4：`nativeAbiVersion()` 的返回值。
///
/// `events` 键按 §3 的约定**始终存在**（这里是空数组）——该方法只读版本，不驱动核心。
pub fn abi_envelope() -> Value {
    json!({
        "ok": true,
        "events": [],
        "abi": ABI,
        "protocolVersion": PROTOCOL_VERSION,
        "features": FEATURES,
    })
}

/// §3：`{"ok":true,"events":[...]}`。
pub fn ok_events(events: Vec<Value>) -> Value {
    json!({ "ok": true, "events": events })
}

/// §5：`{"ok":true,"handle":N,"events":[...]}`。
pub fn ok_handle(handle: u64, events: Vec<Value>) -> Value {
    json!({ "ok": true, "handle": handle, "events": events })
}

/// §5 的 `"dryRun": true` 信封：只解析、不建会话、不占 handle。
///
/// `handle` 是显式的 `null`（不是省略键，§3）；`events` 恒为空数组——dryRun 不驱动任何状态机。
pub fn ok_dry_run(parsed: Value) -> Value {
    json!({
        "ok": true,
        "dryRun": true,
        "handle": null,
        "events": [],
        "parsed": parsed,
    })
}

/// §8：`{"ok":true,"operation":<字符串或 null>,"events":[...]}`。
pub fn ok_operation(operation: Option<String>, events: Vec<Value>) -> Value {
    json!({ "ok": true, "operation": operation, "events": events })
}

/// §9：`{"ok":true,"snapshot":{...}}`。
pub fn ok_snapshot(snapshot: Value) -> Value {
    json!({ "ok": true, "snapshot": snapshot })
}

/// §3：失败信封。`events` **始终存在**（出错前已产生的事件不能丢）。
pub fn err(kind: &str, message: impl Into<String>, events: Vec<Value>) -> Value {
    json!({
        "ok": false,
        "error": { "kind": kind, "message": message.into() },
        "events": events,
    })
}

/// `badInput` 的简写（大多数错误路径没有事件）。
pub fn bad_input(message: impl Into<String>) -> Value {
    err("badInput", message, Vec::new())
}

/// §12：panic 信封。
///
/// `message` 只放 panic 载荷的文本；不额外附路径/内部结构（§12 要求用户可见文案收敛）。
pub fn panic_envelope(message: impl Into<String>) -> Value {
    err("panic", message, Vec::new())
}

/// `ZemoteError` → §3 的 `error.kind`。
pub fn zemote_error_kind(error: &ZemoteError) -> &'static str {
    match error {
        ZemoteError::Timeout(_) => "timeout",
        ZemoteError::InvalidState(_) => "invalidState",
        ZemoteError::Protocol(_) => "protocol",
    }
}

/// `ZemoteError` → §3 的 `error.message`（取内部文本，不带 `Display` 的前缀）。
pub fn zemote_error_message(error: &ZemoteError) -> String {
    match error {
        ZemoteError::Timeout(text)
        | ZemoteError::InvalidState(text)
        | ZemoteError::Protocol(text) => text.clone(),
    }
}

/// `ZemoteError` → 失败信封（无事件）。
pub fn zemote_error_envelope(error: &ZemoteError) -> Value {
    err(
        zemote_error_kind(error),
        zemote_error_message(error),
        Vec::new(),
    )
}

// ===========================================================================
// 2. 出参：事件序列化（§7）
// ===========================================================================

/// §7.1：传输动作 → 事件。顺序即核心调用顺序（见 `recording_transport` 的模块文档）。
pub fn actions_to_json(actions: &[Action]) -> Vec<Value> {
    actions.iter().map(action_to_json).collect()
}

/// §7.1 单条动作。
pub fn action_to_json(action: &Action) -> Value {
    match action {
        Action::Connect { generation, uri } => json!({
            "type": "ws_connect",
            "generation": generation,
            "uri": uri,
        }),
        Action::SendText { generation, text } => json!({
            "type": "ws_send_text",
            "generation": generation,
            "text": text,
        }),
        Action::Close {
            generation,
            code,
            reason,
        } => json!({
            "type": "ws_close",
            "generation": generation,
            "code": code,
            "reason": reason,
        }),
    }
}

/// §7：门面事件列表 → JSON 事件列表（按原顺序）。
pub fn events_to_json(events: &[ZemoteEvent]) -> Vec<Value> {
    events.iter().map(event_to_json).collect()
}

/// §7.3 单条门面事件。
///
/// [`ZemoteEvent::Relay`] **不额外包一层**：§7.2 的 relay 事件与 §7.3 的门面事件同处一个
/// `events` 数组，宿主按 `type` 判别。
pub fn event_to_json(event: &ZemoteEvent) -> Value {
    match event {
        ZemoteEvent::Relay(relay) => relay_event_to_json(relay),
        ZemoteEvent::Log(line) => json!({ "type": "log", "line": line }),
        ZemoteEvent::TimerStart { token, delay_ms } => json!({
            "type": "timer_start",
            "token": token.0,
            "delayMs": delay_ms,
        }),
        ZemoteEvent::TimerCancel(token) => json!({
            "type": "timer_cancel",
            "token": token.0,
        }),
        ZemoteEvent::WorkspaceListUpdated(value) => json!({
            "type": "workspace_list_updated",
            "value": value,
        }),
        ZemoteEvent::BridgeDegraded { session, degraded } => json!({
            "type": "bridge_degraded",
            "session": session.0,
            "degraded": degraded,
        }),
        ZemoteEvent::AppError { reason, error } => json!({
            "type": "app_error",
            "reason": reason,
            "error": error,
        }),
        ZemoteEvent::BridgeRecovered { session, count } => json!({
            "type": "bridge_recovered",
            "session": session.0,
            "count": count,
        }),
        ZemoteEvent::BridgeStackAttached { session } => json!({
            "type": "bridge_stack_attached",
            "session": session.0,
        }),
        ZemoteEvent::BridgeStackDisposed { session } => json!({
            "type": "bridge_stack_disposed",
            "session": session.0,
        }),
        ZemoteEvent::ConversationOpened {
            session,
            key,
            scope,
        } => json!({
            "type": "conversation_opened",
            "session": session.0,
            "key": key,
            "scope": scope,
        }),
        ZemoteEvent::Channel(channel_event) => json!({
            "type": "channel",
            "event": channel_event_to_json(channel_event),
        }),
        ZemoteEvent::Completed { token, outcome } => json!({
            "type": "completed",
            "token": token,
            "outcome": completion_to_json(outcome),
        }),
    }
}

/// §7.2：relay 层事件。
pub fn relay_event_to_json(event: &RelayEvent) -> Value {
    match event {
        RelayEvent::StateChanged(state) => json!({
            "type": "relay_state",
            "state": state.as_str(),
        }),
        RelayEvent::Log(line) => json!({ "type": "relay_log", "line": line }),
        RelayEvent::Payload(payload) => json!({
            "type": "relay_payload",
            "payload": payload,
        }),
        RelayEvent::Failure(failure) => json!({
            "type": "relay_failure",
            "reason": failure.reason,
            "message": failure.message,
        }),
        RelayEvent::TimerStart {
            timer,
            delay_ms,
            repeat,
        } => json!({
            "type": "relay_timer_start",
            "timer": relay_timer_name(*timer),
            "delayMs": delay_ms,
            "repeat": repeat,
        }),
        RelayEvent::TimerCancel(timer) => json!({
            "type": "relay_timer_cancel",
            "timer": relay_timer_name(*timer),
        }),
    }
}

/// §7.4：`channel.event`。
pub fn channel_event_to_json(event: &ChannelEvent) -> Value {
    match event {
        ChannelEvent::PromiseSuccess { id, data } => json!({
            "type": "promise_success",
            "id": id,
            "data": to_dart_view(data),
        }),
        ChannelEvent::PromiseError { id, error } => json!({
            "type": "promise_error",
            "id": id,
            "error": {
                "message": error.message,
                "data": to_dart_view(&error.data),
            },
        }),
        ChannelEvent::PromiseErrorObj { id, error } => json!({
            "type": "promise_error_obj",
            "id": id,
            "error": {
                "message": error.message,
                "data": to_dart_view(&error.data),
            },
        }),
        ChannelEvent::EventFire { id, data } => json!({
            "type": "event_fire",
            "id": id,
            "data": to_dart_view(data),
        }),
    }
}

/// §7.5：`completed.outcome`。
pub fn completion_to_json(outcome: &Completion) -> Value {
    match outcome {
        Completion::Payload(value) => json!({ "kind": "payload", "value": value }),
        Completion::BridgeOpened(session) => json!({
            "kind": "bridge_opened",
            "session": session.0,
        }),
        Completion::Ok => json!({ "kind": "ok" }),
        Completion::Failed(error) => json!({
            "kind": "failed",
            "error": {
                "kind": zemote_error_kind(error),
                "message": zemote_error_message(error),
            },
        }),
    }
}

/// `RelayTimer` → §7.2 的 `timer` 取值。
///
/// 协议 crate 没给 `RelayTimer` 提供 `as_str`（只有 `RelayState` 有），故在此显式映射。
pub fn relay_timer_name(timer: RelayTimer) -> &'static str {
    match timer {
        RelayTimer::Heartbeat => "heartbeat",
        RelayTimer::Waiting => "waiting",
        RelayTimer::Reconnect => "reconnect",
        RelayTimer::Rewait => "rewait",
    }
}

// ===========================================================================
// 2.1 §13 出参：对话层事件（§13.4 / §13.5.2）
// ===========================================================================
//
// §13 的事件形状与 §7 不同：**类型名是 camelCase**（`timerStart` / `opAccepted` /
// `attachmentProgress` …），且 `completed.outcome` 用 `type` 判别（§13.5.2 的表）。
// 那两张表是冻结契约的一部分，逐字实现，不套用 §7 的 snake_case 规则。

/// §13.4：把一条对话事件包成宿主可见的 `conversation_event`。
pub fn conversation_event_envelope(session: BridgeSessionId, key: &str, body: Value) -> Value {
    json!({
        "type": "conversation_event",
        "session": session.0,
        "key": key,
        "event": body,
    })
}

/// [`conversation_event_to_json`] 需要的 token 映射能力 —— 由 §13 层实现。
///
/// **为什么是 trait 而不是闭包**：一次映射要同时用到定时器路由表与 op token 前缀，两个
/// `FnMut` 无法同时可变借用同一份状态（`&mut self.timers` 只能借一次）。
///
/// **为什么必须映射**：对话层的 `TimerId` 与核心的 `TimerId` 是**两套独立计数器**（都从 1
/// 开始），op token 也可能与核心的撞（`pair:1` vs `sendText:1` 之外的形态）。直接透传必然撞号，
/// 宿主会把 A 的定时器触发喂给 B。
pub trait ConversationTokens {
    /// 对话层定时器 → 宿主 token。**必分配**：同一个对话层 token 再次出现表示周期定时器
    /// 重新装弹，实现应复用同一个宿主 token（宿主按「同 token 再出现 = 重装」处理）。
    fn start_timer(&mut self, token: TimerId) -> u64;

    /// 对话层定时器 → 宿主 token；`None` = 宿主从没见过它（整条事件被跳过——发一条指向
    /// 不存在 token 的取消只会误导宿主）。
    fn cancel_timer(&mut self, token: TimerId) -> Option<u64>;

    /// 对话层 op token → 宿主 token。
    fn op_token(&mut self, token: &str) -> String;
}

/// §13.5.2：`ConversationEvent` → `conversation_event.event` 对象。
///
/// 返回 `None` 的三种情形（`listenChannel` / `unlistenChannel` / `waitHealthy`）是
/// **FFI 内部消费**的：它们要求对桥的 `ChannelClient` 做事（`add_listener` /
/// `cancel_listener` / `bridge_wait_healthy`），而宿主按契约拿不到通道 id、也没有通道面，
/// 只有 FFI 层做得到（§13 层在调本函数前已就地完成那些动作，故不外发）。
pub fn conversation_event_to_json(
    event: &ConversationEvent,
    tokens: &mut dyn ConversationTokens,
) -> Option<Value> {
    let body = match event {
        ConversationEvent::Log(line) => json!({ "type": "log", "line": line }),
        ConversationEvent::TimerStart { token, delay_ms } => json!({
            "type": "timerStart",
            "token": tokens.start_timer(*token),
            "delayMs": delay_ms,
        }),
        ConversationEvent::TimerCancel(token) => json!({
            "type": "timerCancel",
            "token": tokens.cancel_timer(*token)?,
        }),
        ConversationEvent::StateChanged => json!({ "type": "stateChanged" }),
        ConversationEvent::RowsUpdated {
            session_id,
            frame,
            ready,
            seq,
            total_count,
            first_row_id,
            row_count,
        } => json!({
            "type": "rowsUpdated",
            "sessionId": session_id,
            // §3 的约定：`None` → `null`，键不省。
            "frame": match frame {
                Some(frame) => Value::Object(frame.clone()),
                None => Value::Null,
            },
            "ready": ready,
            "seq": seq,
            "totalCount": total_count,
            "firstRowId": first_row_id,
            "rowCount": row_count,
        }),
        ConversationEvent::SessionsIndexUpdated {
            workspace_id,
            log_epoch,
            seq,
            ready,
            session_count,
        } => json!({
            "type": "sessionsIndexUpdated",
            "workspaceId": workspace_id,
            "logEpoch": log_epoch,
            "seq": seq,
            "ready": ready,
            "sessionCount": session_count,
        }),
        ConversationEvent::HandshakeDone { connection_id } => json!({
            "type": "handshakeDone",
            "connectionId": connection_id,
        }),
        ConversationEvent::OpAccepted { token, op } => json!({
            "type": "opAccepted",
            "token": tokens.op_token(token),
            "op": op,
        }),
        ConversationEvent::Completed { token, outcome } => json!({
            "type": "completed",
            "token": tokens.op_token(token),
            "outcome": transport_completion_to_json(outcome),
        }),
        ConversationEvent::Subscribed {
            session_id,
            subscription_id,
        } => json!({
            "type": "subscribed",
            "sessionId": session_id,
            "subscriptionId": subscription_id,
        }),
        ConversationEvent::AttachmentProgress {
            token,
            session_id,
            upload_id,
            progress,
        } => json!({
            "type": "attachmentProgress",
            "token": tokens.op_token(token),
            "sessionId": session_id,
            "uploadId": upload_id,
            "progress": progress,
        }),
        // FFI 内部消费（见函数文档），不外发。
        ConversationEvent::ListenChannel { .. }
        | ConversationEvent::UnlistenChannel { .. }
        | ConversationEvent::WaitHealthy { .. } => return None,
    };
    Some(body)
}

/// §13.5.2 的 `outcome`。
pub fn transport_completion_to_json(outcome: &ConvCompletion) -> Value {
    match outcome {
        ConvCompletion::Ok => json!({ "type": "ok" }),
        ConvCompletion::Value(value) => json!({ "type": "value", "value": value }),
        ConvCompletion::SessionId(session_id) => json!({
            "type": "sessionId",
            "value": session_id,
        }),
        ConvCompletion::AttachmentDescriptor(value) => json!({
            "type": "attachmentDescriptor",
            "value": value,
        }),
        ConvCompletion::AttachmentBytes {
            base64,
            media_type,
        } => json!({
            "type": "attachmentBytes",
            "base64": base64,
            "mediaType": media_type,
        }),
        ConvCompletion::Failed(error) => json!({
            "type": "failed",
            "error": transport_error_to_json(error),
        }),
    }
}

/// §13.5.2 的 `failed.error`：`kind` ∈ `invalidState` / `timeout` / `channel` / `protocol`。
pub fn transport_error_to_json(error: &TransportError) -> Value {
    json!({
        "kind": transport_error_kind(error.kind),
        "message": error.message,
    })
}

/// `TransportErrorKind` → §13.5.2 的 `kind` 取值。
pub fn transport_error_kind(kind: TransportErrorKind) -> &'static str {
    match kind {
        TransportErrorKind::InvalidState => "invalidState",
        TransportErrorKind::Timeout => "timeout",
        TransportErrorKind::Channel => "channel",
        TransportErrorKind::Protocol => "protocol",
    }
}

/// §13.5.2 的 outcome → §7.5 的 `Completion`。
///
/// **为什么要有这一步**：§8 明说 `operation` 是「挂起操作的关联令牌，宿主拿它跟后续
/// `completed` 事件的 `token` 对上，从而 resolve 自己的 Deferred」。Kotlin 引擎的
/// `pending` 表只在**顶层** `completed` 事件上 resolve（`ZemoteEngine.resolvePending`），
/// 而对话层的 op 完成事件按 §13.5.2 是包在 `conversation_event` 里的。若不给顶层
/// `completed`，宿主那侧会挂到自己的兜底超时。
///
/// 顶层 `completed.outcome` 的形状由 §7.5 冻结（`kind` 判别），故这里把 §13.5.2 的
/// `type` 形态折算过去：`Channel` 类错误在 §7.5 里没有对应 kind，按 `protocol` 出。
pub fn transport_completion_to_core(outcome: &ConvCompletion) -> Completion {
    match outcome {
        ConvCompletion::Ok => Completion::Ok,
        ConvCompletion::Value(value) => Completion::Payload(value.clone()),
        ConvCompletion::SessionId(session_id) => {
            Completion::Payload(Value::String(session_id.clone()))
        }
        ConvCompletion::AttachmentDescriptor(value) => Completion::Payload(value.clone()),
        ConvCompletion::AttachmentBytes {
            base64,
            media_type,
        } => Completion::Payload(json!({
            "base64": base64,
            "mediaType": media_type,
        })),
        ConvCompletion::Failed(error) => Completion::Failed(match error.kind {
            TransportErrorKind::InvalidState => ZemoteError::InvalidState(error.message.clone()),
            TransportErrorKind::Timeout => ZemoteError::Timeout(error.message.clone()),
            // §7.5 的 `failed.error.kind` 只有 timeout / invalidState / protocol 三种。
            TransportErrorKind::Channel | TransportErrorKind::Protocol => {
                ZemoteError::Protocol(error.message.clone())
            }
        }),
    }
}

/// §7.3 的顶层 `completed` 事件（`ZemoteEvent` 里没有「手工构造一条事件」的入口，故在此封装）。
pub fn completed_event(token: impl Into<String>, outcome: Completion) -> Value {
    event_to_json(&ZemoteEvent::Completed {
        token: token.into(),
        outcome,
    })
}

/// §7.5 的 `failed` 顶层 `completed`（给定时任务层的通道错误/超时用）。
///
/// 顶层 `completed` 的 outcome 形状由 §7.5 冻结（`kind` 判别），**不是** §13.5.2 的 `type` 形态
/// ——后者只出现在 `conversation_event.event` 里。
pub fn failed_event(token: impl Into<String>, kind: &str, message: impl Into<String>) -> Value {
    json!({
        "type": "completed",
        "token": token.into(),
        "outcome": {
            "kind": "failed",
            "error": { "kind": kind, "message": message.into() },
        },
    })
}

// ===========================================================================
// 3. 入参：`nativeStep` 的输入（§6）
// ===========================================================================

/// 解析后的 `nativeStep` 输入（owned 版本）。
///
/// [`RelayInput`] 借用 `&str` / `&[u8]`，而 FFI 入参是宿主传进来的 `String`——解析出的文本
/// 必须先落在某处。这个 owned 枚举就是那个「某处」：`Session::step` 持有它，再用
/// [`OwnedRelayInput::as_relay_input`] 借出 `RelayInput<'_>` 喂给核心。
#[derive(Debug, Clone, PartialEq)]
pub enum OwnedInput {
    /// 透传给内部 relay 的输入。
    Relay(OwnedRelayInput),
    /// 门面自己的定时器到点（§6 的 `timer_fired`）。
    TimerFired(TimerId),
}

/// owned 版本的 [`RelayInput`]。字段与 §6 的表逐行对应。
#[derive(Debug, Clone, PartialEq)]
pub enum OwnedRelayInput {
    /// `{"type":"start"}`。
    Start,
    /// `{"type":"socket_ready","generation":N}`。
    SocketReady {
        /// 连接代次。
        generation: u64,
    },
    /// `{"type":"socket_failed","generation":N,"error":"..."}`。
    SocketFailed {
        /// 连接代次。
        generation: u64,
        /// 失败文本。
        error: String,
    },
    /// `{"type":"text_frame","generation":N,"text":"..."}`。
    TextFrame {
        /// 连接代次。
        generation: u64,
        /// 原始 JSON 文本。
        text: String,
    },
    /// `{"type":"binary_frame","generation":N,"base64":"..."}`（**已解码**）。
    BinaryFrame {
        /// 连接代次。
        generation: u64,
        /// 解码后的字节。
        bytes: Vec<u8>,
    },
    /// `{"type":"socket_closed","generation":N,"code":1006,"reason":null}`。
    SocketClosed {
        /// 连接代次。
        generation: u64,
        /// 关闭码。
        code: i32,
        /// 关闭原因。
        reason: Option<String>,
    },
    /// `{"type":"relay_timer_fired","timer":"heartbeat"}`。
    TimerFired(RelayTimer),
    /// `{"type":"send_payload","payload":{...}}`（逃生舱）。
    SendPayload(Map<String, Value>),
    /// `{"type":"poke"}`。
    Poke,
    /// `{"type":"debug_drop_socket"}`。
    DebugDropSocket,
    /// `{"type":"dispose"}`（relay 层退出，**不是** §10 的会话销毁）。
    Dispose,
}

impl OwnedRelayInput {
    /// 借出核心要的 [`RelayInput`]。
    pub fn as_relay_input(&self) -> RelayInput<'_> {
        match self {
            OwnedRelayInput::Start => RelayInput::Start,
            OwnedRelayInput::SocketReady { generation } => RelayInput::SocketReady {
                generation: *generation,
            },
            OwnedRelayInput::SocketFailed { generation, error } => RelayInput::SocketFailed {
                generation: *generation,
                error: error.clone(),
            },
            OwnedRelayInput::TextFrame { generation, text } => RelayInput::TextFrame {
                generation: *generation,
                text,
            },
            OwnedRelayInput::BinaryFrame { generation, bytes } => RelayInput::BinaryFrame {
                generation: *generation,
                bytes,
            },
            OwnedRelayInput::SocketClosed {
                generation,
                code,
                reason,
            } => RelayInput::SocketClosed {
                generation: *generation,
                code: *code,
                reason: reason.clone(),
            },
            OwnedRelayInput::TimerFired(timer) => RelayInput::TimerFired(*timer),
            OwnedRelayInput::SendPayload(payload) => RelayInput::SendPayload(payload.clone()),
            OwnedRelayInput::Poke => RelayInput::Poke,
            OwnedRelayInput::DebugDropSocket => RelayInput::DebugDropSocket,
            OwnedRelayInput::Dispose => RelayInput::Dispose,
        }
    }
}

/// §6：解析 `nativeStep` 的 `inputJson`。
pub fn parse_step_input(value: &Value) -> Result<OwnedInput, BadInput> {
    let map = value
        .as_object()
        .ok_or_else(|| BadInput::new("step 输入必须是 JSON 对象"))?;
    let kind = required_string(map, "type")?;

    let input = match kind {
        "start" => OwnedInput::Relay(OwnedRelayInput::Start),
        "socket_ready" => OwnedInput::Relay(OwnedRelayInput::SocketReady {
            generation: required_u64(map, "generation")?,
        }),
        "socket_failed" => OwnedInput::Relay(OwnedRelayInput::SocketFailed {
            generation: required_u64(map, "generation")?,
            error: required_string(map, "error")?.to_string(),
        }),
        "text_frame" => OwnedInput::Relay(OwnedRelayInput::TextFrame {
            generation: required_u64(map, "generation")?,
            text: required_string(map, "text")?.to_string(),
        }),
        "binary_frame" => {
            let encoded = required_string(map, "base64")?;
            let bytes = STANDARD
                .decode(encoded)
                .map_err(|error| BadInput::new(format!("binary_frame.base64 不是合法 base64: {error}")))?;
            OwnedInput::Relay(OwnedRelayInput::BinaryFrame {
                generation: required_u64(map, "generation")?,
                bytes,
            })
        }
        "socket_closed" => {
            // 对应 Dart `socket.closeCode ?? 1006`：缺 `code` 时按异常关闭处理。
            let raw = optional_i64(map, "code")?.unwrap_or(1006);
            let code = i32::try_from(raw)
                .map_err(|_| BadInput::new(format!("socket_closed.code 超出 i32 范围: {raw}")))?;
            OwnedInput::Relay(OwnedRelayInput::SocketClosed {
                generation: required_u64(map, "generation")?,
                code,
                reason: optional_string(map, "reason")?,
            })
        }
        "relay_timer_fired" => OwnedInput::Relay(OwnedRelayInput::TimerFired(parse_relay_timer(
            required_string(map, "timer")?,
        )?)),
        "send_payload" => {
            let payload = map
                .get("payload")
                .and_then(Value::as_object)
                .ok_or_else(|| BadInput::new("send_payload.payload 必须是 JSON 对象"))?;
            OwnedInput::Relay(OwnedRelayInput::SendPayload(payload.clone()))
        }
        "poke" => OwnedInput::Relay(OwnedRelayInput::Poke),
        "debug_drop_socket" => OwnedInput::Relay(OwnedRelayInput::DebugDropSocket),
        "dispose" => OwnedInput::Relay(OwnedRelayInput::Dispose),
        "timer_fired" => OwnedInput::TimerFired(TimerId(required_u64(map, "token")?)),
        other => return Err(BadInput::new(format!("未知输入 type: {other}"))),
    };
    Ok(input)
}

/// `timer` 字段 → [`RelayTimer`]（§6 的四个取值）。
pub fn parse_relay_timer(name: &str) -> Result<RelayTimer, BadInput> {
    match name {
        "heartbeat" => Ok(RelayTimer::Heartbeat),
        "waiting" => Ok(RelayTimer::Waiting),
        "reconnect" => Ok(RelayTimer::Reconnect),
        "rewait" => Ok(RelayTimer::Rewait),
        other => Err(BadInput::new(format!("未知 relay 定时器槽位: {other}"))),
    }
}

// ===========================================================================
// 4. 入参：`nativeCall` 的命令（§8）
// ===========================================================================

/// §8 的命令。`timeoutMs` 未给时已填入 Dart 侧默认值（见 [`DEFAULT_TIMEOUTS`] 的说明）。
#[derive(Debug, Clone, PartialEq)]
pub enum CallCommand {
    /// `{"method":"bootstrap"}`。
    Bootstrap,
    /// `{"method":"list_workspaces"}`。
    ListWorkspaces,
    /// `{"method":"open_bridge",...}`。
    OpenBridge {
        /// 工作区键。
        workspace_key: String,
        /// 初始任务 id。
        task_id: Option<String>,
        /// 超时。
        timeout_ms: i64,
    },
    /// `{"method":"reconnect_workspace",...}`。
    ReconnectWorkspace {
        /// 工作区键。
        workspace_key: String,
    },
    /// `{"method":"send_mobile_view_state",...}`。
    SendMobileViewState {
        /// 工作区键。
        workspace_key: String,
        /// 任务 id。
        task_id: Option<String>,
    },
    /// `{"method":"connect"}`。
    Connect,
    /// `{"method":"poke_relay"}`。
    PokeRelay,
    /// `{"method":"wait_paired",...}`。
    WaitPaired {
        /// 超时。
        timeout_ms: i64,
    },
    /// `{"method":"bridge_wait_healthy",...}`。
    BridgeWaitHealthy {
        /// 桥句柄。
        session: BridgeSessionId,
        /// 超时。
        timeout_ms: i64,
    },
    /// `{"method":"conversation_open","session":N,"scope":{...}}`（§13.2 打开一个对话）。
    ConversationOpen {
        /// 桥句柄。
        session: BridgeSessionId,
        /// 作用域。
        scope: Map<String, Value>,
    },
    /// `{"method":"conversation_call","session":N,"key":"...","op":"...","args":{...}}`（§13.2）。
    ConversationCall {
        /// 桥句柄。
        session: BridgeSessionId,
        /// `conversation_open` 返回的 conversation key。
        key: String,
        /// 对话层 `op` 名（清单见 `ConversationTransport::OPS`）。
        op: String,
        /// `op` 的参数（缺省或 `null` 视为 `{}`，§13.5.1）。
        args: Map<String, Value>,
    },
    /// `{"method":"conversation_close","session":N,"key":"..."}`（§13.2）。
    ConversationClose {
        /// 桥句柄。
        session: BridgeSessionId,
        /// 要释放的 conversation key。
        key: String,
    },
    /// `{"method":"automation_call","session":N,"op":"...","args":{...}}`（§13.3）。
    AutomationCall {
        /// 桥句柄。
        session: BridgeSessionId,
        /// 定时任务层 `op` 名（`list` / `create` / `setEnabled` / `delete` / `runNow` / `restart`）。
        op: String,
        /// `op` 的参数（缺省或 `null` 视为 `{}`）。
        args: Map<String, Value>,
    },
    /// `{"method":"channel_call","session":N,"channel":"...","method":"...","args":[...]}`。
    ///
    /// 通用通道调用：`setting` / `window-controller` / `skills` / `plugins` /
    /// `subagents` / `usage-stats` 等通道都走这里。`args` 是**位置参数数组**
    /// （与 `ChannelClient::call` 的签名一致），不是对象。
    ChannelCall {
        /// 桥句柄。
        session: BridgeSessionId,
        /// 通道名（如 `setting`）。
        channel: String,
        /// 通道方法名（如 `get`）。
        method: String,
        /// 位置参数。
        args: Vec<Value>,
        /// 超时。
        timeout_ms: i64,
    },
    /// `{"method":"request",...}`（逃生舱）。
    Request {
        /// 请求载荷。
        payload: Map<String, Value>,
        /// 匹配的 `zcode_type` 集合。
        match_types: Vec<String>,
        /// 额外必须严格相等的字段。
        match_fields: Vec<(String, Value)>,
        /// 超时。
        timeout_ms: i64,
    },
}

/// §8 的默认超时（读自 `zemote_client.rs` 的公开常量）。
pub mod default_timeouts {
    use zemote_protocol::zemote_client::{
        BRIDGE_HEALTHY_TIMEOUT_MS, OPEN_BRIDGE_TIMEOUT_MS, PAIR_TIMEOUT_MS, REQUEST_TIMEOUT_MS,
    };

    /// `bootstrap` / `list_workspaces` / `reconnect_workspace` / `request` 的默认超时。
    pub const REQUEST_MS: i64 = REQUEST_TIMEOUT_MS;
    /// `wait_paired` 的默认超时。
    pub const PAIR_MS: i64 = PAIR_TIMEOUT_MS;
    /// `open_bridge` 的默认超时。
    pub const OPEN_BRIDGE_MS: i64 = OPEN_BRIDGE_TIMEOUT_MS;
    /// `bridge_wait_healthy` 的默认超时。
    pub const BRIDGE_HEALTHY_MS: i64 = BRIDGE_HEALTHY_TIMEOUT_MS;
}

/// §8：解析 `nativeCall` 的 `commandJson`。
pub fn parse_call_command(value: &Value) -> Result<CallCommand, BadInput> {
    let map = value
        .as_object()
        .ok_or_else(|| BadInput::new("call 命令必须是 JSON 对象"))?;
    let method = required_string(map, "method")?;

    let command = match method {
        "bootstrap" => CallCommand::Bootstrap,
        "list_workspaces" => CallCommand::ListWorkspaces,
        "open_bridge" => CallCommand::OpenBridge {
            workspace_key: required_string(map, "workspaceKey")?.to_string(),
            task_id: optional_string(map, "taskId")?,
            timeout_ms: optional_i64(map, "timeoutMs")?.unwrap_or(default_timeouts::OPEN_BRIDGE_MS),
        },
        "reconnect_workspace" => CallCommand::ReconnectWorkspace {
            workspace_key: required_string(map, "workspaceKey")?.to_string(),
        },
        "send_mobile_view_state" => CallCommand::SendMobileViewState {
            workspace_key: required_string(map, "workspaceKey")?.to_string(),
            task_id: optional_string(map, "taskId")?,
        },
        "connect" => CallCommand::Connect,
        "poke_relay" => CallCommand::PokeRelay,
        "wait_paired" => CallCommand::WaitPaired {
            timeout_ms: optional_i64(map, "timeoutMs")?.unwrap_or(default_timeouts::PAIR_MS),
        },
        "bridge_wait_healthy" => CallCommand::BridgeWaitHealthy {
            session: BridgeSessionId(required_u64(map, "session")?),
            timeout_ms: optional_i64(map, "timeoutMs")?.unwrap_or(default_timeouts::BRIDGE_HEALTHY_MS),
        },
        // §13.2 / §13.3：对话层与定时任务层。`op` / `args` 的语义全在 Rust 侧（§13.1），
        // 这里只做形状校验（`op` 必须是字符串，`args` 缺省或 `null` 视为 `{}`）。
        "conversation_open" => CallCommand::ConversationOpen {
            session: BridgeSessionId(required_u64(map, "session")?),
            scope: required_object(map, "scope")?,
        },
        "conversation_call" => CallCommand::ConversationCall {
            session: BridgeSessionId(required_u64(map, "session")?),
            key: required_string(map, "key")?.to_string(),
            op: required_string(map, "op")?.to_string(),
            args: optional_object(map, "args")?,
        },
        "conversation_close" => CallCommand::ConversationClose {
            session: BridgeSessionId(required_u64(map, "session")?),
            key: required_string(map, "key")?.to_string(),
        },
        "automation_call" => CallCommand::AutomationCall {
            session: BridgeSessionId(required_u64(map, "session")?),
            op: required_string(map, "op")?.to_string(),
            args: optional_object(map, "args")?,
        },
        "channel_call" => CallCommand::ChannelCall {
            session: BridgeSessionId(required_u64(map, "session")?),
            channel: required_string(map, "channel")?.to_string(),
            method: required_string(map, "method")?.to_string(),
            args: optional_value_array(map, "args")?,
            timeout_ms: optional_i64(map, "timeoutMs")?.unwrap_or(default_timeouts::REQUEST_MS),
        },
        "request" => CallCommand::Request {
            payload: required_object(map, "payload")?,
            match_types: required_string_array(map, "matchTypes")?,
            match_fields: required_field_pairs(map, "matchFields")?,
            timeout_ms: optional_i64(map, "timeoutMs")?.unwrap_or(default_timeouts::REQUEST_MS),
        },
        other => return Err(BadInput::new(format!("未知 method: {other}"))),
    };
    Ok(command)
}

// ===========================================================================
// 5. 字段读取小工具
// ===========================================================================

fn required_string<'a>(map: &'a Map<String, Value>, key: &str) -> Result<&'a str, BadInput> {
    map.get(key)
        .and_then(Value::as_str)
        .ok_or_else(|| BadInput::new(format!("字段 {key} 必须是字符串")))
}

fn required_u64(map: &Map<String, Value>, key: &str) -> Result<u64, BadInput> {
    map.get(key)
        .and_then(Value::as_u64)
        .ok_or_else(|| BadInput::new(format!("字段 {key} 必须是非负整数")))
}

fn optional_i64(map: &Map<String, Value>, key: &str) -> Result<Option<i64>, BadInput> {
    match map.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(value) => value
            .as_i64()
            .map(Some)
            .ok_or_else(|| BadInput::new(format!("字段 {key} 必须是整数或 null"))),
    }
}

/// `null` / 缺失 → `None`；字符串 → `Some`；其它类型 → `badInput`（不静默降级）。
fn optional_string(map: &Map<String, Value>, key: &str) -> Result<Option<String>, BadInput> {
    match map.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(Value::String(text)) => Ok(Some(text.clone())),
        Some(_) => Err(BadInput::new(format!("字段 {key} 必须是字符串或 null"))),
    }
}

fn required_object(map: &Map<String, Value>, key: &str) -> Result<Map<String, Value>, BadInput> {
    map.get(key)
        .and_then(Value::as_object)
        .cloned()
        .ok_or_else(|| BadInput::new(format!("字段 {key} 必须是 JSON 对象")))
}

/// 缺省或 `null` → 空对象；对象 → 原样；其它类型 → `badInput`（不静默降级）。
fn optional_object(map: &Map<String, Value>, key: &str) -> Result<Map<String, Value>, BadInput> {
    match map.get(key) {
        None | Some(Value::Null) => Ok(Map::new()),
        Some(Value::Object(value)) => Ok(value.clone()),
        Some(_) => Err(BadInput::new(format!("字段 {key} 必须是 JSON 对象或 null"))),
    }
}

/// 缺省或 `null` → 空数组；数组 → 原样；其它类型 → `badInput`。
fn optional_value_array(map: &Map<String, Value>, key: &str) -> Result<Vec<Value>, BadInput> {
    match map.get(key) {
        None | Some(Value::Null) => Ok(Vec::new()),
        Some(Value::Array(value)) => Ok(value.clone()),
        Some(_) => Err(BadInput::new(format!("字段 {key} 必须是 JSON 数组或 null"))),
    }
}

fn required_string_array(map: &Map<String, Value>, key: &str) -> Result<Vec<String>, BadInput> {
    let items = map
        .get(key)
        .and_then(Value::as_array)
        .ok_or_else(|| BadInput::new(format!("字段 {key} 必须是字符串数组")))?;
    items
        .iter()
        .map(|item| {
            item.as_str()
                .map(str::to_string)
                .ok_or_else(|| BadInput::new(format!("字段 {key} 的元素必须是字符串")))
        })
        .collect()
}

/// `matchFields` → `(键, 值)` 列表，对应逐个 `PayloadMatcher::with_field`。
///
/// 保持 JSON 对象里的键序无关紧要（`with_field` 只是 `&&` 追加），但这里用 `Map` 的迭代序，
/// 与 Kotlin 传进来的顺序在语义上等价。
fn required_field_pairs(
    map: &Map<String, Value>,
    key: &str,
) -> Result<Vec<(String, Value)>, BadInput> {
    let fields = map
        .get(key)
        .and_then(Value::as_object)
        .ok_or_else(|| BadInput::new(format!("字段 {key} 必须是 JSON 对象")))?;
    Ok(fields
        .iter()
        .map(|(name, value)| (name.clone(), value.clone()))
        .collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    use serde_json::from_str as parse_json;
    use zemote_protocol::channel_client::ChannelRpcError;
    use zemote_protocol::ipc_codec::Value as IpcValue;
    use zemote_protocol::relay_client::{RelayFailure, RelayState};

    fn obj(text: &str) -> Value {
        parse_json(text).expect("测试 JSON 必须合法")
    }

    // ---- 信封（§3 / §4） ----

    #[test]
    fn abi_envelope_has_frozen_shape() {
        assert_eq!(
            abi_envelope(),
            json!({
                "ok": true,
                // §3：每个 native 方法都返回 `events`（这里是空数组）。
                "events": [],
                "abi": 1,
                "protocolVersion": "0.1.0",
                // §13 接完后能力如实反映（Kotlin 据此判断该 .so 是否含对话层）。
                "features": ["conversation", "automation"],
            })
        );
    }

    /// §3：失败信封也**必须**带 `events` 键（Kotlin 侧不处理缺键）。
    #[test]
    fn error_envelope_always_carries_events_key() {
        let envelope = err("invalidState", "未知 handle", Vec::new());
        assert_eq!(envelope["ok"], json!(false));
        assert_eq!(envelope["error"]["kind"], json!("invalidState"));
        assert!(envelope.get("events").is_some(), "events 键不得省略");
        assert_eq!(envelope["events"], json!([]));
    }

    /// §3：`ok=false` 时出错前已产生的事件不能丢。
    #[test]
    fn error_envelope_keeps_events_produced_before_failure() {
        let envelope = err("panic", "boom", vec![json!({"type": "log", "line": "x"})]);
        assert_eq!(envelope["events"].as_array().unwrap().len(), 1);
    }

    /// §5 的 `"dryRun": true` 信封：`handle` 是显式 `null`、`events` 恒为空数组。
    #[test]
    fn dry_run_envelope_shape() {
        let envelope = ok_dry_run(json!({"deviceSid": "S"}));
        assert_eq!(envelope["ok"], json!(true));
        assert_eq!(envelope["dryRun"], json!(true));
        assert!(envelope.get("handle").is_some(), "handle 键不得省略");
        assert_eq!(envelope["handle"], json!(null));
        assert_eq!(envelope["events"], json!([]));
        assert_eq!(envelope["parsed"], json!({"deviceSid": "S"}));
    }

    #[test]
    fn zemote_error_kinds_match_contract() {
        assert_eq!(
            zemote_error_kind(&ZemoteError::Timeout("t".to_string())),
            "timeout"
        );
        assert_eq!(
            zemote_error_kind(&ZemoteError::InvalidState("s".to_string())),
            "invalidState"
        );
        assert_eq!(
            zemote_error_kind(&ZemoteError::Protocol("p".to_string())),
            "protocol"
        );
        // 取内部文本，不带 Display 的 "timeout: " 前缀。
        assert_eq!(
            zemote_error_message(&ZemoteError::Timeout("pairing timeout".to_string())),
            "pairing timeout"
        );
    }

    // ---- §7.1 传输动作 ----

    #[test]
    fn transport_actions_serialize_per_7_1() {
        let actions = vec![
            Action::Connect {
                generation: 1,
                uri: "wss://zcode.z.ai/ws?mid=MID-1".to_string(),
            },
            Action::SendText {
                generation: 1,
                text: "{\"type\":\"auth_init\"}".to_string(),
            },
            Action::Close {
                generation: 1,
                code: Some(3000),
                reason: Some("debug-drop".to_string()),
            },
        ];
        assert_eq!(
            actions_to_json(&actions),
            vec![
                json!({"type": "ws_connect", "generation": 1, "uri": "wss://zcode.z.ai/ws?mid=MID-1"}),
                json!({"type": "ws_send_text", "generation": 1, "text": "{\"type\":\"auth_init\"}"}),
                json!({"type": "ws_close", "generation": 1, "code": 3000, "reason": "debug-drop"}),
            ]
        );
    }

    /// §7.1：`code` / `reason` 为 `None` 时序列化成 `null`，**键仍在**。
    #[test]
    fn ws_close_with_none_code_and_reason_keeps_null_keys() {
        let value = action_to_json(&Action::Close {
            generation: 4,
            code: None,
            reason: None,
        });
        assert_eq!(
            value,
            json!({"type": "ws_close", "generation": 4, "code": null, "reason": null})
        );
        assert!(value.get("code").is_some());
        assert!(value.get("reason").is_some());
    }

    /// 只有 `reason` 为 `None` 的中间态（`close(generation, Some(code), None)`）。
    #[test]
    fn ws_close_with_code_only_keeps_null_reason() {
        assert_eq!(
            action_to_json(&Action::Close {
                generation: 2,
                code: Some(1006),
                reason: None,
            }),
            json!({"type": "ws_close", "generation": 2, "code": 1006, "reason": null})
        );
    }

    // ---- §7.2 relay 事件（表驱动） ----

    #[test]
    fn relay_events_serialize_per_7_2() {
        let cases: Vec<(RelayEvent, Value)> = vec![
            (
                RelayEvent::StateChanged(RelayState::Paired),
                json!({"type": "relay_state", "state": "paired"}),
            ),
            (
                RelayEvent::StateChanged(RelayState::Kicked),
                json!({"type": "relay_state", "state": "kicked"}),
            ),
            (
                RelayEvent::Log("[relay] state -> RelayState.paired".to_string()),
                json!({"type": "relay_log", "line": "[relay] state -> RelayState.paired"}),
            ),
            (
                RelayEvent::Payload(
                    obj(r#"{"zcode_type":"bootstrap-response"}"#)
                        .as_object()
                        .unwrap()
                        .clone(),
                ),
                json!({"type": "relay_payload", "payload": {"zcode_type": "bootstrap-response"}}),
            ),
            (
                RelayEvent::Failure(RelayFailure::new("kicked", None)),
                json!({"type": "relay_failure", "reason": "kicked", "message": null}),
            ),
            (
                RelayEvent::Failure(RelayFailure::new(
                    "session-conflict",
                    Some("dup".to_string()),
                )),
                json!({"type": "relay_failure", "reason": "session-conflict", "message": "dup"}),
            ),
            (
                RelayEvent::TimerStart {
                    timer: RelayTimer::Heartbeat,
                    delay_ms: 10_000,
                    repeat: true,
                },
                json!({"type": "relay_timer_start", "timer": "heartbeat", "delayMs": 10000, "repeat": true}),
            ),
            (
                RelayEvent::TimerStart {
                    timer: RelayTimer::Reconnect,
                    delay_ms: 2_000,
                    repeat: false,
                },
                json!({"type": "relay_timer_start", "timer": "reconnect", "delayMs": 2000, "repeat": false}),
            ),
            (
                RelayEvent::TimerCancel(RelayTimer::Heartbeat),
                json!({"type": "relay_timer_cancel", "timer": "heartbeat"}),
            ),
            (
                RelayEvent::TimerCancel(RelayTimer::Rewait),
                json!({"type": "relay_timer_cancel", "timer": "rewait"}),
            ),
        ];
        for (event, expected) in cases {
            assert_eq!(relay_event_to_json(&event), expected, "事件 {event:?}");
        }
    }

    /// §7.2：`relay_failure` 的 `message` 为 `None` 时必须是 `null` 且键存在。
    #[test]
    fn relay_failure_null_message_key_is_present() {
        let value = relay_event_to_json(&RelayEvent::Failure(RelayFailure::new(
            "session-not-found",
            None,
        )));
        assert!(value.get("message").is_some());
        assert_eq!(value["message"], json!(null));
    }

    #[test]
    fn every_relay_timer_maps_to_its_wire_name() {
        assert_eq!(relay_timer_name(RelayTimer::Heartbeat), "heartbeat");
        assert_eq!(relay_timer_name(RelayTimer::Waiting), "waiting");
        assert_eq!(relay_timer_name(RelayTimer::Reconnect), "reconnect");
        assert_eq!(relay_timer_name(RelayTimer::Rewait), "rewait");
    }

    // ---- §7.3 门面事件（表驱动） ----

    #[test]
    fn zemote_events_serialize_per_7_3() {
        let cases: Vec<(ZemoteEvent, Value)> = vec![
            (
                ZemoteEvent::Log("[facade] hi".to_string()),
                json!({"type": "log", "line": "[facade] hi"}),
            ),
            (
                ZemoteEvent::TimerStart {
                    token: TimerId(7),
                    delay_ms: 30_000,
                },
                json!({"type": "timer_start", "token": 7, "delayMs": 30000}),
            ),
            (
                ZemoteEvent::TimerCancel(TimerId(7)),
                json!({"type": "timer_cancel", "token": 7}),
            ),
            (
                ZemoteEvent::WorkspaceListUpdated(obj(r#"{"items":[1]}"#)),
                json!({"type": "workspace_list_updated", "value": {"items": [1]}}),
            ),
            (
                ZemoteEvent::BridgeDegraded {
                    session: BridgeSessionId(1),
                    degraded: None,
                },
                json!({"type": "bridge_degraded", "session": 1, "degraded": null}),
            ),
            (
                ZemoteEvent::BridgeDegraded {
                    session: BridgeSessionId(1),
                    degraded: Some("reopen-failed: x".to_string()),
                },
                json!({"type": "bridge_degraded", "session": 1, "degraded": "reopen-failed: x"}),
            ),
            (
                ZemoteEvent::AppError {
                    reason: Some("desktop-disconnected".to_string()),
                    error: Some("bye".to_string()),
                },
                json!({"type": "app_error", "reason": "desktop-disconnected", "error": "bye"}),
            ),
            (
                ZemoteEvent::BridgeRecovered {
                    session: BridgeSessionId(1),
                    count: 2,
                },
                json!({"type": "bridge_recovered", "session": 1, "count": 2}),
            ),
            (
                ZemoteEvent::BridgeStackAttached {
                    session: BridgeSessionId(1),
                },
                json!({"type": "bridge_stack_attached", "session": 1}),
            ),
            (
                ZemoteEvent::BridgeStackDisposed {
                    session: BridgeSessionId(1),
                },
                json!({"type": "bridge_stack_disposed", "session": 1}),
            ),
            (
                ZemoteEvent::ConversationOpened {
                    session: BridgeSessionId(1),
                    key: "wk|task".to_string(),
                    scope: obj(r#"{"workspaceKey":"wk"}"#).as_object().unwrap().clone(),
                },
                json!({
                    "type": "conversation_opened",
                    "session": 1,
                    "key": "wk|task",
                    "scope": {"workspaceKey": "wk"},
                }),
            ),
            (
                ZemoteEvent::Completed {
                    token: "bootstrap-abc".to_string(),
                    outcome: Completion::Ok,
                },
                json!({
                    "type": "completed",
                    "token": "bootstrap-abc",
                    "outcome": {"kind": "ok"},
                }),
            ),
        ];
        for (event, expected) in cases {
            assert_eq!(event_to_json(&event), expected, "事件 {event:?}");
        }
    }

    /// `ZemoteEvent::Relay` 直接摊平成 §7.2 形状，**不**再包一层 `{"type":"relay",...}`。
    #[test]
    fn relay_events_are_flattened_into_the_same_events_array() {
        assert_eq!(
            event_to_json(&ZemoteEvent::Relay(RelayEvent::StateChanged(
                RelayState::Connecting
            ))),
            json!({"type": "relay_state", "state": "connecting"})
        );
    }

    /// §7.3：`bridge_degraded` 的 `degraded` 为 `null` 表示已恢复健康 —— 键必须存在。
    #[test]
    fn bridge_degraded_null_means_healthy_and_key_is_present() {
        let value = event_to_json(&ZemoteEvent::BridgeDegraded {
            session: BridgeSessionId(3),
            degraded: None,
        });
        assert!(value.get("degraded").is_some());
        assert_eq!(value["degraded"], json!(null));
    }

    /// §7.3：`channel` 事件是包了一层 `event` 键的。
    #[test]
    fn channel_event_is_wrapped_under_event_key() {
        let value = event_to_json(&ZemoteEvent::Channel(ChannelEvent::EventFire {
            id: 9,
            data: IpcValue::Undefined,
        }));
        assert_eq!(
            value,
            json!({
                "type": "channel",
                "event": {"type": "event_fire", "id": 9, "data": null},
            })
        );
    }

    // ---- §7.4 channel.event（全部 4 个变体） ----

    #[test]
    fn channel_events_serialize_per_7_4() {
        let cases: Vec<(ChannelEvent, Value)> = vec![
            (
                ChannelEvent::PromiseSuccess {
                    id: 1,
                    data: IpcValue::Int(42),
                },
                json!({"type": "promise_success", "id": 1, "data": 42}),
            ),
            (
                ChannelEvent::PromiseError {
                    id: 1,
                    error: ChannelRpcError::new(
                        "boom",
                        IpcValue::Object(obj(r#"{"code":7}"#)),
                    ),
                },
                json!({
                    "type": "promise_error",
                    "id": 1,
                    "error": {"message": "boom", "data": {"code": 7}},
                }),
            ),
            (
                ChannelEvent::PromiseErrorObj {
                    id: 1,
                    error: ChannelRpcError::new(
                        "{code: 7}",
                        IpcValue::Object(obj(r#"{"code":7}"#)),
                    ),
                },
                json!({
                    "type": "promise_error_obj",
                    "id": 1,
                    "error": {"message": "{code: 7}", "data": {"code": 7}},
                }),
            ),
            (
                ChannelEvent::EventFire {
                    id: 1,
                    data: IpcValue::String("tick".to_string()),
                },
                json!({"type": "event_fire", "id": 1, "data": "tick"}),
            ),
        ];
        for (event, expected) in cases {
            assert_eq!(channel_event_to_json(&event), expected, "事件 {event:?}");
        }
    }

    // ---- §7.5 Completion 四种形态 ----

    #[test]
    fn completions_serialize_per_7_5() {
        let cases: Vec<(Completion, Value)> = vec![
            (
                Completion::Payload(obj(r#"{"success":true}"#)),
                json!({"kind": "payload", "value": {"success": true}}),
            ),
            (
                Completion::BridgeOpened(BridgeSessionId(1)),
                json!({"kind": "bridge_opened", "session": 1}),
            ),
            (Completion::Ok, json!({"kind": "ok"})),
            (
                Completion::Failed(ZemoteError::Timeout("pairing timeout".to_string())),
                json!({"kind": "failed", "error": {"kind": "timeout", "message": "pairing timeout"}}),
            ),
            (
                Completion::Failed(ZemoteError::InvalidState("bad".to_string())),
                json!({"kind": "failed", "error": {"kind": "invalidState", "message": "bad"}}),
            ),
            (
                Completion::Failed(ZemoteError::Protocol("frame".to_string())),
                json!({"kind": "failed", "error": {"kind": "protocol", "message": "frame"}}),
            ),
        ];
        for (outcome, expected) in cases {
            assert_eq!(completion_to_json(&outcome), expected, "形态 {outcome:?}");
        }
    }

    // ---- §13.5.2 对话事件（表驱动） ----

    /// 测试用的固定映射：对话层 token 一律搬到 `100+`，op token 加 `conv:k:` 前缀。
    struct FixedTokens;

    impl ConversationTokens for FixedTokens {
        fn start_timer(&mut self, token: TimerId) -> u64 {
            100 + token.0
        }
        fn cancel_timer(&mut self, token: TimerId) -> Option<u64> {
            Some(100 + token.0)
        }
        fn op_token(&mut self, token: &str) -> String {
            format!("conv:k:{token}")
        }
    }

    /// 取消一个**宿主从没见过**的定时器：`cancel_timer` 返回 `None`。
    struct UnknownCancelTokens;

    impl ConversationTokens for UnknownCancelTokens {
        fn start_timer(&mut self, token: TimerId) -> u64 {
            100 + token.0
        }
        fn cancel_timer(&mut self, _: TimerId) -> Option<u64> {
            None
        }
        fn op_token(&mut self, token: &str) -> String {
            token.to_string()
        }
    }

    fn conv_event(event: &ConversationEvent) -> Option<Value> {
        conversation_event_to_json(event, &mut FixedTokens)
    }

    #[test]
    fn conversation_events_serialize_per_13_5_2() {
        let cases: Vec<(ConversationEvent, Value)> = vec![
            (
                ConversationEvent::Log("[v4] hi".to_string()),
                json!({"type": "log", "line": "[v4] hi"}),
            ),
            (
                ConversationEvent::TimerStart {
                    token: TimerId(1),
                    delay_ms: 10_000,
                },
                json!({"type": "timerStart", "token": 101, "delayMs": 10000}),
            ),
            (
                ConversationEvent::TimerCancel(TimerId(2)),
                json!({"type": "timerCancel", "token": 102}),
            ),
            (ConversationEvent::StateChanged, json!({"type": "stateChanged"})),
            (
                ConversationEvent::RowsUpdated {
                    session_id: "s1".to_string(),
                    frame: None,
                    ready: true,
                    seq: 7,
                    total_count: 12,
                    first_row_id: Some(3),
                    row_count: 4,
                },
                json!({
                    "type": "rowsUpdated",
                    "sessionId": "s1",
                    "frame": null,
                    "ready": true,
                    "seq": 7,
                    "totalCount": 12,
                    "firstRowId": 3,
                    "rowCount": 4,
                }),
            ),
            (
                ConversationEvent::RowsUpdated {
                    session_id: "s1".to_string(),
                    frame: Some(obj(r#"{"topic":"t"}"#).as_object().unwrap().clone()),
                    ready: false,
                    seq: 0,
                    total_count: 0,
                    first_row_id: None,
                    row_count: 0,
                },
                json!({
                    "type": "rowsUpdated",
                    "sessionId": "s1",
                    "frame": {"topic": "t"},
                    "ready": false,
                    "seq": 0,
                    "totalCount": 0,
                    "firstRowId": null,
                    "rowCount": 0,
                }),
            ),
            (
                ConversationEvent::SessionsIndexUpdated {
                    workspace_id: Some("id-1".to_string()),
                    log_epoch: None,
                    seq: 1,
                    ready: true,
                    session_count: 2,
                },
                json!({
                    "type": "sessionsIndexUpdated",
                    "workspaceId": "id-1",
                    "logEpoch": null,
                    "seq": 1,
                    "ready": true,
                    "sessionCount": 2,
                }),
            ),
            (
                ConversationEvent::HandshakeDone {
                    connection_id: None,
                },
                json!({"type": "handshakeDone", "connectionId": null}),
            ),
            (
                ConversationEvent::OpAccepted {
                    token: "sendText:4".to_string(),
                    op: "sendText".to_string(),
                },
                json!({"type": "opAccepted", "token": "conv:k:sendText:4", "op": "sendText"}),
            ),
            (
                ConversationEvent::Subscribed {
                    session_id: Some("s1".to_string()),
                    subscription_id: "sub-1".to_string(),
                },
                json!({"type": "subscribed", "sessionId": "s1", "subscriptionId": "sub-1"}),
            ),
            (
                ConversationEvent::AttachmentProgress {
                    token: "attachmentPut:2".to_string(),
                    session_id: "s1".to_string(),
                    upload_id: "upload-1".to_string(),
                    progress: 0.5,
                },
                json!({
                    "type": "attachmentProgress",
                    "token": "conv:k:attachmentPut:2",
                    "sessionId": "s1",
                    "uploadId": "upload-1",
                    "progress": 0.5,
                }),
            ),
        ];
        for (event, expected) in cases {
            assert_eq!(conv_event(&event), Some(expected), "事件 {event:?}");
        }
    }

    /// §13.5.2 的 `completed.outcome` 六种形态。
    #[test]
    fn conversation_completions_serialize_per_13_5_2() {
        let cases: Vec<(ConvCompletion, Value)> = vec![
            (ConvCompletion::Ok, json!({"type": "ok"})),
            (
                ConvCompletion::Value(obj(r#"{"success":true}"#)),
                json!({"type": "value", "value": {"success": true}}),
            ),
            (
                ConvCompletion::SessionId("s-9".to_string()),
                json!({"type": "sessionId", "value": "s-9"}),
            ),
            (
                ConvCompletion::AttachmentDescriptor(
                    obj(r#"{"ref":"r","fileName":"a.txt","mime":"text/plain","bytes":3}"#),
                ),
                json!({
                    "type": "attachmentDescriptor",
                    "value": {"ref": "r", "fileName": "a.txt", "mime": "text/plain", "bytes": 3},
                }),
            ),
            (
                ConvCompletion::AttachmentBytes {
                    base64: "AQID".to_string(),
                    media_type: None,
                },
                json!({"type": "attachmentBytes", "base64": "AQID", "mediaType": null}),
            ),
            (
                ConvCompletion::Failed(TransportError::timeout("zcode-agent.x timed out")),
                json!({
                    "type": "failed",
                    "error": {"kind": "timeout", "message": "zcode-agent.x timed out"},
                }),
            ),
            (
                ConvCompletion::Failed(TransportError::invalid_state("bad")),
                json!({"type": "failed", "error": {"kind": "invalidState", "message": "bad"}}),
            ),
            (
                ConvCompletion::Failed(TransportError::channel("rpc")),
                json!({"type": "failed", "error": {"kind": "channel", "message": "rpc"}}),
            ),
            (
                ConvCompletion::Failed(TransportError::protocol("frame")),
                json!({"type": "failed", "error": {"kind": "protocol", "message": "frame"}}),
            ),
        ];
        for (outcome, expected) in cases {
            assert_eq!(transport_completion_to_json(&outcome), expected, "形态 {outcome:?}");
        }
    }

    /// `completed` 事件里的 outcome 用 §13.5.2 的形状；顶层 `completed` 折算成 §7.5 的形状。
    #[test]
    fn conversation_completed_event_uses_the_13_5_2_outcome_shape() {
        assert_eq!(
            conv_event(&ConversationEvent::Completed {
                token: "handshake:1".to_string(),
                outcome: ConvCompletion::Ok,
            }),
            Some(json!({
                "type": "completed",
                "token": "conv:k:handshake:1",
                "outcome": {"type": "ok"},
            }))
        );
    }

    /// §13.5.2 的三个 **FFI 内部消费** 变体不外发（宿主拿不到通道 id，做不了它们的动作）。
    #[test]
    fn internal_conversation_events_are_not_forwarded() {
        assert_eq!(
            conv_event(&ConversationEvent::ListenChannel {
                token: 1,
                channel: "zcode-agent",
                event: "onDynamicConversationFrame",
                arg: json!({}),
            }),
            None
        );
        assert_eq!(conv_event(&ConversationEvent::UnlistenChannel { id: 7 }), None);
        assert_eq!(
            conv_event(&ConversationEvent::WaitHealthy {
                token: 1,
                timeout_ms: 45_000,
            }),
            None
        );
    }

    /// 宿主从没见过的定时器取消**不**外发（发一条指向不存在 token 的取消只会误导宿主）。
    #[test]
    fn unknown_timer_cancel_is_dropped() {
        assert_eq!(
            conversation_event_to_json(
                &ConversationEvent::TimerCancel(TimerId(9)),
                &mut UnknownCancelTokens,
            ),
            None
        );
    }

    /// §13.5.2 的 outcome → §7.5 的 `Completion`（顶层 `completed` 用）。
    #[test]
    fn conversation_completion_folds_into_core_completion() {
        assert_eq!(
            transport_completion_to_core(&ConvCompletion::Ok),
            Completion::Ok
        );
        assert_eq!(
            transport_completion_to_core(&ConvCompletion::SessionId("s-1".to_string())),
            Completion::Payload(json!("s-1"))
        );
        assert_eq!(
            transport_completion_to_core(&ConvCompletion::Failed(TransportError::timeout("t"))),
            Completion::Failed(ZemoteError::Timeout("t".to_string()))
        );
        assert_eq!(
            transport_completion_to_core(&ConvCompletion::Failed(TransportError::invalid_state("i"))),
            Completion::Failed(ZemoteError::InvalidState("i".to_string()))
        );
        // §7.5 没有 channel 这个 kind，按 protocol 出。
        assert_eq!(
            transport_completion_to_core(&ConvCompletion::Failed(TransportError::channel("c"))),
            Completion::Failed(ZemoteError::Protocol("c".to_string()))
        );
        // 附件字节：base64 + mediaType 装进 §7.5 的 payload。
        assert_eq!(
            transport_completion_to_core(&ConvCompletion::AttachmentBytes {
                base64: "AQID".to_string(),
                media_type: Some("image/png".to_string()),
            }),
            Completion::Payload(json!({"base64": "AQID", "mediaType": "image/png"}))
        );
    }

    /// §13.4 的信封形状：`session` / `key` / `event` 三件套。
    #[test]
    fn conversation_event_envelope_shape() {
        assert_eq!(
            conversation_event_envelope(BridgeSessionId(1), "id-1", json!({"type": "stateChanged"})),
            json!({
                "type": "conversation_event",
                "session": 1,
                "key": "id-1",
                "event": {"type": "stateChanged"},
            })
        );
    }

    // ---- §6 输入解析 ----

    #[test]
    fn parses_every_documented_step_input() {
        let cases: Vec<(&str, OwnedInput)> = vec![
            (r#"{"type":"start"}"#, OwnedInput::Relay(OwnedRelayInput::Start)),
            (
                r#"{"type":"socket_ready","generation":3}"#,
                OwnedInput::Relay(OwnedRelayInput::SocketReady { generation: 3 }),
            ),
            (
                r#"{"type":"socket_failed","generation":3,"error":"boom"}"#,
                OwnedInput::Relay(OwnedRelayInput::SocketFailed {
                    generation: 3,
                    error: "boom".to_string(),
                }),
            ),
            (
                r#"{"type":"text_frame","generation":3,"text":"{}"}"#,
                OwnedInput::Relay(OwnedRelayInput::TextFrame {
                    generation: 3,
                    text: "{}".to_string(),
                }),
            ),
            (
                r#"{"type":"socket_closed","generation":3,"code":1006,"reason":null}"#,
                OwnedInput::Relay(OwnedRelayInput::SocketClosed {
                    generation: 3,
                    code: 1006,
                    reason: None,
                }),
            ),
            (
                r#"{"type":"socket_closed","generation":3,"code":4010,"reason":"gone"}"#,
                OwnedInput::Relay(OwnedRelayInput::SocketClosed {
                    generation: 3,
                    code: 4010,
                    reason: Some("gone".to_string()),
                }),
            ),
            (
                r#"{"type":"relay_timer_fired","timer":"heartbeat"}"#,
                OwnedInput::Relay(OwnedRelayInput::TimerFired(RelayTimer::Heartbeat)),
            ),
            (
                r#"{"type":"relay_timer_fired","timer":"rewait"}"#,
                OwnedInput::Relay(OwnedRelayInput::TimerFired(RelayTimer::Rewait)),
            ),
            (
                r#"{"type":"send_payload","payload":{"zcode_type":"x"}}"#,
                OwnedInput::Relay(OwnedRelayInput::SendPayload(
                    obj(r#"{"zcode_type":"x"}"#).as_object().unwrap().clone(),
                )),
            ),
            (r#"{"type":"poke"}"#, OwnedInput::Relay(OwnedRelayInput::Poke)),
            (
                r#"{"type":"debug_drop_socket"}"#,
                OwnedInput::Relay(OwnedRelayInput::DebugDropSocket),
            ),
            (
                r#"{"type":"dispose"}"#,
                OwnedInput::Relay(OwnedRelayInput::Dispose),
            ),
            (
                r#"{"type":"timer_fired","token":5}"#,
                OwnedInput::TimerFired(TimerId(5)),
            ),
        ];
        for (text, expected) in cases {
            assert_eq!(parse_step_input(&obj(text)), Ok(expected), "输入 {text}");
        }
    }

    /// §6：`binary_frame` 的 `base64` 由 FFI 层解码，宿主不要预解码。
    #[test]
    fn binary_frame_base64_is_decoded() {
        // "zemote" 的 base64 是 "emVtb3Rl"。
        let parsed = parse_step_input(&obj(
            r#"{"type":"binary_frame","generation":1,"base64":"emVtb3Rl"}"#,
        ))
        .expect("应解析成功");
        assert_eq!(
            parsed,
            OwnedInput::Relay(OwnedRelayInput::BinaryFrame {
                generation: 1,
                bytes: b"zemote".to_vec(),
            })
        );
    }

    /// 非 UTF-8 字节也要能过（协议侧按 `utf8.decode` 失败处理并记日志）。
    #[test]
    fn binary_frame_accepts_non_utf8_bytes() {
        let parsed = parse_step_input(&obj(
            r#"{"type":"binary_frame","generation":2,"base64":"//79"}"#,
        ))
        .expect("应解析成功");
        let OwnedInput::Relay(OwnedRelayInput::BinaryFrame { bytes, .. }) = parsed else {
            panic!("应为 BinaryFrame");
        };
        assert_eq!(bytes, vec![0xff, 0xfe, 0xfd]);
    }

    #[test]
    fn binary_frame_with_bad_base64_is_bad_input() {
        let error = parse_step_input(&obj(
            r#"{"type":"binary_frame","generation":1,"base64":"not base64!"}"#,
        ))
        .expect_err("必须失败");
        assert!(error.message.contains("base64"), "{}", error.message);
    }

    #[test]
    fn unknown_step_type_is_bad_input() {
        let error = parse_step_input(&obj(r#"{"type":"nope"}"#)).expect_err("必须失败");
        assert!(error.message.contains("未知输入 type: nope"), "{}", error.message);
    }

    #[test]
    fn missing_step_type_is_bad_input() {
        let error = parse_step_input(&obj(r#"{"generation":1}"#)).expect_err("必须失败");
        assert!(error.message.contains("type"), "{}", error.message);
    }

    #[test]
    fn non_object_step_input_is_bad_input() {
        assert!(parse_step_input(&json!([1, 2, 3])).is_err());
        assert!(parse_step_input(&json!("start")).is_err());
    }

    /// §6：`generation` 必须原样透传（代次不符的输入由核心忽略，FFI 层不改写）。
    #[test]
    fn generation_is_passed_through_verbatim() {
        let parsed = parse_step_input(&obj(
            r#"{"type":"text_frame","generation":18446744073709551615,"text":"{}"}"#,
        ))
        .expect("u64::MAX 应能解析");
        assert_eq!(
            parsed,
            OwnedInput::Relay(OwnedRelayInput::TextFrame {
                generation: u64::MAX,
                text: "{}".to_string(),
            })
        );
    }

    #[test]
    fn missing_generation_is_bad_input() {
        let error = parse_step_input(&obj(r#"{"type":"socket_ready"}"#)).expect_err("必须失败");
        assert!(error.message.contains("generation"), "{}", error.message);
    }

    /// 缺 `code` 时回落 1006（对应 Dart `socket.closeCode ?? 1006`）。
    #[test]
    fn socket_closed_without_code_falls_back_to_1006() {
        let parsed = parse_step_input(&obj(r#"{"type":"socket_closed","generation":1}"#))
            .expect("应解析成功");
        assert_eq!(
            parsed,
            OwnedInput::Relay(OwnedRelayInput::SocketClosed {
                generation: 1,
                code: 1006,
                reason: None,
            })
        );
    }

    #[test]
    fn unknown_relay_timer_is_bad_input() {
        let error = parse_step_input(&obj(
            r#"{"type":"relay_timer_fired","timer":"tick"}"#,
        ))
        .expect_err("必须失败");
        assert!(error.message.contains("tick"), "{}", error.message);
    }

    /// `send_payload.payload` 不是对象时必须拒绝（不能静默当成空对象）。
    #[test]
    fn send_payload_with_non_object_payload_is_bad_input() {
        assert!(parse_step_input(&obj(r#"{"type":"send_payload","payload":[]}"#)).is_err());
        assert!(parse_step_input(&obj(r#"{"type":"send_payload"}"#)).is_err());
    }

    // ---- §8 命令解析 ----

    #[test]
    fn parses_every_documented_call_command() {
        let cases: Vec<(&str, CallCommand)> = vec![
            (r#"{"method":"bootstrap"}"#, CallCommand::Bootstrap),
            (r#"{"method":"list_workspaces"}"#, CallCommand::ListWorkspaces),
            (
                r#"{"method":"open_bridge","workspaceKey":"wk","taskId":null,"timeoutMs":30000}"#,
                CallCommand::OpenBridge {
                    workspace_key: "wk".to_string(),
                    task_id: None,
                    timeout_ms: 30_000,
                },
            ),
            (
                r#"{"method":"reconnect_workspace","workspaceKey":"wk"}"#,
                CallCommand::ReconnectWorkspace {
                    workspace_key: "wk".to_string(),
                },
            ),
            (
                r#"{"method":"send_mobile_view_state","workspaceKey":"wk","taskId":null}"#,
                CallCommand::SendMobileViewState {
                    workspace_key: "wk".to_string(),
                    task_id: None,
                },
            ),
            (r#"{"method":"connect"}"#, CallCommand::Connect),
            (r#"{"method":"poke_relay"}"#, CallCommand::PokeRelay),
            (
                r#"{"method":"wait_paired","timeoutMs":30000}"#,
                CallCommand::WaitPaired { timeout_ms: 30_000 },
            ),
            (
                r#"{"method":"bridge_wait_healthy","session":1,"timeoutMs":45000}"#,
                CallCommand::BridgeWaitHealthy {
                    session: BridgeSessionId(1),
                    timeout_ms: 45_000,
                },
            ),
            (
                r#"{"method":"conversation_open","session":1,"scope":{"workspaceKey":"wk"}}"#,
                CallCommand::ConversationOpen {
                    session: BridgeSessionId(1),
                    scope: obj(r#"{"workspaceKey":"wk"}"#).as_object().unwrap().clone(),
                },
            ),
            (
                r#"{"method":"conversation_call","session":1,"key":"id-1","op":"sendText","args":{"sessionId":"s1","text":"hi"}}"#,
                CallCommand::ConversationCall {
                    session: BridgeSessionId(1),
                    key: "id-1".to_string(),
                    op: "sendText".to_string(),
                    args: obj(r#"{"sessionId":"s1","text":"hi"}"#)
                        .as_object()
                        .unwrap()
                        .clone(),
                },
            ),
            (
                r#"{"method":"conversation_close","session":1,"key":"id-1"}"#,
                CallCommand::ConversationClose {
                    session: BridgeSessionId(1),
                    key: "id-1".to_string(),
                },
            ),
            (
                r#"{"method":"automation_call","session":1,"op":"list","args":{}}"#,
                CallCommand::AutomationCall {
                    session: BridgeSessionId(1),
                    op: "list".to_string(),
                    args: Map::new(),
                },
            ),
            (
                r#"{"method":"request","payload":{"requestId":"r1"},"matchTypes":["a","b"],"matchFields":{"requestId":"r1"},"timeoutMs":30000}"#,
                CallCommand::Request {
                    payload: obj(r#"{"requestId":"r1"}"#).as_object().unwrap().clone(),
                    match_types: vec!["a".to_string(), "b".to_string()],
                    match_fields: vec![("requestId".to_string(), json!("r1"))],
                    timeout_ms: 30_000,
                },
            ),
        ];
        for (text, expected) in cases {
            assert_eq!(parse_call_command(&obj(text)), Ok(expected), "命令 {text}");
        }
    }

    /// §8：未给的 `timeoutMs` 用协议 crate 的常量默认值。
    #[test]
    fn omitted_timeouts_use_core_defaults() {
        assert_eq!(
            parse_call_command(&obj(r#"{"method":"wait_paired"}"#)),
            Ok(CallCommand::WaitPaired { timeout_ms: 60_000 })
        );
        assert_eq!(
            parse_call_command(&obj(r#"{"method":"open_bridge","workspaceKey":"wk"}"#)),
            Ok(CallCommand::OpenBridge {
                workspace_key: "wk".to_string(),
                task_id: None,
                timeout_ms: 30_000,
            })
        );
        assert_eq!(
            parse_call_command(&obj(r#"{"method":"bridge_wait_healthy","session":1}"#)),
            Ok(CallCommand::BridgeWaitHealthy {
                session: BridgeSessionId(1),
                timeout_ms: 45_000,
            })
        );
        assert_eq!(
            parse_call_command(&obj(
                r#"{"method":"request","payload":{},"matchTypes":[],"matchFields":{}}"#
            )),
            Ok(CallCommand::Request {
                payload: Map::new(),
                match_types: Vec::new(),
                match_fields: Vec::new(),
                timeout_ms: 30_000,
            })
        );
    }

    #[test]
    fn unknown_call_method_is_bad_input() {
        let error = parse_call_command(&obj(r#"{"method":"nope"}"#)).expect_err("必须失败");
        assert!(error.message.contains("未知 method: nope"), "{}", error.message);
    }

    /// §13.2 / §13.3：`args` 缺省或 `null` 视为 `{}`（§13.5.1）；`key` / `op` 必填。
    #[test]
    fn section_13_commands_treat_missing_args_as_empty_object() {
        assert_eq!(
            parse_call_command(&obj(
                r#"{"method":"conversation_call","session":1,"key":"k","op":"handshake"}"#
            )),
            Ok(CallCommand::ConversationCall {
                session: BridgeSessionId(1),
                key: "k".to_string(),
                op: "handshake".to_string(),
                args: Map::new(),
            })
        );
        assert_eq!(
            parse_call_command(&obj(
                r#"{"method":"conversation_call","session":1,"key":"k","op":"handshake","args":null}"#
            )),
            Ok(CallCommand::ConversationCall {
                session: BridgeSessionId(1),
                key: "k".to_string(),
                op: "handshake".to_string(),
                args: Map::new(),
            })
        );
        assert_eq!(
            parse_call_command(&obj(r#"{"method":"automation_call","session":1,"op":"list"}"#)),
            Ok(CallCommand::AutomationCall {
                session: BridgeSessionId(1),
                op: "list".to_string(),
                args: Map::new(),
            })
        );
    }

    /// §13：`args` 不是对象（也不是 null）→ `badInput`，不静默当成空对象。
    #[test]
    fn section_13_commands_reject_non_object_args() {
        assert!(parse_call_command(&obj(
            r#"{"method":"conversation_call","session":1,"key":"k","op":"handshake","args":[1]}"#
        ))
        .is_err());
        assert!(parse_call_command(&obj(
            r#"{"method":"automation_call","session":1,"op":"list","args":"x"}"#
        ))
        .is_err());
    }

    /// §13：`key` / `op` / `scope` / `session` 缺失一律 `badInput`。
    #[test]
    fn section_13_commands_require_their_fields() {
        assert!(parse_call_command(&obj(r#"{"method":"conversation_open","session":1}"#)).is_err());
        assert!(parse_call_command(&obj(r#"{"method":"conversation_call","session":1,"op":"x"}"#)).is_err());
        assert!(parse_call_command(&obj(r#"{"method":"conversation_call","session":1,"key":"k"}"#)).is_err());
        assert!(parse_call_command(&obj(r#"{"method":"conversation_close","key":"k"}"#)).is_err());
        assert!(parse_call_command(&obj(r#"{"method":"automation_call","session":1}"#)).is_err());
    }

    /// §8 早期草稿里的 `conversation` 命令**已废弃**：它现在就是未知方法。
    #[test]
    fn the_deprecated_conversation_command_is_gone() {
        let error = parse_call_command(&obj(
            r#"{"method":"conversation","session":1,"scope":{"workspaceKey":"wk"}}"#,
        ))
        .expect_err("必须失败");
        assert!(error.message.contains("未知 method: conversation"), "{}", error.message);
    }

    #[test]
    fn call_command_requires_workspace_key() {
        assert!(parse_call_command(&obj(r#"{"method":"open_bridge"}"#)).is_err());
        assert!(parse_call_command(&obj(r#"{"method":"reconnect_workspace"}"#)).is_err());
        assert!(parse_call_command(&obj(r#"{"method":"send_mobile_view_state"}"#)).is_err());
    }

    #[test]
    fn request_command_requires_all_three_arguments() {
        assert!(parse_call_command(&obj(r#"{"method":"request","matchTypes":[],"matchFields":{}}"#)).is_err());
        assert!(parse_call_command(&obj(r#"{"method":"request","payload":{},"matchFields":{}}"#)).is_err());
        assert!(parse_call_command(&obj(r#"{"method":"request","payload":{},"matchTypes":[]}"#)).is_err());
    }

    #[test]
    fn non_object_call_command_is_bad_input() {
        assert!(parse_call_command(&json!(7)).is_err());
    }
}
