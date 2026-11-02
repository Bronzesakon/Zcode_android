# remote-v4 功能域：输入附件与文件预览

> **审计基线**：3.15.3（2026-10-15 快照）
> 主包 `assets/index-BGa3KiRe.js`（6,592,643 B，SHA-256 `ce2e2fc4…b88fd`）
> schema `assets/validation-CqXZOLXO.js`（307,369 B，SHA-256 `96e64e8f…dd6c9b`）
> 旧基线（2026-09-20 / `index-B-ilXaCQ.js`）的偏移与符号名**一律作废**。
>
> **附加关键文件（本批新定位）**：`assets/parts-DcYGwy2q.js`（57,142 B）—— 附件 RPC 的
> **Zod 参数/应答 schema 全在这里**（旧基线把这块归给主包，实际已切分出来；主包 @68,881 有
> `from"./parts-DcYGwy2q.js"` 的 import）。样式 `assets/index-BD2BL4Cc.css`（455,351 B）。
> **偏移口径**：UTF-8 **字节偏移**（`node -e "…b.indexOf('…')"` 可复现，`Buffer.indexOf` 才是字节口径）。

---

## 0. 一句话

输入面三块：**Lexical 编辑器**（状态整串存为 `editorStateJson`）、**两套并存的 localStorage 草稿键**
（`zcode-chat-composer-drafts:v1:` 与 `zcode-v4-composer-drafts:v1:`，其中 A 套在当前构建**只剩读/删、没有写入**）、
**四段式分片附件上传**（`attachmentBeginV4` → `attachmentChunkV4` → `attachmentCommitV4`，
失败补 `attachmentAbortV4`；**实际 384 KiB/片、schema 上限 512 KiB**、`sha256:<64 位小写 hex>` 秒传、`nextChunkIndex` 续传）。
预览没有自己的协议，全部是懒加载 chunk（Office/PDF/diff/图表/mermaid/KaTeX）。

**与旧基线相比最硬的变化（3 条）**：
1. 上传流程从"begin + 逐片"变成 **begin → chunk… → commit → (abort)** 四段，新增 `attachmentCommitV4` / `attachmentAbortV4`；
2. 进度回调形状由"一个比例数"变成 **`{phase:'uploading'|'committing', uploadedBytes, totalBytes}` 对象**；
3. 秒传分支与每片应答都新增了**服务端进度一致性校验**（`fault.attachment.invalidServerProgress`）。

---

## 1. 输入框：编辑器与草稿

### 1.1 编辑器与 `editorStateJson`

| 项 | 值 | 坐标/证据 |
| --- | --- | --- |
| 编辑器 | **Lexical**，且已是**新版 extension API**（`LexicalBuilder` / `ReactProvider` / `InitialState`） | 主包 @1,972,559 `xEt=Symbol.for(\`@lexical/extension/LexicalBuilder\`)`；@1,977,268 `@lexical/react/ReactProvider`；@1,972,466 `@lexical/extension/InitialState` |
| 包集合（全量，共 4 个字符串） | `@lexical/extension`、`@lexical/react`、`@lexical/plain-text`、`@lexical/rich-text` | 主包全文正则 `@lexical/[a-z-]+` |
| Lexical 版本号 | **未在产物中出现**（无 `version:"x.y.z"`、无 sourcemap、无 license banner；`_manifest.json` 中 `.map` 记录 0 条） | ⚠️ 只能由 API 面推：`LexicalBuilder`+`ReactProvider` 属 Lexical 0.32+ 的扩展式 API；**具体版本未证实** |
| `JEt(320,e)` | **不是版本号**：`JEt` 是 Lexical 的错误抛出器（`Minified Lexical error #${e}`），320 是**错误码** | 主包 @1,982,656 `function JEt(e,...t){…Error(\`Minified Lexical error #${e};…\`)}`；调用点 @1,983,845 `@lexical/plain-text`/`rich-text` 共存告警 |
| 持久化字段 | `editorStateJson`（字符串，与纯文本 `text` 并列） | 主包 11 处：1,761,385 / 1,761,412 / 1,761,430 / 1,762,346 / 1,766,562 / 2,293,935 / 2,293,985 / 2,300,334 / 2,301,954 / 2,306,027 / 2,306,076 |

**`editorStateJson` 的格式来源与生命周期**（必答 8）：

```js
// 生产（写草稿时）：主包 @2,300,149 起
let e = Ge.current?.getEditorState();           // Lexical EditorState
t = e ? JSON.stringify(e.toJSON()) : void 0;    // @2,300,188  ← Lexical SerializedEditorState
// 失败：catch(e){ Z.warn(`[v4-composer] 草稿 editorState 序列化失败: ${String(e)}`) }  @2,300,260

// 消费（恢复草稿时）：主包 @2,293,874 function BLt({draft,inputApi,onEditorStateError})
if (e.editorStateJson) try { t.setEditorStateJson(e.editorStateJson); return t.getMarkdown() }
                       catch (e) { n?.(e) }     // 解析失败→回调，然后退到纯文本 text

// setEditorStateJson 的实现：主包 @2,069,751
function Qkt(e,t){ let n = e.parseEditorState(t); e.setEditorState(n,{tag:gB}) }   // Lexical parseEditorState
```

- **格式来源**：Lexical 的 `EditorState.toJSON()`，即官方 `SerializedEditorState`（`{"root":{…,"type":"root","version":1}}` 形态），**版本号即 Lexical 自身的序列化版本**，未在产物中硬编码。
- **最小必填结构**：`{ root: { children: [...], type: "root" } }`；页面**不做结构校验**，直接交给 `parseEditorState`，异常走 `onEditorStateError` 回调（草稿仍在，只是回退到 `text`）。
- **客户端要不要理解它**：**要**——不是纯透传。读草稿时页面必须能 `parseEditorState` 成功，才能拿 `getMarkdown()` 还原富文本（mention、格式等）。所以壳若要做"草稿迁移/裁剪"，**不能改 `editorStateJson` 的内部结构**，否则只能拿到降级的纯文本。
- 壳侧另有 `__zcodeLexicalInputE2E` 桥（@2,074,750 区）：`{focus,getEditorState,getText,setText,setTextWithPluginMentions,setEditorStateJson}` —— 这是页面自留的 E2E 注入点。

### 1.2 两套草稿键**并存**（且当前构建里 A 套已退化）

| 套 | 键前缀 | 常量 | 默认 scope | 实现函数 | 能力 |
| --- | --- | --- | --- | --- | --- |
| **A** | `zcode-chat-composer-drafts:v1:` | `X9e` @1,353,430 | `__draft__`（`Z9e` @1,353,471） | `$9e` 键构造 @1,353,594 / `eet` 读 @1,353,670 / `tet` 删 @1,354,021 | **只有「读 + 删」，无写入** |
| **B** | `zcode-v4-composer-drafts:v1:` | `qvt` @1,760,077 | `__draft__`（`CP` @1,760,120） | `TP` 键构造 @1,760,392 / `EP` 读 @1,760,467 / `Xvt` 单条校验 @1,760,867 / `Zvt` 落盘 @1,760,002 / `OP` 取 scope / `kP` 写 scope / `Qvt` 删 scope | **完整 CRUD** |

**A 套退化的证据（新结论，推翻旧基线"两者语义相同"）**：
- `X9e` 全文只有 2 处（定义 + `$9e` 内使用），`$9e` 全文只有 2 处（定义 + `tet` 内使用）；
- `eet`（读）只被 `tet`（删）调用；
- 全快照 **2,489 个 JS 文件**中，`zcode-chat-composer-drafts` 只出现 1 次（主包 @1,353,439）——
  **快照里不存在任何 A 套写入路径**（旧基线记录的"两套并存、语义相同"在 3.15.3 已不成立）。
- A 套两条日志仍在：`[chatComposerDraftStorage] 读取 composer 草稿持久化失败` @1,353,760、`清理 composer 草稿持久化失败` @1,354,226。

> ⚠️ A 套的读/删**是否仍被调用**未能证实（压缩后同名符号在多处作用域复用，无法静态区分调用点）。

### 1.3 B 套读写语义（逐字，主包 1,760,077–1,760,600）

