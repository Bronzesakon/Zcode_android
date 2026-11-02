# 工作区列表在 3.17.1 的真实行为（只读静态审计）

审计对象（只读，未执行任何快照代码）：

- 旧版本网页快照：`E:\Zcode_android\Reference_web\3.16.1`（`assets/index-DbEHT6Gg.js` 6,836,503 B；`assets/validation-BBf1FA1a.js`；`assets/src-*.js`）
- 新版本网页快照：`E:\Zcode_android\Reference_web\3.17.1`（`assets/index-Dwhx77cK.js` 6,986,617 B；`assets/validation-B0HfQ5gr.js`；`assets/src-BSClmRM9.js`）
- 桌面端 asar 旁证（3.17.1 同发布）：`C:\Users\wdn32\AppData\Local\Programs\ZCode\resources\app.asar`，经 `node E:\Zcode_android\tools\asar.mjs cat/dump/grep` 读取，未执行

坐标约定：`文件 @UTF-8字节偏移`，偏移取自 `ripgrep --byte-offset` / `asar.mjs grep @offset`，与 UTF-8 字节一一对应。所有引用原文均为单行压缩代码的片段，反引号按原样保留。

---

## 1. 结论摘要

**1）3.17.1 的 `bootstrap-response.workspaces` 不来自项目数据库，来自「桌面端该窗口当前打开的标签页」。**

数据通路是：

```
renderer 的 tab 列表
  → t4n() 的 useEffect                      (out/renderer/assets/styles-C9n_FfEX.js @6595127)
  → platform.syncWebRemoteControlWorkspaces(workspaces)   (renderer IPC 封装 @6595437 附近)
  → main: syncWebRemoteControlWorkspaces(u,m)             (out/main/index.js @860708)
  → Bt.syncAvailableWorkspaces(u, m) → a.set(windowId, workspaces)  (@440481)
  → getAvailableWorkspaces(h) = a.get(windowId) ∪ {当前 runtime target}   (@420762)
  → buildBootstrapResult(U) / buildWorkspaceListResult(H)  (@421167 / @421432)
```

`getAvailableWorkspaces` 的完整原文（`out/main/index.js @420762`）：

```js
function I(h){let b=new Map;for(let O of a.get(h.windowId)??[])b.set(ot(O),O);let x=_(h);if(kp(x)){let O=ot(x);b.has(O)||b.set(O,x)}return[...b.values()]}s(I,"getAvailableWorkspaces");
```

- `a` 是 `new Map()`，按 `windowId` 存「渲染器同步上来的 workspace 数组」；**唯一写入方**是 `syncAvailableWorkspaces(h,b){a.set(h,b);…}`（@440481）、启动恢复 `restorePreviouslyEnabled`（@439080）、远端会话替换 `replaceRemoteSession`（@440798，仅原地改 `remoteSessionId`/`connectionState`）；`disposeWindow` 删除（@448399 附近是另一个 `a`，属别的闭包，已核对）。
- 所以答案：**不是「workspace-bridge-open 过的工作区」，而是「该窗口当前打开的 workspace 标签页（tab）」+ 当前 runtime target。** 没有 `workspace-bridge-open` 也能出现在列表里（例如桌面端侧栏开了一堆 tab 但手机从没开过桥）。
- 但反向有硬约束：**`workspace-bridge-open` 只接受落在 `getAvailableWorkspaces` 里的 key**。原文（`out/main/index.js @427491` 内，`buildWorkspaceBridge`）：

```js
else O=I(h).find(ie=>ot(ie)===b.workspaceKey);if(t.get(h.windowId)!==h||h.bridgeOpen!==x)throw new Error("Workspace bridge request was superseded.");if(!O)throw new Error("\u76EE\u6807\u5DE5\u4F5C\u533A\u4E0D\u5728\u5F53\u524D\u684C\u9762\u7A97\u53E3\u4E2D\uFF0C\u65E0\u6CD5\u521B\u5EFA Web \u8FDC\u7A0B\u63A7\u5236 bridge\u3002");
```

（中文：`目标工作区不在当前桌面窗口中，无法创建 Web 远程控制 bridge。`）

渲染侧的 tab→workspace 映射有两个分支，**由桌面 featureGate 决定**（`out/renderer/assets/styles-C9n_FfEX.js @6594170`）：

```js
function Q2n({tabs:e,reconnectingRemoteWorkspaceKeys:t=[],remoteWorkspaceErrorByWorkspaceKey:n={}}){let r=new Set(t);return e.filter(jT).map(e=>{let t=!!(e.workspaceIdentity||e.remoteTarget||e.remoteSessionId),i=e.workspaceIdentity?.trim()||e.workspacePath,a=n[i]?.trim();return{workspacePath:e.workspacePath,label:e.label,kind:t?`remote`:`local`,...t?{connectionState:r.has(i)?`reconnecting`:e.remoteSessionId?`connected`:`disconnected`}:{},…}})}

function $2n({tabs:e}){return e.filter(jT).filter(e=>e.remoteSessionId&&e.workspaceIdentity).map(e=>({workspacePath:e.workspacePath,label:e.label,kind:`remote`,connectionState:`connected`,workspaceIdentity:e.workspaceIdentity,remoteSessionId:e.remoteSessionId}))}

function t4n({platform:e,tabs:t,isDesktop:n,featureEnabled:r,hasCompletedFullTabRestore:i,…}){(0,Q.useEffect)(()=>{if(!n||!i)return;let s=r?Q2n({tabs:t,…}):$2n({tabs:t});e.syncWebRemoteControlWorkspaces?.(s)},[…])}
```

- `featureEnabled === true` → `Q2n`：**该窗口全部 workspace tab（local + remote）**。
- `featureEnabled === false` → `$2n`：**只同步 remote workspace tab**（必须有 `remoteSessionId && workspaceIdentity`）。这条是手机端「看不到本地项目」的另一个入口级原因。
- `jT(e){return e.kind==='workspace'}`（`styles-C9n_FfEX.js @1186117` 附近）只排除 `settings` tab。

**2）「桌面侧栏全部项目」不是 `workspace-list-*` 能给的；正确通道是 `project.readTree`（channel `project`），不是 `window-project.readTree`。**

- 手机/网页端项目树的 hook 是 `dxt`（3.17.1 `index-Dwhx77cK.js @2100634`），关键两行：

