//! 对应 `Reference_code/lib/protocol/rpc_transport.dart`。
//!
//! rpc-frame 分片传输：逻辑消息按 512 KiB 切片，每片带 crc32 校验和；接收端重组完整消息后
//! 用 `rpc-frame-ack` 回执。对应网页端的 `Xf`/`Zf` schema、`sp()`/`lp()`。
//!
//! 本模块**纯逻辑、同步、无 I/O**：没有 socket、没有 `std::time`、没有 async、没有流。
//! Dart 版的 `StreamController` / `sendPayload` / `onLog` 全部由宿主（Kotlin）驱动；
//! `Timer` 则由核心的 `zemote_client` 桥生命周期驱动（见下）。
//!
//! # 宿主如何驱动这个状态机（给 Kotlin 侧的接口说明）
//!
//! ```text
//! 1) 构造：RpcFrameTransport::new(bridgeSessionId, sink, bridgeGeneration, recoveryId)
//!    sink 是宿主实现的 FrameSink（对应 Dart 的 sendPayload 回调）：拿到
//!    RpcFramePayload 后自行序列化（serde_json）并塞进中继信封发出去。
//!
//! 2) 发一条逻辑消息：transport.send_message(bytes)?
//!    分几片就调几次 sink.send（seq 逐片递增）。Err(InvalidState) 对应 Dart 的
//!    StateError，错误串是原样的 l10n key（remote.rpcFrame.*），宿主按 key 本地化。
//!
//! 3) 每收到一个中继载荷：transport.accept_payload(&json_value, now_ms) -> AcceptOutcome
//!    - consumed == false → 不是本层的载荷（如 workspace-list-updated），交给别的处理器；
//!    - message           → 重组完成且校验通过的完整逻辑消息，宿主投递给上层
//!                          （对应 Dart messages 流的一次 add）；
//!    - logs              → Dart onLog 的内容，宿主逐条写日志；
//!    - 重组成功时 rpc-frame-ack 已经由 sink.send 发出，宿主不用再管。
//!
//! 4) 定时清理：Dart 用 Timer.periodic(30s) 调 _purgeStale()。Rust 侧这个周期**由核心自己
//!    驱动**（`zemote_client` 的桥生命周期：attach 时 arm、到点 purge 并重排、dispose/换栈时
//!    取消），本模块只提供 `transport.purge_stale(now_ms)`；超过
//!    STALE_ASSEMBLY_SECONDS(60s) 没凑齐的重组状态被丢弃，返回的日志行由核心作为
//!    `ZemoteEvent::Log` 发出。
//!
//! 5) 销毁：transport.dispose() 清空未完成的重组状态；核心在同一个桥生命周期里取消第 4 步的
//!    定时器。
//! ```
//!
//! `now_ms` 必须与第 4 步同源（Unix epoch 毫秒或单调时钟都行，只要前后一致）；它替代
//! Dart 内部的 `DateTime.now()`。
//!
//! # 与 Dart 的差异（逐处都留了 `PORT-NOTE`）
//! - 时间由宿主传入（`now_ms`），本模块不读时钟。
//! - `messages` 流 → [`AcceptOutcome::message`]；`onLog` 回调 → [`AcceptOutcome::logs`]。
//! - `sendPayload(Map<String, dynamic>)` → [`FrameSink::send`] 收一个定型的
//!   [`RpcFramePayload`]（字段名用 `#[serde(rename)]` 锁死，与网页端一致）。

use base64::engine::general_purpose::{STANDARD, STANDARD_NO_PAD};
use base64::Engine as _;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::BTreeMap;

use crate::crc32::Crc32;
use crate::error::{ProtocolError, Result};

/// 对应 Dart `RpcFrameTransport.maxPhysicalFrameBytes`（1 MiB）。
///
/// 单个**物理帧**（含 JSON 信封）的上限。本模块不用它（它约束的是中继层信封：
/// 512 KiB 分片 base64 后约 683 KiB，仍在 1 MiB 内），保留常量供宿主/中继层对照。
pub const MAX_PHYSICAL_FRAME_BYTES: usize = 1024 * 1024;

/// 对应 Dart `RpcFrameTransport.maxMessageBytes`（64 MiB），但**按对端权威值收紧到 16 MiB**。
///
/// 对端 schema 的 `rpc-frame` 预算 `bd`（网页端 `validation-CqXZOLXO.js` @128,536，
/// 桌面端同构 `out/main/chunk-OW6EUS2G.js` @545,748）是
/// `maxMessageBytes: 16*1024*1024`、`maxFragments: 64`。超过它的逻辑消息，
/// **每一片都会被对端丢弃**（schema 失败 → `handleDataPayload` 静默 return），
/// 而且没有错误回执、只能靠 ack 超时反推。Dart 的 64 MiB 在线上永远到不了，
/// 只会让本地误以为能发。
pub const MAX_MESSAGE_BYTES: usize = 16 * 1024 * 1024;

/// 对应 Dart `RpcFrameTransport.maxFragments`（256），**按对端权威值收紧到 64**。
///
/// 见 [`MAX_MESSAGE_BYTES`]：`bd.maxFragments = 64`。16 MiB / 512 KiB = 32 片，
/// 仍在 64 以内，所以正常发送撞不到这个上限。
///
/// 注意别与 `Uu.logicalFrameAssemblyMaxFragments: 1024` 混淆——那是**桥内逻辑帧**
/// 的预算（`main ↔ host` 内部 RPC），不是 `rpc-frame` 的线上分片上限。
pub const MAX_FRAGMENTS: usize = 64;

/// 对应 Dart `RpcFrameTransport._fragmentPayloadBytes`（512 KiB）。
///
/// 每片**原始**字节数；base64 会放大 4/3，故 512 KiB → 约 683 KiB，信封仍 < 1 MiB。
/// Dart 里是私有常量，Rust 侧同样不公开（测试用 `super::` 可见）。
const FRAGMENT_PAYLOAD_BYTES: usize = 512 * 1024;

/// 对应 Dart `_purgeStale()` 里的 `inSeconds > 60`，**按对端权威值收紧到 30 s**。
///
/// 对端 `bd.assemblyTimeoutMs = 3e4`：一条逻辑消息必须在 30 s 内收完，否则终态降级。
/// 我们本地只是清理未完成的装配（软清理），但阈值应对齐，免得替对端多保留半分钟
/// 已经不可能完成的半条消息。
pub const STALE_ASSEMBLY_SECONDS: i64 = 30;

/// 对应 Dart 构造函数里的 `Timer.periodic(const Duration(seconds: 30), ...)`。
///
/// 本模块不持定时器；这个周期**由核心自己驱动** —— `zemote_client` 在桥 attach 时按本常量
/// arm 一个定时器，到点调 [`RpcFrameTransport::purge_stale`] 并重排，桥 dispose / 换栈时取消。
/// 宿主不参与。
pub const PURGE_INTERVAL_SECONDS: u64 = 30;

/// `zcode_type` 的取值之一：物理帧（Dart 里是字面量 `'rpc-frame'`）。
pub const ZCODE_TYPE_RPC_FRAME: &str = "rpc-frame";

/// `zcode_type` 的取值之一：重组回执（Dart 里是字面量 `'rpc-frame-ack'`）。
pub const ZCODE_TYPE_RPC_FRAME_ACK: &str = "rpc-frame-ack";

/// 对应 Dart 帧里的 `'checksum': {'algorithm': 'crc32', 'value': ...}`。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct FrameChecksum {
    /// 恒为 `"crc32"`（Dart 只写死这一个值；收端**不读**它，见
    /// [`RpcFrameTransport::accept_payload`] 的 PORT-NOTE 第 2 条）。
    pub algorithm: String,
    /// `Crc32::hex_of` 的 8 位小写十六进制字符串。
    pub value: String,
}

