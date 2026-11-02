# remote-v4 页面重载恢复机制逐条取证

> **审计基线**：3.15.3（2026-10-15 快照）
> 主包 `assets/index-BGa3KiRe.js`（6,592,643 B，SHA-256 `ce2e2fc4…b88fd`）
> schema `assets/validation-CqXZOLXO.js`（307,369 B，SHA-256 `96e64e8f…dd6c9b`）
> 旧基线（2026-09-20 / `index-B-ilXaCQ.js`）的偏移与符号名**一律作废**。
>
> **偏移口径**：`Buffer.indexOf` 得到的**字节偏移**（UTF-8），
> `node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');process.stdout.write(b.subarray(OFF-300,OFF+600).toString())"` 可复现。
> **证据强度**：`【实证】`= 源码原文直接支持；`【推断】`= 证据强但缺一环；`【未复核（旧基线）】`= 本次无对应证据。

本篇回答一个具体问题：**reload / 冷启动之后，页面打开什么、由谁决定。**
（姊妹篇 [21 篇](21-回前台对话恢复-页面机制与真机事实.md) 讲"回前台的恢复通路与真机形态"，本篇只讲**引导链**。）

---

## 0. 一句话

页面启动后按**四跳**决定你看到什么：**⓪ 路由分派 + 版本闸门**（`desktopAppVersion` 与 URL
`app_version` 不一致就整页重载）→ **① 初始页**（`history.state` 命中就是 chat）→
**② 外壳分派**（home / chat，同一组件的两个分支）→ **③ 会话恢复**（**仅当
`navigation.type==='reload'`** 且当前会话为空时，才从 localStorage 槽位读上次会话并 `selectSession`）。
**④ 3.15.3 新增**：`popstate` 事件在会话内实时驱动页状态。
**URL 里没有任何"回哪个会话"的信息**——它只带连接凭据（本条是本次新证）。

---

## 1. 引导链逐跳

### 1.0 第零跳：路由分派与版本闸门（off **6,586,947** `K6n`）

```js
async function K6n(){let e=new URLSearchParams(window.location.search);
  if(v6n(e)){S6n();return}
  if(F3n(window.location.pathname)){await C6n();return}
  if(L6n()){await W6n(e);return}                        // relay 路径（/remote/v4）
  if(e.has(`remoteControlToken`)){try{await G6n(e)}catch(e){K9(…)}return}
  if(b6n(e)){…T6n(e);return} …}
```

**版本闸门 `s6n`（off 6,549,007）——3.15.3 新增，且位于恢复链的最前面**：

```js
function s6n(e){let t=0,n=!1,r,i=!1,a,
  o=()=>{n||i||!r||(i=!0,e.navigate(r))}, s=()=>{t+=1,a=void 0};
  return{ isBlocked:()=>r!==void 0, confirm:o, invalidate:s, dispose(){n=!0,s()},
    check(){ if(n||r)return Promise.resolve(void 0); if(a)return a; let i=t,
      s=Promise.resolve().then(()=>e.requestBootstrap()).then(a=>{ if(n||i!==t)return;
        let s=a.desktopAppVersion?.trim(); if(!s)return a;                     // 字段缺失 ⇒ 不拦
        let c=new URL(e.getUrl());
        if(c.pathname.replace(/\/+$/,'')!==`/remote/v4`||c.searchParams.get(`app_version`)===s)return a;
        c.searchParams.set(`app_version`,s),r=c.toString(),                     // ★ 记下新 URL
        e.isPageReady()?e.onMismatch(s):o() })…; return a=s,s } } }
```

| 事实 | 后果 |
| --- | --- |
| `desktopAppVersion`（relay `bootstrap-response` 新增字段）≠ URL `app_version` ⇒ **整页重载** | 版本不一致时**恢复链被整体短路**（`p.isBlocked()` 为真，`A`/`O`/`L` 全部早退） |
| 字段缺失 ⇒ `return a`（不拦） | 桌面端不回带该字段时，闸门**静默失效** |
| `invalidate()` 让 `t+=1` ⇒ 在飞的 `check()` 结果作废 | 连接状态一变（`onStateChange` off **6,578,419** 里 `p.invalidate()`）**旧闸门结果不会覆盖新状态** |