```js
let t=ME(),n=t.windowProjectDirectory??t,r=n.projectService,i=n.remoteProjectService,…
let t=await Mbt({projectService:r,remoteProjectService:i});
```

即：**有 `windowProjectDirectory` 就用它，否则回退到 `services.projectService`（channel `project`）**。桌面渲染器走的是 `desktop-continuous` 模式，而 `window-project` 只在 `web-remote-replayable` 下注册，所以桌面侧栏本身用的就是 `project`。

- `Mbt`（`index-Dwhx77cK.js @2072406` 附近）的实际读法：

```js
let[n,r]=await Promise.all([e.projectService.readTree({includeDeleted:!0,includeDeletedTasks:!0}),Promise.allSettled(t.hosts.map(t=>e.projectService.readTree({hostId:t.id,includeDeleted:!0,includeDeletedTasks:!0})))]),
```

即：本地一次 `readTree()` + 每个在线远端 host 一次 `readTree({hostId})`；远端 host 列表来自 `remoteProjectService.readDirectory()`（channel `remote-projects`）。

- 通道名常量（3.17.1 web `index-Dwhx77cK.js @1254500` 附近 / 桌面 `out/host/chunk-ZSDMP7DM.js @770488`）：

| 别名 | enum | 线上 channel 名 |
|---|---|---|
| `w7e` = `IE(Dr.Project)` | `ke.Project` | `project` |
| `T7e` = `IE(Dr.RemoteProjects)` | `ke.RemoteProjects` | `remote-projects` |
| `F5e` = `IE(Dr.WindowProject)` | `ke.WindowProject` | `window-project` |
| `I5e` = `IE(Dr.WindowRemoteProjects)` | `ke.WindowRemoteProjects` | `window-remote-projects` |
| `L5e` = `IE(Dr.WindowProjectChanges)` | `ke.WindowProjectChanges` | `window-project-changes` |

- 网页客户端**确实会**调用 `window-project.readTree`——但**仅当** `bridge.kind === 'remote' && bridge.capabilities.windowProjectDirectory === true`（见结论 5）。默认（本地工作区）它调的是 `project.readTree`。
- 手机 Web 远程端可用性：`project` / `remote-projects` **始终可用**（`out/host/index.js @1956766` 的 `allowedChannels` 白名单含 `ke.Project`、`ke.RemoteProjects`；且 `unr` 构造函数无条件创建 `projectService`/`remoteProjectService`，`index-Dwhx77cK.js @6842282`）。`window-project` 只在远程 attachment scope 下存在（见结论 5）。

**3）`workspace-list-request` / `workspace-list-updated` 在 3.17.1 的字段与 3.16.1 完全一致；没有新增「全部项目」能力，也没有新增 `workspaceId` 映射。**

- `workspace-list-request`：`{ zcode_type:"workspace-list-request", requestId }`（3.17.1 `index-Dwhx77cK.js @6974257`；3.16.1 `index-DbEHT6Gg.js @6824123`，逐字相同）。
- `workspace-list-response`：`{ zcode_type, requestId, success:true, result:I }`，`I = { workspaces: WorkspaceEntry[], activeWorkspaceKey?, activeTaskId? }`。
- `workspace-list-updated`（服务端推送，无 requestId）：`{ zcode_type:"workspace-list-updated", result:I }`。
- `WorkspaceEntry` 里**没有** `workspaceId`、`projectId`、`hostId`、`roots`、`pinned`、`archived` 字段。只有 `workspacePath` / `workspaceIdentity?` / `remoteSessionId?` / `label` / `workspacePurpose?` / `kind` / `connectionState?` / `lastConnectionError?`。3.16.1 与 3.17.1 这个 schema 逐字段相同（见 §4 diff 表）。

**4）`readTree` 的形状与 project→workspace 键规则。**

- 参数（`{hostId?, includeDeleted?, includeDeletedTasks?}`，`hostId` 省略或 `'local'` 表示本机）：

```js
async readTree(s){let a=await t(s?.hostId);if(a)return a.readTree({includeDeleted:s?.includeDeleted,includeDeletedTasks:s?.includeDeletedTasks});let c=(await e.repository()).readTree({includeDeleted:s?.includeDeleted,includeDeletedTasks:s?.includeDeletedTasks});return{...c,projects:await rH(c.projects)}}   // out/host/index.js @42796
```

- 返回 `{ projects: Project[], tasks: Task[], legacySingleRoot?: true }`（本地路径的 `projects` 额外被 `rH` 补 `primaryRootStatus`）。
- 键规则：**`workspaceKey = workspaceIdentity?.trim() || workspacePath`**（3.17.1 `src-BSClmRM9.js @52705`：`function G(e){return e.workspaceIdentity?.trim()||e.workspacePath}`，该函数以 `G as E` 导出，被 `index-Dwhx77cK.js` 以 `E as gr` 引入；桌面 main 里的 `ot()` 是同一规则，见 `attachRemoteWorkspaceSessionHost` 的 `workspaceKey:w`，`w = workspaceIdentity?.trim() || workspacePath`）。
- **project 到 workspace 没有 id 映射**：project 的关联靠在 `task` 上，`task.projectId` + `task.workspacePath`/`workspaceIdentity`；project 自身只有 `roots[].rootPath`（可与 workspacePath 相等）和 `hostId`。`rootPath === workspacePath` 是唯一的「路径相等」关联方式（legacy 单根服务里就是 `c.find(k=>k.roots[0]?.rootPath===u)?.id`）。

**5）已知现象「Android 调 `window-project.readTree` 无应答」——源码可证：该通道在本地工作区的 mobile remote bridge 上根本没有注册。**

三条独立证据：

(a) Web 客户端不建该服务（`index-Dwhx77cK.js @6976130`）：

```js
let p=await Iir({relayProtocol:l,windowProjectDirectory:s.bridge.kind===`remote`&&s.bridge.capabilities?.windowProjectDirectory===!0,…})
```

`unr` 构造函数（`@6841987`）：`t.windowProjectDirectory&&(this.windowProjectDirectory={projectService:…F5e, remoteProjectService:…I5e, changesService:…L5e})`。

(b) 桌面端只在 `kind==='remote'` 的 bridge 上声明该 capability（`out/main/index.js @421168` 之前的 `toExternalBridge`）：

