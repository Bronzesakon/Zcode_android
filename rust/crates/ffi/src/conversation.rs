//! §13：对话层（`conversation_open` / `conversation_call` / `conversation_close`）与定时任务层
//! （`automation_call`）的接线。
//!
//! # 这一层做什么
//!
//! 协议 crate 里 [`ConversationTransport`] 与 [`AutomationClient`] 都是**纯状态机**：它们
//! 要么要求一个 `&mut dyn ChannelRpc`（桥的通道面），要么要求宿主回喂能力结果。本模块把那
//! 两条线接起来，让宿主只认 §6 的输入 / §7 的事件 + §13.4 的 `conversation_event`：
//!
//! | 协议层要的东西 | 本模块怎么给 |
//! |---|---|
//! | `ConversationTransport::step` 的 `channels` | 直接拿桥的 `ChannelClient`（`BridgeSession::channels_mut`） |
//! | `ConversationInput::Channel` | 核心的 `ZemoteEvent::Channel` 按 `owns_channel_id` 路由过来 |
//! | `ConversationInput::TimerFired` | 宿主回喂的 `timer_fired`（经本模块的 token 重映射还原） |
//! | `ConversationInput::BridgeRecovered` / `BridgeDegraded` | 核心的同名事件 |
//! | `ConversationInput::ListenerRegistered` | 本模块自己调 `ChannelClient::add_listener`（宿主拿不到通道 id） |
//! | `ConversationInput::ChannelTimeout` | 本模块按 `nowMs` 记的 deadline 到期 → `remove_pending` + 回喂 |
//! | `ConversationInput::BridgeHealthy` / `BridgeHealthyTimeout` | 本模块自己调 `ZemoteClient::bridge_wait_healthy`，用它的 `completed` 收尾 |
//!
//! **§13.5.2 里那张「宿主回喂输入」的表按内部输入实现**：`channel` / `timerFired` /
//! `bridgeRecovered` / `bridgeDegraded` / `listenerRegistered` / `channelTimeout` /
//! `bridgeHealthy` / `bridgeHealthyTimeout` 全部由本模块在 Rust 侧自行构造，**不新增任何
//! `nativeStep` 输入类型**（§6 的输入表不变）。原因有两条：宿主拿不到桥 `ChannelClient`
//! 的内部 id（`add_listener` / `remove_pending` 都要它），以及 Kotlin 引擎层已按现有契约
//! 实现并测好，改输入表会作废它。
//!
//! # token 命名空间隔离（本任务最容易出错的地方）
//!
//! 核心的 `TimerId` 与对话层的 `TimerId` 是**两套独立计数器**（都从 1 开始），直接透传必然
//! 撞号——宿主会把 A 的定时器触发喂给 B。本模块因此**统一重映射**（核心的也走映射，做法
//! 一致）：
//!
//! * 对外：`timer_start` / `timer_cancel` 一律发本模块分配的**全局唯一 u64**；
//! * 收 `timer_fired{token}`：查表还原成「核心的 token」或「某 conversation 的 token」；
//! * 完成令牌：对话层 op token（形如 `sendText:4`）加 `conv:<key>:` 前缀；定时任务层加
//!   `automation:<op>:` 前缀。两者与核心的 `bootstrap-<uuid>` / `pair:3` / `healthy:7` /
//!   conversation key 都不可能相等。
//!
//! # 事件路由（§13.4）
//!
//! 核心发 `ZemoteEvent::Channel(ev)` 时，本模块按 [`ConversationTransport::owns_channel_id`]
//! 判断它属于哪个 conversation：属于的喂给它（作为 `ConversationInput::Channel`），**其余的
//! 仍按 §7.3 作为裸 `channel` 事件透传给宿主——一条都不丢**。

use std::collections::{BTreeMap, VecDeque};

use serde_json::{json, Map, Value};

use zemote_protocol::automation_client::{
    parse_automation_list, AutomationClient, CreateAutomationParams,
};
use zemote_protocol::channel_client::{to_dart_view, ChannelEvent};
use zemote_protocol::conversation::CONVERSATION_PROTOCOL_APP_VERSION;
use zemote_protocol::conversation_transport::{
    ConversationEvent, ConversationInput, ConversationTransport,
};
use zemote_protocol::error::ProtocolError;
use zemote_protocol::prelude::ChannelRpc;
use zemote_protocol::zemote_client::{
    BridgeSessionId, Completion, TimerId, ZemoteClient, ZemoteError, ZemoteEvent,
};

use crate::codec::{self, ConversationTokens};

// ===========================================================================
// 1. 错误
// ===========================================================================

/// 本层的失败 —— 映射到 §3 的 `error.kind`。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LayerError {
    /// 入参不合法（未知 `op`、必填字段缺失/类型不符）→ `badInput`。
    BadInput(String),
    /// 状态不允许（未知桥会话、未知 conversation key）→ `invalidState`。
    InvalidState(String),
    /// 协议层错误 → `protocol`。
    Protocol(String),
}

impl LayerError {
    /// §3 的失败信封。
    pub fn envelope(&self) -> Value {
        match self {
            LayerError::BadInput(message) => codec::bad_input(message.clone()),
            LayerError::InvalidState(message) => codec::err("invalidState", message.clone(), Vec::new()),
            LayerError::Protocol(message) => codec::err("protocol", message.clone(), Vec::new()),
        }
    }
}

/// 协议 crate 的 `ProtocolError` → 本层错误。
///
/// `Other` 是 `ConversationTransport::call_op` 报「未知 op / 参数不符」用的（§13.5.1 要求
/// 一律 `badInput`），`InvalidState` 直译，其余（帧格式、JSON、UTF-8…）归 `protocol`。
fn protocol_error_to_layer(error: ProtocolError) -> LayerError {
    match error {
        ProtocolError::InvalidState(message) => LayerError::InvalidState(message),
        ProtocolError::Other(message) => LayerError::BadInput(message),
        other => LayerError::Protocol(other.to_string()),
    }
}

/// 门面的 `ZemoteError` → 本层错误。
fn zemote_error_to_layer(error: ZemoteError) -> LayerError {
    match error {
        ZemoteError::InvalidState(message) => LayerError::InvalidState(message),
        ZemoteError::Timeout(message) => LayerError::Protocol(message),
        ZemoteError::Protocol(message) => LayerError::Protocol(message),
    }
}

