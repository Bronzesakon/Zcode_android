# 07 · rpc-frame 的分片、重组、CRC、序号、上限与去重（桌面端权威行为）

> **审计基线**：ZCode 桌面端 3.15.3（app.asar 340,516,766 B，build `d7ca8bee`，2026-10-13）
> 主要坐标：`out/main/chunk-OW6EUS2G.js`（共享协议模块）、`out/main/chunk-JS2T33SG.js`（relay 传输协议实现）、`out/main/index.js`（桥生命周期 / 入站分发 / 降级）
>
> 交叉校验副本：`out/host/chunk-OUCOFHX4.js`（host 侧同一份 schema，值一致）、网页快照 `Reference_web/3.15.3/assets/validation-CqXZOLXO.js` 与 `assets/index-BGa3KiRe.js`（客户端视角，实现逐字节同构）
>
> 本文只读逆向，未运行任何包内代码。所有字节偏移均指**原 asar 内文件的字节偏移**（已用 `out/main/chunk-OW6EUS2G.js` 偏移 554,377 = `ackMessageSeq` 与 BRIEF 给的值对齐验证）。

---

## 0. 结论速览（TL;DR）

| # | 结论 | 坐标 |
|---|---|---|
| 1 | **桌面端预算与网页端逐字段一致**：`maxPhysicalFrameBytes` 1 MiB、`maxMessageBytes` 16 MiB、`maxFragments` 64、`assemblyTimeoutMs` 30 s、`transportIdMaxChars` 256。BRIEF 给的权威值全部核实 | `chunk-OW6EUS2G.js` @534,064 `Re` / @545,748 `Ue`；`chunk-OUCOFHX4.js` @485,520 同值 |
| 2 | **桌面端发帧用的是动态二分预算**，与网页端 `Bd()` 是**同一段代码**（仅符号名不同）：每片解码后上限实算 **786,180–786,186 B ≈ 767.8 KiB**（不是 768 KiB 整，也不是 512 KiB） | `chunk-OW6EUS2G.js` @549,076 `jE` = 网页 `Bd` @131,327 |
| 3 | **桌面端严格拒绝乱序**：`seq > expectedPhysicalSeq` → `physicalGap` **终态**；`fragmentIndex !== 已收片数` → `fragmentGap` **终态**；`messageSeq !== expectedMessageSeq` → `messageGap` **终态**。终态 fault 一律把整座桥降级 | `chunk-OW6EUS2G.js` @551,665 `XS.accept`；`chunk-JS2T33SG.js` @9019 `ve.processInbound` |
| 4 | **ack 必须与桌面端存的身份三元组精确相等**（含 `bridgeGeneration` 的"有/无"状态）。桌面端 ack 接收路径第一行就是 `ke(this.identity,e)` 全等比较，不等则**静默丢弃**。我们（Rust/Dart 参考）的 ack **不带** `bridgeGeneration`/`recoveryId` → 我们所有 ack 被丢弃 → 45 s 后对端 `replayGraceExceeded` 终态降级 | `chunk-JS2T33SG.js` @7688 `ke` / `ve.acceptPayload`；`rust/crates/protocol/src/rpc_transport.rs:535-550` |
| 5 | **我们收到的对端分片会被我们自己丢掉**：我们检查 `chunk.len() > 512 KiB` 就丢弃，而对端发的是 ~786 KiB/片 ⇒ 任何 >512 KiB 的对端消息（含单片的）永久丢失，且我们不回 ack ⇒ 45 s 后对端降级 | `rust/.../rpc_transport.rs:398-402`；对端 `jE` @549,076 |
| 6 | 去重是**按 `seq` 的指纹表**：`frameFingerprintsBySeq`（当前装配中）+ `settledFrameFingerprintsBySeq`（上一条已完成消息的快照，整体替换不累积）。重复 → 只回 ack；冲突重复 → `conflictingDuplicate` 终态 | `chunk-OW6EUS2G.js` @551,316 `BE` / @551,665 `XS` |
| 7 | CRC-32 覆盖**整条逻辑消息**（不是单片），格式 `{algorithm:"crc32", value:"<8位小写hex>"}`，`algorithm` 字段被 schema 锁死为字面量 `crc32` | `chunk-OW6EUS2G.js` @544,721 `Xa`；@546,214 `$E` |
| 8 | relay 层额外有 **8 MiB 重放缓冲 + 45 s 双路 grace**（未 ack 数据 / 待发 ack），超限或超时都是终态降级。`replayBufferGraceMs=45e3` 与 BRIEF 一致 | `chunk-JS2T33SG.js` @7203 `m`、@6235 `f`、@9019 `ve` |

---

## 1. 权威预算核对：桌面端 vs 网页端

### 1.1 桌面端预算对象

`out/main/chunk-OW6EUS2G.js` @**534,064**（符号 `Re`，全文件第 62 行）：

```js
var Re={maxFrameBytes:1024*1024,logicalFrameAssemblyMaxBytes:16*1024*1024,
logicalFrameAssemblyMaxFragments:1024,logicalFrameAssemblyMaxConcurrent:32,
logicalFrameAssemblyMaxStagedBytes:32*1024*1024,logicalFrameAssemblyTimeoutMs:3e4,
transportEnvelopeIdMaxChars:256,subscriberBufferMaxOps:500,subscriberBufferMaxBytes:1024*1024,
eventRetentionPerSession:2e3,snapshotTailWindowRows:60,rowsRangeMaxLimit:200,...}
```

`out/main/chunk-OW6EUS2G.js` @**545,748**（符号 `Ue`，即 relay 传输预算，导出名 `Z`）：

```js
var Ue={maxPhysicalFrameBytes:Re.maxFrameBytes,maxMessageBytes:16*1024*1024,maxFragments:64,
assemblyTimeoutMs:3e4,transportIdMaxChars:Re.transportEnvelopeIdMaxChars}
```

host 侧同一份副本：`out/host/chunk-OUCOFHX4.js` @**485,520**（符号 `we`）值逐字节相同。

### 1.2 桌面端 vs 网页端 vs BRIEF 权威值

| 字段 | 桌面端 `Ue` | 网页端 `bd`（`validation-CqXZOLXO.js` @128,548） | BRIEF 权威值 | 一致？ |
|---|---|---|---|---|
| `maxPhysicalFrameBytes` | `Re.maxFrameBytes` = 1,048,576 B (1 MiB) | `Uu.maxFrameBytes` | 1 MiB | ✅ |
| `maxMessageBytes` | 16,777,216 B (16 MiB) | 16 MiB | 16 MiB | ✅ |
| `maxFragments` | 64 | 64 | 64 | ✅ |
| `assemblyTimeoutMs` | 30,000 ms | 30,000 ms | 30 s | ✅ |
| `transportIdMaxChars` | `Re.transportEnvelopeIdMaxChars` = 256 | 256 | 256 | ✅ |

**核对结论：桌面端与网页端、与 BRIEF 权威值三方完全一致，无冲突。** 注意别与 `Re.logicalFrameAssembly*`（1024 片 / 16 MiB / 30 s）混淆——那是 **host IPC 逻辑帧**层的预算，不是 relay 的 `rpc-frame`。

### 1.3 relay 传输层自己的预算（第二组常量）

`out/main/chunk-JS2T33SG.js` @**7203**（符号 `m`，全文件第 1 行）：

```js
var m={saturationHighWaterMarkBytes:1024*1024,saturationLowWaterMarkBytes:256*1024,
replayBufferMaxBytes:8*1024*1024,replayBufferGraceMs:45e3,assemblyTimeoutMs:g.assemblyTimeoutMs}
```

其中 `g` = 从 `./chunk-OW6EUS2G.js` 导入的 `Z`（即 `Ue`）⇒ `assemblyTimeoutMs = 30_000`。
`Te()`（@8270，`resolveAcknowledgedRelayLimits`）对每个值做 `Math.min(传入值, 默认值)`，**只允许收紧，不允许放宽**。

网页端对应对象是 `assets/index-BGa3KiRe.js` @**6,473,333**（符号 `_9`），常量逐字节相同 ⇒ 网页端与桌面端在 relay 层也一致。

---

## 2. 分片尺寸算法：动态二分（桌面端 = 网页端，同一段代码）

### 2.1 定位链（可复现）

`out/main/chunk-JS2T33SG.js` 第 1 行头部 import：

```js
import{$ as ye,Z as g,_ as he,aa as fe,ba as Ce,ca as be,uc as Ie}from"./chunk-OW6EUS2G.js";
```

配合 `chunk-OW6EUS2G.js` 末尾导出表（@797,330）：

```
export{... Ue as Z, Ug as _, M2 as $, Je as aa, $2 as ba, XS as ca, ...}
```

⇒ `Ce` = `$2` = `encodeWebRemoteControlRpcTransportMessage`，`be` = `XS` = 装配器，`ye` = `M2` = 载荷解析，`he` = `Ug` = 信封测长，`g` = `Ue` = 预算。

### 2.2 预算函数原文

`out/main/chunk-OW6EUS2G.js` @**549,076**（`jE` = `findDecodedBudget`）：

