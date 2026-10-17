# 04 · 桌面端 relay 连接与心跳（对端权威）

> **审计基线**：ZCode 桌面端 3.15.3（app.asar 340,516,766 B，build `d7ca8bee`，2026-10-13）
> 主要坐标：`out/main/index.js`、`out/main/chunk-OW6EUS2G.js`、`out/main/chunk-JS2T33SG.js`
> 交叉基线：网页端 3.15.3（`docs/remote-v4/16-remote-v4-relay协议目录.md`，2026-10-15 快照）
> 偏移口径：**UTF-8 字节偏移**（Node `Buffer.subarray`）。本地只读副本
> `docs/_audit/desktop-extract/`（`main-index.js` = `out/main/index.js`）。
> **只读逆向，未运行、未修改任何包内代码。**

---

## 0. 一句话

桌面端在 relay 上是一个 **`WebRemoteControlDeviceTransport` 类（压缩名 `fa`，`out/main/index.js` @378,577）**：
`wss://zcode.z.ai/ws?mid=<deviceMid>` + HTTP 头 `X-Device-ID` + `permessage-deflate`，
以 **`role:"device"`** 走 `auth_init → auth_challenge → auth_response → auth_ack/pair_status_ack`，
心跳 `pair_status_query` **10 000 ± 2 000 ms**、ack 看门狗 **30 000 ms**，
重连是**固定 1 000 ms 定时器（没有指数退避）**，**不读 WebSocket 关闭码**，
**没有 `DEVICE_OFFLINE` 分支**（全包 21,920 个文件 0 命中），
**永远不会进入 `kicked` 状态**（`setState("kicked")` 出现 0 次）。
与网页端的差异集中在：多了 `device_register_*` 注册握手、状态名 `waiting_terminal`、
`staleWaitingCount` 是**活代码**（网页端是死代码）、重连不做指数退避。

---

## 1. 连接与握手

### 1.1 WS URL 形态（**逐条复核网页端结论**）

| 网页端结论 | 桌面端事实 | 判定 |
| --- | --- | --- |
| URL = `wss://zcode.z.ai/ws` + `?mid=<deviceMid>` | **一致**。`let t=new URL(this.options.relayWsUrl); t.searchParams.set("mid",this.options.deviceMid);`（`main-index.js` @**380,303**） | ✅ 一致 |
| `sid` / `hash` **不在** WS URL 里 | **一致**。WS URL 只加 `mid`；`device_sid` 只出现在 `auth_init` / `auth_response` / `pair_status_query` 帧里 | ✅ 一致 |
| 口令走 HMAC proof，不在 URL | **一致**。`pass_hash` 只在 `device_register_init.pass_hash` 与 proof 计算中使用 | ✅ 一致 |
| 网页端取串 `Rv()`（`validation` @213,995） | 桌面端同源函数 `resolveWebRemoteControlRelayWsUrl`（`chunk-OW6EUS2G.js` @**3,382**，导出名 `fb` → `main-index.js` 里的 `zc`）：<br>`function WL(t){let n=t?.overrideUrl?.trim();return n\|\|(t?.endpointOrigin?.trim()===Os?"wss://zcode.chatglm.site/ws":aI)}`，其中 `aI="wss://zcode.z.ai/ws"`（@**1,273**）、`Os="https://zcode.chatglm.site"`（@980） | ✅ 同构 |
| —— | 桌面端**多两样**：① HTTP 头 `headers:{"X-Device-ID":this.options.deviceMid}`（@**380,439**）；② `perMessageDeflate:!0`（@**380,418**） | ➕ 桌面端独有 |

**桌面端的取串链**（`main-index.js` @**757,573** 起）：

```js
ZE = process.env.ZCODE_WEB_REMOTE_CONTROL_RELAY_WS_URL?.trim(),   // 环境变量覆盖
JE = process.env.ZCODE_WEB_REMOTE_CONTROL_URL?.trim();
...
relayWsUrl: zc({ endpointOrigin: ye, overrideUrl: ZE })            // ye = 常量 "https://zcode.z.ai"（chunk-OW6EUS2G @956 的 pn，导出名 Za）
...
// start() 里：let A=await e.getEndpointUrls?.(), N=A?.relayWsUrl??e.relayWsUrl
```

- `ye` = `chunk-OW6EUS2G.js` 导出 `Za` = 常量 `pn="https://zcode.z.ai"`（@956）。**默认 origin 是硬编码字面量**，不是运行时探测。
- 网页端 `?mid=` 注入点 @652,2931 ↔ 桌面端 @380,303：**同一形态**。
- 版本闸 `sI="3.4.0"`（`chunk-OW6EUS2G.js` @1,298）与网页端 `Ov='3.4.0'`（`validation` @212,410）**同值**；它决定 `/remote/v4` vs `/remote/v3`，**与 relay WS 路径无关**（两边 WS 路径恒为 `/ws`）。

**结论：WS URL 形态三方（网页端 / 桌面端 / 我们）是同一个形态**：`{wss}://{host}/ws?mid={mid}`。
差异只在**传输选项**（`X-Device-ID` 头、permessage-deflate），不在 URL。

### 1.2 握手步骤顺序（桌面端，逐帧）

`connect()` @**379,980**：

```js
connect(){this.clearReconnectTimer(),this.stopHeartbeat(),this.clearStaleWaitingRecoveryTimer(),
  this.connectAttempt+=1,this.socketGeneration+=1,this.connectStartedAt=Date.now(),
  this.setState("connecting"),this.lastPairStatusAckAt=Date.now(),
  ...;let t=new URL(this.options.relayWsUrl);t.searchParams.set("mid",this.options.deviceMid);
  let r=new tu(t.toString(),{perMessageDeflate:!0,headers:{"X-Device-ID":this.options.deviceMid}});
  this.socket=r, r.on("open",()=>{...}) }
```

`import{WebSocket as tu}from"ws"`（@**376,595**）——桌面端用 Node `ws` 库（Electron 主进程），**不是浏览器 WebSocket**。

**`open` 事件后按 `activeAuth.mode` 分两条路**（@380,502 起）：

| 模式 | 何时 | 首帧 | 后续 |
| --- | --- | --- | --- |
| `register` | 本地无持久化凭据（首次远控 / 被 `onInvalidPersistedAuth` 清掉后） | `setState("registering")` + 发 `device_register_init` | 等 `device_register_ack` |
| `persisted` | 本地有 `deviceSid`+`passHash` | `sendAuthInit(deviceSid)` → `setState("authenticating")` + 发 `auth_init` | 等 `auth_challenge` |

```js
// @380,841   register 分支
this.setState("registering"),
this.send({type:"device_register_init",device_mid:this.options.deviceMid,
           pass_hash:this.activeAuth.passHash,meta:this.options.meta,client_ts:Date.now()});
// @383,037   persisted 分支
sendAuthInit(t){this.deviceSid=t,this.setState("authenticating"),
  this.send({type:"auth_init",role:"device",device_sid:t,meta:this.options.meta,client_ts:Date.now()})}
```

**完整帧序（`handleMessage` @382,161，`switch(t.type)` 6 个 case）**：

