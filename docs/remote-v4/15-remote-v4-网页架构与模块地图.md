# remote-v4 网页架构与模块地图

> **审计基线**：3.15.3（2026-10-15 快照）
> 主包 `assets/index-BGa3KiRe.js`（6,592,643 B，SHA-256 `ce2e2fc4…b88fd`）
> schema `assets/validation-CqXZOLXO.js`（307,369 B，SHA-256 `96e64e8f…dd6c9b`）
> 旧基线（2026-09-20 / `index-B-ilXaCQ.js`）的偏移与符号名**一律作废**。
>
> 快照：`E:\Zcode_android\Reference_web\3.15.3\`（只读；2,577 个 assets + `index.html` + `_manifest.json`）
> 入口：`https://zcode.z.ai/remote/v4?app_version=3.15.3` ｜ 抓取完成 2026-10-15 19:52 (+08:00)
> **偏移口径：UTF-8 字节偏移**（用 `Buffer.indexOf` 取得；Node 字符串 `indexOf` 是 UTF-16 索引，中文前缀会错位，禁止使用）
> **方法**：`_manifest.json` 引用关系反推模块边界 + 关键字→字节偏移定位 + 全 2,489 个 `.js` 的字面量归属扫描

---

## 0. 一句话

这是一份 **Vite 生产构建的 React SPA**，**功能运行时几乎全部压在主包** `index-BGa3KiRe.js`（6.59 MB 单行，占全部 `.js` 体积的 17.7%），
另外 **2,488 个 chunk（30,706,978 B ≈ 30.7 MB）** 主要承载按需加载的重资源（代码编辑器语言包、Office/PDF 预览器、图标、图表、i18n 目录）。
但**协议层被切成独立 chunk**：全部业务 Zod schema 在 `validation-CqXZOLXO.js`，主包只 `import` 它的常量与类型，不再内联。
⇒ 找代码的正确姿势：**运行时/UI → 主包；协议 schema/常量 → validation chunk；"某功能在哪"先用三个字面量判别落点（见 §3.3）**。

---

## 1. 构建指纹表

### 1.1 关键文件（已实测，SHA-256 全部自行复算一致）

| 角色 | 文件 | 大小 | SHA-256（前 8 / 后 6） | Last-Modified |
| --- | --- | --- | --- | --- |
| 入口 HTML | `index.html` | 18,351 B | `4ca1a8b0…d322f2` | — |
| **主包** | `assets/index-BGa3KiRe.js` | **6,592,643 B** | `ce2e2fc4…f9b88fd` | 2026-10-13 09:09:04 GMT |
| **schema/校验模块** | `assets/validation-CqXZOLXO.js` | **307,369 B** | `96e64e8f…dd6c9b` | 2026-10-13 09:09:13 GMT |
| **样式** | `assets/index-BD2BL4Cc.css` | **455,351 B** | `40894faf…997ebc2f` | 2026-10-13 09:08:43 GMT |
| i18n 目录 | `assets/IntlProvider-C-26GNLk.js` | 855,577 B | — | 2026-10-13 09:08:16 GMT |
| 共享 UI + 次级 schema | `assets/parts-DcYGwy2q.js` | 57,142 B | — | — |
| markdown/artifact + 路由匹配 | `assets/src-AL-_ONNp.js` | 82,282 B | — | — |
| 第二样式表 | `assets/pdf-viewer-Dgb4oCkS.css` | 1,498 B | — | — |

复算命令：

```bash
cd E:/Zcode_android/Reference_web/3.15.3/assets
sha256sum index-BGa3KiRe.js validation-CqXZOLXO.js index-BD2BL4Cc.css
```

### 1.2 `_manifest.json` 记录的归档规模

| 字段 | 值 | 说明 |
| --- | --- | --- |
| `fetchedAt` | `2026-10-15T11:52:15.153Z` | = 2026-10-15 19:52 +08:00 |
| `entryUrl` | `https://zcode.z.ai/remote/v4?app_version=3.15.3` | 抓取入口 |
| `assetPrefix` | `/remote/v4/3.15.3/` | **版本固定前缀，不是 `latest`** |
| `deploymentVersion` | `3.15.3` | |
| `fileCount` | **2,578** | `records` 条数（含 `index.html`；全部 HTTP 200） |
| `totalBytes` | **45,994,698 B** | 2,578 个 200 记录之和（= `records` 的 `bytes` 累加，已复核） |
| `failures` | **6** | 全为 404，见下 |
| URL 总数 | **2,584** | `fileCount + failures` |

`failures`（6 个 404，均为 wasm/字体旁挂资源，不影响页面主路径）：

```
assets/pdf.worker.mjs                        (referencedBy assets/pdf-viewer-BomvfTjX.js)
assets/docx_wasm_bg.js                       (referencedBy assets/chunk-WKBPLHUA-lBwOoAJX.js)
assets/LiberationSans-Regular.ttf            (referencedBy assets/pdf.worker.min-qwK7q_zL.mjs)
assets/LiberationSans-BoldItalic.ttf         (同上)
assets/LiberationSans-Italic.ttf             (同上)
assets/duke_sheets_wasm_bg.js                (referencedBy assets/duke_sheets_wasm-BjbvBM6g.js)
```

文件类型分布（`records` 按扩展名统计）：`.js` 2,489 ｜ `.png` 22 ｜ `.woff2` 19 ｜ `.woff` 20 ｜ `.ttf` 20 ｜ `.wasm` 2 ｜ `.css` 2 ｜ `.mjs` 1 ｜ `.svg` 1 ｜ `.mp3` 1 ｜ `.html` 1。

`.js` 总体积 **37,299,621 B**（2,489 个文件）；其余 8.7 MB 是字体/图片/wasm。

### 1.3 与旧基线（2026-09-20）的规模对比（只说事实）

| 项 | 旧基线 | 3.15.3 | 差 |
| --- | --- | --- | --- |
| 主包 | `index-B-ilXaCQ.js` 4,821,509 B | `index-BGa3KiRe.js` **6,592,643 B** | **+1,771,134 B** |
| schema 模块 | `src-wmk2orCZ.js` 285,095 B | `validation-CqXZOLXO.js` **307,369 B** | **+22,274 B** |
| 样式 | `index-DR0mqKFV.css` 392,369 B | `index-BD2BL4Cc.css` **455,351 B** | **+62,982 B** |
| 入口 HTML | 20,641 B | 18,351 B | −2,290 B |
| 主包内资源清单 | `__vite__mapDeps` 约 73,575 字符 / 1,757 条 | **60,291 B（偏移 52–60,343）/ 1,829 条** | 条目 +72，字面量 −13,284 B |
| `.js` 文件数 | 2,506 | **2,489** | −17 |

**已核实失效的旧文件名（新快照中不存在）**：`index-B-ilXaCQ.js`、`src-wmk2orCZ.js`、`index-DR0mqKFV.css`、`catalogTree-DmoX4pSX.js`。

---

## 2. 入口与路由

### 2.1 入口 HTML（`index.html`，18,351 B，SHA-256 `4ca1a8b0…d322f2`）

