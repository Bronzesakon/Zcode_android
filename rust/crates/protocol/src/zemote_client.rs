//! 对应 `Reference_code/lib/protocol/zemote_client.dart` —— 协议层**门面**。
//!
//! 复刻网页端 `otn()` 那条主流程：relay 连接 → 配对 → bootstrap → workspace 桥 → 通道 RPC。
//!
//! # I/O 边界（本模块最要紧的一条，见 `PORTING.md`）
//!
//! 门面**自己不碰网络、不碰时钟、不做文件与权限**：
//!
//! * 它持有并驱动 [`RelayClient`]（`relay_client.rs` 的纯状态机），但把 WebSocket 本身
//!   经 [`RelayTransport`] trait 留给宿主；`Box<dyn RelayTransport>` 由构造函数注入。
//! * 它持有每个桥的 [`RpcFrameTransport`] + [`ChannelClient`]（都是本 crate 的纯状态机），
//!   并完成 Dart 里那两处接线（`transport.messages.listen(channels.handleMessage)` 与
//!   `ChannelClient(sendBody: transport.sendMessage)`）。这两个对象最终要发出去的载荷落进
//!   每个桥的出站队列，由 [`ZemoteClient::pump`] 取走再喂给 relay。
//! * **时间一律是入参**（`now_ms: i64`，Unix 毫秒），本模块不出现 `std::time`。
//! * 计时器（`Future.timeout` / `Timer` / `Future.delayed`）变成
//!   [`ZemoteEvent::TimerStart`] / [`ZemoteEvent::TimerCancel`]，宿主到点后回喂
//!   [`ZemoteInput::TimerFired`]。
//! * `Future` / `Stream` / `Completer` 由宿主持有：门面用**令牌**（`String`）标识一个挂起操作，
//!   结束时发 [`ZemoteEvent::Completed`]，宿主按令牌 resolve 自己的 future。
//!   `workspaceListUpdated` 这条 `Stream` 变成 [`ZemoteEvent::WorkspaceListUpdated`]。
//! * `ValueNotifier`（`BridgeSession.degraded` / `.recovered`）变成
//!   [`ZemoteEvent::BridgeDegraded`] / [`ZemoteEvent::BridgeRecovered`]，同时保留
//!   [`BridgeSession::degraded`] / [`BridgeSession::recovered`] 访问器。
//!
//! # 宿主怎么驱动
//!
//! ```text
//! // 1) socket 生命周期 / 收到的帧 / relay 自己的定时器
//! for e in client.step(ZemoteInput::Relay(RelayInput::TextFrame { generation, text }), now_ms) { .. }
//! // 2) 门面自己的定时器到点
//! for e in client.step(ZemoteInput::TimerFired(timer_id), now_ms) { .. }
//! // 3) 发起操作（返回令牌 + 要执行的动作）
//! let op = client.bootstrap(now_ms)?;
//! ```
//!
//! 宿主直接驱动 `BridgeSession` 的 `channels`（`conversation.rs` 那层要用）之后，调一次
//! [`ZemoteClient::pump`] 把出站帧推给 relay —— `step` 内部已经会 pump。
//!
//! # 与 Dart 的差异（逐条见各处的 `PORT-NOTE`）
//!
//! * Dart 的 `Map` 是插入序，`serde_json::Map` 默认是 `BTreeMap`（字典序）。JSON 对象无序，
//!   解析端按字段取值，**线上语义不受影响**；只有把 map 打成字符串（日志）时键序不同。
//! * Dart 的 `as String?` / `as num?` 强转失败抛 `TypeError`；Rust 侧一律按「字段缺失/类型不符
//!   即 `None`」处理（`PORTING.md`：不把 panic 当错误路径）。
//! * Dart 的 `$e` 插值（`TimeoutException` 自带时长）在 Rust 侧用近似文本 —— 日志不是线上格式。

use std::collections::{BTreeMap, BTreeSet};
use std::sync::{Arc, Mutex, PoisonError};

use serde_json::{json, Value};

use crate::channel_client::{
    dart_json_to_string, json_as_dart_int, ChannelClient, ChannelEvent, ChannelRpc,
};
use crate::connection_params::ZemoteConnectionParams;
use crate::conversation::JsonMap;
use crate::device_info::{zemote_platform_name, DeviceInfo, TargetPlatform, ZEMOTE_APP_NAME};
use crate::error::{ProtocolError, Result};
use crate::id::{generate_request_id_with, OsSecureRandom, SecureRandom};
use crate::relay_client::{RelayClient, RelayEvent, RelayInput, RelayState, RelayTransport};
use crate::rpc_transport::{FrameSink, RpcFramePayload, RpcFrameTransport};

// ===========================================================================
// 0. 类型别名与常量（Dart 的默认值，逐条照抄 `timeout:` 实参）
// ===========================================================================

/// 对应 Dart 的 `Map<String, dynamic>`（relay payload 的形状）。
///
/// PORT-NOTE: 与 `conversation::JsonMap` 是同一个类型别名；本模块另起名字只为可读性，
/// 主线收口时可统一（别名不产生实际重复，故不影响 FFI）。
pub type Payload = JsonMap;

/// 对应 Dart `request(payload, match, {timeout: const Duration(seconds: 30)})` 的默认超时。
pub const REQUEST_TIMEOUT_MS: i64 = 30_000;

/// 对应 Dart `waitPaired({timeout: const Duration(seconds: 60)})` 的默认超时。
pub const PAIR_TIMEOUT_MS: i64 = 60_000;

/// 对应 Dart `openBridge(..., {timeout: const Duration(seconds: 30)})` 的默认超时。
pub const OPEN_BRIDGE_TIMEOUT_MS: i64 = 30_000;

/// 对应 Dart `_recoverBridgeOnce` 里 `reconnectWorkspace(...).timeout(15s)` 的外层超时。
pub const RECONNECT_REQUEST_TIMEOUT_MS: i64 = 15_000;

/// 对应 Dart `_recoverBridgeWithRetry` 的 `for (var attempt = 1; attempt <= 15; attempt++)`。
pub const RECOVERY_MAX_ATTEMPTS: u32 = 15;

/// 对应 Dart `_recoverBridgeWithRetry` 里 `Future.delayed(const Duration(seconds: 3))`。
pub const RECOVERY_RETRY_DELAY_MS: i64 = 3_000;

/// 对应 Dart `BridgeSession.waitHealthy({timeout: const Duration(seconds: 45)})` 的默认超时。
///
/// PORT-NOTE: `conversation.rs` 有等价的 `BRIDGE_HEALTHY_TIMEOUT_SECS = 45`（它只是引用这个
/// 默认值）。真源是 `zemote_client.dart` 的形参默认值，故定义在此；主线可择一保留。
pub const BRIDGE_HEALTHY_TIMEOUT_MS: i64 = 45_000;

/// 线上 `zcode_type` 字面量（**不可改**）。逐条对应 `zemote_client.dart` 里的 map 字面量。
mod zcode_type {
    /// 对应 `bootstrap-request`。
    pub const BOOTSTRAP_REQUEST: &str = "bootstrap-request";
    /// 对应 `bootstrap-response`。
    pub const BOOTSTRAP_RESPONSE: &str = "bootstrap-response";
    /// 对应 `workspace-list-request`。
    pub const WORKSPACE_LIST_REQUEST: &str = "workspace-list-request";
    /// 对应 `workspace-list-response`。
    pub const WORKSPACE_LIST_RESPONSE: &str = "workspace-list-response";
    /// 对应 `workspace-list-updated`。
    pub const WORKSPACE_LIST_UPDATED: &str = "workspace-list-updated";
    /// 对应 `workspace-bridge-open`。
    pub const WORKSPACE_BRIDGE_OPEN: &str = "workspace-bridge-open";
    /// 对应 `workspace-bridge-ready`。
    pub const WORKSPACE_BRIDGE_READY: &str = "workspace-bridge-ready";
    /// 对应 `workspace-bridge-error`。
    pub const WORKSPACE_BRIDGE_ERROR: &str = "workspace-bridge-error";
    /// 对应 `workspace-reconnect-request`。
    pub const WORKSPACE_RECONNECT_REQUEST: &str = "workspace-reconnect-request";
    /// 对应 `workspace-reconnect-response`。
    pub const WORKSPACE_RECONNECT_RESPONSE: &str = "workspace-reconnect-response";
    /// 对应 `mobile-view-state-update`。
    pub const MOBILE_VIEW_STATE_UPDATE: &str = "mobile-view-state-update";
    /// 对应 `bridge-degraded`。
    pub const BRIDGE_DEGRADED: &str = "bridge-degraded";
    /// 对应 `rpc-frame`。
    pub const RPC_FRAME: &str = "rpc-frame";
    /// 对应 `rpc-frame-ack`。
    pub const RPC_FRAME_ACK: &str = "rpc-frame-ack";
    /// 对应 `app-error`（**对端 → 移动端**单向；对端的 `routePayload` 没有这个 case）。
    pub const APP_ERROR: &str = "app-error";
}

// ===========================================================================
// 1. 句柄与错误
// ===========================================================================

/// 门面自己的定时器句柄。
///
/// 宿主收到 [`ZemoteEvent::TimerStart`] 后按 `delay_ms` 起计时器，到点回喂
/// [`ZemoteInput::TimerFired`]；[`ZemoteEvent::TimerCancel`] 与之配对。
///
/// 与 `relay_client::RelayTimer` 互不干扰：那个是 relay 内部的**固定槽位**，这个是门面按需
/// 分配的句柄。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct TimerId(pub u64);

impl std::fmt::Display for TimerId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "timer#{}", self.0)
    }
}

/// 一个活跃桥的**本地稳定句柄**。
///
/// 对应 Dart 里 `BridgeSession` 这个**对象身份**：`workspace-bridge-open` 重开时
/// `bridgeSessionId`（线上字段）会换新，但 `BridgeSession` 对象不变（`_swap` 只换里面的栈）。
/// 宿主用本句柄关联会话层对象；线上字段用 [`BridgeSession::wire_bridge_session_id`]。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct BridgeSessionId(pub u64);

impl std::fmt::Display for BridgeSessionId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "bridge#{}", self.0)
    }
}

/// 门面级错误 —— 对应 Dart 门面里会往外抛的三种异常。
///
/// 与 `error.rs` 的分工：`ProtocolError` 是**协议层**错误（帧格式、编解码）；这里是
/// **门面层**的语义错误。`ZemoteError::Protocol` 承接前者的文本。
///
/// PORT-NOTE: 主线可考虑把 `Timeout` 并进 `error.rs`（其它模块同样有 Dart `TimeoutException`
/// 的对应物）；本模块先就地定义，避免改动 `error.rs`。
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum ZemoteError {
    /// 对应 Dart `TimeoutException`（`request` / `waitPaired` / `waitHealthy` 的超时）。
    #[error("timeout: {0}")]
    Timeout(String),

    /// 对应 Dart `StateError`，例如 `openBridge` 收到 `workspace-bridge-error`。
    #[error("invalid state: {0}")]
    InvalidState(String),

    /// 协议层错误（含 Dart 的 `TypeError` 强转失败）转成的文本。
    #[error("{0}")]
    Protocol(String),
}

impl From<ProtocolError> for ZemoteError {
    fn from(value: ProtocolError) -> Self {
        ZemoteError::Protocol(value.to_string())
    }
}

/// 门面方法的返回类型。
pub type ZemoteResult<T> = std::result::Result<T, ZemoteError>;

// ===========================================================================
// 2. 请求匹配器（Dart 的 `match` 闭包）
// ===========================================================================

/// 对应 Dart `request(payload, match)` 的 `match` 谓词。
///
/// Dart 侧传的是闭包，但四个调用点的谓词形状**完全一致**：`zcode_type` 属于一个集合，
/// 且若干字段与给定值严格相等。这里收成声明式结构：语义逐条等价（`zcode_type` 用 `||` 链、
/// 其余用 `&&`），且便于跨 FFI。
///
/// 注意 Dart 的注释：**响应不保证回显 requestId**，所以匹配是「每个挂起项都拿每个载荷试一遍」
/// （见 `ZemoteClient::dispatch_payload`），而不是按 requestId 查表。
#[derive(Debug, Clone, PartialEq)]
pub struct PayloadMatcher {
    /// `zcode_type` 必须属于该集合（对应 Dart 的 `== 'x'` 或 `== 'x' || == 'y'`）。
    pub types: Vec<String>,
    /// 额外必须严格相等的字段（对应 Dart 的 `&& p['k'] == v`）。
    pub fields: Vec<(String, Value)>,
}

impl PayloadMatcher {
    /// 对应 Dart 闭包里的 `p['zcode_type'] == 'x'`（可给多个候选，语义是 `||`）。
    pub fn new(types: &[&str]) -> Self {
        Self {
            types: types.iter().map(|t| (*t).to_string()).collect(),
            fields: Vec::new(),
        }
    }

    /// 追加一个「必须严格相等」的字段。对应 Dart 的 `&& p['k'] == v`。
    pub fn with_field(mut self, key: &str, value: Value) -> Self {
        self.fields.push((key.to_string(), value));
        self
    }

    /// 求值。入参用 [`Value`] 以便同时接受对象与其它形状（非对象一律不匹配）。
    ///
    /// 字段缺失与字段为 `null` 都让「要求非 null 值」的条件不成立 —— 与 Dart 的
    /// `p['k'] == v`（缺失取到 `null`）一致。
    pub fn matches(&self, payload: &Value) -> bool {
        let Some(object) = payload.as_object() else {
            return false;
        };
        let type_ok = object
            .get("zcode_type")
            .and_then(Value::as_str)
            .is_some_and(|t| self.types.iter().any(|candidate| candidate == t));
        if !type_ok {
            return false;
        }
        self.fields
            .iter()
            .all(|(key, expected)| object.get(key) == Some(expected))
    }
}

// ===========================================================================
// 3. 输入 / 输出
// ===========================================================================

/// [`ZemoteClient::step`] 的输入。
#[derive(Debug, Clone, PartialEq)]
pub enum ZemoteInput<'a> {
    /// 透传给内部 `RelayClient::step`：socket 生命周期、收到的帧、relay 自己的定时器
    /// （`RelayTimer`）、`Poke`、`Dispose`。
    ///
    /// PORT-NOTE: 宿主**不应**从这里传 `SendPayload` / `Start` / `Poke` / `Dispose` ——
    /// 请用门面方法（`bootstrap`、`connect` 等），否则会绕过 `_dispatchPayload` /
    /// `_onRelayState` 的监听语义（Dart 里这两个是构造函数里挂上的监听器，宿主直接驱动 relay
    /// 等价于把监听摘掉）。
    Relay(RelayInput<'a>),

    /// 门面自己的定时器到点。
    TimerFired(TimerId),
}

/// 一次挂起操作的结束形态。对应 Dart 里各类 `Completer` 的 complete / completeError。
#[derive(Debug, Clone, PartialEq)]
pub enum Completion {
    /// 对应 Dart `request()` 的 `Completer<Map<String, dynamic>>.complete(payload)`。
    ///
    /// 具体载荷由调用方决定：`request` 给整个响应，`bootstrap` 给 `res['result']`（或整个响应），
    /// `listWorkspaces` 给 `res['result']`。
    Payload(Value),

    /// 对应 Dart `openBridge()` 成功时返回的 `BridgeSession`。
    BridgeOpened(BridgeSessionId),

    /// 对应 Dart `waitPaired()` / `BridgeSession.waitHealthy()` 的 `complete()`（无返回值）。
    Ok,

    /// 失败：超时 / `StateError` / 协议错误。
    Failed(ZemoteError),
}

/// [`ZemoteClient::step`] 的输出。
#[derive(Debug, Clone, PartialEq)]
pub enum ZemoteEvent {
    /// 透传的内部 `RelayClient` 事件（`StateChanged` / `Log` / `Failure` / `TimerStart` /
    /// `TimerCancel`）。
    ///
    /// **`RelayEvent::Payload` 不会出现在这里** —— 门面已按 `_dispatchPayload` 消费掉它。
    /// 宿主要看 relay 的状态（UI）与失败（错误条）就读这里。
    Relay(RelayEvent),

    /// 对应 Dart `_log(line)` → `onLog`（**门面自己**产生的日志；relay 的日志在
    /// [`ZemoteEvent::Relay`] 里）。
    Log(String),

    /// 请宿主起一个计时器；`delay_ms` 毫秒后回喂 [`ZemoteInput::TimerFired`]。
    TimerStart {
        /// 定时器句柄。
        token: TimerId,
        /// 延迟（毫秒），对应 Dart `Duration(milliseconds: ...)`。
        delay_ms: i64,
    },

    /// 取消计时器（幂等；只对本模块发出过的 `token` 发）。
    TimerCancel(TimerId),

    /// 对应 Dart `_workspaceListUpdatedController.add(payload['result'])`
    /// （`workspaceListUpdated` 流）。
    WorkspaceListUpdated(Value),

    /// `BridgeSession.degraded` 变化（对应 Dart 那个 `ValueNotifier<String?>` 的通知）。
    BridgeDegraded {
        /// 哪个桥。
        session: BridgeSessionId,
        /// 新值；`None` 表示已恢复健康。
        degraded: Option<String>,
    },

    /// 对端主动报错（`zcode_type: "app-error"`）。
    ///
    /// 对端在主动下线远控前会发 `{reason: "desktop-disconnected"}`；网页端收到后
    /// **直接 dispose**。旧实现完全忽略这个帧，所以桌面端断开时移动端收不到通知，
    /// 只能靠关闭码 4010 猜。
    AppError {
        /// 对端只发 5 个值之一：`session-conflict` / `workspace-closed` /
        /// `desktop-disconnected` / `unsupported-action` / `unexpected-error`。
        reason: Option<String>,
        /// 附加错误文本。
        error: Option<String>,
    },

    /// 对应 Dart `session.recovered.value += 1`。
    ///
    /// 订阅方（`conversation.rs` 那层）必须据此**重做握手**：服务端订阅状态随旧桥一起死了。
    BridgeRecovered {
        /// 哪个桥。
        session: BridgeSessionId,
        /// 新计数（Dart `ValueNotifier<int>` 的值）。
        count: u32,
    },

    /// 对应 Dart `_attachStack` 里新建 `RpcFrameTransport`（含重开后的换栈）。
    ///
    /// 分片清理周期（Dart 在 `RpcFrameTransport` 构造函数里的 `Timer.periodic(30s)` →
    /// `_purgeStale()`）**由核心自己驱动**：本事件之后核心会为这个桥 arm 一个 30 秒定时器
    /// （表现为一条 [`ZemoteEvent::TimerStart`]，`delay_ms == PURGE_INTERVAL_SECONDS * 1000`），
    /// 到点自行 `purge_stale` 并重排下一个周期。宿主**不需要**为此做任何事，只需照常按
    /// `TimerStart` / `TimerCancel` 起停自己的计时器。
    BridgeStackAttached {
        /// 哪个桥。
        session: BridgeSessionId,
    },

    /// 对应 Dart `session._transport.dispose()`。
    ///
    /// 该桥的分片清理周期由核心驱动，核心会在发出本事件的同时取消它的清理定时器
    /// （表现为一条 [`ZemoteEvent::TimerCancel`]）；宿主无需额外处理。
    BridgeStackDisposed {
        /// 哪个桥。
        session: BridgeSessionId,
    },

    /// 对应 Dart `BridgeSession.conversation(scope)` 首次为某个 key 新建
    /// `ConversationTransport(session: this, scope: scope, onLog: onLog)`。
    ///
    /// 宿主据此建立会话层对象；`scope` 原样带出。
    ConversationOpened {
        /// 哪个桥。
        session: BridgeSessionId,
        /// 缓存键（见 [`conversation_key`]）。
        key: String,
        /// 作用域，原样透传。
        scope: Payload,
    },

    /// 通道 RPC 的结果 —— 对应 Dart `ChannelClient.handleMessage` 调到的 `_handlers[id]` 闭包。
    ///
    /// 宿主按 `ChannelEvent::id()` 关联自己发起的 `channels.call(...)`。
    Channel(ChannelEvent),

    /// 一个挂起操作结束。`token` 是发起时拿到的令牌。
    Completed {
        /// 关联键：线上 `requestId`，或 `waitPaired` / `waitHealthy` 的本地令牌。
        token: String,
        /// 结束形态。
        outcome: Completion,
    },
}

/// 一次门面操作的发起结果。
///
/// 对应 Dart「调用一个 `async` 方法」这件事的两半：方法返回的 future（Rust 侧靠 `token` 与
/// 后续 [`ZemoteEvent::Completed`] 关联），以及调用瞬间产生的副作用。
#[derive(Debug, Clone, PartialEq)]
pub struct Operation {
    /// 关联键。
    ///
    /// * `bootstrap` / `list_workspaces` / `open_bridge` / `reconnect_workspace`：线上 `requestId`
    ///   （`_reqId(prefix)` 生成）。
    /// * `wait_paired` / `bridge_wait_healthy`：本地令牌，形如 `pair:3` / `healthy:7`。
    /// * `conversation`：会话缓存键（见 [`conversation_key`]）。
    pub token: String,
    /// 发起时必须执行的宿主动作。
    pub events: Vec<ZemoteEvent>,
}

