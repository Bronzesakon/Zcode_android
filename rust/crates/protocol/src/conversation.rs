//! 对应 `Reference_code/lib/protocol/conversation.dart`——Conversation V4 协议（`zcode-agent` 通道）。
//!
//! 线上流程（与网页端 `sk()`/`uk()` 一致）：
//! 1. `helloConversationV4()` + `initializeConversationV4(clientHello)`
//! 2. `subscribeConversationV4(scope + sessionId)` → `ack.subscriptionId`
//! 3. 帧由动态事件 `onDynamicConversationFrame(scope)` 推送：
//!    `{wireVersion:3, kind:'complete'|'fragment', topic, subscriptionId, frame|fragment*}`；
//!    complete 帧内层是 `{topic, subscriptionId, fromSeq, toSeq, sentAt, payload}`，
//!    payload 为 `{kind:'snapshot', snapshot}` 或 `{kind:'deltas', deltas}`
//! 4. 命令经 `sendConversationCommandV4(scope + envelope)` 下发，
//!    envelope 为 `{commandId, clientId, sessionId, type, payload, issuedAt}`
//!
//! # 边界（见 `PORTING.md`）
//!
//! 本模块只保留**纯逻辑、同步、无 I/O** 的部分：
//!
//! * 命令信封 / 载荷 / 请求参数的构造（线上格式零漂移）；
//! * 命令应答的解析与 CAS 修订号跟踪（`_ackedRevisions` 的纯算法）；
//! * 附件分块数学 + 纯读取状态机（[`AttachmentReader`]）；
//! * 逻辑帧分片重组（[`FragmentAssembler`]）与订阅的纯状态（[`SubscriptionCore`]）；
//! * [`ConversationState`] / [`SessionsIndexState`] 的快照与增量应用；
//! * workspace 配置与技能条目的解析（[`WorkspacePrep`] / [`SkillEntry`]）。
//!
//! 不在此处（宿主/Kotlin 侧实现，本模块只给常量与判定函数）：
//!
//! * 通道调用（`_channels.call`）、`session.waitHealthy`、`recovered`/`degraded` 监听；
//! * 全部定时器（watchdog、resubscribe 重试、fragment 清理周期）——只移植其**判定**；
//! * `ChangeNotifier`/`notifyListeners`（Rust 侧由宿主在方法返回后自行通知 UI）；
//! * `generateUuid()`（见 `crate::id`）、`Channels.zcodeAgent` 等通道名字面量
//!   （见 `crate::channel_client`，本模块只用到 `'zcode-agent'` 这一个通道，不在此重复定义）。

use std::collections::{BTreeMap, HashMap};

use base64::engine::general_purpose::{STANDARD, STANDARD_NO_PAD};
use base64::Engine as _;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};

use crate::error::{ProtocolError, Result};

/// 对应 Dart 的 `Map<String, dynamic>`（本模块所有 JSON 对象都用它）。
///
/// `serde_json::Map` 有两个泛型参数，写起来太吵，这里起个别名。
// PORT-NOTE: 该类型应由主线提升到公共模块（`ipc_codec` / `automation_client` 等同样需要它）。
pub type JsonMap = serde_json::Map<String, Value>;

// ============================================================ 1. 常量与命令集合

/// 对应 Dart `conversationProtocolAppVersion`。
///
/// 这是**桌面 Conversation 协议能力版本**，不是 Zemote 发布版本；这里发 0.x 会让服务端协商
/// 关掉 V4 能力（如 sessions-index）。
pub const CONVERSATION_PROTOCOL_APP_VERSION: &str = "3.6.5";

/// 对应 `initializeConversationV4` 里写死的 `protocolVersion`。
pub const CONVERSATION_PROTOCOL_VERSION: i64 = 3;

/// 对应 `initializeConversationV4` 里写死的 `clientKind`。
pub const CONVERSATION_CLIENT_KIND: &str = "mobileApp";

/// 对应 `sendCommand` 的 `timeout` 默认值（30 秒）。
pub const COMMAND_DEFAULT_TIMEOUT_SECS: u64 = 30;
/// 对应 `session.waitHealthy(timeout: 45s)`（发命令前 / 掉线重试前各一次）。
pub const BRIDGE_HEALTHY_TIMEOUT_SECS: u64 = 45;
/// 对应 `_start()` 里订阅调用的 60 秒超时（服务端可能要预热会话运行时）。
pub const SUBSCRIBE_TIMEOUT_SECS: u64 = 60;
/// 对应 `createSession` 的 90 秒超时。
pub const CREATE_SESSION_TIMEOUT_SECS: u64 = 90;
/// 对应 `createSelectionSideSession` 的 60 秒超时。
pub const CREATE_SIDE_SESSION_TIMEOUT_SECS: u64 = 60;
/// 对应 `skills()` 的 20 秒超时。
pub const SKILLS_TIMEOUT_SECS: u64 = 20;
/// 对应 `_SubscriptionBase` 的 `Timer.periodic(30s)` 分片清理周期。
pub const FRAGMENT_CLEANUP_INTERVAL_SECS: u64 = 30;
/// 对应 `_purgeFragments` 的「超过 60 秒未完成」判定（见 [`fragment_is_stale`]）。
pub const FRAGMENT_STALE_SECS: i64 = 60;
/// 对应 `_acceptFragment` 里的 `count > 64` 上限。
pub const FRAGMENT_MAX_COUNT: i64 = 64;
/// 对应 `ConversationSubscription._startWatchdog` 的 `Timer.periodic(10s)`。
pub const WATCHDOG_INTERVAL_SECS: u64 = 10;
/// 对应 watchdog 的 `quietSeconds < 20` 静默阈值。
pub const WATCHDOG_QUIET_SECS: i64 = 20;
/// 对应 `_resubscribe` 失败后 `Timer(3s)` 的重试延迟。
pub const RESUBSCRIBE_RETRY_SECS: u64 = 3;
/// 对应 `attachmentPut` 的 `_attachmentChunkBytes = 384 * 1024`。
///
/// 这是**上传**分片大小（客户端实测值）。别与读取页大小 [`ATTACHMENT_READ_LIMIT_BYTES`]
/// 混淆——网页端 `attachmentChunkMaxBytes` 的 512 KiB 是上限，上传实际用 384 KiB。
pub const ATTACHMENT_CHUNK_BYTES: usize = 384 * 1024;

/// `attachmentReadV4` 单次请求的 `limit`：**512 KiB**（网页端实测）。
///
/// 对端常量 `attachmentChunkMaxBytes = 512 KiB`（`validation-CqXZOLXO.js` @117,666）。
/// 参考 Dart 用 384 KiB 也能工作，只是多跑几轮。
pub const ATTACHMENT_READ_LIMIT_BYTES: usize = 512 * 1024;

/// 读取轮数上限：**60**（网页端）。
///
/// 60 × 512 KiB = 30 MiB = 对端的 `attachmentPreviewMaxBytes`（`Lu`，30 MiB），
/// 也就是 `attachmentPreviewMaxChunks`。参考 Dart 的 1024 轮没有对应的对端依据。
pub const ATTACHMENT_MAX_READ_ROUNDS: usize = 60;
/// 对应 `rowsRange(..., int limit = 60)` 的默认值。
pub const ROWS_RANGE_DEFAULT_LIMIT: i64 = 60;

/// 对应 Dart `ConversationTransport._casCommands`：需要 `baseRevision` 的命令（网页端 `eAe`）。
pub const CAS_COMMANDS: &[&str] = &[
    "applyFileRewind",
    "forkAssistant",
    "editUserQuery",
    "retryTurn",
    "setAssistantFeedback",
    "sendQueuedNow",
    "editQueueItem",
    "reorderQueueItem",
    "deleteQueueItem",
    "setAutoDrain",
    "switchModelConfig",
    "switchCollaborationMode",
    "setFollowupMode",
    "pauseGoal",
    "resumeGoal",
];

/// 对应 Dart `ConversationTransport._rowTargetCommands`：行目标命令，额外需要 `baseLogEpoch`（网页端 `tAe`）。
pub const ROW_TARGET_COMMANDS: &[&str] = &[
    "applyFileRewind",
    "forkAssistant",
    "editUserQuery",
    "retryTurn",
    "setAssistantFeedback",
    // 对端/网页端都有、Dart 漏了（`out/host/chunk-T2GB4I3K.js` @43,342 的 `Fp` 是 6 个）。
    // 漏掉它会导致该命令不附 `baseLogEpoch`，CAS 不完整。
    "setHighspeedMetrics",
];

/// 对应 Dart `_casCommands.contains(type)`。
pub fn is_cas_command(command_type: &str) -> bool {
    CAS_COMMANDS.contains(&command_type)
}

/// 对应 Dart `_rowTargetCommands.contains(type)`。
pub fn is_row_target_command(command_type: &str) -> bool {
    ROW_TARGET_COMMANDS.contains(&command_type)
}

// ============================================================ 2. 载荷 / 信封构造（纯构造，发送交给宿主）

/// 对应 `initializeConversationV4` 参数里的 `clientHello`。
///
/// 字段名与顺序照抄 Dart：`kind/protocolVersion/clientId/clientKind/appVersion`。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ClientHello {
    /// 固定 `'clientHello'`。
    pub kind: String,
    /// 固定 3（[`CONVERSATION_PROTOCOL_VERSION`]）。
    #[serde(rename = "protocolVersion")]
    pub protocol_version: i64,
    /// 对应 Dart `ConversationTransport.clientId`（构造时 `generateUuid()`，见 `crate::id`）。
    #[serde(rename = "clientId")]
    pub client_id: String,
    /// 固定 `'mobileApp'`。
    #[serde(rename = "clientKind")]
    pub client_kind: String,
    /// 对应 `appVersion`，默认 [`CONVERSATION_PROTOCOL_APP_VERSION`]。
    #[serde(rename = "appVersion")]
    pub app_version: String,
}

impl ClientHello {
    /// 对应 `handshake()` 里 `initializeConversationV4` 的 `clientHello` 字面量。
    pub fn new(client_id: impl Into<String>, app_version: impl Into<String>) -> Self {
        Self {
            kind: "clientHello".to_string(),
            protocol_version: CONVERSATION_PROTOCOL_VERSION,
            client_id: client_id.into(),
            client_kind: CONVERSATION_CLIENT_KIND.to_string(),
            app_version: app_version.into(),
        }
    }
}

/// 对应 `sendCommand` 组装的命令信封。
///
/// 字段顺序照抄 Dart 的 Map 字面量：
/// `commandId, clientId, sessionId, baseRevision?, baseLogEpoch?, type, payload, issuedAt`。
/// `sessionId` **始终存在**（Dart 会写出 null）；`baseRevision`/`baseLogEpoch` 只在适用时出现。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct CommandEnvelope {
    /// 对应 `generateUuid()`（见 `crate::id`）。
    #[serde(rename = "commandId")]
    pub command_id: String,
    /// 对应 `clientId`。
    #[serde(rename = "clientId")]
    pub client_id: String,
    /// 对应 `sessionId`：createSession 时为 null，但键必须存在。
    #[serde(rename = "sessionId")]
    pub session_id: Option<String>,
    /// 对应 `if (_casCommands.contains(type)) 'baseRevision': baseRevision`。
    #[serde(rename = "baseRevision", skip_serializing_if = "Option::is_none")]
    pub base_revision: Option<i64>,
    /// 对应 `if (_rowTargetCommands.contains(type) && sub?.state.logEpoch != null) 'baseLogEpoch': ...`。
    #[serde(rename = "baseLogEpoch", skip_serializing_if = "Option::is_none")]
    pub base_log_epoch: Option<String>,
    /// 对应 `type`。
    #[serde(rename = "type")]
    pub command_type: String,
    /// 对应 `payload`。
    pub payload: Value,
    /// 对应 `issuedAt`：`DateTime.now().millisecondsSinceEpoch`。
    #[serde(rename = "issuedAt")]
    pub issued_at: i64,
}

/// 对应 `sendCommand` 里的
/// `[sub?.state.revision ?? 0, _ackedRevisions[sessionId] ?? 0].reduce((a, b) => a > b ? a : b)`。
///
/// `session_id` 为 `None` 时返回 `None`（Dart 里 `baseRevision` 是 null）。
pub fn command_base_revision(
    session_id: Option<&str>,
    subscription_revision: Option<i64>,
    acked_revision: Option<i64>,
) -> Option<i64> {
    session_id.map(|_| {
        let a = subscription_revision.unwrap_or(0);
        let b = acked_revision.unwrap_or(0);
        if a > b {
            a
        } else {
            b
        }
    })
}

/// 对应 `sendCommand` 组装信封的那段（含 CAS / 行目标命令的字段取舍）。
///
/// `subscription_revision` / `subscription_log_epoch` 来自当前会话订阅的状态；
/// `acked_revision` 来自 [`AckedRevisions`]。
#[allow(clippy::too_many_arguments)]
pub fn build_command_envelope(
    command_id: impl Into<String>,
    client_id: impl Into<String>,
    session_id: Option<&str>,
    command_type: &str,
    payload: Value,
    subscription_revision: Option<i64>,
    subscription_log_epoch: Option<&str>,
    acked_revision: Option<i64>,
    issued_at: i64,
) -> CommandEnvelope {
    let base = command_base_revision(session_id, subscription_revision, acked_revision);
    let base_revision = if is_cas_command(command_type) {
        base
    } else {
        None
    };
    let base_log_epoch = if is_row_target_command(command_type) {
        subscription_log_epoch.map(|s| s.to_string())
    } else {
        None
    };
    CommandEnvelope {
        command_id: command_id.into(),
        client_id: client_id.into(),
        session_id: session_id.map(|s| s.to_string()),
        base_revision,
        base_log_epoch,
        command_type: command_type.to_string(),
        payload,
        issued_at,
    }
}

// PORT-NOTE: Dart 在 `_casCommands.contains(type)` 成立时**无条件**写入 `'baseRevision': baseRevision`
// （哪怕它是 null，即「CAS 命令 + sessionId 为 null」会发出 `"baseRevision": null`）。本移植用
// `skip_serializing_if = "Option::is_none"` 省掉了这个 null 分支——差异只在上面那种组合下出现，
// 而本文件里所有 CAS 命令的调用点都带 sessionId（createSession 不是 CAS 命令），故线上不可达。
// 若主线要字节级复刻，把该字段改成 `Option<Option<i64>>`，或直接构 `Value`。

/// 对应 `_sendCommandWithRetry` 里超时重发用的 `fresh` 信封：换 `commandId` 与 `issuedAt`，其余照抄。
pub fn fresh_envelope(
    envelope: &CommandEnvelope,
    command_id: &str,
    issued_at: i64,
) -> CommandEnvelope {
    let mut e = envelope.clone();
    e.command_id = command_id.to_string();
    e.issued_at = issued_at;
    e
}

/// 对应 `sendCommand` 的 stale 重试信封：
/// `{...envelope, 'commandId': 新, 'baseRevision': serverRevision, 'issuedAt': 现在}`。
pub fn stale_retry_envelope(
    envelope: &CommandEnvelope,
    command_id: &str,
    server_revision: i64,
    issued_at: i64,
) -> CommandEnvelope {
    let mut e = envelope.clone();
    e.command_id = command_id.to_string();
    e.base_revision = Some(server_revision);
    e.issued_at = issued_at;
    e
}

/// 对应 `sendCommand` 的 `[{...scope, 'envelope': envelope}]` 参数。
pub fn send_command_args(scope: &JsonMap, envelope: &CommandEnvelope) -> Value {
    let mut m = scope.clone();
    m.insert(
        "envelope".to_string(),
        serde_json::to_value(envelope).unwrap_or(Value::Null),
    );
    Value::Object(m)
}

/// 对应 `createSession` 的 payload：
/// `{workspaceId, firstInput?: {text, attachments?}, config?, runtimeModel?, mcpServers?}`。
pub fn create_session_payload(
    workspace_id: &str,
    first_text: Option<&str>,
    attachments: Option<&[JsonMap]>,
    config: Option<&JsonMap>,
    runtime_model: Option<&str>,
    mcp_servers: Option<&[String]>,
) -> Value {
    let mut m = JsonMap::new();
    m.insert(
        "workspaceId".to_string(),
        Value::String(workspace_id.to_string()),
    );
    if let Some(text) = first_text {
        let mut first = JsonMap::new();
        first.insert("text".to_string(), Value::String(text.to_string()));
        if let Some(a) = attachments {
            if !a.is_empty() {
                first.insert("attachments".to_string(), arr_of_objects(a));
            }
        }
        m.insert("firstInput".to_string(), Value::Object(first));
    }
    if let Some(c) = config {
        m.insert("config".to_string(), Value::Object(c.clone()));
    }
    if let Some(rm) = runtime_model {
        m.insert("runtimeModel".to_string(), Value::String(rm.to_string()));
    }
    if let Some(servers) = mcp_servers {
        if !servers.is_empty() {
            m.insert("mcpServers".to_string(), arr_of_strings(servers));
        }
    }
    Value::Object(m)
}

/// 对应 `sendText` 的 payload（可选字段都按 Dart 的 `if (... != null)` / 非空判定取舍）。
#[allow(clippy::too_many_arguments)]
pub fn send_text_payload(
    text: &str,
    attachments: Option<&[JsonMap]>,
    held_queue_disposition: Option<&str>,
    expected_held_queue_item_ids: Option<&[String]>,
    automation_id: Option<&str>,
    off_peak_task_id: Option<&str>,
    off_peak_run_type: Option<&str>,
    bot_delivery_target: Option<&str>,
    tool_disallowlist: Option<&[String]>,
) -> Value {
    let mut m = JsonMap::new();
    m.insert("text".to_string(), Value::String(text.to_string()));
    if let Some(a) = attachments {
        if !a.is_empty() {
            m.insert("attachments".to_string(), arr_of_objects(a));
        }
    }
    if let Some(v) = held_queue_disposition {
        m.insert(
            "heldQueueDisposition".to_string(),
            Value::String(v.to_string()),
        );
    }
    if let Some(ids) = expected_held_queue_item_ids {
        if !ids.is_empty() {
            m.insert("expectedHeldQueueItemIds".to_string(), arr_of_strings(ids));
        }
    }
    if let Some(v) = automation_id {
        m.insert("automationId".to_string(), Value::String(v.to_string()));
    }
    if let Some(v) = off_peak_task_id {
        m.insert("offPeakTaskId".to_string(), Value::String(v.to_string()));
    }
    if let Some(v) = off_peak_run_type {
        m.insert("offPeakRunType".to_string(), Value::String(v.to_string()));
    }
    if let Some(v) = bot_delivery_target {
        m.insert(
            "botDeliveryTarget".to_string(),
            Value::String(v.to_string()),
        );
    }
    if let Some(v) = tool_disallowlist {
        if !v.is_empty() {
            m.insert("toolDisallowlist".to_string(), arr_of_strings(v));
        }
    }
    Value::Object(m)
}

/// 对应 `sendGoalCommand` 的 payload。
pub fn send_goal_command_payload(
    text: &str,
    display_text: Option<&str>,
    held_queue_disposition: Option<&str>,
    expected_held_queue_item_ids: Option<&[String]>,
) -> Value {
    let mut m = JsonMap::new();
    m.insert("text".to_string(), Value::String(text.to_string()));
    if let Some(v) = display_text {
        m.insert("displayText".to_string(), Value::String(v.to_string()));
    }
    if let Some(v) = held_queue_disposition {
        m.insert(
            "heldQueueDisposition".to_string(),
            Value::String(v.to_string()),
        );
    }
    if let Some(ids) = expected_held_queue_item_ids {
        if !ids.is_empty() {
            m.insert("expectedHeldQueueItemIds".to_string(), arr_of_strings(ids));
        }
    }
    Value::Object(m)
}

