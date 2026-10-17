# 02 · 桌面端帧 schema 全集（`zcode_type` 判别联合）逐字段权威表

> **审计基线**：ZCode 桌面端 3.15.3（app.asar 340,516,766 B，build `d7ca8bee`，2026-10-13）
> 主要坐标：`out/main/index.js`、`out/main/chunk-OW6EUS2G.js`、`out/host/chunk-OUCOFHX4.js`
> 对照基线：网页端 `Reference_web/3.15.3/assets/validation-CqXZOLXO.js`

**偏移口径说明（全文统一）**：本文件凡写「偏移 N」均指**字节偏移**（`grep -b` / `Buffer.byteLength`
口径），与派单 BRIEF 一致。
- `out/main/chunk-OW6EUS2G.js` 在 0–728,200 区间是**纯 ASCII**，字节偏移 == 字符偏移。
- 网页端 `validation-CqXZOLXO.js` 含中文等多字节字符，**字节偏移 = 字符偏移 + 72**（在 28 万区间）。
  例：`uS=k([` 字节 284,618 / 字符 284,546 —— 派单给的 284,618–287,155 是字节口径，已复核一致。

---

## 0. 一句话结论

桌面端用**同一个** `zcode_type` 判别联合（`e.union(DD)` / `UD`）同时承载双向帧；
入站方向由 `routePayload` 的 `switch` 只认其中 **10 个**字面量，其余 9 个是桌面→移动单向。
**校验失败的帧在 `handleDataPayload` 里被静默丢弃**（只打一条 warn，无错误回执）。
联合共 **21 个臂 / 19 个字面量**；只有 **3 个 schema 是 `.strict()`**（`telemetry-report`、
`rpc-frame`、`rpc-frame-ack`），其余臂多带键只会被 **strip**，不会丢帧。

---

## 1. 定义位置与逐字抄录

### 1.1 坐标

| 位置 | 原路径 | 字节偏移 | 行号 | 符号 |
|---|---|---|---|---|
| **权威（main）** | `out/main/chunk-OW6EUS2G.js` | **725,057** | 64 | `DD=[…]` |
| 联合构造 | 同上 | **728,113** | 64 | `UD=e.union(DD)` |
| 解析入口 | 同上 | **728,128** | 64 | `function qee(t){let n=UD.safeParse(t);return n.success?n.data:null}` |
| **host 侧同构副本** | `out/host/chunk-OUCOFHX4.js` | **689,416** | 64 | `wU=[…]` |
| host 联合构造 | 同上 | **692,472** | 64 | `Y7=e.union(wU)` |
| 网页端副本 | `Reference_web/3.15.3/assets/validation-CqXZOLXO.js` | **284,618** | 58 | `uS=k([…])` |
| 网页端解析入口 | 同上 | **287,156** | 58 | `function dS(e){let t=uS.safeParse(e);return t.success?t.data:null}` |

符号别名（**同名符号在不同 chunk 含义不同，必须连上下文看**）：

| 桌面端 chunk 符号 | 含义 | 网页端对应符号 |
|---|---|---|
| `e` | zod 命名空间（`e.object`/`e.literal`/`e.number`/`e.enum`/`e.union`/`e.array`/`e.unknown`/`e.boolean`/`e.record`/`e.discriminatedUnion`） | `O/N/w/M/k/D/E/T/R/A` |
| `DD` | 联合臂数组 | `uS` 的数组字面量 |
| `UD` | `e.union(DD)`，本文件的主角 | `uS` |
| `qee` | 解析函数，**被 main-index 以 `Lb as vf` 导入**（见 §1.3） | `dS` |
| `S` | `e.string().trim().min(1)` | `$` |
| `Ze` | `S.max(256).regex(/^[A-Za-z0-9._~-]+$/u)` | `eS` |
| `Hf` | `viewState` | `aS` |
| `MD` | `deviceInfo` | `oS` |
| `zD` | `bridge` 判别联合 | `iS` |
| `$D` / `Vx` | bootstrap result / workspace-list result | `sS` / `cS` |
| `Gf` | `reason` 枚举（11 值） | `rS` |
| `Kf` | `platform-request` 的 `method` 枚举（7 值） | `lS` |
| `xr` / `Dg` | `rpc-frame` / `rpc-frame-ack` 的 strict schema | `Dd` / `Od` |
| `Wa` | `telemetry-report.event` 的 strict schema | `Sl` |
| `qx` / `Gx` | `workspaces[]` / `tasks[]` 数组 | `tS` / `nS` |
| `Re` / `Ue` | 常量对象（`Re.maxFrameBytes` 等 / rpc 传输预算） | `Uu` / `bd` |

### 1.2 逐字抄录（`chunk-OW6EUS2G.js` 偏移 725,057–728,114，原文，未改一个字符）

```js
DD=[e.object({zcode_type:e.literal("telemetry-report"),event:Wa}).strict(),e.object({zcode_type:e.literal("bootstrap-request"),requestId:S}),e.object({zcode_type:e.literal("bootstrap-response"),requestId:S,success:e.literal(!0),result:$D}),e.object({zcode_type:e.literal("workspace-list-request"),requestId:S}),e.object({zcode_type:e.literal("workspace-list-response"),requestId:S,success:e.literal(!0),result:Vx}),e.object({zcode_type:e.literal("workspace-list-updated"),result:Vx}),e.object({zcode_type:e.literal("workspace-bridge-open"),requestId:S,bridgeSessionId:Ze,bridgeGeneration:e.number().int().nonnegative().optional(),recoveryId:Ze.optional(),workspaceKey:S,taskId:S.optional()}),e.object({zcode_type:e.literal("workspace-bridge-ready"),requestId:S,bridgeSessionId:Ze,bridgeGeneration:e.number().int().nonnegative().optional(),recoveryId:Ze.optional(),bridge:zD}),e.object({zcode_type:e.literal("workspace-bridge-error"),requestId:S,bridgeSessionId:Ze.optional(),bridgeGeneration:e.number().int().nonnegative().optional(),recoveryId:Ze.optional(),reason:Gf,error:e.string()}),e.object({zcode_type:e.literal("workspace-reconnect-request"),requestId:S,workspaceKey:S}),e.object({zcode_type:e.literal("workspace-reconnect-response"),requestId:S,workspaceKey:S,success:e.literal(!0)}),e.object({zcode_type:e.literal("workspace-reconnect-response"),requestId:S,workspaceKey:S,success:e.literal(!1),error:e.string()}),e.object({zcode_type:e.literal("mobile-view-state-update"),viewState:Hf,deviceInfo:MD.optional()}),e.object({zcode_type:e.literal("platform-request"),requestId:S,method:Kf,args:e.unknown().optional()}),e.object({zcode_type:e.literal("platform-response"),requestId:S,method:Kf,success:e.literal(!0),result:e.unknown()}),e.object({zcode_type:e.literal("platform-response"),requestId:S,method:Kf,success:e.literal(!1),error:e.string()}),xr,Dg,e.object({zcode_type:e.literal("bridge-degraded"),bridgeSessionId:Ze,bridgeGeneration:e.number().int().nonnegative().optional(),recoveryId:Ze.optional(),reason:e.enum(["rpc-transport-fault","rpc-frame-gap","buffer-overflow","buffer-timeout"]),seq:e.number().int().nonnegative().optional(),expectedSeq:e.number().int().nonnegative().optional(),droppedCount:e.number().int().nonnegative().optional()}),e.object({zcode_type:e.literal("app-error"),requestId:S.optional(),bridgeSessionId:Ze.optional(),reason:Gf,error:e.string()}),e.object({zcode_type:e.literal("mobile-diagnostic"),event:e.enum(["state-transition","socket-close","socket-error","recover-start","recover-scheduled","pair-status","failure"]),timestamp:e.number().int().nonnegative(),state:e.string().optional(),previousState:e.string().optional(),pairStatus:e.enum(["waiting","matched"]).optional(),closeCode:e.number().int().optional(),closeReason:e.string().optional(),wasClean:e.boolean().optional(),wasPaired:e.boolean().optional(),failureReason:Gf.optional(),failureMessage:e.string().optional(),visibilityState:e.string().optional(),online:e.boolean().optional(),hiddenDurationMs:e.number().int().nonnegative().optional()})],UD=e.union(DD);
```