```js
function jE(t){let n=nv({identity:t.identity,seq:t.endSeq,messageSeq:t.messageSeq,
fragmentIndex:t.fragmentCount-1,fragmentCount:t.fragmentCount,messageBytes:t.messageBytes,
checksum:t.checksum,dataBase64:""}),r=Ug(n),a=0,o=t.messageBytes;
for(;a<o;){let i=Math.ceil((a+o)/2);r+NE(i)<=t.maxPhysicalFrameBytes?a=i:o=i-1}return a}
```

其中 `NE` = `base64Length` = `4*Math.ceil(n/3)`（@548,771），`Ug` = `measureWebRemoteControlRpcRelayEnvelopeBytes` = 对 `{type:"data",payload,client_ts:MAX_SAFE_INTEGER,server_ts:MAX_SAFE_INTEGER}` 取 UTF-8 字节数（@547,843）。

**判据**：`信封字节数(用 dataBase64="" 且 seq=末片 seq 的壳) + 4*ceil(解码字节数/3) ≤ maxPhysicalFrameBytes`。二分出**单片解码后的最大字节数** `p`。

### 2.3 分片数不动点迭代

`out/main/chunk-OW6EUS2G.js` @**549,427**（`$2` = `encodeWebRemoteControlRpcTransportMessage`，节选原文）：

```js
let s=Object.freeze({algorithm:"crc32",value:Xa(t)}),l=1,p=0;
for(;;){let g=n.firstPhysicalSeq+l-1;
 if(!Number.isSafeInteger(g)||g>Number.MAX_SAFE_INTEGER)throw new Je("remote.rpcFrame.sequenceOverflow");
 if(p=jE({identity:r,endSeq:g,messageSeq:n.messageSeq,fragmentCount:l,messageBytes:t.byteLength,
   checksum:s,maxPhysicalFrameBytes:a}),p<1)throw new Je("remote.rpcFrame.envelopeTooLarge");
 let b=Math.ceil(t.byteLength/p);
 if(b>i)throw new Je("remote.rpcFrame.fragmentLimitExceeded");
 if(b<=l)break;l=b}
```

`l` 从 1 起步，`p = 预算(l)`，`b = ceil(字节数/p)`，`b > l` 则 `l = b` 重来，直到 `b <= l`。片数由**末片 seq 的位数**反过来影响预算（seq 变长则 `p` 变小），所以是不动点迭代而不是一次算完。

### 2.4 实算值（按上述公式独立复算，非运行包内代码）

取 `bridgeSessionId` 长度 25、`bridgeGeneration=1`（本机按公式重算，`MAX_SAFE_INTEGER` 时间戳）：

| 逻辑消息字节数 | 分片数 | 每片解码字节数 `p` | 壳信封字节数（dataBase64 为空时） |
|---|---|---|---|
| 1,024 | 1 | 1,024 | 326 |
| 300 KiB | 1 | 307,200 | 328 |
| **768 KiB** | **2** | **786,186** | 328 |
| 1 MiB | 2 | 786,183 | 329 |
| 2 MiB | 3 | 786,183 | 329 |
| 16 MiB | **22** | 786,180 | 333 |

⇒ **单片解码上限 ≈ 786,18x B（767.7 KiB），base64 后 ≈ 1,048,24x B，加上 ~330 B 信封刚好顶到 1 MiB 上限。** BRIEF 里"实算约 768 KiB"成立。

### 2.5 与网页端的关系

网页端 `validation-CqXZOLXO.js` @**131,327** 的 `Bd()`：

```js
function Bd(e){let t=Md(zd({identity:e.identity,seq:e.endSeq,messageSeq:e.messageSeq,
fragmentIndex:e.fragmentCount-1,fragmentCount:e.fragmentCount,messageBytes:e.messageBytes,
checksum:e.checksum,dataBase64:``})),n=0,r=e.messageBytes;
for(;n<r;){let i=Math.ceil((n+r)/2);t+Rd(i)<=e.maxPhysicalFrameBytes?n=i:r=i-1}return n}
```

与桌面端 `jE` **逐字符同构**（`Md`=`Ug`、`zd`=`nv`、`Rd`=`NE`）。编码器 `Vd` @131,648 与桌面端 `$2` 同构。

**结论：桌面端与网页端分片算法完全一致（同一构建的两份副本）。** 二者唯一差别是桌面端**真的会走这条路径发帧**（见 §3），网页端也是。

---

## 3. 出站流水线（桌面端作为发送方）

### 3.1 装配调用点

`out/main/index.js` @约 401,900（`createWorkspaceBridge` 内）：

```js
let Se=new pg(p0(re.port)),He=Mg({bridgeSessionId:q.bridgeSessionId,
 bridgeGeneration:q.bridgeGeneration,recoveryId:q.recoveryId,
 measureFrameBytes:s(Le=>h.transport.measurePayloadBytes?.(Le)??nf(Le),"measureFrameBytes"),
 sendFrame:s(Le=>{if(!q.readyAnnounced||q.degraded)return!1;let Ls=me(h,Le);
  if(Ls.kind==="oversize")throw new Error("remote.rpcFrame.envelopeTooLarge");return Ls.kind==="sent"},"sendFrame")}),
yo=Se.onMessage(Le=>{He.protocol.send(Le)}),ge=He.protocol.onMessage(Le=>{Se.send(Le)}),
Uc=He.onDegraded(Le=>{I(h,q,Le.reasonCode)}),...
```

⇒ host 进程产出的每条逻辑消息经 `He.protocol.send` → `reserveMessage` → `$2`（动态二分分片）→ `sendFrame` → 中继 WS。**桌面端确实用动态二分发帧。**

`Mg` 来自 `out/main/chunk-JS2T33SG.js`（导出 `b`），`Ae`（@16436）=`createAcknowledgedWebRemoteControlRelayProtocol`。

### 3.2 `reserveMessage`：编码 → 测长 → 缓冲 → 冲刷

`out/main/chunk-JS2T33SG.js` @**9019**（`ve` = `AcknowledgedRelayProtocol`）：

```js
reserveMessage(e){if(this.disposed||this.degraded||this.deadline.check())return;let r;
 try{r=Ce(e,{...this.identity,firstPhysicalSeq:this.nextPhysicalSeq,messageSeq:this.nextMessageSeq})}
 catch(S){this.enterDegraded({reasonCode:S instanceof fe?S.reasonCode:"remote.rpcFrame.encodingFailed",terminal:!0});return}
 let o=0;try{o=Re(r,this.measureFrameBytes)}catch{this.enterDegraded({reasonCode:"remote.rpcFrame.outerMeterFailed",terminal:!0});return}
 if(this.unacknowledgedByteCount+o>this.replayBufferMaxBytes){
   this.enterDegraded({reasonCode:"remote.rpcFrame.replayBufferExceeded",terminal:!0,messageSeq:this.nextMessageSeq});return}
 let c=this.nextMessageSeq,u=r.at(-1);
 this.outboundBatches.append({messageSeq:c,frames:r,outerBytes:o,queuedAt:this.now(),nextFrameIndex:0}),
 this.unacknowledgedByteCount+=o,this.nextMessageSeq+=1,this.nextPhysicalSeq=u.seq+1,
 this.updateSaturationAfterReserve(),this.deadline.refresh(),this.flushPendingFrames()}
```

要点：
- `firstPhysicalSeq` 从 1 起、跨消息连续（`nextPhysicalSeq = 末片.seq + 1`）；`messageSeq` 从 1 起、每消息 +1。
- **不传** `maxPhysicalFrameBytes/maxMessageBytes/maxFragments` ⇒ 编码器用 `Ue` 默认值（§1.1）。
- 未 ack 字节数 + 本批字节数 > **8 MiB** ⇒ `replayBufferExceeded` 终态降级。
- 编码器抛出的 `reasonCode` 被原样透传（如 `remote.rpcFrame.messageTooLarge`、`remote.rpcFrame.fragmentLimitExceeded`、`remote.rpcFrame.envelopeTooLarge`、`remote.rpcFrame.sequenceOverflow`、`remote.rpcFrame.emptyMessage`、`remote.rpcFrame.invalidIdentity`）。

### 3.3 `flushPendingFrames`：**ack 优先于数据帧**

```js
flushPendingFrames(){...for(;!this.disposed&&!this.degraded;){
 if(this.deadline.check()){e=!1;break}
 if(this.pendingAckMessageSeq!==null){let u=this.pendingAckMessageSeq;
   if(!this.invokeSend({zcode_type:"rpc-frame-ack",...this.identity,ackMessageSeq:u})||...){e=!1;break}
   this.pendingAckMessageSeq===u&&(this.pendingAckMessageSeq=null,this.pendingAckQueuedAt=null,this.deadline.refresh()),
   this.drainQueuedInbound();continue}
 let r=this.outboundBatches.nextUnsent;if(!r)break;
 let o=r.frames[r.nextFrameIndex];
 if(!this.invokeSend(o)||this.degraded||this.disposed){e=!1;break}
 r.nextFrameIndex+=1,r.nextFrameIndex===r.frames.length&&(this.highestFullySentMessageSeq=Math.max(this.highestFullySentMessageSeq,r.messageSeq),this.outboundBatches.advanceUnsent()),
 this.drainQueuedInbound()}}...}
```

