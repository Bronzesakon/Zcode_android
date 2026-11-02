//! 对应 `Reference_code/lib/protocol/relay_client.dart`——中继终端 WebSocket 客户端。
//!
//! 网页端是 `pen` 类：在 `wss://<host>/ws` 上跑 JSON 文本帧（`auth_init` →
//! `auth_challenge`/`auth_response` → `pair_status` → `data`）。本模块把 Dart 那份
//! 「socket + Timer + Stream + ValueNotifier」的实现搬成**纯逻辑、同步、无 I/O** 的
//! 状态机（契约见 `PORTING.md`）：
//!
//! - 全部 I/O 走 [`RelayTransport`]（宿主实现；Android 侧接 OkHttp WebSocket）；
//! - 全部定时器由宿主驱动：[`RelayClient::step`] 吐出 [`RelayEvent::TimerStart`] /
//!   [`RelayEvent::TimerCancel`]，宿主到点后把 [`RelayInput::TimerFired`] 喂回来；
//! - 时间戳一律由调用方传入 `now_ms`（Unix 毫秒），本模块不读 `std::time`；
//! - Dart 的 `onLog` / `stateListenable` / `payloads` / `failures` 四条输出分别对应
//!   [`RelayEvent::Log`] / [`RelayEvent::StateChanged`] / [`RelayEvent::Payload`] /
//!   [`RelayEvent::Failure`]。
//!
//! ## 宿主驱动方式
//!
//! ```text
//! 1. client.step(RelayInput::Start, &mut transport, now)     // 对应 start()
//! 2. transport.connect(generation, uri) 收到后建连：
//!      成功 → client.step(SocketReady { generation }, ..)
//!      失败 → client.step(SocketFailed { generation, error }, ..)
//! 3. 收到文本帧 → client.step(TextFrame { generation, text }, ..)
//!    收到二进制帧 → client.step(BinaryFrame { generation, bytes }, ..)
//!    连接关闭    → client.step(SocketClosed { generation, code, reason }, ..)
//! 4. 事件里的 TimerStart / TimerCancel 驱动宿主定时器；到点回 TimerFired
//! 5. 上行数据 → SendPayload；App 回前台 → Poke；退出 → Dispose
//! ```
//!
//! `generation` 是 Dart 的 `_socketGeneration`，它**就是 socket 的句柄**：宿主每次收到
//! [`RelayTransport::connect`] 记下代次，之后回报结果 / 投递帧 / 关闭时原样带回。

use std::fmt;

use serde_json::{json, Map, Value};

use crate::connection_params::{Uri, ZemoteConnectionParams};
use crate::device_info::{DeviceInfo, ZEMOTE_APP_NAME};
use crate::proof::calculate_proof;

/// 对应 Dart `RelayState`（`relay_client.dart:11`）的 9 个取值，顺序照抄。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum RelayState {
    /// 对应 `RelayState.idle`。
    Idle,
    /// 对应 `RelayState.connecting`。
    Connecting,
    /// 对应 `RelayState.authenticating`。
    Authenticating,
    /// 对应 `RelayState.waiting`。
    Waiting,
    /// 对应 `RelayState.paired`。
    Paired,
    /// 对应 `RelayState.reconnecting`。
    Reconnecting,
    /// 对应 `RelayState.error`。
    Error,
    /// 对应 `RelayState.kicked`。
    Kicked,
    /// 对应 `RelayState.closed`。
    Closed,
}

impl RelayState {
    /// 小写名（`"paired"`），供宿主做存储 / 映射用。
    ///
    /// 注意：**不要**拿它拼 Dart 的日志文本——Dart 的 `$state` 走 enum 默认
    /// `toString()`，得到的是 `"RelayState.paired"`，那由 [`fmt::Display`] 负责。
    pub fn as_str(self) -> &'static str {
        match self {
            RelayState::Idle => "idle",
            RelayState::Connecting => "connecting",
            RelayState::Authenticating => "authenticating",
            RelayState::Waiting => "waiting",
            RelayState::Paired => "paired",
            RelayState::Reconnecting => "reconnecting",
            RelayState::Error => "error",
            RelayState::Kicked => "kicked",
            RelayState::Closed => "closed",
        }
    }
}

/// 对应 Dart enum 的默认 `toString()`：`"RelayState.paired"`。
///
/// `relay_client.dart` 的日志里有 `'[relay] state -> $s'` 与 `'queued (state=$state)'`，
/// 两处都是这个形态，故 `Display` 必须与之一致（[`RelayState::as_str`] 是给小写场景用的）。
impl fmt::Display for RelayState {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "RelayState.{}", self.as_str())
    }
}

/// 对应 Dart `RelayFailure`（`relay_client.dart:23`）：一个原因码 + 可选详情。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RelayFailure {
    /// 对应 `reason`：`session-not-found` / `session-conflict` / `kicked` / … 。
    pub reason: String,
    /// 对应 `message`：可为 `None`（Dart 的 `String?`）。
    pub message: Option<String>,
}

impl RelayFailure {
    /// 对应 `RelayFailure(this.reason, [this.message])`。
    pub fn new(reason: impl Into<String>, message: Option<String>) -> Self {
        RelayFailure {
            reason: reason.into(),
            message,
        }
    }
}

/// 对应 Dart `RelayFailure.toString()`：`reason` 或 `'$reason: $message'`。
impl fmt::Display for RelayFailure {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match &self.message {
            None => write!(f, "{}", self.reason),
            Some(message) => write!(f, "{}: {message}", self.reason),
        }
    }
}

/// 对应 Dart `relayCloseReason(int code)`（网页端 `VC()` / `BC`）：
/// WebSocket 关闭码 → 原因码。未收录的码返回 `None`。
pub fn relay_close_reason(code: i32) -> Option<&'static str> {
    match code {
        4004 => Some("session-not-found"),
        4009 => Some("session-conflict"),
        4010 => Some("desktop-disconnected"),
        4011 => Some("session-expired"),
        4012 => Some("workspace-closed"),
        4013 => Some("invalid-mobile-connection"),
        _ => None,
    }
}

/// 3.16.1 网页/桌面的心跳间隔：5s ±1s（`src@55069` 的 `G=5e3`）。
pub const HEARTBEAT_INTERVAL_MS: i64 = 5_000;
/// 3.16.1 网页/桌面的心跳 ack 超时：15s（`src@55069` 的 `ho=15e3`）。
pub const HEARTBEAT_ACK_TIMEOUT_MS: i64 = 15_000;
/// 对应 `static const waitingTimeout = Duration(seconds: 30)`。
pub const WAITING_TIMEOUT_MS: i64 = 30_000;
/// 外层 WebSocket 文本帧的 UTF-8 字节上限（`validation@148178`：1 MiB）。
pub const MAX_PHYSICAL_FRAME_BYTES: usize = 1024 * 1024;
/// 对应 `static const _deadLinkThreshold = Duration(seconds: 25)`。
///
/// PORT-NOTE: Dart 里它是私有常量（`_deadLinkThreshold`），只有 `poke()` 用；这里公开
/// 是为了让宿主也能复用同一个阈值，语义不变。
pub const DEAD_LINK_THRESHOLD_MS: i64 = 25_000;
/// 对应 `sendPayload` 里写死的字面量 `if (_outboundQueue.length < 100)`。
pub const MAX_OUTBOUND_QUEUE: usize = 100;

/// 宿主定时器槽位。对应 Dart 的 4 个 `Timer?` 字段。
///
/// 每个槽位同一时刻只允许一个定时器（`TimerStart` 隐含先取消同槽位的旧定时器，对应
/// Dart 的 `_heartbeatTimer?.cancel(); _heartbeatTimer = Timer(...)`）。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum RelayTimer {
    /// 对应 `_heartbeatTimer`：`Timer.periodic(heartbeatInterval, ...)`，**周期**定时器。
    Heartbeat,
    /// 对应 `_waitingTimer`：`Timer(waitingTimeout, ...)`，单次。
    Waiting,
    /// 对应 `_reconnectTimer`：`Timer(delayMs, ...)`，单次。
    Reconnect,
    /// 对应 `_rewaitTimer`：`Timer(reconnectWaitTimeout, ...)`，单次。
    Rewait,
}

/// [`RelayClient::step`] 的输出：宿主需要执行的定时器动作 + 需要向外转发的通知。
///
/// 对应 Dart 侧的四条输出通道（`onLog` / `stateListenable` / `payloads` / `failures`），
/// 外加两个定时器指令。**发帧与关连接不在这里**——它们直接走 [`RelayTransport`]，
/// 与 Dart 的 `_send` / `sink.close()` 一样是同步副作用。
#[derive(Debug, Clone, PartialEq)]
pub enum RelayEvent {
    /// 对应 `_setState` 里的 `_state.value = s`（宿主据此更新 UI）。
    StateChanged(RelayState),
    /// 对应 `onLog?.call(line)`。
    Log(String),
    /// 对应 `_payloadController.add(payload)`（`payloads` 流）。
    Payload(Map<String, Value>),
    /// 对应 `_failureController.add(...)`（`failures` 流）。
    Failure(RelayFailure),
    /// 启动一个宿主定时器。`repeat` 为 `true` 表示周期定时器（宿主需自行重排）。
    TimerStart {
        /// 槽位。
        timer: RelayTimer,
        /// 首次触发的延迟（毫秒），对应 Dart `Duration(milliseconds: delayMs)`。
        delay_ms: i64,
        /// 是否周期触发（只有 [`RelayTimer::Heartbeat`] 为 `true`）。
        repeat: bool,
    },
    /// 取消槽位上的定时器。对应 Dart 的 `timer?.cancel()`：**幂等**，槽位为空时是 no-op，
    /// 因此本模块不做「是否启动过」的判断，直接发取消（宿主按幂等实现即可）。
    TimerCancel(RelayTimer),
}

/// 宿主的 WebSocket 能力（本 crate 的 I/O 边界）。
///
/// 对应 Dart 里 `WebSocketChannel` 用到的三件事：建连、写文本帧、关连接。**实现留给宿主**
/// （Android 侧 OkHttp / Ktor，桌面侧任意）。
///
/// 代次（`generation`）即 Dart 的 `_socketGeneration`，等价于 socket 句柄：
/// - [`RelayTransport::connect`] 时宿主记下 `generation`；
/// - 之后回报 [`RelayInput::SocketReady`] / [`RelayInput::SocketFailed`]、
///   投递帧（[`RelayInput::TextFrame`] / [`RelayInput::BinaryFrame`]）、
///   回报关闭（[`RelayInput::SocketClosed`]）都要带上同一个 `generation`；
/// - 收到 [`RelayTransport::close`] 时按 `generation` 找到那条连接并关闭；
/// - **收到 `connect` 之后，宿主必须停止向本客户端投递旧代次的帧**（对应 Dart
///   `_connect()` 里的 `await _socketSub?.cancel()`）。本模块另有代次校验兜底，但宿主
///   仍应真正退订，否则旧 socket 会一直占用资源。
///
/// 每个 `generation` 只回报一次 `SocketReady` / `SocketFailed`（Dart 侧是
/// `await socket.ready` 只 resolve 一次）。
pub trait RelayTransport {
    /// 建立连接。对应 Dart `WebSocketChannel.connect(uri)` + `await socket.ready`。
    ///
    /// 本调用**不阻塞**：宿主在后台建连，成功/失败后分别回
    /// [`RelayInput::SocketReady`] / [`RelayInput::SocketFailed`]。
    fn connect(&mut self, generation: u64, uri: &str);

    /// 发送一个文本帧。对应 Dart `socket.sink.add(jsonEncode(frame))`。
    ///
    /// `text` 是完整的 JSON 文本（已序列化，紧凑格式、无空格）。
    fn send_text(&mut self, generation: u64, text: &str);

    /// 关闭连接。对应 Dart `socket.sink.close([code[, reason]])`。
    ///
    /// `code` / `reason` 为 `None` 表示 Dart 的无参调用形态（`sink.close()`，按正常关闭处理）。
    /// 参考 `debugDropSocket` 的 `close(3000, 'debug-drop')`。
    fn close(&mut self, generation: u64, code: Option<i32>, reason: Option<&str>);
}