/// 构造参数 —— Dart `ZemoteClient(params, {onLog})` 里**隐式依赖宿主取数**的两项。
///
/// Dart 的 `device_info.dart` 用 `kIsWeb` / `defaultTargetPlatform` 取当前运行时平台；
/// 按 `PORTING.md`，取数留给宿主，本 crate 只接收结果。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ZemoteClientConfig {
    /// 宿主传入 Flutter 的 `kIsWeb`。
    pub is_web: bool,
    /// 宿主传入 Flutter 的 `defaultTargetPlatform`。
    pub target_platform: TargetPlatform,
}

// ===========================================================================
// 4. 桥会话
// ===========================================================================

/// 某个桥上一次健康等待（`BridgeSession.waitHealthy`）的登记项。
#[derive(Debug, Clone)]
struct HealthyWaiter {
    token: String,
    timer: TimerId,
}

/// 桥的恢复循环位置 —— 对应 Dart `_recoverBridgeWithRetry` + `_recoverBridgeOnce` 的
/// `await` 交错点。
#[derive(Debug, Clone)]
enum RecoveryPhase {
    /// 阶段 1：已发 `workspace-reconnect-request`，等响应。
    ReconnectRequest {
        /// 该请求的 `requestId`。
        request_id: String,
        /// 内层 `request()` 自己的 30s 超时（见 `finish_recovery_reconnect` 的 PORT-NOTE）。
        inner_timer: TimerId,
        /// 外层 `.timeout(15s)`。
        outer_timer: TimerId,
    },
    /// 阶段 2：已发 `workspace-bridge-open`（完整重开），等 ready / error。
    ///
    /// 不带 `requestId`：阶段 2 同时只有一个在飞请求，且匹配器已用新生成的
    /// `bridgeSessionId` 把无关响应排除了。
    Reopen,
    /// 一次尝试失败，等 3 秒再试。
    ///
    /// 不带定时器句柄：过期的触发会被 `on_recovery_retry` 用 `attempt` + 本阶段双重过滤掉
    /// （见那里的 `still_waiting` 判定），而 Dart 的 `Future.delayed` 本来就无法取消，
    /// 所以这里没有需要撤销的东西。
    RetryDelay,
}

/// 对应 Dart `_recoverBridgeWithRetry` 的循环变量 + `_recoverBridgeOnce` 的局部量。
#[derive(Debug, Clone)]
struct RecoveryState {
    /// 当前尝试序号（Dart `for (var attempt = 1; ...)`）。
    attempt: u32,
    /// `_recoverBridgeOnce` 里读到的 `session.bridge['workspaceKey']`。
    workspace_key: String,
    /// 卡在哪个 `await` 上。
    phase: RecoveryPhase,
}

/// 对应 Dart `class BridgeSession`。
///
/// 持有该桥的 rpc-frame 传输 + IPC 通道客户端，并保留 `_bridge` / `degraded` / `recovered`
/// 三个状态量。**对象跨重开保持不变**（`_swap` 只换内部栈），这正是 Dart 的语义。
pub struct BridgeSession {
    /// 本地稳定句柄（对应 Dart 的对象身份）。
    id: BridgeSessionId,
    /// 对应 Dart `_bridge`（`workspace-bridge-ready` 的 `bridge` 字段，或重开后的新值）。
    bridge: Payload,
    /// 对应 Dart `_transport`。
    transport: RpcFrameTransport,
    /// 对应 Dart `_channels`。
    channels: ChannelClient,
    /// [`RpcFrameTransport`] 的 `FrameSink` 落点；`pump` 从这里取走并发给 relay。
    outbox: Arc<Mutex<Vec<Payload>>>,
    /// 对应 Dart `degraded`（`ValueNotifier<String?>` 的值）。非 null 表示桥已降级。
    degraded: Option<String>,
    /// 对应 Dart `recovered`（`ValueNotifier<int>` 的值）。
    recovered: u32,
    /// 对应 Dart `_disposed`。
    disposed: bool,
    /// 对应 Dart `_conversations` 的**键集**（值即会话层对象，由宿主持有）。
    conversations: BTreeSet<String>,
    /// 挂起的 `waitHealthy`。
    healthy_waiters: Vec<HealthyWaiter>,
    /// 恢复循环位置（Dart 放在 `ZemoteClient._recoveringBridges` + 各 `await` 的续体里）。
    recovery: Option<RecoveryState>,
    /// 该桥 rpc 分片清理周期的定时器句柄 —— 对应 Dart `RpcFrameTransport` 构造函数里那个
    /// `Timer.periodic(const Duration(seconds: 30), ...)`。
    ///
    /// 定时器由**核心自己**持有与重排（宿主只按 [`ZemoteEvent::TimerStart`] /
    /// [`ZemoteEvent::TimerCancel`] 起停），换栈 / dispose 时用本字段精确取消，避免新旧两条
    /// 清理周期并存。
    fragment_purge_timer: Option<TimerId>,
}

impl BridgeSession {
    /// 对应 Dart `BridgeSession._(...)` + `_placeholderTransport`：先用占位栈建对象，
    /// 随后立刻被 `_attachStack` 换掉（Dart 里 `_attachStack` 第一件事就是
    /// `session._transport.dispose()`）。这里照抄这个「先占位再换」的顺序。
    fn placeholder(id: BridgeSessionId, bridge: Payload) -> Self {
        // Dart: `bridgeSessionId: '${bridge['bridgeSessionId'] ?? ''}'`
        let placeholder_id = match bridge.get("bridgeSessionId") {
            Some(value) if !value.is_null() => dart_json_to_string(value),
            _ => String::new(),
        };
        let outbox = Arc::new(Mutex::new(Vec::new()));
        let transport = RpcFrameTransport::new(
            placeholder_id,
            Box::new(OutboxSink::new(Arc::clone(&outbox))),
            None,
            None,
        );
        Self {
            id,
            bridge,
            transport,
            channels: ChannelClient::new(),
            outbox,
            degraded: None,
            recovered: 0,
            disposed: false,
            conversations: BTreeSet::new(),
            healthy_waiters: Vec::new(),
            recovery: None,
            fragment_purge_timer: None,
        }
    }

    /// 本地稳定句柄。
    pub fn id(&self) -> BridgeSessionId {
        self.id
    }

    /// 对应 Dart `get bridge`。
    pub fn bridge(&self) -> &Payload {
        &self.bridge
    }

    /// 线上 `bridgeSessionId` —— 即 `bridge['bridgeSessionId']`。
    ///
    /// 与 [`BridgeSession::id`] 不同：这个字段在重开后**会变**。
    pub fn wire_bridge_session_id(&self) -> Option<&str> {
        self.bridge.get("bridgeSessionId").and_then(Value::as_str)
    }

    /// 对应 Dart `get workspaceKey`：`_bridge['workspaceKey'] as String?`。
    pub fn workspace_key(&self) -> Option<&str> {
        self.bridge.get("workspaceKey").and_then(Value::as_str)
    }

    /// 对应 Dart `get initialTaskId`：`_bridge['initialTaskId'] as String?`。
    pub fn initial_task_id(&self) -> Option<&str> {
        self.bridge.get("initialTaskId").and_then(Value::as_str)
    }

    /// 对应 Dart `degraded.value`。
    pub fn degraded(&self) -> Option<&str> {
        self.degraded.as_deref()
    }

    /// 对应 Dart `recovered.value`。
    pub fn recovered(&self) -> u32 {
        self.recovered
    }

    /// 对应 Dart `_disposed`。
    pub fn is_disposed(&self) -> bool {
        self.disposed
    }

    /// 对应 Dart `get transport`。
    pub fn transport(&self) -> &RpcFrameTransport {
        &self.transport
    }

    /// 对应 Dart `get transport`（可变）—— 宿主用它直接发消息；分片清理周期由核心自己驱动
    /// （见 [`ZemoteEvent::BridgeStackAttached`]），宿主不必再调 `purge_stale`。
    pub fn transport_mut(&mut self) -> &mut RpcFrameTransport {
        &mut self.transport
    }

    /// 对应 Dart `get channels`。
    pub fn channels(&self) -> &ChannelClient {
        &self.channels
    }

    /// 对应 Dart `get channels`（可变）—— 会话层/自动化层从这里发起通道 RPC。
    pub fn channels_mut(&mut self) -> &mut ChannelClient {
        &mut self.channels
    }

    /// 对应 Dart `_conversations` 的键集（`conversation(scope)` 建立的缓存）。
    pub fn conversation_keys(&self) -> impl Iterator<Item = &str> {
        self.conversations.iter().map(String::as_str)
    }

    /// 对应 Dart `_swap(bridge, transport, channels)`。
    fn swap(
        &mut self,
        bridge: Payload,
        transport: RpcFrameTransport,
        channels: ChannelClient,
        outbox: Arc<Mutex<Vec<Payload>>>,
    ) {
        self.bridge = bridge;
        self.transport = transport;
        self.channels = channels;
        self.outbox = outbox;
    }
}

/// 让 `BridgeSession` 直接可当 `ChannelRpc` 用 —— 对应 Dart 的 `bridge.channels.call(...)`。
///
/// PORT-NOTE: `automation_client.rs` / `channel_client.rs` 都留了「主线决定是否给
/// `BridgeSession` 加 `impl ChannelRpc`」的待办；这里直接补上，转发到内部的 `ChannelClient`。
impl ChannelRpc for BridgeSession {
    // 参数类型必须写全路径：本文件的 `Value` 是 `serde_json::Value`（见文件头
    // `use serde_json::{json, Value}`），而通道 RPC 的参数是 **IPC 值模型**
    // （`ipc_codec::Value`）——后者才是通道的真实线格式。两者同名不同型，混用会静默走错编码路径。
    fn call(
        &mut self,
        channel: &str,
        method: &str,
        args: Vec<crate::ipc_codec::Value>,
        timeout_ms: i64,
    ) -> u64 {
        self.channels.call(channel, method, args, timeout_ms)
    }
}

/// [`RpcFrameTransport`] 的 [`FrameSink`]：把载荷塞进该桥的出站队列。
///
/// 对应 Dart 里 `RpcFrameTransport(sendPayload: relay.sendPayload)` 这条回调。Rust 侧不能让
/// transport 直接借用门面里的 `RelayClient`（同一个 `self` 里借不出两个 `&mut`），所以经一个
/// 共享队列中转，由 [`ZemoteClient::pump`] 取走并喂给 relay。
///
/// PORT-NOTE: 用 `Arc<Mutex<..>>` 而非 `Rc<RefCell<..>>`，是为了让 `BridgeSession` 保持
/// `Send + Sync`（UniFFI 的对象要求）。实际访问始终在单线程内，锁不会竞争。
struct OutboxSink {
    outbox: Arc<Mutex<Vec<Payload>>>,
}

impl OutboxSink {
    fn new(outbox: Arc<Mutex<Vec<Payload>>>) -> Self {
        Self { outbox }
    }
}

impl FrameSink for OutboxSink {
    fn send(&mut self, payload: &RpcFramePayload) -> Result<()> {
        let value = serde_json::to_value(payload)?;
        let Value::Object(map) = value else {
            return Err(ProtocolError::InvalidFrame(
                "rpc-frame 载荷不是 JSON 对象".to_string(),
            ));
        };
        self.outbox
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .push(map);
        Ok(())
    }
}

// ===========================================================================
// 5. 门面
// ===========================================================================

/// 挂起请求的后续处理方式 —— 对应 Dart 各 `await request(...)` 之后那段代码。
#[derive(Debug, Clone)]
enum PendingKind {
    /// 对应 Dart `request()`：原样返回整个响应载荷。
    Raw,
    /// 对应 Dart `bootstrap()`：`(res['result'] as Map?)?.cast() ?? res`。
    BootstrapResult,
    /// 对应 Dart `listWorkspaces()`：`res['result']`（可能是 null）。
    WorkspaceListResult,
    /// 对应 Dart `openBridge()` 成功后的建栈 + `sendMobileViewState`。
    OpenBridge {
        /// 请求里那个新生成的 `bridgeSessionId`。
        requested_bridge_session_id: String,
        /// `openBridge` 的入参 `workspaceKey`。
        workspace_key: String,
        /// `openBridge` 的入参 `taskId`。
        task_id: Option<String>,
    },
    /// 对应 Dart `_recoverBridgeOnce` 阶段 1（内部 `reconnectWorkspace`）。
    RecoveryReconnect {
        /// 哪个桥。
        session: BridgeSessionId,
        /// 第几次尝试。
        attempt: u32,
    },
    /// 对应 Dart `_reopenBridge`（阶段 2）。
    RecoveryReopen {
        /// 哪个桥。
        session: BridgeSessionId,
        /// 第几次尝试。
        attempt: u32,
        /// 请求里那个新生成的 `bridgeSessionId`。
        requested_bridge_session_id: String,
    },
}

/// 一条挂起请求。
///
/// Dart 用 `_pendingMatchers` + `_pendingCompleters` 两张平行表；这里合成一条记录，并挂上它
/// 的超时定时器。`Completer` 本身由宿主持有（Rust 侧无 future）。
#[derive(Debug, Clone)]
struct Pending {
    matcher: PayloadMatcher,
    kind: PendingKind,
    timer: TimerId,
}

/// 定时器用途 —— 到点后要做什么。
#[derive(Debug, Clone)]
enum TimerPurpose {
    /// 对应 Dart `request()` 的 `completer.future.timeout(...)`。
    Request {
        /// 该请求的 `requestId`。
        request_id: String,
    },
    /// 对应 Dart `waitPaired()` 里的 `Timer(timeout, ...)`。
    WaitPaired {
        /// 本地令牌。
        token: String,
    },
    /// 对应 Dart `BridgeSession.waitHealthy()` 的 `future.timeout(...)`。
    BridgeHealthy {
        /// 哪个桥。
        session: BridgeSessionId,
        /// 本地令牌。
        token: String,
    },
    /// 对应 Dart `_recoverBridgeOnce` 里 `.timeout(15s)`。
    RecoveryOuter {
        /// 哪个桥。
        session: BridgeSessionId,
        /// 第几次尝试。
        attempt: u32,
        /// 阶段 1 的 `requestId`。
        request_id: String,
    },
    /// 对应 Dart `_recoverBridgeWithRetry` 里 `Future.delayed(3s)`。
    RecoveryRetry {
        /// 哪个桥。
        session: BridgeSessionId,
        /// 刚失败的那次尝试序号。
        attempt: u32,
    },
    /// 对应 Dart `RpcFrameTransport` 构造函数里的 `Timer.periodic(30s)` → `_purgeStale()`。
    ///
    /// 定时器由**核心自己**驱动：到点清掉该桥超时未凑齐的分片装配体，并重排下一个周期
    /// （见 [`ZemoteClient::on_fragment_purge`]）。宿主不再需要自己做这件事。
    FragmentPurge {
        /// 哪个桥。
        session: BridgeSessionId,
    },
}

/// `waitPaired` 的登记项。
#[derive(Debug, Clone)]
struct PairWaiter {
    token: String,
    timer: TimerId,
}

/// 对应 Dart `class ZemoteClient`（网页端 `otn()` 流程的门面）。
pub struct ZemoteClient {
    /// 对应 Dart `final ZemoteConnectionParams params`。
    params: ZemoteConnectionParams,
    /// 对应 Dart `zemotePlatformName()` / `zemoteAppName` 的取数结果。
    device_info: DeviceInfo,

    /// 对应 Dart `late final RelayClient relay`。
    relay: RelayClient,
    /// 对应 Dart `relay` 内部那个 `WebSocketChannel`（I/O 边界，宿主实现）。
    relay_transport: Box<dyn RelayTransport>,

    /// 对应 Dart `_pendingMatchers` + `_pendingCompleters` + 各自的超时。
    pending: BTreeMap<String, Pending>,
    /// 对应 Dart 各处 `Timer` / `Future.timeout` 的登记表。
    timers: BTreeMap<TimerId, TimerPurpose>,
    /// 对应 Dart `waitPaired` 挂上的那些 `stateListenable` 监听器。
    pair_waiters: Vec<PairWaiter>,

    /// 对应 Dart `_needsBridgeRecovery`。
    needs_bridge_recovery: bool,
    /// 对应 Dart `_bridgeGeneration`（`++_bridgeGeneration`）。
    bridge_generation: i64,
    /// 对应 Dart `_activeBridges`（有序）。
    sessions: Vec<BridgeSession>,
    /// 对应 Dart `_frameRouters` 的**键**：线上 `bridgeSessionId` → 本地句柄。
    frame_routers: BTreeMap<String, BridgeSessionId>,
    /// 对应 Dart `_pendingBridgePayloads`（`Initialize` 竞态缓冲）。
    pending_bridge_payloads: BTreeMap<String, Vec<Value>>,
    /// 对应 Dart `_recoveringBridges`（按本地句柄记；Dart 记的是对象）。
    recovering: BTreeSet<BridgeSessionId>,

    /// `dispose()` 之后：对应 Dart 摘掉 `_payloadSub` 与 `_onRelayState` 监听。
    ///
    /// PORT-NOTE: Dart 还 `_workspaceListUpdatedController.close()`，订阅者收到 done；Rust 侧由
    /// 宿主在自己的流上处理（收到 `Relay(StateChanged(Closed))` 即可收尾），这里只做状态位。
    disposed: bool,

    /// 句柄分配器。
    next_timer_id: u64,
    next_session_id: u64,
    next_local_token: u64,
    /// 对应 Dart `_reqId(prefix)` 里的 `generateRequestId`（熵源由宿主注入）。
    rng: Box<dyn SecureRandom>,
}

impl ZemoteClient {
    /// 构造。对应 Dart `ZemoteClient(this.params, {this.onLog})`。
    ///
    /// * `relay_transport`：宿主的 WebSocket 实现（对应 Dart `RelayClient` 内部的
    ///   `WebSocketChannel`）。门面持有并驱动它。
    /// * `now_ms`：Unix 毫秒，充当 `RelayClient` 构造函数里那两个 `DateTime.now()`。
    pub fn new(
        params: ZemoteConnectionParams,
        config: ZemoteClientConfig,
        relay_transport: Box<dyn RelayTransport>,
        now_ms: i64,
    ) -> Self {
        Self::new_with_random(
            params,
            config,
            relay_transport,
            now_ms,
            Box::new(OsSecureRandom::new()),
        )
    }

    /// 同 [`ZemoteClient::new`]，但由调用方提供熵源（测试用固定字节即可复现 requestId）。
    pub fn new_with_random(
        params: ZemoteConnectionParams,
        config: ZemoteClientConfig,
        relay_transport: Box<dyn RelayTransport>,
        now_ms: i64,
        rng: Box<dyn SecureRandom>,
    ) -> Self {
        let device_info = DeviceInfo {
            platform: zemote_platform_name(config.is_web, config.target_platform).to_string(),
            // Dart: `params.appVersion ?? 'web'`
            version: params
                .app_version
                .clone()
                .unwrap_or_else(|| "web".to_string()),
            name: ZEMOTE_APP_NAME.to_string(),
        };
        let relay = RelayClient::new(params.clone(), device_info.platform.clone(), now_ms);
        Self {
            params,
            device_info,
            relay,
            relay_transport,
            pending: BTreeMap::new(),
            timers: BTreeMap::new(),
            pair_waiters: Vec::new(),
            needs_bridge_recovery: false,
            bridge_generation: 0,
            sessions: Vec::new(),
            frame_routers: BTreeMap::new(),
            pending_bridge_payloads: BTreeMap::new(),
            recovering: BTreeSet::new(),
            disposed: false,
            next_timer_id: 0,
            next_session_id: 0,
            next_local_token: 0,
            rng,
        }
    }

    // -----------------------------------------------------------------------
    // 5.1 只读视图
    // -----------------------------------------------------------------------

    /// 对应 Dart `params`。
    pub fn params(&self) -> &ZemoteConnectionParams {
        &self.params
    }

    /// 本客户端上报的设备身份（`mobile-view-state-update.deviceInfo`，与 relay `auth_init.meta` 同源）。
    pub fn device_info(&self) -> &DeviceInfo {
        &self.device_info
    }

    /// 内部 relay 的只读视图（UI 若要直接看 `state` 用它；写请走 [`ZemoteClient::step`]）。
    pub fn relay(&self) -> &RelayClient {
        &self.relay
    }

    /// 宿主注入的 relay 传输（诊断/测试用；正常驱动不需要它）。
    pub fn relay_transport_mut(&mut self) -> &mut dyn RelayTransport {
        self.relay_transport.as_mut()
    }

    /// 对应 Dart `_activeBridges`（有序）。
    pub fn active_bridges(&self) -> &[BridgeSession] {
        &self.sessions
    }

    /// 按本地句柄取桥。
    pub fn bridge(&self, session: BridgeSessionId) -> Option<&BridgeSession> {
        self.sessions.iter().find(|s| s.id == session)
    }