```
① open → [register] device_register_init {device_mid, pass_hash, meta, client_ts}
        → 期望 device_register_ack {device_sid}
        → 记 deviceSid、持久化 → sendAuthInit()
   或 [persisted] 直接 auth_init
② auth_init {role:"device", device_sid, meta, client_ts}
        → 期望 auth_challenge {nonce}
③ auth_response {device_sid, proof, client_ts}
        proof = HMAC-SHA256(key=passHash, msg=`${nonce}|device|${deviceSid}`) → base64url
        → 期望 auth_ack {pair_status}  或  pair_status_ack {pair_status}
④ pair_status ∈ {waiting, matched}
        waiting → state=waiting_terminal（+ startHeartbeat）
        matched → state=paired（+ startHeartbeat）
⑤ 心跳：pair_status_query {device_sid, client_ts} → pair_status_ack {pair_status}
⑥ 业务：{type:"data", payload, client_ts}（双向）
⑦ 错误：{type:"error", code, message}
```

**`meta` 的构造**（`main-index.js` @**407,433** 起，manager 的 `start()`）：

```js
meta:{platform:process.platform, version:e.appVersion, name:e.deviceName}
```

- `platform` = Node 的 `process.platform`（`'win32'`/`'darwin'`/`'linux'`），**不是** 网页端的 `'web'`。
- `name` = `aK()`（设备名，非 `'mobile-browser'`）。
- `version` = `appVersion`，**无 `?? 'web'` 回落**（桌面端必有版本号）。

**proof 算法（桌面端实现，`main-index.js` @412,161 `createNodeWebRemoteControlRelayAuthProvider`）**：

```js
function vS(){return{
  createPassword: ()=>w0(24).toString("base64url"),                  // 24 随机字节
  createPassHash: e=>g0("sha256").update(e).digest("base64"),        // sha256 → base64（非 hex）
  calculateProof: (e,t,r,n)=>h0("sha256",e).update(`${t}|${r}|${n}`).digest("base64url")}}
// 调用点（@382,646）：calculateProof(passHash, t.nonce, "device", this.deviceSid)
```

| 项 | 网页端 | 桌面端 | 判定 |
| --- | --- | --- | --- |
| 算法 | HMAC-SHA-256 | HMAC-SHA-256 | ✅ |
| key | `passHash` | `passHash` | ✅ |
| message | `` `${nonce}|${role}|${deviceSid}` `` | `` `${nonce}|${role}|${deviceSid}` `` | ✅ |
| `role` 取值 | **`terminal`** | **`device`** | ⚠️ **同名不同值（角色区分）** |
| 输出编码 | base64url 去 padding（`btoa` + 手工替换） | `digest('base64url')`（Node 原生） | ✅ 等价 |

> **这条对壳侧是硬结论**：`role` 是**双方各自的角色名**，不是固定字面量。
> 移动端（网页端 / 我们）发 `terminal`，桌面端发 `device`。Rust `relay_client.rs:553` 用 `"terminal"`、`:631` proof 也用 `"terminal"` —— **正确**。

### 1.3 超时值

| 定时器 | 桌面端值 | 位置 | 到点行为 |
| --- | --- | --- | --- |
| **握手超时** | **不存在** | —— | transport 层无任何握手定时器：`open` 后进 `authenticating`/`registering`，若 relay 不回帧，**永久挂起**（心跳只在 `applyPairStatus` 里启动） |
| QR-ready 超时（外层护栏） | **30 000 ms**（`c0=3e4`） | `main-index.js` @**407,743** | reject `"External relay device did not reach QR-ready state before timeout."`（**只 reject `start()` 的 promise，不关 socket、不改状态**） |
| 心跳 ack 看门狗 | **30 000 ms** | @385,481 | 见 §3 |
| stale waiting 恢复 | **15 000 ms** | @386,733 | 见 §4 |
| 移动端断连宽限 | **3 000 ms**（`d0=3e3`） | @394,548 | 见 §4.3 |
| start 授权 TTL | **30 000 ms**（`a0=3e4`） | @392,012 | 授权 token 过期（非 relay 层） |

> ⚠️ 网页端有 `waitingTimeoutMs = 30 s`（`state==='waiting'` 且从未配对 ⇒ 终态 `invalid-mobile-connection`）。
> **桌面端没有这一条**：桌面端的 `waiting_terminal` 不设终态计时器，靠心跳看门狗（30 s）兜底。
> ⇒ **对端不会因为"移动端迟迟不配对"而自杀**；壳侧不能指望这个信号。

---

## 2. 身份

### 2.1 `mid` / `sid` / `device_sid` 的关系（桌面端权威）

| 名 | 含义 | 产生点 | 出现位置 |
| --- | --- | --- | --- |
| `mid`（`deviceMid`） | **每安装一份**的持久设备 id | `ensureDesktopDeviceMidSync`（`main-index.js` @**30,463**）：读 `<configDir>/telemetry-state.json` 的 `deviceMid`，缺则生成并写回 | relay WS URL `?mid=`（@380,303）、HTTP 头 `X-Device-ID`（@380,439）、`device_register_init.device_mid``device_register_init.device_mid`（@380,841）、**配对二维码 URL `mid=`** |
| `sid`（`deviceSid`） | **relay 颁发的会话/设备 sid** | relay 在注册成功时下发：`case"device_register_ack":{this.deviceSid=t.device_sid,...}`（@**382,204**）；本地持久化 | **只在配对二维码 URL `sid=` 里**（`buildWebRemoteControlExternalQrUrl`，`chunk-OW6EUS2G.js` @**780,843**）；**不在 relay WS URL 里** |
| `device_sid` | 同一 `deviceSid` 在 relay 帧里的字段名 | 同上 | `auth_init` / `auth_response` / `pair_status_query`（@382,333 / 382,625 / 385,331） |
| manager 的 `sessionId` / `windowControlSessionId` | **就是 `deviceSid`** | `main-index.js` @**409,869**：`{...,sessionId:ir.deviceSid,windowControlSessionId:ir.deviceSid,...}` | 桌面端内部/前端状态 |

**二维码（connect）URL 与 relay WS URL 是两串，别混**（`chunk-OW6EUS2G.js` @780,843）：

```js
function ace({baseUrl:t,deviceSid:n,passHash:r,timestamp:a,deviceMid:o,deviceName:i,appVersion:s,theme:l}){
  let p=new URL(t);
  p.searchParams.set("sid",n), p.searchParams.set("hash",r), p.searchParams.set("t",String(a)),
  o?.trim()&&p.searchParams.set("mid",o), i?.trim()&&p.searchParams.set("name",i),
  s?.trim()&&p.searchParams.set("app_version",s); return p.toString() }
```