// ===========================================================================
// 2. token 重映射
// ===========================================================================

/// 一个宿主 token 指向谁。
///
/// `Ord` 让 [`TimerTokens`] 能同时维护正向与反向两张表（`TimerId` / `BridgeSessionId` 都
/// 是 `Ord`）。
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
enum TimerRoute {
    /// 门面自己的定时器（`ZemoteEvent::TimerStart` / `TimerCancel`）。
    Core(TimerId),
    /// 某个 conversation 的定时器（`ConversationEvent::TimerStart` / `TimerCancel`）。
    Conversation {
        bridge: BridgeSessionId,
        key: String,
        token: TimerId,
    },
}

/// 宿主 token 分配器 + 正反两张路由表。
///
/// **同一个 route 反复 `start` 会拿到同一个宿主 token** —— 那正是周期定时器「重新装弹」的
/// 语义（§13.5.2：同一 token 再次出现 = 重新装弹）。
#[derive(Debug, Default)]
struct TimerTokens {
    next: u64,
    forward: BTreeMap<u64, TimerRoute>,
    reverse: BTreeMap<TimerRoute, u64>,
}

impl TimerTokens {
    /// 分配（或复用）一个宿主 token。
    fn start(&mut self, route: TimerRoute) -> u64 {
        if let Some(host) = self.reverse.get(&route) {
            return *host;
        }
        self.next += 1;
        let host = self.next;
        self.forward.insert(host, route.clone());
        self.reverse.insert(route, host);
        host
    }

    /// 撤销一个 route，返回它的宿主 token（宿主据此取消计时器）。
    ///
    /// `None` = 这个 route 从没对外发过（此时不该发 `timer_cancel`：发一条指向不存在 token
    /// 的取消只会误导宿主）。
    fn cancel(&mut self, route: &TimerRoute) -> Option<u64> {
        let host = self.reverse.remove(route)?;
        self.forward.remove(&host);
        Some(host)
    }

    /// 宿主 token → route。
    fn route(&self, host: u64) -> Option<&TimerRoute> {
        self.forward.get(&host)
    }

    /// 清掉某个 conversation 的全部路由（`conversation_close` 用）。
    fn forget_conversation(&mut self, bridge: BridgeSessionId, key: &str) -> Vec<u64> {
        let hosts: Vec<u64> = self
            .forward
            .iter()
            .filter(|(_, route)| {
                matches!(
                    route,
                    TimerRoute::Conversation { bridge: b, key: k, .. } if *b == bridge && k == key
                )
            })
            .map(|(host, _)| *host)
            .collect();
        for host in &hosts {
            if let Some(route) = self.forward.remove(host) {
                self.reverse.remove(&route);
            }
        }
        hosts
    }
}

/// 对话层 op token → 宿主 token。
///
/// 加 `conv:<key>:` 前缀，与核心的令牌（`bootstrap-<uuid>` / `pair:3` / `healthy:7` /
/// conversation key）**不可能相等**。
fn conversation_op_token(key: &str, token: &str) -> String {
    format!("conv:{key}:{token}")
}

/// 给 [`ConversationTokens`] 用的适配器：把对话层的 token 映射到宿主 token。
struct ConvTokens<'a> {
    timers: &'a mut TimerTokens,
    bridge: BridgeSessionId,
    key: String,
}

impl ConvTokens<'_> {
    fn route(&self, token: TimerId) -> TimerRoute {
        TimerRoute::Conversation {
            bridge: self.bridge,
            key: self.key.clone(),
            token,
        }
    }
}

impl ConversationTokens for ConvTokens<'_> {
    fn start_timer(&mut self, token: TimerId) -> u64 {
        self.timers.start(self.route(token))
    }

    fn cancel_timer(&mut self, token: TimerId) -> Option<u64> {
        self.timers.cancel(&self.route(token))
    }

    fn op_token(&mut self, token: &str) -> String {
        conversation_op_token(&self.key, token)
    }
}

// ===========================================================================
// 3. 挂起登记
// ===========================================================================

/// 一次已发出、还在等应答的通道调用的用途。
#[derive(Debug, Clone, PartialEq, Eq)]
enum CallKind {
    /// 对话层发的（应答要喂回 `ConversationInput::Channel`）。
    Conversation {
        /// 哪个 conversation。
        key: String,
    },
    /// 定时任务层发的（应答要变成顶层 `completed`）。
    Automation {
        /// 宿主 token。
        token: String,
        /// 线上方法名（超时文案用）。
        method: &'static str,
    },
    /// 通用通道调用（`channel_call`）：应答要变成顶层 `completed`。
    Channel {
        /// 宿主 token。
        token: String,
        /// 通道名（超时文案用）。
        channel: String,
        /// 方法名（超时文案用）。
        method: String,
    },
}

/// 一次已发出的通道调用。
#[derive(Debug, Clone, PartialEq, Eq)]
struct PendingCall {
    /// 到期时刻（Unix 毫秒，由入参 `nowMs` 推）。
    at_ms: i64,
    /// 用途。
    kind: CallKind,
}

/// 挂起的 `waitHealthy`（宿主令牌 ↔ 对话层的 healthy token）。
#[derive(Debug, Clone)]
struct HealthyWait {
    bridge: BridgeSessionId,
    key: String,
    /// 对话层 `WaitHealthy` 事件里的 token。
    token: u64,
}

/// 一个 conversation 的持有物。
struct ConversationEntry {
    /// 协议状态机（`rust/crates/protocol/src/conversation_transport.rs`）。
    transport: ConversationTransport,
}

// ===========================================================================
// 4. 层
// ===========================================================================

/// §13 的全部状态：按 (桥句柄, conversation key) 索引的 conversation、token 路由、挂起调用。
#[derive(Default)]
pub struct ConversationLayer {
    /// conversation 表。`BTreeMap` 让路由遍历顺序确定（同一 id 被多个 conversation 认领时
    /// 取先出现的那个——正常情况下同一桥的通道 id 全局唯一，不会出现）。
    conversations: BTreeMap<(BridgeSessionId, String), ConversationEntry>,
    /// 宿主 token 路由。
    timers: TimerTokens,
    /// 已发出、等应答的通道调用：`(桥, 通道 id)` → 用途 + 到期时刻。
    calls: BTreeMap<(BridgeSessionId, u64), PendingCall>,
    /// 挂起的 `waitHealthy`：核心的 op token → 对话层的 healthy token。
    healthy: BTreeMap<String, HealthyWait>,
    /// `automation:<op>:<n>` / conversation op 之外的本地序号（只需进程内唯一）。
    next_token: u64,
}