/// [`RelayClient::step`] 的输入：宿主喂回来的事件。
#[derive(Debug, Clone, PartialEq)]
pub enum RelayInput<'a> {
    /// 对应 `start()`。
    Start,
    /// 建连成功（`await socket.ready` 通过）。
    SocketReady {
        /// 发起本次连接的代次（来自 [`RelayTransport::connect`]）。
        generation: u64,
    },
    /// 建连失败（`WebSocketChannel.connect` / `await socket.ready` 抛异常）。
    SocketFailed {
        /// 发起本次连接的代次。
        generation: u64,
        /// 对应 Dart `e.toString()` 的文本。
        error: String,
    },
    /// 收到一个文本帧。对应 `_handleRawMessage(String, ...)`。
    TextFrame {
        /// 该帧所属连接的代次。
        generation: u64,
        /// 原始 JSON 文本。
        text: &'a str,
    },
    /// 收到一个二进制帧。对应 `_handleRawMessage(List<int>, ...)` 里的 `utf8.decode`。
    BinaryFrame {
        /// 该帧所属连接的代次。
        generation: u64,
        /// 原始字节（宿主不要预先解码）。
        bytes: &'a [u8],
    },
    /// 连接关闭。对应 `onDone` 回调 `_handleSocketClosed(code, reason, ...)`。
    SocketClosed {
        /// 该连接建立时的代次。
        generation: u64,
        /// 对应 `socket.closeCode ?? 1006`。
        code: i32,
        /// 对应 `socket.closeReason`。
        reason: Option<String>,
    },
    /// 某个宿主定时器到点。对应 Dart 的 `Timer` / `Timer.periodic` 回调体。
    TimerFired(RelayTimer),
    /// 上行数据。对应 `sendPayload(Map<String, dynamic>)`。
    SendPayload(Map<String, Value>),
    /// App 从后台回前台时的探活。对应 `poke()`。
    Poke,
    /// 诊断用：强制掉线以演练重连/桥恢复路径。对应 `debugDropSocket()`。
    DebugDropSocket,
    /// 退出。对应 `dispose()`。
    Dispose,
}

/// 对应 Dart `RelayClient`（网页端 `pen` 类）。
///
/// 纯状态机：所有外部交互都通过 [`RelayClient::step`] 的输入/输出完成。
pub struct RelayClient {
    /// 对应 `final ZemoteConnectionParams params`。
    params: ZemoteConnectionParams,
    /// 对应 `auth_init.meta` 的内联 map（`params.appVersion ?? 'web'` 的回落已在此应用）。
    device_info: DeviceInfo,

    /// 对应 `_state`（`ValueNotifier<RelayState>` 的值）。
    state: RelayState,
    /// 对应 `_wasPaired`。
    was_paired: bool,
    /// 对应网页/桌面的 `authenticated`：收到合法 `auth_ack` 后置真。
    authenticated: bool,
    /// 对应 `_intentionallyClosed`。
    intentionally_closed: bool,
    /// 对应 `_disposed`。
    disposed: bool,
    /// 对应 `_reconnectAttempt`。
    reconnect_attempt: i32,
    /// 对应 `_heartbeatTick`。
    heartbeat_tick: u64,
    /// 对应 `_kickRecoveryAttempted`。
    kick_recovery_attempted: bool,
    /// 对应 `_lastInboundAt`（Unix 毫秒）。
    last_inbound_at: i64,
    /// 对应 `_lastPairStatusAckAt`（Unix 毫秒）。
    last_pair_status_ack_at: i64,

    /// 对应 `_socketGeneration`（同时充当已接受连接的句柄，见 [`RelayTransport`]）。
    socket_generation: u64,
    /// 对应 `_socket`：`Some(generation)` 表示 `_socket != null`。
    socket: Option<u64>,
    /// 对应 `_connectInFlight`。
    connect_in_flight: bool,

    /// 对应 `_outboundQueue`。
    outbound_queue: Vec<Map<String, Value>>,

    /// 对应 `static bool verboseFrames`。
    verbose_frames: bool,
}

impl RelayClient {
    /// 构造。对应 Dart 的字段初始化（`RelayClient(this.params, {this.onLog})` 加上
    /// `_lastInboundAt = DateTime.now()` / `_lastPairStatusAckAt = DateTime.now()`）。
    ///
    /// - `platform`：宿主持有的运行时平台名，由 [`crate::device_info::zemote_platform_name`]
    ///   算出（宿主提供 Flutter 的 `kIsWeb` / `defaultTargetPlatform`），对应 Dart 的
    ///   `zemotePlatformName()`。
    /// - `now_ms`：Unix 毫秒，充当 Dart 那两个 `DateTime.now()` 字段初始值。
    ///
    /// `auth_init.meta` 的三个字段在此定型：`platform` 取入参，`version` 取
    /// `params.app_version`（`None` 回落 `"web"`，对应 `params.appVersion ?? 'web'`），
    /// `name` 恒为 [`ZEMOTE_APP_NAME`]。三者复用 [`DeviceInfo`]，不再重复定义形状。
    ///
    /// PORT-NOTE: `verboseFrames` 在 Dart 里是 `static`（进程级全局），本模块落成
    /// **实例字段**（默认 `false`）。宿主把设置项读出来后调 [`RelayClient::set_verbose_frames`]，
    /// 效果等价；若宿主需要「全局开关」语义，在构造前保存该值并注入即可。
    pub fn new(params: ZemoteConnectionParams, platform: String, now_ms: i64) -> Self {
        let device_info = DeviceInfo {
            platform,
            version: params
                .app_version
                .clone()
                .unwrap_or_else(|| "web".to_string()),
            name: ZEMOTE_APP_NAME.to_string(),
        };
        RelayClient {
            params,
            device_info,
            state: RelayState::Idle,
            was_paired: false,
            authenticated: false,
            intentionally_closed: false,
            disposed: false,
            reconnect_attempt: 0,
            heartbeat_tick: 0,
            kick_recovery_attempted: false,
            last_inbound_at: now_ms,
            last_pair_status_ack_at: now_ms,
            socket_generation: 0,
            socket: None,
            connect_in_flight: false,
            outbound_queue: Vec::new(),
            verbose_frames: false,
        }
    }

    /// 对应 Dart `state` getter（`_state.value`）。
    pub fn state(&self) -> RelayState {
        self.state
    }

    /// 对应 Dart `_wasPaired`（`pair_status == matched` 后置真，此后不复位）。
    ///
    /// 宿主（FFI 层）的快照需要它，所以给出只读访问器——否则只能靠"观察事件流"去推导，
    /// 那种近似值不该出现在状态快照里。
    pub fn was_paired(&self) -> bool {
        self.was_paired
    }

    /// 对应 Dart `params` 字段。
    pub fn params(&self) -> &ZemoteConnectionParams {
        &self.params
    }

    /// `auth_init.meta` 的定型结果（对应 `relay_client.dart:163-168` 的内联 map）。
    pub fn device_info(&self) -> &DeviceInfo {
        &self.device_info
    }

    /// 对应 `static bool verboseFrames`。
    pub fn verbose_frames(&self) -> bool {
        self.verbose_frames
    }

    /// 是否走 3.16.1+ 的 OAuth token 鉴权路径。
    ///
    /// token 模式：WS URL 带 `?token=`，握手只发 `auth_init`（无 `role`），
    /// `client_ts` 用秒；legacy 模式仍走 `auth_challenge` + HMAC proof，`client_ts` 用毫秒。
    fn token_mode(&self) -> bool {
        self.params.auth_token.is_some()
    }

    /// 线上 `client_ts`：token 模式为 Unix 秒（网页 3.16.1 的 `Math.floor(Date.now()/1e3)`），
    /// legacy 模式为 Unix 毫秒（旧客户端行为）。
    fn wire_client_ts(&self, now_ms: i64) -> i64 {
        if self.token_mode() {
            now_ms / 1000
        } else {
            now_ms
        }
    }

    /// 对应 `RelayClient.verboseFrames = value`（`main.dart` / 设置页）。
    pub fn set_verbose_frames(&mut self, value: bool) {
        self.verbose_frames = value;
    }

    /// 状态机推进一步，返回本次需要宿主执行/转发的全部事件。
    ///
    /// `now_ms` 是本次调用的「现在」（Unix 毫秒），对应 Dart 各处的 `DateTime.now()`；
    /// 本模块内部不取时钟。一次 `step` 可能产生多个事件（例如一次 `matched` 会先
    /// `StateChanged(Paired)`，再 `TimerStart(Heartbeat)`，再若干 `Log`，并顺带通过
    /// `transport.send_text` 冲刷队列里的 `data` 帧），宿主按顺序处理即可。
    pub fn step(
        &mut self,
        input: RelayInput<'_>,
        transport: &mut dyn RelayTransport,
        now_ms: i64,
    ) -> Vec<RelayEvent> {
        let mut out = Vec::new();
        match input {
            RelayInput::Start => self.start(transport, now_ms, &mut out),
            RelayInput::SocketReady { generation } => {
                self.on_socket_ready(generation, transport, now_ms, &mut out)
            }
            RelayInput::SocketFailed { generation, error } => {
                self.on_socket_failed(generation, &error, &mut out)
            }
            RelayInput::TextFrame { generation, text } => {
                self.on_frame(generation, FrameData::Text(text), transport, now_ms, &mut out)
            }
            RelayInput::BinaryFrame { generation, bytes } => {
                self.on_frame(generation, FrameData::Binary(bytes), transport, now_ms, &mut out)
            }
            RelayInput::SocketClosed {
                generation,
                code,
                reason,
            } => self.handle_socket_closed(code, reason, Some(generation), &mut out),
            RelayInput::TimerFired(timer) => self.on_timer(timer, transport, now_ms, &mut out),
            RelayInput::SendPayload(payload) => {
                self.send_payload(payload, transport, now_ms, &mut out)
            }
            RelayInput::Poke => self.poke(transport, now_ms, &mut out),
            RelayInput::DebugDropSocket => self.debug_drop_socket(transport, &mut out),
            RelayInput::Dispose => self.dispose(transport, &mut out),
        }
        out
    }

    // ------------------------------------------------------------------
    // 状态迁移与发送原语
    // ------------------------------------------------------------------

    /// 对应 `_setState(RelayState s)`：先改值（通知监听者），再记日志。
    fn set_state(&mut self, s: RelayState, out: &mut Vec<RelayEvent>) {
        // Dart 的 `ValueNotifier` 只在值变化时 notify；同值重复 set 不能发事件，
        // 否则心跳每次 ack 都会把 `paired` 重放给宿主，触发无意义的重拉/重订阅。
        if self.state == s {
            return;
        }
        self.state = s;
        out.push(RelayEvent::StateChanged(s));
        out.push(RelayEvent::Log(format!("[relay] state -> {s}")));
    }

    /// 对应 `_send(Map<String, dynamic> frame)`。
    ///
    /// `_socket == null` 时直接丢弃（Dart 是 `if (socket == null) return;`）。
    fn send(&mut self, frame: Value, transport: &mut dyn RelayTransport, out: &mut Vec<RelayEvent>) {
        let Some(generation) = self.socket else {
            return;
        };
        // PORT-NOTE: Dart 的 `jsonEncode` 不会失败；`serde_json::to_string` 对
        // `serde_json::Value`（不含 NaN/Infinity）同样不可失败，故此处不返回 `Result`。
        // PORT-NOTE: 键序差异——Dart 的 `Map` 保留插入顺序，`serde_json::Value` 默认
        // `BTreeMap`（字典序）。JSON 对象无序，中继端按字段取值，且 proof 只覆盖
        // `nonce|role|deviceSid` 而非原始 JSON 文本，故线上语义不受影响；若要字节级一致，
        // 需启用 serde_json 的 `preserve_order`（改 `Cargo.toml`，由主线定夺）。
        let text = serde_json::to_string(&frame).expect("serde_json::Value 序列化不可失败");
        if self.verbose_frames {
            out.push(RelayEvent::Log(format!("[relay] >> {text}")));
        }
        transport.send_text(generation, &text);
    }

    // ------------------------------------------------------------------
    // start / connect
    // ------------------------------------------------------------------

    /// 对应 `Future<void> start()`。
    fn start(&mut self, transport: &mut dyn RelayTransport, now_ms: i64, out: &mut Vec<RelayEvent>) {
        self.disposed = false;
        self.intentionally_closed = false;
        self.reconnect_attempt = 0;
        self.kick_recovery_attempted = false;
        self.set_state(RelayState::Connecting, out);
        // PORT-NOTE: Dart 的 `start()` **不**重置 `_connectInFlight`，本实现照抄——
        // 于是「上一次 connect 仍在飞」时 `start()` 会静默什么都不做。属可疑但既有行为。
        self.do_connect(transport, now_ms, out);
    }