### 1.1 第一跳：初始页判定（off **4,446,315** `Mbn`）【实证】

```js
var Y0=`idle`,X0=new Set;
function Dbn(e){return X0.add(e),()=>{X0.delete(e)}}      // subscribe
function Obn(){return Y0}                                  // getSnapshot
function kbn(e){if(Y0!==e){Y0=e;for(let e of X0)e()}}      // publish
function Abn(e=Y0){return(0,Q.useSyncExternalStore)(Dbn,Obn,()=>e)}
function Z0(e){return typeof e===`object`&&!!e&&e.zcodeMobilePage===`chat`}          // off 4,446,127
function jbn(){typeof window>`u`||Z0(window.history.state)||window.history.pushState({zcodeMobilePage:`chat`},``)}  // off 4,446,201
function Mbn(e){return e===`chat`||typeof window<`u`&&Z0(window.history.state)?`chat`:`home`}                       // off 4,446,315
```

- **判据只有 `history.state.zcodeMobilePage === 'chat'` 这一条**，没有别的开关参与（3.15.3 逐字相同）。
- `history.pushState` 是**页面自己**在进入对话时写的（不是宿主写）。
- 导航意图 `initialNavigationIntent`（页面自己的 `switchWorkspace/startDraft` 调用传入）**优先于** `history.state`。

### 1.2 第二跳：外壳分派（off **4,446,408** `Nbn`）【实证】

```js
function Nbn({activeTaskId:e,activeWorkspaceIdentity:t,activeWorkspacePath:n,chatContent:r,chatOverlay:i,
  initialNavigationIntent:a,initialWorkspaceList:o,isSidePaneOpen:s=!1,onCloseSidePane:c,onSelectTask:l,
  onStartDraftInWorkspace:u,renderChatHeader:d,sidePaneContent:f,switcher:p,
  webRemoteControlTerminalTransportState:m}){ let{intl:h}=X(),
  [g,_]=(0,Q.useState)(()=>Mbn(a)),                     // ← 第一跳判据
  [v,y]=(0,Q.useState)(()=>vbn()),                      // 用户偏好
  [b,x]=(0,Q.useState)(0),                              // draftFocusVersion / refreshKey
  w=Abn(m), T=(0,Q.useRef)(null), D=()=>{_(`chat`),jbn()},
  k=()=>{ if(x(e=>e+1), typeof window<`u`&&Z0(window.history.state)){window.history.back();return} _(`home`) };
  (0,Q.useEffect)(()=>{a===`chat`&&D()},[a,D]),
  (0,Q.useEffect)(()=>{ if(typeof window>`u`)return; let e=e=>{let t=Z0(e.state)?`chat`:`home`;_(t),t===`home`&&x(e=>e+1)};
     return window.addEventListener(`popstate`,e),()=>{window.removeEventListener(`popstate`,e)} },[]),
  … return g===`home`? <Ebn …/> : <section data-mobile-page="chat" …/> }
```

- `home` 与 `chat` 是**同一组件的两个分支**（不是两条路由），切换只改内部状态 + `pushState`。
- **chat 分支的根节点带 `data-mobile-page="chat"`**（off **4,448,306**）——宿主判断"在不在对话"的锚点。
- 返回按钮（off **4,447,155** 区）**先 `history.back()`**（若 state 是 chat），否则才置 `home`。
- **🆕 `popstate` 监听**（off **4,447,393**）：前进/后退时按 `history.state` 重算页状态；落 `home` 时
  `x(e=>e+1)` 递增 `refreshKey` ⇒ 首页 `Ebn` 的 `useEffect(()=>{d&&I()},[I,d])` 触发**数据重拉**。

### 1.3 第三跳：会话恢复（`mun` + `pun` + `jQ`）【实证】

