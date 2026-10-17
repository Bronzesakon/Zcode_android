//! 会话层：把「一次 `nativeStep` / `nativeCall` / `nativeQuery` / `nativeDispose`」编排成
//! 「解析入参 → 驱动核心 → 收集事件与传输动作 → 组信封」。
//!
//! 契约见 `docs/FFI.md`：§5 创建、§6 步进、§8 命令、§9 查询、§10 销毁、§12 panic 约定、
//! §13 对话层与定时任务层。
//!
//! ## 事件与传输动作的相对顺序
//!
//! [`Session::collect`] 先追加核心事件（§7.2/§7.3/§7.4/§7.5），再追加本步
//! [`RecordingTransport`] 记录到的传输动作（§7.1）。**动作之间**的先后严格等于核心调用顺序
//! （见 `recording_transport` 的模块文档），宿主按数组顺序执行即可。
//!
//! ## §13 的位置
//!
//! 核心的 `ZemoteEvent::Channel` 先经 [`ConversationLayer`] 路由：属于某个 conversation 的
//! 喂给它（产出 `conversation_event`），其余的仍按 §7.3 裸透传。核心自己的 `TimerStart` /
//! `TimerCancel` 也经该层重映射 token 后才给宿主（对话层与核心的 `TimerId` 是两套独立计数器）。

use serde_json::{json, Map, Value};

use zemote_protocol::device_info::TargetPlatform;
use zemote_protocol::zemote_client::{
    PayloadMatcher, ZemoteClient, ZemoteClientConfig, ZemoteEvent, ZemoteInput,
};

use crate::codec::{self, BadInput, CallCommand, OwnedInput};
use crate::conversation::{ConversationLayer, TimerFired};
use crate::recording_transport::RecordingTransport;

/// 一个会话（一个 handle）。
pub struct Session {
    /// 协议核心。
    client: ZemoteClient,
    /// 记录型 transport；`client` 持有的是它的一个 `Box<dyn RelayTransport>` 克隆。
    transport: RecordingTransport,
    /// §13：对话层与定时任务层（按 (桥句柄, conversation key) 持有 conversation）。
    layer: ConversationLayer,
}

// SAFETY: `Session` 要放进 §11 要求的 `static Mutex<HashMap<handle, Arc<Mutex<Session>>>>`，
// 因此必须 `Send + Sync`。它之所以**不是**自动 `Send`，是因为 `ZemoteClient` 里挂着三个
// **未加 `Send` 约束**的 trait object（`Box<dyn RelayTransport>`、`Box<dyn SecureRandom>`、
// `RpcFrameTransport` 里的 `Box<dyn FrameSink>`）——那是协议 crate 的类型，本次禁止改动。
//
// 本 crate 保证的**构造不变式**：`Session` 只能由 [`Session::create`] 造出来，而它固定安装
// 下面这三个具体类型，三者都是纯数据、都 `Send + Sync`：
//   * `RecordingTransport` = `Arc<Mutex<Vec<Action>>>`；
//   * `OsSecureRandom`     = `{ buf: [u8; 32], pos: usize }`（`ZemoteClient::new` 内部构造）；
//   * `OutboxSink`         = `Arc<Mutex<Vec<Payload>>>`（`BridgeSession` 内部构造）。
// 没有任何 `Rc` / 线程局部 / 裸指针，也没有对外暴露「换掉 transport」的口子
// （`client` 是私有字段，`Session` 不转发 `relay_transport_mut`），所以跨线程移动与共享引用
// 都是安全的。访问始终在注册表为该 handle 分配的互斥锁内串行发生（§11）。
unsafe impl Send for Session {}
// SAFETY: 同上 —— `&Session` 跨线程共享时只会读到 `ZemoteClient` 的只读视图
// （`snapshot` / `query`），且实际调用都在注册表的 `Mutex` 内。
unsafe impl Sync for Session {}

impl Session {
    /// §5：按 `configJson` 建会话。
    ///
    /// 返回 `(会话, 构造过程中产生的事件)`。**构造本身可能已有事件**（§5）——这里把
    /// `RecordingTransport` 上可能积压的动作一并带出，宿主不能丢。
    pub fn create(config_json: &str) -> Result<(Session, Vec<Value>), BadInput> {
        Ok(build(parse_config(config_json)?))
    }

    /// §6：喂一个输入，拿回要执行的动作。
    pub fn step(&mut self, input_json: &str, now_ms: i64) -> Value {
        let value: Value = match serde_json::from_str(input_json) {
            Ok(value) => value,
            Err(error) => return codec::bad_input(format!("非法 JSON: {error}")),
        };
        let input = match codec::parse_step_input(&value) {
            Ok(input) => input,
            Err(error) => return codec::bad_input(error.message),
        };
        // 对话层定时器到点时**不**碰核心（那是两个独立的状态机），它的事件单独先出。
        let mut conversation_events: Vec<Value> = Vec::new();
        // `input` 是 owned 的，`as_relay_input()` 借它；与 `self.client` 的可变借用互不冲突。
        let core_events = match &input {
            OwnedInput::TimerFired(host_token) => match self.layer.resolve_timer(host_token.0) {
                Some(TimerFired::Core(token)) => {
                    self.client.step(ZemoteInput::TimerFired(token), now_ms)
                }
                Some(TimerFired::Conversation { bridge, key, token }) => {
                    conversation_events =
                        self.layer
                            .timer_fired(&mut self.client, bridge, &key, token, now_ms);
                    Vec::new()
                }
                // 本层没发过这个 token：按核心的定时器兜底（§6 的输入不该被拒）。
                None => self.client.step(ZemoteInput::TimerFired(*host_token), now_ms),
            },
            OwnedInput::Relay(relay) => {
                self.client
                    .step(ZemoteInput::Relay(relay.as_relay_input()), now_ms)
            }
        };
        conversation_events.extend(self.collect(core_events, now_ms));
        codec::ok_events(conversation_events)
    }

    /// §8：发起一个门面操作。
    pub fn call(&mut self, command_json: &str, now_ms: i64) -> Value {
        let value: Value = match serde_json::from_str(command_json) {
            Ok(value) => value,
            Err(error) => return codec::bad_input(format!("非法 JSON: {error}")),
        };
        let command = match codec::parse_call_command(&value) {
            Ok(command) => command,
            Err(error) => return codec::bad_input(error.message),
        };
        self.dispatch(command, now_ms)
    }

    /// §9：只读查询。
    pub fn query(&self, query_json: &str) -> Value {
        let value: Value = match serde_json::from_str(query_json) {
            Ok(value) => value,
            Err(error) => return codec::bad_input(format!("非法 JSON: {error}")),
        };
        let Some(name) = value.get("query").and_then(Value::as_str) else {
            return codec::bad_input("query 必须是含字符串字段 query 的 JSON 对象");
        };
        match name {
            "snapshot" => codec::ok_snapshot(self.snapshot()),
            other => codec::bad_input(format!("未知 query: {other}")),
        }
    }

    /// §10：销毁会话。对已销毁的 handle 再调用由注册表拦下（`invalidState`，不 panic）。
    ///
    /// §13：**先释放 conversation 再销毁核心** —— 状态机的 dispose 要发
    /// `timerCancel` / `unlistenChannel`（后者还要碰桥的 `ChannelClient`，桥得还在）。
    pub fn dispose(&mut self, now_ms: i64) -> Value {
        let mut events = self.layer.dispose(&mut self.client, now_ms);
        let core_events = self.client.dispose(now_ms);
        events.extend(self.layer.absorb_core_events(&mut self.client, core_events, now_ms));
        self.layer.pump(&mut self.client, now_ms, &mut events);
        events.extend(codec::actions_to_json(&self.transport.take_actions()));
        codec::ok_events(events)
    }

    /// §9 的 `snapshot`。
    ///
    /// **安全约定：`passHash` 绝不出现在返回值里。** `params` 只挑出契约列出的 6 个字段，
    /// 不整体序列化 [`zemote_protocol::connection_params::ZemoteConnectionParams`]。
    pub fn snapshot(&self) -> Value {
        let params = self.client.params();
        let bridges: Vec<Value> = self
            .client
            .active_bridges()
            .iter()
            .map(|bridge| {
                json!({
                    "session": bridge.id().0,
                    "wireBridgeSessionId": bridge.wire_bridge_session_id(),
                    "workspaceKey": bridge.workspace_key(),
                    "initialTaskId": bridge.initial_task_id(),
                    "degraded": bridge.degraded(),
                    "recovered": bridge.recovered(),
                    "disposed": bridge.is_disposed(),
                    "conversationKeys": bridge.conversation_keys().collect::<Vec<&str>>(),
                })
            })
            .collect();
        let device_info = self.client.device_info();
        json!({
            "disposed": self.client.is_disposed(),
            "relay": {
                "state": self.client.relay().state().as_str(),
                // 直接读核心的标志位。曾经这里靠"观察事件流"推导（记录是否见过 paired），
                // 那对快照来说是不该有的近似——已改为读 `RelayClient::was_paired()`。
                "wasPaired": self.client.relay().was_paired(),
            },
            "deviceInfo": {
                "platform": device_info.platform,
                "version": device_info.version,
                "name": device_info.name,
            },
            "params": {
                "deviceSid": params.device_sid,
                "deviceMid": params.device_mid,
                "deviceName": params.device_name,
                "appVersion": params.app_version,
                "theme": params.theme,
                "timestamp": params.timestamp,
            },
            "bridges": bridges,
            // 直接读核心的 `_recoveringBridges`。曾经这里靠"最后一次观察到的 degraded 值"
            // 推导（`== "recovering"` 即视为仍在恢复），在边界情形下会与核心不一致。
            "recovering": self
                .client
                .recovering()
                .iter()
                .map(|session| session.0)
                .collect::<Vec<u64>>(),
        })
    }

