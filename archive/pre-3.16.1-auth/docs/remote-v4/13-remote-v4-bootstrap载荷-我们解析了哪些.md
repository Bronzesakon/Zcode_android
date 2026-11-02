# remote-v4 bootstrap / workspace-list 载荷：网页端消费面盘点

> **审计基线**：3.15.3（2026-10-15 快照）
> 主包 `assets/index-BGa3KiRe.js`（6,592,643 B，SHA-256 `ce2e2fc4…b88fd`）
> schema `assets/validation-CqXZOLXO.js`（307,369 B，SHA-256 `96e64e8f…dd6c9b`）
> 旧基线（2026-09-20 / `index-B-ilXaCQ.js`）的偏移与符号名**一律作废**。
>
> **本篇视角已切换**：旧版问的是"**旧 Android 壳**解析了哪些字段"；新版问的是
> "**网页端自己消费了哪些**"——因为新宿主（本项目原生 Kotlin）不照抄旧壳的取舍。
> 旧壳的消费面只作为**一列对照**保留在 §5，不再作为本文的结论主体。
>
> **偏移口径**：`Buffer.indexOf` 得到的**字节偏移**（UTF-8），
> `node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');process.stdout.write(b.subarray(OFF-300,OFF+600).toString())"` 可复现。
> 标「未复核（旧基线）」= 该结论来自 2026-09-20 旧构建，本次未在新构建上找到对应物。
> 标「⚠️ 推测」= 证据不足的推断。

---

## 0. 一句话

网页端对 bootstrap 载荷的消费面**比旧壳宽得多，但形状完全不同**：
relay 路径下 `bootstrap-response.result` 的 6 个顶层键里**读了 4 个**
（`desktopAppVersion` / `workspaces` / `mobileViewState` / `initialViewState`），
**没读 2 个**（`windowControlSessionId` / `tasks`）；
而旧壳只读 `result.tasks[]` 里 3 个叶子字段。**`tasks` 的消费权在 3.15.3 已经整体让给了
`workspace-list-response` / `workspace-list-updated` 这一族**——这是与旧基线最本质的差异。

---

## 1. 网页端有两条互不相通的 bootstrap 通道

页面入口路由 `K6n()`（off **6,586,944**）按 URL 形状分派，bootstrap 也因此有两套：

```js
async function K6n(){let e=new URLSearchParams(window.location.search);
  if(v6n(e)){S6n();return}
  if(F3n(window.location.pathname)){await C6n();return}
  if(L6n()){await W6n(e);return}                       // ← relay 路径（deviceSid/passHash）
  if(e.has(`remoteControlToken`)){...await G6n(e)...}  // ← HTTP token 路径
  ...
```

| 通道 | 触发条件 | 载荷来源 | 页面入口 | 载荷形状 |
| --- | --- | --- | --- | --- |
| ① **relay** | 路径匹配 `/remote/v4`（`L6n()` off **6,565,547**） | `bootstrap-request` / `bootstrap-response`（WS） | `W6n()` off **6,575,323** | `sS`（6 键） |
| ② **HTTP token** | URL 带 `remoteControlToken` | `GET /api/remote-control/windows/bootstrap/:token` | `O6n()` off **6,556,817** | 服务端响应 `{workspaces,tasks,mobileViewState,initialViewState}`；`O6n` 归一化为 `{workspaces,tasks,activeWorkspaceKey,activeTaskId}`（**另一种形状**） |

**路由基址是新事实**：`BASE_URL:'/remote/v4/3.15.3/'`、`VITE_WEB_REMOTE_CONTROL_ROUTE_PATH:'/remote/v4'`
（`L6n()` off **6,565,547**，内联常量）。即 3.15.3 的资源挂在**带版本号的子路径**下，
旧基线"`zcode.z.ai/remote/v4` 一个扁平路径"的描述已不准确。

> ⚠️ 两条通道**不要混用**：HTTP 通道的 `windowControlSessionId` 来自另一条 WS 的
> `window-control-ready` 帧（off **6,564,634**），**不是** relay `bootstrap-response` 提供的。

---

## 2. `bootstrap-response.result` 逐键消费表（relay 路径）

schema 定义 `sS` 在 `validation-CqXZOLXO.js` off **284,202**：

