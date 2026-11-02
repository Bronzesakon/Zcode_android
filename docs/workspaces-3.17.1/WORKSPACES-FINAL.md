# 3.17.1 工作区 / 任务 / 新对话：最终规格

**基线：`Reference_web/3.17.1/`（2026-10-30 抓取）+ 桌面端 `app.asar`（3.17.1 同发布）。**
**旧基线（3.16.1）只用于 diff；本文件所有结论以 3.17.1 为准。**
**状态：Round1 审计 + Round2 交叉复核 + Round3 实现/线协议审计后的合并结论；Round4 收尾验证见 §11。**

源指纹见 `README.md`。本文只写「实现必须遵守什么」，证据索引在各轮报告里。

---

## 0. 一句话结论

1. `bootstrap-response.workspaces` **不是项目库**，只是「该桌面窗口当前打开的 workspace tab + 当前 runtime target」。
2. 全部项目/任务必须用 **channel `project` + `readTree`**：本地一次，再对 `remote-projects.readDirectory()` 里每个 `online` host 各一次。
3. 非当前 tab 的项目任务/新对话，开桥必须带 **`taskHostId`** 或 **`project`**；桌面端会先 `prepareProjectWorkspace`。
4. 任务基底是 `readTree.tasks`（pinned/archived/unreadAt/title 都在里面）；sessions-index 只叠活动字段。
5. 新对话 = **先 `createSession(workspaceId=权威 workspaceKey)`，再 `sendText`**；首条消息不随 createSession 提交。
6. 桥的权威身份以 **`workspace-bridge-ready.bridge`** 为准，可能与请求的 `workspaceKey` 不同。

---

## 1. 数据源与键规则

### 1.1 bootstrap / workspace-list

- `workspaces[]` 字段：`workspacePath / workspaceIdentity? / remoteSessionId? / label / workspacePurpose? / kind / connectionState? / lastConnectionError?`。
- **没有** `workspaceId / projectId / hostId / roots / pinned / archived`。
- 键：`workspaceKey = workspaceIdentity?.trim() || workspacePath`（只 trim identity）。
- `workspacePurpose` 可能是 `project` 或 `conversation`；conversation 是伪工作区，不参与项目树订阅。

### 1.2 project.readTree

- 通道：`project`；方法：`readTree`；参数是**位置参数数组里的单个对象**：
  `[{ includeDeleted: boolean, includeDeletedTasks: boolean, hostId?: string }]`
  （`channel_call.args` 是位置参数数组，不是「整条消息只有一个对象」；传 `[obj]` 正确。）
- 本地：`hostId` 省略或 `"local"`；远端：`hostId` 必须是 `readDirectory.hosts[].id`。
- 返回：`{ projects: Project[], tasks: Task[], legacySingleRoot?: true }`。
- Project：`id / hostId / name / roots[{rootPath,isPrimary,position}] / pinned / deleted / createdAt / updatedAt`。
- Task：`taskId / workspacePath / workspaceIdentity? / hostId / projectId / title / titleOverridden / status / pinned / archived / unreadAt? / createdAt / updatedAt / lastActivityAt? / provider? / phase? / hasBackgroundWork? / remoteSessionId?`。
- `includeDeletedTasks:true` 只是额外返回 `deletedTasks` 字段，**不会**把已删任务混进 `tasks`。
- 远端 host 列表：`remote-projects.readDirectory()` → `{ hosts:[{id, connectionStatus, …}], entries:[…] }`；只对 `connectionStatus === "online"` 的 host 发 `readTree`。

### 1.3 workspaceIdentity 的构造

- 本地：无 identity，`key = workspacePath`。
- 远端任务行：`workspaceIdentity` 由任务自己的 `workspacePath` 现算，**任务行上的值是权威**。
- 远端项目无任务时：`workspaceIdentity = hostId + ":" + gy(rootPath)`，
  `gy` = 反斜杠转 `/`、折叠 `//`、去首尾 `/`、再补一个 `/`。
  `hostId` 本身就是 identity 去掉末尾 `/<path>` 的部分（如 `remote:server:<id>`）。
- 多根项目：**每个任务用自己的 `workspaceKey` + `taskHostId` 打开**；不要用 primary root 的 key 代替。
- 任务按 `projectId + hostId` 归到项目主根展示；找不到项目时按任务自己的 key 建一个工作区，避免任务消失。

### 1.4 bridgeable