- `baseUrl` = `mobileRemoteControlUrl` = `${origin}/remote/${v4|v3}`（`main-index.js` @757,573 起，`dt(ye,{appVersion})`）。
- 即：**`sid`/`hash`/`t`/`mid`/`name`/`app_version` 出现在手机扫码打开的 URL 上**；relay WS URL 只有 `mid`。
- ⇒ 网页端文档 §0 的"`sid`/`hash` 不在 URL 里"是**针对 relay WS URL** 说的，**成立**；但它们确实在**配对 URL** 里（`hash` 是 `passHash`）。
  ⚠️ 我们的 `connection_params.rs` 正是从这个**配对 URL** 解析 `sid`/`hash`/`t`/`mid`/`name`/`app_version`（`connection_params.rs:461-468`）——**与桌面端的产出形态逐字一致**，这是正确的接入方式。

### 2.2 凭据的持久化（桌面端）

| 项 | 位置 | 内容 |
| --- | --- | --- |
| `deviceSid` | settings 键 `webRemoteControlExternalRelayDevice.deviceSid` | `main-index.js` @412,710 起 `SS()`（`authStorageProvider`） |
| `passHash` | 系统凭据库（`credentialService`），键字面量 `"web-remote-control:external-relay:pass_hash"`（@412,495 `var ou=`） | 同上 |
| 部分状态 | 只有其中之一 ⇒ 视为脏数据，整体清空并告警 `"external relay auth partial state cleared"` | 同上 |

**注册模式 vs 持久模式**（`main-index.js` @407,433 起）：

```js
let re = await e.authStorageProvider.load(),
    Se = re ? {mode:"persisted", deviceSid:re.deviceSid, passHash:re.passHash}
            : {mode:"register",   passHash: e.authProvider.createPassHash(e.authProvider.createPassword())}
```

### 2.3 `bridgeSessionId` / `bridgeGeneration` / `recoveryId`

**结论：桌面端从不产生这三个值，只回显。**

- 出现点全部是**读**或**透传**：`toExternalBridge`（@390,278）、`workspace-bridge-ready`（@403,128）、
  `workspace-bridge-error`（@403,719）、`bridge-degraded`（@393,331）。
- 全文件 `bridgeGeneration` 的 26 处出现中**没有任何 `++` / 自增**（`grep` 复核）。
- ⇒ 三元组由**移动端**（网页端 / 我们）在 `workspace-bridge-open` 里铸造，桌面端原样抄进所有应答。
- 桌面端自己的**身份匹配是三元组严格相等**：`N?.bridgeSessionId===C.bridgeSessionId&&N.bridgeGeneration===C.bridgeGeneration`（@**403,354**），
  以及传输层的 `rawIdentityMatches`（`out/main/chunk-JS2T33SG.js` @**7,875**）：
  ```js
  function ke(n,e){if(typeof e!="object"||e===null)return!1;let r=e;
    return r.bridgeSessionId===n.bridgeSessionId&&r.bridgeGeneration===n.bridgeGeneration&&r.recoveryId===n.recoveryId}
  ```
- ⇒ 与网页端文档 §10.1 的结论**互相印证**：缺 `bridgeGeneration` / `recoveryId` 的 ack（Dart 风格）
  在**桌面端同样不匹配**。壳侧必须补齐（见 §8）。

---

## 3. 心跳（**网页端数值复核：成立，且桌面端同值**）

```js
// @385,135  scheduleHeartbeat
scheduleHeartbeat(){this.heartbeatTimer=setTimeout(()=>{this.heartbeatTimer=void 0,
  !(!this.deviceSid||this.state!=="paired"&&this.state!=="waiting_terminal")&&
  (this.send({type:"pair_status_query",device_sid:this.deviceSid,client_ts:Date.now()}),this.scheduleHeartbeat())},
  Qf(this.options.heartbeatIntervalMs??1e4,this.options.heartbeatJitterMs))}
// @385,481  armHeartbeatAckWatchdog
armHeartbeatAckWatchdog(){this.state!=="paired"&&this.state!=="waiting_terminal"||(
  this.heartbeatAckWatchdogTimer&&clearTimeout(this.heartbeatAckWatchdogTimer),
  this.heartbeatAckWatchdogTimer=setTimeout(()=>{if(this.heartbeatAckWatchdogTimer=void 0,
    this.state!=="paired"&&this.state!=="waiting_terminal")return;
    let t=Date.now()-this.lastPairStatusAckAt;
    this.options.logger.warn("[web-remote-control] external relay heartbeat ack timeout, reconnecting",{state:this.state,staleMs:t});
    this.reconnectAfterStaleWaiting(this.getReconnectJitterMs())},this.options.heartbeatAckTimeoutMs??3e4))}
// @386,066  getReconnectJitterMs
getReconnectJitterMs(){let t=this.options.heartbeatIntervalMs??1e4,
  r=Yf(t,this.options.heartbeatJitterMs);return eg(this.options.reconnectJitterMs??Math.min(r,2e3))}
```

**抖动函数（桌面端有实名，可直接引用；`chunk-OW6EUS2G.js`）**：

```js
// @781,476  getWebRemoteControlHeartbeatJitterMs  （导出名 pd → main-index.js 的 Yf）
function oL(t=1e4,n){let r=Number.isFinite(t)&&t>0?Math.floor(t):1e4, a=Math.min(2e3,Math.floor(r*.2)),
  o=n??a; return !Number.isFinite(o)||o<=0?0:Math.min(Math.floor(o),Math.max(0,r-1))}
// @781,704  getWebRemoteControlHeartbeatDelayMs   （导出名 qd → main-index.js 的 Qf）
function pce(t=1e4,n,r=Math.random){let a=Number.isFinite(t)&&t>0?Math.floor(t):1e4, o=oL(a,n),
  i=Math.max(1,a-o), s=a+o; return i+Math.floor(xk(r)*(s-i+1))}
// @781,902  getWebRemoteControlReconnectJitterMs  （导出名 rd → main-index.js 的 eg）
function mce(t=2e3,n=Math.random){if(!Number.isFinite(t)||t<=0)return 0; let r=Math.floor(t);
  return Math.floor(xk(n)*(r+1))}
```

| 项 | 网页端（16 篇 §2.2） | **桌面端（本篇）** | 判定 |
| --- | --- | --- | --- |
| 心跳周期基准 | 10 000 ms | 10 000 ms（`heartbeatIntervalMs??1e4`） | ✅ |
| 抖动幅度 | `min(2000, floor(i*0.2))` = 2 000 ms | **同**（`Math.min(2e3,Math.floor(r*.2))`） | ✅ |
| 实际间隔 | 8 000 – 12 000 ms 均匀 | **8 000 – 12 000 ms 均匀** | ✅ |
| ack 看门狗 | 30 000 ms | 30 000 ms（`heartbeatAckTimeoutMs??3e4`） | ✅ |
| 看门狗→重连延迟 | `sn(min(2000,2000))` = 0–2 000 ms | **同**：`eg(Math.min(Yf(1e4,undefined),2e3))` = `mce(2000)` = 0–2 000 ms | ✅ |
| 发心跳的状态 | `paired` / `waiting` | `paired` / **`waiting_terminal`** | ⚠️ 状态名不同 |
| 额外闸门 | 无 | **`deviceSid` 必须为真**（注册模式收到 `device_register_ack` 前不发） | ➕ 桌面端独有 |
| 看门狗重新武装点 | `applyPairStatus` 每次应答 | **同**（`applyPairStatus` @383,203 开头 `armHeartbeatAckWatchdog()`） | ✅ |
| `authenticating` 期间 | 不发心跳 | **同**（只有 `paired`/`waiting_terminal` 发） | ✅ |