```js
sS = O({windowControlSessionId:$,desktopAppVersion:$.optional(),workspaces:tS,tasks:nS,
        initialViewState:aS.optional(),mobileViewState:aS.optional()})
// 旧基线记 5 键（dg）；新构建 6 键，新增 desktopAppVersion
// 必填：windowControlSessionId / workspaces / tasks；可选：desktopAppVersion / initialViewState / mobileViewState
```

| # | 键 | 网页端读? | 读取点（3.15.3） | 用途 / 判据 |
| --- | --- | --- | --- | --- |
| 1 | `windowControlSessionId` | **❌ relay 路径不读** | 仅 HTTP 通道由 `window-control-ready` 提供 | schema 必填，但 relay 消费链零引用 |
| 2 | `desktopAppVersion` | **✅ 读** | off **6,549,335**（`s6n.check`） | **版本闸门**：与 URL `app_version` 比对，不一致 ⇒ `isPageReady()? onMismatch(s) : navigate()`（弹窗/改 URL 重载） |
| 3 | `workspaces[]` | **✅ 读** | off **6,584,053**（`O` → `t.workspaces.find(t=>ja(t)===e.preferredWorkspaceKey)`） | 恢复时"按首选工作区选桥"；元素读 `workspacePath` / `workspaceIdentity` / `kind` / `remoteSessionId` |
| 4 | `tasks[]` | **❌ relay 路径不读** | 全 bundle 只有 HTTP loader 读 `.tasks`（off **6,557,184**） | relay 下任务清单改由 `workspace-list-response` / `workspace-list-updated` 供给 |
| 5 | `initialViewState` | **✅ 读（回退位）** | off **6,565,517**（`I6n`=`Ud`，实现在 `src-AL-_ONNp.js` off **63,951**） | `o = mobileViewState ?? initialViewState` ⇒ 取 `activeWorkspaceKey` / `activeTaskId` |
| 6 | `mobileViewState` | **✅ 读（优先位）** | 同上 | 同上；**优先级高于 `initialViewState`** |

**读取原文（版本闸门，off 6,549,335 区）**：

```js
let s=a.desktopAppVersion?.trim();if(!s)return a;
let c=new URL(e.getUrl());
if(c.pathname.replace(/\/+$/,'')!==`/remote/v4`||c.searchParams.get(`app_version`)===s)return a;
c.searchParams.set(`app_version`,s),r=c.toString(),e.isPageReady()?e.onMismatch(s):o()
```

**读取原文（恢复选桥，off 6,584,053 区）**：

```js
O=async e=>{let t=await L();if(!t||a||p.isBlocked())return;
  let n=e?.preferredWorkspaceKey?t.workspaces.find(t=>ja(t)===e.preferredWorkspaceKey):void 0,
      r=n?{workspaceKey:ja(n),taskId:e?.preferredTaskId,
           canBridge:n.kind!==`remote`||!!(n.workspaceIdentity&&n.remoteSessionId)}:I6n(t);
  if(!r)throw q9(`workspace-closed`,`No opened desktop workspace is available for Web remote control.`);
  if(!r.canBridge){await H(r.workspaceKey,r.taskId);return}
  let i=await V(r.workspaceKey,{taskId:r.taskId});te(i.services,i.bridge)}
```

> `L()`（off **6,579,161**）是"bootstrap 结果缓存器"：`L()=()=>{if(I)return I; let e=P(async()=>{let e=await p.check(); ...})}`
> —— `p.check()` 内部就是 `requestBootstrap()`。**`bootstrap-response.result` 全页只有一个真正的消费者 `O`**，
> 它读 `workspaces`，回退读 `mobileViewState ?? initialViewState`。

**请求侧（off 6,578,999）**：

```js
F=async()=>{let e=X9(`bootstrap`);
  return (await N({zcode_type:`bootstrap-request`,requestId:e},
                  t=>t.zcode_type===`bootstrap-response`&&t.requestId===e)).result}
```

⇒ **网页端按 `requestId` 严格配对**（旧壳不回配 requestId——旧基线 §6 ③ 的"壳不回配"在网页端不成立）。

---

## 3. `workspaces[]` / `tasks[]` 的元素级消费

