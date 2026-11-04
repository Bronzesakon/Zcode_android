//! 通道 RPC 客户端 —— 对应 `Reference_code/lib/protocol/channel_client.dart`（网页端 `Pne`）。
//!
//! 请求头数组：`[reqType, reqId, channelName, name]`，紧随其后是参数值
//! （`call` 是 args 列表，事件订阅是自由形态）。响应：`[respType, reqId]` + 数据值。
//!
//! # I/O 边界（本 crate 纯逻辑、同步、无 I/O）
//!
//! | Dart 侧 | 本模块 |
//! |---|---|
//! | `sendBody` 回调 | [`ChannelClient::drain_outgoing`]（顺序与 Dart 调用 `sendBody` 的顺序一致） |
//! | `onLog` 回调 | [`ChannelClient::drain_logs`] |
//! | `Future`/`Completer` | [`ChannelClient::handle_message`] 直接返回 [`ChannelEvent`] 列表，宿主据此完成自己的 future；请求 id 由 [`ChannelClient::call`] / [`ChannelClient::add_listener`] 返回 |
//! | `ready` Completer | [`ChannelClient::is_ready`] |
//! | 两处 `Duration(seconds: 30)` | 宿主计时器，见 [`ChannelClient::READY_TIMEOUT_MS`] 与 `call` 的 `timeout_ms` 参数 |
//!
//! PORT-NOTE: 本模块的 [`dart_to_string`] / [`dart_json_to_string`] / [`to_dart_view`] /
//! [`json_as_dart_int`] 是“Dart 动态值视角”的公共工具（`is List` / `is Map` / `'$data'` 语义），
//! 后续 `zemote_client` / `rpc_transport` 解析响应时同样需要，应由主线决定是否提升到公共模块。
//!
//! PORT-NOTE: [`ChannelRpc`] 是宿主侧“发起通道 RPC”的能力抽象（Dart 里就是 `ChannelClient`
//! 本身，`automation_client.dart` 通过 `bridge.channels` 拿到它）。放在本模块是因为它描述的就是
//! 本类型的能力；若主线认为它该独立成模块，移动时保持签名不变即可。

use std::collections::{BTreeMap, VecDeque};

use crate::error::Result;
use crate::ipc_codec::{decode_value, encode_value, Value, ValueReader, ValueWriter};

// ---------------------------------------------------------------------------
// Dart 动态值视角的工具
// ---------------------------------------------------------------------------

/// 对应 Dart 的 `'$value'` / `value.toString()`（[`Value`] 版本）。
///
/// 逐条照抄 Dart 的 `toString`：
/// - `null` → `"null"`
/// - `String` → 原样（不加引号）
/// - `Uint8List`（tag 3）/ 读端 tag 2 → `"[1, 2, 3]"`（Dart `List<int>.toString()`）
/// - `List` → `"[a, b]"`（元素递归，分隔符 `", "`）
/// - `Map` → `"{k: v, k2: v2}"`（见 [`dart_json_to_string`]）
/// - `int` → 十进制
///
/// 用途：Dart `ChannelRpcError` 的两处消息构造 `'$data'` 与 `(data['message'] ?? data).toString()`。
pub fn dart_to_string(value: &Value) -> String {
    match value {
        Value::Undefined => "null".to_string(),
        Value::String(s) => s.clone(),
        // PORT-NOTE: Dart 写端从不产出 tag 2，读端把 2/3 都解成 `Uint8List`，两者 toString 相同。
        Value::Buffer(bytes) | Value::VsBuffer(bytes) => {
            let items: Vec<String> = bytes.iter().map(|b| b.to_string()).collect();
            format!("[{}]", items.join(", "))
        }
        Value::Array(items) => {
            let items: Vec<String> = items.iter().map(dart_to_string).collect();
            format!("[{}]", items.join(", "))
        }
        Value::Object(json) => dart_json_to_string(json),
        Value::Int(v) => v.to_string(),
    }
}

/// 对应 Dart 的 `'$value'` / `value.toString()`（`jsonDecode` 出来的动态值版本）。
///
/// Dart 的 `Map.toString()` 形如 `{key: value}`（**键与字符串值都不加引号**），
/// `List.toString()` 形如 `[1, 2]`，分隔符是 `", "`。
///
/// PORT-NOTE: 键序差异——Dart 的 `Map` 保留插入顺序，`serde_json::Value` 默认是 `BTreeMap`
/// （字典序）。JSON 对象无序，只有把该字符串展示给用户/写日志时可见，协议语义不受影响；
/// 若主线要求与 Dart 逐字符一致，需启用 serde_json 的 `preserve_order`（改 Cargo.toml，由主线定夺）。
pub fn dart_json_to_string(value: &serde_json::Value) -> String {
    match value {
        serde_json::Value::Null => "null".to_string(),
        serde_json::Value::Bool(b) => if *b { "true" } else { "false" }.to_string(),
        // Dart 的 `num.toString()`：整数原样，double 保留 `.0`；serde_json 同样保留字面量形态。
        serde_json::Value::Number(n) => n.to_string(),
        serde_json::Value::String(s) => s.clone(),
        serde_json::Value::Array(items) => {
            let items: Vec<String> = items.iter().map(dart_json_to_string).collect();
            format!("[{}]", items.join(", "))
        }
        serde_json::Value::Object(map) => {
            let entries: Vec<String> = map
                .iter()
                .map(|(k, v)| format!("{}: {}", k, dart_json_to_string(v)))
                .collect();
            format!("{{{}}}", entries.join(", "))
        }
    }
}

/// 把 [`decode_value`] 的结果投影成 **Dart 视角的动态值**。
///
/// Dart 的 `dynamic` 不带 tag 信息，所以 Dart 代码里的 `is List` / `is Map` / `is num` 判定
/// 必须按“解出来是什么形状”来判，而不是按线上 tag 来判。本函数复刻这个视角：
/// - `Undefined` → JSON `null`
/// - `String` → JSON 字符串
/// - `Buffer`(tag 2) / `VsBuffer`(tag 3) → JSON 数字数组。**Dart 的 `Uint8List` 实现了 `List<int>`**，
///   所以它在 `is List` 判定里为真、`x[0] is num` 也为真——这个怪癖被照抄（见测试
///   `initialize_frame_accepts_uint8list_header_quirk`）。
/// - `Array`(tag 4) → JSON 数组
/// - `Object`(tag 5) → 直接展开 `jsonDecode` 的结果（可能是对象/数组/标量）
/// - `Int`(tag 6) → JSON 数字
pub fn to_dart_view(value: &Value) -> serde_json::Value {
    match value {
        Value::Undefined => serde_json::Value::Null,
        Value::String(s) => serde_json::Value::String(s.clone()),
        Value::Buffer(bytes) | Value::VsBuffer(bytes) => serde_json::Value::Array(
            bytes
                .iter()
                .map(|b| serde_json::Value::Number((*b as u64).into()))
                .collect(),
        ),
        Value::Array(items) => serde_json::Value::Array(items.iter().map(to_dart_view).collect()),
        Value::Object(json) => json.clone(),
        Value::Int(v) => serde_json::Value::Number((*v as u64).into()),
    }
}

/// 对应 Dart 的 `(x as num?)?.toInt()`：不是数字则返回 `None`，是数字则**向零截断**成 `i64`。
///
/// PORT-NOTE: Dart 里 `as num` 对非数字抛 `TypeError`（不是 `FormatException`，PORTING.md 的
/// 映射表未覆盖）。Rust 侧不 panic，改为返回 `None`，由调用方决定回落值；两处调用点
/// （`handleMessage` 的 `header[0]`/`header[1]`、`AutomationEntry` 的数字字段）在 Dart 里
/// 都不会因为桌面端的正常载荷而抛错。
/// PORT-NOTE: 超过 `i64` 的 JSON 数字在 Dart 里是 double、`toInt()` 会饱和；本函数走 `as_f64`
/// 分支后 `as i64` 饱和，行为一致（精度已丢，属不可达路径）。
pub fn json_as_dart_int(value: &serde_json::Value) -> Option<i64> {
    if let Some(v) = value.as_i64() {
        return Some(v);
    }
    value.as_f64().map(|f| f as i64)
}