    /// 按本地句柄取桥（可变）—— 宿主在这里驱动 `channels` / `transport`。
    pub fn bridge_mut(&mut self, session: BridgeSessionId) -> Option<&mut BridgeSession> {
        self.sessions.iter_mut().find(|s| s.id == session)
    }

    /// 对应 Dart `dispose()` 之后的 `_disposed`。
    pub fn is_disposed(&self) -> bool {
        self.disposed
    }

    /// 对应 Dart `_recoveringBridges`：**正在恢复循环里**的桥句柄集合。
    ///
    /// 与 [`BridgeSession::degraded`] 的区别：`degraded` 是给用户看的文案（可能是
    /// `"recovering"`，也可能是 `"reopen-failed: ..."`），而本集合只表示"恢复循环还在跑"。
    /// 宿主（FFI 层）的快照需要它，所以给出只读访问器——靠"最后观察到的 degraded 值"去推导
    /// 在边界情形下会不一致。
    pub fn recovering(&self) -> &BTreeSet<BridgeSessionId> {
        &self.recovering
    }

    // -----------------------------------------------------------------------
    // 5.2 驱动
    // -----------------------------------------------------------------------

    /// 状态机推进一步：喂一个输入，拿回要执行的动作。
    ///
    /// 末尾会自动 [`ZemoteClient::pump`]（把通道/传输攒下的出站帧推给 relay）。
    pub fn step(&mut self, input: ZemoteInput<'_>, now_ms: i64) -> Vec<ZemoteEvent> {
        let mut events = Vec::new();
        match input {
            ZemoteInput::Relay(relay_input) => events.extend(self.drive_relay(relay_input, now_ms)),
            ZemoteInput::TimerFired(timer) => self.on_timer(timer, now_ms, &mut events),
        }
        events.extend(self.pump(now_ms));
        events
    }

    /// 把「通道 → 传输 → relay」这条链上攒下的东西推出去。
    ///
    /// 对应 Dart 里 `ChannelClient(sendBody: transport.sendMessage)` 与
    /// `RpcFrameTransport(sendPayload: relay.sendPayload)` 两段同步回调：Dart 里是同步发生的，
    /// Rust 侧因为出站要经队列中转，需要宿主在**直接驱动 `channels` / `transport` 之后**显式调
    /// 一次（`step` 内部已经调了）。
    pub fn pump(&mut self, now_ms: i64) -> Vec<ZemoteEvent> {
        let mut events = Vec::new();
        let mut outbound: Vec<Payload> = Vec::new();

        for session in self.sessions.iter_mut() {
            for line in session.channels.drain_logs() {
                events.push(ZemoteEvent::Log(line));
            }
            for body in session.channels.drain_outgoing() {
                if let Err(error) = session.transport.send_message(&body) {
                    // Dart: `sendBody` 抛出的异常会从 `call` 里冒出来。Rust 侧已经离开调用点，
                    // 只能记一条日志（帧没发出去，调用方会按自己的通道超时失败）。
                    events.push(ZemoteEvent::Log(format!("[bridge] send failed: {error}")));
                }
            }
            let mut queue = session.outbox.lock().unwrap_or_else(PoisonError::into_inner);
            outbound.append(&mut queue);
        }

        for payload in outbound {
            events.extend(self.drive_relay(RelayInput::SendPayload(payload), now_ms));
        }
        events
    }

    /// 把输入喂给内部 relay，并把它的输出转成门面事件（含 `_dispatchPayload` / `_onRelayState`）。
    fn drive_relay(&mut self, input: RelayInput<'_>, now_ms: i64) -> Vec<ZemoteEvent> {
        let relay_events = {
            let transport = self.relay_transport.as_mut();
            self.relay.step(input, transport, now_ms)
        };
        self.handle_relay_events(relay_events, now_ms)
    }

    fn handle_relay_events(
        &mut self,
        relay_events: Vec<RelayEvent>,
        now_ms: i64,
    ) -> Vec<ZemoteEvent> {
        let mut events = Vec::new();
        for event in relay_events {
            match event {
                // Dart: `_payloadSub = relay.payloads.listen(_dispatchPayload)`
                RelayEvent::Payload(payload) => {
                    if !self.disposed {
                        self.dispatch_payload(Value::Object(payload), now_ms, &mut events);
                    }
                }
                RelayEvent::StateChanged(state) => {
                    events.push(ZemoteEvent::Relay(RelayEvent::StateChanged(state)));
                    // 监听器注册顺序（Dart 构造函数）：`_payloadSub` 先、`_onRelayState` 后；
                    // `waitPaired` 的 listener 在方法调用时才挂上，所以排在 `_onRelayState` 之后。
                    if !self.disposed {
                        self.on_relay_state(state, now_ms, &mut events);
                    }
                    self.resolve_pair_waiters(state, &mut events);
                }
                other => events.push(ZemoteEvent::Relay(other)),
            }
        }
        events
    }

    // -----------------------------------------------------------------------
    // 5.3 `_dispatchPayload`
    // -----------------------------------------------------------------------

    /// 对应 Dart `_dispatchPayload(Map<String, dynamic> payload)`。
    fn dispatch_payload(&mut self, payload: Value, now_ms: i64, events: &mut Vec<ZemoteEvent>) {
        // 取成 owned，避免后面把 `payload` 移进 `_pendingBridgePayloads` 时的借用冲突。
        let payload_type = payload
            .get("zcode_type")
            .and_then(Value::as_str)
            .map(str::to_string);
        let payload_type = payload_type.as_deref();

        if payload_type == Some(zcode_type::WORKSPACE_LIST_UPDATED) {
            // Dart: `_workspaceListUpdatedController.add(payload['result'])`（缺失即 null）。
            events.push(ZemoteEvent::WorkspaceListUpdated(
                payload.get("result").cloned().unwrap_or(Value::Null),
            ));
            return;
        }

        if payload_type == Some(zcode_type::BRIDGE_DEGRADED) {
            self.handle_bridge_degraded(&payload, now_ms, events);
            return;
        }

        // 对端主动报错。网页端收到 `app-error` 会直接 dispose；我们只透传给宿主，
        // 由它决定提示/断开（宿主才知道当前在哪个界面）。
        if payload_type == Some(zcode_type::APP_ERROR) {
            events.push(ZemoteEvent::AppError {
                reason: payload
                    .get("reason")
                    .and_then(Value::as_str)
                    .map(str::to_string),
                error: payload
                    .get("error")
                    .and_then(Value::as_str)
                    .map(str::to_string),
            });
            return;
        }

        if payload_type == Some(zcode_type::RPC_FRAME)
            || payload_type == Some(zcode_type::RPC_FRAME_ACK)
        {
            // Dart: `final id = payload['bridgeSessionId'] as String?;`
            // PORT-NOTE: `as String?` 对非字符串抛 `TypeError`；这里按「不是字符串即 null」处理。
            let wire_id = payload
                .get("bridgeSessionId")
                .and_then(Value::as_str)
                .map(str::to_string);
            match wire_id {
                Some(wire_id) => {
                    let router = self.frame_routers.get(&wire_id).copied();
                    match router {
                        // Dart: `router(payload)` —— 即 `transport.acceptPayload(payload)`
                        Some(session) => self.route_to_bridge(session, &payload, now_ms, events),
                        // Dart: `(_pendingBridgePayloads[id] ??= []).add(payload)` ——
                        // `openBridge` 的 await 续体还没把 transport 挂上时的竞态缓冲。
                        None => self
                            .pending_bridge_payloads
                            .entry(wire_id)
                            .or_default()
                            .push(payload),
                    }
                }
                // Dart: `id == null` → 既不路由也不缓存，直接丢。
                None => {}
            }
            return;
        }

        // Dart 的 `k()` 辅助逻辑：每个挂起匹配器都拿这个载荷试一遍；
        // 响应**不保证**回显 requestId，所以不能按 requestId 查表。
        let mut done: Vec<String> = self
            .pending
            .iter()
            .filter(|(_, pending)| pending.matcher.matches(&payload))
            .map(|(request_id, _)| request_id.clone())
            .collect();
        // PORT-NOTE: Dart 的 `Map` 是插入序，这里是字典序。只有「一次载荷同时满足多个挂起项」
        // 时事件顺序会不同（每个挂起项各自完成，互不影响）。
        done.sort();

        for request_id in done {
            let Some(pending) = self.pending.remove(&request_id) else {
                continue;
            };
            self.cancel_timer(pending.timer, events);
            self.complete_pending(request_id, pending, payload.clone(), now_ms, events);
        }
    }

    /// 把载荷交给某个桥的传输栈，并把重组出的逻辑消息喂给它的通道客户端。
    ///
    /// 对应 Dart 里 `transport.acceptPayload(payload)` +
    /// `transport.messages.listen(channels.handleMessage)` 这条接线。
    fn route_to_bridge(
        &mut self,
        session: BridgeSessionId,
        payload: &Value,
        now_ms: i64,
        events: &mut Vec<ZemoteEvent>,
    ) {
        let Some(bridge) = self.sessions.iter_mut().find(|s| s.id == session) else {
            return;
        };
        let outcome = bridge.transport.accept_payload(payload, now_ms);
        for line in outcome.logs {
            events.push(ZemoteEvent::Log(line));
        }
        if let Some(message) = outcome.message {
            for event in bridge.channels.handle_message(&message) {
                events.push(ZemoteEvent::Channel(event));
            }
        }
    }

    /// 对应 Dart `_handleBridgeDegraded(payload)`。
    fn handle_bridge_degraded(
        &mut self,
        payload: &Value,
        now_ms: i64,
        events: &mut Vec<ZemoteEvent>,
    ) {
        let wire_id = payload.get("bridgeSessionId").and_then(Value::as_str);
        let reason = payload.get("reason").and_then(Value::as_str);
        events.push(ZemoteEvent::Log(format!(
            "[bridge] degraded: {} reason={}",
            dart_opt_str(wire_id),
            dart_opt_str(reason)
        )));

        let mut found = false;
        for session in self.sessions.iter_mut() {
            // PORT-NOTE: Dart 是 `session.bridge['bridgeSessionId'] == bridgeSessionId`，
            // 两边都缺失时 `null == null` 为真 —— 照抄这个宽松比较。
            if session.bridge.get("bridgeSessionId").and_then(Value::as_str) == wire_id {
                // Dart: `session.degraded.value = reason ?? 'unknown';`
                let value = Some(reason.unwrap_or("unknown").to_string());
                if session.degraded != value {
                    session.degraded = value.clone();
                    events.push(ZemoteEvent::BridgeDegraded {
                        session: session.id,
                        degraded: value,
                    });
                }
                found = true;
            }
        }
        if found {
            self.recover_active_bridges(now_ms, events);
        }
    }

    /// 挂起请求完成后的分派 —— 对应 Dart 各 `await request(...)` 之后的代码。
    fn complete_pending(
        &mut self,
        request_id: String,
        pending: Pending,
        payload: Value,
        now_ms: i64,
        events: &mut Vec<ZemoteEvent>,
    ) {
        match pending.kind {
            PendingKind::Raw => events.push(ZemoteEvent::Completed {
                token: request_id,
                outcome: Completion::Payload(payload),
            }),
            PendingKind::BootstrapResult => {
                // Dart: `(res['result'] as Map?)?.cast<String, dynamic>() ?? res`
                // （先取成 owned，避免与「整包回落」那一支的移动冲突。）
                let outcome = match payload.get("result").cloned() {
                    None | Some(Value::Null) => Completion::Payload(payload),
                    Some(Value::Object(map)) => Completion::Payload(Value::Object(map)),
                    // Dart 的 `as Map?` 对非 Map 抛 `TypeError`（不是回落到 res）。
                    Some(other) => Completion::Failed(ZemoteError::Protocol(format!(
                        "bootstrap result 不是 Map: {other}"
                    ))),
                };
                events.push(ZemoteEvent::Completed {
                    token: request_id,
                    outcome,
                });
            }
            PendingKind::WorkspaceListResult => {
                // Dart: `return res['result'];`（缺失即 null）
                events.push(ZemoteEvent::Completed {
                    token: request_id,
                    outcome: Completion::Payload(
                        payload.get("result").cloned().unwrap_or(Value::Null),
                    ),
                });
            }
            PendingKind::OpenBridge {
                requested_bridge_session_id,
                workspace_key,
                task_id,
            } => self.finish_open_bridge(
                request_id,
                payload,
                requested_bridge_session_id,
                workspace_key,
                task_id,
                now_ms,
                events,
            ),
            PendingKind::RecoveryReconnect { session, attempt } => {
                self.finish_recovery_reconnect(session, attempt, request_id, payload, now_ms, events)
            }
            PendingKind::RecoveryReopen {
                session,
                attempt,
                requested_bridge_session_id,
            } => self.finish_recovery_reopen(
                session,
                attempt,
                requested_bridge_session_id,
                payload,
                now_ms,
                events,
            ),
        }
    }

    // -----------------------------------------------------------------------
    // 5.4 `request` 与各请求构造
    // -----------------------------------------------------------------------

    /// 对应 Dart `request(payload, match, {timeout})`。
    ///
    /// 返回线上 `requestId`（同时是完成事件的 `token`）。挂起项与超时定时器都在这里登记。
    pub fn request(
        &mut self,
        payload: Payload,
        matcher: PayloadMatcher,
        timeout_ms: i64,
        now_ms: i64,
        events: &mut Vec<ZemoteEvent>,
    ) -> ZemoteResult<String> {
        self.request_with_kind(payload, matcher, timeout_ms, now_ms, PendingKind::Raw, events)
    }

    fn request_with_kind(
        &mut self,
        payload: Payload,
        matcher: PayloadMatcher,
        timeout_ms: i64,
        now_ms: i64,
        kind: PendingKind,
        events: &mut Vec<ZemoteEvent>,
    ) -> ZemoteResult<String> {
        // Dart: `final requestId = payload['requestId'] as String;`
        // PORT-NOTE: Dart 这里是强转，缺失/类型不符会抛 `TypeError`；这里收成协议错误。
        let request_id = payload
            .get("requestId")
            .and_then(Value::as_str)
            .map(str::to_string)
            .ok_or_else(|| {
                ZemoteError::Protocol("request 载荷缺少字符串 requestId".to_string())
            })?;

        let timer = self.arm_timer(
            TimerPurpose::Request {
                request_id: request_id.clone(),
            },
            timeout_ms,
            events,
        );
        self.pending.insert(
            request_id.clone(),
            Pending {
                matcher,
                kind,
                timer,
            },
        );
        // Dart: `relay.sendPayload(payload);`
        events.extend(self.drive_relay(RelayInput::SendPayload(payload), now_ms));
        Ok(request_id)
    }

    /// 对应 Dart `bootstrap()`：`bootstrap-request` → `bootstrap-response`。
    pub fn bootstrap(&mut self, now_ms: i64) -> ZemoteResult<Operation> {
        let request_id = self.req_id("bootstrap");
        let matcher = PayloadMatcher::new(&[zcode_type::BOOTSTRAP_RESPONSE])
            .with_field("requestId", json!(request_id));
        let mut events = Vec::new();
        self.request_with_kind(
            bootstrap_request(&request_id),
            matcher,
            REQUEST_TIMEOUT_MS,
            now_ms,
            PendingKind::BootstrapResult,
            &mut events,
        )?;
        Ok(Operation {
            token: request_id,
            events,
        })
    }

    /// 对应 Dart `listWorkspaces()`：`workspace-list-request` → `workspace-list-response`。
    pub fn list_workspaces(&mut self, now_ms: i64) -> ZemoteResult<Operation> {
        let request_id = self.req_id("workspace-list");
        let matcher = PayloadMatcher::new(&[zcode_type::WORKSPACE_LIST_RESPONSE])
            .with_field("requestId", json!(request_id));
        let mut events = Vec::new();
        self.request_with_kind(
            list_workspaces_request(&request_id),
            matcher,
            REQUEST_TIMEOUT_MS,
            now_ms,
            PendingKind::WorkspaceListResult,
            &mut events,
        )?;
        Ok(Operation {
            token: request_id,
            events,
        })
    }

    /// 对应 Dart `openBridge(workspaceKey, {taskId, timeout})`。
    ///
    /// 成功时发 [`ZemoteEvent::Completed`] + [`Completion::BridgeOpened`]
    /// （宿主再用 [`ZemoteClient::bridge`] 取桥）。
    pub fn open_bridge(
        &mut self,
        workspace_key: &str,
        task_id: Option<&str>,
        timeout_ms: i64,
        now_ms: i64,
    ) -> ZemoteResult<Operation> {
        let bridge_session_id = self.req_id("bridge");
        self.bridge_generation += 1;
        let generation = self.bridge_generation;
        let request_id = self.req_id("workspace-bridge");
        let matcher = PayloadMatcher::new(&[
            zcode_type::WORKSPACE_BRIDGE_READY,
            zcode_type::WORKSPACE_BRIDGE_ERROR,
        ])
        .with_field("bridgeSessionId", json!(bridge_session_id));
        let mut events = Vec::new();
        self.request_with_kind(
            open_bridge_request(
                &request_id,
                &bridge_session_id,
                generation,
                workspace_key,
                task_id,
            ),
            matcher,
            timeout_ms,
            now_ms,
            PendingKind::OpenBridge {
                requested_bridge_session_id: bridge_session_id,
                workspace_key: workspace_key.to_string(),
                task_id: task_id.map(str::to_string),
            },
            &mut events,
        )?;
        Ok(Operation {
            token: request_id,
            events,
        })
    }

    /// 对应 Dart `reconnectWorkspace(workspaceKey)`。
    ///
    /// 返回值就是整个响应载荷（调用方读它的 `success` 字段）。
    pub fn reconnect_workspace(
        &mut self,
        workspace_key: &str,
        now_ms: i64,
    ) -> ZemoteResult<Operation> {
        let mut events = Vec::new();
        let request_id = self.reconnect_workspace_with_kind(
            workspace_key,
            REQUEST_TIMEOUT_MS,
            now_ms,
            None,
            &mut events,
        )?;
        Ok(Operation {
            token: request_id,
            events,
        })
    }

    fn reconnect_workspace_with_kind(
        &mut self,
        workspace_key: &str,
        timeout_ms: i64,
        now_ms: i64,
        recovery: Option<(BridgeSessionId, u32)>,
        events: &mut Vec<ZemoteEvent>,
    ) -> ZemoteResult<String> {
        let request_id = self.req_id("workspace-reconnect");
        let matcher = PayloadMatcher::new(&[zcode_type::WORKSPACE_RECONNECT_RESPONSE])
            .with_field("requestId", json!(request_id))
            .with_field("workspaceKey", json!(workspace_key));
        let kind = match recovery {
            Some((session, attempt)) => PendingKind::RecoveryReconnect { session, attempt },
            None => PendingKind::Raw,
        };
        self.request_with_kind(
            reconnect_workspace_request(&request_id, workspace_key),
            matcher,
            timeout_ms,
            now_ms,
            kind,
            events,
        )
    }

    /// 对应 Dart `sendMobileViewState({workspaceKey, taskId})`。
    ///
    /// `now_ms` 充当 Dart 的 `DateTime.now().millisecondsSinceEpoch`。
    pub fn send_mobile_view_state(
        &mut self,
        workspace_key: &str,
        task_id: Option<&str>,
        now_ms: i64,
    ) -> ZemoteResult<Vec<ZemoteEvent>> {
        let payload =
            mobile_view_state_update_payload(workspace_key, task_id, now_ms, &self.device_info)?;
        Ok(self.drive_relay(RelayInput::SendPayload(payload), now_ms))
    }

    /// 对应 Dart `connect()`（`relay.start()`）。
    ///
    /// PORT-NOTE: Dart 的 `Future` 在 `await socket.ready` 之后 resolve；Rust 侧没有 future，
    /// 宿主改为观察 `RelayEvent::StateChanged`（`Connecting` → `Authenticating` / `Error`）。
    /// 配对等待请用 [`ZemoteClient::wait_paired`]。
    pub fn connect(&mut self, now_ms: i64) -> Vec<ZemoteEvent> {
        self.drive_relay(RelayInput::Start, now_ms)
    }

    /// 对应 Dart `pokeRelay()`（`relay.poke()`）。
    pub fn poke_relay(&mut self, now_ms: i64) -> Vec<ZemoteEvent> {
        self.drive_relay(RelayInput::Poke, now_ms)
    }

    /// 对应 Dart `waitPaired({timeout})`。
    ///
    /// 返回 `None` 表示**已经配对**（Dart 返回一个已完成 future）；否则返回令牌，配对成功发
    /// [`Completion::Ok`]，超时发 `Timeout("pairing timeout")`。
    pub fn wait_paired(&mut self, timeout_ms: i64) -> Option<Operation> {
        // Dart: `if (relay.state == RelayState.paired) return Future.value();`
        if self.relay.state() == RelayState::Paired {
            return None;
        }
        let token = self.next_local_token("pair");
        let mut events = Vec::new();
        let timer = self.arm_timer(
            TimerPurpose::WaitPaired {
                token: token.clone(),
            },
            timeout_ms,
            &mut events,
        );
        self.pair_waiters.push(PairWaiter {
            token: token.clone(),
            timer,
        });
        Some(Operation { token, events })
    }