/// 对应 `switchModelConfig` 的 payload：`{provider, model, thought}`。
pub fn switch_model_config_payload(provider: &str, model: &str, thought: &str) -> Value {
    json!({"provider": provider, "model": model, "thought": thought})
}

/// 对应 `switchCollaborationMode` 的 payload：`{mode}`。
pub fn switch_collaboration_mode_payload(mode: &str) -> Value {
    json!({"mode": mode})
}

/// 对应 `setFollowupMode` 的 payload：`{mode}`。
pub fn set_followup_mode_payload(mode: &str) -> Value {
    json!({"mode": mode})
}

/// 对应 `setAssistantFeedback` 的 payload。
///
/// **注意**：Dart 里 `'feedback': feedback` 无条件写入，`feedback` 为 null 时线上是 `"feedback": null`。
pub fn set_assistant_feedback_payload(target: &JsonMap, feedback: Option<&str>) -> Value {
    let mut m = JsonMap::new();
    m.insert("target".to_string(), Value::Object(target.clone()));
    m.insert(
        "feedback".to_string(),
        match feedback {
            Some(s) => Value::String(s.to_string()),
            None => Value::Null,
        },
    );
    Value::Object(m)
}

/// 对应 `retryTurn` 的 payload：`{target}`。
pub fn retry_turn_payload(target: &JsonMap) -> Value {
    json!({"target": Value::Object(target.clone())})
}

/// 对应 `sendQueuedNow` 的 payload：`{queueItemId}`。
pub fn send_queued_now_payload(queue_item_id: &str) -> Value {
    json!({"queueItemId": queue_item_id})
}

/// 对应 `editQueueItem` 的 payload：`{queueItemId, newText}`。
pub fn edit_queue_item_payload(queue_item_id: &str, new_text: &str) -> Value {
    json!({"queueItemId": queue_item_id, "newText": new_text})
}

/// 对应 `deleteQueueItem` 的 payload：`{queueItemId}`。
pub fn delete_queue_item_payload(queue_item_id: &str) -> Value {
    json!({"queueItemId": queue_item_id})
}

/// 对应 `setAutoDrain` 的 payload：`{autoDrain}`。
pub fn set_auto_drain_payload(auto_drain: bool) -> Value {
    json!({"autoDrain": auto_drain})
}

/// 对应 `forkAssistant` 的 payload：`{target}`。
pub fn fork_assistant_payload(target: &JsonMap) -> Value {
    json!({"target": Value::Object(target.clone())})
}

/// 对应 `editUserQuery` 的 payload：`{target, newText}`。
pub fn edit_user_query_payload(target: &JsonMap, new_text: &str) -> Value {
    json!({"target": Value::Object(target.clone()), "newText": new_text})
}

/// 对应 `applyFileRewind` 的 payload：`{target}`。
pub fn apply_file_rewind_payload(target: &JsonMap) -> Value {
    json!({"target": Value::Object(target.clone())})
}

/// 对应 `resolveInteraction` 的 payload：
/// `{interactionId, answer: {optionId?, freeText?, action?, content?}}`。
pub fn resolve_interaction_payload(
    interaction_id: &str,
    option_id: Option<&str>,
    free_text: Option<&str>,
    action: Option<&str>,
    content: Option<&JsonMap>,
) -> Value {
    let mut answer = JsonMap::new();
    if let Some(v) = option_id {
        answer.insert("optionId".to_string(), Value::String(v.to_string()));
    }
    if let Some(v) = free_text {
        answer.insert("freeText".to_string(), Value::String(v.to_string()));
    }
    if let Some(v) = action {
        answer.insert("action".to_string(), Value::String(v.to_string()));
    }
    if let Some(v) = content {
        answer.insert("content".to_string(), Value::Object(v.clone()));
    }
    json!({"interactionId": interaction_id, "answer": Value::Object(answer)})
}

/// 对应 `pauseGoal` / `resumeGoal` / `stop` / `compact` 的 payload：`{}`。
pub fn empty_payload() -> Value {
    json!({})
}

/// 对应 `plans()` 的 `{...scope, 'sessionId': sessionId}` 参数。
pub fn conversation_plans_args(scope: &JsonMap, session_id: &str) -> Value {
    let mut m = scope.clone();
    m.insert("sessionId".to_string(), Value::String(session_id.to_string()));
    Value::Object(m)
}

/// 对应 `fileChanges` / `fileRewindPreview` 的参数：
/// `{...scope, sessionId, target, baseRevision?, baseLogEpoch?}`。
pub fn file_changes_args(
    scope: &JsonMap,
    session_id: &str,
    target: &JsonMap,
    base_revision: Option<i64>,
    base_log_epoch: Option<&str>,
) -> Value {
    let mut m = scope.clone();
    m.insert("sessionId".to_string(), Value::String(session_id.to_string()));
    m.insert("target".to_string(), Value::Object(target.clone()));
    if let Some(v) = base_revision {
        m.insert("baseRevision".to_string(), json!(v));
    }
    if let Some(v) = base_log_epoch {
        m.insert("baseLogEpoch".to_string(), Value::String(v.to_string()));
    }
    Value::Object(m)
}

/// 对应 `rowsRange` 的参数：`{...scope, sessionId, beforeRowId?, limit}`。
pub fn rows_range_args(
    scope: &JsonMap,
    session_id: &str,
    before_row_id: Option<i64>,
    limit: i64,
) -> Value {
    let mut m = scope.clone();
    m.insert("sessionId".to_string(), Value::String(session_id.to_string()));
    if let Some(v) = before_row_id {
        m.insert("beforeRowId".to_string(), json!(v));
    }
    m.insert("limit".to_string(), json!(limit));
    Value::Object(m)
}

// ============================================================ 3. 应答解析与 CAS 修订跟踪

/// 对应 Dart `ConversationTransport._ackedRevisions`：来自命令 ack 的 `revisionAtDecision` 下界。
///
/// ack 会先于随后的 `state.updated` 帧到达，所以下一条 CAS 命令不能因为没看到帧而发旧基准。
#[derive(Debug, Clone, Default)]
pub struct AckedRevisions {
    by_session: HashMap<String, i64>,
}

/// 对应 Dart 里 `status == 'accepted' || status == 'noop' || status == 'duplicate' ? rev + 1 : rev`。
///
/// `revisionAtDecision` 是决策时刻的基准；被接受的命令会把修订号 +1，故下一条 CAS 基准要 +1。
pub fn ack_revision_floor(status: Option<&str>, revision_at_decision: i64) -> i64 {
    match status {
        Some("accepted") | Some("noop") | Some("duplicate") => revision_at_decision + 1,
        _ => revision_at_decision,
    }
}

impl AckedRevisions {
    /// 对应 `_ackedRevisions[sessionId] ?? 0`。
    pub fn get(&self, session_id: &str) -> i64 {
        self.by_session.get(session_id).copied().unwrap_or(0)
    }

    /// 对应 `if (serverRevision > (_ackedRevisions[sessionId] ?? 0)) _ackedRevisions[sessionId] = serverRevision;`
    /// （stale ack 里的服务端修订号）。
    pub fn note_revision(&mut self, session_id: &str, revision: i64) {
        if revision > self.get(session_id) {
            self.by_session.insert(session_id.to_string(), revision);
        }
    }

    /// 对应 `sendCommand` 里 stale 分支的判定与记账：
    /// `res is Map && res['status'] == 'stale' && res['revisionAtDecision'] is num`。
    ///
    /// 返回 `Some(serverRevision)` 表示应按服务端修订号重试一次。
    pub fn observe_stale(&mut self, session_id: Option<&str>, res: &Value) -> Result<Option<i64>> {
        let Some(session_id) = session_id else {
            return Ok(None);
        };
        let Some(obj) = res.as_object() else {
            return Ok(None);
        };
        if obj.get("status").and_then(|v| v.as_str()) != Some("stale") {
            return Ok(None);
        }
        let Some(rev) = num_of(obj.get("revisionAtDecision"))? else {
            return Ok(None);
        };
        self.note_revision(session_id, rev);
        Ok(Some(rev))
    }

    /// 对应 `sendCommand` 末尾的 ack 记账：
    /// `if (sessionId != null && res is Map && res['revisionAtDecision'] is num)` → 按 [`ack_revision_floor`] 抬升下界。
    pub fn observe_final(&mut self, session_id: Option<&str>, res: &Value) -> Result<()> {
        let Some(session_id) = session_id else {
            return Ok(());
        };
        let Some(obj) = res.as_object() else {
            return Ok(());
        };
        let Some(rev) = num_of(obj.get("revisionAtDecision"))? else {
            return Ok(());
        };
        let status = obj.get("status").and_then(|v| v.as_str());
        let floor = ack_revision_floor(status, rev);
        if floor > self.get(session_id) {
            self.by_session.insert(session_id.to_string(), floor);
        }
        Ok(())
    }
}

/// 对应 `createSession` 的应答解析：要求 `status == 'accepted'`，并从 `result.sessionId` 取会话 id。
///
/// 失败信息与 Dart 的 `StateError` 文案逐字一致（注意 `'$reasonCode $message'` 中间那个空格）。
pub fn parse_create_session_result(res: &Value) -> Result<String> {
    let map = res.as_object();
    let status = map.and_then(|m| m.get("status"));
    if status.and_then(|v| v.as_str()) != Some("accepted") {
        let reason = map
            .and_then(|m| m.get("reasonCode"))
            .filter(|v| !v.is_null())
            .or(status);
        let message = map.and_then(|m| m.get("message")).filter(|v| !v.is_null());
        return Err(ProtocolError::InvalidState(format!(
            "createSession rejected: {} {}",
            interp(reason),
            interp_empty(message)
        )));
    }
    let session_id = map
        .and_then(|m| m.get("result"))
        .and_then(|r| r.as_object())
        .and_then(|r| r.get("sessionId"));
    match session_id {
        Some(Value::String(s)) if !s.is_empty() => Ok(s.clone()),
        _ => Err(ProtocolError::InvalidState(
            "createSession: missing sessionId in result".to_string(),
        )),
    }
}

/// 对应 `createSelectionSideSession` 的应答解析：`status` 为 `accepted` 或 `duplicate` 都算成功。
pub fn parse_create_selection_side_session_result(res: &Value) -> Result<String> {
    let map = res.as_object();
    let status = map.and_then(|m| m.get("status"));
    let ok = matches!(
        status.and_then(|v| v.as_str()),
        Some("accepted") | Some("duplicate")
    );
    if !ok {
        let reason = map
            .and_then(|m| m.get("reasonCode"))
            .filter(|v| !v.is_null())
            .or(status);
        let message = map.and_then(|m| m.get("message")).filter(|v| !v.is_null());
        return Err(ProtocolError::InvalidState(format!(
            "createSelectionSideSession rejected: {} {}",
            interp(reason),
            interp_empty(message)
        )));
    }
    let session_id = map
        .and_then(|m| m.get("result"))
        .and_then(|r| r.as_object())
        .and_then(|r| r.get("sessionId"));
    match session_id {
        Some(Value::String(s)) if !s.is_empty() => Ok(s.clone()),
        _ => Err(ProtocolError::InvalidState(
            "createSelectionSideSession: missing sessionId in result".to_string(),
        )),
    }
}

/// 对应 `handshake()` 里 `if (hello is Map) connectionId = hello['connectionId'] as String?`。
///
/// 附件上传需要这个 `connectionId`（`attachmentPut` 缺它时抛
/// `StateError('attachmentPut: missing connectionId')`）。
pub fn parse_hello_connection_id(hello: &Value) -> Result<Option<String>> {
    match hello.as_object() {
        Some(obj) => str_of(obj.get("connectionId")),
        None => Ok(None),
    }
}

/// 对应 `_start()` 里从订阅应答中取 `ack.subscriptionId` 的结果。
#[derive(Debug, Clone, PartialEq)]
pub struct SubscribeAck {
    /// `ack.subscriptionId`（缺失即为错误）。
    pub subscription_id: String,
    /// 原始 `ack` 映射（供 [`conversation_ack_log_epoch`] 之类的状态钩子使用）。
    pub ack: Option<JsonMap>,
}

/// 对应 `_start()`：`ack = (res as Map?)?['ack'] as Map?`；
/// `subscriptionId` 为 null 时抛 `StateError('$_subscribeMethod: missing ack.subscriptionId')`。
pub fn parse_subscribe_ack(res: &Value, subscribe_method: &str) -> Result<SubscribeAck> {
    let ack_value = res.as_object().and_then(|m| m.get("ack"));
    let ack = match ack_value {
        Some(Value::Object(m)) => Some(m.clone()),
        Some(Value::Null) | None => None,
        Some(other) => {
            return Err(ProtocolError::InvalidFrame(format!(
                "ack is not a map: {other}"
            )))
        }
    };
    let subscription_id = match ack.as_ref().and_then(|m| m.get("subscriptionId")) {
        Some(Value::String(s)) => Some(s.clone()),
        Some(Value::Null) | None => None,
        Some(other) => {
            return Err(ProtocolError::InvalidFrame(format!(
                "ack.subscriptionId is not a string: {other}"
            )))
        }
    };
    match subscription_id {
        Some(id) => Ok(SubscribeAck {
            subscription_id: id,
            ack,
        }),
        None => Err(ProtocolError::InvalidState(format!(
            "{subscribe_method}: missing ack.subscriptionId"
        ))),
    }
}

/// 对应 `ConversationSubscription._onSubscribeAck`：
/// `if (ack['logEpoch'] is String) state.logEpoch = ack['logEpoch']`。
pub fn conversation_ack_log_epoch(ack: &Value) -> Option<String> {
    ack.as_object()
        .and_then(|m| m.get("logEpoch"))
        .and_then(|v| v.as_str())
        .map(|s| s.to_string())
}

/// 对应 `switchModelConfig` 里 `res is Map ? '${res['message'] ?? ''}' : ''`。
pub fn response_message(res: &Value) -> String {
    match res.as_object() {
        Some(m) => interp_empty(m.get("message")),
        None => String::new(),
    }
}

/// 对应 `switchModelConfig` 的重试判定：`message.contains('Unsupported reasoning effort')`。
pub fn is_unsupported_reasoning_effort(message: &str) -> bool {
    message.contains("Unsupported reasoning effort")
}

/// 对应 `switchModelConfig` 的兜底思路级别：
/// `(thought == 'enabled' || thought == 'off') ? 'max' : 'enabled'`。
///
/// 各模型族的级别不同（GLM-5.2: max/high/nothink；Turbo: enabled/off），所以撞到
/// `Unsupported reasoning effort` 就换另一族的默认值重试。
pub fn switch_model_config_fallback_thought(thought: &str) -> &'static str {
    if thought == "enabled" || thought == "off" {
        "max"
    } else {
        "enabled"
    }
}

// ============================================================ 4. 附件（分块数学 + 纯读取状态机）

/// 对应 Dart `'upload-${generateUuid()}'`。
pub fn attachment_upload_id(uuid: &str) -> String {
    format!("upload-{uuid}")
}

/// 对应 Dart `'sha256:${sha256.convert(bytes).toString()}'`（小写十六进制）。
pub fn attachment_checksum(bytes: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(bytes);
    let digest = hasher.finalize();
    let mut hex = String::with_capacity(64);
    for b in digest.iter() {
        hex.push_str(&format!("{b:02x}"));
    }
    format!("sha256:{hex}")
}

/// 对应 Dart `(bytes.length + _attachmentChunkBytes - 1) ~/ _attachmentChunkBytes`。
pub fn attachment_total_chunks(total_bytes: usize) -> usize {
    (total_bytes + ATTACHMENT_CHUNK_BYTES - 1) / ATTACHMENT_CHUNK_BYTES
}

/// 对应 `attachmentPut` 里第 `n` 个分块的 `[start, end)` 区间（end 会夹到总长度）。
pub fn attachment_chunk_range(total_bytes: usize, chunk_index: usize) -> (usize, usize) {
    let start = chunk_index * ATTACHMENT_CHUNK_BYTES;
    let end = if start + ATTACHMENT_CHUNK_BYTES > total_bytes {
        total_bytes
    } else {
        start + ATTACHMENT_CHUNK_BYTES
    };
    (start, end)
}

/// 对应 `attachmentPut` 里 `begin` 应答的分支。
#[derive(Debug, Clone, PartialEq)]
pub enum AttachmentBegin {
    /// `beginRes['state'] == 'committed'`：服务端已有完整内容，直接返回描述符
    /// （Dart 里同时 `onProgress(1)`）。
    Committed {
        /// 对应 `beginRes['ref']`（Dart 不校验类型，可能为 null）。
        reference: Option<Value>,
    },
    /// 否则从 `nextChunkIndex ?? 0` 开始传分块。
    Chunked {
        /// 对应 `(beginRes['nextChunkIndex'] as num?)?.toInt() ?? 0`。
        next_chunk_index: usize,
    },
}

/// 对应 `attachmentPut` 的 begin 应答判定。
pub fn attachment_begin_decision(res: &Value) -> Result<AttachmentBegin> {
    let obj = res.as_object();
    if obj.and_then(|m| m.get("state")).and_then(|v| v.as_str()) == Some("committed") {
        return Ok(AttachmentBegin::Committed {
            reference: obj.and_then(|m| m.get("ref")).cloned(),
        });
    }
    let next = match obj {
        Some(m) => num_of(m.get("nextChunkIndex"))?.unwrap_or(0),
        None => 0,
    };
    Ok(AttachmentBegin::Chunked {
        next_chunk_index: next.max(0) as usize,
    })
}

/// 对应 `attachmentPut` 分块循环里的进度校验：
/// `if (nextChunk != n + 1) throw StateError('fault.attachment.invalidServerProgress')`。
pub fn validate_attachment_progress(chunk_index: usize, next_chunk_index: usize) -> Result<()> {
    if next_chunk_index != chunk_index + 1 {
        return Err(ProtocolError::InvalidState(
            "fault.attachment.invalidServerProgress".to_string(),
        ));
    }
    Ok(())
}

/// 对应 `onProgress?.call(nextChunk / totalChunks)`（Dart 的 `/` 是浮点除法）。
pub fn attachment_progress(next_chunk_index: usize, total_chunks: usize) -> f64 {
    next_chunk_index as f64 / total_chunks as f64
}

/// 对应 `attachmentPut` 返回的描述符 `{ref, fileName, mime, bytes}`（`ref` 键始终存在，可能为 null）。
pub fn attachment_descriptor(
    reference: Option<&Value>,
    file_name: &str,
    mime: &str,
    bytes: usize,
) -> Value {
    json!({
        "ref": reference.cloned().unwrap_or(Value::Null),
        "fileName": file_name,
        "mime": mime,
        "bytes": bytes,
    })
}

/// 对应 `attachmentPut` 的 begin 请求体
/// （`{...scope, ...base, fileName, mime, totalBytes, totalChunks, checksum}`）。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct AttachmentBeginRequest {
    /// 对应 `connectionId`（来自服务端 hello）。
    #[serde(rename = "connectionId")]
    pub connection_id: String,
    /// 对应 `uploadId`（[`attachment_upload_id`]）。
    #[serde(rename = "uploadId")]
    pub upload_id: String,
    /// 对应 `sessionId`。
    #[serde(rename = "sessionId")]
    pub session_id: String,
    /// 对应 `fileName`。
    #[serde(rename = "fileName")]
    pub file_name: String,
    /// 对应 `mime`。
    pub mime: String,
    /// 对应 `totalBytes`。
    #[serde(rename = "totalBytes")]
    pub total_bytes: usize,
    /// 对应 `totalChunks`（[`attachment_total_chunks`]）。
    #[serde(rename = "totalChunks")]
    pub total_chunks: usize,
    /// 对应 `checksum`（[`attachment_checksum`]）。
    pub checksum: String,
}