impl ConversationLayer {
    /// 新建空层。
    pub fn new() -> Self {
        Self::default()
    }

    /// 是否没有任何 conversation（诊断/测试用）。
    pub fn is_empty(&self) -> bool {
        self.conversations.is_empty()
    }

    /// 某个 conversation 是否已登记（诊断/测试用）。
    pub fn contains(&self, bridge: BridgeSessionId, key: &str) -> bool {
        self.conversations.contains_key(&(bridge, key.to_string()))
    }

    /// §9 扩展 `{"query":"conversation"}`：某个 conversation 的传输层快照。
    ///
    /// 核心的 `Session::snapshot` 只有 relay/桥状态；`conversations` 与 `sessionsIndex`
    /// （含 `lastAssistantPreview`）属于本层的 `ConversationTransport`，只能从这里取。
    /// 未登记（没 open 过、或已 close）时返回 `None`。
    pub fn snapshot(&self, bridge: BridgeSessionId, key: &str) -> Option<Value> {
        self.conversations
            .get(&(bridge, key.to_string()))
            .map(|entry| entry.transport.snapshot())
    }

    // -----------------------------------------------------------------------
    // 4.1 §13.2 对话层
    // -----------------------------------------------------------------------

    /// `conversation_open`：取 key（必要时新建 conversation），返回 key + 事件。
    ///
    /// 重复 open 同一个 key **不重复登记**（Dart 的 `putIfAbsent`）：第二个 transport 会让
    /// 桥上的两份状态机抢同一批通道 id。
    pub fn open(
        &mut self,
        client: &mut ZemoteClient,
        session: BridgeSessionId,
        scope: Map<String, Value>,
        now_ms: i64,
    ) -> Result<(String, Vec<Value>), LayerError> {
        // 核心是权威的 key 生成者（`conversation_key(scope)`），且它自己维护
        // `_conversations` 键集与 `ConversationOpened` 事件。
        let operation = client
            .conversation(session, scope.clone())
            .map_err(zemote_error_to_layer)?;
        let key = operation.token;
        let mut events = self.absorb_core_events(client, operation.events, now_ms);
        // §8 明说 `operation` 是「宿主拿它跟后续 `completed` 的 `token` 对上」的关联令牌。
        // `conversation_open` 的 `operation` 是 key（§13.2），若不给一条同 token 的顶层
        // `completed`，宿主那侧会一直等到自己的兜底超时才抛错（Kotlin 的 `invoke` 就是这么写的：
        // `operation != null` 就登记 `Deferred`）。这里立刻收尾（`ok` = 无返回值）。
        events.push(codec::completed_event(key.clone(), Completion::Ok));
        if !self.conversations.contains_key(&(session, key.clone())) {
            self.conversations.insert(
                (session, key.clone()),
                ConversationEntry {
                    transport: ConversationTransport::new(
                        scope,
                        CONVERSATION_PROTOCOL_APP_VERSION,
                    ),
                },
            );
        }
        self.pump(client, now_ms, &mut events);
        Ok((key, events))
    }

    /// `conversation_call`：把一个 `op` + `args` 透传给某个 conversation。
    ///
    /// 返回 `(该 op 的宿主令牌, 事件)`；未知 key → `invalidState`，未知 `op` → `badInput`。
    pub fn call(
        &mut self,
        client: &mut ZemoteClient,
        session: BridgeSessionId,
        key: &str,
        op: &str,
        args: Map<String, Value>,
        now_ms: i64,
    ) -> Result<(Option<String>, Vec<Value>), LayerError> {
        if !self.conversations.contains_key(&(session, key.to_string())) {
            return Err(LayerError::InvalidState(format!(
                "未知 conversation key: {key}（桥 {}）",
                session.0
            )));
        }
        let mut events = Vec::new();
        let mut work = VecDeque::new();
        let token = self.drive_op(
            client,
            session,
            key,
            op,
            &Value::Object(args),
            now_ms,
            &mut events,
            &mut work,
        )?;
        self.drain(client, now_ms, &mut events, &mut work);
        self.pump(client, now_ms, &mut events);
        Ok((token, events))
    }

    /// `conversation_close`：释放一个 conversation。
    ///
    /// 先让状态机自己 dispose（它会发 `timerCancel` / `unlistenChannel`，本层就地执行），
    /// 再清掉它的路由与挂起登记。之后同 key 的 `conversation_call` 报 `invalidState`。
    pub fn close(
        &mut self,
        client: &mut ZemoteClient,
        session: BridgeSessionId,
        key: &str,
        now_ms: i64,
    ) -> Result<Vec<Value>, LayerError> {
        let Some(mut entry) = self.conversations.remove(&(session, key.to_string())) else {
            return Err(LayerError::InvalidState(format!(
                "未知 conversation key: {key}（桥 {}）",
                session.0
            )));
        };
        let disposed = entry.transport.dispose(now_ms);
        let mut events = Vec::new();
        let mut work = VecDeque::new();
        self.translate(client, session, key, disposed, now_ms, &mut events, &mut work);
        self.drain(client, now_ms, &mut events, &mut work);
        // 兜底：状态机没撤到的路由一并撤掉（宿主不能留悬挂的计时器）。
        for host in self.timers.forget_conversation(session, key) {
            events.push(json!({ "type": "timer_cancel", "token": host }));
        }
        self.calls.retain(|(bridge, _), pending| {
            !(*bridge == session
                && matches!(&pending.kind, CallKind::Conversation { key: k } if k == key))
        });
        self.pump(client, now_ms, &mut events);
        Ok(events)
    }

    // -----------------------------------------------------------------------
    // 4.2 §13.3 定时任务层
    // -----------------------------------------------------------------------

