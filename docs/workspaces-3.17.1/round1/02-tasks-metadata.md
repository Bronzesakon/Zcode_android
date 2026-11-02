# 02 · 任务列表 / 置顶 / 归档 / 未读：3.17.1 真实数据源与 3.16.1→3.17.1 变更

审计方式：**只做静态阅读**。未执行任何快照 JS，未联网。
坐标说明：`Reference_web/**/assets/*.js` 用 **UTF-8 字节偏移**（`rg -b` / `Buffer.indexOf` 同源）；
桌面 asar 用 `out/<file>:<行> @<相对字节偏移>`（`tools/asar.mjs grep` 口径，偏移相对该内部文件）。
桌面 asar 只有 **3.17.1**（`C:\Users\wdn32\AppData\Local\Programs\ZCode\resources\app.asar`），
3.16.1 桌面侧无二进制可比 —— 见 §7 UNVERIFIED。

---

## 1. 结论摘要（先给答案）

1. **3.17.1 的任务基底是「两源分工」，不是单一源**：
   - **存在性 / pinned / archived / unreadAt / titleOverridden** ← `projectService.readTree({includeDeleted:true, includeDeletedTasks:true})` 的 `tree.tasks`。
     host 侧再向内落到 **`zcode-task` 通道的 `listTasks` / `listPinnedTasks` / `listArchivedTasks`**，
     最终读 **SQLite `~/.zcode/v2/tasks-index.sqlite` 的 `tasks` 表**。
   - **实时活动（phase / lastActivityAt / hasBackgroundWork / pendingInteractions / workflowActivity）** ← **sessions-index V4 订阅**
     （`zcode-agent.subscribeSessionsIndexV4`），逐条经 `cvt(entry,{previous})` 转成 Task 并把活动挂到 `__zcodeSessionActivity`。
   - `zcodeTaskService.listTasks/listPinnedTasks/listArchivedTasks` 在 **web 包里只有占位桩**（各 1 处，全在 `jar()` 兜底树里，返回 `[]`）；
     `listTaskList` 是有 controller 时的**预热/兜底查询**，不是主列表来源。
2. **`cvt(entry,{previous})` 在 3.17.1 完全没变**：3.16.1 的 `cvt` 与 3.17.1 的 `Pbt` 除压缩别名（`cvt→Pbt`、`svt→Nbt`、`HO→lk`）外逐字符相同。
3. **`pinned` / `archived` 是 SQLite 列**（`tasks.pinned` / `tasks.archived`），但 **`zcode-task.listPinnedTasks` / `getTaskMeta` / `listTaskList` 的返回体里不带这两个字段**
   —— `rowToMeta` 不注入它们，调用方必须靠自己调的是哪个方法来推断（Android 正是这么做的）。
   只有 `projectService.readTree` 的 tasks 带 `pinned/archived`：legacy 路径手工拼 `{...k,pinned:!1,archived:!1}`，repository 路径从 SQL 列读。
4. **`window-controller` 通道没有 `readActivities` / `list` 方法**。这两个是 web 侧本地注册表
   （`createWindowControllerTaskListRegistry`）的方法：`readActivities()` 读的是 `controller/tasks-index` 动态帧在内存里攒的 map，
   `list()` 才是 RPC，转调 `listTaskList`。真通道方法是 `subscribeControllerV4 / unsubscribeControllerV4 / resyncControllerV4 /
   onDynamicControllerFrame / listTaskList / mutateTask / getTaskMeta / onDynamicTaskUnreadSignal`。
5. **3.17.1 没有新增或改名任何 zcode-task 任务元数据方法**；`provider` 不是调用参数（host 硬编码 `"glm"`），
   `workspaceScopes` 只对 `listTaskList` 必需。Android「置顶区只有两个」与这三件事都无关，候选原因见 §6。
6. **3.16.1→3.17.1 的实差只有 4 处**（都在 web 侧 controller 注册表/读取器）：`list()` 增加 `workspaceScopes==='host'` 直通、
   缓存失效条件增加 `query.kind==='all'`、`sj` 在 host scope 下改用 `getDirectoryRevision`、stub/`cvt`/`readActivities`/`mutateTask` 派发零变化。

---

## 2. 任务数据源与优先级

### 2.1 三层来源

