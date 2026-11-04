# 进对话无内容与左下角转圈：现场分析与机制归因

> **审计基线**：`zcode.z.ai/remote/v4` 当前线上构建 —— 主包 `assets/index-B-ilXaCQ.js`（4,821,509 B）、
> 协议 schema 模块 `assets/src-wmk2orCZ.js`（285,095 B）、样式 `assets/index-DR0mqKFV.css`（392,369 B）。
> 快照：`Docs/27-remote-v4-网页快照-20260920/latest/`（2,518 个 assets）；入口 `index.html` 与线上逐字节一致。
> **审计日期**：2026-09-20 ｜ **方法**：页面机制按当前构建字符偏移取证；**现场**引自 2026-09-11 真机日志（标「外部输入」）。
> **偏移口径**：UTF-8 解码后**字符偏移**（`python -c "d=open(f,encoding='utf-8',errors='replace').read();print(d[OFF-400:OFF+400])"` 可复现）。

---

## 0. 一句话

三段现象各有**一条明确的页面机制**：① 标题回退「新建任务」= `readSession` 失败**被静默吞掉**；
② 进对话迟迟不出内容 = 桌面端在**一条 relay 连接上按序处理**，别处的 RPC 突发把会话请求压在队尾；
③ 停更 + 转圈 = 工作区桥被判 `degraded` ⇒ 页面走**全量恢复**（重连 + 重开工作区/任务），恢复期间的呈现就是"连接中 + 转圈"。
**三者的共同点：页面从不报错**，全部走"静默降级 + 重试"。

---

## 1. 三段现象（「外部输入」：2026-09-11 真机现场）

> 来源：`android-shell/docs/05-远程网页-remote-v4-20260911/_evidence/zcode-shell.log`（126 KB，含行号引用，
> 已自查无凭证；壳把页面 URL 统一替换为 `<redacted>`）。**该日志属于旧构建 + 旧壳版本时期，
> 本篇只保留它作为"现象确实发生过"的证据，机制归因一律以当前构建重新取证。**

| # | 现象 | 现场读数（日志） |
| --- | --- | --- |
| 1 | 标题一直是回退的「新建任务」 | 两次 `readSession · Session is not active: sess_…` |
| 2 | 进对话迟迟不出内容 | 连续 4 个 10s 窗口在重发 `getTaskSessionFilePath / getTaskNativeSessionLogFile / getWorkspaceProviderConfigFile / subscribeConversationV4` |
| 3 | 刷新后能出内容、几秒后停更、左下角一直转圈 | 同一工作区反复 `rpc-transport-fault`；随后每 10s 发一次 `unsubscribeConversationV4`（订阅被拆掉后没能重建的形态） |

---

## 2. 现象一：标题回退「新建任务」

**机制（当前构建，off 301,615 与 305,401）**：

```js
// ① 任务元数据的兜底读取：失败被吞成 null
function phe(e, t, n, r, i){                    // e=workspacePath t=taskId n=remoteSessionId r=identity i=skip
  const a = Nd(e, n, r), [meta, setMeta] = useState(null)
  useEffect(() => {
    let cancelled = false
    setMeta(m => fhe(m, t, i))                   // 先按现有数据兜一次
    if (!t || i) return
    a.readSession({ workspacePath:e, workspaceIdentity:r, sessionId:t, messageLimit:1 })
     .then(v => { cancelled || setMeta(ihe(v)) })
     .catch(() => { cancelled || setMeta(null) })   // ★ 失败 ⇒ null，没有任何用户可见的错误
    return () => { cancelled = true }
  }, [a, t, i, r, e])
  return meta
}

// ② 标题回退（off 305,401）
activeTaskTitle: u?.title?.trim()
  ? u.title
  : formatMessage({ id: u?.forkedFromTaskId ? 'taskList.forkedUntitled' : 'taskList.newThread' })
```

**读出来的两条**：

- `readSession` 的失败路径**没有任何上报**（不发事件、不写错误横幅），只把元数据置空；
- 标题因此回退到 i18n 键 **`taskList.newThread`**（=「新建任务」）。
  ⇒ **看到"新建任务"不等于"这是个新对话"**，而是"任务元数据没读到"。