/// 出站载荷：对应 Dart 里传给 `sendPayload` 的那个 `Map<String, dynamic>`。
///
/// 一个 struct 同时覆盖 `rpc-frame` 与 `rpc-frame-ack` 两种形状（Dart 就是同一个 map
/// 的两个分支）：`zcode_type` 决定形状，其余字段按形状填 `Option`。
/// `#[serde(rename)]` 锁死线上字段名；`skip_serializing_if` 保证缺省字段**不出现**
/// （而不是出现 `null`）——Dart 的 `if (x != null)` 展开语义。
///
/// PORT-NOTE: 该类型应由主线提升为公共类型——`relay_client` / `zemote_client` 同样要拼
/// 中继信封（`sendPayload` 的对应物），会共用这个载荷形状；本模块先就地定义。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RpcFramePayload {
    /// `"rpc-frame"` 或 `"rpc-frame-ack"`，见 [`ZCODE_TYPE_RPC_FRAME`]。
    #[serde(rename = "zcode_type")]
    pub zcode_type: String,

    /// 对应 Dart `_identity['bridgeSessionId']`（帧与 ack 都带）。
    #[serde(rename = "bridgeSessionId")]
    pub bridge_session_id: String,

    /// 对应 Dart `_identity['bridgeGeneration']`——**仅当构造时非 null 才出现**。
    #[serde(rename = "bridgeGeneration", skip_serializing_if = "Option::is_none")]
    pub bridge_generation: Option<i64>,

    /// 对应 Dart `_identity['recoveryId']`——**仅当构造时非 null 才出现**。
    #[serde(rename = "recoveryId", skip_serializing_if = "Option::is_none")]
    pub recovery_id: Option<String>,

    /// 物理帧序号（每片 +1，跨消息连续），仅 `rpc-frame` 有。
    #[serde(skip_serializing_if = "Option::is_none")]
    pub seq: Option<u64>,

    /// 逻辑消息序号（每调一次 `send_message` +1），仅 `rpc-frame` 有。
    #[serde(rename = "messageSeq", skip_serializing_if = "Option::is_none")]
    pub message_seq: Option<u64>,

    /// 本片在消息内的下标（0 起），仅 `rpc-frame` 有。
    #[serde(rename = "fragmentIndex", skip_serializing_if = "Option::is_none")]
    pub fragment_index: Option<usize>,

    /// 本消息的总片数，仅 `rpc-frame` 有。
    #[serde(rename = "fragmentCount", skip_serializing_if = "Option::is_none")]
    pub fragment_count: Option<usize>,

    /// 重组后消息的字节数（校验用），仅 `rpc-frame` 有。
    #[serde(rename = "messageBytes", skip_serializing_if = "Option::is_none")]
    pub message_bytes: Option<usize>,

    /// crc32 校验和，仅 `rpc-frame` 有。
    #[serde(skip_serializing_if = "Option::is_none")]
    pub checksum: Option<FrameChecksum>,

    /// 本片字节的标准 base64（带 padding），仅 `rpc-frame` 有。
    #[serde(rename = "dataBase64", skip_serializing_if = "Option::is_none")]
    pub data_base64: Option<String>,

    /// 被确认的逻辑消息序号，仅 `rpc-frame-ack` 有。
    #[serde(rename = "ackMessageSeq", skip_serializing_if = "Option::is_none")]
    pub ack_message_seq: Option<u64>,
}

/// 宿主的发送能力，对应 Dart 构造函数参数 `sendPayload`。
///
/// 本模块只负责**构造**载荷，真正写 socket 的是宿主：拿到 [`RpcFramePayload`] 后
/// 序列化并塞进中继信封。
pub trait FrameSink {
    /// 发送一个 rpc-frame / rpc-frame-ack 载荷。
    ///
    /// - [`RpcFrameTransport::send_message`] 里返回 `Err` 会**立即中断**发送循环并把错误
    ///   抛给调用方（对应 Dart 的 `sendPayload` 抛异常：已发出的片与已递增的计数器都不回滚）。
    /// - [`RpcFrameTransport::accept_payload`] 里发 ack 失败**不会**丢消息：已重组出的消息
    ///   照常返回，失败被记成一条日志（见该方法上的 `PORT-NOTE`）。
    fn send(&mut self, payload: &RpcFramePayload) -> Result<()>;
}

/// [`RpcFrameTransport::accept_payload`] 的结果。
///
/// 对应 Dart 的「返回 bool + 往 `messages` 流 add 一次 + 若干次 `onLog`」三件事。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct AcceptOutcome {
    /// 对应 Dart `acceptPayload` 的返回值：载荷是否被本层消费。
    /// `false` 表示这不是 rpc-frame/ack，或 `bridgeSessionId` 不是本连接。
    pub consumed: bool,

    /// 本次重组完成并通过校验的逻辑消息（对应 Dart `messages` 流的一次 `add`）。
    /// 一次 `accept_payload` 最多产出一条。
    pub message: Option<Vec<u8>>,

    /// 对应 Dart `onLog?.call(line)` 的日志行，顺序与 Dart 一致。
    pub logs: Vec<String>,
}

/// 对应 Dart `class RpcFrameTransport`（网页端 `Xf`/`Zf` 分片传输）。
pub struct RpcFrameTransport {
    /// 对应 Dart `bridgeSessionId`（构造后不可变）。
    pub bridge_session_id: String,

    /// 对应 Dart `bridgeGeneration`（构造后不可变）。
    pub bridge_generation: Option<i64>,

    /// 对应 Dart `recoveryId`（构造后不可变）。
    pub recovery_id: Option<String>,

    /// 对应 Dart 的 `sendPayload` 回调。
    ///
    /// PORT-NOTE: 这里与契约草稿里的 `fn send(&mut self, frame: &[u8])` 不同——交给宿主的
    /// 不是裸字节，而是**已定型的 JSON 载荷**（`Map<String, dynamic>` 的 Rust 对应物）：
    /// 线上字段名必须零漂移，若传字节，宿主还得反序列化才能拼中继信封，而序列化本来就是
    /// 宿主的活（只有它知道信封长什么样）。若主线坚持字节接口，改这一处即可（调用点 2 个）。
    sink: Box<dyn FrameSink>,

    /// 对应 Dart `_seq`：物理帧计数器，**每片** +1，跨消息连续。
    seq: u64,

    /// 对应 Dart `_messageSeq`：逻辑消息计数器，**每调一次 `send_message`** +1。
    message_seq: u64,

    /// 对应 Dart `_assemblies`：未完成的重组状态，键是 `messageSeq`。
    ///
    /// PORT-NOTE: Dart 是 `LinkedHashMap`（按插入序）；Rust 这里用 `BTreeMap`（按 seq 升序），
    /// 只有在 `messageSeq` 乱序到达时，「purge 日志行的先后」才会与 Dart 不同——纯日志顺序，
    /// 不影响任何线上行为。不为此引入 `indexmap` 依赖。
    assemblies: BTreeMap<i64, Assembly>,
}

impl RpcFrameTransport {
    /// 对应 Dart `RpcFrameTransport({required bridgeSessionId, required sendPayload,
    /// bridgeGeneration, recoveryId, onLog})`。
    ///
    /// - 参数顺序与 Dart 声明顺序一致（`bridgeSessionId`, `sendPayload`, `bridgeGeneration`,
    ///   `recoveryId`）。
    /// - Dart 的 `onLog` 回调在 Rust 侧变成 [`AcceptOutcome::logs`] /
    ///   [`RpcFrameTransport::purge_stale`] 的返回值，宿主负责真正落日志。
    /// - Dart 构造函数里启动的 30 秒定时器由核心的 `zemote_client` 桥生命周期驱动
    ///   （见模块文档第 4 步），本模块与宿主都不持有它。
    pub fn new(
        bridge_session_id: String,
        sink: Box<dyn FrameSink>,
        bridge_generation: Option<i64>,
        recovery_id: Option<String>,
    ) -> Self {
        Self {
            bridge_session_id,
            bridge_generation,
            recovery_id,
            sink,
            seq: 0,
            message_seq: 0,
            assemblies: BTreeMap::new(),
        }
    }