| 元素 | 值 |
| --- | --- |
| module script | `<script type="module" crossorigin src="/remote/v4/3.15.3/assets/index-BGa3KiRe.js">` |
| `<link rel="modulepreload">` | **57 条**（全部 `/remote/v4/3.15.3/assets/…`，含 `chunk-Bj-mKKzh.js`、`validation-CqXZOLXO.js`、`parts-DcYGwy2q.js`、`src-AL-_ONNp.js`、`IntlProvider-C-26GNLk.js`） |
| `<link rel="preload">` | **0 条** |
| stylesheet | **1 条**：`/remote/v4/3.15.3/assets/index-BD2BL4Cc.css` |
| license | `<link rel="license" href="/remote/v4/3.15.3/THIRD-PARTY-NOTICES.md">` |
| favicon | 内嵌 `data:image/png;base64,…`（32×32，不产生网络请求） |
| 内联脚本 | 首屏主题判定：读 `localStorage['zcode-theme']` → 写 `data-zcode-bootstrap-theme` + `data-zcode-browser-theme-surface`；并读一次 `sessionStorage['zcode:web-remote-control:bootstrap-shell']` 后**立即 removeItem** |
| 内联样式 | `.zcode-boot-loading` 预渲染壳 + `html[data-zcode-browser-theme-surface]` 语义背景覆盖 |

> **`bootstrap-shell` 快照键的写入方不在网页产物内**：字符串 `zcode:web-remote-control:bootstrap-shell` 在全部 2,489 个 `.js` 与 2 个 `.css` 中 **0 命中**，只出现在 `index.html` 内联脚本里（读 + 删）。⇒ 该槽位只能由**宿主壳在跳转前注入**，或当前是死代码。**未证实**其写入方。

### 2.2 环境对象（构建期内联）

```js
{ BASE_URL: `/remote/v4/3.15.3/`, DEV: !1, MODE: `production`, PROD: !0, SSR: !1,
  VITE_WEB_REMOTE_CONTROL_ROUTE_PATH: `/remote/v4`,
  VITE_ZAI_OAUTH_CLIENT_ID: `client_P8X5CMWmlaRO9gyO-KSqtg`,
  VITE_ZAI_OAUTH_ORIGIN: `https://chat.z.ai`,
  VITE_ZCODE_BASE_URL: `https://zcode.z.ai`,
  VITE_ZCODE_ENDPOINT_ORIGIN: `https://zcode.z.ai` }
```

- 首次内联 @ 主包 **1,085,990**（工厂函数 `function U$e` @ **1,085,968**）。
- **内联 7 处**（判据：`MODE:\`production\`` 命中 7 次）：**1,086,027 / 1,796,160 / 1,804,756 / 4,585,367 / 6,495,981 / 6,499,252 / 6,565,639**。
  旧基线记的是"四处内联"——**该结论已失效**。
- `BASE_URL:` 共 15 命中；字面量 `/remote/v4/3.15.3/` 共 34 命中（资源前缀在多处硬编码）。
- **`BASE_URL` 由 `latest` 改为版本固定 `3.15.3`**：资源根 = `/remote/v4/3.15.3/assets/`，与入口路径 `/remote/v4` 仍是两个不同概念。

### 2.3 `/remote/v4?app_version=3.15.3` → `assets/index-BGa3KiRe.js` 这条链是否仍成立

**成立**，且比旧基线多了一道**版本自校正闸**。

启动入口是 `async function K6n()` @ **6,586,947**，由主包末尾直接调用（`K6n();` @ **6,588,174**，紧跟 `export{…}` 之前）。分支顺序：

| 序 | 判据（定义偏移） | 命中后行为 |
| --- | --- | --- |
| 1 | `v6n(e)` @ **6,553,695** —— `['/web-remote/callback','/cn/share/callback','/share/callback'].includes(pathname) && e.has('state') && (e.has('code')||e.has('error'))` | `S6n()` @ **6,554,164** → `document.title='ZCode - Web Remote Control Sign In'`，渲染 OAuth 回调页 |
| 2 | `F3n(pathname)` @ **6,520,004** —— `pathname==='/cn/share'`、`startsWith('/cn/share/')`、`'/share'`、`startsWith('/share/')` | `C6n()` @ **6,554,501** → `document.title='ZCode Conversation Share'`（或 `ZCode 会话分享`） |
| 3 | **`L6n()` @ 6,565,547** —— `Tn(window.location.pathname, l6n(env))` | **`W6n(e)` @ 6,575,329** ← **手机远控入口** |
| 4 | `e.has('remoteControlToken')` @ **6,584,833** 区 | `G6n(e)` @ **6,584,804** → relay 客户端直连（token 模式） |
| 5 | `b6n(e)` @ **6,553,971** —— `pathname==='/web-remote'` 或 `'/web-remote/'` | 恢复缓存会话；无缓存则 `w6n()` |
| 6 | 其余 | `M6n()` @ **6,563,711** → `fetch('/api/server-info')` → `document.title='ZCode - Web + Server'` 桌面版外壳 |

关键实现细节（可复现）：

- `l6n(e)` @ **6,551,041**：`let t=e.VITE_WEB_REMOTE_CONTROL_ROUTE_PATH?.trim(); return Ht(t||e.BASE_URL)` ⇒ 入口路径来自 `/remote/v4` 而非 `BASE_URL`。
- `Tn` 实际实现是 `src-AL-_ONNp.js` 的 `zd` @ **62,970**（该文件导出名 `E`，主包绑定为 `Tn`）：
  ```js
  function zd(e,t){let n=e=>e.replace(/\/+$/,``)||`/`;return n(e)===n(t)}
  ```
  ⇒ **去尾斜杠后严格相等**。`/remote/v4/latest`、`/remote/v4/anything` 都不匹配（旧文档此结论仍成立）。
- `W6n` 内部另有一处独立硬判：`window.location.pathname.replace(/\/+$/,``)===`/remote/v4`` @ **6,575,586**。
- **`app_version` 版本闸**：`s6n(e)` @ **6,549,007** 的 `check()` @ **6,549,470**：
  ```js
  let s=a.desktopAppVersion?.trim(); if(!s)return a;
  let c=new URL(e.getUrl());
  if(c.pathname.replace(/\/+$/,``)!==`/remote/v4`||c.searchParams.get(`app_version`)===s)return a;
  c.searchParams.set(`app_version`,s), r=c.toString(), e.isPageReady()?e.onMismatch(s):o()
  ```
  ⇒ 页面拿 `bootstrap.desktopAppVersion` 与 URL 的 `app_version` 比对；不一致就把 URL 的 `app_version` **改写成桌面端版本**并 `location.replace`，若页面已就绪则弹版本不符对话框（`c6n` @ **6,549,672**，`aria-labelledby='remote-version-title'`）。
  **所以 `?app_version=3.15.3` 是自校正参数，不是硬门禁**；桌面端版本一变，页面会自己跳走。

### 2.4 relay 参数与常量（主包 6,493,xxx 区块）

