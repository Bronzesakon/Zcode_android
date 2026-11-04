//! 定时任务（automation）客户端 —— 对应 `Reference_code/lib/protocol/automation_client.dart`。
//!
//! 两块内容：
//! 1. [`AutomationEntry`]：`zcode-agent.listAllAutomations` 返回项的可读视图 + `scheduleLabel()`。
//! 2. [`AutomationClient`]：`zcode-agent` 通道上的 automation RPC（list / create / setEnabled /
//!    delete / runNow / restart）的**载荷构造**与**响应解析**。
//!
//! # I/O 边界（本 crate 纯逻辑、同步、无 I/O）
//! Dart 的 `Future` 由宿主驱动：每个方法只发一次 `call` 并返回请求 id，宿主等
//! [`ChannelEvent`](crate::channel_client::ChannelEvent) 里的同 id 应答，再用本模块的
//! [`parse_automation_list`] / [`parse_automation_entry`] 解析数据。超时毫秒数由本模块的常量给出
//! （逐条照抄 Dart 的 `timeout:` 实参），宿主据此起计时器。
//!
//! PORT-NOTE: Dart 的 `AutomationClient` 持有 `BridgeSession bridge`，通过 `bridge.channels`
//! 取到 `ChannelClient`（`BridgeSession` 在 `zemote_client.dart`，对应本 crate 的
//! `zemote_client.rs`，此刻由另一子代理并行移植）。为避免与那个模块耦合、也避免在 Rust 里
//! 表达“共享 bridge 内部的可变通道”，本模块只依赖 [`ChannelRpc`]（定义在 `channel_client.rs`，
//! 由 `ChannelClient` 实现）。等 `zemote_client.rs` 落地后，由主线决定是给 `BridgeSession` 加
//! `impl ChannelRpc`，还是让本模块直接持有 `&mut ChannelClient`。
//!
//! PORT-NOTE: Dart 的 `scope` 是 `Map<String, dynamic>`（工作区描述），调用点见
//! `automation_roundtrip_test.dart:29-34`：从 `bootstrap()['workspaces'].first` 里取
//! `workspacePath` 与（可选的）`workspaceIdentity`。本模块用 `serde_json::Map` 承载同一形状，
//! 取数留给宿主。

use crate::channel_client::{dart_json_to_string, json_as_dart_int, to_dart_view, ChannelRpc, Channels};
use crate::ipc_codec::Value;

/// 星期标签表 —— 对应 Dart `AutomationEntry._weekdayLabel` 里的 `const names`。
const WEEKDAY_NAMES: [&str; 7] = ["周日", "周一", "周二", "周三", "周四", "周五", "周六"];

/// 无法得知计划时的占位符 —— 对应 Dart `scheduleLabel()` 里的 `'—'`（U+2014 EM DASH）。
const DASH: &str = "—";

// ---------------------------------------------------------------------------
// AutomationEntry
// ---------------------------------------------------------------------------

/// 桌面上的一条定时任务 —— 对应 Dart `AutomationEntry`（`zcode-agent.listAllAutomations` 的返回项）。
///
/// 所有字段都是 `raw` 的**派生视图**，与 Dart 一样在构造时一次性取出；`raw` 原样保留。
#[derive(Debug, Clone, PartialEq)]
pub struct AutomationEntry {
    /// 对应 Dart `automationId`（`'${raw['automationId'] ?? ''}'`）。
    pub automation_id: String,
    /// 对应 Dart `title`。
    pub title: String,
    /// 对应 Dart `prompt`。
    pub prompt: String,
    /// 对应 Dart `cronExpr`。
    pub cron_expr: String,
    /// 对应 Dart `model`。
    pub model: String,
    /// 对应 Dart `provider`。
    pub provider: String,
    /// 对应 Dart `mode`。
    pub mode: String,
    /// 对应 Dart `thoughtLevel`。
    pub thought_level: String,
    /// 对应 Dart `recurring`（严格判定 `raw['recurring'] == true`）。
    pub recurring: bool,
    /// 对应 Dart `scheduleRule`（`(raw['scheduleRule'] as Map?)`）；不是 JSON 对象时为 `None`。
    pub schedule_rule: Option<serde_json::Value>,
    /// 对应 Dart `enabled`（严格判定 `raw['enabled'] == true`）。
    pub enabled: bool,
    /// 对应 Dart `lifecycleStatus`。
    pub lifecycle_status: String,
    /// 对应 Dart `runCount`（`(raw['runCount'] as num?)?.toInt() ?? 0`）。
    pub run_count: i64,
    /// 对应 Dart `lastRunAt`（Unix epoch 毫秒）。
    pub last_run_at: i64,
    /// 对应 Dart `nextRunAt`（可空）。
    pub next_run_at: Option<i64>,
    /// 对应 Dart `createdAt`（Unix epoch 毫秒）。
    pub created_at: i64,
    /// 对应 Dart `updatedAt`（Unix epoch 毫秒）。
    pub updated_at: i64,
    /// 对应 Dart `raw`：原始 map，原样保留。
    pub raw: serde_json::Map<String, serde_json::Value>,
}

