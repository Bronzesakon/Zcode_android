# 后台原生承载（Background Native Carrier）独立设计

> 独立设计文档。作者在撰写前**未阅读**本项目任何既有实现与既有分析。
>
> **只读来源**：`android-shell/docs/05-远程网页-remote-v4-20260911/` 下的网页快照
> （`index.html`、`assets/index-nOVzQNKW.js`、`assets/src-DHgFesxz.js`、`assets/logger-BVohFQ23.js`、
> `assets/index-BMndL2ru.css`、`_manifest.md`）。
>
> **证据格式**：`文件:行:列`（列号 = 该行内 1-based 字符列）。因为 bundle 是压缩产物、主逻辑集中在**单一行**里，
> 每列号同时给出**字节偏移**（`off=N`），两者互为校验：用 `grep -n` 得到 `文件:行:列`，用偏移可以在多行压缩产物里稳定复现。
> 行号口径与 `grep -n` 一致（主 bundle 共 897 行）。
>
> **未读声明**：`app/src/main/assets/**`、`app/src/main/java/**`、`tools/**`、`README.md`、
> `docs/05-.../分析-*.md`、`scratch/**` 均未打开，未依赖其中任何内容。唯一例外需声明：`docs/05-.../` 目录下存在
> `分析-进对话无内容与左下角转圈.md`、`分析-壳端注入点审计-主题-滚动条-会话加载.md`、`README.md` 三个文件，
> 我在最初 `Get-ChildItem` 列目录时**看到了它们的文件名与字节大小**，但未打开、未读取内容、结论不依赖它们。
> `_evidence/ctx.js`、`_evidence/zcode-shell.log` 亦未作为证据使用（前者与我的扫描脚本功能相同但未参照）。
>
> **凭证处理**：URL 查询串参数值、`device_sid`、`passHash`、`deviceMid`、配对码、`remoteControlToken`
> 一律写作占位符 `<SID>` / `<PASS_HASH>` / `<MID>` / `<TOKEN>`，不出现任何原文。

---

## 1. 一句话结论 + 方案总览

### 1.1 一句话结论

网页只用了**两条** WebSocket：一条 `/ws` 中转终端 socket（承载全部 RPC 数据面与会话数据面），
一条 `/ws/remote-control/window/<TOKEN>` 窗口控制 socket（只做配对/身份/工作区切换的带外控制面）；
其中与桌面端配对的**唯一权威**是 `/ws` 那条，且它是**单控制端**语义——
因此方案核心不是"再造一个客户端并抢连接"，而是**让网页在进入后台时主动、先一步释放 `/ws`，原生再接管；
回前台时原生先释放，网页重连**，全程任意时刻只有一条 `/ws` 存在。

**对题设前提的一处关键修正（源码 + 平台取证得出，见 §2.2-1 与 §2.6 的 R8）**：

> 题述"切后台后连接在 40~70 s 内失效"**不能只归因于"定时器被节流到 1 次/分钟"**。
> 源码已证：网页在 `visibilitychange → hidden` 时**自己调用了 `stopHeartbeat()`**（停止发 `pair_status_query`）；
> 而平台取证（B5）表明 **Chromium 的节流不影响 WebSocket 事件处理器**，即**收包通道在后台仍可用**。
> ⇒ 真正被破坏的是**心跳契约**，不是收包能力。这个区分决定了方案形态：
> **不是"帮网页把心跳跑起来"，而是"由原生持有连接并发心跳、同时收帧"** —— 结论方向一致，
> 但**必要性论证更硬**（因为 `stopHeartbeat()` 是网页的既有设计，不是可以被"调参绕过"的节流现象）。

### 1.2 方案总览

```
┌──────────────────────────── Android App 进程 ────────────────────────────┐
│                                                                          │
│  前台（页面可见）                          后台（页面隐藏）                 │
│  ┌──────────────────────┐                 ┌──────────────────────────┐   │
│  │ WebView / remote-v4  │                 │ WebView 仍存活、页面已停心跳│   │
│  │  └─ WS#1 /ws  (持有)  │                 │  └─ WS#1 已主动 close      │   │
│  │  └─ WS#2 /ws/...window│                 └──────────────────────────┘   │
│  └──────────────────────┘                                                │
│            │ ①onPause/visibilitychange=hidden                            │
│            ▼                                                             │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │ RelayOwner (单例，决定"谁持有 WS#1")                                │   │
│  │   state: PAGE_OWNED → HANDOFF_PENDING → NATIVE_OWNED → …           │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│            │ ②页面 socket 确认 CLOSED                                    │
│            ▼                                                             │
│  ┌──────────────────────┐   RPC 帧    ┌────────────────────────────┐    │
│  │ NativeRelayClient    │◄───────────►│ /ws  (唯一一条)             │    │
│  │  ·Auth(HMAC proof)   │             └────────────────────────────┘    │
│  │  ·Heartbeat 10s      │                        ▲                      │
│  │  ·RpcFrameCodec      │                        │ ③onResume            │
│  │  ·ConversationSub    │────────────► ④native close → 页面 reload/重连   │
│  │  ·AiTextExtractor    │                                               │
│  └──────────┬───────────┘                                               │
│             ▼                                                            │
│      ColorOS 流体云 / 通知（AI 正文流式投递）                              │
└──────────────────────────────────────────────────────────────────────────┘
```

**关键顺序（这是整个方案的安全底线）**：
`页面 close WS#1` **必须早于** `原生 dial WS#1`；反向顺序或并发 dial 会触发中转的"设备接管"判定，
被顶掉的一方进入 `kicked` 终态且**源码内无自愈路径**（§2.9、§4）。

---

## 2. 从网页源码推出的协议契约（逐条带证据）

### 2.0 先回答"一共几条 WebSocket"

**两条，且互相独立**（不在同一个对象里，生命周期也不同）：

| # | 路径 | 何时开 | 承载什么 | 证据 |
|---|---|---|---|---|
| WS#1 | `wss://<web-origin>/ws`（或 `/ws/remote/<REMOTE_ID>`） | 进入远控页、`transport.start()` 后立即 | **全部** RPC 数据面 + 会话数据面（`conversation/*` 订阅、`rpc-frame`、诊断） | `index-nOVzQNKW.js:897:349646` (off=4734646) |
| WS#2 | `wss://<relayOrigin>/ws/remote-control/window/<TOKEN>` | 页面引导阶段，先 `bootstrap` 再开 | 窗口级控制：`window-control-ready` 握手、工作区桥接/切换、`mobile-view-state` 上报 | `index-nOVzQNKW.js:897:350786` (off=4735786) |

WS#1 的 URL 构造（`window.location.host` 即网页自身 origin，不是 relay origin）：

```js
function R4t(){return`${window.location.protocol===`https:`?`wss:`:`ws:`}//${window.location.host}`}
async function z4t(){let e=new URLSearchParams(window.location.search).get(`remote`),t=e?`${R4t()}/ws/remote/${e}`:`${R4t()}/ws`;…}
```
→ `index-nOVzQNKW.js:897:349610` / `:897:349646`（off=4734610 / 4734646）

WS#1 实例化处的 options 明确暴露了它需要的全部凭证位（值均为占位符）：

```js
E=new i4t({relayWsUrl:q4t(),deviceSid:t.deviceSid,passHash:t.passHash,deviceMid:t.deviceMid,
           appVersion:t.appVersion,authProvider:Z2t(),…})
```
→ `index-nOVzQNKW.js:897:362399`（off=4747413）

`relayWsUrl` 的默认值是**网页自身 origin**，只有走 `remoteControlToken` 引导路径时才被替换成 relay origin：

```js
function q4t(){return Vne({endpointOrigin:`https://zcode.z.ai`?.trim()||…,overrideUrl:void 0})}
```
→ `index-nOVzQNKW.js:897:357947`（off=4742961）

**推断（需真机确认，见 §5-R1）**：`https://zcode.z.ai/remote/v4` 这条路径上页面自己就是同源中转，
所以 `relayWsUrl ≈ wss://zcode.z.ai/ws`。原生只需复制页面的 `relayWsUrl`，不要自己拼。

WS#2 的开法与握手：

```js
a=new WebSocket(`${i.replace(/\/$/,'')}/ws/remote-control/window/${e.token}`)  // i = relayOrigin 的 http→ws 改写
…c=e=>{let r=V4t(e);r&&(o=!0,s(),…n({socket:a,ready:r}))}
function V4t(e){…t?.type===`window-control-ready`&&typeof t.windowControlSessionId==`string`&&typeof t.mobileConnectionId==`string`…}
```
→ `index-nOVzQNKW.js:897:350870` / `:897:350611` / `:897:350452`（off=4735884 / 4735625 / 4735466）

`mobileConnectionId` 随后被当作请求头 `X-ZCode-Mobile-Connection-Id` 用于 HTTP 控制面：

```js
fetch(`${e.relayOrigin}/api/remote-control/windows/${e.token}/workspace-bridge`,{method:`POST`,
      headers:{"Content-Type":`application/json`,"X-ZCode-Mobile-Connection-Id":e.mobileConnectionId},…})
```
→ `index-nOVzQNKW.js:897:343232`（off=4728246）

> **对方案的意义**：WS#2 **不承载 AI 正文**，不参与 `KICKED` 判定。原生后台承载**只需要 WS#1**；
> WS#2 可以继续留在网页里（它只服务于前台 UI 的工作区切换）。这一点把需要原生化的工作量砍掉了一半。

### 2.1 WS#1 的认证与配对握手

**握手四步**：`open → auth_init → auth_challenge → auth_response → auth_ack → pair_status_query/pair_status_ack`。

**① `auth_init`（客户端在 socket `open` 后立刻发）**

```js
this.setState(`authenticating`),this.send({type:`auth_init`,role:`terminal`,device_sid:this.options.deviceSid,
  meta:{platform:`web`,version:this.options.appVersion??`web`,name:`mobile-browser`},client_ts:Date.now()})
```
→ `index-nOVzQNKW.js:897:314012`（off=4699026）

字段：`type`、`role="terminal"`、`device_sid`、`meta{platform,version,name}`、`client_ts`。
**注意 `role` 固定 `terminal`**（WS#2 那条不是这个角色），原生必须照抄。

**② `auth_challenge` → `auth_response`（proof 计算）**

```js
case`auth_challenge`:this.send({type:`auth_response`,device_sid:this.options.deviceSid,
  proof:await this.options.authProvider.calculateProof(this.options.passHash,t.nonce,`terminal`,this.options.deviceSid),
  client_ts:Date.now()});break;
```
→ `index-nOVzQNKW.js:897:317021`（off=4702035）

proof 的**确切算法**（HMAC-SHA256 + base64url 去 padding）：

```js
async function X2t(e,t,n,r){let i=new TextEncoder,a=await globalThis.crypto.subtle.importKey(`raw`,i.encode(e),
  {name:`HMAC`,hash:`SHA-256`},!1,[`sign`]),o=await globalThis.crypto.subtle.sign(`HMAC`,a,i.encode(`${t}|${n}|${r}`));
  return Y2t(new Uint8Array(o))}
function Y2t(e){return btoa(String.fromCharCode(...e)).replace(/\+/g,`-`).replace(/\//g,`_`).replace(/=+$/,``)}
```
→ `index-nOVzQNKW.js:897:310878`（off=4695892）、`:897:310761`（off=4695775）

即：

```
key        = passHash                      (ASCII/UTF-8)
message    = `${nonce}|${role}|${deviceSid}`   role 恒为 "terminal"
proof      = base64url_nopad( HMAC_SHA256(key, message) )
```

> 这条**必须逐字节复刻**。原生侧 `Mac.getInstance("HmacSHA256")` + `Base64.encodeToString(..., URL_SAFE|NO_PADDING|NO_WRAP)` 即可。
> 注意 `deviceSid` 拼接用的是**原始字符串**（不是 hash 后的），`role` 是字面量 `terminal`。

**③ `auth_ack` 与 `pair_status_ack` 走同一个分支**，只按 `pair_status` 分派：

```js
case`auth_ack`:case`pair_status_ack`:this.applyPairStatus(t.pair_status);break;
case`data`:this.handleDataPayload(t.payload);break;
case`error`:this.handleRelayError(t.code,t.message);break
```
→ `index-nOVzQNKW.js:897:317237`（off=4702251）

**④ 入站消息解析口径**：任何文本帧先按 `JSON.parse` 解析，且必须含 `type` 字段，否则整帧丢弃：

```js
async function e4t(e){try{let t=await Q2t(e);if(t===null)return null;let n=JSON.parse(t);
  return n&&typeof n==`object`&&`type`in n?n:null}catch{return null}}
```
→ `index-nOVzQNKW.js:897:311841`（off=4696855）

**⑤ 心跳／配对查询帧**（`pair_status_query` 同时充当心跳）：

```js
sendPairStatusQuery(){return this.send({type:`pair_status_query`,device_sid:this.options.deviceSid,client_ts:Date.now()})}
```
→ `index-nOVzQNKW.js:897:320045`（off=4705059）

**ack 长什么样**：客户端**不读** ack 的其它字段，只读 `pair_status`（取值 `waiting` / `matched`）：

```js
e===`waiting`){if(this.wasPaired){…this.setState(`waiting`),this.startHeartbeat();return}this.stopHeartbeat(),
  this.setState(`waiting`),this.startWaitingTimer();return}
if(e===`matched`){let e=this.lastPairedSocketGeneration,t=e===0?null:e===this.socketGeneration?`same-socket`:`reconnected-socket`;
  …this.setState(`paired`),this.wasPaired=!0,this.hadPairedSession=!0,this.lastPairedSocketGeneration=this.socketGeneration,
  this.startHeartbeat(),t&&this.options.onSendReady?.({kind:t});return}
```
→ `index-nOVzQNKW.js:897:317540` / `:897:317828`（off=4702554 / 4702842）

**⑥ 身份唯一性校验（`mid`）**：URL 上只有可选的 `mid` 查询参数：

```js
connect(){this.socketGeneration+=1;let e=new URL(this.options.relayWsUrl);
  this.options.deviceMid?.trim()&&e.searchParams.set(`mid`,this.options.deviceMid);
  let t=new this.WebSocketCtor(e.toString());…}
```
→ `index-nOVzQNKW.js:897:313473`（off=4698487）

> **对方案的意义**：`mid` 是**同一个 device_sid 下区分"哪一个手机身份"**的字段。原生接管时
> **必须使用网页同一个 `deviceMid`**（而不是自己新生成一个），否则中转会认为是另一台设备 → 必然 `KICKED`（§2.3）。

### 2.2 心跳、看门狗、退避与重连梯子

| 项 | 值 | 证据 |
|---|---|---|
| 心跳发送间隔 | `heartbeatIntervalMs ?? 10000`（10s，带 jitter） | `index-nOVzQNKW.js:897:319272` (off=4704286) |
| 发送动作 | 每次发 `pair_status_query` | 同上 `:897:319292` (off=4704306) |
| **ack 看门狗超时** | `heartbeatAckTimeoutMs ?? 30000`（**30s**） | `index-nOVzQNKW.js:897:319652` (off=4704766) |
| 看门狗超时后的动作 | `reconnectAfterStaleWaiting(getReconnectJitterMs())` | 同上 |
| 重连抖动上限 | `reconnectJitterMs ?? Math.min(心跳间隔, 2000)` | `index-nOVzQNKW.js:897:319784` (off=4704898) |
| 等待配对超时 | `waitingTimeoutMs ?? 30000`（30s 未 matched → 终态 `invalid-mobile-connection`） | `index-nOVzQNKW.js:897:320192` (off=4705306) |
| 重连退避 | `Math.min(10000, 500 * 2 ** reconnectAttempt)`，即 0.5s→1→2→4→8→10s 封顶 | `index-nOVzQNKW.js:897:321596` (off=4706710) |
| 恢复健康检查窗口 | `healthCheckTimeoutMs ?? 3000`（3s） | `index-nOVzQNKW.js:897:315909` (off=4701023) |
| 整体恢复窗口 | `recoverConnection(e=15000)`（15s） | `index-nOVzQNKW.js:897:315652` (off=4700666) |
| 桌面端离线宽限 | `desktopOfflineGraceMs ?? 15000`（15s，超时 → 终态 `desktop-disconnected`） | `index-nOVzQNKW.js:897:322572` (off=4707686) |

心跳与看门狗的原始实现：

```js
scheduleHeartbeat(){this.heartbeatTimer=setTimeout(()=>{this.heartbeatTimer=void 0,
  !(this.state!==`paired`&&this.state!==`waiting`)&&(this.sendPairStatusQuery(),this.scheduleHeartbeat())},
  xae(this.options.heartbeatIntervalMs??1e4,this.options.heartbeatJitterMs))}
armHeartbeatAckWatchdog(){this.state!==`paired`&&this.state!==`waiting`||(this.heartbeatAckWatchdogTimer&&clearTimeout(…),
  this.heartbeatAckWatchdogTimer=setTimeout(()=>{this.heartbeatAckWatchdogTimer=void 0,
  !(this.state!==`paired`&&this.state!==`waiting`)&&this.reconnectAfterStaleWaiting(this.getReconnectJitterMs())},
  this.options.heartbeatAckTimeoutMs??3e4))}
```
→ `index-nOVzQNKW.js:897:319272` / `:897:319432`（off=4704286 / 4704446）