> 抄录到 `UD=e` 为止（`UD=e.union(DD)` 起于偏移 728,113）。

**host 侧副本差异**：`chunk-OUCOFHX4.js` 偏移 689,416 起 3,051 字节与上面**逐字节相同**，
唯一差别是 `telemetry-report` 臂里的符号名 `Wa` → `fs`（`event:fs`，定义在 host chunk 内）。
联合变量名 `DD`→`wU`，构造 `UD=e.union(DD)`→`Y7=e.union(wU)`。
即 **main 与 host 是同一份 schema 的两个副本，无语义差异**（已用归一化比对确认）。

### 1.3 入站校验链路（关键，决定「会不会被丢」）

`out/main/index.js`（纯 ASCII，字节==字符）：

| 偏移 | 原文片段 | 作用 |
|---|---|---|
| 376,664 | `function t0(e){if(ma(e)>kr.maxPhysicalFrameBytes)return null;…JSON.parse…return r&&typeof r=="object"&&"type"in r?r:null}` | `parseRelayMessage`：**只做 JSON.parse + 必须有 `type`**，不做 strict 校验。上限 `kr.maxPhysicalFrameBytes` = 1,048,576 B |
| 377,342 | `function r0(e){…let t=e.zcode_type;return t==="rpc-frame"||t==="rpc-frame-ack"}` | `isRawTransportCandidate` |
| 384,787 | `handleDataPayload(t){if(this.state!=="paired"||r0(t)&&this.options.onRawTransportPayload?.(t))return;let r=vf(t);if(!r){…warn("[web-remote-control] invalid external relay payload dropped",{...hS(t)});return}this.options.onPayload(r)}` | **唯一的入站校验点** |
| 408,266 | `onPayload:s(Pe=>IT(ge,Pe),"onPayload")` | 校验通过后进分发器 |
| 405,108 | `function IT(h,C){switch(C.zcode_type){…default:break}}` | `routePayload`，只认 10 个字面量 |

`vf` 的来源：`main-index.js` 顶部 `import{…,Lb as vf,…}from"./chunk-OW6EUS2G.js"`，
而 chunk 内 `export{… qee as Lb …}` ⇒ **`vf` 就是 `qee` = `UD.safeParse`**（偏移 728,128）。
且 `case"data":{this.handleDataPayload(t.payload)}`（偏移 382,918）说明
**被校验的是中继信封里的 `payload` 本身**，不是整条 `{type:"data",…}`。

⇒ **结论：`payload` 过不了 `UD.safeParse` 就整帧丢弃，无 ack、无错误回执。**
（与 BRIEF §6.1 一致，本次从桌面端侧复核确认。）

---

## 2. 逐臂字段表（21 臂 / 19 字面量）

通用符号语义（**先读这里，后面表格不再重复**）：

| 符号 | 定义（原文） | 偏移 | 语义 |
|---|---|---|---|
| `S` | `var S=e.string().trim().min(1)` | 722,142 | **字符串；先 trim，再要求 trim 后长度 ≥1**（空串/纯空白**不通过**） |
| `Ze` | `Ze=S.max(Re.transportEnvelopeIdMaxChars).regex(/^[A-Za-z0-9._~-]+$/u)` | 722,197 | `S` + 长度 ≤256 + **字符集只允许 `A-Za-z0-9._~-`** |
| `e.string()` | — | — | 裸字符串，**不 trim、允许空串** |
| `e.number().finite()` | — | — | 有限数（可小数、可负） |
| `e.number().int().nonnegative()` | — | — | 非负整数 |
| `e.number().int().positive()` | — | — | 正整数 |
| `e.literal(!0)` / `(!1)` | — | — | `true` / `false` |
| `e.unknown()` | — | — | 任意值，无约束 |
| `_r` | `_r=e.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER)` | 545,974 | 非负整数 ≤ 2^53-1 |
| `$g` | `$g=e.number().int().positive().max(Number.MAX_SAFE_INTEGER)` | 545,914 | **正整数** ≤ 2^53-1 |
| `no` | `no=e.string().min(1).max(Ue.transportIdMaxChars).regex(/^[A-Za-z0-9._~-]+$/u)` | 546,037 | **不 trim**；长度 1–256；字符集 `A-Za-z0-9._~-` |

> `S` 与 `no` 的区别：`S` 会 trim 且要求 trim 后非空（**`"  x  "` 通过**）；`no` 不 trim 但**首尾有空格就不通过**。

方向标记：**↓入** = 桌面端 `routePayload` 会处理（移动→桌面）；**↑出** = 桌面端只发不收
（`routePayload` 落到 `default:break`）。

---

### 臂 1 · `telemetry-report` ↓入 —— **`.strict()`**
偏移 725,071

| 键 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `zcode_type` | string(literal) | 必填 | 恒为 `"telemetry-report"` |
| `event` | object（strict，`Wa`，偏移 512,908） | 必填 | 见 §3.1 |

`.strict()` ⇒ 任何额外键 ⇒ `unrecognized_keys` ⇒ 整帧丢弃。

### 臂 2 · `bootstrap-request` ↓入
偏移 725,142

| 键 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `zcode_type` | string(literal) | 必填 | `"bootstrap-request"` |
| `requestId` | string | 必填 | `S`：trim 后非空 |

### 臂 3 · `bootstrap-response` ↑出
偏移 725,208

| 键 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `zcode_type` | string(literal) | 必填 | `"bootstrap-response"` |
| `requestId` | string | 必填 | `S` |
| `success` | boolean(literal) | 必填 | **恒为 `true`**（失败分支不存在于此联合） |
| `result` | object（`$D`，偏移 724,622） | 必填 | 见 §3.2 |

### 臂 4 · `workspace-list-request` ↓入
偏移 725,307

| 键 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `zcode_type` | string(literal) | 必填 | `"workspace-list-request"` |
| `requestId` | string | 必填 | `S` |

### 臂 5 · `workspace-list-response` ↑出
偏移 725,378

| 键 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `zcode_type` | string(literal) | 必填 | `"workspace-list-response"` |
| `requestId` | string | 必填 | `S` |
| `success` | boolean(literal) | 必填 | **恒为 `true`** |
| `result` | object（`Vx`，偏移 724,777） | 必填 | 见 §3.3 |

### 臂 6 · `workspace-list-updated` ↑出
偏移 725,482

| 键 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `zcode_type` | string(literal) | 必填 | `"workspace-list-updated"` |
| `result` | object（`Vx`） | 必填 | 见 §3.3；**无 `requestId`** |

### 臂 7 · `workspace-bridge-open` ↓入
偏移 725,551

| 键 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `zcode_type` | string(literal) | 必填 | `"workspace-bridge-open"` |
| `requestId` | string | 必填 | `S` |
| `bridgeSessionId` | string | **必填** | `Ze`：trim 非空 + ≤256 + `[A-Za-z0-9._~-]` |
| `bridgeGeneration` | number | `.optional()` | `.int().nonnegative()` |
| `recoveryId` | string | `.optional()` | `Ze` |
| `workspaceKey` | string | 必填 | `S` |
| `taskId` | string | `.optional()` | `S` |

### 臂 8 · `workspace-bridge-ready` ↑出
偏移 725,759

| 键 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `zcode_type` | string(literal) | 必填 | `"workspace-bridge-ready"` |
| `requestId` | string | 必填 | `S` |
| `bridgeSessionId` | string | **必填** | `Ze` |
| `bridgeGeneration` | number | `.optional()` | `.int().nonnegative()` |
| `recoveryId` | string | `.optional()` | `Ze` |
| `bridge` | union(`zD`) | 必填 | 见 §3.4 |

