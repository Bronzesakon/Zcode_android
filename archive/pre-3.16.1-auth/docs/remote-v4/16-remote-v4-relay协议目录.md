# remote-v4 relay 协议目录

> **审计基线**：3.15.3（2026-10-15 快照）
> 主包 `assets/index-BGa3KiRe.js`（6,592,643 B，SHA-256 `ce2e2fc4…b88fd`）
> schema `assets/validation-CqXZOLXO.js`（307,369 B，SHA-256 `96e64e8f…dd6c9b`）
> 旧基线（2026-09-20 / `index-B-ilXaCQ.js`）的偏移与符号名**一律作废**。
>
> 快照目录 `E:\Zcode_android\Reference_web\3.15.3\`（只读，抓取于 2026-10-15 19:52 +08:00）。
> **偏移口径：UTF-8 字节偏移**（一律用 `Buffer.indexOf` / `grep -b`；Node 字符串 `indexOf` 是
> UTF-16 索引，主包含大量中文，两种口径会差数千字节）。
> 本文只写 **relay 层的行为**（时序/数值/状态机/网页端实际处置）；逐帧字段表见新增的 49 篇。
> 桌面端（`app.asar`）只作同构交叉校验，正文不展开，证据行里标「桌面端同构」。

---

## 0. 一句话

页面有**两条互不相通的远控通道**：**relay / terminal**（壳走这条）与 **token / window**（页面内嵌远控）。
业务消息类型系统只有一套（Zod 判别联合，**19 个不同 `zcode_type`**），两条通道共用它，
但传输形态不同：relay 是 `wss://zcode.z.ai/ws` 上的 `{type:'data', payload}` JSON 封套，
window 模式另有 REST 面。

relay 的**鉴权参数不在 URL 里**（旧基线的 `?sid=…&hash=…` 已作废）：URL 只带 `?mid=<deviceMid>`，
`device_sid` 在 `auth_init` 帧里，口令以 HMAC proof 在 `auth_response` 里。

---

## 1. 两条通道（先分清，否则后面所有偏移都会用错）

| 维度 | **relay / terminal**（壳用的） | **token / window**（页面内嵌远控） |
| --- | --- | --- |
| WebSocket | `wss://zcode.z.ai/ws` + `?mid=<deviceMid>` | `${relayOrigin→ws}/ws/remote-control/window/${token}` |
| 鉴权 | `auth_init(role:'terminal', device_sid, meta, client_ts)` → `auth_challenge(nonce)` → `auth_response(proof)` → `auth_ack(pair_status)` | 建连即 `ready:{windowControlSessionId, mobileConnectionId}` |
| 业务面 | 走 WS 的 `{type:'data', payload}` 封套 | 部分走 **REST**：`POST …/api/remote-control/platform/{token}`、`POST …/windows/{token}/workspace-bridge`、`POST …/windows/{token}/mobile-view-state`、`GET …/windows/{token}/bootstrap/{token}` |
| 桥 | `workspace-bridge-open` 在同一 socket 上开桥 | `workspace-bridge` 由 REST 返回 `wsUrl`（缺 `wsUrl` 直接 throw） |

**URL 形态的权威出处**（`validation-CqXZOLXO.js`）：