重连梯子（`wasPaired` 被**清零**，即每次退避重连都是一次全新配对）：

```js
scheduleReconnect(e){if(this.reconnectTimer)return;let t=e?.immediate?0:Math.min(1e4,500*2**this.reconnectAttempt);
  this.reconnectAttempt+=1,this.setState(`reconnecting`),…this.reconnectTimer=setTimeout(()=>{…
  this.staleWaitingCount=0,this.wasPaired=!1;let e=this.socket;this.socket=void 0,e?.close(),this.connect()},t)}
```
→ `index-nOVzQNKW.js:897:321596`（off=4706710）

**"什么情况下服务端/桌面端会停止推送"——源码能证的部分**：

1. 页面进入后台时，网页自己把心跳**停掉**了（不是节流，是主动停）：
   ```js
   suspend(){this.state!==`paired`&&this.state!==`connecting`&&this.state!==`reconnecting`||(this.hiddenStartedAt=Date.now(),
     this.clearReconnectTimer(),this.stopHeartbeat(),this.clearWaitingTimer(),this.clearStaleWaitingRecoveryTimer(),
     this.setState(`suspended`))}
   ```
   → `index-nOVzQNKW.js:897:315452`（off=4700466）
   **注意：`suspend()` 只停心跳，不关 socket**（下文 §2.9 会说明这个"僵尸 socket"正是 KICKED 的成因）。
2. `staleWaitingCount>0` 时，所有业务帧发送被拒绝（返回 `{kind:'unavailable'}`）：
   ```js
   …:this.state!==`paired`||this.staleWaitingCount>0?{kind:`unavailable`}:this.sendSerialized(t.message,t.json)?…}
   ```
   → `index-nOVzQNKW.js:897:315133`（off=4700147）
3. 桌面端侧：`bridge-degraded` / `workspace-bridge-error` / `app-error` 会让网页的 workspace bridge 进入 degraded，
   进而触发一次工作区重桥接或整页失败：
   ```js
   if(e.zcode_type===`bridge-degraded`){e.bridgeSessionId===d?.getBridgeSessionId()&&(d?.markDegraded(),T(e.reason));return}
   ```
   → `index-nOVzQNKW.js:897:362732`（off=4747746）

### 2.3 KICKED：触发条件、页面终态、可自愈性

**服务端在什么条件下发**：源码只能证"客户端收到 `code==='KICKED'` 后怎么处理"，服务端判定条件为**推断**——
由 UI 文案反推是**同一 device 的第二个控制端接入**：

```js
"session-conflict":{tone:`warning`,badge:`设备接管`,title:`已被其他设备接管`,
  description:`另一台远程控制设备已经接入，同一时间只能保留一个手机控制端。`,
  detailLabel:`Relay 返回`,nextStepsTitle:`下一步`,
  nextSteps:[`继续使用新接入的设备。`,`如果要用本设备控制，请重新扫描桌面端二维码。`],action:`重新连接`}
```
→ `index-nOVzQNKW.js:897:328272`（off=4713286）

**页面收到后的状态**：

```js
handleRelayError(e,t){if(e===`KICKED`){this.setState(`kicked`),this.enterTerminalFailure(R9(`session-conflict`,t||e));return}
```
→ `index-nOVzQNKW.js:897:318485`（off=4703499）

```js
enterTerminalFailure(e){this.intentionallyClosed=!0,this.stopHeartbeat(),this.clearWaitingTimer(),
  this.clearStaleWaitingRecoveryTimer(),this.clearDesktopOfflineFailureTimer(),this.clearReconnectTimer(),
  this.setState(e.reason===`session-conflict`?`kicked`:`error`),this.rejectPairedWaiters(Error(e.message??e.reason)),
  this.options.onFailure(e),this.socket?.close()}
```
→ `index-nOVzQNKW.js:897:323881`（off=4708895）

**能否自愈：不能。三条独立证据（这是"不可自愈"的源码级证明）：**

1. `intentionallyClosed=!0` 让后续的 `close` 事件跳过重连分支：
   ```js
   t.addEventListener(`close`,e=>{…this.stopHeartbeat(),…,!this.intentionallyClosed&&this.state!==`suspended`)){
     if(this.hadPairedSession){this.scheduleReconnect();return}…}})
   ```
   → `index-nOVzQNKW.js:897:314499`（off=4699513）
2. 后台恢复路径对 `kicked` 直接 return：
   ```js
   recoverInBackground(){this.intentionallyClosed||this.state===`paired`||this.state===`kicked`||(…this.reconnectNow())}
   ```
   → `index-nOVzQNKW.js:897:316172`（off=4701186）
3. 终态界面的"重新连接"按钮**只做整页 reload**，没有任何针对性重连逻辑：
   ```js
   function G9(e){V9.render(jsx(_4t,{failure:e,locale:m4t(navigator.language),onReload:()=>{window.location.reload()}}))}
   ```
   → `index-nOVzQNKW.js:897:340319`（off=4725333）

补充：进入 `error`/`kicked` 时还会 `rejectPairedWaiters`，把所有在飞的 RPC 请求全部拒掉：

```js
…&&this.resolvePairedWaiters(),(e===`error`||e===`kicked`)&&this.rejectPairedWaiters(Error(`Web remote con…
```
→ `index-nOVzQNKW.js:897:324371`（off=4709385）

### 2.4 会话数据面：`rpc-frame` 分片、编码、ack

**外层信封**（这是 WS#1 上 `type:'data'` 的 `payload`）：

```js
var d2t=class{cache=new WeakMap;prepare(e,t=Date.now()){…let r={type:`data`,payload:e,client_ts:t},i=JSON.stringify(r),
  a=Object.freeze({message:r,json:i,bytes:new TextEncoder().encode(i).byteLength});return this.cache.set(e,a),a}
isOversize(e){return e.bytes>Eo.maxPhysicalFrameBytes}}
```
→ `index-nOVzQNKW.js:897:295800`（off=4680814）

**物理帧（分片）结构**，字段与约束（严格 `.strict()`）：

```js
var Ch=O({zcode_type:N(`rpc-frame`),bridgeSessionId:yh,bridgeGeneration:vh.optional(),recoveryId:yh.optional(),
  seq:_h,messageSeq:_h,fragmentIndex:w().int().nonnegative().max(X.maxFragments-1),
  fragmentCount:w().int().positive().max(X.maxFragments),messageBytes:w().int().positive().max(X.maxMessageBytes),
  checksum:xh,dataBase64:S().min(4).max(X.maxPhysicalFrameBytes).refine(Sh)}).strict().superRefine((e,t)=>{
  e.fragmentIndex>=e.fragmentCount&&t.addIssue({…});e.fragmentCount>e.messageBytes&&t.addIssue({…})})
```
→ `src-DHgFesxz.js:39:137095`（off=187359）

**ack 结构（必须带哪些身份字段）**：

```js
wh=O({zcode_type:N(`rpc-frame-ack`),bridgeSessionId:yh,bridgeGeneration:vh.optional(),recoveryId:yh.optional(),
  ackMessageSeq:_h}).strict(),Th=k([Ch,wh])
```
→ `src-DHgFesxz.js:39:137791`（off=188055）

```js
if(this.pendingAckMessageSeq!==null){let t=this.pendingAckMessageSeq;
  if(!this.invokeSend({zcode_type:`rpc-frame-ack`,...this.identity,ackMessageSeq:t})||…){…}}
```
→ `index-nOVzQNKW.js:897:291852`（off=4676866）

**"缺了会怎样"——有明确后果，且是终态**：

```js
function a2t(e,t){if(typeof t!=`object`||!t)return!1;let n=t;return n.bridgeSessionId===e.bridgeSessionId&&
  n.bridgeGeneration===e.bridgeGeneration&&n.recoveryId===e.recoveryId}
…
acceptPayload(e){return this.disposed||this.degraded||!o2t(e)||!a2t(this.identity,e)?!1:…}
```
→ `index-nOVzQNKW.js:897:287172` / `:897:292812`（off=4672186 / 4677826）

即：`rpc-frame` 与 `rpc-frame-ack` **都必须原样携带三元组身份**
`bridgeSessionId` + `bridgeGeneration` + `recoveryId`，**三者任一不等即整帧被静默丢弃**（返回 `false`，不报错）。

**本端身份的构造**：

```js
this.identity={bridgeSessionId:e.bridgeSessionId,...e.bridgeGeneration===void 0?{}:{bridgeGeneration:e.bridgeGeneration},
  ...e.recoveryId===void 0?{}:{recoveryId:e.recoveryId}}
```
→ `index-nOVzQNKW.js:897:289092`（off=4676106）

**ack 超时（45 秒）**——与题述"45 秒容忍窗"**数值吻合**，源码可证：

```js
T9={saturationHighWaterMarkBytes:1024*1024,saturationLowWaterMarkBytes:256*1024,
     replayBufferMaxBytes:8*1024*1024,replayBufferGraceMs:45e3,assemblyTimeoutMs:Eo.assemblyTimeoutMs}
```
→ `index-nOVzQNKW.js:897:286798`（off=4671812）

```js
{deadline:n.queuedAt+this.graceMs+1,fault:{reasonCode:`remote.rpcFrame.ackGraceExceeded`,terminal:!0,messageSeq:n.messageSeq}}
```
→ `index-nOVzQNKW.js:897:286372`（off=4671386）

**分片编码/重组**：`crc32` 校验 + 二分求最大分片大小 + base64：

```js
function ph(e){let t=4294967295;for(let n of e){t^=n;for(let e=0;e<8;e+=1)t=t>>>1^(t&1?3988292384:0)}
  return((t^4294967295)>>>0).toString(16).padStart(8,`0`)}
```
→ `src-DHgFesxz.js:39:135280`（off=185808） — 标准 CRC-32（多项式 0xEDB88320，反射实现），输出**小写 8 位 hex**

```js
xh=O({algorithm:N(`crc32`),value:S().regex(/^[0-9a-f]{8}$/u)}).strict()
function Fh(e){return{zcode_type:`rpc-frame`,...e.identity,seq:e.seq,messageSeq:e.messageSeq,
  fragmentIndex:e.fragmentIndex,fragmentCount:e.fragmentCount,messageBytes:e.messageBytes,
  checksum:e.checksum,dataBase64:e.dataBase64}}
function Ih(e){…二分搜索最大分片字节数，使整帧 JSON ≤ maxPhysicalFrameBytes…}
```
→ `src-DHgFesxz.js:39:136632` / `:39:139038` / `:39:139264`（off=186896 / 189302 / 189528）

**关键限制常量**（原生必须一致，否则被对端 schema 拒）：

```js
Gs={maxFrameBytes:1024*1024,logicalFrameAssemblyMaxBytes:16*1024*1024,logicalFrameAssemblyMaxFragments:1024,
    logicalFrameAssemblyMaxConcurrent:32,logicalFrameAssemblyMaxStagedBytes:32*1024*1024,
    logicalFrameAssemblyTimeoutMs:3e4,transportEnvelopeIdMaxChars:256,…}
var X={maxPhysicalFrameBytes:Gs.maxFrameBytes,maxMessageBytes:16*1024*1024,maxFragments:64,
       assemblyTimeoutMs:3e4,transportIdMaxChars:Gs.transportEnvelopeIdMaxChars}
```
→ `src-DHgFesxz.js:39:37066` / `:39:136492`（off=87330 / 186756）

即：**单条物理帧 ≤ 1 MiB；单条逻辑消息 ≤ 16 MiB；每条消息 ≤ 64 片；分片重组超时 30 s；
`bridgeSessionId`/`recoveryId` 只允许 `^[A-Za-z0-9._~-]+$` 且 ≤ 256 字符**（`yh`）。

> **原生实现要点**：`dataBase64` 是**消息切片**的 base64，不是整条消息的 base64。分片必须是同一
> `messageSeq`、`messageBytes`、`checksum`，`fragmentIndex` 递增、`fragmentCount` 相同；`checksum`
> 是对**整条消息**（未分片前）算的 CRC-32。

### 2.5 逻辑帧（`wireVersion:3`）与订阅/re-anchor

**逻辑帧外层**（`rpc-frame` 的 `dataBase64` 解出来之后是它，JSON）：

```js
var Hc=oa([`initial`,`online`,`recovery`]),Uc=Xi(`kind`,[
  Ka({wireVersion:Xa(3),kind:Xa(`complete`),deliveryKind:Li().optional(),logicalFrameId:Ji().min(1),
      logicalFrameOrdinal:Di(),topic:Ji().min(1),subscriptionId:Ji().min(1),frame:Li().optional()}).passthrough(),
  Ka({wireVersion:Xa(3),kind:Xa(`fragment`),deliveryKind:Li().optional(),logicalFrameId:Ji().min(1),
      logicalFrameOrdinal:Di(),topic:Ji().min(1),subscriptionId:Ji().min(1),
      fragmentIndex:Di().int().nonnegative(),fragmentCount:Di().int().positive().max(Pa.logicalFrameAssemblyMaxFragments),
      logicalBytes:Di().int().positive(),checksum:Hle,dataBase64:fae}).strict()]).superRefine((e,t)=>{…
  n.fragmentCount>n.logicalBytes&&t.addIssue({code:`custom`,message:`fragmentCount cannot exceed logicalBytes`,…});
  let r=n.frame;r.topic!==n.topic&&t.addIssue({…`complete wire topic must match logical frame topic`…}),
  r.subscriptionId!==n.subscriptionId&&t.addIssue({…})})
```
→ `index-nOVzQNKW.js:2:46827` ~ `:2:47985`（off=104933 ~ 106091）

**订阅请求形状**（`subscribeConversationV4`）：

```js
Gle=Ka({topic:Ji(),base:Ka({logEpoch:Ji(),seq:Di()}).optional(),
        visibility:oa([`foreground`,`background`]).optional()}).strict()
```
→ `index-nOVzQNKW.js:2:49538`（off=107644）

**ack 形状**：

```js
Gc=Ka({subscriptionId:Ji(),mode:oa([`snapshot`,`resume`]),logEpoch:Ji()})
```
→ `index-nOVzQNKW.js:2:49671`（off=107777）

**发起订阅的完整调用**：

```js
let s=m,c=await e.subscribeConversationV4({...n,sessionId:i,...t.base?{base:t.base}:{},
  ...t.visibility?{visibility:t.visibility}:{}});
…u.set(c.ack.subscriptionId,t.topic),…
```
→ `index-nOVzQNKW.js:22:28203` / `:22:28875`（off=544048 / 544675）

其中 `n` 是工作区上下文（`{workspacePath, workspaceIdentity}`）：

```js
function bb(e,t){let n={workspacePath:t.workspacePath,...t.workspaceIdentity?{workspaceIdentity:t.workspaceIdentity}:{}},…}
```
→ `index-nOVzQNKW.js:22:27155`（off=542955）

**re-anchor / resync 请求形状**：

```js
C=Ka({subscriptionId:Ji().trim().min(1).max(1024),
      base:Ka({logEpoch:Ji().trim().min(1).max(1024),seq:Di().int().nonnegative()}).strict().nullable(),
      forceSnapshot:ji().optional()}).strict().extend({topic:Ji().trim().min(1).max(2048),
      connectionId:Ji().trim().min(1).max(1024)}).strict()
