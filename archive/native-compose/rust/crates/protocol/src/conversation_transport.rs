//! 对应 `Reference_code/lib/protocol/conversation.dart` 的 **`ConversationTransport`**
//! （含 `_SubscriptionBase` / `ConversationSubscription` / `SessionsIndexSubscription`）
//! —— Conversation V4 的**协议状态机**：握手、订阅、看门狗、CAS 重试、行区间、
//! 附件分块上传/读取、sessions-index、workspace 配置与技能。
//!
//! [`conversation`](crate::conversation) 已经移植了载荷构造器与状态类型；本模块补上
//! **"谁在什么时候发哪一次 `channels.call`"** 这一层——那才是 `ConversationTransport` 的本体。
//!
//! # 边界（纯逻辑、同步、无 I/O、不读时钟）
//!
//! | Dart 侧 | 本模块 |
//! |---|---|
//! | `await _channels.call(...)` | [`ConversationTransport::step`] / [`ConversationTransport::call_op`] 里直接调 [`ChannelRpc::call`]（返回 id），应答经 [`ConversationInput::Channel`] 回喂 |
//! | `Future` / `Completer` | [`ConversationEvent::OpAccepted`]（`token`）+ [`ConversationEvent::Completed`] |
//! | `Timer.periodic` / `Timer` | [`ConversationEvent::TimerStart`] / [`ConversationEvent::TimerCancel`]，到点回喂 [`ConversationInput::TimerFired`] |
//! | `DateTime.now()` | 一律用入参 `now_ms`（epoch 毫秒） |
//! | `session.recovered` 监听 | [`ConversationInput::BridgeRecovered`] |
//! | `session.degraded.value` | [`ConversationInput::BridgeDegraded`]（宿主从 `ZemoteEvent::BridgeDegraded` 转喂） |
//! | `await session.waitHealthy(45s)` | [`ConversationEvent::WaitHealthy`] + [`ConversationInput::BridgeHealthy`] / [`ConversationInput::BridgeHealthyTimeout`] |
//! | `Future.timeout` 抛的 `TimeoutException` | [`ConversationInput::ChannelTimeout`]（宿主超时后先 `ChannelClient::remove_pending`） |
//! | `addEventListener` / 取消函数 | [`ConversationEvent::ListenChannel`] / [`ConversationEvent::UnlistenChannel`]，注册结果经 [`ConversationInput::ListenerRegistered`] 回报 |
//! | `notifyListeners()` | [`ConversationEvent::StateChanged`] / [`RowsUpdated`](ConversationEvent::RowsUpdated) / [`SessionsIndexUpdated`](ConversationEvent::SessionsIndexUpdated) |
//!
//! **为什么监听器要绕一圈事件**：[`ChannelRpc`] 只有 `call`（没有 `addEventListener`），
//! 而 Dart 的 `_start()` 是**先注册监听器、后发 subscribe**（`addEventListener` 的
//! `ready.then` 先于 `call` 的 `ready.then` 入队，见 `channel_client.dart:105-128` 与 `:68-73`）。
//! 为了保住这个顺序，本模块发出 [`ConversationEvent::ListenChannel`] 后**挂起**订阅流程，
//! 等宿主回报监听器 id（[`ConversationInput::ListenerRegistered`]）再发 subscribe 调用。
//! 若宿主不回报，订阅流程会停在 `AwaitListener`——这是刻意的不静默降级。
//!
//! # `op` 判别式
//!
//! FFI 的 `{"method":"conversation_call","op":"sendText","args":{...}}` 由
//! [`ConversationTransport::call_op`] 解释，`op` 名一律用 **Dart 方法名原样**
//! （`sendText` / `attachmentPut` / `createSelectionSideSession` …），清单见
//! [`ConversationTransport::OPS`]。未知 `op` → `Err(ProtocolError::Other(..))`。
//!
//! # PORT-NOTE 汇总
//!
//! * `session.waitHealthy` 在 `degraded == null` 时是**已完成**的 future
//!   （`zemote_client.dart:452`），本模块据此**跳过** `WaitHealthy` 往返——线上无可观测差异。
//! * `AckedRevisions`（`conversation.rs`）没有迭代器，`snapshot().ackedRevisions` 只能列出
//!   当前/曾经订阅过的 `sessionId`。**建议提升**：给 `AckedRevisions` 加 `iter()`。
//! * `FragmentAssembler::accept` 把「base64 非法」与「字段缺失/越界」都收敛成
//!   `FragmentOutcome::Ignored`，所以 Dart 的 `'[$_logTag] bad fragment: $e'` 这行日志**无法**
//!   逐字复现（其余分支都按 Dart 记了）。**建议提升**：把 `Ignored` 拆出原因。
//! * `_resubscribe()` 与在飞的首次 `_start()` 可能并发（桥恢复的竞态）；本模块选择
//!   「新重订阅**顶掉**旧的订阅流程」，被顶掉的宿主 op 以 `InvalidState` 失败。
//!   Dart 在那个竞态下没有确定行为（旧 future 会随旧桥一起挂掉），故这是**保守取定**。
//! * Dart 的 `_onBridgeRecovered`（transport）先于各订阅的 `_onBridgeRecovered` 执行
//!   （`ChangeNotifier` 按注册顺序通知）；本模块照抄该顺序（先清握手状态，再逐个重订阅）。
//! * 本模块的 transport 级 [`ConversationTransport::dispose`] **不发 unsubscribe**：Dart 的
//!   `BridgeSession.dispose()`（`zemote_client.dart:520-527`）同样只拆传输、不逐个 dispose 订阅。
//!   需要服务端退订时，宿主应在 `conversation_close` 之前逐个调 `disposeSubscription` op。
//! * `observe_stale` / `observe_final` 在 `revisionAtDecision` 类型不符时返回 `Err`，而 Dart 的
//!   `res['revisionAtDecision'] is num` 只是让条件为假——故这里按 Dart 语义**忽略**该错误。

use std::collections::BTreeMap;

use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine as _;
use serde_json::{json, Value};

use crate::channel_client::{
    dart_json_to_string, to_dart_view, ChannelEvent, ChannelRpc, Channels,
};
use crate::conversation::{
    apply_file_rewind_payload, attachment_begin_args, attachment_begin_decision, attachment_checksum,
    attachment_chunk_args, attachment_chunk_range, attachment_commit_args, attachment_descriptor,
    attachment_progress, attachment_total_chunks, attachment_upload_id, build_command_envelope,
    conversation_ack_log_epoch, conversation_plans_args, conversation_resync_args,
    conversation_subscribe_args, conversation_topic, create_session_payload,
    delete_queue_item_payload, edit_queue_item_payload, edit_user_query_payload, empty_payload,
    file_changes_args, fork_assistant_payload, frame_matches_subscription, fresh_envelope,
    is_unsupported_reasoning_effort, parse_create_selection_side_session_result,
    parse_create_session_result, parse_hello_connection_id, parse_skills_response,
    parse_subscribe_ack, resolve_interaction_payload, response_message, resync_request_args,
    retry_turn_payload, route_wire_frame, rows_range_args, send_command_args,
    send_goal_command_payload, send_queued_now_payload, send_text_payload,
    sessions_index_subscribe_args, sessions_index_topic, set_assistant_feedback_payload,
    set_auto_drain_payload, set_followup_mode_payload, stale_retry_envelope,
    subscribe_request_args, switch_collaboration_mode_payload, switch_model_config_fallback_thought,
    switch_model_config_payload, unsubscribe_request_args, validate_attachment_progress,
    watchdog_should_resync, AckedRevisions, AttachmentBase, AttachmentBegin, AttachmentBeginRequest,
    AttachmentReader, ClientHello, CommandEnvelope, ConversationState, FragmentOutcome, JsonMap,
    SessionsIndexState, SkillEntry, SubscriptionCore, WireFrameRoute, WorkspacePrep,
    BRIDGE_HEALTHY_TIMEOUT_SECS, COMMAND_DEFAULT_TIMEOUT_SECS, CONVERSATION_FRAME_EVENT,
    CONVERSATION_RESYNC_METHOD, CONVERSATION_SUBSCRIBE_METHOD,
    CONVERSATION_UNSUBSCRIBE_METHOD, CREATE_SESSION_TIMEOUT_SECS, CREATE_SIDE_SESSION_TIMEOUT_SECS,
    FRAGMENT_CLEANUP_INTERVAL_SECS, RESUBSCRIBE_RETRY_SECS, ROWS_RANGE_DEFAULT_LIMIT,
    SESSIONS_INDEX_FRAME_EVENT, SESSIONS_INDEX_RESYNC_METHOD, SESSIONS_INDEX_SUBSCRIBE_METHOD,
    SESSIONS_INDEX_UNSUBSCRIBE_METHOD, SKILLS_TIMEOUT_SECS, SUBSCRIBE_TIMEOUT_SECS,
    WATCHDOG_INTERVAL_SECS,
};
use crate::error::{ProtocolError, Result};
use crate::id::generate_uuid;
use crate::ipc_codec::Value as IpcValue;
use crate::zemote_client::{conversation_key, TimerId};

// ============================================================ 1. 常量

/// 对应 Dart `ConversationTransport.channel`（`Channels.zcodeAgent`）。
pub const TRANSPORT_CHANNEL: &str = Channels::ZCODE_AGENT;
/// 对应 `prepareWorkspace` 用的通道（`Channels.zcodeTask`）。
pub const ZCODE_TASK_CHANNEL: &str = Channels::ZCODE_TASK;
/// 对应 `skills()` 用的通道（`Channels.skills`）。
pub const SKILLS_CHANNEL: &str = Channels::SKILLS;
/// 模型选择视图通道（桌面 `model-selection` 服务）。
pub const MODEL_SELECTION_CHANNEL: &str = Channels::MODEL_SELECTION;
/// 模型选择视图方法名。
pub const MODEL_SELECTION_GET_VIEW_METHOD: &str = "getView";
/// 模型选择视图超时（与 `skills` 同档）。
pub const MODEL_SELECTION_TIMEOUT_SECS: u64 = 20;
/// 对应 `handshake()` 第一步 `helloConversationV4`。
pub const HELLO_METHOD: &str = "helloConversationV4";
/// 对应 `handshake()` 第二步 `initializeConversationV4`。
pub const INITIALIZE_METHOD: &str = "initializeConversationV4";
/// 对应 `_sendCommandWithRetry` 的 `sendConversationCommandV4`。
pub const SEND_COMMAND_METHOD: &str = "sendConversationCommandV4";
/// 对应 `plans()` 的 `conversationPlansV4`。
pub const PLANS_METHOD: &str = "conversationPlansV4";
/// 对应 `fileChanges()` 的 `conversationFileChangesV4`。
pub const FILE_CHANGES_METHOD: &str = "conversationFileChangesV4";
/// 对应 `fileRewindPreview()` 的 `conversationFileRewindPreviewV4`。
pub const FILE_REWIND_PREVIEW_METHOD: &str = "conversationFileRewindPreviewV4";
/// 对应 `rowsRange()` 的 `conversationRowsRangeV4`。
pub const ROWS_RANGE_METHOD: &str = "conversationRowsRangeV4";
/// 对应 `attachmentPut` 的 `attachmentBeginV4`。
pub const ATTACHMENT_BEGIN_METHOD: &str = "attachmentBeginV4";
/// 对应 `attachmentPut` 的 `attachmentChunkV4`。
pub const ATTACHMENT_CHUNK_METHOD: &str = "attachmentChunkV4";
/// 对应 `attachmentPut` 的 `attachmentCommitV4`。
pub const ATTACHMENT_COMMIT_METHOD: &str = "attachmentCommitV4";
/// 对应 `attachmentRead` 的 `attachmentReadV4`。
pub const ATTACHMENT_READ_METHOD: &str = "attachmentReadV4";
/// **对端 3.15.3 已不存在这个方法**：`zcode-task` 上没有 `prepareWorkspace`
/// （见 `FlowKind::PrepareWorkspace` 的注释）。保留常量只为记录参考实现曾用过这个名字。
pub const PREPARE_WORKSPACE_METHOD: &str = "prepareWorkspace";
/// 对应 `skills()` 的 `list`。
pub const SKILLS_LIST_METHOD: &str = "list";
/// 对应 `skills()` 请求里写死的 `'provider': 'glm'`。
pub const SKILLS_PROVIDER: &str = "glm";
/// 对应 `session.waitHealthy(timeout: const Duration(seconds: 45))`。
pub const WAIT_HEALTHY_TIMEOUT_MS: i64 = BRIDGE_HEALTHY_TIMEOUT_SECS as i64 * 1000;

/// 内部握手流程在 `flows` 表里用的保留 token。
const HANDSHAKE_TOKEN: &str = "#handshake";

// ============================================================ 2. 输入

/// [`ConversationTransport::step`] 的输入。
///
/// 前三项是契约要求的最小面；其余是宿主必须回喂的"宿主能力结果"（Dart 里对应
/// `TimeoutException` / `session.degraded` / `session.waitHealthy` / `addEventListener` 返回值）。
#[derive(Debug, Clone, PartialEq)]
pub enum ConversationInput<'a> {
    /// 桥的 channel 事件（宿主先用 [`ConversationTransport::owns_channel_id`] 路由）。
    Channel(ChannelEvent),
    /// 本对象的定时器到点（watchdog / 分片清理 / 重订阅重试）。
    TimerFired(TimerId),
    /// 桥恢复了（`ZemoteEvent::BridgeRecovered`）——**必须重做握手**，服务端订阅状态随旧桥一起死了。
    BridgeRecovered,
    /// 桥降级状态变化（对应 `session.degraded.value`）。`None` 表示已恢复健康。
    ///
    /// 用途：`_sendCommandWithRetry` 的 `if (session.degraded.value == null) rethrow;`
    /// ——只有降级中才允许超时重发（否则会重复投递，例如 `sendText`）。
    BridgeDegraded {
        /// 新的降级原因；`None` = 健康。
        degraded: Option<&'a str>,
    },
    /// 宿主完成了 [`ConversationEvent::ListenChannel`] 的注册，回报 channel 订阅 id。
    ///
    /// `token` 取自 `ListenChannel`；`id` 是 `ChannelClient::add_listener` 的返回值。
    ListenerRegistered {
        /// `ListenChannel` 事件里的 token。
        token: u64,
        /// 宿主侧 `add_listener` 返回的订阅 id。
        id: u64,
    },
    /// 宿主判定某个通道请求超时（对应 Dart `Future.timeout` 抛出的 `TimeoutException`）。
    ///
    /// 宿主应：按 `call` 的 `timeout_ms` 起计时器 → 到点 `ChannelClient::remove_pending(id)`
    /// → 喂本输入。
    ChannelTimeout {
        /// 超时的请求 id。
        id: u64,
    },
    /// [`ConversationEvent::WaitHealthy`] 完成（桥已健康）。
    BridgeHealthy {
        /// `WaitHealthy` 事件里的 token。
        token: u64,
    },
    /// [`ConversationEvent::WaitHealthy`] 超时（对应 Dart `waitHealthy` 抛 `TimeoutException`）。
    BridgeHealthyTimeout {
        /// `WaitHealthy` 事件里的 token。
        token: u64,
    },
}

// ============================================================ 3. 事件

/// 一次挂起操作的结束形态 —— 对应 Dart 里那个 `async` 方法的返回值/异常。
#[derive(Debug, Clone, PartialEq)]
pub enum Completion {
    /// 无返回值（对应 Dart `Future<void>`）。
    Ok,
    /// 对应 Dart 返回的 `dynamic`（命令应答 / `plans` / `rowsRange` / `fileChanges` / `skills` …）。
    Value(Value),
    /// 对应 `createSession` / `createSelectionSideSession` 返回的 `sessionId`。
    SessionId(String),
    /// 对应 `attachmentPut` 的 `{ref, fileName, mime, bytes}`。
    AttachmentDescriptor(Value),
    /// 对应 `attachmentRead` 的 `({Uint8List bytes, String? mediaType})`（字节按 base64 出 FFI）。
    AttachmentBytes {
        /// 累积字节的 base64（标准字母表 + 填充）。
        base64: String,
        /// 对应 `mediaType`。
        media_type: Option<String>,
    },
    /// 失败：对应 Dart 抛出的异常。
    Failed(TransportError),
}

/// [`Completion::Failed`] 的错误分类（决定 Kotlin 侧的展示与重试策略）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TransportErrorKind {
    /// 对应 Dart `StateError`（如 `createSession rejected: ...`）。
    InvalidState,
    /// 对应 Dart `TimeoutException`。
    Timeout,
    /// 对应 Dart `ChannelRpcError`（通道调用被拒/失败）。
    Channel,
    /// 对应 Dart `FormatException` / `TypeError` 等协议层错误。
    Protocol,
}

/// 对应 Dart 异常的一次失败。
#[derive(Debug, Clone, PartialEq)]
pub struct TransportError {
    /// 分类。
    pub kind: TransportErrorKind,
    /// 消息（尽量与 Dart 的 `toString()` 文本一致）。
    pub message: String,
}

impl TransportError {
    /// 对应 `StateError(message)`。
    pub fn invalid_state(message: impl Into<String>) -> Self {
        Self {
            kind: TransportErrorKind::InvalidState,
            message: message.into(),
        }
    }

    /// 对应 `TimeoutException(message)`。
    pub fn timeout(message: impl Into<String>) -> Self {
        Self {
            kind: TransportErrorKind::Timeout,
            message: message.into(),
        }
    }

    /// 对应 `ChannelRpcError(message, data)`。
    pub fn channel(message: impl Into<String>) -> Self {
        Self {
            kind: TransportErrorKind::Channel,
            message: message.into(),
        }
    }

    /// 对应 `FormatException` / `TypeError` 等。
    pub fn protocol(message: impl Into<String>) -> Self {
        Self {
            kind: TransportErrorKind::Protocol,
            message: message.into(),
        }
    }

    /// 尽量复刻 Dart 异常的 `toString()`（用于 `'resubscribe failed: $e'` 这类日志）。
    pub fn dart_to_string(&self) -> String {
        match self.kind {
            TransportErrorKind::Channel => format!("ChannelRpcError: {}", self.message),
            TransportErrorKind::InvalidState => format!("Bad state: {}", self.message),
            TransportErrorKind::Timeout => format!("TimeoutException: {}", self.message),
            TransportErrorKind::Protocol => self.message.clone(),
        }
    }
}

impl std::fmt::Display for TransportError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.dart_to_string())
    }
}

/// [`ConversationTransport::step`] / [`ConversationTransport::call_op`] 的输出。
#[derive(Debug, Clone, PartialEq)]
pub enum ConversationEvent {
    /// 对应 `_log(line)` → `onLog`。
    Log(String),

    /// 请宿主起一个计时器；`delay_ms` 毫秒后回喂 [`ConversationInput::TimerFired`]。
    ///
    /// **周期定时器**（watchdog / 分片清理）到点后会再发一次**同一个** `token` 的
    /// `TimerStart`：宿主把 `TimerStart` 当成对该 token 的"重新装弹"即可。
    TimerStart {
        /// 定时器句柄。
        token: TimerId,
        /// 延迟（毫秒）。
        delay_ms: i64,
    },

    /// 取消计时器（幂等）。
    TimerCancel(TimerId),

    /// 状态变了（订阅应答、logEpoch、队列、workspace 配置缓存……），宿主应重读
    /// [`ConversationTransport::snapshot`]。
    StateChanged,

    /// 会话行/增量更新 —— 对应 Dart `ConversationState.notifyListeners()`。
    ///
    /// `frame` 是刚被应用的那条**逻辑帧**（`{topic, subscriptionId, fromSeq, toSeq, sentAt,
    /// payload}`，`payload` 为 `{kind:'snapshot', snapshot}` 或 `{kind:'deltas', deltas}`），
    /// 宿主可按 `payload.kind` 做增量渲染；其余字段是应用后的状态标量。需要完整行窗口时读
    /// [`ConversationTransport::snapshot`]（避免每次增量都复制整个 `rows`）。
    RowsUpdated {
        /// 哪个会话。
        session_id: String,
        /// 触发本次更新的逻辑帧（状态写入类 op 触发的更新为 `None`）。
        frame: Option<JsonMap>,
        /// 对应 `ready`。
        ready: bool,
        /// 对应 `seq`。
        seq: i64,
        /// 对应 `totalCount`。
        total_count: i64,
        /// 对应 `firstRowId`。
        first_row_id: Option<i64>,
        /// 当前行窗口长度（对应 `rows.length`）。
        row_count: usize,
    },

    /// sessions-index 更新 —— 对应 Dart `SessionsIndexState.notifyListeners()`。
    SessionsIndexUpdated {
        /// 对应 `workspaceId`。
        workspace_id: Option<String>,
        /// 对应 `logEpoch`。
        log_epoch: Option<String>,
        /// 对应 `seq`。
        seq: i64,
        /// 对应 `ready`。
        ready: bool,
        /// 当前会话条目数（对应 `sessions.length`）。
        session_count: usize,
    },

    /// 握手完成（`initializeConversationV4` 的应答已到）—— 对应 `_handshaken = true`。
    HandshakeDone {
        /// 服务端 hello 里的 `connectionId`（`hello` 不是 Map 或没有该键时为 `None`；
        /// 附件上传需要它）。
        connection_id: Option<String>,
    },

    /// 一次 `op` 已被接受；宿主用 `token` 与后续 [`ConversationEvent::Completed`] 关联
    /// （对应 Dart 方法返回的 `Future`）。
    OpAccepted {
        /// 关联键，形如 `sendText:4`（与 `zemote_client` 的 `pair:3` 同风格）。
        token: String,
        /// 原始 `op` 名。
        op: String,
    },

    /// 一次挂起操作结束。
    Completed {
        /// `OpAccepted` 给的关联键。
        token: String,
        /// 结束形态。
        outcome: Completion,
    },

    /// 请宿主注册动态事件监听器
    /// （对应 `_channels.addEventListener(channel, event, handler, arg: scope)`）。
    ///
    /// 宿主注册后**必须**喂 [`ConversationInput::ListenerRegistered`]，否则该订阅流程挂起。
    ListenChannel {
        /// 关联键（回报时带回）。
        token: u64,
        /// 通道名（`zcode-agent`）。
        channel: &'static str,
        /// 事件名（`onDynamicConversationFrame` / `onDynamicSessionsIndexFrame`）。
        event: &'static str,
        /// 对应 Dart 的 `arg:`（就是 `scope`）。
        arg: Value,
    },

    /// 请宿主取消监听器（对应 `addEventListener` 返回的那个取消函数 → `reqEventDispose`）。
    UnlistenChannel {
        /// 宿主侧 `add_listener` 返回的订阅 id。
        id: u64,
    },

    /// 订阅成功（`ack.subscriptionId` 已拿到）。
    Subscribed {
        /// 会话 id；sessions-index 订阅为 `None`。
        session_id: Option<String>,
        /// 对应 `ack.subscriptionId`。
        subscription_id: String,
    },

    /// 请宿主等待桥恢复健康（对应 `await session.waitHealthy(timeout: 45s)`）。
    ///
    /// 宿主实现方式：`ZemoteClient::bridge_wait_healthy(session, token)`，完成喂
    /// [`ConversationInput::BridgeHealthy`]，超时喂 [`ConversationInput::BridgeHealthyTimeout`]。
    WaitHealthy {
        /// 关联键。
        token: u64,
        /// 超时（毫秒），固定 [`WAIT_HEALTHY_TIMEOUT_MS`]。
        timeout_ms: i64,
    },

    /// 附件上传进度（对应 `onProgress?.call(p)`）。
    AttachmentProgress {
        /// 发起该上传的 op token。
        token: String,
        /// 会话 id。
        session_id: String,
        /// 对应 `uploadId`（`upload-<uuid>`）。
        upload_id: String,
        /// 进度（0.0–1.0）。
        progress: f64,
    },
}

// ============================================================ 4. 内部类型

/// `clientId` / `commandId` 的熵源。对应 Dart 的 `generateUuid()`。
enum UuidSource {
    /// 生产路径：操作系统 CSPRNG（见 `crate::id`）。
    Os,
    /// 测试路径：确定性 uuid。
    #[cfg(test)]
    Injected(Box<dyn FnMut() -> String>),
}

impl UuidSource {
    fn next_uuid(&mut self) -> String {
        match self {
            UuidSource::Os => generate_uuid(),
            #[cfg(test)]
            UuidSource::Injected(f) => f(),
        }
    }
}

impl std::fmt::Debug for UuidSource {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("UuidSource")
    }
}

/// 订阅种类 —— 对应 Dart 的两个子类。
#[derive(Debug, Clone, PartialEq)]
enum SubKind {
    /// `ConversationSubscription`（`_logTag = 'v4'`，带 watchdog）。
    Conversation {
        /// 会话 id。
        session_id: String,
    },
    /// `SessionsIndexSubscription`（`_logTag = 'v4-si'`，无 watchdog）。
    SessionsIndex,
}

impl SubKind {
    /// 对应 `_logTag`。
    fn log_tag(&self) -> &'static str {
        match self {
            SubKind::Conversation { .. } => "v4",
            SubKind::SessionsIndex => "v4-si",
        }
    }

    /// 对应 `_frameEventName`。
    fn frame_event(&self) -> &'static str {
        match self {
            SubKind::Conversation { .. } => CONVERSATION_FRAME_EVENT,
            SubKind::SessionsIndex => SESSIONS_INDEX_FRAME_EVENT,
        }
    }

    /// 对应 `_subscribeMethod`。
    fn subscribe_method(&self) -> &'static str {
        match self {
            SubKind::Conversation { .. } => CONVERSATION_SUBSCRIBE_METHOD,
            SubKind::SessionsIndex => SESSIONS_INDEX_SUBSCRIBE_METHOD,
        }
    }

    /// 对应 `_unsubscribeMethod`。
    fn unsubscribe_method(&self) -> &'static str {
        match self {
            SubKind::Conversation { .. } => CONVERSATION_UNSUBSCRIBE_METHOD,
            SubKind::SessionsIndex => SESSIONS_INDEX_UNSUBSCRIBE_METHOD,
        }
    }

    /// 对应 `_resyncMethod`。
    fn resync_method(&self) -> &'static str {
        match self {
            SubKind::Conversation { .. } => CONVERSATION_RESYNC_METHOD,
            SubKind::SessionsIndex => SESSIONS_INDEX_RESYNC_METHOD,
        }
    }

    /// 对应 `_subscribeArgs`。
    fn subscribe_args(&self) -> JsonMap {
        match self {
            SubKind::Conversation { session_id } => conversation_subscribe_args(session_id),
            SubKind::SessionsIndex => sessions_index_subscribe_args(),
        }
    }

    /// 对应 `_unsubscribeArgs`（conversation 为空映射，sessions-index 带 runtimePolicy）。
    fn unsubscribe_args(&self) -> JsonMap {
        match self {
            SubKind::Conversation { .. } => JsonMap::new(),
            SubKind::SessionsIndex => sessions_index_subscribe_args(),
        }
    }

    /// 对应 `_resyncArgs`（conversation 是 `{forceSnapshot: true}`）。
    fn resync_args(&self) -> JsonMap {
        match self {
            SubKind::Conversation { .. } => conversation_resync_args(),
            SubKind::SessionsIndex => sessions_index_subscribe_args(),
        }
    }

    /// 对应 `topic` getter。
    fn topic(&self, scope: &JsonMap) -> String {
        match self {
            SubKind::Conversation { session_id } => conversation_topic(session_id),
            SubKind::SessionsIndex => sessions_index_topic(scope),
        }
    }
}

/// 订阅的状态容器 —— 对应 Dart 里两个 `*State`。
#[derive(Debug)]
enum SubState {
    /// `ConversationState`。
    Conversation(Box<ConversationState>),
    /// `SessionsIndexState`。
    SessionsIndex(Box<SessionsIndexState>),
}

impl SubState {
    /// 对应 `state.revision`（sessions-index 没有这个概念，恒 0）。
    fn revision(&self) -> i64 {
        match self {
            SubState::Conversation(s) => s.revision(),
            SubState::SessionsIndex(_) => 0,
        }
    }

    /// 对应 `state.logEpoch`。
    fn log_epoch(&self) -> Option<String> {
        match self {
            SubState::Conversation(s) => s.log_epoch.clone(),
            SubState::SessionsIndex(s) => s.log_epoch.clone(),
        }
    }

    /// 对应 `state.seq`。
    fn seq(&self) -> i64 {
        match self {
            SubState::Conversation(s) => s.seq,
            SubState::SessionsIndex(s) => s.seq,
        }
    }

    /// 对应 `state.applyFrame(frame, onGap: ...)`。
    fn apply_frame(&mut self, frame: &Value, on_gap: &mut dyn FnMut()) -> Result<()> {
        match self {
            SubState::Conversation(s) => s.apply_frame(frame, on_gap),
            SubState::SessionsIndex(s) => s.apply_frame(frame, on_gap),
        }
    }
}

