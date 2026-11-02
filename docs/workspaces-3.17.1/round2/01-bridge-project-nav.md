# 工作区桥接 / 全部项目导航：Round 2 交叉复核与落地规格

**基线**

- Web 快照：`E:\Zcode_android\Reference_web\3.17.1\assets\index-Dwhx77cK.js`（6,986,617 B）、
  `Reference_web\3.17.1\assets\validation-B0HfQ5gr.js`（348,943 B）、`assets\src-BSClmRM9.js`。
- 桌面 asar（同 3.17.1 发布，374,627,566 B）：`C:\Users\wdn32\AppData\Local\Programs\ZCode\resources\app.asar`，
  经 `node E:\Zcode_android\tools\asar.mjs cat/dump/grep` 只读读取，未执行包内代码。
- Rust：`rust/crates/protocol/src/channel_client.rs`、`rust/crates/protocol/src/zemote_client.rs`、
  `rust/crates/ffi/src/codec.rs`、`rust/crates/ffi/src/session.rs`。
- Kotlin：`android/app/src/main/kotlin/app/zemote/ui/home/HomeViewModel.kt`、
  `ui/chat/ConversationRegistry.kt`、`ui/EnginePort.kt`、`ui/chat/ChatViewModel.kt`。

**坐标约定**

- 压缩 bundle（`index-Dwhx77cK.js`、`out/main/index.js`、`out/host/*.js`）：`文件@UTF-8 字节偏移`，
  偏移由 `grep -b -o` / `asar.mjs grep @offset` 取得。
- 源码文件（Rust / Kotlin）：给 `文件:行号` + 原文片段。审计期间 Android 文件正被并发修改
  （`HomeViewModel.kt` mtime 16:35、`ChatViewModel.kt` 16:34），行号可能漂移，以片段为准。
- 所有引用均为原文片段；省略号不改变语义。中文错误串在压缩产物里是 `\uXXXX`，本文还原后引用。

---

## 0. 五问结论摘要

1. **`channel_call` 的线上 arg 必须是「位置参数数组」**。`sendRequest(e,t,n,r,i)` 的 `i` 就是
   `channel.call(method, args)` 里的 `args` 数组；服务端 `ChannelServer.onPromise` 原样把它交给
   `fromService` 包装器 `service[method].apply(service, arg || [])`。所以 Android 传
   `JsonArray(listOf(obj))` **完全正确**，`zcode-task.listPinnedTasks([arg])` 成功正是因为这个形状；
   Round1 的「多包了一层、应传单对象」结论被推翻（照它改反而会全线 `TypeError`）。
2. **`workspace-bridge-open` 有两类路径**：无 intent 时按 `getAvailableWorkspaces` 的 key 找 tab；
   带 `taskHostId` 或 `project` 时走 `prepareProjectWorkspace`，**不需要**目标工作区是当前 tab。
   打开「非 tab 项目中的任务」= `{workspaceKey:taskKey, taskId, taskHostId}`；
   在「非 tab 项目中新建对话」= `{workspaceKey:"project-navigation", project:{projectId, hostId?}}`
   （本地未归类草稿才用 `projectId:null + draftId + reuseDraft`）。
3. **`prepareProjectWorkspace` 的返回是权威来源**：主进程把 `{projectId,hostId,draftId,reuseDraft,task?}`
   转发给窗口 Local Host，由 `createProjectWorkspaceNavigation` 解析成
   `{workspacePath, workspaceIdentity?, hostId, remote?}`；随后 `buildWorkspaceBridge` 用
   `workspaceKey = workspaceIdentity?.trim() || workspacePath` 重新计算并写进 `bridge`。
   **bridge-ready 的 `workspaceKey` 可能与请求 key 不同**（草稿目录、项目主根、identity 归一化）。
   Kotlin 当前 `_bridgeWorkspaceKey.value = workspaceKey` 记的是请求值，是隐性 bug。
4. **远端项目/任务必须「先 directory，再逐 host readTree」**：`remote-projects.readDirectory()`
   返回 `{hosts:[{id,membershipId,kind,target,position,connectionStatus,...}], entries:[...]}`；
   每个 host 再 `project.readTree({hostId,includeDeleted,includeDeletedTasks})`。
   web 端 `Mbt` 对**所有** host 并发 `readTree`（`Promise.allSettled`），只对 online host 记录错误，
   合并时只接受「刷新后仍在 directory 里」的 host；远端任务还要求其 `projectId` 在 `entries` 中
   且项目 online（`projectId==null` 的任务例外）。
5. **远端任务的 `workspaceIdentity` 与项目 `roots[].rootPath` 没有字段级绑定**：identity 是按
   `task.workspacePath` 现算的 `${hostId}:${normalizePath(path)}`（远端恒为 `remote:server:<serverId>`），
   仅当 task 恰好位于某个 root 下时两者路径相等。构造 `WorkspaceItem` 时 identity **只能**取
   task 行的权威值；项目无任务时才用 `${project.hostId}:${gy(rootPath)}` 推导，本地一律 `key=path`。

与 Round1 的逐条冲突见 §6。

---

## 1. Q1：`channel_call` 的参数形状

### 1.1 线上帧

RPC 帧由两段 VQL 编码组成：

```
header = [reqType, id, channelName, methodName]
arg    = <任意 VQL 值>
```

请求 `reqType=100`（Promise），事件订阅 `reqType=102`。

### 1.2 web 端 `sendRequest` 完整原文

`Reference_web/3.17.1/assets/index-Dwhx77cK.js@262144`：

```js
sendRequest(e,t,n,r,i){let a=new vu;Tu(a,[e,t,n,r]),Tu(a,i);try{this.protocol.send(a.buffer)}catch{}}
```

调用方 `requestPromise`（`index-Dwhx77cK.js@260243`）：

```js
requestPromise(e,t,n,r=Wle.None){let i=this.lastRequestId++;……this.sendRequest(Du.Promise,i,e,t,n)……}
```

`getChannel`（`index-Dwhx77cK.js@260048`）：

```js
getChannel(e){return{call:(t,n,r)=>this.isDisposed?Promise.reject(...):this.requestPromise(e,t,n,r),……}}
```

所以：

| 形参 | 含义 |
|---|---|
| `e` | 帧类型（100/102） |
| `t` | 请求 id |
| `n` | channel 名 |
| `r` | 方法名 |
| **`i`** | **参数值本身**；在 Promise 路径上就是 `requestPromise` 的第三个实参 `n`，也就是
  `channel.call(method, arg)` 的 `arg` |

### 1.3 `i` 是什么：位置参数数组

web 客户端为每个 channel 建的是 `Au.toService(getChannel(...))` 代理（`index-Dwhx77cK.js@264050`）：

```js
function n(e,t){return new Proxy({},{get(n,r,i){……:async(...n)=>{let i=t?.context===void 0?n:[t.context,...n];return e.call(r,i)}:……}})}
```

即：调用 `service.readTree({hostId})` 时 `(...n)` = `[ {hostId} ]`，代理把**整个数组**作为 `arg`
交给 `channel.call`。服务端反解（`index-Dwhx77cK.js@263762`，同文件 `Au.fromService`）：

```js
call(e,t,r){let i=n[t];if(typeof i==="function"){let e=i.apply(n,r||[]);……}}
```

`r`（线上 arg）被 `apply` **展开为位置参数**。因此单参数方法 `readTree(s)` 收到的 `s` 就是数组第 0 项；
零参方法收到 `[]`；多参方法按顺序展开。

同一套 `fromService/toService` 也存在于桌面 asar 的 host chunk
（`out/host/chunk-ZDLGWCUS.js@9403` / `@12033`，逻辑逐字一致），说明两端协议同源。

### 1.4 服务端解包

`out/host/chunk-ZDLGWCUS.js@9116`（`ChannelServer.onRawMessage`）：

```js
case 100:this.onPromise({type:r,id:s[1],channelName:s[2],name:s[3],arg:y(t)});return;
```

`out/host/chunk-ZDLGWCUS.js@9403`：

```js
onPromise(e){let t=this.channels.get(e.channelName);if(!t){this.collectPendingRequest(e);return}……r=t.call(this.ctx,e.name,e.arg,s.token)……}
```

`onPromise` 把 `e.arg` 原样交给 `fromService` 包装器的 `call(ctx, method, arg)`，再 `apply` 展开。
**没有任何「如果 arg 是数组就解一层 / 不是数组就包一层」的兼容逻辑。**

### 1.5 Rust / Kotlin 侧

- `rust/crates/protocol/src/channel_client.rs:392` `call(channel, method, args: Vec<Value>, timeout_ms)`。
- `channel_client.rs:525-534`：

```rust
fn send_call(&mut self, id: u64, channel: &str, method: &str, args: &[Value]) {
    ...
    self.send_request(Self::REQ_PROMISE, id, channel, method, &Value::Array(args.to_vec()));
}
```

- `rust/crates/ffi/src/codec.rs:988-996`：`channel_call` 的 `args` 由 `optional_value_array` 解析为
  `Vec<Value>`，注释明确「`args` 是**位置参数数组**」。
- `android/.../ui/EnginePort.kt:187-193`：`channelCall(..., args: JsonArray = JsonArray(emptyList()))`，
  KDoc「`args` 是位置参数数组」；实现直接透传。

