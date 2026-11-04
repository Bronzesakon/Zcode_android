# 架构：分层与数据流

本文是各层的总图。每一层的**契约文档**才是实现依据，这里只讲边界与数据怎么流。

## 层次总览

```
┌─────────────────────────────────────────────────────────────────────┐
│  界面层（Compose）                          ← 按 web 3.17.1 逆向规范实现     │
│  按 design/SCREENS.md 一比一复刻网页版；视觉基线取自 design/tokens/  │
└────────────────────────────┬────────────────────────────────────────┘
                             │ 只读 StateFlow / 调 ViewModel 方法
┌────────────────────────────▼────────────────────────────────────────┐
│  表现层  app.zemote.ui.*（ViewModel + 导航图）                       │
│  把引擎事件收敛成 UI 状态；不碰协议、不碰 JSON 细节                   │
└────────────────────────────┬────────────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────────────┐
│  引擎层  app.zemote.engine.ZemoteEngine                              │
│  协程事件循环：执行 ws_* 动作、arm/撤定时器、把 socket 与定时器事件   │
│  回喂核心、把 completed 解析到等待中的 Deferred                      │
│  ★ 单线程调度（同一 handle 上的 native 调用不可重入）                 │
└────────────────────────────┬────────────────────────────────────────┘
                             │  JNI：JSON 字符串进 / JSON 字符串出
┌────────────────────────────▼────────────────────────────────────────┐
│  FFI 层  rust/crates/ffi                                             │
│  RecordingTransport（把 connect/send/close 记成事件）+ 会话注册表     │
│  ★ 零回调：Kotlin 不实现任何 Rust trait                              │
│  契约见 docs/FFI.md                                                  │
└────────────────────────────┬────────────────────────────────────────┘
                             │  纯函数调用
┌────────────────────────────▼────────────────────────────────────────┐
│  协议核心  rust/crates/protocol（纯逻辑 / 同步 / 无 I/O / 无时钟）    │
│  relay_client · rpc_transport · ipc_codec · channel_client           │
│  conversation · conversation_transport · automation_client           │
│  connection_params · device_info · proof · crc32 · id · zemote_client│
└─────────────────────────────────────────────────────────────────────┘

旁路（不经核心，但同属应用）：
  app.zemote.data    设备列表 / 设置持久化（DataStore + Keystore 加密凭据）
  app.zemote.pairing 配对 URL 前置筛查（权威校验仍在 Rust）
  app.zemote.update  应用内更新（拉版本清单 / 断点续传 / MD5 校验 / FileProvider 安装）
```

## 数据流：一次「发消息」要经过什么

```
用户在界面输入文本
  → ViewModel 调 engine.callConversationOp(op="sendText", args={...})
  → FFI: {"method":"conversation_call", ...}                ← Kotlin 只透传 op，不理解协议
  → Rust: ConversationTransport::call_op 构造命令信封（CAS 版本、clientId、超时…）
  → 核心把命令编码成 IPC 值 → 分片 → CRC → 中继信封
  → 返回 events: [{"type":"ws_send_text","text":"..."}]
  → 引擎把它交给 OkHttp 发出去
  → 对端回帧 → 引擎喂 {"type":"text_frame", ...} 回核心
  → 核心重组 → 通道事件 → 路由到对应 ConversationTransport（按 owns_channel_id）
  → 返回 events: [{"type":"conversation_event","event":{"type":"rows_updated",...}}]
  → 引擎转成 Flow → ViewModel → 界面
```

关键点：**Kotlin 全程不知道 IPC 编码、分片、CRC、CAS 信封长什么样**。它只搬运 JSON。
协议知识 100% 在 Rust，这正是这次重写的目的。

## 两条贯穿性约定

### 一、时间与 I/O 全部由宿主提供

核心不读时钟、不做 I/O、不持定时器。它只发 `TimerStart`/`TimerCancel` 事件，宿主到点回喂
`TimerFired`；socket 的建连/收帧/关闭也由宿主回喂。因此：

- 整条链路可以用假时钟 + 假 socket 在**主机上**完整测试（协议 crate 的 311 个测试就是这么来的）。
- 没有 async、没有 tokio、没有跨线程唤醒，FFI 边界小到只有 6 个函数。

### 二、两套定时器命名空间，不要混

| | 发出方 | 句柄 | 回喂 |
|---|---|---|---|
| relay 槽位 | `RelayEvent::TimerStart{timer}` | `RelayTimer` 枚举（`heartbeat`/`waiting`/`reconnect`/`rewait`） | `{"type":"relay_timer_fired","timer":"..."}` |
| 门面 token | `ZemoteEvent::TimerStart{token}` | `TimerId`（u64） | `{"type":"timer_fired","token":N}` |

