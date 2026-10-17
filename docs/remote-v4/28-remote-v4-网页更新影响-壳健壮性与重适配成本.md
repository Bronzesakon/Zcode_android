# 28-remote-v4 网页更新影响 · 壳健壮性与重适配成本

> **审计基线**：3.15.3（2026-10-15 快照）
> 主包 `assets/index-BGa3KiRe.js`（6,592,643 B，SHA-256 `ce2e2fc4…b88fd`）
> schema `assets/validation-CqXZOLXO.js`（307,369 B，SHA-256 `96e64e8f…dd6c9b`）
> 旧基线（2026-09-20 / `index-B-ilXaCQ.js`）的偏移与符号名**一律作废**。
>
> 快照根：`E:\Zcode_android\Reference_web\3.15.3\`（2,577 assets，入口 HTML 18,351 B，SHA-256 `4ca1a8b0…32f2`）
> **旧基线对照**（只读）：`Docs/27-remote-v4-网页快照-20260920/latest/`（`index-B-ilXaCQ.js`，4,821,509 B）
> **入口实测记录**：`Reference_web\_entry-evidence\README.md` + `unversioned-index.html`（2026-10-15）
> **本篇性质**：旧版是"成本估算"；本次**正好发生了一次真实更新**（20260920 构建 → 3.15.3），
> 所以下面给的是**实测的重适配成本**，不是估算。所有偏移是 UTF-8 字节偏移，只在上述 SHA 上可复现。

---

## 一、结论速览

| # | 问题 | 结论 | 强度 |
|---|---|---|---|
| 1 | **这次真实更新让壳功能失效了吗？** | **没有。** 壳依赖的 **37 条锚点 0 消失 / 0 恒假**；壳对网页资源文件名/哈希/资源根**零引用**。 | 硬结论（全量检索 + 逐条复核） |
| 2 | **壳这次改了几行？** | **0 行**。这是一次**主包 +37% 体积、i18n +920 键、testid +34 常量**的真实功能升级，壳侧一行未动。 | 硬结论 |
| 3 | **文件切分变了吗？** | **变了（本次最大变化）**：`logger-*.js` 被并入 `officeFilePreview-*.js`；`src-wmk2orCZ.js` 拆成 `validation-*.js` + **新的共享核心 `src-AL-_ONNp.js`**；schema、i18n、路由匹配器的落点**全部搬家**。 | 硬结论 §二 |
| 4 | **入口纪律变了吗？** | **变了，而且是承重变化**：`/remote/v4`（无参）**仍服务旧构建**；3.15.3 走 `/remote/v4/3.15.3/`，由 **`?app_version=3.15.3`** 选择；页面会**自己补参数并整页重载**。 | 实测 §四 |
| 5 | **旧基线「`app_version` 不是页面版本号」还对吗？** | **被推翻**。`app_version` 现在是**承重的路由选择参数**，页面有专门的自举重定向机制（`s6n`）。 | 推翻 §四 |
| 6 | **`/remote/v4/latest` 还是坏入口吗？** | **仍然是**（匹配器仍为"去尾斜杠后严格相等"），且 `/remote/v4/latest` 字面量在全快照 **0 命中**。 | 硬结论 §四 |
| 7 | **下次网页再重建，壳要花多大力气？** | **例行重建 + 功能升级：0**。会断的只有三类：**锚点改名/删除**、**入口形态变化**（本次即此类）、**线格式变化**。前两类各有可自动化的预警。 | 成本模型 §六 |

---

## 二、这次真实更新：20260920 → 3.15.3 的量化切分变化

### 2.1 两代产物的指纹与规模

| 项 | 20260920（旧基线） | 3.15.3（当前） | Δ |
|---|---|---|---|
| 应用版本号 | 3.12.3 | **3.15.3** | +3 次小版本 |
| 主包 | `index-B-ilXaCQ.js` · 4,821,509 B | `index-BGa3KiRe.js` · **6,592,643 B** | **+1,771,134 B（+36.7%）** |
| 协议 schema 块 | `src-wmk2orCZ.js` · 285,095 B | `validation-CqXZOLXO.js` · **307,369 B** | +22,274 B（**换了文件**） |
| 共享核心块 | （与 schema 同块） | **`src-AL-_ONNp.js` · 82,282 B（新拆分）** | 新增文件 |
| 日志出口块 | `logger-BcwVkv_N.js` · 1,196 B | **并入 `officeFilePreview-Bs0TmYyT.js` · 3,314 B** | **合并** |
| CSS | `index-DR0mqKFV.css` · 392,369 B | `index-BD2BL4Cc.css` · **455,351 B** | +62,982 B |
| i18n 块 | `IntlProvider-D2bfNJ9H.js` · 727,956 B | `IntlProvider-C-26GNLk.js` · **855,577 B** | +127,621 B |
| i18n 去重键（同正则） | **5,409** | **6,329** | **+920 净（+1,040 新 / −120 删）** |
| `__vite__mapDeps` 项数 | 1,757 | **1,829** | +72 |
| testid kebab 常量（`src-*` 内） | 299 | **333** | +34 |
| `data-testid` 去重值（全 assets） | 169 | **472** | +303 |
| 归档 assets 数 | 2,518 | **2,577** | +59 |
| 入口 HTML | 20,641 B / 336 行 / **81** modulepreload | 18,351 B / 313 行 / **57** modulepreload | 见 §四 |

> **切分变化的性质**：旧基线的三处"壳容易踩的落点"全部搬家，但**搬家不改变锚点字符串本身**：
> - `[data-testid="chat-error-banner"]`：`src-wmk2orCZ.js` → **`src-AL-_ONNp.js`**
> - `向 ZCode 提问…` / `新建任务` / `任务会话`：`IntlProvider-D2bfNJ9H.js` → **`IntlProvider-C-26GNLk.js`**
> - 路由匹配器 `dO`/`fO`（`src-wmk2orCZ.js@275,187`）→ **`Rd`/`zd`（`src-AL-_ONNp.js@62,712 / @62,898`）**
> ⇒ 旧基线的**方法论告诫仍然成立且更要紧**：**"只 grep 主包"的版本体检会产生假阴性**，必须扫全 `assets/**`。

### 2.2 入口 HTML 的逐行差异（内容差异极小）

| 项 | 旧（`latest` 入口） | 新（`3.15.3` 入口） |
|---|---|---|
| 入口脚本 | `/remote/v4/latest/assets/index-B-ilXaCQ.js` | `/remote/v4/3.15.3/assets/index-BGa3KiRe.js` |
| 去重行数 | 264 | 241 |
| 内联脚本 | 1 个，**4,003 B** | 1 个，**4,003 B** |
| 内联脚本 SHA-256(前16) | `208d47accbe12093` | `208d47accbe12093`（**逐字节相同**） |
| `.zcode-boot-loading` 出现次数 | **8** | **8** |

**⇒ 入口 HTML 的结构与内联主题引导脚本完全未变**（差异只有资源前缀与资源名）。壳对 `index.html` 的一切假设
（`.zcode-boot-loading` 开屏骨架、`data-zcode-browser-theme-surface` 内联写入、8 处 boot class）**继续成立**。

---

## 三、壳依赖的页面锚点：37 条逐条复核（0 消失 / 0 恒假）

方法：把 `android-shell/app/src/main/assets/inject.js`（4,021 行）与 `entry/src/main/ets/pages/Index.ets`（1,229 行）
里出现的**每一个** DOM 锚点/存储键/类名/CSS 变量取出，在 3.15.3 全快照（`index.html` + 全部 `.js/.mjs/.css`）里逐条定位。
下表按用途合并了同名成对项（如 `theme-zai-dark`/`theme-zai-light`），**逐条计数为 37**。

| 锚点 | 壳侧位置 | 3.15.3 命中文件 | 字节偏移（`index-BGa3KiRe.js`） | 结论 |
|---|---|---|---|---|
| `[data-mobile-page="chat"]` | `inject.js`（`querySelector`/`section[...]`） | 主包 | @4,340,222 | 存活 |
| `[data-testid="chat-error-banner"]` | `inject.js:2116` 区 | `src-AL-_ONNp.js` | —（**已换块**） | 存活 |
| `[data-v4-running-live-tail]` | `inject.js` | 主包 | @3,453,745 | 存活 |
| `[data-v4-timeline-scroll]` | `inject.js` | 主包 | @3,451,135 / @3,451,168 | 存活 |
| `[data-zcode-chat-loading-animate]` | `inject.js` | CSS + 主包 | — | 存活 |
| `data-row-count` | `inject.js:1611,2112-2114` | 主包 | @3,451,259 | 存活（"时间线已就绪"判据） |
| `data-loading-older` | `inject.js` | 主包 | @3,451,413 | 存活 |
| `data-following` | `inject.js` | 主包 | @2,898,050 / @3,451,378 / @5,439,993 | 存活 |
| `.zcode-boot-loading` | `Index.ets:119`、`inject.js` | **`index.html`（仅此）** | html @9,025 / @9,549 / @9,743 …（8 处） | 存活 |
| `.bg-background-win-alt` | `Index.ets:118`、`inject.js` | CSS + 主包 | — | 存活 |
| `data-zcode-browser-theme-surface` | `inject.js`（`getAttribute`） | **`index.html`** + 主包 | html @4,477 / @8,253；主包 @707,167 | 存活 |
| `header[data-testid="workspace-header"]` | `Index.ets:231` | CSS + 主包 + `src-AL-_ONNp.js` | — | 存活 |
| `button[data-testid="side-pane-toggle"]` | `Index.ets:238` | `src-AL-_ONNp.js` | — | 存活 |
| `button[data-testid="terminal-toggle"]` | `Index.ets:239` | `src-AL-_ONNp.js` | — | 存活 |
| `chat-send-button` | （候选升级点） | 主包 + `src-AL-_ONNp.js` | — | 存活 |
| `v4-composer-send` / `v4-composer-input` / `v4-timeline` | （候选升级点） | `src-AL-_ONNp.js`（+主包） | — | 存活 |
| `task-list` / `task-item` | （候选升级点） | `chunk-BO2N2NFS-*.js` + `src-AL-_ONNp.js` | — | 存活 |
| `向 ZCode 提问…` | `inject.js:1721-1726,1833-1836` | `IntlProvider-C-26GNLk.js` | —（**已换块**） | 存活（逐字未变） |
| `新建任务` | `inject.js` | 主包 + `IntlProvider-C-26GNLk.js` | — | 存活 |
| `任务会话` | `inject.js` | `IntlProvider-C-26GNLk.js` | — | 存活 |
| `theme-zai-dark` / `theme-zai-light` | `inject.js` / `Index.ets` | CSS（19/17 处）+ 主包 | 主包 @708,138 / @1,796,970 / @6,435,730 | 存活（**仅这 2 个类，前缀未变**） |
| `--color-background-win-alt` | `inject.js` 色表 | CSS（仅） | — | 存活 |
| `--color-border` | `inject.js` | CSS + 主包 + 2 个图表 chunk | — | 存活 |
| `zcode:developer-tools:enabled` / `zcode:token-debug:enabled` | （未使用，候选） | 主包 | @5,538,801 / @5,538,833 | 存活 |
| `zcode:auth:jwt-invalid-restart` | `inject.js` 区 | 主包 | @6,395,523 | 存活 |
| `zcode-v4-last-session:v1:` | `inject.js`（恢复槽） | 主包 | @3,724,155 | 存活（**键名须带 `:v1:`**） |
| `zcode-v4-pending-commands:v1` | `inject.js` | 主包 | @2,646,605 | 存活 |
| `zcode:coding-plan:embedded` | （宿主注入标记） | 主包 | @1,797,090 | 存活 |
| `zcode-locale-preference` | `inject.js` | 主包 + `IntlProvider-C-26GNLk.js` | — | 存活 |
| `webRemoteControlWorkspaceSwitcher` | （**不是壳的锚点**） | 主包（React prop，12 处） | @4,843,536 … | 存活但**壳不可用**（见 [10 篇 §5](10-remote-v4-网页扩展面与可观测面穷尽盘点.md)） |
| `data-v4-timeline-scroll-locked` | （候选升级点） | 主包 | @3,451,168 | 存活 |
| `data-v4-timeline-header-slot` | （候选升级点） | 主包 | @3,452,828 | 存活 |

**合计 37 条：消失 0 / 恒假 0。**

> **方法论告诫（务必写进巡检脚本）**：`chat-error-banner`、`向 ZCode 提问…` 这次**又搬了一次家**
> （`src-wmk2orCZ.js` → `src-AL-_ONNp.js`；`IntlProvider-D2bfNJ9H.js` → `IntlProvider-C-26GNLk.js`）。
> 巡检**必须扫全 `assets/**` 且必须扫 `index.html`**——`zcode-boot-loading` 与
> `data-zcode-browser-theme-surface` 的首次写入只在 `index.html` 里，只 grep 主包会误判成"死判据"。

### 3.1 壳侧 URL 校验（未变，但被新形态放大）

| 实现 | 位置 | 判据 |
|---|---|---|
| 安卓壳 | `android-shell/app/src/main/java/com/zcode/remote/core/RemoteUrl.kt:38-45` | `https` + `host=='z.ai'\|\|endsWith('.z.ai')` + `path.startsWith('/remote')` |
| 鸿蒙壳 | `entry/src/main/ets/common/RemoteUrlManager.ets:21-40` | 同上（`protocol=='https:'`、`hostname=='z.ai'\|\|endsWith('.z.ai')`、`pathname.startsWith('/remote')`） |
| 注入作用域 | `android-shell/app/src/main/java/com/zcode/remote/MainActivity.kt:507` | `setOf("https://z.ai","https://*.z.ai")`（**origin 级，不含路径**） |

**结论**：`/remote/v4`、`/remote/v4/`、`/remote/v4/latest`、`/remote/v4/3.15.3`、`/remote/v4?app_version=…`
在两端校验里**全部通过**。旧基线 §3.2 的判据描述**仍然准确**；但**新的版本化前缀让"通过但不可用"的形态多了一种**：

| 壳放行的形态 | 服务端 | 页面行为 | 壳能否察觉 |
|---|---|---|---|
| `/remote/v4?app_version=3.15.3` | 200（新构建） | 挂远控入口 | — |
| `/remote/v4`（无参） | 200（**旧构建**） | 挂远控入口，**然后自举重定向到 `?app_version=<桌面版本>` 并整页 reload** | **不能**（只看到一次导航） |
| `/remote/v4/` | 200 | 等价于上两行（尾斜杠归一化） | 不能 |
| `/remote/v4/latest` | 200（旧构建 HTML） | **落到桌面版外壳**（`title='ZCode - Web + Server'`） | **不能** |
| **`/remote/v4/3.15.3`** | **404** | 无 | **不能**（壳直接白屏/报错，日志里没有"这是 404"这一条） |

> **G4 已消失**：旧基线 §4.2 的 G4 缺口"两处 `/remote/v4` 字面路径"（`inject.js:2553`、`ShellRuntime.kt:1468`）
> 在当前壳代码里**已不存在**——全 `android-shell`（排除 `build/`）检索 `/remote/v4` **0 命中**。该缺口**关闭**。

### 3.2 壳的 `ready` 帧仍未带构建指纹（G1 仍开着）

`inject.js:4005` 逐字：`post('ready', {href: location.href, documentId: pageDocumentId});` ⇒ **无版本指纹**。

**这次更新让这条缺口的代价变大了**：现在版本号**就写在 URL 路径里**（`/remote/v4/3.15.3/`），
壳只要在 `ready` 帧里带上 `location.pathname` 的第三段，就能回答"我现在跑的是哪版页面"。
（旧基线说"页面自己不自报版本，指纹只能从资源名推"——**这一点现在更简单了**，见 §七第 4 条。）

---

## 四、入口纪律：旧 §3.6 的重写（含一处推翻）

### 4.1 匹配语义：未变（仍是"去尾斜杠后严格相等"）

匹配器从 `src-wmk2orCZ.js@275,187` 搬到 `src-AL-_ONNp.js`，实现更直白：

```js
// src-AL-_ONNp.js @62,712  —— 路由路径解析器（主包 import 别名 Ht）
function Rd(e){let t=e.trim()||`/`,n=`/`;try{n=new URL(t,`https://zcode.invalid`).pathname}
  catch{n=t.startsWith(`/`)?t:`/${t}`}let r=n.replace(/\/+$/,``)||`/`;return r===`/`?`/remote`:r}
// src-AL-_ONNp.js @62,898  —— 匹配器（主包 import 别名 Tn）
function zd(e,t){let n=e=>e.replace(/\/+$/,``)||`/`;return n(e)===n(t)}
```

主包调用点 @6,565,547：

```js
function l6n(e){let t=e.VITE_WEB_REMOTE_CONTROL_ROUTE_PATH?.trim();return Ht(t||e.BASE_URL)}
function L6n(){return Tn(window.location.pathname,l6n({BASE_URL:`/remote/v4/3.15.3/`,…,
  VITE_WEB_REMOTE_CONTROL_ROUTE_PATH:`/remote/v4`,…}))}
```

⇒ `L6n()` = `strip(pathname) === '/remote/v4'`。**不是前缀匹配**。两处内联判据交叉印证：

| 位点 | 原文 | 坐标 |
|---|---|---|
| 挂载门 | `d=window.location.pathname.replace(/\/+$/,``)===`/remote/v4`` | `index-BGa3KiRe.js` @**6,575,538** |
| 版本自举门 | `if(c.pathname.replace(/\/+$/,``)!==`/remote/v4`\|\|c.searchParams.get(`app_version`)===s)return a` | @**6,549,404** |

**`/remote/v4/latest` 字面量在全快照 0 命中**——旧基线里那句
`Noe(\`/remote/v4\`?.trim()\|\|\`/remote/v4/latest/\`)` 的死代码兜底**已被删除**；
新的兜底是 `BASE_URL`（`/remote/v4/3.15.3/`），但因 `VITE_WEB_REMOTE_CONTROL_ROUTE_PATH` 非空，**不可达**。

分派链（`K6n()` @6,586,947）：① OAuth 回调 → ② 分享页 → ③ **`/remote/v4` 手机远控** →
④ `remoteControlToken` → ⑤ **`/web-remote` 登录页（新增分支）** → ⑥ 桌面版外壳回落
（`document.title='ZCode - Web + Server'` @**6,587,574**）。远控入口的 title 仍是
`ZCode - Web Remote Control`（5 处：@6,555,607 / 6,575,244 / 6,581,533 / 6,582,892 / 6,585,259）
⇒ [§七第 3 条](#七护栏建议把运气变成信号)的"一行 title 判落错分支"**依然可用**。

### 4.2 **推翻旧基线 §2.3：「`app_version` 不是页面版本号」不再成立**

旧基线写：「`app_version` 是"生成这个链接的宿主 App 版本"这个查询参数，**不是页面版本号**；
`/remote/v4/3.10.1` 实测 **404**」。**这一结论在本构建上被推翻**，依据是三路实测：

| 证据 | 内容 | 来源 |
|---|---|---|
| 服务端 | `https://zcode.z.ai/remote/v4?app_version=3.15.3` → 18,351 B HTML，加载 `/remote/v4/3.15.3/assets/index-BGa3KiRe.js`；**不带该参数**的 `/remote/v4` → 20,641 B HTML，仍加载 `/remote/v4/latest/assets/index-B-ilXaCQ.js`（**旧构建**） | `Reference_web/_entry-evidence/README.md`（2026-10-15 实测） |
| 字节级 | `_entry-evidence/unversioned-index.html`（20,641 B，SHA-256 前16 `40ba258490171be3`）与旧快照 `Docs/27-.../latest/index.html` **逐字节相同** | 本轮实测 |
| 客户端 | 页面有**自举重定向** `s6n`：bootstrap 拿到 `desktopAppVersion` 后，若 `strip(pathname)==='/remote/v4'` 且 `?app_version` 与之不等，就 `searchParams.set('app_version', s)` 并 `window.location.replace()`（或弹阻塞对话框） | `index-BGa3KiRe.js` @6,549,007 / @6,575,601 |

**重定向与对话框逐字**（@6,550,334 区）：

```js
// s6n.check()  —— 版本自举
let s=a.desktopAppVersion?.trim(); if(!s)return a;
let c=new URL(e.getUrl());
if(c.pathname.replace(/\/+$/,``)!==`/remote/v4`||c.searchParams.get(`app_version`)===s)return a;
c.searchParams.set(`app_version`,s),r=c.toString(),e.isPageReady()?e.onMismatch(s):o()
// 调用点 @6,575,601
p=s6n({requestBootstrap:()=>F(),getUrl:()=>window.location.href,isPageReady:()=>i,
       navigate:e=>window.location.replace(e),onMismatch:e=>{f=c6n(e,()=>p.confirm())}})
```

对话框文案（zh-CN / en，`t6n(navigator.language)` 决定）：

- 标题：`桌面版本已更新` / `Desktop version changed`
- 正文：`桌面当前版本为 ${e}。任务操作已暂停，请重新加载以继续。未发送的附件不会保留。` /
  `The desktop is now running ${e}. Task controls are paused. Reload to continue. Unsent attachments will not be retained.`
- 按钮：`重新加载` / `Reload`
- 期间挂**捕获相** `keydown` 监听并 `e.stopImmediatePropagation()` ⇒ **键盘输入被阻断**

> **`/remote/v4/3.15.3` 实测 404 并不矛盾**：版本选择走的是**查询参数** `?app_version=`，
> 不是路径段。旧基线把"路径段 404"误读成"版本参数不承重"。

### 4.3 桌面端发的形态：仍成立，但残留风险升级

旧基线 §3.6 的最终裁定（桌面端"复制链接"发的是 `/remote/v4?sid=…`，不含 `/latest`）**未复核**（未重新取证桌面端）。
但本次新增一条**与桌面端无关**的风险：**用户手输或旧链接里的无参 `/remote/v4` 现在会加载旧构建**，
页面随即自举重定向——对壳而言这是一次**未被解释的整页导航**（壳日志里只有一次 reload，没有原因）。

---

## 五、本次实际打断了哪些壳侧假设（量化 + 成本等级）

| # | 壳侧假设 | 本次是否被打断 | 证据 | 重适配成本等级 |
|---|---|---|---|---|
| 1 | 页面资源名/哈希/资源根固定 | **未打断**（哈希全变，但壳零**功能**引用） | 全 `android-shell`（排除 `build/`）检索旧哈希：`B-ilXaCQ`/`ilXaCQ`/`wmk2orCZ`/`BcwVkv`/`nOVzQNKW`/`ZagIv7p`/`C7ym11zO` **0 命中**；仅 `BMndL2ru`/`DR0mqKFV` 各 1 处，且都在 `inject.js:3539-3541` 的**注释**里（滚动条规格的出处说明，非代码） | **无需改**（结构性免疫；注释里的旧哈希属文档性过期，见 §六） |
| 2 | 壳依赖的 DOM 锚点字符串存在 | **未打断**（37/37 存活） | §三 全量复核 | **无需改** |
| 3 | 锚点的**所在文件**固定 | **打断（6 处落点搬家）** | §2.1：`chat-error-banner`、i18n 文案、路由匹配器、logger 全部换块 | **无需改壳代码**，但**巡检脚本的落点注释会过期** → 改注释（分钟级） |
| 4 | `index.html` 结构与内联脚本 | **未打断** | §2.2：内联脚本 4,003 B 逐字节相同；boot class 仍 8 处 | **无需改** |
| 5 | 主题类名前缀 `theme-zai-*` | **未打断** | 仍只有 `theme-zai-dark`/`theme-zai-light` 两类（CSS 19/17 处） | **无需改** |
| 6 | CSS 变量 `--color-border` / `--color-background-win-alt` | **未打断** | 两者均存活（后者仅 CSS） | **无需改** |
| 7 | 文案锚点逐字不变 | **未打断**（但搬块） | `向 ZCode 提问`/`新建任务`/`任务会话` 逐字存活于新 i18n 块 | **无需改**；**G8/G9 缺口仍在**（见 §六） |
| 8 | **入口 URL 形态**（`/remote/v4` 一个就够） | **打断** | §4.2：无参会加载旧构建并触发自举重定向 | **改机制**：见 §七第 2、4 条 |
| 9 | 版本号可从资源名推 | **打断（向好的方向）** | 版本号现在**就在 URL 路径里**（`/remote/v4/3.15.3/`） | **改常量/改机制**：`ready` 帧带 `pathname` 即可（1 处，0.5 人时） |
| 10 | relay 线格式与协议契约不变 | **未打断** | 见 §5.1 | **无需改** |
| 11 | `zcode-v4-last-session`（无版本后缀）是恢复槽键名 | **打断（命名订正）** | 实际键是 `zcode-v4-last-session:v1:`；壳用的正是带后缀的全名 | **无需改**（壳本来就对；**文档要订正**） |
| 12 | 页面 CustomEvent 只有 2 个 | **打断（认知订正）** | 3.15.3 有 **13 个 `zcode*` 事件**；旧构建实为 11 个 | **无需改**（壳一处都没监听） |

**合计**：本次更新**打断了 4 条壳侧假设**（#3 落点、#8 入口形态、#9 版本指纹可得性、#11/#12 认知订正），
其中**只有 #8 需要动壳代码**，且是"加护栏"而非"修故障"；**壳的实际改动 = 0 行**。

### 5.1 协议面：本轮只做交叉校验（协议细节归第二批子代理）

本轮**未**重跑三代主包的线格式逐字节比对（那是旧基线的做法，且旧快照是另一套 G1/G2/G3 代号，已作废）。
本轮只做了两项与壳直接相关的确认：

| 确认项 | 结论 | 坐标 |
|---|---|---|
| relay 桥接用的 `zcode_type` 判别字段仍在 | 在（`validation-CqXZOLXO.js` 与主包两处） | 主包 @多 |
| 壳的 `ready` 帧契约 | 未变（仍 `{href, documentId}`，无协议字段） | `inject.js:4005` |

> ⚠️ **未复核（旧基线）**：旧基线 §3.4「relay 线格式三代逐字节相同」、§五「构建自带 2 条死链 / shiki 双份 /
> 草稿 50 条上限 LRU 被删」等条目，**本轮未在新构建上重跑**，**不得**当作 3.15.3 的事实引用。

---

## 六、重适配成本模型（实测版）

| 变化类型 | 壳要改什么 | 工作量 | 可自动化 | **本次是否发生** |
|---|---|---|---|---|
| **例行重新构建**（哈希/资源根变） | **无** | **0** | 已结构性免疫 | **是**（资源名全变） |
| **跨版本功能升级**（页面新增功能面） | **无**（只要没动壳的锚点） | **0** | 巡检可覆盖 | **是**（3.12.3 → 3.15.3，主包 +37%、i18n +920 键） |
| **锚点所在 chunk 搬家** | **无需改壳代码**；改巡检脚本注释 | **0.1 人时** | 巡检可覆盖 | **是**（6 处落点） |
| **代码注释里的旧哈希过期** | `inject.js:3539-3541` 的出处注释（`index-BMndL2ru.css @368578` 等） | **0.1 人时** | 否 | **是**（注释仍指 2026-09-11 快照） |
| 锚点改名（`data-*`/class） | `inject.js` 选择器 + `Index.ets` 观察脚本 + test 夹具 | 0.5–2 人时/点，DOM 类通常 3–8 处 | 半自动 | 否 |
| 锚点删除 / 语义变 | 同上 + 需替代判据 | 0.5–2 人日 | 否 | 否 |
| 文案 / i18n 变更 | `inject.js` 文本回退匹配 | 0.5–2 人时 | 巡检可覆盖 | 否（搬块但逐字未变） |
| **入口形态变化** | 两端 URL 校验 + `ready` 帧带指纹 | **0.5–1 人日** | 半自动 | **是**（`?app_version` 承重 + 自举重定向） |
| 协议字段增删 | `zcode-protocol.js` + `RelayWire.kt` + `protocol.test.js` | 1–3 人日 | 半自动 | **未复核（旧基线）** |
| **线格式变化**（分片/CRC/值编码） | 两端同步 + 真机验收 | **2–5 人日** | 否 | **未复核（旧基线）** |
| 主题 token 改名 | `inject.js` 色表 + `Index.ets` | 0.5–1 人时 | 巡检可覆盖 | 否 |
| 文档偏移量刷新 | `Docs/**` 带偏移的条目 | 1–2 人时 | 半自动 | **是**（本篇与 10 篇即为此而写） |

**结论**：
- **例行重建与功能升级的成本都趋近 0**（本次两者同时发生，**壳侧改动 0 行**）；
- 真正的成本只在**"页面改了壳依赖的语义锚点"**、**"入口/版本形态变了"**、**"线格式变了"**时发生；
- **本次发生的是第二类**，成本 = **加护栏 0.5–1 人日**（不是修故障），且**不做也不影响当前功能**。

---

## 七、护栏建议（把"运气"变成"信号"）

> **已落地**：`Docs/27-.../tools/anchor-audit.js`（159 条锚点全集，退出码 0/2/3）。
> 下面是按本轮实测重排的落地顺序。

1. **锚点巡检进 CI**（最高性价比）：`node Docs/27-.../tools/anchor-audit.js`。
   建议独立 workflow（检的是**外部页面**，壳没改也可能变红），每日 cron + 手动触发。
   **必须同时扫 `index.html` 与全 `assets/**`**——本轮再次验证：`zcode-boot-loading` 只在 `index.html`，
   `向 ZCode 提问…` 只在 i18n 块，且这两个块**本次都换了文件名**。
   ⚠️ 巡检脚本里的"锚点落点注释"本轮已过期 6 处，**需同步更新**，否则下次会照着旧文件名找而误判。
2. **收紧 URL 校验**（防呆，成本极低）：两端把 `pathname.startsWith('/remote')` 收紧为
   「`strip(pathname) === '/remote/v4'`」——这一次**理由比旧基线更硬**：
   - `/remote/v4/latest` → 服务端 200 但页面**挂桌面外壳**；
   - **`/remote/v4/3.15.3` → 服务端 404**（新增的"通过校验但根本不存在"形态）；
   - `/remote/v4`（无参）→ 加载**旧构建**，随后自举重定向（一次壳无法解释的整页导航）。
   收紧后，**上述三类一次性消失**。
   > **老板裁定（2026-09-20）**：本条暂不做（桌面端发的形态是对的，且页面收紧入口的规律未明）。
   > **本轮新增信息**：规律已明（`zd` = 去尾斜杠严格相等；`Rd` 兜底 `'/'→'/remote'`），
   > 且**旧构建与新构建在无参入口上分叉**这一事实，把"谜题"升级成了"可复现的形态表"（§3.1 末表）。
   > 建议**重新评估**本条；若仍不做，至少做第 4 条（成本更低）。
3. **注入层加"落错分支"上报**：最强判据仍是 `document.title` ——
   远控入口 = `ZCode - Web Remote Control`，桌面回落 = `ZCode - Web + Server`（两个串本轮逐字未变）。
   再加一条**版本判据**：`document.title` 正确但 `?app_version` 缺失 ⇒ 预测"即将自举重定向"。
4. **`ready` 帧带上页面构建指纹**（本轮性价比显著提升）：版本号现在**就在 URL 路径里**
   （`/remote/v4/3.15.3/`）⇒ 只需 `inject.js:4005` 那一行加 `pagePath: location.pathname`，
   壳即可回答"我现在跑的是哪版页面"。成本 **1 处 + 回归，约 0.5 人时**。
   > 注意：`?app_version` 与路径段**可能不一致**（无参入口时路径是 `/remote/v4/`），
   > 所以两个都带上更稳：`{href, documentId, pathname, appVersion: new URLSearchParams(location.search).get('app_version')}`。
5. **文案锚点换成 `data-testid`**：testid 常量表本轮 **299 → 333**，壳点名的
   `chat-send-button`/`v4-composer-send`/`v4-composer-input`/`v4-timeline`/`task-list`/`task-item`
   以及 `Index.ets` 用的 `workspace-header`/`side-pane-toggle`/`terminal-toggle` **全部存活**
   （多数在 `src-AL-_ONNp.js`）⇒ 可把 8 个文案锚点降到 0，同时消掉 **G8（随语言失效）** 与 **G9（语义漂移）**。
6. **单测夹具接一层真页面**：把 3.15.3 的 `index.html` 解析成"真夹具"跑状态判定，消掉 **G3 的假绿**。
7. **消掉两端"同源双实现"**：安卓 `inject.js` 与鸿蒙 `Index.ets` 是**同源两份状态栏判态实现**
   （判据、断点、状态名重复）。本轮**两份的锚点集合完全一致**（§三），但**没有任何机制保证它们以后仍一致**。
   建议把判据抽成一份声明式表，两端各自消费 + 一条跨端一致性断言。
8. **不要用 `__ZCODE_RENDERER_DISABLE_LOGGING__` 降噪**：它是页面**全局日志总闸**（含 lifecycle），
   关掉会一并掐掉壳自己依赖的 `window.zcode.log` 观测源；要降噪应在壳侧 `diag` 层过滤。
9. **不要再把 `webRemoteControlWorkspaceSwitcher` 当挂点**（本轮澄清）：它是页面内部 **React prop**，
   不是 DOM 属性/全局量，壳看不到也写不了（[10 篇 §5](10-remote-v4-网页扩展面与可观测面穷尽盘点.md)）。
10. **顺带上报页面方**（本轮新增/沿用的页面侧问题）：`/remote/v4/3.15.3` 路径 404 而
    `?app_version=3.15.3` 可用，两套寻址不一致容易被外部（含壳）误用。

---

## 八、引用纪律

- 本篇哈希/偏移只对以下构建有效：**3.15.3** = `index-BGa3KiRe.js`（6,592,643 B，2026-10-15 快照，
  SHA-256 `ce2e2fc4…b88fd`）；**旧基线** = `index-B-ilXaCQ.js`（4,821,509 B，2026-09-20 快照，应用版本 3.12.3）。
- 旧基线里的 **G1/G2/G3 代号**（`index-nOVzQNKW.js` / `index-BZagIv7p.js` / `index-B-ilXaCQ.js`，
  应用版本 3.11.2/3.12.3）**只适用于 2026-09-20 那批取证**，引用时必须标注；本轮的"旧基线"一律指 **G3**。
- 「3.10.1」在本文档体系内**不得**作为页面版本号使用（旧 §2.3 的其余结论仍成立：
  `app_version=3.10.1` 是当时宿主写入的埋点参数；但**"app_version 不承重"这一条已作废**，见 §4.2）。
- 引用 `Docs/10/13/14/15/16/17/18/19/20/21/22/23/24` 里的偏移时：**它们仍是 2026-09-20 按 G3 重写的版本**
  （文首「审计基线」写的是 `index-B-ilXaCQ.js`）⇒ **其偏移在 3.15.3 上不可复现**；
  旧 G1/G2 版本整批备份在 `Docs/_archive-20260920-旧构建网页审计/`。第一批子代理正在把相关篇目就地重写到 3.15.3。
