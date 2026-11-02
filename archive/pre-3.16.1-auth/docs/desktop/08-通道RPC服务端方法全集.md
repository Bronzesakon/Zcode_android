# 08 · 桌面端提供的通道 RPC 服务端方法全集

> **审计基线**：ZCode 桌面端 3.15.3（app.asar 340,516,766 B，build `d7ca8bee`，2026-10-13）
> 主要坐标：`out/main/index.js`、`out/host/index.js`、`out/host/chunk-T2GB4I3K.js`、
> `out/host/chunk-WNM2RERY.js`（通道 RPC 框架）、`out/main/chunk-OW6EUS2G.js`（通道名枚举）
>
> 本文所有偏移均为**解包后文本的字节偏移**（`node tools/asar.mjs dump` 落盘后的副本），
> 与 asar 内文件内容一致。已抽副本目录：`E:\Zcode_android\docs\_audit\desktop-extract\`。
> 下文中 `hi:` = `host-index.js`，`t2:` = `_t2g.js`（= `out/host/chunk-T2GB4I3K.js` 的副本，
> 原 `host-chunk-T2GB4I3K.js` 已被并行任务清理），`wmr:` = `host-chunk-WNM2RERY.js`，
> `ow:` = `chunk-OW6EUS2G.js`，`mi:` = `main-index.js`，`zc:` = `chunk-ZCWQ2IP2.js`。

---

## 0. 结论摘要（先看这 8 条）

1. **通道 RPC 框架是一套独立于 relay 的二进制 IPC 协议**：`[type, id, ack, dataLen]` 13 字节头
   + VQL 值编码，运行在 MessagePort 上（移动远控走 relay 的 `rpc-frame` 逻辑帧承载它）。
   框架全量在 `out/host/chunk-WNM2RERY.js`（18,099 B）。
2. **通道名枚举 48 条**（`ow:` 偏移 **9,462** 的 `aZ={File:"file",…}`），另有枚举外的
   `gen-ui`（`t2:` 偏移 71,240 的 `createServiceDescriptor` 用法），实际注册到桥上的通道名
   **49 条**（48 枚举 + `gen-ui`）。
3. **注册机制不是 `switch`，是「服务对象 → 通道」的 Map**：
   `ServiceCollection.register(descriptor, service)` + `exposeOnChannelServer(server, overrides)`
   → `server.registerChannel(name, fromService(service))`。服务对象的**每个可枚举函数属性**
   自动成为 RPC 方法；`on[A-Z]*` 前缀的成为事件（`listen`），其余成为可 `call` 的方法。
   **通道层零 schema 校验**。
4. **`zcode-agent` 是主通道，共 144 个成员**（125 个可调用 + 19 个 `on*` 事件），
   由 `hi:` 偏移 **295,137** 的 `createZCodeAgentService` 工厂返回。移动端所有 V4 方法都在这里。
5. **移动远控路径的 `zcode-agent` 外面还套了一层「连接作用域桥」**
   （`t2:` 偏移 **59,626** 的 `createZCodeAgentConnectionScope`），它强制
   `hello → initialize` 顺序、按订阅归属做所有权校验、并注入可信 connectionId；
   桥方法优先、未覆盖的方法回落到基础服务。
6. **`subscribeControllerV4` / `resyncControllerV4` / `unsubscribeControllerV4` /
   `onDynamicControllerFrame` 不在 `zcode-agent`，而在 `window-controller` 通道**——
   移动远控路径下该通道被替换成「窗口 host 控制器附件服务」（`hi:` 偏移 1,633,559 的
   `createAttachmentService`）。这是"控制器"能力的权威位置。
7. **入参校验是逐方法自愿的**：`initializeConversationV4`（strict）、`attachmentReadV4`、
   `conversationAttachmentReadV4/StatV4`、`attachmentPreviewSourceV4`、
   `queryConversationCommandsV4`、`backgroundBashOutputV4` 会跑 zod `.parse()`；
   订阅/取消订阅/resync/rowsRange/plans/fileChanges/workflow* **完全不校验**。
   校验失败 = 抛 `ZodError` → 通道层回 `202`，客户端只看到 `name:"ZodError"` + message(JSON)。
8. **我们实现里有一条必然失败**：`zcode-task.prepareWorkspace`
   （`conversation_transport.rs:131/1626/2664`）——对端 `zcode-task` 服务 78 个方法里
   **没有** `prepareWorkspace`（`hi:` 偏移 409,792 的 `createZCodeTaskServiceAdapter`）。
   调用会得到 `202 {name:"Error", message:"Method not found: prepareWorkspace"}`。

---

## 1. 通道 RPC 框架（权威实现）

原文件：`out/host/chunk-WNM2RERY.js`（18,099 B，压缩后 4 行）。以下偏移均指该文件。

### 1.1 线格式

| 项 | 值 | 坐标（原文片段） |
|---|---|---|
| 消息头长度 | **13 字节** | `var S=13,B=class{…}` @ **3,844** |
| 头布局 | `type:u8(0)` / `id:u32BE(1)` / `ack:u32BE(5)` / `dataLen:u32BE(9)` | `e.writeUInt8(n.type,0),e.writeUInt32BE(n.id,1),e.writeUInt32BE(n.ack,5),e.writeUInt32BE(n.data.byteLength,9)` @ **4,210**（`writeProtocolMessage`） |
| 消息类型 | 只有 `1` 会触发 `onMessage`（`t===1&&this._onMessage.fire(c)`） | `readMessages()` @ ~4,700 |
| MessagePort 传输 | `postMessage(e.buffer)`；控制帧 `{__zcodeRpcControl:"connection-flow-v1",state}` 单独分流 | `isMessagePortFlowControl` @ **2,804**；`MessagePortProtocol` @ ~5,000 |

### 1.2 值编码（`serialize` / `deserialize`）

标签（`var x={Undefined:k(0),String:k(1),Buffer:k(2),VSBuffer:k(3),Array:k(4),Object:k(5),Int:k(6)}`）：

| 标签 | 类型 | 编码 |
|---|---|---|
| 0 | undefined | 无负载 |
| 1 | string | VQL 长度 + UTF-8 |
| 2 | `Uint8Array` | VQL 长度 + 原始字节 |
| 3 | `VSBuffer` | 同上 |
| 4 | array | VQL 元素数 + 逐元素递归 |
| 5 | object | VQL 长度 + **JSON**（`Uint8Array` 先包成 `{__zcode_rpc_nested_uint8array_v1:true, base64:…}`） |
| 6 | int | VQL 变长整数（仅 `(e\|0)===e` 的 int32；其它 number 走 JSON） |

> VQL = LEB128 无符号变长（`writeInt32VQL`/`readIntVQL`）。
> 对端能把 `number` 拆成「Int 标签」或「JSON 里的 number」，客户端两种都要接受。

### 1.3 请求/响应类型码

| 方向 | 码 | 名 | 负载 |
|---|---|---|---|
| 请求 | 100 | `reqPromise` | `[100, id, channelName, methodName]` + arg |
| 请求 | 101 | `reqPromiseCancel` | `[101, id]` + undefined |
| 请求 | 102 | `reqEventListen` | `[102, id, channelName, eventName]` + arg |
| 请求 | 103 | `reqEventDispose` | `[103, id]` + undefined |
| 响应 | 200 | `resInitialize` | `[200]`（无 data） |
| 响应 | 201 | `resPromiseSuccess` | `[201, id]` + data |
| 响应 | 202 | `resPromiseError` | `[202, id]` + `{message,name,stack?,code?,kind?,status?,retryAfterMs?,data?,detail?,details?,taskId?,traceId?}` |
| 响应 | 203 | `resPromiseErrorObj` | `[203, id]` + 原样 data |
| 响应 | 204 | `resEventFire` | `[204, id]` + data |

坐标：`ChannelServer.sendResponse` @ **~9,050**；`ChannelServer.onRawMessage` 的
`switch(i){case 100:this.onPromise(...);case 102:this.onEventListen(...);case 101:case 103:this.disposeActiveRequest(s[1])}`。

### 1.4 `ChannelServer` 行为（这是"对端会怎么对我"的权威）

```js
var A=class{constructor(e,t,s=1e3,r=!1){…this.timeoutDelay=s…this.deferInit||this.sendResponse({type:200})}
channels=new Map; activeRequests=new Map; pendingRequests=new Map;
registerChannel(e,t){this.channels.set(e,t),setTimeout(()=>this.flushPendingRequests(e),0)}
onPromise(e){let t=this.channels.get(e.channelName);if(!t){this.collectPendingRequest(e);return}
  let s=new E,r;try{r=t.call(this.ctx,e.name,e.arg,s.token)}catch(o){r=Promise.reject(o)}
  …r.then(o=>{this.sendResponse({id:e.id,data:o,type:201})},
        o=>{ if(o instanceof Error){ …复制 code/kind/status/retryAfterMs/data/detail/details/taskId/traceId…; this.sendResponse({…type:202}) } 
             else this.sendResponse({id:e.id,data:o,type:203}) })}
```
（`wmr:` 偏移 **8,640** 起，`i(this,"ChannelServer")`）

关键行为：

| 场景 | 对端行为 | 客户端可见结果 |
|---|---|---|
| 通道已注册、方法存在 | 调用，成功回 201 | 正常 |
| 方法不存在 | `fromService.call` 抛 `Error("Method not found: X")` | **202**，`{name:"Error", message:"Method not found: X"}` |
| 事件名不存在（102） | `fromService.listen` 抛 `Error("Event not found: X")`，**在 `onRawMessage` 里没有 try/catch** | **没有任何响应**（客户端只能等自己的超时） |
| 通道未注册 | 进 `pendingRequests`，起 `timeoutDelay` 定时器；到点回 202 | **202**，`{name:"Unknown channel", message:"Channel name 'X' timed out after 1000ms"}`（默认 `timeoutDelay=1000`） |
| 通道在 1000ms 内被注册 | `flushPendingRequests` 重放请求 | 正常 |
| 方法抛 `Error` | 202 + 白名单字段透传 | `code`/`kind`/`status`/`retryAfterMs` 等可用 |
| 方法抛非 Error | 203 + 原值 | data 即原值 |
| 发送失败（`protocol.send` 抛） | `try{…}catch{}` **静默吞掉** | 客户端超时 |
| 取消（101）/释放（103） | `disposeActiveRequest` | 无回执 |

**没有任何 per-call 超时**：`ChannelServer` 对已注册通道的调用不设超时，超时责任在调用方
（Dart/Rust 侧的 `call(timeout:)`）。唯一的时间常数是**未知通道的 1000 ms 等待窗口**。

### 1.5 `ChannelClient` 行为（对端作为客户端时；用于理解 `prepareStorage` 等内部调用）

`ChannelClient.requestPromise`（`wmr:` ~12,900）：发 100，**无超时**，只支持取消；
`state!==1` 时先 `whenInitialized()`（等 200）。`requestEvent` 发 102，监听器引用计数归零时发 103。

### 1.6 `fromService` / `toService`（方法可见性规则 —— 决定"什么能调"）

```js
function n(s,r){let i=s,o=new Map;
  for(let c in i) $(c)&&!W(c)&&typeof i[c]=="function"&&o.set(c,ee(i[c]));   // 事件表
  return{
    listen(c,d,h){let m=o.get(d);if(m)return m;let g=i[d];
      if(typeof g=="function"){if(W(d))return g.call(i,h);if($(d))return o.set(d,ee(i[d])),o.get(d)}
      throw new Error(`Event not found: ${d}`)},
    call(c,d,h){let m=i[d];if(typeof m=="function"){let g=m.apply(i,h||[]);
      return g instanceof Promise||(g=Promise.resolve(g)),g}
      throw new Error(`Method not found: ${d}`)}}}
function $(n){return n.length>=3&&n[0]==="o"&&n[1]==="n"&&n.charCodeAt(2)>=65&&n.charCodeAt(2)<=90}   // /^on[A-Z]/
function W(n){return n.length>=10&&n.startsWith("onDynamic")&&n.charCodeAt(9)>=65&&n.charCodeAt(9)<=90}
```
（`wmr:` 偏移 **17,059** / **16,892** / **17,088** / **17,393**）

推论（重要）：
- `for(let c in i)` 是**枚举全部可枚举属性**（含原型链），但只收 `typeof === "function"`。
- **`on[A-Z]` 开头的方法不能 `call`，只能 `listen`**。`onDynamic[A-Z]` 是"带参数的事件工厂"：
  `listen(channel, "onDynamicX", arg)` 会调用 `onDynamicX(arg)` 拿到一个 per-arg disposer。
- 名字不以 `on[A-Z]` 开头的函数一律可 `call`——包括 `dispose`、`initialize`、
  `hasActiveCuaOperationTurn` 这类内部方法（**它们确实暴露在线上**）。
- 非函数属性（getter 取到的值）不暴露。

---

## 2. 通道名全集

### 2.1 枚举（48 条，权威）

`out/main/chunk-OW6EUS2G.js` 偏移 **9,462**：

```js
var aZ={File:"file",MediaPreview:"media-preview",System:"system",Terminal:"terminal",Git:"git",
GitCheckpoint:"git-checkpoint",Setting:"setting",Credential:"credential",CuaPermission:"cua-permission",
CuaPipSession:"cua-pip-session",Broadcast:"broadcast",ZCodeTask:"zcode-task",
WindowController:"window-controller",ZCodeAgent:"zcode-agent",ZCodeSession:"zcode-session",
ConversationShare:"conversation-share",FileWatcher:"file-watcher",OAuth:"oauth",
ProviderSettings:"provider-settings",ModelSelection:"model-selection",
ProviderProvisioningTarget:"provider-provisioning-target",UsageStats:"usage-stats",
CodingPlanSubscription:"coding-plan-subscription",ClientConfig:"client-config",
ClientScenes:"client-scenes",CloudContent:"cloud-content",MarketingTouch:"marketing-touch",
Skills:"skills",SkillSync:"skill-sync",McpSync:"mcp-sync",PluginSync:"plugin-sync",Plugins:"plugins",
PluginManagement:"plugin-management",PluginUiBridge:"plugin-ui-bridge",
PluginUiSampling:"plugin-ui-sampling",PluginUiAppTools:"plugin-ui-app-tools",Subagents:"subagents",
Commands:"commands",Hooks:"hooks",Memory:"memory",OutputStyle:"output-style",
SettingsSync:"settings-sync",Bots:"bots",Feedback:"feedback",
PromptAttachmentTransfer:"prompt-attachment-transfer",OffPeakTask:"off-peak-task",
HighspeedCard:"highspeed-card",OnboardingRecord:"onboarding-record"}
```

同构副本：`out/host/chunk-OUCOFHX4.js` 偏移 **721,419**（`CloudContent` 处对齐）。
`out/host/chunk-T2GB4I3K.js` 里逐条 `var X=f(h.EnumName)` 生成描述符
（`function f(t){return{channelName:t}}`，`t2:` 偏移 **71,240**）。

**枚举外还有 1 条：`gen-ui`** —— `t2:` 偏移 71,240 附近
`var $n=a("gen-ui")`（`createServiceDescriptor`），注册时用 `qg`（见 §3.2）。
故可注册通道名 **49 条**。

### 2.2 `"transport"` 不是通道名（纠正常见误解）

全包内 `"transport"` 只出现在三处语义，**都不是通道**：
- 遥测字段 `transport:e.enum(["http","sse","websocket"])`（`ow:` 偏移 **610,138**）；
- 分享范围 `scope:"conversation"|"transport"`（`zc:` 偏移 ~223,394，分享预检）；
- 通道框架里 `MessagePortProtocol`/`SocketProtocol` 的变量名。

`out/main/index.js` 中也没有名为 `transport` 的通道。

### 2.3 每个通道的职责与注册坐标

| 通道 | 职责（一句话） | 服务工厂 / 注册坐标 |
|---|---|---|
| `file` | 工作区文件读、目录遍历、搜索、文本/二进制/媒体预览 | `createFileService` @ hi:150,282（17 法） |
| `media-preview` | 本地媒体预览（授权 URL 或内联 base64） | `createMediaPreviewService`（`Rg`）@ t2:71,291（1 法） |
| `system` | 系统信息、终端 shell 列表、内网探测 | `createSystemService` @ hi:220,918（3 法） |
| `terminal` | 伪终端 create/write/resize/dispose + 数据/退出事件 | `createTerminalService` @ hi:246,368（7 法） |
| `git` | 仓库摘要、分支、diff、stage/commit/push | `createGitService` @ hi:198,207（18 法） |
| `git-checkpoint` | 检查点创建/对比/恢复/删除 | `createGitCheckpointService` @ hi:214,729（4 法） |
| `setting` | 设置快照与更新 | `createSettingService`/`createObservableSettingService` @ hi:253,468 / 1,329,?（未完整枚举） |
| `credential` | 凭据 load/save | `createCredentialService` @ hi:258,976（2 法） |
| `cua-permission` | CUA 权限（桌面专用） | `shouldUseCuaPermissionService` @ hi:~631,000（未完整枚举） |
| `cua-pip-session` | CUA 画中画会话生命周期 | `createCuaPipSessionService` @ hi:631,209（terminateGeneration/close/dispose…） |
| `broadcast` | 跨窗口广播 send/tryClaim | `createBroadcastService` @ hi:261,653（2 法） |
| `zcode-task` | 任务列表/快照/归档/置顶/删除/发送提示 | `createZCodeTaskServiceAdapter` @ hi:409,792（78 法，见 §4.3） |
| `window-controller` | **移动远控下 = 控制器附件服务**（controller V4 + 任务 mutation） | `createWindowHostControllerRuntime` @ hi:1,624,437 → `createAttachmentService` @ hi:1,633,559（8 法） |
| `zcode-agent` | **主通道**：会话、对话 V4、附件、自动化、插件/MCP、workflow、订阅 | `createZCodeAgentService` @ hi:295,137（144 成员，见 §4.1） |
| `zcode-session` | 会话生命周期（create/resume/close/model/mode） | `createZCodeSessionService` @ hi:508,374（16 法） |
| `conversation-share` | 对话分享/导入（preflight/publish/import） | `ConversationShareService` @ zc:224,871（9 法） |
| `file-watcher` | watch/unwatch + 变更事件 | `createFileWatcherService` @ hi:942,429（4 法） |
| `oauth` | OAuth 登录/登出/刷新 | `createOAuthService` @ hi:984,181（未完整枚举） |
| `provider-settings` | Provider 设置视图 | `nt.providerSettings`（未完整枚举） |
| `model-selection` | 模型选择视图 | `nt.modelSelection`（未完整枚举） |
| `provider-provisioning-target` | Provider 供给目标（条件注册） | `lT({...})` @ hi:~154,?（条件：`t \|\| providerProvisioningTargetEnabled===true`） |
| `usage-stats` | 用量/编码计划用量快照、重置机会 | `createUsageStatsService` @ hi:1,106,136（9 法） |
| `coding-plan-subscription` | 编码计划订阅状态 | `createCodingPlanSubscriptionService` @ hi:1,110,733（getSnapshot 等） |
| `client-config` | 客户端配置快照 | `createClientConfigService` @ hi:1,111,937（1 法） |
| `client-scenes` | 客户端场景快照 | `createClientScenesService` @ hi:1,112,121（getSnapshot） |
| `cloud-content` | 已发布媒体读取、bundle 预取/释放 | `createCloudContentService` @ hi:122,460（4 法） |
| `marketing-touch` | 运营位查询/上报 | `createMarketingTouchService` @ hi:124,923（2 法） |
| `skills` | 技能列表/启停/提示上下文/复制/删除 | `createSkillsService` @ hi:1,129,609（6 法） |
| `skill-sync` | 用户技能跨端导入导出 | `createSkillSyncService` @ hi:1,137,811（5 法） |
| `mcp-sync` | MCP 跨端导入导出 | `createMcpSyncService` @ hi:1,144,522（8 法） |
| `plugin-sync` | 插件跨端导入导出 | `createPluginSyncService` @ hi:1,160,979（7 法） |
| `plugins` | 插件总览/市场/安装/卸载/启停 | `createPluginsService` @ hi:1,176,048（7 法） |
| `plugin-management` | 插件管理（配置/校验/描述） | `createPluginManagementService` @ hi:1,177,438（未完整枚举） |
| `plugin-ui-bridge` | 插件 UI 桥 | `PluginUiBridge` @ hi:1,125,?（未完整枚举） |
| `plugin-ui-sampling` | 插件 UI 采样 | 同上（未完整枚举） |
| `plugin-ui-app-tools` | 插件 App 工具注册/调用 | 同上（未完整枚举） |
| `subagents` | 子代理列表/启停/模型覆盖/CRUD | `createSubagentsService` @ hi:1,227,240（10 法） |
| `commands` | 斜杠命令目录 | `createCommandsService` @ hi:1,239,841（未完整枚举） |
| `hooks` | Hook 列表/配置 | `createHooksService` @ hi:1,260,001（未完整枚举） |
| `memory` | 项目记忆读写/预览 | `createMemoryService` @ hi:1,265,811（未完整枚举） |
| `output-style` | 输出风格 | `createOutputStyleService` @ hi:1,269,160（未完整枚举） |
| `settings-sync` | Claude→ZCode 设置迁移 | `createSettingsSyncService` @ hi:1,300,?（6 法） |
| `bots` | IM 机器人（飞书/微信）注册、绑定、话题、回复 | `createBotsService` @ hi:940,604（34 法） |
| `feedback` | 反馈提交/列表 | `createFeedbackService` @ hi:1,328,?（未完整枚举） |
| `prompt-attachment-transfer` | 提示附件跨端搬运 stage/adopt/cancel/cleanup | `createLocalPromptAttachmentTransferService` @ hi:1,329,580 / `createRemote...`（5 法） |
| `off-peak-task` | 错峰任务 | `OffPeakTaskService` @ hi:~1,540,?（未完整枚举） |
| `highspeed-card` | 高速卡（绘图/健康检查） | `hi:~1,540,?`（未完整枚举） |
| `onboarding-record` | 引导记录 | `createOnboardingRecordService` @ hi:1,455,846（9 法） |
| `gen-ui`（枚举外） | 生成式 UI 沙箱 | `createGenUiService` @ hi:30,288（1 法） |

---

## 3. 方法注册机制

### 3.1 一张注册表 + 一个适配器（不是 switch）

`t2:` 偏移 **80,900** 起：

```js
var Ft=class{static{a(this,"ServiceCollection")}_services=new Map;
  register(o,r){return this._services.set(o.channelName,r),this}
  get(o){let r=this._services.get(o.channelName);
    if(!r)throw new Error(`Service not registered: ${o.channelName}`);return r}
  getOptional(o){return this._services.get(o.channelName)}
  exposeOnChannelServer(o,r=new Map){for(let[n,i]of this._services){
    let d=r.get(n)??i;o.registerChannel(n,P.fromService(d))}}};
```

- `register(descriptor, service)`：`descriptor` 就是 `{channelName}`（`f(t){return{channelName:t}}`）。
- `exposeOnChannelServer(server, overrides)`：把**整个集合**逐条 `server.registerChannel`，
  `overrides` 是 `Map<channelName, service>`，用于按场景替换某条通道的实现。
- `P.fromService(service)` 见 §1.6 —— 服务对象的函数属性即方法。

### 3.2 本地服务集合的装配点（45 + 3 条）

`hi:` 偏移 **1,540,961**（函数 `R3` = `createLocalServices`）：

```js
new Eu().register(pB,KF({…})).register(Ua,yo).register(ud,ya)…register(Jn,re)…
```
`Eu` = `ServiceCollection`，`Jn` = `zcode-agent`，`xn` = `zcode-task`，`Zo` = `zcode-session`，
`zs` = `conversation-share`，`ud` = `media-preview`，`so` = `cua-permission`，`qg` = `gen-ui`，
`pd` = `provider-settings`，`gi` = `model-selection`，`sL` = `provider-provisioning-target`。

链上共 **45 次 `.register(`**，链后追加
`Vt.register(pd,nt.providerSettings).register(gi,nt.modelSelection)` 与条件
`Vt.register(sL,lT({…}))`；`window-controller` 在暴露阶段补注册（见 §3.3）。
合计 **48 条枚举通道全部注册**，外加 `gen-ui`。

### 3.3 移动远控路径：从 relay 到 ChannelServer

```
手机 → relay(WS) → main 进程 routePayload
  case "rpc-frame"/"rpc-frame-ack": wo(h,C) → h.currentBridge.relayProtocol.acceptPayload(C)
  （mi: 偏移 405,584 的 switch；wo → Ut → acceptPayload，mi: 404,431）
       ↓ 重组后的逻辑帧
  Mg({bridgeSessionId,…}).protocol.onMessage(Le => Se.send(Le))       ← mi: 401,6xx（createWorkspaceBridge）
       ↓ MessagePort
  host 进程 attachLocalHost 收到的 port
       ↓
  Zi.expose → exposeServicesOnMessagePort(port, mn, false, "web-remote-replayable", {kind:"local"})
```

坐标：
- `routePayload` 的 `case"rpc-frame":case"rpc-frame-ack":wo(h,C)` —— `mi:` 偏移 **405,584**。
- `createWorkspaceBridge`（`te`）—— `mi:` 偏移 **400,202**；`attachWorkspaceHost` 分派 ——
  `mi:` 偏移 **629,910**（`attachLocalHost` 在 629,059 附近，发 `{type:H.AttachServicePort, clientMode:"web-remote-replayable", scope:{kind:"local"}}`）。
- `exposeServicesOnMessagePort`（`uDe`）—— `hi:` 偏移 **1,664,860**（marker 在 1,666,176）：

```js
function uDe(e,t,n,r="desktop-continuous",o={kind:"local"},s){
  let a=V3(e),c=new Zg(a);
  let d=new Hg(c,"host",1e3,n), l=new YN(d,oDe), u=new QN(l);     // ChannelServer(protocol,"host",timeout=1000,deferInit=n)
  let p=t.getOptional(Jn), m=p?th(p,{connectionId:`host-rpc-${ha()}`,clientMode:r}):void 0;  // zcode-agent 连接作用域桥
  t.register(GP,Xr.service);                                        // window-controller ← 窗口 host 控制器运行时
  let h=Xr.createAttachmentService(), v=new Map([[GP.channelName,h]]);   // 覆盖：window-controller ← 控制器附件服务
  let P=o.kind==="remote"&&r==="desktop-continuous"?s?.remoteMediaPreviewFactory?.(o):void 0;
  P&&v.set(ud.channelName,P.service);                               // media-preview 覆盖（仅 remote+continuous）
  let k=t.getOptional(xn); k&&v.set(xn.channelName,lDe(k,o));       // zcode-task ← controller 路由代理
  m&&v.set(Jn.channelName,m.service);                               // zcode-agent ← 连接作用域桥
  let _=t.getOptional(zs); _&&v.set(zs.channelName,O8(_,r,m?.service));  // conversation-share ← 连接作用域分享
  t.exposeOnChannelServer(u,v);
  …}
```

**结论**：手机上能调的通道 = 本地服务集合的全部 48(+gen-ui) 条；其中 5 条被覆盖：
`zcode-agent`、`window-controller`、`zcode-task`、`conversation-share`、
（条件）`media-preview`。`zcode-agent` 桥的 `clientMode = "web-remote-replayable"`。

---

## 4. 方法名全集

### 4.1 `zcode-agent`（144 成员 = 125 可调用 + 19 事件）

工厂 `createZCodeAgentService`（`hi:` 偏移 **295,137**，返回对象起始于偏移 **333,488**）。
移动远控下再套 `createZCodeAgentConnectionScope`（`t2:` 偏移 **59,626**，方法表 `Lr`），
桥方法**全部同名覆盖**、未覆盖的回落到基础服务（`return{service:new Proxy(t,{get(c,u,C){
let v=Reflect.get(Lr,u,C);if(v!==void 0)return v;let k=Reflect.get(c,u,c);…}})}`，`t2:` 偏移 ~70,5xx）。

**A. 握手 / 连接（桥覆盖）**

| 方法 | 入参 | 返回 | 备注 |
|---|---|---|---|
| `helloConversationV4` | 无 | `{kind:"hello",protocolVersion:3,connectionId,clientMode:"web-remote-replayable",deliveryProfile:"replayable",serverTime,capabilities:{nativeDialogs:false,localTerminal:false,binaryFrames:false,compression:"none",workspaceHookReview:true,independentPlanState:true,workflowRunDeltas:true},auth:{}}` | `t2:` 59,245 `createHello`；桥先 `z()`（closed 检查）再置 `w=true` |
| `initializeConversationV4` | `{kind:"clientHello",protocolVersion:3,clientId,clientKind?:"desktop"\|"web"\|"mobileRemote"\|"mobileApp",appVersion,capabilities?:{workspaceHookReviewUi?,workflowRunDeltas?}}` **strict** | `undefined` | `Hn.parse`；桥要求先 hello（否则 `fault.connection.helloRequired`），clientId 二次不一致 → `fault.connection.clientChanged` |
| `setConnectionFlowStateV4` | `{connectionId,state:"saturated"\|"drained"\|"closed"}` | `undefined` | 桥：非 `trusted-host-relay` 直接 `fault.connection.flowControlForbidden`（移动端**不可用**） |

**B. 对话订阅（桥覆盖）**

| 方法 | 入参 | 返回 | 备注 |
|---|---|---|---|
| `subscribeConversationV4` | `{sessionId, workspacePath, workspaceIdentity?, connectionId?, clientMode?, base?:{logEpoch,seq}, visibility?:"foreground"\|"background", runtimePolicy?, subscriberScope?, workflowRunDeltas?}` | `{ack:{subscriptionId,mode:"snapshot"\|"resume",logEpoch,openTiming?}}` | topic 由服务端算 `conversation/<sessionId>`；`ack.openTiming` 含 `hostPrepareMs/cliProcessState/...` |
| `unsubscribeConversationV4` | `{workspacePath,workspaceIdentity?,subscriptionId}` | `undefined` | **找不到归属时静默 no-op**（`if(!G)return`，hi:305,317） |
| `resyncConversationV4` | `{workspacePath,workspaceIdentity?,subscriptionId,base?,forceSnapshot?}` | `{ack:{subscriptionId,mode,logEpoch}}` | 找不到归属 → `fault.subscription.notOwned` |

**C. 命令**

| 方法 | 入参 | 返回 | 备注 |
|---|---|---|---|
| `sendConversationCommandV4` | `{envelope:{commandId,clientId,sessionId,baseRevision?,baseLogEpoch?,type,payload,issuedAt}, workspacePath,workspaceIdentity?,…}` | 命令应答（`KB` schema） | 桥：`terminal-client` 下 `envelope.clientId` 必须等于 initialize 的 clientId，否则 `fault.command.clientMismatch`；`sendText.payload` 跑 `eh.sendText.parse`；`web-remote-replayable` 时注入 `payload.inputOrigin="mobile"` 并剥掉 `ttft` |
| `queryConversationCommandsV4` | `{commands:[…], clock?:true, workspacePath,…}` | 查询结果（`JB`） | `VB.parse`；桥：无 `clock` 时跨工作区查询 → `fault.command.queryForeignWorkspace` |

**D. 附件**

| 方法 | 入参（zod） | 返回 |
|---|---|---|
| `attachmentBeginV4` | `{connectionId,uploadId,sessionId,fileName,mime,totalBytes,totalChunks,checksum:"sha256:<64hex>"}`（仅做 NDJSON 体积检查 `proto.frameTooLarge`） | `{uploadId,state:"staging",nextChunkIndex}` 或 `{uploadId,state:"committed",nextChunkIndex,ref}` |
| `attachmentChunkV4` | `{connectionId,uploadId,sessionId,chunkIndex,dataBase64}`（解码后 ≤512 KiB） | `{uploadId,nextChunkIndex}` |
| `attachmentCommitV4` | `{connectionId,uploadId,sessionId}` | 提交结果（`Xg`） |
| `attachmentAbortV4` | `{connectionId,uploadId,sessionId}` | `{}` |
| `attachmentReadV4` | `{sessionId,ref,target?,attachmentIndex?,offset,limit≤512KiB}` **`BB.parse`**，`target` 与 `attachmentIndex` 必须同时给 | `{dataBase64,mediaType,totalBytes,nextOffset:number\|null}` |
| `conversationAttachmentReadV4` | `{sessionId,ref,target,attachmentIndex,offset,limit}` **`zB.parse`**（target+index 必填） | 同上 |
| `conversationAttachmentStatV4` | `{sessionId,ref,target,attachmentIndex}` **`$B.parse`** | `{size,mime,…}` |
| `attachmentPreviewSourceV4` | `{sessionId,ref,target?,attachmentIndex?,clientMode}` **`LB.parse`** | `{kind:"local_path",path,mediaType}` 或 `{kind:"chunked"}`；**`clientMode!==desktop-continuous` 直接返回 `{kind:"chunked"}`**（不做本地路径授权） |

**E. 会话内容查询**

| 方法 | 入参 | 返回 |
|---|---|---|
| `conversationRowsRangeV4` | `{sessionId,beforeRowId?,limit(1..200)}`（**不校验**，`limit` 直接透传） | `{rows,atSeq,atRevision,atLogEpoch,hasMore}` |
| `conversationPlansV4` | `{sessionId}` | `{plans,atSeq,atLogEpoch}` |
| `conversationFileChangesV4` | `{sessionId,target:{rowId,entityId},baseRevision,baseLogEpoch}` | `{files,additions,deletions,state?,items[]}` |
| `conversationFileRewindPreviewV4` | 同上 | `{canApply,ignoredFiles,safeFiles,unsafeFiles}` |
| `backgroundBashOutputV4` | `{sessionId,workId}` **`SB.parse`（两个都必填）** | 输出对象；特定错误降级为 `{kind:"unsupported"\|"unavailable",workId}` |

**F. workflow run**

| 方法 | 入参 |
|---|---|
| `conversationWorkflowRunsV4` | `{sessionId,limit?≤64}` → `{runs:[{runId,toolCallId?,label?,updatedAt?,status,stopReason?,resumedFrom?,supersededBy?,failureCode?,failureMessage?,resumable}]}` |
| `conversationWorkflowRunEventsV4` | `{sessionId,runId,afterSequence?,limit?≤500}` → `{events:[{sequence,type,payload,truncated?}],hasMore}` |
| `conversationWorkflowRunArtifactsV4` | `{sessionId,runId}` |
| `conversationWorkflowRunArtifactDataV4` | `{sessionId,runId,artifactId,afterSequence?,limit?}` |
| `conversationWorkflowRunArtifactReadV4` | `{sessionId,runId,artifactId,version,offset,limit}` |
| `conversationWorkflowRunWorkspaceV4` | `{sessionId,runId}` |
| `conversationWorkflowRunNodeResultV4` | `{sessionId,runId,siteId,ordinal,maxBytes?}` |

**G. 索引 / 工作区配置订阅（桥覆盖）**

| 方法 | 入参 | topic |
|---|---|---|
| `subscribeSessionsIndexV4` | `{workspacePath,workspaceIdentity?,base?,visibility?,runtimePolicy?,subscriberScope?}` | `sessions-index/<workspaceKey>` |
| `unsubscribeSessionsIndexV4` | `{…,subscriptionId}` | 同上 |
| `resyncSessionsIndexV4` | `{…,subscriptionId,base?}` | 同上 |
| `subscribeWorkspaceConfigV4` | `{workspacePath,workspaceIdentity?,base?,visibility?}` | `workspace-config/<workspaceKey>` |
| `unsubscribeWorkspaceConfigV4` | `{…,subscriptionId}` | 同上 |
| `resyncWorkspaceConfigV4` | `{…,subscriptionId,base?}` | 同上 |

**H. 事件（`listen`，19 个）**

`onDynamicStorageStartupState(scope)`、`onDynamicSessionEvent(scope)`、
`onDynamicConversationFrame(scope)`（按**工作区**聚合，帧内带 `topic`）、
`onDynamicLocalTtftFacts(scope)`、`onDynamicConversationTelemetryFact(scope)`、
`onDynamicCuaPermissionObservation()`、`onDynamicSessionsIndexFrame(scope)`、
`onDynamicWorkspaceConfigFrame(scope)`、`onDynamicProcessResourceSample()`、
`onDynamicToolExecResource()`、`onDynamicMcpResourceSamples()`、`onDynamicMcpTelemetry()`、
`onDynamicProviderRuntimeHeadersRequest(scope)`、
`onDynamicWorkspaceProviderRuntimeHeadersRequest(scope)`、
`onDynamicWorkspaceProviderRuntimeHeadersCancelled(scope)`、
`onDynamicSessionRuntimePreferencesRequest()`、`onDynamicPluginOperationProgress(scope)`、
`onAgentRuntimeRestarted(scope)`、`onAgentRuntimeLifecycle(scope)`。

> 移动路径（`web-remote-replayable` + `terminal-client`）下，桥把
> `onDynamicLocalTtftFacts`、`onDynamicConversationTelemetryFact`、
> `onDynamicCuaPermissionObservation`、`onDynamicProcessResourceSample`、
> `onDynamicToolExecResource`、`onDynamicMcpResourceSamples`、`onDynamicMcpTelemetry`
> **一律返回 `None`**（订阅成功但永远不推数据）。

**I. 其余可调用方法（按域）**

- 工作区/存储：`prepareStorage(scope)`、`getStorageStartupState(scope)`、`initialize(scope)`、
  `getWorkspaceRuntimeIdentity(scope)`、`disposeWorkspace(scope)`、`disposeAll()`、
  `disposeAllAndWait()`、`dispose()`。
- 会话：`createSession`、`resumeSession`、`listSessions`、`listSessionSubagents`、`readSession`、
  `readSessionMessages`、`readSessionDebug`、`readSessionEvents`、`readWorkspacePresentation`、
  `closeSession`、`compactSession`、`goalSession`、`setModel`、`setThoughtLevel`、`setMode`、
  `sendPrompt`、`generateWorkspaceText`、`testModelConnectivity`、`grantWorkspaceHookTrust`、
  `getAppUsageStats`、`getTaskTokenUsage`、`syncDynamicWorkflowUserMode`、
  `syncAppRuntimePreferences`、`respondSessionRuntimePreferences`、
  `respondProviderRuntimeHeaders`、`hasActiveCuaOperationTurn`。
- 自动化：`listAutomations(scope)`、`listAllAutomations()`、`createAutomation`、
  `updateAutomation`、`deleteAutomation`、`setAutomationEnabled`、`restartAutomation`、
  `runAutomationNow`、`listAutomationRuns`、`deleteAutomationRun`。
- 插件/MCP/UI 桥：`listPlugins`、`getPluginsOverview`、`addPluginMarketplace`、
  `removePluginMarketplace`、`updatePluginMarketplace`、`installPlugin`、`uninstallPlugin`、
  `updatePlugin`、`restoreBuiltinPlugin`、`cancelPluginOperation`、`configurePlugin`、
  `resetPluginConfig`、`validatePlugin`、`describePlugin`、`setPluginEnabled`、
  `getPluginReferenceCatalog`、`getSkillReferenceCatalog`、`resolveSuggestedPluginReference`、
  `listPluginUiSurfaces`、`listMcpServerStatuses`、`openMcpUiInstance`、`validateMcpUiInstance`、
  `recycleMcpUiInstance`、`closeMcpUiInstance`、`readMcpResource`、`sampleMcpApp`、
  `cancelMcpAppSampling`、`callMcpToolForUi`、`cancelMcpToolCallForUi`、`readMcpResourceForUi`、
  `listMcpResourcesForUi`、`listMcpResourceTemplatesForUi`、`subscribeMcpResourceForUi`、
  `unsubscribeMcpResourceForUi`、`registerAppToolsForUi`、`unregisterAppToolsForUi`、
  `claimAppToolCallForUi`、`resolveAppToolCallForUi`、`collectLocalRuntimeChildProcesses`。
- 已保存 workflow：`listSavedWorkflows`、`getSavedWorkflow`、`updateSavedWorkflowMeta`、
  `deleteSavedWorkflow`、`listSavedWorkflowRuns`、`moveSavedWorkflow`、
  `saveSavedWorkflowFromRun`、`findSavedWorkflowForRun`。

### 4.2 `window-controller`（8 法，**控制器 V4 在这里**）

`createAttachmentService`（`hi:` 偏移 **1,633,559**，`i(U,"createAttachmentService")`）：

| 方法 | 入参 | 返回 |
|---|---|---|
| `subscribeControllerV4` | `{workspacePath,workspaceIdentity?,…}`（桥内用 `t.subscribe`，返回 `{ack:{subscriptionId}}`） | `{ack:{subscriptionId}}` |
| `resyncControllerV4` | `{subscriptionId}` | `{ack:{subscriptionId}}` |
| `unsubscribeControllerV4` | `{subscriptionId}` | `undefined` |
| `onDynamicControllerFrame()` | 无参数（**注意：没有 scope 参数**） | 事件流 |
| `mutateTask` | `{address,mutation}` | 任务投影 |
| `deleteArchivedTask` | `{address}` | 删除结果 |
| `deleteArchivedTasks` | `{address,taskIds}` | `{deletedTaskIds,skippedTaskIds,failedTaskIds}` |
| `dispose` | 无 | — |

> 这是 §6.1 里 `fault.controller.*` 的宿主。**网页端 31 个 V4 方法名里的 3 个 controller 方法
> 走的是 `window-controller` 通道，不是 `zcode-agent`** —— 与"方法名全挂在 zcode-agent"的直觉相反。

### 4.3 `zcode-task`（78 法，移动路径被 controller 代理包裹）

`createZCodeTaskServiceAdapter`（`hi:` 偏移 **409,792**）。移动路径下再包
`createControllerRoutedTaskService`（`hi:` 偏移 **1,663,099**，Proxy），覆盖 7 个方法：
`setTaskPinned`、`archiveTask`、`unarchiveTask`、`deleteTask`、`deleteArchivedTasks`、
`deleteArchivedTask`、`setTaskUnread`（改为经窗口 host 控制器做投影 mutation），
`getBotGroupTaskBlockReason`/`cancelBotGroupInput`/`submitBotGroupInput`/`readBotTopicSummaries`
加 scope 包装，其余原样转发。

方法全集（78）：

```
initialize, releaseWorkspacePreparation, checkCodexConnectivity, createTask,
readBotTopicSummaries, invalidateBotTopicInputs, readBotTopicExecution, stopBotTopicExecution,
getBotGroupTaskBlockReason, cancelBotGroupInput, submitBotGroupInput, sendPrompt,
deliverSessionMessage, sendSessionMessageDeliveryResult, enqueueTaskCommand, promoteTaskCommand,
cancelTaskCommand, stopGeneration, compactSession, goalSession, respondPermission,
respondElicitation, closeTask, resumeTask, listTasks, listPinnedTaskIds, listPinnedTasks,
listDeletedTaskIds, listTaskList, createTaskGroup, renameTaskGroup, updateTaskGroupColor,
deleteTaskGroup, listGroupedTaskViewStructure, applyGroupedTaskViewOrder, listArchivedTasks,
archiveStaleTasks, archiveWorkspaceTasks, getTaskSnapshot, getTaskSnapshotWithEtag,
getTaskSnapshotBody, getTaskSnapshotRef, getTaskSnapshotToolCallsSlice, getTaskMeta,
getTaskConfigOptions, getTaskModelSelection, setAssistantMessageFeedback, syncTaskSessionBinding,
switchAgent, scanImportableClaudeSessions, importClaudeSessions, setMode, setConfigOption,
setModel, setAutomationSessionConfig, getWorkspaceProviderConfigFile, getTaskNativeSessionLogFile,
getModelTrajectory, getTaskTokenUsage, getTaskSessionFilePath, restartWorkspaceProcess,
deleteTask, deleteArchivedTask, deleteArchivedTasks, renameTask, setTaskPinned, setTaskUnread,
archiveTask, unarchiveTask, branchTaskFromPrompt,
onDynamicStreamEvent, onDynamicTaskTerminalOutcome, onDynamicTaskReady, onDynamicTaskEvent,
onDynamicWorkspaceEvent, dispose, disposeAll, disposeAllAndWait
```

**`prepareWorkspace` 不在其中**（见 §0.8 / §7.1）。

### 4.4 `conversation-share`（9 法）

`ConversationShareService`（`zc:` 偏移 224,871；桩实现 `createUnsupportedConversationShareService`
在 `t2:` 偏移 ~79,9xx 给出完整接口）：
`getCapabilities`、`preflight`、`publish`、`onDynamicPublishProgress`、`importShare`、
`onDynamicImportProgress`、`getImportedConversation`、`getPreview`、`getContinuation`。
移动路径用 `O8(service,"desktop-continuous"|…, zcodeAgentService)` 做连接作用域包装
（`hi:` 偏移 **1,598,490**）。

### 4.5 其余通道方法名（已逐字枚举）

| 通道 | 方法（原文，含入参形参名） |
|---|---|
| `file` (17) | `readdir, stat, checkFilesExist, resolvePath, createDefaultWorkspace, ensureConversationWorkspace, createScratchWorkspace, readTextFile, readFileRange, readMediaPreview, readBinaryPreview, searchWorkspaceFiles, listWorkspaceFilesLength, listWorkspaceFilesRange, readWorkspaceFileSearchIgnore, applyWorkspaceFileSearchIgnoreTransform, writeWorkspaceFileSearchIgnore` |
| `git` (18) | `getRepositorySummary, getWorkspaceRepositoryInfo, getLocalBranches, getCommitGraph, switchBranch, createBranchAndSwitch, getChanges, getIgnoredPaths, getDiff, getBranchComparison, stagePaths, unstagePaths, discardPaths, generateCommitMessage, commit, push, getIdentity, refresh` |
| `git-checkpoint` (4) | `createCheckpoint, diffCheckpoints, restoreBetweenCheckpoints, deleteCheckpoint` |
| `system` (3) | `info, listIntegratedTerminalShells, probeIntranet` |
| `terminal` (7) | `create, write, resize, dispose, onDynamicData, onDynamicExit, disposeAll` |
| `credential` (2) | `load, save` |
| `broadcast` (2) | `send, tryClaim` |
| `media-preview` (1) | `prepare({path,expectedKind})` |
| `zcode-session` (16) | `initializeWorkspace, getWorkspaceRuntimeIdentity, readWorkspacePresentation, createSession, resumeSession, listSessions, readSession, readSessionMessages, readSessionEvents, promoteDeferredDraftSession, closeSession, closeDeferredDraftSession, setModel, setThoughtLevel, setMode, respondProviderRuntimeHeaders` |
| `file-watcher` (4) | `watch, unwatch, disposeAll, onDynamicChange` |
| `usage-stats` (9) | `getAppUsageSnapshot, getCodingPlanUsageSnapshot, getCodingPlanRegularTps, getCodingPlanResetStatus, requestCodingPlanResetOpportunity, useCodingPlanReset, markCodingPlanResetHistoryRead, getSnapshot, getEntitlementSnapshot` |
| `client-config` (1) | `getSnapshot` |
| `client-scenes` | `getSnapshot`（未完整枚举） |
| `cloud-content` (4) | `readPublishedMedia({asset,kind}), prepare({bundle}), release({leaseId}), disposeAllAndWait` |
| `marketing-touch` (2) | `query({locale}), report` |
| `skills` (6) | `list, setEnabled, buildPromptContext, copyToCommon, removeFromCommon, deleteSkill` |
| `skill-sync` (5) | `listLocalUserSkillCandidates, listRemoteUserSkillStatuses, exportSkillsArchive, checkRemoteUserSkillWriteAccess, importSkillsArchive` |
| `mcp-sync` (8) | `loadMcpFromUserDirectory, listWorkspaceMcpServerStatuses, saveMcpToUserDirectory, listLocalUserMcpCandidates, listRemoteUserMcpStatuses, exportMcpServers, checkRemoteUserMcpWriteAccess, importMcpServers` |
| `plugin-sync` (7) | `listLocalUserPluginCandidates, listRemoteUserPluginStatuses, exportPluginsArchive, exportMarketplaceSourceArchive, importPluginsArchive, checkRemoteUserPluginWriteAccess, importMarketplaceSourceArchive` |
| `plugins` (7) | `getOverview, addMarketplace, removeMarketplace, updateMarketplace, installPlugin, uninstallPlugin, setPluginEnabled` |
| `subagents` (10) | `prepareRuntimeState, list, setEnabled, setBuiltInModelOverride, setPluginAgentModelOverride, getPrimaryUserAgentsDirectory, createAgent, updateAgent, deleteAgent, disposeAll` |
| `settings-sync` (6) | `getClaudeAgentsFileMigrationStatus, copyClaudeAgentsFileToZcodeAgentsFile, detect, importSelected, getFirstRunPromptState, markFirstRunPromptHandled` |
| `prompt-attachment-transfer` (5) | `stage, adopt, cancel, cleanup` + `onProgress`（远端变体） |
| `onboarding-record` (9) | `appendRecord, claimAnonymousRecord, shouldOnboard, dismissOnboarding, getLatestEntry, syncSettingsFromRecord, updateRecordPreferences, getRecords, clearRecords` |
| `bots` (34) | `getRuntimeStatus, imInput, automation, dispose, syncAppRuntimePreferences, getStatus, beginFeishuRegistration, pollFeishuRegistration, beginWeixinRegistration, pollWeixinRegistration, saveConfig, listBots, saveBot, removeBotSecret, deleteBot, testBot, createBindCode, getGroupMemberNames, validateTopicResource, readTopicResource, getTopicDetails, retryTopicPreparation, replyToChannel, getBotStates, reconcileGroupResult, resendGroupResult, setGroupEnabled, retryPrivateDelivery, resetBotState, handleInboundMessage, handleProviderCallback, handleProviderCallbackResponse, disposeAll, disposeAllAndWait` |
| `gen-ui` (1) | `prepareSandbox` |

> 计数口径：上述**已逐字枚举**的通道合计 **428 个成员**（含事件）。
> `setting / oauth / provider-settings / model-selection / provider-provisioning-target /
> cua-permission / off-peak-task / highspeed-card / plugin-management / plugin-ui-* /
> commands / hooks / memory / output-style / feedback / coding-plan-subscription`
> 这 16 条因是「类 + 闭包」形态，本次扫描器只拿到部分名字，**未完整枚举**（坐标已在 §2.3 给出）。

---

## 5. 按功能域分组：我们到底能做什么

| 功能域 | 通道 | 能做什么 |
|---|---|---|
| **对话** | `zcode-agent` | 握手 → 订阅 `conversation/<sid>` → 拉行区间/计划/文件变更/回滚预览 → 发命令（含 CAS `baseRevision`/`baseLogEpoch`）→ 收流式帧 |
| **附件** | `zcode-agent` | begin/chunk/commit/abort 上传（384 KiB/片，≤20 MiB，≤64 片，sha256）、按 ref 分片读、会话内附件读/stat、预览源 |
| **自动化/定时任务** | `zcode-agent` | 列表（全局/按工作区）、创建（cron 或相对延迟）、改、删、启停、重启、立即跑、运行历史 |
| **工作区/存储** | `zcode-agent` + `file` | `prepareStorage` 预热、启动状态、运行时身份、文件读写/搜索/预览 |
| **会话索引** | `zcode-agent` | 订阅 `sessions-index/<workspaceKey>`，快照 + 增量，支持 `base` 续传与 `forceSnapshot` |
| **工作区配置** | `zcode-agent` | 订阅 `workspace-config/<workspaceKey>`（模型/模式等配置变更流） |
| **控制器** | `window-controller` | 订阅/重同步/退订控制器帧；任务投影 mutation（置顶/归档/删除/未读） |
| **诊断** | `zcode-agent` | 进程资源采样、工具执行资源、MCP 资源采样/遥测、CUA 权限观察（**移动路径多数被禁用**） |
| **平台能力** | `system`/`terminal`/`git`/`git-checkpoint`/`credential`/`broadcast` | 系统信息、PTY、Git 全套、检查点、凭据、跨窗广播 |
| **插件/MCP/技能** | `zcode-agent` + `plugins`/`plugin-management`/`plugin-*`/`skills`/`*-sync` | 插件市场与安装、MCP 服务器状态与 UI 实例、技能列表/启停、跨端同步 |
| **分享** | `conversation-share` | 分享预检/发布/导入/预览/续传 |
| **机器人** | `bots` | 飞书/微信机器人注册、绑定码、话题资源、群/私聊回复 |
| **用量/订阅** | `usage-stats`/`coding-plan-subscription` | 应用与编码计划用量、TPS、重置机会 |
| **引导/运营** | `onboarding-record`/`marketing-touch`/`highspeed-card`/`client-config`/`client-scenes`/`cloud-content` | 引导记录、运营位、高速卡、客户端配置/场景、云端内容 |

---

## 6. 入参校验

### 6.1 通道层：零校验

`fromService.call` 只有两步：`typeof i[d]=="function"` → `m.apply(i,h||[])`。
**没有 schema、没有必填检查、没有多余字段拒绝**。多余字段是否报错完全取决于方法体。

### 6.2 方法层：逐方法自愿的 zod

| 方法 | 校验 | 失败表现 |
|---|---|---|
| `initializeConversationV4` | `Hn.parse`（`.strict()`） | ZodError → **202** `{name:"ZodError", message:"[{\"code\":\"unrecognized_keys\",…}]"}` |
| `attachmentReadV4` | `BB.parse` | 同上 |
| `conversationAttachmentReadV4` | `zB.parse`（`target`/`attachmentIndex` 必须成对） | 同上 |
| `conversationAttachmentStatV4` | `$B.parse` | 同上 |
| `attachmentPreviewSourceV4` | `LB.parse` | 同上 |
| `queryConversationCommandsV4` | `VB.parse` | 同上 |
| `backgroundBashOutputV4` | `SB.parse`（`sessionId`+`workId`） | 同上 |
| `attachmentBeginV4`/`ChunkV4`/`CommitV4`/`AbortV4` | **只做 NDJSON 体积检查**（`yy` → `proto.frameTooLarge`） | Error → 202 |
| `subscribeConversationV4` / `unsubscribe` / `resync` | **无** | 缺字段 → 后续 `undefined` 传播，可能抛 TypeError → 202 |
| `conversationRowsRangeV4` / `PlansV4` / `FileChangesV4` / `FileRewindPreviewV4` | **无** | `limit` 超过 200 由 CLI 侧决定 |
| `conversationWorkflow*` | **无** | — |
| `subscribeSessionsIndexV4` / `subscribeWorkspaceConfigV4` | **无** | — |
| `skills.list` | **无**（`provider` 字段被忽略，内部硬编码 `"glm"`） | — |
| `helloConversationV4` | 无入参 | — |

**客户端可见的校验失败只有一种形态**：`202` + `name:"ZodError"` + `message` 是 JSON 数组字符串
（`stack` 也被回传）。对端不会告诉你"哪个字段多余"以外的语义。

### 6.3 桥层（`zcode-agent` 覆盖）额外的前置校验

| 条件 | 错误 message（`name="Error"`） |
|---|---|
| 桥已 dispose | `fault.connection.closed` |
| 未 `initializeConversationV4` 就调业务方法 | `fault.connection.handshakeRequired` |
| 未 `hello` 就 `initialize` | `fault.connection.helloRequired` |
| `initialize` 的 clientId 与首次不同 | `fault.connection.clientChanged` |
| 非 trusted-host-relay 调 `setConnectionFlowStateV4` | `fault.connection.flowControlForbidden` |
| connectionId 空或 >256 | `fault.connection.invalidConnectionId` |
| `sendConversationCommandV4` 的 envelope.clientId ≠ 握手 clientId | `fault.command.clientMismatch` |
| `queryConversationCommandsV4` 无 `clock` 且跨工作区 | `fault.command.queryForeignWorkspace` |
| `resync*` 的 subscriptionId 不属于本连接 | `fault.subscription.notOwned` |
| 订阅首帧暂存溢出（>1024 帧或 >32 MiB） | `fault.subscription.initialFrameStagingOverflow` |
| 运行时重启使订阅失效 | `fault.subscription.runtimeRestarted` |

> `unsubscribe*` 找不到归属时**不报错**（`if(!G)return`）。

### 6.4 超时

| 层 | 超时 | 证据 |
|---|---|---|
| 通道层（已注册通道） | **无** | `ChannelServer.onPromise` 无定时器 |
| 通道层（未知通道） | **1000 ms** → 202 "Unknown channel" | `new Hg(c,"host",1e3,n)`（hi:1,665,4xx） |
| 协议层（`rpc-frame` 重组） | 30 s（`bd.assemblyTimeoutMs`） | 第一批已证，见 `docs/remote-v4/16` §5 |
| 订阅首帧暂存 | 32 MiB / 1024 帧上限，无时间上限 | `ss=1024,as=32*1024*1024`（`t2:` 偏移 58,809） |
| 内部 CLI 请求 | 各方法自带（如 `conversationQueryTimeoutMs` 10 s） | 常量对象 `we`（`chunk-OUCOFHX4.js` 偏移 485,500） |

---

## 7. 与我们的实现的差异

对照：`rust/crates/protocol/src/channel_client.rs`、`automation_client.rs`、
`conversation_transport.rs`、`rust/crates/ffi/src/conversation.rs`（只读）。

### 7.1 我们调了、但对端**没有**的方法（必然失败）

| 我们的调用 | 位置 | 对端事实 | 实际结果 |
|---|---|---|---|
| `zcode-task.prepareWorkspace` | `conversation_transport.rs:131`（常量）、`:1626`（op 分派）、`:2664`（发送） | `zcode-task` 服务 78 个方法里**没有** `prepareWorkspace`；全包唯一出现是内部函数名 `prepareWorkspaceRuntime`（`hi:` 1,603,328），**不是** RPC 方法 | `202 {name:"Error", message:"Method not found: prepareWorkspace"}` |

**修法**：改用 `zcode-agent.prepareStorage({workspacePath, workspaceIdentity?})`
（`hi:` 偏移 333,488，返回 `undefined`，内部 `getClient` + `storageStartup.wait()`）。
若要"预热并拿状态"，配 `getStorageStartupState(scope)` + `onDynamicStorageStartupState(scope)`。

### 7.2 参数/语义不一致（方法存在，但用得不对）

| 项 | 我们的实现 | 对端 | 影响 |
|---|---|---|---|
| `listAllAutomations` 的 scope | `automation_client.rs:349-357` 发 `[{workspacePath,workspaceIdentity?}]` | `listAllAutomations()` **无参数**，实现是 `o.list()`（`hi:` 353,224）→ 不传任何 scope 给仓库；按工作区过滤的是 `listAutomations(g)`（`hi:` 353,181，`o.list(g)`） | 不报错，但**scope 被丢弃**（⚠️ 推测：仓库层无 scope 时返回全集），移动端会看到别的 workspace 的定时任务 |
| `skills.list` 的 `provider` | `conversation_transport.rs:3767` 发 `provider:"glm"` | `createSkillsService.list(n)` 内部硬编码 `o="glm"`，**忽略 `n.provider`**（`hi:` 1,129,609） | 无功能影响；属冗余字段（非 strict schema，不报错） |
| `subscribeConversationV4` 的 `connectionId` | 我们发 hello 拿到的 `connectionId`（`conversation_transport.rs:1175` 附近写入 envelope；订阅 args 里带 scope+connectionId） | 桥用 `A(c,T(c))` **删掉**入参里的 `connectionId/clientMode/deliveryProfile/subscriberScope/workflowRunDeltas`，再用自己的可信连接（`t2:` 58,6xx `withTrustedConnection`） | 不报错；但说明**客户端传 connectionId 无效**，不要依赖它做隔离 |
| `unsubscribeConversationV4` / `resyncConversationV4` 的 `topic` | 只发 `{...scope, subscriptionId}`（`conversation.rs:1480`） | 服务端从自己的订阅表按 `workspaceKey + subscriptionId + topic 前缀` 反查（`hi:` 304,922 `Ln`） | 可用；但 **unsubscribe 找不到时静默 no-op**，我们无法从返回值判断是否真的退订 |
| `rowsRange` 的 `limit` 默认 60 | `conversation_transport.rs:1569` | 上限 200（`rowsRangeMaxLimit`，`chunk-OUCOFHX4.js` 485,500） | 合法，无影响 |
| `attachmentReadV4` 的 `limit` | `conversation.rs:1001` = 384 KiB | 上限 512 KiB（`attachmentChunkMaxBytes`） | 合法，无影响 |
| `initializeConversationV4` 的 `clientKind` | `conversation.rs:161` 固定 `"mobileApp"` | `Hn` 允许 `desktop\|web\|mobileRemote\|mobileApp`，`.strict()` | **合法**；但注意桌面端 `sendConversationCommandV4` 的 `inputOrigin` 是**按 clientMode** 判的（`web-remote-replayable` → `"mobile"`），与 clientKind 无关 |
| `ClientHello` 是否带 `capabilities` | 不带（`conversation.rs:137-165`） | `capabilities.workflowRunDeltas` 决定桥是否把 `workflowRunDeltas` 透传给 CLI（`t2:` 11,072 `Hn` + `Vn`） | 不带 = 不开 workflow run 增量；若要用 `conversationWorkflowRun*` 的增量语义需补 `capabilities:{workflowRunDeltas:true}` |

### 7.3 对端有、我们**完全没实现**的能力（缺口）

按域收敛（括号内为方法数）：

| # | 域 | 缺口 | 数量 |
|---|---|---|---|
| 1 | 控制器（`window-controller`） | `subscribeControllerV4`、`resyncControllerV4`、`unsubscribeControllerV4`、`onDynamicControllerFrame`、`mutateTask`、`deleteArchivedTask`、`deleteArchivedTasks` | **7** |
| 2 | 会话生命周期（`zcode-agent`） | `prepareStorage`、`getStorageStartupState`、`initialize`、`createSession`、`resumeSession`、`listSessions`、`readSession`、`readSessionMessages`、`readSessionEvents`、`closeSession`、`compactSession`、`goalSession`、`setModel`、`setThoughtLevel`、`setMode` | **15** |
| 3 | 对话附加查询（`zcode-agent`） | `queryConversationCommandsV4`、`conversationAttachmentReadV4`、`conversationAttachmentStatV4`、`attachmentPreviewSourceV4`、`backgroundBashOutputV4` | **5** |
| 4 | workflow run（`zcode-agent`） | `conversationWorkflowRunsV4`、`…RunEventsV4`、`…RunArtifactsV4`、`…RunArtifactDataV4`、`…RunArtifactReadV4`、`…RunWorkspaceV4`、`…RunNodeResultV4` | **7** |
| 5 | 工作区配置订阅（`zcode-agent`） | `subscribeWorkspaceConfigV4`、`unsubscribeWorkspaceConfigV4`、`resyncWorkspaceConfigV4`、`onDynamicWorkspaceConfigFrame` | **4** |
| 6 | 事件（`zcode-agent`） | `onDynamicSessionEvent`、`onDynamicProviderRuntimeHeadersRequest`、`onDynamicWorkspaceProviderRuntimeHeadersRequest`、`onDynamicWorkspaceProviderRuntimeHeadersCancelled`、`onDynamicSessionRuntimePreferencesRequest`、`respondProviderRuntimeHeaders`、`respondSessionRuntimePreferences`、`onDynamicStorageStartupState` | **8** |
| 7 | 任务通道（`zcode-task`） | 78 个方法全缺（核心：`listTasks`、`getTaskSnapshot`、`createTask`、`sendPrompt`、`archiveTask`、`setTaskPinned`、`deleteTask`、`renameTask`） | **78** |
| 8 | 分享（`conversation-share`） | `getCapabilities`、`preflight`、`publish`、`importShare`、`getImportedConversation`、`getPreview`、`getContinuation`（+2 事件） | **9** |
| 9 | 媒体预览（`media-preview`） | `prepare({path,expectedKind})` | **1** |
| 10 | 文件/Git/终端/系统（`file`/`git`/`git-checkpoint`/`terminal`/`system`） | 49 个方法全缺（移动端若要"看文件/改文件/跑终端"必须补） | **49** |
| 11 | 用量/订阅/配置（`usage-stats`/`coding-plan-subscription`/`client-config`/`client-scenes`） | 约 12 个 | **~12** |
| 12 | 插件/MCP/技能/子代理/同步 | 约 50 个 | **~50** |
| 13 | 机器人/引导/运营/云端内容/跨窗广播/凭据 | 约 60 个 | **~60** |

**合计**：已逐字枚举的 428 个成员中，我们实现（且对端存在）的只有 **23 个**
（`zcode-agent` 17 + 自动化 6）+ `skills.list` 1 = **24 个**；
缺口 **≥404 个**，其中与移动远控直接相关的**高价值缺口 49 个**
（表中 #1 7 个 + #2 15 个 + #3 5 个 + #4 7 个 + #5 4 个 + #6 8 个 + #8 2 个核心 + #9 1 个）。

### 7.4 我们做对了的（确认项，不必改）

- 类型码 100/101/102/103/200/201/202/203/204 与对端**逐值一致**
  （`channel_client.rs:290-309` ↔ `wmr:` 9,050）。
- `hello`/`initialize`/`sendCommand`/`plans`/`fileChanges`/`rewindPreview`/`rowsRange`/
  `attachmentBegin|Chunk|Commit|Read`/`subscribe|unsubscribe|resync`（conversation + sessions-index）
  的**方法名全部存在**于 `zcode-agent`。
- `ClientHello` 的 5 个键与 `Hn`（strict）**完全吻合**，不会被 ZodError 拒。
- `target` 形状 `{rowId,entityId}` 与 `CG` 一致（`chunk-OUCOFHX4.js` 485,339）。
- `clientId` 在 hello 与 command envelope 中同源（`conversation_transport.rs:1965` / `:2821`），
  不会触发 `fault.command.clientMismatch`。
- 订阅 `topic` 由服务端计算，我们不需要自己拼 `conversation/…`。
- 事件监听名 `onDynamicConversationFrame` / `onDynamicSessionsIndexFrame` 存在且参数用
  `{workspacePath,workspaceIdentity}` 正确（服务端按 workspace key 聚合）。

---

## 8. 仍未证实 / 需要抓包

1. **`setting` / `oauth` / `provider-settings` / `model-selection` / `commands` / `hooks` /
   `memory` / `output-style` / `feedback` / `plugin-management` / `plugin-ui-*` /
   `cua-permission` / `off-peak-task` / `highspeed-card` / `coding-plan-subscription` /
   `client-scenes` 共 16 条通道的方法名未完整枚举**（类/闭包形态，静态扫描器只拿到部分）。
   需要：逐条定位 `i(this,"XxxService")` 的类体并做括号配对，或真机 `listen` 探测。
2. **`gen-ui` 是否在移动远控路径上真正注册**：`qg`（`XP`）在 `R3` 链上，但
   `createGenUiService` 只有 `prepareSandbox`；`exposeOnChannelServer` 会把它注册出去，
   未实测调用是否可用。
3. **`provider-provisioning-target` 是条件注册**（`t || providerProvisioningTargetEnabled===true`），
   移动端是否可见取决于桌面配置，未实测。
4. **`window-controller` 的 `subscribeControllerV4` 入参 schema**：实现里走 `t.subscribe(...)`，
   但 `$8` 内部订阅函数的参数校验未展开；目前只知返回 `{ack:{subscriptionId}}`。
5. **`unsubscribe*` 静默 no-op 的客户端可观测性**：返回 `undefined`，无法区分"已退订"与
   "本来就不属于我"。
6. **`fromService` 用 `for…in` 是否会枚举到原型链上的方法**（如 `constructor`）：
   服务对象多为对象字面量/Proxy，`constructor` 是函数且不以 `on` 开头，理论上可被 `call`；
   未实测（若可调，`constructor` 会返回对象本身）。
7. **`ChannelServer` 在 `listen` 抛异常时的实际表现**：静态看是未捕获异常，
   但 MessagePort 的 `onmessage` 是否把它吞掉/冒泡到进程级 uncaught，需真机抓。
8. **`prepareStorage` 在 `web-remote-replayable` 下是否被桥拦截**：桥的 `Lr` 未覆盖它，
   直接落到基础服务；基础服务 `prepareStorage` 用 `t.getClient(g)`，是否对移动连接可用未实测。
9. **`conversationQueryTimeoutMs`（10 s）等内部超时是否会把错误上抛成 202**：
   取决于 CLI 传输层，静态未定位到抛出点。
10. **桌面端是否对 `rpc-frame` 逻辑帧再叠加长度/条数限制**（除 `rpc-frame` 自身分片预算外）：
    `ChannelServer` 层无限制，`MessagePort` 层无限制；relay 层有 16 MiB 逻辑帧上限。
