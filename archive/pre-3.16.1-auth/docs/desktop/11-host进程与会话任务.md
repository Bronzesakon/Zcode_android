# 11 · host 进程与会话 / 任务索引

> **审计基线**：ZCode 桌面端 3.15.3（app.asar 340,516,766 B，build `d7ca8bee`，2026-10-13）
> 主要坐标：`out/host/index.js`、`out/host/chunk-T2GB4I3K.js`、`out/host/chunk-OUCOFHX4.js`、
> `out/host/chunk-WG6TEDKG.js`、`out/host/tasksStorageWorker.js`、`out/main/index.js`、
> `out/main/chunk-OW6EUS2G.js`、`out/renderer/assets/styles-CSpM3qgc.js`
> 交叉基线：`Reference_web/3.15.3/assets/index-BGa3KiRe.js`（同一 3.15.3 构建）
> 本文所有偏移均为**该文件内的字节偏移**；符号名为压缩后名字，已连上下文给出。

---

## 0. 结论速览（先看这 8 条）

| # | 结论 | 坐标 |
|---|---|---|
| 1 | `out/host/index.js` 是 Electron **utility process**，一个桌面窗口一个，跑 agent / 任务索引 / 窗口宿主控制器；由 main 用 `utilityProcess.fork` 拉起，`serviceName = zcode-host-<windowKind>` | `out/main/index.js` @38802、@525690 |
| 2 | 会话索引 topic = `sessions-index/<workspaceKey>`，`workspaceKey = workspaceIdentity?.trim() \|\| workspacePath`；快照 + `session.upserted`/`session.removed` 增量 | `out/host/chunk-T2GB4I3K.js` @6917、@6220、@6939；`out/host/chunk-OUCOFHX4.js` @548627 |
| 3 | **`tasks[i]` 的 16 键权威 schema 不在 host，而在共享协议 chunk**；`displayStatus` 枚举是 `idle\|running\|completed\|error` | `out/host/chunk-OUCOFHX4.js` @687153（`kU`）；`out/main/chunk-OW6EUS2G.js` @723090（`OD`，同构副本） |
| 4 | **16 键里 8 个来自任务索引 DB**（`taskId/title/workspacePath/workspaceIdentity/createdAt/updatedAt/provider/unreadAt`），`displayStatus`/`hasBackgroundWork`/`workflowActivity` 来自**会话**，`workspaceLabel`/`workspaceKind`/`remoteSessionId` 来自**工作区 tab**，`pinned`/`archived` 来自**列表种类** | `out/renderer/assets/styles-CSpM3qgc.js` @4404753（`w2`） |
| 5 | **`pinned` / `archived` / `hasBackgroundWork` 是"存在即为 true"的旗标，不是布尔**：false 时字段**整个不出现**（`...r?{pinned:!0}:{}`） | 同上 |
| 6 | `displayStatus` **不是** host 控制器帧里的 `liveStatus`。后者是 5 值 `idle\|running\|waiting\|completed\|error`，`waiting` 永不出现在线上；`displayStatus` 在渲染进程由 `activity.phase` 重新算出 | `out/host/chunk-T2GB4I3K.js` @54772（`liveStatus`）；renderer @4404539（`mpn`） |
| 7 | 「在跑」判据在本构建里**有两条**：网页/列表排序用 `displayStatus==='running' \|\| hasBackgroundWork===true`；桌面侧栏排序用 `phase∈{prewarming,running} \|\| hasBackgroundWork===true`（**不含** `creating/streaming`） | renderer @4403900（`cpn`）、@1315365（`f3e`）；web @4422834（`tbn`） |
| 8 | 任务清单**只含"当前窗口已打开为 tab 的工作区"下的任务**；且是渲染进程**整表 push**（`syncWebRemoteControlTasks`，`ipcRenderer.send`），main 只做去重比较后转发 | renderer @6426539（`qYn`）、@4404753；`out/main/index.js` @411283、@399674 |

---

## 1. host 进程

### 1.1 它是什么

`out/host/index.js`（1,681,899 B）是 **Electron `utilityProcess` 的入口脚本**。证据（`out/main/index.js`）：

```js
// @38802
var xd = xe(import.meta.dirname, "../host/index.js"),
    Lh = xe(import.meta.dirname, "../scheduler/index.js");

// @525690  spawnHostProcess
function aP(e,t,r,n,o){
  let i=YF(), a=_o(),
      c=[...Fc?[`--inspect-brk=${Fc}`]:[], "--no-warnings"],
      d = JF.fork(xd, [], {
        serviceName: Ks(t),
        execArgv: c,
        env: {...si(n.hostProcessLocalEnv), ...Ov(), ZCODE_PROCESS_LABEL: t, ...}
      });
  n.logger.info(`[spawnHostProcess] forked host process for (${t}), pid=${d.pid}`);
  n.logger.info(`[spawnHostProcess] host module path: ${xd}`);
  n.logger.info(`[spawnHostProcess] glm binary path: ${a ?? "<not found>"}`);
```

其中 `JF` = `import{utilityProcess as JF} from"electron"`（`out/main/index.js` @514443），`Ks` = `hd` = `ise`：

```js
// out/main/chunk-OW6EUS2G.js @778343
Xj="zcode";  function Qj(t){...}  // sanitizeProcessNameSegment
// @778529
function bt(...t){let n=t.map(r=>Qj(r)).filter(r=>!!r); return [Xj,...n].join("-")}  // joinZCodeProcessName
// @778900
function ise(t){return bt("host",t)}   // formatZCodeHostProcessName
```

⇒ **服务名 / 进程名 = `zcode-host-<windowKind>`**（`windowKind` 就是 `ZCODE_PROCESS_LABEL`，`out/main/index.js` @525690 的 `env`）。host 自己的日志前缀也是这个：

```js
// out/host/index.js @1637911
{parentPort:Ve}=process, ix=A3(Ve);
process.title=SN(process.env.ZCODE_PROCESS_LABEL);
// @1636980
let d={role:"utility_host", ...DN(c), counters:e.collectCounters()}
```

`role:"utility_host"`（内存诊断上报的角色名）与 `out/main/index.js` @49440 的进程角色映射表
`{main:"main", renderer:"renderer", utility:"utility", utility_host:"utility", gpu:"gpu", host:"host", agent:"agent"}`
互相印证。

### 1.2 职责

从 host-index 的模块结构（顶部 16 条 `import`）与内部可辨识的子系统看，host 承担：

| 职责 | 坐标（`out/host/index.js`） |
|---|---|
| **任务索引 SQLite 的宿主**（`tasks-index` 库、迁移、启动协调） | @627（`CREATE TABLE tasks`）、@11176、@15498、@17952 |
| **会话索引订阅 + 会话覆盖层**（`sessions-index/<key>`） | @1618729（`createWindowHostSessionsIndexObserver`） |
| **窗口宿主控制器投影**（`controller/tasks-index` + `controller/workspaces` 帧、列表查询、mutation） | @1618729（`createWindowHostControllerProjection`）、@1635580（`createWindowHostControllerRuntime`） |
| **ZCode agent 运行时**（`zcodeAgentService`：`resumeSession`/`createSession`/`listTaskMetas`/`subscribeSessionsIndexV4` …） | @422646、@1459949、@1461074 |
| **workspace 宿主**（本地 / WSL / 远端；`workspaceRuntimeByKey`、`runningTaskCountByWorkspaceKey`） | @1601111、@1610408 |
| **附件服务**（`attachmentBegin/Chunk/Commit/Abort`、`createAttachmentService`） | @264945、@1635580 段末 `U()` |
| **本地媒体预览 / 录屏物化 / 反馈日志打包**（经 `parentPort` 请求 main 授权） | @1637911 起 |
| **CUA（电脑操作）指示器 / 插件沙箱 / 浏览器执行 / MCP 遥测 / cron·off-peak 调度回调** | @530397 段、@585031、@521994 段 |

> ⚠️ 上面是"host 里能读出这些子系统"，**不是**说它们只在 host 里。agent 进程本身（`glm`/`zcode-agent` 可执行体）不在 `app.asar` 里（`asar ls --filter "out/agent"` 为空；host 启动时打印 `glm binary path`）。

### 1.3 与 main 的边界

单向 `MessagePort`（`parentPort`）上的判别消息。**main → host** 枚举 `H`（`out/main/index.js`）：

```
H.Dispose                                    // @526201  disposeHostProcess 发
H.AttachServicePort                          // @588626  renderer dom-ready 时把 ServicePort 交给 host
H.LocalMediaPreviewPathAuthorizeResult       // @518xxx
H.PluginSandboxRegisterResult
H.FeedbackLogArchiveResult
H.BrowserExecuteResult
H.BotRemoteWorkspaceReconnectResult
H.InitLocal                                  // @589963  首次 spawn 时下发 deviceMid / workspacePath / agentWarmupTargets
```