| 常量 | 偏移 | 原文 |
| --- | --- | --- |
| `/web-remote` 集合 `A4n` | 6,493,160 | `new Set([`/web-remote`,`/web-remote/`])` |
| 回调路径集合 `j4n` | 6,493,207 | `new Set([`/web-remote/callback`,`/cn/share/callback`,`/share/callback`])` |
| share 路径正则 `M4n` | 6,493,294 | `/^\/(?:cn\/share|share)\/[A-Za-z0-9._~-]{1,512}$/u` |
| **接受的查询参数 `N4n`** | 6,493,245 | `new Set([`remoteControlToken`,`relayOrigin`])` |
| 本地开发正则 `P4n` | 6,493,384 | `/^https?:\/\/(localhost\|127\.0\.0\.1\|10\.\d+…\|172\.(1[6-9]\|2\d\|3[01])…\|192\.168\..*)(:\d+)?(\/\|$)/` |
| 帧编解码起点 `I4n` | 6,493,262 | `let t=new TextEncoder().encode(e),n=``;for(let e of t)n+=…`（→ base64） |
| token 取值 | 6,584,833 | `e.get(`remoteControlToken`)?.trim()`（通用取值器，非 `searchParams.get`） |
| relayOrigin 回退 | 6,584,860 区 | `e.get(`relayOrigin`)?.trim()||window.location.origin` |

---

## 3. chunk 切分：`validation-*.js` vs 主包 vs `parts-*.js`

### 3.1 旧基线 → 新构建的切分变化（事实）

| 项 | 旧基线（2026-09-20） | 3.15.3 |
| --- | --- | --- |
| schema 落点 | `src-wmk2orCZ.js`（285,095 B），**单一文件** | `validation-CqXZOLXO.js`（307,369 B）**+ `parts-DcYGwy2q.js`（57,142 B）次级落点** |
| 常量对象符号名 | 旧文档记为 `hc` | **`hc` 是 ZodNull 工厂（@ validation 90,638），旧文档的命名是错的**；真正的协议常量对象是 **`Uu` @ validation 117,018**，导出名 **`Rn`** |
| 主包是否内联常量 | 内联（旧文档记"四处重复内联"的环境对象 + 常量） | 主包**不内联常量对象**，只 `import` 后用 `W.maxFrameBytes` / `W.snapshotTailWindowRows` |

### 3.2 `validation-CqXZOLXO.js`（307,369 B）里到底有什么

**只有两样东西：Zod 运行时核心 + 全部业务 schema。没有 React、没有 i18n 目录、没有 DOM 代码。**
（校验：`react` 仅 2 命中且是类型描述文本；`IntlProvider`/`createLucideIcon`/`localStorage`/`window.` 全 0 命中；`webRemoteControl` 4 命中全是设置字段名如 `webRemoteControlExternalRelayDevice` @ 222,868。）

**Zod 核心与工厂函数**（压缩符号名 → 语义，跨 chunk 通用）：

| 符号 | 偏移 | 语义 | 原文片段 |
| --- | --- | --- | --- |
| `w` | 90,145 | `ZodNumber` | `function w(e){return ma(cc,e)}` |
| `g` | ~89,9xx | 类型定义器（`$constructor`） | `g(\`ZodNumberFormat\`,(e,t)=>{…})` |
| `hc` | **90,638** | `ZodNull`（**不是常量对象**） | `function hc(e){return ya(mc,e)}` |
| `E` | 90,889 | `ZodUnknown` | `function E(){return xa(vc)}` |
| `T` | 90,273 | `ZodBoolean` | `var dc=g(\`ZodBoolean\`,…)` |
| `D` | 91,680 | `ZodArray` | `function D(e,t){return Wa(Cc,e,t)}` |
| `O` | **92,512** | `ZodObject`（**默认非 strict**） | `function O(e,t){return new wc({type:\`object\`,shape:e??{},…})}` |
| `Tc` | 92,579 | `ZodObject` + `catchall:never`（= strict 简写） | `function Tc(e,t){return new wc({type:\`object\`,shape:e,catchall:bc(),…})}` |
| `k` | **92,855** | `ZodUnion` | `function k(e,t){return new Dc({type:\`union\`,options:e,…})}` |
| `A` | 92,988 | `ZodDiscriminatedUnion(discriminator, options)` | `function A(e,t,n){return new Oc({type:\`union\`,options:t,discriminator:e,…})}` |

**协议常量对象 `Uu` @ 117,018**（原文，节选）：

```js
Uu={maxFrameBytes:1024*1024, logicalFrameAssemblyMaxBytes:16*1024*1024,
    logicalFrameAssemblyMaxFragments:1024, logicalFrameAssemblyMaxConcurrent:32,
    logicalFrameAssemblyMaxStagedBytes:32*1024*1024, logicalFrameAssemblyTimeoutMs:3e4,
    transportEnvelopeIdMaxChars:256, subscriberBufferMaxOps:500,
    subscriberBufferMaxBytes:1024*1024, eventRetentionPerSession:2e3,
    snapshotTailWindowRows:60, rowsRangeMaxLimit:200,
    toolOutputFinalHeadBytes:32*1024, toolOutputFinalTailBytes:32*1024,
    goalVerificationsRetained:20, pendingCommandsDisplayMax:32,
    commandPendingTtlMs:1440*60*1e3, idempotencyTablePerSession:5…
```

**物理帧上限 `bd` @ 128,548**：

```js
var bd={maxPhysicalFrameBytes:Uu.maxFrameBytes, maxMessageBytes:16*1024*1024,
        maxFragments:64, assemblyTimeoutMs:3e4, transportIdMaxChars:Uu.transportEnvelopeIdMaxChars}
```

**通道 RPC 帧 schema**（`rpc-frame` 是**通道层**帧，与业务帧同走 `zcode_type`）：

| schema | 偏移 | 关键字段 |
| --- | --- | --- |
| `Dd` = `rpc-frame` | **129,164** | `bridgeSessionId, bridgeGeneration?, recoveryId?, seq, messageSeq, fragmentIndex, fragmentCount, messageBytes, checksum{algorithm:'crc32',value:/^[0-9a-f]{8}$/}, dataBase64`（`.strict()` + `superRefine`：`fragmentIndex<fragmentCount`） |
| `Od` = `rpc-frame-ack` | 129,9xx | `bridgeSessionId, bridgeGeneration?, recoveryId?, ackMessageSeq` |
| `kd = k([Dd, Od])` | 129,993 | 通道帧判别联合 |
| 信封 | 130,010 | `O({type:N('data'), payload:kd, client_ts?, server_ts?}).strict()` |

**业务帧判别联合 `uS = k([…])` @ 284,618 – 287,155**，共 **19 个 distinct `zcode_type`**（21 条 schema，`platform-response` 与 `workspace-reconnect-response` 各出现两次以区分 success）：

```
telemetry-report, bootstrap-request, bootstrap-response,
workspace-list-request, workspace-list-response, workspace-list-updated,
workspace-bridge-open, workspace-bridge-ready, workspace-bridge-error,
workspace-reconnect-request, workspace-reconnect-response(×2),
mobile-view-state-update, platform-request, platform-response(×2),
bridge-degraded, app-error, mobile-diagnostic
```

`bridge-degraded` 的 `reason` 枚举 @ 286,155：`['rpc-transport-fault','rpc-frame-gap','buffer-overflow','buffer-timeout']`。
`mobile-diagnostic` 的 `event` 枚举 @ 286,611：`['state-transition','socket-close','socket-error','recover-start','recover-scheduled','pair-status','failure']`。

`zcode_type:N(` 在 validation 中共 **21** 处；`zcode_type` 共 **22** 处（第 22 处是 131,123 的引用）。

> **validation chunk 尾部（305,622 – 307,369）是 export 映射表**，形如 `export{…,Uu as Rn,…,hc as fr,…}`。查"某符号是什么"先看这里。