**壳能观测到什么**：壳侧不读标题（它用 relay 的 `title` 字段），所以壳的日志里**看不到这个失败**；
它只能从"页面标题与 relay 侧 title 不一致"间接发现。这是"页面静默失败、壳观测不到"的典型样本。

---

## 3. 现象二：进对话迟迟不出内容

**机制（页面侧，当前构建）**：relay 是**一条连接**，桌面端在它上面**按序处理请求**。
任何一处产生 RPC 突发（例如壳的握手 burst：多工作区 × 多 RPC），
会话相关请求（`subscribeConversationV4` 与它的伙伴调用）就**被压在队尾**；
页面拿不到回包不是失败、而是**排队**，于是按固定节拍重试（现场是 10s 一轮）。

**页面侧的排队/重试证据面**（当前构建）：

| 面 | 证据 |
| --- | --- |
| 订阅调用 | `subscribeConversationV4({...scope, sessionId, base?, visibility?})` → `{ack:{subscriptionId}}`（off **503,312**） |
| 反订阅 | `unsubscribeConversationV4({...scope, subscriptionId})`（off **505,585**）——现场日志里每 10s 一次的正是它 |
| 会话文件类调用 | `getTaskSessionFilePath`（主包 1 处，off **303,500**）等成组出现 |
| 握手/订阅的"懒执行 + 记忆化" | `my(e)` + `WeakMap`（off 496,591，见 16 篇 §6）⇒ 同一 scope 只握手一次 |

**为什么"迟迟"而不是"失败"**：桌面端最终会回包（现场 4 个窗口后内容出现），
所以这是**延迟**类问题，不是错误类问题 —— 判据必须是"时间窗 + 是否最终到达"。

---

## 4. 现象三：停更 + 左下角一直转圈

**机制链（当前构建）**：

```js
// ① 桥被判降级：桌面端告知的 bridge-degraded，或本地传输故障
onRawTransportFault: e => { d?.markDegraded(e) }                       // off 4,789,706（连接回调）
// 业务面：bridge-degraded / workspace-bridge-error 且 bridgeSessionId 匹配 ⇒ markDegraded() + T(reason)   （16 篇 §4）
// 本地面：enterDegraded({reasonCode}) 共 17 个原因码（16 篇 §7.2）

// ② 全量恢复：只有 isDegraded() 才走"重开工作区"这条重路
const w = e => e === 'visible' || e === 'pageshow' || e === 'online' || e === 'resume'
const T = async e => { … const o = !w(e) || d?.isDegraded() === true ; o && d?.markDegraded()
  if (await E.recoverConnection(), !o && d && !d.isDegraded()) { return }   // 健康 ⇒ 只重拨
  await C?.({ preferredWorkspaceKey, preferredTaskId }) }                    // ★ 降级 ⇒ 重开工作区/任务

// ③ 恢复失败要等 15s 才升级
recoverFromDeviceOffline(msg){
  this.options.onError(Error(msg)); … this.wasPaired = false
  this.desktopOfflineFailureTimer ||= setTimeout(() => {
      this.enterTerminalFailure({ reason: 'desktop-disconnected', message: msg })
  }, this.options.desktopOfflineGraceMs ?? 15e3)        // ★ 15 秒宽限（off 4,749,305）
  const t = this.socket; this.socket = undefined; t?.close(); this.scheduleReconnect({ immediate:true })
}
```

**呈现层的对应**：

| 现象 | 页面侧 |
| --- | --- |
| 左下角转圈 | 手机外壳的加载动画节点 `[data-zcode-chat-loading-animate]`（主包 1 处）；工作区处于"连接中"时的状态呈现 |
| "连接中"文案 | i18n 键 `webRemoteControl.status.connecting` / `statusDetail.connecting`（六态之一，见 20 篇 §4） |
| 最终失败文案 | `webRemoteControl.failure.*` 共 **12 个**：`connectionRecoveryTimeout`、`desktopBootstrapTimeout`、`desktopDisconnected`、`invalidMobileConnection`、`kicked`、`relayUnavailable`、`sessionConflict`、`sessionExpired`、`sessionNotFound`、`unexpectedError`、`unsupportedAction`、`workspaceClosed` |

> **判定口径**：`desktop-disconnected` 的 15 秒宽限意味着 —— **"转圈"在 15 秒内是正常恢复过程**，
> 15 秒后才升级为终态失败。所以"转圈多久算异常"的阈值必须以它为准，不能凭观感。