    /// 对应 `Future<void> _connect()` 中「发起连接」的前半段（到 `WebSocketChannel.connect` 为止）。
    ///
    /// `await socket.ready` 之后的收尾在 [`RelayClient::on_socket_ready`] /
    /// [`RelayClient::on_socket_failed`]。
    fn do_connect(
        &mut self,
        transport: &mut dyn RelayTransport,
        now_ms: i64,
        out: &mut Vec<RelayEvent>,
    ) {
        if self.connect_in_flight || self.disposed {
            return;
        }
        self.connect_in_flight = true;
        self.socket_generation += 1;
        let generation = self.socket_generation;
        // 对应 `await _socketSub?.cancel()`：宿主契约见 [`RelayTransport`]（收到 connect
        // 后停止投递旧代次的帧）。这里只负责把旧 socket 关掉。
        if let Some(old) = self.socket.take() {
            transport.close(old, None, None);
        }
        self.last_pair_status_ack_at = now_ms;
        self.last_inbound_at = now_ms;
        self.authenticated = false;
        let uri = self.params.relay_ws_uri();
        out.push(RelayEvent::Log(format!(
            "[relay] connecting {}",
            safe_uri_for_log(&uri)
        )));
        transport.connect(generation, &uri.to_string());
    }

    /// 对应 `_connect()` 里 `await socket.ready` 成功之后的收尾 + `auth_init`。
    fn on_socket_ready(
        &mut self,
        generation: u64,
        transport: &mut dyn RelayTransport,
        now_ms: i64,
        out: &mut Vec<RelayEvent>,
    ) {
        if self.disposed {
            transport.close(generation, None, None);
            self.connect_in_flight = false;
            return;
        }
        if generation != self.socket_generation {
            transport.close(generation, None, None);
            self.connect_in_flight = false;
            return;
        }
        self.socket = Some(generation);
        self.connect_in_flight = false;
        self.set_state(RelayState::Authenticating, out);
        // 3.16.1 的 token 模式：没有 `role`，`client_ts` 为秒。
        // legacy 模式保持旧形状（`role: "terminal"`，毫秒），服务端仍可走 HMAC 挑战。
        let init = if self.token_mode() {
            json!({
                "type": "auth_init",
                "device_sid": self.params.device_sid.clone(),
                "meta": self.device_info.clone(),
                "client_ts": self.wire_client_ts(now_ms),
            })
        } else {
            json!({
                "type": "auth_init",
                "role": "terminal",
                "device_sid": self.params.device_sid.clone(),
                "meta": self.device_info.clone(),
                "client_ts": self.wire_client_ts(now_ms),
            })
        };
        self.send(init, transport, out);
    }

    /// 对应 `_connect()` 里 `catch (e)` 分支：`_handleSocketClosed(1006, e.toString(), ...)`。
    fn on_socket_failed(&mut self, generation: u64, error: &str, out: &mut Vec<RelayEvent>) {
        self.connect_in_flight = false;
        out.push(RelayEvent::Log(format!("[relay] connect failed: {error}")));
        if generation == self.socket_generation {
            self.handle_socket_closed(1006, Some(error.to_string()), Some(generation), out);
        }
    }

    // ------------------------------------------------------------------
    // 收帧
    // ------------------------------------------------------------------

    /// 对应 `_handleRawMessage(dynamic data, {required int generation})`。
    fn on_frame(
        &mut self,
        generation: u64,
        data: FrameData<'_>,
        transport: &mut dyn RelayTransport,
        now_ms: i64,
        out: &mut Vec<RelayEvent>,
    ) {
        if generation != self.socket_generation || self.disposed {
            return;
        }
        self.last_inbound_at = now_ms;
        // 3.16.1 网页/桌面都先量 UTF-8 字节数再解析（1 MiB）；超限直接丢弃。
        let frame_len = match &data {
            FrameData::Text(text) => text.len(),
            FrameData::Binary(bytes) => bytes.len(),
        };
        if frame_len > MAX_PHYSICAL_FRAME_BYTES {
            out.push(RelayEvent::Log(format!(
                "[relay] frame too large: {frame_len} bytes (max {MAX_PHYSICAL_FRAME_BYTES})"
            )));
            return;
        }
        // Dart：`data is String ? data : utf8.decode(data as List<int>)`，解码失败被
        // 同一个 try/catch 吞掉并记 `bad frame`。
        let text = match data {
            FrameData::Text(text) => text.to_string(),
            FrameData::Binary(bytes) => match std::str::from_utf8(bytes) {
                Ok(text) => text.to_string(),
                Err(e) => {
                    out.push(RelayEvent::Log(format!("[relay] bad frame: {e}")));
                    return;
                }
            },
        };
        if self.verbose_frames {
            out.push(RelayEvent::Log(format!("[relay] << {text}")));
        }
        // PORT-NOTE: 解析失败时 Dart 记的是 `FormatException` 的文本，这里记 serde 的
        // 错误文本——两者都只是诊断信息，行为（丢弃该帧）一致。
        let decoded: Value = match serde_json::from_str(&text) {
            Ok(value) => value,
            Err(e) => {
                out.push(RelayEvent::Log(format!("[relay] bad frame: {e}")));
                return;
            }
        };
        // Dart：`decoded is Map<String, dynamic> && decoded.containsKey('type')`，
        // 否则 `frame` 保持 null → 直接 return（**不记日志**）。
        let frame = match decoded {
            Value::Object(map) if map.contains_key("type") => map,
            _ => return,
        };
        // `switch (frame['type'])`：非字符串的 type 不命中任何 case。
        match frame.get("type").and_then(|value| value.as_str()) {
            Some("auth_challenge") => {
                // token 模式（3.16.1+）不需要 HMAC proof；服务端不应再发挑战。
                // 若仍收到，且本地没有旧凭据，只能忽略——继续重试同样的 token 也不会成功。
                let Some(pass_hash) = self.params.pass_hash.as_deref() else {
                    out.push(RelayEvent::Log(
                        "[relay] auth_challenge without passHash (token mode), ignored"
                            .to_string(),
                    ));
                    return;
                };
                // PORT-NOTE: `frame['nonce'] as String? ?? ''` 这个**回退写在调用点**
                // （`relay_client.dart:247`，不在 `proof.dart`），此处必须原样带上：
                // 缺 `nonce` 键 / `nonce: null` → 空串。
                // PORT-NOTE: 若 `nonce` 是**非字符串非 null**（如数字），Dart 的
                // `as String?` 会抛 `TypeError`——而该语句在 try/catch **之外**，异常会
                // 冒泡成未处理的异步错误。本实现不制造 panic，按「取不到字符串」处理成
                // 空串（更稳健，且与缺失键同路径）。属有意的行为收紧。
                let nonce = frame
                    .get("nonce")
                    .and_then(|value| value.as_str())
                    .unwrap_or("");
                let proof = calculate_proof(
                    pass_hash,
                    nonce,
                    "terminal",
                    &self.params.device_sid,
                );
                let response = json!({
                    "type": "auth_response",
                    "device_sid": self.params.device_sid.clone(),
                    "proof": proof,
                    "client_ts": self.wire_client_ts(now_ms),
                });
                self.send(response, transport, out);
            }
            Some("auth_ack") => {
                // 3.16.1：只有 authenticating 态、且 ack.device_sid 等于本地 sid 才接受。
                if self.state != RelayState::Authenticating {
                    return;
                }
                let ack_sid = frame.get("device_sid").and_then(|value| value.as_str());
                if ack_sid != Some(self.params.device_sid.as_str()) {
                    return;
                }
                self.authenticated = true;
                self.reconnect_attempt = 0;
                let status = frame
                    .get("pair_status")
                    .and_then(|value| value.as_str())
                    .map(|s| s.to_string());
                self.apply_pair_status(status.as_deref(), transport, now_ms, out);
            }
            Some("pair_status_ack") => {
                // 必须先通过 auth_ack，否则整帧丢弃。
                if !self.authenticated {
                    return;
                }
                let status = frame
                    .get("pair_status")
                    .and_then(|value| value.as_str())
                    .map(|s| s.to_string());
                self.apply_pair_status(status.as_deref(), transport, now_ms, out);
            }
            Some("data") => {
                // 3.16.1：仅 authenticated && paired 才把 payload 上抛。
                if !self.authenticated || self.state != RelayState::Paired {
                    return;
                }
                // Dart：`payload is Map<String, dynamic>` → 对象才转发，数组/标量丢弃。
                if let Some(Value::Object(payload)) = frame.get("payload") {
                    out.push(RelayEvent::Payload(payload.clone()));
                }
            }
            Some("error") => {
                let code = frame
                    .get("code")
                    .and_then(|value| value.as_str())
                    .map(|s| s.to_string());
                let message = frame
                    .get("message")
                    .and_then(|value| value.as_str())
                    .map(|s| s.to_string());
                self.handle_relay_error(code.as_deref(), message, transport, now_ms, out);
            }
            _ => {}
        }
    }

    // ------------------------------------------------------------------
    // 配对状态
    // ------------------------------------------------------------------

    /// 对应 `_applyPairStatus(String? status)`。
    fn apply_pair_status(
        &mut self,
        status: Option<&str>,
        transport: &mut dyn RelayTransport,
        now_ms: i64,
        out: &mut Vec<RelayEvent>,
    ) {
        self.last_pair_status_ack_at = now_ms;
        if status == Some("waiting") {
            if self.was_paired {
                self.clear_waiting_timer(out);
                self.set_state(RelayState::Waiting, out);
                self.start_heartbeat(out);
            } else {
                self.set_state(RelayState::Waiting, out);
                self.start_waiting_timer(out);
            }
            return;
        }
        if status == Some("matched") {
            out.push(RelayEvent::TimerCancel(RelayTimer::Rewait));
            self.reconnect_attempt = 0;
            self.kick_recovery_attempted = false;
            self.clear_waiting_timer(out);
            self.set_state(RelayState::Paired, out);
            self.was_paired = true;
            self.start_heartbeat(out);
            self.flush_outbound_queue(transport, now_ms, out);
        }
    }

    /// 对应 `_handleRelayError(String? code, String? message)`。
    fn handle_relay_error(
        &mut self,
        code: Option<&str>,
        message: Option<String>,
        transport: &mut dyn RelayTransport,
        now_ms: i64,
        out: &mut Vec<RelayEvent>,
    ) {
        out.push(RelayEvent::Log(format!(
            "[relay] error frame: {} {}",
            dart_opt_str(code),
            dart_opt_str(message.as_deref())
        )));

        // 3.16.1：只有 AUTH_FAILED / WRONG_PARAM 是终态；INTERNAL 可恢复。
        if matches!(code, Some("AUTH_FAILED") | Some("WRONG_PARAM")) {
            let reason = match code {
                Some("AUTH_FAILED") => "device-not-owned",
                Some("WRONG_PARAM") => "invalid-mobile-connection",
                _ => unreachable!(),
            };
            self.set_state(RelayState::Error, out);
            self.intentionally_closed = true;
            out.push(RelayEvent::Failure(RelayFailure::new(reason, message)));
            if let Some(generation) = self.socket {
                transport.close(generation, None, None);
            }
            return;
        }

        if code == Some("INTERNAL") {
            out.push(RelayEvent::Failure(RelayFailure::new(
                "relay-internal",
                message,
            )));
            if self.state == RelayState::Paired
                || (self.was_paired && self.state == RelayState::Waiting)
            {
                self.set_state(RelayState::Waiting, out);
                self.start_heartbeat(out);
            } else {
                self.reconnect(transport, now_ms, out);
            }
            return;
        }

        if code == Some("KICKED") {
            let detail = message.clone().unwrap_or_default().to_lowercase();
            let transient = detail.contains("conflict")
                || detail.contains("duplicate")
                || detail.contains("already connected")
                || detail.contains("another connection");
            if !self.kick_recovery_attempted
                && (self.state == RelayState::Authenticating || transient)
            {
                self.kick_recovery_attempted = true;
                out.push(RelayEvent::Failure(RelayFailure::new(
                    "session-conflict",
                    message,
                )));
                self.reconnect(transport, now_ms, out);
                return;
            }
            self.set_state(RelayState::Kicked, out);
            self.intentionally_closed = true;
            out.push(RelayEvent::Failure(RelayFailure::new("kicked", message)));
            if let Some(generation) = self.socket {
                transport.close(generation, None, None);
            }
            return;
        }

        // 其余（`DEVICE_OFFLINE` / 未知码）：上报给界面，并保留重连。
        let reason = code.unwrap_or("relay-error").to_lowercase();
        out.push(RelayEvent::Failure(RelayFailure::new(&reason, message)));
        if code == Some("DEVICE_OFFLINE") {
            self.reconnect(transport, now_ms, out);
        }
    }