**host → main** 枚举 `V`（`out/main/index.js` @517454 起的大 `if` 链）：

```
V.DatabaseStartupState      V.Log                    V.NetworkTelemetryBatch
V.NetworkCaptureBatch       V.AgentResourceSample    V.HostResourceSample
V.ResourceUsageSnapshotResult  V.ToolExecResource    V.McpResourceSamples
V.McpTelemetry              V.SessionCreateTelemetry V.LocalMediaPreviewPathAuthorizeRequest
V.PluginSandboxRegisterRequest  V.CuaOperationState  V.FeedbackLogArchiveRequest
V.BrowserExecuteRequest
V.AgentProcessSpawned / Ready / Exited / Error / Exception
V.AgentRunningTaskCountChanged        // @521829  用于 app 退出闸门
V.WorkspaceRunningTaskCountChanged    // @521994  用于 WSL/远端 workspace 空闲释放
V.CronRunResult / V.OffPeakRunResult / V.CronSchedulerWakeRequest / V.OffPeakSchedulerWakeRequest
```

**RPC 不走 `parentPort`**：window 宿主控制器与 conversation 走独立的 `MessageChannelMain` 端口
（`out/main/index.js` @524803：`let{port1:w,port2:b}=new XF; d.postMessage(p,[b])`；
渲染进程侧 `webContents.postMessage(Ge.ServicePort | Ge.ScopedServicePort, ...)`）。

### 1.4 谁拉起谁 / 生命周期

```
main（Electron 主进程）
 └─ BrowserWindow
     └─ renderer（dom-ready）
         └─ main.spawnHostProcess()  ──utilityProcess.fork──▶ host（每窗口一个）
              ├─ host 内：agent 运行时 / tasks-index / 窗口宿主控制器 / workspace 宿主
              └─ ServicePort ──▶ renderer（windowControllerService 等）
```

关键代码（`out/main/index.js` @588626 起，`createWindow` 内）：

```js
t.webContents.on("dom-ready", async () => {
  ...
  let d = e.windowHostProcessMap.get(n);
  if (d && d.pid !== void 0) {           // 已存在 → 先 dispose，带 150ms 宽限
    ... e.disposeHostProcess(d, `${r}:reload`, 150) ...
  }
  ...
  let l = w => {                          // spawnLocalHost
    let I = e.spawnHostProcess(t, r, {type:H.InitLocal, deviceMid:e.deviceMid,
              workspacePath:b?.workspacePath, workspaceIdentity:b?.workspaceIdentity,
              agentWarmupTargets:[...], runtimeProcessEnvPatch:w, agentSpawnFallbackCwd:...});
    e.windowHostProcessMap.set(n, I), e.onHostProcessReady?.(n), ...
  };
  ...
});
t.on("closed", () => {                    // @590952
  e.logger.info(`[createWindow] window closed, killing host process (${r})`);
  let c = e.windowHostProcessMap.get(n);
  c && (e.disposeHostProcess(c, `${r}:window-closed`), e.windowHostProcessMap.delete(n));
  e.webRemoteControlManager.disposeWindow(o);
  e.disposeRemoteWorkspaceSessionsForWindow(n, `${r}:window-closed`);
});
```

* **一个窗口一个 host 进程**（`windowHostProcessMap: Map<webContentsId, ChildProcess>`）。
* **渲染进程 reload 也会重建 host**（先 dispose 再 spawn，150 ms 宽限）——所以 host **不跨 renderer 重载存活**。
* 优雅关闭：`disposeHostProcess`（`out/main/index.js` @526201）先 `postMessage({type:H.Dispose})`，
  超时默认 `300 ms` 后强杀：

```js
function Yu(e,t,r,n,o=300){ if(r.has(e))return; Xu.add(e), ... 
  try{ e.postMessage({type:H.Dispose}) }catch(c){...}
```

* **崩溃语义**：host `exit` 时 main 只做清理，**没有自动重启**：

```js
// out/main/index.js @525565  (spawnHostProcess 内)
d.on("exit", w => {
  Xa.add(d), n.onCuaOperationStateSourceExited?.(d), l.flushRawLogs(),
  n.logger.info(`[spawnHostProcess] host process (${t}) exited with code ${w}`),
  n.hostRunningTaskCountMap.delete(d),
  v && n.broadcastHub.unregister(y),
  oR(t);
  for (let [b,I] of n.windowHostProcessMap) if (I === d) { n.windowHostProcessMap.delete(b); break }
})
```

  重新拉起只发生在**下一次 `dom-ready`**（renderer 重载）或窗口重建。⚠️ **推测**：renderer 侧
  ServicePort 断开后应当自己触发一次 reload 才会恢复；本轮**未在 renderer 包里找到**显式的
  "host 挂了 → 重载" 钩子（`hostProcessExited` / `host-crashed` / `onHostRestarted` 在
  `styles-CSpM3qgc.js` 里命中数均为 0），故列为「未能证实」。
* **退出闸门**：app 退出前等 host 清干净，并且按"在跑的 agent session 数"决策：

```js
// out/main/index.js @521829
if (b.data.type === V.AgentRunningTaskCountChanged) {
  b.data.runningTaskCount > 0 ? n.hostRunningTaskCountMap.set(d, b.data.runningTaskCount)
                              : n.hostRunningTaskCountMap.delete(d),
  n.logger.info(`[app-quit] host running agent sessions updated (${t}) count=${b.data.runningTaskCount}`);
  return }
// @761672
S.info(`[app-quit] waiting for host process cleanup (${e}), kind=${lm}, hosts=${o.length},
        forceKillDelayMs=${mo.forceKillDelayMs}, waitTimeoutMs=${mo.waitTimeoutMs}`)
```

  这个计数由 host 侧产生（`out/host/index.js` @1651386 / @1563696）：

```js
function X3(e){ let t=new Map, n=0;
  function r(o){ e({...o.context, runningTaskCount:o.activeTaskIds.size}) }
  return { getRunningTaskCount(o){...}, getTotalRunningTaskCount(){return n},
           begin(o,s){ let a=ge(s), c=t.get(a)??{context:s,activeTaskIds:new Set};
                       if(c.activeTaskIds.has(o))return !1;
                       return c.activeTaskIds.add(o), n+=1, t.set(a,c), r(c), !0 },
           finish(o,s){ ... } } }
var Kl = X3(e => { Ve.postMessage({type:We.WorkspaceRunningTaskCountChanged, ...e});
                   cn.setWorkspaceRunningTaskCount(e); wS() });
function wS(){ r5.onRunningTaskCountChanged({runningTaskCount: Kl.getTotalRunningTaskCount() + $m }) }
```

  `Kl.begin/finish` 的调用点在**远端 prompt 镜像**路径（@1653899）：`beginWorkspaceTask` /
  `finishWorkspaceTask`，与 `onDynamicTaskReady` 绑定。⚠️ 这是"agent 侧任务在跑"的计数，
  **不是** `displayStatus` 的来源，两者别混。

---

## 2. 会话（session）

### 2.1 粒度与索引

「会话」= **一次 agent 对话运行**，其索引就是**任务列表的数据源**。会话与任务是**同一个 id**：

```js
// out/host/index.js @1624343  (sessionOverlay，被 xOe 命名)
function xOe(e){ let t = e.pendingInteraction || (e.pendingInteractionSummary
                    && e.pendingInteractionSummary.permissionCount + e.pendingInteractionSummary.userInputCount > 0)
                    ? "waiting"
                  : e.phase==="prewarming"||e.phase==="running" ? "running"
                  : e.phase==="error" ? "error"
                  : e.phase==="completedSuccess"||e.phase==="completedInterrupted" ? "completed"
                  : "idle";
  return { taskId: e.sessionId,          // ← 会话 id 就是 taskId
           liveStatus: t,
           title: e.title,
           ...e.titleSource?{titleSource:e.titleSource}:{},
           updatedAt: e.lastActivityAt,
           ...e.pendingInteraction?{pendingInteraction:{...}}:{},
           activity:{ phase:e.phase, lastActivityAt:e.lastActivityAt,
                      hasBackgroundWork:e.hasBackgroundWork,
                      ...e.pendingInteractionSummary?{pendingInteractions:e.pendingInteractionSummary}:{},
                      ...e.workflowActivity?{workflowActivity:e.workflowActivity}:{} } } }
```

### 2.2 会话索引的线上形状（权威 schema）

topic 构造与 topic 校验在 **`out/host/chunk-T2GB4I3K.js`**：

```js
// @6917
function _n(t){return `sessions-index/${t}`}  s(_n,"sessionsIndexTopic");

// @13153  （帧校验：topic 必须以此前缀开头，且不能只有前缀）
Kn = ct(On,Bn).superRefine((t,o)=>{
  (!t.topic.startsWith("sessions-index/") || t.topic.length === 15)
    && o.addIssue({code:"custom",message:"invalid sessions-index topic",path:["topic"]}) })
```