### 1.6 为什么 Android 现有调用能成功

`HomeViewModel.kt`（当前版本 `:543-552`）：

```kotlin
val args = JsonArray(listOf(buildJsonObject {
    put("includeDeleted", false)
    put("includeDeletedTasks", true)
}))
val response = runCatching {
    engine.channelCall(bridge, "project", "readTree", args, timeoutMs = 12_000)
}.getOrNull().asObject() ?: return false
```

线上 arg = `[{includeDeleted:false,includeDeletedTasks:true}]`，服务端 `readTree.apply(service, [obj])`
→ `readTree` 收到 `obj`。同理 `zcode-task.listPinnedTasks` 的签名是单对象
（`out/host/index.js@668897`）：

```js
async listPinnedTasks(C){return(await T.listTaskMetas({workspacePath:C.workspacePath,workspaceIdentity:C.workspaceIdentity,...pinned:!0,archived:!1})).map(Vn)}
```

`[arg]` 展开后 `C={workspacePath,...}`，完全正确。

反证：若按 Round1 的建议改传单个对象（`arg={...}`），`fromService` 会执行
`listPinnedTasks.apply(service, {workspacePath:...})`；`Function.prototype.apply` 对非数组的
非对象实参抛 `TypeError: CreateListFromArrayLike called on non-object`。所以 **Round1 的修法会让
当前所有 `channelCall` 全部失败**，不是修 bug，是造 bug。

### 1.7 落地规范

1. `channel_call.args` **永远是数组**，元素按方法形参顺序排列。
2. 单参数方法：`[arg]`；零参方法：`[]`（不要传 `null`/省略；FFI 缺省也会得到 `[]`，但显式更好）。
3. 不要为某个方法特判「传对象还是传数组」。方法签名在 host 服务里是一等公民：
   `readTree(s)`、`listPinnedTasks(C)`、`getCapabilities()`、`readDirectory()`。
4. Kotlin 侧保持 `JsonArray(listOf(...))` 即可；当前 `HomeViewModel` 的 `readTree` 调用形状正确。
   历史上真机验证过的 `zcode-task.listPinnedTasks([arg])` 用的也是同一形状——它成功不是巧合，
   正是「arg 是位置参数数组」的直接证据。

---

## 2. Q2：`workspace-bridge-open` 的全部字段与语义

### 2.1 客户端 schema（3.17.1 权威）

`Reference_web/3.17.1/assets/validation-B0HfQ5gr.js@322465`：

```js
D({zcode_type:N(`workspace-bridge-open`),
   terminalClientSessionId:$.optional(),
   requestId:Q,
   bridgeSessionId:$,
   bridgeGeneration:C().int().nonnegative().optional(),
   recoveryId:$.optional(),
   workspaceKey:Q,
   taskId:Q.optional(),
   taskHostId:Q.optional(),
   fileTreeTaskId:Q.optional(),
   project:D({projectId:Q.nullable(),hostId:Q.optional(),draftId:x().uuid().optional(),reuseDraft:w().optional()})
       .strict()
       .refine(e=>(!e.draftId||e.projectId===null)&&(!e.reuseDraft||!!e.draftId),`Only an unclassified draft can reuse its directory`)
       .optional()})
 .refine(e=>(!e.project||!e.taskId&&!e.fileTreeTaskId)
           &&!(e.taskId&&e.fileTreeTaskId)
           &&(!e.fileTreeTaskId||!!e.taskHostId)
           &&(!e.taskHostId||!!(e.taskId||e.fileTreeTaskId)),
        `Project draft cannot target an existing task`)
```

| 字段 | 必填 | 语义 |
|---|---|---|
| `zcode_type` | 是 | 固定 `workspace-bridge-open` |
| `terminalClientSessionId` | 否 | 终端 owner 的客户端会话 id。给了才绑定 `terminalOwner`；与当前 terminal owner 不一致时服务端拒绝 `Terminal page session has ended; bootstrap is required.`；并决定 ready 里的 `terminalOwnerId` |
| `requestId` | 是 | 应答匹配用 |
| `bridgeSessionId` | 是 | 客户端生成的桥标识；ready/error 回显 |
| `bridgeGeneration` | 否 | 单调代号；同一 `bridgeSessionId+generation` 重复请求会复用同一个 promise（同 intent 时） |
| `recoveryId` | 否 | 恢复重开路径（`reopen_bridge_request`）才带；普通打开不传 |
| `workspaceKey` | 是 | 无 intent 时是 tab 查找键；有 intent 时只参与 intent 签名/日志 |
| `taskId` | 否 | 初始任务；同时作为 `bridge.initialTaskId`。仅当与 `taskHostId` 同时出现才触发任务导航 |
| `taskHostId` | 否 | 任务所属 host（`"local"` 或远端 hostId）。触发 `prepareProjectWorkspace({hostId, task:{taskId,workspaceKey}})` |
| `fileTreeTaskId` | 否 | 文件树任务 id；与 `taskHostId` 一起触发同一任务导航，但 `taskId` 取它（`initialTaskId` 仍取 `taskId`，通常为空）。web 只在「打开任务文件树」时使用 |
| `project` | 否 | 项目/草稿导航意图。`projectId:string|null` 必填于对象内；`hostId` 可选；`draftId` 必须 UUID 且只在 `projectId===null` 时允许；`reuseDraft` 必须伴随 `draftId`。对象 `.strict()`，多余键报错 |

### 2.2 合法组合（客户端 schema + 服务端双重约束）

| `project` | `taskId` | `taskHostId` | `fileTreeTaskId` | 结果 |
|---|---|---|---|---|
| 无 | 无 | 无 | 无 | 纯 tab 查找：`getAvailableWorkspaces().find(key)`；不在当前窗口 tab → `目标工作区不在当前桌面窗口中，无法创建 Web 远程控制 bridge。` |
| 无 | 有 | 无 | 无 | tab 查找 + `bridge.initialTaskId=taskId`（**不**触发 prepare） |
| 无 | 有 | 有 | 无 | 任务导航；`prepareProjectWorkspace({hostId:taskHostId, task:{taskId,workspaceKey}})` |
| 无 | 无 | 有 | 有 | 任务导航；`task.taskId=fileTreeTaskId`，`initialTaskId=undefined` |
| 有 | 无 | 无 | 无 | 项目/草稿导航；忽略 `workspaceKey` 查找 |
| 有 | 有/有 `fileTreeTaskId` | — | — | **非法**：schema 拒绝；即使绕过 schema，服务端也抛 `Project navigation is unavailable.` |
| 无 | 有 + 有 `fileTreeTaskId` 同时 | — | — | **非法**（schema） |
| 无 | 无 | 有但无 task/fileTree | — | **非法**（schema） |
| 无 | 有但不给 taskHostId | — | — | 合法但**不会 prepare**，退化成 tab 查找（项目树任务会失败） |

服务端 `buildWorkspaceBridge` 的 intent 选择（`out/main/index.js@423608`）：

```js
let O,Z=b.project??((b.fileTreeTaskId||b.taskId)&&b.taskHostId
    ?{hostId:b.taskHostId,task:{taskId:b.fileTreeTaskId??b.taskId,workspaceKey:b.workspaceKey}}
    :void 0);
if(Z){if(b.project&&b.taskId||!e.prepareProjectWorkspace)throw new Error("Project navigation is unavailable.");
```

- `b.project` 优先；`project` 与任何 task 同时出现直接抛错（防御 schema）。
- `taskHostId` 是触发 prepare 的必要条件；`taskId`/`fileTreeTaskId` 二选一。
- `preparingProject: !!(b.project || b.taskHostId)`（`out/main/index.js@422530` 附近），
  prepare 期间可用 `cancelProjectPreparation` 取消。

### 2.3 两种 intent 的准确 payload

#### A. 打开「非当前 tab 项目中的任务」

来源：web `useWebRemoteControlTaskOpen`（`index-Dwhx77cK.js@2349364`）：

```js
await d.switchWorkspace(m,{taskId:f.taskId,...f.hostId?{taskHostId:f.hostId}:{},...})
```

`m = f.workspaceIdentity?.trim() || f.workspacePath`（即 `readTree.tasks[]` 的任务 `workspaceKey`）；
`f.hostId` 是 `task.hostId`，**本地任务也传 `"local"`**（truthy）。

协议 payload：

```json
{
  "zcode_type": "workspace-bridge-open",
  "requestId": "workspace-bridge-<n>",
  "bridgeSessionId": "<uuid>",
  "bridgeGeneration": 1,
  "workspaceKey": "<task.workspaceIdentity?.trim() || task.workspacePath>",
  "taskId": "<task.taskId>",
  "taskHostId": "<task.hostId>"
}
```

Android（Rust 已支持 `taskHostId`，见 `codec.rs:941-950`）：

```json
{"method":"open_bridge","workspaceKey":"<taskKey>","taskId":"<taskId>","taskHostId":"<hostId>","timeoutMs":30000}
```

硬性要求：

- `workspaceKey` 必须**逐字**等于 `task.workspaceIdentity?.trim() || task.workspacePath`；
  服务端 `readTask` 以它做三条件匹配（`hostId + taskId + workspaceKey`），不匹配抛
  `project.task-not-found`。
