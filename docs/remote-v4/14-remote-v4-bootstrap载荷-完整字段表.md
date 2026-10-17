# remote-v4 `bootstrap-response` / `workspace-list` 载荷：完整字段表（Zod schema 权威挖掘）

> **审计基线**：3.15.3（2026-10-15 快照）
> 主包 `assets/index-BGa3KiRe.js`（6,592,643 B，SHA-256 `ce2e2fc4…b88fd`）
> schema `assets/validation-CqXZOLXO.js`（307,369 B，SHA-256 `96e64e8f…dd6c9b`）
> 旧基线（2026-09-20 / `index-B-ilXaCQ.js`）的偏移与符号名**一律作废**。
>
> **审计日期**：2026-10-15 ｜ **方法**：`Buffer.indexOf` 取**字节偏移** → 窗口取证 → 回主包定位生产/消费端调用点。
> 本代全部偏移都是**字节**偏移（不是 UTF-16 字符偏移），可直接用
> `node -e "const b=require('fs').readFileSync(F);process.stdout.write(b.subarray(OFF-300,OFF+800).toString())"` 复现。
> 标「**外部输入**」的条目来自旧安卓壳真机日志/注释，非本次源码审计所得（该壳已不在工作树，见 §10）。

本文只回答**载荷长什么样**（协议事实）。"壳端/客户端读了哪些、还能拿去干什么"在
[`17-remote-v4-bootstrap载荷-合并总表与未解析字段清单.md`](17-remote-v4-bootstrap载荷-合并总表与未解析字段清单.md)。

---

## 0. 本次审计最要紧的 8 条（先看这里）

| # | 结论 | 坐标 |
|---|---|---|
| 1 | **`deviceInfo`（schema 名 `oS`）在 3.15.3 仍然必填 `updatedAt`** —— `updatedAt:w().finite()`，**没有** `.optional()`。旧基线"必填"结论**成立** | `validation-CqXZOLXO.js` @**283,833** |
| 2 | `deviceInfo` 只出现在 `mobile-view-state-update` 里，该帧是**客户端 → 桌面端**单向；**网页端只发不收**，所以唯一的校验者是桌面端 | @285,800 / 主包 @6,579,843 |
| 3 | 客户端发的 `deviceInfo` 缺 `updatedAt` ⟹ 接收侧 `safeParse` 失败 ⟹ **整帧被丢弃**（`null`），**不抛错、不回错误帧**，只落一行 warn 日志。桌面端因此永远收不到该次视图上报 | 见 §6.5 |
| 4 | bootstrap `result`（`sS`）从 5 键变 **6 键**：新增 `desktopAppVersion`（可选） | @284,202 |
| 5 | `tasks[i]`（`nS`）从 14 键变 **16 键**：新增 `hasBackgroundWork`（`T().optional()` = **布尔**）、`workflowActivity`（`ud.optional()`） | @282,649 |
| 6 | **`provider` 枚举从 5 值缩成 1 值**：`Ou=M(['glm'])` —— 只剩 `glm` | @116,320 |
| 7 | 消息 union `uS` 从 20 成员变 **21 成员**：新增 `telemetry-report`（且是 union 里**唯一** `.strict()` 的业务帧） | @284,618 / @284,627 |
| 8 | **schema 只在 `validation-CqXZOLXO.js` 里定义**；`index-BGa3KiRe.js` 里出现的 `deviceInfo` 是**客户端的构造函数与发送点**，不是 schema 副本 | 主包 @6,548,210 / @6,579,998 |

---

## 1. 权威 schema 在哪、怎么读

### 1.1 文件与落点

| 项 | 事实 |
|---|---|
| **唯一权威定义** | `Reference_web/3.15.3/assets/validation-CqXZOLXO.js`，**307,369 B**，压缩单行 |
| bootstrap 族落点 | **字节偏移 282,127 – 286,6xx**（`$` 到 `mobile-diagnostic` 结束，约 4.5 K 字节） |
| 唯一解析闸门 | `function dS(e){let t=uS.safeParse(e);return t.success?t.data:null}`（`function dS(` @**287,156**，`safeParse` @287,180）——**整个族只有这一个入口，失败返回 `null`** |
| 对外导出 | 导出块 @305,550 起，逐字 `` dS as t ``（@**307,125**） |
| 主包怎么用它 | `index-BGa3KiRe.js` 的导入表逐字 `t as dt`（@61,548 起的那条 `from"./validation-CqXZOLXO.js"`），消费点见下 |
| 主包收帧闸门 | `` handleDataPayload(e){if(U3n(e)&&this.options.onRawTransportPayload?.(e))return;let t=dt(e);t&&this.options.onPayload(t)} `` —— `handleDataPayload(e){if(U3n(e)` @**6,528,315**，`let t=dt(e);t&&this.options.onPayload(t)` @**6,528,394** |
| 旁路判定 `U3n` | `function U3n(e){…return t===\`rpc-frame\`\|\|t===\`rpc-frame-ack\`}` @**6,521,679** |
| **主包里没有 schema 副本** | 主包 `deviceInfo` 只有 2 处：@**6,548,210**（`function o6n(`，网页端**构造** deviceInfo）与 @**6,579,998**（发送点）。全主包搜不到 `viewState:aS` / `deviceInfo:oS` / `windowControlSessionId:$` 这类 schema 声明 |
| `zcode_type` 出现次数 | `validation-*.js` **22** 处（其中 **19** 处是 union 成员声明、`Dd`/`Od` 各 1 处内联、其余为构造器）；主包 **30** 处（全是对象字面量的键或 `===` 比较，**零 schema 声明**） |
| 消息 union | `uS=k([…])` @**284,618**，**21 个成员** = 19 个 `zcode_type` 打头的业务类型 + `Dd`(rpc-frame) + `Od`(rpc-frame-ack) |

> ⚠️ 主包 `deviceInfo` 的 2 处命中**不是** schema：一处是 `o6n()` 造 `{platform,version,name,…}`，一处是 `j.sendPayload({zcode_type:'mobile-view-state-update',…})`。别把"两个文件都出现 `deviceInfo`"读成"两份 schema"。**权威定义只有 `validation-CqXZOLXO.js` 一份。**

### 1.2 族内符号表（本文所有字段表的出处，`validation-CqXZOLXO.js` 字节偏移）

| 符号 | off | 是什么 |
|---|---|---|
| `$` | 282,127 | `` $=S().trim().min(1) `` —— 非空串（带 trim），本代**所有**必填串都用它 |
| `eS` | 282,147 | `$.max(Uu.transportEnvelopeIdMaxChars).regex(/^[A-Za-z0-9._~-]+$/u)` —— 传输 id，≤256 字符 |
| `tS` | 282,217 | `result.workspaces[]` 元素（8 键，§4） |
| `nS` | 282,649 | `result.tasks[]` 元素（**16 键**，§5） |
| `rS` | 283,087 | 失败原因枚举（11 值，§7.5） |
| `iS` | 283,340 | `bridge`（按 `kind` 判别的联合，§7.5） |
| `aS` | 283,744 | viewState（3 键，§6.2） |
| `oS` | 283,833 | **`deviceInfo`（12 键，§6.4）** |
| `sS` | 284,202 | `bootstrap-response.result`（**6 键**，§6.1） |
| `cS` | 284,350 | `workspace-list-*.result`（4 键，§7.1） |
| `lS` | 284,450 | `platform-request/response.method` 枚举（7 值，§7.5） |
| `uS` | 284,618 | 业务消息 union（**21 成员**，§2） |
| `dS` | 287,156 | `safeParse` 闸门 |