```js
if(e.kind==="remote"){…return{...t,kind:"remote",workspaceIdentity:e.workspaceIdentity,remoteSessionId:e.remoteSessionId,capabilities:{windowProjectDirectory:!0}}}return{...t,kind:"local"}
```

schema 侧同样互斥（`out/host/chunk-ZSDMP7DM.js @约726600` 的 `BL` discriminated union）：`local` 分支**没有** `capabilities` 字段，`remote` 分支才有 `capabilities:{windowProjectDirectory?: true}`。

(c) 服务端根本不注册该 channel。`attachLocalHost` 写死 `scope:{kind:"local"}`（`out/main/index.js @672575`）：

```js
w.postMessage({type:V.AttachServicePort,…clientMode:"web-remote-replayable",…scope:{kind:"local"}},[y])
```

而注册逻辑在 `out/host/index.js @1958312`：

```js
function wie(e){if(e.clientMode==="web-remote-replayable"&&e.attachmentScope?.kind==="remote"){if(!e.windowServices)throw new Error("window-project-directory.unavailable");new Mi().register(Hy,(e.decorateWindowProjectService??(n=>n))(e.windowServices.get(zr))).register(qy,e.windowServices.get(lr)).register(Ky,PF(e.windowServices.get(Jo))).exposeOnChannelServer(e.server,void 0,yie(e.clientMode))} … }
```

其中 `zr=De(ke.Project)`、`lr=De(ke.RemoteProjects)`、`Hy=De(ke.WindowProject)`、`qy=De(ke.WindowRemoteProjects)`、`Ky=De(ke.WindowProjectChanges)`（`out/host/index.js @36765 / @18872 / @1903069 / @1903093 / @1903124`）。也就是说 **`window-project` 就是把窗口作用域的 `project` 服务换个 channel 名再注册一次**，且只在 remote scope 下。

- 参数：**必须是单个对象**，`{hostId?}`（另有 `includeDeleted?`、`includeDeletedTasks?`）；不是数组、不是 `{params:[…]}`。RPC 帧格式为 `[type, id, channelName, methodName]` + `arg`（`index-Dwhx77cK.js @约259900` 的 `sendRequest`：`Tu(a,[e,t,n,r]);Tu(a,i)`）。注意 Android 侧当前传的是 `JsonArray(listOf(obj))`（`HomeViewModel.kt:509-516`），形状与协议不符；因 `readTree` 服务端不做 zod 校验（`iH.readTree` 只读 `s?.hostId`），数组入参不会报错但会退化为「本地」，属于隐性 bug，见 §6。
- 分片/ack：**需要**。`workspace-bridge-open` 之后的业务 RPC 走 `remote.rpc-frame` / `rpc-frame-ack` 分帧 + 重放（`Cnr`/`Snr` 一层：`replayBufferMaxBytes=2*maxMessageBytes`、`replayBufferGraceMs=45e3`、`assemblyTimeoutMs`），由 relay 协议层自动处理；调用方不需要自己 ack，但**大响应会被拆成多帧**，`remote.rpcFrame.envelopeTooLarge` 是唯一超限报错。
- 「无应答」的机制（源码可证其**不是静默**）：`ChannelServer.onPromise` 对未注册 channel 走 `collectPendingRequest`（`out/host/chunk-ZDLGWCUS.js @8853`）：

```js
onPromise(e){let t=this.channels.get(e.channelName);if(!t){this.collectPendingRequest(e);return} …}
collectPendingRequest(e){…let s=setTimeout(()=>{console.error(`Unknown channel: ${e.channelName}`),e.type===100&&this.sendResponse({id:e.id,data:{name:"Unknown channel",message:`Channel name '${e.channelName}' timed out after ${this.timeoutDelay}ms`,stack:void 0},type:202})},this.timeoutDelay);t.push({request:e,timer:s})}
```

`this.timeoutDelay` 在 `exposeServicesOnMessagePort` 里是 **1000**（`out/host/index.js @2162032`：`let f=new Av(p,"host",1e3,n)`），所以正常应在 ~1 s 后收到 `PromiseError: Channel name 'window-project' timed out after 1000ms`。客户端若把这条错误吞掉（Android 的 `runCatching{…}.getOrNull()`，`HomeViewModel.kt:517-519`），表现就是「无应答」。若连错误帧都收不到，则问题在 relay 分帧/降级层，需另行取证（见 UNVERIFIED）。

---

## 2. 时序（手机 Web 远程端，3.17.1）

```
[桌面 renderer]  tabs 变化 / 重连状态变化 / 错误变化 / 首次恢复完成
   └─ t4n.useEffect → Q2n(tabs) 或 $2n(tabs)                styles-C9n_FfEX.js @6595127
        └─ platform.syncWebRemoteControlWorkspaces(list)
             └─ [main] syncWebRemoteControlWorkspaces → syncAvailableWorkspaces → a.set(windowId,list)   main @860708 / @440481
                  └─ pushWorkspaceListUpdated(window)  // 仅当签名变化                       main @421847

[手机] bootstrap-request {requestId, terminalClientSessionId}      index @6973674
   └─ [main] bootstrap-response.result = { windowControlSessionId, desktopAppVersion?,
                                           workspaces:getAvailableWorkspaces(), initialViewState?,
                                           mobileViewState?, mobileTaskViewEpoch? }             main @421167
        └─ [手机] hir(result,{preferredWorkspaceKey}) 选一个可桥接工作区                      index @6916729
             │    canBridge = entry.kind!=='remote' || (entry.workspaceIdentity && entry.remoteSessionId)
             └─ workspace-bridge-open {bridgeSessionId, bridgeGeneration, recoveryId?, workspaceKey,
                                       taskId?, taskHostId?, fileTreeTaskId?, project?}
                  └─ [main] getAvailableWorkspaces().find(w => workspaceKey(w)===workspaceKey) 必须命中
                       ├─ kind local  → attachLocalHost, scope={kind:"local"}          main @672575
                       └─ kind remote → attachRemoteWorkspaceSessionHost, scope={kind:"remote",remoteSessionId,…}  main @666764
                            └─ [host] exposeServicesOnMessagePort                                host @2162032
                                 └─ wie(): 仅 scope.kind==="remote" 才注册
                                            window-project / window-remote-projects / window-project-changes
                  └─ workspace-bridge-ready { bridge, … }   bridge.kind='local' 时无 capabilities    main @427580
                       └─ [手机] Iir(): windowProjectDirectory = (kind==='remote' && capabilities.windowProjectDirectory)
                            └─ 若启用：window-project-changes.getCapabilities() 必须 version===1

[手机] workspace-list-request {requestId}  → workspace-list-response {workspaces,activeWorkspaceKey?,activeTaskId?}
[main] workspace-list-updated {result}  （推送；无 requestId）
[手机] workspace-switcher 用上面两个；项目树用 project.readTree（channel "project"），
       远端 host 列表用 remote-projects.readDirectory()，逐 host project.readTree({hostId})
```