    // -----------------------------------------------------------------------
    // 内部
    // -----------------------------------------------------------------------

    /// 把核心事件 + 本步传输动作转成 JSON 事件数组。
    ///
    /// 顺序：核心事件（经 §13 层路由/重映射）→ 超时清扫 → 出站帧推给 relay → §7.1 传输动作。
    /// `take_actions` 是整体取空，动作之间的先后即核心调用顺序。
    fn collect(&mut self, core_events: Vec<ZemoteEvent>, now_ms: i64) -> Vec<Value> {
        let mut events = self.layer.absorb_core_events(&mut self.client, core_events, now_ms);
        // §13：通道调用 / 定时任务调用的 deadline 到期（宿主拿不到那些通道 id，只能本层判）。
        events.extend(self.layer.sweep(&mut self.client, now_ms));
        // 对话层/定时任务层可能刚发了通道调用 → 推给 relay。
        self.layer.pump(&mut self.client, now_ms, &mut events);
        events.extend(codec::actions_to_json(&self.transport.take_actions()));
        events
    }

    /// §13 的命令：层已经产出 JSON 事件，这里补上本步的 §7.1 传输动作。
    fn finish_layer_events(&self, mut events: Vec<Value>) -> Vec<Value> {
        events.extend(codec::actions_to_json(&self.transport.take_actions()));
        events
    }

    /// §8 的命令分发。
    fn dispatch(&mut self, command: CallCommand, now_ms: i64) -> Value {
        let mut events: Vec<ZemoteEvent> = Vec::new();
        let operation: Option<String> = match command {
            CallCommand::Bootstrap => match self.client.bootstrap(now_ms) {
                Ok(op) => {
                    events = op.events;
                    Some(op.token)
                }
                Err(error) => return codec::zemote_error_envelope(&error),
            },
            CallCommand::ListWorkspaces => match self.client.list_workspaces(now_ms) {
                Ok(op) => {
                    events = op.events;
                    Some(op.token)
                }
                Err(error) => return codec::zemote_error_envelope(&error),
            },
            CallCommand::OpenBridge {
                workspace_key,
                task_id,
                timeout_ms,
            } => match self.client.open_bridge(
                &workspace_key,
                task_id.as_deref(),
                timeout_ms,
                now_ms,
            ) {
                Ok(op) => {
                    events = op.events;
                    Some(op.token)
                }
                Err(error) => return codec::zemote_error_envelope(&error),
            },
            CallCommand::ReconnectWorkspace { workspace_key } => {
                match self.client.reconnect_workspace(&workspace_key, now_ms) {
                    Ok(op) => {
                        events = op.events;
                        Some(op.token)
                    }
                    Err(error) => return codec::zemote_error_envelope(&error),
                }
            }
            CallCommand::SendMobileViewState {
                workspace_key,
                task_id,
            } => {
                match self
                    .client
                    .send_mobile_view_state(&workspace_key, task_id.as_deref(), now_ms)
                {
                    // §8：不产生挂起操作 → `operation: null`。
                    Ok(produced) => {
                        events = produced;
                        None
                    }
                    Err(error) => return codec::zemote_error_envelope(&error),
                }
            }
            CallCommand::Connect => {
                events = self.client.connect(now_ms);
                None
            }
            CallCommand::PokeRelay => {
                events = self.client.poke_relay(now_ms);
                None
            }
            CallCommand::WaitPaired { timeout_ms } => match self.client.wait_paired(timeout_ms) {
                // §8：已配对时核心返回 `None`（Dart 返回一个已完成的 future）→ `operation: null`。
                Some(op) => {
                    events = op.events;
                    Some(op.token)
                }
                None => None,
            },
            CallCommand::BridgeWaitHealthy {
                session,
                timeout_ms,
            } => match self.client.bridge_wait_healthy(session, timeout_ms) {
                Ok(Some(op)) => {
                    events = op.events;
                    Some(op.token)
                }
                Ok(None) => None,
                Err(error) => return codec::zemote_error_envelope(&error),
            },
            // §13.2：打开一个对话。返回的 `operation` 就是 conversation key。
            CallCommand::ConversationOpen { session, scope } => {
                match self.layer.open(&mut self.client, session, scope, now_ms) {
                    Ok((key, layer_events)) => {
                        return codec::ok_operation(
                            Some(key),
                            self.finish_layer_events(layer_events),
                        )
                    }
                    Err(error) => return error.envelope(),
                }
            }
            // §13.2：把一个 `op` + `args` 透传给对话层（协议细节全在 Rust，§13.1）。
            CallCommand::ConversationCall {
                session,
                key,
                op,
                args,
            } => match self
                .layer
                .call(&mut self.client, session, &key, &op, args, now_ms)
            {
                Ok((operation, layer_events)) => {
                    return codec::ok_operation(operation, self.finish_layer_events(layer_events))
                }
                Err(error) => return error.envelope(),
            },
            // §13.2：释放对话层对象。
            CallCommand::ConversationClose { session, key } => {
                match self.layer.close(&mut self.client, session, &key, now_ms) {
                    Ok(layer_events) => {
                        return codec::ok_operation(None, self.finish_layer_events(layer_events))
                    }
                    Err(error) => return error.envelope(),
                }
            }
            // §13.3：定时任务层。结果经顶层 `completed` 的 `outcome.value` 返回。
            CallCommand::AutomationCall { session, op, args } => {
                match self
                    .layer
                    .automation(&mut self.client, session, &op, args, now_ms)
                {
                    Ok((token, layer_events)) => {
                        return codec::ok_operation(
                            Some(token),
                            self.finish_layer_events(layer_events),
                        )
                    }
                    Err(error) => return error.envelope(),
                }
            }
            // 通用通道调用：任意通道 / 任意方法，结果同样经顶层 `completed` 返回。
            CallCommand::ChannelCall {
                session,
                channel,
                method,
                args,
                timeout_ms,
            } => match self.layer.channel_call(
                &mut self.client,
                session,
                &channel,
                &method,
                args,
                timeout_ms,
                now_ms,
            ) {
                Ok((token, layer_events)) => {
                    return codec::ok_operation(
                        Some(token),
                        self.finish_layer_events(layer_events),
                    )
                }
                Err(error) => return error.envelope(),
            },
            CallCommand::Request {
                payload,
                match_types,
                match_fields,
                timeout_ms,
            } => {
                let type_refs: Vec<&str> = match_types.iter().map(String::as_str).collect();
                let mut matcher = PayloadMatcher::new(&type_refs);
                for (key, value) in match_fields {
                    matcher = matcher.with_field(&key, value);
                }
                match self
                    .client
                    .request(payload, matcher, timeout_ms, now_ms, &mut events)
                {
                    Ok(token) => Some(token),
                    Err(error) => return codec::zemote_error_envelope(&error),
                }
            }
        };
        let json_events = self.collect(events, now_ms);
        codec::ok_operation(operation, json_events)
    }
}

// ===========================================================================
// §5 的 config 解析
// ===========================================================================

/// `nativeCreate` 的 `configJson` 解析结果。
///
/// 与「建会话」刻意解耦：`"dryRun": true` 时只解析、不建会话、不占 handle（见
/// [`parsed_view`] 与 `registry::create`）。
#[derive(Debug, Clone)]
pub struct ParsedConfig {
    /// 解析（并按需覆盖）后的连接参数。
    pub params: zemote_protocol::connection_params::ZemoteConnectionParams,
    /// 宿主传入的 `kIsWeb`。
    pub is_web: bool,
    /// 宿主传入的 `defaultTargetPlatform`。
    pub target_platform: TargetPlatform,
    /// 构造时间（Unix 毫秒）。
    pub now_ms: i64,
    /// `"dryRun": true` 时只解析、不建会话。
    pub dry_run: bool,
}

/// 解析 `nativeCreate` 的 `configJson`（§5）。
///
/// 解析失败一律 [`BadInput`]（§5：`{"ok":false,"error":{"kind":"badInput",...}}`）。
pub fn parse_config(config_json: &str) -> Result<ParsedConfig, BadInput> {
    let value: Value = serde_json::from_str(config_json)
        .map_err(|error| BadInput::new(format!("非法 JSON: {error}")))?;
    let map = value
        .as_object()
        .ok_or_else(|| BadInput::new("config 必须是 JSON 对象"))?;

    let url = required_str(map, "url")?;
    // §5：`url` 必填，其余可选。
    let now_ms = optional_i64(map, "nowMs")?.unwrap_or(0);
    let is_web = optional_bool(map, "isWeb")?.unwrap_or(false);
    let target_platform = parse_target_platform(map.get("targetPlatform"))?;
    let dry_run = optional_bool(map, "dryRun")?.unwrap_or(false);

    let mut params = zemote_protocol::connection_params::ZemoteConnectionParams::parse(url)
        .ok_or_else(|| BadInput::new(format!("url 解析失败（需要 https/wss 且含 sid/hash/t）: {url}")))?;
    // §5：显式给了就**覆盖** URL 里解析出来的对应字段。
    if let Some(app_version) = optional_string(map, "appVersion")? {
        params.app_version = Some(app_version);
    }
    if let Some(device_name) = optional_string(map, "deviceName")? {
        params.device_name = Some(device_name);
    }
    if let Some(theme) = optional_string(map, "theme")? {
        params.theme = Some(theme);
    }
    if let Some(mid) = optional_string(map, "mid")? {
        params.device_mid = Some(mid);
    }

    Ok(ParsedConfig {
        params,
        is_web,
        target_platform,
        now_ms,
        dry_run,
    })
}

