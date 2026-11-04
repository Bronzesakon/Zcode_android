# PROTOCOL-MAP —— 远程控制协议面索引

> **用途**：`rust/crates/protocol/` 下一批移植（`rpc_transport` / `relay_client` /
> `automation_client` / `channel_client` / `zemote_client`）的导航图。契约与交付要求见
> `rust/crates/protocol/PORTING.md`；**行为以 Dart 为准**，本文只做索引，不替代 Dart 源码。
>
> **来源（全部只读）**
> - 主：`Reference_code/lib/protocol/*.dart`（12 文件 / 3,524 行）+ `Reference_code/test/*.dart`
> - 补充：同仓 Dart 参考实现的调用点（`lib/ui/*.dart` 等，**超出 `protocol/` 目录**，逐条标注文件:行）
> - 交叉核对（姊妹仓库，只读）：`E:\Zcode_harmony\Docs\11/13/14/16/17-remote-v4-*.md`
> - 另一份实现（只读）：`E:\Zcode_harmony\android-shell\app\src\main\assets\zcode-protocol.js`、`inject.js`
>
> **口径**：每条结论带出处；判不出的写 `UNKNOWN`。行号对应本文写作时的 `Reference_code` 工作树。
> 网页 schema 的字符偏移（off N）抄自 Docs 16/14，未经二次取证，标为「二手」。

---

## 0. 一页速览

### 0.1 协议分层（自下而上）

| 层 | 形态 | 载体 | 本仓实现 |
|---|---|---|---|
| L0 URL | `https://zcode.z.ai/remote/v4?sid&hash&t&mid&name&app_version&theme` | 系统浏览器/深链 | `connection_params.dart` |
| L1 WS 传输 | `wss://<host>/ws?mid=` JSON 文本帧，顶层 `type` 判别 | 原生直连 | `relay_client.dart` |
| L2 认证/配对 | `auth_init → auth_challenge → auth_response(proof) → auth_ack` | 同上 | `relay_client.dart` + `proof.dart` |
| L3 业务封套 | `{type:'data', payload:{zcode_type:…}, client_ts}` | 同上 | `relay_client.dart:203` |
| L4 业务消息 | 19 个 `zcode_type`（bootstrap / workspace / bridge / platform / 诊断） | 同上 | `zemote_client.dart` |
| L5 分片传输 | `rpc-frame` / `rpc-frame-ack`（base64 + crc32 + 分片） | 同上 | `rpc_transport.dart` + `crc32.dart` |
| L6 通道 RPC | ChannelClient 值编码流 `[reqType,reqId,channel,name]+arg` | L5 逻辑消息体 | `channel_client.dart` + `ipc_codec.dart` |
| L7 对话协议 | Conversation V4（hello / subscribe / 帧流 / 命令） | L6 的 `zcode-agent` 通道 | `conversation.dart` |

**关键：L6 之上没有额外 IPC 头**。`zemote_client.dart:328-329` 明确：「over the workspace bridge each
rpc-frame message IS one ChannelClient body — no 13-byte IPC framing on this path」。
`zcode-protocol.js` 头部注释独立复述了同一结论（"the essential discovery"）。

### 0.2 文件职责表

| 文件 | 行 | 职责 | 对应网页端 |
|---|---|---|---|
| `connection_params.dart` | 74 | URL 解析 + relay WS URL 推导 | `zC()` / `Jc()` |
| `proof.dart` | 17 | HMAC-SHA256 → base64url-nopad 证明 | `aen()` / `ien()` |
| `id.dart` | 15 | UUID v4 + 带前缀 requestId | `ua()` |
| `crc32.dart` | 27 | IEEE CRC-32，`hexOf` 出 8 位小写十六进制 | `Bf` |
| `device_info.dart` | 27 | 客户端自称：`name='zemote'`、platform | 网页自称 `mobile-browser`/`web` |
| `relay_client.dart` | 471 | WS 连接、认证、配对状态机、心跳、重连、出站排队 | `pen` / `X2t` |
| `rpc_transport.dart` | 218 | 逻辑消息 ↔ `rpc-frame` 分片、重组、crc32、ack | `sp()`/`lp()`、`Xf`/`Zf` |
| `ipc_codec.dart` | 177 | 值编解码（tag 0–6 + 7-bit varint）、13 字节 IPC 帧 | `Sm()`/`Cm()`、`Mne()` |
| `channel_client.dart` | 177 | ChannelClient 请求/响应/事件订阅 + 33 个通道名 | `Pne`、`Wb` |
| `zemote_client.dart` | 528 | 高层门面：relay→pair→bootstrap→bridge→channel | `otn()`、`k()` |
| `automation_client.dart` | 165 | 定时任务 CRUD（`zcode-agent` 通道） | — |
| `conversation.dart` | 1,628 | 对话 V4 传输 + 订阅 + 快照/增量状态机 + 数据模型 | `sk()`/`uk()`、`fke()`/`pke()`/`QAe` |

### 0.3 状态机（`RelayState`，relay_client.dart:11-21）

`idle → connecting → authenticating → waiting | paired`，失败支线 `reconnecting / error / kicked / closed`。

---

## 1. 握手与连接建立（逐步）

