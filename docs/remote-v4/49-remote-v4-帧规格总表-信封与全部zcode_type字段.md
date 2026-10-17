# remote-v4 帧规格总表 —— 信封与全部 `zcode_type` 字段

> **审计基线**：3.15.3（2026-10-15 快照）
> 主包 `assets/index-BGa3KiRe.js`（6,592,643 B，SHA-256 `ce2e2fc4…b88fd`）
> schema `assets/validation-CqXZOLXO.js`（307,369 B，SHA-256 `96e64e8f…dd6c9b`）
> 旧基线（2026-09-20 / `index-B-ilXaCQ.js`）的偏移与符号名**一律作废**。
>
> 快照目录（只读）：`E:\Zcode_android\Reference_web\3.15.3\`。**偏移口径一律为字节偏移**
> （`Buffer.indexOf`，不是 UTF-16 字符偏移；旧稿的"字符偏移"口径在本构建上会错位，勿沿用）。
> 桌面端交叉校验（只读，不写入本文结论）：`app.asar` `out/main/chunk-OW6EUS2G.js` 为同一份 zod 的同构副本
> （见 §7.3）。
>
> **本文的边界**：本文只覆盖**帧层**——信封、判别联合、逐帧字段、`.strict()`、枚举、速查表。
> `workspaces[i]` / `tasks[i]` / `sessions[i]` / `viewState` / `mobileViewState` / `deviceInfo` 的
> **载荷内部叶子字段表**由 [14 篇](14-remote-v4-bootstrap载荷-完整字段表.md) 与
> [17 篇](17-remote-v4-bootstrap载荷-合并总表与未解析字段清单.md) 产出，本文只给顶层键与引用点。

---

## 0. 一句话

3.15.3 的业务帧判别联合是 **19 个 `zcode_type` 字面量 / 21 个 union 臂**，定义在
`validation-CqXZOLXO.js` 的 `uS`（off **284,618**）；相对 2026-09-20 基线**新增 1 个字面量
`telemetry-report`**，**无消失**。21 个臂里**只有 1 个是 `.strict()`**（`telemetry-report`），
其余 18 臂全是裸 `z.object`（zod 默认 **strip**：多键静默剥离、缺必填即整帧非法）。
另有两个 `.strict()` 的帧族成员 `rpc-frame` / `rpc-frame-ack`（`Dd`/`Od`）与一个 `.strict()` 的
`data` 传输封套——**帧级 strict 只有这四处**（`telemetry-report` 的嵌套 `Sl` / `context` /
`eventExtraDetail` 也全 strict，见 §3.2）；**只有这四类帧会因"多一个键"被整帧拒绝**。

---

## 1. 信封层

### 1.1 三层结构（先分清层次，否则所有字段都会读错）

```
relay WebSocket 消息                      ← 第 1 层：顶层 type（§1.2）
  └─ { type:'data', payload, client_ts?, server_ts? }   ← 第 2 层：传输封套（§1.3），.strict()
       └─ payload = { zcode_type:'…', … }               ← 第 3 层：业务帧（§2），只有 1/19 是 .strict()