// ---------------------------------------------------------------------------
// 错误与事件
// ---------------------------------------------------------------------------

/// 对应 Dart `ChannelRpcError`。
#[derive(Debug, Clone, PartialEq)]
pub struct ChannelRpcError {
    /// 对应 Dart `ChannelRpcError.message`。
    pub message: String,
    /// 对应 Dart `ChannelRpcError.data`（原始解码值；Dart 的 `null` 即 [`Value::Undefined`]）。
    pub data: Value,
}

impl ChannelRpcError {
    /// 对应 Dart `ChannelRpcError(this.message, this.data)`。
    pub fn new(message: impl Into<String>, data: Value) -> Self {
        Self {
            message: message.into(),
            data,
        }
    }
}

impl std::fmt::Display for ChannelRpcError {
    /// 对应 Dart `toString() => 'ChannelRpcError: $message'`。
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "ChannelRpcError: {}", self.message)
    }
}

impl std::error::Error for ChannelRpcError {}

/// `handleMessage` 分派出来的一次结果 —— 对应 Dart 里 `_handlers[id]` 那个闭包被调用。
///
/// Dart 侧闭包直接操作 `Completer`；本 crate 把结果交回宿主，宿主完成自己的 future。
#[derive(Debug, Clone, PartialEq)]
pub enum ChannelEvent {
    /// 对应 `case resPromiseSuccess: completer.complete(data)`。
    PromiseSuccess {
        /// 对应 Dart 的 `reqId`（请求关联键）。
        id: u64,
        /// 对应 Dart `completer.complete(data)` 的 `data`。
        data: Value,
    },
    /// 对应 `case resPromiseError:` 分支构造的 `ChannelRpcError(message, data)`。
    PromiseError {
        /// 请求关联键。
        id: u64,
        /// `message` 取自 `data['message']`（缺失/为 null 时取整个 `data` 的 `toString`）。
        error: ChannelRpcError,
    },
    /// 对应 `case resPromiseErrorObj:` 分支构造的 `ChannelRpcError('$data', data)`。
    PromiseErrorObj {
        /// 请求关联键。
        id: u64,
        /// `message` 恒为整个 `data` 的 `toString`。
        error: ChannelRpcError,
    },
    /// 对应事件订阅闭包的 `if (type == resEventFire) onEvent(data)`。
    EventFire {
        /// 订阅关联键（`add_listener` 返回的 id）。
        id: u64,
        /// 事件载荷。
        data: Value,
    },
}

impl ChannelEvent {
    /// 该事件对应的请求/订阅 id（Dart 里是 `_handlers` 的键）。
    pub fn id(&self) -> u64 {
        match self {
            ChannelEvent::PromiseSuccess { id, .. }
            | ChannelEvent::PromiseError { id, .. }
            | ChannelEvent::PromiseErrorObj { id, .. }
            | ChannelEvent::EventFire { id, .. } => *id,
        }
    }
}

// ---------------------------------------------------------------------------
// 客户端
// ---------------------------------------------------------------------------

/// 已注册的处理器 —— 对应 Dart `_handlers[id]` 里那个闭包的两条分支。
#[derive(Debug, Clone)]
enum Pending {
    /// `call` 注册的应答处理器（Dart 闭包里 `switch (type)` 认 201/202/203）。
    Call {
        /// 对应 Dart `call(..., timeout:)`；宿主据此给该请求起超时定时器。
        timeout_ms: i64,
    },
    /// `addEventListener` 注册的事件处理器（Dart 闭包里只认 204）。
    Listen {
        /// 取消订阅时要回发给对端的通道名（Dart 的取消闭包捕获了它）。
        channel: String,
        /// 取消订阅时要回发的事件名。
        event: String,
        /// 对应 Dart 闭包捕获的 `sent`：只有已发出 `reqEventListen` 才需要发 `reqEventDispose`。
        sent: bool,
    },
}

/// 等 Initialize 期间排队的一次注册 —— 对应 Dart 里挂在 `ready.then(...)` 上的回调。
#[derive(Debug, Clone)]
enum Deferred {
    /// 见 [`ChannelClient::call`]。
    Call {
        /// 已分配的请求 id。
        id: u64,
        /// 通道名。
        channel: String,
        /// 方法名。
        method: String,
        /// 参数列表。
        args: Vec<Value>,
    },
    /// 见 [`ChannelClient::add_listener`]。
    Listen {
        /// 已分配的订阅 id。
        id: u64,
        /// 通道名。
        channel: String,
        /// 事件名。
        event: String,
        /// 自由形态参数。
        arg: Value,
    },
}

impl Deferred {
    fn id(&self) -> u64 {
        match self {
            Deferred::Call { id, .. } | Deferred::Listen { id, .. } => *id,
        }
    }
}

/// 对应 Dart `ChannelClient`（网页端 `Pne`）。
#[derive(Debug, Default, Clone)]
pub struct ChannelClient {
    /// 对应 Dart `_lastRequestId`（Dart 是后自增，首个 id 为 0）。
    last_request_id: u64,
    /// 对应 Dart `_initialized.isCompleted`。
    initialized: bool,
    /// 对应 Dart `_handlers`。
    pending: BTreeMap<u64, Pending>,
    /// 对应 Dart 里挂在 `ready.then(...)` 上、尚未执行的注册（保序）。
    deferred: VecDeque<Deferred>,
    /// 对应 Dart `sendBody` 收到的字节（宿主取走后发给 IPC 通道）。
    outbox: Vec<Vec<u8>>,
    /// 对应 Dart `onLog` 收到的日志行。
    logs: Vec<String>,
}

impl ChannelClient {
    /// 对应 Dart `ChannelClient.reqPromise`。
    pub const REQ_PROMISE: i64 = 100;
    /// 对应 Dart `ChannelClient.reqPromiseCancel`。
    // PORT-NOTE: Dart 定义了这个常量但**从未使用**（`call` 的取消路径只是 `_handlers.remove(id)`，
    // 从不发 `reqPromiseCancel`）。本模块照抄常量，也照抄“不使用”这一行为。
    pub const REQ_PROMISE_CANCEL: i64 = 101;
    /// 对应 Dart `ChannelClient.reqEventListen`。
    pub const REQ_EVENT_LISTEN: i64 = 102;
    /// 对应 Dart `ChannelClient.reqEventDispose`。
    pub const REQ_EVENT_DISPOSE: i64 = 103;

    /// 对应 Dart `ChannelClient.resInitialize`。
    pub const RES_INITIALIZE: i64 = 200;
    /// 对应 Dart `ChannelClient.resPromiseSuccess`。
    pub const RES_PROMISE_SUCCESS: i64 = 201;
    /// 对应 Dart `ChannelClient.resPromiseError`。
    pub const RES_PROMISE_ERROR: i64 = 202;
    /// 对应 Dart `ChannelClient.resPromiseErrorObj`。
    pub const RES_PROMISE_ERROR_OBJ: i64 = 203;
    /// 对应 Dart `ChannelClient.resEventFire`。
    pub const RES_EVENT_FIRE: i64 = 204;

    /// 对应 Dart `call(..., timeout: const Duration(seconds: 30))` 的默认值。
    pub const DEFAULT_CALL_TIMEOUT_MS: i64 = 30_000;

    /// 对应 Dart `call` 里 `ready.timeout(const Duration(seconds: 30), onTimeout: ...)` 的 30s。
    // PORT-NOTE: 这个 30s 与 `call` 的 `timeout` 参数**无关**（Dart 里是硬编码字面量）。
    // 宿主需要两个独立计时器：等 Initialize 的 30s（超时文案
    // `channel init timeout (no Initialize frame from desktop)`），以及发出后每个请求的
    // `timeout_ms`（超时文案 `'$channel.$method timed out'`）。
    pub const READY_TIMEOUT_MS: i64 = 30_000;