    /// 对应 Dart `sendMessage(Uint8List bytes)`。
    ///
    /// 把一条逻辑消息切片后逐片交给 [`FrameSink::send`]。三个校验失败都对应 Dart 的
    /// `StateError`，错误串**原样**保留 Dart 的 l10n key（宿主按 key 本地化）：
    /// `remote.rpcFrame.emptyMessage` / `remote.rpcFrame.messageTooLarge` /
    /// `remote.rpcFrame.fragmentLimitExceeded`。
    ///
    /// PORT-NOTE: `fragmentLimitExceeded` 在 Dart 里也**不可达**——`maxMessageBytes`
    /// （64 MiB）先被判掉，而 64 MiB / 512 KiB = 128 ≤ `maxFragments`（256）。照抄保留该分支，
    /// 以便将来常量调整时行为不变。
    pub fn send_message(&mut self, bytes: &[u8]) -> Result<()> {
        // Dart: if (bytes.isEmpty) throw StateError('remote.rpcFrame.emptyMessage');
        if bytes.is_empty() {
            return Err(ProtocolError::InvalidState(
                "remote.rpcFrame.emptyMessage".to_string(),
            ));
        }
        // Dart: if (bytes.length > maxMessageBytes) throw StateError('...messageTooLarge');
        if bytes.len() > MAX_MESSAGE_BYTES {
            return Err(ProtocolError::InvalidState(
                "remote.rpcFrame.messageTooLarge".to_string(),
            ));
        }
        // Dart: final messageSeq = ++_messageSeq;
        self.message_seq += 1;
        let message_seq = self.message_seq;
        let checksum = Crc32::hex_of(bytes);
        // Dart: (bytes.length + _fragmentPayloadBytes - 1) ~/ _fragmentPayloadBytes
        let fragment_count = (bytes.len() + FRAGMENT_PAYLOAD_BYTES - 1) / FRAGMENT_PAYLOAD_BYTES;
        if fragment_count > MAX_FRAGMENTS {
            return Err(ProtocolError::InvalidState(
                "remote.rpcFrame.fragmentLimitExceeded".to_string(),
            ));
        }
        for i in 0..fragment_count {
            let start = i * FRAGMENT_PAYLOAD_BYTES;
            let end = (start + FRAGMENT_PAYLOAD_BYTES).min(bytes.len());
            let chunk = &bytes[start..end];
            // Dart: _seq += 1;（在 sendPayload 之前，故发送失败也不回滚）
            self.seq += 1;
            let payload = self.frame_payload(
                self.seq,
                message_seq,
                i,
                fragment_count,
                bytes.len(),
                &checksum,
                chunk,
            );
            self.sink.send(&payload)?;
        }
        Ok(())
    }

    /// 对应 Dart `acceptPayload(Map<String, dynamic> payload)`。
    ///
    /// `now_ms` 是宿主的当前时间（毫秒），只在**新建**重组状态时被记下来（Dart 用
    /// `DateTime.now()` 记 `_Assembly.createdAt`）；配合 [`Self::purge_stale`] 做超时清理。
    ///
    /// 返回 [`AcceptOutcome`]，其中 `consumed` 就是 Dart 的返回值。本方法**没有** `Result`：
    /// Dart 的 `acceptPayload` 唯一会抛的路径是「dispose 之后往已关闭的 StreamController add」，
    /// 那是 Dart 流机制的副作用，Rust 侧消息由返回值交付，不存在该状态。
    ///
    /// PORT-NOTE: 三个刻意的取舍，都照抄了 Dart 的行为或有意收敛了它的崩溃面：
    /// 1. Dart 用 `payload['x'] as num?` 取值——字段类型不对（比如是字符串）会抛 `TypeError`
    ///    冲出 `acceptPayload`。Rust 侧按「字段缺失」处理（返回 consumed、不抛错），
    ///    因为 PORTING.md 要求不把 panic 当错误路径；协议里这些字段本就只可能是数字。
    /// 2. Dart 的 `checksum['algorithm']` **从不被读取**，本实现同样只读 `value`。
    /// 3. `dataBase64` 的解码比 Rust 默认解码器宽容（Dart `_Base64Codec.normalize` 会把
    ///    `-`/`_` 归一成 `+`/`/` 并自动补 padding），见私有函数 `decode_base64_like_dart`。
    pub fn accept_payload(&mut self, payload: &Value, now_ms: i64) -> AcceptOutcome {
        let mut outcome = AcceptOutcome::default();

        let zcode_type = payload.get("zcode_type").and_then(Value::as_str);
        // Dart: if (type == 'rpc-frame-ack') return true;
        if zcode_type == Some(ZCODE_TYPE_RPC_FRAME_ACK) {
            outcome.consumed = true;
            return outcome;
        }
        // Dart: if (type != 'rpc-frame') return false;
        if zcode_type != Some(ZCODE_TYPE_RPC_FRAME) {
            return outcome;
        }
        // Dart: if (payload['bridgeSessionId'] != bridgeSessionId) return false;
        if payload.get("bridgeSessionId").and_then(Value::as_str)
            != Some(self.bridge_session_id.as_str())
        {
            return outcome;
        }

        let message_seq = payload.get("messageSeq").and_then(as_dart_int);
        let fragment_index = payload.get("fragmentIndex").and_then(as_dart_int);
        let fragment_count = payload.get("fragmentCount").and_then(as_dart_int);
        let message_bytes = payload.get("messageBytes").and_then(as_dart_int);
        let data_base64 = payload.get("dataBase64").and_then(Value::as_str);
        let checksum = payload
            .get("checksum")
            .and_then(|c| c.get("value"))
            .and_then(Value::as_str);

        let (Some(message_seq), Some(fragment_index), Some(fragment_count), Some(message_bytes), Some(data_base64)) =
            (message_seq, fragment_index, fragment_count, message_bytes, data_base64)
        else {
            // Dart: 五个必填字段任一为 null → return true（消费掉，不报错）
            outcome.consumed = true;
            return outcome;
        };

        // Dart: 超限的**逻辑消息**直接丢掉，但绝不静默（首帧快照被丢 = 界面永远转圈，#9）。
        if fragment_count > MAX_FRAGMENTS as i64 || message_bytes > MAX_MESSAGE_BYTES as i64 {
            outcome.logs.push(format!(
                "[rpc] dropped oversized message {message_seq} \
                 ({message_bytes} bytes / {fragment_count} fragments)"
            ));
            outcome.consumed = true;
            return outcome;
        }
        // Dart: messageSeq < 0 || fragmentCount < 1 || fragmentIndex < 0 ||
        //       fragmentIndex >= fragmentCount || messageBytes < 1 → return true
        // 这些校验在**建重组状态之前**完成，所以坏元数据不会导致任何分配。
        if message_seq < 0
            || fragment_count < 1
            || fragment_index < 0
            || fragment_index >= fragment_count
            || message_bytes < 1
        {
            outcome.consumed = true;
            return outcome;
        }
        let fragment_count = fragment_count as usize;
        let fragment_index = fragment_index as usize;
        let message_bytes = message_bytes as usize;

        // Dart: try { chunk = base64.decode(dataBase64); } catch (_) { return true; }
        let Some(chunk) = decode_base64_like_dart(data_base64) else {
            outcome.consumed = true;
            return outcome;
        };
        // Dart: if (chunk.length > _fragmentPayloadBytes) return true;
        //
        // **不能照抄 512 KiB**：对端的分片尺寸是动态二分出来的（目标是把 1 MiB 的
        // **物理帧**预算用满），单片解码后可达约 786 KiB。用 512 KiB 卡会把对端
        // 发来的大半数分片整片丢掉，而且不回 ack → 对端 45 s 后 ackGraceExceeded
        // 终态降级。
        //
        // 正确的入站判据与对端 schema 一致：`dataBase64` 的**字符串长度**不超过
        // `bd.maxPhysicalFrameBytes`（1 MiB），且解码后非空（`Ed` 的 refine）。
        if data_base64.len() > MAX_PHYSICAL_FRAME_BYTES || chunk.is_empty() {
            outcome.consumed = true;
            return outcome;
        }

        // Dart: 已有 assembly 的元数据（片数/总字节/校验和）与本片声明不一致 → 整个丢掉重来。
        if let Some(existing) = self.assemblies.get(&message_seq) {
            if existing.fragment_count != fragment_count
                || existing.message_bytes != message_bytes
                || existing.checksum.as_deref() != checksum
            {
                self.assemblies.remove(&message_seq);
                outcome.consumed = true;
                return outcome;
            }
        }

        let complete = {
            let assembly = self
                .assemblies
                .entry(message_seq)
                .or_insert_with(|| Assembly::new(fragment_count, message_bytes, checksum, now_ms));
            assembly.add(fragment_index, chunk);
            assembly.is_complete()
        };

        if complete {
            // Dart: 先 remove 再 assemble —— 无论后面校验是否通过，重组状态都已清掉
            // （所以校验失败后重传整条消息可以重新开始）。
            let assembly = self
                .assemblies
                .remove(&message_seq)
                .expect("刚从 assemblies 里取出，必然存在");
            let message = assembly.assemble();
            if message.len() != assembly.message_bytes {
                outcome
                    .logs
                    .push(format!("[rpc] message {message_seq} size mismatch"));
            } else if assembly.checksum.is_none()
                || Some(Crc32::hex_of(&message).as_str()) == assembly.checksum.as_deref()
            {
                outcome.logs.push(format!(
                    "[rpc] message {message_seq} assembled ({} bytes)",
                    message.len()
                ));
                outcome.message = Some(message);
                let ack = self.ack_payload(message_seq as u64);
                // PORT-NOTE: Dart 的 `sendPayload` 返回 void，ack 发不出去无法表达（只有把回调
                // 抛出的异常往外扔）。这里选择「不丢已重组出的消息」：失败记成一条日志，
                // message 照常返回。若主线要改成向外传播，改动点只在这一处。
                if let Err(err) = self.sink.send(&ack) {
                    outcome
                        .logs
                        .push(format!("[rpc] ack send failed: {err}"));
                }
            } else {
                outcome
                    .logs
                    .push(format!("[rpc] message {message_seq} checksum mismatch"));
            }
        }

        outcome.consumed = true;
        outcome
    }