| # | 步骤 | 方向 | 消息 / 动作 | 载荷关键字段 | 实现出处 |
|---|---|---|---|---|---|
| 1 | 解析 URL | — | `sid`,`hash`,`t` 必填；`mid`,`name`,`app_version`,`theme` 可选；scheme 必须 `https`/`wss` | — | `connection_params.dart:31-57`；测试 `test/connection_params_test.dart` |
| 2 | 推导 relay WS | — | `ws(s)://<host>:<port>/ws`，仅当有 `mid` 时带 `?mid=` | — | `connection_params.dart:61-71` |
| 3 | 建 WS | → | `WebSocketChannel.connect(uri)` + `await socket.ready` | — | `relay_client.dart:126-131` |
| 4 | `auth_init` | → 桌面 | `{type:'auth_init', role:'terminal', device_sid, meta:{platform,version,name}, client_ts}` | `meta.platform`=真实平台；`meta.name`=`'zemote'` | `relay_client.dart:160-170`；`device_info.dart:7-27` |
| 5 | `auth_challenge` | ← 桌面 | `{type:'auth_challenge', nonce}` | `nonce` | `relay_client.dart:241-242` |
| 6 | 算 proof | — | `base64url_nopad(HMAC-SHA256(key=utf8(passHash), msg=utf8('$nonce\|terminal\|$deviceSid')))` | — | `proof.dart:8-17`；调用 `relay_client.dart:245-250` |
| 7 | `auth_response` | → 桌面 | `{type:'auth_response', device_sid, proof, client_ts}` | — | `relay_client.dart:243-252` |
| 8 | `auth_ack` | ← 桌面 | `{type:'auth_ack', pair_status}` | `pair_status ∈ {waiting, matched}` | `relay_client.dart:254-256` |
| 9 | 配对判定 | — | `waiting`→等桌面匹配（30s 超时 → `invalid-mobile-connection`）；`matched`→`paired` | — | `relay_client.dart:271-305`、`:403-414` |
| 10 | 心跳 | → 桌面 | 每 10s `{type:'pair_status_query', device_sid, client_ts}`；ack 超 30s 重连 | — | `relay_client.dart:348-374` |
| 11 | `bootstrap-request` | → 桌面 | `{zcode_type:'bootstrap-request', requestId}`（配对后**首帧**，取全量工作区/任务快照） | — | `zemote_client.dart:217-225` |
| 12 | `bootstrap-response` | ← 桌面 | `result` 5 键：`windowControlSessionId`(必填)、`workspaces[]`、`tasks[]`、`initialViewState?`、`mobileViewState?` | 键表见 Docs 17 §2；Dart 只取 `result.workspaces` | `zemote_client.dart:224`；Docs 17 §2.1-2.5 |
| 13 | `workspace-list-request/response` | 双向 | `{requestId}` → `{requestId, success:true, result:{workspaces, tasks?, activeWorkspaceKey?, activeTaskId?}}` | — | `zemote_client.dart:228-237` |
| 14 | `mobile-view-state-update` | → 桌面 | `{zcode_type, viewState:{activeWorkspaceKey, activeTaskId?, updatedAt}, deviceInfo:{platform,version,name}}` | Dart 缺 `deviceInfo.updatedAt`（见 §5） | `zemote_client.dart:386-403` |
| 15 | `workspace-bridge-open` | → 桌面 | `{zcode_type, requestId, bridgeSessionId, bridgeGeneration, workspaceKey, taskId?, recoveryId?}` | — | `zemote_client.dart:273-287`、`:361-375` |
| 16 | `workspace-bridge-ready` | ← 桌面 | `{requestId, bridgeSessionId, bridge:{kind:'local'\|'remote', bridgeSessionId, bridgeGeneration?, recoveryId?, workspaceKey, workspacePath, initialTaskId?, workspaceIdentity?+remoteSessionId?(remote 必填)}}` | `kind` 判别联合 `cg` | `zemote_client.dart:282-293`；Docs 14 §7.5 |
| 17 | 装栈 | — | `RpcFrameTransport(bridgeSessionId, bridgeGeneration, recoveryId)` + `ChannelClient(sendBody=transport.sendMessage)`；注册到 `_frameRouters[bridgeSessionId]`；**冲刷早到的 rpc-frame**（Initialize 竞态） | — | `zemote_client.dart:314-349` |
| 18 | `Initialize`（IPC） | ← 桌面 | ChannelClient 值流首帧 `[200, 0]` → `ready` 完成 | — | `channel_client.dart:40-44`；`test/channel_client_test.dart:32-49` |
| 19 | 对话握手① | → 桌面 | `helloConversationV4()` → `{connectionId}`（附件上传要用） | — | `conversation.dart:69-73` |
| 20 | 对话握手② | → 桌面 | `initializeConversationV4({kind:'clientHello', protocolVersion:3, clientId, clientKind:'mobileApp', appVersion:'3.6.5'})` | `appVersion` 是**协议能力版本**不是发布号 | `conversation.dart:74-82`、`:13`、`:46-48` |
| 21 | 订阅 | → 桌面 | `subscribeConversationV4({...scope, sessionId})` → `{ack:{subscriptionId, logEpoch}}` | 60s 超时 | `conversation.dart:803-844`、`:1005`、`:1024-1028` |
| 22 | 帧事件订阅 | → 桌面 | `addEventListener('zcode-agent','onDynamicConversationFrame', arg=scope)`（reqType 102） | — | `conversation.dart:806-811`；`channel_client.dart:105-128` |
| 23 | 命令 | → 桌面 | `sendConversationCommandV4({...scope, envelope})`，envelope `{commandId, clientId, sessionId, baseRevision?, baseLogEpoch?, type, payload, issuedAt}` | — | `conversation.dart:145-211` |

**握手总步数：23 步**（其中 URL→配对 10 步、业务引导 4 步、桥+通道 4 步、对话 5 步）。

### 1.1 桥恢复路径（zemote_client.dart:44-130）

| 触发 | 动作 |
|---|---|
| relay 进 `reconnecting`/`error` | 所有活跃桥 `degraded='reconnecting'`，置 `_needsBridgeRecovery` |
| relay 回 `paired` | 逐桥恢复，最多 15 次 × 3s |
| 恢复尝试 1 | `workspace-reconnect-request{requestId, workspaceKey}` → `-response{success}` |
| 恢复尝试 2 | 重新 `workspace-bridge-open`（新 bridgeSessionId、generation+1、回带旧 `recoveryId`），**换栈进同一个 BridgeSession** |
| 桌面推 `bridge-degraded{bridgeSessionId, reason}` | 按 id 命中则标降级并触发恢复 |
| 恢复成功 | `session.recovered++` → 订阅基类重订阅（`conversation.dart:846-881`） |

---

## 2. 传输层

### 2.1 `rpc_transport.dart` 负责什么

| 关注点 | 事实 | 出处 |
|---|---|---|
| **帧格式** | 一条逻辑消息切成 N 个 `rpc-frame` 业务消息；每帧带完整身份三元组 + 分片元数据 | `rpc_transport.dart:92-102` |
| **身份三元组** | `bridgeSessionId`(必) / `bridgeGeneration`(可) / `recoveryId`(可) | `:66-70` |
| **分片** | 原始切片 `_fragmentPayloadBytes = 512 KiB`，`dataBase64` 承载；分片数 `ceil(len/512KiB)` | `:26`、`:80-103` |
| **重组** | 按 `messageSeq` 聚合，乱序可收；完整后校验长度 + crc32 | `:150-181`、`:192-218` |
| **校验** | 发端算 `crc32` 放 `checksum:{algorithm:'crc32', value:<8位小写hex>}`；收端重算比对，不符则丢弃且**不 ack** | `:79`、`:168-180`；`crc32.dart:25-26` |
| **ack** | 成功重组后回 `{zcode_type:'rpc-frame-ack', bridgeSessionId, ackMessageSeq}` | `:173-177` |
| **超时/清理** | 每 30s 扫一次，组装超过 60s 的丢弃（`purged stale assembly`） | `:50-64` |
| **上限** | `maxPhysicalFrameBytes=1 MiB`(仅声明未强制) / `maxMessageBytes=64 MiB` / `maxFragments=256` | `:14-26` |
| **重连** | **不在本文件**——重连由 `relay_client.dart` 负责，本文件只提供 `dispose()` | `:185-189` |
| **心跳** | **不在本文件**——心跳在 relay 层（`pair_status_query`） | `relay_client.dart:348-374` |

### 2.2 帧类型标签与长度约定

**`rpc-frame` 字段表**（Dart 发端 `rpc_transport.dart:92-102`；网页 schema `Xm` off 189,836 为二手）：

| 字段 | 类型 | Dart 常量/校验 | 备注 |
|---|---|---|---|
| `zcode_type` | `'rpc-frame'` | — | 唯一判别键 |
| `bridgeSessionId` | string | 必填 | 不匹配则 `acceptPayload` 返回 false（交还上层） |
| `bridgeGeneration` | int? | 有才带 | — |
| `recoveryId` | string? | 有才带 | — |
| `seq` | int | 出站自增，**全局单调**（跨消息累加） | `:34`、`:91` |
| `messageSeq` | int | 出站自增，**每条逻辑消息一个** | `:35`、`:78` |
| `fragmentIndex` | int | `0..fragmentCount-1` | — |
| `fragmentCount` | int | `>=1`，`<=256` | — |
| `messageBytes` | int | 重组后总长，`1..64 MiB` | — |
| `checksum` | `{algorithm:'crc32', value}` | value = 8 位小写 hex | — |
| `dataBase64` | string | 标准 base64（**非 url-safe**） | — |

