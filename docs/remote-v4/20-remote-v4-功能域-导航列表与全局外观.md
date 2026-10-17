# remote-v4 功能域：导航列表与全局外观

> **审计基线**：3.15.3（2026-10-15 快照）
> 主包 `assets/index-BGa3KiRe.js`（6,592,643 B，SHA-256 `ce2e2fc4…b88fd`）
> schema `assets/validation-CqXZOLXO.js`（307,369 B，SHA-256 `96e64e8f…dd6c9b`）
> 旧基线（2026-09-20 / `index-B-ilXaCQ.js`）的偏移与符号名**一律作废**。
>
> 快照根：`E:\Zcode_android\Reference_web\3.15.3\`（只读）｜ i18n 目录块 `assets/IntlProvider-C-26GNLk.js`（855,577 B）
> **偏移口径**：**UTF-8 字节偏移**（旧基线用的是"解码后字符偏移"，两者不可混用；本文坐标已全部按字节重挖）。
> **审计日期**：2026-10-16 ｜ **方法**：构建期常量反解 + 组件门控逐行取证 + i18n 键枚举

---

## 0. 一句话

导航是**一层外壳**：页面按 URL 分派到"手机远控外壳"或桌面形态；手机外壳内部只有**两个页**（`home` / `chat`），
切换靠内部 state + `history.pushState({zcodeMobilePage:'chat'})`。
**本版三处旧结论被推翻**：① 静态资源前缀 `BASE_URL` 从 `/remote/v4/latest/` 变成 **`/remote/v4/3.15.3/`**（版本钉死）；
② 状态栏主题属性 `data-zcode-browser-theme-surface` **已进入主包**（不再只存在于 `catalogTree` chunk）；
③ `webRemoteControl.*` i18n 键从 88 个增至 **104 个**（新增 `mobileHome.*` / `triggerStatus.*` / `failure.*` / `botChannel.*` 四族）。

---

## 1. URL 形态分派（页面认哪些路径）

全部在 `index-BGa3KiRe.js`：

| 面 | 值 | 字节偏移 |
| --- | --- | --- |
| 远控入口路径常量 | `VITE_WEB_REMOTE_CONTROL_ROUTE_PATH: '/remote/v4'` | **6496,014**（另 4 份内联构建常量：1086,060 / 1796,193 / 1804,789 / 4585,400） |
| **资源前缀** | `BASE_URL: '/remote/v4/3.15.3/'` | **6495,944**（另 4 处：1085,990 / 1796,123 / 1804,719 / 4585,330） |
| 远控根路径集合 | `A4n = new Set(['/web-remote','/web-remote/'])` | **6493,226** |
| 回调路径集合 | `j4n = new Set(['/web-remote/callback','/cn/share/callback','/share/callback'])` | **6493,270** |
| share 路径正则 | `M4n = /^\/(?:cn\/share\|share)\/[A-Za-z0-9._~-]{1,512}$/u` | **6493,347** |
| 接受的查询参数 | `N4n = new Set(['remoteControlToken','relayOrigin'])` | **6493,402** |
| 本地开发地址正则 | `P4n = /^https?:\/\/(localhost\|127\.0\.0\.1\|10\.\d+\.\d+\.\d+\|172\.(1[6-9]\|2\d\|3[01])\.\d+\.\d+\|192\.168\.\d+\.\d+)(:\d+)?(\/\|$)/` | **6493,452** |
| OAuth / 站点常量 | `VITE_ZAI_OAUTH_ORIGIN: 'https://chat.z.ai'`、`VITE_ZCODE_BASE_URL` / `VITE_ZCODE_ENDPOINT_ORIGIN: 'https://zcode.z.ai'` | 6495,944 区（同一 `q4n({...})` 构建常量对象） |

**两条容易搞混的事（本版复核后仍成立）**：
- `BASE_URL` 是**页面拼静态资源**用的前缀，**不是**入口判据。**本版它变成了版本化路径 `/remote/v4/3.15.3/`** ——
  旧基线写的 `/remote/v4/latest/` **作废**。⇒ 壳若硬编码 `/remote/v4/latest` 做资源拦截会全部 miss。
- 入口判据来自 `VITE_WEB_REMOTE_CONTROL_ROUTE_PATH`（`/remote/v4`）——所以 `/remote/v4/latest` 会被服务端 200 返回，
  但**不认作手机远控入口**。

---

## 2. 手机外壳与导航

### 2.1 外壳的挂载门控：**三重 AND**

渲染点 `index-BGa3KiRe.js` @ **5706,950**（`&&M?` 在 5707,080）：

```jsx
return rn && wn && M
  ? <uZ title={`ZCode / ${cg(R)}`} showHeader={true} isDesktop={V} isMacDesktop={te}
        isWindowsDesktop={H} headerTestId={Gi}>
      <Nbn activeTaskId={De} activeWorkspaceIdentity={B} activeWorkspacePath={R}
           initialNavigationIntent={N} initialWorkspaceList={P}
           webRemoteControlTerminalTransportState={F} isSidePaneOpen={ve} … switcher={M} … />
    </uZ>
  : null