```js
// 重载判据（标准 Navigation Timing；off 3,688,391 / 3,688,550）
function Acn(){try{return typeof globalThis.performance?.getEntriesByType===`function`
  ?globalThis.performance.getEntriesByType(`navigation`):[]}catch{return[]}}
function jQ(e=Acn()){return e[0]?.type===`reload`}                       // 'reload' / 'navigate' / 'back_forward'

// 槽位（off 3,724,155 / 3,724,182 / 3,724,216 / 3,724,290）
var uun=`zcode-v4-last-session:v1:`; function C$(e){return`${uun}${e}`}
function dun(e){try{return localStorage.getItem(C$(e))}catch{return null}}
function fun(e,t){try{t?localStorage.setItem(C$(e),t):localStorage.removeItem(C$(e))}catch{}}

// ★ 用不用槽位：四条件同时成立（off 3,724,383）
function pun(e){return e.enabled&&e.rendererReload&&e.activeSessionId===null&&e.draftFocusVersion===0}
```

`mun`（off 3,724,485）的两个 effect（恢复侧 / 记账侧）与四条语义见
[21 篇 §2.1](21-回前台对话恢复-页面机制与真机事实.md)，要点复述：

1. 槽位**只在 `jQ()` 为真（`navigation.type==='reload'`）时被读**；冷启动 `navigate` 不读；
2. 读时还要求 **`activeSessionId===null`** 与 **`draftFocusVersion===0`**；
3. **条件不满足且无会话 ⇒ 页面主动删槽位**（`fun(e,null)`）——槽位不是持久记忆；
4. 恢复动作 = 页面自己的 `selectSession(sessionId)`。

**消费者定位（本轮新证）**：`mun` 的唯一调用点在 off **5,690,890**，位于 `fUn`
（`var fUn=(0,Q.memo)(function({…})`，off **5,686,317**）：

```js
Mn=B?.trim()||R, Nn=LPn(Ce?.tabs??[]), Pn=Nn?q3(Ce?.tabs??[],Nn):void 0;
mun({workspaceKey:Mn,activeSessionId:De,draftFocusVersion:U.draftFocusVersion,enabled:!rn,selectSession:e=>ot(R,e,B)});
```

`fUn` 由 `RUn`（memo 为 `r1n`）在 off **5,753,025** 渲染 → `i1n`（off 6,296,603）→ `J2n`（off 6,449,960）→ `p9`（应用根，off 6,449,317），
而 `p9` 正是 relay 与 HTTP 两条远控路径渲染的**同一棵应用树**（`te()` off 6,581,496 / `m()` off 6,585,196）。
⇒ 槽位机制在 `/remote/v4` 页面上**是活的**。

---

## 2. 「重载后状态从哪里恢复」——四条通路逐条给触发条件与证据

| 状态 | 载体 | 写入者 | 读取触发条件 | 跨 reload | 跨进程 | 证据 |
| --- | --- | --- | --- | --- | --- | --- |
| 当前页（chat/home） | `history.state.zcodeMobilePage` | 页面 `jbn()` off 4,446,201 | `Z0()` off 4,446,127 → `Mbn()` off 4,446,315；**`popstate`** off 4,447,393 | ✅ | ❌ | 【实证】 |
| 上次会话 | `localStorage['zcode-v4-last-session:v1:<workspaceKey>']` | 页面 `fun()` off 3,724,290；宿主预设时也写 | `dun()` off 3,724,216，**仅 `jQ()` 为真且 `activeSessionId===null` 且 `draftFocusVersion===0`** | ✅ | ✅ | 【实证】 |
| **URL** | `location.search` | 宿主/桌面端生成链接 | 入口 `K6n()` off 6,586,947 → `gn`=`Bd`（**`src-AL-_ONNp.js` off 63,174**） | ✅ | ✅ | 【实证】 |
| 桌面侧视图态 | relay `mobileViewState` / `initialViewState` | 页面 `B` off 6,579,893（relay）/ `A6n` off 6,557,927（HTTP） | 恢复时 `O()` off 6,584,053 → `t.workspaces.find(…)` / `I6n`=`Ud`（`src` off 63,951） | —— | ✅（在桌面端） | 【实证】 |