/// 对应 `attachmentPut` 里的 `base = {connectionId, uploadId, sessionId}`。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct AttachmentBase {
    /// 对应 `connectionId`。
    #[serde(rename = "connectionId")]
    pub connection_id: String,
    /// 对应 `uploadId`。
    #[serde(rename = "uploadId")]
    pub upload_id: String,
    /// 对应 `sessionId`。
    #[serde(rename = "sessionId")]
    pub session_id: String,
}

/// 对应 `attachmentBeginV4` 的参数：`{...scope, ...base, fileName, mime, totalBytes, totalChunks, checksum}`。
pub fn attachment_begin_args(scope: &JsonMap, req: &AttachmentBeginRequest) -> Value {
    let mut m = scope.clone();
    m.insert(
        "connectionId".to_string(),
        Value::String(req.connection_id.clone()),
    );
    m.insert("uploadId".to_string(), Value::String(req.upload_id.clone()));
    m.insert("sessionId".to_string(), Value::String(req.session_id.clone()));
    m.insert("fileName".to_string(), Value::String(req.file_name.clone()));
    m.insert("mime".to_string(), Value::String(req.mime.clone()));
    m.insert("totalBytes".to_string(), json!(req.total_bytes));
    m.insert("totalChunks".to_string(), json!(req.total_chunks));
    m.insert("checksum".to_string(), Value::String(req.checksum.clone()));
    Value::Object(m)
}

/// 对应 `attachmentChunkV4` 的参数：`{...scope, ...base, chunkIndex, dataBase64}`。
pub fn attachment_chunk_args(
    scope: &JsonMap,
    base: &AttachmentBase,
    chunk_index: usize,
    data_base64: &str,
) -> Value {
    let mut m = scope.clone();
    m.insert(
        "connectionId".to_string(),
        Value::String(base.connection_id.clone()),
    );
    m.insert("uploadId".to_string(), Value::String(base.upload_id.clone()));
    m.insert(
        "sessionId".to_string(),
        Value::String(base.session_id.clone()),
    );
    m.insert("chunkIndex".to_string(), json!(chunk_index));
    m.insert(
        "dataBase64".to_string(),
        Value::String(data_base64.to_string()),
    );
    Value::Object(m)
}

/// 对应 `attachmentCommitV4` 的参数：`{...scope, ...base}`。
pub fn attachment_commit_args(scope: &JsonMap, base: &AttachmentBase) -> Value {
    let mut m = scope.clone();
    m.insert(
        "connectionId".to_string(),
        Value::String(base.connection_id.clone()),
    );
    m.insert("uploadId".to_string(), Value::String(base.upload_id.clone()));
    m.insert(
        "sessionId".to_string(),
        Value::String(base.session_id.clone()),
    );
    Value::Object(m)
}

/// 对应 `attachmentReadV4` 的参数：`{...scope, sessionId, ref, offset, limit}`
/// （limit 固定为 [`ATTACHMENT_READ_LIMIT_BYTES`] = 512 KiB，与网页端一致）。
pub fn attachment_read_args(
    scope: &JsonMap,
    session_id: &str,
    reference: &str,
    offset: i64,
) -> Value {
    let mut m = scope.clone();
    m.insert("sessionId".to_string(), Value::String(session_id.to_string()));
    m.insert("ref".to_string(), Value::String(reference.to_string()));
    m.insert("offset".to_string(), json!(offset));
    m.insert("limit".to_string(), json!(ATTACHMENT_READ_LIMIT_BYTES));
    Value::Object(m)
}

/// 对应 Dart `attachmentRead` 的读取循环状态（纯逻辑，通道调用由宿主做）。
///
/// 宿主用法：
/// `while reader.should_continue() { let res = call(reader.request_args(...)); reader.feed(&res)?; }`
/// 最后取 [`AttachmentReader::bytes`] / [`AttachmentReader::media_type`]。
#[derive(Debug, Clone, Default)]
pub struct AttachmentReader {
    /// 累积字节（对应 Dart 的 `chunks`）。
    pub bytes: Vec<u8>,
    /// 对应 `mediaType ??= res['mediaType'] as String?`（只在首次取到非 null 时赋值）。
    pub media_type: Option<String>,
    /// 对应 `offset`。
    pub offset: i64,
    /// 是否已终止循环。
    pub done: bool,
    /// 已消耗的轮数（上限 [`ATTACHMENT_MAX_READ_ROUNDS`]）。
    pub rounds: usize,
}

impl AttachmentReader {
    /// 对应 `var chunks = <int>[]; var offset = 0; String? mediaType;`。
    pub fn new() -> Self {
        Self::default()
    }

    /// 循环条件：未读完且未超过 [`ATTACHMENT_MAX_READ_ROUNDS`]（网页端 60 轮）。
    pub fn should_continue(&self) -> bool {
        !self.done && self.rounds < ATTACHMENT_MAX_READ_ROUNDS
    }

    /// 对应 `attachmentReadV4` 的参数（每次循环都用当前 `offset`）。
    pub fn request_args(&self, scope: &JsonMap, session_id: &str, reference: &str) -> Value {
        attachment_read_args(scope, session_id, reference, self.offset)
    }

    /// 消费一次 `attachmentReadV4` 应答（对应循环体：`res is! Map` 跳出、`mediaType ??=`、
    /// `dataBase64` 累加、`nextOffset`/`totalBytes` 终止判定）。
    pub fn feed(&mut self, res: &Value) -> Result<()> {
        self.rounds += 1;
        let Some(obj) = res.as_object() else {
            self.done = true;
            return Ok(());
        };
        if self.media_type.is_none() {
            self.media_type = str_of(obj.get("mediaType"))?;
        }
        if let Some(data) = str_of(obj.get("dataBase64"))? {
            if !data.is_empty() {
                self.bytes.extend_from_slice(&decode_base64(&data)?);
            }
        }
        let next = num_of(obj.get("nextOffset"))?;
        let total = num_of(obj.get("totalBytes"))?;
        let Some(next) = next else {
            self.done = true;
            return Ok(());
        };
        if next <= self.offset {
            self.done = true;
            return Ok(());
        }
        self.offset = next;
        if let Some(total) = total {
            if self.offset >= total {
                self.done = true;
            }
        }
        Ok(())
    }
}

// ============================================================ 5. 逻辑帧分片重组

/// 对应 Dart `_LogicalFrameAssembly`。
#[derive(Debug, Clone, PartialEq)]
pub struct LogicalFrameAssembly {
    /// 对应 `count`。
    pub count: usize,
    /// 对应 `parts`（`List<Uint8List?>`）。
    pub parts: Vec<Option<Vec<u8>>>,
    /// 对应 `received`。
    pub received: usize,
    /// 对应 `createdAt`（Dart 用 `DateTime.now()`；这里由宿主传入 epoch 毫秒）。
    pub created_at_ms: i64,
}

impl LogicalFrameAssembly {
    /// 对应 `_LogicalFrameAssembly(this.count) : parts = List.filled(count, null)`。
    pub fn new(count: usize, now_ms: i64) -> Self {
        Self {
            count,
            parts: vec![None; count],
            received: 0,
            created_at_ms: now_ms,
        }
    }

    /// 对应 `add(index, data)`：越界忽略；首次填坑时 `received += 1`。返回是否是新填的坑。
    pub fn add(&mut self, index: usize, data: Vec<u8>) -> bool {
        if index >= self.count {
            return false;
        }
        let fresh = self.parts[index].is_none();
        if fresh {
            self.received += 1;
        }
        self.parts[index] = Some(data);
        fresh
    }

    /// 对应 `bool get isComplete => received == count`。
    pub fn is_complete(&self) -> bool {
        self.received == self.count
    }

    /// 对应 `assemble()`：按 index 顺序拼接**已收到**的部分（缺失的坑跳过）。
    pub fn assemble(&self) -> Vec<u8> {
        let mut out = Vec::new();
        for p in self.parts.iter().flatten() {
            out.extend_from_slice(p);
        }
        out
    }
}

/// 对应 `_purgeFragments` 的判定：`now.difference(createdAt).inSeconds > 60`。
///
/// 注意 Dart 的 `inSeconds` 是截断取整，所以真实阈值是「已过 ≥ 61 秒」。
pub fn fragment_is_stale(now_ms: i64, created_at_ms: i64) -> bool {
    (now_ms - created_at_ms) / 1000 > FRAGMENT_STALE_SECS
}

/// 对应 `_acceptFragment` 的几种出口。
#[derive(Debug, Clone, PartialEq)]
pub enum FragmentOutcome {
    /// 丢弃：字段缺失/类型不符以外的情形——越界、`fragmentCount` 与已有装配体不一致、
    /// base64 非法（Dart 里 log 后丢弃）。
    Ignored,
    /// 已收下分片但还没凑齐。
    Pending,
    /// 凑齐并成功解析成 JSON 对象——对应 `_acceptOrStage(decoded.cast<String,dynamic>())`。
    Complete(Value),
    /// 凑齐但 JSON 解析失败 / 非法 UTF-8——Dart 记日志后丢弃。
    BadJson(String),
    /// 凑齐、解析成功，但顶层不是对象——Dart 静默丢弃（不记日志）。
    DecodedNonMap,
}

/// 对应 Dart `_acceptFragment` + `_fragments` 表。
#[derive(Debug, Clone, Default)]
pub struct FragmentAssembler {
    frames: BTreeMap<String, LogicalFrameAssembly>,
}

impl FragmentAssembler {
    /// 对应 `final _fragments = <String, _LogicalFrameAssembly>{};`。
    pub fn new() -> Self {
        Self::default()
    }

    /// 对应 `_fragments.clear()`。
    pub fn clear(&mut self) {
        self.frames.clear();
    }

    /// 装配体数量（测试用）。
    pub fn len(&self) -> usize {
        self.frames.len()
    }

    /// 是否没有在途装配体。
    pub fn is_empty(&self) -> bool {
        self.frames.is_empty()
    }

    /// 对应 `_acceptFragment(frame)`。
    ///
    /// `now_ms` 用于给新装配体打 `createdAt`（Dart 用 `DateTime.now()`，本 crate 无时钟）。
    pub fn accept(&mut self, frame: &JsonMap, now_ms: i64) -> Result<FragmentOutcome> {
        let id = str_of(frame.get("logicalFrameId"))?;
        let index = num_of(frame.get("fragmentIndex"))?;
        let count = num_of(frame.get("fragmentCount"))?;
        let data_base64 = str_of(frame.get("dataBase64"))?;
        let (Some(id), Some(index), Some(count), Some(data_base64)) = (id, index, count, data_base64)
        else {
            return Ok(FragmentOutcome::Ignored);
        };
        if count < 1 || count > FRAGMENT_MAX_COUNT || index < 0 || index >= count {
            return Ok(FragmentOutcome::Ignored);
        }
        let count = count as usize;
        let mismatch = self
            .frames
            .get(&id)
            .map(|existing| existing.count != count)
            .unwrap_or(false);
        if mismatch {
            self.frames.remove(&id);
            return Ok(FragmentOutcome::Ignored);
        }
        self.frames
            .entry(id.clone())
            .or_insert_with(|| LogicalFrameAssembly::new(count, now_ms));
        let bytes = match decode_base64(&data_base64) {
            Ok(b) => b,
            Err(_) => {
                self.frames.remove(&id);
                return Ok(FragmentOutcome::Ignored);
            }
        };
        if let Some(assembly) = self.frames.get_mut(&id) {
            assembly.add(index as usize, bytes);
        }
        let complete = self
            .frames
            .get(&id)
            .map(|a| a.is_complete())
            .unwrap_or(false);
        if !complete {
            return Ok(FragmentOutcome::Pending);
        }
        let assembly = match self.frames.remove(&id) {
            Some(a) => a,
            None => return Ok(FragmentOutcome::Ignored),
        };
        let text = match String::from_utf8(assembly.assemble()) {
            Ok(t) => t,
            Err(e) => return Ok(FragmentOutcome::BadJson(e.to_string())),
        };
        match serde_json::from_str::<Value>(&text) {
            Err(e) => Ok(FragmentOutcome::BadJson(e.to_string())),
            Ok(v) if v.is_object() => Ok(FragmentOutcome::Complete(v)),
            Ok(_) => Ok(FragmentOutcome::DecodedNonMap),
        }
    }

    /// 对应 `_purgeFragments`：清掉超时未凑齐的装配体，返回被清掉的 id（供宿主记日志）。
    pub fn purge_stale(&mut self, now_ms: i64) -> Vec<String> {
        let stale: Vec<String> = self
            .frames
            .iter()
            .filter(|(_, a)| fragment_is_stale(now_ms, a.created_at_ms))
            .map(|(id, _)| id.clone())
            .collect();
        for id in &stale {
            self.frames.remove(id);
        }
        stale
    }
}

/// 对应 `_handleWireFrame` 的三种路由结果。
#[derive(Debug, Clone, PartialEq)]
pub enum WireFrameRoute<'a> {
    /// 直接忽略：不是 Map、topic 不符、kind 未知、或 complete 帧里没有 `frame` 映射。
    Ignored,
    /// `kind == 'complete'` 且 `frame` 是映射——内层逻辑帧。
    Complete(&'a JsonMap),
    /// `kind == 'fragment'`——外层分片帧（要交给 [`FragmentAssembler::accept`]）。
    Fragment(&'a JsonMap),
}

/// 对应 `_handleWireFrame` 的 topic / kind 路由。
pub fn route_wire_frame<'a>(expected_topic: &str, frame: &'a JsonMap) -> WireFrameRoute<'a> {
    if frame.get("topic").and_then(|v| v.as_str()) != Some(expected_topic) {
        return WireFrameRoute::Ignored;
    }
    match frame.get("kind").and_then(|v| v.as_str()) {
        Some("complete") => match frame.get("frame").and_then(|v| v.as_object()) {
            Some(inner) => WireFrameRoute::Complete(inner),
            None => WireFrameRoute::Ignored,
        },
        Some("fragment") => WireFrameRoute::Fragment(frame),
        _ => WireFrameRoute::Ignored,
    }
}

/// 对应 `_acceptLogicalFrame` 开头的过滤：
/// `if (subId == null || frame['subscriptionId'] != subId) return;`。
pub fn frame_matches_subscription(frame: &JsonMap, subscription_id: &str) -> bool {
    frame.get("subscriptionId").and_then(|v| v.as_str()) == Some(subscription_id)
}

// ============================================================ 6. 订阅核心（纯状态）+ 订阅规格

/// 对应 `_SubscriptionBase` 里不含 I/O 的那部分状态：
/// 订阅 id、订阅应答前暂存的帧、分片表、dispose/resync 标志。
///
/// 宿主驱动方式：[`SubscriptionCore::handle_wire_frame`] 返回「该交给
/// `state.apply_frame()` 的逻辑帧」，然后由宿主自己调 `apply_frame`，
/// 并在 gap 回调里调 [`SubscriptionCore::can_resync`] → 发 resync 请求。
#[derive(Debug, Clone, Default)]
pub struct SubscriptionCore {
    /// 对应 `_subscriptionId`。
    pub subscription_id: Option<String>,
    /// 对应 `_stagedFrames`（订阅应答到达前的帧先存着）。
    pub staged_frames: Vec<JsonMap>,
    /// 对应 `_fragments`。
    pub fragments: FragmentAssembler,
    /// 对应 `_disposed`。
    pub disposed: bool,
    /// 对应 `_resyncing`。
    pub resyncing: bool,
}

impl SubscriptionCore {
    /// 对应 `_SubscriptionBase` 的字段初值。
    pub fn new() -> Self {
        Self::default()
    }

    /// 对应 `_acceptOrStage`：订阅 id 还没有就暂存，返回 `None`；否则返回该立即应用的帧。
    pub fn accept_or_stage(&mut self, frame: JsonMap) -> Option<JsonMap> {
        if self.subscription_id.is_none() {
            self.staged_frames.push(frame);
            return None;
        }
        Some(frame)
    }

    /// 对应 `_start()` 成功后 `final staged = List.from(_stagedFrames); _stagedFrames.clear();`
    /// 以及随后逐帧 `_acceptLogicalFrame`。
    pub fn drain_staged(&mut self) -> Vec<JsonMap> {
        std::mem::take(&mut self.staged_frames)
    }

    /// 对应 `_handleWireFrame` → `_acceptOrStage` → `_acceptFragment` 的全链路，
    /// 返回需要宿主立即 `apply_frame` 的逻辑帧（未订阅时它们进了 `staged_frames`）。
    pub fn handle_wire_frame(
        &mut self,
        expected_topic: &str,
        frame: &Value,
        now_ms: i64,
    ) -> Result<Vec<JsonMap>> {
        if self.disposed {
            return Ok(Vec::new());
        }
        let Some(obj) = frame.as_object() else {
            return Ok(Vec::new());
        };
        match route_wire_frame(expected_topic, obj) {
            WireFrameRoute::Ignored => Ok(Vec::new()),
            WireFrameRoute::Complete(inner) => {
                Ok(self.accept_or_stage(inner.clone()).into_iter().collect())
            }
            WireFrameRoute::Fragment(outer) => match self.fragments.accept(outer, now_ms)? {
                FragmentOutcome::Complete(value) => match value.as_object().cloned() {
                    Some(map) => Ok(self.accept_or_stage(map).into_iter().collect()),
                    None => Ok(Vec::new()),
                },
                _ => Ok(Vec::new()),
            },
        }
    }

    /// 对应 `_purgeFragments()`：disposed 时直接返回；返回被清掉的 id（宿主负责记日志）。
    pub fn purge_fragments(&mut self, now_ms: i64) -> Vec<String> {
        if self.disposed {
            return Vec::new();
        }
        self.fragments.purge_stale(now_ms)
    }

    /// 对应 `_start()` 的 catch 分支：
    /// `_subscriptionId = null; _stagedFrames.clear(); _fragments.clear();`（`_cancelFrameListener` 属宿主）。
    pub fn on_start_failure(&mut self) {
        self.subscription_id = None;
        self.staged_frames.clear();
        self.fragments.clear();
    }

    /// 对应 `_resubscribe()` 里
    /// `_subscriptionId = null; _stagedFrames.clear(); _fragments.clear();`（旧订阅的 unsubscribe 属宿主）。
    ///
    /// 返回被清掉的旧订阅 id（宿主用它发 unsubscribe）。
    pub fn on_resubscribe_cleanup(&mut self) -> Option<String> {
        let old = self.subscription_id.take();
        self.staged_frames.clear();
        self.fragments.clear();
        old
    }

    /// 对应 `_start()` 里 `_subscriptionId = ack?['subscriptionId'] as String?`。
    pub fn set_subscription_id(&mut self, id: impl Into<String>) {
        self.subscription_id = Some(id.into());
    }

    /// 对应 `_resync()` 的门控：`if (id == null || _disposed || _resyncing) return;`。
    pub fn can_resync(&self) -> bool {
        self.subscription_id.is_some() && !self.disposed && !self.resyncing
    }

    /// 对应 `_resyncing = true;` / `finally { _resyncing = false; }`。
    pub fn set_resyncing(&mut self, value: bool) {
        self.resyncing = value;
    }

    /// 对应 `dispose()` 的 `_disposed = true;`（取消定时器、发 unsubscribe 属宿主）。
    pub fn mark_disposed(&mut self) {
        self.disposed = true;
    }

    /// 对应 `_resubscribe` 失败后
    /// `Timer(3s, () { if (!_disposed && _subscriptionId == null) _resubscribe(); })` 里的重试条件。
    pub fn should_retry_resubscribe(&self) -> bool {
        !self.disposed && self.subscription_id.is_none()
    }
}