```

| 项 | 变量 | 来源（字节偏移） |
| --- | --- | --- |
| ① 远控外壳开关 | `rn` | `rn = !!M` @ **5690,254**；`M` = prop `webRemoteControlWorkspaceSwitcher`（解构 @ **5687,114**）；另在 @ 5697,033 以 `isWebRemoteControlShell:rn` 传入 `NHn` |
| ② 移动视口 | `wn` | `[wn,Tn] = useState(lUn)` @ **5690,683**；`lUn()` = `window.matchMedia(WHn).matches`，`WHn = '(max-width: 767px)'` @ **5684,881** |
| ③ 工作区切换器 | `M` | 同 ①（`rn` 是 `M` 的派生值，故实际是**两项有效**） |

⇒ **旧基线的结论仍然成立且更精确了**：`webRemoteControlWorkspaceSwitcher` 为假时整个手机外壳不渲染；
但**还叠加了一条新的门控 —— 视口宽度必须 ≤ 767px**。这正是"在窄窗口/桌面版外壳里看不到手机远控页"的源码落点。

外壳组件本体 `function Nbn({activeTaskId, activeWorkspaceIdentity, activeWorkspacePath, chatContent,
chatOverlay, initialNavigationIntent, initialWorkspaceList, isSidePaneOpen, onCloseSidePane, onSelectTask,
onStartDraftInWorkspace, renderChatHeader, sidePaneContent, switcher, webRemoteControlTerminalTransportState})`
定义在 @ **4446,408**（旧基线符号 `Dwt` **作废**）。

### 2.2 两个页：home 与 chat

| 页 | 判据（字节偏移） | DOM 标记 |
| --- | --- | --- |
| `home` | `function Mbn(e){ return e==='chat' \|\| (typeof window<'u' && Z0(window.history.state)) ? 'chat' : 'home' }` @ **4446,324** 返回 `'home'` | **无任何 `data-mobile-page`**（全主包仅 1 处命中，见下） |
| `chat` | 同上返回 `'chat'`；或用户操作触发 `D()`（`_(`chat`)` + `jbn()`，@ 4446,7xx） | 根节点 **`data-mobile-page="chat"`** @ **4448,306** |

```js
// @ 4446,127
function Z0(e){ return typeof e==='object' && !!e && e.zcodeMobilePage === 'chat' }
// @ 4446,210
function jbn(){ typeof window>'u' || Z0(window.history.state) || window.history.pushState({zcodeMobilePage:'chat'},'') }
```

- **`history.state` 的标记键是 `zcodeMobilePage`，值固定 `'chat'`**（旧基线只写了"`history.state` 未命中"，未给出键名）。
- `popstate` 监听（@ 4446,7xx 区）：`let e = e => { let t = Z0(e.state) ? 'chat' : 'home'; _(t), t==='home' && x(e=>e+1) }`。
- 页头：`data-mobile-page="chat"` 节点内的返回按钮 `aria-label` = `webRemoteControl.mobileShell.backHome` @ **4448,549**；
  标题 = `webRemoteControl.mobileShell.chatTitle` @ **4448,755**。
- **`data-mobile-page` 只有 `"chat"` 一个取值，`home` 分支不渲染该属性** ⇒ 旧基线"home 页为 `null`"**已复核成立**。
- 详细判据与 `history.state` 生命周期见 [24 篇 §1](24-remote-v4-页面重载恢复机制逐条取证.md)。

### 2.3 手机端偏好（独立于路由）

```js
// 读 @ 4426,668
function vbn(e=gbn()){ try { let t=e?.getItem(hbn); if(!t) return K0; let n=JSON.parse(t); return _bn(n)?n:K0 } catch { return K0 } }
// 写 @ 4426,788
function ybn(e,t=gbn()){ try { t?.setItem(hbn, JSON.stringify(e)) } catch {} }
// @ 4426,289
hbn = 'zcode-web-remote-control-mobile-task-home-preferences'
// @ 4426,349
K0  = { organizeBy: 'workspace', sortBy: 'updated' }
```

- 外壳里：`[v,y] = useState(() => vbn())`（@ 4446,806），更新回调 `O = e => { ybn(e); Z.debug('[WebRemoteControlMobileShell] 更新手机端任务首页偏好', e); y(e) }`
  （日志串 @ **4446,987**）。
- **诊断线索**：日志出现 `[WebRemoteControlMobileShell] 更新手机端任务首页偏好` 说明用户改了首页偏好；
  出现 `[WebRemoteControlMobileShell] 同步 mobileViewState 失败`（@ **4447,709**）说明 `updateMobileViewState` 桥调用失败。
- 旧基线写的"偏好另存一份（`zcode-sidebar-task-preferences`、`zcode-grouped-task-collapsed-groups` 同族）"
  在存储**键名**上不成立：手机首页偏好用的是**独立键** `zcode-web-remote-control-mobile-task-home-preferences`
  （桌面侧栏那两族键仍存在，见 §3，但属于不同用途）。

---

## 3. 列表、分组与搜索

| 面 | 证据（字节偏移） |
| --- | --- |
| 任务状态文案 | **动态键** `webRemoteControl.taskStatus.${...}`（@ **4438,233** / 4440,656 / 4445,821 三处）；静态回退键 `taskStatus.idle\|running\|completed\|error` 在 i18n 目录（§5.2） |
| 分组折叠态持久化 | localStorage 键 `zcode-grouped-task-collapsed-groups` @ 4525,438 / 4525,753 |
| 侧栏偏好 | `zcode-sidebar-task-preferences` @ 4516,047 / 4516,296；`zcode-sidebar-purpose-section-preferences` @ 4516,659 |
| 搜索（命令面板） | 键前缀 `zcode-command-center-search-history`（+ scope）@ 1495,922 |
| 任务导航历史 | store 里 `taskNavHistory` @ 1352,944 区；`taskNavGoBack` @ 1353,139（另一处 @ 5730,753）；另有 forward / remove |
| 手机首页组织/排序 | `mobileHome.organizeBy` ∈ `workspace\|timeline`；`mobileHome.sortBy` ∈ `created\|updated`（默认 `updated`，见 §2.3 的 `K0`） |

> **本轮仍未展开（诚实标注）**：任务列表的**排序键实现**（是否只按 `updatedAt`/`createdAt`）、
> **分组规则**（`organizeBy:'workspace'` 的具体分组键）、**搜索匹配域**（匹配标题/路径/正文？）
> **未逐条取证** —— 若壳或鸿蒙要复刻"任务列表的排序与过滤"，需要补这一段。

---

## 4. 连接与状态呈现（六态）

映射函数在 `index-BGa3KiRe.js`（标题函数起点约 @ **4658,291**，详情函数 @ **4658,698**）：

```js
// 标题
switch(e.status){ case 'starting': … 'webRemoteControl.status.starting'   // @ 4658,291
                  case 'running':  … 'webRemoteControl.status.running'
                  case 'connecting':… 'webRemoteControl.status.connecting'
                  case 'active':   … 'webRemoteControl.status.active'
                  case 'error':    … 'webRemoteControl.status.error'
                  default:         … 'webRemoteControl.status.idle' }     // @ 4658,601