### 2.1 URL 通路：**只带连接凭据，不带会话**（本轮新证）

```js
// src-AL-_ONNp.js off 63,174
function Bd(e){let t=V(e,`sid`),n=V(e,`hash`),r=V(e,`t`),i=r?Number(r):NaN,a=V(e,`theme`);
  return !t||!n||!Number.isFinite(i)?null:{deviceSid:t,passHash:n,timestamp:i,
    ...V(e,`mid`)?{deviceMid:V(e,`mid`)}:{}, ...V(e,`name`)?{deviceName:V(e,`name`)}:{},
    ...V(e,`app_version`)?{appVersion:V(e,`app_version`)}:{}, ...Ld(a)?{theme:a}:{}}}
```

| URL 参数 | 语义 | 页面是否消费 |
| --- | --- | --- |
| `sid` | deviceSid（必填，缺失 ⇒ `Bd` 返回 `null` ⇒ 报 `invalid-mobile-connection`） | ✅ |
| `hash` | passHash（必填） | ✅ |
| `t` | 时间戳（必填，须 finite；**解析后无人读**） | 仅作有效性闸门 |
| `mid` | deviceMid → 拼到 relay WS URL 的 `?mid=`（off 6,522,910） | ✅ |
| `name` | deviceName | ⚠️ 未见消费点 |
| `app_version` | 版本闸门比对基准（`s6n.check`） | ✅ |
| `theme` | 主题 | ✅ |
| `app_version`（闸门回写） | `s6n` 不一致时**改 URL 后重载** | ✅ |

⇒ **"回哪个会话"完全不在 URL 里**。想在 reload 后落到某个会话，**只能靠槽位或 `history.state`**。

### 2.2 服务端 / relay 通路：重开桥的选桥规则（off **6,584,053** `O`）

```js
O=async e=>{let t=await L();if(!t||a||p.isBlocked())return;
  let n=e?.preferredWorkspaceKey?t.workspaces.find(t=>ja(t)===e.preferredWorkspaceKey):void 0,
      r=n?{workspaceKey:ja(n),taskId:e?.preferredTaskId,
           canBridge:n.kind!==`remote`||!!(n.workspaceIdentity&&n.remoteSessionId)}:I6n(t);
  if(!r)throw q9(`workspace-closed`,`No opened desktop workspace is available for Web remote control.`);
  if(!r.canBridge){await H(r.workspaceKey,r.taskId);return}
  let i=await V(r.workspaceKey,{taskId:r.taskId});te(i.services,i.bridge)}
```

- `L()`（off 6,579,161）＝ **bootstrap 结果单飞缓存**：`L=()=>{if(I)return I; let e=P(async()=>{let e=await p.check(); …})}`。
- 无首选工作区时回退 `I6n`=`Ud`（`src-AL-_ONNp.js` off 63,951）：
  `o = mobileViewState ?? initialViewState`，取 `activeWorkspaceKey`/`activeTaskId`；
  在"可承载工作区集合"（`Hd`：非 remote 或含 `workspaceIdentity && remoteSessionId`）里命中就用它，
  否则退到第一个可承载工作区，再否则退到任意工作区（`canBridge:false`）。
- ⇒ **桌面端（服务端侧）通过 `mobileViewState` 决定"新终端绑哪个窗口"**，这是跨进程唯一通路。

---

## 3. 冷路径 vs 热路径（一张对照表）

| 场景 | 导航类型 | `history.state` | 槽位是否读 | URL 带会话? | 你会看到 |
| --- | --- | --- | --- | --- | --- |
| 首次打开 / 新开页 | `navigate` | 空 | **不读** | ❌ | home |
| **宿主执行 `location.reload()`** | **`reload`** | **保留**（同进程内存） | **读**（若无会话） | ❌ | `history.state` 命中 ⇒ chat **且**打开上次会话 |
| 用户按 F5 | `reload` | 保留 | 读 | ❌ | 同上 |
| 进程被杀后重新打开 | `navigate` | **归零** | 不读 | ❌ | **home** |
| 浏览器前进/后退 | `back_forward` | 保留 | **不读**（`jQ()` 为假） | ❌ | **🆕 由 `popstate` 按 `history.state` 决定页状态** |
| 版本不一致时被闸门重载 | `navigate`（`location.replace` 改 URL） | **归零** | **不读** | ❌ | home（⚠️ 推测，需真机确认） |