### 3.1 workspace 元素（schema `tS`，8 键，off 282,217）

```js
tS = D(O({workspacePath:$,workspaceIdentity:$.optional(),remoteSessionId:$.optional(),label:$,
          workspacePurpose:M([`project`,`conversation`]).optional(),
          kind:M([`local`,`remote`]),
          connectionState:M([`connected`,`disconnected`,`reconnecting`]).optional(),
          lastConnectionError:S().optional()}))
```

| 键 | 网页端读? | 读取点 | 用途 |
| --- | --- | --- | --- |
| `workspacePath` | ✅ | off 4425045（`dbn`→`ja`）、4442734（显示） | 工作区键回退位；界面展示 |
| `workspaceIdentity` | ✅ | off 4425045 / src 63,886（`U`） | **工作区键第一优先**（`identity?.trim()‖path`） |
| `remoteSessionId` | ✅ | off 4440876 / src 63,886（`Hd`） | 判定"远程工作区是否可承载"；缺它 ⇒ 显示"请重连" |
| `label` | ✅ | off 4,441,928 | 工作区分组标题（`purpose!=='conversation'` 时） |
| `workspacePurpose` | ✅ | off 4,441,118 | `'conversation'` ⇒ 换图标/换标题文案 |
| `kind` | ✅ | off 4440876 / 6584254 | `'remote'` ⇒ 远程图标 + 断连判定 |
| `connectionState` | ✅ | off 4440876 / 4,581,178 | `disconnected`/`reconnecting` ⇒ 禁用该工作区入口 |
| `lastConnectionError` | ✅ | off 4,442,734 | 直接渲染为红色错误文案 |

**8/8 全消费**（旧壳对 `workspaces[]` 是 0 消费）。

### 3.2 task 元素（schema `nS`，**16 键**，off 282,649）

```js
nS = D(O({taskId:$,title:S(),workspacePath:$,workspaceIdentity:$.optional(),remoteSessionId:$.optional(),
          workspaceLabel:$,workspaceKind:M([`local`,`remote`]),createdAt:w().finite(),updatedAt:w().finite(),
          provider:Ou.optional(),unreadAt:w().finite().optional(),
          displayStatus:M([`idle`,`running`,`completed`,`error`]).optional(),
          hasBackgroundWork:T().optional(),workflowActivity:ud.optional(),
          pinned:T().optional(),archived:T().optional()}))
```

| 键 | 网页端读? | 读取点 | 用途 |
| --- | --- | --- | --- |
| `taskId` | ✅ | off 4415186（`L0`）、4426849 | 任务键、`data-testid` |
| `title` | ✅ | off 4446176、4,423,105 | 列表标题 |
| `workspacePath` / `workspaceIdentity` | ✅ | off 4425045（`ja`） | **任务→工作区分组键**（`ja(e)=identity?.trim()‖path`） |
| `workspaceLabel` | ✅ | off 4425297（`fbn` 搜索） | 仅搜索匹配串 |
| `workspaceKind` | ⚠️ 仅本地合并路径（非 relay 载荷路径） | off 4,423,105 | 合并本地 tab 时使用 |
| `createdAt` | ✅ | off 4,437,953 | 排序切到"创建时间"时用（`C==='created'?createdAt:updatedAt`） |
| `updatedAt` | ✅ | off 4427341、4,423,105 | 默认排序键；"x 分钟前"文案 |
| `provider` | ⚠️ 仅本地合并路径（非 relay 载荷路径） | off 4,423,105 | 本地合并路径透传 |
| `unreadAt` | ✅ | off 4425297、4446176 | `typeof unreadAt==='number'` ⇒ 未读点 + 分组展开 |
| `displayStatus` | ✅ | off 4422876、4446176 | **状态徽标**；`??'idle'` 兜底 |
| `hasBackgroundWork` | ✅ | off **4,422,876**（`tbn`） | **"算不算在跑"的第二判据** |
| `workflowActivity` | ✅ | off 4446176、4,437,953 | 渲染工作流活动条 |
| `pinned` | ✅ | off 4427091、4,425,745 | 置顶分组 |
| `archived` | ✅ | off 4427091、4,425,745 | 归档分组（主列表过滤掉） |
| `remoteSessionId` | ⚠️ 仅搜索串（`fbn` off 4,425,297） | —— | 无独立判据 |