---

## 3. 逐字段表

### 3.1 `WorkspaceEntry`（bootstrap.workspaces[] 与 workspace-list.result.workspaces[] 共用）

3.17.1 定义：`validation-B0HfQ5gr.js @318740`（`DT`）；桌面镜像：`out/host/chunk-ZSDMP7DM.js @725873`（`ZL`）。生产方：`Q2n`/`$2n`（renderer）+ `getAvailableWorkspaces` 的当前 target（main 的 `_()`）。

| 字段 | 类型 | 必填 | 来源 / 语义 |
|---|---|---|---|
| `workspacePath` | string(min1,trim) | 是 | tab 的 `workspacePath`；远程工作区是远端绝对路径 |
| `workspaceIdentity` | string(min1,trim) | 否 | 远端身份串，形如 `remote:<kind>:<segments…>:<absPath>`（`src-BSClmRM9.js @9957` `Ge='remote:'`；`@9970` `Ke={ssh:3,wsl:1,docker:1,server:1}`） |
| `remoteSessionId` | string(min1,trim) | 否 | 源服务器 logical session id |
| `label` | string(min1,trim) | 是 | UI 名，main 兜底为 `basename(workspacePath)`（`getPathLabel`） |
| `workspacePurpose` | `"project"` \| `"conversation"` | 否 | 仅 `Q2n` 分支从 tab 带出 |
| `kind` | `"local"` \| `"remote"` | 是 | `!!(tab.workspaceIdentity \|\| tab.remoteTarget \|\| tab.remoteSessionId)` |
| `connectionState` | `"connected"` \| `"disconnected"` \| `"reconnecting"` | 否 | 仅 remote 才有；`reconnecting` 来自 `reconnectingRemoteWorkspaceKeys` |
| `lastConnectionError` | string | 否 | 仅 remote，来自 `remoteWorkspaceErrorByWorkspaceKey[identity\|\|path]` |

**没有** `workspaceId` / `projectId` / `hostId` / `roots` / `pinned` / `archived` / `isPrimary` / `name`。

### 3.2 `bootstrap-response.result`

3.17.1 `validation-B0HfQ5gr.js @320455`；桌面 `out/host/chunk-ZSDMP7DM.js @727996`（`VL`）。

| 字段 | 类型 | 说明 |
|---|---|---|
| `windowControlSessionId` | string | = 桌面窗口的 `deviceSid` |
| `desktopAppVersion` | string? | 客户端据此做 `/remote/v4?app_version=` 版本自愈 |
| `workspaces` | WorkspaceEntry[] | 见 3.1 |
| `initialViewState` | `{activeWorkspaceKey?,activeTaskId?,updatedAt}`? | 来自 `h.initialTaskId` |
| `mobileViewState` | 同上? | 桌面记录的手机上次位置 |
| `mobileTaskViewEpoch` | number(int≥0)? | 手机任务视图 epoch |

### 3.3 `workspace-list-response.result` / `workspace-list-updated.result`

3.17.1 `validation-B0HfQ5gr.js @320455` 后段（`PT`）。

| 字段 | 类型 | 说明 |
|---|---|---|
| `workspaces` | WorkspaceEntry[] | 同 3.1（`buildWorkspaceListResult` 里 `workspaces:I(h)`） |
| `activeWorkspaceKey` | string? | `mobileViewState?.activeWorkspaceKey ?? initialViewState?.activeWorkspaceKey ?? ot(当前target)` |
| `activeTaskId` | string? | `mobileViewState?.activeTaskId ?? currentBridge?.initialTaskId ?? initialViewState?.activeTaskId` |

推送去重签名（`out/main/index.js @421666`）：按 `[workspaceKey, kind, connectionState??"connected", remoteSessionId??"", lastConnectionError??""]` 拼 JSON 后排序，整体比对。

### 3.4 `workspace-bridge-ready.bridge`（capability 门禁所在）

`schema: out/host/chunk-ZSDMP7DM.js @约726600`（`BL`，判别式 union on `kind`）。

| 字段 | local 分支 | remote 分支 |
|---|---|---|
| `terminalOwnerId` | string? | string? |
| `bridgeSessionId` | string | string |
| `bridgeGeneration` | int≥0? | int≥0? |
| `recoveryId` | string? | string? |
| `kind` | `"local"` | `"remote"` |
| `workspaceKey` | string | string |
| `workspacePath` | string | string |
| `workspaceIdentity` | — | string（必填） |
| `workspacePurpose` | — | `"project"`\|`"conversation"`? |
| `remoteSessionId` | — | string（必填） |
| `initialTaskId` | string? | string? |
| `capabilities` | **不存在** | `{windowProjectDirectory?: true}`? |

### 3.5 `project.readTree` 返回的 project / roots / task

实现：`out/host/index.js @42796`（`iH.readTree`），本地再加 `rH` 补 `primaryRootStatus`（`@约43700` `projectPrimaryRootStatuses`），远端任务补 `workspaceIdentity`（`@46543` `jv`）。

**Project**

| 字段 | 类型 | 备注 |
|---|---|---|
| `id` | string | 项目 id（legacy 单根服务是 `nl+sha256(JSON.stringify([hostId,path]))` 的前缀形式） |
| `hostId` | string | `"local"` 或远端 host id |
| `name` | string | 显示名 |
| `nameOverridden` | boolean | 用户改过名 |
| `roots` | `{rootPath:string,isPrimary:boolean,position:number}[]` | 多根；`isPrimary` 标记主根 |
| `position` | number | 排序 |
| `pinned` | boolean | 项目置顶 |
| `deleted` | boolean | 软删 |
| `createdAt` / `updatedAt` | number | ms |
| `legacySingleRoot` | boolean? | 仅 legacy 目录型项目 |
| `directoryMatchPath` | string? | legacy 归一化路径 |
| `primaryRootStatus` | `"available"`\|`"missing"`\|`"unavailable"`? | 仅本机路径由 `rH` 补 |