/// 一个订阅 —— 对应 `_SubscriptionBase` 的子类实例。
#[derive(Debug)]
struct Subscription {
    /// 种类。
    kind: SubKind,
    /// 对应 `_SubscriptionBase` 的纯状态部分（订阅 id、暂存帧、分片表、标志位）。
    core: SubscriptionCore,
    /// 对应 `state`。
    state: SubState,
    /// 对应 `_cancelFrameListener` 对应的 channel 订阅 id。
    listener: Option<u64>,
    /// 对应 `_lastFrameAt`（只有 conversation 会用）。
    last_frame_at_ms: i64,
    /// 对应 `_watchdog`。
    watchdog: Option<TimerId>,
    /// 对应 `_fragmentCleanup`。
    fragment_cleanup: Option<TimerId>,
    /// 对应 `_resubscribeTimer`。
    resubscribe_timer: Option<TimerId>,
    /// `_start()` 是否成功过。
    started: bool,
}

/// 挂起流程的阶段。
#[derive(Debug, Clone, PartialEq)]
enum FlowStage {
    /// 前置条件已满足，可以推进（新流程的初值）。
    Ready,
    /// 等握手完成（对应 `await handshake()`）。
    AwaitHandshake,
    /// 等桥健康（对应 `await session.waitHealthy(45s)`）。
    AwaitHealthy {
        /// `WaitHealthy` 事件里的 token。
        healthy_token: u64,
    },
    /// 等宿主注册监听器（对应 `addEventListener` 的返回值）。
    AwaitListener {
        /// `ListenChannel` 事件里的 token。
        listener_token: u64,
    },
    /// 已发出通道调用，等应答。
    Sent {
        /// 通道请求 id。
        call: u64,
    },
}

/// 命令流的 post 处理（对应 Dart 里包在 `sendCommand` 外层的几个方法体）。
#[derive(Debug, Clone, PartialEq)]
enum CommandPost {
    /// 原样返回应答。
    None,
    /// `createSession`：要求 `status == 'accepted'` 并取 `result.sessionId`。
    CreateSession,
    /// `createSelectionSideSession`：`accepted` 或 `duplicate`，取 `result.sessionId`。
    CreateSideSession,
    /// `switchModelConfig`：`Unsupported reasoning effort` 时换另一族默认 thought 重发。
    SwitchModelConfig {
        /// 请求里的 provider。
        provider: String,
        /// 请求里的 model。
        model: String,
        /// 请求里的 thought。
        thought: String,
    },
}

/// `sendCommand` 的原始参数（信封在 gate 打开后才构造，因为 `baseRevision`/`issuedAt`
/// 要读**当时**的订阅状态与时钟）。
#[derive(Debug, Clone)]
struct CommandParams {
    session_id: Option<String>,
    command_type: String,
    payload: Value,
    timeout_ms: i64,
    post: CommandPost,
}

/// 一次 `sendCommand` 的运行态（对应 Dart `sendCommand` 的局部变量 + `_sendCommandWithRetry`）。
#[derive(Debug, Clone)]
struct CommandFlow {
    session_id: Option<String>,
    command_type: String,
    timeout_ms: i64,
    post: CommandPost,
    /// 原始信封（stale 重试以它为基底：`{...envelope, ...}`）。
    base_envelope: CommandEnvelope,
    /// 本次 `_sendCommandWithRetry` 的信封。
    envelope: CommandEnvelope,
    /// 是否处于 stale 重试（对应 `res` 来自 `retryEnvelope`）。
    stale: bool,
    /// 本次 `_sendCommandWithRetry` 内是否已因超时重发过一次。
    retried: bool,
    /// 超时重发用的新信封（等桥恢复期间暂存）。
    next_envelope: Option<CommandEnvelope>,
}

/// 命令应答的处理结果。
enum CommandOutcome {
    /// 流程结束（已完成/已失败）。
    Done,
    /// 已重新发出一条命令调用（stale 重试 / 超时重发），流程继续。
    Resent {
        /// 新的通道请求 id。
        call: u64,
    },
    /// 需要重新过 gate（`switchModelConfig` 的 thought 兜底）。
    Restart(Box<CommandParams>),
}

/// 单次请求类调用的种类（对应 Dart 里直接 `await _channels.call(...)` 的那几个方法）。
#[derive(Debug, Clone)]
enum SimpleCall {
    /// `plans(sessionId)`。
    Plans {
        /// 会话 id。
        session_id: String,
    },
    /// `fileChanges(sessionId, target:, baseRevision:, baseLogEpoch:)`。
    FileChanges {
        /// 会话 id。
        session_id: String,
        /// 行目标。
        target: JsonMap,
        /// 可选基准修订号。
        base_revision: Option<i64>,
        /// 可选基准 logEpoch。
        base_log_epoch: Option<String>,
    },
    /// `fileRewindPreview(...)`。
    FileRewindPreview {
        /// 会话 id。
        session_id: String,
        /// 行目标。
        target: JsonMap,
        /// 可选基准修订号。
        base_revision: Option<i64>,
        /// 可选基准 logEpoch。
        base_log_epoch: Option<String>,
    },
    /// `rowsRange(sessionId, beforeRowId:, limit:)`。
    RowsRange {
        /// 会话 id。
        session_id: String,
        /// 分页游标（最老一行）。
        before_row_id: Option<i64>,
        /// 条数上限。
        limit: i64,
    },
    /// `skills()`（通道 `skills`，无握手前置）。
    Skills,
    /// `modelSelectionView(selection?)`（通道 `model-selection.getView`）。
    ModelSelectionView {
        /// 可选 `{providerId, modelId, options?}`，用于让服务端给出 effectiveSelection。
        selection: Option<JsonMap>,
    },
}

/// 附件上传的阶段。
#[derive(Debug, Clone, Copy, PartialEq)]
enum UploadPhase {
    /// 已发 `attachmentBeginV4`，等应答。
    Begin,
    /// 待发下一个分块或 commit。
    Between,
    /// 已发 `attachmentChunkV4`，等应答。
    AwaitChunk,
    /// 已发 `attachmentCommitV4`，等应答。
    AwaitCommit,
}

/// `attachmentPut` 的运行态（对应 Dart `attachmentPut` 的局部变量）。
#[derive(Debug, Clone)]
struct UploadFlow {
    session_id: String,
    file_name: String,
    mime: String,
    bytes: Vec<u8>,
    upload_id: String,
    base: AttachmentBase,
    total_chunks: usize,
    checksum: String,
    /// 下一个要传的分块下标。
    next_chunk: usize,
    phase: UploadPhase,
}

/// `attachmentRead` 的运行态。
#[derive(Debug, Clone)]
struct ReadFlow {
    session_id: String,
    reference: String,
    reader: AttachmentReader,
}

/// 订阅流程的阶段。
#[derive(Debug, Clone, Copy, PartialEq)]
enum SubscribePhase {
    /// 先取消旧监听器 + 旧订阅（仅 resubscribe 走到这里）。
    CancelOld,
    /// 已发 unsubscribe，等应答（结果忽略）。
    AwaitUnsubscribe,
    /// 注册监听器（已发 `ListenChannel`，等 `ListenerRegistered`）。
    RegisterListener,
    /// 已发 subscribe，等 ack。
    AwaitAck,
}

/// 订阅流程（`_start()` 或 `_resubscribe()`）。
#[derive(Debug, Clone)]
struct SubscribeFlow {
    /// 已存在的订阅（resubscribe）或 `None`（首次订阅：握手后新建）。
    sub_key: Option<u64>,
    /// 首次订阅要建的会话种类。
    kind: SubKind,
    /// 是否是 `_resubscribe()`（影响失败后的处置：重试定时器 vs 直接失败并 dispose）。
    resubscribe: bool,
    phase: SubscribePhase,
}

/// 握手的两步。
#[derive(Debug, Clone, Copy, PartialEq)]
enum HandshakePhase {
    /// 待发 `helloConversationV4`。
    Hello,
    /// 已发 hello，待发 `initializeConversationV4`。
    Initialize,
}

/// 流程种类。
#[derive(Debug, Clone)]
enum FlowKind {
    /// 占位（`mem::replace` 期间用）。
    None,
    /// 握手（内部流程，token 恒为 [`HANDSHAKE_TOKEN`]）。
    Handshake {
        /// 当前处于哪一步。
        phase: HandshakePhase,
    },
    /// 只等握手（`handshake` op；握手成功即 `Completed{Ok}`）。
    HandshakeWaiter,
    /// 命令（信封还没构造，gate 打开后构造）。
    PendingCommand(Box<CommandParams>),
    /// 命令（信封已构造）。
    Command(Box<CommandFlow>),
    /// 订阅 / 重订阅。
    Subscribe(Box<SubscribeFlow>),
    /// resync（结果忽略，失败记日志，结束时清 `_resyncing`）。
    Resync {
        /// 哪个订阅。
        sub_key: u64,
    },
    /// 单次请求。
    Simple(Box<SimpleCall>),
    /// `prepareWorkspace`（带缓存）。
    PrepareWorkspace {
        /// 对应 `refresh` 具名参数。
        refresh: bool,
    },
    /// 附件上传。
    Upload(Box<UploadFlow>),
    /// 附件读取。
    Read(Box<ReadFlow>),
    /// 发出去就不管的调用（dispose 的 unsubscribe）。
    Ignore {
        /// 通道名。
        channel: &'static str,
        /// 方法名。
        method: &'static str,
        /// 参数。
        args: Value,
        /// 超时。
        timeout_ms: i64,
    },
}

/// 一个挂起流程 —— 对应 Dart 里一个尚未完成的 `async` 方法调用。
#[derive(Debug, Clone)]
struct Flow {
    /// 关联键；宿主 op 是 `"<op>:<n>"`，内部流程是 `"#..."`。
    token: String,
    /// 对应的 `op` 名；`None` 表示内部流程（不发 `OpAccepted`/`Completed`）。
    op: Option<String>,
    stage: FlowStage,
    /// 是否已经确认桥健康（`WaitHealthy` 返回过）——防止重复等待。
    healthy_ok: bool,
    kind: FlowKind,
}

impl Flow {
    /// 前置条件是否已满足（只处理握手；健康等待由 `advance_flow` 判）。
    fn can_advance(&self, handshaken: bool) -> bool {
        match self.stage {
            FlowStage::Ready => true,
            FlowStage::AwaitHandshake => handshaken,
            _ => false,
        }
    }
}

// ============================================================ 5. ConversationTransport

/// 对应 Dart `ConversationTransport`。
///
/// 纯状态机：所有 I/O 通过入参 `channels` 与宿主回喂的 [`ConversationInput`] 进出。
#[derive(Debug)]
pub struct ConversationTransport {
    scope: JsonMap,
    key: String,
    app_version: String,
    /// 对应 `clientId`（构造时 `generateUuid()`）。
    client_id: String,
    uuid: UuidSource,

    /// 对应 `_handshaken`。
    handshaken: bool,
    /// 对应服务端 hello 里的 `connectionId`。
    connection_id: Option<String>,
    /// 对应 `session.degraded.value`（宿主从 [`ConversationInput::BridgeDegraded`] 喂）。
    bridge_degraded: Option<String>,
    /// 对应 `_ackedRevisions`。
    acked: AckedRevisions,
    /// 对应 `_prep`。
    prep: Option<WorkspacePrep>,

    /// 订阅表（key → 订阅）。
    subscriptions: BTreeMap<u64, Subscription>,
    /// 订阅创建顺序（`BridgeRecovered` 按序重订阅）。
    sub_order: Vec<u64>,
    /// 对应 `_subscriptions`（sessionId → 订阅）。
    by_session: BTreeMap<String, u64>,
    next_sub_key: u64,

    /// 已发出但未结束的通道调用：请求 id → 流程 token。
    by_call: BTreeMap<u64, String>,
    /// 监听器 id → 订阅 key。
    listeners: BTreeMap<u64, u64>,
    /// 挂起流程表。
    flows: BTreeMap<String, Flow>,
    /// 流程/令牌序号。
    next_flow_seq: u64,
    /// 定时器句柄序号。
    next_timer: u64,
    /// 监听器注册令牌序号。
    next_listener_token: u64,
    /// 等待桥健康的令牌序号。
    next_healthy_token: u64,
    /// 对应 `conversation_close`：销毁后忽略一切输入。
    disposed: bool,
}