- `taskHostId` 必须是 `task.hostId`（`"local"` 或 `remote:server:*`）。
- 本地任务也**必须**带 `taskHostId:"local"`；不带就退化成 tab 查找。

#### B. 在「非当前 tab 项目中新建对话」

来源：web `openProjectDraft`（`index-Dwhx77cK.js@6978369`）：

```js
ue=async e=>{let n=e.projectId??null,
  r=!n&&(!e.hostId||e.hostId===`local`)?Xmt({clientNamespace:`mobile:...`,hostId:`local`,projectId:null}):null,
  i=await ce(u??`project-navigation`,{project:{projectId:n,...e.hostId?{hostId:e.hostId}:{},
      ...r?{draftId:r.draftId,reuseDraft:!!r.workspacePath}:{}}});
  le(i.services,i.bridge,`chat`,{...e,projectId:n,...r?{draftId:r.draftId}:{}})}
```

即：

```json
{
  "zcode_type": "workspace-bridge-open",
  "requestId": "workspace-bridge-<n>",
  "bridgeSessionId": "<uuid>",
  "bridgeGeneration": 1,
  "workspaceKey": "project-navigation",
  "project": { "projectId": "<projectId>", "hostId": "<hostId>" }
}
```

- `hostId` 可省略（= `local`）；本地项目省略即可。
- 远端项目**必须带 `projectId`**：`hostId !== "local"` 且无 `taskId`/`projectId` 时服务端直接抛
  `project.remote-project-required`（`host/index.js@850228` 内 `DM`）。
- `projectId` 为 `null` 只用于本地「未归类草稿」：`{projectId:null, draftId:"<uuid>", reuseDraft:true|false}`，
  且 `draftId` 是本地生成的 UUID（web `Xmt` 产出）。
- `reuseDraft` 只能与 `draftId` 同时出现；远端路径会丢弃 draft 语义（`iH.prepareSession` 对远端
  只转发 `{projectId}`）。
- Android `projectIntent()` 当前就是 `{projectId, hostId?}`，符合规格。

#### C. 其他对照

- 「当前 tab 工作区新建对话」用 `startDraft(workspaceKey)`：`ce(e, void 0)` → 纯 tab 查找；
  不要在 `project` 与纯 key 之间混用。
- 打开任务文件树：`{taskHostId, fileTreeTaskId, mobileNavigationIntent:"chat"}`
  （`index-Dwhx77cK.js@2353507`）。Rust FFI 目前**没有** `fileTreeTaskId` 字段
  （`codec.rs:941-950` 只解析 `taskId`/`taskHostId`/`project`），Android 暂不可用。

### 2.4 `prepareProjectWorkspace` 在手机远控路径是否始终可用

**结论：3.17.1 桌面端始终注入；能否成功取决于窗口/Local Host/目标项目状态，而不是 tab。**

证据一：web-remote-control 管理器构造时无条件提供 handler（`out/main/index.js@837062`）：

```js
prepareProjectWorkspace:s(async(e,t,r)=>{let n=Be.fromId(e);if(!n||n.isDestroyed())throw new Error("项目导航窗口已关闭");return Ct.prepareProjectWorkspace(n,t,r)},"prepareProjectWorkspace"),
cancelProjectPreparation:s((e,t)=>{let r=Be.fromId(e);r&&!r.isDestroyed()&&Ct.cancelPendingRemoteWorkspaceSessionsForWindow(r.webContents.id,"mobile-navigation-superseded",t)},"cancelProjectPreparation"),
```

证据二：`$e` 只在缺 handler 时兜底抛 `Project navigation is unavailable.`（`out/main/index.js@423608`）。

证据三：真正实现要求窗口存活 + Local Host 存活（`out/main/index.js@667114` 的 `Te` 投递
`PrepareProjectWorkspace`；`out/host/index.js@2168091` 的 handler 里 `if(!s||Bd)throw new Error("Window Host is unavailable")`）。

常见失败码（均来自 host 侧 `DM`，`out/host/index.js@850228`）：

| 错误 | 触发 |
|---|---|
| `Project navigation is unavailable.` | 缺 prepare handler，或 `project` 与 `taskId` 同时给 |
| `项目导航窗口已关闭` / `Window Host is unavailable` | 窗口销毁 / host 进程退出 |
| `project.host-offline` | 远端 host 无 target / 无连接 owner / `connectionStatus!=="online"` |
| `project.remote-project-required` | 远端 host 既无 task 也无 projectId |
| `project.task-not-found` | `(hostId,taskId,workspaceKey)` 匹配不到任务 |
| `project.task-directory-mismatch` | `resolveSessionDirectories` 结果与任务行 workspacePath/identity 不一致 |
| `project.not-retained` | 远端项目不在本机 remote project 缓存（entries）里 |
| `project.navigation-stale` | 解析期间 membership/entry 变化 |
| `project.primary-root-missing` / `project.primaryRootMissing` / `project.primaryRootUnavailable` | 本地项目主根缺失/不可用 |
| `project.directory-required` | `reuseDraft` 但草稿目录不存在 |
| `project.invalid-draft-target` | `draftId`+`projectId` 同给，或 `reuseDraft` 无 `draftId` |

---

## 3. Q3：`prepareProjectWorkspace` 的行为与权威返回值

### 3.1 调用链（没有 renderer 参与）

```
[main] $e (buildWorkspaceBridge)
  └─ e.prepareProjectWorkspace(windowId, payload, hostRequestId)      out/main/index.js@837062
       └─ Ct.prepareProjectWorkspace(win, payload, requestId)          out/main/index.js@667114 (Te)
            └─ winHostProcess.postMessage({type:PrepareProjectWorkspace, requestId, ...payload, clientMode:"web-remote-replayable"})
                 └─ [host] if(n.type===an.PrepareProjectWorkspace)     out/host/index.js@2168091
                      └─ DM({projectService, remoteProjectService, readConnectionOwner, openRemoteWorkspace})
                           .prepare({projectId,hostId,draftId,reuseDraft,...task})   out/host/index.js@2168555
                           └─ 回投 ProjectWorkspacePrepared {ok, value|error}          out/host/index.js@2168202 / @2169389
                 └─ [main] It(acceptPreparedProjectWorkspace)          out/main/index.js@654060
```

**重要更正**：处理 `PrepareProjectWorkspace` 的是**窗口 Local Host 进程**（`out/host/index.js`），
不是 renderer。renderer 只在 `t4n` 里同步 tab 列表，与 prepare 无关。

### 3.2 `DM`（`createProjectWorkspaceNavigation`）逐步行为

schema（`out/host/index.js@849787`）：

```js
var lRe=g.object({projectId:g.string().min(1).nullable().optional(),hostId:g.string().min(1).optional(),
  draftId:g.string().uuid().optional(),reuseDraft:g.boolean().optional(),
  task:g.object({taskId:g.string().min(1),workspaceKey:g.string().min(1)}).strict().optional()})
 .strict().refine(e=>(!e.task||e.projectId===void 0&&e.draftId===void 0)&&(!e.draftId||!e.projectId)&&(!e.reuseDraft||!!e.draftId),
   "Task navigation cannot prepare a draft");
```

实现（`out/host/index.js@850228`）：

1. `r = hostId ?? "local"`；`r!=="local" && !task && !projectId` → `project.remote-project-required`。
2. `o = {hostId:r, projectId?, draftId?, reuseDraft?}`（传给 `prepareSession`）。
3. 有 `task` 时：
   - `readTree({hostId:r, includeDeleted:true})`；
   - 找 `T.hostId===r && T.taskId===task.taskId && (T.workspaceIdentity?.trim()||T.workspacePath)===task.workspaceKey`，
     找不到 → `project.task-not-found`；
   - `project = projects.find(id===task.projectId && hostId===r)`（可能 undefined）。
4. 目录解析：
   - 无 task → `projectService.prepareSession(o)`；
   - 有 task → `projectService.resolveSessionDirectories(task, r)`，并强校验
     `workspacePath===task.workspacePath && workspaceIdentity===task.workspaceIdentity`，
     否则 `project.task-directory-mismatch`。
5. `r==="local"` → 返回 `{...解析结果, hostId:"local"}`。
6. 远端：
   - `readProjectCatalog`（`nd`，`out/host/index.js@848778`）读 `remoteProjectService.readDirectory()` +
     本地/逐 host `readProjects`，得到 projects/directory；
   - host entry 必须存在 `target`，`readConnectionOwner(r)` 有值且 `connectionStatus==="online"`，
     否则 `project.host-offline`；
   - 项目归属：`p = task.project ?? (projectId ? catalog.projects.find(...) : undefined)`；
     `task.projectId && !p` 或 `projectId && !p` → `project.not-retained`；
   - `assertCurrent` 重新读 directory，校验 membership/entries 未变，否则 `project.navigation-stale`；
   - `workspaceIdentity = 解析结果.workspaceIdentity ?? Wo(hostId, workspacePath)`；
   - `openRemoteWorkspace` 连 source server、校验 physical owner 未变，再
     `openWorkspaceOnSource({workspacePath, workspaceIdentity, workspacePurpose: projectId?"project":"conversation"})`；
   - 返回 `{...解析结果, hostId:r, remote:<descriptor>}`。

### 3.3 返回值形状

