# remote-v4 网页扩展面与可观测面 穷尽盘点

> **审计基线**：3.15.3（2026-10-15 快照）
> 主包 `assets/index-BGa3KiRe.js`（6,592,643 B，SHA-256 `ce2e2fc4…b88fd`）
> schema `assets/validation-CqXZOLXO.js`（307,369 B，SHA-256 `96e64e8f…dd6c9b`）
> 旧基线（2026-09-20 / `index-B-ilXaCQ.js`）的偏移与符号名**一律作废**。
>
> 快照根：`E:\Zcode_android\Reference_web\3.15.3\`（2,577 assets，入口 HTML 18,351 B，SHA-256 `4ca1a8b0…32f2`）
> **偏移口径**：**UTF-8 字节偏移**，只在上述 SHA 的主包/分块上可复现。旧文档用的是"字符偏移"，两者不可混用。
> 复现命令见 §8；旧基线对照取自 `Docs/27-remote-v4-网页快照-20260920/latest/assets/`（`index-B-ilXaCQ.js`，4,821,509 B）。

---

## 0. 一句话

网页对宿主开放的扩展面**仍然是四类且都很窄**：① **一个日志钩子** `window.zcode.log`（页面只读、宿主生产）；
② **CustomEvent 总线**（比旧基线记的宽：**13 个 `zcode*` 事件名**，旧基线只记了 2 个）；③ **两个"宿主可写"的 localStorage 开关**；
④ **`localStorage` / `sessionStorage` 里的共享键**。

**本轮最硬的新结论**：旧基线列为「待验证」的 E2E 门控函数**已定位并证伪**——
门控是 `aw()`，其判据 `VITE_ZCODE_E2E_STORE_BRIDGE === '1'` 是**构建期内联常量，本构建未注入** ⇒ `aw()` **恒 false** ⇒
**所有 `aw()` 门控的 E2E 钩子在真机上恒不存在**（§4）。这条把一个"未定位的疑点"变成了"确定恒假"。

**本轮被推翻的旧结论**：① 「CustomEvent 只有 2 个」（§2）；② 「门控函数未定位」（§4）；
③ 旧基线把 `zcode-v4-last-session` 记成无版本后缀（实际键是 `zcode-v4-last-session:v1:`，§3）。

**新增/消失**：新增 2 个 CustomEvent 名、5 个 E2E 钩子、16 个 `zcode*` 存储键、1 个版本自举重定向机制（§6）；
**消失 0 项**（壳依赖的锚点一个没丢，见 [28 篇 §三](28-remote-v4-网页更新影响-壳健壮性与重适配成本.md)）。

---

## 1. 日志出口：`window.zcode.log`（宿主提供、页面调用）

**位置变了**：旧基线是独立 chunk `assets/logger-BcwVkv_N.js`（1,196 B）；
3.15.3 里它被**并入** `assets/officeFilePreview-Bs0TmYyT.js`（3,314 B）的**头部**，与 office 预览辅助函数同块。

逐字（`officeFilePreview-Bs0TmYyT.js` 第 1 行，压缩后原文）：

```js
function t(){return{BASE_URL:`/remote/v4/3.15.3/`,DEV:!1,MODE:`production`,PROD:!0,SSR:!1,
  VITE_WEB_REMOTE_CONTROL_ROUTE_PATH:`/remote/v4`,…}.PROD===!0||typeof process<`u`&&!0}
function n(){return globalThis.__ZCODE_RENDERER_DISABLE_LOGGING__===!0}
var r={debug:console.debug,info:console.log,warn:console.warn,error:console.error};
function i(e){return!t()&&!n()}
function a(t,...n){i(t)&&(r[t](e(`ui`),...n),t!==`debug`&&typeof window<`u`&&window.zcode?.log?.(t,n))}
function o(e,...r){if(!n()){if(t()){typeof window<`u`&&window.zcode?.log?.(e,r);return}a(e,...r)}}
var s={debug:(...e)=>a(`debug`,...e),info:(...e)=>a(`info`,...e),warn:…,error:…,
  lifecycle:{info:(...e)=>o(`info`,...e),warn:…,error:…},
  trace:(e,t,...n)=>{a(t,`[trace:${e}]`,...n)}};