    /// 对应 Dart `ChannelClient({required this.sendBody, this.onLog})`。
    ///
    /// `sendBody` / `onLog` 两个回调由本 crate 的出站队列（[`Self::drain_outgoing`]）与
    /// 日志队列（[`Self::drain_logs`]）取代。
    pub fn new() -> Self {
        Self::default()
    }

    /// 对应 Dart `Future<void> get ready`：是否已完成（收到过 Initialize 帧）。
    pub fn is_ready(&self) -> bool {
        self.initialized
    }

    /// 对应 Dart `sendBody` 回调：取走本次待发送的 IPC body，队列清空。
    ///
    /// 调用时机：宿主应在每次 `handle_message` 之后（以及每次 `call` / `add_listener` /
    /// `cancel_listener` 之后）取一次，取到的顺序就是 Dart 调用 `sendBody` 的顺序。
    pub fn drain_outgoing(&mut self) -> Vec<Vec<u8>> {
        std::mem::take(&mut self.outbox)
    }

    /// 对应 Dart `onLog` 回调：取走累积的日志行。
    pub fn drain_logs(&mut self) -> Vec<String> {
        std::mem::take(&mut self.logs)
    }

    /// 当前未完成的请求/订阅数（Dart 的 `_handlers.length`；Dart 无对应公开 API）。
    pub fn pending_len(&self) -> usize {
        self.pending.len()
    }

    /// 等 Initialize 期间排队的注册数（Dart 无对应公开 API；宿主据此判断 `ready` 超时影响面）。
    pub fn deferred_len(&self) -> usize {
        self.deferred.len()
    }

    /// 某个未完成 `call` 的超时毫秒数（Dart 无对应公开 API；宿主在重连/重发时用它恢复定时器）。
    ///
    /// 返回 `None` 表示该 id 不存在，或它是事件订阅（订阅没有超时）。
    pub fn pending_timeout_ms(&self, id: u64) -> Option<i64> {
        match self.pending.get(&id) {
            Some(Pending::Call { timeout_ms }) => Some(*timeout_ms),
            _ => None,
        }
    }

    /// 丢弃一个未完成的请求/订阅，返回它是否确实存在。
    ///
    /// 对应 Dart `call` 的
    /// `completer.future.timeout(timeout, onTimeout: () { _handlers.remove(id); ... })`
    /// ——宿主计时器到点后调本函数，之后同 id 的响应会被静默忽略（与 Dart 一致）。
    pub fn remove_pending(&mut self, id: u64) -> bool {
        let existed = self.pending.remove(&id).is_some();
        self.deferred.retain(|d| d.id() != id);
        existed
    }

    /// 对应 Dart `call(String channel, String method, List<Object?> args, {Duration timeout})`。
    ///
    /// 返回该请求的 id（Dart 侧是 `_lastRequestId++` 分配、并作为 future 的关联键）。
    /// 与 Dart 一样：Initialize 未到之前不发帧，等收到 Initialize 后按注册顺序补发。
    ///
    /// 应答经 [`Self::handle_message`] 以 [`ChannelEvent::PromiseSuccess`] /
    /// [`ChannelEvent::PromiseError`] / [`ChannelEvent::PromiseErrorObj`] 返回，宿主据 id 关联。
    ///
    /// PORT-NOTE: id 分配时机——Dart 在 `ready` 之后才分配 id，本实现**在 `call` 调用时就分配**。
    /// 因为 ready 队列是 FIFO、补发顺序等于注册顺序，正常路径下两者分配出的 id 完全一致。
    /// 差异只在两种极端情形：(1) ready 超时（30s）时 Dart 不消耗 id，本实现已消耗——id 只是本端
    /// 关联键、对端只回显，线格式不受影响；(2) 与 Dart 微任务交错有关的顺序（Dart 的 `call` 要穿过
    /// `Future.wait` 的额外微任务，`addEventListener` 只穿一个）可能与调用顺序不一致——同样只影响
    /// 本端关联键。本实现按调用顺序单调分配，便于宿主把 id 与自己的 future 对上。
    pub fn call(&mut self, channel: &str, method: &str, args: Vec<Value>, timeout_ms: i64) -> u64 {
        let id = self.alloc_id();
        self.pending.insert(id, Pending::Call { timeout_ms });
        if self.initialized {
            self.send_call(id, channel, method, &args);
        } else {
            self.deferred.push_back(Deferred::Call {
                id,
                channel: channel.to_string(),
                method: method.to_string(),
                args,
            });
        }
        id
    }

    /// 对应 Dart
    /// `addEventListener(String channel, String event, void Function(dynamic) onEvent, {Object? arg})`。
    ///
    /// 返回订阅 id；取消订阅调 [`Self::cancel_listener`]（对应 Dart 返回的那个取消函数）。
    /// 事件经 [`ChannelEvent::EventFire`] 返回。
    ///
    /// 与 Dart 一样：Initialize 未到时不会发 `reqEventListen`，取消也就不用发 `reqEventDispose`。
    pub fn add_listener(&mut self, channel: &str, event: &str, arg: Value) -> u64 {
        let id = self.alloc_id();
        self.pending.insert(
            id,
            Pending::Listen {
                channel: channel.to_string(),
                event: event.to_string(),
                sent: false,
            },
        );
        if self.initialized {
            self.send_listen(id, channel, event, &arg);
        } else {
            self.deferred.push_back(Deferred::Listen {
                id,
                channel: channel.to_string(),
                event: event.to_string(),
                arg,
            });
        }
        id
    }

    /// 对应 Dart `addEventListener` 返回的取消函数：
    /// `cancelled = true; _handlers.remove(id); if (sent) _sendRequest(reqEventDispose, id, channel, event, null);`
    ///
    /// 返回是否确实取消了一个事件订阅（id 不存在或不是订阅时为 `false`；Dart 的取消函数无返回值）。
    pub fn cancel_listener(&mut self, id: u64) -> bool {
        if !matches!(self.pending.get(&id), Some(Pending::Listen { .. })) {
            return false;
        }
        let (channel, event, sent) = match self.pending.remove(&id) {
            Some(Pending::Listen {
                channel,
                event,
                sent,
            }) => (channel, event, sent),
            _ => return false,
        };
        // Dart 的 `cancelled = true` 让 ready 回调提前返回：等价于把排队项删掉。
        self.deferred.retain(|d| d.id() != id);
        if sent {
            self.send_request(
                Self::REQ_EVENT_DISPOSE,
                id,
                &channel,
                &event,
                &Value::Undefined,
            );
        }
        true
    }

    /// 对应 Dart `dispose() => _handlers.clear()`。
    ///
    /// PORT-NOTE: Dart 只清 `_handlers`，**不动**已挂在 `ready.then` 上的排队项（这些项之后仍会
    /// 发帧，只是没有处理器接收响应）。本实现照抄该行为，`deferred` 不清。
    pub fn dispose(&mut self) {
        self.pending.clear();
    }

    /// 对应 Dart `handleMessage(Uint8List body)`。
    ///
    /// 与 Dart 一样**不抛错**：任何解码失败都只记一条 `[ipc] invalid frame: ...` 日志。
    /// 返回值是本次分派出的结果（Dart 里是直接调用 `_handlers[id]` 闭包）。
    /// 若 Initialize 帧到达，还会把排队中的注册按顺序补发进出站队列（见 [`Self::drain_outgoing`]）。
    pub fn handle_message(&mut self, body: &[u8]) -> Vec<ChannelEvent> {
        let mut events = Vec::new();
        let outcome = self.handle_message_inner(body, &mut events);
        if let Err(e) = outcome {
            // 对应 Dart 的 `on Object catch (e) { onLog?.call('[ipc] invalid frame: $e'); }`
            let line = format!("[ipc] invalid frame: {}", e);
            self.log(line);
        }
        events
    }

    // -- 内部实现 ----------------------------------------------------------

    /// 对应 Dart `_lastRequestId++`。
    fn alloc_id(&mut self) -> u64 {
        let id = self.last_request_id;
        self.last_request_id += 1;
        id
    }

    fn log(&mut self, line: impl Into<String>) {
        self.logs.push(line.into());
    }