```

- 第 1 层的顶层消息**不是** `zcode_type` 帧，它是 relay 传输协议（`auth_*` / `pair_status_*` / `data` / `error`）。
- 第 2 层的 `payload` 在 schema 上**只允许** `rpc-frame` / `rpc-frame-ack`（`kd = k([Dd, Od])`，off **129,993**）。
  但页面代码把**任意业务帧对象直接塞进 `payload`** 发送（见 §2 各帧的发送点），
  所以 schema 的 `kd` 只是"传输分片"这条支路的约束，不是业务帧的白名单。
  判别函数（主包 off **6,473,860**，`u4n`）：
  ```js
  function u4n(e){if(typeof e!=`object`||!e)return!1;let t=e.zcode_type;return t===`rpc-frame`||t===`rpc-frame-ack`}
  ```
  同一判据在主包另有一份（off **6,521,747**，`U3n`）——用于页面自己的 trace 裁剪。

### 1.2 第 1 层：relay 顶层消息类型

页面自己的 trace 裁剪函数（主包 off **6,521,747**，`W3n`）逐类列出保留字段，即权威形状：

| 顶层 `type` | 保留字段 | 方向 | 证据 |
| --- | --- | --- | --- |
| `auth_init` | `role` | 客户端 → 桌面端（经 relay） | 主包 off 6,521,747；发送点 off **6,523,300**（`{type:'auth_init',role:'terminal',device_sid,meta:{platform:'web',version,name:'mobile-browser'},client_ts}`） |
| `auth_challenge` | ——（带 `nonce`） | relay → 客户端 | 主包 off **6,526,366** |
| `auth_response` | ——（带 `proof`） | 客户端 → relay | 主包 off **6,526,399** |
| `auth_ack` | `pair_status` | relay → 客户端 | 主包 off **6,526,600** |
| `pair_status_query` | —— | 客户端 → relay（心跳） | 主包 off **6,529,391** |
| `pair_status_ack` | `pair_status` | relay → 客户端 | 主包 off **6,521,835**、**6,526,615** |
| `data` | `payload`（经 `H3n` 裁剪为 `{zcode_type, requestId, bridgeSessionId}`） | 双向 | 主包 off 6,521,747 / **6,521,474** |
| `error` | `code` | relay → 客户端 | 主包 off 6,521,747 |
| `socket-extensions` | —— | 仅本地诊断 | 主包 off **6,523,171**、**6,576,101** |

> `H3n`（主包 off **6,521,474**）是**本构建里替换旧 `q2t` 的那个裁剪器**，逐字：
> ```js
> function H3n(e){if(!e||typeof e!=`object`||Array.isArray(e))return{};let t=e;
>   return{zcode_type:typeof t.zcode_type==`string`?t.zcode_type:void 0,
>          requestId:typeof t.requestId==`string`?t.requestId:void 0,
>          bridgeSessionId:typeof t.bridgeSessionId==`string`?t.bridgeSessionId:void 0}}
> ```
> 它证明：**业务层的"身份三键"只有 `zcode_type` / `requestId` / `bridgeSessionId`**，
> 其余全是载荷字段。这三个键也是 trace / 日志里唯一被保留的。

> 顶层消息的完整语义、心跳周期与状态机归 [16 篇](16-remote-v4-relay协议目录.md)；本文只到"它是第 1 层"为止。

### 1.3 第 2 层：`data` 传输封套（schema 原文）

```js
// validation-CqXZOLXO.js off 130,007
O({type:N(`data`),payload:kd,client_ts:Sd.optional(),server_ts:Sd.optional()}).strict()
// 其中 kd = k([Dd,Od])  @129,993 ；Sd = w().int().nonnegative().max(2**53-1)  @128,750
```

| 键 | 类型 | 必填 | 约束 | 说明 |
| --- | --- | --- | --- | --- |
| `type` | literal | ✅ | 常量 `'data'` | 第 1 层类型 |
| `payload` | union | ✅ | `rpc-frame` \| `rpc-frame-ack`（schema）；实际可为任意业务帧对象 | 见 §1.1 |
| `client_ts` | number | ❌ | int / nonnegative / ≤ 2^53−1（`Sd`） | 客户端时间戳 |
| `server_ts` | number | ❌ | 同上 | 服务端时间戳 |

**`.strict()`**：是。多一个键 ⇒ `unrecognized_keys`（zod 内核报错点 off **33,517**：
`code:'unrecognized_keys',keys:s,input:t,inst:a,continue:true`）⇒ 整个封套解析失败。

### 1.4 业务帧共有字段词表（"信封层"字段 = 跨帧复用的顶层键）

这是**实现者最该背下来的表**：同一个键名在不同帧里类型/必填不同，是常见的踩坑点。

| 键 | 类型（schema 别名） | 定义 off | 约束 | 出现在哪些帧 | 必填性 |
| --- | --- | --- | --- | --- | --- |
| `zcode_type` | `N(...)` literal | 94,583 | 19 个字面量之一 | 全部 21 臂 | ✅ 全部必填 |
| `requestId` | `$` = `S().trim().min(1)` | 282,127 | **trim 后非空字符串，无长度上限** | bootstrap-\* / workspace-list-\* / workspace-bridge-\* / workspace-reconnect-\* / platform-\* | 除 `workspace-list-updated`、`bridge-degraded`、`mobile-diagnostic`、`mobile-view-state-update`、`telemetry-report`、`app-error` 外均**必填** |
| `success` | `N(!0)` / `N(!1)` literal | 94,583 | 字面量 `true` 或 `false`（**不是 boolean**） | bootstrap-response、workspace-list-response、workspace-reconnect-response、platform-response | ✅（各自那一臂） |
| `result` | `sS` / `cS` / `E()` | 284,202 / 284,350 / 90,889 | 见 §2 | 同上 + `workspace-list-updated` | ✅（成功臂） |
| `error` | `S()` = `z.string()` | 87,801 | 任意字符串（**无长度约束**） | 失败臂 + `workspace-bridge-error` + `app-error` | ✅（失败臂） |
| `reason` | `rS` = `M([…11 值…])` | 283,087 | 见 §4.1 | `workspace-bridge-error`、`app-error` | ✅ |
| `bridgeSessionId` | `eS` | 282,147 | `$.max(256).regex(/^[A-Za-z0-9._~-]+$/)` | 桥族 + `rpc-frame*` + `bridge-degraded` + `app-error?` | 桥族必填；`workspace-bridge-error` / `app-error` 为**可选** |
| `bridgeGeneration` | `w().int().nonnegative()` | 90,145 | int ≥ 0，**无上限** | 桥族 + `rpc-frame*` | ❌ 全部可选 |
| `recoveryId` | `eS` | 282,147 | 同 `bridgeSessionId` | 桥族 + `rpc-frame*` | ❌ 全部可选 |
| `workspaceKey` | `$` | 282,127 | trim 非空 | `workspace-bridge-open`、`workspace-reconnect-*` | ✅ |
| `taskId` | `$` | 282,127 | trim 非空 | `workspace-bridge-open` | ❌ 可选 |
| `method` | `lS` = `M([…7 值…])` | 284,450 | 见 §4.5 | `platform-request` / `platform-response` | ✅ **请求与响应都必填** |
| `args` | `E()` = `z.unknown()` | 90,889 | 无约束 | `platform-request` | ❌ 可选 |
| `event` | `Sl`（对象）/ `M([…7 值…])` | 100,400 / 94,165 | 见 §2.1 / §4.3 | `telemetry-report` / `mobile-diagnostic` | ✅ |
| `timestamp` | `w().int().nonnegative()` | 90,145 | int ≥ 0 | `mobile-diagnostic` | ✅ |
| `viewState` | `aS` | 283,744 | 见 §2.1 | `mobile-view-state-update` | ✅ |
| `deviceInfo` | `oS` | 283,833 | 见 §2.1 | `mobile-view-state-update` | ❌ 可选 |

### 1.5 身份三元组与 id 约束（`bridgeSessionId` / `bridgeGeneration` / `recoveryId`）

```js
// validation-CqXZOLXO.js off 128,862 —— 独立导出的严格三元组
wd = O({bridgeSessionId:Cd, bridgeGeneration:Sd.optional(), recoveryId:Cd.optional()}).strict()
// Cd @128,790 = S().min(1).max(bd.transportIdMaxChars).regex(/^[A-Za-z0-9._~-]+$/u)
// Sd @128,750 = w().int().nonnegative().max(2**53-1)
// bd @128,552 = {maxPhysicalFrameBytes:Uu.maxFrameBytes(1 MiB), maxMessageBytes:16 MiB,
//                maxFragments:64, assemblyTimeoutMs:3e4, transportIdMaxChars:Uu.transportEnvelopeIdMaxChars(256)}
// Uu @117,018 = {maxFrameBytes:1024*1024, logicalFrameAssemblyMaxBytes:16*1024*1024,
//                logicalFrameAssemblyMaxFragments:1024, logicalFrameAssemblyMaxConcurrent:32,
//                logicalFrameAssemblyMaxStagedBytes:32*1024*1024, logicalFrameAssemblyTimeoutMs:3e4,
//                transportEnvelopeIdMaxChars:256, subscriberBufferMaxOps:500, subscriberBufferMaxBytes:1024*1024,
//                eventRetentionPerSession:2e3, snapshotTailWindowRows:60, …}
```

**两条 id 变体，别混**（这是 3.15.3 里最容易写错的约束）：

| 变体 | 定义 | trim | 长度上限 | 字符集 | 用在哪 |
| --- | --- | --- | --- | --- | --- |
| `Cd` @128,790 | `S().min(1).max(256).regex(/^[A-Za-z0-9._~-]+$/)` | **不 trim** | 256 | `[A-Za-z0-9._~-]` | `rpc-frame*` 的 `bridgeSessionId`/`recoveryId` |
| `eS` @282,147 | `$.max(256).regex(/^[A-Za-z0-9._~-]+$/)`，其中 `$=S().trim().min(1)` | **先 trim** | 256 | 同上 | 业务桥族（`workspace-bridge-*`、`bridge-degraded`、`app-error`）的 `bridgeSessionId`/`recoveryId` |

> 实践含义：业务帧里的 id 允许带首尾空白（会被 trim 掉）；`rpc-frame` 里的 id **不允许**任何空白字符
> （`regex` 直接不匹配）。壳侧生成 id 时统一用 `[A-Za-z0-9._~-]` 即可两端通吃。

---

## 2. 判别联合全集（19 字面量 / 21 臂）

### 2.0 联合定义原文与别名反解

```js
// validation-CqXZOLXO.js off 284,618
uS = k([ O({zcode_type:N(`telemetry-report`),event:Sl}).strict(), O({zcode_type:N(`bootstrap-request`),requestId:$}), … ])
// 解析器 off 287,156：function dS(e){let t=uS.safeParse(e);return t.success?t.data:null}
```

**联合全文**（off 284,618–287,153，逐字节原文，按 arm 折行；行首数字 = 该臂的字节偏移）：

```js
284627 O({zcode_type:N(`telemetry-report`),event:Sl}).strict(),
284683 O({zcode_type:N(`bootstrap-request`),requestId:$}),
284734 O({zcode_type:N(`bootstrap-response`),requestId:$,success:N(!0),result:sS}),
284810 O({zcode_type:N(`workspace-list-request`),requestId:$}),
284866 O({zcode_type:N(`workspace-list-response`),requestId:$,success:N(!0),result:cS}),
284947 O({zcode_type:N(`workspace-list-updated`),result:cS}),
285001 O({zcode_type:N(`workspace-bridge-open`),requestId:$,bridgeSessionId:eS,bridgeGeneration:w().int().nonnegative().optional(),recoveryId:eS.optional(),workspaceKey:$,taskId:$.optional()}),
285187 O({zcode_type:N(`workspace-bridge-ready`),requestId:$,bridgeSessionId:eS,bridgeGeneration:w().int().nonnegative().optional(),recoveryId:eS.optional(),bridge:iS}),
285349 O({zcode_type:N(`workspace-bridge-error`),requestId:$,bridgeSessionId:eS.optional(),bridgeGeneration:w().int().nonnegative().optional(),recoveryId:eS.optional(),reason:rS,error:S()}),
285532 O({zcode_type:N(`workspace-reconnect-request`),requestId:$,workspaceKey:$}),
285608 O({zcode_type:N(`workspace-reconnect-response`),requestId:$,workspaceKey:$,success:N(!0)}),
285699 O({zcode_type:N(`workspace-reconnect-response`),requestId:$,workspaceKey:$,success:N(!1),error:S()}),
285800 O({zcode_type:N(`mobile-view-state-update`),viewState:aS,deviceInfo:oS.optional()}),
285884 O({zcode_type:N(`platform-request`),requestId:$,method:lS,args:E().optional()}),
285964 O({zcode_type:N(`platform-response`),requestId:$,method:lS,success:N(!0),result:E()}),
286050 O({zcode_type:N(`platform-response`),requestId:$,method:lS,success:N(!1),error:S()}),
   —   Dd,          // = rpc-frame      @129,158
   —   Od,          // = rpc-frame-ack  @129,854