---

## 4. 逐条结论（旧基线 10 条逐条复核 + 新增）

### 4.1 旧基线 10 条复核

| # | 旧结论 | 3.15.3 复核 | 强度 |
| --- | --- | --- | --- |
| 1 | "在不在对话页"只有一个判据：`history.state.zcodeMobilePage==='chat'` | ✅ 仍成立（`Z0` off 4,446,127 / `Mbn` off 4,446,315）；**新增** `popstate` 也用它 | 【实证】 |
| 2 | 进对话由页面自己 `pushState`，宿主不写也能进 | ✅ 仍成立（`jbn` off 4,446,201） | 【实证】 |
| 3 | home 与 chat 不是路由，是同一组件的两个分支 | ✅ 仍成立（`Nbn` 三元渲染；`data-mobile-page` off 4,448,306） | 【实证】 |
| 4 | 槽位键 = `zcode-v4-last-session:v1:<workspaceKey>`，按工作区存 | ✅ 仍成立（`uun`/`C$` off 3,724,155/3,724,182） | 【实证】 |
| 5 | 槽位**只在 `type==='reload'` 时被消费** | ✅ 仍成立（`jQ` off 3,688,550 + `pun.rendererReload`） | 【实证】 |
| 6 | 消费时要求当前无会话；否则页面**清槽位** | ✅ 仍成立（`mun` 第一 effect 的 `t===null&&fun(e,null)`） | 【实证】 |
| 7 | 冷启动不读槽位 ⇒ 落 home | ✅ 仍成立（`navigate` 路径） | 【实证】+【推断】 |
| 8 | 进程被杀后 `history.state` 归零 ⇒ 重载也落 home | ✅ 机制成立（`history.state` 是进程内内存）；**真机 5 分 46 秒现场【未复核（旧基线）】** | 【实证】+【未复核（旧基线）】 |
| 9 | 宿主"写槽位 + 钉 `history.state` + reload"**正好命中这两条通路** | ✅ 仍成立：槽位消费者 `fUn`（off 5,686,317）在远控页面上活的，调用点 off 5,690,890 | 【实证】 |
| 10 | 槽位**不是**"常态承重"机制 | ✅ 仍成立 | 【实证】 |

### 4.2 新增结论（旧基线没有）

| # | 新结论 | 证据 | 强度 |
| --- | --- | --- | --- |
| 11 | **URL 不承载会话/任务**：只有 `sid`/`hash`/`t`/`mid`/`name`/`app_version`/`theme` | `Bd`（`src-AL-_ONNp.js` off 63,174） | 【实证】 |
| 12 | **`popstate` 成为第四条状态通路**，并在落 home 时递增 `refreshKey` 触发首页重拉 | `Nbn` off 4,447,393；`Ebn` off 4,428,882 的 `useEffect(()=>{d&&I()},[I,d])` | 【实证】 |
| 13 | **版本闸门 `s6n` 前置在恢复链之前**：`desktopAppVersion` ≠ URL `app_version` ⇒ 弹窗/改 URL 重载；`p.isBlocked()` 让整条恢复链短路 | off 6,549,007；调用点 `A` off 6,577,262 / `O` off 6,584,053 / `L` off 6,579,161 | 【实证】 |
| 14 | **请求层自带"恢复并重试"**：`P=q3n(()=>j.recoverConnection())`，失败按 `K3n` 分类（`relay-unavailable`，或 `recoverTimeout` 下的 `desktop-bootstrap-timeout`）⇒ 单飞 `recoverConnection()` 后重试一次 | `q3n` off 6,534,994 / `K3n` off 6,534,864 / `P` off 6,578,966 | 【实证】 |
| 15 | **槽位消费点唯一**：`mun` 只被 `fUn`（远控与桌面共用的应用外壳）调用一次 | off 5,690,890 / 5,686,317 | 【实证】 |
| 16 | `recoverConnection` 的"原状态快路径"改为**转 `reconnecting` + 发 `pair_status_query` + 最多等 3s** | off 6,524,997 | 【实证】 |