impl ConversationTransport {
    /// 支持的 `op` 名清单（FFI 侧校验用）。
    ///
    /// 一律是 **Dart 方法名原样**；参数名也是 Dart 的具名参数名。
    pub const OPS: &'static [&'static str] = &[
        // ---- 握手与订阅 ----
        "handshake",
        "subscribe",
        "subscribeSessionsIndex",
        "disposeSubscription",
        "disposeSessionsIndex",
        // ---- 命令（`sendCommand` 家族）----
        "sendCommand",
        "sendText",
        "sendGoalCommand",
        "pauseGoal",
        "resumeGoal",
        "stop",
        "compact",
        "switchModelConfig",
        "switchCollaborationMode",
        "setFollowupMode",
        "setAssistantFeedback",
        "retryTurn",
        "sendQueuedNow",
        "editQueueItem",
        "deleteQueueItem",
        "setAutoDrain",
        "forkAssistant",
        "editUserQuery",
        "applyFileRewind",
        "createSession",
        "createSelectionSideSession",
        "resolveInteraction",
        // ---- 查询 ----
        "plans",
        "fileChanges",
        "fileRewindPreview",
        "rowsRange",
        "modelSelectionView",
        // ---- 附件 ----
        "attachmentPut",
        "attachmentRead",
        // ---- workspace / skills ----
        "prepareWorkspace",
        "skills",
        // ---- `ConversationState` 写入面（Dart 里由 UI 直接改 state）----
        "setHistoryExhausted",
        "prependOlderRows",
        "optimisticPatch",
        "optimisticRowUpdate",
        "optimisticRemoveQueueItem",
    ];

    /// 对应 Dart `ConversationTransport({required session, required scope, appVersion, onLog})`。
    ///
    /// `app_version` 默认用 [`CONVERSATION_PROTOCOL_APP_VERSION`]（这是**桌面 Conversation
    /// 协议能力版本**，不是 Zemote 发布版本；发 0.x 会让服务端协商关掉 V4 能力）。
    ///
    /// PORT-NOTE: `clientId` 在构造时就用 OS CSPRNG 生成（照抄 Dart 的字段初始化
    /// `final String clientId = generateUuid()`）；测试用注入确定性 uuid 的私有构造器。
    pub fn new(scope: JsonMap, app_version: &str) -> Self {
        Self::build(scope, app_version, UuidSource::Os)
    }

    /// 测试/宿主注入确定性 uuid 的构造器（不在 FFI 面上）。
    #[cfg(test)]
    fn with_uuid_source(
        scope: JsonMap,
        app_version: &str,
        source: Box<dyn FnMut() -> String>,
    ) -> Self {
        Self::build(scope, app_version, UuidSource::Injected(source))
    }

    fn build(scope: JsonMap, app_version: &str, uuid: UuidSource) -> Self {
        let key = conversation_key(&scope);
        let mut uuid = uuid;
        let client_id = uuid.next_uuid();
        Self {
            scope,
            key,
            app_version: app_version.to_string(),
            client_id,
            uuid,
            handshaken: false,
            connection_id: None,
            bridge_degraded: None,
            acked: AckedRevisions::default(),
            prep: None,
            subscriptions: BTreeMap::new(),
            sub_order: Vec::new(),
            by_session: BTreeMap::new(),
            next_sub_key: 0,
            by_call: BTreeMap::new(),
            listeners: BTreeMap::new(),
            flows: BTreeMap::new(),
            next_flow_seq: 0,
            next_timer: 0,
            next_listener_token: 0,
            next_healthy_token: 0,
            disposed: false,
        }
    }

    /// conversation key（与 [`crate::zemote_client::conversation_key`] 一致）。
    pub fn key(&self) -> &str {
        &self.key
    }

    /// 对应 `connectionId`（服务端 hello 给的；附件上传需要）。
    pub fn connection_id(&self) -> Option<&str> {
        self.connection_id.as_deref()
    }

    /// 对应 `_handshaken`。
    pub fn is_handshaken(&self) -> bool {
        self.handshaken
    }

    /// **路由用**：宿主拿到 [`ChannelEvent`] 后判断它属于哪个 conversation。
    ///
    /// 返回 `true` 表示该 id 是**本对象当前仍挂起**的通道请求 id，或本对象发起的监听器
    /// 订阅 id。已经结束的请求返回 `false`（Dart 里 `_handlers.remove(id)` 之后同 id 的
    /// 响应会被静默忽略，语义一致）。
    pub fn owns_channel_id(&self, id: u64) -> bool {
        self.by_call.contains_key(&id) || self.listeners.contains_key(&id)
    }

    /// 纯状态机入口。`channels` 由宿主传入（桥的 [`ChannelRpc`]）。
    pub fn step(
        &mut self,
        input: ConversationInput<'_>,
        channels: &mut dyn ChannelRpc,
        now_ms: i64,
    ) -> Vec<ConversationEvent> {
        let mut events = Vec::new();
        if self.disposed {
            return events;
        }
        match input {
            ConversationInput::Channel(event) => {
                self.on_channel_event(event, channels, now_ms, &mut events)
            }
            ConversationInput::TimerFired(token) => {
                self.on_timer(token, channels, now_ms, &mut events)
            }
            ConversationInput::BridgeRecovered => {
                self.on_bridge_recovered(channels, now_ms, &mut events)
            }
            ConversationInput::BridgeDegraded { degraded } => {
                // 对应 `session.degraded` 的 ValueNotifier：同值不通知（这里也只存值）。
                self.bridge_degraded = degraded.map(|s| s.to_string());
            }
            ConversationInput::ListenerRegistered { token, id } => {
                self.on_listener_registered(token, id, channels, now_ms, &mut events)
            }
            ConversationInput::ChannelTimeout { id } => {
                self.on_channel_timeout(id, channels, now_ms, &mut events)
            }
            ConversationInput::BridgeHealthy { token } => {
                self.on_bridge_healthy(token, &mut events)
            }
            ConversationInput::BridgeHealthyTimeout { token } => {
                self.on_bridge_healthy_timeout(token, &mut events)
            }
        }
        self.pump(channels, now_ms, &mut events);
        events
    }

    /// FFI 的 `op` 判别式入口：`{"method":"conversation_call","op":"sendText","args":{...}}`。
    ///
    /// 未知 `op` → `Err(ProtocolError::Other(..))`；参数缺失/类型不符 → 同样 `Other`。
    pub fn call_op(
        &mut self,
        op: &str,
        args: &Value,
        channels: &mut dyn ChannelRpc,
        now_ms: i64,
    ) -> Result<Vec<ConversationEvent>> {
        if self.disposed {
            return Err(ProtocolError::InvalidState(
                "conversation transport disposed".to_string(),
            ));
        }
        if !Self::OPS.contains(&op) {
            return Err(ProtocolError::Other(format!(
                "unknown conversation op: {op}"
            )));
        }
        let args = args_object(op, args)?;
        let mut events = Vec::new();
        self.dispatch_op(op, &args, channels, now_ms, &mut events)?;
        self.pump(channels, now_ms, &mut events);
        Ok(events)
    }

    /// 当前状态的 JSON 快照。
    ///
    /// 顶层键：`key` / `clientId` / `appVersion` / `handshaken` / `connectionId` /
    /// `bridgeDegraded` / `ackedRevisions` / `conversations` / `sessionsIndex` / `workspacePrep`。
    ///
    /// `conversations` 以 sessionId 为键，值是 `ConversationState` 的**全部字段与派生 getter**
    /// （`snapshot` / `rows` / `seq` / `logEpoch` / `firstRowId` / `totalCount` / `ready` /
    /// `historyExhausted` / `control` / `revision` / `phase` / `canStop` / `isRunning` /
    /// `config` / `currentModel` / `currentThought` / `currentMode` / `thoughtLevels` /
    /// `queue` / `queueItems` / `autoDrain` / `usage` / `canLoadOlder` / `oldestRowId` /
    /// `backgroundWorks` / `goal` / `plan` / `inputRoutingMode` / `pendingInteractions` /
    /// `hasStreamingRow`），外加本模块的订阅元数据（`subscriptionId` / `disposed` /
    /// `started` / `listenerId` / `resyncing` / `stagedFrameCount` / `pendingFragmentCount` /
    /// `lastFrameAtMs`）。
    ///
    /// `sessionsIndex` 是数组（Dart 允许同一 transport 上存在多个 sessions-index 订阅），
    /// 每项是 `SessionsIndexState` 的字段与 `list`（按 `lastActivityAt` 倒序的原始条目）。
    ///
    /// `workspacePrep` 是 `prepareWorkspace` 的缓存（原始应答，未取过为 `null`）。
    pub fn snapshot(&self) -> Value {
        let mut conversations = JsonMap::new();
        let mut index_subs = Vec::new();
        for key in &self.sub_order {
            let Some(sub) = self.subscriptions.get(key) else {
                continue;
            };
            // 已 dispose 的订阅对应 Dart 里 `_subscriptions.remove(...)` 之后的不可见状态。
            if sub.core.disposed {
                continue;
            }
            match (&sub.kind, &sub.state) {
                (SubKind::Conversation { session_id }, SubState::Conversation(state)) => {
                    conversations
                        .insert(session_id.clone(), conversation_state_snapshot(sub, state));
                }
                (SubKind::SessionsIndex, SubState::SessionsIndex(state)) => {
                    index_subs.push(sessions_index_snapshot(sub, state));
                }
                _ => {}
            }
        }
        // PORT-NOTE: `AckedRevisions` 没有迭代器，只能列当前/曾经订阅过的 sessionId。
        let acked: JsonMap = self
            .by_session
            .keys()
            .map(|sid| (sid.clone(), json!(self.acked.get(sid))))
            .collect();
        json!({
            "key": self.key,
            "clientId": self.client_id,
            "appVersion": self.app_version,
            "handshaken": self.handshaken,
            "connectionId": self.connection_id,
            "bridgeDegraded": self.bridge_degraded,
            "ackedRevisions": Value::Object(acked),
            "conversations": Value::Object(conversations),
            "sessionsIndex": Value::Array(index_subs),
            "workspacePrep": match &self.prep {
                Some(prep) => Value::Object(prep.raw.clone()),
                None => Value::Null,
            },
        })
    }

    /// 销毁：等价于对所有订阅调 `dispose()`（Dart 里由 `BridgeSession` 整体销毁承载）。
    ///
    /// 返回需要宿主执行的事件（取消定时器、取消监听器）。
    /// **不发 unsubscribe**（见模块头 PORT-NOTE）；之后 `step` / `call_op` 不再产生任何输出。
    pub fn dispose(&mut self, now_ms: i64) -> Vec<ConversationEvent> {
        // Dart 的 `dispose()` 不用时钟；签名保留 `now_ms` 与其它入口对称。
        let _ = now_ms;
        let mut events = Vec::new();
        if self.disposed {
            return events;
        }
        self.disposed = true;
        let keys: Vec<u64> = self.sub_order.clone();
        for key in keys {
            self.dispose_subscription(key, &mut events);
        }
        // 未完成的调用不再关心（宿主会自己清理 pending）。
        self.flows.clear();
        self.by_call.clear();
        self.listeners.clear();
        events
    }

    // -------------------------------------------------------- op 分派

    fn dispatch_op(
        &mut self,
        op: &str,
        args: &JsonMap,
        _channels: &mut dyn ChannelRpc,
        _now_ms: i64,
        events: &mut Vec<ConversationEvent>,
    ) -> Result<()> {
        match op {
            // ---- 握手 ----
            "handshake" => {
                self.enqueue_flow(op, FlowKind::HandshakeWaiter, FlowStage::AwaitHandshake, events);
            }
            // ---- 订阅 ----
            "subscribe" => {
                let session_id = req_str(op, args, "sessionId")?;
                self.enqueue_flow(
                    op,
                    FlowKind::Subscribe(Box::new(SubscribeFlow {
                        sub_key: None,
                        kind: SubKind::Conversation { session_id },
                        resubscribe: false,
                        phase: SubscribePhase::RegisterListener,
                    })),
                    FlowStage::AwaitHandshake,
                    events,
                );
            }
            "subscribeSessionsIndex" => {
                self.enqueue_flow(
                    op,
                    FlowKind::Subscribe(Box::new(SubscribeFlow {
                        sub_key: None,
                        kind: SubKind::SessionsIndex,
                        resubscribe: false,
                        phase: SubscribePhase::RegisterListener,
                    })),
                    FlowStage::AwaitHandshake,
                    events,
                );
            }
            "disposeSubscription" => {
                let session_id = req_str(op, args, "sessionId")?;
                let token = self.new_op_token(op);
                events.push(ConversationEvent::OpAccepted {
                    token: token.clone(),
                    op: op.to_string(),
                });
                if let Some(key) = self.by_session.get(&session_id).copied() {
                    if let Some(call) = self.dispose_subscription(key, events) {
                        self.enqueue_ignore_call(call.method, call.args);
                    }
                }
                events.push(ConversationEvent::Completed {
                    token,
                    outcome: Completion::Ok,
                });
            }
            "disposeSessionsIndex" => {
                let token = self.new_op_token(op);
                events.push(ConversationEvent::OpAccepted {
                    token: token.clone(),
                    op: op.to_string(),
                });
                let keys: Vec<u64> = self
                    .sub_order
                    .iter()
                    .copied()
                    .filter(|k| {
                        self.subscriptions
                            .get(k)
                            .map(|s| matches!(s.kind, SubKind::SessionsIndex))
                            .unwrap_or(false)
                    })
                    .collect();
                for key in keys {
                    if let Some(call) = self.dispose_subscription(key, events) {
                        self.enqueue_ignore_call(call.method, call.args);
                    }
                }
                events.push(ConversationEvent::Completed {
                    token,
                    outcome: Completion::Ok,
                });
            }
            // ---- 命令家族 ----
            "sendCommand" => {
                let session_id = opt_str(op, args, "sessionId")?;
                let command_type = req_str(op, args, "type")?;
                let payload = match args.get("payload") {
                    Some(Value::Null) | None => empty_payload(),
                    Some(v) => v.clone(),
                };
                let timeout_ms = opt_timeout_ms(op, args)?;
                self.enqueue_flow(
                    op,
                    FlowKind::PendingCommand(Box::new(CommandParams {
                        session_id,
                        command_type,
                        payload,
                        timeout_ms,
                        post: CommandPost::None,
                    })),
                    FlowStage::AwaitHandshake,
                    events,
                );
            }
            "sendText" => {
                let session_id = req_str(op, args, "sessionId")?;
                let text = req_str(op, args, "text")?;
                let mode = opt_str(op, args, "mode")?;
                let plan_enabled = opt_bool(op, args, "planEnabled")?;
                let model_selection = opt_obj(op, args, "modelSelection")?;
                let attachments = opt_obj_list(op, args, "attachments")?;
                let held_queue_disposition = opt_str(op, args, "heldQueueDisposition")?;
                let expected = opt_str_list(op, args, "expectedHeldQueueItemIds")?;
                let automation_id = opt_str(op, args, "automationId")?;
                let off_peak_task_id = opt_str(op, args, "offPeakTaskId")?;
                let off_peak_run_type = opt_str(op, args, "offPeakRunType")?;
                let bot_delivery_target = opt_str(op, args, "botDeliveryTarget")?;
                let tool_disallowlist = opt_str_list(op, args, "toolDisallowlist")?;
                let payload = send_text_payload(
                    &text,
                    mode.as_deref(),
                    plan_enabled,
                    model_selection.as_ref(),
                    attachments.as_deref(),
                    held_queue_disposition.as_deref(),
                    expected.as_deref(),
                    automation_id.as_deref(),
                    off_peak_task_id.as_deref(),
                    off_peak_run_type.as_deref(),
                    bot_delivery_target.as_deref(),
                    tool_disallowlist.as_deref(),
                );
                self.enqueue_command(op, Some(session_id), "sendText", payload, None, events);
            }
            "sendGoalCommand" => {
                let session_id = req_str(op, args, "sessionId")?;
                let text = req_str(op, args, "text")?;
                let display_text = opt_str(op, args, "displayText")?;
                let held_queue_disposition = opt_str(op, args, "heldQueueDisposition")?;
                let expected = opt_str_list(op, args, "expectedHeldQueueItemIds")?;
                let payload = send_goal_command_payload(
                    &text,
                    display_text.as_deref(),
                    held_queue_disposition.as_deref(),
                    expected.as_deref(),
                );
                self.enqueue_command(op, Some(session_id), "sendGoalCommand", payload, None, events);
            }
            "pauseGoal" | "resumeGoal" | "stop" | "compact" => {
                let session_id = req_str(op, args, "sessionId")?;
                self.enqueue_command(op, Some(session_id), op, empty_payload(), None, events);
            }
            "switchModelConfig" => {
                let session_id = req_str(op, args, "sessionId")?;
                let provider = req_str(op, args, "provider")?;
                let model = req_str(op, args, "model")?;
                let thought = req_str(op, args, "thought")?;
                let payload = switch_model_config_payload(&provider, &model, &thought);
                self.enqueue_command(
                    op,
                    Some(session_id),
                    "switchModelConfig",
                    payload,
                    Some(CommandPost::SwitchModelConfig {
                        provider,
                        model,
                        thought,
                    }),
                    events,
                );
            }
            "switchCollaborationMode" => {
                let session_id = req_str(op, args, "sessionId")?;
                let mode = req_str(op, args, "mode")?;
                self.enqueue_command(
                    op,
                    Some(session_id),
                    "switchCollaborationMode",
                    switch_collaboration_mode_payload(&mode),
                    None,
                    events,
                );
            }
            "setFollowupMode" => {
                let session_id = req_str(op, args, "sessionId")?;
                let mode = req_str(op, args, "mode")?;
                self.enqueue_command(
                    op,
                    Some(session_id),
                    "setFollowupMode",
                    set_followup_mode_payload(&mode),
                    None,
                    events,
                );
            }
            "setAssistantFeedback" => {
                let session_id = req_str(op, args, "sessionId")?;
                let target = req_obj(op, args, "target")?;
                let feedback = opt_str(op, args, "feedback")?;
                let payload = set_assistant_feedback_payload(&target, feedback.as_deref());
                self.enqueue_command(op, Some(session_id), "setAssistantFeedback", payload, None, events);
            }
            "retryTurn" | "forkAssistant" | "applyFileRewind" => {
                let session_id = req_str(op, args, "sessionId")?;
                let target = req_obj(op, args, "target")?;
                let payload = match op {
                    "retryTurn" => retry_turn_payload(&target),
                    "forkAssistant" => fork_assistant_payload(&target),
                    _ => apply_file_rewind_payload(&target),
                };
                self.enqueue_command(op, Some(session_id), op, payload, None, events);
            }
            "editUserQuery" => {
                let session_id = req_str(op, args, "sessionId")?;
                let target = req_obj(op, args, "target")?;
                let new_text = req_str(op, args, "newText")?;
                self.enqueue_command(
                    op,
                    Some(session_id),
                    "editUserQuery",
                    edit_user_query_payload(&target, &new_text),
                    None,
                    events,
                );
            }
            "sendQueuedNow" | "deleteQueueItem" => {
                let session_id = req_str(op, args, "sessionId")?;
                let queue_item_id = req_str(op, args, "queueItemId")?;
                let payload = if op == "sendQueuedNow" {
                    send_queued_now_payload(&queue_item_id)
                } else {
                    delete_queue_item_payload(&queue_item_id)
                };
                self.enqueue_command(op, Some(session_id), op, payload, None, events);
            }
            "editQueueItem" => {
                let session_id = req_str(op, args, "sessionId")?;
                let queue_item_id = req_str(op, args, "queueItemId")?;
                let new_text = req_str(op, args, "newText")?;
                self.enqueue_command(
                    op,
                    Some(session_id),
                    "editQueueItem",
                    edit_queue_item_payload(&queue_item_id, &new_text),
                    None,
                    events,
                );
            }
            "setAutoDrain" => {
                let session_id = req_str(op, args, "sessionId")?;
                let auto_drain = req_bool(op, args, "autoDrain")?;
                self.enqueue_command(
                    op,
                    Some(session_id),
                    "setAutoDrain",
                    set_auto_drain_payload(auto_drain),
                    None,
                    events,
                );
            }
            "createSession" => {
                let workspace_id = req_str(op, args, "workspaceId")?;
                let first_text = opt_str(op, args, "firstText")?;
                let attachments = opt_obj_list(op, args, "attachments")?;
                let config = opt_obj(op, args, "config")?;
                let runtime_model = opt_str(op, args, "runtimeModel")?;
                let mcp_servers = opt_str_list(op, args, "mcpServers")?;
                let payload = create_session_payload(
                    &workspace_id,
                    first_text.as_deref(),
                    attachments.as_deref(),
                    config.as_ref(),
                    runtime_model.as_deref(),
                    mcp_servers.as_deref(),
                );
                let timeout_ms = match opt_i64(op, args, "timeoutSecs")? {
                    Some(secs) => secs * 1000,
                    None => CREATE_SESSION_TIMEOUT_SECS as i64 * 1000,
                };
                self.enqueue_flow(
                    op,
                    FlowKind::PendingCommand(Box::new(CommandParams {
                        session_id: None,
                        command_type: "createSession".to_string(),
                        payload,
                        timeout_ms,
                        post: CommandPost::CreateSession,
                    })),
                    FlowStage::AwaitHandshake,
                    events,
                );
            }
            "createSelectionSideSession" => {
                let parent_session_id = req_str(op, args, "parentSessionId")?;
                let timeout_ms = match opt_i64(op, args, "timeoutSecs")? {
                    Some(secs) => secs * 1000,
                    None => CREATE_SIDE_SESSION_TIMEOUT_SECS as i64 * 1000,
                };
                self.enqueue_flow(
                    op,
                    FlowKind::PendingCommand(Box::new(CommandParams {
                        session_id: Some(parent_session_id),
                        command_type: "createSelectionSideSession".to_string(),
                        payload: empty_payload(),
                        timeout_ms,
                        post: CommandPost::CreateSideSession,
                    })),
                    FlowStage::AwaitHandshake,
                    events,
                );
            }
            "resolveInteraction" => {
                let session_id = req_str(op, args, "sessionId")?;
                let interaction_id = req_str(op, args, "interactionId")?;
                let option_id = opt_str(op, args, "optionId")?;
                let free_text = opt_str(op, args, "freeText")?;
                let action = opt_str(op, args, "action")?;
                let content = opt_obj(op, args, "content")?;
                let payload = resolve_interaction_payload(
                    &interaction_id,
                    option_id.as_deref(),
                    free_text.as_deref(),
                    action.as_deref(),
                    content.as_ref(),
                );
                self.enqueue_command(op, Some(session_id), "resolveInteraction", payload, None, events);
            }
            // ---- 查询 ----
            "plans" => {
                let session_id = req_str(op, args, "sessionId")?;
                self.enqueue_simple(op, SimpleCall::Plans { session_id }, events);
            }
            "fileChanges" | "fileRewindPreview" => {
                let session_id = req_str(op, args, "sessionId")?;
                let target = req_obj(op, args, "target")?;
                let base_revision = opt_i64(op, args, "baseRevision")?;
                let base_log_epoch = opt_str(op, args, "baseLogEpoch")?;
                let call = if op == "fileChanges" {
                    SimpleCall::FileChanges {
                        session_id,
                        target,
                        base_revision,
                        base_log_epoch,
                    }
                } else {
                    SimpleCall::FileRewindPreview {
                        session_id,
                        target,
                        base_revision,
                        base_log_epoch,
                    }
                };
                self.enqueue_simple(op, call, events);
            }
            "rowsRange" => {
                let session_id = req_str(op, args, "sessionId")?;
                let before_row_id = opt_i64(op, args, "beforeRowId")?;
                let limit = opt_i64(op, args, "limit")?.unwrap_or(ROWS_RANGE_DEFAULT_LIMIT);
                self.enqueue_simple(
                    op,
                    SimpleCall::RowsRange {
                        session_id,
                        before_row_id,
                        limit,
                    },
                    events,
                );
            }
            // ---- 附件 ----
            "attachmentPut" => {
                let session_id = req_str(op, args, "sessionId")?;
                let file_name = req_str(op, args, "fileName")?;
                let mime = req_str(op, args, "mime")?;
                let data_base64 = req_str(op, args, "dataBase64")?;
                let bytes = BASE64.decode(data_base64.as_bytes()).map_err(|e| {
                    ProtocolError::Other(format!("attachmentPut: dataBase64 is not base64: {e}"))
                })?;
                self.enqueue_flow(
                    op,
                    FlowKind::Upload(Box::new(UploadFlow {
                        session_id,
                        file_name,
                        mime,
                        bytes,
                        upload_id: String::new(),
                        base: AttachmentBase {
                            connection_id: String::new(),
                            upload_id: String::new(),
                            session_id: String::new(),
                        },
                        total_chunks: 0,
                        checksum: String::new(),
                        next_chunk: 0,
                        phase: UploadPhase::Begin,
                    })),
                    FlowStage::AwaitHandshake,
                    events,
                );
            }
            "attachmentRead" => {
                let session_id = req_str(op, args, "sessionId")?;
                let reference = req_str(op, args, "ref")?;
                self.enqueue_flow(
                    op,
                    FlowKind::Read(Box::new(ReadFlow {
                        session_id,
                        reference,
                        reader: AttachmentReader::new(),
                    })),
                    FlowStage::AwaitHandshake,
                    events,
                );
            }
            // ---- workspace / skills ----
            "prepareWorkspace" => {
                let refresh = opt_bool(op, args, "refresh")?.unwrap_or(false);
                self.enqueue_flow(op, FlowKind::PrepareWorkspace { refresh }, FlowStage::Ready, events);
            }
            "skills" => {
                self.enqueue_flow(
                    op,
                    FlowKind::Simple(Box::new(SimpleCall::Skills)),
                    FlowStage::Ready,
                    events,
                );
            }
            "modelSelectionView" => {
                let selection = opt_obj(op, args, "selection")?;
                self.enqueue_flow(
                    op,
                    FlowKind::Simple(Box::new(SimpleCall::ModelSelectionView { selection })),
                    FlowStage::Ready,
                    events,
                );
            }
            // ---- ConversationState 写入面 ----
            "setHistoryExhausted" => {
                let session_id = req_str(op, args, "sessionId")?;
                let value = req_bool(op, args, "value")?;
                let token = self.new_op_token(op);
                events.push(ConversationEvent::OpAccepted {
                    token: token.clone(),
                    op: op.to_string(),
                });
                if let Some(state) = self.conversation_state_mut(&session_id) {
                    state.history_exhausted = value;
                    push_rows_updated(events, &session_id, None, state);
                }
                events.push(ConversationEvent::Completed {
                    token,
                    outcome: Completion::Ok,
                });
            }
            "prependOlderRows" => {
                let session_id = req_str(op, args, "sessionId")?;
                let rows = opt_obj_list(op, args, "rows")?.unwrap_or_default();
                let new_first_row_id = opt_i64(op, args, "newFirstRowId")?;
                let token = self.new_op_token(op);
                events.push(ConversationEvent::OpAccepted {
                    token: token.clone(),
                    op: op.to_string(),
                });
                let mut failed = None;
                if let Some(state) = self.conversation_state_mut(&session_id) {
                    match state.prepend_older_rows(&rows, new_first_row_id) {
                        Ok(()) => push_rows_updated(events, &session_id, None, state),
                        Err(e) => failed = Some(e),
                    }
                }
                events.push(ConversationEvent::Completed {
                    token,
                    outcome: match failed {
                        None => Completion::Ok,
                        Some(e) => Completion::Failed(protocol_error_to_transport(e)),
                    },
                });
            }
            "optimisticPatch" => {
                let session_id = req_str(op, args, "sessionId")?;
                let patch = req_obj(op, args, "patch")?;
                let token = self.new_op_token(op);
                events.push(ConversationEvent::OpAccepted {
                    token: token.clone(),
                    op: op.to_string(),
                });
                if let Some(state) = self.conversation_state_mut(&session_id) {
                    let had_snapshot = state.snapshot.is_some();
                    state.optimistic_patch(&patch);
                    // Dart: `if (snapshot == null) return;` → 不通知。
                    if had_snapshot {
                        push_rows_updated(events, &session_id, None, state);
                    }
                }
                events.push(ConversationEvent::Completed {
                    token,
                    outcome: Completion::Ok,
                });
            }
            "optimisticRowUpdate" => {
                let session_id = req_str(op, args, "sessionId")?;
                let row_id = opt_i64(op, args, "rowId")?;
                let patch = req_obj(op, args, "patch")?;
                let token = self.new_op_token(op);
                events.push(ConversationEvent::OpAccepted {
                    token: token.clone(),
                    op: op.to_string(),
                });
                let mut failed = None;
                if let Some(state) = self.conversation_state_mut(&session_id) {
                    match state.optimistic_row_update(row_id, &patch) {
                        Ok(()) => push_rows_updated(events, &session_id, None, state),
                        Err(e) => failed = Some(e),
                    }
                }
                events.push(ConversationEvent::Completed {
                    token,
                    outcome: match failed {
                        None => Completion::Ok,
                        Some(e) => Completion::Failed(protocol_error_to_transport(e)),
                    },
                });
            }
            "optimisticRemoveQueueItem" => {
                let session_id = req_str(op, args, "sessionId")?;
                let queue_item_id = req_str(op, args, "queueItemId")?;
                let token = self.new_op_token(op);
                events.push(ConversationEvent::OpAccepted {
                    token: token.clone(),
                    op: op.to_string(),
                });
                let mut failed = None;
                if let Some(state) = self.conversation_state_mut(&session_id) {
                    match state.optimistic_remove_queue_item(&queue_item_id) {
                        Ok(()) => push_rows_updated(events, &session_id, None, state),
                        Err(e) => failed = Some(e),
                    }
                }
                events.push(ConversationEvent::Completed {
                    token,
                    outcome: match failed {
                        None => Completion::Ok,
                        Some(e) => Completion::Failed(protocol_error_to_transport(e)),
                    },
                });
            }
            _ => unreachable!("OPS 校验已覆盖"),
        }
        Ok(())
    }

    // -------------------------------------------------------- 令牌 / 入队

    fn new_op_token(&mut self, op: &str) -> String {
        self.next_flow_seq += 1;
        format!("{op}:{}", self.next_flow_seq)
    }

    fn new_internal_token(&mut self, prefix: &str) -> String {
        self.next_flow_seq += 1;
        format!("#{prefix}:{}", self.next_flow_seq)
    }

    fn new_op_flow(&mut self, op: &str, kind: FlowKind, stage: FlowStage) -> Flow {
        Flow {
            token: self.new_op_token(op),
            op: Some(op.to_string()),
            stage,
            healthy_ok: false,
            kind,
        }
    }

    fn enqueue_flow(
        &mut self,
        op: &str,
        kind: FlowKind,
        stage: FlowStage,
        events: &mut Vec<ConversationEvent>,
    ) -> String {
        let flow = self.new_op_flow(op, kind, stage);
        let token = flow.token.clone();
        events.push(ConversationEvent::OpAccepted {
            token: token.clone(),
            op: op.to_string(),
        });
        self.flows.insert(token.clone(), flow);
        token
    }

    fn enqueue_command(
        &mut self,
        op: &str,
        session_id: Option<String>,
        command_type: &str,
        payload: Value,
        post: Option<CommandPost>,
        events: &mut Vec<ConversationEvent>,
    ) {
        self.enqueue_flow(
            op,
            FlowKind::PendingCommand(Box::new(CommandParams {
                session_id,
                command_type: command_type.to_string(),
                payload,
                timeout_ms: COMMAND_DEFAULT_TIMEOUT_SECS as i64 * 1000,
                post: post.unwrap_or(CommandPost::None),
            })),
            FlowStage::AwaitHandshake,
            events,
        );
    }

    fn enqueue_simple(&mut self, op: &str, call: SimpleCall, events: &mut Vec<ConversationEvent>) {
        self.enqueue_flow(op, FlowKind::Simple(Box::new(call)), FlowStage::AwaitHandshake, events);
    }

    /// 登记一条"发出去就不管"的内部调用（下一轮 `pump` 里发）。
    fn enqueue_ignore_call(&mut self, method: &'static str, args: Value) {
        let token = self.new_internal_token("call");
        self.flows.insert(
            token.clone(),
            Flow {
                token,
                op: None,
                stage: FlowStage::Ready,
                healthy_ok: true,
                kind: FlowKind::Ignore {
                    channel: TRANSPORT_CHANNEL,
                    method,
                    args,
                    timeout_ms: COMMAND_DEFAULT_TIMEOUT_SECS as i64 * 1000,
                },
            },
        );
    }

    fn alloc_timer(&mut self) -> TimerId {
        self.next_timer += 1;
        TimerId(self.next_timer)
    }

    fn alloc_healthy_token(&mut self) -> u64 {
        self.next_healthy_token += 1;
        self.next_healthy_token
    }

    fn take_flow(&mut self, token: &str) -> Option<Flow> {
        let flow = self.flows.remove(token)?;
        self.by_call.retain(|_, t| t != token);
        Some(flow)
    }

    /// 发一条 JSON 参数（`args` 列表里放一个值）。
    fn call_json(
        &mut self,
        token: &str,
        channel: &str,
        method: &str,
        arg: Value,
        timeout_ms: i64,
        channels: &mut dyn ChannelRpc,
    ) -> u64 {
        self.call_value(token, channel, method, vec![arg.into()], timeout_ms, channels)
    }

    /// 发一条空参数调用（`handshake` 的 `helloConversationV4` 用 `[]`）。
    fn call_empty(
        &mut self,
        token: &str,
        channel: &str,
        method: &str,
        timeout_ms: i64,
        channels: &mut dyn ChannelRpc,
    ) -> u64 {
        self.call_value(token, channel, method, Vec::new(), timeout_ms, channels)
    }

    fn call_value(
        &mut self,
        token: &str,
        channel: &str,
        method: &str,
        args: Vec<IpcValue>,
        timeout_ms: i64,
        channels: &mut dyn ChannelRpc,
    ) -> u64 {
        let call = channels.call(channel, method, args, timeout_ms);
        self.by_call.insert(call, token.to_string());
        call
    }

    fn send_command_call(
        &mut self,
        token: &str,
        envelope: &CommandEnvelope,
        timeout_ms: i64,
        channels: &mut dyn ChannelRpc,
    ) -> u64 {
        let args = send_command_args(&self.scope, envelope);
        self.call_json(token, TRANSPORT_CHANNEL, SEND_COMMAND_METHOD, args, timeout_ms, channels)
    }

    // -------------------------------------------------------- 输入：通道事件

    fn on_channel_event(
        &mut self,
        event: ChannelEvent,
        channels: &mut dyn ChannelRpc,
        now_ms: i64,
        events: &mut Vec<ConversationEvent>,
    ) {
        match event {
            ChannelEvent::PromiseSuccess { id, data } => {
                let Some(token) = self.by_call.remove(&id) else {
                    return;
                };
                let res = to_dart_view(&data);
                self.on_call_ok(&token, res, channels, now_ms, events);
            }
            ChannelEvent::PromiseError { id, error } | ChannelEvent::PromiseErrorObj { id, error } => {
                let Some(token) = self.by_call.remove(&id) else {
                    return;
                };
                let err = TransportError::channel(error.message);
                self.on_call_err(&token, err, events);
            }
            ChannelEvent::EventFire { id, data } => {
                let Some(key) = self.listeners.get(&id).copied() else {
                    return;
                };
                let frame = to_dart_view(&data);
                self.accept_wire_frame(key, &frame, channels, now_ms, events);
            }
        }
    }

    fn on_call_ok(
        &mut self,
        token: &str,
        res: Value,
        channels: &mut dyn ChannelRpc,
        now_ms: i64,
        events: &mut Vec<ConversationEvent>,
    ) {
        let Some(mut flow) = self.take_flow(token) else {
            return;
        };
        let mut kind = std::mem::replace(&mut flow.kind, FlowKind::None);
        let mut keep = true;
        match &mut kind {
            FlowKind::None => keep = false,
            FlowKind::Handshake { phase } => match *phase {
                HandshakePhase::Hello => {
                    // 对应 `_log('[v4] hello: $hello')`。
                    events.push(ConversationEvent::Log(format!(
                        "[v4] hello: {}",
                        dart_json_to_string(&res)
                    )));
                    match parse_hello_connection_id(&res) {
                        Ok(connection_id) => {
                            self.connection_id = connection_id;
                            let hello =
                                ClientHello::new(self.client_id.clone(), self.app_version.clone());
                            let arg = serde_json::to_value(&hello).unwrap_or(Value::Null);
                            let call = self.call_json(
                                token,
                                TRANSPORT_CHANNEL,
                                INITIALIZE_METHOD,
                                arg,
                                COMMAND_DEFAULT_TIMEOUT_SECS as i64 * 1000,
                                channels,
                            );
                            *phase = HandshakePhase::Initialize;
                            flow.stage = FlowStage::Sent { call };
                        }
                        Err(e) => {
                            keep = false;
                            self.handshake_failed(protocol_error_to_transport(e), events);
                        }
                    }
                }
                HandshakePhase::Initialize => {
                    self.handshaken = true;
                    events.push(ConversationEvent::HandshakeDone {
                        connection_id: self.connection_id.clone(),
                    });
                    events.push(ConversationEvent::StateChanged);
                    keep = false;
                }
            },
            FlowKind::HandshakeWaiter => {
                keep = false;
                emit_completion(&flow, Completion::Ok, events);
            }
            FlowKind::PendingCommand(_) => keep = false,
            FlowKind::Command(cmd) => match self.on_command_response(&flow, cmd, res, channels, now_ms, events) {
                CommandOutcome::Done => keep = false,
                CommandOutcome::Resent { call } => {
                    flow.stage = FlowStage::Sent { call };
                }
                CommandOutcome::Restart(params) => {
                    kind = FlowKind::PendingCommand(params);
                    flow.stage = FlowStage::Ready;
                    flow.healthy_ok = false;
                }
            },
            FlowKind::Subscribe(sf) => {
                if sf.phase == SubscribePhase::AwaitUnsubscribe {
                    // Dart 的 `try { await unsubscribe } catch (_) {}`：结果一律忽略。
                    sf.phase = SubscribePhase::RegisterListener;
                    flow.stage = FlowStage::Ready;
                } else {
                    keep = self.on_subscribe_response(&flow, sf, res, channels, now_ms, events);
                }
            }
            FlowKind::Resync { sub_key } => {
                let key = *sub_key;
                keep = false;
                if let Some(sub) = self.subscriptions.get_mut(&key) {
                    sub.core.set_resyncing(false);
                }
            }
            FlowKind::Simple(call) => {
                keep = false;
                let outcome = match &**call {
                    SimpleCall::Skills => match parse_skills_response(&res) {
                        Ok(entries) => Completion::Value(Value::Array(
                            entries.iter().map(skill_entry_to_json).collect(),
                        )),
                        Err(e) => Completion::Failed(protocol_error_to_transport(e)),
                    },
                    SimpleCall::RowsRange {
                        before_row_id,
                        limit,
                        ..
                    } => {
                        // 分页应答：宿主靠 beforeRowId / atLogEpoch 做 CAS，日志里必须能看到
                        // 「请求的游标 → 返回行数 → 是否还有更早」这一组。
                        let row_count = res
                            .get("rows")
                            .and_then(|v| v.as_array())
                            .map(|a| a.len())
                            .unwrap_or(0);
                        events.push(ConversationEvent::Log(format!(
                            "[conv] rowsRange reply beforeRowId={} limit={} rows={} hasMore={} atLogEpoch={} atSeq={}",
                            opt_value(*before_row_id),
                            limit,
                            row_count,
                            res.get("hasMore")
                                .and_then(|v| v.as_bool())
                                .map(|b| b.to_string())
                                .unwrap_or_else(|| "-".to_string()),
                            res.get("atLogEpoch").and_then(|v| v.as_str()).unwrap_or("-"),
                            res.get("atSeq")
                                .map(|v| v.to_string())
                                .unwrap_or_else(|| "-".to_string()),
                        )));
                        Completion::Value(res)
                    }
                    _ => Completion::Value(res),
                };
                emit_completion(&flow, outcome, events);
            }
            FlowKind::PrepareWorkspace { .. } => {
                keep = false;
                match WorkspacePrep::from_response(&res) {
                    Ok(prep) => {
                        let json = workspace_prep_to_json(&prep);
                        self.prep = Some(prep);
                        events.push(ConversationEvent::StateChanged);
                        emit_completion(&flow, Completion::Value(json), events);
                    }
                    Err(e) => emit_completion(
                        &flow,
                        Completion::Failed(protocol_error_to_transport(e)),
                        events,
                    ),
                }
            }
            FlowKind::Upload(up) => {
                if self.on_upload_response(&flow, up, res, events) {
                    // 还要继续传下一片 / commit：把流程交回 pump 推进。
                    flow.stage = FlowStage::Ready;
                } else {
                    keep = false;
                }
            }
            FlowKind::Read(rd) => {
                match rd.reader.feed(&res) {
                    Err(e) => {
                        keep = false;
                        emit_completion(
                            &flow,
                            Completion::Failed(protocol_error_to_transport(e)),
                            events,
                        );
                    }
                    Ok(()) => {
                        if rd.reader.should_continue() {
                            flow.stage = FlowStage::Ready;
                        } else {
                            keep = false;
                            let outcome = Completion::AttachmentBytes {
                                base64: BASE64.encode(&rd.reader.bytes),
                                media_type: rd.reader.media_type.clone(),
                            };
                            emit_completion(&flow, outcome, events);
                        }
                    }
                }
            }
            FlowKind::Ignore { .. } => keep = false,
        }
        if keep {
            flow.kind = kind;
            self.flows.insert(flow.token.clone(), flow);
        }
    }

    fn on_call_err(&mut self, token: &str, err: TransportError, events: &mut Vec<ConversationEvent>) {
        let Some(mut flow) = self.take_flow(token) else {
            return;
        };
        let mut kind = std::mem::replace(&mut flow.kind, FlowKind::None);
        match &mut kind {
            FlowKind::Handshake { .. } => {
                self.handshake_failed(err, events);
            }
            FlowKind::Subscribe(sf) => {
                let key = sf.sub_key;
                let resubscribe = sf.resubscribe;
                self.subscribe_failed(&flow, key, resubscribe, err, events);
            }
            FlowKind::Resync { sub_key } => {
                let key = *sub_key;
                let tag = self.sub_log_tag(key);
                events.push(ConversationEvent::Log(format!(
                    "[{tag}] resync failed: {}",
                    err.dart_to_string()
                )));
                if let Some(sub) = self.subscriptions.get_mut(&key) {
                    sub.core.set_resyncing(false);
                }
            }
            FlowKind::Ignore { .. } => {}
            _ => emit_completion(&flow, Completion::Failed(err), events),
        }
    }

    fn on_channel_timeout(
        &mut self,
        id: u64,
        channels: &mut dyn ChannelRpc,
        now_ms: i64,
        events: &mut Vec<ConversationEvent>,
    ) {
        let Some(token) = self.by_call.remove(&id) else {
            return;
        };
        let Some(mut flow) = self.take_flow(&token) else {
            return;
        };
        let mut kind = std::mem::replace(&mut flow.kind, FlowKind::None);
        match &mut kind {
            FlowKind::Handshake { .. } => {
                self.handshake_failed(
                    TransportError::timeout(format!("{TRANSPORT_CHANNEL}.{HELLO_METHOD} timed out")),
                    events,
                );
            }
            FlowKind::Command(cmd) => {
                // 对应 `_sendCommandWithRetry` 的 `on TimeoutException`。
                if cmd.retried || self.bridge_degraded.is_none() {
                    // `if (session.degraded.value == null) rethrow;` 以及「超时重发之后又超时」。
                    emit_completion(
                        &flow,
                        Completion::Failed(TransportError::timeout(format!(
                            "{TRANSPORT_CHANNEL}.{SEND_COMMAND_METHOD} timed out"
                        ))),
                        events,
                    );
                } else {
                    events.push(ConversationEvent::Log(
                        "[v4] command timed out during drop, waiting for recovery and retrying"
                            .to_string(),
                    ));
                    cmd.retried = true;
                    let uuid = self.uuid.next_uuid();
                    let fresh = fresh_envelope(&cmd.envelope, &uuid, now_ms);
                    cmd.next_envelope = Some(fresh);
                    let healthy_token = self.alloc_healthy_token();
                    flow.stage = FlowStage::AwaitHealthy { healthy_token };
                    flow.kind = kind;
                    self.flows.insert(flow.token.clone(), flow);
                    events.push(ConversationEvent::WaitHealthy {
                        token: healthy_token,
                        timeout_ms: WAIT_HEALTHY_TIMEOUT_MS,
                    });
                }
            }
            FlowKind::Subscribe(sf) => {
                let key = sf.sub_key;
                let resubscribe = sf.resubscribe;
                let method = sf.kind.subscribe_method();
                self.subscribe_failed(
                    &flow,
                    key,
                    resubscribe,
                    TransportError::timeout(format!("{TRANSPORT_CHANNEL}.{method} timed out")),
                    events,
                );
            }
            FlowKind::Resync { sub_key } => {
                let key = *sub_key;
                let tag = self.sub_log_tag(key);
                let method = self.sub_resync_method(key);
                events.push(ConversationEvent::Log(format!(
                    "[{tag}] resync failed: TimeoutException: {TRANSPORT_CHANNEL}.{method} timed out"
                )));
                if let Some(sub) = self.subscriptions.get_mut(&key) {
                    sub.core.set_resyncing(false);
                }
            }
            FlowKind::Ignore { .. } => {}
            _ => emit_completion(
                &flow,
                Completion::Failed(TransportError::timeout(format!(
                    "{TRANSPORT_CHANNEL} request timed out"
                ))),
                events,
            ),
        }
        let _ = (channels, now_ms);
    }

    fn on_listener_registered(
        &mut self,
        listener_token: u64,
        id: u64,
        channels: &mut dyn ChannelRpc,
        _now_ms: i64,
        _events: &mut Vec<ConversationEvent>,
    ) {
        let found = self
            .flows
            .iter()
            .find(|(_, f)| f.stage == FlowStage::AwaitListener { listener_token })
            .map(|(t, _)| t.clone());
        let Some(token) = found else {
            return;
        };
        let Some(mut flow) = self.take_flow(&token) else {
            return;
        };
        let mut kind = std::mem::replace(&mut flow.kind, FlowKind::None);
        if let FlowKind::Subscribe(sf) = &mut kind {
            if let Some(key) = sf.sub_key {
                if let Some(sub) = self.subscriptions.get_mut(&key) {
                    sub.listener = Some(id);
                }
                self.listeners.insert(id, key);
                let subscribe_method = sf.kind.subscribe_method();
                let extra = sf.kind.subscribe_args();
                let args = subscribe_request_args(&self.scope, &extra);
                let call = self.call_json(
                    &flow.token,
                    TRANSPORT_CHANNEL,
                    subscribe_method,
                    args,
                    SUBSCRIBE_TIMEOUT_SECS as i64 * 1000,
                    channels,
                );
                sf.phase = SubscribePhase::AwaitAck;
                flow.stage = FlowStage::Sent { call };
            }
        }
        flow.kind = kind;
        self.flows.insert(flow.token.clone(), flow);
    }

    fn on_bridge_healthy(&mut self, healthy_token: u64, _events: &mut Vec<ConversationEvent>) {
        let found = self
            .flows
            .iter()
            .find(|(_, f)| f.stage == FlowStage::AwaitHealthy { healthy_token })
            .map(|(t, _)| t.clone());
        if let Some(token) = found {
            if let Some(flow) = self.flows.get_mut(&token) {
                flow.healthy_ok = true;
                flow.stage = FlowStage::Ready;
            }
        }
    }

    fn on_bridge_healthy_timeout(&mut self, healthy_token: u64, events: &mut Vec<ConversationEvent>) {
        let found = self
            .flows
            .iter()
            .find(|(_, f)| f.stage == FlowStage::AwaitHealthy { healthy_token })
            .map(|(t, _)| t.clone());
        let Some(token) = found else {
            return;
        };
        let Some(flow) = self.take_flow(&token) else {
            return;
        };
        // 对应 `waitHealthy` 超时的 `TimeoutException('bridge 恢复超时: ${degraded.value}')`。
        let message = format!(
            "bridge 恢复超时: {}",
            interp_opt_str(self.bridge_degraded.as_deref())
        );
        emit_completion(&flow, Completion::Failed(TransportError::timeout(message)), events);
    }

    // -------------------------------------------------------- 输入：定时器 / 桥

    fn on_timer(
        &mut self,
        token: TimerId,
        channels: &mut dyn ChannelRpc,
        now_ms: i64,
        events: &mut Vec<ConversationEvent>,
    ) {
        let keys: Vec<u64> = self.sub_order.clone();
        for key in keys {
            let (is_watchdog, is_fragment, is_retry) = match self.subscriptions.get(&key) {
                Some(sub) => (
                    sub.watchdog == Some(token),
                    sub.fragment_cleanup == Some(token),
                    sub.resubscribe_timer == Some(token),
                ),
                None => continue,
            };
            if is_fragment {
                self.fragment_tick(key, token, now_ms, events);
            } else if is_watchdog {
                self.watchdog_tick(key, token, channels, now_ms, events);
            } else if is_retry {
                self.resubscribe_retry_tick(key, channels, now_ms, events);
            }
        }
    }

    /// 对应 `_purgeFragments()`（`Timer.periodic(30s)`）。
    fn fragment_tick(
        &mut self,
        key: u64,
        token: TimerId,
        now_ms: i64,
        events: &mut Vec<ConversationEvent>,
    ) {
        let tag = self.sub_log_tag(key);
        let purged = match self.subscriptions.get_mut(&key) {
            Some(sub) => sub.core.purge_fragments(now_ms),
            None => return,
        };
        for id in purged {
            events.push(ConversationEvent::Log(format!(
                "[{tag}] purged stale fragment {id}"
            )));
        }
        events.push(ConversationEvent::TimerStart {
            token,
            delay_ms: FRAGMENT_CLEANUP_INTERVAL_SECS as i64 * 1000,
        });
    }

    /// 对应 `ConversationSubscription._startWatchdog` 的 `Timer.periodic(10s)` 回调。
    fn watchdog_tick(
        &mut self,
        key: u64,
        token: TimerId,
        channels: &mut dyn ChannelRpc,
        now_ms: i64,
        events: &mut Vec<ConversationEvent>,
    ) {
        let (disposed, quiet, is_running, streaming) = match self.subscriptions.get(&key) {
            Some(sub) => (
                sub.core.disposed,
                (now_ms - sub.last_frame_at_ms) / 1000,
                match &sub.state {
                    SubState::Conversation(s) => s.is_running(),
                    SubState::SessionsIndex(_) => false,
                },
                match &sub.state {
                    SubState::Conversation(s) => s.has_streaming_row(),
                    SubState::SessionsIndex(_) => false,
                },
            ),
            None => return,
        };
        if disposed {
            return;
        }
        if watchdog_should_resync(quiet, is_running, streaming) {
            events.push(ConversationEvent::Log(format!(
                "[v4] watchdog: no frames for {quiet}s while active, resync"
            )));
            self.request_resync(key, channels, now_ms, events, "watchdog");
        }
        events.push(ConversationEvent::TimerStart {
            token,
            delay_ms: WATCHDOG_INTERVAL_SECS as i64 * 1000,
        });
    }

    /// 对应 `Timer(3s, () { if (!_disposed && _subscriptionId == null) _resubscribe(); })`。
    fn resubscribe_retry_tick(
        &mut self,
        key: u64,
        channels: &mut dyn ChannelRpc,
        now_ms: i64,
        events: &mut Vec<ConversationEvent>,
    ) {
        let should_retry = match self.subscriptions.get_mut(&key) {
            Some(sub) => {
                sub.resubscribe_timer = None;
                sub.core.should_retry_resubscribe()
            }
            None => false,
        };
        if should_retry {
            self.start_resubscribe(key, channels, now_ms, events);
        }
    }

    /// 对应 transport 的 `_onBridgeRecovered` + 各订阅的 `_onBridgeRecovered`。
    fn on_bridge_recovered(
        &mut self,
        channels: &mut dyn ChannelRpc,
        now_ms: i64,
        events: &mut Vec<ConversationEvent>,
    ) {
        // Dart: `_handshaken = false; _handshakeFuture = null; connectionId = null; _prep = null;`
        self.handshaken = false;
        self.flows.remove(HANDSHAKE_TOKEN);
        self.connection_id = None;
        self.prep = None;
        // 各订阅按创建顺序重订阅（ChangeNotifier 按注册顺序通知）。
        let keys: Vec<u64> = self.sub_order.clone();
        for key in keys {
            self.start_resubscribe(key, channels, now_ms, events);
        }
    }

    // -------------------------------------------------------- 推进

    fn pump(
        &mut self,
        channels: &mut dyn ChannelRpc,
        now_ms: i64,
        events: &mut Vec<ConversationEvent>,
    ) {
        loop {
            let mut progressed = false;
            if !self.handshaken
                && !self.flows.contains_key(HANDSHAKE_TOKEN)
                && self
                    .flows
                    .values()
                    .any(|f| matches!(f.stage, FlowStage::AwaitHandshake))
            {
                self.flows.insert(
                    HANDSHAKE_TOKEN.to_string(),
                    Flow {
                        token: HANDSHAKE_TOKEN.to_string(),
                        op: None,
                        stage: FlowStage::Ready,
                        healthy_ok: true,
                        kind: FlowKind::Handshake {
                            phase: HandshakePhase::Hello,
                        },
                    },
                );
                progressed = true;
            }
            let handshaken = self.handshaken;
            let ready: Vec<String> = self
                .flows
                .iter()
                .filter(|(_, f)| f.can_advance(handshaken))
                .map(|(t, _)| t.clone())
                .collect();
            for token in ready {
                self.advance_flow(&token, channels, now_ms, events);
                progressed = true;
            }
            if !progressed {
                break;
            }
        }
    }

    fn advance_flow(
        &mut self,
        token: &str,
        channels: &mut dyn ChannelRpc,
        now_ms: i64,
        events: &mut Vec<ConversationEvent>,
    ) {
        let Some(mut flow) = self.take_flow(token) else {
            return;
        };
        let mut kind = std::mem::replace(&mut flow.kind, FlowKind::None);
        let mut keep = true;
        match &mut kind {
            FlowKind::None => keep = false,
            FlowKind::Handshake { phase } => match *phase {
                HandshakePhase::Hello => {
                    let call = self.call_empty(
                        token,
                        TRANSPORT_CHANNEL,
                        HELLO_METHOD,
                        COMMAND_DEFAULT_TIMEOUT_SECS as i64 * 1000,
                        channels,
                    );
                    flow.stage = FlowStage::Sent { call };
                }
                HandshakePhase::Initialize => keep = false,
            },
            FlowKind::HandshakeWaiter => {
                keep = false;
                emit_completion(&flow, Completion::Ok, events);
            }
            FlowKind::PendingCommand(params) => {
                if self.bridge_degraded.is_some() && !flow.healthy_ok {
                    let healthy_token = self.alloc_healthy_token();
                    flow.stage = FlowStage::AwaitHealthy { healthy_token };
                    events.push(ConversationEvent::WaitHealthy {
                        token: healthy_token,
                        timeout_ms: WAIT_HEALTHY_TIMEOUT_MS,
                    });
                } else {
                    let cmd = self.build_command(params, now_ms, events);
                    let call = self.send_command_call(token, &cmd.envelope, cmd.timeout_ms, channels);
                    // 注意：写回局部 `kind`（末尾统一 `flow.kind = kind`），不要直接改 `flow.kind`，
                    // 否则会被末尾的写回覆盖回 PendingCommand。
                    kind = FlowKind::Command(Box::new(cmd));
                    flow.stage = FlowStage::Sent { call };
                }
            }
            FlowKind::Command(cmd) => {
                // 走到这里只有一种情形：`WaitHealthy` 返回后重发（`next_envelope` 已备好）。
                if let Some(next) = cmd.next_envelope.take() {
                    cmd.envelope = next;
                }
                let call = self.send_command_call(token, &cmd.envelope, cmd.timeout_ms, channels);
                flow.stage = FlowStage::Sent { call };
            }
            FlowKind::Subscribe(sf) => {
                if sf.sub_key.is_none() {
                    // Dart `subscribe()`：`await handshake()` 之后才 new 订阅对象。
                    let key = self.create_subscription(sf.kind.clone(), now_ms, events);
                    sf.sub_key = Some(key);
                }
                let key = sf.sub_key.expect("刚建好");
                loop {
                    match sf.phase {
                        SubscribePhase::CancelOld => {
                            // `_onResubscribeCleanup()`（conversation：watchdog.cancel）
                            self.cancel_watchdog(key, events);
                            // `_cancelFrameListener?.call()`
                            if let Some(listener) =
                                self.subscriptions.get_mut(&key).and_then(|s| s.listener.take())
                            {
                                self.listeners.remove(&listener);
                                events.push(ConversationEvent::UnlistenChannel { id: listener });
                            }
                            // `_subscriptionId = null; _stagedFrames.clear(); _fragments.clear();`
                            let old = self
                                .subscriptions
                                .get_mut(&key)
                                .and_then(|s| s.core.on_resubscribe_cleanup());
                            match old {
                                Some(old_id) => {
                                    let method = self.sub_unsubscribe_method(key);
                                    let extra = self.sub_unsubscribe_args(key);
                                    let args = unsubscribe_request_args(&self.scope, &old_id, &extra);
                                    let call = self.call_json(
                                        token,
                                        TRANSPORT_CHANNEL,
                                        method,
                                        args,
                                        COMMAND_DEFAULT_TIMEOUT_SECS as i64 * 1000,
                                        channels,
                                    );
                                    sf.phase = SubscribePhase::AwaitUnsubscribe;
                                    flow.stage = FlowStage::Sent { call };
                                    break;
                                }
                                None => sf.phase = SubscribePhase::RegisterListener,
                            }
                        }
                        SubscribePhase::AwaitUnsubscribe | SubscribePhase::AwaitAck => break,
                        SubscribePhase::RegisterListener => {
                            let event_name = sf.kind.frame_event();
                            let listener_token = {
                                self.next_listener_token += 1;
                                self.next_listener_token
                            };
                            events.push(ConversationEvent::ListenChannel {
                                token: listener_token,
                                channel: TRANSPORT_CHANNEL,
                                event: event_name,
                                arg: Value::Object(self.scope.clone()),
                            });
                            flow.stage = FlowStage::AwaitListener { listener_token };
                            break;
                        }
                    }
                }
            }
            FlowKind::Resync { .. } => keep = false,
            FlowKind::Simple(call) => {
                let (channel, method, arg, timeout_ms) = match &**call {
                    SimpleCall::Plans { session_id } => (
                        TRANSPORT_CHANNEL,
                        PLANS_METHOD,
                        conversation_plans_args(&self.scope, session_id),
                        COMMAND_DEFAULT_TIMEOUT_SECS as i64 * 1000,
                    ),
                    SimpleCall::FileChanges {
                        session_id,
                        target,
                        base_revision,
                        base_log_epoch,
                    } => (
                        TRANSPORT_CHANNEL,
                        FILE_CHANGES_METHOD,
                        file_changes_args(
                            &self.scope,
                            session_id,
                            target,
                            *base_revision,
                            base_log_epoch.as_deref(),
                        ),
                        COMMAND_DEFAULT_TIMEOUT_SECS as i64 * 1000,
                    ),
                    SimpleCall::FileRewindPreview {
                        session_id,
                        target,
                        base_revision,
                        base_log_epoch,
                    } => (
                        TRANSPORT_CHANNEL,
                        FILE_REWIND_PREVIEW_METHOD,
                        file_changes_args(
                            &self.scope,
                            session_id,
                            target,
                            *base_revision,
                            base_log_epoch.as_deref(),
                        ),
                        COMMAND_DEFAULT_TIMEOUT_SECS as i64 * 1000,
                    ),
                    SimpleCall::RowsRange {
                        session_id,
                        before_row_id,
                        limit,
                    } => (
                        TRANSPORT_CHANNEL,
                        ROWS_RANGE_METHOD,
                        rows_range_args(&self.scope, session_id, *before_row_id, *limit),
                        COMMAND_DEFAULT_TIMEOUT_SECS as i64 * 1000,
                    ),
                    SimpleCall::Skills => (
                        SKILLS_CHANNEL,
                        SKILLS_LIST_METHOD,
                        skills_args(&self.scope),
                        SKILLS_TIMEOUT_SECS as i64 * 1000,
                    ),
                    SimpleCall::ModelSelectionView { selection } => (
                        MODEL_SELECTION_CHANNEL,
                        MODEL_SELECTION_GET_VIEW_METHOD,
                        model_selection_args(selection),
                        MODEL_SELECTION_TIMEOUT_SECS as i64 * 1000,
                    ),
                };
                let call = self.call_json(token, channel, method, arg, timeout_ms, channels);
                flow.stage = FlowStage::Sent { call };
            }
            FlowKind::PrepareWorkspace { refresh } => {
                let refresh = *refresh;
                if !refresh {
                    if let Some(prep) = &self.prep {
                        let json = workspace_prep_to_json(prep);
                        keep = false;
                        emit_completion(&flow, Completion::Value(json), events);
                    }
                }
                if keep {
                    // 对端 3.15.3 **没有** `zcode-task.prepareWorkspace` 这个方法
                    // （整个 `out/` 里只有内部函数名 `prepareWorkspaceRuntime`）。
                    // 它只有 `zcode-agent.prepareStorage`，语义是「启动 agent 客户端并等
                    // storage 就绪」，不返回 configOptions / slashCommands，不能顶替。
                    // 参考 Dart 一直在调一个不存在的方法，两个调用点都用 `catch (_) {}`
                    // 吞掉，所以这个功能一直是静默失效的。
                    //
                    // 这里不再发那次注定失败的往返，直接回空 prep。真正的 model/mode
                    // 选项与 slash 命令要等 `queryConversationCommandsV4` 接线。
                    let empty = WorkspacePrep::from_json(&Default::default())
                        .expect("空 map 解析 WorkspacePrep 不会失败");
                    let json = workspace_prep_to_json(&empty);
                    self.prep = Some(empty);
                    keep = false;
                    events.push(ConversationEvent::Log(
                        "[conversation] 对端没有 prepareWorkspace；返回空 prep".to_string(),
                    ));
                    emit_completion(&flow, Completion::Value(json), events);
                }
            }
            FlowKind::Upload(up) => match up.phase {
                UploadPhase::Between => {
                    if up.next_chunk < up.total_chunks {
                        let n = up.next_chunk;
                        let (start, end) = attachment_chunk_range(up.bytes.len(), n);
                        let data = BASE64.encode(&up.bytes[start..end]);
                        let arg = attachment_chunk_args(&self.scope, &up.base, n, &data);
                        let call = self.call_json(
                            token,
                            TRANSPORT_CHANNEL,
                            ATTACHMENT_CHUNK_METHOD,
                            arg,
                            COMMAND_DEFAULT_TIMEOUT_SECS as i64 * 1000,
                            channels,
                        );
                        up.phase = UploadPhase::AwaitChunk;
                        flow.stage = FlowStage::Sent { call };
                    } else {
                        // 循环结束：Dart 先 `onProgress?.call(1)`，再 commit。
                        events.push(ConversationEvent::AttachmentProgress {
                            token: flow.token.clone(),
                            session_id: up.session_id.clone(),
                            upload_id: up.upload_id.clone(),
                            progress: 1.0,
                        });
                        let arg = attachment_commit_args(&self.scope, &up.base);
                        let call = self.call_json(
                            token,
                            TRANSPORT_CHANNEL,
                            ATTACHMENT_COMMIT_METHOD,
                            arg,
                            COMMAND_DEFAULT_TIMEOUT_SECS as i64 * 1000,
                            channels,
                        );
                        up.phase = UploadPhase::AwaitCommit;
                        flow.stage = FlowStage::Sent { call };
                    }
                }
                UploadPhase::Begin => {
                    // Dart: `final connId = connectionId; if (connId == null) throw StateError(...)`
                    let Some(connection_id) = self.connection_id.clone() else {
                        emit_completion(
                            &flow,
                            Completion::Failed(TransportError::invalid_state(
                                "attachmentPut: missing connectionId",
                            )),
                            events,
                        );
                        return;
                    };
                    let uuid = self.uuid.next_uuid();
                    up.upload_id = attachment_upload_id(&uuid);
                    up.total_chunks = attachment_total_chunks(up.bytes.len());
                    up.checksum = attachment_checksum(&up.bytes);
                    up.base = AttachmentBase {
                        connection_id: connection_id.clone(),
                        upload_id: up.upload_id.clone(),
                        session_id: up.session_id.clone(),
                    };
                    let req = AttachmentBeginRequest {
                        connection_id,
                        upload_id: up.upload_id.clone(),
                        session_id: up.session_id.clone(),
                        file_name: up.file_name.clone(),
                        mime: up.mime.clone(),
                        total_bytes: up.bytes.len(),
                        total_chunks: up.total_chunks,
                        checksum: up.checksum.clone(),
                    };
                    let arg = attachment_begin_args(&self.scope, &req);
                    let call = self.call_json(
                        token,
                        TRANSPORT_CHANNEL,
                        ATTACHMENT_BEGIN_METHOD,
                        arg,
                        COMMAND_DEFAULT_TIMEOUT_SECS as i64 * 1000,
                        channels,
                    );
                    flow.stage = FlowStage::Sent { call };
                }
                UploadPhase::AwaitChunk | UploadPhase::AwaitCommit => keep = false,
            },
            FlowKind::Read(rd) => {
                if rd.reader.should_continue() {
                    let arg = rd
                        .reader
                        .request_args(&self.scope, &rd.session_id, &rd.reference);
                    let call = self.call_json(
                        token,
                        TRANSPORT_CHANNEL,
                        ATTACHMENT_READ_METHOD,
                        arg,
                        COMMAND_DEFAULT_TIMEOUT_SECS as i64 * 1000,
                        channels,
                    );
                    flow.stage = FlowStage::Sent { call };
                } else {
                    keep = false;
                    let outcome = Completion::AttachmentBytes {
                        base64: BASE64.encode(&rd.reader.bytes),
                        media_type: rd.reader.media_type.clone(),
                    };
                    emit_completion(&flow, outcome, events);
                }
            }
            FlowKind::Ignore {
                channel,
                method,
                args,
                timeout_ms,
            } => {
                let channel = *channel;
                let method = *method;
                let args = args.clone();
                let timeout_ms = *timeout_ms;
                let call = self.call_json(token, channel, method, args, timeout_ms, channels);
                flow.stage = FlowStage::Sent { call };
            }
        }
        if keep {
            flow.kind = kind;
            self.flows.insert(flow.token.clone(), flow);
        }
    }

    // -------------------------------------------------------- 命令流

    /// 构造信封 + 记 `'[v4] command $type'`（对应 Dart `sendCommand` 里 gate 之后那段）。
    fn build_command(
        &mut self,
        params: &mut CommandParams,
        now_ms: i64,
        events: &mut Vec<ConversationEvent>,
    ) -> CommandFlow {
        let (revision, log_epoch) = {
            let sub = params
                .session_id
                .as_deref()
                .and_then(|sid| self.by_session.get(sid))
                .and_then(|key| self.subscriptions.get(key));
            match sub {
                Some(sub) => (Some(sub.state.revision()), sub.state.log_epoch()),
                None => (None, None),
            }
        };
        let acked = params.session_id.as_deref().map(|sid| self.acked.get(sid));
        let uuid = self.uuid.next_uuid();
        let envelope = build_command_envelope(
            uuid,
            self.client_id.clone(),
            params.session_id.as_deref(),
            &params.command_type,
            params.payload.clone(),
            revision,
            log_epoch.as_deref(),
            acked,
            now_ms,
        );
        events.push(ConversationEvent::Log(format!(
            "[v4] command {}",
            params.command_type
        )));
        CommandFlow {
            session_id: params.session_id.clone(),
            command_type: params.command_type.clone(),
            timeout_ms: params.timeout_ms,
            post: params.post.clone(),
            base_envelope: envelope.clone(),
            envelope,
            stale: false,
            retried: false,
            next_envelope: None,
        }
    }

    fn on_command_response(
        &mut self,
        flow: &Flow,
        cmd: &mut CommandFlow,
        res: Value,
        channels: &mut dyn ChannelRpc,
        now_ms: i64,
        events: &mut Vec<ConversationEvent>,
    ) -> CommandOutcome {
        if !cmd.stale {
            // PORT-NOTE: `observe_stale` 在 `revisionAtDecision` 类型不符时返回 `Err`，
            // 而 Dart 的 `res['revisionAtDecision'] is num` 只是让条件为假——故按 Dart 语义忽略。
            if let Ok(Some(server_revision)) =
                self.acked.observe_stale(cmd.session_id.as_deref(), &res)
            {
                events.push(ConversationEvent::Log(format!(
                    "[v4] command {} stale, retry at rev {server_revision}",
                    cmd.command_type
                )));
                let uuid = self.uuid.next_uuid();
                let retry = stale_retry_envelope(&cmd.base_envelope, &uuid, server_revision, now_ms);
                cmd.stale = true;
                cmd.retried = false;
                cmd.envelope = retry;
                let call = self.send_command_call(&flow.token, &cmd.envelope, cmd.timeout_ms, channels);
                return CommandOutcome::Resent { call };
            }
        }
        let _ = self.acked.observe_final(cmd.session_id.as_deref(), &res);
        match &cmd.post {
            CommandPost::None => {
                emit_completion(flow, Completion::Value(res), events);
            }
            CommandPost::CreateSession => match parse_create_session_result(&res) {
                Ok(session_id) => emit_completion(flow, Completion::SessionId(session_id), events),
                Err(e) => emit_completion(
                    flow,
                    Completion::Failed(protocol_error_to_transport(e)),
                    events,
                ),
            },
            CommandPost::CreateSideSession => {
                match parse_create_selection_side_session_result(&res) {
                    Ok(session_id) => emit_completion(flow, Completion::SessionId(session_id), events),
                    Err(e) => emit_completion(
                        flow,
                        Completion::Failed(protocol_error_to_transport(e)),
                        events,
                    ),
                }
            }
            CommandPost::SwitchModelConfig {
                provider,
                model,
                thought,
            } => {
                if is_unsupported_reasoning_effort(&response_message(&res)) {
                    let fallback = switch_model_config_fallback_thought(thought);
                    events.push(ConversationEvent::Log(format!(
                        "[v4] switchModelConfig retry with thought={fallback}"
                    )));
                    // Dart 里这是**再调一次 sendCommand**：重新过 gate（握手已完成，健康要重判）、
                    // 重算 baseRevision、重新记 `'[v4] command ...'`。
                    return CommandOutcome::Restart(Box::new(CommandParams {
                        session_id: cmd.session_id.clone(),
                        command_type: "switchModelConfig".to_string(),
                        payload: switch_model_config_payload(provider, model, fallback),
                        timeout_ms: cmd.timeout_ms,
                        post: CommandPost::None,
                    }));
                }
                emit_completion(flow, Completion::Value(res), events);
            }
        }
        CommandOutcome::Done
    }

    // -------------------------------------------------------- 握手

    /// 对应 `handshake()` 的 `.catchError((e) { _handshakeFuture = null; throw e; })`：
    /// 清掉握手流程，并让所有 `await handshake()` 的等待者一起失败（Dart 里每个等待者的
    /// `await` 都会抛同一个异常）。
    fn handshake_failed(&mut self, err: TransportError, events: &mut Vec<ConversationEvent>) {
        self.flows.remove(HANDSHAKE_TOKEN);
        let waiters: Vec<String> = self
            .flows
            .iter()
            .filter(|(token, f)| {
                token.as_str() != HANDSHAKE_TOKEN && matches!(f.stage, FlowStage::AwaitHandshake)
            })
            .map(|(token, _)| token.clone())
            .collect();
        for token in waiters {
            if let Some(flow) = self.take_flow(&token) {
                emit_completion(&flow, Completion::Failed(err.clone()), events);
            }
        }
    }

    // -------------------------------------------------------- 附件上传应答

    /// `attachmentPut` 的 begin / chunk / commit 三种应答；返回是否保留该流程。
    fn on_upload_response(
        &mut self,
        flow: &Flow,
        up: &mut UploadFlow,
        res: Value,
        events: &mut Vec<ConversationEvent>,
    ) -> bool {
        match up.phase {
            UploadPhase::Begin => match attachment_begin_decision(&res) {
                Err(e) => {
                    emit_completion(&flow, Completion::Failed(protocol_error_to_transport(e)), events);
                    false
                }
                Ok(AttachmentBegin::Committed { reference }) => {
                    // Dart: `onProgress?.call(1); return {ref, fileName, mime, bytes};`
                    events.push(ConversationEvent::AttachmentProgress {
                        token: flow.token.clone(),
                        session_id: up.session_id.clone(),
                        upload_id: up.upload_id.clone(),
                        progress: 1.0,
                    });
                    let descriptor = attachment_descriptor(
                        reference.as_ref(),
                        &up.file_name,
                        &up.mime,
                        up.bytes.len(),
                    );
                    emit_completion(flow, Completion::AttachmentDescriptor(descriptor), events);
                    false
                }
                Ok(AttachmentBegin::Chunked { next_chunk_index }) => {
                    up.next_chunk = next_chunk_index;
                    up.phase = UploadPhase::Between;
                    true
                }
            },
            UploadPhase::AwaitChunk => {
                let sent = up.next_chunk;
                // Dart: `nextChunk = chunkRes is Map ? (chunkRes['nextChunkIndex'] as num?)?.toInt()
                // ?? n + 1 : n + 1;`
                let next = res
                    .as_object()
                    .and_then(|m| m.get("nextChunkIndex"))
                    .and_then(crate::channel_client::json_as_dart_int)
                    .unwrap_or(sent as i64 + 1);
                let next = if next < 0 { usize::MAX } else { next as usize };
                if let Err(e) = validate_attachment_progress(sent, next) {
                    emit_completion(&flow, Completion::Failed(protocol_error_to_transport(e)), events);
                    return false;
                }
                up.next_chunk = next;
                up.phase = UploadPhase::Between;
                events.push(ConversationEvent::AttachmentProgress {
                    token: flow.token.clone(),
                    session_id: up.session_id.clone(),
                    upload_id: up.upload_id.clone(),
                    progress: attachment_progress(next, up.total_chunks),
                });
                true
            }
            UploadPhase::AwaitCommit => {
                // Dart: `final ref = commitRes is Map ? commitRes['ref'] : null;`
                let reference = res.as_object().and_then(|m| m.get("ref")).cloned();
                let descriptor =
                    attachment_descriptor(reference.as_ref(), &up.file_name, &up.mime, up.bytes.len());
                emit_completion(flow, Completion::AttachmentDescriptor(descriptor), events);
                false
            }
            UploadPhase::Between => false,
        }
    }

    // -------------------------------------------------------- 订阅流

    fn create_subscription(
        &mut self,
        kind: SubKind,
        now_ms: i64,
        events: &mut Vec<ConversationEvent>,
    ) -> u64 {
        let key = self.next_sub_key;
        self.next_sub_key += 1;
        let state = match &kind {
            SubKind::Conversation { .. } => {
                SubState::Conversation(Box::new(ConversationState::new()))
            }
            SubKind::SessionsIndex => SubState::SessionsIndex(Box::new(SessionsIndexState::new())),
        };
        // 对应 `_SubscriptionBase` 构造函数里的 `Timer.periodic(30s)` 分片清理。
        let fragment_timer = self.alloc_timer();
        events.push(ConversationEvent::TimerStart {
            token: fragment_timer,
            delay_ms: FRAGMENT_CLEANUP_INTERVAL_SECS as i64 * 1000,
        });
        if let SubKind::Conversation { session_id } = &kind {
            // PORT-NOTE: Dart 在 `_start()` 成功后才写 `_subscriptions[sessionId]`；这里在创建时
            // 就登记。差异只影响「订阅在飞时发命令」的 baseRevision 取值：Dart 读不到订阅（按 0
            // 算），这里读到空 state（`revision()` 也是 0），线上一致。
            self.by_session.insert(session_id.clone(), key);
        }
        self.subscriptions.insert(
            key,
            Subscription {
                kind,
                core: SubscriptionCore::new(),
                state,
                listener: None,
                last_frame_at_ms: now_ms,
                watchdog: None,
                fragment_cleanup: Some(fragment_timer),
                resubscribe_timer: None,
                started: false,
            },
        );
        self.sub_order.push(key);
        key
    }

    fn on_subscribe_response(
        &mut self,
        flow: &Flow,
        sf: &mut SubscribeFlow,
        res: Value,
        channels: &mut dyn ChannelRpc,
        now_ms: i64,
        events: &mut Vec<ConversationEvent>,
    ) -> bool {
        let Some(key) = sf.sub_key else {
            return false;
        };
        let method = sf.kind.subscribe_method();
        match parse_subscribe_ack(&res, method) {
            Err(e) => {
                self.subscribe_failed(
                    flow,
                    Some(key),
                    sf.resubscribe,
                    protocol_error_to_transport(e),
                    events,
                );
                false
            }
            Ok(ack) => {
                let tag = self.sub_log_tag(key);
                let topic = self.sub_topic(key);
                let subscription_id = ack.subscription_id.clone();
                if let Some(sub) = self.subscriptions.get_mut(&key) {
                    sub.core.set_subscription_id(subscription_id.clone());
                    sub.started = true;
                }
                let ack_epoch = ack
                    .ack
                    .as_ref()
                    .map(|a| Value::Object(a.clone()))
                    .and_then(|v| conversation_ack_log_epoch(&v));
                // 订阅 ack 是「握手 → 订阅」这条链的确认点；resubscribe 标记必须带上：
                // 桥恢复后的重订阅在宿主侧会表现成「自己重载」，排查时要能区分。
                events.push(ConversationEvent::Log(format!(
                    "[{tag}] subscribed {topic} id={subscription_id}"
                )));
                // 补充：ack 的 epoch 与订阅模式（first / resubscribe）。桥恢复后的重订阅
                // 在宿主侧会表现成「自己重载」，排查时要能和首次订阅区分开。
                events.push(ConversationEvent::Log(format!(
                    "[conv] subscribe ack topic={topic} mode={} epoch={}",
                    if sf.resubscribe { "resubscribe" } else { "first" },
                    interp_opt_str(ack_epoch.as_deref()),
                )));
                // `_onSubscribeAck`（只有 ConversationSubscription 覆写）。
                if matches!(sf.kind, SubKind::Conversation { .. }) {
                    if let Some(epoch) = ack_epoch {
                        if let Some(sub) = self.subscriptions.get_mut(&key) {
                            if let SubState::Conversation(state) = &mut sub.state {
                                state.log_epoch = Some(epoch);
                            }
                        }
                    }
                }
                let session_id = match &sf.kind {
                    SubKind::Conversation { session_id } => Some(session_id.clone()),
                    SubKind::SessionsIndex => None,
                };
                events.push(ConversationEvent::Subscribed {
                    session_id,
                    subscription_id,
                });
                // 订阅应答到达前的暂存帧，现在按序应用。
                let staged = match self.subscriptions.get_mut(&key) {
                    Some(sub) => sub.core.drain_staged(),
                    None => Vec::new(),
                };
                for frame in staged {
                    self.accept_logical_frame(key, frame, now_ms, channels, events);
                }
                // `_onStarted()`（只有 ConversationSubscription 覆写：起 watchdog）。
                if matches!(sf.kind, SubKind::Conversation { .. }) {
                    self.start_watchdog(key, events);
                }
                events.push(ConversationEvent::StateChanged);
                emit_completion(flow, Completion::Ok, events);
                false
            }
        }
    }

    fn subscribe_failed(
        &mut self,
        flow: &Flow,
        key: Option<u64>,
        resubscribe: bool,
        err: TransportError,
        events: &mut Vec<ConversationEvent>,
    ) {
        if resubscribe {
            if let Some(key) = key {
                let tag = self.sub_log_tag(key);
                // Dart: `_transport._log('[$_logTag] resubscribe failed: $e')`
                events.push(ConversationEvent::Log(format!(
                    "[{tag}] resubscribe failed: {}",
                    err.dart_to_string()
                )));
                let timer = self.alloc_timer();
                if let Some(sub) = self.subscriptions.get_mut(&key) {
                    sub.resubscribe_timer = Some(timer);
                }
                events.push(ConversationEvent::TimerStart {
                    token: timer,
                    delay_ms: RESUBSCRIBE_RETRY_SECS as i64 * 1000,
                });
            }
        } else if let Some(key) = key {
            // Dart `_start()` 的 catch + `subscribe()` 的 catch：清状态、dispose。
            if let Some(sub) = self.subscriptions.get_mut(&key) {
                sub.core.on_start_failure();
            }
            if let Some(call) = self.dispose_subscription(key, events) {
                self.enqueue_ignore_call(call.method, call.args);
            }
        }
        emit_completion(flow, Completion::Failed(err), events);
    }

    /// 对应 `_SubscriptionBase._onBridgeRecovered` + `_resubscribe()`。
    fn start_resubscribe(
        &mut self,
        key: u64,
        _channels: &mut dyn ChannelRpc,
        _now_ms: i64,
        events: &mut Vec<ConversationEvent>,
    ) {
        let (disposed, tag, topic, kind) = match self.subscriptions.get(&key) {
            Some(sub) => (
                sub.core.disposed,
                sub.kind.log_tag(),
                sub.kind.topic(&self.scope),
                sub.kind.clone(),
            ),
            None => return,
        };
        if disposed {
            return;
        }
        events.push(ConversationEvent::Log(format!(
            "[{tag}] bridge recovered, resubscribing {topic}"
        )));
        self.abort_subscribe_flow(key, events);
        let token = self.new_internal_token("resubscribe");
        self.flows.insert(
            token.clone(),
            Flow {
                token,
                op: None,
                stage: FlowStage::AwaitHandshake,
                healthy_ok: true,
                kind: FlowKind::Subscribe(Box::new(SubscribeFlow {
                    sub_key: Some(key),
                    kind,
                    resubscribe: true,
                    phase: SubscribePhase::CancelOld,
                })),
            },
        );
    }

    /// 顶掉该订阅上仍在飞的订阅流程（桥恢复竞态；见模块头 PORT-NOTE）。
    fn abort_subscribe_flow(&mut self, key: u64, events: &mut Vec<ConversationEvent>) {
        let tokens: Vec<String> = self
            .flows
            .iter()
            .filter(|(_, f)| match &f.kind {
                FlowKind::Subscribe(sf) => sf.sub_key == Some(key),
                _ => false,
            })
            .map(|(t, _)| t.clone())
            .collect();
        for token in tokens {
            if let Some(flow) = self.take_flow(&token) {
                emit_completion(
                    &flow,
                    Completion::Failed(TransportError::invalid_state("superseded by resubscribe")),
                    events,
                );
            }
        }
    }

    /// 对应 `_SubscriptionBase.dispose()`；返回待发的 unsubscribe 请求。
    fn dispose_subscription(
        &mut self,
        key: u64,
        events: &mut Vec<ConversationEvent>,
    ) -> Option<IgnoreCall> {
        let (session_id, listener, resubscribe_timer, fragment_timer, watchdog, subscription_id, method, extra) = {
            let sub = self.subscriptions.get_mut(&key)?;
            if sub.core.disposed {
                return None;
            }
            // `_disposed = true; _resubscribeTimer?.cancel(); _fragmentCleanup?.cancel();`
            sub.core.mark_disposed();
            let session_id = match &sub.kind {
                SubKind::Conversation { session_id } => Some(session_id.clone()),
                SubKind::SessionsIndex => None,
            };
            (
                session_id,
                sub.listener.take(),
                sub.resubscribe_timer.take(),
                sub.fragment_cleanup.take(),
                sub.watchdog.take(),
                sub.core.subscription_id.clone(),
                sub.kind.unsubscribe_method(),
                sub.kind.unsubscribe_args(),
            )
        };
        if let Some(timer) = resubscribe_timer {
            events.push(ConversationEvent::TimerCancel(timer));
        }
        if let Some(timer) = fragment_timer {
            events.push(ConversationEvent::TimerCancel(timer));
        }
        if let Some(timer) = watchdog {
            events.push(ConversationEvent::TimerCancel(timer));
        }
        if let Some(session_id) = session_id {
            self.by_session.remove(&session_id);
        }
        if let Some(listener) = listener {
            self.listeners.remove(&listener);
            events.push(ConversationEvent::UnlistenChannel { id: listener });
        }
        self.abort_subscribe_flow(key, events);
        if let Some(sub) = self.subscriptions.get_mut(&key) {
            sub.core.fragments.clear();
        }
        subscription_id.map(|subscription_id| {
            let args = unsubscribe_request_args(&self.scope, &subscription_id, &extra);
            IgnoreCall { method, args }
        })
    }

    fn conversation_state_mut(&mut self, session_id: &str) -> Option<&mut ConversationState> {
        let key = *self.by_session.get(session_id)?;
        match self.subscriptions.get_mut(&key).map(|s| &mut s.state) {
            Some(SubState::Conversation(state)) => Some(state),
            _ => None,
        }
    }

    fn sub_log_tag(&self, key: u64) -> &'static str {
        self.subscriptions
            .get(&key)
            .map(|s| s.kind.log_tag())
            .unwrap_or("v4")
    }

    fn sub_topic(&self, key: u64) -> String {
        self.subscriptions
            .get(&key)
            .map(|s| s.kind.topic(&self.scope))
            .unwrap_or_default()
    }

    fn sub_unsubscribe_method(&self, key: u64) -> &'static str {
        self.subscriptions
            .get(&key)
            .map(|s| s.kind.unsubscribe_method())
            .unwrap_or(CONVERSATION_UNSUBSCRIBE_METHOD)
    }

    fn sub_unsubscribe_args(&self, key: u64) -> JsonMap {
        self.subscriptions
            .get(&key)
            .map(|s| s.kind.unsubscribe_args())
            .unwrap_or_default()
    }

    fn sub_resync_method(&self, key: u64) -> &'static str {
        self.subscriptions
            .get(&key)
            .map(|s| s.kind.resync_method())
            .unwrap_or(CONVERSATION_RESYNC_METHOD)
    }

    fn start_watchdog(&mut self, key: u64, events: &mut Vec<ConversationEvent>) {
        self.cancel_watchdog(key, events);
        let timer = self.alloc_timer();
        if let Some(sub) = self.subscriptions.get_mut(&key) {
            sub.watchdog = Some(timer);
        }
        events.push(ConversationEvent::TimerStart {
            token: timer,
            delay_ms: WATCHDOG_INTERVAL_SECS as i64 * 1000,
        });
    }

    fn cancel_watchdog(&mut self, key: u64, events: &mut Vec<ConversationEvent>) {
        let timer = self
            .subscriptions
            .get_mut(&key)
            .and_then(|s| s.watchdog.take());
        if let Some(timer) = timer {
            events.push(ConversationEvent::TimerCancel(timer));
        }
    }

    /// 对应 `_handleWireFrame` → `_acceptOrStage` → `_acceptFragment` 的整条链路。
    fn accept_wire_frame(
        &mut self,
        key: u64,
        data: &Value,
        channels: &mut dyn ChannelRpc,
        now_ms: i64,
        events: &mut Vec<ConversationEvent>,
    ) {
        let (topic, tag) = match self.subscriptions.get(&key) {
            Some(sub) => (sub.kind.topic(&self.scope), sub.kind.log_tag()),
            None => return,
        };
        let (ready_frames, logs) = {
            let Some(sub) = self.subscriptions.get_mut(&key) else {
                return;
            };
            if sub.core.disposed {
                return;
            }
            let Some(obj) = data.as_object() else {
                return;
            };
            let mut logs: Vec<String> = Vec::new();
            let frames: Vec<JsonMap> = match route_wire_frame(&topic, obj) {
                WireFrameRoute::Ignored => Vec::new(),
                WireFrameRoute::Complete(inner) => {
                    sub.core.accept_or_stage(inner.clone()).into_iter().collect()
                }
                WireFrameRoute::Fragment(outer) => match sub.core.fragments.accept(outer, now_ms) {
                    Ok(FragmentOutcome::Complete(value)) => match value.as_object().cloned() {
                        Some(map) => sub.core.accept_or_stage(map).into_iter().collect(),
                        None => Vec::new(),
                    },
                    Ok(FragmentOutcome::BadJson(e)) => {
                        logs.push(format!("[{tag}] bad logical frame: {e}"));
                        Vec::new()
                    }
                    // PORT-NOTE: Dart 在 base64 非法时记 `'[$_logTag] bad fragment: $e'`，但
                    // `FragmentAssembler` 把它与「字段缺失/越界/数量不符」一起收敛成 `Ignored`，
                    // 无法区分（其余分支都照抄了）。
                    Ok(FragmentOutcome::Pending)
                    | Ok(FragmentOutcome::Ignored)
                    | Ok(FragmentOutcome::DecodedNonMap) => Vec::new(),
                    // Dart 这里会抛 `TypeError`，被 `ChannelClient.handleMessage` 的 try/catch
                    // 记成 `'[ipc] invalid frame: $e'`。
                    Err(e) => {
                        logs.push(format!("[ipc] invalid frame: {e}"));
                        Vec::new()
                    }
                },
            };
            (frames, logs)
        };
        for line in logs {
            events.push(ConversationEvent::Log(line));
        }
        for frame in ready_frames {
            self.accept_logical_frame(key, frame, now_ms, channels, events);
        }
    }

    /// 对应 `ConversationSubscription._acceptLogicalFrame` / `SessionsIndexSubscription` 的同名方法。
    fn accept_logical_frame(
        &mut self,
        key: u64,
        frame: JsonMap,
        now_ms: i64,
        channels: &mut dyn ChannelRpc,
        events: &mut Vec<ConversationEvent>,
    ) {
        let (tag, subscription_id, is_conversation) = match self.subscriptions.get(&key) {
            Some(sub) => (
                sub.kind.log_tag(),
                sub.core.subscription_id.clone(),
                matches!(sub.kind, SubKind::Conversation { .. }),
            ),
            None => return,
        };
        // `if (subId == null || frame['subscriptionId'] != subId) return;`
        let Some(subscription_id) = subscription_id else {
            return;
        };
        if !frame_matches_subscription(&frame, &subscription_id) {
            return;
        }
        if is_conversation {
            // `_lastFrameAt = DateTime.now();`（在 applyFrame 之前）
            if let Some(sub) = self.subscriptions.get_mut(&key) {
                sub.last_frame_at_ms = now_ms;
            }
        }
        let value = Value::Object(frame.clone());
        let mut gap = false;
        let result = {
            let Some(sub) = self.subscriptions.get_mut(&key) else {
                return;
            };
            let mut on_gap = || gap = true;
            sub.state.apply_frame(&value, &mut on_gap)
        };
        if let Err(e) = result {
            events.push(ConversationEvent::Log(format!(
                "[{tag}] applyFrame failed: {e}"
            )));
            self.request_resync(key, channels, now_ms, events, "apply-frame-failed");
            return;
        }
        if gap {
            self.request_resync(key, channels, now_ms, events, "gap");
            return;
        }
        // `notifyListeners()` 只在 payload 是 Map 时发生（deltas 的 gap 已提前返回）。
        if !frame
            .get("payload")
            .map(|p| p.is_object())
            .unwrap_or(false)
        {
            return;
        }
        let event = match self.subscriptions.get(&key).map(|s| &s.state) {
            Some(SubState::Conversation(state)) => {
                let session_id = match self.subscriptions.get(&key).map(|s| &s.kind) {
                    Some(SubKind::Conversation { session_id }) => session_id.clone(),
                    _ => return,
                };
                // 逻辑帧摘要：滑动手势 / 运行时推送都会走到这里，是「谁触发了重载」的直接证据。
                let payload = frame.get("payload");
                let kind = payload
                    .and_then(|p| p.get("kind"))
                    .and_then(|v| v.as_str())
                    .unwrap_or("-");
                let ops = payload
                    .and_then(|p| p.get("deltas"))
                    .and_then(|v| v.as_array())
                    .map(|a| a.len())
                    .unwrap_or(0);
                events.push(ConversationEvent::Log(format!(
                    "[conv] rowsUpdated session={session_id} source=frame kind={kind} ops={ops} seq={} total={} rows={} firstRow={}",
                    state.seq,
                    state.total_count,
                    state.rows.len(),
                    opt_value(state.first_row_id),
                )));
                ConversationEvent::RowsUpdated {
                    session_id,
                    frame: Some(frame),
                    ready: state.ready,
                    seq: state.seq,
                    total_count: state.total_count,
                    first_row_id: state.first_row_id,
                    row_count: state.rows.len(),
                }
            }
            Some(SubState::SessionsIndex(state)) => ConversationEvent::SessionsIndexUpdated {
                workspace_id: state.workspace_id.clone(),
                log_epoch: state.log_epoch.clone(),
                seq: state.seq,
                ready: state.ready,
                session_count: state.sessions.len(),
            },
            None => return,
        };
        events.push(event);
    }

    /// 对应 `_resync()`：门控 + 日志 + 发 `resync*V4`。
    ///
    /// `reason` 只用于日志：滑动手势引发的 gap / watchdog 静默 / applyFrame 失败
    /// 走的是同一条重载路径，日志里必须能区分。
    fn request_resync(
        &mut self,
        key: u64,
        channels: &mut dyn ChannelRpc,
        now_ms: i64,
        events: &mut Vec<ConversationEvent>,
        reason: &'static str,
    ) {
        let (tag, subscription_id, epoch, seq, method, extra) = match self.subscriptions.get(&key) {
            Some(sub) => (
                sub.kind.log_tag(),
                sub.core.subscription_id.clone(),
                sub.state.log_epoch(),
                sub.state.seq(),
                sub.kind.resync_method(),
                sub.kind.resync_args(),
            ),
            None => return,
        };
        let Some(subscription_id) = subscription_id else {
            return;
        };
        // `if (id == null || _disposed || _resyncing) return;`
        {
            let Some(sub) = self.subscriptions.get_mut(&key) else {
                return;
            };
            if !sub.core.can_resync() {
                return;
            }
            sub.core.set_resyncing(true);
        }
        events.push(ConversationEvent::Log(format!(
            "[{tag}] resync (gap detected) seq={seq} logEpoch={}",
            interp_opt_str(epoch.as_deref())
        )));
        // 补充：谁触发的重载（watchdog 静默 / 帧 gap / applyFrame 失败）。
        events.push(ConversationEvent::Log(format!(
            "[conv] resync request reason={reason} seq={seq} subscriptionId={subscription_id}"
        )));
        let args = resync_request_args(&self.scope, &subscription_id, &extra, epoch.as_deref(), seq);
        let token = self.new_internal_token("resync");
        let call = self.call_json(
            &token,
            TRANSPORT_CHANNEL,
            method,
            args,
            COMMAND_DEFAULT_TIMEOUT_SECS as i64 * 1000,
            channels,
        );
        self.flows.insert(
            token.clone(),
            Flow {
                token,
                op: None,
                stage: FlowStage::Sent { call },
                healthy_ok: true,
                kind: FlowKind::Resync { sub_key: key },
            },
        );
        let _ = now_ms;
    }
}