### 3.3 主包里的 schema 相关代码是什么

主包 `import` validation（@ 主包 61,541 的 from 子句）：

```js
import{…,Rn as W,…}from"./validation-CqXZOLXO.js"
```

⇒ 主包局部名 `W` = validation 导出 `Rn` = 常量对象 `Uu`。**验证方法**：

```bash
node -e "const m=require('fs').readFileSync('index-BGa3KiRe.js').toString('latin1');
const i=m.indexOf('from\"./validation-CqXZOLXO.js\"');
console.log(m.slice(m.lastIndexOf('import{',i),i).match(/,Rn as (\w+)/)[1])"   # → W
```

主包对常量的全部消费点只有 2 处（`transportEnvelopeIdMaxChars` 等字段在主包 **0 命中**）：

- `W.maxFrameBytes` @ **1,105,186**（`if(w1e(e,t)>W.maxFrameBytes)throw Error(\`proto.frameTooLarge\`)`）
- `W.snapshotTailWindowRows` @ **1,136,539**（`async loadOlder(e=W.snapshotTailWindowRows)`）

主包里的 **30 处 `zcode_type`** 全部是**判别读取/构造**（relay 客户端与业务发送侧），**没有一处是 schema 定义**。
代表：`u4n(e)` @ 6,473,803、`processInbound` @ 6,479,436、`zcode_type:\`mobile-view-state-update\`` @ 6,579,881、`zcode_type:\`workspace-bridge-open\`` @ 6,580,406。

### 3.4 `parts-DcYGwy2q.js`（57,142 B）——第二个 schema 落点

它**自己声明**了一部分 schema（对话/rowsRange 子集），Zod 核心则从 validation `import`：

| parts 局部名 | validation 导出 | validation 内部符号 | 语义 |
| --- | --- | --- | --- |
| `v` | `Tr` | `O` | ZodObject（用 `.strict()` 补严） |
| `w` | `hr` | `D` | ZodArray |
| `O` | `wr` | `w` | ZodNumber |
| `S` | `dr` | `M` | ZodEnum |
| `C` | `gr` | `T` | ZodBoolean |
| `n` | `Ar` | `S` | ZodString |
| `_` | `Rn` | `Uu` | **协议常量对象** |

关键坐标：`rowsRangeMaxLimit` 用法 @ 15,600 ｜ `v4/conversation/frame`（**传输预算估算器的 RPC 方法名**）@ 23,820 ｜ `zcode_type:\`rpc-frame\`` @ 24,025。

原文 @ 23,790（`ln(e)`，旧文档称之为"预算口径 113,335"的那段逻辑，**新构建落点在 parts 而非主包**）：

```js
function ln(e){let t=un({method:`v4/conversation/frame`,params:e})+1,n=un(e),…,
  c=`x`.repeat(_.transportEnvelopeIdMaxChars),
  l=un({type:`data`,payload:{zcode_type:`rpc-frame`,bridgeSessionId:c,bridgeGeneration:2**53-1,
     recoveryId:c,seq:2**53-1,dataBase64:``},client_ts:2**53-1,server_ts:2**53-1})+s;
  return{cliNdjsonBytes:t,channelSocketBytes:o,mobileRelayBytes:l,maxBytes:Math.max(t,o,l)}}
```

### 3.5 `src-AL-_ONNp.js`（82,282 B）

markdown / artifact 渲染辅助（`zcode-artifact://` 图片正则 @ 25,375）+ **relay 连接参数解析 `Bd` @ 62,900** + **路径匹配器 `zd` @ 62,970**（导出名 `E`，主包绑定为 `Tn`）。
`Bd(e)` 解析的 relay 参数：`sid`、`hash`、`t`（时间戳）、`theme`、`mid`、`name`、`app_version`。

### 3.6 判别方法（给下一个审计者，照做即可）

```bash
cd E:/Zcode_android/Reference_web/3.15.3/assets

# ① schema 只可能落在这三个文件（全 2,489 个 .js 实测）
grep -l 'zcode_type' *.js        # → index-BGa3KiRe.js, parts-DcYGwy2q.js, validation-CqXZOLXO.js

# ② 判断是"schema 定义"还是"帧构造/判别读取"
grep -c 'zcode_type:N(' validation-CqXZOLXO.js   # 21  → 定义
grep -c 'zcode_type:N(' index-BGa3KiRe.js        # 0   → 主包无定义

# ③ 协议常量：在 validation 找 Uu，看消费方绑定名
node -e "const b=require('fs').readFileSync('validation-CqXZOLXO.js');console.log(b.indexOf('Uu={'))"  # 117018
node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');console.log(b.indexOf('W.maxFrameBytes'))" # 1105186

# ④ i18n 文案不在 validation，在 IntlProvider chunk
grep -l 'webRemoteControl.mobileShell.backHome' *.js   # → IntlProvider-C-26GNLk.js
```

**四条铁律**：
1. 找 **schema 定义** → `validation-*.js`（或 `parts-*.js` 的对话子集）；
2. 找 **帧构造/判别读取/状态机** → 主包；
3. 找 **协议常量数值** → `validation-*.js` 的 `Uu`（别在主包搜 `transportEnvelopeIdMaxChars`，0 命中）；
4. 找 **UI / 文案** → 主包 / `IntlProvider-*.js`。

---

## 4. 模块地图（功能域 → 文件 → 符号 → grep 锚点）

> 所有偏移为 **UTF-8 字节偏移**。主包 = `assets/index-BGa3KiRe.js`；V = `assets/validation-CqXZOLXO.js`；P = `assets/parts-DcYGwy2q.js`；S = `assets/src-AL-_ONNp.js`。

### 4.1 relay / 连接（主包 6,473,xxx – 6,587,xxx，单点聚集区）

| 关注点 | 文件 | 偏移 | 符号 / 原文片段 |
| --- | --- | --- | --- |
| 通道帧判别 | 主包 | 6,473,803 | `function u4n(e){…let t=e.zcode_type;return t===\`rpc-frame\`\|\|t===\`rpc-frame-ack\`}` |
| 拥塞水位 | 主包 | 6,473,9xx | `_9.saturationHighWaterMarkBytes` / `saturationLowWaterMarkBytes` / `replayBufferMaxBytes`（`f4n` 校验 low ≤ high） |
| 逻辑帧组装 + 退化管理 | 主包 | 6,476,379 – 6,481,470 | `enterDegraded`（**12 处**）；`this.assembler.accept(t,…)`；`queuedInbound`；`unacknowledgedByteCount` |
| 入站分发 | 主包 | **6,479,436** | `processInbound(e){let t=St(e);if(!t){this.enterDegraded({reasonCode:\`remote.rpcFrame.invalidPayload\`,terminal:!0});return}…}` |
| ack 处理 | 主包 | 6,479,6xx | `t.zcode_type===\`rpc-frame-ack\`` → `this.processAck(t.ackMessageSeq)` |
| relay 信封分发 | 主包 | 6,526,600 | `case\`auth_ack\`:…case\`pair_status_ack\`:…case\`data\`:this.handleDataPayload(t.payload);case\`error\`:this.handleRelayError(t.code,t.message)` |
| relay 错误 | 主包 | **6,526,742 / 6,527,764** | `handleRelayError` |
| 心跳看门狗 | 主包 | 6,526,700 区 | `armHeartbeatAckWatchdog()` / `lastPairStatusAckAt` / `emitDiagnostic({type:'pair-status'…})` |
| 入站分发器（桌面端同构物） | app.asar `out/main/index.js` | 字节 405,759 | `s(IT,"routePayload")`（**仅作交叉校验，不在本文档展开**） |
| 接受的 URL 参数 | 主包 | 6,493,245 | `N4n=new Set([\`remoteControlToken\`,\`relayOrigin\`])` |
| relay 引导（token 模式） | 主包 | **6,584,804** | `G6n(e)` → `N6n` @ 6,564,181 `fetch(\`${e.relayOrigin.replace(/\/$/,``)}/api/remote-control/windows/bootstrap/${e.token}\`)` |
| 移动视图状态上报 | 主包 | **6,557,921** | `A6n(e,t,n)`：`POST {relayOrigin}/api/remote-control/windows/{token}/mobile-view-state`，头 `X-ZCode-Mobile-Connection-Id: {mobileConnectionId}` |
| 连接会话标识 | 主包 | 6,564,666 / 6,585,095 | `windowControlSessionId`、`mobileConnectionId` |
| relay 参数解析 | S | **62,900** | `Bd(e)`：`sid`/`hash`/`t`/`theme`/`mid`/`name`/`app_version` |