impl AutomationEntry {
    /// 对应 Dart `AutomationEntry(this.raw)`。
    ///
    /// PORT-NOTE: Dart 的构造里有几处 `as` 强转在载荷异常时会抛 `TypeError`
    /// （`(raw['scheduleRule'] as Map?)`、`(raw['runCount'] as num?)` 等；`TypeError` 不在
    /// PORTING.md 的错误映射表里）。Rust 侧不 panic：`scheduleRule` 不是对象时按“无计划规则”
    /// 处理（回落 `cronExpr`），数字字段不是数字时回落默认值（`0` / `None`）。
    /// 桌面端的正常载荷不会走到这些分支；若主线要求严格报错，把这些字段改成 `Result` 即可。
    pub fn from_json(raw: &serde_json::Map<String, serde_json::Value>) -> Self {
        Self {
            automation_id: dart_string_field(raw.get("automationId")),
            title: dart_string_field(raw.get("title")),
            prompt: dart_string_field(raw.get("prompt")),
            cron_expr: dart_string_field(raw.get("cronExpr")),
            model: dart_string_field(raw.get("model")),
            provider: dart_string_field(raw.get("provider")),
            mode: dart_string_field(raw.get("mode")),
            thought_level: dart_string_field(raw.get("thoughtLevel")),
            // 对应 Dart `raw['recurring'] == true`：JSON 的 1 / "true" 都**不算** true。
            recurring: raw.get("recurring").and_then(|v| v.as_bool()) == Some(true),
            // 对应 Dart `(raw['scheduleRule'] as Map?)?.cast<String, dynamic>()`。
            schedule_rule: raw.get("scheduleRule").filter(|v| v.is_object()).cloned(),
            enabled: raw.get("enabled").and_then(|v| v.as_bool()) == Some(true),
            lifecycle_status: dart_string_field(raw.get("lifecycleStatus")),
            run_count: json_as_dart_int_field(raw.get("runCount")).unwrap_or(0),
            last_run_at: json_as_dart_int_field(raw.get("lastRunAt")).unwrap_or(0),
            next_run_at: json_as_dart_int_field(raw.get("nextRunAt")),
            created_at: json_as_dart_int_field(raw.get("createdAt")).unwrap_or(0),
            updated_at: json_as_dart_int_field(raw.get("updatedAt")).unwrap_or(0),
            raw: raw.clone(),
        }
    }

    /// 对应 Dart `AutomationEntry.scheduleLabel()`：人类可读的计划描述，
    /// 例如 `每 5 分钟` / `每 2 小时` / `每天 11:17` / `每周 周三` / 回落 `cronExpr` / 占位 `—`。
    pub fn schedule_label(&self) -> String {
        let Some(rule) = self.schedule_rule.as_ref().and_then(|v| v.as_object()) else {
            // Dart: `if (rule == null) return cronExpr.isEmpty ? '—' : cronExpr;`
            return self.cron_or_dash();
        };
        // Dart: `final unit = '${rule['unit'] ?? ''}';`
        let unit = dart_string_field(rule.get("unit"));
        // Dart: `final interval = (rule['interval'] as num?)?.toInt() ?? 1;`
        let interval = json_as_dart_int_field(rule.get("interval")).unwrap_or(1);
        match unit.as_str() {
            "minute" => format!("每 {} 分钟", interval),
            "hour" => format!("每 {} 小时", interval),
            "day" => {
                // Dart: `(rule['hour'] as num?)?.toInt() ?? 0` / 同理 minute。
                let hour = json_as_dart_int_field(rule.get("hour")).unwrap_or(0);
                let minute = json_as_dart_int_field(rule.get("minute")).unwrap_or(0);
                format!("每天 {}:{}", two(hour), two(minute))
            }
            "week" => {
                // Dart: `final dow = rule['weekday'] ?? rule['dayOfWeek'];`
                let dow = rule
                    .get("weekday")
                    .filter(|v| !v.is_null())
                    .or_else(|| rule.get("dayOfWeek"));
                format!("每周 {}", weekday_label(dow))
            }
            // Dart 的 `default:` 分支（含 unit 缺失、空串、未知取值）。
            _ => self.cron_or_dash(),
        }
    }

    /// 对应 Dart 的 `cronExpr.isEmpty ? '—' : cronExpr`（`scheduleLabel` 里出现两次）。
    fn cron_or_dash(&self) -> String {
        if self.cron_expr.is_empty() {
            DASH.to_string()
        } else {
            self.cron_expr.clone()
        }
    }
}

/// 对应 Dart `AutomationEntry._two(int v)`：`v.toString().padLeft(2, '0')`。
///
/// `padLeft` 只在不足 2 位时补零，`{:02}` 语义相同（负数、`>= 100` 都不补）。
fn two(v: i64) -> String {
    format!("{:02}", v)
}

/// 对应 Dart `AutomationEntry._weekdayLabel(Object? dow)`。
///
/// Dart: `if (dow is num && dow >= 0 && dow < 7) return names[dow.toInt()]; return '';`
/// ——判定用原值，取表用 `toInt()` 向零截断（`6.9` 取 `周六`，`7.0` / `-0.5` / 非数字取空串）。
fn weekday_label(dow: Option<&serde_json::Value>) -> String {
    match dow.and_then(|v| v.as_f64()) {
        Some(d) if (0.0..7.0).contains(&d) => WEEKDAY_NAMES[d as usize].to_string(),
        _ => String::new(),
    }
}

/// 对应 Dart 的 `'${x ?? ''}'`：键不存在或值为 null → 空串；否则用 Dart 的 `toString`。
///
/// 即 `'${raw['title'] ?? ''}'` 这类字段初始化表达式的公共部分（`AutomationEntry` 的 9 个字符串字段、
/// `scheduleLabel` 里的 `unit` 都走这里）。
fn dart_string_field(value: Option<&serde_json::Value>) -> String {
    match value {
        None => String::new(),
        Some(v) if v.is_null() => String::new(),
        Some(v) => dart_json_to_string(v),
    }
}

/// 对应 Dart 的 `(x as num?)?.toInt()`（`json_as_dart_int` 的“字段版”）。
///
/// 只是把 [`crate::channel_client::json_as_dart_int`] 包一层以保持调用点与 Dart 表达式一一对应。
fn json_as_dart_int_field(value: Option<&serde_json::Value>) -> Option<i64> {
    value.and_then(json_as_dart_int)
}

// ---------------------------------------------------------------------------
// 响应解析
// ---------------------------------------------------------------------------