- 待发 ack 一次只保留**一个**（`queueAck` 里取 max）：`queueAck(e){...this.pendingAckMessageSeq===null?(this.pendingAckMessageSeq=e,...):e>this.pendingAckMessageSeq&&(this.pendingAckMessageSeq=e),this.flushPendingFrames()}`。
- `highestFullySentMessageSeq` 只在**整条消息全部片发完**时抬升——这是 `futureAck` 判据的来源（§8）。

### 3.4 `replayUnacknowledged`：重连后从最老的未 ack 批次重发

```js
replayUnacknowledged(){return this.disposed||this.degraded||this.deadline.check()?!1:
 (this.outboundBatches.resetReplay(),this.flushPendingFrames())}
```

`resetReplay(){for(let e=this.headIndex;e<this.storage.length;e+=1){let r=this.storage[e];r&&(r.nextFrameIndex=0)}this.nextUnsentIndex=this.headIndex}`

调用点：`out/main/index.js` @408 附近 `onSendReady:s(()=>{Te(ge);let Pe=ge.currentBridge?.relayProtocol;!Pe||Pe.isDegraded()||Pe.replayUnacknowledged()},"onSendReady")` —— 配对成功 / 重连成功后触发。

---

## 4. 重组器状态机（`XS` = WebRemoteControlRpcTransportAssembler）

坐标：`out/main/chunk-OW6EUS2G.js` @**551,665**（导出 `ca` → relay 协议里叫 `be`）。网页端同构副本在 `validation-CqXZOLXO.js` @约 133,6xx（符号 `Kd`）。

### 4.1 状态字段（原文）

```js
identity;maxPhysicalFrameBytes;maxMessageBytes;maxFragments;timeoutMs;now;
expectedPhysicalSeq;expectedMessageSeq;lastCompletedMessageSeq;
physicalSequenceExhausted=!1;messageSequenceExhausted=!1;
settledFrameFingerprintsBySeq=new Map;active=null;terminalReason=null;
```

构造：`expectedPhysicalSeq = n.initialPhysicalSeq ?? 1`，`expectedMessageSeq = n.initialMessageSeq ?? 1`，`lastCompletedMessageSeq = expectedMessageSeq - 1`。
**桌面端创建时不传 initial\*（`new be({identity,timeoutMs,now})`）⇒ 都从 1 起。**

`active` 形状：`{messageSeq, fragmentCount, messageBytes, checksum, firstSeenAt, stagedBytes:0, fragments:[], frameFingerprintsBySeq:new Map}`

**同一时刻最多只有一个 `active`**（单装配体、单消息、片内严格顺序）——这是与我们的实现最根本的差别。

### 4.2 状态集合

| 状态 | 含义 | 出口 |
|---|---|---|
| `IDLE` | `active=null, terminalReason=null` | 收到合法片 0 → `ASSEMBLING`；任一终态 fault → `TERMINAL` |
| `ASSEMBLING` | `active!=null`，`expectedPhysicalSeq` 已推进 | 凑齐 → `IDLE`（`lastCompletedMessageSeq` 更新、`expectedMessageSeq+=1`）；终态 fault → `TERMINAL`；30 s 到期 → `TERMINAL` |
| `TERMINAL` | `terminalReason!=null`，`active=null` | 不可恢复；此后任何 `accept` 都返回 `fault(terminalReason, true)` |

`physicalSequenceExhausted` / `messageSequenceExhausted` 是**粘性布尔**（seq 撞到 `Number.MAX_SAFE_INTEGER` 时置位），不是独立状态。

### 4.3 `fault()` 原文（决定 terminal 语义）

```js
fault(n,r,a){return r&&(this.terminalReason=n,this.active=null),
 {kind:"fault",fault:{reasonCode:n,terminal:r,
  ...typeof a?.seq=="number"?{seq:a.seq}:{},
  ...typeof a?.messageSeq=="number"?{messageSeq:a.messageSeq}:{},
  expectedSeq:this.expectedPhysicalSeq,expectedMessageSeq:this.expectedMessageSeq}}}
```

**只有 `terminal===true` 的 fault 才写 `terminalReason` 并清 `active`；`terminal===false` 的 fault 是"一次性拒绝"，状态机继续工作。**

### 4.4 `accept()` 的完整判定顺序（原文节选 + 逐条注释）

```js
accept(n,r=this.now()){
 if(ZE(this.identity,n))return this.fault("remote.rpcFrame.identityMismatch",!1);      // ①
 if(this.terminalReason)return this.fault(this.terminalReason,!0);                     // ②
 let a=this.expire(r);if(a)return a;                                                   // ③
 let o=...n.dataBase64:void 0;
 if(typeof o=="string"&&(o.length>this.maxPhysicalFrameBytes||!QS(o)))
   return this.fault("remote.rpcFrame.invalidBase64",!0);                              // ④
 let i=xr.safeParse(n);if(!i.success)return this.fault("remote.rpcFrame.invalidMetadata",!0); // ⑤
 let s=i.data;
 if(!LE(this.identity,s))return this.fault("remote.rpcFrame.identityMismatch",!1,s);    // ⑥
 if(s.messageBytes>this.maxMessageBytes)return this.fault("remote.rpcFrame.messageTooLarge",!0,s);        // ⑦
 if(s.fragmentCount>this.maxFragments)return this.fault("remote.rpcFrame.fragmentLimitExceeded",!0,s);    // ⑧
 if(Ug(s)>this.maxPhysicalFrameBytes)return this.fault("remote.rpcFrame.envelopeTooLarge",!0,s);          // ⑨
 let l=Mg(s.dataBase64);if(!l||l.byteLength===0)return this.fault("remote.rpcFrame.invalidBase64",!0,s);  // ⑩
 let p=BE(s,l),m=this.active?.frameFingerprintsBySeq.get(s.seq);
 if(m)return YS(m,p)?{kind:"duplicate",ackMessageSeq:this.lastCompletedMessageSeq>0?this.lastCompletedMessageSeq:null}
                     :this.fault("remote.rpcFrame.conflictingDuplicate",!0,s);         // ⑪
 let g=this.settledFrameFingerprintsBySeq.get(s.seq);
 if(g)return YS(g,p)?{kind:"duplicate",ackMessageSeq:...}
   :this.physicalSequenceExhausted&&s.messageSeq!==g.messageSeq
     ?this.fault("remote.rpcFrame.physicalSequenceExhausted",!0,s)
     :this.fault("remote.rpcFrame.conflictingDuplicate",!0,s);                        // ⑫
 if(s.seq<this.expectedPhysicalSeq)return{kind:"duplicate",ackMessageSeq:...};          // ⑬
 if(this.physicalSequenceExhausted)return this.fault("remote.rpcFrame.physicalSequenceExhausted",!0,s);  // ⑭
 if(this.messageSequenceExhausted&&!this.active)return this.fault("remote.rpcFrame.messageSequenceExhausted",!0,s); // ⑮
 if(s.seq>this.expectedPhysicalSeq)return this.fault("remote.rpcFrame.physicalGap",!0,s);   // ⑯ ★乱序拒绝
 if(s.messageSeq!==this.expectedMessageSeq)return this.fault("remote.rpcFrame.messageGap",!0,s); // ⑰ ★跨消息乱序拒绝
 if(s.seq===Number.MAX_SAFE_INTEGER&&s.fragmentIndex+1<s.fragmentCount)
   return this.fault("remote.rpcFrame.physicalSequenceExhausted",!0,s);                // ⑱
 let b=this.active?.fragments.length??0;
 if(s.fragmentIndex!==b)return this.fault("remote.rpcFrame.fragmentGap",!0,s);          // ⑲ ★片内乱序拒绝
 if(this.active&&(s.fragmentCount!==this.active.fragmentCount||s.messageBytes!==this.active.messageBytes))
   return this.fault("remote.rpcFrame.metadataMismatch",!0,s);                         // ⑳
 if(this.active&&s.checksum.value!==this.active.checksum.value)
   return this.fault("remote.rpcFrame.checksumMismatch",!0,s);                         // ㉑
 if(this.active||(this.active={messageSeq:s.messageSeq,fragmentCount:s.fragmentCount,messageBytes:s.messageBytes,
   checksum:s.checksum,firstSeenAt:r,stagedBytes:0,fragments:[],frameFingerprintsBySeq:new Map}),
  this.active.stagedBytes+l.byteLength>this.active.messageBytes)
   return this.fault("remote.rpcFrame.lengthMismatch",!0,s);                           // ㉒
 if(this.active.fragments.push(l),this.active.frameFingerprintsBySeq.set(s.seq,p),
  this.active.stagedBytes+=l.byteLength,
  s.seq===Number.MAX_SAFE_INTEGER?this.physicalSequenceExhausted=!0:this.expectedPhysicalSeq+=1,
  this.active.fragments.length<this.active.fragmentCount)
   return{kind:"incomplete",messageSeq:s.messageSeq,receivedFragments:this.active.fragments.length};      // ㉓
 if(this.active.stagedBytes!==this.active.messageBytes)return this.fault("remote.rpcFrame.lengthMismatch",!0,s); // ㉔
 let v=new Uint8Array(this.active.messageBytes),_=0;
 for(let E of this.active.fragments)v.set(E,_),_+=E.byteLength;
 if(Xa(v)!==this.active.checksum.value)return this.fault("remote.rpcFrame.checksumMismatch",!0,s);        // ㉕
 let I=this.active,T=I.messageSeq;
 return this.settledFrameFingerprintsBySeq=new Map(I.frameFingerprintsBySeq),this.active=null,
  this.lastCompletedMessageSeq=T,T===Number.MAX_SAFE_INTEGER?this.messageSequenceExhausted=!0:this.expectedMessageSeq+=1,
  {kind:"complete",messageSeq:T,bytes:v}}                                              // ㉖
```