- bootstrap/workspace-list 里的条目：`bridgeable = true`（当前窗口的 tab）。
- `readTree` 补出来的项目/任务/孤儿工作区：`bridgeable = false`（需要 prepare 才能开桥）。

---

## 2. 开桥意图（workspace-bridge-open）

桌面端 `createWorkspaceBridge` 的判定（`out/main/index.js`）：

```
project = body.project ?? (
  (body.fileTreeTaskId || body.taskId) && body.taskHostId
    ? { hostId: body.taskHostId, task: { taskId: body.fileTreeTaskId ?? body.taskId, workspaceKey: body.workspaceKey } }
    : undefined
)
if (project) { prepareProjectWorkspace(...) } else { 从 getAvailableWorkspaces 里按 key 找 }
if (body.project && body.taskId) throw "Project navigation is unavailable."
```

因此合法组合只有四种：

| 场景 | 字段 |
|---|---|
| 当前 tab 工作区 / 主根任务 | `{ workspaceKey }`（可带 `taskId`） |
| 非 tab 项目任务 / 非主根任务 | `{ workspaceKey: task.workspaceIdentity?.trim() ?? task.workspacePath, taskId, taskHostId }` |
| 非 tab 项目里新建对话 | `{ workspaceKey, project: { projectId, hostId? } }`（**不带 taskId**） |
| 无 projectId 的 legacy 项目 | 只能按任务开；**不要伪造 projectId**，也不要在这里新建对话 |

- `taskHostId`：任务的 `hostId`（`local` 或 `remote:server:*`）。
- `project`：`{ projectId, hostId?, draftId?, reuseDraft? }`；没有 projectId 时才用 `projectId:null + draftId + reuseDraft`。
- **权威身份**：`workspace-bridge-ready.bridge.workspaceKey / workspacePath / workspaceIdentity / initialTaskId`。
  `prepareProjectWorkspace` / identity 归一化后，可能与请求 key 不同；`conversation_open` 的 scope、
  `createSession.workspaceId`、`send_mobile_view_state` 都必须用权威值。
- `bridge.initialTaskId` 会覆盖请求里的 `taskId` 用于 `send_mobile_view_state`；宿主不要再用请求值覆盖回去。

---

## 3. 新对话

### 3.1 时序

1. 进草稿页（或点发送时）先备好桥/对话。
2. `createSession`：payload `{ workspaceId: 权威 workspaceKey, config?, timeoutSecs: 90 }`。
   - **不带 `firstText` / `firstInput`**（3.17.1 正常路径；firstInput 只用于崩溃重放/工作流）。
   - 90s 要同时传给核心与宿主：核心 `CREATE_SESSION_TIMEOUT_SECS=90`，宿主只按 `args.timeoutSecs` 兜底。
   - 宿主兜底还要再加 45s 的 `waitHealthy` 预算（桥降级时最坏 135s）。
3. 拿到 `result.sessionId` 后：`subscribe { sessionId }`，`subscribeSessionsIndex`（若还没订）。
4. `sendText`：`{ sessionId, text, mode, planEnabled, modelSelection?, attachments? }`。
   - `mode` ∈ `build | edit | yolo | guarded`；`plan` 归一化成 `build + planEnabled:true`。
   - `modelSelection = { providerId, modelId, options: { reasoningLevel } }`，来自会话 config；新会话无 config 时省略，吃工作区默认。
   - 附件先 `attachmentPut`，再把描述符数组放进 `attachments`。
5. 发送失败：保留已建会话，把文本回填输入框；重试只重发 `sendText`，不重复建会话。

### 3.2 失败矩阵

| 失败点 | 处理 |
|---|---|
| createSession 被拒/超时 | 报错 + 回填文本；下次发送会重新 createSession |
| createSession 成功、subscribe 失败 | 保留 sessionId；消息可能收不到增量，重进页面会补快照 |
| sendText 被拒/超时 | 保留 sessionId 与附件；回填文本，重试走普通 sendText |
| 工作区无 projectId 且非 tab | 明确提示「先在电脑端打开一次该项目」，不发必然失败的 RPC |

---

## 4. 任务、置顶、归档、未读

### 4.1 权威分工

- 存在性 / `pinned` / `archived` / `unreadAt` / `title`（含 `titleOverridden`）→ `readTree.tasks`。
- 活动状态（`phase` / `lastActivityAt` / `hasBackgroundWork` / `pendingInteraction` / `titleSource`）→ sessions-index。
- sessions-index **不含** `pinned / archived / unreadAt / provider`。
- 不要再调 `listPinnedTasks / listArchivedTasks` 重算：它们只是 readTree 的子集，失败还会把标记清零。