| 分支 | 返回 |
|---|---|
| 本地、无 task | `prepareSession` 结果 + `hostId:"local"`，即 `{workspacePath, additionalDirectories, projectId, hostId}`；identity 缺失 |
| 本地、有 task | `resolveSessionDirectories` 结果 + `hostId:"local"`，即 `{workspacePath, projectId, additionalDirectories, hostId}`；identity 缺失（除非任务行/解析器给出） |
| 远端（task 或 project） | `{workspacePath, workspaceIdentity, projectId?, additionalDirectories?, hostId, remote:{remoteSessionId, workspacePath, workspaceIdentity, target, sourceServerId, ...}}` |

`prepareSession` 本地语义（`out/host/index.js@45062`）：

- 无 `projectId`：`workspacePath = nH(workspaceDirectory, draftId?`draft-${draftId}`:`${Date.now()}-${uuid}`)`；
  `reuseDraft` 时目录必须已存在，否则 `project.directory-required`；否则递归建目录。
  返回 `{workspacePath, additionalDirectories:[], projectId:null}`。
- 有 `projectId`：校验项目存在/未删、`isPrimary` 存在、主根状态 `available`；
  **远端 owner 时**额外要求 `roots.length===1`（`project.remote-single-root-required`），本地允许多根；
  `workspacePath = primaryRoot`，`additionalDirectories = 其它 roots`。
- 远端分支在 `iH.prepareSession` 里只转发 `{projectId}`（`out/host/index.js@45062`）：
  `let c=await t(s?.hostId); if(c) return c.prepareSession({projectId:s?.projectId})`。

### 3.4 bridge-ready 的 `workspaceKey` 会不会与请求不同

会。`$e` 在 prepare 后重新计算（`out/main/index.js@423608`）：

```js
O={workspacePath:ie.workspacePath,workspaceIdentity:ie.workspaceIdentity,
   remoteSessionId:ie.remote?.remoteSessionId,kind:ie.remote?"remote":"local",label:ie.workspacePath,...}
...
let Q=ot(O), ...   // ot() = workspaceIdentity?.trim() || workspacePath
let J={terminalOwnerId:...,bridgeSessionId:b.bridgeSessionId,...,kind:O.kind,workspaceKey:Q,
       workspacePath:O.workspacePath,workspaceIdentity:O.workspaceIdentity,remoteSessionId:O.remoteSessionId,
       initialTaskId:b.taskId, ...}
```

差异来源：

1. **本地项目草稿**：`prepareSession` 新建随机目录 → `bridge.workspaceKey` 是全新路径，
   与请求的 `"project-navigation"` 无关。
2. **项目（有 projectId）本地/远端**：`workspaceKey` = 项目主根路径（远端为主根的 server identity）；
   与调用方可能传的任何 key 都不同。
3. **任务导航**：`bridge.workspaceKey` 应等于 `task.workspaceIdentity?.trim() || task.workspacePath`；
   若调用方传错 key，`readTask` 已先抛 `project.task-not-found`，不会出现「悄悄不同」。
4. **远端 identity 归一化**：若任务行 identity 缺失，`Wo(hostId,path)` 会补出
   `remote:server:<id>:<normalizedPath>`，与原始路径字符串不同。
5. `bridge.initialTaskId = b.taskId`（不是 `fileTreeTaskId`，不是 prepare 返回值）。

### 3.5 调用方如何取权威值

权威顺序：

1. `workspace-bridge-ready.bridge.workspaceKey / workspacePath / workspaceIdentity / initialTaskId`
   —— 这是服务端最终生效值。web 客户端正是这样用的（`index-Dwhx77cK.js@6974987` 的 `ce`：
   `u=s.bridge.workspaceKey,d=s.bridge.initialTaskId,await oe(s.bridge.workspaceKey,s.bridge.initialTaskId)`；
   随后 `switchWorkspace` 传 `t`，桥内导航一律以 `bridge.workspaceKey` 为准）。
2. Rust 核心已经遵守：`finish_open_bridge` 用
   `bridge['workspaceKey'] ?? 请求值` 发 `mobile-view-state-update`
   （`zemote_client.rs:2150-2161`），`BridgeSession::workspace_key()` 读的是 ready 的 bridge map
   （`zemote_client.rs:563-566`）。
3. `snapshot` 目前只投影 `workspaceKey / initialTaskId / degraded / recovered / conversationKeys`
   （`rust/crates/ffi/src/session.rs:167-183`），**没有** `workspacePath / workspaceIdentity`。

Android 现状与修正：

- `ui/EnginePort.kt:402`：

```kotlin
_bridgeSession.value = session
_bridgeWorkspaceKey.value = workspaceKey   // 请求值，不是 ready 的 bridge.workspaceKey
```

  对草稿/项目导航会记错。正确做法：`openBridge` 成功后调 `snapshot()`，按
  `bridges[].session === session` 取 `workspaceKey`/`initialTaskId`，覆盖 `_bridgeWorkspaceKey`。
- `ConversationRegistry.ensure` 以请求 key 为 map 键与 `Handle.workspaceKey`；应额外保存
  `readyKey`（或把 Handle 的 workspaceKey 改成 ready 值），否则上层用请求 key 去发
  `mobile-view-state-update`/二次查表会与桌面不一致。
- 草稿场景还需要 `workspacePath`/`workspaceIdentity`。建议 Rust `snapshot` 的 bridge 投影补上
  `workspacePath`、`workspaceIdentity`、`kind`；否则 Android 只能：
  （a）本地草稿用 `bridge.workspaceKey` 当路径（草稿 bridge key == path，因为无 identity，尚可）；
  （b）远端草稿必须从 ready 的 identity 取 scope，当前 API 拿不到。
- `conversation_open` 的 `workspacePath` 必须是**真实路径**（桌面把它当 cwd）；草稿 bridge 的
  `workspaceKey` 恰好等于新路径，可临时用；远端必须等 snapshot 扩展。

### 3.6 `prepareProjectWorkspace` 期间的生命周期

- `bridgeOpen` 记录 `intentKey = JSON.stringify([terminalClientSessionId, workspaceKey, taskId??null,
  project?.projectId, project?.hostId, project?.draftId, taskHostId, fileTreeTaskId])`
  （`out/main/index.js@422530` 的 `Fe`）。同一 `bridgeSessionId+generation` 重复请求：
  intentKey 不同 → `Workspace bridge identity cannot change intent.`；相同 → 复用 promise。
- 已被取代/降级 → `Workspace bridge request was superseded.` / `Degraded workspace bridge requires a new identity.`
- `ge()`（cancelBridgeOpen）会 `cancelProjectPreparation(windowId, hostRequestId)`；
  上层换目标时应换 `bridgeSessionId/generation`。

---

## 4. Q4：`readTree` 远端项目与 `remote-projects.readDirectory`

### 4.1 `remote-projects.readDirectory` 返回形状

实现：`out/host/index.js@25068`（`Dm`）→ `@25413`：

```js
async readDirectory(){if(e.controller===!1)return{hosts:[],entries:[]};
  let c=await e.database();c.exec("BEGIN");try{let d=rl(c);c.exec("COMMIT");
  let l=await e.readConnectionStates?.();
  return l?{...d,hosts:d.hosts.map(u=>({...u,connectionStatus:l.get(u.id)?.connectionStatus??"offline",
    lastConnectionError:l.get(u.id)?.lastConnectionError,lastConnectionStatus:l.get(u.id)?.lastConnectionStatus,
    upgradeTarget:l.get(u.id)?.upgradeTarget}))}:d}...}
```

行映射 `rl`（`out/host/index.js@24361`）：

```js
function rl(e){return{
  hosts:e.prepare("SELECT * FROM hosts WHERE host_id<>'local' AND target_json IS NOT NULL ORDER BY position,host_id").all()
    .map(t=>({id:String(t.host_id),membershipId:Lr.parse(t.membership_id),kind:t.kind,
      target:t.target_json==null?null:Fv.parse(JSON.parse(String(t.target_json))),position:Number(t.position),
      ...t.last_connection_status==null?{}:{lastConnectionStatus:String(t.last_connection_status)},
      ...t.last_connection_error==null?{}:{lastConnectionError:String(t.last_connection_error)},
      ...t.last_opened_at==null?{}:{lastOpenedAt:Number(t.last_opened_at)}})),
  entries:e.prepare("SELECT * FROM remote_project_entries ORDER BY rowid").all().map(VZ)}}
```

`VZ`（同文件 `@24361` 前文）：

```js
{id:String(entry_id),hostId:String(host_id),projectId:entry.remote_project_id==null?null:String(...),
 name:String(cached_name),roots:array({rootPath,position,isPrimary}),
 legacyWorkspace:legacy_workspace_json==null?null:JSON.parse(...),
 lastSyncedAt:...,...initialPosition?,...deleted?}
```

字段表：

| `hosts[]` | 类型 | 说明 |
|---|---|---|
| `id` | string | hostId，形如 `remote:server:<normalizedServerId>`（见 §5） |
| `membershipId` | string(min1) | 连接身份代次；重连/换身份会变，用于判 stale |
| `kind` | string | `ssh` / `wsl` / `docker` / `server` |
| `target` | object\|null | `Fv` 判别式 union；可能含 `tokenCredentialKey`、`serverId`、`url` 等（不含明文 token） |
| `position` | number | 排序 |
| `lastConnectionStatus` | string? | 底层状态原文 |
| `lastConnectionError` | string? | 上次连接错误 |
| `lastOpenedAt` | number? | 上次打开时间 |
| `connectionStatus` | `"online"`\|`"connecting"`\|`"offline"` | 运行时合成；缺省 `"offline"` |
| `lastConnectionError` / `lastConnectionStatus` / `upgradeTarget` | 运行时覆盖 | 来自 `readConnectionStates` |