### 4.5 fault 全表（reasonCode → terminal）

| # | reasonCode | terminal | 触发条件 | 备注 |
|---|---|---|---|---|
| ① | `remote.rpcFrame.identityMismatch` | **false** | 原始载荷里带了 `bridgeSessionId`/`bridgeGeneration`/`recoveryId` 中任一键，且与本地身份不等（`ZE` = `hasRawIdentityMismatch`） | 一次性拒绝，不降级；三个键**都不在**时 `ZE` 返回 false（继续往下走） |
| ② | 粘性 `terminalReason` | true | 已进入终态 | 复读同一个 reasonCode |
| ③ | `remote.rpcFrame.assemblyTimeout` | **true** | `active` 存在且 `now - firstSeenAt >= timeoutMs`（30 s） | `expire()` 触发 |
| ④ | `remote.rpcFrame.invalidBase64` | true | `dataBase64` 是字符串，且 `len > maxPhysicalFrameBytes` 或**非规范 base64**（`QS` = `isCanonicalBase64`：长度 4..1 MiB、能解码、非空、**重编码后与原串逐字符相等**） | 注意这是**编码规范性**检查，不只是"能不能解码" |
| ⑤ | `remote.rpcFrame.invalidMetadata` | true | `xr`（`rpc-frame` schema）`safeParse` 失败 | 含未知键（`.strict()`）、`checksum.algorithm !== "crc32"`、`checksum.value` 不匹配 `^[0-9a-f]{8}$`、`fragmentCount > messageBytes`、`fragmentIndex >= fragmentCount`、id 字符集/长度不合规等 |
| ⑥ | `remote.rpcFrame.identityMismatch` | **false** | schema 解析后的三元组与本地身份**全等**比较失败（`LE`） | 同样不降级 |
| ⑦ | `remote.rpcFrame.messageTooLarge` | true | `messageBytes > maxMessageBytes`（16 MiB） | |
| ⑧ | `remote.rpcFrame.fragmentLimitExceeded` | true | `fragmentCount > maxFragments`（64） | |
| ⑨ | `remote.rpcFrame.envelopeTooLarge` | true | 重新序列化该帧（`Ug(s)`）> 1 MiB | 与 ④ 的 `len` 检查互补：④ 只看 `dataBase64` 串长，⑨ 看整个信封 |
| ⑩ | `remote.rpcFrame.invalidBase64` | true | 解码结果为空或 null | |
| ⑪ | `remote.rpcFrame.conflictingDuplicate` | true | **同一 `seq` 在 `active` 里已存在**且指纹不同 | |
| ⑫ | `remote.rpcFrame.conflictingDuplicate` / `remote.rpcFrame.physicalSequenceExhausted` | true | 同一 `seq` 在 `settled` 里已存在且指纹不同；若 `physicalSequenceExhausted` 且 `messageSeq` 不同则报后者 | |
| ⑬ | —（不是 fault） | — | `seq < expectedPhysicalSeq` ⇒ `{kind:"duplicate"}` | 回 ack，不降级 |
| ⑭ | `remote.rpcFrame.physicalSequenceExhausted` | true | `physicalSequenceExhausted` 已置位而收到新帧 | |
| ⑮ | `remote.rpcFrame.messageSequenceExhausted` | true | `messageSequenceExhausted` 已置位且无 active | |
| ⑯ | `remote.rpcFrame.physicalGap` | **true** | **`seq > expectedPhysicalSeq`** | ★ 乱序即终态 |
| ⑰ | `remote.rpcFrame.messageGap` | **true** | `messageSeq !== expectedMessageSeq` | ★ 跨消息跳号/回退即终态 |
| ⑱ | `remote.rpcFrame.physicalSequenceExhausted` | true | `seq === MAX_SAFE_INTEGER` 且还有后续片 | |
| ⑲ | `remote.rpcFrame.fragmentGap` | **true** | `fragmentIndex !== 已收片数` | ★ 片内乱序/跳片即终态 |
| ⑳ | `remote.rpcFrame.metadataMismatch` | true | active 已存在，且 `fragmentCount` 或 `messageBytes` 与 active 不同 | |
| ㉑ | `remote.rpcFrame.checksumMismatch` | true | active 已存在，且 `checksum.value` 与 active 不同 | 提前拒（不等凑齐） |
| ㉒ | `remote.rpcFrame.lengthMismatch` | true | `stagedBytes + 本片字节 > messageBytes` | |
| ㉔ | `remote.rpcFrame.lengthMismatch` | true | 片齐但 `stagedBytes !== messageBytes` | |
| ㉕ | `remote.rpcFrame.checksumMismatch` | true | 拼出的消息 CRC 与声明不符 | |

**除 ① ⑥ 两个 `identityMismatch`（以及 ⑬ 的 duplicate 分支）之外，全部 fault 都是终态。**

### 4.6 fault 的传播：整座桥降级

`out/main/chunk-JS2T33SG.js` @9019（`processInbound` 节选）：

```js
processInbound(e){let r=ye(e);if(!r){this.enterDegraded({reasonCode:"remote.rpcFrame.invalidPayload",terminal:!0});return}
 if(r.zcode_type==="rpc-frame-ack"){this.processAck(r.ackMessageSeq);return}
 let o=this.assembler?.accept(r,this.now());
 if(o){if(o.kind==="fault"){o.fault.terminal&&this.enterDegraded(o.fault);return}
  if(o.kind==="incomplete"){this.scheduleAssemblyTimer();return}
  if(o.kind==="duplicate"){o.ackMessageSeq!==null&&this.queueAck(o.ackMessageSeq);return}
  this.clearAssemblyTimer();try{this.onMessageEmitter.fire(h.wrap(o.bytes))}
  catch{this.enterDegraded({reasonCode:"remote.rpcFrame.deliveryFailed",terminal:!0,messageSeq:o.messageSeq});return}
  this.queueAck(o.messageSeq)}}
```

`enterDegraded(e)` 把协议置为 `degraded`（清空重放缓冲、待发 ack、装配器、dispose deadline），并 fire `degradedEmitter`。桌面端在 `out/main/index.js` @约 392,900 的 `I`（`degradeBridgeAfterRawFault`）：

```js
function I(h,C,A){!b(h,C)||C.degraded||(C.degraded=!0,e.logger.warn("[web-remote-control] raw relay bridge degraded",{windowId:h.windowId,session:h.deviceSid,bridgeSessionId:C.bridgeSessionId,bridgeGeneration:C.bridgeGeneration,recoveryId:C.recoveryId,reasonCode:A}),
 le(h,{zcode_type:"bridge-degraded",bridgeSessionId:C.bridgeSessionId,
  ...C.bridgeGeneration===void 0?{}:{bridgeGeneration:C.bridgeGeneration},
  ...C.recoveryId?{recoveryId:C.recoveryId}:{},reason:"rpc-transport-fault"}))}
```

⇒ **对端会把 reasonCode 只写日志，给客户端发的是 `{zcode_type:"bridge-degraded", reason:"rpc-transport-fault"}`。** 之后 `Ut(h,C)`（`routeRawTransportCandidate`）恒返回 false（`A.degraded` 为真），该桥的 rpc 通道彻底死掉。

---

## 5. 序号规则

### 5.1 出站产生（发送方）

| 计数器 | 初值 | 步进 | 说明 |
|---|---|---|---|
| `nextPhysicalSeq` | 1 | 每发**一片** +1；整条消息发完置为 `末片.seq + 1` | 跨消息连续，永不回退 |
| `nextMessageSeq` | 1 | 每调一次 `reserveMessage` +1 | 即使编码抛错也已经/将要 +1（抛错走 degraded 分支，不再发） |

编码器内：第 `i` 片的 `seq = firstPhysicalSeq + i`（`$2` 原文 `seq:n.firstPhysicalSeq+g`），`fragmentIndex = i`，`fragmentCount = l`。

`seq` 上限：`$g = int().positive().max(Number.MAX_SAFE_INTEGER)`（@545,914）；编码器另有 `sequenceOverflow` 检查（`!Number.isSafeInteger(g) || g > MAX_SAFE_INTEGER`）。

### 5.2 入站校验（接收方）

- `expectedPhysicalSeq` / `expectedMessageSeq` 从 **1** 起（桌面端不传 initial\*）。
- 判定链见 §4.4 ⑬⑭⑯⑰⑱：`seq < expected` → **duplicate**（回 ack）；`seq > expected` → **`physicalGap` 终态**。
- `fragmentIndex` 不参与 seq 校验，而是独立要求 **严格等于已收片数**（⑲）。
- `messageSeq` 必须 **严格等于** `expectedMessageSeq`（⑰），不许跳号也不许回退。