/// 由已解析的 config 建会话（不会失败）。
pub fn build(config: ParsedConfig) -> (Session, Vec<Value>) {
    let ParsedConfig {
        params,
        is_web,
        target_platform,
        now_ms,
        dry_run: _,
    } = config;
    let transport = RecordingTransport::new();
    let client = ZemoteClient::new(
        params,
        ZemoteClientConfig {
            is_web,
            target_platform,
        },
        Box::new(transport.clone()),
        now_ms,
    );
    let mut session = Session {
        client,
        transport,
        layer: ConversationLayer::new(),
    };
    let events = session.collect(Vec::new(), now_ms);
    (session, events)
}

/// `"dryRun": true` 时返回的 `parsed` 对象。
///
/// 前 6 个字段与 §9 `snapshot.params` **逐字段一致**（同一份 `ZemoteConnectionParams`，
/// 同样的覆盖规则），另加 `host` / `scheme` / `relayUri` 供 Kotlin 配对层做去重键与展示。
///
/// **`passHash` 同样绝不出现在这里**（与 §9 的安全约定一致）。
pub fn parsed_view(config: &ParsedConfig) -> Value {
    let params = &config.params;
    json!({
        "deviceSid": params.device_sid,
        "deviceMid": params.device_mid,
        "deviceName": params.device_name,
        "appVersion": params.app_version,
        "theme": params.theme,
        "timestamp": params.timestamp,
        "host": params.source.host(),
        "scheme": params.source.scheme(),
        "relayUri": params.relay_ws_uri().to_string(),
    })
}

// ===========================================================================
// §5 的 config 字段解析
// ===========================================================================

/// §5：`targetPlatform` 取值，默认 `android`。
fn parse_target_platform(value: Option<&Value>) -> Result<TargetPlatform, BadInput> {
    let Some(value) = value else {
        return Ok(TargetPlatform::Android);
    };
    let Some(name) = value.as_str() else {
        return Err(BadInput::new("字段 targetPlatform 必须是字符串"));
    };
    match name {
        "android" => Ok(TargetPlatform::Android),
        "ios" => Ok(TargetPlatform::Ios),
        "windows" => Ok(TargetPlatform::Windows),
        "macos" => Ok(TargetPlatform::Macos),
        "linux" => Ok(TargetPlatform::Linux),
        "fuchsia" => Ok(TargetPlatform::Fuchsia),
        other => Err(BadInput::new(format!("未知 targetPlatform: {other}"))),
    }
}

fn required_str<'a>(map: &'a Map<String, Value>, key: &str) -> Result<&'a str, BadInput> {
    map.get(key)
        .and_then(Value::as_str)
        .ok_or_else(|| BadInput::new(format!("字段 {key} 必须是字符串")))
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

fn optional_bool(map: &Map<String, Value>, key: &str) -> Result<Option<bool>, BadInput> {
    match map.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(Value::Bool(value)) => Ok(Some(*value)),
        Some(_) => Err(BadInput::new(format!("字段 {key} 必须是布尔或 null"))),
    }
}