### 臂 9 · `workspace-bridge-error` ↑出
偏移 725,943

| 键 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `zcode_type` | string(literal) | 必填 | `"workspace-bridge-error"` |
| `requestId` | string | 必填 | `S` |
| `bridgeSessionId` | string | `.optional()` | `Ze`（**注意此臂可选**） |
| `bridgeGeneration` | number | `.optional()` | `.int().nonnegative()` |
| `recoveryId` | string | `.optional()` | `Ze` |
| `reason` | enum(`Gf`) | 必填 | 11 值，见 §3.5 |
| `error` | string | 必填 | 裸 `e.string()`（**允许空串**） |

### 臂 10 · `workspace-reconnect-request` ↓入
偏移 726,155

| 键 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `zcode_type` | string(literal) | 必填 | `"workspace-reconnect-request"` |
| `requestId` | string | 必填 | `S` |
| `workspaceKey` | string | 必填 | `S` |

### 臂 11 · `workspace-reconnect-response`（成功）↑出
偏移 726,246

| 键 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `zcode_type` | string(literal) | 必填 | `"workspace-reconnect-response"` |
| `requestId` | string | 必填 | `S` |
| `workspaceKey` | string | 必填 | `S` |
| `success` | boolean(literal) | 必填 | **恒为 `true`**；此臂**无** `error` |

### 臂 12 · `workspace-reconnect-response`（失败）↑出
偏移 726,360

| 键 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `zcode_type` | string(literal) | 必填 | `"workspace-reconnect-response"` |
| `requestId` | string | 必填 | `S` |
| `workspaceKey` | string | 必填 | `S` |
| `success` | boolean(literal) | 必填 | **恒为 `false`** |
| `error` | string | 必填 | 裸 `e.string()` |

> 两臂靠 `success` 字面量区分；`z.union` 按顺序试，故 `success:true` 命中臂 11、`false` 命中臂 12。
> **`success` 不是布尔，是字面量**：`success:true` 若带 `error` 键 ⇒ 臂 11 因多余键被 strip（非 strict，仍通过）。

### 臂 13 · `mobile-view-state-update` ↓入
偏移 726,491

| 键 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `zcode_type` | string(literal) | 必填 | `"mobile-view-state-update"` |
| `viewState` | object（`Hf`） | **必填** | 见 §3.6 |
| `deviceInfo` | object（`MD`） | `.optional()` | 见 §3.7 —— **注意：字段可选，但一旦存在其内部键必须过校验** |

### 臂 14 · `platform-request` ↓入
偏移 726,590

| 键 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `zcode_type` | string(literal) | 必填 | `"platform-request"` |
| `requestId` | string | 必填 | `S` |
| `method` | enum(`Kf`) | 必填 | 7 值，见 §3.8 |
| `args` | unknown | `.optional()` | 无约束 |

### 臂 15 · `platform-response`（成功）↑出
偏移 726,693

| 键 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `zcode_type` | string(literal) | 必填 | `"platform-response"` |
| `requestId` | string | 必填 | `S` |
| `method` | enum(`Kf`) | 必填 | 7 值 |
| `success` | boolean(literal) | 必填 | **恒为 `true`** |
| `result` | unknown | 声明为必填，**但实际可缺省** | `e.unknown()` 的解析是 `t._zod.parse=r=>r`（恒通过，连 `undefined` 也通过）⇒ 缺键不会失败 |

### 臂 16 · `platform-response`（失败）↑出
偏移 726,810

| 键 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `zcode_type` | string(literal) | 必填 | `"platform-response"` |
| `requestId` | string | 必填 | `S` |
| `method` | enum(`Kf`) | 必填 | 7 值 |
| `success` | boolean(literal) | 必填 | **恒为 `false`** |
| `error` | string | 必填 | 裸 `e.string()` |

### 臂 17 · `rpc-frame` ↓入 —— **`.strict()` + `.superRefine`**
定义在 `xr`，偏移 546,466（不在联合字面量里，联合直接引用 `xr`）

| 键 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `zcode_type` | string(literal) | 必填 | `"rpc-frame"` |
| `bridgeSessionId` | string | **必填** | `no`：**不 trim**，1–256 字符，`[A-Za-z0-9._~-]` |
| `bridgeGeneration` | number | `.optional()` | `_r`（非负整数 ≤2^53-1） |
| `recoveryId` | string | `.optional()` | `no` |
| `seq` | number | **必填** | `$g`：**正整数** ≤2^53-1（**0 不通过**） |
| `messageSeq` | number | **必填** | `$g`：**正整数**（**0 不通过**） |
| `fragmentIndex` | number | **必填** | `.int().nonnegative().max(64-1)` ⇒ **0–63** |
| `fragmentCount` | number | **必填** | `.int().positive().max(64)` ⇒ **1–64** |
| `messageBytes` | number | **必填** | `.int().positive().max(16*1024*1024)` ⇒ **1–16,777,216** |
| `checksum` | object(`$E`，strict) | **必填** | 见 §3.9 |
| `dataBase64` | string | **必填** | `.min(4).max(1,048,576)` + `.refine(QS)`（见下） |

`.superRefine` 追加两条（原文，偏移 546,700 附近）：
```js
t.fragmentIndex>=t.fragmentCount&&n.addIssue({code:"custom",path:["fragmentIndex"],message:"fragmentIndex must be smaller than fragmentCount"}),
t.fragmentCount>t.messageBytes&&n.addIssue({code:"custom",path:["fragmentCount"],message:"non-empty fragments cannot exceed message bytes"})
```
`dataBase64` 的 `.refine(QS)`（`QS` 偏移 546,308，原文）：
```js
function QS(t){if(t.length<4||t.length>Ue.maxPhysicalFrameBytes)return!1;let n=Mg(t);return n!==null&&n.byteLength>0&&zg(n)===t}
```
⇒ 必须是**规范（canonical）标准 base64**：字母表 `ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/`（偏移 544,905），
标准 `=` 补齐，**无空白**，且「解码→再编码 == 原串」；解码后字节数 **>0**（空分片不通过）。
`zE`（偏移 544,625）= `e.string().min(4).regex(/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/u)`。

`.strict()` ⇒ 任何额外键 ⇒ 整帧丢弃。

### 臂 18 · `rpc-frame-ack` ↓入 —— **`.strict()`**
定义在 `Dg`，偏移 547,204

| 键 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `zcode_type` | string(literal) | 必填 | `"rpc-frame-ack"` |
| `bridgeSessionId` | string | **必填** | `no` |
| `bridgeGeneration` | number | `.optional()` | `_r` |
| `recoveryId` | string | `.optional()` | `no` |
| `ackMessageSeq` | number | **必填** | `$g`：**正整数**（0 不通过） |

**注意**：ack 只有这 5 个键；**没有** `seq`/`messageSeq`/`fragment*`/`checksum`/`dataBase64`。
`.strict()` ⇒ 多带任何一个都会丢帧。
（与 BRIEF §6.4 一致：`bridgeGeneration`/`recoveryId` 确为 `.optional()`，
「ack 必须带三元组否则被忽略」的说法**不成立**。）

### 臂 19 · `bridge-degraded` ↑出
偏移 726,931

| 键 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `zcode_type` | string(literal) | 必填 | `"bridge-degraded"` |
| `bridgeSessionId` | string | **必填** | `Ze` |
| `bridgeGeneration` | number | `.optional()` | `.int().nonnegative()` |
| `recoveryId` | string | `.optional()` | `Ze` |
| `reason` | enum | 必填 | **仅 4 值**：`rpc-transport-fault` / `rpc-frame-gap` / `buffer-overflow` / `buffer-timeout` |
| `seq` | number | `.optional()` | `.int().nonnegative()` |
| `expectedSeq` | number | `.optional()` | `.int().nonnegative()` |
| `droppedCount` | number | `.optional()` | `.int().nonnegative()` |

