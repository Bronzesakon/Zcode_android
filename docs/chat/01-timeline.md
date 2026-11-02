# 网页版对话时间线：滚动与历史加载行为逐条取证（3.15.3）

> 审计基线：3.15.3（`E:\Zcode_android\archive\pre-3.16.1-auth\Reference_web\3.15.3\`，只读）
> 主包：`assets/index-BGa3KiRe.js`（6,592,643 B）
> 样式：`assets/index-BD2BL4Cc.css`（455,351 B）
> 虚拟器：`assets/esm-DhX58A8k.js`（TanStack Virtual 打包产物）
> 按钮尺寸：`assets/button-B-6dbTqe.js`
> 文案：`assets/IntlProvider-C-26GNLk.js`
> **偏移口径：UTF-8 字节偏移**（用 `Buffer.indexOf` 复现；本文所有 `文件:偏移` 均为字节偏移，不是字符偏移）。
> 交叉核对：`archive/pre-3.16.1-auth/docs/remote-v4/18-remote-v4-功能域-对话渲染与流式更新.md` 第 201/331 行（overscan=8）、第 380–401 行（DOM 属性表）、第 451 行（活尾切分）。
> 审计日期：2026-10-17。方法：字符串切片 + 正则定位，未执行应用代码。

---

## 0. 结论摘要（先看这 12 条）

| # | 结论 | 关键证据 |
|---|---|---|
| 1 | 跟随状态是组件内的 `useRef(true)` + 一个派生 state；`data-following` 写的不是 `following` 本身，而是「是否显示回到底部按钮」的反值 | `index-BGa3KiRe.js:3454108`、`3454498`、`3458180`、`3470510` |
| 2 | 「贴底」判定为 `contentHeight - viewportHeight - scrollTop <= 48px`；48px 是 follow 的唯一距离阈值 | `index-BGa3KiRe.js:3435876`、`3435956` |
| 3 | 滚轮/触摸/键盘上滚会**立即**退出跟随（1px 即可）；只有滚动条拖动/普通 scroll 事件才用 48px 判据 | `index-BGa3KiRe.js:3458604`、`3458780`、`3458899`、`3459069` |
| 4 | 跟随中，新行/流式增高会自动滚底；不跟随则完全不滚，只出现按钮 | `index-BGa3KiRe.js:3461589`、`3469020`–`3469290`、`3463480`–`3463800` |
| 5 | 更早历史的预取阈值是 `max(64, 2 × clientHeight)` 像素，不是「距顶多少行」 | `index-BGa3KiRe.js:3437263`、`3464673` |
| 6 | 没有 `historyExhausted` 符号；历史是否耗尽由 `canLoadOlder = window[0].rowId > rows.firstRowId` 表达 | `index-BGa3KiRe.js:1123202`、`3684888` |
| 7 | 没有加载骨架/占位行；只有 `data-loading-older` 属性。未测量单元按 72px 估算，测量后修正 | `index-BGa3KiRe.js:3470545`、`3457152`、`3472465` |
| 8 | prepend 后靠「锚点 key + 偏移差 + 总高差」在 layout effect 里手动补偿；同时 `overflow-anchor:none` | `index-BGa3KiRe.js:3464890`、`3468468`–`3468990`、`3471549` |
| 9 | `rowsRange` 的 CAS 是 `atLogEpoch` + 当前 `window[0].rowId` 双校验；不匹配整批丢弃，无内建重试 | `index-BGa3KiRe.js:1136779`、`1136902` |
| 10 | 用户看历史时**没有**新消息 pill、计数、未读分割线；唯一提示是「回到底部」按钮 | 全量搜索 0 命中：`newMessages`/`newMessageDivider`/`unreadDivider`/`data-v4-new`；按钮 `3474140` |
| 11 | 回到底部按钮：lucide `arrow-down`、16px 图标、28px 圆形按钮、无显隐动画；有 dock 时悬浮在 dock 上方 8px，无 dock 时距底 12px | `81834`、`3450639`、`3474158`、`3474366`、`button-B-6dbTqe.js:4676` |
| 12 | 虚拟化：`overscan=8`、`estimateSize` 缓存值否则 72px、`getItemKey=unit.key`、`scrollMargin=header 高度`；运行中的最后一个 turn 被切出虚拟列表做 live tail | `3457420`、`3457152`、`3457102`、`3457521`、`3424138` |

---

## 1. Follow（跟随）状态机

### 1.1 状态载体与常量

| 项 | 值/语义 | 证据 |
|---|---|---|
| 跟随真值 | `we = useRef(LZ())`，`LZ()` 恒返回 `true`；即挂载时默认跟随 | `index-BGa3KiRe.js:3454108`、`3436921` |
| 按钮可见 state | `[Re, ze] = useState(false)`；`Re` 不是 following，而是「不跟随且当前有渲染单元」 | `index-BGa3KiRe.js:3454498`、`3436889` |
| 派生公式 | `ze(lrn(e, units.length))`，`lrn(e,t) = !e && t>0` | `index-BGa3KiRe.js:3458180`、`3436889` |
| 贴底 epsilon | 默认 48px | `index-BGa3KiRe.js:3435956`、`3435993` |
| 回退 epsilon | `scrollEpsilonPx` 默认 2px | `index-BGa3KiRe.js:3436663` |
| intent 过期 | `Xrn = 1200ms` | `index-BGa3KiRe.js:3450343` |
| layout 滚动窗口 | `Zrn = 250ms` | `index-BGa3KiRe.js:3450343` |
| 容器宽度变化去抖 | `Qrn = 120ms` | `index-BGa3KiRe.js:3450343` |
| 恢复判定容差 | `$rn = 1px` | `index-BGa3KiRe.js:3450343` |

贴底函数原文（`index-BGa3KiRe.js:3435876`）：

```js
function trn(e){return Math.max(0,e.contentHeight-e.viewportHeight-e.scrollTop)}
function FZ(e,t=48){return trn(e)<=t}
```

`IZ()` 是 follow 的纯函数归约器（`index-BGa3KiRe.js:3436663`）：

```js
function IZ(e){
  let t=e.userScrollIntent??`unknown`;
  return t===`awayFromBottom` ? false
    : t===`none` ? e.following
    : FZ(e.metrics, e.bottomEpsilonPx??48) ? true
    : e.metrics.scrollTop < e.lastObservedScrollTop-(e.scrollEpsilonPx??2) ? false
    : e.following;
}
```

### 1.2 进入 / 退出跟随的条件

| 方向 | 条件 | 是否立即 | 证据 |
|---|---|---|---|
| 退出 | 滚轮 `deltaY < 0`（向上滚）且内容可滚 | 立即（1px 即可），`dt(false)` | `index-BGa3KiRe.js:3458604`、`3458780`、`3436273` |
| 退出 | 触摸拖动：新 `clientY > 旧 clientY`（手指下拉看更早） | 立即 | `index-BGa3KiRe.js:3458899`、`3436343` |
| 退出 | `ArrowUp` / `PageUp` / `Home` / `Shift+Space` | 立即（按键后） | `index-BGa3KiRe.js:3459069`、`3436415` |
| 退出 | 用户 scroll 事件且距底 `> 48px` | 该次 scroll 事件 | `index-BGa3KiRe.js:3464239`、`3436029` |
| 退出 | 非 away/none intent 下，`scrollTop` 比上次观测回退 `> 2px` 且不在 48px 内 | 该次 layout effect | `index-BGa3KiRe.js:3436663` |
| 退出 | 恢复非贴底会话时 `Mt(state)` 强制 `we.current=false` | 挂载恢复 | `index-BGa3KiRe.js:3464054` |
| 进入 | 用户 scroll 到距底 `<= 48px` | 该次 scroll 事件 | `index-BGa3KiRe.js:3464239`、`3436029` |
| 进入 | 点击「回到底部」→ `Pt()` 内 `dt(true)` | 点击时 | `index-BGa3KiRe.js:3465128` |
| 进入 | 挂载/会话切换时保存状态为 `wasPinnedToBottom === true`（或没有记忆）→ 强制 follow + `At()` | layout effect | `index-BGa3KiRe.js:3467686` |
| 进入 | 渲染单元数为 0（且无待恢复状态）→ 重置为 true | effect | `index-BGa3KiRe.js:3469456` |
| 不改变 | `source` 为 `programmatic` 或 `layout` 的 scroll 事件 | — | `index-BGa3KiRe.js:3436029`、`3464239` |

> 注意：`towardBottom` intent 本身**不**直接把 `following` 设为 true；它只记录 intent，真正的状态由随后的 scroll 事件按 48px 判据算出（`index-BGa3KiRe.js:3458604`、`3464239`）。

### 1.3 输入事件 → intent 映射

| 输入 | 映射 | 证据 |
|---|---|---|
| wheel | `deltaY<0 → awayFromBottom`；`>0 → towardBottom`；`=0 → none` | `index-BGa3KiRe.js:3436273` |
| touchmove | `newY>oldY → awayFromBottom`；`< → towardBottom` | `index-BGa3KiRe.js:3436343` |
| keydown | Up/PageUp/Home/Shift+Space → away；Down/PageDown/End/Space → toward；可编辑目标 → none | `index-BGa3KiRe.js:3436415` |
| pointerdown 在容器自身（滚动条/空白） | `intent=unknown`，记录 pointerId | `index-BGa3KiRe.js:3459270` |
| pointerup/cancel | 清 pointerId | `index-BGa3KiRe.js:3459270` |

`ht(intent)` 的副作用（`index-BGa3KiRe.js:3458604`）：

```js
if(e===`none`)return;
Ee.current={intent:e,observedAt:Date.now()};
let t=R.current;
e===`awayFromBottom`&&t&&t.scrollHeight>t.clientHeight&&dt(!1)
```

即：只要内容可滚，向上滚轮/触摸/按键会立刻把 `we.current` 置 false，不等待 scroll 事件。

### 1.4 scroll 事件源分类

`onScroll = Nt`（`index-BGa3KiRe.js:3464239`）：

```js
let t = Te.current!==null && Math.abs(e.scrollTop-Pe.current)<1;
let n = pt()===`none` ? (t?`programmatic`:Date.now()<=ke.current?`layout`:`user`) : `user`;
```

| source | 判定 | 对 follow 的影响 |
|---|---|---|
| `programmatic` | 有 `ut()` 预置的下一帧标记，且 scrollTop 与上次观测差 <1px | 保持原值（`rrn` 对非 user 返回 `e.following`） |
| `layout` | `Date.now() <= ke.current`（`mt()` 设置 `now+250ms`） | 保持原值；且不重置 `je`（测量补偿开关） |
| `user` | 其余 | 用 48px 判据重算；清 `Ne`（待恢复状态）；置 `Ae=true`；`jt()` 持久化滚动记忆 |

证据：`index-BGa3KiRe.js:3464239`、`3458547`、`3436029`。

### 1.5 新行到达时是否自动滚底

**跟随中：是；不跟随：否。** 两条通路：

1. 渲染后的 layout effect（依赖包含总高 `st`、单元数 `Lt`、行窗口、pending 队列）：
   ```js
   dt(IZ({...}));
   irn(we.current,Se())===`stickToBottom` && At()
   ```
   `index-BGa3KiRe.js:3469020`–`3469290`。
2. 活尾（running live tail）的 `ResizeObserver`：高度变化 → 重新算 `IZ` → `stickToBottom` 时 `At()`（`index-BGa3KiRe.js:3463480`–`3463800`）。

`At()` 是瞬时滚底（直接赋值，不是 smooth）：
```js
e.scrollTop = ue ? 0 : e.scrollHeight
```
`index-BGa3KiRe.js:3461589`。

容器宽度变化时另有 120ms 去抖：`we.current && At()`（`index-BGa3KiRe.js:3462940`）。

### 1.6 `data-following` 的读写点

| 操作 | 位置/值 | 证据 |
|---|---|---|
| 写 | `"data-following": Re ? 'false' : 'true'` | `index-BGa3KiRe.js:3470510` |
| JS 读 | **没有**。主包内 `data-following` 仅此一处时间线写入；另外两处 `data-following` 属于 bash 输出面板，与时间线无关 | `index-BGa3KiRe.js:2916130`、`5551025` |
| CSS 读 | **没有**。`index-BD2BL4Cc.css` 中 `data-following` 0 命中 | 全 CSS 搜索 0 命中 |

**重要语义偏差**：`data-following="true"` 等价于 `Re=false`，即「没有显示回到底部按钮」。当 `following=false` 但渲染单元数为 0 时，`lrn(false,0)=false`，`data-following` 仍为 `"true"`。复刻时不要把它当作 `following` 的严格镜像。

### 1.7 滚动记忆与 remount 恢复（影响「回到底部」与重载行为）

| 项 | 实现 | 证据 |
|---|---|---|
| 记忆 key | `Cgt({workspacePath, workspaceIdentity, paneId, sessionId, taskId:null})`，拼成 `workspace::pane:x::session:y` | `index-BGa3KiRe.js:1716666`、`3633260`、`3684751` |
| 存储 | 模块级 `Map TN`，`Tgt` 写入，`Sgt` 把上限裁到 200 条；**仅内存，不落 localStorage** | `1716965`、`1716576`、`1716892` |
| 捕获/提交 | `ain` 组件在 `scopeKey` 变化时 `getSnapshotBeforeUpdate` 捕获旧 key 的状态，`componentDidUpdate` 提交 | `3451364` |
| 状态形状 | `{scrollTop, scrollHeight, clientHeight, wasPinnedToBottom, updatedAt}` | `3460898` |
| 恢复判定 | `rin`：贴底状态不需要等待；非贴底时按 `waitFor='rows'` 或 `'content'` 等待 | `3451078` |
| 恢复完成 | `iin`：行窗口 key 变化或滚动位置已覆盖时清除待恢复状态 | `3451191` |
| 恢复执行 | 贴底 → `we=true; ze(false); At()`；非贴底 → `Mt(state)`（`scrollTop` 恢复、`we=false`、按钮显示） | `3467652`、`3464132` |
| 整页 reload | 内存 Map 为空 → `wgt(key)` 为 null → 恢复分支走 `At()`，**回到最底部** | `3467548`、`3467652` |

> Android 若要做「重载后保持阅读位置」，网页版本本身做不到（内存 Map 不跨进程/重载）；这与 `archive/pre-3.16.1-auth/docs/remote-v4/24-remote-v4-页面重载恢复机制逐条取证.md` 的结论一致。

---

## 2. 「静默加载更早历史」

### 2.1 触发条件（滚动路径）

| 项 | 值 | 证据 |
|---|---|---|
| 阈值函数 | `frn(clientHeight) = max(64, clientHeight * 2)` | `index-BGa3KiRe.js:3437263` |
| 触发判定 | `canLoadOlder && !loadingOlder && scrollTop <= triggerPx` | `index-BGa3KiRe.js:3437331` |
| 调用点 | scroll handler 内，先取 `at.getVirtualItemForOffset(scrollTop)`，记录锚点，再 `onLoadOlder()` | `index-BGa3KiRe.js:3464673`–`3465060` |
| 触发单位 | **像素**（约两个视口高），不是行数 | 同上 |
| 失败后 | 无内建重试；下一次 scroll 事件仍满足条件会再次调用 | `index-BGa3KiRe.js:3464673`、`1136519` |

> 2 倍视口高意味着：视口 800px 时，距顶 1600px 就开始预取。Android 复刻若想 1:1，应使用 `max(64, 2*viewportHeight)`，而不是常见的 200–400px。

### 2.2 并发去重

| 层 | 去重方式 | 证据 |
|---|---|---|
| store | `if(this.closed || this.state.loadingOlder) return;` | `index-BGa3KiRe.js:1136563` |
| store 前置 | `if(!zw(snapshot) || !snapshot) return;`（不可加载直接返回） | `index-BGa3KiRe.js:1136563` |
| UI 判定 | `prn` 检查 `!loadingOlder` | `index-BGa3KiRe.js:3437331` |
| `loadAllOlder` | 与 `loadOlder` 共用同一个 `loadingOlder` 标志，互斥 | `index-BGa3KiRe.js:1137357` |
| 查找补齐 | 以 `${query}:${firstRowId}:${loadedCount}` 为 key 去重 | `index-BGa3KiRe.js:3443438` |
| 导航器补齐 | 自己的 `status` 状态机 + key `${sessionKey}:${logEpoch}:${revision}` | `index-BGa3KiRe.js:3456100`–`3456460` |

### 2.3 `canLoadOlder` / `historyExhausted`

| 概念 | 实现 | 证据 |
|---|---|---|
| `canLoadOlder` | `zw(snapshot)`：`window[0].rowId > rows.firstRowId`；空窗口或 `firstRowId===null` 为 false | `index-BGa3KiRe.js:1123202` |
| 传入时间线 | `canLoadOlder: qi ? zw(qi) : false` | `index-BGa3KiRe.js:3684888` |
| `historyExhausted` | **不存在**。全量 JS 搜索 0 命中 | 全量搜索 `historyExhausted` 0 命中 |
| 首轮不完整自动补拉 | `H1e(snapshot, loadingOlder)`：`window[0].turnId` 在窗口内找不到 `turnHeader` 时返回 true | `index-BGa3KiRe.js:1123319` |
| 自动补拉去重 | key `${sessionId}:${subscriptionId}:${firstRowId}`，只补一次 | `index-BGa3KiRe.js:3667288`–`3667609` |

> 因此「历史到底了」没有独立布尔量，只能通过 `canLoadOlder` 变 false 观察；`rows.firstRowId` 是权威游标。

### 2.4 加载中的占位高度

| 项 | 事实 | 证据 |
|---|---|---|
| 占位行/骨架 | **没有**。时间线不渲染任何 loading 行或 spinner | 时间线区间 `3400000–3474500` 搜索 `loading` 仅命中 props/attr/effect；无 JSX 占位 |
| 唯一 DOM 信号 | `data-loading-older={p ? 'true':'false'}` | `index-BGa3KiRe.js:3470545` |
| 高度来源 | `at.getTotalSize()`（已测量 + 未测量估算之和） | `index-BGa3KiRe.js:3457730` |
| 未测量单元估算 | 缓存命中用缓存值，否则 **72px** | `index-BGa3KiRe.js:3457152` |
| 测量后修正 | `measureElement` 取 `borderBoxSize.blockSize` 或 `getBoundingClientRect().height`，四舍五入 | `index-BGa3KiRe.js:3472465`、`3451620` |

> 所谓「加载中占位高度」在本版并不存在固定值；实际是「每个未测量单元先按 72px 占位，prepend 后由虚拟器重新测量」。

### 2.5 prepend 后如何保持视口不跳

分三层：

1. **触发时记录锚点**（`index-BGa3KiRe.js:3464890`）：
   ```js
   Ie.current = {key:n.key, offsetTop:t.start-e.scrollTop, start:t.start}
   ```
   `key` 是视口内第一个虚拟单元的 key，`offsetTop` 是它相对视口的偏移。

2. **prepend 后的 layout effect 补偿**（`index-BGa3KiRe.js:3468468`–`3468990`）：
   - `Fe.current` 保存上一次的 `{firstRowId, totalSize}`；
   - 若新的 `window[0].rowId < 旧 firstRowId`，判定为 prepend；
   - 优先用锚点算差值：`urn = newStart - oldOffsetTop - currentScrollTop`（`3436944`）；
   - 锚点失效时回退到总高差：`drn = nextTotalSize - prevTotalSize`（`3437091`）；
   - `e.scrollTop += delta`，并在同一 layout effect 内更新 `Pe.current`、重算 mask、派发一次合成 scroll。

3. **禁用浏览器原生锚定 + 稳定 key**：
   - 内层内容容器显式 `style:{overflowAnchor:'none'}`（`index-BGa3KiRe.js:3471549`）；
   - `getItemKey = pe.current[e]?.key ?? e`（`index-BGa3KiRe.js:3457102`）；
   - React key 为 `${unit.key}:${logEpoch}`（`index-BGa3KiRe.js:3472777`）；
   - `shouldAdjustScrollPositionOnItemSizeChange` 只在「单元完全在视口上方」且非跟随、非抑制、非宽度变化时补偿（`index-BGa3KiRe.js:3457542`、`3436166`）。

> 这套机制的效果：prepend 前后，锚点单元在视口中的位置不变。`Fe`/`Ie` 都是 ref，不触发额外渲染。

### 2.6 其他会触发 `loadOlder` 的路径

| 路径 | 条件 | 证据 |
|---|---|---|
| 对话内查找 | 有 query、`canLoadOlder`、非 loading、已加载行数 < `qZ=1200`，且 key 变化 | `index-BGa3KiRe.js:3441509`、`3443438` |
| 问题导航器目录补齐 | `canLoadOlder && !loadingOlder && hasLoadHandler && containerWidthPx >= 864`，调用 `loadAllOlder` | `index-BGa3KiRe.js:3415570` |
| 冷快照首 turn 不完整 | `H1e(snapshot, loadingOlder)` 为 true | `index-BGa3KiRe.js:3667288` |

导航器补齐的重试节奏：`Jtn(attempt) = 1→250ms，2→1000ms，其余→null`（`index-BGa3KiRe.js:3415668`）。

---

## 3. `rowsRange` 的 CAS 参数、失败重试与 `hasMore`

### 3.1 请求/响应形状

| 项 | 形状 | 证据 |
|---|---|---|
| 客户端 transport | `conversationRowsRangeV4({...workspace, sessionId, beforeRowId?, limit})` | `index-BGa3KiRe.js:1111491` |
| 请求 schema | `{sessionId, clientMode?, beforeRowId?: int, limit: int(1..200)}` | `parts-DcYGwy2q.js:15550` |
| 响应 schema | `{rows, atSeq, atRevision, atLogEpoch, hasMore}` | `parts-DcYGwy2q.js:15680` |
| 默认 `loadOlder` limit | `snapshotTailWindowRows = 60` | `validation-CqXZOLXO.js:117368`、`index-BGa3KiRe.js:1136539` |
| 最大 limit | `rowsRangeMaxLimit = 200` | `validation-CqXZOLXO.js:117394` |
| `loadAllOlder` 每页 limit | 200 | `index-BGa3KiRe.js:1138123` |

### 3.2 客户端 CAS 校验

`loadOlder` 原文（`index-BGa3KiRe.js:1136519`–`1137300`）：

| 步骤 | 校验 | 不匹配行为 | 证据 |
|---|---|---|---|
| 1 | `closed || loadingOlder` | 直接返回 | `1136563` |
| 2 | `zw(snapshot)` 为真 | 直接返回（没有更早行） | `1136563` |
| 3 | 取 `beforeRowId = window[0].rowId` | 无窗口首行则返回 | `1136563` |
| 4 | 请求返回后 `atLogEpoch === snapshot.logEpoch` | 整批丢弃，warn | `1136902` |
| 5 | 当前 `window[0].rowId` 仍等于请求时的 `beforeRowId` | 静默丢弃（游标已变） | `1137034` |
| 6 | `Bw(window, newRows)` 非 null | 无更早行则不更新 | `1123466` |

`Bw` 的合并规则：只保留 `rowId < 当前 window[0].rowId` 的行，拼到队首；没有则返回 null（`index-BGa3KiRe.js:1123466`）。

`loadAllOlder` 的 CAS 更严格（`index-BGa3KiRe.js:1137357`–`1138600`）：
- 每页都要求 `result.atLogEpoch === a`、`snapshot.logEpoch === a`、`snapshot.window[0].rowId === i`（最初游标）；
- 任一条不满足 → 整批丢弃并返回 `stale`/`retryable-failure`；
- 每页要求游标前进：`nextFirst < beforeRowId`，否则停止并返回 `retryable-failure`（`1138590`）。

### 3.3 失败与重试

| 场景 | 行为 | 证据 |
|---|---|---|
| `loadOlder` 网络/解析异常 | `catch` 只 warn，`finally` 清 `loadingOlder`；不自动重试 | `index-BGa3KiRe.js:1137120`–`1137300` |
| `loadOlder` 纪元不匹配 | 丢弃整批，不重试 | `1136902` |
| `loadAllOlder` 异常 | 返回 `retryable-failure` | `1139989` |
| 导航器补齐 | 250ms → 1000ms → 终止 | `3415668`、`3456460`–`3456820` |
| 下一次滚动 | 只要仍在阈值内且 `loadingOlder=false`，会再次触发 | `3464673` |

> 所以「失败重试」在普通静默加载路径上是**事件驱动**的，不是定时器驱动。

### 3.4 `hasMore` 的用法

| 使用点 | 用法 | 证据 |
|---|---|---|
| 响应 schema | 必填布尔 | `parts-DcYGwy2q.js:15680` |
| `loadOlder` | **完全不用**；是否还能加载由 `zw(snapshot)` 决定 | `1136519`–`1137300` |
| `loadAllOlder` | 循环终止条件：`if(!t.hasMore) break;` | `index-BGa3KiRe.js:1138802` |
| 客户端状态 | 不持久化 `hasMore`；快照里没有 `rows.hasMore` | 全量搜索 `rows.hasMore` 0 命中 |

---

## 4. 用户看历史时，新消息如何呈现

**结论：没有新消息 pill、没有计数、没有未读分割线；唯一提示是「回到底部」按钮。**

| 检查项 | 结果 | 证据 |
|---|---|---|
| `newMessages` / `newMessageDivider` / `unreadDivider` / `data-v4-new` / `jumpToLatest` | 全量 JS 0 命中 | 全量搜索 |
| 时间线区间 `aria-live` / `role="status"` / badge / 未读 | 0 命中 | `index-BGa3KiRe.js:3400000–3474500` 区间搜索 |
| 新行到达时 | store 追加行；时间线不改变 `scrollTop`（因为 `following=false`）；`Re` 变 true → 按钮出现 | `3464239`、`3458180`、`3474140` |
| 右侧问题导航器（`nav`） | 按 `realUser` 输入生成问题条目（带助手预览）的迷你地图，≥864px 容器才可见；不是新消息提示 | `index-BGa3KiRe.js:3416518`、`3420197`、`3420377`、`3420515` |
| 点击按钮 | `Pt()`：`dt(true)` → `At()` 瞬时滚底 → `jt()` 保存滚动记忆 | `3465128`、`3461589` |

> 导航器是「问题跳转目录」，不是未读提示：`tnn` 只取 `origin==='realUser'` 的输入生成条目，附带助手预览，点击 `onJumpToQuery` 跳到对应 query（`index-BGa3KiRe.js:3416518`、`3418937`–`3420737`）。

---

## 5. 「回到底部」按钮

### 5.1 出现条件

| 条件 | 说明 | 证据 |
|---|---|---|
| `Re === true` | `Re = !following && renderUnits.length > 0` | `3458180`、`3436889`、`3454498` |
| 无额外「新消息」条件 | 只要滚离底部（或恢复非贴底）就出现，哪怕没有新行 | `3464054` |
| 单元数为 0 时隐藏 | effect 中 `Re && ze(false)`（无待恢复时） | `3469456` |
| 挂载恢复贴底时隐藏 | `we=true; ze(false)` | `3467686` |

### 5.2 图标、尺寸、样式

| 项 | 值 | 证据 |
|---|---|---|
| 图标 | lucide `arrow-down`：`M12 5v14` + `m19 12-7 7-7-7` | `index-BGa3KiRe.js:81834` |
| 图标尺寸 | `size-4` = 16px | `index-BGa3KiRe.js:3450639`、`index-BD2BL4Cc.css:53536` |
| 按钮 | design-system `Button`，`size="icon"`，`variant="outline"` | `index-BGa3KiRe.js:3450639` |
| `size="icon"` | `size-7`（28px），默认 svg 16px | `button-B-6dbTqe.js:4676` |
| `variant="outline"` | `border-border text-foreground hover:border-border-hover hover:bg-input/50` | `button-B-6dbTqe.js:3209` |
| 额外类 | `rounded-full bg-card hover:bg-card-selected` + 外部 `shadow-sm` | `index-BGa3KiRe.js:3450639`、`3474158` |
| 无障碍 | `aria-label`/`title` = `chat.scrollToBottom`（中文「滚动到底部」） | `IntlProvider-C-26GNLk.js:288471`（英文 `715927`） |
| 埋点 | `featureId=conversation.navigation, action=jump_bottom, trigger=button` | `index-BGa3KiRe.js:3450639` |

### 5.3 位置

| 场景 | 位置类 | 证据 |
|---|---|---|
| 有 composer dock | 放在 `data-v4-back-to-bottom-anchor="composer-dock"` 的 `relative` 容器内：`absolute bottom-full left-1/2 z-30 mb-2 -translate-x-1/2`，即 dock 顶部上方 8px、水平居中 | `3474061`、`3474158` |
| 无 dock | 放在时间线外层 `relative` 容器：`absolute bottom-3 left-1/2 -translate-x-1/2`，距底 12px、水平居中 | `3474366` |

### 5.4 动画

**没有出现/消失动画。** 自定义类名中没有 `animate-*`；按钮基础组件自带 `transition-colors`（颜色过渡，默认 150ms，见 `button-B-6dbTqe.js:2530` 的基类），不影响显隐；CSS 也没有针对该按钮的显隐动画规则（`index-BD2BL4Cc.css` 全量搜索 `data-v4-back-to-bottom` 0 命中）。出现/消失是瞬时的。

---

## 6. 虚拟化参数与 turn unit 划分

### 6.1 虚拟器参数

| 参数 | 值 | 证据 |
|---|---|---|
| 库 | TanStack Virtual（`esm-DhX58A8k.js`） | `index-BGa3KiRe.js:59750` 预加载清单；`esm-DhX58A8k.js:11390` |
| `count` | `virtualizedUnits.length`（**不含** live tail） | `index-BGa3KiRe.js:3457420`、`3453220` |
| `getScrollElement` | 时间线滚动容器 `R.current` | `3457420` |
| `estimateSize` | `sizeCache.estimate(unit.key, 72)`，缓存命中用缓存值，否则 72px | `3457152` |
| 尺寸缓存 | `ern`，最多 4000 条（`$nn=4e3`） | `3435455` |
| `overscan` | `Krn = 8` | `3450315`、`3457460` |
| `getItemKey` | `pe.current[e]?.key ?? e`（unit.key） | `3457102` |
| `measureElement` | `borderBoxSize.blockSize` 或 `getBoundingClientRect().height`，四舍五入 | `3472465`、`3451620` |
| `scrollMargin` | header slot 的高度 `ee`（0 或实测 px） | `3457521`、`3452579`–`3452690` |
| `shouldAdjustScrollPositionOnItemSizeChange` | `arn(...)`：跟随中/抑制中/宽度变化中不调整；否则仅当 `itemEnd <= scrollTop` 调整 | `3457542`、`3436166` |
| 单元定位 | 绝对定位 `absolute left-0 top-0 w-full` + `translateY(start - scrollMargin)` | `3472508`、`3472620` |
| live tail | 不参与虚拟化，正常流式布局，位于虚拟历史之后 | `3472877`、`3424138` |

### 6.2 render unit / turn unit 的划分与合并

| 步骤 | 规则 | 证据 |
|---|---|---|
| 分组键 | `turnId`；`Map` 保持首次出现顺序 | `3435018` |
| `turnHeader` | 放入 `unit.header` | `3435018`–`3435120` |
| `userInput` | 放入 `visibleUserInputs` | 同上 |
| `hookInvocation` | 放入 `hookInvocations` | 同上 |
| 其余行 | 放入 `assistantWorkRows` | 同上 |
| 空单元过滤 | `Znn`：无可见用户输入、无工作行、无实际执行的 hook、无 leading boundary、无 workflowLaunch、且非 running → 丢弃 | `3433950` |
| 最后单元标记 | `Qnn` 设置 `isLastTurn`、`assistantHistoryDefaultOpen` | `3434167` |
| 活尾切分 | `mnn`：最后一个 unit 若 `isRunning`，从虚拟列表切出为 `liveUnit`，索引为 `liveUnitIndex` | `3424138` |
| topic 预处理合并 | `eKt` 把 `topicPreparations` 合并进最后一个 unit；没有 unit 时创建一个 `key=turnId='topic-preparation'` 的 timelineOnly 单元 | `2771012` |
| highspeed 页脚合并 | `jnn`/`Mnn` 把 highspeed 输出页脚挂到最后一个有输出的 unit | `3428584` |
| 单元内部再分片 | `Ynn` 计算 `visibleUserInputs / assistantHistoryRows / assistantFollowingRows / assistantTailRows / browserTurnEndRows / assistantTextRows / leadingBoundaryRows / flowItems / workSegments / renderRows` | `3432120`–`3433400` |

> 因此：**render unit 与 turn unit 是 1:1**，不存在「多个 turn 合并成一个虚拟项」的规则；唯一的「切分」是 running 的最后一个 turn 被切出虚拟列表，唯一的「合并」是 topic preparations / highspeed footer 并入最后一个 unit。

### 6.3 DOM 上的计数属性

| 属性 | 含义 | 证据 |
|---|---|---|
| `data-row-count` | `rows.length`（行窗口行数） | `3470391` |
| `data-window-row-count` | 同 `rows.length`（冗余） | `3470417` |
| `data-render-unit-count` | `renderUnits.length`（**含** live tail） | `3470450` |
| `data-total-row-count` | `totalCount`（服务端总行数） | `3470485` |

---

## 7. 动画（进入/退出、流式、折叠）

### 7.1 行/单元进入与退出

**没有动画。** 证据：
- 单元包装类只有 `absolute left-0 top-0 w-full` + 内联 `transform`（`index-BGa3KiRe.js:3472508`、`3472620`）；
- CSS 中 `data-v4-turn-unit` 0 命中；时间线区间 `animate-in`/`animate-out`/`fade-in`/`slide-in` 均 0 命中（`index-BGa3KiRe.js:3400000–3474500`）；
- 虚拟器在 overscan 外直接卸载，无退出过渡。

### 7.2 流式更新

| 动画 | 触发属性 | 参数 | 证据 |
|---|---|---|---|
| 工具调用块淡入 | `data-zcode-tool-stream-animate="true"` | `zcode-stream-text-in`，0.9s，`cubic-bezier(.16,1,.3,1)`，`both`，`will-change:opacity` | `index-BGa3KiRe.js:3144824`、`index-BD2BL4Cc.css:425586`、`425269` |
| 预览卡淡入 | `data-zcode-stream-animate="true"` | 同上；每项通过 `--zcode-stream-animation-delay` 做错峰 | `index-BGa3KiRe.js:3336443`、`index-BD2BL4Cc.css:425269` |
| 流式 marker 颜色淡入 | `data-zcode-stream-marker-animate="true"` | `zcode-stream-marker-in`，0.9s，同缓动 | `index-BD2BL4Cc.css:425429`、`425177` |
| 助手正文逐字淡入 | **未发现写入点**；主包/全部 chunk 搜索 `data-zcode-stream-animate` 仅命中预览卡 | 全量搜索 |
| 折叠关闭 | `data-zcode-collapsible-animate-close="true"` + `data-state=closed` | `zcode-collapsible-up` 0.3s ease-in-out（height→0）；子元素 `zcode-collapsible-fade-out` 0.3s | `index-BD2BL4Cc.css:425748`、`426014`、`426109` |
| 折叠关闭是否实际生效 | **未接线**：全量 JS 搜索 `collapsible-animate-close` 0 命中；Radix Collapsible 只写 `data-state` | `index-BGa3KiRe.js:736050` |
| 减少动态 | `prefers-reduced-motion: reduce` 下上述 stream/collapse 动画全部 `animation:none` | `index-BD2BL4Cc.css:426174` |

### 7.3 宽度/布局过渡

| 元素 | 过渡 | 证据 |
|---|---|---|
| 内容列（虚拟历史 / live tail / dock content / header slot） | `transition-[width,max-width,transform] duration-150 ease-out`；≥1280px 只保留 `transition-[transform]` | `index-BGa3KiRe.js:3472192`、`3472979`、`3473821`、`index-BD2BL4Cc.css:178206`、`180398`、`181121` |
| 缓动值 | `--ease-out: cubic-bezier(0, 0, .2, 1)` | `index-BD2BL4Cc.css:10569` |
| 问题导航器 | `transition-[opacity,transform,visibility] duration-150 ease-out motion-reduce:transition-none` | `index-BGa3KiRe.js:3420515` |
| 导航器条目条 | `transition-[height,opacity,transform,background-color] duration-150 ease-out` | `index-BGa3KiRe.js:3422354` |
| 回到底部按钮 | 无显隐过渡（仅基类 `transition-colors`） | `3450639` |

### 7.4 底部遮罩（与滚动相关的视觉）

| 项 | 值 | 证据 |
|---|---|---|
| 常量 | `Yrn=96`、`Jrn=24` | `3450315` |
| 行为 | 贴底时 `maskImage='none'`；否则设置 `linear-gradient(to bottom, black 0, black (H-120)px, transparent (H-96)px, transparent 100%)` | `3459344`、`3459498`、`3459553` |
| 应用对象 | 消息层 `he`（`data-v4-timeline-message-layer`），不是滚动容器 | `3471757` |
| 位置补偿 | `maskPosition = 0 (scrollTop - offsetTop)px`，`maskSize = 100% Hpx`，随滚动固定于视口底部 | `3459553` |

> 效果：距底约 120px 开始淡出，最后 96px 全透明，给 composer dock 让位；贴底时关闭遮罩。

---

## 8. 时间线容器的 padding / gap / 最大宽度 / 响应式断点

### 8.1 结构树（从外到内）

```text
div.relative.flex.min-h-0.flex-1.flex-col                      (外层 Je)
├─ 选择面板 / 滚动记忆捕获器 ain / 问题导航器 cnn
├─ div[data-v4-timeline-scroll]                                (滚动容器 R)
│  └─ div (flex min-h-full flex-col; style overflow-anchor:none)
│     ├─ 空态 div 或
│     └─ div[data-v4-timeline-message-layer]                   (消息层 he, mask 应用点)
│        ├─ div[data-v4-timeline-header-slot]                  (可选 header)
│        ├─ div[data-v4-timeline-virtual-history]              (高度 = getTotalSize())
│        │  └─ div[data-v4-turn-unit] * N                      (绝对定位)
│        ├─ div[data-v4-running-live-tail]                     (running 的最后一个 turn)
│        └─ div[data-v4-pending-guide-list]                    (可选)
│     └─ div[data-v4-composer-dock] (sticky bottom-0)          (可选 dock)
│        └─ div[data-v4-back-to-bottom-anchor="composer-dock"]
│           └─ button (回到底部)
└─ (无 dock 时的回到底部按钮 absolute bottom-3)
```

证据：`index-BGa3KiRe.js:3469550`、`3470267`、`3471757`、`3471960`、`3472135`、`3472508`、`3472877`、`3473616`、`3474061`。

### 8.2 padding / gap

| 元素 | 值 | 证据 |
|---|---|---|
| 滚动容器 | 无 padding；`overflow-x-hidden overflow-y-auto`、`[scrollbar-gutter:stable]` | `index-BGa3KiRe.js:3470795`、`index-BD2BL4Cc.css:188201` |
| turn `section` | `flex flex-col gap-5 px-4 @md/conversation:px-6 pb-5`；普通 turn `pt-14`，带背景任务标题的 turn `pt-0` | `3411454`、`3411500` |
| `gap-5` | 20px | `index-BD2BL4Cc.css:81257`（`--spacing:.25rem` @ `9267`） |
| `px-4` / `px-6` | 16px / 24px | `136443` / `136535` |
| `pb-5` / `pt-14` | 20px / 56px | `139935` / `138390` |
| 消息层 | 无 padding/gap；`relative w-full flex-1` | `3471757` |
| 虚拟历史 | 无 padding；`relative mx-auto w-full shrink-0` | `3472135` |
| pending guide 列表 | `flex flex-col gap-5 px-4 pt-5` | `3415380` |
| composer dock 内容 | 非居中空态时 `px-4 pb-4` | `3473880` |
| 空态（居中） | `flex min-h-full flex-col items-center justify-center gap-4 px-4`；`before:basis-[29dvh]`、`after:min-h-4` | `3471260`–`3471460` |

### 8.3 最大宽度

| 场景 | 类 | 实际值 | 证据 |
|---|---|---|---|
| 内容列 <864px | `w-full` | 100% | `index-BGa3KiRe.js:2727132` |
| 内容列 ≥864px | `w-[calc(100%-6rem)] max-w-4xl` | 100%−96px，最大 896px | `2727132`、`index-BD2BL4Cc.css:285593` |
| 内容列 ≥1280px | `w-[calc(100%-24rem)] max-w-6xl` | 100%−384px，最大 1152px | `2727132`、`286161` |
| 居中空态 | `max-w-2xl` | 672px | `2726932` |
| 有右侧状态面板（≥1280） | 追加 `-translate-x-42` | 左移 168px | `2727316`、`286161` |

`--container-4xl:56rem`（896px）@ `index-BD2BL4Cc.css:9433`；`--container-6xl:72rem`（1152px）@ `9477`。

### 8.4 响应式断点

时间线使用的是**容器查询**，容器名 `conversation`，定义在对话面板外层：

```css
.@container/conversation{container:conversation/inline-size}
```

证据：`index-BD2BL4Cc.css:24474`；使用点 `index-BGa3KiRe.js:3661707`（`@container/conversation` 外层）。

| 断点 | 触发的变化 | 证据 |
|---|---|---|
| 360px（`not (width>=360px)`） | 某些元素 `hidden` | `index-BD2BL4Cc.css:283567` |
| 448px（`@md` / 28rem） | turn 的 `px-6`（24px） | `287382`、`index-BGa3KiRe.js:3411454` |
| 624px | 某些内容 `max-w-xl` | `285358` |
| 864px | 内容列改为 `calc(100%-6rem)` + `max-w-4xl`；问题导航器显示；表格左 inset 48px | `285593`、`3420515`、`3471060` |
| 1280px | 内容列改为 `calc(100%-24rem)` + `max-w-6xl`；只保留 transform 过渡；可左移 168px | `286161`、`3472192`、`2727316` |

另有视口级 `max-md`（<768px）把 markdown 表格左右 inset 从 16px 降到 8px（`index-BGa3KiRe.js:3470860`）。这是 Tailwind 的视口断点，不是容器查询。

### 8.5 滚动容器属性一览

| 属性 | 值 | 证据 |
|---|---|---|
| `data-v4-timeline-scroll` | `true` | `3470267` |
| `data-v4-timeline-scroll-locked` | `backgroundScrollLocked ? 'true':'false'` | `3470300` |
| `data-markdown-table-layout-root` | `true` | `3470350` |
| `data-row-count` | `rows.length` | `3470391` |
| `data-window-row-count` | `rows.length` | `3470417` |
| `data-render-unit-count` | `renderUnits.length` | `3470450` |
| `data-total-row-count` | `totalCount` | `3470485` |
| `data-following` | `Re ? 'false':'true'` | `3470510` |
| `data-loading-older` | `loadingOlder ? 'true':'false'` | `3470545` |
| 事件 | `onScroll`、`onWheelCapture`、`onTouchStart/Move/End/CancelCapture`、`onKeyDownCapture`、`onPointerDown/Up/CancelCapture` | `3470583`–`3470790` |
| 滚动条 | `[scrollbar-gutter:stable]`，避免内容增减导致宽度跳动 | `3470795`、`index-BD2BL4Cc.css:188201` |

---

## 9. Android 复刻要点（从取证直接推导）

1. **跟随判定**：用 `distanceToBottom = contentHeight - viewportHeight - scrollTop`；`<=48dp` 视为贴底。滚轮/触摸/键盘的上滚动作要**立即**退出跟随；普通拖动/滚动条则等距离 >48dp 再退出。
2. **自动滚底**：仅在 `following=true` 时，每次行数/总高变化后滚到底；不要用平滑动画。`following=false` 时绝不自动滚。
3. **回到底部按钮**：条件只用「不跟随且有内容」；不要等新消息。图标 `arrow-down` 16dp，圆形 28dp，outline；有输入栏时悬浮在输入栏上方 8dp，否则距底 12dp；无显隐动画。
4. **历史预取**：阈值 `max(64, 2*viewportHeight)`，不是行数；加载中只置 `data-loading-older`，不要插骨架/占位行。
5. **prepend 不跳**：记录视口首单元的 key 与 `start - scrollTop`；插入后按 `newStart - oldOffset - scrollTop` 补偿，失败则用总高差；关闭系统 scroll anchoring。
6. **CAS**：每次 `rowsRange` 带 `beforeRowId=window[0].rowId`；响应 `atLogEpoch` 必须等于当前 epoch，且当前首行未变，否则整批丢弃；普通加载不做定时重试。
7. **虚拟化**：overscan 8；未测量项按 72dp 估算；key 用 turn unit key；header 高度作为 scrollMargin；running 的最后一个 turn 放在虚拟列表之外。
8. **视觉**：turn 之间 gap 20dp，左右 padding 16dp（容器 ≥448px 时 24dp），顶部 56dp，底部 20dp；内容列最大 896dp（≥864px）/1152dp（≥1280px）；底部 96dp 渐隐遮罩仅在非贴底时出现。

---

## 10. 未发现 / 未验证 / 容易误判的点

| 项 | 状态 | 说明 |
|---|---|---|
| `historyExhausted` | **不存在** | 全量 JS 0 命中；用 `canLoadOlder`/`rows.firstRowId` 替代 |
| 新消息 pill / 计数 / 未读分割线 | **不存在** | 全量 JS 0 命中；时间线区间无 badge/aria-live |
| 行进入/退出动画 | **不存在** | 无 CSS 规则、无 `animate-in/out` 类 |
| 折叠关闭动画 | CSS 存在但**未接线** | `data-zcode-collapsible-animate-close` 在全部 JS 中 0 命中 |
| 助手正文逐字淡入 | **未发现写入点** | `data-zcode-stream-animate` 仅预览卡使用 |
| `data-following` 的 JS 读取 | **不存在** | 仅写不读；外部壳/测试可能读取 |
| `data-following` 严格等于 following | **不成立** | 它等于「未显示回到底部按钮」；`following=false` 且 0 单元时仍为 `"true"` |
| `hasMore` 在普通加载中的使用 | **不使用** | 仅 `loadAllOlder` 循环终止用 |
| `atSeq` / `atRevision` | 返回但本路径未使用 | 只校验 `atLogEpoch` |
| `scrollMemoryKey` 跨页面重载 | **不持久化** | 内存 `Map`，上限 200（`index-BGa3KiRe.js:1716576`、`1716666`、`1716892`） |
| `data-testid` 的具体字符串 | 未解析 | 使用常量 `ur`，未在主包内定位到字面量；不影响行为复刻 |
| `ls(topic)` 的实现 | 未展开 | 只用于 topic→sessionId，非滚动行为关键 |

---

## 附：证据索引（按主题）

| 主题 | 主要坐标 |
|---|---|
| follow 纯函数 | `3435876`（trn）、`3435956`（FZ）、`3435993`（nrn）、`3436029`（rrn）、`3436109`（irn）、`3436663`（IZ）、`3436889`（lrn）、`3436921`（LZ） |
| 输入 intent | `3436273`（wheel）、`3436343`（touch）、`3436415`（key）、`3458604`（ht）、`3458780`（gt）、`3458899`（vt）、`3459069`（bt）、`3459270`（pointer） |
| follow 状态写入 | `3454108`（初始 true）、`3454498`（Re state）、`3458180`（dt）、`3464054`（Mt）、`3467686`（恢复）、`3469456`（0 单元） |
| scroll 处理 | `3464239`（Nt）、`3461589`（At）、`3465128`（Pt）、`3463887`（jt）、`3459933`（wt） |
| 历史预取 | `3437263`（frn）、`3437331`（prn）、`3464673`（触发）、`3464890`（锚点）、`3415570`（qtn）、`3415668`（Jtn） |
| prepend 补偿 | `3436944`（urn）、`3437091`（drn）、`3468468`（Fe）、`3468965`（scrollTop+=）、`3471549`（overflowAnchor）、`3457542`（shouldAdjust） |
| store 加载 | `1136519`（loadOlder）、`1136563`（guard）、`1136779`（rowsRange）、`1136902`（epoch）、`1137357`（loadAllOlder）、`1138123`（loop）、`1138590`（cursor） |
| 游标/耗尽 | `1123202`（zw）、`1123319`（H1e）、`3684888`（canLoadOlder prop）、`3667288`（自动补拉） |
| 协议 | `1111491`（transport）、`parts-DcYGwy2q.js:15550`（请求）、`15680`（响应）、`validation-CqXZOLXO.js:117368/117394` |
| 虚拟化 | `3450315`（Krn=8）、`3457420`（gs）、`3457152`（estimate 72）、`3457102`（key）、`3457521`（scrollMargin）、`3472465`（measure）、`3435455`（cache 4000） |
| turn unit | `3435018`（PZ）、`3433950`（Znn）、`3433842`（Xnn）、`3424138`（mnn）、`2771012`（eKt）、`3428584`（jnn）、`3432120`（Ynn） |
| 按钮 | `3450639`（nin）、`81834`（icon）、`3474140`（render）、`3474158`（dock 位置）、`3474366`（无 dock 位置）、`button-B-6dbTqe.js:4676/3209` |
| DOM 属性 | `3470267/3470300/3470391/3470417/3470450/3470485/3470510/3470545` |
| 动画 | `index-BD2BL4Cc.css:424254/425269/425429/425586/425748/426014/426109/426174/178206/180398/181121/10569` |
| 布局 | `3411454`（turn section）、`3471757/3471960/3472135/3472192/3473616/3473791`、`2726932/2727132`、`index-BD2BL4Cc.css:287382/285593/286161/9433/9477/9267/81257/136443/136535/139935/138390` |
