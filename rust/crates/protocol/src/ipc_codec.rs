//! 值编解码与 IPC 帧定界 —— 对应 `Reference_code/lib/protocol/ipc_codec.dart`。
//!
//! 两块内容：
//!
//! 1. **值编解码**：网页端 `Sm()`（写）/ `Cm()`（读）。类型标签
//!    `Undefined=0, String=1, Buffer=2, VSBuffer=3, Array=4, Object=5(JSON), Int=6`；
//!    长度/计数是 **7-bit 小端 varint**。
//! 2. **帧定界**：网页端 `Mne()` / `Nne`。13 字节头
//!    `[type:u8][id:u32be][ack:u32be][bodyLen:u32be]` + body。
//!
//! 纯逻辑、同步、无 I/O。
//!
// PORT-NOTE: 本模块的 `Value` / `ValueReader` / `ValueWriter` / `IpcFraming` / `IpcFrameParser`
// 是后续所有协议模块（relay_client / rpc_transport / channel_client / zemote_client）的公共底座，
// 按契约应由主线决定是否在 `lib.rs` 里 `pub use` 提升为 crate 级公共 API（本子代理不改 lib.rs）。

use crate::error::{ProtocolError, Result};

// ---------------------------------------------------------------------------
// 写端
// ---------------------------------------------------------------------------

/// 值写入器 —— 对应 Dart `ValueWriter`（网页端 `Sm()` 的底层缓冲）。
#[derive(Debug, Default, Clone)]
pub struct ValueWriter {
    buf: Vec<u8>,
}

impl ValueWriter {
    /// 对应 `ValueWriter()` 构造。
    pub fn new() -> Self {
        Self { buf: Vec::new() }
    }

    /// 对应 `ValueWriter.writeByte`（Dart 内部做 `v & 0xFF`，Rust 由 `u8` 类型承担该截断）。
    pub fn write_byte(&mut self, v: u8) {
        self.buf.push(v);
    }

    /// 对应 `ValueWriter.writeVarint`：7-bit 小端 varint。
    ///
    /// 与 Dart 一样是 do-while 语义：`0` 也写 1 字节 `0x00`。
    // PORT-NOTE: Dart 的 `writeVarint` 参数是 64 位 `int` 且对负数行为诡异（`-1` 会写出单字节
    // `0x7F`，因为算术右移后 `v > 0` 恒为假）。Rust 取 `u64`，无法表达负数——实际调用点
    // （长度、计数、`Int` 标签值）从不传负数，此差异不可达。
    // PORT-NOTE: 写端允许 > `0xFFFFFFFF`（Dart 同样允许），但那样的字节 Dart/Rust 的读端都读不回来
    // （读端第 5 字节只接受低 4 位），此不对称与 Dart 完全一致，不额外收紧。
    pub fn write_varint(&mut self, value: u64) {
        let mut v = value;
        loop {
            let mut byte = (v & 0x7F) as u8;
            v >>= 7;
            if v > 0 {
                byte |= 0x80;
            }
            self.buf.push(byte);
            if v == 0 {
                break;
            }
        }
    }

    /// 对应 `ValueWriter.writeBytes`。
    pub fn write_bytes(&mut self, bytes: &[u8]) {
        self.buf.extend_from_slice(bytes);
    }

    /// 对应 `ValueWriter.toBytes`：返回当前内容的一份副本，writer 仍可继续写。
    pub fn to_bytes(&self) -> Vec<u8> {
        self.buf.clone()
    }

    /// 取走内部缓冲（Dart 无对应函数，避免多拷一次；语义等价于 `to_bytes` 后丢弃 writer）。
    pub fn into_bytes(self) -> Vec<u8> {
        self.buf
    }

    /// 已写字节数的只读视图（Dart 无对应；便于宿主/测试观察）。
    pub fn as_bytes(&self) -> &[u8] {
        &self.buf
    }
}

// ---------------------------------------------------------------------------
// 读端
// ---------------------------------------------------------------------------

/// 值读取器 —— 对应 Dart `ValueReader`（网页端 `Cm()` 的底层游标）。
#[derive(Debug, Clone)]
pub struct ValueReader {
    data: Vec<u8>,
    pos: usize,
}

impl ValueReader {
    /// 对应 `ValueReader.maxContainerItems`。
    pub const MAX_CONTAINER_ITEMS: usize = 100_000;
    /// 对应 `ValueReader.maxValueBytes`。
    pub const MAX_VALUE_BYTES: usize = 16 * 1024 * 1024;

    /// 对应 `ValueReader(this.data)`。
    pub fn new(data: impl Into<Vec<u8>>) -> Self {
        Self {
            data: data.into(),
            pos: 0,
        }
    }

    /// 对应 `ValueReader.remaining`。
    pub fn remaining(&self) -> usize {
        self.data.len() - self.pos
    }

    /// 对应 `ValueReader.pos` 字段（Dart 里是公开字段，Rust 侧只读暴露）。
    pub fn pos(&self) -> usize {
        self.pos
    }

    /// 对应 `ValueReader.read(n)`：取 `n` 字节。越界时 Dart 抛 `FormatException`。
    ///
    /// 返回借用切片（Dart 返回 `Uint8List.sublistView`，同为视图、不拷贝）。
    pub fn read(&mut self, n: usize) -> Result<&[u8]> {
        let end = self
            .pos
            .checked_add(n)
            .filter(|end| *end <= self.data.len())
            .ok_or(ProtocolError::Truncated {
                needed: n,
                remaining: self.remaining(),
            })?;
        let out = &self.data[self.pos..end];
        self.pos = end;
        Ok(out)
    }