286141 O({zcode_type:N(`bridge-degraded`),bridgeSessionId:eS,bridgeGeneration:w().int().nonnegative().optional(),recoveryId:eS.optional(),reason:M([`rpc-transport-fault`,`rpc-frame-gap`,`buffer-overflow`,`buffer-timeout`]),seq:w().int().nonnegative().optional(),expectedSeq:w().int().nonnegative().optional(),droppedCount:w().int().nonnegative().optional()}),
286493 O({zcode_type:N(`app-error`),requestId:$.optional(),bridgeSessionId:eS.optional(),reason:rS,error:S()}),
286597 O({zcode_type:N(`mobile-diagnostic`),event:M([`state-transition`,`socket-close`,`socket-error`,`recover-start`,`recover-scheduled`,`pair-status`,`failure`]),timestamp:w().int().nonnegative(),state:S().optional(),previousState:S().optional(),pairStatus:M([`waiting`,`matched`]).optional(),closeCode:w().int().optional(),closeReason:S().optional(),wasClean:T().optional(),wasPaired:T().optional(),failureReason:rS.optional(),failureMessage:S().optional(),visibilityState:S().optional(),online:T().optional(),hiddenDurationMs:w().int().nonnegative().optional()})
])
```

> 共 **21 个臂 / 19 个不同 `zcode_type` 字面量**（`workspace-reconnect-response` 与 `platform-response`
> 各占 2 臂，靠 `success` 字面量区分）。**一个都不缺**——本文已逐臂建表（§2.2）。

**zod 工厂别名反解表**（本模块是 zod 内核内联，别名是文件内 `function` 声明，连偏移给出以便复核）：

| 别名 | 真身 | 定义 off | 证据片段 |
| --- | --- | --- | --- |
| `S` | `z.string()` | 87,801 | `function S(e){return Vi(Fs,e)}`（ZodString 族） |
| `w` | `z.number()` | 90,145 | `function w(e){return ma(cc,e)}` |
| `T` | `z.boolean()` | 90,372 | `function T(e){return _a(dc,e)}`，`dc=g('ZodBoolean'…)` |
| `E` | `z.unknown()` | 90,889 | `function E(){return xa(vc)}`，`vc=g('ZodUnknown'…)` @90,600 |
| `D` | `z.array()` | 91,680 | `function D(e,t){return Wa(Cc,e,t)}` |
| `O` | `z.object()`（**默认 strip**） | 92,512 | `function O(e,t){return new wc({type:'object',shape:e??{},...m(t)})}` |
| `k` | `z.union()` | 92,855 | `function k(e,t){return new Dc({type:'union',options:e,...m(t)})}` |
| `A` | `z.discriminatedUnion()` | 92,988 | `function A(e,t,n){return new Oc({type:'union',options:t,discriminator:e,...m(n)})}` |
| `j` | `z.record()` | 93,398 | `function j(e,t,n){…}` |
| `M` | `z.enum()` | 94,165 | `function M(e,t){return new Nc({type:'enum',entries:…})}` |
| `N` | `z.literal()` | 94,583 | `function N(e,t){return new Pc({type:'literal',values:…})}` |
| `$` | `S().trim().min(1)` | 282,127 | `var $=S().trim().min(1),eS=$.max(Uu.transportEnvelopeIdMaxChars).regex(…)` |
| `Sl` | 遥测事件对象（全 strict） | 100,400 | 见 §2.1 |
| `rS` | 失败原因枚举（11 值） | 283,087 | 见 §4.1 |
| `iS` | bridge 判别联合（`kind`） | 283,340 | 见 §2.1 |
| `aS` | `viewState` | 283,744 | 见 §2.1 |
| `oS` | `deviceInfo` | 283,833 | 见 §2.1 |
| `sS` | `bootstrap-response.result` | 284,202 | 见 §2.1 |
| `cS` | `workspace-list-*.result` | 284,350 | 见 §2.1 |
| `lS` | platform method 枚举（7 值） | 284,450 | 见 §4.5 |
| `Dd`/`Od` | `rpc-frame`/`rpc-frame-ack` | 129,158 / 129,854 | 见 §2.20/§2.21 |
| `kd` | `k([Dd,Od])` | 129,993 | `data` 封套的 payload |

> **与旧基线的关键差异**：2026-09-20 基线里 `O` 是 union、`D` 是 object、union 名是 `mg`（off 208,773）。
> 3.15.3 里 `O` = **object**、`k` = union、union 名是 `uS`。**照抄旧稿的符号名会全错。**

### 2.1 复用子 schema 原文（本文引用它们，叶子字段表见 14/17 篇）

```js
// off 100,400  Sl —— telemetry-report.event（三层全 .strict()）
Sl = O({elementName:N(`session_create`), eventRegion:N(`app`), eventType:N(`result`),
        talkId:S().min(1).max(512), messageId:S().min(1).max(512),
        context:O({clientTimezone:S().max(128),clientLanguage:S().max(128),screenResolution:S().max(64)}).strict(),
        eventExtraDetail:O({create_source:M([`group`,`project`,`session`]), client_kind:N(`mobile`),
                            workspace_kind:M([`local`,`remote`]),
                            remote_kind:M([``,`ssh`,`wsl`,`docker`,`server`])}).strict()}).strict()
// off 100,845  Cl —— 桌面端变体（同构 extend，客户端不会发）
Cl = Sl.extend({eventExtraDetail: Sl.shape.eventExtraDetail.extend({
        create_source:M([`automation_idle`,`automation_scheduled`]), client_kind:N(`desktop`)})})

// off 283,340  iS —— workspace-bridge-ready.bridge（按 kind 判别）
iS = A(`kind`,[
  O({bridgeSessionId:eS, bridgeGeneration:w().int().nonnegative().optional(), recoveryId:eS.optional(),
     kind:N(`local`),  workspaceKey:$, workspacePath:$, initialTaskId:$.optional()}),
  O({bridgeSessionId:eS, bridgeGeneration:w().int().nonnegative().optional(), recoveryId:eS.optional(),
     kind:N(`remote`), workspaceKey:$, workspacePath:$, workspaceIdentity:$, remoteSessionId:$, initialTaskId:$.optional()})])

// off 283,744  aS —— viewState
aS = O({activeWorkspaceKey:$.optional(), activeTaskId:$.optional(), updatedAt:w().finite()})

// off 283,833  oS —— deviceInfo
oS = O({platform:$, version:$, name:$, userAgent:S().optional(), language:S().optional(),
        languages:D(S()).optional(), browserPlatform:S().optional(),
        viewport:O({width:w().finite(),height:w().finite(),devicePixelRatio:w().finite()}).optional(),
        screen:O({width:w().finite(),height:w().finite()}).optional(),
        timezone:S().optional(), online:T().optional(), updatedAt:w().finite()})

// off 284,202  sS —— bootstrap-response.result
sS = O({windowControlSessionId:$, desktopAppVersion:$.optional(), workspaces:tS, tasks:nS,
        initialViewState:aS.optional(), mobileViewState:aS.optional()})

// off 284,350  cS —— workspace-list-response/updated.result
cS = O({workspaces:tS, tasks:nS.optional(), activeWorkspaceKey:$.optional(), activeTaskId:$.optional()})

// off 282,217 / 282,649 —— 两个数组（叶子字段见 14 篇 §3/§4、17 篇）
tS = D(O({workspacePath:$, workspaceIdentity:$.optional(), remoteSessionId:$.optional(), label:$,
          workspacePurpose:M([`project`,`conversation`]).optional(), kind:M([`local`,`remote`]),
          connectionState:M([`connected`,`disconnected`,`reconnecting`]).optional(),
          lastConnectionError:S().optional()}))
nS = D(O({taskId:$, title:S(), workspacePath:$, workspaceIdentity:$.optional(), remoteSessionId:$.optional(),
          workspaceLabel:$, workspaceKind:M([`local`,`remote`]), createdAt:w().finite(), updatedAt:w().finite(),
          provider:Ou.optional(), unreadAt:w().finite().optional(),
          displayStatus:M([`idle`,`running`,`completed`,`error`]).optional(),
          hasBackgroundWork:T().optional(), workflowActivity:ud.optional(),
          pinned:T().optional(), archived:T().optional()}))