function c(i){if(!n()){if(typeof window<`u`){let e=window.zcode?.log;if(e){e(`info`,[i]);return}}t()||r.info(e(`ui`),i)}}
```

**坐标**：`__ZCODE_RENDERER_DISABLE_LOGGING__` @`officeFilePreview-Bs0TmYyT.js` **byte 431**；
`window.zcode?.log` 全快照 **3 处**，全在本文件（页面**从不写** `window.zcode`，全 assets 检索 `window.zcode=` **0 命中**）。

**四条硬语义逐条复核：全部成立（与旧基线一致）**

| # | 语义 | 3.15.3 复核 | 后果 |
| --- | --- | --- | --- |
| 1 | `t()` 恒 `true`（`PROD:!0`）⇒ `i(level)` 恒 `false` | **成立**（`PROD:!0` 显式内联） | **普通 `debug/info/warn/error` 既不进 console、也不发给宿主** |
| 2 | `lifecycle.*` 走 `o()`：生产下**只**调 `window.zcode?.log?.()` | **成立** | **lifecycle 级日志是宿主专享通道** |
| 3 | `c(msg)` 有钩子就发给宿主并 return | **成立** | 同上，宿主优先 |
| 4 | 全部调用都是 `?.` 可选链 | **成立** | 宿主**不提供钩子也不报错**，只丢日志 |

**与旧基线的唯一差异（不改变语义）**：① `BASE_URL` 由 `/remote/v4/latest/` 改为 `/remote/v4/3.15.3/`（版本化前缀）；
② 每个 `window` 访问前多了 `typeof window<`u`` 守卫（SSR 安全）；③ `PROD:!0`/`DEV:!1` 由表达式变成字面量。

> **壳的用法不变**：注入层提供 `window.zcode = {log}`，把页面 lifecycle 日志转成 `pagelog` 桥消息。
> **总闸** `globalThis.__ZCODE_RENDERER_DISABLE_LOGGING__ = true` 会连 `o()`/`c()` 一起掐掉 ⇒ **壳不应开这个总闸**。

---

## 2. CustomEvent 总线（13 个 `zcode*` 名，旧基线只记了 2 个 —— 推翻）

全快照 `new CustomEvent(` 共 **20 处**（旧构建同为 20 处）。按**首参名字**分类：

### 2.1 宿主注入脚本产生的事件（3 个，都在"注入 webview 执行的原始 JS"字符串里）

| 事件 | 载荷 | 坐标 | 旧基线 |
| --- | --- | --- | --- |
| `zcode-coding-plan-auth-ready` | `{provider, locale, reportContext}` | `index-BGa3KiRe.js` **@1,802,730** | 有（旧 @800,954 区） |
| `zcode-coding-plan-lang-change` | `{locale}` | `index-BGa3KiRe.js` **@1,804,469** | 有 |
| **`zcode-rewards-context`** | `{…凭证上下文}` | **`src-AL-_ONNp.js` @72,577** | **新增** |

原文（@1,802,730 一带，仍是 `(() => { … })()` 注入串，注释原文保留）：

```js
  window.__zcodeLang__ = ${JSON.stringify(s)};
  const zcodeReportContext = ${JSON.stringify(c)};
  window.__zcodeReportContext__ = zcodeReportContext;
  localStorage.setItem(${JSON.stringify(kbt)}, JSON.stringify(zcodeReportContext));
  window.dispatchEvent(new CustomEvent("zcode-coding-plan-auth-ready", {
    detail: { ...${JSON.stringify({provider:e,locale:s})}, reportContext: zcodeReportContext },
  }));
```

其中 `kbt = 'zcode:coding-plan:report-context'`（@1,799,980）——宿主注入脚本写下的存储键。

### 2.2 页面自身逻辑派发的事件（10 个）

| 事件 | 坐标（`index-BGa3KiRe.js`） | 旧基线 |
| --- | --- | --- |
| `zcode:add-whiteboard-to-chat` | @1,387,800 | 有 |
| `zcode:conversation-selection-add` | @1,392,974 | 有 |
| `zcode:open-plugin-store` | @1,463,035 | 有 |
| `zcode:settings-section-intent` | @1,565,920 | 有 |
| `zcode:workspace-file-drag-state` | @1,716,031 | 有 |
| `zcode:add-workspace-file-to-chat` | @1,716,161 | 有 |
| **`zcode:code-comment-add-to-chat`** | **@2,128,317** | **新增** |
| `zcode:code-comment-remove-from-chat` | @2,128,436 | 有 |
| `zcode:web-element-context-add-to-chat` | @2,134,307 | 有 |
| `zcode:pptx-element-reference-add-to-chat` | `imeComposition-C0HlYpBV.js` **@248,653** | 有 |

> 另有 **5 处 radix-ui 内部事件**（非 `zcode*`，与宿主无关）：`rovingFocusGroup.onEntryFocus`(@153,879)、
> `menu.itemSelect`(@161,737)、`focusScope.autoFocusOnMount`(@206,993)、`focusScope.autoFocusOnUnmount`(@207,203)、
> 以及 `imeComposition` 内 2 处按参数派发。

### 2.3 新旧对照（同一正则，两代快照各跑一遍）

| 构建 | `zcode*` CustomEvent 名数 | 名单 |
| --- | --- | --- |
| 20260920 / `index-B-ilXaCQ.js` | **11** | 无 `zcode-rewards-context`、无 `zcode:code-comment-add-to-chat` |
| 3.15.3 / `index-BGa3KiRe.js` | **13** | 上表全集 |

**推翻旧基线 §2**：旧文档写"全快照里 `new CustomEvent('…')` 只出现两个名字"——那是**只扫字面量首参**的方法漏了
**变量构造的事件**（`xO=\`zcode:add-whiteboard-to-chat\`; new CustomEvent(xO,…)`）。**旧构建的真实数量就是 11，不是 2**。
本轮的复现命令（§8 ②）对变量名做了一次回查（`var X=\`…\`` 与 `,X=\`…\`` 两种形态）。

> **对壳的意义**：**壳一处都不监听这些事件**（`android-shell/app/src/main/assets/inject.js` 全量检索 `CustomEvent` **0 命中**；
> 其 `addEventListener` 只挂在 `WebSocket`、`document`(DOMContentLoaded/scroll)、`window`(resize/load)、按钮上）。
> ⇒ 这 13 个事件对壳**零价值**，**不构成扩展面**；它们是页面内部 UI 解耦用的。

---

## 3. 存储面（localStorage / sessionStorage）

### 3.1 宿主可写通道（页面只读、页面内无写入方）

| 键 | 语义 | 3.15.3 判据（逐字，@5,538,801 区） |
| --- | --- | --- |
| `zcode:developer-tools:enabled` | 打开页面的开发者工具面 | `JVn=[\`zcode:developer-tools:enabled\`,\`zcode:token-debug:enabled\`]`；`YVn=new Set([\`0\`,\`false\`,\`off\`,\`no\`])`；`XVn(e){if(e===null)return!1;let t=e.trim().toLowerCase();return!YVn.has(t)}`；`ZVn(e=QVn()){return e?JVn.some(t=>{try{return XVn(e.getItem(t))}catch{return!1}}):!1}` |
| `zcode:token-debug:enabled` | 打开 token 调试 | 同上（**任一为真即为真**；空/`0`/`false`/`off`/`no` 视为假） |

**这是壳（或任何宿主）真正可用的一条通道**：写 `localStorage['zcode:developer-tools:enabled']='1'` 即生效，
且**页面自己从不写它**（全快照只有这一处读点）。

**生效时延（旧基线漏记，两代都在）**：消费方是一个 React hook（3.15.3 `eHn` **@5,650,326**），
它同时挂 `storage` 事件、`focus` 事件，并 **`window.setInterval(e, $Vn)` 且 `$Vn=1e3`** ⇒
**宿主写入后 ≤1 秒被页面读到，无需 reload**。旧构建同名 hook `tVt`（`eVt=1e3`）行为相同 ⇒ **不是本次新增**。

### 3.2 页面自己读写的键（本轮实测计数与差异）

**计数口径**（可复现，§8 ④）：主包内反引号包裹的 `` `zcode-…` `` / `` `zcode:…` `` 字面量**去重后**：

| 构建 | 去重键数 |
| --- | --- |
| 20260920 / `index-B-ilXaCQ.js` | **72** |
| 3.15.3 / `index-BGa3KiRe.js` | **88** |

> ⚠️ 旧基线 §3.2 写"共 **38** 个"——用上述同一命令在两代快照上**都复现不出 38**（旧构建实得 72）。
> 该数字**不可复现，作废**；以本节 72 → 88 为准。

**本次净增 16 个键 / 净删 1 个**（同命令集合运算）：

| 变化 | 键 |
| --- | --- |
| **删除**（1） | `zcode:usage-entitlement:`（被 `zcode:usage-entitlement:subscription-v2:` 取代） |
| **新增**（16） | `zcode-automations-page-tab`、`zcode-cloud-hero-v1`、`zcode-code-preview-settings`、`zcode-gen-ui`、`zcode-hero-sandbox-v1`、`zcode-interface-mode`、`zcode-last-editor-id`、`zcode-locale`、`zcode-notification-enabled`、`zcode-notification-sound-enabled`、`zcode-performance-mode`、`zcode-ui-font-size-px`、`zcode-workflow-run-acknowledged`、`zcode-workspace-expansion`、`zcode-workspace-file-search`、`zcode:global-notice:v1` |

**关键键逐条复核（含旧基线点名过的）**：

| 键 | 谁写 | 3.15.3 坐标（`index-BGa3KiRe.js`） | 备注 |
| --- | --- | --- | --- |
| `zcode-theme` | 页面 + 宿主注入脚本 | 宿主注入脚本 @1,802,193 | 值 `zai-light`/`zai-dark`，同时切 `theme-zai-dark/light` 类 |
| `zcode-locale-preference` | 页面 | 主包 + `IntlProvider-C-26GNLk.js` | **壳也读它**（inject.js） |
| `zcode:coding-plan:embedded` = `'app'` | **宿主注入脚本** | @1,802,266 | 标记"运行在 App 内嵌 webview" |
| **`zcode-v4-last-session:v1:`** | 页面 | **@3,724,155** | **会话恢复槽位**。旧基线记成 `zcode-v4-last-session`（无 `:v1:`）——**键名纠正**；壳侧 `inject.js` 用的正是带后缀的全名 |
| `zcode-v4-composer-drafts:v1:` | 页面 | @1,760,086 | v4 输入框草稿（**唯一**的 v4 草稿键；旧基线写作 `zcode-v4-composer-draft` 是前缀截断，两代快照都只有这个带 `s:v1:` 的全名） |
| `zcode-chat-composer-drafts:v1:` | 页面 | @1,353,439 | 又一套草稿键（旧基线也记了"并存"） |
| `zcode-v4-client-id:v1` | 页面 | @1,086,745 | **新增** |
| `zcode-chat-prompt-history:` | 页面 | @2,105,969 | 提示历史（前缀 + scope） |
| `zcode-task-snapshot-cache:v1` | 页面 | @1,438,524 | 任务快照缓存 |
| `zcode-cua-permission-status` | 页面 | @2,145,376 / @2,146,262 | CUA 权限状态 |
| `zcode:start-plan-reminder:v1:` | 页面 | @3,610,914 | 计划提醒去重（28 篇 §3.5 点过名） |
| `zcode:web-remote-control:collapse-navigation-once` | 页面 | @5,684,906（变量 `M8`） | **sessionStorage** 一次性槽（见 §3.3） |
| `zcode-web-remote-control-mobile-task-home-preferences` | 页面 | @4,426,294 | 远控手机端任务首页偏好 |
| `oauth:zai:access_token` / `oauth:bigmodel:access_token` / `zcodejwttoken` | 页面（OAuth） | @6,490,501 起（变量 `x9`/`C9`/`T9`/`S9`） | **凭据**（见 §3.4） |
| `zcode:auth:jwt-invalid-restart` | 页面 | @6,395,523 | **一次性信号**：写 `'1'` / 读到 `'1'` 就 `removeItem` 并返回 true |

### 3.3 sessionStorage 槽位（旧基线未覆盖，本轮补齐）

页面用 sessionStorage 的**全部**位点（`index-BGa3KiRe.js`，同 `sessionStorage` 词根）：

| 键（变量） | 语义 | 读写语义 | 坐标 |
| --- | --- | --- | --- |
| `HA`/`WA`/`GA`/`KA`/`UA`/`qA`（设置页跳转 intent） | 跨路由传"打开哪个设置分区/插件 tab/provider" | **一次性**：`getItem` 后立刻 `removeItem` | @1,563,386 ~ @1,566,000 |
| `M8` = `zcode:web-remote-control:collapse-navigation-once` | 远控手机端"只折叠一次导航" | **一次性**：`uUn()` 读到 `'1'` → `removeItem` → `true` | @5,686,132 |
| `D9` = `oauth_pending_nonce` / `O9` = `oauth_pending_provider` | OAuth 待处理态 | 会话内保存/读取/清除 | @6,490,663 起 |
| `Fhn` = `zcode-automations-page-tab` | 自动化页 tab | 普通会话内偏好 | @3,974,725 |

> **回答旧基线留下的"槽位只在 reload 路径生效"**：以上 sessionStorage 槽**不是 reload 恢复槽**，
> 而是**一次性读后即删**的跨路由 intent（`removeItem` 紧跟 `getItem`）。
> 唯一**承重的 reload 恢复槽**是 **localStorage** 的 `zcode-v4-last-session:v1:`（§3.2）。
> ⇒ 壳若想"预置下次加载要打开的东西"，**sessionStorage 不是可写通道**（页面读一次就删，且壳写的是别的键）；
> 这条与 28 篇 §4.2 G12「假成功日志」是同一根因。

### 3.4 凭据纪律（本项目硬约束，未削弱）

`oauth:zai:access_token` / `oauth:bigmodel:access_token` / `zcodejwttoken` / URL 上的 `sid`/`hash`/`mid`
**一律不得写进日志、文档或输出**。壳侧已有措施：`Diagnostics` 的 `redact()` 逐行剥掉 URL query；
注入层把 relay 凭证**只驻内存**、不落任何 log。3.15.3 新增了 `oauth_pending_nonce` / `oauth_pending_provider`
（sessionStorage，非凭据本体），**不改变**上述纪律。

---

## 4. `window.__zcode*` 上的调试与 E2E 钩子 —— 门控已定位，多数**恒假**

### 4.1 门控函数 `aw()` 与它的判据（旧基线列为"未定位"，本轮定位并证伪）

```js
// index-BGa3KiRe.js  @1,086,297
function aw(e=U$e()){return typeof window<`u`&&e.VITE_ZCODE_E2E_STORE_BRIDGE===`1`}
```

`U$e()`（@1,085,968）返回的环境对象**逐字**为：

```js
{BASE_URL:`/remote/v4/3.15.3/`,DEV:!1,MODE:`production`,PROD:!0,SSR:!1,
 VITE_WEB_REMOTE_CONTROL_ROUTE_PATH:`/remote/v4`,VITE_ZAI_OAUTH_CLIENT_ID:`client_P8X5CMWmlaRO9gyO-KSqtg`,
 VITE_ZAI_OAUTH_ORIGIN:`https://chat.z.ai`,VITE_ZCODE_BASE_URL:`https://zcode.z.ai`,
 VITE_ZCODE_ENDPOINT_ORIGIN:`https://zcode.z.ai`}