`tS` 与 `nS` 之间（282,4xx–282,6xx）还夹着两条**匿名** schema：`O({requestId:$,workspaceKey:$})` 与
`k([O({…,success:N(!0)}),O({…,success:N(!1),error:S()})])` —— `workspace-reconnect-request/response`
的形状被单独定义了一次，union 里又内联写了一遍（两处等价）。

线上限对象 `Uu` @117,018，族内直接用到的是 `transportEnvelopeIdMaxChars:256`（@117,233）：

```js
Uu={maxFrameBytes:1024*1024, logicalFrameAssemblyMaxBytes:16*1024*1024, logicalFrameAssemblyMaxFragments:1024,
    logicalFrameAssemblyMaxConcurrent:32, logicalFrameAssemblyMaxStagedBytes:32*1024*1024,
    logicalFrameAssemblyTimeoutMs:3e4, transportEnvelopeIdMaxChars:256, subscriberBufferMaxOps:500,
    subscriberBufferMaxBytes:1024*1024, eventRetentionPerSession:2e3, snapshotTailWindowRows:60,
    rowsRangeMaxLimit:200, attachmentMaxBytes:20*1024*1024, …}
```

### 1.3 zod 工厂别名反解（本代字母表 —— **与旧基线完全不同，别沿用**）

这些别名都是 `function` 声明，且**语义与旧基线错位**：本代 `O` 是 object、`D` 是 array、`E` 是 unknown
（旧基线 `D`=object、`E`=array、`T`=unknown）。**照着旧字母表读本代代码会得出完全相反的结论。**

| 别名 | 定义 off | 展开（逐字） | 基类绑定 |
|---|---|---|---|
| `S(e)` | 87,801 | `Vi(Fs,e)` = `z.string()` | `Fs` = `ZodString` @87,840 |
| `w(e)` | 90,145 | `ma(cc,e)` = `z.number()` | `cc` = `ZodNumber` @90,185 |
| `T(e)` | 90,372 | `_a(dc,e)` = `z.boolean()` | `dc` = `ZodBoolean` @90,279 |
| `E()` | 90,889 | `xa(vc)` = **`z.unknown()`** | `vc` = `ZodUnknown` @90,801 |
| `D(e,t)` | 91,680 | `Wa(Cc,e,t)` = **`z.array()`** | `Cc` = `ZodArray`（`wrap(){return this.element}` 紧邻其前） |
| `O(e,t)` | 92,512 | `new wc({type:'object',shape:e??{},…})` = **`z.object`（默认 strip 未知键）** | `wc` = `ZodObject` @91,724 |
| `k(e,t)` | 92,855 | `new Dc({type:'union',options:e,…})` = `z.union` | `Dc` = `ZodUnion` @92,744 |
| `A(e,t,n)` | 92,988 | `new Oc({type:'union',options:t,discriminator:e,…})` = `z.discriminatedUnion` | `Oc` = `ZodDiscriminatedUnion` @92,929 |
| `j(e,t,n)` | 93,398 | `new jc({type:'record',…})` = `z.record` | `jc` = `ZodRecord` @93,257 |
| `M(e,t)` | 94,165 | `new Nc({type:'enum',entries:…})` = `z.enum` | `Nc` = `ZodEnum` @93,645 |
| `N(e,t)` | 94,583 | `new Pc({type:'literal',values:…})` = `z.literal` | `Pc` = `ZodLiteral` @94,291 |

`O`（object）的默认 catchall 由 `strip(){return this.clone(re(this._zod.def,{catchall:void 0}))}`（@92,164）给出 ⟹ **未声明键被静默剥离**。

### 1.4 两条读表须知（对本文所有表成立）

1. bootstrap 族的对象**全是裸 `O({…})`，一个 `.strict()` 都没有**（对照：全模块 `.strict()` 共 **766** 处；
   族内 282,113–286,300 这一段只有 **2** 处 `.strict()`：一处属于族前的 `startup/storagePathReady`（@282,113），
   一处是 union 成员 `telemetry-report`（@284,670））。非 strict 的对象在 zod 里默认 **strip**：
   载荷里多出来的键被**静默剥离**，不报错 —— 所以"schema 没写的键"永远到不了业务层。
2. 反过来，**必填键缺失 = 整帧 `safeParse` 失败 = 被 §1.1 的 `dS` 闸门整帧丢弃**（不是丢一个字段）。
   非 strict 只救"多键"，不救"缺键"。**这条是 §6.5 判缺陷的唯一依据。**

---

## 2. 载荷总览

应答方是**配对端（桌面）**；网页端与移动客户端都是**客户端**。union `uS` 的 21 个成员（`zcode_type:N(…)` 逐个取自 @284,627–286,597）：

| # | `zcode_type` | 声明 off | 方向 | 信封键 | `result` |
|---|---|---|---|---|---|
| 1 | `telemetry-report` | 284,627 | 客户端 → 桌面（**`.strict()`**） | `event` | — |
| 2 | `bootstrap-request` | 284,683 | 客户端 → 桌面 | `requestId` | — |
| 3 | **`bootstrap-response`** | 284,734 | 桌面 → 客户端 | `requestId`,`success`,`result` | `sS`（**tasks 必填**） |
| 4 | `workspace-list-request` | 284,810 | 客户端 → 桌面 | `requestId` | — |
| 5 | `workspace-list-response` | 284,866 | 桌面 → 客户端 | `requestId`,`success`,`result` | `cS`（tasks **可选**） |
| 6 | `workspace-list-updated` | 284,947 | 桌面 → 客户端（**推送**） | **无 `requestId`、无 `success`** | `cS` |
| 7 | `workspace-bridge-open` | 285,001 | 客户端 → 桌面 | `requestId`,`bridgeSessionId`,… | — |
| 8 | `workspace-bridge-ready` | 285,187 | 桌面 → 客户端 | 同上 + `bridge:iS` | — |
| 9 | `workspace-bridge-error` | 285,349 | 桌面 → 客户端 | `reason:rS`,`error:z.string()`（**都必填**） | — |
| 10/11 | `workspace-reconnect-request` | 285,532 | 客户端 → 桌面 | `requestId`,`workspaceKey` | — |
| 12/13 | `workspace-reconnect-response` | 285,608 / 285,699 | 桌面 → 客户端 | `success:true` 变体 / `success:false`+`error` 变体 | — |
| 14 | **`mobile-view-state-update`** | **285,800** | **客户端 → 桌面（单向）** | `viewState:aS`（**必填**）,`deviceInfo:oS.optional()` | — |
| 15–17 | `platform-request` / `-response`×2 | 285,884 / 285,964 / 286,050 | 双向 | `method:lS`,`args`/`result` | — |
| 18/19 | `rpc-frame` / `rpc-frame-ack` | `Dd` @129,158 / `Od` @129,854 | 双向（桥内） | 见 §3.2 | — |
| 20 | `bridge-degraded` | 286,141 | 桌面 → 客户端 | `bridgeSessionId`,`reason`(4 值) | — |
| 21 | `app-error` | 286,493 | 桌面 → 客户端 | `reason:rS`,`error`（都必填） | — |
| 22 | `mobile-diagnostic` | 286,597 | 客户端 → 桌面 | `event`(7 值),`timestamp` | — |

> 表里编号到 22 是因为 `workspace-reconnect-response` 与 `platform-response` 各有 success/fail 两个变体 —— **union 成员总数是 21**。

- **网页端发送点**（主包 `index-BGa3KiRe.js`）：`` let e=X9(`bootstrap`) `` @**6,579,011**（id 生成器 `function X9(` @6,565,911），
  请求形状 `{zcode_type:'bootstrap-request',requestId:e}`，等待谓词 `t=>t.zcode_type==='bootstrap-response'&&t.requestId===e`；
  `workspace-list-request` 构造 @**6,579,643**（`X9('workspace-list')` @6,579,643，`zcode_type:` 键 @6,579,674）；
  `platform-request` 构造 @**6,579,401**（`X9('platform')`）。