**Task（`readTree.tasks[]`）**

| 字段 | 备注 |
|---|---|
| `taskId` | 任务 id（legacy 也接受 `sessionId` 兜底） |
| `workspacePath` / `workspaceIdentity?` | 所属工作区；`workspaceKey = identity?.trim() \|\| path` |
| `hostId` | legacy 服务在此处补 `e.hostId` |
| `projectId` | `string \| null`；legacy 用 `roots[0].rootPath === workspacePath` 反查 |
| `pinned` / `archived` | `readTree` 由 active/pinned/archived 三次 readTasks 合并后打标 |
| `title` / `status` / `displayStatus?` / `provider?` | UI 字段 |
| `createdAt` / `updatedAt` / `lastActivityAt` / `unreadAt?` | 时间 |
| `mode?` / `model?` / `forkedFromTaskId?` | 会话配置 |
| `pendingInteraction?` / `pendingInteractions?` / `activity?` | 仅 `window-project`（经 `decorateProjectServiceWithTaskActivity`）与 `project`（web-remote-replayable 下同样 decorate）会带 |
| `remoteSessionId?` | 远端任务 |
| `phase?` / `hasBackgroundWork?` / `workflowActivity?` | 运行态 |

**project → workspace 的键规则（唯一）**

- `workspaceKey = workspaceIdentity?.trim() || workspacePath`（`src-BSClmRM9.js @52705`）。
- `hostId` 从 identity 前缀解析：`ma(key)` = `et(identity)` = 把 identity 里的 `<absPath>` 段切掉后剩下的 `remote:<kind>:<segments>`；本地无前缀返回 `null`，调用方 `?? "local"`（`src-BSClmRM9.js @11445`）。
- project 与 workspace 之间**没有 id 级映射**：project 靠 `roots[].rootPath`，task 靠 `(workspaceIdentity||workspacePath, taskId)` 二元组（`kF(e)` = `${identity?.trim()||path}\0${taskId}`，`out/host/index.js @1903075` 附近；renderer 同规则 `JSON.stringify([hostId, identity||path, taskId])`，`styles-C9n_FfEX.js @6594170` 之后）。
- 因此同一物理目录在「打开为 workspace」与「保存为 project」时，**只要 workspacePath 字符串一致就能对齐**；远端必须 identity 一致。

---

## 4. 3.16.1 → 3.17.1 diff

### 4.1 协议/schema

| 项 | 3.16.1 | 3.17.1 | 结论 |
|---|---|---|---|
| `bootstrap-request` | `{zcode_type, requestId}`（`index-DbEHT6Gg.js @6823539` 附近 `ie=async()=>{…V({zcode_type:'bootstrap-request',requestId:e},…)}`） | `{zcode_type, requestId, terminalClientSessionId:r}`（`index-Dwhx77cK.js @6973674`） | **新增 `terminalClientSessionId`** |
| `bootstrap-response.result` | `{windowControlSessionId, desktopAppVersion?, workspaces, initialViewState?, mobileViewState?, mobileTaskViewEpoch?}`（`validation-BBf1FA1a.js @302600` 区） | 同 | 无变化 |
| `workspace-list-request` | `{zcode_type, requestId}`（`validation-BBf1FA1a.js @303300` 区 / `index-DbEHT6Gg.js @6824123`） | `{zcode_type, requestId}`（`validation-B0HfQ5gr.js @322260` / `index-Dwhx77cK.js @6974257`） | **无变化** |
| `workspace-list-response` | `{requestId, success:true, result}` | 同 | 无变化 |
| `workspace-list-updated` | `{result}`（`index-DbEHT6Gg.js @6822254`） | `{result}`（`index-Dwhx77cK.js @6972446`） | **无变化** |
| `WorkspaceEntry` | `YC`：`workspacePath, workspaceIdentity?, remoteSessionId?, label, workspacePurpose?, kind, connectionState?, lastConnectionError?`（`validation-BBf1FA1a.js @302600`） | `DT`：逐字段相同（`validation-B0HfQ5gr.js @318740`） | **无变化** |
| bridge `local` 分支 | `{bridgeSessionId, bridgeGeneration?, recoveryId?, kind, workspaceKey, workspacePath, initialTaskId?}`（`validation-BBf1FA1a.js @302930`） | 相同 **+ `terminalOwnerId?`**（`validation-B0HfQ5gr.js @约319000`） | 仅加 `terminalOwnerId` |
| bridge `remote.capabilities` | `{windowProjectDirectory?: true}` | 同 | **无变化** |
| `workspace-reconnect-request` 响应匹配 | `n.requestId===t` | `n.requestId===t&&n.workspaceKey===e` | 客户端收紧校验 |

### 4.2 行为/结构

| 项 | 3.16.1 | 3.17.1 |
|---|---|---|
| 项目树 hook 回退 | `Fvt`：`let t=lE(),n=t.windowProjectDirectory??t,…`（`index-DbEHT6Gg.js @2053824`） | `dxt`：同结构（`index-Dwhx77cK.js @2100634`） | **无变化** |
| `windowProjectDirectory` capability 门禁 | `s.bridge.kind==='remote'&&s.bridge.capabilities?.windowProjectDirectory===!0`（`index-DbEHT6Gg.js @6825976`） | 同（`index-Dwhx77cK.js @6976130`） | **无变化** |
| bridge 准备 | `eer(...)`：`A5n(protocol,{windowProjectDirectory})`，**无 `signal`** | `Iir(...)`：`pnr(protocol,{windowProjectDirectory,signal})`，**加 abort signal**（`index-Dwhx77cK.js @6927204`） | 生命周期加固 |
| `workspace-list` 拉取 | `se`：`V({...workspace-list-request})`（`index-DbEHT6Gg.js @6824123` 区） | `ae`：相同 | 无变化 |
| 手机 workspace 切面板 | 只有 `listWorkspaces/switchWorkspace/startDraft` | 新增 `openProjectDraft`（`ue`）+ `reconnectWorkspace` 校验加强（`index-Dwhx77cK.js @6975000` 区） | 新增「打开项目草稿」入口 |
| `window-project` channel 存在性 | 存在（`index-DbEHT6Gg.js @6772608` 命中） | 存在（`index-Dwhx77cK.js @6927409` 命中） | 未移除 |
| **桌面 `getAvailableWorkspaces` 的历史实现** | **无 3.16.1 asar 可比** | 只返回「该窗口 tab + 当前 target」 | 见 UNVERIFIED |