```

**关键事实**：全快照 `VITE_ZCODE_E2E_STORE_BRIDGE` 共 **4 处，全部是属性读取**，**没有任何一处定义**该键
（主包内 3 处 env 对象 + 1 处 `aw()` 读取，逐个核对均无该键；`officeFilePreview-Bs0TmYyT.js` 的 env 对象也没有）。
⇒ `e.VITE_ZCODE_E2E_STORE_BRIDGE` 求值为 `undefined`，`undefined === '1'` 为 `false` ⇒
**`aw()` 在本构建上恒 `false`**。

### 4.2 钩子清单（按门控分类）

| 钩子 | 门控 | 坐标（`index-BGa3KiRe.js`） | 真机上存在？ | 旧基线 |
| --- | --- | --- | --- | --- |
| `__zcodeSessionStoreE2E` | `aw()&&(window.…=iO)` | @1,371,809 | **否（恒假）** | 有（标"受门控"） |
| `__zcodeTabStoreE2E` | `if(!aw()\|\|!t.current)return` | @1,207,163 | **否** | **新增** |
| `__zcodeComposerAttachmentUploadStoreE2E` | `aw()&&(window.…=uV)` | @2,107,585 | **否** | 有 |
| `__zcodeCurrentComposerAttachmentScopeKeyE2E` | 依赖上一条已存在 | @2,107,701 | **否** | 有 |
| `__zcodeConversationTelemetryParityE2E` | `if(!e.isDesktop\|\|!aw())return` | @6,442,453 | **否**（双重门控） | 有 |
| `__zcodeTransportDelayE2E` | `aw()&&e.ttft?…:void 0` | @1,086,460 | **否** | **新增** |
| `__zcodeModelTrajectoryStoreE2E` | **无门控** `typeof window<`u`&&(…)` | @1,389,925 | **是** | 有（旧已确认为无门控） |
| `__zcodeV4CommandAcksE2E` | **无门控**（首次 push 时懒挂 `??=[]`，上限 50） | @3,538,591 | **是** | **新增** |
| `__zcodeWebElementPicker` | **按需**：元素选择器组件运行时 `window['__zcodeWebElementPicker']=…` | @5,333,696 / @5,342,736 | **按需存在** | 有 |
| `__zcodeLexicalInputE2E` | **按需**：挂到具体 DOM 节点并打 `data-e2e-lexical-bridge='ready'` | @2,074,958 | **按需存在** | **新增** |
| `__zcodeLang__` / `__zcodeReportContext__` | 宿主注入脚本设置 | @1,802,480 / @1,802,578 | **宿主决定** | 有 |
| `__zcodeSessionActivity`（`Het`，状态键非 window 钩子） | — | @1,371,937 | — | **新增** |
| `__zcode_missing_parent__`（内部哨兵） | — | @2,679,792 | — | **新增（非钩子）** |

> **结论（取代旧基线的"必须用 CDP 实测，不能按源码推断"）**：门控已定位为**构建期常量**，
> 因此**可以按源码确定**：6 个 `aw()` 门控钩子在本构建上**恒不存在**，无需真机实测。
> 仍**需要真机实测**的只剩"按需挂载类"（`__zcodeWebElementPicker` / `__zcodeLexicalInputE2E`）
> 与"宿主注入类"（`__zcodeLang__` / `__zcodeReportContext__`）的**触发时机**。
> ⚠️ 注意：若将来构建把 `VITE_ZCODE_E2E_STORE_BRIDGE` 注入为 `'1'`，这 6 个钩子会**集体出现**——
> 可作为"这是 E2E 构建而非生产构建"的**指纹**。

---

## 5. 「看着能用其实恒假」的面（逐条重新证实 / 推翻）

| # | 面 | 3.15.3 判定 | 判据 |
| --- | --- | --- | --- |
| 1 | 用 `window.zcode.log` 拿**普通日志** | **仍然恒假** | §1 语义 1：`PROD:!0` ⇒ `i(level)` 恒 false，`a()` 整段不执行 |
| 2 | 用 `__ZCODE_RENDERER_DISABLE_LOGGING__` 降噪 | **仍然有害** | 它是**总闸**，连 lifecycle 与关键提示一起掐掉（§1） |
| 3 | 按源码推断 E2E 钩子存在 | **已从"疑点"升级为"确定恒假"** | §4.1：`aw()` 判据是构建期常量且未注入 |
| 4 | `zcode:web-remote-control:bootstrap-shell`（旧文提到的键） | **仍然 0 命中** | 全快照 `grep -rc` 无该串 |
| 5 | 从页面 script URL 推 base | **仍然恒假** | 主包 `import.meta.env` **0 命中**（全部构建期内联）；`import.meta` 仅 4 处且全是 `import.meta.url`（图标/Worker 的基址），不暴露 env |
| 6 | 把 `/remote/v4/latest` 当远控入口 | **仍然恒假** | §6：匹配器是"去尾斜杠后严格相等"；且 `/remote/v4/latest` 在全快照 **0 命中**（旧基线的 `\|\|'/remote/v4/latest/'` 死代码串已被删除） |
| 7 | **新增**：把 `webRemoteControlWorkspaceSwitcher` 当壳可用挂点 | **恒假** | 它是页面内部的 **React prop**（`index-BGa3KiRe.js` @6,464,300 / @6,582,314 等 12 处，全部是 props 传递），不是 DOM 属性/全局量 ⇒ **壳看不到、写不了** |
| 8 | **新增**：把 `/web-remote` 当远控入口 | **恒假（是登录页）** | `y6n(){return pathname==='/web-remote'\|\|pathname==='/web-remote/'}`；该分支只渲染登录 UI（`document.title='ZCode - Web Remote Control Sign In'` @6,554,179 / @6,555,391） |
| 9 | **新增**：向 sessionStorage 预置"下次要打开的东西" | **恒假** | §3.3：页面所有 sessionStorage 槽都是"读后即删"的一次性 intent，不是 reload 恢复槽 |
| 10 | **新增**：用 `data-e2e-lexical-bridge` 找输入框 | **有条件** | 该属性只在 E2E 桥挂载后出现，**且挂载本身可能受门控**（未能证实其门控是否也是 `aw()`）——标「**未证实**」 |

---

## 6. 路由契约：`/remote/v4` 认什么形态（新构建逐条复核）

### 6.1 匹配器与解析器（新位置，旧基线坐标作废）

旧基线的匹配器在 `src-wmk2orCZ.js@275,187`（`dO`/`fO`）。3.15.3 里它们搬到共享 chunk `src-AL-_ONNp.js`：

```js
// src-AL-_ONNp.js @62,712   —— 路由路径解析器（主包 import 别名 Ht）
function Rd(e){let t=e.trim()||`/`,n=`/`;try{n=new URL(t,`https://zcode.invalid`).pathname}
  catch{n=t.startsWith(`/`)?t:`/${t}`}let r=n.replace(/\/+$/,``)||`/`;return r===`/`?`/remote`:r}