    /// 对应 `ValueReader.readVarint`：7-bit 小端 varint。
    ///
    /// 返回值域与 Dart 完全一致：`0..=0xFFFFFFFF`（第 5 字节只允许低 4 位，见下方检查）。
    /// 错误映射：Dart 一律抛 `FormatException`，Rust 侧两类畸形输入都收成
    /// [`ProtocolError::InvalidFrame`]（不是 `Truncated`——Dart 里也是同一个异常类型）。
    // PORT-NOTE: Dart 里 `if (shift >= 35) break;` 是**死代码**：`shift` 只在第 5 字节（`shift == 28`）
    // 之后才可能变成 35，而第 5 字节若带续位（`b >= 0x80`）则 `b & 0xF0 != 0` 必先抛 overflow，
    // 若不带续位则必先 return。Rust 侧照样保留这行以保持结构可对照（`#[allow]` 无需，分支不可达
    // 只是逻辑上不可达，编译器不报）。
    pub fn read_varint(&mut self) -> Result<u32> {
        let mut value: u32 = 0;
        let mut shift: u32 = 0;
        while self.pos < self.data.len() {
            let b = self.data[self.pos];
            self.pos += 1;
            if shift == 28 && (b & 0xF0) != 0 {
                return Err(ProtocolError::InvalidFrame(format!(
                    "ValueReader: varint overflow at pos {}",
                    self.pos
                )));
            }
            value |= ((b & 0x7F) as u32) << shift;
            if (b & 0x80) == 0 {
                return Ok(value);
            }
            shift += 7;
            if shift >= 35 {
                break;
            }
        }
        Err(ProtocolError::InvalidFrame(format!(
            "ValueReader: invalid varint at pos {}",
            self.pos
        )))
    }
}

// ---------------------------------------------------------------------------
// 动态值
// ---------------------------------------------------------------------------

/// 动态值 —— 对应 Dart `encodeValue`/`decodeValue` 能表达的全部类型。
///
/// 变体与线上 tag 一一对应，**保留 tag 区分**（Dart 的 `dynamic` 无法区分 tag 2/3 之外的
/// 来源信息，Rust 侧刻意保留以便往返保真）。
#[derive(Debug, Clone, PartialEq)]
pub enum Value {
    /// tag 0（Dart `null`）。
    Undefined,
    /// tag 1（Dart `String`，UTF-8）。
    String(String),
    /// tag 2（Node `Buffer`）。Dart 的写端从不产出此 tag，但读端接受；此变体为读端与
    /// Node 兼容对端保留。
    Buffer(Vec<u8>),
    /// tag 3（Dart `Uint8List` / 网页端 `VSBuffer`）。
    VsBuffer(Vec<u8>),
    /// tag 4（Dart `List`，元素递归编码）。
    Array(Vec<Value>),
    /// tag 5（JSON，Dart `jsonEncode`/`jsonDecode`）。
    Object(serde_json::Value),
    /// tag 6（小整数）。
    ///
    /// Dart 写端只产出 `0..=0x7FFFFFFF`，Dart 读端接受 `0..=0xFFFFFFFF`（见 `read_varint`）。
    Int(u32),
}

impl Value {
    /// 该值会写出的 tag 字节（Dart 无对应函数；便于断言与调试）。
    pub fn tag(&self) -> u8 {
        match self {
            Value::Undefined => 0,
            Value::String(_) => 1,
            Value::Buffer(_) => 2,
            Value::VsBuffer(_) => 3,
            Value::Array(_) => 4,
            Value::Object(_) => 5,
            Value::Int(_) => 6,
        }
    }

    /// 复刻 Dart `encodeValue` 的 `int` 分支判定：`0 <= v <= 0x7FFFFFFF` 走 tag 6，
    /// 其余（负数、`> 0x7FFFFFFF`）落进 JSON 分支 tag 5。
    // PORT-NOTE: 网页端/VS Code 原版 `writeData` 的 Int 上界疑似是 `2^32 - 1`（`0xFFFFFFFF`），
    // Dart 移植写端收窄到 `0x7FFFFFFF`。按契约以 Dart 为准（写端用本函数），但读端按 Dart 读端
    // 实现（`Value::Int` 是 `u32` 全域），因此即使对端真的写来 `0x80000000..=0xFFFFFFFF` 的 tag 6，
    // 本模块也能正确解出，不会把线上数据读错。
    pub fn from_i64(v: i64) -> Value {
        if (0..=0x7FFF_FFFF).contains(&v) {
            Value::Int(v as u32)
        } else {
            Value::Object(serde_json::Value::Number(v.into()))
        }
    }
}

impl From<&str> for Value {
    fn from(v: &str) -> Self {
        Value::String(v.to_string())
    }
}

impl From<String> for Value {
    fn from(v: String) -> Self {
        Value::String(v)
    }
}

impl From<bool> for Value {
    /// Dart 里 `bool` 不是 `int`，落进 JSON 分支 → tag 5。
    fn from(v: bool) -> Self {
        Value::Object(serde_json::Value::Bool(v))
    }
}

impl From<serde_json::Value> for Value {
    fn from(v: serde_json::Value) -> Self {
        Value::Object(v)
    }
}

impl From<Vec<u8>> for Value {
    /// 对应 Dart 的 `Uint8List` 分支 → tag 3。
    fn from(v: Vec<u8>) -> Self {
        Value::VsBuffer(v)
    }
}

impl From<Vec<Value>> for Value {
    fn from(v: Vec<Value>) -> Self {
        Value::Array(v)
    }
}

impl From<i64> for Value {
    fn from(v: i64) -> Self {
        Value::from_i64(v)
    }
}

impl From<u32> for Value {
    fn from(v: u32) -> Self {
        Value::from_i64(v as i64)
    }
}