// 详情（同样 6 值，键为 statusDetail.*）@ 4658,698 → 4659,038
```

| 键族 | 取值 | 用途 |
| --- | --- | --- |
| `webRemoteControl.status.*` | `idle` / `connecting` / `starting` / `running` / `active` / `error` | **六态标题** |
| `webRemoteControl.statusDetail.*` | 同上六值 | 六态的**详情文案** |
| `webRemoteControl.statusTag.*` | `phone` / `ready` | 状态标签（@ 4659,501 = `statusTag.ready`） |
| `webRemoteControl.triggerStatus.*` | `connected` / `connecting` / `error` / `idle` / `starting` / `waiting` | **本版新增的第二组六态**（@ 4673,554 = `triggerStatus.connected`），用于桌面侧"远控开关"入口 |

- 状态源：relay 客户端的连接状态机。原始状态名在本版可实证的有 `authenticating`（`this.setState('authenticating')` @ **6523,297**）、
  `waiting`、`paired`（@ 6524,593 区）。旧基线写的状态序列 `idle → connecting → authenticating → waiting | paired`
  **未复核（旧基线）**：本版未定位到该状态联合的显式声明；展示层六态由上面的 `switch` 完备覆盖（`default → idle`）。
- 传输态通过 **`webRemoteControlTerminalTransportState`** 这个 prop 进入外壳（@ 4446,689 解构 / 5707,337 传入）。
- 设备信息展示字段：`mobileDeviceInfo.browserPlatform` → `mobileDeviceInfo.name` → `mobileConnected`
  → 六态回退（函数 @ 4659,6xx 区，`statusTag.phone` @ 4659,501）。
- 状态点配色 `function fTn(e)`：`error→bg-destructive`、`active→bg-success`、`idle→bg-border`、其余 `bg-warning`（@ 4659,5xx 区）。

---

## 5. 全局外观

### 5.1 主题

| 面 | 证据（字节偏移） |
| --- | --- |
| 主题类 | `theme-zai-light` / `theme-zai-dark`（+ `dark`）三处：`n_(e)` @ **708,431**、宿主注入脚本 @ **1802,054**、网页入口 applyTheme @ **6553,261** |
| 应用写法 | `document.documentElement.classList.toggle('dark', t==='dark'); … .toggle('theme-zai-light', n==='zai-light'); .toggle('theme-zai-dark', n==='zai-dark')` |
| localStorage 键 | `zcode-theme`（值 `zai-light` / `zai-dark`）@ 1802,215 等 |
| 宿主注入写法 | `document.documentElement.classList.toggle("dark", zcodeTheme === "zai-dark"); … localStorage.setItem("zcode-theme", zcodeTheme); localStorage.setItem("zcode:coding-plan:embedded", "app"); window.__zcodeLang__ = …` @ **1802,054** 区 |
| 模式解析 | `'system'` → `matchMedia('(prefers-color-scheme: dark)')` 决定 `zai-dark`/`zai-light`；`'dark'` → `zai-dark`；`'light'` → `zai-light`（@ 6553,261） |
| **状态栏主题属性** | `aFe = 'data-zcode-browser-theme-surface'` @ **707,532** —— **已在主包内**（`applyTheme` 内 `t.setAttribute(aFe, e)` + `t.style.colorScheme = e`，@ 708,4xx） |

> **推翻旧结论**：旧基线说 `data-zcode-browser-theme-surface` 是"**唯一一个关键锚点不在主包**"的例子、
> 只在 `catalogTree-DmoX4pSX.js`（654 KB chunk）里。3.15.3 里它**定义并使用于主包**
> （`index-BGa3KiRe.js` @ 707,532 / 708,4xx）。⇒ 壳不再需要"先确保该 chunk 已加载"才能观测它。
> 旧基线引用的 `catalogTree-DmoX4pSX.js` 文件名**已作废**。

### 5.2 i18n

| 面 | 证据 |
| --- | --- |
| 目录块 | `assets/IntlProvider-C-26GNLk.js`（855,577 B） |
| 键前缀 | `webRemoteControl.*`，**104 个唯一键**（zh-CN 块 @ 107,528 与 en 块 @ 531,539 键集一致） |
| 语言偏好键 | `zcode-locale-preference` @ 3288,918 / 4710,297（另 IntlProvider @ 852,565） |
| 语言切换事件 | CustomEvent `zcode-coding-plan-lang-change`（`{locale}`）@ **1804,486**，与 `window.__zcodeLang__`（@ 1802,480 / 1804,408）同段注入脚本 |
| 文案读取 | `intl.formatMessage({id})`（外壳内 `const {intl:h} = X()` @ 4446,7xx 区） |

**键数变化（推翻旧基线）**：旧基线记 **88 键**，本版目录实测 **104 键**。新增的族：

| 族 | 键数 | 说明 |
| --- | --- | --- |
| `webRemoteControl.mobileHome.*` | 22 | 手机首页：`title`(@4432,549) / `summary` / `sectionTitle` / `notice` / `organize` / `organizeByWorkspace`(@4434,475) / `organizeByTimeline` / `sortBy` / `sortByCreated` / `sortByUpdated` / `refresh` / `collapseAll` / `connected` / `disconnected` / `reconnecting` / `reconnect` / `taskCount` / `updatedAt` / `workspaceEmpty` / `workspaceKind.local\|remote\|conversation` |
| `webRemoteControl.triggerStatus.*` | 6 | `idle` / `starting` / `connecting` / `connected` / `waiting` / `error` |
| `webRemoteControl.failure.*` | 12 | `sessionNotFound` / `sessionExpired` / `sessionConflict` / `workspaceClosed` / `kicked` / `desktopDisconnected` / `invalidMobileConnection` / `desktopBootstrapTimeout` / `connectionRecoveryTimeout` / `relayUnavailable` / `unsupportedAction` / `unexpectedError` |
| `webRemoteControl.botChannel.*` | 12 | `title` / `description` / `configure` / `manageBots` + `weixin\|feishu\|lark\|telegram` × `title\|description` |
| 其余新增/保留 | 其余 | `mobileShell.chatTitle` / `mobileShell.reconnecting` / `themeMenu.trigger` / `stop` / `stopSuccess` / `stopFailed` / `startFailed` / `generating` / `copyLink*` / `qrAlt` / `refreshQr*` / `mobileQr.*` / `linkLabel` / `linkPending` / `singlePageNote` / `switchWorkspace` / `noSwitchableWorkspaces` / `noTasks` / `openTask` / `collapseNavigation` / `expandNavigation` / `statusLabel` |

- 旧基线的 88 键结论**已作废**；`mobileShell.backHome` 仍存在（@ 4448,549）。
- 注意：主包代码里另有 2 个**动态拼接**键（`webRemoteControl.taskStatus.${...}`、`webRemoteControl.botChannel.${provider}.title/.description`），
  它们不计入目录的 104 个静态键。

---

## 6. 对壳（与鸿蒙）的意义

| 面 | 壳侧用法 | 注意 |
| --- | --- | --- |
| 入口校验 | 壳只校验 `/remote` 前缀（比页面宽松） | 页面实际只认 `/remote/v4`；宽松校验会放行 `/remote/v4/latest` 这类"能打开但不是手机外壳"的地址 |
| **资源前缀** | 若壳做资源拦截/离线缓存 | **本版是 `/remote/v4/3.15.3/`（版本钉死），不是 `/latest/`**；升版即变 |
| 外壳门控 | 三条件 AND：`webRemoteControlWorkspaceSwitcher` ∧ `isWebRemoteControlShell` ∧ **`matchMedia('(max-width: 767px)')`** | 视口 >767px 时手机外壳**不渲染**（新增门控） |
| `data-mobile-page` | "在不在对话"的主锚点 | 只在 chat 分支渲染 ⇒ home 页无该属性 |
| `history.state.zcodeMobilePage` | 判断"是否处于 chat 页"的另一条路 | 值为 `'chat'`；页面靠它做 `pushState`/`popstate` 往返 |
| 六态文案 | 壳**不用**（壳有自己的通知文案） | 但可用 `webRemoteControl.statusTag.ready` 做"就绪"判据；新增的 `triggerStatus.*` 可作桌面侧入口状态判据 |
| 主题 | 壳自己维护三态固定色表（不读页面类名） | 页面 `theme-zai-light/dark` 可作为"深/浅色"的**旁证**；`zcode-theme` 键可读 |
| 状态栏属性 | 壳依赖 `data-zcode-browser-theme-surface` | **本版在主包内**（@ 707,532），无需等待 chunk；但仍是页面实现细节 |
| 手机首页偏好 | 若要跨端同步"按工作区/时间线组织、按更新/创建排序" | 键 `zcode-web-remote-control-mobile-task-home-preferences`，默认 `{organizeBy:'workspace',sortBy:'updated'}` |

---

## 7. 复现

```bash
cd /e/Zcode_android/Reference_web/3.15.3/assets
# ① 路径常量与白名单（含新的版本化 BASE_URL）
node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');process.stdout.write(b.subarray(6493200,6493520).toString())"
node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');process.stdout.write(b.subarray(6495930,6496100).toString())"
# ② 外壳三重门控 + history.state 判据
node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');process.stdout.write(b.subarray(4446100,4446420).toString())"
node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');process.stdout.write(b.subarray(5706900,5707500).toString())"
node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');process.stdout.write(b.subarray(5690200,5690720).toString())"
# ③ 六态映射
node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');process.stdout.write(b.subarray(4658250,4659100).toString())"
# ④ 主题类 + 状态栏属性（现在都在主包）
node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');process.stdout.write(b.subarray(707500,708560).toString())"
# ⑤ i18n 键枚举（应为 104）
node -e "const s=require('fs').readFileSync('IntlProvider-C-26GNLk.js','utf8');const re=/[\`'\"]webRemoteControl\.[A-Za-z0-9_.]+[\`'\"]\s*:/g;const k=new Set();let m;while((m=re.exec(s)))k.add(m[0]);console.log(k.size)"
```

---

## 8. 出处

- 快照：`E:\Zcode_android\Reference_web\3.15.3\`（入口 `index.html` 引用 `assets/index-BGa3KiRe.js`，逐字节冻结）。
- 旧基线快照（历史证据，不改不删）：`Docs/27-remote-v4-网页快照-20260920/`。
- 相关篇目：[15 架构与模块地图](15-remote-v4-网页架构与模块地图.md) ·
  [16 relay 协议目录](16-remote-v4-relay协议目录.md) ·
  [18 对话渲染与流式更新](18-remote-v4-功能域-对话渲染与流式更新.md) ·
  [24 重载恢复逐条取证](24-remote-v4-页面重载恢复机制逐条取证.md) ·
  [10 扩展面与可观测面](10-remote-v4-网页扩展面与可观测面穷尽盘点.md) ·
  [22 壳端注入点审计](22-remote-v4-壳端注入点审计-主题-滚动条-会话加载.md)。