`workspace-config/` 同构：`qn(t) = \`workspace-config/${t}\``（@7926），长度必须是 17。

快照 / 增量 schema（`out/host/chunk-T2GB4I3K.js` @6220）：

```js
oi = e.object({ interactionId:e.string(), kind:e.enum(["permission","userInput"]),
                toolName:e.string().optional(), autoResolution:ln.optional() })
at = e.object({ permissionCount:e.number().int().nonnegative(),
                userInputCount:e.number().int().nonnegative() })

Dn = e.object({ sessionId:e.string(),                       // 1
                workspaceId:e.string(),                     // 2
                parentSessionId:e.string().optional(),      // 3
                title:e.string(),                           // 4
                titleSource:be.shape.titleSource.optional(),// 5
                phase:we,                                   // 6
                sessionEnded:e.boolean(),                   // 7
                hasBackgroundWork:e.boolean(),              // 8  ← 必填
                workflowActivity:he.optional(),             // 9
                pendingInteraction:oi.optional(),           // 10
                pendingInteractionSummary:at.optional(),    // 11
                goalStatus:Ie.shape.status.optional(),      // 12
                lastActivityAt:N,                           // 13
                lastAssistantPreview:e.string().optional(), // 14
                createdAt:N })                              // 15

On = e.object({ protocolVersion:e.literal(1), workspaceId:e.string(),
                logEpoch:e.string(), sessions:e.array(Dn) })                       // snapshot
Bn = e.discriminatedUnion("op",[
       e.object({op:e.literal("session.upserted"), session:Dn}),
       e.object({op:e.literal("session.removed"),  sessionId:e.string()}) ])       // deltas
```

别名解析（`out/host/chunk-OUCOFHX4.js` 导出表）：`we` ← `mb` ← `jA`（@539980）
`["draft","prewarming","running","completedSuccess","completedInterrupted","error"]`；
`he` ← `Y` ← `D_`（@577601）`{runs: array(sz).max(rz)}`，`rz = 4`（@576946）；
`N` ← `B = e.number()`（@485439）；`be` = 任务 meta（见 §3.3）。

### 2.3 `sessionId` 的形态

* 线上 schema 只要求 `e.string()`（无格式约束）；`title`/`sessionId` 都不是 `.strict()` 之外的强校验。
* `sessionId` 由 **agent 运行时**产生（`session.upserted` 帧从 agent 来），app.asar 内**没有**它的生成代码。
* 与我们相关的一条：**`taskId === sessionId`**（`xOe` 的 `taskId: e.sessionId`），
  `out/host/index.js` @426522 的 tasks-index 写入也是 `taskId: y.session.sessionId`。
  ⇒ 会话与任务**共用同一个 id 空间**，不需要两套 id 映射。

### 2.4 会话索引的消费（host 侧）

```js
// out/host/index.js @1618729
function W8(e){                        // createWindowHostSessionsIndexObserver
  let t={workspacePath:..., workspaceIdentity:...},
      n = e.target.workspaceIdentity?.trim() || e.target.workspacePath,   // ← 用 ge() 等价规则
      r = dd(n),                        // topic = sessions-index/<workspaceKey>
      o = new Map,                      // sessionId → session
      s = new Da(Vg),                   // 帧重组（分片/乱序）
      a = null,                         // 当前 subscriptionId
      c = null,                         // {logEpoch, seq}
      ...
  function F(me){                       // applyFrame
    if (me.payload.kind === "snapshot") {
      o.clear(); for (let Ce of me.payload.snapshot.sessions) o.set(Ce.sessionId, Ce);
      c = {logEpoch: me.payload.snapshot.logEpoch, seq: me.toSeq}, ...
      return }
    if (!c || me.fromSeq !== c.seq) { N(); return }         // ← 序号断裂 → 强制 resync
    for (let Ce of me.payload.deltas)
      Ce.op === "session.upserted" ? o.set(Ce.session.sessionId, Ce.session)
                                   : o.delete(Ce.sessionId);
    c = {...c, seq: me.toSeq}, A() }
```

* 订阅参数：`{...target, visibility:"background", subscriberScope:"window-controller", runtimePolicy:"existing-only"}`。
* 首帧是 **staging 缓冲**（`U8 = 32*1024*1024` 字节 / `TOe = 1024` 帧上限，@1616xxx），超限即
  `fault.subscription.initialFrameStagingOverflow`。
* 序号断裂 / 组装超时 → `resyncSessionsIndexV4({forceSnapshot:true})`，超时值
  `Kn.logicalFrameAssemblyTimeoutMs`。
* `onSessionsChange` → `Z.map(xOe)` → `t.replaceSourceSessionOverlays(scope, overlays)`（@1616913）。

**`dd` = 会话索引 topic 构造器**（host-index 里 `dd(ge(g))`）：`ge` ← `Db` ← `hO`（`out/host/chunk-OUCOFHX4.js` @548627）
`function hO(t){return t.workspaceIdentity?.trim()||t.workspacePath}` 命名为 `resolveWorkspaceKey`。
（`dd` 本身是 chunk 里 `_n` 的再导出，同名规则已由 `_n` 与 `resolveWorkspaceKey` 两处独立证实。）

---

## 3. 任务（task）：`tasks[i]` 16 键

### 3.1 权威 schema（线上 DTO）

**这是"我们能不能收下对端字段"的唯一权威**，位于共享协议 chunk：

```js
// out/host/chunk-OUCOFHX4.js @687153   （host 侧副本，符号名 kU）
// out/main/chunk-OW6EUS2G.js @723090   （main 侧副本，符号名 OD，逐字节同构）
kU = e.object({
  taskId: S,                                   //  1  string.trim().min(1)
  title: e.string(),                           //  2
  workspacePath: S,                            //  3
  workspaceIdentity: S.optional(),             //  4
  remoteSessionId: S.optional(),               //  5
  workspaceLabel: S,                           //  6
  workspaceKind: e.enum(["local","remote"]),   //  7
  createdAt: e.number().finite(),              //  8
  updatedAt: e.number().finite(),              //  9
  provider: sn.optional(),                     // 10  sn = e.enum(["glm"])   （@533355）
  unreadAt: e.number().finite().optional(),    // 11
  displayStatus: e.enum(["idle","running","completed","error"]).optional(),  // 12
  hasBackgroundWork: e.boolean().optional(),   // 13
  workflowActivity: JS.optional(),             // 14  JS = e.object({runs: array(OE).max(PE)})
  pinned: e.boolean().optional(),              // 15
  archived: e.boolean().optional(),            // 16
})
```

配套（同文件）：
* `xU` @686628 = `workspaces[i]` 的 **8 键**：`workspacePath`、`workspaceIdentity?`、`remoteSessionId?`、
  **`label`**、`workspacePurpose?("project"|"conversation")`、**`kind`("local"|"remote")`**、
  `connectionState?("connected"|"disconnected"|"reconnecting")`、`lastConnectionError?`。
  ⇒ **工作区里叫 `label`/`kind`，任务里叫 `workspaceLabel`/`workspaceKind`**（与第一批一致）。
  ⇒ **工作区 DTO 里没有 `key` 字段**（§7 会用到）。
* `CU` @688994 = `bootstrap-response.result`：
  `{windowControlSessionId, desktopAppVersion?, workspaces:bk, tasks:Sk, initialViewState?, mobileViewState?}`
  —— **`tasks` 在 bootstrap 里是必填数组**。
* `yk` @689023 = `workspace-list-*` 的 `result`：
  `{workspaces:bk, tasks:Sk.optional(), activeWorkspaceKey?, activeTaskId?}` —— `tasks` **可选**。
* 三处对象都**不是** `.strict()`：多给键会被 zod 剥掉但不报错；**缺必填键会整帧被丢**。
* `workflowActivity` 形状（`JS` @544565 / `D_` @577601）：
  `{runs:[{runId, toolCallId?, name?, status, stopReason, startedAt?, phases:[{name,status:"pending"|"running"|"done"|"failed",alongside?:number[],hole?:"open"|"waiting"}], currentPhase?, agentsWorking:int≥0, waitingHole?}]}`，`runs` **上限 4**。
* 注意 **`provider` 在两个 schema 里不一样**：线上 DTO 只接受 `"glm"`（`mE=["glm"]`）；
  任务 meta（DB 行）接受 `["claude","opencode","gemini","codex","glm"]` 并 `.transform(()=>"glm")`。

### 3.2 16 键各自由谁填、什么时候变

真正把 16 键拼出来的是**渲染进程**（不是 host、不是 main）：

```js
// out/renderer/assets/styles-CSpM3qgc.js @4404753
function w2({workspaceTabs:e, tasks:t, taskRuntimeByWorkspaceKey:n={}, pinned:r=!1, archived:i=!1}){
  let a = new Map(e.map(e => [pD(e.workspacePath, e.workspaceIdentity), e]));   // 工作区 tab 索引
  return t.map(e => {
    let t = pD(e.workspacePath, e.workspaceIdentity), o = a.get(t);
    if (!o) return null;                                  // ← 工作区没开 tab → 该任务整条不出现
    let s = !!(o.workspaceIdentity || o.remoteTarget || o.remoteSessionId),     // workspaceKind
        c = mpn({task:e, runtimeStatus:n[t]?.[e.taskId]?.status}),              // displayStatus
        l = tk(e),                                                              // __zcodeSessionActivity
        u = l?.hasBackgroundWork === !0,
        d = l?.workflowActivity;
    return { taskId:e.taskId, title:e.title, workspacePath:e.workspacePath,
             ...e.workspaceIdentity?{workspaceIdentity:e.workspaceIdentity}:{},
             ...o.remoteSessionId?{remoteSessionId:o.remoteSessionId}:{},
             workspaceLabel:o.label, workspaceKind:s?`remote`:`local`,
             createdAt:e.createdAt, updatedAt:e.updatedAt,
             ...e.provider?{provider:e.provider}:{},
             ...typeof e.unreadAt==`number`?{unreadAt:e.unreadAt}:{},
             displayStatus:c,
             ...u?{hasBackgroundWork:!0}:{},          // ← 只在 true 时出现
             ...d?{workflowActivity:d}:{},
             ...r?{pinned:!0}:{},                      // ← 只在 true 时出现
             ...i?{archived:!0}:{} }                   // ← 只在 true 时出现
  }).filter(e => !!e).sort(hpn(`updated`)) }