```js
qvt = 'zcode-v4-composer-drafts:v1:'                  // 键前缀
CP  = '__draft__'                                     // 默认 scope 名
wP  = new Set                                          // 告警去重（一个键只报一次）
TP(pageId, scope) = qvt + encodeURIComponent(scope?.trim() || pageId)

// 读整份：结构坏→整份丢；单条坏→只丢该条（逐条 Xvt 归一化）
EP(key) → {version:1, scopes:{…}} | {version:1, scopes:{}}   // 读失败走 Jvt 告警
Xvt(rec) 归一化字段白名单：
   text(必须 string，否则整条丢) / editorStateJson(string) / mention(需含
   id,category,label,value,markdown 五个 string 且 category ∈
   {files,skills,commands,subagents,whiteboards,sessions,plugins}) / mode(经 H.safeParse，
   值为 'plan' 时归一成 'build') / planEnabled / lastPermissionGrantId /
   lastPlanTransitionId / modelSelection(经 he.safeParse) / initializeFromNewTask / updatedAt(数字，否则 0)

// 取某 scope
OP(pageId, scope) = EP(TP(pageId,scope)).scopes[scopeKey]，无 text 则 null

// 写单条：草稿"空"就删，否则覆盖 + 刷新 updatedAt
kP(pageId, scope, scopeKey, draft):
   if (!draft.text.trim() && !draft.editorStateJson && !draft.mention && !draft.mode
       && !draft.modelSelection && !draft.initializeFromNewTask) delete a.scopes[scopeKey]
   else a.scopes[scopeKey] = { ...draft, updatedAt: Date.now() }
   return Zvt(key, a)

// 落盘：只做三件事（removeItem / setItem / 告警），无任何条数剪枝
Zvt(key, state):
   if (Object.keys(state.scopes).length === 0) { storage.removeItem(key); return true }
   storage.setItem(key, JSON.stringify(state)); wP.delete(key); return true
   catch(t) { Jvt(key, t); return false }     // Jvt → `[v4-composer-draft] 草稿持久化访问失败` @1,760,210
```

- **scope 取值**：`sessionId ?? '__draft__'`（新会话用 `__draft__`）——主包 @1,764,336 区
  `let p = i ?? '__draft__'`（i = sessionId），@1,766,782 区 `P=(e)=>{ … if (C.current.scopeKey!==m || p!=='__draft__') return; … kP(t,n,r,C.current.draft) && Qvt(t,n,CP) }`
  —— **发出首条消息后把 `__draft__` scope 删掉**。
- **跨工作区迁移**：@2,302,611 区 `yjt({sourceWorkspacePath,sourceWorkspaceIdentity,targetWorkspacePath,targetWorkspaceIdentity})`。
- **新会话继承**：`eyt({workspacePath,workspaceIdentity,sessionId,reused})` @1,762,797 —— 若新会话无草稿，把 `__draft__` 的 `mode/planEnabled/modelSelection` 拷过去。

### 1.4 条数上限复核（旧基线"无上限"→ **确认成立**）

- `Zvt`（唯一落盘路径）**不含任何 LRU/裁剪逻辑**；
- 全快照 2,489 个 JS 文件检索 `MAX_DRAFTS` / `DRAFTS_MAX` / `maxDrafts` / `draftMax` /
  `maxDraftCount` / `draftLimit` / `composerDraftMax` = **全部 0 命中**（`node` 逐文件 `Buffer.indexOf` 扫描）。
- 结论：**3.15.3 仍无草稿条数上限**（旧基线结论在新构建上复核通过）。
- 后果（不变）：增长量 ≈ 留下的会话数 × 1–3 KB（`text` + `editorStateJson`），长期可能撞 localStorage 配额；
  `setItem` 失败被 `try/catch` 吞成一条 `[v4-composer-draft] 草稿持久化访问失败`，表现为**草稿静默不再保存**。

### 1.5 输入路由（`inputRouting`）—— 本次已补齐

```js
// validation-CqXZOLXO.js @185,272（会话 schema 里 inputRouting:Mh @189,206）
Mh = O({ mode: M(['startNow','enqueue','guide','reject','choice']), reasonCode: S().optional() })
```

| 项 | 值 | 坐标 |
| --- | --- | --- |
| 模式枚举（5 个） | `startNow` / `enqueue` / `guide` / `reject` / `choice` | validation @185,272 |
| 消费 | `mn = e?.inputRouting.mode ?? 'startNow'`；`nyt({inputRoutingMode,…}) = mode !== 'reject' && !(isWebRemoteControl && isMobileTextInputViewport)` | 主包 @2,304,272 / @2,304,310；`nyt` 定义 @1,763,190 |
| 发送键行为 | `AP(followupMode) = followupMode === 'guide' ? 'queue' : 'startNow'`；`tyt` 把 `zcodeInteractionBehavior==='guide'` 映射成 `'guide'`，否则 `'queue'` | 主包 `tyt` @1,763,054 / `AP` @1,763,137 |
| 对发送的影响 | `bn = r || (e !== null && mn !== 'reject')`，即 `reject` 模式下**不允许向已有会话发输入** | 主包 @2,304,310 区 |

> 旧基线把这一段记为"未展开"，本构建已给出枚举与消费点。

---

## 2. 附件：分片上传协议

### 2.1 常量总表（唯一真相源：`validation-CqXZOLXO.js` 的 `Uu` 对象 @117,018；主包内别名 `W`，parts 内别名 `_`）

| 常量 | 值 | 含义 | 坐标 |
| --- | --- | --- | --- |
| `maxFrameBytes` | `1024*1024` = **1 MiB** | 单帧上限（超则 `proto.frameTooLarge`） | validation @117,022 |
| `attachmentMaxBytes` | `20*1024*1024` = **20 MiB** | 单附件总字节上限 | validation @117,634 |
| `attachmentChunkMaxBytes` | `512*1024` = **512 KiB** | 单片**解码后**字节上限（服务端校验） | validation @117,666 |
| `attachmentUploadMaxChunks` | **64** | 单片数上限 | validation @117,869 |
| `attachmentUploadMaxConcurrent` | **16** | 服务端并发上限（**网页侧不读这个值**，见 §2.7） | validation @117,898 |
| `attachmentUploadMaxStagedBytes` | `64*1024*1024` = 64 MiB | 暂存总量 | validation @117,931 |
| `attachmentUploadTtlMs` | `5*6e4` = 5 分钟 | 未提交上传的 TTL | validation @117,975 |
| `attachmentUnreferencedTtlMs` | `1440*60*1e3` = 24 h | 未被引用附件的 TTL | validation @118,003 |
| `attachmentPreviewMaxBytes` | `Lu` = `30*1024*1024` = **30 MiB** | 预览可读上限 | `Lu` validation @116,736 |
| `attachmentPreviewMaxChunks` | `Lu/(512*1024)` = **60** | 预览分片轮数上限 | validation @117,768 |
| `attachmentStatMaxBytes` | `2*1024*1024*1024` = **2 GiB** | stat 上限 | validation @117,728 |
| `attachmentReadCacheMaxBytes` | `Lu` = 30 MiB | 读缓存上限 | validation @117,809 |
| `attachmentReadCacheTtlMs` | `3e4` = 30 s | 读缓存 TTL | validation @117,840 |

**`Lu` 的值**（同文件、`Uu` 之前定义）：`var Lu=30*1024*1024` @**116,736** —— 即 **30 MiB**。
故 `attachmentPreviewMaxBytes = 30 MiB`、`attachmentReadCacheMaxBytes = 30 MiB`、
`attachmentPreviewMaxChunks = 30 MiB / 512 KiB = 60`。注意最后一项是**按 512 KiB 折算**的，
这本身就是"**512 KiB 是该协议里预览/分片的基准单位**"的一条旁证（但见 §2.2：上传实际发的是 384 KiB）。

**紧随 `Uu` 之后的另一个对象 `L`**（validation @**118,104**，逐字）：
`L={maxIdLength:64,maxTitleLength:120,maxDescriptionLength:500,maxVersions:16,maxItemsPerPage:500,defaultItemsPerPage:200,maxPageBytes:Uu.logicalFrameAssemblyMaxBytes/4,maxFieldsPerRequest:32,maxFieldPathLength:256,maxFieldValueBytes:4096}`
—— 这是**分页/列表/字段投影**约束，`maxPageBytes = 16 MiB/4 = 4 MiB`。它与本篇的
**预览 chunk 分类无关**（预览是懒加载 JS chunk，不是分页载荷）；与附件唯一沾边的是
`Uu` 内部的 `rowsRangeMaxLimit:200`（会话行窗口）与 `snapshotTailWindowRows:60`，
不在本功能域内，此处仅登记以免被误当成附件常量。