- **网页端从不发 `workspace-list-updated`**：主包 30 处 `zcode_type` 里它只出现在**消费分支**（@**6,578,052**），
  没有任何构造点。同样"只收不发"的还有 `bootstrap-response`、`workspace-bridge-ready`、`app-error`。
- **同源不同必填度**：`sS` 的 `tasks` 必填且额外带 `desktopAppVersion` + 两个 viewState；
  `cS` 的 `tasks` 可选、另有两个"当前焦点"键。同一个客户端解析器可通吃五条消息（`tasks` 缺了就是空）。
- **体量**：旧安卓壳注释记「响应约 24 KB」「每次 24–25 KB」，真机实测每轮 27,832 字符。
  **（外部输入：旧安卓壳真机日志与注释，非本次源码审计所得；该壳已不在工作树，见 §10）**

---

## 3. 信封层

### 3.1 业务信封（bootstrap / workspace-list 族）

| # | 字段 | 类型 | 必填 | 含义 |
|---|---|---|---|---|
| 1 | `zcode_type` | 字面量 | ✅ | 见 §2 |
| 2 | `requestId` | `$` 非空串（≤256，`[A-Za-z0-9._~-]`） | ✅（**`workspace-list-updated` 没有**） | 回显请求 id；网页端靠 `requestId===e` 配对 |
| 3 | `success` | `N(!0)` = 字面量 `true` | ✅ | **union 里没有 `success:false` 变体** —— 失败不走这一族，走 `app-error{reason:rS}`（§7.5）或超时 |
| 4 | `result` | 对象 | ✅ | `sS` / `cS`（§4–§7） |

### 3.2 传输信封（业务帧外面那一层）

| 层 | 形状（逐字） | 定位 off |
|---|---|---|
| 线上限 `bd` | `{maxPhysicalFrameBytes:Uu.maxFrameBytes(=1 MiB), maxMessageBytes:16 MiB, maxFragments:64, assemblyTimeoutMs:3e4, transportIdMaxChars:Uu.transportEnvelopeIdMaxChars}` | 128,552 |
| 传输 id `Cd` | `S().min(1).max(bd.transportIdMaxChars).regex(/^[A-Za-z0-9._~-]+$/u)` | 128,6xx |
| 桥标识 `wd` | `O({bridgeSessionId:Cd,bridgeGeneration:Sd.optional(),recoveryId:Cd.optional()}).strict()` | 128,862 |
| 校验和 `Td` | `` O({algorithm:N(`crc32`),value:S().regex(/^[0-9a-f]{8}$/u)}).strict() `` | 128,954 |
| rpc-frame `Dd` | `` O({zcode_type:N(`rpc-frame`),bridgeSessionId:Cd,bridgeGeneration:Sd.optional(),recoveryId:Cd.optional(),seq:xd,messageSeq:xd,fragmentIndex:w().int().nonnegative().max(bd.maxFragments-1),fragmentCount:w().int().positive().max(bd.maxFragments),messageBytes:w().int().positive().max(bd.maxMessageBytes),checksum:Td,dataBase64:S().min(4).max(bd.maxPhysicalFrameBytes).refine(Ed)}).strict().superRefine(…) `` + 两条 superRefine（`fragmentIndex must be smaller than fragmentCount`、`non-empty fragments cannot exceed message bytes`） | 129,158 |
| rpc-frame-ack `Od` | `` O({zcode_type:N(`rpc-frame-ack`),bridgeSessionId:Cd,bridgeGeneration:Sd.optional(),recoveryId:Cd.optional(),ackMessageSeq:xd}).strict() `` | 129,854 |
| 帧联合 `kd` | `k([Dd,Od])` | 129,993 |
| 帧域 data 封套 | `` O({type:N(`data`),payload:kd,client_ts:Sd.optional(),server_ts:Sd.optional()}).strict() ``（`xd=w().int().positive().max(2**53-1)`、`Sd=w().int().nonnegative().max(2**53-1)`） | 130,007 |

⚠️ **别把最后一条当成业务帧的信封**：它是**帧域**的 `data` 封套（payload 只允许 rpc-frame/ack）。
线上跑的业务帧是把业务对象**直接**放进 `{type:'data', payload:<业务对象>, client_ts}` ——
网页端序列化器在 `sendPayloadResult` 段（@**6,524,304** 起，`this.payloadSerializer.prepare(e)`）；
接收侧 `case\`data\`: this.handleDataPayload(t.payload)`（主包 @**6,526,690**）。

**收帧闸门（主包，逐字 @6,528,315）**：

```js
handleDataPayload(e){ if(U3n(e)&&this.options.onRawTransportPayload?.(e))return; let t=dt(e); t&&this.options.onPayload(t) }
// dt = validation-CqXZOLXO.js 导出的 dS（`dS as t` @307,125 → 主包 `t as dt` @61,548 那条导入表）
// U3n(e) = e.zcode_type==='rpc-frame'||'rpc-frame-ack'（@6,521,679）
```

即：**`rpc-frame`/`rpc-frame-ack` 先交桥，其余业务帧必须过 `uS.safeParse`，过不了就整帧丢弃（静默，无日志）。**

---

## 4. `result.workspaces[i]` —— schema `tS`（8 键，off 282,217）

逐字（@282,217 起）：

```js
tS=D(O({workspacePath:$,workspaceIdentity:$.optional(),remoteSessionId:$.optional(),label:$,
        workspacePurpose:M([`project`,`conversation`]).optional(),kind:M([`local`,`remote`]),
        connectionState:M([`connected`,`disconnected`,`reconnecting`]).optional(),
        lastConnectionError:S().optional()}))
```

| # | 字段 | 类型 | 必填 | 枚举/取值 | 含义 |
|---|---|---|---|---|---|
| 1 | `workspacePath` | 非空串 | ✅ | — | 工作区绝对路径；与 identity 一起构成**工作区键**（§4.1） |
| 2 | `workspaceIdentity` | 非空串 | ◻ | — | 远程工作区身份 id；**工作区键优先用它** |
| 3 | `remoteSessionId` | 非空串 | ◻ | — | 远程会话 id；"该工作区能否开远程桥"的第二半（§4.2） |
| 4 | `label` | 非空串 | ✅ | — | 显示名 |
| 5 | `workspacePurpose` | enum | ◻ | `project` \| `conversation` | `conversation` = 配对端内置"默认会话"伪工作区 |
| 6 | `kind` | enum | ✅ | `local` \| `remote` | 本地/远程 |
| 7 | `connectionState` | enum | ◻ | `connected` \| `disconnected` \| `reconnecting` | 远程工作区连接态 |
| 8 | `lastConnectionError` | 字符串 | ◻ | 自由文本 | 最近一次连接失败原因 |

**键名与旧基线逐字一致，8 键不变。** 变化的只有"谁在用它"（见 17 篇）。

### 4.1 工作区键规则（网页端权威）

规则**仍是** **`workspaceIdentity?.trim() || workspacePath`（identity 优先，顺序不能反）**。
本代在主包里能找到的实现点比旧基线记录的 4 处**多得多**，分三类：