    /// 对应 Dart `BridgeSession.waitHealthy({timeout})`。
    ///
    /// 返回 `None` 表示**已经健康**（Dart 返回已完成 future）；否则返回令牌，桥恢复健康发
    /// [`Completion::Ok`]，超时发 `Timeout("bridge 恢复超时: <degraded>")`。
    pub fn bridge_wait_healthy(
        &mut self,
        session: BridgeSessionId,
        timeout_ms: i64,
    ) -> ZemoteResult<Option<Operation>> {
        let Some(bridge) = self.sessions.iter().find(|s| s.id == session) else {
            // Dart 里这个方法挂在 `BridgeSession` 对象上，不存在「未知会话」；Rust 侧句柄由宿主
            // 传递，所以要显式报错。
            return Err(ZemoteError::InvalidState(format!("未知桥会话 {session}")));
        };
        if bridge.disposed || bridge.degraded.is_none() {
            return Ok(None);
        }
        let token = self.next_local_token("healthy");
        let mut events = Vec::new();
        let timer = self.arm_timer(
            TimerPurpose::BridgeHealthy {
                session,
                token: token.clone(),
            },
            timeout_ms,
            &mut events,
        );
        if let Some(bridge) = self.sessions.iter_mut().find(|s| s.id == session) {
            bridge.healthy_waiters.push(HealthyWaiter {
                token: token.clone(),
                timer,
            });
        }
        Ok(Some(Operation { token, events }))
    }

    /// 对应 Dart `BridgeSession.conversation(scope, {onLog})`。
    ///
    /// 首次为某个 key 调用时发 [`ZemoteEvent::ConversationOpened`]（宿主据此建立会话层对象），
    /// 之后同 key 复用。返回的 `token` 就是缓存键。
    ///
    /// PORT-NOTE: Dart 的注释说 `_conversations`「cleared on stack swap」，但 `_swap` **从不**
    /// 清它 —— 真正让新桥重新握手的是 `ConversationTransport` 构造时挂在 `session.recovered` 上的
    /// 监听器（`_onBridgeRecovered` 把 `_handshaken` 等状态清空）。这里照抄行为：**不清缓存**，
    /// 宿主收到 [`ZemoteEvent::BridgeRecovered`] 后自行重置会话层的握手状态。
    pub fn conversation(
        &mut self,
        session: BridgeSessionId,
        scope: Payload,
    ) -> ZemoteResult<Operation> {
        let key = conversation_key(&scope);
        let Some(bridge) = self.sessions.iter_mut().find(|s| s.id == session) else {
            return Err(ZemoteError::InvalidState(format!("未知桥会话 {session}")));
        };
        let mut events = Vec::new();
        // Dart: `_conversations.putIfAbsent(key, () => ConversationTransport(...))`
        if bridge.conversations.insert(key.clone()) {
            events.push(ZemoteEvent::ConversationOpened {
                session,
                key: key.clone(),
                scope,
            });
        }
        Ok(Operation {
            token: key,
            events,
        })
    }

    /// 对应 Dart `dispose()`。
    pub fn dispose(&mut self, now_ms: i64) -> Vec<ZemoteEvent> {
        let mut events = Vec::new();
        // Dart: `relay.stateListenable.removeListener(_onRelayState); await _payloadSub?.cancel();`
        // —— 摘掉监听：此后 relay 的载荷与状态变化都不再进 `_dispatchPayload` / `_onRelayState`。
        self.disposed = true;
        // Dart: `for (final s in List.from(_activeBridges)) s.dispose();`
        let ids: Vec<BridgeSessionId> = self.sessions.iter().map(|s| s.id).collect();
        for id in ids {
            self.dispose_session(id, &mut events);
        }
        // Dart: `await relay.dispose();`
        events.extend(self.drive_relay(RelayInput::Dispose, now_ms));
        // PORT-NOTE: Dart 还 `await _workspaceListUpdatedController.close()`；Rust 侧宿主在自己的流上
        // 收尾（`Relay(StateChanged(Closed))`）。
        // PORT-NOTE: Dart 不取消挂起的 request / waitPaired / waitHealthy（它们的 `Future.timeout`
        // 仍在跑）。这里同样不清 `pending` / `timers` / `pair_waiters`。
        events
    }

    // -----------------------------------------------------------------------
    // 5.5 `_onRelayState` 与桥恢复
    // -----------------------------------------------------------------------

    /// 对应 Dart `_onRelayState()`。
    fn on_relay_state(&mut self, state: RelayState, now_ms: i64, events: &mut Vec<ZemoteEvent>) {
        if state == RelayState::Reconnecting || state == RelayState::Error {
            if !self.sessions.is_empty() {
                self.needs_bridge_recovery = true;
                // Dart: `if (s.degraded.value == null) s.degraded.value = 'reconnecting';`
                for index in 0..self.sessions.len() {
                    if self.sessions[index].degraded.is_none() {
                        self.sessions[index].degraded = Some("reconnecting".to_string());
                        events.push(ZemoteEvent::BridgeDegraded {
                            session: self.sessions[index].id,
                            degraded: Some("reconnecting".to_string()),
                        });
                    }
                }
            }
            return;
        }
        if state == RelayState::Paired && self.needs_bridge_recovery {
            self.needs_bridge_recovery = false;
            self.recover_active_bridges(now_ms, events);
        }
    }

    /// 对应 Dart `_recoverActiveBridges()`。
    fn recover_active_bridges(&mut self, now_ms: i64, events: &mut Vec<ZemoteEvent>) {
        events.push(ZemoteEvent::Log(format!(
            "[bridge] recovering {} bridge(s)",
            self.sessions.len()
        )));
        // Dart: `for (final session in List<BridgeSession>.from(_activeBridges))` —— 先取快照。
        let snapshot: Vec<BridgeSessionId> = self.sessions.iter().map(|s| s.id).collect();
        for session in snapshot {
            if self.recovering.contains(&session) {
                continue;
            }
            self.recovering.insert(session);
            self.recover_once(session, 1, now_ms, events);
        }
    }

    /// 对应 Dart `_recoverBridgeWithRetry(session)` 的循环体前半 + `_recoverBridgeOnce(session)`。
    fn recover_once(
        &mut self,
        session: BridgeSessionId,
        attempt: u32,
        now_ms: i64,
        events: &mut Vec<ZemoteEvent>,
    ) {
        // Dart: `if (session._disposed) return;`（`finally` 里仍会 `_recoveringBridges.remove`）
        let Some(bridge) = self.sessions.iter().find(|s| s.id == session) else {
            self.recovering.remove(&session);
            return;
        };
        if bridge.disposed {
            self.recovering.remove(&session);
            return;
        }
        // Dart: `final workspaceKey = session.bridge['workspaceKey'] as String?;`
        let workspace_key = bridge.workspace_key().map(str::to_string);
        let Some(workspace_key) = workspace_key else {
            // Dart: 没有 workspaceKey 就没什么可重连的，清 degraded 让命令解除阻塞。
            self.set_degraded(session, None, events);
            self.recovering.remove(&session);
            return;
        };
        // Dart: `session.degraded.value = 'recovering';`
        self.set_degraded(session, Some("recovering".to_string()), events);

        // ---- 阶段 1：便宜的 workspace-reconnect-request ----
        let mut inner_events = Vec::new();
        let request_id = match self.reconnect_workspace_with_kind(
            &workspace_key,
            REQUEST_TIMEOUT_MS,
            now_ms,
            Some((session, attempt)),
            &mut inner_events,
        ) {
            Ok(request_id) => request_id,
            Err(error) => {
                // Dart 里 `request()` 不会在这里失败（payload 是本地拼的），保守起见走阶段 2。
                events.extend(inner_events);
                events.push(ZemoteEvent::Log(format!(
                    "[bridge] reconnect-request failed: {error}"
                )));
                self.start_reopen(session, &workspace_key, attempt, now_ms, events);
                return;
            }
        };
        events.extend(inner_events);
        let Some(inner_timer) = self.pending.get(&request_id).map(|pending| pending.timer) else {
            return;
        };
        // Dart: `.timeout(const Duration(seconds: 15))`（外层）
        let outer_timer = self.arm_timer(
            TimerPurpose::RecoveryOuter {
                session,
                attempt,
                request_id: request_id.clone(),
            },
            RECONNECT_REQUEST_TIMEOUT_MS,
            events,
        );
        if let Some(bridge) = self.sessions.iter_mut().find(|s| s.id == session) {
            bridge.recovery = Some(RecoveryState {
                attempt,
                workspace_key,
                phase: RecoveryPhase::ReconnectRequest {
                    request_id,
                    inner_timer,
                    outer_timer,
                },
            });
        }
    }

    /// 阶段 1 的响应到达（`_recoverBridgeOnce` 的 `if (res['success'] == true)`）。
    fn finish_recovery_reconnect(
        &mut self,
        session: BridgeSessionId,
        attempt: u32,
        request_id: String,
        response: Value,
        now_ms: i64,
        events: &mut Vec<ZemoteEvent>,
    ) {
        // 只有仍卡在阶段 1、且 requestId 对得上时才生效。
        //
        // PORT-NOTE: Dart 里外层 `.timeout(15s)` 抛错后，原 future 的续体成了死代码 —— 它即使
        // 后来被 complete 也不会做任何事。这里用「阶段是否已推进」表达同一件事。
        let Some(bridge) = self.sessions.iter().find(|s| s.id == session) else {
            return;
        };
        let Some(recovery) = bridge.recovery.clone() else {
            return;
        };
        let RecoveryPhase::ReconnectRequest {
            request_id: current,
            outer_timer,
            ..
        } = recovery.phase
        else {
            return;
        };
        if current != request_id {
            return;
        }
        self.cancel_timer(outer_timer, events);

        if response.get("success") == Some(&Value::Bool(true)) {
            events.push(ZemoteEvent::Log(format!(
                "[bridge] reconnected {}",
                recovery.workspace_key
            )));
            self.set_degraded(session, None, events);
            self.bump_recovered(session, events);
            self.finish_recovery(session);
            return;
        }
        // Dart: 成功标志不为 true 时**不打日志**，直接走完整重开。
        self.start_reopen(session, &recovery.workspace_key, attempt, now_ms, events);
    }

    /// 对应 Dart `_reopenBridge(session, workspaceKey)`。
    fn start_reopen(
        &mut self,
        session: BridgeSessionId,
        workspace_key: &str,
        attempt: u32,
        now_ms: i64,
        events: &mut Vec<ZemoteEvent>,
    ) {
        let Some(bridge) = self.sessions.iter().find(|s| s.id == session) else {
            self.recovering.remove(&session);
            return;
        };
        let old_bridge = bridge.bridge.clone();
        let bridge_session_id = self.req_id("bridge");
        self.bridge_generation += 1;
        let generation = self.bridge_generation;
        let request_id = self.req_id("workspace-bridge");
        events.push(ZemoteEvent::Log(format!(
            "[bridge] reopen {workspace_key} (gen {generation})"
        )));
        let matcher = PayloadMatcher::new(&[
            zcode_type::WORKSPACE_BRIDGE_READY,
            zcode_type::WORKSPACE_BRIDGE_ERROR,
        ])
        .with_field("bridgeSessionId", json!(bridge_session_id));
        let mut inner_events = Vec::new();
        let result = self.request_with_kind(
            reopen_bridge_request(
                &request_id,
                &bridge_session_id,
                generation,
                old_bridge.get("recoveryId").filter(|value| !value.is_null()),
                workspace_key,
            ),
            matcher,
            REQUEST_TIMEOUT_MS,
            now_ms,
            PendingKind::RecoveryReopen {
                session,
                attempt,
                requested_bridge_session_id: bridge_session_id,
            },
            &mut inner_events,
        );
        events.extend(inner_events);
        if result.is_err() {
            self.recovering.remove(&session);
            return;
        }
        if let Some(bridge) = self.sessions.iter_mut().find(|s| s.id == session) {
            bridge.recovery = Some(RecoveryState {
                attempt,
                workspace_key: workspace_key.to_string(),
                phase: RecoveryPhase::Reopen,
            });
        }
    }

    /// 阶段 2 的响应到达（`_reopenBridge` 之后 `_recoverBridgeOnce` 的收尾）。
    fn finish_recovery_reopen(
        &mut self,
        session: BridgeSessionId,
        attempt: u32,
        requested_bridge_session_id: String,
        response: Value,
        now_ms: i64,
        events: &mut Vec<ZemoteEvent>,
    ) {
        let Some(bridge) = self.sessions.iter().find(|s| s.id == session) else {
            self.recovering.remove(&session);
            return;
        };
        let Some(recovery) = bridge.recovery.clone() else {
            return;
        };
        if !matches!(recovery.phase, RecoveryPhase::Reopen) {
            return;
        }

        if response.get("zcode_type").and_then(Value::as_str)
            == Some(zcode_type::WORKSPACE_BRIDGE_ERROR)
        {
            // Dart: `throw StateError('workspace-bridge-error: ${res['error'] ?? res}')`
            let message = format!(
                "workspace-bridge-error: {}",
                dart_json_to_string(response.get("error").unwrap_or(&response))
            );
            // Dart: `_log('[bridge] reopen failed: $e'); degraded.value = 'reopen-failed: $e';`
            events.push(ZemoteEvent::Log(format!("[bridge] reopen failed: {message}")));
            self.set_degraded(session, Some(format!("reopen-failed: {message}")), events);
            self.schedule_recovery_retry(session, attempt, events);
            return;
        }

        let new_bridge = match response.get("bridge") {
            Some(Value::Object(map)) => map.clone(),
            _ => Payload::new(),
        };
        self.attach_stack(
            session,
            &requested_bridge_session_id,
            new_bridge.clone(),
            now_ms,
            events,
        );
        events.push(ZemoteEvent::Log(format!(
            "[bridge] reopened: {}",
            dart_json_to_string(&Value::Object(new_bridge))
        )));
        self.set_degraded(session, None, events);
        self.bump_recovered(session, events);
        self.finish_recovery(session);
    }

    /// 对应 Dart `_recoverBridgeWithRetry` 循环尾：记日志 → `Future.delayed(3s)` → 再试。
    fn schedule_recovery_retry(
        &mut self,
        session: BridgeSessionId,
        attempt: u32,
        events: &mut Vec<ZemoteEvent>,
    ) {
        // Dart: `if (session._disposed) return;`（`finally` 仍 remove）
        let disposed = self
            .sessions
            .iter()
            .find(|s| s.id == session)
            .map(|s| s.disposed)
            .unwrap_or(true);
        if disposed {
            self.finish_recovery(session);
            return;
        }
        events.push(ZemoteEvent::Log(format!(
            "[bridge] recovery attempt {attempt} failed, retrying"
        )));
        let _ = self.arm_timer(
            TimerPurpose::RecoveryRetry { session, attempt },
            RECOVERY_RETRY_DELAY_MS,
            events,
        );
        if let Some(bridge) = self.sessions.iter_mut().find(|s| s.id == session) {
            let workspace_key = bridge
                .recovery
                .as_ref()
                .map(|r| r.workspace_key.clone())
                .unwrap_or_default();
            bridge.recovery = Some(RecoveryState {
                attempt,
                workspace_key,
                phase: RecoveryPhase::RetryDelay,
            });
        }
    }

    /// 对应 `finally { _recoveringBridges.remove(session); }`。
    fn finish_recovery(&mut self, session: BridgeSessionId) {
        self.recovering.remove(&session);
        if let Some(bridge) = self.sessions.iter_mut().find(|s| s.id == session) {
            bridge.recovery = None;
        }
    }

    /// 对应 Dart `_recoverBridgeWithRetry` 里 3 秒延迟到点。
    fn on_recovery_retry(
        &mut self,
        session: BridgeSessionId,
        attempt: u32,
        now_ms: i64,
        events: &mut Vec<ZemoteEvent>,
    ) {
        // 只有仍处于 `RetryDelay` 阶段、且尝试序号对得上才继续
        // （会话已 dispose、或已开始新一轮时忽略）。
        let still_waiting = self
            .sessions
            .iter()
            .find(|s| s.id == session)
            .and_then(|s| s.recovery.as_ref())
            .is_some_and(|r| {
                r.attempt == attempt && matches!(r.phase, RecoveryPhase::RetryDelay)
            });
        if !still_waiting {
            return;
        }
        let disposed = self
            .sessions
            .iter()
            .find(|s| s.id == session)
            .map(|s| s.disposed)
            .unwrap_or(true);
        if disposed {
            self.finish_recovery(session);
            return;
        }
        // Dart 的 `for (var attempt = 1; attempt <= 15; attempt++)`：第 15 次失败后循环终止。
        if attempt >= RECOVERY_MAX_ATTEMPTS {
            self.finish_recovery(session);
            return;
        }
        self.recover_once(session, attempt + 1, now_ms, events);
    }

    /// 对应 Dart `_recoverBridgeOnce` 里阶段 1 的 `.timeout(15s)` 到点。
    fn on_recovery_outer_timeout(
        &mut self,
        session: BridgeSessionId,
        attempt: u32,
        request_id: &str,
        now_ms: i64,
        events: &mut Vec<ZemoteEvent>,
    ) {
        let Some(bridge) = self.sessions.iter().find(|s| s.id == session) else {
            return;
        };
        let Some(recovery) = bridge.recovery.clone() else {
            return;
        };
        let RecoveryPhase::ReconnectRequest {
            request_id: current,
            inner_timer,
            ..
        } = recovery.phase
        else {
            return;
        };
        if current != request_id {
            return;
        }
        // Dart: `_log('[bridge] reconnect-request failed: $e')`（`$e` 是 TimeoutException）。
        events.push(ZemoteEvent::Log(
            "[bridge] reconnect-request failed: TimeoutException after 0:00:15.000000: Future not completed"
                .to_string(),
        ));
        // PORT-NOTE: Dart 会把内层 `request()` 的 completer 留成孤儿 —— 它自己的 30 秒超时最后
        // 抛成 Unhandled exception。这里在外层 15 秒超时时就撤销内层挂起项，免得宿主收到一个
        // 无人等待的令牌。线上行为等价：外层一超时，迟到的 `workspace-reconnect-response` 本
        // 就不会被任何续体处理。
        self.pending.remove(request_id);
        self.cancel_timer(inner_timer, events);
        self.start_reopen(session, &recovery.workspace_key, attempt, now_ms, events);
    }

    // -----------------------------------------------------------------------
    // 5.6 `_attachStack` / `openBridge` / `_swap`
    // -----------------------------------------------------------------------

    /// 对应 Dart `_attachStack(session, requestedBridgeSessionId, bridge)`。
    fn attach_stack(
        &mut self,
        session: BridgeSessionId,
        requested_bridge_session_id: &str,
        bridge: Payload,
        now_ms: i64,
        events: &mut Vec<ZemoteEvent>,
    ) {
        let Some(index) = self.sessions.iter().position(|s| s.id == session) else {
            return;
        };
        // Dart: `session._transport.dispose();`
        self.sessions[index].transport.dispose();
        events.push(ZemoteEvent::BridgeStackDisposed { session });
        // Dart 里 `_transport.dispose()` 顺带 cancel 了它自己那个 30 秒周期定时器；这里定时器
        // 由核心持有，故显式取消 —— 换栈后新栈会 arm 自己的清理定时器，不能留下两条。
        self.cancel_fragment_purge(session, events);

        // Dart: `(bridge['bridgeSessionId'] as String?) ?? requestedBridgeSessionId`
        let wire_id = bridge
            .get("bridgeSessionId")
            .and_then(Value::as_str)
            .map(str::to_string)
            .unwrap_or_else(|| requested_bridge_session_id.to_string());
        let generation = bridge.get("bridgeGeneration").and_then(json_as_dart_int);
        let recovery_id = bridge
            .get("recoveryId")
            .and_then(Value::as_str)
            .map(str::to_string);

        let outbox = Arc::new(Mutex::new(Vec::new()));
        let transport = RpcFrameTransport::new(
            wire_id.clone(),
            Box::new(OutboxSink::new(Arc::clone(&outbox))),
            generation,
            recovery_id,
        );
        // Dart: `ChannelClient(sendBody: transport.sendMessage, onLog: onLog)`
        // —— 出站由 `pump` 从 `channels.drain_outgoing()` 取走再喂 transport。
        let channels = ChannelClient::new();
        // Dart: `session._swap(bridge, transport, channels);`
        self.sessions[index].swap(bridge, transport, channels, outbox);
        events.push(ZemoteEvent::BridgeStackAttached { session });

        // Dart 原文：
        //   final id = transport.bridgeSessionId;
        //   final oldId = session._bridge['bridgeSessionId'];
        //   if (oldId != id) _frameRouters.remove(oldId);
        //
        // PORT-NOTE: 这里有个**疑似 bug**，照抄不改 —— `_swap` 在上一行刚把 `session._bridge`
        // 换成新 bridge，所以 `oldId` 读到的是**新** id，`oldId != id` 通常为假，旧 router 永远
        // 不被摘掉（重开后 `_frameRouters` 里会残留一条指向已 dispose 传输的项）。线上无影响：
        // 旧线上 id 不会再出现，残留项不会被命中；只是轻微泄漏。
        let id = wire_id;
        let old_id = self.sessions[index].bridge.get("bridgeSessionId").cloned();
        if old_id != Some(Value::String(id.clone())) {
            if let Some(Value::String(old)) = old_id {
                self.frame_routers.remove(&old);
            }
        }
        self.frame_routers.insert(id.clone(), session);

        // Dart: 冲刷在 transport 存在之前到达的帧（Initialize 竞态）。
        if let Some(pending) = self.pending_bridge_payloads.remove(&id) {
            for payload in pending {
                self.route_to_bridge(session, &payload, now_ms, events);
            }
        }

        // Dart: `RpcFrameTransport` 构造函数里的 `Timer.periodic(30s)` → `_purgeStale()`。
        // 核心自己拥有这个周期：先 arm 一个 30 秒定时器，到点清理并重排（见 `on_fragment_purge`）。
        self.arm_fragment_purge(session, events);
    }