```
→ `index-nOVzQNKW.js:2:52261` ~ `:2:52571`（off=110367 ~ 110677）；`forceSnapshot` 字段在 `:2:52558`（off=110664）

**页面自己怎么决定 re-anchor**（这是原生要对齐的策略）：

```js
issueRecovery(e,t){this.clearRecoveryDeadline(e);let n=this.state.snapshot,r=t||!this.subscriptionHasAppliedBase;
  e.requestInFlight=!0,…e.forceSnapshot=r,…this.transport.resync({subscriptionId:e.subscriptionId,
  base:this.subscriptionHasAppliedBase&&n?{logEpoch:n.logEpoch,seq:n.seq}:null,...r?{forceSnapshot:!0}:{}})
```
→ `index-nOVzQNKW.js:22:46470`（off=562315）

**re-anchor 的触发条件**（哪些情况必须重订阅而不是续传）：

```js
…=`deltas`&&!this.subscriptionHasAppliedBase){this.requestRecovery(r===`recovery`);return}
```
→ `index-nOVzQNKW.js:22:43118`（off=558963）

即：**收到 `deltas` 但本地还没应用过 base 快照 → 必须 re-anchor（必要时 `forceSnapshot:true`）**。
另有 `fault.subscription.notOwned` 时直接 `connect()`（全新订阅）：

```js
if(n.includes(`fault.subscription.notOwned`)){this.connect();return}
```
→ `index-nOVzQNKW.js:22:47255`（off=563055）

### 2.6 背景行为：平台侧事实（**不在网页 bundle 内**，独立取证）

> **取证方式声明**：本节**不是**从网页快照读出来的，而是由本会话另派的独立调研子代理用
> Chromium / AOSP 源码、Chrome 官方文档、Chromium 工程师邮件列表与同行评审论文取证得到的。
> **与 §2.7~§2.9 的"源码已证"分级不同，本节单列**，并在 §5 中以 B# 编号引用。
> （本机内置检索对这些站点连接失败，实际检索经由外部 MCP 检索通道完成；下述链接均为真实来源。）

| # | 事实 | 等级 | 来源 |
|---|---|---|---|
| B1 | Chromium 后台定时器分三档：**不节流**（页面可见 / 30 s 内发过声）／**1 次/秒**（隐藏即刻生效，只要链式计数 ≥5）／**1 次/分钟**（隐藏 >5 min **且** 链式计数 ≥5 **且** 静音 ≥30 s **且** 未用 WebRTC） | 高（官方） | [Chrome 88 计时器节流](https://developer.chrome.com/blog/timer-throttling-in-chrome-88) |
| B2 | 源码阈值：`kIntensiveWakeUpThrottling_GracePeriodSeconds_Default = 5*60`；"已完全加载"的快速路径为 **60 s**（**不是**提案里的 10 s，且该快速路径官方 Intent 明示 **desktop only**） | 高（源码 + 官方 Intent） | [Blink `features.h`](https://chromium.googlesource.com/chromium/src/+/HEAD/third_party/blink/renderer/platform/scheduler/common/features.h)、[blink-dev Intent](https://groups.google.com/a/chromium.org/g/blink-dev/c/j095jobY9Qw) |
| B3 | `setInterval` 的**每一次迭代都计入 chain count** ⇒ 用 `setInterval` 做心跳是最容易被压到 1 次/分钟的写法 | 高（官方） | 同 B1 |
| B4 | **"用了 WebSocket 就免于重度节流"的豁免已失效**：`AllowAggressiveThrottlingWithWebSocket` 默认启用，之后该 feature 被删除 | 高（源码 + issue） | [`websocket_channel_impl.cc`](https://chromium.googlesource.com/chromium/src/+/4c7ee57e51c771ffa13d7ca73fa8cfbc62089027/third_party/blink/renderer/modules/websockets/websocket_channel_impl.cc)、[issue 40128284](https://issues.chromium.org/issues/40128284) |
| **B5** | **节流只掐 `setTimeout`/`setInterval`/`scheduler.postTask`，"does not affect Web Sockets"** —— 服务端推来的消息 `onmessage` **仍会正常派发**，不会因节流被丢或延迟到分钟级 | 高（Chromium 工程师原话） | [scheduler-dev](https://groups.google.com/a/chromium.org/g/scheduler-dev/c/BVQbTtNU8Kw) |
| B6 | 从 WebSocket 事件处理器里发出的**一次性**（非深递归）`setTimeout` **不会**被重度节流；但嵌套 >5 层后同样中招 | 高（同上） | 同 B5 |
| B7 | 浏览器**不会**因节流而主动断开后台 WebSocket。网络层的 Background Tab Resource Load Throttling **明确把 WebSocket 排除**在外，"to allow keep-alive connections" | 高（官方） | [chromestatus 5527160148197376](https://chromestatus.com/feature/5527160148197376) |
| B8 | `WebView.onPause()` javadoc 原文：**"this call does not pause JavaScript"**；真正全局硬暂停 JS 定时器的是 **`pauseTimers()`**（对**所有** WebView 生效） | 高（AOSP 源码） | [AOSP `WebView.java`](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/webkit/WebView.java) |
| B9 | **强推断**：Android System WebView 共用 Blink Scheduler，且可见性由 `AwContents` 以 `visible = mIsAttachedToWindow && mIsViewVisible && mIsWindowVisible` 喂给 `WebContents`，因此**同样适用 B1 的节流**。**未找到官方明文** | 中高（源码结构一致） | [`AwContents.java`](https://chromium.googlesource.com/chromium/src/+/refs/heads/master/android_webview/java/src/org/chromium/android_webview/AwContents.java) |
| B10 | `freeze`/`resume` **不是**"退到后台"的事件：浏览器"at their discretion"决定是否冻结，主要来源是 bfcache 进出与内存/省电压力 | 高（官方） | [Page Lifecycle API](https://developer.chrome.com/docs/web-platform/page-lifecycle-api) |
| B11 | `document.wasDiscarded` **仅桌面 Chrome 支持，Android 未支持**（issue 仍跟踪中） | 高（官方） | 同 B10 + [issue 40268039](https://issues.chromium.org/issues/40268039) |
| B12 | **前台服务不能解除 JS 定时器节流**：节流判定只依据**页面可见性**，与进程优先级无关。前台服务换来的是"进程不被 Cached Apps Freezer 冻结 + 网络可用 + `onmessage` 仍能派发"，不是"定时器全速" | 中（官方分项事实 + 逻辑推论） | B1 + B8 + [Cached apps freezer](https://source.android.com/docs/core/perf/cached-apps-freezer) |
| B13 | Android 侧真正的杀手是**进程被冻结 + 网络被挂起**（Doze "Network access is suspended"；Standby Buckets 限制网络；Cached Apps Freezer 对 cached 进程 SIGSTOP），而非浏览器定时器节流 | 高（官方） | [Doze](https://developer.android.com/training/monitoring-device-state/doze-standby)、[Standby Buckets](https://developer.android.com/topic/performance/appstandby)、[Cached apps freezer](https://source.android.com/docs/core/perf/cached-apps-freezer) |
| B14 | 运营商 NAT 对空闲 TCP 的**静默**回收实测窗 **<5 min ~ >30 min**（73 家运营商中 11 家 <10 min）；代理/负载均衡（Nginx/ALB 60 s、GCLB 30 s）**通常先到** | 高（同行评审） | [SIGCOMM 2011 p374](https://conferences.sigcomm.org/sigcomm/2011/papers/sigcomm/p374.pdf)、[CoNEXT 2013 p211](https://conferences.sigcomm.org/co-next/2013/program/p211.pdf) |
| B15 | 心跳业界惯例 **30–45 s**（"最短代理超时 × 0.75"）；移动端必须"回前台立刻补心跳 + 漏 pong 立即重连" | 中（权威技术站点） | [websocket.org heartbeat](https://websocket.org/guides/heartbeat/) |
| B16 | ColorOS 在 AOSP 之外**额外**杀后台（含前台服务），需用户手动开"允许后台运行 / 允许自启动 / 电池用量不受限 / 最近任务加锁"；**OPPO 无开发者文档**说明其杀 FGS 的确切条件 | 中（OPPO 官方用户文档 + 社区） | [OPPO 支持](https://support.oppo.com/ae/answer/?aid=2020322)、[dontkillmyapp/oppo](https://dontkillmyapp.com/oppo) |

**B1~B9 对本方案的三条直接推论（本次独立设计中最关键的一次机制修正）**：

1. **"40~70 秒后连接失效"的主因很可能不是"心跳定时器被节流到 1 次/分钟"**，而是
   **网页自己在 `visibilitychange → hidden` 时调了 `stopHeartbeat()`**（§2.2 第 1 条与 §2.9-② 的源码证据）。
   两者表现相似，但**修复路径完全不同**：前者改心跳写法可缓解；后者是源码事实 ——
   **网页进后台就是不发心跳**，只能由原生接管。
2. **`onmessage` 在后台仍会被派发（B5）** ⇒ 收包通道本身没坏；被破坏的是**心跳契约**
   （`pair_status_query` 停了 ⇒ 中转/桌面端判死 ⇒ 停止推送）。
   因此题述"后台无法继续接收流式正文"的**直接原因是心跳停止，而不是收包被节流**。
3. **不要指望"加前台服务 + 改心跳写法"替代原生接管**（B12）：前台服务解决的是 B13（进程/网络），
   而"网页在后台不发心跳"（源码事实）与 `document.visibilityState === 'hidden'` 都改变不了。
   ⇒ **原生接管是唯一同时满足"心跳照发 + 收包不依赖 Blink 调度"的路径，本方案方向因此被强化。**

> **反向条件**：若真机证明 **B9 不成立**（Android WebView 实际不做 1 次/分钟节流），
> 本方案的**必要性不变**（§2.2 第 1 条的 `stopHeartbeat()` 是源码事实），
> 但**紧迫性下降**，且网页侧可能只需"后台不停心跳"即可缓解 —— 这正是 §6-E1 必须最先做的原因。

### 2.7 快照与增量帧结构

**帧主体**（`frame` 字段的内容）：

```js
function qc(e,t){return Ka({topic:Ji(),subscriptionId:Ji(),fromSeq:Di(),toSeq:Di(),sentAt:to,
  payload:Xi(`kind`,[Ka({kind:Xa(`snapshot`),snapshot:e}),Ka({kind:Xa(`deltas`),deltas:Pi(t)})])})}
```
→ `index-nOVzQNKW.js:2:50166`（off=108272）

**topic 命名（强约束）**：必须 `conversation/<非空 sessionId>`，长度恰好 13 即视为非法：

```js
var Jc=qc(Fne,Ole).superRefine((e,t)=>{(!e.topic.startsWith(`conversation/`)||e.topic.length===13)&&
  t.addIssue({code:`custom`,message:`invalid conversation topic`,path:[`topic`]})})
function due(e){return`conversation/${e}`} function Zc(e){if(!e.startsWith(`conversation/`))return null;let t=e.slice(13);return t.length>0?t:null}
```
→ `index-nOVzQNKW.js:2:50356` / `:2:59039` / `:2:59091`（off=108462 / 117145 / 117197）

会话 store 侧发起订阅时用的就是 `due(sessionId)`：

```js
let e=await this.transport.subscribe({topic:this.topic,base:n?{logEpoch:n.logEpoch,seq:n.seq}:void 0});
```
→ `index-nOVzQNKW.js:22:38995`（off=554895）

**delta 操作集**（会话数据面的全部变更种类）：

```js
var Ole=Xi(`op`,[Ka({op:Xa(`row.appended`),row:Ea}),Ka({op:Xa(`row.upserted`),row:Ea}),
  Ka({op:Xa(`row.removed`),fromRowId:Di()}),Ka({op:Xa(`row.delta`),rowId:Di(),path:Gae,append:Ji()}),
  Ka({op:Xa(`state.updated`),patch:Dle})])
```
→ `index-nOVzQNKW.js:2:43575`（off=101681）

**纯函数 reducer**（原生可以用同样语义复刻"最新 AI 正文"）：

```js
function kle(e,t,n){switch(t){case`text`:return e.kind===`assistantText`||e.kind===`reasoning`?{...e,text:e.text+n}:e;
  case`inputText`:return e.kind===`toolCall`?{...e,inputText:e.inputText+n}:e;
  case`output.text`:return e.kind===`toolCall`&&e.output?{...e,output:{...e.output,text:e.output.text+n}}:e;
  case`summaryText`:return e.kind===`subagent`?{...e,summaryText:e.summaryText+n}:e}}
function Ale(e,t){switch(t.op){case`row.appended`:return{...e,rows:{...e.rows,window:[...e.rows.window,t.row],…}};
  case`row.upserted`:{…}case`row.removed`:{…}case`row.delta`:{…i[n]=kle(r,t.path,t.append)…}case`state.updated`:return{...e,...t.patch}}}
```
→ `index-nOVzQNKW.js:2:43798` / `:2:44118`（off=101904 / 102224）

**快照帧的两条硬校验**（原生若自己造快照帧必须满足）：

```js
n.kind===`snapshot`&&(e.fromSeq!==0&&t.addIssue({code:`custom`,message:`snapshot frame must start at seq zero`,…}),
  n.snapshot.logEpoch!==e.logEpoch&&t.addIssue({…message:`frame and snapshot logEpoch must match`…}))
```
→ `index-nOVzQNKW.js:2:82610`（off=140716）

**逻辑帧组装预算**（原生侧的内存/超时护栏必须对齐，否则行为分叉）：

```js
this.maxAssemblyBytes=nl(t.maxAssemblyBytes,Pa.logicalFrameAssemblyMaxBytes,`maxAssemblyBytes`),
this.maxFragments=nl(t.maxFragments,Pa.logicalFrameAssemblyMaxFragments,`maxFragments`),
this.maxConcurrentAssemblies=nl(…Pa.logicalFrameAssemblyMaxConcurrent…),
this.maxStagedDecodedBytes=nl(…Pa.logicalFrameAssemblyMaxStagedBytes…),
this.timeoutMs=nl(t.timeoutMs,Pa.logicalFrameAssemblyTimeoutMs,`timeoutMs`),
this.maxPhysicalFrameBytes=nl(t.maxPhysicalFrameBytes,Pa.maxFrameBytes,`maxPhysicalFrameBytes`)
```
→ `index-nOVzQNKW.js:2:61314` ~ `:2:61954`（off=119420 ~ 120060）；默认值见 §2.4 `Gs`

组装完成后的去重/超序语义（`recovery` 帧可以**回退**已 settled 的故障记录）：

```js
let a=vue(e.deliveryKind);if(a===`recovery`)for(let t=n.length-1;t>=0;--t){let r=n[t];r?.kind===`fault`&&
  r.fault.topic===e.topic&&…&&r.fault.logicalFrameOrdinal<e.logicalFrameOrdinal&&n.splice(t,1)}
```
→ `index-nOVzQNKW.js:2:62374`（off=120374）

**CRC 校验与 UTF-8 严格解码**（客户端侧）：

```js
if(Wee(u)!==c.checksum.value)return this.settle(r,c),n.push(this.fault(c,`proto.frameAssemblyChecksumMismatch`)),n;
…f=new TextDecoder(`utf-8`,{fatal:!0}).decode(u)…
```
→ 精确引用：`index-nOVzQNKW.js` off=125119 / 125125（`Wee(u)!==c.checksum.value` → `proto.frameAssemblyChecksumMismatch`）

### 2.8 AI 对话正文走哪条通道、topic 命名、如何取"最新一段 AI 正文"

**通道**：WS#1（`/ws`）→ `type:'data'` → `zcode_type:'rpc-frame'` → `dataBase64` 解出逻辑帧 →
`frame.payload.kind==='deltas'` → `op:'row.delta'` + `path:'text'`。

**topic**：`conversation/<sessionId>`（§2.7 已证）。

**"最新一段 AI 正文"的取法（源码里现成的语义，原生照抄即可）**：

```js
function Bot(e){return e.kind===`assistantText`}
function Oot(e,t){let n=Math.max(8,t);return e.length<=n?e:`${e.slice(0,n-3).trimEnd()}...`}
function LX({texts:e,fallback:t,maxPreviewChars:n,maxPreviewParagraphs:r}){let i=Dot(e.join(`\n\n`),r);
  return i.length===0?t:Oot(i.join(`\n`),n)}
function kot(e,t){return e.assistantTextRows.length>0?{assistantPreview:LX({texts:e.assistantTextRows.map(e=>e.text),
  fallback:t.assistantEmptyPreview,maxPreviewChars:t.maxPreviewChars,maxPreviewParagraphs:t.maxPreviewParagraphs}),
  assistantPreviewKind:`text`}:e.isRunning?{assistantPreview:t.assistantRunningPreview,assistantPreviewKind:`running`}
  :{assistantPreview:t.assistantEmptyPreview,assistantPreviewKind:`empty`}}
```
→ `index-nOVzQNKW.js:168:7432`（off=2219700）、`:168:34`（off=2212302）、`:165:18112`（off=2212133）

`assistantTextRows` / `latestAssistantTextRow` 是会话投影状态里的字段：

```js
…latestAssistantTextRow:n,workspacePath:r,workspaceHomePath:i,compactForRemoteControl:a,…
```
→ `index-nOVzQNKW.js` 行 160 区，off=2112496

**原生提取规则（建议，可直接落地）**：

```
state.rows.window 中筛 kind==='assistantText' 的 row，按 rowId 升序 → assistantTextRows
最新一段 = rowId 最大的那条（= latestAssistantTextRow）→ row.text
"正文"（区别于 reasoning）= 只取 kind==='assistantText'，不要混入 kind==='reasoning'
流式增量 = 对最新那条 rowId 持续应用 {op:'row.delta', rowId, path:'text', append}
```

**辅助判据**：`row.delta` 的 `path` 枚举与 row kind 的对应关系见 §2.7 的 `kle`，可以直接按
`path==='text' && row.kind==='assistantText'` 判断"这是 AI 正文增量"。

### 2.9 可见性与生命周期：页面对自己做了什么（决定"是否必须重载"）

**① 一个统一的 observer，把所有生命周期事件映射成 suspend/recover 两态**：

```js
function c4t({documentTarget:e,windowTarget:t,onSuspend:n,onRecover:r}){let i=!1,a=()=>{i=!0,n()},
  o=e=>{i&&(i=!1,r(e))},s=()=>{if(e.visibilityState===`hidden`){a();return}o(`visible`)},
  c=()=>o(`pageshow`),l=()=>o(`online`),u=()=>o(`resume`),d=()=>a(),f=()=>a();
  return e.addEventListener(`visibilitychange`,s),e.addEventListener(`freeze`,f),e.addEventListener(`resume`,u),
  t.addEventListener(`pageshow`,c),t.addEventListener(`pagehide`,d),t.addEventListener(`online`,l),{dispose(){…}}}