    /// `automation_call`：把 `op` + `args` 透传给 `zcode-agent` 通道上的定时任务 RPC。
    ///
    /// 返回宿主令牌；应答到达时（或超时后）以顶层 `completed` 事件收尾（§13.3）。
    pub fn automation(
        &mut self,
        client: &mut ZemoteClient,
        session: BridgeSessionId,
        op: &str,
        args: Map<String, Value>,
        now_ms: i64,
    ) -> Result<(String, Vec<Value>), LayerError> {
        let scope = automation_scope(client, session, &args)?;
        let (call_id, method, timeout_ms) = {
            let bridge = client.bridge_mut(session).ok_or_else(|| {
                LayerError::InvalidState(format!("未知桥会话 {}", session.0))
            })?;
            let mut automation = AutomationClient::new(bridge.channels_mut(), &scope);
            match op {
                "list" => (
                    automation.list(),
                    "listAllAutomations",
                    AutomationClient::LIST_TIMEOUT_MS,
                ),
                "create" => (
                    automation.create(&create_params(&args)?),
                    "createAutomation",
                    AutomationClient::CREATE_TIMEOUT_MS,
                ),
                "setEnabled" => (
                    automation.set_enabled(
                        &req_str(&args, "automationId")?,
                        req_bool(&args, "enabled")?,
                    ),
                    "setAutomationEnabled",
                    AutomationClient::MUTATE_TIMEOUT_MS,
                ),
                "delete" => (
                    automation.delete(&req_str(&args, "automationId")?),
                    "deleteAutomation",
                    AutomationClient::MUTATE_TIMEOUT_MS,
                ),
                "runNow" => (
                    automation.run_now(&req_str(&args, "automationId")?),
                    "runAutomationNow",
                    AutomationClient::MUTATE_TIMEOUT_MS,
                ),
                "restart" => (
                    automation.restart(&req_str(&args, "automationId")?),
                    "restartAutomation",
                    AutomationClient::MUTATE_TIMEOUT_MS,
                ),
                other => {
                    return Err(LayerError::BadInput(format!("未知 automation op: {other}")));
                }
            }
        };
        let token = self.new_local_token(&format!("automation:{op}"));
        self.calls.insert(
            (session, call_id),
            PendingCall {
                at_ms: now_ms + timeout_ms,
                kind: CallKind::Automation {
                    token: token.clone(),
                    method,
                },
            },
        );
        let mut events = Vec::new();
        self.pump(client, now_ms, &mut events);
        Ok((token, events))
    }

    /// 通用通道调用（`channel_call`）：任意通道的任意方法。
    ///
    /// 与 [`ConversationLayer::automation`] 同一套收尾机制——应答变成一个顶层
    /// `completed`，`outcome.value` 是通道应答的原始 JSON。这样 `setting` /
    /// `window-controller` / `skills` / `plugins` 等通道不需要各写一层 FFI。
    ///
    /// `args` 是**位置参数数组**（`ChannelClient::call` 的签名），不是对象。
    pub fn channel_call(
        &mut self,
        client: &mut ZemoteClient,
        session: BridgeSessionId,
        channel: &str,
        method: &str,
        args: Vec<Value>,
        timeout_ms: i64,
        now_ms: i64,
    ) -> Result<(String, Vec<Value>), LayerError> {
        let ipc_args: Vec<zemote_protocol::ipc_codec::Value> =
            args.into_iter().map(Into::into).collect();
        let call_id = {
            let bridge = client.bridge_mut(session).ok_or_else(|| {
                LayerError::InvalidState(format!("未知桥会话 {}", session.0))
            })?;
            bridge
                .channels_mut()
                .call(channel, method, ipc_args, timeout_ms)
        };
        let token = self.new_local_token(&format!("channel:{channel}:{method}"));
        self.calls.insert(
            (session, call_id),
            PendingCall {
                at_ms: now_ms + timeout_ms,
                kind: CallKind::Channel {
                    token: token.clone(),
                    channel: channel.to_string(),
                    method: method.to_string(),
                },
            },
        );
        let mut events = Vec::new();
        self.pump(client, now_ms, &mut events);
        Ok((token, events))
    }

    // -----------------------------------------------------------------------
    // 4.3 输入：核心事件
    // -----------------------------------------------------------------------
    /// 把一批核心事件吃进来：路由通道事件、转喂桥状态、重映射定时器 token、透传其余。
    pub fn absorb_core_events(
        &mut self,
        client: &mut ZemoteClient,
        core_events: Vec<ZemoteEvent>,
        now_ms: i64,
    ) -> Vec<Value> {
        let mut events = Vec::new();
        let mut work: VecDeque<ZemoteEvent> = core_events.into();
        self.drain(client, now_ms, &mut events, &mut work);
        events
    }

    /// §6 的 `timer_fired`：宿主 token → 还原成核心或某个 conversation 的 token。
    ///
    /// 返回 `None` 表示这个 token 不是本层发出的（调用方按核心的 `TimerFired` 处理——虽然
    /// 核心的 token 也全部经本层重映射，那个分支只是兜底）。
    pub fn resolve_timer(&self, host: u64) -> Option<TimerFired> {
        match self.timers.route(host)? {
            TimerRoute::Core(token) => Some(TimerFired::Core(*token)),
            TimerRoute::Conversation { bridge, key, token } => {
                Some(TimerFired::Conversation {
                    bridge: *bridge,
                    key: key.clone(),
                    token: *token,
                })
            }
        }
    }

    /// §6 的 `timer_fired` 落到某个 conversation 时走这里（`resolve_timer` 已还原 token）。
    pub fn timer_fired(
        &mut self,
        client: &mut ZemoteClient,
        bridge: BridgeSessionId,
        key: &str,
        token: TimerId,
        now_ms: i64,
    ) -> Vec<Value> {
        let mut events = Vec::new();
        let mut work = VecDeque::new();
        self.drive(
            client,
            bridge,
            key,
            ConversationInput::TimerFired(token),
            now_ms,
            &mut events,
            &mut work,
        );
        self.drain(client, now_ms, &mut events, &mut work);
        self.pump(client, now_ms, &mut events);
        events
    }

    /// 把核心/对话层攒下的出站帧推给 relay（`ZemoteClient::pump`），并把随之而来的核心事件
    /// 也吃掉。
    ///
    /// 循环到 pump 不再产出为止：一次 pump 可能推出发帧，而发帧又会产生 relay 事件。
    pub fn pump(&mut self, client: &mut ZemoteClient, now_ms: i64, out: &mut Vec<Value>) {
        loop {
            let events = client.pump(now_ms);
            if events.is_empty() {
                return;
            }
            self.absorb_into(client, events, now_ms, out);
        }
    }