### 5.3 乱序是否接受？——**不接受，且是终态降级**

- 片间乱序（`seq` 跳号）：`physicalGap` 终态。
- 片内乱序（同消息内 `fragmentIndex` 非顺序到达）：`fragmentGap` 终态。
- 跨消息乱序（后一条消息的片先到）：`messageGap` 终态。
- 唯一"容忍"的乱序是**已经处理过的旧 seq**（`seq < expectedPhysicalSeq`）→ 视作 duplicate，只回 ack。

**BRIEF 里"网页端不接受乱序、`fragmentGap` 即终态降级"的说法成立，并且桌面端（对端权威）同构。** 实践中 relay 走 TCP，同一连接内不会真乱序；这条约束是防御性的，但对我们的实现有直接后果（见 §10）。

---

## 6. 去重

### 6.1 指纹定义（`BE` = `frameFingerprint`，`chunk-OW6EUS2G.js` @551,316）

```js
function BE(t,n){return{messageSeq:t.messageSeq,value:JSON.stringify([t.bridgeSessionId,
 t.bridgeGeneration??null,t.recoveryId??null,t.seq,t.messageSeq,t.fragmentIndex,t.fragmentCount,
 t.messageBytes,t.checksum.algorithm,t.checksum.value,n.byteLength,Xa(n)])}}
function YS(t,n){return t.value===n.value}
```

指纹覆盖：**身份三元组 + seq + messageSeq + fragmentIndex + fragmentCount + messageBytes + checksum.algorithm + checksum.value + 本片解码后字节数 + 本片解码后字节的 crc32**。⇒ 同 `seq` 但字节被改（哪怕长度相同）也会被识别为冲突。

### 6.2 两张表的语义

| 表 | 生命周期 | 键 | 值 | 语义 |
|---|---|---|---|---|
| `active.frameFingerprintsBySeq` | 随 `active` 装配体 | `seq` | 指纹 | 当前正在装配的**这一条消息**里每个物理 seq 的指纹 |
| `settledFrameFingerprintsBySeq` | 跨消息，**整体替换** | `seq` | 指纹 | 上一条**已完成**消息的指纹表快照：`this.settledFrameFingerprintsBySeq=new Map(I.frameFingerprintsBySeq)`（@㉖）。**不是累积**，只保留最近一条 |

### 6.3 重复帧的处理

| 情形 | 判定 | 行为 |
|---|---|---|
| 同 `seq`，指纹相同，落在 `active` 内 | ⑪ | `{kind:"duplicate", ackMessageSeq: lastCompletedMessageSeq>0 ? lastCompletedMessageSeq : null}` ⇒ 若不为 null 则 `queueAck` 回 ack；**不重投消息、不推进 seq** |
| 同 `seq`，指纹相同，落在 `settled` 内 | ⑫ | 同上（duplicate + 回 ack） |
| 同 `seq`，指纹**不同** | ⑪/⑫ | `conflictingDuplicate` **终态降级** |
| `seq < expectedPhysicalSeq`（不在任一表里） | ⑬ | `{kind:"duplicate", ackMessageSeq:...}` ⇒ 回 ack |
| `settled` 命中且指纹不同，同时 `physicalSequenceExhausted` 且 `messageSeq` 不同 | ⑫ | `physicalSequenceExhausted` 终态 |

**重复帧的回 ack 是"最后一条已完成消息的 messageSeq"**（`lastCompletedMessageSeq`），不是重复帧自己的 `messageSeq`。当 `lastCompletedMessageSeq === 0`（还没完成过任何消息）时 `ackMessageSeq = null` ⇒ **不回 ack**。

### 6.4 为什么需要这套去重

`replayUnacknowledged()`（§3.4）会在重连/配对就绪时把**所有未 ack 的批次从头重发**。对端因此必须能识别"这是重传"，既不重复投递也不报错。我们侧没有对应的记忆（§10）。

---

## 7. CRC

### 7.1 算法（`Xa` = `crc32WireBytes`，`chunk-OW6EUS2G.js` @544,721）

```js
function Xa(t){let n=4294967295;for(let r of t){n^=r;for(let a=0;a<8;a+=1)n=n>>>1^(n&1?3988292384:0)}return((n^4294967295)>>>0).toString(16).padStart(8,"0")}
```

- 多项式 `3988292384` = `0xEDB88320`（IEEE 反射式），初值 `0xFFFFFFFF`，末取反 `0xFFFFFFFF`。
- 输出 `>>>0` 后 `toString(16).padStart(8,"0")` ⇒ **8 位小写十六进制**。
- 与我们 `rust/crates/protocol/src/crc32.rs` 的 `compute`/`hex_of` 等价（同表、同掩码、`format!("{:08x}")`）。

### 7.2 覆盖范围：**整条逻辑消息**

发送侧：`$2` 在分片之前算一次 —— `let s=Object.freeze({algorithm:"crc32",value:Xa(t)})`，`t` 是**未分片的整条消息字节**，然后每片都带同一个 `checksum`。
接收侧：`Xa(v)` 对**拼接完成后的整条消息**（`v` 长度 = `messageBytes`）校验（㉕）。
⇒ **CRC 不是每片校验**。另外 §6.1 的指纹里额外对**单片字节**算了一次 `Xa(n)`，那是去重用的内部指纹，不上线。

### 7.3 `checksum.value` 格式与 schema 约束

`chunk-OW6EUS2G.js` @546,214（`$E`）：

```js
$E=e.object({algorithm:e.literal("crc32"),value:e.string().regex(/^[0-9a-f]{8}$/u)}).strict()
```

- `algorithm` 被锁为**字面量** `"crc32"`：传别的值 ⇒ 整帧 schema 失败 ⇒ `invalidMetadata` **终态降级**（不是忽略）。
- `value` 必须恰好 8 位小写 hex；`.strict()` ⇒ 多一个键也失败。
- 该对象是 `xr`（`rpc-frame`）的必填字段（无 `.optional()`）。

⚠️ 注意与我们实现的一处**方向性差异**：我们收端**不读 `algorithm`**（`rpc_transport.rs:327` 注释 + 测试 `checksum_algorithm_field_is_ignored`），对端则把 `algorithm !== "crc32"` 当**致命错误**。作为收端我们更宽容（不影响上线）；作为发端我们恒写 `"crc32"`（正确）。

---

## 8. ack 规则

### 8.1 何时回 ack

| 触发 | 代码 | ack 值 |
|---|---|---|
| 消息**成功重组**（片齐 + 长度 + CRC 全过） | `processInbound` 末尾 `this.queueAck(o.messageSeq)` | 该消息的 `messageSeq` |
| 收到**重复帧**且 `ackMessageSeq !== null` | `if(o.kind==="duplicate"){o.ackMessageSeq!==null&&this.queueAck(o.ackMessageSeq)}` | `lastCompletedMessageSeq`（最后一条已完成消息） |
| 未凑齐（incomplete） | 不回 ack，只 `scheduleAssemblyTimer()` | — |
| 任何 fault | 不回 ack（直接降级） | — |

`queueAck(e)` **合并**：只保留最大的待发 seq；同一个 ack 在一次 `flushPendingFrames` 里先于数据帧发出（§3.3）。

### 8.2 ack 的字段（`rpc-frame-ack` schema `Dg`，`chunk-OW6EUS2G.js` @547,205）

```js
Dg=e.object({zcode_type:e.literal("rpc-frame-ack"),bridgeSessionId:no,
 bridgeGeneration:_r.optional(),recoveryId:no.optional(),ackMessageSeq:$g}).strict()
```

桌面端**实际发出的** ack（`flushPendingFrames` 里）：

```js
{zcode_type:"rpc-frame-ack",...this.identity,ackMessageSeq:u}
```

`this.identity` = `{bridgeSessionId, ...(bridgeGeneration!==undefined?{bridgeGeneration}:{}), ...(recoveryId!==undefined?{recoveryId}:{})}`。

⇒ **桌面端的 ack 一定带 `bridgeGeneration`（若客户端在 `workspace-bridge-open` 里给了）**，而 schema 允许不带。

### 8.3 ack 的接收与校验（**关键**）

`out/main/chunk-JS2T33SG.js` @**7688**（`ke` = `rawIdentityMatches`）与 @9019（`ve.acceptPayload`）：

```js
function ke(n,e){if(typeof e!="object"||e===null)return!1;let r=e;
 return r.bridgeSessionId===n.bridgeSessionId&&r.bridgeGeneration===n.bridgeGeneration&&r.recoveryId===n.recoveryId}
```

```js
acceptPayload(e){return this.disposed||this.degraded||!Pe(e)||!ke(this.identity,e)?!1:
 this.deadline.check()?!0:this.outboundCallDepth>0?(this.queuedInbound.push(e),!0):(this.processInbound(e),!0)}
```