### 4.2 通道 RPC（主包 1,087,xxx – 1,117,xxx）

| 关注点 | 文件 | 偏移 | 说明 |
| --- | --- | --- | --- |
| v4 RPC 服务门面 | 主包 | **1,087,900** | `function J$e(e){let{agentService:t,ensureHandshake:n,workspace:r}=e;return{async workflowRunEvents(e){return await n(),t.conversationWorkflowRunEventsV4({...r,sessionId:e.sessionId,…})},…}}` |
| RPC 方法名总数 | 主包 | 全文 | **31 个 distinct `*V4(` 方法名 / 43 个调用点**（见下表） |
| 通道帧编码预算 | 主包 | **1,105,155** | `Mw(e,t){if(w1e(e,t)>W.maxFrameBytes)throw Error(\`proto.frameTooLarge\`)}` |
| 订阅帧暂存器 | 主包 | **1,089,460** | `$$e(e)`：`topic → {subscriptionId, frames[], stagedBytes, overflowReason}`；溢出码 `fault.subscription.initialFrameStagingOverflow` @ 1,089,334；常量 `Y$e=1024` @ 1,089,303、`X$e=32*1024*1024` |
| 帧预算估算器 | **P** | **23,820** | `ln(e)` → `{cliNdjsonBytes, channelSocketBytes, mobileRelayBytes, maxBytes}` |
| 通道帧 schema | **V** | 129,164 / 129,9xx / 130,0xx | `rpc-frame` / `rpc-frame-ack` / `kd=k([…])` |

**31 个 `*V4(` RPC 方法名（网页端调用点实测，`\w+V4\(` 全扫）**：

```
附件(6)     attachmentAbortV4, attachmentBeginV4, attachmentChunkV4,
            attachmentCommitV4, attachmentPreviewSourceV4, attachmentReadV4
会话(7)     helloConversationV4, initializeConversationV4, subscribeConversationV4,
            unsubscribeConversationV4, resyncConversationV4, sendConversationCommandV4,
            queryConversationCommandsV4
分页/差异(4) conversationRowsRangeV4, conversationFileChangesV4,
            conversationFileRewindPreviewV4, conversationPlansV4
工作流(8)   conversationWorkflowRunEventsV4, conversationWorkflowRunsV4,
            conversationWorkflowRunArtifactsV4, conversationWorkflowRunArtifactDataV4,
            conversationWorkflowRunArtifactReadV4, conversationWorkflowRunWorkspaceV4,
            conversationWorkflowRunNodeResultV4, backgroundBashOutputV4
controller(3) subscribeControllerV4, unsubscribeControllerV4, resyncControllerV4
索引(3)     subscribeSessionsIndexV4, unsubscribeSessionsIndexV4, resyncSessionsIndexV4
            —— 合计 31 ——
```

> **这些方法名的定义体不在网页产物里**：它们只在调用点出现（`agentService.<method>V4(...)`），
> 名字集合是**网页端与桌面宿主之间的契约**。桌面端实现在 `app.asar`（见第二批文档）。

### 4.3 对话（会话 / 增量 / 分页 / 虚拟列表）

| 关注点 | 文件 | 偏移 | 符号 / 原文片段 |
| --- | --- | --- | --- |
| 会话握手 | 主包 | **1,087,556 / 1,087,672** | `helloConversationV4()` → `initializeConversationV4({kind:\`clientHello\`,protocolVersion:3,clientId:q$e(),clientKind:t.clientMode===\`desktop-continuous\`?\`desktop\`:\`web\`,appVersion:\`unknown\`,capabilities:n})` |
| 订阅（带 ack） | 主包 | **1,108,234** | `await e.subscribeConversationV4({...n,sessionId:r,...t.base?{base:t.base}:{},...t.visibility?{visibility:t.visibility}:{}})` → `d.bind(o,c.ack.subscriptionId)` @ 1,108,810 |
| 取消订阅 | 主包 | 1,108,719 / 1,110,505 | `unsubscribeConversationV4({...n,subscriptionId:c.ack.subscriptionId})` |
| 未知 topic 报错 | 主包 | **1,107,991** | `return Promise.reject(Error(\`Unsupported v4 topic: ${t.topic}\`))`（topic 校验器 `ls` 绑定名，**其定义未在本次扫描中定位 → 未证实**） |
| 增量/分页拉取 | 主包 | **1,136,525** | `async loadOlder(e=W.snapshotTailWindowRows){…let r=t.rows.window[0]?.rowId;…await this.transport.rowsRange({sessionId:n,beforeRowId:r,limit:e})…}` |
| 分页上限 | **V** | 117,018（`Uu`） | `rowsRangeMaxLimit:200`；schema 侧 `limit:O().min(1).max(_.rowsRangeMaxLimit)` @ **P 15,500 区** |
| 客户端模式枚举 | 主包 | 1,087,777 | `\`desktop-continuous\`` / `\`web-remote-replayable\`` |
| 虚拟列表 | 主包 | **576,846 – 650,193**（34 处） | `virtualizer`；buffer 元素 `this.bufferBefore.dataset.virtualizerBuffer=\`before\`` @ 577,2xx |
| 时间线 DOM 契约 | 主包 | **3,470,268** | `"data-v4-timeline-scroll":\`true\``，同处还有 `data-v4-timeline-scroll-locked`、`data-markdown-table-layout-root`(3,470,351)、`data-row-count`(3,470,392)、`data-window-row-count`(3,470,418)、`data-total-row-count`(3,470,486)、`data-following`(3,470,511)、`data-loading-older`(3,470,546) |
| 实时尾随 | 主包 | **3,472,878** | `data-v4-running-live-tail` |
| 加载动画 | 主包 | **2,781,020** | `data-zcode-chat-loading-animate` |
| 会话恢复槽位 | 主包 | **3,724,081** | 真实键 `zcode-v4-last-session:v1:`（`uun` @ 3,724,081；`C$(e)` → `${uun}${e}`；读写 `dun`/`fun`）。旧锚点 `zcode-v4-last-session` 仍可 grep 到，但**键名已带 `:v1:` 后缀** |
| 会话恢复守卫 | 主包 | 3,724,2xx | `pun(e){return e.enabled&&e.rendererReload&&e.activeSessionId===null&&e.draftFocusVersion===0}` |