// ---------------------------------------------------------------------------
// 编解码
// ---------------------------------------------------------------------------

/// 对应 Dart `encodeValue`：把一个 [`Value`] 追加进 writer。
///
/// 与 Dart 的差别只有返回类型：Dart 返回 `void` 但 `jsonEncode` 可能抛错，Rust 侧把该失败
/// 收成 `Err`（对 `serde_json::Value` 实际不会失败），避免把 panic 当错误路径。
// PORT-NOTE: Dart `jsonEncode` 输出紧凑 JSON（无空格），与 `serde_json::to_vec` 一致；非 ASCII
// 两边都不转义（直接输出 UTF-8）。**但键序不同**：Dart 的 `Map` 保留插入顺序，`serde_json::Value`
// 默认是 `BTreeMap`（按键字典序）。JSON 对象无序，解析端不受影响；若线上存在对 JSON 做字节比对
// 或把 JSON 纳入签名覆盖的场景，需启用 serde_json 的 `preserve_order` feature（改 Cargo.toml，
// 由主线定夺），届时 `Object` 的往返字节才与 Dart 完全一致。
pub fn encode_value(w: &mut ValueWriter, value: &Value) -> Result<()> {
    match value {
        Value::Undefined => {
            w.write_byte(0);
        }
        Value::String(s) => {
            let bytes = s.as_bytes();
            w.write_byte(1);
            w.write_varint(bytes.len() as u64);
            w.write_bytes(bytes);
        }
        // PORT-NOTE: Dart 的 `encodeValue` 只写 tag 3（`Uint8List`），从不写 tag 2；但 Dart 读端
        // 把 2/3 等价处理。Rust 侧保留 `Buffer` 变体并原样写 tag 2，以便与 Node 对端（VS Code 原版
        // 的 `Buffer` = 2）往返保真；若主线要求严格"只写 3"，改这一行即可。
        Value::Buffer(bytes) => {
            w.write_byte(2);
            w.write_varint(bytes.len() as u64);
            w.write_bytes(bytes);
        }
        Value::VsBuffer(bytes) => {
            w.write_byte(3);
            w.write_varint(bytes.len() as u64);
            w.write_bytes(bytes);
        }
        Value::Array(items) => {
            w.write_byte(4);
            w.write_varint(items.len() as u64);
            for item in items {
                encode_value(w, item)?;
            }
        }
        Value::Int(v) => {
            w.write_byte(6);
            w.write_varint(*v as u64);
        }
        Value::Object(json) => {
            let bytes = serde_json::to_vec(json)?;
            w.write_byte(5);
            w.write_varint(bytes.len() as u64);
            w.write_bytes(&bytes);
        }
    }
    Ok(())
}

/// 对应 Dart `decodeValue`：按 tag 读出一个 [`Value`]。
///
/// 失败路径与 Dart 一一对应：
/// - 数据不足 → [`ProtocolError::Truncated`]（Dart `ValueReader.read` 的 `FormatException`）
/// - 超过 `maxValueBytes` / `maxContainerItems` → [`ProtocolError::LimitExceeded`]
///   （Dart 的 "string/bytes/object/list too large"）
/// - 未知 tag → [`ProtocolError::UnsupportedTag`]（Dart 的 `unknown value tag`）
/// - 非法 UTF-8 → [`ProtocolError::Utf8`]、非法 JSON → [`ProtocolError::Json`]（Dart 都是 `FormatException`）
// PORT-NOTE: tag 5 解出的永远是 `Value::Object(serde_json::Value)`，即使 JSON 内容本身是标量
// （`"hi"`、`42`、`true`）。这与 Dart 一致：Dart 的 `jsonDecode` 也返回 `dynamic`，值本身无法
// 反推 tag，只有 Rust 侧保留了 tag 信息，消费方不要用"是不是字符串"去判断 tag。
pub fn decode_value(r: &mut ValueReader) -> Result<Value> {
    let tag = r.read(1)?[0];
    match tag {
        0 => Ok(Value::Undefined),
        1 => {
            let length = r.read_varint()? as usize;
            if length > ValueReader::MAX_VALUE_BYTES {
                return Err(ProtocolError::LimitExceeded(
                    "ValueReader: string too large".into(),
                ));
            }
            Ok(Value::String(String::from_utf8(r.read(length)?.to_vec())?))
        }
        2 | 3 => {
            let length = r.read_varint()? as usize;
            if length > ValueReader::MAX_VALUE_BYTES {
                return Err(ProtocolError::LimitExceeded(
                    "ValueReader: bytes too large".into(),
                ));
            }
            let bytes = r.read(length)?.to_vec();
            Ok(if tag == 2 {
                Value::Buffer(bytes)
            } else {
                Value::VsBuffer(bytes)
            })
        }
        4 => {
            let count = r.read_varint()? as usize;
            if count > ValueReader::MAX_CONTAINER_ITEMS {
                return Err(ProtocolError::LimitExceeded(
                    "ValueReader: list too large".into(),
                ));
            }
            // Dart 用 `List<Object?>.generate(count, ...)`，同样是先按 count 预分配。
            let mut items = Vec::with_capacity(count);
            for _ in 0..count {
                items.push(decode_value(r)?);
            }
            Ok(Value::Array(items))
        }
        5 => {
            let length = r.read_varint()? as usize;
            if length > ValueReader::MAX_VALUE_BYTES {
                return Err(ProtocolError::LimitExceeded(
                    "ValueReader: object too large".into(),
                ));
            }
            Ok(Value::Object(serde_json::from_slice(r.read(length)?)?))
        }
        6 => Ok(Value::Int(r.read_varint()?)),
        other => Err(ProtocolError::UnsupportedTag(other)),
    }
}