| # | 来源 | 提供字段 | 3.17.1 调用点（web） | 3.17.1 落地（host） |
|---|---|---|---|---|
| A | `projectService.readTree` | 任务全集 + `pinned` + `archived` + `unreadAt` + `titleOverridden` + `hostId` + `projectId` | `Mbt()` @2072273；`vbt()` @2067661；sidebar 首帧/刷新 @2101200 起 | `createProjectService` 的 `readTree`（legacy `out/host/index.js:10 @38229`；repository `out/host/chunk-LNTVTW5D.js @102497` → `ud()` @100574） |
| B | sessions-index V4（`zcode-agent`） | `phase`、`lastActivityAt`、`hasBackgroundWork`、`pendingInteractions`、`workflowActivity`、`title`、`titleSource`、`lastOutcome`、`parentSessionId` | `txt()` @2092835 → `Pbt()`(=`cvt`) @2073406 → `lk()` @1713487 | `subscribeSessionsIndexV4`（`out/host/index.js:86 @597983` 真实现；`:10 @254916` 转发层）；entry schema `pC`（`out/host/chunk-ZSDMP7DM.js:64 @655239`） |
| C | `window-controller` 注册表（仅桌面/有 controller 时） | 活动 overlay + `kind` 分组成员关系 + 未读信号 | `Pdt()` @1856426；`readActivities()` @1858358；`list()` @1858507 | `subscribeControllerV4` 两 topic；`listTaskList` @`out/host/index.js:500 @2010360`；`mutateTask` @`out/host/index.js:500 @2005449` |

### 2.2 优先级（谁覆盖谁）

- **任务是否存在、是否置顶/归档**：**只看 A**（`tree.tasks`）。
  web 分组过滤：`OP()` @2066420 —— `pinned` → `e.pinned && !e.archived`；`archived` → `e.archived`；
  `timeline` → `!e.archived`；`unread` → `gbt()` @2066846（有 `unreadAt`、未归档、且任务属于可见项目）。
- **活动态**：B 覆盖 A 的同名活动字段（`pbt()` 用 `address` 建 map 后 `lk()` 挂载）；
  有 C 时 C 的 `activity` 优先（`pbt(p, s ? s.readActivities() : R.items.flatMap(...))`，`s` = controller 注册表）。
- **未读**：`unreadAt` 来自 A（`tasks.unread_at`）；B 不带 `unreadAt`（entry schema 无此字段），
  所以 `cvt` 里 `...n?.unreadAt?{unreadAt:n.unreadAt}:{}` 是从 **previous**（上一次 A 派生出来的 Task）继承，不是从 entry 读。
- **标题**：`title = titleSource==='custom' ? e.title : (e.title || previous.title)`，
  `titleOverridden = titleSource==='custom' || previous.titleOverridden===true`；A 侧再叠加 SQL 的 `cli_title` 合并逻辑（见 §3.3）。

### 2.3 `cvt(entry,{previous})` 逐字对照（3.16.1 vs 3.17.1）

```
3.16.1 @2026736: function cvt(e,t){let n=t.previous,r=svt(e.phase),i=e.titleSource===`custom`,
  a=n?.titleOverridden===!0&&!i?n.title:e.title||n?.title||``,o=i||n?.titleOverridden===!0?!0:void 0;
  return HO({taskId:e.sessionId,traceId:n?.traceId??`session-${e.sessionId}`,title:a,...o?{titleOverridden:o}:{},
  workspacePath:t.workspacePath,...createdAt:e.createdAt||n?.createdAt||0,updatedAt:e.lastActivityAt||n?.updatedAt||0,
  mode:n?.mode??`default`,...n?.model?{model:n.model}:{},...e.parentSessionId?{forkedFromTaskId:e.parentSessionId}:{},
  ...n?.provider?{provider:n.provider}:{},...r?{status:r}:{},...pendingInteraction...,...n?.unreadAt?{unreadAt:n.unreadAt}:{}},
  {phase:e.phase,lastActivityAt:e.lastActivityAt,hasBackgroundWork:e.hasBackgroundWork,...pendingInteractions,...workflowActivity})}

3.17.1 @2073406: function Pbt(e,t){ ...完全相同，仅 svt→Nbt、HO→lk ... }
```

**结论：语义零变化**（`titleOverridden` 的继承规则、`unreadAt` 只从 previous 继承、`hasBackgroundWork` 只进第二参 activity，均未改）。

### 2.4 去重键（四个不同的键，别混用）

| 位置 | 键 | 证据 |
|---|---|---|
| host `readTree`（legacy） | `JSON.stringify([workspaceIdentity?.trim()\|\|workspacePath, taskId])` | `out/host/index.js:10 @38649`（`new Map(d.flat().map(u=>[JSON.stringify([...]),u]))`） |
| web 项目树合并 | `` `${workspaceIdentity?.trim()\|\|workspacePath}::${taskId}` `` | `ij()` @1854913，`oj()` 用它去重 |
| web 活动/未读匹配 | 同上（`OE()` 走 `DE=_E`，`_E(e,t)=t?.trim()\|\|e`） | `OE()` @1242589、`_E()` @1240296 |
| controller 注册表 | `JSON.stringify([remoteSessionId??'local', workspaceIdentity?.trim()\|\|workspacePath, taskId])` | `jdt()` @1855869 |
| host controller source 索引 | `` `${workspaceIdentity?.trim()\|\|workspacePath}\0${taskId}` `` | `Vy()` `out/host/index.js:500 @2003110` |
| controller 版本签名 | `address + membership + sourceAvailability + meta(去 updatedAt/status/pendingInteraction)` | `Adt()` @1855644 |

---

## 3. 关键实现细节（带原文）