**`rpc-frame-ack`**：`{zcode_type, bridgeSessionId, ackMessageSeq}`（`rpc_transport.dart:173-177`）。

**长度约定三档**（Dart vs 网页 schema，**不一致，见 §5**）：

| 量 | Dart | 网页 schema `Y`（二手） | 网页 schema `hc`（二手） |
|---|---|---|---|
| 物理帧上限 | 512 KiB（发端切片）/ 1 MiB（声明） | `maxPhysicalFrameBytes` = 1 MiB | `maxFrameBytes` = 1 MiB |
| 逻辑消息上限 | 64 MiB | `maxMessageBytes` = 16 MiB | `logicalFrameAssemblyMaxBytes` = 16 MiB |
| 分片数上限 | 256 | `maxFragments` = 64 | `logicalFrameAssemblyMaxFragments` = 1024 |

### 2.3 与 `ipc_codec.dart` 的配合

`ipc_codec.dart` 提供**两件互不相干的东西**：

1. **值编解码**（真正在用）：`ValueWriter/ValueReader/encodeValue/decodeValue`。
   tag：`Undefined=0`、`String=1`、`Buffer=2`、`VSBuffer=3`、`Array=4`、`Object(JSON)=5`、`Int=6`；
   长度/计数用 **7-bit 小端 varint**。编码侧**从不产 tag 2**；解码侧把 tag 2 与 3 同样当字节。
   `Int` 只在 `0..0x7FFFFFFF` 走 tag 6，负数/更大数落到 tag 5（JSON）。上限：
   `maxValueBytes=16 MiB`、`maxContainerItems=100000`。
   调用链：`ChannelClient._sendRequest`（`channel_client.dart:54-60`）→ `rpc_transport.sendMessage`。
2. **13 字节 IPC 帧**（`IpcFraming`/`IpcFrameParser`）：`[type:u8][id:u32be][ack:u32be][bodyLen:u32be]` + body，
   `typeRegular=1`。**在 `lib/` 中零调用点**——只有 `test/ipc_codec_test.dart:141-196` 测它。见 §5-UNKNOWN-1。

---

## 3. 消息目录

> 图例：**→** 客户端发出（本仓方向），**←** 桌面端发出，**↔** 双向。
> 出处列：`文件:行` = Dart 工作树；`Docs N §x` = 姊妹仓文档（二手）；`schema off N` = 网页 schema 偏移（二手）。

### 3.1 relay 顶层 WS 帧（9 条）

| 消息 | 向 | 关键载荷 | 出处 |
|---|---|---|---|
| `auth_init` | → | `role:'terminal'`, `device_sid`, `meta{platform,version,name}`, `client_ts` | `relay_client.dart:160-170` |
| `auth_challenge` | ← | `nonce` | `relay_client.dart:241` |
| `auth_response` | → | `device_sid`, `proof`, `client_ts` | `relay_client.dart:243-252` |
| `auth_ack` | ← | `pair_status` | `relay_client.dart:254` |
| `pair_status_query` | → | `device_sid`, `client_ts`（10s 心跳） | `relay_client.dart:368-372` |
| `pair_status_ack` | ← | `pair_status ∈ {waiting, matched}` | `relay_client.dart:255` |
| `data` | ↔ | `payload`（业务对象或 `rpc-frame`）, `client_ts` | `relay_client.dart:203-207`、`:258-262` |
| `error` | ← | `code`, `message` | `relay_client.dart:264-267` |
| `socket-extensions` | ← | 仅诊断（`perMessageDeflate`） | Docs 16 §2.1（**Dart 不处理**） |

### 3.2 业务 `zcode_type` 全集（19 条）

| # | `zcode_type` | 向 | 必填键（除 zcode_type） | 域 | 出处 |
|---|---|---|---|---|---|
| 1 | `bootstrap-request` | → | `requestId` | 引导 | `zemote_client.dart:219-222`；Docs 16 §4 |
| 2 | `bootstrap-response` | ← | `requestId, success:true, result` | 引导 | `zemote_client.dart:224` |
| 3 | `workspace-list-request` | → | `requestId` | 引导 | `zemote_client.dart:231` |
| 4 | `workspace-list-response` | ← | `requestId, success:true, result` | 引导 | `zemote_client.dart:234-235` |
| 5 | `workspace-list-updated` | ← | `result`（**无 requestId**，推送） | 引导 | `zemote_client.dart:158-160` |
| 6 | `workspace-bridge-open` | → | `requestId, bridgeSessionId, workspaceKey`（+`bridgeGeneration?`,`recoveryId?`,`taskId?`） | 桥 | `zemote_client.dart:274-281` |
| 7 | `workspace-bridge-ready` | ← | `requestId, bridgeSessionId, bridge` | 桥 | `zemote_client.dart:283-285` |
| 8 | `workspace-bridge-error` | ← | `requestId, reason, error` | 桥 | `zemote_client.dart:284`、`:288-290` |
| 9 | `workspace-reconnect-request` | → | `requestId, workspaceKey` | 桥恢复 | `zemote_client.dart:409-413` |
| 10 | `workspace-reconnect-response` | ← | `requestId, workspaceKey, success` | 桥恢复 | `zemote_client.dart:414-418` |
| 11 | `mobile-view-state-update` | → | `viewState{activeWorkspaceKey?, activeTaskId?, updatedAt}`；`deviceInfo?` | 诊断/焦点 | `zemote_client.dart:390-402` |
| 12 | `platform-request` | → | `requestId, method`（7 值）, `args?` | 平台 | Docs 16 §4（**Dart 零实现**） |
| 13 | `platform-response` | ← | `requestId, success, result\|error` | 平台 | 同上 |
| 14 | `bridge-degraded` | ← | `bridgeSessionId, reason`（4 值）+`seq?`,`expectedSeq?`,`droppedCount?` | 桥 | `zemote_client.dart:162-164`、`:249-261` |
| 15 | `app-error` | ← | `reason`（`sg` 11 值）, `error`；`requestId?`,`bridgeSessionId?` | 错误 | Docs 16 §4（**Dart 未处理**，见 §5） |
| 16 | `mobile-diagnostic` | → | `event`（7 值）, `timestamp` + 12 可选键 | 诊断 | Docs 16 §4（**Dart 零实现**） |
| 17 | `rpc-frame` | ↔ | 见 §2.2 | 桥内传输 | `rpc_transport.dart:92-102` |
| 18 | `rpc-frame-ack` | ↔ | `bridgeSessionId, ackMessageSeq` | 桥内传输 | `rpc_transport.dart:173-177` |

> 网页 schema 的判别联合共 **19 值**（`rpc-frame` 与 `rpc-frame-ack` 同列一项），Docs 16 §4 有逐值 off。

**枚举取值**