**关键新增判据**（off **4,422,876**）：

```js
var tbn = e => e.displayStatus===`running` || e.hasBackgroundWork===!0;
```

⇒ 网页端"任务在跑"= `displayStatus==='running'` **或** `hasBackgroundWork===true`。
而 `displayStatus` 的枚举在新构建里**收窄为 `['idle','running','completed','error']`**
（off 282,907）——**没有 `waiting` / `prewarming`**。
旧壳写的 `ACTIVE = {running, waiting, prewarming}`（见 §5）在 3.15.3 的 relay 载荷里**不存在对应值**。
（`prewarming` 只出现在**本地运行时 phase** 映射 `obn` 里：`case 'prewarming':case 'running':return 'running'`，off **4,423,278** `function obn(`。）

### 3.3 分组与丢弃规则（off 4,425,045 `dbn`）

```js
function dbn({workspaces:e,tasks:t,sortBy:n=`updated`}){
  let r=e.map(e=>({workspaceKey:ja(e),workspace:e,tasks:[],hasUnread:!1})),
      i=new Map(r.map(e=>[e.workspaceKey,e]));
  for(let e of t){let t=i.get(ja(e));t&&t.tasks.push(e)}      // ★ 匹配不上就丢弃
  return r.map(e=>({...e,tasks:W0(e.tasks,n),
                    hasUnread:e.tasks.some(e=>typeof e.unreadAt===`number`)}))}
```

**任务按 `ja` = `workspaceIdentity?.trim()‖workspacePath` 归组；归不上的任务被静默丢弃。**
这与旧壳 `RelayTaskDigest.keyOf` 的"键顺序不能反"是**同一条规则**，且网页端四处实现逐字一致
（off 70,323 / 3,688,524 `FQ` / 4425045 / src 63,886 `U`）。

---

## 4. workspace-list 家族（网页端真正的任务清单来源）

| 帧 | 方向 | 页面落点 | 载荷 |
| --- | --- | --- | --- |
| `workspace-list-request` | 页面→桌面 | off **6,579,686**（`z`） | `{requestId}` |
| `workspace-list-response` | 桌面→页面 | 同上，`.result` 交给 `x()` | `cS = {workspaces, tasks?, activeWorkspaceKey?, activeTaskId?}`（off **284,350**） |
| `workspace-list-updated` | 桌面→页面（推送） | off **6,578,071**：`if(e.zcode_type==='workspace-list-updated'){S(e.result);return}` | 同上 `cS`，**整份替换** |

```js
// off 6,575,915 区（页面订阅/发布骨架）
y=new Set, b=e=>(y.add(e),()=>{y.delete(e)}),                     // 订阅登记（= onWorkspaceListUpdated）
x=e=>({...e, activeWorkspaceKey:e.activeWorkspaceKey??s, activeTaskId:e.activeTaskId??c}),
S=e=>{let t=x(e);for(let e of y)e(t)},                            // 广播
z=async()=>{let e=async()=>{let e=X9(`workspace-list`);
  return N({zcode_type:`workspace-list-request`,requestId:e},
           t=>t.zcode_type===`workspace-list-response`&&t.requestId===e)};
  return x((await P(()=>e(),{recoverTimeout:!0})).result)}
```

- `x()` 的语义：**载荷没给 `activeWorkspaceKey`/`activeTaskId` 时，用页面当前值补齐**（`s`/`c`）。
- 消费侧（首页组件 off **4,426,849** `bbn`）：

```js
c=(i.tasks??[]).filter(e=>!e.archived);          // 归档过滤
l=W0(c.filter(e=>e.pinned),r);                    // 置顶
d=dbn({workspaces:i.workspaces,tasks:u});         // 按工作区分组
o=i.activeWorkspaceKey??ja({workspacePath:n,workspaceIdentity:t})   // 结果优先，props 回退
```

⇒ **旧基线"`workspace-list-updated` 壳端零消费、只有页面侧消费"在新构建上依然成立**，
且页面现在是"主动 request + 应答"**和**"推送整份替换"**两条都走**。

---

## 5. 旧壳 vs 网页端：消费面对照（旧壳列＝历史对照，不再是结论主体）