    /// 对应 Dart `_purgeStale()`（由 30 秒定时器驱动）。
    ///
    /// 由 `zemote_client` 的桥生命周期每 [`PURGE_INTERVAL_SECONDS`] 秒调一次（传当前的
    /// `now_ms`）；返回需要写出的日志行（对应 Dart 的
    /// `onLog?.call('[rpc] purged stale assembly $seq')`），**已经是格式化好的整行**。
    ///
    /// 判定照抄 Dart：`Duration.inSeconds > 60`，`inSeconds` 向零截断 ⇒ 实际阈值 61000 ms；
    /// `now_ms` 早于创建时间（时钟回拨）时差值为负，同样不清理。
    pub fn purge_stale(&mut self, now_ms: i64) -> Vec<String> {
        let mut stale = Vec::new();
        for (&seq, assembly) in self.assemblies.iter() {
            if (now_ms - assembly.created_at_ms) / 1000 > STALE_ASSEMBLY_SECONDS {
                stale.push(seq);
            }
        }
        let mut logs = Vec::new();
        for seq in stale {
            self.assemblies.remove(&seq);
            logs.push(format!("[rpc] purged stale assembly {seq}"));
        }
        logs
    }

    /// 对应 Dart `dispose()`。
    ///
    /// 清空未完成的重组状态。Dart 里还要 `cancel()` 定时器、`close()` 消息流——后者在 Rust
    /// 侧不存在（消息由返回值交付）；定时器由核心的 `zemote_client` 在同一桥生命周期里取消。
    ///
    /// PORT-NOTE: Dart 在 dispose 之后若又凑齐一条消息，`_messageController.add` 会抛
    /// `StateError('Cannot add event after closing')`。本模块没有流，不复制这个副作用：
    /// dispose 之后 `accept_payload` 仍按正常逻辑产出消息。宿主不该在 dispose 后继续驱动状态机。
    pub fn dispose(&mut self) {
        self.assemblies.clear();
    }

    /// 对应 Dart 的 `_identity` getter：`bridgeSessionId` 恒有，
    /// `bridgeGeneration` / `recoveryId` 仅在非 null 时出现。
    fn frame_payload(
        &self,
        seq: u64,
        message_seq: u64,
        fragment_index: usize,
        fragment_count: usize,
        message_bytes: usize,
        checksum: &str,
        chunk: &[u8],
    ) -> RpcFramePayload {
        RpcFramePayload {
            zcode_type: ZCODE_TYPE_RPC_FRAME.to_string(),
            bridge_session_id: self.bridge_session_id.clone(),
            bridge_generation: self.bridge_generation,
            recovery_id: self.recovery_id.clone(),
            seq: Some(seq),
            message_seq: Some(message_seq),
            fragment_index: Some(fragment_index),
            fragment_count: Some(fragment_count),
            message_bytes: Some(message_bytes),
            checksum: Some(FrameChecksum {
                algorithm: "crc32".to_string(),
                value: checksum.to_string(),
            }),
            data_base64: Some(STANDARD.encode(chunk)),
            ack_message_seq: None,
        }
    }

    /// 对应 Dart `acceptPayload` 成功分支里内联构造的 ack map。
    ///
    /// **与 Dart 不同：必须展开完整身份三元组。** Dart 只带 `bridgeSessionId`，
    /// 但对端的 ack 接收路径第一行就是 `rawIdentityMatches`
    /// （`out/main/chunk-JS2T33SG.js` @7,688）：`bridgeSessionId`、`bridgeGeneration`、
    /// `recoveryId` 三者严格 `===`，不等即**哑丢**（无日志、无回执）。丢了 ack →
    /// 对端重放缓冲收不到确认 → 45 s 后 `ackGraceExceeded` 终态降级。
    /// 网页端也是这么发的：`{zcode_type, ...this.identity, ackMessageSeq}`。
    fn ack_payload(&self, message_seq: u64) -> RpcFramePayload {
        RpcFramePayload {
            zcode_type: ZCODE_TYPE_RPC_FRAME_ACK.to_string(),
            bridge_session_id: self.bridge_session_id.clone(),
            bridge_generation: self.bridge_generation,
            recovery_id: self.recovery_id.clone(),
            seq: None,
            message_seq: None,
            fragment_index: None,
            fragment_count: None,
            message_bytes: None,
            checksum: None,
            data_base64: None,
            ack_message_seq: Some(message_seq),
        }
    }
}

/// 对应 Dart `class _Assembly`：一条逻辑消息的重组状态。
struct Assembly {
    /// 对应 Dart `fragmentCount`。
    fragment_count: usize,
    /// 对应 Dart `messageBytes`。
    message_bytes: usize,
    /// 对应 Dart `checksum`（可为 null：Dart 允许对端不带校验和，此时不做校验）。
    checksum: Option<String>,
    /// 对应 Dart `fragments`（`List<Uint8List?>.filled(fragmentCount, null)`）。
    fragments: Vec<Option<Vec<u8>>>,
    /// 对应 Dart `createdAt`（`DateTime.now()`）——Rust 侧改由宿主传入毫秒。
    created_at_ms: i64,
    /// 对应 Dart `received`。
    received: usize,
}