```
→ `index-nOVzQNKW.js:897:326801` ~ `:897:327382`（off=4711815 ~ 4712396）

映射表（`o(e)` 的 `e` 即恢复原因）：

| 事件 | 动作 | 传给 `onRecover` 的 reason |
|---|---|---|
| `visibilitychange` → `hidden` | `onSuspend()` | — |
| `visibilitychange` → 非 hidden | `onRecover` | `'visible'` |
| `window.pageshow` | `onRecover` | `'pageshow'` |
| `window.online` | `onRecover` | `'online'` |
| `document.freeze` | `onSuspend()` | — |
| `document.resume` | `onRecover` | `'resume'` |
| `window.pagehide` | `onSuspend()` | — |

**② 接线（`onSuspend` 只 suspend，`onRecover` 走完整恢复）**：

```js
let L=c4t({documentTarget:document,windowTarget:window,onSuspend:()=>{E.suspend()},onRecover:e=>{T(e)}});
```
→ `index-nOVzQNKW.js:897:368498`（off=4753512）

**③ 恢复函数 `T` 的分支（决定"哪些情况不用重载、哪些必须重载"）**：

```js
C,w=e=>e===`visible`||e===`pageshow`||e===`online`||e===`resume`,
T=async e=>{if(r||U9||a)return;r=!0;let t=s,i=c;l=X9(`recovery`);let o=!w(e)||d?.isDegraded()===!0;
  o&&d?.markDegraded(),n=!0;try{if(await E.recoverConnection(),!o&&d&&!d.isDegraded()){l=void 0;return}
  await C?.({preferredWorkspaceKey:t,preferredTaskId:i})}catch{!a&&!U9&&E.recoverInBackground()}finally{r=!1,n=!1}};
```
→ `index-nOVzQNKW.js:897:361792` ~ `:897:362152`（off=4746806 ~ 4747166）  <!-- C24/C31 引用 361792 起点 -->

读法：
- reason ∈ {`visible`,`pageshow`,`online`,`resume`} **且** bridge 未 degraded → `recoverConnection()` 成功后**直接 return**，
  **不重建工作区、不 reload**。→ **说明"回前台"本身不需要重载页面**。
- 否则（reason 不在集合内，或 bridge 已 degraded）→ 走 `C()` 即重新桥接工作区（`switchWorkspace` 路径），
  这才是需要重建的阶段。
- 失败 → `E.recoverInBackground()`（但见 §2.3：`kicked` 时它是空操作）。

**④ `recoverConnection()` 里那条**看起来像**"复用旧 socket"的分支，实际**永远不会生效**（重要！）**：

```js
async recoverConnection(e=15e3){if(this.emitDiagnostic({type:`recover-start`,state:this.state}),this.clearReconnectTimer(),
  this.clearStaleWaitingRecoveryTimer(),this.stopHeartbeat(),this.clearWaitingTimer(),this.staleWaitingCount=0,
  this.state===`suspended`&&this.wasPaired&&this.socket?.readyState===this.WebSocketCtor.OPEN&&
  (this.setState(`reconnecting`),this.sendPairStatusQuery()))
  try{await this.waitForPaired(this.options.healthCheckTimeoutMs??3e3);return}catch{}
  this.reconnectAttempt=0,this.reconnectNow(),await this.waitForPaired(e)}
```
→ `index-nOVzQNKW.js:897:315666`（off=4700666）

`A&&B&&C&&D()||E` 的优先级使**无论**旧 socket 是否 OPEN，后面那句
`this.reconnectAttempt=0,this.reconnectNow(),await this.waitForPaired(e)` **都会执行**。而 `reconnectNow()`：

```js
reconnectNow(){let e=this.socket;this.socket=void 0,e?.close(),this.setState(`reconnecting`),
  this.lastPairStatusAckAt=Date.now(),this.connect()}
```
→ `index-nOVzQNKW.js:897:321546`（off=4706546）

> **结论**：网页每次"回前台"都会**关掉旧 socket 并重新 dial**。旧 socket 的 close 与新 socket 的
> auth/pair 在时序上重叠——这正是**KICKED 的一个现实来源**。原生方案必须避免复制这个行为。

**⑤ 一条被设计但已失效的"陈旧权威"恢复路径**（对方案有正面价值：说明中转本身**允许**同一 device 重连接管，
只要旧连接已断）：

```js
scheduleStaleWaitingRecovery(){this.staleWaitingRecoveryTimer||=setTimeout(()=>{this.staleWaitingRecoveryTimer=void 0,
  (this.state===`paired`||this.state===`reconnecting`||this.state===`connecting`||this.state===`authenticating`)&&
  this.staleWaitingCount>0&&this.reconnectAfterStaleWaiting()},this.options.staleWaitingRecoveryMs??1500)}
```
→ `index-nOVzQNKW.js:897:320658`（off=4705658）

但全 bundle 内 `staleWaitingCount` **只有写 0、没有自增**（`staleWaitingCount++` / `+=1` 均 0 命中），
`scheduleStaleWaitingRecovery()` **无任何调用点**。→ **这段恢复逻辑是死代码**（源码已证）。

**⑥ 页面向桌面端上报自己的可见性**（原生可以复用同一字段做诊断对齐）：

```js
v=e=>{e.type!==`socket-extensions`&&p?.({zcode_type:`mobile-diagnostic`,event:e.type,timestamp:e.timestamp,
  state:e.state,previousState:…,pairStatus:…,closeCode:…,closeReason:…,wasClean:…,wasPaired:…,
  visibilityState:e.visibilityState,online:e.online,hiddenDurationMs:e.hiddenDurationMs})}
```
→ `index-nOVzQNKW.js:897:360946`（off=4745946）

`hiddenDurationMs` 的计算（`hiddenStartedAt` 在 `suspend()` 里置位）：

```js
let t=typeof document>`u`?`unknown`:document.visibilityState,n=this.hiddenStartedAt&&t!==`hidden`?
  Date.now()-this.hiddenStartedAt:void 0;n!==void 0&&(this.hiddenStartedAt=void 0),this.options.onDiagnostic?.({...e,…})
```
→ `index-nOVzQNKW.js:897:325145`（off=4710145）

**⑦ `beforeunload` 会置全局 `U9=!0`，抑制所有失败界面**：

```js
var V9=(0,Hse.createRoot)(document.getElementById(`root`)),H9=K2t(),U9=!1,W9=!1;
window.addEventListener(`beforeunload`,()=>{U9=!0});
```
→ `index-nOVzQNKW.js:897:340266`（off=4725266）

**⑧ 网页未暴露任何原生桥**：全 bundle 内 `addJavascriptInterface` / `AndroidBridge` / `nativeBridge` /
`window.zcode` 均 **0 命中**；`index.html` 内 `javascriptInterface|nativeBridge|WebView|Android` 亦 **0 命中**。
→ 壳端要拿到"页面何时关闭了 socket"这类事件，只能**自己注入**。
（`logger-BVohFQ23.js:1:1` 里确实有一处 `window.zcode?.log?.(...)` 的桥，但它是 `logger` 模块内的可选钩子，
**不在主 bundle 中引用**，不能作为控制通道依赖。）

---

## 3. 原生侧设计

### 3.1 模块划分

| 模块 | 职责 | 依赖网页源码的哪一节 |
|---|---|---|
| `RelayOwner`（单例，状态机） | 唯一决定"当前谁持有 WS#1"；串行化 接管/交还 两个方向的状态转换 | §2.2 §2.3 §2.9 |
| `PageSocketArbiter` | 通过注入的 JS + `evaluateJavascript` 桥，观测/命令网页关闭自己的 WS#1 | §2.9-⑧（网页无桥，必须注入） |
| `RelaySocket` | WS 连接、`auth_init`/`auth_response`、心跳、`pair_status` 解析、退避重连 | §2.1 §2.2 |
| `ProofSigner` | `HMAC-SHA256(passHash, "nonce\|role\|deviceSid")` → base64url 无 padding | §2.1-② |
| `RpcFrameCodec` | `rpc-frame` 分片/重组、CRC-32、身份三元组、ack 生成与发送 | §2.4 |
| `LogicalFrameCodec` | `wireVersion:3` 的 complete/fragment 解析、组装预算、`deliveryKind` 去重 | §2.5 §2.7 |
| `ConversationMirror` | 订阅 `conversation/<sid>`、维护 snapshot+deltas 状态、re-anchor 决策 | §2.5 §2.7 |
| `AiTextExtractor` | 从镜像状态取"最新一段 AI 正文"，做流式增量去重 | §2.8 |
| `FluidCloudPublisher` | 把正文增量投递给 ColorOS 流体云/通知（限流、节流、去抖） | 本方案外，见 §5-R7 |
| `CredentialVault` | 保存 `<SID>/<PASS_HASH>/<MID>/<relayWsUrl>/<workspacePath>/<workspaceIdentity>/<sessionId>` | §2.1-⑥ + §2.5 |

**凭证来源**：全部从**网页当前 URL 与其上下文**抄取一次（§2.1 的 `i4t` options 全部字段），不要自行生成。
`<MID>` 尤其**必须复用**（§2.1-⑥）。

### 3.2 连接状态机（原生侧）

```
                ┌──────────────────────────────────────────────────────────┐
                │                                                          │
  (App 启动)    │                                                          │
      │         │                                                          │
      ▼         │                                                          │
 ┌─────────┐  ①onResume/前台   ┌──────────────┐  ②页面 close 确认  ┌──────────────┐
 │ PAGE_   │─────────────────►│ NATIVE_      │                   │ NATIVE_      │
 │ OWNED   │  (什么都不做)      │ DRAINING     │──────────────────►│ CONNECTING   │
 └─────────┘                   │ (等页面释放) │                   └──────┬───────┘
      ▲                        └──────────────┘                          │
      │                               ▲                                  │ auth_ack
      │                               │ ⑧onResume 触发交还                ▼
      │                        ┌──────┴───────┐                    ┌──────────────┐
      │                        │ PAGE_        │◄───────────────────│ NATIVE_      │
      │                        │ RESTORING    │  ⑦native close     │ PAIRED       │
      │                        │ (reload/重连)│                    └──────┬───────┘
      │                        └──────────────┘                          │
      │                                                          ┌───────┴────────┐
      │                                                          ▼                ▼
      │                                                   ┌────────────┐  ┌────────────┐
      │                                                   │ SUBSCRIBING│  │ WAITING_   │
      │                                                   │ (会话订阅) │  │ PAIR(30s)  │
      │                                                   └─────┬──────┘  └─────┬──────┘
      │                                                         │               │ 超时
      │                                                         ▼               ▼
      │                                                   ┌────────────┐  ┌────────────┐
      │                                                   │ STREAMING  │  │ TERMINAL   │
      │                                                   │ (投递正文) │  │ (见下)     │
      │                                                   └─────┬──────┘  └────────────┘
      │                                                         │
      │                              ┌──────────────────────────┴───────────────┐
      │                              │ ③onPause 前 / 网络抖动 / 心跳 ack 超 30s   │
      │                              ▼                                          ▼
      │                       ┌────────────┐                            ┌────────────┐
      │                       │ RECONNECT  │  0.5→1→2→4→8→10s 封顶      │ SUSPENDED  │
      │                       │ (_BACKOFF) │  每次都是“全新配对”          │ (App 被杀) │
      │                       └────────────┘                            └────────────┘
      │
      └── ④交还失败 或 用户手势重载 ──► PAGE_RESTORING

  TERMINAL 细分（原生侧不再盲目重试，必须区分，抄 §2.3 的判定）：
    · SESSION_CONFLICT  收到 error.code==='KICKED' → 只允许“用户手动重载”或“原生释放 + 网页重连”
    · INVALID_CONN      error.code==='AUTH_FAILED' | 'WRONG_PARAM'  → 凭证失效，清空 CredentialVault
    · DESKTOP_OFFLINE   error.code==='DEVICE_OFFLINE' 后 15s 宽限仍失败
    · RELAY_UNAVAIL     其它 error.code
```

**TEARDOWN 的两条硬规则**：

1. `NATIVE_DRAINING → NATIVE_CONNECTING` 的准入条件必须是**"页面 socket 已确认关闭"**，
   而不是"已经发了关闭命令"。判定手段见 §3.3-②。
2. 原生任何一次 `close` 之前，**不允许**发起新的 dial。即 native **永不**做"边关边连"
   （这正是 §2.9-④ 网页 `reconnectNow()` 的写法和它带来的风险）。

### 3.3 接管/交还时序

#### 接管（App → 后台）

```
① App.onPause() / WebView 可见性变为不可见
   → RelayOwner.onEnterBackground()
   → state: PAGE_OWNED → HANDOFF_PENDING

② PageSocketArbiter 注入脚本（构建期或 addDocumentStartJavaScript 期注入，见下）：
   重写 window.WebSocket，捕获页面创建的 /ws 连接：
     const _WS = window.WebSocket;
     window.WebSocket = function (...a) {
       const s = new _WS(...a);
       if (String(a[0]).includes('/ws')) { window.__zcRelaySockets.add(s); }   // 只登记，不改行为
       return s;
     };
     window.__zcRelayCloseRelay = () => {
       for (const s of window.__zcRelaySockets) { try { s.close(1000, 'native-handoff'); } catch {} }
       window.__zcRelaySockets.clear();
     };
   （只登记 / 不改行为 → 对页面零功能影响）

③ App 调 evaluateJavascript("__zcRelayCloseRelay()")，同时开始等
   等待判据（任一）：
     a) 注入的 close 事件回调（在 addEventListener 包装里上报 NativeBridge.onPageSocketClosed()）
     b) 超时兜底：≥ 1500 ms 未上报 → 视为已释放（宁可多等，不可抢连）
   超时上限：3000 ms

④ 拿到“已释放”信号后：
   → state: HANDOFF_PENDING → NATIVE_CONNECTING
   → RelaySocket.connect(relayWsUrl?mid=<MID>)   // 注意：QueryString 只加 mid，device_sid 走 auth_init
   → auth_init(role=terminal, device_sid=<SID>, meta{platform:…,version:…,name:…})
   → auth_challenge → auth_response(proof=HMAC(passHash, `${nonce}|terminal|${SID}`))
   → auth_ack → 用 pair_status_query 心跳（10s）+ ack 看门狗（30s）
   → pair_status==='matched' → NATIVE_PAIRED
   → 对目标会话 subscribeConversationV4({ workspacePath, workspaceIdentity,
                                          sessionId, topic:'conversation/'+sessionId,
                                          base:{logEpoch,seq} 可选, visibility:'background' })
   → ack.mode==='snapshot' → 先落地整快照；==='resume' → 从 base 续传
   → state: NATIVE_STREAMING → AiTextExtractor → FluidCloudPublisher
```

**为什么②③必须在①之后、且必须"先关后连"**：§2.3 已证 `KICKED` 会落到 `kicked` 终态，而终态无自愈；
§2.2 `mid` 用于区分设备身份。因此并发 dial 的代价是**用户回前台看到"已被其他设备接管"死页**。
把"页面先关"作为前置条件，成本只是后台接管延迟 ≤ 3 s，远小于"死页 + 重扫二维码"。

#### 交还（App → 前台）

```
⑤ App.onResume() / WebView 重新可见
   → RelayOwner.onEnterForeground()
   → state: NATIVE_STREAMING → HANDOFF_PENDING（方向反转）
   → RelaySocket 停止订阅、停心跳、close(1000,'page-resume')
   → 等 socket CLOSED（本地即可判定，无需服务端确认）
   → state: PAGE_RESTORING

⑥ 通知网页恢复。优先级：
   A. 页面从未被销毁、且注入脚本仍在 → evaluateJavascript("location.reload()")
      （理由：§2.9-③ 已证，只要可见性恢复事件正常送达且 bridge 未 degraded，
        网页自己的 recoverConnection 就能把连接拉回来；但 §2.9-④ 显示它必然会
        重 dial，所以让它从干净状态重 dial 最稳）
   B. 页面已被系统回收 → 壳端 loadUrl(原始 remote-v4 URL) 重新加载

⑦ 交还完成的判据：网页侧上报 pair_status 变为 matched（用注入脚本镜像
   onStateChange / onDiagnostic 的 state 字段回传原生，§2.9-⑥ 的 mobile-diagnostic
   同源字段）。在此之后原生彻底释放所有资源。