    /// 超时清扫：通道调用与定时任务调用的 deadline 到期。
    ///
    /// **为什么要本层做**：通道调用的 id 只有本层知道（是它调 `channels.call` 拿到的），
    /// 宿主无从构造 `channelTimeout{id}`；而核心不为通道调用起定时器（Dart 用的是
    /// `Future.timeout`）。所以 deadline 由本层按宿主传入的 `nowMs` 判、并在每次入口调用时
    /// 清一次。宿主侧 relay 心跳（10s）会周期性驱动 `nativeStep`，故清扫不会长期不跑。
    pub fn sweep(&mut self, client: &mut ZemoteClient, now_ms: i64) -> Vec<Value> {
        let due: Vec<(BridgeSessionId, u64)> = self
            .calls
            .iter()
            .filter(|(_, pending)| pending.at_ms <= now_ms)
            .map(|(key, _)| *key)
            .collect();
        let mut events = Vec::new();
        if due.is_empty() {
            return events;
        }
        let mut work = VecDeque::new();
        for key in due {
            let Some(pending) = self.calls.remove(&key) else {
                continue;
            };
            let (bridge, call_id) = key;
            // 对应 Dart `Future.timeout` 的 `onTimeout`：先摘掉挂起项，再喂超时。
            if let Some(session) = client.bridge_mut(bridge) {
                session.channels_mut().remove_pending(call_id);
            }
            match pending.kind {
                CallKind::Conversation { key } => {
                    self.drive(
                        client,
                        bridge,
                        &key,
                        ConversationInput::ChannelTimeout { id: call_id },
                        now_ms,
                        &mut events,
                        &mut work,
                    );
                }
                CallKind::Automation { token, method } => {
                    // 对应 `ChannelClient.call(timeout:)` 到点抛的
                    // `TimeoutException('$channel.$method timed out')`。
                    events.push(codec::failed_event(
                        token,
                        "timeout",
                        format!("zcode-agent.{method} timed out"),
                    ));
                }
                CallKind::Channel {
                    token,
                    channel,
                    method,
                } => {
                    events.push(codec::failed_event(
                        token,
                        "timeout",
                        format!("{channel}.{method} timed out"),
                    ));
                }
            }
        }
        self.drain(client, now_ms, &mut events, &mut work);
        events
    }

    /// 释放全部 conversation（§10 的 `nativeDispose`）。
    ///
    /// 先让每个状态机 dispose（宿主需要收到它的 `timerCancel` / `unlistenChannel`），再把
    /// 残留的宿主定时器全部取消——宿主不能留悬挂计时器。
    pub fn dispose(&mut self, client: &mut ZemoteClient, now_ms: i64) -> Vec<Value> {
        let keys: Vec<(BridgeSessionId, String)> = self.conversations.keys().cloned().collect();
        let mut events = Vec::new();
        let mut work = VecDeque::new();
        for (bridge, key) in keys {
            let Some(mut entry) = self.conversations.remove(&(bridge, key.clone())) else {
                continue;
            };
            let disposed = entry.transport.dispose(now_ms);
            self.translate(
                client,
                bridge,
                &key,
                disposed,
                now_ms,
                &mut events,
                &mut work,
            );
        }
        self.drain(client, now_ms, &mut events, &mut work);
        for (host, _) in std::mem::take(&mut self.timers.forward) {
            events.push(json!({ "type": "timer_cancel", "token": host }));
        }
        self.timers.reverse.clear();
        self.calls.clear();
        self.healthy.clear();
        events
    }

    // -----------------------------------------------------------------------
    // 4.4 内部
    // -----------------------------------------------------------------------

    /// 一个进程内唯一的本地令牌（`automation:<op>:<n>`）。
    fn new_local_token(&mut self, prefix: &str) -> String {
        self.next_token += 1;
        format!("{prefix}:{}", self.next_token)
    }

    /// 消费工作队列，直到没有新的核心事件为止。
    ///
    /// 用队列而不是递归，是因为处理一条核心事件可能**又**产生核心事件（例如对话层要
    /// `bridge_wait_healthy`，核心立刻回一条 `timer_start`）。
    fn drain(
        &mut self,
        client: &mut ZemoteClient,
        now_ms: i64,
        out: &mut Vec<Value>,
        work: &mut VecDeque<ZemoteEvent>,
    ) {
        while let Some(event) = work.pop_front() {
            self.handle_core_event(client, event, now_ms, out, work);
        }
    }

    fn absorb_into(
        &mut self,
        client: &mut ZemoteClient,
        events: Vec<ZemoteEvent>,
        now_ms: i64,
        out: &mut Vec<Value>,
    ) {
        let mut work: VecDeque<ZemoteEvent> = events.into();
        self.drain(client, now_ms, out, &mut work);
    }

