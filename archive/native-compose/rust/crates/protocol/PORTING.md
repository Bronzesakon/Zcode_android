# 移植契约：`Reference_code/lib/protocol/*.dart` → Rust

## 唯一真理来源

`Reference_code/lib/protocol/*.dart` 是对远程控制网页协议**逆向**的结果。**行为以它为准**，线上格式一个字节都不能变。
对应测试在 `Reference_code/test/`，是移植期望值的主要来源。

工作顺序：**先读 Dart 实现，再读它的测试**，把测试用例逐条搬成 Rust 的 `#[cfg(test)] mod tests`。

## 本 crate 的边界（最重要的一条）

`zemote-protocol` 是**纯逻辑、同步、无 I/O** 的 crate：

- 不做网络、不做文件、不做定时器、不用 async、不依赖 tokio。
- WebSocket / 存储 / 计时 / 权限 / 通知等宿主能力由 Kotlin 侧实现。需要宿主的地方，在本 crate 里**只定义 trait**，实现留给宿主。
- 这样 FFI 边界最小（UniFFI 好接），单测也不需要任何 mock 运行时。

## 命名与类型映射

| Dart | Rust |
|---|---|
| `Uint8List` / `List<int>`（字节） | `Vec<u8>` / `&[u8]` |
| `String` | `String` / `&str` |
| `int`（64 位） | 语义定宽：CRC 用 `u32`、长度用 `usize`、时间戳用 `i64` |
| `Map<String, dynamic>` | 动态用 `serde_json::Value`；结构固定用 `#[derive(Serialize, Deserialize)]` 的 struct |
| `throw FormatException(msg)` | `Err(ProtocolError::InvalidFrame(msg.into()))` |
| `StateError` / `ArgumentError` | `ProtocolError::InvalidState(..)` / `InvalidFrame(..)` |
| `null` | `Option<T>` |
| 方法与字段 | 一律 `snake_case` |
| JSON 里的 camelCase 字段名 | `#[serde(rename = "camelCaseName")]` 保持不变——**线上字段名不能改** |
| `DateTime` | `i64`（Unix epoch 毫秒） |
| `Future`/`Stream` | 不在本 crate；改成 `fn step(&mut self, input) -> Vec<Event>` 之类的纯状态机接口，并在 `// PORT-NOTE:` 里说明宿主该怎么驱动 |

## 硬性要求

1. **格式零漂移**：7-bit 小端 varint、位掩码、CRC-32 多项式、base64url 去 padding、JSON 字段名与类型——全部照抄 Dart。
2. **只动你自己的模块文件**。不要改 `lib.rs`、`Cargo.toml`、`error.rs`，也不要碰别人的模块。需要跨模块共享的类型：先在自己模块里定义并 `pub`，留 `// PORT-NOTE:` 说明谁要用，由主线决定是否进 `lib.rs` 的 `prelude`（**不搬家**，见文末「已决事项」）。
3. **不要运行 `cargo`**。`rust/target/` 是共享目录，并发会互相等锁，而且别的模块此刻可能还没写完——你跑出来的失败不代表你的代码有问题。正确性由主线统一 `cargo test` 收口；你只负责写对。
4. 每个公开项写 `///` 文档，并注明它对应 Dart/网页端的哪个函数，例如：
   `/// 对应 `ValueWriter.writeValue`（Dart）→ 网页端 `Sm()``。
5. **测试期望值必须来自既有测试**：`Reference_code/test/*.dart` 里的断言值直接搬。没有现成测试的（如 `crc32`、`proof`），用**独立实现**算一份期望值（本机有 Python，可独立算 HMAC-SHA256 / base64url / CRC-32），并在注释里注明"该期望值由 Python 独立算出"——不要自己编造。
6. Dart 里语义可疑、像 bug、或依赖平台差异的地方：**照抄行为**，并用 `// PORT-NOTE:` 写明你的判断与理由。
7. 不要顺手"优化"算法或改写结构。移植的第一目标是**可对照**：Dart 里一个函数，Rust 里一个函数，命名可追溯。

## 交付与回报

- 交付物：你的模块文件（含 `#[cfg(test)] mod tests`）。
- 回报格式：**≤15 行**摘要——改了哪个文件、移植了哪些函数、搬了多少条测试用例、`PORT-NOTE` 里记了哪些待主线收口的事项、你认为有歧义的地方。

## 已决事项（主线收口时定下，后续不要重新提出）

移植过程中各模块提的 `PORT-NOTE` 里，下面两条**反复出现且已定论**，不要再作为待办上报。

### 一、不启用 `serde_json` 的 `preserve_order`

`ipc_codec.rs`、`channel_client.rs`、`relay_client.rs`、`conversation.rs` 都提到：Dart 的 `Map`
保插入序，`serde_json` 默认按键字典序，因此我们序列化出的 JSON 键序与 Dart 不同。

**结论：不影响协议正确性，不引入 `indexmap` 依赖。** 涉及键序的只有两处：

1. 错误消息与日志文本（`channel_client::dart_to_string` / `dart_json_to_string`，调用点就是
   `ChannelRpcError` 的消息构造）——纯展示，键序只影响人读到的顺序。
2. 线上字节：`rpc_transport` 的 `checksum` 是**发送方对自己序列化出的那份字节**算的
   （Dart `RpcFrameTransport.sendMessage` 的 `Crc32.hexOf(bytes)`），接收方对**自己收到的
   字节**重算（`acceptPayload` 的 `Crc32.hexOf(message)`）——两边算同一串字节，自洽，
   与键序无关。对端只做 JSON 解析，不会拿我们的字节重新序列化后比对。
   另：`proof::calculate_proof` 的 HMAC 输入是 `'$nonce|$role|$deviceSid'` 拼接串，与 JSON 无关。

判据是"**键序是否参与任何一方的校验或比对**"，答案是两处都不参与。若将来抓包证实对端确实会对
我们发出的 JSON 做字节级复算，再回来启用 `preserve_order` 并重跑全部测试——那时各模块测试里
可能有用例写死了字典序期望值，需要一并调整。

### 二、公共类型走 `prelude` 再导出，不搬家

`Uri`、`Value`、`RpcFramePayload`、`JsonMap`、`dart_to_string` 等被多个模块复用的项，
主线**不移动定义位置**，改为在 `lib.rs` 的 `prelude` 里再导出。

原因：模块与 Dart 源文件一一对应是移植可追溯性的基础（硬性要求 7）。为了"看起来整齐"
去重排上万行代码，收益只是审美，风险是打散对照关系。需要精确对应某个 Dart 函数时按模块
导入即可。

### 附：`ipc_codec::IpcFraming` / `IpcFrameParser` 在应用内没有调用点

全仓核实：这两个类型（13 字节 IPC 帧壳）在 `Reference_code/lib/` 里只出现在自己的定义处，
唯一消费者是 `test/ipc_codec_test.dart`；桥接路径明确不走它（`zemote_client.dart:328-329`）。
同文件的 `ValueWriter` / `ValueReader` / `encode_value` / `decode_value` **是活的**，
`channel_client.dart:36,56` 正在用。移植时值编解码器是核心路径，帧壳只作协议完整性参考与测试对象。