    /// 对应 Dart `openBridge` 里 `await request(...)` 之后那段。
    #[allow(clippy::too_many_arguments)]
    fn finish_open_bridge(
        &mut self,
        token: String,
        response: Value,
        requested_bridge_session_id: String,
        workspace_key: String,
        task_id: Option<String>,
        now_ms: i64,
        events: &mut Vec<ZemoteEvent>,
    ) {
        if response.get("zcode_type").and_then(Value::as_str)
            == Some(zcode_type::WORKSPACE_BRIDGE_ERROR)
        {
            // Dart: `throw StateError('workspace-bridge-error: ${res['error'] ?? res}')`
            let message = format!(
                "workspace-bridge-error: {}",
                dart_json_to_string(response.get("error").unwrap_or(&response))
            );
            events.push(ZemoteEvent::Completed {
                token,
                outcome: Completion::Failed(ZemoteError::InvalidState(message)),
            });
            return;
        }

        // Dart: `(res['bridge'] as Map?)?.cast<String, dynamic>() ?? <String, dynamic>{}`
        let bridge = match response.get("bridge") {
            Some(Value::Object(map)) => map.clone(),
            _ => Payload::new(),
        };
        events.push(ZemoteEvent::Log(format!(
            "[bridge] ready: {}",
            dart_json_to_string(&Value::Object(bridge.clone()))
        )));

        self.next_session_id += 1;
        let session = BridgeSessionId(self.next_session_id);
        self.sessions
            .push(BridgeSession::placeholder(session, bridge.clone()));
        self.attach_stack(
            session,
            &requested_bridge_session_id,
            bridge.clone(),
            now_ms,
            events,
        );
        // Dart: `_activeBridges.add(session);` 在 `_attachStack` 之后 —— 对象已在上面 push，
        // 这里不重复。

        // Dart: `await sendMobileViewState(workspaceKey: bridge['workspaceKey'] ?? workspaceKey,
        //                                   taskId: bridge['initialTaskId'] ?? taskId);`
        let effective_key = bridge
            .get("workspaceKey")
            .and_then(Value::as_str)
            .map(str::to_string)
            .unwrap_or(workspace_key);
        let effective_task = bridge
            .get("initialTaskId")
            .and_then(Value::as_str)
            .map(str::to_string)
            .or(task_id);
        match mobile_view_state_update_payload(
            &effective_key,
            effective_task.as_deref(),
            now_ms,
            &self.device_info,
        ) {
            Ok(payload) => {
                events.extend(self.drive_relay(RelayInput::SendPayload(payload), now_ms));
            }
            Err(error) => events.push(ZemoteEvent::Log(format!(
                "[bridge] mobile-view-state 构造失败: {error}"
            ))),
        }

        events.push(ZemoteEvent::Completed {
            token,
            outcome: Completion::BridgeOpened(session),
        });
    }

    // -----------------------------------------------------------------------
    // 5.7 桥的 dispose 与状态量
    // -----------------------------------------------------------------------

    /// 对应 Dart `BridgeSession.dispose()`（含它的 `onDispose` 回调）。
    fn dispose_session(&mut self, session: BridgeSessionId, events: &mut Vec<ZemoteEvent>) {
        let Some(index) = self.sessions.iter().position(|s| s.id == session) else {
            return;
        };
        if self.sessions[index].disposed {
            return;
        }
        self.sessions[index].disposed = true;
        // Dart: `degraded.dispose(); recovered.dispose();`（清监听器；Rust 侧只保留值）。
        // Dart: `_transport.dispose();`
        self.sessions[index].transport.dispose();
        events.push(ZemoteEvent::BridgeStackDisposed { session });
        // Dart 的 `_transport.dispose()` 会 cancel 那个 30 秒周期定时器；核心持有时显式取消。
        self.cancel_fragment_purge(session, events);
        // Dart 的 `onDispose`：
        //   `_activeBridges.remove(s); _frameRouters.remove(s.bridge['bridgeSessionId']);`
        let wire_id = self.sessions[index]
            .bridge
            .get("bridgeSessionId")
            .and_then(Value::as_str)
            .map(str::to_string);
        self.sessions.remove(index);
        if let Some(wire_id) = wire_id {
            self.frame_routers.remove(&wire_id);
        }
        self.recovering.remove(&session);
        // Dart 不取消挂起的 `waitHealthy`（`degraded` 一 dispose，listener 就永远不会被调）。
        // 这里同样保留，等它的定时器到点走超时。
    }

    /// 设置 `degraded`，按 Dart `ValueNotifier` 的语义（**同值不通知**），并在变回 `None` 时
    /// 唤醒所有 `waitHealthy`。
    fn set_degraded(
        &mut self,
        session: BridgeSessionId,
        value: Option<String>,
        events: &mut Vec<ZemoteEvent>,
    ) {
        let mut waiters = Vec::new();
        {
            let Some(bridge) = self.sessions.iter_mut().find(|s| s.id == session) else {
                return;
            };
            if bridge.degraded == value {
                return;
            }
            bridge.degraded = value.clone();
            if value.is_none() {
                waiters = std::mem::take(&mut bridge.healthy_waiters);
            }
        }
        events.push(ZemoteEvent::BridgeDegraded {
            session,
            degraded: value,
        });
        // Dart `waitHealthy` 的 `check()`：`degraded.value == null` 时完成。
        for waiter in waiters {
            self.cancel_timer(waiter.timer, events);
            events.push(ZemoteEvent::Completed {
                token: waiter.token,
                outcome: Completion::Ok,
            });
        }
    }

    /// 对应 Dart `session.recovered.value += 1`。
    fn bump_recovered(&mut self, session: BridgeSessionId, events: &mut Vec<ZemoteEvent>) {
        let Some(bridge) = self.sessions.iter_mut().find(|s| s.id == session) else {
            return;
        };
        bridge.recovered += 1;
        let count = bridge.recovered;
        events.push(ZemoteEvent::BridgeRecovered { session, count });
    }

    // -----------------------------------------------------------------------
    // 5.8 定时器与令牌
    // -----------------------------------------------------------------------

    fn arm_timer(
        &mut self,
        purpose: TimerPurpose,
        delay_ms: i64,
        events: &mut Vec<ZemoteEvent>,
    ) -> TimerId {
        self.next_timer_id += 1;
        let token = TimerId(self.next_timer_id);
        self.timers.insert(token, purpose);
        events.push(ZemoteEvent::TimerStart { token, delay_ms });
        token
    }

    fn cancel_timer(&mut self, token: TimerId, events: &mut Vec<ZemoteEvent>) {
        if self.timers.remove(&token).is_some() {
            events.push(ZemoteEvent::TimerCancel(token));
        }
    }

    /// 为该桥 arm 一个 30 秒的分片清理定时器，并记到桥上以便取消。
    ///
    /// 对应 Dart `RpcFrameTransport` 构造函数里的
    /// `Timer.periodic(const Duration(seconds: 30), ...)` → `_purgeStale()`。间隔取自
    /// [`crate::rpc_transport::PURGE_INTERVAL_SECONDS`]，不写字面量。
    fn arm_fragment_purge(&mut self, session: BridgeSessionId, events: &mut Vec<ZemoteEvent>) {
        let delay_ms = crate::rpc_transport::PURGE_INTERVAL_SECONDS as i64 * 1000;
        let token = self.arm_timer(TimerPurpose::FragmentPurge { session }, delay_ms, events);
        if let Some(bridge) = self.sessions.iter_mut().find(|s| s.id == session) {
            bridge.fragment_purge_timer = Some(token);
        }
    }

    /// 取消该桥的分片清理定时器（幂等；没 arm 过就什么都不做）。
    fn cancel_fragment_purge(&mut self, session: BridgeSessionId, events: &mut Vec<ZemoteEvent>) {
        let token = self
            .sessions
            .iter_mut()
            .find(|s| s.id == session)
            .and_then(|s| s.fragment_purge_timer.take());
        if let Some(token) = token {
            self.cancel_timer(token, events);
        }
    }

    /// 对应 Dart `_reqId(prefix)` = `generateRequestId(prefix)`。
    fn req_id(&mut self, prefix: &str) -> String {
        generate_request_id_with(prefix, self.rng.as_mut())
    }

    fn next_local_token(&mut self, kind: &str) -> String {
        self.next_local_token += 1;
        format!("{kind}:{}", self.next_local_token)
    }

    /// 定时器到点。
    fn on_timer(&mut self, token: TimerId, now_ms: i64, events: &mut Vec<ZemoteEvent>) {
        let Some(purpose) = self.timers.remove(&token) else {
            return;
        };
        match purpose {
            // Dart: `request()` 的 `completer.future.timeout(timeout, onTimeout: ...)`
            TimerPurpose::Request { request_id } => {
                if self.pending.remove(&request_id).is_some() {
                    events.push(ZemoteEvent::Completed {
                        token: request_id.clone(),
                        outcome: Completion::Failed(ZemoteError::Timeout(format!(
                            "request {request_id} timed out"
                        ))),
                    });
                }
            }
            // Dart: `waitPaired` 里的 `Timer(timeout, ...)`
            TimerPurpose::WaitPaired { token: waiter } => {
                if let Some(position) = self.pair_waiters.iter().position(|w| w.token == waiter) {
                    self.pair_waiters.remove(position);
                    events.push(ZemoteEvent::Completed {
                        token: waiter,
                        outcome: Completion::Failed(ZemoteError::Timeout(
                            "pairing timeout".to_string(),
                        )),
                    });
                }
            }
            // Dart: `BridgeSession.waitHealthy` 的 `future.timeout(...)`
            TimerPurpose::BridgeHealthy {
                session,
                token: waiter,
            } => {
                let mut fired = false;
                if let Some(bridge) = self.sessions.iter_mut().find(|s| s.id == session) {
                    if let Some(position) =
                        bridge.healthy_waiters.iter().position(|w| w.token == waiter)
                    {
                        bridge.healthy_waiters.remove(position);
                        fired = true;
                    }
                }
                if fired {
                    // Dart: `'bridge 恢复超时: ${degraded.value}'`（null 插值成 "null"）
                    let degraded = self
                        .sessions
                        .iter()
                        .find(|s| s.id == session)
                        .and_then(|s| s.degraded.clone())
                        .unwrap_or_else(|| "null".to_string());
                    events.push(ZemoteEvent::Completed {
                        token: waiter,
                        outcome: Completion::Failed(ZemoteError::Timeout(format!(
                            "bridge 恢复超时: {degraded}"
                        ))),
                    });
                }
            }
            TimerPurpose::RecoveryOuter {
                session,
                attempt,
                request_id,
            } => self.on_recovery_outer_timeout(session, attempt, &request_id, now_ms, events),
            TimerPurpose::RecoveryRetry { session, attempt } => {
                self.on_recovery_retry(session, attempt, now_ms, events)
            }
            TimerPurpose::FragmentPurge { session } => {
                self.on_fragment_purge(session, now_ms, events)
            }
        }
    }

    /// 分片清理定时器到点：清掉该桥超时未凑齐的重组状态，并**重排**下一个周期。
    ///
    /// 对应 Dart `Timer.periodic(const Duration(seconds: 30), () => _purgeStale())`。
    /// `purge_stale` 返回的行**已经是格式化好的整行**（`[rpc] purged stale assembly <seq>`），
    /// 直接作为 [`ZemoteEvent::Log`] 发出，不再二次包装。
    fn on_fragment_purge(
        &mut self,
        session: BridgeSessionId,
        now_ms: i64,
        events: &mut Vec<ZemoteEvent>,
    ) {
        let Some(bridge) = self.sessions.iter_mut().find(|s| s.id == session) else {
            // 桥已 dispose（正常路径下它的定时器已随 dispose 取消，这里是防御）：不重排。
            return;
        };
        for line in bridge.transport.purge_stale(now_ms) {
            events.push(ZemoteEvent::Log(line));
        }
        // Dart 是 `Timer.periodic`：周期语义必须保持 —— 重新 arm 下一个 30 秒。
        self.arm_fragment_purge(session, events);
    }

    /// 对应 Dart `waitPaired` 里那个 `relay.stateListenable` 监听器。
    fn resolve_pair_waiters(&mut self, state: RelayState, events: &mut Vec<ZemoteEvent>) {
        if state != RelayState::Paired {
            return;
        }
        let waiters = std::mem::take(&mut self.pair_waiters);
        for waiter in waiters {
            self.cancel_timer(waiter.timer, events);
            events.push(ZemoteEvent::Completed {
                token: waiter.token,
                outcome: Completion::Ok,
            });
        }
    }
}

// ===========================================================================
// 6. 载荷构造（Dart 里内联的 map 字面量，抽出来便于逐字段断言）
// ===========================================================================

/// 对应 Dart `bootstrap()` 里的 `{'zcode_type': 'bootstrap-request', 'requestId': id}`。
pub fn bootstrap_request(request_id: &str) -> Payload {
    let mut payload = Payload::new();
    payload.insert("zcode_type".into(), zcode_type::BOOTSTRAP_REQUEST.into());
    payload.insert("requestId".into(), json!(request_id));
    payload
}

/// 对应 Dart `listWorkspaces()` 里的 `workspace-list-request`。
pub fn list_workspaces_request(request_id: &str) -> Payload {
    let mut payload = Payload::new();
    payload.insert(
        "zcode_type".into(),
        zcode_type::WORKSPACE_LIST_REQUEST.into(),
    );
    payload.insert("requestId".into(), json!(request_id));
    payload
}

/// 对应 Dart `openBridge()` 里那个 `workspace-bridge-open`（含 `if (taskId != null)` 的展开）。
///
/// 字段顺序照抄 Dart 的字面量顺序：`zcode_type`, `requestId`, `bridgeSessionId`,
/// `bridgeGeneration`, `workspaceKey`, `taskId?`。
pub fn open_bridge_request(
    request_id: &str,
    bridge_session_id: &str,
    bridge_generation: i64,
    workspace_key: &str,
    task_id: Option<&str>,
) -> Payload {
    let mut payload = Payload::new();
    payload.insert("zcode_type".into(), zcode_type::WORKSPACE_BRIDGE_OPEN.into());
    payload.insert("requestId".into(), json!(request_id));
    payload.insert("bridgeSessionId".into(), json!(bridge_session_id));
    payload.insert("bridgeGeneration".into(), json!(bridge_generation));
    payload.insert("workspaceKey".into(), json!(workspace_key));
    if let Some(task_id) = task_id {
        payload.insert("taskId".into(), json!(task_id));
    }
    payload
}

/// 对应 Dart `_reopenBridge` 里那个 `workspace-bridge-open`（带 `recoveryId`，**无** `taskId`）。
///
/// 注意 Dart 的键序与 [`open_bridge_request`] 不同：`recoveryId` 插在 `workspaceKey` **之前**。
pub fn reopen_bridge_request(
    request_id: &str,
    bridge_session_id: &str,
    bridge_generation: i64,
    recovery_id: Option<&Value>,
    workspace_key: &str,
) -> Payload {
    let mut payload = Payload::new();
    payload.insert("zcode_type".into(), zcode_type::WORKSPACE_BRIDGE_OPEN.into());
    payload.insert("requestId".into(), json!(request_id));
    payload.insert("bridgeSessionId".into(), json!(bridge_session_id));
    payload.insert("bridgeGeneration".into(), json!(bridge_generation));
    if let Some(recovery_id) = recovery_id {
        payload.insert("recoveryId".into(), recovery_id.clone());
    }
    payload.insert("workspaceKey".into(), json!(workspace_key));
    payload
}

/// 对应 Dart `reconnectWorkspace()` 里的 `workspace-reconnect-request`。
pub fn reconnect_workspace_request(request_id: &str, workspace_key: &str) -> Payload {
    let mut payload = Payload::new();
    payload.insert(
        "zcode_type".into(),
        zcode_type::WORKSPACE_RECONNECT_REQUEST.into(),
    );
    payload.insert("requestId".into(), json!(request_id));
    payload.insert("workspaceKey".into(), json!(workspace_key));
    payload
}

/// 对应 Dart `sendMobileViewState` 里的 `mobile-view-state-update`。
///
/// `now_ms` 即 Dart 的 `DateTime.now().millisecondsSinceEpoch`。
pub fn mobile_view_state_update_payload(
    workspace_key: &str,
    task_id: Option<&str>,
    now_ms: i64,
    device_info: &DeviceInfo,
) -> ZemoteResult<Payload> {
    let mut view_state = Payload::new();
    view_state.insert("activeWorkspaceKey".into(), json!(workspace_key));
    if let Some(task_id) = task_id {
        view_state.insert("activeTaskId".into(), json!(task_id));
    }
    view_state.insert("updatedAt".into(), json!(now_ms));

    let mut payload = Payload::new();
    payload.insert(
        "zcode_type".into(),
        zcode_type::MOBILE_VIEW_STATE_UPDATE.into(),
    );
    payload.insert("viewState".into(), Value::Object(view_state));
    // 形状复用 `device_info::DeviceInfo`（`{platform, version, name}`），不重复定义。
    //
    // `updatedAt` 必须补：对端 schema `MD`（`out/main/chunk-OW6EUS2G.js` @724,141）
    // 的 12 个键里只有 `updatedAt` 没有 `.optional()`；缺它 → `safeParse` 失败 →
    // 整帧被 `handleDataPayload` 静默丢弃（只打一条 warn）→ `h.mobileViewState`
    // 永不更新。它是**每帧时间戳**，不属于设备身份，所以只加在序列化结果上，
    // 不改 `DeviceInfo`——那个结构体还被 `relay_client.rs` 的 `auth_init.meta`
    // 复用，两处形状要求不同。
    let mut device_info_value = serde_json::to_value(device_info)
        .map_err(|error| ZemoteError::Protocol(format!("deviceInfo 序列化失败: {error}")))?;
    match &mut device_info_value {
        Value::Object(map) => {
            map.insert("updatedAt".into(), json!(now_ms));
        }
        _ => {
            return Err(ZemoteError::Protocol(
                "deviceInfo 序列化结果必须是对象".to_string(),
            ))
        }
    }
    payload.insert("deviceInfo".into(), device_info_value);
    Ok(payload)
}

/// 对应 Dart `BridgeSession.conversation` 里的
/// `'${scope['workspaceIdentity'] ?? scope['workspacePath']}'`。
///
/// Dart 的 `??` 对「键缺失」与「值为 null」都成立，且最终是字符串插值（`null` → `"null"`）。
pub fn conversation_key(scope: &Payload) -> String {
    match scope.get("workspaceIdentity") {
        Some(value) if !value.is_null() => dart_json_to_string(value),
        _ => dart_json_to_string(scope.get("workspacePath").unwrap_or(&Value::Null)),
    }
}

/// Dart 字符串插值里 `Option<&str>` 的写法：`None` → `"null"`。
fn dart_opt_str(value: Option<&str>) -> String {
    match value {
        Some(value) => value.to_string(),
        None => "null".to_string(),
    }
}

// ===========================================================================
// 7. 测试
// ===========================================================================
//
// 期望值来源说明：`Reference_code/test/` 下**没有**门面级的单元测试 ——
// `automation_roundtrip_test.dart` / `real_feature_probe_test.dart` / `session_list_probe_test.dart`
// 三条都是被 `ZEMOTE_RUN_REAL_TESTS` 门禁挡住的**联网集成探测**（本机无 Flutter、无网络，
// 不能作为断言来源）。因此：
//
// * 载荷形状的期望值**逐字段照抄** `zemote_client.dart` 的 map 字面量与字符串字面量
//   （含超时毫秒数、日志模板、错误文案），未做任何推测；
// * `bootstrap` 的字段名（`workspaces` / `workspacePath` / `workspaceIdentity`）由
//   `session_list_probe_test.dart:24-35` 与 `automation_roundtrip_test.dart:29-36` 的取数确认；
// * 端到端断言（帧真的发出去了、桥真的被路由）用宿主 mock transport 抓中继信封里的 `payload`。
#[cfg(test)]
mod tests {
    use super::*;
    use crate::relay_client::RelayTimer;
    use base64::Engine as _;

    const NOW: i64 = 1_700_000_000_000;

    // ------------------------------------------------------------------ 测试脚手架

