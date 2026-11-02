# remote-v4 功能域：对话渲染与流式更新

> **审计基线**：3.15.3（2026-10-15 快照）
> 主包 `assets/index-BGa3KiRe.js`（6,592,643 B，SHA-256 `ce2e2fc4…b88fd`）
> schema `assets/validation-CqXZOLXO.js`（307,369 B，SHA-256 `96e64e8f…dd6c9b`）
> 旧基线（2026-09-20 / `index-B-ilXaCQ.js`）的偏移与符号名**一律作废**。
>
> 快照根：`E:\Zcode_android\Reference_web\3.15.3\`（只读）
> **偏移口径**：**UTF-8 字节偏移**（`node -e "const b=require('fs').readFileSync(F);console.log(b.indexOf('KEY'))"` 可复现）。
> 旧基线用的是「UTF-8 解码后字符偏移」，两者**不可混用**；本文全部坐标已按字节重挖。
> **审计日期**：2026-10-16 ｜ **方法**：Zod schema 反解 + 归约函数逐行取证 + DOM 属性定位

---

## 0. 一句话

对话内容走 **"增量 op 流"**：relay 桥内推来的帧里带一串 **op**，页面用**纯函数归约**把它们应用到一份"行窗口"状态上。
本版最大的变化：**op 从 5 种扩到 11 种**（新增 `pluginUi.*` 4 种、`workflowRun.*` 2 种），**行类型从 8 种扩到 9 种**
（新增 `hookInvocation`）。流式仍是 `row.delta` 按字段追加、**归约层无节流**，但新增了 `subscriberBufferMaxOps:500`
等协议侧缓冲上限。渲染侧是**虚拟列表 + 渲染单元**，并把整条时间线的状态**落在 DOM 属性上**（`data-*`）。

---

## 1. 数据链路：relay 帧 → store

### 1.1 订阅请求 / 应答 / 帧信封（schema 权威，全部在 `parts-DcYGwy2q.js`）

> **切分已变**：旧基线里这些 schema 在 `src-wmk2orCZ.js`（285 KB）。3.15.3 把它们切到了
> `assets/parts-DcYGwy2q.js`（57,142 B），且该 chunk 出现在主包的**预加载清单**里
> （`index-BGa3KiRe.js` 偏移 59,413 处含 `"assets/parts-DcYGwy2q.js"`）。

```js
// 订阅请求（strict）· parts-DcYGwy2q.js @ 12,116
Pt = { topic, base?: { logEpoch, seq }, visibility?: ['foreground'|'background'] }.strict()
// 订阅应答 · 同文件 @ 12,237
L  = { subscriptionId, mode: ['snapshot'|'resume'], logEpoch }
// 对话帧载荷（按 kind 判别）· 同文件 @ 12,890（构造函数 Rt 在 @ 12,706）
zt = { topic, subscriptionId, fromSeq, toSeq, sentAt,
       payload: discriminatedUnion('kind', [
         { kind:'snapshot', snapshot },
         { kind:'deltas',   deltas: [ op ] } ]) }.extend({ ttft?, ttftRelated? })
// 线上帧信封（wireVersion:3）· 同文件 @ 9,362
F  = discriminatedUnion('kind', [
       { wireVersion:3, kind:'complete', deliveryKind?, logicalFrameId, logicalFrameOrdinal,
         topic, subscriptionId, frame? },
       { wireVersion:3, kind:'fragment', deliveryKind?, logicalFrameId, logicalFrameOrdinal,
         topic, subscriptionId, fragmentIndex?, fragmentCount?, logicalBytes?, checksum?, dataBase64? } ])
