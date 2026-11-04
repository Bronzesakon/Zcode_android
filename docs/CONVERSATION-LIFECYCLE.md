# 对话生命周期、缓存与历史预取

> 目标：对话在应用进程内保活；打开对话立即有内容；历史按滚动按需补齐；
> 已生成的行保持稳定，只有最后一轮被改写重发时才变化；任务删除时缓存与对话一起释放。
>
> 本版按 `docs/audit/10-conversation-window-audit.md`（2026-11-04）覆写，撤销了
> 「缓存不设上限 / LRU 80 / prefetchHistory 自动拉 200 页」的旧口径。

## 1. 分层

| 层 | 职责 | 生命周期 |
| --- | --- | --- |
| `ConversationRegistry`（Kotlin，App 级） | 按 `workspaceKey` 复用桥 + conversation，订阅 sessions-index（ack 在锁外等） | 进程级；离开页面不关闭 |
| `ConversationTransport`（Rust） | 对话状态机、行窗口、logEpoch | 随 registry 的 close / 进程结束 |
| `ChatCache`（Kotlin，磁盘） | 每个 `(workspaceKey, taskId)` 一份**尾部 400 行** + 元数据（每文件 ≤ 6MiB） | 随任务删除；3 天未写入淘汰 |
| `ChatViewModel` | 绑定 registry、合并缓存/快照、窗口裁剪、驱动 UI | 随页面；重建时从 registry + 缓存恢复 |

## 2. 保活

- `ConversationRegistry.ensure(workspaceKey, taskId, scope)`：
  首次 `openBridge` → `waitHealthy` → `conversationOpen` → `subscribeSessionsIndex`；
  之后同一工作区直接复用。注册表的全局锁内**不等待** sessions-index ack：
  需要 ack 的调用方在锁外等（实测锁内 await 曾把进对话卡 61s，并堵住所有工作区）。
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
- **有界**：每个任务只存尾部 `MAX_ROWS = 400` 行；写入前后都按字节收缩，
  文件上限 `MAX_FILE_BYTES = 6MiB`（超限时从最老端继续丢行，直到能读回）。
  整段历史落盘再整段读进 Compose 是之前低内存闪退的直接原因。
- 只按时间淘汰：超过 3 天未写入的文件在下次写入时删除。
- 打开对话时先读缓存铺满界面，再等 `subscribe` 的首个快照；读盘与建桥并行。
- 每次行变化防抖 600ms 落盘；进后台与 ViewModel 销毁时各补一次立即写盘。

## 4. 快照合并（不可变性的来源）

首个快照与增量 delta 只保证「窗口」；缓存和已翻页的旧行不能被覆盖。

合并规则：

1. 取快照窗口中最老的 `rowId` 作为分界；
2. 保留本地 `rowId < 分界` 的行（缓存/已加载的历史），但要求**相邻**：
   本地保留段的最大 `rowId + 1 ≥ 分界`。断开时（离线期间会话前进了很多行）
   整段丢弃、只留窗口——绝不拼出一段永远补不上的空洞；
3. `logEpoch` 变化说明桌面端日志被重建：不做前缀合并，整窗替换；
4. 分界及之后以快照为准（窗口内被服务端删除/改写的行不会复活）；
5. 按 `rowId` 升序；重复 `rowId` 由时间线的 normalize 保留最后一份；
6. 快照窗口为空时保留本地行（避免一次空帧清空界面）。

`row.appended` 遇到已存在的 `rowId` 按 upsert 替换而不是再追加一条：重连/快照
重叠会重发同一行，重复行会让时间线出现两条同 key 的行。

因此：

- 已生成的行是固定的；
- 只有最后一轮被改写重发时，服务端发 `row.removed(fromRowId)` 或新窗口，
  窗口内才会变化；
- 历史前缀只增不改，除非发生「整段丢弃（洞）/ epoch 重建 / 内存裁剪」。

## 5. 历史加载与窗口上限

**滚动驱动，一次一页：**

- 视觉顶部距内容顶部 ≤ `max(64px, 2×视口高)` 时请求一页；
- 一次「进入阈值区」只发一页（armed），插入历史后视口自然离开阈值区才重新武装；
- 不循环、不按 1.5s 重试：旧实现会在 pin 抖动时自激，把整段历史自动拉完；
- 与用户点击 `loadOlder()` 共用同一把互斥锁与分页 helper，避免并发重复请求；
- 双 CAS：响应 `atLogEpoch` 必须等于当前 `logEpoch`，且当前首行仍是请求游标；
  任一不符整页丢弃；
- 用户在顶部阅读时只发请求、不 pin 视口（reverse 下 prepend 不改变现有 index）。

**内存上界：**

- `MAX_WINDOW_ROWS = 600`，超过后裁到 `TRIM_TARGET_ROWS = 400`（200 行滞回）；
- 只在「可见最顶项距内容顶部 > 3 屏」时裁剪（时间线回报 `setWindowTrimSafe`），
  进入时/贴顶时绝不裁；
- 只从最老端丢弃：reverseLayout 下不改变其余单元 index，不动视口；
- 裁剪同步前移分页游标，`canLoadOlder` 重新成立，下次上翻会重新取被裁掉的段。

**订阅成功判据：** `ack + 首帧快照` 都到手。只有 ack 不算成功（实测存在 ack 正常、
5 分钟零帧的静默失效）；任一超时都走同一条自动恢复路径（重连 + 重开工作区）。

## 6. 删除

`deleteTask()`：

1. `ChatCache.remove(workspaceKey, taskId)`；
2. `ConversationRegistry.close(workspaceKey)`（关闭桥上的 conversation）；
3. 调 `zcode-task.deleteTask`。

## 7. 边界

- 进程被杀：缓存仍在，下次打开先出尾部 400 行；对话由 `openBridge`/`conversationOpen`
  重新建立（服务端幂等）。
- 工作区路径/identity 变化：缓存 key 含 `workspaceKey`，不会串。
- 日志重建（`logEpoch` 变化）：快照整窗替换本地行，`historyExhausted` 复位；
  旧 epoch 的缓存前缀不再合并（避免串 epoch）。
- 缓存与快照之间断开（离线期间会话前进 > 1 行）：丢弃缓存前缀，只留快照窗口，
  由滚动按需回补；宁可少显示，不留补不上的洞。
- 内存压力：窗口超过 600 行且可见区远离最老端时裁到 400；进入对话/贴顶阅读时
  绝不裁剪。