/// 对应 `ConversationSubscription._frameEventName`。
pub const CONVERSATION_FRAME_EVENT: &str = "onDynamicConversationFrame";
/// 对应 `ConversationSubscription._subscribeMethod`。
pub const CONVERSATION_SUBSCRIBE_METHOD: &str = "subscribeConversationV4";
/// 对应 `ConversationSubscription._unsubscribeMethod`。
pub const CONVERSATION_UNSUBSCRIBE_METHOD: &str = "unsubscribeConversationV4";
/// 对应 `ConversationSubscription._resyncMethod`。
pub const CONVERSATION_RESYNC_METHOD: &str = "resyncConversationV4";
/// 对应 `SessionsIndexSubscription._frameEventName`。
pub const SESSIONS_INDEX_FRAME_EVENT: &str = "onDynamicSessionsIndexFrame";
/// 对应 `SessionsIndexSubscription._subscribeMethod`。
pub const SESSIONS_INDEX_SUBSCRIBE_METHOD: &str = "subscribeSessionsIndexV4";
/// 对应 `SessionsIndexSubscription._unsubscribeMethod`。
pub const SESSIONS_INDEX_UNSUBSCRIBE_METHOD: &str = "unsubscribeSessionsIndexV4";
/// 对应 `SessionsIndexSubscription._resyncMethod`。
pub const SESSIONS_INDEX_RESYNC_METHOD: &str = "resyncSessionsIndexV4";
/// 对应 sessions-index 的 `_subscribeArgs`/`_unsubscribeArgs`/`_resyncArgs`：
/// `{'runtimePolicy': 'existing-only'}`。
pub const SESSIONS_INDEX_RUNTIME_POLICY: &str = "existing-only";

/// 对应 `ConversationSubscription.topic`：`'conversation/$sessionId'`。
pub fn conversation_topic(session_id: &str) -> String {
    format!("conversation/{session_id}")
}

/// 对应 `SessionsIndexSubscription.topic`：
/// `'sessions-index/${scope['workspaceIdentity'] ?? scope['workspacePath']}'`
/// （两者都缺时 Dart 插值成字面量 `null`）。
pub fn sessions_index_topic(scope: &JsonMap) -> String {
    let v = match scope.get("workspaceIdentity") {
        Some(v) if !v.is_null() => Some(v),
        _ => scope.get("workspacePath"),
    };
    format!("sessions-index/{}", interp(v))
}

/// 对应 conversation 的 `_subscribeArgs`：`{'sessionId': sessionId}`。
pub fn conversation_subscribe_args(session_id: &str) -> JsonMap {
    let mut m = JsonMap::new();
    m.insert("sessionId".to_string(), Value::String(session_id.to_string()));
    m
}

/// 对应 sessions-index 的 `_subscribeArgs`：`{'runtimePolicy': 'existing-only'}`。
pub fn sessions_index_subscribe_args() -> JsonMap {
    let mut m = JsonMap::new();
    m.insert(
        "runtimePolicy".to_string(),
        Value::String(SESSIONS_INDEX_RUNTIME_POLICY.to_string()),
    );
    m
}

/// 对应 conversation 的 `_resyncArgs`：`{'forceSnapshot': true}`。
pub fn conversation_resync_args() -> JsonMap {
    let mut m = JsonMap::new();
    m.insert("forceSnapshot".to_string(), Value::Bool(true));
    m
}

/// 对应 `_start()` 的 `[{..._transport.scope, ..._subscribeArgs}]`。
pub fn subscribe_request_args(scope: &JsonMap, extra: &JsonMap) -> Value {
    Value::Object(merge_maps(scope, extra))
}

/// 对应 unsubscribe 的 `[{...scope, 'subscriptionId': oldId, ..._unsubscribeArgs}]`。
pub fn unsubscribe_request_args(
    scope: &JsonMap,
    subscription_id: &str,
    extra: &JsonMap,
) -> Value {
    let mut m = scope.clone();
    m.insert(
        "subscriptionId".to_string(),
        Value::String(subscription_id.to_string()),
    );
    for (k, v) in extra {
        m.insert(k.clone(), v.clone());
    }
    Value::Object(m)
}

/// 对应 `_resync()` 的请求参数：
/// `{...scope, 'subscriptionId': id, ..._resyncArgs, if (logEpoch != null) 'base': {'logEpoch': ..., 'seq': ...}}`。
pub fn resync_request_args(
    scope: &JsonMap,
    subscription_id: &str,
    extra: &JsonMap,
    log_epoch: Option<&str>,
    seq: i64,
) -> Value {
    let mut m = scope.clone();
    m.insert(
        "subscriptionId".to_string(),
        Value::String(subscription_id.to_string()),
    );
    for (k, v) in extra {
        m.insert(k.clone(), v.clone());
    }
    if let Some(epoch) = log_epoch {
        m.insert("base".to_string(), json!({"logEpoch": epoch, "seq": seq}));
    }
    Value::Object(m)
}

/// 对应 `ConversationSubscription._startWatchdog` 的判定：
/// 静默 ≥ [`WATCHDOG_QUIET_SECS`] 秒且（会话在跑或有行处于 `streaming`）→ resync。
pub fn watchdog_should_resync(quiet_seconds: i64, is_running: bool, has_streaming_row: bool) -> bool {
    if quiet_seconds < WATCHDOG_QUIET_SECS {
        return false;
    }
    is_running || has_streaming_row
}

// ============================================================ 7. ConversationState

/// 对应 Dart `ConversationState`——会话快照 + 行状态（网页端 `fke()`/`pke()` 的增量应用）。
///
/// 与 Dart 的差异：Dart 是 `ChangeNotifier`，每次成功应用后 `notifyListeners()`；
/// 本结构没有监听器，宿主在方法返回后自行通知 UI（各方法文档里注明了对应点）。
#[derive(Debug, Clone, Default)]
pub struct ConversationState {
    /// 对应 `snapshot`（顶层快照对象）。
    pub snapshot: Option<JsonMap>,
    /// 对应 `rows`。
    pub rows: Vec<JsonMap>,
    /// 对应 `seq`。
    pub seq: i64,
    /// 对应 `logEpoch`。
    pub log_epoch: Option<String>,
    /// 对应 `firstRowId`（快照投影头，不是分页游标；分页游标见 [`ConversationState::oldest_row_id`]）。
    pub first_row_id: Option<i64>,
    /// 对应 `totalCount`。
    pub total_count: i64,
    /// 对应 `ready`。
    pub ready: bool,
    /// 对应 `historyExhausted`：**由 UI 层写**（`lib/ui/chat_page.dart` 在 `rowsRange` 返回
    /// `hasMore == false` 时置 true），协议层只读它算 [`ConversationState::can_load_older`]。
    pub history_exhausted: bool,
    /// 对应私有字段 `_pendingPatch`：快照还没到时先攒着的 `state.updated` 补丁。
    pending_patch: Option<JsonMap>,
}

impl ConversationState {
    /// 对应 Dart 的字段初值（`ConversationState()`）。
    pub fn new() -> Self {
        Self::default()
    }

    /// 对应 `applyFrame(frame, onGap: ...)`。
    ///
    /// * `payload` 不是 Map → 直接返回（`ready` 不动、不通知）；
    /// * `kind == 'snapshot'` → 走 [`ConversationState::apply_snapshot`]（`snapshot` 缺失/非 Map 时
    ///   Dart 抛 `TypeError`，这里返回 `Err`）；
    /// * `kind == 'deltas'` → `fromSeq != seq` 时调 `on_gap()` 并**提前返回**（`ready`/`seq` 都不动）；
    /// * 结束时 `ready = true`（Dart 在这里 `notifyListeners()`）。
    pub fn apply_frame(&mut self, frame: &Value, on_gap: &mut dyn FnMut()) -> Result<()> {
        let Some(frame_obj) = frame.as_object() else {
            return Ok(());
        };
        let Some(payload) = frame_obj.get("payload").and_then(|v| v.as_object()) else {
            return Ok(());
        };
        let to_seq = num_of(frame_obj.get("toSeq"))?.unwrap_or(self.seq);

        match payload.get("kind").and_then(|v| v.as_str()) {
            Some("snapshot") => {
                let snap = match payload.get("snapshot").and_then(|v| v.as_object()) {
                    Some(s) => s.clone(),
                    None => {
                        return Err(ProtocolError::InvalidFrame(
                            "snapshot payload is missing a 'snapshot' map".to_string(),
                        ))
                    }
                };
                self.apply_snapshot(&snap, to_seq)?;
            }
            Some("deltas") => {
                let from_seq = num_of(frame_obj.get("fromSeq"))?.unwrap_or(self.seq);
                if from_seq != self.seq {
                    on_gap();
                    return Ok(());
                }
                if let Some(Value::Array(deltas)) = payload.get("deltas") {
                    for d in deltas {
                        if let Some(obj) = d.as_object() {
                            self.apply_delta(obj)?;
                        }
                    }
                }
                self.seq = to_seq;
            }
            _ => {}
        }
        self.ready = true;
        Ok(())
    }

    /// 对应私有 `_applySnapshot(snap, toSeq)`（这里公开，便于宿主在 resync / rowsRange 时直接喂快照）。
    ///
    /// 关键点（最容易漂移）：`logEpoch` / `rows` 都从**原始** `snap` 读，不是从合并了
    /// `_pendingPatch` 的那份读；`totalCount` 的缺省值是**合并后**的 `rows.length`。
    pub fn apply_snapshot(&mut self, snap: &JsonMap, to_seq: i64) -> Result<()> {
        let mut merged = snap.clone();
        if let Some(patch) = self.pending_patch.take() {
            for (k, v) in patch {
                merged.insert(k, v);
            }
        }
        self.snapshot = Some(merged);
        self.seq = to_seq;
        self.log_epoch = str_of(snap.get("logEpoch"))?;

        match snap.get("rows").and_then(|v| v.as_object()) {
            Some(rows_obj) => {
                match rows_obj.get("window") {
                    Some(Value::Array(window)) => {
                        let window_rows: Vec<JsonMap> = window
                            .iter()
                            .filter_map(|e| e.as_object().cloned())
                            .collect();
                        let head = match window_rows.first() {
                            Some(first) => num_of(first.get("rowId"))?,
                            None => None,
                        };
                        let mut rows: Vec<JsonMap> = match head {
                            None => Vec::new(),
                            Some(head) => {
                                let mut older = Vec::new();
                                for r in &self.rows {
                                    if let Some(id) = num_of(r.get("rowId"))? {
                                        if id < head {
                                            older.push(r.clone());
                                        }
                                    }
                                }
                                older
                            }
                        };
                        rows.extend(window_rows);
                        self.rows = rows;
                    }
                    _ => {
                        self.rows = Vec::new();
                    }
                }
                self.total_count = match num_of(rows_obj.get("totalCount"))? {
                    Some(n) => n,
                    None => self.rows.len() as i64,
                };
                self.first_row_id = num_of(rows_obj.get("firstRowId"))?;
            }
            None => {
                self.rows = Vec::new();
                self.total_count = 0;
                self.first_row_id = None;
            }
        }
        Ok(())
    }

    /// 对应 `_applyDelta(delta)`（网页端 `fke()` 的增量分支）。
    fn apply_delta(&mut self, delta: &JsonMap) -> Result<()> {
        match delta.get("op").and_then(|v| v.as_str()) {
            Some("row.appended") => {
                let row = require_object(delta.get("row"), "row.appended: 'row'")?.clone();
                self.rows.push(row.clone());
                self.total_count += 1;
                if self.first_row_id.is_none() {
                    self.first_row_id = num_of(row.get("rowId"))?;
                }
            }
            Some("row.upserted") => {
                let row = require_object(delta.get("row"), "row.upserted: 'row'")?.clone();
                let id = num_of(row.get("rowId"))?;
                let mut found = None;
                for (i, r) in self.rows.iter().enumerate() {
                    if num_of(r.get("rowId"))? == id {
                        found = Some(i);
                        break;
                    }
                }
                // PORT-NOTE: Dart 里找不到就什么都不做（**不**追加）；且 rowId 缺失(null)时
                // `null == null` 成立，会命中第一条 rowId 也缺失的行——这里照抄该比较语义。
                if let Some(i) = found {
                    self.rows[i] = row.clone();
                }
            }
            Some("row.removed") => {
                // 对应网页端 `fke()`：保留 rowId < fromRowId 的行（即删掉 >= fromRowId 的）。
                let from_row_id = num_of(delta.get("fromRowId"))?.unwrap_or(0);
                let mut kept = Vec::new();
                for r in &self.rows {
                    if num_of(r.get("rowId"))?.unwrap_or(0) < from_row_id {
                        kept.push(r.clone());
                    }
                }
                let removed = self.rows.len() as i64 - kept.len() as i64;
                self.rows = kept;
                match self.first_row_id {
                    Some(first) if from_row_id <= first => {
                        self.total_count = 0;
                        self.first_row_id = None;
                    }
                    _ => {
                        self.total_count = (self.total_count - removed).clamp(0, 1 << 31);
                    }
                }
            }
            Some("row.delta") => {
                let row_id = num_of(delta.get("rowId"))?;
                let path = str_of(delta.get("path"))?;
                let append = str_of(delta.get("append"))?.unwrap_or_default();
                let mut found = None;
                for (i, r) in self.rows.iter().enumerate() {
                    if num_of(r.get("rowId"))? == row_id {
                        found = Some(i);
                        break;
                    }
                }
                if let Some(i) = found {
                    let updated = append_to_row(&self.rows[i], path.as_deref(), &append);
                    self.rows[i] = updated;
                }
            }
            Some("state.updated") => {
                if let Some(patch) = delta.get("patch").and_then(|v| v.as_object()) {
                    if let Some(snap) = self.snapshot.as_mut() {
                        for (k, v) in patch {
                            snap.insert(k.clone(), v.clone());
                        }
                    } else {
                        // 补丁先到：攒起来，等快照落地时合并
                        // （否则 config/queue/control 的更新会静默丢失）。
                        let mut pending = self.pending_patch.take().unwrap_or_default();
                        for (k, v) in patch {
                            pending.insert(k.clone(), v.clone());
                        }
                        self.pending_patch = Some(pending);
                    }
                }
            }
            // 其余 op 是**有意不处理**，不是漏写：
            // - `pluginUi.resourceUpdated` / `resourceListChanged` / `instanceClosed` /
            //   `appToolCall`：只影响插件渲染的面板，本项目没有插件 UI 层；
            // - `workflowRun.updated` / `removed`：我们不在 `clientHello.capabilities` 里
            //   声明 `workflowRunDeltas`，服务端会把它们合并成一条 `state.updated`，
            //   所以这两种 op **根本不会到达**。
            //
            // 未知 op 静默忽略是刻意的：对话流是增量协议，多认识一个 op 不该打断整条流。
            _ => {}
        }
        Ok(())
    }

    /// 对应 `optimisticPatch(patch)`：命令已被接受，确认帧可能迟到；立即并进快照。
    ///
    /// `snapshot == null` 时 Dart 直接返回。
    pub fn optimistic_patch(&mut self, patch: &JsonMap) {
        if let Some(snap) = self.snapshot.as_mut() {
            for (k, v) in patch {
                snap.insert(k.clone(), v.clone());
            }
        }
    }

    /// 对应 `optimisticRowUpdate(rowId, patch)`：就地改行（服务端的 `row.upserted` 随后确认）。
    pub fn optimistic_row_update(&mut self, row_id: Option<i64>, patch: &JsonMap) -> Result<()> {
        let mut found = None;
        for (i, r) in self.rows.iter().enumerate() {
            if num_of(r.get("rowId"))? == row_id {
                found = Some(i);
                break;
            }
        }
        if let Some(i) = found {
            let mut row = self.rows[i].clone();
            for (k, v) in patch {
                row.insert(k.clone(), v.clone());
            }
            self.rows[i] = row;
        }
        Ok(())
    }

    /// 对应 `optimisticRemoveQueueItem(queueItemId)`（`sendQueuedNow` / `deleteQueueItem` 被接受后）。
    ///
    /// 注意 Dart 的过滤条件：非 Map 的元素**也被丢掉**；`'${i['queueItemId']}'` 会把缺失值插值成 `"null"`。
    pub fn optimistic_remove_queue_item(&mut self, queue_item_id: &str) -> Result<()> {
        let Some(q) = self.queue_ref().cloned() else {
            return Ok(());
        };
        let items: Option<Vec<Value>> = match q.get("items") {
            None | Some(Value::Null) => None,
            Some(Value::Array(items)) => Some(
                items
                    .iter()
                    .filter(|i| match i.as_object() {
                        Some(m) => interp(m.get("queueItemId")) != queue_item_id,
                        None => false,
                    })
                    .cloned()
                    .collect(),
            ),
            Some(other) => {
                return Err(ProtocolError::InvalidFrame(format!(
                    "queue.items is not a list: {other}"
                )))
            }
        };
        let mut new_queue = q.clone();
        new_queue.insert(
            "items".to_string(),
            Value::Array(items.unwrap_or_default()),
        );
        if let Some(snap) = self.snapshot.as_mut() {
            snap.insert("queue".to_string(), Value::Object(new_queue));
        }
        Ok(())
    }

    /// 对应 `control` getter。
    pub fn control(&self) -> Option<&JsonMap> {
        self.snapshot
            .as_ref()
            .and_then(|s| s.get("control"))
            .and_then(|v| v.as_object())
    }

    /// 对应 `revision` getter（CAS 命令的基准来源）。
    pub fn revision(&self) -> i64 {
        self.snapshot
            .as_ref()
            .and_then(|s| s.get("revision"))
            .and_then(lenient_num)
            .unwrap_or(0)
    }

    /// 对应 `phase` getter（phase 在 `control` 下面，不在顶层）。
    pub fn phase(&self) -> String {
        self.control()
            .and_then(|c| c.get("phase"))
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string()
    }

    /// 对应 `canStop` getter：`control?['canStop'] == true`（严格等于 true）。
    pub fn can_stop(&self) -> bool {
        matches!(
            self.control().and_then(|c| c.get("canStop")),
            Some(Value::Bool(true))
        )
    }

    /// 对应 `isRunning` getter：`phase == 'running' || phase == 'prewarming'`。
    pub fn is_running(&self) -> bool {
        let p = self.phase();
        p == "running" || p == "prewarming"
    }

    /// 对应 `config` getter：`{provider, model, thought, thoughtLevels, followupMode, mode}`。
    pub fn config(&self) -> Option<&JsonMap> {
        self.snapshot
            .as_ref()
            .and_then(|s| s.get("config"))
            .and_then(|v| v.as_object())
    }

    /// 对应 `currentModel` getter。
    pub fn current_model(&self) -> String {
        self.config()
            .and_then(|c| c.get("model"))
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string()
    }

    /// 对应 `currentThought` getter。
    pub fn current_thought(&self) -> String {
        self.config()
            .and_then(|c| c.get("thought"))
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string()
    }

    /// 对应 `currentMode` getter（缺省 `'build'`）。
    pub fn current_mode(&self) -> String {
        self.config()
            .and_then(|c| c.get("mode"))
            .and_then(|v| v.as_str())
            .unwrap_or("build")
            .to_string()
    }

    /// 对应 `thoughtLevels` getter：是 List 才取，元素逐个插值成字符串。
    pub fn thought_levels(&self) -> Vec<String> {
        match self.config().and_then(|c| c.get("thoughtLevels")) {
            Some(Value::Array(items)) => items.iter().map(|e| interp(Some(e))).collect(),
            _ => Vec::new(),
        }
    }

    /// 对应 `queue` getter：`{items: [...], autoDrain}`（借用版）。
    pub fn queue_ref(&self) -> Option<&JsonMap> {
        self.snapshot
            .as_ref()
            .and_then(|s| s.get("queue"))
            .and_then(|v| v.as_object())
    }