    /// 记录 `send_text` 的宿主传输（对应 Dart 的 `socket.sink.add`）。
    struct RecordingTransport {
        sent: Arc<Mutex<Vec<String>>>,
        connects: Arc<Mutex<Vec<u64>>>,
    }

    impl RelayTransport for RecordingTransport {
        fn connect(&mut self, generation: u64, _uri: &str) {
            self.connects
                .lock()
                .unwrap_or_else(PoisonError::into_inner)
                .push(generation);
        }

        fn send_text(&mut self, _generation: u64, text: &str) {
            self.sent
                .lock()
                .unwrap_or_else(PoisonError::into_inner)
                .push(text.to_string());
        }

        fn close(&mut self, _generation: u64, _code: Option<i32>, _reason: Option<&str>) {}
    }

    /// 固定字节熵源，让 requestId 可复现。
    struct FixedRandom {
        bytes: Vec<u8>,
        pos: usize,
    }

    impl FixedRandom {
        fn new(byte: u8) -> Self {
            Self {
                bytes: vec![byte; 16],
                pos: 0,
            }
        }
    }

    impl SecureRandom for FixedRandom {
        fn next_byte(&mut self) -> u8 {
            let byte = self.bytes[self.pos % self.bytes.len()];
            self.pos += 1;
            byte
        }
    }

    const PARAMS_URL: &str =
        "https://relay.example.com/pair?sid=sid-1&hash=hash-1&t=1700000000000&app_version=0.5.3";

    struct Harness {
        client: ZemoteClient,
        sent: Arc<Mutex<Vec<String>>>,
        connects: Arc<Mutex<Vec<u64>>>,
        generation: u64,
    }

    impl Harness {
        fn new() -> Self {
            let sent = Arc::new(Mutex::new(Vec::new()));
            let connects = Arc::new(Mutex::new(Vec::new()));
            let transport = RecordingTransport {
                sent: Arc::clone(&sent),
                connects: Arc::clone(&connects),
            };
            let params = ZemoteConnectionParams::parse(PARAMS_URL).expect("测试 URL 应能解析");
            let client = ZemoteClient::new_with_random(
                params,
                ZemoteClientConfig {
                    is_web: false,
                    target_platform: TargetPlatform::Android,
                },
                Box::new(transport),
                NOW,
                Box::new(FixedRandom::new(0xAB)),
            );
            Self {
                client,
                sent,
                connects,
                generation: 1,
            }
        }

        /// 最近一次 `RelayTransport::connect` 拿到的代次。
        fn last_connect_generation(&self) -> u64 {
            *self
                .connects
                .lock()
                .unwrap_or_else(PoisonError::into_inner)
                .last()
                .expect("应发生过 connect")
        }

        fn step(&mut self, input: ZemoteInput<'_>) -> Vec<ZemoteEvent> {
            self.client.step(input, NOW)
        }

        fn text_frame(&mut self, text: String) -> Vec<ZemoteEvent> {
            let generation = self.generation;
            self.step(ZemoteInput::Relay(RelayInput::TextFrame {
                generation,
                text: &text,
            }))
        }

        /// 喂一个 `{"type":"data","payload":{..}}` 中继信封。
        fn data_frame(&mut self, payload: Value) -> Vec<ZemoteEvent> {
            let envelope = json!({"type": "data", "payload": payload});
            self.text_frame(envelope.to_string())
        }

        /// 把 relay 推到 `paired`（网页端握手：auth_init → auth_challenge → auth_response
        /// → auth_ack / pair_status_ack(matched)），并清掉握手期间的出站帧。
        fn drive_to_paired(&mut self) {
            self.step(ZemoteInput::Relay(RelayInput::Start));
            let generation = self.generation;
            self.step(ZemoteInput::Relay(RelayInput::SocketReady { generation }));
            self.text_frame(json!({"type": "auth_challenge", "nonce": "nonce-1"}).to_string());
            self.text_frame(
                json!({"type": "pair_status_ack", "pair_status": "matched"}).to_string(),
            );
            assert_eq!(self.client.relay().state(), RelayState::Paired);
            self.sent
                .lock()
                .unwrap_or_else(PoisonError::into_inner)
                .clear();
        }

        fn sent_texts(&self) -> Vec<String> {
            self.sent
                .lock()
                .unwrap_or_else(PoisonError::into_inner)
                .clone()
        }

        /// 最后一条已发出的中继信封里的 `payload`。
        fn last_payload(&self) -> Payload {
            let texts = self.sent_texts();
            let text = texts.last().expect("应有已发出的帧");
            let envelope: Value = serde_json::from_str(text).expect("中继信封应是 JSON");
            envelope
                .get("payload")
                .and_then(Value::as_object)
                .expect("信封里应有 payload 对象")
                .clone()
        }

        /// 打开一个桥并返回它的本地句柄。
        fn open_bridge(&mut self, wire_id: &str) -> BridgeSessionId {
            let operation = self
                .client
                .open_bridge("wk-1", None, OPEN_BRIDGE_TIMEOUT_MS, NOW)
                .expect("open_bridge 应能发起");
            let requested = self.last_payload()["bridgeSessionId"]
                .as_str()
                .unwrap()
                .to_string();
            let events = self.data_frame(json!({
                "zcode_type": "workspace-bridge-ready",
                "requestId": operation.token,
                "bridgeSessionId": requested,
                "bridge": {"bridgeSessionId": wire_id, "workspaceKey": "wk-1"},
            }));
            session_of(&events)
        }
    }

    fn session_of(events: &[ZemoteEvent]) -> BridgeSessionId {
        events
            .iter()
            .find_map(|event| match event {
                ZemoteEvent::Completed {
                    outcome: Completion::BridgeOpened(session),
                    ..
                } => Some(*session),
                _ => None,
            })
            .expect("应有一个 BridgeOpened 完成事件")
    }

    fn completed(events: &[ZemoteEvent]) -> &Completion {
        events
            .iter()
            .find_map(|event| match event {
                ZemoteEvent::Completed { outcome, .. } => Some(outcome),
                _ => None,
            })
            .expect("应有完成事件")
    }

    fn timer_of(events: &[ZemoteEvent]) -> TimerId {
        events
            .iter()
            .find_map(|event| match event {
                ZemoteEvent::TimerStart { token, .. } => Some(*token),
                _ => None,
            })
            .expect("应有定时器")
    }

    /// 开一个桥，返回它的本地句柄 + 触发 attach 的那批事件（便于断言清理定时器）。
    fn open_bridge_capturing(
        harness: &mut Harness,
        wire_id: &str,
    ) -> (BridgeSessionId, Vec<ZemoteEvent>) {
        let operation = harness
            .client
            .open_bridge("wk-1", None, OPEN_BRIDGE_TIMEOUT_MS, NOW)
            .expect("open_bridge 应能发起");
        let requested = harness.last_payload()["bridgeSessionId"]
            .as_str()
            .unwrap()
            .to_string();
        let events = harness.data_frame(json!({
            "zcode_type": "workspace-bridge-ready",
            "requestId": operation.token,
            "bridgeSessionId": requested,
            "bridge": {"bridgeSessionId": wire_id, "workspaceKey": "wk-1"},
        }));
        (session_of(&events), events)
    }

    /// 从事件里取出 30 秒分片清理定时器的句柄。
    fn purge_timer_of(events: &[ZemoteEvent]) -> TimerId {
        events
            .iter()
            .find_map(|event| match event {
                ZemoteEvent::TimerStart { token, delay_ms } if *delay_ms == 30_000 => Some(*token),
                _ => None,
            })
            .expect("应有 30 秒分片清理定时器")
    }

    /// 当前活跃的分片清理定时器（`ZemoteClient::timers` 里的 `FragmentPurge` 项）。
    fn purge_timers(client: &ZemoteClient) -> Vec<TimerId> {
        client
            .timers
            .iter()
            .filter_map(|(token, purpose)| {
                matches!(purpose, TimerPurpose::FragmentPurge { .. }).then_some(*token)
            })
            .collect()
    }

    /// 造一个完整的单片 `rpc-frame`（crc32 与 transport 同源）。
    fn rpc_frame(wire_bridge_session_id: &str, body: &[u8]) -> Value {
        json!({
            "zcode_type": "rpc-frame",
            "bridgeSessionId": wire_bridge_session_id,
            "seq": 1,
            "messageSeq": 1,
            "fragmentIndex": 0,
            "fragmentCount": 1,
            "messageBytes": body.len(),
            "checksum": {"algorithm": "crc32", "value": crate::crc32::Crc32::hex_of(body)},
            "dataBase64": base64::engine::general_purpose::STANDARD.encode(body),
        })
    }

    /// 取出发给中继的所有 `payload`。
    fn sent_payloads(harness: &Harness) -> Vec<Payload> {
        harness
            .sent_texts()
            .into_iter()
            .filter_map(|text| serde_json::from_str::<Value>(&text).ok())
            .filter_map(|envelope| envelope.get("payload")?.as_object().cloned())
            .collect()
    }

    // ------------------------------------------------------------------ 载荷形状

    /// 期望值照抄 `zemote_client.dart:217-225`。
    #[test]
    fn bootstrap_request_payload_shape() {
        let request = bootstrap_request("bootstrap-abc");
        assert_eq!(request.len(), 2);
        assert_eq!(
            request.get("zcode_type").and_then(Value::as_str),
            Some("bootstrap-request")
        );
        assert_eq!(request.get("requestId"), Some(&json!("bootstrap-abc")));
    }

    /// 期望值照抄 `zemote_client.dart:228-237`。
    #[test]
    fn list_workspaces_request_payload_shape() {
        let request = list_workspaces_request("workspace-list-abc");
        assert_eq!(request.len(), 2);
        assert_eq!(
            request.get("zcode_type").and_then(Value::as_str),
            Some("workspace-list-request")
        );
        assert_eq!(request.get("requestId"), Some(&json!("workspace-list-abc")));
    }

    /// 期望值照抄 `zemote_client.dart:273-281`（含 `if (taskId != null)` 的两种展开）。
    #[test]
    fn open_bridge_request_payload_shape() {
        let without_task = open_bridge_request("req-1", "bridge-1", 3, "wk-1", None);
        assert_eq!(without_task.len(), 5);
        assert_eq!(
            without_task.get("zcode_type").and_then(Value::as_str),
            Some("workspace-bridge-open")
        );
        assert_eq!(without_task.get("requestId"), Some(&json!("req-1")));
        assert_eq!(without_task.get("bridgeSessionId"), Some(&json!("bridge-1")));
        assert_eq!(without_task.get("bridgeGeneration"), Some(&json!(3)));
        assert_eq!(without_task.get("workspaceKey"), Some(&json!("wk-1")));
        assert!(!without_task.contains_key("taskId"));

        let with_task = open_bridge_request("req-1", "bridge-1", 3, "wk-1", Some("task-9"));
        assert_eq!(with_task.len(), 6);
        assert_eq!(with_task.get("taskId"), Some(&json!("task-9")));
    }

    /// 期望值照抄 `zemote_client.dart:361-370`：`recoveryId` 插在 `workspaceKey` **之前**，
    /// 且**没有** `taskId`。
    #[test]
    fn reopen_bridge_request_payload_shape() {
        let with_recovery =
            reopen_bridge_request("req-2", "bridge-2", 4, Some(&json!("recovery-1")), "wk-1");
        assert_eq!(with_recovery.len(), 6);
        assert_eq!(with_recovery.get("recoveryId"), Some(&json!("recovery-1")));
        assert!(!with_recovery.contains_key("taskId"));

        let without_recovery = reopen_bridge_request("req-2", "bridge-2", 4, None, "wk-1");
        assert_eq!(without_recovery.len(), 5);
        assert!(!without_recovery.contains_key("recoveryId"));
    }

    /// 期望值照抄 `zemote_client.dart:408-418`。
    #[test]
    fn reconnect_workspace_request_payload_shape() {
        let request = reconnect_workspace_request("req-3", "wk-1");
        assert_eq!(request.len(), 3);
        assert_eq!(
            request.get("zcode_type").and_then(Value::as_str),
            Some("workspace-reconnect-request")
        );
        assert_eq!(request.get("requestId"), Some(&json!("req-3")));
        assert_eq!(request.get("workspaceKey"), Some(&json!("wk-1")));
    }

    /// 期望值照抄 `zemote_client.dart:386-403`：`viewState` 里 `updatedAt` 排在
    /// `activeTaskId` **之后**；`deviceInfo` 复用 `device_info.dart` 的三个字段。
    #[test]
    fn mobile_view_state_update_payload_shape() {
        let device_info = DeviceInfo {
            platform: "android".to_string(),
            version: "0.5.3".to_string(),
            name: "zemote".to_string(),
        };
        let value = mobile_view_state_update_payload("wk-1", Some("task-7"), NOW, &device_info)
            .expect("构造应成功");
        assert_eq!(
            Value::Object(value),
            json!({
                "zcode_type": "mobile-view-state-update",
                "viewState": {
                    "activeWorkspaceKey": "wk-1",
                    "activeTaskId": "task-7",
                    "updatedAt": NOW,
                },
                "deviceInfo": {
                    "platform": "android",
                    "version": "0.5.3",
                    "name": "zemote",
                    // 对端 schema `MD` 的唯一必填时间戳；缺它整帧被静默丢弃。
                    "updatedAt": NOW,
                },
            })
        );

        let without_task =
            mobile_view_state_update_payload("wk-1", None, 42, &device_info).expect("构造应成功");
        assert_eq!(without_task["viewState"].get("activeTaskId"), None);
        assert_eq!(without_task["viewState"]["updatedAt"], json!(42));
    }

    /// 期望值照抄 `zemote_client.dart:509`：
    /// `'${scope['workspaceIdentity'] ?? scope['workspacePath']}'`。
    #[test]
    fn conversation_key_prefers_identity_then_path() {
        let both = json!({"workspaceIdentity": "id-1", "workspacePath": "/p"});
        assert_eq!(conversation_key(both.as_object().unwrap()), "id-1");

        let only_path = json!({"workspacePath": "/p"});
        assert_eq!(conversation_key(only_path.as_object().unwrap()), "/p");

        // 键存在但值为 null → Dart 的 `??` 也回落。
        let null_identity = json!({"workspaceIdentity": null, "workspacePath": "/p"});
        assert_eq!(conversation_key(null_identity.as_object().unwrap()), "/p");

        // 两者都缺 → 插值 `null` → "null"。
        let empty = json!({});
        assert_eq!(conversation_key(empty.as_object().unwrap()), "null");
    }

    // ------------------------------------------------------------------ 匹配器

    /// 对应 Dart 四个 `match` 闭包的形状（`zcode_type` 集合 + 字段严格相等）。
    #[test]
    fn payload_matcher_semantics() {
        let matcher = PayloadMatcher::new(&["a", "b"])
            .with_field("requestId", json!("r1"))
            .with_field("workspaceKey", json!("w1"));

        assert!(matcher
            .matches(&json!({"zcode_type": "a", "requestId": "r1", "workspaceKey": "w1"})));
        assert!(matcher
            .matches(&json!({"zcode_type": "b", "requestId": "r1", "workspaceKey": "w1"})));
        assert!(!matcher
            .matches(&json!({"zcode_type": "c", "requestId": "r1", "workspaceKey": "w1"})));
        assert!(!matcher
            .matches(&json!({"zcode_type": "a", "requestId": "r2", "workspaceKey": "w1"})));
        // 字段缺失 → 取到 null → 与 "w1" 不等。
        assert!(!matcher.matches(&json!({"zcode_type": "a", "requestId": "r1"})));
        // 非对象一律不匹配。
        assert!(!matcher.matches(&json!([1, 2, 3])));
        assert!(!matcher.matches(&json!(null)));
    }

    // ------------------------------------------------------------------ bootstrap / list

    /// 端到端：`bootstrap()` 真的把 `bootstrap-request` 发出去了，并起了一个 30 秒定时器。
    #[test]
    fn bootstrap_sends_request_and_arms_timeout() {
        let mut harness = Harness::new();
        harness.drive_to_paired();
        let operation = harness.client.bootstrap(NOW).expect("应能发起");
        assert!(operation.token.starts_with("bootstrap-"));

        let payload = harness.last_payload();
        assert_eq!(
            payload.get("zcode_type").and_then(Value::as_str),
            Some("bootstrap-request")
        );
        assert_eq!(payload.get("requestId"), Some(&json!(operation.token)));
        assert!(operation.events.iter().any(|event| matches!(
            event,
            ZemoteEvent::TimerStart {
                delay_ms: 30_000,
                ..
            }
        )));
    }

    /// 对应 Dart `bootstrap()` 的 `(res['result'] as Map?)?.cast() ?? res`。
    /// 字段名 `workspaces` 由 `session_list_probe_test.dart:24` 确认。
    #[test]
    fn bootstrap_extracts_result_map() {
        let mut harness = Harness::new();
        harness.drive_to_paired();
        let token = harness.client.bootstrap(NOW).expect("应能发起").token;
        let events = harness.data_frame(json!({
            "zcode_type": "bootstrap-response",
            "requestId": token,
            "result": {"workspaces": [{"workspacePath": "/p", "workspaceIdentity": "id-1"}]},
        }));
        match completed(&events) {
            Completion::Payload(value) => {
                assert_eq!(value["workspaces"][0]["workspacePath"], json!("/p"));
                assert_eq!(value["workspaces"][0]["workspaceIdentity"], json!("id-1"));
                assert!(value.get("zcode_type").is_none());
            }
            other => panic!("期望 result map，得到 {other:?}"),
        }
    }

    /// `result` 缺失 → 回落整个响应（Dart 的 `?? res`）。
    #[test]
    fn bootstrap_falls_back_to_whole_response() {
        let mut harness = Harness::new();
        harness.drive_to_paired();
        let token = harness.client.bootstrap(NOW).expect("应能发起").token;
        let events = harness.data_frame(json!({
            "zcode_type": "bootstrap-response",
            "requestId": token,
        }));
        match completed(&events) {
            Completion::Payload(value) => {
                assert_eq!(value["zcode_type"], json!("bootstrap-response"));
            }
            other => panic!("期望整个响应，得到 {other:?}"),
        }
    }

    /// `result` 存在但不是 Map → Dart 的 `as Map?` 抛 `TypeError`，这里收成协议错误。
    #[test]
    fn bootstrap_non_map_result_is_protocol_error() {
        let mut harness = Harness::new();
        harness.drive_to_paired();
        let token = harness.client.bootstrap(NOW).expect("应能发起").token;
        let events = harness.data_frame(json!({
            "zcode_type": "bootstrap-response",
            "requestId": token,
            "result": [1, 2, 3],
        }));
        match completed(&events) {
            Completion::Failed(ZemoteError::Protocol(message)) => {
                assert!(message.contains("bootstrap result"), "message={message}");
            }
            other => panic!("期望协议错误，得到 {other:?}"),
        }
    }

    /// 对应 Dart `listWorkspaces()` 的 `return res['result'];`。
    #[test]
    fn list_workspaces_returns_result_field() {
        let mut harness = Harness::new();
        harness.drive_to_paired();
        let token = harness.client.list_workspaces(NOW).expect("应能发起").token;
        assert!(token.starts_with("workspace-list-"));
        assert_eq!(
            harness.last_payload().get("zcode_type").and_then(Value::as_str),
            Some("workspace-list-request")
        );

        let events = harness.data_frame(json!({
            "zcode_type": "workspace-list-response",
            "requestId": token,
            "result": [{"workspacePath": "/a"}],
        }));
        match completed(&events) {
            Completion::Payload(value) => assert_eq!(value[0]["workspacePath"], json!("/a")),
            other => panic!("期望 result，得到 {other:?}"),
        }

        // `result` 缺失 → null（Dart 的 `res['result']`）。
        let token = harness.client.list_workspaces(NOW).expect("应能发起").token;
        let events = harness.data_frame(json!({
            "zcode_type": "workspace-list-response",
            "requestId": token,
        }));
        assert_eq!(completed(&events), &Completion::Payload(Value::Null));
    }

    /// 对应 Dart `Future.timeout`：超时撤销挂起项，之后迟到的响应不再完成它。
    #[test]
    fn request_timeout_removes_pending_and_late_response_is_ignored() {
        let mut harness = Harness::new();
        harness.drive_to_paired();
        let operation = harness.client.bootstrap(NOW).expect("应能发起");
        let timer = timer_of(&operation.events);

        let events = harness.step(ZemoteInput::TimerFired(timer));
        assert_eq!(
            completed(&events),
            &Completion::Failed(ZemoteError::Timeout(format!(
                "request {} timed out",
                operation.token
            )))
        );

        let events = harness.data_frame(json!({
            "zcode_type": "bootstrap-response",
            "requestId": operation.token,
            "result": {"workspaces": []},
        }));
        assert!(
            !events
                .iter()
                .any(|event| matches!(event, ZemoteEvent::Completed { .. })),
            "迟到的响应不应再完成已超时的请求"
        );
    }

    // ------------------------------------------------------------------ openBridge