impl Assembly {
    /// 对应 Dart `_Assembly(fragmentCount, messageBytes, checksum)` + `createdAt`。
    fn new(
        fragment_count: usize,
        message_bytes: usize,
        checksum: Option<&str>,
        created_at_ms: i64,
    ) -> Self {
        Self {
            fragment_count,
            message_bytes,
            checksum: checksum.map(str::to_string),
            fragments: vec![None; fragment_count],
            created_at_ms,
            received: 0,
        }
    }

    /// 对应 Dart `_Assembly.add`：下标越界静默忽略；重复下标覆盖但不重复计数。
    fn add(&mut self, index: usize, data: Vec<u8>) {
        if index >= self.fragment_count {
            return;
        }
        if self.fragments[index].is_none() {
            self.received += 1;
        }
        self.fragments[index] = Some(data);
    }

    /// 对应 Dart `_Assembly.isComplete`。
    fn is_complete(&self) -> bool {
        self.received == self.fragment_count
    }

    /// 对应 Dart `_Assembly.assemble`：按下标顺序拼接已收到的片，空洞跳过。
    fn assemble(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(self.message_bytes);
        for fragment in &self.fragments {
            if let Some(bytes) = fragment {
                out.extend_from_slice(bytes);
            }
        }
        out
    }
}

/// 对应 Dart `base64.decode`（`_Base64Codec.normalize` + 解码器）。
///
/// Dart 的 `normalize` 比 Rust 默认解码器宽容两条：把 `-`/`_` 归一成 `+`/`/`，以及
/// 「没有 padding 就补上」。这里用「先归一化字母表、再依次试带 padding / 不带 padding」
/// 复刻这两条；非法字符（如 `!!!`）和长度非法（`len % 4 == 1`）仍然失败，与 Dart 一致。
///
/// PORT-NOTE: Dart 的 `normalize` 还会先做 `%`-unescape（`%XX` → 字符），这条**没有**照抄：
/// 线上的 `dataBase64` 由标准 base64 编码器产生，不可能出现 `%`；出现时 Dart 接受、这里判为
/// 非法（该片被丢弃，等价于 Dart 里 base64 抛异常被 catch 掉的分支）。
fn decode_base64_like_dart(input: &str) -> Option<Vec<u8>> {
    let normalized: String = input
        .chars()
        .map(|c| match c {
            '-' => '+',
            '_' => '/',
            other => other,
        })
        .collect();
    STANDARD
        .decode(&normalized)
        .ok()
        .or_else(|| STANDARD_NO_PAD.decode(&normalized).ok())
}

/// 对应 Dart 的 `(payload['x'] as num?)?.toInt()`。
///
/// Dart 的 `num.toInt()` 对浮点是**向零截断**，故 `1.0`/`1.9` 都算 1；Rust 的
/// `Value::as_i64` 对浮点返回 `None`，这里补上截断分支以对齐 Dart。
fn as_dart_int(value: &Value) -> Option<i64> {
    value
        .as_i64()
        .or_else(|| value.as_f64().map(|f| f.trunc() as i64))
}

#[cfg(test)]
mod tests {
    use super::*;
    // 显式再引入一遍本模块用到的外部项，避免只依赖 `use super::*` 的传递可见性。
    use crate::crc32::Crc32;
    use crate::error::{ProtocolError, Result};
    use base64::engine::general_purpose::STANDARD;
    use serde_json::json;
    use std::cell::RefCell;
    use std::rc::Rc;

    /// 对应 Dart 测试里的 `sendPayload: (p) => sent.add(p)`。
    struct RecordingSink {
        sent: Rc<RefCell<Vec<RpcFramePayload>>>,
        /// `true` 时模拟宿主发送失败（对应 Dart 的 `sendPayload` 抛异常）。
        fail: bool,
    }

    impl RecordingSink {
        fn new(sent: &Rc<RefCell<Vec<RpcFramePayload>>>) -> Self {
            Self {
                sent: Rc::clone(sent),
                fail: false,
            }
        }

        fn failing(sent: &Rc<RefCell<Vec<RpcFramePayload>>>) -> Self {
            Self {
                sent: Rc::clone(sent),
                fail: true,
            }
        }
    }

    impl FrameSink for RecordingSink {
        fn send(&mut self, payload: &RpcFramePayload) -> Result<()> {
            if self.fail {
                return Err(ProtocolError::Other("sink down".to_string()));
            }
            self.sent.borrow_mut().push(payload.clone());
            Ok(())
        }
    }

    /// 对应 Dart 测试 `setUp()`：`bridgeSessionId: 'bridge-1'` + 记录型 `sendPayload`。
    fn new_transport(sent: &Rc<RefCell<Vec<RpcFramePayload>>>) -> RpcFrameTransport {
        RpcFrameTransport::new(
            "bridge-1".to_string(),
            Box::new(RecordingSink::new(sent)),
            None,
            None,
        )
    }

    /// 对应 Dart `rpc_transport_test.dart` 的
    /// `'send single-fragment message and receive via acceptPayload'`。
    ///
    /// 期望的 base64 值由本机 Python `base64.b64encode(bytes([1,2,3,4,5]))` 独立算出：
    /// `AQIDBAU=`。
    #[test]
    fn send_single_fragment_message_and_receive_via_accept_payload() {
        let sent = Rc::new(RefCell::new(Vec::new()));
        let mut transport = new_transport(&sent);

        let outcome = transport.accept_payload(
            &json!({
                "zcode_type": "rpc-frame",
                "bridgeSessionId": "bridge-1",
                "seq": 1,
                "messageSeq": 1,
                "fragmentIndex": 0,
                "fragmentCount": 1,
                "messageBytes": 5,
                "dataBase64": "AQIDBAU=",
            }),
            0,
        );

        assert!(outcome.consumed);
        assert_eq!(outcome.message.as_deref(), Some(&[1u8, 2, 3, 4, 5][..]));
    }

    /// 对应既有测试 `'multi-fragment message roundtrip'`。
    ///
    /// 1 MiB + 100 字节 → 3 片（2 满片 + 1 个 100 字节尾片）；把发出的帧**逆序**回灌，
    /// 仍应重组出原消息。crc32 期望值由本机 Python `zlib.crc32` 独立算出：
    /// `bytes(i % 256 for i in range(1024*1024 + 100))` → `40a36d5b`。
    #[test]
    fn multi_fragment_message_roundtrip() {
        let sent = Rc::new(RefCell::new(Vec::new()));
        let mut transport = new_transport(&sent);

        let payload: Vec<u8> = (0..1024 * 1024 + 100).map(|i| (i % 256) as u8).collect();
        transport.send_message(&payload).expect("发送不应失败");
        assert_eq!(sent.borrow().len(), 3); // 2 full + 1 partial fragment

        {
            let frames = sent.borrow();
            for (i, frame) in frames.iter().enumerate() {
                assert_eq!(frame.zcode_type, ZCODE_TYPE_RPC_FRAME);
                assert_eq!(frame.bridge_session_id, "bridge-1");
                assert_eq!(frame.seq, Some(i as u64 + 1));
                assert_eq!(frame.message_seq, Some(1));
                assert_eq!(frame.fragment_index, Some(i));
                assert_eq!(frame.fragment_count, Some(3));
                assert_eq!(frame.message_bytes, Some(payload.len()));
                assert_eq!(
                    frame.checksum.as_ref().map(|c| (c.algorithm.as_str(), c.value.as_str())),
                    Some(("crc32", "40a36d5b"))
                );
            }
        }

        // Dart: `List<Map<String, dynamic>>.from(sent.reversed)`
        let frames: Vec<RpcFramePayload> = sent.borrow().iter().rev().cloned().collect();
        let mut received: Vec<Vec<u8>> = Vec::new();
        for frame in &frames {
            let value = serde_json::to_value(frame).expect("帧可序列化");
            let outcome = transport.accept_payload(&value, 0);
            assert!(outcome.consumed);
            if let Some(message) = outcome.message {
                received.push(message);
            }
        }

        assert_eq!(received.len(), 1);
        assert_eq!(received[0], payload);
    }