| 枚举 | 值 | 出处 |
|---|---|---|
| `bridge-degraded.reason` | `rpc-transport-fault`, `rpc-frame-gap`, `buffer-overflow`, `buffer-timeout` | Docs 16 §4 |
| `mobile-diagnostic.event` | `state-transition`, `socket-close`, `socket-error`, `recover-start`, `recover-scheduled`, `pair-status`, `failure` | Docs 16 §4 |
| 失败原因 `sg`（11 值） | `session-not-found`, `session-expired`, `session-conflict`, `workspace-closed`, `desktop-disconnected`, `invalid-mobile-connection`, `desktop-bootstrap-timeout`, `connection-recovery-timeout`, `relay-unavailable`, `unsupported-action`, `unexpected-error` | Docs 14 §7.5 |
| `platform-request.method`（7 值） | `isDockerAvailable`, `listWSLDistros`, `listDockerContainers`, `listSSHConfigAliases`, `loadMcpFromUserDirectory`, `saveMcpToUserDirectory`, `migrateLegacyCommonMcp` | Docs 14 §7.5 |

### 3.3 relay 关闭码与错误码

| 码 | 含义 | 出处 |
|---|---|---|
| WS close 4004 | `session-not-found` | `relay_client.dart:35-36` |
| 4009 | `session-conflict` | `:37-38` |
| 4010 | `desktop-disconnected` | `:39-40` |
| 4011 | `session-expired` | `:41-42` |
| 4012 | `workspace-closed` | `:43-44` |
| 4013 | `invalid-mobile-connection` | `:45-46` |
| `error.code = KICKED` | 终态（不再重连）；但若在 `authenticating` 或文案含 conflict/duplicate/already connected/another connection → 先试一次重连 | `relay_client.dart:307-327` |
| `error.code` 其他 | Dart **只认 KICKED**；网页还认 `DEVICE_OFFLINE`(可恢复) / `AUTH_FAILED` / `WRONG_PARAM` / `INTERNAL` | Docs 16 §7.1（Dart 缺口，见 §5） |

### 3.4 ChannelClient 协议与通道名（33 条）

**请求/响应标签**（`channel_client.dart:12-21`）：

| 常量 | 值 | 向 | 含义 |
|---|---|---|---|
| `reqPromise` | 100 | → | 方法调用 |
| `reqPromiseCancel` | 101 | → | 取消（Dart 定义未用） |
| `reqEventListen` | 102 | → | 事件订阅 |
| `reqEventDispose` | 103 | → | 事件退订 |
| `resInitialize` | 200 | ← | 通道就绪（`[200, 0]`） |
| `resPromiseSuccess` | 201 | ← | 调用成功 |
| `resPromiseError` | 202 | ← | 调用失败（`{message}`） |
| `resPromiseErrorObj` | 203 | ← | 调用失败（对象） |
| `resEventFire` | 204 | ← | 事件触发 |

**帧形状**：请求头 `[reqType, reqId, channel, name]` + 参数值；响应头 `[respType, reqId]` + 数据值。
`reqId` 从 0 自增（`channel_client.dart:73`）。初始化超时 30s。

**33 个通道名**（`channel_client.dart:143-177`）：

| 通道 | Dart 是否已用 | 通道 | Dart 是否已用 |
|---|---|---|---|
| `file` | ✗ | `plugin-management` | ✗ |
| `system` | ✗ | `subagents` | ✗ |
| `terminal` | ✗ | `commands` | ✓ `list` |
| `git` | ✗ | `hooks` | ✗ |
| `git-checkpoint` | ✗ | `memory` | ✗ |
| `setting` | ✗ | `output-style` | ✗ |
| `credential` | ✗ | `settings-sync` | ✗ |
| `broadcast` | ✗ | `bots` | ✗ |
| `zcode-task` | ✓ | `feedback` | ✗ |
| `zcode-agent` | ✓ | `repo-wiki` | ✗ |
| `zcode-session` | ✗ | `prompt-attachment-transfer` | ✗ |
| `file-watcher` | ✗ | `off-peak-task` | ✗ |
| `oauth` | ✗ | `skills` | ✓ `list` |
| `model-provider` | ✓ | `skill-sync` | ✗ |
| `usage-stats` | ✓ | `mcp-sync` | ✗ |
| `coding-plan-subscription` | ✗ | `plugin-sync` | ✗ |
| `plugins` | ✗ | | |

### 3.5 Channel RPC 方法名（按功能域）

#### 3.5.1 任务域 —— 通道 `zcode-task`（12 条）

| 方法 | 参数关键字段 | 出处 |
|---|---|---|
| `listTasks` | `{workspacePath, workspaceIdentity?}` | `lib/ui/task_home_page.dart:317`；`test/session_list_probe_test.dart:44-49` |
| `listPinnedTasks` | 同上 | `lib/ui/task_home_page.dart:321` |
| `listArchivedTasks` | 同上 | `lib/ui/task_home_page.dart:325` |
| `setTaskUnread` | `{...scope, taskId, unread, expectedUnreadAt}`（`unreadAt` 是 CAS 令牌） | `lib/ui/task_home_page.dart:381`；Docs 17 §2.4 |
| `setTaskPinned` | `{...scope, taskId, pinned}` | `lib/ui/task_home_page.dart:431` |
| `archiveTask` | `{...scope, taskId}` | `lib/ui/task_home_page.dart:441` |
| `unarchiveTask` | 同上 | `lib/ui/task_home_page.dart:460` |
| `renameTask` | `{...scope, taskId, title}` | `lib/ui/task_home_page.dart:499` |
| `deleteTask` | `{...scope, taskId}` | `lib/ui/task_home_page.dart:528` |
| `getTaskSnapshotWithEtag` | `{...scope, taskId}`（90s 超时） | `lib/ui/task_detail_page.dart:42-52` |
| `getTaskTokenUsage` | `{...scope, taskId}` | `lib/ui/chat_page.dart:4071-4078` |
| `prepareWorkspace` | `scope` → `{configOptions[], slashCommands[]}` | `conversation.dart:680-691` |

#### 3.5.2 对话 V4 —— 通道 `zcode-agent`（非命令类，9 条）

| 方法 | 关键参数 | 出处 |
|---|---|---|
| `helloConversationV4` | 无 → `{connectionId}` | `conversation.dart:69` |
| `initializeConversationV4` | `{kind:'clientHello', protocolVersion:3, clientId, clientKind:'mobileApp', appVersion}` | `conversation.dart:74-82` |
| `subscribeConversationV4` | `{...scope, sessionId}` → `{ack:{subscriptionId, logEpoch}}` | `conversation.dart:1005` |
| `unsubscribeConversationV4` | `{...scope, subscriptionId}` | `conversation.dart:1007` |
| `resyncConversationV4` | `{...scope, subscriptionId, forceSnapshot:true, base:{logEpoch, seq}?}` | `conversation.dart:1009`、`:943-968` |
| `sendConversationCommandV4` | `{...scope, envelope}` | `conversation.dart:219-224` |
| `conversationPlansV4` | `{...scope, sessionId}` | `conversation.dart:462-464` |
| `conversationFileChangesV4` | `{...scope, sessionId, target, baseRevision?, baseLogEpoch?}` | `conversation.dart:474-482` |
| `conversationFileRewindPreviewV4` | 同上 | `conversation.dart:492-500` |
| `conversationRowsRangeV4` | `{...scope, sessionId, beforeRowId?, limit=60}` | `conversation.dart:647-654` |