    /// 对应 `queue` getter（返回拷贝；改队列请走
    /// [`ConversationState::optimistic_remove_queue_item`]）。
    pub fn queue(&self) -> Option<JsonMap> {
        self.queue_ref().cloned()
    }

    /// 对应 `queueItems` getter：只保留 Map 元素。
    pub fn queue_items(&self) -> Vec<JsonMap> {
        match self.queue_ref().and_then(|q| q.get("items")) {
            Some(Value::Array(items)) => items
                .iter()
                .filter_map(|i| i.as_object().cloned())
                .collect(),
            _ => Vec::new(),
        }
    }

    /// 对应 `autoDrain` getter：`queue?['autoDrain'] != false`（只有严格的 false 才算关）。
    pub fn auto_drain(&self) -> bool {
        !matches!(
            self.queue_ref().and_then(|q| q.get("autoDrain")),
            Some(Value::Bool(false))
        )
    }

    /// 对应 `usage` getter：`{contextWindow: {usedTokens, maxTokens, ...}?, cumulative}`。
    pub fn usage(&self) -> Option<&JsonMap> {
        self.snapshot
            .as_ref()
            .and_then(|s| s.get("usage"))
            .and_then(|v| v.as_object())
    }

    /// 对应 `canLoadOlder` getter：
    /// `!historyExhausted && firstRowId != null && totalCount > rows.length`。
    pub fn can_load_older(&self) -> bool {
        !self.history_exhausted
            && self.first_row_id.is_some()
            && self.total_count > self.rows.len() as i64
    }

    /// 对应 `oldestRowId` getter：分页游标是**当前持有的最老一行**，不是快照投影头 `firstRowId`。
    pub fn oldest_row_id(&self) -> Option<i64> {
        match self.rows.first() {
            None => self.first_row_id,
            Some(row) => row.get("rowId").and_then(lenient_num),
        }
    }

    /// 对应 `prependOlderRows(older, newFirstRowId)`：把 `rowsRange` 取回的老行插到前面（按 rowId 去重）。
    ///
    /// Dart 的 `existing` 是 `Set<int?>`，所以「rowId 缺失」的行也算同一个键，会被去重。
    pub fn prepend_older_rows(
        &mut self,
        older: &[JsonMap],
        new_first_row_id: Option<i64>,
    ) -> Result<()> {
        let mut existing: Vec<Option<i64>> = Vec::with_capacity(self.rows.len());
        for r in &self.rows {
            existing.push(num_of(r.get("rowId"))?);
        }
        let mut fresh: Vec<JsonMap> = Vec::new();
        for r in older {
            let id = num_of(r.get("rowId"))?;
            if !existing.contains(&id) {
                fresh.push(r.clone());
            }
        }
        if !fresh.is_empty() {
            let first_fresh = num_of(fresh[0].get("rowId"))?;
            let mut rows = fresh;
            rows.append(&mut self.rows);
            self.rows = rows;
            self.first_row_id = new_first_row_id.or(first_fresh).or(self.first_row_id);
        } else if let Some(new_first) = new_first_row_id {
            if Some(new_first) != self.first_row_id {
                self.first_row_id = Some(new_first);
            }
        }
        Ok(())
    }

    /// 对应 `backgroundWorks` getter：只保留 Map 元素。
    pub fn background_works(&self) -> Vec<JsonMap> {
        match self
            .snapshot
            .as_ref()
            .and_then(|s| s.get("backgroundWorks"))
        {
            Some(Value::Array(items)) => items
                .iter()
                .filter_map(|i| i.as_object().cloned())
                .collect(),
            _ => Vec::new(),
        }
    }

    /// 对应 `goal` getter。
    pub fn goal(&self) -> Option<&JsonMap> {
        self.snapshot
            .as_ref()
            .and_then(|s| s.get("goal"))
            .and_then(|v| v.as_object())
    }

    /// 对应 `plan` getter。
    pub fn plan(&self) -> Option<&JsonMap> {
        self.snapshot
            .as_ref()
            .and_then(|s| s.get("plan"))
            .and_then(|v| v.as_object())
    }

    /// 对应 `inputRoutingMode` getter：
    /// `inputRouting: {mode: startNow|enqueue|guide|reject|choice, reasonCode?}`。
    pub fn input_routing_mode(&self) -> String {
        self.snapshot
            .as_ref()
            .and_then(|s| s.get("inputRouting"))
            .and_then(|v| v.as_object())
            .and_then(|m| m.get("mode"))
            .and_then(|v| v.as_str())
            .unwrap_or("startNow")
            .to_string()
    }

    /// 对应 `pendingInteractions` getter：只保留 Map 元素。
    pub fn pending_interactions(&self) -> Vec<JsonMap> {
        match self
            .snapshot
            .as_ref()
            .and_then(|s| s.get("pendingInteractions"))
        {
            Some(Value::Array(items)) => items
                .iter()
                .filter_map(|i| i.as_object().cloned())
                .collect(),
            _ => Vec::new(),
        }
    }

    /// 对应 watchdog 里的 `state.rows.any((r) => r['state'] == 'streaming')`。
    pub fn has_streaming_row(&self) -> bool {
        self.rows
            .iter()
            .any(|r| r.get("state").and_then(|v| v.as_str()) == Some("streaming"))
    }
}

/// 对应 `_appendToRow(row, path, append)`（网页端 `dke()`）：按 kind 决定往哪个字段追加流式文本。
///
/// 不匹配的 (path, kind) 组合原样返回该行；`path` 为 null 走 default 分支。
pub fn append_to_row(row: &JsonMap, path: Option<&str>, append: &str) -> JsonMap {
    let kind = row.get("kind").and_then(|v| v.as_str());
    match path {
        Some("text") => {
            if kind == Some("assistantText") || kind == Some("reasoning") {
                let mut out = row.clone();
                let text = format!("{}{}", interp_empty(row.get("text")), append);
                out.insert("text".to_string(), Value::String(text));
                return out;
            }
            row.clone()
        }
        Some("inputText") => {
            if kind == Some("toolCall") {
                let mut out = row.clone();
                let text = format!("{}{}", interp_empty(row.get("inputText")), append);
                out.insert("inputText".to_string(), Value::String(text));
                return out;
            }
            row.clone()
        }
        Some("output.text") => {
            if kind == Some("toolCall") {
                if let Some(output) = row.get("output").and_then(|v| v.as_object()) {
                    let mut new_output = output.clone();
                    let text = format!("{}{}", interp_empty(output.get("text")), append);
                    new_output.insert("text".to_string(), Value::String(text));
                    let mut out = row.clone();
                    out.insert("output".to_string(), Value::Object(new_output));
                    return out;
                }
            }
            row.clone()
        }
        Some("summaryText") => {
            if kind == Some("subagent") {
                let mut out = row.clone();
                let text = format!("{}{}", interp_empty(row.get("summaryText")), append);
                out.insert("summaryText".to_string(), Value::String(text));
                return out;
            }
            row.clone()
        }
        _ => row.clone(),
    }
}

// ============================================================ 8. sessions-index

/// 对应 Dart `SessionEntry`（**公开**构造，参数是原始 JSON 映射）。
#[derive(Debug, Clone, PartialEq)]
pub struct SessionEntry {
    /// 对应 `sessionId`（`'${raw['sessionId'] ?? ''}'`，缺失/为 null → 空串）。
    pub session_id: String,
    /// 对应 `parentSessionId`（侧聊标记；非 null 时必须是字符串）。
    pub parent_session_id: Option<String>,
    /// 对应 `title`。
    pub title: String,
    /// 对应 `phase`。
    pub phase: String,
    /// 对应 `lastAssistantPreview`。
    pub last_assistant_preview: Option<String>,
    /// 对应 `lastActivityAt`（缺省 0）。
    pub last_activity_at: i64,
    /// 对应 `createdAt`（缺省 0）。
    pub created_at: i64,
    /// 对应 `hasBackgroundWork`（严格 `== true`）。
    pub has_background_work: bool,
    /// 对应 `pendingInteraction`（`as Map?` 后的映射）。
    pub pending_interaction: Option<JsonMap>,
    /// 对应 `raw`（原始映射，供 UI 取未建模字段）。
    pub raw: JsonMap,
}

impl SessionEntry {
    /// 对应 `SessionEntry(this.raw)`。
    pub fn from_json(raw: &JsonMap) -> Result<Self> {
        let pending_interaction = match raw.get("pendingInteraction") {
            Some(Value::Object(m)) => Some(m.clone()),
            Some(Value::Null) | None => None,
            Some(other) => {
                return Err(ProtocolError::InvalidFrame(format!(
                    "pendingInteraction is not a map: {other}"
                )))
            }
        };
        Ok(Self {
            session_id: interp_empty(raw.get("sessionId")),
            parent_session_id: str_of(raw.get("parentSessionId"))?,
            title: interp_empty(raw.get("title")),
            phase: interp_empty(raw.get("phase")),
            last_assistant_preview: str_of(raw.get("lastAssistantPreview"))?,
            last_activity_at: num_of(raw.get("lastActivityAt"))?.unwrap_or(0),
            created_at: num_of(raw.get("createdAt"))?.unwrap_or(0),
            has_background_work: matches!(raw.get("hasBackgroundWork"), Some(Value::Bool(true))),
            pending_interaction,
            raw: raw.clone(),
        })
    }
}

/// 对应 Dart `SessionsIndexState`——工作区的任务列表（网页端 `QAe` 的增量应用）。
#[derive(Debug, Clone, Default)]
pub struct SessionsIndexState {
    /// 对应 `workspaceId`。
    pub workspace_id: Option<String>,
    /// 对应 `logEpoch`。
    pub log_epoch: Option<String>,
    /// 对应 `seq`。
    pub seq: i64,
    /// 对应 `sessions`（按 sessionId 索引）。
    // PORT-NOTE: Dart 的 Map 是插入序；Rust 侧用 BTreeMap（按 key 排序），
    // 只影响 [`SessionsIndexState::list`] 在 `lastActivityAt` 相同时的先后
    // （Dart 的 `List.sort` 本身也不稳定）。
    pub sessions: BTreeMap<String, SessionEntry>,
    /// 对应 `ready`。
    pub ready: bool,
}

impl SessionsIndexState {
    /// 对应 `SessionsIndexState()` 的字段初值。
    pub fn new() -> Self {
        Self::default()
    }

    /// 对应 `list` getter：按 `lastActivityAt` 倒序。
    pub fn list(&self) -> Vec<SessionEntry> {
        let mut values: Vec<SessionEntry> = self.sessions.values().cloned().collect();
        values.sort_by(|a, b| b.last_activity_at.cmp(&a.last_activity_at));
        values
    }

    /// 对应 `applyFrame(frame, onGap: ...)`。
    ///
    /// 与 `ConversationState` 相同：`payload` 非 Map 直接返回；`deltas` 分支 `fromSeq != seq`
    /// 时调 `on_gap()` 并**提前返回**（`ready` 不置位、不通知）。
    pub fn apply_frame(&mut self, frame: &Value, on_gap: &mut dyn FnMut()) -> Result<()> {
        let Some(frame_obj) = frame.as_object() else {
            return Ok(());
        };
        let Some(payload) = frame_obj.get("payload").and_then(|v| v.as_object()) else {
            return Ok(());
        };
        let to_seq = num_of(frame_obj.get("toSeq"))?.unwrap_or(self.seq);

        match payload.get("kind").and_then(|v| v.as_str()) {
            Some("snapshot") => {
                let snap = match payload.get("snapshot").and_then(|v| v.as_object()) {
                    Some(s) => s.clone(),
                    None => {
                        return Err(ProtocolError::InvalidFrame(
                            "sessions-index snapshot is missing a 'snapshot' map".to_string(),
                        ))
                    }
                };
                self.workspace_id = str_of(snap.get("workspaceId"))?;
                self.log_epoch = str_of(snap.get("logEpoch"))?;
                self.sessions.clear();
                if let Some(Value::Array(list)) = snap.get("sessions") {
                    for s in list {
                        if let Some(obj) = s.as_object() {
                            let entry = SessionEntry::from_json(obj)?;
                            self.sessions.insert(entry.session_id.clone(), entry);
                        }
                    }
                }
                self.seq = to_seq;
            }
            Some("deltas") => {
                let from_seq = num_of(frame_obj.get("fromSeq"))?.unwrap_or(self.seq);
                if from_seq != self.seq {
                    on_gap();
                    return Ok(());
                }
                if let Some(Value::Array(deltas)) = payload.get("deltas") {
                    for d in deltas {
                        let Some(obj) = d.as_object() else {
                            continue;
                        };
                        match obj.get("op").and_then(|v| v.as_str()) {
                            Some("session.upserted") => {
                                if let Some(session) =
                                    obj.get("session").and_then(|v| v.as_object())
                                {
                                    let entry = SessionEntry::from_json(session)?;
                                    self.sessions.insert(entry.session_id.clone(), entry);
                                }
                            }
                            Some("session.removed") => {
                                // Dart: `sessions.remove('${d['sessionId']}')` —— null 会插值成 "null"。
                                self.sessions.remove(&interp(obj.get("sessionId")));
                            }
                            _ => {}
                        }
                    }
                }
                self.seq = to_seq;
            }
            _ => {}
        }
        self.ready = true;
        Ok(())
    }
}

// ============================================================ 9. workspace 配置与技能

/// 对应 Dart `SkillEntry`（`skills.list` 返回的桌面技能，在 composer 里用 `$name` 触发）。
#[derive(Debug, Clone, PartialEq)]
pub struct SkillEntry {
    /// 对应 `id`。
    pub id: String,
    /// 对应 `name`（空名条目会被 [`parse_skills_response`] 过滤掉）。
    pub name: String,
    /// 对应 `path`。
    pub path: String,
    /// 对应 `scope`（缺省 `'workspace'`）。
    pub scope: String,
    /// 对应 `description`（`as String?`）。
    pub description: Option<String>,
    /// 对应 `argumentHint`（`as String?`）。
    pub argument_hint: Option<String>,
    /// 对应 `enabled`（`raw['enabled'] != false`，只有严格 false 才算禁用）。
    pub enabled: bool,
}

impl SkillEntry {
    /// 对应 `SkillEntry._(Map raw)`。
    pub fn from_json(raw: &JsonMap) -> Result<Self> {
        let scope = match raw.get("scope") {
            Some(v) if !v.is_null() => interp(Some(v)),
            _ => "workspace".to_string(),
        };
        Ok(Self {
            id: interp_empty(raw.get("id")),
            name: interp_empty(raw.get("name")),
            path: interp_empty(raw.get("path")),
            scope,
            description: str_of(raw.get("description"))?,
            argument_hint: str_of(raw.get("argumentHint"))?,
            enabled: !matches!(raw.get("enabled"), Some(Value::Bool(false))),
        })
    }
}

/// 对应 `skills()` 的应答解析：
/// `raw = res is List ? res : (res is Map ? res['skills'] : null); if (raw is! List) return const [];`
/// 然后逐个 `whereType<Map>()` 解析并丢掉 `name` 为空的条目。
///
/// 通道调用（`Channels.skills` 的 `list`）与 20 秒超时属宿主。
pub fn parse_skills_response(res: &Value) -> Result<Vec<SkillEntry>> {
    let raw = match res {
        Value::Array(items) => Some(items),
        Value::Object(m) => match m.get("skills") {
            Some(Value::Array(items)) => Some(items),
            _ => None,
        },
        _ => None,
    };
    let Some(raw) = raw else {
        return Ok(Vec::new());
    };
    let mut out = Vec::new();
    for item in raw {
        if let Some(obj) = item.as_object() {
            let entry = SkillEntry::from_json(obj)?;
            if !entry.name.is_empty() {
                out.push(entry);
            }
        }
    }
    Ok(out)
}

/// 对应 Dart `ConfigOptionValue`。
#[derive(Debug, Clone, PartialEq)]
pub struct ConfigOptionValue {
    /// 对应 `value`。
    pub value: String,
    /// 对应 `name`（`'${raw['name'] ?? raw['value'] ?? ''}'`）。
    pub name: String,
    /// 对应 `description`。
    pub description: Option<String>,
    /// 对应 `modelProviderName`。
    pub model_provider_name: Option<String>,
}

impl ConfigOptionValue {
    /// 对应 `ConfigOptionValue._(Map raw)`。
    pub fn from_json(raw: &JsonMap) -> Result<Self> {
        let name = match raw.get("name") {
            Some(v) if !v.is_null() => interp(Some(v)),
            _ => match raw.get("value") {
                Some(v) if !v.is_null() => interp(Some(v)),
                _ => String::new(),
            },
        };
        Ok(Self {
            value: interp_empty(raw.get("value")),
            name,
            description: str_of(raw.get("description"))?,
            model_provider_name: str_of(raw.get("modelProviderName"))?,
        })
    }
}

/// 对应 Dart `ConfigOption`。
#[derive(Debug, Clone, PartialEq)]
pub struct ConfigOption {
    /// 对应 `id`。
    pub id: String,
    /// 对应 `name`。
    pub name: String,
    /// 对应 `category`。
    pub category: String,
    /// 对应 `type`。
    pub type_: String,
    /// 对应 `currentValue`（任意类型，原样保留）。
    pub current_value: Option<Value>,
    /// 对应 `options`。
    pub options: Vec<ConfigOptionValue>,
}

impl ConfigOption {
    /// 对应 `ConfigOption._(Map raw)`。
    pub fn from_json(raw: &JsonMap) -> Result<Self> {
        let mut options = Vec::new();
        if let Some(Value::Array(list)) = raw.get("options") {
            for v in list {
                if let Some(obj) = v.as_object() {
                    options.push(ConfigOptionValue::from_json(obj)?);
                }
            }
        }
        Ok(Self {
            id: interp_empty(raw.get("id")),
            name: interp_empty(raw.get("name")),
            category: interp_empty(raw.get("category")),
            type_: interp_empty(raw.get("type")),
            current_value: raw.get("currentValue").cloned(),
            options,
        })
    }
}

/// 对应 Dart `SlashCommand`。
#[derive(Debug, Clone, PartialEq)]
pub struct SlashCommand {
    /// 对应 `name`。
    pub name: String,
    /// 对应 `description`。
    pub description: String,
    /// 对应 `inputHint`。
    pub input_hint: Option<String>,
    /// 对应 `source`。
    pub source: String,
}

impl SlashCommand {
    /// 对应 `SlashCommand._(Map raw)`。
    pub fn from_json(raw: &JsonMap) -> Result<Self> {
        Ok(Self {
            name: interp_empty(raw.get("name")),
            description: interp_empty(raw.get("description")),
            input_hint: str_of(raw.get("inputHint"))?,
            source: interp_empty(raw.get("source")),
        })
    }
}

/// 对应 Dart `WorkspacePrep`（`zcode-task.prepareWorkspace` 的结果）。
#[derive(Debug, Clone, PartialEq)]
pub struct WorkspacePrep {
    /// 对应 `configOptions`（model/mode/thought 选择项）。
    pub config_options: Vec<ConfigOption>,
    /// 对应 `slashCommands`（内置命令 + 自定义 skills/MCP）。
    pub slash_commands: Vec<SlashCommand>,
    /// 对应 `raw`。
    pub raw: JsonMap,
}

impl WorkspacePrep {
    /// 对应 `WorkspacePrep._(Map raw)`。
    pub fn from_json(raw: &JsonMap) -> Result<Self> {
        let mut config_options = Vec::new();
        if let Some(Value::Array(list)) = raw.get("configOptions") {
            for o in list {
                if let Some(obj) = o.as_object() {
                    config_options.push(ConfigOption::from_json(obj)?);
                }
            }
        }
        let mut slash_commands = Vec::new();
        if let Some(Value::Array(list)) = raw.get("slashCommands") {
            for c in list {
                if let Some(obj) = c.as_object() {
                    slash_commands.push(SlashCommand::from_json(obj)?);
                }
            }
        }
        Ok(Self {
            config_options,
            slash_commands,
            raw: raw.clone(),
        })
    }