| 形态 | 本代符号与位置（主包字节偏移） | 说明 |
|---|---|---|
| **对象形（本代新增，最重要）** | ① `src-AL-_ONNp.js` @**63,863**：`function U(e){return e.workspaceIdentity?.trim()\|\|e.workspacePath}` —— 该模块导出 `U as j`（@80,934 导出块），主包以 `j as ja` 导入（@64,442），relay 客户端到处用 `ja(...)`<br>② 主包 @**1,386,011**：`function bO({workspacePath:e,workspaceIdentity:t}){return t?.trim()\|\|e}`<br>③ 调用点示例（对象形传入）：主包 @**3,261,030** 的 `x7t` —— `` for(let n of e){if(!VT(n)\|\|n.workspacePurpose===\`conversation\`\|\|x4e(n))continue; let e=b({workspacePath:n.workspacePath,workspaceIdentity:n.workspaceIdentity}); …} ``（工作区标签页分组；**这里同时演示了 `workspacePurpose==='conversation'` 的过滤**） | 对象形是 relay 客户端（`ja`）与标签页分组（`b`/`bO`）共用的形态 |
| 双参函数形 | `tE` @1,209,687；`DAt` @2,095,874；`lV` @2,107,230；`_V` @2,125,371；`CV` @2,130,582；`rNt` @2,150,736；`jwn` @4,615,111；`L5` @5,989,981 —— 函数体**逐字相同**：`return t?.trim()\|\|e` | 8 处独立实现 |
| 拼接形（键里再塞后缀） | `Nvt` @1,755,312（`` `${Avt}:${t?.trim()\|\|e}` ``）；`Gz` @1,989,942 / `gDt` @1,991,777 / `e_n` @4,060,209（`` `${n?.trim()\|\|e}::${…}` ``）；`Ojt` @2,109,058（`\u0000` 分隔） | 5 处 |
| **内联表达式** | `workspaceIdentity?.trim()\|\|` 在主包共 **111** 处命中（含上述函数的调用点与内联点） | 例：@70,323 `Ds(e){return JSON.stringify([e.workspaceIdentity?.trim()\|\|e.workspacePath,e.sessionId])}`、@923,871（bash-output 侧栏键）、@1,106,871（local-ttft 观察）、@6,569,643 / @6,569,875（web-remote 服务桩的 `workspaceKey`） |

另有一处**带空值归零**的变体（不要与上面混）：
`Bx({workspacePath:e,workspaceIdentity:t,taskId:n}){let r=t?.trim()||e; return r.trim()?r:null}`（@923,7xx）——
identity 与 path 都空时返回 `null` 而不是空串。

relay 客户端挑工作区用的就是对象形 `ja`（`src-AL-_ONNp.js` 的 `U`）：

```js
// 主包 @6,584,254 起（Web remote 接管时挑工作区）
i = r ? { workspaceKey: ja(r), taskId: e?.preferredTaskId,
          canBridge: r.kind !== 'remote' || !!(r.workspaceIdentity && r.remoteSessionId) }  // canBridge @6,584,266
      : I6n(n.result)                        // I6n(e){return $te(e)} @6,565,517 → 兜底挑选器（src-AL 的 Ud）
// 同一段的前半句：t.workspaces.find(t => ja(t) === e.preferredWorkspaceKey)
```

### 4.2 网页端对 `workspaces` 的派生判定

`canBridge` 判据（@6,584,266）：**本地工作区恒可桥；远程工作区必须 `workspaceIdentity` 与 `remoteSessionId` 同时非空**。
同一判据也被抽成独立函数 `Hd`（`src-AL-_ONNp.js` @63,929：`function Hd(e){return e.kind!=='remote'||!!(e.workspaceIdentity&&e.remoteSessionId)}`）。

兜底挑选器 `Ud`（`src-AL-_ONNp.js` @64,013，导出为 `Ud as k` → 主包 `I6n`）逐字：

```js
function Ud({workspaces:e,mobileViewState:t,initialViewState:n}){
  let r=e.filter(Hd), i=new Set(r.map(e=>U(e))), a=new Set(e.map(e=>U(e))),
      o=t??n,                       // ← mobileViewState 优先于 initialViewState
      s=o?.activeWorkspaceKey;
  if(s&&i.has(s)) return {workspaceKey:s, ...o.activeTaskId?{taskId:o.activeTaskId}:{}, canBridge:!0};
  let c=r[0];
  if(!c){ if(s&&a.has(s)) return {workspaceKey:s,...o?.activeTaskId?{taskId:o.activeTaskId}:{},canBridge:!1};
          let t=e[0]; return t?{workspaceKey:U(t),canBridge:!1}:null }
  return {workspaceKey:U(c),canBridge:!0}
}
```

⟹ **"`mobileViewState` 优先于 `initialViewState`"这条合并在 relay 路径上也成立**（不只是 REST 路径，见 §9）。

---

## 5. `result.tasks[i]` —— schema `nS`（**16 键**，off 282,649）

逐字（@282,649 起）：

```js
nS=D(O({taskId:$,title:S(),workspacePath:$,workspaceIdentity:$.optional(),remoteSessionId:$.optional(),
        workspaceLabel:$,workspaceKind:M([`local`,`remote`]),createdAt:w().finite(),updatedAt:w().finite(),
        provider:Ou.optional(),unreadAt:w().finite().optional(),
        displayStatus:M([`idle`,`running`,`completed`,`error`]).optional(),
        hasBackgroundWork:T().optional(),workflowActivity:ud.optional(),
        pinned:T().optional(),archived:T().optional()}))
```

| # | 字段 | 类型 | 必填 | 枚举/取值 | 与旧基线 |
|---|---|---|---|---|---|
| 1 | `taskId` | 非空串 | ✅ | — | 不变 |
| 2 | `title` | `z.string()`（**无 min，可为空串**） | ✅ | — | 不变 |
| 3 | `workspacePath` | 非空串 | ✅ | — | 不变 |
| 4 | `workspaceIdentity` | 非空串 | ◻ | — | 不变 |
| 5 | `remoteSessionId` | 非空串 | ◻ | — | 不变 |
| 6 | `workspaceLabel` | 非空串 | ✅ | — | 不变（**不是键**） |
| 7 | `workspaceKind` | enum | ✅ | `local` \| `remote` | 不变 |
| 8 | `createdAt` | number(finite) | ✅ | — | 不变 |
| 9 | `updatedAt` | number(finite) | ✅ | — | 不变 |
| 10 | `provider` | enum `Ou` | ◻ | **只有 `glm`** | **枚举从 5 值缩到 1 值** |
| 11 | `unreadAt` | number(finite) | ◻ | — | 不变 |
| 12 | `displayStatus` | enum | ◻ | `idle` \| `running` \| `completed` \| `error` | 不变 |
| 13 | `hasBackgroundWork` | `z.boolean()`（`T()`） | ◻ | — | **新增** |
| 14 | `workflowActivity` | `ud` | ◻ | 见下 | **新增** |
| 15 | `pinned` | boolean | ◻ | — | 不变 |
| 16 | `archived` | boolean | ◻ | — | 不变 |

**`provider` 缩成单值（@116,320）**：`var Ou=M(['glm'])`。同模块另有一个**旧值归一化**枚举
`Jd=M(['claude','opencode','gemini','codex','glm']).transform(()=>'glm')`（@139,148 附近），
它是**历史迁移用**的（把旧值一律折成 `glm`），不是 `nS` 用的那个。别把 `Jd` 的 5 值当成本族 `provider` 的取值。

**`workflowActivity`（`ud`，@126,013）形状**（用于卡片上"后台在跑几条工作流"）：

```js
cd=M([`pending`,`running`,`done`,`failed`])                       // 阶段状态
ld=O({name:S().min(1).max(R.maxPhaseNameLength),status:cd,
      alongside:D(w().int().nonnegative()).max(R.maxPhases).optional(),
      hole:M([`open`,`waiting`]).optional()})                    // 单个 phase
ud=O({runs:D(O({runId:S().min(1),toolCallId:S().min(1).optional(),name:S().min(1).optional(),
                status:od.shape.status,stopReason:od.shape.stopReason,startedAt:I.optional(),
                phases:D(ld).max(R.maxPhases),
                currentPhase:S().min(1).max(R.maxPhaseNameLength).optional(),
                agentsWorking:w().int().nonnegative(),
                waitingHole:S().min(1).max(R.maxPhaseNameLength).optional()})).max(4)})
```

`hasBackgroundWork` 是**布尔**（`T()` = `_a(dc,e)` @90,372，`dc` = `ZodBoolean` @90,279），与 `pinned`/`archived` 同型。
（注意别与本代另一个易混别名 `E()` 搞混：`E()` @90,889 是 **`z.unknown()`**，用在 `platform-response.result` 上。）

**`pinned`/`archived`/`hasBackgroundWork` 是 true-only**：网页端 digest 构造器（主包 @4,424,3xx–4,424,7xx）逐字写
`` …createdAt:e.createdAt,updatedAt:e.updatedAt,…e.provider?{provider:e.provider}:{},…typeof e.unreadAt==\`number\`?{unreadAt:e.unreadAt}:{},displayStatus:c,…u?{hasBackgroundWork:!0}:{},…d?{workflowActivity:d}:{},…r?{pinned:!0}:{},…i?{archived:!0}:{} ``（`pinned:!0` @**4,424,590**、`archived:!0` @**4,424,610**），
即 `false` 从不写入，缺省即 false；同一构造器尾部 `.sort(cbn('updated'))`（@4,424,66x）—— **网页端按 `updatedAt` 排序**。

**`unreadAt` 是 CAS 令牌，不是布尔**：网页端标已读时把它作为乐观并发期望值
（`setTaskUnread({…,unread:!1,expectedUnreadAt:y})`，主包 `expectedUnreadAt` @**5,731,723**；`setTaskUnreadIndicator` @1,371,021 一带）。
脱离"标记已读"单独读它，只能当"有未读"用。

**两个状态域不要混**：`displayStatus` 是本族里**唯一**的状态字段（扁平四值）。
`liveStatus`（`idle|running|waiting|completed|error`）与 `activity.phase`
（`prewarming|running|completedSuccess|completedInterrupted|error|draft`）属于**桥内 controller 订阅域**，
**不在本族 schema 里** —— `nS` 里既没有 `liveStatus`，也没有 `waiting`/`prewarming`。

---

## 6. `initialViewState` / `mobileViewState`，与 `mobile-view-state-update`

### 6.1 `result` 顶层 —— schema `sS`（**6 键**，off 284,202）

逐字：

```js
sS=O({windowControlSessionId:$,desktopAppVersion:$.optional(),workspaces:tS,tasks:nS,
      initialViewState:aS.optional(),mobileViewState:aS.optional()})
```

| # | 字段 | 类型 | 必填 | 含义 | 与旧基线 |
|---|---|---|---|---|---|
| 1 | `windowControlSessionId` | 非空串 | ✅ | **bootstrap 独有**。桌面"窗口控制会话"标识 | 不变 |
| 2 | `desktopAppVersion` | 非空串 | ◻ | **新增**。桌面的应用版本 | **新增** |
| 3 | `workspaces` | `tS` 数组 | ✅ | §4 | 不变 |
| 4 | `tasks` | `nS` 数组 | ✅ | §5 | 不变 |
| 5 | `initialViewState` | `aS` | ◻ | 接通时刻页面的视图 | 不变 |
| 6 | `mobileViewState` | `aS` | ◻ | 本移动连接上次上报的视图 | 不变 |

**`desktopAppVersion` 的用途**（主包 @6,548,337 起的 `s6n`，逐字）：

```js
let s=a.desktopAppVersion?.trim();
if(!s)return a;
let c=new URL(e.getUrl());
if(c.pathname.replace(/\/+$/,``)!==`/remote/v4`||c.searchParams.get(`app_version`)===s)return a;
c.searchParams.set(`app_version`,s), r=c.toString(), e.isPageReady()?e.onMismatch(s):o()
```

⟹ 网页端拿到 bootstrap 后，会把 `desktopAppVersion` 与当前 URL 的 `app_version` 比对；
不一致就**把页面导航到带正确 `app_version` 的 URL**（必要时先弹确认框 `c6n`）。这是本代新增的"网页版本与桌面版本对齐"机制。

`windowControlSessionId` 在网页端的用法（主包）：`@6,564,666`（WS ready 回传进 `{token,relayOrigin,windowControlSessionId,mobileConnectionId,…}`）、
`@6,585,095` / `@6,585,126`（web-remote 会话状态）、`@4,514,820` / `@4,658,060` 一带（状态比较器）。

### 6.2 视图状态 —— schema `aS`（3 键，off 283,744）

逐字：`aS=O({activeWorkspaceKey:$.optional(),activeTaskId:$.optional(),updatedAt:w().finite()})`

| # | 字段 | 类型 | 必填 | 含义 |
|---|---|---|---|---|
| 1 | `activeWorkspaceKey` | 非空串 | ◻ | 焦点工作区键（规则同 §4.1） |
| 2 | `activeTaskId` | 非空串 | ◻ | 焦点任务 |
| 3 | `updatedAt` | number(finite) | **✅** | 该视图的时间戳（**`aS` 里唯一必填键**） |

### 6.3 `mobile-view-state-update`（union 成员 @285,800）

逐字：`` O({zcode_type:N(`mobile-view-state-update`),viewState:aS,deviceInfo:oS.optional()}) ``

- **方向：客户端 → 桌面端，单向。** 网页端**只发不收**（主包 @**6,579,843** 是唯一构造点，
  全主包无任何消费分支）。所以 `viewState`/`deviceInfo` 的**校验者只有桌面端**（见 §6.5）。
- `viewState` **必填**、形状 = `aS`（同一 schema 复用 ⟹ 回执里 `updatedAt` 也必填）。
- 网页端发送点（主包 @6,579,843 起，逐字）：

```js
B=async(e,n)=>{s=e,c=n,j.sendPayload({zcode_type:`mobile-view-state-update`,
   viewState:{activeWorkspaceKey:e,…n?{activeTaskId:n}:{},updatedAt:Date.now()},
   deviceInfo:o6n({appVersion:t.appVersion})})}
```

- builder `o6n` 在 @**6,548,210**，逐字尾部：`…,online:…,updatedAt:t()}`（`t=()=>Date.now()`）——
  **网页端自己发的 `deviceInfo` 是带 `updatedAt` 的**，并带小写 `timezone`。
- 消费端是**桌面自己**（把 `viewState`/`deviceInfo` 存到会话对象上，随后在 `bootstrap-response.mobileViewState`
  与 `workspace-list-*.activeWorkspaceKey` 里回显），以及 REST 通道的合并规则 `mobileViewState ?? initialViewState`（§9）。

### 6.4 `deviceInfo` —— schema `oS`（12 键，off **283,833**）★

逐字（@283,833 起，**完整原文**）：

```js
oS=O({platform:$,version:$,name:$,userAgent:S().optional(),language:S().optional(),
      languages:D(S()).optional(),browserPlatform:S().optional(),
      viewport:O({width:w().finite(),height:w().finite(),devicePixelRatio:w().finite()}).optional(),
      screen:O({width:w().finite(),height:w().finite()}).optional(),
      timezone:S().optional(),online:T().optional(),updatedAt:w().finite()})
```

| # | 字段 | 类型 | **必填** | 约束 | 网页端实际填法（`o6n`，@6,548,210–6,549,007，共 797 B） |
|---|---|---|---|---|---|
| 1 | `platform` | `$` 非空串 | **✅** | trim 后 ≥1 字符 | 固定 `'web'` |
| 2 | `version` | `$` 非空串 | **✅** | trim 后 ≥1 字符 | `appVersion` 或 `'web'` |
| 3 | `name` | `$` 非空串 | **✅** | trim 后 ≥1 字符 | 固定 `'mobile-browser'` |
| 4 | `userAgent` | `z.string()` | ◻ | — | `navigator.userAgent` |
| 5 | `language` | `z.string()` | ◻ | — | `navigator.language` |
| 6 | `languages` | `z.array(z.string())`（`D(S())`） | ◻ | — | `navigator.languages`（过滤空串） |
| 7 | `browserPlatform` | `z.string()` | ◻ | — | `navigator.platform` |
| 8 | `viewport` | object | ◻ | `width`/`height`/`devicePixelRatio` 三个 `number(finite)` **都必填** | `innerWidth/innerHeight/devicePixelRatio` |
| 9 | `screen` | object | ◻ | `width`/`height` 两个 `number(finite)` **都必填** | `screen.width/height` |
| 10 | `timezone` | `z.string()` | ◻ | — | `Intl.DateTimeFormat().resolvedOptions().timeZone` —— **全小写 `timezone`** |
| 11 | `online` | `z.boolean()` | ◻ | — | `navigator.onLine` |
| 12 | `updatedAt` | `w().finite()` = **`z.number()`** | **✅（无 `.optional()`）** | 只要求 number 且有限（**没有** int / 非负 / 范围约束） | `Date.now()` |

**结论（问题 1）：3.15.3 的 `deviceInfo` 确实要求 `updatedAt`。** 证据是 §6.4 的逐字原文：
`updatedAt:w().finite()` 结尾，**没有** `.optional()`；而同一对象里 12 个键中有 8 个显式带 `.optional()`，
证明作者是逐个标注可选的 —— `updatedAt` 不是漏标。**旧基线结论成立，未被推翻。**

**约束总结（问题 1 的"逐键约束"列）**：只有 `platform`/`version`/`name` 有"非空（trim 后 ≥1）"约束；
其余全是裸 `z.string()` / `z.number()` / `z.boolean()`，**无枚举、无正则、无范围**。
`viewport`/`screen` 若出现，其内部三个/两个数值键**必须齐全**（否则内层 object 校验失败 ⟹ 同样整帧失败）。

### 6.5 【问题 2 / 问题 3】缺 `updatedAt` 的实际后果 —— 证据链

**（a）该 schema 是哪个方向的帧在用它？**

`oS` 在**整个 3.15.3 快照里只被引用一次**：union 成员 `mobile-view-state-update`（@285,800）的
`deviceInfo:oS.optional()`。没有第二处。该帧方向为**客户端 → 桌面端**：

| 侧 | 行为 | 坐标 |
|---|---|---|
| 网页端 | **只发不收**：唯一构造点 `B=async(e,n)=>{…j.sendPayload({zcode_type:'mobile-view-state-update',…})}`；全主包 30 处 `zcode_type` 里没有该类型的比较/消费分支 | 主包 @6,579,843 |
| 网页端 | 发的 `deviceInfo` **带** `updatedAt`（`o6n` 尾部 `updatedAt:t()`） | 主包 @6,548,210 |
| 桌面端 | **接收 + 校验 + 分发** | `out/main/index.js` @405,427 `case "mobile-view-state-update": ho(h, C.viewState, C.deviceInfo)`；校验闸门见下 |

**（b）谁调用 `safeParse`，失败时做什么？**

```js
// 桌面端（3.15.3 同构 Zod；交叉校验用，不展开）
handleDataPayload(t){ if(this.state!=="paired"||r0(t)&&this.options.onRawTransportPayload?.(t))return;
  let r=vf(t);                                   // vf = parseWebRemoteControlAppPayload = UD.safeParse
  if(!r){ this.options.logger.warn("[web-remote-control] invalid external relay payload dropped",{...hS(t)}); return }
  this.options.onPayload(r) }                    // → routePayload 的 switch
// out/main/index.js:408 @384,894（vf 的别名 `Lb as vf` @744；解析器本体 chunk-OW6EUS2G.js:64 @728,202
//   `function qee(t){let n=UD.safeParse(t);return n.success?n.data:null}`）
```

⟹ 失败时：**返回 `null` → 整帧被丢弃 → 只落一行 `warn` 日志 → 不抛错、不回错误帧、不重试。**
`routePayload` 的 `case "mobile-view-state-update"` 根本不会被进入。

网页端那一侧同理：`let t=dt(e); t&&this.options.onPayload(t)`（主包 @6,528,394）——
失败即 `t` 为 `null`，**静默丢弃，连日志都没有**。所以**无论哪一端做校验，缺必填键的后果都是"整帧消失"**，
这正是 §1.4 第 2 条说的"非 strict 只救多键，不救缺键"。

**（c）缺 `updatedAt` 时 `mobile-view-state-update` 会怎样？**

`deviceInfo` 是 `.optional()`，所以**完全不发 `deviceInfo`** 是合法的（`undefined` 不进入校验）；
但只要**发了** `deviceInfo`，它就必须满足 `oS` 的 12 键约束 —— **缺 `updatedAt` ⟹ `oS` 校验失败 ⟹ 整个
`mobile-view-state-update` 帧校验失败 ⟹ 整帧被接收侧丢弃**。

**（d）后果的范围（结论）**：只有"桌面端记住移动端在看什么"这一件事丢失，**不影响配对、bootstrap、开桥、任务列表**：

```js
// 桌面端：收到该帧后唯一动作就是把两个值挂到会话对象上
function ho(h,C,A){h.mobileViewState=C, A&&(h.mobileDeviceInfo=A)}   // out/main/index.js:408 @404,331
// 这两个值的下游用途：
function T(h){return {windowControlSessionId:h.deviceSid, desktopAppVersion:e.appVersion,
                      workspaces:M(h), tasks:E(h), initialViewState:O(h),
                      mobileViewState:h.mobileViewState}}              // buildBootstrapResult @398,715
function W(h){…, activeWorkspaceKey: h.mobileViewState?.activeWorkspaceKey ?? A?.activeWorkspaceKey ?? Ve(C),
                   activeTaskId:      h.mobileViewState?.activeTaskId      ?? h.currentBridge?.initialTaskId ?? A?.activeTaskId}
                                                                       // buildWorkspaceListResult @398,733
```

即：`h.mobileViewState` 永远停在旧值/未定义 ⟹
① `bootstrap-response.mobileViewState` 不带（或缺）该字段；
② `workspace-list-*.activeWorkspaceKey` 退到 `initialViewState`（桌面自己当前看的）或第一个可桥工作区。
**移动端仍然能拿到全量 `tasks`、仍然能开桥、配对不受影响** —— 所以 Dart 参考实现"能工作"与这条缺陷**并不矛盾**：
它丢的是一条"记住上次焦点"的软状态，不是主链路。

**（e）未能证实的部分**：
- **没有真机抓包**证明桌面端确实丢过帧。以上是"schema 必填 + 校验闸门语义 + 分发点"三段静态推导，
  链路闭合但缺一帧线上样本。**要坐实需要**：桌面端日志里出现
  `[web-remote-control] invalid external relay payload dropped` 且 `zcode_type=mobile-view-state-update`（桌面端 logger 已具备该行），
  或浏览器 console 侧对称证据（网页端静默丢弃，无日志，只能靠 `mobile-diagnostic` 侧证）。
- 也**未验证**"移动端是否真的依赖 `mobileViewState` 回显"——当前工作树的 Kotlin 投影根本不读
  `mobileViewState`（见 17 篇 §2.5），所以在这份代码上即使修好也不会立刻有可见收益。

### 6.6 客户端侧对照（**外部输入 / 参考实现，非本次快照所得**）

| 实现 | `deviceInfo` 实际发出的键 | 结论 |
|---|---|---|
| 网页端 `o6n`（3.15.3 主包 @6,548,210） | `platform,version,name,userAgent?,language?,languages?,browserPlatform?,viewport?,screen?,timezone?,online?,updatedAt` | **合规**（带 `updatedAt`、小写 `timezone`） |
| Dart 参考实现 `Reference_code/lib/protocol/zemote_client.dart:389-403` | `{platform, version, name}` —— **3 键，无 `updatedAt`** | **不合规**（缺必填键） |
| 本仓 Rust 移植 `rust/crates/protocol/src/device_info.rs`（`DeviceInfo` 结构体）+ `zemote_client.rs:2462` `mobile_view_state_update_payload()` | `{platform, version, name}` —— **3 键，无 `updatedAt`**（`device_info.rs` 注释写"线上字段名（已冻结，不可改）：`platform`、`version`、`name`"） | **不合规**（同缺陷） |
| 旧安卓壳 `Tier2Probe.kt:449-457` | `{platform,version,name,language,timeZone}` | **双重不合规**：`timeZone`（大写 Z）不在 schema 里 ⟹ 被 strip；且缺 `updatedAt` |

> ⚠️ `rust/crates/protocol/src/device_info.rs` 把 `{platform,version,name}` 当作"已冻结"的线上形状，
> 依据是 `zemote_client.dart:396-401` 与 `relay_client.dart:163-168` 两处**客户端自己的**内联 map。
> **客户端发什么 ≠ schema 要求什么。** schema 是 `oS`（12 键、`updatedAt` 必填），
> 客户端 3 键是**缺陷**而不是"冻结的协议"。这条要在移植收口时明确。

---

## 7. `workspace-list` 族结果形状 `cS` 与增量语义

### 7.1 `cS`（4 键，off 284,350）

逐字：`cS=O({workspaces:tS,tasks:nS.optional(),activeWorkspaceKey:$.optional(),activeTaskId:$.optional()})`

| # | 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| 1 | `workspaces` | `tS` 数组 | ✅ | 同 §4 |
| 2 | `tasks` | `nS` 数组 | ◻ | 同 §5，但**可选**（bootstrap 里必填） |
| 3 | `activeWorkspaceKey` | 非空串 | ◻ | 桌面认为的当前焦点工作区 |
| 4 | `activeTaskId` | 非空串 | ◻ | 当前焦点任务 |

### 7.2 业务增量只有一条：`workspace-list-updated`

`` O({zcode_type:N(`workspace-list-updated`),result:cS}) ``（@284,947）—— **无 `requestId`、无 `success`**，
桌面**主动推送**的整份 `cS` 快照（**不是字段级差分**）。

网页端处理（主包 @6,578,052 的分发分支 + @6,575,959 / @6,576,0xx 的处理器）：

```js
// 分发（在 safeParse 之外，因为它没有 requestId 可配对）
if(e.zcode_type===`rpc-frame`||e.zcode_type===`rpc-frame-ack`){ g?.acceptPayload(e); return }
if(!M.acceptPayload(e)){
  if(e.zcode_type===`workspace-list-updated`){ S(e.result); return }
  if(e.zcode_type===`bridge-degraded`){ … }
  if(e.zcode_type===`workspace-bridge-error`){ … }
  e.zcode_type===`app-error` && E(q9(e.reason,e.error))
}
// 处理器
b=e=>(y.add(e),()=>{y.delete(e)}),                                   // y = Set<订阅者>
x=e=>({...e, activeWorkspaceKey:e.activeWorkspaceKey??s, activeTaskId:e.activeTaskId??c}),   // @6,575,959
S=e=>{let t=x(e); for(let e of y)e(t)}                               // @6,576,0xx 广播
```

即：网页把 `workspace-list-updated` 当**活的工作区/任务列表源**，并用本地已上报的焦点补齐两个可选键后广播给订阅者。
**是否也推给移动客户端，源码判不出**（见 17 篇 §6③）。

### 7.3 传输层增量：分片与重组（fragment 族）

大帧在 rpc-frame 里被切片，字段与上限见 §3.2：`fragmentIndex`/`fragmentCount`/`messageBytes`/`dataBase64`/`checksum{crc32}`，
上限 `maxFragments:64`、`maxPhysicalFrameBytes:1 MiB`、`maxMessageBytes:16 MiB`、`assemblyTimeoutMs:30000`；
组装层参数在 `Uu`（`logicalFrameAssemblyMaxBytes:16 MiB`、`logicalFrameAssemblyMaxFragments:1024`、
`logicalFrameAssemblyMaxConcurrent:32`、`logicalFrameAssemblyTimeoutMs:3e4`）。
校验和基类 `Td` @128,954；帧联合 `kd=k([Dd,Od])` @129,993。

### 7.4 桥内流式增量（**不在本族**）

`text_delta`/`reasoning_delta`/`tool_input_delta`、`turn-started`/`turn-completed`/`turn-failed`
都是**桥内 RPC/session 事件**的形状，靠 `workspace-bridge-open` 之后的 rpc-frame 流承载。
bootstrap 载荷**没有任何流式字段**：它是配对那一刻的一次性快照。
（对照：`tasks-index` 在本模块里只是**数据库迁移的 `databaseKind` 枚举值** —— `j(['tasks-index','session'])`，
off **99,856** 与 **234,366** —— 不是 relay 消息名。）

### 7.5 同族/邻接信封

| 消息 | 关键字段（off） |
|---|---|
| `workspace-bridge-open` | `requestId`,`bridgeSessionId:eS`,`bridgeGeneration?:int≥0`,`recoveryId?:eS`,`workspaceKey:$`,`taskId?:$`（285,001） |
| `workspace-bridge-ready` | 同上 + `bridge:iS`（285,187） |
| `workspace-bridge-error` | `requestId`,`bridgeSessionId?`,`bridgeGeneration?`,`recoveryId?`,`reason:rS`,`error:z.string()`（285,349）——**`reason` 与 `error` 都必填** |
| `workspace-reconnect-request` | `requestId`,`workspaceKey`（285,532；形状另在 282,4xx 定义过一次） |
| `workspace-reconnect-response` | `success:true` 变体（285,608）／`success:false`+`error` 变体（285,699） |
| `platform-request` / `-response` | `method:lS`、`args?:unknown` / `result:unknown`（285,884 / 285,964 / 286,050） |
| `bridge-degraded` | `bridgeSessionId`,`bridgeGeneration?`,`recoveryId?`,`reason:j(['rpc-transport-fault','rpc-frame-gap','buffer-overflow','buffer-timeout'])`,`seq?`,`expectedSeq?`,`droppedCount?`（286,141） |
| `app-error` | `requestId?`,`bridgeSessionId?`,`reason:rS`,`error:z.string()`（286,493） |
| `mobile-diagnostic` | `event:j([7 值])`,`timestamp:int≥0`,`state?`,`previousState?`,`pairStatus?:waiting\|matched`,`closeCode?`,`closeReason?`,`wasClean?`,`wasPaired?`,`failureReason?:rS`,`failureMessage?`,`visibilityState?`,`online?`,`hiddenDurationMs?`（286,597） |
| `telemetry-report`（**本代新增**） | `` O({zcode_type:N(`telemetry-report`),event:Sl}).strict() ``（284,627）；`Sl` @100,400 = `{elementName:'session_create',eventRegion:'app',eventType:'result',talkId,messageId,context:{clientTimezone,clientLanguage,screenResolution},eventExtraDetail:{create_source:…}}` |

`lS`（platform 方法，7 值，off 284,450）：`isDockerAvailable`,`listWSLDistros`,`listDockerContainers`,
`listSSHConfigAliases`,`loadMcpFromUserDirectory`,`saveMcpToUserDirectory`,`migrateLegacyCommonMcp`。

**`bridge` —— schema `iS`（按 `kind` 判别，off 283,340）**：

- `kind:'local'` → `{bridgeSessionId:eS, bridgeGeneration?:int≥0, recoveryId?:eS, kind, workspaceKey:$, workspacePath:$, initialTaskId?:$}`
- `kind:'remote'` → 同上，但 `kind:'remote'` 且**多 `workspaceIdentity:$` + `remoteSessionId:$`（都必填）**

**失败原因枚举 —— schema `rS`（11 值，off 283,087）**：
`session-not-found`,`session-expired`,`session-conflict`,`workspace-closed`,`desktop-disconnected`,
`invalid-mobile-connection`,`desktop-bootstrap-timeout`,`connection-recovery-timeout`,`relay-unavailable`,
`unsupported-action`,`unexpected-error`。
同一个 `rS` 被 `workspace-bridge-error.reason`、`app-error.reason`、`mobile-diagnostic.failureReason` 三处复用。

---

## 8. 同模块里容易被误认成 bootstrap 的形状（锚点重定位）

同一个 schema 模块里混装了好几个协议域。以下名字常被当成 bootstrap 族的一部分，实测都**不是**：

| 名字 | 实际位置（本代） | 真实归属 |
|---|---|---|
| `independentPlanState` | off **230,463** | **桌面 host RPC 协议的能力位**，与 relay 的 bootstrap/workspace-list 载荷无关 |
| `zcode-hello` / `zcode-hello-ack` | off **287,801** / 287,876：`` O({type:N(`zcode-hello`),version:S(),platform:S(),arch:S(),pid:w().int()}) ``、`` O({type:N(`zcode-hello-ack`),version:S(),clientId:$}) `` | host RPC 握手，**不在 `uS` union 里**（`uS` 只收 `zcode_type` 打头的消息，hello 用 `type`） |
| `tasks-index` | `j(['tasks-index','session'])`，off **99,856** / **234,366** | **数据库迁移的 `databaseKind` 枚举**，不是订阅名 |
| `snapshot` / 其余 `*snapshot*` | 散落在各域错误码 / 平台方法 / 资源名里 | 各自域内，与 bootstrap 无关 |
| `delta` / `extra_delta` | 缓存 manifest 的 kind 表 | 同上 |

---

## 9. HTTP / WebSocket 同形状通道（主包，不走 relay）

| 通道 | 形状 | 定位 off |
|---|---|---|
| REST 工作区列表 | `GET {relayOrigin}/api/remote-control/windows/bootstrap/{token}`（URL 串 @**6,556,892**）→ 解析器 `O6n` 返回 `{workspaces, tasks, activeWorkspaceKey: n.mobileViewState?.activeWorkspaceKey ?? n.initialViewState?.activeWorkspaceKey, …}` | 6,556,892 / **6,557,212**（合并点） |
| REST 桥开启 | `POST …/workspace-bridge`，头 `X-ZCode-Mobile-Connection-Id`（@**6,557,563**） | 6,557,563 |
| REST 视图上报 | `POST …/mobile-view-state`（@**6,558,047**），body `{activeWorkspaceKey, activeTaskId?, updatedAt:Date.now(), deviceInfo:o6n()}` | 6,558,047 |
| WS 窗口控制 | `` …replace(/^http:/,`ws:`).replace(/^https:/,`wss:`), a=new WebSocket(`${i.replace(/\/$/,'')}/ws/remote-control/window/${e.token}`) ``（`new WebSocket(` @**6,564,906**；路径串 @**6,564,943**）；首帧 `window-control-ready`，解析器 @**6,564,634**：`if(t?.type==='window-control-ready'&&typeof t.windowControlSessionId==='string'&&typeof t.mobileConnectionId==='string')return t` | 6,564,634 / 6,564,906 |

⟹ 同一份 `{workspaces,tasks,initialViewState,mobileViewState}` 有**两套投递方式**：relay 业务帧（§2）与 REST/WS（本表）。
移动客户端只走 relay；网页 remote 页两条都用，WS ready 的 `windowControlSessionId` 会被回传进
`{token,relayOrigin,windowControlSessionId,mobileConnectionId,…}`（@6,564,666）。

---

## 10. schema ↔ 真机对照（**外部输入：旧安卓壳，已不在工作树**）

⚠️ 旧基线的这一节依据的是 `android-shell/app/src/main/java/com/zcode/remote/...` 的
`Tier2Probe.kt` / `RelayBridge.kt` 与真机日志。**这些文件在本仓工作树里已经不存在**
（当前实现是 `android/app/src/main/kotlin/app/zemote/` + `rust/crates/`，见 17 篇 §0）。因此本节全部标
**「未复核（旧基线）」**，只作历史对照，**不可作为当前结论引用**：

| 方向 | 旧基线结论（**未复核**） |
|---|---|
| schema 有、真机 `tasks[0]` 没出现 | 4 个可选键：`workspaceIdentity`、`remoteSessionId`、`unreadAt`、`archived` |
| schema 有、真机枚举值未出现 | `displayStatus:'idle'`（真机直方图为 running×1 + completed×89 + error×1） |
| 真机出现、schema 没写 | 无 |
| 判不出 | 逐任务的 `workspaceIdentity/remoteSessionId/unreadAt/archived` 分布；`result.workspaces[]` 真机形状；`initialViewState/mobileViewState` 是否随响应下发 |

**本代新增的两条 schema 键（`hasBackgroundWork`、`workflowActivity`）在任何真机样本里都没被观察过**
—— 旧壳的 `shapeOf` 探针只落 `tasks[0]` 的键集，且该探针代码已随旧壳消失。

---

## 11. 复现命令

```bash
cd E:/Zcode_android/Reference_web/3.15.3/assets

# ① 关键字 → 字节偏移（必须用 Buffer.indexOf；Node 字符串 indexOf 是 UTF-16 索引，中文前缀会错位）
node -e "const b=require('fs').readFileSync('validation-CqXZOLXO.js');console.log(b.indexOf('oS=O({'))"

# ② 取窗口：整个 bootstrap 族（282,127–286,700）
node -e "const b=require('fs').readFileSync('validation-CqXZOLXO.js');process.stdout.write(b.subarray(282100,286700).toString())"

# ③ deviceInfo 与 union 成员（最要紧的两处）
node -e "const b=require('fs').readFileSync('validation-CqXZOLXO.js');process.stdout.write(b.subarray(283750,285900).toString())"

# ④ 主包收帧闸门
node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');process.stdout.write(b.subarray(6528250,6528450).toString())"

# ⑤ 网页端 deviceInfo 构造函数（对照：它带 updatedAt）
node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');process.stdout.write(b.subarray(6548150,6548500).toString())"
```

| 你要什么 | 去哪 |
|---|---|
| 客户端逐字段"读没读、用在哪" | [`17-remote-v4-bootstrap载荷-合并总表与未解析字段清单.md`](17-remote-v4-bootstrap载荷-合并总表与未解析字段清单.md) |
| 全 relay 消息目录与时序 | [`16-remote-v4-relay协议目录.md`](16-remote-v4-relay协议目录.md) |
| 网页端模块坐标 | [`15-remote-v4-网页架构与模块地图.md`](15-remote-v4-网页架构与模块地图.md) |