#### 3.5.3 会话索引 —— 通道 `zcode-agent`（3 + 1 事件）

| 方法/事件 | 关键参数 | 出处 |
|---|---|---|
| `subscribeSessionsIndexV4` | `{...scope, runtimePolicy:'existing-only'}` → `{ack:{subscriptionId}}` | `conversation.dart:1289`、`:1295-1296` |
| `unsubscribeSessionsIndexV4` | `{...scope, subscriptionId, runtimePolicy:'existing-only'}` | `conversation.dart:1291`、`:1298-1299` |
| `resyncSessionsIndexV4` | `{...scope, subscriptionId, runtimePolicy:'existing-only', base?}` | `conversation.dart:1293`、`:1301-1302` |
| `onDynamicSessionsIndexFrame`（事件） | arg = scope | `conversation.dart:1287`；`zcode-protocol.js` 同名字符串 |
| `onDynamicConversationFrame`（事件） | arg = scope | `conversation.dart:1003` |

#### 3.5.4 对话命令（envelope `type`，22 条）

| 命令 | 需 `baseRevision` | 需 `baseLogEpoch` | payload 关键字段 | 出处 |
|---|---|---|---|---|
| `createSession` | ✗（sessionId=null） | ✗ | `{workspaceId, firstInput{text, attachments?}, config?, runtimeModel?, mcpServers?}` | `conversation.dart:260-277` |
| `createSelectionSideSession` | ✗ | ✗ | `{}`（挂在 parentSessionId 上） | `conversation.dart:299-304` |
| `sendText` | ✗ | ✗ | `{text, attachments?, heldQueueDisposition?, expectedHeldQueueItemIds?, automationId?, offPeakTaskId?, offPeakRunType?, botDeliveryTarget?, toolDisallowlist?}` | `conversation.dart:332-347` |
| `sendGoalCommand` | ✗ | ✗ | `{text, displayText?, heldQueueDisposition?, expectedHeldQueueItemIds?}` | `conversation.dart:356-364` |
| `stop` | ✗ | ✗ | `{}` | `conversation.dart:372` |
| `compact` | ✗ | ✗ | `{}` | `conversation.dart:374-375` |
| `resolveInteraction` | ✗ | ✗ | `{interactionId, answer{optionId?, freeText?, action?, content?}}` | `conversation.dart:631-639` |
| `switchModelConfig` | ✓ | ✗ | `{provider, model, thought}`（三者皆必填） | `conversation.dart:388-392`；`conversation.dart:122` |
| `switchCollaborationMode` | ✓ | ✗ | `{mode}`（build/edit/plan/yolo） | `conversation.dart:408-409` |
| `setFollowupMode` | ✓ | ✗ | `{mode}`（queue/guide） | `conversation.dart:412-413` |
| `setAutoDrain` | ✓ | ✗ | `{autoDrain}` | `conversation.dart:441-442` |
| `sendQueuedNow` | ✓ | ✗ | `{queueItemId}` | `conversation.dart:430-431` |
| `editQueueItem` | ✓ | ✗ | `{queueItemId, newText}` | `conversation.dart:433-436` |
| `deleteQueueItem` | ✓ | ✗ | `{queueItemId}` | `conversation.dart:438-439` |
| `reorderQueueItem` | ✓ | ✗ | UNKNOWN（在 CAS 集合内但无 Dart 封装） | `conversation.dart:122` |
| `pauseGoal` | ✓ | ✗ | `{}` | `conversation.dart:366-367` |
| `resumeGoal` | ✓ | ✗ | `{}` | `conversation.dart:369-370` |
| `retryTurn` | ✓ | ✓ | `{target}` | `conversation.dart:427-428` |
| `forkAssistant` | ✓ | ✓ | `{target}` | `conversation.dart:444-446` |
| `editUserQuery` | ✓ | ✓ | `{target, newText}` | `conversation.dart:448-454` |
| `applyFileRewind` | ✓ | ✓ | `{target}` | `conversation.dart:456-458` |
| `setAssistantFeedback` | ✓ | ✓ | `{target:{rowId, entityId?}, feedback: like\|dislike\|null}` | `conversation.dart:417-425` |

**CAS 规则**（`conversation.dart:111-134`、`:156-209`）：`baseRevision` 取 `max(订阅 state.revision, 已 ack 的 revisionAtDecision)`；
ack `status ∈ {accepted, noop, duplicate}` 时下一次基线为 `revisionAtDecision + 1`；`status=='stale'` 时用
`revisionAtDecision` 重试一次（换新 commandId）。

#### 3.5.5 附件 V4 —— 通道 `zcode-agent`（4 条）

| 方法 | 关键字段 | 出处 |
|---|---|---|
| `attachmentBeginV4` | `{...scope, connectionId, uploadId, sessionId, fileName, mime, totalBytes, totalChunks, checksum:'sha256:<hex>'}` → `{state, ref?, nextChunkIndex?}` | `conversation.dart:532-542` |
| `attachmentChunkV4` | `{..., chunkIndex, dataBase64}`（384 KiB/片）→ `{nextChunkIndex}` | `conversation.dart:560-567` |
| `attachmentCommitV4` | `{...scope, connectionId, uploadId, sessionId}` → `{ref}` | `conversation.dart:577-579` |
| `attachmentReadV4` | `{...scope, sessionId, ref, offset, limit}` → `{dataBase64, mediaType, nextOffset, totalBytes}` | `conversation.dart:599-607` |

#### 3.5.6 定时任务 —— 通道 `zcode-agent`（6 条）

| 方法 | 关键字段 | 出处 |
|---|---|---|
| `listAllAutomations` | `{workspacePath, workspaceIdentity?}` | `automation_client.dart:94-96` |
| `createAutomation` | `{...scope, title, prompt, cronExpr, model, provider, mode, thoughtLevel, recurring, scheduleRule, enabled}` | `automation_client.dart:119-133` |
| `setAutomationEnabled` | `{...scope, automationId, enabled}` | `automation_client.dart:140-142` |
| `deleteAutomation` | `{...scope, automationId}` | `automation_client.dart:147-149` |
| `runAutomationNow` | 同上 | `automation_client.dart:154-156` |
| `restartAutomation` | 同上 | `automation_client.dart:161-163` |

> `scheduleRule` 形状：`{unit:'minute'|'hour'|'day'|'week', interval, hour?, minute?, weekday?|dayOfWeek?}`（`automation_client.dart:46-74`）。
> 测试里还试过 `updateAutomation`/`removeAutomation` 作为回退名（`test/automation_roundtrip_test.dart:117`、`:141`），
> 说明这些名字**未被证实**（见 §5）。

#### 3.5.7 其他通道方法（5 条）

| 通道 | 方法 | 出处 |
|---|---|---|
| `skills` | `list` → `{...scope, provider:'glm'}` | `conversation.dart:697-716` |
| `commands` | `list` | `lib/ui/services_page.dart:277` |
| `model-provider` | `getAll` / `save` / `delete` | `lib/ui/model_providers_page.dart:36`、`:59`、`:96` |
| `usage-stats` | `getEntitlementSnapshot` → `{includeSubscription:true}` | `lib/ui/usage_page.dart:34-40` |
| `zcode-agent` | `listPlugins` | `lib/ui/services_page.dart:60-64` |