### 4.4 附件上传（主包 1,105,xxx）

| 关注点 | 文件 | 偏移 | 原文 |
| --- | --- | --- | --- |
| 单帧上限 | 主包 | 1,105,186 | `W.maxFrameBytes` |
| 上传限额 | 主包 | **1,105,308** | `W.attachmentMaxBytes`（另有前端展示限额 `KAt=Math.min(S,W.attachmentMaxBytes)` @ 2,102,658） |
| 帧过大错误 | 主包 | **1,105,213** | `Error(\`proto.frameTooLarge\`)` |
| 上传主流程 | 主包 | **1,105,4xx** | `async function T1e(e,t,n,r={}){…}` |
| 校验和 | 主包 | 1,105,1xx | `sha256:${[…new Uint8Array(t)].map(e=>e.toString(16).padStart(2,\`0\`)).join(\`\`)}`（`crypto.subtle.digest('SHA-256')`） |
| uploadId | 主包 | 1,105,1xx | `upload-${globalThis.crypto.randomUUID()}`（降级：4×`getRandomValues(Uint32Array(4))`） |
| 开始上传 | 主包 | **1,105,633** | `Mw(\`attachmentBeginV4\`,l)` → `await e.attachmentBeginV4(l)`；`t.state===\`committed\`` 直接短路返回 `{ref:t.ref}` |
| 分块 | 主包 | **1,106,190** | `Mw(\`attachmentChunkV4\`,i)` → `await e.attachmentChunkV4(i)`；校验 `nextChunkIndex===n+1`，否则 `fault.attachment.invalidServerProgress` |
| 提交 | 主包 | **1,106,544** | `Mw(\`attachmentCommitV4\`,n)` → `await e.attachmentCommitV4(n)` |
| 进度回调 | 主包 | 1,105,8xx | `onProgress?.({phase:\`uploading\`/\`committing\`, uploadedBytes, totalBytes})` |
| 草稿存储 | 主包 | **1,760,022** | 真实键 `zcode-v4-composer-drafts:v1:`（`qvt`）；`TP(e,t)` 用 `encodeURIComponent` 拼 workspace；空草稿哨兵 `CP=\`__draft__\`` |
| 草稿告警标签 | 主包 | 1,760,086 | `[v4-composer-draft]`（**这是日志标签，不是 localStorage 键**——旧文档把两者混为一谈） |
| 编辑器状态 | 主包 | **1,761,385** | `editorStateJson`（11 处，范围 1,761,385 – 2,306,076） |

### 4.5 导航 / 工作区（主包 4,446,xxx / 6,579,xxx）

| 关注点 | 文件 | 偏移 | 原文 |
| --- | --- | --- | --- |
| 移动页判据 | 主包 | **4,446,176 / 4,446,287** | `zcodeMobilePage`；`Z0(e){return typeof e===\`object\`&&!!e&&e.zcodeMobilePage===\`chat\`}` @ 4,446,5xx；`jbn()` → `history.pushState({zcodeMobilePage:\`chat\`},\`\`)`；`Mbn(e)` → `'chat'\|'home'` |
| 对话页 DOM | 主包 | **4,448,306** | `"data-mobile-page":\`chat\`` |
| 工作区列表请求 | 主包 | **6,579,686** | `{zcode_type:\`workspace-list-request\`,requestId:e}`，等待 `workspace-list-response && requestId===e` |
| 视图状态上报 | 主包 | **6,579,881** | `j.sendPayload({zcode_type:\`mobile-view-state-update\`,viewState:{activeWorkspaceKey:e,…activeTaskId,…updatedAt:Date.now()},deviceInfo:o6n({appVersion:t.appVersion})})` |
| 重连 | 主包 | 6,580,1xx | `zcode_type:\`workspace-reconnect-request\``，等 `workspace-reconnect-response && requestId===t && workspaceKey===e` |
| 桥接打开 | 主包 | **6,580,406** | `N({zcode_type:\`workspace-bridge-open\`,requestId:X9(\`workspace-bridge\`),bridgeSessionId:n,bridgeGeneration:r,…l?{recoveryId:l}:{},workspaceKey:e,…t?.taskId?{taskId:t.taskId}:{}},…)` |
| 工作区切换面 | 主包 | 6,586,1xx | `webRemoteControlWorkspaceSwitcher:{listWorkspaces,switchWorkspace,markTaskRead,startDraft,updateMobileViewState}` |
| 打开工作区 | 主包 | 6,564,9xx | `k6n(p,e,t)` → `r4n(wsUrl,{onOpenSocket,onClose})` → `u6n({bridge,services})` |
| 主题面（**已移入主包**） | 主包 | **707,537** | `aFe=\`data-zcode-browser-theme-surface\``；同步器 `cFe(e)` @ 707,8xx（`t.setAttribute(aFe,e); t.style.colorScheme=e`）；`oFe()` 读 `prefers-color-scheme`；`e_(e)` 归一；`t_(e)` 映射 `dark→zai-dark` |
| 导航列表（旧在 catalogTree chunk） | 主包 | **3,470,511**（另有 2,916,130 / 5,551,025） | `data-following` 3 处；`catalogTree-*.js` **在新快照中不存在** |

### 4.6 i18n / 主题

| 关注点 | 文件 | 偏移 | 原文 |
| --- | --- | --- | --- |
| 语言注入 + 事件 | 主包 | **1,802,480 / 1,804,408** | `window.__zcodeLang__ = ${JSON.stringify(t)}` |
| 语言变更事件名 | 主包 | **1,804,486** | `window.dispatchEvent(new CustomEvent("zcode-coding-plan-lang-change",{detail:${JSON.stringify({locale:t})}}))` |
| 注入配套键 | 主包 | 1,802,215 | `localStorage.setItem("zcode-theme", zcodeTheme)` + `localStorage.setItem("zcode:coding-plan:embedded","app")` |
| 主题键全量落点 | 主包 | 1,190,680 / 1,802,215 / 6,519,103 / 6,537,099 / 6,537,179 / 6,552,838 | `zcode-theme`（7 处） |
| 首屏主题（无 JS 依赖） | `index.html` | 内联脚本 | 读 `zcode-theme` → 写 `data-zcode-bootstrap-theme` + `data-zcode-browser-theme-surface`；CSS 变量 `--zcode-bootstrap-bg/-fg/-border/-card` |
| i18n 目录 | **`IntlProvider-C-26GNLk.js`** | 855,577 B | `webRemoteControl.mobileShell.backHome` @ 114,330 / 538,444；`zh-CN` @ 146,024；`zai-dark` @ 130,537；`export{` @ 855,513 |
| 主题面 CSS 覆盖 | `index.html` 内联样式 | — | `html[data-zcode-browser-theme-surface]{background:var(--color-background,…)!important}`（覆盖 Electron vibrancy 透明根背景） |
| 壳端滚动条 | `index-BD2BL4Cc.css` | 52,330 / 52,425 / 325,039 / 423,402 / 423,445 | `::-webkit-scrollbar`（10 处）；`bg-background-win` @ 105,460（1 处） |