    /// 对应既有测试 `'rpc-frame-ack is consumed'`。
    #[test]
    fn rpc_frame_ack_is_consumed() {
        let sent = Rc::new(RefCell::new(Vec::new()));
        let mut transport = new_transport(&sent);

        let outcome = transport.accept_payload(
            &json!({"zcode_type": "rpc-frame-ack", "bridgeSessionId": "bridge-1"}),
            0,
        );
        assert!(outcome.consumed);
    }

    /// 对应既有测试 `'non-rpc-frame passes through unconsumed'`。
    #[test]
    fn non_rpc_frame_passes_through_unconsumed() {
        let sent = Rc::new(RefCell::new(Vec::new()));
        let mut transport = new_transport(&sent);

        let outcome = transport.accept_payload(&json!({"zcode_type": "workspace-list-updated"}), 0);
        assert!(!outcome.consumed);
    }

    /// 对应既有测试 `'mismatched bridgeSessionId is not consumed'`。
    #[test]
    fn mismatched_bridge_session_id_is_not_consumed() {
        let sent = Rc::new(RefCell::new(Vec::new()));
        let mut transport = new_transport(&sent);

        let outcome = transport.accept_payload(
            &json!({"zcode_type": "rpc-frame", "bridgeSessionId": "other-bridge"}),
            0,
        );
        assert!(!outcome.consumed);
    }

    /// 对应既有测试 `'rejects invalid fragment metadata without allocating'`。
    #[test]
    fn rejects_invalid_fragment_metadata_without_allocating() {
        let sent = Rc::new(RefCell::new(Vec::new()));
        let mut transport = new_transport(&sent);

        let outcome = transport.accept_payload(
            &json!({
                "zcode_type": "rpc-frame",
                "bridgeSessionId": "bridge-1",
                "messageSeq": 1,
                "fragmentIndex": 0,
                "fragmentCount": MAX_FRAGMENTS + 1,
                "messageBytes": 3,
                "dataBase64": "AQID",
            }),
            0,
        );
        assert!(outcome.consumed);
        assert!(outcome.message.is_none());
    }

    /// 对应既有测试 `'rejects assembled message with incorrect declared size'`。
    #[test]
    fn rejects_assembled_message_with_incorrect_declared_size() {
        let sent = Rc::new(RefCell::new(Vec::new()));
        let mut transport = new_transport(&sent);

        let outcome = transport.accept_payload(
            &json!({
                "zcode_type": "rpc-frame",
                "bridgeSessionId": "bridge-1",
                "messageSeq": 1,
                "fragmentIndex": 0,
                "fragmentCount": 1,
                "messageBytes": 4,
                "dataBase64": "AQID",
            }),
            0,
        );
        assert!(outcome.message.is_none());
        assert!(outcome.logs.iter().any(|l| l == "[rpc] message 1 size mismatch"));
    }

    /// 对应既有测试 `'invalid base64 data is handled gracefully'`。
    #[test]
    fn invalid_base64_data_is_handled_gracefully() {
        let sent = Rc::new(RefCell::new(Vec::new()));
        let mut transport = new_transport(&sent);

        let outcome = transport.accept_payload(
            &json!({
                "zcode_type": "rpc-frame",
                "bridgeSessionId": "bridge-1",
                "messageSeq": 1,
                "fragmentIndex": 0,
                "fragmentCount": 1,
                "messageBytes": 5,
                "dataBase64": "!!!not-valid-base64!!!",
            }),
            0,
        );
        assert!(outcome.consumed);
        assert!(outcome.message.is_none());
    }

    /// 对应既有测试 `'missing required fields returns consumed without crash'`。
    #[test]
    fn missing_required_fields_returns_consumed_without_crash() {
        let sent = Rc::new(RefCell::new(Vec::new()));
        let mut transport = new_transport(&sent);

        let outcome = transport.accept_payload(
            &json!({"zcode_type": "rpc-frame", "bridgeSessionId": "bridge-1"}),
            0,
        );
        assert!(outcome.consumed);
        assert!(outcome.message.is_none());
    }

    /// 对应既有测试 `'checksum mismatch does not emit message'`。
    #[test]
    fn checksum_mismatch_does_not_emit_message() {
        let sent = Rc::new(RefCell::new(Vec::new()));
        let mut transport = new_transport(&sent);

        // `[1,2,3]` 的真实 crc32 是 `55bc801d`（Python `zlib.crc32` 独立算出），
        // 这里故意声明 `'00000000'`。
        let outcome = transport.accept_payload(
            &json!({
                "zcode_type": "rpc-frame",
                "bridgeSessionId": "bridge-1",
                "messageSeq": 1,
                "fragmentIndex": 0,
                "fragmentCount": 1,
                "messageBytes": 3,
                "dataBase64": "AQID",
                "checksum": {"algorithm": "crc32", "value": "00000000"},
            }),
            0,
        );
        assert!(outcome.message.is_none());
        assert!(outcome.logs.iter().any(|l| l == "[rpc] message 1 checksum mismatch"));
    }

    /// 对应既有测试 `'ack is sent on successful assembly'`。
    #[test]
    fn ack_is_sent_on_successful_assembly() {
        let sent = Rc::new(RefCell::new(Vec::new()));
        let mut transport = new_transport(&sent);

        let payload = vec![7u8, 8, 9];
        let checksum = Crc32::hex_of(&payload); // 既有测试用 Crc32.hexOf → '4b0bfd3b'
        let outcome = transport.accept_payload(
            &json!({
                "zcode_type": "rpc-frame",
                "bridgeSessionId": "bridge-1",
                "messageSeq": 1,
                "fragmentIndex": 0,
                "fragmentCount": 1,
                "messageBytes": 3,
                "dataBase64": "BwgJ",
                "checksum": {"algorithm": "crc32", "value": checksum},
            }),
            0,
        );

        assert_eq!(outcome.message.as_deref(), Some(&payload[..]));
        let sent = sent.borrow();
        let ack = sent
            .iter()
            .find(|p| p.zcode_type == ZCODE_TYPE_RPC_FRAME_ACK)
            .expect("成功重组必须发出 rpc-frame-ack");
        assert_eq!(ack.ack_message_seq, Some(1));
        assert_eq!(ack.bridge_session_id, "bridge-1");
    }

    /// 对应既有测试 `'sendMessage throws on empty payload'`。
    #[test]
    fn send_message_throws_on_empty_payload() {
        let sent = Rc::new(RefCell::new(Vec::new()));
        let mut transport = new_transport(&sent);

        match transport.send_message(&[]) {
            Err(ProtocolError::InvalidState(msg)) => {
                assert_eq!(msg, "remote.rpcFrame.emptyMessage")
            }
            other => panic!("期望 InvalidState，实际 {other:?}"),
        }
        assert!(sent.borrow().is_empty());
        // 失败时计数器不前进：下一条消息仍是 messageSeq 1 / seq 1。
        transport.send_message(&[1, 2, 3]).expect("发送不应失败");
        assert_eq!(sent.borrow()[0].message_seq, Some(1));
        assert_eq!(sent.borrow()[0].seq, Some(1));
    }