---

## 5. 壳（与鸿蒙）能观测到什么、该怎么做

| 现象 | 壳的可用信号 | 处置建议 |
| --- | --- | --- |
| 标题回退 | 页面**不报**；只能靠 relay `title` 与 DOM 标题的差异 | 不主动干预（这是页面兜底设计）；诊断时把它当"元数据读取失败"的证据 |
| 内容延迟 | `data-row-count` / `data-total-row-count` / `data-loading-older` / `data-following` 组合 + 会话帧统计 | 判据要**带时间窗**（不是"立刻没内容就异常"）；壳的档 0（3s/12s）正好是这个量级 |
| 停更 + 转圈 | `[data-zcode-chat-loading-animate]`、`[data-testid="chat-error-banner"]`、`bridge-degraded` 的观测面 | **15 秒宽限内不要抢着 reload**（页面自己在恢复）；超窗仍未恢复才是壳动手的时机 |

**三条跨平台提示（迁移到鸿蒙时）**：

1. **这些机制全在页面侧**（relay 客户端 + React 状态），与宿主平台无关 ⇒ 行为会**原样复现**；
2. 但**触发源**与宿主有关：壳制造 RPC 突发（握手 burst）会直接放大现象二 ⇒ 鸿蒙若做原生承载，必须**串行化 + 限速**；
3. `desktop-disconnected` 的 15 秒宽限应与壳的"卡死恢复"阈值协调（壳侧"卡死恢复必须 3 秒内见效"的既有偏好与它**冲突**，
   迁移时要显式决定跟谁）。

---

## 6. 本次审计的边界（诚实口径）

| 项 | 状态 |
| --- | --- |
| 三段现象是否真实发生过 | ✅ 「外部输入」真机日志（2026-09-11，126 KB，存档在 `android-shell/docs/05-…/_evidence/`） |
| 三段机制在当前构建是否存在 | ✅ 本篇已逐条取证（偏移见各节） |
| **当时那次的触发源**（壳的重复 bridge / 回前台关 socket） | ⚠️ **未重新复核**：那是旧壳版本的行为，当前壳版本的相关机制已大量重写（见迁移文档 §13.16 的"已删机制"）。**不要把旧归因当成对当前壳的指控**。 |
| "转圈"到底对应哪个 DOM 节点 | 【推断】`[data-zcode-chat-loading-animate]` 是最可能的锚点，但**未在真机逐帧确认**（需 CDP 观测） |

---

## 7. 复现

```bash
cd Docs/27-remote-v4-网页快照-20260920/latest/assets
# 标题兜底与回退
python -c "d=open('index-B-ilXaCQ.js',encoding='utf-8',errors='replace').read();print(d[305401-350:305401+260])"
python -c "d=open('index-B-ilXaCQ.js',encoding='utf-8',errors='replace').read();i=d.find('messageLimit:1');print(d[i-500:i+400])"
# 15 秒宽限
python -c "d=open('index-B-ilXaCQ.js',encoding='utf-8',errors='replace').read();i=d.find('desktopOfflineGraceMs');print(d[i-600:i+300])"
# 失败文案枚举
python -c "import re;d=open('index-B-ilXaCQ.js',encoding='utf-8',errors='replace').read();print(sorted(set(re.findall(r'webRemoteControl\.failure\.([A-Za-z0-9]+)',d))))"
# 现场日志（外部输入）
grep -n "Session is not active\|rpc-transport-fault\|unsubscribeConversationV4" \
  ../../../android-shell/docs/05-远程网页-remote-v4-20260911/_evidence/zcode-shell.log | head -20
```

---

## 8. 出处

- 快照：`Docs/27-remote-v4-网页快照-20260920/latest/`（入口与线上逐字节一致）。
- 现场：「外部输入」`android-shell/docs/05-远程网页-remote-v4-20260911/_evidence/zcode-shell.log`。
- 相关篇目：[16 relay 协议目录（降级与恢复）](16-remote-v4-relay协议目录.md) ·
  [18 对话渲染（DOM 契约）](18-remote-v4-功能域-对话渲染与流式更新.md) ·
  [21 回前台对话恢复](21-回前台对话恢复-页面机制与真机事实.md) ·
  [22 壳端注入点审计](22-remote-v4-壳端注入点审计-主题-滚动条-会话加载.md)。