fn optional_string(map: &Map<String, Value>, key: &str) -> Result<Option<String>, BadInput> {
    match map.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(Value::String(text)) => Ok(Some(text.clone())),
        Some(_) => Err(BadInput::new(format!("字段 {key} 必须是字符串或 null"))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    use base64::engine::general_purpose::STANDARD;
    use base64::Engine as _;
    use zemote_protocol::ipc_codec::{
        decode_value, encode_value, Value as IpcValue, ValueReader, ValueWriter,
    };
    use zemote_protocol::prelude::to_dart_view;
    use zemote_protocol::zemote_client::BridgeSessionId;

    /// 与协议 crate 测试同一份 URL（期望 relay 地址 `wss://zcode.z.ai/ws?mid=MID-1`）。
    const URL: &str = "https://zcode.z.ai/remote/v4?sid=SID123&hash=HASH%3D&t=1785734607338\
                       &mid=MID-1&name=DESKTOP&app_version=3.6.5";

    fn config(extra: &str) -> String {
        format!(r#"{{"url":"{URL}","nowMs":1760000000000{extra}}}"#)
    }

    fn session() -> Session {
        Session::create(&config("")).expect("config 应能建会话").0
    }

    /// 期待失败的建会话（`Session` 没有 `Debug`，不能用 `expect_err`）。
    fn create_error(config: &str) -> BadInput {
        match Session::create(config) {
            Ok(_) => panic!("应当失败: {config}"),
            Err(error) => error,
        }
    }

    fn events_of(envelope: &Value) -> &Vec<Value> {
        envelope["events"].as_array().expect("events 必须是数组")
    }

    fn find<'a>(events: &'a [Value], kind: &str) -> Option<&'a Value> {
        events.iter().find(|event| event["type"] == json!(kind))
    }

    // ---- §5 创建 ----

    #[test]
    fn create_returns_the_session_and_an_events_array() {
        let (session, events) = Session::create(&config("")).expect("应成功");
        // 构造本身可能已有事件（§5：宿主必须处理，不能丢）；形状必须是数组。
        assert!(events.iter().all(Value::is_object));
        assert_eq!(session.snapshot()["relay"]["state"], json!("idle"));
    }

    #[test]
    fn create_overrides_url_derived_fields() {
        let (session, _) = Session::create(&config(
            r#", "appVersion":"9.9.9", "deviceName":"Pixel 8", "theme":"dark", "mid":"MID-9""#,
        ))
        .expect("应成功");
        let snapshot = session.snapshot();
        assert_eq!(snapshot["params"]["appVersion"], json!("9.9.9"));
        assert_eq!(snapshot["params"]["deviceName"], json!("Pixel 8"));
        assert_eq!(snapshot["params"]["theme"], json!("dark"));
        assert_eq!(snapshot["params"]["deviceMid"], json!("MID-9"));
        assert_eq!(snapshot["params"]["deviceSid"], json!("SID123"));
    }

    #[test]
    fn create_keeps_url_values_when_not_overridden() {
        let (session, _) = Session::create(&config("")).expect("应成功");
        let snapshot = session.snapshot();
        assert_eq!(snapshot["params"]["deviceMid"], json!("MID-1"));
        assert_eq!(snapshot["params"]["deviceName"], json!("DESKTOP"));
        assert_eq!(snapshot["params"]["appVersion"], json!("3.6.5"));
        assert_eq!(snapshot["params"]["theme"], json!(null));
    }

    /// §5：`targetPlatform` 默认 `android`，显式给值则覆盖。
    #[test]
    fn create_maps_target_platform() {
        let (default, _) = Session::create(&config("")).expect("应成功");
        assert_eq!(default.snapshot()["deviceInfo"]["platform"], json!("android"));

        let (ios, _) = Session::create(&config(r#", "targetPlatform":"ios""#)).expect("应成功");
        assert_eq!(ios.snapshot()["deviceInfo"]["platform"], json!("ios"));

        let (web, _) = Session::create(&config(r#", "isWeb":true, "targetPlatform":"ios""#))
            .expect("应成功");
        assert_eq!(web.snapshot()["deviceInfo"]["platform"], json!("web"));
    }

    #[test]
    fn create_with_bad_target_platform_is_bad_input() {
        let error = create_error(&config(r#", "targetPlatform":"symbian""#));
        assert!(error.message.contains("symbian"), "{}", error.message);
    }

    #[test]
    fn create_requires_url() {
        let error = create_error(r#"{"nowMs":1}"#);
        assert!(error.message.contains("url"), "{}", error.message);
    }

    #[test]
    fn create_with_unparsable_url_is_bad_input() {
        let error = create_error(r#"{"url":"https://zcode.z.ai/remote/v4?sid=S"}"#);
        assert!(error.message.contains("url 解析失败"), "{}", error.message);
    }

    #[test]
    fn create_with_invalid_json_is_bad_input() {
        assert!(Session::create("not json").is_err());
        assert!(Session::create("[1]").is_err());
    }

    /// `parse_config` 与 `parsed_view`：`dryRun` 只是配置里的一个开关，
    /// 解析逻辑（含覆盖规则）与建会话**完全共用**。
    #[test]
    fn parse_config_reads_dry_run_and_parsed_view_matches_snapshot_params() {
        let parsed = parse_config(&config("")).expect("应解析成功");
        assert!(!parsed.dry_run, "缺省必须是 false");
        let view = parsed_view(&parsed);
        assert_eq!(view["host"], json!("zcode.z.ai"));
        assert_eq!(view["scheme"], json!("https"));
        assert_eq!(view["relayUri"], json!("wss://zcode.z.ai/ws?mid=MID-1"));

        let dry = parse_config(&config(r#", "dryRun":true"#)).expect("应解析成功");
        assert!(dry.dry_run);

        // 与建会话后的 snapshot.params 逐字段一致。
        let session = session();
        let params = &session.snapshot()["params"];
        for key in [
            "deviceSid",
            "deviceMid",
            "deviceName",
            "appVersion",
            "theme",
            "timestamp",
        ] {
            assert_eq!(view[key], params[key], "字段 {key}");
        }
        // 安全约定：`parsed` 里同样不能出现 passHash。
        let text = serde_json::to_string(&view).expect("必须能序列化");
        assert!(!text.contains("HASH=") && !text.contains("passHash"), "{text}");
    }

    // ---- §6 步进 ----

    #[test]
    fn step_with_invalid_json_is_bad_input() {
        let mut session = session();
        let envelope = session.step("{{{", 0);
        assert_eq!(envelope["ok"], json!(false));
        assert_eq!(envelope["error"]["kind"], json!("badInput"));
        assert_eq!(envelope["events"], json!([]));
    }

    #[test]
    fn step_with_unknown_type_is_bad_input() {
        let mut session = session();
        let envelope = session.step(r#"{"type":"teleport"}"#, 0);
        assert_eq!(envelope["error"]["kind"], json!("badInput"));
        assert!(envelope["error"]["message"]
            .as_str()
            .unwrap()
            .contains("teleport"));
    }

    /// **端到端**：建会话 → `start` → `ws_connect`（wss）→ 回喂 `socket_ready` →
    /// `relay_state` 变化 + `ws_send_text`。
    ///
    /// 这条证明整条链路是通的：`RecordingTransport` 记录 → §7.1 事件 → 宿主按代次回喂。
    #[test]
    fn end_to_end_start_then_socket_ready_drives_relay_state() {
        let mut session = session();

        // 1) start：核心发起连接，动作必须变成 ws_connect 事件。
        let started = session.step(r#"{"type":"start"}"#, 1_760_000_000_000);
        assert_eq!(started["ok"], json!(true));
        let connect = find(events_of(&started), "ws_connect").expect("必须有 ws_connect");
        assert!(
            connect["uri"].as_str().unwrap().starts_with("wss://"),
            "uri 应为 wss: {connect}"
        );
        assert_eq!(connect["uri"], json!("wss://zcode.z.ai/ws?mid=MID-1"));
        // 代次由核心给出，宿主原样回传。
        let generation = connect["generation"].as_u64().expect("generation 必须是数字");
        assert_eq!(generation, 1);
        assert!(
            find(events_of(&started), "relay_state").is_some(),
            "start 至少产生一次 relay_state"
        );

        // 2) 回喂 socket_ready：核心进入 authenticating 并发 auth_init。
        let ready = session.step(
            &format!(r#"{{"type":"socket_ready","generation":{generation}}}"#),
            1_760_000_000_000,
        );
        assert_eq!(ready["ok"], json!(true));
        let states: Vec<&str> = events_of(&ready)
            .iter()
            .filter(|event| event["type"] == json!("relay_state"))
            .map(|event| event["state"].as_str().unwrap())
            .collect();
        assert!(
            states.contains(&"authenticating"),
            "socket_ready 后应进入 authenticating，实际 {states:?}"
        );

        // 3) 传输动作里必须有 auth_init 的 ws_send_text，且跟在 ws_connect 之后（同一代次）。
        let send = find(events_of(&ready), "ws_send_text").expect("必须有 ws_send_text");
        assert_eq!(send["generation"], json!(generation));
        let frame: Value = serde_json::from_str(send["text"].as_str().unwrap()).expect("必须是 JSON");
        assert_eq!(frame["type"], json!("auth_init"));
        assert_eq!(frame["device_sid"], json!("SID123"));

        // 4) 快照反映核心状态。
        let snapshot = session.query(r#"{"query":"snapshot"}"#);
        assert_eq!(snapshot["snapshot"]["relay"]["state"], json!("authenticating"));
        assert_eq!(snapshot["snapshot"]["relay"]["wasPaired"], json!(false));
    }

    /// 整条配对链路：`socket_ready` → `matched` → `paired`，快照的 `wasPaired` 随之翻真。
    #[test]
    fn pairing_marks_snapshot_was_paired() {
        let mut session = session();
        session.step(r#"{"type":"start"}"#, 0);
        session.step(r#"{"type":"socket_ready","generation":1}"#, 0);
        let paired = session.step(
            r#"{"type":"text_frame","generation":1,"text":"{\"type\":\"pair_status_ack\",\"pair_status\":\"matched\"}"}"#,
            0,
        );
        assert_eq!(paired["ok"], json!(true));
        assert!(find(events_of(&paired), "relay_timer_start").is_some(), "配对后应起心跳");
        assert_eq!(
            session.query(r#"{"query":"snapshot"}"#)["snapshot"]["relay"]["wasPaired"],
            json!(true)
        );
    }

    /// §6：代次不符的输入被核心忽略 —— FFI 层不改写、不报错，原样透传。
    #[test]
    fn stale_generation_is_passed_through_and_ignored_by_core() {
        let mut session = session();
        session.step(r#"{"type":"start"}"#, 0);
        session.step(r#"{"type":"socket_ready","generation":1}"#, 0);
        let stale = session.step(
            r#"{"type":"text_frame","generation":99,"text":"{\"type\":\"pair_status_ack\",\"pair_status\":\"matched\"}"}"#,
            0,
        );
        assert_eq!(stale["ok"], json!(true));
        assert!(
            find(events_of(&stale), "relay_state").is_none(),
            "旧代次的帧不该改变状态"
        );
        assert_eq!(
            session.query(r#"{"query":"snapshot"}"#)["snapshot"]["relay"]["state"],
            json!("authenticating")
        );
    }

    /// §6：`timer_fired`（门面定时器）走 `ZemoteInput::TimerFired`。
    #[test]
    fn facade_timer_fired_is_accepted() {
        let mut session = session();
        let envelope = session.step(r#"{"type":"timer_fired","token":0}"#, 0);
        assert_eq!(envelope["ok"], json!(true));
    }

    /// §6 的 `dispose` 是 **relay 层**退出，不是 §10 的会话销毁。
    #[test]
    fn step_dispose_disposes_the_relay_not_the_session() {
        let mut session = session();
        session.step(r#"{"type":"start"}"#, 0);
        let envelope = session.step(r#"{"type":"dispose"}"#, 0);
        let states: Vec<&str> = events_of(&envelope)
            .iter()
            .filter(|event| event["type"] == json!("relay_state"))
            .map(|event| event["state"].as_str().unwrap())
            .collect();
        assert!(states.contains(&"closed"), "实际 {states:?}");
        assert_eq!(session.snapshot()["disposed"], json!(false));
    }

    // ---- §8 命令 ----

    #[test]
    fn call_unknown_method_is_bad_input() {
        let mut session = session();
        let envelope = session.call(r#"{"method":"fly"}"#, 0);
        assert_eq!(envelope["ok"], json!(false));
        assert_eq!(envelope["error"]["kind"], json!("badInput"));
    }

    #[test]
    fn call_with_invalid_json_is_bad_input() {
        let mut session = session();
        assert_eq!(session.call("nope", 0)["error"]["kind"], json!("badInput"));
    }

    /// `bootstrap` 返回 `operation`（线上 `requestId`），并且**未配对时**载荷只会入队。
    #[test]
    fn bootstrap_returns_operation_token() {
        let mut session = session();
        let envelope = session.call(r#"{"method":"bootstrap"}"#, 0);
        assert_eq!(envelope["ok"], json!(true));
        let token = envelope["operation"].as_str().expect("operation 必须是字符串");
        assert!(token.starts_with("bootstrap-"), "token={token}");
        // 未配对 → relay 队列里攒着，不产生 ws_send_text。
        assert!(find(events_of(&envelope), "ws_send_text").is_none());
    }

    #[test]
    fn list_workspaces_returns_operation_token() {
        let mut session = session();
        let envelope = session.call(r#"{"method":"list_workspaces"}"#, 0);
        let token = envelope["operation"].as_str().expect("operation 必须是字符串");
        assert!(token.starts_with("workspace-list-"), "token={token}");
    }

    #[test]
    fn open_bridge_returns_operation_token() {
        let mut session = session();
        let envelope = session.call(
            r#"{"method":"open_bridge","workspaceKey":"wk","taskId":null,"timeoutMs":30000}"#,
            0,
        );
        assert_eq!(envelope["ok"], json!(true));
        let token = envelope["operation"].as_str().expect("operation 必须是字符串");
        assert!(token.starts_with("workspace-bridge-"), "token={token}");
    }

    #[test]
    fn reconnect_workspace_returns_operation_token() {
        let mut session = session();
        let envelope = session.call(
            r#"{"method":"reconnect_workspace","workspaceKey":"wk"}"#,
            0,
        );
        let token = envelope["operation"].as_str().expect("operation 必须是字符串");
        assert!(token.starts_with("workspace-reconnect-"), "token={token}");
    }

    /// §8：`connect` / `poke_relay` / `send_mobile_view_state` 不产生挂起操作 → `null`。
    #[test]
    fn operations_that_do_not_suspend_return_null() {
        let mut session = session();

        let connect = session.call(r#"{"method":"connect"}"#, 0);
        assert_eq!(connect["ok"], json!(true));
        assert_eq!(connect["operation"], json!(null));
        assert!(find(events_of(&connect), "ws_connect").is_some());

        let poke = session.call(r#"{"method":"poke_relay"}"#, 0);
        assert_eq!(poke["operation"], json!(null));

        let view = session.call(
            r#"{"method":"send_mobile_view_state","workspaceKey":"wk","taskId":null}"#,
            0,
        );
        assert_eq!(view["ok"], json!(true));
        assert_eq!(view["operation"], json!(null));
    }

    /// `wait_paired`：未配对 → 有令牌；已配对 → `None`（`operation: null`）。
    #[test]
    fn wait_paired_token_and_already_paired_null() {
        let mut session = session();
        let waiting = session.call(r#"{"method":"wait_paired","timeoutMs":30000}"#, 0);
        let token = waiting["operation"].as_str().expect("未配对时应有令牌");
        assert!(token.starts_with("pair:"), "token={token}");
        // 该挂起操作自己的超时定时器必须是 §7.3 的 timer_start。
        let timer = find(events_of(&waiting), "timer_start").expect("应有 timer_start");
        assert_eq!(timer["delayMs"], json!(30000));

        // 走到已配对。
        session.step(r#"{"type":"start"}"#, 0);
        session.step(r#"{"type":"socket_ready","generation":1}"#, 0);
        session.step(
            r#"{"type":"text_frame","generation":1,"text":"{\"type\":\"pair_status_ack\",\"pair_status\":\"matched\"}"}"#,
            0,
        );
        let done = session.call(r#"{"method":"wait_paired"}"#, 0);
        assert_eq!(done["ok"], json!(true));
        assert_eq!(done["operation"], json!(null));
    }

    /// `bridge_wait_healthy` 对未知桥会话报 `invalidState`（§8 的失败信封形态）。
    #[test]
    fn bridge_wait_healthy_on_unknown_session_is_invalid_state() {
        let mut session = session();
        let envelope = session.call(
            r#"{"method":"bridge_wait_healthy","session":42,"timeoutMs":45000}"#,
            0,
        );
        assert_eq!(envelope["ok"], json!(false));
        assert_eq!(envelope["error"]["kind"], json!("invalidState"));
        assert!(envelope.get("events").is_some());
    }

    /// `conversation_open` 对未知桥会话报 `invalidState`（§8 的失败信封形态）。
    #[test]
    fn conversation_open_on_unknown_session_is_invalid_state() {
        let mut session = session();
        let envelope = session.call(
            r#"{"method":"conversation_open","session":7,"scope":{"workspacePath":"/repo"}}"#,
            0,
        );
        assert_eq!(envelope["error"]["kind"], json!("invalidState"));
        assert_eq!(envelope["events"], json!([]));
    }

    /// `request` 的逃生舱：`matchTypes` / `matchFields` 映射到 `PayloadMatcher`，
    /// 返回线上 `requestId` 作为 `operation`。
    #[test]
    fn request_escape_hatch_returns_request_id() {
        let mut session = session();
        let envelope = session.call(
            r#"{"method":"request","payload":{"requestId":"r-1","zcode_type":"custom"},"matchTypes":["custom-response"],"matchFields":{"requestId":"r-1"},"timeoutMs":30000}"#,
            0,
        );
        assert_eq!(envelope["ok"], json!(true));
        assert_eq!(envelope["operation"], json!("r-1"));
        assert!(find(events_of(&envelope), "timer_start").is_some());
    }

    /// `request` 的载荷缺 `requestId` → 核心报 `protocol`（§3 的 kind 之一）。
    #[test]
    fn request_without_request_id_reports_protocol_error() {
        let mut session = session();
        let envelope = session.call(
            r#"{"method":"request","payload":{"zcode_type":"x"},"matchTypes":["y"],"matchFields":{}}"#,
            0,
        );
        assert_eq!(envelope["ok"], json!(false));
        assert_eq!(envelope["error"]["kind"], json!("protocol"));
    }

    // ---- §13：对话层与定时任务层 ----

    /// 测试用时间戳（与 `config("")` 里的 `nowMs` 一致）。
    const NOW: i64 = 1_760_000_000_000;

    /// §13 测试用的 workspace scope；`conversation_key` 取 `workspaceIdentity`，故 key 是 `id-1`。
    const SCOPE: &str = r#"{"workspaceIdentity":"id-1","workspacePath":"/repo"}"#;

    /// 通道应答类型（`ChannelClient::RES_PROMISE_SUCCESS`）。
    const RES_PROMISE_SUCCESS: i64 = 201;

    /// 一条中继文本帧（宿主侧 socket 收到的帧）。
    fn text_frame(text: &str) -> String {
        json!({"type": "text_frame", "generation": 1, "text": text}).to_string()
    }

    /// 把 relay 推到 paired（与协议 crate 测试同一套握手帧）。
    fn drive_to_paired(session: &mut Session) {
        session.step(r#"{"type":"start"}"#, NOW);
        session.step(r#"{"type":"socket_ready","generation":1}"#, NOW);
        session.step(&text_frame(r#"{"type":"auth_challenge","nonce":"nonce-1"}"#), NOW);
        session.step(
            &text_frame(r#"{"type":"pair_status_ack","pair_status":"matched"}"#),
            NOW,
        );
    }

    /// 最近一条 `ws_send_text` 的中继信封里的 `payload`。
    fn last_sent_payload(events: &[Value]) -> Value {
        let text = events
            .iter()
            .filter(|event| event["type"] == json!("ws_send_text"))
            .next_back()
            .and_then(|event| event["text"].as_str())
            .expect("应有 ws_send_text");
        let envelope: Value = serde_json::from_str(text).expect("中继信封应是 JSON");
        envelope["payload"].clone()
    }

    /// 把若干个 IPC 值依次编码成字节。
    ///
    /// 通道帧的线格式是「头数组 + 参数值」**两个**连续的值（`ChannelClient::send_request`
    /// 连写两次 `encode_value`），而 `encode_to_vec` 只写一个，故这里自己拼。
    fn ipc_bytes(values: &[IpcValue]) -> Vec<u8> {
        let mut writer = ValueWriter::new();
        for value in values {
            encode_value(&mut writer, value).expect("IPC 值应能编码");
        }
        writer.into_bytes()
    }

    /// 从 IPC 字节里按顺序解出「头」与「参数」两个值。
    fn ipc_pair(bytes: &[u8]) -> (Value, Value) {
        let mut reader = ValueReader::new(bytes.to_vec());
        let header = to_dart_view(&decode_value(&mut reader).expect("头应能解码"));
        let args = to_dart_view(&decode_value(&mut reader).expect("参数应能解码"));
        (header, args)
    }

    /// 一条中继 `rpc-frame` 载荷（`dataBase64` 是 IPC 体）。
    fn rpc_frame(wire: &str, body: &[u8]) -> Value {
        json!({
            "type": "data",
            "payload": {
                "zcode_type": "rpc-frame",
                "bridgeSessionId": wire,
                "seq": 1,
                "messageSeq": 1,
                "fragmentIndex": 0,
                "fragmentCount": 1,
                "messageBytes": body.len(),
                "dataBase64": STANDARD.encode(body),
            },
        })
    }

    /// 最近一条 `ws_send_text` 里的 rpc-frame 解出的 `(通道请求头, 参数)`。
    fn last_channel_call(events: &[Value]) -> (Value, Value) {
        let payload = last_sent_payload(events);
        let bytes = STANDARD
            .decode(
                payload["dataBase64"]
                    .as_str()
                    .expect("rpc-frame 应带 dataBase64"),
            )
            .expect("base64 应合法");
        ipc_pair(&bytes)
    }

    /// 构造一条「桌面端发来的通道应答」rpc-frame（IPC 头 `[respType, id]` + 数据）。
    fn desktop_channel_response(wire: &str, id: u64, data: Value) -> String {
        let body = ipc_bytes(&[
            IpcValue::Array(vec![
                IpcValue::from_i64(RES_PROMISE_SUCCESS),
                IpcValue::from_i64(id as i64),
            ]),
            data.into(),
        ]);
        rpc_frame(wire, &body).to_string()
    }

    /// 打开一个桥、把 relay 推到 paired、并让桥的 `ChannelClient` 就绪（收到 Initialize）。
    ///
    /// 返回 `(桥句柄, 线上 bridgeSessionId, open_bridge 的 30s 请求超时定时器的宿主 token)`。
    fn open_bridge_ready(session: &mut Session) -> (u64, String, u64) {
        drive_to_paired(session);
        let envelope = session.call(
            r#"{"method":"open_bridge","workspaceKey":"wk-1","taskId":null,"timeoutMs":30000}"#,
            NOW,
        );
        let token = envelope["operation"].as_str().expect("应有令牌").to_string();
        let requested = last_sent_payload(events_of(&envelope))["bridgeSessionId"]
            .as_str()
            .expect("open_bridge 请求应带 bridgeSessionId")
            .to_string();
        // open_bridge 的请求超时定时器（宿主 token 由本层分配）。
        let core_host = find(events_of(&envelope), "timer_start")
            .expect("open_bridge 应有 30s 请求超时定时器")["token"]
            .as_u64()
            .expect("token 应是数字");
        let wire = "bridge-1".to_string();
        let ready = json!({
            "type": "data",
            "payload": {
                "zcode_type": "workspace-bridge-ready",
                "requestId": token,
                "bridgeSessionId": requested,
                "bridge": {"bridgeSessionId": wire, "workspaceKey": "wk-1"},
            },
        });
        let envelope = session.step(&text_frame(&ready.to_string()), NOW);
        let bridge = events_of(&envelope)
            .iter()
            .find_map(|event| {
                (event["type"] == json!("completed")
                    && event["outcome"]["kind"] == json!("bridge_opened"))
                .then(|| event["outcome"]["session"].as_u64())
                .flatten()
            })
            .expect("应有 bridge_opened 完成事件");

        // 桌面端发来的 Initialize：让桥的 `ChannelClient` 就绪（否则 `call` 只会排队不发帧）。
        let body = ipc_bytes(&[IpcValue::Array(vec![IpcValue::from_i64(200)])]);
        let initialized = session.step(&text_frame(&rpc_frame(&wire, &body).to_string()), NOW);
        assert_eq!(initialized["ok"], json!(true));
        assert!(
            events_of(&initialized)
                .iter()
                .any(|event| event["line"] == json!("[ipc] initialized")),
            "Initialize 帧应让桥的通道就绪：{initialized}"
        );
        (bridge, wire, core_host)
    }

    fn conversation_open_cmd(bridge: u64) -> String {
        format!(r#"{{"method":"conversation_open","session":{bridge},"scope":{SCOPE}}}"#)
    }

    fn conversation_call_cmd(bridge: u64, key: &str, op: &str, args: &str) -> String {
        format!(
            r#"{{"method":"conversation_call","session":{bridge},"key":"{key}","op":"{op}","args":{args}}}"#
        )
    }

    /// 跑完 `handshake` op 的两次往返（`helloConversationV4` → `initializeConversationV4`）。
    fn finish_handshake(session: &mut Session, bridge: u64, wire: &str) {
        let opened = session.call(&conversation_call_cmd(bridge, "id-1", "handshake", "{}"), NOW);
        assert_eq!(opened["ok"], json!(true));
        let (hello, _) = last_channel_call(events_of(&opened));
        assert_eq!(hello[2], json!("zcode-agent"));
        assert_eq!(hello[3], json!("helloConversationV4"));
        let hello_id = hello[1].as_u64().expect("id 应是数字");

        let step = session.step(
            &text_frame(&desktop_channel_response(
                wire,
                hello_id,
                json!({"connectionId": "conn-1"}),
            )),
            NOW,
        );
        let (initialize, _) = last_channel_call(events_of(&step));
        assert_eq!(initialize[3], json!("initializeConversationV4"));
        let initialize_id = initialize[1].as_u64().expect("id 应是数字");

        let step = session.step(
            &text_frame(&desktop_channel_response(wire, initialize_id, json!({}))),
            NOW,
        );
        assert!(
            find(events_of(&step), "conversation_event").is_some(),
            "initialize 应答应产出对话事件：{step}"
        );
        // §8 的挂起操作机制：对话层的 op 完成也必须在**顶层**给一条 `completed`，
        // 否则宿主（Kotlin 只在顶层 `completed` 上 resolve）的 `Deferred` 会挂到兜底超时。
        assert!(
            events_of(&step).iter().any(|event| {
                event["type"] == json!("completed")
                    && event["token"] == json!("conv:id-1:handshake:1")
                    && event["outcome"]["kind"] == json!("ok")
            }),
            "handshake op 应在顶层收尾：{step}"
        );
    }

    /// §13.2：`conversation_open` 返回 key、登记成功；重复 open 同一 key **不重复登记**。
    #[test]
    fn conversation_open_returns_the_key_and_registers_once() {
        let mut session = session();
        let (bridge, _wire, _) = open_bridge_ready(&mut session);
        let command = conversation_open_cmd(bridge);

        let first = session.call(&command, NOW);
        assert_eq!(first["ok"], json!(true));
        assert_eq!(first["operation"], json!("id-1"));
        assert!(session.layer.contains(BridgeSessionId(bridge), "id-1"));
        assert!(
            events_of(&first).iter().any(|event| {
                event["type"] == json!("conversation_opened")
                    && event["key"] == json!("id-1")
                    && event["session"] == json!(bridge)
            }),
            "首次 open 应发 conversation_opened：{first}"
        );
        // §8 的挂起操作机制：`operation` 非 null 就必须有一条同 token 的顶层 `completed`，
        // 否则宿主（Kotlin 的 `invoke` 一见 `operation != null` 就登记 `Deferred`）会等到兜底超时。
        assert!(
            events_of(&first).iter().any(|event| {
                event["type"] == json!("completed")
                    && event["token"] == json!("id-1")
                    && event["outcome"]["kind"] == json!("ok")
            }),
            "conversation_open 应在顶层收尾：{first}"
        );

        let second = session.call(&command, NOW);
        assert_eq!(second["ok"], json!(true));
        assert_eq!(second["operation"], json!("id-1"));
        assert!(
            !events_of(&second)
                .iter()
                .any(|event| event["type"] == json!("conversation_opened")),
            "同 key 不应重复登记、也不该再发 conversation_opened：{second}"
        );
        assert!(session.layer.contains(BridgeSessionId(bridge), "id-1"));
    }

    /// §13 的**路由**：属于某 conversation 的通道事件变成 `conversation_event`；
    /// 谁都不认识的 id 仍按 §7.3 裸透传（两条都要测）。
    #[test]
    fn channel_events_are_routed_to_their_conversation_and_others_pass_through() {
        let mut session = session();
        let (bridge, wire, _) = open_bridge_ready(&mut session);
        session.call(&conversation_open_cmd(bridge), NOW);

        // 握手：对话层发出 helloConversationV4（通道 id 由桥的 ChannelClient 分配）。
        let opened = session.call(&conversation_call_cmd(bridge, "id-1", "handshake", "{}"), NOW);
        assert_eq!(opened["ok"], json!(true));
        assert_eq!(opened["operation"], json!("conv:id-1:handshake:1"));
        assert!(
            events_of(&opened).iter().any(|event| {
                event["type"] == json!("conversation_event")
                    && event["event"]["type"] == json!("opAccepted")
                    && event["event"]["op"] == json!("handshake")
            }),
            "应有 opAccepted 对话事件：{opened}"
        );
        let (hello, _) = last_channel_call(events_of(&opened));
        let hello_id = hello[1].as_u64().expect("id 应是数字");

        // 该 id 的应答属于这个 conversation → 变成 conversation_event，**不是**裸 channel。
        let step = session.step(
            &text_frame(&desktop_channel_response(
                &wire,
                hello_id,
                json!({"connectionId": "conn-1"}),
            )),
            NOW,
        );
        assert!(
            find(events_of(&step), "channel").is_none(),
            "属于 conversation 的应答不该裸透传：{step}"
        );
        let (initialize, _) = last_channel_call(events_of(&step));
        let initialize_id = initialize[1].as_u64().expect("id 应是数字");

        // initialize 应答 → handshakeDone（证明这条链真的走到了对话层）。
        let step = session.step(
            &text_frame(&desktop_channel_response(&wire, initialize_id, json!({}))),
            NOW,
        );
        assert!(find(events_of(&step), "channel").is_none(), "{step}");
        let handshake = events_of(&step)
            .iter()
            .find(|event| event["event"]["type"] == json!("handshakeDone"))
            .unwrap_or_else(|| panic!("应有 handshakeDone：{step}"));
        assert_eq!(handshake["key"], json!("id-1"));
        assert_eq!(handshake["session"], json!(bridge));
        assert_eq!(handshake["event"]["connectionId"], json!("conn-1"));

        // 不属于任何 conversation 的通道事件（这里是定时任务层的应答）：仍按 §7.3 裸透传，
        // **一条都不能丢** —— 即使本层同时用它把定时任务调用收尾。
        let automation = session.call(
            &format!(r#"{{"method":"automation_call","session":{bridge},"op":"list","args":{{}}}}"#),
            NOW,
        );
        let (call, _) = last_channel_call(events_of(&automation));
        let call_id = call[1].as_u64().expect("id 应是数字");
        let passthrough = session.step(
            &text_frame(&desktop_channel_response(
                &wire,
                call_id,
                json!([{"automationId": "a-1"}]),
            )),
            NOW,
        );
        let bare = find(events_of(&passthrough), "channel").unwrap_or_else(|| {
            panic!("不属于任何 conversation 的通道事件必须裸透传：{passthrough}")
        });
        assert_eq!(bare["event"]["type"], json!("promise_success"));
        assert_eq!(bare["event"]["id"], json!(call_id));
        assert_eq!(bare["event"]["data"], json!([{"automationId": "a-1"}]));
        assert!(
            find(events_of(&passthrough), "conversation_event").is_none(),
            "裸透传的通道事件不该同时进 conversation：{passthrough}"
        );
        assert!(
            events_of(&passthrough)
                .iter()
                .any(|event| event["type"] == json!("completed")),
            "同一份应答应同时把定时任务调用收尾：{passthrough}"
        );
    }

    /// **token 隔离**：核心与对话层的 `TimerId` 都从 1 开始，回喂 `timer_fired` 不得串号。
    ///
    /// 构造：核心侧第一个定时器是 `request` 的 30s 超时（核心 token = 1）；对话层第一个定时器
    /// 是订阅的分片清理周期（对话层 token 也是 1）。两者必须拿到**不同**的宿主 token，且各自
    /// 触发到正确的对象。
    #[test]
    fn core_and_conversation_timers_do_not_collide() {
        let mut session = session();
        // 核心的第一个定时器：`request` 的请求超时（`matchTypes` 让它一直挂着）。
        let requested = session.call(
            r#"{"method":"request","payload":{"requestId":"r-1","zcode_type":"custom"},"matchTypes":["custom-response"],"matchFields":{"requestId":"r-1"},"timeoutMs":30000}"#,
            NOW,
        );
        let core_token = requested["operation"].as_str().expect("应有令牌").to_string();
        let core_host = find(events_of(&requested), "timer_start")
            .expect("request 应有超时定时器")["token"]
            .as_u64()
            .expect("token 应是数字");
        assert_eq!(core_host, 1, "核心的第一个定时器应拿到宿主 token 1");

        let (bridge, _wire, _) = open_bridge_ready(&mut session);
        session.call(&conversation_open_cmd(bridge), NOW);
        finish_handshake(&mut session, bridge, "bridge-1");

        // 对话层的第一个定时器：订阅的分片清理周期（对话层本地 token = 1）。
        let subscribed = session.call(
            &conversation_call_cmd(bridge, "id-1", "subscribe", r#"{"sessionId":"s-1"}"#),
            NOW,
        );
        assert_eq!(subscribed["ok"], json!(true));
        let conv_host = events_of(&subscribed)
            .iter()
            .find(|event| {
                event["type"] == json!("conversation_event")
                    && event["event"]["type"] == json!("timerStart")
            })
            .unwrap_or_else(|| panic!("订阅应起分片清理周期：{subscribed}"))["event"]["token"]
            .as_u64()
            .expect("token 应是数字");
        assert_ne!(
            core_host, conv_host,
            "核心与对话层的 token 必须隔离（两边的本地 token 都是 1）"
        );

        // 回喂**核心**的 token：命中核心的请求超时，不产生任何对话事件。
        let fired = session.step(
            &format!(r#"{{"type":"timer_fired","token":{core_host}}}"#),
            NOW + 1,
        );
        assert!(
            find(events_of(&fired), "conversation_event").is_none(),
            "核心的定时器不该唤醒对话层：{fired}"
        );
        let done = events_of(&fired)
            .iter()
            .find(|event| event["type"] == json!("completed"))
            .unwrap_or_else(|| panic!("核心的请求超时应完成：{fired}"));
        assert_eq!(done["token"], json!(core_token));
        assert_eq!(done["outcome"]["kind"], json!("failed"));

        // 回喂**对话层**的 token：命中分片清理周期（周期定时器重新装弹，宿主 token 不变）。
        let fired = session.step(
            &format!(r#"{{"type":"timer_fired","token":{conv_host}}}"#),
            NOW + 1,
        );
        let reload = events_of(&fired)
            .iter()
            .find(|event| {
                event["type"] == json!("conversation_event")
                    && event["event"]["type"] == json!("timerStart")
            })
            .unwrap_or_else(|| panic!("对话层的周期定时器应重新装弹：{fired}"));
        assert_eq!(reload["event"]["token"], json!(conv_host));
        assert!(
            !events_of(&fired)
                .iter()
                .any(|event| event["type"] == json!("completed")),
            "对话层的定时器不该完成核心的请求：{fired}"
        );
    }

    /// §13：桥降级时对话层要 `waitHealthy` —— 本层自己调 `bridge_wait_healthy`，
    /// 并把它的完成/超时喂回去（`ConversationInput::BridgeHealthy` / `BridgeHealthyTimeout`）。
    #[test]
    fn degraded_bridge_makes_the_conversation_wait_healthy_and_time_out() {
        let mut session = session();
        let (bridge, wire, _) = open_bridge_ready(&mut session);
        session.call(&conversation_open_cmd(bridge), NOW);
        finish_handshake(&mut session, bridge, &wire);

        // 桥降级（§7.3 的 `bridge_degraded`）。
        let degraded = session.step(
            &text_frame(
                &json!({
                    "type": "data",
                    "payload": {
                        "zcode_type": "bridge-degraded",
                        "bridgeSessionId": wire,
                        "reason": "socket-lost",
                    },
                })
                .to_string(),
            ),
            NOW,
        );
        assert!(
            events_of(&degraded).iter().any(|event| {
                event["type"] == json!("bridge_degraded") && event["session"] == json!(bridge)
            }),
            "降级必须照常给宿主：{degraded}"
        );

        // 降级中发命令：对话层先要 `waitHealthy`，本层据此调核心的 `bridge_wait_healthy`
        // （表现为一条 45s 的 `timer_start`），命令**先不发**。
        let sent = session.call(
            &conversation_call_cmd(
                bridge,
                "id-1",
                "sendText",
                r#"{"sessionId":"s-1","text":"hi"}"#,
            ),
            NOW,
        );
        assert_eq!(sent["ok"], json!(true));
        let healthy = find(events_of(&sent), "timer_start")
            .unwrap_or_else(|| panic!("waitHealthy 应起 45s 超时定时器：{sent}"));
        assert_eq!(healthy["delayMs"], json!(45_000));
        let healthy_host = healthy["token"].as_u64().expect("token 应是数字");
        assert!(
            find(events_of(&sent), "ws_send_text").is_none(),
            "桥降级时命令不该发出去：{sent}"
        );

        // 回喂超时：核心的 healthy 定时器到点 → 本层喂 `BridgeHealthyTimeout` → op 以 timeout 收尾。
        let timed_out = session.step(
            &format!(r#"{{"type":"timer_fired","token":{healthy_host}}}"#),
            NOW + 45_000,
        );
        let done = events_of(&timed_out)
            .iter()
            .find(|event| event["type"] == json!("completed"))
            .unwrap_or_else(|| panic!("应以 completed 收尾：{timed_out}"));
        assert_eq!(done["token"], json!("conv:id-1:sendText:2"));
        assert_eq!(done["outcome"]["kind"], json!("failed"));
        assert_eq!(done["outcome"]["error"]["kind"], json!("timeout"));
        assert!(
            done["outcome"]["error"]["message"]
                .as_str()
                .unwrap()
                .contains("bridge 恢复超时"),
            "{done}"
        );
    }

    /// §13.3：`automation_call` 的 `list` 发出的通道调用逐字段正确，应答经顶层 `completed` 返回。
    #[test]
    fn automation_list_sends_the_channel_call_and_completes_with_its_value() {
        let mut session = session();
        let (bridge, _wire, _) = open_bridge_ready(&mut session);
        let envelope = session.call(
            &format!(r#"{{"method":"automation_call","session":{bridge},"op":"list","args":{{}}}}"#),
            NOW,
        );
        assert_eq!(envelope["ok"], json!(true));
        let token = envelope["operation"].as_str().expect("应有令牌");
        assert!(token.starts_with("automation:list:"), "token={token}");

        // 反解 `ws_send_text`：IPC 头 `[100, id, "zcode-agent", "listAllAutomations"]` + 参数。
        let (call, args) = last_channel_call(events_of(&envelope));
        assert_eq!(call[0], json!(100), "reqType 应是 reqPromise");
        assert_eq!(call[2], json!("zcode-agent"));
        assert_eq!(call[3], json!("listAllAutomations"));
        // 参数是 `[{workspacePath: <桥的 workspaceKey>}]`（Dart 的 `_scopeMap`）。
        assert_eq!(args[0]["workspacePath"], json!("wk-1"));
        let call_id = call[1].as_u64().expect("id 应是数字");

        // 应答 → 顶层 `completed`，`outcome.value` 是原始条目数组。
        let step = session.step(
            &text_frame(&desktop_channel_response(
                "bridge-1",
                call_id,
                json!([{"automationId": "a-1", "title": "日报", "cronExpr": "0 9 * * *"}, "junk"]),
            )),
            NOW,
        );
        let done = events_of(&step)
            .iter()
            .find(|event| event["type"] == json!("completed"))
            .unwrap_or_else(|| panic!("应有 completed：{step}"));
        assert_eq!(done["token"], json!(token));
        assert_eq!(done["outcome"]["kind"], json!("payload"));
        assert_eq!(
            done["outcome"]["value"],
            json!([{"automationId": "a-1", "title": "日报", "cronExpr": "0 9 * * *"}]),
            "`list` 的条目数组应丢掉非 Map 元素（对应 Dart 的 whereType<Map>）"
        );
    }

    /// §13.3：其余定时任务 `op` 的方法名与载荷（`scope` 可显式覆盖，否则回落到桥的 workspaceKey）。
    #[test]
    fn automation_mutations_send_their_payloads() {
        let mut session = session();
        let (bridge, _wire, _) = open_bridge_ready(&mut session);

        let enabled = session.call(
            &format!(
                r#"{{"method":"automation_call","session":{bridge},"op":"setEnabled","args":{{"automationId":"a-1","enabled":false}}}}"#
            ),
            NOW,
        );
        let (call, args) = last_channel_call(events_of(&enabled));
        assert_eq!(call[3], json!("setAutomationEnabled"));
        assert_eq!(
            args[0],
            json!({"workspacePath": "wk-1", "automationId": "a-1", "enabled": false})
        );

        let created = session.call(
            &format!(
                r#"{{"method":"automation_call","session":{bridge},"op":"create","args":{{"title":"t","prompt":"p","cronExpr":"0 9 * * *","model":"glm-4","provider":"glm","mode":"build","thoughtLevel":"high","recurring":true,"scheduleRule":{{"unit":"day","interval":1,"hour":9,"minute":0}}}}}}"#
            ),
            NOW,
        );
        let (call, args) = last_channel_call(events_of(&created));
        assert_eq!(call[3], json!("createAutomation"));
        assert_eq!(args[0]["title"], json!("t"));
        assert_eq!(
            args[0]["scheduleRule"],
            json!({"unit": "day", "interval": 1, "hour": 9, "minute": 0})
        );
        // `enabled` 缺省时是 Dart 的默认值 `true`。
        assert_eq!(args[0]["enabled"], json!(true));

        // 显式 `scope` 覆盖回落值（§13.3 没规定 scope 从哪来，本层两种都收）。
        let scoped = session.call(
            &format!(
                r#"{{"method":"automation_call","session":{bridge},"op":"list","args":{{"scope":{{"workspacePath":"/other","workspaceIdentity":"id-9"}}}}}}"#
            ),
            NOW,
        );
        let (_, args) = last_channel_call(events_of(&scoped));
        assert_eq!(
            args[0],
            json!({"workspacePath": "/other", "workspaceIdentity": "id-9"})
        );
    }

    /// §13.3：定时任务调用**没有应答**时，deadline 到点由本层收尾（否则宿主的 `Deferred` 永挂）。
    #[test]
    fn automation_call_times_out_when_no_response_arrives() {
        let mut session = session();
        let (bridge, _wire, _) = open_bridge_ready(&mut session);
        let envelope = session.call(
            &format!(r#"{{"method":"automation_call","session":{bridge},"op":"list","args":{{}}}}"#),
            NOW,
        );
        let token = envelope["operation"].as_str().expect("应有令牌").to_string();

        // 还没到期：什么也不发。
        let early = session.step(r#"{"type":"poke"}"#, NOW + 1_000);
        assert!(
            !events_of(&early)
                .iter()
                .any(|event| event["type"] == json!("completed")),
            "{early}"
        );

        // 30s 后（`AutomationClient::LIST_TIMEOUT_MS`）→ 顶层 `completed` 超时。
        let late = session.step(r#"{"type":"poke"}"#, NOW + 30_000);
        let done = events_of(&late)
            .iter()
            .find(|event| event["type"] == json!("completed"))
            .unwrap_or_else(|| panic!("应有超时的 completed：{late}"));
        assert_eq!(done["token"], json!(token));
        assert_eq!(done["outcome"]["kind"], json!("failed"));
        assert_eq!(done["outcome"]["error"]["kind"], json!("timeout"));
        assert!(
            done["outcome"]["error"]["message"]
                .as_str()
                .unwrap()
                .contains("listAllAutomations"),
            "{done}"
        );
    }

    /// §13：未知 `op` → `badInput`；未知 conversation key → `invalidState`。
    #[test]
    fn unknown_op_is_bad_input_and_unknown_key_is_invalid_state() {
        let mut session = session();
        let (bridge, _wire, _) = open_bridge_ready(&mut session);
        session.call(&conversation_open_cmd(bridge), NOW);

        let unknown_op = session.call(
            &conversation_call_cmd(bridge, "id-1", "teleport", "{}"),
            NOW,
        );
        assert_eq!(unknown_op["ok"], json!(false));
        assert_eq!(unknown_op["error"]["kind"], json!("badInput"));
        assert!(
            unknown_op["error"]["message"]
                .as_str()
                .unwrap()
                .contains("teleport"),
            "{unknown_op}"
        );

        let unknown_key = session.call(
            &conversation_call_cmd(bridge, "nope", "handshake", "{}"),
            NOW,
        );
        assert_eq!(unknown_key["ok"], json!(false));
        assert_eq!(unknown_key["error"]["kind"], json!("invalidState"));

        let unknown_close =
            session.call(&format!(r#"{{"method":"conversation_close","session":{bridge},"key":"nope"}}"#), NOW);
        assert_eq!(unknown_close["error"]["kind"], json!("invalidState"));

        let unknown_automation = session.call(
            &format!(r#"{{"method":"automation_call","session":{bridge},"op":"teleport","args":{{}}}}"#),
            NOW,
        );
        assert_eq!(unknown_automation["error"]["kind"], json!("badInput"));

        // 参数不符也是 `badInput`（§13.5.1）。
        let bad_args = session.call(
            &conversation_call_cmd(bridge, "id-1", "sendText", r#"{"sessionId":"s-1"}"#),
            NOW,
        );
        assert_eq!(bad_args["error"]["kind"], json!("badInput"), "{bad_args}");
    }

    /// §13：`conversation_close` 之后同 key 不可再用；`dispose` 把 conversation 一并释放。
    #[test]
    fn close_and_dispose_release_conversations() {
        let mut session = session();
        let (bridge, _wire, _) = open_bridge_ready(&mut session);
        session.call(&conversation_open_cmd(bridge), NOW);
        assert!(session.layer.contains(BridgeSessionId(bridge), "id-1"));

        let closed = session.call(
            &format!(r#"{{"method":"conversation_close","session":{bridge},"key":"id-1"}}"#),
            NOW,
        );
        assert_eq!(closed["ok"], json!(true));
        assert!(!session.layer.contains(BridgeSessionId(bridge), "id-1"));
        let after_close = session.call(&conversation_call_cmd(bridge, "id-1", "handshake", "{}"), NOW);
        assert_eq!(after_close["error"]["kind"], json!("invalidState"));

        // 重新 open 可以（key 相同，但是全新的状态机）。
        session.call(&conversation_open_cmd(bridge), NOW);
        assert!(session.layer.contains(BridgeSessionId(bridge), "id-1"));

        // §10 dispose：会话销毁后 conversation 也一并释放。
        let disposed = session.dispose(NOW);
        assert_eq!(disposed["ok"], json!(true));
        assert!(session.layer.is_empty());
        let after_dispose = session.call(&conversation_call_cmd(bridge, "id-1", "handshake", "{}"), NOW);
        assert_eq!(after_dispose["error"]["kind"], json!("invalidState"));
    }

    /// §13：`conversation_close` / `dispose` 必须把宿主那侧的定时器**逐个取消**（不能留悬挂计时器）。
    #[test]
    fn closing_a_conversation_cancels_its_host_timers() {
        let mut session = session();
        let (bridge, _wire, _) = open_bridge_ready(&mut session);
        session.call(&conversation_open_cmd(bridge), NOW);
        finish_handshake(&mut session, bridge, "bridge-1");
        let subscribed = session.call(
            &conversation_call_cmd(bridge, "id-1", "subscribe", r#"{"sessionId":"s-1"}"#),
            NOW,
        );
        let conv_host = events_of(&subscribed)
            .iter()
            .find(|event| {
                event["type"] == json!("conversation_event")
                    && event["event"]["type"] == json!("timerStart")
            })
            .expect("订阅应起分片清理周期")["event"]["token"]
            .as_u64()
            .unwrap();

        let closed = session.call(
            &format!(r#"{{"method":"conversation_close","session":{bridge},"key":"id-1"}}"#),
            NOW,
        );
        let cancelled: Vec<u64> = events_of(&closed)
            .iter()
            .filter(|event| event["type"] == json!("conversation_event")
                && event["event"]["type"] == json!("timerCancel"))
            .map(|event| event["event"]["token"].as_u64().unwrap())
            .collect();
        assert!(
            cancelled.contains(&conv_host),
            "关闭时应取消该对话的宿主定时器 {conv_host}：{closed}"
        );
    }

    // ---- §9 查询 ----

    #[test]
    fn snapshot_has_the_frozen_shape() {
        let session = session();
        let snapshot = session.snapshot();
        assert_eq!(snapshot["disposed"], json!(false));
        assert_eq!(snapshot["relay"]["state"], json!("idle"));
        assert_eq!(snapshot["relay"]["wasPaired"], json!(false));
        assert_eq!(
            snapshot["deviceInfo"],
            json!({"platform": "android", "version": "3.6.5", "name": "zemote"})
        );
        assert_eq!(
            snapshot["params"],
            json!({
                "deviceSid": "SID123",
                "deviceMid": "MID-1",
                "deviceName": "DESKTOP",
                "appVersion": "3.6.5",
                "theme": null,
                "timestamp": 1785734607338_i64,
            })
        );
        assert_eq!(snapshot["bridges"], json!([]));
        assert_eq!(snapshot["recovering"], json!([]));
    }

    /// **安全约定（§9）：`passHash` 绝不出现在任何返回值里。**
    #[test]
    fn snapshot_never_contains_pass_hash() {
        let session = session();
        let snapshot = session.snapshot();
        let text = serde_json::to_string(&snapshot).expect("必须能序列化");
        assert!(
            !text.contains("HASH="),
            "snapshot 泄漏了 passHash: {text}"
        );
        assert!(!text.contains("passHash"), "snapshot 出现了 passHash 键: {text}");
        assert!(!text.contains("pass_hash"), "snapshot 出现了 pass_hash 键: {text}");
    }

    /// 查询信封形状与未知 query 的拒绝。
    #[test]
    fn query_snapshot_envelope_and_unknown_query() {
        let session = session();
        let envelope = session.query(r#"{"query":"snapshot"}"#);
        assert_eq!(envelope["ok"], json!(true));
        assert!(envelope["snapshot"].is_object());

        let unknown = session.query(r#"{"query":"everything"}"#);
        assert_eq!(unknown["ok"], json!(false));
        assert_eq!(unknown["error"]["kind"], json!("badInput"));
        assert!(unknown["error"]["message"]
            .as_str()
            .unwrap()
            .contains("everything"));

        assert_eq!(session.query("{}")["error"]["kind"], json!("badInput"));
        assert_eq!(session.query("oops")["error"]["kind"], json!("badInput"));
    }

    // ---- §10 销毁 ----

    #[test]
    fn dispose_closes_the_relay_and_marks_the_session() {
        let mut session = session();
        session.step(r#"{"type":"start"}"#, 0);
        // 必须真的建连成功（`socket_ready`）核心才持有 socket 句柄，dispose 时才会关它。
        session.step(r#"{"type":"socket_ready","generation":1}"#, 0);
        let envelope = session.dispose(0);
        assert_eq!(envelope["ok"], json!(true));
        let close = find(events_of(&envelope), "ws_close").expect("应有 ws_close");
        assert_eq!(close["generation"], json!(1));
        assert_eq!(close["code"], json!(null));
        assert_eq!(close["reason"], json!(null));
        assert_eq!(session.snapshot()["disposed"], json!(true));
    }

    /// 只发过 `start`（建连仍在飞）时核心没有 socket 句柄，dispose 不该产生 `ws_close`。
    #[test]
    fn dispose_before_socket_ready_emits_no_close() {
        let mut session = session();
        session.step(r#"{"type":"start"}"#, 0);
        let envelope = session.dispose(0);
        assert_eq!(envelope["ok"], json!(true));
        assert!(find(events_of(&envelope), "ws_close").is_none());
    }

    #[test]
    fn dispose_on_a_fresh_session_emits_no_close() {
        let mut session = session();
        let envelope = session.dispose(0);
        assert_eq!(envelope["ok"], json!(true));
        assert!(find(events_of(&envelope), "ws_close").is_none());
        assert_eq!(session.snapshot()["disposed"], json!(true));
    }

    // ---- 观察量的定义 ----
    //
    // 这里原先有一组 `Observations` 的测试（靠观察事件流推导 `wasPaired` / `recovering`）。
    // 那两个量现在直接读协议 crate 的 `RelayClient::was_paired()` / `ZemoteClient::recovering()`，
    // 推导逻辑与其测试一并删除 —— 核心自己那侧已有测试覆盖。
    // 快照是否反映真实状态由 `pairing_marks_snapshot_was_paired` 这类端到端测试保证。
}