    /// 对应 `prepareWorkspace` 的应答解析（非 Map 应答按空 prep 处理，对应 Dart 的 `const {}`）。
    pub fn from_response(res: &Value) -> Result<Self> {
        match res.as_object() {
            Some(m) => Self::from_json(m),
            None => Self::from_json(&JsonMap::new()),
        }
    }

    /// 对应 `option(String id)`：按 id 找配置项。
    pub fn option(&self, id: &str) -> Option<&ConfigOption> {
        self.config_options.iter().find(|o| o.id == id)
    }
}

// ============================================================ 10. 内部辅助

/// 对应 Dart 的 `'$v'` 插值：null/缺失 → 字面量 `"null"`。
fn interp(v: Option<&Value>) -> String {
    match v {
        None | Some(Value::Null) => "null".to_string(),
        Some(Value::String(s)) => s.clone(),
        Some(Value::Number(n)) => n.to_string(),
        Some(Value::Bool(b)) => b.to_string(),
        // PORT-NOTE: Dart 对 List/Map 用自身的 toString（`[1, 2]` / `{a: 1}`），
        // 这里落到 JSON 形式（`[1,2]` / `{"a":1}`）。只影响畸形载荷里的插值文本。
        Some(other) => other.to_string(),
    }
}

/// 对应 Dart 的 `'${v ?? ''}'`：null/缺失 → 空串。
fn interp_empty(v: Option<&Value>) -> String {
    match v {
        None | Some(Value::Null) => String::new(),
        other => interp(other),
    }
}

/// 对应 Dart 的 `x as num?`：缺失/`null` → `Ok(None)`；数字 → `Ok(Some)`；
/// **其它类型 → `Err`**（Dart 抛 `TypeError`）。
// PORT-NOTE: 浮点截断用 Rust 的 `as i64`（饱和 + NaN→0）；Dart `toInt()` 对 NaN/Infinity 抛
// `UnsupportedError`、对超出 64 位的有限值行为未验证。线上不会出现这种数，仅记录差异。
fn num_of(v: Option<&Value>) -> Result<Option<i64>> {
    match v {
        None | Some(Value::Null) => Ok(None),
        Some(Value::Number(n)) => {
            if let Some(i) = n.as_i64() {
                Ok(Some(i))
            } else if let Some(u) = n.as_u64() {
                Ok(Some(i64::try_from(u).unwrap_or(i64::MAX)))
            } else {
                Ok(n.as_f64().map(|f| f as i64))
            }
        }
        Some(other) => Err(ProtocolError::InvalidFrame(format!(
            "expected a number, got {other}"
        ))),
    }
}

/// getter 专用：类型不符时降级为 `None`（Dart 的 getter 会抛 `TypeError`）。
// PORT-NOTE: Dart 的 `revision`/`phase`/`canStop` 等 getter 在类型不符时抛 `TypeError`
// （会被订阅层捕获后 resync）。这些 getter 不返回 `Result`（宿主/UI 直接读），故按缺省值降级处理——
// 只在畸形载荷下有差异，正常路径与 Dart 一致。写入路径（`apply_frame`/`apply_snapshot`/
// `prepend_older_rows` 等）仍严格照抄 Dart 的抛错语义。
fn lenient_num(v: &Value) -> Option<i64> {
    num_of(Some(v)).ok().flatten()
}

/// 对应 Dart 的 `x as String?`：缺失/`null` → `Ok(None)`；字符串 → `Ok(Some)`；其它类型 → `Err`。
fn str_of(v: Option<&Value>) -> Result<Option<String>> {
    match v {
        None | Some(Value::Null) => Ok(None),
        Some(Value::String(s)) => Ok(Some(s.clone())),
        Some(other) => Err(ProtocolError::InvalidFrame(format!(
            "expected a string, got {other}"
        ))),
    }
}

/// 对应 Dart 的 `(delta['row'] as Map).cast<String, dynamic>()`：缺失/非映射 → `Err`。
fn require_object<'a>(v: Option<&'a Value>, what: &str) -> Result<&'a JsonMap> {
    match v.and_then(|v| v.as_object()) {
        Some(m) => Ok(m),
        None => Err(ProtocolError::InvalidFrame(format!(
            "{what} is missing or not a map"
        ))),
    }
}

/// 对应 Dart 的 `{...a, ...b}`。
fn merge_maps(a: &JsonMap, b: &JsonMap) -> JsonMap {
    let mut out = a.clone();
    for (k, v) in b {
        out.insert(k.clone(), v.clone());
    }
    out
}

/// 对应 Dart 的 `List<Map<String,dynamic>>` → JSON 数组。
fn arr_of_objects(items: &[JsonMap]) -> Value {
    Value::Array(items.iter().cloned().map(Value::Object).collect())
}

/// 对应 Dart 的 `List<String>` → JSON 数组。
fn arr_of_strings(items: &[String]) -> Value {
    Value::Array(items.iter().cloned().map(Value::String).collect())
}

/// 对应 Dart `base64.decode`。
// PORT-NOTE: Dart 的 `Base64Decoder` 容忍缺 padding 与空白；这里先按标准字母表 + 必需 padding 解，
// 失败再按无 padding 解，仍失败返回 `Err`（调用方按 Dart 的 catch 分支丢弃该分片/中止读取）。
// 差异只在畸形输入上，且两边都会走错误路径。若主线在 crate 内统一出 base64 工具，请替换本函数。
fn decode_base64(s: &str) -> Result<Vec<u8>> {
    if let Ok(bytes) = STANDARD.decode(s) {
        return Ok(bytes);
    }
    if let Ok(bytes) = STANDARD_NO_PAD.decode(s) {
        return Ok(bytes);
    }
    Err(ProtocolError::InvalidFrame(
        "invalid base64 payload".to_string(),
    ))
}

// ============================================================ 11. 测试
//
// 用例逐条搬自 `Reference_code/test/conversation_test.dart`（25 条），期望值原样照抄；
// 标了「补充用例」的几条对应 Dart 源码里没有现成测试的纯逻辑分支，
// 期望值直接由 Dart 分支行为推出（涉及 base64 / sha256 / 分块数学的期望值由 Python 独立算出）。

#[cfg(test)]
mod tests {
    use super::*;

    // ---------------------------------------------------------- 测试辅助

    /// 对应 Dart `_injectSnapshot` 的具名参数（用 `..Default::default()` 覆盖需要的那几个）。
    struct SnapSpec {
        seq: i64,
        revision: i64,
        rows: Vec<JsonMap>,
        total_count: i64,
        first_row_id: Option<i64>,
        snapshot: Option<JsonMap>,
    }

    impl Default for SnapSpec {
        fn default() -> Self {
            Self {
                seq: 5,
                revision: 1,
                rows: Vec::new(),
                total_count: 0,
                first_row_id: None,
                snapshot: None,
            }
        }
    }

    /// 对应 Dart 的 `Map<String, dynamic>` 字面量 → `JsonMap`。
    fn m(v: Value) -> JsonMap {
        v.as_object()
            .expect("test helper expects a JSON object")
            .clone()
    }

    /// 对应 Dart `_injectSnapshot(state, {seq, revision, rows, totalCount, firstRowId, snapshot})`。
    fn inject_snapshot(state: &mut ConversationState, spec: SnapSpec) {
        let mut rows_obj = JsonMap::new();
        rows_obj.insert(
            "window".to_string(),
            Value::Array(spec.rows.into_iter().map(Value::Object).collect()),
        );
        rows_obj.insert("totalCount".to_string(), json!(spec.total_count));
        if let Some(first) = spec.first_row_id {
            rows_obj.insert("firstRowId".to_string(), json!(first));
        }
        let mut snap = JsonMap::new();
        snap.insert("revision".to_string(), json!(spec.revision));
        snap.insert("rows".to_string(), Value::Object(rows_obj));
        if let Some(extra) = spec.snapshot {
            for (k, v) in extra {
                snap.insert(k, v);
            }
        }
        let frame = json!({
            "payload": {"kind": "snapshot", "snapshot": Value::Object(snap)},
            "toSeq": spec.seq,
        });
        let mut on_gap = || {
            panic!("unexpected gap");
        };
        state
            .apply_frame(&frame, &mut on_gap)
            .expect("applyFrame should succeed");
    }

    /// 对应 Dart `_injectSessionsSnapshot(state, {sessions})`。
    fn inject_sessions_snapshot(state: &mut SessionsIndexState, sessions: Vec<JsonMap>) {
        let frame = json!({
            "payload": {
                "kind": "snapshot",
                "snapshot": {
                    "sessions": Value::Array(sessions.into_iter().map(Value::Object).collect()),
                },
            },
            "toSeq": 1,
        });
        let mut on_gap = || {
            panic!("unexpected gap");
        };
        state
            .apply_frame(&frame, &mut on_gap)
            .expect("applyFrame should succeed");
    }

    // ---------------------------------------------------------- ConversationState

    /// 对应 `snapshot clears and replaces state`。
    #[test]
    fn snapshot_clears_and_replaces_state() {
        let mut state = ConversationState::new();
        let frame = json!({
            "payload": {
                "kind": "snapshot",
                "snapshot": {
                    "logEpoch": "epoch-1",
                    "revision": 5,
                    "rows": {
                        "window": [
                            {"rowId": 1, "kind": "user", "text": "hello"},
                            {"rowId": 2, "kind": "assistant", "text": "hi"},
                        ],
                        "totalCount": 2,
                        "firstRowId": 1,
                    },
                },
            },
            "toSeq": 10,
        });
        let mut on_gap = || {
            panic!("should not gap on snapshot");
        };
        state.apply_frame(&frame, &mut on_gap).unwrap();

        assert_eq!(state.rows.len(), 2);
        assert_eq!(state.rows[0]["text"], json!("hello"));
        assert_eq!(state.seq, 10);
        assert_eq!(state.log_epoch.as_deref(), Some("epoch-1"));
        assert_eq!(state.revision(), 5);
        assert_eq!(state.first_row_id, Some(1));
        assert_eq!(state.total_count, 2);
        assert!(state.ready);
    }

    /// 对应 `row.appended adds to end`。
    #[test]
    fn row_appended_adds_to_end() {
        let mut state = ConversationState::new();
        inject_snapshot(
            &mut state,
            SnapSpec {
                seq: 1,
                ..Default::default()
            },
        );

        let frame = json!({
            "payload": {
                "kind": "deltas",
                "deltas": [
                    {"op": "row.appended", "row": {"rowId": 99, "kind": "user", "text": "new"}},
                ],
            },
            "fromSeq": 1,
            "toSeq": 2,
        });
        let mut on_gap = || {
            panic!("should not gap");
        };
        state.apply_frame(&frame, &mut on_gap).unwrap();

        assert_eq!(state.rows.len(), 1);
        assert_eq!(state.rows[0]["rowId"], json!(99));
        assert_eq!(state.seq, 2);
        assert_eq!(state.total_count, 1);
    }

    /// 对应 `row.upserted replaces existing row`。
    #[test]
    fn row_upserted_replaces_existing_row() {
        let mut state = ConversationState::new();
        inject_snapshot(
            &mut state,
            SnapSpec {
                rows: vec![m(json!({"rowId": 1, "kind": "user", "text": "old"}))],
                ..Default::default()
            },
        );

        let frame = json!({
            "payload": {
                "kind": "deltas",
                "deltas": [
                    {"op": "row.upserted", "row": {"rowId": 1, "kind": "user", "text": "updated"}},
                ],
            },
            "fromSeq": 5,
            "toSeq": 6,
        });
        let mut on_gap = || {
            panic!("should not gap");
        };
        state.apply_frame(&frame, &mut on_gap).unwrap();

        assert_eq!(state.rows.len(), 1);
        assert_eq!(state.rows[0]["text"], json!("updated"));
    }

    /// 对应 `row.removed removes from rowId upward`。
    #[test]
    fn row_removed_removes_from_row_id_upward() {
        let mut state = ConversationState::new();
        inject_snapshot(
            &mut state,
            SnapSpec {
                rows: vec![
                    m(json!({"rowId": 1, "kind": "user", "text": "a"})),
                    m(json!({"rowId": 2, "kind": "assistant", "text": "b"})),
                    m(json!({"rowId": 3, "kind": "user", "text": "c"})),
                ],
                total_count: 3,
                ..Default::default()
            },
        );

        let frame = json!({
            "payload": {
                "kind": "deltas",
                "deltas": [{"op": "row.removed", "fromRowId": 2}],
            },
            "fromSeq": 5,
            "toSeq": 6,
        });
        let mut on_gap = || {
            panic!("should not gap");
        };
        state.apply_frame(&frame, &mut on_gap).unwrap();

        assert_eq!(state.rows.len(), 1);
        assert_eq!(state.rows[0]["rowId"], json!(1));
        // totalCount 原本 3，删掉 rowId >= 2 的两行 → (3-2).clamp(0, 1<<31) = 1
        assert_eq!(state.total_count, 1);
    }

    /// 对应 `row.delta appends text`。
    #[test]
    fn row_delta_appends_text() {
        let mut state = ConversationState::new();
        inject_snapshot(
            &mut state,
            SnapSpec {
                rows: vec![m(json!({"rowId": 1, "kind": "assistantText", "text": "Hello"}))],
                ..Default::default()
            },
        );

        let frame = json!({
            "payload": {
                "kind": "deltas",
                "deltas": [{"op": "row.delta", "rowId": 1, "path": "text", "append": " World"}],
            },
            "fromSeq": 5,
            "toSeq": 6,
        });
        let mut on_gap = || {
            panic!("should not gap");
        };
        state.apply_frame(&frame, &mut on_gap).unwrap();

        assert_eq!(state.rows[0]["text"], json!("Hello World"));
    }

    /// 对应 `row.delta on toolCall inputText`。
    #[test]
    fn row_delta_on_tool_call_input_text() {
        let mut state = ConversationState::new();
        inject_snapshot(
            &mut state,
            SnapSpec {
                rows: vec![m(json!({"rowId": 1, "kind": "toolCall", "inputText": "ls"}))],
                ..Default::default()
            },
        );

        let frame = json!({
            "payload": {
                "kind": "deltas",
                "deltas": [{"op": "row.delta", "rowId": 1, "path": "inputText", "append": " -la"}],
            },
            "fromSeq": 5,
            "toSeq": 6,
        });
        let mut on_gap = || {
            panic!("should not gap");
        };
        state.apply_frame(&frame, &mut on_gap).unwrap();

        assert_eq!(state.rows[0]["inputText"], json!("ls -la"));
    }

    /// 对应 `row.delta on toolCall output.text merges into nested map`。
    #[test]
    fn row_delta_on_tool_call_output_text_merges_into_nested_map() {
        let mut state = ConversationState::new();
        inject_snapshot(
            &mut state,
            SnapSpec {
                rows: vec![m(json!({
                    "rowId": 1,
                    "kind": "toolCall",
                    "output": {"text": "file1"},
                }))],
                ..Default::default()
            },
        );

        let frame = json!({
            "payload": {
                "kind": "deltas",
                "deltas": [{"op": "row.delta", "rowId": 1, "path": "output.text", "append": "\nfile2"}],
            },
            "fromSeq": 5,
            "toSeq": 6,
        });
        let mut on_gap = || {
            panic!("should not gap");
        };
        state.apply_frame(&frame, &mut on_gap).unwrap();

        let output = state.rows[0]["output"].as_object().unwrap();
        assert_eq!(output["text"], json!("file1\nfile2"));
    }

    /// 对应 `state.updated merges into snapshot`。
    #[test]
    fn state_updated_merges_into_snapshot() {
        let mut state = ConversationState::new();
        inject_snapshot(
            &mut state,
            SnapSpec {
                revision: 5,
                snapshot: Some(m(json!({
                    "control": {"phase": "idle", "canStop": false},
                }))),
                ..Default::default()
            },
        );

        let frame = json!({
            "payload": {
                "kind": "deltas",
                "deltas": [{"op": "state.updated", "patch": {"revision": 6}}],
            },
            "fromSeq": 5,
            "toSeq": 6,
        });
        let mut on_gap = || {
            panic!("should not gap");
        };
        state.apply_frame(&frame, &mut on_gap).unwrap();

        assert_eq!(state.revision(), 6);
        // phase 挂在 control 下面，不在顶层
        assert_eq!(state.phase(), "idle");
    }

    /// 对应 `state.updated before snapshot is buffered`。
    #[test]
    fn state_updated_before_snapshot_is_buffered() {
        let mut state = ConversationState::new();
        let frame = json!({
            "payload": {
                "kind": "deltas",
                "deltas": [{"op": "state.updated", "patch": {"revision": 3}}],
            },
            "fromSeq": 0,
            "toSeq": 1,
        });
        let mut on_gap = || {
            panic!("should not gap");
        };
        state.apply_frame(&frame, &mut on_gap).unwrap();

        // 补丁还挂着，快照没到
        assert_eq!(state.revision(), 0);

        // 快照到达 → 攒下的补丁合并进来
        inject_snapshot(
            &mut state,
            SnapSpec {
                seq: 2,
                revision: 1,
                ..Default::default()
            },
        );
        assert_eq!(state.revision(), 3);
    }

    /// 对应 `fromSeq mismatch triggers onGap`。
    #[test]
    fn from_seq_mismatch_triggers_on_gap() {
        let mut state = ConversationState::new();
        inject_snapshot(
            &mut state,
            SnapSpec {
                seq: 10,
                ..Default::default()
            },
        );

        let mut gap_called = false;
        let frame = json!({
            "payload": {"kind": "deltas", "deltas": []},
            "fromSeq": 5, // 与当前 seq=10 不符
            "toSeq": 11,
        });
        state
            .apply_frame(&frame, &mut || {
                gap_called = true;
            })
            .unwrap();

        assert!(gap_called);
        assert_eq!(state.seq, 10); // 未变
    }

    /// 对应 `optimisticRowUpdate mutates in place`。
    #[test]
    fn optimistic_row_update_mutates_in_place() {
        let mut state = ConversationState::new();
        inject_snapshot(
            &mut state,
            SnapSpec {
                rows: vec![m(json!({"rowId": 1, "kind": "assistant", "feedback": null}))],
                ..Default::default()
            },
        );

        state
            .optimistic_row_update(Some(1), &m(json!({"feedback": "like"})))
            .unwrap();
        assert_eq!(state.rows[0]["feedback"], json!("like"));
    }

    /// 对应 `optimisticRemoveQueueItem removes from queue items`。
    #[test]
    fn optimistic_remove_queue_item_removes_from_queue_items() {
        let mut state = ConversationState::new();
        inject_snapshot(
            &mut state,
            SnapSpec {
                snapshot: Some(m(json!({
                    "revision": 1,
                    "rows": {"window": [], "totalCount": 0},
                    "queue": {
                        "items": [
                            {"queueItemId": "q1", "text": "a"},
                            {"queueItemId": "q2", "text": "b"},
                        ],
                    },
                }))),
                ..Default::default()
            },
        );

        state.optimistic_remove_queue_item("q1").unwrap();
        let items = state.queue_items();
        assert_eq!(items.len(), 1);
        assert_eq!(items[0]["queueItemId"], json!("q2"));
    }

    /// 对应 `canLoadOlder is true when more rows exist`。
    #[test]
    fn can_load_older_is_true_when_more_rows_exist() {
        let mut state = ConversationState::new();
        inject_snapshot(
            &mut state,
            SnapSpec {
                rows: vec![m(json!({"rowId": 10, "kind": "user", "text": "latest"}))],
                total_count: 100,
                first_row_id: Some(10),
                ..Default::default()
            },
        );

        assert!(state.can_load_older());
    }