```

`tk(e)` = `e.__zcodeSessionActivity ?? null`（@1315238），该字段由 `useGlobalTaskList` 在拿到控制器行时挂上
（@1430808 的 `l9e(u.current, e.items.map(e => e.activity ? ek(e, e.activity) : e))`，`ek` @1315167）。

| 键 | 来源 | 谁写 | 什么时候变 |
|---|---|---|---|
| `taskId` | 任务索引 DB 行 / `session.sessionId` | 渲染进程转发 | agent 建会话时写入 DB，此后不变 |
| `title` | DB `title`（可被会话 overlay 覆盖） | agent + 用户重命名 | `task_title_changed` / `session_info_update` |
| `workspacePath` / `workspaceIdentity` | DB 行 | agent | 任务创建时固定 |
| `remoteSessionId` | **工作区 tab**（`o.remoteSessionId`），不是 DB | 渲染进程 | 远端工作区连接建立/断开时 |
| `workspaceLabel` | **工作区 tab 的 `label`** = `ru(workspacePath)` = 路径 basename | main（`kt` @397284）/ renderer tab | 工作区打开时固定 |
| `workspaceKind` | **工作区 tab**：`workspaceIdentity \|\| remoteTarget \|\| remoteSessionId` → `"remote"` 否则 `"local"` | 渲染进程 | tab 的远端属性变化时 |
| `createdAt` / `updatedAt` | DB `created_at` / `updated_at`；有会话 overlay 时 `updatedAt` 被 `lastActivityAt` 覆盖（`B8` @1613046） | agent 每轮活动 | `updatedAt` 每次消息/状态变化都变 |
| `provider` | DB `provider`（→ `"glm"`） | agent | 任务创建时 |
| `unreadAt` | DB `unread_at`（`INTEGER`，可空） | agent 产生未读；用户"标记已读"清除 | `setTaskUnread({unread:false, expectedUnreadAt})` |
| `displayStatus` | **`activity.phase`**（会话），否则本地 `runtimeStatus`，否则 DB `status` | 渲染进程 `mpn` | 见 §3.4 |
| `hasBackgroundWork` | **会话记录 `hasBackgroundWork`** | agent | 会话 upsert 时 |
| `workflowActivity` | **会话记录 `workflowActivity`** | agent | 会话 upsert 时 |
| `pinned` | **列表种类**（`w2(...,{pinned:true})` 那一遍） | 渲染进程 | 用户 pin/unpin → `setTaskPinned` |
| `archived` | **列表种类**（`w2(...,{archived:true})` 那一遍） | 渲染进程 | 用户归档/自动归档 |

**注意 `pinned`/`archived` 的来源**：`qYn`（@6426539）对同一个任务集跑三遍 `w2`：

```js
function qYn({syncWebRemoteControlTasks:e, workspaceTabs:t}){
  let n = Nl(e=>e.workspaces),
      r = ej({kind:`timeline`, workspaceTabs:t, sortBy:`updated`, searchQuery:``, expanded:!0, collapsedLimit:100}),
      i = ej({kind:`pinned`,   ...}), a = ej({kind:`archived`, ...}),
      o = (0,Q.useMemo)(()=>{
        let e = fpn(n),                                     // workspaces → taskRuntimeByTaskId
            o = w2({workspaceTabs:t, tasks:i.items, taskRuntimeByWorkspaceKey:e, pinned:!0}),
            s = w2({workspaceTabs:t, tasks:r.items, taskRuntimeByWorkspaceKey:e}),
            c = w2({workspaceTabs:t, tasks:a.items, taskRuntimeByWorkspaceKey:e, archived:!0});
        return [...o,...s,...c].sort((e,t)=>t.updatedAt===e.updatedAt
                 ? t.createdAt===e.createdAt ? t.taskId.localeCompare(e.taskId) : t.createdAt-e.createdAt
                 : t.updatedAt-e.updatedAt)
      },[a.items,i.items,r.items,n,t]),
      s = kYn({archivedLoading:a.loading, enabled:!0, pinnedLoading:i.loading, timelineLoading:r.loading});
  return (0,Q.useEffect)(()=>{ if(!s){...;return} e(o) },[a.loading,i.loading,s,e,o,r.loading]), null }
```

⇒ 三个列表各自带上自己的旗标后**拼成一个数组**；`timeline` 那批两项都没有。
**这就是为什么 `pinned`/`archived` 在线上是"存在即 true"**。

### 3.3 任务索引 DB 行（host 侧的内部 meta，不是线上 DTO）

```js
// out/host/chunk-OUCOFHX4.js @712774   （符号 xj，导出名 Ye）
// 被 out/host/chunk-T2GB4I3K.js 以 `Ye as bn` 引入，用作 controller 帧里的 dr.meta
xj = e.object({ taskId:S, traceId:S, title:e.string(), titleOverridden:e.boolean().optional(),
  workspacePath:S, workspaceIdentity:S.optional(),
  workspacePurpose:e.enum(["project","conversation"]).optional(),
  createdAt:int≥0, updatedAt:int≥0, mode:fo, model:S.optional(), thoughtLevel:S.optional(),
  runtimeEpoch:int≥0, provider:Pr.optional(), migrationSource:yN.optional(),
  forkedFromTaskId:S.optional(), cronAutomationId:S.optional(), offPeakTaskId:S.optional(),
  unreadAt:int≥0.optional(), status:uj.optional(),         // uj = ["running","completed","error"] @709187
  lastError:{code?,detail?,message,traceId?,taskId?,attribution?}.optional(),
  repairState:{...}.optional(), changeSummary:{...}.optional() })
```

`Pr = e.enum(["claude","opencode","gemini","codex","glm"]).transform(()=>Tr)`，`Tr = "glm"`（@475761/@475732）。

### 3.4 `displayStatus` 的算法（**必读**）

```js
// out/renderer/assets/styles-CSpM3qgc.js
// @4404344
function ppn(e){ switch(e){
  case`prewarming`: case`running`: return `running`;
  case`completedSuccess`: case`completedInterrupted`: return `completed`;
  case`error`: return `error`;
  case`draft`: return } return e }
// @4404539
function mpn({task:e, runtimeStatus:t}){
  let n = tk(e);                       // __zcodeSessionActivity = 控制器行的 activity
  if (n) { let e = ppn(n.phase); if (e) return e }      // ← 会话 phase 优先
  return spn(t) ? `running`                              // spn = t==="creating"||t==="streaming"
       : t===`failed` || e.status===`error`     ? `error`
       : t===`completed` || e.status===`completed` ? `completed`
       : `idle` }
```

* `displayStatus` 的 **4 值**与线上 schema 的 4 值枚举**完全一致**，是同一处产生。
* `phase` 只可能是 `draft|prewarming|running|completedSuccess|completedInterrupted|error`（§2.2），
  其中 `draft` → `undefined`（落到后面的本地状态判断）。
* **host 控制器帧里的 `liveStatus` 有第 5 个值 `waiting`，它永远不会变成 `displayStatus`。**

```js
// out/host/chunk-T2GB4I3K.js @54772   （控制器任务行）
dr = e.object({ address:ar, meta:bn,
  membership:e.object({pinned:e.boolean(), archived:e.boolean(), active:e.boolean()}).strict(),
  sourceAvailability:e.enum(["online","offline"]),
  liveStatus:e.enum(["idle","running","waiting","completed","error"]),      // ← 5 值
  activity:es.optional(), searchSnippets:e.array(e.string()).optional() }).strict()