**桌面端独有的一处**：`armHeartbeatAckWatchdog` 里算了 `Date.now()-lastPairStatusAckAt`，**只用于日志字段 `staleMs`**，
**不参与任何分支判断** —— 到点就重连，判据是"定时器没被 `applyPairStatus` 重置"。

**无人覆盖的选项**：`heartbeatIntervalMs` 只出现在 @385,423 / @386,108 两处（都是读默认值），
`heartbeatJitterMs` @385,461 / @386,153，`heartbeatAckTimeoutMs` @386,037，`reconnectJitterMs` @386,195。
⇒ **manager 从不覆盖它们**，桌面端实际运行值就是上述默认值。

---

## 4. 重连与退避

### 4.1 退避序列：**桌面端没有指数退避**

```js
// @386,448
scheduleReconnect(t=this.options.reconnectDelayMs??1e3){
  this.manuallyClosed||this.terminalClose||(this.clearReconnectTimer(),
  this.reconnectTimer=setTimeout(()=>this.connect(),t))}
```

| 项 | 网页端 | **桌面端** | 我们（Rust） |
| --- | --- | --- | --- |
| 公式 | `min(10 000, 500 × 2^attempt)` | **固定 `reconnectDelayMs ?? 1 000` ms** | `(1000 × 2^attempt).clamp(1000, 15000)` |
| 第 1/2/3/4/5 次 | 1 000 / 2 000 / 4 000 / 8 000 / 10 000 ms | **1 000 / 1 000 / 1 000 / 1 000 / 1 000 ms** | 1 000 / 2 000 / 4 000 / 8 000 / 15 000 ms |
| 上限 | 10 000 ms | 1 000 ms（恒等） | 15 000 ms |
| 计数变量 | `reconnectAttempt`（参与公式） | `connectAttempt`（**只进遥测**，@388,155 `ai(...)`） | `reconnect_attempt` |

**全类区间（`main-index.js` 377,400–389,200）内 `2**` 出现 0 次** —— 这是"无退避"的直接证据。
`reconnectDelayMs` 全文件只在 @386,481（默认参数）出现一次 ⇒ manager 未覆盖 ⇒ **桌面端重连节拍 = 每 1 000 ms 一次，永不增长**。

### 4.2 什么状态下重连

| 触发 | 位置 | 延迟 | 状态迁移 |
| --- | --- | --- | --- |
| socket `close`（且 `!manuallyClosed && !terminalClose`） | @381,769 | `scheduleReconnect()` = 1 000 ms | → `connecting` |
| 心跳 ack 看门狗超时 | @385,481 | `reconnectAfterStaleWaiting(jitter)`，jitter 0–2 000 ms | → `connecting`（仅当 jitter>0） |
| `pair_status='waiting'` 且已配对过，第 1 次 | @383,203 + @386,733 | `scheduleStaleWaitingRecovery()` = **15 000 ms** | 保持 `waiting_terminal`，到点若仍 `paired`（原文如此，见下）且 `staleWaitingCount>0` ⇒ `reconnectAfterStaleWaiting()` |
| 同上，第 ≥2 次 | @383,203 | **立即** `reconnectAfterStaleWaiting()` | → `connecting` |
| relay `error: INTERNAL` 且 state ∈ {`paired`,`waiting_terminal`} | @384,436 | 不关 socket，回 `waiting_terminal` + 起心跳 | `enterWaitingForPairAfterRelayError` |
| relay `error: INTERNAL` 其它状态 | @384,436 | `enterRecoverableError`：关 socket ⇒ close 处理器 1 000 ms 重连 | → `error` → `connecting` |
| relay `error: KICKED` | @383,947 | 关 socket ⇒ close 处理器 1 000 ms 重连 | 保持原状态（**不终态**） |
| relay `error: AUTH_FAILED` + 持久模式 + 未用过一次性重试 | @384,096 | 清凭据 → 切 `register` → 关 socket → `scheduleReconnect(0)`（**0 ms**） | → `connecting` |
| relay `error: 其它` | @384,754 | `enterError`：`terminalClose=true` ⇒ **不再重连** | → `error`（终态） |

**`reconnectAfterStaleWaiting(t=0)`（@387,123）**：

```js
reconnectAfterStaleWaiting(t=0){this.clearStaleWaitingRecoveryTimer(),this.stopHeartbeat(),
  this.clearReconnectTimer(),this.staleWaitingCount=0,this.wasPaired=!1;
  let r=this.socket; if(this.socket=void 0,t>0&&this.setState("connecting"),r?.close(),
  t>0){this.reconnectTimer=setTimeout(()=>{this.reconnectTimer=void 0,
    !(this.manuallyClosed||this.terminalClose)&&this.connect()},t);return} this.connect()}
```

**`scheduleStaleWaitingRecovery()`（@386,733）**：

```js
scheduleStaleWaitingRecovery(){this.staleWaitingRecoveryTimer||(this.staleWaitingRecoveryTimer=setTimeout(()=>{
  this.staleWaitingRecoveryTimer=void 0,this.state==="paired"&&this.staleWaitingCount>0&&this.reconnectAfterStaleWaiting()},15e3))}
```

> ⚠️ **桌面端此处有疑似 bug**（原文照录）：恢复定时器里判的是 `this.state==="paired"`，
> 而它是由 `applyPairStatus('waiting')` 分支武装的（那时状态是 `waiting_terminal`）。
> 于是 **15 s 这条恢复路径只有在"状态已变回 paired"时才触发**，实际等于几乎不可达；
> 真正生效的是**第二次** `waiting` 的立即 `reconnectAfterStaleWaiting()`。
> ⚠️ 标记为「**代码原文如此，语义疑似缺陷**」，未做动态验证。

### 4.3 宽限期（"`DEVICE_OFFLINE` 之类的宽限期"）

**桌面端没有 `DEVICE_OFFLINE` 分支**：

```bash
# 全包检索（tools/asar.mjs grep，扫 21,920 个文件）
DEVICE_OFFLINE  →  0 命中
```

- `handleRelayError` 只处理 `KICKED` / `AUTH_FAILED` / `INTERNAL` / `WRONG_PARAM` / 默认（@383,911–384,7xx）。
- 这与桌面端的**角色**一致：`DEVICE_OFFLINE` 是 relay 告诉**移动端**"桌面掉线了"，桌面端自己不可能是"设备离线"的受害者。
- ⇒ **网页端文档 §7.1 的 `DEVICE_OFFLINE` → 15 s `desktopOfflineGraceMs` 在桌面端无对应实现**，无法从对端验证该宽限期的服务端语义。

**桌面端自己的宽限期（方向相反：移动端掉线）**：

```js
// @394,548  scheduleMobileDisconnectGrace（d0 = 3e3）
function se(h){h.mobileDisconnectGraceTimer||(h.mobileDisconnectGraceTimer=setTimeout(()=>{
  h.mobileDisconnectGraceTimer=void 0,!(t.get(h.windowId)!==h||h.status==="error")&&
  (h.status="running",h.mobileConnected=!1,z(h))},d0))}
```