```

**用户可见代价**：交还瞬间会看到一次页面重载（白屏一闪 + 重新配对动画阶段）。
可优化点在 §4.3。

### 3.4 与网页的关系（谁在前台谁持连接）

| App 状态 | WS#1 持有者 | 网页 | 原生 | 流体云 |
|---|---|---|---|---|
| 前台可见 | 网页 | 正常渲染、正常心跳 | 仅镜像观察（可不开 socket） | 由网页/原生在收到正文时更新 |
| 后台 | 原生 | WebView 存活但不计时（socket 已关） | 持连接、订阅会话、抽正文 | 原生推送 |
| 前台但页面被回收 | 原生（短暂） | 重新加载中 | 继续持有直到网页确认 paired | 原生推送 |
| 崩溃/被杀 | 无 | — | — | 停止 |

**"网页是否必须重载"的结论**：

- **接管阶段：不需要重载。** 页面只是"不再持有连接"，UI 状态没被破坏。
- **交还阶段：以重载为默认。** 理由不是"必须"，而是"最省事且最安全"：
  §2.9-④ 证明网页回前台必然重 dial，若不重载，页面会在一个"我已经被原生顶掉"的残留状态下
  自己重连，可能出现与原生释放的时序竞争；重载把时序变成**严格串行**。
  若真机实验证明 `evaluateJavascript` 能稳定触达页面的 transport 且无竞态，可以退化为
  "只调 close 不 reload"（§6-E5）。

---

## 4. 关键取舍：为什么这样接管不会让用户看到 KICKED 终态

### 4.1 KICKED 的三个可能来源，以及方案各自如何消除

| # | 来源 | 源码依据 | 方案对策 |
|---|---|---|---|
| K1 | **并发 dial**：网页 socket 仍 OPEN 时原生 dial | §2.3 服务端判定（推断）+ `mid` 语义 §2.1-⑥ | 接管前置条件 = "页面 socket 已确认关闭"（§3.3-③），不是"已发关闭命令" |
| K2 | **网页回前台自己重 dial**，与原生释放竞态 | §2.9-④：`recoverConnection` 无条件 `reconnectNow()` | 交还时原生**先 close**，再通知网页 reload/重连（§3.3-⑤⑥） |
| K3 | **凭证不一致**被当成另一台设备 | §2.1-⑥ `mid` 必须复用 | 原生 100% 复制页面的 `<MID>`/`<SID>`/`<PASS_HASH>`，不生成新值 |

三者合起来把"任意时刻只有一条 `/ws`"变成**协议不变式**，而不是"希望它别冲突"。

### 4.2 如果 KICKED 仍发生（无法 100% 避免的情况）

即使串行化，仍有两条不可控路径：
(a) 中转侧对"旧连接是否真的断开"存在**服务端超时**（源码不可见）；
(b) App 被系统杀在"原生已 close、网页还没重连"的窗口里，用户回来看到的是网页的旧失败页。

**降到最小可见代价的做法（按优先级）**：

1. **原生在收到 `KICKED` 时不要转成"用户可见的失败"**，而是：立刻转入
   `HANDOFF_PENDING` 反向流程（主动让位给网页），并在流体云上保留最后一次已收到的正文。
   —— 因为 `KICKED` 的语义是"有更新的控制端"；如果那个控制端其实就是本机的网页，
   让位即刻自愈，用户什么都看不到。
2. **壳端拦截终态界面**：§2.3-③ 已证网页的终态界面是 `_4t` 组件 + "重新连接"→`location.reload()`。
   壳端**不要**在网页处于这个界面时把它当成"正常前台页面"展示超过必要时间：
   检测到该界面（或检测到原生/网页任一侧 `pair_status` 长时间非 matched）即自动 reload 一次。
   **必须加次数上限**（建议 ≤ 2 次 / 5 分钟），否则会与 §2.2 的退避重连形成 reload 风暴。
3. **流体云文案降级**：把"连接中断"做成"正在恢复"，不要做成错误。用户对后台任务的容忍度
   远高于对错误提示的容忍度。
4. **兜底**：若 5 分钟内未恢复 matched，才提示"需要重新扫码"，并**在提示里带上进入后台的时刻**，
   方便用户判断漏掉了多少内容。

### 4.3 其他取舍

| 取舍 | 选择 | 理由 |
|---|---|---|
| 原生是否需要实现 WS#2（窗口控制 socket） | **不需要** | §2.0：WS#2 不带 AI 正文，不参与接管判定 |
| 原生是否实现完整会话投影（rows/plans/fileChanges/…） | **只实现到"能取 AI 正文"所需的最小集**：snapshot + `row.appended/upserted/removed/delta` + `state.updated` | §2.7：这四个 op 覆盖了正文；其余（plans/fileChanges/attachment）可延后 |
| 断线后是 `resume` 还是 `forceSnapshot` | 原则：**本地已应用过 base 且 logEpoch 未变 → `base:{logEpoch,seq}` 续传；否则 `forceSnapshot:true`** | §2.5：页面同款判据（`!subscriptionHasAppliedBase → 必须 re-anchor`） |
| 后台心跳是否照抄 10s/30s | **照抄，不要放宽** | §2.2 是页面自己的容忍窗；改小省电但离 45s 太近，改大浪费电且无收益 |
| 是否用持久通知承载 | **用**（前台服务 + 常驻通知） | 与 §5-R4 相关：进程存活是**必要不充分**条件，见 §5 |

---

## 5. 风险与未验证点（严格区分"源码已证"与"推断"）

### 5.1 源码已证（可作为设计地基）

| # | 结论 | 证据 |
|---|---|---|
| C1 | WS#1 = `/ws`（或 `/ws/remote/<ID>`），`window.location.host` 拼出，承载全部数据面 | `index-nOVzQNKW.js:897:349646` |
| C2 | WS#2 = `/ws/remote-control/window/<TOKEN>`，只做窗口控制，需先 `bootstrap` | `:897:350870`、`:897:350132` |
| C3 | 握手 `auth_init(role=terminal, device_sid, meta, client_ts)` | `:897:314026` |
| C4 | `proof = base64url_nopad(HMAC_SHA256(passHash, `${nonce}\|terminal\|${deviceSid}`))` | `:897:310892`、`:897:310775` |
| C5 | 心跳 = `pair_status_query`，间隔 10 s，ack 看门狗 30 s，超时即"关 socket 重连" | `:897:319272`、`:897:319432`、`:897:319652` |
| C6 | 重连退避 `min(10s, 500·2^n)`，且每次清零 `wasPaired`（=全新配对） | `:897:321596` |
| C7 | 进入后台时网页**主动停心跳**，但**不关 socket**（僵尸连接） | `:897:315466` |
| C8 | `KICKED` → `setState('kicked')` + `enterTerminalFailure`（`intentionallyClosed=true`） | `:897:318429`、`:897:323895` |
| C9 | `kicked` 无自愈：close 分支被 `intentionallyClosed` 拦、`recoverInBackground` 直接 return、UI 只做 reload | `:897:314499`、`:897:316172`、`:897:340319` |
| C10 | 物理帧 `rpc-frame`：`bridgeSessionId/bridgeGeneration/recoveryId/seq/messageSeq/fragmentIndex/fragmentCount/messageBytes/checksum/dataBase64`，`.strict()` | `src-DHgFesxz.js:39:137095` |
| C11 | ack 帧 `rpc-frame-ack` 必须带同一身份三元组 + `ackMessageSeq`，不匹配即整帧丢弃 | `src-DHgFesxz.js:39:137791`、`index-nOVzQNKW.js:897:287172`、`:897:292812` |
| C12 | 上限：单物理帧 ≤ 1 MiB、单消息 ≤ 16 MiB、≤ 64 片、组装超时 30 s、id ≤ 256 且 `^[A-Za-z0-9._~-]+$` | `src-DHgFesxz.js:39:37066`、`:39:136492` |
| C13 | ack/replay 宽限 **45 s**（`replayBufferGraceMs:45e3`），超时 `terminal:true` | `index-nOVzQNKW.js:897:286812`、`:897:286386` |
| C14 | CRC-32 实现与输出格式（小写 8 位 hex） | `src-DHgFesxz.js:39:135280`、`:39:136632` |
| C15 | 逻辑帧 `wireVersion:3`，`kind ∈ {complete,fragment}`，`deliveryKind ∈ {initial,online,recovery}` | `index-nOVzQNKW.js:2:46884`、`:2:104933` |
| C16 | `frame.payload.kind ∈ {snapshot,deltas}`，快照帧必须 `fromSeq===0` 且 `logEpoch` 与帧一致 | `:2:50166`、`:2:82610` |
| C17 | delta op 集：`row.appended / row.upserted / row.removed / row.delta(path,append) / state.updated` | `:2:43575`、`:2:44118` |
| C18 | `path:'text'` 只作用于 `kind==='assistantText'` 或 `'reasoning'`；AI 正文 = `assistantText` | `:2:43734` |
| C19 | topic 必须 `conversation/<非空sid>` | `:2:50356`、`:2:59039` |
| C20 | 订阅请求 `{workspacePath, workspaceIdentity?, sessionId, topic, base?, visibility?}`，ack `{subscriptionId, mode:'snapshot'\|'resume', logEpoch}` | `:2:49538`、`:2:49671`、`:22:28203` |
| C21 | resync 请求 `{subscriptionId, base:{logEpoch,seq}\|null, forceSnapshot?, topic, connectionId}` | `:2:52261`、`:2:52558` |
| C22 | re-anchor 判据：收到 `deltas` 且未应用过 base → 必须重订阅；`fault.subscription.notOwned` → 全新 connect | `:22:43118`、`:22:47255` |
| C23 | 生命周期：hidden/freeze/pagehide → suspend；visible/pageshow/online/resume → recover | `:897:326801` |
| C24 | **可见性恢复本身不需要 reload**：reason 合法且 bridge 未 degraded 时 `recoverConnection()` 后直接 return | `:897:361792` |
| C25 | 但 `recoverConnection()` **无条件** `reconnectNow()`（关旧 socket、dial 新 socket） | `:897:315652`、`:897:321532` |
| C26 | "陈旧权威恢复"逻辑是**死代码**（`staleWaitingCount` 无自增、`scheduleStaleWaitingRecovery` 无调用） | `:897:320644` + 全文件计数为 0 |
| C27 | URL 上只加 `mid` 查询参数；`device_sid` 只走握手帧 | `:897:313473` |
| C28 | 网页**无任何原生桥**（`addJavascriptInterface`/`window.zcode` 在主 bundle 0 命中；`index.html` 0 命中） | 全文件计数 |
| C29 | 每次将进入 `error`/`kicked` 都会 reject 所有在飞 RPC | `:897:324385` |
| C30 | `suspend()` 仅对 `paired/connecting/reconnecting` 生效（`waiting` 不 suspend） | `:897:315452` |
| C31 | 进入远控页的参数解析 `eie(URLSearchParams)` 产出 `{theme,deviceSid,passHash,deviceMid,appVersion}` 且直接喂给 transport | `:897:360518`、`:897:362399` |

### 5.2 推断（未经源码证实，必须真机或抓包验证）

| # | 推断 | 置信度 | 如何验证 |
|---|---|---|---|
| R1 | `relayWsUrl ≈ wss://zcode.z.ai/ws`（页面同源即中转） | 中 | 真机抓 `R4t()` 求值结果 / DevTools Network 的 WS URL |
| R2 | **服务端 KICKED 的触发条件**是"同一 device_sid 下第二个连接完成 auth 并 claim 配对" | 中高 | 双端实验：A 保持 paired，B 用同 sid 不同 mid 连接，观察 A 收到什么、何时收到 |
| R3 | 服务端判定"旧连接已死"存在超时（源码不可见），因此"先关后连"仍需等待窗口 | 中 | 实验 E2：控制"关闭→新连"的间隔，扫描 KICKED 出现的临界时间 |
| R4 | 题述"WebView 隐藏后定时器被节流到 ~1 次/分钟" —— **主 bundle 内无法证实**（这是平台行为）。**现已由 §2.6 独立取证升级**：Chromium 侧节流机制（B1/B2）与 `onmessage` 不被节流（B5）**已证**；Android WebView 是否**同样**执行 1 次/分钟仍为强推断（B9），须真机确认 | B1~B8 高（官方/源码）；B9 中高 | 见 §6-E1、§6-E13（本会话已另派子代理完成 Chromium/AOSP 官方取证） |
| R5 | 页面 CSS/`index.html` 无对后台的特殊处理 | 低（未逐行读 CSS） | 已确认 `index.html` 无原生桥；CSS 未作为证据 |
| R6 | `conversation/<sid>` 的 `sessionId` 在原生侧可从页面 URL/路由直接取到 | 中 | 实验 E4：从注入脚本回传当前 topic |
| R7 | 流体云（ColorOS）能否在纯前台服务下持续接收原生推送 —— **本方案外**，且与 B12/B13 的平台事实强相关 | — | 单独实验 |
| **R8** | **机制修正（重要）**：题述"后台 40~70 s 连接失效"的**直接主因是网页自己调了 `stopHeartbeat()`**（源码事实 §2.2-1），而非收包被节流（B5 已证 `onmessage` 不受影响）。"节流"是**叠加因素**，不是唯一因素 | 中高（源码事实 + B5） | 实验 E1 + E14：分别测"页面在后台是否仍发 `pair_status_query`"与"后台是否仍收得到帧" |

### 5.3 已识别但**当前证据不足以判定**的疑点

- **U1**：`xae(interval, jitter)` / `Hie` / `Pie` 三个 jitter 辅助函数定义在 `toast-HRdaJheI.js` 的
  re-export 之外（`a as b` / `h as Hie` / `g as Pie`），在这份快照里我**未能定位其函数体**
  （`jitter` 关键字在 `src-DHgFesxz.js`、`catalogTree-P8S5ypDb.js` 均 0 命中）。
  **影响**：心跳的抖动范围未知（最坏情况可能比 10 s 更疏）。**不影响设计**——原生可以直接用固定 10 s。
- **U2**：URL 查询串里承载 `<SID>/<PASS_HASH>/<MID>` 的**具体参数名**未定位（解析函数被压缩后
  与 React internals 同名冲突）。**影响**：原生不必知道参数名——它从 `i4t` options 抄值即可（C31）。
- **U3**：`Xee`（`index-nOVzQNKW.js:897:293261` 中解出 `rpc-frame` 的那个入口，off=4678289）来自
  `src-DHgFesxz.js` 的导出别名 —— 已定位到导出语句本身：`src-DHgFesxz.js:44:7979`（off=269531）中的
  `…,Ah as Es,…`（即 `Ah` 在 `src-DHgFesxz.js` 内被导出为 `Es`，进主 bundle 后再被重命名为 `Xee`）；
  但 `Ah` 的**函数体**在本次扫描中**未定位**（`src-DHgFesxz.js` 有 273 KB，只做了符号级定位）。
  **影响**：本地已用 `Ch` schema（C10）替代其语义，风险低。
- **U4**：`xae` / `Hie` / `Pie`（心跳与重连抖动）与 `Wee`（客户端 CRC 复核）三个辅助函数的**函数体**
  均未定位 —— 它们经 `toast-HRdaJheI.js` 的 re-export（`h as Hie` / `g as Pie`）与
  `src-DHgFesxz.js` 的 `Ds as Wee` 引入，但 `src-DHgFesxz.js:44` 的导出表里**没有 `Hie` / `Pie`**
  （这两个名字只在主 bundle 的 import 别名中出现），故其宿主模块未确认。
  **影响**：抖动范围与客户端 CRC 复核细节未确认；但 CRC 算法本体（`ph`，C14）已证，原生照它实现即可。

---

## 6. 真机实验清单

> 每条格式：**目的 / 操作 / 判据**。全部实验都在真机 + 真桌面端上做，且**只观察不改动线上代码**。
> **优先级**：E1 → E14 → E2 必须最先做 —— E1/E14 决定"问题到底是心跳停还是收包停"（§2.6 的机制修正），
> E2 决定 §3.3 的等待窗口取值，三者直接影响方案形态。