// @54595
es = e.object({ phase:we, lastActivityAt:e.number().finite().nonnegative(),
  hasBackgroundWork:e.boolean(),
  pendingInteractions:at.optional(), workflowActivity:he.optional() }).strict()
```

host 侧的 `liveStatus` 与 DB `status` 的映射（`out/host/index.js`）：

```js
// @1612135  defaultLiveStatus
function COe(e){ switch(e.status){ case"completed": return "completed";
                                   case"error": return "error"; default: return "idle" } }
// @1612355  statusFromActivity
function IOe(e){ switch(e.phase){ case"prewarming": case"running": return "running";
    case"completedSuccess": case"completedInterrupted": return "completed";
    case"error": return "error"; case"draft": return } }
// @1613046  buildSourceRows：把 DB meta 与会话 overlay 合并成控制器行
function B8(e){ let t=new Map;
  for (let n of e.memberships.values()){
    let r = e.overlays.get(n.meta.taskId),
        o = r?.title!=null && (n.meta.titleOverridden!==!0 || r.titleSource==="custom"),
        s = r ? {...n.meta, ...o?{title:r.title}:{}, ...r.titleSource==="custom"?{titleOverridden:!0}:{},
                 ...r.updatedAt!=null?{updatedAt:r.updatedAt}:{},
                 ...r.activity?{status:IOe(r.activity)}:{}, pendingInteraction:r.pendingInteraction}
              : n.meta,
        a = L8(e.scope, s.taskId);
    t.set($c(a), { address:a, meta:s, membership:n.membership,
                   sourceAvailability:e.sourceAvailability,
                   liveStatus: r?.liveStatus ?? COe(s),
                   ...r?.activity?{activity:r.activity}:{},
                   ...n.searchSnippets?{searchSnippets:n.searchSnippets}:{} }) }
  return t }
```

⇒ **会话 overlay 存在时，`liveStatus` 与 `activity` 都来自会话；DB 的 `status` 只作兜底。**
⇒ 顺带一个副作用：`B8` 会把 `IOe(activity)` 写回 `meta.status`（`"running"`/`"completed"`/`"error"`），
  而 `meta.status` 的枚举是 `uj = ["running","completed","error"]`。

### 3.5 任务行的身份键（三段，缺一不可）

```js
// out/host/index.js
// @1622714  sourceKey
function Pr(e){ let t=e.workspaceIdentity?.trim()||e.workspacePath;
  return e.kind==="remote" ? `remote\0${e.remoteSessionId}\0${t}` : `local\0${t}` }
// @1612004  taskKey
function $c(e){ return `${e.remoteSessionId??"local"}\0${e.workspaceIdentity?.trim()||e.workspacePath}\0${e.taskId}` }
// @1611662  addressFor 的 key 变体（rx）
function rx(e){ return `${e.workspaceIdentity?.trim()||e.workspacePath}\0${e.taskId}` }
```

⇒ **任务唯一键 = `remoteSessionId ?? "local"` + `workspaceKey` + `taskId`**（`workspaceKey = identity || path`）。
同一个 `taskId` 在不同 workspaceKey 下是不同任务。

### 3.6 任务索引的变更事件（"什么时候变"的上游）

```js
// out/host/chunk-OUCOFHX4.js @548630
yO = e.enum(["task_created","user_message_saved","assistant_message_saved","task_status_changed",
  "task_meta_changed","task_model_changed","task_title_changed","task_pinned","task_unpinned",
  "task_archived","task_unarchived","task_deleted","stream_mirror_gap","stream_mirror_owner_lost"])
// @549xxx
Xv = e.object({eventId:E, workspacePath:E, workspaceIdentity:E.optional(), workspaceKey:E,
               traceId:E, createdAt:int≥0}).strict()
Qv = Xv.extend({reason:yO, streamWatermark:{runId:E, opSeq:int≥0}.optional()}).strict()
```

---

## 4. 「在跑」的判据

### 4.1 网页端/桌面列表用的那一条（与第一批一致）

```js
// out/renderer/assets/styles-CSpM3qgc.js @4403900
var cpn = e => e.displayStatus === `running` || e.hasBackgroundWork === true;