两者互不相干。`heartbeat` 是唯一周期定时器（`repeat=true`），其余都是单次。

## 为什么 FFI 是"JSON 进 JSON 出、零回调"

原本 `RelayTransport` 是回调 trait，最自然的做法是让 Kotlin 实现它。**我们没有**：

- Kotlin 实现 Rust trait 意味着跨语言对象生命周期、回调线程、JNI 局部引用管理、panic 跨边界——
  每一处都是容易出错且难测的。
- 改成 Rust 侧 `RecordingTransport` 记录动作、随事件返回后，Kotlin 只需要处理 JSON。
- 代价是每次 socket 动作多一趟 JSON 序列化，相对网络往返可忽略。

`docs/FFI.md` §1 有完整论述。

## 安全边界

| 项 | 处理 |
|---|---|
| 远控 URL 里的 `hash`（配对密钥材料） | 落盘前用 Android Keystore AES-GCM 加密；**内存中是明文**（引擎要用）。Keystore 密钥丢失时降级为"需重新配对"，不崩不静默 |
| `passHash` | **绝不出现在 FFI 任何返回值里**（`snapshot` 有测试断言） |
| Rust panic | release profile 刻意保留 `panic = "unwind"`（未设 `abort`），每个 JNI 入口 `catch_unwind`，把 panic 转成一次失败调用而不是 SIGABRT |
| 自更新包的 MD5 | 只做**完整性校验**（与服务端资产一致），**不是**防篡改手段——这一点在代码注释里明确写出，避免误用 |
| FileProvider 路径白名单 | 只暴露 `updates/` 目录，不写 `<root-path/>` |
| 签名 | 本项目专属 keystore（别名 `zemote`），不复用 android-shell 的；签名材料与 `key.properties` 均不入库 |

## 当前实现状态

按"层"清点，避免"以为还有一层没做"或"以为已经做完了"。

| 层 | 状态 | 说明 |
|---|---|---|
| 协议核心（Rust） | ✅ 完成 | 13 个模块，逐条对照 Dart 移植；纯逻辑、可主机测试 |
| FFI 绑定层（Rust） | ✅ 完成 | 6 个 JNI 函数、零回调、JSON 进出；对话/定时任务走 §13 的 `op` 通用透传 |
| 宿主适配（Rust 侧） | ✅ 完成 | 记录型 transport、会话注册表、token 命名空间隔离、panic 捕获 |
| 引擎（Kotlin） | ✅ 完成 | 协程事件循环 + OkHttp WebSocket + 双命名空间定时器 |
| 持久化（Kotlin） | ✅ 完成 | DataStore + Keystore 加密凭据 |
| 配对领域层（Kotlin） | ⚠️ 部分 | URL 筛查/去重/接口已就绪；**相机扫码未接入**（属界面层） |
| 应用内更新（Kotlin） | ✅ 完成 | 版本清单、断点续传、MD5、同源签名校验、FileProvider |
| 表现层（Kotlin） | ⚠️ 部分 | ViewModel + 手写导航 + 状态接线已完成；**屏幕的视觉样式未实现** |
| 界面样式 | ❌ 未实现 | 本次范围外。屏幕骨架里需要设计的位置留了 `// TODO(UI):` |
| 打包与发布 | ✅ 完成 | 本地 debug/release 均可构建；CI 已改造（尚未实际跑过） |
| 真机运行验证 | ❌ 未做 | 需要 arm64 真机；x86_64 模拟器跑不了 arm64-only 的包 |

已知缺口（有意不修，理由写在 `docs/FFI.md` §14）：对话层快照没有查询入口、多桥时通道事件路由有歧义、
`conversation_close` 不为在飞操作发失败、完成事件有意发两遍。

## 相关文档

- `docs/FFI.md` —— **冻结的 FFI 契约**（JNI 符号、信封、输入/输出 JSON、线程约定、已知缺口）
- `docs/ENGINE-API.md` —— 引擎层公开 API（ViewModel 层据此编写）
- `rust/crates/protocol/PORTING.md` —— 移植契约（命名映射、硬性要求、已决事项）
- `design/PROTOCOL-MAP.md` —— 协议全貌（消息条目、握手步骤、错误码族、35 条待证实项）
- `design/SCREENS.md` —— 界面清单（界面层实现时用）
- `design/tokens/` —— 设计令牌（Compose 主题由 `Color.kt`/`Type.kt`/`Dimens.kt` 消费）