`Pe(e)` 只判断 `zcode_type ∈ {rpc-frame, rpc-frame-ack}`。**`ke` 是严格全等（含 `undefined` 与 `undefined` 相等），对 ack 和数据帧一视同仁。** 不等 ⇒ 返回 `false` ⇒ `out/main/index.js` 的 `Ut` 返回 false ⇒ `handleDataPayload` 落到 `vf(t)`（应用层 schema）⇒ 失败 ⇒

```js
let r=vf(t);if(!r){this.options.logger.warn("[web-remote-control] invalid external relay payload dropped",{...hS(t)});return}
```

⇒ **静默丢弃，无任何错误回执。**

**因此：BRIEF §6.4 那条"已否定"的说法，在桌面端（对端权威）视角下需要修正** ——
「ack 必须带三元组，否则被静默忽略」**在桌面端成立，但准确的表述是**：

> ack（以及数据帧）的 `bridgeSessionId` / `bridgeGeneration` / `recoveryId` 必须与桌面端从 `workspace-bridge-open` 请求里存下的身份**逐个严格相等**；schema 层面三者可选，但**"缺失"本身是一个参与比较的值**。

推论：
- 客户端**不带** `bridgeGeneration`（该键不出现）⇒ 桌面端身份里也没有该键 ⇒ ack 不带 ⇒ **匹配通过**。
- 客户端**带了** `bridgeGeneration`（我们的实现总是带，`zemote_client.rs:2417`）⇒ 桌面端身份里有 ⇒ ack **必须带同值**。

§6.4 说"网页端自己的 ack 接收路径根本不校验 identity（主包 @6,364,869）"这一点不与本文冲突：**网页端作为客户端不校验对端 ack 的身份，但桌面端作为对端校验客户端的 ack 身份**——两件事，方向不同。

### 8.4 ack 超时：双路 45 s grace

`out/main/chunk-JS2T33SG.js` @**6235**（`f` = `AcknowledgedRelayDeadline`）：

```js
select(){let e=this.readState(),r=e.oldestData,o=e.pendingAck;return!r&&!o?null:
 o&&(!r||o.queuedAt<=r.queuedAt)?{deadline:o.queuedAt+this.graceMs+1,
   fault:{reasonCode:"remote.rpcFrame.ackGraceExceeded",terminal:!0,messageSeq:o.messageSeq}}
 :{deadline:r.queuedAt+this.graceMs+1,
   fault:{reasonCode:"remote.rpcFrame.replayGraceExceeded",terminal:!0,messageSeq:r.messageSeq}}}
```

`readState()`（`ve` 构造函数里传入）返回 `{oldestData: 最老未 ack 数据批次, pendingAck: 待发 ack}`。

| 情形 | 触发时限 | reasonCode | terminal |
|---|---|---|---|
| 有**待发 ack** 且（无未 ack 数据，或待发 ack 更早） | `pendingAckQueuedAt + 45 s + 1 ms` | `remote.rpcFrame.ackGraceExceeded` | **true** |
| 有**未 ack 数据批次** | `oldestBatch.queuedAt + 45 s + 1 ms` | `remote.rpcFrame.replayGraceExceeded` | **true** |

- `refresh()` 在 `reserveMessage` / `queueAck` / `processAck` / `replayUnacknowledged` 之后调用，重排定时器。
- **超时后怎么办**：`onExpired` = `o=>this.enterDegraded(o)` ⇒ 整座桥降级 + 给客户端发 `bridge-degraded`（§4.6）。**没有重试、没有重连逻辑，桥直接废掉**（客户端需要重新 `workspace-bridge-open`）。
- 45 s 的构成：客户端有 45 s 的窗口把 ack 发回来（其中桌面端每 10 s 一次心跳，链路断了也有 ~4 次心跳周期可察觉）。

### 8.5 ack 的处理（`processAck`）

```js
processAck(e){if(e<=this.lastAckedMessageSeq)return;
 if(e>this.highestFullySentMessageSeq){this.enterDegraded({reasonCode:"remote.rpcFrame.futureAck",terminal:!0,messageSeq:e});return}
 let{releasedBytes:r}=this.outboundBatches.releaseThrough(e);
 this.unacknowledgedByteCount=Math.max(0,this.unacknowledgedByteCount-r),this.lastAckedMessageSeq=e,
 this.deadline.refresh(),this.saturated&&this.unacknowledgedByteCount<=this.lowWaterMarkBytes&&(this.saturated=!1,this.drainedEmitter.fire())}
```

- 过期/重复 ack（`<= lastAckedMessageSeq`）→ **静默忽略**。
- **ack 大于已完整发出的最大 messageSeq ⇒ `futureAck` 终态降级。** ⇒ 我们**绝不能** ack 一个对端还没发完的消息序号。
- `releaseThrough(e)` 释放所有 `messageSeq <= e` 的批次（累积释放）。
- 水位：`unacknowledgedByteCount > 1 MiB` ⇒ `saturated`（桌面端转成给 host 的 `flow-state: saturated`，`out/main/index.js` @402,076）；降到 `<= 256 KiB` ⇒ `drained`。

---

## 9. 端到端 fault 清单（relay 协议层，非装配器）

`out/main/chunk-JS2T33SG.js` @9019 `ve` 全量：

| reasonCode | 触发点 | terminal |
|---|---|---|
| `remote.rpcFrame.manuallyDegraded` | `markDegraded()` 默认参数 | true |
| 编码器 reasonCode（`messageTooLarge`/`fragmentLimitExceeded`/`envelopeTooLarge`/`sequenceOverflow`/`emptyMessage`/`invalidIdentity`/`invalidPhysicalSeq`/`invalidMessageSeq`/`invalidPhysicalLimit`/`invalidMessageLimit`/`invalidFragmentLimit`） | `reserveMessage` 捕获编码异常 | true |
| `remote.rpcFrame.encodingFailed` | 编码异常但不是 `Je` 类 | true |
| `remote.rpcFrame.outerMeterFailed` | `measureFrameBytes` 抛错 | true |
| `remote.rpcFrame.replayBufferExceeded` | 未 ack 字节 + 新批 > 8 MiB | true |
| `remote.rpcFrame.sendFailed` | `sendFrame` 返回 false 或抛错（含 WS 层 1 MiB 超限） | true |
| `remote.rpcFrame.invalidPayload` | `M2`（载荷 schema）解析返回 null | true |
| `remote.rpcFrame.deliveryFailed` | 上层 `onMessage` 处理抛错 | true |
| `remote.rpcFrame.ackGraceExceeded` | 待发 ack 超 45 s | true |
| `remote.rpcFrame.replayGraceExceeded` | 未 ack 数据超 45 s | true |
| `remote.rpcFrame.futureAck` | ack > `highestFullySentMessageSeq` | true |
| 装配器全部 fault（§4.5） | `accept` | 除 `identityMismatch` 外全 true |

另外 `out/main/index.js` @约 377,000（`WebRemoteControlDeviceTransport`）在 WS 收/发两侧各有硬闸：

```js
r.on("message",n=>{...if(ma(n)>kr.maxPhysicalFrameBytes){this.options.logger.warn("[web-remote-control] oversize external relay message dropped",{bytes:ma(n),maxBytes:kr.maxPhysicalFrameBytes}),this.options.onRawTransportFault?.("remote.rpcFrame.envelopeTooLarge");return}...
```

```js
send(t){...let n=Buffer.byteLength(r,"utf8");return n>kr.maxPhysicalFrameBytes?(this.options.logger.warn("[web-remote-control] outbound relay message exceeds hard limit",...),!1):this.sendSerialized(t,r)}
```

`kr` 由 `out/main/index.js` 第 505 行的 import 表 `Z as kr` 绑定，即 `chunk-OW6EUS2G.js` 的 `Z` = `Ue`（§1.1）⇒ **两侧硬闸都是 1 MiB**，与 `maxPhysicalFrameBytes` 同源。

---

## 10. 与我们的实现的差异

对照文件：`E:\Zcode_android\rust\crates\protocol\src\rpc_transport.rs`（**只读**）、`crc32.rs`、`zemote_client.rs`。
参考 Dart 实现：`E:\Zcode_android\Reference_code\lib\protocol\rpc_transport.dart`（我们是从它移植的，**它的常量与收端校验本身就是"网页客户端视角的猜测"，不是权威**）。

### 10.1 常量类不一致

| 项 | 桌面端（权威） | 我们 | 位置 | 后果 |
|---|---|---|---|---|
| `maxMessageBytes` | **16 MiB** | **64 MiB** | `rpc_transport.rs:66` | 我们**会发出**对端必然拒绝的消息：对端 `accept` 第 ⑦ 步 `messageTooLarge` **终态降级**整座桥。BRIEF 判断正确，确认为偏松 |
| `maxFragments` | **64** | **256** | `rpc_transport.rs:72` | 我们**会发出** `fragmentCount > 64` 的帧 ⇒ 对端第 ⑧ 步 `fragmentLimitExceeded` **终态降级**。BRIEF 判断正确 |
| 分片尺寸 | **动态二分，实算 ≈786,18x B/片** | **固定 512 KiB** | `rpc_transport.rs:78` | 见 §10.2（**这是最严重的一条**）。BRIEF 判断正确 |
| 装配超时 | **30 s**（`assemblyTimeoutMs`），到期是**终态降级** | **60 s（实际 61,000 ms）**，到期只**丢弃装配体**、桥继续 | `rpc_transport.rs:84`、`473-486` | 我们对半死连接多容忍 30 s；且我们把对端的终态故障降级成了软清理，行为不等价。BRIEF 判断正确 |
| 装配超时的计时基准 | 对端：`active.firstSeenAt`（**单一 active 装配体**的首片时间） | 我们：每条 `messageSeq` 各自的 `created_at_ms` | 同上 | 对端"整条流水线停 30 s 就降级"，我们"某条消息停 61 s 才清理" |
| 物理 seq 上限 | `Number.MAX_SAFE_INTEGER`（2^53-1），撞到即 `physicalSequenceExhausted` | `u64`，无上限、无检查 | `rpc_transport.rs:220` | 仅理论差异 |