/// 对应 Dart `AutomationClient.list()` 里对响应的解析：
/// `if (res is! List) return const []; ... for (final item in res.whereType<Map>())`。
///
/// `whereType<Map>()` 会**丢弃**数组里不是 Map 的元素。
/// `to_dart_view` 让 tag 4 的 List 与 tag 5 里 `jsonDecode` 出来的 List 都算 `is List`。
pub fn parse_automation_list(data: &Value) -> Vec<AutomationEntry> {
    let json = to_dart_view(data);
    let Some(items) = json.as_array() else {
        return Vec::new();
    };
    items
        .iter()
        .filter_map(|item| item.as_object())
        .map(AutomationEntry::from_json)
        .collect()
}

/// 对应 Dart `AutomationClient.create()` 里对响应的解析：
/// `res is Map ? res.cast<String, dynamic>() : <String, dynamic>{}`。
pub fn parse_automation_entry(data: &Value) -> AutomationEntry {
    let json = to_dart_view(data);
    match json.as_object() {
        Some(map) => AutomationEntry::from_json(map),
        None => AutomationEntry::from_json(&serde_json::Map::new()),
    }
}

// ---------------------------------------------------------------------------
// 请求参数
// ---------------------------------------------------------------------------

/// `createAutomation` 的入参 —— 对应 Dart `AutomationClient.create({...})` 的具名必填参数。
///
/// Dart 里除 `enabled`（默认 `true`）外都是 `required`；Rust 侧用 [`CreateAutomationParams::new`]
/// 收下那 9 个必填项（`enabled` 初值即 Dart 的默认值 `true`），要改就改公开字段。
#[derive(Debug, Clone, PartialEq)]
pub struct CreateAutomationParams {
    /// 线上字段 `title`。
    pub title: String,
    /// 线上字段 `prompt`。
    pub prompt: String,
    /// 线上字段 `cronExpr`。
    pub cron_expr: String,
    /// 线上字段 `model`。
    pub model: String,
    /// 线上字段 `provider`。
    pub provider: String,
    /// 线上字段 `mode`。
    pub mode: String,
    /// 线上字段 `thoughtLevel`。
    pub thought_level: String,
    /// 线上字段 `recurring`。
    pub recurring: bool,
    /// 线上字段 `scheduleRule`，形如
    /// `{unit: 'minute'|'hour'|'day'|'week', interval, hour, minute}`。
    pub schedule_rule: serde_json::Map<String, serde_json::Value>,
    /// 线上字段 `enabled`（Dart 默认 `true`）。
    pub enabled: bool,
}

impl CreateAutomationParams {
    /// 对应 Dart `create` 的 9 个 `required` 具名参数；`enabled` 取 Dart 的默认值 `true`。
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        title: impl Into<String>,
        prompt: impl Into<String>,
        cron_expr: impl Into<String>,
        model: impl Into<String>,
        provider: impl Into<String>,
        mode: impl Into<String>,
        thought_level: impl Into<String>,
        recurring: bool,
        schedule_rule: serde_json::Map<String, serde_json::Value>,
    ) -> Self {
        Self {
            title: title.into(),
            prompt: prompt.into(),
            cron_expr: cron_expr.into(),
            model: model.into(),
            provider: provider.into(),
            mode: mode.into(),
            thought_level: thought_level.into(),
            recurring,
            schedule_rule,
            enabled: true,
        }
    }
}

// ---------------------------------------------------------------------------
// 客户端
// ---------------------------------------------------------------------------

/// `zcode-agent` 通道上的定时任务 RPC 客户端 —— 对应 Dart `AutomationClient`。
///
/// 每个方法都对应 Dart 的一次 `_channels.call('zcode-agent', <method>, [<payload>], timeout: ...)`，
/// 返回请求 id；响应数据用 [`parse_automation_list`] / [`parse_automation_entry`] 解析。
pub struct AutomationClient<'a> {
    /// 对应 Dart `bridge.channels`（见模块头的 PORT-NOTE）。
    channels: &'a mut dyn ChannelRpc,
    /// 对应 Dart `final Map<String, dynamic> scope`。
    scope: serde_json::Map<String, serde_json::Value>,
}

impl<'a> AutomationClient<'a> {
    /// 对应 Dart `list()` 的 `timeout: const Duration(seconds: 30)`。
    pub const LIST_TIMEOUT_MS: i64 = 30_000;
    /// 对应 Dart `create()` 的 `timeout: const Duration(seconds: 20)`。
    pub const CREATE_TIMEOUT_MS: i64 = 20_000;
    /// 对应 Dart `setEnabled` / `delete` / `runNow` / `restart` 的 `timeout: const Duration(seconds: 15)`。
    pub const MUTATE_TIMEOUT_MS: i64 = 15_000;

    /// 对应 Dart `const AutomationClient({required this.bridge, required this.scope})`。
    ///
    /// `scope` 取工作区描述（至少含 `workspacePath`，可含 `workspaceIdentity`），见模块头 PORT-NOTE。
    pub fn new(
        channels: &'a mut dyn ChannelRpc,
        scope: &serde_json::Map<String, serde_json::Value>,
    ) -> Self {
        Self {
            channels,
            scope: scope.clone(),
        }
    }

    /// 对应 Dart `_scopeMap` getter：
    /// `{'workspacePath': scope['workspacePath'], if (scope['workspaceIdentity'] != null) 'workspaceIdentity': ...}`。
    ///
    /// 两处细节照抄 Dart：`workspacePath` **恒出现**（值可能是 `null`），
    /// `workspaceIdentity` **仅在非 null 时**才出现（键被省略，不是写 null）。
    pub fn scope_map(&self) -> serde_json::Map<String, serde_json::Value> {
        let mut map = serde_json::Map::new();
        map.insert(
            "workspacePath".to_string(),
            self.scope
                .get("workspacePath")
                .cloned()
                .unwrap_or(serde_json::Value::Null),
        );
        if let Some(identity) = self.scope.get("workspaceIdentity") {
            if !identity.is_null() {
                map.insert("workspaceIdentity".to_string(), identity.clone());
            }
        }
        map
    }