```

### 2.2 逐帧表（按 union 内出现顺序）

> 表内"必填"列：✅=必填，❌=可选（`.optional()`）。"方向"依据主包**发送点 / 接收匹配点**的实测偏移，
> 未找到对向证据的标 ⚠️ 推测。

#### 1) `telemetry-report` — off 284,627 — 🆕 3.15.3 新增 — 客户端 → 桌面端

```js
O({zcode_type:N(`telemetry-report`),event:Sl}).strict()
```

| 键 | 类型 | 必填 | 约束/枚举 |
| --- | --- | --- | --- |
| `zcode_type` | literal | ✅ | `'telemetry-report'` |
| `event` | `Sl`（§2.1） | ✅ | 三层全 strict；`elementName='session_create'`、`eventRegion='app'`、`eventType='result'` 均为**字面量常量**；`talkId`/`messageId` 长度 1–512；`create_source∈{group,project,session}`；`client_kind='mobile'`；`workspace_kind∈{local,remote}`；`remote_kind∈{'',ssh,wsl,docker,server}` |

**方向证据**：主包 off **6,581,897** / **6,583,202**（页面 API `reportTelemetryEvent`）：
```js
reportTelemetryEvent:e=>{let t=Ue.safeParse(e);t.success&&j.sendPayload({zcode_type:`telemetry-report`,event:t.data})}
```
其中 `Ue` 由 validation 模块导出别名 `ir` 导入（`Sl as ir` → 主包 `ir as Ue`），即 `Sl`。
**注意 `t.success&&` 的短路**：schema 校验失败 ⇒ **静默不发**（不报错、不重试）。
这是唯一一个"多一个键就整帧丢弃且无任何日志"的帧。

#### 2) `bootstrap-request` — off 284,683 — 客户端 → 桌面端

| 键 | 类型 | 必填 | 约束 |
| --- | --- | --- | --- |
| `zcode_type` | literal | ✅ | `'bootstrap-request'` |
| `requestId` | `$` | ✅ | trim 非空 |

方向证据：主包 off **6,579,049**
`(await N({zcode_type:'bootstrap-request',requestId:e},t=>t.zcode_type==='bootstrap-response'&&t.requestId===e)).result`
⇒ **响应必须回同一个 `requestId`**，否则请求悬挂。

#### 3) `bootstrap-response` — off 284,734 — 桌面端 → 客户端

| 键 | 类型 | 必填 | 约束 |
| --- | --- | --- | --- |
| `zcode_type` | literal | ✅ | `'bootstrap-response'` |
| `requestId` | `$` | ✅ | 必须等于请求的 `requestId` |
| `success` | `N(!0)` | ✅ | **字面量 `true`**，不能是别的真值 |
| `result` | `sS` | ✅ | `windowControlSessionId` **必填**；`workspaces`/`tasks` 必填；`initialViewState?`/`mobileViewState?`/`desktopAppVersion?` 可选 |

> 叶子字段表：**14 篇**（`workspaces` 8 键 / `tasks` 14 键 / `initialViewState` / `mobileViewState`）；
> 合并总表与未解析项：**17 篇**。

#### 4) `workspace-list-request` — off 284,810 — 客户端 → 桌面端

| 键 | 类型 | 必填 |
| --- | --- | --- |
| `zcode_type` | literal | ✅ `'workspace-list-request'` |
| `requestId` | `$` | ✅ |

方向证据：主包 off **6,579,674**。

#### 5) `workspace-list-response` — off 284,866 — 桌面端 → 客户端

| 键 | 类型 | 必填 | 约束 |
| --- | --- | --- | --- |
| `zcode_type` | literal | ✅ | `'workspace-list-response'` |
| `requestId` | `$` | ✅ | |
| `success` | `N(!0)` | ✅ | 字面量 `true` |
| `result` | `cS` | ✅ | `workspaces` 必填；`tasks?`/`activeWorkspaceKey?`/`activeTaskId?` 可选 |

#### 6) `workspace-list-updated` — off 284,947 — 桌面端 → 客户端（**推送，无 `requestId`**）

| 键 | 类型 | 必填 | 约束 |
| --- | --- | --- | --- |
| `zcode_type` | literal | ✅ | `'workspace-list-updated'` |
| `result` | `cS` | ✅ | 同 §2.5 的 `result` |

方向/语义证据：主包 off **6,578,057**（`onPayload` 分支）
`if(e.zcode_type==='workspace-list-updated'){S(e.result);return}` ⇒ **整份替换**，
且**没有 `requestId`**（无法与请求配对，纯推送）。

#### 7) `workspace-bridge-open` — off 285,001 — 客户端 → 桌面端

| 键 | 类型 | 必填 | 约束 |
| --- | --- | --- | --- |
| `zcode_type` | literal | ✅ | `'workspace-bridge-open'` |
| `requestId` | `$` | ✅ | |
| `bridgeSessionId` | `eS` | ✅ | trim 后 1–256，字符集 `[A-Za-z0-9._~-]` |
| `bridgeGeneration` | int | ❌ | ≥ 0，无上限 |
| `recoveryId` | `eS` | ❌ | 恢复通路时带 |
| `workspaceKey` | `$` | ✅ | trim 非空 |
| `taskId` | `$` | ❌ | 指定初始任务 |

方向证据：主包 off **6,580,394**：
```js
V=async(e,t)=>{G9=!0;try{let n=X9(`bridge`),r=++u,i=await P(()=>N({zcode_type:`workspace-bridge-open`,
  requestId:X9(`workspace-bridge`),bridgeSessionId:n,bridgeGeneration:r,...l?{recoveryId:l}:{},
  workspaceKey:e,...t?.taskId?{taskId:t.taskId}:{}},e=>e.zcode_type===`workspace-bridge-ready`&&e.bridgeSessionId===n))
```
⇒ 等待条件是 **`zcode_type==='workspace-bridge-ready'` 且 `bridgeSessionId` 相同**（不是 `requestId`！）。

#### 8) `workspace-bridge-ready` — off 285,187 — 桌面端 → 客户端

| 键 | 类型 | 必填 | 约束 |
| --- | --- | --- | --- |
| `zcode_type` | literal | ✅ | `'workspace-bridge-ready'` |
| `requestId` | `$` | ✅ | |
| `bridgeSessionId` | `eS` | ✅ | 必须与请求一致（页面按它匹配） |
| `bridgeGeneration` | int | ❌ | ≥ 0 |
| `recoveryId` | `eS` | ❌ | |
| `bridge` | `iS` | ✅ | 按 `kind` 判别：`local` → 需 `workspaceKey`+`workspacePath`；`remote` → 再需 `workspaceIdentity`+`remoteSessionId`；两臂都可带 `initialTaskId?` |

#### 9) `workspace-bridge-error` — off 285,349 — 桌面端 → 客户端

| 键 | 类型 | 必填 | 约束 |
| --- | --- | --- | --- |
| `zcode_type` | literal | ✅ | `'workspace-bridge-error'` |
| `requestId` | `$` | ✅ | |
| `bridgeSessionId` | `eS` | **❌ 可选** | 开桥失败时可能没有桥 id |
| `bridgeGeneration` | int | ❌ | ≥ 0 |
| `recoveryId` | `eS` | ❌ | |
| `reason` | `rS` | ✅ | 11 值，见 §4.1 |
| `error` | `S()` | ✅ | 任意字符串 |

**消费语义**（主包 off **6,535,739**，`acceptPayload`）：若带 `requestId`，**先用于 reject 挂起的请求**
（`z9(e.reason,e.error)` 包成 `{reason,message}`）；主包 off **6,578,241** 另有一路
**按 `bridgeSessionId` 过滤 + `markDegraded()`**。

#### 10) `workspace-reconnect-request` — off 285,532 — 客户端 → 桌面端

| 键 | 类型 | 必填 |
| --- | --- | --- |
| `zcode_type` | literal | ✅ `'workspace-reconnect-request'` |
| `requestId` | `$` | ✅ |
| `workspaceKey` | `$` | ✅ |

方向证据：主包 off **6,580,117**。

#### 11) `workspace-reconnect-response`（成功臂） — off 285,608 — 桌面端 → 客户端

| 键 | 类型 | 必填 | 约束 |
| --- | --- | --- | --- |
| `zcode_type` | literal | ✅ | `'workspace-reconnect-response'` |
| `requestId` | `$` | ✅ | |
| `workspaceKey` | `$` | ✅ | |
| `success` | `N(!0)` | ✅ | 字面量 `true` |

#### 12) `workspace-reconnect-response`（失败臂） — off 285,699 — 桌面端 → 客户端

| 键 | 类型 | 必填 | 约束 |
| --- | --- | --- | --- |
| `zcode_type` | literal | ✅ | `'workspace-reconnect-response'` |
| `requestId` | `$` | ✅ | |
| `workspaceKey` | `$` | ✅ | |
| `success` | `N(!1)` | ✅ | **字面量 `false`** |
| `error` | `S()` | ✅ | |

> ⚠️ **同一 `zcode_type` 的两臂，靠 `success` 字面量区分**。判别联合 `uS` 是 `k([...])`（普通 union，
> 不是 discriminatedUnion），所以**两臂都参与试解析**——失败臂必须带 `error`，成功臂**必须不带**（否则被 strip，不影响）。

#### 13) `mobile-view-state-update` — off 285,800 — 客户端 → 桌面端

| 键 | 类型 | 必填 | 约束 |
| --- | --- | --- | --- |
| `zcode_type` | literal | ✅ | `'mobile-view-state-update'` |
| `viewState` | `aS` | ✅ | `updatedAt` **必填**（`w().finite()`）；`activeWorkspaceKey?`/`activeTaskId?` 可选 |
| `deviceInfo` | `oS` | ❌ | `platform`/`version`/`name`/`updatedAt` 必填（在 `oS` 内部），其余可选 |

方向证据：主包 off **6,579,881**
`j.sendPayload({zcode_type:'mobile-view-state-update',viewState:{activeWorkspaceKey:e,...n?{activeTaskId:n}:{},updatedAt:Date.now()}})`
⇒ 页面发送时**只带 `viewState`，不带 `deviceInfo`**。

> ⚠️ **缺 `viewState.updatedAt` ⇒ 整帧非法**（`aS` 里它是必填）。这是本族最常见的失败原因。

#### 14) `platform-request` — off 285,884 — 客户端 → 桌面端

| 键 | 类型 | 必填 | 约束 |
| --- | --- | --- | --- |
| `zcode_type` | literal | ✅ | `'platform-request'` |
| `requestId` | `$` | ✅ | |
| `method` | `lS` | ✅ | 7 值，见 §4.5 |
| `args` | `E()` = unknown | ❌ | 无任何约束（任意 JSON） |

方向证据：主包 off **6,579,426**。

#### 15) `platform-response`（成功臂） — off 285,964 — 桌面端 → 客户端

| 键 | 类型 | 必填 | 约束 |
| --- | --- | --- | --- |
| `zcode_type` | literal | ✅ | `'platform-response'` |
| `requestId` | `$` | ✅ | |
| `method` | `lS` | ✅ | **响应也必填 `method`**（与旧基线一致，容易漏） |
| `success` | `N(!0)` | ✅ | 字面量 `true` |
| `result` | `E()` = unknown | ✅ | 无约束 |

#### 16) `platform-response`（失败臂） — off 286,050 — 桌面端 → 客户端

| 键 | 类型 | 必填 | 约束 |
| --- | --- | --- | --- |
| `zcode_type` | literal | ✅ | `'platform-response'` |
| `requestId` | `$` | ✅ | |
| `method` | `lS` | ✅ | |
| `success` | `N(!1)` | ✅ | 字面量 `false` |
| `error` | `S()` | ✅ | |

#### 17) `bridge-degraded` — off 286,141 — 桌面端 → 客户端

| 键 | 类型 | 必填 | 约束 |
| --- | --- | --- | --- |
| `zcode_type` | literal | ✅ | `'bridge-degraded'` |
| `bridgeSessionId` | `eS` | ✅ | 页面按它过滤 |
| `bridgeGeneration` | int | ❌ | ≥ 0 |
| `recoveryId` | `eS` | ❌ | |
| `reason` | enum | ✅ | `rpc-transport-fault` \| `rpc-frame-gap` \| `buffer-overflow` \| `buffer-timeout` |
| `seq` | int | ❌ | ≥ 0 |
| `expectedSeq` | int | ❌ | ≥ 0 |
| `droppedCount` | int | ❌ | ≥ 0 |

消费语义（主包 off **6,578,057–6,578,241**）：
```js
if(e.zcode_type==='bridge-degraded'){e.bridgeSessionId===g?.getBridgeSessionId()&&(g?.markDegraded(),A(e.reason));return}
```
⇒ **不是自己的桥就整个忽略**；是自己的桥则 `markDegraded()` + 走恢复。

#### 18) `app-error` — off 286,493 — 桌面端 → 客户端

| 键 | 类型 | 必填 | 约束 |
| --- | --- | --- | --- |
| `zcode_type` | literal | ✅ | `'app-error'` |
| `requestId` | `$` | **❌ 可选** | 带 `requestId` 时用于 reject 挂起请求 |
| `bridgeSessionId` | `eS` | ❌ | |
| `reason` | `rS` | ✅ | 11 值，见 §4.1 |
| `error` | `S()` | ✅ | |

消费语义（主包 off **6,578,366**）：`e.zcode_type==='app-error'&&E(q9(e.reason,e.error))` ⇒ 走**全局错误面**。

#### 19) `mobile-diagnostic` — off 286,597 — 客户端 → 桌面端

| 键 | 类型 | 必填 | 约束 |
| --- | --- | --- | --- |
| `zcode_type` | literal | ✅ | `'mobile-diagnostic'` |
| `event` | enum | ✅ | 7 值，见 §4.3 |
| `timestamp` | int | ✅ | ≥ 0 |
| `state` | `S()` | ❌ | 状态机当前态 |
| `previousState` | `S()` | ❌ | |
| `pairStatus` | enum | ❌ | `waiting` \| `matched` |
| `closeCode` | int | ❌ | **无 nonnegative 约束**（可为负/0） |
| `closeReason` | `S()` | ❌ | |
| `wasClean` | boolean | ❌ | |
| `wasPaired` | boolean | ❌ | |
| `failureReason` | `rS` | ❌ | 11 值 |
| `failureMessage` | `S()` | ❌ | |
| `visibilityState` | `S()` | ❌ | |
| `online` | boolean | ❌ | |
| `hiddenDurationMs` | int | ❌ | ≥ 0 |

方向证据：主包 off **6,576,126**（状态变化上报）与 **6,576,574**（失败上报）。

#### 20) `rpc-frame` — off 129,158（`Dd`） — **双向** — `.strict()` + 2 条 superRefine

```js
Dd = O({zcode_type:N(`rpc-frame`), bridgeSessionId:Cd, bridgeGeneration:Sd.optional(), recoveryId:Cd.optional(),
        seq:xd, messageSeq:xd,
        fragmentIndex:w().int().nonnegative().max(bd.maxFragments-1),
        fragmentCount:w().int().positive().max(bd.maxFragments),
        messageBytes:w().int().positive().max(bd.maxMessageBytes),
        checksum:Td, dataBase64:S().min(4).max(bd.maxPhysicalFrameBytes).refine(Ed)})
     .strict()
     .superRefine((e,t)=>{ e.fragmentIndex>=e.fragmentCount && t.addIssue({code:`custom`,path:[`fragmentIndex`],
                            message:`fragmentIndex must be smaller than fragmentCount`}),
                          e.fragmentCount>e.messageBytes && t.addIssue({code:`custom`,path:[`fragmentCount`],
                            message:`non-empty fragments cannot exceed message bytes`}) })
// Td @128,954 = O({algorithm:N(`crc32`),value:S().regex(/^[0-9a-f]{8}$/u)}).strict()
// xd @128,713 = w().int().positive().max(2**53-1)
```

| 键 | 类型 | 必填 | 约束 |
| --- | --- | --- | --- |
| `zcode_type` | literal | ✅ | `'rpc-frame'` |
| `bridgeSessionId` | `Cd` | ✅ | 1–256，`[A-Za-z0-9._~-]`，**不 trim** |
| `bridgeGeneration` | int | ❌ | 0 … 2^53−1 |
| `recoveryId` | `Cd` | ❌ | |
| `seq` | int | ✅ | **> 0**，≤ 2^53−1 |
| `messageSeq` | int | ✅ | **> 0**，≤ 2^53−1 |
| `fragmentIndex` | int | ✅ | 0 … **63**（`maxFragments−1`） |
| `fragmentCount` | int | ✅ | 1 … **64** |
| `messageBytes` | int | ✅ | 1 … **16 MiB** |
| `checksum` | `Td`（strict） | ✅ | `{algorithm:'crc32', value:/^[0-9a-f]{8}$/}` |
| `dataBase64` | string | ✅ | 长度 **4 … 1 MiB**；`refine(Ed)` 校验"base64 解码后 crc32 == value 且字节数 > 0" |

`Ed` @129,026：`if(e.length<4||e.length>bd.maxPhysicalFrameBytes)return!1;let t=yd(e);return t!==null&&t.byteLength>0&&vd(t)===e`
（`yd` = base64 解码，`vd` = crc32 十六进制）。

#### 21) `rpc-frame-ack` — off 129,854（`Od`） — **双向** — `.strict()`

| 键 | 类型 | 必填 | 约束 |
| --- | --- | --- | --- |
| `zcode_type` | literal | ✅ | `'rpc-frame-ack'` |
| `bridgeSessionId` | `Cd` | ✅ | 1–256，`[A-Za-z0-9._~-]` |
| `bridgeGeneration` | int | ❌ | 0 … 2^53−1 |
| `recoveryId` | `Cd` | ❌ | |
| `ackMessageSeq` | `xd` | ✅ | **> 0**，≤ 2^53−1 |

入站处理（主包 off **6,479,781**）：
```js
if(t.zcode_type===`rpc-frame-ack`){this.processAck(t.ackMessageSeq);return}
```
出站构造（主包 off **6,478,374**）：
```js
if(!this.invokeSend({zcode_type:`rpc-frame-ack`,...this.identity,ackMessageSeq:t})||this.degraded||this.disposed){e=!1;break}
```
⇒ **ack 必须带身份三元组**（`bridgeSessionId` 在 schema 里必填），与旧基线一致。

---

## 3. `.strict()` 逐帧标注与"多键 / 缺键"后果

### 3.1 全模块 strict 规模

| 项 | 3.15.3（`validation-CqXZOLXO.js`） | 2026-09-20 基线（`src-wmk2orCZ.js`） |
| --- | --- | --- |
| `.strict()` 出现次数 | **766** | 571 |

### 3.2 逐帧标注

| 帧 / 构件 | `.strict()` | 后果：多一个键 | 后果：缺一个必填键 |
| --- | --- | --- | --- |
| `data` 传输封套（off 130,007） | ✅ | `unrecognized_keys` ⇒ 整封套非法 | `invalid_type` ⇒ 非法 |
| `rpc-frame`（`Dd` 129,158） | ✅ | 同上 | 同上 |
| `rpc-frame-ack`（`Od` 129,854） | ✅ | 同上 | 同上 |
| `telemetry-report`（284,627） | ✅ | **整帧丢弃，静默**（`t.success&&` 短路，连日志都没有） | 同上 |
| `Sl` / `Cl`（100,400 / 100,845）及其 `context`/`eventExtraDetail` | ✅（三层） | 同上 | 同上 |
| `wd` 身份三元组（128,862）、`Td` 校验和（128,954） | ✅ | 同上 | 同上 |
| **其余 18 个业务臂**（bootstrap-\* / workspace-\* / mobile-\* / platform-\* / bridge-degraded / app-error） | ❌ | **静默剥离**（zod 默认 strip），帧仍合法 | **整帧非法** ⇒ `dS` 返回 `null` ⇒ 帧被丢弃 |

**结论（对 14 篇 §1.4 的复核）**：14 篇原文的判定"bootstrap 族对象全是裸 `D({…})`，一个 `.strict()` 都没有，
非 strict 默认 strip、多键被静默剥离、但**非 strict 只救'多键'不救'缺键'**"——在 3.15.3 上
**逐条成立**，且该结论可推广到 `uS` 的全部 18 个非 strict 臂。**唯一需要补的新事实**是：
3.15.3 新增的 `telemetry-report` 是 strict 的，所以**"业务帧都不 strict"这个概括现在不成立**。

### 3.3 失败后的行为（谁来丢帧）

- schema 侧统一入口是 `dS`（off **287,156**）：`function dS(e){let t=uS.safeParse(e);return t.success?t.data:null}`
  ⇒ 失败返回 `null`，**不抛错、不上报**。
- 主包把它按导出名 `t` 导入为局部名 `dt`（validation 导出表 `dS as t` @305,622 → 主包导入表 `t as dt` @60,433），
  它是**入站业务帧的唯一入口**（主包 off **6,528,400**）：
  ```js
  …U3n(e)&&this.options.onRawTransportPayload?.(e))return;let t=dt(e);t&&this.options.onPayload(t)
  ```
  ⇒ 先按传输判别 `U3n` 分流 `rpc-frame` 族，其余交给 `dt(e)`；**`dt` 返回 `null` 时 `t&&…` 短路，帧被静默丢弃**。
- 因此：**帧级 schema 校验失败在链路上是"静默丢弃"**。壳侧要观测它，只能靠"请求超时"（`acceptPayload` 的
  pending 请求不会 resolve）反推，没有现成的错误帧。
- 例外：`reportTelemetryEvent`（off 6,581,897）在**发送前**就 `safeParse`，失败即不发（§2.1）。

---

## 4. 错误与 reason 枚举全集

### 4.1 `rS` —— 业务失败原因（off 283,087），`workspace-bridge-error` / `app-error` / `mobile-diagnostic.failureReason` 共用

```js
rS = M([`session-not-found`,`session-expired`,`session-conflict`,`workspace-closed`,`desktop-disconnected`,
        `invalid-mobile-connection`,`desktop-bootstrap-timeout`,`connection-recovery-timeout`,`relay-unavailable`,
        `unsupported-action`,`unexpected-error`])
```

**11 值**。与旧基线（`sg` @207,277）逐值一致，**无增删**。

### 4.2 `bridge-degraded.reason`（off 286,141 内联）—— 4 值

```js
M([`rpc-transport-fault`,`rpc-frame-gap`,`buffer-overflow`,`buffer-timeout`])
```

### 4.3 `mobile-diagnostic.event`（off 286,597 内联）—— 7 值

```js
M([`state-transition`,`socket-close`,`socket-error`,`recover-start`,`recover-scheduled`,`pair-status`,`failure`])
```

`pairStatus` 子枚举：`M(['waiting','matched'])`（2 值）。二者与旧基线一致。

### 4.4 relay 顶层 `error.code`（主包 off **6,527,700**，`handleRelayError`）

| `code` | 处置（原文摘要） | 是否终态 |
| --- | --- | --- |
| `KICKED` | `setState('kicked')` + `enterTerminalFailure(z9('session-conflict', msg\|\|code))` | **终态，不重连** |
| `DEVICE_OFFLINE` | `recoverFromDeviceOffline(msg\|\|code)` | 可恢复 |
| `AUTH_FAILED` \| `WRONG_PARAM` | `enterTerminalFailure(z9('invalid-mobile-connection', …))` | 终态 |
| `INTERNAL` | `state==='paired'`（或 `hadPairedSession && state==='waiting'`）⇒ `enterWaitingForDeviceAfterRelayError`；否则 `recoverFromRelayInternal` | 视状态 |
| 其他 | `enterTerminalFailure(z9('relay-unavailable', …))` | 终态 |

`z9`（主包 off **6,521,346**）= `function z9(e,t){return{reason:e,message:t}}` —— 把 relay code 归一到 `rS` 词表。

### 4.5 `lS` —— platform method（off 284,450）—— 7 值

```js
lS = M([`isDockerAvailable`,`listWSLDistros`,`listDockerContainers`,`listSSHConfigAliases`,
        `loadMcpFromUserDirectory`,`saveMcpToUserDirectory`,`migrateLegacyCommonMcp`])
```

与旧基线（`pg` @208,605）**逐值一致，7 值无增删**。**注意：`method` 在 `platform-response` 里也是必填。**

### 4.6 客户端自身降级原因码 `enterDegraded({reasonCode})`（主包 11 处 relay 域 + 非 relay 域）

| `reasonCode` | 主包 off |
| --- | --- |
| `remote.rpcFrame.ackGraceExceeded` | 6,472,953 |
| `remote.rpcFrame.replayGraceExceeded` | 6,473,080 |
| `remote.rpcFrame.manuallyDegraded` | 6,476,605（`markDegraded` 的默认值） |
| `remote.rpcFrame.encodingFailed` | 6,477,047 |
| `remote.rpcFrame.outerMeterFailed` | 6,477,182 |
| `remote.rpcFrame.replayBufferExceeded` | 6,477,330 |
| `remote.rpcFrame.sendFailed` | 6,478,000 |
| `remote.rpcFrame.invalidPayload` | 6,479,723 |
| `remote.rpcFrame.deliveryFailed` | 6,480,236 |
| `remote.rpcFrame.futureAck` | 6,480,715 |
| `remote.rpcFrame.envelopeTooLarge` | 6,526,246（`onRawTransportFault`）/ 6,581,035（发送前 oversize 判定） |

非 relay 域（同函数复用）：`attachment_not_ready`（2,306,427）、`no-session`（3,662,183）、
`render_timeout`（1,308,155）、`composer_error`（2,308,287）、`blocked`（1,170,983 等）、
`workspace_hooks_*`（2,652,501 起）。

> **相对旧基线无增删**：旧主包 `index-B-ilXaCQ.js` 里同样有这 11 个码（`manuallyDegraded` @4,710,716、
> `envelopeTooLarge` @4,760,352 / 4,811,484 两处），新旧一一对应。
> **需要修正的是 16 篇旧稿**：它只列了 9 个（漏 `manuallyDegraded` 与 `envelopeTooLarge`）。
> 另：`envelopeTooLarge` 在两版构建里都是**两处**——入站 `onRawTransportFault`（@6,526,246）
> 与出站发送前 `sendPayloadResult` 的 `kind==='oversize'` 判定（@6,581,035）。

### 4.7 其它帧内枚举（非错误，但实现者必读）

| 枚举 | 值 | off |
| --- | --- | --- |
| `iS.kind`（bridge 判别键） | `local` \| `remote` | 283,340 |
| `tS[i].workspacePurpose` | `project` \| `conversation` | 282,217 |
| `tS[i].kind` | `local` \| `remote` | 282,217 |
| `tS[i].connectionState` | `connected` \| `disconnected` \| `reconnecting` | 282,217 |
| `nS[i].displayStatus` | `idle` \| `running` \| `completed` \| `error` | 282,649 |
| `Sl.eventExtraDetail.remote_kind` | `''`（空串）\| `ssh` \| `wsl` \| `docker` \| `server` | 100,400 |

---

## 5. 实现者速查表

### 5.1 帧 → 必填字段 → 常见失败原因

| `zcode_type` | 方向 | 必填（除 `zcode_type`） | strict | 最常见失败原因 |
| --- | --- | --- | --- | --- |
| `telemetry-report` | 客户端→桌面端 | `event`（含其内部 `elementName`/`eventRegion`/`eventType`/`talkId`/`messageId`/`context.*`/`eventExtraDetail.*` 全部必填） | ✅ | 多任一键即**静默丢弃**；`talkId`/`messageId` 空或 >512 |
| `bootstrap-request` | 客户端→桌面端 | `requestId` | ❌ | `requestId` 为空串或全空白 |
| `bootstrap-response` | 桌面端→客户端 | `requestId`,`success:true`,`result`（含 `windowControlSessionId`,`workspaces`,`tasks`） | ❌ | `result` 缺 `windowControlSessionId`；`workspaces`/`tasks` 缺 |
| `workspace-list-request` | 客户端→桌面端 | `requestId` | ❌ | —— |
| `workspace-list-response` | 桌面端→客户端 | `requestId`,`success:true`,`result.workspaces` | ❌ | `result` 缺 `workspaces` |
| `workspace-list-updated` | 桌面端→客户端 | `result.workspaces` | ❌ | **误加 `requestId`**（会被 strip，无害）；缺 `workspaces` 则整帧丢 |
| `workspace-bridge-open` | 客户端→桌面端 | `requestId`,`bridgeSessionId`,`workspaceKey` | ❌ | `bridgeSessionId` 含 `[A-Za-z0-9._~-]` 之外字符（如 `:`/`/`）；`workspaceKey` 空 |
| `workspace-bridge-ready` | 桌面端→客户端 | `requestId`,`bridgeSessionId`,`bridge`（按 `kind` 分支字段） | ❌ | `kind:'remote'` 时漏 `workspaceIdentity`/`remoteSessionId`；`kind` 非 `local`/`remote` |
| `workspace-bridge-error` | 桌面端→客户端 | `requestId`,`reason`,`error` | ❌ | `reason` 不在 11 值内；`bridgeSessionId` **可缺**，别当必填 |
| `workspace-reconnect-request` | 客户端→桌面端 | `requestId`,`workspaceKey` | ❌ | —— |
| `workspace-reconnect-response` | 桌面端→客户端 | `requestId`,`workspaceKey`,`success` | ❌ | 失败臂漏 `error`；`success` 用了 `1`/`0`（必须是字面量 `true`/`false`） |
| `mobile-view-state-update` | 客户端→桌面端 | `viewState`（含 **`updatedAt`**） | ❌ | **漏 `viewState.updatedAt`** ⇒ 整帧非法（最高频） |
| `platform-request` | 客户端→桌面端 | `requestId`,`method` | ❌ | `method` 不在 7 值内 |
| `platform-response` | 桌面端→客户端 | `requestId`,`method`,`success`（+成功臂 `result` / 失败臂 `error`） | ❌ | **响应漏回 `method`** |
| `bridge-degraded` | 桌面端→客户端 | `bridgeSessionId`,`reason` | ❌ | `reason` 不在 4 值内；`bridgeSessionId` 不匹配 ⇒ 被整帧忽略 |
| `app-error` | 桌面端→客户端 | `reason`,`error` | ❌ | 漏 `error`；`reason` 不在 11 值内 |
| `mobile-diagnostic` | 客户端→桌面端 | `event`,`timestamp` | ❌ | 漏 `timestamp`；`event` 不在 7 值内；`failureReason` 不在 11 值内 |
| `rpc-frame` | 双向 | `bridgeSessionId`,`seq`,`messageSeq`,`fragmentIndex`,`fragmentCount`,`messageBytes`,`checksum`,`dataBase64` | ✅ | `seq`/`messageSeq` 为 0（需 **> 0**）；`fragmentIndex ≥ fragmentCount`；`fragmentCount > messageBytes`；`dataBase64` 长度 <4 或 >1 MiB；crc32 不匹配 |
| `rpc-frame-ack` | 双向 | `bridgeSessionId`,`ackMessageSeq` | ✅ | 漏 `bridgeSessionId`（schema 必填）；`ackMessageSeq` 为 0 |

### 5.2 数值约束一览（`bd` @128,552 / `Uu` @117,018）

| 常量 | 值 | 用在哪 |
| --- | --- | --- |
| `bd.maxPhysicalFrameBytes` = `Uu.maxFrameBytes` | 1 MiB (1,048,576) | `rpc-frame.dataBase64` 长度上限；`G2t(e) > maxPhysicalFrameBytes` ⇒ `envelopeTooLarge` |
| `bd.maxMessageBytes` | 16 MiB | `rpc-frame.messageBytes` 上限 |
| `bd.maxFragments` | **64** | `fragmentCount` 上限 / `fragmentIndex` 上限 = 63（**物理帧层**） |
| `bd.assemblyTimeoutMs` | 30,000 ms | 重组超时 |
| `bd.transportIdMaxChars` = `Uu.transportEnvelopeIdMaxChars` | **256** | `bridgeSessionId`/`recoveryId` 长度上限 |
| `Uu.logicalFrameAssemblyMaxFragments` | 1,024 | **逻辑帧层**（桥内 topic 流）分片上限，与 64 不同层 |
| `Uu.logicalFrameAssemblyMaxBytes` | 16 MiB | 逻辑帧总字节上限 |
| `Uu.logicalFrameAssemblyMaxConcurrent` | 32 | 并发重组数上限 |
| `Uu.logicalFrameAssemblyMaxStagedBytes` | 32 MiB | 暂存上限 |
| `Uu.logicalFrameAssemblyTimeoutMs` | 30,000 ms | 逻辑帧超时 |
| `Uu.subscriberBufferMaxOps` / `subscriberBufferMaxBytes` | 500 / 1 MiB | 订阅缓冲 |
| `Uu.eventRetentionPerSession` / `snapshotTailWindowRows` | 2,000 / 60 | 事件保留 / 快照尾窗 |
| `xd` 上限 | 2^53 − 1 | `seq`/`messageSeq`/`ackMessageSeq` |

---

## 6. 复现命令

```bash
cd "E:/Zcode_android/Reference_web/3.15.3/assets"

# ① 判别联合全部臂的字节偏移（19 字面量 / 21 臂）
node -e "const b=require('fs').readFileSync('validation-CqXZOLXO.js');const s=b.toString('latin1');
let i=0,k='zcode_type:N(';while((i=s.indexOf(k,i))>=0){console.log(i,s.substr(i,44));i+=k.length}"

# ② 联合定义窗口（含别名反解）
node -e "const b=require('fs').readFileSync('validation-CqXZOLXO.js');process.stdout.write(b.subarray(284400,286900).toString())"

# ③ strict 分布（766 处；业务臂里只有 telemetry-report 一处）
node -e "const s=require('fs').readFileSync('validation-CqXZOLXO.js').toString('latin1');
let i=0,c=0;while((i=s.indexOf('.strict()',i))>=0){c++;i+=9};console.log('strict() total',c)"
node -e "const s=require('fs').readFileSync('validation-CqXZOLXO.js').toString('latin1');
let i=0;while((i=s.indexOf('.strict()',284600))>=0&&i<287300){console.log(i);i+=9}"

# ④ rpc-frame / ack / data 封套
node -e "const b=require('fs').readFileSync('validation-CqXZOLXO.js');process.stdout.write(b.subarray(128700,130200).toString())"

# ⑤ 主包消费点（方向判定）
node -e "const s=require('fs').readFileSync('index-BGa3KiRe.js').toString('latin1');
let i=0;while((i=s.indexOf('zcode_type',i))>=0){console.log(i);i+=10}"
```

---

## 7. 出处与交叉引用

### 7.1 本文一手证据

| 结论 | 文件 | 字节偏移 |
| --- | --- | --- |
| 判别联合 `uS`（21 臂） | `assets/validation-CqXZOLXO.js` | 284,618 |
| 联合解析器 `dS`（失败返回 `null`） | 同上 | 287,156 |
| zod 别名反解（`S`/`w`/`T`/`E`/`D`/`O`/`k`/`A`/`j`/`M`/`N`） | 同上 | 87,801 / 90,145 / 90,372 / 90,889 / 91,680 / 92,512 / 92,855 / 92,988 / 93,398 / 94,165 / 94,583 |
| `$` / `eS` / `rS` / `iS` / `aS` / `oS` / `sS` / `cS` / `lS` | 同上 | 282,127 / 282,147 / 283,087 / 283,340 / 283,744 / 283,833 / 284,202 / 284,350 / 284,450 |
| `tS` / `nS`（数组，叶子见 14 篇） | 同上 | 282,217 / 282,649 |
| `Sl` / `Cl`（遥测事件） | 同上 | 100,400 / 100,845 |
| `Uu` / `bd` / `xd` / `Sd` / `Cd` / `wd` / `Td` | 同上 | 117,018 / 128,552 / 128,713 / 128,750 / 128,790 / 128,862 / 128,954 |
| `Dd` / `Od` / `kd` / `data` 封套 | 同上 | 129,158 / 129,854 / 129,993 / 130,007 |
| strict 报错点 `unrecognized_keys` | 同上 | 33,517 |
| 导出映射 `dS as t` / `Sl as ir` | 同上 | 305,622（export 语句） |
| 导入映射 `t as dt` / `ir as Ue` | `assets/index-BGa3KiRe.js` | 60,433（import 语句） |
| 传输判别 `u4n` / trace 判别 `U3n` | 同上 | 6,473,860 / 6,521,747 |
| trace 裁剪 `H3n` / `z9` | 同上 | 6,521,474 / 6,521,346 |
| `handleRelayError`（KICKED 等） | 同上 | 6,527,700 |
| `acceptPayload`（按 `requestId` reject） | 同上 | 6,535,739 |
| `onPayload` 分发 | 同上 | 6,577,943 |
| 入站帧解析调用点 `let t=dt(e);t&&…onPayload(t)` | 同上 | 6,528,400 |
| 各帧发送/匹配点 | 同上 | bootstrap 6,579,049 · platform 6,579,426 · workspace-list 6,579,674 · view-state 6,579,881 · reconnect 6,580,117 · bridge-open 6,580,394 · telemetry 6,581,897 |
| `mobile-diagnostic` 发送点 | 同上 | 6,576,126 / 6,576,574 |

### 7.2 相关篇目

[14 bootstrap 完整字段表](14-remote-v4-bootstrap载荷-完整字段表.md)（`workspaces`/`tasks`/`viewState` 叶子字段）·
[17 合并总表与未解析字段](17-remote-v4-bootstrap载荷-合并总表与未解析字段清单.md) ·
[16 relay 协议目录](16-remote-v4-relay协议目录.md)（relay 传输层与状态机）·
[13 壳侧解析了哪些](13-remote-v4-bootstrap载荷-我们解析了哪些.md) ·
[15 网页架构与模块地图](15-remote-v4-网页架构与模块地图.md) ·
[28 网页更新影响与重适配成本](28-remote-v4-网页更新影响-壳健壮性与重适配成本.md)。

### 7.3 桌面端交叉校验（只读，未写入本文结论）

`app.asar` 的 `out/main/chunk-OW6EUS2G.js` 与 `out/host/chunk-OUCOFHX4.js` 含同一份 zod schema 的同构副本
（`zcode_type` 各 22 次）；`out/main/index.js` 的入站分发器（`routePayload`）里出现
`case"mobile-view-state-update"` / `"workspace-bridge-open"` / `"workspace-reconnect-request"` /
`"rpc-frame"` / `"mobile-diagnostic"` / `"telemetry-report"` / `"platform-request"`。
⇒ **本文的必填/枚举约束对网页端与桌面端同时成立**（同一份 Zod）。逐帧的桌面端行为见
`E:\Zcode_android\docs\desktop\`（第二批产物），本文不重复。

---

## 8. 仍未证实

| # | 项 | 状态 |
| --- | --- | --- |
| 1 | `telemetry-report` 到达桌面端后的处置（是否落库、是否影响会话） | **未证实**：仅证实客户端发送路径与 strict 校验；桌面端消费未读 |
| 2 | `workspace-bridge-open.taskId` 的语义（初始任务 vs 仅提示） | **未证实**：schema 仅约束为可选字符串 |
| 3 | `mobile-diagnostic.closeCode` 允许负值的意图 | **未证实**：schema 无 `nonnegative()`，但取值来源（WebSocket CloseEvent.code）通常 ≥1000 |
| 4 | 两臂同名帧（`platform-response` / `workspace-reconnect-response`）在普通 union 下的解析顺序是否影响结果 | ⚠️ **推测**：`k([...])` 按数组顺序试解析，两臂字段集不冲突（成功臂不含 `error`、失败臂不含 `result`），故顺序无影响 |
| 5 | `data` 封套的 `payload` 在 schema 上只允许 rpc-frame 族，但业务帧实际也走它——桌面端是否对 `payload` 做 schema 校验 | **未证实**：网页端发送侧不做该校验；桌面端入站校验归第二批 |