### 10.2 分片算法与"逆序回灌"测试（重点）

**(a) 分片算法不一致。** 桌面端/网页端用 §2 的动态二分（≈786,18x B/片）；我们固定 512 KiB（`rpc_transport.rs:78,288,294-297`）。方向是"我们发得更碎"——对端收我们的 512 KiB 片**不违规**（对端 `accept` 没有单片解码长度的检查）。**真正的问题是反方向**：

**(b) 我们收不了对端的大分片（严重）。** `rpc_transport.rs:398-402`：

```rust
// Dart: if (chunk.length > _fragmentPayloadBytes) return true;
if chunk.len() > FRAGMENT_PAYLOAD_BYTES {
    outcome.consumed = true;
    return outcome;
}
```

`FRAGMENT_PAYLOAD_BYTES = 512 KiB`（`:78`）。对端发的是 ~786 KiB/片（§2.4）⇒

- **任何解码后 >512 KiB 的对端消息**（包括**单片**的 600 KiB 消息，因为对端单片上限是 786 KiB）**每一片都被我们静默丢弃**（`consumed=true`、**连日志都没有**）；
- 我们永远凑不齐 ⇒ **永远不回 ack** ⇒ 对端 `replayGraceExceeded` 45 s 后**终态降级**（§8.4）；
- 且对端 `replayUnacknowledged()` 会反复重发同一批（§3.4），每次都被我们丢 ⇒ 死循环直到降级。

这条在 Dart 参考实现里同样存在（`Reference_code/lib/protocol/rpc_transport.dart:148`），是**上游移植缺陷**，不是 Rust 侧新引入的。

**(c) "逆序回灌"测试与对端行为不符。** `rpc_transport.rs:737-776` 的 `multi_fragment_message_roundtrip` 把发出去的 3 片**逆序**回灌并断言能重组；`rpc_transport.rs:972-1009` 的 `reassembly_with_out_of_order_fragments` 先喂 `fragmentIndex=1` 再喂 `0`。

- 对端遇到这两种输入分别走 **⑯ `physicalGap`（终态）** 与 **⑲ `fragmentGap`（终态）** ⇒ **整座桥降级**。
- 也就是说这两个测试断言的"宽容乱序"能力**不是对端行为**，我们对端永远不会这样发；它们是移植时凭空加的"健壮性"，**不能当作协议兼容性的证据**，反而掩盖了 §10.2(b) 那类真问题。
- 建议：保留测试（作为本地健壮性回归）但**显式标注"对端不具备该行为，此路径永不出现"**，并**补一条"对端 786 KiB 分片必须能收"的测试**。

**(d) 序号完全不校验。** 我们的 `accept_payload` 从不读 `seq`（`rpc_transport.rs:350-358` 只读 messageSeq/fragmentIndex/fragmentCount/messageBytes/dataBase64/checksum）。后果：
- 无法识别 gap / 乱序 / 重复（对端 §4.4 的 ⑯⑰⑲ 全部无对应）；
- **无去重记忆**：对端 `replayUnacknowledged()` 重发时，若那条消息我们**已经重组并投递过**，我们会**再投递一次**（上层会收到重复的逻辑消息）。对端此情形只回 ack、不重投（§6.3）。

**(e) 并发装配体。** 我们按 `messageSeq` 建**任意多条**并发装配体（`assemblies: BTreeMap`，`:230`）；对端同一时刻只有**一条** active，且要求 `messageSeq` 严格递增、片内严格顺序。对端不会这样发，所以不致命，但我们对"半途换 messageSeq 后回退"这类畸形输入的行为与对端不同（对端终态降级）。

### 10.3 ack 不一致

| 项 | 桌面端 | 我们 | 位置 | 后果 |
|---|---|---|---|---|
| **ack 是否带 `bridgeGeneration`/`recoveryId`** | **带**（`{...this.identity}`） | **不带**（`ack_payload` 硬置 `None`） | `rpc_transport.rs:535-550`（测试断言在 `:1039-1040`、`:1214-1221`） | **致命**：对端 `ke(this.identity,e)` 严格全等比较（§8.3）⇒ 我们所有 ack 被丢弃 ⇒ 45 s 后 `replayGraceExceeded` 终态降级。**我们的客户端总是发 `bridgeGeneration`**（`zemote_client.rs:2417`、`:2439`），所以对端身份里一定有该键 |
| ack 的合并与优先级 | 合并（只留最大待发 seq）、**先于数据帧**发 | 立即内联发一条，无合并、无优先级 | `rpc_transport.rs:445-453` | 轻微（功能上能到达） |
| 重复帧是否回 ack | **回**（`lastCompletedMessageSeq`） | **不回**（重复片只覆盖槽位） | `rpc_transport.rs:416-423` | 对端重放时不会因我们的重复 ack 释放缓冲；但也不致命 |
| ack 发送失败 | 抛错 → 终态降级（`sendFailed`） | 记一条日志、消息照投 | `rpc_transport.rs:446-453` | 我们的选择更"不丢消息"，但与对端不等价 |
| `ackMessageSeq` 上限 | `$g` = positive safe int（1..2^53-1） | `u64` | `rpc_transport.rs:166` | 理论 |
| `futureAck` 防护 | 有（ack > 已完整发出 ⇒ 终态） | 无 | — | 我们不可能发出过大 ack（只 ack 刚凑齐的 messageSeq），低风险 |

### 10.4 校验/格式不一致（我们更宽）

| 项 | 桌面端 | 我们 | 位置 |
|---|---|---|---|
| `checksum.algorithm` | 字面量 `"crc32"`，不符即 `invalidMetadata` 终态 | **不读**（`rpc_transport.rs:327` 注释、测试 `:1134-1169`） | 收端更宽，无害 |
| `dataBase64` 规范性 | `isCanonicalBase64`：长度 4..1 MiB + 可解码 + 非空 + **重编码逐字符相等**（拒绝 url-safe、拒绝缺 padding） | 接受 url-safe（`-`/`_`）与缺 padding（`decode_base64_like_dart`，`:624-637`） | 收端更宽，无害 |
| 信封总长 | 每次 `Ug(s) > 1 MiB` ⇒ `envelopeTooLarge` 终态（⑨）；WS 层另有 1 MiB 硬闸 | **完全不测信封长度** | `rpc_transport.rs:270-312` | 512 KiB 分片下不会超；但若按 §10.2 改成分片尺寸自适应而忘了这条，会踩坑 |
| 必填/额外键 | `.strict()`：未知键、`fragmentCount > messageBytes`、`fragmentIndex >= fragmentCount` 都是**终态** | 只查 `fragmentCount > MAX_FRAGMENTS || messageBytes > MAX_MESSAGE_BYTES` 与几个 `< 0`，未知键忽略 | `rpc_transport.rs:369-388` | 收端更宽 |
| `bridgeSessionId` 字符集/长度 | `min(1).max(256)` + `/^[A-Za-z0-9._~-]+$/` | 不校验（生成的 id 形如 `bridge-<uuid>`，天然合规） | `id.rs:102-108` | 无实际风险 |
| 超限消息的处理 | **终态降级**（⑦⑧） | 打一条日志、丢掉（`rpc_transport.rs:369-376`，注释引 issue #9） | 行为不同：对端会把整座桥打死，我们只是丢消息 | 我们更"温和"，但会让上层看到一个静默的桥 |

### 10.5 我们完全未实现的对端行为

1. **relay 层重放缓冲**（`AcknowledgedRelayBatchQueue`，`chunk-JS2T33SG.js` @4835）：未 ack 批次保留 + `replayUnacknowledged` 重发 + 8 MiB 上限。
2. **45 s 双路 grace 看门狗**（`ackGraceExceeded` / `replayGraceExceeded`）。
3. **水位/背压**（`saturationHighWaterMarkBytes` 1 MiB / `saturationLowWaterMarkBytes` 256 KiB → `saturated`/`drained`）。
4. **装配器级终态降级**：对端任何一个 fault 都会打掉整座桥并通知客户端 `bridge-degraded`；我们只有 `purge_stale` 软清理。
5. **`bridge-degraded` 帧的处理**：`zemote_client.rs` 是否处理该帧需要单独核对（本文未覆盖，见 §11）。
6. **`identity` 三元的严格比较**（`ke`/`LE`）：我们对收到的帧只比 `bridgeSessionId`（`rpc_transport.rs:343-348`），不比 `bridgeGeneration`/`recoveryId`，也不区分"键缺失"与"值不同"。
7. **`Number.MAX_SAFE_INTEGER` 溢出防护**（`physicalSequenceExhausted` / `messageSequenceExhausted` / `sequenceOverflow`）。