    /// 对应 `_handleSocketClosed(int code, String? reason, {int? generation})`。
    fn handle_socket_closed(
        &mut self,
        code: i32,
        reason: Option<String>,
        generation: Option<u64>,
        out: &mut Vec<RelayEvent>,
    ) {
        if self.disposed {
            return;
        }
        if let Some(generation) = generation {
            if generation != self.socket_generation {
                return;
            }
        }
        self.stop_heartbeat(out);
        self.clear_waiting_timer(out);
        let mapped = relay_close_reason(code);
        out.push(RelayEvent::Log(format!(
            "[relay] closed code={code} reason={} mapped={}",
            dart_opt_str(reason.as_deref()),
            dart_opt_str(mapped)
        )));
        self.authenticated = false;
        if self.intentionally_closed {
            return;
        }
        // 3.16.1 网页/桌面完全不解析关闭码：只写诊断，然后无条件重连。
        // `mapped` 仅用于日志，不再决定终态。
        self.schedule_reconnect(out);
    }

    // ------------------------------------------------------------------
    // 心跳 / 定时器
    // ------------------------------------------------------------------

    /// 对应 `_startHeartbeat()`。
    fn start_heartbeat(&mut self, out: &mut Vec<RelayEvent>) {
        out.push(RelayEvent::TimerCancel(RelayTimer::Heartbeat));
        out.push(RelayEvent::TimerStart {
            timer: RelayTimer::Heartbeat,
            delay_ms: HEARTBEAT_INTERVAL_MS,
            repeat: true,
        });
    }

    /// 对应 `_stopHeartbeat()`。
    fn stop_heartbeat(&mut self, out: &mut Vec<RelayEvent>) {
        out.push(RelayEvent::TimerCancel(RelayTimer::Heartbeat));
    }

    /// 对应 `_startWaitingTimer()`。
    fn start_waiting_timer(&mut self, out: &mut Vec<RelayEvent>) {
        self.clear_waiting_timer(out);
        out.push(RelayEvent::TimerStart {
            timer: RelayTimer::Waiting,
            delay_ms: WAITING_TIMEOUT_MS,
            repeat: false,
        });
    }

    /// 对应 `_clearWaitingTimer()`：**同时**清掉 `_waitingTimer` 与 `_rewaitTimer`。
    fn clear_waiting_timer(&mut self, out: &mut Vec<RelayEvent>) {
        out.push(RelayEvent::TimerCancel(RelayTimer::Waiting));
        out.push(RelayEvent::TimerCancel(RelayTimer::Rewait));
    }

    /// 对应宿主定时器回调体的分发（Dart 的 4 个 `Timer` 回调）。
    fn on_timer(
        &mut self,
        timer: RelayTimer,
        transport: &mut dyn RelayTransport,
        now_ms: i64,
        out: &mut Vec<RelayEvent>,
    ) {
        match timer {
            RelayTimer::Heartbeat => self.heartbeat_tick(transport, now_ms, out),
            RelayTimer::Waiting => {
                // 对应 `_startWaitingTimer()` 的回调体。
                if self.state == RelayState::Waiting && !self.was_paired {
                    self.set_state(RelayState::Error, out);
                    out.push(RelayEvent::Failure(RelayFailure::new(
                        "desktop-disconnected",
                        Some(
                            "Desktop did not match this mobile connection before the waiting timeout."
                                .to_string(),
                        ),
                    )));
                }
            }
            RelayTimer::Reconnect => {
                // 对应 `_scheduleReconnect()` 的回调体 `if (!_disposed) _connect();`。
                if !self.disposed {
                    self.do_connect(transport, now_ms, out);
                }
            }
            RelayTimer::Rewait => {
                // 对应 `_applyPairStatus` 里 rewait 定时器的回调体。
                if self.was_paired && self.state == RelayState::Waiting && !self.disposed {
                    out.push(RelayEvent::Log(
                        "[relay] re-pair stuck in waiting, reconnecting".to_string(),
                    ));
                    self.reconnect(transport, now_ms, out);
                }
            }
        }
    }

    /// 对应 `_startHeartbeat()` 里 `Timer.periodic` 的回调体。
    fn heartbeat_tick(
        &mut self,
        transport: &mut dyn RelayTransport,
        now_ms: i64,
        out: &mut Vec<RelayEvent>,
    ) {
        if self.state != RelayState::Paired && self.state != RelayState::Waiting {
            return;
        }
        self.heartbeat_tick += 1;
        if now_ms - self.last_pair_status_ack_at > HEARTBEAT_ACK_TIMEOUT_MS {
            out.push(RelayEvent::Log(
                "[relay] heartbeat ack timeout, reconnecting".to_string(),
            ));
            self.reconnect(transport, now_ms, out);
            return;
        }
        let frame = json!({
            "type": "pair_status_query",
            "client_ts": self.wire_client_ts(now_ms),
        });
        self.send(frame, transport, out);
    }

    // ------------------------------------------------------------------
    // 上行 / 队列
    // ------------------------------------------------------------------

    /// 对应 `sendPayload(Map<String, dynamic> payload)`。
    ///
    /// 未配对（reconnecting / waiting）时入队，`matched` 后统一冲刷——否则重连期间发出的
    /// 请求会写进死 socket 而静默丢失，调用方一直挂到超时。
    fn send_payload(
        &mut self,
        payload: Map<String, Value>,
        transport: &mut dyn RelayTransport,
        now_ms: i64,
        out: &mut Vec<RelayEvent>,
    ) {
        if self.state != RelayState::Paired || self.socket.is_none() {
            if self.outbound_queue.len() < MAX_OUTBOUND_QUEUE {
                out.push(RelayEvent::Log(format!(
                    "[relay] queued (state={}): {}",
                    self.state,
                    dart_value_to_string(payload.get("zcode_type"))
                )));
                self.outbound_queue.push(payload);
            }
            return;
        }
        let frame = json!({
            "type": "data",
            "payload": payload,
            "client_ts": self.wire_client_ts(now_ms),
        });
        self.send(frame, transport, out);
    }

    /// 对应 `_flushOutboundQueue()`。
    fn flush_outbound_queue(
        &mut self,
        transport: &mut dyn RelayTransport,
        now_ms: i64,
        out: &mut Vec<RelayEvent>,
    ) {
        if self.outbound_queue.is_empty() {
            return;
        }
        out.push(RelayEvent::Log(format!(
            "[relay] flushing {} queued payload(s)",
            self.outbound_queue.len()
        )));
        let queued = std::mem::take(&mut self.outbound_queue);
        // PORT-NOTE: Dart 对每个 payload 各取一次 `DateTime.now()`；本实现共用同一次
        // `now_ms`（`step` 是同步的，两者差值在毫秒级，宿主可按需逐条细分）。
        for payload in queued {
            let frame = json!({
                "type": "data",
                "payload": payload,
                "client_ts": self.wire_client_ts(now_ms),
            });
            self.send(frame, transport, out);
        }
    }

    // ------------------------------------------------------------------
    // 探活 / 重连 / 退出
    // ------------------------------------------------------------------

    /// 对应 `poke()`：App 从后台回前台时立即探活；长时间没有入站帧则跳过退避重建 socket。
    fn poke(
        &mut self,
        transport: &mut dyn RelayTransport,
        now_ms: i64,
        out: &mut Vec<RelayEvent>,
    ) {
        if self.disposed || self.intentionally_closed {
            return;
        }
        if self.state == RelayState::Paired {
            if now_ms - self.last_inbound_at > DEAD_LINK_THRESHOLD_MS {
                out.push(RelayEvent::Log(
                    "[relay] poke: stale inbound link, reconnecting".to_string(),
                ));
                self.reconnect(transport, now_ms, out);
                return;
            }
            let frame = json!({
                "type": "pair_status_query",
                "client_ts": self.wire_client_ts(now_ms),
            });
            self.send(frame, transport, out);
        } else if self.state != RelayState::Idle
            && self.state != RelayState::Closed
            && self.state != RelayState::Kicked
        {
            // PORT-NOTE: 这里调的是 `_connect()` 而不是 `_reconnect()`——状态**不会**先切到
            // `reconnecting`（保持原状态）。照抄。
            // PORT-NOTE: `state != kicked` 这个条件实际不可达：`kicked` 只由
            // `_handleRelayError` 的终止分支设置，而那条分支同时把 `_intentionallyClosed`
            // 置真，`poke()` 开头就已返回。保留该判断以逐字对齐 Dart（属死分支）。
            out.push(RelayEvent::Log(format!(
                "[relay] poke: state={}, forcing reconnect",
                self.state
            )));
            out.push(RelayEvent::TimerCancel(RelayTimer::Reconnect));
            self.do_connect(transport, now_ms, out);
        }
    }

    /// 对应 `_scheduleReconnect()`：指数退避 1s/2s/4s/8s/15s（上限 15s）。
    fn schedule_reconnect(&mut self, out: &mut Vec<RelayEvent>) {
        if self.disposed || self.intentionally_closed {
            return;
        }
        self.set_state(RelayState::Reconnecting, out);
        // 对应 `(1000 * (1 << _reconnectAttempt.clamp(0, 4))).clamp(1000, 15000)`。
        let shift = self.reconnect_attempt.clamp(0, 4) as u32;
        let delay_ms = (1000_i64 * (1_i64 << shift)).clamp(1000, 15000);
        self.reconnect_attempt += 1;
        out.push(RelayEvent::Log(format!(
            "[relay] reconnect in {delay_ms}ms (attempt {})",
            self.reconnect_attempt
        )));
        out.push(RelayEvent::TimerCancel(RelayTimer::Reconnect));
        out.push(RelayEvent::TimerStart {
            timer: RelayTimer::Reconnect,
            delay_ms,
            repeat: false,
        });
    }

    /// 对应 `Future<void> _reconnect()`。
    fn reconnect(
        &mut self,
        transport: &mut dyn RelayTransport,
        now_ms: i64,
        out: &mut Vec<RelayEvent>,
    ) {
        if self.disposed || self.intentionally_closed || self.connect_in_flight {
            return;
        }
        out.push(RelayEvent::TimerCancel(RelayTimer::Reconnect));
        // 必须经过 `reconnecting`：监听者（桥恢复）要据此知道连接掉了——心跳超时那条路
        // 曾经跳过这一步，导致重新配对后桥一直没被恢复。
        self.set_state(RelayState::Reconnecting, out);
        self.do_connect(transport, now_ms, out);
    }

    /// 对应 `Future<void> debugDropSocket()`。
    fn debug_drop_socket(&mut self, transport: &mut dyn RelayTransport, out: &mut Vec<RelayEvent>) {
        self.intentionally_closed = false;
        // 对应 `await _socketSub?.cancel()`（宿主契约：停止投递旧代次帧）。
        if let Some(generation) = self.socket.take() {
            transport.close(generation, Some(3000), Some("debug-drop"));
        }
        // Dart 传的是当前代次（与刚丢弃的 socket 同代）。
        self.handle_socket_closed(
            1006,
            Some("debug-drop".to_string()),
            Some(self.socket_generation),
            out,
        );
    }

    /// 对应 `Future<void> dispose()`。
    fn dispose(&mut self, transport: &mut dyn RelayTransport, out: &mut Vec<RelayEvent>) {
        self.disposed = true;
        self.intentionally_closed = true;
        self.stop_heartbeat(out);
        self.clear_waiting_timer(out);
        out.push(RelayEvent::TimerCancel(RelayTimer::Reconnect));
        out.push(RelayEvent::TimerCancel(RelayTimer::Rewait));
        // PORT-NOTE: Dart 的 dispose 只 `_socket?.sink.close()`，**不**把 `_socket` 置 null
        // （`_socketSub` 也没置 null）。本实现照抄，句柄保留。
        if let Some(generation) = self.socket {
            transport.close(generation, None, None);
        }
        // 对应 `await _payloadController.close(); await _failureController.close();`：
        // 本模块无流对象，宿主收到 `Closed` 后自行关闭自己的通道。
        self.set_state(RelayState::Closed, out);
    }
}