```js
// @212,410
Dv=`wss://zcode.z.ai/ws`, Ov=`3.4.0`;
// @213,995  relay WS 串（网页端 B6n() 传 {endpointOrigin:'https://zcode.z.ai', overrideUrl:void 0}）
function Rv(e){return e?.overrideUrl?.trim()||(e?.endpointOrigin?.trim()===`https://zcode.chatglm.site`?`wss://zcode.chatglm.site/ws`:Dv)}
// @212,446  版本闸：kv(appVersion) 为真 ⇒ 路由 v4，否则 v3
function kv(e){ … let o=[Number(t[1]),Number(t[2]),Number(t[3])],s=Ov.split(`.`).map(Number); … }
// @214,580  Uv(...) 返回 { origin, apiBaseUrl, remoteUrl:`${n}/remote/${kv(appVersion)?'v4':'v3'}`, …, relayWsUrl:`${wssOrigin}/ws`, … }
```

- 网页端取串：`index-BGa3KiRe.js` @**657,2333** `function B6n(){return Se({endpointOrigin:'https://zcode.z.ai'?.trim()||…, overrideUrl:void 0})}`
  （`Se` = `validation` 导出的 `b` = 上面的 `Rv`）。
- `mid` 注入：@**652,2931** `this.options.deviceMid?.trim()&&e.searchParams.set('mid',this.options.deviceMid)`。
- **`sid` / `hash` 不再出现在 URL 里**：`device_sid` 是 `auth_init` 的字段（@652,3326），
  `passHash` 参与 `auth_response.proof`（@652,6393）。
- window 模式入口仍读 `remoteControlToken` / `relayOrigin`（白名单 `N4n=new Set([\`remoteControlToken\`,\`relayOrigin\`])` @649,3416；
  读取点 @655,4034、@658,4833），
  REST 面 @655,6363（platform）、@655,6817（bootstrap）、@655,7368（workspace-bridge）、@655,7921（mobile-view-state）；
  `X-ZCode-Mobile-Connection-Id` 头 @655,7563 / @655,8125。

---

## 2. 传输层（relay 模式）

### 2.1 建连与握手（帧序 + 超时）

客户端类 `G3n`（@**652,2049** `var G3n=class`），构造字段与旧基线同名（`state`、`intentionallyClosed`、
`wasPaired`、`hadPairedSession`、`staleWaitingCount`、`reconnectAttempt`、`socketGeneration`、
`lastPairedSocketGeneration`、`lastPairStatusAckAt`、`pairedWaiters`、各类 timer）。

**`connect()`**（@652,2823）：`socketGeneration+=1` → `new URL(relayWsUrl)` → 可选 `?mid=` → `new WebSocket(...)`。

**`open` 事件**（@652,3037 起）按序三件事，与旧基线一致：

```js
// 1) 读扩展，只上报诊断
let e = typeof t.extensions===`string` ? t.extensions : ``
this.emitDiagnostic({type:`socket-extensions`, state:this.state, perMessageDeflate:e.includes(`permessage-deflate`), ...(e?{extensions:e}:{})})
// 2) 进认证态
this.setState(`authenticating`)
// 3) 发握手首帧（@652,3326）
this.send({type:`auth_init`, role:`terminal`, device_sid:this.options.deviceSid,
           meta:{platform:`web`, version:this.options.appVersion??`web`, name:`mobile-browser`},
           client_ts:Date.now()})
```

**入站 `handleMessage`**（@**652,6151**）：

```js
if (B3n(e) > At.maxPhysicalFrameBytes) { this.options.onRawTransportFault?.(`remote.rpcFrame.envelopeTooLarge`); return }   // @652,6223
let t = await V3n(e)                       // JSON.parse，非对象或没有 type ⇒ null
if (t) switch (this.logRelayTrace(`recv`, W3n(t)), t.type) {
  case `auth_challenge`: this.send({type:`auth_response`, device_sid:…, proof: await authProvider.calculateProof(passHash, t.nonce, `terminal`, deviceSid), client_ts:Date.now()}); break   // @652,6361 / @652,6393
  case `auth_ack`:
  case `pair_status_ack`: this.applyPairStatus(t.pair_status); break        // @652,6595
  case `data`:  this.handleDataPayload(t.payload); break                    // @652,6674
  case `error`: this.handleRelayError(t.code, t.message); break
}
```

**proof 算法**（@**652,0227** `async function L3n(e,t,n,r)`）：`HMAC-SHA-256`，
key = `passHash`，message = `` `${nonce}|${role}|${deviceSid}` ``，输出 base64url 去 padding
（`I3n` = `btoa(...).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'')`，@652,0110）。

**顶层消息类型**（页面自己 trace 时保留的形状，`W3n` @**652,1802**）：

| 顶层 `type` | 保留字段 | 方向 |
| --- | --- | --- |
| `auth_init` | `role` | 出站 |
| `auth_challenge` | ——（带 `nonce`） | 入站 |
| `auth_response` | —— | 出站 |
| `auth_ack` | `pair_status` | 入站 |
| `pair_status_query` | —— | **出站心跳** |
| `pair_status_ack` | `pair_status` | 入站心跳应答 |
| `data` | `payload`（经 `H3n` @652,1390 精简为 `{zcode_type, requestId, bridgeSessionId}`） | 双向业务封套 |
| `error` | `code` | 入站错误 |
| `socket-extensions` | —— | 仅诊断 |

> 顶层判别只看 `type`；业务判别只看 `payload.zcode_type`（`H3n` 只保留三个业务键）。

**握手超时**：没有独立的「握手超时」定时器。握手/等待阶段只有下面几个超时，**默认值全部可被
`options` 覆盖**：

| 定时器 | 默认值 | 位置 | 到点行为 |
| --- | --- | --- | --- |
| `waitingTimeoutMs` | **30 s** | @652,9635 `startWaitingTimer()` | `state==='waiting'` ⇒ 终态 `invalid-mobile-connection` |
| `desktopOfflineGraceMs` | **15 s** | @652,7919 `recoverFromDeviceOffline` | 终态 `desktop-disconnected` |
| `healthCheckTimeoutMs` | **3 s** | @652,4997 `recoverConnection` | 只用在校验「挂起的 socket 是否已重新配对」 |
| `recoverConnection` 入参 | **15 s** | 同上 | `waitForPaired` 超时 |
| `waitForPaired(e=15e3)` | **15 s** | @653,2727 | reject `…did not recover before timeout.` |

### 2.2 心跳（10s / 30s 复核结论：**仍成立，且抖动数值已定位**）

```js
// @652,8435
startHeartbeat(){ this.heartbeatTimer || (this.armHeartbeatAckWatchdog(), this.scheduleHeartbeat()) }
// @652,8531
scheduleHeartbeat(){ this.heartbeatTimer = setTimeout(() => {
    this.heartbeatTimer = void 0
    !(this.state!==`paired` && this.state!==`waiting`) && (this.sendPairStatusQuery(), this.scheduleHeartbeat())
  }, Hre(this.options.heartbeatIntervalMs ?? 1e4, this.options.heartbeatJitterMs)) }
// @652,8794
armHeartbeatAckWatchdog(){ !(this.state!==`paired` && this.state!==`waiting`) || (
    this.heartbeatAckWatchdogTimer && clearTimeout(this.heartbeatAckWatchdogTimer),
    this.heartbeatAckWatchdogTimer = setTimeout(() => {
      this.heartbeatAckWatchdogTimer = void 0
      !(this.state!==`paired` && this.state!==`waiting`) && this.reconnectAfterStaleWaiting(this.getReconnectJitterMs())
    }, this.options.heartbeatAckTimeoutMs ?? 3e4)) }
// @652,9345  出站心跳帧
sendPairStatusQuery(){ return this.send({type:`pair_status_query`, device_sid:this.options.deviceSid, client_ts:Date.now()}) }
// @652,9184
getReconnectJitterMs(){ let e=Mi(this.options.heartbeatIntervalMs??1e4, this.options.heartbeatJitterMs); return sn(this.options.reconnectJitterMs ?? Math.min(e,2e3)) }
```

**抖动函数的真实数值**（`src-AL-_ONNp.js`，`index` 里的 `Hre`/`Mi`/`sn` 就是这里的 `x`/`S`/`C`）：

```js
// @64,492  var W=1e4, Wd=2e3, Gd=3e4, Kd=2e3;
function qd(e){ … Math.min(.999999999, Math.max(0,t)) }                    // @64,519
function Jd(e=W,t){ let n=…||W, r=Math.min(Wd, Math.floor(n*.2)), i=t??r; … return Math.min(Math.floor(i), Math.max(0,n-1)) }  // @64,607 ⇒ 导出名 Mi
function Yd(e=W,t,n=Math.random){ let r=…||W, i=Jd(r,t), a=Math.max(1,r-i), o=r+i; return a+Math.floor(qd(n)*(o-a+1)) }        // @64,785 ⇒ 导出名 Hre
function Xd(e=Kd,t=Math.random){ … return Math.floor(qd(t)*(n+1)) }        // @64,933 ⇒ 导出名 sn
```

| 项 | 值 | 来源 |
| --- | --- | --- |
| 心跳周期基准 | **10 000 ms** | `heartbeatIntervalMs ?? 1e4` |
| 抖动幅度 | **`min(2000, floor(interval*0.2))` = 2000 ms**（默认周期下） | `Jd` / `Wd=2e3` |
| 实际心跳间隔 | **8 000 – 12 000 ms** 均匀分布 | `Yd` |
| ack 看门狗 | **30 000 ms** | `heartbeatAckTimeoutMs ?? 3e4` |
| 看门狗超时后的重连延迟 | `sn(min(2000,2000))` = **0 – 2000 ms** 均匀 | `getReconnectJitterMs` + `Xd` |
| 心跳启停条件 | 仅 `state ∈ {paired, waiting}` 发心跳；`applyPairStatus` 每次应答都重新武装看门狗 | @652,6784 |

> **复核结论**：旧基线「心跳 10 s / ack 超时 30 s 两侧对称」**成立**，且现在能给出抖动上限（±2 s）。
> 另外：`sendPairStatusQuery` 只在 `paired` / `waiting` 两态发出，`authenticating` 期间不发。

### 2.3 重连退避与状态迁移

```js
// @653,1042
scheduleReconnect(e){ if(this.reconnectTimer) return
  let t = e?.immediate ? 0 : Math.min(1e4, 500 * 2**this.reconnectAttempt)
  this.reconnectAttempt += 1
  this.setState(`reconnecting`); this.emitDiagnostic({type:`recover-scheduled`, state:this.state})
  this.reconnectTimer = setTimeout(() => { … this.staleWaitingCount=0, this.wasPaired=!1; … this.connect() }, t) }
// @653,0898
reconnectNow(){ let e=this.socket; this.socket=void 0; e?.close(); this.setState(`reconnecting`); this.lastPairStatusAckAt=Date.now(); this.connect() }
// @653,0444
reconnectAfterStaleWaiting(e=0){ … e>0 ? (换 socket + setTimeout(connect, e)) : this.reconnectNow() }
```

| 项 | 值 |
| --- | --- |
| 退避公式 | `min(10 000, 500 × 2^reconnectAttempt)` ms |
| 第 1/2/3/4/5 次 | 1 000 / 2 000 / 4 000 / 8 000 / 10 000 ms（之后恒 10 s） |
| 立即重连 | `{immediate:true}` ⇒ 0 ms（`INTERNAL`、`DEVICE_OFFLINE` 走这条） |
| `reconnectAttempt` 归零点 | `applyPairStatus('matched')`、`recoverConnection`、`start()` |

**`applyPairStatus`（@652,6784）——配对状态机的唯一入口**：

```js
applyPairStatus(e){
  this.lastPairStatusAckAt=Date.now(); this.armHeartbeatAckWatchdog()
  this.emitDiagnostic({type:`pair-status`, pairStatus:e, state:this.state})
  if (e===`waiting`) {
    if (this.wasPaired) { …清全部 timer…; this.setState(`waiting`); this.startHeartbeat(); return }   // 配对后掉回 waiting：继续心跳
    this.stopHeartbeat(); this.setState(`waiting`); this.startWaitingTimer(); return                  // 首次 waiting：起 30 s 终态计时
  }
  if (e===`matched`) {
    let t = this.lastPairedSocketGeneration===0 ? null : (t===this.socketGeneration ? `same-socket` : `reconnected-socket`)
    …清全部 timer…; this.setState(`paired`); this.wasPaired=!0; this.hadPairedSession=!0
    this.lastPairedSocketGeneration=this.socketGeneration; this.startHeartbeat()
    t && this.options.onSendReady?.({kind:t})                                                         // ⇒ 页面据此判定"要重放未 ack 的帧"
    return } }
```

- `pair_status` 取值只有 **`waiting` / `matched`** 两个（schema 侧同源：`mobile-diagnostic.pairStatus: M(['waiting','matched'])`，`validation` @286,835）。
- `reconnected-socket` ⇒ 页面调用 `g?.replayUnacknowledged()`（@657,8741 `onSendReady`），
  即 **socket 换代后未 ack 的帧按原样重放**（不是重发整桥）。
- **`staleWaitingCount` 在 3.15.3 是死代码**：13 处引用全是 `=0`（@6522153、6522666、6524608、6525209、
  6525786、6526984、6527400、6530225、6530595、6531406、6531659、6531932、6532450），**没有任何自增**；
  `scheduleStaleWaitingRecovery()` 只有定义（@652,9990）**没有任何调用点**。
  ⇒ 旧基线里"stale waiting 计数 + 延迟恢复定时器"这一对**已失效**；
  但 `reconnectAfterStaleWaiting` 本身仍活（看门狗直接调它），`sendPayloadResult` 也仍在读
  `staleWaitingCount > 0`（@652,4409），因恒为 0 故不构成实际闸门。

### 2.4 出站闸门

```js
// @652,4409  sendPayloadResult(e)
let t = this.payloadSerializer.prepare(e)                     // @648,2212 h4n：{type:'data', payload, client_ts} + JSON.stringify，WeakMap 缓存
return this.payloadSerializer.isOversize(t)
  ? {kind:`oversize`, bytes:t.bytes, maxBytes:At.maxPhysicalFrameBytes}
  : this.state!==`paired` || this.staleWaitingCount>0 ? {kind:`unavailable`}
  : this.sendSerialized(t.message, t.json) ? {kind:`sent`, bytes:t.bytes} : {kind:`unavailable`}
// @653,3908  send(e)：socket 未 OPEN 或 UTF-8 字节数 > maxPhysicalFrameBytes ⇒ false（静默不发）
```

> 出站业务帧在 `state!=='paired'` 时**不发**（不是排队）。`oversize` 与 `unavailable` 的区分由调用方
> （桥层 `sendFrame`）转成 `remote.rpcFrame.envelopeTooLarge` 异常（@657,7691 起 `new G3n({…})` 的 `sendFrame`）。

### 2.5 挂起 / 恢复（页面生命周期）

```js
// @652,4736  suspend()：只在 paired/connecting/reconnecting 生效 ⇒ state='suspended'，清全部 timer
// @652,4997  async recoverConnection(e=15e3)：先尝试"复用仍 OPEN 的 socket + 立即 pair_status_query"，
//            waitForPaired(healthCheckTimeoutMs ?? 3e3)；失败则 reconnectAttempt=0 + reconnectNow() + waitForPaired(e)
// @652,5536  recoverInBackground()：intentionallyClosed / paired / kicked 之外一律 reconnectNow()
```

事件源（`Y3n` @**653,6133**）：`visibilitychange`（hidden ⇒ suspend；visible ⇒ recover）、
`freeze` / `resume`（Document）、`pagehide` / `pageshow`、`online`（Window）。

---

## 3. 业务层封套

**传输封套**（`validation` @**130,007**，`.strict()`）：

```js
O({ type:N(`data`), payload: kd /* = k([Dd, Od]) = union(rpc-frame, rpc-frame-ack) */,
    client_ts: Sd.optional(), server_ts: Sd.optional() }).strict()
```

**关键双域辨析**（与旧基线一致）：`type:'data'` 有两个层次——

1. **传输封套**：`payload` 只允许 `rpc-frame` / `rpc-frame-ack`。判定 `U3n(e)`（@652,1670）
   `t=e.zcode_type; return t==='rpc-frame'||t==='rpc-frame-ack'`；命中走 `onRawTransportPayload`
   （= `g?.acceptPayload(e) ?? false`，@657,7824），否则走 `handleDataPayload` 的 `dt(e)` → `onPayload`。
2. **业务载荷**：`{zcode_type:'bootstrap-response', …}` 这类对象**自身就是完整消息**，以
   `{type:'data', payload:<业务对象>}` 送出。

**业务帧解析失败是静默的**（@652,8315 `handleDataPayload`）：

```js
handleDataPayload(e){
  if (U3n(e) && this.options.onRawTransportPayload?.(e)) return
  let t = dt(e)                    // dt = validation 的 dS：uS.safeParse(e)，失败返回 null
  t && this.options.onPayload(t)   // null ⇒ 什么都不做，无错误回执
}
```

> `dt` = `validation` @**287,156** `function dS(e){let t=uS.safeParse(e);return t.success?t.data:null}`。
> **校验失败的入站业务帧被静默丢弃**——这条对壳侧很重要（§5 末）。

---

## 4. 业务 `zcode_type` 全集

判别联合 `uS`（`validation` @**284,627** 起，21 个臂 / **19 个不同值**）：

| # | `zcode_type` | 定义 off | 必填键（除 `zcode_type`） | 方向 | 作用 |
| --- | --- | --- | --- | --- | --- |
| 1 | `telemetry-report` | 284,627 | `event`（`Sl`，`elementName:'session_create'` 等） | → 桌面 | **新增**：客户端遥测上报（@658,1909 `reportTelemetryEvent`） |
| 2 | `bootstrap-request` | 284,683 | `requestId` | → 桌面 | 配对后取全量快照 |
| 3 | `bootstrap-response` | 284,734 | `requestId, success:true, result`（`sS`） | ← 桌面 | 全量快照 |
| 4 | `workspace-list-request` | 284,810 | `requestId` | → 桌面 | 刷新工作区列表 |
| 5 | `workspace-list-response` | 284,866 | `requestId, success:true, result`（`cS`） | ← 桌面 | 列表结果 |
| 6 | `workspace-list-updated` | 284,947 | `result`（`cS`） | ← 桌面 | 无 `requestId` 的推送，整份替换 |
| 7 | `workspace-bridge-open` | 285,001 | `requestId, bridgeSessionId, workspaceKey`；`bridgeGeneration?` `recoveryId?` `taskId?` | → 桌面 | 开桥 |
| 8 | `workspace-bridge-ready` | 285,187 | `requestId, bridgeSessionId, bridge`（`iS`，按 `kind` 判别） | ← 桌面 | 桥就绪 |
| 9 | `workspace-bridge-error` | 285,349 | `requestId, reason`（`rS`）, `error`；`bridgeSessionId?` | ← 桌面 | 桥失败 |
| 10 | `workspace-reconnect-request` | 285,532 | `requestId, workspaceKey` | → 桌面 | 重连工作区 |
| 11 | `workspace-reconnect-response` | 285,608 / 285,699 | `…, success:true` ｜ `…, success:false, error` | ← 桌面 | 两臂 |
| 12 | `mobile-view-state-update` | 285,800 | `viewState`（`aS`）；`deviceInfo?`（`oS`） | → 桌面 | 上报"我在看哪个工作区/任务" |
| 13 | `platform-request` | 285,884 | `requestId, method`（`lS` 七值）；`args?` | → 桌面 | 平台能力调用 |
| 14 | `platform-response` | 285,964 / 286,050 | `…, success:true, result` ｜ `…, success:false, error` | ← 桌面 | 两臂 |
| 15 | `rpc-frame` | 129,154（`Dd`） | 见 §5 | 双向 | 桥内 RPC 的传输分片 |
| 16 | `rpc-frame-ack` | 129,854（`Od`） | `bridgeSessionId, ackMessageSeq`；`bridgeGeneration?` `recoveryId?` | 双向 | 分片回执 |
| 17 | `bridge-degraded` | 286,141 | `bridgeSessionId, reason`；`seq?` `expectedSeq?` `droppedCount?` | ← 桌面 | 桌面端主动告知"该桥降级" |
| 18 | `app-error` | 286,493 | `reason`（`rS`）, `error`；`requestId?` `bridgeSessionId?` | ← 桌面 | 应用级错误 |
| 19 | `mobile-diagnostic` | 286,597 | `event`（7 值）, `timestamp` + 12 个可选键 | → 桌面 | 客户端自我诊断上报 |

**增删核对（对旧基线）**：

- **新增 1 个**：`telemetry-report`（第 1 项）。
- **删除 0 个**：旧基线 18 个值全部保留，字段有微调（见下）。
- 旧文档标题写"19 值"：按旧构建 `src-wmk2orCZ.js` 逐条枚举，**实际只有 18 个不同值**
  （旧文档把 `workspace-reconnect-response` / `platform-response` 的两臂按行重复计了一次）。
  新构建 **19 个不同值 = 旧 18 + `telemetry-report`**。

**字段级变化（相对旧基线）**：

| 帧 | 变化 |
| --- | --- |
| `workspace-bridge-open` / `-ready` / `-error` / `bridge-degraded` | 全部新增可选 `bridgeGeneration`（`w().int().nonnegative()`） |
| `platform-response` | 新增必填 `method: lS`（旧基线无） |
| `bootstrap-response.result`（`sS`） | 新增 `desktopAppVersion?`、`initialViewState?`、`mobileViewState?` |
| `mobile-view-state-update.deviceInfo`（`oS`） | 新增 `screen?{width,height}`、`timezone?`、`online?` |
| `workspace-reconnect-response` | 未变（仍两臂） |

**枚举取值（逐字取自 schema）**：

```js
// rS —— app-error / workspace-bridge-error 共用的失败原因（validation @283,087）
[`session-not-found`,`session-expired`,`session-conflict`,`workspace-closed`,`desktop-disconnected`,
 `invalid-mobile-connection`,`desktop-bootstrap-timeout`,`connection-recovery-timeout`,
 `relay-unavailable`,`unsupported-action`,`unexpected-error`]                       // 11 值

// bridge-degraded.reason（validation @286,269）
[`rpc-transport-fault`,`rpc-frame-gap`,`buffer-overflow`,`buffer-timeout`]           // 4 值

// mobile-diagnostic.event（validation @286,631）
[`state-transition`,`socket-close`,`socket-error`,`recover-start`,`recover-scheduled`,`pair-status`,`failure`]
// mobile-diagnostic 可选键：state, previousState, pairStatus:['waiting','matched'], closeCode, closeReason,
//   wasClean, wasPaired, failureReason, failureMessage, visibilityState, online, hiddenDurationMs

// lS —— platform-request.method（validation @284,450），与旧基线同为 7 值
[`isDockerAvailable`,`listWSLDistros`,`listDockerContainers`,`listSSHConfigAliases`,
 `loadMcpFromUserDirectory`,`saveMcpToUserDirectory`,`migrateLegacyCommonMcp`]

// iS —— workspace-bridge-ready.bridge，按 kind 判别（validation @283,340）
{kind:'local',  bridgeSessionId, bridgeGeneration?, recoveryId?, workspaceKey, workspacePath, initialTaskId?}
{kind:'remote', bridgeSessionId, bridgeGeneration?, recoveryId?, workspaceKey, workspacePath, workspaceIdentity, remoteSessionId, initialTaskId?}
```

**结果对象形状**：

```js
sS = { windowControlSessionId, desktopAppVersion?, workspaces:tS, tasks:nS,
       initialViewState?:aS, mobileViewState?:aS }                       // @284,202
cS = { workspaces:tS, tasks?:nS, activeWorkspaceKey?, activeTaskId? }    // @284,350
aS = { activeWorkspaceKey?, activeTaskId?, updatedAt:finite }            // @283,744
oS = { platform, version, name, userAgent?, language?, languages?, browserPlatform?,
       viewport?:{width,height,devicePixelRatio}, screen?:{width,height},
       timezone?, online?, updatedAt:finite }                            // @283,833
tS 元素 = { workspacePath, workspaceIdentity?, remoteSessionId?, label,
            workspacePurpose?:['project','conversation'], kind:['local','remote'],
            connectionState?:['connected','disconnected','reconnecting'] } // @282,217
nS 元素 = { taskId, title, workspacePath, workspaceIdentity?, remoteSessionId?, workspaceLabel,
            workspaceKind:['local','remote'], createdAt, updatedAt, provider?,
            unreadAt?, displayStatus?:['idle','running','completed','error'],
            hasBackgroundWork?, workflowActivity?, pinned?, archived? }   // @282,649
```

> `oS` 的网页端构造器是 `o6n`（@**654,8210**）：`platform:'web'`、`name:'mobile-browser'`、
> `version` 取 `appVersion ?? 'web'`，并补 `navigator.userAgent/language/languages/platform`、
> `viewport{innerWidth,innerHeight,devicePixelRatio}`、`screen{width,height}`、
> `Intl.DateTimeFormat().resolvedOptions().timeZone`、`navigator.onLine`、`updatedAt`。
> 壳侧要发 `mobile-view-state-update` 时**至少**要能提供 `platform/version/name/updatedAt` 四项。

**网页端消费分支**（@657,7691 `new G3n({…onPayload…})` 的 `onPayload`）：

```js
onPayload:e=>{ if(!a){
  if (e.zcode_type===`rpc-frame`||e.zcode_type===`rpc-frame-ack`) { g?.acceptPayload(e); return }
  if (!M.acceptPayload(e)) {                                   // M = J3n 的 requestId 匹配器
    if (e.zcode_type===`workspace-list-updated`)     { S(e.result); return }
    if (e.zcode_type===`bridge-degraded`)            { e.bridgeSessionId===g?.getBridgeSessionId() && (g?.markDegraded(), A(e.reason)); return }
    if (e.zcode_type===`workspace-bridge-error`)     { e.bridgeSessionId===g?.getBridgeSessionId() && (g?.markDegraded(), A(e.reason)); return }
    e.zcode_type===`app-error` && E(q9(e.reason, e.error)) } } }
```

> 两条可读出的设计约束（与旧基线同）：① `bridge-degraded` 与 `workspace-bridge-error` **按
> `bridgeSessionId` 过滤**；② 二者处置都是 `markDegraded()` + `A(reason)` ⇒ **"降级即走恢复通路"**。

---

## 5. `rpc-frame` 分片与重组（含三方数值对照）

### 5.1 帧 schema 与两条跨字段约束

```js
// validation @129,154
var Dd=O({ zcode_type:N(`rpc-frame`), bridgeSessionId:Cd, bridgeGeneration:Sd.optional(), recoveryId:Cd.optional(),
  seq:xd, messageSeq:xd,
  fragmentIndex: w().int().nonnegative().max(bd.maxFragments-1),
  fragmentCount: w().int().positive().max(bd.maxFragments),
  messageBytes:  w().int().positive().max(bd.maxMessageBytes),
  checksum: Td, dataBase64: S().min(4).max(bd.maxPhysicalFrameBytes).refine(Ed)
}).strict().superRefine((e,t)=>{
  e.fragmentIndex>=e.fragmentCount && t.addIssue({code:`custom`,path:[`fragmentIndex`],message:`fragmentIndex must be smaller than fragmentCount`})
  e.fragmentCount > e.messageBytes && t.addIssue({code:`custom`,path:[`fragmentCount`],message:`non-empty fragments cannot exceed message bytes`}) })

// validation @129,854
var Od=O({ zcode_type:N(`rpc-frame-ack`), bridgeSessionId:Cd, bridgeGeneration:Sd.optional(), recoveryId:Cd.optional(), ackMessageSeq:xd }).strict()
```

| 子校验器 | 定义 off | 原文 | 说明 |
| --- | --- | --- | --- |
| `xd` | 128,712 | `w().int().positive().max(2**53-1)` | `seq`/`messageSeq`：**0 非法**，上界 2^53−1 |
| `Sd` | 128,751 | `w().int().nonnegative().max(2**53-1)` | `bridgeGeneration`：0 合法 |
| `Cd`（物理帧用） | 128,791 | `S().min(1).max(bd.transportIdMaxChars).regex(/^[A-Za-z0-9._~-]+$/)` | **不 trim**、字符集受限、≤256 |
| `eS`（业务帧用） | 282,147 | `$.max(Uu.transportEnvelopeIdMaxChars).regex(/^[A-Za-z0-9._~-]+$/)`，其中 `$=S().trim().min(1)`（@282,127） | **会 trim** ⇒ **与 `Cd` 不是同一个校验器** |
| `Td` | 128,954 | `O({algorithm:N('crc32'), value:S().regex(/^[0-9a-f]{8}$/)}).strict()` | **小写 hex、恰好 8 位**，多一个键就 `.strict()` 失败 |
| `Ed` | 129,026 | `if(e.length<4\|\|e.length>bd.maxPhysicalFrameBytes) return false; let t=yd(e); return t!==null&&t.byteLength>0&&vd(t)===e` | `dataBase64`：长度 4 B–1 MiB，**解码后非空 且 重编码等于原串** ⇒ 规范的标准 base64（带 padding） |
| `wd` | 128,862 | `O({bridgeSessionId:Cd, bridgeGeneration:Sd.optional(), recoveryId:Cd.optional()}).strict()` | 身份三元组校验器 |

### 5.2 两个预算对象必须分清（**别混用**）

```js
// ① 线上线格式的硬预算 bd —— validation @128,548
var bd={ maxPhysicalFrameBytes:Uu.maxFrameBytes, maxMessageBytes:16*1024*1024, maxFragments:64,
         assemblyTimeoutMs:3e4, transportIdMaxChars:Uu.transportEnvelopeIdMaxChars }

// ② 协议常量对象 Uu —— validation @117,018（供"逻辑帧/订阅缓冲"用，不是 rpc-frame 的上限）
Uu={ maxFrameBytes:1024*1024, logicalFrameAssemblyMaxBytes:16*1024*1024,
     logicalFrameAssemblyMaxFragments:1024, logicalFrameAssemblyMaxConcurrent:32,
     logicalFrameAssemblyMaxStagedBytes:32*1024*1024, logicalFrameAssemblyTimeoutMs:3e4,
     transportEnvelopeIdMaxChars:256, subscriberBufferMaxOps:500, subscriberBufferMaxBytes:1024*1024,
     eventRetentionPerSession:2e3, snapshotTailWindowRows:60, rowsRangeMaxLimit:200, … }
```

| 概念 | 常量 | 值 | 谁在用 |
| --- | --- | --- | --- |
| **物理帧**（relay 分片）上限 | `bd.maxFragments` | **64** | `rpc-frame.fragmentIndex/fragmentCount` 的 `.max()` |
| **逻辑帧**（桥内 topic 流）上限 | `Uu.logicalFrameAssemblyMaxFragments` | **1024** | 桥内流式装配，**与 `rpc-frame` 无关** |
| 物理帧消息上限 | `bd.maxMessageBytes` | **16 MiB** | `rpc-frame.messageBytes` |
| 逻辑帧装配上限 | `Uu.logicalFrameAssemblyMaxBytes` | 16 MiB | 桥内 |
| 物理帧信封上限 | `bd.maxPhysicalFrameBytes` = `Uu.maxFrameBytes` | **1 MiB** | `dataBase64` + 出站封套闸门 |
| 装配超时 | `bd.assemblyTimeoutMs` | **30 000 ms** | `rpc-frame` 重组 |
| 传输 id 长度 | `bd.transportIdMaxChars` = `Uu.transportEnvelopeIdMaxChars` | **256** | `Cd` / `eS` |

> **`1024` 不是 `rpc-frame` 的分片上限。** 写代码时误用 1024 会让对端在 schema 层直接拒收
> （`fragmentCount > 64`）。
> 另外 `validation` 里的收端构造器**只能把上限调低、不能调高**：
> `Nd(e,t,n) = Math.min(e ?? t, t)`（@130,430）；`f4n`（@647,4093）也把
> `replayBufferMaxBytes ≤ 8 MiB`、`replayBufferGraceMs ≤ 45 s`、`assemblyTimeoutMs ≤ 30 s` 夹住。

### 5.3 网页端的分片算法：**不是固定 512 KiB，是二分求最大可容纳字节数**

```js
// validation @127,600  gd(e)  —— crc32（返回 8 位小写 hex）
// validation @127,831  vd(e)  —— base64 编码（标准字母表 + padding，`_d='…+/'`）
// validation @128,113  yd(e)  —— base64 解码（先过 hd schema，非规范串返回 null）
// validation @130,095  Ad(e) = new TextEncoder().encode(e).byteLength
// validation @130,156  jd(e,t) = {type:'data', payload:e, client_ts:t??2**53-1, server_ts:t??2**53-1}
// validation @130,378  Md(e,t) = Ad(JSON.stringify(jd(e,t)))        ← 量的是【整条 relay 信封】
// validation @131,062  Rd(e)=4*Math.ceil(e/3)                       —— base64 编码后长度
// validation @131,101  zd(e) —— 构造一个 rpc-frame 对象
// validation @131,327  Bd(e) —— 二分：给定"信封头 + base64 开销"预算，求单片最大原始字节数
function Bd(e){
  let t=Md(zd({identity:e.identity,seq:e.endSeq,messageSeq:e.messageSeq,
               fragmentIndex:e.fragmentCount-1,fragmentCount:e.fragmentCount,
               messageBytes:e.messageBytes,checksum:e.checksum,dataBase64:``})),   // 空 data 的【整条信封】字节数
      n=0, r=e.messageBytes
  while(n<r){ let i=Math.ceil((n+r)/2); t+Rd(i)<=e.maxPhysicalFrameBytes ? n=i : r=i-1 }
  return n }
// validation @131,648  Vd(e,t) —— 出站分片主循环
let r = Nd(t.maxPhysicalFrameBytes, bd.maxPhysicalFrameBytes, `remote.rpcFrame.invalidPhysicalLimit`),
    i = Nd(t.maxMessageBytes,     bd.maxMessageBytes,     `remote.rpcFrame.invalidMessageLimit`),
    a = Nd(t.maxFragments,        bd.maxFragments,        `remote.rpcFrame.invalidFragmentLimit`)
if (e.byteLength===0) throw new Fd(`remote.rpcFrame.emptyMessage`)
if (e.byteLength>i)   throw new Fd(`remote.rpcFrame.messageTooLarge`)
let o = Object.freeze({algorithm:`crc32`, value: gd(e)})      // ← crc32 覆盖【整条消息】
let s=1, c=0
for(;;){ let i=t.firstPhysicalSeq+s-1
  if(!Number.isSafeInteger(i)||i>2**53-1) throw new Fd(`remote.rpcFrame.sequenceOverflow`)
  if((c=Bd({identity:n,endSeq:i,messageSeq:t.messageSeq,fragmentCount:s,messageBytes:e.byteLength,
            checksum:o,maxPhysicalFrameBytes:r}))<1) throw new Fd(`remote.rpcFrame.envelopeTooLarge`)
  let l=Math.ceil(e.byteLength/c)
  if(l>a) throw new Fd(`remote.rpcFrame.fragmentLimitExceeded`)
  if(l<=s) break
  s=l }                                                        // 迭代到"片数 × 片长"自洽
// 逐片：dataBase64 = vd(e.subarray(i*c, min(len, i*c+c)))
```

| 项 | 结论 |
| --- | --- |
| 分片尺寸 | **动态**：`maxPhysicalFrameBytes` 减去该帧**整条 relay 信封**（`{type:'data',payload:{…dataBase64:''},client_ts}`，含 16 位默认 `client_ts`）后的剩余预算；默认量级 **约 786 KB（≈768 KiB）原始字节**，随 `bridgeSessionId`/`recoveryId` 串长略变，**不是常量** |
| 片数 | `ceil(messageBytes / 片长)`，**必须 ≤ 64** |
| 序号 | `seq` 逐片 +1 且**跨消息连续**（`firstPhysicalSeq` 由上层 `nextPhysicalSeq` 传入）；`messageSeq` 每条逻辑消息 +1 |
| CRC | `crc32`，**覆盖整条重组后的消息**（不是单片）；`value` 为 8 位小写 hex |
| 出站并发 | 单条消息的分片按序发出；不同消息**不交错**（`outboundBatches` 队列逐条推进，`nextUnsent` 语义） |

### 5.4 入站重组（`validation` @133,713 `var Kd=class`，收端状态机）

```js
nextExpiryAt(){ return this.active ? this.active.firstSeenAt + this.timeoutMs : null }        // 30 s
fault(e,t,n){ … return {kind:`fault`, fault:{reasonCode:e, terminal:t, seq?, messageSeq?, expectedSeq, expectedMessageSeq}} }
expire(e=now()){ return !this.active || e-this.active.firstSeenAt < this.timeoutMs ? null
                       : this.fault(`remote.rpcFrame.assemblyTimeout`, true, {messageSeq:this.active.messageSeq}) }
accept(e,t=now()){
  if (Ud(this.identity,e))     return this.fault(`remote.rpcFrame.identityMismatch`, false)      // ← 非终态
  if (this.terminalReason)     return this.fault(this.terminalReason, true)
  … invalidBase64 / invalidMetadata / messageTooLarge / fragmentLimitExceeded / envelopeTooLarge …
  let s = Wd(a,o)                                  // 指纹：JSON.stringify([身份三元组, seq, messageSeq,
                                                   //   fragmentIndex, fragmentCount, messageBytes,
                                                   //   checksum.algorithm, checksum.value, byteLength, crc32(本片)])
  … 重复片：指纹相同 ⇒ {kind:'duplicate', ackMessageSeq}；指纹不同 ⇒ conflictingDuplicate（终态） …
  if (a.seq <  this.expectedPhysicalSeq) return {kind:`duplicate`, ackMessageSeq:…}
  if (a.seq >  this.expectedPhysicalSeq) return this.fault(`remote.rpcFrame.physicalGap`, true, a)
  if (a.messageSeq !== this.expectedMessageSeq) return this.fault(`remote.rpcFrame.messageGap`, true, a)
  if (a.seq===2**53-1 && a.fragmentIndex+1<a.fragmentCount) return this.fault(`remote.rpcFrame.physicalSequenceExhausted`, true, a)
  if (a.fragmentIndex !== (this.active?.fragments.length ?? 0)) return this.fault(`remote.rpcFrame.fragmentGap`, true, a)
  if (active && (fragmentCount/messageBytes 与首片不符)) return this.fault(`remote.rpcFrame.metadataMismatch`, true, a)
  if (active && checksum.value 与首片不符)               return this.fault(`remote.rpcFrame.checksumMismatch`, true, a)
  … stagedBytes+片长 > messageBytes ⇒ lengthMismatch（终态）…
  … 收齐后：stagedBytes!==messageBytes ⇒ lengthMismatch；crc32(整条)!==checksum ⇒ checksumMismatch …
  return {kind:`complete`, messageSeq, bytes} }
```

**收端硬约束（对壳侧实现直接生效）**：

| 约束 | 违反后果 |
| --- | --- |
| `seq` 必须**严格递增且连续**（无洞） | `physicalGap` ⇒ **终态降级** |
| `messageSeq` 必须**严格递增且连续** | `messageGap` ⇒ **终态降级** |
| `fragmentIndex` 必须按 0,1,2… **顺序到达** | `fragmentGap` ⇒ **终态降级**（**不允许乱序重组**） |
| 身份三元组必须与连接身份**严格相等** | `identityMismatch` ⇒ **非终态**（该帧被忽略，桥不降级） |
| 同一 `seq` 重复且指纹一致 | `duplicate` ⇒ 回 `ackMessageSeq = lastCompletedMessageSeq`（或 null） |
| 同一 `seq` 重复但指纹不同 | `conflictingDuplicate` ⇒ **终态降级** |
| 同消息内 `fragmentCount`/`messageBytes`/`checksum` 必须一致 | `metadataMismatch` / `checksumMismatch` ⇒ **终态降级** |
| 30 s 内未收齐 | `assemblyTimeout` ⇒ **终态降级** |

> `Ud(e,t)`（@133,168）：只有**出现了**三元组中任一键、且与本地不等，才算 mismatch——
> 完全不带的载荷（如 `{zcode_type:'rpc-frame-ack',bridgeSessionId:…}`）走 `Hd` 比较，同样要求严格相等。

### 5.5 三方数值对照（**本节的核结论**）

| 常量 | **线上权威**（`validation.bd`） | 桌面端（`app.asar`） | Dart（`Reference_code`） | Rust（本仓） |
| --- | --- | --- | --- | --- |
| `maxPhysicalFrameBytes` | **1 MiB** | 1 MiB（同构） | 1 MiB | 1 MiB |
| `maxMessageBytes` | **16 MiB** | 16 MiB（同构） | **64 MiB** ❌ | **64 MiB** ❌ |
| `maxFragments` | **64** | 64（同构） | **256** ❌ | **256** ❌ |
| 分片尺寸 | **动态二分**（填满 1 MiB 信封） | 同 | **固定 512 KiB** ⚠️ | 固定 512 KiB ⚠️ |
| 装配超时 | **30 000 ms** | 30 000 ms | 无（另用 60 s stale purge，30 s 定时清理） | 同 Dart |
| `transportIdMaxChars` | 256 | 256 | 无校验 | 无校验 |
| crc32 覆盖 | **整条消息** | 同 | 整条消息 ✅ | 整条消息 ✅ |
| crc 大小写/长度 | **小写 8 位 hex**（`/^[0-9a-f]{8}$/`） | 同 | ✅ `Crc32.hexOf` = `compute(bytes).toRadixString(16).padLeft(8,'0')`（`crc32.dart:26-27`，小写） | 同 ✅ |

- 线上权威出处：`validation-CqXZOLXO.js` @**128,548**（`bd` 对象，逐字见 §5.2）。
- 桌面端同构出处：`app.asar out/main/chunk-OW6EUS2G.js` @**545,824**
  `var Ue={maxPhysicalFrameBytes:Re.maxFrameBytes,maxMessageBytes:16*1024*1024,maxFragments:64,assemblyTimeoutMs:3e4,transportIdMaxChars:Re.transportEnvelopeIdMaxChars}`；
  身份严格匹配同构于 `out/main/chunk-JS2T33SG.js` @**7,843** `rawIdentityMatches`。
- Dart 出处：`Reference_code/lib/protocol/rpc_transport.dart:14/20/23/26`
  （`1024*1024` / `64*1024*1024` / `256` / `_fragmentPayloadBytes = 512*1024`）。
- Rust 出处：`rust/crates/protocol/src/rpc_transport.rs:60/66/72/78`（逐值照抄 Dart）。

**结论（壳侧 Rust 必须按此收敛）**：

1. **`maxMessageBytes` 应从 64 MiB 收到 16 MiB**，**`maxFragments` 应从 256 收到 64**。
   这两个数才是真正会被对端强制的；Dart 的 64 MiB / 256 片**超出了线上 schema 上限**。
2. 超限的后果是**静默丢弃**，不是错误回执：网页/桌面端走 `safeParse`（`dt` / `dS`，
   `validation` @287,156），`fragmentCount>64` 或 `messageBytes>16 MiB` 直接 `null`，
   `handleDataPayload` 里 `t && …` 短路，**什么都不做**。
   壳侧只能靠「发出后 45 s 内不被 ack」反推（`_9.replayBufferGraceMs = 45e3`，见 §7.2）。
3. **分片尺寸 512 KiB 本身合法**（512 KiB → base64 约 683 KiB，加信封仍 < 1 MiB），
   但它把「16 MiB 上限」压到 **32 片**（512 KiB × 32 = 16 MiB），远未触及 64 片上限 ⇒
   真正的硬闸门只有 `maxMessageBytes`。若要与网页端逐字节对齐，需改成动态二分（§5.3），
   但**不改也不违约**——收端只校验上限，不校验片长。
4. **顺序约束是硬约束**：网页端**不接受乱序分片**（`fragmentGap` 终态降级），
   也不接受 `seq` 有洞。壳侧出站必须保证 `seq` 连续、`fragmentIndex` 顺序、`messageSeq` 连续。
   Rust 现有测试 `multi_fragment_message_roundtrip` 把分片**逆序回灌**自己——那只是自测，
   与线上收端行为不符，**不能作为线上互通的依据**。

---

## 6. 桥内 RPC

桥内 RPC 由 `rpc-frame` 的**逻辑帧**承载，信封是 `ChannelClient` 四元组
`[reqType, reqId, channelName, name]` + 值编码（二进制 IPC codec），响应 `[respType, reqId]` + 数据。

**类型码**（Dart `lib/protocol/channel_client.dart:13-23`，与网页端同源）：

| 方向 | 码 | 名 |
| --- | --- | --- |
| 请求 | 100 / 101 / 102 / 103 | `reqPromise` / `reqPromiseCancel` / `reqEventListen` / `reqEventDispose` |
| 响应 | 200 / 201 / 202 / 203 / 204 | `resInitialize` / `resPromiseSuccess` / `resPromiseError` / `resPromiseErrorObj` / `resEventFire` |

- 收到 `resInitialize` ⇒ 该 channel 就绪。（Dart 注释把网页端的对应类写作 `Pne`；**新构建里该符号名
  已不存在**——`Pne` 现在是一个 `data-testid` 字符串常量，@6,167,037。此处以 Dart 侧行为为准。）
- 错误表达：`202` 的 data 是 `{message}` 或字符串；`203` 的 data 直接 `'$data'`。
  Dart 侧包装成 `ChannelRpcError(message, data)`；Rust 侧对应 `ProtocolError`。
- **默认超时 30 000 ms**（Dart `call(…, timeout: Duration(seconds: 30))`；
  Rust `channel_client.rs:319 ChannelClient::READY_TIMEOUT_MS = 30_000`）。
- 网页端的桥 socket 由 `r4n(url, {onClose, onOpenSocket})` 建立（@**647,0488**），
  `binaryType='arraybuffer'`，适配器 `n4n`（@**647,0051**），再包 `i4n`（@**647,0937**）。

**channel 名全集（48 条，权威枚举）**：

枚举定义在 `src-AL-_ONNp.js` @**35,665**（`Ic={File:'file', …}`），
经 `index-BGa3KiRe.js` 的 import 别名 `rn as za`（@6,5146）与 `SE(e){return{channelName:e}}`（@121,6025）
组合成 `SE(za.File)` 形式的描述符（描述符逐条列在 @122,4690–@125,3385）。**逐字**：

```
file                 media-preview        system                terminal
git                  git-checkpoint       setting               credential
cua-permission       cua-pip-session      broadcast             zcode-task
window-controller    zcode-agent          zcode-session         conversation-share
file-watcher         oauth                provider-settings     model-selection
provider-provisioning-target             usage-stats           coding-plan-subscription
client-config        client-scenes        cloud-content         marketing-touch
skills               skill-sync           mcp-sync              plugin-sync
plugins              plugin-management    plugin-ui-bridge      plugin-ui-sampling
plugin-ui-app-tools  subagents            commands              hooks
memory               output-style         settings-sync         bots
feedback             prompt-attachment-transfer                 off-peak-task
highspeed-card       onboarding-record    gen-ui（`V6e=SE('gen-ui')` @1,253,385，不在枚举里）
```

> 网页端的服务类（`index` @6,466,1xx–@6,468,4xx）里 `Dw.toService(e.getChannel(<描述符>.channelName))` 出现
> **24 次**（`getChannel` 在 @6,466,762–@6,468,404 共 24 处），即**枚举 48 条、本构建只挂了 24 条**。
> 壳侧只需要它实际要用的那几条；其余 channel 名只作为命名空间保留。

**方法名全集（`*V4` 族，主包逐字枚举）**：

| 域 | 方法 |
| --- | --- |
| 会话握手 | `helloConversationV4`、`initializeConversationV4` |
| 会话流 | `subscribeConversationV4`、`unsubscribeConversationV4`、`resyncConversationV4`、`conversationRowsRangeV4`、`conversationPlansV4`、`conversationFileChangesV4`、`conversationFileRewindPreviewV4` |
| 命令 | `sendConversationCommandV4`、`queryConversationCommandsV4` |
| workflow | `conversationWorkflowRunsV4`、`conversationWorkflowRunEventsV4`、`conversationWorkflowRunArtifactsV4`、`conversationWorkflowRunArtifactDataV4`、`conversationWorkflowRunArtifactReadV4`、`conversationWorkflowRunWorkspaceV4`、`conversationWorkflowRunNodeResultV4` |
| 控制器 | `subscribeControllerV4`、`unsubscribeControllerV4`、`resyncControllerV4` |
| 会话索引 | `subscribeSessionsIndexV4`、`unsubscribeSessionsIndexV4`、`resyncSessionsIndexV4` |
| 附件 | `attachmentBeginV4`、`attachmentChunkV4`、`attachmentCommitV4`、`attachmentAbortV4`、`attachmentReadV4`、`attachmentPreviewSourceV4` |
| 其他 | `backgroundBashOutputV4`、`__zcodeV4` |

**事件订阅**：`onDynamicConversationFrame`（@**1,114,324**）—— `(topic)(cb)` 返回 dispose，
监听可叠加；另有 `onDynamicControllerFrame`（@1,489,590）与 `onDynamicConversationTelemetryFact`。
> 「壳侧绝不发 `listen`（`onDynamicControllerFrame`）」是既有实测结论（真机曾把桌面端 host 打崩），
> 静态侧见 10 篇；本构建**未复核**该实测。

**页面自己的握手是懒执行 + 记忆化**（@**1,087,466** `function uw(e)`，`lw` 是 `WeakMap` @1,087,447）：

```js
let r=(async()=>{
  let t = Gae.parse(await e.helloConversationV4())                       // 有 schema 校验
  let n = {workspaceHookReviewUi:!0, ...(Hae(t.capabilities) ? {workflowRunDeltas:!0} : {})}
  return await e.initializeConversationV4({kind:`clientHello`, protocolVersion:3, clientId:q$e(),
           clientKind: t.clientMode===`desktop-continuous` ? `desktop` : `web`,
           appVersion:`unknown`, capabilities:n}), t })()
```

- **每 scope 只握手一次**，失败后可重试（`catch` 里比对同一个 promise 再删缓存）。
- `clientId` 由 `q$e()`（@**1,086,776**）生成并落 `localStorage['zcode-v4-client-id:v1']`，格式 `client-<rand>`。
- 能力位：基础 `{workspaceHookReviewUi:true}`，当桌面端 `capabilities` 通过 `Hae` 判定时追加 `workflowRunDeltas`。
  **壳侧目前不声明 `workspaceHookReviewUi`** —— 与旧基线结论一致。

**业务命令信封**（@**1,086,943** `function cw(e)`）：

```js
if (ioe.has(e.type) && e.baseRevision===void 0) throw Error(`command ${e.type} 是 CAS 命令，必须携带 baseRevision（10-protocol-spec §6.4）`)
if (noe.has(e.type) && !e.baseLogEpoch)         throw Error(`command ${e.type} 是 row target 命令，必须携带 baseLogEpoch`)
return { commandId: ua(), clientId: q$e(), sessionId,
         ...(baseRevision===undefined?{}:{baseRevision}), ...(baseLogEpoch?{baseLogEpoch}:{}),
         type, payload, issuedAt: Date.now() }
```

> `baseRevision` / `baseLogEpoch` 是**页面侧**的基线机制；壳侧对应 `resyncConversationV4` 的 `base`
> 参数与 `logEpoch` 追踪（见 18 篇）。

**桥内 `fault.*` 错误码全集（23 个，主包逐字）**：

```
fault.connection.handshakeUnavailable
fault.subscribe.unknown
fault.subscription.{initialFrameStagingOverflow, notOwned, recoveryFailed,
                   recoveryFrameTimedOut, resyncGenerationMismatch, runtimeRestarted, transportReplaced}
fault.command.{capabilityUnsupported, inputDiscardedOnRestart, queryUnavailable,
               savedWorkflowStartRejected.*, workflowRunSettingsRejected.*}
fault.attachment.{checksumUnavailable, invalidServerProgress, previewChangedDuringRead,
                  previewEmptyChunk, previewOffsetMismatch, previewSizeMismatch,
                  previewTooManyChunks, previewTruncated}
fault.runtime.hookBlocked
```

`fault.connection.handshakeUnavailable` 的触发点（@**2,035,762**）：
`!e.helloConversationV4 || !e.initializeConversationV4` ⇒ reject —— 即**桥对象上缺方法**时抛，
不是超时抛。旧基线提到的 `fault.subscription.notOwned` / `runtimeRestarted` 仍在（@1,109,900 起）。

**桥内 `proto.*` 错误码（7 个，主包逐字）**：

```
proto.frameTooLarge  proto.invalidBase64  proto.payloadTooLarge  proto.staleLogEpoch
proto.staleRevision  proto.staleTarget    proto.independentPlanUnsupported
```

`proto.independentPlanUnsupported`（@1,111,291）：桌面端 `capabilities.independentPlanState !== true`
且命令里带 `planEnabled` 时抛。

---

## 7. 错误与降级

### 7.1 relay 顶层 `error.code`（@652,6742 `handleRelayError(t,r)`）

| `code` | 网页端处置 | 终态？ |
| --- | --- | --- |
| `KICKED` | `setState('kicked')` + `enterTerminalFailure({reason:'session-conflict', message: t\|\|e})` | **是**（`intentionallyClosed=true`，不再重连） |
| `DEVICE_OFFLINE` | `recoverFromDeviceOffline(msg)`：关 socket + 立即重连，并起 **15 s** `desktopOfflineGraceMs` 计时；到点仍没恢复 ⇒ 终态 `desktop-disconnected` | 否（宽限内可恢复） |
| `AUTH_FAILED` / `WRONG_PARAM` | `enterTerminalFailure({reason:'invalid-mobile-connection'})` | **是** |
| `INTERNAL` | `state==='paired'` 或（`hadPairedSession && state==='waiting'`）⇒ `enterWaitingForDeviceAfterRelayError`（回 `waiting` + 起心跳）；否则 `recoverFromRelayInternal`（`{immediate:true}` 重连） | 否 |
| 其他 | `enterTerminalFailure({reason:'relay-unavailable'})` | **是** |

```js
// @653,3227
enterTerminalFailure(e){ this.intentionallyClosed=!0; …清全部 timer…
  this.setState(e.reason===`session-conflict` ? `kicked` : `error`)
  this.rejectPairedWaiters(Error(e.message ?? e.reason)); this.options.onFailure(e); this.socket?.close() }
```

> **「单控制端互斥」由 `KICKED` 实现**：终态、`intentionallyClosed=true` 后不重连。
> 壳侧观测到的"原生一配对、页面立刻进 `kicked`"由此得到静态印证。

### 7.2 网页端自身的降级原因码（`enterDegraded`，主包 12 处）

`enterDegraded(e)`（@**648,1470**）：置 `degraded=true`，停 deadline、清装配定时器、
清空 `outboundBatches` / `queuedInbound` / pendingAck / 未确认字节，`assembler=null`，
最后 `degradedEmitter.fire(e)`。

**传输层 reasonCode（主包字面量，11 个）**：

```
remote.rpcFrame.ackGraceExceeded     remote.rpcFrame.deliveryFailed
remote.rpcFrame.encodingFailed       remote.rpcFrame.envelopeTooLarge
remote.rpcFrame.futureAck            remote.rpcFrame.invalidPayload
remote.rpcFrame.manuallyDegraded     remote.rpcFrame.outerMeterFailed
remote.rpcFrame.replayBufferExceeded remote.rpcFrame.replayGraceExceeded
remote.rpcFrame.sendFailed
```

**装配层 reasonCode（`validation` 字面量，26 个）**：

```
remote.rpcFrame.assemblyTimeout            remote.rpcFrame.checksumMismatch
remote.rpcFrame.conflictingDuplicate       remote.rpcFrame.emptyMessage
remote.rpcFrame.envelopeTooLarge           remote.rpcFrame.fragmentGap
remote.rpcFrame.fragmentLimitExceeded      remote.rpcFrame.identityMismatch
remote.rpcFrame.internalEnvelopeError      remote.rpcFrame.invalidBase64
remote.rpcFrame.invalidFragmentLimit       remote.rpcFrame.invalidIdentity
remote.rpcFrame.invalidMessageLimit        remote.rpcFrame.invalidMessageSeq
remote.rpcFrame.invalidMetadata            remote.rpcFrame.invalidPhysicalLimit
remote.rpcFrame.invalidPhysicalSeq         remote.rpcFrame.invalidTimeout
remote.rpcFrame.lengthMismatch             remote.rpcFrame.messageGap
remote.rpcFrame.messageSequenceExhausted   remote.rpcFrame.messageTooLarge
remote.rpcFrame.metadataMismatch           remote.rpcFrame.physicalGap
remote.rpcFrame.physicalSequenceExhausted  remote.rpcFrame.sequenceOverflow
```

| 触发点 | 位置 | 语义 |
| --- | --- | --- |
| `remote.rpcFrame.envelopeTooLarge` | @652,6262（入站原始帧 > 1 MiB）、@658,1051（出站 `sendFrame` 收到 `oversize`） | 信封超 1 MiB |
| `remote.rpcFrame.ackGraceExceeded` | @647,2337（`s4n.select()`，`replayBufferGraceMs=45e3`） | **发出去的帧 45 s 不被 ack** |
| `remote.rpcFrame.replayGraceExceeded` | 同上 | 队头数据 45 s 未被释放 |
| `remote.rpcFrame.replayBufferExceeded` | @647,6791 `reserveMessage` | 未确认字节 > 8 MiB |
| `remote.rpcFrame.futureAck` | @648,0593 `processAck` | `ackMessageSeq > highestFullySentMessageSeq` ⇒ **终态** |
| `remote.rpcFrame.invalidPayload` / `deliveryFailed` | @647,9654 `processInbound` | `St(e)`（= `validation.Pd`）解析失败 / 上层消费抛错 ⇒ **终态** |
| `remote.rpcFrame.sendFailed` | @647,7881 `invokeSend` | 发送抛错 ⇒ **终态** |
| `remote.rpcFrame.outerMeterFailed` / `encodingFailed` | @647,6791 `reserveMessage` | 计长失败 / 编码失败 ⇒ **终态** |
| `remote.rpcFrame.manuallyDegraded` | @647,6589 `markDegraded(e=…)` 默认值 | 由 `onRawTransportFault` / `bridge-degraded` / `workspace-bridge-error` 触发 |

**传输层预算常量**（@**647,3220**）：

```js
_9={ saturationHighWaterMarkBytes:1024*1024, saturationLowWaterMarkBytes:256*1024,
     replayBufferMaxBytes:8*1024*1024, replayBufferGraceMs:45e3, assemblyTimeoutMs:At.assemblyTimeoutMs }
```

| 项 | 值 | 用途 |
| --- | --- | --- |
| 饱和高水位 | **1 MiB** | 未确认字节超过 ⇒ `saturated` 事件（上游暂停产出） |
| 饱和低水位 | **256 KiB** | 降到其下 ⇒ `drained` 事件 |
| 重放缓冲上限 | **8 MiB** | 超过 ⇒ `replayBufferExceeded`（终态） |
| 重放/ack 宽限 | **45 000 ms** | 队头 45 s 未释放 ⇒ `ackGraceExceeded` / `replayGraceExceeded`（终态） |
| 装配超时 | **30 000 ms**（= `bd.assemblyTimeoutMs`） | 单条消息重组 |

> **45 s 就是壳侧真机观测到的"约 45 s 后整桥判 degraded"的数值来源**，两边指向同一机制。

### 7.3 桌面端告知的降级（两条业务消息）

| 消息 | 触发者 | 语义 |
| --- | --- | --- |
| `bridge-degraded` | 桌面端 | 该桥不再可靠：`reason ∈ {rpc-transport-fault, rpc-frame-gap, buffer-overflow, buffer-timeout}`，可带 `seq / expectedSeq / droppedCount` |
| `workspace-bridge-error` | 桌面端 | 开桥失败：`reason`（`rS` 11 值）+ `error` |

页面收到后**都先按 `bridgeSessionId` 过滤**，命中即 `markDegraded()` + 走恢复通路（§4 末代码块）。

### 7.4 请求级超时（`J3n` 请求匹配器，@**653,5176**）

```js
function J3n({sendPayload:e, timeoutMs:t=1e4}){
  let n=new Map
  return { requestAppPayload(r,i,a){ return new Promise((o,s)=>{
        let c=setTimeout(()=>{ n.delete(r.requestId); s(z9(`desktop-bootstrap-timeout`,`Desktop did not respond in time.`)) }, a?.timeoutMs??t)
        n.set(r.requestId,{isMatch:i,resolve:o,reject:s,timer:c})
        e(r) || (clearTimeout(c), n.delete(r.requestId), s(z9(`relay-unavailable`,`Web remote control transport is not ready to send payload.`))) }) },
    acceptPayload(e){ if((e.zcode_type===`app-error`||e.zcode_type===`workspace-bridge-error`)&&e.requestId) return r(e.requestId, z9(e.reason,e.error))
        for(let[t,r] of n) if(r.isMatch(e)) return clearTimeout(r.timer), n.delete(t), r.resolve(e), !0
        return !1 },
    rejectAll(e){…}, dispose(){ this.rejectAll(z9(`desktop-disconnected`,`Web remote control transport closed.`)) } } }
```

| 请求 | 默认超时 | 备注 |
| --- | --- | --- |
| 通用 app payload（`bootstrap` / `platform` / `workspace-list`） | **10 000 ms** | `J3n` 的 `t=1e4` |
| `workspace-reconnect-request` | **120 000 ms** | 调用处传 `{timeoutMs: Nre}`，`Nre` = `src-AL-_ONNp.js` @62,554 `var Id=12e4` |
| 恢复重试包装 `q3n`（@653,4994） | —— | 仅当 reason ∈ {`relay-unavailable`} 或（`recoverTimeout && reason==='desktop-bootstrap-timeout'`）才重试一次 |

**请求 id 形态**（@**656,5911**）：`function X9(e){return `${e}-${crypto.randomUUID?.() ?? `${Date.now()}-${Math.random()}`}`}`；
前缀逐字：`bootstrap` / `platform` / `workspace-list` / `workspace-reconnect` / `bridge` / `workspace-bridge` / `recovery`。

**请求匹配条件（逐条，壳侧若要复用同一套 id 语义必须一致）**：

| 请求 | 匹配谓词 |
| --- | --- |
| `bootstrap-request` | `t.zcode_type==='bootstrap-response' && t.requestId===e` |
| `platform-request` | `t.zcode_type==='platform-response' && t.requestId===e && t.method===e.method` |
| `workspace-list-request` | `t.zcode_type==='workspace-list-response' && t.requestId===e` |
| `workspace-reconnect-request` | `t.zcode_type==='workspace-reconnect-response' && t.requestId===e && t.workspaceKey===e.workspaceKey` |
| `workspace-bridge-open` | `t.zcode_type==='workspace-bridge-ready' && t.bridgeSessionId===e.bridgeSessionId` |

> 注意 `workspace-bridge-open` 的匹配**只看 `bridgeSessionId`**（不看 `requestId`）——
> 因为 `requestId` 用的是 `X9('workspace-bridge')` 而 `bridgeSessionId` 是 `X9('bridge')`，两者不同。

### 7.5 信封级护栏

| 判定 | 位置 | 行为 |
| --- | --- | --- |
| 入站原始帧字节 > 1 MiB | @652,6262 | `onRawTransportFault('remote.rpcFrame.envelopeTooLarge')` ⇒ `markDegraded` |
| 入站业务帧 `safeParse` 失败 | @652,8315 | **静默丢弃**（`dt` 返回 null） |
| 出站信封 `bytes > 1 MiB` | @652,4409 | `{kind:'oversize'}` ⇒ 桥层抛 `remote.rpcFrame.envelopeTooLarge` |
| 出站时 `state!=='paired'` | 同上 | `{kind:'unavailable'}` ⇒ 桥层抛 `relay-unavailable` |
| `invokeSend` 抛错 / `degraded` / `disposed` | @647,7881 / @647,8079 | 停止发送循环 |
| `reserveMessage` 时 `deadline.check()` 为真 | @647,6791 | 直接放弃该消息 |

---

## 8. 关闭码语义

**网页端不解读 WebSocket 关闭码。** 全主包在 relay 客户端区间（字节 652,0000–653,5000）内
**没有任何 4xxx 关闭码常量**，唯一出现的数字是 `1500`（`staleWaitingRecoveryMs` 默认值）。

```js
// @652,3778  close 监听（逐字）
t.addEventListener(`close`,e=>{ if(t===this.socket){
  this.emitDiagnostic({type:`socket-close`, code:e.code, reason:e.reason, wasClean:e.wasClean,
                       wasPaired:this.wasPaired, state:this.state})            // ← 仅上报诊断
  this.stopHeartbeat(); this.clearWaitingTimer(); this.clearStaleWaitingRecoveryTimer()
  if(!this.intentionallyClosed && this.state!==`suspended`){
    if(this.hadPairedSession){ this.scheduleReconnect(); return }              // 配对过 ⇒ 退避重连
    this.setState(`error`); this.options.onFailure(z9(`relay-unavailable`,`Web remote control relay connection closed.`)) } } })
```

| 情形 | 处置 |
| --- | --- |
| 主动关闭（`intentionallyClosed`）或 `suspended` | 什么都不做 |
| **曾经配对过**（`hadPairedSession`） | `scheduleReconnect()`（退避 1 s→10 s），**不区分关闭码** |
| 从未配对过 | `state='error'` + 终态 `relay-unavailable` |
| `code` / `reason` / `wasClean` | 只进 `mobile-diagnostic` 上报，**不参与任何分支** |

> **语义全部由 relay 顶层 `error.code`（§7.1）承担，不由 WebSocket 关闭码承担。**
> 壳侧若要复刻"关闭即重连"的行为，判据应是 `hadPairedSession`，不是关闭码。

---

## 9. 状态机取值总表

### 9.1 relay 客户端 `state`（9 值，`setState` 字面量逐字）

| 值 | 进入条件 | 出向 |
| --- | --- | --- |
| `idle` | 构造初值 / `dispose()` | → `connecting` |
| `connecting` | `start()` | → `authenticating`（socket open） |
| `authenticating` | socket `open` 后 | → `waiting`（`auth_ack` / `pair_status_ack` 带 `waiting`） |
| `waiting` | `applyPairStatus('waiting')` | → `paired`；30 s 未 matched ⇒ `error` |
| `paired` | `applyPairStatus('matched')` | 只有降级/关闭/挂起能离开 |
| `reconnecting` | `scheduleReconnect` / `reconnectNow` / `reconnectAfterStaleWaiting` | → `connecting` |
| `suspended` | `suspend()`（页面 hidden/freeze/pagehide） | → `reconnecting`（`recoverConnection` 复用 socket 分支） |
| `kicked` | `handleRelayError('KICKED')` | 终态 |
| `error` | 各类终态失败（`enterTerminalFailure` 非 session-conflict）、close 且从未配对 | 终态 |

**状态比较点**（逐字）：`state==='suspended' | 'paired' | 'kicked' | 'waiting' | 'reconnecting' | 'connecting' | 'authenticating'`；
`state!=='suspended' | 'paired' | 'connecting' | 'reconnecting' | 'waiting'`。

### 9.2 `transportState` —— **本构建不存在**

全快照 `grep -rl "transportState"` = **0 个文件**。旧基线若有此名，属**未复核/已消失**。
应用层用的是另外三个命名，别混：

| 名字 | 位置 | 取值 |
| --- | --- | --- |
| `connectionKind`（应用层连接种类） | @1,215,799 `c = o ? (a ? 'remote-ready' : 'remote-waiting') : 'local-ready'` | `remote-ready` / `remote-waiting` / `local-ready` |
| `webRemoteControlTerminalTransportState`（prop 名） | @6,464,114 / @6,465,290 等 11 处 | 各处均为 prop 透传；**本构建未定位到其取值枚举的定义点** |
| `connectionState`（**会话索引**字段，不是连接状态） | `parts-DcYGwy2q.js` @47,859（`Kn`） | `['connecting','online','closing','failed','disconnected','reconnecting']` |

---

## 10. ack 身份三元组 / `liveStatus` 复核

### 10.1 ack 身份三元组 —— **旧基线结论成立，且发现一处 Dart/网页不一致**

**schema 层（强制）**：`rpc-frame-ack.bridgeSessionId` **必填**（`Cd`，不 trim、≤256、字符集受限），
`bridgeGeneration` / `recoveryId` **可选**，整对象 `.strict()` 拒多余键（`validation` @129,854）。

**网页端出站 ack（@647,8079 `flushPendingFrames`）**：

```js
this.invokeSend({ zcode_type:`rpc-frame-ack`, ...this.identity, ackMessageSeq:t })
```

`this.identity`（@647,4813 起构造）= `{bridgeSessionId, bridgeGeneration?, recoveryId?}`
—— **有值就展开**。所以网页端的 ack 带全三元组。

**Dart / Rust 出站 ack（`rpc_transport.dart:173-177` / `rpc_transport.rs:535-550`）**：

```dart
sendPayload({'zcode_type': 'rpc-frame-ack', 'bridgeSessionId': bridgeSessionId, 'ackMessageSeq': messageSeq});
```

—— **只有两个字段，`bridgeGeneration` / `recoveryId` 一律不带**（Rust 的 `ack_payload` 把两者设为 `None`）。

**收端匹配是严格相等**：

```js
// index @647,3625
function l4n(e,t){ if(typeof t!==`object`||!t) return !1; let n=t
  return n.bridgeSessionId===e.bridgeSessionId && n.bridgeGeneration===e.bridgeGeneration && n.recoveryId===e.recoveryId }
// 桌面端同构：out/main/chunk-JS2T33SG.js @7,843 rawIdentityMatches（同三字段严格相等）
```

**后果（壳侧必须修）**：当桥是用 `bridgeGeneration`（或 `recoveryId`）开的——
网页端 §4 的 `V` 流程里 `bridgeGeneration = ++u` **恒有值**——那么 Dart 风格的 ack
（缺 `bridgeGeneration`）会 `undefined !== N` ⇒ 身份不匹配 ⇒ **ack 被丢弃**：

- 网页端：`acceptPayload` 返回 `false` → `handleDataPayload` 落到 `dt(e)` → `onPayload` →
  又只对 `rpc-frame` 调 `acceptPayload`，**ack 无人处理**。
- 结果：对端（我们）发出的帧永远不被释放 ⇒ 45 s 后 `ackGraceExceeded`（终态降级），
  或 8 MiB 时 `replayBufferExceeded`。

⇒ **Rust 侧 `ack_payload` 必须展开 `bridge_generation` / `recovery_id`（有值就带），
与网页端 `{zcode_type:'rpc-frame-ack', ...identity, ackMessageSeq}` 逐字节对齐。**
（旧基线把这条表述为"缺了会整桥降级"——静态证据现在完整了：不是"整桥降级"，
而是"ack 被静默忽略 ⇒ 45 s 后本端判 degraded"。）

### 10.2 `liveStatus` —— **不是 relay 概念，是会话索引字段**

`liveStatus` 只出现在 `parts-DcYGwy2q.js` @**48,803**，是**会话索引条目** `Jn`（定义 @48,675）的字段：

```js
Jn = v({ address:Gn, meta:Ae,
         membership: v({pinned:C(), archived:C(), active:C()}).strict(),
         sourceAvailability: S([`online`,`offline`]),
         liveStatus: S([`idle`,`running`,`waiting`,`completed`,`error`]),      // ← 5 值
         activity: qn.optional(), searchSnippets: w(n()).optional() }).strict()
qn = v({ phase:he, lastActivityAt:O().finite().nonnegative(), hasBackgroundWork:C(),
         pendingInteractions:vt.optional(), workflowActivity:t.optional() }).strict()
```

| 项 | 结论 |
| --- | --- |
| 归属 | **会话索引（`subscribeSessionsIndexV4` 数据面）**，与 relay 连接状态无关 |
| 取值 | `idle` / `running` / `waiting` / `completed` / `error` |
| 与 `mobile-view-state-update` 的关系 | 无。后者只带 `viewState{activeWorkspaceKey?,activeTaskId?,updatedAt}` + `deviceInfo` |
| 与 §9.1 relay `state` 的关系 | 无。两者取值集不相交 |

> 旧基线若把 `liveStatus` 当成连接态使用，属**误用**，此处更正。
> 相邻的 `sourceAvailability:['online','offline']`（`Jn` 内）与
> `connectionState:['connected','disconnected','reconnecting']`（在 `tS` 工作区条目里，@282,217）
> 才是"数据源可用性"相关的两个枚举。

---

## 11. 与壳侧（Rust）的对照

| 协议事实 | 线上权威值/位置 | Rust 现状 | 判定 |
| --- | --- | --- | --- |
| `maxMessageBytes` | **16 MiB**（`validation` @128,548） | 64 MiB（`rpc_transport.rs:66`） | ❌ **应收紧** |
| `maxFragments` | **64**（同上） | 256（`rpc_transport.rs:72`） | ❌ **应收紧** |
| `maxPhysicalFrameBytes` | 1 MiB | 1 MiB | ✅ |
| 分片尺寸 | 动态二分（@131,327） | 固定 512 KiB | ⚠️ 不违约，但与网页端不同形 |
| 装配超时 | 30 s | 30 s purge / 60 s stale | ⚠️ 语义不同（网页是"30 s 未收齐即终态降级"，我们只是清理） |
| 分片顺序 | **必须严格顺序、`seq`/`messageSeq` 连续** | 允许乱序（`Assembly.add` 按下标填） | ❌ **出站必须顺序；入站若允许乱序，与线上收端行为不一致** |
| ack 身份 | **全三元组**（网页端 `...identity`） | 仅 `bridgeSessionId` | ❌ **必须补齐** |
| crc32 覆盖 | 整条消息、小写 8 位 hex | 整条消息 | ✅ |
| `checksum.algorithm` | 字面量 `'crc32'`、`.strict()` | 只读 `value`、不校验 | ⚠️ 入站宽容（可接受），出站必须写 `'crc32'` ✅ |
| 业务帧 `bridgeSessionId` | `eS`（**trim**） | 未区分 | ⚠️ 出站值不要带首尾空格 |
| `pair_status` | `waiting` / `matched` | `RelayState::Waiting` 等已建模 | ✅ |
| 心跳周期 / ack 超时 | 10 s（**带 ±2 s 抖动**）/ 30 s | `HEARTBEAT_INTERVAL_MS=10_000`、`HEARTBEAT_ACK_TIMEOUT_MS=30_000`（`relay_client.rs:138/140`） | ⚠️ 周期/超时一致，但 **Rust 无抖动**；且 Rust 在 `waiting` 态**隔一拍发一次（每 20 s）**（`relay_client.rs:889-890`），网页端 `waiting` 态仍按 10 s 发 |
| `waitingTimeoutMs` | 30 s | `WAITING_TIMEOUT_MS=30_000`（`relay_client.rs:142`） | ✅ |
| `enterDegraded` 语义 | 降级 ⇒ 清空全部在途状态 + 走恢复通路 | —— | 待对齐 |

**`relay_client.rs` 只做了常量级对照**（grep `heartbeat`/`30_000`/`pair_status`，未逐行读）；
§11 上表的 Rust 结论除该行外只针对 `rpc_transport.rs`（已逐行读取）。
若 `relay_client.rs` 里另有 `maxMessageBytes`/`maxFragments` 的第二份定义，**两处都要收紧**。

---

## 12. 另一条通道（token / window）速览

- 入口：读 `remoteControlToken` / `relayOrigin`（白名单 @649,3416；读取点 @655,4034），
  `relayOrigin` 缺省 `window.location.origin`。
- REST 面（`index-BGa3KiRe.js`）：

| 端点 | 函数 | 位置 |
| --- | --- | --- |
| `POST ${relayOrigin}/api/remote-control/platform/${token}` | `J9` | @655,6363 |
| `GET ${relayOrigin}/api/remote-control/windows/bootstrap/${token}` | `O6n` | @655,6817 |
| `POST ${relayOrigin}/api/remote-control/windows/${token}/workspace-bridge` | `k6n` | @655,7368 |
| `POST ${relayOrigin}/api/remote-control/windows/${token}/mobile-view-state` | `A6n` | @655,7921 |

- 后三者带 `X-ZCode-Mobile-Connection-Id: e.mobileConnectionId`（@655,7563 / @655,8125）。
- 错误表达：先试 `E6n(i)`（@655,5807）——响应体是 `{reason, error}` 就抛 `{reason,message}`；
  否则取 `error` 字段抛 `Error`；再否则用固定文案。
- 页面标题：`document.title = 'ZCode - Web Remote Control'`（@655,4195 等 7 处）。
- 与 relay 模式的**共同点**：业务消息类型系统相同（§4 的 19 个 `zcode_type`）；
  `platform-request/response` 在 window 模式下由 REST 承担（body `{method, args}`）。
- 对壳的意义：壳**不用**这条通道；但"页面能同时挂第二条远控通道"是理解
  `windowControlSessionId`（`bootstrap-response.result`（`sS`）的**必填**键）与
  `mobileConnectionId`（window 模式建连时下发、用于 `X-ZCode-Mobile-Connection-Id` 头）为何在协议里
  的前提（见 14 篇 §6.1、17 篇 §2.2）。

---

## 13. 复现命令

```bash
cd E:/Zcode_android/Reference_web/3.15.3/assets

# 1) 业务 zcode_type 全集（schema 判别联合）
node -e "const b=require('fs').readFileSync('validation-CqXZOLXO.js');\
const s=b.subarray(284627,287200).toString();\
console.log([...s.matchAll(/zcode_type:N\(\`([^\`]+)\`\)/g)].map(m=>m[1]).join('\n'))"

# 2) 两个预算对象（别混用）
node -e "const b=require('fs').readFileSync('validation-CqXZOLXO.js');\
console.log(b.subarray(117018,117420).toString());console.log(b.subarray(128536,128700).toString())"

# 3) 分片算法（二分）
node -e "const b=require('fs').readFileSync('validation-CqXZOLXO.js');\
console.log(b.subarray(130990,131500).toString());console.log(b.subarray(131320,131420).toString())"

# 4) relay 客户端骨架（心跳 / 重连 / 状态机）
node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');\
console.log(b.subarray(6528400,6529450).toString());console.log(b.subarray(6530400,6531350).toString())"

# 5) ack 身份三元组（网页端 vs Dart 的差异点）
node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');\
console.log(b.subarray(6478060,6478260).toString());console.log(b.subarray(6473620,6473720).toString())"

# 6) 降级原因码全集
node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js').toString();\
const v=require('fs').readFileSync('validation-CqXZOLXO.js').toString();\
console.log([...new Set([...(b+v).matchAll(/\`remote\.rpcFrame\.[A-Za-z]+\`/g)].map(m=>m[0]))].sort().join('\n'))"
```

---

## 14. 出处

- 快照：`Reference_web/3.15.3/`（主包 `assets/index-BGa3KiRe.js`、schema `assets/validation-CqXZOLXO.js`、
  共享块 `assets/src-AL-_ONNp.js`、会话索引 `assets/parts-DcYGwy2q.js`）；清单 `3.15.3/_manifest.json`。
- 桌面端同构交叉校验：`app.asar` → `out/main/chunk-OW6EUS2G.js` @545,824（`Ue` = `bd`）、
  `out/main/chunk-JS2T33SG.js` @7,843（`rawIdentityMatches`）。**桌面端内容不进入本篇正文。**
- 我方实现对照：`rust/crates/protocol/src/rpc_transport.rs`、`rust/crates/protocol/src/channel_client.rs`
  （**只读对照，未修改**）。
- Dart 参照实现：`Reference_code/lib/protocol/rpc_transport.dart`、`lib/protocol/channel_client.dart`。
- 相关篇目：[14 bootstrap 完整字段表](14-remote-v4-bootstrap载荷-完整字段表.md) ·
  [17 合并总表与未解析字段](17-remote-v4-bootstrap载荷-合并总表与未解析字段清单.md) ·
  [13 壳侧解析了哪些](13-remote-v4-bootstrap载荷-我们解析了哪些.md) ·
  [15 网页架构与模块地图](15-remote-v4-网页架构与模块地图.md) ·
  [10 扩展面与可观测面](10-remote-v4-网页扩展面与可观测面穷尽盘点.md)。
- **未复核（旧基线）**：「壳侧绝不发 `onDynamicControllerFrame`（真机打崩桌面端 host）」是实测结论，
  本构建未做真机复核，静态侧只能看到该方法存在（@1,489,590）。