> 与 `workspace-bridge-error.reason`（11 值 `Gf`）**是两个不同的枚举**，别混用。

### 臂 20 · `app-error` ↑出
偏移 727,331

| 键 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `zcode_type` | string(literal) | 必填 | `"app-error"` |
| `requestId` | string | `.optional()` | `S`（**本臂唯一可选的 requestId**） |
| `bridgeSessionId` | string | `.optional()` | `Ze` |
| `reason` | enum(`Gf`) | 必填 | 11 值 |
| `error` | string | 必填 | 裸 `e.string()` |

### 臂 21 · `mobile-diagnostic` ↓入
偏移 727,457

| 键 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `zcode_type` | string(literal) | 必填 | `"mobile-diagnostic"` |
| `event` | enum | 必填 | 7 值：`state-transition` / `socket-close` / `socket-error` / `recover-start` / `recover-scheduled` / `pair-status` / `failure` |
| `timestamp` | number | 必填 | `.int().nonnegative()` |
| `state` | string | `.optional()` | 裸 `e.string()` |
| `previousState` | string | `.optional()` | 裸 `e.string()` |
| `pairStatus` | enum | `.optional()` | `waiting` / `matched` |
| `closeCode` | number | `.optional()` | `.int()`（**可负**） |
| `closeReason` | string | `.optional()` | 裸 `e.string()` |
| `wasClean` | boolean | `.optional()` | — |
| `wasPaired` | boolean | `.optional()` | — |
| `failureReason` | enum(`Gf`) | `.optional()` | 11 值 |
| `failureMessage` | string | `.optional()` | 裸 `e.string()` |
| `visibilityState` | string | `.optional()` | 裸 `e.string()` |
| `online` | boolean | `.optional()` | — |
| `hiddenDurationMs` | number | `.optional()` | `.int().nonnegative()` |

---

## 3. 嵌套对象逐键展开

### 3.1 `telemetry-report.event` = `Wa`（偏移 512,908，**strict**）

| 键 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `elementName` | string(literal) | 必填 | 恒为 `"session_create"` |
| `eventRegion` | string(literal) | 必填 | 恒为 `"app"` |
| `eventType` | string(literal) | 必填 | 恒为 `"result"` |
| `talkId` | string | 必填 | `.min(1).max(512)` |
| `messageId` | string | 必填 | `.min(1).max(512)` |
| `context` | object（**strict**） | 必填 | `clientTimezone:string.max(128)` / `clientLanguage:string.max(128)` / `screenResolution:string.max(64)`，三者均必填 |
| `eventExtraDetail` | object（**strict**） | 必填 | `create_source:enum(["group","project","session"])` / `client_kind:literal("mobile")` / `workspace_kind:enum(["local","remote"])` / `remote_kind:enum(["","ssh","wsl","docker","server"])`，四者均必填 |

> 相邻还有 `DS=Wa.extend({eventExtraDetail:Wa.shape.eventExtraDetail.extend({create_source:e.enum(["automation_idle","automation_scheduled"]),client_kind:e.literal("desktop")})})`（偏移 513,456 起）。
> 但联合里写死的是 `event:Wa`，**不接受** `DS` 形状的 `create_source`。
> ⚠️ 该事件叶子字段归属 B10；此处仅给出信封层可见的完整形状。

### 3.2 `bootstrap-response.result` = `$D`（偏移 724,622）

| 键 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `windowControlSessionId` | string | 必填 | `S` |
| `desktopAppVersion` | string | `.optional()` | `S` |
| `workspaces` | array(`qx`) | 必填 | 见 §3.10 |
| `tasks` | array(`Gx`) | 必填 | 见 §3.11 |
| `initialViewState` | object(`Hf`) | `.optional()` | 同 §3.6 |
| `mobileViewState` | object(`Hf`) | `.optional()` | 同 §3.6（**桌面端会把移动端上报的 viewState 回显在这里**） |

### 3.3 `workspace-list-response.result` / `workspace-list-updated.result` = `Vx`（偏移 724,777）

| 键 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `workspaces` | array(`qx`) | 必填 | 见 §3.10 |
| `tasks` | array(`Gx`) | `.optional()` | 见 §3.11 |
| `activeWorkspaceKey` | string | `.optional()` | `S` |
| `activeTaskId` | string | `.optional()` | `S` |

### 3.4 `bridge` = `zD`（偏移 723,571）—— `e.discriminatedUnion("kind",[…])`，两臂

两臂共有：`bridgeSessionId:Ze`（必填）、`bridgeGeneration:_r.optional()`、`recoveryId:Ze.optional()`。

| `kind` | 额外键 | 类型 | 必填 |
|---|---|---|---|
| `"local"` | `workspaceKey` | string(`S`) | 必填 |
| | `workspacePath` | string(`S`) | 必填 |
| | `initialTaskId` | string(`S`) | `.optional()` |
| `"remote"` | `workspaceKey` | string(`S`) | 必填 |
| | `workspacePath` | string(`S`) | 必填 |
| | `workspaceIdentity` | string(`S`) | **必填**（remote 专有） |
| | `remoteSessionId` | string(`S`) | **必填**（remote 专有） |
| | `initialTaskId` | string(`S`) | `.optional()` |

两臂均**非** strict。

### 3.5 `reason` = `Gf`（偏移 723,313）—— 11 值枚举

`session-not-found` / `session-expired` / `session-conflict` / `workspace-closed` /
`desktop-disconnected` / `invalid-mobile-connection` / `desktop-bootstrap-timeout` /
`connection-recovery-timeout` / `relay-unavailable` / `unsupported-action` / `unexpected-error`

用于：`workspace-bridge-error.reason`、`app-error.reason`、`mobile-diagnostic.failureReason`。

### 3.6 `viewState` = `Hf`（偏移 724,038）

| 键 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `activeWorkspaceKey` | string | `.optional()` | `S` |
| `activeTaskId` | string | `.optional()` | `S` |
| `updatedAt` | number | **必填** | `e.number().finite()`（无 `.optional()`） |

### 3.7 `deviceInfo` = `MD`（偏移 724,141）—— 12 键，**只有 `updatedAt` 必填**

| 键 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `platform` | string | **必填** | 裸 `e.string()`（**允许空串**） |
| `version` | string | **必填** | 裸 `e.string()` |
| `name` | string | **必填** | 裸 `e.string()` |
| `userAgent` | string | `.optional()` | 裸 `e.string()` |
| `language` | string | `.optional()` | 裸 `e.string()` |
| `languages` | array(string) | `.optional()` | 元素裸 `e.string()` |
| `browserPlatform` | string | `.optional()` | 裸 `e.string()` |
| `viewport` | object | `.optional()` | `width/height/devicePixelRatio` 均 `e.number().finite()` 必填 |
| `screen` | object | `.optional()` | `width/height` 均 `e.number().finite()` 必填 |
| `timezone` | string | `.optional()` | 裸 `e.string()` |
| `online` | boolean | `.optional()` | — |
| `updatedAt` | number | **必填** | `e.number().finite()` ← **没有 `.optional()`** |

> **这是已知的真实缺陷（BRIEF §6.2 复核结论：成立）**：`{platform,version,name}` 三键
> 缺 `updatedAt` ⇒ `deviceInfo` 校验失败 ⇒ 臂 13 失败 ⇒ **整条 `mobile-view-state-update` 被丢弃**。

### 3.8 `method` = `Kf`（偏移 724,884）—— 7 值枚举

`isDockerAvailable` / `listWSLDistros` / `listDockerContainers` / `listSSHConfigAliases` /
`loadMcpFromUserDirectory` / `saveMcpToUserDirectory` / `migrateLegacyCommonMcp`

### 3.9 `checksum` = `$E`（偏移 546,214，**strict**）