### 10.6 旁注：另一层分片（不要混）

`rust/crates/protocol/src/conversation.rs` / `conversation_transport.rs` 里的 `LogicalFrameAssembly` 是 **host IPC 逻辑帧**层（`kind:"fragment"` + `topic`/`subscriptionId`），权威预算是 `Re.logicalFrameAssemblyMaxBytes` 16 MiB / `logicalFrameAssemblyMaxFragments` **1024** / `logicalFrameAssemblyTimeoutMs` 30 s。该层也有同样的 60 s vs 30 s 偏差：`conversation.rs:77` `FRAGMENT_STALE_SECS = 60`，而权威是 30 s。**本文的结论只针对 relay 的 `rpc-frame`，不要套到这一层**（但 60→30 的修正建议同样成立）。

---

## 11. 仍未证实 / 需要抓包

1. **`acceptPayload` 早退顺序的实测确认**：桌面端 `acceptPayload` 的 `ke` 检查在**所有**其他检查之前（源码如此）。但"我们的 ack 真的被丢掉"这一步**只有代码证据，没有线上证据**。需要抓一次真实会话：观察我们发 ack 之后对端是否在 ~45 s 后发 `bridge-degraded`，以及桌面端日志里是否出现 `invalid external relay payload dropped`（带 `zcode_type: "rpc-frame-ack"`）。**这是最高优先级的一条。**
2. **对端实际发给我们的大消息尺寸分布**：§10.2(b) 推出"任何 >512 KiB 的对端消息都收不到"，需要抓包确认对端真的会发 >512 KiB 的逻辑消息（预期会：会话快照、附件读回、长对话行）。若实测从未超过 512 KiB，则严重度下降，但仍必须修。
3. **`bridge-degraded` 之后对端是否会自动重开桥**：`I()` 只置 `degraded` 并发帧；客户端要不要重新 `workspace-bridge-open`、对端是否接受同 `bridgeSessionId` 重开，**未在本文范围内证实**。
4. **`onSendReady` 的触发条件与 `replayUnacknowledged` 的实际频率**：`applyPairStatus("matched")` 里 `n&&this.options.onSendReady?.({kind:n})`，`n` 只在 `lastPairedSocketGeneration !== 0` 时非 null ⇒ 首次配对**不**触发 replay，重连后才触发。需抓包确认重连场景下对端真的从最老未 ack 批次重发。
5. **`M2`/`ye`（载荷 schema `ev`）是否 `.strict()`**：`ev=e.union([xr,Dg])` 而 `xr`/`Dg` 都是 `.strict()` ⇒ 我们发的帧多一个键就会被 `invalidPayload` 终态降级。我们目前字段零漂移，但**任何"顺手加个字段"都会打死桥**，值得写进实现纪律。
6. **装配器 30 s 超时的计时精度**：`scheduleAssemblyTimer()` 用 `assembler.nextExpiryAt` 排一个 `setTimeout`；`active.firstSeenAt` 是**首片**到达时间。若首片在 t=0 到、第 2 片在 t=29.9 s 到，则 t=30 s 到期检查时 `active` 仍在 ⇒ 终态。这一"整条消息 30 s 内必须收完"的语义需实测确认（尤其大消息 + 慢链路）。
7. **`transportEnvelopeIdMaxChars = 256` 与 `no` 的 `min(1)`**：我们的 `recoveryId` 来自对端 `workspace-bridge-ready` 的 `bridge.recoveryId`（`zemote_client.rs:1758`、`:1998-2001`），若对端回一个空串会被 `min(1)` 拒；未证实对端是否可能回空串（`f0` 里 `...e.recoveryId?{recoveryId:e.recoveryId}:{}` 是**真值**判断，空串会被省略，所以大概率不会出现）。
8. **`conversation_transport.rs` 是否处理 `bridge-degraded`**：本文只核对到 `rpc_transport.rs`，`bridge-degraded` 的客户端侧处理需另一篇覆盖。

---

## 附录 A · 坐标索引（便于复核）

| 内容 | 文件 | 字节偏移 | 符号 |
|---|---|---|---|
| 全局预算 `Re` | `out/main/chunk-OW6EUS2G.js` | 534,064 | `Re` |
| relay 预算 `Ue` | 同上 | 545,748 | `Ue`（导出 `Z`） |
| 预算副本（host） | `out/host/chunk-OUCOFHX4.js` | 485,520 | `we` |
| CRC-32 | `out/main/chunk-OW6EUS2G.js` | 544,721 | `Xa` |
| base64 编码 / 解码 | 同上 | 544,975 / 545,287 | `zg` / `Mg` |
| `isCanonicalBase64` | 同上 | 546,308 | `QS` |
| `rpc-frame` schema | 同上 | 546,462 | `xr` |
| `rpc-frame-ack` schema | 同上 | 547,205 | `Dg` |
| 载荷 union / 解析 | 同上 | 547,359 / 548,073 | `ev` / `M2` |
| `base64Length` / `frameShell` | 同上 | 548,771 / 548,831 | `NE` / `nv` |
| **动态二分预算** | 同上 | **549,076** | `jE`（网页 `Bd` @131,327） |
| **编码器（分片）** | 同上 | **549,427** | `$2`（网页 `Vd` @131,648） |
| 身份全等 `sameIdentity` | 同上 | 550,881 | `LE` |
| `hasRawIdentityMismatch` | 同上 | 551,034 | `ZE` |
| 帧指纹 | 同上 | 551,316 | `BE` |
| **装配器** | 同上 | **551,665** | `XS`（导出 `ca`） |
| relay 协议实现 | `out/main/chunk-JS2T33SG.js` | 9,019 | `ve` |
| 重放队列 | 同上 | 4,835 | `y` |
| 45 s 双路 deadline | 同上 | 6,235 | `f` |
| relay 层预算 `m` | 同上 | 7,203 | `m` |
| `rawIdentityMatches` | 同上 | 7,688 | `ke` |
| 工厂 | 同上 | 16,436 | `Ae` |
| WS 层 1 MiB 硬闸 / `handleDataPayload` | `out/main/index.js` | ~377,000 / 384,900 | `fa` / — |
| 桥降级 `degradeBridgeAfterRawFault` | 同上 | ~392,900 | `I` |
| 桥建立 + relay 协议装配 | 同上 | ~401,900 | `te` |
| `routeRawTransportCandidate` | 同上 | 404,554 | `Ut` |
| 网页端预算 `bd` | `Reference_web/3.15.3/assets/validation-CqXZOLXO.js` | 128,548 | `bd` |
| 网页端 relay 预算 `_9` | `Reference_web/3.15.3/assets/index-BGa3KiRe.js` | 6,473,333 | `_9` |

## 附录 B · 我们的实现坐标索引

| 内容 | 位置 |
|---|---|
| `MAX_PHYSICAL_FRAME_BYTES`（1 MiB，未被使用） | `rust/crates/protocol/src/rpc_transport.rs:60` |
| `MAX_MESSAGE_BYTES`（64 MiB，**应 16 MiB**） | `rpc_transport.rs:66` |
| `MAX_FRAGMENTS`（256，**应 64**） | `rpc_transport.rs:72` |
| `FRAGMENT_PAYLOAD_BYTES`（512 KiB 固定，**应动态 ≈786 KiB**） | `rpc_transport.rs:78` |
| `STALE_ASSEMBLY_SECONDS`（60 / 实 61 s，**应 30 s 且终态**） | `rpc_transport.rs:84` |
| `PURGE_INTERVAL_SECONDS`（30，与对端装配超时同值但语义不同） | `rpc_transport.rs:91` |
| 分片发送 | `rpc_transport.rs:270-312`（`288`、`294-297`） |
| **收端丢弃 >512 KiB 片** | `rpc_transport.rs:398-402` |
| 收端不读 `seq` | `rpc_transport.rs:350-358` |
| 收端不校验 `checksum.algorithm` | `rpc_transport.rs:355-358`、`:1134-1169` |
| 并发装配体（对端只有一条） | `rpc_transport.rs:230`、`:405-423` |
| **ack 不带 generation/recoveryId** | `rpc_transport.rs:535-550` |
| ack 断言 | `rpc_transport.rs:1039-1040`、`:1214-1221` |
| **逆序回灌测试（与对端不符）** | `rpc_transport.rs:737-776` |
| 乱序片测试（与对端不符） | `rpc_transport.rs:972-1009` |
| CRC-32 实现 | `rust/crates/protocol/src/crc32.rs`（`compute`/`hex_of`，与对端等价） |
| 我们总是发 `bridgeGeneration` | `rust/crates/protocol/src/zemote_client.rs:2417`、`:2439` |
| 从 ready 的 `bridge` 取身份 | `zemote_client.rs:1991-2009` |
| Dart 参考实现（缺陷源头） | `Reference_code/lib/protocol/rpc_transport.dart:14-26`（常量）、`:148`（>512 KiB 丢弃）、`:173-177`（ack 不带三元组） |
