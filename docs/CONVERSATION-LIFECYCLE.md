# 对话生命周期、缓存与历史预取

> 目标：对话在应用进程内保活；打开对话立即有内容；历史轮次静默补齐；
> 已生成的行保持稳定，只有最后一轮被改写重发时才变化；任务删除时缓存与对话一起释放。

## 1. 分层

| 层 | 职责 | 生命周期 |
| --- | --- | --- |
| `ConversationRegistry`（Kotlin，App 级） | 按 `workspaceKey` 复用桥 + conversation，订阅 sessions-index | 进程级；离开页面不关闭 |
| `ConversationTransport`（Rust） | 对话状态机、行窗口、logEpoch | 随 registry 的 close / 进程结束 |
| `ChatCache`（Kotlin，磁盘） | 每个 `(workspaceKey, taskId)` 一份完整行窗口 + 元数据 | 随任务删除；LRU 上限 80 个任务 |
| `ChatViewModel` | 绑定 registry、合并缓存/快照、静默预取、驱动 UI | 随页面；重建时从 registry + 缓存恢复 |

## 2. 保活

- `ConversationRegistry.ensure(workspaceKey, taskId, scope)`：
  首次 `openBridge` → `waitHealthy` → `conversationOpen` → `subscribeSessionsIndex`；
  之后同一工作区直接复用。
- 离开对话页只解除 UI 绑定，**不**调用 `conversationClose`。
- 只有任务删除（`deleteTask`）调用 `registry.close(workspaceKey)`；
  应用进程结束由系统回收。
- 断线重连由核心的桥恢复机制处理；registry 不做隐式重建，调用方捕获
  `invalidState` 后重新 `ensure`。

## 3. 缓存（schema v2）

```json
{
  "version": 2,
  "workspaceKey": "...",
  "taskId": "...",
  "savedAt": 0,
  "logEpoch": "...",
  "firstRowId": 0,
  "rows": [ ... ]
}
```

- 原子写：临时文件 + rename；写失败静默（缓存是尽力而为）。
- **不设行数/文件数/体积上限**：长对话经过多轮上下文压缩后仍要保留完整历史。
- 只按时间淘汰：超过 3 天未写入的文件在下次写入时删除。
- 打开对话时先读缓存铺满界面，再等 `subscribe` 的首个快照。
- 每次行变化防抖 600ms 落盘；流式帧不逐帧写。

## 4. 快照合并（不可变性的来源）

首个快照与增量 delta 只保证「窗口」；缓存和已翻页的旧行不能被覆盖。

合并规则：

1. 取快照窗口中最老的 `rowId` 作为分界；
2. 保留本地 `rowId < 分界` 的行（缓存/已加载的历史）；
3. 分界及之后以快照为准（窗口内被服务端删除/改写的行不会复活）；
4. 按 `rowId` 升序；
5. 快照窗口为空时保留本地行（避免一次空帧清空界面）。

因此：

- 已生成的行是固定的；
- 只有最后一轮被改写重发时，服务端发 `row.removed(fromRowId)` 或新窗口，
  窗口内才会变化；
- 历史前缀只增不改。

## 5. 静默历史预取

- 首个快照到达后启动 `prefetchHistory()`。
- 循环 `rowsRange(beforeRowId = 当前最老行, limit = OLDER_PAGE_SIZE)`，直到
  `hasMore == false`、返回空、任务切换或达到 200 页上限。
- 双 CAS：响应 `atLogEpoch` 必须等于当前 `logEpoch`，且当前首行仍是请求游标；
  任一不符整页丢弃。
- 静默：不设置 `loadingOlder`、不显示 spinner/占位/错误横幅；失败即停，
  下一次快照或用户滚动可重试。
- 与用户触发的 `loadOlder()` 共用同一把互斥锁和分页 helper，避免并发重复请求。
- 切任务时取消预取 Job。

## 6. 删除

`deleteTask()`：

1. `ChatCache.remove(workspaceKey, taskId)`；
2. `ConversationRegistry.close(workspaceKey)`（关闭桥上的 conversation）；
3. 调 `zcode-task.deleteTask`。

## 7. 边界

- 进程被杀：缓存仍在，下次打开先出历史；对话由 `openBridge`/`conversationOpen`
  重新建立（服务端幂等）。
- 工作区路径/identity 变化：缓存 key 含 `workspaceKey`，不会串。
- 日志重建（`logEpoch` 变化）：`historyExhausted` 复位，预取重新开始；
  旧 epoch 的缓存行仍可作为首屏兜底，但会被新窗口按上述规则替换。