/// dispose 时待发的 unsubscribe 请求。
struct IgnoreCall {
    method: &'static str,
    args: Value,
}

fn emit_completion(flow: &Flow, outcome: Completion, events: &mut Vec<ConversationEvent>) {
    if flow.op.is_some() {
        events.push(ConversationEvent::Completed {
            token: flow.token.clone(),
            outcome,
        });
    }
}

fn rows_updated_event(
    session_id: &str,
    frame: Option<JsonMap>,
    state: &ConversationState,
) -> ConversationEvent {
    ConversationEvent::RowsUpdated {
        session_id: session_id.to_string(),
        frame,
        ready: state.ready,
        seq: state.seq,
        total_count: state.total_count,
        first_row_id: state.first_row_id,
        row_count: state.rows.len(),
    }
}

/// 状态写入类 op 触发的 `RowsUpdated`：先把摘要写进日志，再发事件。
///
/// `prependOlderRows` / `setHistoryExhausted` 等是 UI 主动改本地窗口的路径，和网络帧
/// 区分开（`source=op`），排查「滑动后谁把窗口重置了」时能一眼看到。
fn push_rows_updated(
    events: &mut Vec<ConversationEvent>,
    session_id: &str,
    frame: Option<JsonMap>,
    state: &ConversationState,
) {
    events.push(ConversationEvent::Log(format!(
        "[conv] rowsUpdated session={session_id} source=op seq={} total={} rows={} firstRow={}",
        state.seq,
        state.total_count,
        state.rows.len(),
        opt_value(state.first_row_id),
    )));
    events.push(rows_updated_event(session_id, frame, state));
}