### 2.2 客户端分片常量（**必答 1**）

> **先把两件事分开（旧文档最容易错的地方）**
> 1. **schema 允许的单片上限 = 512 KiB**（`attachmentChunkMaxBytes`，validation @117,666）。这是**上限**，
>    服务端 `superRefine` 硬校验"解码后 > 512 KiB"直接拒（parts @20,080 区
>    `attachment chunk exceeds decoded byte limit`）。512 KiB **同时**是客户端**读**路径的页大小（见下）。
> 2. **客户端上传时实际每片 = 384 KiB**（`Aw = 384*1024`，主包 @1,103,781）。**不是**按 512 KiB 上限算出来的动态值，
>    也与文件类型/大小无关；`Aw` 是模块级常量，唯一"动态"的是**末片被夹短**。
>
> 两件事的证据都在下面这张表里，互不替代：512 KiB 是"**最多能收多少**"，384 KiB 是"**实际发多少**"。
> 之所以能确定 384 KiB 是实际值而非上限：客户端自己用它算 `totalChunks` 并据此切片，
> 若按 512 KiB 分片则片数与偏移都会不同（见"实际值证据"行）。

| 问题 | 结论 | 证据 |
| --- | --- | --- |
| **schema 允许的上限** | **512 KiB**（= 524,288 B） | validation @117,666 `attachmentChunkMaxBytes:512*1024`；校验点 parts @20,080 区 |
| **客户端实际每片** | **384 KiB**（= 393,216 B） | 主包 @1,103,781 `var Aw=384*1024;`；**同一数值也出现在 schema 模块**：validation @233,472 `Gy=k([S(),w().int()]),Ky=384*1024;`（`Ky` 被用于 `O({dataBase64:S().max(Ky/3*4)})` @233,921，即该通道单片解码后 ≤ 384 KiB） |
| **实际值证据 ①（片数）** | `totalChunks = Math.ceil(a.byteLength / Aw)`，即按 384 KiB 折算片数 | 主包 @1,105,592 区（`let c=Math.ceil(a.byteLength/Aw)`） |
| **实际值证据 ②（偏移与长度）** | `let t = n*Aw`，切片 `a.subarray(t, Math.min(t+Aw, a.length))`，偏移步长 = `Aw` | 主包 @1,106,096（`let t=n*Aw`）/ @1,106,156（`Math.min(t+Aw,a.length)`） |
| **实际值证据 ③（进度口径）** | 进度分母用 `Math.min((n+1)*Aw, byteLength)`（@1,106,366）、续传起点用 `Math.min(nextChunkIndex*Aw, byteLength)`（@1,105,977）—— 全部以 `Aw` 为单位 | 主包 @1,105,977 / @1,106,366 |
| 单位 KiB 还是 KB | **KiB**（`*1024`；若为 KB 应是 384,000） | 同上；Rust 侧测试断言 `393_216`（`conversation.rs:3494`） |
| 是否随类型/大小变 | **不变**。`Aw` 是模块常量；只有末片是部分片 | 主包 @1,103,781（常量）/ @1,106,156（末片夹短） |
| 512 KiB 在客户端的**另一处**真实用途 | 作为**读**页大小：`attachmentReadV4` 的 `limit` = `W.attachmentChunkMaxBytes`；workflow artifact 读取器同样用它做页大小（96 轮上限） | 主包 @1,113,011（read limit）、@3,302,880 `var T9t=W.attachmentChunkMaxBytes,E9t=96` |
| 与片数上限的关系 | 20 MiB ÷ 384 KiB = **54 片** ≤ `attachmentUploadMaxChunks`（64）；若按 512 KiB 只需 40 片 —— 故**先撞字节上限**，两个上限都不紧 | 计算 + validation @117,634 / @117,869 |
| 帧尺寸护栏 | 发每个上传帧前用本地二进制序列化器预测量，超 1 MiB 抛 `proto.frameTooLarge`（384 KiB 片 base64 后约 512 KiB 字符，留有余量） | 主包 @1,105,048 `function w1e(e,t){let n=new mw;return xw(n,[100,2147483647,za.ZCodeAgent,e]),xw(n,[t]),n.buffer.byteLength}`；@1,105,155 `Mw` 比对 `W.maxFrameBytes` |

**给实现的口径（直接照此落地，别再混用）**：

| 用途 | 该用哪个值 | 理由 |
| --- | --- | --- |
| **上传**每片大小 | **384 KiB**（393,216 B） | 网页客户端实测值，`Aw` 常量；改大不会立刻坏，但与网页侧片数/进度口径不一致 |
| 服务端**接收**单片的上限校验 | **512 KiB** | `attachmentChunkMaxBytes`；若按此上限分片也能通过，但网页端不这么做 |
| **读取**（预览/区间）每页大小 | **512 KiB** | 网页 `attachmentReadV4` 的 `limit` 就用它（@1,113,011）；Rust 目前用 384 KiB（见 §4 第 13 行） |
| 单片**片数**上限 | 64 片（20 MiB ÷ 384 KiB = 54 片，够用） | `attachmentUploadMaxChunks` |

### 2.3 RPC 方法全集（**必答 4**）

**网页侧实际调用的 6 个**（`attachmentBegin/Chunk/Commit/AbortV4` + `attachmentReadV4` + `attachmentPreviewSourceV4`）：

| 方法 | 请求字段（线上实发） | 应答形状 | 坐标（调用点 / schema） |
| --- | --- | --- | --- |
| `attachmentBeginV4` | `connectionId`, `uploadId`, `sessionId`, `fileName`, `mime`, `totalBytes`, `totalChunks`, `checksum` | `{uploadId, state:'staging', nextChunkIndex}` \| `{uploadId, state:'committed', nextChunkIndex, ref}` | 主包 @1,105,633 / @1,105,695；parts @18,681；应答联合 @19,193 |
| `attachmentChunkV4` | `connectionId`, `uploadId`, `sessionId`, `chunkIndex`, `dataBase64` | `{uploadId, nextChunkIndex}` | 主包 @1,106,190 / @1,106,221；parts @19,696（params）/ @20,149（result） |
| `attachmentCommitV4` | `connectionId`, `uploadId`, `sessionId` | `{ref}` | 主包 @1,106,544 / @1,106,575；parts @20,213（params）/ 结果 `{ref}` = parts `tn` @18,528 |
| `attachmentAbortV4` | `connectionId`, `uploadId`, `sessionId`（与 commit 同形） | 空（best-effort，失败只 warn） | 主包 @1,106,623；**桌面端同构**：`app.asar out/host/index.js` @265,524 区 `e.request(It.attachmentAbort,{connectionId:t,uploadId:c.uploadId,sessionId:c.sessionId},Qg)` |
| `attachmentReadV4` | `sessionId`, `ref`, `target?`, `attachmentIndex?`, `offset`, `limit` | `{dataBase64, mediaType, totalBytes, nextOffset}` | 主包 @1,112,837 区 / @1,113,795；parts @20,310（params）/ @21,682（result） |
| `attachmentPreviewSourceV4` | `sessionId`, `ref`, `target?`, `attachmentIndex?`, `clientMode` | `{kind:'local_path', path, mediaType}` \| `{kind:'chunked'}` | 主包 @1,112,371；parts @20,736（params）/ @21,134（result） |

**网页侧不存在的两个（桌面端专有）**：`conversationAttachmentReadV4`、`conversationAttachmentStatV4`
（全快照 2,489 JS 检索 = 0 命中；桌面端见 `out/host/chunk-T2GB4I3K.js` @66,995 区）。
`clientMode` 取值 `desktop-continuous` | `web-remote-replayable`，由页面全局模式注入
（主包 @3,713,814 区 `configureClientMode`，默认 `desktop-continuous`）。