### 4.3 明确**不生效**的路径（逐条）

| 路径 | 是否生效 | 依据 |
| --- | --- | --- |
| 用 URL 参数指定"回哪个会话" | **❌ 不生效** | `Bd` 不解析任何会话参数（off 63,174） |
| 冷启动（`navigate`）读槽位 | **❌ 不生效** | `jQ()` 只在 `type==='reload'` 时为真 |
| `back_forward` 读槽位 | **❌ 不生效**（但 `history.state` **会**被 `popstate` 读） | `jQ()` + off 4,447,393 |
| 进程被杀后靠 `history.state` 回到 chat | **❌ 不生效**（进程内内存归零） | 机制；真机现场【未复核（旧基线）】 |
| "只写槽位、不 reload"就跳任务 | **❌ 不生效** | `pun.rendererReload` 要求 `reload` |
| 健康连接下靠"回前台"把内容拉回来 | **❌ 不生效** | `A`（off 6,577,262）健康路径只跑 `L()` 后 return |

---

## 5. 恢复的时序与竞态

### 5.1 启动/恢复时序（谁先谁后）

```
页面加载
 └─ K6n() 路由分派 (off 6,586,947)
     └─ W6n() (off 6,575,323) 建立 relay 传输，注册 Y3n 可见性监听 (off 6,536,133)
         └─ onStateChange: 进入 `paired`
             ├─ 若桥健康 或 n（重开中）⇒ L()  (off 6,579,161)  ← 单飞 bootstrap（版本闸门 + 缓存 workspaces）
             └─ 否则          ⇒ O({preferredWorkspaceKey,preferredTaskId}) (off 6,584,053)
                                 └─ O 内部先 await L()，再按 preferredWorkspaceKey / I6n 选桥
                                     └─ V() 发 workspace-bridge-open → te() 渲染应用树
                                         └─ fUn 内 mun(...) (off 5,690,890)  ← 槽位恢复（仅 reload）
```

### 5.2 竞态清单

| # | 竞态 | 页面怎么处理 | 证据 |
| --- | --- | --- | --- |
| A | **版本闸门结果覆盖新状态** | `invalidate()` 让 `t+=1`；`check()` 内 `if(n\|\|i!==t)return` ⇒ 在飞结果作废 | off 6,549,007（调用点 `onStateChange` off 6,578,419 的 `p.invalidate()`） |
| B | **并发 bootstrap** | `L()` 用 `I` 做单飞缓存（`if(I)return I`）；`p.check()` 用 `a` 做单飞 | off 6,579,161 / 6,549,007 |
| C | **并发 `recoverConnection`** | `q3n` 的 `n()` 用 `t\|\|=e().finally(()=>{t=void 0})` 做单飞 | off 6,534,994 |
| D | **槽位恢复被"记账侧"提前覆盖** | `s.current`（pending）机制：恢复的会话**还没选上**（`activeSessionId===null`）时记账侧直接 `return`，不写槽位 | `mun` 第二 effect，off 3,724,485 |
| E | **`A`（恢复）与 `O`（重开）互相踩** | `A` 用 `r` 重入锁 + `n` 在飞标志；`onStateChange`（off 6,578,419）的 `paired` 分支用 `n&&I` 抑制重复重开 | off 6,577,262 / 6,578,419 |
| F | **`mobileViewState` 重复上报** | `Nbn` 用 `T.current` 存 `${workspaceKey}:${taskId}` 去重，值不变就不发 | off 4,447,486 |
| G | **`popstate` 重拉 与 推送整份替换 竞争** | `popstate` 落 home ⇒ `refreshKey++` ⇒ `listWorkspaces()`；同时 `onWorkspaceListUpdated` 回调直接 `_(e)` 覆盖本地态——**后者无版本号，谁后到谁赢** ⚠️ 推测（无去重证据） | off 4,447,393 / `Ebn` off 4,428,882 |
| H | **请求超时** | 默认 `timeoutMs=1e4`（10s）⇒ 抛 `desktop-bootstrap-timeout`；`workspace-reconnect-request` 用 `Nre`＝`Id`＝`12e4`（**120s**） | `J3n` off 6,535,176（调用点 `M=J3n(` off 6,578,887）；`Id` `src-AL-_ONNp.js` off 62,626 |