| 键 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `algorithm` | string(literal) | 必填 | 恒为 `"crc32"` |
| `value` | string | 必填 | `.regex(/^[0-9a-f]{8}$/u)` ⇒ **恰好 8 位小写十六进制** |

### 3.10 `workspaces[i]` = `AD`（偏移 722,267）—— 8 键
`workspacePath:S`(必填) / `workspaceIdentity:S.optional()` / `remoteSessionId:S.optional()` /
`label:S`(必填) / `workspacePurpose:enum(["project","conversation"]).optional()` /
`kind:enum(["local","remote"])`(必填) / `connectionState:enum(["connected","disconnected","reconnecting"]).optional()` /
`lastConnectionError:e.string().optional()`

### 3.11 `tasks[i]` = `OD`（偏移 722,794）—— 16 键
`taskId:S` / `title:e.string()` / `workspacePath:S` / `workspaceIdentity:S.optional()` /
`remoteSessionId:S.optional()` / `workspaceLabel:S` / `workspaceKind:enum(["local","remote"])` /
`createdAt:e.number().finite()` / `updatedAt:e.number().finite()` / `provider:sn.optional()` /
`unreadAt:e.number().finite().optional()` / `displayStatus:enum(["idle","running","completed","error"]).optional()` /
`hasBackgroundWork:e.boolean().optional()` / `workflowActivity:JS.optional()` /
`pinned:e.boolean().optional()` / `archived:e.boolean().optional()`

> `workspaces[i]` / `tasks[i]` 的叶子语义归 B10；此处只列键名与顶层约束，
> 供信封层（`result.workspaces` / `result.tasks`）实现者对齐。

### 3.12 中继信封（`{type:"data",…}`）—— **不是** `zcode_type` 联合的一部分

`z2=e.object({type:e.literal("data"),payload:ev,client_ts:_r.optional(),server_ts:_r.optional()}).strict()`（偏移 547,379），
其中 `ev=e.union([xr,Dg])`（偏移 547,359）。

⚠️ **`z2` 是死代码**：全 chunk 仅出现 1 次（定义处），未被使用、未被导出。
桌面端实际的信封校验是 `t0`（偏移 376,664，见 §1.3）：**只要求有 `type` 字段**，不 strict。
⇒ **信封里多带键无害**；`client_ts`/`server_ts` 不被校验（但若出现，`_r` 语义为「非负整数 ≤2^53-1」）。

另有出站构造器：
`UE(t,n)={type:"data",payload:t,…client_ts…,server_ts…}`（偏移 547,564，等价于 `relayEnvelopeFor`）；
`nv(t)={zcode_type:"rpc-frame",…t.identity,seq,messageSeq,fragmentIndex,fragmentCount,messageBytes,checksum,dataBase64}`
（函数偏移 548,831，`zcode_type` 字面量在 548,853）；
`tv(t)`（偏移 548,383）用 `ME=e.object({bridgeSessionId:no,bridgeGeneration:_r.optional(),recoveryId:no.optional()}).strict()`（偏移 546,115）
做**出站身份**校验，失败抛 `Error("remote.rpcFrame.invalidIdentity")`。
`M2`（偏移 548,073）= `ev.safeParse`，`DE`（偏移 547,485）= `utf8Bytes`。

### 3.13 `rpc-frame` 线格式预算（`Ue`，偏移 545,752）

```js
Ue={maxPhysicalFrameBytes:Re.maxFrameBytes,maxMessageBytes:16*1024*1024,maxFragments:64,assemblyTimeoutMs:3e4,transportIdMaxChars:Re.transportEnvelopeIdMaxChars}
```
`Re`（偏移 534,068）= `{maxFrameBytes:1024*1024,logicalFrameAssemblyMaxBytes:16*1024*1024,logicalFrameAssemblyMaxFragments:1024,…,transportEnvelopeIdMaxChars:256,…}`

| 常量 | 值 | 用在哪 |
|---|---|---|
| `maxPhysicalFrameBytes` | **1,048,576 B (1 MiB)** | `t0` 对**整条 WS 消息**的长度上限；`dataBase64` 字符串上限 |
| `maxMessageBytes` | **16,777,216 B (16 MiB)** | `rpc-frame.messageBytes.max()` |
| `maxFragments` | **64** | `fragmentCount.max(64)`、`fragmentIndex.max(63)` |
| `assemblyTimeoutMs` | **30,000 ms** | 重组超时（桌面端） |
| `transportIdMaxChars` | **256** | `no` / `Ze` 的长度上限 |

---

## 4. `.strict()` 清单

| schema | 位置 | strict? | 多带一个键会怎样 |
|---|---|---|---|
| `telemetry-report` 臂 | 725,071 | **是**（臂上内联 `.strict()`） | `unrecognized_keys` ⇒ 整帧丢弃 |
| `rpc-frame` = `xr` | 546,466 | **是**（`.strict().superRefine(…)`） | 同上 |
| `rpc-frame-ack` = `Dg` | 547,204 | **是** | 同上 |
| `telemetry-report.event` = `Wa` | 512,908 | **是** | 同上 |
| `Wa.context` / `Wa.eventExtraDetail` | 512,908 内联 | **是** | 同上 |
| `checksum` = `$E` | 546,214 | **是** | 同上 |
| `ME`（出站身份，非联合臂） | 546,115 | **是** | 出站构造抛异常（本地），不上线 |
| `z2`（中继信封，**死代码**） | 547,379 | 是 | 未使用，无实际影响 |
| **其余 18 个联合臂** | — | **否** | **strip**：多余键被静默删除，**帧仍然通过** |
| `Hf`(viewState) / `MD`(deviceInfo) / `zD`(bridge) / `$D` / `Vx` / `AD` / `OD` | — | **否** | strip |

**语义证据**（zod v4 实现，`chunk-OW6EUS2G.js`）：
- 默认对象工厂 `function Lb(t,n){let r={type:"object",shape:t??{},…};return new lr(r)}`（无 `catchall`）；
  `$ZodObject` 解析：`let s=n.catchall; … return s?dy(v,b,m,g,o.value,t,I===!0):v.length?…` ⇒ **无 `catchall` 时未知键根本不进 `m.value`（strip）**。
- `.strict(){return this.clone(C.mergeDefs(this._zod.def,{catchall:Da()}))}`（`Da`=`never`）⇒ `handleCatchall` 推 `{code:"unrecognized_keys",keys:l,…}` ⇒ `safeParse` 失败。
- `.trim()` 实现：`function Un(){return He(t=>t.trim())}`，`He`=`_overwrite`（`check:"overwrite",tx:t`）⇒ **overwrite 检查按注册顺序先跑，`min`/`max`/`regex` 作用在 trim 之后的值**。

---

## 5. 与网页端的差异

### 5.1 结论：**差异 0 条**（联合本体 + 全部被引用的信封层 schema 完全同构）

方法：把桌面端文本按符号改名归一化到网页端命名，再做**逐字符全等比较**。

| 比对对象（桌面 ↔ 网页） | 结果 |
|---|---|
| 联合臂数组（3,051 字符 / 3,051 字符） | **逐字符相同** |
| `$D` ↔ `sS`（bootstrap result） | 相同 |
| `Vx` ↔ `cS`（workspace-list result） | 相同 |
| `Gf` ↔ `rS`（reason 枚举 11 值） | 相同 |
| `zD` ↔ `iS`（bridge 判别联合） | 相同 |
| `Hf` ↔ `aS`（viewState） | 相同 |
| `MD` ↔ `oS`（deviceInfo，12 键） | 相同 |
| `Kf` ↔ `lS`（method 枚举 7 值） | 相同 |
| `xr` ↔ `Dd`（rpc-frame） | 相同（仅 `superRefine` 形参名与 refine 函数名不同：`(t,n)`/`QS` ↔ `(e,t)`/`Ed`） |
| `Dg` ↔ `Od`（rpc-frame-ack） | 相同 |
| `no` ↔ `Cd`（id 字符集/长度） | 相同 |
| `$g` ↔ `xd`（正整数） | 相同（`Number.MAX_SAFE_INTEGER` ↔ `2**53-1`，同值） |
| `_r` ↔ `Sd`（非负整数） | 相同 |
| `$E` ↔ `Td`（checksum strict） | 相同 |
| `S` ↔ `$`（trim 非空字符串） | 相同 |
| `Ze` ↔ `eS` | 相同 |
| `AD` ↔ `tS` 的元素（workspaces[i]，8 键） | 相同 |
| `Ue` ↔ `bd`（rpc 传输预算） | 相同（1 MiB / 16 MiB / 64 / 30 s / 256） |
| `Re` ↔ `Uu`（协议常量） | 相同 |