> ⚠️ 快照里**没有**"方法名 → schema"的绑定表（`attachmentBeginV4` 等字面量只出现在主包调用点）。
> 上表的方法↔schema 对应关系是**按字段形状唯一匹配 + 桌面端 host 的逐字段投影**推定的
> （`out/host/index.js` @264,857 区逐字段列出 begin/chunk/commit/abort 的线上字段集，与 parts schema 完全一致）。

**请求/应答的传输外壳**：`parts-DcYGwy2q.js` @23,7xx 区给出帧尺寸估算
`un({method:'v4/conversation/frame',params:e})` + `transportEnvelopeIdMaxChars` 信封。

**⚠️ 快照里还有第二条附件上传通道（不是 V4 relay，别混用）**：`validation-CqXZOLXO.js` @233,450–234,000 区
另有一组 schema，作用域是 `workspacePath`/`workspaceIdentity`/`remoteSessionId`（`Py.extend({workspacePath:…})` @233,484），
字段是 **`{fileName, mime, bytes, totalChunks, checksum}`**（@233,688，注意是 `bytes` 而非 `totalBytes`，
且**没有 `uploadId`/`sessionId`/`connectionId`**），分片体是 `{dataBase64}` 且 schema 硬限 `max(Ky/3*4)` 字符
（@233,921，即解码后 ≤ 384 KiB），外面套 JSON-RPC 四元 union（`{id,method,params?,trace?}` /
`{method,params?,trace?}` / `{id,result}` / `{id,error:{code,message,data?}}`，@233,962；`id = [string, int]` 元组 @233,450）。
**主包全文检索 `uploadId` 只有 1 处、`totalChunks` 只有 1 处，都在 §2.4 的 `T1e` 里** ⇒ 网页端**没有**这条通道的客户端，
它服务于桌面端/本地 service-port 方向。本篇的所有上传结论都以 §2.3–§2.4 的 V4 通道为准。

### 2.4 上传主流程（逐字，主包 @1,105,235 `async function T1e(e,t,n,r={})`）

```js
function jw(e){ if(!e?.aborted) return; let t=Error(`Attachment upload canceled`); throw t.name=`AbortError`, t }
async function T1e(e, t, n, r={}){            // e=client, t={connectionId}, n={sessionId,fileName,mime,dataBase64}, r={signal,onProgress}
  jw(r.signal);
  let i = b1e(n.dataBase64);                  // 声明长度（严格 base64 校验）
  if (i > W.attachmentMaxBytes) throw Error(`proto.payloadTooLarge`);
  let a = y1e(n.dataBase64);                  // atob → Uint8Array
  if (a.byteLength !== i) throw Error(`proto.invalidBase64`);
  let o = C1e();                              // uploadId = `upload-${crypto.randomUUID()}`
  let s = { ...t, sessionId: n.sessionId, uploadId: o };        // ← base（chunk/commit/abort 共用）
  let c = Math.ceil(a.byteLength / Aw);                          // totalChunks
  let l = { ...s, fileName:n.fileName, mime:n.mime, totalBytes:a.byteLength, totalChunks:c,
            checksum: await S1e(a) };                            // begin 载荷
  Mw(`attachmentBeginV4`, l);                                    // 帧尺寸护栏
  let u = false;
  try {
    jw(r.signal);
    let t = await e.attachmentBeginV4(l);
    u = true;                                                    // ← 之后失败必须 abort
    if (t.state === `committed`) {                               // ★ 秒传
      r.onProgress?.({phase:`committing`, uploadedBytes:a.byteLength, totalBytes:a.byteLength});
      return { ref: t.ref };
    }
    if (t.nextChunkIndex > c) throw Error(`fault.attachment.invalidServerProgress`);
    r.onProgress?.({phase:`uploading`, uploadedBytes: Math.min(t.nextChunkIndex*Aw, a.byteLength), totalBytes:a.byteLength});
    for (let n = t.nextChunkIndex; n < c; n += 1) {               // ★ 续传起点 = 服务端给的
      jw(r.signal);
      let t = n*Aw, i = { ...s, chunkIndex:n, dataBase64: x1e(a.subarray(t, Math.min(t+Aw, a.length))) };
      Mw(`attachmentChunkV4`, i);
      if ((await e.attachmentChunkV4(i)).nextChunkIndex !== n+1)
        throw Error(`fault.attachment.invalidServerProgress`);    // ★ 每片都要回执确认
      r.onProgress?.({phase:`uploading`, uploadedBytes: Math.min((n+1)*Aw, a.byteLength), totalBytes:a.byteLength});
    }
    jw(r.signal);
    r.onProgress?.({phase:`committing`, uploadedBytes:a.byteLength, totalBytes:a.byteLength});
    let n2 = s; Mw(`attachmentCommitV4`, n2);
    return await e.attachmentCommitV4(n2);                        // 应答 = {ref}
  } catch (t) {
    if (u) try { await e.attachmentAbortV4(s) }
           catch (e) { Z.warn(`[v4-attachment] failed to abort upload transaction`, e) }   // @1,106,677
    throw t;
  }
}
```

四条可读出的设计（其中 2 条相对旧基线是**新增**）：

1. **秒传**靠 begin 应答的 `state:'committed'` —— 一片不传，直接拿 `ref`（见 §2.5）。
2. **续传**靠服务端返回的 `nextChunkIndex`（不是客户端自记进度）—— 刷新/重试后仍能续（见 §2.6）。
3. 🆕 **每片都要服务端回执**：`chunk` 应答的 `nextChunkIndex` 必须恰为 `n+1`，否则立刻报错并 abort。
4. 🆕 **有显式 commit 段**：分片传完不算完成，必须再发一次 `attachmentCommitV4` 才拿到 `ref`；
   begin 成功之后的任何失败都会补发 `attachmentAbortV4`（失败仅 warn，不覆盖原异常）。

### 2.5 秒传（**必答 2**）

| 子问题 | 结论 | 证据 |
| --- | --- | --- |
| 算法 | **SHA-256**（WebCrypto `crypto.subtle.digest('SHA-256', …)`） | 主包 @1,104,548 `function S1e(e){ if(!globalThis.crypto?.subtle) throw Error('fault.attachment.checksumUnavailable'); let t=await globalThis.crypto.subtle.digest('SHA-256', Uint8Array.from(e).buffer); … }` |
| 编码 | **小写十六进制**，64 字符，无 padding、**不是 base64url**：`sha256:${[…].map(e=>e.toString(16).padStart(2,'0')).join('')}` | 主包 @1,104,624 区（`checksumUnavailable`）与 @1,104,700 区 |
| 前缀 | `sha256:`（小写 `sha256`，冒号后紧跟 64 位 hex） | 同上 |
| 格式约束（服务端强校验） | `/^sha256:[0-9a-f]{64}$/`（parts @19,200 区用 `[0-9a-f]`；validation @233,866 用 `[a-f0-9]`，等价） | parts `Ai=e.string().regex(/^sha256:[0-9a-f]{64}$/)` |
| 在哪一步比对 | **只在 `attachmentBeginV4` 这一步**。checksum 随 begin 请求体上行（`totalBytes`/`totalChunks`/`checksum` 三者同批），服务端按 checksum 判定内容是否已存在 | 主包 @1,105,592 区（begin 载荷构造） |
| 命中的响应形状 | `{uploadId, state:'committed', nextChunkIndex, ref}`（判别联合的另一支）；客户端据此**发一次 `phase:'committing'` 进度后直接返回 `{ref}`** | parts @19,193 区；主包 @1,105,724 区 |
| 未命中 | `{uploadId, state:'staging', nextChunkIndex}`，进入分片循环 | 同上 |
| 失败降级 | `crypto.subtle` 不可用 → `fault.attachment.checksumUnavailable`（**不是**回退到别的哈希） | 主包 @1,104,624 |

### 2.6 续传（**必答 3**）