/// 便捷入口：编码单个值（Dart 无对应函数，等价于新建 writer + `encode_value` + `to_bytes`）。
pub fn encode_to_vec(value: &Value) -> Result<Vec<u8>> {
    let mut w = ValueWriter::new();
    encode_value(&mut w, value)?;
    Ok(w.into_bytes())
}

/// 便捷入口：解码单个值（Dart 无对应函数，等价于新建 reader + `decode_value`）。
///
/// 注意：只解出一个值，**不校验尾部是否有多余字节**——与 Dart 的 `decodeValue` 行为一致。
pub fn decode_from_slice(data: &[u8]) -> Result<Value> {
    let mut r = ValueReader::new(data);
    decode_value(&mut r)
}

// ---------------------------------------------------------------------------
// IPC 帧定界
// ---------------------------------------------------------------------------

/// IPC 帧封装 —— 对应网页端 `Mne()` / Dart `IpcFraming`。
///
/// 头部固定 13 字节：`[type:u8][id:u32be][ack:u32be][bodyLen:u32be]`。
pub struct IpcFraming;

impl IpcFraming {
    /// 对应 `IpcFraming.typeRegular`。
    pub const TYPE_REGULAR: u8 = 1;

    /// 对应 `IpcFraming.encode`：`id` 与 `ack` 恒写 0（Dart 亦如此）。
    // PORT-NOTE: 网页端 `Mne()` 可能支持非 0 的 id/ack（请求-应答关联），但 Dart 移植只写 0，
    // 本函数照抄。若后续 rpc_transport 需要 id/ack，请由主线确认线上语义后再扩展签名，
    // 不要在本模块私自加参数。
    // PORT-NOTE: `body.len() as u32` 会静默截断 > 4GiB 的 body——与 Dart `view.setUint32(9, len)`
    // 相同（Dart 的 setUint32 也取低 32 位）。IPC 帧不可能这么大，保持与 Dart 一致。
    pub fn encode(body: &[u8]) -> Vec<u8> {
        let mut out = Vec::with_capacity(13 + body.len());
        out.push(Self::TYPE_REGULAR);
        out.extend_from_slice(&0u32.to_be_bytes()); // id
        out.extend_from_slice(&0u32.to_be_bytes()); // ack
        out.extend_from_slice(&(body.len() as u32).to_be_bytes()); // bodyLen
        out.extend_from_slice(body);
        out
    }
}

/// IPC 帧增量解析器 —— 对应 Dart `IpcFrameParser`（网页端 `Nne`）。
///
/// 输入任意切分的字节块，输出本次能凑齐的完整帧 body。非 `typeRegular` 的帧被**丢弃但同样
/// 消费掉**（与 Dart 一致）。
#[derive(Debug, Default)]
pub struct IpcFrameParser {
    buffer: Vec<u8>,
}

impl IpcFrameParser {
    /// 对应 `IpcFrameParser()` 构造。
    pub fn new() -> Self {
        Self {
            buffer: Vec::new(),
        }
    }

    /// 对应 `IpcFrameParser.acceptChunk`：喂入一块字节，返回本次解析出的完整 body 列表。
    // PORT-NOTE: Dart 每次调用都 `_buffer.toBytes()` 拷一份快照再重算 offset（O(n²)），Rust 侧
    // 直接在内部 `Vec` 上前进 offset、末尾 `drain` 一次，结果字节级等价、复杂度更低。
    // 这是纯实现细节，不影响任何可观测行为（返回值、内部余留字节完全一致）。
    pub fn accept_chunk(&mut self, chunk: &[u8]) -> Vec<Vec<u8>> {
        self.buffer.extend_from_slice(chunk);

        let mut out = Vec::new();
        let mut offset = 0usize;
        while self.buffer.len() - offset >= 13 {
            let frame_type = self.buffer[offset];
            let body_len = u32::from_be_bytes([
                self.buffer[offset + 9],
                self.buffer[offset + 10],
                self.buffer[offset + 11],
                self.buffer[offset + 12],
            ]) as usize;
            // Dart 的 int 是 64 位，此处不会溢出；usize 在 64 位平台同理（bodyLen 最大 0xFFFFFFFF）。
            let total = 13 + body_len;
            if self.buffer.len() - offset < total {
                break;
            }
            if frame_type == IpcFraming::TYPE_REGULAR {
                out.push(self.buffer[offset + 13..offset + total].to_vec());
            }
            offset += total;
        }
        if offset > 0 {
            self.buffer.drain(..offset);
        }
        out
    }

    /// 尚未凑齐完整帧的余留字节数（Dart 无对应；便于宿主做背压/诊断）。
    pub fn buffered_len(&self) -> usize {
        self.buffer.len()
    }
}