- 由 `mapTransportState`（@**405,774**）在 state 变成 `connecting` / `registering` / `authenticating` / `waiting_terminal`
  **且 `mobileConnected` 曾为真**时调用 ⇒ 窗口保持 `status:"active"` **3 000 ms** 后才翻成 `"running"` + `mobileConnected=false`。
- 意义：**relay 抖动/重连的 3 s 内，桌面端 UI 不闪"移动端已断开"**。

---

## 5. 关闭码

**网页端结论（不解读 close code，语义在 relay 顶层 `error.code`）→ 桌面端复核：成立，且更彻底。**

```js
// @381,769  close 监听（逐字，注意回调**没有参数**）
r.on("close",()=>{if(r===this.socket){if(this.stopHeartbeat(),this.suppressNextCloseReconnect){
  this.suppressNextCloseReconnect=!1;return}!this.manuallyClosed&&!this.terminalClose&&(
  this.options.logger.warn("[web-remote-control] external relay device disconnected"),
  this.state!=="paired"&&ai(Date.now()-this.connectStartedAt,!1,{attempt:this.connectAttempt}),Kh(),
  this.scheduleReconnect())}})
```

| 项 | 网页端 | **桌面端** |
| --- | --- | --- |
| 是否读 `code` | 读，但只进 `mobile-diagnostic` 上报 | **完全不读**（`close` 回调无参数，连 `wasClean`/`reason` 都没有） |
| 4xxx 关闭码常量 | 无 | **无**（全包 0 命中） |
| 重连判据 | `hadPairedSession` | **`!manuallyClosed && !terminalClose`**（没有"是否配对过"这个条件） |
| 从未配对过就断开 | 终态 `relay-unavailable` | **照样重连**（1 000 ms） |
| 唯一例外 | —— | `suppressNextCloseReconnect`（`AUTH_FAILED` 重注册路径专用，@384,096 置真，close 处理器消费一次） |

> **与我们的实现的直接冲突**：Rust `relay_client.rs:125-134` 有 `relay_close_reason()`（4004/4009/4010/4011/4012/4013 → 原因码，臂在 127–132），
> 并在 `:778` 用它产出 failure、在 `:791` 用 `mapped == Some("desktop-disconnected")` 作为**额外的重连判据**。
> **网页端与桌面端都不这样做**（16 篇 §8 已实测网页端 0 命中）。见 §8 第 7 条。

---

## 6. relay 顶层帧全集（桌面端视角）

桌面端**没有任何 zod schema 覆盖这些顶层帧**：`device_register_init` 全包只出现 1 次（@380,841，出站），
`device_register_ack` 1 次（@382,204，入站 `case`），无 `.strict()` 校验器 ⇒ **顶层帧是裸 JSON 判别**。
入站解析 `parseRelayMessage`（@**376,664**）：

```js
function t0(e){if(ma(e)>kr.maxPhysicalFrameBytes)return null;try{
  let t=Array.isArray(e)?Buffer.concat(e):e instanceof ArrayBuffer?Buffer.from(e):ArrayBuffer.isView(e)?Buffer.from(e.buffer,e.byteOffset,e.byteLength):Buffer.from(String(e),"utf8"),
  r=JSON.parse(t.toString("utf8")); return r&&typeof r=="object"&&"type"in r?r:null}catch{return null}}
```

`kr` = `chunk-OW6EUS2G.js` 导出 `Z` = **`Ue`**（@**545,748**）= 与网页端 `bd` 同构的预算对象
（`maxPhysicalFrameBytes:Re.maxFrameBytes` = **1 MiB**）。

| 顶层 `type` | 方向 | 字段（逐字） | 桌面端处置 |
| --- | --- | --- | --- |
| `device_register_init` | 出 | `device_mid, pass_hash, meta, client_ts` | @380,841（register 模式首帧） |
| `device_register_ack` | 入 | `device_sid` | @382,204：记 `deviceSid`；若 register 模式则 `onRegisteredAuth` + 切 persisted，然后 `sendAuthInit(device_sid)` |
| `auth_init` | 出 | `role:"device", device_sid, meta, client_ts` | @383,112（`sendAuthInit` @383,037） |
| `auth_challenge` | 入 | `nonce` | @382,516：无 `deviceSid` 时 `enterError("External relay auth challenge arrived before device_sid")`（**终态**）；否则回 `auth_response` |
| `auth_response` | 出 | `device_sid, proof, client_ts` | @382,646 |
| `auth_ack` | 入 | `pair_status` | @382,838（`case"auth_ack":case"pair_status_ack"`）→ `applyPairStatus` |
| `pair_status_ack` | 入 | `pair_status` | 同上 |
| `pair_status_query` | 出 | `device_sid, client_ts` | @385,331（心跳） |
| `data` | 双向 | 入：`payload`；出：`payload, client_ts` | @382,918 → `handleDataPayload`（@384,787；出站经 `Og` 序列化器） |
| `error` | 入 | `code, message` | @382,970 → `handleRelayError(code, message)` |
| **`ping` / `pong`** | —— | —— | **不存在**（全文件 `"ping"`/`"pong"` 字面量 0 命中；`ws` 库也未调 `.ping()`，`.ping(` 0 命中） |

**`data` 的出站封装**（`out/main/chunk-JS2T33SG.js`，`WebRemoteControlRelayPayloadSerializer`）：

```js
prepare(e,r=Date.now()){let o=this.cache.get(e);if(o)return o;
  let c={type:"data",payload:e,client_ts:r},u=JSON.stringify(c),
  S=Object.freeze({message:c,json:u,bytes:new TextEncoder().encode(u).byteLength});
  return this.cache.set(e,S),S}
isOversize(e){return e.bytes>g.maxPhysicalFrameBytes}
```

⇒ 与网页端 `h4n` **逐字节同构**（`{type:'data',payload,client_ts}` + `WeakMap` 缓存 + 1 MiB 闸门）。

**桌面端的两处信封护栏（网页端也有，同值）**：

| 判定 | 位置 | 行为 |
| --- | --- | --- |
| 入站原始帧 > 1 MiB | @381,077（`on("message")`） | `logger.warn("oversize external relay message dropped")` + `onRawTransportFault("remote.rpcFrame.envelopeTooLarge")` ⇒ manager 侧 `markDegraded` |
| 入站 JSON 解析失败 / 非对象 / 无 `type` | `t0` @376,664 | 返回 `null` ⇒ **静默丢弃**（`logger.warn("invalid external relay message")`） |
| 入站业务帧 `safeParse` 失败 | `vf`（= `chunk-OW6EUS2G.js` @**728,128** `parseWebRemoteControlAppPayload` = `UD.safeParse(t)`） | `logger.warn("invalid external relay payload dropped")` + **静默丢弃**（与网页端 §3 结论一致） |
| 出站信封 > 1 MiB | @388,455 `send()` | 返回 `false`，**静默不发**（`logger.warn("outbound relay message exceeds hard limit")`） |
| 出站业务帧且 `state!=='paired'` 或 `staleWaitingCount>0` | @379,472 `sendPayloadResult` | `{kind:'unavailable'}` ⇒ manager 存入 `pendingOutboundPayloads`（上限 50，@395,375 `U`） |