// src-AL-_ONNp.js @62,898   —— 匹配器（主包 import 别名 Tn）
function zd(e,t){let n=e=>e.replace(/\/+$/,``)||`/`;return n(e)===n(t)}
```

主包调用点（@6,565,547）：

```js
function l6n(e){let t=e.VITE_WEB_REMOTE_CONTROL_ROUTE_PATH?.trim();return Ht(t||e.BASE_URL)}
function L6n(){return Tn(window.location.pathname,l6n({BASE_URL:`/remote/v4/3.15.3/`,…,
  VITE_WEB_REMOTE_CONTROL_ROUTE_PATH:`/remote/v4`,…}))}
```

⇒ `L6n()` 等价于 `strip(pathname) === strip(Rd('/remote/v4'))` = **`strip(pathname) === '/remote/v4'`**。
**判据仍是「去尾斜杠后严格相等」，不是前缀匹配**（与旧基线结论一致；旧基线的证据链是 `dO`/`fO` + stub DOM 实测，
本轮换成 `Rd`/`zd` + 两处内联比较，**结论未变**）。

**注意**：`Rd` 的兜底是 `'/' → '/remote'`；`l6n` 的兜底是 `BASE_URL`（=`/remote/v4/3.15.3/`，**不再是** `/remote/v4/latest/`）。
因 `VITE_WEB_REMOTE_CONTROL_ROUTE_PATH` 非空（`/remote/v4`），两个兜底**都不可达**。

### 6.2 三处内联判据交叉印证

| 位点 | 原文 | 坐标 |
| --- | --- | --- |
| 挂载门 | `d=window.location.pathname.replace(/\/+$/,``)===`/remote/v4`` | `index-BGa3KiRe.js` **@6,575,538** |
| 版本自举门 | `if(c.pathname.replace(/\/+$/,``)!==`/remote/v4`\|\|c.searchParams.get(`app_version`)===s)return a` | **@6,549,404** |
| 匹配器实现 | `zd`（§6.1） | `src-AL-_ONNp.js` @62,898 |

### 6.3 分派链（`K6n()`，@6,586,947）—— 与旧基线的结构对照

```js
async function K6n(){let e=new URLSearchParams(window.location.search);
  if(v6n(e)){S6n();return}                                  // ① OAuth 回调
  if(F3n(window.location.pathname)){await C6n();return}      // ② 分享页
  if(L6n()){await W6n(e);return}                             // ③ 手机远控入口 = /remote/v4
  if(e.has(`remoteControlToken`)){…G6n(e)…;return}           // ④ token 型（窗口远控）
  if(b6n(e)){…登录…;return}                                  // ⑤ /web-remote 登录页  ← 新增分支
  …document.title=`ZCode - Web + Server`…                    // ⑥ 桌面版外壳回落
}
```

- `document.title='ZCode - Web Remote Control'` 出现在 **5 处**（@6,555,607 / 6,575,244 / 6,581,533 / 6,582,892 / 6,585,259）；
  桌面回落 `document.title='ZCode - Web + Server'` @**6,587,574**。**两个 title 串与旧基线逐字相同** ⇒
  [28 篇 §八第 3 条](28-remote-v4-网页更新影响-壳健壮性与重适配成本.md)的"一行 title 判落错分支"护栏**依然可用**。
- `/remote/v4/latest` 与任意乱路径一样，落到 ⑥ 桌面外壳。

### 6.4 **新增机制：版本自举重定向（`s6n`，@6,549,007）**

```js
function s6n(e){…check(){…return e.requestBootstrap().then(a=>{
  let s=a.desktopAppVersion?.trim(); if(!s)return a;
  let c=new URL(e.getUrl());
  if(c.pathname.replace(/\/+$/,``)!==`/remote/v4`||c.searchParams.get(`app_version`)===s)return a;
  c.searchParams.set(`app_version`,s),r=c.toString(),e.isPageReady()?e.onMismatch(s):o()})}}