**唯一措辞差异**（非语义）：网页端 `MD`/`oS` 里 `userAgent` 等用 `S()`（= 裸 `string()`），
桌面端用 `e.string()` —— 同一件事，因为桌面端的 `S` 是 `trim().min(1)`，而它在这里故意用了裸 `e.string()`。

### 5.2 顺带复核（与网页端一致 ⇒ 与 BRIEF §6.2 一致）

`deviceInfo.updatedAt` 必填在**两端都一样**（桌面 `MD` 偏移 724,141；网页 `oS` 字节偏移 283,833）。
所以这不是某一端的笔误，而是**协议本身的形状**：任何只发 `{platform,version,name}` 的客户端都会被两端丢弃。

---

## 6. 实现者速查表

### 6.1 帧 → 必填字段 → 常见失败原因

| `zcode_type` | 方向 | 必填键 | 最常见的静默丢弃原因 |
|---|---|---|---|
| `bootstrap-request` | ↓入 | `requestId` | `requestId` 为空串/纯空白（`S` 要求 trim 后 ≥1） |
| `workspace-list-request` | ↓入 | `requestId` | 同上 |
| `workspace-bridge-open` | ↓入 | `requestId`,`bridgeSessionId`,`workspaceKey` | ① `bridgeSessionId` 含 `[A-Za-z0-9._~-]` 之外字符或 >256；② `bridgeGeneration` 为负；③ `workspaceKey` trim 后为空 |
| `workspace-reconnect-request` | ↓入 | `requestId`,`workspaceKey` | `workspaceKey` trim 后为空 |
| `mobile-view-state-update` | ↓入 | `viewState`（`updatedAt` 必填） | ① **`deviceInfo` 缺 `updatedAt`**（最常见）；② `viewState.updatedAt` 缺失或非有限数 |
| `rpc-frame` | ↓入 | `bridgeSessionId`,`seq`,`messageSeq`,`fragmentIndex`,`fragmentCount`,`messageBytes`,`checksum`,`dataBase64` | ① `seq`/`messageSeq` = 0（要求正整数）；② `fragmentCount` > 64；③ `fragmentIndex` ≥ `fragmentCount`；④ `messageBytes` > 16 MiB；⑤ `fragmentCount` > `messageBytes`；⑥ `dataBase64` 非规范 base64 / 解码为空 / >1 MiB；⑦ `checksum.value` 不是 8 位小写 hex；⑧ **多带任何一个键（strict）** |
| `rpc-frame-ack` | ↓入 | `bridgeSessionId`,`ackMessageSeq` | ① `ackMessageSeq` = 0；② **多带 `seq`/`messageSeq`/`checksum` 等（strict）** |
| `platform-request` | ↓入 | `requestId`,`method` | `method` 不在 7 值枚举内 |
| `telemetry-report` | ↓入 | `event`（strict，见 §3.1） | ① `event` 不是 `session_create/app/result` 形状；② **多带键**；③ `eventExtraDetail.create_source` 不是 `group/project/session` |
| `mobile-diagnostic` | ↓入 | `event`,`timestamp` | `event` 不在 7 值枚举内；`timestamp` 为负或非整数 |
| `bootstrap-response` | ↑出 | `requestId`,`success:true`,`result` | —（我们不构造） |
| `workspace-list-response` | ↑出 | `requestId`,`success:true`,`result` | — |
| `workspace-list-updated` | ↑出 | `result` | — |
| `workspace-bridge-ready` | ↑出 | `requestId`,`bridgeSessionId`,`bridge` | — |
| `workspace-bridge-error` | ↑出 | `requestId`,`reason`,`error` | — |
| `workspace-reconnect-response` | ↑出 | `requestId`,`workspaceKey`,`success` | — |
| `platform-response` | ↑出 | `requestId`,`method`,`success` | — |
| `bridge-degraded` | ↑出 | `bridgeSessionId`,`reason` | `reason` 只有 4 值（**`socket-lost` 不在内**） |
| `app-error` | ↑出 | `reason`,`error` | — |

### 6.2 三条通用铁律

1. **`requestId` / `workspaceKey` / `taskId` / `viewState.*` 都是 `S`**：trim 后必须 ≥1 字符；
   `"  "`、`""`、`null` 一律失败。
2. **`bridgeSessionId` / `recoveryId` 是 `Ze`/`no`**：只允许 `A-Za-z0-9._~-`，长度 ≤256；
   **不能含 `:`、`/`、`+`、空格**。任何自造 token（例如 `recovery:1`）都会直接丢帧。
3. **只有 3 类 schema 是 strict**：`telemetry-report`、`rpc-frame`、`rpc-frame-ack`。
   这三类多带一个键就丢帧；其余帧多带键只是被 strip。

### 6.3 丢帧后的行为（桌面端侧，供诊断）

`handleDataPayload`（偏移 384,787）在 `vf` 返回 null 时：
```js
this.options.logger.warn("[web-remote-control] invalid external relay payload dropped",{...hS(t)})
```
`hS`（偏移 377,035）= 只提取 `zcode_type`/`requestId`/`bridgeSessionId` 三个字符串字段的脱敏元数据
（`ma` 在偏移 377,505，是长度估算）。
**不发任何回执、不改状态、不进 `routePayload`。** 表现为「客户端一直转圈/超时」。

---

## 7. 与我们的实现的差异（对照 `rust/crates/protocol/src/`，只读不改）

### 7.1 【必须修】`mobile-view-state-update` 必被丢弃

| 位置 | 我们的行为 | 桌面端要求 | 后果 |
|---|---|---|---|
| `rust/crates/protocol/src/zemote_client.rs:2482-2486` | `deviceInfo` = `serde_json::to_value(device_info)` | `deviceInfo.updatedAt` **必填**（`MD` 偏移 724,141） | **整帧丢弃** |
| `rust/crates/protocol/src/device_info.rs:98-109` | `DeviceInfo{platform,version,name}`（`device_info.rs:171-182` 有「序列化形状已冻结」的测试，断言整串 JSON 无多余键） | 12 键，其中 `updatedAt:number().finite()` 无 `.optional()` | 同上 |

构造点：`zemote_client.rs:2462`（`mobile_view_state_update_payload`）、
`viewState` 组装在 `zemote_client.rs:2468-2473`、载荷装配与 `deviceInfo` 插入在 `zemote_client.rs:2475-2487`。

**丢失后的桌面端行为**（`out/main/index.js`，已实测）：
- `function ho(h,C,A){h.mobileViewState=C,A&&(h.mobileDeviceInfo=A)}`（偏移 404,331）—— 存不上，`h.mobileViewState` 保持 `null`。
- 消费点 1：`function T(h){…initialViewState:O(h),mobileViewState:h.mobileViewState}`（偏移 398,715 附近，`buildBootstrapResult`）
  ⇒ `bootstrap-response.result.mobileViewState` 回显为空。