> **`staleWaitingCount` 在桌面端是活代码**（与网页端的死代码相反）：
> `applyPairStatus` 里 `this.staleWaitingCount+=1`（@383,203 分支），且被 `sendPayloadResult` 当**出站闸门**读。
> ⇒ 桌面端一旦掉回 `waiting`，**主动停发业务帧**。

---

## 7. 桌面端状态机

`setState` 字面量（类区间内枚举，共 7 个）：`idle` / `connecting` / `registering` / `authenticating` / `waiting_terminal` / `paired` / `error`。

| 值 | 进入条件 | 备注 |
| --- | --- | --- |
| `idle` | 构造初值 / `dispose()`（@379,472 附近） | |
| `connecting` | `connect()` @379,980；`reconnectAfterStaleWaiting(t>0)` | |
| `registering` | `open` 且 `activeAuth.mode==='register'` @380,795（帧 @380,841） | **网页端无此状态** |
| `authenticating` | `sendAuthInit()` @383,037 | |
| `waiting_terminal` | `applyPairStatus('waiting')` @383,203 | **网页端叫 `waiting`** |
| `paired` | `applyPairStatus('matched')` @383,203 | |
| `error` | `enterError` / `enterRecoverableError` @387,521 | |
| `kicked` | **从未进入**：`setState("kicked")` 全文件 0 次 | manager 的 `mapTransportState` 里仍留着 `kicked` 分支（@406,344），**本构建为死分支** |

**manager 侧另有一份 `transportState`**（`main-index.js` @**405,774** `mapTransportState`，字段在 `ET` 里赋值）：

```js
function ET(h,C){let A=h.transportState;h.transportState=C;
  if(C==="connecting"||C==="registering"||C==="authenticating"){ ... h.status="starting" / "active" ... }
  if(C==="waiting_terminal"){ ... }
  if(C==="paired"){ ... h.status="active",h.mobileConnected=!0,z(h),Te(h) ... }   // Te = 冲刷 pendingOutboundPayloads
  if(C==="kicked"){ ... ht(h.windowId,h,"external-relay-kicked",wS("session-conflict",...)) }
  if(C==="error"){ ... h.status="error" ... } }
```

> ⚠️ **网页端文档 §9.2 说 `transportState` 在 3.15.3 网页端不存在（0 文件命中）——桌面端主进程里存在同名概念**，
> 但它是**桌面端内部的管理器字段**，**不上线**，与网页端的 `connectionKind` / `webRemoteControlTerminalTransportState` 无关。
> 两边不冲突，但"`transportState` 不存在"这句话**只对网页端成立**。

---

## 8. 与我们的实现的差异

对照 `rust/crates/protocol/src/relay_client.rs`（2,404 行，已读）、
`rust/crates/protocol/src/connection_params.rs`、`android/app/src/main/kotlin/app/zemote/engine/RelayWebSocket.kt`（201 行，已读）。

### 8.0 先给核对结论：**WS URL 形态三方一致** ✅

| 实现 | URL 构造 | 形态 |
| --- | --- | --- |
| 网页端 | `Rv()`（`validation` @213,995）+ `searchParams.set('mid',…)`（@652,2931） | `wss://zcode.z.ai/ws?mid=<mid>` |
| 桌面端 | `resolveWebRemoteControlRelayWsUrl`（`chunk-OW6EUS2G.js` @3,382）+ `searchParams.set("mid",…)`（`main-index.js` @380,303） | `wss://zcode.z.ai/ws?mid=<mid>` |
| **我们** | `connection_params.rs:474-491` `relay_ws_uri()`：`{wss\|ws}://{source.host}[:port]/ws` + `?mid={device_mid}` | **同形态** |

- 我们的 `mid` 来源 = 配对 URL 的 `?mid=`（`connection_params.rs:464`），桌面端**正是**把 `deviceMid` 写进配对 URL（`chunk-OW6EUS2G.js` @780,843）。
  ⇒ **推导链正确**。
- 唯一形态差异：桌面端 host 来自**硬编码常量** `https://zcode.z.ai`（或 `ZCODE_WEB_REMOTE_CONTROL_RELAY_WS_URL` 环境变量覆盖）；
  我们取**配对 URL 的 host**。正常场景（`zcode.z.ai` / `zcode.chatglm.site`）两者结果相同；
  自建 origin 场景下会分歧（桌面端仍打 `wss://zcode.z.ai/ws`，我们打该 origin）——⚠️ 非缺陷，但要知道语义不同。
- 我们**不带** `X-Device-ID` 头、**不请求** permessage-deflate（见下表 11/12）。

### 8.1 逐条不一致（`文件:行`）