`connectionStatus` 的取值来源（`out/host/index.js@2184215`）：

```js
let T=k.state==="online"?"online":["bootstrapping","retrying","updating"].includes(k.state)?"connecting":"offline";
```

| `entries[]` | 类型 | 说明 |
|---|---|---|
| `id` | string | entry id（缓存行 id） |
| `hostId` | string | 所属 host |
| `projectId` | string\|null | 远端项目 id；`null` = legacy 单根工作区 |
| `name` | string | 上次同步的名称 |
| `roots` | `{rootPath,position,isPrimary}[]` | 约束：非空且唯一、恰好一个 primary（`X_` refine） |
| `legacyWorkspace` | `{workspacePath,workspacePurpose,...}\|null` | 仅 legacy entry |
| `lastSyncedAt` | number\|null | 用于 `refreshEntry` 的 CAS |
| `initialPosition` | number? | 有值时表示待升级的 entry id |
| `deleted` | `true?` | 软删标记 |

注意：`controller===!1` 时返回空目录。窗口 Local Host 的 `fe` 是
`controller: !projectStorageHostId || projectStorageHostId==="local"`（`out/host/index.js@1884619` 前文），
所以**在窗口本地 host 上可用**；远端 source host 进程上会返回空。

### 4.2 `Mbt` 原文（web 的全量项目树算法）

`Reference_web/3.17.1/assets/index-Dwhx77cK.js@2072273`：

```js
async function Mbt(e){let t=await e.remoteProjectService?.readDirectory()??{hosts:[],entries:[]},
 [n,r]=await Promise.all([e.projectService.readTree({includeDeleted:!0,includeDeletedTasks:!0}),
   Promise.allSettled(t.hosts.map(t=>e.projectService.readTree({hostId:t.id,includeDeleted:!0,includeDeletedTasks:!0})))]),
 i=new Map,a=new Map;
 for(let e=0;e<r.length;e++){let n=r[e],o=t.hosts[e];
   n.status===`fulfilled`?i.set(o.id,n.value):o.connectionStatus===`online`&&a.set(o.id,String(n.reason))}
 for(let n of t.entries){let t=i.get(n.hostId),r=t&&_bt(n,t);
   r&&e.remoteProjectService&&await e.remoteProjectService.refreshEntry({entryId:n.id,expectedLastSyncedAt:n.lastSyncedAt,
     project:{projectId:r.id,name:r.name,roots:r.roots,deleted:r.deleted}})}
 let o=await e.remoteProjectService?.readDirectory()??t;
 for(let[e]of i){let n=t.hosts.find(t=>t.id===e);
   o.hosts.some(t=>t.id===e&&t.membershipId===n?.membershipId)||i.delete(e)}
 return{...vbt(n,o,i),directory:o,errors:a}}
```

步骤：

1. 先 `readDirectory()` 拿 hosts/entries（此后所有 host 都发 `readTree`，**不是只 online**）。
2. `Promise.allSettled`：本地 `readTree()` + 每 host `readTree({hostId})`。
   成功进 `i`（hostId→tree）；失败**仅当该 host `connectionStatus==="online"`** 才记入 `errors`。
3. 对每个 entry，用该 host 的 tree 匹配项目（`_bt`），命中则 `refreshEntry` 回写缓存
   （`{entryId, expectedLastSyncedAt, project:{projectId,name,roots,deleted}}`）。
4. 重新 `readDirectory()`；若 host 的 `membershipId` 变了或 host 消失，丢弃该 host 的 tree。
5. `vbt(localTree, directory2, hostTrees)` 合并。

`_bt`（`index@2067285`）：

```js
function _bt(e,t){let n=t.projects.find(t=>t.hostId===e.hostId&&t.id===e.projectId);if(n)return n;
 let r=e.projectId?.startsWith(`legacy-single-root:`)&&e.roots.length===1?e.roots[0].rootPath:
   e.projectId?void 0:e.legacyWorkspace?.workspacePath,
   i=t.projects.filter(t=>t.hostId===e.hostId&&!t.deleted&&r!==void 0&&t.roots.some(e=>e.rootPath===r));
 return i.length===1?i[0]:void 0}
```

### 4.3 `vbt` 合并原文

`index@2067661`：

```js
function vbt(e,t,n){let r=[...e.projects],i=[...e.tasks],a=[...e.deletedTasks??[]],
 o=new Map(e.projects.map(e=>[kP(e),{online:!0}])),s=new Set(t.hosts.map(e=>e.id));
 for(let e of t.entries){if(!s.has(e.hostId))continue;
   let t=n.get(e.hostId),i=t&&_bt(e,t),
     a=i??{id:e.projectId??`legacy:${e.id}`,hostId:e.hostId,name:e.name,roots:e.roots,pinned:!1,
       deleted:e.deleted===!0,nameOverridden:!1,position:r.length,createdAt:0,updatedAt:0},
     c=e.initialPosition==null?a:{...a,position:e.initialPosition},l=kP(c);
   o.has(l)||r.push(c),
   o.set(l,{entryId:e.id,...e.initialPosition==null?{}:{upgradeEntryId:e.id},online:!!i,...!i&&t?{unresolved:!0}:{}})}
 for(let[e,t]of n)if(s.has(e)){
   a.push(...(t.deletedTasks??[]).filter(t=>t.hostId===e));
   for(let n of t.tasks)n.hostId===e&&(n.projectId==null||o.get(kP({hostId:e,id:n.projectId}))?.online)&&i.push(n)}
 return{tree:{projects:r,tasks:i,...a.length?{deletedTasks:a}:{}},availability:o}}
```

合并规则（可直接照抄）：

- 项目键 `kP(project) = JSON.stringify([hostId, id])`。
- 以本地树为基底；entries 逐个补项目：host 必须在 directory.hosts 里；能匹配到 host tree 项目就用它，
  否则用 entry 合成（`id = entry.projectId ?? "legacy:"+entry.id`，pinned/deleted/时间置默认）。
- `availability[hostId::id] = {entryId, online: !!matchedTreeProject, unresolved?: hostTree存在但未匹配}`。
- 远端任务只从 `hostTrees` 追加，且 host 仍在 directory 里；`projectId==null` 或该项目 online 才保留。
- `deletedTasks` 按 host 追加。

### 4.4 host 侧 `readTree` 实现

`out/host/index.js@43115`：

```js
async readTree(s){let a=await t(s?.hostId);
  if(a)return a.readTree({includeDeleted:s?.includeDeleted,includeDeletedTasks:s?.includeDeletedTasks});
  let c=(await e.repository()).readTree({includeDeleted:s?.includeDeleted,includeDeletedTasks:s?.includeDeletedTasks});
  return{...c,projects:await rH(c.projects)}}
```

- `hostId` 缺省或等于 `ownerHostId`（窗口 host 上是 `"local"`）→ 本地 repository；
- 否则 `resolveHost(hostId)`（`iH` 构造，`out/host/index.js@1884619`；
  实现 `resolveProjectHostService` 在 `@2184969`），远端不可用抛 `project.host-offline`。

repository 返回（`out/host/chunk-LNTVTW5D.js@102497`）：

```js
readTree(e){let t=s(()=>({projects:Wr(this.db,e?.includeDeleted??!1).filter(n=>n.hostId===(this.ownerHostId??"local")),
  ...e?.includeDeletedTasks?{deletedTasks:...}:{},
  tasks:e?.includeTasks===!1?[]:ud(this.db).filter(n=>n.hostId===(this.ownerHostId??"local"))}),"read");
```

`Wr` 项目行（`chunk@98940`）：`{id,hostId,name,nameOverridden,position,pinned,deleted,createdAt,updatedAt,
roots:[{rootPath,position,isPrimary}]}`。

`ud` 任务行（`chunk@100574`）：`{...meta_json,taskId,traceId,workspacePath,workspaceIdentity?,title,
titleOverridden,createdAt,updatedAt,status,mode,pinned,archived,unreadAt?,projectId,hostId}`。

远端 wrapper `jv`（`out/host/index.js@46639`）给任务补 identity：

```js
async readTree(n){let r=await e.readTree(n),
 o=[...new Set([...r.projects.flatMap(a=>a.roots.map(c=>c.rootPath)),...r.tasks.map(a=>a.workspacePath)])],
 s=new Map(await Promise.all(o.map(async a=>[a,await t(a)])));
 return{...r,tasks:r.tasks.map(a=>({...a,workspaceIdentity:s.get(a.workspacePath)}))}}
```

### 4.5 Kotlin 合并规格

1. 选一条**本地 scope** 的桥（`bridge.kind==="local"`，通常来自 bootstrap）发目录请求：
   `channelCall(bridge,"remote-projects","readDirectory",[])`。
   若当前只有远端桥：`remote-projects` 在该桥上被 override 成窗口服务（`out/host/index.js@2161721`），
   `readDirectory` 仍可用；但 controller 取决于窗口 host，实测以返回为准。