    /// 对应既有测试 `'reassembly with out-of-order fragments'`。
    #[test]
    fn reassembly_with_out_of_order_fragments() {
        let sent = Rc::new(RefCell::new(Vec::new()));
        let mut transport = new_transport(&sent);

        let payload: Vec<u8> = (0..200).map(|i| (i % 256) as u8).collect();
        let tail = STANDARD.encode(&payload[100..200]);
        let head = STANDARD.encode(&payload[0..100]);

        // 后片先到：还不完整。
        let first = transport.accept_payload(
            &json!({
                "zcode_type": "rpc-frame",
                "bridgeSessionId": "bridge-1",
                "messageSeq": 1,
                "fragmentIndex": 1,
                "fragmentCount": 2,
                "messageBytes": 200,
                "dataBase64": tail,
            }),
            0,
        );
        assert!(first.message.is_none());

        // 前片到达后凑齐（帧里没有 checksum 字段 → Dart 不做校验）。
        let second = transport.accept_payload(
            &json!({
                "zcode_type": "rpc-frame",
                "bridgeSessionId": "bridge-1",
                "messageSeq": 1,
                "fragmentIndex": 0,
                "fragmentCount": 2,
                "messageBytes": 200,
                "dataBase64": head,
            }),
            0,
        );
        assert_eq!(second.message.as_deref(), Some(&payload[..]));
    }

    /// 对应既有测试 `'identity includes bridgeGeneration and recoveryId'`。
    #[test]
    fn identity_includes_bridge_generation_and_recovery_id() {
        let sent = Rc::new(RefCell::new(Vec::new()));
        let mut transport = RpcFrameTransport::new(
            "b1".to_string(),
            Box::new(RecordingSink::new(&sent)),
            Some(5),
            Some("rec-99".to_string()),
        );

        assert_eq!(transport.bridge_generation, Some(5));
        assert_eq!(transport.recovery_id.as_deref(), Some("rec-99"));

        // 帧与 ack 都带 generation/recoveryId：对端的 `rawIdentityMatches`
        // 要求三元组严格相等，ack 少字段会被哑丢。
        transport.send_message(&[1, 2, 3]).expect("发送不应失败");
        let frame = sent.borrow()[0].clone();
        assert_eq!(frame.bridge_generation, Some(5));
        assert_eq!(frame.recovery_id.as_deref(), Some("rec-99"));
        assert_eq!(frame.bridge_session_id, "b1");

        let value = serde_json::to_value(&frame).expect("帧可序列化");
        transport.accept_payload(&value, 0);
        let sent = sent.borrow();
        let ack = sent
            .iter()
            .find(|p| p.zcode_type == ZCODE_TYPE_RPC_FRAME_ACK)
            .expect("应有 ack");
        assert_eq!(ack.bridge_generation, Some(5));
        assert_eq!(ack.recovery_id.as_deref(), Some("rec-99"));

        transport.dispose();
    }

    /// 既有测试里没有覆盖：`sendMessage` 的 `messageTooLarge` 分支。
    /// 期望值取自 `rpc_transport.dart`（`bytes.length > maxMessageBytes` → StateError）。
    #[test]
    fn send_message_rejects_message_over_max_message_bytes() {
        let sent = Rc::new(RefCell::new(Vec::new()));
        let mut transport = new_transport(&sent);

        let huge = vec![0u8; MAX_MESSAGE_BYTES + 1];
        match transport.send_message(&huge) {
            Err(ProtocolError::InvalidState(msg)) => {
                assert_eq!(msg, "remote.rpcFrame.messageTooLarge")
            }
            other => panic!("期望 InvalidState，实际 {other:?}"),
        }
        assert!(sent.borrow().is_empty());
    }

    /// 既有测试里没有覆盖：`_purgeStale()` 的阈值。
    /// 期望值取自 `rpc_transport.dart`：`now.difference(createdAt).inSeconds > 60`，
    /// `inSeconds` 向零截断 ⇒ 60999 ms 保留、61000 ms 清理。
    #[test]
    fn purge_stale_uses_truncated_30_second_threshold() {
        let sent = Rc::new(RefCell::new(Vec::new()));
        let mut transport = new_transport(&sent);

        // 造一个永远凑不齐的重组：2 片只来 1 片（`[1,2]` → base64 `AQI=`）。
        transport.accept_payload(
            &json!({
                "zcode_type": "rpc-frame",
                "bridgeSessionId": "bridge-1",
                "messageSeq": 1,
                "fragmentIndex": 0,
                "fragmentCount": 2,
                "messageBytes": 2,
                "dataBase64": "AQI=",
            }),
            1_000,
        );

        // 时钟回拨（now < createdAt）→ inSeconds 为负，不清理。
        assert!(transport.purge_stale(0).is_empty());
        // 30.999 秒 → inSeconds == 30，不清理。
        assert!(transport.purge_stale(1_000 + 30_999).is_empty());
        assert_eq!(transport.assemblies.len(), 1);
        // 31.000 秒 → inSeconds == 31 > 30，清理并打日志。
        assert_eq!(
            transport.purge_stale(1_000 + 31_000),
            vec!["[rpc] purged stale assembly 1".to_string()]
        );
        assert!(transport.assemblies.is_empty());
    }

    /// 既有测试里没有覆盖：`acceptPayload` 里「已有 assembly 的元数据不一致 → 整个丢弃」分支。
    /// 期望值取自 `rpc_transport.dart`（`existing.fragmentCount/messageBytes/checksum` 任一不同）。
    #[test]
    fn conflicting_fragment_metadata_drops_existing_assembly() {
        let sent = Rc::new(RefCell::new(Vec::new()));
        let mut transport = new_transport(&sent);

        let fragment = |index: i64, message_bytes: i64, data: &str| {
            json!({
                "zcode_type": "rpc-frame",
                "bridgeSessionId": "bridge-1",
                "messageSeq": 1,
                "fragmentIndex": index,
                "fragmentCount": 2,
                "messageBytes": message_bytes,
                "dataBase64": data,
            })
        };

        // 第 0 片按 messageBytes = 4 建起重组状态（[1,2] → 'AQI='）。
        let first = transport.accept_payload(&fragment(0, 4, "AQI="), 0);
        assert!(first.message.is_none());
        assert_eq!(transport.assemblies.len(), 1);

        // 第 1 片声明 messageBytes = 3：元数据冲突 → 整个重组被丢掉，不产出消息。
        let second = transport.accept_payload(&fragment(1, 3, "AQI="), 0);
        assert!(second.message.is_none());
        assert!(transport.assemblies.is_empty());

        // 丢掉之后可以按新的声明重新开始（重传整条消息能恢复）。
        transport.accept_payload(&fragment(0, 4, "AQI="), 0);
        let done = transport.accept_payload(&fragment(1, 4, "AQI="), 0);
        assert_eq!(done.message.as_deref(), Some(&[1u8, 2, 1, 2][..]));
    }

    /// 对端（网页/桌面端）的分片尺寸是动态二分出来的，单片解码后可达约 786 KiB。
    /// 入站上限必须按 `bd.maxPhysicalFrameBytes`（1 MiB，作用在 `dataBase64` 字符串上）
    /// 判定，不能沿用 Dart 的 512 KiB **解码后**上限——否则这类分片会被整片丢掉，
    /// 而且不回 ack ⇒ 对端 45 s 后 `ackGraceExceeded` 终态降级。
    #[test]
    fn accepts_inbound_fragment_larger_than_dart_512kib() {
        let sent = Rc::new(RefCell::new(Vec::new()));
        let mut transport = new_transport(&sent);
        let bytes = vec![7u8; 600 * 1024]; // 600 KiB：> 512 KiB，仍在 786 KiB 以内
        let encoded = STANDARD.encode(&bytes);
        assert!(
            encoded.len() <= MAX_PHYSICAL_FRAME_BYTES,
            "base64 长度 {} 应不超过 1 MiB 信封预算",
            encoded.len()
        );

        let outcome = transport.accept_payload(
            &json!({
                "zcode_type": "rpc-frame",
                "bridgeSessionId": "bridge-1",
                "messageSeq": 1,
                "fragmentIndex": 0,
                "fragmentCount": 1,
                "messageBytes": bytes.len(),
                "checksum": {"algorithm": "crc32", "value": Crc32::hex_of(&bytes)},
                "dataBase64": encoded,
            }),
            0,
        );

        let message = outcome.message.expect("600 KiB 单片必须被接收");
        assert_eq!(message.len(), bytes.len());
    }