    /// 对应 `oldestRowId uses the oldest held row as pagination cursor`。
    #[test]
    fn oldest_row_id_uses_the_oldest_held_row_as_pagination_cursor() {
        let mut state = ConversationState::new();
        inject_snapshot(
            &mut state,
            SnapSpec {
                rows: vec![m(json!({"rowId": 10, "kind": "user", "text": "latest"}))],
                total_count: 20,
                first_row_id: Some(1),
                ..Default::default()
            },
        );
        assert_eq!(state.oldest_row_id(), Some(10));
    }

    /// 对应 `resync snapshot preserves paged-in older rows`。
    #[test]
    fn resync_snapshot_preserves_paged_in_older_rows() {
        let mut state = ConversationState::new();
        inject_snapshot(
            &mut state,
            SnapSpec {
                rows: vec![m(json!({"rowId": 10, "kind": "user", "text": "tail"}))],
                total_count: 10,
                first_row_id: Some(1),
                ..Default::default()
            },
        );
        state
            .prepend_older_rows(
                &[m(json!({"rowId": 1, "kind": "user", "text": "older"}))],
                Some(1),
            )
            .unwrap();

        let frame = json!({
            "payload": {
                "kind": "snapshot",
                "snapshot": {
                    "rows": {
                        "window": [{"rowId": 10, "kind": "user", "text": "fresh tail"}],
                        "totalCount": 10,
                        "firstRowId": 1,
                    },
                },
            },
            "toSeq": 2,
        });
        let mut on_gap = || {
            panic!("snapshot should not gap");
        };
        state.apply_frame(&frame, &mut on_gap).unwrap();

        let ids: Vec<Option<i64>> = state.rows.iter().map(|r| r["rowId"].as_i64()).collect();
        assert_eq!(ids, vec![Some(1), Some(10)]);
        assert_eq!(state.rows.last().unwrap()["text"], json!("fresh tail"));
    }

    /// 对应 `canLoadOlder is false when all rows loaded`。
    #[test]
    fn can_load_older_is_false_when_all_rows_loaded() {
        let mut state = ConversationState::new();
        inject_snapshot(
            &mut state,
            SnapSpec {
                rows: vec![m(json!({"rowId": 1, "kind": "user", "text": "first"}))],
                total_count: 1,
                first_row_id: Some(1),
                ..Default::default()
            },
        );

        assert!(!state.can_load_older());
    }

    /// 对应 `prependOlderRows inserts before existing`。
    #[test]
    fn prepend_older_rows_inserts_before_existing() {
        let mut state = ConversationState::new();
        inject_snapshot(
            &mut state,
            SnapSpec {
                rows: vec![m(json!({"rowId": 3, "kind": "assistant", "text": "c"}))],
                total_count: 3,
                first_row_id: Some(3),
                ..Default::default()
            },
        );

        state
            .prepend_older_rows(
                &[
                    m(json!({"rowId": 1, "kind": "user", "text": "a"})),
                    m(json!({"rowId": 2, "kind": "assistant", "text": "b"})),
                ],
                Some(1),
            )
            .unwrap();

        assert_eq!(state.rows.len(), 3);
        assert_eq!(state.rows[0]["rowId"], json!(1));
        assert_eq!(state.rows[1]["rowId"], json!(2));
        assert_eq!(state.rows[2]["rowId"], json!(3));
        assert_eq!(state.first_row_id, Some(1));
    }

    /// 对应 `prependOlderRows deduplicates by rowId`。
    #[test]
    fn prepend_older_rows_deduplicates_by_row_id() {
        let mut state = ConversationState::new();
        inject_snapshot(
            &mut state,
            SnapSpec {
                rows: vec![m(json!({"rowId": 2, "kind": "assistant", "text": "existing"}))],
                ..Default::default()
            },
        );

        state
            .prepend_older_rows(
                &[
                    m(json!({"rowId": 1, "kind": "user", "text": "old"})),
                    m(json!({"rowId": 2, "kind": "user", "text": "dup"})),
                ],
                Some(1),
            )
            .unwrap();

        assert_eq!(state.rows.len(), 2);
        assert_eq!(state.rows[0]["text"], json!("old"));
        assert_eq!(state.rows[1]["text"], json!("existing"));
    }

    /// 对应 `computed properties from snapshot`。
    #[test]
    fn computed_properties_from_snapshot() {
        let mut state = ConversationState::new();
        inject_snapshot(
            &mut state,
            SnapSpec {
                snapshot: Some(m(json!({
                    "revision": 5,
                    "control": {"phase": "running", "canStop": true},
                    "config": {"model": "GLM-5.2", "thought": "max", "mode": "build"},
                    "usage": {"contextWindow": {"usedTokens": 100, "maxTokens": 200000}},
                }))),
                ..Default::default()
            },
        );

        assert!(state.is_running());
        assert!(state.can_stop());
        assert_eq!(state.current_model(), "GLM-5.2");
        assert_eq!(state.current_thought(), "max");
        assert_eq!(state.current_mode(), "build");
        assert!(state.usage().is_some());
    }

    /// 对应 `draft phase is not running`。
    #[test]
    fn draft_phase_is_not_running() {
        let mut state = ConversationState::new();
        inject_snapshot(
            &mut state,
            SnapSpec {
                snapshot: Some(m(json!({
                    "revision": 1,
                    "control": {"phase": "draft"},
                    "rows": {"window": [], "totalCount": 0},
                }))),
                ..Default::default()
            },
        );

        assert!(!state.is_running());
        assert!(!state.can_stop());
    }

    // ---------------------------------------------------------- SessionsIndexState

    /// 对应 `snapshot loads sessions`。
    #[test]
    fn sessions_snapshot_loads_sessions() {
        let mut state = SessionsIndexState::new();
        let mut gap_count = 0;
        let frame = json!({
            "payload": {
                "kind": "snapshot",
                "snapshot": {
                    "workspaceId": "ws-1",
                    "logEpoch": "epoch-1",
                    "sessions": [
                        {"sessionId": "s1", "title": "Task A", "phase": "running", "lastActivityAt": 1000, "createdAt": 900},
                        {"sessionId": "s2", "title": "Task B", "phase": "completed", "lastActivityAt": 2000, "createdAt": 800},
                    ],
                },
            },
            "toSeq": 5,
        });
        state
            .apply_frame(&frame, &mut || {
                gap_count += 1;
            })
            .unwrap();

        assert_eq!(state.sessions.len(), 2);
        assert!(state.ready);
        assert_eq!(state.workspace_id.as_deref(), Some("ws-1"));
        assert_eq!(gap_count, 0);
    }

    /// 对应 `list is sorted by lastActivityAt descending`。
    #[test]
    fn sessions_list_is_sorted_by_last_activity_descending() {
        let mut state = SessionsIndexState::new();
        let frame = json!({
            "payload": {
                "kind": "snapshot",
                "snapshot": {
                    "sessions": [
                        {"sessionId": "older", "lastActivityAt": 100, "createdAt": 50, "phase": "draft", "title": "Old"},
                        {"sessionId": "newer", "lastActivityAt": 200, "createdAt": 50, "phase": "draft", "title": "New"},
                    ],
                },
            },
            "toSeq": 1,
        });
        let mut on_gap = || {
            panic!("should not gap");
        };
        state.apply_frame(&frame, &mut on_gap).unwrap();

        let list = state.list();
        assert_eq!(list[0].session_id, "newer");
        assert_eq!(list[1].session_id, "older");
    }

    /// 对应 `session.upserted delta`。
    #[test]
    fn session_upserted_delta() {
        let mut state = SessionsIndexState::new();
        inject_sessions_snapshot(&mut state, Vec::new());

        let frame = json!({
            "payload": {
                "kind": "deltas",
                "deltas": [
                    {
                        "op": "session.upserted",
                        "session": {
                            "sessionId": "new-task",
                            "title": "Fresh",
                            "phase": "draft",
                            "lastActivityAt": 500,
                            "createdAt": 400,
                        },
                    },
                ],
            },
            "fromSeq": 1,
            "toSeq": 2,
        });
        let mut on_gap = || {
            panic!("should not gap");
        };
        state.apply_frame(&frame, &mut on_gap).unwrap();

        assert_eq!(state.sessions.len(), 1);
        assert_eq!(state.sessions["new-task"].title, "Fresh");
    }

    /// 对应 `session.removed delta`。
    #[test]
    fn session_removed_delta() {
        let mut state = SessionsIndexState::new();
        inject_sessions_snapshot(
            &mut state,
            vec![
                m(json!({"sessionId": "s1", "title": "T1", "phase": "draft", "lastActivityAt": 0, "createdAt": 0})),
                m(json!({"sessionId": "s2", "title": "T2", "phase": "draft", "lastActivityAt": 0, "createdAt": 0})),
            ],
        );

        let frame = json!({
            "payload": {
                "kind": "deltas",
                "deltas": [{"op": "session.removed", "sessionId": "s1"}],
            },
            "fromSeq": 1,
            "toSeq": 2,
        });
        let mut on_gap = || {
            panic!("should not gap");
        };
        state.apply_frame(&frame, &mut on_gap).unwrap();

        assert_eq!(state.sessions.len(), 1);
        assert!(!state.sessions.contains_key("s1"));
        assert!(state.sessions.contains_key("s2"));
    }

    /// 对应 `session entry parses parentSessionId (side chat marker)`。
    #[test]
    fn session_entry_parses_parent_session_id() {
        let mut state = SessionsIndexState::new();
        let frame = json!({
            "payload": {
                "kind": "snapshot",
                "snapshot": {
                    "sessions": [
                        {
                            "sessionId": "side-1",
                            "parentSessionId": "main-1",
                            "title": "Side chat",
                            "phase": "running",
                            "lastActivityAt": 100,
                            "createdAt": 90,
                        },
                        {
                            "sessionId": "main-1",
                            "title": "Main task",
                            "phase": "draft",
                            "lastActivityAt": 200,
                            "createdAt": 50,
                        },
                    ],
                },
            },
            "toSeq": 1,
        });
        let mut on_gap = || {
            panic!("should not gap");
        };
        state.apply_frame(&frame, &mut on_gap).unwrap();

        assert_eq!(
            state.sessions["side-1"].parent_session_id.as_deref(),
            Some("main-1")
        );
        assert_eq!(state.sessions["main-1"].parent_session_id, None);
    }

    // ---------------------------------------------------------- 补充用例（Dart 测试未覆盖的纯逻辑分支）

    /// 补充用例：分片重组（`_acceptFragment` 正常路径）。
    /// 期望值由 Python 独立算出：`{"a":1}` 的两段 base64 为 `eyJhIg==` / `OjF9`。
    #[test]
    fn fragment_reassembly_joins_parts_in_index_order() {
        let mut asm = FragmentAssembler::new();
        let head = m(json!({
            "logicalFrameId": "lf-1",
            "fragmentIndex": 0,
            "fragmentCount": 2,
            "dataBase64": "eyJhIg==",
        }));
        assert_eq!(asm.accept(&head, 1_000).unwrap(), FragmentOutcome::Pending);
        let tail = m(json!({
            "logicalFrameId": "lf-1",
            "fragmentIndex": 1,
            "fragmentCount": 2,
            "dataBase64": "OjF9",
        }));
        assert_eq!(
            asm.accept(&tail, 1_000).unwrap(),
            FragmentOutcome::Complete(json!({"a": 1}))
        );
        assert!(asm.is_empty());
    }

    /// 补充用例：同一 `logicalFrameId` 的 `fragmentCount` 变化 → 丢弃整份装配体
    /// （Dart 里 `remove` + `return`）。
    #[test]
    fn fragment_count_mismatch_drops_assembly() {
        let mut asm = FragmentAssembler::new();
        let a = m(json!({
            "logicalFrameId": "lf-2", "fragmentIndex": 0, "fragmentCount": 2, "dataBase64": "eyJhIg==",
        }));
        assert_eq!(asm.accept(&a, 0).unwrap(), FragmentOutcome::Pending);
        let b = m(json!({
            "logicalFrameId": "lf-2", "fragmentIndex": 1, "fragmentCount": 3, "dataBase64": "OjF9",
        }));
        assert_eq!(asm.accept(&b, 0).unwrap(), FragmentOutcome::Ignored);
        assert!(asm.is_empty());
    }

    /// 补充用例：分片越界与超上限一律忽略
    /// （`count < 1 || count > 64 || index < 0 || index >= count`）。
    #[test]
    fn fragment_bounds_are_rejected() {
        let mut asm = FragmentAssembler::new();
        let over = m(json!({
            "logicalFrameId": "x", "fragmentIndex": 0, "fragmentCount": 65, "dataBase64": "eyJhIg==",
        }));
        assert_eq!(asm.accept(&over, 0).unwrap(), FragmentOutcome::Ignored);
        let idx = m(json!({
            "logicalFrameId": "x", "fragmentIndex": 2, "fragmentCount": 2, "dataBase64": "eyJhIg==",
        }));
        assert_eq!(asm.accept(&idx, 0).unwrap(), FragmentOutcome::Ignored);
        assert!(asm.is_empty());
    }

    /// 补充用例：`now - createdAt` 的秒数 `> 60` 才算过期（即 ≥ 61 秒），照抄 Dart `inSeconds > 60`。
    #[test]
    fn fragments_are_purged_only_after_61_seconds() {
        let mut asm = FragmentAssembler::new();
        let head = m(json!({
            "logicalFrameId": "lf-3", "fragmentIndex": 0, "fragmentCount": 2, "dataBase64": "eyJhIg==",
        }));
        asm.accept(&head, 0).unwrap();
        assert!(asm.purge_stale(60_000).is_empty());
        assert_eq!(asm.purge_stale(61_000), vec!["lf-3".to_string()]);
        assert!(asm.is_empty());
    }

    /// 补充用例：附件分块数学（`~/` 与 end 夹取）。期望值由 Python 独立算出。
    #[test]
    fn attachment_chunk_math_matches_dart() {
        assert_eq!(ATTACHMENT_CHUNK_BYTES, 393_216);
        assert_eq!(attachment_total_chunks(0), 0);
        assert_eq!(attachment_total_chunks(1), 1);
        assert_eq!(attachment_total_chunks(393_216), 1);
        assert_eq!(attachment_total_chunks(393_217), 2);
        assert_eq!(attachment_chunk_range(393_217, 0), (0, 393_216));
        assert_eq!(attachment_chunk_range(393_217, 1), (393_216, 393_217));
        assert_eq!(attachment_upload_id("abc"), "upload-abc");
    }