    /// 对应 Dart `_sendRequest(reqType, id, channel, name, arg)`。
    fn send_request(&mut self, req_type: i64, id: u64, channel: &str, name: &str, arg: &Value) {
        let mut writer = ValueWriter::new();
        // 对应 Dart `encodeValue(writer, [reqType, id, channel, name])`。
        // `Value::from_i64` 复刻 Dart 写端的 int 分支判定（0..=0x7FFFFFFF 走 tag 6，否则 JSON）。
        let header = Value::Array(vec![
            Value::from_i64(req_type),
            Value::from_i64(id as i64),
            Value::String(channel.to_string()),
            Value::String(name.to_string()),
        ]);
        // PORT-NOTE: Dart 的 `encodeValue` 返回 void（`jsonEncode` 理论上可抛，如循环引用）；
        // Rust 的 `encode_value` 返回 Result，但对上面这几个值不可能失败，故按 Dart 的语义忽略。
        let _ = encode_value(&mut writer, &header);
        let _ = encode_value(&mut writer, arg);
        self.outbox.push(writer.into_bytes());
    }

    /// 对应 Dart `call` 闭包里的 `onLog?.call('[ipc] call $channel.$method id=$id')`
    /// + `_sendRequest(reqPromise, ...)`。
    fn send_call(&mut self, id: u64, channel: &str, method: &str, args: &[Value]) {
        self.log(format!("[ipc] call {}.{} id={}", channel, method, id));
        self.send_request(
            Self::REQ_PROMISE,
            id,
            channel,
            method,
            &Value::Array(args.to_vec()),
        );
    }

    /// 对应 Dart `ready.then` 里的
    /// `sent = true; onLog?.call('[ipc] listen ...')` + `_sendRequest(reqEventListen, ...)`。
    fn send_listen(&mut self, id: u64, channel: &str, event: &str, arg: &Value) {
        if let Some(Pending::Listen { sent, .. }) = self.pending.get_mut(&id) {
            *sent = true;
        }
        self.log(format!("[ipc] listen {}.{} id={}", channel, event, id));
        self.send_request(Self::REQ_EVENT_LISTEN, id, channel, event, arg);
    }

    /// 对应 Dart 里 Initialize 帧完成后、`ready.then` 回调依次执行的效果（按注册顺序补发）。
    // PORT-NOTE: Dart 的补发发生在 `handleMessage` 返回**之后**的微任务里；本实现同步补发。
    // 出站顺序（注册顺序）与后续帧的相互次序都不变，宿主总是 `handle_message` 之后再取队列，
    // 因此没有可观测差异。
    fn flush_deferred(&mut self) {
        let queued: Vec<Deferred> = self.deferred.drain(..).collect();
        for item in queued {
            match item {
                Deferred::Call {
                    id,
                    channel,
                    method,
                    args,
                } => self.send_call(id, &channel, &method, &args),
                Deferred::Listen {
                    id,
                    channel,
                    event,
                    arg,
                } => self.send_listen(id, &channel, &event, &arg),
            }
        }
    }

    /// 对应 Dart `handleMessage` 的 try 块（错误由 [`Self::handle_message`] 兜住并记日志）。
    fn handle_message_inner(&mut self, body: &[u8], events: &mut Vec<ChannelEvent>) -> Result<()> {
        let mut reader = ValueReader::new(body.to_vec());
        // 对应 Dart `final header = decodeValue(reader);`
        // + `if (header is! List || header.isEmpty || header[0] is! num) return;`
        // `to_dart_view` 让 tag 4 的 List 与 tag 5 里 jsonDecode 出来的 List 走同一条判定
        // （Dart 的 `is List` 不区分 tag）。
        let header = to_dart_view(&decode_value(&mut reader)?);
        let Some(items) = header.as_array() else {
            return Ok(());
        };
        let Some(first) = items.first() else {
            return Ok(());
        };
        let Some(req_type) = json_as_dart_int(first) else {
            return Ok(());
        };

        if req_type == Self::RES_INITIALIZE {
            // 对应 Dart: 命中 Initialize 就立刻返回——**即使 header 只有 1 个元素**（第二个 Dart
            // 测试），也**不读** header[1] 与后续的 data 值。
            self.log("[ipc] initialized");
            self.initialized = true;
            self.flush_deferred();
            return Ok(());
        }

        if items.len() < 2 {
            return Ok(());
        }
        let Some(id) = json_as_dart_int(&items[1]) else {
            return Ok(());
        };
        // 对应 Dart `final data = decodeValue(reader);`：解码失败会抛进外层 catch（记日志、不派发）。
        // 事件里携带的是**原始解码值**（保留 tag），与 ipc_codec 的约定一致；需要“Dart 视角”的
        // 消费方（如 automation 的响应解析）自行调 `to_dart_view`。
        let data = decode_value(&mut reader)?;
        self.dispatch(req_type, id, data, events);
        Ok(())
    }

    /// 对应 Dart `_handlers[id]?.call(type, data)`（id 无处理器时静默忽略）。
    fn dispatch(&mut self, req_type: i64, id: i64, data: Value, events: &mut Vec<ChannelEvent>) {
        // Dart 的 id 是 64 位 int，负值同样只是查不到处理器；u64 转换失败即等价于“查不到”。
        let Ok(id) = u64::try_from(id) else {
            return;
        };
        let is_call = matches!(self.pending.get(&id), Some(Pending::Call { .. }));
        let is_listen = matches!(self.pending.get(&id), Some(Pending::Listen { .. }));

        if is_call {
            match req_type {
                Self::RES_PROMISE_SUCCESS => {
                    self.pending.remove(&id);
                    events.push(ChannelEvent::PromiseSuccess { id, data });
                }
                Self::RES_PROMISE_ERROR => {
                    self.pending.remove(&id);
                    let message = promise_error_message(&data);
                    events.push(ChannelEvent::PromiseError {
                        id,
                        error: ChannelRpcError::new(message, data),
                    });
                }
                Self::RES_PROMISE_ERROR_OBJ => {
                    self.pending.remove(&id);
                    let message = dart_to_string(&data);
                    events.push(ChannelEvent::PromiseErrorObj {
                        id,
                        error: ChannelRpcError::new(message, data),
                    });
                }
                // Dart 的 call 闭包对其它 type 不做事（也不移除处理器）。
                _ => {}
            }
        } else if is_listen {
            // Dart: `if (type == resEventFire) onEvent(data);`（其它 type 忽略，处理器保留）。
            if req_type == Self::RES_EVENT_FIRE {
                events.push(ChannelEvent::EventFire { id, data });
            }
        }
    }
}

/// 对应 Dart `resPromiseError` 分支的消息构造：
/// `data is Map ? (data['message'] ?? data).toString() : '$data'`。
///
/// 注意 `??` 只在 `data['message']` 为 **null**（或键不存在）时才回落到整个 `data`；
/// 值为 `false` / `0` / `""` 时都算命中。
fn promise_error_message(data: &Value) -> String {
    if let Value::Object(json) = data {
        if let Some(map) = json.as_object() {
            let chosen = map.get("message").filter(|v| !v.is_null()).unwrap_or(json);
            return dart_json_to_string(chosen);
        }
    }
    dart_to_string(data)
}

/// 宿主侧的“发起通道 RPC”能力 —— 对应 Dart `BridgeSession.channels`（即 `ChannelClient`）。
///
/// Dart 的 `ChannelClient.call` 返回 `Future`，本 crate 无 async，故这里只登记请求并返回 id；
/// 响应由宿主从 [`ChannelClient::handle_message`] 返回的 [`ChannelEvent`] 里按 id 关联。
/// `timeout_ms` 对应 Dart `call(timeout:)`，宿主据此起定时器（本 crate 不碰时钟）。
///
/// PORT-NOTE: 该 trait 让 `automation_client` 不必依赖 `zemote_client.rs` 里 `BridgeSession` 的
/// 具体形态（那个模块由另一子代理并行移植）。等 `zemote_client.rs` 落地后，由主线决定是给
/// `BridgeSession` 加 `impl ChannelRpc`（转发到内部 `ChannelClient`），还是直接传 `&mut ChannelClient`。
pub trait ChannelRpc {
    /// 对应 Dart `ChannelClient.call(channel, method, args, timeout: ...)`，返回请求 id。
    fn call(&mut self, channel: &str, method: &str, args: Vec<Value>, timeout_ms: i64) -> u64;
}