    /// 单条核心事件。
    fn handle_core_event(
        &mut self,
        client: &mut ZemoteClient,
        event: ZemoteEvent,
        now_ms: i64,
        out: &mut Vec<Value>,
        work: &mut VecDeque<ZemoteEvent>,
    ) {
        match event {
            // §13 的路由点：属于某个 conversation 的通道事件喂给它，其余裸透传。
            ZemoteEvent::Channel(channel) => {
                let id = channel.id();
                if let Some((bridge, key)) = self.owner_of(id) {
                    self.calls.remove(&(bridge, id));
                    self.drive(
                        client,
                        bridge,
                        &key,
                        ConversationInput::Channel(channel),
                        now_ms,
                        out,
                        work,
                    );
                } else if let Some(pending) = self.take_automation(id) {
                    // §13.3：定时任务调用由本层收尾（顶层 `completed`）……
                    out.push(automation_response(&pending, &channel));
                    // ……但 §7.3 的透传规则不变：不属于任何 conversation 的通道事件仍原样给宿主
                    // （**一条都不能丢**）。宿主拿到一条自己没发起过的 id 是安全的——它对不上
                    // 任何挂起项，按 Dart 的 `_handlers[id]?.call` 语义会被忽略。
                    out.push(codec::event_to_json(&ZemoteEvent::Channel(channel)));
                } else if let Some(pending) = self.take_channel(id) {
                    // 通用通道调用：同样由本层收尾，同时保留透传。
                    out.push(channel_response(&pending, &channel));
                    out.push(codec::event_to_json(&ZemoteEvent::Channel(channel)));
                } else {
                    // 一条都不能丢：不属于任何 conversation 的仍按 §7.3 透传。
                    out.push(codec::event_to_json(&ZemoteEvent::Channel(channel)));
                }
            }
            ZemoteEvent::BridgeRecovered { session, count } => {
                // §7.3 的事件照常给宿主（宿主自己也要看 `recovered` 计数）。
                out.push(codec::event_to_json(&ZemoteEvent::BridgeRecovered {
                    session,
                    count,
                }));
                for key in self.keys_of(session) {
                    self.drive(
                        client,
                        session,
                        &key,
                        ConversationInput::BridgeRecovered,
                        now_ms,
                        out,
                        work,
                    );
                }
            }
            ZemoteEvent::BridgeDegraded { session, degraded } => {
                out.push(codec::event_to_json(&ZemoteEvent::BridgeDegraded {
                    session,
                    degraded: degraded.clone(),
                }));
                for key in self.keys_of(session) {
                    self.drive(
                        client,
                        session,
                        &key,
                        ConversationInput::BridgeDegraded {
                            degraded: degraded.as_deref(),
                        },
                        now_ms,
                        out,
                        work,
                    );
                }
            }
            // §7.3 的 `timer_start` / `timer_cancel`：token 经本层重映射后才给宿主。
            ZemoteEvent::TimerStart { token, delay_ms } => {
                let host = self.timers.start(TimerRoute::Core(token));
                out.push(json!({ "type": "timer_start", "token": host, "delayMs": delay_ms }));
            }
            ZemoteEvent::TimerCancel(token) => {
                if let Some(host) = self.timers.cancel(&TimerRoute::Core(token)) {
                    out.push(json!({ "type": "timer_cancel", "token": host }));
                }
            }
            // 本层自己发起的 `waitHealthy` 的收尾：喂回对话层，不给宿主（宿主从没拿到过它的令牌）。
            ZemoteEvent::Completed { token, outcome } => {
                if let Some(waiter) = self.healthy.remove(&token) {
                    self.feed_healthy(client, waiter, &outcome, now_ms, out, work);
                } else {
                    out.push(codec::event_to_json(&ZemoteEvent::Completed { token, outcome }));
                }
            }
            other => out.push(codec::event_to_json(&other)),
        }
    }

    /// 某个通道 id 属于哪个 conversation（§13.5.2 的路由规则）。
    fn owner_of(&self, id: u64) -> Option<(BridgeSessionId, String)> {
        self.conversations
            .iter()
            .find(|(_, entry)| entry.transport.owns_channel_id(id))
            .map(|((bridge, key), _)| (*bridge, key.clone()))
    }

    /// 某个桥上的全部 conversation key（按确定顺序）。
    fn keys_of(&self, bridge: BridgeSessionId) -> Vec<String> {
        self.conversations
            .keys()
            .filter(|(b, _)| *b == bridge)
            .map(|(_, key)| key.clone())
            .collect()
    }

    /// 摘下一个等应答的定时任务调用（按通道 id）。
    fn take_automation(&mut self, id: u64) -> Option<AutomationCall> {
        let key = self.calls.iter().find_map(|(key, pending)| {
            if key.1 == id && matches!(pending.kind, CallKind::Automation { .. }) {
                Some(*key)
            } else {
                None
            }
        })?;
        let pending = self.calls.remove(&key)?;
        match pending.kind {
            CallKind::Automation { token, method } => Some(AutomationCall { token, method }),
            CallKind::Conversation { .. } | CallKind::Channel { .. } => None,
        }
    }

    /// 摘下一个等应答的通用通道调用（按通道 id）。
    fn take_channel(&mut self, id: u64) -> Option<ChannelCall> {
        let key = self.calls.iter().find_map(|(key, pending)| {
            if key.1 == id && matches!(pending.kind, CallKind::Channel { .. }) {
                Some(*key)
            } else {
                None
            }
        })?;
        let pending = self.calls.remove(&key)?;
        match pending.kind {
            CallKind::Channel {
                token,
                channel,
                method,
            } => Some(ChannelCall {
                token,
                channel,
                method,
            }),
            CallKind::Conversation { .. } | CallKind::Automation { .. } => None,
        }
    }

    /// 走一次 conversation 状态机（`step`），并把产出翻成宿主事件。
    ///
    /// 返回该次调用产生的第一个 `opAccepted` 的宿主令牌（`conversation_call` 用它当
    /// `operation`）。
    #[allow(clippy::too_many_arguments)]
    fn drive(
        &mut self,
        client: &mut ZemoteClient,
        bridge: BridgeSessionId,
        key: &str,
        input: ConversationInput<'_>,
        now_ms: i64,
        out: &mut Vec<Value>,
        work: &mut VecDeque<ZemoteEvent>,
    ) -> Option<String> {
        let mut issued: Vec<(u64, i64)> = Vec::new();
        let events = {
            let Some(entry) = self.conversations.get_mut(&(bridge, key.to_string())) else {
                return None;
            };
            let Some(session) = client.bridge_mut(bridge) else {
                return None;
            };
            let mut channels = CallTap {
                inner: session.channels_mut(),
                issued: &mut issued,
                now_ms,
            };
            entry.transport.step(input, &mut channels, now_ms)
        };
        for (call_id, at_ms) in issued {
            self.calls.insert(
                (bridge, call_id),
                PendingCall {
                    at_ms,
                    kind: CallKind::Conversation {
                        key: key.to_string(),
                    },
                },
            );
        }
        self.translate(client, bridge, key, events, now_ms, out, work)
    }

    /// `conversation_call` 的 op 入口。
    #[allow(clippy::too_many_arguments)]
    fn drive_op(
        &mut self,
        client: &mut ZemoteClient,
        bridge: BridgeSessionId,
        key: &str,
        op: &str,
        args: &Value,
        now_ms: i64,
        out: &mut Vec<Value>,
        work: &mut VecDeque<ZemoteEvent>,
    ) -> Result<Option<String>, LayerError> {
        let mut issued: Vec<(u64, i64)> = Vec::new();
        let events = {
            let Some(entry) = self.conversations.get_mut(&(bridge, key.to_string())) else {
                return Err(LayerError::InvalidState(format!(
                    "未知 conversation key: {key}（桥 {}）",
                    bridge.0
                )));
            };
            let Some(session) = client.bridge_mut(bridge) else {
                return Err(LayerError::InvalidState(format!("未知桥会话 {}", bridge.0)));
            };
            let mut channels = CallTap {
                inner: session.channels_mut(),
                issued: &mut issued,
                now_ms,
            };
            entry
                .transport
                .call_op(op, args, &mut channels, now_ms)
                .map_err(protocol_error_to_layer)?
        };
        for (call_id, at_ms) in issued {
            self.calls.insert(
                (bridge, call_id),
                PendingCall {
                    at_ms,
                    kind: CallKind::Conversation {
                        key: key.to_string(),
                    },
                },
            );
        }
        Ok(self.translate(client, bridge, key, events, now_ms, out, work))
    }