2. 本地树：`channelCall(bridge,"project","readTree",[{includeDeleted:false,includeDeletedTasks:true}])`。
3. 对 `hosts.filter { connectionStatus=="online" }` 逐个
   `channelCall(bridge,"project","readTree",[{hostId:h.id,includeDeleted:false,includeDeletedTasks:true}])`，
   用 `allSettled` 语义（Kotlin `supervisorScope`/逐条 runCatching），记录 online host 的失败。
4. 再 `readDirectory()` 一次；`membershipId` 变化或 host 消失的结果丢弃。
5. 合并：
   - `projects`: key = `${hostId}\u0000${id}`；本地在前，远端 tree 命中的项目覆盖 entry 合成项；
   - entry 合成：`id = projectId ?: "legacy:${entry.id}"`，`roots` 用 entry.roots，
     `deleted = entry.deleted===true`，`pinned=false`；
   - legacy 匹配（`_bt`）：`entry.projectId` 为 `null` 时用 `entry.legacyWorkspace.workspacePath`；
     若 `entry.projectId` 以 `legacy-single-root:` 开头且恰有一个 root，则用该 `rootPath`；
     在 host 树里找「未删、hostId 相同、某个 rootPath 等于该路径」的项目，**唯一命中**才采用；
   - `tasks`: key = `${hostId}\u0000${identity?.trim() ?: workspacePath}\u0000${taskId}`；
     远端任务仅当 `projectId==null` 或其项目 `online` 时保留；`deletedTasks` 单独收集；
   - 同一 key 冲突：`updatedAt` 大者胜；`pinned/archived` 取 `true`（存在即 true）。
6. `hostId`/`projectId` 一律照抄对象字段，不从 key 字符串反解；本地 hostId 是字面量 `"local"`。

---

## 5. Q5：`workspaceIdentity` 与 `project.roots[].rootPath` 的对应

### 5.1 identity 构造规则

`out/host/chunk-ZSDMP7DM.js`（schema/共享 chunk）：

- `gy`（`@759647`）：

```js
function gy(t){return`/${t.replace(/\\/g,"/").replace(/\/+/g,"/").replace(/^\/+|\/+$/g,"")}`}
```

- `xse`（`@760307`）：

```js
function xse(t,n){let r=gy(t);switch(n.kind){
 case"ssh":return`remote:ssh:${n.host.trim().toLowerCase()}:${n.port??22}:${n.username.trim()}:${r}`;
 case"wsl":{let i=n.distro?.trim()||"default",o=n.user?.trim();
   return o?`remote:wsl:${i}:${o}:${r}`:`remote:wsl:${i}:${r}`}
 case"docker":return`remote:docker:${n.container}:${r}`;
 case"server":return oB({serverId:tB(n),workspacePath:r})}}
```

- `oB`（`@760176`）：`remote:server:${nB(serverId)}:${gy(workspacePath)}`，
  `nB` = 小写、非 `[a-z0-9._-]` 换 `-`、去首尾 `-`；`tB` = `serverId || name || new URL(url).host`。
- `_se`（`@761430`，桌面 host 里叫 `Wo`）：

```js
function _se(t,n){let r=`${t}:${gy(n)}`;if(rB(r)!==t)throw new Error("Invalid remote host identity");return r}
```

- `rB`（`@761308`）：`remoteHostIdFromWorkspaceIdentity` = identity 去掉末尾 `"/<path>"`。

**结论**：`workspaceIdentity = hostId + ":" + normalizePath(workspacePath)`；
`hostId = identity 去掉 "/<path>"`。对**可读的**远端项目，hostId 恒为
`remote:server:<normalizedSourceServerId>`（`resolveProjectHostService` 用
`Br({serverId:S, workspacePath:M})`，`out/host/index.js@2184969`）。`remote:ssh:*` 形态的 hostId
在 `readTree({hostId})` 里会因 `resolveProjectHostService` 匹配不到 sourceServerId 而抛
`project.host-offline`；ssh/wsl/docker 形态的 identity 只用于「工作区 tab」，不用于 remote project 树。

hostId 生成（`out/host/index.js@20661` / `@20847`）：

```js
function Nc(e,t){let n=t?Br({serverId:t,workspacePath:"/"}):vi("/",e),r=Jr(n);if(!r)throw new Error("remote-project.invalid-host-identity");return r}
function Wa(e){return Nc(e.target,e.sourceServerId)}
```

### 5.2 任务行的 identity 从哪来

- 本地 repository（`out/host/chunk-LNTVTW5D.js@100574`）：

```js
workspaceIdentity:e.workspace_identity===e.workspace_key?String(e.workspace_identity):
  ve(String(e.workspace_key))?String(e.workspace_key):void 0
```

  即：DB 里 `workspace_identity` 与 `workspace_key` 相同才用 identity；`workspace_key` 看起来像远端
  identity 才回退；否则 `undefined`。**本地任务通常没有 identity。**
- 远端 host 服务：`jv` 包装（`out/host/index.js@46639`），对 `task.workspacePath` 调用
  `VGe(services, path, ()=>Br({serverId,path}))`（`out/host/index.js@2153711` 的 `zv` 记忆化）。
  结果是确定性的 server identity：`remote:server:<normalizedServerId>:<gy(path)>`。
- `readTree` 的 `projects[].roots[].rootPath` **从不携带 identity**；它只是绝对路径。

### 5.3 与 `roots[].rootPath` 的对应关系

- 一个 task 的 `workspacePath` 通常等于其项目某个 root 的 `rootPath`（可能是非 primary root）。
- identity 是按该 task 的 `workspacePath` 现算的；因此：
  `task.workspaceIdentity === `${project.hostId}:${gy(task.workspacePath)}``（远端 server 形态）。
- 当 task 在 primary root 上时，identity 也等于 `${project.hostId}:${gy(primaryRoot.rootPath)}`；
  在 secondary root 上则不相等（路径段不同）。
- 多 root 项目：**不要用 primary root 的 key 去打开 secondary root 上的任务**；必须用 task 自己的
  `workspaceKey` + `taskHostId`。

### 5.4 Android `WorkspaceItem` 正确构造规则

| 来源 | key | path | identity | projectId | hostId | bridgeable |
|---|---|---|---|---|---|---|
| bootstrap/workspace-list 条目 | `workspaceIdentity?.trim()\|\|workspacePath` | `workspacePath` | 有则原样 | null | null | true |
| 本地项目任务行 | `workspacePath`（无 identity） | `workspacePath` | null | `task.projectId ?? null` | `"local"` | false |
| 远端项目任务行 | `task.workspaceIdentity?.trim()\|\|task.workspacePath`（**权威**） | `task.workspacePath` | task 行原样 | `task.projectId ?? null` | `task.hostId` | false |
| 本地项目、无任务 | `primaryRoot.rootPath` | 同 key | null | `project.id` | `"local"` | false |
| 远端项目、无任务 | `${project.hostId}:${gy(rootPath)}` | `rootPath` | 同 key | `project.id` | `project.hostId` | false |
| entry `projectId==null` 的 legacy 项目 | 同「远端项目、无任务」 | `roots[0].rootPath` | 推导 | null（合成 id `legacy:<entryId>` 仅用于分组） | `entry.hostId` | false |

补充规则：

1. 多 root 项目如果要按任务分组，**每个 root 一个 WorkspaceItem**（或项目一个、任务按 projectId
   分组，但打开时用 task 自己的 key/hostId）。
2. 远端 identity 推导只能用 `${hostId}:${gy(rootPath)}`；`gy` 必须做反斜杠替换、`//` 折叠、
   首尾 `/` 裁剪。不要直接 `${hostId}:${rootPath}`。
3. `hostId` 原样使用（`"local"` 或 `remote:server:*`）；不要从 `workspacePath` 反推。
4. 同一物理目录既出现在 bootstrap 又出现在项目树时，按 key 合并；`identity` 取非空值，
   `projectId/hostId` 取项目树值，`bridgeable` 取 bootstrap 值。
5. `projectId` 为 `null` 的 legacy entry 不要伪造成真实 id 去发 `project` intent；
   `prepareProjectWorkspace` 对本地会走 draft 路径，对远端会拒绝。

---

## 6. 与 Round1 冲突的结论

> 本节只列被本轮证据推翻或需要改写的断言。Round1 的 schema 表、`window-project` 仅 remote scope、
> bootstrap 来源（tab 列表）等结论本轮未发现反证，保留。

### 6.1 被推翻：RPC arg 是「单值」而非数组

- Round1 原文（`round1/01-workspace-list.md` §1 结论 5、§5.1 @259900、§7 第 1 条）：
  > 「RPC 帧格式为 `[type,id,channelName,methodName]` + `arg`……**必须是单个对象**……注意 Android 侧
  > 当前传的是 `JsonArray(listOf(obj))`……形状与协议不符」「不要再传 `JsonArray([...])`」。
- 实际：`arg` 是**位置参数数组**；web 客户端 `toService` 也是把 `(...args)` 打包成数组再发。
  `sendRequest` 的 `i` 就是这个数组。服务端 `fromService` 用 `apply(service, arg)` 展开。
- 证据：`index@262144`、`index@260243`、`index@263762`、`index@264050`；
  `out/host/chunk-ZDLGWCUS.js@9116/@9403/@12033`；`channel_client.rs:525-534`；
  `codec.rs:988-996`。