### 3.1 host `readTree` 的 tasks 拼装（问题 6 的直接证据）

`out/host/index.js:10 @38561`（legacy catalog 路径）：

```js
let{paths:a,projects:c}=await r(),d=await Promise.all(a.map(async u=>{
  let p={workspacePath:u},
  [f,m,v]=await Promise.all([e.readTasks({...p,kind:"active"}),e.readTasks({...p,kind:"pinned"}),e.readTasks({...p,kind:"archived"})]),
  S=c.find(k=>k.roots[0]?.rootPath===u)?.id??null;
  return [...f.map(k=>({...k,pinned:!1,archived:!1})),
          ...m.map(k=>({...k,pinned:!0,archived:!1})),
          ...v.map(k=>({...k,pinned:!1,archived:!0}))].map(k=>({...k,hostId:e.hostId,projectId:S}))})),
l=[...new Map(d.flat().map(u=>[JSON.stringify([u.workspaceIdentity?.trim()||u.workspacePath,u.taskId]),u])).values()];
return{projects:c,tasks:l,legacySingleRoot:!0}
```

- `readTasks({workspacePath,kind})`（`out/host/index.js:499 @1933227`）确实转调 `listPinnedTasks / listArchivedTasks / listTasks`。
- **覆盖顺序 = active → pinned → archived，Map 后者覆盖前者**：pinned+archived 的任务最终落成 `pinned:false, archived:true`；
  与 `listPinnedTasks` 自带 `archived=0` 过滤、`listArchivedTasks` 不过滤 pinned 一致。
- `paths` 来源 `catalog()`（`@37100` 附近）：`lastWorkspaceSession(kind==='local')` + `readKnownWorkspaces()` + `recentProjects`，
  并剔除 `workspacePurpose==='conversation'`。

repository 路径 `out/host/chunk-LNTVTW5D.js @102497`：`tasks: e?.includeTasks===!1?[]:ud(this.db).filter(n=>n.hostId===...)`，
`ud()` @100574 直接 `SELECT * FROM tasks WHERE provider=? AND deleted=0 AND coalesce(cli_deleted,0)=0 AND cli_created_at IS NOT NULL`，
映射里显式带 `pinned:e.pinned===1, archived:e.archived===1, unreadAt, titleOverridden, projectId, hostId`。
再经 `jv()`（`out/host/index.js:10 @46715`）按 `workspacePath` 回填 `workspaceIdentity`。

### 3.2 SQLite `tasks` 表与读取过滤（`out/host/chunk-LNTVTW5D.js`）

DB 路径：`me(){return F(ye(),"tasks-index.sqlite")}` @3867，`ye()=join(ne(),'v2')`，`ne()=join(dataBaseDir,'.zcode')`
→ **`~/.zcode/v2/tasks-index.sqlite`**。

`listTaskMetas`（@140750）关键 WHERE：

```sql
WHERE (@workspace_key IS NULL OR workspace_key = @workspace_key)
  AND cli_created_at IS NOT NULL
  AND (@include_deleted = 1 OR (deleted = 0 AND coalesce(cli_deleted,0) = 0))
  -- Bugfix: ZCode Agent 列表只接受当前 glm provider；Claude Code 导入也属于非 glm 历史数据。
  AND (@provider IS NULL OR provider = @provider)
  AND (@pinned IS NULL OR pinned = @pinned)
  AND (@archived IS NULL OR archived = @archived)
ORDER BY cli_updated_at DESC, cli_created_at DESC, task_id DESC
```

`queryTaskList`（@142822）：`kind==='pinned'` → `pinned=1 AND archived=0`；`kind==='archived'` → `archived=1`；
`kind!=='all'` 其他 → `pinned=0 AND archived=0`；`workspaceScopes!=='host'` 且归一化后为空 → 直接返回 `{items:[],total:0,hasMore:false}`；
`workspaceScopes==='host'` → `host_id = storageHostId||'local'`。

`rowToMeta`（@119001）：`{...Pd(r), title: title_overridden===1?title:cli_title??'', titleOverridden: title_overridden===1||cli_title_source==='custom', createdAt, updatedAt, status}`
—— **不含 `pinned` / `archived`**。`Pd()` 里的 `ss` 校验用 `K0`（`out/host/chunk-ZSDMP7DM.js @757156`）也不含这两个字段。

### 3.3 写入路径

- `updateTaskState`（@139057）：`pinned=coalesce(?,pinned), archived=coalesce(?,archived), unread_at=CASE WHEN ? THEN ? ELSE unread_at END, last_unread_at=max(...)`；
  且 `unreadAt` 取 `Math.max(patch.unreadAt, last_unread_at+1, (unread_at??0)+1)` —— 未读时间戳**单调递增**，可当 CAS 令牌。
- `clearTaskUnreadIfMatches`（@137929）：
  `UPDATE tasks SET last_unread_at=max(last_unread_at,unread_at), unread_at=NULL WHERE workspace_key=? AND task_id=? AND deleted=0 AND unread_at=?`，
  返回 `{meta, cleared: changes>0}`。