| 子问题 | 结论 | 证据 |
| --- | --- | --- |
| `nextChunkIndex` 语义 | 服务端**已持有的下一个待传片下标**（= 已落盘片数）；`committed` 时该字段仍必须存在（schema 里两支都带） | parts @19,193 区（`staging` 支与 `committed` 支都含 `nextChunkIndex`） |
| 服务端何时返回 | ① **begin 应答**（staging / committed 两种都带）；② **每个 chunk 应答**必须回 `{uploadId, nextChunkIndex}` | parts @20,000 区（chunk result）；主包 @1,106,190 区 |
| 客户端如何续 | `for (let n = t.nextChunkIndex; n < c; n += 1)`，起点**完全由服务端决定**，客户端不自记 | 主包 @1,106,058 区 |
| 进度如何续 | 进入循环**前**先发一次 `phase:'uploading'`，`uploadedBytes = Math.min(nextChunkIndex*Aw, byteLength)` —— 进度条从服务端进度起跳而不是从 0 | 主包 @1,105,988 区 |
| 越界校验 | begin 应答 `nextChunkIndex > totalChunks` → `fault.attachment.invalidServerProgress`；chunk 应答 `!== n+1` → 同错 | 主包 @1,105,856 / @1,106,243 区 |
| 注意 | chunk 应答里的 `nextChunkIndex` **不驱动循环**，只做 `n+1` 一致性校验（循环变量仍是本地 `n`） | 同上 |

### 2.7 进度与取消（**必答 5**）

**进度事件形状**（协议层回调 `onProgress`，主包 @1,105,988 / @1,106,058 / @1,106,21x 区）：

```js
{ phase: 'uploading' | 'committing', uploadedBytes: number, totalBytes: number }
```

**节律**（按发生顺序）：

| 时机 | 事件 |
| --- | --- |
| 秒传命中 | 恰好 1 次 `{phase:'committing', uploadedBytes: totalBytes, totalBytes}` |
| 正常上传，进入循环前 | 1 次 `{phase:'uploading', uploadedBytes: min(nextChunkIndex*384KiB, total)}`（续传时非 0） |
| **每一片成功后** | 1 次 `{phase:'uploading', uploadedBytes: min((n+1)*384KiB, total)}` |
| 分片循环结束、commit 前 | 1 次 `{phase:'committing', uploadedBytes: totalBytes, totalBytes}` |
| 无 `onProgress` | 全部可选链（`r.onProgress?.()`），静默 |

**UI 层对进度的加工**（主包 @2,109,618 `function Mjt`）：

```js
Mjt(uploaded, total) = total <= 0 ? 0 : Math.min(99, Math.max(0, Math.floor(uploaded/total*99)))
// 即：进度百分比封顶 99，直到上传真正结束才由状态置 100
```

`phase==='committing'` 会把 UI 的 `uploadStatus` 从 `uploading` 抬成 **`committing`**（主包 @2,111,586 区、@2,111,652 区），
`uploadProgress` 用 `Math.max(旧值, Mjt(…))` 保证单调不回退。

**取消语义**：

| 层 | 机制 | 坐标 |
| --- | --- | --- |
| 协议层 | `jw(signal)` 在 **begin 前 / 每片前 / commit 前**各查一次；已 abort 则抛 `Error('Attachment upload canceled')` 且 `name='AbortError'` | 主包 @1,103,797 |
| 协议层善后 | begin 已成功（`u=true`）后任何异常 → best-effort `attachmentAbortV4(base)`；abort 自身失败只 warn `[v4-attachment] failed to abort upload transaction` | 主包 @1,106,623 / @1,106,677 |
| UI 层 | 每个附件一个 `AbortController`（`v.current` Map 按 `scopeKey\0attachmentId` 索引）；取消时 `.abort()`；本地物化路径额外 `transferService.cleanup(operationId)` | 主包 @2,111,302 区 |
| 取消判定 | `kjt(e)`：`e.name === 'AbortError'` 或沿 `e.cause` 链递归为 AbortError → 视为取消 | 主包 @2,109,0xx 区 |

**失败的重试语义**（主包 @2,109,241 `function jjt` / @2,113,324 / @2,114,992）：

```js
// 是否"可重试"（transient）：
jjt(e) = 若 kjt(e)(取消) 或 e instanceof {YB,XB,RAt} 或 e.code === 'remoteAttachmentNotStaged'
         → false（不重试）
         否则 → !/(payloadTooLarge|invalidBase64|invalidServerProgress|frameTooLarge|permission|
                    EACCES|ENOENT|not found|unsupported|附件缺少|缺少可读取内容|远端附件未完成物化)/i.test(msg)
```

| 触发 | 行为 |
| --- | --- |
| transient 且 `autoRetryCount < 1` | 置 `uploadStatus:'queued'`、`uploadProgress:0`、`uploadErrorKind:'transient'`、`autoRetryCount+1`，**500 ms 后自动重排一次**（只自动重试 1 次） |
| 否则 | `uploadStatus:'failed'`、`uploadErrorKind: s ? 'transient' : 'permanent'`、`uploadProgress: min(旧,99)`，日志 `[v4-composer-attachments] 附件上传失败` |
| 运行时重启（页面被重建） | `runtimeRebuildRetryCount < 5` → 回 `waitingSession` 并带 `uploadErrorKind:'runtimeRestarted'`；`>= 5` → `failed`，错误 id `chat.attachments.upload.runtimeRestarted`（@2,114,992） |
| 成功 | `uploadStatus:'ready'`、`uploadProgress:100`、`showComplete: !localZeroCopy`；`showComplete` 300 ms 后自动清掉 |

**并发节律**：调度器硬编码 **同时最多 2 个上传**（主包 @2,113,974 `for(;_.current<2 && g.current.length>0;)`），
用 `queueMicrotask` 重入；**页面不读** `attachmentUploadMaxConcurrent:16`（那是服务端常量，全快照主包 0 命中）。

### 2.8 错误码全集

**客户端主动抛**（主包检索 `fault.attachment.*` / `proto.*` 全量）：

| 错误码 | 触发 | 坐标 |
| --- | --- | --- |
| `proto.frameTooLarge` | 单帧估算 > `maxFrameBytes`（1 MiB） | @1,105,155 `Mw` |
| `proto.payloadTooLarge` | 附件 > 20 MiB | @1,105,342 |
| `proto.invalidBase64` | ① 声明长度 != 解码长度；② base64 字符集/padding 非法（`b1e`） | @1,104,090 / @1,104,290 / @1,104,377 / @1,105,423 |
| `fault.attachment.checksumUnavailable` | `crypto.subtle` 不可用 | @1,104,624 |
| `fault.attachment.invalidServerProgress` | begin 的 `nextChunkIndex > totalChunks`，或 chunk 应答 `!== n+1` | @1,105,888 / @1,106,277 |
| `fault.attachment.previewTooManyChunks` | 预览读轮数 ≥ `attachmentPreviewMaxChunks`（60） | @1,112,743 |
| `fault.attachment.previewChangedDuringRead` | 读途中 `totalBytes` 或 `mediaType` 变了 | @1,112,8xx 区 |
| `fault.attachment.previewSizeMismatch` | `offset + 本次长度 > totalBytes` | 同上 |
| `fault.attachment.previewOffsetMismatch` | `nextOffset` != `offset + 本次长度` | 同上 |
| `fault.attachment.previewTruncated` | `nextOffset === null` 但累计长度 != `totalBytes` | 同上 |
| `fault.attachment.previewEmptyChunk` | 非终止片却返回 0 字节 | 同上 |
| `Attachment upload canceled`（`AbortError`） | 用户/上层取消 | @1,103,797 |
| `remoteAttachmentNotStaged`（`Ajt.code`） | 本地文件未在远端物化（本地路径路径） | @2,108,867 |

**服务端错误码集合**（`parts-DcYGwy2q.js` @3,783 区 `new Set(Object.values({…}))`，共 11 个，**新增，旧基线只有 1 个**）：