// ---------------------------------------------------------------------------
// 测试：逐条搬自 `Reference_code/test/ipc_codec_test.dart`
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    /// 便捷：编码一个值，期望必然成功。
    fn enc(v: &Value) -> Vec<u8> {
        encode_to_vec(v).expect("encode 不应失败")
    }

    /// 便捷：构造带 varint 前缀的裸字节（用于构造超限/截断输入）。
    fn tag_with_varint(tag: u8, n: u64) -> Vec<u8> {
        let mut w = ValueWriter::new();
        w.write_byte(tag);
        w.write_varint(n);
        w.into_bytes()
    }

    // ---- group: ValueReader truncation ------------------------------------

    /// Dart: 'read beyond length throws FormatException'
    #[test]
    fn reader_read_beyond_length_is_truncated() {
        let mut r = ValueReader::new(vec![1u8, 2, 3]);
        let err = r.read(5).unwrap_err();
        assert!(matches!(
            err,
            ProtocolError::Truncated {
                needed: 5,
                remaining: 3
            }
        ));
        // 失败后游标不动（Dart 同样在检查通过前不改 pos）。
        assert_eq!(r.pos(), 0);
    }

    /// Dart: 'read exactly at boundary succeeds'
    #[test]
    fn reader_read_exactly_at_boundary_succeeds() {
        let mut r = ValueReader::new(vec![1u8, 2, 3]);
        assert_eq!(r.read(3).unwrap(), &[1u8, 2, 3]);
        assert_eq!(r.remaining(), 0);
    }

    /// 补充：空输入 `read(1)`（Dart 测试未覆盖，`decodeValue` 首字节路径）
    #[test]
    fn reader_read_from_empty_is_truncated() {
        let mut r = ValueReader::new(Vec::<u8>::new());
        assert!(matches!(
            r.read(1).unwrap_err(),
            ProtocolError::Truncated {
                needed: 1,
                remaining: 0
            }
        ));
    }

    // ---- group: ValueReader readVarint ------------------------------------

    /// Dart: 'simple varint'（300 → 由 Python 独立实现算出 `[0xAC, 0x02]`）
    #[test]
    fn varint_simple_300() {
        let mut w = ValueWriter::new();
        w.write_varint(300);
        assert_eq!(w.as_bytes(), &[0xAC, 0x02]);
        let mut r = ValueReader::new(w.into_bytes());
        assert_eq!(r.read_varint().unwrap(), 300);
    }

    /// Dart: 'zero'
    #[test]
    fn varint_zero() {
        let mut w = ValueWriter::new();
        w.write_varint(0);
        assert_eq!(w.as_bytes(), &[0x00]);
        let mut r = ValueReader::new(w.into_bytes());
        assert_eq!(r.read_varint().unwrap(), 0);
    }

    /// Dart: 'max 32-bit value'（0x7FFFFFFF → `[0xFF,0xFF,0xFF,0xFF,0x07]`，Python 独立算出）
    #[test]
    fn varint_max_32bit() {
        let mut w = ValueWriter::new();
        w.write_varint(0x7FFF_FFFF);
        assert_eq!(w.as_bytes(), &[0xFF, 0xFF, 0xFF, 0xFF, 0x07]);
        let mut r = ValueReader::new(w.into_bytes());
        assert_eq!(r.read_varint().unwrap(), 0x7FFF_FFFF);
    }

    /// 补充：读端上界 0xFFFFFFFF（Dart 第 5 字节只允许低 4 位，故可读全 u32；
    /// 期望字节 `[0xFF,0xFF,0xFF,0xFF,0x0F]` 由 Python 独立实现算出）
    #[test]
    fn varint_u32_max_roundtrip() {
        let mut w = ValueWriter::new();
        w.write_varint(0xFFFF_FFFF);
        assert_eq!(w.as_bytes(), &[0xFF, 0xFF, 0xFF, 0xFF, 0x0F]);
        let mut r = ValueReader::new(w.into_bytes());
        assert_eq!(r.read_varint().unwrap(), 0xFFFF_FFFF);
    }

    /// Dart: 'truncated varint throws FormatException'
    ///
    /// PORT-NOTE: 该 Dart 用例名叫 "truncated"，但 5 个 `0x80` 在第 5 字节（`shift == 28`）会先命中
    /// `(b & 0xF0) != 0` 的 overflow 分支，实际抛的是 "varint overflow"。Rust 侧照抄同一条路径。
    #[test]
    fn varint_five_continuation_bytes_errors() {
        let mut r = ValueReader::new(vec![0x80u8, 0x80, 0x80, 0x80, 0x80]);
        assert!(matches!(
            r.read_varint().unwrap_err(),
            ProtocolError::InvalidFrame(_)
        ));
    }

    /// Dart: 'varint overflow throws FormatException'
    #[test]
    fn varint_overflow_errors() {
        let mut r = ValueReader::new(vec![0xFFu8, 0xFF, 0xFF, 0xFF, 0x10]);
        assert!(matches!(
            r.read_varint().unwrap_err(),
            ProtocolError::InvalidFrame(_)
        ));
    }

    /// Dart: 'empty data throws FormatException'
    #[test]
    fn varint_empty_data_errors() {
        let mut r = ValueReader::new(Vec::<u8>::new());
        assert!(matches!(
            r.read_varint().unwrap_err(),
            ProtocolError::InvalidFrame(_)
        ));
    }

    /// 补充：单个续位字节后数据耗尽（走 `invalid varint` 而非 overflow 的那条分支）
    #[test]
    fn varint_single_continuation_byte_errors() {
        let mut r = ValueReader::new(vec![0x80u8]);
        let err = r.read_varint().unwrap_err();
        assert!(matches!(err, ProtocolError::InvalidFrame(_)));
        assert!(err.to_string().contains("invalid varint"));
    }

    // ---- group: encodeValue tag coverage ----------------------------------

    /// Dart: 'null → tag 0'
    #[test]
    fn encode_null_is_tag0() {
        assert_eq!(enc(&Value::Undefined), vec![0]);
    }

    /// Dart: 'String → tag 1'
    #[test]
    fn encode_string_is_tag1() {
        let bytes = enc(&Value::from("hello"));
        // 期望字节由 Python 独立实现算出：tag 1 + varint(5) + "hello"
        assert_eq!(bytes, vec![1, 5, b'h', b'e', b'l', b'l', b'o']);

        let mut r = ValueReader::new(bytes);
        assert_eq!(r.read(1).unwrap()[0], 1);
        let len = r.read_varint().unwrap() as usize;
        assert_eq!(String::from_utf8(r.read(len).unwrap().to_vec()).unwrap(), "hello");
    }

    /// Dart: 'Uint8List → tag 3'
    #[test]
    fn encode_vsbuffer_is_tag3() {
        let bytes = enc(&Value::from(vec![10u8, 20, 30]));
        // 期望字节由 Python 独立实现算出：tag 3 + varint(3) + 10,20,30
        assert_eq!(bytes, vec![3, 3, 10, 20, 30]);

        let mut r = ValueReader::new(bytes);
        assert_eq!(decode_value(&mut r).unwrap(), Value::VsBuffer(vec![10, 20, 30]));
    }

    /// Dart: 'List → tag 4'
    #[test]
    fn encode_array_is_tag4() {
        let v = Value::Array(vec![
            Value::Int(1),
            Value::from("a"),
            Value::from(true),
        ]);
        let bytes = enc(&v);
        // 期望字节由 Python 独立实现算出（复刻 Dart 写端算法）：
        // [4, 3, 6, 1, 1, 1, 'a', 5, 4, 't','r','u','e']
        assert_eq!(
            bytes,
            vec![4, 3, 6, 1, 1, 1, 0x61, 5, 4, 0x74, 0x72, 0x75, 0x65]
        );

        let mut r = ValueReader::new(bytes);
        match decode_value(&mut r).unwrap() {
            Value::Array(items) => {
                assert_eq!(items[0], Value::Int(1));
                assert_eq!(items[1], Value::from("a"));
                assert_eq!(items[2], Value::from(true));
            }
            other => panic!("期望 Array，实得 {:?}", other),
        }
    }

    /// Dart: 'Map (JSON) → tag 5'
    ///
    /// 该用例的键序在 Dart（插入序）与 serde_json（BTreeMap 字典序）下**恰好一致**（key < num），
    /// 所以可以直接断言整串字节（期望值由 Python 独立实现以紧凑 JSON 算出）。
    #[test]
    fn encode_map_is_tag5() {
        let v = Value::Object(json!({"key": "value", "num": 42}));
        let bytes = enc(&v);
        let mut expected = vec![5, 24];
        expected.extend_from_slice(br#"{"key":"value","num":42}"#);
        assert_eq!(bytes, expected);

        let mut r = ValueReader::new(bytes);
        match decode_value(&mut r).unwrap() {
            Value::Object(map) => {
                assert_eq!(map["key"], json!("value"));
                assert_eq!(map["num"], json!(42));
            }
            other => panic!("期望 Object，实得 {:?}", other),
        }
    }

    /// Dart: 'int (positive) → tag 6'
    #[test]
    fn encode_positive_int_is_tag6() {
        let bytes = enc(&Value::from(123_456i64));
        assert_eq!(bytes[0], 6);
        let mut r = ValueReader::new(bytes);
        assert_eq!(decode_value(&mut r).unwrap(), Value::Int(123_456));
    }

    /// Dart: 'nested structures'
    #[test]
    fn encode_nested_structures() {
        let v = Value::Object(json!({
            "ints": [1, 2, 3],
            "texts": ["a", "b"],
            "nested": {"x": null},
        }));
        let mut r = ValueReader::new(enc(&v));
        match decode_value(&mut r).unwrap() {
            Value::Object(map) => {
                assert_eq!(map["ints"], json!([1, 2, 3]));
                assert_eq!(map["texts"], json!(["a", "b"]));
                assert!(map["nested"]["x"].is_null());
            }
            other => panic!("期望 Object，实得 {:?}", other),
        }
    }

    /// Dart: 'large negative int treated as Map (tag 5)'
    #[test]
    fn encode_negative_int_falls_back_to_tag5() {
        let bytes = enc(&Value::from_i64(-1));
        assert_eq!(bytes[0], 5); // 负数落进 JSON 分支
        assert_eq!(bytes, vec![5, 2, b'-', b'1']); // Python 独立算出

        let mut r = ValueReader::new(bytes);
        assert_eq!(decode_value(&mut r).unwrap(), Value::Object(json!(-1)));
    }

    /// 补充：tag 6 的写端上界 = 0x7FFFFFFF，越过即落 JSON（Dart `encodeValue` 的 int 分支判定）
    #[test]
    fn encode_int_range_boundary_matches_dart() {
        assert_eq!(Value::from_i64(0x7FFF_FFFF), Value::Int(0x7FFF_FFFF));
        assert_eq!(Value::from_i64(0), Value::Int(0));
        assert_eq!(
            Value::from_i64(0x8000_0000),
            Value::Object(json!(2_147_483_648u64))
        );
        // 0x80000000 落 JSON 分支：期望字节由 Python 独立实现算出
        let mut expected = vec![5, 10];
        expected.extend_from_slice(b"2147483648");
        assert_eq!(enc(&Value::from_i64(0x8000_0000)), expected);
    }

    /// 补充：tag 2（Buffer）解码路径——Dart 写端不产出，但读端接受，需保证解成字节而非数组
    #[test]
    fn decode_buffer_tag2() {
        let mut w = ValueWriter::new();
        w.write_byte(2);
        w.write_varint(2);
        w.write_bytes(&[7, 8]);
        let mut r = ValueReader::new(w.into_bytes());
        assert_eq!(decode_value(&mut r).unwrap(), Value::Buffer(vec![7, 8]));
        // `Value::tag` 与线上 tag 一一对应（tag 2 与 tag 3 在 Dart 侧是同一个分支）
        assert_eq!(Value::Buffer(vec![7, 8]).tag(), 2);
        assert_eq!(Value::VsBuffer(vec![7, 8]).tag(), 3);
        assert_eq!(Value::from_i64(-1).tag(), 5);
    }

    /// 补充：未知 tag（Dart 'unknown value tag' 分支，Dart 测试未覆盖）
    #[test]
    fn decode_unknown_tag_is_unsupported() {
        let mut r = ValueReader::new(vec![7u8]);
        assert!(matches!(
            decode_value(&mut r).unwrap_err(),
            ProtocolError::UnsupportedTag(7)
        ));

        let mut r = ValueReader::new(vec![200u8]);
        assert!(matches!(
            decode_value(&mut r).unwrap_err(),
            ProtocolError::UnsupportedTag(200)
        ));
    }

    /// 补充：`decodeValue` 首字节缺失 → Truncated（Dart 抛 FormatException）
    #[test]
    fn decode_empty_is_truncated() {
        let mut r = ValueReader::new(Vec::<u8>::new());
        assert!(matches!(
            decode_value(&mut r).unwrap_err(),
            ProtocolError::Truncated {
                needed: 1,
                remaining: 0
            }
        ));
    }

    // ---- group: 上限（maxValueBytes / maxContainerItems） ------------------

    /// 补充：字符串长度 > 16MiB → 'string too large'（Dart 的 `length > maxValueBytes`）
    #[test]
    fn decode_string_too_large_is_limit_exceeded() {
        let bytes = tag_with_varint(1, (ValueReader::MAX_VALUE_BYTES + 1) as u64);
        let mut r = ValueReader::new(bytes);
        let err = decode_value(&mut r).unwrap_err();
        assert!(matches!(err, ProtocolError::LimitExceeded(_)));
        assert!(err.to_string().contains("string too large"));
    }

    /// 补充：字节长度 > 16MiB → 'bytes too large'（tag 2 与 3 共用该分支）
    #[test]
    fn decode_bytes_too_large_is_limit_exceeded() {
        for tag in [2u8, 3] {
            let bytes = tag_with_varint(tag, (ValueReader::MAX_VALUE_BYTES + 1) as u64);
            let mut r = ValueReader::new(bytes);
            let err = decode_value(&mut r).unwrap_err();
            assert!(matches!(err, ProtocolError::LimitExceeded(_)), "tag {tag}");
            assert!(err.to_string().contains("bytes too large"));
        }
    }

    /// 补充：JSON 长度 > 16MiB → 'object too large'
    #[test]
    fn decode_object_too_large_is_limit_exceeded() {
        let bytes = tag_with_varint(5, (ValueReader::MAX_VALUE_BYTES + 1) as u64);
        let mut r = ValueReader::new(bytes);
        let err = decode_value(&mut r).unwrap_err();
        assert!(matches!(err, ProtocolError::LimitExceeded(_)));
        assert!(err.to_string().contains("object too large"));
    }

    /// 补充：数组元素数 > 100000 → 'list too large'
    #[test]
    fn decode_list_too_large_is_limit_exceeded() {
        let bytes = tag_with_varint(4, (ValueReader::MAX_CONTAINER_ITEMS + 1) as u64);
        let mut r = ValueReader::new(bytes);
        let err = decode_value(&mut r).unwrap_err();
        assert!(matches!(err, ProtocolError::LimitExceeded(_)));
        assert!(err.to_string().contains("list too large"));
    }

    /// 补充：上限判定是**严格大于**——正好等于 `maxValueBytes` 时先过限检查，再因数据不足报截断
    #[test]
    fn decode_length_equal_to_max_bytes_passes_limit_then_truncated() {
        let bytes = tag_with_varint(1, ValueReader::MAX_VALUE_BYTES as u64);
        let mut r = ValueReader::new(bytes);
        assert!(matches!(
            decode_value(&mut r).unwrap_err(),
            ProtocolError::Truncated { .. }
        ));
    }

    /// 补充：正好等于 `maxContainerItems` 时同样先过限检查，再因缺数据报截断
    #[test]
    fn decode_count_equal_to_max_items_passes_limit_then_truncated() {
        let bytes = tag_with_varint(4, ValueReader::MAX_CONTAINER_ITEMS as u64);
        let mut r = ValueReader::new(bytes);
        assert!(matches!(
            decode_value(&mut r).unwrap_err(),
            ProtocolError::Truncated { .. }
        ));
    }

    /// 补充：长度合法但数据被截断（Dart 抛 FormatException → Truncated）
    #[test]
    fn decode_truncated_payload_is_truncated() {
        // tag 1 + varint(5) + 只有 2 字节
        let mut w = ValueWriter::new();
        w.write_byte(1);
        w.write_varint(5);
        w.write_bytes(&[b'h', b'i']);
        let mut r = ValueReader::new(w.into_bytes());
        assert!(matches!(
            decode_value(&mut r).unwrap_err(),
            ProtocolError::Truncated {
                needed: 5,
                remaining: 2
            }
        ));
    }

    /// 补充：tag 5 内容不是合法 JSON（Dart `jsonDecode` 抛 FormatException）
    #[test]
    fn decode_invalid_json_is_json_error() {
        let mut w = ValueWriter::new();
        w.write_byte(5);
        w.write_varint(4);
        w.write_bytes(b"nope");
        let mut r = ValueReader::new(w.into_bytes());
        assert!(matches!(
            decode_value(&mut r).unwrap_err(),
            ProtocolError::Json(_)
        ));
    }

    /// 补充：tag 1 内容不是合法 UTF-8（Dart `utf8.decode` 抛 FormatException）
    #[test]
    fn decode_invalid_utf8_is_utf8_error() {
        let mut w = ValueWriter::new();
        w.write_byte(1);
        w.write_varint(2);
        w.write_bytes(&[0xFF, 0xFE]);
        let mut r = ValueReader::new(w.into_bytes());
        assert!(matches!(
            decode_value(&mut r).unwrap_err(),
            ProtocolError::Utf8(_)
        ));
    }

    // ---- group: IpcFraming roundtrip --------------------------------------

    /// 补充：13 字节头布局（`[type][id u32be][ack u32be][bodyLen u32be]` + body）
    #[test]
    fn framing_header_layout() {
        assert_eq!(IpcFraming::encode(&[]), vec![1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
        let frame = IpcFraming::encode(&[0xAA, 0xBB, 0xCC]);
        assert_eq!(frame.len(), 16);
        assert_eq!(frame[0], IpcFraming::TYPE_REGULAR);
        assert_eq!(&frame[1..5], &[0, 0, 0, 0]); // id = 0
        assert_eq!(&frame[5..9], &[0, 0, 0, 0]); // ack = 0
        assert_eq!(&frame[9..13], &[0, 0, 0, 3]); // bodyLen = 3（大端）
        assert_eq!(&frame[13..], &[0xAA, 0xBB, 0xCC]);
    }

    /// Dart: 'single chunk parse'
    #[test]
    fn framing_single_chunk_parse() {
        let mut body = ValueWriter::new();
        body.write_varint(42);
        let frame = IpcFraming::encode(&body.to_bytes());

        let mut parser = IpcFrameParser::new();
        let results = parser.accept_chunk(&frame);
        assert_eq!(results.len(), 1);
        let mut r = ValueReader::new(results[0].clone());
        assert_eq!(r.read_varint().unwrap(), 42);
    }

    /// Dart: 'split into multiple chunks'（逐字节喂入）
    #[test]
    fn framing_split_into_multiple_chunks() {
        let mut body = ValueWriter::new();
        body.write_varint(999);
        let frame = IpcFraming::encode(&body.to_bytes());

        let mut parser = IpcFrameParser::new();
        let mut parts: Vec<Vec<u8>> = Vec::new();
        for i in 0..frame.len() {
            parts.extend(parser.accept_chunk(&frame[i..i + 1]));
        }
        assert_eq!(parts.len(), 1);
        let mut r = ValueReader::new(parts[0].clone());
        assert_eq!(r.read_varint().unwrap(), 999);
    }

    /// Dart: 'two frames in one chunk'
    #[test]
    fn framing_two_frames_in_one_chunk() {
        let mut b1 = ValueWriter::new();
        b1.write_varint(10);
        let mut b2 = ValueWriter::new();
        b2.write_varint(20);

        let mut combined = IpcFraming::encode(&b1.to_bytes());
        combined.extend_from_slice(&IpcFraming::encode(&b2.to_bytes()));

        let mut parser = IpcFrameParser::new();
        let results = parser.accept_chunk(&combined);
        assert_eq!(results.len(), 2);
        assert_eq!(ValueReader::new(results[0].clone()).read_varint().unwrap(), 10);
        assert_eq!(ValueReader::new(results[1].clone()).read_varint().unwrap(), 20);
    }

    /// Dart: 'partial header across chunks'
    #[test]
    fn framing_partial_header_across_chunks() {
        let mut body = ValueWriter::new();
        body.write_varint(555);
        let frame = IpcFraming::encode(&body.to_bytes());

        let mut parser = IpcFrameParser::new();
        let r1 = parser.accept_chunk(&frame[..5]); // 只给 5 字节，头部不完整
        assert!(r1.is_empty());
        assert_eq!(parser.buffered_len(), 5);

        let r2 = parser.accept_chunk(&frame[5..]);
        assert_eq!(r2.len(), 1);
        assert_eq!(ValueReader::new(r2[0].clone()).read_varint().unwrap(), 555);
        assert_eq!(parser.buffered_len(), 0);
    }

    /// 补充：头部完整但 body 被切开（Dart 测试只覆盖了头部被切开）
    #[test]
    fn framing_partial_body_across_chunks() {
        let frame = IpcFraming::encode(&[1, 2, 3, 4, 5]);
        let mut parser = IpcFrameParser::new();

        assert!(parser.accept_chunk(&frame[..15]).is_empty()); // 头 13 + body 前 2
        assert_eq!(parser.buffered_len(), 15);
        let results = parser.accept_chunk(&frame[15..]);
        assert_eq!(results, vec![vec![1u8, 2, 3, 4, 5]]);
        assert_eq!(parser.buffered_len(), 0);
    }

    /// 补充：非 `typeRegular` 的帧被丢弃但同样被消费（Dart 代码里的分支，测试未覆盖）
    #[test]
    fn framing_non_regular_type_is_skipped_and_consumed() {
        let mut odd = vec![2u8]; // type = 2（非 regular）
        odd.extend_from_slice(&0u32.to_be_bytes()); // id
        odd.extend_from_slice(&0u32.to_be_bytes()); // ack
        odd.extend_from_slice(&1u32.to_be_bytes()); // bodyLen = 1
        odd.push(0xAA); // body

        let regular = IpcFraming::encode(&[0x55]);

        let mut parser = IpcFrameParser::new();
        // 非 regular 帧单独到达：不产出，但必须被消费掉（否则会挡住后面的帧）
        assert!(parser.accept_chunk(&odd).is_empty());
        assert_eq!(parser.buffered_len(), 0);
        // 紧随其后的 regular 帧仍能正常解出
        assert_eq!(parser.accept_chunk(&regular), vec![vec![0x55u8]]);
        // 同块内混合：跳过前一个，只产出 regular
        let mut combined = odd.clone();
        combined.extend_from_slice(&regular);
        let mut parser2 = IpcFrameParser::new();
        assert_eq!(parser2.accept_chunk(&combined), vec![vec![0x55u8]]);
        assert_eq!(parser2.buffered_len(), 0);
    }
}