- 消费点 2：`function W(h){let C=kt(h),A=O(h);return{workspaces:M(h),tasks:E(h),activeWorkspaceKey:h.mobileViewState?.activeWorkspaceKey??A?.activeWorkspaceKey??Ve(C),activeTaskId:h.mobileViewState?.activeTaskId??h.currentBridge?.initialTaskId??A?.activeTaskId}}`（偏移 398,778，`buildWorkspaceListResult`）
  ⇒ 回落用桌面端自己的 `initialViewState`。
- 消费点 3：会话摘要字段 `mobileViewState:h.mobileViewState,mobileDeviceInfo:h.mobileDeviceInfo`（偏移 394,020）。
⇒ **只影响「移动端上次停留的工作区/任务」这一偏好，不影响配对、开桥、任务列表**（与 BRIEF §6.2 一致）。

### 7.2 【必须修】`rpc-frame` 的预算常量比桌面端宽，超限帧会被对端静默丢弃

| 常量 | 我们的值 | 桌面端权威值 | 文件:行 |
|---|---|---|---|
| `MAX_MESSAGE_BYTES` | **64 MiB** | **16 MiB**（`Ue.maxMessageBytes`，偏移 545,752） | `rust/crates/protocol/src/rpc_transport.rs:66` |
| `MAX_FRAGMENTS` | **256** | **64**（`Ue.maxFragments`） | `rust/crates/protocol/src/rpc_transport.rs:72` |
| `MAX_PHYSICAL_FRAME_BYTES` | 1 MiB | 1 MiB ✅ | `rpc_transport.rs:60` |
| `FRAGMENT_PAYLOAD_BYTES` | 512 KiB | 512 KiB ✅（512 KiB → base64 699,052 ≤ 1 MiB） | `rpc_transport.rs:78` |

**后果（具体到帧）**：
- 逻辑消息 > 16 MiB：`send_message`（`rpc_transport.rs:270`）会照常切片发出，
  但每片 `messageBytes` 都 > `maxMessageBytes` ⇒ **该消息的每一片都被对端丢弃**（`fragmentCount` 也一并作废）。
- 逻辑消息 33–64 MiB：`fragmentCount` 会到 65–128 > 64 ⇒ 同样全丢。
- 接收侧 `accept_payload`（`rpc_transport.rs:369`）用 `MAX_FRAGMENTS`/`MAX_MESSAGE_BYTES` 做准入，
  比桌面端**宽松**，属"多收"方向，不会导致我们丢对端的帧；但两侧常量不一致本身就是隐患。
- `rpc_transport.rs:267-269` 的 PORT-NOTE 明确写了「64 MiB / 512 KiB = 128 ≤ 256，`fragmentLimitExceeded` 不可达」
  —— 这个推理基于**我们自己的常量**，与对端 16 MiB/64 不符；按对端口径，
  有效上限应是 `min(16 MiB, 64 × 512 KiB = 32 MiB) = 16 MiB`。

### 7.3 逐帧合规判定（我们发出的每一类帧）

| 我们的构造点 | `zcode_type` | 合规？ | 依据 |
|---|---|---|---|
| `zemote_client.rs:2384` `bootstrap_request` | `bootstrap-request` | ✅ | `{zcode_type,requestId}`；`requestId` = `req_id("bootstrap")` = `bootstrap-<uuid>`，`S` 通过 |
| `zemote_client.rs:2392` `list_workspaces_request` | `workspace-list-request` | ✅ | 同上（`workspace-list-<uuid>`） |
| `zemote_client.rs:2406` `open_bridge_request` | `workspace-bridge-open` | ✅ | `bridgeSessionId` = `req_id("bridge")` = `bridge-<uuid>` ⇒ 仅 `[A-Za-z0-9-]`，43 字符 ⇒ 过 `Ze`；`bridgeGeneration` 起始 0、发前 `+=1`（`zemote_client.rs:1336`）⇒ ≥1 非负；`workspaceKey` = `conversation_key`（`zemote_client.rs:2494`） |
| `zemote_client.rs:2428` `reopen_bridge_request` | `workspace-bridge-open` | ✅ | 同上；`recoveryId` 来自对端 `bridge.recoveryId`（对端侧类型即 `Ze`），故字符集必然合法 |
| `zemote_client.rs:2448` `reconnect_workspace_request` | `workspace-reconnect-request` | ✅ | `{zcode_type,requestId,workspaceKey}` |
| `zemote_client.rs:2462` `mobile_view_state_update_payload` | `mobile-view-state-update` | ❌ | **`deviceInfo` 缺 `updatedAt`** ⇒ 整帧丢（§7.1）。`viewState` 本身合规（`activeWorkspaceKey`+`updatedAt`，`zemote_client.rs:2468-2473`） |
| `rpc_transport.rs:502` `frame_payload` | `rpc-frame` | ✅（≤16 MiB 且 ≤64 片时） | 键集与 `xr` 完全一致、无多余键；`seq`/`messageSeq` 起始 0、发前 `+=1`（`rpc_transport.rs:284,299`）⇒ ≥1；`fragmentIndex` 0 起；`checksum.algorithm="crc32"`、`value` = `Crc32::hex_of`（8 位小写 hex）；`dataBase64` = `base64::STANDARD`（规范、带 padding）⇒ 过 `QS`。**超 16 MiB / 64 片时 ❌**（§7.2） |
| `rpc_transport.rs:535` `ack_payload` | `rpc-frame-ack` | ✅ | 只发 `zcode_type`/`bridgeSessionId`/`ackMessageSeq`；其余字段 `None` 被 `skip_serializing_if` 省略 ⇒ 无多余键，过 strict；`ackMessageSeq` 取自对端帧的 `messageSeq`（对端保证 ≥1） |
| `zemote_client.rs:99-128` `mod zcode_type` | 未实现：`telemetry-report`、`platform-request`、`platform-response`、`app-error`、`mobile-diagnostic` | — | 常量模块里没有这 5 个字面量 ⇒ 我们**从不发送**这 5 类帧（也就无合规风险）。若将来要发：`platform-request.method` 必须落在 7 值枚举内；`telemetry-report.event` 必须落在 `Wa` 的 `session_create/app/result` 形状内且整帧 strict |

### 7.4 其他核对（无差异项，避免重复排查）

- `conversation_transport.rs`：**不构造任何 `zcode_type` 帧**（全仓 grep 确认；它是 channel/op 层）。
  故本文件对它无合规要求。
- `relay_client.rs`：只拼中继信封 `{"type":"data","payload":…,"client_ts":…}`（`relay_client.rs:945,971`），
  桌面端 `t0` 对信封不 strict ⇒ 合规。
- `rpc_transport.rs:118-167` `RpcFramePayload` 的 `skip_serializing_if = "Option::is_none"`
  保证了「Dart `if (x != null)` 才展开」语义，正是 strict 臂能通过的原因（不会出现 `"checksum":null` 这类多余键）。
- `rust/crates/ffi/src/session.rs:1421` 的测试夹具用了 `"reason":"socket-lost"` —— **不在** `bridge-degraded` 的
  4 值枚举内。它只是 `#[cfg(test)]`（`session.rs:555` 起）里模拟**入站**帧，我们不做入站校验，故不影响线上；
  但若被当成模板去发 `bridge-degraded` 就会丢帧。
- 我们**没有** `bridgeGeneration` 之外的代次校验：`Q`（偏移 405,108 之后）用
  `N?.bridgeSessionId===C.bridgeSessionId&&N.bridgeGeneration===C.bridgeGeneration` 决定
  `readyAnnounced` 与 `flushPendingFrames()`。⇒ 我们**必须**继续在 `workspace-bridge-open` 里带 `bridgeGeneration`
  （虽然 schema 里它 `.optional()`，不带会导致对端不 flush 暂存帧）。

---

## 8. 仍未证实 / 需要抓包

1. **`z2` 是否真的从未被使用** —— 依据是「全 chunk 只有 1 次出现、且不在 export 表里」。
   未排查 `out/preload/*.cjs`、`out/renderer/assets/*` 里是否有独立的一份（它们可能各自内联）。
   影响面小（只影响「信封是否 strict」），但若要 100% 确认需扫其余产物。