- `applyCliFacts`（@136109）写入 `cli_title / cli_title_source / cli_created_at / cli_updated_at / cli_last_outcome / cli_deleted`，
  并处理 `handoff_pending` 位（`title` 是否被本地覆盖、是否待删除）——这是 `titleOverridden` 的第二来源。
- 归档/置顶/未读在 host 侧还有一层 `fL` 代理（`out/host/index.js:507 @2160131`）把 `setTaskPinned/archiveTask/unarchiveTask/deleteTask/deleteArchivedTasks/setTaskUnread`
  统一改走 `mutateTask`（远程 attachment scope 时），失败抛 `pin mutation 后 task 投影缺失` 等。

---

## 4. 通道契约（3.17.1 实测静态签名）

### 4.1 `zcode-task`（`ke.ZCodeTask = "zcode-task"`，`out/host/chunk-ZSDMP7DM.js:64 @770262`）

服务对象由 `createZCodeTaskServiceAdapter`（`out/host/index.js:96` 起）注册，`ht.fromService` 全量暴露方法。

| 方法 | 参数 | 返回 | 证据 |
|---|---|---|---|
| `listTasks` | `{workspacePath, workspaceIdentity?}` | `TaskMeta[]`，内部 `{provider:"glm", pinned:false, archived:false}` | `@668617` |
| `listPinnedTaskIds` | **无参** | `string[]`（**跨工作区**，无 workspace_key 过滤） | `@668780` |
| `listPinnedTasks` | `{workspacePath, workspaceIdentity?}` | `TaskMeta[]`（`provider:"glm", pinned:true, archived:false`） | `@668891` |
| `listArchivedTasks` | `{workspacePath, workspaceIdentity?}` | `TaskMeta[]`（`provider:"glm", archived:true`；**不过滤 pinned**） | `@669408` |
| `listTaskList` | `{kind:'pinned'\|'archived'\|'timeline'\|'unread'\|'all', workspaceScopes:[{workspacePath,workspaceIdentity?}]\|'host', sortBy:'updated'\|'created', limit?:number, search?:string}` | `{items:TaskMeta[], total, hasMore}` | `@669302`（`queryTaskList({...C,provider:"glm"})`） |
| `getTaskMeta` | `{workspacePath, workspaceIdentity?, taskId}` | `TaskMeta \| null` | `@672456` |
| `setTaskUnread` | `{workspacePath, workspaceIdentity?, taskId, unread:boolean, expectedUnreadAt?:number}` | `TaskMeta` | `@677778` |
| `setTaskPinned` | `{…scope, taskId, pinned:boolean}` | `TaskMeta` | `@677662` |
| `archiveTask` / `unarchiveTask` | `{…scope, taskId}` | `TaskMeta` | `@678174` |
| `renameTask` | `{…scope, taskId, title}` | `TaskMeta` | `@677502` |

`setTaskUnread` 分支（原文）：

```js
async setTaskUnread(C){
  if(!C.unread&&typeof C.expectedUnreadAt=="number"){
    let ue=await T.clearTaskUnreadIfMatches({taskId:C.taskId,workspacePath:C.workspacePath,
      workspaceIdentity:C.workspaceIdentity,expectedUnreadAt:C.expectedUnreadAt});
    return ue.cleared&&Xt(C,ue.meta,"task_meta_changed"),ue.meta}
  let B=C.unread?Date.now():void 0,G=await be(C,{unreadAt:B});
  return Xt(C,G,"task_meta_changed"),G}
```

要点：**带 `expectedUnreadAt` 的 mark-read 是 CAS**（只有当前 `unread_at` 等于该值才清）；
不带则无条件清（`updateTaskState` 里 `unreadAt:undefined` 仍算 "in patch"，写 `NULL`）。
`TaskMeta` 字段集（`K0` schema，`out/host/chunk-ZSDMP7DM.js @757156`）：
`taskId, traceId, title, titleOverridden?, workspacePath, workspaceIdentity?, workspacePurpose?, createdAt, updatedAt, mode, model?, thoughtLevel?, runtimeEpoch?, provider?, migrationSource?, forkedFromTaskId?, cronAutomationId?, offPeakTaskId?, unreadAt?, status?, lastError?, repairState?, changeSummary?, target?`
—— **没有 `pinned` / `archived` / `hasBackgroundWork`**。

### 4.2 `window-controller`（`ke.WindowController = "window-controller"`）