### 3.6 对话帧流（wire frame）与状态增量

**订阅帧封套**（`conversation.dart:16-24` 注释 + `:883-941` 实现）：

| 层 | 形状 | 出处 |
|---|---|---|
| wire frame | `{wireVersion:3, kind:'complete'\|'fragment', topic, subscriptionId, frame \| fragment*}` | `conversation.dart:887-897` |
| `kind:'fragment'` | `{logicalFrameId, fragmentIndex, fragmentCount(1..64), dataBase64}`（重组后是 JSON 文本） | `conversation.dart:908-941` |
| 逻辑帧 | `{topic, subscriptionId, fromSeq, toSeq, sentAt, payload}` | `conversation.dart:23-24`、`:1049-1050` |
| `payload.kind='snapshot'` | `{snapshot}` | `conversation.dart:1241-1255`、`:1349-1351` |
| `payload.kind='deltas'` | `{deltas:[…]}`；`fromSeq != 本地 seq` ⇒ `onGap()` → `resync` | `conversation.dart:1256-1276`、`:1352-1365` |

**topic 命名**（Dart 侧）：`conversation/<sessionId>`（`conversation.dart:1017`）、
`sessions-index/<workspaceIdentity ?? workspacePath>`（`conversation.dart:1304-1305`）。
网页/壳侧另有 `controller/tasks-index`、`controller/workspaces`（`zcode-protocol.js:91`；Docs 17 §7.3）。

**对话 delta `op`（5 条）**：`row.appended`、`row.upserted`、`row.removed`（保留 `rowId < fromRowId`）、
`row.delta`（`path`+`append`）、`state.updated`（`patch` 合并进 snapshot；早到则缓冲 `_pendingPatch`）。
出处：`conversation.dart:1408-1466`；Docs 18 同名列。

**`row.delta.path` 取值（4 条）**：`text`（仅 `assistantText`/`reasoning`）、`inputText`（仅 `toolCall`）、
`output.text`（仅 `toolCall`）、`summaryText`（仅 `subagent`）。出处 `conversation.dart:1503-1536`。

**会话索引 delta `op`（2 条）**：`session.upserted`（`{session}`）、`session.removed`（`{sessionId}`）。
出处 `conversation.dart:1266-1272`。

**snapshot 关键字段**（`conversation.dart:1370-1406`、`:1538-1627`）：

| 字段 | 含义 | 出处 |
|---|---|---|
| `snapshot.rows.{window[], totalCount, firstRowId}` | 行窗口（尾部 60 行量级） | `:1379-1400` |
| `snapshot.revision` | CAS 基线 | `:1542` |
| `snapshot.logEpoch` | 行目标命令的 CAS 基线 | `:1377`、`:1309` |
| `snapshot.control.{phase, canStop}` | 运行态；`isRunning = phase ∈ {running, prewarming}` | `:1544-1548` |
| `snapshot.config.{provider, model, thought, thoughtLevels[], followupMode, mode}` | 会话配置 | `:1552-1560` |
| `snapshot.queue.{items[], autoDrain}` | 挂起队列 | `:1563-1575` |
| `snapshot.usage.{contextWindow{usedTokens,maxTokens}, cumulative}` | 用量 | `:1578-1579` |
| `snapshot.backgroundWorks[]` / `goal` / `plan` | 后台作业 / 目标 / 计划 | `:1607-1617` |
| `snapshot.inputRouting.mode` | `startNow\|enqueue\|guide\|reject\|choice` | `:1619-1621` |
| `snapshot.pendingInteractions[]` | 待答交互 | `:1623-1627` |
| `SessionEntry`（会话索引） | `{sessionId, parentSessionId?, title, phase, lastAssistantPreview?, lastActivityAt, createdAt, hasBackgroundWork, pendingInteraction?}` | `conversation.dart:1197-1220` |

**行 `kind` 取值**（出现于 `conversation.dart` + 全仓 Dart）：`assistantText`、`reasoning`、`toolCall`、
`subagent`、`turnHeader`、`userInput`、`plan`、`goal`、`error`、`timelineMarker`（Docs 18 同列）。

### 3.7 超时与节律常量

| 常量 | 值 | 出处 |
|---|---|---|
| relay 心跳间隔 | 10s | `relay_client.dart:58` |
| 心跳 ack 超时 | 30s | `relay_client.dart:59` |
| `waiting` 超时（未配对） | 30s → `invalid-mobile-connection` | `relay_client.dart:60`、`:405-413` |
| 已配对后重连却停在 `waiting` | 20s → 强制重连 | `relay_client.dart:61`、`:282-288` |
| 死链阈值（poke 判定） | 25s | `relay_client.dart:62`、`:381-384` |
| 重连退避 | `1s * 2^attempt`，clamp 到 `[1s, 15s]` | `relay_client.dart:424-425` |
| rpc 组装清理周期 / 过期 | 30s / 60s | `rpc_transport.dart:51`、`:58` |
| 逻辑帧分片清理周期 / 过期 | 30s / 60s | `conversation.dart:742-743`、`:794-795` |
| 对话 watchdog | 每 10s 检查，静默 ≥20s 且 active ⇒ resync | `conversation.dart:1062-1075` |
| 订阅超时 / channel 默认超时 | 60s / 30s | `conversation.dart:821`、`channel_client.dart:66` |
| 桥健康等待 | 45s | `zemote_client.dart:451` |
| 附件分片 | 384 KiB | `conversation.dart:505` |

---

## 4. relay 通道

### 4.1 `relay_client.dart` 与网页端 relay 的关系

`relay_client.dart` 是网页端 relay 终端客户端（网页类名 `pen` / `X2t`）的**逐行为复刻**，
连接对象与消息形状完全一致，差别只在自称与工程加固：

| 维度 | 网页端（Docs 16 §2） | `relay_client.dart` |
|---|---|---|
| 入口 | `/remote/v4?sid&hash&mid` | 同（`connection_params.dart`） |
| socket | `wss://<host>/ws` | 同 |
| 自称 | `meta:{platform:'web', version:appVersion??'web', name:'mobile-browser'}` | `name:'zemote'`、`platform` = 真实平台（`device_info.dart`） |
| 心跳 | 10s **带 jitter** | 10s 固定，`waiting` 态隔拍发 |
| 出站排队 | — | 未配对时排队（上限 100），`matched` 后冲刷（`relay_client.dart:189-222`） |
| 重连 | `reconnectAfterStaleWaiting` + `reconnectAttempt` | 指数退避 + `_rewaitTimer` 兜底 |
| `error.code` | 5 种分派（§7.1） | 只认 `KICKED` |
| 帧日志 | 有 trace 形状表 `Y2t` | `verboseFrames` 默认关（避免泄漏载荷） |
| 诊断上报 | 发 `mobile-diagnostic` | **不发** |

### 4.2 两条承载路径（是否存在差异：**是，且是架构级差异**）

Docs 13 §1 把壳侧的三条数据源分开列；对本仓移植而言关键的是前两条：