// 调用：s6n({requestBootstrap:()=>F(),getUrl:()=>window.location.href,isPageReady:()=>i,
//           navigate:e=>window.location.replace(e),onMismatch:e=>{f=c6n(e,()=>p.confirm())}})   @6,575,601
```

语义：页面 bootstrap 拿到桌面端版本 `desktopAppVersion`；若当前 `strip(pathname)==='/remote/v4'`
且 `?app_version` 与之不等，就**把 `app_version` 写进 URL 并 `window.location.replace()`**（未就绪时），
或弹出**阻塞对话框**（已就绪时）。对话框逐字（@6,550,334 区）：

- 标题：`桌面版本已更新` / `Desktop version changed`
- 正文：`桌面当前版本为 ${e}。任务操作已暂停，请重新加载以继续。未发送的附件不会保留。`
- 按钮：`重新加载` / `Reload`
- 期间挂**捕获相** `keydown` 监听并 `stopImmediatePropagation()` ⇒ **键盘输入被阻断**

> **这是本轮最重要的机制性新增**：`app_version` 从"只是宿主埋点参数"变成**承重的路由选择参数**。
> 直接后果见 [28 篇 §四](28-remote-v4-网页更新影响-壳健壮性与重适配成本.md)。

---

## 7. 壳当前依赖的扩展面（一张表，3.15.3 复核）

| 面 | 壳侧用法 | 3.15.3 是否仍可用 |
| --- | --- | --- |
| `window.zcode.log` | 提供钩子 → 页面 lifecycle 日志变 `pagelog` 桥消息 | **可用**（§1，语义未变） |
| relay WebSocket（**协议面**，非扩展面） | hook `WebSocket.prototype`，被动转发协议帧 | **可用**（线格式未变，见 28 篇 §五） |
| DOM 属性（**可观测面**） | 对话存活/滚动/行数/跟随态 | **可用**（37 条锚点 0 消失，见 28 篇 §三） |
| `history.state` / `localStorage` 槽位（**写入面**） | 通知点击定位、档 3 恢复 | **可用**（键名须用 `zcode-v4-last-session:v1:`，§3.2） |
| `zcode:developer-tools:enabled`（**未使用**） | 壳没写它 | **可用且更省事**（≤1s 生效，§3.1） |
| CustomEvent 总线 | **壳一处都没用** | 页面侧有 13 个，**对壳零价值**（§2.3） |
| `webRemoteControlWorkspaceSwitcher` | **不可用** | React prop，非 DOM（§5 #7） |

---

## 8. 复现

```bash
cd E:/Zcode_android/Reference_web/3.15.3/assets
# ① 日志出口（3,314 B 的 chunk 头部即 logger；可整读）
cat officeFilePreview-Bs0TmYyT.js
node -e "const b=require('fs').readFileSync('officeFilePreview-Bs0TmYyT.js');console.log(b.indexOf('__ZCODE_RENDERER_DISABLE_LOGGING__'))"   # → 431