// Reference_web/3.15.3/assets/index-BGa3KiRe.js @4422834（同构建、另一 bundle）
var tbn = e => e.displayStatus === `running` || e.hasBackgroundWork === true;
```

两处**逐字符相同**，互为交叉验证。用途：列表排序（`hpn`/`Qyn` 家族把"在跑"的排前面）。

### 4.2 桌面侧栏用的那一条（更严）

```js
// out/renderer/assets/styles-CSpM3qgc.js @1315291 / @1315365
function d3e(e){ let t = tk(e)?.phase; return t === `prewarming` || t === `running` }
function f3e(e){ return d3e(e) || tk(e)?.hasBackgroundWork === true }
```

**差异**：`f3e` 直接看 `phase`，**不含** `runtimeStatus ∈ {creating, streaming}`（本地乐观态），
而 `cpn` 看的是 `displayStatus`，`displayStatus` 在 `phase` 为 `draft` 时会落到 `runtimeStatus`
⇒ `creating`/`streaming` 期间 `cpn` 为 true 而 `f3e` 为 false。

**给我们的建议**：跟网页端一致用 `cpn`（即第一批已知的判据），因为我们的对端是**网页端视角**的
`workspace-list-*` 帧；但要知道桌面自己的"侧栏 spinner"用的是 `f3e`，两者在
"刚创建/刚开始流式"这一小窗口内不一致。

### 4.3 `hasBackgroundWork` 什么时候为 true

**未能证实（app.asar 内无产生方）**。已证实的事实：

* 它是**会话记录的必填布尔**（`Dn.hasBackgroundWork: e.boolean()`，非 optional），
  随 `sessions-index/<key>` 的 `session.upserted` 帧从 **agent 运行时**送达 host。
* `app.asar` 内 `hasBackgroundWork` 共 21 处命中，**全部是 schema 定义或透传**，没有一处计算它：

  | 文件 | 处 |
  |---|---|
  | `out/host/chunk-OUCOFHX4.js` | @687521（DTO schema） |
  | `out/host/chunk-T2GB4I3K.js` | @6410（会话 schema `Dn`）、@54666（activity schema `es`） |
  | `out/host/index.js` | @1624106、@1624126（`xOe` 里 `hasBackgroundWork:e.hasBackgroundWork` 的两次子串命中，同一处） |
  | `out/main/chunk-OW6EUS2G.js` | @723162 |
  | `out/main/chunk-ZI5VUQWA.js` | @10926、@48932 |
  | `out/main/index.js` | @399356（签名比较） |
  | `out/renderer/assets/*` | 4 个 bundle，均为 schema / `=== true` 判断 |
  | `out/scheduler/index.js` | 3 处（schema） |

* host 侧**没有任何**写入 `hasBackgroundWork` 的代码；渲染进程也只是 `=== true` 读取。
* 语义（⚠️ 推测，基于命名与同族字段）：该会话有**后台工作在进行**——后台 bash、子代理、
  workflow run 等"用户没盯着但还在跑"的工作。`sessions-index` 里同时有 `pendingInteractionSummary`
  与 `workflowActivity`，三者是并列的"活动"维度。

⇒ **要确定 true 的确切条件，必须抓 agent ↔ host 的 `sessions-index/*` 帧**（见 §8）。

---

## 5. 任务存储

### 5.1 `out/host/tasksStorageWorker.js`（616 B，全文）

```js
import{t as n}from"./chunk-WG6TEDKG.js";
import{b as e,p as o,q as i,t as p}from"./chunk-OUCOFHX4.js";
import{parentPort as r,workerData as c}from"worker_threads";
var d = e.object({path:e.string().min(1)}).strict().parse(c);
try{
  await n(d.path, (t,a) => r?.postMessage({type:"progress", phase:t, migration:a})),
  r?.postMessage({type:"done"})
}catch(t){
  let a = p.safeParse(t && typeof t==="object" ? t.startupMigration : void 0);
  r?.postMessage({type:"failed", migration:a.success?a.data:void 0, errorCode:o(t), ...i(t)})
}finally{ r?.close() }
```

**它本身不做业务**：只是一个 **worker_threads 壳**，把 `{path}` 交给 `chunk-WG6TEDKG.js` 的
`t`（= `prepareStorage`），把进度/结果回传给 host 主线程。真正的 SQLite 在 `chunk-WG6TEDKG.js`。

main/host 侧的调用与超时（`out/host/index.js` @15498）：

```js
function kL(e,t,n){ return new Promise((r,o)=>{
  let s = new vL(new URL("./tasksStorageWorker.js", import.meta.url), {workerData:{path:e}}), ...
      l = setTimeout(()=>{ c = bo("startup_status_timeout"), s.terminate() }, 3e4),   // 30 s
      ...
  s.on("message", p=>{ let m = lX.safeParse(p); if(!m.success){ c=bo("transport_closed"), s.terminate(); return }
    d=!0, clearTimeout(l);
    let h = m.data;
    h.type==="progress" ? t(h.phase, h.migration)
    : h.type==="done"   ? a=!0
    : c = bo(h.errorCode, h, "tasks-index") });    // ← databaseId 固定 "tasks-index"
  ...}) }
```

消息 schema（@lX，@14000 附近）严格三态：`{type:"progress", migration?, phase: "checking"|"waiting_for_lock"|"migrating"|"committing"|"maintaining"|"ready"}`、
`{type:"done"}`、`{type:"failed", errorCode, migration?}`。

**启动顺序**（`out/host/index.js` @17952）：

```js
o("preparing_host_storage","checking");
let d = jN();  await c(d), a.start(),
await kL(d, (m,h)=>o("preparing_host_storage", m, {databaseId:"tasks-index", migration:h}), t.signal),
ZN(d);
o("preparing_session_storage","checking");   // 然后才是每个 cwd 的 session 存储
```

### 5.2 落盘形状（`out/host/chunk-WG6TEDKG.js` @627）

```sql
CREATE TABLE IF NOT EXISTS tasks (
  workspace_key       TEXT NOT NULL,
  workspace_path      TEXT NOT NULL,
  workspace_identity  TEXT,
  task_id             TEXT NOT NULL,
  title               TEXT NOT NULL DEFAULT '',
  task_status         TEXT,
  provider            TEXT,
  mode                TEXT NOT NULL DEFAULT 'build',
  model               TEXT,
  migration_source    TEXT,
  forked_from_task_id TEXT,
  created_at          INTEGER NOT NULL,
  updated_at          INTEGER NOT NULL,
  unread_at           INTEGER,
  last_unread_at      INTEGER NOT NULL DEFAULT 0,
  pinned              INTEGER NOT NULL DEFAULT 0,
  archived            INTEGER NOT NULL DEFAULT 0,
  deleted             INTEGER NOT NULL DEFAULT 0,
  title_overridden    INTEGER NOT NULL DEFAULT 0,
  meta_json           TEXT NOT NULL DEFAULT '{}',
  PRIMARY KEY (workspace_key, task_id)
);

CREATE INDEX IF NOT EXISTS idx_tasks_workspace_archived_updated
  ON tasks (workspace_key, archived, updated_at DESC) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_tasks_workspace_pinned_updated
  ON tasks (workspace_key, pinned,   updated_at DESC) WHERE deleted = 0;
```

同库还有：`task_groups`、`task_group_members`、`task_group_view_node_orders`、
`task_group_workspace_bootstraps`、`automations`、`automation_runs`、`off_peak_tasks`、
`tasks_schema_migration`（@1767 / @2005 / @2505 / @2796 / @3285 / @4873 / @5530 / @12691）。

注意：
* **`tasks` 表主键是 `(workspace_key, task_id)`，没有 `remoteSessionId` 列** —— 远端任务靠 `workspace_key` 区分。
* `workspace_key` 就是 `workspaceIdentity?.trim() || workspacePath`（`hO`/`resolveWorkspaceKey` 在写入前算好）。
* 列表查询（@96500 起）用 `LIMIT ?`（`Oo(e.limit)`，`limit == null` 时不加 LIMIT），
  排序 `created_at DESC, updated_at DESC, task_id DESC` 或 `updated_at DESC, created_at DESC, task_id DESC`。
  **没有硬上限**；`limit` 由调用方（渲染进程 `collapsedLimit`）给。
* 软删：`deleteTask` 写 `deleted:1` + `UPDATE`；硬删：`DELETE FROM tasks WHERE workspace_key = ? AND task_id = ?`（@71396）。

### 5.3 清理规则

| 规则 | 条件 | 坐标 |
|---|---|---|
| **自动归档旧任务** | `deleted=0 AND archived=0 AND pinned=0 AND unread_at IS NULL AND updated_at < now - olderThanDays*86400e3 AND task_status='completed'` | `out/host/chunk-WG6TEDKG.js` @79213（`archiveStaleTasks`） |
| 自动归档的开关与天数 | 设置项 `taskAutoArchiveEnabled`，`taskAutoArchiveOlderThanDays ?? 7` | `out/host/index.js` @422106（`readTaskAutoArchiveConfig`） |
| 自动归档的触发时机 | **只在 `listGroupedTaskViewStructure` 被调用时**（即用户打开分组视图），不是定时任务 | `out/host/index.js` @422646（`runWorkspaceTaskAutoArchive`）、@450387 |
| 归档任务的删除 | `deleteArchivedTasks({taskIds})` → 逐个 `deleteArchivedTask`，返回 `{deletedTaskIds, skippedTaskIds, failedTaskIds}` | `out/host/index.js` @458071 |
| 定时任务运行记录 | `DELETE FROM automation_runs WHERE created_at < ?`（`pruneRuns(olderThanMs)`） | `out/host/chunk-WG6TEDKG.js` @45871 |

⇒ **任务表没有"最多 N 条"的截断，也没有时间清理**；唯一的自动清理是"completed 且 7 天未动、未读、未 pin 的
自动归档"，且只在打开分组视图时跑一次。归档本身**不删数据**，只有用户显式删归档才 `DELETE`。

---

## 6. `workspace-list-response` / `workspace-list-updated`

### 6.1 main 侧的构造与下发（`out/main/index.js`）

```js
// @398750  buildBootstrapResult
function T(h){ return { windowControlSessionId:h.deviceSid, desktopAppVersion:e.appVersion,
                        workspaces:M(h), tasks:E(h), initialViewState:O(h),
                        mobileViewState:h.mobileViewState } }
// @399027  buildWorkspaceListResult
function W(h){ let C=kt(h), A=O(h);
  return { workspaces:M(h), tasks:E(h),
           activeWorkspaceKey: h.mobileViewState?.activeWorkspaceKey ?? A?.activeWorkspaceKey ?? Ve(C),
           activeTaskId:       h.mobileViewState?.activeTaskId       ?? h.currentBridge?.initialTaskId ?? A?.activeTaskId } }
// @399505  buildWorkspaceListPushSignature  ← 去重比较用的"指纹"
function D(h){
  let C = h.workspaces.map(N=>{ let q=Ve(N);
        return JSON.stringify([q, N.kind, N.connectionState??"connected",
                               N.remoteSessionId??"", N.lastConnectionError??""]) }).sort(),
      A = h.tasks.map(N=>{ let q=Ve(N);
        return JSON.stringify([q, N.taskId, N.title, N.remoteSessionId??"",
                               N.displayStatus??"idle", !!N.hasBackgroundWork,
                               N.workflowActivity??null,
                               typeof N.unreadAt=="number"?N.unreadAt:"",
                               !!N.pinned, !!N.archived]) }).sort();
  return JSON.stringify([C,A]) }
// @399674  pushWorkspaceListUpdated
function B(h){ let C=W(h), A=D(C);
  d.get(h.windowId)!==A && (d.set(h.windowId,A), le(h,{zcode_type:"workspace-list-updated", result:C})) }
```

**注意 `D` 就是"哪些任务字段参与变更判定"的权威清单**——正是 §3.1 的 16 键里的 10 个
（`workspacePath/workspaceIdentity`→`q`、`taskId`、`title`、`remoteSessionId`、`displayStatus`、
`hasBackgroundWork`、`workflowActivity`、`unreadAt`、`pinned`、`archived`）。
**`createdAt`/`updatedAt`/`provider`/`workspaceLabel`/`workspaceKind` 不参与** ⇒ 只改标题时间戳不会触发推送。

工作区侧：`kt`（@397284，`getRuntimeWorkspaceTarget`）：

```js
function kt(h){ let C=h.currentBridge;
  return C ? {workspacePath:C.workspacePath, workspaceIdentity:C.workspaceIdentity,
              remoteSessionId:C.remoteSessionId, label:ru(C.workspacePath), kind:C.kind}
           : {workspacePath:h.workspacePath, workspaceIdentity:h.workspaceIdentity,
              remoteSessionId:h.remoteSessionId, label:ru(h.workspacePath),
              kind: h.remoteSessionId||h.workspaceIdentity ? "remote" : "local"} }
```

`ru`（@389901）= `getPathLabel` = 路径最后一段（basename）；`nu`/`m0`（@390015/@390132）
= `isBridgeableRemoteTarget` / `isBridgeableRemoteTask`：

```js
function nu(e){ return e.kind!=="remote" || !!(e.workspaceIdentity && e.remoteSessionId) }
function m0(e){ return e.workspaceKind!=="remote" || !!(e.workspaceIdentity && e.remoteSessionId) }
```

任务集与工作区集的取法：

```js
// @397940  getAvailableWorkspaces
function M(h){ let C=new Map; for(let N of n.get(h.windowId)??[]) C.set(Ve(N),N);
  let A=kt(h); if(nu(A)){ let N=Ve(A); C.has(N)||C.set(N,A) } return [...C.values()] }
// @398125  getAvailableTasks
function E(h){ let C=new Set(M(h).map(A=>Ve(A)));
  return (c.get(h.windowId)??[])                     // ← 渲染进程 push 进来的任务数组
    .filter(A=>m0(A) && C.has(Ve(A)))                 // ① 可桥接的远端任务 ② 工作区在列表里
    .sort((A,N)=> N.updatedAt!==A.updatedAt ? N.updatedAt-A.updatedAt
                 : N.createdAt!==A.createdAt ? N.createdAt-A.createdAt
                 : N.taskId.localeCompare(A.taskId)) }
```

⇒ **任务清单是"工作区清单的子集"**：不在 `workspaces` 里的任务会被 main 直接滤掉。

### 6.2 任务怎么进 main

```js
// @411283  syncAvailableTasks
syncAvailableWorkspaces(h,C){ let A=t.get(h); A && (n.set(h,C), B(A)) },
syncAvailableTasks(h,C){      let A=t.get(h); A && (c.set(h,C), B(A)) },
// @782513  暴露成 IPC 处理函数
syncWebRemoteControlTasks: (u,m) => cn.syncAvailableTasks(u,m),
syncWebRemoteControlWorkspaces: (u,m) => { cn.syncAvailableWorkspaces(u,m), cn.restorePreviouslyEnabled(u,m) },
```

preload 侧（`out/preload/index.cjs` @739677）：

```js
syncWebRemoteControlWorkspaces: s(t => v.ipcRenderer.send(x.SyncWebRemoteControlWorkspaces, t), ...),
syncWebRemoteControlTasks:      s(t => v.ipcRenderer.send(x.SyncWebRemoteControlTasks, t), ...),
```

⇒ **`ipcRenderer.send`（单向，不等回执）**，载荷就是**整张任务数组**。

### 6.3 完整链路

```
agent 运行时（glm / zcode-agent，不在 app.asar）
  │  sessions-index/<workspaceKey>   snapshot + session.upserted/removed
  ▼
host（每窗口一个 utility process）
  ├─ createWindowHostSessionsIndexObserver  → sessionOverlay (xOe)          out/host/index.js @1622538
  ├─ tasks-index SQLite（listTasks / listPinnedTasks / listArchivedTasks）  @448600 / chunk-WG6TEDKG
  ├─ createWindowHostControllerProjection   → buildSourceRows (B8)          @1613046
  │     行 = {address, meta, membership, sourceAvailability, liveStatus, activity?}
  └─ controller/tasks-index + controller/workspaces 帧（快照+增量）
        与 listTaskList（支持 kind/workspaces/sortBy/search/limit）           chunk-T2GB4I3K @53445/@56235
  ▼  ServicePort
renderer
  ├─ useGlobalTaskList (ej @1430808)  list(...) → items（挂 __zcodeSessionActivity）
  ├─ w2 (@4404753) ×3（timeline / pinned / archived）→ 16 键 DTO
  └─ qYn (@6426539) 合并排序 → syncWebRemoteControlTasks(tasks)   [ipcRenderer.send]
  ▼
main
  ├─ syncAvailableTasks → c.set(windowId, tasks) → pushWorkspaceListUpdated (B @399674)
  ├─ workspace-list-updated  → relay → 移动端
  └─ workspace-list-request  → buildWorkspaceListResult → workspace-list-response
```

### 6.4 与 `bootstrap-response.result.tasks` 的关系

* main 在 `bootstrap-response` **也带** `tasks`（`$D` 里是必填数组，`out/host/chunk-OUCOFHX4.js` @688994）。
* `workspace-list-*` 的 `result.tasks` 在 schema 里是 `.optional()`（`yk` @689023），但 main 实现里**总是给**。
* 两者**内容同源**（都走 `E(h)`），唯一差别是 `bootstrap-response` 多带 `windowControlSessionId` /
  `desktopAppVersion` / `initialViewState` / `mobileViewState`。
* 网页端不读 `bootstrap-result.tasks` 是**客户端选择**，不是对端不提供。我们的实现可以任选一路，
  但**必须同时处理 `workspace-list-updated`**（否则任务状态永远停在 bootstrap 那一刻）。

---

## 7. 与我们的实现的差异

> 只读对照：`E:\Zcode_android\rust\crates\protocol\src\zemote_client.rs`、
> `E:\Zcode_android\android\app\src\main\kotlin\app\zemote\ui\chat\ChatViewModel.kt`
> （以及 `android/.../engine/EngineModels.kt`、`ZemoteEngine.kt`、`ui/EnginePort.kt`）。**未改动任何文件。**

### 7.1 Rust 协议核心：**完全没有工作区/任务模型**

| 项 | 现状 | 对端权威 | 影响 |
|---|---|---|---|
| 工作区/任务类型 | `zemote_client.rs` 只有 `ZemoteEvent::WorkspaceListUpdated(Value)`（:317）与 `Completion`（:273），**没有任何 `Workspace`/`Task` struct** | 16 键 DTO（§3.1） | 投影全靠上层猜字段名 |
| `bootstrap` | `:1284` 只做 `bootstrap-request`→`bootstrap-response`，把 `result` 原样交回（`:676`、`:1179` 的 `"bootstrap result 不是 Map"` 错误） | `CU` @688994 | 无字段级校验 |
| `list_workspaces` | `:1304` 同样只交回 `Value` | `yk` @689023 | 同上 |
| 变更判定 | 无 | main 的 `buildWorkspaceListPushSignature`（@399505）用 10 个字段判重 | 我们每次 `workspace-list-updated` 都当全量刷新，无法做"只在真变时才动 UI" |
| `pinned`/`archived`/`hasBackgroundWork` 的"缺省即 false"语义 | 无（无模型） | renderer `w2`（@4404753）用 `...u?{hasBackgroundWork:!0}:{}` 等**省略**写法 | 上层若用 `field.as_bool().unwrap_or(false)` 才对；用"必须存在"会全错 |
| `displayStatus` vs `liveStatus` | 无 | 线上只有 `displayStatus`（4 值）；`waiting` 永不上线 | 若从 host 控制器帧抄 `liveStatus`，会收到 4 值枚举之外的 `waiting` |

**建议**：在 `rust/crates/protocol/src/` 新增一个 `workspace_list.rs`（或放进 `conversation.rs` 旁），
按 §3.1 的两个 schema（`workspaces` 8 键、`tasks` 16 键）做**只读投影**：
`pinned`/`archived`/`hasBackgroundWork` 一律 `Option<bool>`（`None` 当 false）；
`displayStatus` 用 4 值枚举，**不要**引入 `waiting`。

### 7.2 Android `WorkspaceProjection`：字段名全线不匹配（**P0**）

`android/app/src/main/kotlin/app/zemote/ui/chat/ChatViewModel.kt`

| 行 | 我们读的键 | 对端实际键 | 结果 |
|---|---|---|---|
| **:441** `val key = strField("key") ?: strField("workspaceKey") ?: strField("id") ?: return null` | `key` / `workspaceKey` / `id` | 工作区 DTO（`xU` @686628）**一个都没有**；只有 `workspacePath` + `workspaceIdentity` | ⇒ **每个工作区都返回 null ⇒ 工作区列表恒为空**（`from()` 返回 `emptyList`，:306/:355 写进 state） |
| :445 `name = strField("name") ?: strField("title") ?: key` | `name`/`title` | **`label`** | 名字永远退化成 key（而 key 恒为 null） |
| :446 `path = strField("path") ?: strField("workspacePath") ?: strField("root")` | 含 `workspacePath` | `workspacePath` ✓ | 这一项是对的 |
| :447 `kind = strField("kind") ?: strField("workspaceKind") ?: strField("type")` | 含 `kind` | `kind` ✓ | 对（工作区里确实叫 `kind`） |
| :448 `connected = boolField("connected")` | `connected`(bool) | **`connectionState`(string: connected/disconnected/reconnecting)** | 恒为 null |
| :449 `lastError = strField("lastConnectionError") ?: strField("error")` | 含 `lastConnectionError` | `lastConnectionError` ✓ | 对 |
| **:450** `tasks = (this["tasks"] ?: this["items"])` | 工作区**内嵌** `tasks` | 工作区 DTO **没有** `tasks`；任务是 `result.tasks` 这个**顶层兄弟数组**（`yk` @689023） | ⇒ **`WorkspaceItem.tasks` 恒为空** |
| **:456-465** `toTask()`（`:457` id、`:461` title、`:462` status、`:463` updatedAt、`:464` unread） | `id`/`taskId`/`key`；`title`/`name`；`status`/`state`；`updatedAt`/`lastActivityAt`/`createdAt`；`unread`(bool) | `taskId` ✓；`title` ✓；**`displayStatus`**（不是 `status`）；`updatedAt` ✓；**`unreadAt`(number)**（不是 `unread`(bool)） | ⇒ `TaskItem.status` 恒 null、`TaskItem.unread` 恒 false |
| :426-438 `from()` | 只认 `workspaces`/`items`/`list` | `workspaces` ✓（bootstrap 与 workspace-list 都是） | 外层容器是对的 |
| :49-55 `TaskItem` | `id/title/status/updatedAt/unread` | 16 键 | **缺 11 个字段**：`displayStatus`、`hasBackgroundWork`、`unreadAt`、`pinned`、`archived`、`workspaceLabel`、`workspaceKind`、`workflowActivity`、`provider`、`createdAt`、`remoteSessionId` |
| :32-41 `WorkspaceItem` | `key/name/path/kind/connected/lastError/tasks` | 8 键 | **缺 `label`（当 name 用）、`workspacePurpose`、`remoteSessionId`**；`key` 应改为 `workspaceIdentity?.trim() ?: workspacePath` 现算；`connected` 应改为 `connectionState` |
| :355 `WorkspaceListUpdated` 分支 | `if (list.isNotEmpty()) _state.update {...}` | 对端 `workspace-list-updated` 是**整表快照**（`result` 里带完整 `workspaces` + `tasks`） | 空列表是**合法的**（工作区全关）⇒ 这条"空列表不覆盖"会**卡住 UI 不更新**。注释里"协议允许增量事件"与 §6.1 的 `W(h)` 不符 |
| :306 `WorkspaceProjection.from(snapshot)` | 传 `bootstrap()` 的 result | `result.workspaces` ✓、`result.tasks`（**顶层**） | 只拿到 workspaces，拿不到 tasks |

**P0 修复清单**（按 §3.1/§6 的权威）：
1. `key` 改为 `workspaceIdentity?.trim() || workspacePath` 现算（不要找 `key`/`id`）。
2. `name` 改读 `label`。
3. `connected` 改读 `connectionState`（`"connected"` 才算连通）。
4. `tasks` 从 **`result.tasks` 顶层数组**读，用 `(remoteSessionId ?? "local") + workspaceKey + taskId`
   归到工作区（或干脆平铺成一个列表）。
5. `TaskItem.status` 改读 `displayStatus`，并把枚举收紧为 `idle|running|completed|error`（**不要**加 `waiting`）。
6. `TaskItem.unread` 改读 `unreadAt`（number 存在即未读）。
7. 删掉 :355 的"空列表不覆盖"。

### 7.3 Android 引擎层

| 位置 | 现状 | 说明 |
|---|---|---|
| `android/.../engine/EngineModels.kt:211-212` | `data class WorkspaceListUpdated(val value: JsonElement)` | 原样透传，没问题 |
| `android/.../engine/EngineModels.kt:389` | `"workspace_list_updated" -> ...` | 与 Rust `ffi/src/codec.rs:229` 的 `{"type":"workspace_list_updated","value":...}` 对齐 ✓ |
| `android/.../engine/ZemoteEngine.kt:235` | `suspend fun bootstrap(): JsonElement = call(jsonCommand("bootstrap"))` | 原样透传 ✓ |
| `android/.../ui/EnginePort.kt:93` / `:233` | 同上层签名 | ✓ |
| `rust/crates/ffi/src/codec.rs:229` | `ZemoteEvent::WorkspaceListUpdated(value) => json!({"type":"workspace_list_updated","value":...})` | ✓ |

⇒ 引擎层不需要改；**投影层要按 §7.2 重写**。`ChatViewModel.kt:412` 的 TODO（"§13.5 冻结后搬进引擎层"）
现在可以落地了：字段名已经有权威依据。

### 7.4 其它不一致

| 项 | 我们 | 对端 |
|---|---|---|
| 任务清单范围 | 认为 `tasks` 与 `workspaces` 平级、独立 | main 会**先滤掉工作区不在 `workspaces` 里的任务**（`E(h)` @398125），且渲染进程**只推"已开 tab 的工作区"的任务**（`w2` 的 `if(!o) return null`） |
| `workspaceLabel` 语义 | 无 | = 工作区路径 basename（`ru` = `getPathLabel` @389901） |
| `workspaceKind` 语义 | 无 | 由**工作区 tab**的 `workspaceIdentity \|\| remoteTarget \|\| remoteSessionId` 决定（renderer @4404753），**不是**工作区 DTO 的 `kind` |
| `pinned`/`archived` | 无 | 是"列表种类旗标"，只可能 `true` 或缺省；**没有 `false`** |
| 任务排序 | 无 | main：`updatedAt DESC, createdAt DESC, taskId ASC`；renderer 再排一遍同规则 |
| 自动归档 | 无 | 设置项 + 7 天默认 + 只在打开分组视图时跑（§5.3）——移动端**看不到**归档触发，只会收到 `archived` 变化 |
| `provider` | 无 | 线上只可能是 `"glm"`（`mE=["glm"]` @533355）；DB 里 5 值但 `transform` 成 `"glm"` |

---

## 8. 仍未证实 / 需要抓包

1. **`hasBackgroundWork` 的确切触发条件**（§4.3）。app.asar 内 21 处全是 schema/透传，产生方是
   agent 运行时（`glm`/`zcode-agent` 可执行体，不在 asar 内）。
   **需要**：抓 `sessions-index/<workspaceKey>` 的 `session.upserted` 帧，在
   ①后台 bash ②子代理 ③workflow run ④goal continuation 四种情形下分别看该布尔。
2. **host 崩溃后谁来重启**（§1.4）。`d.on("exit")` 只清理不重启；renderer 侧未找到显式重载钩子
   （`hostProcessExited`/`host-crashed`/`onHostRestarted` 命中 0）。
   **需要**：`taskkill /PID <host pid>` 后观察 renderer 是否自动 reload / 是否弹错误。
3. **`agent` 进程的边界**：`V.AgentProcessSpawned` 带 `provider/workspacePath/command/args`，
   说明 agent 是**子进程**；但它的入口、协议（相对 host）在 asar 内不可见。
   **需要**：`out/host/index.js` 里 `spawn` 的可执行路径（运行时打印 `glm binary path`）+ 抓它的 stdio/IPC。
4. **`displayStatus` 的 `runtimeStatus` 支路**（§3.4 的 `spn(t)` = `creating|streaming`）来自哪个 store。
   本轮未定位 `taskRuntimeByWorkspaceKey` 的产生方（renderer @6426539 的 `fpn(n)` 从
   `Nl(e=>e.workspaces)` 取 `workspace.taskRuntimeByTaskId`）。
   **需要**：在 renderer 里追 `taskRuntimeByTaskId` 的 setter。
5. **`sessions-index` 首帧 staging 溢出的实际后果**：`U8 = 32 MiB` / `TOe = 1024` 帧
   （`out/host/index.js` @1616xxx），超限即 `fault.subscription.initialFrameStagingOverflow`
   且**不重试**（`k && e.onError?.(...)`）。**需要**：确认移动端是否会看到"任务列表永久为空"。
6. **`workspacePurpose === "conversation"` 的工作区**在任务清单里如何表现（renderer 侧栏把
   conversation 工作区单独分节，`workspaceSidebar.conversationsSection`），但 `w2` 不做区分。
   **需要**：抓一个 conversation 工作区的 `workspace-list-updated`，看 `workspaces[i].workspacePurpose`。
7. **`unreadAt` 的写入时机**（agent 写还是 host 写）。已知 DB 有 `unread_at` 与 `last_unread_at` 两列，
   且 `setTaskUnread` 支持 `expectedUnreadAt` 乐观并发；**未见** `last_unread_at` 的读方。
   **需要**：`grep` `last_unread_at` 的 SELECT（本轮只在 `archiveStaleTasks` 的列清单里见过它）。
8. **任务清单的实际上限**：schema 无上限，`listTaskList` 的 `limit` 由渲染进程给
   （`ej` 传 `collapsedLimit:100` 但 `expanded:true` ⇒ `limit: undefined`）。
   **需要**：在任务数 > 300 的窗口里抓 `syncWebRemoteControlTasks` 的数组长度。
9. **`dd`（host 侧 sessions-index topic 构造器）的独立符号身份**：已证 `_n`（T2GB4I3K @6917）与
   `ge`（`Db`→`hO` @548627，`resolveWorkspaceKey`）两处规则；`dd` 的导出别名未逐一追平。
   **需要**：`chunk-T2GB4I3K.js` 导出表里 `dd` 的对应本地名（不影响结论：topic 规则已由两处独立证实）。