fn opt_value(value: Option<i64>) -> String {
    value
        .map(|n| n.to_string())
        .unwrap_or_else(|| "-".to_string())
}

fn protocol_error_to_transport(e: ProtocolError) -> TransportError {
    match e {
        ProtocolError::InvalidState(message) => TransportError::invalid_state(message),
        other => TransportError::protocol(other.to_string()),
    }
}

// ============================================================ 6. 参数解析

/// 把 `args` 规整成对象（`null`/缺失 → 空对象）。
fn args_object(op: &str, args: &Value) -> Result<JsonMap> {
    match args {
        Value::Null => Ok(JsonMap::new()),
        Value::Object(m) => Ok(m.clone()),
        _ => Err(ProtocolError::Other(format!(
            "{op}: args must be a JSON object"
        ))),
    }
}

/// 取必填字符串参数。
fn req_str(op: &str, args: &JsonMap, name: &str) -> Result<String> {
    match args.get(name) {
        Some(Value::String(s)) => Ok(s.clone()),
        _ => Err(ProtocolError::Other(format!(
            "{op}: missing required string arg '{name}'"
        ))),
    }
}

/// 取必填布尔参数。
fn req_bool(op: &str, args: &JsonMap, name: &str) -> Result<bool> {
    match args.get(name) {
        Some(Value::Bool(b)) => Ok(*b),
        _ => Err(ProtocolError::Other(format!(
            "{op}: missing required bool arg '{name}'"
        ))),
    }
}

/// 取必填对象参数。
fn req_obj(op: &str, args: &JsonMap, name: &str) -> Result<JsonMap> {
    match args.get(name) {
        Some(Value::Object(m)) => Ok(m.clone()),
        _ => Err(ProtocolError::Other(format!(
            "{op}: missing required object arg '{name}'"
        ))),
    }
}

/// 取可选字符串参数（缺失/`null` → `None`；类型不符 → 报错）。
fn opt_str(op: &str, args: &JsonMap, name: &str) -> Result<Option<String>> {
    match args.get(name) {
        None | Some(Value::Null) => Ok(None),
        Some(Value::String(s)) => Ok(Some(s.clone())),
        Some(_) => Err(ProtocolError::Other(format!(
            "{op}: arg '{name}' must be a string"
        ))),
    }
}

/// 取可选对象参数。
fn opt_obj(op: &str, args: &JsonMap, name: &str) -> Result<Option<JsonMap>> {
    match args.get(name) {
        None | Some(Value::Null) => Ok(None),
        Some(Value::Object(m)) => Ok(Some(m.clone())),
        Some(_) => Err(ProtocolError::Other(format!(
            "{op}: arg '{name}' must be an object"
        ))),
    }
}

/// 取可选整数参数。
fn opt_i64(op: &str, args: &JsonMap, name: &str) -> Result<Option<i64>> {
    match args.get(name) {
        None | Some(Value::Null) => Ok(None),
        Some(v) => crate::channel_client::json_as_dart_int(v)
            .map(Some)
            .ok_or_else(|| ProtocolError::Other(format!("{op}: arg '{name}' must be an integer"))),
    }
}

/// 取可选布尔参数。
fn opt_bool(op: &str, args: &JsonMap, name: &str) -> Result<Option<bool>> {
    match args.get(name) {
        None | Some(Value::Null) => Ok(None),
        Some(Value::Bool(b)) => Ok(Some(*b)),
        Some(_) => Err(ProtocolError::Other(format!(
            "{op}: arg '{name}' must be a bool"
        ))),
    }
}

/// 取可选字符串数组参数。
fn opt_str_list(op: &str, args: &JsonMap, name: &str) -> Result<Option<Vec<String>>> {
    match args.get(name) {
        None | Some(Value::Null) => Ok(None),
        Some(Value::Array(items)) => {
            let mut out = Vec::with_capacity(items.len());
            for item in items {
                match item {
                    Value::String(s) => out.push(s.clone()),
                    _ => {
                        return Err(ProtocolError::Other(format!(
                            "{op}: arg '{name}' must be an array of strings"
                        )))
                    }
                }
            }
            Ok(Some(out))
        }
        Some(_) => Err(ProtocolError::Other(format!(
            "{op}: arg '{name}' must be an array of strings"
        ))),
    }
}

/// 取可选对象数组参数（非对象元素被跳过，与 Dart 的 `whereType<Map>()` 一致）。
fn opt_obj_list(op: &str, args: &JsonMap, name: &str) -> Result<Option<Vec<JsonMap>>> {
    match args.get(name) {
        None | Some(Value::Null) => Ok(None),
        Some(Value::Array(items)) => Ok(Some(
            items.iter().filter_map(|i| i.as_object().cloned()).collect(),
        )),
        Some(_) => Err(ProtocolError::Other(format!(
            "{op}: arg '{name}' must be an array"
        ))),
    }
}

/// 命令类 op 的可选 `timeoutSecs`（默认 30 秒，对应 `sendCommand` 的 `timeout` 参数）。
fn opt_timeout_ms(op: &str, args: &JsonMap) -> Result<i64> {
    Ok(match opt_i64(op, args, "timeoutSecs")? {
        Some(secs) => secs * 1000,
        None => COMMAND_DEFAULT_TIMEOUT_SECS as i64 * 1000,
    })
}

// ============================================================ 7. 序列化辅助

/// 对应 Dart 插值里 `String?` 的部分（`null` → 字面量 `null`）。
fn interp_opt_str(v: Option<&str>) -> String {
    match v {
        Some(s) => s.to_string(),
        None => "null".to_string(),
    }
}

/// 对应 Dart `skills()` 的请求参数：
/// `{'workspacePath': scope['workspacePath'], if (scope['workspaceIdentity'] != null)
/// 'workspaceIdentity': ..., 'provider': 'glm'}`。
///
/// `workspacePath` **恒出现**（可能是 null），`workspaceIdentity` 只在非 null 时出现。
fn skills_args(scope: &JsonMap) -> Value {
    let mut m = JsonMap::new();
    m.insert(
        "workspacePath".to_string(),
        scope.get("workspacePath").cloned().unwrap_or(Value::Null),
    );
    if let Some(identity) = scope.get("workspaceIdentity") {
        if !identity.is_null() {
            m.insert("workspaceIdentity".to_string(), identity.clone());
        }
    }
    m.insert(
        "provider".to_string(),
        Value::String(SKILLS_PROVIDER.to_string()),
    );
    Value::Object(m)
}

/// `model-selection.getView` 的唯一位置参数：`{selection?: {providerId, modelId, options?}}`。
fn model_selection_args(selection: &Option<JsonMap>) -> Value {
    let mut m = JsonMap::new();
    if let Some(selection) = selection {
        m.insert(
            "selection".to_string(),
            Value::Object(selection.clone()),
        );
    }
    Value::Object(m)
}

/// `SkillEntry` → JSON（对应 Dart 的 `SkillEntry` 字段）。
fn skill_entry_to_json(entry: &SkillEntry) -> Value {
    json!({
        "id": entry.id,
        "name": entry.name,
        "path": entry.path,
        "scope": entry.scope,
        "description": entry.description,
        "argumentHint": entry.argument_hint,
        "enabled": entry.enabled,
    })
}

/// `WorkspacePrep` → JSON（对应 Dart `WorkspacePrep` 的 `configOptions` / `slashCommands`）。
fn workspace_prep_to_json(prep: &WorkspacePrep) -> Value {
    let config_options: Vec<Value> = prep
        .config_options
        .iter()
        .map(|o| {
            let options: Vec<Value> = o
                .options
                .iter()
                .map(|v| {
                    json!({
                        "value": v.value,
                        "name": v.name,
                        "description": v.description,
                        "modelProviderName": v.model_provider_name,
                    })
                })
                .collect();
            json!({
                "id": o.id,
                "name": o.name,
                "category": o.category,
                "type": o.type_,
                "currentValue": o.current_value,
                "options": options,
            })
        })
        .collect();
    let slash_commands: Vec<Value> = prep
        .slash_commands
        .iter()
        .map(|c| {
            json!({
                "name": c.name,
                "description": c.description,
                "inputHint": c.input_hint,
                "source": c.source,
            })
        })
        .collect();
    json!({
        "configOptions": config_options,
        "slashCommands": slash_commands,
    })
}