    /// 对应 Dart `list()`：该工作区的全部定时任务（活动 + 已完成）。
    ///
    /// 调用 `zcode-agent.listAllAutomations`，参数 `[_scopeMap]`；响应交 [`parse_automation_list`]。
    pub fn list(&mut self) -> u64 {
        let payload = Value::Object(serde_json::Value::Object(self.scope_map()));
        self.channels.call(
            Channels::ZCODE_AGENT,
            "listAllAutomations",
            vec![payload],
            Self::LIST_TIMEOUT_MS,
        )
    }

    /// 对应 Dart `create({...})`：创建一条定时任务，返回创建出来的那条。
    ///
    /// 调用 `zcode-agent.createAutomation`，参数 `[{..._scopeMap, title, prompt, cronExpr, model,
    /// provider, mode, thoughtLevel, recurring, scheduleRule, enabled}]`；
    /// 响应交 [`parse_automation_entry`]。
    pub fn create(&mut self, params: &CreateAutomationParams) -> u64 {
        let mut map = self.scope_map();
        map.insert("title".to_string(), params.title.clone().into());
        map.insert("prompt".to_string(), params.prompt.clone().into());
        map.insert("cronExpr".to_string(), params.cron_expr.clone().into());
        map.insert("model".to_string(), params.model.clone().into());
        map.insert("provider".to_string(), params.provider.clone().into());
        map.insert("mode".to_string(), params.mode.clone().into());
        map.insert(
            "thoughtLevel".to_string(),
            params.thought_level.clone().into(),
        );
        map.insert(
            "recurring".to_string(),
            serde_json::Value::Bool(params.recurring),
        );
        map.insert(
            "scheduleRule".to_string(),
            serde_json::Value::Object(params.schedule_rule.clone()),
        );
        map.insert(
            "enabled".to_string(),
            serde_json::Value::Bool(params.enabled),
        );
        let payload = Value::Object(serde_json::Value::Object(map));
        self.channels.call(
            Channels::ZCODE_AGENT,
            "createAutomation",
            vec![payload],
            Self::CREATE_TIMEOUT_MS,
        )
    }

    /// 对应 Dart `setEnabled(String automationId, bool enabled)`：启用/停用一条定时任务。
    ///
    /// 调用 `zcode-agent.setAutomationEnabled`，参数 `[{..._scopeMap, automationId, enabled}]`。
    ///
    /// PORT-NOTE: 真机探测脚本 `automation_roundtrip_test.dart:91-111` 会在
    /// `setAutomationEnabled` 失败后**再试 `updateAutomation`**（同样的载荷），说明不同版本的桌面端
    /// 可能只认其中一个方法名。Dart 的 `AutomationClient` 只实现 `setAutomationEnabled`，本模块照抄；
    /// 若后续实测发现必须回落，需要由主线决定是加参数还是加方法（不要在本模块私自改方法名）。
    pub fn set_enabled(&mut self, automation_id: &str, enabled: bool) -> u64 {
        let mut map = self.scope_map();
        map.insert(
            "automationId".to_string(),
            serde_json::Value::String(automation_id.to_string()),
        );
        map.insert("enabled".to_string(), serde_json::Value::Bool(enabled));
        let payload = Value::Object(serde_json::Value::Object(map));
        self.channels.call(
            Channels::ZCODE_AGENT,
            "setAutomationEnabled",
            vec![payload],
            Self::MUTATE_TIMEOUT_MS,
        )
    }

    /// 对应 Dart `delete(String automationId)`：删除一条定时任务。
    ///
    /// 调用 `zcode-agent.deleteAutomation`，参数 `[{..._scopeMap, automationId}]`。
    ///
    /// PORT-NOTE: 同 [`Self::set_enabled`]——真机探测脚本在 `deleteAutomation` 失败后会再试
    /// `removeAutomation`（`automation_roundtrip_test.dart:116-135`）；Dart 客户端只实现前者，
    /// 本模块照抄，回落策略由主线定夺。
    pub fn delete(&mut self, automation_id: &str) -> u64 {
        let payload = self.automation_id_payload(automation_id);
        self.channels.call(
            Channels::ZCODE_AGENT,
            "deleteAutomation",
            vec![payload],
            Self::MUTATE_TIMEOUT_MS,
        )
    }

    /// 对应 Dart `runNow(String automationId)`：立刻跑一次（入队一次运行）。
    ///
    /// 调用 `zcode-agent.runAutomationNow`，参数 `[{..._scopeMap, automationId}]`。
    pub fn run_now(&mut self, automation_id: &str) -> u64 {
        let payload = self.automation_id_payload(automation_id);
        self.channels.call(
            Channels::ZCODE_AGENT,
            "runAutomationNow",
            vec![payload],
            Self::MUTATE_TIMEOUT_MS,
        )
    }

    /// 对应 Dart `restart(String automationId)`：重启一条已完成/出错的定时任务。
    ///
    /// 调用 `zcode-agent.restartAutomation`，参数 `[{..._scopeMap, automationId}]`。
    pub fn restart(&mut self, automation_id: &str) -> u64 {
        let payload = self.automation_id_payload(automation_id);
        self.channels.call(
            Channels::ZCODE_AGENT,
            "restartAutomation",
            vec![payload],
            Self::MUTATE_TIMEOUT_MS,
        )
    }