- 影响：Android 现有 `listPinnedTasks([arg])`/`readTree([obj])` **无需修改**；按 Round1 修改会全崩。

### 6.2 被推翻：`workspace-bridge-open` 只能桥接「已打开的 tab」

- Round1 原文（§1 结论 1 后半、§7 第 3 条）：
  > 「`workspace-bridge-open` 只接受落在 `getAvailableWorkspaces` 里的 key」「要在『当前桌面窗口未打开的
  > 本地项目』上开桥是不可能的（`目标工作区不在当前桌面窗口中`）」。
- 实际：带 `taskHostId`（任务）或 `project`（项目/草稿）时走 `prepareProjectWorkspace`，
  完全不查 `getAvailableWorkspaces`。本地项目/本地任务、远端项目/远端任务都可打开。
- 证据：`out/main/index.js@423608` 的 `$e`：`let O,Z=b.project??((b.fileTreeTaskId||b.taskId)&&b.taskHostId?{hostId:...}:void 0);
  if(Z){...prepareProjectWorkspace...}else O=I(h).find(...)`。
- 影响：Round1 §7 第 3 条的「本地非 tab 项目不可能」是错误结论；实际只需带 `project` intent。

### 6.3 被推翻：project 与 workspace「没有 id 级映射」

- Round1 原文（§1 结论 4、§3.5 末尾）：
  > 「project 到 workspace 没有 id 映射……`rootPath === workspacePath` 是唯一的『路径相等』关联方式」。
- 实际：任务行带 `projectId`（DB `project_id`），项目行带 `id` + `hostId`；
  `kP(project)=JSON.stringify([hostId,id])` 就是 id 级映射。`rootPath` 相等只是
  **legacy entry（`projectId==null`）** 的回退匹配（`_bt`）。
- 证据：`out/host/chunk-LNTVTW5D.js@100574`（`projectId:e.project_id==null?null:String(...)`）、
  `chunk@98940`（项目 `id/hostId`）、`index@2067285`（`_bt` 的 legacy 分支）、
  `index@2067661`（`kP` / `availability`）。

### 6.4 需改写：「只对 online host 发 readTree」

- Round1 原文（§7 第 2 条）：
  > 「只对 `connectionStatus==="online"` 的 host 发 `readTree({hostId})`」。
- 实际：web `Mbt` 对 **directory 里的全部 host** 发 `readTree`（`Promise.allSettled`）；
  只有 online host 的失败才记入 `errors`。合并阶段再按刷新后的 directory 剔除消失/换 membership 的 host。
- 证据：`index@2072273`。
- 影响：Android 只对 online 发是**性能优化**，不是协议要求；但若某 host 状态是 `connecting` 而实际可读，
  只发 online 会少拿数据。建议：全部发 + `allSettled`，或至少 online；错误只对 online 上报。

### 6.5 已解决（Round1 UNVERIFIED）

- **UNVERIFIED #5（hostId 与 identity 前缀是否同一套编码）**：已证实 `hostId = identity 去掉
  "/<path>"`；`Nc/Wa` 用 `Jr(identity)` 生成 hostId，`_se` 用 `hostId+":"+gy(path)` 反向拼。
  证据：`chunk-ZSDMP7DM@761308/@761430`、`out/host/index.js@20661/@20847`。
- **UNVERIFIED #6（readTree 任务的 `remoteSessionId` 产出点）**：`project` 服务的任务行
  （repository `ud` + `jv` + activity 装饰）都不产出 `remoteSessionId`。它是桥/工作区字段，
  不是任务字段；Android 不应依赖 `readTree.tasks[].remoteSessionId`。
  证据：`chunk-LNTVTW5D@100574`、`out/host/index.js@46639`、`@1903481`（activity 只加
  `pendingInteraction(s)`）。

### 6.6 坐标/引用层面的更正

- Round1 §5.1 把 `sendRequest` 标在 `index@259900 区`；实际 `sendRequest(e,t,n,r,i)` 在
  `index@262144`，`requestPromise` 在 `@260243`，`toService/fromService` 在 `@263762/@264050`。
- Round1 说 `Mbt` 在 `@2072406 区`；函数声明实际在 `@2072273`。
- Round1 §5.10 引用 `HomeViewModel.kt:509-516` 的 `window-project` 调用已过时：当前源码
  （审计时 mtime 16:35）用 `project` 通道，args 在 `:543-550`、调用在 `:552`；
  `zcode-task` 元数据调用已删除（pinned/archived 改为从 `readTree.tasks` 取，`:501-503`）。

---

## 7. Android 侧可直接落地的规格

### 7.1 刷新「全部项目」

```
bridge := 本地 bridge（bootstrap/workspace-list 里 kind=="local" 的第一条；否则任一桥）
localTree := channelCall(bridge, "project", "readTree",
                [{"includeDeleted": false, "includeDeletedTasks": true}])
dir1 := channelCall(bridge, "remote-projects", "readDirectory", [])
for h in dir1.hosts:
    if h.connectionStatus == "online":
        tree[h.id] := channelCall(bridge, "project", "readTree",
                         [{"hostId": h.id, "includeDeleted": false, "includeDeletedTasks": true}])
        // 失败仅对 online host 记 error；连接态为 connecting 时可选择也发