> 竞态 G 是**唯一在源码里找不到去重/版本保护**的一条，属于"服务端推送 vs 客户端重拉"的经典覆盖风险。

---

## 6. 对原生宿主（与鸿蒙）的含义

1. **通知点击定位**：`钉 history.state` → `写槽位` → `reload()` 是**正确且必要**的三连
   ——因为只有 reload 路径才会读槽位（§4.1 #5/#9）。
2. **不要指望"只写槽位"就能跳任务**：必须伴随 reload（或页面自己的 `selectSession` 通路）。
3. **不要指望 URL**：3.15.3 的远控 URL 只带连接凭据（§2.1）。
4. **进程被杀后的恢复**是**页面设计上就做不到的**（`history.state` 不跨进程）⇒ 只能由宿主外部干预。
5. **新增必须处理的闸门**：宿主/桌面端要**回带 `desktopAppVersion`**；若不一致，页面会
   `location.replace` 到带 `app_version` 的新 URL ⇒ **整页重载、槽位不读**（`navigate` 路径）——
   这会让"钉 state + reload"的三连在版本切换那一刻**失效一次**。
6. **迁移到鸿蒙**：四条通路的实现全是标准 Web API（`history` / `localStorage` / Navigation Timing /
   `popstate`），**逐行可移植**。

---

## 7. 复现

```bash
cd E:/Zcode_android/Reference_web/3.15.3/assets
# 初始页判定与外壳分派（含 popstate）
node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');process.stdout.write(b.subarray(4446100,4448500).toString())"
# 槽位三件套 + 门控 + 重载判据
node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');process.stdout.write(b.subarray(3688380,3688600).toString())"
node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');process.stdout.write(b.subarray(3724100,3725100).toString())"
# 槽位唯一调用点（fUn 内）
node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');process.stdout.write(b.subarray(5690840,5690960).toString())"
# URL 参数解析（无会话参数）
node -e "const b=require('fs').readFileSync('src-AL-_ONNp.js');process.stdout.write(b.subarray(63100,63400).toString())"
# 版本闸门
node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');process.stdout.write(b.subarray(6549000,6549600).toString())"
# 恢复选桥 O 与单飞 L
node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');process.stdout.write(b.subarray(6579100,6579300).toString())"
node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');process.stdout.write(b.subarray(6584050,6584300).toString())"
# 请求层恢复重试
node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');process.stdout.write(b.subarray(6534850,6535120).toString())"
```

---

## 8. 出处

- 网页端：`Reference_web/3.15.3/assets/index-BGa3KiRe.js`、`src-AL-_ONNp.js`（只读快照）。
- 【未复核（旧基线）】项：进程被杀后 5 分 46 秒真机现场（沿用 2026-09-20 观测）。
- 相关篇目：[21 回前台对话恢复](21-回前台对话恢复-页面机制与真机事实.md)（姊妹篇：恢复通路与真机形态）·
  [13 bootstrap 消费面](13-remote-v4-bootstrap载荷-我们解析了哪些.md) ·
  [22 宿主注入点审计](22-remote-v4-壳端注入点审计-主题-滚动条-会话加载.md) ·
  [10 扩展面与可观测面](10-remote-v4-网页扩展面与可观测面穷尽盘点.md)。