    /// `delete` / `runNow` / `restart` 共用的 `{..._scopeMap, 'automationId': id}` 载荷。
    fn automation_id_payload(&self, automation_id: &str) -> Value {
        let mut map = self.scope_map();
        map.insert(
            "automationId".to_string(),
            serde_json::Value::String(automation_id.to_string()),
        );
        Value::Object(serde_json::Value::Object(map))
    }
}

// ---------------------------------------------------------------------------
// 测试：前 6 条逐条搬自 `Reference_code/test/automation_client_test.dart`；
// 请求载荷部分取自 `Reference_code/test/automation_roundtrip_test.dart`（真机探测脚本，
// 默认 `SKIP`，其载荷字面量是线格式的独立证据）；其余为本模块补充。
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use crate::channel_client::{ChannelClient, ChannelEvent};
    use crate::ipc_codec::{decode_value, encode_to_vec, ValueReader};
    use serde_json::json;

    // -- 测试辅助 ----------------------------------------------------------

    /// Dart 测试的 `AutomationEntry entry(Map<String, dynamic> raw) => AutomationEntry(raw)`。
    fn entry(raw: serde_json::Value) -> AutomationEntry {
        AutomationEntry::from_json(raw.as_object().expect("测试输入必须是 JSON 对象"))
    }

    fn scope(workspace_path: &str, workspace_identity: Option<&str>) -> serde_json::Map<String, serde_json::Value> {
        let mut map = serde_json::Map::new();
        map.insert("workspacePath".to_string(), json!(workspace_path));
        if let Some(id) = workspace_identity {
            map.insert("workspaceIdentity".to_string(), json!(id));
        }
        map
    }

    /// 收到 Initialize 的通道客户端（Dart 测试里 `client.handleMessage(initHeader())`）。
    fn ready_channels() -> ChannelClient {
        let mut channels = ChannelClient::new();
        let init = encode_to_vec(&Value::Array(vec![
            Value::from_i64(ChannelClient::RES_INITIALIZE),
            Value::Int(0),
        ]))
        .unwrap();
        channels.handle_message(&init);
        assert!(channels.is_ready());
        channels
    }

    /// 解出一次 `_sendRequest` 产出的 `(header, arg)`。
    fn decode_request(body: &[u8]) -> (Value, Value) {
        let mut reader = ValueReader::new(body.to_vec());
        let header = decode_value(&mut reader).expect("header 应可解出");
        let arg = decode_value(&mut reader).expect("arg 应可解出");
        (header, arg)
    }

    /// 校验一次 automation RPC 的通道名、方法名、请求 id 与超时，返回参数值。
    fn expect_call(
        channels: &ChannelClient,
        body: &[u8],
        id: u64,
        method: &str,
        timeout_ms: i64,
    ) -> Value {
        let (header, arg) = decode_request(body);
        assert_eq!(
            header,
            Value::Array(vec![
                Value::from_i64(ChannelClient::REQ_PROMISE),
                Value::from_i64(id as i64),
                Value::from(Channels::ZCODE_AGENT),
                Value::from(method),
            ])
        );
        // 头部整段字节复核（`encodeValue([reqType, id, channel, name])` 的精确输出）。
        let expected_header = encode_to_vec(&Value::Array(vec![
            Value::from_i64(ChannelClient::REQ_PROMISE),
            Value::from_i64(id as i64),
            Value::from(Channels::ZCODE_AGENT),
            Value::from(method),
        ]))
        .unwrap();
        assert!(body.starts_with(&expected_header), "头部字节前缀不符");
        assert_eq!(channels.pending_timeout_ms(id), Some(timeout_ms));
        arg
    }

    /// 取 `arg`（应为单元素数组）里的那个对象。
    fn sole_object(arg: &Value) -> serde_json::Map<String, serde_json::Value> {
        match arg {
            Value::Array(items) => {
                assert_eq!(items.len(), 1, "参数列表应只有 1 个元素");
                match &items[0] {
                    Value::Object(json) => json.as_object().expect("应是 JSON 对象").clone(),
                    other => panic!("期望 JSON 对象参数，实得 {:?}", other),
                }
            }
            other => panic!("期望单元素数组参数，实得 {:?}", other),
        }
    }

    // -- 搬自 Dart：`automation_client_test.dart` -------------------------

    /// Dart: `'parses automation fields'`
    #[test]
    fn parses_automation_fields() {
        let e = entry(json!({
            "automationId": "a1",
            "title": "定时巡检",
            "prompt": "检查一下",
            "cronExpr": "*/5 * * * *",
            "model": "builtin:zai-coding-plan/GLM-5.2",
            "provider": "glm",
            "mode": "yolo",
            "thoughtLevel": "max",
            "recurring": true,
            "scheduleRule": {"unit": "minute", "interval": 5, "hour": 11, "minute": 17},
            "enabled": true,
            "lifecycleStatus": "active",
            "runCount": 3,
            "lastRunAt": 1000,
            "nextRunAt": 2000,
            "createdAt": 500,
            "updatedAt": 900,
        }));
        assert_eq!(e.automation_id, "a1");
        assert_eq!(e.title, "定时巡检");
        assert_eq!(e.cron_expr, "*/5 * * * *");
        assert!(e.enabled);
        assert!(e.recurring);
        assert_eq!(e.run_count, 3);
        assert_eq!(e.next_run_at, Some(2000));
        // 其余字段一并锁定（Dart 测试没断言，但都是同一条构造表达式）。
        assert_eq!(e.prompt, "检查一下");
        assert_eq!(e.model, "builtin:zai-coding-plan/GLM-5.2");
        assert_eq!(e.provider, "glm");
        assert_eq!(e.mode, "yolo");
        assert_eq!(e.thought_level, "max");
        assert_eq!(e.lifecycle_status, "active");
        assert_eq!(e.last_run_at, 1000);
        assert_eq!(e.created_at, 500);
        assert_eq!(e.updated_at, 900);
        assert_eq!(e.schedule_rule, Some(json!({"unit": "minute", "interval": 5, "hour": 11, "minute": 17})));
        // `raw` 原样保留。
        assert_eq!(e.raw.get("automationId"), Some(&json!("a1")));
    }

    /// Dart: `'scheduleLabel for minute interval'`
    #[test]
    fn schedule_label_for_minute_interval() {
        let e = entry(json!({
            "scheduleRule": {"unit": "minute", "interval": 5, "hour": 11, "minute": 17},
            "cronExpr": "*/5 * * * *",
        }));
        assert_eq!(e.schedule_label(), "每 5 分钟");
    }

    /// Dart: `'scheduleLabel for hour interval'`
    #[test]
    fn schedule_label_for_hour_interval() {
        let e = entry(json!({
            "scheduleRule": {"unit": "hour", "interval": 2, "hour": 9, "minute": 0},
            "cronExpr": "0 */2 * * *",
        }));
        assert_eq!(e.schedule_label(), "每 2 小时");
    }

    /// Dart: `'scheduleLabel for daily'`
    #[test]
    fn schedule_label_for_daily() {
        let e = entry(json!({
            "scheduleRule": {"unit": "day", "interval": 1, "hour": 9, "minute": 5},
            "cronExpr": "5 9 * * *",
        }));
        assert_eq!(e.schedule_label(), "每天 09:05");
    }

    /// Dart: `'scheduleLabel falls back to cronExpr'`
    #[test]
    fn schedule_label_falls_back_to_cron_expr() {
        let e = entry(json!({"cronExpr": "0 8 * * 1"}));
        assert_eq!(e.schedule_label(), "0 8 * * 1");
    }

    /// Dart: `'defaults when fields missing'`
    #[test]
    fn defaults_when_fields_missing() {
        let e = entry(json!({}));
        assert_eq!(e.automation_id, "");
        assert!(!e.enabled);
        assert_eq!(e.run_count, 0);
        assert_eq!(e.next_run_at, None);
        // 缺 cronExpr 且无计划规则 → 占位符。
        assert_eq!(e.schedule_label(), DASH);
    }

    // -- 取自 `automation_roundtrip_test.dart` 的请求载荷 ------------------

    /// 真机探测脚本里 `createAutomation` 的完整载荷（`automation_roundtrip_test.dart:48-75`）
    /// 必须与本模块 `create()` 发出的字节一致：通道、方法、参数结构、字段名、超时。
    #[test]
    fn create_request_matches_roundtrip_probe() {
        let mut channels = ready_channels();
        let workspace = scope("/repo", Some("id-1"));
        let mut client = AutomationClient::new(&mut channels, &workspace);

        let mut params = CreateAutomationParams::new(
            "zemote-probe-automation",
            "这是一条来自 zemote 的协议探测任务，请只回复 ok 两个字",
            "*/5 * * * *",
            "builtin:zai-coding-plan/GLM-5.2",
            "glm",
            "yolo",
            "max",
            true,
            json!({"unit": "minute", "interval": 5, "hour": 11, "minute": 17})
                .as_object()
                .unwrap()
                .clone(),
        );
        // 探测脚本创建的是**停用**的探测任务（`'enabled': false`）。
        params.enabled = false;

        let id = client.create(&params);
        drop(client);

        let sent = channels.drain_outgoing();
        assert_eq!(sent.len(), 1);
        let arg = expect_call(
            &channels,
            &sent[0],
            id,
            "createAutomation",
            AutomationClient::CREATE_TIMEOUT_MS,
        );
        assert_eq!(
            arg,
            Value::Array(vec![Value::Object(json!({
                "workspacePath": "/repo",
                "workspaceIdentity": "id-1",
                "title": "zemote-probe-automation",
                "prompt": "这是一条来自 zemote 的协议探测任务，请只回复 ok 两个字",
                "cronExpr": "*/5 * * * *",
                "model": "builtin:zai-coding-plan/GLM-5.2",
                "provider": "glm",
                "mode": "yolo",
                "thoughtLevel": "max",
                "recurring": true,
                "scheduleRule": {"unit": "minute", "interval": 5, "hour": 11, "minute": 17},
                "enabled": false,
            }))])
        );
    }

    /// 探测脚本里 `listAllAutomations` 的参数就是 scope 本身（`automation_roundtrip_test.dart:82`）。
    #[test]
    fn list_request_matches_roundtrip_probe() {
        let mut channels = ready_channels();
        let workspace = scope("/repo", Some("id-1"));
        let mut client = AutomationClient::new(&mut channels, &workspace);

        let id = client.list();
        drop(client);

        let sent = channels.drain_outgoing();
        let arg = expect_call(
            &channels,
            &sent[0],
            id,
            "listAllAutomations",
            AutomationClient::LIST_TIMEOUT_MS,
        );
        assert_eq!(
            arg,
            Value::Array(vec![Value::Object(json!({
                "workspacePath": "/repo",
                "workspaceIdentity": "id-1",
            }))])
        );
    }

    /// 探测脚本里启用/停用与删除的载荷（`automation_roundtrip_test.dart:93-105` / `118-129`）。
    #[test]
    fn mutate_requests_match_roundtrip_probe() {
        let mut channels = ready_channels();
        let workspace = scope("/repo", Some("id-1"));
        let mut client = AutomationClient::new(&mut channels, &workspace);

        let enable_id = client.set_enabled("a1", true);
        let delete_id = client.delete("a1");
        let run_id = client.run_now("a1");
        let restart_id = client.restart("a1");
        drop(client);

        let sent = channels.drain_outgoing();
        assert_eq!(sent.len(), 4);

        let arg = expect_call(
            &channels,
            &sent[0],
            enable_id,
            "setAutomationEnabled",
            AutomationClient::MUTATE_TIMEOUT_MS,
        );
        assert_eq!(
            sole_object(&arg),
            json!({"workspacePath": "/repo", "workspaceIdentity": "id-1", "automationId": "a1", "enabled": true})
                .as_object()
                .unwrap()
                .clone()
        );

        for (body, id, method) in [
            (&sent[1], delete_id, "deleteAutomation"),
            (&sent[2], run_id, "runAutomationNow"),
            (&sent[3], restart_id, "restartAutomation"),
        ] {
            let arg = expect_call(&channels, body, id, method, AutomationClient::MUTATE_TIMEOUT_MS);
            assert_eq!(
                sole_object(&arg),
                json!({"workspacePath": "/repo", "workspaceIdentity": "id-1", "automationId": "a1"})
                    .as_object()
                    .unwrap()
                    .clone(),
                "method={method}"
            );
        }
    }

    /// 探测脚本的 `list contains created` / `deleted (not in list)` 两处判定
    /// （`automation_roundtrip_test.dart:84-86` / `138-139`）用响应解析复现。
    #[test]
    fn list_contains_created_and_not_deleted() {
        let created = json!({"automationId": "a1", "title": "probe"});
        let other = json!({"automationId": "a2", "title": "other"});

        let data = Value::Object(json!([created.clone(), other.clone()]));
        let list = parse_automation_list(&data);
        assert_eq!(list.len(), 2);
        assert!(list.iter().any(|a| a.automation_id == "a1"));

        // 删除后列表里不再有 a1。
        let data = Value::Object(json!([other]));
        let list = parse_automation_list(&data);
        assert!(!list.iter().any(|a| a.automation_id == "a1"));
    }

    /// `create` 的响应解析：是 Map 就用它，否则空 entry（Dart 的 `res is Map ? ... : {}`）。
    #[test]
    fn parse_created_returns_entry_or_empty() {
        let entry = parse_automation_entry(&Value::Object(json!({"automationId": "a1"})));
        assert_eq!(entry.automation_id, "a1");

        // 非 Map（Dart 里 `res is Map` 为假）→ 全默认值的 entry。
        for data in [
            Value::Undefined,
            Value::from("nope"),
            Value::Array(vec![Value::Object(json!({"automationId": "a1"}))]),
        ] {
            let entry = parse_automation_entry(&data);
            assert_eq!(entry.automation_id, "", "data={:?}", data);
            assert!(entry.raw.is_empty());
        }
    }

    /// `list` 的响应解析：非 List → 空；List 里的非 Map 元素被丢弃（`whereType<Map>()`）。
    #[test]
    fn parse_list_filters_non_maps_and_non_lists() {
        for data in [Value::Undefined, Value::from("nope"), Value::Object(json!({"a": 1}))] {
            assert!(parse_automation_list(&data).is_empty(), "data={:?}", data);
        }

        let data = Value::Object(json!([
            {"automationId": "a1"},
            "not a map",
            42,
            {"automationId": "a2"},
        ]));
        let list = parse_automation_list(&data);
        assert_eq!(
            list.iter().map(|e| e.automation_id.clone()).collect::<Vec<_>>(),
            vec!["a1".to_string(), "a2".to_string()]
        );

        // tag 4 的 List 与 tag 5 的 JSON 数组在 Dart 里都是 `is List`。
        let data = Value::Array(vec![Value::Object(json!({"automationId": "a3"}))]);
        let list = parse_automation_list(&data);
        assert_eq!(list.len(), 1);
        assert_eq!(list[0].automation_id, "a3");
    }

    // -- 补充：scope 形状、scheduleLabel 分支、字段类型边界 ----------------

    /// `_scopeMap` 的两处细节：`workspacePath` 恒出现（可为 null），
    /// `workspaceIdentity` 仅在非 null 时出现。
    #[test]
    fn scope_map_omits_null_identity_but_keeps_path() {
        let mut channels = ready_channels();
        let workspace = scope("/repo", None);
        let client = AutomationClient::new(&mut channels, &workspace);
        assert_eq!(client.scope_map(), json!({"workspacePath": "/repo"}).as_object().unwrap().clone());

        // identity 显式为 null → 键被省略。
        let workspace = json!({"workspacePath": "/repo", "workspaceIdentity": null})
            .as_object()
            .unwrap()
            .clone();
        let client = AutomationClient::new(&mut channels, &workspace);
        assert_eq!(client.scope_map(), json!({"workspacePath": "/repo"}).as_object().unwrap().clone());

        // workspacePath 缺失 → 键仍出现、值为 null（Dart 的 map 字面量保留值为 null 的键）。
        let client = AutomationClient::new(&mut channels, &serde_json::Map::new());
        assert_eq!(client.scope_map(), json!({"workspacePath": null}).as_object().unwrap().clone());

        // scope 里的其它键（如 bootstrap 里的额外字段）不会被带出去。
        let workspace = json!({"workspacePath": "/repo", "workspaceIdentity": "id-1", "extra": 1})
            .as_object()
            .unwrap()
            .clone();
        let client = AutomationClient::new(&mut channels, &workspace);
        assert_eq!(
            client.scope_map(),
            json!({"workspacePath": "/repo", "workspaceIdentity": "id-1"})
                .as_object()
                .unwrap()
                .clone()
        );
    }

    /// `scheduleLabel` 的 `week` 分支：`weekday` 优先、回落 `dayOfWeek`、越界/非数字 → 空标签。
    #[test]
    fn schedule_label_week_arm() {
        assert_eq!(
            entry(json!({"scheduleRule": {"unit": "week", "weekday": 1}})).schedule_label(),
            "每周 周一"
        );
        assert_eq!(
            entry(json!({"scheduleRule": {"unit": "week", "dayOfWeek": 2}})).schedule_label(),
            "每周 周二"
        );
        // weekday 为 null 时回落到 dayOfWeek。
        assert_eq!(
            entry(json!({"scheduleRule": {"unit": "week", "weekday": null, "dayOfWeek": 6}}))
                .schedule_label(),
            "每周 周六"
        );
        // 越界 → 空标签（Dart 的 `_weekdayLabel` 返回 ''，于是字符串尾部留一个空格）。
        assert_eq!(
            entry(json!({"scheduleRule": {"unit": "week", "weekday": 7}})).schedule_label(),
            "每周 "
        );
        assert_eq!(
            entry(json!({"scheduleRule": {"unit": "week", "weekday": "1"}})).schedule_label(),
            "每周 "
        );
        // 判定用原值、取表用 toInt() 截断：6.9 → 周六。
        assert_eq!(
            entry(json!({"scheduleRule": {"unit": "week", "weekday": 6.9}})).schedule_label(),
            "每周 周六"
        );
    }

    /// `scheduleLabel` 的 `default` 分支与 `_two` 的补零边界。
    #[test]
    fn schedule_label_default_arm_and_padding() {
        // 未知 unit → 回落 cronExpr。
        assert_eq!(
            entry(json!({"scheduleRule": {"unit": "year"}, "cronExpr": "0 0 1 1 *"}))
                .schedule_label(),
            "0 0 1 1 *"
        );
        // unit 缺失 → 同上；cronExpr 也空 → 占位符。
        assert_eq!(
            entry(json!({"scheduleRule": {"interval": 3}})).schedule_label(),
            DASH
        );
        // scheduleRule 不是对象 → 视为“无规则”，回落 cronExpr。
        assert_eq!(
            entry(json!({"scheduleRule": "minute", "cronExpr": "* * * * *"})).schedule_label(),
            "* * * * *"
        );
        // interval 缺失默认 1。
        assert_eq!(
            entry(json!({"scheduleRule": {"unit": "minute"}})).schedule_label(),
            "每 1 分钟"
        );
        // hour/minute 缺失默认 0；`_two` 对 >= 100 不补零。
        assert_eq!(
            entry(json!({"scheduleRule": {"unit": "day"}})).schedule_label(),
            "每天 00:00"
        );
        assert_eq!(
            entry(json!({"scheduleRule": {"unit": "day", "hour": 111, "minute": 7}}))
                .schedule_label(),
            "每天 111:07"
        );
    }

    /// `recurring` / `enabled` 是**严格** `== true`：JSON 的 `1` / `"true"` 都不算。
    #[test]
    fn flags_are_strictly_true() {
        let e = entry(json!({"recurring": 1, "enabled": "true"}));
        assert!(!e.recurring);
        assert!(!e.enabled);
        let e = entry(json!({"recurring": true, "enabled": true}));
        assert!(e.recurring);
        assert!(e.enabled);
    }

    /// 数字字段：`toInt()` 向零截断、缺省回落；字符串（Dart 会抛 TypeError）在 Rust 侧回落默认值。
    #[test]
    fn number_fields_truncate_and_default() {
        let e = entry(json!({
            "runCount": 3.7,
            "lastRunAt": 1.9,
            "nextRunAt": 2.5,
            "createdAt": "oops",
            "updatedAt": null,
        }));
        assert_eq!(e.run_count, 3);
        assert_eq!(e.last_run_at, 1);
        assert_eq!(e.next_run_at, Some(2));
        // PORT-NOTE: Dart 的 `as num?` 对字符串抛 TypeError；本实现回落 0。
        assert_eq!(e.created_at, 0);
        assert_eq!(e.updated_at, 0);
        // nextRunAt 缺失 → None（Dart 的 `?? null` 语义）。
        assert_eq!(entry(json!({})).next_run_at, None);
    }

    /// 字符串字段用 Dart 的 `toString` 语义：数字/布尔也能进来，null/缺失 → 空串。
    #[test]
    fn string_fields_use_dart_to_string() {
        let e = entry(json!({"title": 42, "prompt": true, "model": null, "provider": {"a": 1}}));
        assert_eq!(e.title, "42");
        assert_eq!(e.prompt, "true");
        assert_eq!(e.model, "");
        assert_eq!(e.provider, "{a: 1}");
    }

    /// 端到端形状：`list()` 的应答经 `handle_message` 回来后可直接解析成 entries。
    #[test]
    fn list_response_flows_through_channel_client() {
        let mut channels = ready_channels();
        let workspace = scope("/repo", Some("id-1"));
        let mut client = AutomationClient::new(&mut channels, &workspace);
        let id = client.list();
        drop(client);
        let sent = channels.drain_outgoing();
        assert_eq!(sent.len(), 1);
        assert_eq!(decode_request(&sent[0]).0, {
            Value::Array(vec![
                Value::from_i64(ChannelClient::REQ_PROMISE),
                Value::from_i64(id as i64),
                Value::from(Channels::ZCODE_AGENT),
                Value::from("listAllAutomations"),
            ])
        });

        // 桌面端应答：[201, id] + JSON 数组。
        let mut response = encode_to_vec(&Value::Array(vec![
            Value::from_i64(ChannelClient::RES_PROMISE_SUCCESS),
            Value::from_i64(id as i64),
        ]))
        .unwrap();
        response.extend_from_slice(
            &encode_to_vec(&Value::Object(json!([{"automationId": "a1", "runCount": 2}]))).unwrap(),
        );

        let events = channels.handle_message(&response);
        match &events[..] {
            [ChannelEvent::PromiseSuccess { data, .. }] => {
                let list = parse_automation_list(data);
                assert_eq!(list.len(), 1);
                assert_eq!(list[0].automation_id, "a1");
                assert_eq!(list[0].run_count, 2);
            }
            other => panic!("期望 PromiseSuccess，实得 {:?}", other),
        }
    }
}