| ID | 目的 | 操作 | 判据 |
|---|---|---|---|
| **E1** | 证伪/证实"WebView 隐藏后定时器被节流到 ~1 次/分钟"（验证 B9） | 在页面注入 `setInterval(()=>bridge.ping(Date.now()), 1000)`，壳端记录到达间隔；App 切后台/锁屏持续 5 分钟；**并同时注入 `document.addEventListener('freeze'/'resume')` 与 `visibilitychange` 打点** | 得到"进入后台后 tick 间隔"时间序列。稳定 ≈60 s/次 → B9 成立；仍是 ≈1 s → B9 不成立（题设前提需修正）。同时记录 `freeze` 是否**从未**触发（验证 B10 在 Android 的推论） |
| **E2** | 确定"关闭旧 socket → 新连接"的安全间隔（决定 §3.3-③ 的超时取值） | 脚本化：页面 socket `close()`，延时 T 后原生 dial；T 从 0 开始以 250 ms 递增到 5 s，每组重复 5 次 | 找出 KICKED 出现的最小 T（记 T*）。方案取 `max(1500, T*×2)` 作为等待窗口；若 T=0 也不 KICKED，则可去掉等待、直接串行 |
| **E3** | 确认 KICKED 的真实触发条件（验证 R2） | 端点 A 保持 paired；端点 B 用**同 `<SID>`+同 `<MID>`** 连接；再用**同 `<SID>`+不同 `<MID>`** 连接 | 记录 A 收到 `{type:'error',code:'KICKED'}` 的时机、以及 B 的 `pair_status`。判据：同 mid → A 被顶；不同 mid → 观察 A 是否也被顶（决定 §2.1-⑥ 的严格程度） |
| **E4** | 验证原生能独立完成配对与订阅 | 原生只实现 §2.1 + §2.4 + §2.5 最小集，从零 dial、握手、`subscribeConversationV4` | 原生收到的 `ack.mode` 与 `ack.subscriptionId` 与网页侧同会话一致；能解出至少一条 `row.delta(path='text')` |
| **E5** | 验证"交还时不 reload"是否可行（决定 §4 的可见代价） | 原生只 `close`，不 reload 页面；观察页面在 `visibilitychange→visible` 后自己恢复的耗时与是否出现 `kicked` | 若 10 次中 ≥9 次能自愈且无 KICKED → 可去掉 reload；否则保留 reload |
| **E6** | 验证交还后 `KICKED` 是否还会残留 | 连续做 20 次"后台→前台"循环，每次后台停留 3 分钟 | 统计出现"已被其他设备接管"界面的次数。**目标 0 次**；若非 0，记录当时的 T 间隔与网络状态 |
| **E7** | 验证"原生先 close 再 reload"是否比"原生 close + 网页自恢复"更稳 | 对比 E5/E6 两种交还策略各 20 次 | 比较 KICKED 次数 + 用户可见白屏时长，选优 |
| **E8** | 定位 45 s 容忍窗在**本链路**上的实际表现 | 原生侧故意停发 `rpc-frame-ack`（或 `pair_status_query`）并记录时间 | 测出实际被断开的秒数；与 C5（30 s 看门狗）/C13（45 s ack grace）对照。**这是设置原生心跳与重连参数的直接依据** |
| **E9** | 验证后台进程存活边界（决定是否需要前台服务） | 三种配置各跑 30 分钟后台：①无前台服务 ②有前台服务+常驻通知 ③前台服务+忽略电池优化 | 记录三种配置下原生 socket 的实际存活时长与是否收到 `DEVICE_OFFLINE`/`INTERNAL`。判据：能否覆盖"一次完整 AI 任务的时长"。（对应 B12/B13：前台服务解决进程与网络，不解决节流） |
| **E10** | 验证流体云投递链路 | 原生在后台每收到一段正文增量就更新流体云 | 锁屏状态下流体云正文是否连续、有无丢段、限流阈值多少 |
| **E11** | 验证多会话并发订阅的代价 | 同时订阅 2/4/8 个 `conversation/<sid>` | 记录内存、`logicalFrameAssemblyMaxConcurrent`（C12 的 32）是否触发 `proto.frameAssemblyBudgetExceeded` |
| **E12** | 验证 re-anchor 策略在真机网络切换下的正确性 | 后台期间做 WiFi↔蜂窝切换、飞行模式 30 s | 原生是否按 C22 判据正确选择 `base` 续传或 `forceSnapshot`；有无重复/丢失正文段 |
| **E13** | 验证 B5：**后台是否仍收得到帧**（决定"收包通道是否可用"） | 页面在后台期间持续产生 AI 输出；原生/注入脚本记录 `onmessage` 到达时刻 | 若后台仍持续收到 `rpc-frame` ⇒ 收包通道**未**被节流（B5 成立）；这条与 E1 一起把"心跳停"与"收包停"彻底分离 |
| **E14** | 验证 R8：**页面进后台后是否真的停发 `pair_status_query`**（源码已证 `stopHeartbeat()`，此处确认线上行为） | 注入脚本包装 `WebSocket.prototype.send`，记录每一条出站帧的 `type` 与时刻；切后台 3 分钟 | 后台期间若**完全没有** `pair_status_query` ⇒ `stopHeartbeat()` 生效（源码事实被线上行为确认），**原生接管必要性确认** |
| **E15** | 验证 B8：`pauseTimers()` 与 `onPause()` 的实际差异（决定"是否要把页面彻底冻结"） | 三种壳端配置：①只 `onPause()` ②加 `pauseTimers()` ③都不做 | ②下页面 JS 与网络活动应完全静默（更适合"交给原生"）；①下页面仍会收帧但不发心跳。**判据：选出让"原生持连接"最干净的那一种** |
| **E16** | 验证 B16：ColorOS 对前台服务的实际容忍度 | 本机 ColorOS 上跑 E9 的三种配置，并分别在"允许后台运行 / 允许自启动 / 电池用量不受限 / 最近任务加锁"四种开关的开/关组合下，各跑 30 分钟后台 | 找出**最小必要开关组合**；若关闭某项后 30 分钟内进程被杀 ⇒ 该项必须在引导页里强制要求用户开启 |
| **E17** | 验证 B1 的"降级写法"是否可让网页侧自保（备选方案，可降低原生改造量） | 把页面心跳从 `setInterval` 改为"收到任何入站帧后再排下一次的浅层 `setTimeout`"（嵌套 <5，依 B6），后台跑 5 分钟 | 若后台心跳能维持 ≤30 s 间隔 ⇒ 网页侧自保可行，原生方案可退化为"仅补齐长时后台"；否则维持原生全量接管 |

---

## 附录 A：本次扫描用到的只读工具（未修改任何项目文件）

为在 4.6 MB 单行压缩 bundle 上稳定取 `行:列` 与字节偏移，临时写了 7 个只读 Node 脚本
（`scratch15-scan.mjs` / `scratch15-off.mjs` / `scratch15-pos.mjs` / `scratch15-find.mjs` /
`scratch15-line.mjs` / `scratch15-modules.mjs` / `scratch15-exact.mjs`），
全部只 `fs.readFileSync` 网页快照并打印到 stdout，**不写入任何被分析的文件**。

> **列号口径**：`index-nOVzQNKW.js` 共 897 行、`src-DHgFesxz.js` 共 44 行，两者都是压缩产物、主逻辑各集中在
> 第 897 行与第 39 行。下文每条 `行:列` 均已用字节偏移 `off=N`（该文件内 1-based 字符序号）双向校验：
> 拿 `off` 在文件里取上下文即可复现。`src-DHgFesxz.js` 的列号与偏移近似线性对应，便于人工核对。

## 附录 B：证据引用速查（行:列 → 字节偏移）

| 引用 | 字节偏移 | 备注 |
|---|---|---|
| `index-nOVzQNKW.js:897:284086` | 4669106 | bridge socket 工厂 `$0t` |
| `index-nOVzQNKW.js:897:286372` | 4671386 | ack grace 超时 fault |
| `index-nOVzQNKW.js:897:286812` | 4671812 | `T9` 桥接缓冲默认值（含 45 s） |
| `index-nOVzQNKW.js:897:287172` | 4672186 | `a2t` 身份三元组校验 |
| `index-nOVzQNKW.js:897:289106` | 4676106 | `identity` 构造 |
| `index-nOVzQNKW.js:897:291866` | 4676866 | 发送 `rpc-frame-ack` |
| `index-nOVzQNKW.js:897:292812` | 4677826 | `acceptPayload` 身份门 |
| `index-nOVzQNKW.js:897:295814` | 4680814 | `d2t` 外层信封序列化 |
| `index-nOVzQNKW.js:897:310892` | 4695892 | proof 计算 `X2t` |
| `index-nOVzQNKW.js:897:311855` | 4696855 | 入站 JSON 解析 `e4t` |
| `index-nOVzQNKW.js:897:312366` | 4697366 | relay 帧类型判定 `n4t` |
| `index-nOVzQNKW.js:897:312567` | 4697581 | 帧日志投影 `r4t` |
| `index-nOVzQNKW.js:897:313266` | 4698280 | `start()` |
| `index-nOVzQNKW.js:897:313487` | 4698487 | `connect()` + `mid` |
| `index-nOVzQNKW.js:897:314026` | 4699026 | `auth_init` |
| `index-nOVzQNKW.js:897:314499` | 4699513 | close 处理器（KICKED 不自愈点 1） |
| `index-nOVzQNKW.js:897:315147` | 4700147 | `sendPayloadResult` |
| `index-nOVzQNKW.js:897:315452` | 4700466 | `suspend()`（僵尸 socket 成因） |
| `index-nOVzQNKW.js:897:315666` | 4700666 | `recoverConnection()` |
| `index-nOVzQNKW.js:897:316172` | 4701186 | `recoverInBackground()`（不自愈点 2） |
| `index-nOVzQNKW.js:897:317035` | 4702035 | `auth_response` |
| `index-nOVzQNKW.js:897:317554` | 4702554 | `pair_status='waiting'` 处理 |
| `index-nOVzQNKW.js:897:317842` | 4702842 | `pair_status='matched'` 处理 |
| `index-nOVzQNKW.js:897:318429` | 4703499 | `handleRelayError` / KICKED |
| `index-nOVzQNKW.js:897:319272` | 4704286 | `scheduleHeartbeat` |
| `index-nOVzQNKW.js:897:319446` | 4704446 | `armHeartbeatAckWatchdog`（30 s） |
| `index-nOVzQNKW.js:897:320059` | 4705059 | `sendPairStatusQuery` |
| `index-nOVzQNKW.js:897:320306` | 4705306 | waiting 超时（30 s） |
| `index-nOVzQNKW.js:897:320644` | 4705658 | 死代码 `scheduleStaleWaitingRecovery` |
| `index-nOVzQNKW.js:897:321532` | 4706546 | `reconnectNow()` |
| `index-nOVzQNKW.js:897:321596` | 4706710 | `scheduleReconnect` 退避梯子 |
| `index-nOVzQNKW.js:897:323881` | 4708895 | `enterTerminalFailure` |
| `index-nOVzQNKW.js:897:325145` | 4710145 | `hiddenDurationMs` 诊断 |
| `index-nOVzQNKW.js:897:326766` | 4711766 | `c4t` 生命周期 observer |
| `index-nOVzQNKW.js:897:340319` | 4725333 | 终态 UI 只做 reload（不自愈点 3） |
| `index-nOVzQNKW.js:897:343246` | 4728246 | `X-ZCode-Mobile-Connection-Id` |
| `index-nOVzQNKW.js:897:349646` | 4734646 | `R4t()` + `z4t()` WS#1 URL |
| `index-nOVzQNKW.js:897:350146` | 4735146 | `bootstrap` |
| `index-nOVzQNKW.js:897:350625` | 4735625 | `window-control-ready` 判定 |
| `index-nOVzQNKW.js:897:350884` | 4735884 | WS#2 构造 |
| `index-nOVzQNKW.js:897:357961` | 4742961 | `q4t()` relay 基址 |
| `index-nOVzQNKW.js:897:360486` | 4745486 | `Z4t()` 参数解析入口 |
| `index-nOVzQNKW.js:897:361906` | 4746806 | 恢复函数 `T` |
| `index-nOVzQNKW.js:897:362413` | 4747413 | transport options 实例化 |
| `index-nOVzQNKW.js:897:368366` | 4752366 | `c4t` 接线 |
| `index-nOVzQNKW.js:2:43575` | 101681 | delta op 集 `Ole` |
| `index-nOVzQNKW.js:2:43798` | 101904 | 会话 reducer `kle`（path→row kind） |
| `index-nOVzQNKW.js:2:44118` | 102224 | 会话 reducer `Ale`（op 分派） |
| `index-nOVzQNKW.js:2:46827` | 104933 | `deliveryKind` 枚举 `Hc` |
| `index-nOVzQNKW.js:2:46866` | 104972 | 逻辑帧 schema `Uc` 起点 |
| `index-nOVzQNKW.js:2:46884` | 104990 | `wireVersion:Xa(3)` 首次出现 |
| `index-nOVzQNKW.js:2:49538` | 107644 | subscribe 请求 schema `Gle` |
| `index-nOVzQNKW.js:2:49671` | 107777 | subscribe ack schema `Gc` |
| `index-nOVzQNKW.js:2:50166` | 108272 | 帧主体 `qc` |
| `index-nOVzQNKW.js:2:50356` | 108462 | conversation topic 校验 `Jc` |
| `index-nOVzQNKW.js:2:52261` | 110367 | resync 请求 schema 起点 |
| `index-nOVzQNKW.js:2:52558` | 110664 | resync 的 `forceSnapshot` 字段 |
| `index-nOVzQNKW.js:2:59039` | 117145 | `due()` topic 构造 |
| `index-nOVzQNKW.js:2:61148` | 119254 | 逻辑帧组装字段声明起点 |
| `index-nOVzQNKW.js:2:61314` | 119420 | `maxAssemblyBytes=nl(...)` |
| `index-nOVzQNKW.js:2:61498` | 119604 | `maxConcurrentAssemblies=nl(...)` |
| `index-nOVzQNKW.js:2:82610` | 140716 | 快照帧 fromSeq/logEpoch 校验 |
| `index-nOVzQNKW.js:22:28203` | 544048 | `subscribeConversationV4({...})` 调用 |
| `index-nOVzQNKW.js:22:43118` | 558963 | re-anchor 触发判据（`deltas` 无 base） |
| `index-nOVzQNKW.js:22:46470` | 562315 | `issueRecovery` |
| `src-DHgFesxz.js:39:37066` | 87330 | `Gs` 全局限制常量 |
| `src-DHgFesxz.js:39:135280` | 185808 | CRC-32 `ph` |
| `src-DHgFesxz.js:39:136492` | 186756 | `X` 物理层限制 |
| `src-DHgFesxz.js:39:136632` | 186896 | `checksum` schema `xh` |
| `src-DHgFesxz.js:39:137095` | 187359 | `rpc-frame` schema `Ch` |
| `src-DHgFesxz.js:39:137791` | 188055 | `rpc-frame-ack` schema `wh` |
| `src-DHgFesxz.js:39:139038` | 189302 | 分片构造 `Fh` |
| `src-DHgFesxz.js:39:139264` | 189528 | 最大分片二分 `Ih` |

> 复现方式：以 `src-DHgFesxz.js:39:137095` 为例，取该文件第 39 行、第 137095 列（= `off=187359`），
> 得到 `var Ch=O({zcode_type:N(\`rpc-frame\`),bridgeSessionId:yh,...`，与 §2.4 引用的代码片段一致。

## 附录 C：§2.6 平台取证的来源与限制（独立于网页 bundle）

**取证分工**：§2.6 的全部结论由**另派的独立调研子代理**完成，未与本文档第 2 章其它小节共享上下文，
因此它的"证据分级"（官方 / 源码 / 社区）与本文档的"源码已证 / 推断"分级是**两套独立体系**，§2.6 表内每条都自带等级。

**必须声明的限制**：

1. **检索通道**：本会话内置的 `web_search` / `web_fetch` 对 `developer.chrome.com`、`chromium.googlesource.com`、
   `developer.android.com`、`web.dev` 等站点**连接失败**；实际检索经由**用户提供的外部 MCP 检索通道**完成
   （子代理报告已注明）。因此 §2.6 的来源链接虽为真实命中，但**未经本会话二次核验**。
2. **`developer.android.com` 正文抽取被拒**：Android API 语义改以 **AOSP 源码 javadoc** 与 API 镜像站点为准。
3. **最重要的一条未证实项**：**B9**（Android System WebView 是否同样执行 1 次/分钟节流）**没有官方明文**，
   是"共用 Blink Scheduler + `AwContents` 喂可见性"的**强推断**，已由 §6-E1 承接为真机实验。
4. **与本文档第 2 章其它小节的关系**：B1~B16 **不参与**任何协议结论的推导；
   它们只用于（a）解释"40~70 s 失效"的机制（§1.1 修正、§5-R8），（b）指导 §6 的实验设计，
   （c）为 §3.4 的"前台服务是否够用"提供依据。**删掉 §2.6，第 2 章的协议契约与第 3 章的设计依然自洽。**


# 这里是二级子代理通过anysearch的调研报告原文
调研完成。报告已发送给父代理。以下是完整报告正文。

---

# Chromium / Android WebView 后台定时器与网络行为调研报告

> **调研方法与访问限制说明（重要）**
> 本会话中 harness 的 `web_fetch` 对绝大多数目标站点（developer.chrome.com、chromium.googlesource.com、developer.android.com、web.dev、raw.githubusercontent.com）**均连接失败**，`web_search` 只返回聚合片段。实际检索通过用户提供的 **anysearch MCP**（`https://api.anysearch.com/mcp`）以 curl 手动 JSON-RPC 调用 `search` / `batch_search` / `extract` 完成。
> 其中 **developer.android.com 正文抽取被拒**，故 Android API 语义以 **AOSP 源码 javadoc**（android.googlesource.com）与 **Microsoft Learn 的 Android API 镜像**（页面明确标注 "Java documentation for android.webkit.WebView..."）为准，并用搜索索引片段交叉验证。所有 URL 均为检索实际命中的真实链接，**未编造**。
> 未创建/修改任何项目文件；助手脚本与缓存全部位于 `%TEMP%\anysearch`，曾在项目内误建的 `.anysearch` 临时目录**已删除**，工作区保持原样。

---

## 一句话结论清单

1. **存在"每分钟一次"的重度节流**：页面隐藏 >5 分钟 **且** 定时器链式嵌套 ≥5 层 **且** 静音 ≥30 秒 **且** 未用 WebRTC，四条同时满足才降到 **1 次/分钟**；否则先退化为 **1 次/秒**。`高（官方文档）`
2. **Android System WebView 共用同一套 Blink Scheduler，同样受限**；`WebView.onPause()` 官方明确"**不暂停 JavaScript**"，`pauseTimers()` 才是全局硬暂停；`setRendererPriorityPolicy` 只管 OOM 击杀，**不影响定时器**。`高（AOSP 源码）／中（节流同样适用系推断）`
3. **节流不掐 WebSocket 数据通道，只掐你自己的心跳定时器**：Chromium 源码原话"激进节流**不影响 WebSocket 事件处理器的执行**"。`高（源码）`
4. **`freeze`/`resume` 不是"退到后台"事件**，只在浏览器真正冻结页面时派发；`document.wasDiscarded` **仅桌面 Chrome 支持，Android 至今未支持**。`高（官方文档）`
5. **前台服务不能解除 JS 定时器节流**：节流判定只看**页面可见性**，与进程优先级无关。**必须区分"进程存活/网络可用"与"JS 定时器节流"两件事。** `中（官方分项事实 + 逻辑推论，需真机确认）`
6. **Chromium 判定 hidden 的依据是 View 可见性，而非 Activity 生命周期**：`visible = mIsAttachedToWindow && mIsViewVisible && mIsWindowVisible`。`高（Chromium 源码）`
7. **官方保活基线 = 前台服务 + 通知 + 电池优化白名单 + WifiLock/Partial WakeLock**；ColorOS 另有"允许后台运行/允许自启动"开关，**会在 AOSP 规则之外杀进程**。`高（官方）／中（ColorOS 部分）`
8. **心跳建议 30–45 秒（=最短代理空闲超时的 75%）**；运营商 NAT 空闲回收实测 **<5 分钟 ~ >30 分钟**，SIGCOMM 2011 建议运营商设 30 分钟。`中`