    /// 补充用例：`'sha256:${sha256.convert(bytes).toString()}'`。期望值由 Python 独立算出。
    #[test]
    fn attachment_checksum_matches_python() {
        assert_eq!(
            attachment_checksum(b""),
            "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );
        assert_eq!(
            attachment_checksum(b"abc"),
            "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }

    /// 补充用例：分块进度校验（`fault.attachment.invalidServerProgress`）。
    #[test]
    fn attachment_progress_validation() {
        assert!(validate_attachment_progress(0, 1).is_ok());
        let err = validate_attachment_progress(0, 5).unwrap_err();
        assert_eq!(
            err.to_string(),
            "invalid state: fault.attachment.invalidServerProgress"
        );
        // begin 应答：committed 直接给描述符；否则从 nextChunkIndex 开始
        assert_eq!(
            attachment_begin_decision(&json!({"state": "committed", "ref": "r-1"})).unwrap(),
            AttachmentBegin::Committed {
                reference: Some(json!("r-1"))
            }
        );
        assert_eq!(
            attachment_begin_decision(&json!({"nextChunkIndex": 3})).unwrap(),
            AttachmentBegin::Chunked {
                next_chunk_index: 3
            }
        );
        assert_eq!(
            attachment_begin_decision(&json!("nope")).unwrap(),
            AttachmentBegin::Chunked {
                next_chunk_index: 0
            }
        );
    }

    /// 补充用例：CAS / 行目标命令的 `baseRevision` / `baseLogEpoch` 取舍与 `max()` 取值。
    #[test]
    fn command_envelope_marks_cas_and_row_target_fields() {
        let env = build_command_envelope(
            "cmd-1",
            "client-1",
            Some("s1"),
            "retryTurn",
            json!({"target": {"rowId": 1}}),
            Some(3),
            Some("epoch-1"),
            Some(7),
            1_700_000_000_000,
        );
        assert_eq!(env.base_revision, Some(7));
        assert_eq!(env.base_log_epoch.as_deref(), Some("epoch-1"));
        let value = serde_json::to_value(&env).unwrap();
        assert_eq!(value["baseRevision"], json!(7));
        assert_eq!(value["baseLogEpoch"], json!("epoch-1"));
        assert_eq!(value["type"], json!("retryTurn"));
        assert_eq!(value["sessionId"], json!("s1"));

        // sendText 不是 CAS / 行目标命令 → 两个字段都不出现
        let plain = build_command_envelope(
            "cmd-2",
            "client-1",
            Some("s1"),
            "sendText",
            json!({"text": "hi"}),
            Some(3),
            Some("epoch-1"),
            Some(7),
            1,
        );
        let plain_value = serde_json::to_value(&plain).unwrap();
        assert!(plain_value.get("baseRevision").is_none());
        assert!(plain_value.get("baseLogEpoch").is_none());

        // createSession：sessionId 为 null，但键必须存在
        let create = build_command_envelope(
            "cmd-3",
            "client-1",
            None,
            "createSession",
            json!({}),
            None,
            None,
            None,
            1,
        );
        let create_value = serde_json::to_value(&create).unwrap();
        assert_eq!(create_value["sessionId"], Value::Null);

        // stale 重试信封：baseRevision 换成服务端修订号
        let retry = stale_retry_envelope(&env, "cmd-4", 9, 42);
        assert_eq!(retry.command_id, "cmd-4");
        assert_eq!(retry.base_revision, Some(9));
        assert_eq!(retry.issued_at, 42);

        // 超时重发信封只换 commandId / issuedAt
        let fresh = fresh_envelope(&env, "cmd-5", 7);
        assert_eq!(fresh.command_id, "cmd-5");
        assert_eq!(fresh.issued_at, 7);
        assert_eq!(fresh.base_revision, Some(7));
    }

    /// 补充用例：ack 修订号下界（accepted/noop/duplicate 抬到 rev+1，其它保持 rev）。
    #[test]
    fn acked_revisions_floor_and_stale_retry() {
        let mut acked = AckedRevisions::default();
        assert_eq!(acked.get("s1"), 0);
        acked
            .observe_final(
                Some("s1"),
                &json!({"status": "accepted", "revisionAtDecision": 5}),
            )
            .unwrap();
        assert_eq!(acked.get("s1"), 6);
        acked
            .observe_final(
                Some("s1"),
                &json!({"status": "rejected", "revisionAtDecision": 2}),
            )
            .unwrap();
        assert_eq!(acked.get("s1"), 6); // 2 < 6，不回落
        assert_eq!(ack_revision_floor(Some("noop"), 5), 6);
        assert_eq!(ack_revision_floor(Some("duplicate"), 5), 6);
        assert_eq!(ack_revision_floor(Some("rejected"), 5), 5);

        // stale：返回服务端修订号并抬升下界
        let server = acked
            .observe_stale(
                Some("s1"),
                &json!({"status": "stale", "revisionAtDecision": 9}),
            )
            .unwrap();
        assert_eq!(server, Some(9));
        assert_eq!(acked.get("s1"), 9);
        // 非 stale 或 sessionId 为 null 时不动
        assert_eq!(
            acked
                .observe_stale(
                    Some("s1"),
                    &json!({"status": "accepted", "revisionAtDecision": 3})
                )
                .unwrap(),
            None
        );
        assert_eq!(
            acked
                .observe_stale(None, &json!({"status": "stale", "revisionAtDecision": 3}))
                .unwrap(),
            None
        );
        assert_eq!(command_base_revision(Some("s1"), Some(4), Some(9)), Some(9));
        assert_eq!(command_base_revision(None, Some(4), Some(9)), None);
    }

    /// 补充用例：`createSession` 的失败文案逐字对齐 Dart 的 `StateError`。
    #[test]
    fn create_session_result_parsing_matches_dart_messages() {
        assert_eq!(
            parse_create_session_result(
                &json!({"status": "accepted", "result": {"sessionId": "s9"}})
            )
            .unwrap(),
            "s9"
        );
        let err = parse_create_session_result(&json!({
            "status": "rejected", "reasonCode": "fault.x", "message": "boom",
        }))
        .unwrap_err();
        assert_eq!(
            err.to_string(),
            "invalid state: createSession rejected: fault.x boom"
        );
        let err =
            parse_create_session_result(&json!({"status": "accepted", "result": {}})).unwrap_err();
        assert_eq!(
            err.to_string(),
            "invalid state: createSession: missing sessionId in result"
        );
        // reasonCode 缺失 → 用 status 插值；message 缺失 → 空串（注意中间那个空格）
        let err = parse_create_session_result(&json!({"status": "rejected"})).unwrap_err();
        assert_eq!(
            err.to_string(),
            "invalid state: createSession rejected: rejected "
        );
        // 非 Map 应答 → status 为 null，插值成 "null"
        let err = parse_create_session_result(&json!("nope")).unwrap_err();
        assert_eq!(err.to_string(), "invalid state: createSession rejected: null ");
        assert_eq!(
            parse_create_selection_side_session_result(
                &json!({"status": "duplicate", "result": {"sessionId": "side"}})
            )
            .unwrap(),
            "side"
        );
        // hello 应答里的 connectionId
        assert_eq!(
            parse_hello_connection_id(&json!({"connectionId": "conn-1"})).unwrap(),
            Some("conn-1".to_string())
        );
        assert_eq!(parse_hello_connection_id(&json!("nope")).unwrap(), None);
    }

    /// 补充用例：订阅应答缺 `ack.subscriptionId` → `StateError` 文案；
    /// `logEpoch` 只在是字符串时取用。
    #[test]
    fn subscribe_ack_parsing() {
        let err = parse_subscribe_ack(&json!({"ack": {}}), "subscribeConversationV4").unwrap_err();
        assert_eq!(
            err.to_string(),
            "invalid state: subscribeConversationV4: missing ack.subscriptionId"
        );
        let ack = parse_subscribe_ack(
            &json!({"ack": {"subscriptionId": "sub-1", "logEpoch": "epoch-1"}}),
            "subscribeConversationV4",
        )
        .unwrap();
        assert_eq!(ack.subscription_id, "sub-1");
        assert_eq!(
            conversation_ack_log_epoch(&json!({"logEpoch": "epoch-1"})).as_deref(),
            Some("epoch-1")
        );
        assert_eq!(conversation_ack_log_epoch(&json!({"logEpoch": 5})), None);
        assert_eq!(conversation_ack_log_epoch(&json!({})), None);
    }

    /// 补充用例：`switchModelConfig` 撞 `Unsupported reasoning effort` 时的兜底级别、topic 拼接
    /// 与订阅/重同步参数。
    #[test]
    fn switch_model_config_fallback_and_request_args() {
        assert!(is_unsupported_reasoning_effort(
            "Unsupported reasoning effort: max"
        ));
        assert!(!is_unsupported_reasoning_effort("ok"));
        assert_eq!(switch_model_config_fallback_thought("enabled"), "max");
        assert_eq!(switch_model_config_fallback_thought("off"), "max");
        assert_eq!(switch_model_config_fallback_thought("max"), "enabled");
        assert_eq!(switch_model_config_fallback_thought("high"), "enabled");
        assert_eq!(response_message(&json!({"message": "boom"})), "boom");
        assert_eq!(response_message(&json!({})), "");
        assert_eq!(response_message(&json!("nope")), "");

        assert_eq!(conversation_topic("s1"), "conversation/s1");
        assert_eq!(
            sessions_index_topic(&m(json!({"workspaceIdentity": "id-1", "workspacePath": "/p"}))),
            "sessions-index/id-1"
        );
        assert_eq!(
            sessions_index_topic(&m(json!({"workspacePath": "/p"}))),
            "sessions-index//p"
        );
        assert_eq!(sessions_index_topic(&m(json!({}))), "sessions-index/null");
        assert_eq!(
            subscribe_request_args(
                &m(json!({"workspacePath": "/p"})),
                &conversation_subscribe_args("s1")
            ),
            json!({"workspacePath": "/p", "sessionId": "s1"})
        );
        assert_eq!(
            sessions_index_subscribe_args(),
            m(json!({"runtimePolicy": "existing-only"}))
        );
        assert_eq!(
            resync_request_args(
                &JsonMap::new(),
                "sub-1",
                &conversation_resync_args(),
                Some("epoch-1"),
                7
            ),
            json!({"subscriptionId": "sub-1", "forceSnapshot": true, "base": {"logEpoch": "epoch-1", "seq": 7}})
        );
        assert_eq!(
            resync_request_args(&JsonMap::new(), "sub-1", &conversation_resync_args(), None, 7),
            json!({"subscriptionId": "sub-1", "forceSnapshot": true})
        );
        assert_eq!(
            unsubscribe_request_args(&JsonMap::new(), "sub-1", &JsonMap::new()),
            json!({"subscriptionId": "sub-1"})
        );
    }

    /// 补充用例：订阅核心的暂存/放行与 dispose、resync 门控。
    #[test]
    fn subscription_core_stages_until_subscribed() {
        let mut core = SubscriptionCore::new();
        let topic = conversation_topic("s1");
        let frame = json!({
            "topic": topic,
            "kind": "complete",
            "frame": {"subscriptionId": "sub-1", "toSeq": 1, "payload": {"kind": "deltas", "deltas": []}},
        });
        // 还没订阅 id → 暂存
        assert!(core.handle_wire_frame(&topic, &frame, 0).unwrap().is_empty());
        assert_eq!(core.staged_frames.len(), 1);
        assert!(!core.can_resync());

        core.set_subscription_id("sub-1");
        assert!(core.can_resync());
        let staged = core.drain_staged();
        assert_eq!(staged.len(), 1);
        assert!(frame_matches_subscription(&staged[0], "sub-1"));
        assert!(!frame_matches_subscription(&staged[0], "sub-2"));

        // topic 不符 → 忽略
        assert!(core
            .handle_wire_frame("conversation/other", &frame, 0)
            .unwrap()
            .is_empty());
        // 已订阅 → 直接放行
        assert_eq!(core.handle_wire_frame(&topic, &frame, 0).unwrap().len(), 1);

        // resync 中不允许再次 resync；resubscribe 清理会清空 id 与暂存
        core.set_resyncing(true);
        assert!(!core.can_resync());
        core.set_resyncing(false);
        assert_eq!(core.on_resubscribe_cleanup().as_deref(), Some("sub-1"));
        assert!(core.subscription_id.is_none());
        assert!(core.should_retry_resubscribe());

        core.mark_disposed();
        assert!(!core.should_retry_resubscribe());
        assert!(core.handle_wire_frame(&topic, &frame, 0).unwrap().is_empty());

        // 失败清理：id / 暂存 / 分片全清
        let mut core = SubscriptionCore::new();
        core.set_subscription_id("sub-9");
        core.staged_frames.push(m(json!({"a": 1})));
        core.on_start_failure();
        assert!(core.subscription_id.is_none());
        assert!(core.staged_frames.is_empty());
        assert!(core.fragments.is_empty());
    }

    /// 补充用例：`AttachmentReader` 的终止条件
    /// （`res` 非 Map / `nextOffset` 缺失或回退 / 到达 `totalBytes`）。
    #[test]
    fn attachment_reader_termination() {
        let scope = JsonMap::new();
        let mut reader = AttachmentReader::new();
        assert!(reader.should_continue());
        assert_eq!(
            reader.request_args(&scope, "s1", "ref-1"),
            json!({"sessionId": "s1", "ref": "ref-1", "offset": 0, "limit": 524288})
        );
        reader
            .feed(&json!({"mediaType": "image/png", "dataBase64": "YWJj", "nextOffset": 3, "totalBytes": 6}))
            .unwrap();
        assert_eq!(reader.bytes, b"abc".to_vec());
        assert_eq!(reader.media_type.as_deref(), Some("image/png"));
        assert_eq!(reader.offset, 3);
        assert!(reader.should_continue());
        // nextOffset 回退 → 终止
        reader.feed(&json!({"nextOffset": 3})).unwrap();
        assert!(reader.done);
        assert!(!reader.should_continue());

        // 到达 totalBytes → 终止
        let mut reader = AttachmentReader::new();
        reader
            .feed(&json!({"dataBase64": "", "nextOffset": 6, "totalBytes": 6}))
            .unwrap();
        assert!(reader.done);

        // 非 Map 应答 → 终止
        let mut reader = AttachmentReader::new();
        reader.feed(&json!("nope")).unwrap();
        assert!(reader.done);
    }

    /// 补充用例：`parse_skills_response` 的三种应答形状与空名过滤。
    #[test]
    fn skills_response_shapes() {
        // 直接是 List
        let list = parse_skills_response(&json!([
            {"id": "a", "name": "alpha", "path": "/a", "scope": "user", "description": "d", "argumentHint": "x", "enabled": false},
            {"id": "b", "name": "", "path": "/b"},
        ]))
        .unwrap();
        assert_eq!(list.len(), 1);
        assert_eq!(list[0].name, "alpha");
        assert_eq!(list[0].scope, "user");
        assert!(!list[0].enabled);
        assert_eq!(list[0].argument_hint.as_deref(), Some("x"));

        // 包在 {'skills': [...]} 里；scope 缺省 workspace；enabled 只有严格 false 才为 false
        let wrapped = parse_skills_response(&json!({"skills": [{"name": "beta"}]})).unwrap();
        assert_eq!(wrapped.len(), 1);
        assert_eq!(wrapped[0].scope, "workspace");
        assert!(wrapped[0].enabled);

        // 其它形状 → 空表
        assert!(parse_skills_response(&json!("nope")).unwrap().is_empty());
        assert!(parse_skills_response(&json!({"skills": "nope"}))
            .unwrap()
            .is_empty());
    }

    /// 补充用例：`WorkspacePrep` 的解析与 `option(id)` 查找。
    #[test]
    fn workspace_prep_parsing() {
        let prep = WorkspacePrep::from_response(&json!({
            "configOptions": [
                {
                    "id": "model",
                    "name": "Model",
                    "category": "model",
                    "type": "select",
                    "currentValue": "GLM-5.2",
                    "options": [
                        {"value": "glm-5.2", "name": "GLM-5.2", "description": "d", "modelProviderName": "glm"},
                        {"value": "turbo"},
                    ],
                },
            ],
            "slashCommands": [
                {"name": "compact", "description": "Compact", "inputHint": "hint", "source": "builtin"},
            ],
        }))
        .unwrap();

        assert_eq!(prep.config_options.len(), 1);
        let opt = prep.option("model").unwrap();
        assert_eq!(opt.current_value, Some(json!("GLM-5.2")));
        assert_eq!(opt.type_, "select");
        assert_eq!(opt.options.len(), 2);
        // name 缺省回落到 value
        assert_eq!(opt.options[1].name, "turbo");
        assert_eq!(prep.option("nope"), None);
        assert_eq!(prep.slash_commands.len(), 1);
        assert_eq!(prep.slash_commands[0].input_hint.as_deref(), Some("hint"));

        // 非 Map 应答 → 空 prep（对应 Dart 的 `const {}`）
        let empty = WorkspacePrep::from_response(&json!("nope")).unwrap();
        assert!(empty.config_options.is_empty());
        assert!(empty.slash_commands.is_empty());
    }

    /// 补充用例：`append_to_row` 的 (path, kind) 组合与 `summaryText` 分支。
    #[test]
    fn append_to_row_kind_gates() {
        // kind 不匹配 → 原样返回
        let row = m(json!({"rowId": 1, "kind": "user", "text": "x"}));
        assert_eq!(append_to_row(&row, Some("text"), "y"), row);
        // summaryText 只对 subagent 生效
        let sub = m(json!({"rowId": 1, "kind": "subagent", "summaryText": "a"}));
        assert_eq!(
            append_to_row(&sub, Some("summaryText"), "b")["summaryText"],
            json!("ab")
        );
        assert_eq!(append_to_row(&row, Some("summaryText"), "b"), row);
        // 未知 path / path 缺失 → 原样返回
        assert_eq!(append_to_row(&sub, Some("nope"), "b"), sub);
        assert_eq!(append_to_row(&sub, None, "b"), sub);
        // reasoning 行也走 text 分支；字段缺失按空串起头
        let reasoning = m(json!({"rowId": 1, "kind": "reasoning"}));
        assert_eq!(
            append_to_row(&reasoning, Some("text"), "think")["text"],
            json!("think")
        );
    }

    /// 补充用例：`watchdog_should_resync` 的门限（静默 < 20 秒不动；≥ 20 秒且活跃才 resync）。
    #[test]
    fn watchdog_thresholds() {
        assert!(!watchdog_should_resync(19, true, false));
        assert!(watchdog_should_resync(20, true, false));
        assert!(watchdog_should_resync(20, false, true));
        assert!(!watchdog_should_resync(20, false, false));
        assert!(!watchdog_should_resync(120, false, false));

        let mut state = ConversationState::new();
        inject_snapshot(
            &mut state,
            SnapSpec {
                rows: vec![m(json!({"rowId": 1, "kind": "assistantText", "state": "streaming"}))],
                ..Default::default()
            },
        );
        assert!(state.has_streaming_row());
    }

    /// 补充用例：派生属性的边界（`autoDrain` / `thoughtLevels` / `inputRoutingMode` / 缺省值）。
    #[test]
    fn derived_properties_edge_cases() {
        let mut state = ConversationState::new();
        inject_snapshot(
            &mut state,
            SnapSpec {
                snapshot: Some(m(json!({
                    "config": {"thoughtLevels": ["max", 5]},
                    "queue": {"items": "nope", "autoDrain": false},
                    "inputRouting": {"mode": "enqueue", "reasonCode": "x"},
                }))),
                ..Default::default()
            },
        );
        // thoughtLevels 元素逐个插值成字符串
        assert_eq!(
            state.thought_levels(),
            vec!["max".to_string(), "5".to_string()]
        );
        // autoDrain 只有严格 false 才算关
        assert!(!state.auto_drain());
        // items 不是 List → 空表（getter 不抛错）
        assert!(state.queue_items().is_empty());
        assert_eq!(state.input_routing_mode(), "enqueue");
        assert!(state.control().is_none());

        // 缺省值
        let fresh = ConversationState::new();
        assert!(fresh.auto_drain());
        assert_eq!(fresh.current_mode(), "build");
        assert_eq!(fresh.current_model(), "");
        assert_eq!(fresh.current_thought(), "");
        assert_eq!(fresh.input_routing_mode(), "startNow");
        assert!(fresh.thought_levels().is_empty());
        assert_eq!(fresh.oldest_row_id(), None);
        assert_eq!(fresh.revision(), 0);
        assert_eq!(fresh.phase(), "");
        assert!(!fresh.is_running());
        assert!(fresh.queue().is_none());
        assert!(fresh.goal().is_none());
        assert!(fresh.plan().is_none());
        assert!(fresh.background_works().is_empty());
        assert!(fresh.pending_interactions().is_empty());
    }

    /// 补充用例：`row.appended` 只在 `firstRowId` 为空时回填；`row.upserted` 找不到行不追加。
    #[test]
    fn row_appended_and_upserted_edge_cases() {
        let mut state = ConversationState::new();
        inject_snapshot(
            &mut state,
            SnapSpec {
                rows: vec![m(json!({"rowId": 1, "kind": "user"}))],
                first_row_id: Some(1),
                ..Default::default()
            },
        );
        let frame = json!({
            "payload": {
                "kind": "deltas",
                "deltas": [
                    {"op": "row.appended", "row": {"rowId": 5, "kind": "user"}},
                    {"op": "row.upserted", "row": {"rowId": 99, "kind": "user"}},
                ],
            },
            "fromSeq": 5,
            "toSeq": 6,
        });
        let mut on_gap = || {
            panic!("should not gap");
        };
        state.apply_frame(&frame, &mut on_gap).unwrap();
        assert_eq!(state.rows.len(), 2); // upsert 找不到 → 不追加
        assert_eq!(state.first_row_id, Some(1)); // 已有 firstRowId → 不回填
        assert_eq!(state.total_count, 1);
    }

    /// 补充用例：`row.removed` 的 `fromRowId <= firstRowId` 分支（totalCount 归零、firstRowId 置空）。
    #[test]
    fn row_removed_resets_total_count_when_from_row_id_leads_first_row_id() {
        let mut state = ConversationState::new();
        inject_snapshot(
            &mut state,
            SnapSpec {
                rows: vec![
                    m(json!({"rowId": 1, "kind": "user"})),
                    m(json!({"rowId": 2, "kind": "user"})),
                ],
                total_count: 9,
                first_row_id: Some(2),
                ..Default::default()
            },
        );
        let frame = json!({
            "payload": {"kind": "deltas", "deltas": [{"op": "row.removed", "fromRowId": 2}]},
            "fromSeq": 5,
            "toSeq": 6,
        });
        let mut on_gap = || {
            panic!("should not gap");
        };
        state.apply_frame(&frame, &mut on_gap).unwrap();
        assert_eq!(state.rows.len(), 1);
        assert_eq!(state.total_count, 0);
        assert_eq!(state.first_row_id, None);
    }

    /// 补充用例：畸形载荷的错误路径（Dart 会抛 `TypeError`，订阅层捕获后 resync）。
    #[test]
    fn malformed_payloads_return_errors() {
        let mut state = ConversationState::new();
        let mut on_gap = || {
            panic!("should not gap");
        };
        // snapshot 不是 Map
        let frame = json!({"payload": {"kind": "snapshot", "snapshot": "nope"}, "toSeq": 1});
        assert!(state.apply_frame(&frame, &mut on_gap).is_err());

        // payload 不是 Map → 直接返回，ready 不动
        let frame = json!({"payload": "nope", "toSeq": 1});
        assert!(state.apply_frame(&frame, &mut on_gap).is_ok());
        assert!(!state.ready);

        // row.delta 的 rowId 是字符串 → 类型不符报错
        let mut state = ConversationState::new();
        inject_snapshot(&mut state, SnapSpec::default());
        let frame = json!({
            "payload": {"kind": "deltas", "deltas": [{"op": "row.delta", "rowId": "1", "path": "text", "append": "x"}]},
            "fromSeq": 5,
            "toSeq": 6,
        });
        assert!(state.apply_frame(&frame, &mut on_gap).is_err());

        // sessions-index：deltas 的 fromSeq 不符 → onGap 且 ready 不置位
        let mut index = SessionsIndexState::new();
        let mut gap_called = false;
        let frame = json!({"payload": {"kind": "deltas", "deltas": []}, "fromSeq": 3, "toSeq": 4});
        index
            .apply_frame(&frame, &mut || {
                gap_called = true;
            })
            .unwrap();
        assert!(gap_called);
        assert!(!index.ready);
        assert_eq!(index.seq, 0);

        // sessions-index：snapshot 不是 Map → 报错
        let frame = json!({"payload": {"kind": "snapshot", "snapshot": 5}, "toSeq": 1});
        assert!(index.apply_frame(&frame, &mut on_gap).is_err());
    }
}