| | **① 原生直连 relay** | **② 网页承载（页面被动转发）** |
|---|---|---|
| 谁拨 WS | 原生自己拨 relay WS | WebView 里的页面自己拨，原生只是旁听 |
| 实现 | `Tier2Probe.kt` + `RelayBridge.kt` + `RelayWire.kt`（3,036 行） | `assets/zcode-protocol.js`（89 KB）+ `assets/inject.js`（190 KB） |
| 形态 | 完整 relay 编解码（业务消息 + rpc-frame + ChannelClient） | hook 页面的 socket，读**页面已解码**的形状（`sessions`/`controllertasks`/`status`） |
| 协议写权限 | 有（可自行开桥、订阅、发命令） | 被动只读；`inject.js` 里只做诊断/合成（如 `relayDegradeTest` 合成 `bridge-degraded`） |
| 本仓 `rust/crates/protocol/` 对应 | **是**——`relay_client.dart` 等全部属于这条 | 否（`zcode-protocol.js` 是同协议的第二实现，可作对照） |

**结论**：`relay_client.dart` / `zemote_client.dart` / `rpc_transport.dart` / `channel_client.dart`
描述的是**路径①**；路径②没有 Dart 对应物，它的价值是**独立复现了同一套 wire 约定**
（`zcode-protocol.js` 头部注释逐条列出 `{type:'data'}` 封套、`rpc-frame` 分片、
ChannelClient 值流，并明确「no extra IPC header layer」——与 `zemote_client.dart:328-329` 互相印证）。
移植时若对某个字节级决策不确定，`zcode-protocol.js` 是第二份可交叉验证的实现。

**第三条通道（token/window）本仓不涉及**：`remoteControlToken` + `relayOrigin` →
`/ws/remote-control/window/{token}` + REST（`POST .../platform/{token}` 等）。业务消息类型系统与 relay 共用
（Docs 16 §1、§9）。Dart 侧零实现，壳侧也不用。

---

## 5. 未解析 / 不确定清单

> 这一节比正文更重要：**明确不知道**比假装完整有用。

### 5.1 结构性缺口（Dart 侧零实现或零处理）

| # | 项 | 现状 | 影响 / 下一步 |
|---|---|---|---|
| U1 | **13 字节 IPC 帧**（`IpcFraming`/`IpcFrameParser`） | 定义在 `ipc_codec.dart:135-177`，`lib/` 内**零调用点**，只有 `test/ipc_codec_test.dart` 测它 | `UNKNOWN`：它属于哪条路径？桥路径明确不用（`zemote_client.dart:328-329`）。可能属"原生 desktop IPC"（非 relay）。移植前必须判明，否则会白写 |
| U2 | `platform-request/response` | Dart 零实现（Docs 16 §4 列 7 个 method） | 平台能力（Docker/WSL/SSH/MCP 目录）在移动端是否需要？UNKNOWN |
| U3 | `mobile-diagnostic` | Dart 零实现（不发） | 网页/壳都发；不发是否影响桌面端判定本客户端健康？UNKNOWN |
| U4 | `app-error` | `zemote_client._dispatchPayload` **未匹配**它 | 桌面端应用级错误（11 个 `sg` 值，含可重试的 `desktop-bootstrap-timeout`）在本仓不可见——失败只表现为超时。Docs 17 §2.7 记为同一缺口 |
| U5 | `reqPromiseCancel`(101) | 常量已定义，Dart 无调用 | 取消语义 UNKNOWN（是否必须发、如何配对 reqId） |
| U6 | `bridge.kind='remote'` 开桥 | `openBridge` 只传 `workspaceKey`；remote 桥 schema 要求 `workspaceIdentity` + `remoteSessionId`（Docs 14 §7.5） | 远程工作区能否开桥 UNKNOWN；`bootstrap-response.result.workspaces[].remoteSessionId` 可能是凭据 |
| U7 | `deviceInfo` 12 键 | Dart 只发 `{platform, version, name}`；schema `ug` 的 `updatedAt` 是**必填** | 按 Docs 17 §4② 推断整帧会被 `safeParse` 丢弃——**未在真机证实**。`UNKNOWN` |
| U8 | `error.code` 分派 | Dart 只认 `KICKED` | `DEVICE_OFFLINE`（可恢复）/`AUTH_FAILED`/`WRONG_PARAM`/`INTERNAL` 在 Dart 会落到"整条连接失败"而非对应恢复路径 |
| U9 | 关闭码覆盖 | 只映射 4004/4009–4013 | 4000–4003、4005–4008、4014+ 的语义 UNKNOWN，一律降级为 `relay-unavailable` |

### 5.2 数值/约定不一致（Dart ↔ 网页 schema）

| # | 项 | Dart | 网页 schema（二手） | 判断 |
|---|---|---|---|---|
| U10 | 物理帧上限 | 发端切 512 KiB；声明 1 MiB | `maxPhysicalFrameBytes`/`maxFrameBytes` = 1 MiB | Dart 更保守，**发端不会超限**；但 `_fragmentPayloadBytes` 是私有的，移植时必须照抄 512 KiB 还是抬到 1 MiB？`UNKNOWN`。`zcode-protocol.js:46-56` 的注释说 Kotlin 侧**已抬到 1 MiB / 1024 分片**（`MAX_FRAGMENT_BYTES`/`MAX_FRAGMENTS` 在 `:55-56`），与 Dart 不一致 |
| U11 | 逻辑消息上限 | 64 MiB | 16 MiB | Dart 更宽松。收端接受 64 MiB 会不会收到对方永不发的尺寸？`UNKNOWN`（`rpc_transport.dart:16-20` 注释说抬到 64 MiB 是为修 issue #9） |
| U12 | 分片数上限 | 256 | `maxFragments`=64（物理）/`logicalFrameAssemblyMaxFragments`=1024（逻辑） | 两处"分片"含义不同：Dart 的 256 是物理帧；对话逻辑帧 Dart 用 64（`conversation.dart:916`）而网页是 1024。**不一致，须确认** |
| U13 | `rowsRange` limit | 默认 60（`conversation.dart:644`） | `snapshotTailWindowRows:60`、`rowsRangeMaxLimit:200`（Docs 14 §1.2） | 60 是尾窗还是 rowsRange 上限？`UNKNOWN` |
| U14 | 心跳 jitter | 无 | 有（`withJitter(heartbeatIntervalMs)`） | 无 jitter 是否会造成多端同拍？低风险，照抄 Dart |

### 5.3 字段/参数语义未明