| # | 项 | 对端（桌面端 / 网页端） | 我们 | 判定 |
| --- | --- | --- | --- | --- |
| 1 | **心跳无抖动** | 8 000–12 000 ms 均匀（`main-index.js:385,135` + `chunk-OW6EUS2G.js:781,704`；网页端 16 篇 §2.2 同值） | 固定 10 000 ms：`relay_client.rs:138`（`HEARTBEAT_INTERVAL_MS`）+ `:811`（`TimerStart{Heartbeat, 10_000, repeat:true}`） | ❌ **无抖动**，且是**周期定时器**而非递归 `setTimeout` |
| 2 | **waiting 态心跳节拍** | 每拍都发（10 s ± 2 s，桌面端 `waiting_terminal` / 网页端 `waiting`） | **隔一拍发一次**：`relay_client.rs:889-890`（`if self.state == Waiting && self.heartbeat_tick % 2 == 1 { return }`）⇒ **20 s** | ❌ 网页端与桌面端**都**是 10 s，只有我们 20 s |
| 3 | **看门狗判据** | 定时器未被 `applyPairStatus` 重置即重连（`main-index.js:385,481`；网页端同） | 在心跳 tick 内比 `now - last_pair_status_ack_at > 30_000`：`relay_client.rs:893` | ⚠️ 效果近似；但我们额外有 `stale_probe_sent` **两次探测**（`:893-905`）才重连 ⇒ 实际 30 s **+1 拍** |
| 4 | **waiting 终态超时** | 网页端有 `waitingTimeoutMs=30 s` → `invalid-mobile-connection`；**桌面端没有** | 有：`relay_client.rs:142` + `:826` + `:852` | ⚠️ 跟网页端一致、跟桌面端不一致。**对端权威（桌面端）不做这件事**，我们做了 → 若移动端长时间不被配对，我们会自己进终态而桌面端不会 |
| 5 | **rewait 恢复延迟** | 桌面端 **15 000 ms**（`main-index.js:386,733`） | **20 000 ms**：`relay_client.rs:144`（`RECONNECT_WAIT_TIMEOUT_MS`）+ `:699` | ❌ 数值不一致（且桌面端那条路径语义可疑，见 §4.2） |
| 6 | **重连退避** | 桌面端 **固定 1 000 ms，无退避**（`main-index.js:386,448`）；网页端 `min(10 000, 500×2^n)` | `(1000×2^n).clamp(1000,15000)`：`relay_client.rs:1033` | ❌ 上限 15 000 与桌面端 1 000 / 网页端 10 000 都不同；我们照抄的是 Dart |
| 7 | **关闭码语义** | **桌面端完全不读**（`main-index.js:381,769`，回调无参数）；**网页端不解读**（16 篇 §8，0 命中 4xxx） | **解读并映射**：`relay_client.rs:125-134` `relay_close_reason(4004/4009/4010/4011/4012/4013)`；`:778` 用它产出 failure；`:791` `mapped == Some("desktop-disconnected")` 作为**额外重连判据** | ❌ **我们独有**。两个对端都不这么做 ⇒ 该分支的语义无法被对端确认（Dart 遗留） |
| 8 | **`KICKED` 处置** | 桌面端：log + 关 socket ⇒ **1 000 ms 后重连**（`main-index.js:383,947`，永不终态，`setState("kicked")` 0 次）；网页端：终态 `kicked` + `intentionallyClosed` | 一次性 `kick_recovery_attempted` 后进**终态** `Kicked` + `intentionally_closed`：`relay_client.rs:734-757`（`set_state(Kicked)` @751） | ❌ 桌面端**会一直重连**，我们会终态。真机上"原生一配对，我们立刻 KICKED"的处置策略会不同 |
| 9 | **`DEVICE_OFFLINE`** | 桌面端**无此分支**（全包 0 命中）；网页端有 15 s `desktopOfflineGraceMs` | **无**（`relay_client.rs` 未出现） | ✅ 与桌面端一致（我们本来就收不到这个码，除非伪装成移动端被 relay 判桌面离线——那种情况我们确实需要它，见 §9） |
| 10 | **业务帧出站闸门** | 桌面端 `state!=='paired' || staleWaitingCount>0` ⇒ **不入队、返回 `unavailable`**（`main-index.js:379,472`）；网页端 `state!=='paired'` ⇒ `unavailable` | `state != Paired \|\| socket.is_none()` ⇒ **入队**（上限 100）：`relay_client.rs:934` + `:151` | ❌ **语义相反**：对端"未配对就丢弃/交上层缓冲"，我们"未配对就本地排队" |
| 11 | **`X-Device-ID` 头** | 桌面端带：`headers:{"X-Device-ID":this.options.deviceMid}`（`main-index.js:380,439`） | 不带：`RelayWebSocket.kt:84` `Request.Builder().url(uri).build()` | ❌ 缺失（relay 是否强制校验**未证实**，见 §9） |
| 12 | **permessage-deflate** | 桌面端请求：`perMessageDeflate:!0`（`main-index.js:380,418`）并读 `socket.extensions` 上报诊断 | OkHttp 默认**不启用**压缩（`RelayWebSocket.kt:178-183`） | ⚠️ 非缺陷（双方可协商失败），但**帧字节数/带宽行为不同** |
| 13 | **入站 1 MiB 护栏** | 桌面端：`ma(n) > kr.maxPhysicalFrameBytes` ⇒ 丢弃 + `onRawTransportFault`（`main-index.js:381,077`） | **无**：`relay_client.rs:575` `on_frame` 无字节上限检查 | ❌ 未实现 |
| 14 | **出站 1 MiB 护栏** | 桌面端：`send()` 里 `n > kr.maxPhysicalFrameBytes` ⇒ 返回 `false` 静默不发（`main-index.js:388,455`） | **无**：`relay_client.rs:466-484` `send()` 无上限检查 | ❌ 未实现（分片层 `rpc_transport.rs` 有 1 MiB 概念，但 relay 信封本身不量） |
| 15 | **注册握手 `device_register_*`** | 桌面端**有**（首次安装）：`device_register_init` → `device_register_ack`（`main-index.js:380,824` / `382,199`） | **无**：`on_socket_ready` 一律直接发 `auth_init`（`relay_client.rs:552-558`） | ✅ 对**移动端**而言是正确缺省（移动端从不注册）；但要知道：**首次配对的桌面端在这个阶段，relay 上还没有 `sid`** |
| 16 | **`role` 字面量** | 移动端 `terminal` / 桌面端 `device` | `auth_init` `"terminal"`（`relay_client.rs:553`）、proof `"terminal"`（`:631`） | ✅ **正确**（我们扮演移动端） |
| 17 | **`poke()` / 死链阈值** | 桌面端**无**（Electron 主进程无前后台生命周期）；网页端有 `visibilitychange`/`freeze` 挂起-恢复（16 篇 §2.5） | 有 `poke()` + `DEAD_LINK_THRESHOLD_MS=25_000`：`relay_client.rs:145` / `:990-1024` | ⚠️ 保留无害（Android 有前后台），但**数值 25 s 无对端依据**（Dart 遗留） |
| 18 | **`stale_probe_sent` 二次探测** | 桌面端**无**（看门狗到点即重连）；网页端**无** | 有：`relay_client.rs:305` / `:893-905` | ⚠️ 我们多一次"先探后断"，会**晚一拍（10 s）**才重连 |
| 19 | **`transportState`** | 桌面端 manager **有**（`main-index.js:405,774`，内部字段）；网页端 3.15.3 **无** | 无此名 | ✅ 无需动作（不上线概念） |

**不一致合计 19 条**：

- ❌ **判为需修（10 条）**：#1 心跳无抖动、#2 waiting 态 20 s、#5 rewait 20 s vs 15 s、#6 退避上限、
  #7 关闭码解读、#8 `KICKED` 终态、#10 出站闸门语义相反、#11 缺 `X-Device-ID`、#13 缺入站 1 MiB 护栏、#14 缺出站 1 MiB 护栏。
- ⚠️ **需决策（6 条）**：#3 二次探测晚一拍、#4 waiting 终态超时、#9 `DEVICE_OFFLINE` 缺失、#12 压缩、
  #17 `poke()`/死链 25 s、#18 `stale_probe_sent`。
- ✅ **已正确（3 条）**：#15 不注册（移动端正确缺省）、#16 `role:"terminal"` 正确、#19 `transportState` 无需动作。

> 注：#3/#18 是同一机制（`stale_probe_sent`）的两个侧面，合并看即 1 项。

### 8.2 与 `RelayWebSocket.kt` 的差异（传输层）