| 错误码 | 含义 |
| --- | --- |
| `fault.attachment.statUnsupported` | stat 不支持 |
| `fault.attachment.readUnsupported` | read 不支持 |
| `fault.attachment.statNotFile` | 目标不是文件 |
| `fault.attachment.shareStatNotAuthorized` / `shareReadNotAuthorized` | 共享件无权限 |
| `fault.attachment.shareStatConnectionUntrusted` / `shareReadConnectionUntrusted` | 共享件来源连接不可信 |
| `fault.attachment.shareStatNotFound` | 共享件不存在 |
| `fault.attachment.shareStatTooLarge` | 共享件超 `attachmentStatMaxBytes`（2 GiB） |
| `fault.attachment.previewTooLarge` | 预览超 `attachmentPreviewMaxBytes`（30 MiB） |
| `fault.attachment.previewNotMedia` | 不是可预览媒体类型（仅 image/* / video/* / application/pdf / text/plain） |

**schema 级拒绝消息**（parts，客户端本地就会撞到）：
`zero-byte upload must declare zero chunks` @19,100（`totalBytes===0` 与 `totalChunks===0` 必须同真同假）、
`attachment chunk exceeds decoded byte limit` @20,080（单片解码后 > 512 KiB）、
`attachment preview only supports image/video/pdf/plain text types`、
`target and attachmentIndex must be provided together` @21,0xx 区。

### 2.9 上传其实有**三条**路径（旧基线只写了 1 条）

UI 上传编排在 `Njt` 里（主包 @2,109,993 `promptAttachmentTransferService` 起），逐条：

| 路径 | 触发条件 | 行为 | 坐标 |
| --- | --- | --- | --- |
| ① **本地零拷贝**（不上传） | 附件带 `localPath` 且**无远端会话/工作区身份**（`!fV(ctx)`） | `uploadStatus:'ready'`、`uploadProgress:100`、`attachmentRef = {ref: localPath, fileName, mime, bytes}`、`localZeroCopy:true` | @2,110,953 / @2,113,7xx 区（`localZeroCopy` 计算） |
| ② **远端物化**（桌面侧读本地文件） | 带 `localPath` 且**有远端会话**（`fV(ctx)` 为真） | `transferService.onDynamicProgress(operationId)` 订阅进度 → `transferService.stage({operationId,sessionId,workspacePath,workspaceIdentity?,remoteSessionId,localPath,fileName,mime,sizeBytes})`；`staged===false` 时抛 `remoteAttachmentNotStaged`（i18n id `chat.attachments.upload.remoteMaterializationRequired`）。**页面不传字节** | @2,111,586 区 / @2,112,206 |
| ③ **分片上传**（本文 §2.4） | 其余（有 `dataBase64` 或 `textContent`） | `tjt(rec)` 归一化 → `wjt(attachmentPut, sessionId, payload, {signal,onProgress})` → 走 `T1e` | `tjt` @2,103,605；`wjt` @2,108,204 |

路径 ③ 的入口 `wjt`（@2,108,204）逐字要点：

```js
if (n.localPath) return {...o, ref:n.localPath, fileName:i, mime:a, bytes:s??0};   // 本地直通
let c = ('dataBase64' in n && n.dataBase64) ? n.dataBase64
      : ('textContent' in n && n.textContent !== undefined) ? Sjt(n.textContent) : null;
if (c === null) { Z.warn(`[v4-composer] 附件无内容可发（无 localPath/dataBase64/textContent），已丢弃: ${i}`); return null }
let {ref:l} = await e({sessionId:t, fileName:i, mime:a, dataBase64:c}, r);        // ← e = attachmentPut
return {...o, ref:l, fileName:i, mime:a, bytes:s ?? Cjt(c)};
```

`attachmentPut(payload, opts)` 是 `T1e` 的薄封装（主包 @1,112,151 `async attachmentPut(t,r){return await a(), T1e(e,n,t,r)}`），
并经 `conversationTransport` 暴露为页面内 API（@1,117,985 / @1,335,216 / @1,337,392 三处透传）。

### 2.10 内联附件的尺寸闸门与文件数上限（**新增，旧基线没有**）

`tjt(rec)`（@2,103,605）按 MIME 分类并卡尺寸：

| 类型 | 判定 | 闸门 |
| --- | --- | --- |
| `image/*` | 无 `localPath` 且 `sizeBytes > $B` | 抛 `OversizedInlineImageAttachmentError`（`$B = 20*1024*1024` = **20 MiB**，@2,102,625） |
| `video/*` | 有 `localPath` → 直通；否则 `sizeBytes > KAt` | 抛 `OversizedInlineVideoAttachmentError`（`KAt = Math.min(S, W.attachmentMaxBytes)`，@2,102,641；**`S` 未解析** ⚠️ 推测为视频专用上限常量） |
| `application/pdf` | 有 `localPath` → 直通 | 抛 `OversizedInlinePdfAttachmentError` / `MissingInlinePdfContentError`（@2,104,615 / 类定义 @2,100,618 区） |
| 长文本粘贴 | 长度 ≥ `GAt = 15*1024`（15 KiB） | 转成附件文件（@2,102,613） |

错误类层次（主包 @2,100,618 区，逐字）：

```js
JB  = class extends Error { filename; maxSizeBytes; sizeBytes; constructor(e,t,n){super(t),this.name=e,
      this.filename=n.filename, this.maxSizeBytes=n.maxSizeBytes, this.sizeBytes=n.sizeBytes} }
LAt = class extends JB { constructor(e){ super('OversizedInlineImageAttachmentError','oversized-inline-image-attachment',e) } }
YB  = class extends JB { constructor(e){ super('OversizedInlineVideoAttachmentError','oversized-inline-video-attachment',e) } }
XB  = class extends JB { constructor(e){ super('OversizedInlinePdfAttachmentError','oversized-inline-pdf-attachment',e) } }
RAt = class extends Error { constructor(e){ super('missing-inline-pdf-content'), this.name='MissingInlinePdfContentError', … } }
```

**每条消息最多 8 个附件**（主包 @2,115,819 区）：

```js
R = () => So(p.formatMessage({id:`chat.attachments.maxFiles`},{count:`8`}), {variant:'warning',…})
z = (files) => { let t = 8 - dV(m).length; if (t <= 0) { files.forEach(tV); R(); return [] }
                 let n = files.slice(0, t); files.slice(t).forEach(tV); … }   // 超出直接丢弃 + 提示
```

**附件记录状态机**（`uV` store，主包 @2,107,3xx 区，含 E2E 钩子 `window.__zcodeComposerAttachmentUploadStoreE2E`）：

- store 按 `scopeKey = ${scope?.trim()||workspacePath}\u0000${scopeId}` 分桶（`Ojt` @2,109,028）；
- `uploadStatus` ∈ `queued` / `uploading` / `committing` / `ready` / `waitingSession` / `failed`；
- 其他字段：`operationId`（`prompt-attachment-<id>`）、`referenceOwnership`（`composer`|`session`）、
  `staged`、`adopted`、`autoRetryCount`、`runtimeRebuildRetryCount`、`showComplete`、`localZeroCopy`、`attachmentRef`。

---

## 3. 附件读取与文件预览

### 3.1 三条读取接口（客户端封装在主包 @1,112,200 区）

| 接口 | 用途 | 关键约束 |
| --- | --- | --- |
| `attachmentPreviewSourceV4` | 只对 **`video/*` 且宿主提供了 `createLocalMediaPreviewUrl`** 时先探一次；返回 `{kind:'local_path'}` 则直接用本地 URL 播放，**不拉字节** | 主包 @1,112,371 |
| `attachmentRead`（循环 `attachmentReadV4`） | 整份读；`limit = W.attachmentChunkMaxBytes`（**512 KiB**），`offset` 每轮推进到 `nextOffset`；轮数上限 `attachmentPreviewMaxChunks`（**60**）；全程 6 条一致性护栏（见 §2.8） | @1,112,837 |
| `attachmentReadRange` | 区间读（同一 RPC，`offset`/`limit` 由调用方给）；返回 `{bytes, mediaType, totalBytes, nextOffset}` | @1,113,694 |

### 3.2 预览 chunk 分类全集（**必答 7**，全部来自 `Reference_web/3.15.3/_manifest.json`，含体积与引用方）

| 类型 | chunk（新 hash） | 体积 | 引用方 |
| --- | --- | --- | --- |
| Office 表格 xlsx | `previewPaneOfficeXlsxContent-Bw1ZhcuV.js`（+ `xlsx-worker-6QzQDoZw.js` 132,116 B） | 2,154,677 B | index-BGa3KiRe.js |
| Office 演示 pptx | `pptx-preview-viewer-BNMMq7MX.js` → `pptxRendererPreviewEngine-BCGbMo2P.js`（+ `presentationPdfPrintExport-Cu-amNHF.js`） | 16,844 B → 1,042,714 B | index → pptx-preview-viewer |
| Office 旧版 doc | `previewPaneOfficeLegacyDocContent-CqYSjzSV.js`（+ `docx-import-worker-C1uoGLsA.js` 14,828 B + `docx_wasm_bg-HWnIvdMf.wasm` 1,119,333 B） | 900,568 B | index-BGa3KiRe.js |
| Office docx | `previewPaneOfficeDocxContent-BLMrfzIu.js` | 174,627 B | index-BGa3KiRe.js |
| PDF | `pdf-viewer-BomvfTjX.js`（+ `pdf-viewer-Dgb4oCkS.css` 1,498 B）→ `pdf.worker.min-qwK7q_zL.mjs` | 427,000 B → 1,046,214 B | index → pdf-viewer |
| diff | `diffs.worker-CAavpt0L.js`（**Web Worker**） | 827,865 B | index-BGa3KiRe.js |
| 图表 | `chart-CIHO61XL.js` 261,023 B · `cytoscape.esm-DyTbCmLR.js` 434,143 B · `xychartDiagram-5P7HB3ND-DFvS3QFg.js` 38,761 B | — | index-BGa3KiRe.js |
| 公式 | `katex-DefY6ydN.js` | 257,047 B | **`index.html`（在 modulepreload 图里，不是懒加载）** |
| mermaid | `mermaid-DoeU2fQJ.js` / `mermaid-DvJuNNke.js` 各 29,504 B + `mermaid-parser.core-oLgrNfH7.js` 4,533 B | — | index / chunk-BO2N2NFS |
| 预览壳（小 wrapper） | `previewPaneOfficeContent-DzPas5fy.js` 78 B · `previewPanePptxContent-wzh3BN1q.js` 72 B · `previewPanePdfContent-CmrWsWpH.js` 71 B · `officeFilePreview-Bs0TmYyT.js` 3,314 B | — | index / index.html |
| 缩略图 | `pdf-CQM2rvTK.png` 9,819 B · `pdf@2x-CMAdO9B0.png` 9,748 B | — | index-BGa3KiRe.js |

### 3.3 加载方式

- **入口 HTML 预加载图**：`index.html` 里 1 个 `type="module"` 主脚本 + **40 余条 `<link rel="modulepreload">`**，
  其中**包含 `katex-DefY6ydN.js` 与 `chunk-BO2N2NFS-CP8HYAja.js`（mermaid 的引用方）** ——
  即 **KaTeX 与 mermaid 基础件在首屏就被拉**，不是交互后才加载（旧基线把公式列为懒加载 chunk，**此处修正**）。
- **其余预览件走动态 import**：由主包里的 chunk 清单（主包 @59,413 区那串 `assets/…js` 数组）按需 `import()`；
  体积最大的 xlsx/pptx/doc 引擎都是**点开预览才拉**。
- **Worker 三处**：`diffs.worker-CAavpt0L.js`、`xlsx-worker-6QzQDoZw.js`、`docx-import-worker-C1uoGLsA.js`、
  `workspaceFileSearchFilter.worker-OQwPzeVM.js`（最后一个属文件搜索，不属预览）。

> 对壳的意义不变：**页面"空闲时"与"预览时"的网络活动完全不同**，诊断时不要把 chunk 加载误判为异常
> （完整分类见 [15 架构与模块地图](15-remote-v4-网页架构与模块地图.md) §1.2）。

---

## 4. 与参考实现的差异（Rust `rust/crates/protocol` 交叉校验）

只读核对，**未改动任何 Rust/Kotlin 文件**。参考实现：
`rust/crates/protocol/src/conversation.rs`（分片数学 + 读取状态机）与
`rust/crates/protocol/src/conversation_transport.rs`（上传状态机）。
下表 Rust 列统一给 `文件:行`（`CT` = `conversation_transport.rs`，`C` = `conversation.rs`）。

| # | 项 | 网页 3.15.3 | Rust | 判定 |
| --- | --- | --- | --- | --- |
| 1 | 分片大小 | `Aw = 384*1024`（主包 @1,103,781） | `ATTACHMENT_CHUNK_BYTES = 384 * 1024`（**C:87**，测试断言 `393_216` 在 **C:3494**） | ✅ 一致 |
| 2 | 分片区间 | `subarray(n*Aw, min(n*Aw+Aw, len))`（主包 @1,106,096 / @1,106,156） | `attachment_chunk_range(...)`（**C:816**），调用点 **CT:2676** | ✅ 一致 |
| 3 | `uploadId` | `upload-${crypto.randomUUID()}`（主包 @1,104,816） | `format!("upload-{uuid}")`（**C:794**），调用点 **CT:2723** | ✅ 一致 |
| 4 | `checksum` | `sha256:` + 64 位小写 hex（主包 @1,104,7xx 区） | 同（**C:799**），调用点 **CT:2725** | ✅ 一致 |
| 5 | `totalChunks` | `Math.ceil(len/Aw)`（主包 @1,105,592） | `(n + CHUNK - 1) / CHUNK`（**C:811**），调用点 **CT:2724** | ✅ 一致 |
| 6 | 方法名常量 | `attachmentBeginV4` / `ChunkV4` / `CommitV4` / `AbortV4`（主包 @1,105,633 / @1,106,190 / @1,106,544 / @1,106,623） | `ATTACHMENT_BEGIN_METHOD` **CT:123**、`CHUNK` **CT:125**、`COMMIT` **CT:127**、`READ` **CT:129**；**无 ABORT 常量** | ❌ 缺 1 个 |
| 7 | **进度事件形状** | `{phase:'uploading'\|'committing', uploadedBytes, totalBytes}`（主包 @1,105,988 / @1,106,058 / @1,106,366） | `AttachmentProgress{token, session_id, upload_id, progress: f64}`（**CT:415**，发射点 **CT:2691 / CT:2964 / CT:3001**）；`attachment_progress()` 返回浮点比例（**C:871**，调用点 **CT:3005**） | ❌ **形状不兼容**：Rust 无 `phase`、无字节数；网页侧无法只靠 Rust 事件区分"传输中/提交中" |
| 8 | 秒传的进度 | 命中 `committed` 时**也发**一次 `{phase:'committing'}`（主包 @1,105,724） | `Committed` 分支发浮点 `progress`（**CT:2964**，判定在 **CT:2957**→**C:843**） | ⚠️ 语义近似但形状不同 |
| 9 | **abort** | 有 `attachmentAbortV4`，begin 成功后任何失败都 best-effort abort（主包 @1,106,623 / @1,106,677） | **完全没有**（`grep attachmentAbort/AbortV4` 在 `rust/crates/protocol/src/` = 0 命中；上传失败路径 **CT:2752** 直接 `keep = false` 丢弃流程） | ❌ **缺失**：Rust 侧失败后服务端会留 staging 直到 `attachmentUploadTtlMs`（5 min）自然过期 |
| 10 | begin 应答校验 | `state` 必须是 `staging`/`committed`；`nextChunkIndex > totalChunks` → `fault.attachment.invalidServerProgress`（主包 @1,105,856） | `attachment_begin_decision`（**C:843**，调用点 **CT:2957**）只判 `state=='committed'`，其余按 `nextChunkIndex ?? 0`，**不校验越界**；`Chunked` 分支直接 `up.next_chunk = next_chunk_index`（**CT:2980**） | ❌ **更宽松**：服务端返回越界值时 Rust 会静默跳过所有分片直接 commit（因为 **CT:2674** 的 `next_chunk < total_chunks` 不成立） |
| 11 | chunk 应答校验 | `nextChunkIndex !== n+1` → `fault.attachment.invalidServerProgress`（主包 @1,106,243） | `validate_attachment_progress` 同（**C:861**，调用点 **CT:2995**） | ✅ 一致 |
| 12 | 上传载荷字段名 | begin = `{connectionId,uploadId,sessionId,fileName,mime,totalBytes,totalChunks,checksum}`；chunk 追加 `chunkIndex,dataBase64`；commit/abort = `{connectionId,uploadId,sessionId}` | `attachment_begin_args`（**C:933**，调用点 **CT:2741**）、`attachment_chunk_args`（**C:950**，**CT:2678**）、`attachment_commit_args`（**C:975**，**CT:2697**） | ✅ 一致（字段名逐个对齐） |
| 13 | 读 chunk 的 `limit` | `W.attachmentChunkMaxBytes` = **512 KiB**（主包 @1,113,011） | 固定 `ATTACHMENT_CHUNK_BYTES` = **384 KiB**（**C:1001**，调用点 **CT:2762**） | ⚠️ 数值不同（都在服务端 512 KiB 上限内，但 Rust 多花约 1/3 轮次） |
| 14 | 读轮数上限 | `W.attachmentPreviewMaxChunks` = **60**（validation @117,768） | `ATTACHMENT_MAX_READ_ROUNDS` = **1024**（**C:89**，`should_continue` 在 **C:1032**） | ⚠️ 不同：Rust 不设 30 MiB / 60 轮这类业务闸门 |
| 15 | 读一致性护栏 | 6 条（`previewChangedDuringRead` / `previewSizeMismatch` / `previewOffsetMismatch` / `previewTruncated` / `previewEmptyChunk` / `previewTooManyChunks`） | **无**：`AttachmentReader::feed` 只在 `next <= offset` 时终止（**C:1062**），不比对 `totalBytes`/`mediaType` | ❌ 缺失：字节被中途改写/截断时 Rust 不会报错 |
| 16 | 本地视频预览直通 | `attachmentPreviewSourceV4` → `{kind:'local_path'}`（主包 @1,112,371） | 无该方法（**CT:129** 只有 READ） | ❌ 缺失：远端视频只能整段拉字节 |
| 17 | 本地零拷贝上传 | `localPath` 且无远端会话 → `ref` 直接用本地路径、**零上传**（主包 @2,110,953） | 无此分支（上传入口 **CT:1581** `"attachmentPut"` 要求 `dataBase64`） | ❌ 缺失 |
| 18 | 附件记录/重试编排 | UI store：并发 2、自动重试 1 次（500 ms）、运行时重启重试 ≤5、8 文件/消息 | 无（Rust 只到"上传状态机"层，`UploadFlow` 见 **CT:749–765**） | ⚠️ 层不同，非缺陷，但壳侧若照抄 Rust 会缺重试与限流 |

**给移植的优先建议（按风险排序）**：
① 补 `attachmentAbortV4`（否则失败上传占服务端 staging 5 分钟）；
② begin 应答加 `nextChunkIndex > totalChunks` 越界校验（当前会静默跳过全部分片）；
③ 进度事件改为带 `phase` 与字节数的对象（否则无法与网页侧 UI/日志口径对齐）；
④ 读路径补一致性护栏与轮数/字节闸门；
⑤ 读 `limit` 与网页对齐到 512 KiB（可选，属性能优化）。

---

## 5. 对壳（与鸿蒙）的意义

| 面 | 壳侧对应 | 注意 |
| --- | --- | --- |
| 文件选择器 | 安卓 `onShowFileChooser` + `UploadMime.normalisePlatform`；鸿蒙 `onShowFileSelector` + 后缀白名单 | 页面侧的文件选择走 `selectFiles()` / `selectFile()` 桥（主包 @2,108,925 / @2,108,967，`Djt` @2,108,9xx 收集）；**新构建里检索不到 `acceptTypes` 标识**（旧基线记录的该转换名在当前构建不存在）。`accept` 属性→MIME 的转换属**运行时 DOM 面**，静态检索看不到 —— 仍记为"需一次真机核" |
| 上传通道 | 壳**不参与**上传 | 三条路径里 ① 本地零拷贝 ② 远端物化（桌面侧读文件）③ 分片上传，**全在页面/桌面侧**；壳只提供文件选择 |
| 上传进度 | 若要镜像进度，读 `window.__zcodeComposerAttachmentUploadStoreE2E`（仅 E2E 构建暴露，生产可能不存在） | 更稳的做法是观察 DOM 上的附件卡状态，而不是依赖该 store |
| 草稿键 | 壳**不读不写** `*-composer-drafts:*` | 与"回前台恢复"无关（承重的是 `history.state`，见 [21 篇](21-回前台对话恢复-页面机制与真机事实.md)） |
| `editorStateJson` | 壳**不要试图解析/改写** | 页面必须 `parseEditorState` 成功才能还原富文本；改写会静默降级成纯文本 |
| 预览 chunk | 壳**零引用** | 页面重建换 hash 不影响壳（成本模型见 [28 篇](28-remote-v4-网页更新影响-壳健壮性与重适配成本.md)）；注意 **KaTeX/mermaid 在首屏预加载图里**，别把它当异常流量 |
| 附件读取 | 壳**不参与** | `attachmentReadV4` / `attachmentPreviewSourceV4` 全在页面侧；但**视频预览依赖宿主提供 `createLocalMediaPreviewUrl`**（主包 @1,112,3xx 区），壳若不注入该能力，视频会退化成"整段拉字节" |

---

## 6. 复现

```bash
cd E:/Zcode_android/Reference_web/3.15.3/assets

# ① 分片常量（384 KiB，客户端侧）
node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');console.log(b.indexOf('var Aw=384*1024'))"
node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');const i=b.indexOf('var Aw=384*1024');process.stdout.write(b.subarray(i-100,i+900).toString())"

# ② 上传主流程（begin→chunk→commit→abort，含秒传/续传/进度）
node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');const i=b.indexOf('async function T1e(');process.stdout.write(b.subarray(i-2500,i+2600).toString())"

# ③ 附件 RPC 的 Zod schema（begin/chunk/commit/abort/read/previewSource/stat 全集）
node -e "const b=require('fs').readFileSync('parts-DcYGwy2q.js');process.stdout.write(b.subarray(18300,23100).toString())"

# ④ 协议常量（分片/上限/TTL）
node -e "const b=require('fs').readFileSync('validation-CqXZOLXO.js');const i=b.indexOf('attachmentMaxBytes');process.stdout.write(b.subarray(i-620,i+300).toString())"

# ⑤ 两套草稿键（B 套完整 CRUD；A 套只有读/删）
node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');const i=b.indexOf('var qvt=');process.stdout.write(b.subarray(i,i+1600).toString())"
node -e "const b=require('fs').readFileSync('index-BGa3KiRe.js');const i=b.indexOf('var X9e=');process.stdout.write(b.subarray(i,i+1100).toString())"

# ⑥ 草稿条数上限复核（应全为 0 命中）
node -e "const fs=require('fs');const ks=['MAX_DRAFTS','DRAFTS_MAX','maxDrafts','draftMax'];let n=0;for(const f of fs.readdirSync('.').filter(f=>f.endsWith('.js'))){const b=fs.readFileSync(f);for(const k of ks)if(b.indexOf(k)>=0){console.log(f,k);n++}}console.log('hits',n)"

# ⑦ 预览 chunk 体积与引用方
node -e "const j=require('./../_manifest.json');for(const r of j.records)if(/preview|pdf|pptx|docx|xlsx|diffs|katex|mermaid|cytoscape|chart-C/.test(r.file))console.log(r.bytes,r.file,'<-',r.referencedBy)"
```

---

## 7. 出处

- 快照：`E:\Zcode_android\Reference_web\3.15.3\`（2026-10-15 19:52 抓取，`_manifest.json` 2,578 条记录）。
- 桌面端交叉校验（**仅用于增强可信度**，非本篇主体）：`app.asar` `out/host/index.js` @264,857 区（逐字段投影）、
  `out/host/chunk-T2GB4I3K.js` @66,759 区（方法代理）、`out/renderer/assets/styles-CSpM3qgc.js` @1,073,858 区
  （与网页端**逐字节同源**的上传实现）。
- 参考实现（只读）：`rust/crates/protocol/src/conversation.rs`、`rust/crates/protocol/src/conversation_transport.rs`。
- 相关篇目：[14 bootstrap 字段表（含 `attachmentMaxBytes`）](14-remote-v4-bootstrap载荷-完整字段表.md) ·
  [15 架构与模块地图（chunk 分类）](15-remote-v4-网页架构与模块地图.md) ·
  [16 relay 协议目录](16-remote-v4-relay协议目录.md) ·
  [18 对话渲染](18-remote-v4-功能域-对话渲染与流式更新.md) ·
  [21 回前台对话恢复](21-回前台对话恢复-页面机制与真机事实.md) ·
  [28 网页更新影响与重适配成本](28-remote-v4-网页更新影响-壳健壮性与重适配成本.md)。