---

## 1. 后台定时器节流到底是多少

### 1.1 三档节流（Chrome 88 官方原文）

来源：[Heavy throttling of chained JS timers beginning in Chrome 88](https://developer.chrome.com/blog/timer-throttling-in-chrome-88) `高（官方文档）`

**(a) Minimal throttling（几乎不节流）** — 满足任一：页面**可见**；页面在过去 **30 秒内发出过声音**（任何发声 API，**静音音轨不算**）。
→ 定时器不节流，唯一例外：超时 <4ms 且**链式计数 ≥5** 时夹到 4ms。

**(b) Throttling（1 次/秒）** — 不满足 (a)，且满足**任一**：
- **链式计数 < 5**
- 页面隐藏**不足 5 分钟**
- **正在使用 WebRTC**（`RTCPeerConnection` 的 `RTCDataChannel` 为 `open`，或有 `live` 的 `MediaStreamTrack`）

原文："The browser will check timers in this group once per **second**."

**(c) Intensive throttling（1 次/分钟）** — 不满足 (a)(b)，且**全部**满足：
- 页面隐藏**超过 5 分钟**
- **链式计数 ≥ 5**
- 页面**静音至少 30 秒**
- **未使用 WebRTC**

原文："the browser will check timers in this group once per **minute**."

> **"chained timers ≥5"的确切含义**（原文）："If you call `setTimeout` in the same task as a `setTimeout` callback, the second invocation is 'chained'. With `setInterval`, **each iteration is part of the chain**."
> ⇒ **`setInterval` 天然满足"chain count ≥5"**，所以**用 `setInterval` 做心跳/轮询是最容易被重度节流的写法**。

### 1.2 关键澄清：不是"5 分钟后才开始"

`1 次/秒` 那一档**在页面隐藏的瞬间就已生效**（只要链式计数 ≥5）。"5 分钟"只是进入 **1 次/分钟** 的门槛。`高`

M87 官方博客确认发布时间线：
> "Beginning in M87, we're **throttling JavaScript timer wake-ups in background tabs to once per minute**."
> [Chromium Blog M87](https://blog.chromium.org/2020/11/tab-throttling-and-more-performance.html) `高`

### 1.3 源码级确认

来源：[Blink scheduler `features.h`](https://chromium.googlesource.com/chromium/src/+/HEAD/third_party/blink/renderer/platform/scheduler/common/features.h) `高（Chromium 源码）`

```cpp
// When features::kIntensiveWakeUpThrottling is enabled, wake ups from timers
// with a high nesting level are limited to 1 per minute on a page that has been
// backgrounded for GetIntensiveWakeUpThrottlingGracePeriod().
//
// Intensive wake up throttling is enforced in addition to other throttling
// mechanisms:
// - 1 wake up per second in a background page or hidden cross-origin frame
// - 1% CPU time in a page that has been backgrounded for 10 seconds

constexpr int kIntensiveWakeUpThrottling_GracePeriodSeconds_Default = 5 * 60;    // 300 秒
constexpr int kIntensiveWakeUpThrottling_GracePeriodSecondsLoaded_Default = 60;  // 60 秒
```

### 1.4 "Quick intensive throttling"（10 秒提案）——**桌面专属，Android 不适用**

- 提案文案确有 "Throttling after **10 seconds** if the page is fully loaded"：[chromestatus #5580139453743104](https://chromestatus.com/feature/5580139453743104) `中`
- **但 blink-dev 正式 Intent 明确写了 WebView 风险为零、仅桌面发布**：
  > *WebView application risks …* "**No, this feature will only ship on desktop platforms.**"
  > [Ready for Trial: Quick intensive timer throttling of loaded background pages](https://groups.google.com/a/chromium.org/g/blink-dev/c/j095jobY9Qw) `高（官方 Intent 原文）`
- 且当前源码中该快速路径默认值是 **60 秒**，不是提案里的 10 秒。

⇒ **对 Android WebView 应按"5 分钟 → 1 次/分钟"建模。**`中`

### 1.5 企业策略官方措辞

> "…**running no more than once per minute after a page has been backgrounded for 5 minutes or more**."
> [IntensiveWakeUpThrottlingEnabled](https://chromeenterprise.google/policies/intensive-wake-up-throttling-enabled/) `高`

该策略同时说明这符合 Web 标准，因为 HTML 规范允许 UA "Optionally, wait a further implementation-defined length of time."

---

## 2. Android System WebView 是否同样适用

### 2.1 `onPause()` vs `pauseTimers()` —— 官方语义（最常被搞混）

来源：AOSP `WebView.java` javadoc（[master](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/webkit/WebView.java)、[旧版](https://android.googlesource.com/platform/frameworks/base/+/785f494/core/java/android/webkit/WebView.java)），与 [developer.android.com/reference/android/webkit/WebView](https://developer.android.com/reference/android/webkit/WebView) 一致 `高（AOSP 源码 javadoc）`

**`pauseTimers()` / `resumeTimers()`**：
> "Pauses **all layout, parsing, and JavaScript timers for all WebViews**. This is a **global** requests, not restricted to just this WebView. This can be useful if the application has been paused."

**`onPause()`** —— 最关键的一句：
> "Does a best-effort attempt to pause any processing that can be paused safely, such as animations and geolocation. **Note that this call does not pause JavaScript.** To pause JavaScript globally, use `pauseTimers()`.
> To resume WebView, call `onResume()`."

⇒ `高`：
- **`WebView.onPause()` 不停 JavaScript 定时器**，页面照常进入 Chromium 可见性节流逻辑。
- **`pauseTimers()` 是"硬暂停"**（全局、所有 WebView），比节流更彻底；如果你要"后台完全别跑 JS"，这是唯一的公开 API。

### 2.2 `setRendererPriorityPolicy` 不影响定时器

来源：[setRendererPriorityPolicy（Android API 镜像）](https://learn.microsoft.com/en-us/dotnet/api/android.webkit.webview.setrendererprioritypolicy?view=net-android-35.0)、[Manage WebView objects](https://developer.android.com/develop/ui/views/layout/webapps/managing-webview) `高`

> "The priority policy will be used to determine whether an out of process renderer should be considered to be a **target for OOM killing**."
> "The **default policy** is to set the priority to `RENDERER_PRIORITY_IMPORTANT` **regardless of visibility**…"
> "The `true` argument decreases the renderer's priority to `RENDERER_PRIORITY_WAIVED` when the WebView is **no longer visible**."

⇒ 这是**内存回收优先级**旋钮，**与 JS 定时器节流无关**；能让渲染进程不易被杀（间接利于保活），但**不会**让后台定时器恢复全速。

### 2.3 其他开关

- `setWebContentsDebuggingEnabled()`：仅调试开关，**与节流无关**。`高`
- `android:hardwareAccelerated`：影响合成/渲染路径，**与 JS 定时器无关**。`中`
- `View.GONE`/`INVISIBLE`/detach：**会**让页面 hidden，从而**触发**节流（见第 6 节）。`高`

### 2.4 WebView 是否共用同一套 Scheduler

`AwContents.isPageVisible()` 的 javadoc：
> "Returns true if the page is **visible according to DOM page visibility API**. See http://www.w3.org/TR/page-visibility/"
> [AwContents.java](https://chromium.googlesource.com/chromium/src/+/afd3c658f932029bb4953b1e2498a500799c6d7f/android_webview/java/src/org/chromium/android_webview/AwContents.java) `高`

⇒ **推断**：Android System WebView 中页面 hidden 后**同样适用 1 次/秒与 1 次/分钟节流**。`中（强推断，未找到官方明文"Android WebView 也节流"）` → **建议真机实测**。

---

## 3. WebSocket 是否被节流影响

### 3.1 节流**不会**阻断 WebSocket 收发（源码原话）

来源：[`websocket_channel_impl.cc`](https://chromium.googlesource.com/chromium/src/+/4c7ee57e51c771ffa13d7ca73fa8cfbc62089027/third_party/blink/renderer/modules/websockets/websocket_channel_impl.cc) `高（源码）`

```cpp
// When enabled, a page can be aggressively throttled even if it uses a
// WebSocket. Aggressive throttling does not affect the execution of WebSocket
// event handlers, so there is little reason to disable it on pages using a
// WebSocket.
//
// TODO(crbug.com/1121725): Cleanup this feature in June 2021, when it becomes
// enabled by default on Stable.
const base::Feature kAllowAggressiveThrottlingWithWebSocket{
    "AllowAggressiveThrottlingWithWebSocket", base::FEATURE_ENABLED_BY_DEFAULT};
```

两点：
1. **"激进节流不影响 WebSocket 事件处理器的执行"** —— 服务端推来的消息，`onmessage` **仍会被正常派发**。
2. 该 feature **默认启用**，且后续已被删除：
   > "Remove the AllowAggressiveThrottlingWithWebSocket feature. It's been **enabled by default for >1 year**."
   > [issues.chromium.org/40128284](https://issues.chromium.org/40128284)

⇒ **"用了 WebSocket 就不被重度节流"这个老豁免已不存在**。`高`

### 3.2 Chromium 工程师本人回答

来源：[chromium scheduler-dev](https://groups.google.com/a/chromium.org/g/scheduler-dev/c/BVQbTtNU8Kw) `高（Chromium 工程师原话）`

François Doray：
> "The feature throttles Javascript Timers (`setTimeout` and `setInterval`) and Web scheduling tasks (`scheduler.postTask`) **only. It does not affect Web Sockets.**"
> "timers are **only intensively throttled after some recursion depth is reached**. **A one-off timer fired from a Websocket event handler won't be intensively throttled.**"

### 3.3 浏览器会主动断开后台 WebSocket 吗？

**没有官方文档声明浏览器会主动断开。** 社区报告的"5–7 分钟断开"根因都是**心跳定时器被节流**：`中（社区）`
- [SignalR #4536](https://github.com/SignalR/SignalR/issues/4536)
- [XWiki XWIKI-23928](https://jira.xwiki.org/browse/XWIKI-23928)
- [Solace 社区：45 秒 ping 被拉长到 60 秒](https://community.solace.com/t/client-websocket-disconnects-when-browser-is-minimized-or-not-in-focus-chrome/690) ／ [issues.chromium.org/40775371](https://issues.chromium.org/40775371)

**反向证据**：Chromium 的"Background Tab Resource Load Throttling"**明确把 WebSocket 排除**：
> "**WebSockets**, WebRTC's data channel, Fetch API, XMLHttpRequest, EventSource, Video and Audio are **excluded** today to allow **keep-alive connections**."
> [chromestatus #5527160148197376](https://chromestatus.com/feature/5527160148197376) `高`

### 3.4 Android 侧对 socket 的影响

| 机制 | 影响 | 来源 |
|---|---|---|
| **Doze** | **Network access is suspended**；系统**忽略 wake lock**；标准 Alarm 延迟到维护窗口 | [Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby) `高` |
| **App Standby Buckets** | 按使用频率分桶，低桶限制后台任务与**网络访问** | [App Standby Buckets](https://developer.android.com/topic/performance/appstandby) `高` |
| **Cached Apps Freezer（A11+）** | 进程转 cached 后被**整体冻结**，socket 事实停摆；**前台服务可免于冻结** | [Cached apps freezer](https://source.android.com/docs/core/perf/cached-apps-freezer) `高` |
| **Data Saver** | 后台应用受限使用计量网络 | [Data Saver](https://source.android.com/docs/core/data/data-saver) `高` |
| **Background Execution Limits（O+）** | 限制后台服务；前台服务不受限 | [Background Execution Limits](https://developer.android.com/about/versions/oreo/background) `高` |

补充：**Cached Apps Freezer 不冻结运行前台服务的应用** —— [Pushy 文档](https://support.pushy.me/hc/en-us/articles/44515682336653-How-can-I-receive-notifications-in-the-background-on-devices-with-the-Cached-Apps-Freezer-enabled) `中`

⇒ Android 侧真正的杀手是**"进程被冻结 + 网络被挂起"**，不是浏览器定时器节流。

---

## 4. Page Lifecycle API 的语义

来源：[Page Lifecycle API](https://developer.chrome.com/docs/web-platform/page-lifecycle-api)、[WICG 规范](https://wicg.github.io/page-lifecycle/)、[web.dev bfcache](https://web.dev/articles/bfcache) `高`

### 4.1 状态表（官方原文摘录）

| 状态 | 定义 | 进入事件 | 可观测性 |
|---|---|---|---|
| **Active** | "visible and has input focus" | `focus` | 可查 |
| **Passive** | "visible and does not have input focus" | `blur` | 可查 |
| **Hidden** | "**not visible** (and has not been frozen, discarded, or terminated)" | `visibilitychange` | 可查 |
| **Frozen** | 挂起任务队列中的 freezable 任务 | `freeze` | **仅事件回调内可感知** |
| **Discarded** | "**No tasks, event callbacks, or JavaScript of any kind can run**" | **不派发任何事件** | 靠 `document.wasDiscarded` |
| **Terminated** | "started being unloaded and cleared from memory" | `pagehide` | 仅事件内 |

Frozen 官方描述：
> "…**This means things like JavaScript timers and fetch callbacks don't run.**"

### 4.2 各事件触发时机

- **`visibilitychange`**：`visible` ↔ `hidden` 切换。**这是"退到后台"最可靠、最先到达的信号。**
- **`pagehide`**：离开会话历史条目时触发。`persisted === true` ⇒ 进入 **frozen**（将被 bfcache 缓存）；`false` ⇒ 进入 **terminated**。
- **`freeze`**：页面**刚被冻结**时触发。
- **`resume`**：恢复 frozen 页面时触发，**在 `pageshow` 之前**：
  > "the `resume` event fires when pages are restored from bfcache (**immediately before the `pageshow` event**)"
- **`pageshow`**：页面显示时触发；正常加载一次，**从 bfcache 恢复时再次触发且 `persisted === true`**。

### 4.3 `freeze` 在 Chromium 上真的会派发到普通页面吗？

官方措辞：
> "In Chrome 68, developers can now observe **when a hidden tab is frozen and unfrozen** by listening for the `freeze` and `resume` events on `document`."
> "…browsers today already **occasionally freeze and discard hidden tabs (at their discretion)**…"

⇒ `高`：
- `freeze` **不是**"页面进入后台"的可靠信号，**浏览器完全自主决定**是否冻结；
- 真实来源两种：**① bfcache 进出**（`pagehide(persisted=true)` → `freeze`）；**② 浏览器在内存/省电压力下主动冻结隐藏标签**（时机不保证）；
- **不能拿 `freeze` 感知"App 退到后台"**，那是 `visibilitychange` 的职责；
- 官方唯一主动测试手段：`chrome://discards`（"to actually freeze or discard any of your open tabs"）。

> ⚠️ **Android WebView 场景下，进程级冻结由 Android 的 Cached Apps Freezer（SIGSTOP）执行，它不派发任何 DOM `freeze` 事件。**`中（分层推断）`

### 4.4 `document.wasDiscarded`

> "From Chrome 68 the `document` object now includes a **`wasDiscarded`** property on **desktop Chrome** (Android support is being tracked in [this issue](https://issues.chromium.org/issues/40268039))."
> "…you can inspect the value of this property **at page load time** (note: **discarded pages must be reloaded to use again**)."

规范侧：[WICG — "The `wasDiscarded` attribute's getter must return the value of this Document's discarded boolean."](https://wicg.github.io/page-lifecycle/)

⇒ 只读、**加载时读取**的标志；**Android 至今未支持（issue 40268039 仍为跟踪状态）——WebView 里不要依赖它**。`高`

---

## 5. 前台服务能否让 WebView 的 JS 不被节流？【关键】

**答案：不能。必须把两件事拆开。**

| 维度 | 决定因素 | 前台服务的作用 |
|---|---|---|
| **A. 进程存活 + 网络可用** | Android 进程优先级 / cached 状态 / Doze / Standby Bucket | ✅ **有效**：不被 Cached Apps Freezer 冻结、不被后台限制杀掉、网络基本可用 |
| **B. JS 定时器节流** | **页面可见性 `document.visibilityState`**（Blink 内部） | ❌ **无效**：前台服务不改变 WebView 的 View 可见性 |

**依据链**：
1. 节流的**全部触发条件都写成 "page hidden / silent"**，无一涉及进程优先级 — [Chrome 88](https://developer.chrome.com/blog/timer-throttling-in-chrome-88) `高`
2. 源码注释只以 "a page that has been **backgrounded**" 为条件 — [features.h](https://chromium.googlesource.com/chromium/src/+/HEAD/third_party/blink/renderer/platform/scheduler/common/features.h) `高`
3. WebView 可见性来自 **View/Window 可见性**，与 Service 无关 — [AwContents.java](https://chromium.googlesource.com/chromium/src/+/refs/heads/master/android_webview/java/src/org/chromium/android_webview/AwContents.java) `高`
4. Activity 退后台 ⇒ Window 不可见 ⇒ `mIsWindowVisible = false` ⇒ `Visibility.HIDDEN`。**前台服务不会让这个 Window 变可见。**

⇒ `中（由官方分项事实推出的必然结论；未找到"前台服务不解除节流"的官方直述）`
> **持有前台服务后，WebView 的 `setInterval` 依旧会被压到 1 次/秒，5 分钟后压到 1 次/分钟。**
> 前台服务换来的是：**进程不被冻结、socket 不被系统掐断、`onmessage` 仍能派发**。

**这正好解释了第 3 节的矛盾**：官方说节流 "does not affect Web Sockets"（`onmessage` 正常），社区说后台 5–7 分钟就断（[SignalR #4536](https://github.com/SignalR/SignalR/issues/4536)）——**两者都对**：断的不是接收通道，而是**用 `setInterval` 发的心跳**被压到 60 秒一次，撞上服务端 60 秒空闲超时。`高`

---

## 6. WebView 隐藏但不销毁时的 `visibilityState`

### 6.1 判定依据：**View 可见性**，不是 Activity 生命周期

来源：[AwContents.java](https://chromium.googlesource.com/chromium/src/+/refs/heads/master/android_webview/java/src/org/chromium/android_webview/AwContents.java) `高（Chromium 源码）`

```java
private boolean mIsPaused;
private boolean mIsViewVisible;
private boolean mIsWindowVisible;
private boolean mIsAttachedToWindow;
// Visiblity state of |mWebContents|.
private boolean mIsContentVisible;
...
boolean visible = mIsAttachedToWindow && mIsViewVisible && mIsWindowVisible;
...
updateWebContentsVisibility(Visibility.HIDDEN);
// Geolocation is paused/resumed via the page visibility mechanism.
updateWebContentsVisibility();
```

**Activity 的 `onPause/onStop` 本身不是输入**，只通过影响 window 可见性**间接**起作用。

### 6.2 各场景预期

| 场景 | `mIsViewVisible` | `mIsWindowVisible` | `mIsAttachedToWindow` | `visibilityState` 预期 |
|---|---|---|---|---|
| 正常显示 | ✅ | ✅ | ✅ | `visible` |
| `setVisibility(View.GONE / INVISIBLE)` | ❌ | ✅ | ✅ | **`hidden`** |
| Activity 退到后台（View 未改动） | ✅ | **❌** | ✅ | **`hidden`** |
| `onPause` 但仍部分可见（多窗口/对话框覆盖） | ✅ | ✅ | ✅ | 通常仍 `visible` |
| 被 `removeView` / detach | ✅ | ✅ | **❌** | **`hidden`** |
| 调用 `WebView.onPause()` | — | — | — | 走 `setIsPaused` → `Visibility.HIDDEN` ⇒ **`hidden`** |
| 仅 `pauseTimers()` | 不变 | 不变 | 不变 | 可见性**不变**，JS 定时器**全局硬暂停** |

`高（前三行源码直推）／中（其余为推断，建议实测）`

⇒ 实操结论 `高`：
- **想省电**：`View.GONE/INVISIBLE` 或随 Activity 进后台**就够了**；
- **想彻底停 JS**：`pauseTimers()`（注意**全局**影响 App 内所有 WebView）；
- **想在后台仍然全速跑 JS**：`setVisibility(VISIBLE)` 不够（Activity 不可见时 `mIsWindowVisible` 即 false）。**唯一非常规做法是把 WebView 挂到始终可见的 window 上**（社区用 `WindowManager` 加 0 尺寸/1px 悬浮窗）：
  [SO: keep webview running in foreground service](https://stackoverflow.com/questions/58569423/how-to-keep-webview-running-in-foreground-service-after-pressing-home-button-in) `低（单一社区来源，非常规、有兼容性与上架风险）`

---

## 7. 原生后台保活最佳实践（官方口径）

### 7.1 官方基线

| 手段 | 官方要点 | 来源 |
|---|---|---|
| **前台服务 + 常驻通知** | "you **must display a notification** so that users are actively aware that the service is running" | [Services overview](https://developer.android.com/develop/background-work/services) `高` |
| **`foregroundServiceType`（A14+）** | 目标 API 34+ 必须声明具体类型并申请对应权限 | [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types) `高` |
| **Android 12+ 后台启动 FGS 受限** | 后台应用**不能**启动前台服务，仅明确豁免例外 | [FGS background start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) `高` |
| **Android 15+ `dataSync` 配额** | "run for **6 hours in the last 24**, you cannot start another `dataSync` foreground service unless…" | [Behavior changes: Android 15+](https://developer.android.com/about/versions/15/behavior-changes-15) `高` |
| **电池优化白名单** | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | [Manifest.permission](https://developer.android.com/reference/android/Manifest.permission)、[Doze 文档](https://developer.android.com/training/monitoring-device-state/doze-standby) `高` |
| **`PARTIAL_WAKE_LOCK`** | 持有时 CPU 持续运行，"even after the user presses the power button" | [Set a wake lock](https://developer.android.com/develop/background-work/background-tasks/awake/wakelock/set) `高` |
| **`WifiManager.WifiLock`** | "Allows an application to **keep the Wi-Fi radio awake**" | [WifiLock（API 镜像）](https://learn.microsoft.com/en-us/dotnet/api/android.net.wifi.wifimanager.wifilock?view=net-android-35.0) `高` |
| **后台限制自助配置** | 系统电池用量页可"允许所有后台工作"，同时警告 "Can't launch foreground services" 等边界 | [Background optimization](https://developer.android.com/topic/performance/background-optimization) `高` |
| **避免滥用 wake lock** | Play vitals 会标记过度/卡住的 partial wake lock | [Excessive](https://developer.android.com/topic/performance/vitals/excessive-wakelock)、[Stuck](https://developer.android.com/topic/performance/vitals/stuck-wakelock) `高` |

> ⚠️ `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 受 **Google Play 政策**限制，滥用会被下架（社区有实例）。`中`

### 7.2 ColorOS / OPPO

**OPPO 官方支持页（用户侧开关）** `高（OPPO 官方）`：
> "Go to **[Settings] > [Battery] > [App Battery Management] > [Select App]** > Enable/Disable **[Allow foreground/Background activity]**. You can also disable **[Allow auto startup]**…"
> [How to Manage App Battery Usage on OPPO Phones](https://support.oppo.com/ae/answer/?aid=2020322)
> 另见 [OPPO 社区：How to Control App Permissions and Background Activity](https://community.oppo.com/thread/1903831448289280008) `高`

**OPPO 官方社区对"后台被杀"的解释** `中`：
> "It's because **ColorOS has aggressive battery optimizations** to save power — but sometimes, it goes too far."
> [Tired of OPPO Killing Your Background Apps?](https://community.oppo.com/thread/1917800086667001861)

**第三方汇总** `中（多来源一致）`：
> "**ColorOS kills background processes aggressively.** Steps: **Settings > Battery** → **App battery management** → **Allow background activity**；再检查 **Settings > App management** > 应用 > **Battery usage** → **Unlimited**。"
> [Keep Background Services Alive on Android (by Brand)](https://help.biolovision.net/Keep_Background_Services_Alive_on_Android_(by_Brand))

**Don't kill my app!（Oppo）** `中（知名社区项目，信息偏 ColorOS 5/6 时代）`：
> "**All four of those need to be done** before the app would function: Pin your app to the recent apps screen / 在安全中心"startup manager"启用 / 关闭电池优化 / **给服务常驻通知**。"
> [dontkillmyapp.com/oppo](https://dontkillmyapp.com/oppo)

**关于 ColorOS 对前台服务的具体策略**：
- **未找到 OPPO/ColorOS 官方的开发者文档**说明其何时会杀掉前台服务；OPPO 官方只提供面向**用户**的设置指引。
- 社区一致经验：**ColorOS 可能在 AOSP 规则之外额外限制/杀掉前台服务**，必须依赖用户手动开启"允许后台运行 / 允许自启动 / 锁定后台"。`中`
- 参考：[SO: Background Services are restricted in Xiaomi and Oppo](https://stackoverflow.com/questions/59442078/background-services-are-restricted-in-xiaomi-and-oppo)、[XDA: aggressive app killing](https://xdaforums.com/t/any-easy-solution-for-the-aggressive-app-killing.4384549/)

⇒ 建议 `中`：前台服务 + 常驻通知 → 引导加入电池白名单 → 检测 `Build.MANUFACTURER` 为 OPPO/realme 时给**ColorOS 专属引导页**（App battery management → Allow background activity；Battery usage → Unlimited；Allow auto startup；最近任务加锁）→ **不要假设前台服务一定不被杀**，必须实现断线重连与前台恢复对齐。

---

## 8. WebSocket 心跳在移动网络下的容忍度

### 8.1 协议层事实

- **RFC 6455** 定义 Ping（`0x9`）/ Pong（`0xA`）："A Pong frame sent in response to a Ping frame must have identical 'Application data'…" — [RFC 6455](https://www.rfc-editor.org/info/rfc6455/)、[MDN](https://developer.mozilla.org/en-US/docs/Web/API/WebSockets_API/Writing_WebSocket_servers) `高`
- **浏览器 JS 拿不到 ping/pong**（不能发、不能感知、不知道何时回 pong；浏览器会自动回，但对代码不可见）— [websocket.org](https://websocket.org/guides/heartbeat/) `高`
  ⇒ **浏览器端只能做应用层心跳**。

### 8.2 业界推荐值与依据

来源：[WebSocket Heartbeat: Ping/Pong, Keep-Alive & Zombie Detection](https://websocket.org/guides/heartbeat/) `中（权威技术站点，非标准组织）`

- **推荐区间：每 30–45 秒一次。**
  > "**Every 30 to 45 seconds** works for most deployments. Apply the **75% rule**… Nginx and AWS ALB default to 60 seconds, so **45 seconds is safe**. If Google Cloud Load Balancing (30-second default) is in your path, **drop to 22 seconds**."
- **75% 规则**：心跳间隔 = 最短代理空闲超时 × 0.75。

| 代理 / 服务 | 默认空闲超时 |
|---|---|
| Nginx | 60 s |
| AWS ALB | 60 s |
| Cloudflare | 100 s |
| HAProxy | 50–60 s |
| Azure App Gateway | 60 s |
| Google Cloud LB | 30 s |

- **TCP keepalive 不可依赖**："default `TCP_KEEPIDLE` is **7200 seconds – two hours**… Any reverse proxy will kill your idle connection in 60–100 seconds."
- **移动端警告（原文，直接命中本场景）**：
  > "**Mobile apps need extra care.** iOS and Android aggressively suspend background apps and kill their network connections. **Your heartbeat timer stops when the app is backgrounded.**… **Send an immediate heartbeat on app foreground**… and **treat a missed pong as a signal to reconnect immediately**."

其他佐证：IBM Cloud Ingress 60 秒超时 → "Set the heartbeat interval to **58 seconds or less**"（[IBM](https://cloud.ibm.com/docs/containers?topic=containers-cs_ingress_websocket) `中`）；`websockets` 库默认 20s ping / 20s 超时（[文档](https://websockets.readthedocs.io/en/stable/topics/keepalive.html) `中`）；Meteor DDP ~30s（[论坛](https://forums.meteor.com/t/ddp-websocket-data-consumption-for-ping-pong/37465) `低`）。

### 8.3 运营商 NAT / 移动网络回收时间窗

`高（同行评审论文）`：
- **SIGCOMM 2011, Wang et al.（测量 73 家运营商）**：
  > "For carriers, we recommend a **30-minute timeout** value."
  > [SIGCOMM 2011 p374.pdf](https://conferences.sigcomm.org/sigcomm/2011/papers/sigcomm/p374.pdf)
  > 二手汇总："**11 of 73 carriers set TCP timeout to less than 10 minutes**" — [Purdue DCSL](https://engineering.purdue.edu/dcsl/reading/2012/matt_cellular_networks.pdf)
- **CoNEXT 2013, Qian et al.**：
  > "…removed by timeout at the NAT, ranging from **less than 5 mins to more than 30 mins** in today's cellular networks."
  > [CoNEXT 2013 p211.pdf](https://conferences.sigcomm.org/co-next/2013/program/p211.pdf)

⇨ `高`：
- **NAT 空闲 TCP 回收普遍 5 分钟 ~ 30 分钟以上**（少数短于 5 分钟）；
- **NAT 回收是静默的**（不发 RST）——这是"僵尸连接"的根源；
- 实际中**代理/负载均衡的 60 秒超时通常先到**，因此心跳间隔应由"路径上最短超时"决定。

### 8.4 与第 1、3 节联动的关键结论（本项目最该注意）

> **Android WebView 后台（hidden）时，`setInterval` / 深递归 `setTimeout` 心跳会被压到 1 次/分钟。**
> ⇒ 服务端空闲超时 60 秒时，**心跳必然来不及**，连接必先被服务端杀掉。
> **正确做法（三选一或组合）**：
> 1. **服务端把 WebSocket 空闲超时放宽到 ≥120 秒**；
> 2. **心跳改成"事件驱动的浅层 `setTimeout`"**（收到消息/pong 后再排下一次，嵌套 <5 层）——依据 Chromium 工程师原话，这类**一次性定时器不会被重度节流**；
> 3. **把连接下沉到原生层**（Kotlin/Java + OkHttp，放在前台服务里），彻底绕开 Blink 定时器节流 —— **本场景最稳的工程方案**。
> `高（由官方事实直接推出）`

---

## 仍然不确定 / 需要真机实测的点

1. **Android System WebView 是否真的执行 1 次/分钟节流** —— 只有"共用 Blink Scheduler + AwContents 喂可见性"的强推断，**无官方明文**。→ 实测：WebView 内 `setInterval(1000)` 打点，退后台 10 分钟后取日志，看间隔是 ~1000ms 还是 ~60000ms。
2. **`AllowAggressiveThrottlingWithWebSocket` 的 "aggressive throttling" 究竟指哪一档** —— 源码未逐字点名 `IntensiveWakeUpThrottling`，我是按 M88 同期 + TODO 时间点推断。→ 同上打点实验，页面带 WebSocket。
3. **前台服务能否间接影响节流** —— 逻辑上不能，但厂商 ROM 是否因此改变 window 可见性无法从文档确定。→ 实测三种配置：无前台服务 / 有前台服务 / 前台服务 + 悬浮窗承载 WebView。
4. **`View.GONE/INVISIBLE` 与 Activity 后台是否产生完全一致的 `visibilitychange` 时序**（是否连发两次、恢复时是否只发一次）。→ 实测。
5. **`freeze` 在 Android WebView 中是否永不触发** —— 属分层推断。→ 注册 `freeze` 监听，锁屏/切后台 30 分钟观察。
6. **ColorOS 具体何时杀前台服务** —— **未找到 OPPO 官方开发者文档**；现有一切均来自 OPPO 用户支持页 + 社区，且 ColorOS 13/14/15 行为差异大。→ 逐机型实测。
7. **Cached Apps Freezer 的冻结延迟** —— 社区报告 30–40 秒（[GrapheneOS](https://discuss.grapheneos.org/d/11574-cached-apps-freezer-breaking-foreground-services-pixel-7pro-qpr2)、[vision-camera #3005](https://github.com/margelo/react-native-vision-camera/issues/3005)），AOSP 无统一阈值，随内存压力与厂商配置变化。`低`
8. **Dedicated Web Worker 是否不受节流** —— `features.h` 中存在 `BASE_FEATURE(kDedicatedWorkerThrottling, "BlinkSchedulerWorkerThrottling", base::FEATURE_DISABLED_BY_DEFAULT)`，**可能**暗示 Worker 节流默认关闭，但语义不明。`低（推断）` → **值得实测**：若成立，这是**改动最小的 Web 侧方案**。
9. **Quick intensive throttling（10 秒）是否真的完全没上 Android** —— blink-dev Intent 明说 desktop only（2022 年），后续是否变化无法确认。`中` → 实测。
10. **各厂商 ROM 对 WebView 渲染进程的额外处理** —— **未找到权威来源**。

---

## 给工程的 3 条最直接建议

1. **不要再依赖 WebView 里的 `setInterval` 心跳保活** —— 后台必然被压到 1 次/秒甚至 1 次/分钟，这是官方既定行为，**加前台服务也无法解除**（节流只看页面可见性）。
2. **要真保活，把长连接下沉到原生层**（Kotlin/Java + OkHttp/自研 socket）+ **前台服务 + 常驻通知**，WebView 只做 UI。这样同时避开 Blink 定时器节流、Cached Apps Freezer 与 Doze 网络挂起三个坑。
3. **若必须留在 WebView 内**：改用事件驱动的浅层 `setTimeout`（嵌套 <5 层）、服务端空闲超时放宽到 ≥120 秒、`visibilitychange` 回到 `visible` 时立即补一次心跳 + 未收到 pong 立即重连；并优先实测 **Web Worker** 是否为可行逃生舱（本次未查到权威结论）。