| 方法 | 参数 | 返回 / 语义 |
|---|---|---|
| `subscribeControllerV4` | `{topic:'controller/workspaces'\|'controller/tasks-index', visibility:'foreground'}` | `{ack:{subscriptionId, logEpoch, mode?}}` |
| `unsubscribeControllerV4` | `{subscriptionId}` | — |
| `resyncControllerV4` | `{subscriptionId, base:{logEpoch,seq}?, forceSnapshot?}` | 新快照帧 |
| `onDynamicControllerFrame()` | — | `{subscriptionId, topic, logEpoch, fromSeq, toSeq, payload:{kind:'snapshot',snapshot:{tasks:[…]}}\|{kind:'deltas',deltas:[…]}}` |
| `listTaskList` | 同 `zcode-task.listTaskList`；`workspaceScopes==='host'` → `readHostTaskList`（**全部本地任务**），否则按 source 逐个 `taskService.listTaskList({...query, workspaceScopes:[scope], limit:undefined})` 后合并 live 状态 | `{items, total, hasMore}` |
| `mutateTask` | `{address:{workspacePath, workspaceIdentity?, taskId, remoteSessionId?, sourceServerId?}, mutation:{kind}}` | `TaskMeta`（`open`/`resume` 返回空） |
| `getTaskMeta` | `{address:{…}}` | `TaskMeta \| null` |
| `onDynamicTaskUnreadSignal()` | — | 后台终态未读信号（`unreadSignal:'background_terminal'`） |

`mutation.kind` 取值：`rename` / `pin` / `archive` / `delete` / `delete-archived` / `delete-archived-batch` / `mark-read` / `mark-unread` / `open` / `resume`
（`out/host/index.js:500 @2005353` switch；`mutateTask` 本体 `@2015559`；`executeMutation` `@2006277`）。
web 侧同一 union 在 `HP()` @2094713 分成 controller 与 service 两条路。

**`readActivities` / `list` 不是通道方法**：注册表工厂 `Pdt()` @1856426 返回
`{subscribe, getRevision, getDirectoryRevision, getSourceRevision, readActivities(), list(key, version, query)}`；
`readActivities()` @1858358 = `Array.from(s.values()).flatMap(e=>e.sourceAvailability==='online'&&e.activity?[{address:e.address,activity:e.activity}]:[])`，
`s` 是 `controller/tasks-index` 快照/增量帧里 `task.upserted/removed` 攒出来的 map（键 `jdt()`）。

### 4.3 mobile / web remote bridge 可用性（源码证据）

- web remote 入口先渲染 `services: jar()`（@6979028），`jar()`（@6962633）的 `zcodeTaskService` 是：
  `listTasks/listPinnedTaskIds/listPinnedTasks/listArchivedTasks → []`、`listTaskList → {items:[],total:0,hasMore:false}`、`getTaskMeta → null`；
  其他方法统一抛 `"<service>.<method> is not available before a Web remote control workspace bridge is connected."`。
  → **没有 workspace bridge 时，web 页面调 `listPinnedTasks` 会静默拿到空数组，不会报错。**
- 有 workspace bridge 时由 `PE()`/`le(services, bridge, 'chat', …)` 换入真实 channel 客户端（`connectionKind==='remote-ready'`）。
- 暴露策略：`H1e` 白名单含 `ke.ZCodeTask` 与 `ke.WindowController`（`out/host/index.js:500 @1956437`，策略对象 `q1e={allowedChannels:H1e}` @1957143，只在 `clientMode==='web-remote-replayable'` 生效），
  **没有按方法名二次白名单**（同类限定的唯一例子是 MCP 的 `K1e` 四方法集）。
  → 结论：**bridge 建好后 `zcode-task` 与 `window-controller` 全方法可用**；仅 channel 级开关。

---

## 5. 3.16.1 → 3.17.1 差异

### 5.1 web 侧（index-DbEHT6Gg.js → index-Dwhx77cK.js）

| 项 | 3.16.1 | 3.17.1 | 判定 |
|---|---|---|---|
| `cvt(entry,{previous})` | `cvt` @2026736 | `Pbt` @2073406 | **零语义变化**（别名不同） |
| 任务基底 `readTree({includeDeleted:!0,includeDeletedTasks:!0})` | `ovt()` @2025653 | `Mbt()` @2072273 | 无变化 |
| `zcode-task` 占位桩整段 | 720 B | 720 B | **逐字节相等**（`listPinnedTasks` 等仍未接真实现） |
| controller `readActivities()` | @1841776 | @1858358 | 无变化 |
| controller `list(t,n,r)` | 无 host 分支 | 新增 `if(r.workspaceScopes===\`host\`)return e.listTaskList(r);` | **新增** |
| 帧驱动缓存失效 | `(!r\|\|go(r.membership,n.query.kind)\|\|i&&go(i.membership,…))` | 追加 `n.query.kind===\`all\`\|\|` | 新增（`kind:'all'` 查询不再被增量帧错误清理） |
| `sj()` 订阅版本 | `getRevision` | `e.scope==='host'?getDirectoryRevision:getRevision` | 新增 host scope 维度 |
| `HP()`/`ixt()`（web 侧 mutate/getTaskMeta 分派） | `uP`/`Avt` | `HP`/`ixt` | 逻辑相同（controller 优先，否则逐个 service 方法） |
| controller topics | `controller/workspaces` + `controller/tasks-index`（`parts-BrfQr2ul.js`） | 同名（`parts-Ba2kLaRj.js`） | 无变化 |
| 计数对比 | `listTaskList` 2 处 / `workspaceScopes` 3 处 | 3 处 / 5 处 | 与上表 3 处新增吻合 |