impl ChannelRpc for ChannelClient {
    fn call(&mut self, channel: &str, method: &str, args: Vec<Value>, timeout_ms: i64) -> u64 {
        ChannelClient::call(self, channel, method, args, timeout_ms)
    }
}

/// 通道名（网页端 `Wb` 枚举，Dart `Channels` 类里的 static const）。
///
/// 线上字符串，**不可改**（含连字符的写法照抄 Dart）。
pub struct Channels;

impl Channels {
    /// 对应 Dart `Channels.file`。
    pub const FILE: &'static str = "file";
    /// 对应 Dart `Channels.system`。
    pub const SYSTEM: &'static str = "system";
    /// 对应 Dart `Channels.terminal`。
    pub const TERMINAL: &'static str = "terminal";
    /// 对应 Dart `Channels.git`。
    pub const GIT: &'static str = "git";
    /// 对应 Dart `Channels.gitCheckpoint`。
    pub const GIT_CHECKPOINT: &'static str = "git-checkpoint";
    /// 对应 Dart `Channels.setting`。
    pub const SETTING: &'static str = "setting";
    /// 对应 Dart `Channels.credential`。
    pub const CREDENTIAL: &'static str = "credential";
    /// 对应 Dart `Channels.broadcast`。
    pub const BROADCAST: &'static str = "broadcast";
    /// 对应 Dart `Channels.zcodeTask`。
    pub const ZCODE_TASK: &'static str = "zcode-task";
    /// 对应 Dart `Channels.zcodeAgent`。
    pub const ZCODE_AGENT: &'static str = "zcode-agent";
    /// 对应 Dart `Channels.zcodeSession`。
    pub const ZCODE_SESSION: &'static str = "zcode-session";
    /// 对应 Dart `Channels.fileWatcher`。
    pub const FILE_WATCHER: &'static str = "file-watcher";
    /// 对应 Dart `Channels.oauth`。
    pub const OAUTH: &'static str = "oauth";
    /// 对应 Dart `Channels.modelProvider`。
    pub const MODEL_PROVIDER: &'static str = "model-provider";
    /// 对应 Dart `Channels.usageStats`。
    pub const USAGE_STATS: &'static str = "usage-stats";
    /// 对应 Dart `Channels.codingPlanSubscription`。
    pub const CODING_PLAN_SUBSCRIPTION: &'static str = "coding-plan-subscription";
    /// 对应 Dart `Channels.skills`。
    pub const SKILLS: &'static str = "skills";
    /// 模型选择视图（桌面 `model-selection` 服务）。
    pub const MODEL_SELECTION: &'static str = "model-selection";
    /// 对应 Dart `Channels.skillSync`。
    pub const SKILL_SYNC: &'static str = "skill-sync";
    /// 对应 Dart `Channels.mcpSync`。
    pub const MCP_SYNC: &'static str = "mcp-sync";
    /// 对应 Dart `Channels.pluginSync`。
    pub const PLUGIN_SYNC: &'static str = "plugin-sync";
    /// 对应 Dart `Channels.plugins`。
    pub const PLUGINS: &'static str = "plugins";
    /// 对应 Dart `Channels.pluginManagement`。
    pub const PLUGIN_MANAGEMENT: &'static str = "plugin-management";
    /// 对应 Dart `Channels.subagents`。
    pub const SUBAGENTS: &'static str = "subagents";
    /// 对应 Dart `Channels.commands`。
    pub const COMMANDS: &'static str = "commands";
    /// 对应 Dart `Channels.hooks`。
    pub const HOOKS: &'static str = "hooks";
    /// 对应 Dart `Channels.memory`。
    pub const MEMORY: &'static str = "memory";
    /// 对应 Dart `Channels.outputStyle`。
    pub const OUTPUT_STYLE: &'static str = "output-style";
    /// 对应 Dart `Channels.settingsSync`。
    pub const SETTINGS_SYNC: &'static str = "settings-sync";
    /// 对应 Dart `Channels.bots`。
    pub const BOTS: &'static str = "bots";
    /// 对应 Dart `Channels.feedback`。
    pub const FEEDBACK: &'static str = "feedback";
    /// 对应 Dart `Channels.repoWiki`。
    pub const REPO_WIKI: &'static str = "repo-wiki";
    /// 对应 Dart `Channels.promptAttachmentTransfer`。
    pub const PROMPT_ATTACHMENT_TRANSFER: &'static str = "prompt-attachment-transfer";
    /// 对应 Dart `Channels.offPeakTask`。
    pub const OFF_PEAK_TASK: &'static str = "off-peak-task";
}