/// 收帧的两种载体（对应 Dart `_handleRawMessage` 的 `dynamic data`）。
enum FrameData<'a> {
    /// 对应 `data is String`。
    Text(&'a str),
    /// 对应 `List<int>`（Dart 侧走 `utf8.decode`）。
    Binary(&'a [u8]),
}

/// 对应 `_safeUriForLog(Uri uri)`：把 query 里所有参数的值换成 `<redacted>`。
///
/// Dart 用 `uri.replace(queryParameters: {k: '<redacted>'})`，值会再经
/// `Uri.encodeQueryComponent` 转义，所以 `<redacted>` 在日志里是 `%3Credacted%3E`。
/// 这里复用 [`crate::connection_params::Uri`] 的 `with_query_parameters`（同一套转义规则）。
///
/// PORT-NOTE: token 模式下中继地址有多个 query 参数（`token` / `mid`），必须按**原始顺序**
/// 重写，否则日志里的参数顺序会随机（3.17.1 的 Dart `Map` 是插入序）。
/// PORT-NOTE: query 为空时 `with_query_parameters(&[])` 产出 `?`（Dart
/// `_makeQueryFromParametersDefault` 对空 map 返回 `''`，同样是 `?`）——该细节未能在
/// 本机验证（无 Dart 运行时），但它只影响日志字符串，不影响线上格式。
fn safe_uri_for_log(uri: &Uri) -> String {
    let pairs = uri.query_parameter_pairs();
    let parameters: Vec<(&str, &str)> = pairs
        .iter()
        .map(|(key, _)| (key.as_str(), "<redacted>"))
        .collect();
    uri.with_query_parameters(&parameters).to_string()
}

/// 对应 Dart 字符串插值里 `$x`（`x` 为 `String?`）的渲染：`null` → `"null"`。
fn dart_opt_str(value: Option<&str>) -> String {
    match value {
        Some(text) => text.to_string(),
        None => "null".to_string(),
    }
}

/// 对应 Dart 字符串插值 `$value`（`value` 为 `dynamic`）的渲染，用于
/// `'queued (state=$state): ${payload['zcode_type']}'`。
///
/// PORT-NOTE: Dart 的 `toString()` 对容器给的是 `{k: v}` / `[1, 2]` 这种调试形态，本实现
/// 退化成紧凑 JSON——只影响这一行诊断日志（`zcode_type` 实际恒为字符串）。
fn dart_value_to_string(value: Option<&Value>) -> String {
    match value {
        None | Some(Value::Null) => "null".to_string(),
        Some(Value::String(text)) => text.clone(),
        Some(Value::Bool(value)) => value.to_string(),
        Some(Value::Number(value)) => value.to_string(),
        Some(other) => other.to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    use crate::device_info::{zemote_platform_name, TargetPlatform};

    /// 与 `Reference_code/test/widget_test.dart` 的 `'parse zemote connection url'` 用例
    /// 同一份 URL（期望值直接搬）：`deviceSid='SID123'`、`passHash='HASH='`、
    /// `deviceMid='MID-1'`、`appVersion='3.6.5'`，`relayWsUri='wss://zcode.z.ai/ws?mid=MID-1'`。
    fn test_params() -> ZemoteConnectionParams {
        ZemoteConnectionParams::parse(
            "https://zcode.z.ai/remote/v4?sid=SID123&hash=HASH%3D&t=1785734607338\
             &mid=MID-1&name=DESKTOP&app_version=3.6.5",
        )
        .expect("test url should parse")
    }

    /// 3.16.1 新链接：无 `hash` / `t`，JWT 由宿主注入。
    fn token_params() -> ZemoteConnectionParams {
        let mut params = ZemoteConnectionParams::parse(
            "https://zcode.z.ai/remote/v4?app_version=3.16.1&sid=SID123&mid=MID-1&name=DESKTOP",
        )
        .expect("test url should parse");
        params.auth_token = Some("JWT".to_string());
        params
    }

    fn obj(value: Value) -> Map<String, Value> {
        value.as_object().expect("must be a JSON object").clone()
    }

    #[derive(Default)]
    struct MockTransport {
        connects: Vec<(u64, String)>,
        sends: Vec<(u64, String)>,
        closes: Vec<(u64, Option<i32>, Option<String>)>,
    }

    impl RelayTransport for MockTransport {
        fn connect(&mut self, generation: u64, uri: &str) {
            self.connects.push((generation, uri.to_string()));
        }
        fn send_text(&mut self, generation: u64, text: &str) {
            self.sends.push((generation, text.to_string()));
        }
        fn close(&mut self, generation: u64, code: Option<i32>, reason: Option<&str>) {
            self.closes
                .push((generation, code, reason.map(|r| r.to_string())));
        }
    }

    struct Harness {
        client: RelayClient,
        transport: MockTransport,
    }

    impl Harness {
        fn new(now_ms: i64) -> Self {
            Self::with_params(test_params(), now_ms)
        }

        fn with_params(params: ZemoteConnectionParams, now_ms: i64) -> Self {
            let platform = zemote_platform_name(false, TargetPlatform::Android);
            Harness {
                client: RelayClient::new(params, platform.to_string(), now_ms),
                transport: MockTransport::default(),
            }
        }

        fn step(&mut self, input: RelayInput<'_>, now_ms: i64) -> Vec<RelayEvent> {
            self.client.step(input, &mut self.transport, now_ms)
        }

        /// 最近一次 `connect` 的代次（即当前 socket 句柄）。
        fn generation(&self) -> u64 {
            self.transport.connects.last().expect("no connect issued").0
        }

        /// `Start` + `SocketReady`：走到 authenticating（并已发出 auth_init）。
        fn start(&mut self, now_ms: i64) -> Vec<RelayEvent> {
            let mut events = self.step(RelayInput::Start, now_ms);
            let generation = self.generation();
            events.extend(self.step(RelayInput::SocketReady { generation }, now_ms));
            events
        }

        /// `Start` + `SocketReady` + `matched`：走到 paired。
        fn pair(&mut self, now_ms: i64) -> Vec<RelayEvent> {
            self.start(now_ms);
            self.frame(
                r#"{"type":"auth_ack","device_sid":"SID123","pair_status":"matched"}"#,
                now_ms,
            )
        }

        /// 用当前代次投递一个文本帧。
        fn frame(&mut self, text: &str, now_ms: i64) -> Vec<RelayEvent> {
            let generation = self.generation();
            self.step(RelayInput::TextFrame { generation, text }, now_ms)
        }

        fn sent(&self) -> Vec<Value> {
            self.transport
                .sends
                .iter()
                .map(|(_, text)| serde_json::from_str(text).expect("sent text must be JSON"))
                .collect()
        }

        fn last_sent(&self) -> Value {
            self.sent().pop().expect("no frame sent yet")
        }
    }

    fn states(events: &[RelayEvent]) -> Vec<RelayState> {
        events
            .iter()
            .filter_map(|event| match event {
                RelayEvent::StateChanged(state) => Some(*state),
                _ => None,
            })
            .collect()
    }

    fn has_state(events: &[RelayEvent], state: RelayState) -> bool {
        states(events).contains(&state)
    }

    fn logs(events: &[RelayEvent]) -> Vec<String> {
        events
            .iter()
            .filter_map(|event| match event {
                RelayEvent::Log(line) => Some(line.clone()),
                _ => None,
            })
            .collect()
    }

    fn has_log(events: &[RelayEvent], needle: &str) -> bool {
        logs(events).iter().any(|line| line.contains(needle))
    }

    fn failures(events: &[RelayEvent]) -> Vec<RelayFailure> {
        events
            .iter()
            .filter_map(|event| match event {
                RelayEvent::Failure(failure) => Some(failure.clone()),
                _ => None,
            })
            .collect()
    }

    fn payloads(events: &[RelayEvent]) -> Vec<Map<String, Value>> {
        events
            .iter()
            .filter_map(|event| match event {
                RelayEvent::Payload(payload) => Some(payload.clone()),
                _ => None,
            })
            .collect()
    }

    fn timers_started(events: &[RelayEvent]) -> Vec<(RelayTimer, i64, bool)> {
        events
            .iter()
            .filter_map(|event| match event {
                RelayEvent::TimerStart {
                    timer,
                    delay_ms,
                    repeat,
                } => Some((*timer, *delay_ms, *repeat)),
                _ => None,
            })
            .collect()
    }

    fn timers_cancelled(events: &[RelayEvent]) -> Vec<RelayTimer> {
        events
            .iter()
            .filter_map(|event| match event {
                RelayEvent::TimerCancel(timer) => Some(*timer),
                _ => None,
            })
            .collect()
    }

    // ---- relayCloseReason（`relay_client.dart:33-50`，6 个 case 逐条） ----

    #[test]
    fn close_reason_maps_every_dart_case() {
        assert_eq!(relay_close_reason(4004), Some("session-not-found"));
        assert_eq!(relay_close_reason(4009), Some("session-conflict"));
        assert_eq!(relay_close_reason(4010), Some("desktop-disconnected"));
        assert_eq!(relay_close_reason(4011), Some("session-expired"));
        assert_eq!(relay_close_reason(4012), Some("workspace-closed"));
        assert_eq!(relay_close_reason(4013), Some("invalid-mobile-connection"));
    }

    #[test]
    fn close_reason_returns_none_for_unmapped_codes() {
        for code in [0, 1000, 1006, 3000, 4000, 4005, 4014, -1] {
            assert_eq!(relay_close_reason(code), None, "code {code} 不该被映射");
        }
    }

    // ---- RelayFailure / RelayState 的文本形态 ----

    #[test]
    fn relay_failure_display_matches_dart_to_string() {
        assert_eq!(
            RelayFailure::new("session-not-found", None).to_string(),
            "session-not-found"
        );
        assert_eq!(
            RelayFailure::new("kicked", Some("bad device".to_string())).to_string(),
            "kicked: bad device"
        );
        // 空串 message 不是 null，Dart 会走 `'$reason: $message'`。
        assert_eq!(
            RelayFailure::new("kicked", Some(String::new())).to_string(),
            "kicked: "
        );
    }

    #[test]
    fn state_display_matches_dart_enum_to_string() {
        assert_eq!(RelayState::Idle.to_string(), "RelayState.idle");
        assert_eq!(RelayState::Connecting.to_string(), "RelayState.connecting");
        assert_eq!(
            RelayState::Authenticating.to_string(),
            "RelayState.authenticating"
        );
        assert_eq!(RelayState::Waiting.to_string(), "RelayState.waiting");
        assert_eq!(RelayState::Paired.to_string(), "RelayState.paired");
        assert_eq!(
            RelayState::Reconnecting.to_string(),
            "RelayState.reconnecting"
        );
        assert_eq!(RelayState::Error.to_string(), "RelayState.error");
        assert_eq!(RelayState::Kicked.to_string(), "RelayState.kicked");
        assert_eq!(RelayState::Closed.to_string(), "RelayState.closed");
    }

    // ---- start / connect ----

    #[test]
    fn start_connects_to_relay_ws_uri_and_redacts_it_in_log() {
        let mut h = Harness::new(0);
        let events = h.step(RelayInput::Start, 0);
        assert_eq!(states(&events), vec![RelayState::Connecting]);
        assert_eq!(h.client.state(), RelayState::Connecting);
        assert_eq!(h.transport.connects.len(), 1);
        assert_eq!(h.transport.connects[0].0, 1);
        // 实际连接用的是真 URL（widget_test.dart 的同款期望值）。
        assert_eq!(h.transport.connects[0].1, "wss://zcode.z.ai/ws?mid=MID-1");
        // 日志里 query 值被替换成 <redacted> 并转义。
        assert!(has_log(
            &events,
            "[relay] connecting wss://zcode.z.ai/ws?mid=%3Credacted%3E"
        ));
    }

    #[test]
    fn safe_uri_for_log_redacts_every_query_value() {
        let params = test_params();
        assert_eq!(
            safe_uri_for_log(&params.relay_ws_uri()),
            "wss://zcode.z.ai/ws?mid=%3Credacted%3E"
        );
    }

    #[test]
    fn socket_ready_authenticates_and_sends_auth_init() {
        let now = 1_785_734_607_338_i64;
        let mut h = Harness::new(now);
        let events = h.start(now);
        assert!(has_state(&events, RelayState::Authenticating));
        assert_eq!(h.transport.sends.len(), 1);
        assert_eq!(h.transport.sends[0].0, 1);
        assert_eq!(
            h.last_sent(),
            json!({
                "type": "auth_init",
                "role": "terminal",
                "device_sid": "SID123",
                "meta": {
                    "platform": "android",
                    "version": "3.6.5",
                    "name": "zemote",
                },
                "client_ts": now,
            })
        );
    }

    #[test]
    fn token_mode_connects_to_ws2_and_sends_roleless_auth_init_in_seconds() {
        let now = 1_785_734_607_338_i64;
        let mut h = Harness::with_params(token_params(), now);
        let events = h.step(RelayInput::Start, now);
        assert_eq!(
            h.transport.connects[0].1,
            "wss://zcode.z.ai/api/v1/remote/ws2?token=JWT&mid=MID-1"
        );
        assert!(has_log(
            &events,
            "[relay] connecting wss://zcode.z.ai/api/v1/remote/ws2?token=%3Credacted%3E&mid=%3Credacted%3E"
        ));
        h.step(RelayInput::SocketReady { generation: 1 }, now);
        assert_eq!(
            h.last_sent(),
            json!({
                "type": "auth_init",
                "device_sid": "SID123",
                "meta": {
                    "platform": "android",
                    "version": "3.16.1",
                    "name": "zemote",
                },
                "client_ts": now / 1000,
            })
        );
    }

    #[test]
    fn token_mode_ignores_auth_challenge_without_pass_hash() {
        let now = 1_785_734_607_338_i64;
        let mut h = Harness::with_params(token_params(), now);
        h.start(now);
        let sent_before = h.transport.sends.len();
        h.frame(r#"{"type":"auth_challenge","nonce":"nonce"}"#, now);
        assert_eq!(h.transport.sends.len(), sent_before);
    }

    #[test]
    fn auth_init_version_falls_back_to_web_when_app_version_missing() {
        let params =
            ZemoteConnectionParams::parse("https://zcode.z.ai/remote/v4?sid=S&hash=H&t=1")
                .expect("should parse");
        assert!(params.app_version.is_none());
        let mut client = RelayClient::new(params, "web".to_string(), 0);
        let mut transport = MockTransport::default();
        client.step(RelayInput::Start, &mut transport, 0);
        client.step(RelayInput::SocketReady { generation: 1 }, &mut transport, 0);
        let frame: Value = serde_json::from_str(&transport.sends[0].1).unwrap();
        assert_eq!(frame["meta"]["version"], json!("web"));
        assert_eq!(frame["meta"]["platform"], json!("web"));
    }

    #[test]
    fn socket_failed_while_unpaired_schedules_reconnect() {
        // 3.17.1 网页/桌面：socket close / connect 失败**无条件**重连；
        // 只有非可重试的 relay 错误帧（AUTH_FAILED / WRONG_PARAM 等）才进终态。
        let mut h = Harness::new(0);
        h.step(RelayInput::Start, 0);
        let generation = h.generation();
        let events = h.step(
            RelayInput::SocketFailed {
                generation,
                error: "boom".to_string(),
            },
            0,
        );
        assert!(has_log(&events, "[relay] connect failed: boom"));
        assert_eq!(states(&events), vec![RelayState::Reconnecting]);
        assert!(failures(&events).is_empty());
        assert_eq!(
            timers_started(&events),
            vec![(RelayTimer::Reconnect, 1000, false)]
        );
    }

    #[test]
    fn socket_failed_after_pairing_schedules_reconnect() {
        let mut h = Harness::new(0);
        h.pair(0);
        // 触发一次重连（KICKED 恢复路径），得到第 2 个代次。
        h.frame(
            r#"{"type":"error","code":"KICKED","message":"conflict"}"#,
            0,
        );
        let generation = h.generation();
        assert_eq!(generation, 2);
        // 新 socket 先就绪（进入 authenticating），再让它连接失败。
        h.step(RelayInput::SocketReady { generation }, 0);
        let events = h.step(
            RelayInput::SocketFailed {
                generation,
                error: "tls".to_string(),
            },
            0,
        );
        assert_eq!(states(&events), vec![RelayState::Reconnecting]);
        assert!(failures(&events).is_empty());
        assert_eq!(
            timers_started(&events),
            vec![(RelayTimer::Reconnect, 1000, false)]
        );
    }

    #[test]
    fn stale_socket_ready_is_closed_and_ignored() {
        let mut h = Harness::new(0);
        h.step(RelayInput::Start, 0);
        let events = h.step(RelayInput::SocketReady { generation: 99 }, 0);
        assert!(events.is_empty());
        assert_eq!(h.transport.sends.len(), 0);
        assert_eq!(h.transport.closes, vec![(99, None, None)]);
        assert_eq!(h.client.state(), RelayState::Connecting);
    }

    // ---- auth_challenge → auth_response（含 `?? ''` 调用点回退） ----

    #[test]
    fn auth_challenge_sends_auth_response_with_proof() {
        let mut h = Harness::new(0);
        h.start(0);
        let events = h.frame(r#"{"type":"auth_challenge","nonce":"n1"}"#, 0);
        assert!(events.is_empty(), "该分支不改状态、不发通知");
        // 期望值由 Python 独立算出：
        //   hmac.new(b'HASH=', b'n1|terminal|SID123', sha256) -> base64url 去 padding
        // 与 `proof.rs` 测试 `pass_hash_with_equals_and_realistic_sid` 同向量。
        assert_eq!(
            h.last_sent(),
            json!({
                "type": "auth_response",
                "device_sid": "SID123",
                "proof": "sk6FEyvF8YPR5rNeTifmZ9Ks5E6Wfiyqlc6uRyDVIMM",
                "client_ts": 0,
            })
        );
    }

    #[test]
    fn auth_challenge_without_nonce_falls_back_to_empty_string() {
        // 对应调用点 `frame['nonce'] as String? ?? ''`（`relay_client.dart:247`）：
        // 缺 `nonce` 键时必须当空串参与 HMAC。
        let mut h = Harness::new(0);
        h.start(0);
        h.frame(r#"{"type":"auth_challenge"}"#, 0);
        // 期望值由 Python 独立算出：
        //   hmac.new(b'HASH=', b'|terminal|SID123', sha256) -> base64url 去 padding
        assert_eq!(
            h.last_sent()["proof"],
            json!("WPTf4d3imWvy0r_kGTY8bmXxAJLq6gTut50RaCYcBMw")
        );
        assert_eq!(h.last_sent()["client_ts"], json!(0));
    }

    #[test]
    fn auth_challenge_with_explicit_null_nonce_is_treated_as_missing() {
        let mut h = Harness::new(0);
        h.start(0);
        h.frame(r#"{"type":"auth_challenge","nonce":null}"#, 0);
        assert_eq!(
            h.last_sent()["proof"],
            json!("WPTf4d3imWvy0r_kGTY8bmXxAJLq6gTut50RaCYcBMw")
        );
    }

    #[test]
    fn auth_challenge_with_non_string_nonce_is_treated_as_missing() {
        // PORT-NOTE 覆盖：Dart 这里会抛 TypeError（未捕获），本实现按缺失处理。
        let mut h = Harness::new(0);
        h.start(0);
        h.frame(r#"{"type":"auth_challenge","nonce":42}"#, 0);
        assert_eq!(
            h.last_sent()["proof"],
            json!("WPTf4d3imWvy0r_kGTY8bmXxAJLq6gTut50RaCYcBMw")
        );
    }

    #[test]
    fn proof_uses_params_pass_hash_and_device_sid() {
        // 换一组参数：`sid=sid`、`hash=secret`，配 `nonce=nonce` —— 这正是
        // `Reference_code/test/widget_test.dart` 的 `'proof is hmac-sha256 base64url'`
        // 用例的输入（该用例只断言不含 '='/'+'/'/'，具体值由 Python 独立算出，见 proof.rs）。
        let params = ZemoteConnectionParams::parse("https://zcode.z.ai/remote/v4?sid=sid&hash=secret&t=1")
            .expect("should parse");
        let mut client = RelayClient::new(params, "android".to_string(), 0);
        let mut transport = MockTransport::default();
        client.step(RelayInput::Start, &mut transport, 0);
        client.step(RelayInput::SocketReady { generation: 1 }, &mut transport, 0);
        client.step(
            RelayInput::TextFrame {
                generation: 1,
                text: r#"{"type":"auth_challenge","nonce":"nonce"}"#,
            },
            &mut transport,
            0,
        );
        let frame: Value = serde_json::from_str(&transport.sends[1].1).unwrap();
        assert_eq!(frame["device_sid"], json!("sid"));
        assert_eq!(frame["proof"], json!("BhK2dVn3Xgv0gLej7PGHVdzIGyznuw5a8Gwz_nl9tPo"));
    }

    // ---- pair_status ----

    #[test]
    fn pair_status_waiting_when_never_paired_arms_waiting_timer() {
        let mut h = Harness::new(0);
        h.start(0);
        let events = h.frame(
            r#"{"type":"auth_ack","device_sid":"SID123","pair_status":"waiting"}"#,
            0,
        );
        assert_eq!(states(&events), vec![RelayState::Waiting]);
        assert_eq!(
            timers_started(&events),
            vec![(RelayTimer::Waiting, 30_000, false)]
        );
        // 心跳只在已配对/等待路径里由 `_startHeartbeat` 启动，waiting(未配对) 不启。
        assert!(timers_started(&events)
            .iter()
            .all(|(timer, _, _)| *timer != RelayTimer::Heartbeat));
    }

    #[test]
    fn pair_status_waiting_timeout_reports_desktop_disconnected() {
        let mut h = Harness::new(0);
        h.start(0);
        h.frame(
            r#"{"type":"auth_ack","device_sid":"SID123","pair_status":"waiting"}"#,
            0,
        );
        let events = h.step(RelayInput::TimerFired(RelayTimer::Waiting), 30_000);
        assert_eq!(states(&events), vec![RelayState::Error]);
        assert_eq!(
            failures(&events),
            vec![RelayFailure::new(
                "desktop-disconnected",
                Some(
                    "Desktop did not match this mobile connection before the waiting timeout."
                        .to_string()
                ),
            )]
        );
    }

    #[test]
    fn waiting_timeout_is_ignored_when_was_paired() {
        let mut h = Harness::new(0);
        h.pair(0);
        h.frame(r#"{"type":"pair_status_ack","pair_status":"waiting"}"#, 0);
        let events = h.step(RelayInput::TimerFired(RelayTimer::Waiting), 30_000);
        assert!(states(&events).is_empty());
        assert!(failures(&events).is_empty());
    }

    #[test]
    fn pair_status_matched_marks_paired_and_flushes_queue() {
        let mut h = Harness::new(0);
        h.start(0);
        let queued = h.step(
            RelayInput::SendPayload(obj(json!({"zcode_type": "listTasks"}))),
            0,
        );
        assert!(has_log(
            &queued,
            "[relay] queued (state=RelayState.authenticating): listTasks"
        ));
        assert_eq!(h.transport.sends.len(), 1, "未配对时不能真发出去");

        let events = h.frame(
            r#"{"type":"auth_ack","device_sid":"SID123","pair_status":"matched"}"#,
            5,
        );
        assert!(has_state(&events, RelayState::Paired));
        assert!(has_log(&events, "[relay] flushing 1 queued payload(s)"));
        assert_eq!(h.client.state(), RelayState::Paired);
        assert_eq!(
            timers_started(&events),
            vec![(RelayTimer::Heartbeat, 5_000, true)]
        );
        assert_eq!(h.transport.sends.len(), 2);
        assert_eq!(
            h.sent()[1],
            json!({
                "type": "data",
                "payload": {"zcode_type": "listTasks"},
                "client_ts": 5,
            })
        );
    }

    #[test]
    fn paired_waiting_restarts_heartbeat_without_rewait() {
        let mut h = Harness::new(0);
        h.pair(0);
        let events = h.frame(r#"{"type":"pair_status_ack","pair_status":"waiting"}"#, 1000);
        assert_eq!(states(&events), vec![RelayState::Waiting]);
        assert_eq!(
            timers_started(&events),
            vec![(RelayTimer::Heartbeat, 5_000, true)]
        );
        assert!(timers_started(&events)
            .iter()
            .all(|(timer, _, _)| *timer != RelayTimer::Rewait));
    }

    #[test]
    fn unknown_pair_status_only_refreshes_ack_timestamp() {
        let mut h = Harness::new(0);
        h.start(0);
        let events = h.frame(
            r#"{"type":"auth_ack","device_sid":"SID123","pair_status":"weird"}"#,
            0,
        );
        assert!(states(&events).is_empty());
        assert!(events.is_empty());
        // 缺 pair_status（null）同样只是刷新时间戳。
        let events = h.frame(r#"{"type":"auth_ack","device_sid":"SID123"}"#, 0);
        assert!(events.is_empty());
    }

    // ---- 心跳 ----

    #[test]
    fn heartbeat_sends_every_tick_while_waiting() {
        let mut h = Harness::new(0);
        h.start(0);
        h.frame(
            r#"{"type":"auth_ack","device_sid":"SID123","pair_status":"waiting"}"#,
            0,
        );
        let before = h.transport.sends.len();
        h.step(RelayInput::TimerFired(RelayTimer::Heartbeat), 5_000);
        assert_eq!(h.transport.sends.len(), before + 1);
        assert_eq!(
            h.last_sent(),
            json!({"type": "pair_status_query", "client_ts": 5_000})
        );
        h.step(RelayInput::TimerFired(RelayTimer::Heartbeat), 10_000);
        assert_eq!(h.transport.sends.len(), before + 2);
        assert_eq!(
            h.last_sent(),
            json!({"type": "pair_status_query", "client_ts": 10_000})
        );
    }

    #[test]
    fn heartbeat_is_ignored_when_not_paired_or_waiting() {
        let mut h = Harness::new(0);
        h.start(0); // authenticating
        let events = h.step(RelayInput::TimerFired(RelayTimer::Heartbeat), 100_000);
        assert!(events.is_empty());
    }

    #[test]
    fn heartbeat_ack_timeout_reconnects_immediately() {
        let mut h = Harness::new(0);
        h.pair(0);
        let before = h.transport.sends.len();
        let events = h.step(RelayInput::TimerFired(RelayTimer::Heartbeat), 16_000);
        assert!(has_log(&events, "[relay] heartbeat ack timeout, reconnecting"));
        assert_eq!(states(&events), vec![RelayState::Reconnecting]);
        assert_eq!(h.transport.connects.len(), 2);
        assert_eq!(h.transport.sends.len(), before);
    }

    #[test]
    fn heartbeat_within_ack_timeout_sends_query() {
        let mut h = Harness::new(0);
        h.pair(0);
        let events = h.step(RelayInput::TimerFired(RelayTimer::Heartbeat), 10_000);
        assert!(!has_log(&events, "heartbeat ack timeout"));
        assert_eq!(h.last_sent()["type"], json!("pair_status_query"));
        assert_eq!(h.last_sent()["client_ts"], json!(10_000));
    }

    // ---- KICKED ----

    #[test]
    fn kicked_with_transient_message_recovers_once() {
        let mut h = Harness::new(0);
        h.start(0);
        let events = h.frame(
            r#"{"type":"error","code":"KICKED","message":"Another connection detected"}"#,
            0,
        );
        assert_eq!(
            failures(&events),
            vec![RelayFailure::new(
                "session-conflict",
                Some("Another connection detected".to_string())
            )]
        );
        assert_eq!(states(&events), vec![RelayState::Reconnecting]);
        assert_eq!(h.transport.connects.len(), 2);
        assert_eq!(h.client.state(), RelayState::Reconnecting);

        // 恢复只尝试一次：第二次同样的帧直接进终止分支。
        let events = h.frame(
            r#"{"type":"error","code":"KICKED","message":"Another connection detected"}"#,
            0,
        );
        assert_eq!(states(&events), vec![RelayState::Kicked]);
        assert_eq!(
            failures(&events),
            vec![RelayFailure::new(
                "kicked",
                Some("Another connection detected".to_string())
            )]
        );
        assert_eq!(h.client.state(), RelayState::Kicked);
    }

    #[test]
    fn kicked_while_authenticating_recovers_even_without_transient_hint() {
        let mut h = Harness::new(0);
        h.start(0); // authenticating
        let events = h.frame(r#"{"type":"error","code":"KICKED","message":"nope"}"#, 0);
        assert_eq!(
            failures(&events),
            vec![RelayFailure::new("session-conflict", Some("nope".to_string()))]
        );
        assert_eq!(h.client.state(), RelayState::Reconnecting);
    }

    #[test]
    fn kicked_without_message_or_transient_hint_is_terminal() {
        let mut h = Harness::new(0);
        h.pair(0);
        let events = h.frame(r#"{"type":"error","code":"KICKED"}"#, 0);
        assert_eq!(states(&events), vec![RelayState::Kicked]);
        assert_eq!(failures(&events), vec![RelayFailure::new("kicked", None)]);
        // `_socket?.sink.close()`：无参关闭。
        assert_eq!(h.transport.closes, vec![(1, None, None)]);
    }

    #[test]
    fn kicked_with_null_message_is_terminal_when_paired() {
        let mut h = Harness::new(0);
        h.pair(0);
        let events = h.frame(r#"{"type":"error","code":"KICKED","message":null}"#, 0);
        assert_eq!(states(&events), vec![RelayState::Kicked]);
        assert_eq!(failures(&events), vec![RelayFailure::new("kicked", None)]);
        assert!(has_log(&events, "[relay] error frame: KICKED null"));
    }

    /// 未知/非终态错误码：不迁移状态（仍可重连），但**必须上报 Failure**——
    /// 否则界面只能看到「永远转圈」。`BUSY` 不在终态集合里。
    #[test]
    fn non_terminal_error_frame_reports_failure_and_keeps_state() {
        let mut h = Harness::new(0);
        h.pair(0);
        let events = h.frame(r#"{"type":"error","code":"BUSY","message":"later"}"#, 0);
        assert!(states(&events).is_empty());
        let reported = failures(&events);
        assert_eq!(reported.len(), 1);
        assert_eq!(reported[0].reason, "busy");
        assert_eq!(reported[0].message.as_deref(), Some("later"));
        assert!(has_log(&events, "[relay] error frame: BUSY later"));
    }

    /// 终态错误码：停下并让界面看见（`AUTH_FAILED` / `WRONG_PARAM`）。
    /// 重试同样的凭据/参数不可能成功。
    #[test]
    fn terminal_error_codes_enter_error_state() {
        for (code, reason) in [
            ("AUTH_FAILED", "device-not-owned"),
            ("WRONG_PARAM", "invalid-mobile-connection"),
        ] {
            let mut h = Harness::new(0);
            h.pair(0);
            let events = h.frame(
                &format!(r#"{{"type":"error","code":"{code}","message":"m"}}"#),
                0,
            );
            assert_eq!(states(&events), vec![RelayState::Error], "code={code}");
            let reported = failures(&events);
            assert_eq!(reported.len(), 1, "code={code}");
            assert_eq!(reported[0].reason, reason, "code={code}");
            assert_eq!(reported[0].message.as_deref(), Some("m"), "code={code}");
        }
    }

    #[test]
    fn internal_error_recovers_without_terminal_state() {
        let mut h = Harness::new(0);
        h.pair(0);
        let events = h.frame(r#"{"type":"error","code":"INTERNAL","message":"m"}"#, 0);
        assert_eq!(states(&events), vec![RelayState::Waiting]);
        assert_eq!(
            failures(&events),
            vec![RelayFailure::new("relay-internal", Some("m".to_string()))]
        );
        assert!(timers_started(&events)
            .iter()
            .any(|(timer, delay, repeat)| *timer == RelayTimer::Heartbeat
                && *delay == 5_000
                && *repeat));
        assert_eq!(h.client.state(), RelayState::Waiting);
    }

    // ---- 连接关闭 ----

    #[test]
    fn close_while_paired_schedules_reconnect() {
        let mut h = Harness::new(0);
        h.pair(0);
        let events = h.step(
            RelayInput::SocketClosed {
                generation: 1,
                code: 1006,
                reason: None,
            },
            0,
        );
        assert_eq!(states(&events), vec![RelayState::Reconnecting]);
        assert_eq!(
            timers_started(&events),
            vec![(RelayTimer::Reconnect, 1000, false)]
        );
        assert!(has_log(&events, "[relay] closed code=1006 reason=null mapped=null"));
        assert!(failures(&events).is_empty());
    }

    #[test]
    fn close_desktop_disconnected_reconnects_even_when_never_paired() {
        let mut h = Harness::new(0);
        h.start(0);
        let events = h.step(
            RelayInput::SocketClosed {
                generation: 1,
                code: 4010,
                reason: Some("bye".to_string()),
            },
            0,
        );
        assert_eq!(states(&events), vec![RelayState::Reconnecting]);
        assert!(failures(&events).is_empty());
    }

    #[test]
    fn close_session_not_found_while_unpaired_reconnects() {
        let mut h = Harness::new(0);
        h.start(0);
        let events = h.step(
            RelayInput::SocketClosed {
                generation: 1,
                code: 4004,
                reason: Some("not found".to_string()),
            },
            0,
        );
        assert_eq!(states(&events), vec![RelayState::Reconnecting]);
        assert!(failures(&events).is_empty());
        assert!(has_log(&events, "[relay] closed code=4004 reason=not found mapped=session-not-found"));
    }

    #[test]
    fn close_without_reason_reconnects_and_logs() {
        let mut h = Harness::new(0);
        h.start(0);
        let events = h.step(
            RelayInput::SocketClosed {
                generation: 1,
                code: 4011,
                reason: None,
            },
            0,
        );
        assert_eq!(states(&events), vec![RelayState::Reconnecting]);
        assert!(failures(&events).is_empty());
        assert!(has_log(&events, "[relay] closed code=4011 reason=null mapped=session-expired"));
    }

    #[test]
    fn close_with_unmapped_code_reconnects() {
        let mut h = Harness::new(0);
        h.start(0);
        let events = h.step(
            RelayInput::SocketClosed {
                generation: 1,
                code: 1006,
                reason: None,
            },
            0,
        );
        assert_eq!(states(&events), vec![RelayState::Reconnecting]);
        assert!(failures(&events).is_empty());
        assert!(has_log(&events, "[relay] closed code=1006 reason=null mapped=null"));
    }

    #[test]
    fn close_is_ignored_after_intentional_kick() {
        let mut h = Harness::new(0);
        h.pair(0);
        h.frame(r#"{"type":"error","code":"KICKED","message":"banned"}"#, 0);
        let events = h.step(
            RelayInput::SocketClosed {
                generation: 1,
                code: 1006,
                reason: None,
            },
            0,
        );
        assert!(states(&events).is_empty());
        assert!(failures(&events).is_empty());
        // 但仍然记一条日志。
        assert!(has_log(&events, "[relay] closed code=1006"));
        assert_eq!(h.client.state(), RelayState::Kicked);
    }

    #[test]
    fn close_with_stale_generation_is_ignored() {
        let mut h = Harness::new(0);
        h.pair(0);
        let events = h.step(
            RelayInput::SocketClosed {
                generation: 99,
                code: 1006,
                reason: None,
            },
            0,
        );
        assert!(events.is_empty());
        assert_eq!(h.client.state(), RelayState::Paired);
    }

    // ---- 重连退避 ----

    #[test]
    fn reconnect_backoff_sequence_is_1s_2s_4s_8s_15s_capped() {
        let mut h = Harness::new(0);
        h.pair(0);
        let mut delays = Vec::new();
        for _ in 0..6 {
            let generation = h.generation();
            let events = h.step(
                RelayInput::SocketClosed {
                    generation,
                    code: 1006,
                    reason: None,
                },
                0,
            );
            delays.extend(
                timers_started(&events)
                    .into_iter()
                    .filter(|(timer, _, _)| *timer == RelayTimer::Reconnect)
                    .map(|(_, delay, _)| delay),
            );
            h.step(RelayInput::TimerFired(RelayTimer::Reconnect), 0);
            let generation = h.generation();
            h.step(RelayInput::SocketReady { generation }, 0);
        }
        assert_eq!(delays, vec![1000, 2000, 4000, 8000, 15000, 15000]);
    }

    #[test]
    fn reconnect_attempt_resets_after_matched() {
        let mut h = Harness::new(0);
        h.pair(0);
        // 连撞三次，把 attempt 推到 3（下一次 delay 应为 8000）。
        for _ in 0..3 {
            let generation = h.generation();
            h.step(
                RelayInput::SocketClosed {
                    generation,
                    code: 1006,
                    reason: None,
                },
                0,
            );
            h.step(RelayInput::TimerFired(RelayTimer::Reconnect), 0);
            let generation = h.generation();
            h.step(RelayInput::SocketReady { generation }, 0);
        }
        // 重新配对 → attempt 归零（3.16.1+ 只有 auth_ack 才生效，pair_status_ack 被门禁丢弃）。
        h.frame(
            r#"{"type":"auth_ack","device_sid":"SID123","pair_status":"matched"}"#,
            0,
        );
        let generation = h.generation();
        let events = h.step(
            RelayInput::SocketClosed {
                generation,
                code: 1006,
                reason: None,
            },
            0,
        );
        assert_eq!(
            timers_started(&events),
            vec![(RelayTimer::Reconnect, 1000, false)]
        );
    }

    #[test]
    fn reconnect_timer_does_nothing_after_dispose() {
        let mut h = Harness::new(0);
        h.pair(0);
        h.step(RelayInput::Dispose, 0);
        let connects = h.transport.connects.len();
        let events = h.step(RelayInput::TimerFired(RelayTimer::Reconnect), 0);
        assert!(events.is_empty());
        assert_eq!(h.transport.connects.len(), connects);
    }

    #[test]
    fn reconnect_is_skipped_while_a_connect_is_in_flight() {
        let mut h = Harness::new(0);
        h.pair(0);
        // 直接发起一次 connect（poke 路径）但**不**回报 ready → connect_in_flight=true。
        h.frame(r#"{"type":"pair_status_ack","pair_status":"waiting"}"#, 0);
        h.step(RelayInput::Poke, 0);
        assert_eq!(h.transport.connects.len(), 2);
        // 此时再触发 reconnect 应被 `_connectInFlight` 拦住。
        h.frame(
            r#"{"type":"error","code":"KICKED","message":"conflict"}"#,
            0,
        );
        assert_eq!(h.transport.connects.len(), 2);
    }

    // ---- sendPayload / 队列 ----

    #[test]
    fn send_payload_when_paired_emits_data_frame() {
        let mut h = Harness::new(0);
        h.pair(0);
        let events = h.step(
            RelayInput::SendPayload(obj(json!({"zcode_type": "listTasks", "n": 1}))),
            7,
        );
        assert!(events.is_empty());
        assert_eq!(
            h.last_sent(),
            json!({
                "type": "data",
                "payload": {"zcode_type": "listTasks", "n": 1},
                "client_ts": 7,
            })
        );
    }

    #[test]
    fn send_payload_while_unpaired_is_queued_with_dart_style_log() {
        let mut h = Harness::new(0);
        h.start(0);
        let events = h.step(RelayInput::SendPayload(obj(json!({"zcode_type": "listTasks"}))), 0);
        assert!(has_log(
            &events,
            "[relay] queued (state=RelayState.authenticating): listTasks"
        ));
        assert_eq!(h.transport.sends.len(), 1);
        // 缺 zcode_type 时按 Dart 的 `$null` 渲染。
        let events = h.step(RelayInput::SendPayload(obj(json!({"other": true}))), 0);
        assert!(has_log(
            &events,
            "[relay] queued (state=RelayState.authenticating): null"
        ));
    }

    #[test]
    fn outbound_queue_caps_at_100_payloads() {
        let mut h = Harness::new(0);
        h.start(0);
        for index in 0..105 {
            h.step(
                RelayInput::SendPayload(obj(json!({"zcode_type": format!("p{index}")}))),
                0,
            );
        }
        let events = h.frame(
            r#"{"type":"auth_ack","device_sid":"SID123","pair_status":"matched"}"#,
            0,
        );
        assert!(has_log(&events, "[relay] flushing 100 queued payload(s)"));
        let sent = h.sent();
        // auth_init + 100 条 data。
        assert_eq!(sent.len(), 101);
        assert_eq!(sent[1]["payload"]["zcode_type"], json!("p0"));
        assert_eq!(sent[100]["payload"]["zcode_type"], json!("p99"));
    }

    #[test]
    fn send_payload_after_close_is_queued_not_sent() {
        let mut h = Harness::new(0);
        h.pair(0);
        let generation = h.generation();
        h.step(
            RelayInput::SocketClosed {
                generation,
                code: 1006,
                reason: None,
            },
            0,
        );
        let before = h.transport.sends.len();
        let events = h.step(RelayInput::SendPayload(obj(json!({"zcode_type": "x"}))), 0);
        assert!(has_log(&events, "[relay] queued (state=RelayState.reconnecting): x"));
        assert_eq!(h.transport.sends.len(), before);
    }

    // ---- poke ----

    #[test]
    fn poke_while_paired_sends_pair_status_query() {
        let mut h = Harness::new(0);
        h.pair(0);
        let before = h.transport.sends.len();
        h.step(RelayInput::Poke, 1000);
        assert_eq!(h.transport.sends.len(), before + 1);
        assert_eq!(
            h.last_sent(),
            json!({"type": "pair_status_query", "client_ts": 1000})
        );
    }

    #[test]
    fn poke_while_paired_with_stale_inbound_reconnects() {
        let mut h = Harness::new(0);
        h.pair(0);
        let events = h.step(RelayInput::Poke, 25_001);
        assert!(has_log(&events, "[relay] poke: stale inbound link, reconnecting"));
        assert_eq!(states(&events), vec![RelayState::Reconnecting]);
    }

    #[test]
    fn poke_while_not_paired_forces_connect_without_state_change() {
        let mut h = Harness::new(0);
        h.start(0);
        let events = h.step(RelayInput::Poke, 0);
        assert!(has_log(
            &events,
            "[relay] poke: state=RelayState.authenticating, forcing reconnect"
        ));
        assert!(states(&events).is_empty(), "poke 直连 _connect，不先切 reconnecting");
        assert!(timers_cancelled(&events).contains(&RelayTimer::Reconnect));
        assert_eq!(h.transport.connects.len(), 2);
        assert_eq!(h.transport.closes, vec![(1, None, None)]);
        assert_eq!(h.client.state(), RelayState::Authenticating);
    }

    #[test]
    fn poke_is_ignored_when_idle_or_disposed() {
        let mut h = Harness::new(0);
        assert!(h.step(RelayInput::Poke, 0).is_empty(), "idle 不动作");
        h.pair(0);
        h.step(RelayInput::Dispose, 0);
        assert!(h.step(RelayInput::Poke, 0).is_empty(), "disposed 不动作");
    }

    // ---- debugDropSocket / dispose ----

    #[test]
    fn debug_drop_socket_closes_with_3000_and_reconnects() {
        let mut h = Harness::new(0);
        h.pair(0);
        let events = h.step(RelayInput::DebugDropSocket, 0);
        assert_eq!(
            h.transport.closes,
            vec![(1, Some(3000), Some("debug-drop".to_string()))]
        );
        assert!(has_log(&events, "[relay] closed code=1006 reason=debug-drop mapped=null"));
        assert_eq!(states(&events), vec![RelayState::Reconnecting]);
        assert_eq!(
            timers_started(&events),
            vec![(RelayTimer::Reconnect, 1000, false)]
        );
    }

    #[test]
    fn dispose_closes_socket_cancels_timers_and_marks_closed() {
        let mut h = Harness::new(0);
        h.pair(0);
        let events = h.step(RelayInput::Dispose, 0);
        assert_eq!(states(&events), vec![RelayState::Closed]);
        let cancelled = timers_cancelled(&events);
        for timer in [
            RelayTimer::Heartbeat,
            RelayTimer::Waiting,
            RelayTimer::Rewait,
            RelayTimer::Reconnect,
        ] {
            assert!(cancelled.contains(&timer), "{timer:?} 应被取消");
        }
        assert_eq!(h.transport.closes, vec![(1, None, None)]);
        assert_eq!(h.client.state(), RelayState::Closed);
    }

    #[test]
    fn dispose_can_be_called_twice_and_closes_again_like_dart() {
        let mut h = Harness::new(0);
        h.pair(0);
        h.step(RelayInput::Dispose, 0);
        assert_eq!(h.transport.closes, vec![(1, None, None)]);
        // Dart 的 dispose 不置 null `_socket`，所以再调一次仍会 close 一次。
        let events = h.step(RelayInput::Dispose, 0);
        assert_eq!(h.transport.closes, vec![(1, None, None), (1, None, None)]);
        // 状态已经是 Closed，ValueNotifier 语义下同值不重复通知。
        assert!(states(&events).is_empty());
    }

    // ---- 收帧的边界 ----

    #[test]
    fn data_frame_emits_payload_only_for_objects() {
        let mut h = Harness::new(0);
        h.pair(0);
        let events = h.frame(
            r#"{"type":"data","payload":{"zcode_type":"result","ok":true}}"#,
            0,
        );
        assert_eq!(
            payloads(&events),
            vec![obj(json!({"zcode_type": "result", "ok": true}))]
        );
        assert!(payloads(&h.frame(r#"{"type":"data","payload":[1,2]}"#, 0)).is_empty());
        assert!(payloads(&h.frame(r#"{"type":"data"}"#, 0)).is_empty());
    }

    #[test]
    fn unknown_malformed_and_untyped_frames_are_dropped() {
        let mut h = Harness::new(0);
        h.start(0);
        let before = h.transport.sends.len();
        // 未知 type：不命中任何 case，也不记日志。
        assert!(h.frame(r#"{"type":"hello"}"#, 0).is_empty());
        // 没有 type 键：frame 保持 null，直接返回（不记日志）。
        assert!(h.frame(r#"{"a":1}"#, 0).is_empty());
        // type 不是字符串：switch 不命中。
        assert!(h.frame(r#"{"type":123}"#, 0).is_empty());
        // 顶层不是对象。
        assert!(h.frame(r#"[1,2,3]"#, 0).is_empty());
        // 非法 JSON：记 bad frame。
        let events = h.frame("not json", 0);
        assert!(has_log(&events, "[relay] bad frame: "));
        assert_eq!(h.transport.sends.len(), before);
    }

    #[test]
    fn binary_frame_is_decoded_as_utf8() {
        let mut h = Harness::new(0);
        h.start(0);
        let generation = h.generation();
        let text = r#"{"type":"auth_challenge","nonce":"n1"}"#;
        h.step(
            RelayInput::BinaryFrame {
                generation,
                bytes: text.as_bytes(),
            },
            0,
        );
        assert_eq!(
            h.last_sent()["proof"],
            json!("sk6FEyvF8YPR5rNeTifmZ9Ks5E6Wfiyqlc6uRyDVIMM")
        );
    }

    #[test]
    fn binary_frame_with_invalid_utf8_logs_bad_frame() {
        let mut h = Harness::new(0);
        h.start(0);
        let generation = h.generation();
        let events = h.step(
            RelayInput::BinaryFrame {
                generation,
                bytes: &[0xff, 0xfe, 0x00],
            },
            0,
        );
        assert!(has_log(&events, "[relay] bad frame: "));
        assert_eq!(h.transport.sends.len(), 1);
    }

    #[test]
    fn frame_with_stale_generation_is_ignored() {
        let mut h = Harness::new(0);
        h.start(0);
        let events = h.step(
            RelayInput::TextFrame {
                generation: 99,
                text: r#"{"type":"auth_challenge","nonce":"n1"}"#,
            },
            0,
        );
        assert!(events.is_empty());
        assert_eq!(h.transport.sends.len(), 1);
    }

    #[test]
    fn frame_after_dispose_is_ignored() {
        let mut h = Harness::new(0);
        h.pair(0);
        h.step(RelayInput::Dispose, 0);
        let generation = h.generation();
        let events = h.step(
            RelayInput::TextFrame {
                generation,
                text: r#"{"type":"auth_challenge","nonce":"n1"}"#,
            },
            0,
        );
        assert!(events.is_empty());
    }

    #[test]
    fn inbound_frame_refreshes_dead_link_clock() {
        let mut h = Harness::new(0);
        h.pair(0);
        // 收到入站帧（哪怕是不认识的 type 也会刷新 `_lastInboundAt`）。
        h.frame(r#"{"type":"hello"}"#, 20_000);
        let events = h.step(RelayInput::Poke, 40_000);
        assert!(!has_log(&events, "stale inbound link"));
        assert_eq!(h.last_sent()["client_ts"], json!(40_000));
    }

    // ---- verboseFrames ----

    #[test]
    fn verbose_frames_is_off_by_default_and_logs_both_directions_when_on() {
        let mut h = Harness::new(0);
        assert!(!h.client.verbose_frames());
        h.client.set_verbose_frames(true);
        let events = h.start(0);
        assert!(
            logs(&events)
                .iter()
                .any(|line| line.starts_with("[relay] >> ")),
            "开启后应记录出站帧"
        );
        let generation = h.generation();
        let events = h.step(
            RelayInput::TextFrame {
                generation,
                text: r#"{"type":"hello"}"#,
            },
            0,
        );
        assert!(logs(&events).iter().any(|line| line == r#"[relay] << {"type":"hello"}"#));
    }

    #[test]
    fn frames_are_not_logged_when_verbose_is_off() {
        let mut h = Harness::new(0);
        let events = h.start(0);
        assert!(!logs(&events).iter().any(|line| line.starts_with("[relay] >> ")));
    }
}