### 5.2 桌面 host（仅 3.17.1 有二进制）

3.16.1 桌面 asar 不存在，不能做逐字节 diff。可确认的是 3.17.1 host 的实现口径：
- `listPinnedTasks` 带 `provider:"glm"` 与 `archived:0`；`listPinnedTaskIds` 无 workspace 过滤；`listArchivedTasks` 不带 pinned 过滤。
- `out/host/index.js:507 @2142833` 的 Proxy 把 `listTasks/listPinnedTasks/listTaskList/listArchivedTasks/getTaskMeta/getTaskSnapshot*` 统一加缓存/序列化包装。
- 3.15.x 归档提取件 `archive/pre-3.16.1-auth/docs/_audit/desktop-extract/host-index.js:88` 里
  `listPinnedTasks(y){return(await O.listTaskMetas({workspacePath:y.workspacePath,workspaceIdentity:y.workspaceIdentity,provider:fo,pinned:!0,archived:!1…`
  **结构与 3.17.1 相同**（`fo` 同为首字母缩写的 provider 常量）→ 这条硬编码 provider 过滤不是 3.17.1 新增，至少 3.15 就存在。

---

## 6. 「Android 调 `zcode-task.listPinnedTasks` 后置顶区只有两个」分析

**先排除（有证据）**：

| 假设 | 证据 | 结论 |
|---|---|---|
| 3.17.1 把方法改名/删了 | `zcode-task` 桩 720 B 与 3.16.1 逐字节相等；host 侧同名方法仍在（`@668821`） | 排除 |
| 必须由调用方传 `provider` | host 硬编码 `provider: ta`（`ta=ro`，`ro=qr="glm"`，`out/host/chunk-ZSDMP7DM.js @477419`） | 排除（传了也会被覆盖） |
| 必须带 `workspaceScopes` | `workspaceScopes` 只出现在 `queryTaskList`/`listTaskList`；`listPinnedTasks` 签名只有 `{workspacePath, workspaceIdentity?}` | 排除 |
| Android 解析错返回体 | Android `metaTasks()` 同时接受数组与 `{items\|tasks}`；host 返回数组 | 排除 |

**仍成立的过滤器（按可疑度排序，全部来自 3.17.1 host 源码）**：

1. `provider = "glm"`（硬编码）。任何 `provider` 非 glm 的历史/导入任务永远不会出现在 pinned/archived 列表里
   （SQL 注释明写：`ZCode Agent 列表只接受当前 glm provider；Claude Code 导入也属于非 glm 历史数据`）。
2. `cli_created_at IS NOT NULL`。任务必须先被 `applyCliFacts` 写过 CLI 事实（`out/host/index.js:494 @1785220` 的 reconcile 循环）
   才进列表；只有 `writeRecord`、没跑过 reconcile 的任务会被过滤掉。
3. `archived = 0`。**pinned + archived 的任务不会出现在 pinned 列表**（只会出现在 archived）。
4. `workspace_key` 精确等于 `L({workspacePath, workspaceIdentity})`，其中 `L=R`、`R` 是统一的 workspaceKey 归一化
   （`chunk-LNTVTW5D.js @3867` 附近；`la(r,e)=e?.trim()||r`）。路径大小写/分隔符/尾斜杠不一致会整体查空，不会只差两个。
5. `deleted=0 AND coalesce(cli_deleted,0)=0`。

**建议的判定方法（未执行）**：直接查 `~/.zcode/v2/tasks-index.sqlite`

```sql
SELECT task_id, provider, pinned, archived, deleted, cli_deleted, cli_created_at, workspace_key, unread_at
FROM tasks WHERE pinned=1 ORDER BY cli_updated_at DESC;
```

- 若只有 2 行 → 库里就只有 2 个 pinned（**不是 3.17.1 的 bug**；其余任务从未被置顶或已被取消置顶）。
- 若行数 > 2 且 `provider<>'glm'` 或 `cli_created_at IS NULL` 或 `archived=1` → 命中上面的过滤器。
- 若行数 > 2 且全为 glm/有 cli_created_at/archived=0 → 查 `workspace_key` 是否与 Android 传的 `{workspacePath, workspaceIdentity}` 归一化后一致；
  若一致则说明是 Android 侧合并逻辑（`applyTaskMeta`）覆盖问题，需要抓 bridge 请求/响应。

---

## 7. 证据索引

### 7.1 `Reference_web/3.17.1/assets/index-Dwhx77cK.js`（6,986,617 B）