2. **`Wa` 之外是否还有别的 `telemetry-report.event` 形状** —— 联合里写死 `event:Wa`，
   而 `DS=Wa.extend(…)`（偏移 513,391）存在但未被引用。**未能证实** `DS` 是否有其它使用点
   （可能在其它 chunk 里以导出名被使用）。若移动端要发 `telemetry-report`，需抓一次真实帧确认 `event` 形状。
3. **`t0` 的 1 MiB 上限作用在「解压后/整条 WS 文本」还是「单条 WS 帧」** —— 原文是
   `if(ma(e)>kr.maxPhysicalFrameBytes)return null`，`ma` 对 string 用 `Buffer.byteLength(e,"utf8")`，
   故是**整条文本长度**。但若 relay 会分片/压缩，实际可用预算可能更小。**需要一次带 512 KiB 分片的抓包**验证。
4. **`deviceInfo.updatedAt` 的取值语义** —— 桌面端只校验「有限数」，不校验单位/单调性。
   修复时用毫秒 epoch（与 `viewState.updatedAt` 一致）是合理默认，但**未从对端证实**它是否被读取
   （`h.mobileDeviceInfo` 只出现在会话摘要与日志里）。
5. **`S` 的 `.trim()` 是否影响我们发出的 `workspaceKey`** —— zod 会 trim 后存值，
   故若我们的 `workspaceKey` 首尾有空格，对端存的是 trim 后的值，**与我们本地记录的 key 可能不一致**。
   未验证对端是否用该值回查工作区；建议抓一次含前导空格 workspacePath 的场景。
6. **`routePayload` 的 10 个入站字面量是否穷尽** —— 依据是 `out/main/index.js` 偏移 405,108 的 `switch` 原文。
   未检查 host 进程（`out/host/index.js`）是否另有入站分发器（B01/B0x 的职责）。

---

## 附录 A · 全部关键坐标速查（字节偏移）

| 内容 | 文件 | 偏移 | 行 |
|---|---|---|---|
| 联合臂数组 `DD=[` | `out/main/chunk-OW6EUS2G.js` | 725,057 | 64 |
| 联合构造 `UD=e.union(DD)` | 同上 | 728,113 | 64 |
| 解析入口 `qee`（=`vf`） | 同上 | 728,128 | 64 |
| host 副本 `wU=[` | `out/host/chunk-OUCOFHX4.js` | 689,416 | 64 |
| host 联合 `Y7=e.union(wU)` | 同上 | 692,472 | 64 |
| 网页端 `uS=k([` | `Reference_web/3.15.3/assets/validation-CqXZOLXO.js` | 284,618 | 58 |
| 网页端解析 `dS` | 同上 | 287,156 | 58 |
| `S`(trim 字符串) | `chunk-OW6EUS2G.js` | 722,142 | 64 |
| `Ze`(id 字符集) | 同上 | 722,197 | 64 |
| `AD` workspaces[i] | 同上 | 722,267 | 64 |
| `Gf` reason 枚举 | 同上 | 723,313 | 64 |
| `OD` tasks[i] | 同上 | 722,794 | 64 |
| `zD` bridge | 同上 | 723,571 | 64 |
| `Hf` viewState | 同上 | 724,038 | 64 |
| `MD` deviceInfo | 同上 | 724,141 | 64 |
| `$D` bootstrap result | 同上 | 724,622 | 64 |
| `Vx` workspace-list result | 同上 | 724,777 | 64 |
| `Kf` method 枚举 | 同上 | 724,884 | 64 |
| `Wa` telemetry event（strict） | 同上 | 512,908 | 63 |
| `DS`=Wa.extend（**未被联合引用**） | 同上 | 513,456 | 63 |
| `Re` 协议常量 | 同上 | 534,068 | 62 |
| `Ue` rpc 预算 | 同上 | 545,752 | 62 |
| `$g` 正整数 | 同上 | 545,914 | 62 |
| `_r` 非负整数 | 同上 | 545,974 | 62 |
| `no` id 字符串 | 同上 | 546,037 | 62 |
| `ME` 出站身份（strict） | 同上 | 546,115 | 62 |
| `$E` checksum（strict） | 同上 | 546,214 | 62 |
| `xr` rpc-frame（strict） | 同上 | 546,466 | 62 |
| `Dg` rpc-frame-ack（strict） | 同上 | 547,204 | 62 |
| `ev`=union([xr,Dg]) | 同上 | 547,359 | 62 |
| `z2` 中继信封（strict，**死代码**） | 同上 | 547,379 | 62 |
| `DE` utf8Bytes | 同上 | 547,485 | 62 |
| `UE` relayEnvelopeFor（出站） | 同上 | 547,564 | 62 |
| `M2`=ev.safeParse | 同上 | 548,073 | 62 |
| `tv` identityFrom（出站身份校验） | 同上 | 548,383 | 62 |
| `nv` frameShell（出站） | 同上 | 548,831 | 62 |
| `Ot` base64 字母表 | 同上 | 544,905 | 62 |
| `zE` base64 正则 | 同上 | 544,625 | 62 |
| `QS` isCanonicalBase64 | 同上 | 546,308 | 62 |
| `t0` parseRelayMessage | `out/main/index.js` | 376,664 | — |
| `hS` safePayloadMetadata | 同上 | 377,035 | — |
| `r0` isRawTransportCandidate | 同上 | 377,342 | — |
| `ma` estimateRawDataBytes | 同上 | 377,505 | — |
| `case"data":handleDataPayload(t.payload)` | 同上 | 382,918 | — |
| `handleDataPayload`（丢帧点） | 同上 | 384,787 | — |
| `invalid external relay payload dropped` | 同上 | 384,954 | — |
| `ho` applyMobileViewStateUpdate | 同上 | 404,331 | — |
| `IT` = `routePayload` | 同上 | 405,108 | — |
| `onPayload` 接线 | 同上 | 408,266 | — |

## 附录 B · 逐臂 `zcode_type` 偏移表

| # | 字面量 | 偏移 | 方向 | strict |
|---|---|---|---|---|
| 1 | `telemetry-report` | 725,071 | ↓入 | **是** |
| 2 | `bootstrap-request` | 725,142 | ↓入 | 否 |
| 3 | `bootstrap-response` | 725,208 | ↑出 | 否 |
| 4 | `workspace-list-request` | 725,307 | ↓入 | 否 |
| 5 | `workspace-list-response` | 725,378 | ↑出 | 否 |
| 6 | `workspace-list-updated` | 725,482 | ↑出 | 否 |
| 7 | `workspace-bridge-open` | 725,551 | ↓入 | 否 |
| 8 | `workspace-bridge-ready` | 725,759 | ↑出 | 否 |
| 9 | `workspace-bridge-error` | 725,943 | ↑出 | 否 |
| 10 | `workspace-reconnect-request` | 726,155 | ↓入 | 否 |
| 11 | `workspace-reconnect-response`(true) | 726,246 | ↑出 | 否 |
| 12 | `workspace-reconnect-response`(false) | 726,360 | ↑出 | 否 |
| 13 | `mobile-view-state-update` | 726,491 | ↓入 | 否 |
| 14 | `platform-request` | 726,590 | ↓入 | 否 |
| 15 | `platform-response`(true) | 726,693 | ↑出 | 否 |
| 16 | `platform-response`(false) | 726,810 | ↑出 | 否 |
| 17 | `rpc-frame`（= `xr`） | 546,466 | ↓入 | **是** |
| 18 | `rpc-frame-ack`（= `Dg`） | 547,204 | ↓入 | **是** |
| 19 | `bridge-degraded` | 726,931 | ↑出 | 否 |
| 20 | `app-error` | 727,331 | ↑出 | 否 |
| 21 | `mobile-diagnostic` | 727,457 | ↓入 | 否 |