// ---------------------------------------------------------------------------
// 测试：前 6 条逐条搬自 `Reference_code/test/channel_client_test.dart`，
// 其余为本模块补充（线格式锁定、Dart 怪癖、状态机分支）。
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ipc_codec::encode_to_vec;
    use serde_json::json;

    // -- 测试辅助 ----------------------------------------------------------

    /// 构造一个 tag 4 的 List 作为帧 body（Dart 测试里的 `encodeValue(writer, [...])`）。
    fn frame(values: Vec<Value>) -> Vec<u8> {
        encode_to_vec(&Value::Array(values)).expect("encode 不应失败")
    }

    /// Dart 测试的 `initHeader()`。
    fn init_frame() -> Vec<u8> {
        frame(vec![
            Value::from_i64(ChannelClient::RES_INITIALIZE),
            Value::Int(0),
        ])
    }

    /// Dart 测试的 `successFrame(id, data)`（`respType` 可替换）。
    fn response_frame(res_type: i64, id: u64, data: Value) -> Vec<u8> {
        let mut writer = ValueWriter::new();
        encode_value(
            &mut writer,
            &Value::Array(vec![Value::from_i64(res_type), Value::from_i64(id as i64)]),
        )
        .unwrap();
        encode_value(&mut writer, &data).unwrap();
        writer.into_bytes()
    }

    /// 解出一次 `_sendRequest` 产出的 `(header, arg)` 两个值。
    fn request_of(body: &[u8]) -> (Value, Value) {
        let mut reader = ValueReader::new(body.to_vec());
        let header = decode_value(&mut reader).expect("header 应可解出");
        let arg = decode_value(&mut reader).expect("arg 应可解出");
        (header, arg)
    }

    /// 期望的请求头（`[reqType, id, channel, name]`）。
    fn header(req_type: i64, id: u64, channel: &str, name: &str) -> Value {
        Value::Array(vec![
            Value::from_i64(req_type),
            Value::from_i64(id as i64),
            Value::from(channel),
            Value::from(name),
        ])
    }

    /// 收到 Initialize 的客户端（Dart 测试里的 `client.handleMessage(initHeader())`）。
    fn ready_client() -> ChannelClient {
        let mut client = ChannelClient::new();
        client.handle_message(&init_frame());
        assert!(client.is_ready());
        client
    }

    // -- 搬自 Dart：`channel_client_test.dart` ----------------------------

    /// Dart: `'initialize frame completes ready'`
    #[test]
    fn initialize_frame_completes_ready() {
        let mut client = ChannelClient::new();
        assert!(!client.is_ready());
        let events = client.handle_message(&init_frame());
        assert!(events.is_empty());
        assert!(client.is_ready());
        assert_eq!(client.drain_logs(), vec!["[ipc] initialized".to_string()]);
        client.dispose();
    }

    /// Dart: `'single-element initialize frame completes ready'`
    #[test]
    fn single_element_initialize_frame_completes_ready() {
        let mut client = ChannelClient::new();
        let body = frame(vec![Value::from_i64(ChannelClient::RES_INITIALIZE)]);
        client.handle_message(&body);
        assert!(client.is_ready());
        client.dispose();
    }

    /// Dart: `'call returns response data'`
    #[test]
    fn call_returns_response_data() {
        let mut client = ready_client();

        let id = client.call(
            "zcode-task",
            "listTasks",
            vec![],
            ChannelClient::DEFAULT_CALL_TIMEOUT_MS,
        );

        // Dart 测试从 `sent.last` 里解出 header[1] 当 id；这里同时锁住 id 与整段 header。
        let sent = client.drain_outgoing();
        assert_eq!(sent.len(), 1);
        let (req_header, arg) = request_of(&sent[0]);
        assert_eq!(
            req_header,
            header(
                ChannelClient::REQ_PROMISE,
                id,
                "zcode-task",
                "listTasks"
            )
        );
        assert_eq!(arg, Value::Array(vec![]));

        let events = client.handle_message(&response_frame(
            ChannelClient::RES_PROMISE_SUCCESS,
            id,
            Value::Object(json!({"result": "ok"})),
        ));
        assert_eq!(
            events,
            vec![ChannelEvent::PromiseSuccess {
                id,
                data: Value::Object(json!({"result": "ok"})),
            }]
        );
        assert_eq!(client.pending_len(), 0);
        client.dispose();
    }

    /// Dart: `'call error returns ChannelRpcError'`
    #[test]
    fn call_error_returns_channel_rpc_error() {
        let mut client = ready_client();
        let id = client.call("gold", "validate", vec![], 30_000);
        client.drain_outgoing();

        let payload = Value::Object(json!({"message": "bad request"}));
        let events = client.handle_message(&response_frame(
            ChannelClient::RES_PROMISE_ERROR,
            id,
            payload.clone(),
        ));
        match &events[..] {
            [ChannelEvent::PromiseError { id: got, error }] => {
                assert_eq!(*got, id);
                assert_eq!(error.message, "bad request");
                assert_eq!(error.data, payload);
                // Dart `toString() => 'ChannelRpcError: $message'`
                assert_eq!(error.to_string(), "ChannelRpcError: bad request");
            }
            other => panic!("期望 PromiseError，实得 {:?}", other),
        }
        assert_eq!(client.pending_len(), 0);
        client.dispose();
    }

    /// Dart: `'addEventListener fires on event'`
    #[test]
    fn add_event_listener_fires_on_event() {
        let mut client = ready_client();

        let id = client.add_listener("zcode-agent", "onDynamicConversationFrame", Value::Undefined);

        let sent = client.drain_outgoing();
        assert_eq!(sent.len(), 1);
        let (req_header, arg) = request_of(&sent[0]);
        assert_eq!(
            req_header,
            header(
                ChannelClient::REQ_EVENT_LISTEN,
                id,
                "zcode-agent",
                "onDynamicConversationFrame"
            )
        );
        // Dart 的 `arg` 缺省是 null。
        assert_eq!(arg, Value::Undefined);

        let events = client.handle_message(&response_frame(
            ChannelClient::RES_EVENT_FIRE,
            id,
            Value::Object(json!({"frame": "hello"})),
        ));
        assert_eq!(
            events,
            vec![ChannelEvent::EventFire {
                id,
                data: Value::Object(json!({"frame": "hello"})),
            }]
        );
        client.dispose();
    }

    /// Dart: `'malformed header is ignored without throwing'`
    #[test]
    fn malformed_header_is_ignored_without_throwing() {
        let mut client = ChannelClient::new();
        let body = frame(vec![Value::from_i64(ChannelClient::RES_PROMISE_SUCCESS)]);
        assert!(client.handle_message(&body).is_empty());
        // 不产生任何出站帧，也不产生事件（Dart 只是 return）。
        assert!(client.drain_outgoing().is_empty());
        client.dispose();
    }

    // -- 补充：header 判定与 Initialize 的边界 -----------------------------

    /// Initialize 帧命中即返回：即使后面还跟着值也不读、不派发（Dart 的 early return）。
    #[test]
    fn initialize_frame_ignores_trailing_values() {
        let mut client = ChannelClient::new();
        // header 只有 [200] 一个元素，后面再跟一个 data 值。
        let mut writer = ValueWriter::new();
        encode_value(
            &mut writer,
            &Value::Array(vec![Value::from_i64(ChannelClient::RES_INITIALIZE)]),
        )
        .unwrap();
        encode_value(&mut writer, &Value::Object(json!({"x": 1}))).unwrap();

        let events = client.handle_message(&writer.into_bytes());
        assert!(events.is_empty());
        assert!(client.is_ready());
    }

    /// header 是 tag 5 的 JSON 数组时同样算 `is List`（Dart 的 `is List` 不区分 tag）。
    #[test]
    fn initialize_frame_accepts_json_array_header() {
        let mut client = ChannelClient::new();
        let body = encode_to_vec(&Value::Object(json!([200, 0]))).unwrap();
        assert!(client.handle_message(&body).is_empty());
        assert!(client.is_ready());
    }

    /// `header[0]` 是 JSON 数字（tag 5 的 number）也算 `is num`；`toInt()` 向零截断。
    #[test]
    fn initialize_frame_accepts_json_number_type() {
        for type_value in [json!(200), json!(200.9)] {
            let mut client = ChannelClient::new();
            let body = encode_to_vec(&Value::Object(json!([type_value, 0]))).unwrap();
            client.handle_message(&body);
            assert!(client.is_ready(), "type={type_value}");
        }
    }

    /// PORT-NOTE 怪癖：Dart 的 `Uint8List` 实现了 `List<int>`，所以 tag 3 的字节串在
    /// `header is List` / `header[0] is num` 判定里都为真——首字节等于 200 时 Dart 会直接
    /// 把 ready 置为完成。本实现照抄（`to_dart_view` 把字节串投影成数字数组）。
    #[test]
    fn initialize_frame_accepts_uint8list_header_quirk() {
        let mut client = ChannelClient::new();
        let body = encode_to_vec(&Value::VsBuffer(vec![200, 0])).unwrap();
        client.handle_message(&body);
        assert!(client.is_ready());
    }

    /// header 不是 List（JSON 对象 / 字符串）、空 body、header 为空数组 → 一律静默忽略。
    #[test]
    fn header_that_is_not_a_list_is_ignored() {
        let cases: Vec<Vec<u8>> = vec![
            encode_to_vec(&Value::Object(json!({"type": 200}))).unwrap(),
            encode_to_vec(&Value::from("200")).unwrap(),
            Vec::new(),
            frame(vec![]),
        ];
        for body in cases {
            let mut client = ChannelClient::new();
            assert!(client.handle_message(&body).is_empty(), "body={:?}", body);
            assert!(!client.is_ready());
        }
    }

    // -- 补充：排队（等 Initialize）与线格式 -------------------------------

    /// Initialize 之前发起的 call/listen 都不出帧，收到 Initialize 后按注册顺序补发。
    #[test]
    fn call_is_deferred_until_initialize() {
        let mut client = ChannelClient::new();
        let call_id = client.call("zcode-task", "listTasks", vec![], 30_000);
        let listen_id = client.add_listener("zcode-agent", "onFrame", Value::Undefined);

        // Dart 的 `_lastRequestId++`：首个 id 为 0，第二个为 1。
        assert_eq!(call_id, 0);
        assert_eq!(listen_id, 1);
        assert!(client.drain_outgoing().is_empty());
        assert_eq!(client.deferred_len(), 2);
        assert_eq!(client.pending_len(), 2);

        client.handle_message(&init_frame());

        let sent = client.drain_outgoing();
        assert_eq!(sent.len(), 2);
        assert_eq!(
            request_of(&sent[0]).0,
            header(
                ChannelClient::REQ_PROMISE,
                call_id,
                "zcode-task",
                "listTasks"
            )
        );
        assert_eq!(
            request_of(&sent[1]).0,
            header(
                ChannelClient::REQ_EVENT_LISTEN,
                listen_id,
                "zcode-agent",
                "onFrame"
            )
        );
        assert_eq!(client.deferred_len(), 0);
        // 日志在补发时记录（Dart 的 ready.then 回调里）。
        assert_eq!(
            client.drain_logs(),
            vec![
                "[ipc] initialized".to_string(),
                "[ipc] call zcode-task.listTasks id=0".to_string(),
                "[ipc] listen zcode-agent.onFrame id=1".to_string(),
            ]
        );
    }

    /// 线格式零漂移：`_sendRequest` 的字节 = `encodeValue([reqType, id, channel, name])`
    /// + `encodeValue(arg)`。期望字节由本 crate 的 `encode_to_vec`（Dart `encodeValue` 的移植）
    /// 独立编码同一结构得到。
    #[test]
    fn call_request_wire_bytes_match_dart() {
        let mut client = ready_client();
        let id = client.call("zcode-task", "listTasks", vec![Value::Int(1)], 30_000);
        let sent = client.drain_outgoing();
        assert_eq!(sent.len(), 1);

        let mut expected =
            encode_to_vec(&header(ChannelClient::REQ_PROMISE, id, "zcode-task", "listTasks"))
                .unwrap();
        expected.extend_from_slice(&encode_to_vec(&Value::Array(vec![Value::Int(1)])).unwrap());
        assert_eq!(sent[0], expected);

        // 逐字节复核头部前缀：tag 4 + count 4 + Int(100) + Int(id=0) + String(10) + "zcode-task"
        assert_eq!(&sent[0][..2], &[4u8, 4][..], "头部是 tag 4 的 4 元素数组");
        assert_eq!(&sent[0][2..4], &[6u8, 100][..]);
        assert_eq!(&sent[0][4..6], &[6u8, 0][..]);
        assert_eq!(&sent[0][6..8], &[1u8, 10][..]);
        assert_eq!(&sent[0][8..18], "zcode-task".as_bytes());
    }

    /// 取消订阅（已发出 listen）会补发 `reqEventDispose`，且 arg 为 null。
    #[test]
    fn cancel_listener_after_send_emits_event_dispose() {
        let mut client = ready_client();
        let id = client.add_listener("zcode-agent", "onFrame", Value::from("arg"));
        assert_eq!(client.drain_outgoing().len(), 1);

        assert!(client.cancel_listener(id));
        let sent = client.drain_outgoing();
        assert_eq!(sent.len(), 1);
        let (req_header, arg) = request_of(&sent[0]);
        assert_eq!(
            req_header,
            header(
                ChannelClient::REQ_EVENT_DISPOSE,
                id,
                "zcode-agent",
                "onFrame"
            )
        );
        assert_eq!(arg, Value::Undefined);
        assert_eq!(client.pending_len(), 0);

        // 取消之后同 id 的事件不再派发。
        assert!(client
            .handle_message(&response_frame(
                ChannelClient::RES_EVENT_FIRE,
                id,
                Value::Object(json!({"frame": "late"})),
            ))
            .is_empty());
    }

    /// 取消发生在 Initialize 之前：listen 从未发出，因此既不补发 listen 也不发 dispose。
    #[test]
    fn cancel_listener_before_initialize_sends_nothing() {
        let mut client = ChannelClient::new();
        let id = client.add_listener("zcode-agent", "onFrame", Value::Undefined);
        assert!(client.cancel_listener(id));

        client.handle_message(&init_frame());
        assert!(client.drain_outgoing().is_empty());
        assert_eq!(client.deferred_len(), 0);
        assert_eq!(client.pending_len(), 0);
    }

    /// 对 call 的 id 调 `cancel_listener` 不生效（Dart 的取消函数只属于订阅）。
    #[test]
    fn cancel_listener_rejects_call_id() {
        let mut client = ready_client();
        let id = client.call("zcode-task", "listTasks", vec![], 30_000);
        client.drain_outgoing();
        assert!(!client.cancel_listener(id));
        assert_eq!(client.pending_len(), 1);
    }

    /// Dart `dispose() => _handlers.clear()`：之后到达的响应不再派发。
    #[test]
    fn dispose_clears_pending_handlers() {
        let mut client = ready_client();
        let id = client.call("zcode-task", "listTasks", vec![], 30_000);
        client.drain_outgoing();
        client.dispose();
        assert_eq!(client.pending_len(), 0);
        assert!(client
            .handle_message(&response_frame(
                ChannelClient::RES_PROMISE_SUCCESS,
                id,
                Value::Object(json!({"result": "ok"})),
            ))
            .is_empty());
    }

    /// 未知 id（含负 id）静默忽略。
    #[test]
    fn unknown_request_id_is_ignored() {
        let mut client = ready_client();
        assert!(client
            .handle_message(&response_frame(
                ChannelClient::RES_PROMISE_SUCCESS,
                42,
                Value::Object(json!({"result": "ok"})),
            ))
            .is_empty());
        // 负 id：Dart 里 `_handlers[-1]` 为 null，同样忽略。
        assert!(client
            .handle_message(&response_frame(
                ChannelClient::RES_PROMISE_SUCCESS,
                -1i64 as u64,
                Value::Undefined,
            ))
            .is_empty());
    }

    /// 处理器类型决定分派：订阅不认 201，call 不认 204（Dart 闭包里的 type 判定）。
    #[test]
    fn response_type_must_match_handler_kind() {
        let mut client = ready_client();
        let call_id = client.call("zcode-task", "listTasks", vec![], 30_000);
        let listen_id = client.add_listener("zcode-agent", "onFrame", Value::Undefined);
        client.drain_outgoing();

        assert!(client
            .handle_message(&response_frame(
                ChannelClient::RES_EVENT_FIRE,
                call_id,
                Value::Object(json!({"a": 1})),
            ))
            .is_empty());
        assert!(client
            .handle_message(&response_frame(
                ChannelClient::RES_PROMISE_SUCCESS,
                listen_id,
                Value::Object(json!({"a": 1})),
            ))
            .is_empty());
        assert_eq!(client.pending_len(), 2);
    }

    /// `resPromiseErrorObj` 的 message 是 `'$data'`（整个 data 的 Dart toString）。
    #[test]
    fn promise_error_obj_uses_dart_to_string() {
        let mut client = ready_client();
        let id = client.call("gold", "validate", vec![], 30_000);
        client.drain_outgoing();

        let data = Value::Object(json!({"code": 7, "detail": [1, "x"]}));
        let events = client.handle_message(&response_frame(
            ChannelClient::RES_PROMISE_ERROR_OBJ,
            id,
            data.clone(),
        ));
        match &events[..] {
            [ChannelEvent::PromiseErrorObj { error, .. }] => {
                assert_eq!(error.message, "{code: 7, detail: [1, x]}");
                assert_eq!(error.data, data);
            }
            other => panic!("期望 PromiseErrorObj，实得 {:?}", other),
        }
    }

    /// `resPromiseError` 的 message 回落：`data` 里没有可用的 `message` 键时用整个 map 的 toString。
    #[test]
    fn promise_error_without_message_key_uses_whole_map_string() {
        let mut client = ready_client();

        let id = client.call("gold", "validate", vec![], 30_000);
        client.drain_outgoing();
        let events = client.handle_message(&response_frame(
            ChannelClient::RES_PROMISE_ERROR,
            id,
            Value::Object(json!({"code": 7})),
        ));
        match &events[..] {
            [ChannelEvent::PromiseError { error, .. }] => assert_eq!(error.message, "{code: 7}"),
            other => panic!("期望 PromiseError，实得 {:?}", other),
        }

        // `message` 键存在但为 null → 同样回落（Dart 的 `??`）。
        let id2 = client.call("gold", "validate", vec![], 30_000);
        client.drain_outgoing();
        let events = client.handle_message(&response_frame(
            ChannelClient::RES_PROMISE_ERROR,
            id2,
            Value::Object(json!({"message": null, "code": 7})),
        ));
        match &events[..] {
            [ChannelEvent::PromiseError { error, .. }] => {
                assert_eq!(error.message, "{code: 7, message: null}")
            }
            other => panic!("期望 PromiseError，实得 {:?}", other),
        }

        // 非 Map（字符串）→ `'$data'`。
        let id3 = client.call("gold", "validate", vec![], 30_000);
        client.drain_outgoing();
        let events = client.handle_message(&response_frame(
            ChannelClient::RES_PROMISE_ERROR,
            id3,
            Value::from("boom"),
        ));
        match &events[..] {
            [ChannelEvent::PromiseError { error, .. }] => assert_eq!(error.message, "boom"),
            other => panic!("期望 PromiseError，实得 {:?}", other),
        }
    }

    /// 解不出 data（头部合法但第二值被截断）→ 记日志、不派发、不 panic。
    #[test]
    fn invalid_frame_is_logged_not_thrown() {
        let mut client = ready_client();
        let id = client.call("zcode-task", "listTasks", vec![], 30_000);
        client.drain_outgoing();
        // 丢掉 `[ipc] initialized` 与 `[ipc] call ...`，下面只断言本帧产生的日志。
        client.drain_logs();

        let mut body = encode_to_vec(&Value::Array(vec![
            Value::from_i64(ChannelClient::RES_PROMISE_SUCCESS),
            Value::from_i64(id as i64),
        ]))
        .unwrap();
        // 追加一个 tag 1（String）但长度声明为 5、只给 2 字节 → Truncated。
        body.extend_from_slice(&[1, 5, b'h', b'i']);

        assert!(client.handle_message(&body).is_empty());
        assert_eq!(client.pending_len(), 1);
        let logs = client.drain_logs();
        assert_eq!(logs.len(), 1);
        assert!(logs[0].starts_with("[ipc] invalid frame: "), "{:?}", logs);

        // 完全非法的字节（tag 7 未知）同样只记日志。
        assert!(client.handle_message(&[7]).is_empty());
        assert!(client.drain_logs()[0].starts_with("[ipc] invalid frame: "));
    }

    /// 超时路径：宿主调 `remove_pending` 后，迟到的响应被忽略
    /// （Dart 的 `onTimeout` 里 `_handlers.remove(id)`）。
    #[test]
    fn remove_pending_matches_dart_timeout_path() {
        let mut client = ready_client();
        let id = client.call("zcode-task", "listTasks", vec![], 12_345);
        client.drain_outgoing();
        assert_eq!(client.pending_timeout_ms(id), Some(12_345));

        assert!(client.remove_pending(id));
        assert!(!client.remove_pending(id));
        assert_eq!(client.pending_timeout_ms(id), None);
        assert!(client
            .handle_message(&response_frame(
                ChannelClient::RES_PROMISE_SUCCESS,
                id,
                Value::Undefined,
            ))
            .is_empty());
    }

    /// 通道名是线上契约：逐条对齐 Dart `Channels` 的 33 个常量。
    #[test]
    fn channel_names_match_dart() {
        let pairs = [
            (Channels::FILE, "file"),
            (Channels::SYSTEM, "system"),
            (Channels::TERMINAL, "terminal"),
            (Channels::GIT, "git"),
            (Channels::GIT_CHECKPOINT, "git-checkpoint"),
            (Channels::SETTING, "setting"),
            (Channels::CREDENTIAL, "credential"),
            (Channels::BROADCAST, "broadcast"),
            (Channels::ZCODE_TASK, "zcode-task"),
            (Channels::ZCODE_AGENT, "zcode-agent"),
            (Channels::ZCODE_SESSION, "zcode-session"),
            (Channels::FILE_WATCHER, "file-watcher"),
            (Channels::OAUTH, "oauth"),
            (Channels::MODEL_PROVIDER, "model-provider"),
            (Channels::USAGE_STATS, "usage-stats"),
            (
                Channels::CODING_PLAN_SUBSCRIPTION,
                "coding-plan-subscription",
            ),
            (Channels::SKILLS, "skills"),
            (Channels::SKILL_SYNC, "skill-sync"),
            (Channels::MCP_SYNC, "mcp-sync"),
            (Channels::PLUGIN_SYNC, "plugin-sync"),
            (Channels::PLUGINS, "plugins"),
            (Channels::PLUGIN_MANAGEMENT, "plugin-management"),
            (Channels::SUBAGENTS, "subagents"),
            (Channels::COMMANDS, "commands"),
            (Channels::HOOKS, "hooks"),
            (Channels::MEMORY, "memory"),
            (Channels::OUTPUT_STYLE, "output-style"),
            (Channels::SETTINGS_SYNC, "settings-sync"),
            (Channels::BOTS, "bots"),
            (Channels::FEEDBACK, "feedback"),
            (Channels::REPO_WIKI, "repo-wiki"),
            (
                Channels::PROMPT_ATTACHMENT_TRANSFER,
                "prompt-attachment-transfer",
            ),
            (Channels::OFF_PEAK_TASK, "off-peak-task"),
        ];
        assert_eq!(pairs.len(), 33, "Dart Channels 有 33 个常量");
        for (actual, expected) in pairs {
            assert_eq!(actual, expected);
        }
    }

    /// 协议常量逐条对齐 Dart（含 Dart 定义了但未使用的 `reqPromiseCancel`）。
    #[test]
    fn protocol_constants_match_dart() {
        assert_eq!(ChannelClient::REQ_PROMISE, 100);
        assert_eq!(ChannelClient::REQ_PROMISE_CANCEL, 101);
        assert_eq!(ChannelClient::REQ_EVENT_LISTEN, 102);
        assert_eq!(ChannelClient::REQ_EVENT_DISPOSE, 103);
        assert_eq!(ChannelClient::RES_INITIALIZE, 200);
        assert_eq!(ChannelClient::RES_PROMISE_SUCCESS, 201);
        assert_eq!(ChannelClient::RES_PROMISE_ERROR, 202);
        assert_eq!(ChannelClient::RES_PROMISE_ERROR_OBJ, 203);
        assert_eq!(ChannelClient::RES_EVENT_FIRE, 204);
        // Dart: `ready.timeout(const Duration(seconds: 30))` 与 `timeout: const Duration(seconds: 30)`
        assert_eq!(ChannelClient::READY_TIMEOUT_MS, 30_000);
        assert_eq!(ChannelClient::DEFAULT_CALL_TIMEOUT_MS, 30_000);
    }

    /// `dart_to_string` 复刻 Dart 的 `toString` 语义（`'$data'` 用）。
    #[test]
    fn dart_to_string_matches_dart_semantics() {
        assert_eq!(dart_to_string(&Value::Undefined), "null");
        assert_eq!(dart_to_string(&Value::from("hi")), "hi");
        assert_eq!(dart_to_string(&Value::Int(7)), "7");
        assert_eq!(dart_to_string(&Value::from(vec![1u8, 2, 3])), "[1, 2, 3]");
        assert_eq!(
            dart_to_string(&Value::Array(vec![
                Value::Int(1),
                Value::from("x"),
                Value::Undefined
            ])),
            "[1, x, null]"
        );
        // Dart 的 `Map.toString()` 不加引号、分隔符 `", "`。
        assert_eq!(
            dart_to_string(&Value::Object(json!({"a": 1, "b": [1, 2]}))),
            "{a: 1, b: [1, 2]}"
        );
        // `jsonDecode` 出来的标量/数组同样按 Dart 动态值渲染。
        assert_eq!(dart_json_to_string(&json!(true)), "true");
        assert_eq!(dart_json_to_string(&json!(1.5)), "1.5");
        assert_eq!(dart_json_to_string(&json!([1, "a"])), "[1, a]");
    }

    /// `json_as_dart_int`：向零截断、非数字为 None（Dart 的 `as num?.toInt()`）。
    #[test]
    fn json_as_dart_int_truncates_like_dart() {
        assert_eq!(json_as_dart_int(&json!(3)), Some(3));
        assert_eq!(json_as_dart_int(&json!(3.9)), Some(3));
        assert_eq!(json_as_dart_int(&json!(-3.9)), Some(-3));
        assert_eq!(json_as_dart_int(&json!("3")), None);
        assert_eq!(json_as_dart_int(&json!(true)), None);
        assert_eq!(json_as_dart_int(&json!(null)), None);
    }
}