    /// 对话层事件 → 宿主事件。
    ///
    /// 三个**内部**变体就地执行（见模块文档）；`opAccepted` / `completed` 除翻成
    /// `conversation_event` 外，还额外给一条**顶层** `completed`——§8 明说宿主靠
    /// `operation` ↔ `completed.token` 配对 resolve 自己的 Deferred，而 Kotlin 引擎只在
    /// 顶层 `completed` 上 resolve（对话层的那条按 §13.5.2 是包在 `conversation_event` 里的）。
    fn translate(
        &mut self,
        client: &mut ZemoteClient,
        bridge: BridgeSessionId,
        key: &str,
        events: Vec<ConversationEvent>,
        now_ms: i64,
        out: &mut Vec<Value>,
        work: &mut VecDeque<ZemoteEvent>,
    ) -> Option<String> {
        let mut first_op: Option<String> = None;
        for event in events {
            match &event {
                ConversationEvent::ListenChannel {
                    token,
                    channel,
                    event: name,
                    arg,
                } => {
                    // 宿主拿不到通道 id（`add_listener` 只有核心侧有），只能本层做。
                    let listener = client.bridge_mut(bridge).map(|session| {
                        session
                            .channels_mut()
                            .add_listener(*channel, *name, arg.clone().into())
                    });
                    if let Some(id) = listener {
                        self.drive(
                            client,
                            bridge,
                            key,
                            ConversationInput::ListenerRegistered { token: *token, id },
                            now_ms,
                            out,
                            work,
                        );
                    }
                    continue;
                }
                ConversationEvent::UnlistenChannel { id } => {
                    if let Some(session) = client.bridge_mut(bridge) {
                        session.channels_mut().cancel_listener(*id);
                    }
                    continue;
                }
                ConversationEvent::WaitHealthy { token, timeout_ms } => {
                    self.wait_healthy(client, bridge, key, *token, *timeout_ms, now_ms, out, work);
                    continue;
                }
                _ => {}
            }

            let body = {
                let mut tokens = ConvTokens {
                    timers: &mut self.timers,
                    bridge,
                    key: key.to_string(),
                };
                codec::conversation_event_to_json(&event, &mut tokens)
            };

            match &event {
                ConversationEvent::OpAccepted { token, .. } => {
                    if first_op.is_none() {
                        first_op = Some(conversation_op_token(key, token));
                    }
                }
                ConversationEvent::Completed { token, outcome } => {
                    out.push(codec::completed_event(
                        conversation_op_token(key, token),
                        codec::transport_completion_to_core(outcome),
                    ));
                }
                _ => {}
            }
            if let Some(body) = body {
                out.push(codec::conversation_event_envelope(bridge, key, body));
            }
        }
        first_op
    }

    /// 对话层要 `await session.waitHealthy(45s)`：本层直接调核心，完成/超时再喂回去。
    #[allow(clippy::too_many_arguments)]
    fn wait_healthy(
        &mut self,
        client: &mut ZemoteClient,
        bridge: BridgeSessionId,
        key: &str,
        token: u64,
        timeout_ms: i64,
        now_ms: i64,
        out: &mut Vec<Value>,
        work: &mut VecDeque<ZemoteEvent>,
    ) {
        match client.bridge_wait_healthy(bridge, timeout_ms) {
            Ok(Some(operation)) => {
                self.healthy.insert(
                    operation.token,
                    HealthyWait {
                        bridge,
                        key: key.to_string(),
                        token,
                    },
                );
                // 它的 `TimerStart`（超时定时器）要经本层重映射后给宿主。
                work.extend(operation.events);
            }
            // 桥已健康（对应 Dart 那个已完成的 future）：立刻喂回。
            Ok(None) => {
                self.drive(
                    client,
                    bridge,
                    key,
                    ConversationInput::BridgeHealthy { token },
                    now_ms,
                    out,
                    work,
                );
            }
            // 桥都不在了（未知句柄）：等不到健康，按超时收（最保守，不放行重发）。
            Err(_) => {
                self.drive(
                    client,
                    bridge,
                    key,
                    ConversationInput::BridgeHealthyTimeout { token },
                    now_ms,
                    out,
                    work,
                );
            }
        }
    }

    /// 核心的 `waitHealthy` 完成 → 对话层输入。
    fn feed_healthy(
        &mut self,
        client: &mut ZemoteClient,
        waiter: HealthyWait,
        outcome: &Completion,
        now_ms: i64,
        out: &mut Vec<Value>,
        work: &mut VecDeque<ZemoteEvent>,
    ) {
        let input = match outcome {
            Completion::Ok => ConversationInput::BridgeHealthy { token: waiter.token },
            // 超时 / 失败 / 其它形态一律按超时（保守：不放行重发）。
            _ => ConversationInput::BridgeHealthyTimeout { token: waiter.token },
        };
        self.drive(
            client,
            waiter.bridge,
            &waiter.key,
            input,
            now_ms,
            out,
            work,
        );
    }
}

/// §6 的 `timer_fired` 还原结果。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TimerFired {
    /// 门面自己的定时器。
    Core(TimerId),
    /// 某个 conversation 的定时器。
    Conversation {
        /// 桥句柄。
        bridge: BridgeSessionId,
        /// conversation key。
        key: String,
        /// 对话层的 token。
        token: TimerId,
    },
}

/// 摘下来的定时任务调用。
#[derive(Debug, Clone, PartialEq, Eq)]
struct AutomationCall {
    token: String,
    method: &'static str,
}

/// 一次通用通道调用的收尾信息。
#[derive(Debug, Clone, PartialEq, Eq)]
struct ChannelCall {
    token: String,
    channel: String,
    method: String,
}

/// 记录「这次 `channels.call` 分配了哪个 id、什么时候到期」的适配器。
///
/// 宿主拿不到这些 id，所以超时只能由本层判（见 [`ConversationLayer::sweep`]）。
struct CallTap<'a> {
    inner: &'a mut dyn ChannelRpc,
    issued: &'a mut Vec<(u64, i64)>,
    now_ms: i64,
}