/// `ConversationState` 的快照（字段 + 全部派生 getter）。
fn conversation_state_snapshot(sub: &Subscription, state: &ConversationState) -> Value {
    let queue_items: Vec<Value> = state.queue_items().into_iter().map(Value::Object).collect();
    let background_works: Vec<Value> = state
        .background_works()
        .into_iter()
        .map(Value::Object)
        .collect();
    let pending_interactions: Vec<Value> = state
        .pending_interactions()
        .into_iter()
        .map(Value::Object)
        .collect();
    json!({
        // --- 订阅元数据（本模块） ---
        "subscriptionId": sub.core.subscription_id,
        "disposed": sub.core.disposed,
        "started": sub.started,
        "listenerId": sub.listener,
        "resyncing": sub.core.resyncing,
        "stagedFrameCount": sub.core.staged_frames.len(),
        "pendingFragmentCount": sub.core.fragments.len(),
        "lastFrameAtMs": sub.last_frame_at_ms,
        // --- ConversationState 字段 ---
        "snapshot": state.snapshot,
        "rows": state.rows,
        "seq": state.seq,
        "logEpoch": state.log_epoch,
        "firstRowId": state.first_row_id,
        "totalCount": state.total_count,
        "ready": state.ready,
        "historyExhausted": state.history_exhausted,
        // --- 派生 getter ---
        "control": state.control(),
        "revision": state.revision(),
        "phase": state.phase(),
        "canStop": state.can_stop(),
        "isRunning": state.is_running(),
        "config": state.config(),
        "currentModel": state.current_model(),
        "currentThought": state.current_thought(),
        "currentMode": state.current_mode(),
        "thoughtLevels": state.thought_levels(),
        "queue": state.queue(),
        "queueItems": queue_items,
        "autoDrain": state.auto_drain(),
        "usage": state.usage(),
        "canLoadOlder": state.can_load_older(),
        "oldestRowId": state.oldest_row_id(),
        "backgroundWorks": background_works,
        "goal": state.goal(),
        "plan": state.plan(),
        "inputRoutingMode": state.input_routing_mode(),
        "pendingInteractions": pending_interactions,
        "hasStreamingRow": state.has_streaming_row(),
    })
}

/// `SessionsIndexState` 的快照。
fn sessions_index_snapshot(sub: &Subscription, state: &SessionsIndexState) -> Value {
    let list: Vec<Value> = state
        .list()
        .into_iter()
        .map(|entry| Value::Object(entry.raw))
        .collect();
    json!({
        "subscriptionId": sub.core.subscription_id,
        "disposed": sub.core.disposed,
        "started": sub.started,
        "listenerId": sub.listener,
        "resyncing": sub.core.resyncing,
        "lastFrameAtMs": sub.last_frame_at_ms,
        "workspaceId": state.workspace_id,
        "logEpoch": state.log_epoch,
        "seq": state.seq,
        "ready": state.ready,
        "sessionCount": state.sessions.len(),
        "list": list,
    })
}

// ============================================================ 8. 测试
//
// 期望值来源：
// * 行/快照相关的断言直接搬自 `Reference_code/test/conversation_test.dart` 的夹具
//   （`snapshot clears and replaces state` 里的 `logEpoch: 'epoch-1'` / `revision: 5` /
//   `toSeq: 10` / `totalCount: 2` / `firstRowId: 1` / 两行 `hello`+`hi`）。
// * 线上格式（方法名、参数形状、超时、envelope 字段、附件分块数学、base64、sha256）的期望值
//   由本机 Python 按 Dart 源码逐行独立算出，注释里标了 `Python:`。
// * 其余断言由 Dart 分支行为直接推出（Dart 侧没有 `ConversationTransport` 的单测，
//   只有 `conversation_test.dart` 覆盖状态层）。

#[cfg(test)]
mod tests {
    use super::*;
    use crate::channel_client::ChannelRpcError;

    const NOW: i64 = 1_700_000_000_000;

    // ---------------------------------------------------------- 测试替身

    #[derive(Debug, Clone)]
    struct RecordedCall {
        channel: String,
        method: String,
        args: Vec<IpcValue>,
        timeout_ms: i64,
    }

    /// 记录调用的 [`ChannelRpc`] 替身。
    ///
    /// id 分配照抄 `ChannelClient`（Dart `_lastRequestId++` 后自增，首个 id 为 0），
    /// 因此第 `i` 次调用（0 起）的 id 恒为 `i`。
    #[derive(Default)]
    struct FakeChannels {
        calls: Vec<RecordedCall>,
        next_id: u64,
    }

    impl FakeChannels {
        fn new() -> Self {
            Self::default()
        }

        fn count(&self) -> usize {
            self.calls.len()
        }

        fn method(&self, i: usize) -> &str {
            &self.calls[i].method
        }

        fn channel(&self, i: usize) -> &str {
            &self.calls[i].channel
        }

        /// 第 `i` 次调用的第一个参数（Dart 视角的 JSON）。
        fn arg(&self, i: usize) -> Value {
            to_dart_view(&self.calls[i].args[0])
        }
    }

    impl ChannelRpc for FakeChannels {
        fn call(
            &mut self,
            channel: &str,
            method: &str,
            args: Vec<IpcValue>,
            timeout_ms: i64,
        ) -> u64 {
            let id = self.next_id;
            self.next_id += 1;
            self.calls.push(RecordedCall {
                channel: channel.to_string(),
                method: method.to_string(),
                args,
                timeout_ms,
            });
            id
        }
    }

    // ---------------------------------------------------------- 辅助

    fn m(v: Value) -> JsonMap {
        v.as_object().expect("测试夹具应是对象").clone()
    }

    fn scope() -> JsonMap {
        m(json!({"workspacePath": "/w", "workspaceIdentity": "id-1"}))
    }

    /// 确定性 uuid：`uuid-1` 给 clientId，之后按调用顺序递增。
    fn transport() -> ConversationTransport {
        let mut n = 0u64;
        ConversationTransport::with_uuid_source(
            scope(),
            "3.6.5",
            Box::new(move || {
                n += 1;
                format!("uuid-{n}")
            }),
        )
    }

    fn ok(
        t: &mut ConversationTransport,
        ch: &mut FakeChannels,
        id: u64,
        data: Value,
    ) -> Vec<ConversationEvent> {
        t.step(
            ConversationInput::Channel(ChannelEvent::PromiseSuccess {
                id,
                data: IpcValue::Object(data),
            }),
            ch,
            NOW,
        )
    }

    /// 应答第 `i` 次调用（id 与下标一致）。
    fn ok_i(
        t: &mut ConversationTransport,
        ch: &mut FakeChannels,
        i: usize,
        data: Value,
    ) -> Vec<ConversationEvent> {
        ok(t, ch, i as u64, data)
    }

    fn err(
        t: &mut ConversationTransport,
        ch: &mut FakeChannels,
        id: u64,
        message: &str,
    ) -> Vec<ConversationEvent> {
        t.step(
            ConversationInput::Channel(ChannelEvent::PromiseError {
                id,
                error: ChannelRpcError::new(message, IpcValue::Undefined),
            }),
            ch,
            NOW,
        )
    }

    fn timeout(
        t: &mut ConversationTransport,
        ch: &mut FakeChannels,
        id: u64,
    ) -> Vec<ConversationEvent> {
        t.step(ConversationInput::ChannelTimeout { id }, ch, NOW)
    }

    fn op(
        t: &mut ConversationTransport,
        ch: &mut FakeChannels,
        name: &str,
        args: Value,
    ) -> Vec<ConversationEvent> {
        t.call_op(name, &args, ch, NOW).expect("op 应被接受")
    }

    fn completed(events: &[ConversationEvent]) -> Option<&Completion> {
        events.iter().find_map(|e| match e {
            ConversationEvent::Completed { outcome, .. } => Some(outcome),
            _ => None,
        })
    }

    fn token_of(events: &[ConversationEvent]) -> String {
        events
            .iter()
            .find_map(|e| match e {
                ConversationEvent::OpAccepted { token, .. } => Some(token.clone()),
                _ => None,
            })
            .expect("应有 OpAccepted")
    }

    fn listen_token(events: &[ConversationEvent]) -> u64 {
        events
            .iter()
            .find_map(|e| match e {
                ConversationEvent::ListenChannel { token, .. } => Some(*token),
                _ => None,
            })
            .expect("应有 ListenChannel")
    }

    fn completed_token(events: &[ConversationEvent]) -> String {
        events
            .iter()
            .find_map(|e| match e {
                ConversationEvent::Completed { token, .. } => Some(token.clone()),
                _ => None,
            })
            .expect("应有 Completed")
    }

    fn logs(events: &[ConversationEvent]) -> Vec<String> {
        events
            .iter()
            .filter_map(|e| match e {
                ConversationEvent::Log(line) => Some(line.clone()),
                _ => None,
            })
            .collect()
    }

    fn timer_start(events: &[ConversationEvent], delay_ms: i64) -> Option<TimerId> {
        events.iter().find_map(|e| match e {
            ConversationEvent::TimerStart { token, delay_ms: d } if *d == delay_ms => Some(*token),
            _ => None,
        })
    }

    /// 跑完 `handshake()`（hello + initialize）。
    fn drive_handshake(t: &mut ConversationTransport, ch: &mut FakeChannels) {
        op(t, ch, "handshake", json!({}));
        ok_i(t, ch, 0, json!({"connectionId": "conn-1"}));
        ok_i(t, ch, 1, json!({}));
        assert!(t.is_handshaken());
    }

    /// 发 `subscribe` 并把监听器 id 回报进去；返回（订阅 op 的 token, 监听器 id）。
    fn drive_subscribe(
        t: &mut ConversationTransport,
        ch: &mut FakeChannels,
        session_id: &str,
        listener_id: u64,
    ) -> (String, u64) {
        let ev = op(t, ch, "subscribe", json!({"sessionId": session_id}));
        let token = token_of(&ev);
        let ltoken = listen_token(&ev);
        t.step(
            ConversationInput::ListenerRegistered {
                token: ltoken,
                id: listener_id,
            },
            ch,
            NOW,
        );
        (token, listener_id)
    }

    /// 完整订阅到 ack（返回订阅 op 的 token；subscribe 调用是第 3 次）。
    fn subscribe_ok(
        t: &mut ConversationTransport,
        ch: &mut FakeChannels,
        session_id: &str,
        listener_id: u64,
        ack: Value,
    ) -> String {
        let (token, _) = drive_subscribe(t, ch, session_id, listener_id);
        let ev = ok_i(t, ch, 2, ack);
        assert!(
            matches!(completed(&ev), Some(Completion::Ok)),
            "订阅应成功: {ev:?}"
        );
        token
    }

    /// 对应 `conversation_test.dart` 里 `snapshot clears and replaces state` 的夹具。
    fn snapshot_frame(subscription_id: &str) -> Value {
        json!({
            "topic": "conversation/s1",
            "kind": "complete",
            "frame": {
                "subscriptionId": subscription_id,
                "toSeq": 10,
                "payload": {
                    "kind": "snapshot",
                    "snapshot": {
                        "logEpoch": "epoch-1",
                        "revision": 5,
                        "rows": {
                            "window": [
                                {"rowId": 1, "kind": "user", "text": "hello"},
                                {"rowId": 2, "kind": "assistant", "text": "hi"}
                            ],
                            "totalCount": 2,
                            "firstRowId": 1
                        }
                    }
                }
            }
        })
    }

    fn fire(
        t: &mut ConversationTransport,
        ch: &mut FakeChannels,
        id: u64,
        frame: Value,
    ) -> Vec<ConversationEvent> {
        t.step(
            ConversationInput::Channel(ChannelEvent::EventFire {
                id,
                data: IpcValue::Object(frame),
            }),
            ch,
            NOW,
        )
    }

    fn envelope_of(ch: &FakeChannels, i: usize) -> Value {
        ch.arg(i)["envelope"].clone()
    }

    // ---------------------------------------------------------- 构造与握手

    #[test]
    fn new_uses_conversation_key_and_generated_client_id() {
        let t = transport();
        assert_eq!(t.key(), "id-1");
        assert!(!t.is_handshaken());
        assert_eq!(t.connection_id(), None);
        let s = t.snapshot();
        assert_eq!(s["key"], "id-1");
        assert_eq!(s["clientId"], "uuid-1");
        assert_eq!(s["appVersion"], "3.6.5");
        assert_eq!(s["handshaken"], false);
        assert_eq!(s["connectionId"], Value::Null);
        assert_eq!(s["bridgeDegraded"], Value::Null);
    }

    /// 生产构造器用 OS CSPRNG 生成 `clientId`（Dart `final String clientId = generateUuid()`）。
    #[test]
    fn new_defaults_app_version_and_generates_real_uuid() {
        let t = ConversationTransport::new(
            scope(),
            crate::conversation::CONVERSATION_PROTOCOL_APP_VERSION,
        );
        let s = t.snapshot();
        assert_eq!(s["appVersion"], "3.6.5");
        let client_id = s["clientId"].as_str().expect("clientId 应是字符串");
        assert_eq!(client_id.len(), 36, "uuid 形如 8-4-4-4-12");
        assert_eq!(client_id.as_bytes()[14], b'4', "版本位应为 4");
    }

    #[test]
    fn handshake_sends_hello_then_initialize() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        let ev = op(&mut t, &mut ch, "handshake", json!({}));
        assert_eq!(token_of(&ev), "handshake:1");
        assert!(completed(&ev).is_none(), "握手未完成前不该有 Completed");

        assert_eq!(ch.count(), 1);
        assert_eq!(ch.channel(0), "zcode-agent");
        assert_eq!(ch.method(0), "helloConversationV4");
        assert_eq!(ch.calls[0].args, Vec::new(), "hello 的参数是空列表");
        assert_eq!(ch.calls[0].timeout_ms, 30_000);

        let ev = ok_i(&mut t, &mut ch, 0, json!({"connectionId": "conn-1"}));
        assert_eq!(logs(&ev), vec!["[v4] hello: {connectionId: conn-1}"]);
        assert_eq!(ch.count(), 2);
        assert_eq!(ch.method(1), "initializeConversationV4");
        assert_eq!(ch.calls[1].timeout_ms, 30_000);
        assert_eq!(
            ch.arg(1),
            json!({
                "kind": "clientHello",
                "protocolVersion": 3,
                "clientId": "uuid-1",
                "clientKind": "mobileApp",
                "appVersion": "3.6.5"
            })
        );