# ② CustomEvent 名全集（含变量构造名：先列 new CustomEvent( 位点，再回查首参变量）
grep -r -o -b "new CustomEvent(" --include=*.js .
grep -o -b '[,;{ ]XVn=`[^`]*`' index-BGa3KiRe.js   # 变量名回查（形如 ,yO=`zcode:add-whiteboard-to-chat`）

# ③ 宿主可写开关（@5,538,801 区）
node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');const i=b.indexOf('zcode:developer-tools:enabled');console.log(i);process.stdout.write(b.subarray(i-260,i+900).toString())"

# ④ zcode* 键字面量去重计数（3.15.3 应得 88；对 27 篇旧快照跑应得 72）
node -e "const s=require('fs').readFileSync('index-BGa3KiRe.js','utf8');const r=/\`(zcode[-:][A-Za-z0-9:_.-]{2,60})\`/g;const t=new Set();let m;while(m=r.exec(s))t.add(m[1]);console.log(t.size)"

# ⑤ E2E 门控与钩子（@1,086,297 是 aw()；判据键全快照只有读取、没有定义）
node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');const i=b.indexOf('function aw(');console.log(i);process.stdout.write(b.subarray(i-900,i+300).toString())"
grep -r -o "VITE_ZCODE_E2E_STORE_BRIDGE" --include=*.js .   # 4 处，全为读取

# ⑥ 路由契约（匹配器 zd / 解析器 Rd；挂载门与版本门两处内联比较）
node -e "const b=require('fs').readFileSync('src-AL-_ONNp.js');const i=b.indexOf('function zd(');console.log(i);process.stdout.write(b.subarray(i-260,i+200).toString())"
grep -c "/remote/v4/latest" index-BGa3KiRe.js    # → 0

# ⑦ 旧基线对照（只读，勿改）
cd "E:/Zcode_harmony/Docs/27-remote-v4-网页快照-20260920/latest/assets" && ls index-B-ilXaCQ.js src-wmk2orCZ.js logger-BcwVkv_N.js
```

---

## 9. 出处

- 快照：`E:\Zcode_android\Reference_web\3.15.3\`（2,577 assets，主包 SHA-256 `ce2e2fc4…b88fd`）；
  抓取记录 `Reference_web\README.md`、`Reference_web\_entry-evidence\README.md`（2026-10-15）。
- 旧基线（只读对照）：`Docs/27-remote-v4-网页快照-20260920/latest/`（`index-B-ilXaCQ.js`，4,821,509 B）。
- 相关篇目：[15 架构与模块地图](15-remote-v4-网页架构与模块地图.md) ·
  [16 relay 协议目录](16-remote-v4-relay协议目录.md) ·
  [19 输入附件与草稿](19-remote-v4-功能域-输入附件与文件预览.md) ·
  [21 回前台恢复](21-回前台对话恢复-页面机制与真机事实.md) ·
  [24 重载恢复](24-remote-v4-页面重载恢复机制逐条取证.md) ·
  [28 网页更新影响与重适配成本](28-remote-v4-网页更新影响-壳健壮性与重适配成本.md)。