    /// 端到端：`open_bridge` 成功 → 建桥 + 建栈 + 发 mobile-view-state + 完成事件。
    /// 顺序照抄 `zemote_client.dart:288-309`。
    #[test]
    fn open_bridge_success_attaches_stack_and_sends_view_state() {
        let mut harness = Harness::new();
        harness.drive_to_paired();

        let operation = harness
            .client
            .open_bridge("wk-1", Some("task-1"), OPEN_BRIDGE_TIMEOUT_MS, NOW)
            .expect("应能发起");
        assert!(operation.token.starts_with("workspace-bridge-"));
        let request = harness.last_payload();
        assert_eq!(
            request.get("zcode_type").and_then(Value::as_str),
            Some("workspace-bridge-open")
        );
        assert_eq!(request.get("bridgeGeneration"), Some(&json!(1)));
        assert_eq!(request.get("workspaceKey"), Some(&json!("wk-1")));
        assert_eq!(request.get("taskId"), Some(&json!("task-1")));
        let requested = request["bridgeSessionId"].as_str().unwrap().to_string();
        assert!(requested.starts_with("bridge-"));

        let events = harness.data_frame(json!({
            "zcode_type": "workspace-bridge-ready",
            "requestId": operation.token,
            "bridgeSessionId": requested,
            "bridge": {
                "bridgeSessionId": "wire-1",
                "bridgeGeneration": 7,
                "recoveryId": "rec-1",
                "workspaceKey": "wk-from-desktop",
                "initialTaskId": "task-from-desktop",
            },
        }));

        let session = session_of(&events);
        let bridge = harness.client.bridge(session).expect("桥应存在");
        assert_eq!(bridge.wire_bridge_session_id(), Some("wire-1"));
        assert_eq!(bridge.workspace_key(), Some("wk-from-desktop"));
        assert_eq!(bridge.initial_task_id(), Some("task-from-desktop"));
        assert_eq!(bridge.transport().bridge_generation, Some(7));
        assert_eq!(bridge.transport().recovery_id.as_deref(), Some("rec-1"));

        // 占位栈先被 dispose，再挂新栈（Dart `_attachStack` 的顺序）。
        assert!(events
            .iter()
            .any(|event| matches!(event, ZemoteEvent::BridgeStackDisposed { .. })));
        assert!(events
            .iter()
            .any(|event| matches!(event, ZemoteEvent::BridgeStackAttached { .. })));

        // `bridge['workspaceKey'] ?? workspaceKey` / `bridge['initialTaskId'] ?? taskId`
        let payload = harness.last_payload();
        assert_eq!(
            payload.get("zcode_type").and_then(Value::as_str),
            Some("mobile-view-state-update")
        );
        assert_eq!(
            payload["viewState"]["activeWorkspaceKey"],
            json!("wk-from-desktop")
        );
        assert_eq!(
            payload["viewState"]["activeTaskId"],
            json!("task-from-desktop")
        );
        assert_eq!(payload["deviceInfo"]["platform"], json!("android"));
        assert_eq!(payload["deviceInfo"]["version"], json!("0.5.3"));
        assert_eq!(payload["deviceInfo"]["name"], json!("zemote"));
    }

    /// `bridge` 字段缺失 → Dart 的 `?? <String, dynamic>{}`；workspaceKey / taskId 回落入参。
    #[test]
    fn open_bridge_without_bridge_map_falls_back_to_arguments() {
        let mut harness = Harness::new();
        harness.drive_to_paired();
        let operation = harness
            .client
            .open_bridge("wk-1", Some("task-1"), OPEN_BRIDGE_TIMEOUT_MS, NOW)
            .expect("应能发起");
        let requested = harness.last_payload()["bridgeSessionId"]
            .as_str()
            .unwrap()
            .to_string();
        let events = harness.data_frame(json!({
            "zcode_type": "workspace-bridge-ready",
            "requestId": operation.token,
            "bridgeSessionId": requested,
        }));
        let session = session_of(&events);
        let bridge = harness.client.bridge(session).unwrap();
        // `_bridge` 存的是 `res['bridge'] ?? {}` 原样（Dart `zemote_client.dart:291-292` 取空 map，
        // `:335` → `:492` `_bridge = bridge`），所以 `session._bridge['bridgeSessionId']` 本来就是 null。
        assert_eq!(bridge.wire_bridge_session_id(), None);
        // Dart `:320-322` 的 `?? requestedBridgeSessionId` **只**作用于 transport 的
        // `bridgeSessionId`，不回写 `session._bridge`。
        assert_eq!(bridge.transport().bridge_session_id, requested);
        let payload = harness.last_payload();
        assert_eq!(payload["viewState"]["activeWorkspaceKey"], json!("wk-1"));
        assert_eq!(payload["viewState"]["activeTaskId"], json!("task-1"));
    }

    /// 对应 Dart `throw StateError('workspace-bridge-error: ...')`。
    #[test]
    fn open_bridge_error_completes_with_invalid_state() {
        let mut harness = Harness::new();
        harness.drive_to_paired();
        let operation = harness
            .client
            .open_bridge("wk-1", None, OPEN_BRIDGE_TIMEOUT_MS, NOW)
            .expect("应能发起");
        let requested = harness.last_payload()["bridgeSessionId"]
            .as_str()
            .unwrap()
            .to_string();
        let events = harness.data_frame(json!({
            "zcode_type": "workspace-bridge-error",
            "requestId": operation.token,
            "bridgeSessionId": requested,
            "error": "workspace-closed",
        }));
        assert_eq!(
            completed(&events),
            &Completion::Failed(ZemoteError::InvalidState(
                "workspace-bridge-error: workspace-closed".to_string()
            ))
        );
        assert!(harness.client.active_bridges().is_empty());
    }

    /// 对应 Dart `openBridge(..., timeout:)` 超时 → 不建桥。
    #[test]
    fn open_bridge_timeout_leaves_no_session() {
        let mut harness = Harness::new();
        harness.drive_to_paired();
        let operation = harness
            .client
            .open_bridge("wk-1", None, OPEN_BRIDGE_TIMEOUT_MS, NOW)
            .expect("应能发起");
        let timer = timer_of(&operation.events);
        let events = harness.step(ZemoteInput::TimerFired(timer));
        assert!(matches!(
            completed(&events),
            Completion::Failed(ZemoteError::Timeout(_))
        ));
        assert!(harness.client.active_bridges().is_empty());
    }

    // ------------------------------------------------------------------ `_dispatchPayload` 路由

    /// 对应 Dart `_dispatchPayload` 的 `workspace-list-updated` 分支。
    #[test]
    fn workspace_list_updated_emits_stream_event() {
        let mut harness = Harness::new();
        harness.drive_to_paired();
        let events = harness.data_frame(json!({
            "zcode_type": "workspace-list-updated",
            "result": {"workspaces": [{"workspacePath": "/x"}]},
        }));
        let value = events
            .iter()
            .find_map(|event| match event {
                ZemoteEvent::WorkspaceListUpdated(value) => Some(value.clone()),
                _ => None,
            })
            .expect("应有 workspaceListUpdated 事件");
        assert_eq!(value["workspaces"][0]["workspacePath"], json!("/x"));
    }

    /// 未知 `bridgeSessionId` 的 rpc-frame 先被缓存，`_attachStack` 时冲刷（Initialize 竞态）。
    /// 观察点：完整单片消息会被回 `rpc-frame-ack`。
    #[test]
    fn rpc_frame_for_unknown_bridge_is_buffered_then_flushed_on_attach() {
        let mut harness = Harness::new();
        harness.drive_to_paired();
        let operation = harness
            .client
            .open_bridge("wk-1", None, OPEN_BRIDGE_TIMEOUT_MS, NOW)
            .expect("应能发起");
        let requested = harness.last_payload()["bridgeSessionId"]
            .as_str()
            .unwrap()
            .to_string();

        // 桥还没建好，先来一帧 —— 应被缓存，不产生 ack。
        harness.data_frame(rpc_frame("wire-1", b"payload-1"));
        assert!(
            sent_payloads(&harness)
                .iter()
                .all(|payload| payload.get("zcode_type").and_then(Value::as_str)
                    != Some("rpc-frame-ack")),
            "桥未建好时不该有 ack"
        );

        // 建桥（线上 id 就是 wire-1）→ 缓存的那帧被冲刷 → 重组 → 回 ack。
        let events = harness.data_frame(json!({
            "zcode_type": "workspace-bridge-ready",
            "requestId": operation.token,
            "bridgeSessionId": requested,
            "bridge": {"bridgeSessionId": "wire-1", "workspaceKey": "wk-1"},
        }));
        let session = session_of(&events);

        let ack = sent_payloads(&harness)
            .into_iter()
            .find(|payload| payload.get("zcode_type").and_then(Value::as_str) == Some("rpc-frame-ack"))
            .expect("应回一个 rpc-frame-ack");
        assert_eq!(ack.get("bridgeSessionId"), Some(&json!("wire-1")));
        assert_eq!(ack.get("ackMessageSeq"), Some(&json!(1)));
        assert_eq!(
            harness
                .client
                .bridge(session)
                .unwrap()
                .wire_bridge_session_id(),
            Some("wire-1")
        );
    }

    /// `bridgeSessionId` 缺失的 rpc-frame 直接丢弃（Dart: `id == null`）。
    #[test]
    fn rpc_frame_without_bridge_session_id_is_dropped() {
        let mut harness = Harness::new();
        harness.drive_to_paired();
        let events = harness.data_frame(json!({
            "zcode_type": "rpc-frame",
            "seq": 1,
            "messageSeq": 1,
            "fragmentIndex": 0,
            "fragmentCount": 1,
            "messageBytes": 1,
            "dataBase64": "aA==",
        }));
        // 不 panic、不路由、不缓存。
        assert!(events.is_empty());
    }

    /// 已知桥的 rpc-frame 直接路由到它的 transport（而不是缓存）。
    #[test]
    fn rpc_frame_routes_to_existing_bridge() {
        let mut harness = Harness::new();
        harness.drive_to_paired();
        let session = harness.open_bridge("wire-1");
        harness.data_frame(rpc_frame("wire-1", b"payload-1"));
        assert!(
            sent_payloads(&harness)
                .iter()
                .any(|payload| payload.get("zcode_type").and_then(Value::as_str)
                    == Some("rpc-frame-ack")),
            "已知桥的帧应被 transport 消费并回 ack"
        );
        assert_eq!(harness.client.active_bridges().len(), 1);
        assert_eq!(harness.client.bridge(session).unwrap().recovered(), 0);
    }

    // ------------------------------------------------------------------ 桥降级与恢复

    /// 对端主动报错：旧实现完全忽略，导致桌面端断开时移动端收不到任何通知。
    #[test]
    fn app_error_is_surfaced_as_event() {
        let mut harness = Harness::new();
        harness.drive_to_paired();

        let events = harness.data_frame(json!({
            "zcode_type": "app-error",
            "reason": "desktop-disconnected",
            "error": "bye",
        }));

        assert!(events.iter().any(|event| matches!(
            event,
            ZemoteEvent::AppError {
                reason: Some(reason),
                error: Some(error),
            } if reason == "desktop-disconnected" && error == "bye"
        )));
    }

    /// 对应 Dart `_handleBridgeDegraded`：标记 + 立刻开始恢复。
    #[test]
    fn bridge_degraded_marks_session_and_starts_recovery() {
        let mut harness = Harness::new();
        harness.drive_to_paired();
        let session = harness.open_bridge("wire-1");

        let events = harness.data_frame(json!({
            "zcode_type": "bridge-degraded",
            "bridgeSessionId": "wire-1",
            "reason": "rpc-transport-fault",
        }));

        // 恢复循环第一件事就是把 degraded 改成 'recovering'（Dart `_recoverBridgeOnce`）。
        assert_eq!(
            harness.client.bridge(session).unwrap().degraded(),
            Some("recovering")
        );
        assert!(events.iter().any(|event| matches!(
            event,
            ZemoteEvent::BridgeDegraded { degraded: Some(value), .. } if value == "rpc-transport-fault"
        )));
        // 阶段 1 发出 workspace-reconnect-request。
        let payload = harness.last_payload();
        assert_eq!(
            payload.get("zcode_type").and_then(Value::as_str),
            Some("workspace-reconnect-request")
        );
        assert_eq!(payload.get("workspaceKey"), Some(&json!("wk-1")));
    }

    /// 对应 Dart `_onRelayState`：`reconnecting` 时把所有活跃桥标成 degraded 并置
    /// `_needsBridgeRecovery`；回到 `paired` 后开始恢复。
    #[test]
    fn relay_reconnect_degrades_bridges_then_recovers_on_paired() {
        let mut harness = Harness::new();
        harness.drive_to_paired();
        let session = harness.open_bridge("wire-1");
        assert_eq!(harness.client.bridge(session).unwrap().degraded(), None);

        // 掉线：宿主回报 socket 关闭（`_wasPaired` 为真 → 进 reconnecting）。
        let generation = harness.generation;
        let events = harness.step(ZemoteInput::Relay(RelayInput::SocketClosed {
            generation,
            code: 1006,
            reason: Some("network switch".to_string()),
        }));
        assert_eq!(
            harness.client.bridge(session).unwrap().degraded(),
            Some("reconnecting")
        );
        assert!(events.iter().any(|event| matches!(
            event,
            ZemoteEvent::BridgeDegraded { degraded: Some(value), .. } if value == "reconnecting"
        )));

        // 重连：等 relay 自己的退避定时器到点 → 它用新代次重新建连。
        harness.step(ZemoteInput::Relay(RelayInput::TimerFired(RelayTimer::Reconnect)));
        harness.generation = harness.last_connect_generation();
        assert_ne!(harness.generation, generation, "重连应换新代次");
        let generation = harness.generation;
        harness.step(ZemoteInput::Relay(RelayInput::SocketReady { generation }));
        harness.text_frame(json!({"type": "auth_challenge", "nonce": "n2"}).to_string());
        harness.text_frame(
            json!({"type": "pair_status_ack", "pair_status": "matched"}).to_string(),
        );

        // 回到 paired → 恢复循环开跑（阶段 1 又发了一次 reconnect 请求）。
        assert_eq!(
            harness.last_payload().get("zcode_type").and_then(Value::as_str),
            Some("workspace-reconnect-request")
        );
    }

    /// 对应 Dart `_recoverBridgeOnce` 阶段 1 成功：清 degraded + `recovered += 1`。
    #[test]
    fn recovery_stage_one_success_clears_degraded() {
        let mut harness = Harness::new();
        harness.drive_to_paired();
        let session = harness.open_bridge("wire-1");

        harness.data_frame(json!({
            "zcode_type": "bridge-degraded",
            "bridgeSessionId": "wire-1",
            "reason": "fault",
        }));
        let request_id = harness.last_payload()["requestId"]
            .as_str()
            .unwrap()
            .to_string();

        let events = harness.data_frame(json!({
            "zcode_type": "workspace-reconnect-response",
            "requestId": request_id,
            "workspaceKey": "wk-1",
            "success": true,
        }));

        let bridge = harness.client.bridge(session).unwrap();
        assert_eq!(bridge.degraded(), None);
        assert_eq!(bridge.recovered(), 1);
        assert!(events
            .iter()
            .any(|event| matches!(event, ZemoteEvent::BridgeRecovered { count: 1, .. })));
        // 恢复完成 → 不再处于 recovering（再收到 degraded 会重新开一轮）。
        assert!(harness.client.recovering.is_empty());
    }

    /// 阶段 1 成功标志不为 true → 走完整重开（`_reopenBridge`）。
    #[test]
    fn recovery_falls_back_to_full_reopen() {
        let mut harness = Harness::new();
        harness.drive_to_paired();
        let session = harness.open_bridge("wire-1");
        // `open_bridge` 走完之后最后一条载荷是 `mobile-view-state-update`，所以首次
        // `bridgeGeneration` 要从已发出的 `workspace-bridge-open` 里取
        // （Dart `zemote_client.dart:271,278` 的 `++_bridgeGeneration`，首次为 1）。
        let first_generation = sent_payloads(&harness)
            .into_iter()
            .find(|payload| {
                payload.get("zcode_type").and_then(Value::as_str)
                    == Some("workspace-bridge-open")
            })
            .expect("应发出过 workspace-bridge-open")
            .get("bridgeGeneration")
            .and_then(Value::as_i64)
            .expect("workspace-bridge-open 应带 bridgeGeneration");

        harness.data_frame(json!({
            "zcode_type": "bridge-degraded",
            "bridgeSessionId": "wire-1",
            "reason": "fault",
        }));
        let request_id = harness.last_payload()["requestId"]
            .as_str()
            .unwrap()
            .to_string();
        // `success` 不是 true（Dart 只在 `== true` 时走快路径）。
        harness.data_frame(json!({
            "zcode_type": "workspace-reconnect-response",
            "requestId": request_id,
            "workspaceKey": "wk-1",
            "success": false,
        }));

        // 阶段 2：新的 workspace-bridge-open，代次 +1，无 taskId。
        let reopen = harness.last_payload();
        assert_eq!(
            reopen.get("zcode_type").and_then(Value::as_str),
            Some("workspace-bridge-open")
        );
        assert_eq!(
            reopen.get("bridgeGeneration"),
            Some(&json!(first_generation + 1))
        );
        assert!(!reopen.contains_key("taskId"));
        let requested = reopen["bridgeSessionId"].as_str().unwrap().to_string();
        assert!(requested.starts_with("bridge-"));

        let events = harness.data_frame(json!({
            "zcode_type": "workspace-bridge-ready",
            "requestId": reopen["requestId"].clone(),
            "bridgeSessionId": requested,
            "bridge": {
                "bridgeSessionId": "wire-2",
                "bridgeGeneration": 8,
                "recoveryId": "rec-2",
                "workspaceKey": "wk-1",
            },
        }));

        // 同一个 BridgeSession 对象换栈（不是新建）。
        assert_eq!(harness.client.active_bridges().len(), 1);
        let bridge = harness.client.bridge(session).unwrap();
        assert_eq!(bridge.wire_bridge_session_id(), Some("wire-2"));
        assert_eq!(bridge.transport().recovery_id.as_deref(), Some("rec-2"));
        assert_eq!(bridge.degraded(), None);
        assert_eq!(bridge.recovered(), 1);
        assert!(events
            .iter()
            .any(|event| matches!(event, ZemoteEvent::BridgeStackAttached { .. })));
    }

    /// 重开带上旧桥的 `recoveryId`（Dart `if (oldBridge['recoveryId'] != null)`）。
    #[test]
    fn reopen_carries_old_recovery_id() {
        let mut harness = Harness::new();
        harness.drive_to_paired();
        harness.open_bridge_with_recovery("wire-1", "rec-old");
        harness.data_frame(json!({
            "zcode_type": "bridge-degraded",
            "bridgeSessionId": "wire-1",
            "reason": "fault",
        }));
        let request_id = harness.last_payload()["requestId"]
            .as_str()
            .unwrap()
            .to_string();
        harness.data_frame(json!({
            "zcode_type": "workspace-reconnect-response",
            "requestId": request_id,
            "workspaceKey": "wk-1",
            "success": false,
        }));
        assert_eq!(
            harness.last_payload().get("recoveryId"),
            Some(&json!("rec-old"))
        );
    }

    /// 重开失败 → `degraded = 'reopen-failed: ...'` + 3 秒后重试。
    #[test]
    fn recovery_reopen_failure_sets_reopen_failed_and_retries() {
        let mut harness = Harness::new();
        harness.drive_to_paired();
        let session = harness.open_bridge("wire-1");

        harness.data_frame(json!({
            "zcode_type": "bridge-degraded",
            "bridgeSessionId": "wire-1",
            "reason": "fault",
        }));
        let request_id = harness.last_payload()["requestId"]
            .as_str()
            .unwrap()
            .to_string();
        harness.data_frame(json!({
            "zcode_type": "workspace-reconnect-response",
            "requestId": request_id,
            "workspaceKey": "wk-1",
            "success": false,
        }));
        let reopen = harness.last_payload();
        let requested = reopen["bridgeSessionId"].as_str().unwrap().to_string();

        let events = harness.data_frame(json!({
            "zcode_type": "workspace-bridge-error",
            "requestId": reopen["requestId"].clone(),
            "bridgeSessionId": requested,
            "error": "desktop-busy",
        }));

        assert_eq!(
            harness.client.bridge(session).unwrap().degraded(),
            Some("reopen-failed: workspace-bridge-error: desktop-busy")
        );
        // Dart `_log`（`zemote_client.dart:88`）走门面自己的 `onLog` → `ZemoteEvent::Log`
        // （不是 relay 的日志）。
        assert!(events.iter().any(|event| matches!(
            event,
            ZemoteEvent::Log(line) if line == "[bridge] recovery attempt 1 failed, retrying"
        )));
        assert!(events.iter().any(|event| matches!(
            event,
            ZemoteEvent::TimerStart { delay_ms: 3_000, .. }
        )));
        let retry = timer_of(&events);

        // 3 秒后重试：`_recoverBridgeWithRetry` 的循环回到 `_recoverBridgeOnce`
        // （Dart `:84-89`）。这条路径**不**经过 `_recoverActiveBridges`，所以不会再有
        // `[bridge] recovering ...` 那条日志 —— 它只在 `:69` 出现过一次。
        // 能确定的可观测结果：degraded 被重新置为 'recovering'（`:104`），且阶段 1 再次发出。
        let events = harness.step(ZemoteInput::TimerFired(retry));
        assert!(events.iter().any(|event| matches!(
            event,
            ZemoteEvent::BridgeDegraded { degraded: Some(value), .. } if value == "recovering"
        )));
        assert_eq!(
            harness.last_payload().get("zcode_type").and_then(Value::as_str),
            Some("workspace-reconnect-request")
        );
    }