        let ev = ok_i(&mut t, &mut ch, 1, json!({}));
        assert!(ev.contains(&ConversationEvent::HandshakeDone {
            connection_id: Some("conn-1".to_string())
        }));
        assert!(ev.contains(&ConversationEvent::StateChanged));
        assert_eq!(completed(&ev), Some(&Completion::Ok));
        assert!(t.is_handshaken());
        assert_eq!(t.connection_id(), Some("conn-1"));
    }

    /// 对应 `_log('[v4] hello: $hello')` 的 Dart `Map.toString()` 插值。
    #[test]
    fn handshake_hello_log_matches_dart_interpolation() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        op(&mut t, &mut ch, "handshake", json!({}));
        let ev = ok_i(
            &mut t,
            &mut ch,
            0,
            json!({"connectionId": "c-1", "extra": [1, 2], "flag": true}),
        );
        assert_eq!(
            logs(&ev),
            vec!["[v4] hello: {connectionId: c-1, extra: [1, 2], flag: true}"]
        );
    }

    #[test]
    fn handshake_without_connection_id_still_completes() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        op(&mut t, &mut ch, "handshake", json!({}));
        ok_i(&mut t, &mut ch, 0, json!({}));
        let ev = ok_i(&mut t, &mut ch, 1, json!({}));
        assert!(ev.contains(&ConversationEvent::HandshakeDone {
            connection_id: None
        }));
        assert_eq!(t.connection_id(), None);
    }

    #[test]
    fn handshake_is_not_repeated_when_already_handshaken() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        assert_eq!(ch.count(), 2);
        let ev = op(&mut t, &mut ch, "handshake", json!({}));
        assert_eq!(completed(&ev), Some(&Completion::Ok));
        assert_eq!(ch.count(), 2, "已握手不该再发调用");
    }

    #[test]
    fn handshake_failure_fails_gated_ops_and_allows_retry() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        let ev = op(
            &mut t,
            &mut ch,
            "sendText",
            json!({"sessionId": "s1", "text": "hi"}),
        );
        assert_eq!(token_of(&ev), "sendText:1");
        assert_eq!(ch.method(0), "helloConversationV4");

        let ev = err(&mut t, &mut ch, 0, "boom");
        assert_eq!(
            completed(&ev),
            Some(&Completion::Failed(TransportError::channel("boom")))
        );
        assert!(!t.is_handshaken());

        // Dart `.catchError` 把 `_handshakeFuture` 清空 → 下一次调用重新握手
        let ev = op(&mut t, &mut ch, "handshake", json!({}));
        assert!(completed(&ev).is_none());
        assert_eq!(ch.count(), 2);
        assert_eq!(ch.method(1), "helloConversationV4");
    }

    #[test]
    fn concurrent_gated_ops_share_one_handshake() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        let a = op(&mut t, &mut ch, "sendText", json!({"sessionId": "s1", "text": "a"}));
        let b = op(&mut t, &mut ch, "sendText", json!({"sessionId": "s1", "text": "b"}));
        assert_eq!(ch.count(), 1, "两次调用共用一个握手");
        assert_eq!(token_of(&a), "sendText:1");
        assert_eq!(token_of(&b), "sendText:2");
        ok_i(&mut t, &mut ch, 0, json!({}));
        let ev = ok_i(&mut t, &mut ch, 1, json!({}));
        assert_eq!(ch.count(), 4, "握手完成后两条命令各发一次");
        assert_eq!(ch.method(2), "sendConversationCommandV4");
        assert_eq!(ch.method(3), "sendConversationCommandV4");
        assert!(completed(&ev).is_none());
    }

    // ---------------------------------------------------------- 订阅

    #[test]
    fn subscribe_registers_listener_before_sending_subscribe() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);

        let ev = op(&mut t, &mut ch, "subscribe", json!({"sessionId": "s1"}));
        assert_eq!(ch.count(), 2, "监听器注册之前不该发 subscribe");
        let ltoken = listen_token(&ev);
        assert!(ev.iter().any(|e| matches!(
            e,
            ConversationEvent::ListenChannel {
                channel: "zcode-agent",
                event: "onDynamicConversationFrame",
                ..
            }
        )));
        assert_eq!(
            ev[0],
            ConversationEvent::OpAccepted {
                token: "subscribe:2".to_string(),
                op: "subscribe".to_string()
            }
        );
        assert_eq!(timer_start(&ev, 30_000), Some(TimerId(1)), "分片清理周期");

        let ev = t.step(
            ConversationInput::ListenerRegistered { token: ltoken, id: 9 },
            &mut ch,
            NOW,
        );
        assert!(ev.is_empty(), "回报监听器 id 只推进流程");
        assert_eq!(ch.count(), 3);
        assert_eq!(ch.method(2), "subscribeConversationV4");
        assert_eq!(ch.calls[2].timeout_ms, 60_000);
        assert_eq!(
            ch.arg(2),
            json!({"workspacePath": "/w", "workspaceIdentity": "id-1", "sessionId": "s1"})
        );
    }

    #[test]
    fn subscribe_ack_sets_subscription_id_and_log_epoch() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        let token = subscribe_ok(
            &mut t,
            &mut ch,
            "s1",
            9,
            json!({"ack": {"subscriptionId": "sub-1", "logEpoch": "epoch-1"}}),
        );
        let s = t.snapshot();
        let c = &s["conversations"]["s1"];
        assert_eq!(c["subscriptionId"], "sub-1");
        assert_eq!(c["logEpoch"], "epoch-1");
        assert_eq!(c["listenerId"], 9);
        assert_eq!(c["started"], true);
        assert_eq!(c["disposed"], false);
        assert_eq!(token, "subscribe:2");
    }

    /// 订阅 ack 的日志行与 watchdog 的 10 秒周期。
    #[test]
    fn subscribe_ack_logs_topic_and_starts_watchdog() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        drive_subscribe(&mut t, &mut ch, "s1", 9);
        let ev = ok_i(
            &mut t,
            &mut ch,
            2,
            json!({"ack": {"subscriptionId": "sub-1", "logEpoch": "epoch-1"}}),
        );
        assert!(logs(&ev).contains(&"[v4] subscribed conversation/s1 id=sub-1".to_string()));
        assert!(ev.contains(&ConversationEvent::Subscribed {
            session_id: Some("s1".to_string()),
            subscription_id: "sub-1".to_string(),
        }));
        assert_eq!(
            timer_start(&ev, 10_000),
            Some(TimerId(2)),
            "watchdog 是第二个定时器"
        );
    }

    #[test]
    fn subscribe_without_subscription_id_fails_and_disposes() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        let (token, _) = drive_subscribe(&mut t, &mut ch, "s1", 9);
        let ev = ok_i(&mut t, &mut ch, 2, json!({"ack": {}}));

        assert_eq!(
            completed(&ev),
            Some(&Completion::Failed(TransportError::invalid_state(
                "subscribeConversationV4: missing ack.subscriptionId"
            )))
        );
        assert_eq!(completed_token(&ev), token);
        assert!(ev.contains(&ConversationEvent::UnlistenChannel { id: 9 }));
        assert_eq!(timer_start(&ev, 30_000), None, "不该重开分片定时器");
        assert!(ev
            .iter()
            .any(|e| matches!(e, ConversationEvent::TimerCancel(_))));
        assert!(
            t.snapshot()["conversations"].as_object().unwrap().is_empty(),
            "失败的订阅要从 _subscriptions 里摘掉"
        );
    }

    #[test]
    fn subscribe_channel_error_fails_op() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        let (token, _) = drive_subscribe(&mut t, &mut ch, "s1", 9);
        let ev = err(&mut t, &mut ch, 2, "nope");
        assert_eq!(completed_token(&ev), token);
        assert_eq!(
            completed(&ev),
            Some(&Completion::Failed(TransportError::channel("nope")))
        );
    }

    #[test]
    fn sessions_index_subscription_uses_its_own_methods_and_args() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        let ev = op(&mut t, &mut ch, "subscribeSessionsIndex", json!({}));
        let ltoken = listen_token(&ev);
        assert!(ev.iter().any(|e| matches!(
            e,
            ConversationEvent::ListenChannel {
                event: "onDynamicSessionsIndexFrame",
                ..
            }
        )));
        t.step(
            ConversationInput::ListenerRegistered { token: ltoken, id: 5 },
            &mut ch,
            NOW,
        );
        assert_eq!(ch.method(2), "subscribeSessionsIndexV4");
        assert_eq!(
            ch.arg(2),
            json!({
                "workspacePath": "/w",
                "workspaceIdentity": "id-1",
                "runtimePolicy": "existing-only"
            })
        );
        let ev = ok_i(&mut t, &mut ch, 2, json!({"ack": {"subscriptionId": "si-1"}}));
        assert!(ev.contains(&ConversationEvent::Subscribed {
            session_id: None,
            subscription_id: "si-1".to_string(),
        }));
        assert_eq!(timer_start(&ev, 10_000), None, "sessions-index 没有 watchdog");
        assert_eq!(
            t.snapshot()["sessionsIndex"][0]["subscriptionId"],
            "si-1"
        );
    }

    #[test]
    fn sessions_index_frame_emits_index_updated() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        let ev = op(&mut t, &mut ch, "subscribeSessionsIndex", json!({}));
        let ltoken = listen_token(&ev);
        t.step(
            ConversationInput::ListenerRegistered { token: ltoken, id: 5 },
            &mut ch,
            NOW,
        );
        ok_i(&mut t, &mut ch, 2, json!({"ack": {"subscriptionId": "si-1"}}));
        let ev = fire(
            &mut t,
            &mut ch,
            5,
            json!({
                "topic": "sessions-index/id-1",
                "kind": "complete",
                "frame": {
                    "subscriptionId": "si-1",
                    "toSeq": 4,
                    "payload": {
                        "kind": "snapshot",
                        "snapshot": {
                            "workspaceId": "ws-1",
                            "logEpoch": "e-1",
                            "sessions": [
                                {"sessionId": "a", "title": "A", "phase": "idle", "lastActivityAt": 2},
                                {"sessionId": "b", "title": "B", "phase": "idle", "lastActivityAt": 9}
                            ]
                        }
                    }
                }
            }),
        );
        assert!(ev.iter().any(|e| matches!(
            e,
            ConversationEvent::SessionsIndexUpdated {
                session_count: 2,
                ready: true,
                seq: 4,
                ..
            }
        )));
        let s = t.snapshot();
        // `list` 按 lastActivityAt 倒序（b 在前）
        assert_eq!(s["sessionsIndex"][0]["list"][0]["sessionId"], "b");
    }

    // ---------------------------------------------------------- 帧处理

    #[test]
    fn frames_before_ack_are_staged_then_applied() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        drive_subscribe(&mut t, &mut ch, "s1", 9);
        let ev = fire(&mut t, &mut ch, 9, snapshot_frame("sub-1"));
        assert!(ev.is_empty(), "订阅 id 未知时先暂存，不应用");
        assert_eq!(t.snapshot()["conversations"]["s1"]["stagedFrameCount"], 1);

        let ev = ok_i(&mut t, &mut ch, 2, json!({"ack": {"subscriptionId": "sub-1"}}));
        assert!(ev
            .iter()
            .any(|e| matches!(e, ConversationEvent::RowsUpdated { .. })));
        let s = t.snapshot();
        let c = &s["conversations"]["s1"];
        assert_eq!(c["ready"], true);
        assert_eq!(c["seq"], 10);
        assert_eq!(c["rows"].as_array().unwrap().len(), 2);
        assert_eq!(c["stagedFrameCount"], 0);
    }

    #[test]
    fn wire_frame_with_other_topic_is_ignored() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        subscribe_ok(
            &mut t,
            &mut ch,
            "s1",
            9,
            json!({"ack": {"subscriptionId": "sub-1"}}),
        );
        let mut frame = snapshot_frame("sub-1");
        frame["topic"] = json!("conversation/other");
        let ev = fire(&mut t, &mut ch, 9, frame);
        assert!(ev.is_empty());
        assert_eq!(t.snapshot()["conversations"]["s1"]["seq"], 0);
    }

    #[test]
    fn frame_with_wrong_subscription_id_is_ignored() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        subscribe_ok(
            &mut t,
            &mut ch,
            "s1",
            9,
            json!({"ack": {"subscriptionId": "sub-1"}}),
        );
        let ev = fire(&mut t, &mut ch, 9, snapshot_frame("other-sub"));
        assert!(ev.is_empty());
        assert_eq!(t.snapshot()["conversations"]["s1"]["seq"], 0);
    }

    #[test]
    fn snapshot_frame_emits_rows_updated_with_dart_fixture_values() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        subscribe_ok(
            &mut t,
            &mut ch,
            "s1",
            9,
            json!({"ack": {"subscriptionId": "sub-1"}}),
        );
        let ev = fire(&mut t, &mut ch, 9, snapshot_frame("sub-1"));
        let updated = ev
            .iter()
            .find_map(|e| match e {
                ConversationEvent::RowsUpdated {
                    session_id,
                    ready,
                    seq,
                    total_count,
                    first_row_id,
                    row_count,
                    frame,
                } => Some((
                    session_id.clone(),
                    *ready,
                    *seq,
                    *total_count,
                    *first_row_id,
                    *row_count,
                    frame.clone(),
                )),
                _ => None,
            })
            .expect("应有 RowsUpdated");
        assert_eq!(updated.0, "s1");
        assert!(updated.1);
        assert_eq!(updated.2, 10);
        assert_eq!(updated.3, 2);
        assert_eq!(updated.4, Some(1));
        assert_eq!(updated.5, 2);
        assert_eq!(updated.6.expect("带帧")["subscriptionId"], "sub-1");
        assert_eq!(t.snapshot()["conversations"]["s1"]["revision"], 5);
    }

    #[test]
    fn fragment_frames_are_reassembled_then_applied() {
        // Python: base64 的独立计算结果
        //   `{"subscriptionId":"sub-1","payload":{"kind":"deltas","deltas":[]},"fromSeq":5,"toSeq":5}`
        //   前 44 字节 / 后 44 字节
        let part0 = "eyJzdWJzY3JpcHRpb25JZCI6InN1Yi0xIiwicGF5bG9hZCI6eyJraW5kIjo=";
        let part1 = "ImRlbHRhcyIsImRlbHRhcyI6W119LCJmcm9tU2VxIjo1LCJ0b1NlcSI6NX0=";
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        subscribe_ok(
            &mut t,
            &mut ch,
            "s1",
            9,
            json!({"ack": {"subscriptionId": "sub-1"}}),
        );
        // 先建立 seq=5 的状态，好让分片里的 deltas 不触发 gap
        let mut snap = snapshot_frame("sub-1");
        snap["frame"]["toSeq"] = json!(5);
        fire(&mut t, &mut ch, 9, snap);

        let fragment = |index: i64, data: &str| {
            json!({
                "topic": "conversation/s1",
                "kind": "fragment",
                "logicalFrameId": "lf-1",
                "fragmentIndex": index,
                "fragmentCount": 2,
                "dataBase64": data
            })
        };
        let ev = fire(&mut t, &mut ch, 9, fragment(1, part1));
        assert!(ev.is_empty(), "还缺第 0 片");
        let ev = fire(&mut t, &mut ch, 9, fragment(0, part0));
        assert!(
            ev.iter()
                .any(|e| matches!(e, ConversationEvent::RowsUpdated { .. })),
            "凑齐后应应用逻辑帧: {ev:?}"
        );
        assert_eq!(t.snapshot()["conversations"]["s1"]["pendingFragmentCount"], 0);
    }

    #[test]
    fn fragment_count_mismatch_drops_assembly() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        subscribe_ok(
            &mut t,
            &mut ch,
            "s1",
            9,
            json!({"ack": {"subscriptionId": "sub-1"}}),
        );
        let ev = fire(
            &mut t,
            &mut ch,
            9,
            json!({
                "topic": "conversation/s1",
                "kind": "fragment",
                "logicalFrameId": "lf-1",
                "fragmentIndex": 0,
                "fragmentCount": 2,
                "dataBase64": "AA=="
            }),
        );
        assert!(ev.is_empty());
        assert_eq!(
            t.snapshot()["conversations"]["s1"]["pendingFragmentCount"],
            1
        );
        // `fragmentCount` 不一致 → `_fragments.remove(id)`
        fire(
            &mut t,
            &mut ch,
            9,
            json!({
                "topic": "conversation/s1",
                "kind": "fragment",
                "logicalFrameId": "lf-1",
                "fragmentIndex": 0,
                "fragmentCount": 3,
                "dataBase64": "AA=="
            }),
        );
        assert_eq!(
            t.snapshot()["conversations"]["s1"]["pendingFragmentCount"],
            0
        );
    }

    #[test]
    fn fragment_out_of_range_count_is_dropped() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        subscribe_ok(
            &mut t,
            &mut ch,
            "s1",
            9,
            json!({"ack": {"subscriptionId": "sub-1"}}),
        );
        // `count > 64` → 直接丢
        fire(
            &mut t,
            &mut ch,
            9,
            json!({
                "topic": "conversation/s1",
                "kind": "fragment",
                "logicalFrameId": "lf-1",
                "fragmentIndex": 0,
                "fragmentCount": 65,
                "dataBase64": "AA=="
            }),
        );
        assert_eq!(
            t.snapshot()["conversations"]["s1"]["pendingFragmentCount"],
            0
        );
    }

    #[test]
    fn non_map_event_payload_is_ignored() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        subscribe_ok(
            &mut t,
            &mut ch,
            "s1",
            9,
            json!({"ack": {"subscriptionId": "sub-1"}}),
        );
        let ev = t.step(
            ConversationInput::Channel(ChannelEvent::EventFire {
                id: 9,
                data: IpcValue::from("not a map"),
            }),
            &mut ch,
            NOW,
        );
        assert!(ev.is_empty());
    }

    // ---------------------------------------------------------- resync

    #[test]
    fn delta_gap_triggers_resync_with_base() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        subscribe_ok(
            &mut t,
            &mut ch,
            "s1",
            9,
            json!({"ack": {"subscriptionId": "sub-1"}}),
        );
        fire(&mut t, &mut ch, 9, snapshot_frame("sub-1"));

        let ev = fire(
            &mut t,
            &mut ch,
            9,
            json!({
                "topic": "conversation/s1",
                "kind": "complete",
                "frame": {
                    "subscriptionId": "sub-1",
                    "fromSeq": 8,
                    "toSeq": 12,
                    "payload": {"kind": "deltas", "deltas": []}
                }
            }),
        );
        assert!(logs(&ev)
            .contains(&"[v4] resync (gap detected) seq=10 logEpoch=epoch-1".to_string()));
        assert_eq!(ch.method(3), "resyncConversationV4");
        assert_eq!(
            ch.arg(3),
            json!({
                "workspacePath": "/w",
                "workspaceIdentity": "id-1",
                "subscriptionId": "sub-1",
                "forceSnapshot": true,
                "base": {"logEpoch": "epoch-1", "seq": 10}
            })
        );
        assert!(!ev
            .iter()
            .any(|e| matches!(e, ConversationEvent::RowsUpdated { .. })));
        assert_eq!(t.snapshot()["conversations"]["s1"]["resyncing"], true);
    }

    #[test]
    fn resync_is_skipped_while_resyncing_and_rearmed_after_response() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        subscribe_ok(
            &mut t,
            &mut ch,
            "s1",
            9,
            json!({"ack": {"subscriptionId": "sub-1"}}),
        );
        fire(&mut t, &mut ch, 9, snapshot_frame("sub-1"));
        let gap_frame = || {
            json!({
                "topic": "conversation/s1",
                "kind": "complete",
                "frame": {
                    "subscriptionId": "sub-1",
                    "fromSeq": 8,
                    "toSeq": 12,
                    "payload": {"kind": "deltas", "deltas": []}
                }
            })
        };
        fire(&mut t, &mut ch, 9, gap_frame());
        assert_eq!(ch.count(), 4);
        fire(&mut t, &mut ch, 9, gap_frame());
        assert_eq!(ch.count(), 4, "`_resyncing` 为真时不再发");
        ok_i(&mut t, &mut ch, 3, json!({}));
        assert_eq!(t.snapshot()["conversations"]["s1"]["resyncing"], false);
        fire(&mut t, &mut ch, 9, gap_frame());
        assert_eq!(ch.count(), 5, "清掉 resyncing 后可以再发");
    }

    #[test]
    fn resync_failure_is_logged_and_clears_flag() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        subscribe_ok(
            &mut t,
            &mut ch,
            "s1",
            9,
            json!({"ack": {"subscriptionId": "sub-1"}}),
        );
        fire(&mut t, &mut ch, 9, snapshot_frame("sub-1"));
        fire(
            &mut t,
            &mut ch,
            9,
            json!({
                "topic": "conversation/s1",
                "kind": "complete",
                "frame": {
                    "subscriptionId": "sub-1",
                    "fromSeq": 8,
                    "toSeq": 12,
                    "payload": {"kind": "deltas", "deltas": []}
                }
            }),
        );
        let ev = err(&mut t, &mut ch, 3, "nope");
        assert_eq!(
            logs(&ev),
            vec!["[v4] resync failed: ChannelRpcError: nope"]
        );
        assert_eq!(t.snapshot()["conversations"]["s1"]["resyncing"], false);
    }

    // ---------------------------------------------------------- watchdog / 分片清理

    #[test]
    fn watchdog_resyncs_when_quiet_and_running() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        drive_subscribe(&mut t, &mut ch, "s1", 9);
        let ev = ok_i(&mut t, &mut ch, 2, json!({"ack": {"subscriptionId": "sub-1"}}));
        let watchdog = timer_start(&ev, 10_000).expect("watchdog token");

        let mut frame = snapshot_frame("sub-1");
        frame["frame"]["payload"]["snapshot"]["control"] = json!({"phase": "running"});
        fire(&mut t, &mut ch, 9, frame);
        assert_eq!(t.snapshot()["conversations"]["s1"]["isRunning"], true);

        let ev = t.step(
            ConversationInput::TimerFired(watchdog),
            &mut ch,
            NOW + 25_000,
        );
        assert!(logs(&ev)
            .contains(&"[v4] watchdog: no frames for 25s while active, resync".to_string()));
        assert_eq!(ch.method(3), "resyncConversationV4");
        assert_eq!(timer_start(&ev, 10_000), Some(watchdog), "周期定时器重新装弹");
    }

    #[test]
    fn watchdog_does_not_resync_when_quiet_but_idle() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        drive_subscribe(&mut t, &mut ch, "s1", 9);
        let ev = ok_i(&mut t, &mut ch, 2, json!({"ack": {"subscriptionId": "sub-1"}}));
        let watchdog = timer_start(&ev, 10_000).unwrap();
        let ev = t.step(ConversationInput::TimerFired(watchdog), &mut ch, NOW + 60_000);
        assert!(logs(&ev).is_empty());
        assert_eq!(ch.count(), 3, "没有 resync 调用");
        assert_eq!(timer_start(&ev, 10_000), Some(watchdog));
    }

    #[test]
    fn watchdog_does_not_resync_before_quiet_threshold() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        drive_subscribe(&mut t, &mut ch, "s1", 9);
        let ev = ok_i(&mut t, &mut ch, 2, json!({"ack": {"subscriptionId": "sub-1"}}));
        let watchdog = timer_start(&ev, 10_000).unwrap();
        let mut frame = snapshot_frame("sub-1");
        frame["frame"]["payload"]["snapshot"]["control"] = json!({"phase": "running"});
        fire(&mut t, &mut ch, 9, frame);
        // 19 秒 < 20 → 不 resync
        let ev = t.step(ConversationInput::TimerFired(watchdog), &mut ch, NOW + 19_000);
        assert!(logs(&ev).is_empty());
        assert_eq!(ch.count(), 3);
    }

    #[test]
    fn watchdog_resyncs_when_a_row_is_streaming() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        drive_subscribe(&mut t, &mut ch, "s1", 9);
        let ev = ok_i(&mut t, &mut ch, 2, json!({"ack": {"subscriptionId": "sub-1"}}));
        let watchdog = timer_start(&ev, 10_000).unwrap();
        let mut frame = snapshot_frame("sub-1");
        frame["frame"]["payload"]["snapshot"]["rows"]["window"] = json!([
            {"rowId": 1, "kind": "assistantText", "state": "streaming", "text": "..."}
        ]);
        fire(&mut t, &mut ch, 9, frame);
        assert_eq!(
            t.snapshot()["conversations"]["s1"]["hasStreamingRow"],
            true
        );
        let ev = t.step(ConversationInput::TimerFired(watchdog), &mut ch, NOW + 30_000);
        assert_eq!(ch.method(3), "resyncConversationV4");
        assert!(!logs(&ev).is_empty());
    }

    #[test]
    fn fragment_cleanup_purges_stale_fragments() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        drive_subscribe(&mut t, &mut ch, "s1", 9);
        ok_i(&mut t, &mut ch, 2, json!({"ack": {"subscriptionId": "sub-1"}}));
        // 分片清理定时器是在建订阅对象时（`subscribe` op 里）起的，不是 ack 里
        let cleanup = TimerId(1);
        fire(
            &mut t,
            &mut ch,
            9,
            json!({
                "topic": "conversation/s1",
                "kind": "fragment",
                "logicalFrameId": "lf-x",
                "fragmentIndex": 0,
                "fragmentCount": 2,
                "dataBase64": "AA=="
            }),
        );
        assert_eq!(
            t.snapshot()["conversations"]["s1"]["pendingFragmentCount"],
            1
        );
        // 61 秒后过期（Dart `inSeconds > 60`）
        let ev = t.step(ConversationInput::TimerFired(cleanup), &mut ch, NOW + 61_000);
        assert_eq!(logs(&ev), vec!["[v4] purged stale fragment lf-x"]);
        assert_eq!(
            t.snapshot()["conversations"]["s1"]["pendingFragmentCount"],
            0
        );
        assert_eq!(timer_start(&ev, 30_000), Some(cleanup));
    }

    #[test]
    fn fragment_cleanup_keeps_fresh_fragments() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        drive_subscribe(&mut t, &mut ch, "s1", 9);
        ok_i(&mut t, &mut ch, 2, json!({"ack": {"subscriptionId": "sub-1"}}));
        // 分片清理定时器是在建订阅对象时（`subscribe` op 里）起的，不是 ack 里
        let cleanup = TimerId(1);
        fire(
            &mut t,
            &mut ch,
            9,
            json!({
                "topic": "conversation/s1",
                "kind": "fragment",
                "logicalFrameId": "lf-y",
                "fragmentIndex": 0,
                "fragmentCount": 2,
                "dataBase64": "AA=="
            }),
        );
        let ev = t.step(ConversationInput::TimerFired(cleanup), &mut ch, NOW + 30_000);
        assert!(logs(&ev).is_empty());
        assert_eq!(
            t.snapshot()["conversations"]["s1"]["pendingFragmentCount"],
            1
        );
    }

    // ---------------------------------------------------------- 桥恢复

    #[test]
    fn bridge_recovered_rehandshakes_and_resubscribes() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        subscribe_ok(
            &mut t,
            &mut ch,
            "s1",
            9,
            json!({"ack": {"subscriptionId": "sub-1"}}),
        );

        let ev = t.step(ConversationInput::BridgeRecovered, &mut ch, NOW);
        assert_eq!(
            logs(&ev),
            vec!["[v4] bridge recovered, resubscribing conversation/s1"]
        );
        assert!(!t.is_handshaken());
        assert_eq!(t.connection_id(), None);
        assert_eq!(ch.count(), 4);
        assert_eq!(ch.method(3), "helloConversationV4", "桥恢复要重做握手");

        ok_i(&mut t, &mut ch, 3, json!({"connectionId": "conn-2"}));
        let ev = ok_i(&mut t, &mut ch, 4, json!({}));
        // 旧监听器先取消，再退订旧订阅
        assert!(ev.contains(&ConversationEvent::UnlistenChannel { id: 9 }));
        assert_eq!(ch.method(5), "unsubscribeConversationV4");
        assert_eq!(
            ch.arg(5),
            json!({
                "workspacePath": "/w",
                "workspaceIdentity": "id-1",
                "subscriptionId": "sub-1"
            })
        );

        // 退订应答 → 重新注册监听器
        let ev = ok_i(&mut t, &mut ch, 5, json!({}));
        let ltoken = listen_token(&ev);
        t.step(
            ConversationInput::ListenerRegistered {
                token: ltoken,
                id: 11,
            },
            &mut ch,
            NOW,
        );
        assert_eq!(ch.method(6), "subscribeConversationV4");
        let ev = ok_i(&mut t, &mut ch, 6, json!({"ack": {"subscriptionId": "sub-2"}}));
        assert!(ev.contains(&ConversationEvent::Subscribed {
            session_id: Some("s1".to_string()),
            subscription_id: "sub-2".to_string(),
        }));
        let s = t.snapshot();
        assert_eq!(s["conversations"]["s1"]["subscriptionId"], "sub-2");
        assert_eq!(s["conversations"]["s1"]["listenerId"], 11);
        assert_eq!(s["conversations"]["s1"]["seq"], 0, "重订阅后状态从头开始");
    }

    #[test]
    fn resubscribe_failure_arms_retry_timer() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        subscribe_ok(
            &mut t,
            &mut ch,
            "s1",
            9,
            json!({"ack": {"subscriptionId": "sub-1"}}),
        );

        t.step(ConversationInput::BridgeRecovered, &mut ch, NOW);
        ok_i(&mut t, &mut ch, 3, json!({}));
        ok_i(&mut t, &mut ch, 4, json!({}));
        let ev = ok_i(&mut t, &mut ch, 5, json!({})); // unsubscribe 应答 → 注册新监听器
        let ltoken = listen_token(&ev);
        t.step(
            ConversationInput::ListenerRegistered {
                token: ltoken,
                id: 11,
            },
            &mut ch,
            NOW,
        );
        let ev = err(&mut t, &mut ch, 6, "boom");
        assert_eq!(
            logs(&ev),
            vec!["[v4] resubscribe failed: ChannelRpcError: boom"]
        );
        assert!(timer_start(&ev, 3_000).is_some(), "3 秒后重试");
        assert!(completed(&ev).is_none(), "重订阅是内部流程，不发 Completed");
        assert_eq!(
            t.snapshot()["conversations"]["s1"]["subscriptionId"],
            Value::Null
        );
    }

    // ---------------------------------------------------------- 命令

    #[test]
    fn send_text_envelope_has_no_cas_fields() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        let ev = op(
            &mut t,
            &mut ch,
            "sendText",
            json!({
                "sessionId": "s1",
                "text": "hi",
                "attachments": [{"ref": "a"}],
                "expectedHeldQueueItemIds": ["q1"],
                "toolDisallowlist": ["Bash"]
            }),
        );
        assert_eq!(logs(&ev), vec!["[v4] command sendText"]);
        assert_eq!(ch.count(), 3);
        assert_eq!(ch.method(2), "sendConversationCommandV4");
        assert_eq!(ch.calls[2].timeout_ms, 30_000);
        assert_eq!(
            ch.arg(2),
            json!({
                "workspacePath": "/w",
                "workspaceIdentity": "id-1",
                "envelope": {
                    "commandId": "uuid-2",
                    "clientId": "uuid-1",
                    "sessionId": "s1",
                    "type": "sendText",
                    "payload": {
                        "text": "hi",
                        "attachments": [{"ref": "a"}],
                        "expectedHeldQueueItemIds": ["q1"],
                        "toolDisallowlist": ["Bash"]
                    },
                    "issuedAt": NOW
                }
            })
        );
    }

    #[test]
    fn create_session_uses_null_session_id_and_90s_timeout() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        op(
            &mut t,
            &mut ch,
            "createSession",
            json!({"workspaceId": "ws-1", "firstText": "hello"}),
        );
        assert_eq!(ch.calls[2].timeout_ms, 90_000);
        let env = envelope_of(&ch, 2);
        assert_eq!(env["sessionId"], Value::Null);
        assert_eq!(env["type"], "createSession");
        assert_eq!(
            env["payload"],
            json!({"workspaceId": "ws-1", "firstInput": {"text": "hello"}})
        );
    }

    #[test]
    fn create_session_returns_session_id() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        let ev = op(
            &mut t,
            &mut ch,
            "createSession",
            json!({"workspaceId": "ws-1", "firstText": "hello"}),
        );
        assert_eq!(token_of(&ev), "createSession:2");
        let ev = ok_i(
            &mut t,
            &mut ch,
            2,
            json!({"status": "accepted", "result": {"sessionId": "new-1"}}),
        );
        assert_eq!(
            completed(&ev),
            Some(&Completion::SessionId("new-1".to_string()))
        );
        assert_eq!(completed_token(&ev), "createSession:2");
    }

    /// Dart: `'createSession rejected: ${reasonCode ?? status} ${message ?? ''}'`
    #[test]
    fn create_session_rejected_matches_dart_message() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        op(
            &mut t,
            &mut ch,
            "createSession",
            json!({"workspaceId": "ws-1"}),
        );
        let ev = ok_i(
            &mut t,
            &mut ch,
            2,
            json!({"status": "rejected", "reasonCode": "noWorkspace", "message": "nope"}),
        );
        assert_eq!(
            completed(&ev),
            Some(&Completion::Failed(TransportError::invalid_state(
                "createSession rejected: noWorkspace nope"
            )))
        );
    }

    #[test]
    fn create_selection_side_session_accepts_duplicate() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        op(
            &mut t,
            &mut ch,
            "createSelectionSideSession",
            json!({"parentSessionId": "p-1"}),
        );
        assert_eq!(ch.calls[2].timeout_ms, 60_000);
        let env = envelope_of(&ch, 2);
        assert_eq!(env["sessionId"], "p-1");
        assert_eq!(env["payload"], json!({}));
        let ev = ok_i(
            &mut t,
            &mut ch,
            2,
            json!({"status": "duplicate", "result": {"sessionId": "side-1"}}),
        );
        assert_eq!(
            completed(&ev),
            Some(&Completion::SessionId("side-1".to_string()))
        );
    }

    /// `pauseGoal` 在 `_casCommands` 里 → 带上订阅的 revision。
    #[test]
    fn cas_command_uses_subscription_revision() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        subscribe_ok(
            &mut t,
            &mut ch,
            "s1",
            9,
            json!({"ack": {"subscriptionId": "sub-1"}}),
        );
        fire(&mut t, &mut ch, 9, snapshot_frame("sub-1")); // revision = 5

        op(&mut t, &mut ch, "pauseGoal", json!({"sessionId": "s1"}));
        let env = envelope_of(&ch, 3);
        assert_eq!(env["type"], "pauseGoal");
        assert_eq!(env["baseRevision"], 5);
        assert_eq!(env["payload"], json!({}));
        assert!(env.get("baseLogEpoch").is_none(), "pauseGoal 不是行目标命令");
    }

    /// `retryTurn` 同时在 `_casCommands` 与 `_rowTargetCommands` 里 → 带 `baseLogEpoch`。
    #[test]
    fn row_target_command_includes_base_log_epoch() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        subscribe_ok(
            &mut t,
            &mut ch,
            "s1",
            9,
            json!({"ack": {"subscriptionId": "sub-1", "logEpoch": "epoch-1"}}),
        );
        op(
            &mut t,
            &mut ch,
            "retryTurn",
            json!({"sessionId": "s1", "target": {"rowId": 7}}),
        );
        let env = envelope_of(&ch, 3);
        assert_eq!(env["baseLogEpoch"], "epoch-1");
        assert_eq!(env["baseRevision"], 0);
        assert_eq!(env["payload"], json!({"target": {"rowId": 7}}));
    }

    /// 非 CAS 命令（`sendGoalCommand`）不带 `baseRevision`。
    #[test]
    fn non_cas_command_omits_base_revision() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        op(
            &mut t,
            &mut ch,
            "sendGoalCommand",
            json!({"sessionId": "s1", "text": "go", "displayText": "G"}),
        );
        let env = envelope_of(&ch, 2);
        assert!(env.get("baseRevision").is_none());
        assert_eq!(env["payload"], json!({"text": "go", "displayText": "G"}));
    }

    #[test]
    fn stale_response_retries_with_server_revision() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        subscribe_ok(
            &mut t,
            &mut ch,
            "s1",
            9,
            json!({"ack": {"subscriptionId": "sub-1"}}),
        );
        fire(&mut t, &mut ch, 9, snapshot_frame("sub-1"));
        op(&mut t, &mut ch, "pauseGoal", json!({"sessionId": "s1"}));
        let first_id = envelope_of(&ch, 3)["commandId"].clone();

        let ev = ok_i(
            &mut t,
            &mut ch,
            3,
            json!({"status": "stale", "revisionAtDecision": 9}),
        );
        assert_eq!(
            logs(&ev),
            vec!["[v4] command pauseGoal stale, retry at rev 9"]
        );
        assert!(completed(&ev).is_none(), "重试还没结束");
        assert_eq!(ch.method(4), "sendConversationCommandV4");
        let retry = envelope_of(&ch, 4);
        assert_eq!(retry["baseRevision"], 9);
        assert_eq!(retry["type"], "pauseGoal");
        assert_ne!(retry["commandId"], first_id, "重试要换 commandId");

        let ev = ok_i(
            &mut t,
            &mut ch,
            4,
            json!({"status": "accepted", "revisionAtDecision": 9}),
        );
        assert_eq!(
            completed(&ev),
            Some(&Completion::Value(
                json!({"status": "accepted", "revisionAtDecision": 9})
            ))
        );
        assert_eq!(t.snapshot()["ackedRevisions"]["s1"], 10);
    }

    #[test]
    fn accepted_ack_bumps_revision_floor_for_next_cas_command() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        subscribe_ok(
            &mut t,
            &mut ch,
            "s1",
            9,
            json!({"ack": {"subscriptionId": "sub-1"}}),
        );
        op(&mut t, &mut ch, "pauseGoal", json!({"sessionId": "s1"}));
        ok_i(
            &mut t,
            &mut ch,
            3,
            json!({"status": "accepted", "revisionAtDecision": 5}),
        );
        op(&mut t, &mut ch, "resumeGoal", json!({"sessionId": "s1"}));
        let env = envelope_of(&ch, 4);
        assert_eq!(env["baseRevision"], 6, "accepted 让下界 +1");
    }

    #[test]
    fn command_timeout_while_healthy_fails_without_retry() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        op(
            &mut t,
            &mut ch,
            "sendText",
            json!({"sessionId": "s1", "text": "hi"}),
        );
        let ev = timeout(&mut t, &mut ch, 2);
        assert_eq!(
            completed(&ev),
            Some(&Completion::Failed(TransportError::timeout(
                "zcode-agent.sendConversationCommandV4 timed out"
            )))
        );
        assert_eq!(ch.count(), 3, "健康桥不重发（避免重复投递）");
    }

    #[test]
    fn command_timeout_while_degraded_waits_healthy_then_retries() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        op(
            &mut t,
            &mut ch,
            "sendText",
            json!({"sessionId": "s1", "text": "hi"}),
        );
        let first_id = envelope_of(&ch, 2)["commandId"].clone();
        // 命令在飞的时候桥掉了（Dart 的 `_sendCommandWithRetry` 在超时才看 degraded）
        t.step(
            ConversationInput::BridgeDegraded {
                degraded: Some("rpc-transport-fault"),
            },
            &mut ch,
            NOW,
        );

        let ev = timeout(&mut t, &mut ch, 2);
        assert_eq!(
            logs(&ev),
            vec!["[v4] command timed out during drop, waiting for recovery and retrying"]
        );
        assert!(ev.contains(&ConversationEvent::WaitHealthy {
            token: 1,
            timeout_ms: 45_000
        }));
        assert!(completed(&ev).is_none());

        let ev = t.step(ConversationInput::BridgeHealthy { token: 1 }, &mut ch, NOW);
        assert_eq!(ch.count(), 4);
        let retry = envelope_of(&ch, 3);
        assert_ne!(retry["commandId"], first_id);
        assert_eq!(retry["type"], "sendText");
        assert_eq!(retry["payload"], json!({"text": "hi"}));
        assert!(completed(&ev).is_none());

        let ev = ok_i(&mut t, &mut ch, 3, json!({"status": "accepted"}));
        assert!(matches!(completed(&ev), Some(Completion::Value(_))));
    }

    #[test]
    fn degraded_bridge_gates_command_before_sending() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        t.step(
            ConversationInput::BridgeDegraded {
                degraded: Some("fault"),
            },
            &mut ch,
            NOW,
        );
        op(
            &mut t,
            &mut ch,
            "sendText",
            json!({"sessionId": "s1", "text": "hi"}),
        );
        assert_eq!(ch.count(), 2, "降级中命令先等桥健康，不发出去");
        let ev = t.step(
            ConversationInput::BridgeHealthyTimeout { token: 1 },
            &mut ch,
            NOW,
        );
        assert_eq!(
            completed(&ev),
            Some(&Completion::Failed(TransportError::timeout(
                "bridge 恢复超时: fault"
            )))
        );
    }

    #[test]
    fn command_channel_error_fails_the_op() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        op(
            &mut t,
            &mut ch,
            "sendText",
            json!({"sessionId": "s1", "text": "hi"}),
        );
        let ev = err(&mut t, &mut ch, 2, "rejected");
        assert_eq!(
            completed(&ev),
            Some(&Completion::Failed(TransportError::channel("rejected")))
        );
    }

    #[test]
    fn switch_model_config_falls_back_to_other_family_thought() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        op(
            &mut t,
            &mut ch,
            "switchModelConfig",
            json!({"sessionId": "s1", "provider": "glm", "model": "m", "thought": "enabled"}),
        );
        let first_id = envelope_of(&ch, 2)["commandId"].clone();
        assert_eq!(first_id, "uuid-2");
        let ev = ok_i(
            &mut t,
            &mut ch,
            2,
            json!({"status": "rejected", "message": "Unsupported reasoning effort: x"}),
        );
        // 第一次 `[v4] command switchModelConfig` 在 op 那一批里（见下），这里只剩两条：
        // 兜底日志 + 第二次 `sendCommand` 的 command 日志（Dart 的顺序）。
        assert_eq!(
            logs(&ev),
            vec![
                "[v4] switchModelConfig retry with thought=max".to_string(),
                "[v4] command switchModelConfig".to_string()
            ]
        );
        assert_eq!(ch.method(3), "sendConversationCommandV4");
        let retry = envelope_of(&ch, 3);
        assert_eq!(retry["payload"]["thought"], "max");
        assert_ne!(retry["commandId"], first_id);
        let ev = ok_i(
            &mut t,
            &mut ch,
            3,
            json!({"status": "rejected", "message": "still bad"}),
        );
        assert_eq!(
            completed(&ev),
            Some(&Completion::Value(
                json!({"status": "rejected", "message": "still bad"})
            ))
        );
    }

    #[test]
    fn set_assistant_feedback_writes_null_feedback_explicitly() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        op(
            &mut t,
            &mut ch,
            "setAssistantFeedback",
            json!({"sessionId": "s1", "target": {"rowId": 3}}),
        );
        let env = envelope_of(&ch, 2);
        assert_eq!(env["type"], "setAssistantFeedback");
        assert_eq!(env["payload"]["feedback"], Value::Null);
        assert_eq!(env["payload"]["target"], json!({"rowId": 3}));
        assert_eq!(env["baseRevision"], 0);
    }

    #[test]
    fn resolve_interaction_builds_answer_object() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        op(
            &mut t,
            &mut ch,
            "resolveInteraction",
            json!({
                "sessionId": "s1",
                "interactionId": "i-1",
                "optionId": "o-1",
                "content": {"a": 1}
            }),
        );
        assert_eq!(
            envelope_of(&ch, 2)["payload"],
            json!({"interactionId": "i-1", "answer": {"optionId": "o-1", "content": {"a": 1}}})
        );
    }

    #[test]
    fn generic_send_command_op_passes_payload_through() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        op(
            &mut t,
            &mut ch,
            "sendCommand",
            json!({"sessionId": "s1", "type": "reorderQueueItem", "payload": {"from": 1, "to": 2}}),
        );
        let env = envelope_of(&ch, 2);
        assert_eq!(env["type"], "reorderQueueItem");
        // `reorderQueueItem` 在 `_casCommands` 里 → 带 baseRevision
        assert_eq!(env["baseRevision"], 0);
        assert_eq!(env["payload"], json!({"from": 1, "to": 2}));
    }

    // ---------------------------------------------------------- 查询类

    #[test]
    fn plans_args_merge_scope() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        let ev = op(&mut t, &mut ch, "plans", json!({"sessionId": "s1"}));
        assert_eq!(ch.method(2), "conversationPlansV4");
        assert_eq!(
            ch.arg(2),
            json!({"workspacePath": "/w", "workspaceIdentity": "id-1", "sessionId": "s1"})
        );
        assert_eq!(token_of(&ev), "plans:2");
        let ev2 = ok_i(&mut t, &mut ch, 2, json!({"plans": [{"id": "p"}]}));
        assert_eq!(
            completed(&ev2),
            Some(&Completion::Value(json!({"plans": [{"id": "p"}]})))
        );
        assert_eq!(
            ev2.iter().find_map(|e| match e {
                ConversationEvent::Completed { token, .. } => Some(token.as_str()),
                _ => None,
            }),
            Some("plans:2")
        );
    }

    #[test]
    fn rows_range_defaults_to_limit_60() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        op(&mut t, &mut ch, "rowsRange", json!({"sessionId": "s1"}));
        assert_eq!(ch.method(2), "conversationRowsRangeV4");
        assert_eq!(
            ch.arg(2),
            json!({"workspacePath": "/w", "workspaceIdentity": "id-1", "sessionId": "s1", "limit": 60})
        );
        op(
            &mut t,
            &mut ch,
            "rowsRange",
            json!({"sessionId": "s1", "beforeRowId": 7, "limit": 10}),
        );
        assert_eq!(
            ch.arg(3),
            json!({
                "workspacePath": "/w",
                "workspaceIdentity": "id-1",
                "sessionId": "s1",
                "beforeRowId": 7,
                "limit": 10
            })
        );
    }

    #[test]
    fn file_changes_carries_optional_base_fields() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        op(
            &mut t,
            &mut ch,
            "fileChanges",
            json!({"sessionId": "s1", "target": {"rowId": 2}, "baseRevision": 4, "baseLogEpoch": "e"}),
        );
        assert_eq!(ch.method(2), "conversationFileChangesV4");
        assert_eq!(
            ch.arg(2),
            json!({
                "workspacePath": "/w",
                "workspaceIdentity": "id-1",
                "sessionId": "s1",
                "target": {"rowId": 2},
                "baseRevision": 4,
                "baseLogEpoch": "e"
            })
        );
        op(
            &mut t,
            &mut ch,
            "fileRewindPreview",
            json!({"sessionId": "s1", "target": {"rowId": 2}}),
        );
        assert_eq!(ch.method(3), "conversationFileRewindPreviewV4");
        assert!(ch.arg(3).get("baseRevision").is_none());
    }

    #[test]
    fn skills_sends_workspace_and_provider_then_filters_empty_names() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        let ev = op(&mut t, &mut ch, "skills", json!({}));
        assert_eq!(token_of(&ev), "skills:1");
        assert_eq!(ch.channel(0), "skills");
        assert_eq!(ch.method(0), "list");
        assert_eq!(ch.calls[0].timeout_ms, 20_000);
        assert_eq!(
            ch.arg(0),
            json!({"workspacePath": "/w", "workspaceIdentity": "id-1", "provider": "glm"})
        );
        let ev = ok_i(
            &mut t,
            &mut ch,
            0,
            json!([
                {
                    "id": "a",
                    "name": "n",
                    "path": "/p",
                    "scope": "user",
                    "description": "d",
                    "argumentHint": "h",
                    "enabled": false
                },
                {"id": "b", "name": ""}
            ]),
        );
        assert_eq!(
            completed(&ev),
            Some(&Completion::Value(json!([
                {
                    "id": "a",
                    "name": "n",
                    "path": "/p",
                    "scope": "user",
                    "description": "d",
                    "argumentHint": "h",
                    "enabled": false
                }
            ])))
        );
    }

    /// 对端 3.15.3 **没有** `zcode-task.prepareWorkspace`（只有 `zcode-agent.prepareStorage`，
    /// 语义是启动 agent 客户端并等 storage 就绪，不返回 configOptions/slashCommands）。
    /// 所以这个 op **不发任何 RPC**，直接回空 prep 并缓存；`refresh` 只重新生成。
    #[test]
    fn prepare_workspace_returns_empty_prep_without_rpc() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        let ev = op(&mut t, &mut ch, "prepareWorkspace", json!({}));
        assert_eq!(token_of(&ev), "prepareWorkspace:1");
        assert_eq!(ch.count(), 0, "对端没有这个方法，不应发 RPC");

        let empty = json!({"configOptions": [], "slashCommands": []});
        assert_eq!(completed(&ev), Some(&Completion::Value(empty.clone())));

        // 第二次命中缓存，仍然不发 RPC。
        let ev = op(&mut t, &mut ch, "prepareWorkspace", json!({}));
        assert_eq!(ch.count(), 0);
        assert_eq!(completed(&ev), Some(&Completion::Value(empty)));

        // refresh=true 只重新生成，依然不发 RPC。
        op(&mut t, &mut ch, "prepareWorkspace", json!({"refresh": true}));
        assert_eq!(ch.count(), 0);
    }

    // ---------------------------------------------------------- 附件

    #[test]
    fn attachment_put_single_chunk_flow() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        // Python: base64(b"hello") == "aGVsbG8="；sha256(b"hello") ==
        // 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
        let ev = op(
            &mut t,
            &mut ch,
            "attachmentPut",
            json!({"sessionId": "s1", "fileName": "a.txt", "mime": "text/plain", "dataBase64": "aGVsbG8="}),
        );
        let token = token_of(&ev);
        assert_eq!(ch.method(2), "attachmentBeginV4");
        assert_eq!(
            ch.arg(2),
            json!({
                "workspacePath": "/w",
                "workspaceIdentity": "id-1",
                "connectionId": "conn-1",
                "uploadId": "upload-uuid-2",
                "sessionId": "s1",
                "fileName": "a.txt",
                "mime": "text/plain",
                "totalBytes": 5,
                "totalChunks": 1,
                "checksum": "sha256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
            })
        );

        ok_i(&mut t, &mut ch, 2, json!({}));
        assert_eq!(ch.method(3), "attachmentChunkV4");
        assert_eq!(
            ch.arg(3),
            json!({
                "workspacePath": "/w",
                "workspaceIdentity": "id-1",
                "connectionId": "conn-1",
                "uploadId": "upload-uuid-2",
                "sessionId": "s1",
                "chunkIndex": 0,
                "dataBase64": "aGVsbG8="
            })
        );

        let ev = ok_i(&mut t, &mut ch, 3, json!({"nextChunkIndex": 1}));
        assert!(ev.contains(&ConversationEvent::AttachmentProgress {
            token: token.clone(),
            session_id: "s1".to_string(),
            upload_id: "upload-uuid-2".to_string(),
            progress: 1.0,
        }));
        assert_eq!(ch.method(4), "attachmentCommitV4");
        assert_eq!(
            ch.arg(4),
            json!({
                "workspacePath": "/w",
                "workspaceIdentity": "id-1",
                "connectionId": "conn-1",
                "uploadId": "upload-uuid-2",
                "sessionId": "s1"
            })
        );
        let ev = ok_i(&mut t, &mut ch, 4, json!({"ref": "att-1"}));
        assert_eq!(
            completed(&ev),
            Some(&Completion::AttachmentDescriptor(json!({
                "ref": "att-1",
                "fileName": "a.txt",
                "mime": "text/plain",
                "bytes": 5
            })))
        );
    }

    #[test]
    fn attachment_put_committed_short_circuits() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        let ev = op(
            &mut t,
            &mut ch,
            "attachmentPut",
            json!({"sessionId": "s1", "fileName": "a.txt", "mime": "text/plain", "dataBase64": "aGVsbG8="}),
        );
        let token = token_of(&ev);
        let ev = ok_i(&mut t, &mut ch, 2, json!({"state": "committed", "ref": "att-9"}));
        assert!(ev.contains(&ConversationEvent::AttachmentProgress {
            token,
            session_id: "s1".to_string(),
            upload_id: "upload-uuid-2".to_string(),
            progress: 1.0,
        }));
        assert_eq!(ch.count(), 3, "committed 直接返回，不发 chunk/commit");
        assert_eq!(
            completed(&ev),
            Some(&Completion::AttachmentDescriptor(json!({
                "ref": "att-9",
                "fileName": "a.txt",
                "mime": "text/plain",
                "bytes": 5
            })))
        );
    }

    #[test]
    fn attachment_put_missing_connection_id_fails() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        op(&mut t, &mut ch, "handshake", json!({}));
        ok_i(&mut t, &mut ch, 0, json!({}));
        ok_i(&mut t, &mut ch, 1, json!({}));
        assert_eq!(t.connection_id(), None);
        let ev = op(
            &mut t,
            &mut ch,
            "attachmentPut",
            json!({"sessionId": "s1", "fileName": "a.txt", "mime": "text/plain", "dataBase64": "aGVsbG8="}),
        );
        assert_eq!(
            completed(&ev),
            Some(&Completion::Failed(TransportError::invalid_state(
                "attachmentPut: missing connectionId"
            )))
        );
        assert_eq!(ch.count(), 2, "没发任何 attachment 调用");
    }

    #[test]
    fn attachment_put_splits_at_384k() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        // Python: ceil((384*1024+1)/(384*1024)) == 2；首片 base64 长度 524288，末片 1 字节 "AA=="
        let bytes = vec![0u8; 384 * 1024 + 1];
        let b64 = BASE64.encode(&bytes);
        op(
            &mut t,
            &mut ch,
            "attachmentPut",
            json!({"sessionId": "s1", "fileName": "big.bin", "mime": "application/octet-stream", "dataBase64": b64}),
        );
        let begin = ch.arg(2);
        assert_eq!(begin["totalBytes"], 384 * 1024 + 1);
        assert_eq!(begin["totalChunks"], 2);
        ok_i(&mut t, &mut ch, 2, json!({}));
        let chunk0 = ch.arg(3);
        assert_eq!(chunk0["chunkIndex"], 0);
        assert_eq!(chunk0["dataBase64"].as_str().unwrap().len(), 524_288);
        ok_i(&mut t, &mut ch, 3, json!({"nextChunkIndex": 1}));
        let chunk1 = ch.arg(4);
        assert_eq!(chunk1["chunkIndex"], 1);
        assert_eq!(chunk1["dataBase64"], "AA==");
        ok_i(&mut t, &mut ch, 4, json!({"nextChunkIndex": 2}));
        assert_eq!(ch.method(5), "attachmentCommitV4");
        let ev = ok_i(&mut t, &mut ch, 5, json!({"ref": "att-big"}));
        assert_eq!(
            completed(&ev),
            Some(&Completion::AttachmentDescriptor(json!({
                "ref": "att-big",
                "fileName": "big.bin",
                "mime": "application/octet-stream",
                "bytes": 384 * 1024 + 1
            })))
        );
    }

    #[test]
    fn attachment_put_invalid_server_progress_fails() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        op(
            &mut t,
            &mut ch,
            "attachmentPut",
            json!({"sessionId": "s1", "fileName": "a.txt", "mime": "text/plain", "dataBase64": "aGVsbG8="}),
        );
        ok_i(&mut t, &mut ch, 2, json!({}));
        let ev = ok_i(&mut t, &mut ch, 3, json!({"nextChunkIndex": 5}));
        assert_eq!(
            completed(&ev),
            Some(&Completion::Failed(TransportError::invalid_state(
                "fault.attachment.invalidServerProgress"
            )))
        );
    }

    #[test]
    fn attachment_put_skips_already_uploaded_chunks() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        let bytes = vec![7u8; 384 * 1024 * 2];
        let b64 = BASE64.encode(&bytes);
        op(
            &mut t,
            &mut ch,
            "attachmentPut",
            json!({"sessionId": "s1", "fileName": "b.bin", "mime": "m", "dataBase64": b64}),
        );
        // 服务端说从第 2 片开始（前 2 片已有）
        ok_i(&mut t, &mut ch, 2, json!({"nextChunkIndex": 2}));
        assert_eq!(ch.method(3), "attachmentCommitV4", "循环不执行，直接 commit");
        let ev = ok_i(&mut t, &mut ch, 3, json!({"ref": "att-skip"}));
        assert!(matches!(
            completed(&ev),
            Some(Completion::AttachmentDescriptor(_))
        ));
    }

    #[test]
    fn attachment_read_loops_until_total_bytes() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        let ev = op(
            &mut t,
            &mut ch,
            "attachmentRead",
            json!({"sessionId": "s1", "ref": "att-1"}),
        );
        assert_eq!(token_of(&ev), "attachmentRead:2");
        assert_eq!(ch.method(2), "attachmentReadV4");
        assert_eq!(
            ch.arg(2),
            json!({
                "workspacePath": "/w",
                "workspaceIdentity": "id-1",
                "sessionId": "s1",
                "ref": "att-1",
                "offset": 0,
                // 读取页大小 = 网页端的 512 KiB（不是上传分片的 384 KiB）
                "limit": 512 * 1024
            })
        );
        // Python: base64(b"hello") == "aGVsbG8="；base64(b" world") == "IHdvcmxk"；
        // base64(b"hello world") == "aGVsbG8gd29ybGQ="
        ok_i(
            &mut t,
            &mut ch,
            2,
            json!({"mediaType": "text/plain", "dataBase64": "aGVsbG8=", "nextOffset": 5, "totalBytes": 11}),
        );
        assert_eq!(ch.arg(3)["offset"], 5);
        let ev = ok_i(
            &mut t,
            &mut ch,
            3,
            json!({"dataBase64": "IHdvcmxk", "nextOffset": 11, "totalBytes": 11}),
        );
        assert_eq!(
            completed(&ev),
            Some(&Completion::AttachmentBytes {
                base64: "aGVsbG8gd29ybGQ=".to_string(),
                media_type: Some("text/plain".to_string()),
            })
        );
        assert_eq!(completed_token(&ev), "attachmentRead:2");
    }

    #[test]
    fn attachment_read_stops_when_server_repeats_offset() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        op(
            &mut t,
            &mut ch,
            "attachmentRead",
            json!({"sessionId": "s1", "ref": "att-1"}),
        );
        let ev = ok_i(
            &mut t,
            &mut ch,
            2,
            json!({"dataBase64": "aGVsbG8=", "nextOffset": 0}),
        );
        assert_eq!(ch.count(), 3, "nextOffset <= offset → 结束");
        assert_eq!(
            completed(&ev),
            Some(&Completion::AttachmentBytes {
                base64: "aGVsbG8=".to_string(),
                media_type: None,
            })
        );
    }

    // ---------------------------------------------------------- 状态写入面

    #[test]
    fn set_history_exhausted_updates_state() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        subscribe_ok(
            &mut t,
            &mut ch,
            "s1",
            9,
            json!({"ack": {"subscriptionId": "sub-1"}}),
        );
        fire(&mut t, &mut ch, 9, snapshot_frame("sub-1"));
        assert_eq!(t.snapshot()["conversations"]["s1"]["canLoadOlder"], false);

        let ev = op(
            &mut t,
            &mut ch,
            "setHistoryExhausted",
            json!({"sessionId": "s1", "value": true}),
        );
        assert!(ev
            .iter()
            .any(|e| matches!(e, ConversationEvent::RowsUpdated { .. })));
        assert_eq!(
            t.snapshot()["conversations"]["s1"]["historyExhausted"],
            true
        );
    }

    #[test]
    fn prepend_older_rows_inserts_and_dedups() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        subscribe_ok(
            &mut t,
            &mut ch,
            "s1",
            9,
            json!({"ack": {"subscriptionId": "sub-1"}}),
        );
        fire(&mut t, &mut ch, 9, snapshot_frame("sub-1"));
        op(
            &mut t,
            &mut ch,
            "prependOlderRows",
            json!({"sessionId": "s1", "rows": [{"rowId": 0, "text": "old"}, {"rowId": 1, "text": "dup"}], "newFirstRowId": 0}),
        );
        let s = t.snapshot();
        let rows = s["conversations"]["s1"]["rows"].as_array().unwrap();
        assert_eq!(rows.len(), 3, "rowId 0 插入，rowId 1 去重");
        assert_eq!(rows[0]["rowId"], 0);
        assert_eq!(s["conversations"]["s1"]["firstRowId"], 0);
    }

    #[test]
    fn optimistic_patch_is_skipped_without_snapshot() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        subscribe_ok(
            &mut t,
            &mut ch,
            "s1",
            9,
            json!({"ack": {"subscriptionId": "sub-1"}}),
        );
        let ev = op(
            &mut t,
            &mut ch,
            "optimisticPatch",
            json!({"sessionId": "s1", "patch": {"revision": 3}}),
        );
        assert!(!ev
            .iter()
            .any(|e| matches!(e, ConversationEvent::RowsUpdated { .. })));
        assert_eq!(t.snapshot()["conversations"]["s1"]["snapshot"], Value::Null);

        fire(&mut t, &mut ch, 9, snapshot_frame("sub-1"));
        let ev = op(
            &mut t,
            &mut ch,
            "optimisticPatch",
            json!({"sessionId": "s1", "patch": {"revision": 99}}),
        );
        assert!(ev
            .iter()
            .any(|e| matches!(e, ConversationEvent::RowsUpdated { .. })));
        assert_eq!(t.snapshot()["conversations"]["s1"]["revision"], 99);
    }

    #[test]
    fn optimistic_row_update_and_queue_removal() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        subscribe_ok(
            &mut t,
            &mut ch,
            "s1",
            9,
            json!({"ack": {"subscriptionId": "sub-1"}}),
        );
        let mut frame = snapshot_frame("sub-1");
        frame["frame"]["payload"]["snapshot"]["queue"] =
            json!({"items": [{"queueItemId": "q1"}, {"queueItemId": "q2"}], "autoDrain": false});
        fire(&mut t, &mut ch, 9, frame);

        op(
            &mut t,
            &mut ch,
            "optimisticRowUpdate",
            json!({"sessionId": "s1", "rowId": 1, "patch": {"text": "edited"}}),
        );
        assert_eq!(
            t.snapshot()["conversations"]["s1"]["rows"][0]["text"],
            "edited"
        );

        op(
            &mut t,
            &mut ch,
            "optimisticRemoveQueueItem",
            json!({"sessionId": "s1", "queueItemId": "q1"}),
        );
        let items = t.snapshot()["conversations"]["s1"]["queueItems"]
            .as_array()
            .unwrap()
            .clone();
        assert_eq!(items.len(), 1);
        assert_eq!(items[0]["queueItemId"], "q2");
        assert_eq!(t.snapshot()["conversations"]["s1"]["autoDrain"], false);
    }

    // ---------------------------------------------------------- 路由 / 快照 / 销毁

    #[test]
    fn owns_channel_id_covers_live_calls_and_listeners() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        op(&mut t, &mut ch, "handshake", json!({}));
        assert!(t.owns_channel_id(0));
        assert!(!t.owns_channel_id(1));
        ok_i(&mut t, &mut ch, 0, json!({}));
        assert!(!t.owns_channel_id(0), "已结束的请求不再拥有该 id");
        assert!(t.owns_channel_id(1));
        ok_i(&mut t, &mut ch, 1, json!({}));
        let ev = op(&mut t, &mut ch, "subscribe", json!({"sessionId": "s1"}));
        let ltoken = listen_token(&ev);
        t.step(
            ConversationInput::ListenerRegistered { token: ltoken, id: 9 },
            &mut ch,
            NOW,
        );
        assert!(t.owns_channel_id(9), "监听器 id 也算本对象发起");
        assert!(t.owns_channel_id(2));
        ok_i(&mut t, &mut ch, 2, json!({"ack": {"subscriptionId": "sub-1"}}));
        assert!(t.owns_channel_id(9));
    }

    #[test]
    fn snapshot_exposes_all_state_getters() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        subscribe_ok(
            &mut t,
            &mut ch,
            "s1",
            9,
            json!({"ack": {"subscriptionId": "sub-1", "logEpoch": "epoch-1"}}),
        );
        let mut frame = snapshot_frame("sub-1");
        let snap = &mut frame["frame"]["payload"]["snapshot"];
        snap["config"] = json!({"provider": "glm", "model": "m", "thought": "max",
                                "thoughtLevels": ["max", "high"], "mode": "yolo"});
        snap["control"] = json!({"phase": "running", "canStop": true});
        snap["queue"] = json!({"items": [{"queueItemId": "q"}], "autoDrain": false});
        snap["usage"] = json!({"cumulative": {"totalTokens": 1}});
        snap["backgroundWorks"] = json!([{"id": "bw"}]);
        snap["goal"] = json!({"text": "g"});
        snap["plan"] = json!({"steps": []});
        snap["inputRouting"] = json!({"mode": "enqueue"});
        snap["pendingInteractions"] = json!([{"interactionId": "i"}]);
        fire(&mut t, &mut ch, 9, frame);

        let s = t.snapshot();
        assert_eq!(s["ackedRevisions"]["s1"], 0);
        let c = &s["conversations"]["s1"];
        for key in [
            "snapshot",
            "rows",
            "seq",
            "logEpoch",
            "firstRowId",
            "totalCount",
            "ready",
            "historyExhausted",
            "control",
            "revision",
            "phase",
            "canStop",
            "isRunning",
            "config",
            "currentModel",
            "currentThought",
            "currentMode",
            "thoughtLevels",
            "queue",
            "queueItems",
            "autoDrain",
            "usage",
            "canLoadOlder",
            "oldestRowId",
            "backgroundWorks",
            "goal",
            "plan",
            "inputRoutingMode",
            "pendingInteractions",
            "hasStreamingRow",
            "subscriptionId",
            "disposed",
            "started",
            "listenerId",
            "resyncing",
            "stagedFrameCount",
            "pendingFragmentCount",
            "lastFrameAtMs",
        ] {
            assert!(c.get(key).is_some(), "快照缺少字段 {key}");
        }
        assert_eq!(c["phase"], "running");
        assert_eq!(c["canStop"], true);
        assert_eq!(c["isRunning"], true);
        assert_eq!(c["currentModel"], "m");
        assert_eq!(c["currentThought"], "max");
        assert_eq!(c["currentMode"], "yolo");
        assert_eq!(c["thoughtLevels"], json!(["max", "high"]));
        assert_eq!(c["autoDrain"], false);
        assert_eq!(c["inputRoutingMode"], "enqueue");
        assert_eq!(c["pendingInteractions"].as_array().unwrap().len(), 1);
        assert_eq!(c["backgroundWorks"].as_array().unwrap().len(), 1);
        assert_eq!(c["goal"], json!({"text": "g"}));
        assert_eq!(c["canLoadOlder"], false);
        assert_eq!(c["oldestRowId"], 1);
        assert_eq!(c["lastFrameAtMs"], NOW);
    }

    #[test]
    fn dispose_cancels_timers_and_unlistens() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        subscribe_ok(
            &mut t,
            &mut ch,
            "s1",
            9,
            json!({"ack": {"subscriptionId": "sub-1"}}),
        );
        let ev = t.dispose(NOW);
        assert!(ev.contains(&ConversationEvent::TimerCancel(TimerId(1))));
        assert!(ev.contains(&ConversationEvent::TimerCancel(TimerId(2))));
        assert!(ev.contains(&ConversationEvent::UnlistenChannel { id: 9 }));
        assert!(!t.owns_channel_id(9));
        assert!(t
            .step(ConversationInput::BridgeRecovered, &mut ch, NOW)
            .is_empty());
        assert!(t.call_op("skills", &json!({}), &mut ch, NOW).is_err());
        assert!(t.dispose(NOW).is_empty(), "重复 dispose 幂等");
    }

    #[test]
    fn dispose_subscription_sends_unsubscribe() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        drive_handshake(&mut t, &mut ch);
        subscribe_ok(
            &mut t,
            &mut ch,
            "s1",
            9,
            json!({"ack": {"subscriptionId": "sub-1"}}),
        );
        let ev = op(
            &mut t,
            &mut ch,
            "disposeSubscription",
            json!({"sessionId": "s1"}),
        );
        assert_eq!(completed(&ev), Some(&Completion::Ok));
        assert!(ev.contains(&ConversationEvent::UnlistenChannel { id: 9 }));
        assert_eq!(ch.count(), 4);
        assert_eq!(ch.method(3), "unsubscribeConversationV4");
        assert_eq!(ch.arg(3)["subscriptionId"], "sub-1");
        assert!(t.snapshot()["conversations"].as_object().unwrap().is_empty());
    }

    // ---------------------------------------------------------- op 校验

    #[test]
    fn unknown_op_is_an_error() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        let err = t
            .call_op("nope", &json!({}), &mut ch, NOW)
            .expect_err("未知 op 应报错");
        assert!(matches!(err, ProtocolError::Other(_)));
        assert!(err.to_string().contains("unknown conversation op: nope"));
        assert_eq!(ch.count(), 0);
    }

    #[test]
    fn missing_required_args_are_errors() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        let err = t
            .call_op("sendText", &json!({"sessionId": "s1"}), &mut ch, NOW)
            .expect_err("缺 text 应报错");
        assert!(err.to_string().contains("missing required string arg 'text'"));
        let err = t
            .call_op("rowsRange", &json!({"sessionId": 1}), &mut ch, NOW)
            .expect_err("sessionId 类型不符应报错");
        assert!(err
            .to_string()
            .contains("missing required string arg 'sessionId'"));
        let err = t
            .call_op("skills", &json!("nope"), &mut ch, NOW)
            .expect_err("args 不是对象应报错");
        assert!(err.to_string().contains("args must be a JSON object"));
    }

    #[test]
    fn ops_list_covers_every_dart_method() {
        assert_eq!(ConversationTransport::OPS.len(), 41);
        for name in [
            "handshake",
            "subscribe",
            "subscribeSessionsIndex",
            "disposeSubscription",
            "disposeSessionsIndex",
            "sendCommand",
            "sendText",
            "sendGoalCommand",
            "pauseGoal",
            "resumeGoal",
            "stop",
            "compact",
            "switchModelConfig",
            "switchCollaborationMode",
            "setFollowupMode",
            "setAssistantFeedback",
            "retryTurn",
            "sendQueuedNow",
            "editQueueItem",
            "deleteQueueItem",
            "setAutoDrain",
            "forkAssistant",
            "editUserQuery",
            "applyFileRewind",
            "createSession",
            "createSelectionSideSession",
            "resolveInteraction",
            "plans",
            "fileChanges",
            "fileRewindPreview",
            "rowsRange",
            "attachmentPut",
            "attachmentRead",
            "prepareWorkspace",
            "skills",
            "setHistoryExhausted",
            "prependOlderRows",
            "optimisticPatch",
            "optimisticRowUpdate",
            "optimisticRemoveQueueItem",
            // 扩展 op（Dart 没有）：对话内模型目录查询。
            "modelSelectionView",
        ] {
            assert!(ConversationTransport::OPS.contains(&name), "缺 {name}");
        }
    }

    #[test]
    fn op_after_dispose_is_rejected() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        t.dispose(NOW);
        let err = t
            .call_op("skills", &json!({}), &mut ch, NOW)
            .expect_err("已销毁应报错");
        assert!(matches!(err, ProtocolError::InvalidState(_)));
    }

    #[test]
    fn unknown_request_responses_are_ignored() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        assert!(timeout(&mut t, &mut ch, 42).is_empty());
        drive_handshake(&mut t, &mut ch);
        assert!(ok_i(&mut t, &mut ch, 0, json!({})).is_empty());
        assert!(err(&mut t, &mut ch, 0, "late").is_empty());
        assert!(fire(&mut t, &mut ch, 999, json!({"topic": "x"})).is_empty());
    }

    #[test]
    fn deferred_ops_survive_repeated_pump() {
        let mut t = transport();
        let mut ch = FakeChannels::new();
        let a = op(&mut t, &mut ch, "plans", json!({"sessionId": "s1"}));
        let b = op(&mut t, &mut ch, "rowsRange", json!({"sessionId": "s1"}));
        assert_eq!(ch.count(), 1);
        ok_i(&mut t, &mut ch, 0, json!({}));
        ok_i(&mut t, &mut ch, 1, json!({}));
        assert_eq!(ch.method(2), "conversationPlansV4");
        assert_eq!(ch.method(3), "conversationRowsRangeV4");
        ok_i(&mut t, &mut ch, 2, json!({"plans": []}));
        let ev = ok_i(&mut t, &mut ch, 3, json!({"rows": {}}));
        assert_eq!(token_of(&a), "plans:1");
        assert_eq!(token_of(&b), "rowsRange:2");
        assert!(matches!(completed(&ev), Some(Completion::Value(_))));
    }
}