**关键结论：3.17.1 没有引入「全部项目」能力，也没有引入 `workspaceId` 映射。3.16.1 的 `window-project` 结构已完全成熟，3.17.1 只是加了 `terminalOwnerId`、`terminalClientSessionId` 与 abort/恢复健壮性。**（注意：3.16.1 desktop asar 不在本次取证范围，桌面侧 diff 只到「3.17.1 现状」这一层。）

---

## 5. 证据索引

### 5.1 网页快照 3.17.1 `E:\Zcode_android\Reference_web\3.17.1\assets\index-Dwhx77cK.js`

| 偏移 | 原文片段 | 证明 |
|---|---|---|
| 6972446 | `workspace-list-updated` | 推送类型名 |
| 6973674 | `bootstrap-response` | bootstrap 匹配 |
| 6974257 | `workspace-list-request` | 拉取类型名 |
| 6974313 | `workspace-list-response` | 响应类型名 |
| 6976130 | `capabilities?.windowProjectDirectory===!0` | capability 门禁 |
| 6927204 | `windowProjectDirectory:e.windowProjectDirectory,signal:…` | bridge 服务构建 |
| 6927288 | `n.windowProjectDirectory.changesService.getCapabilities()…version!==1` → `window-project-directory.unsupported` | changes 服务版本校验 |
| 6927409 | `window-project` | 通道名出现 |
| 6841987 | `t.windowProjectDirectory&&(this.windowProjectDirectory={projectService:Au.toService(e.getChannel(F5e.channelName)),remoteProjectService:…I5e,changesService:…L5e})` | 客户端按开关建服务 |
| 6842282 | `windowProjectDirectory;…constructor(e,t={}){` | `unr` 服务集合 |
| 1255301 / 1255326 / 1255358 | `F5e=IE(Dr.WindowProject)` / `I5e=IE(Dr.WindowRemoteProjects)` / `L5e=IE(Dr.WindowProjectChanges)` | 通道常量 |
| 1254500 区 | `w7e=IE(Dr.Project),T7e=IE(Dr.RemoteProjects)` | `project` / `remote-projects` |
| 2100634 | `let t=ME(),n=t.windowProjectDirectory??t,r=n.projectService,i=n.remoteProjectService` | 项目树回退规则 |
| 2072406 区 | `e.projectService.readTree({includeDeleted:!0,includeDeletedTasks:!0})` + 逐 host `readTree({hostId:…})` | 全量读法 |
| 6758528 | `n.projectService?.readTree(l?{hostId:l}:void 0)` | `{hostId?}` 入参 |
| 1284134 区 | `e.projectService.readProjects()` / `readProjects({hostId:t.id})` | readProjects 入参 |
| 6916729 | `function hir(e,t){…e.workspaces.find(e=>gr(e)===n.activeWorkspaceKey)…canBridge:ji(r)…ma(n.activeWorkspaceKey)??\`local\`` | 选桥接目标 |
| 259900 区 | `sendRequest(e,t,n,r,i){…Tu(a,[e,t,n,r]),Tu(a,i)` | RPC arg 是单值不是数组 |

### 5.2 网页快照 3.17.1 共享 chunk `assets\src-BSClmRM9.js`

| 偏移 | 原文片段 | 证明 |
|---|---|---|
| 52705 | `function G(e){return e.workspaceIdentity?.trim()\|\|e.workspacePath}` | workspaceKey 规则 |
| 527xx | `function qa(e){return e.kind!==\`remote\`\|\|!!(e.workspaceIdentity&&e.remoteSessionId)}` | canBridge |
| 527xx | `function Ja({workspaces,mobileViewState,initialViewState}){…}` | 默认选中 + 优先 viewState |
| 9957 | `Ge=\`remote:\`` | identity 前缀 |
| 9970 | `Ke={ssh:3,wsl:1,docker:1,server:1…}` | identity 分段 |
| 11445 | `function et(e){let t=T(e);return t?e.slice(0,-(t.workspacePath.length+1)):null}` | hostId 提取 |
| 67259 (export) | `G as E`、`qa as b`、`Ja as w`、`et as nr` | 与 index.js 别名对应 |

### 5.3 网页快照 3.17.1 `assets\validation-B0HfQ5gr.js`

| 偏移 | 原文片段 |
|---|---|
| 318740 | `DT=E(D({workspacePath:Q,workspaceIdentity:Q.optional(),remoteSessionId:Q.optional(),label:Q,workspacePurpose:M([\`project\`,\`conversation\`]).optional(),kind:M([\`local\`,\`remote\`]),connectionState:…,lastConnectionError:x().optional()}))` |
| 319861 | `windowProjectDirectory:N(!0).optional()}).optional()})])`（bridge union 的 remote 分支） |
| 320455 | `windowControlSessionId:Q,desktopAppVersion:Q.optional(),workspaces:DT,…,PT=D({workspaces:DT,activeWorkspaceKey:Q.optional(),activeTaskId:Q.optional()})` |
| 322260 | `zcode_type:N(\`workspace-list-request\`),requestId:Q` … `workspace-list-updated` |

### 5.4 桌面 asar `out\main\index.js`