| 载荷 | 旧 Android 壳（2026-09-20 基线，`android-shell/…`） | 3.15.3 网页端 |
| --- | --- | --- |
| `bootstrap-response.result` 顶层 | **0 键**（只把 `result` 整体丢给 `RelayTaskDigest`） | **4/6 键**：`desktopAppVersion` / `workspaces` / `mobileViewState` / `initialViewState` |
| `result.tasks[]` | **3 叶子**：`displayStatus`（主）/ `workspaceIdentity` / `workspacePath`（+ `liveStatus`、`address.*`、`meta.*` 兼容位；`workspaceLabel` 仅诊断） | **relay 路径不读**；改由 workspace-list 族供给（读 **13/16** 键） |
| `result.workspaces[]` | **0** | **8/8 键** |
| `windowControlSessionId` | 0 | 0（relay 路径）；HTTP 路径另有来源 |
| `mobile-view-state-update.deviceInfo` | 只发不读（旧基线 §6 ②） | **页面自己发**（`o6n`，off 6,548,210，12 键）；同一 bundle 内桌面端 UI 读 `mobileDeviceInfo`（off 4,659,000 区） |
| `requestId` 回配 | **不回配** | **严格回配**（`requestId===e`，off 6,578,999 / 6,579,686 / 6,579,438） |

旧壳的 `RelayTaskDigest` 四条纪律（`ACTIVE` 集合 / `displayStatus` 优先 / 键顺序 / 不拿 `label` 兜底）
中，**只有"键顺序"和"不拿 label 兜底"在网页端能找到对应物**；`ACTIVE` 集合与 `displayStatus` 优先
在 3.15.3 已被 `tbn`（`running || hasBackgroundWork`）取代。

---

## 6. 19 个 `zcode_type` 的收发全景（网页端）

schema 联合 `uS` 定义在 `validation-CqXZOLXO.js` off **284,627**。逐一核对后，
**旧基线记的"壳端零读者"帧在网页端全部有读者**：

| `zcode_type` | 页面发 | 页面收 | 页面落点（off） |
| --- | --- | --- | --- |
| `bootstrap-request` / `bootstrap-response` | ✅ | ✅ | 6,578,999（发）/ `s6n.check` 6,549,335（收） |
| `workspace-list-request` / `-response` / `-updated` | ✅ | ✅ | 6,579,686 / 6,579,686 / 6,578,071 |
| `workspace-bridge-open` / `-ready` / `-error` | ✅ | ✅ | 6,580,406 / 6,580,591 / 6,535,753 |
| `workspace-reconnect-request` / `-response` | ✅ | ✅ | 6,580,129 / 6,580,205（`ee`，按 `workspaceKey` 配对） |
| `mobile-view-state-update` | ✅ | —— | 6,579,893（`B`：`viewState:{activeWorkspaceKey,activeTaskId?,updatedAt}` + `deviceInfo`） |
| `platform-request` / `platform-response` | ✅ | ✅ | 6,579,438 / 6,579,504（按 `method` 配对） |
| `rpc-frame` / `rpc-frame-ack` | ✅ | ✅ | 6,473,883（走 bridge 的帧层，`onRawTransportPayload`） |
| `bridge-degraded` | —— | ✅ | 6,578,134：`e.bridgeSessionId===g?.getBridgeSessionId()&&(g?.markDegraded(),A(e.reason))` |
| `app-error` | —— | ✅ | 6,535,753（`acceptPayload`）/ 6,578,380（`E(q9(e.reason,e.error))`） |
| `mobile-diagnostic` | ✅ | —— | 6,576,138 |
| `telemetry-report` | ✅ | —— | 6,581,909（`Ue.safeParse(e)` 后转发） |

> 旧基线 §5.2 说 `bridge-degraded`"生产路径不消费"——**在新构建上被推翻**：
> 网页端**原生消费** `bridge-degraded`，并用 `bridgeSessionId` 做归属校验后 `markDegraded()`。
> `markDegraded` 现在带 `reasonCode` 且 `terminal:true`（off **6,476,555** 区）。

---

## 7. 量化结论（更新）