| # | 项 | 说明 |
|---|---|---|
| U15 | `subscribeConversationV4` 的 `base?` / `visibility?` | Docs 16 §6 列出网页会发这两个参数；Dart 只发 `{...scope, sessionId}`。语义 `UNKNOWN` |
| U16 | `initializeConversationV4` 的 `capabilities` | 网页发 `capabilities:{workspaceHookReviewUi:true}` 且 `clientKind` 为 `web`/`desktop`；Dart 发 `clientKind:'mobileApp'` 且**不发 capabilities**。`mobileApp` 是否被服务端接受、缺 capabilities 的降级行为 `UNKNOWN` |
| U17 | `resync*` 的 `base:{logEpoch, seq}` | Dart 按实现拼出这个形状，网页侧对应物只在 Docs 16 §6 一句提及。权威语义 `UNKNOWN` |
| U18 | `runtimePolicy:'existing-only'` | 会话索引三个方法都带它，Dart 无注释。`UNKNOWN` |
| U19 | `recoveryId` 来源 | Dart 重开桥时回带**旧 bridge 的** `recoveryId`；首次开桥不带。谁生成、桌面端如何用 `UNKNOWN` |
| U20 | `bridgeGeneration` 语义 | Dart 本地单调自增；是否必须与桌面端一致 `UNKNOWN` |
| U21 | `attachment` descriptor `{ref, fileName, mime, bytes}` | 形状是 Dart **自造**（`conversation.dart:546-550`），Docs 未覆盖。服务端是否接受 `UNKNOWN` |
| U22 | `sendText` 的 `botDeliveryTarget` / `toolDisallowlist` / `offPeakTaskId` / `offPeakRunType` | Dart 直传，无注释、无文档。`UNKNOWN` |
| U23 | `heldQueueDisposition` / `expectedHeldQueueItemIds` | 同上；看起来是队列 CAS，`UNKNOWN` |
| U24 | `setAssistantFeedback.feedback` 的 `null` | Dart 传 `null` 表示清除；服务端是否接受 `UNKNOWN` |
| U25 | `topic` 的 scope 部分 | Dart 用 `workspaceIdentity ?? workspacePath` 拼 `sessions-index/…`；网页 `controller/tasks-index` 等前缀说明还有别的 topic 家族。是否有更多 topic `UNKNOWN` |
| U26 | `wireVersion:3` 的取值边界 | 只见过 3；`UNKNOWN`（Dart 也不校验） |
| U27 | `automation` 回退方法名 | `updateAutomation`（`test/automation_roundtrip_test.dart:91`）/ `removeAutomation`（`:116`）只在探测测试里试过，**未证实存在** |
| U28 | 其余 27 个通道的方法名 | `broadcast` / `git-checkpoint` / `file-watcher` / `plugin-management` / `hooks` / `output-style` / `settings-sync` / `skill-sync` / `mcp-sync` / `plugin-sync` / `coding-plan-subscription` / `prompt-attachment-transfer` / `bots` / `feedback` / `memory` / `subagents` / `terminal` / `git` / `file` / `system` / `setting` / `credential` / `oauth` / `repo-wiki` / `off-peak-task` / `plugins` / `zcode-session` 的可用方法**全部 `UNKNOWN`**（Dart 只有通道名常量） |
| U29 | `bootstrap-response.result` 顶层是否还有第 6 键 | schema 非 strict；Docs 17 §6④ 登记为盲区。`UNKNOWN` |
| U30 | `workspace-list-updated` 是否推给非页面客户端 | 桌面端推送范围未知（Docs 17 §6③）。`UNKNOWN` |

### 5.4 需要真机/抓包才能定的事（本仓无样本）

- U31 `tasks[]` 逐任务是否带 `workspaceIdentity`/`remoteSessionId`/`unreadAt`/`archived`（Docs 17 §6①）。
- U32 `result.workspaces[]` 与 `initialViewState`/`mobileViewState` 的真机形状（Docs 17 §6②）。
- U33 `ValueWriter` 从不产 tag 2（`Buffer`）——线上是否出现 tag 2 `UNKNOWN`（`ipc_codec.dart:107-113` 能解）。
- U34 tag 6 `Int` 只覆盖 `0..0x7FFFFFFF`；超出走 tag 5 JSON。服务端是否接受 JSON 数字回填整型字段 `UNKNOWN`（`test/ipc_codec_test.dart:132-138` 记录了这个行为）。
- U35 `data` 封套的 `server_ts` Dart 不读；是否有用 `UNKNOWN`。

---

## 6. 下一批移植的关键文件（按依赖顺序）

1. **`relay_client.dart`（471 行）** —— 最独立、测试最完整（`test/rpc_transport_test.dart` 之外的行为都在这里）。
   状态机 + 心跳 + 重连 + 出站排队是"把协议跑起来"的地基；`RelayState`/`RelayFailure`/`relayCloseReason`
   是纯逻辑，可先落成 `fn step(&mut self, input) -> Vec<Event>` 式状态机。
2. **`rpc_transport.dart` + `crc32.dart`（218 + 27 行）** —— 字节级零漂移的硬骨头：512 KiB 切片、
   `messageSeq` 聚合、乱序重组、crc32 十六进制小写 8 位、ack 三元组。`test/rpc_transport_test.dart`
   有 14 条用例可直接搬成 Rust 测试（含"尺寸不符不发""checksum 不符不发""乱序重组""ack 携带身份"）。
3. **`channel_client.dart` + `ipc_codec.dart`（177 + 177 行）** —— 值编解码是**所有**业务调用的公共路径，
   tag/varint 错一个字节全线不通。`test/ipc_codec_test.dart`（20 条）+ `test/channel_client_test.dart`（6 条）
   覆盖面好。**注意 U1**：13 字节 IPC 帧是否要移植，先判明。

`automation_client.dart`（165 行）依赖 `channel_client` 之上，属纯参数拼装，最后做。

---

## 7. 出处与复现

| 你要什么 | 去哪 |
|---|---|
| 移植契约、类型映射、硬性要求 | `rust/crates/protocol/PORTING.md` |
| relay 顶层帧 / 19 个 `zcode_type` / 分片 schema / 错误分派 | `E:\Zcode_harmony\Docs\16-remote-v4-relay协议目录.md` |
| bootstrap 逐字段表、枚举全量值、字符偏移 | `E:\Zcode_harmony\Docs\14-remote-v4-bootstrap载荷-完整字段表.md` |
| bootstrap 合并总表 + 未解析字段清单 + 盲区 | `E:\Zcode_harmony\Docs\17-remote-v4-bootstrap载荷-合并总表与未解析字段清单.md` |
| 壳侧三条数据源 / 网页承载路径 | `E:\Zcode_harmony\Docs\13-remote-v4-bootstrap载荷-我们解析了哪些.md` |
| 网页架构与模块地图 | `E:\Zcode_harmony\Docs\15-remote-v4-网页架构与模块地图.md` |
| 第二份 wire 实现（可交叉验证字节决策） | `E:\Zcode_harmony\android-shell\app\src\main\assets\zcode-protocol.js` |
| 页面 socket hook / 诊断合成 | `E:\Zcode_harmony\android-shell\app\src\main\assets\inject.js` |

**复现命令**（只读）

```bash
# Dart 侧协议面全量
ls Reference_code/lib/protocol/ && wc -l Reference_code/lib/protocol/*.dart
# 所有 channel RPC 调用点（含 protocol/ 之外）
grep -rn "channels.call(\|\.call(Channels\." Reference_code/lib --include=*.dart
# 13 字节 IPC 帧是否真的没人用（当前结论：lib/ 零调用）
grep -rn "IpcFraming\|IpcFrameParser" Reference_code/lib Reference_code/test
# 第二份实现里的 topic / 消息名
grep -oE "'[a-zA-Z]+V4'|'onDynamic[A-Za-z]*'|'controller/[a-z-]*'" \
  E:/Zcode_harmony/android-shell/app/src/main/assets/zcode-protocol.js | sort -u
```