dir2 := channelCall(bridge, "remote-projects", "readDirectory", [])
// 丢弃 membershipId 变化或 host 消失的 tree
projects := merge(localTree.projects, dir2.entries, tree)
tasks    := merge(localTree.tasks, tree[h].tasks)
```

- `readTree`/`readDirectory` 都要求对话握手已完成（`initializeConversationV4` 应答已到）；
  放在 `subscribeSessionsIndex` ack 之后最稳（现有 `ensurePreviews` 已如此）。
- `readTree` 的参数永远包在数组里（§1）。

### 7.2 打开任务（非 tab 项目）

```
engine.openBridge(
  workspaceKey = task.workspaceIdentity?.trim() ?: task.workspacePath,
  taskId       = task.taskId,
  taskHostId   = task.hostId,          // 本地任务也必须传 "local"
  project      = null
)
```

### 7.3 在项目里新建对话（非 tab 项目）

```
engine.openBridge(
  workspaceKey = "project-navigation",  // 只作占位/日志；服务端不用于查找
  taskId       = null,
  taskHostId   = null,
  project      = {"projectId": project.id, "hostId": project.hostId}  // hostId 可省（本地）
)
```

本地未归类草稿（不属于任何项目）：

```
project = {"projectId": null, "draftId": "<uuid>", "reuseDraft": false}
```

### 7.4 桥打开后取权威值

```
session := engine.openBridge(...)
snap := engine.snapshot()
b := snap.bridges.find { it.session == session }
readyKey := b.workspaceKey ?: requestedKey
readyTask := b.initialTaskId
// 需要 workspacePath / identity 时：当前 snapshot 不提供，必须扩展 Rust
//（建议把 ready bridge map 的 workspacePath/workspaceIdentity/kind 投影出来）
```

- 后续 `sendMobileViewState`、`conversation_open.scope.workspacePath`、任务订阅都以 ready 值为准。
- 草稿场景没有已知路径，必须依赖 ready 的 `workspacePath`；远端必须依赖 ready 的
  `workspaceIdentity`（scope 里两个字段都要）。

### 7.5 不要做的事

- 不要给 `channel_call.args` 去掉外层数组。
- 不要用项目主根 key 去打开 secondary root 上的任务。
- 不要用 `roots[].rootPath` 当远端 identity。
- 不要对 `projectId==null` 的 legacy entry 发 `project` intent。
- 不要在 `project` intent 里同时带 `taskId`/`fileTreeTaskId`（schema 与服务端双拒）。
- 不要在 `workspaceKey` 里传裸 `"local"` 或项目 id；它只接受工作区 key 或 intent 占位串。

---

## 8. 残余 UNVERIFIED

1. **3.16.1 桌面端是否已有 `prepareProjectWorkspace`**：本机只有 3.17.1 asar；无法逐字对比。
   若用户桌面端低于 3.17.1，`project`/`taskHostId` 导航可能返回 `Project navigation is unavailable.`。
   **UNVERIFIED**。
2. **`snapshot.bridges[].workspacePath/workspaceIdentity`**：Rust 未投影；是否需要扩展由宿主决定。
   本文只证明 ready bridge map 里有这些字段（`zemote_client.rs:2127-2130` 保存整包）。
3. **`connectionStatus==="connecting"` 的 host 是否可成功 `readTree`**：`resolveProjectHostService`
   只接受 online source connection；connecting 大概率失败，但未实测。**UNVERIFIED**。
4. **远端多 root 项目的 `prepareSession`**：远端项目强制单根（`project.remote-single-root-required`），
   所以远端不会出现多 root；本地多 root 的 secondary root 任务导航未在真机验证。
   **UNVERIFIED**。
5. **`project.readTree({hostId})` 在「只有远端桥」的会话里是否总能拿到窗口本地目录**：
   代码上 `project`/`remote-projects` 在 remote scope 被 override 成窗口服务
   （`out/host/index.js@2161721`），但依赖 `windowServices`（Gt）存在；若 host 未提供会先抛
   `window-project-directory.unavailable`。**UNVERIFIED**（需真机）。
6. **`bindWorkspaceIdentity` 的副作用**：代码忽略其返回值、固定用 `Br(...)`；若未来版本改为采用
   返回值，identity 可能变化。3.17.1 现状已证。**UNVERIFIED（跨版本）**。

---

## 附录：证据索引

### A.1 桌面 `out/main/index.js`

| 偏移 | 片段/主题 |
|---|---|
| 422530 | `function Fe(h,b){...}` `createWorkspaceBridge`：intentKey、preparingProject、`Workspace bridge identity cannot change intent.` |
| 423608 | `function $e(h,b,x){...}` `buildWorkspaceBridge`：intent 选择、`Project navigation is unavailable.`、prepare 后 `O=...`、`Q=ot(O)`、`J={...workspaceKey:Q...}` |
| 427580 | `workspace-bridge-ready` 载荷 |
| 654060 | `function It(A,_,W,I)` `acceptPreparedProjectWorkspace`：local 直接 resolve；remote 注册 mobileOnly session 并等 providerProvisioningReady |
| 655674 | `I.data.type===K.ProjectWorkspacePrepared` 分发 |
| 667114 | `function Te(A,_,W=yt())`：post `PrepareProjectWorkspace` 到窗口 host |
| 837062 | `prepareProjectWorkspace:s(async(e,t,r)=>{...Ct.prepareProjectWorkspace...})`（web-remote-control 配置，无条件注入） |

### A.2 桌面 `out/host/index.js`

| 偏移 | 片段/主题 |
|---|---|
| 20661 / 20847 | `Nc` / `Wa`：hostId = `Jr(identity)` |
| 24361 | `function rl(e)`：hosts/entries 行映射 |
| 25068 / 25413 | `function Dm(e)` / `async readDirectory(){if(e.controller===!1)...}` |
| 38889 | 旧版 `prepareSession`（legacy single-root） |
| 42116 | `function iH(e)` createProjectService；`route`/`readDirectory`/`readProjects` |
| 43115 | `async readTree(s){let a=await t(s?.hostId)...}` |
| 44915 | `async resolveSessionDirectories(s,a){let c=await t(a)...}` |
| 45062 | 新版 `prepareSession`（draft/主根/远端转发） |
| 46639 | `function jv(e,t)` `bindRemoteProjectIdentities` |
| 668897 | `async listPinnedTasks(C){...}`（单对象参数） |
| 1689766 | `exposeOnChannelServer(t,n=new Map,r)`（allowedChannels + overrides） |
| 1884619 | `be=iH({...resolveHost:e?.resolveProjectHostService...})`、`fe=Dm({...controller:...})` |
| 1903481 | `function c1e(e,t)`（activity 只加 pendingInteraction(s)） |
| 1958013 | `function wie(e)`（remote scope 注册 window-project 等） |
| 2153711 | `VGe=zv()` 身份记忆化 |
| 2161721 | `function XGe(e,t,n,r="desktop-continuous",o={kind:"local"},...)` `exposeServicesOnMessagePort`；remote scope 覆盖 `project`/`remote-projects` |
| 2168091 | `if(n.type===an.PrepareProjectWorkspace)` host 侧 prepare handler |
| 2168202 / 2168555 | 回投 `ProjectWorkspacePrepared` / `DM(...).prepare({projectId,...})` |
| 2184215 | `readConnectionStates:i(async()=>{...})`：online/connecting/offline |
| 2184969 | `resolveProjectHostService:i(async v=>{...jv(...Br({serverId,...}))...})` |

### A.3 桌面 host chunks

| 文件@偏移 | 片段/主题 |
|---|---|
| `chunk-ZDLGWCUS.js@9116` | `onRawMessage`：`case 100:this.onPromise({...arg:y(t)})` |
| `chunk-ZDLGWCUS.js@9403` | `onPromise`：`t.call(this.ctx,e.name,e.arg,s.token)` |
| `chunk-ZDLGWCUS.js@12033` | `requestPromise(e,t,s,r=U.None){...this.sendRequest(100,i,e,t,s)}` |
| `chunk-ZDLGWCUS.js@17630` / `@17935` | `t.fromService=n`（`call` 内 `i.apply(n,r\|\|[])`）/ `t.toService=e`（`async(...d)=>{...h=d;return s.call(o,h)}`） |
| `chunk-LNTVTW5D.js@98940` | `function Wr(r,e=!0)` 项目行 |
| `chunk-LNTVTW5D.js@99978` | `function fi(r,e,t="local")` 任务目录解析 |
| `chunk-LNTVTW5D.js@100574` | `function ud(r)` 任务行（含 projectId/hostId/workspaceIdentity） |
| `chunk-LNTVTW5D.js@102497` | `readTree(e){...}` repository |
| `chunk-ZSDMP7DM.js@759647` | `function gy(t)` 路径归一化 |
| `chunk-ZSDMP7DM.js@760176` | `function oB(t)` server identity |
| `chunk-ZSDMP7DM.js@760307` | `function xse(t,n)` 全 kind identity |
| `chunk-ZSDMP7DM.js@760725` | `function yc(t)` 解析 identity |
| `chunk-ZSDMP7DM.js@761308` | `function rB(t)` hostId from identity |
| `chunk-ZSDMP7DM.js@761430` | `function _se(t,n)` `hostId:gy(path)` |

### A.4 Web `index-Dwhx77cK.js`

| 偏移 | 片段/主题 |
|---|---|
| 260048 | `getChannel(e){return{call:(t,n,r)=>...}}` |
| 260243 | `requestPromise(e,t,n,r=Wle.None){...sendRequest(Du.Promise,i,e,t,n)}` |
| 262144 | `sendRequest(e,t,n,r,i){...Tu(a,[e,t,n,r]),Tu(a,i)...}` |
| 263762 | `e.fromService=t`：`call(e,t,r){...i.apply(n,r||[])}` |
| 264050 | `e.toService=n`：`async(...n)=>{...return e.call(r,i)}` |
| 2067285 | `function _bt(e,t)` entry→project 匹配 |
| 2067661 | `function vbt(e,t,n)` 树+目录+host 树合并 |
| 2072273 | `function Mbt(e)` 全量读取算法 |
| 2100600 | `function dxt(e=!1)` 项目树 hook |
| 2349364 | `switchWorkspace(m,{taskId,...hostId?{taskHostId:hostId}:{},...})` |
| 2353507 | `switchWorkspace(o,{taskHostId:t.hostId,fileTreeTaskId:t.taskId,...})` |
| 6974987 | `ce=async(e,t)=>{...workspace-bridge-open...}` |
| 6978369 | `ue=async e=>{...project:{projectId,...draftId?...}...}` `openProjectDraft` |

### A.5 Web `validation-B0HfQ5gr.js`

| 偏移 | 片段 |
|---|---|
| 319333 | `kT=A("kind",[{...kind:"local"...},{...kind:"remote"...workspaceIdentity:Q,remoteSessionId:Q,capabilities:...}])` |
| 322465 | `workspace-bridge-open` schema + refine |

### A.6 Rust / Kotlin

| 位置 | 片段/主题 |
|---|---|
| `protocol/src/channel_client.rs:392-406` | `call(channel, method, args: Vec<Value>, timeout)` |
| `protocol/src/channel_client.rs:525-534` | `send_call` → `Value::Array(args.to_vec())` |
| `protocol/src/zemote_client.rs:1359-1419` | `open_bridge` / `open_bridge_with_intent` |
| `protocol/src/zemote_client.rs:2101-2180` | `finish_open_bridge`：保存 ready bridge、取 `bridge.workspaceKey` |
| `protocol/src/zemote_client.rs:2458-2483` | `open_bridge_request`（含 taskHostId/project，无 fileTreeTaskId） |
| `protocol/src/zemote_client.rs:563-566` | `workspace_key()` 读 ready map |
| `ffi/src/codec.rs:941-950` | `open_bridge` 命令字段 |
| `ffi/src/codec.rs:988-996` | `channel_call`：`args: optional_value_array` |
| `ffi/src/session.rs:167-183` | snapshot.bridges 投影字段 |
| `ui/EnginePort.kt:187-193` | `channelCall(args: JsonArray)` KDoc「位置参数数组」 |
| `ui/EnginePort.kt:402` | `_bridgeWorkspaceKey.value = workspaceKey`（请求值，非 ready 值） |
| `ui/chat/ConversationRegistry.kt:78-103` | `ensure` 以请求 key 建桥/缓存 Handle |
| `ui/home/HomeViewModel.kt:543-552` | `project.readTree` 调用（`JsonArray(listOf(obj))`） |
| `ui/home/HomeViewModel.kt:557-590` | identityByPath + WorkspaceItem 构造 |
| `ui/chat/ChatViewModel.kt:792-810` | `ensureRouteBridge` / `projectIntent()` |

---

## 9. 一句话交付

**Wire 层 `args` 就是数组（Android 现状正确）；导航层用 `taskHostId`/`project` 两个 intent 打开
非 tab 项目；权威工作区值只认 `workspace-bridge-ready.bridge`；远端项目必须
`readDirectory + 逐 host readTree` 并按 `hostId/projectId/identity` 三元组合并；identity 是
`${hostId}:${normalizePath(path)}`，只能从任务行或该公式取，不能拿 rootPath 当 identity。**