    /// 既有测试里没有覆盖：`checksum['algorithm']` **从不被读取**（照抄 Dart 只读 `value`）。
    #[test]
    fn checksum_algorithm_field_is_ignored() {
        let sent = Rc::new(RefCell::new(Vec::new()));
        let mut transport = new_transport(&sent);

        // algorithm 写成 'sha256'，value 仍是 crc32 值 → 通过。
        let wrong_algorithm = transport.accept_payload(
            &json!({
                "zcode_type": "rpc-frame",
                "bridgeSessionId": "bridge-1",
                "messageSeq": 1,
                "fragmentIndex": 0,
                "fragmentCount": 1,
                "messageBytes": 3,
                "dataBase64": "AQID",
                "checksum": {"algorithm": "sha256", "value": "55bc801d"},
            }),
            0,
        );
        assert_eq!(wrong_algorithm.message.as_deref(), Some(&[1u8, 2, 3][..]));

        // checksum 对象里没有 'algorithm' 键 → 同样通过。
        let no_algorithm = transport.accept_payload(
            &json!({
                "zcode_type": "rpc-frame",
                "bridgeSessionId": "bridge-1",
                "messageSeq": 2,
                "fragmentIndex": 0,
                "fragmentCount": 1,
                "messageBytes": 3,
                "dataBase64": "AQID",
                "checksum": {"value": "55bc801d"},
            }),
            0,
        );
        assert_eq!(no_algorithm.message.as_deref(), Some(&[1u8, 2, 3][..]));
    }

    /// 线上字段名零漂移：逐字段对照网页端的 JSON（crc32 期望值由本机 Python
    /// `zlib.crc32(bytes([1,2,3,4,5]))` 独立算出 → `470b99f4`；base64 由
    /// `base64.b64encode` 独立算出 → `AQIDBAU=`）。
    ///
    /// 用 `serde_json::Value` 比较，同时覆盖「缺省字段不能序列化成 null」。
    #[test]
    fn outbound_json_field_names_match_web_format() {
        let sent = Rc::new(RefCell::new(Vec::new()));
        let mut transport = RpcFrameTransport::new(
            "bridge-1".to_string(),
            Box::new(RecordingSink::new(&sent)),
            Some(5),
            Some("rec-99".to_string()),
        );

        transport.send_message(&[1, 2, 3, 4, 5]).expect("发送不应失败");
        let frame = serde_json::to_value(&sent.borrow()[0]).expect("帧可序列化");
        assert_eq!(
            frame,
            json!({
                "zcode_type": "rpc-frame",
                "bridgeSessionId": "bridge-1",
                "bridgeGeneration": 5,
                "recoveryId": "rec-99",
                "seq": 1,
                "messageSeq": 1,
                "fragmentIndex": 0,
                "fragmentCount": 1,
                "messageBytes": 5,
                "checksum": {"algorithm": "crc32", "value": "470b99f4"},
                "dataBase64": "AQIDBAU=",
            })
        );

        // ack 带完整身份三元组（对端 `rawIdentityMatches` 三字段严格相等）。
        let outcome = transport.accept_payload(&frame, 0);
        assert!(outcome.message.is_some());
        let ack = sent
            .borrow()
            .iter()
            .find(|p| p.zcode_type == ZCODE_TYPE_RPC_FRAME_ACK)
            .cloned()
            .expect("应有 ack");
        assert_eq!(
            serde_json::to_value(&ack).expect("ack 可序列化"),
            json!({
                "zcode_type": "rpc-frame-ack",
                "bridgeSessionId": "bridge-1",
                "bridgeGeneration": 5,
                "recoveryId": "rec-99",
                "ackMessageSeq": 1,
            })
        );
    }

    /// Rust 侧专有行为（Dart 的 `sendPayload` 返回 void，无对应语义）：
    /// ack 发送失败时**不丢**已重组出的消息，失败记成一条日志。
    #[test]
    fn ack_send_failure_is_logged_and_message_is_not_lost() {
        let sent = Rc::new(RefCell::new(Vec::new()));
        let mut transport = RpcFrameTransport::new(
            "bridge-1".to_string(),
            Box::new(RecordingSink::failing(&sent)),
            None,
            None,
        );

        let outcome = transport.accept_payload(
            &json!({
                "zcode_type": "rpc-frame",
                "bridgeSessionId": "bridge-1",
                "messageSeq": 1,
                "fragmentIndex": 0,
                "fragmentCount": 1,
                "messageBytes": 3,
                "dataBase64": "AQID",
            }),
            0,
        );

        assert_eq!(outcome.message.as_deref(), Some(&[1u8, 2, 3][..]));
        assert!(outcome.logs.iter().any(|l| l.starts_with("[rpc] ack send failed:")));
        assert!(sent.borrow().is_empty());
    }

    /// Rust 侧专有行为：`send_message` 里 sink 失败会立刻中断发送循环并向外传播
    /// （对应 Dart 的 `sendPayload` 抛异常：已发出的片与已递增的计数器都不回滚）。
    #[test]
    fn send_message_propagates_sink_failure() {
        let sent = Rc::new(RefCell::new(Vec::new()));
        let mut transport = RpcFrameTransport::new(
            "bridge-1".to_string(),
            Box::new(RecordingSink::failing(&sent)),
            None,
            None,
        );

        assert!(transport.send_message(&[1, 2, 3]).is_err());
        // 计数器照旧前进（Dart 在 sendPayload 之前就 `_seq += 1` / `++_messageSeq`）。
        assert_eq!(transport.seq, 1);
        assert_eq!(transport.message_seq, 1);
    }

    /// 常量之间的约束（对端 `bd` 预算）：16 MiB / 512 KiB = 32 片 ≤ maxFragments(64)，
    /// 故 `send_message` 的 `fragmentLimitExceeded` 分支不可达。
    #[test]
    fn max_message_fits_within_max_fragments() {
        assert_eq!(MAX_MESSAGE_BYTES / FRAGMENT_PAYLOAD_BYTES, 32);
        assert!(MAX_MESSAGE_BYTES / FRAGMENT_PAYLOAD_BYTES <= MAX_FRAGMENTS);
        assert_eq!(MAX_PHYSICAL_FRAME_BYTES, 1024 * 1024);
        assert_eq!(PURGE_INTERVAL_SECONDS, 30);
        assert_eq!(STALE_ASSEMBLY_SECONDS, 30);
    }

    /// Dart 的 `_Base64Codec.normalize` 归一化：`-`/`_` 与缺 padding 都要能解出来。
    /// 期望值由本机 Python 独立算出：`bytes([1,2,3,4,5])` 的标准 base64 是 `AQIDBAU=`，
    /// 去 padding 为 `AQIDBAU`；`bytes([255,255,255])` 是 `////`（url-safe `____`）；
    /// `bytes([251])` 是 `+w==`（url-safe `-w==`）。
    #[test]
    fn base64_decoder_accepts_missing_padding_and_url_safe_alphabet() {
        assert_eq!(decode_base64_like_dart("AQIDBAU="), Some(vec![1, 2, 3, 4, 5]));
        assert_eq!(decode_base64_like_dart("AQIDBAU"), Some(vec![1, 2, 3, 4, 5]));
        assert_eq!(decode_base64_like_dart("____"), Some(vec![255, 255, 255]));
        assert_eq!(decode_base64_like_dart("-w=="), Some(vec![251]));
        // '!!!' 与长度 % 4 == 1 都必须失败（Dart 会抛 FormatException）。
        assert_eq!(decode_base64_like_dart("!!!not-valid-base64!!!"), None);
        assert_eq!(decode_base64_like_dart("AQIDB"), None);
    }
}