| 偏移 | 符号 | 用途 |
|---:|---|---|
| 1854913 | `function ij(e)` | web 任务去重键 `${identity\|\|path}::${taskId}` |
| 1855644 | `function Adt(e)` | controller 任务版本签名 |
| 1855869 | `function jdt(e)` | controller 帧去重键（含 `remoteSessionId\|\|'local'`） |
| 1856426 | `function Pdt(e)` | controller 任务列表注册表工厂（subscribe/readActivities/list） |
| 1858358 | `readActivities(){…}` | 活动 overlay 来源（online + activity） |
| 1858507 | `list(t,n,r){…}` | **3.17.1 新增** `workspaceScopes==='host'` 直通 |
| 2022766 | `function ovt(e)` | 3.16.1 同名函数坐标（对照用） |
| 2065926 | `function EP(e,t)` | 项目树浅合并（`oj` 去重） |
| 2066420 | `function OP(e,t)` | **pinned/archived/timeline/unread 分组过滤** |
| 2066846 | `function gbt(e)` | unread 分组（未归档 + 有 unreadAt + 项目可见） |
| 2067231 | `function kP(e)` | 项目键 `[hostId,id]` |
| 2067661 | `function vbt(e,t,n)` | 多 host 项目树合并 |
| 2072273 | `function Mbt(e)` | `projectService.readTree({includeDeleted,includeDeletedTasks})` + 远端 host 合并 |
| 2073223 | `function Nbt(e)` | phase → status 映射（running/completed/error） |
| 2073406 | `function Pbt(e,t)` | **`cvt` 等价体**（3.17.1 名称） |
| 2092835 | `function txt(e)` | sessions-index → Task 列表（`previous` 用 `nxt` 键，`::` 分隔） |
| 2105082 | `s.list('project-sidebar-activity', …, {kind:'timeline', workspaceScopes, sortBy:'updated', limit:0})` | controller 活动预热（**唯一** listTaskList 常规调用） |
| 2105833 | `s?s.readActivities():R.items.flatMap(…)` | 活动源优先级 |
| 6962633 | `function jar()` | web remote 兜底服务树 |
| 6963359 | `listPinnedTaskIds:async()=>[]` | 桩 |
| 6963389 | `listPinnedTasks:async()=>[]` | 桩 |
| 6963417 | `listTaskList:async()=>({items:[],total:0,hasMore:!1})` | 桩 |
| 6963685 | `listArchivedTasks:async()=>[]` | 桩 |
| 6963907 | `getTaskMeta:async()=>null` | 桩 |
| 6979028 | `services:jar()` | web remote 启动时注入兜底树 |

### 7.2 `Reference_web/3.16.1/assets/index-DbEHT6Gg.js`（6,836,503 B）

| 偏移 | 符号 | 用途 |
|---:|---|---|
| 1841776 | `readActivities(){…}` | 与 3.17.1 同文 |
| 1841925 | `list(t,n,r){…}` | **无** host 分支 |
| 2025653 | `function ovt(e)` | readTree 合并（= 3.17.1 `Mbt`） |
| 2026736 | `function cvt(e,t)` | `cvt` 原文（与 3.17.1 `Pbt` 同） |
| 2047917 | `function Avt(e,t,n)` | controller 优先的 getTaskMeta |
| 2048033 | `function uP(e,t,n,r)` | mutation 分派（= 3.17.1 `HP`） |
| 6813238 | `listPinnedTaskIds:async()=>[]` | 桩（整段与 3.17.1 逐字节相等，720 B） |

### 7.3 桌面 asar（3.17.1，`out/host/*`）