impl ChannelRpc for CallTap<'_> {
    fn call(
        &mut self,
        channel: &str,
        method: &str,
        args: Vec<zemote_protocol::ipc_codec::Value>,
        timeout_ms: i64,
    ) -> u64 {
        let id = self.inner.call(channel, method, args, timeout_ms);
        self.issued.push((id, self.now_ms + timeout_ms));
        id
    }
}

/// 定时任务应答 → 顶层 `completed`（§13.3：结果经 `outcome.value` 返回）。
fn automation_response(pending: &AutomationCall, event: &ChannelEvent) -> Value {
    match event {
        ChannelEvent::PromiseSuccess { data, .. } => {
            let value = if pending.method == "listAllAutomations" {
                // Dart `list()`：`res.whereType<Map>()` → `List<AutomationEntry>`，UI 用 `.raw`。
                // 这里给原始条目数组（丢掉非 Map 元素，与 `whereType<Map>` 一致）。
                Value::Array(
                    parse_automation_list(data)
                        .into_iter()
                        .map(|entry| Value::Object(entry.raw))
                        .collect(),
                )
            } else {
                // 其余 op 的 Dart 返回值是 `dynamic`（原始应答）。
                to_dart_view(data)
            };
            codec::completed_event(pending.token.clone(), Completion::Payload(value))
        }
        ChannelEvent::PromiseError { error, .. }
        | ChannelEvent::PromiseErrorObj { error, .. } => {
            // §7.5 的 `failed.error.kind` 只有 timeout / invalidState / protocol，
            // 通道 RPC 失败归 protocol（细节在 message 里）。
            codec::failed_event(pending.token.clone(), "protocol", error.message.clone())
        }
        // 订阅事件不该落在请求 id 上（`take_automation` 只认挂起的请求）。
        ChannelEvent::EventFire { .. } => codec::failed_event(
            pending.token.clone(),
            "protocol",
            "unexpected event fire for an automation request",
        ),
    }
}

/// `channel_call` 的应答 → 顶层 `completed`（`outcome.value` 是原始应答）。
fn channel_response(pending: &ChannelCall, event: &ChannelEvent) -> Value {
    match event {
        ChannelEvent::PromiseSuccess { data, .. } => {
            codec::completed_event(pending.token.clone(), Completion::Payload(to_dart_view(data)))
        }
        ChannelEvent::PromiseError { error, .. }
        | ChannelEvent::PromiseErrorObj { error, .. } => {
            codec::failed_event(pending.token.clone(), "protocol", error.message.clone())
        }
        ChannelEvent::EventFire { .. } => codec::failed_event(
            pending.token.clone(),
            "protocol",
            format!(
                "unexpected event fire for {}.{}",
                pending.channel, pending.method
            ),
        ),
    }
}

/// `automation_call` 的 `scope`（工作区描述）。
///
/// 优先 `args.scope`（对象）；否则用 `args.workspacePath` / `args.workspaceIdentity` 拼；
/// 再否则回落到桥的 `workspaceKey`。**`workspacePath` 键恒出现**（值可能是 `null`）——
/// 与 `AutomationClient::scope_map` / Dart 的 `_scopeMap` 一致。
fn automation_scope(
    client: &ZemoteClient,
    session: BridgeSessionId,
    args: &Map<String, Value>,
) -> Result<Map<String, Value>, LayerError> {
    match args.get("scope") {
        None | Some(Value::Null) => {}
        Some(Value::Object(scope)) => return Ok(scope.clone()),
        Some(_) => {
            return Err(LayerError::BadInput(
                "字段 scope 必须是 JSON 对象或 null".to_string(),
            ))
        }
    }
    let bridge = client
        .bridge(session)
        .ok_or_else(|| LayerError::InvalidState(format!("未知桥会话 {}", session.0)))?;
    let mut scope = Map::new();
    let path = match args.get("workspacePath") {
        Some(Value::Null) | None => bridge
            .workspace_key()
            .map(|key| Value::String(key.to_string()))
            .unwrap_or(Value::Null),
        Some(Value::String(path)) => Value::String(path.clone()),
        Some(_) => {
            return Err(LayerError::BadInput(
                "字段 workspacePath 必须是字符串或 null".to_string(),
            ))
        }
    };
    scope.insert("workspacePath".to_string(), path);
    if let Some(Value::String(identity)) = args.get("workspaceIdentity") {
        scope.insert(
            "workspaceIdentity".to_string(),
            Value::String(identity.clone()),
        );
    }
    Ok(scope)
}

/// `create` 的参数（§13.3）。
fn create_params(args: &Map<String, Value>) -> Result<CreateAutomationParams, LayerError> {
    let mut params = CreateAutomationParams::new(
        req_str(args, "title")?,
        req_str(args, "prompt")?,
        req_str(args, "cronExpr")?,
        req_str(args, "model")?,
        req_str(args, "provider")?,
        req_str(args, "mode")?,
        req_str(args, "thoughtLevel")?,
        req_bool(args, "recurring")?,
        req_object(args, "scheduleRule")?,
    );
    if let Some(enabled) = match args.get("enabled") {
        None | Some(Value::Null) => None,
        Some(Value::Bool(value)) => Some(*value),
        Some(_) => {
            return Err(LayerError::BadInput(
                "字段 enabled 必须是布尔或 null".to_string(),
            ))
        }
    } {
        params.enabled = enabled;
    }
    Ok(params)
}

fn req_str(args: &Map<String, Value>, key: &str) -> Result<String, LayerError> {
    args.get(key)
        .and_then(Value::as_str)
        .map(str::to_string)
        .ok_or_else(|| LayerError::BadInput(format!("字段 {key} 必须是字符串")))
}

fn req_bool(args: &Map<String, Value>, key: &str) -> Result<bool, LayerError> {
    args.get(key)
        .and_then(Value::as_bool)
        .ok_or_else(|| LayerError::BadInput(format!("字段 {key} 必须是布尔")))
}

fn req_object(args: &Map<String, Value>, key: &str) -> Result<Map<String, Value>, LayerError> {
    args.get(key)
        .and_then(Value::as_object)
        .cloned()
        .ok_or_else(|| LayerError::BadInput(format!("字段 {key} 必须是 JSON 对象")))
}