| 偏移 | 原文片段 | 证明 |
|---|---|---|
| 420762 | `function I(h){let b=new Map;for(let O of a.get(h.windowId)??[])b.set(ot(O),O);let x=_(h);if(kp(x)){…}}s(I,"getAvailableWorkspaces")` | workspaces 来源 |
| 421167 | `function U(h){return{windowControlSessionId:h.deviceSid,desktopAppVersion:e.appVersion,workspaces:I(h),…}}s(U,"buildBootstrapResult")` | bootstrap 构造 |
| 421432 | `function H(h){…return{workspaces:I(h),activeWorkspaceKey:…,activeTaskId:…}}s(H,"buildWorkspaceListResult")` | 列表构造 |
| 421666 | `function ce(h){let b=h.workspaces.map(x=>{let O=ot(x);return JSON.stringify([O,x.kind,x.connectionState??"connected",x.remoteSessionId??"",x.lastConnectionError??""])}).sort();…}s(ce,"buildWorkspaceListPushSignature")` | 推送签名 |
| 421847 | `function oe(h,b=!1){let x=H(h),O=ce(x);!b&&S.get(h.windowId)===O\|\|(S.set(h.windowId,O),te(h,{zcode_type:"workspace-list-updated",result:x}))}s(oe,"pushWorkspaceListUpdated")` | 推送条件 |
| 423577 | `createWorkspaceBridge` | 开桥前置 |
| 427491 | `buildWorkspaceBridge`（含 `I(h).find(ie=>ot(ie)===b.workspaceKey)` 与「目标工作区不在当前桌面窗口中」） | 只能桥接已开 tab |
| 427580 | `workspace-bridge-ready` + `bridge:x` | ready 载荷 |
| 430878 | `o_` 分发：`case"bootstrap-request":…result:U(h)` / `case"workspace-list-request":…result:H(h)` | 两个请求的服务端实现 |
| 440481 | `syncAvailableWorkspaces(h,b){a.set(h,b);let x=t.get(h);x&&oe(x)}` | 唯一写入口 |
| 860708 | `syncWebRemoteControlWorkspaces:s((u,m)=>{Bt.syncAvailableWorkspaces(u,m),Bt.restorePreviouslyEnabled(u,m)}…)` | renderer→main |
| 672575 | `attachLocalHost`：`clientMode:"web-remote-replayable",…,scope:{kind:"local"}` | 本地 scope 写死 |
| 666764 | `attachRemoteWorkspaceSessionHost`：`scope:{kind:"remote",remoteSessionId,workspacePath,workspaceIdentity}` | 远端 scope |
| 6726xx | `attachWorkspaceHost`: `if(m.kind==="local")return o(u,m.terminalOwnerId); … attachmentId:"shared-host-attachment-…"` | 分派 |
| 约421100 区 | `toExternalBridge`：remote→`capabilities:{windowProjectDirectory:!0}`；local→无 capabilities | capability 来源 |

### 5.5 桌面 asar `out\renderer\assets\styles-C9n_FfEX.js`

| 偏移 | 原文片段 |
|---|---|
| 6594170 | `function Q2n({tabs,…})` / `function $2n({tabs:e})` / `function t4n({platform,tabs,isDesktop,featureEnabled,hasCompletedFullTabRestore,…})` |
| 6595127 | `e.syncWebRemoteControlWorkspaces?.(s)` |
| 1186117 区 | `function jT(e){return e.kind===\`workspace\`}` |
| 16883 (index-u-S3C1E6.js) | `syncWebRemoteControlWorkspaces:e=>window.zcode.syncWebRemoteControlWorkspaces?.(e)` |

### 5.6 桌面 asar `out\host\index.js`

| 偏移 | 原文片段 |
|---|---|
| 38051 / 42796 | legacy 与正式 `readProjects`/`readTree` 实现（`{hostId,includeDeleted,includeDeletedTasks}`） |
| 43700 区 | `rH` = `projectPrimaryRootStatuses`（补 `primaryRootStatus`） |
| 46543 | `jv` wrapper：按 `workspacePath` 解析并补 `task.workspaceIdentity` |
| 36765 / 18872 | `var zr=De(ke.Project)` / `lr=De(ke.RemoteProjects)` |
| 1903069 / 1903093 / 1903124 | `Hy=De(ke.WindowProject)` / `qy=De(ke.WindowRemoteProjects)` / `Ky=De(ke.WindowProjectChanges)` |
| 1904025 | `decorateProjectServiceWithTaskActivity`：`get(n,r,o){if(r==="readTree")return async a=>c1e(await n.readTree(a),t())…}` |
| 1904711 | `PF`（`createWindowProjectChangesService`）：`getCapabilities:async()=>({version:1})`，`onDidChange` 监听 `state:projects` |
| 1956766 | `H1e` allowedChannels 白名单（含 `ke.Project`、`ke.RemoteProjects`、`ke.WindowProject`…） |
| 1958312 | `wie`：`if(clientMode==="web-remote-replayable"&&attachmentScope?.kind==="remote"){…register(Hy,…).register(qy,…).register(Ky,…)}` |
| 2162032 | `exposeServicesOnMessagePort`：`new Av(p,"host",1e3,n)`（timeoutDelay=1000）+ `wie({…attachmentScope:o,windowServices:Gt??void 0…})` |
| 2163880 区 | `resolveScope`：`if(e.kind==="local"){…return{services:Gt,generation:1}}` |

### 5.7 桌面 asar `out\host\chunk-ZDLGWCUS.js`（ChannelServer）

| 偏移 | 原文片段 |
|---|---|
| 8853 | `onPromise(e){let t=this.channels.get(e.channelName);if(!t){this.collectPendingRequest(e);return}…}` |
| 11085 区 | `collectPendingRequest(e){…setTimeout(()=>{console.error(\`Unknown channel: ${e.channelName}\`),…type:202})},this.timeoutDelay);…}` / `flushPendingRequests(e)` |

### 5.8 桌面 asar `out\host\chunk-ZSDMP7DM.js`（协议 schema）

| 偏移 | 原文片段 |
|---|---|
| 725873 | `ZL=e.object({workspacePath:y,workspaceIdentity:y.optional(),remoteSessionId:y.optional(),label:y,workspacePurpose:e.enum(["project","conversation"]).optional(),kind:e.enum(["local","remote"]),connectionState:…,lastConnectionError:e.string().optional()})` |
| 726600 区 | `BL=e.discriminatedUnion("kind",[ {…kind:"local"…}（无 capabilities）, {…kind:"remote"…,capabilities:e.object({windowProjectDirectory:e.literal(!0).optional()}).optional()} ])` |
| 727996 | `VL=e.object({windowControlSessionId:y,desktopAppVersion:y.optional(),workspaces:tI,initialViewState:uy.optional(),…})` |
| 728176 | `eI=e.object({workspaces:tI,activeWorkspaceKey:y.optional(),activeTaskId:y.optional()})` |
| 770488 | `Project:"project",…,WindowProject:"window-project",WindowRemoteProjects:"window-remote-projects",WindowProjectChanges:"window-project-changes"` |

### 5.9 网页快照 3.16.1（对照）