| 口径 | 旧基线（2026-09-20） | 3.15.3 |
| --- | --- | --- |
| bootstrap 载荷"被消费的顶层键" | 壳端 0；壳只读 `tasks` 内 3 叶子 | 网页端 **4/6 顶层键**（relay 路径） |
| `tasks[]` 元素字段消费 | 壳 3 个叶子 | 网页端 **13/16 键**（但**不在 bootstrap 路径上**，在 workspace-list 路径上） |
| `workspaces[]` 元素字段消费 | 壳 0/8 | 网页端 **8/8** |
| 无消费者的 `zcode_type` | 壳端 6 个 | 网页端 **0 个**（19 个全部有收发落点） |
| 载荷形状版本 | `dg` 5 键 | `sS` **6 键**（+`desktopAppVersion`） |
| `displayStatus` 枚举 | 壳侧假定含 `waiting`/`prewarming` | `['idle','running','completed','error']`（**无** waiting/prewarming） |
| `deviceInfo` 必填性 | 旧基线称"缺必填键整帧被丢弃" | schema `deviceInfo:oS.optional()` ⇒ **可选，缺了不丢帧**（off 285,814 区） |

---

## 8. 对原生宿主的含义

1. **不要照抄旧壳的"只读 3 个叶子"**：网页端的等价判据是
   `displayStatus==='running' || hasBackgroundWork===true`，且 `waiting`/`prewarming` **不再是合法值**。
2. **要做"原生承载"，`bootstrap` 只是版本握手**：真正的任务清单必须走
   `workspace-list-request/response` 并订阅 `workspace-list-updated`；
   只发 bootstrap 拿不到 `tasks`（relay 路径页面自己都不读它）。
3. **`desktopAppVersion` 必须处理**：它是 3.15.3 新增的**强闸门**——不一致时页面会自弹窗并要求改
   `app_version` 重载。原生宿主若不回带该字段，页面走的是"字段缺失 ⇒ 直接返回"的分支（不弹窗），
   行为与桌面端不一致。
4. **`workspace-bridge-open` 的 `recoveryId` / `bridgeGeneration`** 是新增的恢复语义字段
   （schema `iS`，off **283,340**），恢复链要靠它做幂等；旧壳没有这套。
5. **`requestId` 必须回配**：网页端对 bootstrap / workspace-list / platform / reconnect **全部严格配对**。

---

## 9. 复现

```bash
cd E:/Zcode_android/Reference_web/3.15.3/assets
# ① bootstrap 请求与配对
node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');process.stdout.write(b.subarray(6578980,6579200).toString())"
# ② 版本闸门（desktopAppVersion）
node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');process.stdout.write(b.subarray(6549200,6549500).toString())"
# ③ 恢复选桥（读 workspaces）
node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');process.stdout.write(b.subarray(6584050,6584300).toString())"
# ④ schema：bootstrap-response.result 六键
node -e "const b=require('fs').readFileSync('validation-CqXZOLXO.js');process.stdout.write(b.subarray(284150,284400).toString())"
# ⑤ 工作区选择（mobileViewState 优先于 initialViewState）
node -e "const b=require('fs').readFileSync('src-AL-_ONNp.js');process.stdout.write(b.subarray(63900,64500).toString())"
# ⑥ 任务分组与丢弃规则
node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');process.stdout.write(b.subarray(4425040,4425350).toString())"
# ⑦ 在跑判据 tbn
node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');process.stdout.write(b.subarray(4422860,4422900).toString())"
```

---

## 10. 出处

- 网页端：`Reference_web/3.15.3/assets/index-BGa3KiRe.js`、`validation-CqXZOLXO.js`、`src-AL-_ONNp.js`（只读快照）。
- 旧壳对照列：旧基线文档原文（`android-shell/…` 行号）——**历史对照，不再作为结论主体**。
- 相关篇目：[14 完整字段表](14-remote-v4-bootstrap载荷-完整字段表.md) ·
  [17 合并总表与未解析字段](17-remote-v4-bootstrap载荷-合并总表与未解析字段清单.md) ·
  [16 relay 协议目录](16-remote-v4-relay协议目录.md) ·
  [21 回前台对话恢复](21-回前台对话恢复-页面机制与真机事实.md) ·
  [24 重载恢复逐条取证](24-remote-v4-页面重载恢复机制逐条取证.md)。