    /// 第 15 次失败后循环终止（Dart `for (...; attempt <= 15; ...)`）。
    #[test]
    fn recovery_stops_after_max_attempts() {
        let mut harness = Harness::new();
        harness.drive_to_paired();
        let session = harness.open_bridge("wire-1");

        // 第 1 次：degraded → 阶段 1 失败 → 重开失败 → 等 3 秒。
        harness.data_frame(json!({
            "zcode_type": "bridge-degraded",
            "bridgeSessionId": "wire-1",
            "reason": "fault",
        }));
        let request_id = harness.last_payload()["requestId"]
            .as_str()
            .unwrap()
            .to_string();
        harness.data_frame(json!({
            "zcode_type": "workspace-reconnect-response",
            "requestId": request_id,
            "workspaceKey": "wk-1",
            "success": false,
        }));
        let mut retry = fail_reopen(&mut harness);

        // 第 2..=15 次：每次都是「阶段 1 失败 → 重开失败 → 3 秒」。
        for _ in 2..=RECOVERY_MAX_ATTEMPTS {
            harness.step(ZemoteInput::TimerFired(retry));
            let request_id = harness.last_payload()["requestId"]
                .as_str()
                .unwrap()
                .to_string();
            harness.data_frame(json!({
                "zcode_type": "workspace-reconnect-response",
                "requestId": request_id,
                "workspaceKey": "wk-1",
                "success": false,
            }));
            retry = fail_reopen(&mut harness);
        }

        // 第 15 次之后的 3 秒到点 → 循环终止，不再发新请求。
        let events = harness.step(ZemoteInput::TimerFired(retry));
        // 注意：这里的日志变体必须是 `ZemoteEvent::Log` —— 恢复流程的日志走门面 `onLog`
        // （Dart `_log`），不是 relay 的日志。曾经误写成 `Relay(RelayEvent::Log)`，
        // 那个变体永不匹配，`!any(..)` 就恒为真、断言永远不可能失败。
        assert!(!events.iter().any(|event| matches!(
            event,
            ZemoteEvent::Log(line) if line.starts_with("[bridge] recovering")
        )));
        assert!(harness.client.recovering.is_empty());
        assert_eq!(
            harness.client.bridge(session).unwrap().degraded(),
            Some("reopen-failed: workspace-bridge-error: nope")
        );
    }

    /// 没有 `workspaceKey` 的桥：无处可重连，直接清 degraded 让命令解除阻塞。
    #[test]
    fn recovery_without_workspace_key_clears_degraded_immediately() {
        let mut harness = Harness::new();
        harness.drive_to_paired();
        let operation = harness
            .client
            .open_bridge("wk-1", None, OPEN_BRIDGE_TIMEOUT_MS, NOW)
            .expect("应能发起");
        let requested = harness.last_payload()["bridgeSessionId"]
            .as_str()
            .unwrap()
            .to_string();
        let events = harness.data_frame(json!({
            "zcode_type": "workspace-bridge-ready",
            "requestId": operation.token,
            "bridgeSessionId": requested,
            "bridge": {"bridgeSessionId": "wire-1"},
        }));
        let session = session_of(&events);

        harness.data_frame(json!({
            "zcode_type": "bridge-degraded",
            "bridgeSessionId": "wire-1",
            "reason": "fault",
        }));
        assert_eq!(harness.client.bridge(session).unwrap().degraded(), None);
        assert!(harness.client.recovering.is_empty());
    }

    // ------------------------------------------------------------------ waitPaired / waitHealthy

    /// 对应 Dart `waitPaired` 的 `if (relay.state == paired) return Future.value();`。
    #[test]
    fn wait_paired_returns_none_when_already_paired() {
        let mut harness = Harness::new();
        harness.drive_to_paired();
        assert!(harness.client.wait_paired(PAIR_TIMEOUT_MS).is_none());
    }

    /// 配对成功 → `Completion::Ok`，并撤销定时器。
    #[test]
    fn wait_paired_completes_on_paired() {
        let mut harness = Harness::new();
        harness.step(ZemoteInput::Relay(RelayInput::Start));
        let operation = harness
            .client
            .wait_paired(PAIR_TIMEOUT_MS)
            .expect("未配对时应返回令牌");
        assert!(operation.token.starts_with("pair:"));
        let timer = timer_of(&operation.events);

        let generation = harness.generation;
        harness.step(ZemoteInput::Relay(RelayInput::SocketReady { generation }));
        harness.text_frame(json!({"type": "auth_challenge", "nonce": "n"}).to_string());
        let events = harness.text_frame(
            json!({"type": "pair_status_ack", "pair_status": "matched"}).to_string(),
        );

        assert!(events.iter().any(|event| matches!(
            event,
            ZemoteEvent::Completed { token, outcome: Completion::Ok } if token == &operation.token
        )));
        assert!(events.iter().any(|event| matches!(
            event,
            ZemoteEvent::TimerCancel(cancelled) if *cancelled == timer
        )));
    }

    /// 对应 Dart `TimeoutException('pairing timeout')`。
    #[test]
    fn wait_paired_times_out() {
        let mut harness = Harness::new();
        harness.step(ZemoteInput::Relay(RelayInput::Start));
        let operation = harness
            .client
            .wait_paired(PAIR_TIMEOUT_MS)
            .expect("未配对时应返回令牌");
        let timer = timer_of(&operation.events);
        let events = harness.step(ZemoteInput::TimerFired(timer));
        assert_eq!(
            completed(&events),
            &Completion::Failed(ZemoteError::Timeout("pairing timeout".to_string()))
        );
        assert!(harness.client.pair_waiters.is_empty());
    }

    /// `waitHealthy`：健康时立即返回 `None`。
    #[test]
    fn bridge_wait_healthy_returns_none_when_healthy() {
        let mut harness = Harness::new();
        harness.drive_to_paired();
        let session = harness.open_bridge("wire-1");
        assert!(harness
            .client
            .bridge_wait_healthy(session, BRIDGE_HEALTHY_TIMEOUT_MS)
            .expect("应能查询")
            .is_none());
    }

    /// `waitHealthy` 在 degraded 清空时完成（`Completion::Ok`）。
    #[test]
    fn bridge_wait_healthy_resolves_when_degraded_clears() {
        let mut harness = Harness::new();
        harness.drive_to_paired();
        let session = harness.open_bridge("wire-1");
        harness.data_frame(json!({
            "zcode_type": "bridge-degraded",
            "bridgeSessionId": "wire-1",
            "reason": "fault",
        }));

        let operation = harness
            .client
            .bridge_wait_healthy(session, BRIDGE_HEALTHY_TIMEOUT_MS)
            .expect("应能查询")
            .expect("degraded 期间应返回令牌");
        let timer = timer_of(&operation.events);

        // 阶段 1 成功 → degraded 清空 → 唤醒。
        let request_id = harness.last_payload()["requestId"]
            .as_str()
            .unwrap()
            .to_string();
        let events = harness.data_frame(json!({
            "zcode_type": "workspace-reconnect-response",
            "requestId": request_id,
            "workspaceKey": "wk-1",
            "success": true,
        }));
        assert!(events.iter().any(|event| matches!(
            event,
            ZemoteEvent::Completed { token, outcome: Completion::Ok } if token == &operation.token
        )));
        assert!(events.iter().any(|event| matches!(
            event,
            ZemoteEvent::TimerCancel(cancelled) if *cancelled == timer
        )));
    }

    /// 对应 Dart `TimeoutException('bridge 恢复超时: ${degraded.value}')`。
    #[test]
    fn bridge_wait_healthy_timeout_message_matches_dart() {
        let mut harness = Harness::new();
        harness.drive_to_paired();
        let session = harness.open_bridge("wire-1");
        harness.data_frame(json!({
            "zcode_type": "bridge-degraded",
            "bridgeSessionId": "wire-1",
            "reason": "fault",
        }));
        // 恢复循环会把 degraded 改成 'recovering'，超时文案带的就是这个值。
        let operation = harness
            .client
            .bridge_wait_healthy(session, BRIDGE_HEALTHY_TIMEOUT_MS)
            .expect("应能查询")
            .expect("degraded 期间应返回令牌");
        let timer = timer_of(&operation.events);
        let events = harness.step(ZemoteInput::TimerFired(timer));
        assert_eq!(
            completed(&events),
            &Completion::Failed(ZemoteError::Timeout(
                "bridge 恢复超时: recovering".to_string()
            ))
        );
    }

    // ------------------------------------------------------------------ 会话缓存

    /// 首次调用发 `ConversationOpened`，之后同 key 复用（Dart 的 `putIfAbsent`）。
    #[test]
    fn conversation_is_cached_per_key() {
        let mut harness = Harness::new();
        harness.drive_to_paired();
        let session = harness.open_bridge("wire-1");

        let scope = json!({"workspacePath": "/p", "workspaceIdentity": "id-1"})
            .as_object()
            .unwrap()
            .clone();
        let first = harness
            .client
            .conversation(session, scope.clone())
            .expect("应能建立");
        assert_eq!(first.token, "id-1");
        assert!(first.events.iter().any(|event| matches!(
            event,
            ZemoteEvent::ConversationOpened { key, .. } if key == "id-1"
        )));

        let second = harness
            .client
            .conversation(session, scope)
            .expect("应能复用");
        assert_eq!(second.token, "id-1");
        assert!(second.events.is_empty(), "同 key 不应再发 ConversationOpened");
        assert_eq!(
            harness
                .client
                .bridge(session)
                .unwrap()
                .conversation_keys()
                .collect::<Vec<_>>(),
            vec!["id-1"]
        );

        // 重开（同一个 BridgeSession 换栈）**不**清缓存
        // —— 照抄 Dart：注释说 cleared on stack swap，代码里 `_swap` 没有清。
        harness.data_frame(json!({
            "zcode_type": "bridge-degraded",
            "bridgeSessionId": "wire-1",
            "reason": "fault",
        }));
        let request_id = harness.last_payload()["requestId"]
            .as_str()
            .unwrap()
            .to_string();
        harness.data_frame(json!({
            "zcode_type": "workspace-reconnect-response",
            "requestId": request_id,
            "workspaceKey": "wk-1",
            "success": false,
        }));
        let reopen = harness.last_payload();
        let requested = reopen["bridgeSessionId"].as_str().unwrap().to_string();
        harness.data_frame(json!({
            "zcode_type": "workspace-bridge-ready",
            "requestId": reopen["requestId"].clone(),
            "bridgeSessionId": requested,
            "bridge": {"bridgeSessionId": "wire-2", "workspaceKey": "wk-1"},
        }));

        let bridge = harness.client.bridge(session).expect("桥应仍在");
        assert_eq!(bridge.wire_bridge_session_id(), Some("wire-2"));
        assert_eq!(
            bridge.conversation_keys().collect::<Vec<_>>(),
            vec!["id-1"]
        );

        // 换栈后同 key 仍然复用（宿主靠 `BridgeRecovered` 自行重置握手状态）。
        let third = harness
            .client
            .conversation(session, json!({"workspacePath": "/p", "workspaceIdentity": "id-1"})
                .as_object()
                .unwrap()
                .clone())
            .expect("应能复用");
        assert_eq!(third.token, "id-1");
        assert!(third.events.is_empty());
    }

    /// 未知桥句柄 → `InvalidState`。
    #[test]
    fn conversation_on_unknown_bridge_is_invalid_state() {
        let mut harness = Harness::new();
        let error = harness
            .client
            .conversation(BridgeSessionId(99), Payload::new())
            .expect_err("未知桥应报错");
        assert!(matches!(error, ZemoteError::InvalidState(_)));
    }

    // ------------------------------------------------------------------ dispose / poke

    /// 对应 Dart `dispose()`：逐个 dispose 桥 + dispose relay，之后忽略载荷与状态变化。
    #[test]
    fn dispose_disposes_bridges_and_relay_then_ignores_payloads() {
        let mut harness = Harness::new();
        harness.drive_to_paired();
        let session = harness.open_bridge("wire-1");

        let events = harness.client.dispose(NOW);
        assert!(harness.client.is_disposed());
        assert!(harness.client.active_bridges().is_empty());
        assert!(events.iter().any(|event| matches!(
            event,
            ZemoteEvent::BridgeStackDisposed { session: disposed } if *disposed == session
        )));
        assert!(events.iter().any(|event| matches!(
            event,
            ZemoteEvent::Relay(RelayEvent::StateChanged(RelayState::Closed))
        )));

        // 监听已摘：后续载荷不再产出任何门面事件。
        let events = harness.data_frame(json!({
            "zcode_type": "workspace-list-updated",
            "result": {"workspaces": []},
        }));
        assert!(!events
            .iter()
            .any(|event| matches!(event, ZemoteEvent::WorkspaceListUpdated(_))));
    }

    /// `pokeRelay()` 转发到 relay（配对态下会发 `pair_status_query`）。
    #[test]
    fn poke_relay_sends_pair_status_query_when_paired() {
        let mut harness = Harness::new();
        harness.drive_to_paired();
        harness.client.poke_relay(NOW);
        let text = harness.sent_texts().pop().expect("应有帧");
        let envelope: Value = serde_json::from_str(&text).expect("信封应是 JSON");
        assert_eq!(envelope.get("type"), Some(&json!("pair_status_query")));
    }

    // ------------------------------------------------------------------ rpc 分片清理周期

    /// attach 之后核心会为这个桥 arm 一个 **30 秒**的分片清理定时器
    /// （Dart `RpcFrameTransport` 构造函数里的 `Timer.periodic(30s)`）。
    #[test]
    fn attach_arms_a_30_second_fragment_purge_timer() {
        let mut harness = Harness::new();
        harness.drive_to_paired();
        let (_session, events) = open_bridge_capturing(&mut harness, "wire-1");

        // attach 这批事件里恰好一条 `TimerStart`，且延迟就是 30000ms。
        let starts: Vec<i64> = events
            .iter()
            .filter_map(|event| match event {
                ZemoteEvent::TimerStart { delay_ms, .. } => Some(*delay_ms),
                _ => None,
            })
            .collect();
        assert_eq!(starts, vec![30_000], "attach 后应 arm 恰好一个 30s 清理定时器");
        assert_eq!(purge_timers(&harness.client).len(), 1);
    }

    /// 定时器到点：清掉超过 60 秒未凑齐的重组装配体（发 `Log`），并**重排**下一个周期。
    #[test]
    fn fragment_purge_timer_purges_stale_assembly_and_rearms() {
        let mut harness = Harness::new();
        harness.drive_to_paired();
        let (_session, attach_events) = open_bridge_capturing(&mut harness, "wire-1");
        let timer = purge_timer_of(&attach_events);

        // 造一个永远凑不齐的重组：2 片只来 1 片，创建时间 = NOW。
        harness.data_frame(json!({
            "zcode_type": "rpc-frame",
            "bridgeSessionId": "wire-1",
            "messageSeq": 1,
            "fragmentIndex": 0,
            "fragmentCount": 2,
            "messageBytes": 200,
            "dataBase64": base64::engine::general_purpose::STANDARD.encode(vec![7u8; 100]),
        }));

        // 61 秒后到点（`inSeconds > 60` 照抄 Dart 的截断语义）。
        let events = harness
            .client
            .step(ZemoteInput::TimerFired(timer), NOW + 61_000);
        assert!(
            events.iter().any(|event| matches!(
                event,
                ZemoteEvent::Log(line) if line == "[rpc] purged stale assembly 1"
            )),
            "到点应清理过期装配体并打出整行日志"
        );

        // `Timer.periodic` 的周期语义：必须重排下一个 30 秒。
        let next = purge_timer_of(&events);
        assert_ne!(next, timer, "重排应给一个新的定时器句柄");
        assert_eq!(purge_timers(&harness.client), vec![next]);
    }

    /// 桥 dispose 后清理定时器被取消，且到点不再 purge、不再重排。
    #[test]
    fn dispose_cancels_fragment_purge_timer() {
        let mut harness = Harness::new();
        harness.drive_to_paired();
        let (session, attach_events) = open_bridge_capturing(&mut harness, "wire-1");
        let timer = purge_timer_of(&attach_events);

        harness.data_frame(json!({
            "zcode_type": "rpc-frame",
            "bridgeSessionId": "wire-1",
            "messageSeq": 1,
            "fragmentIndex": 0,
            "fragmentCount": 2,
            "messageBytes": 200,
            "dataBase64": base64::engine::general_purpose::STANDARD.encode(vec![7u8; 100]),
        }));

        let events = harness.client.dispose(NOW);
        assert!(
            events.iter().any(|event| matches!(
                event,
                ZemoteEvent::TimerCancel(cancelled) if *cancelled == timer
            )),
            "dispose 应取消该桥的清理定时器"
        );
        assert!(purge_timers(&harness.client).is_empty());
        assert!(harness.client.bridge(session).is_none(), "桥已 dispose");

        // 到点也不再 purge / 重排。
        let events = harness
            .client
            .step(ZemoteInput::TimerFired(timer), NOW + 61_000);
        assert!(!events.iter().any(|event| matches!(
            event,
            ZemoteEvent::Log(line) if line.starts_with("[rpc] purged")
        )));
        assert!(purge_timers(&harness.client).is_empty());
    }

    /// 换栈（重开）之后旧清理定时器被取消，且**只**留下一条新的 —— 不能两条并存。
    #[test]
    fn reopen_swap_keeps_exactly_one_fragment_purge_timer() {
        let mut harness = Harness::new();
        harness.drive_to_paired();
        let (session, attach_events) = open_bridge_capturing(&mut harness, "wire-1");
        let first = purge_timer_of(&attach_events);
        assert_eq!(purge_timers(&harness.client), vec![first]);

        // 降级 → 阶段 1 失败 → 阶段 2 重开成功（同一个 BridgeSession 换栈）。
        harness.data_frame(json!({
            "zcode_type": "bridge-degraded",
            "bridgeSessionId": "wire-1",
            "reason": "fault",
        }));
        let request_id = harness.last_payload()["requestId"]
            .as_str()
            .unwrap()
            .to_string();
        harness.data_frame(json!({
            "zcode_type": "workspace-reconnect-response",
            "requestId": request_id,
            "workspaceKey": "wk-1",
            "success": false,
        }));
        let reopen = harness.last_payload();
        let requested = reopen["bridgeSessionId"].as_str().unwrap().to_string();
        let events = harness.data_frame(json!({
            "zcode_type": "workspace-bridge-ready",
            "requestId": reopen["requestId"].clone(),
            "bridgeSessionId": requested,
            "bridge": {"bridgeSessionId": "wire-2", "workspaceKey": "wk-1"},
        }));

        assert!(
            events.iter().any(|event| matches!(
                event,
                ZemoteEvent::TimerCancel(cancelled) if *cancelled == first
            )),
            "换栈应先取消旧清理定时器"
        );
        let live = purge_timers(&harness.client);
        assert_eq!(live.len(), 1, "换栈后不能同时存在两条清理定时器");
        assert_ne!(live[0], first);
        assert_eq!(harness.client.active_bridges().len(), 1);
        assert_eq!(
            harness.client.bridge(session).unwrap().wire_bridge_session_id(),
            Some("wire-2")
        );
    }

    // ------------------------------------------------------------------ 测试内小工具

    impl Harness {
        /// 打开一个带 `recoveryId` 的桥。
        fn open_bridge_with_recovery(&mut self, wire_id: &str, recovery_id: &str) {
            let operation = self
                .client
                .open_bridge("wk-1", None, OPEN_BRIDGE_TIMEOUT_MS, NOW)
                .expect("应能发起");
            let requested = self.last_payload()["bridgeSessionId"]
                .as_str()
                .unwrap()
                .to_string();
            self.data_frame(json!({
                "zcode_type": "workspace-bridge-ready",
                "requestId": operation.token,
                "bridgeSessionId": requested,
                "bridge": {
                    "bridgeSessionId": wire_id,
                    "workspaceKey": "wk-1",
                    "recoveryId": recovery_id,
                },
            }));
        }
    }

    /// 让当前阶段 2 的请求以 `workspace-bridge-error` 失败，返回下一次重试的定时器。
    fn fail_reopen(harness: &mut Harness) -> TimerId {
        let reopen = harness.last_payload();
        let requested = reopen["bridgeSessionId"].as_str().unwrap().to_string();
        let events = harness.data_frame(json!({
            "zcode_type": "workspace-bridge-error",
            "requestId": reopen["requestId"].clone(),
            "bridgeSessionId": requested,
            "error": "nope",
        }));
        timer_of(&events)
    }
}
