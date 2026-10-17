# 03 · 入站分发器与 handler 全景（桌面端收到我们的帧之后做了什么）

> **审计基线**：ZCode 桌面端 3.15.3（app.asar 340,516,766 B，build `d7ca8bee`，2026-10-13）
> 主要坐标：`out/main/index.js`、`out/main/chunk-OW6EUS2G.js`、`out/main/chunk-JS2T33SG.js`
> 本地只读副本：`E:\Zcode_android\docs\_audit\desktop-extract\`（`main-index.js` = `out/main/index.js`，
> `chunk-OW6EUS2G.js`、`chunk-JS2T33SG.js` 同）
> 本文所有偏移 = **该文件内 UTF-8 字节偏移**。三个被引文件在我引用到的区间内**全部是纯 ASCII**
> （`main-index.js` 首个非 ASCII 在 642,836；`chunk-OW6EUS2G.js` 在 758,385；`chunk-JS2T33SG.js` 无），
> 因此这些偏移同时等于字符偏移，与 `tools/asar.mjs grep -b` 一致。
> **分工**：逐帧字段表见 B02（`docs/remote-v4/49-*` 同源）；`rpc-frame` 分片/重组/ack 状态机见
> `07-rpc-frame-分片与上限.md`；`mobile-view-state-update` / `mobile-diagnostic` 深挖见
> `10-移动端帧的接收与校验.md`。**本文只写行为**：做什么、何时做、失败如何、副作用是什么。

---

## 0. 结论速览

| # | 结论 | 关键坐标 |
|---|---|---|
| 1 | 入站链共 **7 跳**：`socket.on("message")` → `t0`(JSON) → `handleMessage` → `case"data"` → `handleDataPayload` → `vf`(safeParse) → `routePayload` | `main-index.js` 380,732 / 376,664 / 381,506 / 382,918 / 384,787 / 405,108 |
| 2 | **`handleDataPayload` 有两道前置闸**：`state!=="paired"` 或 `isRawTransportCandidate(t) && onRawTransportPayload(t)` 时**整帧 return，连日志都没有** | `main-index.js` 384,787 |
| 3 | 校验失败的确切后果 = **一条 `logger.warn` + `return`**；无回执、无计数、无遥测、无状态变更、无降级。**移动端侧表现为请求永不返回（只能靠自己的超时）** | `main-index.js` 384,954 |
| 4 | `routePayload` 共 **10 个 `case` 标签 / 9 个不同处理体 + 1 个 `default:break`**。`default` 分支**静默忽略**，无日志 | `main-index.js` 405,108–405,790 |
| 5 | 桌面端**主动发**的 `zcode_type` 共 **11 种**（9 种在 `main-index.js` 字面量 + `rpc-frame`/`rpc-frame-ack` 在 `chunk-JS2T33SG.js` 构造） | `main-index.js` 396,459…405,296；`chunk-JS2T33SG.js` 12,340 / 13,499 |
| 6 | 出站被 **3 道闸**过滤：`pendingOutboundPayloads` 非空则先缓冲、`>1 MiB` 丢弃、`state!=="paired"` 或 `staleWaitingCount>0` 判为 `unavailable` 继续缓冲；缓冲队列 **≥50 条全清**、**5 s 超时全清** | `main-index.js` 396,082 / 395,354 / 394,766 / 379,472 |
| 7 | `rpc-frame` / `rpc-frame-ack` 走**独立通路**（桥的 `relayProtocol.acceptPayload`），要求 **identity 三元组严格 `===`**（`bridgeSessionId` + `bridgeGeneration` + `recoveryId`，`undefined` 也算一个值）；不匹配 = `return false` = **静默丢弃，无回执无日志无降级** | `chunk-JS2T33SG.js` 13,521 / 7,688 |
| 8 | **发现的真实缺陷**：`platformHandlers` 有 8 个方法（多出 `createTempTextAttachment`），但 schema 枚举 `Kf` 只有 7 个 → 移动端调 `createTempTextAttachment` 时**整帧被 `vf` 丢弃**，桌面端永远不返回 `platform-response` | `main-index.js` 758,375（handlers）；`chunk-OW6EUS2G.js` 724,884（`Kf`） |
| 9 | `mobile-view-state-update` 的 handler `ho` **零副作用**：只写 `h.mobileViewState` / `h.mobileDeviceInfo` 两个内存字段，不落盘、不通知 renderer、不触发 `workspace-list-updated` | `main-index.js` 404,331 |
| 10 | `telemetry-report` 的处置是 `e.reportRendererTelemetryEvent?.(C.event)`（可选链，调用点在 **405,654**），最终 `go.reportEvent(e).catch(()=>{})` —— **fire-and-forget，失败静默** | `main-index.js` 405,629 / 759,075 |

---

## 1. 入口链（逐跳，含校验失败的确切后果）

### 1.1 跳 1：WS 消息入口（尺寸闸 + JSON 闸）

`out/main/index.js` **380,732**（relay 客户端类的 `socket.on("message")`）：

```js
r.on("message",n=>{if(r!==this.socket)return;
 if(ma(n)>kr.maxPhysicalFrameBytes){this.options.logger.warn("[web-remote-control] oversize external relay message dropped",{bytes:ma(n),maxBytes:kr.maxPhysicalFrameBytes}),this.options.onRawTransportFault?.("remot…
```

原文（续）：`…onRawTransportFault?.("remote.rpcFrame.envelopeTooLarge");return}let o=t0(n);if(!o){this.options.logger.warn("[web-remote-control] invalid external relay message");return}this.logRelayTrace("recv",{...fS(o),bytes:ma(n),contentHash:n0(n)}),this.handleMessage(o)})`

- `ma` = `estimateRawDataBytes`（**377,505**）
- `kr` = 导入名，来源 `import{… Z as kr …}from"./chunk-OW6EUS2G.js"`；在 chunk 内是 **`var Ue`（545,748）**：
  `var Ue={maxPhysicalFrameBytes:Re.maxFrameBytes,maxMessageBytes:16*1024*1024,maxFragments:64,assemblyTimeoutMs:3e4,transportIdMaxChars:Re.transportEnvelopeIdMaxChars}`
  → `Re.maxFrameBytes:1024*1024`（**534,064**）⇒ **单条 WS 消息硬上限 1 MiB**
- `t0` = `parseRelayMessage`（**376,664**）：`JSON.parse` + 要求 `typeof r=="object"&&"type"in r`，否则 `null`
- `fS` = `summarizeRelayMessageForTrace`（**377,751**），`n0` = `createMessageHashFromRaw`（**378,410**）
- `traceRelayMessages` 默认 `!1`（**379,037**）⇒ 追踪默认关闭

**超限的副作用**：`onRawTransportFault("remote.rpcFrame.envelopeTooLarge")` → 在 `start()` 里接成
`onRawTransportFault:s(Pe=>{ge.currentBridge?.relayProtocol?.markDegraded(Pe)})`（**408,344**）⇒ **有桥时整座桥降级**（`chunk-JS2T33SG.js` 10,850 `markDegraded`），并发 `bridge-degraded`（见 §4）。

### 1.2 跳 2：`handleMessage`（relay 顶层 `type` 分派）

`out/main/index.js` **381,506**：

```js
async handleMessage(t){switch(t.type){
 case"device_register_ack":{…}
 case"auth_challenge":{…}
 case"auth_ack":case"pair_status_ack":{this.applyPairStatus(t.pair_status);break}
 case"data":{this.handleDataPayload(t.payload);break}
 case"error":{await this.handleRelayError(t.code,t.message);break}}}
```

- `case"data"` 在 **382,918**；传入的是 **`t.payload`**（relay 信封解包一层）
- **无 `default`** ⇒ 未知 relay `type` 静默忽略
- `applyPairStatus`（**383,203**）里 `"matched"` → `setState("paired")`、`wasPaired=!0`、`startHeartbeat()`、`options.onSendReady?.({kind:"same-socket"|"reconnected-socket"})`

### 1.3 跳 3：`handleDataPayload` —— 两道前置闸 + 校验闸

`out/main/index.js` **384,787**（原文逐字）：

```js
handleDataPayload(t){if(this.state!=="paired"||r0(t)&&this.options.onRawTransportPayload?.(t))return;
let r=vf(t);if(!r){this.options.logger.warn("[web-remote-control] invalid external relay payload dropped",{...hS(t)});return}this.options.onPayload(r)}
```

**三道闸的语义（按求值顺序）：**

| 闸 | 条件 | 后果 |
|---|---|---|
| G1 | `this.state!=="paired"` | **整帧丢弃，无日志无回执**。`this.state` 取值集合：`idle` / `connecting` / `registering` / `authenticating` / `waiting_terminal` / `paired` / `error`（`setState` 调用点 379,963 / 380,162 / 380,800 / 383,075 / 383,494 / 383,750 / 387,621 / 387,773 / 388,006）。⇒ **`waiting_terminal` 期间的业务帧也一律丢** |
| G2 | `r0(t) && onRawTransportPayload?.(t)` 为真 | 帧交给桥的 rpc 通路后 `return`，**完全绕过 `vf` 校验**（`r0` = `isRawTransportCandidate`，**377,342**：`t==="rpc-frame"\|\|t==="rpc-frame-ack"`）。回调实参见 §1.5 |
| G3 | `vf(t)` 返回 `null` | `logger.warn("[web-remote-control] invalid external relay payload dropped",{...hS(t)})` 然后 `return`（**384,954**） |

**G3 的确切后果（这是"我们发的帧会被怎么处理"的核心答案）：**
- **只发一条 warn**。`hS` = `safePayloadMetadata`（**377,035**）只摘三个字段用于日志：
  `{zcode_type:typeof t.zcode_type=="string"?t.zcode_type:void 0, requestId:…, bridgeSessionId:…}`
- **没有**错误回执、**没有**计数器、**没有**遥测上报、**没有**状态机迁移、**没有**桥降级。
- 因此对 `bootstrap-request` / `workspace-list-request` / `platform-request` / `workspace-bridge-open` /
  `workspace-reconnect-request` 这类**请求-响应帧**：帧被丢 = **移动端的 `await` 永不返回**，只能等它自己的超时。
- 对 `mobile-view-state-update`：帧被丢 = `case "mobile-view-state-update"` 永不进入（见 §2 与 doc 10 §2）。

`vf` 的来源与语义（复核结论）：`main-index.js` 顶部 `import{…,Lb as vf,…}from"./chunk-OW6EUS2G.js"`（**744** 处是 `Lb as vf`）；
chunk 内 `qee as Lb`（**798,585**），定义在 **728,128**：

```js
function qee(t){let n=UD.safeParse(t);return n.success?n.data:null}c(qee,"parseWebRemoteControlAppPayload");
```

⇒ `vf` = **`parseWebRemoteControlAppPayload`**，`UD=e.union(DD)`（**728,113**），`DD` 是 21 臂**普通 union**（起始 **725,057**）。
**返回值是 `n.data`（zod 解析结果）**，不是原始对象 ⇒ 20 个非 `.strict()` 臂的**未知键会被 zod 默认 strip 掉**，
后续 handler 看到的是**剥过键的对象**；只有 `telemetry-report` 臂带 `.strict()`（臂起始 **725,057**，字面量在 725,084）。

### 1.4 跳 4：`onPayload` → `routePayload`

`onPayload` 在 relay 客户端构造参数里接成 `onPayload:s(Pe=>IT(ge,Pe),"onPayload")`（**408,266**），
`ge` = 该窗口的 runtime 对象。⇒ 最终调用 **`routePayload(runtime, payload)`**。

`out/main/index.js` **405,108**（原文逐字，含全部 case）：

```js
function IT(h,C){switch(C.zcode_type){
case"bootstrap-request":le(h,{zcode_type:"bootstrap-response",requestId:C.requestId,success:!0,result:T(h)});break;
case"workspace-list-request":le(h,{zcode_type:"workspace-list-response",requestId:C.requestId,success:!0,result:W(h)});break;
case"platform-request":K(h,C);break;
case"mobile-view-state-update":ho(h,C.viewState,C.deviceInfo);break;
case"workspace-bridge-open":Q(h,C);break;
case"workspace-reconnect-request":wt(h,C);break;
case"rpc-frame":case"rpc-frame-ack":wo(h,C);break;
case"telemetry-report":e.reportRendererTelemetryEvent?.(C.event);break;
case"mobile-diagnostic":Nc(h,C);break;
default:break}}s(IT,"routePayload");
```

**case 偏移表**（同一函数内，便于逐条复核）：

| `case` 标签 | 偏移 | 处理体 |
|---|---|---|
| `bootstrap-request` | 405,146 | 内联 → `le`（`sendAppPayload`） |
| `workspace-list-request` | 405,261 | 内联 → `le` |
| `platform-request` | 405,386 | `K`（`respondToPlatformRequest`） |
| `mobile-view-state-update` | 405,422 | `ho`（`applyMobileViewStateUpdate`） |
| `workspace-bridge-open` | 405,490 | `Q`（`respondToWorkspaceBridgeOpen`） |
| `workspace-reconnect-request` | 405,531 | `wt`（`respondToWorkspaceReconnectRequest`） |
| `rpc-frame` | 405,579 | `wo`（`routeRpcTransportPayload`） |
| `rpc-frame-ack` | 405,595 | `wo`（同上，共用） |
| `telemetry-report` | 405,629 | 内联一行 |
| `mobile-diagnostic` | 405,700 | `Nc`（`logMobileDiagnostic`） |
| `default:break` | **405,738** | 静默忽略 |

**计数**：`case` 标签 **10** 个；不同处理体 **9** 个（`le` 被 2 个臂共用、`wo` 被 2 个臂共用）；
`default` **1** 个。`s(IT,"routePayload")` 在 **405,753**。

### 1.5 G2 的实参：raw transport 通路

```js
onRawTransportPayload:s(Pe=>Ut(ge,Pe),"onRawTransportPayload")   // main-index.js 408,305
function Ut(h,C){let A=h.currentBridge;return!A||A.degraded?!1:A.relayProtocol?.acceptPayload(C)??!1}
                                                     // main-index.js 404,489 routeRawTransportCandidate
function wo(h,C){Ut(h,C)}                            // main-index.js 404,431 routeRpcTransportPayload
```

`acceptPayload` 实现在 `chunk-JS2T33SG.js` **13,521**（原文逐字）：

```js
acceptPayload(e){return this.disposed||this.degraded||!Pe(e)||!ke(this.identity,e)?!1:
  this.deadline.check()?!0:this.outboundCallDepth>0?(this.queuedInbound.push(e),!0):(this.processInbound(e),!0)}
```

- `Pe` = `isRawTransportCandidate`（同文件 **7,896**）
- `ke` = `rawIdentityMatches`（同文件 **7,688**）：
  `return r.bridgeSessionId===n.bridgeSessionId&&r.bridgeGeneration===n.bridgeGeneration&&r.recoveryId===n.recoveryId`
  ⇒ **identity 三元组严格 `===`**。桌面端 identity 由 `workspace-bridge-open` 请求派生
  （`main-index.js` 内 `Mg({bridgeSessionId:q.bridgeSessionId,bridgeGeneration:q.bridgeGeneration,recoveryId:q.recoveryId,…})` 在 **401,557**，
  构造函数按 `===void 0` 决定是否放键）⇒ **移动端在 `workspace-bridge-open` 里省略了 `bridgeGeneration`，
  就必须在 `rpc-frame` 里也省略**；一边有一边无 = `undefined !== 0` = 全部帧静默丢弃。
- 不匹配时 `return false` ⇒ 在 G2 里是 `false` ⇒ **落到 G3**；G3 的 `vf` 若通过，则进 `routePayload` 的
  `case"rpc-frame"` → `wo` → `Ut` → 再判一次桥，仍然 `false` ⇒ **最终什么都不发生**（无回执、无日志、无降级）。
- `processInbound`（**13,695**）→ `ye(e)`（= chunk 内 `M2` = `parseWebRemoteControlRpcTransportPayload`，**548,073**）
  ⇒ `null` 时 `enterDegraded({reasonCode:"remote.rpcFrame.invalidPayload",terminal:!0})` ⇒ **桥降级 + 发 `bridge-degraded`**。
  （与 identity 不匹配不同：**schema 不合法是"响的"，identity 不匹配是"哑的"**。）

---

## 2. 逐 case 的 handler 行为表

> 状态机迁移列里的 `h` = runtime 对象（`{windowId, deviceSid, status, currentBridge, mobileViewState, mobileDeviceInfo, pendingOutboundPayloads, transport, …}`）。
> `le(h,C)` = `sendAppPayload`（**396,082**），`z(h)` = `emitRuntimeStatus`（**394,351**），
> `o(winId,payload)` = `reportRemoteUsageEvent`（**391,204**，上报给桌面自己的用量遥测，**不是发给移动端**）。

### 2.1 `bootstrap-request` → `bootstrap-response`

| 项 | 内容 |
|---|---|
| 处理体 | 内联（405,146）；`T(h)` = `buildBootstrapResult`（**398,579**） |
| 读字段 | 只读 `C.requestId` |
| 产出（成功） | `{zcode_type:"bootstrap-response",requestId,success:!0,result:T(h)}`（**405,176**） |
| `result` 形状 | `{windowControlSessionId:h.deviceSid,desktopAppVersion:e.appVersion,workspaces:M(h),tasks:E(h),initialViewState:O(h),mobileViewState:h.mobileViewState}` |
| 失败分支 | **无**。`T(h)` 是纯函数（`M`/`E`/`O` 都读内存 Map），不抛 ⇒ 恒 `success:!0` |
| 状态机迁移 | 无 |
| 副作用 | 仅 `le` 出站。`M`=`getAvailableWorkspaces`（**397,940**）、`E`=`getAvailableTasks`（**398,125**，按 `updatedAt`/`createdAt`/`taskId` 排序）、`O`=`getRuntimeInitialViewState`（**398,405**） |

**注意**：`T(h)` 内 `mobileViewState:h.mobileViewState` —— 若该字段恒 `undefined`（见 doc 10 §2.4），
则 `bootstrap-response` 里这个键**存在但值为 `undefined`**（`JSON.stringify` 会整键丢掉）。

### 2.2 `workspace-list-request` → `workspace-list-response`

| 项 | 内容 |
|---|---|
| 处理体 | 内联（405,261）；`W(h)` = `buildWorkspaceListResult`（**398,778**） |
| 读字段 | 只读 `C.requestId` |
| 产出 | `{zcode_type:"workspace-list-response",requestId,success:!0,result:W(h)}`（**405,296**） |
| `result` 形状 | `{workspaces:M(h),tasks:E(h),activeWorkspaceKey:h.mobileViewState?.activeWorkspaceKey??A?.activeWorkspaceKey??Ve(C),activeTaskId:h.mobileViewState?.activeTaskId??h.currentBridge?.initialTaskId??A?.activeTaskId}`，其中 `C=kt(h)`（当前工作区目标，**397,284**）、`A=O(h)`（初始视图状态，**398,405**）、`Ve` = 工作区键归一（`workspaceIdentity?.trim()\|\|workspacePath`） |
| 失败分支 | **无** |
| 状态机迁移 | 无 |
| 副作用 | 仅 `le` 出站 |

**这是 `mobile-view-state-update` 唯一的"可观测"下游**：`activeWorkspaceKey` / `activeTaskId` 的**首选来源是
`h.mobileViewState`**。移动端丢帧 ⇒ 这两个字段回落到桌面端自己的初始视图状态（`O(h)`）或桥的 `initialTaskId`。

### 2.3 `platform-request` → `platform-response`

| 项 | 内容 |
|---|---|
| 处理体 | `K` = `respondToPlatformRequest`（**399,706**，`async`） |
| 读字段 | `C.method`、`C.args`、`C.requestId` |
| 分发方式 | `let N=await e.platformHandlers[C.method](C.args)`（**399,752**）—— **按 `method` 字符串直接索引对象**，不做 `in` 检查 |
| 产出（成功） | `{zcode_type:"platform-response",requestId,method:C.method,success:!0,result:N}`（**399,790**） |
| 产出（失败） | `catch` → `logger.warn("[web-remote-control] platform request failed window=… session=… method=… error=…")` → `{zcode_type:"platform-response",requestId,method,success:!1,error:q}`（**400,073**） |
| 状态机迁移 | 无 |
| 副作用 | 取决于方法本身。**会落盘/改配置的方法有两个**：`saveMcpToUserDirectory`（写用户目录 MCP 配置，内部 try/catch 后返回 `{success:false,error}`）与 `migrateLegacyCommonMcp` |

`platformHandlers` 的**完整 8 个键**（`main-index.js` **758,375**）：
`isDockerAvailable`、`listWSLDistros`、`listDockerContainers`、`listSSHConfigAliases`、
**`createTempTextAttachment`**、`loadMcpFromUserDirectory`、`saveMcpToUserDirectory`、`migrateLegacyCommonMcp`。

**schema 枚举 `Kf` 只有 7 个值**（`chunk-OW6EUS2G.js` **724,884**，网页端 `validation-CqXZOLXO.js` 284,385 的 `lS` 同）：
`isDockerAvailable`、`listWSLDistros`、`listDockerContainers`、`listSSHConfigAliases`、
`loadMcpFromUserDirectory`、`saveMcpToUserDirectory`、`migrateLegacyCommonMcp`。

⇒ **`createTempTextAttachment` 是一条死路（3.15.3 真实缺陷）**：
官方网页端 `Reference_web/3.15.3/assets/index-BGa3KiRe.js` **2,110,181** 的粘贴文本处理器会调
`d.createTempTextAttachment?.({text:n,filename:ejt()})`；**6,441,124** 的 `Y9` 把它转成
`n.requestPlatformMethod('createTempTextAttachment', e)`；**6,461,382** 的 `R` 把它打成
`platform-request`；但**两侧 schema 都不含该 method** ⇒ 帧在 G3 被丢弃 ⇒ `platform-response` 永不返回
⇒ 网页端 `requestAppPayload` 的 `await` 挂住（只能等它自己的超时）。桌面端 `platformHandlers` 里那个实现**永远不会被调用**。

### 2.4 `mobile-view-state-update` → `ho`（零副作用）

| 项 | 内容 |
|---|---|
| 处理体 | `ho` = `applyMobileViewStateUpdate`（**404,331**，`s(ho,"applyMobileViewStateUpdate")` 在 404,396） |
| 原文 | `function ho(h,C,A){h.mobileViewState=C,A&&(h.mobileDeviceInfo=A)}` |
| 读字段 | `C.viewState`（→ `h.mobileViewState`）、`C.deviceInfo`（→ `h.mobileDeviceInfo`，**缺省时保持原值**） |
| 产出 | **无出站帧**。没有 `success`/`error` 回执（schema 里也没有这些字段） |
| 状态机迁移 | **无**。不调 `z(h)`（状态推送）、不调 `B(h)`（`workspace-list-updated`）、不调 `se/x`（连接宽限定时器） |
| 副作用 | **仅两个内存字段**。不落盘、不通知 renderer、不广播 |
| 深挖 | 见 `10-移动端帧的接收与校验.md` §2（含 `updatedAt` 必填缺陷与后果清单） |

**一句话索引**：这是全表**唯一零回执、零副作用**的写操作帧；它的全部价值是"给后续
`bootstrap-response` / `workspace-list-response` / `emitRuntimeStatus` 提供首选 `activeWorkspaceKey`/`activeTaskId`"。

### 2.5 `workspace-bridge-open` → `Q`（最重的 handler，含桥的创建）

| 项 | 内容 |
|---|---|
| 处理体 | `Q` = `respondToWorkspaceBridgeOpen`（**403,018**，`async`）；核心 `te` = `createWorkspaceBridge`（**400,208**） |
| 读字段 | `C.requestId`、`C.bridgeSessionId`、`C.bridgeGeneration`、`C.recoveryId`、`C.workspaceKey`、`C.taskId` |
| 查找 | `M(h).find(Se=>Ve(Se)===C.workspaceKey)`（**400,231**）⇒ **按 `workspaceIdentity?.trim()\|\|workspacePath` 归一后的 key 精确匹配** |
| 失败分支 1 | 找不到：`throw new Error("目标工作区不在当前桌面窗口中，无法创建 Web 远程控制 bridge。")` |
| 失败分支 2 | 找到但 `!nu(A)`（`kind==="remote"` 却缺 `workspaceIdentity` 或 `remoteSessionId`）：`throw new Error("目标远程工作区尚未连接，无法创建 bridge，请先重连。")` |
| 成功产出 | `le(h,{zcode_type:"workspace-bridge-ready",requestId,bridgeSessionId,…bridgeGeneration?,…recoveryId?,bridge:A})`（**403,070**）；`bridge` = `f0(q)` = `toExternalBridge`（**390,256**）：`{bridgeSessionId,…bridgeGeneration?,…recoveryId?,workspaceKey,workspacePath,initialTaskId,kind:"local"\|\|"remote",…remote 时额外 workspaceIdentity,remoteSessionId}` |
| 失败产出 | `logger.warn("[web-remote-control] workspace bridge failed window=… session=… workspaceKey=… error=…")` → `le(h,{zcode_type:"workspace-bridge-error",requestId,bridgeSessionId,…bridgeGeneration?,…recoveryId?,reason:u0(A),error:N})`（**403,661**） |
| `reason` 映射 | `u0` = `mapWorkspaceBridgeFailureReason`（**389,338**）：`DESKTOP_HOST_MISSING→desktop-disconnected`；`REMOTE_SESSION_MISSING`/`REMOTE_SESSION_WINDOW_MISMATCH→workspace-closed`；`REMOTE_WORKSPACE_IDENTITY_MISSING`/`_MISMATCH→unsupported-action`；**`default→unexpected-error`**（上面两个中文 throw 走 default） |
| 状态机迁移 | 成功：`h.status="active"`、`h.error/failure=void 0`、`z(h)`；且**只有**当 `h.currentBridge?.bridgeSessionId===C.bridgeSessionId && h.currentBridge?.bridgeGeneration===C.bridgeGeneration` 时才置 `N.readyAnnounced=!0` 并 `N.relayProtocol?.flushPendingFrames()`（**403,396**）。失败：`h.status = h.mobileConnected?"active":"running"`、`z(h)` |
| 副作用 | 1) `w(h)` 清桥、`e.attachWorkspaceHost(h.windowId,…)` 挂 host 端口（**401,226**）；2) `new pg(p0(re.port))` + `Mg({bridgeSessionId:q.bridgeSessionId,…})`（**401,557**）建 rpc 通路与桥协议；3) `o(h.windowId,Qc({result:"success"\|"failure",workspaceKind,remoteKind,entryKind,errorCategory}))` 上报桌面用量遥测；4) 失败时 `e.releaseWorkspaceHostAttachment(re.attachmentId)` 释放 host；5) 另一条入口 `failRemoteSession(h,C,A)`（**411,439**）在 remote session 关闭时**主动**发 `workspace-bridge-error`，`requestId` 是合成的 `` `remote-session-closed:${h}` `` |

**`readyAnnounced` 是 rpc 出站的总闸**：`sendFrame`（**401,746**，`remote.rpcFrame.envelopeTooLarge` 在 401,862）原文
`if(!q.readyAnnounced||q.degraded)return!1;` ⇒ **在 `workspace-bridge-ready` 发出去之前，桥内的任何
`rpc-frame` 都发不出去**（会走 `reserveMessage` → `flushPendingFrames` → `invokeSend` → 返回 `false` → 停住）。

### 2.6 `workspace-reconnect-request` → `wt`

| 项 | 内容 |
|---|---|
| 处理体 | `wt` = `respondToWorkspaceReconnectRequest`（**403,930**，`async`） |
| 读字段 | `C.requestId`、`C.workspaceKey` |
| 成功产出 | `await e.reconnectWorkspace(h.windowId,C.workspaceKey)` 后 `le(h,{zcode_type:"workspace-reconnect-response",requestId,workspaceKey,success:!0})`（**404,017**） |
| 失败产出 | `le(h,{zcode_type:"workspace-reconnect-response",requestId,workspaceKey,success:!1,error:A instanceof Error?A.message:String(A)})`（**404,137**）—— **不 warn、不 log**，失败信息只通过这一帧回给移动端 |
| 状态机迁移 | **无**（`h.status` 不变） |
| 副作用 | 取决于 `e.reconnectWorkspace`（接线在 **759,018**：`reconnectWorkspace:PS`；`PS` 定义在 **414,022**） |

### 2.7 `rpc-frame` / `rpc-frame-ack` → `wo`

| 项 | 内容 |
|---|---|
| 处理体 | `wo` = `routeRpcTransportPayload`（**404,431**）→ `Ut` = `routeRawTransportCandidate`（**404,489**） |
| 读字段 | 桌面端**在这里只读一个字段**：`h.currentBridge` 的存在性与 `A.degraded`。真正的字段校验在桥的 `relayProtocol` 内（`chunk-JS2T33SG.js` 13,521 / 13,695） |
| 产出 | **无任何出站帧**。不产生 `success`/`error`；`Ut` 的返回值被丢弃（`wo` 不返回它） |
| 状态机迁移 | 只有 **schema 不合法 / 序号断档 / 超时** 等 `terminal` fault 才迁移：`enterDegraded` → `degraded=!0`、清空重放缓冲与排队入站、停表 → `onDegraded` → `I(h,q,reasonCode)`（**393,029**）⇒ 发 `bridge-degraded` + `h.currentBridge.degraded=!0` |
| 副作用 | 成功重组出一条逻辑消息时：`onMessageEmitter.fire(...)` → `Se.send(Le)` 送给 host 端口；并 `queueAck(messageSeq)` → 回一条 `rpc-frame-ack` |
| identity 不匹配 | **哑失败**：`acceptPayload` 返回 `false`，无日志无回执无降级（见 §1.5） |
| 深挖 | 分片、CRC、序号、去重、ack、fault 全表见 `07-rpc-frame-分片与上限.md` |

### 2.8 `telemetry-report` → 内联一行

| 项 | 内容 |
|---|---|
| 处理体 | `e.reportRendererTelemetryEvent?.(C.event)`（调用点 **405,654**） |
| 读字段 | `C.event`（整对象透传） |
| 产出 | **无出站帧、无回执** |
| 状态机迁移 | 无 |
| 副作用 | 接线在 **759,040**：`reportRendererTelemetryEvent:s(e=>{go.reportEvent(e).catch(()=>{})},"reportRendererTelemetryEvent")` ⇒ **fire-and-forget，失败静默**（`go.reportEvent` 在 759,075） |
| schema 特点 | `DD` 中**唯一带 `.strict()`** 的臂（臂起始 **725,057**，字面量 725,084），且 `Wa`（**512,908**）三层全 `.strict()`、`client_kind` 是字面量 `"mobile"` ⇒ **多一个键就整帧丢** |
| 深挖 | `10-移动端帧的接收与校验.md` §4.3 |

### 2.9 `mobile-diagnostic` → `Nc`（只写日志）

| 项 | 内容 |
|---|---|
| 处理体 | `Nc` = `logMobileDiagnostic`（**404,625**） |
| 原文 | `function Nc(h,C){e.logger.info("[web-remote-control] mobile diagnostic",{window:h.windowId,session:h.deviceSid,event:C.event,state:C.state,previousState:C.previousState,pairStatus:C.pairStatus,closeCode:C.closeCode,closeReason:C.closeReason,wasClean:C.wasClean,wasPaired:C.wasPaired,failureReason:C.failureReason,failureMessage:C.failureMessage,visibilityState:C.visibilityState,online:C.online,hiddenDurationMs:C.hiddenDurationMs,timestamp:C.timestamp})}` |
| 读字段 | **13 个字段全部读**，但**全部只用于日志**；不做任何兜底、不判空 |
| 产出 | **无出站帧、无回执** |
| 状态机迁移 | **无** |
| 副作用 | **只有 `logger.info` 一行**。不落盘、不上报、无去重、无速率限制 |
| 深挖 | `10-移动端帧的接收与校验.md` §3 |

---

## 3. handler 对字段的假设（缺失/异常时的分支）

| 帧 | 读的字段 | 缺字段时的行为 | 有没有"静默不做"的分支 |
|---|---|---|---|
| `bootstrap-request` | `requestId` | schema 里 `requestId` 必填 ⇒ 缺则**在 G3 整帧丢**，handler 永不进入 | 无（handler 内） |
| `workspace-list-request` | `requestId` | 同上 | 无 |
| `platform-request` | `method`、`args`、`requestId` | `method` 必填且限 7 值；`args` `.optional()` ⇒ 缺则传 `undefined` 进 handler，由各 method 自己兜 | 无。**`platformHandlers[C.method]` 若为 `undefined` 会抛 `TypeError`**，被 `catch` 转成 `success:!1`（但 schema 保证走不到） |
| `mobile-view-state-update` | `viewState`、`deviceInfo?` | `viewState` 必填（`Hf` 三键，`activeWorkspaceKey`/`activeTaskId` 可选、`updatedAt` 必填）；`deviceInfo` 整键可选，但**存在则内部 12 键要过校验**（`updatedAt` 必填）。`A&&(h.mobileDeviceInfo=A)` ⇒ **`deviceInfo` 缺失时不覆盖旧值** | **有**：`ho` 对 `viewState` 内字段不做任何兜底（原样存），后续 `W` 用 `?.` 可选链取用 |
| `workspace-bridge-open` | `requestId`、`bridgeSessionId`、`bridgeGeneration?`、`recoveryId?`、`workspaceKey`、`taskId?` | `workspaceKey` 必填；`taskId` 可选 ⇒ 缺则 `initialTaskId:undefined`（`toExternalBridge` 会带上 `initialTaskId:undefined` 这个键）。**`bridgeGeneration`/`recoveryId` 的"有/无"决定桥 identity 的键集合**，进而决定后续 rpc-frame 能否被接受（§1.5） | **有**：`bridgeGeneration` 用 `…C.bridgeGeneration!==void 0?{bridgeGeneration:C.bridgeGeneration}:{}` 展开，缺则不放键 |
| `workspace-reconnect-request` | `requestId`、`workspaceKey` | 都必填 | 无 |
| `rpc-frame` | 桌面端 handler 只读 `h.currentBridge`；字段校验在桥内 | identity 键缺失/不等 ⇒ **哑丢** | **有**（identity 不匹配这条分支完全静默） |
| `rpc-frame-ack` | 同上 | 同上 | 同上 |
| `telemetry-report` | `event`（整对象） | `.strict()` + 全字段必填 ⇒ 缺任一键整帧丢 | 无 |
| `mobile-diagnostic` | 13 个字段 | `event`/`timestamp` 必填，其余可选 ⇒ 缺则日志里是 `undefined` | 无 |

**跨 handler 的共性**：
- **没有任何 handler 会"因为字段缺失而静默不做"**——除了 `ho` 对 `deviceInfo` 的 `A&&` 短路、
  `workspace-bridge-open` 对 `bridgeGeneration`/`recoveryId` 的条件展开、以及 rpc 通路的 identity 比较。
- **所有"字段不存在就不做"的判定都发生在 schema 层**（G3），且后果是**整帧丢弃 + 一条 warn**，不是"部分降级"。
- 只有 `platform-request` 的失败、`workspace-bridge-open` 的失败、`workspace-reconnect-request` 的失败
  **会回一帧 `*-error` / `success:!1`**。其余 7 个臂**要么无回执（fire-and-forget），要么是请求-响应但校验失败时无回执**。

---

## 4. 出站方向：桌面端主动发给移动端的帧

### 4.1 全集（11 种）与触发条件

| # | `zcode_type` | 构造点 | 触发条件 | 发给谁 |
|---|---|---|---|---|
| 1 | `bootstrap-response` | 405,176 | 收到 `bootstrap-request`（且通过 G1/G3） | 该窗口唯一那条 relay 连接 |
| 2 | `workspace-list-response` | 405,296 | 收到 `workspace-list-request` | 同上 |
| 3 | `workspace-list-updated` | 399,626 | **主动推**：`syncAvailableWorkspaces`/`syncAvailableTasks`（**411,217 / 411,283**，由 renderer 通知）→ `B`=`pushWorkspaceListUpdated`（**399,544**）；**去重**：`D`=`buildWorkspaceListPushSignature`（**399,059**）用 `JSON.stringify` 出 `[workspaces签名[],tasks签名[]]`（workspace 5 字段 / task **10** 字段），与 `d.get(windowId)` 相同则**不发** | 同上 |
| 4 | `platform-response` | 399,790（成功）/ 400,073（失败） | 收到 `platform-request` | 同上 |
| 5 | `workspace-bridge-ready` | 403,070 | `workspace-bridge-open` 成功 | 同上 |
| 6 | `workspace-bridge-error` | 403,661（open 失败）/ 411,584（remote session 关闭） | open 抛错；或 `failRemoteSession` 检测到该 remote session 关闭 | 同上 |
| 7 | `workspace-reconnect-response` | 404,017（成功）/ 404,137（失败） | 收到 `workspace-reconnect-request` | 同上 |
| 8 | `bridge-degraded` | 393,302（`I`=`degradeBridgeAfterRawFault`） | rpc 通路降级（schema 不合法/序号断档/超时/重放缓冲超限/发送失败…）或 relay 单消息超 1 MiB。**`reason` 恒为字面量 `"rpc-transport-fault"`**，schema 允许的另 3 值（`rpc-frame-gap`/`buffer-overflow`/`buffer-timeout`）**桌面端从不发**；`seq`/`expectedSeq`/`droppedCount` 也从不发 | 同上 |
| 9 | `app-error` | 396,459（`ht`=`preserveWindowRuntimeFailure`，**396,381**）/ 396,898（`ne`=`stopWindowRuntime`，**396,856**） | runtime 进入错误态；或用户停用远控。`ht` 的 reason 来自失败对象，`ne` 的 reason 恒为 `"desktop-disconnected"` | 同上 |
| 10 | `rpc-frame` | `chunk-JS2T33SG.js` 12,340（`flushPendingFrames` 里 `invokeSend(o)`，`o` 来自 `$2`=`encodeWebRemoteControlRpcTransportMessage`，549,427） | 桥内有消息要发；**且 `readyAnnounced` 为真且未 degraded**；按 `reserveMessage` 排队、`messageSeq`/`seq` 从 **1** 起 | 按 `bridgeSessionId` 定位的**那条桥**（连接仍是同一条） |
| 11 | `rpc-frame-ack` | `chunk-JS2T33SG.js` 13,499（`invokeSend({zcode_type:"rpc-frame-ack",...this.identity,ackMessageSeq:u})`） | 成功重组出一条逻辑消息（`queueAck`）后，**在下一次 `flushPendingFrames` 里优先于数据帧发出** | 同上 |

### 4.2 出站的三道闸 + 两条丢弃策略

`main-index.js` **396,082**（`le` = `sendAppPayload`，原文逐字）：

```js
function le(h,C){if(h.pendingOutboundPayloads.length>0){U(h,C),Te(h);return}let A=me(h,C);
 if(A.kind!=="sent"){if(A.kind==="oversize"){e.logger.warn("[web-remote-control] rejected oversize app payload",{zcodeType:C.zcod…
```

`me` = `sendPayloadToTransport`（**395,166**）→ 最终 `transport.sendPayloadResult`（**379,472**）：

```js
sendPayloadResult(t){let r=this.payloadSerializer.prepare(t);
 return this.payloadSerializer.isOversize(r)?{kind:"oversize",bytes:r.bytes,maxBytes:kr.maxPhysicalFrameBytes}:
   this.state!=="paired"||this.staleWaitingCount>0?{kind:"unavailable"}:
   this.sendSerialized(r.message,r.json)?{kind:"sent",bytes:r.bytes}:{kind:"unavailable"}}
```

⇒ 三种结果：`sent` / `oversize`（`>1 MiB`，**直接丢弃并 warn**）/ `unavailable`（**未配对或正在 stale-waiting 恢复**）。
`unavailable` 时 `le` 调 `U`（`bufferOutboundPayload`，**395,354**）入缓冲队列。

**两条丢弃策略**：
- `U`：`pendingOutboundPayloads.length>=50` ⇒ **清空整个队列**（含新帧）并 warn `"dropped overflowing outbound payloads"`；
- `ee`（`schedulePendingOutboundPayloadTimeout`，**394,766**）：队列非空时挂 **5,000 ms** 定时器，
  到点 `splice(0)` **清空**并 warn `"dropped buffered outbound payloads"`。

**`Te`（`flushPendingOutboundPayloads`，395,701）**：从队首逐条重试；`oversize` → warn 并 `shift()` 继续；
`unavailable` → 重新挂 `ee` 并**中断**（保留剩余帧）。

**出站信封**（`chunk-JS2T33SG.js` **16,572** `class xe` / **16,659** `prepare`，原文逐字）：

```js
prepare(e,r=Date.now()){let o=this.cache.get(e);if(o)return o;
 let c={type:"data",payload:e,client_ts:r},u=JSON.stringify(c),
 S=Object.freeze({message:c,json:u,bytes:new TextEncoder().encode(u).byteLength});
 return this.cache.set(e,S),S}
isOversize(e){return e.bytes>g.maxPhysicalFrameBytes}
```

⇒ **所有 app 级出站帧都包在 `{type:"data", payload:<zcode_type 对象>, client_ts}` 里**，且用 `WeakMap` 按对象身份缓存
（同一对象重复发复用同一序列化结果）。`sendSerialized`（**379,717**）另有一道 `JSON.stringify` 后的
`Buffer.byteLength>kr.maxPhysicalFrameBytes` 硬闸（**388,455** 的 `send`）。

### 4.3 "发给谁"

桌面端**每个窗口只有一条 relay 连接**（`ge.transport`，即 `windowId → runtime` 一对一），
所以 app 级帧没有"选目标"这一步——**发给该窗口唯一那条已配对的移动连接**。
`rpc-frame` / `rpc-frame-ack` 带 `bridgeSessionId` 不是用来做多路分发的——**每个窗口同时只有一座桥**
（`h.currentBridge` 单值；`te` 开头先 `w(h)` 清旧桥再赋新值）。它是**桥的世代标识**：移动端换工作区会发新的
`workspace-bridge-open`，桌面端据此建一座**新桥**（新 `bridgeSessionId`），旧桥的帧此后全部 identity 不匹配 ⇒ 哑丢。
`rpc-frame` 的 `sendFrame` 闭包**绑定的是 `q` = 该次 `workspace-bridge-open` 建出来的那座桥**（**401,746**，在 `te` 内）。

---

## 5. `default:` 分支

`main-index.js` **405,738**：`default:break`。

- **未知 `zcode_type` = 静默忽略**：无日志、无回执、无计数、无遥测。
- 但**前提是它能通过 `vf`**：`UD` 是 21 臂的普通 union，未知 `zcode_type` 的**任何对象都会被
  `safeParse` 判失败**（没有 catchall、没有 passthrough 臂）⇒ 实际上**未知 `zcode_type` 根本走不到
  `routePayload` 的 `default`**，它在 G3 就被 warn 掉。
- `default:break` 真正兜住的是**"schema 里有、`routePayload` 里没有 case"的臂**。
  按 `DD`（**725,057**）逐臂比对，**只有 `app-error` 属于这一类**：它在 schema 里（`literal("app-error")` 在 **727,344**），
  但 `routePayload` 没有对应 case ⇒ 桌面端收到移动端发来的 `app-error` 时**走 `default:break` 静默忽略**
  （`app-error` 的官方方向是桌面端→客户端，所以这是"反向容错"而非缺陷）。

**可复核的比对方法**：`DD` 的 21 个臂 vs `routePayload` 的 10 个 case。
`DD` 里其余臂要么是**响应帧**（移动端不该发：`bootstrap-response` / `workspace-list-response` /
`workspace-bridge-ready` / `workspace-bridge-error` / `workspace-reconnect-response` / `platform-response` /
`bridge-degraded` / `app-error`），要么是**桌面端主动推**（`workspace-list-updated`）——
这些方向反了的时候，**能过 `vf`、但落进 `default:break`**。

---

## 6. 与我们的实现的差异

> 对照对象：`E:\Zcode_android\rust\crates\ffi\src\session.rs`、`rust\crates\ffi\src\conversation.rs`
> （**可读不可改**），以及它们下游真正做入站分发的
> `rust\crates\protocol\src\zemote_client.rs`（`dispatch_payload`）、
> `rust\crates\protocol\src\rpc_transport.rs`（`accept_payload`）。
> **说明**：`ffi/src/session.rs` 是 FFI 门面（`create`/`step`/`call`/`query`/`dispose`/`snapshot`），
> `ffi/src/conversation.rs` 是对话层（定时器/通道/自动化），**两者都不做 `zcode_type` 分派**；
> 入站分派全在 `zemote_client.rs`。下表以真正承载行为的位置给行号。

### 6.1 不一致（我们比桌面端宽松，且方向不同）

| # | 项 | 桌面端 | 我们的实现 | 后果 |
|---|---|---|---|---|
| D1 | **入站 schema 校验** | 每帧过 `UD.safeParse`（21 臂 union），失败整帧丢 + warn；返回值是 **strip 过的 `n.data`** | `zemote_client.rs:1023` `dispatch_payload` **不做任何 schema 校验**：只按 `zcode_type` 取字符串、按 matcher 比对字段。未知键**原样保留** | 桌面端会丢的畸形帧我们会接受；桌面端会 strip 的键我们会看到。**方向是"我们更宽"**，不会导致对端拒帧 |
| D2 | **未配对/未 ready 时的入站** | G1：`state!=="paired"` ⇒ 整帧丢（含 `waiting_terminal`） | 无等价闸：`zemote_client.rs:998` 收到 `RelayEvent::Payload` 即 `dispatch_payload` | 我们可能在配对完成前就消费帧；桌面端不会 |
| D3 | **rpc-frame 的 identity 校验** | `chunk-JS2T33SG.js:7688` `ke()` 要求 **`bridgeSessionId` + `bridgeGeneration` + `recoveryId` 三者 `===`**（`undefined` 也是值） | `rpc_transport.rs:343-347` **只比 `bridgeSessionId`**，完全不看 `bridgeGeneration` / `recoveryId` | 桌面端发给我们的帧**必然**通过（它按我们 `workspace-bridge-open` 的键集合构造 identity）；但若我们回发的帧键集合与 open 请求不一致，**桌面端会静默全丢**——我们目前不会察觉 |
| D4 | **rpc-frame 无法路由时的处理** | `acceptPayload` 返回 `false` ⇒ **哑丢**（无日志无缓存无回执） | `zemote_client.rs:1053-1069`：按 `bridgeSessionId` 找 `frame_routers`，**找不到就塞进 `pending_bridge_payloads` 缓存**，等桥挂上后重放；`bridgeSessionId` 非字符串则静默丢 | 我们有缓存重放、桌面端没有。**我们更宽松**；但两侧都"静默"，出问题时都难查 |
| D5 | **入站 ack** | `chunk-JS2T33SG.js:14851` `processAck`：`ack<=lastAcked` 忽略；`ack>highestFullySent` ⇒ **`remote.rpcFrame.futureAck` 终态降级**；否则释放重放缓冲 | `rpc_transport.rs:334-337`：`if zcode_type==Some(RPC_FRAME_ACK){outcome.consumed=true;return}` ⇒ **收到 ack 什么都不做** | **不致命**：我们没有重放缓冲，忽略入站 ack 无副作用；而桌面端的重放缓冲靠**我们发出的** ack 释放，我们**有发**（`rpc_transport.rs:445-452`，成功重组后 `ack_payload(message_seq)`），所以对端不会因缺 ack 而饱和/降级。真正的差距：我们无法发现对端 `futureAck` 异常，也无法实现 ack 驱动的重放 |
| D6 | **入站 rpc-frame 的序号/顺序** | 严格要求 `seq` 从 1 起、逐帧 +1、`messageSeq` 从 1 起、`fragmentIndex` 从 0 起按序；乱序/断档/重复冲突 ⇒ **终态降级** | `rpc_transport.rs:330+` 的 `accept_payload`：**不校验 `seq`**、不校验 `messageSeq` 连续性、不查重 | 桌面端给我们的帧必然有序，故不致命；但我们**无法发现**对端异常 |
| D7 | **超限的处置** | `fragment_count>64 \|\| message_bytes>16 MiB` ⇒ `enterDegraded` 终态降级 + `bridge-degraded` | `rpc_transport.rs:367-376`：只 `logs.push("[rpc] dropped oversized message …")` 并 `consumed=true`，**不降级** | 对端超限会降级、我们不会。行为不同但不影响互通 |

### 6.2 未实现（对端"期望我们发"但我们从不发的帧）

用 `grep -rl '"<type>"' rust/ android/` 全仓确认：

| 帧 | 方向（doc 49 §4 结论） | 桌面端对它做什么 | 我们的现状 | 影响 |
|---|---|---|---|---|
| **`platform-request`** | 客户端 → 桌面端 | `case"platform-request"` → `K` → 调 `platformHandlers[method]` → 回 `platform-response`（**399,706**） | **全仓 0 命中**，从不发 | 移动端**无法**使用 7 个桌面平台能力（Docker/WSL/SSH/MCP 目录读写）。官方网页端在 `Reference_web/…/index-BGa3KiRe.js` 6,461,382 的 `R` 里发它 |
| **`telemetry-report`** | 客户端 → 桌面端 | `case"telemetry-report"` → 转发进桌面遥测（**405,629**） | **全仓 0 命中** | 只是遥测缺失，功能无影响。官方网页端在 6,461,382 附近 `reportTelemetryEvent` 里发它 |
| **`mobile-diagnostic`** | 客户端 → 桌面端 | `case"mobile-diagnostic"` → `Nc` 打一行 info（**404,625**） | **全仓 0 命中** | 只是诊断日志缺失。官方网页端在 `index-BGa3KiRe.js` 6,576,126 / 6,576,574 发它 |
| **入站 `app-error`（反向缺口）** | 桌面端 → 客户端 | 桌面端在 `ht`（396,381）与 `ne`（396,856）主动发它，`ne` 的 reason 恒 `"desktop-disconnected"` | `zemote_client.rs` 与 `ffi/` **全仓 0 命中**：`dispatch_payload` 没有 `app-error` 分支，会落到 `:1076` 的 pending-matcher 循环里**匹配不上任何挂起项 ⇒ 静默忽略** | **桌面端主动断开远控时，我们的移动端收不到任何通知**，只能等自己的心跳/看门狗。官方网页端在 6,578,366 用它走全局错误面 |

### 6.3 已确认一致（不必改）

- 出站 `rpc-frame-ack` 的**字段集合**：我们 `rpc_transport.rs:535-548` 的 `ack_payload` 只放
  `zcode_type` / `bridgeSessionId` / `ackMessageSeq` 三键，与桌面端 `Dg`（**547,205**）的
  `.optional()` 约束一致（`bridgeGeneration`/`recoveryId` 可选）——BRIEF §6.4 的结论在我们这一侧成立。
- 出站帧的**必填项**：`bootstrap-request`(`requestId`)、`workspace-list-request`(`requestId`)、
  `workspace-bridge-open`(`requestId`,`bridgeSessionId`,`workspaceKey`)、
  `workspace-reconnect-request`(`requestId`,`workspaceKey`) 与桌面端 schema 一致
  （`zemote_client.rs:2386 / 2396 / 2414 / 2452`）。

### 6.4 `mobile-view-state-update` 的缺陷（复核确认，输入给主线）

- 我们的发送器 `zemote_client.rs:2462-2489` 构造 `viewState` 时**带了 `updatedAt: now_ms`**（正确），
  但 `deviceInfo` 用的是 `device_info::DeviceInfo`（**`rust\crates\protocol\src\device_info.rs:98-109`**），
  **只有 `platform`/`version`/`name` 三键，没有 `updatedAt`**。
- 桌面端 `MD`（`chunk-OW6EUS2G.js` **724,141**）里 `updatedAt:e.number().finite()` **没有 `.optional()`**
  ⇒ 我们发出的整帧在 G3 被丢弃（**384,954**）。
- **修法**：给 `device_info::DeviceInfo` 加 `updatedAt`（或在 `mobile_view_state_update_payload` 里补一个），
  值取 `now_ms`。**注意**：`DeviceInfo` 也可能被别处复用，加键前先看调用点（懒人阶梯：优先只在
  `mobile_view_state_update_payload` 内补键，不动公共类型）。深挖见 `10-移动端帧的接收与校验.md` §2.6。

---

## 7. 仍未证实 / 需要抓包

1. **"桌面端真的丢了我们发的 `mobile-view-state-update`"** —— 目前是**schema 演绎**（`updatedAt` 无
   `.optional()` + `vf` 返回 `null` 即 return），**没有抓包证据**。需要：用我们当前的 Rust 客户端连一次真桌面端，
   抓 `mobile-view-state-update` 之后桌面端日志里是否出现
   `invalid external relay payload dropped` 且 `zcode_type:"mobile-view-state-update"`。
2. **`state!=="paired"` 窗口期的实际宽度** —— 代码上 `waiting_terminal` 也丢帧，但**桌面端在
   `waiting_terminal` 阶段是否会收到我们的业务帧**（取决于我们何时开始发）未证实。需要抓包看时序。
3. **`default:break` 是否真的只兜 `app-error`** —— 我用 `DD`(725,057) 的 21 臂与 10 个 case 手工比对得出。
   若 B02 的字段表发现 `DD` 实际臂数不同（BRIEF 说 19 字面量 / 21 臂），需以 B02 为准重比。
4. **`createTempTextAttachment` 死路的运行时确认** —— 静态证据是两侧 schema 都无该 method（网页端
   `lS` @284,385、桌面端 `Kf` @724,884）而两侧调用点都有（网页端 6,441,124、桌面端 758,375）。
   需要：在真机上触发一次"远控会话里粘贴纯文本"，看是否出现
   `invalid external relay payload dropped` + `zcode_type:"platform-request"`。
5. **`reportRendererTelemetryEvent` 之后发生了什么** —— 我只追到 `go.reportEvent(e).catch(()=>{})`（759,075），
   `go` 的实现与上报目标**未展开**（不在本文范围，但如果要判断"发 telemetry-report 有没有实际收益"需要）。
6. **`workspace-list-updated` 的推送频率** —— `B` 由 `syncAvailableWorkspaces`/`syncAvailableTasks` 触发，
   这两个方法的上游（renderer 的 `broadcastService` 事件）**未展开**；去重签名 `D` 覆盖 10 个 task 字段
   与 5 个 workspace 字段（**399,059**），但"多久被调一次"未证实。
7. **`rpc-frame` 出站 `seq` 起点在重连后的行为** —— `reserveMessage` 里 `nextPhysicalSeq`/`nextMessageSeq`
   初值都是 **1**（`chunk-JS2T33SG.js` 9,0xx 的类字段），`replayUnacknowledged`（**13,373**）只 `resetReplay()`
   不重置 seq。**重连（新桥）后是否从 1 重新开始**未证实——这决定我们入站装配器的 `expectedPhysicalSeq`
   初值（我们 `rpc_transport.rs` 不校验 seq，所以不影响互通，但影响我们理解对端）。
8. **`bridge-degraded` 的 `reason` 恒为 `"rpc-transport-fault"`** —— 我在 `I`（393,029）里读到的是字面量，
   且全 `main-index.js` 只有一处构造 `bridge-degraded`。**未证实**是否有别处（如 renderer 侧）构造其他 reason。