| 项 | 桌面端 | `RelayWebSocket.kt` | 判定 |
| --- | --- | --- | --- |
| WebSocket 实现 | Node `ws`（`main-index.js:376,595`） | OkHttp（`:36-40`） | ✅ 等价 |
| 握手/心跳/退避 | 全在 transport 类里 | **全在 Rust**，Kotlin 只是哑传输（`:74-121`） | ✅ 分层合理 |
| 代次/旧连接静音 | `socketGeneration`，`r!==this.socket` 时丢弃回调（`main-index.js:380,6xx` 每处都判） | `silenced` + `cancel()`（`:74-87`、`:131-142`） | ✅ 同语义 |
| 终止只上报一次 | `ws` 的 `error`/`close` 各自独立处理，靠 `suppressNextCloseReconnect` 去重 | `claimTerminal()` 闸门（`:134-142`） | ✅ Kotlin 侧更严 |
| 关闭码清洗 | 无（`close()` 不传码） | `sanitizeCloseCode`：`null→1000`，非 1000/3000-4999 → 1000（`:193-198`） | ⚠️ OkHttp 限制所致，合理；**但会把 Dart 的 `3000/'debug-drop'` 保留**（`:1066-1072`） |
| 超时 | 无（`ws` 默认无 ping/pong 保活，靠应用层心跳） | `connectTimeout 15 s`、`readTimeout 0`（`:178-183`） | ⚠️ 15 s 连接超时**无对端依据**；桌面端 `ws` 默认 `handshakeTimeout` 未设置 |
| 二进制帧 | 桌面端 `t0` 把 `ArrayBuffer`/数组转 Buffer 后按 UTF-8 解析（@376,664） | 上报 `binary_frame` + **标准 base64**（`:154-157`、`:201`） | ⚠️ 我们多一条二进制通路；桌面端从不发二进制 relay 帧（**未证实**，见 §9） |
| 帧大小护栏 | 入/出双向 1 MiB（`main-index.js:381,077` / `388,455`） | 无 | ❌ 见 8.1 #13/#14 |

---

## 9. 仍未证实 / 需要抓包

1. **`X-Device-ID` 是否被 relay 强制校验**：桌面端无条件发送，我们从不在线抓过 relay 的握手响应。
   若 relay 对 `mid` 与 `X-Device-ID` 做一致性校验，我们可能被静默降级/拒连。**需要抓一次我们自己的握手 + relay 的 `auth_challenge` 是否到来**。
2. **relay 是否发 `ping` 帧（WS 控制帧）**：桌面端 `ws` 库与浏览器都自动回 `pong`，我们的 OkHttp 也自动回。
   全包无 `"ping"`/`"pong"` 字面量 ⇒ 应用层无 ping。**若 relay 用控制帧 ping 做探活，我们的 `readTimeout=0` 会让死链只能靠应用心跳发现**。
3. **`DEVICE_OFFLINE` 是否真的会发给移动端**：桌面端无该分支（0 命中），网页端有。**我们没有抓包证据**证明 relay 在什么条件下发它。
   若它真会发给我们，我们当前**完全不处理**（`handle_relay_error` 的 default 分支 ⇒ 终态 `relay-unavailable`？需确认我们的 default 行为）。
4. **`staleWaitingCount` 出站闸门的实际效果**：桌面端一旦 `waiting_terminal` 就停发业务帧；
   我们不知道 relay 会不会在 `waiting_terminal` 期间仍然接受 `data` 帧（桌面端是主动不发，不是拒收）。
   **需要抓一次"移动端在 waiting 态发 data"的 relay 响应**。
5. **`kicked` 状态在桌面端是否真死代码**：静态上 `setState("kicked")` 0 次、manager 分支存在。
   但**可能存在我们没扫到的动态赋值路径**（如 `onStateChange` 由外部注入 `"kicked"`）。未动态验证。
6. **`scheduleStaleWaitingRecovery` 的 `state==="paired"` 判据是否真是 bug**：见 §4.2 ⚠️。静态可疑，未动态验证。
7. **relay 是否强制 1 MiB 入站上限**：桌面端自己 enforce（@381,077），但**relay 侧**是否也 enforce 未证实。
   我们出站不量信封 ⇒ 若 relay 强制，我们的超限帧会被静默丢弃。
8. **`meta.platform` 取值是否被 relay/桌面端校验**：桌面端发 `process.platform`（`win32`/`darwin`/`linux`），
   网页端发 `'web'`。**我们发什么（android？）是否被任何一方消费，未证实**。
9. **桌面端 `register` 模式下 `device_register_init` 的响应形态**：只知道 `device_register_ack.device_sid`。
   是否有 `device_register_error` / 失败重试上限，**未证实**（全包 0 命中 `device_register_error`）。

---

## 10. 复现命令

```bash
cd E:/Zcode_android/docs/_audit/desktop-extract

# 1) 桌面端 relay 客户端类骨架（connect / open / 首帧分岔）
node -e "const b=require('fs').readFileSync('main-index.js');console.log(b.subarray(379980,381000).toString())"
# 2) 入站 handleMessage 六个 case + applyPairStatus + handleRelayError
node -e "const b=require('fs').readFileSync('main-index.js');console.log(b.subarray(382161,384960).toString())"
# 3) 心跳 / 看门狗 / 抖动 / 退避 / stale waiting
node -e "const b=require('fs').readFileSync('main-index.js');console.log(b.subarray(384960,387600).toString())"
# 4) send() 的 1 MiB 闸门 + 常量 a0/c0/d0 + manager start() 的 meta/options
node -e "const b=require('fs').readFileSync('main-index.js');console.log(b.subarray(388300,389200).toString());console.log(b.subarray(407180,408600).toString())"
# 5) WS URL 解析器 / 抖动函数实名 / 二维码 URL 构造器 / 1 MiB 预算对象
node -e "const b=require('fs').readFileSync('chunk-OW6EUS2G.js');console.log(b.slice(1000,3500));console.log(b.slice(780800,782100));console.log(b.slice(545700,545900))"
# 6) 传输层身份严格相等（rawIdentityMatches）
cd E:/Zcode_android && node tools/asar.mjs cat out/main/chunk-JS2T33SG.js | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>console.log(s.slice(7700,8400)))"
# 7) DEVICE_OFFLINE / ping / pong 全包 0 命中复核
node tools/asar.mjs grep "DEVICE_OFFLINE" --max-hits 5
node tools/asar.mjs grep "pair_status_query" --max-hits 5
```

---

## 11. 出处

- 桌面端主进程：`out/main/index.js`（`WebRemoteControlDeviceTransport` @378,577；`mapTransportState` @405,774；
  `createWebRemoteControlManager` @~391,700 起；options 组装 @757,573）
- 桌面端共享协议块：`out/main/chunk-OW6EUS2G.js`（`resolveWebRemoteControlRelayWsUrl` @3,382；
  `buildWebRemoteControlExternalQrUrl` @780,843；抖动三函数 @781,476/781,704/781,902；预算 `Ue` @545,748；
  `parseWebRemoteControlAppPayload` @728,128）
- 桌面端传输层：`out/main/chunk-JS2T33SG.js`（`rawIdentityMatches` @7,875；
  `WebRemoteControlRelayPayloadSerializer.prepare`；预算 `m` 与 `saturation/replayBuffer` 常量）
- 我方实现（只读对照）：`rust/crates/protocol/src/relay_client.rs`、`connection_params.rs`、
  `android/app/src/main/kotlin/app/zemote/engine/RelayWebSocket.kt`
- 网页端交叉基线：`docs/remote-v4/16-remote-v4-relay协议目录.md`（3.15.3，2026-10-15 快照）
- 相关篇目：`docs/desktop/` 其它批次（帧 schema / 桥与分片 / 配对与二维码）