### 4.2 合并规则

- 基底永远是 readTree 派生的任务列表。
- sessions-index 叠加时：只更新活动字段、`updatedAt = max`、标题按 `titleSource==='custom' ? 条目标题 : 条目标题或旧标题`；**索引里没有的任务保留**（readTree 基底）。
- readTree 刷新时：readTree 是权威；`unreadAt` 允许为 null（服务端已读），不能再用旧值顶回去；readTree 里没有的任务移除。
- 置顶/归档/未读分组：`pinned && !archived`、`!archived && !pinned`、`unread = unreadAt != null && !archived`。

### 4.3 已读

- RPC：`zcode-task.setTaskUnread({ taskId, workspacePath, workspaceIdentity?, unread:false, expectedUnreadAt })`。
- 本地乐观更新必须允许 `unreadAt = null`；失败回滚。
- 打开任务时带 `expectedUnreadAt`，避免把打开期间的新消息误清。

### 4.4 mutation

- `setTaskPinned / archiveTask / unarchiveTask / renameTask / deleteTask` 都做本地乐观更新；失败回滚该工作区的任务快照。
- 删除任务同时删 `ChatCache` 里对应行；**不要** close 工作区级 conversation（Home 的 sessions-index 预览共用它）。
- zcode-task 是 host 级通道，用哪条桥都可以；参数里的 `workspacePath/workspaceIdentity` 决定路由。

---

## 5. 订阅、超时与并发

- 一个工作区 = 一个桥 + 一个 conversation + 一次 `subscribeSessionsIndex`。
- `subscribeSessionsIndex` 的应答是 `{ ack: { subscriptionId, mode, logEpoch } }`；核心超时 60s，宿主必须按 `args.timeoutSecs=60` 兜底（否则 35s 假超时）。
- 订阅失败（如 `existing-only` 且无活 runtime）**不等于桥不可用**：握手已完成时允许继续 `createSession`；只有 `timeout` 才视为握手失败。
- `helloConversationV4` 是单连接语义：所有建桥/开对话必须串行化（注册表 Mutex），否则第二条 transport 永远等不到应答。
- 非 tab 项目**不在首页展开时订阅**（避免凭空 prepare 草稿），只展示 readTree 的任务；进入对话页时按任务精确 prepare。
- 首页下拉刷新：清空预览订阅表并重拉 `readTree`（同一引擎会话内允许重复拉取）；引擎重建时清空所有桥/对话缓存。

---

## 6. 3.16.1 → 3.17.1 差异（与本链路相关）

| 项 | 3.16.1 | 3.17.1 |
|---|---|---|
| bootstrap-request | 无 `terminalClientSessionId` | 新增 `terminalClientSessionId` |
| createSession payload | 无客户端 `sessionId` | 客户端补 `sess_<uuid>`（是否被服务端采纳 UNVERIFIED） |
| hello capabilities | 无 | 新增 `genUiContext/sessionDirectorySource/sessionWorkspaceRouting/residentRowsRange`；客户端上报 `workspaceHookReviewUi/workflowRunDeltas` |
| 项目草稿 | 无 | `openProjectDraft`（`project:{projectId,hostId?,draftId?,reuseDraft?}`） |
| 重启/恢复 | — | `workspace-reconnect-response` 增加 `workspaceKey` 校验 |

其余 `workspace-list-*` / `window-project` / `readTree` / 任务 schema 无变化。

---

## 7. 已撤销的旧结论（Round1 错误，勿再引用）

1. ~~「`channel_call` 的 args 应该传单个对象，`JsonArray([obj])` 多包了一层」~~ **撤销**：线上 arg 是位置参数数组，`[obj]` 正确；单对象会让服务端 `apply` 展开失败/退化为默认值。
2. ~~「不在当前桌面窗口 tab 里的本地项目不可能开桥」~~ **撤销**：`taskHostId` / `project` 会先 `prepareProjectWorkspace`。
3. ~~「Android 必须调 listPinnedTasks/listArchivedTasks 才能拿到置顶/归档」~~ **撤销**：readTree.tasks 已含；二次计算是故障源。
4. ~~「createSession 预热失败后点发送不会补发」~~ **撤销**：web 会调 `waitForSession → startCurrent` 补发一次。
5. ~~「首条消息必须随 createSession 的 firstInput 提交」~~ **撤销**：正常路径是 createSession 空会话 + sendText。

---