| 位置 | 原文要点 |
|---|---|
| `out/host/index.js:10 @38229` | legacy `readTree`：`e.readTasks({...p,kind:"active"/"pinned"/"archived"})` |
| `out/host/index.js:10 @38561` | `{...k,pinned:!1,archived:!1}` / `pinned:!0,archived:!1` / `pinned:!1,archived:!0` 拼装 |
| `out/host/index.js:10 @38649` | 去重 `JSON.stringify([workspaceIdentity?.trim()\|\|workspacePath, taskId])` |
| `out/host/index.js:10 @46715` | `jv()` 按 workspacePath 回填 workspaceIdentity |
| `out/host/index.js:86 @597983` | `listTaskMetas({…,provider:"glm"}).slice(0,QT)` 作为 sessions-index 的 `legacyTaskIds` 归属证明 |
| `out/host/index.js:494 @1785220` | reconcile → `applyCliFacts({…provider:ro…})`（`ro="glm"`） |
| `out/host/index.js:499 @1933227` | `readTasks`：`kind==='pinned'?listPinnedTasks:kind==='archived'?listArchivedTasks:listTasks` |
| `out/host/index.js:500 @2005449` | controller `mutateTask` 分派（rename/pin/archive/delete/mark-read/mark-unread…） |
| `out/host/index.js:500 @2008327` | `readSourceTaskIndex`：三列表 → `membership:{pinned,archived,active}`（pinned 覆盖在最后；函数名标记 @2008818） |
| `out/host/index.js:500 @2010300` | controller `listTaskList`：`workspaceScopes==='host'` → `readHostTaskList`；否则逐 source 查询（`listTaskList:D` @2013581） |
| `out/host/index.js:500 @1956437` | `H1e` web-remote 频道白名单（含 ZCodeTask、WindowController；策略 @1957143） |
| `out/host/index.js:507 @2157557` | `readHostTaskList` → `Gt.get(Kn).listTaskList(e)` |
| `out/host/index.js:507 @2159841` | `fL` 代理：pin/archive/delete/setTaskUnread 改走 `mutateTask` |
| `out/host/index.js:96 @668617-669564` | zcode-task facade：`listTasks`/`listPinnedTaskIds`(668780)/`listPinnedTasks`(668891)/`listTaskList`(669302)/`listArchivedTasks`(669408) |
| `out/host/index.js:96 @672456` | `getTaskMeta → Vn(T.getTaskMeta(C))` |
| `out/host/index.js:96 @677778` | `setTaskUnread`（CAS 分支） |
| `out/host/index.js:96 @649088` | `Vn = rememberIndexedTaskMeta`（返回 meta 原样） |
| `out/host/chunk-LNTVTW5D.js:1 @3867` | `me(){return F(ye(),"tasks-index.sqlite")}` |
| `out/host/chunk-LNTVTW5D.js:746 @102497` | project repository `readTree`（tasks ← `ud`） |
| `out/host/chunk-LNTVTW5D.js:1 @100574` | `ud()`：`provider=? AND deleted=0 AND coalesce(cli_deleted,0)=0 AND cli_created_at IS NOT NULL` |
| `out/host/chunk-LNTVTW5D.js:967 @136109` | `applyCliFacts`（写 `cli_created_at/title_overridden/handoff_pending`） |
| `out/host/chunk-LNTVTW5D.js:976 @137929` | `clearTaskUnreadIfMatches`（CAS SQL） |
| `out/host/chunk-LNTVTW5D.js:978 @140750` | `listTaskMetas` SQL（含 glm 注释与 `cli_created_at IS NOT NULL`） |
| `out/host/chunk-LNTVTW5D.js:1014 @142822` | `queryTaskList`（kind 过滤 + `workspaceScopes==='host'`） |
| `out/host/chunk-LNTVTW5D.js @119001` | `rowToMeta`（**无** pinned/archived） |
| `out/host/chunk-LNTVTW5D.js @139057` | `updateTaskState`（pin/archive/unread 写路径） |
| `out/host/chunk-ZSDMP7DM.js:64 @477419` | `var qr="glm"`，`Cs=[qr]`（默认 provider） |
| `out/host/chunk-ZSDMP7DM.js:64 @655239` | sessions-index entry schema `pC`（phase/hasBackgroundWork/titleSource/…） |
| `out/host/chunk-ZSDMP7DM.js:64 @757156` | Task meta schema `K0`（无 pinned/archived/hasBackgroundWork） |
| `out/host/chunk-ZSDMP7DM.js:64 @770262` | channel 枚举 `ZCodeTask:"zcode-task", WindowController:"window-controller"` |

---

## 8. UNVERIFIED 清单

1. **3.16.1 桌面 host 无法比对**：本机只有 3.17.1 asar；§5.2 的 host 结论只对 3.17.1 成立，
   3.16.1 桌面侧是否同样硬编码 `provider="glm"`、是否有 `cli_created_at IS NOT NULL`
   只能由 3.15.x 归档件旁证（结构相同），**不能**当作 3.16.1 的确证。
2. **「置顶只有两个」的真实原因未验证**：需要 `~/.zcode/v2/tasks-index.sqlite` 的 `tasks` 行；
   本审计未连设备、未读用户数据库（§6 给了判定 SQL）。
3. `ht.fromService` 是否对服务对象方法做过滤（例如跳过 `_` 前缀）未逐行确认；
   只确认 `exposeOnChannelServer` 做 channel 级白名单。
4. `PE()`（web 侧 per-workspace 服务客户端）是否把 `zcode-task` 也纳入 `connectionKind==='remote-ready'`
   未逐行确认（只确认 `services:jar()` 是首帧兜底、且 `_An()` 会直接调 `services.zcodeTaskService.getTaskMeta`）。
5. web remote 经 relay 时，`window-controller` 的两个 topic 是否都会下发 `controller/tasks-index` 帧，
   取决于 host 端 `zee/Nee` 订阅结果；本审计只确认订阅调用存在，未见运行期帧日志。
6. Android 端 `HomeViewModel.loadTaskMeta` 的调用时机（首帧/刷新/重连各一次？）未逐一核对，
   只核对了参数构造与合并逻辑（`workspacePath` + 可选 `workspaceIdentity`，接受数组或 `{items|tasks}`）。
7. 3.17.1 是否在 `tasks` 表 schema 上新增列：迁移清单里未见 3.17.x 的 `tasks` 迁移项
   （`0007/0008/0009` 只动 `projects/remote_project_entries/session_creation_commands`），
   但更早的迁移文件未逐条读完，**不能断言列集未变**。