> CSS（455,351 B）**不含** `data-zcode-browser-theme-surface`、`data-v4-timeline-scroll`、`data-mobile-page`、`.zcode-boot-loading`（全 0 命中）——这些属性/类的样式分别来自 `index.html` 内联样式或 Tailwind 原子类，**不要用 CSS 里搜不到就下结论说该属性不存在**。

### 4.7 Zod schema 校验层（权威落点）

| 层 | 文件 | 偏移 | 内容 |
| --- | --- | --- | --- |
| Zod 运行时核心 | V | 0 – ~90,000 | `$constructor`、`check`、`safeParse`（84748/84800/85169/85211/85275）、`processJSONSchema` |
| 类型工厂 | V | 90,145 – ~93,500 | `w`/`O`/`k`/`A`/`D`/`T`/`M`/`S`/`hc`/`E`/`Tc` |
| 协议常量 | V | **117,018** | `Uu`（导出 `Rn`） |
| 物理帧上限 | V | **128,548** | `bd` |
| 通道帧 schema | V | **129,164** | `Dd`(rpc-frame) / `Od`(rpc-frame-ack) / `kd` |
| 业务帧判别联合 | V | **284,618 – 287,155** | `uS=k([…])`，19 个 `zcode_type` |
| 设置/会话/工作区 schema | V | ~200,000 – 305,000 | 例：`webRemoteControlExternalRelayDevice` @ 222,868；`autoDownloadAndInstallUpdates` @ 223,0xx |
| 对话子集（次级落点） | **P** | 15,500 – 32,100 | `v({…}).strict()` 自声明 rowsRange / conversation frame 相关 |
| 消费侧常量绑定 | 主包 / P | 1,105,186 / 23,790 | 主包 `W`、parts `_` |

**统计**：validation 内 `safeParse` **18** 处；`superRefine` **22** 处（84,071 / 99,253 / 120,661 / **129,547** / 147,682 …）；`discriminatedUnion` 字面量 **0 命中**（打包后走 `A()` 工厂）。

### 4.8 可观测面 / 扩展面

| 关注点 | 文件 | 偏移 | 原文 |
| --- | --- | --- | --- |
| 开发工具开关 | 主包 | **5,649,954** | `JVn=[\`zcode:developer-tools:enabled\`,\`zcode:token-debug:enabled\`]`（`token-debug` @ 5,649,986）；禁用词表 `YVn=new Set([\`0\`,\`false\`,\`off\`,\`no\`])` |
| E2E 轨迹钩子 | 主包 | **1,389,925** | `__zcodeModelTrajectoryStoreE2E` |
| 自定义事件 | 主包 | 27 处（153,883 起） | `CustomEvent` |
| **`window.zcode`** | — | — | **全主包 0 命中**；`globalThis.zcode` 亦 0 命中 ⇒ 旧文档"扩展面 `window.zcode`"这条锚点在新构建的网页产物里**已失效** |
| 遥测帧 | V | 284,641 | `telemetry-report` |

---

## 5. 「我该去哪找什么」速查表

| 我要找的问题类型 | 先查哪个文件 | 用什么关键字 | 参考篇 |
| --- | --- | --- | --- |
| 入口/路由判定、`/remote/v4` 匹配 | 主包 | `K6n` / `L6n` / `W6n` / `VITE_WEB_REMOTE_CONTROL_ROUTE_PATH`（1,086,060 起 8 处） | 15（本篇） |
| 资源前缀 / BASE_URL / env | 主包 | `BASE_URL:`（1,085,990 起）、`MODE:\`production\``（7 处） | 15 |
| `app_version` 版本闸 | 主包 | `desktopAppVersion`（6,549,4xx）、`remote-version-title` | 15 / 24 |
| relay 连接、握手、退化 | 主包 | `processInbound` / `enterDegraded` / `handleRelayError` / `saturationHighWaterMarkBytes` | 16 |
| 业务帧类型全集 | **V** | `uS=k([`（284,618）、`zcode_type:N(` | **16** |
| 通道帧（分片/校验和/ack） | **V** | `rpc-frame`（129,164）、`maxPhysicalFrameBytes`（128,548） | 16 |
| 协议常量数值（帧上限/窗口/限额） | **V** | `Uu={`（117,018） | 16 |
| 传输预算（cliNdjson/channelSocket/mobileRelay） | **P** | `cliNdjsonBytes`（23,8xx） | 16 |
| bootstrap / workspace-list 载荷 | **V** | `bootstrap-request`（284,697）、`workspace-list-updated`（284,961）、`workspace-bridge-open`（285,015） | **14 / 17 / 13** |
| 通道 RPC 方法名全集 | 主包 | `V4(`（31 个方法名，1,087,9xx–1,117,xxx） | 16 |
| 对话订阅 / ack / 取消 | 主包 | `subscribeConversationV4`（1,108,234）、`helloConversationV4`（1,087,556） | 16 |
| 分页拉旧（rowsRange） | 主包 | `loadOlder`（1,136,525）、`beforeRowId`、`rowsRangeMaxLimit` | 18 |
| 虚拟列表 / 时间线 DOM 契约 | 主包 | `data-v4-timeline-scroll`（3,470,268）、`virtualizer`（576,846）、`data-row-count`（3,470,392） | **18** |
| 附件上传三段式 | 主包 | `attachmentBeginV4`（1,105,633）/`attachmentChunkV4`（1,106,190）/`attachmentCommitV4`（1,106,544） | **19** |
| 草稿持久化 | 主包 | `zcode-v4-composer-drafts:v1:`（1,760,022） | 19 |
| 编辑器状态 | 主包 | `editorStateJson`（1,761,385） | 19 |
| 导航/工作区切换 | 主包 | `workspace-bridge-open`（6,580,406）、`mobile-view-state-update`（6,579,881）、`data-mobile-page`（4,448,306） | 20 |
| 主题面属性 | 主包 | `data-zcode-browser-theme-surface`（**707,537**） | **20 / 22** |
| i18n 文案 / locale | `IntlProvider-C-26GNLk.js` | `webRemoteControl.`、`zh-CN` | 20 |
| 语言切换事件 | 主包 | `zcode-coding-plan-lang-change`（1,804,486）、`__zcodeLang__` | 20 |
| 回前台恢复 / 会话槽位 | 主包 | `zcode-v4-last-session:v1:`（3,724,081）、`zcodeMobilePage`（4,446,176） | **21 / 24** |
| 壳端注入点（滚动条/主题底） | `index-BD2BL4Cc.css` + `index.html` | `::-webkit-scrollbar`、`bg-background-win`、`data-zcode-bootstrap-theme` | 22 |
| 开发工具/E2E 钩子 | 主包 | `zcode:developer-tools:enabled`（5,649,954）、`__zcodeModelTrajectoryStoreE2E`（1,389,925） | **10** |
| 判断某资源是否存在 | 主包 | `__vite__mapDeps`（偏移 **6**，1,829 条） | 15 |

---

## 6. 怎么找代码（方法，写给下一个审计者）