## 8. 实现映射（当前代码）

| 行为 | 位置 |
|---|---|
| 全量项目/任务 | `HomeViewModel.loadProjectTree` / `readTree` / `remoteIdentityOf` |
| 任务基底与合并 | `HomeViewModel.mergeTreeTasks` / `mergeSessionsIndex` / `applyTaskMeta`（已删） |
| 开桥意图 | `Route.Chat` → `ChatViewModel.ensureRouteBridge` → `ConversationRegistry.ensure` → `ZemoteEngine.openBridge` |
| 权威 key | `ConversationRegistry.effectiveWorkspaceKey/effectiveScope`、`Handle.effectiveWorkspaceKey` |
| 新对话 | `ChatViewModel.createConversation` → `subscribeConversation` → `sendText` |
| 提交字段 | `ChatViewModel.putSendSubmission`；Rust `send_text_payload` |
| 已读/置顶/归档 | `HomeViewModel.markRead/setPinned/setArchived/delete`、`ChatViewModel.mutateTask` |
| 通知任务源 | `NotificationCoordinator.refreshFromIndex`（workspace-list 不带 tasks） |
| 桥/对话保活 | `ConversationRegistry`（进程级，一个工作区一条） |

---

## 9. 协议字段速查（线上）

```
workspace-bridge-open: { zcode_type, requestId, bridgeSessionId, bridgeGeneration, workspaceKey,
                         taskId?, taskHostId?, fileTreeTaskId?, project? }
project.readTree args:  [{ includeDeleted, includeDeletedTasks, hostId? }]
remote-projects.readDirectory args: []
createSession args:      { workspaceId, config?, timeoutSecs? }
sendText args:           { sessionId, text, mode, planEnabled, modelSelection?,
                           attachments?, heldQueueDisposition?, expectedHeldQueueItemIds? }
```

---

## 10. 验收清单

- [ ] 首页出现桌面侧栏全部项目（本地 + 在线远端），不只 bootstrap 的 tab。
- [ ] 每个项目展开后任务数与桌面一致；置顶区出现且不含归档；未读点打开后消失。
- [ ] 点开非当前 tab 项目的任务能进入对话并收到历史/增量。
- [ ] 在非当前 tab 项目里新建对话能创建会话，首条消息出现，模型/模式按当前选择。
- [ ] 发送失败时文本回到输入框，重试不重复建会话。
- [ ] 同一工作区不会出现两条 transport（无「只发 hello 无后续帧」）。
- [ ] 删除任务后缓存清除、工作区预览仍工作。
- [ ] 迁移/登录/扫码不受影响。

---

## 11. Round4 收尾

- `round4/01-spec-impl.md`：规格 ↔ 实现一致性；Round3 的 P0 已清，P1 修复项见该报告。
- `round4/02-wire-final.md`：5 条命令的线上 JSON 推导；结论「可交付」，`taskId:null` 不会上线。
- `round4/03-acceptance.md`：验收清单与测试缺口；本轮已补 `draftRestore`、开桥意图、readTree 合并等测试。
- 本机验证：Android 单测 **395 例 / 35 suite 全绿**；Rust `cargo test --workspace --offline` **全绿**
  （ffi 113 + protocol 406 + doctest 1，连跑 5 次稳定）；`cargo ndk -t arm64-v8a` 交叉编译通过，
  `.so` 已更新（sha256 `398d15f6…`）并重打 debug APK（sha256 `1c3ba6dd…`）。
- Rust host 测试跑在 `stable-x86_64-pc-windows-gnu`（本仓库目录 override），本机没有 MSVC linker；
  `.so` 仍由 MSVC 1.99.0 + `aarch64-linux-android` 产出。详见工具链记忆。
- **未真机验证**（本轮手机断开）：13 项目加载、非 tab 项目开桥、新建对话/发送、置顶/未读。
  设备重连后用 `install -r` 覆盖安装验证；**禁止 `pm clear`**（会抹掉配对凭据）。

已知边界（不在本轮修复范围）：

1. `bootstrap.workspaces` 为空（桌面没有打开的 tab）时，没有可开桥的工作区，项目树无法拉取。
2. 非 tab 项目不在首页保持 sessions-index 订阅：任务/未读来自 readTree，返回首页或下拉刷新时更新。
3. 发送在途退出对话页会取消请求（`viewModelScope`），草稿只存在内存；重进后需重发。
4. 多根项目按 projectId 归到主根展示；任务打开使用任务自己的 key/path/identity。