At = ['initial','online','recovery']          // deliveryKind 全集 · 同文件 @ 9,324
```

| 项 | 3.15.3 取值 | 与旧基线对比 |
| --- | --- | --- |
| `wireVersion` | `3`（字面量 `k(3)`） | 一致 |
| `deliveryKind` | `initial` / `online` / `recovery`（@ 9,324） | 一致 |
| 帧 `kind` | `complete` / `fragment`（新增分片组装） | 一致（旧基线未展开） |
| topic 前缀约束 | `conversation/`（长度≠13）、`sessions-index/`（≠15）、`workspace-config/`（≠17） | 一致 |
| topic 构造 | `function St(e){return \`sessions-index/${e}\`}` @ 8,545 | 一致 |

**协议侧上限（新增，旧基线未记录）** —— `validation-CqXZOLXO.js` @ **117,010**：

```js
Uu = { maxFrameBytes: 1024*1024,              // 单帧 1 MiB
       logicalFrameAssemblyMaxBytes: 16*1024*1024,
       logicalFrameAssemblyMaxFragments: 1024,
       logicalFrameAssemblyMaxConcurrent: 32,
       logicalFrameAssemblyMaxStagedBytes: 32*1024*1024,
       logicalFrameAssemblyTimeoutMs: 3e4,    // 30 s
       transportEnvelopeIdMaxChars: 256,
       subscriberBufferMaxOps: 500,           // ← 单订阅缓冲上限（op 数）
       subscriberBufferMaxBytes: 1024*1024,   // ← 单订阅缓冲上限（字节）
       eventRetentionPerSession: 2e3,
       snapshotTailWindowRows: 60,            // @ 117,368
       rowsRangeMaxLimit: 200,                // @ 117,394
       ... }
```

### 1.2 ops 判别联合：**11 种**（旧基线 5 种）

`parts-DcYGwy2q.js` @ **5,148**（`=x('op',[v({op:k('row.appended'),...`）：

```js
pt = discriminatedUnion('op', [
  { op:'row.appended', row },
  { op:'row.upserted', row },
  { op:'row.removed',  fromRowId },                       // 删「该 rowId 及其之后」的行
  { op:'row.delta',    rowId, path, append },             // 流式追加
  { op:'state.updated', patch },                          // patch 形状见 @ 4,450 的 ct
  { op:'pluginUi.resourceUpdated',    pluginId, serverName, uri, subscribers },
  { op:'pluginUi.resourceListChanged',pluginId, serverName, subscribers },
  { op:'pluginUi.instanceClosed',     pluginId, serverName, subscribers },
  { op:'pluginUi.appToolCall',        activity?, cancelled?, pluginId, serverName,
                                      subscribers, callId, toolName, arguments },
  { op:'workflowRun.updated', runId, revision, run?, cleared?, removedActors?,
                              removedNodes?, actors?, nodes? },
  { op:'workflowRun.removed', runId, revision },
])
```

- **必填/可选**：只有 `pluginUi.appToolCall` 的 `activity`/`cancelled` 与 `workflowRun.updated` 的
  `run`/`cleared`/`removedActors`/`removedNodes`/`actors`/`nodes` 是可选，其余字段一律必填。
- `state.updated` 的 `patch`（同文件 @ **4,450**）**没有 `.strict()`**，且全部字段可选：
  `{ revision?, control?, sharedContextImport?, availability?, inputRouting?, meta?, config?,
  modelTransition?, usage?, queue?, pendingInteractions?, pendingCommands?, backgroundWorks?,
  subagents?, workflowRuns?, goal?, plan?, workspaceHookAdmission? }`。
- **`row.*` 五种与 `state.updated` 的字段形状与旧基线完全一致**（仅 minifier 符号名变了）。
- `pluginUi.*` / `workflowRun.*` 由能力位门控：`function Nt(e){return e.workflowRunDeltas===!0}` @ 12,171；
  订阅请求里带 `workflowRunDeltas?: boolean`（@ 14,729 的 `Pt.extend({connectionId, clientMode, workspace?,
  legacyTaskIds?, resumeThoughtLevel?, workflowRunDeltas?})`）。

### 1.3 `row.delta.path` 与追加语义

路径枚举在 `validation-CqXZOLXO.js` @ **116,963**：

```js
Hu = ['text','inputText','output.text','summaryText']   // 4 值，与旧基线一致
```

追加实现（`parts-DcYGwy2q.js` @ **6,181** 的 `function mt(e,t,n)`，`t`=path、`n`=append）：

| `path` | 目标 `kind` | 语义（原文条件） |
| --- | --- | --- |
| `text` | `assistantText` **或** `reasoning` | `{...e, text: e.text + n}` |
| `inputText` | `toolCall` | `{...e, inputText: e.inputText + n}` |
| `output.text` | `toolCall` **且 `e.output` 为真** | `{...e, output:{...e.output, text: e.output.text + n}}` |
| `summaryText` | `subagent` | `{...e, summaryText: e.summaryText + n}` |

**类型不匹配时返回原对象**（不是抛错）—— 即 op 静默丢弃。

### 1.4 归约实现语义（逐 op）

单 op 归约 `function ht(e,t)` @ **6,563**；批量归约 `function gt(e,t){let n=e;for(let e of t)n=ht(n,e);return n}` @ **7,735**
（**顺序左折叠**，顺序敏感）。

```js
case 'row.appended':  return { ...e, rows:{ ...e.rows, window:[...e.rows.window, t.row],
                              totalCount: e.rows.totalCount+1,
                              firstRowId: e.rows.firstRowId ?? t.row.rowId } }
case 'row.upserted':  { let n = e.rows.window.findIndex(r=>r.rowId===t.row.rowId)
                        if (n===-1) return e            // ★ 窗口里没有 → 整体忽略，不插入
                        let r=[...e.rows.window]; r[n]=t.row; return {...e, rows:{...e.rows, window:r}} }
case 'row.removed':   { let n = e.rows.window.filter(r=>r.rowId < t.fromRowId)
                        let r = e.rows.window.length - n.length
                        let i = e.rows.firstRowId!==null && t.fromRowId <= e.rows.firstRowId
                        return {...e, rows:{...e.rows, window:n,
                                totalCount: i ? 0 : Math.max(0, e.rows.totalCount - r),
                                firstRowId:  i ? null : e.rows.firstRowId } } }
case 'row.delta':     { let n = e.rows.window.findIndex(r=>r.rowId===t.rowId), r = e.rows.window[n]
                        if (n===-1 || r===void 0) return e     // ★ 不在窗口 → 丢弃
                        let i=[...e.rows.window]; i[n]=mt(r,t.path,t.append)
                        return {...e, rows:{...e.rows, window:i}} }
case 'state.updated': return { ...e, ...t.patch }        // ★ 浅合并，无 revision 校验
case 'pluginUi.*':    return e                           // 对行窗口无影响
case 'workflowRun.updated': return {...e, workflowRuns: Qe(e.workflowRuns, t)}
case 'workflowRun.removed': return {...e, workflowRuns: $e(e.workflowRuns, t)}
```

**工程含义（幂等性 / 顺序要求 / 冲突处理）**：

| op | 幂等 | 顺序要求 | 冲突处理 |
| --- | --- | --- | --- |
| `row.appended` | ✗（重复即重复行） | 依赖到达顺序 | 无去重 |
| `row.upserted` | ✓ | 依赖顺序 | **窗口内找不到 rowId 就整体丢弃**（不插入、不报错） |
| `row.removed` | ✓ | 依赖顺序 | 按 `rowId < fromRowId` 截断；`fromRowId <= firstRowId` 时把 `totalCount` 清零、`firstRowId` 置 null |
| `row.delta` | ✗（append 语义） | **强顺序** | 行不在窗口即丢弃 |
| `state.updated` | ✓ | 后写覆盖 | 浅合并，**不校验 `revision`** |
| `pluginUi.*` | ✓ | — | 对行窗口是 no-op |

⇒ 与旧基线一致的两条关键语义（**本版重新确认**）：`row.removed` 是**按 id 截断**不是"删一条"；
`row.delta` **只在行已在窗口内时生效**。

### 1.5 帧级去重 / 缺口检测（store，`index-BGa3KiRe.js`）

`handleFrame` @ **1129,021** 附近逐字：

```js
handleFrame(e,t){ if(this.closed || e.subscriptionId !== this.state.subscriptionId) return; ... }
// applyFrame 内：
if (n && e.toSeq <= n.seq) { …; return }          // ★ 重复帧：toSeq 不前进 → 丢弃
if (!n || e.fromSeq !== n.seq) {                  // ★ 缺口：fromSeq 与本地 seq 不连续
  Z.warn(`[v4-store] ${this.topic} 帧断档 fromSeq=${e.fromSeq} local=${n?.seq??'none'}，重订阅`)
  t.subscribeMode===null ? this.requestRecovery(t.recovery) : this.connect({forceSnapshot:!0})
  return }
```

- `logEpoch` 的匹配判据在订阅应答处（`index-BGa3KiRe.js` @ **1126,500** 区）：
  `this.subscriptionHasAppliedBase = !!(n && e.ack.mode==='resume' && e.ack.logEpoch === n.logEpoch)`。
  旧基线说"`base:{logEpoch,seq}` 不匹配只能重取快照"——**本版实测的判据是 `ack.logEpoch` 与本地
  `snapshot.logEpoch` 相等，且 `mode==='resume'`**；不等则 `subscriptionHasAppliedBase=false`，
  后续 `deltas` 帧会被 `requestRecovery()` 拦下（`if(e.payload.kind==='deltas' && !this.subscriptionHasAppliedBase)`）。

---

## 2. 流式是否有本地节流

**结论：归约层与 store 层都没有节流/合帧；但协议层新增了缓冲上限。**

| 面 | 证据 | 结论 |
| --- | --- | --- |
| store 归约 | `handleFrame` → `applyFrame` → `setState`，每帧一次（@ 1129,021） | 无合帧 |
| 渲染层 | 主包内 **无** `startTransition`（0 命中）；`useDeferredValue` 3 处（2059,715 / 5473,062 / 6153,817）**均与对话流无关**（分别是 @ 提及搜索、另两处无关面板） | 无 React 侧降频 |
| 虚拟列表 | 时间线虚拟器 `overscan: Krn`，`Krn=8`（@ 3450,315 定义，@ 3457,476 使用） | 靠虚拟化吸收，不靠丢帧 |
| 协议缓冲 | `subscriberBufferMaxOps: 500` / `subscriberBufferMaxBytes: 1 MiB`（@ 117,010） | **新增**：订阅侧积压超限由协议处理 |

- `row.delta` 是"追加"语义（`append`），所以 **帧频率 ≈ 状态更新频率**。
- 旧基线引用的"壳侧真机前台 ~14.5 帧/秒"观测与本节一致（页面不主动降频），**但该实测是旧基线外部输入，
  本版未复核（未复核（旧基线））**。

---

## 3. 会话索引（`sessions-index`）与 `logEpoch`

全部在 `parts-DcYGwy2q.js`：

```js
// topic 构造 @ 8,545
function St(e){ return `sessions-index/${e}` }
// 快照 @ 8,431
bt = { protocolVersion: 1, workspaceId, logEpoch, sessions: [ yt ] }
// 索引 op @ 8,450
xt = discriminatedUnion('op', [ {op:'session.upserted', session: yt},
                                {op:'session.removed',  sessionId} ])
// 会话元素 yt @ 7,999
yt = { sessionId, workspaceId, parentSessionId?, title, titleSource?, phase, sessionEnded,
       hasBackgroundWork, workflowActivity?, pendingInteraction?, pendingInteractionSummary?,
       goalStatus?, lastActivityAt, lastAssistantPreview?, createdAt }
// 工作区配置流 @ 9,139 / op @ 9,201
Dt = { protocolVersion: 1, workspaceId, logEpoch, config: Et }
Ot = discriminatedUnion('op', [ {op:'config.updated', config: Et} ])
```

**与旧基线的字段差异（已复核）**：

| 字段 | 3.15.3 | 旧基线 |
| --- | --- | --- |
| `workflowActivity?` | **新增**（@ 8,168；定义在 validation @ 283,007） | 无 |
| `lastTerminalQuery?` | **已删除**（三个文件 0 命中） | 有 |
| 其余 14 字段 | 一致 | — |

**两个"需要重绘"的判据函数**（`index-BGa3KiRe.js`，旧基线 `HCe`/`UCe` 的对应物）：

```js
// z1e @ 1122,261
function z1e(e){ return e.payload.kind==='snapshot' ? true
  : e.payload.deltas.some(e=>{ if(e.op==='row.removed') return true
      if(e.op!=='row.appended' && e.op!=='row.upserted') return false
      let t=e.row
      return t.kind==='toolCall' && t.toolName==='ExitPlanMode' && R1e.has(t.status) }) }
// B1e @ 1122,520
function B1e(e){ return e.payload.kind==='snapshot' ? true
  : e.payload.deltas.some(e=>{ if(e.op==='row.removed') return true
      if(e.op!=='row.appended' && e.op!=='row.upserted') return false
      let t=e.row
      return t.kind==='userInput' && t.origin==='realUser' }) }
// R1e = new Set(['success','error','cancelled'])   // @ 1122,2xx 区
```

应用点（@ **1131,823** 区）：`z1e(e)` → `planDirectoryRevision+1`；`B1e(e)` → `turnNavigatorDirectoryRevision+1`。
⇒ **语义与旧基线完全一致**：页面把"删过行"或"真实用户输入 / 退出计划模式终态"当作需要重算目录的时刻。

---

## 4. 分页：窗口、`loadOlder`、`overscan`

### 4.1 窗口状态形状

`validation-CqXZOLXO.js`：

```js
// 行窗口 @ 188,947
$h = { window: [ Row ], totalCount: int, firstRowId: int.nullable() }
// 对话快照 @ 189,104
tg = { protocolVersion: 1, sessionId, logEpoch, seq, revision,
       control, availability, inputRouting, meta, sharedContextImport?, config,
       modelTransition, usage, queue, pendingInteractions, pendingCommands, backgroundWorks,
       subagents?, workflowRuns?, goal, plan, workspaceHookAdmission, rows: $h }
```

- **`revision` 是快照里的版本号字段**（@ 189,104 区，`revision: w()`）。**但 store 全程不读它**
  （`index-BGa3KiRe.js` 的 store 区间 1128k–1140k 内 `revision` 0 命中）；`state.updated.patch.revision?`
  也只是被浅合并进快照。**⇒ 当前构建没有基于 `revision` 的 CAS 校验**，真正的去重/冲突判据是
  `seq` / `fromSeq` / `toSeq` / `logEpoch`（见 §1.5）。
- `workspaceHookAdmission`（@ 189,537）= `{ pendingCount, bundleDigest, workspaceIdentity? }`。

### 4.2 `loadOlder` / `loadAllOlder`（`index-BGa3KiRe.js`）

```js
// @ 1136,525
async loadOlder(e = W.snapshotTailWindowRows){          // 默认 limit = 60
  if (this.closed || this.state.loadingOlder) return
  let t = this.state.snapshot; if (!zw(t) || !t) return
  let n = ls(this.topic); if (!n) return                 // topic → sessionId
  let r = t.rows.window[0]?.rowId                        // 以窗口首行为锚点
  if (r !== undefined) { this.setState({loadingOlder:true})
    try { let t = await this.transport.rowsRange({ sessionId:n, beforeRowId:r, limit:e })
      if (!i || t.atLogEpoch !== i.logEpoch) {           // ★ CAS-1：纪元不匹配 → 整体丢弃
        Z.warn(`[v4-store] ${this.topic} rows/range 纪元不匹配（${t.atLogEpoch}），整体丢弃`); return }
      if (i.rows.window[0]?.rowId !== r) return          // ★ CAS-2：窗口已前移 → 丢弃
      let a = Bw(i.rows.window, this.shareRows(t.rows))  // 只接受 rowId < window[0].rowId 的行
      if (a === null) return
      this.setState({ snapshot: {...i, rows:{...i.rows, window:a}} })
    } catch(e){ Z.warn(`[v4-store] rowsRange ${this.topic} 失败: ...`) }
    finally { this.closed || this.setState({loadingOlder:false}) } } }
```

```js
// @ 1136,878 附近
async loadAllOlder(){ … }   // 为 turnNavigator（完整问题目录）循环补拉历史，返回 hydration terminal
```

**分页契约（`parts-DcYGwy2q.js`）**：

| 项 | 形状 | 偏移 |
| --- | --- | --- |
| 请求 | `{ sessionId, clientMode?: ['desktop-continuous'|'web-remote-replayable'], beforeRowId?: int, limit: int.min(1).max(rowsRangeMaxLimit) }` | @ 15,478 / 15,600 |
| 应答 | `{ rows: [Row], atSeq: int, atRevision: int≥0, atLogEpoch: str, hasMore: bool }` | @ 15,6xx 区（`atSeq` @ 15,635、`atRevision` @ 15,645、`hasMore` @ 15,695） |
| `limit` 上限 | `rowsRangeMaxLimit = 200` | validation @ 117,394 |
| 默认 `limit` | `snapshotTailWindowRows = 60` | validation @ 117,368 |
| 合并函数 | `function Bw(e,t){ let n=e[0]?.rowId??1/0; let r=t.filter(e=>e.rowId<n); return r.length===0?null:[...r,...e] }` @ 1123,466 | — |

- **`rowsRange` 走的是 transport 门面**：`rowsRange(t) → e.conversationRowsRangeV4({...n, sessionId:t.sessionId,
  ...(t.beforeRowId===undefined?{}:{beforeRowId:t.beforeRowId}), limit:t.limit})`（`index-BGa3KiRe.js` @ **1110,959** 区）。
- `Bw` 的语义：**只前插 `rowId < window[0].rowId` 的行，且返回 null 表示"没有新增"**（幂等、防重放）。
- **CAS 字段实测**：`atSeq` 与 `atRevision` 在主包 **0 命中**（只在 `parts-DcYGwy2q.js` 的 schema 里定义）
  ⇒ store **完全不校验**这两个字段。`loadOlder` 真正校验的是 `atLogEpoch`（CAS-1）与 `window[0].rowId`（CAS-2）；
  `hasMore` 被 `loadAllOlder` 读取（主包 @ 1138,713 / 1138,723 / 1138,805）用作循环终止。

### 4.3 `overscan`

| 列表 | 值 | 偏移 |
| --- | --- | --- |
| **对话时间线** | `Krn = 8` | 定义 @ 3450,315；使用 @ 3457,476（`gs({count:ie.length, getScrollElement:et, estimateSize:nt, overscan:Krn, getItemKey:tt, measureElement:rt, scrollMargin:ee})`） |
| 文件变更面板 | `12` | @ 4549,804 |
| 变更集搜索 | `rFn` | @ 5409,155 |
| 工作区任务列表 | `ekn` / `lkn` | @ 4829,782 / 4843,774 |

---

## 5. 行类型全集：**9 种**（旧基线 8 种）

判别联合在 `validation-CqXZOLXO.js` @ **182,965**：
`bh = A('kind', [sh, ch, lh, uh, fh, mh, hh, _h, O({...ah, kind:N('timelineMarker'), …})])`

基础字段 `ah` @ **177,270**：
`{ rowId:int, turnId:str, entityId?:str(1..), productTurnId?:str(1..), visibility?:'visible',
   createdAt:ts, createdAtSeq:int, actions?:{canFork?,canEdit?,canRetry?,canRewindFiles?,editDisposition?['rewind'|'fork']} }`

| # | `kind` | schema 偏移 | 关键字段（原文） |
| --- | --- | --- | --- |
| 1 | `turnHeader` | @ 177,760 | `origin:['userInput','backgroundResult','goalContinuation','editRerun','workflowLaunch']`、`executionKind?:['agent','controlOnly']`、`state:['running','completedSuccess','completedInterrupted','failed']`、`startedAt`、`endedAt?`、`activeMs?`、`outputTokens?`、`modelDurationMs?`、`toolDurationMs?`、`otherDurationMs?`、`workSegments?`、`originMeta?`、`workflowLaunch?`、`fileChanges?{additions,deletions,files,state?}` |
| 2 | `userInput` | @ 178,525 | `inputOrigin?:['desktop','mobile']`、`botGroupSource?`、`conversationQuotes?`、`text`、`epilogueStart?`、`origin:['realUser','backgroundResult','goalContinuation','mailbox','synthetic','workflowLaunch']`、`originMeta?`、`workflowLaunch?`、`guided?`、`sourceCommandId?`、`rootSourceCommandId?`、`clientId?`、`highspeed?`、`attachments?`、`source?` |
| 3 | `assistantText` | @ 179,212 | `assistantResponseId?`、`text`、`state:['streaming','complete','interrupted','failed']`、`model?`、`feedback?['like','dislike']` |
| 4 | `reasoning` | @ 179,421 | `assistantResponseId?`、`text`、`state:['streaming','complete','interrupted']`、`durationMs?` |
| 5 | `toolCall` | @ 179,701 | `assistantResponseId?`、`toolCallId`、`toolName`、`status:['inputStreaming','pendingApproval','running','success','error','cancelled']`、`inputText`、`input?`、`cuaApp?{pid,name,bundleId?}`、`output?`、`display?`、`error?{code,message}`、`progress?`、`outputPreview?`、`approvalInteractionId?`、`backgrounded?`、`workId?`、`startedAt?`、`endedAt?` |
| 6 | `artifact` | @ 180,293 | `artifactVersionId`、`logicalArtifactKey`、`displayName`、`artifactType:['pdf','pptx','docx','xlsx','image','html','md','text']`、`mimeType`、`sizeBytes`、`sha256`（`/^[0-9a-f]{64}$/`）、`ref`、`state:'current'` |
| 7 | `subagent` | @ 180,583 | `parentToolCallId?`、`subagentType`、`status:['running','success','failed','cancelled']`、`summaryText`、`childSessionId?`、`backgrounded?`、`workId?`、`startedAt?`、`endedAt?` |
| 8 | **`hookInvocation`** | @ 181,632 | **新增**：`hookInvocationId`、`hookEventName:['SessionStart','UserPromptSubmit','PreToolUse','PermissionRequest','PostToolUse','PostToolUseFailure','Stop']`、`hookCount:int>0`、`state:['running','completed','failed']`、`startedAt`、`endedAt?`、`durationMs?`、`lane:['assistantWork','toolBefore','toolAfter']`、`anchorToolCallId?`、`executions:[gh]`；`gh` @ 181,000 区 = `{hookRunId,hookIndex,didExecute,state,outcome?,blockReason?,startedAt,endedAt?,durationMs?,displayName,sourceKind,pluginName?,toolName?}` |
| 9 | `timelineMarker` | @ 183,002 | `sourceCommandId?`、`lane?:['assistantWork','turnTailBoundary','lightBoundary']`、`marker: vh`；`vh` @ 182,1xx = 9 种 `type`：`compact` / `forkNotice` / `forkCreated` / `modelChange`(×2) / `goalSet` / `goalVerify` / `retryNotice` / `checkpointRestored` |

**渲染分派（两处，均非主时间线）**：

- 主时间线的"是否在流"判据 `function Knn(e)` @ **3431,382**：
  `assistantText|reasoning → state==='streaming'`；`toolCall → backgrounded!==true && status∈{inputStreaming,pendingApproval,running}`；
  `subagent → backgrounded!==true && status==='running'`；`timelineMarker → 'status' in marker && marker.status==='running'`。
- 会话分享渲染器 `function ZZ(e,t,n,r,i,a,o)` @ **3482,376** 的 `switch(e.kind)`：
  `userInput→Sin`、`assistantText→YZ`、`reasoning→Cin`、`toolCall→XZ`、`artifact→Din`、
  `timelineMarker→` 内联 `div[data-conversation-share-row-kind="timelineMarker"]`，
  **`subagent` / `hookInvocation` / `turnHeader` → `return null`**。
- 旧基线给的 `Eot`/`Dot`/`Oot`/`Pot`/`Fot`/`Iot` 等符号名**已全部作废**（新构建符号名不同）。
  旧基线"`toolCall` 且 `toolName` 家族为 `todo` 且隐藏 todo 时不渲染"这条**未复核（旧基线）**：
  本版未定位到对应的 `todo` 门控分支。

---

## 6. DOM 契约（**壳的观测锚点**）

### 6.1 时间线根节点 —— `index-BGa3KiRe.js` @ **3470,268**

```jsx
<div ref={R} data-testid={Vn}
     data-v4-timeline-scroll="true"
     data-v4-timeline-scroll-locked={ y ? 'true' : 'false' }
     data-markdown-table-layout-root="true"
     data-row-count={e.length}
     data-window-row-count={e.length}
     data-render-unit-count={re.length}
     data-total-row-count={i}
     data-following={ Re ? 'false' : 'true' }
     data-loading-older={ p ? 'true' : 'false' }
     onKeyDownCapture onPointerCancelCapture onPointerDownCapture … />
```

| 属性 | 字节偏移 | 含义 | 壳侧用途 |
| --- | --- | --- | --- |
| `data-v4-timeline-scroll` | 3470,268 | 时间线容器存在 | "在对话视图"的判据之一 |
| `data-v4-timeline-scroll-locked` | 3470,301 | 滚动锁定（重排中） | 重排窗口期 |
| `data-markdown-table-layout-root` | 3470,351 | Markdown 表格布局根 | 表格渲染锚点 |
| `data-row-count` | 3470,392 | 窗口内行数 | **"0 行"是卡死判据的关键读数** |
| `data-window-row-count` | 3470,418 | 窗口内行数（同上） | — |
| `data-render-unit-count` | 3470,451 | 渲染单元数 | 虚拟化读数 |
| `data-total-row-count` | 3470,486 | 全会话行数（含窗口外） | 区分"窗口空"与"会话空" |
| `data-following` | 3470,511 | 是否跟随 | 判断"用户滚上去了" |
| `data-loading-older` | 3470,546 | 正在加载更早 | 与"卡住"区分 |
| `data-testid` | 3470,250 | **局部变量 `Vn`，字面值未证实**（同名符号在别处是 Promise 队列，属压缩重名） | 优先用 `data-testid` 的思路不变 |

### 6.2 时间线内部新增锚点（旧基线未列）

| 属性 | 偏移 | 说明 |
| --- | --- | --- |
| `data-v4-running-live-tail` | 3472,878 | 正在流式的"活尾"单元；同节点还有 `data-v4-turn-unit="true"`（3472,913）、`data-turn-id`（3472,940）、`data-index` |
| `data-v4-timeline-header-slot` | 3471,961 | 头部插槽 |
| `data-v4-timeline-message-layer` | 3471,758 | 消息层 |
| `data-v4-timeline-virtual-history` | 3472,136 | 虚拟历史层 |
| `data-v4-timeline-content-column` | 3471,999（另 3 处：3472,178 / 3472,965 / 3473,364） | 内容列 |
| `data-v4-turn-unit` | 3472,509 | 轮次单元 |
| `data-v4-conversation-drop-target` | 3681,471 | 拖放目标 |
| `data-conversation-bottom-dock-mode` | 3534,090 | 底部坞模式 |
| `data-conversation-selectable` | 2773,445（另 4 处：3190,239 / 3377,821 / 3379,859 / 3385,622） | 可选中区 |
| `data-conversation-selection-action` | 3446,415 / 3446,680 | 选区动作 |
| `data-conversation-selection-reference-count` | 2291,551 | 选区引用计数 |
| `data-zcode-chat-loading-animate` | 2781,020 | 加载动画（`role="status"`，`aria-label` = `common.loading`） |
| `data-zcode-chat-loading-slot` | 3391,458 | 加载位 |
| `data-timeline-fade` | 3003,297 | 渐隐 |

> **旧基线列的 `[data-testid="chat-error-banner"]` 在本版 0 命中**（`error-banner`、`chat-error` 均 0 命中）
> ⇒ **已消失 / 未能证实**。旧基线 §7.1 里"composer 可用性"这一行本轮**未复核（旧基线）**。

### 6.3 手机外壳的对话页 —— `index-BGa3KiRe.js` @ **4448,306**

```jsx
{ g === 'home'
  ? <Ebn activeTaskId={e} … switcher={p} />
  : <section className="flex h-full min-h-0 min-w-0 flex-col bg-background text-foreground"
             data-mobile-page="chat">
      <div className="flex h-11 shrink-0 items-center gap-2 border-b border-border bg-header px-2">
        <button aria-label={h.formatMessage({id:'webRemoteControl.mobileShell.backHome'})} …>
```

- `data-mobile-page="chat"` @ **4448,306** —— **唯一取值就是 `"chat"`，`home` 分支不渲染任何 `data-mobile-page`**
  （全主包 `data-mobile-page` 仅 1 处命中）。⇒ 旧基线"home 页为 null"**已复核成立**。
- 头部标题键 `webRemoteControl.mobileShell.chatTitle` @ 4448,755；返回键 `…mobileShell.backHome` @ 4448,549。
- 外壳内**新增**的 `data-mobile-*` 锚点：`data-mobile-chat-surface`（@ 4449,057，隐藏时值为 `inert`）、
  `data-mobile-side-pane-overlay="true"`（@ 4449,304）、`data-mobile-chat-overlay="true"`（@ 4449,844）、
  `data-mobile-active-task`（6 处，@ 4567,646 / 4568,170 / 4772,967 / 4774,843 / 4776,824 / 4819,201）。

---

## 7. 渲染单元与跟随判据

| 面 | 3.15.3 原文 | 偏移 |
| --- | --- | --- |
| 活尾切分 | `function mnn(e){ let t=e.length-1, n=e[t]; return n?.isRunning ? {virtualizedUnits:e.slice(0,t), liveUnit:n, liveUnitIndex:t} : {virtualizedUnits:e, liveUnit:null, liveUnitIndex:null} }` | `index-BGa3KiRe.js` @ **3424,138** |
| 跟随判据 | `function zw(e){ if(!e) return false; let t=e.rows.window[0]; return !t \|\| e.rows.firstRowId===null ? false : t.rowId > e.rows.firstRowId }` | @ **1123,202** |
| 脱离跟随 | `function H1e(e,t){ if(t \|\| !zw(e) \|\| !e) return false; let n=e.rows.window[0]?.turnId; return n ? !e.rows.window.some(e=>e.turnId===n && e.kind==='turnHeader') : false }` | @ **1123,319** |

⇒ 语义与旧基线**完全一致**（旧 `Sy`/`GCe`），仅符号名与偏移作废。

---

## 8. Markdown / 代码 / 公式管线（3.15.3 资产名已变）

| 能力 | 3.15.3 资产 | 大小 | 主包引用偏移 |
| --- | --- | --- | --- |
| 代码高亮 | `shiki` | 主包内联 | 294,932 / 306,908 / 307,459 / 308,946 / 312,336 / 420,412 |
| 公式 | `katex-DefY6ydN.js`（旧 `katex-BTKZ3E6l.js` **作废**） | 257,047 B | 68,923 / 1626,834 / 1629,964 / 1633,149 |
| diff worker | `diffs.worker-CAavpt0L.js`（**同名**） | 827,865 B | 6421,407 |
| 图/流程 | `cytoscape.esm-DyTbCmLR.js`（旧 `…-BWTpgj08.js` **作废**） | 434,143 B | 5,901 |
| 图表 | `chart-CIHO61XL.js`（旧 `chart-Bhu43MI9.js` **作废**） | 261,023 B | — |
| mermaid | `mermaid-DoeU2fQJ.js` / `mermaid-DvJuNNke.js` / `mermaid-GHXKKRXX-DvxlDHQR.js` / `mermaid-parser.core-oLgrNfH7.js` | — | 3,997 / 334,804 / 424,221 |
| Markdown 表格布局 | 时间线根节点 `data-markdown-table-layout-root="true"`（@ 3470,351） | — | — |

---

## 9. 对壳的意义

### 9.1 壳依赖的观测锚点

| 锚点 | 位置 | 说明 |
| --- | --- | --- |
| `[data-mobile-page="chat"]` | 主包 **4448,306** | "在对话视图"的主判据 |
| `[data-v4-timeline-scroll]` + `data-row-count` / `data-window-row-count` | 主包 **3470,268 / 3470,392 / 3470,418** | "有内容 / 0 行"的判据 |
| `data-total-row-count` | 主包 **3470,486** | 区分"窗口空"与"会话空" |
| `data-following` | 主包 **3470,511** | 区分"用户滚上去"与"停更" |
| `data-loading-older` | 主包 **3470,546** | 与"卡住"区分 |
| `data-v4-running-live-tail` | 主包 **3472,878** | 有正在跑的流 |
| `[data-zcode-chat-loading-animate]` | 主包 **2781,020** | 加载动画（转圈形态，见 23 篇） |
| `[data-mobile-chat-surface]` / `data-mobile-chat-overlay` | 主包 **4449,057 / 4449,844** | 手机外壳聊天面/覆盖层（新增） |
| `[data-testid="chat-error-banner"]` | **0 命中** | **旧基线结论作废**，不要依赖 |

### 9.2 三条风险提示（迁移/维护时）

1. **`data-*` 是页面的实现细节**，不是契约——页面改版可能改名（本版就新增了 10+ 个 `data-v4-timeline-*` /
   `data-mobile-*`，并删掉了 `chat-error-banner`）。壳侧应把它们集中成一份声明式表，优先用 `data-testid`。
2. **"0 行"必须有上下文**：`data-row-count=0` 可能是"窗口未加载"（`data-loading-older=true`）
   或"会话真的空"（`data-total-row-count=0`）——只看一个属性会误判。
3. **重排时刻会跳变**：`z1e`/`B1e`（§3）标记的时刻，行数与滚动位置会突变，观测判据要有去抖。

### 9.3 给原生 Kotlin 复现的归约清单（本域的数据契约）

| 项 | 必须在壳侧复现的语义 |
| --- | --- |
| op 全集 | 11 种；`row.*` 五种 + `state.updated` 是对话必需，`pluginUi.*`/`workflowRun.*` 可按能力位忽略 |
| 批量归约 | **顺序左折叠**，非交换 |
| `row.upserted` | 目标行**不在窗口内时整体忽略**（不是插入） |
| `row.removed` | 按 `rowId < fromRowId` 截断；`fromRowId <= firstRowId` ⇒ `totalCount=0`、`firstRowId=null` |
| `row.delta` | 4 个 path；类型不匹配静默丢弃；行不在窗口静默丢弃 |
| 去重 | 帧级：`toSeq <= local.seq` ⇒ 丢；缺口：`fromSeq !== local.seq` ⇒ 重订阅/强制快照 |
| 世代 | `ack.logEpoch === snapshot.logEpoch && mode==='resume'` 才认 `base` |
| 分页 | `{sessionId, beforeRowId?, limit≤200}`；应答 `{rows, atSeq, atRevision, atLogEpoch, hasMore}`；默认 limit 60；合并只前插 `rowId < window[0].rowId`；`atLogEpoch` 与 `window[0].rowId` 是双 CAS |
| 版本号 | `revision` 存在但**不参与校验**；真正的时序判据是 `seq`/`logEpoch` |

---

## 10. 复现

```bash
cd /e/Zcode_android/Reference_web/3.15.3/assets
# ① ops 判别联合（11 种）
node -e "const b=require('fs').readFileSync('parts-DcYGwy2q.js');process.stdout.write(b.subarray(5148,6300).toString())"
# ② 归约实现（mt / ht / gt）
node -e "const b=require('fs').readFileSync('parts-DcYGwy2q.js');process.stdout.write(b.subarray(6181,7900).toString())"
# ③ 行类型判别联合（9 种）
node -e "const b=require('fs').readFileSync('validation-CqXZOLXO.js');process.stdout.write(b.subarray(176500,183200).toString())"
# ④ 协议上限常量
node -e "const b=require('fs').readFileSync('validation-CqXZOLXO.js');process.stdout.write(b.subarray(116950,117450).toString())"
# ⑤ DOM 契约
node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');process.stdout.write(b.subarray(3470200,3470650).toString())"
# ⑥ data-mobile-page
node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');process.stdout.write(b.subarray(4448200,4448950).toString())"
# ⑦ loadOlder / rowsRange 双 CAS
node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');process.stdout.write(b.subarray(1136500,1137100).toString())"
```

---

## 11. 出处

- 快照：`E:\Zcode_android\Reference_web\3.15.3\`（入口 `index.html` 引用 `assets/index-BGa3KiRe.js`，逐字节冻结）。
- 旧基线快照（历史证据，不改不删）：`Docs/27-remote-v4-网页快照-20260920/`。
- 相关篇目：[16 relay 协议目录](16-remote-v4-relay协议目录.md) ·
  [15 架构与模块地图](15-remote-v4-网页架构与模块地图.md) ·
  [19 输入附件与预览](19-remote-v4-功能域-输入附件与文件预览.md) ·
  [20 导航列表与全局外观](20-remote-v4-功能域-导航列表与全局外观.md) ·
  [22 壳端注入点审计](22-remote-v4-壳端注入点审计-主题-滚动条-会话加载.md) ·
  [23 卡死现场分析](23-remote-v4-进对话无内容与左下角转圈-现场分析.md)。