1. **先断言基线**：`sha256sum assets/index-BGa3KiRe.js` 应为 `ce2e2fc490d6364d21630e9f510b99f48748958e392724c0c9faf8fb5f9b88fd`；`index.html` 应为 `4ca1a8b07de46f2ba698dcfee4c49dc9f76566440f2437146d832ad386d322f2`。
2. **必须用字节偏移**：`node -e "const b=require('fs').readFileSync(F);console.log(b.indexOf('锚点'))"`。`grep -b` 也给字节偏移，但主包单行、`grep` 会整行输出，**只能配 `-o` 用**。
3. **先定 chunk 再定偏移**：`grep -l '字面量' assets/*.js`。经验：功能/运行时锚点基本都在主包，**唯一成规模的例外是协议 schema → `validation-*.js`（+ `parts-*.js` 子集）**。
4. **判断资源存在性用 `__vite__mapDeps`**（主包偏移 6，数组字面量 52–60,343，1,829 条），**不要**用"grep 没搜到"下结论。
5. **压缩符号名必须连上下文给**：同名符号在不同 chunk 含义不同（例：`O` 在 validation 是 ZodObject 工厂，在 parts 是 ZodNumber 工厂）。
6. **别用跨行正则**：主包是压缩单行，跨行正则必然失配。
7. **`import{…}from"./validation-CqXZOLXO.js"` 的映射是解压缩符号名的钥匙**：主包 @ 61,541 的 from 子句 + validation 尾部 @ 306,5xx 的 export 表，两表对照即可还原语义。

---

## 7. 复现

```bash
cd E:/Zcode_android/Reference_web/3.15.3

# 规模与哈希
sha256sum index.html assets/index-BGa3KiRe.js assets/validation-CqXZOLXO.js assets/index-BD2BL4Cc.css
ls assets/*.js | wc -l                 # 2489
du -sb assets                          # 目录字节

# manifest 归档规模
node -e "const m=require('./_manifest.json');console.log(m.deploymentVersion,m.assetPrefix,m.fileCount,m.totalBytes,m.failures.length)"

# 资源清单（构建期全量）
node -e "const b=require('fs').readFileSync('assets/index-BGa3KiRe.js').toString('latin1');
const s=b.indexOf('m.f=[');const e=b.indexOf(']))=>i.map',s);
console.log('条目',(b.slice(s,e).match(/\"assets\//g)||[]).length,'跨度',e-s)"

# 环境对象内联处数
grep -o -b 'MODE:`production`' assets/index-BGa3KiRe.js | wc -l   # 7

# 某锚点落在主包还是 chunk
grep -l 'data-zcode-browser-theme-surface' assets/*.js           # → 仅 index-BGa3KiRe.js
grep -l 'zcode_type' assets/*.js                                 # → index / parts / validation

# 帧判别联合成员
node -e "const s=require('fs').readFileSync('assets/validation-CqXZOLXO.js').toString('latin1');
const st=s.indexOf('uS=k([');console.log([...s.slice(st,st+6000).matchAll(/zcode_type:N\(\`([^\`]+)\`\)/g)].map(m=>m[1]).join(', '))"
```

---

## 8. 旧基线锚点失效清单（2026-09-20 → 3.15.3）

| 旧结论 | 新构建实测 | 判定 |
| --- | --- | --- |
| 主包 `index-B-ilXaCQ.js` 4,821,509 B | 主包 `index-BGa3KiRe.js` 6,592,643 B | **文件名与偏移全废** |
| schema `src-wmk2orCZ.js` 285,095 B | `validation-CqXZOLXO.js` 307,369 B（+ `parts-DcYGwy2q.js` 子集） | **文件名废；且不再"单一 schema 文件"** |
| 样式 `index-DR0mqKFV.css` 392,369 B | `index-BD2BL4Cc.css` 455,351 B | **文件名废** |
| "协议常量对象 `hc`" | `hc` = **ZodNull 工厂**（V 90,638）；常量对象是 **`Uu`**（V 117,018，导出 `Rn`） | **旧命名错误，已纠正** |
| 环境对象"四处重复内联" | **7 处**内联（判据 `MODE:\`production\``） | **数字废** |
| `BASE_URL = /remote/v4/latest/` | `BASE_URL = /remote/v4/3.15.3/` | **值已变（版本固定）** |
| `data-zcode-browser-theme-surface` **仅**在 `catalogTree-DmoX4pSX.js` | 在**主包 707,537**；`catalogTree-*.js` 不存在 | **旧结论反转** |
| `__vite__mapDeps` 约 73,575 字符 / 1,757 条 | 60,291 B / **1,829 条** | **数字废** |
| 草稿键 `zcode-v4-composer-draft` | 真实键 `zcode-v4-composer-drafts:v1:`（主包 1,760,022） | **键名已变；旧字符串只剩日志标签** |
| 会话槽位 `zcode-v4-last-session` | 真实键 `zcode-v4-last-session:v1:`（主包 3,724,081） | **键名加版本后缀** |
| 扩展面 `window.zcode` | 主包 0 命中 | **失效（未找到替代）** |
| 预算口径在主包 113,335 | 落到 **`parts-DcYGwy2q.js` 23,790** | **落点变更** |
| 所有字符偏移（旧文档口径） | 本文档口径为**字节偏移**，数值全不可比 | **全废** |

### 未能证实的点

- **对话 topic 字面量集合**：主包 1,107,991 只出现 `Unsupported v4 topic: ${t.topic}`；topic 校验器 `ls` 的**定义未定位**，topic 枚举值未取到。
- **`zcode:web-remote-control:bootstrap-shell` 的写入方**：全部 2,489 个 `.js` + 2 个 `.css` 中 0 命中，只在 `index.html` 内联脚本里被读+删。
- **`window.zcode` 的替代扩展面**：主包无该绑定，也未找到等价的宿主注入命名空间。
- **`data-zcode-browser-theme-surface` 的旧 `catalogTree` 承载物**：新构建该 chunk 消失，其职能是否已完全并入主包，未逐项核对。

---

## 9. 出处

- 快照：`E:\Zcode_android\Reference_web\3.15.3\`（`_manifest.json` / `_manifest.md` / `assets/` / `index.html`；只读）
- 抓取：2026-10-15 19:52 (+08:00)，`entryUrl = https://zcode.z.ai/remote/v4?app_version=3.15.3`
- 桌面端交叉校验（仅一处，不展开）：`app.asar` → `out/main/index.js` 字节 405,759 `s(IT,"routePayload")`；`out/metadata/build-meta.json` 记 `appVersion 3.15.3`
- 相关篇目：[16 relay 协议目录](16-remote-v4-relay协议目录.md) ·
  [14 字段表](14-remote-v4-bootstrap载荷-完整字段表.md) ·
  [13 工作区桥](13-remote-v4-工作区桥与重连协议.md) ·
  [17 workspace-list](17-remote-v4-workspace-list载荷.md) ·
  [10 扩展面与可观测面](10-remote-v4-网页扩展面与可观测面穷尽盘点.md) ·
  [18 对话渲染](18-remote-v4-功能域-对话渲染与流式更新.md) ·
  [19 输入附件](19-remote-v4-功能域-输入附件与文件预览.md) ·
  [20 导航与外观](20-remote-v4-功能域-导航列表与全局外观.md) ·
  [21 回前台恢复](21-回前台对话恢复-页面机制与真机事实.md) ·
  [22 壳端注入点](22-remote-v4-壳端注入点审计-主题-滚动条-会话加载.md) ·
  [24 重载恢复](24-remote-v4-页面重载恢复机制逐条取证.md)