| 文件@偏移 | 原文片段 |
|---|---|
| `index-DbEHT6Gg.js @6823539` 区 | `ie=async()=>{let e=Z9('bootstrap');return(await V({zcode_type:'bootstrap-request',requestId:e},…)).result}`（**无** `terminalClientSessionId`） |
| `index-DbEHT6Gg.js @6824123` | `workspace-list-request` |
| `index-DbEHT6Gg.js @6822254` | `workspace-list-updated` |
| `index-DbEHT6Gg.js @6825976` | `capabilities?.windowProjectDirectory===!0` |
| `index-DbEHT6Gg.js @6771000` 区 | `eer(e)`：`A5n(e.relayProtocol.protocol,{windowProjectDirectory:e.windowProjectDirectory})`（无 signal） |
| `index-DbEHT6Gg.js @6772608` | `window-project` |
| `index-DbEHT6Gg.js @2053824` | `let t=lE(),n=t.windowProjectDirectory??t,r=n.projectService,i=n.remoteProjectService` |
| `validation-BBf1FA1a.js @302600` | `YC=D(O({workspacePath:Q,workspaceIdentity:Q.optional(),remoteSessionId:Q.optional(),label:Q,workspacePurpose:M([...]).optional(),kind:M([...]),connectionState:M([...]).optional(),lastConnectionError:S().optional()}))` |
| `validation-BBf1FA1a.js @约302930` | `ZC=A('kind',[O({bridgeSessionId:$,…,kind:N('local'),…}),O({…,kind:N('remote'),…,capabilities:O({windowProjectDirectory:N(!0).optional()}).optional()})])` |
| `validation-BBf1FA1a.js @304262` | `windowProjectDirectory:N(!0).optional()}).optional()})])` |

### 5.10 Android 侧现状（旁证，`E:\Zcode_android\android\app\src\main\kotlin\app\zemote\ui\home\HomeViewModel.kt`）

| 行 | 内容 |
|---|---|
| 460-465 | 注释：`window-project` / `zcode-task` 都要求对话握手已完成；`bootstrap 只给当前窗口已挂载的工作区，readTree 才给全部项目` |
| 509-516 | `val args = JsonArray(listOf(buildJsonObject { put("includeDeleted", false); put("includeDeletedTasks", true) }))` ← **多包了一层数组** |
| 518 | `engine.channelCall(bridge, "window-project", "readTree", args, timeoutMs = 12_000)` |
| 517-519 | `runCatching{…}.getOrNull().asObject() ?: return false` ← **错误被吞** |

---

## 6. UNVERIFIED 清单

1. **3.16.1 桌面端（asar）的 `getAvailableWorkspaces` / `buildBootstrapResult` 实现**：本机只有 3.17.1 的 `app.asar`，无 3.16.1 asar，无法逐字对比。因此「桌面侧是否在 3.17.1 才改成只回 tab」**未验证**；仅能确认 3.17.1 现状与网页侧结构自 3.16.1 未变。
2. **`featureGate` 在 Web 远程端的默认取值**：`t4n` 的 `featureEnabled` 来自上层 props，未追到 gate 的具体判定源（是否与账号/发行渠道/开关相关）。因此「手机端默认能不能看到本地 tab」**未验证**。若能拿到 `featureEnabled===false` 的实例，其 `workspaces` 必为空或仅含 remote。
3. **「无应答」是否真的收到 1000 ms 的 `Unknown channel` 错误帧**：源码证明服务端会发 type-202，但无法证明 relay/`rpc-frame` 分帧层一定把错误帧送达 Android（该路径涉及 `Cnr`/`Snr` 降级、`bridge-degraded`）。需要设备侧抓包或 logcat 中有无 `Unknown channel: window-project` 才能定论。**UNVERIFIED**。
4. **`window-services`（`Gt`）与 `e.services` 在 local scope 下是否指向同一对象**：`resolveScope` 对 `kind==="local"` 返回 `Gt`，`wie` 里 `services:t` 同时被用于注册 `project`；二者字面相同但未在运行时验证是否同一引用。若不同，「本地 scope 下 `project.readTree` 是否等于桌面侧栏看到的全量项目」需要实测。**UNVERIFIED**。
5. **`ma()` 返回的 hostId 与 project.hostId 的字符串是否完全同一套编码**：`ma` 产出 `remote:<kind>:<segments>` 前缀，`project.hostId` 在 legacy 路径下是 `e.hostId`（`"local"` 或源服务器 id）。远端 host id 与 identity 前缀是否字面相等，未找到直接赋值点。**UNVERIFIED**。
6. **`readTree` 里 `remoteSessionId` 字段的产出点**：Android 侧读 `obj["remoteSessionId"]`，桌面 `iH/jv` 未见显式赋值，可能在更上层投影（`readTasks` 转 `readTaskRows`）注入。未逐层追到赋值处。**UNVERIFIED**。
7. **`workspace-project-changes` 的 `state:projects` 广播是否会对手机端触发项目树重拉**：`PF` 的 `onDidChange` 只监听 Broadcast 服务的 `state:projects` 消息，`dxt` 的订阅链未完整追踪。**UNVERIFIED**。

---

## 7. 给 Android 端的直接结论（可执行）

1. 要「桌面侧栏全部项目」→ 用 **channel `project` + method `readTree`**，参数 **单个对象** `{ includeDeleted:false|true, includeDeletedTasks:true }`（本地）或 `{ hostId:"<id>", includeDeleted:true, includeDeletedTasks:true }`（远端）；不要再传 `JsonArray([...])`。
2. 远端 host 列表 → **channel `remote-projects` + `readDirectory`**，返回 `{hosts:[{id,membershipId,connectionStatus,…}], entries:[…]}`，只对 `connectionStatus==="online"` 的 host 发 `readTree({hostId})`。
3. `window-project` 仅当 `workspace-bridge-ready.bridge.kind==='remote'` 且 `bridge.capabilities.windowProjectDirectory===true` 时才有意义；本地工作区用它必然拿到 `Unknown channel` 超时错误。要在「当前桌面窗口未打开的本地项目」上开桥是不可能的（`目标工作区不在当前桌面窗口中`）。
4. project→workspace 对齐键：`workspaceKey = workspaceIdentity?.trim() || workspacePath`；task 再叠 `taskId`。project 侧用 `roots[].rootPath` 与 `roots[].isPrimary`。
